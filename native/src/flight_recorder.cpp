// The flight recorder (flight_recorder.hpp): per-thread ring buffers of zones, GPU timestamp timelines, the spike
// detector and the Perfetto / Chrome JSON + summary.txt dump. Everything the game thread does here is a clock read and a
// store; the watcher thread does the rest.
#include "flight_recorder.hpp"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "atlas/atlas.h"

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>
#endif

namespace atlas::fr {

// ---- the clock -------------------------------------------------------------------------------------------------------

#ifdef _WIN32
static uint64_t qpcFreq() {
    LARGE_INTEGER f;
    QueryPerformanceFrequency(&f);
    return uint64_t(f.QuadPart);
}
static const uint64_t g_qpcFreq = qpcFreq();
static uint64_t ticksToNs(uint64_t t) { return (t / g_qpcFreq) * 1000000000ull + (t % g_qpcFreq) * 1000000000ull / g_qpcFreq; }
uint64_t nowNs() {
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return ticksToNs(uint64_t(c.QuadPart));
}
static uint32_t osThreadId() { return uint32_t(GetCurrentThreadId()); }
#else
uint64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000000000ull + uint64_t(ts.tv_nsec);
}
static uint64_t ticksToNs(uint64_t t) { return t; }
static uint32_t osThreadId() { return uint32_t(syscall(SYS_gettid)); }
#endif

std::atomic<bool> g_enabled{false};

// ---- the rings -------------------------------------------------------------------------------------------------------

namespace {

struct Event {
    uint64_t t0, t1;
    const char* name;
    int32_t arg;
    uint32_t track;
};
static_assert(sizeof(Event) == 32, "an event is half a cache line");

constexpr uint32_t RING_EVENTS = 1u << 17;  // 4 MB a thread (pages are committed as they are written)
constexpr uint32_t RING_MASK = RING_EVENTS - 1;
constexpr uint32_t MAX_RINGS = 64;
constexpr uint32_t TRACK_BASE = 1000;  // virtual tracks' tids in the trace

struct Ring {
    Event* buf = nullptr;
    std::atomic<uint64_t> head{0};
    uint32_t tid = 0, index = 0;
    char name[64] = {};
    std::atomic<bool> named{false};
    std::atomic<bool> isFrame{false};
};

struct Registry {
    std::mutex mu;
    Ring* rings[MAX_RINGS] = {};
    std::atomic<uint32_t> count{0};
    std::unordered_set<std::string> strings;
    const char* zoneNames[4096] = {};                    // zone ids handed to Java (append-only: read without the lock)
    std::atomic<int> zoneCount{0};
    std::unordered_map<std::string, int> zoneIds;
    std::vector<const char*> trackNames{"(own)"};        // track 0: the thread's own lane
};
// leaked: the DLL may be unloaded (or the process end) while a thread still records
Registry& reg() {
    static Registry* r = new Registry();
    return *r;
}

thread_local Ring* tl_ring = nullptr;
thread_local bool tl_ringFailed = false;

#ifdef _WIN32
// the OS-level name of the calling thread (Java names its threads), if it has one
std::string osThreadName() {
    using Fn = HRESULT(WINAPI*)(HANDLE, PWSTR*);
    static Fn fn = reinterpret_cast<Fn>(reinterpret_cast<void*>(GetProcAddress(GetModuleHandleA("kernel32.dll"), "GetThreadDescription")));
    if (!fn) return {};
    PWSTR w = nullptr;
    if (FAILED(fn(GetCurrentThread(), &w)) || !w) return {};
    std::string out;
    for (const wchar_t* p = w; *p && out.size() < 60; p++) out += (*p < 128 ? char(*p) : '?');
    LocalFree(w);
    return out;
}
#else
std::string osThreadName() { return {}; }
#endif

Ring* makeRing() {
    if (tl_ringFailed) return nullptr;
    Registry& R = reg();
    std::lock_guard<std::mutex> lk(R.mu);
    uint32_t n = R.count.load();
    if (n >= MAX_RINGS) {
        tl_ringFailed = true;
        return nullptr;
    }
    Ring* r = new Ring();
    r->buf = static_cast<Event*>(std::calloc(RING_EVENTS, sizeof(Event)));
    if (!r->buf) {
        delete r;
        tl_ringFailed = true;
        return nullptr;
    }
    r->tid = osThreadId();
    r->index = n;
    std::string nm = osThreadName();
    if (!nm.empty()) {
        std::snprintf(r->name, sizeof(r->name), "%s", nm.c_str());
        r->named.store(true);
    } else {
        std::snprintf(r->name, sizeof(r->name), "thread %u", r->tid);
    }
    R.rings[n] = r;
    R.count.store(n + 1, std::memory_order_release);
    tl_ring = r;
    return r;
}

inline void put(Ring* r, const char* name, uint64_t t0, uint64_t t1, int32_t arg, uint32_t track) {
    uint64_t h = r->head.load(std::memory_order_relaxed);
    r->buf[h & RING_MASK] = Event{t0, t1, name, arg, track};
    r->head.store(h + 1, std::memory_order_release);
}

}  // namespace

void record(const char* name, uint64_t t0, uint64_t t1, int32_t arg, uint32_t track) {
    Ring* r = tl_ring ? tl_ring : makeRing();
    if (r) put(r, name, t0, t1, arg, track);
}

const char* intern(const char* s) {
    Registry& R = reg();
    std::lock_guard<std::mutex> lk(R.mu);
    return R.strings.emplace(s).first->c_str();
}

uint32_t trackId(const char* name) {
    Registry& R = reg();
    const char* s = intern(name);
    std::lock_guard<std::mutex> lk(R.mu);
    for (uint32_t i = 1; i < R.trackNames.size(); i++)
        if (R.trackNames[i] == s) return i;
    R.trackNames.push_back(s);
    return uint32_t(R.trackNames.size() - 1);
}

// ---- configuration and the spike request -----------------------------------------------------------------------------

namespace {

constexpr double POST_SECONDS = 1.5;  // a dump waits this long after the spike so the frames after it are in the trace

struct State {
    std::mutex mu;
    std::condition_variable cv;
    std::string dir;                       // atlas-trace folder (empty: no dumps)
    std::atomic<uint64_t> spikeNs{50'000'000};
    std::atomic<uint64_t> windowNs{30'000'000'000ull};
    std::atomic<uint64_t> gapNs{10'000'000'000ull};
    // the request (guarded by mu)
    bool pending = false;
    uint64_t fs = 0, fe = 0;
    uint64_t lastRequest = 0;
    bool watcherStarted = false;
    // statistics
    std::atomic<uint64_t> frames{0}, dumps{0}, suppressed{0}, lastFrame{0};
    std::string lastDir, lastError;
    uint64_t statEvents = 0, statFrames = 0;
    double zoneNs = 0, offNs = 0;
    uint64_t baseNs = 0;
    std::chrono::system_clock::time_point baseWall;
};
State& st() {
    static State* s = new State();
    return *s;
}

std::string dumpNow(uint64_t fs, uint64_t fe, bool manual, uint64_t to, std::string& err);

void watcherMain() {
    State& S = st();
    for (;;) {
        uint64_t fs, fe;
        {
            std::unique_lock<std::mutex> lk(S.mu);
            S.cv.wait(lk, [&] { return S.pending; });
            fs = S.fs;
            fe = S.fe;
        }
        // the frames after the spike belong in the trace
        uint64_t until = fe + uint64_t(POST_SECONDS * 1e9);
        for (;;) {
            uint64_t now = nowNs();
            if (now >= until) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(std::min<uint64_t>((until - now) / 1000000 + 1, 100)));
        }
        std::string err;
        try {
            std::string dir = dumpNow(fs, fe, false, nowNs(), err);
            if (dir.empty()) {
                std::lock_guard<std::mutex> lk(S.mu);
                S.lastError = err;
            }
        } catch (const std::exception& e) {
            std::lock_guard<std::mutex> lk(S.mu);
            S.lastError = e.what();
        }
        std::lock_guard<std::mutex> lk(S.mu);
        S.pending = false;
    }
}

void requestSpike(uint64_t fs, uint64_t fe) {
    State& S = st();
    std::lock_guard<std::mutex> lk(S.mu);
    if (S.dir.empty()) return;
    if (S.pending || (S.lastRequest && fe - S.lastRequest < S.gapNs.load())) {
        S.suppressed.fetch_add(1);
        return;
    }
    S.lastRequest = fe;
    S.fs = fs;
    S.fe = fe;
    S.pending = true;
    if (!S.watcherStarted) {
        S.watcherStarted = true;
        std::thread(watcherMain).detach();
    }
    S.cv.notify_one();
}

const char* frameName() {
    static const char* n = intern("frame");
    return n;
}

}  // namespace

void frame() {
    if (!enabled()) return;
    State& S = st();
    uint64_t now = nowNs();
    uint64_t prev = S.lastFrame.exchange(now, std::memory_order_relaxed);
    if (!prev || now - prev > 10'000'000'000ull) return;  // the first frame, or a pause that was no frame (a loading screen)
    Ring* r = tl_ring ? tl_ring : makeRing();
    if (r) {
        put(r, frameName(), prev, now, 0, 0);
        if (!r->isFrame.load(std::memory_order_relaxed)) r->isFrame.store(true, std::memory_order_relaxed);
    }
    S.frames.fetch_add(1, std::memory_order_relaxed);
    if (now - prev >= S.spikeNs.load(std::memory_order_relaxed)) requestSpike(prev, now);
}

// ---- GPU timestamps ----------------------------------------------------------------------------------------------------

namespace {

struct GpuDev {
    VkDevice dev = VK_NULL_HANDLE;
    double period = 1.0;
    uint64_t mask = ~0ull;
    PFN_vkGetCalibratedTimestampsEXT fn = nullptr;
    std::mutex mu;
    int64_t offset = 0;      // host ns - device ticks * period
    uint64_t offsetAt = 0;   // host ns of the calibration
    bool ok = false;
};
GpuDev g_gpu[4];
std::atomic<int> g_gpuCount{0};

GpuDev* findGpu(VkDevice d) {
    int n = g_gpuCount.load(std::memory_order_acquire);
    for (int i = 0; i < n; i++)
        if (g_gpu[i].dev == d) return &g_gpu[i];
    return nullptr;
}

// the device clock against the host's (VK_EXT_calibrated_timestamps), refreshed every 250 ms
bool calibrate(GpuDev& g, uint64_t now) {
    std::lock_guard<std::mutex> lk(g.mu);
    if (g.ok && now - g.offsetAt < 250'000'000ull) return true;
    if (!g.fn) return false;
#ifdef _WIN32
    const VkTimeDomainEXT hostDomain = VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_EXT;
#else
    const VkTimeDomainEXT hostDomain = VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT;
#endif
    VkCalibratedTimestampInfoEXT info[2] = {{VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT, nullptr, VK_TIME_DOMAIN_DEVICE_EXT},
                                            {VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT, nullptr, hostDomain}};
    uint64_t ts[2] = {}, dev = 0;
    if (g.fn(g.dev, 2, info, ts, &dev) != VK_SUCCESS) {
        g.ok = false;
        return false;
    }
    g.offset = int64_t(ticksToNs(ts[1])) - int64_t(double(ts[0]) * g.period);
    g.offsetAt = now;
    g.ok = true;
    return true;
}

}  // namespace

void gpuInit(VkDevice dev, float periodNs, uint32_t validBits, bool calibrated) {
    int n = g_gpuCount.load();
    if (n >= 4 || findGpu(dev)) return;
    GpuDev& g = g_gpu[n];
    g.dev = dev;
    g.period = periodNs;
    g.mask = validBits >= 64 || validBits == 0 ? ~0ull : ((1ull << validBits) - 1);
    if (calibrated && vkGetDeviceProcAddr)
        g.fn = reinterpret_cast<PFN_vkGetCalibratedTimestampsEXT>(vkGetDeviceProcAddr(dev, "vkGetCalibratedTimestampsEXT"));
    if (g.fn) calibrate(g, nowNs());
    g_gpuCount.store(n + 1, std::memory_order_release);
}

void GpuTimeline::create(VkDevice dev) {
    destroy();
    if (!findGpu(dev)) return;  // an uninitialised device: no timeline
    VkQueryPoolCreateInfo q{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    q.queryType = VK_QUERY_TYPE_TIMESTAMP;
    q.queryCount = CAP;
    if (vkCreateQueryPool(dev, &q, nullptr, &pool_) != VK_SUCCESS) {
        pool_ = VK_NULL_HANDLE;
        return;
    }
    dev_ = dev;
}

void GpuTimeline::destroy() {
    if (pool_) vkDestroyQueryPool(dev_, pool_, nullptr);
    pool_ = VK_NULL_HANDLE;
    n_ = 0;
    open_ = submitted_ = false;
}

void GpuTimeline::begin(VkCommandBuffer cmd, VkPipelineStageFlagBits stage) {
    n_ = 0;
    open_ = submitted_ = false;
    if (!pool_ || !enabled()) return;
    vkCmdResetQueryPool(cmd, pool_, 0, CAP);
    vkCmdWriteTimestamp(cmd, stage, pool_, 0);
    names_[0] = nullptr;
    n_ = 1;
    open_ = true;
}

void GpuTimeline::mark(VkCommandBuffer cmd, const char* pass) {
    if (!open_ || n_ >= CAP) return;
    vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool_, n_);
    names_[n_++] = pass;
}

void GpuTimeline::submitted() {
    if (open_) {
        submitNs_ = nowNs();
        submitted_ = true;
    }
}

void GpuTimeline::resolve(const char* parent, uint32_t track) {
    bool was = open_ && submitted_ && n_ >= 2;
    open_ = false;
    submitted_ = false;
    if (!was || !enabled()) return;
    GpuDev* g = findGpu(dev_);
    if (!g) return;
    uint64_t ts[CAP];
    if (vkGetQueryPoolResults(dev_, pool_, 0, n_, sizeof(ts), ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) != VK_SUCCESS) return;
    const uint64_t now = nowNs();
    const bool cal = calibrate(*g, now);
    auto host = [&](uint64_t t) -> uint64_t {
        if (cal) return uint64_t(g->offset + int64_t(double(t) * g->period));
        return submitNs_ + uint64_t(double((t - ts[0]) & g->mask) * g->period);  // anchored at the submit
    };
    uint64_t first = host(ts[0]), last = host(ts[n_ - 1]);
    if (last < first) return;
    // the parent's arg: microseconds between the host's submit and the GPU starting the buffer (queue wait; 0 when anchored)
    int64_t lat = cal ? (int64_t(first) - int64_t(submitNs_)) / 1000 : 0;
    record(parent, first, last, int32_t(std::max<int64_t>(0, std::min<int64_t>(lat, 2'000'000'000))), track);
    for (uint32_t i = 1; i < n_; i++) {
        if (ts[i] < ts[i - 1]) continue;
        record(names_[i], host(ts[i - 1]), host(ts[i]), 0, track);
    }
}

// ---- the dump --------------------------------------------------------------------------------------------------------

namespace {

struct Ev {
    uint64_t t0, t1;
    const char* name;
    int32_t arg;
    uint32_t lane;  // thread: ring index + 1; virtual track: TRACK_BASE + track
    double self = 0;
    int depth = 0;
};
struct Lane {
    uint32_t id;
    std::string name;
    bool gpu = false, frame = false;
    uint32_t tid = 0;
};

std::string jsonEsc(const char* s) {
    std::string o;
    for (; *s; s++) {
        unsigned char c = static_cast<unsigned char>(*s);
        if (c == '"' || c == '\\') {
            o += '\\';
            o += char(c);
        } else if (c < 0x20 || c >= 0x7f) {
            o += '?';
        } else {
            o += char(c);
        }
    }
    return o;
}

double ms(uint64_t ns) { return double(ns) / 1e6; }

std::string wallClock(uint64_t t, const char* fmt) {
    State& S = st();
    auto tp = S.baseWall + std::chrono::duration_cast<std::chrono::system_clock::duration>(std::chrono::nanoseconds(int64_t(t) - int64_t(S.baseNs)));
    std::time_t tt = std::chrono::system_clock::to_time_t(tp);
    std::tm tmv{};
#ifdef _WIN32
    localtime_s(&tmv, &tt);
#else
    localtime_r(&tt, &tmv);
#endif
    char b[64];
    std::strftime(b, sizeof(b), fmt, &tmv);
    long msPart = long(std::chrono::duration_cast<std::chrono::milliseconds>(tp.time_since_epoch()).count() % 1000);
    std::string out = b;
    if (std::strchr(fmt, 'S') && std::strchr(fmt, ':')) {
        char m[8];
        std::snprintf(m, sizeof(m), ".%03ld", msPart < 0 ? msPart + 1000 : msPart);
        out += m;
    }
    return out;
}

// the union length of the events (sorted by start) clipped to [a, b]
uint64_t unionLen(std::vector<std::pair<uint64_t, uint64_t>>& v, uint64_t a, uint64_t b) {
    std::sort(v.begin(), v.end());
    uint64_t total = 0, curS = 0, curE = 0;
    bool open = false;
    for (auto& p : v) {
        uint64_t s = std::max(p.first, a), e = std::min(p.second, b);
        if (e <= s) continue;
        if (!open) {
            curS = s;
            curE = e;
            open = true;
        } else if (s <= curE) {
            curE = std::max(curE, e);
        } else {
            total += curE - curS;
            curS = s;
            curE = e;
        }
    }
    if (open) total += curE - curS;
    return total;
}

bool startsWith(const char* s, const char* p) { return std::strncmp(s, p, std::strlen(p)) == 0; }

std::string dumpNow(uint64_t fs, uint64_t fe, bool manual, uint64_t to, std::string& err) {
    State& S = st();
    Registry& R = reg();
    const uint64_t from = to - std::min<uint64_t>(S.windowNs.load(), to);
    const uint64_t t_begin = nowNs();

    // 1. snapshot the rings (the owners keep writing: only entries the writer cannot have reached again are kept)
    std::vector<Ev> evs;
    std::vector<Lane> lanes;
    std::unordered_map<uint32_t, size_t> laneAt;
    auto laneFor = [&](uint32_t id) -> Lane& {
        auto it = laneAt.find(id);
        if (it != laneAt.end()) return lanes[it->second];
        laneAt[id] = lanes.size();
        lanes.push_back(Lane{id});
        return lanes.back();
    };
    std::vector<const char*> trackNames;
    {
        std::lock_guard<std::mutex> lk(R.mu);
        trackNames = R.trackNames;
    }
    uint32_t nRings = R.count.load(std::memory_order_acquire);
    uint64_t totalEvents = 0;
    std::vector<Event> tmp;
    for (uint32_t i = 0; i < nRings; i++) {
        Ring* r = R.rings[i];
        uint64_t h1 = r->head.load(std::memory_order_acquire);
        uint64_t lo = h1 > RING_EVENTS ? h1 - RING_EVENTS : 0;
        tmp.resize(size_t(h1 - lo));
        for (uint64_t k = lo; k < h1; k++) tmp[size_t(k - lo)] = r->buf[k & RING_MASK];
        uint64_t h2 = r->head.load(std::memory_order_acquire);
        uint64_t safe = h2 > RING_EVENTS ? h2 - RING_EVENTS + 64 : 0;  // (older entries may have been overwritten mid-copy)
        totalEvents += h1;
        Lane& own = laneFor(i + 1);
        own.name = r->name;
        own.tid = r->tid;
        own.frame = r->isFrame.load();
        for (uint64_t k = std::max(lo, safe); k < h1; k++) {
            const Event& e = tmp[size_t(k - lo)];
            if (!e.name || e.t1 < from || e.t0 > to || e.t1 < e.t0) continue;
            uint32_t lane = e.track ? TRACK_BASE + e.track : i + 1;
            if (e.track) {
                Lane& L = laneFor(lane);
                L.name = e.track < trackNames.size() ? trackNames[e.track] : "track";
                L.gpu = startsWith(L.name.c_str(), "GPU");
            }
            evs.push_back(Ev{e.t0, e.t1, e.name, e.arg, lane});
        }
    }
    std::sort(lanes.begin(), lanes.end(), [](const Lane& a, const Lane& b) { return a.id < b.id; });
    laneAt.clear();
    for (size_t i = 0; i < lanes.size(); i++) laneAt[lanes[i].id] = i;
    auto L = [&](uint32_t id) -> Lane& { return lanes[laneAt[id]]; };
    const char* fname = frameName();

    // 2. the frames in the window and the one to explain
    std::vector<Ev*> frames;
    for (auto& e : evs)
        if (e.name == fname) frames.push_back(&e);
    std::sort(frames.begin(), frames.end(), [](Ev* a, Ev* b) { return a->t1 < b->t1; });
    if (manual || !fs) {
        // (a manual dump explains the slowest frame of the window)
        Ev* worst = nullptr;
        for (Ev* f : frames)
            if (!worst || f->t1 - f->t0 > worst->t1 - worst->t0) worst = f;
        if (worst) {
            fs = worst->t0;
            fe = worst->t1;
        }
    }
    uint32_t frameLane = 0;
    for (Ev* f : frames)
        if (f->t0 == fs && f->t1 == fe) frameLane = f->lane;
    if (!frameLane && !frames.empty()) frameLane = frames.back()->lane;
    if (frameLane) L(frameLane).frame = true;

    // 3. nesting and self times (without the frame marks), per lane
    {
        std::vector<size_t> idx;
        for (size_t i = 0; i < evs.size(); i++)
            if (evs[i].name != fname) idx.push_back(i);
        std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
            if (evs[a].lane != evs[b].lane) return evs[a].lane < evs[b].lane;
            if (evs[a].t0 != evs[b].t0) return evs[a].t0 < evs[b].t0;
            return evs[a].t1 > evs[b].t1;
        });
        std::vector<size_t> stack;
        std::vector<double> childMs(evs.size(), 0.0);
        uint32_t cur = ~0u;
        for (size_t i : idx) {
            Ev& e = evs[i];
            if (e.lane != cur) {
                stack.clear();
                cur = e.lane;
            }
            while (!stack.empty() && evs[stack.back()].t1 <= e.t0) stack.pop_back();
            while (!stack.empty() && evs[stack.back()].t1 < e.t1) stack.pop_back();  // (a partial overlap is a sibling)
            e.depth = int(stack.size());
            if (!stack.empty()) childMs[stack.back()] += ms(e.t1 - e.t0);
            stack.push_back(i);
        }
        for (size_t i = 0; i < evs.size(); i++) evs[i].self = std::max(0.0, ms(evs[i].t1 - evs[i].t0) - childMs[i]);
    }

    // 4. the directory
    std::string base;
    {
        std::lock_guard<std::mutex> lk(S.mu);
        base = S.dir;
    }
    if (base.empty()) {
        err = "no trace directory configured";
        return {};
    }
    namespace fsys = std::filesystem;
    std::string stamp = wallClock(manual ? to : fe, "%Y%m%d-%H%M%S");
    fsys::path dir = fsys::path(base) / stamp;
    std::error_code ec;
    for (int k = 2; fsys::exists(dir, ec) && k < 100; k++) dir = fsys::path(base) / (stamp + "_" + std::to_string(k));
    fsys::create_directories(dir, ec);
    if (ec) {
        err = "cannot create " + dir.string() + ": " + ec.message();
        return {};
    }

    // 5. trace.json (Chrome trace event format: Perfetto and chrome://tracing open it)
    const uint64_t origin = from;
    {
        FILE* f = std::fopen((dir / "trace.json").string().c_str(), "wb");
        if (!f) {
            err = "cannot write trace.json";
            return {};
        }
        std::vector<char> buf(1 << 20);
        std::setvbuf(f, buf.data(), _IOFBF, buf.size());
        std::fprintf(f, "{\"displayTimeUnit\":\"ms\",\"traceEvents\":[\n");
        std::fprintf(f, "{\"name\":\"process_name\",\"ph\":\"M\",\"pid\":1,\"args\":{\"name\":\"NirnCraft Infinite ATLAS\"}}");
        int sortIdx = 0;
        for (auto& ln : lanes) {
            uint32_t tid = ln.id;
            std::fprintf(f, ",\n{\"name\":\"thread_name\",\"ph\":\"M\",\"pid\":1,\"tid\":%u,\"args\":{\"name\":\"%s\"}}", tid,
                         jsonEsc(ln.name.c_str()).c_str());
            std::fprintf(f, ",\n{\"name\":\"thread_sort_index\",\"ph\":\"M\",\"pid\":1,\"tid\":%u,\"args\":{\"sort_index\":%d}}", tid,
                         ln.gpu ? 100 + sortIdx : ln.frame ? 0 : 10 + sortIdx);
            sortIdx++;
        }
        for (auto& e : evs) {
            double ts = double(int64_t(e.t0) - int64_t(origin)) / 1000.0, dur = double(e.t1 - e.t0) / 1000.0;
            if (e.name == fname) {
                std::fprintf(f, ",\n{\"name\":\"frame\",\"ph\":\"X\",\"ts\":%.3f,\"dur\":%.3f,\"pid\":1,\"tid\":%u,\"args\":{\"ms\":%.2f}}", ts,
                             dur, e.lane, dur / 1000.0);
                // the frame time as a counter track
                std::fprintf(f, ",\n{\"name\":\"frame ms\",\"ph\":\"C\",\"ts\":%.3f,\"pid\":1,\"args\":{\"ms\":%.2f}}", double(int64_t(e.t1) - int64_t(origin)) / 1000.0,
                             dur / 1000.0);
                continue;
            }
            if (e.arg)
                std::fprintf(f, ",\n{\"name\":\"%s\",\"ph\":\"X\",\"ts\":%.3f,\"dur\":%.3f,\"pid\":1,\"tid\":%u,\"args\":{\"arg\":%d}}",
                             jsonEsc(e.name).c_str(), ts, dur, e.lane, e.arg);
            else
                std::fprintf(f, ",\n{\"name\":\"%s\",\"ph\":\"X\",\"ts\":%.3f,\"dur\":%.3f,\"pid\":1,\"tid\":%u}", jsonEsc(e.name).c_str(), ts,
                             dur, e.lane);
        }
        if (fe > fs)
            std::fprintf(f, ",\n{\"name\":\"%s %.1f ms\",\"ph\":\"i\",\"s\":\"g\",\"ts\":%.3f,\"pid\":1,\"tid\":%u}", manual ? "slowest frame" : "SPIKE",
                         ms(fe - fs), double(int64_t(fs) - int64_t(origin)) / 1000.0, frameLane ? frameLane : 1);
        std::fprintf(f, "\n]}\n");
        std::fclose(f);
    }

    // 6. summary.txt
    std::string sum;
    char line[1024];
    auto add = [&](const char* fmt, auto... a) {
        std::snprintf(line, sizeof(line), fmt, a...);
        sum += line;
    };
    std::vector<double> fms;
    for (Ev* fr : frames) fms.push_back(ms(fr->t1 - fr->t0));
    std::sort(fms.begin(), fms.end());
    auto pct = [&](double p) { return fms.empty() ? 0.0 : fms[std::min(fms.size() - 1, size_t(p * double(fms.size())))]; };
    const double spikeMs = ms(S.spikeNs.load());
    size_t over = 0;
    for (double v : fms) over += v >= spikeMs;
    add("NirnCraft Infinite ATLAS flight recorder\n");
    if (manual)
        add("trigger: manual dump at %s (explaining the slowest frame of the window)\n", wallClock(to, "%H:%M:%S").c_str());
    else
        add("trigger: frame of %.1f ms at %s (threshold %.0f ms)\n", ms(fe - fs), wallClock(fe, "%H:%M:%S").c_str(), spikeMs);
    add("window: %.1f s, %zu events on %zu lanes\n", ms(to - from) / 1000.0, evs.size(), lanes.size());
    add("frames in the window: %zu (p50 %.1f ms, p99 %.1f ms, worst %.1f ms); over %.0f ms: %zu; dumps held back by the 10 s limit so far: %llu\n",
        fms.size(), pct(0.5), pct(0.99), fms.empty() ? 0.0 : fms.back(), spikeMs, over, (unsigned long long)S.suppressed.load());
    add("trace.json opens in https://ui.perfetto.dev (or chrome://tracing); GPU lanes are named GPU ...\n");

    if (fe > fs) {
        const uint64_t fdur = fe - fs;
        const double fMs = ms(fdur);
        // events inside the frame
        struct Row {
            Ev* e;
            double in, self;
        };
        std::vector<Row> rows;
        double waitGpu = 0, cpuTopSelf = 0, gcMs = 0, gcLongest = 0;
        const char* gcName = "";
        const char* cpuTopName = "";
        std::string cpuTopLane;
        std::vector<std::pair<uint64_t, uint64_t>> frameCover, gpuFrames, gpuGen;
        for (auto& e : evs) {
            if (e.name == fname) continue;
            uint64_t a = std::max(e.t0, fs), b = std::min(e.t1, fe);
            if (b <= a) continue;
            Lane& ln = L(e.lane);
            double inMs = ms(b - a), full = ms(e.t1 - e.t0);
            double selfIn = full > 0 ? e.self * (inMs / full) : 0;
            if (ln.gpu) {
                if (startsWith(e.name, "gpu ")) {
                    if (std::strstr(ln.name.c_str(), "gen")) gpuGen.push_back({e.t0, e.t1});
                    else gpuFrames.push_back({e.t0, e.t1});
                }
                continue;
            }
            if (ln.name == "GC") {
                gcMs += inMs;
                if (inMs > gcLongest) {
                    gcLongest = inMs;
                    gcName = e.name;
                }
            }
            rows.push_back(Row{&e, inMs, selfIn});
            if (e.lane == frameLane) {
                if (e.depth == 0) frameCover.push_back({e.t0, e.t1});
                if (startsWith(e.name, "wait:gpu")) waitGpu += selfIn;
                else if (!startsWith(e.name, "wait:") && selfIn > cpuTopSelf) {
                    cpuTopSelf = selfIn;
                    cpuTopName = e.name;
                    cpuTopLane = ln.name;
                }
            }
        }
        uint64_t covered = unionLen(frameCover, fs, fe);
        double gpuBusy = ms(unionLen(gpuFrames, fs, fe)), genBusy = ms(unionLen(gpuGen, fs, fe));
        double outside = fMs - ms(covered);

        // the verdict
        std::string verdict;
        char vb[640];
        if (gcMs >= 0.3 * fMs) {
            std::snprintf(vb, sizeof(vb), "the JVM was paused by garbage collection: %.1f ms of the frame in GC (%s, longest %.1f ms); the JVM stops every thread, so nothing else is to blame",
                          gcMs, gcName, gcLongest);
        } else if (waitGpu >= 0.4 * fMs || gpuBusy >= 0.85 * fMs) {
            std::snprintf(vb, sizeof(vb), "the GPU was the long pole: the frame thread waited %.1f ms for the GPU and the frame work kept it busy %.1f ms of %.1f ms (generator work on the same card: %.1f ms)",
                          waitGpu, gpuBusy, fMs, genBusy);
        } else if (cpuTopSelf >= 0.3 * fMs) {
            std::snprintf(vb, sizeof(vb), "the CPU was the long pole: %s / %s held the frame thread for %.1f ms (%.0f%% of the frame) while the GPU was busy only %.1f ms",
                          cpuTopLane.c_str(), cpuTopName, cpuTopSelf, 100.0 * cpuTopSelf / fMs, gpuBusy);
        } else if (outside >= 0.4 * fMs) {
            std::snprintf(vb, sizeof(vb), "the CPU was the long pole, outside the instrumented zones: %.1f ms of the %.1f ms frame ran none of our zones on the frame thread (vanilla's own frame work, the driver, swap or vsync); GPU busy %.1f ms; biggest zone %s %.1f ms",
                          outside, fMs, gpuBusy, cpuTopName, cpuTopSelf);
        } else {
            std::snprintf(vb, sizeof(vb), "no single cause: the time is spread over many zones (biggest %s %.1f ms); GPU busy %.1f ms, frame thread waited %.1f ms for it",
                          cpuTopName, cpuTopSelf, gpuBusy, waitGpu);
        }
        verdict = vb;
        add("\n== the frame: %.1f ms (%s) on thread \"%s\" ==\n", fMs, wallClock(fs, "%H:%M:%S").c_str(), frameLane ? L(frameLane).name.c_str() : "?");
        add("verdict: %s\n", verdict.c_str());
        add("frame thread: %.1f ms inside our zones, %.1f ms outside them; GPU frames busy %.1f ms; generator GPU %.1f ms; GC %.1f ms\n", ms(covered),
            outside, gpuBusy, genBusy, gcMs);

        // top zones in the frame, all threads
        std::sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) { return a.in > b.in; });
        add("\ntop 10 zones in the frame, by time inside it (self = without its child zones; * = frame thread):\n");
        add("   ms   self  thread                      zone\n");
        for (size_t i = 0; i < rows.size() && i < 10; i++) {
            const Row& r = rows[i];
            add("%6.1f %6.1f  %c%-26s  %s%s\n", r.in, r.self, r.e->lane == frameLane ? '*' : ' ', L(r.e->lane).name.c_str(), r.e->name,
                ms(r.e->t1 - r.e->t0) > r.in + 0.05 ? " (runs past the frame)" : "");
        }
        std::sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) { return a.self > b.self; });
        add("\ntop 5 by self time (the leaf causes):\n");
        for (size_t i = 0; i < rows.size() && i < 5; i++)
            add("%6.1f  %c%-26s  %s\n", rows[i].self, rows[i].e->lane == frameLane ? '*' : ' ', L(rows[i].e->lane).name.c_str(), rows[i].e->name);

        // GPU passes in the frame (zones of the GPU lanes overlapping it)
        add("\nGPU passes overlapping the frame (ms; whole pass, GPU clock):\n");
        bool anyGpu = false;
        for (auto& ln : lanes) {
            if (!ln.gpu) continue;
            std::vector<Ev*> g;
            for (auto& e : evs)
                if (e.lane == ln.id && e.t1 > fs && e.t0 < fe) g.push_back(&e);
            if (g.empty()) continue;
            anyGpu = true;
            add("  %s:\n", ln.name.c_str());
            std::sort(g.begin(), g.end(), [](Ev* a, Ev* b) { return a->t0 < b->t0; });
            std::vector<std::string> parts;
            for (Ev* e : g) {
                if (startsWith(e->name, "gpu ")) {
                    std::snprintf(line, sizeof(line), "    %s %.2f ms (started %.2f ms after the host's submit)\n", e->name, ms(e->t1 - e->t0), e->arg / 1000.0);
                    sum += line;
                } else {
                    std::snprintf(line, sizeof(line), "      %-20s %.2f ms\n", e->name, ms(e->t1 - e->t0));
                    sum += line;
                }
            }
        }
        if (!anyGpu) add("  (none: GPU timestamps off or none resolved in this window)\n");
    } else {
        add("\n(no complete frame in the window: the render path has not run yet)\n");
    }

    // all the long frames of the window
    if (over) {
        add("\nframes over %.0f ms in the window:\n", spikeMs);
        int n = 0;
        for (Ev* fr : frames) {
            if (ms(fr->t1 - fr->t0) < spikeMs) continue;
            if (n++ >= 25) {
                add("  ...\n");
                break;
            }
            // its biggest frame-thread leaf
            const Ev* top = nullptr;
            for (auto& e : evs)
                if (e.lane == fr->lane && e.name != fname && !startsWith(e.name, "wait:") && e.t1 > fr->t0 && e.t0 < fr->t1 && (!top || e.self > top->self)) top = &e;
            add("  %s  %6.1f ms  biggest leaf: %s (%.1f ms)\n", wallClock(fr->t0, "%H:%M:%S").c_str(), ms(fr->t1 - fr->t0), top ? top->name : "-",
                top ? top->self : 0.0);
        }
    }
    {
        std::string path = (dir / "summary.txt").string();
        FILE* f = std::fopen(path.c_str(), "wb");
        if (!f) {
            err = "cannot write summary.txt";
            return {};
        }
        std::fwrite(sum.data(), 1, sum.size(), f);
        std::fclose(f);
    }
    S.dumps.fetch_add(1);
    {
        std::lock_guard<std::mutex> lk(S.mu);
        S.lastDir = dir.string();
    }
    std::fprintf(stderr, "[atlas] flight recorder: %s dump in %.0f ms -> %s (%zu events, frame %.1f ms)\n", manual ? "manual" : "spike",
                 ms(nowNs() - t_begin), dir.string().c_str(), evs.size(), ms(fe - fs));
    (void)totalEvents;
    return dir.string();
}

// ns per recorded zone and per disabled zone, measured on a private ring (the real rings stay clean)
void measureCost(State& S) {
    Ring r;
    r.buf = static_cast<Event*>(std::calloc(RING_EVENTS, sizeof(Event)));
    if (!r.buf) return;
    const int N = 200000;
    uint64_t a = nowNs();
    for (int i = 0; i < N; i++) {
        uint64_t t0 = nowNs();
        uint64_t t1 = nowNs();
        put(&r, "selftest", t0, t1, i, 0);
    }
    uint64_t b = nowNs();
    S.zoneNs = double(b - a) / N;
    bool saved = g_enabled.load();
    g_enabled.store(false);
    uint64_t c = nowNs();
    for (int i = 0; i < N; i++) {
        Zone z("off");
        (void)z;
    }
    uint64_t d = nowNs();
    S.offNs = double(d - c) / N;
    g_enabled.store(saved);
    std::free(r.buf);
}

}  // namespace

}  // namespace atlas::fr

// ---- the C API (the Java side) -----------------------------------------------------------------------------------------

using namespace atlas::fr;

extern "C" {

ATLAS_API void atlas_fr_configure(int enable, const char* dir, double spikeMs, double windowSec, double minGapSec) {
    State& S = st();
    {
        std::lock_guard<std::mutex> lk(S.mu);
        if (!S.baseNs) {
            S.baseNs = nowNs();
            S.baseWall = std::chrono::system_clock::now();
        }
        if (dir) S.dir = dir;
        if (spikeMs > 0) S.spikeNs.store(uint64_t(spikeMs * 1e6));
        if (windowSec > 0) S.windowNs.store(uint64_t(windowSec * 1e9));
        if (minGapSec >= 0) S.gapNs.store(uint64_t(minGapSec * 1e9));
    }
    if (enable && S.zoneNs == 0) measureCost(S);
    g_enabled.store(enable != 0);
    if (!enable) S.lastFrame.store(0);
}

ATLAS_API int64_t atlas_fr_now(void) { return int64_t(nowNs()); }

ATLAS_API int atlas_fr_zone_id(const char* name) {
    if (!name) return -1;
    Registry& R = reg();
    const char* s = intern(name);
    std::lock_guard<std::mutex> lk(R.mu);
    auto it = R.zoneIds.find(s);
    if (it != R.zoneIds.end()) return it->second;
    int id = R.zoneCount.load();
    if (id >= 4096) return -1;
    R.zoneNames[id] = s;
    R.zoneIds.emplace(s, id);
    R.zoneCount.store(id + 1, std::memory_order_release);
    return id;
}

ATLAS_API int atlas_fr_track_id(const char* name) { return name ? int(trackId(name)) : 0; }

// Records a zone from another language (times from atlas_fr_now's clock). Returns 1 when the calling thread has no name
// yet (call atlas_fr_thread_name once).
ATLAS_API int atlas_fr_zone(int id, int64_t t0, int64_t t1, int32_t arg, int track) {
    if (!enabled()) return 0;
    Registry& R = reg();
    if (id < 0 || id >= R.zoneCount.load(std::memory_order_acquire)) return 0;
    const char* name = R.zoneNames[id];
    record(name, uint64_t(t0), uint64_t(t1), arg, track > 0 ? uint32_t(track) : 0);
    return tl_ring && !tl_ring->named.load(std::memory_order_relaxed) ? 1 : 0;
}

ATLAS_API void atlas_fr_thread_name(const char* name) {
    Ring* r = tl_ring ? tl_ring : makeRing();
    if (!r || !name) return;
    std::snprintf(r->name, sizeof(r->name), "%s", name);
    r->named.store(true);
}

ATLAS_API void atlas_fr_frame(void) { frame(); }

// A dump of the last window now, on the calling thread. Returns the directory, or "error: ..." (valid until the next call
// from this thread).
ATLAS_API const char* atlas_fr_dump(const char* reason) {
    (void)reason;
    static thread_local std::string out;
    if (!enabled()) {
        out = "error: the recorder is off (-Dnirncraft.atlas.recorder=true)";
        return out.c_str();
    }
    std::string err;
    std::string dir = dumpNow(0, 0, true, nowNs(), err);
    out = dir.empty() ? "error: " + err : dir;
    return out.c_str();
}

ATLAS_API const char* atlas_fr_status(void) {
    static thread_local std::string out;
    State& S = st();
    Registry& R = reg();
    uint64_t events = 0;
    uint32_t n = R.count.load(std::memory_order_acquire);
    for (uint32_t i = 0; i < n; i++) events += R.rings[i]->head.load(std::memory_order_relaxed);
    uint64_t frames = S.frames.load();
    std::string last, lastErr;
    {
        std::lock_guard<std::mutex> lk(S.mu);
        last = S.lastDir;
        lastErr = S.lastError;
    }
    double perFrame = frames > S.statFrames ? double(events - S.statEvents) / double(frames - S.statFrames) : 0.0;
    S.statEvents = events;
    S.statFrames = frames;
    char b[1024];
    std::snprintf(b, sizeof(b),
                  "{\"on\":%s,\"rings\":%u,\"events\":%llu,\"frames\":%llu,\"dumps\":%llu,\"held_back\":%llu,\"zone_ns\":%.1f,\"off_zone_ns\":%.1f,"
                  "\"events_per_frame\":%.1f,\"cost_us_per_frame\":%.2f,\"last\":\"%s\",\"error\":\"%s\"}",
                  enabled() ? "true" : "false", n, (unsigned long long)events, (unsigned long long)frames, (unsigned long long)S.dumps.load(),
                  (unsigned long long)S.suppressed.load(), S.zoneNs, S.offNs, perFrame, perFrame * S.zoneNs / 1000.0, jsonEsc(last.c_str()).c_str(),
                  jsonEsc(lastErr.c_str()).c_str());
    out = b;
    return out.c_str();
}

}  // extern "C"
