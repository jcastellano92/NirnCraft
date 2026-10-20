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
#include "backend.hpp"
#include "lights.hpp"
#include "parallel.hpp"
#include "preview_source.hpp"
#include "world.hpp"

#ifndef ATLAS_VERSION_STRING
#define ATLAS_VERSION_STRING "dev"
#endif

using namespace atlas;

// The Java binding (com.nirncraft.atlas.AtlasNative) declares these layouts by hand; any change is an ABI bump.
static_assert(sizeof(AtlasMaterial) == 24);
static_assert(sizeof(AtlasColumnTile) == 64 && offsetof(AtlasColumnTile, surface) == 16);
static_assert(sizeof(AtlasFrame) == 144 && offsetof(AtlasFrame, invViewProj) == 24 && offsetof(AtlasFrame, width) == 88 &&
              offsetof(AtlasFrame, sunDir) == 108 && offsetof(AtlasFrame, flags) == 128 &&
              offsetof(AtlasFrame, nearDepth) == 136);
static_assert(sizeof(AtlasFrameOutput) == 32 && offsetof(AtlasFrameOutput, colorLinear) == 24);
static_assert(sizeof(AtlasWeather) == 40 && offsetof(AtlasWeather, rainFactor) == 32);
static_assert(sizeof(AtlasActor) == 48 && offsetof(AtlasActor, kind) == 36);
static_assert(sizeof(AtlasGlFrameIO) == 96 && offsetof(AtlasGlFrameIO, nearInvProjection) == 16 &&
              offsetof(AtlasGlFrameIO, dstColor) == 80);
static_assert(sizeof(AtlasLight) == 40 && offsetof(AtlasLight, radius) == 24 && offsetof(AtlasLight, kind) == 36);
static_assert(sizeof(AtlasStats) == 56 && offsetof(AtlasStats, nodeBytes) == 8 && offsetof(AtlasStats, raySteps) == 48);
static_assert(sizeof(AtlasSurfaceCard) == 1552 && offsetof(AtlasSurfaceCard, texels) == 16);
static_assert(sizeof(AtlasMaterialShape) == 400 && offsetof(AtlasMaterialShape, mean) == 8 &&
              offsetof(AtlasMaterialShape, faces) == 16);

struct AtlasContext {
    std::unique_ptr<Backend> backend;
    World world;
    std::vector<MaterialGpu> palette;
    std::vector<uint32_t> weather;  // near field, near dyn, far field, far dyn (128*128 each)
    std::vector<uint32_t> cloudNoise = std::vector<uint32_t>(64 * 64 * 64, 0);
    float rainFactor = 0;
    std::vector<AtlasActor> actors;
    Lights lights;
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
    // the host path's near depths with its sky marked all-sky (FRAME_FILL_NEAR)
    std::vector<float> nearDepthHost;
    // the host's primary-ray jitter in pixels (atlas_set_jitter)
    float jitter[2] = {0, 0};

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
    ctx->world.setClasses(std::move(cls));
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
        std::lock_guard<std::mutex> lock(ctx->mu);
        ctx->info = ctx->backend->info();
        if (ctx->backend->lost() && !ctx->info.empty() && ctx->info.back() == '}')
            ctx->info.insert(ctx->info.size() - 1, ",\"deviceLost\":true");
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
        }
        return ctx->info.c_str();
    } catch (...) {
        return "{}";
    }
}

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
    // ATLAS_GROUP_LODS=<n>: only the n finest levels get groups (the rest keep the old path); a knob for measurements
    if (const char* gl = std::getenv("ATLAS_GROUP_LODS"))
        ctx->world.groupLods = std::clamp(std::atoi(gl), 0, MAX_LODS);
    // ATLAS_SECTION_KEEP=<n>: keep real sections n LOD-0 regions either side (tools: an element seen from 64 km and
    // back has no server to send it again; 256 regions is 64 km)
    if (const char* sk = std::getenv("ATLAS_SECTION_KEEP")) ctx->world.sectionKeep = std::clamp(std::atoi(sk), 0, 1024);
    auto pal = previewPalette();
    setPalette(ctx.get(), pal.data(), uint32_t(pal.size()));
    ctx->weather.assign(4 * 128 * 128, 0);
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
    ctx->world.putSection(sx, sy, sz, mats);
    return ATLAS_OK;
    ATLAS_CATCH(ctx)
}

ATLAS_API AtlasStatus atlas_notify_block(AtlasContext* ctx, int32_t x, int32_t y, int32_t z, uint16_t mat) {
    if (!ctx) return ATLAS_ERR_ARG;
    if (y < WORLD_MIN_Y || y >= WORLD_TOP_Y) return ATLAS_OK;
    ATLAS_TRY
    ctx->world.editBlock(x, y, z, mat);
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
    double ms = 0;
    ctx->world.update(centre, budgetMs, &ms);
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
    double ms = 0;
    std::string err;
    if (!ctx->backend->render(in, ctx->targets, &ms, err)) return backendFailure(ctx, err, ATLAS_ERR_GPU);
    rememberFrame(ctx, f);
    size_t n = size_t(f->width) * f->height;
    if (out->colorLinear) std::memcpy(out->colorLinear, ctx->targets.color.data(), n * 16);
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
    std::lock_guard<std::mutex> lock(ctx->mu);
    AtlasStatus dead;
    if (deadDevice(ctx, dead)) return dead;
    FrameInputs in{};
    AtlasStatus st = buildInputs(ctx, f, in);
    if (st != ATLAS_OK) return st;
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
    }
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
    ctx->haveHistory = true;
    ctx->frameIndex++;
}

static AtlasStatus buildInputs(AtlasContext* ctx, const AtlasFrame* f, FrameInputs& in) {
    in.sceneHold = ctx->world.packed();
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
    g.jitter[0] = f->width > 0 ? ctx->jitter[0] * 2.0f / float(f->width) : 0.0f;
    g.jitter[1] = f->height > 0 ? ctx->jitter[1] * 2.0f / float(f->height) : 0.0f;
    g.jitter[2] = ctx->jitter[0] != 0.0f || ctx->jitter[1] != 0.0f ? 1.0f : 0.0f;
    // ATLAS_ABLATE=<bits>: measurements only, leave parts of the shading out (1 AO, 2 smooth normals, 4 shadows, 8 far
    // lights, 16 water reflections, 32 the trace itself after the first hit's distance; the groups: 64 draw every node
    // whole, 128 a drawn box without its texel walk, 256 never draw a node whole for its error)
    {
        static const uint32_t ablate = [] {
            const char* v = std::getenv("ATLAS_ABLATE");
            return v ? uint32_t(std::strtoul(v, nullptr, 0)) : 0u;
        }();
        std::memcpy(&g.jitter[3], &ablate, 4);
    }
    float a[3], b[3];
    pixelDir(f->invViewProj, f->width * 0.5f, f->height * 0.5f, f->width, f->height, a);
    pixelDir(f->invViewProj, f->width * 0.5f + 1, f->height * 0.5f, f->width, f->height, b);
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
    // temporal clouds: last frame's view-projection (inverted back from its inverse) and camera, if comparable
    uint32_t cloudFlags = f->flags & (ATLAS_FRAME_CLOUDS | ATLAS_FRAME_CLOUDS_QUARTER);
    bool valid = ctx->haveHistory && ctx->prevW == f->width && ctx->prevH == f->height &&
                 ctx->prevCloudFlags == cloudFlags && (cloudFlags & ATLAS_FRAME_CLOUDS);
    if (valid && invert4(ctx->prevInvVP, g.prevViewProj)) {
        for (int i = 0; i < 3; i++) g.prevCam[i] = float(ctx->prevCam[i] - double(g.camInt[i]));
        g.temporal[1] = 1;
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
            std::memcpy(lv.viewProj, vp, sizeof(vp));
            lv.width = f->width;
            lv.height = f->height;
            lv.pixelAngle = g.params[3];
            lv.nearEnd = f->nearFieldEnd;
            lv.maxDist = f->maxDistance;
            lv.planetRadius = f->planetRadius;
            lv.sunY = g.sunDir[1];
            double h = std::max(f->cameraPos[1] - 63.0, 0.0);
            lv.hazeDensity = float(std::exp(-h / 1200.0) * (1.0 / 22000.0) * (1.0 + 3.0 * ctx->rainFactor));
            g.weatherParams[1] = float(ctx->lights.frame(lv, in.actors));
        }
    }
    if (in.actors.empty()) in.actors.push_back(ActorGpu{});
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
        float rgb[341][3] = {}, w[341] = {}, mean[3] = {};
        int k = 0;
        for (int t = 0; t < 256; t++) {
            uint32_t v = a.texels[t];
            for (int ch = 0; ch < 3; ch++) rgb[t][ch] = float((v >> (16 - 8 * ch)) & 255);
            w[t] = (v >> 24) ? 1.0f : 0.0f;
            if (v >> 24) {
                for (int ch = 0; ch < 3; ch++) mean[ch] += rgb[t][ch];
                k++;
            }
        }
        for (float& m : mean) m = k ? m / float(k) : 0.0f;
        for (int l = 1; l < 5; l++) {
            int n2 = 16 >> l;
            for (int v = 0; v < n2; v++)
                for (int u = 0; u < n2; u++) {
                    float acc[3] = {}, ws = 0;
                    for (int j = 0; j < 4; j++) {
                        int src = at[l - 1] + (2 * u + (j & 1)) + (2 * n2) * (2 * v + (j >> 1));
                        for (int ch = 0; ch < 3; ch++) acc[ch] += rgb[src][ch] * w[src];
                        ws += w[src];
                    }
                    int dst = at[l] + u + n2 * v;
                    w[dst] = ws * 0.25f;
                    for (int ch = 0; ch < 3; ch++) rgb[dst][ch] = ws > 0 ? acc[ch] / ws : mean[ch];
                }
        }
        for (int t = 0; t < 341; t++) {
            if (t < 256 && w[t] == 0) for (int ch = 0; ch < 3; ch++) rgb[t][ch] = mean[ch];
            uint32_t v = 0;
            for (int ch = 0; ch < 3; ch++) v |= uint32_t(std::lround(std::clamp(rgb[t][ch], 0.0f, 255.0f))) << (16 - 8 * ch);
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
