// Kernel backends: the same atlas_far kernel on the CPU (Slang -> C++) or on Vulkan (Slang -> SPIR-V).
#pragma once
#include <cstdint>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <utility>

#include "gpu_layout.hpp"
#include "voxgrid.hpp"
#include "world.hpp"

namespace atlas {

// (atlas_set_pipeline_cache) the pipeline cache's file: ATLAS_PIPELINE_CACHE, else the one set; "": none
std::string pipelineCachePath();
// the native build's content hash (cmake/buildhash.cmake): the caches' build fingerprint
uint64_t buildHash();
// the generator's own content hash (the same script's ATLAS_GEN_HASH): what the generation and bake caches are keyed by
uint64_t genHash();
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
// (internal, the Vulkan backend's split frames of the unified field) the far range's primary rays reused across frames
// where a block covers no more than a pixel (atlas_far.slang farHit; ATLAS_FAR_AMORT=0: every pixel traced each frame)
constexpr uint32_t FRAME_FAR_AMORT_BIT = 0x40000000u;
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
    uint64_t paletteRevision = 0;
    const std::vector<uint32_t>* weather;
    uint64_t weatherRevision = 0;
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

// (Minecraft's Vulkan backend, stage S3) Minecraft's own device, which the device-sharing mixin created with atlas's
// features and queues (atlas.h AtlasVkDevice): atlas adopts it instead of creating one.
struct VkAdopt {
    void *instance = nullptr, *physical = nullptr, *device = nullptr;
    uint32_t frameFamily = 0, frameIndex = 0, genFamily = UINT32_MAX, genIndex = 0, flags = 0;
    uint32_t graphicsFamily = 0;  // Minecraft's graphics queue's family (it runs the splices)
};
// Minecraft's images a shared frame is copied into (VkImage handles, GENERAL layout, the frames' queue family; 0 none),
// and the splice the caller records into its frame (atlas.h AtlasVkSplice).
struct VkSharedTargets {
    uint64_t color = 0, depth = 0, nearLight = 0, sunMap = 0;
};
struct VkSplice {
    uint64_t waitSemaphore = 0, waitValue = 0;
    void* commandBuffer = nullptr;
    uint64_t signalSemaphore = 0, signalValue = 0;
};
struct VkRetirement { virtual ~VkRetirement() = default; };

// External kernels' buffers can remain referenced by future grid work after their producer closes.
// Ownership moves to the backend; release is explicit, only after its real consumer queue is idle.
// The destructor deliberately has no Vulkan side effects, including on failure/quarantine paths.
struct DeviceChildOwner {
    DeviceChildOwner* next = nullptr;
    virtual ~DeviceChildOwner() = default;
    virtual void release() noexcept = 0;
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
    enum class CloseState : uint32_t { Open, Sealed, Drained };
    enum class CloseDrain { Ok, Timeout, Error };
    class WorkTicket {
    public:
        WorkTicket() = default;
        WorkTicket(const WorkTicket&) = delete;
        WorkTicket& operator=(const WorkTicket&) = delete;
        WorkTicket(WorkTicket&& other) noexcept : owner_(std::exchange(other.owner_, nullptr)) {}
        WorkTicket& operator=(WorkTicket&& other) noexcept {
            if (this == &other) return *this;
            if (owner_) owner_->activeWork_.fetch_sub(1);
            owner_ = std::exchange(other.owner_, nullptr);
            return *this;
        }
        ~WorkTicket() { if (owner_) owner_->activeWork_.fetch_sub(1); }
        explicit operator bool() const { return owner_ != nullptr; }
        bool belongsTo(const Backend* owner) const { return owner_ == owner; }
    private:
        friend class Backend;
        explicit WorkTicket(Backend* owner) : owner_(owner) {}
        Backend* owner_ = nullptr;
    };
    WorkTicket admitWork() { return admit(false, false); }
    WorkTicket admitProducerCleanup() { return admit(true, false); }
    WorkTicket admitRetirement() { return admit(false, true); }
    CloseState closeState() const { return closeState_.load(); }
    void sealWork() {
        CloseState expected = CloseState::Open;
        closeState_.compare_exchange_strong(expected, CloseState::Sealed);
    }
    uint32_t activeWork() const { return activeWork_.load(); }
    uint32_t activeProducers() const { return activeProducers_.load(); }
    bool producerStarted(const WorkTicket& work) {
        if (!work.belongsTo(this)) return false;
        activeProducers_.fetch_add(1);
        return true;
    }
    void producerStopped() { activeProducers_.fetch_sub(1); }
    void markDrained() { closeState_.store(CloseState::Drained); }
    virtual bool supportsTerminalClose() const { return false; }
    virtual void finishBackgroundWork() {}
    virtual CloseDrain drainForClose(std::chrono::steady_clock::time_point, std::string&) { return CloseDrain::Error; }
    virtual void holdCloseLockForTest(uint32_t, uint32_t, std::atomic<bool>& held) { held.store(true); }
    virtual bool stagedSceneReadyForTest(uint64_t) { return false; }
    // Test-only one-shot failures at the real producer/consumer close boundary. They do not manufacture GPU loss
    // or completion. A producer refusal remains sticky through producerCloseFailed; a consumer refusal can retry.
    void injectCloseFault(uint32_t phase) { closeFault_.store(phase); }
    bool consumeCloseFault(uint32_t phase) {
        uint32_t expected = phase;
        return closeFault_.compare_exchange_strong(expected, 0);
    }
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
    virtual bool stageScene(const PackedScene&, const WorkTicket&) { return false; }
    // the level-0 grid's last build on the device: GPU ms, sections and jobs in it, whether the chunks' boxes were
    // regrown; and the GPU ms of every build so far
    struct VoxGridTimes {
        double lastMs = 0, totalMs = 0;
        uint32_t lastSections = 0, lastJobs = 0;
        bool lastStates = false;
        double stageCpuMs = 0, stageCpuMaxMs = 0;  // the render thread's share (taking a batch, staging it): last, worst
        double stageCpuRecentMs = 0;                // (the worst of the last 2-4 s)
        double takeMs = 0, takeMaxMs = 0;           // (and of it the grid's take(): the books, the ranking lists, the batch)
    };
    virtual VoxGridTimes voxgridTimes() const { return {}; }
    // The game path's wall time on the render thread, eased (ms): waiting for the slot's previous frame, preparing
    // (uploads, staging), recording and submitting
    struct GlTimes {
        double waitMs = 0, prepareMs = 0, recordMs = 0;
    };
    virtual GlTimes glTimes() const { return {}; }
    // (readiness) pipelines still being built off the render thread (the dev kernel reload); 0: all built
    virtual int pipelinesBuilding() const { return 0; }

    // (stage S3, a backend made on Minecraft's device) the frame traced on atlas's queue; `out` is the command buffer
    // that copies it into `targets`, to run on Minecraft's queue after waiting for atlas's frame. Attach with vkAttach.
    virtual bool vkAttach(uint32_t, uint32_t, std::string& err) {
        err = "this backend cannot render for Minecraft's Vulkan backend (use the Vulkan backend)";
        return false;
    }
    virtual bool vkRenderShared(const FrameInputs&, const GlFrameIO*, const VkSharedTargets&, VkSplice&, double*,
                                std::string& err) {
        err = "this backend does not run on Minecraft's Vulkan device";
        return false;
    }
    virtual bool vkCopyComplete(uint64_t, bool&, std::string& err) { err = "not a shared Vulkan context"; return false; }
    virtual bool vkWaitCopy(uint64_t, uint64_t, bool&, std::string& err) { err = "not a shared Vulkan context"; return false; }
    // (S4) a frame may be traced now: with the staging ring, once the copy that last read the ring slot this frame will
    // reuse is done (a non-blocking counter query); else every handed-out copy is done (vkCopyComplete(0)).
    virtual bool vkRenderReady(bool& ready, std::string& err) { return vkCopyComplete(0, ready, err); }
    virtual bool vkCancelSplice(uint64_t, std::string& err) { err = "not a shared Vulkan context"; return false; }
    virtual bool vkDetachRetired(VkRetirement*&, std::string& err) { err = "not a shared Vulkan context"; return false; }
    virtual bool vkSharedAttached() const { return false; }
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
        bool timeline = false;  // timeline semaphores enabled on the device (the generator's submission ring)
    };
    virtual bool sharedDevice(SharedDevice&, const WorkTicket&) { return false; }
    // The producer has drained. These buffers are read only by our native queue/grid, never by host splice commands.
    // The context outlives the producer and keeps them through future grid references until its final consumer drain.
    virtual bool retainDeviceChildren(std::unique_ptr<DeviceChildOwner>&) { return false; }
    // A failed producer close must reach the host's existing checked shared-device retirement operation.
    virtual void producerCloseFailed() {}
    virtual bool producerCloseHasFailed() const { return false; }
    // frames submitted, and the last one known complete (any thread)
    virtual uint64_t framesSubmitted() const { return 0; }
    // the frames' own GPU time (their timestamps, eased), ms; 0 unknown (the generator's budget reads the GPU's idle share)
    virtual double frameGpuMs() const { return 0.0; }
    virtual uint64_t framesCompleted() const { return 0; }
    virtual bool frameOnGpu() { return false; }  // the last frame submitted still on the GPU
    // (tests) level 0's block ids as the GPU holds them: per query the chunk word's index and the index in the section
    // (VoxGrid::locate); out[i] the 16-bit id, 0xFFFE the chunk is unloaded (no data). Waits for the GPU.
    virtual bool readVoxels(const std::vector<uint32_t>& chunk, const std::vector<uint32_t>& local, std::vector<uint32_t>& out,
                            std::string& err) {
        err = "not on this backend";
        out.clear();
        return false;
    }
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
    WorkTicket admit(bool sealed, bool drained) {
        auto allowed = [&](CloseState state) {
            return state == CloseState::Open || (sealed && state == CloseState::Sealed) ||
                   (drained && state == CloseState::Drained);
        };
        if (!allowed(closeState_.load())) return {};
        activeWork_.fetch_add(1);
        // Sequential consistency makes seal/entry ordering explicit: a permitted entrant is visible before seal.
        if (!allowed(closeState_.load())) { activeWork_.fetch_sub(1); return {}; }
        return WorkTicket(this);
    }
    std::atomic<CloseState> closeState_{CloseState::Open};
    std::atomic<uint32_t> activeWork_{0}, activeProducers_{0};
    std::atomic<uint32_t> closeFault_{0};
    Fault injected_ = Fault::None;
    uint32_t injectCountdown_ = 0;
};

std::unique_ptr<Backend> makeCpuBackend();
std::unique_ptr<Backend> makeVulkanBackend(std::string& err);  // null + err when no usable device
std::unique_ptr<Backend> makeVulkanBackendOn(const VkAdopt& device, std::string& err);  // (S3) on Minecraft's device

}  // namespace atlas
