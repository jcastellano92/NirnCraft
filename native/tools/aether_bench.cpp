// GPU timing of the game path: a hidden GL context, aether_gl_render into the shared images (device-local outputs,
// no readback), median GPU time per feature set. The harness's host path reads every output back over PCIe, so
// its timings at high resolutions mostly measure the bus, not the kernel.
//   aether_bench [w=2560 h=1440 frames=40 x=0 z=0 above=60 yaw=105 pitch=2]
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "camera.hpp"

static double arg(int argc, char** argv, const char* k, double d) {
    for (int i = 1; i < argc; i++) {
        size_t n = std::strlen(k);
        if (std::strncmp(argv[i], k, n) == 0 && argv[i][n] == '=') return std::atof(argv[i] + n + 1);
    }
    return d;
}

int main(int argc, char** argv) {
    WNDCLASSA wc{};
    wc.lpfnWndProc = DefWindowProcA;
    wc.hInstance = GetModuleHandleA(nullptr);
    wc.lpszClassName = "aether_bench";
    RegisterClassA(&wc);
    HWND wnd = CreateWindowA("aether_bench", "aether", WS_OVERLAPPEDWINDOW, 0, 0, 64, 64, nullptr, nullptr, wc.hInstance, nullptr);
    HDC dc = GetDC(wnd);
    PIXELFORMATDESCRIPTOR pfd{sizeof(pfd), 1, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL, PFD_TYPE_RGBA, 32};
    SetPixelFormat(dc, ChoosePixelFormat(dc, &pfd), &pfd);
    HGLRC rc = wglCreateContext(dc);
    wglMakeCurrent(dc, rc);

    uint32_t W = uint32_t(arg(argc, argv, "w", 2560)), H = uint32_t(arg(argc, argv, "h", 1440));
    int frames = int(arg(argc, argv, "frames", 40));
    double x = arg(argc, argv, "x", 0), z = arg(argc, argv, "z", 0);
    AetherContext* ctx = nullptr;
    if (aether_create(AETHER_BACKEND_VULKAN, &ctx) != AETHER_OK || aether_preview_load_raster(ctx, "native/data/tamriel_raster.bin") != AETHER_OK) {
        std::printf("setup failed\n");
        return 1;
    }
    std::vector<uint32_t> noise(64 * 64 * 64);
    if (FILE* f = std::fopen("native/data/cloud_noise.bin", "rb")) {
        if (std::fread(noise.data(), 4, noise.size(), f) == noise.size()) aether_set_cloud_noise(ctx, noise.data());
        std::fclose(f);
    }
    // a fair-weather field everywhere (the harness's synthetic "fair" preset, simplified)
    std::vector<uint32_t> fld(128 * 128), dyn(128 * 128);
    for (int i = 0; i < 128 * 128; i++) {
        double c = 0.34 + 0.22 * std::sin(i % 128 * 0.21) * std::cos(i / 128 * 0.17);
        fld[i] = uint32_t(c * 255) | uint32_t(0.04 * 255) << 8 | 5u << 24;
        dyn[i] = uint32_t(240.0 / 1024 * 255) | uint32_t(120.0 / 1024 * 255) << 8 | uint32_t(0.1024 * 255) << 16;
    }
    AetherWeather wth{fld.data(), dyn.data(), fld.data(), dyn.data(), 0.0f};
    aether_set_weather(ctx, &wth);
    double y = std::fmax(aether_preview_height(ctx, x, z), 63.0) + arg(argc, argv, "above", 60);
    for (int lod = 0; lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        aether_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    double centre[3] = {x, y, z};
    aether_update(ctx, centre, 0);
    AetherGlTargets t{};
    if (aether_gl_attach(ctx, W, H, &t) != AETHER_OK) {
        std::printf("attach failed: %s\n", aether_last_error(ctx));
        return 1;
    }
    AetherFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    aether::cameraMatrix(arg(argc, argv, "yaw", 105), arg(argc, argv, "pitch", 2), 70, double(W) / H, f.invViewProj);
    f.width = W;
    f.height = H;
    f.nearFieldEnd = float(arg(argc, argv, "near", 0));
    f.maxDistance = 40000;
    f.planetRadius = 6371000;
    f.sunDir[0] = 0.55f;
    f.sunDir[1] = 0.5f;
    f.sunDir[2] = 0.66f;
    f.lodBias = float(arg(argc, argv, "bias", 1));
    struct Set {
        const char* name;
        uint32_t flags;
    } sets[] = {{"terrain, flat normals", AETHER_FRAME_ATMOS | AETHER_FRAME_FLAT_NORMALS},
                {"terrain", AETHER_FRAME_ATMOS},
                {"terrain+shadows", AETHER_FRAME_ATMOS | AETHER_FRAME_SHADOWS},
                {"terrain+shadows+clouds", AETHER_FRAME_ATMOS | AETHER_FRAME_SHADOWS | AETHER_FRAME_CLOUDS},
                {"  clouds at quarter res", AETHER_FRAME_ATMOS | AETHER_FRAME_SHADOWS | AETHER_FRAME_CLOUDS | AETHER_FRAME_CLOUDS_QUARTER}};
    // bring the GPU to its boost clock first: short runs otherwise time the clock ramp
    f.flags = AETHER_FRAME_ATMOS | AETHER_FRAME_SHADOWS;
    for (int i = 0; i < 250; i++) aether_gl_render(ctx, &f);
    std::printf("%ux%u, camera (%.0f, %.0f, %.0f), sets interleaved\n", W, H, x, y, z);
    const int NS = int(sizeof(sets) / sizeof(sets[0]));
    std::vector<std::vector<double>> gpu(NS);
    // interleaved so every set sees the same clocks and background load. A render reports the GPU time of the frame
    // that finished before it (two behind), so each set renders three frames in a row and keeps the third report.
    for (int i = 0; i < frames; i++)
        for (int k = 0; k < NS; k++) {
            f.flags = sets[k].flags;
            for (int r = 0; r < 3; r++) aether_gl_render(ctx, &f);
            AetherStats st{};
            aether_get_stats(ctx, &st);
            gpu[k].push_back(st.lastFrameMs);
        }
    for (int k = 0; k < NS; k++) {
        std::sort(gpu[k].begin(), gpu[k].end());
        std::printf("  %-24s GPU median %6.2f ms  (p10 %.2f, p90 %.2f)\n", sets[k].name, gpu[k][gpu[k].size() / 2],
                    gpu[k][gpu[k].size() / 10], gpu[k][gpu[k].size() * 9 / 10]);
    }
    aether_gl_detach(ctx);
    aether_destroy(ctx);
    return 0;
}
