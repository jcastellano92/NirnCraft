#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "aether/aether.h"
#include "backend.hpp"
#include "preview_source.hpp"
#include "world.hpp"

#ifndef AETHER_VERSION_STRING
#define AETHER_VERSION_STRING "dev"
#endif

using namespace aether;

struct AetherContext {
    std::unique_ptr<Backend> backend;
    World world;
    std::vector<MaterialGpu> palette;
    std::vector<uint32_t> weather;  // near field, near dyn, far field, far dyn (128*128 each)
    std::vector<uint32_t> cloudNoise = std::vector<uint32_t>(64 * 64 * 64, 0);
    float rainFactor = 0;
    std::vector<AetherActor> actors;
    std::unique_ptr<PreviewSource> preview;
    FrameTargets targets;
    AetherStats stats{};
    std::string error;
    std::mutex mu;

    AetherStatus fail(AetherStatus s, std::string msg) {
        error = std::move(msg);
        return s;
    }
};

namespace {

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

AETHER_API const char* aether_build_info(void) {
    return "aether_core " AETHER_VERSION_STRING " (abi 0.1.0; slang far kernel; " __DATE__ ")";
}

AETHER_API AetherStatus aether_create(AetherBackend backend, AetherContext** out) {
    if (!out) return AETHER_ERR_ARG;
    auto ctx = std::make_unique<AetherContext>();
    if (backend == AETHER_BACKEND_VULKAN) {
        std::string err;
        ctx->backend = makeVulkanBackend(err);
        if (!ctx->backend) {
            *out = nullptr;
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

AETHER_API const char* aether_last_error(AetherContext* ctx) { return ctx ? ctx->error.c_str() : "null context"; }

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
    return AETHER_OK;
}

AETHER_API AetherStatus aether_render(AetherContext* ctx, const AetherFrame* f, const AetherFrameOutput* out) {
    if (!ctx || !f || !out || f->width == 0 || f->height == 0) return AETHER_ERR_ARG;
    std::lock_guard<std::mutex> lock(ctx->mu);
    FrameInputs in{};
    in.scene = &ctx->world.packed();
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
    std::memcpy(g.lods, in.scene->lods, sizeof(g.lods));
    in.palette = &ctx->palette;
    in.weather = &ctx->weather;
    in.cloudNoise = &ctx->cloudNoise;
    double ms = 0;
    std::string err;
    if (!ctx->backend->render(in, ctx->targets, &ms, err)) return ctx->fail(AETHER_ERR_GPU, err);
    size_t n = size_t(f->width) * f->height;
    if (out->color) std::memcpy(out->color, ctx->targets.color.data(), n * 4);
    if (out->normal) std::memcpy(out->normal, ctx->targets.normal.data(), n * 4);
    if (out->depth) std::memcpy(out->depth, ctx->targets.depth.data(), n * 4);
    uint64_t steps = 0;
    for (uint32_t c : ctx->targets.counters) steps += c;
    ctx->stats.raySteps = steps;
    ctx->stats.lastFrameMs = ms;
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
            if (!ctx->world.tile(lod, tx, tz)) todo.push_back({tx, tz});
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

AETHER_API float aether_preview_height(AetherContext* ctx, double x, double z) {
    if (!ctx || !ctx->preview) return NAN;
    return ctx->preview->height(x, z);
}

}  // extern "C"
