// Vulkan -> OpenGL zero-copy bridge test: a hidden-window WGL context imports aether_core's shared images; frames
// rendered through the semaphores must read back from GL byte-identical to the host-path render of the same frame.
//   aether_gl_test [native/data]
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "camera.hpp"
#include "gl_api.hpp"

using namespace aether;

static HGLRC makeContext(HDC& dc) {
    WNDCLASSA wc{};
    wc.lpfnWndProc = DefWindowProcA;
    wc.hInstance = GetModuleHandleA(nullptr);
    wc.lpszClassName = "aether_gl_test";
    RegisterClassA(&wc);
    HWND wnd = CreateWindowA("aether_gl_test", "aether", WS_OVERLAPPEDWINDOW, 0, 0, 64, 64, nullptr, nullptr,
                             wc.hInstance, nullptr);
    dc = GetDC(wnd);
    PIXELFORMATDESCRIPTOR pfd{sizeof(pfd), 1, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER, PFD_TYPE_RGBA, 32};
    int pf = ChoosePixelFormat(dc, &pfd);
    SetPixelFormat(dc, pf, &pfd);
    HGLRC rc = wglCreateContext(dc);
    wglMakeCurrent(dc, rc);
    return rc;
}

int main(int argc, char** argv) {
    std::string data = argc > 1 ? argv[1] : "native/data";
    HDC dc;
    HGLRC rc = makeContext(dc);
    GlApi gl;
    std::string err;
    if (!rc || !gl.load(err)) {
        std::printf("SKIP: %s\n", err.c_str());
        return 0;
    }
    std::printf("GL: %s | %s\n", gl.GetString(GL_RENDERER), gl.GetString(GL_VERSION));

    AetherContext* ctx = nullptr;
    if (aether_create(AETHER_BACKEND_VULKAN, &ctx) != AETHER_OK) {
        std::printf("SKIP: no Vulkan backend\n");
        return 0;
    }
    if (aether_preview_load_raster(ctx, (data + "/tamriel_raster.bin").c_str()) != AETHER_OK) {
        std::printf("SKIP: %s\n", aether_last_error(ctx));
        return 0;
    }
    std::vector<uint32_t> noise(64 * 64 * 64);
    if (FILE* f = std::fopen((data + "/cloud_noise.bin").c_str(), "rb")) {
        if (std::fread(noise.data(), 4, noise.size(), f) == noise.size()) aether_set_cloud_noise(ctx, noise.data());
        std::fclose(f);
    }
    double x = 0, z = 0, y = std::fmax(aether_preview_height(ctx, x, z), 63.0) + 60;
    for (int lod = 0; lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        aether_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    double centre[3] = {x, y, z};
    aether_update(ctx, centre, 0);

    const uint32_t W = 960, H = 540;
    AetherFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    cameraMatrix(105, -3, 70, double(W) / H, f.invViewProj);
    f.width = W;
    f.height = H;
    f.maxDistance = 40000;
    f.planetRadius = 6371000;
    f.sunDir[0] = 0.55f;
    f.sunDir[1] = 0.5f;
    f.sunDir[2] = 0.66f;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_SHADOWS | AETHER_FRAME_ATMOS;  // clouds read a zero weather field here: off

    std::vector<uint32_t> refColor(W * H);
    std::vector<float> refDepth(W * H);
    AetherFrameOutput o{refColor.data(), nullptr, refDepth.data()};
    if (aether_render(ctx, &f, &o) != AETHER_OK) {
        std::printf("FAIL host render: %s\n", aether_last_error(ctx));
        return 1;
    }

    AetherGlTargets t{};
    if (aether_gl_attach(ctx, W, H, &t) != AETHER_OK) {
        std::printf("FAIL attach: %s\n", aether_last_error(ctx));
        return 1;
    }
    std::printf("attached: color %u normal %u depth %u (%ux%u)\n", t.color, t.normal, t.depth, t.width, t.height);

    // several frames: exercises the semaphore ping-pong and the external-queue ownership transfers
    const int FRAMES = 30;
    auto c0 = std::chrono::steady_clock::now();
    for (int i = 0; i < FRAMES; i++) {
        if (aether_gl_render(ctx, &f) != AETHER_OK) {
            std::printf("FAIL gl render %d: %s\n", i, aether_last_error(ctx));
            return 1;
        }
    }
    double cpuMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count() / FRAMES;
    gl.Finish();
    double wallMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count() / FRAMES;

    std::vector<uint32_t> glColor(W * H);
    std::vector<float> glDepth(W * H);
    gl.GetTextureImage(t.color, 0, GL_RGBA, GL_UNSIGNED_BYTE, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(t.depth, 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    GLenum e = gl.GetError();
    size_t colorDiff = 0, depthDiff = 0;
    for (uint32_t yy = 0; yy < H; yy++)
        for (uint32_t xx = 0; xx < W; xx++) {
            size_t a = size_t(yy) * W + xx, b = size_t(H - 1 - yy) * W + xx;  // GL rows are bottom-up
            if (refColor[a] != glColor[b]) colorDiff++;
            float ra = refDepth[a], gb = glDepth[b];
            if (!(ra == gb || (ra > 1e29f && gb > 1e29f))) depthDiff++;
        }
    AetherStats st{};
    aether_get_stats(ctx, &st);
    std::printf("%d frames: %.3f ms CPU per aether_gl_render (no GPU wait), %.2f ms wall incl. final glFinish, "
                "GPU %.2f ms/frame\n", FRAMES, cpuMs, wallMs, st.lastFrameMs);
    std::printf("GL readback vs host render: %zu colour and %zu depth pixels differ (gl error 0x%x)\n", colorDiff,
                depthDiff, e);
    aether_gl_detach(ctx);
    aether_destroy(ctx);
    wglMakeCurrent(nullptr, nullptr);
    wglDeleteContext(rc);
    bool ok = colorDiff == 0 && depthDiff == 0 && e == GL_NO_ERROR;
    std::printf(ok ? "PASS\n" : "FAIL\n");
    return ok ? 0 : 1;
}
