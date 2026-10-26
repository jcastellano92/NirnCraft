// atlas_core tests: SVDAG round trips, sharing, the C ABI, and CPU/Vulkan kernel parity on preview terrain.
#include <algorithm>
#include <set>
#include <array>
#include <atomic>
#include <chrono>
#include <thread>
#include <cstdlib>
#include <stdexcept>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <functional>
#include <random>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "camera.hpp"
#include "svdag.hpp"
#include "parallel.hpp"
#include "vgask.hpp"
#include "slice.hpp"

using namespace atlas;

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
    CHECK(atlas_abi_version() == ATLAS_ABI_VERSION);
    std::printf("  %s\n", atlas_build_info());
    AtlasContext* ctx = nullptr;
    CHECK(atlas_create(ATLAS_BACKEND_CPU, &ctx) == ATLAS_OK);
    CHECK(atlas_render(ctx, nullptr, nullptr) == ATLAS_ERR_ARG);
    // a flat synthetic tile at lod 0 around the origin, rendered looking down: every pixel must hit at the expected depth
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            CHECK(atlas_upload_column_tile(ctx, &t) == ATLAS_OK);
        }
    double centre[3] = {10.5, 140.0, 10.5};
    CHECK(atlas_update(ctx, centre, 0) == ATLAS_OK);
    AtlasFrame f{};
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
    AtlasFrameOutput o{color.data(), nullptr, depth.data()};
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
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
        AtlasFrame g = f;
        g.cameraPos[0] = 0.0;
        g.cameraPos[1] = 140.0;
        g.cameraPos[2] = 0.0;
        g.width = 96;
        g.height = 64;
        cameraMatrix(yaw, -25, 60, 1.5, g.invViewProj);
        std::vector<float> dg(96 * 64);
        AtlasFrameOutput og{nullptr, nullptr, dg.data()};
        CHECK(atlas_render(ctx, &g, &og) == ATLAS_OK);
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
    atlas_destroy(ctx);
}

// A ray must cross a stretch no LOD has loaded yet and still hit land streamed in behind it.
static void testStreamingGap() {
    std::printf("streaming gap\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), wall(256 * 256, 300.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    // LOD 0 around the camera, then nothing until LOD 2 tile x = 2 (blocks 2048..3071), which is a 300-high wall
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    for (int tz = -2; tz <= 2; tz++) {
        AtlasColumnTile t{2, 2, tz, wall.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
        atlas_upload_column_tile(ctx, &t);
    }
    double centre[3] = {0.5, 140.0, 0.5};
    atlas_update(ctx, centre, 0);
    AtlasFrame f{};
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
    AtlasFrameOutput o{nullptr, nullptr, depth.data()};
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    float d = depth[16 * 32 + 16];
    std::printf("  centre pixel depth %.1f (wall face at 2047.5)\n", d);
    CHECK(std::abs(d - 2047.5f) < 8.0f);
    AtlasStats st{};
    atlas_get_stats(ctx, &st);
    atlas_destroy(ctx);
}

// Four zones along one ray east at y 140: A populated air (LOD 0, x 0..255), B unloaded at every LOD (256..511),
// C finer-only (LOD 0 air while the ray wants LOD 1-2, 512..699), D a wall (x 700). Catches early termination,
// brick-sized stepping through B and LOD thrashing in C (either would exhaust the segment budget or miss D).
static void testFourZones() {
    std::printf("four-zone ray\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), withWall(256 * 256, 100.0f), water(256 * 256, NAN);
    for (int j = 0; j < 256; j++)
        for (int i = 188; i < 256; i++) withWall[size_t(j) * 256 + i] = 300.0f;  // x >= 512 + 188 = 700
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            if (tx == 1) continue;  // zone B: no tile at any LOD
            AtlasColumnTile t{0, tx, tz, tx == 2 ? withWall.data() : flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 140.0, 0.5};
    atlas_update(ctx, centre, 0);
    AtlasFrame f{};
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
    AtlasFrameOutput o{nullptr, nullptr, depth.data()};
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    float d = depth[16 * 32 + 16];
    std::printf("  centre pixel depth %.1f (wall face at 699.5)\n", d);
    CHECK(std::abs(d - 699.5f) < 1.0f);
    atlas_destroy(ctx);
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
    for (AtlasBackend backend : {ATLAS_BACKEND_CPU, ATLAS_BACKEND_VULKAN}) {
        std::vector<float> ramp(256 * 256), flat(256 * 256, cs.flat), water(256 * 256, NAN);
        for (int j = 0; j < 256; j++)
            for (int i = 0; i < 256; i++) ramp[size_t(j) * 256 + i] = cs.base + 0.8f * float(i);
        std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
        AtlasContext* ctx = nullptr;
        if (atlas_create(backend, &ctx) != ATLAS_OK) {
            std::printf("  SKIP: no Vulkan\n");
            continue;
        }
        for (int tz = -1; tz <= 1; tz++)
            for (int tx = -3; tx <= 1; tx++) {
                AtlasColumnTile t{0, tx, tz, tx == 0 ? ramp.data() : flat.data(), water.data(), top.data(), fill.data(),
                                   nullptr, nullptr};
                atlas_upload_column_tile(ctx, &t);
            }
        const uint32_t W = 160, H = 160;
        double centre[3] = {-500.5, cs.camY, 128.5};
        atlas_update(ctx, centre, 0);
        AtlasFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = W;
        f.height = H;
        cameraMatrix(90, cs.pitch, 20, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<uint32_t> normal(W * H);
        std::vector<float> depth(W * H);
        AtlasFrameOutput o{nullptr, normal.data(), depth.data(), nullptr};
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
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
                    backend == ATLAS_BACKEND_CPU ? "cpu   " : "vulkan", seam, rest);
        CHECK(cnt[32] >= 20 && cnt[31] >= 20);
        CHECK(std::abs(seam - rest) < 0.15);
        atlas_destroy(ctx);
    }
}

// Buildings over a tile (atlas_upload_column_structures): a 12 x 12 house, walls to 110 and a roof to 114 on ground at
// 100, seen from straight above; it survives the ground being re-uploaded (a refined tile) and goes when removed.
static void testStructures() {
    std::printf("structures\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
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
    AtlasMaterial extra[2] = {{{0.8f, 0.78f, 0.7f}, 0.9f, 0.0f, ATLAS_CLASS_TERRAIN},
                               {{0.5f, 0.12f, 0.08f}, 0.9f, 0.0f, ATLAS_CLASS_TERRAIN}};
    CHECK(atlas_set_materials(ctx, 40, extra, 2) == ATLAS_OK);
    CHECK(atlas_set_materials(ctx, 0, extra, 1) == ATLAS_ERR_ARG);
    // the buildings first, then the ground: they attach when it arrives
    CHECK(atlas_upload_column_structures(ctx, 0, 0, 0, wall.data(), roof.data(), wallMat.data(), roofMat.data()) == ATLAS_OK);
    for (int tz = -1; tz <= 1; tz++)
        for (int tx = -1; tx <= 1; tx++) {
            AtlasColumnTile t{0, tx, tz, ground.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    auto centreDepth = [&](float* edgeOut) {
        double centre[3] = {128.0, 160.0, 128.0};
        atlas_update(ctx, centre, 0);
        AtlasFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = f.height = 33;
        cameraMatrix(0, -89.9, 20, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<float> depth(33 * 33);
        AtlasFrameOutput o{nullptr, nullptr, depth.data(), nullptr};
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        if (edgeOut) *edgeOut = depth[0];
        return depth[16 * 33 + 16];
    };
    float edge = 0, d = centreDepth(&edge);
    std::printf("  roof below the camera at %.2f (expect ~46), ground at the frame edge %.2f\n", d, edge);
    CHECK(std::abs(d - 46.0f) < 1.5f);
    // the ground refined: the house stays
    std::vector<float> ground2(N, 101.0f);
    AtlasColumnTile t2{0, 0, 0, ground2.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
    atlas_upload_column_tile(ctx, &t2);
    float d2 = centreDepth(nullptr);
    CHECK(std::abs(d2 - 46.0f) < 1.5f);
    // removed: the ground (101) shows through
    CHECK(atlas_upload_column_structures(ctx, 0, 0, 0, nullptr, nullptr, nullptr, nullptr) == ATLAS_OK);
    float d3 = centreDepth(nullptr);
    std::printf("  after a ground refine %.2f; removed %.2f (ground at 59)\n", d2, d3);
    CHECK(std::abs(d3 - 59.0f) < 1.5f);
    atlas_destroy(ctx);
}

// The sun map (atlas_set_sun_map): a 200-block pillar on flat ground, the sun 30 degrees up in the east. Decoded
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
    for (AtlasBackend backend : {ATLAS_BACKEND_CPU, ATLAS_BACKEND_VULKAN}) {
        AtlasContext* ctx = nullptr;
        if (atlas_create(backend, &ctx) != ATLAS_OK) {
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
                    AtlasColumnTile t{lod, tx, tz, p ? pillar.data() : s.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
                    atlas_upload_column_tile(ctx, &t);
                }
        }
        CHECK(atlas_set_sun_map(ctx, S, 512.0f) == ATLAS_OK);
        CHECK(atlas_set_sun_map(ctx, 32, 512.0f) == ATLAS_ERR_ARG);
        double centre[3] = {128.5, 160.0, 128.5};
        atlas_update(ctx, centre, 0);
        AtlasFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = f.height = 16;
        cameraMatrix(0, -10, 60, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        double el = 30.0 * 3.14159265358979 / 180.0;
        f.sunDir[0] = float(std::cos(el));
        f.sunDir[1] = float(std::sin(el));
        f.lodBias = 1;
        AtlasFrameOutput o{};
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        maps[b].resize(size_t(S) * (S + 1) * 2);
        CHECK(atlas_read_sun_map(ctx, maps[b].data(), uint32_t(maps[b].size())) == ATLAS_OK);
        const auto& m = maps[b];
        double behind[3] = {128 - 80.0, 100.5, 128}, open[3] = {128 + 150.0, 100.5, 128 + 150.0}, crest[3] = {128, 300.5, 128};
        std::printf("  %s: behind the pillar %s, open ground %s, its top %s\n", backend == ATLAS_BACKEND_CPU ? "cpu   " : "vulkan",
                    sunLit(m, S, behind) ? "lit" : "shadowed", sunLit(m, S, open) ? "lit" : "shadowed", sunLit(m, S, crest) ? "lit" : "shadowed");
        CHECK(!sunLit(m, S, behind));
        CHECK(sunLit(m, S, open));
        CHECK(sunLit(m, S, crest));
        b++;
        atlas_destroy(ctx);
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
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 101.0, 0.5};
    atlas_update(ctx, centre, 0);
    AtlasActor a{};
    a.pos[0] = 200.5;
    a.pos[1] = 100.0;
    a.pos[2] = 0.5;
    a.height = 1.8f;
    a.radius = 0.35f;
    a.kind = 0;
    a.tint = 0xFF3050C0u;
    CHECK(atlas_set_actors(ctx, &a, 1) == ATLAS_OK);
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_ACTORS;
    std::vector<float> depth(33 * 33);
    std::vector<uint32_t> normal(33 * 33);
    AtlasFrameOutput o{nullptr, normal.data(), depth.data(), nullptr};
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    float d = depth[16 * 33 + 16];
    uint32_t cls = (normal[16 * 33 + 16] >> 16) & 255;
    std::printf("  centre pixel depth %.3f (capsule front at 199.65), class %u\n", d, cls);
    CHECK(std::abs(d - 199.65f) < 0.02f);
    CHECK(cls == 4);
    f.flags = 0;  // actors off: the same ray goes on to the far ground (level ray: no hit at all here)
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    CHECK(depth[16 * 33 + 16] > 1e29f);
    atlas_destroy(ctx);
}

// Refinement fades: a tile replaced while resident dissolves from the old ground to the new one over the fade time,
// per pixel (most pixels still show the old ground just after the swap, every pixel the new one once it is over).
// Then LOD dither: a flat plane looks the same at every level, so depth must not change while the level map does.
static void testTransitions() {
    std::printf("refinement fades and LOD dither\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    CHECK(atlas_set_transitions(ctx, -1.0f) == ATLAS_ERR_ARG);
    CHECK(atlas_set_transitions(ctx, 2.0f) == ATLAS_OK);  // long enough that a loaded machine still catches its start
    std::vector<float> low(256 * 256, 100.0f), high(256 * 256, 110.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    auto upload = [&](std::vector<float>& h, int lod) {
        for (int tz = -2; tz <= 2; tz++)
            for (int tx = -2; tx <= 2; tx++) {
                AtlasColumnTile t{lod, tx, tz, h.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
                atlas_upload_column_tile(ctx, &t);
            }
    };
    auto sleepMs = [](int ms) {
        auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
        while (std::chrono::steady_clock::now() < end) {}
    };
    upload(low, 0);
    double centre[3] = {10.5, 140.0, 10.5};
    atlas_update(ctx, centre, 0);
    sleepMs(2100);
    atlas_update(ctx, centre, 0);  // the first window's own fade-in is over (and dropped)
    AtlasFrame f{};
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
    AtlasFrameOutput o{nullptr, nullptr, depth.data()};
    auto newShare = [&] {
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
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
    atlas_update(ctx, centre, 0);
    double start = newShare();
    sleepMs(2100);
    atlas_update(ctx, centre, 0);
    double end = newShare();
    std::printf("  new ground: %.0f%% before the swap, %.0f%% right after it, %.0f%% after the fade\n", 100 * before,
                100 * start, 100 * end);
    CHECK(before == 0.0);
    CHECK(start < 0.5);
    CHECK(end == 1.0);
    atlas_set_transitions(ctx, 0.0f);  // fades off: the swap is immediate
    upload(low, 0);
    atlas_update(ctx, centre, 0);
    CHECK(newShare() == 0.0);

    for (int lod = 1; lod < 3; lod++) upload(low, lod);  // (y 100 is a face at LODs 0-2: 2^lod divides 164)
    atlas_update(ctx, centre, 0);
    AtlasFrame g = f;
    g.width = 160;
    g.height = 90;
    cameraMatrix(90, -4, 70, 160.0 / 90.0, g.invViewProj);
    g.maxDistance = 6000;
    g.flags = ATLAS_FRAME_DEBUG_LOD;
    std::vector<float> d0(160 * 90), d1(160 * 90);
    std::vector<uint32_t> c0(160 * 90), c1(160 * 90);
    AtlasFrameOutput o0{c0.data(), nullptr, d0.data()}, o1{c1.data(), nullptr, d1.data()};
    CHECK(atlas_render(ctx, &g, &o0) == ATLAS_OK);
    g.flags |= ATLAS_FRAME_LOD_DITHER;
    CHECK(atlas_render(ctx, &g, &o1) == ATLAS_OK);
    // (the level is continuous now, picked per pixel with or without the flag: the debug view shows it as a gradient,
    // many tints rather than one a level, and the flag changes nothing)
    size_t hits = 0, depthOff = 0, lodChanged = 0;
    std::set<uint32_t> tints;
    for (size_t i = 0; i < d0.size(); i++) {
        if (d0[i] > 1e29f) continue;
        hits++;
        depthOff += std::abs(d0[i] - d1[i]) > 0.01f + 1e-4f * d0[i];
        lodChanged += c0[i] != c1[i];
        tints.insert(c0[i] & 0xFFFFFFu);
    }
    std::printf("  continuous level: %zu ground px, %zu distinct level tints, %zu change level with the dither flag, %zu change depth\n",
                hits, tints.size(), lodChanged, depthOff);
    CHECK(hits > 1000);
    CHECK(tints.size() > 16);
    CHECK(lodChanged < hits / 100 + 2);
    CHECK(depthOff < hits / 200 + 2);
    atlas_destroy(ctx);
}

// Far lights: a lantern 600 blocks out glows at night (a bright core at its pixel, nothing far from it), is gone by day,
// and a wall between it and the camera hides it; fires stay lit by day.
static void testLights() {
    std::printf("far lights\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 104.0, 0.5};
    atlas_update(ctx, centre, 0);
    AtlasLight l{{600.5, 103.0, 0.5}, 1.0f, 1.0f, 0x40A0FFu, ATLAS_LIGHT_NIGHT};
    CHECK(atlas_set_light_group(ctx, 7, &l, 1) == ATLAS_OK);
    AtlasFrame f{};
    f.cameraPos[0] = 0.5;
    f.cameraPos[1] = 104.0;
    f.cameraPos[2] = 0.5;
    f.width = 65;
    f.height = 65;
    f.maxDistance = 2000;
    f.planetRadius = 0;
    f.lodBias = 1;
    f.flags = ATLAS_FRAME_ATMOS;
    std::vector<float> lin(65 * 65 * 4);
    AtlasFrameOutput o{nullptr, nullptr, nullptr, lin.data()};
    auto lum = [&](int x, int y) {
        const float* p = &lin[(size_t(y) * 65 + x) * 4];
        return p[0] + p[1] + p[2];
    };
    auto shoot = [&](float sunY) {
        f.sunDir[0] = 0;
        f.sunDir[1] = sunY;
        f.sunDir[2] = std::sqrt(1 - sunY * sunY);
        cameraMatrix(90, 0, 6, 1.0, f.invViewProj);  // east, level, narrow
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
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
    AtlasColumnTile t{0, 1, 0, wall.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
    atlas_upload_column_tile(ctx, &t);
    AtlasColumnTile t2{0, 1, -1, wall.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
    atlas_upload_column_tile(ctx, &t2);
    atlas_update(ctx, centre, 0);
    shoot(-0.5f);
    float hidden = lum(bx, by);
    std::printf("  behind a wall: %.4f\n", hidden);
    CHECK(hidden < nightCore * 0.05f);
    // a fire stays lit by day (dimmer against the bright day it is drawn over, but there)
    l.kind = ATLAS_LIGHT_FIRE;
    l.pos[1] = 160.0;  // above the wall
    atlas_set_light_group(ctx, 7, &l, 1);
    shoot(0.6f);
    float fire = 0;
    for (int y = 0; y < 65; y++)
        for (int x = 0; x < 65; x++) fire = std::max(fire, lum(x, y));
    std::printf("  a fire by day: core %.3f\n", fire);
    CHECK(fire > 0.2f);
    CHECK(atlas_set_light_group(ctx, 7, nullptr, 0) == ATLAS_OK);
    atlas_destroy(ctx);
}

// A humanoid impostor is a lit silhouette, never a filled dark rectangle: its pixels cover a humanoid share of their
// bounding box (tall and narrow, about 60 %, not ~100 %), their colour is near the tint and lit (not black), their
// height matches the actor's; and none is drawn nearer than the near field's end (the game draws what is there), with
// a dissolve over the first 16 blocks past it.
static void testImpostorShape() {
    std::printf("humanoid impostor shape\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 101.62, 0.5};
    atlas_update(ctx, centre, 0);
    AtlasActor a{};
    a.pos[0] = 0.5;
    a.pos[1] = 100.0;
    a.pos[2] = -29.5;  // 30 blocks north
    a.yaw = 0.7f;
    a.height = 1.8f;
    a.radius = 0.3f;
    a.kind = 0;
    a.tint = 0xFFC05020u;  // blue-ish cloth (r 0x20, g 0x50, b 0xC0)
    atlas_set_actors(ctx, &a, 1);
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_ACTORS | ATLAS_FRAME_SKY;
    std::vector<uint32_t> normal(128 * 128);
    std::vector<float> lin(128 * 128 * 4);
    AtlasFrameOutput o{nullptr, normal.data(), nullptr, lin.data()};
    auto measure = [&](float nearEnd, size_t& px, int& w, int& h, double rgb[3]) {
        f.nearFieldEnd = nearEnd;
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
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
    atlas_destroy(ctx);
}

// A dragon's wings beat (its silhouette changes over the beat), fold on the ground, and an impostor marked half
// transparent covers about half its pixels (the per-pixel dissolve of a tracking-boundary cross-fade).
static void testActorMotion() {
    std::printf("far actor motion and fades\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> flat(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, flat.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    double centre[3] = {0.5, 160.0, 0.5};
    atlas_update(ctx, centre, 0);
    AtlasActor a{};
    a.pos[0] = 0.5;
    a.pos[1] = 140.0;
    a.pos[2] = -150.5;
    a.height = 5.0f;
    a.radius = 6.0f;
    a.kind = 2;  // dragon, facing -Z (away from the camera)
    a.tint = 0xFF283038u;
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_ACTORS;
    std::vector<uint32_t> normal(96 * 64);
    AtlasFrameOutput o{nullptr, normal.data(), nullptr, nullptr};
    auto cover = [&](uint32_t flags, float t) {
        a.flags = flags;
        atlas_set_actors(ctx, &a, 1);
        f.timeSec = t;
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        size_t n = 0, top = 64, bottom = 0;
        for (size_t i = 0; i < normal.size(); i++)
            if (((normal[i] >> 16) & 255) == 4) {
                n++;
                top = std::min(top, i / 96);
                bottom = std::max(bottom, i / 96);
            }
        return std::array<size_t, 3>{n, top, bottom};
    };
    auto up = cover(ATLAS_GAIT_FLAP << 8, 0.3125f);    // beat phase 90 degrees: wings raised
    auto down = cover(ATLAS_GAIT_FLAP << 8, 0.9375f);  // 270 degrees: lowered
    auto folded = cover(ATLAS_GAIT_STILL << 8, 0.3125f);
    auto full = cover(ATLAS_GAIT_GLIDE << 8, 0.0f);
    auto half = cover((ATLAS_GAIT_GLIDE << 8) | 128u, 0.0f);
    auto gone = cover((ATLAS_GAIT_GLIDE << 8) | 255u, 0.0f);
    std::printf("  dragon px: wings up %zu (rows %zu-%zu), down %zu (rows %zu-%zu), folded %zu; gliding %zu, half faded %zu, "
                "gone %zu\n", up[0], up[1], up[2], down[0], down[1], down[2], folded[0], full[0], half[0], gone[0]);
    CHECK(up[0] > 50 && down[0] > 50);
    CHECK(up[1] < down[1]);       // raised wings reach higher on screen
    CHECK(folded[0] < full[0]);   // folded wings cover less than spread ones
    CHECK(half[0] > full[0] / 4 && half[0] < full[0] * 3 / 4);
    CHECK(gone[0] == 0);
    atlas_destroy(ctx);
}

// The world's surface blocks (atlas_upload_column_heights3): a top block override replaces the dressing's on dry
// ground, a frozen column turns its water to ice, and a river column gets flowing water.
static void testSurfaceOverrides(const char* raster) {
    std::printf("surface block overrides\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    if (atlas_preview_load_raster(ctx, raster) != ATLAS_OK) {
        std::printf("  SKIP: no raster\n");
        atlas_destroy(ctx);
        return;
    }
    AtlasMaterial red{{0.6f, 0.02f, 0.02f}, 0.9f, 0.0f, ATLAS_CLASS_TERRAIN};
    CHECK(atlas_set_materials(ctx, 300, &red, 1) == ATLAS_OK);
    const size_t N = 256 * 256;
    std::vector<float> surf(N, 100.0f), water(N, NAN);
    std::vector<uint16_t> top(N, 0), fill(N, 0);
    std::vector<uint8_t> flags(N, 0);
    for (size_t c = 0; c < N; c++) {
        bool wet = (c % 256) >= 128;  // the east half is a pond
        if (wet) water[c] = 103.0f, flags[c] = ATLAS_COLUMN_FROZEN;
        else top[c] = 300;
    }
    for (int tz = -1; tz <= 1; tz++)
        for (int tx = -1; tx <= 1; tx++)
            CHECK(atlas_upload_column_heights3(ctx, 0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr,
                                                nullptr, flags.data()) == ATLAS_OK);
    double centre[3] = {128.0, 180.0, 128.0};
    atlas_update(ctx, centre, 0);
    AtlasFrame f{};
    for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
    f.width = f.height = 48;
    cameraMatrix(0, -89.9, 40, 1.0, f.invViewProj);
    f.maxDistance = 6000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    f.flags = ATLAS_FRAME_DEBUG_ALBEDO;
    std::vector<float> lin(48 * 48 * 4);
    std::vector<uint32_t> normal(48 * 48);
    AtlasFrameOutput o{nullptr, normal.data(), nullptr, lin.data()};
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    size_t redWest = 0, iceEast = 0, waterEast = 0;
    for (int py = 0; py < 48; py++)
        for (int px = 0; px < 48; px++) {
            size_t i = size_t(py) * 48 + px;
            uint32_t cls = (normal[i] >> 16) & 255;
            if (px < 20) redWest += lin[i * 4] > 3 * lin[i * 4 + 1] && lin[i * 4] > 0.2f;
            if (px > 28) {
                iceEast += cls == ATLAS_CLASS_TERRAIN && lin[i * 4 + 2] > lin[i * 4] && lin[i * 4 + 2] > 0.3f;
                waterEast += cls == ATLAS_CLASS_WATER;
            }
        }
    std::printf("  west (dry, red top): %zu of 960 px red; east (frozen pond): %zu ice px, %zu water px\n", redWest, iceEast, waterEast);
    CHECK(redWest > 900);
    CHECK(iceEast > 900 && waterEast == 0);
    atlas_destroy(ctx);
}

// Pixels the near field covers skip the far march entirely; the rest render exactly as without near depth.
static void testNearDepth(const char* raster) {
    std::printf("near-depth skip\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    if (atlas_preview_load_raster(ctx, raster) != ATLAS_OK) {
        std::printf("  SKIP: no raster\n");
        atlas_destroy(ctx);
        return;
    }
    double x = 0, z = 0, y = atlas_preview_height(ctx, x, z) + 60;
    for (int lod = 0; lod < 4; lod++) {
        int span = 256 << lod;
        atlas_preview_generate(ctx, lod, -2 * span, -2 * span, 3 * span, 3 * span);
    }
    double centre[3] = {x, y, z};
    atlas_update(ctx, centre, 0);
    const uint32_t W = 128, H = 72;
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_ATMOS;
    std::vector<float> base(W * H * 4), with(W * H * 4), nearDist(W * H);
    for (uint32_t py = 0; py < H; py++)
        for (uint32_t px = 0; px < W; px++) nearDist[py * W + px] = px < W / 2 ? 20.0f : 1e30f;  // left half covered
    AtlasFrameOutput o1{nullptr, nullptr, nullptr, base.data()};
    CHECK(atlas_render(ctx, &f, &o1) == ATLAS_OK);
    AtlasStats s1{};
    atlas_get_stats(ctx, &s1);
    f.flags |= ATLAS_FRAME_NEAR_DEPTH;
    f.nearDepth = nearDist.data();
    AtlasFrameOutput o2{nullptr, nullptr, nullptr, with.data()};
    CHECK(atlas_render(ctx, &f, &o2) == ATLAS_OK);
    AtlasStats s2{};
    atlas_get_stats(ctx, &s2);
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
    atlas_destroy(ctx);
}

// Caller geometry, preview dressing (atlas_upload_column_heights): the terrain sits where the caller says.
static void testColumnHeights(const char* raster) {
    std::printf("column heights upload\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    std::vector<float> surf(256 * 256), water(256 * 256, NAN);
    CHECK(atlas_upload_column_heights(ctx, 0, 0, 0, surf.data(), water.data()) == ATLAS_ERR_STATE);  // no raster
    if (atlas_preview_load_raster(ctx, raster) != ATLAS_OK) {
        std::printf("  SKIP: no raster\n");
        atlas_destroy(ctx);
        return;
    }
    // dry upland (the uploads below carry no water, the reference keeps its seas and rivers)
    double x = 1000.5, z = -700.5;
    for (int k = 0; k < 400; k++) {
        double h = atlas_preview_height(ctx, x, z);
        if (h > 120 && h < 250) break;
        x = 1000.5 + (k % 20) * 997.0;
        z = -700.5 + (k / 20) * 1013.0;
    }
    float h0 = atlas_preview_height(ctx, x, z);
    CHECK(atlas_has_tile(ctx, 0, int(std::floor(x / 256)), int(std::floor(z / 256))) == 0);
    for (int lod = 0; lod < 3; lod++) {
        int span = 256 << lod, s = 1 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        for (int tz = cz - 2; tz <= cz + 2; tz++)
            for (int tx = cx - 2; tx <= cx + 2; tx++) {
                // the preview's own ground, lifted 7 blocks, dry: what an exact source would hand over
                for (int j = 0; j < 256; j++)
                    for (int i = 0; i < 256; i++)
                        surf[j * 256 + i] = atlas_preview_height(ctx, tx * span + (i + 0.5) * s, tz * span + (j + 0.5) * s) + 7.0f;
                CHECK(atlas_upload_column_heights(ctx, lod, tx, tz, surf.data(), water.data()) == ATLAS_OK);
            }
    }
    CHECK(atlas_has_tile(ctx, 0, int(std::floor(x / 256)), int(std::floor(z / 256))) == 1);
    CHECK(atlas_upload_column_heights(ctx, 99, 0, 0, surf.data(), water.data()) == ATLAS_ERR_ARG);
    double y = h0 + 60;
    double centre[3] = {x, y, z};
    atlas_update(ctx, centre, 0);
    const uint32_t W = 33, H = 33;
    AtlasFrame f{};
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
    AtlasContext* ref = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ref);
    atlas_preview_load_raster(ref, raster);
    for (int lod = 0; lod < 3; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        atlas_preview_generate(ref, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    atlas_update(ref, centre, 0);
    std::vector<float> depth(W * H), refDepth(W * H);
    AtlasFrameOutput o{nullptr, nullptr, depth.data(), nullptr}, oref{nullptr, nullptr, refDepth.data(), nullptr};
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    CHECK(atlas_render(ref, &f, &oref) == ATLAS_OK);
    std::vector<float> lift;
    for (uint32_t i = 0; i < W * H; i++)
        if (depth[i] < 1e29f && refDepth[i] < 1e29f) lift.push_back(refDepth[i] - depth[i]);
    std::sort(lift.begin(), lift.end());
    float med = lift.empty() ? 0.0f : lift[lift.size() / 2];
    std::printf("  looking down from %.0f blocks: %zu px hit both, median lift %.2f blocks (expect 7)\n", y - h0,
                lift.size(), med);
    CHECK(lift.size() > W * H / 2);
    CHECK(std::abs(med - 7.0f) < 0.75f);
    atlas_destroy(ref);
    atlas_destroy(ctx);
}

// Biome tints over the dressing (atlas_upload_column_heights2): grass-family tops take the caller's material.
static void testGrassTint(const char* raster) {
    std::printf("grass tint\n");
    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    if (atlas_preview_load_raster(ctx, raster) != ATLAS_OK) {
        std::printf("  SKIP: no raster\n");
        atlas_destroy(ctx);
        return;
    }
    AtlasMaterial red{{0.6f, 0.02f, 0.02f}, 0.9f, 0.0f, ATLAS_CLASS_TERRAIN};
    CHECK(atlas_set_materials(ctx, 300, &red, 1) == ATLAS_OK);
    // a temperate lowland with grass (search the raster for one)
    double x = 0.5, z = 0.5;
    for (int k = 0; k < 400; k++) {
        double h = atlas_preview_height(ctx, x, z);
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
                    for (int i = 0; i < 256; i++) surf[size_t(j) * 256 + i] = atlas_preview_height(ctx, cx * 256 + i + 0.5, cz * 256 + j + 0.5);
                CHECK(atlas_upload_column_heights2(ctx, 0, cx, cz, surf.data(), water.data(), pass ? grass.data() : nullptr,
                                                    nullptr) == ATLAS_OK);
            }
        double h0 = atlas_preview_height(ctx, x, z);
        double centre[3] = {x, h0 + 80, z};
        atlas_update(ctx, centre, 0);
        AtlasFrame f{};
        for (int i = 0; i < 3; i++) f.cameraPos[i] = centre[i];
        f.width = f.height = 48;
        cameraMatrix(0, -89.9, 60, 1.0, f.invViewProj);
        f.maxDistance = 6000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<float> lin(48 * 48 * 4);
        AtlasFrameOutput o{nullptr, nullptr, nullptr, lin.data()};
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        size_t redPx = 0;
        for (size_t i = 0; i < 48 * 48; i++) redPx += lin[i * 4] > 3 * lin[i * 4 + 1] && lin[i * 4] > 0.01f;
        frac[pass] = double(redPx) / (48 * 48);
    }
    std::printf("  red-tinted pixels: %.0f%% without the override, %.0f%% with it\n", 100 * frac[0], 100 * frac[1]);
    CHECK(frac[0] < 0.01);
    CHECK(frac[1] > 0.1);  // the open grass of the view (forest and rock keep their own)
    atlas_destroy(ctx);
}

static bool parity(const char* raster) {
    std::printf("cpu/vulkan parity (preview terrain)\n");
    AtlasContext* cpu = nullptr;
    AtlasContext* gpu = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &gpu) != ATLAS_OK) {
        std::printf("  SKIP: no Vulkan device\n");
        return true;
    }
    atlas_create(ATLAS_BACKEND_CPU, &cpu);
    if (atlas_preview_load_raster(cpu, raster) != ATLAS_OK || atlas_preview_load_raster(gpu, raster) != ATLAS_OK) {
        std::printf("  SKIP: %s missing (run native/tools/export_raster.py)\n", raster);
        atlas_destroy(cpu);
        atlas_destroy(gpu);
        return true;
    }
    double x = 0, z = 0;
    double y = atlas_preview_height(cpu, x, z) + 60;
    double centre[3] = {x, y, z};
    // a row of fires out east (the far-light pass in both builds)
    std::vector<AtlasLight> fires;
    for (int i = 0; i < 40; i++)
        fires.push_back(AtlasLight{{x + 400.0 + i * 90.0, atlas_preview_height(cpu, x + 400.0 + i * 90.0, z + (i % 7 - 3) * 40.0) + 4.0,
                                     z + (i % 7 - 3) * 40.0}, 1.5f, 2.0f, 0x2080FFu, ATLAS_LIGHT_FIRE});
    for (AtlasContext* c : {cpu, gpu}) {
        for (int lod = 0; lod < 7; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
            atlas_preview_generate(c, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
        }
        atlas_update(c, centre, 0);
        atlas_set_light_group(c, 1, fires.data(), uint32_t(fires.size()));
    }
    const uint32_t W = 320, H = 180;
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY;
    std::vector<float> dc(W * H), dg(W * H);
    std::vector<uint32_t> cc(W * H), cg(W * H);
    AtlasFrameOutput oc{cc.data(), nullptr, dc.data()}, og{cg.data(), nullptr, dg.data()};
    CHECK(atlas_render(cpu, &f, &oc) == ATLAS_OK);
    CHECK(atlas_render(gpu, &f, &og) == ATLAS_OK);
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

    // the near light (abi 0.15, ATLAS_FRAME_NEAR_LIGHT): the far field's own surfaces handed back as the near field
    // (their distance, their face normal in the info texel), lit by atlas. CPU and Vulkan agree on it, and on land up
    // close (LOD 0, no smoothing) albedo x nearLight x T + inscatter is the far field's own colour there: one light
    {
        AtlasFrame fl = f;
        cameraMatrix(90, -40, 70, double(W) / H, fl.invViewProj);  // down onto the ground: most of it within 96 blocks
        size_t px = size_t(W) * H;
        std::vector<float> farC(px * 4), alb(px * 4), farD(px);
        std::vector<uint32_t> farN(px);
        AtlasFrameOutput of{nullptr, farN.data(), farD.data(), farC.data()};
        CHECK(atlas_render(cpu, &fl, &of) == ATLAS_OK);
        AtlasFrame fa = fl;
        fa.flags |= ATLAS_FRAME_DEBUG_ALBEDO;
        AtlasFrameOutput oa{nullptr, nullptr, nullptr, alb.data()};
        CHECK(atlas_render(cpu, &fa, &oa) == ATLAS_OK);
        std::vector<float> nd(px, 1e30f);
        std::vector<uint32_t> ni(px, 0);
        size_t covered = 0;
        for (size_t i = 0; i < px; i++)
            if (farD[i] < 96.0f && ((farN[i] >> 16) & 255) == ATLAS_CLASS_TERRAIN) {
                nd[i] = farD[i];
                ni[i] = (255u << 8) | ((farN[i] & 0xFFFFu) << 16);  // block 0, sky 15, the far field's normal
                covered++;
            }
        AtlasFrame fn = fl;
        fn.nearFieldEnd = 96;
        fn.flags |= ATLAS_FRAME_NEAR_DEPTH | ATLAS_FRAME_NEAR_LIGHT;
        fn.nearDepth = nd.data();
        fn.nearInfo = ni.data();
        std::vector<float> nlc(px * 4, -1.0f), nlg(px * 4, -1.0f), ccol(px * 4), gcol(px * 4);
        AtlasFrameOutput onc{nullptr, nullptr, nullptr, ccol.data(), nlc.data()};
        AtlasFrameOutput ong{nullptr, nullptr, nullptr, gcol.data(), nlg.data()};
        CHECK(atlas_render(cpu, &fn, &onc) == ATLAS_OK);
        CHECK(atlas_render(gpu, &fn, &ong) == ATLAS_OK);
        size_t buildsOff = 0, uncoveredLit = 0, unlit = 0;
        std::vector<double> rel;
        for (size_t i = 0; i < px; i++) {
            bool off = false;
            for (int k = 0; k < 4; k++) {
                double a = nlc[i * 4 + k], b = nlg[i * 4 + k];
                if (std::abs(a - b) > 0.02 + 0.02 * std::abs(a)) off = true;
            }
            buildsOff += off;
            if (nd[i] > 1e29f) {
                uncoveredLit += nlc[i * 4] != 0.0f || nlc[i * 4 + 3] != 0.0f;
                continue;
            }
            unlit += !(nlc[i * 4] > 0.0f);
            const float T = ccol[i * 4 + 3];
            double worst = 0;
            for (int k = 0; k < 3; k++) {
                double pred = double(alb[i * 4 + k]) * nlc[i * 4 + k] * T + ccol[i * 4 + k], want = farC[i * 4 + k];
                worst = std::max(worst, std::abs(pred - want) / std::max(std::abs(want), 1e-3));
            }
            rel.push_back(worst);
        }
        std::sort(rel.begin(), rel.end());
        double med = rel.empty() ? 1.0 : rel[rel.size() / 2], p90 = rel.empty() ? 1.0 : rel[rel.size() * 9 / 10];
        std::printf("  near light: %zu covered px; cpu/vulkan %.3f%% px differ; albedo x light x T + inscatter vs the far "
                    "field's colour: median %.2e, p90 %.2e relative; %zu covered px unlit, %zu uncovered px lit\n",
                    covered, 100.0 * buildsOff / n, med, p90, unlit, uncoveredLit);
        CHECK(covered > px / 3);
        CHECK(buildsOff / n < 0.01);
        CHECK(med < 0.005);
        CHECK(p90 < 0.05);
        CHECK(unlit == 0);
        CHECK(uncoveredLit == 0);
    }

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
        AtlasWeather wth{fld.data(), dyn.data(), fld.data(), dyn.data(), 0.0f};
        for (AtlasContext* c : {cpu, gpu}) {
            atlas_set_cloud_noise(c, noise.data());
            atlas_set_weather(c, &wth);
        }
        AtlasFrame fc = f;
        cameraMatrix(90, 6, 70, double(W) / H, fc.invViewProj);  // up into the deck
        fc.flags = (fc.flags | ATLAS_FRAME_CLOUDS) & ~ATLAS_FRAME_SKY;  // alpha = cloud opacity over the sky
        std::vector<float> lc(W * H * 4), lg(W * H * 4);
        AtlasFrameOutput ocl{nullptr, nullptr, nullptr, lc.data()}, ogl{nullptr, nullptr, nullptr, lg.data()};
        CHECK(atlas_render(cpu, &fc, &ocl) == ATLAS_OK);
        CHECK(atlas_render(gpu, &fc, &ogl) == ATLAS_OK);
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
        AtlasContext* fresh = nullptr;
        atlas_create(ATLAS_BACKEND_VULKAN, &fresh);
        atlas_preview_load_raster(fresh, raster);
        for (int lod = 0; lod < 7; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
            atlas_preview_generate(fresh, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
        }
        atlas_update(fresh, centre, 0);
        atlas_set_cloud_noise(fresh, noise.data());
        atlas_set_light_group(fresh, 1, fires.data(), uint32_t(fires.size()));
        atlas_set_weather(fresh, &wth);
        std::vector<float> lf(W * H * 4);
        AtlasFrameOutput olf{nullptr, nullptr, nullptr, lf.data()};
        auto freshRender = [&](const AtlasFrame& pose) {
            AtlasFrame bare = pose;
            bare.flags &= ~(ATLAS_FRAME_CLOUDS | ATLAS_FRAME_CLOUDS_QUARTER);
            CHECK(atlas_render(fresh, &bare, &olf) == ATLAS_OK);
            CHECK(atlas_render(fresh, &pose, &olf) == ATLAS_OK);
        };

        // a static camera converges to the fresh march exactly, then stays bit-stable
        std::vector<float> lt(W * H * 4), lt2(W * H * 4);
        AtlasFrameOutput olt{nullptr, nullptr, nullptr, lt.data()}, olt2{nullptr, nullptr, nullptr, lt2.data()};
        for (int i = 0; i < 6; i++) CHECK(atlas_render(gpu, &fc, &olt) == ATLAS_OK);
        for (int i = 0; i < 4; i++) CHECK(atlas_render(gpu, &fc, &olt2) == ATLAS_OK);
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
        AtlasFrame fm = fc;
        for (int i = 0; i <= 8; i++) {
            cameraMatrix(90 + i * 0.25, 6, 70, double(W) / H, fm.invViewProj);  // 0.25 degrees per frame
            CHECK(atlas_render(gpu, &fm, &olt) == ATLAS_OK);
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
        atlas_destroy(fresh);
    }
    atlas_destroy(cpu);
    atlas_destroy(gpu);
    return true;
}

// The error paths (atlas.h AtlasStatus): out of memory, a lost device and a hang, through the test hook
// (atlas_debug_fault / ATLAS_FAULT), on the CPU backend always and on Vulkan when a device is there; a fresh
// context after a loss (the mod's retry); the memory query; the clipmap size; worker exceptions never terminating.
static bool faultScene(AtlasContext* ctx, AtlasFrame& f) {
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -1; tz <= 1; tz++)
        for (int tx = -1; tx <= 1; tx++) {
            AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            if (atlas_upload_column_tile(ctx, &t) != ATLAS_OK) return false;
        }
    double centre[3] = {10.5, 140.0, 10.5};
    if (atlas_update(ctx, centre, 0) != ATLAS_OK) return false;
    f = AtlasFrame{};
    f.cameraPos[0] = 10.5;
    f.cameraPos[1] = 140.0;
    f.cameraPos[2] = 10.5;
    float inv[16] = {0.1f, 0, 0, 0, 0, 0, 0.1f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 32;
    f.height = 32;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    return true;
}

static void setEnv(const char* k, const char* v) {
#ifdef _WIN32
    _putenv_s(k, v ? v : "");
#else
    if (v) setenv(k, v, 1);
    else unsetenv(k);
#endif
}

static void testFaults() {
    std::printf("faults\n");
    std::vector<float> depth(32 * 32);
    AtlasFrameOutput o{nullptr, nullptr, depth.data(), nullptr};
    for (AtlasBackend be : {ATLAS_BACKEND_CPU, ATLAS_BACKEND_VULKAN}) {
        const char* name = be == ATLAS_BACKEND_CPU ? "cpu" : "vulkan";
        AtlasContext* ctx = nullptr;
        if (atlas_create(be, &ctx) != ATLAS_OK) {
            CHECK(be == ATLAS_BACKEND_VULKAN);
            std::printf("  vulkan: no device (%s): skipped\n", atlas_last_error(nullptr));
            continue;
        }
        AtlasFrame f;
        CHECK(faultScene(ctx, f));
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        CHECK(std::abs(depth[16 * 32 + 16] - 40.0f) < 0.5f);
        CHECK(atlas_debug_fault(ctx, 99, 0) == ATLAS_ERR_ARG);

        // out of memory: the armed frame fails, the context goes on
        CHECK(atlas_debug_fault(ctx, ATLAS_FAULT_OUT_OF_MEMORY, 1) == ATLAS_OK);
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_ERR_OUT_OF_MEMORY);
        CHECK(std::strstr(atlas_last_error(ctx), "OUT_OF_DEVICE_MEMORY") != nullptr);
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);

        // memory: Vulkan knows its heaps (and what it allocated); the CPU backend has none
        AtlasMemoryInfo mi{};
        AtlasStatus ms = atlas_memory_info(ctx, &mi);
        if (be == ATLAS_BACKEND_CPU) {
            CHECK(ms == ATLAS_ERR_UNSUPPORTED);
        } else {
            CHECK(ms == ATLAS_OK);
            CHECK(mi.deviceLocalBytes > (64ull << 20));
            CHECK(mi.atlasBytes > 0 && mi.atlasBytes < mi.deviceLocalBytes + mi.hostHeapBytes);
            if (mi.flags & ATLAS_MEMORY_BUDGET_EXT)
                CHECK(mi.deviceLocalBudget > 0 && mi.deviceLocalBudget <= mi.deviceLocalBytes);
            std::printf("  vulkan memory: device-local %.0f MB (budget %.0f MB, used %.0f MB, %s), host heap %.0f MB, "
                        "atlas %.1f MB%s\n",
                        mi.deviceLocalBytes / 1048576.0, mi.deviceLocalBudget / 1048576.0, mi.deviceLocalUsage / 1048576.0,
                        (mi.flags & ATLAS_MEMORY_BUDGET_EXT) ? "VK_EXT_memory_budget" : "no budget extension",
                        mi.hostHeapBytes / 1048576.0, mi.atlasBytes / 1048576.0, mi.integrated ? ", integrated" : "");
        }

        // a lost device: the frame fails, every later GPU call too; CPU-side uploads still work; destroy is clean
        CHECK(atlas_debug_fault(ctx, ATLAS_FAULT_DEVICE_LOST, 0) == ATLAS_OK);
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_ERR_DEVICE_LOST);
        CHECK(std::strstr(atlas_last_error(ctx), "DEVICE_LOST") != nullptr);
        CHECK(atlas_render(ctx, &f, &o) == ATLAS_ERR_DEVICE_LOST);
        CHECK(std::strstr(atlas_last_error(ctx), "lost earlier") != nullptr);
        AtlasGlTargets gt{};
        CHECK(atlas_gl_attach(ctx, 64, 64, &gt) == ATLAS_ERR_DEVICE_LOST);
        double centre[3] = {10.5, 140.0, 10.5};
        CHECK(atlas_update(ctx, centre, 0) == ATLAS_OK);
        CHECK(atlas_memory_info(ctx, &mi) == ATLAS_ERR_DEVICE_LOST);
        CHECK(std::string(atlas_info(ctx)).find("\"deviceLost\":true") != std::string::npos);
        atlas_gl_detach(ctx);
        atlas_destroy(ctx);

        // a hang: ATLAS_ERR_TIMEOUT, then dead the same way; destroy abandons the device instead of waiting
        ctx = nullptr;
        CHECK(atlas_create(be, &ctx) == ATLAS_OK);
        if (ctx) {
            CHECK(faultScene(ctx, f));
            CHECK(atlas_debug_fault(ctx, ATLAS_FAULT_TIMEOUT, 0) == ATLAS_OK);
            CHECK(atlas_render(ctx, &f, &o) == ATLAS_ERR_TIMEOUT);
            CHECK(atlas_render(ctx, &f, &o) == ATLAS_ERR_TIMEOUT);
            atlas_destroy(ctx);
        }

        // the retry: a new context renders again
        ctx = nullptr;
        CHECK(atlas_create(be, &ctx) == ATLAS_OK);
        if (ctx) {
            CHECK(faultScene(ctx, f));
            CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
            CHECK(std::abs(depth[16 * 32 + 16] - 40.0f) < 0.5f);
            atlas_destroy(ctx);
        }

        // ATLAS_FAULT arms every new context: device_lost after one good frame
        setEnv("ATLAS_FAULT", "device_lost@1");
        ctx = nullptr;
        CHECK(atlas_create(be, &ctx) == ATLAS_OK);
        setEnv("ATLAS_FAULT", nullptr);
        if (ctx) {
            CHECK(faultScene(ctx, f));
            CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
            CHECK(atlas_render(ctx, &f, &o) == ATLAS_ERR_DEVICE_LOST);
            atlas_destroy(ctx);
        }
        std::printf("  %s: out of memory recovers; device lost / timeout stay dead and destroy cleanly; a new context renders\n",
                    name);
    }

    // the clipmap: argument checks, and fewer levels keep fewer regions
    AtlasContext* ctx = nullptr;
    CHECK(atlas_create(ATLAS_BACKEND_CPU, &ctx) == ATLAS_OK);
    CHECK(atlas_set_clipmap(ctx, 0) == ATLAS_ERR_ARG);
    CHECK(atlas_set_clipmap(ctx, 8) == ATLAS_ERR_ARG);
    CHECK(atlas_set_clipmap(ctx, 1) == ATLAS_OK);
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    auto upload = [&] {
        for (int lod = 0; lod < 2; lod++) {
            AtlasColumnTile t{lod, 0, 0, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            CHECK(atlas_upload_column_tile(ctx, &t) == ATLAS_OK);
        }
    };
    upload();
    double centre[3] = {10.5, 140.0, 10.5};
    CHECK(atlas_update(ctx, centre, 0) == ATLAS_OK);
    AtlasStats st{};
    CHECK(atlas_get_stats(ctx, &st) == ATLAS_OK);
    uint32_t one = st.regions;
    CHECK(atlas_set_clipmap(ctx, 2) == ATLAS_OK);
    upload();
    CHECK(atlas_update(ctx, centre, 0) == ATLAS_OK);
    CHECK(atlas_get_stats(ctx, &st) == ATLAS_OK);
    CHECK(one > 0 && st.regions > one);
    atlas_destroy(ctx);

    // a worker's exception comes back to the caller (no std::terminate), after every worker stopped
    std::atomic<int> ran{0};
    bool caught = false;
    try {
        parallelRun([&] {
            if (ran.fetch_add(1) == 0) throw std::runtime_error("worker failed");
        });
    } catch (const std::runtime_error&) {
        caught = true;
    }
    CHECK(caught && ran.load() >= 1);
    std::printf("  clipmap levels 1 -> 2: %u -> %u regions; worker exceptions return to the caller\n", one, st.regions);
}

// The content-shaped groups (ATLAS_FRAME_GROUPS): a real section with a row of bottom slabs and a cut-out leaf cluster
// over flat ground. Straight down the slabs read at their real half height (the voxel path draws them as full blocks),
// the ground where both agree, the trees exist and are reported; then the same scene on the CPU and Vulkan builds.
static AtlasContext* groupScene(AtlasBackend be) {
    AtlasContext* ctx = nullptr;
    if (atlas_create(be, &ctx) != ATLAS_OK) return nullptr;
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 52), fill(256 * 256, 52);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    AtlasMaterial m[3] = {{{0.4f, 0.3f, 0.2f}, 0.9f, 0, ATLAS_CLASS_TERRAIN}, {{0.1f, 0.4f, 0.1f}, 0.9f, 0, ATLAS_CLASS_FOLIAGE},
                          {{0.3f, 0.3f, 0.3f}, 0.9f, 0, ATLAS_CLASS_TERRAIN}};
    atlas_set_materials(ctx, 50, m, 3);
    AtlasMaterialShape sh[3] = {};
    sh[0].occupancy = 0x00000000FFFFFFFFull;  // a bottom slab: micro rows y 0 and 1
    sh[1].occupancy = ~0ull;
    sh[1].flags = ATLAS_SHAPE_CUTOUT;
    sh[2].occupancy = ~0ull;
    for (int f = 0; f < 6; f++)
        for (int t = 0; t < 16; t++) {
            sh[0].faces[f][t] = 0xFF8A6A40u;
            sh[1].faces[f][t] = (t & 1) ? 0x80306020u : 0xFF306020u;  // half the texels half covered
            sh[2].faces[f][t] = 0xFF808080u;
        }
    CHECK(atlas_set_material_shapes(ctx, 50, sh, 3) == ATLAS_OK);
    CHECK(atlas_set_material_shapes(ctx, 0, sh, 1) == ATLAS_ERR_ARG);
    std::vector<uint16_t> sec(4096, 0);
    for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int wy = 96 + y;
                uint16_t v = wy < 100 ? 52 : (wy == 100 && x < 8) ? 50 : (wy >= 101 && wy <= 103 && x >= 8 && x <= 12 && z >= 4 && z <= 10) ? 51 : 0;
                sec[size_t(x + 16 * (z + 16 * y))] = v;
            }
    CHECK(atlas_upload_section(ctx, 0, 6, 0, sec.data()) == ATLAS_OK);
    double centre[3] = {3.5, 140.0, 3.5};
    atlas_update(ctx, centre, 0);
    return ctx;
}

static void testGroups() {
    std::printf("groups\n");
    AtlasContext* ctx = groupScene(ATLAS_BACKEND_CPU);
    CHECK(ctx != nullptr);
    if (!ctx) return;
    AtlasFrame f{};
    f.cameraPos[0] = 3.5;
    f.cameraPos[1] = 140.0;
    f.cameraPos[2] = 3.5;
    float inv[16] = {0.1f, 0, 0, 0, 0, 0, 0.1f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};  // straight down (testAbi)
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 64;
    f.height = 64;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> dOld(64 * 64), dGrp(64 * 64);
    AtlasFrameOutput o1{nullptr, nullptr, dOld.data()}, o2{nullptr, nullptr, dGrp.data()};
    CHECK(atlas_render(ctx, &f, &o1) == ATLAS_OK);
    f.flags = ATLAS_FRAME_GROUPS;
    CHECK(atlas_render(ctx, &f, &o2) == ATLAS_OK);  // asks for the trees: the next update builds them
    double centre[3] = {3.5, 140.0, 3.5};
    CHECK(atlas_update(ctx, centre, 0) == ATLAS_OK);
    CHECK(std::strstr(atlas_info(ctx), "\"groups\":{\"regions\":") != nullptr);
    CHECK(std::strstr(atlas_info(ctx), "\"groups\":{\"regions\":0,") == nullptr);
    CHECK(atlas_render(ctx, &f, &o2) == ATLAS_OK);
    double slabWorst = 0, groundWorst = 0;
    size_t slabs = 0, ground = 0;
    for (int py = 0; py < 64; py++)
        for (int px = 0; px < 64; px++) {
            float nx = (px + 0.5f) / 64 * 2 - 1, ny = 1 - (py + 0.5f) / 64 * 2;
            double len = std::sqrt(0.01 * nx * nx + 1 + 0.01 * ny * ny);
            double hx = 3.5 + nx * 0.1 * 40, hz = 3.5 + ny * 0.1 * 40;  // where the ray meets y = 100
            size_t i = size_t(py) * 64 + px;
            if (hx > 0.3 && hx < 7.7 && hz > 0.3 && hz < 7.7) {  // the slabs: top at 100.5
                slabWorst = std::max(slabWorst, std::abs(dGrp[i] - 39.5 * len));
                slabs++;
                // the other path: level 0 from the GPU-built grid (voxgrid.hpp), which draws the shapes too (the SVDAG
                // level 0 alone drew a full block)
                CHECK(std::abs(dOld[i] - 39.5 * len) < 0.02);
            } else if (hx < -0.3 && (hz < -0.3 || hz > 0.3)) {  // the ground, both paths
                groundWorst = std::max(groundWorst, double(std::abs(dGrp[i] - dOld[i])));
                ground++;
            }
        }
    std::printf("  slabs at their half height: worst %.4f blocks (%zu px); ground vs the voxel path: worst %.4f (%zu px)\n",
                slabWorst, slabs, groundWorst, ground);
    CHECK(slabs > 200 && ground > 50);
    CHECK(slabWorst < 0.02);
    CHECK(groundWorst < 0.02);
    atlas_destroy(ctx);

    // CPU and Vulkan through the same trees (a slanted view over the slabs and the leaves)
    AtlasContext* cpu = groupScene(ATLAS_BACKEND_CPU);
    AtlasContext* gpu = groupScene(ATLAS_BACKEND_VULKAN);
    if (!gpu) {
        std::printf("  SKIP parity: no Vulkan device\n");
        atlas_destroy(cpu);
        return;
    }
    const uint32_t W = 160, H = 120;
    AtlasFrame g{};
    g.cameraPos[0] = -6.0;
    g.cameraPos[1] = 108.0;
    g.cameraPos[2] = -6.0;
    cameraMatrix(135, -30, 70, double(W) / H, g.invViewProj);
    g.width = W;
    g.height = H;
    g.maxDistance = 1000;
    g.sunDir[1] = 1;
    g.lodBias = 1;
    g.flags = ATLAS_FRAME_GROUPS | ATLAS_FRAME_SKY;
    std::vector<float> dc(W * H), dg(W * H);
    std::vector<uint32_t> cc(W * H), cg(W * H);
    AtlasFrameOutput oc{cc.data(), nullptr, dc.data()}, og{cg.data(), nullptr, dg.data()};
    double c2[3] = {-6.0, 108.0, -6.0};
    for (AtlasContext* c : {cpu, gpu}) {
        atlas_render(c, &g, c == cpu ? &oc : &og);
        atlas_update(c, c2, 0);
    }
    CHECK(atlas_render(cpu, &g, &oc) == ATLAS_OK);
    CHECK(atlas_render(gpu, &g, &og) == ATLAS_OK);
    size_t mismatch = 0, colorBad = 0;
    for (size_t i = 0; i < dc.size(); i++) {
        if ((dc[i] < 1e29f) != (dg[i] < 1e29f) || (dc[i] < 1e29f && std::abs(dc[i] - dg[i]) > 0.05f)) mismatch++;
        for (int ch = 0; ch < 3; ch++)
            if (std::abs(int((cc[i] >> (8 * ch)) & 255) - int((cg[i] >> (8 * ch)) & 255)) > 3) {
                colorBad++;
                break;
            }
    }
    std::printf("  cpu/vulkan: hit or depth mismatch %.3f%%, colour outliers %.3f%%\n", 100.0 * mismatch / dc.size(),
                100.0 * colorBad / dc.size());
    CHECK(double(mismatch) / dc.size() < 0.005);
    CHECK(double(colorBad) / dc.size() < 0.01);
    atlas_destroy(cpu);
    atlas_destroy(gpu);
}

// Caves (docs/ATLAS-LOD.md "Underground"): a tunnel 30 blocks under flat ground (y 62..69, x -8..199, z -4..3) whose
// west end opens into a pit open to the sky (x -40..-9, z -12..11, from y 62 up). The scene's sections: the chunks'
// surface ones (16 under the ground to its top, as the server recorded before) always, and with `underground` the deep
// ones that are not solid rock (the client's feed and the server's recording now). Tiles: stone under the ground.
static bool caveAir(int x, int y, int z) {
    if (y < 62) return false;
    if (x >= -40 && x < -8 && z >= -12 && z < 12) return true;  // the pit
    return x >= -8 && x < 200 && y < 70 && z >= -4 && z < 4;     // the tunnel
}

static AtlasContext* caveScene(AtlasBackend be, bool underground) {
    AtlasContext* ctx = nullptr;
    if (atlas_create(be, &ctx) != ATLAS_OK) return nullptr;
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    std::vector<uint16_t> sec(4096);
    for (int cz = -1; cz <= 0; cz++)
        for (int cx = -3; cx <= 12; cx++)
            for (int sy = 3; sy <= 6; sy++) {
                bool air = false;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            int wx = cx * 16 + x, wy = sy * 16 + y, wz = cz * 16 + z;
                            // the tiles' own materials where solid (top, fill, stone), so only the cave differs
                            uint16_t m = wy >= 100 || caveAir(wx, wy, wz) ? 0 : wy == 99 ? 2 : wy >= 96 ? 3 : 1;
                            sec[size_t(x + 16 * (z + 16 * y))] = m;
                            air |= m == 0 && wy < 100;
                        }
                bool deep = sy * 16 + 16 <= 100 - 16;  // AtlasSections.deep: solid rock there is the tiles' stone
                if (deep && (!underground || !air)) continue;
                atlas_upload_section(ctx, cx, sy, cz, sec.data());
            }
    double centre[3] = {150.5, 65.5, 0.5};
    atlas_update(ctx, centre, 0);
    return ctx;
}

static void testCaves() {
    std::printf("caves\n");
    const uint32_t W = 160, H = 90;
    const size_t px = size_t(W) * H;
    AtlasContext* after = caveScene(ATLAS_BACKEND_CPU, true);
    AtlasContext* before = caveScene(ATLAS_BACKEND_CPU, false);
    CHECK(after && before);
    if (!after || !before) return;
    // a camera in the tunnel (eye y 65.5, z 0.5) at x, looking along it (yaw 270 west, 90 east); the near field's depth
    // and normal are the tunnel's surfaces as atlas traces them with its sections (every hit, as if all were loaded)
    struct View {
        AtlasFrame f{}, fn{};
        std::vector<float> farD, nd;
        std::vector<uint32_t> farN, ni;
        double x0 = 0, dirX[160 * 90] = {};
    };
    auto makeView = [&](View& v, double camX, double yaw, double fov) {
        const double pitch = -6, aspect = double(W) / H;
        v.x0 = camX;
        v.f.cameraPos[0] = camX;
        v.f.cameraPos[1] = 65.5;
        v.f.cameraPos[2] = 0.5;
        cameraMatrix(yaw, pitch, fov, aspect, v.f.invViewProj);
        v.f.width = W;
        v.f.height = H;
        v.f.maxDistance = 4000;
        v.f.lodBias = 1;
        // the sun high in the east: down the shaft onto its west wall and the west of its foot, nowhere in the tunnel
        double sl = std::sqrt(0.15 * 0.15 + 1.0);
        v.f.sunDir[0] = float(0.15 / sl);
        v.f.sunDir[1] = float(1.0 / sl);
        v.f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS;
        // per pixel: the view direction's x (cameraMatrix's basis), for the surfaces' world x
        double yr = yaw * 3.14159265358979323846 / 180, pr = pitch * 3.14159265358979323846 / 180;
        double fw[3] = {std::sin(yr) * std::cos(pr), std::sin(pr), -std::cos(yr) * std::cos(pr)};
        double rt[3] = {std::cos(yr), 0, std::sin(yr)};
        double up[3] = {rt[1] * fw[2] - rt[2] * fw[1], rt[2] * fw[0] - rt[0] * fw[2], rt[0] * fw[1] - rt[1] * fw[0]};
        double tf = std::tan(fov * 3.14159265358979323846 / 360);
        for (size_t i = 0; i < px; i++) {
            double nx = ((i % W) + 0.5) / W * 2 - 1, ny = 1 - ((i / W) + 0.5) / H * 2;
            double d[3], len = 0;
            for (int k = 0; k < 3; k++) {
                d[k] = fw[k] + rt[k] * nx * tf * aspect + up[k] * ny * tf;
                len += d[k] * d[k];
            }
            v.dirX[i] = d[0] / std::sqrt(len);
        }
        v.farD.assign(px, 0);
        v.farN.assign(px, 0);
        std::vector<float> farC(px * 4);
        AtlasFrameOutput of{nullptr, v.farN.data(), v.farD.data(), farC.data()};
        CHECK(atlas_render(after, &v.f, &of) == ATLAS_OK);
        size_t inTunnel = 0;
        v.nd.assign(px, 1e30f);
        v.ni.assign(px, 0);
        for (size_t i = 0; i < px; i++) {
            inTunnel += v.farD[i] < 200.0f;
            if (v.farD[i] < 1e29f) {
                v.nd[i] = v.farD[i];
                v.ni[i] = (255u << 8) | ((v.farN[i] & 0xFFFFu) << 16);
            }
        }
        CHECK(inTunnel > px * 9 / 10);  // the tunnel fills the view
        v.fn = v.f;
        v.fn.nearFieldEnd = 256;
        v.fn.flags |= ATLAS_FRAME_NEAR_DEPTH | ATLAS_FRAME_NEAR_LIGHT;
        v.fn.nearDepth = v.nd.data();
        v.fn.nearInfo = v.ni.data();
    };
    View* mouth = new View, * deep = new View, * along = new View;
    makeView(*mouth, 24.5, 270, 60);  // west, out of the mouth into the pit
    makeView(*deep, 40.5, 90, 70);    // east, down the tunnel
    makeView(*along, 150.5, 270, 70); // west from deep inside, 158 blocks to the mouth
    // the near light by the surface's x: the pit (open to the sky), the tunnel's first 24 blocks, deep
    struct Bin { double sun = 0, irr = 0, haze = 0; size_t n = 0; };
    double capped = 0, tunnelIrr = 0;  // what the resolve's cap by vanilla's sky light takes of atlas's light in the tunnel
    auto measure = [&](AtlasContext* c, const View& v, Bin bins[4]) {
        std::vector<float> nl(px * 4), col(px * 4);
        AtlasFrameOutput o{nullptr, nullptr, nullptr, col.data(), nl.data()};
        CHECK(atlas_render(c, &v.fn, &o) == ATLAS_OK);
        for (size_t i = 0; i < px; i++) {
            if (v.nd[i] > 1e29f) continue;
            double x = v.x0 + v.dirX[i] * v.nd[i];
            if (x >= -8 && x < 48) {
                // vanilla's sky light: 15 in the pit, one less a block into the tunnel (14 at x -8); the resolve's
                // open = smoothstep(0.05, 0.35, level / 15) scales atlas's light (above the ambient floor,
                // FAR_AMBIENT_FLOOR x LOOK_AMBIENT, which the resolve adds back as its own)
                double lv = std::max(0.0, 6.0 - std::floor(x)) / 15.0, s = std::clamp((lv - 0.05) / 0.3, 0.0, 1.0);
                double e = std::max(0.0, 0.2126 * nl[i * 4] + 0.7152 * nl[i * 4 + 1] + 0.0722 * nl[i * 4 + 2] - 3.0 * 0.01229);
                tunnelIrr += e;
                capped += e * (1.0 - s * s * (3.0 - 2.0 * s));
            }
            int b = x < -8 ? 0 : x < 16 ? 1 : x >= 48 ? 2 : -1;
            if (b < 0) continue;
            bins[b].sun += nl[i * 4 + 3];
            bins[b].irr += 0.2126 * nl[i * 4] + 0.7152 * nl[i * 4 + 1] + 0.0722 * nl[i * 4 + 2];
            bins[b].haze += 0.2126 * col[i * 4] + 0.7152 * col[i * 4 + 1] + 0.0722 * col[i * 4 + 2];
            bins[b].n++;
            if (b == 1 && x < 0) {  // (the mouth's first 8 blocks, also in the first 24)
                bins[3].irr += 0.2126 * nl[i * 4] + 0.7152 * nl[i * 4 + 1] + 0.0722 * nl[i * 4 + 2];
                bins[3].n++;
            }
        }
    };
    Bin a[4], bf[4];
    for (const View* v : {mouth, deep}) measure(after, *v, a);
    std::printf("  the resolve's sky-light cap takes %.1f%% of the light atlas traces in the tunnel's first 48 blocks "
                "(above the ambient floor)\n", tunnelIrr > 0 ? 100.0 * capped / tunnelIrr : 0.0);
    for (const View* v : {mouth, deep}) measure(before, *v, bf);
    for (Bin* bins : {a, bf})
        for (int b = 0; b < 4; b++)
            if (bins[b].n) bins[b].sun /= double(bins[b].n), bins[b].irr /= double(bins[b].n), bins[b].haze /= double(bins[b].n);
    const char* names[4] = {"pit (open)", "tunnel, first 24", "deep (x >= 48)", "the mouth, first 8"};
    for (int b = 0; b < 4; b++)
        std::printf("  near light, %-16s (%5zu px): sun %.3f / %.3f, irradiance %.4f / %.4f, inscatter %.5f / %.5f "
                    "(with / without the cave's sections)\n", names[b], a[b].n, a[b].sun, bf[b].sun, a[b].irr, bf[b].irr,
                    a[b].haze, bf[b].haze);
    CHECK(a[0].n > 100 && a[1].n > 100 && a[2].n > 1000);
    CHECK(a[2].sun == 0.0);               // no sun deep under rock
    CHECK(a[0].sun > 0.3);                // the sun in the pit
    // deep inside: the ambient floor alone (FAR_AMBIENT_FLOOR x LOOK_AMBIENT, 0.0369; 0.0616 before the bounce, when
    // the look's constant bounce scaled by that floor came on top), no sky, the walls the rays meet unlit
    CHECK(a[2].irr < 0.040);
    // the sky and the pit's sunlit floor through the mouth (one bounce), falling off into the tunnel
    CHECK(a[3].irr > 1.3 * a[2].irr);
    CHECK(a[1].irr > 1.1 * a[2].irr);
    CHECK(a[0].irr > 8.0 * a[2].irr);
    // without the cave's sections the pit's floor lies in the tiles' stone: atlas's world disagrees with the drawn one
    // there (nearLightAt: untrusted), and with the eye in the tunnel that is rock: the ambient floor alone
    CHECK(a[0].irr > 2.0 * bf[0].irr);
    CHECK(a[2].haze < 1e-6);              // under cover at both ends: no aerial inscatter

    // past the near field: the loaded chunks end 24 blocks out (the hand-off at 16); the far trace goes on down the
    // tunnel instead of starting in the tiles' stone
    const std::vector<float>& farD = along->farD;
    std::vector<float> nd2(px, 1e30f);
    for (size_t i = 0; i < px; i++)
        if (farD[i] < 24.0f) nd2[i] = farD[i];
    AtlasFrame fp = along->fn;
    fp.nearFieldEnd = 16;
    fp.nearDepth = nd2.data();
    for (AtlasContext* c : {after, before}) {
        std::vector<float> d(px), col(px * 4), nl(px * 4);
        AtlasFrameOutput o{nullptr, nullptr, d.data(), col.data(), nl.data()};
        CHECK(atlas_render(c, &fp, &o) == ATLAS_OK);
        size_t far = 0, match = 0;
        double lum = 0;
        for (size_t i = 0; i < px; i++) {
            if (nd2[i] < 1e29f || farD[i] > 1e29f) continue;
            far++;
            match += std::abs(d[i] - farD[i]) < 0.5f;
            lum += 0.2126 * col[i * 4] + 0.7152 * col[i * 4 + 1] + 0.0722 * col[i * 4 + 2];
        }
        std::printf("  past the near field (%s): %zu px, %.1f%% at the tunnel's own depth, mean colour %.5f\n",
                    c == after ? "with the cave's sections" : "surface sections only", far,
                    far ? 100.0 * match / far : 0.0, far ? lum / far : 0.0);
        if (c == after) {
            CHECK(far > px / 40);
            CHECK(match > far * 99 / 100);
        }
    }

    // CPU and Vulkan agree in the cave (the enclosed reach, the roof rays)
    AtlasContext* gpu = caveScene(ATLAS_BACKEND_VULKAN, true);
    if (gpu) {
        std::vector<float> nlc(px * 4), nlg(px * 4);
        AtlasFrameOutput oc{nullptr, nullptr, nullptr, nullptr, nlc.data()}, og{nullptr, nullptr, nullptr, nullptr, nlg.data()};
        CHECK(atlas_render(after, &mouth->fn, &oc) == ATLAS_OK);
        CHECK(atlas_render(gpu, &mouth->fn, &og) == ATLAS_OK);
        // (under cover one sky ray a pixel either meets the mouth or not, so a hair of float difference flips a pixel
        // whole: the pixels may differ a little more than in the open, the means not)
        size_t off = 0;
        double sc = 0, sg = 0;
        for (size_t i = 0; i < px; i++) {
            sc += nlc[i * 4] + nlc[i * 4 + 1] + nlc[i * 4 + 2];
            sg += nlg[i * 4] + nlg[i * 4 + 1] + nlg[i * 4 + 2];
            for (int k = 0; k < 4; k++)
                if (std::abs(nlc[i * 4 + k] - nlg[i * 4 + k]) > 0.02 + 0.02 * std::abs(nlc[i * 4 + k])) {
                    off++;
                    break;
                }
        }
        std::printf("  cpu/vulkan near light in the cave: %.3f%% px differ, mean irradiance %.5f / %.5f\n",
                    100.0 * off / px, sc / (3.0 * px), sg / (3.0 * px));
        CHECK(off < px / 50);
        // (1.5 %: the night's floor no longer pads both means, so the same sky-ray flips weigh a little more)
        CHECK(std::abs(sc - sg) < 0.015 * sc);
        atlas_destroy(gpu);
    } else {
        std::printf("  SKIP parity: no Vulkan device\n");
    }
    delete mouth;
    delete deep;
    delete along;
    atlas_destroy(after);
    atlas_destroy(before);
}

// An overhang (docs/ATLAS-RENDERER-PLAN.md "One bounce"): a stone slab two blocks thick over flat sunlit ground (x 0..7,
// z 0..7, y 104..105; the ground's top at y 99) and a wall standing in the open (x 20..21, z -8..7, y 100..107). The
// near light on the slab's underside (lit only by what the sunlit ground around it bounces up), on the wall's shaded
// face and on open ground, the near field's depth and normals being the surfaces atlas traces.
static uint16_t overhangMat(int x, int y, int z) {
    if (y >= 104 && y < 106 && x >= 0 && x < 8 && z >= 0 && z < 8) return 1;
    if (y >= 100 && y < 108 && x >= 20 && x < 22 && z >= -8 && z < 8) return 1;
    return y >= 100 ? 0 : y == 99 ? 2 : y >= 96 ? 3 : 1;
}

static AtlasContext* overhangScene(AtlasBackend be) {
    AtlasContext* ctx = nullptr;
    if (atlas_create(be, &ctx) != ATLAS_OK) return nullptr;
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    std::vector<uint16_t> sec(4096);
    for (int cz = -2; cz <= 2; cz++)
        for (int cx = -2; cx <= 3; cx++) {
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        sec[size_t(x + 16 * (z + 16 * y))] = overhangMat(cx * 16 + x, 96 + y, cz * 16 + z);
            atlas_upload_section(ctx, cx, 6, cz, sec.data());
        }
    double centre[3] = {8.5, 101.5, 4.5};
    atlas_update(ctx, centre, 0);
    return ctx;
}

static void testOverhang() {
    std::printf("overhang\n");
    const uint32_t W = 128, H = 96;
    const size_t px = size_t(W) * H;
    AtlasContext* cpu = overhangScene(ATLAS_BACKEND_CPU);
    CHECK(cpu != nullptr);
    if (!cpu) return;
    // which surface a pixel's hit lies on: 0 the slab's underside, 1 the wall's shaded face (west), 2 open ground
    auto classify = [](const double p[3], const double d[3]) {
        if (d[1] > 0 && std::abs(p[1] - 104.0) < 0.05 && p[0] > 0.5 && p[0] < 7.5 && p[2] > 0.5 && p[2] < 7.5) return 0;
        if (d[0] > 0 && std::abs(p[0] - 20.0) < 0.05 && p[1] > 100.5 && p[1] < 107.5 && p[2] > -7.5 && p[2] < 7.5) return 1;
        if (d[1] < 0 && std::abs(p[1] - 100.0) < 0.05 && (p[0] < -10 || p[0] > 26 || p[2] < -14 || p[2] > 14)) return 2;
        return -1;
    };
    struct Bin { double irr = 0, sun = 0; size_t n = 0; } bins[3];
    struct Cam { double pos[3], yaw, pitch, fov; } cams[3] = {
        {{3.9, 101.7, 4.1}, 0, 80, 60},      // under the slab, looking up at its underside
        {{14.5, 103.5, 0.5}, 90, 0, 40},     // west of the wall, looking at its shaded face
        {{50.5, 112.0, 30.5}, 0, -80, 60},   // over open ground
    };
    std::vector<AtlasFrame> nearFrames;
    std::vector<std::vector<float>> nds;
    std::vector<std::vector<uint32_t>> nis;
    for (const Cam& c : cams) {
        AtlasFrame f{};
        for (int k = 0; k < 3; k++) f.cameraPos[k] = c.pos[k];
        cameraMatrix(c.yaw, c.pitch, c.fov, double(W) / H, f.invViewProj);
        f.width = W;
        f.height = H;
        f.maxDistance = 4000;
        f.lodBias = 1;
        // the sun high in the south-east: the slab's shadow falls just beside it, the ground around it is lit
        double sd[3] = {0.3, 1.0, 0.2}, sl = std::sqrt(0.3 * 0.3 + 1.0 + 0.2 * 0.2);
        for (int k = 0; k < 3; k++) f.sunDir[k] = float(sd[k] / sl);
        f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS;
        std::vector<float> farD(px), farC(px * 4);
        std::vector<uint32_t> farN(px);
        AtlasFrameOutput of{nullptr, farN.data(), farD.data(), farC.data()};
        CHECK(atlas_render(cpu, &f, &of) == ATLAS_OK);
        std::vector<float> nd(px, 1e30f);
        std::vector<uint32_t> ni(px, 0);
        for (size_t i = 0; i < px; i++)
            if (farD[i] < 1e29f) {
                nd[i] = farD[i];
                ni[i] = (255u << 8) | ((farN[i] & 0xFFFFu) << 16);
            }
        f.nearFieldEnd = 256;
        f.flags |= ATLAS_FRAME_NEAR_DEPTH | ATLAS_FRAME_NEAR_LIGHT;
        nds.push_back(std::move(nd));
        nis.push_back(std::move(ni));
        nearFrames.push_back(f);
    }
    // the per-pixel view directions (cameraMatrix's basis), for the surfaces' positions
    auto pixelDirs = [&](const Cam& c, std::vector<double>& dirs) {
        double yr = c.yaw * 3.14159265358979323846 / 180, pr = c.pitch * 3.14159265358979323846 / 180;
        double fw[3] = {std::sin(yr) * std::cos(pr), std::sin(pr), -std::cos(yr) * std::cos(pr)};
        double rt[3] = {std::cos(yr), 0, std::sin(yr)};
        double up[3] = {rt[1] * fw[2] - rt[2] * fw[1], rt[2] * fw[0] - rt[0] * fw[2], rt[0] * fw[1] - rt[1] * fw[0]};
        double tf = std::tan(c.fov * 3.14159265358979323846 / 360), aspect = double(W) / H;
        dirs.assign(px * 3, 0);
        for (size_t i = 0; i < px; i++) {
            double nx = ((i % W) + 0.5) / W * 2 - 1, ny = 1 - ((i / W) + 0.5) / H * 2, len = 0;
            for (int k = 0; k < 3; k++) {
                dirs[i * 3 + k] = fw[k] + rt[k] * nx * tf * aspect + up[k] * ny * tf;
                len += dirs[i * 3 + k] * dirs[i * 3 + k];
            }
            for (int k = 0; k < 3; k++) dirs[i * 3 + k] /= std::sqrt(len);
        }
    };
    for (int v = 0; v < 3; v++) {
        AtlasFrame f = nearFrames[size_t(v)];
        f.nearDepth = nds[size_t(v)].data();
        f.nearInfo = nis[size_t(v)].data();
        std::vector<float> nl(px * 4), col(px * 4);
        AtlasFrameOutput o{nullptr, nullptr, nullptr, col.data(), nl.data()};
        CHECK(atlas_render(cpu, &f, &o) == ATLAS_OK);
        std::vector<double> dirs;
        pixelDirs(cams[v], dirs);
        for (size_t i = 0; i < px; i++) {
            if (nds[size_t(v)][i] > 1e29f) continue;
            double p[3];
            for (int k = 0; k < 3; k++) p[k] = cams[v].pos[k] + dirs[i * 3 + k] * nds[size_t(v)][i];
            int b = classify(p, &dirs[i * 3]);
            if (b != v) continue;
            bins[b].irr += 0.2126 * nl[i * 4] + 0.7152 * nl[i * 4 + 1] + 0.0722 * nl[i * 4 + 2];
            bins[b].sun += nl[i * 4 + 3];
            bins[b].n++;
        }
    }
    const char* names[3] = {"slab's underside", "wall, shaded face", "open ground"};
    for (Bin& b : bins) {
        if (b.n) b.irr /= double(b.n), b.sun /= double(b.n);
    }
    for (int b = 0; b < 3; b++)
        std::printf("  near light, %-17s (%5zu px): irradiance %.4f, sun %.3f\n", names[b], bins[b].n, bins[b].irr,
                    bins[b].sun);
    CHECK(bins[0].n > px / 2 && bins[1].n > px / 2 && bins[2].n > px / 2);
    CHECK(bins[0].sun < 0.01 && bins[1].sun < 0.01 && bins[2].sun > 0.99);
    // before the bounce (the sky rays that met a surface brought back nothing, the look's constant scaled by the sky
    // seen; ATLAS_ABLATE 524288), in the air's light (2026-10-20: the physical sun and sky, LOOK_AMBIENT 1; under the
    // old analytic sky and LOOK_AMBIENT 3 they were 0.1615 / 1.0842 / 4.4158 and the bounce 2.5x the underside):
    // 0.1613 / 0.5990 / 3.4625. The underside is lit by the ground (x1.8), the open land's light stays as it was
    CHECK(bins[0].irr > 1.6 * 0.1613);
    CHECK(bins[1].irr > 0.95 * 0.5990 && bins[1].irr < 1.15 * 0.5990);
    CHECK(std::abs(bins[2].irr - 3.4625) < 0.02 * 3.4625);
    atlas_destroy(cpu);
}

// A bounded slice of queued work (slice.hpp, World::packedDone): the front applied until the time is up, never fewer
// than the minimum, the rest kept in order.
static void testSlice() {
    std::printf("slice\n");
    std::vector<int> q(1000);
    for (int i = 0; i < 1000; i++) q[size_t(i)] = i;
    std::vector<int> got;
    int ticks = 0;
    size_t n = applySlice(q, 64, [&](int v) { got.push_back(v); }, [&] { return ++ticks > 100; });  // (time up after 100 checks)
    CHECK(n == 164);                    // 64 before the clock is read, then 100 more
    CHECK(got.size() == 164 && got.front() == 0 && got.back() == 163);
    CHECK(q.size() == 836 && q.front() == 164 && q.back() == 999);
    n = applySlice(q, 64, [&](int) {}, [] { return true; });  // (over at once: the minimum still goes)
    CHECK(n == 64 && q.front() == 228);
    n = applySlice(q, 0, [&](int) {}, [] { return false; });  // (time enough: all)
    CHECK(n == 772 && q.empty());
}

// Level 0's asks (vgask, VoxGrid's gpuStandby_): a section never asked is asked now; one asked lately is held back (the
// generator would remake it each batch); one asked long ago and never answered is asked again (a lost answer kept a hole
// in the near field for good, 2026-10-26).
static void testReask() {
    std::printf("reask\n");
    const uint64_t again = 240;
    CHECK(vgask::due(false, 0, 5, again));             // never asked
    CHECK(!vgask::due(true, 100, 101, again));         // asked a batch ago
    CHECK(!vgask::due(true, 100, 100 + again - 1, again));
    CHECK(vgask::due(true, 100, 100 + again, again));  // the answer never came: again
    CHECK(vgask::due(true, 0, 1000000, again));
}

// The GPU generator's far terrain into the grid (VoxGrid::ingestCoarseGpu, through atlas_debug_coarse_chunks): level-1
// chunks given whole over flat tiles (ground y 100) with no sections, the camera straight down from y 300 over the
// chunk at x, z 0..32, its row y 192..224. A pixel there needs level 0, which has nothing: the trace takes the grid's
// coarse levels behind the gap (traceLod), so it meets what was given. One id throughout: the chunk's top (y 224); its
// ids, rock in its lower half: y 208; air: the ground again (y 100).
static void testCoarseGiven() {
    std::printf("coarse\n");
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) {
        std::printf("  SKIP: no Vulkan device\n");
        return;
    }
    std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
    std::vector<uint16_t> top(256 * 256, 52), fill(256 * 256, 52);
    for (int tz = -2; tz <= 2; tz++)
        for (int tx = -2; tx <= 2; tx++) {
            AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            atlas_upload_column_tile(ctx, &t);
        }
    AtlasMaterial m[1] = {{{0.4f, 0.4f, 0.4f}, 0.9f, 0, ATLAS_CLASS_TERRAIN}};
    atlas_set_materials(ctx, 50, m, 1);
    double centre[3] = {16.0, 300.0, 16.0};
    atlas_update(ctx, centre, 0);
    AtlasFrame f{};
    f.cameraPos[0] = 16.0;
    f.cameraPos[1] = 300.0;
    f.cameraPos[2] = 16.0;
    float inv[16] = {0.1f, 0, 0, 0, 0, 0, 0.1f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};  // straight down (testAbi)
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 64;
    f.height = 64;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> depth(64 * 64);
    AtlasFrameOutput o{nullptr, nullptr, depth.data()};
    // the centre pixels' median depth after a few frames (the grid takes what was given with the next one)
    auto look = [&]() {
        for (int i = 0; i < 3; i++) CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
        std::vector<float> c;
        for (int y = 28; y < 36; y++)
            for (int x = 28; x < 36; x++) c.push_back(depth[size_t(y) * 64 + size_t(x)]);
        std::sort(c.begin(), c.end());
        return c[c.size() / 2];
    };
    double len = std::sqrt(1.0 + 0.01 * (0.5 / 32) * (0.5 / 32) * 2);  // (the centre rays are almost straight down)
    float d0 = look();
    int32_t pos[3] = {0, 8, 0};  // level 1: blocks 0..32, rows of 32 from y -64: y 192..224
    uint32_t kind = 2u | 50u << 8;
    std::vector<uint16_t> ids(4096, 0);
    uint8_t acc = 0;
    CHECK(atlas_debug_coarse_chunks(ctx, 1, 1, pos, &kind, ids.data(), &acc) == ATLAS_OK);
    CHECK(acc == 1);
    float d1 = look();
    for (int y = 0; y < 8; y++)
        for (int i = 0; i < 256; i++) ids[size_t(y) * 256 + size_t(i)] = 50;  // rock in the lower half: top y 208
    kind = 0u;
    CHECK(atlas_debug_coarse_chunks(ctx, 1, 1, pos, &kind, ids.data(), &acc) == ATLAS_OK);
    float d2 = look();
    kind = 1u;
    CHECK(atlas_debug_coarse_chunks(ctx, 1, 1, pos, &kind, ids.data(), &acc) == ATLAS_OK);
    float d3 = look();
    int32_t far[3] = {100000, 8, 0};  // outside the level's window: not taken
    CHECK(atlas_debug_coarse_chunks(ctx, 1, 1, far, &kind, ids.data(), &acc) == ATLAS_OK);
    CHECK(acc == 0);
    std::printf("  centre depth: ground %.2f, one id %.2f, its lower half %.2f, air %.2f (expect 200, 76, 92, 200)\n", d0, d1,
                d2, d3);
    CHECK(std::abs(d0 - 200.0 * len) < 0.6);
    CHECK(std::abs(d1 - 76.0 * len) < 0.6);
    CHECK(std::abs(d2 - 92.0 * len) < 0.6);
    CHECK(std::abs(d3 - 200.0 * len) < 0.6);
    atlas_destroy(ctx);
}

// The host's tiers (step 5; sectionpack.hpp, sectionstore.hpp), on Vulkan: uploads packed on the GPU (one id, 2, 3, 5,
// 17 and 300 ids: every width), the host's copy the packed one, the same ids back; a frame from packed sections (a
// jump away and back: the grid takes them from the warm tier, expanded on the GPU) the same as from the uploads; over
// the warm budget the sections go to the cold tier on disk (the regenerable one dropped) and come back from it, in this
// context and in a new one (a reload, no stream).
// the warm tier's spills are a spiller thread's (World::spiller, in half-millisecond slices: 83fe24ac): wait until it has
// dropped and spilled at least this many (atlas_info's tiers "dropped" / "spilled"), at most 5 s
static void waitSpills(AtlasContext* ctx, unsigned long long dropped, unsigned long long spilled) {
    auto num = [](const std::string& inf, const char* key) {
        size_t at = inf.find(key);
        return at == std::string::npos ? 0ull : std::strtoull(inf.c_str() + at + std::strlen(key), nullptr, 10);
    };
    for (int i = 0; i < 500; i++) {
        std::string inf = atlas_info(ctx);
        if (num(inf, "\"dropped\":") >= dropped && num(inf, "\"spilled\":") >= spilled) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

static void testTiers() {
    std::printf("tiers\n");
    namespace fs = std::filesystem;
    std::error_code ec;
    fs::path dir = fs::temp_directory_path(ec) / "atlas-tiers-test";
    fs::remove_all(dir, ec);
    auto tiles = [&](AtlasContext* ctx) {
        std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
        std::vector<uint16_t> top(256 * 256, 52), fill(256 * 256, 52);
        for (int tz = -2; tz <= 2; tz++)
            for (int tx = -2; tx <= 2; tx++) {
                AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
                atlas_upload_column_tile(ctx, &t);
            }
    };
    auto scene = [&](AtlasContext*& ctx) {
        if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) return false;
        tiles(ctx);
        std::vector<AtlasMaterial> m(400, AtlasMaterial{{0.4f, 0.4f, 0.4f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
        for (size_t i = 0; i < m.size(); i++) m[i].albedo[0] = float(i % 7) / 7.0f;
        atlas_set_materials(ctx, 1, m.data(), uint32_t(m.size()));
        return true;
    };
    AtlasContext* ctx = nullptr;
    if (!scene(ctx)) {
        std::printf("  SKIP: no Vulkan device\n");
        return;
    }
    // sections at sy 6 (y 96..112), x 0..6 sections, z 0: n distinct ids each (air in the upper half but for one id)
    const int counts[7] = {1, 2, 3, 5, 17, 300, 5};
    std::vector<std::vector<uint16_t>> secs(7, std::vector<uint16_t>(4096));
    for (int s = 0; s < 7; s++)
        for (int i = 0; i < 4096; i++) {
            int y = i >> 8, h = (i * 2654435761u) >> 20;
            int n = counts[s];
            secs[size_t(s)][size_t(i)] = n == 1 ? 50 : (y >= 8 && (h & 1)) ? 0 : uint16_t(1 + h % (n - 1 == 0 ? 1 : n - 1));
        }
    // (the cold tier from the start: what the window leaves goes to disk)
    CHECK(atlas_set_cache(ctx, dir.string().c_str(), 7, 1, 0, 0) == ATLAS_OK);
    for (int s = 0; s < 7; s++)
        CHECK(atlas_upload_section_flags(ctx, s, 6, 0, secs[size_t(s)].data(), s == 6 ? ATLAS_SECTION_REGENERABLE : 0u) == ATLAS_OK);
    double centre[3] = {56.0, 300.0, 8.0};
    atlas_update(ctx, centre, 0);
    AtlasFrame f{};
    f.cameraPos[0] = 56.0;
    f.cameraPos[1] = 300.0;
    f.cameraPos[2] = 8.0;
    float inv[16] = {0.4f, 0, 0, 0, 0, 0, 0.4f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};  // straight down, wide
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 64;
    f.height = 64;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> d1(64 * 64), d2(64 * 64);
    AtlasFrameOutput o1{nullptr, nullptr, d1.data()}, o2{nullptr, nullptr, d2.data()};
    for (int i = 0; i < 6; i++) CHECK(atlas_render(ctx, &f, &o1) == ATLAS_OK);  // (packed with a batch, read back later)
    std::vector<uint16_t> got(4096);
    int packed = 0, same = 0;
    for (int s = 0; s < 7; s++) {
        uint32_t tier = 0;
        CHECK(atlas_debug_section(ctx, s, 6, 0, got.data(), &tier) == ATLAS_OK);
        packed += tier == 2u;
        same += got == secs[size_t(s)];
    }
    CHECK(packed == 7 && same == 7);
    // a jump away and back: the sections left behind go to the cold tier (the regenerable one is dropped), and coming
    // back the grid takes them from it, packed, expanded on the GPU; the regenerable one the client makes again
    f.cameraPos[0] = 56.0 + 20000.0;
    double away[3] = {56.0 + 20000.0, 300.0, 8.0};
    atlas_update(ctx, away, 0);
    for (int i = 0; i < 3; i++) atlas_render(ctx, &f, &o2);
    waitSpills(ctx, 1, 6);  // (the six to disk, the regenerable one dropped: the spiller's, not this thread's)
    int awayTier = 0;
    for (int s = 0; s < 7; s++) {
        uint32_t tier = 0;
        CHECK(atlas_debug_section(ctx, s, 6, 0, got.data(), &tier) == ATLAS_OK);  // (a cold read: back into warm)
        awayTier += tier == 2u;
    }
    atlas_update(ctx, away, 0);  // (and out of warm again: still away)
    f.cameraPos[0] = 56.0;
    tiles(ctx);  // (the tiles there went with the jump)
    CHECK(atlas_upload_section_flags(ctx, 6, 6, 0, secs[6].data(), ATLAS_SECTION_REGENERABLE) == ATLAS_OK);
    atlas_update(ctx, centre, 0);
    for (int i = 0; i < 12; i++) CHECK(atlas_render(ctx, &f, &o2) == ATLAS_OK);
    size_t hits = 0, differ = 0;
    for (size_t i = 0; i < d1.size(); i++) {
        hits += d1[i] < 250.0f;
        differ += std::abs(d1[i] - d2[i]) > 1e-3f;
    }
    std::string inf = atlas_info(ctx);
    size_t ch = inf.find("\"cold\":{");
    std::printf("  %s\n", ch == std::string::npos ? "no tiers" : inf.substr(ch, inf.find('}', ch) - ch + 1).c_str());
    // over the warm budget: out of warm (on disk already; the regenerable one dropped), and back from the cold tier
    CHECK(atlas_set_cache(ctx, dir.string().c_str(), 7, 1, 1, 0) == ATLAS_OK);
    atlas_update(ctx, centre, 0);
    waitSpills(ctx, 2, 0);  // (the regenerable one dropped again; the others on disk already)
    int cold = 0, gone = 0;
    for (int s = 0; s < 7; s++) {
        uint32_t tier = 0;
        CHECK(atlas_debug_section(ctx, s, 6, 0, got.data(), &tier) == ATLAS_OK);
        if (s == 6) gone += tier == 0u;
        else cold += tier == 2u && got == secs[size_t(s)];
    }
    atlas_destroy(ctx);
    // a new context, the same world: read back from disk, nothing streamed
    AtlasContext* again = nullptr;
    int reloaded = 0;
    if (scene(again)) {
        CHECK(atlas_set_cache(again, dir.string().c_str(), 7, 1, 0, 0) == ATLAS_OK);
        atlas_update(again, centre, 0);
        CHECK(atlas_render(again, &f, &o2) == ATLAS_OK);  // (the plan: the cache applied)
        for (int s = 0; s < 6; s++) {
            uint32_t tier = 0;
            CHECK(atlas_debug_section(again, s, 6, 0, got.data(), &tier) == ATLAS_OK);
            reloaded += tier == 2u && got == secs[size_t(s)];
        }
        atlas_destroy(again);
    }
    std::printf("  packed on the GPU %d of 7, the same ids %d; away: %d of 6 read back from disk; back: %zu of %zu hits "
                "differ; over budget: cold %d of 6, regenerable dropped %d; a new context read %d of 6 from disk\n",
                packed, same, awayTier, differ, hits, cold, gone, reloaded);
    CHECK(awayTier == 6);
    CHECK(hits > 500 && differ == 0);
    CHECK(cold == 6 && gone == 1 && reloaded == 6);
    fs::remove_all(dir, ec);
}

// Edits as layers over the generated base (sectionstore.hpp's EditLayer), on Vulkan: a client-generated section edited
// (its base on the host), a build in the air above the ground (a base of one id), an edit of a GPU-made section (its
// base packed on the GPU and read back: never the tile's stand-in), one whose base changes later (a generator change).
// Over the warm budget they go to disk as deltas; in a new context the air build is back on its own, the others once
// their bases come again (uploaded, ingested on the GPU), the changed one as it was (its fallback, flagged).
static void testEdits() {
    std::printf("edits\n");
    namespace fs = std::filesystem;
    std::error_code ec;
    fs::path dir = fs::temp_directory_path(ec) / "atlas-edits-test";
    fs::remove_all(dir, ec);
    auto scene = [&](AtlasContext*& ctx) {
        if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) return false;
        std::vector<float> surf(256 * 256, 100.0f), water(256 * 256, NAN);
        std::vector<uint16_t> top(256 * 256, 52), fill(256 * 256, 52);
        for (int tz = -2; tz <= 2; tz++)
            for (int tx = -2; tx <= 2; tx++) {
                AtlasColumnTile t{0, tx, tz, surf.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
                atlas_upload_column_tile(ctx, &t);
            }
        std::vector<AtlasMaterial> m(64, AtlasMaterial{{0.4f, 0.4f, 0.4f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
        atlas_set_materials(ctx, 1, m.data(), uint32_t(m.size()));
        return true;
    };
    AtlasContext* ctx = nullptr;
    if (!scene(ctx)) {
        std::printf("  SKIP: no Vulkan device\n");
        return;
    }
    // bases: A (1,6,0) the client's, G (3,6,0) the GPU's, M (5,6,0) the client's (changed later); ground to y 104
    auto base = [](int salt) {
        std::vector<uint16_t> s(4096);
        for (int i = 0; i < 4096; i++) {
            int y = i >> 8;
            uint32_t h = (uint32_t(i) + uint32_t(salt) * 7919u) * 2654435761u;
            s[size_t(i)] = y < 8 ? uint16_t(1 + (h >> 28) % 3) : 0;
        }
        return s;
    };
    std::vector<uint16_t> A = base(1), G = base(2), M = base(3), M2 = base(4), air(4096, 0);
    double centre[3] = {56.0, 170.0, 8.0};
    AtlasFrame f{};
    f.cameraPos[0] = 56.0;
    f.cameraPos[1] = 170.0;
    f.cameraPos[2] = 8.0;
    float inv[16] = {0.4f, 0, 0, 0, 0, 0, 0.4f, 0, 0, -0.5f, 0, 0, 0, -0.75f, 0, 1};
    std::memcpy(f.invViewProj, inv, sizeof(inv));
    f.width = 64;
    f.height = 64;
    f.maxDistance = 1000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<float> d(64 * 64);
    AtlasFrameOutput o{nullptr, nullptr, d.data()};
    CHECK(atlas_set_cache(ctx, dir.string().c_str(), 11, 1, 0, 0) == ATLAS_OK);
    atlas_update(ctx, centre, 0);
    CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);  // (the grid placed: the GPU's section has a window to go in)
    CHECK(atlas_upload_section_flags(ctx, 1, 6, 0, A.data(), ATLAS_SECTION_REGENERABLE) == ATLAS_OK);
    CHECK(atlas_upload_section_flags(ctx, 5, 6, 0, M.data(), ATLAS_SECTION_REGENERABLE) == ATLAS_OK);
    int32_t gp[3] = {3, 6, 0};
    uint8_t acc = 0;
    CHECK(atlas_debug_gpu_sections(ctx, 1, gp, G.data(), nullptr, &acc) == ATLAS_OK);
    for (int i = 0; i < 6; i++) CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);
    // the edits: a wall in each section (x 2..13 at z 5, y 4..11), the air build a pillar (y 136..143)
    std::vector<uint16_t> eA = A, eG = G, eM = M, eAir = air;
    auto edit = [&](int sx, int sy, int sz, std::vector<uint16_t>& want, uint16_t mat) {
        for (int y = 4; y < 12; y++)
            for (int x = 2; x < 14; x++) {
                CHECK(atlas_notify_block(ctx, sx * 16 + x, sy * 16 + y, sz * 16 + 5, mat) == ATLAS_OK);
                want[size_t(x + 16 * (5 + 16 * y))] = mat;
            }
    };
    edit(1, 6, 0, eA, 9);
    edit(3, 6, 0, eG, 10);
    edit(5, 6, 0, eM, 11);
    edit(1, 8, 0, eAir, 12);
    for (int i = 0; i < 8; i++) CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);  // (G's base packed and read back)
    std::vector<uint16_t> got(4096);
    auto is = [&](AtlasContext* c, int sx, int sy, int sz, const std::vector<uint16_t>& want) {
        uint32_t tier = 0;
        return atlas_debug_section(c, sx, sy, sz, got.data(), &tier) == ATLAS_OK && tier != 0 && got == want;
    };
    bool liveA = is(ctx, 1, 6, 0, eA), liveG = is(ctx, 3, 6, 0, eG), liveM = is(ctx, 5, 6, 0, eM), liveAir = is(ctx, 1, 8, 0, eAir);
    // over the warm budget: out to disk (the edited ones as deltas)
    for (int i = 0; i < 4; i++) CHECK(atlas_render(ctx, &f, &o) == ATLAS_OK);  // (packed into warm first)
    CHECK(atlas_set_cache(ctx, dir.string().c_str(), 11, 1, 1, 0) == ATLAS_OK);
    atlas_update(ctx, centre, 0);
    std::string inf = atlas_info(ctx);
    size_t e0 = inf.find("\"edits\":{");
    std::string edits = e0 == std::string::npos ? "none" : inf.substr(e0, inf.find('}', e0) - e0 + 1);
    atlas_destroy(ctx);
    // a new context: the air build on its own; A and G once their bases come again; M's base changed: its old section
    AtlasContext* again = nullptr;
    bool backAir = false, backA = false, backG = false, backM = false;
    std::string edits2 = "none";
    if (scene(again)) {
        CHECK(atlas_set_cache(again, dir.string().c_str(), 11, 1, 0, 0) == ATLAS_OK);
        atlas_update(again, centre, 0);
        CHECK(atlas_render(again, &f, &o) == ATLAS_OK);
        backAir = is(again, 1, 8, 0, eAir);
        CHECK(atlas_upload_section_flags(again, 1, 6, 0, A.data(), ATLAS_SECTION_REGENERABLE) == ATLAS_OK);
        CHECK(atlas_upload_section_flags(again, 5, 6, 0, M2.data(), ATLAS_SECTION_REGENERABLE) == ATLAS_OK);
        CHECK(atlas_debug_gpu_sections(again, 1, gp, G.data(), nullptr, &acc) == ATLAS_OK);
        for (int i = 0; i < 10; i++) CHECK(atlas_render(again, &f, &o) == ATLAS_OK);
        backA = is(again, 1, 6, 0, eA);
        backG = is(again, 3, 6, 0, eG);
        backM = is(again, 5, 6, 0, eM);
        std::string i2 = atlas_info(again);
        size_t e1 = i2.find("\"edits\":{");
        if (e1 != std::string::npos) edits2 = i2.substr(e1, i2.find('}', e1) - e1 + 1);
        atlas_destroy(again);
    }
    std::printf("  live: client-made %d, GPU-made %d, changing %d, air %d; on disk %s\n", liveA, liveG, liveM, liveAir,
                edits.c_str());
    std::printf("  a new context: air on its own %d, client-made over its base again %d, GPU-made %d, changed base kept %d; %s\n",
                backAir, backA, backG, backM, edits2.c_str());
    CHECK(liveA && liveG && liveM && liveAir);
    CHECK(backAir && backA && backG && backM);
    CHECK(edits2.find("\"baseMismatch\":1") != std::string::npos);
    fs::remove_all(dir, ec);
}

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);  // (each line as it comes: a suite stopped part way still shows where)
    // atlas_tests [raster] [only=a,b,..]: the suites named alone (svdag, caves, ..., parity); each prints
    // "TRACE done <name> <secs>s" when it ends, so a slow or runaway one is named
    const char* raster = "native/data/tamriel_raster.bin";
    std::string only;
    for (int i = 1; i < argc; i++) {
        if (std::strncmp(argv[i], "only=", 5) == 0) only = "," + std::string(argv[i] + 5) + ",";
        else raster = argv[i];
    }
    struct Suite {
        const char* name;
        std::function<void()> run;
    };
    const Suite suites[] = {
        {"svdag", testSvdag}, {"caves", testCaves}, {"overhang", testOverhang}, {"groups", testGroups}, {"abi", testAbi},
        {"streaminggap", testStreamingGap}, {"fourzones", testFourZones}, {"regionseam", testRegionSeam},
        {"structures", testStructures}, {"sunmap", testSunMap}, {"actors", testActors}, {"impostor", testImpostorShape},
        {"actormotion", testActorMotion}, {"transitions", testTransitions}, {"lights", testLights}, {"faults", testFaults},
        {"neardepth", [&] { testNearDepth(raster); }}, {"columnheights", [&] { testColumnHeights(raster); }},
        {"grasstint", [&] { testGrassTint(raster); }}, {"surfaceoverrides", [&] { testSurfaceOverrides(raster); }},
        {"parity", [&] { parity(raster); }}, {"coarse", testCoarseGiven}, {"reask", testReask}, {"slice", testSlice}, {"tiers", testTiers}, {"edits", testEdits},
    };
    for (const Suite& t : suites) {
        if (!only.empty() && only.find("," + std::string(t.name) + ",") == std::string::npos) continue;
        std::printf("TRACE begin %s\n", t.name);
        auto t0 = std::chrono::steady_clock::now();
        int before = failures;
        t.run();
        std::printf("TRACE done %s %.1fs (%d failures)\n", t.name,
                    std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(), failures - before);
    }
    std::printf(failures ? "FAILED (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
