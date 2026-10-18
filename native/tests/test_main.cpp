// aether_core tests: SVDAG round trips, sharing, the C ABI, and CPU/Vulkan kernel parity on preview terrain.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <random>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "camera.hpp"
#include "svdag.hpp"

using namespace aether;

static int failures = 0;
#define CHECK(c)                                                              \
    do {                                                                      \
        if (!(c)) {                                                           \
            std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #c);       \
            failures++;                                                       \
        }                                                                     \
    } while (0)

// A dense source with no classification shortcuts beyond what it can prove.
struct DenseSource : VoxelSource {
    std::function<uint16_t(int, int, int)> f;
    bool smartClassify = false;
    BoxClass classify(int x, int y, int z, int n, uint16_t& mat) override {
        if (!smartClassify) return BoxClass::Mixed;
        uint16_t first = f(x, y, z);
        bool same = true;
        for (int k = 0; k < n && same; k++)
            for (int j = 0; j < n && same; j++)
                for (int i = 0; i < n && same; i++) same = f(x + i, y + j, z + k) == first;
        if (!same) return BoxClass::Mixed;
        mat = first;
        return first ? BoxClass::Uniform : BoxClass::Air;
    }
    void brick(int x, int y, int z, uint16_t out[64]) override {
        for (int k = 0; k < 4; k++)
            for (int j = 0; j < 4; j++)
                for (int i = 0; i < 4; i++) out[i + 4 * j + 16 * k] = f(x + i, y + j, z + k);
    }
};

static void testRoundTrip(const char* name, std::function<uint16_t(int, int, int)> f, bool smart) {
    DenseSource s;
    s.f = f;
    s.smartClassify = smart;
    auto t0 = std::chrono::steady_clock::now();
    Svdag d = buildSvdag(s);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    uint64_t mismatches = 0, solid = 0;
    for (int z = 0; z < REGION; z += 1)
        for (int y = 0; y < REGION; y += 1)
            for (int x = 0; x < REGION; x += 1) {
                uint16_t want = f(x, y, z);
                if (want) solid++;
                if (d.at(x, y, z) != want) mismatches++;
            }
    std::printf("  %-22s build %7.1f ms, nodes %7zu words, leaves %6zu, runs %7zu, solid %9llu, mismatches %llu\n", name,
                ms, d.nodes.size(), d.leaves.size(), d.runEnd.size(), (unsigned long long)solid,
                (unsigned long long)mismatches);
    CHECK(mismatches == 0);
    CHECK(d.solidVoxels == solid);
}

static void testSvdag() {
    std::printf("svdag round trips\n");
    testRoundTrip("empty", [](int, int, int) { return uint16_t(0); }, true);
    testRoundTrip("full", [](int, int, int) { return uint16_t(7); }, true);
    testRoundTrip("sphere", [](int x, int y, int z) {
        double dx = x - 127.5, dy = y - 100.5, dz = z - 140.5;
        double r = std::sqrt(dx * dx + dy * dy + dz * dz);
        return uint16_t(r < 90 ? (r < 60 ? 1 : 2 + (x / 37) % 3) : 0);
    }, false);
    testRoundTrip("terrain", [](int x, int y, int z) {
        double h = 90 + 30 * std::sin(x * 0.05) * std::cos(z * 0.043) + 8 * std::sin(x * 0.31 + z * 0.17);
        if (y < h - 4) return uint16_t(1);
        if (y < h - 1) return uint16_t(3);
        if (y < h) return uint16_t(2);
        return uint16_t(y < 70 ? 6 : 0);
    }, true);
    std::mt19937 rng(12345);
    std::vector<uint16_t> noise(size_t(REGION) * REGION * REGION);
    for (auto& v : noise) v = (rng() % 100) < 7 ? uint16_t(1 + rng() % 5) : 0;
    testRoundTrip("random 7%", [&](int x, int y, int z) { return noise[(size_t(z) * REGION + y) * REGION + x]; }, false);

    // sharing: a full solid region is a handful of nodes whatever its material count
    DenseSource s;
    s.f = [](int, int, int) { return uint16_t(1); };
    s.smartClassify = true;
    Svdag d = buildSvdag(s);
    CHECK(d.nodes.size() <= 6 * 17);
    CHECK(d.leaves.size() == 1);
    CHECK(d.runEnd.size() == 1);
}

static void testAbi() {
    std::printf("c abi\n");
    CHECK(aether_abi_version() == AETHER_ABI_VERSION);
    std::printf("  %s\n", aether_build_info());
    AetherContext* ctx = nullptr;
    CHECK(aether_create(AETHER_BACKEND_CPU, &ctx) == AETHER_OK);
    CHECK(aether_render(ctx, nullptr, nullptr) == AETHER_ERR_ARG);
    // a flat synthetic tile at lod 0 around the origin, rendered looking down: every pixel must hit at the expected depth
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AetherColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            CHECK(aether_upload_column_tile(ctx, &t) == AETHER_OK);
        }
    double centre[3] = {10.5, 140.0, 10.5};
    CHECK(aether_update(ctx, centre, 0) == AETHER_OK);
    AetherFrame f{};
    f.cameraPos[0] = 10.5;
    f.cameraPos[1] = 140.0;
    f.cameraPos[2] = 10.5;
    // straight down: invViewProj maps ndc (x, y, 0.5) to direction (x * 0.1, -1, y * 0.1)
    float inv[16] = {0.1f, 0, 0, 0, 0, 0, 0.1f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 64;
    f.height = 64;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> depth(64 * 64);
    std::vector<uint32_t> color(64 * 64);
    AetherFrameOutput o{color.data(), nullptr, depth.data()};
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    // surface 100 means voxels with centre < 100 are solid: the top face is y = 100, 40 blocks below the camera
    double worst = 0;
    for (int py = 0; py < 64; py++)
        for (int px = 0; px < 64; px++) {
            float nx = (px + 0.5f) / 64 * 2 - 1, ny = 1 - (py + 0.5f) / 64 * 2;
            double len = std::sqrt(0.01 * nx * nx + 1 + 0.01 * ny * ny);
            double expect = 40.0 * len;
            worst = std::max(worst, std::abs(depth[size_t(py) * 64 + px] - expect));
        }
    std::printf("  flat plane: worst depth error %.4f blocks\n", worst);
    CHECK(worst < 0.01);

    // grazing rays: camera on integer coordinates, so whole pixel columns run inside region boundary planes
    // (regression: these used to stall on the boundary and see through the ground)
    for (double yaw : {0.0, 90.0, 180.0, 270.0}) {
        AetherFrame g = f;
        g.cameraPos[0] = 0.0;
        g.cameraPos[1] = 140.0;
        g.cameraPos[2] = 0.0;
        g.width = 96;
        g.height = 64;
        cameraMatrix(yaw, -25, 60, 1.5, g.invViewProj);
        std::vector<float> dg(96 * 64);
        AetherFrameOutput og{nullptr, nullptr, dg.data()};
        CHECK(aether_render(ctx, &g, &og) == AETHER_OK);
        double worstG = 0;
        for (int py = 0; py < 64; py++)
            for (int px = 0; px < 96; px++) {
                float nx = (px + 0.5f) / 96 * 2 - 1, ny = 1 - (py + 0.5f) / 64 * 2;
                float p4[4];
                for (int r = 0; r < 4; r++)
                    p4[r] = g.invViewProj[r] * nx + g.invViewProj[4 + r] * ny + g.invViewProj[8 + r] * 0.5f + g.invViewProj[12 + r];
                double dx = p4[0] / p4[3], dy = p4[1] / p4[3], dz = p4[2] / p4[3];
                double l = std::sqrt(dx * dx + dy * dy + dz * dz);
                if (dy / l > -0.2) continue;
                double expect = 40.0 / (-dy / l);
                worstG = std::max(worstG, std::abs(dg[size_t(py) * 96 + px] - expect));
            }
        std::printf("  grazing yaw %3.0f: worst depth error %.4f blocks\n", yaw, worstG);
        CHECK(worstG < 0.02);
    }
    aether_destroy(ctx);
}

// A ray must cross a stretch no LOD has loaded yet and still hit land streamed in behind it.
static void testStreamingGap() {
    std::printf("streaming gap\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), wall(256 * 256, 300.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    // LOD 0 around the camera, then nothing until LOD 2 tile x = 2 (blocks 2048..3071), which is a 300-high wall
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AetherColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    for (int tz = -2; tz <= 2; tz++) {
        AetherColumnTile t{2, 2, tz, wall.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
        aether_upload_column_tile(ctx, &t);
    }
    double centre[3] = {0.5, 140.0, 0.5};
    aether_update(ctx, centre, 0);
    AetherFrame f{};
    f.cameraPos[0] = 0.5;
    f.cameraPos[1] = 140.0;
    f.cameraPos[2] = 0.5;
    f.width = 32;
    f.height = 32;
    cameraMatrix(90, 0, 10, 1.0, f.invViewProj);  // east, level, narrow: wants LOD 1-2 over the gap
    f.maxDistance = 6000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> depth(32 * 32);
    AetherFrameOutput o{nullptr, nullptr, depth.data()};
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    float d = depth[16 * 32 + 16];
    std::printf("  centre pixel depth %.1f (wall face at 2047.5)\n", d);
    CHECK(std::abs(d - 2047.5f) < 8.0f);
    AetherStats st{};
    aether_get_stats(ctx, &st);
    aether_destroy(ctx);
}

// Four zones along one ray east at y 140: A populated air (LOD 0, x 0..255), B unloaded at every LOD (256..511),
// C finer-only (LOD 0 air while the ray wants LOD 1-2, 512..699), D a wall (x 700). Catches early termination,
// brick-sized stepping through B and LOD thrashing in C (either would exhaust the segment budget or miss D).
static void testFourZones() {
    std::printf("four-zone ray\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), withWall(256 * 256, 100.0f), water(256 * 256, NAN);
    for (int j = 0; j < 256; j++)
        for (int i = 188; i < 256; i++) withWall[size_t(j) * 256 + i] = 300.0f;  // x >= 512 + 188 = 700
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            if (tx == 1) continue;  // zone B: no tile at any LOD
            AetherColumnTile t{0, tx, tz, tx == 2 ? withWall.data() : flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 140.0, 0.5};
    aether_update(ctx, centre, 0);
    AetherFrame f{};
    f.cameraPos[0] = 0.5;
    f.cameraPos[1] = 140.0;
    f.cameraPos[2] = 0.5;
    f.width = 32;
    f.height = 32;
    cameraMatrix(90, 0, 10, 1.0, f.invViewProj);
    f.maxDistance = 6000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> depth(32 * 32);
    AetherFrameOutput o{nullptr, nullptr, depth.data()};
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    float d = depth[16 * 32 + 16];
    std::printf("  centre pixel depth %.1f (wall face at 699.5)\n", d);
    CHECK(std::abs(d - 699.5f) < 1.0f);
    aether_destroy(ctx);
}

// A far actor on flat ground: the ray must hit its body at the analytic distance, in front of the terrain.
static void testActors() {
    std::printf("far actors\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AetherColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 101.0, 0.5};
    aether_update(ctx, centre, 0);
    AetherActor a{};
    a.pos[0] = 200.5;
    a.pos[1] = 100.0;
    a.pos[2] = 0.5;
    a.height = 1.8f;
    a.radius = 0.35f;
    a.kind = 0;
    a.tint = 0xFF3050C0u;
    CHECK(aether_set_actors(ctx, &a, 1) == AETHER_OK);
    AetherFrame f{};
    f.cameraPos[0] = 0.5;
    f.cameraPos[1] = 101.0;
    f.cameraPos[2] = 0.5;
    f.width = 33;
    f.height = 33;
    cameraMatrix(90, 0, 2, 1.0, f.invViewProj);  // east, level, narrow: the actor fills the centre
    f.maxDistance = 600;
    f.planetRadius = 0;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_ACTORS;
    std::vector<float> depth(33 * 33);
    std::vector<uint32_t> normal(33 * 33);
    AetherFrameOutput o{nullptr, normal.data(), depth.data(), nullptr};
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    float d = depth[16 * 33 + 16];
    uint32_t cls = (normal[16 * 33 + 16] >> 16) & 255;
    std::printf("  centre pixel depth %.3f (capsule front at 199.65), class %u\n", d, cls);
    CHECK(std::abs(d - 199.65f) < 0.02f);
    CHECK(cls == 4);
    f.flags = 0;  // actors off: the same ray goes on to the far ground (level ray: no hit at all here)
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    CHECK(depth[16 * 33 + 16] > 1e29f);
    aether_destroy(ctx);
}

// Pixels the near field covers skip the far march entirely; the rest render exactly as without near depth.
static void testNearDepth(const char* raster) {
    std::printf("near-depth skip\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    if (aether_preview_load_raster(ctx, raster) != AETHER_OK) {
        std::printf("  SKIP: no raster\n");
        aether_destroy(ctx);
        return;
    }
    double x = 0, z = 0, y = aether_preview_height(ctx, x, z) + 60;
    for (int lod = 0; lod < 4; lod++) {
        int span = 256 << lod;
        aether_preview_generate(ctx, lod, -2 * span, -2 * span, 3 * span, 3 * span);
    }
    double centre[3] = {x, y, z};
    aether_update(ctx, centre, 0);
    const uint32_t W = 128, H = 72;
    AetherFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    cameraMatrix(105, -3, 70, double(W) / H, f.invViewProj);
    f.width = W;
    f.height = H;
    f.nearFieldEnd = 96;
    f.maxDistance = 8000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_ATMOS;
    std::vector<float> base(W * H * 4), with(W * H * 4), nearDist(W * H);
    for (uint32_t py = 0; py < H; py++)
        for (uint32_t px = 0; px < W; px++) nearDist[py * W + px] = px < W / 2 ? 20.0f : 1e30f;  // left half covered
    AetherFrameOutput o1{nullptr, nullptr, nullptr, base.data()};
    CHECK(aether_render(ctx, &f, &o1) == AETHER_OK);
    AetherStats s1{};
    aether_get_stats(ctx, &s1);
    f.flags |= AETHER_FRAME_NEAR_DEPTH;
    f.nearDepth = nearDist.data();
    AetherFrameOutput o2{nullptr, nullptr, nullptr, with.data()};
    CHECK(aether_render(ctx, &f, &o2) == AETHER_OK);
    AetherStats s2{};
    aether_get_stats(ctx, &s2);
    size_t leftNonZero = 0, rightDiff = 0;
    for (uint32_t py = 0; py < H; py++)
        for (uint32_t px = 0; px < W; px++) {
            const float* a = &base[(py * W + px) * 4];
            const float* b = &with[(py * W + px) * 4];
            if (px < W / 2) leftNonZero += (b[0] != 0 || b[1] != 0 || b[2] != 0 || b[3] != 0);
            else rightDiff += std::memcmp(a, b, 16) != 0;
        }
    std::printf("  covered half: %zu non-empty px; open half: %zu px differ; ray steps %llu -> %llu\n", leftNonZero,
                rightDiff, (unsigned long long)s1.raySteps, (unsigned long long)s2.raySteps);
    CHECK(leftNonZero == 0);
    CHECK(rightDiff == 0);
    CHECK(s2.raySteps * 10 < s1.raySteps * 7);
    aether_destroy(ctx);
}

static bool parity(const char* raster) {
    std::printf("cpu/vulkan parity (preview terrain)\n");
    AetherContext* cpu = nullptr;
    AetherContext* gpu = nullptr;
    if (aether_create(AETHER_BACKEND_VULKAN, &gpu) != AETHER_OK) {
        std::printf("  SKIP: no Vulkan device\n");
        return true;
    }
    aether_create(AETHER_BACKEND_CPU, &cpu);
    if (aether_preview_load_raster(cpu, raster) != AETHER_OK || aether_preview_load_raster(gpu, raster) != AETHER_OK) {
        std::printf("  SKIP: %s missing (run native/tools/export_raster.py)\n", raster);
        aether_destroy(cpu);
        aether_destroy(gpu);
        return true;
    }
    double x = 0, z = 0;
    double y = aether_preview_height(cpu, x, z) + 60;
    double centre[3] = {x, y, z};
    for (AetherContext* c : {cpu, gpu}) {
        for (int lod = 0; lod < 7; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
            aether_preview_generate(c, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
        }
        aether_update(c, centre, 0);
    }
    const uint32_t W = 320, H = 180;
    AetherFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    cameraMatrix(90, -5, 70, double(W) / H, f.invViewProj);  // looking east, slightly down
    f.width = W;
    f.height = H;
    f.maxDistance = 40000;
    f.planetRadius = 6371000;
    f.sunDir[0] = 0.4f;
    f.sunDir[1] = 0.6f;
    f.sunDir[2] = -0.3f;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_SHADOWS | AETHER_FRAME_ATMOS | AETHER_FRAME_SKY;
    std::vector<float> dc(W * H), dg(W * H);
    std::vector<uint32_t> cc(W * H), cg(W * H);
    AetherFrameOutput oc{cc.data(), nullptr, dc.data()}, og{cg.data(), nullptr, dg.data()};
    CHECK(aether_render(cpu, &f, &oc) == AETHER_OK);
    CHECK(aether_render(gpu, &f, &og) == AETHER_OK);
    size_t hitMismatch = 0, depthBad = 0, colorBad = 0, hits = 0;
    for (size_t i = 0; i < dc.size(); i++) {
        bool hc = dc[i] < 1e29f, hg = dg[i] < 1e29f;
        hits += hc;
        if (hc != hg) {
            hitMismatch++;
            continue;
        }
        if (hc && std::abs(dc[i] - dg[i]) > 0.5f + 0.002f * dc[i]) depthBad++;
        for (int ch = 0; ch < 3; ch++) {
            int a = int((cc[i] >> (8 * ch)) & 255), b = int((cg[i] >> (8 * ch)) & 255);
            if (std::abs(a - b) > 3) {
                colorBad++;
                break;
            }
        }
    }
    double n = double(dc.size());
    std::printf("  %zu px, hits %.1f%%, hit/miss mismatch %.3f%%, depth outliers %.3f%%, colour outliers %.3f%%\n",
                dc.size(), 100.0 * hits / n, 100.0 * hitMismatch / n, 100.0 * depthBad / n, 100.0 * colorBad / n);
    // float order differs (FMA contraction, transcendental ulps); a silhouette pixel may flip, nothing more
    CHECK(hits > dc.size() / 4);
    CHECK(hitMismatch / n < 0.002);
    CHECK(depthBad / n < 0.002);
    CHECK(colorBad / n < 0.01);

    // the cloud shell (half-resolution pass, upsampled) through both builds: the baked noise and a fair-weather field
    std::vector<uint32_t> noise(64 * 64 * 64);
    bool haveNoise = false;
    if (FILE* nf = std::fopen("native/data/cloud_noise.bin", "rb")) {
        haveNoise = std::fread(noise.data(), 4, noise.size(), nf) == noise.size();
        std::fclose(nf);
    }
    if (haveNoise) {
        std::vector<uint32_t> fld(128 * 128), dyn(128 * 128);
        for (int i = 0; i < 128 * 128; i++) {
            double c = 0.40 + 0.25 * std::sin(i % 128 * 0.21) * std::cos(i / 128 * 0.17);
            fld[i] = uint32_t(c * 255) | uint32_t(0.1 * 255) << 8 | 5u << 24;
            dyn[i] = uint32_t(240.0 / 1024 * 255) | uint32_t(120.0 / 1024 * 255) << 8 | uint32_t(0.1024 * 255) << 16;
        }
        AetherWeather wth{fld.data(), dyn.data(), fld.data(), dyn.data(), 0.0f};
        for (AetherContext* c : {cpu, gpu}) {
            aether_set_cloud_noise(c, noise.data());
            aether_set_weather(c, &wth);
        }
        AetherFrame fc = f;
        cameraMatrix(90, 6, 70, double(W) / H, fc.invViewProj);  // up into the deck
        fc.flags = (fc.flags | AETHER_FRAME_CLOUDS) & ~AETHER_FRAME_SKY;  // alpha = cloud opacity over the sky
        std::vector<float> lc(W * H * 4), lg(W * H * 4);
        AetherFrameOutput ocl{nullptr, nullptr, nullptr, lc.data()}, ogl{nullptr, nullptr, nullptr, lg.data()};
        CHECK(aether_render(cpu, &fc, &ocl) == AETHER_OK);
        CHECK(aether_render(gpu, &fc, &ogl) == AETHER_OK);
        size_t cloudy = 0, bad = 0;
        for (size_t i = 0; i < size_t(W) * H; i++) {
            bool diff = false;
            for (int k = 0; k < 4; k++) {
                double a = lc[i * 4 + k], b = lg[i * 4 + k];
                if (std::abs(a - b) > 0.01 + 0.01 * std::abs(a)) diff = true;
            }
            bad += diff;
            cloudy += lc[i * 4 + 3] > 0.0f && lc[i * 4 + 3] < 0.999f;
        }
        std::printf("  clouds: %zu px with partial cover; %.3f%% px differ between builds\n", cloudy, 100.0 * bad / n);
        CHECK(cloudy > size_t(W) * H / 20);
        CHECK(bad / n < 0.01);

        // temporal clouds (Vulkan context, which has history from the frame above). The reference is a fresh march
        // in its own context: a cloud-less frame of the same pose first (so the cloud pass clips to the right
        // surfaces, as in game), then the cloud frame, which has no cloud history and so marches every block.
        AetherContext* fresh = nullptr;
        aether_create(AETHER_BACKEND_VULKAN, &fresh);
        aether_preview_load_raster(fresh, raster);
        for (int lod = 0; lod < 7; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
            aether_preview_generate(fresh, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
        }
        aether_update(fresh, centre, 0);
        aether_set_cloud_noise(fresh, noise.data());
        aether_set_weather(fresh, &wth);
        std::vector<float> lf(W * H * 4);
        AetherFrameOutput olf{nullptr, nullptr, nullptr, lf.data()};
        auto freshRender = [&](const AetherFrame& pose) {
            AetherFrame bare = pose;
            bare.flags &= ~(AETHER_FRAME_CLOUDS | AETHER_FRAME_CLOUDS_QUARTER);
            CHECK(aether_render(fresh, &bare, &olf) == AETHER_OK);
            CHECK(aether_render(fresh, &pose, &olf) == AETHER_OK);
        };

        // a static camera converges to the fresh march exactly, then stays bit-stable
        std::vector<float> lt(W * H * 4), lt2(W * H * 4);
        AetherFrameOutput olt{nullptr, nullptr, nullptr, lt.data()}, olt2{nullptr, nullptr, nullptr, lt2.data()};
        for (int i = 0; i < 6; i++) CHECK(aether_render(gpu, &fc, &olt) == AETHER_OK);
        for (int i = 0; i < 4; i++) CHECK(aether_render(gpu, &fc, &olt2) == AETHER_OK);
        freshRender(fc);
        size_t staticDiff = 0, freshOff = 0;
        for (size_t i = 0; i < lt.size(); i++) {
            staticDiff += lt[i] != lt2[i];
            freshOff += lt[i] != lf[i];
        }
        std::printf("  temporal, static camera: %zu values off the fresh march, %zu change once converged\n", freshOff,
                    staticDiff);
        CHECK(freshOff == 0);
        CHECK(staticDiff == 0);

        // a slowly turning one stays close to a fresh march of its final pose
        AetherFrame fm = fc;
        for (int i = 0; i <= 8; i++) {
            cameraMatrix(90 + i * 0.25, 6, 70, double(W) / H, fm.invViewProj);  // 0.25 degrees per frame
            CHECK(aether_render(gpu, &fm, &olt) == AETHER_OK);
        }
        freshRender(fm);
        size_t moved = 0;
        double worstA = 0;
        for (size_t i = 0; i < size_t(W) * H; i++) {
            double da = std::abs(double(lt[i * 4 + 3]) - lf[i * 4 + 3]);
            worstA = std::max(worstA, da);
            moved += da > 0.08;
        }
        std::printf("  temporal, turning camera: %.2f%% px off by > 0.08 alpha (worst %.3f)\n", 100.0 * moved / n, worstA);
        CHECK(moved / n < 0.02);
        aether_destroy(fresh);
    }
    aether_destroy(cpu);
    aether_destroy(gpu);
    return true;
}

int main(int argc, char** argv) {
    testSvdag();
    testAbi();
    testStreamingGap();
    testFourZones();
    testActors();
    testNearDepth(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    parity(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    std::printf(failures ? "FAILED (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
