// The flight recorder: an always-on profiler for Infinite ATLAS. CPU zones (scoped timers with static names) and Vulkan
// GPU timestamps per pass go into lock-free per-thread ring buffers; a frame over the spike threshold (default 50 ms)
// dumps the surrounding 30 s as a Chrome / Perfetto JSON trace plus a one-page summary.txt that names the zones, the
// GPU passes and the long pole. The Java side feeds the same rings through atlas_fr_* (flight_recorder.cpp).
//
// Cost: a zone is two clock reads and one 32-byte store into the calling thread's ring (~50 ns); a disabled recorder
// is one relaxed load. Names are string literals (or interned strings): the rings hold the pointer.
#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>

#include "vk_loader.hpp"

namespace atlas::fr {

// ---- the clock: one monotonic nanosecond clock for every zone (Windows: QueryPerformanceCounter, the clock behind
// std::chrono::steady_clock and Vulkan's QUERY_PERFORMANCE_COUNTER time domain)
uint64_t nowNs();

extern std::atomic<bool> g_enabled;
inline bool enabled() { return g_enabled.load(std::memory_order_relaxed); }

// Appends one finished zone to the calling thread's ring. track 0: the thread's own; else a virtual track (trackId).
void record(const char* name, uint64_t t0, uint64_t t1, int32_t arg = 0, uint32_t track = 0);
// A string kept for the process (zone names built at run time). Thread-safe; cheap after the first call.
const char* intern(const char* s);
// A virtual track ("GPU frames"): events recorded on it show as their own lane in the trace.
uint32_t trackId(const char* name);

// A scoped zone: [construction, destruction) on the calling thread. minNs: shorter zones are not recorded (fine-grained
// zones in hot paths). arg: any small number the trace shows (a count, bytes >> 10 ...).
class Zone {
public:
    explicit Zone(const char* name, uint64_t minNs = 0, int32_t arg = 0) {
        if (enabled()) {
            name_ = name;
            minNs_ = minNs;
            arg_ = arg;
            t0_ = nowNs();
        }
    }
    ~Zone() {
        if (name_) {
            uint64_t t1 = nowNs();
            if (t1 - t0_ >= minNs_) record(name_, t0_, t1, arg_);
        }
    }
    void setArg(int32_t a) { arg_ = a; }
    Zone(const Zone&) = delete;
    Zone& operator=(const Zone&) = delete;

private:
    const char* name_ = nullptr;
    uint64_t t0_ = 0, minNs_ = 0;
    int32_t arg_ = 0;
};
#define ATLAS_FR_CAT2(a, b) a##b
#define ATLAS_FR_CAT(a, b) ATLAS_FR_CAT2(a, b)
// ATLAS_ZONE("render:prepare"); the zone ends with the scope. ATLAS_ZONE_MIN: only when it ran at least `us` microseconds.
#define ATLAS_ZONE(NAME) ::atlas::fr::Zone ATLAS_FR_CAT(frZone_, __LINE__)(NAME)
#define ATLAS_ZONE_MIN(NAME, US) ::atlas::fr::Zone ATLAS_FR_CAT(frZone_, __LINE__)(NAME, uint64_t(US) * 1000u)

// A std::lock_guard that also records how long it waited for the mutex (>= 0.2 ms: "wait:...") and how long it held it
// (>= 1 ms: "hold:..."): the trace then shows who held a lock a stalled thread was waiting for. Names are literals.
class LockZone {
public:
    LockZone(std::mutex& m, const char* waitName, const char* holdName) : m_(m), hold_(holdName) {
        if (enabled()) {
            uint64_t t0 = nowNs();
            m.lock();
            t1_ = nowNs();
            if (t1_ - t0 >= 200000) record(waitName, t0, t1_);
        } else {
            m.lock();
        }
    }
    ~LockZone() {
        uint64_t t2 = t1_ ? nowNs() : 0;
        m_.unlock();
        if (t1_ && t2 - t1_ >= 1000000) record(hold_, t1_, t2);
    }
    LockZone(const LockZone&) = delete;
    LockZone& operator=(const LockZone&) = delete;

private:
    std::mutex& m_;
    const char* hold_;
    uint64_t t1_ = 0;
};

// The render thread's frame boundary (once per game frame): records the "frame" zone since the last call and starts a
// spike dump when it ran over the threshold.
void frame();

// ---- GPU timestamps ------------------------------------------------------------------------------------------------
// Once per device (after vkCreateDevice): the timestamp period (ns per tick), the queue family's valid bits and whether
// VK_EXT_calibrated_timestamps was enabled (zones then sit at their true place on the host clock; else they are anchored at
// the host's submit time).
void gpuInit(VkDevice dev, float periodNs, uint32_t validBits, bool calibrated);

// One command buffer's timestamps: begin() after vkBeginCommandBuffer, mark() after each pass, submitted() just before
// vkQueueSubmit, resolve() once its fence is done (reads the results and records the zones: "<parent>" over the whole
// buffer, one zone per mark from the previous mark to this one, named for the pass).
class GpuTimeline {
public:
    static constexpr uint32_t CAP = 48;
    void create(VkDevice dev);
    void destroy();
    bool active() const { return pool_ != VK_NULL_HANDLE; }
    // stage: where the first stamp executes (a buffer behind a semaphore wait on compute: COMPUTE_SHADER, so the wait is not counted)
    void begin(VkCommandBuffer cmd, VkPipelineStageFlagBits stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    void mark(VkCommandBuffer cmd, const char* pass);
    void submitted();
    // parent: the zone over the whole buffer; track: virtual track of the recorded zones
    void resolve(const char* parent, uint32_t track);

private:
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueryPool pool_ = VK_NULL_HANDLE;
    uint32_t n_ = 0;
    uint64_t submitNs_ = 0;
    bool open_ = false, submitted_ = false;
    const char* names_[CAP] = {};
};

}  // namespace atlas::fr
