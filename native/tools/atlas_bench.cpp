// GPU timing of the game path: a hidden GL context, atlas_gl_render into the shared images (device-local outputs,
// no readback), median GPU time per feature set. The harness's host path reads every output back over PCIe, so
// its timings at high resolutions mostly measure the bus, not the kernel.
//   atlas_bench [w=2560 h=1440 frames=40 x=0 z=0 above=60 yaw=105 pitch=2]
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "camera.hpp"
#include "gl_context.hpp"

static double arg(int argc, char** argv, const char* k, double d) {
    for (int i = 1; i < argc; i++) {
        size_t n = std::strlen(k);
        if (std::strncmp(argv[i], k, n) == 0 && argv[i][n] == '=') return std::atof(argv[i] + n + 1);
    }
    return d;
}

int main(int argc, char** argv) {
    atlas_test::GlContext glc;
    std::string glErr;
    if (!glc.create(glErr)) {
        std::printf("no GL context: %s\n", glErr.c_str());
        return 1;
    }

    uint32_t W = uint32_t(arg(argc, argv, "w", 2560)), H = uint32_t(arg(argc, argv, "h", 1440));
    int frames = int(arg(argc, argv, "frames", 40));
    double x = arg(argc, argv, "x", 0), z = arg(argc, argv, "z", 0);
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK || atlas_preview_load_raster(ctx, "native/data/tamriel_raster.bin") != ATLAS_OK) {
        std::printf("setup failed\n");
        return 1;
    }
    std::vector<uint32_t> noise(64 * 64 * 64);
    if (FILE* f = std::fopen("native/data/cloud_noise.bin", "rb")) {
        if (std::fread(noise.data(), 4, noise.size(), f) == noise.size()) atlas_set_cloud_noise(ctx, noise.data());
        std::fclose(f);
    }
    // a fair-weather field everywhere (the harness's synthetic "fair" preset, simplified)
    std::vector<uint32_t> fld(128 * 128), dyn(128 * 128);
    for (int i = 0; i < 128 * 128; i++) {
        double c = 0.34 + 0.22 * std::sin(i % 128 * 0.21) * std::cos(i / 128 * 0.17);
        fld[i] = uint32_t(c * 255) | uint32_t(0.04 * 255) << 8 | 5u << 24;
        dyn[i] = uint32_t(240.0 / 1024 * 255) | uint32_t(120.0 / 1024 * 255) << 8 | uint32_t(0.1024 * 255) << 16;
    }
    AtlasWeather wth{fld.data(), dyn.data(), fld.data(), dyn.data(), 0.0f};
    atlas_set_weather(ctx, &wth);
    double y = std::fmax(atlas_preview_height(ctx, x, z), 63.0) + arg(argc, argv, "above", 60);
    for (int lod = 0; lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        atlas_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    double centre[3] = {x, y, z};
    atlas_update(ctx, centre, 0);
    AtlasGlTargets t{};
    if (atlas_gl_attach(ctx, W, H, &t) != ATLAS_OK) {
        std::printf("attach failed: %s\n", atlas_last_error(ctx));
        return 1;
    }
    std::printf("%s\n", atlas_info(ctx));
    AtlasFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    atlas::cameraMatrix(arg(argc, argv, "yaw", 105), arg(argc, argv, "pitch", 2), 70, double(W) / H, f.invViewProj);
    f.width = W;
    f.height = H;
    f.nearFieldEnd = float(arg(argc, argv, "near", 0));
    f.maxDistance = 40000;
    f.planetRadius = 6371000;
    f.sunDir[0] = 0.55f;
    f.sunDir[1] = 0.5f;
    f.sunDir[2] = 0.66f;
    f.lodBias = float(arg(argc, argv, "bias", 1));
    if (int sm = int(arg(argc, argv, "sunmap", 0))) atlas_set_sun_map(ctx, uint32_t(sm), float(arg(argc, argv, "sunextent", 2048)));
    // lights=N: a night scene with N town lights scattered over the terrain ahead (lit windows, lamps), timed with and
    // without them (the far-light pass's cost)
    int nLights = int(arg(argc, argv, "lights", 0));
    std::vector<AtlasLight> lights;
    if (nLights > 0) {
        f.sunDir[0] = 0.55f;
        f.sunDir[1] = -0.45f;
        f.sunDir[2] = 0.7f;
        double yaw = arg(argc, argv, "yaw", 105) * 3.14159265 / 180;
        uint32_t h = 12345;
        auto rnd = [&] {
            h = h * 1664525u + 1013904223u;
            return double(h >> 8) / double(1 << 24);
        };
        for (int i = 0; i < nLights; i++) {
            // towns: clusters of 40 around random centres 0.5 .. 12 km ahead
            if (i % 40 == 0) {
                double d = 500 + 11500 * rnd(), a = yaw + (rnd() - 0.5) * 1.2;
                lights.push_back({{x + std::sin(a) * d, 0, z - std::cos(a) * d}, 1.5f, 0.4f, 0x58A8FFu, ATLAS_LIGHT_NIGHT});
            } else {
                const AtlasLight& c = lights[size_t(i / 40) * 40];
                lights.push_back({{c.pos[0] + (rnd() - 0.5) * 160, 0, c.pos[2] + (rnd() - 0.5) * 160}, 1.5f, 0.4f, 0x58A8FFu,
                                  ATLAS_LIGHT_NIGHT});
            }
            AtlasLight& l = lights.back();
            l.pos[1] = std::fmax(atlas_preview_height(ctx, l.pos[0], l.pos[2]), 63.0) + 3.0;
        }
    }
    struct Set {
        const char* name;
        uint32_t flags;
        bool lights;
    } sets[] = {{"terrain, flat normals", ATLAS_FRAME_ATMOS | ATLAS_FRAME_FLAT_NORMALS, false},
                {"terrain", ATLAS_FRAME_ATMOS, false},
                {"terrain+shadows", ATLAS_FRAME_ATMOS | ATLAS_FRAME_SHADOWS, false},
                {"terrain+shadows+lights", ATLAS_FRAME_ATMOS | ATLAS_FRAME_SHADOWS, true},
                {"terrain+shadows+clouds", ATLAS_FRAME_ATMOS | ATLAS_FRAME_SHADOWS | ATLAS_FRAME_CLOUDS, false},
                {"  clouds at quarter res", ATLAS_FRAME_ATMOS | ATLAS_FRAME_SHADOWS | ATLAS_FRAME_CLOUDS | ATLAS_FRAME_CLOUDS_QUARTER, false}};
    // bring the GPU to its boost clock first: short runs otherwise time the clock ramp
    f.flags = ATLAS_FRAME_ATMOS | ATLAS_FRAME_SHADOWS;
    for (int i = 0; i < 250; i++) atlas_gl_render(ctx, &f);
    std::printf("%ux%u, camera (%.0f, %.0f, %.0f), sets interleaved\n", W, H, x, y, z);
    const int NS = int(sizeof(sets) / sizeof(sets[0]));
    std::vector<std::vector<double>> gpu(NS), cpu(NS);
    // interleaved so every set sees the same clocks and background load. A render reports the GPU time of the frame
    // that finished before it (two behind), and a set's first frame after a switch has no cloud history, so each set renders five frames and keeps the last report.
    for (int i = 0; i < frames; i++)
        for (int k = 0; k < NS; k++) {
            if (sets[k].lights && lights.empty()) continue;
            f.flags = sets[k].flags;
            atlas_set_light_group(ctx, 1, sets[k].lights ? lights.data() : nullptr, sets[k].lights ? uint32_t(lights.size()) : 0u);
            for (int r = 0; r < 4; r++) atlas_gl_render(ctx, &f);
            auto c0 = std::chrono::steady_clock::now();
            atlas_gl_render(ctx, &f);
            cpu[k].push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count());
            AtlasStats st{};
            atlas_get_stats(ctx, &st);
            gpu[k].push_back(st.lastFrameMs);
        }
    for (int k = 0; k < NS; k++) {
        if (gpu[k].empty()) continue;
        std::sort(gpu[k].begin(), gpu[k].end());
        std::sort(cpu[k].begin(), cpu[k].end());
        // CPU: the atlas_gl_render call itself (zero-copy: recording; host-copy: plus the wait and the uploads)
        std::printf("  %-24s GPU median %6.2f ms  (p10 %.2f, p90 %.2f)  CPU per call median %6.2f ms\n", sets[k].name,
                    gpu[k][gpu[k].size() / 2], gpu[k][gpu[k].size() / 10], gpu[k][gpu[k].size() * 9 / 10],
                    cpu[k][cpu[k].size() / 2]);
    }
    std::printf("%s\n", atlas_info(ctx));
    atlas_gl_detach(ctx);
    atlas_destroy(ctx);
    glc.destroy();
    return 0;
}
