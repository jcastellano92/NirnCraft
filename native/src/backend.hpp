// Kernel backends: the same atlas_far kernel on the CPU (Slang -> C++) or on Vulkan (Slang -> SPIR-V).
#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gpu_layout.hpp"
#include "voxgrid.hpp"
#include "world.hpp"

namespace atlas {

// (atlas_set_pipeline_cache) the pipeline cache's file: ATLAS_PIPELINE_CACHE, else the one set; "": none
std::string pipelineCachePath();
// the native build's content hash (cmake/buildhash.cmake): the caches' build fingerprint
uint64_t buildHash();
void setPipelineCachePath(const std::string& p);

struct GlFrameIO {
    uint32_t nearDepthTex = 0, depthZeroToOne = 0, reversedZ = 0;
    float nearInvProjection[16] = {};
    uint32_t dstColor = 0, dstNormal = 0, dstDepth = 0;
    uint32_t dstSunMap = 0;  // caller texture (RG32F, size x size + 1) the sun map is copied into, or 0
    // (abi 0.15, FRAME_NEAR_LIGHT) the near field's surface info (RGBA8) and the caller's near-light texture (RGBA16F)
    uint32_t nearInfoTex = 0, dstNearLight = 0;
};

// FRAME_NEAR_LIGHT (atlas.h ATLAS_FRAME_NEAR_LIGHT; the kernels' atlas_shared.slang)
constexpr uint32_t FRAME_NEAR_LIGHT_BIT = 0x20000u;
constexpr uint32_t FRAME_NEAR_SPLIT_BIT = 0x40000u;
// (internal) the far lights culled, ranked and binned on the device (atlas_far.slang lightCullMain): backends that can
constexpr uint32_t FRAME_GPU_LIGHTS_BIT = 0x80000000u;
// (abi 0.17) ATLAS_FRAME_PRIMARY_NEAR / _LIT: atlas's primary rays draw the near field; the near light is then an output
// without near inputs (the surfaces are atlas's own)
constexpr uint32_t FRAME_PRIMARY_NEAR_BITS = 0x80000u | 0x100000u;
constexpr uint32_t FRAME_PRIMARY_NEAR_LIT_BIT = 0x100000u;  // (internal) the near light at half resolution (atlas_shared.slang)

struct FrameInputs {
    std::shared_ptr<const PackedScene> sceneHold;  // keeps the snapshot alive for the frame
    const PackedScene* scene;
    FrameGpu frame;
    const std::vector<MaterialGpu>* palette;
    const std::vector<uint32_t>* weather;
    const std::vector<uint32_t>* cloudNoise;  // 64^3 RGBA8
    const float* nearDepth = nullptr;         // host path: w*h near-field view distances (null: none)
    bool wantNearDepth = false;               // caller asked for the near-depth skip (GL path: shared image)
    const uint32_t* nearInfo = nullptr;       // host path: w*h near-field surface info, RGBA8 (FRAME_NEAR_LIGHT)
    std::vector<ActorGpu> actors;             // at least one element (count in frame.weatherParams[3])
    float preExposure = 1.0f;                 // (abi 0.16) the host's exposure, applied when the half-float images are written
    VoxGrid* voxgrid = nullptr;               // the level-0 grid's books: the backend takes its batches (voxgrid.hpp)
    const std::vector<uint32_t>* textures = nullptr;  // the near field's textures, packed (voxgrid.hpp packTextures)
    uint64_t texturesRevision = 0;
    uint64_t texturesGeneration = 0;  // bumped when a slot already there was rewritten (texturepack.hpp): upload it all again
    const std::vector<uint32_t>* bricks = nullptr;  // (abi 0.18) the bricks, packed (bricks.hpp BrickStore::pack)
    uint64_t bricksRevision = 0;
    bool gridLights = false;  // (abi 0.18) some material emits: the grid's emitters become lights (gridLightMain)
    // (FRAME_GPU_LIGHTS_BIT) the far-light index as lights.cpp packs it for the GPU (Lights::packGpu: header, cells,
    // records), uploaded when its revision changes; the frame culls and bins it on the device
    const std::vector<Float4>* lights = nullptr;
    uint64_t lightsRevision = 0;
    uint32_t lightRecords = 0;
};

struct FrameTargets {
    std::vector<float> color;  // linear RGBA, 4 floats per pixel
    std::vector<uint32_t> normal, counters;
    std::vector<float> depth;
    std::vector<float> nearLight;  // RGBA, 4 floats per pixel (FRAME_NEAR_LIGHT frames only)
};

// GL texture names of the images shared with the caller's OpenGL context (bottom-up rows, GL convention).
struct GlTargets {
    uint32_t color = 0, normal = 0, depth = 0, nearDepth = 0;
    uint32_t width = 0, height = 0;
};

// Why a backend call failed, beyond its message: decides the status the C API returns (atlas.h AtlasStatus).
enum class Fault { None, Error, DeviceLost, OutOfMemory, Timeout };

// Memory of the device (atlas_memory_info); zeros where unknown.
struct MemoryInfo {
    uint64_t deviceLocalBytes = 0, deviceLocalBudget = 0, deviceLocalUsage = 0, hostHeapBytes = 0, atlasBytes = 0;
    uint64_t maxStorageRange = 0;  // (what one storage descriptor sees: the grid's pool is one)
    bool integrated = false, budgetExt = false;
};

class Backend {
public:
    virtual ~Backend() = default;
    virtual const char* name() const = 0;

    // ---- failure state (every backend): what the last failed call ran into, and whether the device is gone
    Fault fault() const { return fault_; }
    // True once the device is lost or hung: the backend refuses every further GPU call (and frees nothing it would
    // have to wait on).
    bool lost() const { return lost_; }
    void clearFault() { if (!lost_) fault_ = Fault::None; }
    // Test hook (atlas_debug_fault): fail as the driver would, after `afterFrames` more frames.
    void injectFault(Fault f, uint32_t afterFrames) {
        injected_ = f;
        injectCountdown_ = afterFrames;
    }
    virtual bool memoryInfo(MemoryInfo&, std::string& err) {
        err = "this backend has no device memory";
        return false;
    }
    // One line of JSON for logs: device, driver, interop mode and the features it was chosen from.
    virtual std::string info() const { return std::string("{\"backend\":\"") + name() + "\"}"; }
    // Whether the frame's far lights may be culled and binned on the device (FRAME_GPU_LIGHTS_BIT, FrameInputs::lights):
    // the CPU backend bins them on the CPU (Lights::frame)
    virtual bool gpuLights() const { return false; }
    // Whether it renders on a device (Vulkan): the CPU's own fallbacks (a CPU level 0 for what the grid gave up) are
    // the CPU backend's alone
    virtual bool onDevice() const { return false; }
    // (test hook, atlas_debug_coarse_chunks) a buffer on the device holding `bytes` of data, kept until the backend
    // goes: its handle as ingestGpu / ingestCoarseGpu take it (0: none on this backend)
    virtual uint64_t debugDeviceBuffer(const void*, size_t) { return 0; }
    virtual bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) = 0;
    // Called from the update thread with a new scene revision: a backend may copy it to the GPU here, off the
    // render thread. Returns false when it could not (the frame then uploads it itself).
    virtual bool stageScene(const PackedScene&) { return false; }
    // the level-0 grid's last build on the device: GPU ms, sections and jobs in it, whether the chunks' boxes were
    // regrown; and the GPU ms of every build so far
    struct VoxGridTimes {
        double lastMs = 0, totalMs = 0;
        uint32_t lastSections = 0, lastJobs = 0;
        bool lastStates = false;
        double stageCpuMs = 0, stageCpuMaxMs = 0;  // the render thread's share (taking a batch, staging it): last, worst
    };
    virtual VoxGridTimes voxgridTimes() const { return {}; }
    // The game path's wall time on the render thread, eased (ms): waiting for the slot's previous frame, preparing
    // (uploads, staging), recording and submitting
    struct GlTimes {
        double waitMs = 0, prepareMs = 0, recordMs = 0;
    };
    virtual GlTimes glTimes() const { return {}; }

    // Game path (render thread, the game's GL context current): zero-copy through shared memory and semaphores
    // when the driver pair supports it, else frames copied through host memory. Default: unsupported.
    virtual bool glAttach(uint32_t, uint32_t, GlTargets&, std::string& err) {
        err = "this backend cannot share images with OpenGL (use the Vulkan backend)";
        return false;
    }
    // Renders straight into the shared images. GL commands issued after it returns see the frame (a GL-side
    // semaphore wait is queued); the CPU does not block on the GPU. gpuMs reports the previous frame.
    virtual bool glRender(const FrameInputs&, const GlFrameIO*, double*, std::string& err) {
        err = "not attached";
        return false;
    }
    virtual void glDetach() {}
    // (the client generator, gen_engine.cpp) the device for kernels that write straight into the grid's sections:
    // Vulkan handles as void*, a queue of its own when the device has one (else the frames' queue, submitted under
    // queueMu), 64-bit floats enabled or not. False on a backend without a device.
    struct SharedDevice {
        void *instance = nullptr, *physical = nullptr, *device = nullptr, *queue = nullptr;
        uint32_t family = 0, frameFamily = 0;  // the queue's family; the frames' (buffers both read: concurrent)
        std::mutex* queueMu = nullptr;         // non-null: the queue is the frames' own
        bool float64 = false;
        float timestampPeriod = 1.0f;
        bool timestamps = false;
    };
    virtual bool sharedDevice(SharedDevice&) { return false; }
    // frames submitted, and the last one known complete (any thread)
    virtual uint64_t framesSubmitted() const { return 0; }
    virtual uint64_t framesCompleted() const { return 0; }
    // The sun map as last computed (size * (size + 1) float2, empty when none). Waits for the GPU.
    virtual bool readSunMap(std::vector<float>& out, std::string& err) {
        err = "no sun map";
        out.clear();
        return false;
    }

protected:
    // Records a failure; a lost device (or a hang) is permanent for this backend.
    bool failWith(Fault f, std::string& err, const std::string& msg) {
        fault_ = f;
        if (f == Fault::DeviceLost || f == Fault::Timeout) lost_ = true;
        err = msg;
        return false;
    }
    // Called at the start of every frame: true (and err set, the fault recorded) when an injected fault fires now.
    bool injectedNow(std::string& err) {
        if (injected_ == Fault::None) return false;
        if (injectCountdown_ > 0) {
            injectCountdown_--;
            return false;
        }
        Fault f = injected_;
        injected_ = Fault::None;
        const char* what = f == Fault::DeviceLost    ? "VK_ERROR_DEVICE_LOST (injected by atlas_debug_fault / ATLAS_FAULT)"
                           : f == Fault::Timeout     ? "GPU timeout (injected by atlas_debug_fault / ATLAS_FAULT)"
                           : f == Fault::OutOfMemory ? "VK_ERROR_OUT_OF_DEVICE_MEMORY (injected by atlas_debug_fault / ATLAS_FAULT)"
                                                     : "injected error";
        return !failWith(f, err, what);
    }

    Fault fault_ = Fault::None;
    bool lost_ = false;

private:
    Fault injected_ = Fault::None;
    uint32_t injectCountdown_ = 0;
};

std::unique_ptr<Backend> makeCpuBackend();
std::unique_ptr<Backend> makeVulkanBackend(std::string& err);  // null + err when no usable device

}  // namespace atlas
