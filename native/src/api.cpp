#include <cstdio>
#include <cstdlib>
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "atlas/atlas.h"
#include "air.hpp"
#include "backend.hpp"
#include "flight_recorder.hpp"
#include "texturepack.hpp"
#include "gencache.hpp"
#include "atlas_build_hash.h"
#include "bricks.hpp"
#include "lights.hpp"
#include "parallel.hpp"
#include "preview_source.hpp"
#include "world.hpp"
#include "sectionpack.hpp"
#include "sectionstore.hpp"

#ifndef ATLAS_VERSION_STRING
#define ATLAS_VERSION_STRING "dev"
#endif

using namespace atlas;

// The Java binding (com.nirncraft.atlas.AtlasNative) declares these layouts by hand; any change is an ABI bump.
static_assert(sizeof(AtlasMaterial) == 24);
static_assert(sizeof(AtlasColumnTile) == 64 && offsetof(AtlasColumnTile, surface) == 16);
// (0.15 appended AtlasFrame.nearInfo, AtlasFrameOutput.nearLight and AtlasGlFrameIO.nearInfoTex / dstNearLight; they
// are read only under ATLAS_FRAME_NEAR_LIGHT, so a 0.14 caller's 144 / 32 / 96-byte structs are never read past)
static_assert(sizeof(AtlasFrame) == 152 && offsetof(AtlasFrame, invViewProj) == 24 && offsetof(AtlasFrame, width) == 88 &&
              offsetof(AtlasFrame, sunDir) == 108 && offsetof(AtlasFrame, flags) == 128 &&
              offsetof(AtlasFrame, nearDepth) == 136 && offsetof(AtlasFrame, nearInfo) == 144);
static_assert(sizeof(AtlasFrameOutput) == 40 && offsetof(AtlasFrameOutput, colorLinear) == 24 &&
              offsetof(AtlasFrameOutput, nearLight) == 32);
static_assert(sizeof(AtlasWeather) == 40 && offsetof(AtlasWeather, rainFactor) == 32);
static_assert(sizeof(AtlasActor) == 48 && offsetof(AtlasActor, kind) == 36);
static_assert(sizeof(AtlasGlFrameIO) == 104 && offsetof(AtlasGlFrameIO, nearInvProjection) == 16 &&
              offsetof(AtlasGlFrameIO, dstColor) == 80 && offsetof(AtlasGlFrameIO, nearInfoTex) == 96 &&
              offsetof(AtlasGlFrameIO, dstNearLight) == 100);
static_assert(sizeof(AtlasLight) == 40 && offsetof(AtlasLight, radius) == 24 && offsetof(AtlasLight, kind) == 36);
static_assert(sizeof(AtlasStats) == 56 && offsetof(AtlasStats, nodeBytes) == 8 && offsetof(AtlasStats, raySteps) == 48);
static_assert(sizeof(AtlasSurfaceCard) == 1552 && offsetof(AtlasSurfaceCard, texels) == 16);
static_assert(sizeof(AtlasMaterialShape) == 400 && offsetof(AtlasMaterialShape, mean) == 8 &&
              offsetof(AtlasMaterialShape, faces) == 16);
static_assert(sizeof(AtlasAir) == 44 && offsetof(AtlasAir, aerosolG) == 24 && offsetof(AtlasAir, airScale) == 40);
static_assert(sizeof(AtlasClimate) == 52 && offsetof(AtlasClimate, background) == 12 && offsetof(AtlasClimate, coast) == 48);

// ---- the air (air.hpp) ---------------------------------------------------------------------------------------------

namespace {

// the weather buffer's tail: the multiple-scattering LUT (rgb float bits and a pad per texel), atlas_air.slang MS_BASE
constexpr size_t WEATHER_FIELDS = 4 * 128 * 128;
constexpr size_t WEATHER_WORDS = WEATHER_FIELDS + size_t(air::MS_SIZE) * air::MS_SIZE * 4;

air::Params airParamsOf(const AtlasAir& a) {
    auto ok = [](float v, float lo, float hi, float d) { return std::isfinite(v) ? std::clamp(v, lo, hi) : d; };
    air::Params p, d;
    p.airDensity = ok(a.airDensity, 0.0f, 4.0f, d.airDensity);
    p.aerosolDepth = ok(a.aerosolDepth, 0.0f, 8.0f, d.aerosolDepth);
    p.angstrom = ok(a.angstrom, -1.0f, 4.0f, d.angstrom);
    p.aerosolAlbedo = {ok(a.aerosolAlbedo[0], 0.0f, 1.0f, 0.97f), ok(a.aerosolAlbedo[1], 0.0f, 1.0f, 0.97f),
                       ok(a.aerosolAlbedo[2], 0.0f, 1.0f, 0.97f)};
    p.aerosolG = ok(a.aerosolG, 0.0f, 0.95f, d.aerosolG);
    p.aerosolHeight = ok(a.aerosolHeight, 0.2f, 8.0f, d.aerosolHeight);
    p.ozone = ok(a.ozone, 0.0f, 4.0f, d.ozone);
    p.groundAlbedo = ok(a.groundAlbedo, 0.0f, 1.0f, d.groundAlbedo);
    p.airScale = ok(a.airScale, 1e-5f, 0.1f, d.airScale);
    return p;
}

void buildLut(const air::Coeffs& c, air::MsLut& lut) {
    air::buildMultiScatter(c, lut, [](int n, auto f) {
        std::atomic<int> next{0};
        parallelRun([&] {
            for (int i; (i = next.fetch_add(1)) < n;) f(i);
        });
    });
}

void writeLut(const air::MsLut& lut, std::vector<uint32_t>& weather) {
    weather.resize(WEATHER_WORDS, 0);
    for (int k = 0; k < air::MS_SIZE * air::MS_SIZE; k++) {
        float v[4] = {lut.t[k].x, lut.t[k].y, lut.t[k].z, 1.0f};
        std::memcpy(weather.data() + WEATHER_FIELDS + size_t(k) * 4, v, 16);
    }
}

// the moon the air is lit by: the host's, else the old moonlight opposite the sun (atlas_air.slang airMoonDir / airAt)
void moonOf(const float sun[3], const float* dir, const float* rad, float outDir[3], float outRad[3]) {
    static const float fallback[3] = {8.7e-6f, 1.25e-5f, 2.2e-5f};  // (a real full moon: AtlasAir's; atlas_air.slang airAt)
    bool set = dir && rad && rad[0] + rad[1] + rad[2] > 0.0f;
    for (int i = 0; i < 3; i++) {
        outDir[i] = set ? dir[i] : -sun[i];
        outRad[i] = set ? rad[i] : fallback[i];
    }
}

// The context-free calls' air: the LUT of the last air asked for, kept
struct AirCache {
    std::mutex mu;
    bool have = false;
    AtlasAir key{};
    air::Coeffs c;
    air::MsLut lut;
};
AirCache& airCache() {
    static AirCache cache;
    return cache;
}
void cachedAir(const AtlasAir* a, air::Coeffs& c, air::MsLut& lut) {
    AtlasAir in{};
    if (a) in = *a;
    else atlas_air_defaults(&in);
    AirCache& k = airCache();
    std::lock_guard<std::mutex> lock(k.mu);
    if (!k.have || std::memcmp(&k.key, &in, sizeof(AtlasAir)) != 0) {
        k.c = air::coeffs(airParamsOf(in));
        buildLut(k.c, k.lut);
        k.key = in;
        k.have = true;
    }
    c = k.c;
    lut = k.lut;
}

}  // namespace

struct AtlasContext {
    double glInputsMs = 0;  // (atlas_info glRender: building the frame's inputs, eased)
    double inSnapMs = 0, inSkyMs = 0, inLightsMs = 0, inGridMs = 0, inTexMs = 0;  // (its parts)
    std::unique_ptr<Backend> backend;
    World world;
    VoxGrid voxgrid;  // the level-0 grid's books (voxgrid.hpp): sections in, batches to the backend's GPU build
    bool gridPlanned = false;  // (its pool sized from the device's memory once, before its first frame)
    // the host's tiers (atlas_set_cache; ATLAS_CACHE_DIR, ATLAS_HOST_SECTIONS_MB, ATLAS_COLD_MB): applied with the plan
    std::string cacheDir;
    uint64_t cacheSeed = 0, cacheHostBytes = 0, cacheDiskBytes = 0;
    uint32_t cacheGenVersion = 0;
    // the near field's textures for the kernels (texturepack.hpp): a slot a material, packed on a worker thread as they arrive
    TexturePack texPack;
    std::vector<uint32_t> texturesPacked;
    uint64_t texturesRevision = 0;
    // (abi 0.18) the micro-voxel bricks (atlas_set_material_bricks), packed for the kernels once each revision
    BrickStore bricks;
    std::vector<uint32_t> bricksPacked = std::vector<uint32_t>(1, 0u);
    uint64_t bricksRevision = 0, bricksStoreRevision = 0;
    uint32_t textureTicks = 0;  // atlas_set_texture_ticks + 1 (0: never set: the frame's timeSec clocks the animations)
    uint32_t ablateExtra = 0;  // atlas_debug_ablate: bits OR-ed into the measurement switches (ATLAS_ABLATE) from the host
    std::vector<uint32_t> materialLights;  // per material its light word (bricks.hpp), the last packed
    uint64_t materialLightsHash = 0;
    bool gridLights = false;  // some material emits: the grid's emitters are lights (gridLightMain)
    std::vector<MaterialGpu> palette;
    std::vector<uint32_t> weather;  // near field, near dyn, far field, far dyn (128*128 each)
    std::vector<uint32_t> cloudNoise = std::vector<uint32_t>(64 * 64 * 64, 0);
    float rainFactor = 0;
    float preExposure = 1.0f;  // atlas_set_pre_exposure
    std::vector<AtlasActor> actors;
    Lights lights;
    // (FRAME_GPU_LIGHTS_BIT) the far-light index as the GPU culls it (Lights::packGpu), packed when it changes
    std::vector<Float4> lightsPacked;
    uint64_t lightsRevision = 0;
    uint32_t lightRecords = 0;
    // the light by need (lightControl): the tiles' share traced a frame and the footprint scale, eased
    float lightRate = 0.25f, lightFoot = 1.0f, lightBudget = 1.0f;
    float prevJitterNdc[2] = {0.0f, 0.0f};  // the last frame's jitter (NDC): the histories reproject to its rays
    std::unique_ptr<PreviewSource> preview;
    FrameTargets targets;
    AtlasStats stats{};
    std::string error;
    std::string info;  // atlas_info's last answer
    std::mutex mu;
    uint64_t stagedRevision = UINT64_MAX;
    // the last frame, for temporal cloud reprojection
    bool haveHistory = false;
    float prevInvVP[16] = {};
    double prevCam[3] = {};
    uint32_t prevW = 0, prevH = 0, prevCloudFlags = 0;
    uint32_t frameIndex = 0;
    // the sun map (atlas_set_sun_map)
    uint32_t sunMapSize = 0;
    float sunMapHalf = 2048.0f;
    double sunMapCentre[3] = {1e300, 0, 1e300};
    bool sunMapFull = true;
    uint32_t sunMapCursor = 0;
    // the night key light (atlas_set_moon)
    float moonDir[3] = {0, 1, 0}, moonRad[3] = {0, 0, 0};
    bool moonSet = false;
    // the sky's own light (atlas_set_sky_glow), added to the air's sky light
    float glowUp[3] = {0, 0, 0}, glowGround[3] = {0, 0, 0};
    // the host path's near depths with its sky marked all-sky (FRAME_FILL_NEAR)
    std::vector<float> nearDepthHost;
    // the host's primary-ray jitter in pixels (atlas_set_jitter)
    float jitter[2] = {0, 0};
    // the air (atlas_set_air; its LUT rides in `weather`'s tail) and the sky light last integrated for a frame
    air::Coeffs airC = air::coeffs(air::Params{});
    air::MsLut airLut;
    uint64_t airVersion = 0;
    struct {
        uint64_t version = ~0ull;
        float sun[3] = {}, moon[3] = {}, moonE[3] = {}, alt = -1, share = -1;
        air::V3 up, ground;
    } skyLight;
    // an eclipse's occulters (atlas_set_eclipse): centre from the camera (km), radius (km) x 2; km a block
    float eclipse[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    float eclipseKm = 0.1f;

    AtlasStatus fail(AtlasStatus s, std::string msg) noexcept {
        try {
            error = std::move(msg);
        } catch (...) {
            // no memory even for the message: the status says enough
        }
        return s;
    }
};

// the palette's material classes for the world's generated section levels (docs/ATLAS-LOD.md)
static void syncClasses(AtlasContext* ctx) {
    std::vector<uint8_t> cls(ctx->palette.size(), 0);
    for (size_t i = 0; i < cls.size(); i++) cls[i] = uint8_t(ctx->palette[i].emisClass[1]);
    std::vector<uint8_t> grid = cls;
    // (the grid's vote by coverage: a brick (a plant, a rail, a torch, a door, a slab) counts by the share of its block
    // it fills, 128 | foliage 64 | coverage 0..63 (atlas_voxgrid.slang vKind); leaves and full blocks as before)
    for (size_t i = 0; i < grid.size(); i++) {
        int cov = ctx->bricks.coverage(uint32_t(i));
        bool kind = grid[i] == ATLAS_CLASS_TERRAIN || grid[i] == ATLAS_CLASS_EMISSIVE || grid[i] == ATLAS_CLASS_FOLIAGE;
        if (cov >= 0 && cov < 60 && kind) grid[i] = uint8_t(128 | (grid[i] == ATLAS_CLASS_FOLIAGE ? 64 : 0) | cov);
    }
    ctx->voxgrid.setClasses(grid);  // (the grid's coarse levels: the same vote, on the GPU)
    ctx->world.setClasses(std::move(grid));  // (and the regions' levels: world.hpp voteCoarse)
}


namespace {

// why the last atlas_create on this thread failed (there is no context to hold the message)
std::string& createError() {
    thread_local std::string e;
    return e;
}

// Every entry point catches what C++ throws inside (std::bad_alloc, std::system_error from a thread pool, ...): an
// exception escaping into the JVM's downcall would terminate the game.
AtlasStatus caught(AtlasContext* ctx, AtlasStatus s, const char* what) noexcept {
    if (ctx) return ctx->fail(s, what);
    try {
        createError() = what;
    } catch (...) {
    }
    return s;
}
#define ATLAS_TRY try {
#define ATLAS_CATCH(ctx)                                                                     \
    }                                                                                         \
    catch (const std::bad_alloc&) {                                                           \
        return caught(ctx, ATLAS_ERR_OUT_OF_MEMORY, "out of host memory (std::bad_alloc)"); \
    }                                                                                         \
    catch (const std::exception& e) {                                                         \
        return caught(ctx, ATLAS_ERR_INTERNAL, e.what());                                    \
    }                                                                                         \
    catch (...) {                                                                             \
        return caught(ctx, ATLAS_ERR_INTERNAL, "unknown exception");                         \
    }

// The status for a backend call that failed: a lost device, a hang and running out of memory get their own codes
// (the caller turns the far field off and retries later, or shrinks), anything else `otherwise`.
AtlasStatus backendFailure(AtlasContext* ctx, const std::string& err, AtlasStatus otherwise) noexcept {
    Fault f = ctx->backend->fault();
    AtlasStatus s = f == Fault::DeviceLost    ? ATLAS_ERR_DEVICE_LOST
                     : f == Fault::Timeout     ? ATLAS_ERR_TIMEOUT
                     : f == Fault::OutOfMemory ? ATLAS_ERR_OUT_OF_MEMORY
                                               : otherwise;
    ctx->backend->clearFault();
    try {
        return ctx->fail(s, err);
    } catch (...) {
        return s;
    }
}

// A context whose device is gone refuses GPU work (the same status every time) until it is destroyed.
bool deadDevice(AtlasContext* ctx, AtlasStatus& s) noexcept {
    if (!ctx->backend->lost()) {
        ctx->backend->clearFault();  // a fault an earlier call recovered from (e.g. zero-copy -> host-copy) is not this one's
        return false;
    }
    s = ctx->backend->fault() == Fault::Timeout ? ATLAS_ERR_TIMEOUT : ATLAS_ERR_DEVICE_LOST;
    ctx->fail(s, s == ATLAS_ERR_TIMEOUT ? "the GPU hung earlier (timeout); destroy this context and create a new one"
                                         : "the GPU device was lost earlier; destroy this context and create a new one");
    return true;
}

// ATLAS_FAULT=<device_lost|timeout|oom>[@frames]: arms the test hook on every new context.
void armFromEnvironment(Backend& b) {
    const char* v = std::getenv("ATLAS_FAULT");
    if (!v || !*v) return;
    std::string s(v);
    uint32_t after = 0;
    size_t at = s.find('@');
    if (at != std::string::npos) {
        for (size_t i = at + 1; i < s.size() && s[i] >= '0' && s[i] <= '9'; i++) after = after * 10 + uint32_t(s[i] - '0');
        s = s.substr(0, at);
    }
    Fault f = s == "device_lost" ? Fault::DeviceLost : s == "timeout" ? Fault::Timeout : s == "oom" ? Fault::OutOfMemory : Fault::None;
    if (f != Fault::None) {
        b.injectFault(f, after);
        std::fprintf(stderr, "[atlas] ATLAS_FAULT: %s after %u frames\n", s.c_str(), after);
    }
}

MaterialGpu toGpu(const AtlasMaterial& m) {
    MaterialGpu g{};
    g.albedoRough[0] = m.albedo[0];
    g.albedoRough[1] = m.albedo[1];
    g.albedoRough[2] = m.albedo[2];
    g.albedoRough[3] = m.roughness;
    g.emisClass[0] = m.emissive;
    g.emisClass[1] = float(m.cls);
    return g;
}

void setPalette(AtlasContext* ctx, const AtlasMaterial* m, uint32_t n) {
    ctx->palette.assign(1, MaterialGpu{});  // id 0 = air
    uint16_t water = 0;
    for (uint32_t i = 0; i < n; i++) {
        ctx->palette.push_back(toGpu(m[i]));
        if (!water && m[i].cls == ATLAS_CLASS_WATER) water = uint16_t(i + 1);
    }
    if (water) ctx->world.waterMat = water;
    applyWaterFlow(ctx->palette);
    syncClasses(ctx);
}

// Camera-relative direction through the centre of pixel (px, py).
void pixelDir(const float* inv, float px, float py, uint32_t w, uint32_t h, float out[3]) {
    float nx = (px + 0.5f) / float(w) * 2.0f - 1.0f, ny = 1.0f - (py + 0.5f) / float(h) * 2.0f;
    float p[4];
    for (int r = 0; r < 4; r++) p[r] = inv[0 * 4 + r] * nx + inv[1 * 4 + r] * ny + inv[2 * 4 + r] * 0.5f + inv[3 * 4 + r];
    float l = std::sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
    for (int i = 0; i < 3; i++) out[i] = p[i] / p[3] / (l / std::abs(p[3]));
}

}  // namespace

// The share of the sun's disc seen past an eclipse's occulters from a point p (km from the camera); atlas_air.slang's
// airSunShareKm, the same formula
static float eclipseShare(const float occ[8], const float p[3], const float sun[3]) {
    const float rs = 0.0089f;  // the sun's angular radius (the host's sky disc)
    float vis = 1.0f;
    for (int k = 0; k < 2; k++) {
        const float* o = occ + k * 4;
        if (!(o[3] > 0.0f)) continue;
        float v[3] = {o[0] - p[0], o[1] - p[1], o[2] - p[2]};
        float l = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l <= o[3]) return 0.0f;
        float c = (v[0] * sun[0] + v[1] * sun[1] + v[2] * sun[2]) / l;
        if (c <= 0.0f) continue;
        float cx = (v[1] * sun[2] - v[2] * sun[1]) / l, cy = (v[2] * sun[0] - v[0] * sun[2]) / l,
              cz = (v[0] * sun[1] - v[1] * sun[0]) / l;
        float ang = std::asin(std::fmin(o[3] / l, 1.0f));
        float sep = std::atan2(std::sqrt(cx * cx + cy * cy + cz * cz), c);
        float cover = std::fmin(std::fmax((ang + rs - sep) / (2.0f * std::fmin(ang, rs)), 0.0f), 1.0f) *
                      std::fmin(1.0f, ang * ang / (rs * rs));
        vis *= 1.0f - cover * cover * (3.0f - 2.0f * cover);
    }
    return vis;
}

namespace atlas {
namespace {
std::mutex& pipeCacheMu() {
    static std::mutex m;
    return m;
}
std::string& pipeCacheSet() {
    static std::string s;
    return s;
}
}  // namespace
std::string pipelineCachePath() {
    if (const char* e = std::getenv("ATLAS_PIPELINE_CACHE")) return e;
    std::lock_guard<std::mutex> l(pipeCacheMu());
    return pipeCacheSet();
}
uint64_t buildHash() { return ATLAS_BUILD_HASH; }
uint64_t genHash() { return ATLAS_GEN_HASH; }
void setPipelineCachePath(const std::string& p) {
    std::lock_guard<std::mutex> l(pipeCacheMu());
    pipeCacheSet() = p;
}
}  // namespace atlas

extern "C" {

ATLAS_API uint32_t atlas_abi_version(void) { return ATLAS_ABI_VERSION; }

#define ATLAS_STR2(x) #x
#define ATLAS_STR(x) ATLAS_STR2(x)
#if defined(_WIN32)
#define ATLAS_PLATFORM "windows"
#elif defined(__linux__)
#define ATLAS_PLATFORM "linux"
#else
#define ATLAS_PLATFORM "unknown"
#endif
#if defined(_MSC_VER)
#define ATLAS_COMPILER "msvc " ATLAS_STR(_MSC_VER)
#elif defined(__clang__)
#define ATLAS_COMPILER "clang " __clang_version__
#elif defined(__GNUC__)
#define ATLAS_COMPILER "gcc " __VERSION__
#else
#define ATLAS_COMPILER "unknown compiler"
#endif

ATLAS_API const char* atlas_build_info(void) {
    static const std::string s = [] {
        char abi[32];
        std::snprintf(abi, sizeof(abi), "%u.%u.%u", (ATLAS_ABI_VERSION >> 16) & 255u, (ATLAS_ABI_VERSION >> 8) & 255u,
                      ATLAS_ABI_VERSION & 255u);
        return std::string("atlas_core " ATLAS_VERSION_STRING " (abi ") + abi +
               "; slang far kernel; " ATLAS_PLATFORM ", " ATLAS_COMPILER "; " __DATE__ ")";
    }();
    return s.c_str();
}

ATLAS_API const char* atlas_info(AtlasContext* ctx) {
    if (!ctx) return "{}";
    try {
        ATLAS_ZONE_MIN("api:info", 100);
        std::unique_lock<std::mutex> lock(ctx->mu, std::defer_lock);
        {
            ATLAS_ZONE_MIN("wait:ctxLock (info)", 100);
            lock.lock();
        }
        // (each part zoned: a frame held seconds inside api:info in game, while the generator stored chunks)
        {
            ATLAS_ZONE_MIN("api:info.backend", 100);
            ctx->info = ctx->backend->info();
        }
        if (ctx->backend->lost() && !ctx->info.empty() && ctx->info.back() == '}')
            ctx->info.insert(ctx->info.size() - 1, ",\"deviceLost\":true");
        if (!ctx->info.empty() && ctx->info.back() == '}') {
            // (abi 0.18) the bricks the context holds: materials with a voxel brick, with sheets, the table's length
            uint32_t withBrick = 0, withCross = 0, table = 0;
            ctx->bricks.stats(withBrick, withCross, table);
            char bb[160];
            std::snprintf(bb, sizeof bb, ",\"brickMats\":{\"voxels\":%u,\"sheets\":%u,\"table\":%u}", withBrick, withCross, table);
            ctx->info.insert(ctx->info.size() - 1, bb);
        }
        if (ctx->world.groupsWanted.load() && !ctx->info.empty() && ctx->info.back() == '}') {
            // (abi 0.14) the content-shaped groups: regions with a tree, their memory, the last update's builds
            World::GroupStats g = ctx->world.groupStats();
            char b[512];
            std::snprintf(b, sizeof b,
                          ",\"groups\":{\"regions\":%u,\"refused\":%u,\"bytes\":%llu,\"maxRegionBytes\":%llu,\"prims\":%llu,"
                          "\"nodes\":%llu,\"bricks\":%llu,\"buildMs\":%.1f,\"maxBuildMs\":%.1f,\"lastBuilt\":%u,"
                          "\"lastBuildMs\":%.1f,\"lods\":%d,\"maxDepth\":%u}",
                          g.regions, g.refused, (unsigned long long)g.bytes, (unsigned long long)g.maxRegionBytes,
                          (unsigned long long)g.prims, (unsigned long long)g.nodes, (unsigned long long)g.bricks, g.buildMs,
                          g.maxBuildMs, g.built, g.lastUpdateMs, ctx->world.groupLods, g.maxDepth);
            ctx->info.insert(ctx->info.size() - 1, b);
            // the coarse levels of real content (CoarseCube): memory, cubes per level, the last update's digest
            World::CoarseStats c = ctx->world.coarseStats();
            std::snprintf(b, sizeof b,
                          ",\"coarse\":{\"bytes\":%llu,\"budget\":%llu,\"entries\":%u,\"cubes\":[%u,%u,%u,%u,%u,%u,%u],"
                          "\"levelBytes\":[%llu,%llu,%llu,%llu,%llu],\"digested\":%u,\"evicted\":%u,\"digestMs\":%.1f}",
                          (unsigned long long)c.bytes, (unsigned long long)ctx->world.coarseBudget, c.entries, c.cubes[0],
                          c.cubes[1], c.cubes[2], c.cubes[3], c.cubes[4], c.cubes[5], c.cubes[6],
                          (unsigned long long)c.levelBytes[2], (unsigned long long)c.levelBytes[3],
                          (unsigned long long)c.levelBytes[4], (unsigned long long)c.levelBytes[5],
                          (unsigned long long)c.levelBytes[6], c.digested, c.evicted, c.digestMs);
            ctx->info.insert(ctx->info.size() - 1, b);
        }
        if (!ctx->info.empty() && ctx->info.back() == '}') {
            // the game path's wall time on the render thread (eased): building the inputs, waiting, preparing, recording
            Backend::GlTimes g = ctx->backend->glTimes();
            char b[256];
            std::snprintf(b, sizeof b, ",\"glRender\":{\"inputsMs\":%.2f,\"waitMs\":%.2f,\"prepareMs\":%.2f,\"recordMs\":%.2f,"
                          "\"snapMs\":%.2f,\"skyMs\":%.2f,\"lightsMs\":%.2f,\"gridMs\":%.2f,\"texMs\":%.2f}",
                          ctx->glInputsMs, g.waitMs, g.prepareMs, g.recordMs, ctx->inSnapMs, ctx->inSkyMs, ctx->inLightsMs,
                          ctx->inGridMs, ctx->inTexMs);
            ctx->info.insert(ctx->info.size() - 1, b);
            // readiness (the harness gates wait on it): pipelines still being built off the render thread
            std::snprintf(b, sizeof b, ",\"ready\":{\"pipelinesBuilding\":%d}", ctx->backend->pipelinesBuilding());
            ctx->info.insert(ctx->info.size() - 1, b);
        }
        if (ctx->voxgrid.active() && !ctx->info.empty() && ctx->info.back() == '}') {
            // the level-0 grid: slots in use, sections built, what is waiting, the GPU's last build and all of them
            Backend::VoxGridTimes t = [&] {
                ATLAS_ZONE_MIN("api:info.voxgridTimes", 100);
                return ctx->backend->voxgridTimes();
            }();
            const VoxGrid& v = ctx->voxgrid;
            std::string phases = v.phaseInfo(), refine = [&] {
                ATLAS_ZONE_MIN("api:info.refineCoverage", 100);
                return v.refineInfo();
            }();
            uint64_t bytes = uint64_t(v.chunkWords()) * 4 + uint64_t(v.totalSlots()) * (64 * 12 + VG_SECTION_WORDS * 4);
            char b[4096];
            std::snprintf(b, sizeof b,
                          ",\"voxgrid\":{\"window\":[%d,%d,%d],\"slots\":%u,\"slotCap\":%u,\"gpuBytes\":%llu,\"built\":%llu,"
                          "\"batches\":%llu,\"pending\":%zu,\"lastGpuMs\":%.3f,\"lastSections\":%u,\"lastJobs\":%u,"
                          "\"lastRegrow\":%s,\"totalGpuMs\":%.2f,\"ownsLevel0\":%s,\"level0Skipped\":%zu,"
                          "\"stageCpuMs\":%.3f,\"stageCpuMaxMs\":%.3f,\"stageCpuRecentMs\":%.3f,\"takeMs\":%.3f,\"takeMaxMs\":%.3f,\"stageScale\":%.2f,\"baking\":%zu,%s%s",
                          v.D, v.H, v.D, v.slotsUsed(), v.slotCap, (unsigned long long)bytes, (unsigned long long)v.sectionsBuilt,
                          (unsigned long long)v.batches, v.pending(), t.lastMs, t.lastSections, t.lastJobs,
                          t.lastStates ? "true" : "false", t.totalMs, ctx->world.gridOwnsLevel0.load() ? "true" : "false",
                          ctx->world.gridSkippedCount(), t.stageCpuMs, t.stageCpuMaxMs, t.stageCpuRecentMs, t.takeMs, t.takeMaxMs, double(v.budgetScale), v.bakeQueued(), phases.c_str(), refine.c_str());
            // residency: slots used / of the pool, uniform cells (no slot), the waiting (CPU ids kept, GPU ones to ask
            // for), given up for nearer ones, could not be taken, the GPU's uniform finds, the priority cut-off of a
            // full pool, level 0's footprint reach and the nearest known section not resident (blocks; 0: none)
            {
                ATLAS_ZONE_MIN("api:info.residency", 100);
                VoxGrid::Residency rs = v.residency();
                char r[1024];
                std::snprintf(r, sizeof r,
                              "\"resident\":%u,\"slotsTotal\":%u,\"uniform\":%u,\"waiting\":%zu,\"gpuWaiting\":%zu,\"coarseWaiting\":%zu,"
                              "\"misses\":%llu,\"evictedNow\":%llu,\"gpuUniform\":%llu,\"cutoff\":%.0f,\"reach\":%.0f,\"nearestMissing\":%u,",
                              rs.resident, rs.cap, rs.uniform, rs.waiting, rs.gpuWaiting, rs.coarseWaiting, (unsigned long long)rs.misses, (unsigned long long)rs.evicted,
                              (unsigned long long)rs.gpuUniform, std::fmin(rs.cutoff, 1e9f), std::fmin(rs.reach, 1e9f),
                              rs.nearestMissing);
                std::strncat(b, r, sizeof b - std::strlen(b) - 1);
            }
            // the tiers (step 5): hot, the GPU's level-0 slots; raw, uploads not yet packed; warm, packed in host RAM;
            // cold, on disk; what left warm (dropped: the client makes it again; spilled: to disk; lost: neither)
            std::string tiers;
            {
                ATLAS_ZONE_MIN("api:info.tiers", 100);
                SectionStore::Stats ts;
                World::StoreStats ws = ctx->world.storeStats(&ts);
                VoxGrid::Residency rs = v.residency();
                char t[4096];
                std::snprintf(t, sizeof t,
                              "\"tiers\":{\"hot\":{\"slots\":%u,\"uniform\":%u,\"mb\":%.1f},\"raw\":{\"sections\":%zu,\"mb\":%.1f,"
                              "\"packQueue\":%zu,\"packed\":%llu},\"warm\":{\"sections\":%zu,\"mb\":%.1f,\"budgetMb\":%.0f,"
                              "\"hits\":%llu,\"misses\":%llu},\"cold\":{\"on\":%s,\"records\":%zu,\"regions\":%zu,\"mb\":%.1f,"
                              "\"budgetMb\":%.0f,\"hits\":%llu,\"writes\":%llu,\"evicted\":%llu},\"dropped\":%llu,\"spilled\":%llu,"
                              "\"lost\":%llu,\"edits\":{\"layers\":%zu,\"waiting\":%zu,\"deltaMb\":%.3f,\"deltaWrites\":%llu,"
                              "\"rewrites\":%llu,\"writtenMb\":%.3f,\"wholeEquivMb\":%.3f,\"baseApplied\":%llu,"
                              "\"baseMismatch\":%llu,\"codecMs\":%.1f}},",
                              rs.resident, rs.uniform, double(rs.resident) * VoxGrid::SLOT_BYTES / 1048576.0, ws.raw,
                              double(ws.rawBytes) / 1048576.0, ws.packQueue, (unsigned long long)ws.packed, ts.warm,
                              double(ts.warmBytes) / 1048576.0, double(ts.warmBudget) / 1048576.0,
                              (unsigned long long)ts.hits, (unsigned long long)ts.misses, ts.coldOn ? "true" : "false",
                              ts.coldRecords, ts.coldRegions, double(ts.coldBytes) / 1048576.0, double(ts.coldBudget) / 1048576.0,
                              (unsigned long long)ts.coldHits, (unsigned long long)ts.coldWrites,
                              (unsigned long long)ts.coldEvicted, (unsigned long long)ts.dropped,
                              (unsigned long long)ts.spilled, (unsigned long long)ts.lost, ts.layers, ts.layersWaiting,
                              double(ts.deltaBytes) / 1048576.0, (unsigned long long)ts.deltaWrites,
                              (unsigned long long)ts.rewrites, double(ts.writtenBytes) / 1048576.0,
                              double(ts.wholeEquivBytes) / 1048576.0, (unsigned long long)ts.baseApplied,
                              (unsigned long long)ts.baseMismatch, ts.codecMs);
                tiers = t;
            }
            // where the window is against the camera (sections), how it moved, what it still waits for, what the pool
            // full gave back, and how many frames its content took to be complete after its last move
            VoxGrid::Stats st = [&] {
                ATLAS_ZONE_MIN("api:info.gridStats", 100);
                return v.stats();
            }();
            char c[2048];
            std::snprintf(c, sizeof c,
                          "\"origin\":[%d,%d],\"cameraSection\":[%d,%d],\"jumps\":%llu,\"shifts\":%llu,\"refills\":%llu,"
                          "\"freshColumns\":%u,\"pendingSections\":%zu,\"evicted\":%llu,\"overflow\":%llu,"
                          "\"framesSinceMove\":%llu,\"lastSettleFrames\":%llu,\"settled\":%s",
                          st.ox, st.oz, st.camSx, st.camSz, (unsigned long long)st.jumps, (unsigned long long)st.shifts,
                          (unsigned long long)st.refills, st.freshColumns, st.pending, (unsigned long long)st.evicted,
                          (unsigned long long)st.overflow, (unsigned long long)(st.frames - st.lastShiftFrame),
                          (unsigned long long)st.lastSettleFrames, st.settledFrame >= st.lastShiftFrame ? "true" : "false");
            // the coarse levels: window (chunks of 16 << k blocks), slots, sections taken in, waiting, given up
            std::string coarse = ",\"levels\":[";
            for (int k = 1; k < v.levels(); k++) {
                VoxGrid::LevelInfo li = [&] {
                    ATLAS_ZONE_MIN("api:info.levelInfo", 100);
                    return v.levelInfo(k);
                }();
                char q[1024];
                std::snprintf(q, sizeof q,
                              "%s{\"level\":%d,\"window\":[%d,%d,%d],\"origin\":[%d,%d],\"slots\":%u,\"slotCap\":%u,"
                              "\"ingested\":%llu,\"pending\":%zu,\"evicted\":%llu,\"overflow\":%llu,\"jumps\":%llu,"
                              "\"shifts\":%llu}",
                              k > 1 ? "," : "", k, li.D, li.H, li.D, li.ox, li.oz, li.used, li.cap, (unsigned long long)li.ingested,
                              li.pending, (unsigned long long)li.evicted, (unsigned long long)li.overflow,
                              (unsigned long long)li.jumps, (unsigned long long)li.shifts);
                coarse += q;
            }
            coarse += "]}";
            // the self-baking cache (gencache.hpp): what is on disk for this world and generator, what is waiting to be
            // written, and what this session read from it (a relaunch: hits > 0 and few generated)
            std::string genCache = "\"genCache\":{\"on\":false},";
            if (GenCache* gc = ctx->world.genCache()) {
                ATLAS_ZONE_MIN("api:info.genCache", 100);
                GenCache::Stats gs = gc->stats();
                char g[512];
                std::snprintf(g, sizeof g,
                              "\"genCache\":{\"on\":%s,\"fingerprint\":\"%016llx\",\"chunks\":%llu,\"sections\":%llu,\"mb\":%.2f,"
                              "\"queuedWrites\":%llu,\"chunkHits\":%llu,\"chunkMisses\":%llu,\"sectionReads\":%llu,"
                              "\"sectionWrites\":%llu,\"farTileWrites\":%llu,\"farTileHits\":%llu,\"hashErrors\":%llu},",
                              gs.on ? "true" : "false", (unsigned long long)gs.fingerprint, (unsigned long long)gs.chunks,
                              (unsigned long long)gs.sections, double(gs.fileBytes) / 1048576.0, (unsigned long long)gs.queued,
                              (unsigned long long)gs.chunkHits, (unsigned long long)gs.chunkMisses, (unsigned long long)gs.sectionReads,
                              (unsigned long long)gs.sectionWrites, (unsigned long long)gs.farWrites, (unsigned long long)gs.farHits,
                              (unsigned long long)gs.hashErrors);
                genCache = g;
            }
            ctx->info.insert(ctx->info.size() - 1, std::string(b) + tiers + genCache + c + coarse);
        }
        return ctx->info.c_str();
    } catch (...) {
        return "{}";
    }
}

ATLAS_API uint64_t atlas_build_hash(void) { return atlas::buildHash(); }

ATLAS_API AtlasStatus atlas_set_pipeline_cache(const char* path) {
    atlas::setPipelineCachePath(path ? path : "");
    return ATLAS_OK;
}

void atlasPipelinesBuilt();  // (vk_backend.cpp)

ATLAS_API AtlasStatus atlas_create(AtlasBackend backend, AtlasContext** out) {
    if (!out) return ATLAS_ERR_ARG;
    *out = nullptr;
    ATLAS_TRY
    auto ctx = std::make_unique<AtlasContext>();
    if (backend == ATLAS_BACKEND_VULKAN) {
        std::string err;
        ctx->backend = makeVulkanBackend(err);
        if (!ctx->backend) {
            createError() = "Vulkan backend unavailable: " + err;  // atlas_last_error(NULL) reports it
            return ATLAS_ERR_GPU;
        }
    } else {
        ctx->backend = makeCpuBackend();
    }
    armFromEnvironment(*ctx->backend);
    ctx->voxgrid.setWorld(&ctx->world);  // (the grid takes its sections back from the world's tiers)
    ctx->voxgrid.gpuRank = ctx->backend->onDevice();  // (level 0 ranked on the GPU; the CPU backend ranks here)
    if (const char* d = std::getenv("ATLAS_CACHE_DIR")) ctx->cacheDir = d;
    if (const char* v = std::getenv("ATLAS_HOST_SECTIONS_MB")) ctx->cacheHostBytes = uint64_t(std::max(16, std::atoi(v))) << 20;
    if (const char* v = std::getenv("ATLAS_COLD_MB")) ctx->cacheDiskBytes = uint64_t(std::max(16, std::atoi(v))) << 20;
    // ATLAS_GROUP_LODS=<n>: only the n finest levels get groups (the rest keep the old path); a knob for measurements
    if (const char* gl = std::getenv("ATLAS_GROUP_LODS"))
        ctx->world.groupLods = std::clamp(std::atoi(gl), 0, MAX_LODS);
    // ATLAS_SECTION_KEEP=<n>: keep real sections n LOD-0 regions either side (tools: an element seen from 64 km and
    // back has no server to send it again; 256 regions is 64 km)
    if (const char* sk = std::getenv("ATLAS_SECTION_KEEP")) ctx->world.sectionKeep = std::clamp(std::atoi(sk), 0, 1024);
    // ATLAS_COARSE_BUDGET_MB=<n>: the coarse levels of real content's memory (World::coarseBudget; measurements of the
    // eviction and of a level borrowing a coarser one's micro-voxels)
    if (const char* cb = std::getenv("ATLAS_COARSE_BUDGET_MB"))
        ctx->world.coarseBudget = uint64_t(std::clamp(std::atoll(cb), 0ll, 1ll << 20)) << 20;
    auto pal = previewPalette();
    setPalette(ctx.get(), pal.data(), uint32_t(pal.size()));
    ctx->weather.assign(WEATHER_WORDS, 0);
    {  // the default air until the host sets one (the context-free cache shares the build)
        air::Coeffs c;
        cachedAir(nullptr, c, ctx->airLut);
        ctx->airC = c;
        writeLut(ctx->airLut, ctx->weather);
    }
    if (backend == ATLAS_BACKEND_VULKAN) atlasPipelinesBuilt();  // (atlas_pipeline_progress: the list as built)
    *out = ctx.release();
    return ATLAS_OK;
    ATLAS_CATCH(nullptr)
}

ATLAS_API void atlas_destroy(AtlasContext* ctx) {
    try {
        delete ctx;
    } catch (...) {
        // destructors do not throw; if one ever did, leaking beats terminating the game
    }
}

ATLAS_API const char* atlas_last_error(AtlasContext* ctx) {
    if (ctx) return ctx->error.c_str();
    return createError().empty() ? "null context" : createError().c_str();
}

ATLAS_API AtlasStatus atlas_set_palette(AtlasContext* ctx, const AtlasMaterial* m, uint32_t count) {
    if (!ctx || (!m && count)) return ATLAS_ERR_ARG;
    if (count > 65534) return ctx->fail(ATLAS_ERR_ARG, "palette too large");
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    setPalette(ctx, m, count);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_upload_column_tile(AtlasContext* ctx, const AtlasColumnTile* t) {
    if (!ctx || !t || !t->surface || !t->water || !t->topMat || !t->fillMat) return ATLAS_ERR_ARG;
    if (t->lod < 0 || t->lod >= MAX_LODS) return ctx->fail(ATLAS_ERR_ARG, "lod out of range");
    ATLAS_TRY
    auto tile = std::make_unique<Tile>();
    size_t n = size_t(TILE) * TILE;
    tile->lod = t->lod;
    tile->tx = t->tileX;
    tile->tz = t->tileZ;
    tile->surface.assign(t->surface, t->surface + n);
    tile->water.assign(t->water, t->water + n);
    tile->top.assign(t->topMat, t->topMat + n);
    tile->fill.assign(t->fillMat, t->fillMat + n);
    if (t->canopy && t->canopyMat) {
        tile->canopy.assign(t->canopy, t->canopy + n);
        tile->canopyMat.assign(t->canopyMat, t->canopyMat + n);
    }
    ctx->world.putTile(std::move(tile));
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_upload_section(AtlasContext* ctx, int32_t sx, int32_t sy, int32_t sz, const uint16_t* mats) {
    if (!ctx || !mats) return ATLAS_ERR_ARG;
    ATLAS_TRY
    // (the grid's books share the world's stored copy: one section's ids once in memory, whatever reads them)
    ATLAS_ZONE_MIN("api:uploadSection", 30);
    std::shared_ptr<const Section> sec = ctx->world.putSection(sx, sy, sz, mats);
    ctx->voxgrid.put(sx, sy, sz, VgIds(sec, &sec->mats));
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_upload_section_flags(AtlasContext* ctx, int32_t sx, int32_t sy, int32_t sz, const uint16_t* mats,
                                                 uint32_t flags) {
    if (!ctx || !mats || (flags & ~ATLAS_SECTION_REGENERABLE)) return ATLAS_ERR_ARG;
    ATLAS_TRY
    uint8_t f = (flags & ATLAS_SECTION_REGENERABLE) ? SectionStore::REGEN : 0;
    std::shared_ptr<const Section> sec = ctx->world.putSection(sx, sy, sz, mats, f);
    ctx->voxgrid.put(sx, sy, sz, VgIds(sec, &sec->mats));
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

namespace {
// the host's tiers from the context's settings: the warm budget by default a 32nd of the host heap (256 MiB..2 GiB)
void applyCache(AtlasContext* ctx) {
    uint64_t host = ctx->cacheHostBytes;
    if (!host) {
        MemoryInfo m;
        std::string err;
        uint64_t heap = ctx->backend->memoryInfo(m, err) ? m.hostHeapBytes : 0;
        host = std::clamp<uint64_t>(heap / 32, 256ull << 20, 2ull << 30);
    }
    World::Made made;
    ctx->world.configureStore(ctx->cacheDir, ctx->cacheSeed, ctx->cacheGenVersion, host, ctx->cacheDiskBytes, &made);
    for (auto& [k, s] : made) ctx->voxgrid.put(k.x, k.y, k.z, VgIds(s, &s->mats));
}
}  // namespace

ATLAS_API AtlasStatus atlas_set_cache(AtlasContext* ctx, const char* dir, uint64_t seed, uint32_t genVersion, uint64_t hostBytes,
                                      uint64_t diskBytes) {
    if (!ctx) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->cacheDir = dir ? dir : "";
    ctx->cacheSeed = seed;
    ctx->cacheGenVersion = genVersion;
    ctx->cacheHostBytes = hostBytes;
    ctx->cacheDiskBytes = diskBytes;
    if (ctx->gridPlanned) applyCache(ctx);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_notify_block(AtlasContext* ctx, int32_t x, int32_t y, int32_t z, uint16_t mat) {
    if (!ctx) return ATLAS_ERR_ARG;
    if (y < WORLD_MIN_Y || y >= WORLD_TOP_Y) return ATLAS_OK;
    ATLAS_TRY
    if (ctx->world.editBlock(x, y, z, mat)) {
        // the edited section, whole, to the level-0 grid (rebuilt on the GPU with the next frame)
        std::vector<uint16_t> m(4096);
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        if (ctx->world.sectionMats(sx, sy, sz, m.data())) ctx->voxgrid.put(sx, sy, sz, m.data());
    }
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_weather(AtlasContext* ctx, const AtlasWeather* w) {
    if (!ctx || !w) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    const uint32_t* src[4] = {w->nearField, w->nearDyn, w->farField, w->farDyn};
    for (int i = 0; i < 4; i++) {
        if (src[i]) std::memcpy(ctx->weather.data() + size_t(i) * 128 * 128, src[i], 128 * 128 * 4);
    }
    ctx->rainFactor = w->rainFactor;
    return ATLAS_OK;
}

static void airOut(const air::Params& p, AtlasAir* out) {
    out->airDensity = p.airDensity;
    out->aerosolDepth = p.aerosolDepth;
    out->angstrom = p.angstrom;
    out->aerosolAlbedo[0] = p.aerosolAlbedo.x;
    out->aerosolAlbedo[1] = p.aerosolAlbedo.y;
    out->aerosolAlbedo[2] = p.aerosolAlbedo.z;
    out->aerosolG = p.aerosolG;
    out->aerosolHeight = p.aerosolHeight;
    out->ozone = p.ozone;
    out->groundAlbedo = p.groundAlbedo;
    out->airScale = p.airScale;
}

ATLAS_API void atlas_air_defaults(AtlasAir* out) {
    if (out) airOut(air::fromClimate(air::Climate{}), out);
}

ATLAS_API AtlasStatus atlas_air_from_climate(const AtlasClimate* k, AtlasAir* out) {
    if (!k || !out) return ATLAS_ERR_ARG;
    air::Climate c;
    auto ok = [](float v, float d) { return std::isfinite(v) ? v : d; };
    c.temperatureC = ok(k->temperatureC, 15.0f);
    c.relativeHumidity = ok(k->relativeHumidity, 0.5f);
    c.pressure = ok(k->pressure, 1.0f);
    for (int i = 0; i < 4; i++) c.background[i] = ok(k->background[i], i == 0 ? 1.0f : 0.0f);
    c.dust = ok(k->dust, 0.0f);
    c.ash = ok(k->ash, 0.0f);
    c.snow = ok(k->snow, 0.0f);
    c.rain = ok(k->rain, 0.0f);
    c.fog = ok(k->fog, 0.0f);
    c.coast = ok(k->coast, 0.0f);
    airOut(air::fromClimate(c), out);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_air(AtlasContext* ctx, const AtlasAir* a) {
    if (!ctx || !a) return ATLAS_ERR_ARG;
    ATLAS_TRY
    air::Coeffs c;
    air::MsLut lut;
    cachedAir(a, c, lut);  // outside the lock (frames go on meanwhile); the host's own LUT (atlas_air_multiscatter) shares it
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->airC = c;
    ctx->airLut = lut;
    writeLut(lut, ctx->weather);
    ctx->airVersion++;
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_air_coeffs(const AtlasAir* a, float out[16]) {
    if (!a || !out) return ATLAS_ERR_ARG;
    air::Coeffs c = air::coeffs(airParamsOf(*a));
    const air::V3 v[4] = {c.rayleigh, c.mieScat, c.mieExt, c.ozone};
    const float w[4] = {c.invHMie, c.g, c.airScale, c.groundAlbedo};
    for (int i = 0; i < 4; i++) {
        out[i * 4] = v[i].x;
        out[i * 4 + 1] = v[i].y;
        out[i * 4 + 2] = v[i].z;
        out[i * 4 + 3] = w[i];
    }
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_air_multiscatter(const AtlasAir* a, float* rgba) {
    if (!a || !rgba) return ATLAS_ERR_ARG;
    ATLAS_TRY
    air::Coeffs c;
    air::MsLut lut;
    cachedAir(a, c, lut);
    for (int k = 0; k < air::MS_SIZE * air::MS_SIZE; k++) {
        rgba[k * 4] = lut.t[k].x;
        rgba[k * 4 + 1] = lut.t[k].y;
        rgba[k * 4 + 2] = lut.t[k].z;
        rgba[k * 4 + 3] = 1.0f;
    }
    return ATLAS_OK;
    ATLAS_CATCH(nullptr)
}

ATLAS_API AtlasStatus atlas_air_light(const AtlasAir* a, const float sunDir[3], const float moonDir[3],
                                      const float moonRad[3], float eyeY, float out[12]) {
    if (!a || !sunDir || !out || !std::isfinite(eyeY)) return ATLAS_ERR_ARG;
    ATLAS_TRY
    air::Coeffs c;
    air::MsLut lut;
    cachedAir(a, c, lut);
    float md[3], mr[3];
    moonOf(sunDir, moonDir, moonRad, md, mr);
    float kd[3];
    air::V3 kr, up, ground;
    air::keyLight(c, eyeY, sunDir, md, mr, kd, kr);
    air::skyLight(c, lut, air::altitudeOf(c, eyeY), air::lightsOf(sunDir, md, mr), up, ground);
    const float v[12] = {kd[0], kd[1], kd[2], kr.x, kr.y, kr.z, up.x, up.y, up.z, ground.x, ground.y, ground.z};
    std::memcpy(out, v, sizeof(v));
    return ATLAS_OK;
    ATLAS_CATCH(nullptr)
}

ATLAS_API AtlasStatus atlas_air_sky(const AtlasAir* a, const float sunDir[3], const float moonDir[3],
                                    const float moonRad[3], float eyeY, const float* dirs, uint32_t n, float* rgb) {
    if (!a || !sunDir || (n && (!dirs || !rgb)) || !std::isfinite(eyeY)) return ATLAS_ERR_ARG;
    ATLAS_TRY
    air::Coeffs c;
    air::MsLut lut;
    cachedAir(a, c, lut);
    float md[3], mr[3];
    moonOf(sunDir, moonDir, moonRad, md, mr);
    air::Lights l = air::lightsOf(sunDir, md, mr);
    float h = air::altitudeOf(c, eyeY);
    for (uint32_t i = 0; i < n; i++) {
        air::V3 s = air::skyRadiance(c, lut, h, dirs + size_t(i) * 3, l);
        rgb[i * 3] = s.x;
        rgb[i * 3 + 1] = s.y;
        rgb[i * 3 + 2] = s.z;
    }
    return ATLAS_OK;
    ATLAS_CATCH(nullptr)
}

ATLAS_API AtlasStatus atlas_set_cloud_noise(AtlasContext* ctx, const uint32_t* rgba) {
    if (!ctx || !rgba) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    std::vector<uint32_t> v(rgba, rgba + 64 * 64 * 64);
    ctx->cloudNoise.swap(v);  // new storage: the Vulkan backend re-uploads when the pointer changes
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_actors(AtlasContext* ctx, const AtlasActor* a, uint32_t count) {
    if (!ctx || (!a && count)) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->actors.assign(a, a + count);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_update(AtlasContext* ctx, const double centre[3], double budgetMs) {
    if (!ctx || !centre) return ATLAS_ERR_ARG;
    ATLAS_TRY
    ATLAS_ZONE("api:update");
    double ms = 0;
    // the level-0 grid: the columns its window entered read from the world here when the render thread could not (its
    // lock busy); what it gave up is the CPU's level 0 again only on the CPU backend (on a device the grid's coarse
    // levels answer there: no CPU level 0 near)
    ctx->voxgrid.refill(ctx->world);
    {
        std::vector<Key3> rel = ctx->voxgrid.takeReleased();
        if (!rel.empty() && !ctx->backend->onDevice()) ctx->world.gridReleased(rel);
    }
    // the grid's coarse levels own their levels' real blocks (before its first frame too: what they will hold)
    ctx->world.gridCoarse = ctx->voxgrid.enabled ? std::clamp(ctx->voxgrid.coarseLevels, 0, std::max(0, ctx->world.lodCount - 1)) : 0;
    {
        const char* e = std::getenv("ATLAS_VOXGRID_LEVEL0");  // (as buildInputs: before the first frame too)
        ctx->world.gridOwnsLevel0 = ctx->voxgrid.enabled && (!e || std::atoi(e) != 0);
    }
    {
        ATLAS_ZONE("world:update");
        ctx->world.update(centre, budgetMs, &ms);
    }
    ctx->stats.lastBuildMs = ms;
    // copy a new revision to the GPU here (the game calls update off the render thread), not in the next frame;
    // a lost device stages nothing (the render thread reports the loss)
    auto snap = ctx->world.packed();
    if (snap->lodCount && snap->revision != ctx->stagedRevision && !ctx->backend->lost()) {
        if (ctx->backend->stageScene(*snap)) ctx->stagedRevision = snap->revision;
    }
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

static AtlasStatus buildInputs(AtlasContext* ctx, const AtlasFrame* f, FrameInputs& in);
static void rememberFrame(AtlasContext* ctx, const AtlasFrame* f);

ATLAS_API AtlasStatus atlas_render(AtlasContext* ctx, const AtlasFrame* f, const AtlasFrameOutput* out) {
    if (!ctx || !f || !out || f->width == 0 || f->height == 0) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    FrameInputs in{};
    AtlasStatus st = buildInputs(ctx, f, in);
    if (st != ATLAS_OK) return st;
    // the near light needs both (unless atlas's own primary rays are the near field: ATLAS_FRAME_PRIMARY_NEAR)
    if ((!in.nearDepth || !in.nearInfo) && !(f->flags & FRAME_PRIMARY_NEAR_BITS)) in.frame.dims[2] &= ~ATLAS_FRAME_NEAR_LIGHT;
    double ms = 0;
    std::string err;
    if (!ctx->backend->render(in, ctx->targets, &ms, err)) return backendFailure(ctx, err, ATLAS_ERR_GPU);
    rememberFrame(ctx, f);
    size_t n = size_t(f->width) * f->height;
    if (out->colorLinear) std::memcpy(out->colorLinear, ctx->targets.color.data(), n * 16);
    // (out->nearLight exists only in a 0.15 caller's struct, which is the one that sets the flag)
    if ((f->flags & ATLAS_FRAME_NEAR_LIGHT) && out->nearLight) {
        if ((in.frame.dims[2] & ATLAS_FRAME_NEAR_LIGHT) && ctx->targets.nearLight.size() >= n * 4)
            std::memcpy(out->nearLight, ctx->targets.nearLight.data(), n * 16);
        else
            std::memset(out->nearLight, 0, n * 16);  // the flag was dropped (no near depth or info): no near light
    }
    if (out->color) {
        // harness tonemapper (the game composite tonemaps near and far together instead)
        static const auto lut = [] {
            std::vector<uint8_t> t(4097);
            for (int i = 0; i <= 4096; i++) t[size_t(i)] = uint8_t(std::pow(i / 4096.0f, 1.0f / 2.2f) * 255.0f + 0.5f);
            return t;
        }();
        const float* c = ctx->targets.color.data();
        for (size_t i = 0; i < n; i++) {
            uint32_t px = 0;
            for (int k = 0; k < 3; k++) {
                float v = std::fmax(c[4 * i + k], 0.0f);
                v = v / (1.0f + v);  // [0, 1)
                px |= uint32_t(lut[size_t(v * 4096.0f + 0.5f)]) << (8 * k);
            }
            px |= uint32_t(std::fmin(std::fmax(c[4 * i + 3], 0.0f), 1.0f) * 255.0f + 0.5f) << 24;
            out->color[i] = px;
        }
    }
    if (out->normal) std::memcpy(out->normal, ctx->targets.normal.data(), n * 4);
    if (out->depth) std::memcpy(out->depth, ctx->targets.depth.data(), n * 4);
    uint64_t steps = 0;
    for (uint32_t c : ctx->targets.counters) steps += c;
    ctx->stats.raySteps = steps;
    // (dev: ATLAS_STEP_STATS=1, the per-pixel steps' spread: a warp (8 x 4 pixels of an 8 x 8 group) runs as long as its
    // slowest pixel, so the mean of the warps' maxima is what the pass costs, not the mean. Not in timed frames,
    // ATLAS_TIMING_DEVICE_OUTPUTS: its sort of every pixel's count held the GPU idle between frames, and its clocks fell)
    static const bool stepStats = [] { const char* e = std::getenv("ATLAS_STEP_STATS"); return e && *e == '1'; }();
    if (stepStats && !std::getenv("ATLAS_TIMING_DEVICE_OUTPUTS") && ctx->targets.counters.size() >= size_t(f->width) * f->height) {
        const uint32_t W = f->width, H = f->height;
        std::vector<uint32_t> v(ctx->targets.counters.begin(), ctx->targets.counters.begin() + size_t(W) * H);
        double warpMax = 0;
        size_t warps = 0;
        for (uint32_t y = 0; y < H; y += 4)
            for (uint32_t x = 0; x < W; x += 8) {
                uint32_t m = 0;
                for (uint32_t j = y; j < std::min(y + 4, H); j++)
                    for (uint32_t i = x; i < std::min(x + 8, W); i++) m = std::max(m, v[size_t(j) * W + i]);
                warpMax += m;
                warps++;
            }
        std::sort(v.begin(), v.end());
        fprintf(stderr, "[atlas] steps: mean %.1f p50 %u p90 %u p99 %u p999 %u max %u warp-max mean %.1f\n",
                double(steps) / double(v.size()), v[v.size() / 2], v[v.size() * 9 / 10], v[v.size() * 99 / 100],
                v[v.size() * 999 / 1000], v.back(), warpMax / double(std::max<size_t>(warps, 1)));
        // (and by eighths of the image, top to bottom: the sky's rows against the ground's)
        char band[256];
        int at = 0;
        for (uint32_t b = 0; b < 8; b++) {
            double s = 0;
            for (uint32_t y = H * b / 8; y < H * (b + 1) / 8; y++)
                for (uint32_t x = 0; x < W; x++) s += ctx->targets.counters[size_t(y) * W + x];
            at += snprintf(band + at, sizeof(band) - at, " %.0f", s / (double(W) * (H * (b + 1) / 8 - H * b / 8)));
        }
        fprintf(stderr, "[atlas] steps by eighth, top to bottom:%s\n", band);
    }
    ctx->stats.lastFrameMs = ms;
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_gl_attach(AtlasContext* ctx, uint32_t w, uint32_t h, AtlasGlTargets* out) {
    if (!ctx || !out || w == 0 || h == 0) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    GlTargets t;
    std::string err;
    if (!ctx->backend->glAttach(w, h, t, err)) return backendFailure(ctx, err, ATLAS_ERR_UNSUPPORTED);
    *out = AtlasGlTargets{t.color, t.normal, t.depth, t.nearDepth, t.width, t.height};
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_gl_render(AtlasContext* ctx, const AtlasFrame* f) {
    return atlas_gl_render_io(ctx, f, nullptr);
}

ATLAS_API AtlasStatus atlas_gl_render_io(AtlasContext* ctx, const AtlasFrame* f, const AtlasGlFrameIO* io) {
    if (!ctx || !f || f->width == 0 || f->height == 0) return ATLAS_ERR_ARG;
    ATLAS_TRY
    fr::frame();  // the flight recorder's frame boundary: one atlas frame a game frame
    ATLAS_ZONE("api:glRender");
    std::unique_lock<std::mutex> lock(ctx->mu, std::defer_lock);
    {
        ATLAS_ZONE_MIN("wait:ctxLock", 50);
        lock.lock();
    }
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    FrameInputs in{};
    auto tb0 = std::chrono::steady_clock::now();
    AtlasStatus st;
    {
        ATLAS_ZONE("api:buildInputs");
        st = buildInputs(ctx, f, in);
    }
    ctx->glInputsMs += (std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tb0).count() - ctx->glInputsMs) * 0.05;
    if (st != ATLAS_OK) return st;
    in.preExposure = ctx->preExposure;
    GlFrameIO g;
    if (io) {
        g.nearDepthTex = io->nearDepthTex;
        g.depthZeroToOne = io->depthZeroToOne;
        g.reversedZ = io->reversedZ;
        std::memcpy(g.nearInvProjection, io->nearInvProjection, sizeof(g.nearInvProjection));
        g.dstColor = io->dstColor;
        g.dstNormal = io->dstNormal;
        g.dstDepth = io->dstDepth;
        g.dstSunMap = io->dstSunMap;
        if (g.nearDepthTex) in.wantNearDepth = true;
        // (abi 0.15: the fields exist only in a caller that sets the flag)
        if (f->flags & ATLAS_FRAME_NEAR_LIGHT) {
            g.nearInfoTex = io->nearInfoTex;
            g.dstNearLight = io->dstNearLight;
        }
    }
    // the near light needs the near field's depth, its info and somewhere to put the result (the backend also drops
    // it where it cannot read the near depth this frame)
    // (ATLAS_FRAME_PRIMARY_NEAR: the near field is atlas's own, only the output is needed)
    bool primaryNear = (f->flags & FRAME_PRIMARY_NEAR_BITS) != 0;
    if (!io || (!primaryNear && (!g.nearDepthTex || !g.nearInfoTex)) || !g.dstNearLight) in.frame.dims[2] &= ~ATLAS_FRAME_NEAR_LIGHT;
    double ms = ctx->stats.lastFrameMs;
    std::string err;
    if (!ctx->backend->glRender(in, io ? &g : nullptr, &ms, err)) return backendFailure(ctx, err, ATLAS_ERR_GPU);
    rememberFrame(ctx, f);
    ctx->stats.lastFrameMs = ms;
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API void atlas_gl_detach(AtlasContext* ctx) {
    if (!ctx) return;
    try {
        std::lock_guard<std::mutex> lock(ctx->mu);
        ctx->backend->glDetach();  // after a loss: frees the GL side and the images without waiting on the device
    } catch (...) {
    }
}

// General 4x4 inverse (column-major), in double. False when singular.
static bool invert4(const float* m, float* out) {
    double a[16], inv[16];
    for (int i = 0; i < 16; i++) a[i] = m[i];
    inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
    inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
    inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
    inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
    inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
    inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
    inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
    inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
    inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
    inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
    inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
    inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
    inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
    inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
    inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
    inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
    double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
    if (std::abs(det) < 1e-30) return false;
    for (int i = 0; i < 16; i++) out[i] = float(inv[i] / det);
    return true;
}

// Remembers this frame as the next frame's history (after a successful render).
static void rememberFrame(AtlasContext* ctx, const AtlasFrame* f) {
    std::memcpy(ctx->prevInvVP, f->invViewProj, sizeof(ctx->prevInvVP));
    for (int i = 0; i < 3; i++) ctx->prevCam[i] = f->cameraPos[i];
    ctx->prevW = f->width;
    ctx->prevH = f->height;
    ctx->prevCloudFlags = f->flags & (ATLAS_FRAME_CLOUDS | ATLAS_FRAME_CLOUDS_QUARTER);
    ctx->prevJitterNdc[0] = f->width > 0 ? ctx->jitter[0] * 2.0f / float(f->width) : 0.0f;
    ctx->prevJitterNdc[1] = f->height > 0 ? ctx->jitter[1] * 2.0f / float(f->height) : 0.0f;
    ctx->haveHistory = true;
    ctx->frameIndex++;
}

static void easeMs(double& v, std::chrono::steady_clock::time_point a) {
    v += (std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count() - v) * 0.05;
}

// The light by need (docs/ATLAS-RENDERER-PLAN.md "The light by need"; atlas_far.slang nearLightAt, nearLightMain): how
// much of the near light a frame traces, each knob a smooth function eased frame to frame, never a switch.
//  x the footprint scale: the secondary rays start at the level whose voxels are the pixel's footprint x this (1: about
//    a pixel; the budget raises it, coarser past where a voxel is sub-pixel);
//  y the share of the split's 16 px tiles traced a frame (the rest keep their history: the light is diffuse, the camera's
//    motion does not change it; a tile with a pixel lacking history is traced anyway): a still or slowly turning view
//    ATLAS_LIGHT_RATE (0.25: each pixel every 16 frames), up to every tile as the image moves faster (the history's
//    resampling blurs what it carries), the budget lowering it;
//  z the sun ray's cone (voxels of a level before the next: 108, about the inverse of the sun's diameter, an occluder's
//    voxel never wider than its penumbra), w the bounce's sun ray's (24: bounced light is soft).
// ATLAS_TARGET_MS=<ms>: the budget follows the frame's GPU time towards it (0 or unset: none). ATLAS_LIGHT=full: the
// full light (level 0 everywhere, no cones, every tile every frame; with ATLAS_NEAR_FULLRES=1 every pixel in the far
// pass: the reference).
static void lightControl(AtlasContext* ctx, const AtlasFrame* f, const float fwd[3], FrameGpu& g) {
    // (read every frame, as ATLAS_ABLATE: a measurement switches them between runs in one process)
    const char* le = std::getenv("ATLAS_LIGHT");
    if (le && std::strcmp(le, "full") == 0) {
        g.lightCtl[0] = 0.0f, g.lightCtl[1] = 1.0f, g.lightCtl[2] = 0.0f, g.lightCtl[3] = 0.0f;
        return;
    }
    const char* re = std::getenv("ATLAS_LIGHT_RATE");
    float rateStill = re && std::atof(re) > 0.0 ? std::fmin(float(std::atof(re)), 1.0f) : 0.25f;
    const char* te = std::getenv("ATLAS_TARGET_MS");
    float target = te ? float(std::atof(te)) : 0.0f;
    // the image's motion, pixels a frame: the view's turn, and the camera's move as a point 16 blocks off sees it
    float motion = 0.0f;
    float pa = std::fmax(g.params[3], 1e-5f);
    if (ctx->haveHistory && ctx->prevW == f->width && ctx->prevH == f->height) {
        float b[3];
        pixelDir(ctx->prevInvVP, f->width * 0.5f, f->height * 0.5f, f->width, f->height, b);
        float d = std::fmin(1.0f, fwd[0] * b[0] + fwd[1] * b[1] + fwd[2] * b[2]);
        double dx = f->cameraPos[0] - ctx->prevCam[0], dy = f->cameraPos[1] - ctx->prevCam[1], dz = f->cameraPos[2] - ctx->prevCam[2];
        motion = std::acos(d) / pa + float(std::sqrt(dx * dx + dy * dy + dz * dz)) / (16.0f * pa);
    }
    // the budget: the frame's GPU time against the target, a few percent a frame
    if (target > 0.0f && ctx->stats.lastFrameMs > 0.0) {
        float err = float(ctx->stats.lastFrameMs) / target - 1.0f;
        ctx->lightBudget = std::clamp(ctx->lightBudget - 0.05f * std::clamp(err, -1.0f, 1.0f), 0.25f, 1.0f);
    }
    float t = std::clamp((motion - 2.0f) / 22.0f, 0.0f, 1.0f);
    float want = (rateStill + (1.0f - rateStill) * t * t * (3.0f - 2.0f * t)) * ctx->lightBudget;
    want = std::clamp(want, 0.0625f, 1.0f);
    ctx->lightRate += (want - ctx->lightRate) * 0.2f;
    ctx->lightFoot += (1.0f / std::fmax(ctx->lightBudget, 0.25f) - ctx->lightFoot) * 0.2f;
    g.lightCtl[0] = ctx->lightFoot;
    g.lightCtl[1] = ctx->lightRate;
    g.lightCtl[2] = 108.0f;
    g.lightCtl[3] = 24.0f;
    // (measurements: ATLAS_LIGHT_KNOBS=foot,rate,cone,bounce overrides them, a negative entry keeps that one)
    if (const char* k = std::getenv("ATLAS_LIGHT_KNOBS")) {
        float v[4] = {-1, -1, -1, -1};
        std::sscanf(k, "%f,%f,%f,%f", &v[0], &v[1], &v[2], &v[3]);
        for (int i = 0; i < 4; i++)
            if (v[i] >= 0.0f) g.lightCtl[i] = v[i];
    }
}

// (FrameGpu.lodR) each level's resident radius: atlas_far.slang lodWindowR and its nesting, the same float steps, once
// a frame here (in the kernels every thread of five filled two arrays of eight)
static void lodRadii(FrameGpu& g) {
    uint32_t ablate;
    std::memcpy(&ablate, &g.jitter[3], 4);
    const bool vgOn = g.vgOrigin[3] != 0 && (ablate & 134217728u) == 0;  // (atlas_far.slang vgOn, vgLevelOn)
    auto levelOn = [&](int k) {
        if (k == 0) return vgOn;
        if (k > 6 || !vgOn) return false;
        return ((g.vgLevel[k - 1][3] >> 16) & 1) != 0 && (ablate & 1073741824u) == 0;
    };
    const int n = std::min(int(g.dims[3]), 8);
    float prev = 1.0f;
    for (int k = 0; k < 8; k++) {
        float r;
        if (k < n) {
            const LodGpu& L = g.lods[k];
            const int span = 256 << k;
            float lox = float(L.origin[0] * span - g.camInt[0]) - g.camFrac[0];
            float loz = float(L.origin[2] * span - g.camInt[2]) - g.camFrac[2];
            float hix = lox + float(L.dim[0] * span), hiz = loz + float(L.dim[2] * span);
            float w = std::max(std::min(std::min(-lox, hix), std::min(-loz, hiz)), 1.0f);
            if (k <= 6 && (k == 0 ? (g.vgDims[3] & 2u) != 0 && vgOn : levelOn(k))) {
                int gx0, gz0, X;
                if (k == 0) {
                    gx0 = g.vgOrigin[0] * 16, gz0 = g.vgOrigin[2] * 16, X = int(g.vgDims[0]);
                } else {
                    const int cs = 16 << k;
                    gx0 = g.vgLevel[k - 1][0] * cs, gz0 = g.vgLevel[k - 1][1] * cs, X = g.vgLevel[k - 1][3] & 255;
                }
                float gs = float(X * (16 << k));
                float gx = float(gx0 - g.camInt[0]) - g.camFrac[0], gz = float(gz0 - g.camInt[2]) - g.camFrac[2];
                w = std::min(w, std::max(std::min(std::min(-gx, gx + gs), std::min(-gz, gz + gs)), 1.0f));
            }
            r = std::max(w, prev * 1.01f);
        } else {
            r = prev * 2.0f;
        }
        g.lodR[k] = r;
        prev = r;
    }
}

static AtlasStatus buildInputs(AtlasContext* ctx, const AtlasFrame* f, FrameInputs& in) {
    auto tq = std::chrono::steady_clock::now();
    {
        ATLAS_ZONE_MIN("api:world.packed", 50);
        in.sceneHold = ctx->world.packed();
    }
    easeMs(ctx->inSnapMs, tq);
    in.scene = in.sceneHold.get();
    if (in.scene->lodCount == 0) return ctx->fail(ATLAS_ERR_STATE, "call atlas_update before atlas_render");
    // the first frame that traces the groups starts their building (the next atlas_update, nearest first)
    if ((f->flags & ATLAS_FRAME_GROUPS) && !ctx->world.groupsWanted.load(std::memory_order_relaxed))
        ctx->world.groupsWanted.store(true);
    FrameGpu& g = in.frame;
    std::memcpy(g.invViewProj, f->invViewProj, sizeof(g.invViewProj));
    for (int i = 0; i < 3; i++) {
        double fl = std::floor(f->cameraPos[i]);
        g.camInt[i] = int32_t(fl);
        g.camFrac[i] = float(f->cameraPos[i] - fl);
    }
    g.camFrac[3] = f->planetRadius > 0 ? f->planetRadius : 0.0f;
    float sl = std::sqrt(f->sunDir[0] * f->sunDir[0] + f->sunDir[1] * f->sunDir[1] + f->sunDir[2] * f->sunDir[2]);
    for (int i = 0; i < 3; i++) g.sunDir[i] = sl > 0 ? f->sunDir[i] / sl : (i == 1 ? 1.0f : 0.0f);
    g.sunDir[3] = f->timeSec;
    for (int i = 0; i < 3; i++) {
        g.moonDir[i] = ctx->moonDir[i];
        g.moonRad[i] = ctx->moonRad[i];
    }
    g.moonDir[3] = ctx->moonSet ? 1.0f : 0.0f;
    g.moonRad[3] = 0.0f;
    // the air (atlas_air.slang): its coefficients, the lights above it, the eye's altitude and the sky's light there
    // (integrated again when the sun, the moon, the eye's height or the air moved enough to show)
    {
        const air::Coeffs& c = ctx->airC;
        auto put = [](float* d, air::V3 v, float w) {
            d[0] = v.x;
            d[1] = v.y;
            d[2] = v.z;
            d[3] = w;
        };
        put(g.airRayleigh, c.rayleigh, c.invHMie);
        put(g.airMieScat, c.mieScat, c.g);
        put(g.airMieExt, c.mieExt, c.airScale);
        put(g.airOzone, c.ozone, c.groundAlbedo);
        float alt = air::altitudeOf(c, float(f->cameraPos[1]));
        float md[3], mr[3];
        moonOf(g.sunDir, ctx->moonSet ? ctx->moonDir : nullptr, ctx->moonSet ? ctx->moonRad : nullptr, md, mr);
        air::Lights l = air::lightsOf(g.sunDir, md, mr);
        put(g.airSun, l.sunE, alt);  // (the GPU takes an eclipse's share per point: atlas_air.slang airSunShareKm)
        // the sky's light on faces at the eye: an eclipse's share there, above a twilight's floor (the air beyond the
        // moon's shadow still lights the sky round the horizon)
        const float origin[3] = {0, 0, 0};
        const float sunShare = std::fmax(eclipseShare(ctx->eclipse, origin, g.sunDir), 0.004f);
        l.sunE = l.sunE * sunShare;
        put(g.airMoon, l.moonE, 0.0f);
        auto& s = ctx->skyLight;
        const float me[3] = {l.moonE.x, l.moonE.y, l.moonE.z};
        bool same = s.version == ctx->airVersion && std::fabs(s.alt - alt) < 0.01f &&
                    std::fabs(s.share - sunShare) < 1e-4f;
        for (int i = 0; i < 3 && same; i++)
            same = std::fabs(s.sun[i] - g.sunDir[i]) < 2e-4f && std::fabs(s.moon[i] - md[i]) < 2e-3f &&
                   std::fabs(s.moonE[i] - me[i]) < 1e-4f;
        auto tsk = std::chrono::steady_clock::now();
        if (!same) {
            air::skyLight(c, ctx->airLut, alt, l, s.up, s.ground);
            s.version = ctx->airVersion;
            s.alt = alt;
            s.share = sunShare;
            for (int i = 0; i < 3; i++) {
                s.sun[i] = g.sunDir[i];
                s.moon[i] = md[i];
                s.moonE[i] = me[i];
            }
        }
        easeMs(ctx->inSkyMs, tsk);
        put(g.airSkyUp, s.up + air::V3{ctx->glowUp[0], ctx->glowUp[1], ctx->glowUp[2]}, 0.0f);
        put(g.airGround, s.ground + air::V3{ctx->glowGround[0], ctx->glowGround[1], ctx->glowGround[2]}, 0.0f);
    }
    for (int i = 0; i < 4; i++) {
        g.eclipseA[i] = ctx->eclipse[i];
        g.eclipseB[i] = ctx->eclipse[4 + i];
    }
    g.eclipseC[0] = ctx->eclipseKm;
    g.eclipseC[1] = ctx->eclipse[3] > 0.0f || ctx->eclipse[7] > 0.0f ? 1.0f : 0.0f;
    g.eclipseC[2] = g.eclipseC[3] = 0.0f;
    g.jitter[0] = f->width > 0 ? ctx->jitter[0] * 2.0f / float(f->width) : 0.0f;
    g.jitter[1] = f->height > 0 ? ctx->jitter[1] * 2.0f / float(f->height) : 0.0f;
    g.jitter[2] = ctx->jitter[0] != 0.0f || ctx->jitter[1] != 0.0f ? 1.0f : 0.0f;
    g.farLight[2] = ctx->prevJitterNdc[0];  // (the near light's history: the last frame's rays went through these)
    g.farLight[3] = ctx->prevJitterNdc[1];
    // ATLAS_ABLATE=<bits>: measurements only, leave parts of the shading out (1 AO, 2 smooth normals, 4 shadows, 8 far
    // lights, 16 water reflections, 32 the trace itself after the first hit's distance; the far light: 256 the cheap
    // light of ATLAS_FRAME_LIGHT_LOW, 512 never enclosed (the sky's short reach and the aerial haze under solid cover as
    // in the open), 1024 no light through leaves, 4096 a grey of the sky visibility (| 2048: the
    // secondary rays' origin in a solid), 8192 two sky rays, 16384 half their reach, 524288 no traced bounce (the
    // look's constant alone), 2097152 the secondary rays' level 0 without the height mip, 4194304 foliage free to them,
    // bits 23-25 a counter of the near light's secondary rays per pixel (atlas_far.slang gSecMode), 67108864 its level 0
    // only, 536870912 the counters count the pixels the split traces (raySteps: the light by need's work); the groups: 64 draw every node
    // whole, 128 a drawn box without its texel walk, 32768 never draw a node whole for its error, bits 16-18 the level
    // their primary rays start from: a coarse level seen up close)
    {
        // (read every frame: a measurement interleaves its variants in one process, atlas_render nlablate=)
        const char* v = std::getenv("ATLAS_ABLATE");
        const uint32_t ablate = (v ? uint32_t(std::strtoul(v, nullptr, 0)) : 0u) | ctx->ablateExtra;
        std::memcpy(&g.jitter[3], &ablate, 4);
        // ATLAS_LOD=mode,minCos,bias (measurements): the continuous level's threshold pattern (atlas_far.slang gLodXi),
        // the grazing widening's least cos (0.5 by default: at most one level), a bias added to every ray's level
        // (and a fourth: the gain of the light through leaves, 0: 1)
        float lp[4] = {0.0f, 0.5f, 0.0f, 0.0f};
        if (const char* e = std::getenv("ATLAS_LOD")) std::sscanf(e, "%f,%f,%f,%f", &lp[0], &lp[1], &lp[2], &lp[3]);
        for (int i = 0; i < 4; i++) g.lodParams[i] = lp[i];
    }
    float a[3], b[3];
    pixelDir(f->invViewProj, f->width * 0.5f, f->height * 0.5f, f->width, f->height, a);
    pixelDir(f->invViewProj, f->width * 0.5f + 1, f->height * 0.5f, f->width, f->height, b);
    {
        // the view's horizontal forward: the world builds what lies behind the camera after what it sees (World::setView)
        float hl = std::sqrt(a[0] * a[0] + a[2] * a[2]);
        ATLAS_ZONE_MIN("api:setView", 50);
        if (hl > 1e-4f) {
            ctx->world.setView(a[0] / hl, a[2] / hl);
            ctx->voxgrid.setView(a[0] / hl, a[2] / hl);
        }
        // the grid's residency: the full forward, the cone to the screen's corner, the pixel's angle (times the lod
        // bias, as the continuous level's footprint) and the level's bias (ATLAS_LOD's third knob)
        float corner[3];
        pixelDir(f->invViewProj, 0.0f, 0.0f, f->width, f->height, corner);
        float cosHalf = a[0] * corner[0] + a[1] * corner[1] + a[2] * corner[2];
        float dc = std::fmin(1.0f, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]);
        float lodBias = f->lodBias > 0 ? f->lodBias : 1.0f;
        ctx->voxgrid.setView(a, cosHalf, std::acos(dc) * lodBias, g.lodParams[2]);
        if (!ctx->gridPlanned) {
            // level 0's pool from the device's memory plan (atlas_memory_info's budget), before its first frame
            ctx->gridPlanned = true;
            MemoryInfo m;
            std::string merr;
            if (ctx->backend->memoryInfo(m, merr))
                ctx->voxgrid.planMemory(m.deviceLocalBudget ? m.deviceLocalBudget : m.deviceLocalBytes * 8 / 10, m.maxStorageRange);
            applyCache(ctx);  // (and the host's tiers)
        }
    }
    float dot = std::fmin(1.0f, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]);
    g.params[0] = f->nearFieldEnd;
    g.params[1] = f->maxDistance;
    g.params[2] = f->lodBias > 0 ? f->lodBias : 1.0f;
    g.params[3] = std::acos(dot);
    g.dims[0] = f->width;
    g.dims[1] = f->height;
    g.dims[2] = f->flags;
    g.dims[3] = uint32_t(in.scene->lodCount);
    g.weatherParams[0] = ctx->rainFactor;
    std::memcpy(&g.weatherParams[2], &ctx->textureTicks, 4);  // (bits: atlas_bricks.slang brickOf's clock, 0 unset)
    {
        ATLAS_ZONE_MIN("api:lightControl", 50);
        lightControl(ctx, f, a, g);
    }
    // last frame's view-projection (inverted back from its inverse) and camera, whenever the frames are comparable:
    // the near light's history reprojects through them (with or without clouds: weather off left it no camera, and
    // every pixel's history was refused); temporal.y the clouds' own history, which also needs the same cloud pass
    uint32_t cloudFlags = f->flags & (ATLAS_FRAME_CLOUDS | ATLAS_FRAME_CLOUDS_QUARTER);
    bool comparable = ctx->haveHistory && ctx->prevW == f->width && ctx->prevH == f->height;
    if (comparable && invert4(ctx->prevInvVP, g.prevViewProj)) {
        for (int i = 0; i < 3; i++) g.prevCam[i] = float(ctx->prevCam[i] - double(g.camInt[i]));
        if (ctx->prevCloudFlags == cloudFlags && (cloudFlags & ATLAS_FRAME_CLOUDS)) g.temporal[1] = 1;
    }
    g.temporal[0] = ctx->frameIndex;
    g.temporal[2] = nowMs();  // World's clock: refinement fades (RegionGpu.runs.w) are stamped with it
    g.temporal[3] = ctx->world.fadeMs;
    if (ctx->sunMapSize) {
        // the map stays put while the camera wanders (256-block steps), so most frames only refresh a band of rows
        // (the light moves slowly); a step or a new size redoes all of it
        uint32_t S = ctx->sunMapSize;
        double cx = std::floor(f->cameraPos[0] / 256.0 + 0.5) * 256.0, cz = std::floor(f->cameraPos[2] / 256.0 + 0.5) * 256.0;
        bool full = ctx->sunMapFull || cx != ctx->sunMapCentre[0] || cz != ctx->sunMapCentre[2];
        ctx->sunMapCentre[0] = cx;
        ctx->sunMapCentre[2] = cz;
        ctx->sunMapFull = false;
        float L[3];
        bool day = g.sunDir[1] > -0.05f;  // the key light: the sun, or the moon opposite it at night
        for (int i = 0; i < 3; i++) L[i] = day ? g.sunDir[i] : (ctx->moonSet ? g.moonDir[i] : -g.sunDir[i]);
        float up[3] = {0, 0, 1};
        if (std::abs(L[2]) > 0.9f) up[0] = 1, up[2] = 0;
        float X[3] = {up[1] * L[2] - up[2] * L[1], up[2] * L[0] - up[0] * L[2], up[0] * L[1] - up[1] * L[0]};
        float xl = std::sqrt(X[0] * X[0] + X[1] * X[1] + X[2] * X[2]);
        for (float& v : X) v /= xl;
        float Y[3] = {L[1] * X[2] - L[2] * X[1], L[2] * X[0] - L[0] * X[2], L[0] * X[1] - L[1] * X[0]};
        g.sunMapCentre[0] = float(cx - double(g.camInt[0]));
        g.sunMapCentre[1] = float(64.0 - double(g.camInt[1]));
        g.sunMapCentre[2] = float(cz - double(g.camInt[2]));
        g.sunMapCentre[3] = ctx->sunMapHalf;
        for (int i = 0; i < 3; i++) {
            g.sunMapX[i] = X[i];
            g.sunMapY[i] = Y[i];
            g.sunMapL[i] = L[i];
        }
        uint32_t band = std::max(8u, S / 8);
        g.sunMapRows[0] = full ? 0 : ctx->sunMapCursor;
        g.sunMapRows[1] = full ? S : band;
        g.sunMapRows[2] = S;
        g.sunMapRows[3] = 1;
        ctx->sunMapCursor = full ? 0 : (ctx->sunMapCursor + band) % S;
    }
    std::memcpy(g.lods, in.scene->lods, sizeof(g.lods));
    in.palette = &ctx->palette;
    in.weather = &ctx->weather;
    in.cloudNoise = &ctx->cloudNoise;
    // far actors: in the kernel's frame (relative to the integer camera block, like every other position there),
    // computed in double; the curvature drop uses the distance from the camera itself
    in.actors.clear();
    for (const AtlasActor& a : ctx->actors) {
        ActorGpu ag{};
        double dx = a.pos[0] - f->cameraPos[0], dz = a.pos[2] - f->cameraPos[2];
        if (dx * dx + dz * dz > double(f->maxDistance) * f->maxDistance) continue;
        double drop = f->planetRadius > 0 ? (dx * dx + dz * dz) / (2.0 * f->planetRadius) : 0.0;
        ag.posYaw[0] = float(a.pos[0] - double(g.camInt[0]));
        ag.posYaw[1] = float(a.pos[1] - double(g.camInt[1]) - drop);
        ag.posYaw[2] = float(a.pos[2] - double(g.camInt[2]));
        ag.posYaw[3] = a.yaw;
        ag.shape[0] = a.height;
        ag.shape[1] = a.radius;
        ag.shape[2] = float(a.kind);
        std::memcpy(&ag.shape[3], &a.tint, 4);
        ag.motion[0] = float((a.flags >> 16) & 0xFFFFu) / 65536.0f;
        ag.motion[1] = float((a.flags >> 8) & 3u);
        ag.motion[2] = 1.0f - float(a.flags & 255u) / 255.0f;
        if ((a.flags & 255u) == 255u) continue;  // fully dissolved: the real entity has taken over
        in.actors.push_back(ag);
        if (in.actors.size() >= 512) break;
    }
    g.weatherParams[3] = float(in.actors.size());
    // far lights after the actors (then their screen-tile lists); the kernel finds them from the two counts
    {
        LightView lv{};
        for (int i = 0; i < 3; i++) {
            lv.cam[i] = f->cameraPos[i];
            lv.camInt[i] = g.camInt[i];
        }
        float vp[16];
        if (invert4(f->invViewProj, vp)) {
            std::memcpy(g.viewProj, vp, sizeof(vp));  // (ATLAS_FRAME_PRIMARY_NEAR: the host's NDC depth)
            std::memcpy(lv.viewProj, vp, sizeof(vp));
            lv.width = f->width;
            lv.height = f->height;
            lv.pixelAngle = g.params[3];
            lv.nearEnd = f->nearFieldEnd;
            lv.maxDist = f->maxDistance;
            lv.planetRadius = f->planetRadius;
            lv.sunY = g.sunDir[1];
            // the air's extinction (green) per block at the eye's altitude
            const air::Coeffs& c = ctx->airC;
            float h = air::altitudeOf(c, float(f->cameraPos[1]));
            lv.hazeDensity = (c.rayleigh.y * std::exp(-h / air::H_RAYLEIGH) + c.mieExt.y * std::exp(-h * c.invHMie)) * c.airScale;
            auto tl = std::chrono::steady_clock::now();
            ATLAS_ZONE_MIN("api:lights", 50);
            if (ctx->backend->gpuLights()) {
                // culled, ranked and binned on the GPU (atlas_far.slang lightCullMain): only the index goes up, when it
                // changes; weatherParams.y says there are lights at all
                ctx->lights.packGpu(ctx->lightsRevision, ctx->lightsPacked, ctx->lightRecords);
                in.lights = &ctx->lightsPacked;
                in.lightsRevision = ctx->lightsRevision;
                in.lightRecords = ctx->lightRecords;
                g.dims[2] |= FRAME_GPU_LIGHTS_BIT;
                g.weatherParams[1] = ctx->lightRecords > 0 || (ctx->gridLights && ctx->voxgrid.enabled) ? 1.0f : 0.0f;  // (gridLightMain: last frame's table)
                g.farLight[0] = lv.hazeDensity;
            } else {
                g.weatherParams[1] = float(ctx->lights.frame(lv, in.actors));
            }
            easeMs(ctx->inLightsMs, tl);
        }
    }
    if (in.actors.empty()) in.actors.push_back(ActorGpu{});
    // the grid: its windows follow the camera; the backend builds what changed before this frame's passes
    // (its coarse levels up to the world's coarsest, set before the first frame lays them out)
    ctx->voxgrid.coarseLevels = std::min(ctx->voxgrid.coarseLevels, std::max(0, ctx->world.lodCount - 1));
    ctx->voxgrid.stoneMat = ctx->world.stoneMat;
    auto tgr = std::chrono::steady_clock::now();
    {
        ATLAS_ZONE_MIN("api:voxgrid.frame", 50);
        ctx->voxgrid.frame(g.camInt, ctx->world);
    }
    easeMs(ctx->inGridMs, tgr);
    ATLAS_ZONE_MIN("api:buildInputs tail (grid levels, packing, textures, bricks)", 100);
    {
        ATLAS_ZONE_MIN("api:voxgrid.origin+levelFrame", 100);
    ctx->voxgrid.origin(g.vgOrigin);
    g.vgDims[0] = uint32_t(ctx->voxgrid.D);
    g.vgDims[1] = uint32_t(ctx->voxgrid.H);
    g.vgDims[2] = uint32_t(ctx->voxgrid.D);
    for (int k = 1; k < VG_LEVELS; k++) ctx->voxgrid.levelFrame(k, g.vgLevel[k - 1]);
    }
    // the coarse levels own the real blocks at their levels: the CPU builds those levels' regions from the tiles alone
    // and no longer rebuilds them (nor digests) per section (World::gridCoarse)
    ctx->world.gridCoarse = ctx->voxgrid.levels() - 1;  // (atlas_update sets it before the first frame too)
    // the grid owns level 0 (ATLAS_VOXGRID_LEVEL0=0: not): primary rays take it over the groups there too, and the CPU no
    // longer rebuilds the level-0 regions of the sections it holds (World::gridOwnsLevel0)
    static const bool ownLevel0 = [] {
        const char* e = std::getenv("ATLAS_VOXGRID_LEVEL0");
        return !e || std::atoi(e) != 0;
    }();
    ctx->world.gridOwnsLevel0 = ownLevel0 && ctx->voxgrid.enabled;
    // (the host's tiers: uploads packed on the GPU where the grid owns every level's real blocks; the CPU backend and
    // the CPU's own level paths keep raw sections)
    {
        ATLAS_ZONE_MIN("api:world.setPackOnGpu", 100);
        ctx->world.setPackOnGpu(ctx->backend->onDevice() && ctx->world.gridOwnsReal() && ctx->voxgrid.packBudget > 0);
    }
    // (bit 2: the grid owns every level's real blocks, World::gridOwnsReal: level 0's regions are the tiles' alone)
    // (bit 4: a run of level-0 chunks the grid does not hold yet traced in its coarse levels, atlas_far.slang traceLod;
    // ATLAS_VOXGRID_FALLBACK=0, measurements: the regions as before)
    static const bool fallback = [] {
        const char* e = std::getenv("ATLAS_VOXGRID_FALLBACK");
        return !e || std::atoi(e) != 0;
    }();
    g.vgDims[3] = (ctx->world.gridOwnsLevel0 ? 1u : 0u) | (ctx->world.gridOwnsReal() ? 2u : 0u) | (fallback ? 4u : 0u);
    lodRadii(g);  // (after every field it reads: the levels, the grid's windows, the camera, the measurement bits)
    in.voxgrid = &ctx->voxgrid;
    {
        // the near field's textures (atlas_set_material_textures), packed for the kernels once each revision
        auto ttx = std::chrono::steady_clock::now();
        if (ctx->texPack.sync(ctx->texturesPacked)) ctx->texturesRevision++;  // (the slots the worker finished; never waits)
        easeMs(ctx->inTexMs, ttx);
        in.textures = &ctx->texturesPacked;
        in.texturesRevision = ctx->texturesRevision;
        in.texturesGeneration = ctx->texPack.generation();
        // the materials' lights (gridLightMain): every emitter at its level in its colour (its brick's glowing
        // micro-voxels, else its palette colour), checked against the palette each frame (cheap: a word a material)
        ATLAS_ZONE_MIN("api:materialLights", 100);
        std::vector<uint32_t>& lw = ctx->materialLights;
        lw.assign(ctx->palette.size(), 0u);
        uint64_t lh = 0xcbf29ce484222325ull;
        bool anyLight = false;
        for (size_t m = 1; m < ctx->palette.size(); m++) {
            const MaterialGpu& p = ctx->palette[m];
            uint32_t level = uint32_t(std::clamp(p.emisClass[0] * 15.0f + 0.5f, 0.0f, 15.0f));
            if (!level || p.emisClass[1] == float(ATLAS_CLASS_WATER)) continue;
            uint32_t rgb = ctx->bricks.lightRgb(uint32_t(m));
            if (!rgb) {
                float mx = std::max(p.albedoRough[0], std::max(p.albedoRough[1], std::max(p.albedoRough[2], 1e-4f)));
                auto q = [&](float x) { return uint32_t(std::clamp(std::pow(x / mx, 1.0f / 2.2f) * 255.0f + 0.5f, 0.0f, 255.0f)); };
                rgb = q(p.albedoRough[0]) << 16 | q(p.albedoRough[1]) << 8 | q(p.albedoRough[2]);
            }
            lw[m] = level << 24 | rgb;
            lh = (lh ^ (uint64_t(m) << 32 | lw[m])) * 0x100000001b3ull;
            anyLight = true;
        }
        ctx->gridLights = anyLight;
        bool lightsMoved = lh != ctx->materialLightsHash;
        ctx->materialLightsHash = lh;
        uint64_t sr = ctx->bricksStoreRevision;
        ATLAS_ZONE_MIN("api:bricks.pack", 50);
        if (ctx->bricks.pack(sr, lightsMoved, lw, ctx->bricksPacked, sr)) {
            ctx->bricksStoreRevision = sr;
            ctx->bricksRevision++;
        }
        in.bricks = &ctx->bricksPacked;
        in.bricksRevision = ctx->bricksRevision;
        // (ATLAS_GRID_LIGHTS=0, measurements: the grid's emitters light nothing)
        static const bool gridLightsOn = [] {
            const char* e = std::getenv("ATLAS_GRID_LIGHTS");
            return !e || std::atoi(e) != 0;
        }();
        in.gridLights = gridLightsOn && ctx->gridLights && ctx->voxgrid.enabled;
    }
    in.wantNearDepth = (f->flags & ATLAS_FRAME_NEAR_DEPTH) != 0;
    in.nearDepth = in.wantNearDepth ? f->nearDepth : nullptr;
    if (in.nearDepth && (f->flags & ATLAS_FRAME_FILL_NEAR)) {
        // the host's depths are per pixel: its sky is all-sky (2e30), as the GL path marks a fully uncovered pixel
        ctx->nearDepthHost.assign(f->nearDepth, f->nearDepth + size_t(f->width) * f->height);
        for (float& d : ctx->nearDepthHost)
            if (d >= 1e29f) d = 2e30f;
        in.nearDepth = ctx->nearDepthHost.data();
    }
    if ((f->flags & ATLAS_FRAME_NEAR_DEPTH) && !f->nearDepth) g.dims[2] &= ~ATLAS_FRAME_NEAR_DEPTH;  // GL path sets it back
    // (abi 0.15) the near light's surface info (host path; AtlasFrame.nearInfo exists only in a caller with the flag)
    in.nearInfo = (f->flags & ATLAS_FRAME_NEAR_LIGHT) ? f->nearInfo : nullptr;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_get_stats(AtlasContext* ctx, AtlasStats* out) {
    if (!ctx || !out) return ATLAS_ERR_ARG;
    ATLAS_TRY
    ctx->stats.regions = uint32_t(ctx->world.regionCount());
    ctx->stats.regionsDirty = uint32_t(ctx->world.pendingCount());  // was never set (always 0)
    ctx->stats.nodeBytes = ctx->world.nodeBytes();
    ctx->stats.leafBytes = ctx->world.leafBytes();
    ctx->stats.materialBytes = ctx->world.materialBytes();
    *out = ctx->stats;
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_preview_load_raster(AtlasContext* ctx, const char* path) {
    if (!ctx || !path) return ATLAS_ERR_ARG;
    ATLAS_TRY
    auto p = std::make_unique<PreviewSource>();
    std::string err;
    if (!p->load(path, err)) return ctx->fail(ATLAS_ERR_IO, err);
    ctx->preview = std::move(p);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_preview_generate(AtlasContext* ctx, int32_t lod, int32_t x0, int32_t z0, int32_t x1, int32_t z1) {
    if (!ctx || lod < 0 || lod >= MAX_LODS) return ATLAS_ERR_ARG;
    if (!ctx->preview) return ctx->fail(ATLAS_ERR_STATE, "no preview raster loaded");
    ATLAS_TRY
    int span = 256 << lod;
    auto fdiv = [](int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); };
    int tx0 = fdiv(x0, span), tz0 = fdiv(z0, span), tx1 = fdiv(x1 - 1, span), tz1 = fdiv(z1 - 1, span);
    std::vector<std::pair<int, int>> todo;
    for (int tz = tz0; tz <= tz1; tz++)
        for (int tx = tx0; tx <= tx1; tx++)
            if (!ctx->world.hasTile(lod, tx, tz)) todo.push_back({tx, tz});
    std::atomic<size_t> next{0};
    auto worker = [&] {
        for (;;) {
            size_t i = next.fetch_add(1);
            if (i >= todo.size()) return;
            ctx->world.putTile(ctx->preview->makeTile(lod, todo[i].first, todo[i].second));
        }
    };
    parallelRun(worker);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_upload_column_heights(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                     const float* surface, const float* water) {
    if (!ctx || lod < 0 || lod >= MAX_LODS || !surface || !water) return ATLAS_ERR_ARG;
    if (!ctx->preview) return ctx->fail(ATLAS_ERR_STATE, "no preview raster loaded (it dresses the columns)");
    ATLAS_TRY
    ctx->world.putTile(ctx->preview->dressTile(lod, tileX, tileZ, surface, water));
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_upload_column_heights2(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat) {
    if (!ctx || lod < 0 || lod >= MAX_LODS || !surface || !water) return ATLAS_ERR_ARG;
    if (!ctx->preview) return ctx->fail(ATLAS_ERR_STATE, "no preview raster loaded (it dresses the columns)");
    ATLAS_TRY
    ctx->world.putTile(ctx->preview->dressTile(lod, tileX, tileZ, surface, water, grassMat, foliageMat));
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_upload_column_heights3(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* topMat,
                                                      const uint16_t* fillMat, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat, const uint8_t* flags) {
    if (!ctx || lod < 0 || lod >= MAX_LODS || !surface || !water) return ATLAS_ERR_ARG;
    if (!ctx->preview) return ctx->fail(ATLAS_ERR_STATE, "no preview raster loaded (it dresses the columns)");
    PreviewSource::Overrides o;
    o.grassMat = grassMat;
    o.foliageMat = foliageMat;
    o.topMat = topMat;
    o.fillMat = fillMat;
    o.flags = flags;
    ctx->world.putTile(ctx->preview->dressTile(lod, tileX, tileZ, surface, water, o));
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_upload_column_structures(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                        const float* wallTop, const float* roofTop,
                                                        const uint16_t* wallMat, const uint16_t* roofMat) {
    return atlas_upload_column_structures2(ctx, lod, tileX, tileZ, wallTop, roofTop, wallMat, roofMat, nullptr);
}

ATLAS_API AtlasStatus atlas_upload_column_structures2(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                         const float* wallTop, const float* roofTop,
                                                         const uint16_t* wallMat, const uint16_t* roofMat,
                                                         const float* base) {
    return atlas_upload_column_structures3(ctx, lod, tileX, tileZ, wallTop, roofTop, wallMat, roofMat, base, 0u);
}

ATLAS_API AtlasStatus atlas_upload_column_structures3(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                         const float* wallTop, const float* roofTop,
                                                         const uint16_t* wallMat, const uint16_t* roofMat,
                                                         const float* base, uint32_t flags) {
    if (!ctx || lod < 0 || lod >= MAX_LODS) return ATLAS_ERR_ARG;
    ATLAS_TRY
    if (!wallTop) {
        ctx->world.putStructures(lod, tileX, tileZ, nullptr);
        return ATLAS_OK;
    }
    if (!roofTop || !wallMat || !roofMat) return ATLAS_ERR_ARG;
    size_t n = size_t(TILE) * TILE;
    auto s = std::make_shared<Structures>();
    s->replacesCanopy = (flags & ATLAS_STRUCTURES_REPLACE_CANOPY) != 0;
    s->wallTop.assign(wallTop, wallTop + n);
    s->roofTop.assign(roofTop, roofTop + n);
    s->wallMat.assign(wallMat, wallMat + n);
    s->roofMat.assign(roofMat, roofMat + n);
    bool floating = false;
    if (base) {
        s->base.assign(base, base + n);
        for (size_t c = 0; c < n; c++) {
            if (std::isnan(s->base[c])) s->base[c] = -1e30f;  // from the ground
            floating |= s->base[c] > -1e29f;
        }
        if (!floating) s->base.clear();
    }
    for (size_t c = 0; c < n; c++) {
        if (std::isnan(s->roofTop[c])) s->roofTop[c] = -1e30f;  // never above any voxel
        s->wallTop[c] = std::min(s->wallTop[c], s->roofTop[c]);
    }
    ctx->world.putStructures(lod, tileX, tileZ, std::move(s));
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_materials(AtlasContext* ctx, uint32_t first, const AtlasMaterial* m, uint32_t count) {
    if (!ctx || first == 0 || (!m && count)) return ATLAS_ERR_ARG;
    if (uint64_t(first) + count > 65535) return ctx->fail(ATLAS_ERR_ARG, "material ids beyond 65534");
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    AtlasMaterial grey{{0.3f, 0.3f, 0.3f}, 0.9f, 0.0f, ATLAS_CLASS_TERRAIN};
    if (ctx->palette.size() < first + count) ctx->palette.resize(first + count, toGpu(grey));
    for (uint32_t i = 0; i < count; i++) ctx->palette[first + i] = toGpu(m[i]);
    syncClasses(ctx);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_sun_map(AtlasContext* ctx, uint32_t size, float halfExtent) {
    if (!ctx || (size && (size < 64 || size > 4096)) || (size && !(halfExtent >= 256.0f && halfExtent <= 16384.0f)))
        return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->sunMapSize = size;
    ctx->sunMapHalf = halfExtent;
    ctx->sunMapFull = true;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_eclipse(AtlasContext* ctx, const float occulters[8], float kmPerBlock) {
    if (!ctx || !occulters || !std::isfinite(kmPerBlock) || kmPerBlock <= 0.0f) return ATLAS_ERR_ARG;
    for (int i = 0; i < 8; i++) {
        if (!std::isfinite(occulters[i])) return ATLAS_ERR_ARG;
        ctx->eclipse[i] = occulters[i];
    }
    ctx->eclipseKm = kmPerBlock;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_moon(AtlasContext* ctx, const float dir[3], const float radiance[3]) {
    if (!ctx || !dir || !radiance) return ATLAS_ERR_ARG;
    float len = std::sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
    if (!(len > 1e-4f) || !std::isfinite(len)) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    for (int i = 0; i < 3; i++) {
        ctx->moonDir[i] = dir[i] / len;
        ctx->moonRad[i] = std::isfinite(radiance[i]) ? std::max(0.0f, radiance[i]) : 0.0f;
    }
    ctx->moonSet = ctx->moonRad[0] + ctx->moonRad[1] + ctx->moonRad[2] > 0.0f;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_sky_glow(AtlasContext* ctx, const float up[3], const float ground[3]) {
    if (!ctx || !up || !ground) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    for (int i = 0; i < 3; i++) {
        ctx->glowUp[i] = std::isfinite(up[i]) ? std::max(0.0f, up[i]) : 0.0f;
        ctx->glowGround[i] = std::isfinite(ground[i]) ? std::max(0.0f, ground[i]) : 0.0f;
    }
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_jitter(AtlasContext* ctx, float x, float y) {
    if (!ctx || !std::isfinite(x) || !std::isfinite(y)) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->jitter[0] = std::clamp(x, -0.5f, 0.5f);
    ctx->jitter[1] = std::clamp(y, -0.5f, 0.5f);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_material_shapes(AtlasContext* ctx, uint32_t first, const AtlasMaterialShape* shapes,
                                                uint32_t count) {
    if (!ctx || first == 0 || (!shapes && count)) return ATLAS_ERR_ARG;
    if (uint64_t(first) + count > 65535) return ctx->fail(ATLAS_ERR_ARG, "material ids beyond 65534");
    ATLAS_TRY
    std::vector<std::shared_ptr<const MaterialShape>> v(count);
    for (uint32_t i = 0; i < count; i++) {
        const AtlasMaterialShape& a = shapes[i];
        if (!a.occupancy) continue;  // no shape: a plain full cube in the palette colour
        auto s = std::make_shared<MaterialShape>();
        s->mask = a.occupancy;
        s->mean = a.mean;
        s->flags = a.flags & ATLAS_SHAPE_CUTOUT;
        std::memcpy(s->faces, a.faces, sizeof s->faces);
        s->deriveAlpha();
        v[i] = std::move(s);
    }
    ctx->world.setShapes(first, v);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_pre_exposure(AtlasContext* ctx, float exposure) {
    if (!ctx || !(exposure > 0.0f) || !std::isfinite(exposure)) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->preExposure = exposure;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_material_textures(AtlasContext* ctx, uint32_t first, const uint32_t* textures,
                                                  uint32_t count) {
    if (!ctx || first == 0 || (!textures && count)) return ATLAS_ERR_ARG;
    if (uint64_t(first) + count > 65535) return ctx->fail(ATLAS_ERR_ARG, "material ids beyond 65534");
    ATLAS_TRY
    const uint32_t* has = textures + size_t(count) * 6 * 256;
    std::vector<std::shared_ptr<const MaterialTexture>> v(count);
    for (uint32_t i = 0; i < count; i++) {
        if (!has[i]) continue;
        auto t = std::make_shared<MaterialTexture>();
        std::memcpy(t->faces, textures + size_t(i) * 6 * 256, sizeof t->faces);
        v[i] = std::move(t);
    }
    ctx->texPack.submit(first, v);  // (packed off the render thread, merged a few a frame: texturepack.hpp)
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_material_offsets(AtlasContext* ctx, uint32_t first, const uint32_t* words, uint32_t count) {
    if (!ctx || first == 0 || (!words && count)) return ATLAS_ERR_ARG;
    if (uint64_t(first) + count > 65535) return ctx->fail(ATLAS_ERR_ARG, "material ids beyond 65534");
    ATLAS_TRY
    for (uint32_t m = 0; m < count; m++) ctx->bricks.setOffset(first + m, words[m]);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_material_bricks(AtlasContext* ctx, uint32_t first, uint32_t count, const AtlasBrickQuad* quads,
                                                uint32_t quadCount, const AtlasBrickSprite* sprites, uint32_t spriteCount,
                                                const uint32_t* texels, uint32_t texelCount, const float* emission) {
    if (!ctx || first == 0 || (!quads && quadCount) || (!sprites && spriteCount) || (!texels && texelCount)) return ATLAS_ERR_ARG;
    if (uint64_t(first) + count > 65535) return ctx->fail(ATLAS_ERR_ARG, "material ids beyond 65534");
    ATLAS_TRY
    // each material's quads voxelised on its own (independent: the pool's threads), then interned and set
    std::vector<std::shared_ptr<const std::vector<uint32_t>>> recs(count);
    std::vector<uint32_t> lightOf(count, 0u);
    std::vector<std::vector<std::shared_ptr<const std::vector<uint32_t>>>> frameRecs(count);
    std::vector<uint32_t> ticksOf(count, 1u);
    std::vector<float> coverOf(count, 1.0f);
    std::vector<std::shared_ptr<const std::vector<uint32_t>>> crossOf(count);
    std::vector<std::vector<std::shared_ptr<const std::vector<uint32_t>>>> crossFramesOf(count);
    std::vector<std::vector<AtlasBrickQuad>> per(count);
    for (uint32_t q = 0; q < quadCount; q++)
        if (quads[q].material < count) per[quads[q].material].push_back(quads[q]);
    std::atomic<uint32_t> next{0};
    parallelRun([&] {
        BrickVoxels v;
        for (uint32_t m; (m = next.fetch_add(1)) < count;) {
            if (per[m].empty()) continue;
            float e = emission ? std::clamp(emission[m], 0.0f, 1.0f) : 0.0f;
            // its animation: the longest of its sprites' (frames, ticks a frame), at most 64 frames
            uint32_t frames = 1, ticks = 1;
            for (const AtlasBrickQuad& q : per[m])
                if (q.sprite < spriteCount && (sprites[q.sprite].frames & 0xFFFFu) > frames) {
                    frames = std::min(64u, sprites[q.sprite].frames & 0xFFFFu);
                    ticks = std::max(1u, sprites[q.sprite].frames >> 16);
                }
            for (uint32_t k = 0; k < frames; k++) {
                if (!voxeliseBrick(per[m].data(), uint32_t(per[m].size()), m, sprites, spriteCount, texels, texelCount, e, v, k))
                    break;
                auto r = ctx->bricks.intern(encodeBrick(v));
                if (k == 0) recs[m] = r, lightOf[m] = v.lightRgb, coverOf[m] = v.coverage;
                if (frames > 1) frameRecs[m].push_back(r);
            }
            ticksOf[m] = ticks;
            // quads off the axes on up to four planes (a cross plant, a coral fan, a fire): traced as those sheets (frame 0)
            for (uint32_t k = 0; k < frames; k++) {
                std::vector<uint32_t> cr = crossRecord(per[m].data(), uint32_t(per[m].size()), m, sprites, spriteCount, texels, texelCount, k);
                if (cr.empty()) break;
                auto r = ctx->bricks.intern(std::move(cr));
                if (k == 0) crossOf[m] = r;
                if (frames > 1) crossFramesOf[m].push_back(r);
            }
        }
    }, count < 8 ? 1u : 0u);
    for (uint32_t m = 0; m < count; m++) {
        if (frameRecs[m].size() > 1) ctx->bricks.setFrames(first + m, std::move(frameRecs[m]), ticksOf[m], lightOf[m]);
        else ctx->bricks.set(first + m, recs[m], lightOf[m]);
        if (crossFramesOf[m].size() > 1) ctx->bricks.setCrossFrames(first + m, std::move(crossFramesOf[m]), ticksOf[m]);
        else ctx->bricks.setCross(first + m, crossOf[m]);
        ctx->bricks.setCoverage(first + m, recs[m] ? int(coverOf[m] * 63.0f + 0.5f) : -1);
    }
    {
        std::lock_guard<std::mutex> lock(ctx->mu);
        syncClasses(ctx);  // (the bricks vote by their coverage in the grid's coarse levels)
    }
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_surface_cards(AtlasContext* ctx, const AtlasSurfaceCard* cards, uint32_t count) {
    if (!ctx || (!cards && count)) return ATLAS_ERR_ARG;
    if (count > (1u << 24)) return ctx->fail(ATLAS_ERR_ARG, "too many surface cards");
    ATLAS_TRY
    auto set = std::make_shared<CardSet>();
    set->cards.resize(count);
    const int at[5] = {0, 256, 320, 336, 340};
    for (uint32_t i = 0; i < count; i++) {
        const AtlasSurfaceCard& a = cards[i];
        SurfaceCard& c = set->cards[i];
        c.x = a.x, c.y = a.y, c.z = a.z, c.face = a.face % 6;
        // the mip chain of the covered texels (atlas_lab's loadCards): means of what shows, the face's mean elsewhere
        // (and per texel its alpha, the top byte: what a translucent face, water, lets through; the mean of the shown
        // ones in the coarser levels)
        float rgb[341][4] = {}, w[341] = {}, mean[4] = {};
        int k = 0;
        for (int t = 0; t < 256; t++) {
            uint32_t v = a.texels[t];
            for (int ch = 0; ch < 3; ch++) rgb[t][ch] = float((v >> (16 - 8 * ch)) & 255);
            rgb[t][3] = float(v >> 24);
            w[t] = (v >> 24) ? 1.0f : 0.0f;
            if (v >> 24) {
                for (int ch = 0; ch < 4; ch++) mean[ch] += rgb[t][ch];
                k++;
            }
        }
        for (float& m : mean) m = k ? m / float(k) : 0.0f;
        for (int l = 1; l < 5; l++) {
            int n2 = 16 >> l;
            for (int v = 0; v < n2; v++)
                for (int u = 0; u < n2; u++) {
                    float acc[4] = {}, ws = 0;
                    for (int j = 0; j < 4; j++) {
                        int src = at[l - 1] + (2 * u + (j & 1)) + (2 * n2) * (2 * v + (j >> 1));
                        for (int ch = 0; ch < 4; ch++) acc[ch] += rgb[src][ch] * w[src];
                        ws += w[src];
                    }
                    int dst = at[l] + u + n2 * v;
                    w[dst] = ws * 0.25f;
                    for (int ch = 0; ch < 4; ch++) rgb[dst][ch] = ws > 0 ? acc[ch] / ws : mean[ch];
                }
        }
        for (int t = 0; t < 341; t++) {
            if (t < 256 && w[t] == 0) for (int ch = 0; ch < 4; ch++) rgb[t][ch] = mean[ch];
            uint32_t v = 0;
            for (int ch = 0; ch < 3; ch++) v |= uint32_t(std::lround(std::clamp(rgb[t][ch], 0.0f, 255.0f))) << (16 - 8 * ch);
            v |= uint32_t(std::lround(std::clamp(rgb[t][3], 0.0f, 255.0f))) << 24;
            c.mip[t] = v;
        }
    }
    ctx->world.setCards(set);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_read_sun_map(AtlasContext* ctx, float* out, uint32_t count) {
    if (!ctx || !out) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    std::vector<float> m;
    std::string err;
    if (!ctx->backend->readSunMap(m, err)) return backendFailure(ctx, err.empty() ? "no sun map yet" : err, ATLAS_ERR_STATE);
    if (m.empty()) return ctx->fail(ATLAS_ERR_STATE, "no sun map yet");
    if (count < m.size()) return ctx->fail(ATLAS_ERR_ARG, "sun map buffer too small");
    std::memcpy(out, m.data(), m.size() * 4);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_debug_read_blocks(AtlasContext* ctx, const int32_t* xyz, uint32_t count, uint32_t* out) {
    if (!ctx || !xyz || !out || !count || count > 4096) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    std::vector<uint32_t> chunk, local, at;
    for (uint32_t i = 0; i < count; i++) {
        out[i] = 0xFFFEu;
        uint32_t c, l;
        if (!ctx->voxgrid.locate(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], c, l)) continue;
        chunk.push_back(c);
        local.push_back(l);
        at.push_back(i);
    }
    if (chunk.empty()) return ATLAS_OK;
    std::vector<uint32_t> ids;
    std::string err;
    if (!ctx->backend->readVoxels(chunk, local, ids, err)) return backendFailure(ctx, err.empty() ? "no readback" : err, ATLAS_ERR_STATE);
    for (size_t j = 0; j < at.size(); j++) out[at[j]] = ids[j];
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_light_group(AtlasContext* ctx, uint32_t group, const AtlasLight* lights, uint32_t count) {
    if (!ctx || (!lights && count)) return ATLAS_ERR_ARG;
    if (count > 1u << 20) return ctx->fail(ATLAS_ERR_ARG, "light group too large");
    ctx->lights.setGroup(group, lights, count);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_light_budget(AtlasContext* ctx, uint32_t maxPerFrame, float clusterDistance) {
    if (!ctx || !(clusterDistance >= 0.0f)) return ATLAS_ERR_ARG;
    ctx->lights.setBudget(maxPerFrame, clusterDistance);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_set_transitions(AtlasContext* ctx, float fadeSeconds) {
    if (!ctx || !(fadeSeconds >= 0.0f && fadeSeconds <= 10.0f)) return ATLAS_ERR_ARG;
    ctx->world.fadeMs = uint32_t(fadeSeconds * 1000.0f + 0.5f);
    return ATLAS_OK;
}

ATLAS_API int32_t atlas_has_tile(AtlasContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ) {
    if (!ctx || lod < 0 || lod >= MAX_LODS) return 0;
    try {
        return ctx->world.hasTile(lod, tileX, tileZ) ? 1 : 0;
    } catch (...) {
        return 0;
    }
}

ATLAS_API float atlas_preview_height(AtlasContext* ctx, double x, double z) {
    if (!ctx || !ctx->preview) return NAN;
    return ctx->preview->height(x, z);
}

ATLAS_API AtlasStatus atlas_memory_info(AtlasContext* ctx, AtlasMemoryInfo* out) {
    if (!ctx || !out) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    *out = AtlasMemoryInfo{};
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    MemoryInfo m;
    std::string err;
    if (!ctx->backend->memoryInfo(m, err)) return ctx->fail(ATLAS_ERR_UNSUPPORTED, err);
    out->deviceLocalBytes = m.deviceLocalBytes;
    out->deviceLocalBudget = m.deviceLocalBudget;
    out->deviceLocalUsage = m.deviceLocalUsage;
    out->hostHeapBytes = m.hostHeapBytes;
    out->atlasBytes = m.atlasBytes;
    out->integrated = m.integrated ? 1u : 0u;
    out->flags = m.budgetExt ? ATLAS_MEMORY_BUDGET_EXT : 0u;
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_clipmap(AtlasContext* ctx, uint32_t lodCount) {
    if (!ctx || lodCount < 1 || lodCount > 7u) return ATLAS_ERR_ARG;  // 7: the default (and the most the mod streams)
    ctx->world.lodCount = int(lodCount);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_debug_section(AtlasContext* ctx, int32_t sx, int32_t sy, int32_t sz, uint16_t* out, uint32_t* tier) {
    if (!ctx || !out || !tier) return ATLAS_ERR_ARG;
    ATLAS_TRY
    World::SectionSrc s = ctx->world.sectionSource(sx, sy, sz);
    *tier = s.raw ? 1u : s.packed ? 2u : 0u;
    if (s.raw) std::copy(s.raw->mats.begin(), s.raw->mats.end(), out);
    else if (s.packed) unpackSection(s.packed->data(), out);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_set_texture_ticks(AtlasContext* ctx, uint32_t ticks) {
    if (!ctx) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->textureTicks = ticks == 0xFFFFFFFFu ? 1u : ticks + 1u;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_debug_ablate(AtlasContext* ctx, uint32_t bits) {
    if (!ctx) return ATLAS_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->ablateExtra = bits;
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_debug_material(AtlasContext* ctx, uint32_t mat, uint32_t out[6]) {
    if (!ctx || !out) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    // what the kernels' tables say of material `mat`: its brick word (0 none, bit 30 sheets, bit 31 frames), its texture
    // slot (0 none), its class, the brick table's length, whether it has a voxel brick / sheets in the store
    std::vector<uint32_t> packed;
    uint64_t rev = 0;
    ctx->bricks.pack(~0ull, true, ctx->materialLights, packed, rev);
    out[0] = mat < packed[0] ? packed[1 + mat] : 0u;
    out[1] = mat + 1 < ctx->texturesPacked.size() && mat < ctx->texturesPacked[0] ? ctx->texturesPacked[1 + mat] : 0u;
    out[2] = mat < ctx->palette.size() ? uint32_t(ctx->palette[mat].emisClass[1]) : 255u;
    out[3] = packed[0];
    uint32_t v = 0, sh = 0, t = 0;
    ctx->bricks.stats(v, sh, t);
    out[4] = v;
    out[5] = sh;
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_debug_gpu_sections(AtlasContext* ctx, uint32_t count, const int32_t* pos, const uint16_t* ids,
                                               const uint32_t* uniform, uint8_t* accepted) {
    if (!ctx || !pos || (count && !ids)) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    if (!ctx->backend->onDevice()) return ctx->fail(ATLAS_ERR_UNSUPPORTED, "atlas_debug_gpu_sections: Vulkan only");
    uint64_t buf = ctx->backend->debugDeviceBuffer(ids, size_t(count) * 8192);
    if (!buf) return ctx->fail(ATLAS_ERR_UNSUPPORTED, "atlas_debug_gpu_sections: no device buffer");
    std::vector<uint64_t> offs(count);
    for (uint32_t i = 0; i < count; i++) offs[i] = uint64_t(i) * 8192;
    ctx->voxgrid.ingestGpu(buf, offs.data(), pos, count, accepted, uniform);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_debug_coarse_chunks(AtlasContext* ctx, int32_t level, uint32_t count, const int32_t* pos,
                                                const uint32_t* kinds, const uint16_t* ids, uint8_t* accepted) {
    if (!ctx || !pos || !kinds || (count && !ids)) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    if (!ctx->backend->onDevice()) return ctx->fail(ATLAS_ERR_UNSUPPORTED, "atlas_debug_coarse_chunks: Vulkan only");
    uint64_t buf = ctx->backend->debugDeviceBuffer(ids, size_t(count) * 8192);
    if (!buf) return ctx->fail(ATLAS_ERR_UNSUPPORTED, "atlas_debug_coarse_chunks: no device buffer");
    std::vector<uint64_t> offs(count);
    for (uint32_t i = 0; i < count; i++) offs[i] = uint64_t(i) * 8192;
    ctx->voxgrid.ingestCoarseGpu(level, buf, offs.data(), pos, kinds, count, accepted);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_debug_fault(AtlasContext* ctx, uint32_t fault, uint32_t afterFrames) {
    if (!ctx || fault > ATLAS_FAULT_TIMEOUT) return ATLAS_ERR_ARG;
    ATLAS_TRY
    std::lock_guard<std::mutex> lock(ctx->mu);
    Fault f = fault == ATLAS_FAULT_DEVICE_LOST    ? Fault::DeviceLost
              : fault == ATLAS_FAULT_OUT_OF_MEMORY ? Fault::OutOfMemory
              : fault == ATLAS_FAULT_TIMEOUT       ? Fault::Timeout
                                                    : Fault::None;
    ctx->backend->injectFault(f, afterFrames);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

}  // extern "C"

// (gen_engine.cpp, the client generator on the context's own device) the context's backend and grid
namespace atlas {
Backend* contextBackend(AtlasContext* ctx) { return ctx ? ctx->backend.get() : nullptr; }
VoxGrid* contextGrid(AtlasContext* ctx) { return ctx ? &ctx->voxgrid : nullptr; }
World* contextWorld(AtlasContext* ctx) { return ctx ? &ctx->world : nullptr; }
}  // namespace atlas
