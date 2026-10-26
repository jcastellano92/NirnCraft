// Vulkan compute backend for the atlas_far kernel.
//  * Host path (harness, tests): scene in host-visible buffers (BAR when available), outputs read back.
//  * GL path (game), zero-copy: outputs published into images whose memory is exported to the game's OpenGL context
//    (VK_KHR_external_memory_win32 <-> GL_EXT_memory_object_win32 on Windows, VK_KHR_external_memory_fd <->
//    GL_EXT_memory_object_fd on Linux), ordered by shared semaphores, so the frame never touches the CPU. Two frame
//    slots: the CPU records frame N+1 while the GPU still runs frame N.
//  * GL path, host-copy fallback (no interop extensions, formats not exportable, or the GL context on another GPU):
//    the same images are local to Vulkan, copied to host memory and uploaded into plain GL textures.
// Baseline: Vulkan 1.2 + shaderInt64 (the SPIR-V is 1.5 with only the Shader and Int64 capabilities).
#include "vk_loader.hpp"  // first: VK_NO_PROTOTYPES, the loader is opened at run time
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan_win32.h>
#else
#include <unistd.h>
#endif

#include <algorithm>
#include <atomic>
#include <thread>
#include <cctype>
#include <chrono>
#include <deque>
#include <cstdio>
#include <mutex>
#include <cstdlib>
#ifdef _WIN32
#include <process.h>
#else
#include <unistd.h>
#endif
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "flight_recorder.hpp"  // the flight recorder: zones and GPU timestamps
#include "atlas_cloud_spv.h"
#include "atlas_sunmap_spv.h"    // generated: the same kernel source's cloudMain entry
#include "atlas_nearlight_spv.h"  // generated: its nearLightMain and nearUpsampleMain (the half-resolution near light)
#include "atlas_nearup_spv.h"
#include "atlas_neartemporal_spv.h"
#include "atlas_lightcull_spv.h"   // generated: the far lights culled, admitted and binned on the GPU
#include "atlas_lightadmit_spv.h"
#include "atlas_lightbin_spv.h"
#include "atlas_gridlight_spv.h"
#include "atlas_far_spv.h"      // generated: atlas_far_spv[], atlas_far_spv_size (bytes)
#include "atlas_farsplit_spv.h" // generated: farMain for the unified field's split frames (ATLAS_FAR_SPLIT)
#include "atlas_ingest_spv.h"   // generated
#include "atlas_publish_spv.h"  // generated
#include "atlas_vg_section_spv.h"  // generated: the level-0 grid's build kernels (atlas_voxgrid.slang)
#include "atlas_vg_satx_spv.h"
#include "atlas_vg_satz_spv.h"
#include "atlas_vg_saty_spv.h"
#include "atlas_vg_grow_spv.h"
#include "atlas_vg_fill_spv.h"
#include "atlas_vg_ingest_spv.h"
#include "atlas_vg_down_spv.h"
#include "atlas_vg_pack_spv.h"
#include "atlas_vg_unpack_spv.h"
#include "atlas_vg_cand_spv.h"
#include "atlas_vg_rank_spv.h"
#include "atlas_vg_rankcut_spv.h"
#include "atlas_vg_ranklist_spv.h"
#include "atlas/atlas.h"  // (atlas_pipeline_progress)
#include "backend.hpp"
#include "gl_api.hpp"
#include "gl_near.hpp"
#include "sectionpack.hpp"
#include "voxgrid.hpp"

namespace atlas {
// (atlas_pipeline_progress) the context's pipelines as the backend builds them: done of total (its list of kernels,
// counted at its first build: the loading screen's real progress), the one being built, the split frames' far kernel
namespace pipeprog {
std::atomic<uint32_t> done{0}, total{0}, split{0};
std::mutex mu;
char name[96] = "";
// the Vulkan backend's kernels built during atlas_create (corrected at its end if the list changed)
constexpr uint32_t INIT_KERNELS = 26;
}  // namespace pipeprog
namespace {

// ---- platform shim: the one place the two interop flavours differ -------------------------------------------------
#if defined(_WIN32)
using ExtHandle = HANDLE;
const ExtHandle NO_HANDLE = nullptr;
constexpr VkExternalMemoryHandleTypeFlagBits MEM_HANDLE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
constexpr VkExternalSemaphoreHandleTypeFlagBits SEM_HANDLE = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
const char* const MEM_EXT = VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
const char* const SEM_EXT = VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
const char* const INTEROP_NAME = "zero-copy (win32 handles)";
const char* const GL_INTEROP_EXTS = "GL_EXT_memory_object_win32 + GL_EXT_semaphore_win32";
const char* const GL_MEM_EXT = "GL_EXT_memory_object_win32";
const char* const GL_SEM_EXT = "GL_EXT_semaphore_win32";
void closeHandle(ExtHandle& h) {
    if (h) CloseHandle(h);
    h = nullptr;
}
bool glHasInterop(const GlApi& gl) { return gl.memoryObjectWin32 && gl.semaphoreWin32; }

struct ExportFns {
    PFN_vkGetMemoryWin32HandleKHR mem = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR sem = nullptr;
    bool load(VkDevice d) {
        mem = reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(vkGetDeviceProcAddr(d, "vkGetMemoryWin32HandleKHR"));
        sem = reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(vkGetDeviceProcAddr(d, "vkGetSemaphoreWin32HandleKHR"));
        return mem && sem;
    }
    bool memory(VkDevice d, VkDeviceMemory m, ExtHandle& out) const {
        VkMemoryGetWin32HandleInfoKHR hi{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
        hi.memory = m;
        hi.handleType = MEM_HANDLE;
        return mem(d, &hi, &out) == VK_SUCCESS;
    }
    bool semaphore(VkDevice d, VkSemaphore s, ExtHandle& out) const {
        VkSemaphoreGetWin32HandleInfoKHR hi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
        hi.semaphore = s;
        hi.handleType = SEM_HANDLE;
        return sem(d, &hi, &out) == VK_SUCCESS;
    }
};
// GL duplicates nothing: the handle stays ours and is closed when the image goes
void glImportMemory(const GlApi& gl, GLuint obj, uint64_t size, ExtHandle& h) {
    gl.ImportMemoryWin32HandleEXT(obj, size, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, h);
}
void glImportSemaphore(const GlApi& gl, GLuint obj, ExtHandle& h) {
    gl.ImportSemaphoreWin32HandleEXT(obj, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, h);
}
#else
using ExtHandle = int;
const ExtHandle NO_HANDLE = -1;
constexpr VkExternalMemoryHandleTypeFlagBits MEM_HANDLE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
constexpr VkExternalSemaphoreHandleTypeFlagBits SEM_HANDLE = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
const char* const MEM_EXT = VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
const char* const SEM_EXT = VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
const char* const INTEROP_NAME = "zero-copy (opaque fds)";
const char* const GL_INTEROP_EXTS = "GL_EXT_memory_object_fd + GL_EXT_semaphore_fd";
const char* const GL_MEM_EXT = "GL_EXT_memory_object_fd";
const char* const GL_SEM_EXT = "GL_EXT_semaphore_fd";
void closeHandle(ExtHandle& h) {
    if (h >= 0) close(h);
    h = -1;
}
bool glHasInterop(const GlApi& gl) { return gl.memoryObjectFd && gl.semaphoreFd; }

struct ExportFns {
    PFN_vkGetMemoryFdKHR mem = nullptr;
    PFN_vkGetSemaphoreFdKHR sem = nullptr;
    bool load(VkDevice d) {
        mem = reinterpret_cast<PFN_vkGetMemoryFdKHR>(vkGetDeviceProcAddr(d, "vkGetMemoryFdKHR"));
        sem = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(vkGetDeviceProcAddr(d, "vkGetSemaphoreFdKHR"));
        return mem && sem;
    }
    bool memory(VkDevice d, VkDeviceMemory m, ExtHandle& out) const {
        VkMemoryGetFdInfoKHR hi{VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
        hi.memory = m;
        hi.handleType = MEM_HANDLE;
        return mem(d, &hi, &out) == VK_SUCCESS;
    }
    bool semaphore(VkDevice d, VkSemaphore s, ExtHandle& out) const {
        VkSemaphoreGetFdInfoKHR hi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
        hi.semaphore = s;
        hi.handleType = SEM_HANDLE;
        return sem(d, &hi, &out) == VK_SUCCESS;
    }
};
// A successful fd import transfers the fd to GL (it must not be closed or reused); a failed one leaves it ours.
void glImportMemory(const GlApi& gl, GLuint obj, uint64_t size, ExtHandle& h) {
    gl.ImportMemoryFdEXT(obj, size, GL_HANDLE_TYPE_OPAQUE_FD_EXT, h);
    if (gl.GetError() == GL_NO_ERROR) h = -1;
}
void glImportSemaphore(const GlApi& gl, GLuint obj, ExtHandle& h) {
    gl.ImportSemaphoreFdEXT(obj, GL_HANDLE_TYPE_OPAQUE_FD_EXT, h);
    if (gl.GetError() == GL_NO_ERROR) h = -1;
}
#endif

enum class Interop { None, ZeroCopy, HostCopy };

std::string jsonEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if (static_cast<unsigned char>(c) < 0x20) continue;
        o += c;
    }
    return o;
}

std::string hex16(const uint8_t* u) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (int i = 0; i < 16; i++) {
        s += d[u[i] >> 4];
        s += d[u[i] & 15];
    }
    return s;
}

std::string apiString(uint32_t v) {
    return std::to_string(VK_API_VERSION_MAJOR(v)) + "." + std::to_string(VK_API_VERSION_MINOR(v)) + "." +
           std::to_string(VK_API_VERSION_PATCH(v));
}

const char* typeName(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

constexpr uint32_t BINDINGS = 43;
constexpr uint32_t SLOTS = 2;
// far kernel bindings
constexpr uint32_t B_FRAME = 0, B_PALETTE = 7, B_COLOR = 8, B_NORMAL = 9, B_DEPTH = 10, B_WEATHER = 11,
                   B_COUNTERS = 12, B_NOISE = 13, B_NEAR = 14, B_ACTORS = 15, B_HEIGHTMIP = 16, B_CLOUD = 17,
                   B_CLOUD_DEPTH = 18, B_PREV_SURFACE = 19, B_SURFACE = 20, B_NOISE_TEX = 21, B_PREV_CLOUD = 22,
                   B_PREV_CLOUD_DEPTH = 23, B_SUNMAP = 24, B_GROUPS = 25, B_NEAR_INFO = 26, B_NEAR_LIGHT = 27,
                   B_PREV_NEAR_LIGHT = 28, B_VG_CHUNKS = 29, B_VG_BLOCKS = 30, B_VG_BITS = 31, B_VG_POOL = 32,
                   B_TEX = 33,  // (abi 0.16) the near field's textures (voxgrid.hpp packTextures)
                   // the far lights on the GPU (FRAME_GPU_LIGHTS_BIT): the index (shared, uploaded when it changes), this
                   // frame's candidates and its tile lists (atlas_far.slang lightCullMain)
                   B_LIGHT_SRC = 34, B_LIGHT_CAND = 35, B_LIGHT_LIST = 36,
                   // the near light's own samples per pixel in its history (nearTemporalMain), this frame's and the last's
                   B_NEAR_AGE = 37, B_PREV_NEAR_AGE = 38,
                   // (the split under PRIMARY_NEAR_LIT) the water over each pixel's surface (farMain), for nearTemporalMain
                   B_WATER_ADD = 39,
                   // (abi 0.18) the micro-voxel bricks (bricks.hpp BrickStore::pack; atlas_bricks.slang)
                   B_BRICKS = 40,
                   // (FRAME_FAR_AMORT_BIT) each pixel's primary hit, this frame's and the last's (atlas_far.slang farHit)
                   B_FAR_HIT = 41, B_PREV_FAR_HIT = 42;
// lightList's words before the tiles' (counters, the score histogram) and a tile's (count + 64 indices)
constexpr uint32_t LIGHT_LIST_HEAD = 260, LIGHT_TILE_WORDS = 65;
// gridLightMain (atlas_bricks.slang): its threads (GRID_LIGHT_THREADS: 4 x 4 x 4 blocks each of 192 x 96 x 192) and the
// candidates' cap (atlas_far.slang LIGHT_CAND_CAP)
constexpr uint32_t GRID_LIGHT_THREADS = 48u * 48u * 24u, GRID_LIGHT_CAND_CAP = 65536u;
// the grid's build kernels (atlas_voxgrid.slang): chunks, blocks, bits, pool, jobs, summed-volume table, the batch's
// sections' raw ids (the coarse levels' ingest reads them where they were staged), and those that were on the GPU
// already (VoxGrid::ingestGpu), copied together; packed sections staged to expand, and those packed for the host
constexpr uint32_t VG_BINDINGS = 12;  // (+ the waiting flags and the ranking's lists: residency on the GPU)
// the ranking's buffer (atlas_voxgrid.slang RANK_*): histograms and counts, then the admit and evict lists
constexpr uint32_t RANK_LISTS = 1544, RANK_MAXL = 4096, RANK_WORDS = RANK_LISTS + 2 * RANK_MAXL;
// the scene's buffers (two copies, staged off the render thread)
constexpr uint32_t SCENE_BINDINGS[] = {1, 2, 3, 4, 5, 6, B_HEIGHTMIP, B_GROUPS};
bool isScene(uint32_t b) { return (b >= 1 && b <= 6) || b == B_HEIGHTMIP || b == B_GROUPS; }
// shared images (the near info and near light: abi 0.15, FRAME_NEAR_LIGHT)
constexpr int IMG_COLOR = 0, IMG_NORMAL = 1, IMG_DEPTH = 2, IMG_NEAR = 3, IMG_NEAR_INFO = 4, IMG_NEAR_LIGHT = 5,
              IMAGES = 6;
// push constants of the publish and ingest passes: width, height, and whether the near light / info rides along
constexpr uint32_t PASS_PUSH_BYTES = 16;  // width, height, nearLight, pre-exposure (float bits)

// The GL compute pass that writes the shared nearDepth / nearInfo images: gl_near.hpp (both backends' GL paths). Same
// bottom-up rows as GL; the Vulkan ingest pass flips them for the far kernel.

// the split under PRIMARY_NEAR_LIT: waterAdd is a buffer per pixel, the publish pass hands its water surface on
bool unifiedSplit(const FrameGpu& frame) {
    return (frame.dims[2] & FRAME_NEAR_SPLIT_BIT) && (frame.dims[2] & 0x100000u);  // (ATLAS_FRAME_PRIMARY_NEAR_LIT)
}

bool perSlot(uint32_t b) {
    return b == B_FRAME || b == B_PALETTE || b == B_COLOR || b == B_NORMAL || b == B_DEPTH || b == B_WEATHER ||
           b == B_COUNTERS || b == B_NEAR || b == B_ACTORS || b == B_CLOUD || b == B_CLOUD_DEPTH || b == B_SURFACE ||
           b == B_NEAR_INFO || b == B_NEAR_LIGHT || b == B_LIGHT_CAND || b == B_LIGHT_LIST || b == B_NEAR_AGE || b == B_WATER_ADD ||
           b == B_FAR_HIT;
}

VKAPI_ATTR VkBool32 VKAPI_CALL onValidation(VkDebugUtilsMessageSeverityFlagBitsEXT sev, VkDebugUtilsMessageTypeFlagsEXT,
                                            const VkDebugUtilsMessengerCallbackDataEXT* d, void*) {
    std::fprintf(stderr, "[vulkan %s] %s\n", sev >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? "error" : "warning",
                 d->pMessage);
    return VK_FALSE;
}

// Where a buffer lives. The host-visible device-local (BAR) heap is often only 214-256 MB without Resizable BAR, so
// nothing big may depend on it: frame outputs and the scene are device-local, filled by GPU copies.
// HostRead: a readback target only copied into, never bound (no descriptor range limit)
enum class Mem { HostWrite, Readback, DeviceOnly, Staging, HostRead };

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
    VkDeviceSize memBytes = 0;  // of its allocation (atlas_memory_info's atlasBytes)
    Mem kind = Mem::HostWrite;
};

// Fence waits: far longer than any frame or upload atlas makes (a 4K frame on an iGPU is ~100 ms), shorter than a
// player waits for a frozen game. Windows resets a GPU stuck for 2 s (TDR) and amdgpu after 10 s; either way the wait
// returns VK_ERROR_DEVICE_LOST, and a wait that runs out first means a hung device, treated as lost.
constexpr uint64_t FENCE_TIMEOUT_NS = 5'000'000'000ull;

const char* vkResultName(VkResult r) {
    switch (r) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_OUT_OF_POOL_MEMORY: return "VK_ERROR_OUT_OF_POOL_MEMORY";
        case VK_ERROR_FRAGMENTED_POOL: return "VK_ERROR_FRAGMENTED_POOL";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
        default: return "VkResult";
    }
}

// What a failed VkResult means for the context (atlas.h AtlasStatus).
Fault classify(VkResult r) {
    switch (r) {
        case VK_SUCCESS: return Fault::None;
        case VK_ERROR_DEVICE_LOST: return Fault::DeviceLost;
        case VK_TIMEOUT: return Fault::Timeout;
        case VK_ERROR_OUT_OF_HOST_MEMORY:
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:
        case VK_ERROR_OUT_OF_POOL_MEMORY:
        case VK_ERROR_FRAGMENTED_POOL:
        case VK_ERROR_TOO_MANY_OBJECTS:
        case VK_ERROR_MEMORY_MAP_FAILED: return Fault::OutOfMemory;
        default: return Fault::Error;
    }
}

struct SharedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    ExtHandle handle = NO_HANDLE;
    VkDeviceSize size = 0;        // of its (dedicated) allocation: what GL imports
    GLuint glMem = 0, glTex = 0;  // host-copy: glTex is a plain GL texture, glMem 0
};

struct SharedSemaphore {
    VkSemaphore sem = VK_NULL_HANDLE;
    ExtHandle handle = NO_HANDLE;
    GLuint gl = 0;
};

// The scene's six buffers. Two copies: the update thread fills the one no in-flight frame reads, frames flip to it.
struct SceneCopy {
    Buf bufs[BINDINGS];  // the SCENE_BINDINGS entries, device-local
    Buf staging;  // host memory the staging thread writes; a GPU copy moves it into bufs
    VkCommandBuffer uploadCmd = VK_NULL_HANDLE;
    VkFence uploadFence = VK_NULL_HANDLE;
    bool uploading = false;  // a copy was submitted; usable once uploadFence signals
    uint64_t revision = UINT64_MAX;
    std::atomic<uint64_t> lastFrame{0};  // last frame number recorded against this copy
    uint32_t generation = 0;  // bumped when a buffer is reallocated (descriptor sets must be rewritten)
};

struct Slot {
    uint64_t frameNumber = 0;  // of its last submission
    VkBuffer boundPrevHit = VK_NULL_HANDLE;   // and its primary hits (FRAME_FAR_AMORT_BIT)
    bool hitFresh = false;                    // B_FAR_HIT newly allocated: filled with no hit in record
    int boundCopy = -1;        // scene copy its far descriptor set points at
    VkBuffer boundPrev = VK_NULL_HANDLE;  // the previous frame's surface buffer its cloud pass reads
    VkBuffer boundPrevNear = VK_NULL_HANDLE;  // and its near light (nearTemporalMain)
    bool surfaceFresh = true;  // its surface buffer was (re)allocated: clear to "no surface" before use
    uint32_t boundGeneration = 0;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool query = VK_NULL_HANDLE;
    fr::GpuTimeline gt;  // the flight recorder's per-pass timestamps of this slot's frame
    bool inFlight = false;
    VkDescriptorSet farSet = VK_NULL_HANDLE, publishSet = VK_NULL_HANDLE, ingestSet = VK_NULL_HANDLE;
    Buf bufs[BINDINGS];  // only the per-slot bindings are used
    bool farDirty = true, publishDirty = true, ingestDirty = true;
    SharedSemaphore glReady, vkDone;
    Buf hostOut;            // host-copy: this slot's frame in host memory (colour, normal, depth, sun map)
    bool hostPending = false;  // hostOut holds a frame not yet uploaded to GL
    bool hostSun = false;      // ... including the sun map
    bool hostNearLight = false;  // ... and the near light (abi 0.15)
    // the level-0 grid's build this frame (voxgrid.hpp): its sections and jobs in host memory, what to record
    Buf vgStage, vgJobs, vgGsrc;
    // (the GPU's own sections) level 0's jobs copied back after the build: those vgSectionMain found to be one id
    // (VoxGrid::gpuUniform), read when this slot comes round again; their cells' generations
    Buf vgResults;
    uint32_t vgResultN = 0;
    std::vector<uint32_t> vgGens;
    // (VoxGrid::ingestGpu) copies from the sections' own buffers: into vgGsrc (the coarse levels' ingest), into the pool
    struct VgGpuCopy {
        VkBuffer from;
        VkBufferCopy region;
        bool toPool;
    };
    std::vector<VgGpuCopy> vgGcopies;
    VkDescriptorSet vgSet = VK_NULL_HANDLE;
    std::vector<VkBufferCopy> vgCopies;
    uint32_t vgJobCount = 0;
    int vgOx = 0, vgOz = 0;  // the batch's window origin (sections)
    bool vgClear = false, vgStates = false, vgTimed = false;
    uint32_t vgSections = 0;
    // the coarse levels' jobs in vgJobs after the chunk jobs (uint4 offsets and counts), the class table, the levels
    uint32_t vgClearsAt = 0, vgClears = 0, vgFillsAt = 0, vgFills = 0, vgIngestAt = 0, vgIngests = 0;
    uint32_t vgDownAt[VG_LEVELS] = {}, vgDowns[VG_LEVELS] = {};
    uint32_t vgClassAt = 0, vgClassN = 0;
    // (the host's tiers) packed sections staged and expanded into vgGsrc after the GPU's own (then copied into level
    // 0's slots), and raw sections packed for the host (read back when this slot comes round again: their keys)
    Buf vgPackIn, vgPackOut, vgPackBack;
    uint32_t vgUnpackAt = 0, vgUnpacks = 0, vgPackAt = 0, vgPacks = 0, vgPackRead = 0;
    std::vector<VkBufferCopy> vgPackCopies;
    std::vector<std::pair<Key3, uint32_t>> vgPackKeys;
    // (residency on the GPU) the waiting flags' changes and the ranking's view in vgJobs; its lists read back
    Buf vgRankBack;
    uint32_t vgCandAt = 0, vgCands = 0, vgRankAt = 0, vgCells = 0, vgRankBudget = 0;
    bool vgRankOn = false, vgRankRead = false;
    struct VgLevelRec {
        bool clear = false, grow = false;
        uint32_t D = 0, H = 0, base = 0;
        int ox = 0, oz = 0;
    } vgLv[VG_LEVELS];
    // the far lights on the GPU this frame (FRAME_GPU_LIGHTS_BIT): the index's records, the screen's 32 px tiles
    bool gpuLights = false;
    uint32_t lightRecords = 0, lightTiles = 0;
    bool gridLights = false;  // gridLightMain this frame
};

class VulkanBackend final : public Backend {
public:
    ~VulkanBackend() override {
        if (fault_ == Fault::Timeout) {
            // a hung device may still run atlas's work: destroying what it uses (or waiting for it) could crash or
            // freeze the game, so everything is left to the driver and the process exit (a few hundred MB at most)
            std::fprintf(stderr, "[atlas] the GPU hung: the Vulkan device is abandoned, not destroyed\n");
            // (a thread left joinable would terminate the process: the builds only read what is never freed here)
            if (farSplitThread_.joinable()) farSplitThread_.detach();
            if (reloadThread_.joinable()) reloadThread_.detach();
            return;
        }
        if (dev_) {
            vkDeviceWaitIdle(dev_);  // after a loss it returns VK_ERROR_DEVICE_LOST at once
            destroyInterop();
            for (auto& s : slots_) {
                for (auto& b : s.bufs) release(b);
                release(s.hostOut);
                release(s.vgStage);
                release(s.vgJobs);
                release(s.vgGsrc);
                release(s.vgResults);
                release(s.vgPackIn);
                release(s.vgPackOut);
                release(s.vgPackBack);
                release(s.vgRankBack);
                if (s.fence) vkDestroyFence(dev_, s.fence, nullptr);
                if (s.query) vkDestroyQueryPool(dev_, s.query, nullptr);
                s.gt.destroy();
            }
            for (auto& b : shared_) release(b);
            release(vgSat_);
            release(vgCand_);
            release(vgRank_);
            for (Buf& b : debugBufs_) release(b);
            releaseRetired(true);
            for (auto& p : vgPipes_)
                if (p) vkDestroyPipeline(dev_, p, nullptr);
            for (auto& m : vgShaders_)
                if (m) vkDestroyShaderModule(dev_, m, nullptr);
            if (vgLayout_) vkDestroyPipelineLayout(dev_, vgLayout_, nullptr);
            if (vgSetLayout_) vkDestroyDescriptorSetLayout(dev_, vgSetLayout_, nullptr);
            release(sunReadback_);
            release(peek_);
            release(noiseStaging_);
            if (noiseSampler_) vkDestroySampler(dev_, noiseSampler_, nullptr);
            if (noiseView_) vkDestroyImageView(dev_, noiseView_, nullptr);
            if (noiseImg_) vkDestroyImage(dev_, noiseImg_, nullptr);
            if (noiseMem_) vkFreeMemory(dev_, noiseMem_, nullptr);
            for (auto& c : copies_) {
                for (auto& b : c.bufs) release(b);
                release(c.staging);
                if (c.uploadFence) vkDestroyFence(dev_, c.uploadFence, nullptr);
            }
            if (uploadPool_) vkDestroyCommandPool(dev_, uploadPool_, nullptr);
            if (pool_) vkDestroyCommandPool(dev_, pool_, nullptr);
            if (dpool_) vkDestroyDescriptorPool(dev_, dpool_, nullptr);
            if (reloadThread_.joinable()) reloadThread_.join();  // a shader reload still building
            if (farSplitThread_.joinable()) farSplitThread_.join();  // (the split frames' far kernel still building)
            if (farSplitPipe_) vkDestroyPipeline(dev_, farSplitPipe_, nullptr);
            if (farSplitShader_) vkDestroyShaderModule(dev_, farSplitShader_, nullptr);
            for (int i = 0; i < RELOAD_KERNELS; i++) {  // (built and never swapped in)
                if (reloadPipe_[i]) vkDestroyPipeline(dev_, reloadPipe_[i], nullptr);
                if (reloadMod_[i]) vkDestroyShaderModule(dev_, reloadMod_[i], nullptr);
            }
            if (cloudPipe_) vkDestroyPipeline(dev_, cloudPipe_, nullptr);
            if (cloudShader_) vkDestroyShaderModule(dev_, cloudShader_, nullptr);
            if (sunPipe_) vkDestroyPipeline(dev_, sunPipe_, nullptr);
            if (sunShader_) vkDestroyShaderModule(dev_, sunShader_, nullptr);
            if (nearLightPipe_) vkDestroyPipeline(dev_, nearLightPipe_, nullptr);
            if (nearLightShader_) vkDestroyShaderModule(dev_, nearLightShader_, nullptr);
            if (nearUpPipe_) vkDestroyPipeline(dev_, nearUpPipe_, nullptr);
            if (nearUpShader_) vkDestroyShaderModule(dev_, nearUpShader_, nullptr);
            if (nearTempPipe_) vkDestroyPipeline(dev_, nearTempPipe_, nullptr);
            if (nearTempShader_) vkDestroyShaderModule(dev_, nearTempShader_, nullptr);
            for (int i = 0; i < 4; i++) {
                if (lightPipe_[i]) vkDestroyPipeline(dev_, lightPipe_[i], nullptr);
                if (lightShader_[i]) vkDestroyShaderModule(dev_, lightShader_[i], nullptr);
            }
            destroyPipe(farPipe_, farLayout_, farSetLayout_, farShader_);
            destroyPipe(publishPipe_, publishLayout_, publishSetLayout_, publishShader_);
            destroyPipe(ingestPipe_, ingestLayout_, ingestSetLayout_, ingestShader_);
            if (pipeCache_) vkDestroyPipelineCache(dev_, pipeCache_, nullptr);
            vkDestroyDevice(dev_, nullptr);
        }
        if (messenger_) {
            auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(inst_, "vkDestroyDebugUtilsMessengerEXT"));
            if (destroy) destroy(inst_, messenger_, nullptr);
        }
        if (inst_ && vkDestroyInstance) vkDestroyInstance(inst_, nullptr);
    }

    const char* name() const override { return deviceName_; }
    bool onDevice() const override { return true; }
    uint64_t debugDeviceBuffer(const void* data, size_t bytes) override {
        debugBufs_.emplace_back();
        std::string err;
        if (!ensure(debugBufs_.back(), bytes, false, Mem::HostWrite, err) || !debugBufs_.back().map) return 0;
        std::memcpy(debugBufs_.back().map, data, bytes);
        return uint64_t(debugBufs_.back().buf);
    }
    // the far lights culled and binned on the device (ATLAS_CPU_LIGHTS=1: on the CPU, as before; measurements)
    bool gpuLights() const override {
        static const bool cpu = [] {
            const char* e = std::getenv("ATLAS_CPU_LIGHTS");
            return e && *e == '1';
        }();
        return !cpu;
    }

    std::string info() const override {
        auto q = [](const std::string& s) { return "\"" + jsonEscape(s) + "\""; };
        auto b = [](bool v) { return v ? std::string("true") : std::string("false"); };
        char vendor[16];
        std::snprintf(vendor, sizeof(vendor), "0x%04x", props_.vendorID);
        std::string o = "{\"backend\":\"vulkan\",\"device\":" + q(props_.deviceName) + ",\"type\":" +
                        q(typeName(props_.deviceType)) + ",\"vendor\":\"" + vendor + "\",\"driver\":" + q(driverName_) +
                        ",\"driverId\":" + std::to_string(driverId_) + ",\"api\":" + q(apiString(props_.apiVersion)) +
                        ",\"deviceUuid\":" + q(hex16(deviceUuid_)) + ",\"driverUuid\":" + q(hex16(driverUuid_)) +
                        ",\"pickedForGlContext\":" + b(glMatchedAtCreate_);
        const char* mode = mode_ == Interop::ZeroCopy ? INTEROP_NAME : mode_ == Interop::HostCopy ? "host-copy" : "not attached";
        o += ",\"interop\":" + q(mode);
        if (mode_ == Interop::HostCopy) {
            o += ",\"interopReason\":" + q(interopWhy_) + ",\"hostCopy\":{\"waitMs\":" + std::to_string(hostWaitMs_) +
                 ",\"uploadMs\":" + std::to_string(hostUploadMs_) + ",\"nearDepthSkip\":false}";
        }
        o += ",\"vkExport\":" + b(interopCapable_);
        if (!interopCapable_) o += ",\"vkExportReason\":" + q(vkInteropWhy_);
        o += ",\"dedicatedOnly\":" + b(dedicatedOnly_) + ",\"timestamps\":" + b(timestamps_) + ",\"genLowPriority\":" + b(genLowPriority_) + ",\"genOwnFamily\":" + b(genFamily_ != UINT32_MAX && genFamily_ != family_) +
             ",\"maxStorageBufferRange\":" + std::to_string(maxStorageRange_);
        if (glLoaded_) {
            o += ",\"gl\":{\"renderer\":" + q(glRenderer_) + ",\"version\":" + q(glVersion_) + ",\"vendor\":" + q(glVendor_) +
                 ",\"winsys\":" + q(glWinsys_) + ",\"memoryObject\":" + b(glHasInterop(gl_)) +
                 ",\"uuidMatch\":" + b(glUuidMatch_);
            if (!glDriverUuid_.empty()) o += ",\"driverUuid\":" + q(glDriverUuid_);
            o += "}";
        }
        o += ",\"devices\":[";
        for (size_t i = 0; i < seen_.size(); i++) {
            const DeviceSeen& d = seen_[i];
            o += std::string(i ? "," : "") + "{\"name\":" + q(d.name) + ",\"type\":" + q(d.type) + ",\"api\":" + q(d.api) +
                 ",\"glContext\":" + b(d.glMatch) + ",\"chosen\":" + b(int(i) == chosen_);
            if (!d.unfit.empty()) o += ",\"unfit\":" + q(d.unfit);
            o += "}";
        }
        return o + "]}";
    }

    bool init(std::string& err) {
        pipeprog::done.store(0);
        pipeprog::total.store(pipeprog::INIT_KERNELS);
        pipeprog::split.store(0);
        {
            std::lock_guard<std::mutex> l(pipeprog::mu);
            std::snprintf(pipeprog::name, sizeof pipeprog::name, "%s", "the device");  // (before its first kernel)
        }
        if (!createInstance(err) || !pickDevice(err) || !createDevice(err)) return false;
        probeInterop();
        // far kernel: binding 0 uniform, the rest storage buffers
        std::vector<VkDescriptorType> farTypes(BINDINGS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        farTypes[B_FRAME] = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        farTypes[B_NOISE_TEX] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        if (!createPipe(atlas_far_spv, atlas_far_spv_size, farTypes, 0, farPipe_, farLayout_, farSetLayout_, farShader_, err,
                        "farMain"))
            return false;
        // the split frames' far kernel (the game's unified field: atlas_far.slang ATLAS_FAR_SPLIT, every trace inlined, its
        // state in registers: about half the far pass's time). Its driver compile takes a minute or two the first time (the
        // pipeline cache keeps it), so it is built on a thread and the general far kernel draws until it is ready;
        // ATLAS_FAR_SPLIT_KERNEL=0: never
        if (!(std::getenv("ATLAS_FAR_SPLIT_KERNEL") && std::getenv("ATLAS_FAR_SPLIT_KERNEL")[0] == '0')) {
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = atlas_farsplit_spv_size;
            smci.pCode = atlas_farsplit_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &farSplitShader_) == VK_SUCCESS) {
                pipeprog::split.store(1);
                farSplitThread_ = std::thread([this] {
                    VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
                    cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
                    cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                    cp.stage.module = farSplitShader_;
                    cp.stage.pName = "main";
                    cp.layout = farLayout_;
                    VkPipeline p = VK_NULL_HANDLE;
                    if (makePipeline(cp, &p, "farMain (split frames)") == VK_SUCCESS) {
                        farSplitPipe_ = p;
                        farSplitReady_.store(true, std::memory_order_release);
                        pipeprog::split.store(2);
                    } else {
                        pipeprog::split.store(3);
                    }
                });
                // (offline timings: the frames after the first are timed on it)
                if (std::getenv("ATLAS_SHADER_WAIT") && *std::getenv("ATLAS_SHADER_WAIT") == '1') farSplitThread_.join();
            }
        }
        {
            // the cloud pass: same source, same bindings, same descriptor set as the far pass
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = atlas_cloud_spv_size;
            smci.pCode = atlas_cloud_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &cloudShader_) != VK_SUCCESS) return fail(err, "cloud shader");
            VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
            cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cp.stage.module = cloudShader_;
            cp.stage.pName = "main";
            cp.layout = farLayout_;
            if (makePipeline(cp, &cloudPipe_, "cloudMain") != VK_SUCCESS)
                return fail(err, "cloud pipeline");
            // and the sun map pass
            smci.codeSize = atlas_sunmap_spv_size;
            smci.pCode = atlas_sunmap_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &sunShader_) != VK_SUCCESS) return fail(err, "sun map shader");
            cp.stage.module = sunShader_;
            if (makePipeline(cp, &sunPipe_, "sunMapMain") != VK_SUCCESS)
                return fail(err, "sun map pipeline");
            // and the near light at half resolution, then its upsample
            smci.codeSize = atlas_nearlight_spv_size;
            smci.pCode = atlas_nearlight_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &nearLightShader_) != VK_SUCCESS) return fail(err, "near light shader");
            cp.stage.module = nearLightShader_;
            if (makePipeline(cp, &nearLightPipe_, "nearLightMain") != VK_SUCCESS)
                return fail(err, "near light pipeline");
            smci.codeSize = atlas_nearup_spv_size;
            smci.pCode = atlas_nearup_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &nearUpShader_) != VK_SUCCESS) return fail(err, "near upsample shader");
            cp.stage.module = nearUpShader_;
            if (makePipeline(cp, &nearUpPipe_, "nearUpsampleMain") != VK_SUCCESS)
                return fail(err, "near upsample pipeline");
            smci.codeSize = atlas_neartemporal_spv_size;
            smci.pCode = atlas_neartemporal_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &nearTempShader_) != VK_SUCCESS) return fail(err, "near temporal shader");
            cp.stage.module = nearTempShader_;
            if (makePipeline(cp, &nearTempPipe_, "nearTemporalMain") != VK_SUCCESS)
                return fail(err, "near temporal pipeline");
            // the far lights on the GPU: cull, admit, bin
            // (and the grid's emitters as lights, atlas_bricks.slang gridLightMain)
            const uint32_t* lcode[4] = {atlas_lightcull_spv, atlas_lightadmit_spv, atlas_lightbin_spv, atlas_gridlight_spv};
            const size_t lsize[4] = {atlas_lightcull_spv_size, atlas_lightadmit_spv_size, atlas_lightbin_spv_size,
                                     atlas_gridlight_spv_size};
            for (int i = 0; i < 4; i++) {
                smci.codeSize = lsize[i];
                smci.pCode = lcode[i];
                if (vkCreateShaderModule(dev_, &smci, nullptr, &lightShader_[i]) != VK_SUCCESS) return fail(err, "far light shader");
                cp.stage.module = lightShader_[i];
                if (makePipeline(cp, &lightPipe_[i], "lightKernel") != VK_SUCCESS)
                    return fail(err, "far light pipeline");
            }
        }
        std::vector<VkDescriptorType> pubTypes = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};  // (8: the water's surface)
        if (!createPipe(atlas_publish_spv, atlas_publish_spv_size, pubTypes, PASS_PUSH_BYTES, publishPipe_,
                        publishLayout_, publishSetLayout_, publishShader_, err, "publishMain"))
            return false;
        std::vector<VkDescriptorType> ingTypes = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
        if (!createPipe(atlas_ingest_spv, atlas_ingest_spv_size, ingTypes, PASS_PUSH_BYTES, ingestPipe_, ingestLayout_,
                        ingestSetLayout_, ingestShader_, err, "ingestMain"))
            return false;
        if (!createVoxGridPipes(err)) return false;

        VkDescriptorPoolSize ps[4] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, SLOTS},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, SLOTS * (BINDINGS - 1 + 5 + 2 + VG_BINDINGS)},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SLOTS * (4 + 2)},
                                      {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, SLOTS}};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = SLOTS * 4;
        dp.poolSizeCount = 4;
        dp.pPoolSizes = ps;
        if (vkCreateDescriptorPool(dev_, &dp, nullptr, &dpool_) != VK_SUCCESS) return fail(err, "descriptor pool");
        VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpi.queueFamilyIndex = family_;
        if (vkCreateCommandPool(dev_, &cpi, nullptr, &pool_) != VK_SUCCESS) return fail(err, "command pool");
        for (auto& s : slots_) {
            VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            cba.commandPool = pool_;
            cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            cba.commandBufferCount = 1;
            if (vkAllocateCommandBuffers(dev_, &cba, &s.cmd) != VK_SUCCESS) return fail(err, "command buffer");
            VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            if (!vkOk(vkCreateFence(dev_, &fci, nullptr, &s.fence), "vkCreateFence", err)) return false;
            if (timestamps_) {
                VkQueryPoolCreateInfo q{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
                q.queryType = VK_QUERY_TYPE_TIMESTAMP;
                q.queryCount = 4;  // the frame; the level-0 grid's build
                if (vkCreateQueryPool(dev_, &q, nullptr, &s.query) != VK_SUCCESS) s.query = VK_NULL_HANDLE;  // no GPU timer
                s.gt.create(dev_);
            }
            VkDescriptorSetLayout layouts[3] = {farSetLayout_, publishSetLayout_, ingestSetLayout_};
            VkDescriptorSet sets[3];
            VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            da.descriptorPool = dpool_;
            da.descriptorSetCount = 3;
            da.pSetLayouts = layouts;
            if (vkAllocateDescriptorSets(dev_, &da, sets) != VK_SUCCESS) return fail(err, "descriptor sets");
            s.farSet = sets[0];
            s.publishSet = sets[1];
            s.ingestSet = sets[2];
            da.descriptorSetCount = 1;
            da.pSetLayouts = &vgSetLayout_;
            if (vkAllocateDescriptorSets(dev_, &da, &s.vgSet) != VK_SUCCESS) return fail(err, "descriptor sets (voxgrid)");
        }
        // scene uploads are recorded by whichever thread stages a scene (under sceneMu_): their own pool
        VkCommandPoolCreateInfo upi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        upi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        upi.queueFamilyIndex = family_;
        if (vkCreateCommandPool(dev_, &upi, nullptr, &uploadPool_) != VK_SUCCESS) return fail(err, "upload pool");
        for (auto& c : copies_) {
            VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            cba.commandPool = uploadPool_;
            cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            cba.commandBufferCount = 1;
            if (vkAllocateCommandBuffers(dev_, &cba, &c.uploadCmd) != VK_SUCCESS) return fail(err, "upload command buffer");
            VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            if (!vkOk(vkCreateFence(dev_, &fci, nullptr, &c.uploadFence), "vkCreateFence (upload)", err)) return false;
        }
        return true;
    }

    bool sharedDevice(SharedDevice& d) override {
        if (!dev_ || lost_) return false;
        d.instance = inst_;
        d.physical = phys_;
        d.device = dev_;
        d.frameFamily = family_;
        if (genQueue_) {
            d.queue = genQueue_;
            d.family = genFamily_;
            d.queueMu = nullptr;
            d.timestamps = genTimestamps_;
        } else {
            d.queue = queue_;
            d.family = family_;
            d.queueMu = &queueMu_;
            d.timestamps = timestamps_;
        }
        d.float64 = float64_;
        d.timestampPeriod = timestampPeriod_;
        d.timeline = timeline_;
        return true;
    }
    uint64_t framesSubmitted() const override { return frameCounter_.load(); }
    double frameGpuMs() const override { return frameGpuEma_.load(); }
    std::atomic<double> frameGpuEma_{0.0};
    uint64_t framesCompleted() const override { return completedFrame_.load(); }
    // whether the last frame submitted is still on the GPU (its fence, read under the queue's lock; the client generator
    // fills the gaps between frames)
    bool frameOnGpu() override {
        std::lock_guard<std::mutex> q(queueMu_);
        return lastFrameFence_ && vkGetFenceStatus(dev_, lastFrameFence_) == VK_NOT_READY;
    }

    bool memoryInfo(MemoryInfo& m, std::string&) override {
        VkPhysicalDeviceMemoryBudgetPropertiesEXT budget{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
        VkPhysicalDeviceMemoryProperties2 mp2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2};
        if (budgetExt_) mp2.pNext = &budget;
        vkGetPhysicalDeviceMemoryProperties2(phys_, &mp2);
        const VkPhysicalDeviceMemoryProperties& mp = mp2.memoryProperties;
        int local = -1, host = -1;
        for (uint32_t h = 0; h < mp.memoryHeapCount; h++) {
            bool isLocal = (mp.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
            int& best = isLocal ? local : host;
            if (best < 0 || mp.memoryHeaps[h].size > mp.memoryHeaps[best].size) best = int(h);
        }
        if (local >= 0) {
            m.deviceLocalBytes = mp.memoryHeaps[local].size;
            if (budgetExt_) {
                m.deviceLocalBudget = budget.heapBudget[local];
                m.deviceLocalUsage = budget.heapUsage[local];
            }
        }
        if (host >= 0) m.hostHeapBytes = mp.memoryHeaps[host].size;
        m.atlasBytes = allocated_.load();
        m.maxStorageRange = maxStorageRange_;
        m.integrated = props_.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ||
                       props_.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU;
        m.budgetExt = budgetExt_;
        return true;
    }

    bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) override {
        if (lost_) return fail(err, "the device was lost");
        if (injectedNow(err)) return false;  // atlas_debug_fault / ATLAS_FAULT
        Slot& s = slots_[frameIndex_++ % SLOTS];
        if (!waitSlot(s, gpuMs, err)) return false;
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        size_t px = size_t(w) * h;
        FrameGpu frame = in.frame;
        // ATLAS_TIMING_DEVICE_OUTPUTS=1 (offline timings, atlas_render frames=): the outputs stay in device memory as
        // the game's are (no read back: writing a 1080p G-buffer across the bus costs the kernel ~20 ms), the frame
        // returns zeros
        bool deviceOnly = std::getenv("ATLAS_TIMING_DEVICE_OUTPUTS") != nullptr;
        // (ATLAS_FRAME_PRIMARY_NEAR: atlas's own near field, the near light an output without near inputs)
        const bool primaryNear = (frame.dims[2] & FRAME_PRIMARY_NEAR_BITS) != 0;
        const bool nearLight = (frame.dims[2] & FRAME_NEAR_LIGHT_BIT) && ((in.nearDepth && in.nearInfo) || primaryNear);
        if (!nearLight) frame.dims[2] &= ~FRAME_NEAR_LIGHT_BIT;
        // (PRIMARY_NEAR_LIT: the light by need, as in game; ATLAS_NEAR_FULLRES=1: every pixel lit in the far pass)
        {
            const char* fullEnv = std::getenv("ATLAS_NEAR_FULLRES");
            const bool nearFull = fullEnv && *fullEnv == '1';
            if (nearLight && primaryNear && (frame.dims[2] & FRAME_PRIMARY_NEAR_LIT_BIT) && !nearFull) frame.dims[2] |= FRAME_NEAR_SPLIT_BIT;
            else frame.dims[2] &= ~FRAME_NEAR_SPLIT_BIT;
        }
        farAmortBit(frame, s);
        hostNearInputs_ = true;  // (the CPU writes the near inputs here, also when the outputs stay on the device)
        if (!prepare(s, in, frame, !deviceOnly, err)) return false;
        if (in.nearDepth && !upload(s.bufs[B_NEAR], in.nearDepth, px * 4, false, Mem::HostWrite, err)) return false;
        if (nearLight && in.nearInfo && !upload(s.bufs[B_NEAR_INFO], in.nearInfo, px * 4, false, Mem::HostWrite, err)) return false;
        refreshDescriptors(s);
        begin(s);
        recordFar(s, w, h, (frame.dims[2] & 0x1u) != 0, (frame.dims[2] & FRAME_NEAR_SPLIT_BIT) != 0,
                  (frame.dims[2] & FRAME_PRIMARY_NEAR_LIT_BIT) != 0);
        if (deviceOnly) {
            if (!submit(s, nullptr, nullptr, err) || !waitSlot(s, gpuMs, err)) return false;
            out.color.assign(px * 4, 0.0f);
            out.normal.assign(px, 0);
            out.depth.assign(px, 1e30f);
            out.counters.assign(px, 0);
            if (nearLight) out.nearLight.assign(px * 4, 0.0f);
            return true;
        }
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
        if (!submit(s, nullptr, nullptr, err) || !waitSlot(s, gpuMs, err)) return false;
        out.color.resize(px * 4);
        out.normal.resize(px);
        out.depth.resize(px);
        out.counters.resize(px);
        std::memcpy(out.color.data(), s.bufs[B_COLOR].map, px * 16);
        std::memcpy(out.normal.data(), s.bufs[B_NORMAL].map, px * 4);
        std::memcpy(out.depth.data(), s.bufs[B_DEPTH].map, px * 4);
        std::memcpy(out.counters.data(), s.bufs[B_COUNTERS].map, px * 4);
        if (nearLight) {
            out.nearLight.resize(px * 4);
            std::memcpy(out.nearLight.data(), s.bufs[B_NEAR_LIGHT].map, px * 16);
        }
        return true;
    }

    // ---- GL interop ------------------------------------------------------------------------------------------

    bool glAttach(uint32_t w, uint32_t h, GlTargets& out, std::string& err) override {
        if (attached_) glDetach();
        if (!gl_.load(err)) return false;
        glLoaded_ = true;
        auto str = [&](GLenum e) {
            const GLubyte* s = gl_.GetString(e);
            return s ? std::string(reinterpret_cast<const char*>(s)) : std::string("?");
        };
        glRenderer_ = str(GL_RENDERER);
        glVersion_ = str(GL_VERSION);
        glVendor_ = str(GL_VENDOR);
        glWinsys_ = GlApi::windowSystem();
        while (gl_.GetError() != GL_NO_ERROR) {}  // the caller's stale errors are not ours
        // zero-copy when the driver pair allows it, else host copies; ATLAS_INTEROP=hostcopy|zerocopy forces one
        const char* f = std::getenv("ATLAS_INTEROP");
        std::string force = f ? f : "";
        {
            // reported whatever the mode (info): is the GL context on our device at all?
            uint8_t u[8][16];
            int n = 0;
            glUuidMatch_ = false;
            if (gl_.deviceUuids(u, 8, n))
                for (int i = 0; i < n; i++) glUuidMatch_ |= std::memcmp(u[i], deviceUuid_, 16) == 0;
        }
        std::string why = zeroCopyBlocker(w, h, force != "zerocopy");
        if (force == "hostcopy") why = "forced by ATLAS_INTEROP=hostcopy";
        if (!why.empty() && force == "zerocopy") return fail(err, ("zero-copy interop unavailable: " + why).c_str());
        bool ok = false;
        if (why.empty()) {
            mode_ = Interop::ZeroCopy;
            ok = createTargets(w, h, err);
            if (!ok) {
                destroyInterop();
                if (force == "zerocopy") return false;
                why = "zero-copy import failed (" + err + ")";
            }
        }
        if (!ok) {
            mode_ = Interop::HostCopy;
            if (!createTargets(w, h, err)) {
                destroyInterop();
                return false;
            }
        }
        interopWhy_ = why;
        if (mode_ == Interop::HostCopy) std::fprintf(stderr, "[atlas] OpenGL interop: host copies (%s)\n", why.c_str());
        width_ = w;
        height_ = h;
        firstGlFrame_ = true;
        attached_ = true;
        frameIndex_ = 0;
        for (auto& s : slots_) s.publishDirty = s.ingestDirty = true;
        out = {images_[IMG_COLOR].glTex, images_[IMG_NORMAL].glTex, images_[IMG_DEPTH].glTex, images_[IMG_NEAR].glTex, w, h};
        return true;
    }

    bool glRender(const FrameInputs& in, const GlFrameIO* io, double* gpuMs, std::string& err) override {
        if (!attached_) return fail(err, "atlas_gl_attach first");
        if (lost_) return fail(err, "the device was lost");
        // before any GL semaphore is signalled: a failure here leaves GL waiting on nothing
        if (injectedNow(err)) return false;
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        if (w != width_ || h != height_) return fail(err, "frame size differs from the attached targets (re-attach)");
        Slot& s = slots_[frameIndex_ % SLOTS];
        ATLAS_ZONE("vk:glRender");
        auto tg0 = std::chrono::steady_clock::now();
        if (!waitSlot(s, gpuMs, err)) return false;  // frame N-2: normally long done
        auto tg1 = std::chrono::steady_clock::now();
        const bool host = mode_ == Interop::HostCopy;
        // the near light (abi 0.15): the near field's depth and surface info in, the caller's texture out
        // (ATLAS_FRAME_PRIMARY_NEAR: atlas's own near field; the near light is an output only, traced in the far pass)
        const bool primaryNear = (in.frame.dims[2] & FRAME_PRIMARY_NEAR_BITS) != 0;
        const bool nearLight = (in.frame.dims[2] & FRAME_NEAR_LIGHT_BIT) && io && io->dstNearLight &&
                               (primaryNear || (in.wantNearDepth && io->nearDepthTex && io->nearInfoTex));
        // the near-depth skip reads the near field's distances from the shared image; host copies cannot share it
        // without stalling GL on the near field, so they march every pixel (the composite's depth test still keeps
        // whatever is nearer). The near light needs those distances: host copies then read them back (the stall)
        const bool nearOn = in.wantNearDepth && !primaryNear && (!host || nearLight);
        FrameGpu frame = in.frame;
        if (nearOn) frame.dims[2] |= 0x20u;  // FRAME_NEAR_DEPTH: read from the shared image
        else frame.dims[2] &= ~0x20u;
        if (!nearLight) frame.dims[2] &= ~FRAME_NEAR_LIGHT_BIT;
        // the near light at half resolution (atlas_far.slang nearLightMain); ATLAS_NEAR_FULLRES=1: every pixel traced
        // in the far pass, as the host render does (read each frame: atlas_gl_test switches it for its parity check)
        const char* fullEnv = std::getenv("ATLAS_NEAR_FULLRES");
        const bool nearFull = fullEnv && *fullEnv == '1';
        // (PRIMARY_NEAR_LIT, the unified field: the light by need, atlas_far.slang nearLightMain; the G-buffer mode keeps
        // its full-rate light in the far pass)
        const bool lit = (in.frame.dims[2] & FRAME_PRIMARY_NEAR_LIT_BIT) != 0;
        if (nearLight && !nearFull && (!primaryNear || lit)) frame.dims[2] |= FRAME_NEAR_SPLIT_BIT;
        else frame.dims[2] &= ~FRAME_NEAR_SPLIT_BIT;
        farAmortBit(frame, s);
        hostNearInputs_ = host && nearLight && !primaryNear;
        const Mem nearKind = hostNearInputs_ ? Mem::HostWrite : Mem::DeviceOnly;
        if (!prepare(s, in, frame, false, err)) return false;
        if (!ensure(s.bufs[B_NEAR], size_t(w) * h * 4, false, nearKind, err)) return false;
        if (nearLight && !ensure(s.bufs[B_NEAR_INFO], size_t(w) * h * 4, false, nearKind, err)) return false;
        refreshDescriptors(s);

        {
            ATLAS_ZONE_MIN("gl:linearizeNearDepth", 100);
            if (io && io->nearDepthTex && !primaryNear && (!host || nearLight) && !linearizeNearDepth(*io, w, h, nearLight, err))
                return false;
        }
        if (hostNearInputs_ && !readNearInputs(s, w, h)) return fail(err, "reading the near depth and info back from GL failed");
        if (sunSize_ != sunImageSize_) {
            for (auto& o : slots_)
                if (!waitSlot(o, nullptr, err)) return false;
            destroyShared(sunImage_);
            sunImageSize_ = 0;
            if (sunSize_) {
                if (!createImage(sunImage_, VK_FORMAT_R32G32_SFLOAT, GL_RG32F, sunSize_, sunSize_ + 1, err)) return false;
                sunImageSize_ = sunSize_;
                sunImageFresh_ = true;
            }
        }
        auto tg2 = std::chrono::steady_clock::now();
        if (host) return hostCopyFrame(s, in, frame, io, nearLight, gpuMs, err);

        // GL releases the shared images to Vulkan (after everything GL queued so far, e.g. last frame's composite)
        GLuint tex[IMAGES + 1];
        GLenum layouts[IMAGES + 1];
        for (int i = 0; i < IMAGES; i++) {
            tex[i] = images_[i].glTex;
            layouts[i] = GL_LAYOUT_GENERAL_EXT;
        }
        GLuint nTex = IMAGES;
        if (sunImage_.glTex) {
            tex[IMAGES] = sunImage_.glTex;
            layouts[IMAGES] = GL_LAYOUT_GENERAL_EXT;
            nTex++;
        }
        {
            ATLAS_ZONE_MIN("gl:signalSemaphore+flush", 100);
            gl_.SignalSemaphoreEXT(s.glReady.gl, 0, nullptr, nTex, tex, layouts);
            gl_.Flush();
        }

        begin(s);
        ATLAS_ZONE("vk:recordAndSubmit");
        imageBarriers(s.cmd, true);
        uint32_t pcv[4] = {w, h, nearLight ? (unifiedSplit(frame) ? 2u : 1u) : 0u, 0u};
        std::memcpy(&pcv[3], &in.preExposure, 4);
        if (nearOn) {
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ingestPipe_);
            vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ingestLayout_, 0, 1, &s.ingestSet, 0, nullptr);
            vkCmdPushConstants(s.cmd, ingestLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
            vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
            computeBarrier(s.cmd);
            s.gt.mark(s.cmd, "nearIngest");
        }
        recordFar(s, w, h, (frame.dims[2] & 0x1u) != 0, (frame.dims[2] & FRAME_NEAR_SPLIT_BIT) != 0,
                  (frame.dims[2] & FRAME_PRIMARY_NEAR_LIT_BIT) != 0);
        computeBarrier(s.cmd);
        if (sunDispatchRows_ && sunImage_.image) copySunToImage(s.cmd);
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishPipe_);
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishLayout_, 0, 1, &s.publishSet, 0, nullptr);
        vkCmdPushConstants(s.cmd, publishLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
        imageBarriers(s.cmd, false);
        if (!submit(s, &s.glReady.sem, &s.vkDone.sem, err)) return false;
        firstGlFrame_ = false;
        frameIndex_++;

        // GL commands after this point wait for the Vulkan frame
        {
            ATLAS_ZONE_MIN("gl:waitSemaphore+copyToCaller", 100);
            gl_.WaitSemaphoreEXT(s.vkDone.gl, 0, nullptr, nTex, tex, layouts);
            copyToCaller(io, w, h, nearLight);
        }
        auto tg3 = std::chrono::steady_clock::now();
        auto ease = [](double& v, std::chrono::steady_clock::time_point a, std::chrono::steady_clock::time_point b) {
            v += (std::chrono::duration<double, std::milli>(b - a).count() - v) * 0.05;
        };
        ease(glTimes_.waitMs, tg0, tg1);
        ease(glTimes_.prepareMs, tg1, tg2);
        ease(glTimes_.recordMs, tg2, tg3);
        return true;
    }

    // hand the frame to the caller's own textures (e.g. what a shader pack samples via customTexture)
    void copyToCaller(const GlFrameIO* io, uint32_t w, uint32_t h, bool nearLight) {
        if (!io) return;
        const GLuint dst[3] = {io->dstColor, io->dstNormal, io->dstDepth};
        for (int i = 0; i < 3; i++)
            if (dst[i])
                gl_.CopyImageSubData(images_[i].glTex, GL_TEXTURE_2D, 0, 0, 0, 0, dst[i], GL_TEXTURE_2D, 0, 0, 0, 0,
                                     GLsizei(w), GLsizei(h), 1);
        if (nearLight && io->dstNearLight)
            gl_.CopyImageSubData(images_[IMG_NEAR_LIGHT].glTex, GL_TEXTURE_2D, 0, 0, 0, 0, io->dstNearLight, GL_TEXTURE_2D,
                                 0, 0, 0, 0, GLsizei(w), GLsizei(h), 1);
        if (io->dstSunMap && sunImage_.glTex)
            gl_.CopyImageSubData(sunImage_.glTex, GL_TEXTURE_2D, 0, 0, 0, 0, io->dstSunMap, GL_TEXTURE_2D, 0, 0, 0, 0,
                                 GLsizei(sunSize_), GLsizei(sunSize_ + 1), 1);
    }

    // ---- host-copy fallback ----------------------------------------------------------------------------------
    // The frame is published into Vulkan-local images (the same publish pass, so the GL textures hold exactly what
    // zero-copy would), copied into the slot's host buffer, and uploaded with glTextureSubImage2D. By default the
    // CPU waits for this frame's GPU work (no added latency); ATLAS_HOSTCOPY_LATENCY=1 uploads the previous
    // frame instead, so the CPU never waits but the far field lags one frame behind the camera.
    bool hostCopyFrame(Slot& s, const FrameInputs& in, const FrameGpu& frame, const GlFrameIO* io, bool nearLight,
                       double* gpuMs, std::string& err) {
        uint32_t w = width_, h = height_;
        size_t px = size_t(w) * h;
        VkDeviceSize sunBytes = sunDispatchRows_ && sunImage_.glTex ? VkDeviceSize(sunSize_) * (sunSize_ + 1) * 8 : 0;
        // colour, normal, depth, the near light (RGBA16F) and the sun map, one after the other
        VkDeviceSize bytes = VkDeviceSize(px) * 24 + VkDeviceSize(sunSize_ ? VkDeviceSize(sunSize_) * (sunSize_ + 1) * 8 : 0);
        if (!ensure(s.hostOut, bytes, false, Mem::HostRead, err)) return false;
        begin(s);
        hostImageBarrier(s.cmd, true);
        recordFar(s, w, h, (frame.dims[2] & 0x1u) != 0, (frame.dims[2] & FRAME_NEAR_SPLIT_BIT) != 0,
                  (frame.dims[2] & FRAME_PRIMARY_NEAR_LIT_BIT) != 0);
        computeBarrier(s.cmd);
        uint32_t pcv[4] = {w, h, nearLight ? (unifiedSplit(frame) ? 2u : 1u) : 0u, 0u};
        std::memcpy(&pcv[3], &in.preExposure, 4);
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishPipe_);
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishLayout_, 0, 1, &s.publishSet, 0, nullptr);
        vkCmdPushConstants(s.cmd, publishLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
        hostImageBarrier(s.cmd, false);
        const int img[4] = {IMG_COLOR, IMG_NORMAL, IMG_DEPTH, IMG_NEAR_LIGHT};
        const VkDeviceSize texel[4] = {8, 4, 4, 8};
        VkDeviceSize off = 0;
        for (int i = 0; i < (nearLight ? 4 : 3); i++) {
            VkBufferImageCopy region{};
            region.bufferOffset = off;
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageExtent = {w, h, 1};
            vkCmdCopyImageToBuffer(s.cmd, images_[img[i]].image, VK_IMAGE_LAYOUT_GENERAL, s.hostOut.buf, 1, &region);
            off += VkDeviceSize(px) * texel[i];
        }
        off = VkDeviceSize(px) * 24;  // (the sun map's place whether or not the near light came along)
        if (sunBytes) {
            VkBufferCopy rc{0, off, sunBytes};
            vkCmdCopyBuffer(s.cmd, shared_[B_SUNMAP].buf, s.hostOut.buf, 1, &rc);
        }
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0,
                             nullptr);
        if (!submit(s, nullptr, nullptr, err)) return false;
        s.hostPending = true;
        s.hostSun = sunBytes != 0;
        s.hostNearLight = nearLight;
        firstGlFrame_ = false;
        frameIndex_++;
        const char* lag = std::getenv("ATLAS_HOSTCOPY_LATENCY");
        Slot& up = lag && *lag == '1' ? slots_[frameIndex_ % SLOTS] : s;  // the other slot holds the previous frame
        if (!up.hostPending) return true;
        auto t0 = std::chrono::steady_clock::now();
        if (!waitSlot(up, gpuMs, err)) return false;
        auto t1 = std::chrono::steady_clock::now();
        hostUpload(up, w, h);
        up.hostPending = false;
        auto t2 = std::chrono::steady_clock::now();
        hostWaitMs_ = std::chrono::duration<double, std::milli>(t1 - t0).count();
        hostUploadMs_ = std::chrono::duration<double, std::milli>(t2 - t1).count();
        copyToCaller(io, w, h, nearLight && up.hostNearLight);
        return true;
    }

    // Host copies with the near light: the near depth and surface info the GL pass just wrote (atlas's resolution,
    // bottom-up rows) read back into the slot's near buffers (row 0 top), as the ingest pass does on the device. The
    // read waits for GL's near pass. The caller's pixel-pack state is saved and restored around it.
    bool readNearInputs(Slot& s, uint32_t w, uint32_t h) {
        size_t px = size_t(w) * h;
        if (!s.bufs[B_NEAR].map || !s.bufs[B_NEAR_INFO].map || s.bufs[B_NEAR].size < px * 4 ||
            s.bufs[B_NEAR_INFO].size < px * 4)
            return false;
        const GLenum keys[] = {GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS, GL_PACK_ALIGNMENT,
                               GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST};
        const GLint want[] = {0, 0, 0, 4, 0, 0};
        GLint saved[6] = {}, pbo = 0;
        gl_.GetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pbo);
        for (int i = 0; i < 6; i++) gl_.GetIntegerv(keys[i], &saved[i]);
        if (pbo) gl_.BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        for (int i = 0; i < 6; i++)
            if (saved[i] != want[i]) gl_.PixelStorei(keys[i], want[i]);
        nearReadback_.resize(px * 2);
        gl_.GetTextureImage(images_[IMG_NEAR].glTex, 0, GL_RED, GL_FLOAT, GLsizei(px * 4), nearReadback_.data());
        gl_.GetTextureImage(images_[IMG_NEAR_INFO].glTex, 0, GL_RGBA, GL_UNSIGNED_BYTE, GLsizei(px * 4),
                            nearReadback_.data() + px);
        for (int i = 0; i < 6; i++)
            if (saved[i] != want[i]) gl_.PixelStorei(keys[i], saved[i]);
        if (pbo) gl_.BindBuffer(GL_PIXEL_PACK_BUFFER, GLuint(pbo));
        char* depth = static_cast<char*>(s.bufs[B_NEAR].map);
        char* info = static_cast<char*>(s.bufs[B_NEAR_INFO].map);
        const char* src = reinterpret_cast<const char*>(nearReadback_.data());
        for (uint32_t y = 0; y < h; y++) {
            size_t from = size_t(h - 1 - y) * w * 4, to = size_t(y) * w * 4;
            std::memcpy(depth + to, src + from, size_t(w) * 4);
            std::memcpy(info + to, src + px * 4 + from, size_t(w) * 4);
        }
        return true;
    }

    void hostImageBarrier(VkCommandBuffer cmd, bool before) {
        if (before && firstGlFrame_) {
            VkImageMemoryBarrier b[IMAGES];
            for (int i = 0; i < IMAGES; i++) {
                b[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                b[i].image = images_[i].image;
                b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                b[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            }
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                                 0, nullptr, IMAGES, b);
            return;
        }
        // before: the previous frame's copies out of the images finish before the publish overwrites them;
        // after: the publish's writes (and the sun map's) are visible to the copies
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = before ? VK_ACCESS_TRANSFER_READ_BIT : VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = before ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cmd, before ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             before ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
    }

    // Host buffer -> the GL textures. The caller's pixel-unpack state (Minecraft leaves row length / skips set after
    // its own uploads, and may have a PBO bound) is saved and restored around the uploads.
    void hostUpload(const Slot& s, uint32_t w, uint32_t h) {
        const GLenum keys[] = {GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_ALIGNMENT,
                               GL_UNPACK_SWAP_BYTES, GL_UNPACK_LSB_FIRST};
        const GLint want[] = {0, 0, 0, 4, 0, 0};
        GLint saved[6] = {}, pbo = 0;
        gl_.GetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &pbo);
        for (int i = 0; i < 6; i++) gl_.GetIntegerv(keys[i], &saved[i]);
        if (pbo) gl_.BindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        for (int i = 0; i < 6; i++)
            if (saved[i] != want[i]) gl_.PixelStorei(keys[i], want[i]);
        size_t px = size_t(w) * h;
        const char* p = static_cast<const char*>(s.hostOut.map);
        gl_.TextureSubImage2D(images_[IMG_COLOR].glTex, 0, 0, 0, GLsizei(w), GLsizei(h), GL_RGBA, GL_HALF_FLOAT, p);
        gl_.TextureSubImage2D(images_[IMG_NORMAL].glTex, 0, 0, 0, GLsizei(w), GLsizei(h), GL_RGBA, GL_UNSIGNED_BYTE, p + px * 8);
        gl_.TextureSubImage2D(images_[IMG_DEPTH].glTex, 0, 0, 0, GLsizei(w), GLsizei(h), GL_RED, GL_FLOAT, p + px * 12);
        if (s.hostNearLight)
            gl_.TextureSubImage2D(images_[IMG_NEAR_LIGHT].glTex, 0, 0, 0, GLsizei(w), GLsizei(h), GL_RGBA, GL_HALF_FLOAT,
                                  p + px * 16);
        if (s.hostSun && sunImage_.glTex)
            gl_.TextureSubImage2D(sunImage_.glTex, 0, 0, 0, GLsizei(sunSize_), GLsizei(sunSize_ + 1), GL_RG, GL_FLOAT,
                                  p + px * 24);
        for (int i = 0; i < 6; i++)
            if (saved[i] != want[i]) gl_.PixelStorei(keys[i], saved[i]);
        if (pbo) gl_.BindBuffer(GL_PIXEL_UNPACK_BUFFER, GLuint(pbo));
    }

    bool stageScene(const PackedScene& sc) override {
        if (lost_) return false;
        ATLAS_ZONE_MIN("vk:stageScene", 50);
        std::lock_guard<std::mutex> lock(sceneMu_);
        int c = current_ == 0 ? 1 : 0;
        if (copies_[c].lastFrame > completedFrame_.load()) return false;  // a frame may still read it
        if (copies_[c].uploading && vkGetFenceStatus(dev_, copies_[c].uploadFence) != VK_SUCCESS) return false;
        std::string err;
        if (!uploadScene(copies_[c], sc, false, err)) return false;
        staged_ = c;
        return true;
    }

    void glDetach() override {
        if (fault_ == Fault::Timeout) {
            // hung: nothing that work may still touch is freed (see the destructor); GL forgets the images
            attached_ = false;
            mode_ = Interop::None;
            return;
        }
        if (reloadThread_.joinable()) reloadThread_.join();  // (a shader reload still building)
        if (dev_) vkDeviceWaitIdle(dev_);  // (the split kernel's build reads nothing freed here: not waited for)
        for (auto& s : slots_) s.inFlight = false;
        destroyInterop();
        mode_ = Interop::None;
    }

private:
    bool fail(std::string& err, const char* m) { return failWith(Fault::Error, err, m); }

    // false (and the fault recorded: lost, out of memory, timeout or plain error) unless r is VK_SUCCESS
    bool vkOk(VkResult r, const char* what, std::string& err) {
        if (r == VK_SUCCESS) return true;
        Fault f = classify(r);
        std::string msg = std::string(what) + ": " + vkResultName(r) + " (" + std::to_string(int(r)) + ")";
        if (f == Fault::DeviceLost) msg += ": the GPU was reset (driver timeout / TDR, GPU reset) or atlas's work faulted";
        if (f == Fault::Timeout) msg += ": the GPU did not finish atlas's work in 5 s (hung or stalled)";
        return failWith(f, err, msg);
    }

    bool waitFence(VkFence f, const char* what, std::string& err) {
        ATLAS_ZONE_MIN("wait:gpuFence", 100);
        return vkOk(vkWaitForFences(dev_, 1, &f, VK_TRUE, FENCE_TIMEOUT_NS), what, err);
    }

    // GL state the pass touches is saved and restored (gl_near.hpp).
    // info: also the near field's surface info (io.nearInfoTex) into the shared nearInfo image (the near light)
    bool linearizeNearDepth(const GlFrameIO& io, uint32_t w, uint32_t h, bool info, std::string& err) {
        return near_.run(gl_, io, w, h, info, images_[IMG_NEAR].glTex, images_[IMG_NEAR_INFO].glTex, err);
    }

    // The 64^3 RGBA8 cloud noise as a sampled 3D image (linear filter, repeat): one hardware trilinear fetch per
    // noise lookup instead of eight buffer reads. Uploaded through a staging buffer; callers waited all slots.
    bool uploadNoiseTexture(const std::vector<uint32_t>& vol, std::string& err) {
        if (!noiseImg_) {
            VkImageCreateInfo ic{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            ic.imageType = VK_IMAGE_TYPE_3D;
            ic.format = VK_FORMAT_R8G8B8A8_UNORM;
            ic.extent = {64, 64, 64};
            ic.mipLevels = 1;
            ic.arrayLayers = 1;
            ic.samples = VK_SAMPLE_COUNT_1_BIT;
            ic.tiling = VK_IMAGE_TILING_OPTIMAL;
            ic.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            if (vkCreateImage(dev_, &ic, nullptr, &noiseImg_) != VK_SUCCESS) return fail(err, "noise image");
            VkMemoryRequirements req;
            vkGetImageMemoryRequirements(dev_, noiseImg_, &req);
            VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            ma.allocationSize = req.size;
            ma.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (vkAllocateMemory(dev_, &ma, nullptr, &noiseMem_) != VK_SUCCESS) return fail(err, "noise memory");
            vkBindImageMemory(dev_, noiseImg_, noiseMem_, 0);
            VkImageViewCreateInfo vc{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            vc.image = noiseImg_;
            vc.viewType = VK_IMAGE_VIEW_TYPE_3D;
            vc.format = VK_FORMAT_R8G8B8A8_UNORM;
            vc.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (vkCreateImageView(dev_, &vc, nullptr, &noiseView_) != VK_SUCCESS) return fail(err, "noise view");
            VkSamplerCreateInfo sc{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            sc.magFilter = VK_FILTER_LINEAR;
            sc.minFilter = VK_FILTER_LINEAR;
            sc.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            sc.addressModeU = sc.addressModeV = sc.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
            if (vkCreateSampler(dev_, &sc, nullptr, &noiseSampler_) != VK_SUCCESS) return fail(err, "noise sampler");
        }
        if (!upload(noiseStaging_, vol.data(), vol.size() * 4, false, Mem::Staging, err)) return false;
        VkCommandBuffer cmd = copies_[0].uploadCmd;  // idle: callers waited every slot and upload under sceneMu_
        std::lock_guard<std::mutex> scene(sceneMu_);
        if (copies_[0].uploading && !waitFence(copies_[0].uploadFence, "noise upload wait", err)) return false;
        copies_[0].uploading = false;
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.image = noiseImg_;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &b);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {64, 64, 64};
        vkCmdCopyBufferToImage(cmd, noiseStaging_.buf, noiseImg_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &b);
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(dev_, 1, &copies_[0].uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (!vkOk(vkQueueSubmit(queue_, 1, &si, copies_[0].uploadFence), "noise upload", err)) return false;
        }
        return waitFence(copies_[0].uploadFence, "noise upload", err);
    }

    // ---- setup ---------------------------------------------------------------------------------------------

    bool createInstance(std::string& err) {
        if (!vkLoadGlobal(err)) return false;
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "atlas_core";
        app.apiVersion = VK_API_VERSION_1_2;  // baseline: Pascal-era drivers, Mesa, MoltenVK-free Linux stacks
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo = &app;
        const char* layers[] = {"VK_LAYER_KHRONOS_validation"};
        const char* instExt[] = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
        const char* val = std::getenv("ATLAS_VK_VALIDATION");
        bool validate = val && *val == '1';
        if (validate) {
            ici.enabledLayerCount = 1;
            ici.ppEnabledLayerNames = layers;
            ici.enabledExtensionCount = 1;
            ici.ppEnabledExtensionNames = instExt;
        }
        VkResult r = vkCreateInstance(&ici, nullptr, &inst_);
        if (validate && (r == VK_ERROR_LAYER_NOT_PRESENT || r == VK_ERROR_EXTENSION_NOT_PRESENT)) {
            std::fprintf(stderr, "[vulkan] validation requested but VK_LAYER_KHRONOS_validation is not installed\n");
            validate = false;
            ici.enabledLayerCount = ici.enabledExtensionCount = 0;
            r = vkCreateInstance(&ici, nullptr, &inst_);
        }
        if (r != VK_SUCCESS) {
            inst_ = VK_NULL_HANDLE;
            err = "vkCreateInstance (Vulkan 1.2) failed: " + std::to_string(int(r));
            return false;
        }
        if (!vkLoadInstance(inst_, err)) return false;
        if (validate) {
            auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(inst_, "vkCreateDebugUtilsMessengerEXT"));
            VkDebugUtilsMessengerCreateInfoEXT mi{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
            mi.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
            mi.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
            mi.pfnUserCallback = onValidation;
            if (create) create(inst_, &mi, nullptr, &messenger_);
            std::fprintf(stderr, "[vulkan] validation %s\n", messenger_ ? "on" : "requested but unavailable");
        }
        return true;
    }

    // Which device: the one the game's GL context runs on when a context is current (atlas_create is called on the
    // render thread: hybrid laptops, a Deck with lavapipe installed beside RADV, a second GPU), else the best type
    // (discrete > integrated > virtual > cpu). ATLAS_VK_DEVICE=<index or name substring> overrides both.
    bool pickDevice(std::string& err) {
        uint32_t n = 0;
        vkEnumeratePhysicalDevices(inst_, &n, nullptr);
        std::vector<VkPhysicalDevice> devs(n);
        vkEnumeratePhysicalDevices(inst_, &n, devs.data());
        uint8_t glUuids[8][16];
        int glCount = 0;
        if (GlApi::contextCurrent()) {
            GlApi probe;
            std::string e;
            if (probe.load(e)) probe.deviceUuids(glUuids, 8, glCount);
        }
        const char* want = std::getenv("ATLAS_VK_DEVICE");
        int best = -1, bestScore = -1;
        std::string reasons;
        for (uint32_t i = 0; i < n; i++) {
            VkPhysicalDeviceIDProperties idp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
            VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
            p2.pNext = &idp;
            vkGetPhysicalDeviceProperties2(devs[i], &p2);
            const VkPhysicalDeviceProperties& p = p2.properties;
            VkPhysicalDeviceFeatures f;
            vkGetPhysicalDeviceFeatures(devs[i], &f);
            bool glMatch = false;
            for (int k = 0; k < glCount; k++) glMatch |= std::memcmp(glUuids[k], idp.deviceUUID, 16) == 0;
            std::string why = deviceUnfit(devs[i], p, f);
            DeviceSeen seen{p.deviceName, typeName(p.deviceType), apiString(p.apiVersion), glMatch, why};
            seen_.push_back(seen);
            if (!why.empty()) {
                reasons += std::string(reasons.empty() ? "" : "; ") + p.deviceName + ": " + why;
                continue;
            }
            int score = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU     ? 4
                        : p.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 3
                        : p.deviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU    ? 2
                        : p.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU            ? 0
                                                                                 : 1;
            if (glMatch) score += 100;
            if (want && *want) {
                // an index, or a name substring (parsed by hand: strtol binds to glibc 2.38's __isoc23_strtol)
                bool digits = true;
                long idx = 0;
                for (const char* c = want; *c; c++) {
                    digits &= *c >= '0' && *c <= '9';
                    idx = idx * 10 + (*c - '0');
                }
                bool named = digits ? idx == long(i) : nameContains(p.deviceName, want);
                if (named) score += 1000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = int(i);
            }
        }
        if (best < 0) return fail(err, ("no usable Vulkan device" + (reasons.empty() ? std::string() : " (" + reasons + ")")).c_str());
        phys_ = devs[size_t(best)];
        chosen_ = best;
        glMatchedAtCreate_ = seen_[size_t(best)].glMatch;
        VkPhysicalDeviceDriverProperties drv{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
        VkPhysicalDeviceIDProperties idp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        idp.pNext = &drv;
        VkPhysicalDeviceProperties2 props2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        props2.pNext = &idp;
        vkGetPhysicalDeviceProperties2(phys_, &props2);
        props_ = props2.properties;
        std::memcpy(deviceUuid_, idp.deviceUUID, VK_UUID_SIZE);
        std::memcpy(driverUuid_, idp.driverUUID, VK_UUID_SIZE);
        driverName_ = std::string(drv.driverName) + " " + drv.driverInfo;
        driverId_ = uint32_t(drv.driverID);
        std::snprintf(deviceName_, sizeof(deviceName_), "vulkan (%s)", props2.properties.deviceName);
        timestampPeriod_ = props2.properties.limits.timestampPeriod;
        maxStorageRange_ = props2.properties.limits.maxStorageBufferRange;
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, qf.data());
        family_ = UINT32_MAX;
        // a compute-capable family; the graphics one first (it is family 0 everywhere that matters, and the queue
        // GL interop is best tested against), else a compute-only one
        for (int pass = 0; pass < 2 && family_ == UINT32_MAX; pass++)
            for (uint32_t i = 0; i < qn; i++) {
                bool gfx = (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
                if ((qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && (pass == 1 || gfx)) {
                    family_ = i;
                    timestamps_ = qf[i].timestampValidBits > 0;
                    break;
                }
            }
        // the client generator's queue (sharedDevice): a compute-only family (async compute) when there is one, else a
        // second queue of the frames' family, else none (it then submits to the frames' queue)
        genFamily_ = UINT32_MAX;
        genTimestamps_ = false;
        for (uint32_t i = 0; i < qn && family_ != UINT32_MAX; i++)
            if (i != family_ && (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && !(qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                genFamily_ = i;
                genTimestamps_ = qf[i].timestampValidBits > 0;
                break;
            }
        if (genFamily_ == UINT32_MAX && family_ != UINT32_MAX && qf[family_].queueCount >= 2) {
            genFamily_ = family_;
            genTimestamps_ = timestamps_;
        }
        return family_ != UINT32_MAX || fail(err, "no compute queue");
    }

    static bool nameContains(const char* name, const char* sub) {
        std::string a(name), b(sub);
        for (auto& c : a) c = char(std::tolower(static_cast<unsigned char>(c)));
        for (auto& c : b) c = char(std::tolower(static_cast<unsigned char>(c)));
        return a.find(b) != std::string::npos;
    }

    // Why a device cannot run the kernels ("" when it can): the Vulkan 1.2 baseline, 64-bit integers in shaders,
    // a compute queue, and the far kernel's 24 storage buffers in one stage.
    static std::string deviceUnfit(VkPhysicalDevice d, const VkPhysicalDeviceProperties& p, const VkPhysicalDeviceFeatures& f) {
        if (p.apiVersion < VK_API_VERSION_1_2) return "Vulkan " + apiString(p.apiVersion) + " < 1.2";
        if (!f.shaderInt64) return "no shaderInt64";
        const VkPhysicalDeviceLimits& l = p.limits;
        if (l.maxPerStageDescriptorStorageBuffers < BINDINGS - 2 || l.maxDescriptorSetStorageBuffers < BINDINGS - 2)
            return "only " + std::to_string(l.maxPerStageDescriptorStorageBuffers) + " storage buffers per stage";
        if (l.maxComputeWorkGroupInvocations < 64 || l.maxComputeWorkGroupSize[0] < 8 || l.maxComputeWorkGroupSize[1] < 8)
            return "compute work groups smaller than 8x8";
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, qf.data());
        for (auto& q : qf)
            if (q.queueFlags & VK_QUEUE_COMPUTE_BIT) return "";
        return "no compute queue";
    }

    // What the device can export to GL: the platform's handle type for memory and semaphores, for every format the
    // bridge shares with the usage it needs, and whether those allocations must be dedicated (AMD's usually are).
    void probeInterop() {
        if (!interopCapable_) {
            if (vkInteropWhy_.empty()) vkInteropWhy_ = "the Vulkan device does not export memory handles";
            return;
        }
        const VkFormat fmts[] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R32_SFLOAT,
                                 VK_FORMAT_R32G32_SFLOAT};
        const char* names[] = {"RGBA16F", "RGBA8", "R32F", "RG32F"};
        maxExport_ = UINT32_MAX;
        for (int i = 0; i < 4; i++) {
            VkPhysicalDeviceExternalImageFormatInfo ext{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
            ext.handleType = MEM_HANDLE;
            VkPhysicalDeviceImageFormatInfo2 fi{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};
            fi.pNext = &ext;
            fi.format = fmts[i];
            fi.type = VK_IMAGE_TYPE_2D;
            fi.tiling = VK_IMAGE_TILING_OPTIMAL;
            fi.usage = SHARED_USAGE;
            VkExternalImageFormatProperties ep{VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
            VkImageFormatProperties2 fp{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};
            fp.pNext = &ep;
            VkResult r = vkGetPhysicalDeviceImageFormatProperties2(phys_, &fi, &fp);
            VkExternalMemoryFeatureFlags feat = ep.externalMemoryProperties.externalMemoryFeatures;
            if (r != VK_SUCCESS || !(feat & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)) {
                interopCapable_ = false;
                vkInteropWhy_ = std::string(names[i]) + " storage images cannot be exported (" +
                                (r != VK_SUCCESS ? "format query " + std::to_string(int(r)) : std::string("not exportable")) + ")";
                return;
            }
            if (feat & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) dedicatedOnly_ = true;
            maxExport_ = std::min({maxExport_, fp.imageFormatProperties.maxExtent.width, fp.imageFormatProperties.maxExtent.height});
        }
        VkPhysicalDeviceExternalSemaphoreInfo si{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO};
        si.handleType = SEM_HANDLE;
        VkExternalSemaphoreProperties sp{VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
        vkGetPhysicalDeviceExternalSemaphoreProperties(phys_, &si, &sp);
        if (!(sp.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)) {
            interopCapable_ = false;
            vkInteropWhy_ = "binary semaphores cannot be exported";
        }
    }

    // Why zero-copy cannot work with the current GL context ("" when it can).
    std::string zeroCopyBlocker(uint32_t w, uint32_t h, bool strictDriver) {
        if (!interopCapable_) return vkInteropWhy_;
        if (!glHasInterop(gl_)) {
            std::string missing;
            for (const char* e : {"GL_EXT_memory_object", "GL_EXT_semaphore", GL_MEM_EXT, GL_SEM_EXT})
                if (!gl_.hasExtension(e)) missing += std::string(missing.empty() ? "" : ", ") + e;
            return "the OpenGL context lacks " + (missing.empty() ? std::string(GL_INTEROP_EXTS) : missing);
        }
        uint8_t u[8][16];
        int n = 0;
        if (!gl_.deviceUuids(u, 8, n)) return "the OpenGL context reports no device UUID";
        bool same = false;
        for (int i = 0; i < n; i++) same |= std::memcmp(u[i], deviceUuid_, 16) == 0;
        if (!same)
            return "the OpenGL context (" + glRenderer_ + ", device " + hex16(u[0]) + ") is not on the Vulkan device " +
                   props_.deviceName + " (" + hex16(deviceUuid_) + ")";
        uint8_t du[16];
        if (gl_.driverUuid(du)) {
            glDriverUuid_ = hex16(du);
            // opaque handles only import into the same driver build (spec: driver and device UUID must match);
            // ATLAS_INTEROP=zerocopy waives this one check, for a stack that reports differing UUIDs yet works
            if (strictDriver && std::memcmp(du, driverUuid_, 16) != 0)
                return "GL driver UUID " + glDriverUuid_ + " differs from Vulkan's " + hex16(driverUuid_);
        }
        if (w > maxExport_ || h > maxExport_) return "targets larger than the exportable image size";
        return "";
    }

    bool createDevice(std::string& err) {
        // interop extensions are optional: without them the host path still works
        uint32_t en = 0;
        vkEnumerateDeviceExtensionProperties(phys_, nullptr, &en, nullptr);
        std::vector<VkExtensionProperties> ext(en);
        vkEnumerateDeviceExtensionProperties(phys_, nullptr, &en, ext.data());
        auto has = [&](const char* nm) {
            for (auto& e : ext)
                if (std::strcmp(e.extensionName, nm) == 0) return true;
            return false;
        };
        std::vector<const char*> enable;
        // external memory / semaphores themselves are core in 1.1; only the handle flavour is an extension
        // VK_EXT_memory_budget (optional): what the process may use of each heap, for atlas_memory_info
        if (has(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME)) {
            enable.push_back(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
            budgetExt_ = true;
        }
        if (has(VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)) {  // the flight recorder places GPU zones on the host clock
            enable.push_back(VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
            calibratedTs_ = true;
        }
        if (has(MEM_EXT) && has(SEM_EXT)) {
            enable.push_back(MEM_EXT);
            enable.push_back(SEM_EXT);
            interopCapable_ = true;
        } else {
            vkInteropWhy_ = std::string("the Vulkan device lacks ") +
                            (!has(MEM_EXT) && !has(SEM_EXT) ? std::string(MEM_EXT) + " and " + SEM_EXT
                                                            : std::string(has(MEM_EXT) ? SEM_EXT : MEM_EXT));
        }
        // the frames' queue, and the client generator's (lower priority: frames first)
        float prio[2] = {1.0f, 0.5f};
        VkDeviceQueueCreateInfo qci[2] = {{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}, {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}};
        uint32_t nq = 1;
        qci[0].queueFamilyIndex = family_;
        qci[0].queueCount = genFamily_ == family_ ? 2 : 1;
        qci[0].pQueuePriorities = prio;
        // (VK_EXT_global_priority, where offered: the generator's compute-only family at the system's low priority, so
        // the frames' work goes before it across queues too; ATLAS_GEN_LOW_PRIORITY=0 off)
        VkDeviceQueueGlobalPriorityCreateInfoEXT low{VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT};
        low.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT;
        bool lowWanted = !(std::getenv("ATLAS_GEN_LOW_PRIORITY") && std::getenv("ATLAS_GEN_LOW_PRIORITY")[0] == '0');
        if (genFamily_ != UINT32_MAX && genFamily_ != family_) {
            qci[1].queueFamilyIndex = genFamily_;
            qci[1].queueCount = 1;
            qci[1].pQueuePriorities = prio + 1;
            nq = 2;
            if (lowWanted && has("VK_EXT_global_priority")) {
                enable.push_back("VK_EXT_global_priority");
                qci[1].pNext = &low;
                genLowPriority_ = true;
            }
        }
        VkPhysicalDeviceFeatures have{};
        vkGetPhysicalDeviceFeatures(phys_, &have);
        VkPhysicalDeviceFeatures feats{};
        feats.shaderInt64 = VK_TRUE;
        feats.shaderFloat64 = have.shaderFloat64;  // the client generator's exact doubles (optional)
        float64_ = have.shaderFloat64 == VK_TRUE;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = nq;
        dci.pQueueCreateInfos = qci;
        dci.pEnabledFeatures = &feats;
        // (dev: ATLAS_FEAT_CLOCK=1) the decoration kernels' clock probes (a core built with CMake ATLAS_FEAT_CLOCK)
        VkPhysicalDeviceShaderClockFeaturesKHR clockF{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_CLOCK_FEATURES_KHR};
        if (std::getenv("ATLAS_FEAT_CLOCK") && std::getenv("ATLAS_FEAT_CLOCK")[0] == '1' && has(VK_KHR_SHADER_CLOCK_EXTENSION_NAME)) {
            enable.push_back(VK_KHR_SHADER_CLOCK_EXTENSION_NAME);
            clockF.shaderSubgroupClock = VK_TRUE;
            dci.pNext = &clockF;
        }
        // timeline semaphores (core 1.2, every 1.2 device has them): the client generator's submission ring
        VkPhysicalDeviceTimelineSemaphoreFeatures timelineF{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
        {
            VkPhysicalDeviceTimelineSemaphoreFeatures have12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
            VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            f2.pNext = &have12;
            if (vkGetPhysicalDeviceFeatures2) vkGetPhysicalDeviceFeatures2(phys_, &f2);
            if (have12.timelineSemaphore) {
                timelineF.timelineSemaphore = VK_TRUE;
                timelineF.pNext = const_cast<void*>(dci.pNext);
                dci.pNext = &timelineF;
                timeline_ = true;
            }
        }
        // (dev: ATLAS_PIPE_STATS=1) each pipeline's executable statistics (registers, local memory...) on stderr
        VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR statsF{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_EXECUTABLE_PROPERTIES_FEATURES_KHR};
        // (ATLAS_PIPE_BUDGET=1 or strict: and each against shaders/kernel_budget.json's "pipe" part, pipeBudget)
        const char* budgetEnv = std::getenv("ATLAS_PIPE_BUDGET");
        pipeBudget_ = budgetEnv && *budgetEnv && *budgetEnv != '0';
        pipeBudgetStrict_ = budgetEnv && std::string(budgetEnv) == "strict";
        if (((std::getenv("ATLAS_PIPE_STATS") && std::getenv("ATLAS_PIPE_STATS")[0] == '1') || pipeBudget_) &&
            has(VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME)) {
            enable.push_back(VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME);
            statsF.pipelineExecutableInfo = VK_TRUE;
            statsF.pNext = const_cast<void*>(dci.pNext);
            dci.pNext = &statsF;
            pipeStats_ = true;
        }
        dci.enabledExtensionCount = uint32_t(enable.size());
        dci.ppEnabledExtensionNames = enable.data();
        VkResult cr = vkCreateDevice(phys_, &dci, nullptr, &dev_);
        if (cr != VK_SUCCESS && genLowPriority_) {
            // (a driver that refuses the priority: as before, without it)
            qci[1].pNext = nullptr;
            enable.erase(std::remove_if(enable.begin(), enable.end(), [](const char* e) { return std::strcmp(e, "VK_EXT_global_priority") == 0; }),
                         enable.end());
            genLowPriority_ = false;
            dci.enabledExtensionCount = uint32_t(enable.size());
            dci.ppEnabledExtensionNames = enable.data();
            dev_ = VK_NULL_HANDLE;
            cr = vkCreateDevice(phys_, &dci, nullptr, &dev_);
        }
        if (cr != VK_SUCCESS && budgetExt_) {
            // a driver that lists the budget extension yet refuses it: try again without
            enable.erase(std::remove_if(enable.begin(), enable.end(),
                                        [](const char* e) { return std::strcmp(e, VK_EXT_MEMORY_BUDGET_EXTENSION_NAME) == 0; }),
                         enable.end());
            budgetExt_ = false;
            dci.enabledExtensionCount = uint32_t(enable.size());
            dci.ppEnabledExtensionNames = enable.data();
            dev_ = VK_NULL_HANDLE;
            cr = vkCreateDevice(phys_, &dci, nullptr, &dev_);
        }
        if (cr != VK_SUCCESS) {
            dev_ = VK_NULL_HANDLE;
            return vkOk(cr, "vkCreateDevice", err);
        }
        vkGetDeviceQueue(dev_, family_, 0, &queue_);
        fr::gpuInit(dev_, timestampPeriod_, 64, calibratedTs_);
        if (genFamily_ != UINT32_MAX) vkGetDeviceQueue(dev_, genFamily_, genFamily_ == family_ ? 1 : 0, &genQueue_);
        if (interopCapable_) interopCapable_ = export_.load(dev_);
        return true;
    }

    bool createPipe(const uint32_t* code, size_t bytes, const std::vector<VkDescriptorType>& types, uint32_t pushBytes,
                    VkPipeline& pipe, VkPipelineLayout& layout, VkDescriptorSetLayout& setLayout, VkShaderModule& module,
                    std::string& err, const char* name = "pipe") {
        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = bytes;
        smci.pCode = code;
        if (vkCreateShaderModule(dev_, &smci, nullptr, &module) != VK_SUCCESS) return fail(err, "shader module");
        std::vector<VkDescriptorSetLayoutBinding> b(types.size());
        for (uint32_t i = 0; i < types.size(); i++) b[i] = {i, types[i], 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = uint32_t(b.size());
        dl.pBindings = b.data();
        if (vkCreateDescriptorSetLayout(dev_, &dl, nullptr, &setLayout) != VK_SUCCESS) return fail(err, "set layout");
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &setLayout;
        pl.pushConstantRangeCount = pushBytes ? 1 : 0;
        pl.pPushConstantRanges = pushBytes ? &pr : nullptr;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &layout) != VK_SUCCESS) return fail(err, "pipeline layout");
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cp.stage.module = module;
        cp.stage.pName = "main";
        cp.layout = layout;
        if (makePipeline(cp, &pipe, name) != VK_SUCCESS) return fail(err, "pipeline");
        return true;
    }

    // Every compute pipeline is made here: timed (a TRACE line each: which one the driver's own compile spends on; a
    // new far kernel took ~6 min in NVIDIA's JIT once, then its disk cache), and through a pipeline cache of our own
    // when ATLAS_PIPELINE_CACHE names a file (read once, written back after each pipeline that took long)
    // (ATLAS_PIPE_STATS=1) the driver's statistics of a pipeline's executables, one line each
    bool pipeStats_ = false;
    // (ATLAS_PIPE_BUDGET) a pipeline's driver statistics against its budget in shaders/kernel_budget.json ("pipe", then
    // the vendor, then the pipeline's name: registers, shared, local, stack, binary in bytes; local memory is the
    // statistic's low 32 bits, bit 36 a flag): TRACE FAIL pipebudget for each over it, the process ends (exit 3) when
    // strict. A ratchet: the budget changes only in a deliberate commit.
    bool pipeBudget_ = false, pipeBudgetStrict_ = false;
    void pipeBudget(const char* name, const std::vector<VkPipelineExecutableStatisticKHR>& st) {
        static const std::string text = [] {
            std::string t;
#ifdef ATLAS_KERNEL_BUDGET
            const char* path = std::getenv("ATLAS_PIPE_BUDGET_FILE") ? std::getenv("ATLAS_PIPE_BUDGET_FILE") : ATLAS_KERNEL_BUDGET;
#else
            const char* path = std::getenv("ATLAS_PIPE_BUDGET_FILE");
#endif
            if (std::FILE* f = path ? std::fopen(path, "rb") : nullptr) {
                char buf[4096];
                size_t k;
                while ((k = std::fread(buf, 1, sizeof buf, f)) > 0) t.append(buf, k);
                std::fclose(f);
            }
            return t;
        }();
        const char* vendor = props_.vendorID == 0x10DE ? "nvidia" : props_.vendorID == 0x1002 ? "amd" : props_.vendorID == 0x8086 ? "intel" : "other";
        const std::string qt = "\"";
        size_t at = text.find(qt + "pipe" + qt);
        if (at != std::string::npos) at = text.find(qt + vendor + qt, at);
        if (at != std::string::npos) at = text.find(qt + name + qt, at);
        if (at == std::string::npos) {
            std::fprintf(stderr, "TRACE pipebudget %s: no budget for %s (shaders/kernel_budget.json, pipe)\n", name, vendor);
            return;
        }
        size_t end = text.find('}', at);
        std::string obj = text.substr(at, end == std::string::npos ? std::string::npos : end - at);
        auto limit = [&](const char* key, double& v) {
            size_t k = obj.find(qt + key + qt);
            if (k == std::string::npos) return false;
            k = obj.find(':', k);
            if (k == std::string::npos) return false;
            v = std::atof(obj.c_str() + k + 1);
            return true;
        };
        auto statOf = [&](const char* statName) -> uint64_t {
            for (const auto& x : st)
                if (std::strcmp(x.name, statName) == 0 && x.format == VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR) return x.value.u64;
            return 0;
        };
        const struct { const char* key; const char* stat; uint64_t mask; } rows[] = {
            {"registers", "Register Count", ~0ull}, {"shared", "Shared Memory Size", ~0ull},
            {"local", "Local Memory Size", 0xFFFFFFFFull}, {"stack", "Stack Size", ~0ull}, {"binary", "Binary Size", ~0ull}};
        bool fail = false;
        for (const auto& r : rows) {
            double lim;
            if (!limit(r.key, lim)) continue;
            uint64_t have = statOf(r.stat) & r.mask;
            if (double(have) > lim) {
                std::fprintf(stderr, "TRACE FAIL pipebudget %s %s %llu > budget %.0f (shaders/kernel_budget.json: the far walk lives on L1, "
                                     "shared memory and spills cost every pixel)\n",
                             name, r.key, (unsigned long long)have, lim);
                fail = true;
            }
        }
        if (fail && pipeBudgetStrict_) {
            std::fflush(stderr);
            std::exit(3);
        }
    }
    void pipelineStats(VkPipeline p, const char* name) {
        auto props = reinterpret_cast<PFN_vkGetPipelineExecutablePropertiesKHR>(vkGetDeviceProcAddr(dev_, "vkGetPipelineExecutablePropertiesKHR"));
        auto stats = reinterpret_cast<PFN_vkGetPipelineExecutableStatisticsKHR>(vkGetDeviceProcAddr(dev_, "vkGetPipelineExecutableStatisticsKHR"));
        if (!props || !stats) return;
        VkPipelineInfoKHR pi{VK_STRUCTURE_TYPE_PIPELINE_INFO_KHR};
        pi.pipeline = p;
        uint32_t n = 0;
        if (props(dev_, &pi, &n, nullptr) != VK_SUCCESS) return;
        for (uint32_t e = 0; e < n; e++) {
            VkPipelineExecutableInfoKHR ei{VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INFO_KHR};
            ei.pipeline = p;
            ei.executableIndex = e;
            uint32_t m = 0;
            if (stats(dev_, &ei, &m, nullptr) != VK_SUCCESS || m == 0) continue;
            std::vector<VkPipelineExecutableStatisticKHR> st(m, {VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_STATISTIC_KHR});
            if (stats(dev_, &ei, &m, st.data()) != VK_SUCCESS) continue;
            std::string line;
            for (const auto& x : st) {
                char b[160];
                if (x.format == VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR)
                    std::snprintf(b, sizeof b, " %s=%llu;", x.name, (unsigned long long)x.value.u64);
                else if (x.format == VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR)
                    std::snprintf(b, sizeof b, " %s=%lld;", x.name, (long long)x.value.i64);
                else if (x.format == VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_FLOAT64_KHR)
                    std::snprintf(b, sizeof b, " %s=%.3f;", x.name, x.value.f64);
                else
                    std::snprintf(b, sizeof b, " %s=%s;", x.name, x.value.b32 ? "true" : "false");
                line += b;
            }
            std::fprintf(stderr, "TRACE pipestats %s[%u]:%s\n", name, e, line.c_str());
            if (pipeBudget_ && e == 0) pipeBudget(name, st);
            // (ATLAS_PIPE_IR=<dir>: the driver's internal representations of it, one file each)
            const char* irDir = std::getenv("ATLAS_PIPE_IR");
            auto irs = reinterpret_cast<PFN_vkGetPipelineExecutableInternalRepresentationsKHR>(
                vkGetDeviceProcAddr(dev_, "vkGetPipelineExecutableInternalRepresentationsKHR"));
            uint32_t ni = 0;
            if (!irDir || !*irDir || !irs || irs(dev_, &ei, &ni, nullptr) != VK_SUCCESS || ni == 0) continue;
            std::vector<VkPipelineExecutableInternalRepresentationKHR> ir(ni, {VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INTERNAL_REPRESENTATION_KHR});
            if (irs(dev_, &ei, &ni, ir.data()) != VK_SUCCESS) continue;
            std::vector<std::vector<char>> data(ni);
            for (uint32_t k = 0; k < ni; k++) {
                data[k].resize(ir[k].dataSize + 1, 0);
                ir[k].pData = data[k].data();
            }
            if (irs(dev_, &ei, &ni, ir.data()) != VK_SUCCESS) continue;
            static int seq = 0;
            for (uint32_t k = 0; k < ni; k++) {
                char path[1024];
                std::snprintf(path, sizeof path, "%s/%02d-%s-%u-%s.txt", irDir, seq, name, e, ir[k].name);
                if (FILE* f = std::fopen(path, "wb")) {
                    std::fwrite(data[k].data(), 1, ir[k].dataSize, f);
                    std::fclose(f);
                }
                std::fprintf(stderr, "TRACE pipeir %s: %s (%s, %zu bytes, text %d)\n", path, ir[k].name, ir[k].description,
                             size_t(ir[k].dataSize), int(ir[k].isText));
            }
            seq++;
        }
    }

    VkResult makePipeline(const VkComputePipelineCreateInfo& cp, VkPipeline* out, const char* name) {
        if (!pipeCacheTried_) {
            pipeCacheTried_ = true;
            std::string cachePath = pipelineCachePath();
            if (const char* path = cachePath.empty() ? nullptr : cachePath.c_str()) {
                pipeCachePath_ = path;
                std::vector<char> data;
                if (FILE* f = std::fopen(path, "rb")) {
                    std::fseek(f, 0, SEEK_END);
                    long n = std::ftell(f);
                    std::fseek(f, 0, SEEK_SET);
                    if (n > 0) {
                        data.resize(size_t(n));
                        if (std::fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
                    }
                    std::fclose(f);
                }
                pipeCacheFresh_ = data.empty();  // (no file yet: every pipeline written to it, not only the slow ones)
                VkPipelineCacheCreateInfo pc{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
                pc.initialDataSize = data.size();
                pc.pInitialData = data.empty() ? nullptr : data.data();
                if (vkCreatePipelineCache(dev_, &pc, nullptr, &pipeCache_) != VK_SUCCESS) {
                    pc.initialDataSize = 0;  // (a cache from another driver or device: start empty)
                    pc.pInitialData = nullptr;
                    if (vkCreatePipelineCache(dev_, &pc, nullptr, &pipeCache_) != VK_SUCCESS) pipeCache_ = VK_NULL_HANDLE;
                }
            }
        }
        bool counted = std::strcmp(name, "reloaded") != 0 && !std::strstr(name, "split");
        if (counted) {
            std::lock_guard<std::mutex> l(pipeprog::mu);
            std::snprintf(pipeprog::name, sizeof pipeprog::name, "%s", name);
        }
        auto t0 = std::chrono::steady_clock::now();
        ATLAS_ZONE("vk:createPipeline");
        VkComputePipelineCreateInfo cs = cp;
        if (pipeStats_) cs.flags |= VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR;
        if (pipeStats_ && std::getenv("ATLAS_PIPE_IR")) cs.flags |= VK_PIPELINE_CREATE_CAPTURE_INTERNAL_REPRESENTATIONS_BIT_KHR;
        VkResult r = vkCreateComputePipelines(dev_, pipeCache_, 1, &cs, nullptr, out);
        double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        std::fprintf(stderr, "TRACE done pipeline:%s %.2fs\n", name, s);
        if (pipeStats_ && r == VK_SUCCESS) pipelineStats(*out, name);
        if (counted) {
            uint32_t d = pipeprog::done.fetch_add(1) + 1;
            if (d > pipeprog::total.load()) pipeprog::total.store(d);  // (more kernels than the list knew)
        }
        std::lock_guard<std::mutex> cacheLock(pipeCacheMu_);  // (the split kernel's thread writes it too)
        if (pipeCache_ && (s > 0.25 || pipeCacheFresh_)) {
            size_t n = 0;
            if (vkGetPipelineCacheData(dev_, pipeCache_, &n, nullptr) == VK_SUCCESS && n) {
                std::vector<char> data(n);
                if (vkGetPipelineCacheData(dev_, pipeCache_, &n, data.data()) == VK_SUCCESS) {
                    // (a name of this process's own: a prewarm process, gen_bake_tool mode=pipes, may write the same file)
#ifdef _WIN32
                    std::string tmp = pipeCachePath_ + "." + std::to_string(_getpid()) + ".tmp";
#else
                    std::string tmp = pipeCachePath_ + "." + std::to_string(getpid()) + ".tmp";
#endif
                    if (FILE* f = std::fopen(tmp.c_str(), "wb")) {
                        bool ok = std::fwrite(data.data(), 1, n, f) == n;
                        std::fclose(f);
                        if (ok) {
                            std::remove(pipeCachePath_.c_str());
                            std::rename(tmp.c_str(), pipeCachePath_.c_str());
                        }
                    }
                }
            }
        }
        return r;
    }

    void destroyPipe(VkPipeline& p, VkPipelineLayout& l, VkDescriptorSetLayout& s, VkShaderModule& m) {
        if (p) vkDestroyPipeline(dev_, p, nullptr);
        if (l) vkDestroyPipelineLayout(dev_, l, nullptr);
        if (s) vkDestroyDescriptorSetLayout(dev_, s, nullptr);
        if (m) vkDestroyShaderModule(dev_, m, nullptr);
        p = VK_NULL_HANDLE;
        l = VK_NULL_HANDLE;
        s = VK_NULL_HANDLE;
        m = VK_NULL_HANDLE;
    }

    // ---- per frame -------------------------------------------------------------------------------------------

    // Development: ATLAS_SHADER_DIR names a folder holding atlas_far.spv, atlas_cloud.spv and atlas_sunmap.spv
    // (tools/dev/shader_watch.py compiles them on every save, atlas_far.spv last); when atlas_far.spv changes (looked
    // at every 250 ms) the far, cloud and sun map pipelines are rebuilt from them. The bindings must stay the same (a
    // layout change needs a build); a file that fails keeps the running pipelines.
    void hotReload() {
        ATLAS_ZONE_MIN("vk:hotReload", 100);
        // ATLAS_SHADER_DIR, else a folder named atlas-shaders in the working directory (the game's: a dev instance opts
        // in by having it, where a launcher's environment settings are unreliable)
        static const char* dir = [] {
            const char* e = std::getenv("ATLAS_SHADER_DIR");
            if (e && *e) return e;
            std::error_code ec;
            return std::filesystem::is_directory("atlas-shaders", ec) ? "atlas-shaders" : static_cast<const char*>(nullptr);
        }();
        if (!dir || !*dir) return;
        // a build in flight (a thread: the driver's pipeline compiles took 30-60 s for the six kernels, the game froze
        // and looked crashed): swap its pipelines in once it is done, never wait for it (ATLAS_SHADER_WAIT=1, offline
        // measurements: wait, so the frames after the first are timed on the folder's kernels)
        static const bool waitReload = std::getenv("ATLAS_SHADER_WAIT") && *std::getenv("ATLAS_SHADER_WAIT") == '1';
        if (reloadThread_.joinable()) {
            if (!reloadDone_.load(std::memory_order_acquire) && !waitReload) return;
            reloadThread_.join();
            constexpr int K = RELOAD_KERNELS;
            if (reloadOk_) {
                vkDeviceWaitIdle(dev_);  // frames in flight still use the old pipelines
                VkPipeline* pipes[K] = {&farPipe_, &cloudPipe_, &sunPipe_, &nearLightPipe_, &nearUpPipe_, &nearTempPipe_};
                VkShaderModule* mods[K] = {&farShader_, &cloudShader_, &sunShader_, &nearLightShader_, &nearUpShader_,
                                           &nearTempShader_};
                for (int i = 0; i < K; i++) {
                    if (*pipes[i]) vkDestroyPipeline(dev_, *pipes[i], nullptr);
                    if (*mods[i]) vkDestroyShaderModule(dev_, *mods[i], nullptr);
                    *pipes[i] = reloadPipe_[i];
                    *mods[i] = reloadMod_[i];
                }
                std::fprintf(stderr, "[atlas] shaders reloaded from %s\n", dir);
                farReloaded_ = true;  // (the split frames draw with it too)
            }
            for (int i = 0; i < K; i++) reloadPipe_[i] = VK_NULL_HANDLE, reloadMod_[i] = VK_NULL_HANDLE;
            reloadBusy_.store(false, std::memory_order_release);
            return;
        }
        auto now = std::chrono::steady_clock::now();
        if (now - reloadCheck_ < std::chrono::milliseconds(250)) return;
        reloadCheck_ = now;
        std::error_code ec;
        std::filesystem::path base(dir);
        auto stamp = std::filesystem::last_write_time(base / "atlas_far.spv", ec);
        if (ec || stamp == reloadStamp_) return;
        reloadStamp_ = stamp;
        reloadDone_.store(false);
        reloadBusy_.store(true, std::memory_order_release);
        reloadThread_ = std::thread([this, base] { buildReload(base); reloadDone_.store(true, std::memory_order_release); });
        if (waitReload) hotReload();  // (the swap, after the build)
    }

    static constexpr int RELOAD_KERNELS = 6;
    std::thread reloadThread_;
    std::atomic<bool> reloadDone_{false};
    std::atomic<bool> reloadBusy_{false};  // a kernel reload is building or waiting to be swapped in (atlas_info ready.pipelinesBuilding)
    bool reloadOk_ = false;
    VkShaderModule reloadMod_[RELOAD_KERNELS] = {};
    VkPipeline reloadPipe_[RELOAD_KERNELS] = {};

    // (the reload thread) the folder's kernels into reloadMod_ / reloadPipe_, reloadOk_ when all six built
    void buildReload(const std::filesystem::path& base) {
        reloadOk_ = false;
        auto read = [&](const char* name, std::vector<uint32_t>& out) {
            std::ifstream f(base / name, std::ios::binary | std::ios::ate);
            if (!f) return false;
            std::streamsize n = f.tellg();
            if (n < 20 || n % 4) return false;
            out.resize(size_t(n) / 4);
            f.seekg(0);
            return bool(f.read(reinterpret_cast<char*>(out.data()), n)) && out[0] == 0x07230203u;
        };
        constexpr int K = RELOAD_KERNELS;
        std::vector<uint32_t> code[K];
        const char* names[K] = {"atlas_far.spv", "atlas_cloud.spv", "atlas_sunmap.spv", "atlas_nearlight.spv",
                                "atlas_nearup.spv", "atlas_neartemporal.spv"};
        for (int i = 0; i < K; i++)
            if (!read(names[i], code[i])) {
                std::fprintf(stderr, "[atlas] shader reload: %s unreadable, kept the running shaders\n", names[i]);
                return;  // (reloadOk_ false: nothing to swap)
            }
        VkShaderModule* mod = reloadMod_;
        VkPipeline* pipe = reloadPipe_;
        bool ok = true;
        for (int i = 0; i < K && ok; i++) {
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = code[i].size() * 4;
            smci.pCode = code[i].data();
            ok = vkCreateShaderModule(dev_, &smci, nullptr, &mod[i]) == VK_SUCCESS;
            if (!ok) break;
            VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
            cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cp.stage.module = mod[i];
            cp.stage.pName = "main";
            cp.layout = farLayout_;
            ok = makePipeline(cp, &pipe[i], "reloaded") == VK_SUCCESS;
        }
        if (!ok) {
            for (int i = 0; i < K; i++) {
                if (pipe[i]) vkDestroyPipeline(dev_, pipe[i], nullptr);
                if (mod[i]) vkDestroyShaderModule(dev_, mod[i], nullptr);
                pipe[i] = VK_NULL_HANDLE;
                mod[i] = VK_NULL_HANDLE;
            }
            std::fprintf(stderr, "[atlas] shader reload: pipeline creation failed, kept the running shaders\n");
            return;
        }
        reloadOk_ = true;
    }

    bool prepare(Slot& s, const FrameInputs& in, const FrameGpu& frame, bool readback, std::string& err) {
        ATLAS_ZONE("vk:prepare");
        hotReload();
        uint32_t w = frame.dims[0], h = frame.dims[1];
        size_t px = size_t(w) * h;
        const PackedScene& sc = *in.scene;
        {
            // never block the frame on a stage in progress: keep drawing the current copy until it is done
            std::unique_lock<std::mutex> lock(sceneMu_, std::try_to_lock);
            if (!lock.owns_lock() && current_ < 0) lock.lock();
            // the very first frame has nothing else to draw: it waits for a staged upload still in flight
            if (lock.owns_lock() && current_ < 0 && staged_ >= 0 && copies_[staged_].revision == sc.revision &&
                !waitFence(copies_[staged_].uploadFence, "first scene upload", err))
                return false;
            if (lock.owns_lock() && staged_ >= 0 && copies_[staged_].revision == sc.revision &&
                vkGetFenceStatus(dev_, copies_[staged_].uploadFence) == VK_SUCCESS) {
                current_ = staged_;  // staged off the render thread: just flip
                staged_ = -1;
            }
            bool stale = current_ >= 0 && copies_[current_].revision != sc.revision;
            staleFrames_ = stale ? staleFrames_ + 1 : 0;
            // a newer scene the update thread could not stage (both copies were busy) is normally staged by its
            // next call within a frame or two; only upload here for the first scene or when it stays stale
            if (lock.owns_lock() && (current_ < 0 || (stale && staleFrames_ > 30)) &&
                (staged_ < 0 || copies_[staged_].revision != sc.revision)) {
                // not staged (no update thread, or both copies were busy): upload here, once nothing reads it
                int c = current_ < 0 ? 0 : current_;
                for (auto& o : slots_)
                    if (!waitSlot(o, nullptr, err)) return false;
                if (!uploadScene(copies_[c], sc, true, err)) return false;
                current_ = c;
                if (staged_ == c) staged_ = -1;
            }
        }
        if (in.cloudNoise->data() != noiseUploaded_ || !shared_[B_NOISE].buf) {
            for (auto& o : slots_)
                if (!waitSlot(o, nullptr, err)) return false;
            if (!upload(shared_[B_NOISE], in.cloudNoise->data(), in.cloudNoise->size() * 4, false, Mem::HostWrite, err)) return false;
            if (!uploadNoiseTexture(*in.cloudNoise, err)) return false;
            noiseUploaded_ = in.cloudNoise->data();
            sharedDirty_ = true;  // every slot's set re-points at the new image
            sharedDirtySlots_ = 0;
        }
        // the sun map: one persistent device buffer (frames run in order on our queue), progressively refreshed
        {
            uint32_t S = frame.sunMapRows[3] ? frame.sunMapRows[2] : 0;
            VkDeviceSize bytes = S ? VkDeviceSize(S) * (S + 1) * 8 : 16;
            if (S != sunSize_ || !shared_[B_SUNMAP].buf) {
                for (auto& o : slots_)
                    if (!waitSlot(o, nullptr, err)) return false;
                release(shared_[B_SUNMAP]);
                if (!ensure(shared_[B_SUNMAP], bytes, false, Mem::DeviceOnly, err)) return false;
                sunFresh_ = true;  // zeroed before first use, like the CPU backend's
                sunSize_ = S;
                sharedDirty_ = true;
                sharedDirtySlots_ = 0;
            }
            sunDispatchRows_ = S ? frame.sunMapRows[1] : 0;
        }
        releaseRetired(false);
        if (!stageVoxGrid(s, in, err)) return false;
        vgOpen_ = true;  // (cleared by the frame's submit)
        {
            const bool texNew = in.textures && in.texturesRevision != texRevision_;
            const size_t texWords = in.textures && !in.textures->empty() ? in.textures->size() : 1;
            const bool texFull = !shared_[B_TEX].buf || in.texturesGeneration != texGeneration_ || (texNew && texWords * 4 > shared_[B_TEX].size);
            if (texFull) {
                // the first set, a slot rewritten, or the set outgrew the buffer: into a new buffer (with room to grow); frames in
                // flight keep the old one until they are done (no wait)
                retire(shared_[B_TEX]);
                static const uint32_t none = 0u;
                const uint32_t* src = in.textures && !in.textures->empty() ? in.textures->data() : &none;
                if (!ensure(shared_[B_TEX], VkDeviceSize(texWords) * 4 * 6 / 5, false, Mem::HostWrite, err)) return false;
                std::memcpy(shared_[B_TEX].map, src, texWords * 4);
                texRevision_ = in.textures ? in.texturesRevision : 0;
                texGeneration_ = in.texturesGeneration;
                texUploadedWords_ = texWords;
            } else if (texNew) {
                // texturepack.hpp: slots are only ever appended and the table has a fixed length, so what changed is the table and the
                // tail: copied in place (frames in flight read slots that were there before)
                ATLAS_ZONE_MIN("vk:textures delta", 100);
                const uint32_t* src = in.textures->data();
                size_t table = std::min<size_t>(texWords, size_t(src[0]) + 1);
                std::memcpy(shared_[B_TEX].map, src, table * 4);
                if (texWords > texUploadedWords_) {
                    size_t from = std::max(texUploadedWords_, table);
                    std::memcpy(static_cast<char*>(shared_[B_TEX].map) + from * 4, src + from, (texWords - from) * 4);
                }
                texUploadedWords_ = texWords;
                texRevision_ = in.texturesRevision;
            }
        }
        if (!shared_[B_BRICKS].buf || (in.bricks && in.bricksRevision != brickRevision_)) {
            // new bricks: a new buffer, as the textures (frames in flight keep the old one)
            retire(shared_[B_BRICKS]);
            static const uint32_t none = 0u;
            const uint32_t* src = in.bricks && !in.bricks->empty() ? in.bricks->data() : &none;
            size_t n = in.bricks && !in.bricks->empty() ? in.bricks->size() : 1;
            if (!upload(shared_[B_BRICKS], src, n * 4, false, Mem::HostWrite, err)) return false;
            brickRevision_ = in.bricks ? in.bricksRevision : 0;
        }
        // the far lights on the GPU: the index when it changed (a new buffer; frames in flight keep the old one)
        // (the grid's emitters, gridLightMain, need the passes too when the index holds nothing: its header, the budget)
        s.gridLights = (frame.dims[2] & FRAME_GPU_LIGHTS_BIT) != 0 && in.lights && in.gridLights && in.voxgrid;
        s.gpuLights = (frame.dims[2] & FRAME_GPU_LIGHTS_BIT) != 0 && in.lights && (in.lightRecords > 0 || s.gridLights);
        if (!shared_[B_LIGHT_SRC].buf || (s.gpuLights && in.lightsRevision != lightRevision_)) {
            retire(shared_[B_LIGHT_SRC]);
            static const Float4 none{};
            const Float4* src = s.gpuLights ? in.lights->data() : &none;
            size_t n = s.gpuLights ? in.lights->size() : 1;
            if (!upload(shared_[B_LIGHT_SRC], src, n * sizeof(Float4), false, Mem::HostWrite, err)) return false;
            lightRevision_ = s.gpuLights ? in.lightsRevision : ~0ull;
        }
        s.lightRecords = s.gpuLights ? in.lightRecords : 0;
        s.lightTiles = ((w + 31) / 32) * ((h + 31) / 32);
        // (gridLightMain appends up to LIGHT_CAND_CAP: the whole list then)
        size_t cands = s.gridLights ? size_t(GRID_LIGHT_CAND_CAP) : size_t(std::max<uint32_t>(s.lightRecords, 1));
        if (!ensure(s.bufs[B_LIGHT_CAND], cands * 64, false, Mem::DeviceOnly, err) ||
            !ensure(s.bufs[B_LIGHT_LIST], (size_t(LIGHT_LIST_HEAD) + size_t(s.lightTiles) * LIGHT_TILE_WORDS) * 4, false,
                    Mem::DeviceOnly, err))
            return false;
        // host path: outputs in cached system memory (CPU reads from BAR are uncached); GL path: device local only
        Mem outKind = readback ? Mem::Readback : Mem::DeviceOnly;
        // the near inputs: written by the CPU (host path; host copies with the near light), else by the ingest pass
        Mem nearKind = readback || hostNearInputs_ ? Mem::HostWrite : Mem::DeviceOnly;
        // (PRIMARY_NEAR_LIT's split: the far pass writes its own hits there for the light passes, device memory)
        const bool pnSplit = (frame.dims[2] & FRAME_NEAR_SPLIT_BIT) && (frame.dims[2] & FRAME_PRIMARY_NEAR_BITS);
        if (pnSplit) nearKind = Mem::DeviceOnly;
        const size_t nearBytes = pnSplit ? px * 4 : 16;
        cloudDiv_ = (frame.dims[2] & 0x80u) ? 4u : 2u;  // FRAME_CLOUDS_QUARTER
        bool ok = upload(s.bufs[B_FRAME], &frame, sizeof(FrameGpu), true, Mem::HostWrite, err) &&
                  upload(s.bufs[B_PALETTE], in.palette->data(), in.palette->size() * sizeof(MaterialGpu), false, Mem::HostWrite, err) &&
                  upload(s.bufs[B_WEATHER], in.weather->data(), in.weather->size() * 4, false, Mem::HostWrite, err) &&
                  upload(s.bufs[B_ACTORS], in.actors.data(), in.actors.size() * sizeof(ActorGpu), false, Mem::HostWrite, err) &&
                  ensure(s.bufs[B_COLOR], px * 16, false, outKind, err) && ensure(s.bufs[B_NORMAL], px * 4, false, outKind, err) &&
                  ensure(s.bufs[B_DEPTH], px * 4, false, outKind, err) && ensure(s.bufs[B_COUNTERS], px * 4, false, outKind, err) &&
                  ensure(s.bufs[B_NEAR], nearBytes, false, nearKind, err) && ensure(s.bufs[B_NEAR_INFO], nearBytes, false, nearKind, err) &&
                  // the near light: a pixel's worth unless the frame lights the near field (the kernel writes it then)
                  ensure(s.bufs[B_NEAR_LIGHT], (frame.dims[2] & FRAME_NEAR_LIGHT_BIT) ? px * 16 : 16, false, outKind, err) &&
                  ensure(s.bufs[B_CLOUD], size_t((w + 1) / 2) * ((h + 1) / 2) * 16, false, Mem::DeviceOnly, err) &&
                  ensure(s.bufs[B_CLOUD_DEPTH], size_t((w + 1) / 2) * ((h + 1) / 2) * 4, false, Mem::DeviceOnly, err) &&
                  ensure(s.bufs[B_WATER_ADD], pnSplit ? px * 16 : 16, false, Mem::DeviceOnly, err);
        if (ok) {
            VkBuffer before = s.bufs[B_SURFACE].buf;
            VkBuffer ageBefore = s.bufs[B_NEAR_AGE].buf;
            VkBuffer hitBefore = s.bufs[B_FAR_HIT].buf;
            ok = ensure(s.bufs[B_SURFACE], px * 4, false, Mem::DeviceOnly, err) &&
                 ensure(s.bufs[B_NEAR_AGE], px * 4, false, Mem::DeviceOnly, err) &&
                 ensure(s.bufs[B_FAR_HIT], (frame.dims[2] & FRAME_FAR_AMORT_BIT) ? px * 32 : 32, false, Mem::DeviceOnly, err);
            // filled with "no surface" and "no samples" in record
            if (ok && (s.bufs[B_SURFACE].buf != before || s.bufs[B_NEAR_AGE].buf != ageBefore)) s.surfaceFresh = true;
            if (ok && s.bufs[B_FAR_HIT].buf != hitBefore) s.hitFresh = true;
        }
        return ok;
    }

    void refreshDescriptors(Slot& s) {
        ATLAS_ZONE_MIN("vk:refreshDescriptors", 100);
        const SceneCopy& sc = copies_[current_];
        int prev = prevSlot_ < 0 ? int(&s - slots_) : prevSlot_;
        VkBuffer prevBuf = slots_[prev].bufs[B_SURFACE].buf;  // (its cloud buffers are allocated with it)
        VkBuffer prevNear = slots_[prev].bufs[B_NEAR_LIGHT].buf;  // (not: it grows when the near light turns on)
        VkBuffer prevHit = slots_[prev].bufs[B_FAR_HIT].buf;  // (nor: it grows when the far range is amortised)
        if (s.farDirty || s.boundCopy != current_ || s.boundGeneration != sc.generation || sharedDirty_ || s.boundPrev != prevBuf ||
            s.boundPrevNear != prevNear || s.boundPrevHit != prevHit) {
            s.boundPrev = prevBuf;
            s.boundPrevNear = prevNear;
            s.boundPrevHit = prevHit;
            VkDescriptorBufferInfo info[BINDINGS];
            VkWriteDescriptorSet wr[BINDINGS];
            VkDescriptorImageInfo noiseInfo{noiseSampler_, noiseView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
            for (uint32_t i = 0; i < BINDINGS; i++) {
                if (i == B_NOISE_TEX) {
                    wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                    wr[i].dstSet = s.farSet;
                    wr[i].dstBinding = i;
                    wr[i].descriptorCount = 1;
                    wr[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                    wr[i].pImageInfo = &noiseInfo;
                    continue;
                }
                const Slot& ps = slots_[prev];
                const Buf& b = i == B_PREV_SURFACE ? ps.bufs[B_SURFACE]
                             : i == B_PREV_CLOUD ? ps.bufs[B_CLOUD]
                             : i == B_PREV_CLOUD_DEPTH ? ps.bufs[B_CLOUD_DEPTH]
                             : i == B_PREV_NEAR_LIGHT ? (ps.bufs[B_NEAR_LIGHT].buf ? ps.bufs[B_NEAR_LIGHT] : s.bufs[B_NEAR_LIGHT])
                             : i == B_PREV_NEAR_AGE ? (ps.bufs[B_NEAR_AGE].buf ? ps.bufs[B_NEAR_AGE] : s.bufs[B_NEAR_AGE])
                             : i == B_PREV_FAR_HIT ? (ps.bufs[B_FAR_HIT].buf ? ps.bufs[B_FAR_HIT] : s.bufs[B_FAR_HIT])
                             : perSlot(i) ? s.bufs[i] : (isScene(i) ? sc.bufs[i] : shared_[i]);
                info[i] = {b.buf, 0, i == B_FRAME ? uniformRange(b) : storageRange(b)};
                wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                wr[i].dstSet = s.farSet;
                wr[i].dstBinding = i;
                wr[i].descriptorCount = 1;
                wr[i].descriptorType = i == B_FRAME ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                wr[i].pBufferInfo = &info[i];
            }
            vkUpdateDescriptorSets(dev_, BINDINGS, wr, 0, nullptr);
            s.farDirty = false;
            s.boundCopy = current_;
            s.boundGeneration = sc.generation;
            sharedDirtySlots_ |= 1u << uint32_t(&s - slots_);
            if (sharedDirtySlots_ == (1u << SLOTS) - 1) sharedDirty_ = false;
        }
        if (attached_ && s.publishDirty) {
            // bindings 0-2 the colour, normal and depth buffers, 3-5 their images, 6 the near light buffer, 7 its image
            const uint32_t bufOf[4] = {B_COLOR, B_NORMAL, B_DEPTH, B_NEAR_LIGHT};
            const int imgOf[4] = {IMG_COLOR, IMG_NORMAL, IMG_DEPTH, IMG_NEAR_LIGHT};
            const uint32_t bufAt[4] = {0, 1, 2, 6}, imgAt[4] = {3, 4, 5, 7};
            VkDescriptorBufferInfo bi[5];
            VkDescriptorImageInfo ii[4];
            VkWriteDescriptorSet wr[9];
            for (uint32_t k = 0; k < 4; k++) {
                bi[k] = {s.bufs[bufOf[k]].buf, 0, storageRange(s.bufs[bufOf[k]])};
                ii[k] = {VK_NULL_HANDLE, images_[imgOf[k]].view, VK_IMAGE_LAYOUT_GENERAL};
                VkWriteDescriptorSet& b = wr[2 * k];
                VkWriteDescriptorSet& im = wr[2 * k + 1];
                b = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                b.dstSet = s.publishSet;
                b.dstBinding = bufAt[k];
                b.descriptorCount = 1;
                b.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                b.pBufferInfo = &bi[k];
                im = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                im.dstSet = s.publishSet;
                im.dstBinding = imgAt[k];
                im.descriptorCount = 1;
                im.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                im.pImageInfo = &ii[k];
            }
            // 8: the water's surface (waterAdd; a pixel's worth unless the split under PRIMARY_NEAR_LIT sized it)
            bi[4] = {s.bufs[B_WATER_ADD].buf, 0, storageRange(s.bufs[B_WATER_ADD])};
            wr[8] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            wr[8].dstSet = s.publishSet;
            wr[8].dstBinding = 8;
            wr[8].descriptorCount = 1;
            wr[8].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            wr[8].pBufferInfo = &bi[4];
            vkUpdateDescriptorSets(dev_, 9, wr, 0, nullptr);
            s.publishDirty = false;
        }
        if (attached_ && s.ingestDirty) {
            // bindings 0, 1: the near depth image and buffer; 2, 3: the near info image and buffer
            VkDescriptorImageInfo ii[2] = {{VK_NULL_HANDLE, images_[IMG_NEAR].view, VK_IMAGE_LAYOUT_GENERAL},
                                           {VK_NULL_HANDLE, images_[IMG_NEAR_INFO].view, VK_IMAGE_LAYOUT_GENERAL}};
            VkDescriptorBufferInfo bi[2] = {{s.bufs[B_NEAR].buf, 0, storageRange(s.bufs[B_NEAR])},
                                            {s.bufs[B_NEAR_INFO].buf, 0, storageRange(s.bufs[B_NEAR_INFO])}};
            VkWriteDescriptorSet wr[4];
            for (uint32_t i = 0; i < 4; i++) {
                wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                wr[i].dstSet = s.ingestSet;
                wr[i].dstBinding = i;
                wr[i].descriptorCount = 1;
                if (i % 2 == 0) {
                    wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    wr[i].pImageInfo = &ii[i / 2];
                } else {
                    wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                    wr[i].pBufferInfo = &bi[i / 2];
                }
            }
            vkUpdateDescriptorSets(dev_, 4, wr, 0, nullptr);
            s.ingestDirty = false;
        }
    }

    VkDeviceSize storageRange(const Buf& b) const { return std::min<VkDeviceSize>(b.size, maxStorageRange_); }
    VkDeviceSize uniformRange(const Buf& b) const { return std::min<VkDeviceSize>(b.size, props_.limits.maxUniformBufferRange); }

    void begin(Slot& s) {
        vkResetCommandBuffer(s.cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(s.cmd, &bi);
        s.gt.begin(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);  // (after the GL-ready wait, like the frame query below)
        if (s.query) {
            vkCmdResetQueryPool(s.cmd, s.query, 0, 4);
            // at the compute stage, which the GL-ready semaphore wait blocks: the time is atlas's own work only
            vkCmdWriteTimestamp(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, s.query, 0);
        }
    }

    // ---- the level-0 grid (voxgrid.hpp, atlas_voxgrid.slang) ----------------------------------------------------

    GlTimes glTimes() const override { return glTimes_; }
    // (a reload building, and the split frames' far kernel still compiling on its thread)
    int pipelinesBuilding() const override {
        return (reloadBusy_.load(std::memory_order_acquire) ? 1 : 0) + (pipeprog::split.load() == 1 ? 1 : 0);
    }

    VoxGridTimes voxgridTimes() const override {
        VoxGridTimes t;
        t.lastMs = vgLastMs_;
        t.totalMs = vgMsTotal_;
        t.lastSections = vgLastSections_;
        t.lastJobs = vgLastJobs_;
        t.lastStates = vgLastStates_;
        t.stageCpuMs = vgStageMs_;
        t.stageCpuMaxMs = vgStageMaxMs_;
        t.stageCpuRecentMs = std::max(vgStagePrev_, vgStageCur_);
        t.takeMs = vgTakeMs_;
        t.takeMaxMs = vgTakeMaxMs_;
        return t;
    }

    bool createVoxGridPipes(std::string& err) {
        std::vector<VkDescriptorType> types(VG_BINDINGS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        if (!createPipe(atlas_vg_section_spv, atlas_vg_section_spv_size, types, 32, vgPipes_[0], vgLayout_, vgSetLayout_,
                        vgShaders_[0], err, "vgSectionMain"))
            return false;
        const uint32_t* code[VG_KERNELS] = {atlas_vg_section_spv, atlas_vg_satx_spv, atlas_vg_satz_spv, atlas_vg_saty_spv,
                                            atlas_vg_grow_spv, atlas_vg_fill_spv, atlas_vg_ingest_spv, atlas_vg_down_spv,
                                            atlas_vg_pack_spv, atlas_vg_unpack_spv, atlas_vg_cand_spv, atlas_vg_rank_spv,
                                            atlas_vg_rankcut_spv, atlas_vg_ranklist_spv};
        const size_t bytes[VG_KERNELS] = {atlas_vg_section_spv_size, atlas_vg_satx_spv_size, atlas_vg_satz_spv_size,
                                          atlas_vg_saty_spv_size, atlas_vg_grow_spv_size, atlas_vg_fill_spv_size,
                                          atlas_vg_ingest_spv_size, atlas_vg_down_spv_size, atlas_vg_pack_spv_size,
                                          atlas_vg_unpack_spv_size, atlas_vg_cand_spv_size, atlas_vg_rank_spv_size,
                                          atlas_vg_rankcut_spv_size, atlas_vg_ranklist_spv_size};
        for (int k = 1; k < VG_KERNELS; k++) {
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = bytes[k];
            smci.pCode = code[k];
            if (vkCreateShaderModule(dev_, &smci, nullptr, &vgShaders_[k]) != VK_SUCCESS) return fail(err, "voxgrid shader");
            VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
            cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cp.stage.module = vgShaders_[k];
            cp.stage.pName = "main";
            cp.layout = vgLayout_;
            if (makePipeline(cp, &vgPipes_[k], "voxgridKernel") != VK_SUCCESS)
                return fail(err, "voxgrid pipeline");
        }
        return true;
    }

    // Takes the grid's next batch (prepare: the slot was waited) into the slot's host buffers; recordVoxGrid records
    // the copies and kernels. The far set binds the grid's buffers always (16-byte stand-ins until there is a grid).
    bool stageVoxGrid(Slot& s, const FrameInputs& in, std::string& err) {
        ATLAS_ZONE("vk:stageVoxGrid");
        auto c0 = std::chrono::steady_clock::now();
        bool ok = stageVoxGridTimed(s, in, err);
        auto c1 = std::chrono::steady_clock::now();
        vgStageMs_ = std::chrono::duration<double, std::milli>(c1 - c0).count();
        vgStageMaxMs_ = std::max(vgStageMaxMs_, vgStageMs_);
        // the worst over the last 2-4 s (two buckets): whether it persists
        if (c1 - vgStageBucket_ > std::chrono::seconds(2)) {
            vgStagePrev_ = vgStageCur_;
            vgStageCur_ = 0;
            vgStageBucket_ = c1;
        }
        vgStageCur_ = std::max(vgStageCur_, vgStageMs_);
        // the staging's budget (ATLAS_STAGE_MS, 2 ms of the render thread a frame): over it, this frame's batch budgets
        // shrink in proportion; well under it, they grow back (a tenth a frame) to their maxima
        if (in.voxgrid) {
            static const double target = [] {
                const char* e = std::getenv("ATLAS_STAGE_MS");
                return e && std::atof(e) > 0 ? std::atof(e) : 2.0;
            }();
            float& q = in.voxgrid->budgetScale;
            if (vgStageMs_ > target) q = std::max(0.25f, q * float(std::clamp(0.8 * target / vgStageMs_, 0.5, 0.9)));
            else if (vgStageMs_ < 0.5 * target) q = std::min(1.0f, q * 1.1f);
        }
        return ok;
    }
    bool stageVoxGridTimed(Slot& s, const FrameInputs& in, std::string& err) {
        if (vgOpen_) {
            // (the last batch was taken but its frame failed before the submit: the books thought it written)
            vgOpen_ = false;
            if (in.voxgrid) in.voxgrid->forceReplace();
            for (auto& o : slots_) o.vgResultN = 0, o.vgPackRead = 0, o.vgRankRead = false;
        }
        // (the slot was waited: its last build is done) the GPU's sections it found to be one id give their slots back
        if (s.vgResultN && in.voxgrid && s.vgResults.map) {
            std::vector<VoxGrid::UniformHit> hits;
            const uint32_t* r = static_cast<const uint32_t*>(s.vgResults.map);
            for (uint32_t k = 0; k < s.vgResultN && k < s.vgGens.size(); k++) {
                const uint32_t* j = r + size_t(k) * 4;
                if ((j[1] & 3u) == VG_UNIFORM && j[2] != ~0u && j[2] != VG_COLUMN) hits.push_back({j[0], j[2], s.vgGens[k], j[1] >> 2});
            }
            if (!hits.empty()) {
                ATLAS_ZONE_MIN("grid:gpuUniform", 100);
                in.voxgrid->gpuUniform(hits);
            }
        }
        s.vgResultN = 0;
        // (and the raw sections it packed: to the world's warm tier)
        if (s.vgPackRead && in.voxgrid && s.vgPackBack.map) {
            std::vector<World::PackDone> done;
            const uint32_t* r = static_cast<const uint32_t*>(s.vgPackBack.map);
            for (uint32_t k = 0; k < s.vgPackRead && k < s.vgPackKeys.size(); k++) {
                const uint32_t* rec = r + size_t(k) * (PACK_MAX_WORDS + 1);
                uint32_t n = packWords(rec + 1);
                if (n > PACK_MAX_WORDS) continue;
                done.push_back({s.vgPackKeys[k].first, s.vgPackKeys[k].second, rec[0],
                                std::make_shared<const std::vector<uint32_t>>(rec + 1, rec + 1 + n)});
            }
            ATLAS_ZONE_MIN("grid:packedResults", 100);
            in.voxgrid->packedResults(done);
        }
        s.vgPackRead = 0;
        s.vgUnpacks = s.vgPacks = 0;
        if (s.vgRankRead && in.voxgrid && s.vgRankBack.map) {
            const uint32_t* r = static_cast<const uint32_t*>(s.vgRankBack.map);
            uint32_t na = std::min({r[512], s.vgRankBudget, RANK_MAXL}), ne = std::min({r[513], r[518], RANK_MAXL});
            std::vector<uint32_t> admits(r + RANK_LISTS, r + RANK_LISTS + na);
            std::vector<uint32_t> evicts(r + RANK_LISTS + RANK_MAXL, r + RANK_LISTS + RANK_MAXL + ne);
            ATLAS_ZONE_MIN("grid:rankResults", 100);
            in.voxgrid->rankResults(admits, evicts, r[514], 255u - std::min(r[517], 255u));
        }
        s.vgRankRead = false;
        s.vgCands = 0;
        s.vgRankOn = false;
        s.vgCopies.clear();
        s.vgJobCount = 0;
        s.vgClear = s.vgStates = s.vgTimed = false;
        s.vgSections = 0;
        s.vgClears = s.vgFills = s.vgIngests = 0;
        s.vgGcopies.clear();
        for (int k = 0; k < VG_LEVELS; k++) s.vgDowns[k] = 0, s.vgLv[k] = {};
        VoxGrid* g = in.voxgrid;
        VgBatch b;
        if (g && g->active()) {
            static const int budget = [] {
                const char* e = std::getenv("ATLAS_VOXGRID_BUDGET");  // sections built a frame at most
                return e ? std::max(1, std::atoi(e)) : 256;  // (8 KiB each: a 2 MiB copy a frame at most, ~0.2 ms)
            }();
            ATLAS_ZONE_MIN("grid:take", 100);
            auto k0 = std::chrono::steady_clock::now();
            b = g->take(g->scaled(budget));
            vgTakeMs_ = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - k0).count();
            vgTakeMaxMs_ = std::max(vgTakeMaxMs_, vgTakeMs_);
        }
        bool work = b.clear || !b.jobs.empty() || b.coarse();
        bool fresh = false;
        size_t words = g ? g->chunkWords() : 0;
        uint32_t cap = g ? g->totalSlots() : 0;
        if (!shared_[B_VG_CHUNKS].buf || (work && (vgWords_ != words || vgCap_ != cap))) {
            // (frames in flight keep the old ones until they are done: no wait on the render thread)
            if (!work) words = 0, cap = 0;
            VkDeviceSize satBytes = 16;
            if (work) {
                satBytes = VkDeviceSize(g->D + 1) * (g->H + 1) * (g->D + 1) * 4;
                for (int k = 1; k < g->levels(); k++) {
                    VoxGrid::LevelInfo li = g->levelInfo(k);
                    satBytes = std::max(satBytes, VkDeviceSize(li.D + 1) * (li.H + 1) * (li.D + 1) * 4);
                }
            }
            for (uint32_t i = B_VG_CHUNKS; i <= B_VG_POOL; i++) retire(shared_[i]);
            retire(vgSat_);
            retire(vgCand_);
            if (!ensure(vgCand_, work ? VkDeviceSize(g->D) * g->H * g->D * 4 : 16, false, Mem::DeviceOnly, err) ||
                !ensure(vgRank_, VkDeviceSize(RANK_WORDS) * 4, false, Mem::DeviceOnly, err) ||
                !ensure(shared_[B_VG_CHUNKS], VkDeviceSize(words) * 4, false, Mem::DeviceOnly, err) ||
                !ensure(shared_[B_VG_BLOCKS], VkDeviceSize(cap) * 64 * 4, false, Mem::DeviceOnly, err) ||
                !ensure(shared_[B_VG_BITS], VkDeviceSize(cap) * 64 * 8, false, Mem::DeviceOnly, err) ||
                !ensure(shared_[B_VG_POOL], VkDeviceSize(cap) * VG_SECTION_WORDS * 4, false, Mem::DeviceOnly, err) ||
                !ensure(vgSat_, satBytes, false, Mem::DeviceOnly, err))
                return false;
            vgWords_ = words;
            vgCap_ = cap;
            vgSatBytes_ = satBytes;
            fresh = work;  // garbage until cleared
        }
        if (!work) return true;
        constexpr size_t SECTION_BYTES = size_t(VG_SECTION_WORDS) * 4;
        // the jobs buffer (uint4): the chunk jobs, the coarse levels' clears, fills, ingests (2 each), downsamples (3
        // each, by level), the class table (16 materials a uint4)
        uint32_t at = uint32_t(b.jobs.size());
        s.vgClearsAt = at, s.vgClears = uint32_t(b.clears.size()), at += s.vgClears;
        s.vgFillsAt = at, s.vgFills = uint32_t(b.fills.size()), at += s.vgFills;
        s.vgIngestAt = at, s.vgIngests = uint32_t(b.ingests.size()), at += 2 * s.vgIngests;
        for (int k = 0; k < VG_LEVELS; k++) s.vgDownAt[k] = at, s.vgDowns[k] = uint32_t(b.downs[k].size()), at += 3 * s.vgDowns[k];
        size_t ncls = b.classes ? b.classes->size() : 0;
        s.vgClassAt = at, s.vgClassN = uint32_t(ncls), at += uint32_t((ncls + 15) / 16);
        s.vgUnpackAt = at, s.vgUnpacks = uint32_t(b.packs.size()), at += s.vgUnpacks;
        s.vgPackAt = at, s.vgPacks = uint32_t(b.packJobs.size()), at += s.vgPacks;
        s.vgCandAt = at, s.vgCands = uint32_t(b.cand.size()), at += s.vgCands;
        s.vgRankOn = b.rank.on;
        s.vgRankAt = at, at += 4;
        s.vgCells = uint32_t(g->D * g->H * g->D);
        s.vgRankBudget = b.rank.budget;
        size_t dataBytes = b.secs.size() * SECTION_BYTES, jobBytes = size_t(at) * 16;
        if (!ensure(s.vgStage, dataBytes, false, Mem::Staging, err) || !ensure(s.vgJobs, jobBytes, false, Mem::HostWrite, err))
            return false;
        for (size_t k = 0; k < b.secs.size(); k++)
            std::memcpy(static_cast<char*>(s.vgStage.map) + k * SECTION_BYTES, b.secs[k]->data(), SECTION_BYTES);
        char* jm = static_cast<char*>(s.vgJobs.map);
        if (!b.jobs.empty()) std::memcpy(jm, b.jobs.data(), b.jobs.size() * 16);
        if (s.vgClears) std::memcpy(jm + size_t(s.vgClearsAt) * 16, b.clears.data(), size_t(s.vgClears) * 16);
        if (s.vgFills) std::memcpy(jm + size_t(s.vgFillsAt) * 16, b.fills.data(), size_t(s.vgFills) * 16);
        for (size_t i = 0; i < b.cand.size(); i++) {
            uint32_t job[4] = {b.cand[i].first, b.cand[i].second, 0, 0};
            std::memcpy(jm + (size_t(s.vgCandAt) + i) * 16, job, 16);
        }
        {
            // the ranking's view (atlas_voxgrid.slang rankView)
            const VgBatch::Rank& r = b.rank;
            uint32_t v[16];
            v[0] = uint32_t(r.eye[0]), v[1] = uint32_t(r.eye[1]), v[2] = uint32_t(r.eye[2]), v[3] = 0;
            std::memcpy(&v[4], r.fwd, 12);
            std::memcpy(&v[7], &r.cosView, 4);
            std::memcpy(&v[8], &r.reach, 4);
            std::memcpy(&v[9], &r.nearBall, 4);
            std::memcpy(&v[10], &r.viewOut, 4);
            v[11] = (r.budget & 0xFFFFu) | r.margin << 16;
            v[12] = uint32_t(r.ox), v[13] = uint32_t(r.oz), v[14] = r.cap, v[15] = r.free;
            std::memcpy(jm + size_t(s.vgRankAt) * 16, v, 64);
        }
        if (s.vgRankOn && !ensure(s.vgRankBack, VkDeviceSize(RANK_WORDS) * 4, false, Mem::Readback, err)) return false;
        // (packed sections' ingests read them where they are expanded: after the GPU's own in vgGsrc)
        const uint32_t G = uint32_t(b.gsecs.size());
        for (size_t k = 0; k < b.ingests.size(); k++) {
            VgIngest ing = b.ingests[k];
            if (ing.src != ~0u && (ing.src & VG_PACK_SRC)) ing.src = VG_GPU_SRC | (G + (ing.src & ~VG_PACK_SRC));
            std::memcpy(jm + (size_t(s.vgIngestAt) + 2 * k) * 16, &ing, 32);
        }
        {
            // packed sections staged (their words one after the other), each expanded into vgGsrc[G + i]
            size_t words = 0;
            for (const VgPacked& p : b.packs) words += p->size();
            if (!ensure(s.vgPackIn, words * 4, false, Mem::Staging, err)) return false;
            size_t w = 0;
            for (size_t i = 0; i < b.packs.size(); i++) {
                std::memcpy(static_cast<uint32_t*>(s.vgPackIn.map) + w, b.packs[i]->data(), b.packs[i]->size() * 4);
                uint32_t job[4] = {uint32_t(w), G + uint32_t(i), 0, 0};
                std::memcpy(jm + (size_t(s.vgUnpackAt) + i) * 16, job, 16);
                w += b.packs[i]->size();
            }
            // raw sections packed for the host's warm tier: a record of PACK_MAX_WORDS + 1 each (its hash, its words)
            if (!ensure(s.vgPackOut, size_t(b.packJobs.size()) * (PACK_MAX_WORDS + 1) * 4, false, Mem::DeviceOnly, err) ||
                !ensure(s.vgPackBack, size_t(b.packJobs.size()) * (PACK_MAX_WORDS + 1) * 4, false, Mem::Readback, err))
                return false;
            s.vgPackKeys.clear();
            for (size_t i = 0; i < b.packJobs.size(); i++) {
                uint32_t job[4] = {b.packJobs[i].src, uint32_t(i) * (PACK_MAX_WORDS + 1), 0, 0};
                std::memcpy(jm + (size_t(s.vgPackAt) + i) * 16, job, 16);
                s.vgPackKeys.push_back({b.packJobs[i].key, b.packJobs[i].gen});
            }
        }
        for (int k = 0; k < VG_LEVELS; k++)
            if (s.vgDowns[k]) std::memcpy(jm + size_t(s.vgDownAt[k]) * 16, b.downs[k].data(), size_t(s.vgDowns[k]) * 48);
        if (ncls) {
            std::memset(jm + size_t(s.vgClassAt) * 16, 0, (ncls + 15) / 16 * 16);
            std::memcpy(jm + size_t(s.vgClassAt) * 16, b.classes->data(), ncls);
        }
        constexpr VkDeviceSize SEC = VkDeviceSize(VG_SECTION_WORDS) * 4;
        s.vgGcopies.clear();
        if (!ensure(s.vgGsrc, (b.gsecs.size() + b.packs.size()) * SECTION_BYTES, false, Mem::DeviceOnly, err)) return false;
        s.vgPackCopies.clear();
        for (size_t k = 0; k < b.gsecs.size(); k++)
            s.vgGcopies.push_back({VkBuffer(b.gsecs[k].buffer), {VkDeviceSize(b.gsecs[k].offset), VkDeviceSize(k) * SEC, SEC}, false});
        // (ingestCoarseGpu) the generator's coarse chunks into their slots whole, before the passes refine them
        for (const auto& [slot, ref] : b.poolCopies)
            s.vgGcopies.push_back({VkBuffer(ref.buffer), {VkDeviceSize(ref.offset), VkDeviceSize(slot) * SEC, SEC}, true});
        // (ingestCoarseHost) a coarse chunk's packed words, expanded into vgGsrc, into its pool slot
        for (const auto& [slot, idx] : b.packPool)
            s.vgPackCopies.push_back({VkDeviceSize(G + idx) * SEC, VkDeviceSize(slot) * SEC, SEC});
        for (size_t k = 0; k < b.level0Jobs; k++) {
            const VgJob& j = b.jobs[k];
            if (j.slot == ~0u || j.slot == VG_COLUMN || j.data == VG_NO_COPY) continue;
            if (j.data & VG_GPU_SRC) {
                const VgGpuRef& g = b.gsecs[j.data & ~VG_GPU_SRC];
                s.vgGcopies.push_back({VkBuffer(g.buffer), {VkDeviceSize(g.offset), VkDeviceSize(j.slot) * SEC, SEC}, true});
            } else if (j.data & VG_PACK_SRC) {
                // (expanded into vgGsrc first: then into its slot)
                s.vgPackCopies.push_back({VkDeviceSize(G + (j.data & ~VG_PACK_SRC)) * SEC, VkDeviceSize(j.slot) * SEC, SEC});
            } else {
                s.vgCopies.push_back({VkDeviceSize(j.data) * SEC, VkDeviceSize(j.slot) * SEC, SEC});
            }
        }
        s.vgJobCount = uint32_t(b.jobs.size());
        // (level 0's jobs of the GPU's own sections: their words back, the uniform ones' slots to free)
        bool gpuLevel0 = false;
        for (size_t k = 0; k < b.level0Jobs && !gpuLevel0; k++)
            gpuLevel0 = b.jobs[k].slot != ~0u && b.jobs[k].slot != VG_COLUMN && b.jobs[k].data != VG_NO_COPY && (b.jobs[k].data & VG_GPU_SRC);
        if (gpuLevel0 && b.gens.size() >= b.level0Jobs) {
            if (!ensure(s.vgResults, b.level0Jobs * 16, false, Mem::Readback, err)) return false;
            s.vgResultN = uint32_t(b.level0Jobs);
            s.vgGens.assign(b.gens.begin(), b.gens.begin() + ptrdiff_t(b.level0Jobs));
        }
        s.vgOx = b.ox;
        s.vgOz = b.oz;
        s.vgClear = b.clear || fresh;
        s.vgStates = b.chunkStates || fresh;
        s.vgSections = uint32_t(b.sections());
        for (int k = 0; k < VG_LEVELS; k++) s.vgLv[k] = {};
        s.vgLv[0] = {s.vgClear, s.vgStates, uint32_t(g->D), uint32_t(g->H), 0u, b.ox, b.oz};
        for (int k = 1; k < g->levels(); k++) {
            VoxGrid::LevelInfo li = g->levelInfo(k);
            s.vgLv[k] = {b.levels[k].clear || fresh, b.levels[k].grow || fresh, uint32_t(li.D), uint32_t(li.H), li.chunkBase,
                         b.levels[k].ox, b.levels[k].oz};
        }
        const Buf* bound[VG_BINDINGS] = {&shared_[B_VG_CHUNKS], &shared_[B_VG_BLOCKS], &shared_[B_VG_BITS], &shared_[B_VG_POOL],
                                         &s.vgJobs, &vgSat_, &s.vgStage, &s.vgGsrc, &s.vgPackIn, &s.vgPackOut, &vgCand_,
                                         &vgRank_};
        VkDescriptorBufferInfo bi[VG_BINDINGS];
        VkWriteDescriptorSet wr[VG_BINDINGS];
        for (uint32_t i = 0; i < VG_BINDINGS; i++) {
            bi[i] = {bound[i]->buf, 0, storageRange(*bound[i])};
            wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            wr[i].dstSet = s.vgSet;
            wr[i].dstBinding = i;
            wr[i].descriptorCount = 1;
            wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            wr[i].pBufferInfo = &bi[i];
        }
        vkUpdateDescriptorSets(dev_, VG_BINDINGS, wr, 0, nullptr);
        return true;
    }

    void recordVoxGrid(Slot& s) {
        bool any = s.vgJobCount || s.vgClears || s.vgFills || s.vgIngests || !s.vgGcopies.empty() || s.vgUnpacks || s.vgPacks ||
                   s.vgCands || s.vgRankOn;
        for (int k = 0; k < VG_LEVELS; k++) any |= s.vgLv[k].clear || s.vgLv[k].grow || s.vgDowns[k];
        if (!any) return;
        VkCommandBuffer cmd = s.cmd;
        if (s.query) {
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, s.query, 2);
            s.vgTimed = true;
        }
        // earlier frames' passes may still read the slots and chunks rewritten here
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        for (int k = 0; k < VG_LEVELS; k++) {
            const Slot::VgLevelRec& L = s.vgLv[k];
            if (!L.clear || !L.D) continue;
            vkCmdFillBuffer(cmd, shared_[B_VG_CHUNKS].buf, VkDeviceSize(L.base) * 4, VkDeviceSize(L.D) * L.H * L.D * 4, VG_UNLOADED);
        }
        if (s.vgLv[0].clear) vkCmdFillBuffer(cmd, vgCand_.buf, 0, VK_WHOLE_SIZE, 0u);  // (the waiting flags of the old window)
        if (s.vgRankOn) vkCmdFillBuffer(cmd, vgRank_.buf, 0, VkDeviceSize(RANK_LISTS) * 4, 0u);
        if (!s.vgCopies.empty())
            vkCmdCopyBuffer(cmd, s.vgStage.buf, shared_[B_VG_POOL].buf, uint32_t(s.vgCopies.size()), s.vgCopies.data());
        for (const Slot::VgGpuCopy& c : s.vgGcopies)  // (the generator's writes are its own to order before the frame)
            vkCmdCopyBuffer(cmd, c.from, c.toPool ? shared_[B_VG_POOL].buf : s.vgGsrc.buf, 1, &c.region);
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vgLayout_, 0, 1, &s.vgSet, 0, nullptr);
        const uint32_t row = 65535;
        auto pmod = [](int a, uint32_t d) { return uint32_t(((a % int(d)) + int(d)) % int(d)); };
        // one group a job, in passes: the coarse levels' clears, fills, ingests, downsamples by level; then every chunk job
        auto pass = [&](int kernel, uint32_t count, uint32_t first) {
            if (!count) return;
            uint32_t pcv[8] = {s.vgLv[0].D, s.vgLv[0].H, s.vgLv[0].D, count, row, first, s.vgClassAt, s.vgClassN};
            vkCmdPushConstants(cmd, vgLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vgPipes_[kernel]);
            vkCmdDispatch(cmd, std::min(count, row), (count + row - 1) / row, 1);
            computeBarrier(cmd);
        };
        // (the host's tiers) packed sections expanded, then copied into their level-0 slots; raw ones packed
        pass(9, s.vgUnpacks, s.vgUnpackAt);
        if (!s.vgPackCopies.empty()) {
            mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            vkCmdCopyBuffer(cmd, s.vgGsrc.buf, shared_[B_VG_POOL].buf, uint32_t(s.vgPackCopies.size()), s.vgPackCopies.data());
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        }
        pass(8, s.vgPacks, s.vgPackAt);
        if (s.vgPacks) {
            mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            VkBufferCopy pc{0, 0, VkDeviceSize(s.vgPacks) * (PACK_MAX_WORDS + 1) * 4};
            vkCmdCopyBuffer(cmd, s.vgPackOut.buf, s.vgPackBack.buf, 1, &pc);
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            s.vgPackRead = s.vgPacks;
        }
        pass(5, s.vgClears, s.vgClearsAt);
        pass(5, s.vgFills, s.vgFillsAt);
        pass(6, s.vgIngests, s.vgIngestAt);
        for (int k = 0; k < VG_LEVELS; k++) pass(7, s.vgDowns[k], s.vgDownAt[k]);
        pass(0, s.vgJobCount, 0);
        if (s.vgCands || s.vgRankOn) {
            // residency: the waiting flags' changes, then (anyone waiting) every cell scored, the cut, the lists
            auto threads = [&](int kernel, uint32_t count, uint32_t first) {
                if (!count) return;
                uint32_t pcv[8] = {s.vgLv[0].D, s.vgLv[0].H, s.vgLv[0].D, count, row, first, 0, 0};
                vkCmdPushConstants(cmd, vgLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vgPipes_[kernel]);
                vkCmdDispatch(cmd, (count + 63) / 64, 1, 1);
                computeBarrier(cmd);
            };
            threads(10, s.vgCands, s.vgCandAt);
            if (s.vgRankOn) {
                threads(11, s.vgCells, s.vgRankAt);
                uint32_t pcv[8] = {s.vgLv[0].D, s.vgLv[0].H, s.vgLv[0].D, 1, row, s.vgRankAt, 0, 0};
                vkCmdPushConstants(cmd, vgLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vgPipes_[12]);
                vkCmdDispatch(cmd, 1, 1, 1);
                computeBarrier(cmd);
                threads(13, s.vgCells, s.vgRankAt);
                mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
                VkBufferCopy rc{0, 0, VkDeviceSize(RANK_WORDS) * 4};
                vkCmdCopyBuffer(cmd, vgRank_.buf, s.vgRankBack.buf, 1, &rc);
                mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
                s.vgRankRead = true;
            }
        }
        if (s.vgResultN) {
            // (the findings back to the host: pass's barrier is compute to compute)
            mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            VkBufferCopy rc{0, 0, VkDeviceSize(s.vgResultN) * 16};
            vkCmdCopyBuffer(cmd, s.vgJobs.buf, s.vgResults.buf, 1, &rc);
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        }
        // the chunk-level boxes of each level whose chunk states may have changed
        // (twice: the EMPTY chunks' boxes of empty chunks, then the UNLOADED chunks' of unloaded ones)
        for (int round = 0; round < 2 * VG_LEVELS; round++) {
            int k = round >> 1;
            uint32_t target = (round & 1) ? VG_UNLOADED : VG_EMPTY;
            const Slot::VgLevelRec& L = s.vgLv[k];
            if (!L.grow || !L.D) continue;
            mb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            vkCmdFillBuffer(cmd, vgSat_.buf, 0, VK_WHOLE_SIZE, 0u);
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            uint32_t D = L.D, H = L.H;
            uint32_t pcv[8] = {D, H, D, target, row, pmod(L.ox, D), pmod(L.oz, D), L.base};
            vkCmdPushConstants(cmd, vgLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
            const uint32_t lines[4] = {H * D, H * D, D * D, D * H * D};
            for (int q = 1; q <= 4; q++) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vgPipes_[q]);
                vkCmdDispatch(cmd, (lines[q - 1] + 63) / 64, 1, 1);
                computeBarrier(cmd);
            }
        }
        if (s.query) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s.query, 3);
        s.gt.mark(cmd, "voxgrid");
    }

    void recordFar(Slot& s, uint32_t w, uint32_t h, bool clouds, bool nearSplit = false, bool lit = false) {
        recordVoxGrid(s);  // the level-0 grid's changes first (the barrier below orders them before the far pass)
        // the previous frame (other slot) wrote the surfaces this one's cloud pass reads, and will next overwrite
        // ours: order against everything submitted before
        computeBarrier(s.cmd);
        if (sunFresh_) {
            // fresh device memory is garbage on most drivers (NVIDIA happens to hand out zeros): the sun map's
            // parameter row is only partly written, and readers must see the same bytes everywhere
            vkCmdFillBuffer(s.cmd, shared_[B_SUNMAP].buf, 0, VK_WHOLE_SIZE, 0u);
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                                 nullptr, 0, nullptr);
            sunFresh_ = false;
        }
        if (s.hitFresh) {
            // (after every read of it before: the other slot's frame read it as its last frame's hits)
            VkMemoryBarrier rb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            rb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            rb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &rb, 0, nullptr, 0,
                                 nullptr);
            vkCmdFillBuffer(s.cmd, s.bufs[B_FAR_HIT].buf, 0, VK_WHOLE_SIZE, 0u);  // (no hit: distance 0, never taken)
            transferToCompute(s.cmd);
            s.hitFresh = false;
        }
        if (s.surfaceFresh) {
            vkCmdFillBuffer(s.cmd, s.bufs[B_SURFACE].buf, 0, VK_WHOLE_SIZE, 0x7149F2CAu);  // 1e30f: no surface yet
            vkCmdFillBuffer(s.cmd, s.bufs[B_NEAR_AGE].buf, 0, VK_WHOLE_SIZE, 0u);
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                                 nullptr, 0, nullptr);
            s.surfaceFresh = false;
        }
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, farLayout_, 0, 1, &s.farSet, 0, nullptr);
        if (s.gpuLights) {
            // the far lights: counters and histogram cleared, every record culled, the budget admitted, the tiles binned
            vkCmdFillBuffer(s.cmd, s.bufs[B_LIGHT_LIST].buf, 0, LIGHT_LIST_HEAD * 4, 0u);
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                                 nullptr, 0, nullptr);
            uint32_t groups = (s.lightRecords + 63) / 64;
            if (groups) {
                vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, lightPipe_[0]);
                vkCmdDispatch(s.cmd, groups, 1, 1);
            }
            if (s.gridLights) {
                // the grid's emitters as candidates beside the index's (atlas_bricks.slang gridLightMain)
                vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, lightPipe_[3]);
                vkCmdDispatch(s.cmd, (GRID_LIGHT_THREADS + 63) / 64, 1, 1);
                groups = (GRID_LIGHT_CAND_CAP + 63) / 64;  // (up to four candidates a thread: the whole list)
            }
            computeBarrier(s.cmd);
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, lightPipe_[1]);
            vkCmdDispatch(s.cmd, groups, 1, 1);
            computeBarrier(s.cmd);
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, lightPipe_[2]);
            vkCmdDispatch(s.cmd, s.lightTiles, 1, 1);
            computeBarrier(s.cmd);
            s.gt.mark(s.cmd, "lights");
        }
        if (clouds) {
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cloudPipe_);
            uint32_t dv = cloudDiv_;
            vkCmdDispatch(s.cmd, ((w + dv - 1) / dv + 7) / 8, ((h + dv - 1) / dv + 7) / 8, 1);
            computeBarrier(s.cmd);
            s.gt.mark(s.cmd, "clouds");
        }
        // (the split frames of the unified field: their own far kernel once built; never once a shader folder reloaded the
        // far kernel, the dev loop's edits are drawn with)
        bool splitKernel = nearSplit && lit && !farReloaded_ && farSplitReady_.load(std::memory_order_acquire);
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, splitKernel ? farSplitPipe_ : farPipe_);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
        s.gt.mark(s.cmd, "far");
        if (nearSplit) {
            // the near light: one pixel of each 2 x 2 traced, then the other three filled (both after the far pass:
            // the upsample darkens the air it wrote over covered surfaces)
            computeBarrier(s.cmd);
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, nearLightPipe_);
            vkCmdDispatch(s.cmd, ((w + 1) / 2 + 7) / 8, ((h + 1) / 2 + 7) / 8, 1);
            s.gt.mark(s.cmd, "nearLight");
            computeBarrier(s.cmd);
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, nearUpPipe_);
            vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
            s.gt.mark(s.cmd, "nearUpsample");
            computeBarrier(s.cmd);  // then its history (every pixel complete before any is blended)
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, nearTempPipe_);
            vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
            s.gt.mark(s.cmd, "nearTemporal");
        }
        if (sunDispatchRows_) {
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, sunPipe_);
            vkCmdDispatch(s.cmd, (sunSize_ + 7) / 8, (sunDispatchRows_ + 1 + 7) / 8, 1);
            s.gt.mark(s.cmd, "sunMap");
        }
        prevSlot_ = int(&s - slots_);
    }

    // (FRAME_FAR_AMORT_BIT) the split frames of the unified field: the far range's primary rays reused across frames
    // (ATLAS_FAR_AMORT=0: off; read each frame, a measurement switches it)
    // (and only with the other slot's last frame to read: the first frame's, or one after a failed frame's, slot would read
    // its own buffer as it writes it; and with a buffer the device can bind)
    void farAmortBit(FrameGpu& frame, const Slot& s) {
        const char* e = std::getenv("ATLAS_FAR_AMORT");
        const bool lastOk = prevSlot_ >= 0 && prevSlot_ != int(&s - slots_);
        const bool fits = VkDeviceSize(frame.dims[0]) * frame.dims[1] * 32 <= maxStorageRange_;
        bool on = !(e && *e == '0') && lastOk && fits && (frame.dims[2] & FRAME_NEAR_SPLIT_BIT) &&
                  (frame.dims[2] & FRAME_PRIMARY_NEAR_LIT_BIT);
        if (on) frame.dims[2] |= FRAME_FAR_AMORT_BIT;
        else frame.dims[2] &= ~FRAME_FAR_AMORT_BIT;
    }

    static void transferToCompute(VkCommandBuffer cmd) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0,
                             nullptr);
    }

    static void computeBarrier(VkCommandBuffer cmd) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
    }

    bool submit(Slot& s, VkSemaphore* wait, VkSemaphore* signal, std::string& err) {
        if (s.query) vkCmdWriteTimestamp(s.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s.query, 1);
        s.gt.mark(s.cmd, "publish");
        vkEndCommandBuffer(s.cmd);
        // the GL-ready wait blocks every stage that touches the shared images: the passes and the sun map copy
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &s.cmd;
        if (wait) {
            si.waitSemaphoreCount = 1;
            si.pWaitSemaphores = wait;
            si.pWaitDstStageMask = &stage;
        }
        if (signal) {
            si.signalSemaphoreCount = 1;
            si.pSignalSemaphores = signal;
        }
        {
            ATLAS_ZONE_MIN("vk:queueSubmit", 100);
            std::lock_guard<std::mutex> q(queueMu_);  // the staging thread submits scene copies to the same queue
            vkResetFences(dev_, 1, &s.fence);  // (under the lock: frameOnGpu reads the last frame's fence)
            s.gt.submitted();
            if (!vkOk(vkQueueSubmit(queue_, 1, &si, s.fence), "vkQueueSubmit (frame)", err)) return false;
            lastFrameFence_ = s.fence;
            vgOpen_ = false;  // (the grid's batch is on its way)
        }
        s.inFlight = true;
        s.frameNumber = ++frameCounter_;
        copies_[current_].lastFrame.store(s.frameNumber);  // current_ only changes on this thread
        return true;
    }

    // Waits for the slot's previous submission and reports its GPU time.
    bool waitSlot(Slot& s, double* gpuMs, std::string& err) {
        if (!s.inFlight) return true;
        if (!waitFence(s.fence, "frame wait", err)) return false;
        s.inFlight = false;
        static const uint32_t gpuTrack = fr::trackId("GPU frames");
        s.gt.resolve("gpu frame", gpuTrack);
        // frames complete in submission order on our single queue
        if (s.frameNumber > completedFrame_.load()) completedFrame_.store(s.frameNumber);
        if (s.query && gpuMs) {
            uint64_t ts[4] = {};
            uint32_t nq = s.vgTimed ? 4u : 2u;
            vkGetQueryPoolResults(dev_, s.query, 0, nq, sizeof(ts), ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            *gpuMs = double(ts[1] - ts[0]) * timestampPeriod_ / 1e6;
            double was = frameGpuEma_.load();
            frameGpuEma_.store(was <= 0.0 ? *gpuMs : was * 0.8 + *gpuMs * 0.2);
            if (s.vgTimed) {
                vgLastMs_ = double(ts[3] - ts[2]) * timestampPeriod_ / 1e6;
                vgLastSections_ = s.vgSections;
                vgLastJobs_ = s.vgJobCount;
                vgLastStates_ = s.vgStates;
                vgMsTotal_ += vgLastMs_;
                s.vgTimed = false;
            }
        }
        return true;
    }

    // acquire: from GL (external queue family) for writing; release: back to GL.
    void imageBarriers(VkCommandBuffer cmd, bool acquire) {
        VkImageMemoryBarrier b[IMAGES];
        for (int i = 0; i < IMAGES; i++) {
            b[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b[i].image = images_[i].image;
            b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (acquire) {
                // nearDepth and nearInfo were cleared by GL at attach: keep their contents even on the first frame
                bool undefined = firstGlFrame_ && i != IMG_NEAR && i != IMG_NEAR_INFO;
                b[i].oldLayout = undefined ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : VK_QUEUE_FAMILY_EXTERNAL;
                b[i].dstQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : family_;
                b[i].srcAccessMask = 0;
                b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            } else {
                b[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = family_;
                b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
                b[i].srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                b[i].dstAccessMask = 0;
            }
        }
        uint32_t n = IMAGES;
        VkImageMemoryBarrier all[IMAGES + 1];
        for (int i = 0; i < IMAGES; i++) all[i] = b[i];
        if (sunImage_.image) {
            VkImageMemoryBarrier& sb = all[n++];
            sb = b[0];
            sb.image = sunImage_.image;
            bool undefined = acquire && sunImageFresh_;
            if (acquire) {
                sb.oldLayout = undefined ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL;
                sb.srcQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : VK_QUEUE_FAMILY_EXTERNAL;
                sb.dstQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : family_;
                sb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            } else {
                sb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            }
            if (acquire) sunImageFresh_ = false;
        }
        // acquire: the first scope must name the stages the GL-ready semaphore wait blocks (compute and transfer, see
        // submit), so the barrier chains after that wait; TOP_OF_PIPE there chains to nothing and the sun map copy
        // could start before GL let go of the image (synchronization validation: WRITE_AFTER_WRITE)
        const VkPipelineStageFlags work = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
        vkCmdPipelineBarrier(cmd, work, acquire ? work : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr,
                             n, all);
    }

    // The sun map buffer into its shared image (whole image: a few MB, a few microseconds of copy).
    void copySunToImage(VkCommandBuffer cmd) {
        // the sun pass's buffer writes before the copy reads them, and the previous frame's copy into the same image
        // before this one overwrites it (the queue ownership round trip through GL does not order two transfer
        // writes on this queue for the synchronization validator; the compute images get that from the global
        // compute barrier at the start of every frame)
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {sunSize_, sunSize_ + 1, 1};
        vkCmdCopyBufferToImage(cmd, shared_[B_SUNMAP].buf, sunImage_.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
    }

    bool glUsable() const { return glLoaded_ && GlApi::contextCurrent(); }

    void destroyShared(SharedImage& s) {
        if (glUsable()) {
            if (s.glTex) gl_.DeleteTextures(1, &s.glTex);
            if (s.glMem) gl_.DeleteMemoryObjectsEXT(1, &s.glMem);
        }
        if (s.view) vkDestroyImageView(dev_, s.view, nullptr);
        if (s.image) vkDestroyImage(dev_, s.image, nullptr);
        if (s.mem) {
            vkFreeMemory(dev_, s.mem, nullptr);
            allocated_ -= s.size;
        }
        closeHandle(s.handle);
        s = {};
    }

public:
    // regions of src into the peek buffer (host-visible), submitted and waited for (tests' readbacks)
    bool peekCopy(VkBuffer src, const std::vector<VkBufferCopy>& regions, std::string& err) {
        std::lock_guard<std::mutex> scene(sceneMu_);
        if (copies_[0].uploading && !waitFence(copies_[0].uploadFence, "peek wait", err)) return false;
        copies_[0].uploading = false;
        VkCommandBuffer cmd = copies_[0].uploadCmd;
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        vkCmdCopyBuffer(cmd, src, peek_.buf, uint32_t(regions.size()), regions.data());
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(dev_, 1, &copies_[0].uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (!vkOk(vkQueueSubmit(queue_, 1, &si, copies_[0].uploadFence), "peek", err)) return false;
        }
        return waitFence(copies_[0].uploadFence, "peek", err);
    }

    bool readVoxels(const std::vector<uint32_t>& chunk, const std::vector<uint32_t>& local, std::vector<uint32_t>& out,
                    std::string& err) override {
        size_t n = chunk.size();
        out.assign(n, 0xFFFEu);
        if (!n) return true;
        if (!shared_[B_VG_CHUNKS].buf || !shared_[B_VG_POOL].buf) {
            err = "the grid has no GPU buffers yet";
            return false;
        }
        for (auto& o : slots_)
            if (!waitSlot(o, nullptr, err)) return false;
        if (!ensure(peek_, VkDeviceSize(n) * 4, false, Mem::HostRead, err)) return false;
        std::vector<VkBufferCopy> rc(n);
        for (size_t i = 0; i < n; i++) rc[i] = VkBufferCopy{VkDeviceSize(chunk[i]) * 4, VkDeviceSize(i) * 4, 4};
        if (!peekCopy(shared_[B_VG_CHUNKS].buf, rc, err)) return false;
        std::vector<uint32_t> words(n);
        std::memcpy(words.data(), peek_.map, n * 4);
        std::vector<size_t> mixed;
        std::vector<VkBufferCopy> pc;
        for (size_t i = 0; i < n; i++) {
            uint32_t w = words[i], st = w & 3u;
            if (st == VG_EMPTY) out[i] = 0;
            else if (st == VG_UNIFORM) out[i] = (w >> 2) & 0xFFFFu;
            else if (st == VG_MIXED) {
                pc.push_back(VkBufferCopy{(VkDeviceSize(w >> 2) * VG_SECTION_WORDS + (local[i] >> 1)) * 4, VkDeviceSize(mixed.size()) * 4, 4});
                mixed.push_back(i);
            }
        }
        if (!mixed.empty()) {
            if (!peekCopy(shared_[B_VG_POOL].buf, pc, err)) return false;
            const uint32_t* p = static_cast<const uint32_t*>(peek_.map);
            for (size_t j = 0; j < mixed.size(); j++) out[mixed[j]] = (local[mixed[j]] & 1u) ? p[j] >> 16 : p[j] & 0xFFFFu;
        }
        return true;
    }

    bool readSunMap(std::vector<float>& out, std::string& err) override {
        out.clear();
        if (!sunSize_ || !shared_[B_SUNMAP].buf) return true;
        for (auto& o : slots_)
            if (!waitSlot(o, nullptr, err)) return false;
        VkDeviceSize bytes = VkDeviceSize(sunSize_) * (sunSize_ + 1) * 8;
        if (!ensure(sunReadback_, bytes, false, Mem::HostRead, err)) return false;
        std::lock_guard<std::mutex> scene(sceneMu_);
        if (copies_[0].uploading && !waitFence(copies_[0].uploadFence, "sun map readback wait", err)) return false;
        copies_[0].uploading = false;
        VkCommandBuffer cmd = copies_[0].uploadCmd;
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        VkBufferCopy rc{0, 0, bytes};
        vkCmdCopyBuffer(cmd, shared_[B_SUNMAP].buf, sunReadback_.buf, 1, &rc);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(dev_, 1, &copies_[0].uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (!vkOk(vkQueueSubmit(queue_, 1, &si, copies_[0].uploadFence), "sun map readback", err)) return false;
        }
        if (!waitFence(copies_[0].uploadFence, "sun map readback", err)) return false;
        out.resize(size_t(bytes / 4));
        std::memcpy(out.data(), sunReadback_.map, size_t(bytes));
        return true;
    }

private:

    // ---- resources -------------------------------------------------------------------------------------------

    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags need) {
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
        for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
            if ((bits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & need) == need) return t;
        return UINT32_MAX;
    }

    // The attach targets in the current mode: colour, normal, depth, nearDepth (+ semaphores for zero-copy).
    bool createTargets(uint32_t w, uint32_t h, std::string& err) {
        const VkFormat fmt[IMAGES] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R32_SFLOAT,
                                      VK_FORMAT_R32_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT};
        const GLenum glFmt[IMAGES] = {GL_RGBA16F, GL_RGBA8, GL_R32F, GL_R32F, GL_RGBA8, GL_RGBA16F};
        for (int i = 0; i < IMAGES; i++)
            if (!createImage(images_[i], fmt[i], glFmt[i], w, h, err)) return false;
        if (mode_ == Interop::ZeroCopy)
            for (auto& s : slots_)
                if (!createSemaphore(s.glReady, err) || !createSemaphore(s.vkDone, err)) return false;
        // nothing covers anything until the GL side writes the near field's distances
        float uncovered = 1e30f, zero[4] = {};
        gl_.ClearTexImage(images_[IMG_NEAR].glTex, 0, GL_RED, GL_FLOAT, &uncovered);
        gl_.ClearTexImage(images_[IMG_NEAR_INFO].glTex, 0, GL_RGBA, GL_FLOAT, zero);
        if (mode_ == Interop::HostCopy) {
            // the first upload lands a frame late with ATLAS_HOSTCOPY_LATENCY: start empty, not undefined
            gl_.ClearTexImage(images_[IMG_COLOR].glTex, 0, GL_RGBA, GL_FLOAT, zero);
            gl_.ClearTexImage(images_[IMG_NORMAL].glTex, 0, GL_RGBA, GL_FLOAT, zero);
            gl_.ClearTexImage(images_[IMG_DEPTH].glTex, 0, GL_RED, GL_FLOAT, &uncovered);
        }
        GLenum e = gl_.GetError();
        if (e != GL_NO_ERROR) {
            err = "OpenGL error 0x" + hexWord(e) + " while creating the " +
                  (mode_ == Interop::ZeroCopy ? "shared images" : "host-copy textures");
            return false;
        }
        return true;
    }

    static std::string hexWord(uint32_t v) {
        char b[16];
        std::snprintf(b, sizeof(b), "%x", v);
        return b;
    }

    bool createImage(SharedImage& s, VkFormat fmt, GLenum glFmt, uint32_t w, uint32_t h, std::string& err) {
        return mode_ == Interop::ZeroCopy ? createShared(s, fmt, glFmt, w, h, err) : createLocal(s, fmt, glFmt, w, h, err);
    }

    static constexpr VkImageUsageFlags SHARED_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                                                      VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    bool createVkImage(SharedImage& s, VkFormat fmt, uint32_t w, uint32_t h, bool exported, std::string& err) {
        VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        ext.handleTypes = MEM_HANDLE;
        VkImageCreateInfo ic{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ic.pNext = exported ? &ext : nullptr;
        ic.imageType = VK_IMAGE_TYPE_2D;
        ic.format = fmt;
        ic.extent = {w, h, 1};
        ic.mipLevels = 1;
        ic.arrayLayers = 1;
        ic.samples = VK_SAMPLE_COUNT_1_BIT;
        ic.tiling = VK_IMAGE_TILING_OPTIMAL;
        ic.usage = SHARED_USAGE;
        ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (!vkOk(vkCreateImage(dev_, &ic, nullptr, &s.image), "vkCreateImage (shared)", err)) {
            s.image = VK_NULL_HANDLE;
            return false;
        }
        VkImageMemoryRequirementsInfo2 ri{VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2};
        ri.image = s.image;
        VkMemoryDedicatedRequirements dr{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS};
        VkMemoryRequirements2 req2{VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2};
        req2.pNext = &dr;
        vkGetImageMemoryRequirements2(dev_, &ri, &req2);
        const VkMemoryRequirements& req = req2.memoryRequirements;
        // exported images are always dedicated allocations: AMD (RADV / radeonsi) needs it to carry the tiling
        // metadata across, the GL side must be told the same (GL_DEDICATED_MEMORY_OBJECT_EXT), and it is always legal
        VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        ded.image = s.image;
        VkExportMemoryAllocateInfo exp{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
        exp.pNext = &ded;
        exp.handleTypes = MEM_HANDLE;
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.pNext = exported ? static_cast<const void*>(&exp) : (dr.requiresDedicatedAllocation || dr.prefersDedicatedAllocation ? &ded : nullptr);
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (ma.memoryTypeIndex == UINT32_MAX) ma.memoryTypeIndex = memoryType(req.memoryTypeBits, 0);
        if (ma.memoryTypeIndex == UINT32_MAX) return fail(err, "no memory type for the shared image");
        if (!vkOk(allocate(ma, s.mem), "vkAllocateMemory (shared image)", err)) return false;
        s.size = req.size;
        if (!vkOk(vkBindImageMemory(dev_, s.image, s.mem, 0), "vkBindImageMemory (shared)", err)) return false;
        VkImageViewCreateInfo vc{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vc.image = s.image;
        vc.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vc.format = fmt;
        vc.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vkCreateImageView(dev_, &vc, nullptr, &s.view) != VK_SUCCESS) return fail(err, "vkCreateImageView");
        return true;
    }

    void glTextureParams(GLuint t) {
        gl_.TextureParameteri(t, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        gl_.TextureParameteri(t, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        gl_.TextureParameteri(t, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        gl_.TextureParameteri(t, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    bool createShared(SharedImage& s, VkFormat fmt, GLenum glFmt, uint32_t w, uint32_t h, std::string& err) {
        if (!createVkImage(s, fmt, w, h, true, err)) return false;
        if (!export_.memory(dev_, s.mem, s.handle)) return fail(err, "exporting the image memory failed");
        gl_.CreateMemoryObjectsEXT(1, &s.glMem);
        GLint dedicated = 1;
        gl_.MemoryObjectParameterivEXT(s.glMem, GL_DEDICATED_MEMORY_OBJECT_EXT, &dedicated);
        glImportMemory(gl_, s.glMem, s.size, s.handle);
        gl_.CreateTextures(GL_TEXTURE_2D, 1, &s.glTex);
        gl_.TextureParameteri(s.glTex, GL_TEXTURE_TILING_EXT, GLint(GL_OPTIMAL_TILING_EXT));
        gl_.TextureStorageMem2DEXT(s.glTex, 1, glFmt, GLsizei(w), GLsizei(h), s.glMem, 0);
        glTextureParams(s.glTex);
        GLenum e = gl_.GetError();
        if (e != GL_NO_ERROR) {
            err = "OpenGL error 0x" + hexWord(e) + " importing a shared image";
            return false;
        }
        return true;
    }

    // host-copy: a Vulkan-only image the publish pass writes, and a plain GL texture the frame is uploaded into
    bool createLocal(SharedImage& s, VkFormat fmt, GLenum glFmt, uint32_t w, uint32_t h, std::string& err) {
        if (!createVkImage(s, fmt, w, h, false, err)) return false;
        gl_.CreateTextures(GL_TEXTURE_2D, 1, &s.glTex);
        gl_.TextureStorage2D(s.glTex, 1, glFmt, GLsizei(w), GLsizei(h));
        glTextureParams(s.glTex);
        return true;
    }

    bool createSemaphore(SharedSemaphore& s, std::string& err) {
        VkExportSemaphoreCreateInfo exp{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
        exp.handleTypes = SEM_HANDLE;
        VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        sc.pNext = &exp;
        if (vkCreateSemaphore(dev_, &sc, nullptr, &s.sem) != VK_SUCCESS) return fail(err, "vkCreateSemaphore (shared)");
        if (!export_.semaphore(dev_, s.sem, s.handle)) return fail(err, "exporting a semaphore failed");
        gl_.GenSemaphoresEXT(1, &s.gl);
        glImportSemaphore(gl_, s.gl, s.handle);
        GLenum e = gl_.GetError();
        if (e != GL_NO_ERROR) {
            err = "OpenGL error 0x" + hexWord(e) + " importing a semaphore";
            return false;
        }
        return true;
    }

    void destroyInterop() {
        destroyShared(sunImage_);
        sunImageSize_ = 0;
        bool glOk = glUsable();
        for (auto& s : images_) destroyShared(s);
        for (auto& sl : slots_) {
            for (SharedSemaphore* sem : {&sl.glReady, &sl.vkDone}) {
                if (glOk && sem->gl) gl_.DeleteSemaphoresEXT(1, &sem->gl);
                if (sem->sem) vkDestroySemaphore(dev_, sem->sem, nullptr);
                closeHandle(sem->handle);
                *sem = {};
            }
            sl.hostPending = false;
        }
        near_.destroy(gl_, glOk);
        attached_ = false;
    }

    // A buffer frames already submitted may still read: freed once the last of them is done (releaseRetired, at each
    // frame's prepare), never waited for
    struct RetiredBuf {
        Buf b;
        uint64_t frame;
    };
    std::vector<RetiredBuf> retired_;
    void retire(Buf& b) {
        if (b.buf) retired_.push_back({b, frameCounter_});
        b = {};
    }
    void releaseRetired(bool all) {
        ATLAS_ZONE_MIN("vk:releaseRetired", 100);
        for (size_t i = 0; i < retired_.size();) {
            if (all || retired_[i].frame <= completedFrame_.load()) {
                release(retired_[i].b);
                retired_[i] = retired_.back();
                retired_.pop_back();
            } else {
                i++;
            }
        }
    }

    void release(Buf& b) {
        ATLAS_ZONE_MIN("vk:releaseBuffer", 100);
        if (b.map) vkUnmapMemory(dev_, b.mem);
        if (b.buf) vkDestroyBuffer(dev_, b.buf, nullptr);
        if (b.mem) {
            vkFreeMemory(dev_, b.mem, nullptr);
            allocated_ -= b.memBytes;
        }
        b = {};
    }

    // vkAllocateMemory with the bookkeeping atlas_memory_info reports (and the test hook's view of it)
    VkResult allocate(const VkMemoryAllocateInfo& ma, VkDeviceMemory& mem) {
        ATLAS_ZONE_MIN("vk:vkAllocateMemory", 100);
        mem = VK_NULL_HANDLE;
        VkResult r = vkAllocateMemory(dev_, &ma, nullptr, &mem);
        if (r == VK_SUCCESS) allocated_ += ma.allocationSize;
        else mem = VK_NULL_HANDLE;
        return r;
    }

    // (Re)allocates b when too small or in the wrong memory kind. The caller guarantees the GPU is not using it
    // (per-slot buffers: the slot was waited; shared buffers: all slots were waited).
    bool ensure(Buf& b, VkDeviceSize size, bool uniform, Mem kind, std::string& err) {
        size = size < 16 ? 16 : size;
        if (b.buf && b.size >= size && b.kind == kind) return true;
        ATLAS_ZONE_MIN("vk:ensure (new buffer)", 100);
        // a descriptor can only see maxStorageBufferRange bytes (lavapipe and some mobile-class parts: 128 MB)
        bool bound = kind != Mem::Staging && kind != Mem::HostRead;
        VkDeviceSize limit = uniform ? props_.limits.maxUniformBufferRange : maxStorageRange_;
        if (bound && size > limit) return fail(err, "a frame or scene buffer exceeds this device's descriptor range limit");
        release(b);
        // headroom against regrowth every frame: doubling up to 32 MiB (a batch that grows a little each frame made a
        // device allocation each time: the render thread's worst staging), a quarter beyond
        VkDeviceSize alloc = size < (32u << 20) ? size * 2 : size + size / 4;
        if (bound) alloc = std::max(size, std::min(alloc, limit));
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = alloc;
        bc.usage = (uniform ? VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) |
                   VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (!vkOk(vkCreateBuffer(dev_, &bc, nullptr, &b.buf), "vkCreateBuffer", err)) {
            b.buf = VK_NULL_HANDLE;
            return false;
        }
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        uint32_t want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        uint32_t type = UINT32_MAX;
        if (kind == Mem::DeviceOnly) {
            type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        } else {
            // small inputs: device-local host-visible (BAR) when it fits; readback: host-cached; staging: system memory
            uint32_t extra = kind == Mem::Readback || kind == Mem::HostRead ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT
                           : kind == Mem::HostWrite ? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT : 0u;
            type = memoryType(req.memoryTypeBits, want | extra);
            if (type == UINT32_MAX) type = memoryType(req.memoryTypeBits, want);
        }
        if (type == UINT32_MAX) return fail(err, "no suitable memory type");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = type;
        VkResult ar = allocate(ma, b.mem);
        if (ar == VK_ERROR_DEVICE_LOST) return vkOk(ar, "vkAllocateMemory", err);
        if (ar != VK_SUCCESS) {
            // BAR heaps are small on some systems; retry in system memory
            VkPhysicalDeviceMemoryProperties mp;
            vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
            type = UINT32_MAX;
            for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
                if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & want) == want &&
                    !(mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                    type = t;
                    break;
                }
            ma.memoryTypeIndex = type;
            if (type == UINT32_MAX) return vkOk(ar, "vkAllocateMemory (no system-memory fallback)", err);
            if (!vkOk(allocate(ma, b.mem), "vkAllocateMemory", err)) return false;
        }
        b.memBytes = req.size;
        if (!vkOk(vkBindBufferMemory(dev_, b.buf, b.mem, 0), "vkBindBufferMemory", err)) return false;
        if (kind != Mem::DeviceOnly && !vkOk(vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map), "vkMapMemory", err)) {
            b.map = nullptr;
            return false;
        }
        b.size = alloc;
        b.kind = kind;
        // whichever set points at this buffer must be rewritten
        if (&b >= shared_ && &b < shared_ + BINDINGS) {
            sharedDirty_ = true;
            sharedDirtySlots_ = 0;
        }
        for (auto& s : slots_)
            if (&b >= s.bufs && &b < s.bufs + BINDINGS) {
                s.farDirty = true;
                if (&b == &s.bufs[B_COLOR] || &b == &s.bufs[B_NORMAL] || &b == &s.bufs[B_DEPTH] ||
                    &b == &s.bufs[B_NEAR_LIGHT])
                    s.publishDirty = true;
                if (&b == &s.bufs[B_NEAR] || &b == &s.bufs[B_NEAR_INFO]) s.ingestDirty = true;
            }
        return true;
    }

    // Scene arrays -> staging (CPU) -> device-local buffers (GPU copy). The caller holds sceneMu_ and guarantees no
    // frame reads this copy. wait: block until the copy is done (render-thread fallback).
    bool uploadScene(SceneCopy& c, const PackedScene& sc, bool wait, std::string& err) {
        ATLAS_ZONE(wait ? "vk:uploadScene (render thread)" : "vk:uploadScene");
        if (c.uploading && !waitFence(c.uploadFence, "scene upload wait", err)) return false;
        c.uploading = false;
        const void* src[BINDINGS] = {};
        VkDeviceSize bytes[BINDINGS] = {}, offset[BINDINGS] = {}, total = 0;
        src[1] = sc.table.data();
        bytes[1] = sc.table.size() * 4;
        src[2] = sc.regions.data();
        bytes[2] = sc.regions.size() * sizeof(RegionGpu);
        src[3] = sc.nodes.data();
        bytes[3] = sc.nodes.size() * 4;
        src[4] = sc.leaves.data();
        bytes[4] = sc.leaves.size() * 8;
        src[5] = sc.runEnd.data();
        bytes[5] = sc.runEnd.size() * 4;
        src[6] = sc.runMat.data();
        bytes[6] = sc.runMat.size() * 4;
        src[B_HEIGHTMIP] = sc.heightMip.data();
        bytes[B_HEIGHTMIP] = sc.heightMip.size() * 4;
        src[B_GROUPS] = sc.groups.data();
        bytes[B_GROUPS] = sc.groups.size() * 4;
        for (uint32_t i : SCENE_BINDINGS) {
            offset[i] = total;
            total += (bytes[i] + 255) & ~VkDeviceSize(255);
        }
        bool realloc = false;
        for (uint32_t i : SCENE_BINDINGS) {
            VkBuffer before = c.bufs[i].buf;
            if (!ensure(c.bufs[i], bytes[i], false, Mem::DeviceOnly, err)) return false;
            realloc |= c.bufs[i].buf != before;
        }
        if (realloc) c.generation++;
        if (!ensure(c.staging, total, false, Mem::Staging, err)) return false;
        for (uint32_t i : SCENE_BINDINGS)
            if (bytes[i]) std::memcpy(static_cast<char*>(c.staging.map) + offset[i], src[i], size_t(bytes[i]));
        vkResetCommandBuffer(c.uploadCmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(c.uploadCmd, &bi);
        for (uint32_t i : SCENE_BINDINGS) {
            if (!bytes[i]) continue;
            VkBufferCopy region{offset[i], 0, bytes[i]};
            vkCmdCopyBuffer(c.uploadCmd, c.staging.buf, c.bufs[i].buf, 1, &region);
        }
        // later submissions on this queue (the frames) read what the copies wrote
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(c.uploadCmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb,
                             0, nullptr, 0, nullptr);
        vkEndCommandBuffer(c.uploadCmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &c.uploadCmd;
        vkResetFences(dev_, 1, &c.uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (!vkOk(vkQueueSubmit(queue_, 1, &si, c.uploadFence), "vkQueueSubmit (scene upload)", err)) return false;
        }
        c.uploading = true;
        c.revision = sc.revision;
        if (wait && !waitFence(c.uploadFence, "scene upload", err)) return false;
        return true;
    }

    bool upload(Buf& b, const void* data, size_t bytes, bool uniform, Mem kind, std::string& err) {
        ATLAS_ZONE_MIN("vk:upload", 100);
        if (!ensure(b, bytes, uniform, kind, err)) return false;
        if (bytes && !b.map) return fail(err, "upload into an unmapped buffer");
        if (bytes) std::memcpy(b.map, data, bytes);
        return true;
    }

    std::atomic<uint64_t> allocated_{0};  // device memory allocated by this backend (bytes)
    bool calibratedTs_ = false;           // VK_EXT_calibrated_timestamps enabled (flight recorder)
    bool budgetExt_ = false;              // VK_EXT_memory_budget enabled
    VkInstance inst_ = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkPipelineCache pipeCache_ = VK_NULL_HANDLE;  // (makePipeline: ATLAS_PIPELINE_CACHE)
    bool pipeCacheFresh_ = false;
    std::mutex pipeCacheMu_;
    std::deque<Buf> debugBufs_;                    // (debugDeviceBuffer)
    bool pipeCacheTried_ = false;
    bool vgOpen_ = false;  // (a grid batch staged and not yet submitted)
    std::string pipeCachePath_;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
    uint8_t deviceUuid_[VK_UUID_SIZE] = {};
    char deviceName_[300] = "vulkan";
    bool timestamps_ = false;
    float timestampPeriod_ = 1.0f;

    VkShaderModule farShader_ = VK_NULL_HANDLE, publishShader_ = VK_NULL_HANDLE, ingestShader_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout farSetLayout_ = VK_NULL_HANDLE, publishSetLayout_ = VK_NULL_HANDLE, ingestSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout farLayout_ = VK_NULL_HANDLE, publishLayout_ = VK_NULL_HANDLE, ingestLayout_ = VK_NULL_HANDLE;
    VkPipeline farPipe_ = VK_NULL_HANDLE, publishPipe_ = VK_NULL_HANDLE, ingestPipe_ = VK_NULL_HANDLE;
    // (the split frames' far kernel: built on its thread, used once ready; farReloaded_: a shader folder's far kernel)
    VkShaderModule farSplitShader_ = VK_NULL_HANDLE;
    VkPipeline farSplitPipe_ = VK_NULL_HANDLE;
    std::thread farSplitThread_;
    std::atomic<bool> farSplitReady_{false};
    bool farReloaded_ = false;
    VkPipeline cloudPipe_ = VK_NULL_HANDLE;
    VkPipeline sunPipe_ = VK_NULL_HANDLE;
    VkShaderModule sunShader_ = VK_NULL_HANDLE;
    VkPipeline nearLightPipe_ = VK_NULL_HANDLE, nearUpPipe_ = VK_NULL_HANDLE;
    VkShaderModule nearLightShader_ = VK_NULL_HANDLE, nearUpShader_ = VK_NULL_HANDLE;
    VkPipeline nearTempPipe_ = VK_NULL_HANDLE;
    VkPipeline lightPipe_[4] = {};  // the far lights on the GPU: lightCullMain, lightAdmitMain, lightBinMain
    VkShaderModule lightShader_[4] = {};
    uint64_t lightRevision_ = ~0ull;
    // the level-0 grid (voxgrid.hpp): its build kernels, their layout, the summed-volume table, the last build's GPU time
    // vgSectionMain, vgSatX, vgSatZ, vgSatY, vgGrowMain, vgFillMain, vgIngestMain, vgDownMain
    static constexpr int VG_KERNELS = 14;
    VkPipeline vgPipes_[VG_KERNELS] = {};
    VkShaderModule vgShaders_[VG_KERNELS] = {};
    VkPipelineLayout vgLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout vgSetLayout_ = VK_NULL_HANDLE;
    Buf vgSat_;
    Buf vgCand_, vgRank_;  // (residency on the GPU) level 0's waiting flags; the ranking's histograms and lists
    size_t vgWords_ = 0;      // chunk words of every level the buffers hold
    uint32_t vgCap_ = 0;      // slots of every level
    VkDeviceSize vgSatBytes_ = 0;
    uint64_t texRevision_ = ~0ull, texGeneration_ = 0;
    size_t texUploadedWords_ = 0;  // the texture buffer holds this many words of the host's set (texturepack.hpp)
    uint64_t brickRevision_ = ~0ull;
    double vgStageMs_ = 0, vgStageMaxMs_ = 0, vgTakeMs_ = 0, vgTakeMaxMs_ = 0, vgStagePrev_ = 0, vgStageCur_ = 0;
    std::chrono::steady_clock::time_point vgStageBucket_ = std::chrono::steady_clock::now();
    double vgLastMs_ = 0, vgMsTotal_ = 0;
    GlTimes glTimes_;
    uint32_t vgLastSections_ = 0, vgLastJobs_ = 0;
    bool vgLastStates_ = false;
    VkShaderModule nearTempShader_ = VK_NULL_HANDLE;
    uint32_t sunSize_ = 0;          // size of the sun map buffer / image now
    uint32_t sunDispatchRows_ = 0;  // this frame's sun map rows (0: no pass)
    bool sunFresh_ = false;         // the sun map buffer was (re)allocated: zero it before the next pass
    SharedImage sunImage_;          // the GL-shared sun map image (RG32F, size x size + 1), when attached
    uint32_t sunImageSize_ = 0;
    bool sunImageFresh_ = false;
    Buf sunReadback_;
    Buf peek_;  // (readVoxels: the tests' readback of the grid)
    VkShaderModule cloudShader_ = VK_NULL_HANDLE;
    std::chrono::steady_clock::time_point reloadCheck_{};  // shader hot reload (ATLAS_SHADER_DIR): last look
    std::filesystem::file_time_type reloadStamp_{};          // and the far kernel's time then
    int prevSlot_ = -1;  // slot of the last recorded frame (its surfaces feed the next cloud pass)
    uint32_t cloudDiv_ = 2;  // cloud pass resolution divisor for the frame being recorded
    VkImage noiseImg_ = VK_NULL_HANDLE;
    VkDeviceMemory noiseMem_ = VK_NULL_HANDLE;
    VkImageView noiseView_ = VK_NULL_HANDLE;
    VkSampler noiseSampler_ = VK_NULL_HANDLE;
    Buf noiseStaging_;
    VkDescriptorPool dpool_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;

    Slot slots_[SLOTS];
    Buf shared_[BINDINGS];  // only the scene (1-6) and noise (13) bindings are used
    bool sharedDirty_ = true;
    uint32_t sharedDirtySlots_ = 0;
    SceneCopy copies_[2];
    int current_ = -1, staged_ = -1;
    std::mutex sceneMu_;
    std::mutex queueMu_;  // vkQueueSubmit from the render and staging threads
    VkFence lastFrameFence_ = VK_NULL_HANDLE;  // (under queueMu_) the last frame submitted's
    VkCommandPool uploadPool_ = VK_NULL_HANDLE;
    std::atomic<uint64_t> frameCounter_{0};  // (atomic: the client generator reads it, framesSubmitted)
    int staleFrames_ = 0;
    // the client generator's queue (sharedDevice; VK_NULL_HANDLE: it shares queue_ under queueMu_)
    uint32_t genFamily_ = UINT32_MAX;
    VkQueue genQueue_ = VK_NULL_HANDLE;
    bool genLowPriority_ = false;  // (the generator's queue at VK_EXT_global_priority's low)
    bool timeline_ = false;        // (timeline semaphores enabled: the generator's submission ring)
    bool genTimestamps_ = false, float64_ = false;
    std::atomic<uint64_t> completedFrame_{0};
    const void* noiseUploaded_ = nullptr;
    uint64_t frameIndex_ = 0;

    // device choice and capabilities (for info())
    struct DeviceSeen {
        std::string name, type, api;
        bool glMatch;
        std::string unfit;
    };
    std::vector<DeviceSeen> seen_;
    int chosen_ = -1;
    bool glMatchedAtCreate_ = false;
    VkPhysicalDeviceProperties props_{};
    uint8_t driverUuid_[VK_UUID_SIZE] = {};
    std::string driverName_;
    uint32_t driverId_ = 0;
    VkDeviceSize maxStorageRange_ = 1ull << 27;
    uint32_t maxExport_ = 0;
    bool dedicatedOnly_ = false;

    // interop
    bool interopCapable_ = false, attached_ = false, firstGlFrame_ = true;
    Interop mode_ = Interop::None;
    std::string interopWhy_, vkInteropWhy_;  // why not zero-copy (empty when it is)
    ExportFns export_;
    bool glLoaded_ = false, glUuidMatch_ = false;
    std::string glRenderer_, glVersion_, glVendor_, glWinsys_, glDriverUuid_;
    double hostWaitMs_ = 0, hostUploadMs_ = 0;  // host-copy: last frame's CPU wait for the GPU, and upload time
    GlApi gl_;
    SharedImage images_[IMAGES];
    uint32_t width_ = 0, height_ = 0;
    GlNearPass near_;  // the near depth / info pass (gl_near.hpp)
    bool hostNearInputs_ = false;      // this frame's near inputs are written by the CPU (prepare's buffer kind)
    std::vector<float> nearReadback_;  // host copies with the near light: GL's near depth and info, read back
};

}  // namespace

std::unique_ptr<Backend> makeVulkanBackend(std::string& err) {
    auto b = std::make_unique<VulkanBackend>();
    if (!b->init(err)) return nullptr;
    return b;
}

}  // namespace atlas

// (atlas.h) the pipelines being built: callable from any thread while atlas_create runs on another
ATLAS_API void atlas_pipeline_progress(uint32_t* done, uint32_t* total, char* name, uint32_t cap, uint32_t* split) {
    if (done) *done = atlas::pipeprog::done.load();
    if (total) *total = atlas::pipeprog::total.load();
    if (split) *split = atlas::pipeprog::split.load();
    if (name && cap) {
        std::lock_guard<std::mutex> l(atlas::pipeprog::mu);
        std::snprintf(name, cap, "%s", atlas::pipeprog::name);
    }
}

// (api.cpp, the end of atlas_create on the Vulkan backend) the list as it was built: the total is what was counted
extern "C" void atlasPipelinesBuilt() {
    uint32_t d = atlas::pipeprog::done.load();
    if (d != atlas::pipeprog::total.load())
        std::fprintf(stderr, "TRACE warn the context built %u pipelines, its list says %u (pipeprog::INIT_KERNELS)\n", d,
                     atlas::pipeprog::total.load());
    atlas::pipeprog::total.store(d);
}
