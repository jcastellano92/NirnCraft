// aether_core tests: SVDAG round trips, sharing, the C ABI, and CPU/Vulkan kernel parity on preview terrain.
#include <algorithm>
#include <array>
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

// A slope crossing a vertical region boundary (LOD 0 regions span y -64 + 256k), seen from far enough that normals
// are smoothed: the band of hits at the boundary must shade like the slope above and below it. Both ways: rays
// rising into the region above (boundary 448, camera below it) and descending into the one below (boundary 192,
// camera just above it, as on a hillside looking across a valley). (A layer
// read wrongly at a region's bottom shows up as a band of flat, sideways normals: a dark line along the boundary.)
static void testRegionSeam() {
    std::printf("vertical region seam\n");
    struct Case { float boundary, base, flat, camY, pitch; };
    for (Case cs : {Case{448, 300, 280, 430, 2}, Case{192, 100, 90, 210.4f, -4}})
    for (AetherBackend backend : {AETHER_BACKEND_CPU, AETHER_BACKEND_VULKAN}) {
        std::vector<float> ramp(256 * 256), flat(256 * 256, cs.flat), water(256 * 256, NAN);
        for (int j = 0; j < 256; j++)
            for (int i = 0; i < 256; i++) ramp[size_t(j) * 256 + i] = cs.base + 0.8f * float(i);
        std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
        AetherContext* ctx = nullptr;
        if (aether_create(backend, &ctx) != AETHER_OK) {
            std::printf("  SKIP: no Vulkan\n");
            continue;
        }
        for (int tz = -1; tz <= 1; tz++)
            for (int tx = -3; tx <= 1; tx++) {
                AetherColumnTile t{0, tx, tz, tx == 0 ? ramp.data() : flat.data(), water.data(), top.data(), fill.data(),
                                   nullptr, nullptr};
                aether_upload_column_tile(ctx, &t);
            }
        const uint32_t W = 160, H = 160;
        double centre[3] = {-500.5, cs.camY, 128.5};
        aether_update(ctx, centre, 0);
        AetherFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = W;
        f.height = H;
        cameraMatrix(90, cs.pitch, 20, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<uint32_t> normal(W * H);
        std::vector<float> depth(W * H);
        AetherFrameOutput o{nullptr, normal.data(), depth.data(), nullptr};
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        // mean normal.y of the hits per 2-block band of world height
        double sum[64] = {}, cnt[64] = {};
        for (uint32_t py = 0; py < H; py++)
            for (uint32_t px = 0; px < W; px++) {
                float d = depth[py * W + px];
                if (d > 1e29f) continue;
                double nx = (px + 0.5) / W * 2 - 1, ny = 1 - (py + 0.5) / H * 2, v[4];
                for (int r = 0; r < 4; r++) v[r] = f.invViewProj[r] * nx + f.invViewProj[4 + r] * ny + f.invViewProj[8 + r] + f.invViewProj[12 + r];
                double dl = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                double y = centre[1] + v[1] / dl * d;
                int band = int(std::floor((y - (cs.boundary - 64)) / 2));
                if (band < 0 || band >= 64) continue;
                uint32_t nv = normal[py * W + px];
                double ex = (nv & 255) / 255.0 * 2 - 1, ez = (nv >> 8 & 255) / 255.0 * 2 - 1;
                double ey = 1 - std::abs(ex) - std::abs(ez);
                double l = std::sqrt(ey * ey + (ey >= 0 ? ex * ex + ez * ez : 1.0));
                sum[band] += ey / l;
                cnt[band]++;
            }
        double seam = 0, rest = 0, nRest = 0, worst = 0;
        for (int b = 0; b < 64; b++) {
            if (cnt[b] < 20) continue;
            double m = sum[b] / cnt[b];
            if (b == 32 || b == 31) seam += m / 2;  // the two bands either side of the boundary
            else if (b < 30 || b > 33) {
                rest += m;
                nRest++;
                worst = std::max(worst, std::abs(m - 0.6));
            }
        }
        rest /= std::max(1.0, nRest);
        std::printf("  boundary %.0f, %s: mean normal.y %.2f at the boundary, %.2f elsewhere\n", cs.boundary,
                    backend == AETHER_BACKEND_CPU ? "cpu   " : "vulkan", seam, rest);
        CHECK(cnt[32] >= 20 && cnt[31] >= 20);
        CHECK(std::abs(seam - rest) < 0.15);
        aether_destroy(ctx);
    }
}

// Buildings over a tile (aether_upload_column_structures): a 12 x 12 house, walls to 110 and a roof to 114 on ground at
// 100, seen from straight above; it survives the ground being re-uploaded (a refined tile) and goes when removed.
static void testStructures() {
    std::printf("structures\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    const size_t N = 256 * 256;
    std::vector<float> ground(N, 100.0f), water(N, NAN), wall(N, NAN), roof(N, NAN);
    std::vector<uint16_t> top(N, 2), fill(N, 3), wallMat(N, 0), roofMat(N, 0);
    for (int j = 122; j < 134; j++)
        for (int i = 122; i < 134; i++) {
            size_t c = size_t(j) * 256 + i;
            wall[c] = 110.0f;
            roof[c] = 114.0f - 0.5f * float(std::abs(2 * i - 255) / 2);  // a hip towards the edges
            wallMat[c] = 40;
            roofMat[c] = 41;
        }
    AetherMaterial extra[2] = {{{0.8f, 0.78f, 0.7f}, 0.9f, 0.0f, AETHER_CLASS_TERRAIN},
                               {{0.5f, 0.12f, 0.08f}, 0.9f, 0.0f, AETHER_CLASS_TERRAIN}};
    CHECK(aether_set_materials(ctx, 40, extra, 2) == AETHER_OK);
    CHECK(aether_set_materials(ctx, 0, extra, 1) == AETHER_ERR_ARG);
    // the buildings first, then the ground: they attach when it arrives
    CHECK(aether_upload_column_structures(ctx, 0, 0, 0, wall.data(), roof.data(), wallMat.data(), roofMat.data()) == AETHER_OK);
    for (int tz = -1; tz <= 1; tz++)
        for (int tx = -1; tx <= 1; tx++) {
            AetherColumnTile t{0, tx, tz, ground.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    auto centreDepth = [&](float* edgeOut) {
        double centre[3] = {128.0, 160.0, 128.0};
        aether_update(ctx, centre, 0);
        AetherFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = f.height = 33;
        cameraMatrix(0, -89.9, 20, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<float> depth(33 * 33);
        AetherFrameOutput o{nullptr, nullptr, depth.data(), nullptr};
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        if (edgeOut) *edgeOut = depth[0];
        return depth[16 * 33 + 16];
    };
    float edge = 0, d = centreDepth(&edge);
    std::printf("  roof below the camera at %.2f (expect ~46), ground at the frame edge %.2f\n", d, edge);
    CHECK(std::abs(d - 46.0f) < 1.5f);
    // the ground refined: the house stays
    std::vector<float> ground2(N, 101.0f);
    AetherColumnTile t2{0, 0, 0, ground2.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
    aether_upload_column_tile(ctx, &t2);
    float d2 = centreDepth(nullptr);
    CHECK(std::abs(d2 - 46.0f) < 1.5f);
    // removed: the ground (101) shows through
    CHECK(aether_upload_column_structures(ctx, 0, 0, 0, nullptr, nullptr, nullptr, nullptr) == AETHER_OK);
    float d3 = centreDepth(nullptr);
    std::printf("  after a ground refine %.2f; removed %.2f (ground at 59)\n", d2, d3);
    CHECK(std::abs(d3 - 59.0f) < 1.5f);
    aether_destroy(ctx);
}

// The sun map (aether_set_sun_map): a 200-block pillar on flat ground, the sun 30 degrees up in the east. Decoded
// as a shader pack would (parameter row only): the ground in the pillar's shadow is behind an occluder, open ground
// and the pillar's top are not; CPU and Vulkan agree.
static bool sunLit(const std::vector<float>& m, uint32_t S, const double p[3]) {
    const float* q = &m[size_t(S) * S * 2];
    double c[3] = {q[0], q[1], q[2]}, he = q[3], X[3] = {q[4], q[5], q[6]}, Y[3] = {q[7], q[8], q[9]};
    double L[3] = {q[10], q[11], q[12]}, r[3] = {p[0] - c[0], p[1] - c[1], p[2] - c[2]};
    double u = (r[0] * X[0] + r[1] * X[1] + r[2] * X[2]) / he, v = (r[0] * Y[0] + r[1] * Y[1] + r[2] * Y[2]) / he;
    int i = int((u * 0.5 + 0.5) * S), j = int((v * 0.5 + 0.5) * S);
    if (i < 0 || j < 0 || i >= int(S) || j >= int(S)) return true;
    double occ = m[(size_t(j) * S + i) * 2], s = r[0] * L[0] + r[1] * L[1] + r[2] * L[2];
    return !(occ > s + 3.0);
}

static void testSunMap() {
    std::printf("sun map\n");
    const size_t N = 256 * 256;
    std::vector<float> flat(N, 100.0f), pillar(N, 100.0f), water(N, NAN);
    for (int j = 108; j < 148; j++)
        for (int i = 108; i < 148; i++) pillar[size_t(j) * 256 + i] = 300.0f;  // x, z -20 .. 20 in tile (0, 0)
    std::vector<uint16_t> top(N, 2), fill(N, 3);
    std::vector<float> maps[2];
    const uint32_t S = 256;
    int b = 0;
    for (AetherBackend backend : {AETHER_BACKEND_CPU, AETHER_BACKEND_VULKAN}) {
        AetherContext* ctx = nullptr;
        if (aether_create(backend, &ctx) != AETHER_OK) {
            std::printf("  SKIP: no Vulkan\n");
            continue;
        }
        for (int lod = 0; lod < 4; lod++) {
            int span = 256 << lod;
            for (int tz = -3; tz <= 2; tz++)
                for (int tx = -3; tx <= 2; tx++) {
                    bool p = lod == 0 && tx == 0 && tz == 0;
                    // coarser LODs see the pillar too (a 40-block pillar is a few voxels there)
                    std::vector<float> s = flat;
                    if (lod > 0 && tx == 0 && tz == 0) {
                        int c = 128 >> lod, r = std::max(1, 20 >> lod);  // the same world x, z at that LOD
                        for (int jj = c - r; jj < c + r; jj++)
                            for (int ii = c - r; ii < c + r; ii++) s[size_t(jj) * 256 + ii] = 300.0f;
                    }
                    (void)span;
                    AetherColumnTile t{lod, tx, tz, p ? pillar.data() : s.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
                    aether_upload_column_tile(ctx, &t);
                }
        }
        CHECK(aether_set_sun_map(ctx, S, 512.0f) == AETHER_OK);
        CHECK(aether_set_sun_map(ctx, 32, 512.0f) == AETHER_ERR_ARG);
        double centre[3] = {128.5, 160.0, 128.5};
        aether_update(ctx, centre, 0);
        AetherFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = f.height = 16;
        cameraMatrix(0, -10, 60, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        double el = 30.0 * 3.14159265358979 / 180.0;
        f.sunDir[0] = float(std::cos(el));
        f.sunDir[1] = float(std::sin(el));
        f.lodBias = 1;
        AetherFrameOutput o{};
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        maps[b].resize(size_t(S) * (S + 1) * 2);
        CHECK(aether_read_sun_map(ctx, maps[b].data(), uint32_t(maps[b].size())) == AETHER_OK);
        const auto& m = maps[b];
        double behind[3] = {128 - 80.0, 100.5, 128}, open[3] = {128 + 150.0, 100.5, 128 + 150.0}, crest[3] = {128, 300.5, 128};
        std::printf("  %s: behind the pillar %s, open ground %s, its top %s\n", backend == AETHER_BACKEND_CPU ? "cpu   " : "vulkan",
                    sunLit(m, S, behind) ? "lit" : "shadowed", sunLit(m, S, open) ? "lit" : "shadowed", sunLit(m, S, crest) ? "lit" : "shadowed");
        CHECK(!sunLit(m, S, behind));
        CHECK(sunLit(m, S, open));
        CHECK(sunLit(m, S, crest));
        b++;
        aether_destroy(ctx);
    }
    if (b == 2) {
        size_t off = 0;
        for (size_t k = 0; k < size_t(S) * S; k++) {
            float a = maps[0][k * 2], c = maps[1][k * 2];
            if ((a < -1e29f) != (c < -1e29f) || (a > -1e29f && std::abs(a - c) > 1.0f)) off++;
        }
        std::printf("  cpu/vulkan: %.3f%% texels differ\n", 100.0 * off / (double(S) * S));
        CHECK(off < size_t(S) * S / 200);
    }
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

// Refinement fades: a tile replaced while resident dissolves from the old ground to the new one over the fade time,
// per pixel (most pixels still show the old ground just after the swap, every pixel the new one once it is over).
// Then LOD dither: a flat plane looks the same at every level, so depth must not change while the level map does.
static void testTransitions() {
    std::printf("refinement fades and LOD dither\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    CHECK(aether_set_transitions(ctx, -1.0f) == AETHER_ERR_ARG);
    CHECK(aether_set_transitions(ctx, 2.0f) == AETHER_OK);  // long enough that a loaded machine still catches its start
    std::vector<float> low(256 * 256, 100.0f), high(256 * 256, 110.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    auto upload = [&](std::vector<float>& h, int lod) {
        for (int tz = -2; tz <= 2; tz++)
            for (int tx = -2; tx <= 2; tx++) {
                AetherColumnTile t{lod, tx, tz, h.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
                aether_upload_column_tile(ctx, &t);
            }
    };
    auto sleepMs = [](int ms) {
        auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
        while (std::chrono::steady_clock::now() < end) {}
    };
    upload(low, 0);
    double centre[3] = {10.5, 140.0, 10.5};
    aether_update(ctx, centre, 0);
    sleepMs(2100);
    aether_update(ctx, centre, 0);  // the first window's own fade-in is over (and dropped)
    AetherFrame f{};
    f.cameraPos[0] = 10.5;
    f.cameraPos[1] = 140.0;
    f.cameraPos[2] = 10.5;
    float inv[16] = {0.1f, 0, 0, 0, 0, 0, 0.1f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};  // straight down
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 64;
    f.height = 64;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> depth(64 * 64);
    AetherFrameOutput o{nullptr, nullptr, depth.data()};
    auto newShare = [&] {
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        size_t n = 0;
        for (size_t i = 0; i < depth.size(); i++) {
            float nx = (float(i % 64) + 0.5f) / 64 * 2 - 1, ny = 1 - (float(i / 64) + 0.5f) / 64 * 2;
            float len = std::sqrt(0.01f * nx * nx + 1 + 0.01f * ny * ny);
            n += std::abs(depth[i] / len - 30.0f) < 0.5f;  // the new ground is 30 below the camera, the old 40
        }
        return double(n) / double(depth.size());
    };
    double before = newShare();
    upload(high, 0);
    aether_update(ctx, centre, 0);
    double start = newShare();
    sleepMs(2100);
    aether_update(ctx, centre, 0);
    double end = newShare();
    std::printf("  new ground: %.0f%% before the swap, %.0f%% right after it, %.0f%% after the fade\n", 100 * before,
                100 * start, 100 * end);
    CHECK(before == 0.0);
    CHECK(start < 0.5);
    CHECK(end == 1.0);
    aether_set_transitions(ctx, 0.0f);  // fades off: the swap is immediate
    upload(low, 0);
    aether_update(ctx, centre, 0);
    CHECK(newShare() == 0.0);

    for (int lod = 1; lod < 3; lod++) upload(low, lod);  // (y 100 is a face at LODs 0-2: 2^lod divides 164)
    aether_update(ctx, centre, 0);
    AetherFrame g = f;
    g.width = 160;
    g.height = 90;
    cameraMatrix(90, -4, 70, 160.0 / 90.0, g.invViewProj);
    g.maxDistance = 6000;
    g.flags = AETHER_FRAME_DEBUG_LOD;
    std::vector<float> d0(160 * 90), d1(160 * 90);
    std::vector<uint32_t> c0(160 * 90), c1(160 * 90);
    AetherFrameOutput o0{c0.data(), nullptr, d0.data()}, o1{c1.data(), nullptr, d1.data()};
    CHECK(aether_render(ctx, &g, &o0) == AETHER_OK);
    g.flags |= AETHER_FRAME_LOD_DITHER;
    CHECK(aether_render(ctx, &g, &o1) == AETHER_OK);
    size_t hits = 0, depthOff = 0, lodChanged = 0;
    for (size_t i = 0; i < d0.size(); i++) {
        if (d0[i] > 1e29f) continue;
        hits++;
        depthOff += std::abs(d0[i] - d1[i]) > 0.01f + 1e-4f * d0[i];
        lodChanged += c0[i] != c1[i];
    }
    std::printf("  dither: %zu ground px, %zu change level, %zu change depth\n", hits, lodChanged, depthOff);
    CHECK(hits > 1000);
    CHECK(lodChanged > 0 && lodChanged < hits / 3);
    CHECK(depthOff < hits / 200 + 2);
    aether_destroy(ctx);
}

// Far lights: a lantern 600 blocks out glows at night (a bright core at its pixel, nothing far from it), is gone by day,
// and a wall between it and the camera hides it; fires stay lit by day.
static void testLights() {
    std::printf("far lights\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AetherColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 104.0, 0.5};
    aether_update(ctx, centre, 0);
    AetherLight l{{600.5, 103.0, 0.5}, 1.0f, 1.0f, 0x40A0FFu, AETHER_LIGHT_NIGHT};
    CHECK(aether_set_light_group(ctx, 7, &l, 1) == AETHER_OK);
    AetherFrame f{};
    f.cameraPos[0] = 0.5;
    f.cameraPos[1] = 104.0;
    f.cameraPos[2] = 0.5;
    f.width = 65;
    f.height = 65;
    f.maxDistance = 2000;
    f.planetRadius = 0;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_ATMOS;
    std::vector<float> lin(65 * 65 * 4);
    AetherFrameOutput o{nullptr, nullptr, nullptr, lin.data()};
    auto lum = [&](int x, int y) {
        const float* p = &lin[(size_t(y) * 65 + x) * 4];
        return p[0] + p[1] + p[2];
    };
    auto shoot = [&](float sunY) {
        f.sunDir[0] = 0;
        f.sunDir[1] = sunY;
        f.sunDir[2] = std::sqrt(1 - sunY * sunY);
        cameraMatrix(90, 0, 6, 1.0, f.invViewProj);  // east, level, narrow
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    };
    // where the light falls on screen: the brightest pixel
    shoot(-0.5f);
    int bx = 0, by = 0;
    for (int y = 0; y < 65; y++)
        for (int x = 0; x < 65; x++)
            if (lum(x, y) > lum(bx, by)) bx = x, by = y;
    float nightCore = lum(bx, by), nightFar = lum(std::min(64, bx + 12), by);
    shoot(0.6f);
    float dayCore = lum(bx, by) - lum(std::min(64, bx + 12), by);
    std::printf("  night: core %.3f at (%d, %d), 12 px away %.4f; day: core excess %.4f\n", nightCore, bx, by, nightFar, dayCore);
    CHECK(std::abs(bx - 32) <= 1 && std::abs(by - 32) <= 3);
    CHECK(nightCore > 0.2f && nightCore > 20 * nightFar);
    CHECK(std::abs(dayCore) < 0.01f);
    // a wall at x 300..303, 40 blocks high, hides it
    std::vector<float> wall = flat;
    for (int z = 0; z < 256; z++)
        for (int x = 44; x < 48; x++) wall[size_t(z) * 256 + x] = 140.0f;
    AetherColumnTile t{0, 1, 0, wall.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
    aether_upload_column_tile(ctx, &t);
    AetherColumnTile t2{0, 1, -1, wall.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
    aether_upload_column_tile(ctx, &t2);
    aether_update(ctx, centre, 0);
    shoot(-0.5f);
    float hidden = lum(bx, by);
    std::printf("  behind a wall: %.4f\n", hidden);
    CHECK(hidden < nightCore * 0.05f);
    // a fire stays lit by day (dimmer against the bright day it is drawn over, but there)
    l.kind = AETHER_LIGHT_FIRE;
    l.pos[1] = 160.0;  // above the wall
    aether_set_light_group(ctx, 7, &l, 1);
    shoot(0.6f);
    float fire = 0;
    for (int y = 0; y < 65; y++)
        for (int x = 0; x < 65; x++) fire = std::max(fire, lum(x, y));
    std::printf("  a fire by day: core %.3f\n", fire);
    CHECK(fire > 0.2f);
    CHECK(aether_set_light_group(ctx, 7, nullptr, 0) == AETHER_OK);
    aether_destroy(ctx);
}

// A humanoid impostor is a lit silhouette, never a filled dark rectangle: its pixels cover a humanoid share of their
// bounding box (tall and narrow, about 60 %, not ~100 %), their colour is near the tint and lit (not black), their
// height matches the actor's; and none is drawn nearer than the near field's end (the game draws what is there), with
// a dissolve over the first 16 blocks past it.
static void testImpostorShape() {
    std::printf("humanoid impostor shape\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AetherColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 101.62, 0.5};
    aether_update(ctx, centre, 0);
    AetherActor a{};
    a.pos[0] = 0.5;
    a.pos[1] = 100.0;
    a.pos[2] = -29.5;  // 30 blocks north
    a.yaw = 0.7f;
    a.height = 1.8f;
    a.radius = 0.3f;
    a.kind = 0;
    a.tint = 0xFFC05020u;  // blue-ish cloth (r 0x20, g 0x50, b 0xC0)
    aether_set_actors(ctx, &a, 1);
    AetherFrame f{};
    for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
    f.width = 128;
    f.height = 128;
    cameraMatrix(0, -0.0, 6, 1.0, f.invViewProj);  // north, level, narrow: the actor about 70 px tall
    f.maxDistance = 600;
    f.planetRadius = 0;
    f.sunDir[0] = 0.3f;
    f.sunDir[1] = 0.8f;
    f.sunDir[2] = 0.5f;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_ACTORS | AETHER_FRAME_SKY;
    std::vector<uint32_t> normal(128 * 128);
    std::vector<float> lin(128 * 128 * 4);
    AetherFrameOutput o{nullptr, normal.data(), nullptr, lin.data()};
    auto measure = [&](float nearEnd, size_t& px, int& w, int& h, double rgb[3]) {
        f.nearFieldEnd = nearEnd;
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        int x0 = 128, x1 = -1, y0 = 128, y1 = -1;
        px = 0;
        rgb[0] = rgb[1] = rgb[2] = 0;
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 128; x++) {
                size_t i = size_t(y) * 128 + x;
                if (((normal[i] >> 16) & 255) != 4) continue;
                px++;
                x0 = std::min(x0, x), x1 = std::max(x1, x), y0 = std::min(y0, y), y1 = std::max(y1, y);
                for (int k = 0; k < 3; k++) rgb[k] += lin[i * 4 + k];
            }
        w = x1 - x0 + 1;
        h = y1 - y0 + 1;
        if (px) for (int k = 0; k < 3; k++) rgb[k] /= double(px);
    };
    size_t px = 0;
    int w = 0, h = 0;
    double rgb[3];
    measure(0.0f, px, w, h, rgb);
    double cover = px ? double(px) / (double(w) * h) : 0;
    // the expected height on screen: 1.8 blocks at 30 over a 6-degree, 128 px view
    double expectH = 1.8 / 30.0 / (2 * std::tan(3.0 * 3.14159265 / 180)) * 128;
    std::printf("  %zu px in a %d x %d box (fill %.2f, expected height %.0f px); mean linear rgb %.3f %.3f %.3f\n", px, w, h,
                cover, expectH, rgb[0], rgb[1], rgb[2]);
    CHECK(px > 200);
    CHECK(h > 2 * w);                         // tall and narrow
    CHECK(cover > 0.4 && cover < 0.9);        // a silhouette, not a filled rectangle
    CHECK(std::abs(h - expectH) < 0.15 * expectH);
    CHECK(rgb[2] > rgb[0] && rgb[0] + rgb[1] + rgb[2] > 0.05);  // the tint's hue, and lit
    size_t inside = 0, band = 0;
    measure(40.0f, inside, w, h, rgb);         // the near field reaches past it: not drawn
    measure(22.0f, band, w, h, rgb);           // 8 blocks into the 16-block fade-in: about half its pixels
    std::printf("  near field to 40: %zu px; to 22 (fading in): %zu px\n", inside, band);
    CHECK(inside == 0);
    CHECK(band > px / 4 && band < px * 3 / 4);
    aether_destroy(ctx);
}

// A dragon's wings beat (its silhouette changes over the beat), fold on the ground, and an impostor marked half
// transparent covers about half its pixels (the per-pixel dissolve of a tracking-boundary cross-fade).
static void testActorMotion() {
    std::printf("far actor motion and fades\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AetherColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            aether_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 160.0, 0.5};
    aether_update(ctx, centre, 0);
    AetherActor a{};
    a.pos[0] = 0.5;
    a.pos[1] = 140.0;
    a.pos[2] = -150.5;
    a.height = 5.0f;
    a.radius = 6.0f;
    a.kind = 2;  // dragon, facing -Z (away from the camera)
    a.tint = 0xFF283038u;
    AetherFrame f{};
    f.cameraPos[0] = 0.5;
    f.cameraPos[1] = 180.0;
    f.cameraPos[2] = 0.5;
    f.width = 96;
    f.height = 64;
    cameraMatrix(0, -14.5, 24, 1.5, f.invViewProj);  // north, from above and behind: the wings' upper faces
    f.maxDistance = 600;
    f.planetRadius = 0;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_ACTORS;
    std::vector<uint32_t> normal(96 * 64);
    AetherFrameOutput o{nullptr, normal.data(), nullptr, nullptr};
    auto cover = [&](uint32_t flags, float t) {
        a.flags = flags;
        aether_set_actors(ctx, &a, 1);
        f.timeSec = t;
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        size_t n = 0, top = 64, bottom = 0;
        for (size_t i = 0; i < normal.size(); i++)
            if (((normal[i] >> 16) & 255) == 4) {
                n++;
                top = std::min(top, i / 96);
                bottom = std::max(bottom, i / 96);
            }
        return std::array<size_t, 3>{n, top, bottom};
    };
    auto up = cover(AETHER_GAIT_FLAP << 8, 0.3125f);    // beat phase 90 degrees: wings raised
    auto down = cover(AETHER_GAIT_FLAP << 8, 0.9375f);  // 270 degrees: lowered
    auto folded = cover(AETHER_GAIT_STILL << 8, 0.3125f);
    auto full = cover(AETHER_GAIT_GLIDE << 8, 0.0f);
    auto half = cover((AETHER_GAIT_GLIDE << 8) | 128u, 0.0f);
    auto gone = cover((AETHER_GAIT_GLIDE << 8) | 255u, 0.0f);
    std::printf("  dragon px: wings up %zu (rows %zu-%zu), down %zu (rows %zu-%zu), folded %zu; gliding %zu, half faded %zu, "
                "gone %zu\n", up[0], up[1], up[2], down[0], down[1], down[2], folded[0], full[0], half[0], gone[0]);
    CHECK(up[0] > 50 && down[0] > 50);
    CHECK(up[1] < down[1]);       // raised wings reach higher on screen
    CHECK(folded[0] < full[0]);   // folded wings cover less than spread ones
    CHECK(half[0] > full[0] / 4 && half[0] < full[0] * 3 / 4);
    CHECK(gone[0] == 0);
    aether_destroy(ctx);
}

// The world's surface blocks (aether_upload_column_heights3): a top block override replaces the dressing's on dry
// ground, a frozen column turns its water to ice, and a river column gets flowing water.
static void testSurfaceOverrides(const char* raster) {
    std::printf("surface block overrides\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    if (aether_preview_load_raster(ctx, raster) != AETHER_OK) {
        std::printf("  SKIP: no raster\n");
        aether_destroy(ctx);
        return;
    }
    AetherMaterial red{{0.6f, 0.02f, 0.02f}, 0.9f, 0.0f, AETHER_CLASS_TERRAIN};
    CHECK(aether_set_materials(ctx, 300, &red, 1) == AETHER_OK);
    const size_t N = 256 * 256;
    std::vector<float> surf(N, 100.0f), water(N, NAN);
    std::vector<uint16_t> top(N, 0), fill(N, 0);
    std::vector<uint8_t> flags(N, 0);
    for (size_t c = 0; c < N; c++) {
        bool wet = (c % 256) >= 128;  // the east half is a pond
        if (wet) water[c] = 103.0f, flags[c] = AETHER_COLUMN_FROZEN;
        else top[c] = 300;
    }
    for (int tz = -1; tz <= 1; tz++)
        for (int tx = -1; tx <= 1; tx++)
            CHECK(aether_upload_column_heights3(ctx, 0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr,
                                                nullptr, flags.data()) == AETHER_OK);
    double centre[3] = {128.0, 180.0, 128.0};
    aether_update(ctx, centre, 0);
    AetherFrame f{};
    for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
    f.width = f.height = 48;
    cameraMatrix(0, -89.9, 40, 1.0, f.invViewProj);
    f.maxDistance = 6000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_DEBUG_ALBEDO;
    std::vector<float> lin(48 * 48 * 4);
    std::vector<uint32_t> normal(48 * 48);
    AetherFrameOutput o{nullptr, normal.data(), nullptr, lin.data()};
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    size_t redWest = 0, iceEast = 0, waterEast = 0;
    for (int py = 0; py < 48; py++)
        for (int px = 0; px < 48; px++) {
            size_t i = size_t(py) * 48 + px;
            uint32_t cls = (normal[i] >> 16) & 255;
            if (px < 20) redWest += lin[i * 4] > 3 * lin[i * 4 + 1] && lin[i * 4] > 0.2f;
            if (px > 28) {
                iceEast += cls == AETHER_CLASS_TERRAIN && lin[i * 4 + 2] > lin[i * 4] && lin[i * 4 + 2] > 0.3f;
                waterEast += cls == AETHER_CLASS_WATER;
            }
        }
    std::printf("  west (dry, red top): %zu of 960 px red; east (frozen pond): %zu ice px, %zu water px\n", redWest, iceEast, waterEast);
    CHECK(redWest > 900);
    CHECK(iceEast > 900 && waterEast == 0);
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

// Caller geometry, preview dressing (aether_upload_column_heights): the terrain sits where the caller says.
static void testColumnHeights(const char* raster) {
    std::printf("column heights upload\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    std::vector<float> surf(256 * 256), water(256 * 256, NAN);
    CHECK(aether_upload_column_heights(ctx, 0, 0, 0, surf.data(), water.data()) == AETHER_ERR_STATE);  // no raster
    if (aether_preview_load_raster(ctx, raster) != AETHER_OK) {
        std::printf("  SKIP: no raster\n");
        aether_destroy(ctx);
        return;
    }
    // dry upland (the uploads below carry no water, the reference keeps its seas and rivers)
    double x = 1000.5, z = -700.5;
    for (int k = 0; k < 400; k++) {
        double h = aether_preview_height(ctx, x, z);
        if (h > 120 && h < 250) break;
        x = 1000.5 + (k % 20) * 997.0;
        z = -700.5 + (k / 20) * 1013.0;
    }
    float h0 = aether_preview_height(ctx, x, z);
    CHECK(aether_has_tile(ctx, 0, int(std::floor(x / 256)), int(std::floor(z / 256))) == 0);
    for (int lod = 0; lod < 3; lod++) {
        int span = 256 << lod, s = 1 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        for (int tz = cz - 2; tz <= cz + 2; tz++)
            for (int tx = cx - 2; tx <= cx + 2; tx++) {
                // the preview's own ground, lifted 7 blocks, dry: what an exact source would hand over
                for (int j = 0; j < 256; j++)
                    for (int i = 0; i < 256; i++)
                        surf[j * 256 + i] = aether_preview_height(ctx, tx * span + (i + 0.5) * s, tz * span + (j + 0.5) * s) + 7.0f;
                CHECK(aether_upload_column_heights(ctx, lod, tx, tz, surf.data(), water.data()) == AETHER_OK);
            }
    }
    CHECK(aether_has_tile(ctx, 0, int(std::floor(x / 256)), int(std::floor(z / 256))) == 1);
    CHECK(aether_upload_column_heights(ctx, 99, 0, 0, surf.data(), water.data()) == AETHER_ERR_ARG);
    double y = h0 + 60;
    double centre[3] = {x, y, z};
    aether_update(ctx, centre, 0);
    const uint32_t W = 33, H = 33;
    AetherFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    cameraMatrix(0, -89.9, 20, 1.0, f.invViewProj);
    f.width = W;
    f.height = H;
    f.maxDistance = 8000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    // against the preview's own tiles: every pixel 7 blocks nearer (ground or the canopy dressed onto it)
    AetherContext* ref = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ref);
    aether_preview_load_raster(ref, raster);
    for (int lod = 0; lod < 3; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        aether_preview_generate(ref, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    aether_update(ref, centre, 0);
    std::vector<float> depth(W * H), refDepth(W * H);
    AetherFrameOutput o{nullptr, nullptr, depth.data(), nullptr}, oref{nullptr, nullptr, refDepth.data(), nullptr};
    CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
    CHECK(aether_render(ref, &f, &oref) == AETHER_OK);
    std::vector<float> lift;
    for (uint32_t i = 0; i < W * H; i++)
        if (depth[i] < 1e29f && refDepth[i] < 1e29f) lift.push_back(refDepth[i] - depth[i]);
    std::sort(lift.begin(), lift.end());
    float med = lift.empty() ? 0.0f : lift[lift.size() / 2];
    std::printf("  looking down from %.0f blocks: %zu px hit both, median lift %.2f blocks (expect 7)\n", y - h0,
                lift.size(), med);
    CHECK(lift.size() > W * H / 2);
    CHECK(std::abs(med - 7.0f) < 0.75f);
    aether_destroy(ref);
    aether_destroy(ctx);
}

// Biome tints over the dressing (aether_upload_column_heights2): grass-family tops take the caller's material.
static void testGrassTint(const char* raster) {
    std::printf("grass tint\n");
    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    if (aether_preview_load_raster(ctx, raster) != AETHER_OK) {
        std::printf("  SKIP: no raster\n");
        aether_destroy(ctx);
        return;
    }
    AetherMaterial red{{0.6f, 0.02f, 0.02f}, 0.9f, 0.0f, AETHER_CLASS_TERRAIN};
    CHECK(aether_set_materials(ctx, 300, &red, 1) == AETHER_OK);
    // a temperate lowland with grass (search the raster for one)
    double x = 0.5, z = 0.5;
    for (int k = 0; k < 400; k++) {
        double h = aether_preview_height(ctx, x, z);
        if (h > 70 && h < 110) break;
        x = 0.5 + (k % 20) * 731.0;
        z = 0.5 + (k / 20) * 677.0;
    }
    const size_t N = 256 * 256;
    std::vector<float> surf(N), water(N, NAN);
    std::vector<uint16_t> grass(N, 300);
    double frac[2] = {0, 0};
    for (int pass = 0; pass < 2; pass++) {
        for (int tz = -1; tz <= 1; tz++)
            for (int tx = -1; tx <= 1; tx++) {
                int cx = int(std::floor(x / 256)) + tx, cz = int(std::floor(z / 256)) + tz;
                for (int j = 0; j < 256; j++)
                    for (int i = 0; i < 256; i++) surf[size_t(j) * 256 + i] = aether_preview_height(ctx, cx * 256 + i + 0.5, cz * 256 + j + 0.5);
                CHECK(aether_upload_column_heights2(ctx, 0, cx, cz, surf.data(), water.data(), pass ? grass.data() : nullptr,
                                                    nullptr) == AETHER_OK);
            }
        double h0 = aether_preview_height(ctx, x, z);
        double centre[3] = {x, h0 + 80, z};
        aether_update(ctx, centre, 0);
        AetherFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = f.height = 48;
        cameraMatrix(0, -89.9, 60, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<float> lin(48 * 48 * 4);
        AetherFrameOutput o{nullptr, nullptr, nullptr, lin.data()};
        CHECK(aether_render(ctx, &f, &o) == AETHER_OK);
        size_t redPx = 0;
        for (size_t i = 0; i < 48 * 48; i++) redPx += lin[i * 4] > 3 * lin[i * 4 + 1] && lin[i * 4] > 0.01f;
        frac[pass] = double(redPx) / (48 * 48);
    }
    std::printf("  red-tinted pixels: %.0f%% without the override, %.0f%% with it\n", 100 * frac[0], 100 * frac[1]);
    CHECK(frac[0] < 0.01);
    CHECK(frac[1] > 0.1);  // the open grass of the view (forest and rock keep their own)
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
    // a row of fires out east (the far-light pass in both builds)
    std::vector<AetherLight> fires;
    for (int i = 0; i < 40; i++)
        fires.push_back(AetherLight{{x + 400.0 + i * 90.0, aether_preview_height(cpu, x + 400.0 + i * 90.0, z + (i % 7 - 3) * 40.0) + 4.0,
                                     z + (i % 7 - 3) * 40.0}, 1.5f, 2.0f, 0x2080FFu, AETHER_LIGHT_FIRE});
    for (AetherContext* c : {cpu, gpu}) {
        for (int lod = 0; lod < 7; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
            aether_preview_generate(c, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
        }
        aether_update(c, centre, 0);
        aether_set_light_group(c, 1, fires.data(), uint32_t(fires.size()));
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
        aether_set_light_group(fresh, 1, fires.data(), uint32_t(fires.size()));
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
    testRegionSeam();
    testStructures();
    testSunMap();
    testActors();
    testImpostorShape();
    testActorMotion();
    testTransitions();
    testLights();
    testNearDepth(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    testColumnHeights(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    testGrassTint(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    testSurfaceOverrides(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    parity(argc > 1 ? argv[1] : "native/data/tamriel_raster.bin");
    std::printf(failures ? "FAILED (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
