#include <cstdio>
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

#include "aether/aether.h"
#include "backend.hpp"
#include "lights.hpp"
#include "preview_source.hpp"
#include "world.hpp"

#ifndef AETHER_VERSION_STRING
#define AETHER_VERSION_STRING "dev"
#endif

using namespace aether;

// The Java binding (com.skycraft.aether.AetherNative) declares these layouts by hand; any change is an ABI bump.
static_assert(sizeof(AetherMaterial) == 24);
static_assert(sizeof(AetherColumnTile) == 64 && offsetof(AetherColumnTile, surface) == 16);
static_assert(sizeof(AetherFrame) == 144 && offsetof(AetherFrame, invViewProj) == 24 && offsetof(AetherFrame, width) == 88 &&
              offsetof(AetherFrame, sunDir) == 108 && offsetof(AetherFrame, flags) == 128 &&
              offsetof(AetherFrame, nearDepth) == 136);
static_assert(sizeof(AetherFrameOutput) == 32 && offsetof(AetherFrameOutput, colorLinear) == 24);
static_assert(sizeof(AetherWeather) == 40 && offsetof(AetherWeather, rainFactor) == 32);
static_assert(sizeof(AetherActor) == 48 && offsetof(AetherActor, kind) == 36);
static_assert(sizeof(AetherGlFrameIO) == 96 && offsetof(AetherGlFrameIO, nearInvProjection) == 16 &&
              offsetof(AetherGlFrameIO, dstColor) == 80);
static_assert(sizeof(AetherLight) == 40 && offsetof(AetherLight, radius) == 24 && offsetof(AetherLight, kind) == 36);
static_assert(sizeof(AetherStats) == 56 && offsetof(AetherStats, nodeBytes) == 8 && offsetof(AetherStats, raySteps) == 48);

struct AetherContext {
    std::unique_ptr<Backend> backend;
    World world;
    std::vector<MaterialGpu> palette;
    std::vector<uint32_t> weather;  // near field, near dyn, far field, far dyn (128*128 each)
    std::vector<uint32_t> cloudNoise = std::vector<uint32_t>(64 * 64 * 64, 0);
    float rainFactor = 0;
    std::vector<AetherActor> actors;
    Lights lights;
    std::unique_ptr<PreviewSource> preview;
    FrameTargets targets;
    AetherStats stats{};
    std::string error;
    std::string info;  // aether_info's last answer
    std::mutex mu;
    uint64_t stagedRevision = UINT64_MAX;
    // the last frame, for temporal cloud reprojection
    bool haveHistory = false;
    float prevInvVP[16] = {};
    double prevCam[3] = {};
    uint32_t prevW = 0, prevH = 0, prevCloudFlags = 0;
    uint32_t frameIndex = 0;
    // the sun map (aether_set_sun_map)
    uint32_t sunMapSize = 0;
    float sunMapHalf = 2048.0f;
    double sunMapCentre[3] = {1e300, 0, 1e300};
    bool sunMapFull = true;
    uint32_t sunMapCursor = 0;

    AetherStatus fail(AetherStatus s, std::string msg) {
        error = std::move(msg);
        return s;
    }
};

namespace {

// why the last aether_create on this thread failed (there is no context to hold the message)
std::string& createError() {
    thread_local std::string e;
    return e;
}

MaterialGpu toGpu(const AetherMaterial& m) {
    MaterialGpu g{};
    g.albedoRough[0] = m.albedo[0];
    g.albedoRough[1] = m.albedo[1];
    g.albedoRough[2] = m.albedo[2];
    g.albedoRough[3] = m.roughness;
    g.emisClass[0] = m.emissive;
    g.emisClass[1] = float(m.cls);
    return g;
}

void setPalette(AetherContext* ctx, const AetherMaterial* m, uint32_t n) {
    ctx->palette.assign(1, MaterialGpu{});  // id 0 = air
    uint16_t water = 0;
    for (uint32_t i = 0; i < n; i++) {
        ctx->palette.push_back(toGpu(m[i]));
        if (!water && m[i].cls == AETHER_CLASS_WATER) water = uint16_t(i + 1);
    }
    if (water) ctx->world.waterMat = water;
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

AETHER_API uint32_t aether_abi_version(void) { return AETHER_ABI_VERSION; }

#define AETHER_STR2(x) #x
#define AETHER_STR(x) AETHER_STR2(x)
#if defined(_WIN32)
#define AETHER_PLATFORM "windows"
#elif defined(__linux__)
#define AETHER_PLATFORM "linux"
#else
#define AETHER_PLATFORM "unknown"
#endif
#if defined(_MSC_VER)
#define AETHER_COMPILER "msvc " AETHER_STR(_MSC_VER)
#elif defined(__clang__)
#define AETHER_COMPILER "clang " __clang_version__
#elif defined(__GNUC__)
#define AETHER_COMPILER "gcc " __VERSION__
#else
#define AETHER_COMPILER "unknown compiler"
#endif

AETHER_API const char* aether_build_info(void) {
    static const std::string s = [] {
        char abi[32];
        std::snprintf(abi, sizeof(abi), "%u.%u.%u", (AETHER_ABI_VERSION >> 16) & 255u, (AETHER_ABI_VERSION >> 8) & 255u,
                      AETHER_ABI_VERSION & 255u);
        return std::string("aether_core " AETHER_VERSION_STRING " (abi ") + abi +
               "; slang far kernel; " AETHER_PLATFORM ", " AETHER_COMPILER "; " __DATE__ ")";
    }();
    return s.c_str();
}

AETHER_API const char* aether_info(AetherContext* ctx) {
    if (!ctx) return "{}";
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->info = ctx->backend->info();
    return ctx->info.c_str();
}

AETHER_API AetherStatus aether_create(AetherBackend backend, AetherContext** out) {
    if (!out) return AETHER_ERR_ARG;
    auto ctx = std::make_unique<AetherContext>();
    if (backend == AETHER_BACKEND_VULKAN) {
        std::string err;
        ctx->backend = makeVulkanBackend(err);
        if (!ctx->backend) {
            *out = nullptr;
            createError() = "Vulkan backend unavailable: " + err;  // aether_last_error(NULL) reports it
            return AETHER_ERR_GPU;
        }
    } else {
        ctx->backend = makeCpuBackend();
    }
    auto pal = previewPalette();
    setPalette(ctx.get(), pal.data(), uint32_t(pal.size()));
    ctx->weather.assign(4 * 128 * 128, 0);
    *out = ctx.release();
    return AETHER_OK;
}

AETHER_API void aether_destroy(AetherContext* ctx) { delete ctx; }

AETHER_API const char* aether_last_error(AetherContext* ctx) {
    if (ctx) return ctx->error.c_str();
    return createError().empty() ? "null context" : createError().c_str();
}

AETHER_API AetherStatus aether_set_palette(AetherContext* ctx, const AetherMaterial* m, uint32_t count) {
    if (!ctx || (!m && count)) return AETHER_ERR_ARG;
    if (count > 65534) return ctx->fail(AETHER_ERR_ARG, "palette too large");
    std::lock_guard<std::mutex> lock(ctx->mu);
    setPalette(ctx, m, count);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_upload_column_tile(AetherContext* ctx, const AetherColumnTile* t) {
    if (!ctx || !t || !t->surface || !t->water || !t->topMat || !t->fillMat) return AETHER_ERR_ARG;
    if (t->lod < 0 || t->lod >= MAX_LODS) return ctx->fail(AETHER_ERR_ARG, "lod out of range");
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
    return AETHER_OK;
}

AETHER_API AetherStatus aether_upload_section(AetherContext* ctx, int32_t sx, int32_t sy, int32_t sz, const uint16_t* mats) {
    if (!ctx || !mats) return AETHER_ERR_ARG;
    ctx->world.putSection(sx, sy, sz, mats);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_notify_block(AetherContext* ctx, int32_t x, int32_t y, int32_t z, uint16_t mat) {
    if (!ctx) return AETHER_ERR_ARG;
    if (y < WORLD_MIN_Y || y >= WORLD_TOP_Y) return AETHER_OK;
    ctx->world.editBlock(x, y, z, mat);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_weather(AetherContext* ctx, const AetherWeather* w) {
    if (!ctx || !w) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    const uint32_t* src[4] = {w->nearField, w->nearDyn, w->farField, w->farDyn};
    for (int i = 0; i < 4; i++) {
        if (src[i]) std::memcpy(ctx->weather.data() + size_t(i) * 128 * 128, src[i], 128 * 128 * 4);
    }
    ctx->rainFactor = w->rainFactor;
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_cloud_noise(AetherContext* ctx, const uint32_t* rgba) {
    if (!ctx || !rgba) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    std::vector<uint32_t> v(rgba, rgba + 64 * 64 * 64);
    ctx->cloudNoise.swap(v);  // new storage: the Vulkan backend re-uploads when the pointer changes
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_actors(AetherContext* ctx, const AetherActor* a, uint32_t count) {
    if (!ctx || (!a && count)) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->actors.assign(a, a + count);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_update(AetherContext* ctx, const double centre[3], double budgetMs) {
    if (!ctx || !centre) return AETHER_ERR_ARG;
    double ms = 0;
    ctx->world.update(centre, budgetMs, &ms);
    ctx->stats.lastBuildMs = ms;
    // copy a new revision to the GPU here (the game calls update off the render thread), not in the next frame
    auto snap = ctx->world.packed();
    if (snap->lodCount && snap->revision != ctx->stagedRevision) {
        if (ctx->backend->stageScene(*snap)) ctx->stagedRevision = snap->revision;
    }
    return AETHER_OK;
}

static AetherStatus buildInputs(AetherContext* ctx, const AetherFrame* f, FrameInputs& in);
static void rememberFrame(AetherContext* ctx, const AetherFrame* f);

AETHER_API AetherStatus aether_render(AetherContext* ctx, const AetherFrame* f, const AetherFrameOutput* out) {
    if (!ctx || !f || !out || f->width == 0 || f->height == 0) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    FrameInputs in{};
    AetherStatus st = buildInputs(ctx, f, in);
    if (st != AETHER_OK) return st;
    double ms = 0;
    std::string err;
    if (!ctx->backend->render(in, ctx->targets, &ms, err)) return ctx->fail(AETHER_ERR_GPU, err);
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
    return AETHER_OK;
}

AETHER_API AetherStatus aether_gl_attach(AetherContext* ctx, uint32_t w, uint32_t h, AetherGlTargets* out) {
    if (!ctx || !out || w == 0 || h == 0) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    GlTargets t;
    std::string err;
    if (!ctx->backend->glAttach(w, h, t, err)) return ctx->fail(AETHER_ERR_UNSUPPORTED, err);
    *out = AetherGlTargets{t.color, t.normal, t.depth, t.nearDepth, t.width, t.height};
    return AETHER_OK;
}

AETHER_API AetherStatus aether_gl_render(AetherContext* ctx, const AetherFrame* f) {
    return aether_gl_render_io(ctx, f, nullptr);
}

AETHER_API AetherStatus aether_gl_render_io(AetherContext* ctx, const AetherFrame* f, const AetherGlFrameIO* io) {
    if (!ctx || !f || f->width == 0 || f->height == 0) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    FrameInputs in{};
    AetherStatus st = buildInputs(ctx, f, in);
    if (st != AETHER_OK) return st;
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
    if (!ctx->backend->glRender(in, io ? &g : nullptr, &ms, err)) return ctx->fail(AETHER_ERR_GPU, err);
    rememberFrame(ctx, f);
    ctx->stats.lastFrameMs = ms;
    return AETHER_OK;
}

AETHER_API void aether_gl_detach(AetherContext* ctx) {
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->backend->glDetach();
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
static void rememberFrame(AetherContext* ctx, const AetherFrame* f) {
    std::memcpy(ctx->prevInvVP, f->invViewProj, sizeof(ctx->prevInvVP));
    for (int i = 0; i < 3; i++) ctx->prevCam[i] = f->cameraPos[i];
    ctx->prevW = f->width;
    ctx->prevH = f->height;
    ctx->prevCloudFlags = f->flags & (AETHER_FRAME_CLOUDS | AETHER_FRAME_CLOUDS_QUARTER);
    ctx->haveHistory = true;
    ctx->frameIndex++;
}

static AetherStatus buildInputs(AetherContext* ctx, const AetherFrame* f, FrameInputs& in) {
    in.sceneHold = ctx->world.packed();
    in.scene = in.sceneHold.get();
    if (in.scene->lodCount == 0) return ctx->fail(AETHER_ERR_STATE, "call aether_update before aether_render");
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
    uint32_t cloudFlags = f->flags & (AETHER_FRAME_CLOUDS | AETHER_FRAME_CLOUDS_QUARTER);
    bool valid = ctx->haveHistory && ctx->prevW == f->width && ctx->prevH == f->height &&
                 ctx->prevCloudFlags == cloudFlags && (cloudFlags & AETHER_FRAME_CLOUDS);
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
        for (int i = 0; i < 3; i++) L[i] = day ? g.sunDir[i] : -g.sunDir[i];
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
    for (const AetherActor& a : ctx->actors) {
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
    in.wantNearDepth = (f->flags & AETHER_FRAME_NEAR_DEPTH) != 0;
    in.nearDepth = in.wantNearDepth ? f->nearDepth : nullptr;
    if ((f->flags & AETHER_FRAME_NEAR_DEPTH) && !f->nearDepth) g.dims[2] &= ~AETHER_FRAME_NEAR_DEPTH;  // GL path sets it back
    return AETHER_OK;
}

AETHER_API AetherStatus aether_get_stats(AetherContext* ctx, AetherStats* out) {
    if (!ctx || !out) return AETHER_ERR_ARG;
    ctx->stats.regions = uint32_t(ctx->world.regionCount());
    ctx->stats.nodeBytes = ctx->world.nodeBytes();
    ctx->stats.leafBytes = ctx->world.leafBytes();
    ctx->stats.materialBytes = ctx->world.materialBytes();
    *out = ctx->stats;
    return AETHER_OK;
}

AETHER_API AetherStatus aether_preview_load_raster(AetherContext* ctx, const char* path) {
    if (!ctx || !path) return AETHER_ERR_ARG;
    auto p = std::make_unique<PreviewSource>();
    std::string err;
    if (!p->load(path, err)) return ctx->fail(AETHER_ERR_IO, err);
    ctx->preview = std::move(p);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_preview_generate(AetherContext* ctx, int32_t lod, int32_t x0, int32_t z0, int32_t x1, int32_t z1) {
    if (!ctx || lod < 0 || lod >= MAX_LODS) return AETHER_ERR_ARG;
    if (!ctx->preview) return ctx->fail(AETHER_ERR_STATE, "no preview raster loaded");
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
    unsigned n = std::max(1u, std::thread::hardware_concurrency());
    std::vector<std::thread> pool;
    for (unsigned i = 0; i + 1 < n; i++) pool.emplace_back(worker);
    worker();
    for (auto& t : pool) t.join();
    return AETHER_OK;
}

AETHER_API AetherStatus aether_upload_column_heights(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                     const float* surface, const float* water) {
    if (!ctx || lod < 0 || lod >= MAX_LODS || !surface || !water) return AETHER_ERR_ARG;
    if (!ctx->preview) return ctx->fail(AETHER_ERR_STATE, "no preview raster loaded (it dresses the columns)");
    ctx->world.putTile(ctx->preview->dressTile(lod, tileX, tileZ, surface, water));
    return AETHER_OK;
}

AETHER_API AetherStatus aether_upload_column_heights2(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                      const float* surface, const float* water, const uint16_t* grassMat,
                                                      const uint16_t* foliageMat) {
    if (!ctx || lod < 0 || lod >= MAX_LODS || !surface || !water) return AETHER_ERR_ARG;
    if (!ctx->preview) return ctx->fail(AETHER_ERR_STATE, "no preview raster loaded (it dresses the columns)");
    ctx->world.putTile(ctx->preview->dressTile(lod, tileX, tileZ, surface, water, grassMat, foliageMat));
    return AETHER_OK;
}

AETHER_API AetherStatus aether_upload_column_structures(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                        const float* wallTop, const float* roofTop,
                                                        const uint16_t* wallMat, const uint16_t* roofMat) {
    return aether_upload_column_structures2(ctx, lod, tileX, tileZ, wallTop, roofTop, wallMat, roofMat, nullptr);
}

AETHER_API AetherStatus aether_upload_column_structures2(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ,
                                                         const float* wallTop, const float* roofTop,
                                                         const uint16_t* wallMat, const uint16_t* roofMat,
                                                         const float* base) {
    if (!ctx || lod < 0 || lod >= MAX_LODS) return AETHER_ERR_ARG;
    if (!wallTop) {
        ctx->world.putStructures(lod, tileX, tileZ, nullptr);
        return AETHER_OK;
    }
    if (!roofTop || !wallMat || !roofMat) return AETHER_ERR_ARG;
    size_t n = size_t(TILE) * TILE;
    auto s = std::make_shared<Structures>();
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
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_materials(AetherContext* ctx, uint32_t first, const AetherMaterial* m, uint32_t count) {
    if (!ctx || first == 0 || (!m && count)) return AETHER_ERR_ARG;
    if (uint64_t(first) + count > 65535) return ctx->fail(AETHER_ERR_ARG, "material ids beyond 65534");
    std::lock_guard<std::mutex> lock(ctx->mu);
    AetherMaterial grey{{0.3f, 0.3f, 0.3f}, 0.9f, 0.0f, AETHER_CLASS_TERRAIN};
    if (ctx->palette.size() < first + count) ctx->palette.resize(first + count, toGpu(grey));
    for (uint32_t i = 0; i < count; i++) ctx->palette[first + i] = toGpu(m[i]);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_sun_map(AetherContext* ctx, uint32_t size, float halfExtent) {
    if (!ctx || (size && (size < 64 || size > 4096)) || (size && !(halfExtent >= 256.0f && halfExtent <= 16384.0f)))
        return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    ctx->sunMapSize = size;
    ctx->sunMapHalf = halfExtent;
    ctx->sunMapFull = true;
    return AETHER_OK;
}

AETHER_API AetherStatus aether_read_sun_map(AetherContext* ctx, float* out, uint32_t count) {
    if (!ctx || !out) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    std::vector<float> m;
    std::string err;
    if (!ctx->backend->readSunMap(m, err) || m.empty()) return ctx->fail(AETHER_ERR_STATE, err.empty() ? "no sun map yet" : err);
    if (count < m.size()) return ctx->fail(AETHER_ERR_ARG, "sun map buffer too small");
    std::memcpy(out, m.data(), m.size() * 4);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_light_group(AetherContext* ctx, uint32_t group, const AetherLight* lights, uint32_t count) {
    if (!ctx || (!lights && count)) return AETHER_ERR_ARG;
    if (count > 1u << 20) return ctx->fail(AETHER_ERR_ARG, "light group too large");
    ctx->lights.setGroup(group, lights, count);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_light_budget(AetherContext* ctx, uint32_t maxPerFrame, float clusterDistance) {
    if (!ctx || !(clusterDistance >= 0.0f)) return AETHER_ERR_ARG;
    ctx->lights.setBudget(maxPerFrame, clusterDistance);
    return AETHER_OK;
}

AETHER_API AetherStatus aether_set_transitions(AetherContext* ctx, float fadeSeconds) {
    if (!ctx || !(fadeSeconds >= 0.0f && fadeSeconds <= 10.0f)) return AETHER_ERR_ARG;
    ctx->world.fadeMs = uint32_t(fadeSeconds * 1000.0f + 0.5f);
    return AETHER_OK;
}

AETHER_API int32_t aether_has_tile(AetherContext* ctx, int32_t lod, int32_t tileX, int32_t tileZ) {
    if (!ctx || lod < 0 || lod >= MAX_LODS) return 0;
    return ctx->world.hasTile(lod, tileX, tileZ) ? 1 : 0;
}

AETHER_API float aether_preview_height(AetherContext* ctx, double x, double z) {
    if (!ctx || !ctx->preview) return NAN;
    return ctx->preview->height(x, z);
}

}  // extern "C"
