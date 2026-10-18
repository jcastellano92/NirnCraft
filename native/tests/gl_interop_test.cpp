// Vulkan -> OpenGL zero-copy bridge test: a hidden-window WGL context imports aether_core's shared images; frames
// rendered through the semaphores must read back from GL matching the host-path render of the same frame (colour
// to half-float precision, depth exactly). GL also writes the near field's distances into the shared nearDepth
// image, and the covered pixels must come back empty.
//   aether_gl_test [native/data]
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
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

struct Compare {
    size_t color = 0, depth = 0;
    double worstRel = 0;
};

// host frame (rows top-down) vs GL readback (rows bottom-up)
static Compare compare(const std::vector<float>& refC, const std::vector<float>& refD, const std::vector<float>& glC,
                       const std::vector<float>& glD, uint32_t W, uint32_t H) {
    Compare c;
    for (uint32_t y = 0; y < H; y++)
        for (uint32_t x = 0; x < W; x++) {
            size_t a = size_t(y) * W + x, b = size_t(H - 1 - y) * W + x;
            bool bad = false;
            for (int k = 0; k < 4; k++) {
                float r = refC[a * 4 + k], g = glC[b * 4 + k];
                double err = std::fabs(double(r) - g);
                double tol = std::fmax(1e-3, std::fabs(r) * 1e-3);  // half float: 11-bit mantissa
                if (err > tol) bad = true;
                if (std::fabs(r) > 1e-3) c.worstRel = std::fmax(c.worstRel, err / std::fabs(r));
            }
            c.color += bad;
            float ra = refD[a], gb = glD[b];
            if (!(ra == gb || (ra > 1e29f && gb > 1e29f))) c.depth++;
        }
    return c;
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
    f.nearFieldEnd = 96;
    f.maxDistance = 40000;
    f.planetRadius = 6371000;
    f.sunDir[0] = 0.55f;
    f.sunDir[1] = 0.5f;
    f.sunDir[2] = 0.66f;
    f.lodBias = 1;
    f.flags = AETHER_FRAME_SHADOWS | AETHER_FRAME_ATMOS | AETHER_FRAME_SKY;  // weather is zero here: no clouds

    std::vector<float> refColor(size_t(W) * H * 4), refDepth(size_t(W) * H);
    AetherFrameOutput o{nullptr, nullptr, refDepth.data(), refColor.data()};
    if (aether_render(ctx, &f, &o) != AETHER_OK) {
        std::printf("FAIL host render: %s\n", aether_last_error(ctx));
        return 1;
    }

    AetherGlTargets t{};
    if (aether_gl_attach(ctx, W, H, &t) != AETHER_OK) {
        std::printf("FAIL attach: %s\n", aether_last_error(ctx));
        return 1;
    }
    std::printf("attached: color %u normal %u depth %u nearDepth %u (%ux%u)\n", t.color, t.normal, t.depth,
                t.nearDepth, t.width, t.height);

    // several frames: exercises both frame slots, the semaphore ping-pong and the queue ownership transfers
    const int FRAMES = 60;
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
    std::vector<float> glColor(size_t(W) * H * 4), glDepth(size_t(W) * H);
    gl.GetTextureImage(t.color, 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(t.depth, 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c1 = compare(refColor, refDepth, glColor, glDepth, W, H);
    AetherStats st{};
    aether_get_stats(ctx, &st);
    std::printf("%d frames: %.3f ms CPU per aether_gl_render, %.2f ms wall incl. final glFinish, GPU %.2f ms/frame\n",
                FRAMES, cpuMs, wallMs, st.lastFrameMs);
    std::printf("GL readback vs host render: %zu colour (worst rel %.1e) and %zu depth pixels differ\n", c1.color,
                c1.worstRel, c1.depth);

    // near depth written on the GL side: the left half is covered by "near field" at 20 blocks
    float covered = 20.0f;
    gl.ClearTexSubImage(t.nearDepth, 0, 0, 0, 0, GLsizei(W / 2), GLsizei(H), 1, GL_RED, GL_FLOAT, &covered);
    std::vector<float> nearDist(size_t(W) * H);
    for (uint32_t yy = 0; yy < H; yy++)
        for (uint32_t xx = 0; xx < W; xx++) nearDist[size_t(yy) * W + xx] = xx < W / 2 ? covered : 1e30f;
    AetherFrame fn = f;
    fn.flags |= AETHER_FRAME_NEAR_DEPTH;
    fn.nearDepth = nearDist.data();
    AetherFrameOutput on{nullptr, nullptr, refDepth.data(), refColor.data()};
    if (aether_render(ctx, &fn, &on) != AETHER_OK) {
        std::printf("FAIL host near render: %s\n", aether_last_error(ctx));
        return 1;
    }
    fn.nearDepth = nullptr;  // the GL path reads the shared image
    for (int i = 0; i < 3; i++) aether_gl_render(ctx, &fn);
    gl.Finish();
    gl.GetTextureImage(t.color, 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(t.depth, 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c2 = compare(refColor, refDepth, glColor, glDepth, W, H);
    size_t coveredLit = 0;
    for (uint32_t yy = 0; yy < H; yy++)
        for (uint32_t xx = 0; xx < W / 2; xx++)
            coveredLit += glColor[(size_t(yy) * W + xx) * 4 + 3] != 0.0f;
    std::printf("near depth from GL: %zu covered pixels drawn; vs host: %zu colour, %zu depth pixels differ\n",
                coveredLit, c2.color, c2.depth);
    GLenum e = gl.GetError();

    // streaming flight: a worker streams tiles and rebuilds the clipmap while frames keep coming; staging must keep
    // the multi-megabyte scene uploads off the render thread (a render-thread upload shows as a 10+ ms frame)
    std::atomic<double> camX{x};
    std::atomic<bool> stop{false};
    std::atomic<int> rebuilds{0};
    std::thread worker([&] {
        while (!stop) {
            double cx = camX.load();
            for (int lod = 0; lod < 7; lod++) {
                int span = 256 << lod;
                int rx = int(std::floor(cx / span)), rz = int(std::floor(z / span));
                aether_preview_generate(ctx, lod, (rx - 2) * span, (rz - 2) * span, (rx + 3) * span, (rz + 3) * span);
            }
            double c[3] = {cx, y, z};
            aether_update(ctx, c, 0);
            rebuilds++;
        }
    });
    std::vector<double> frameMs;
    AetherFrame ff = f;
    for (int i = 0; i < 240; i++) {
        double px = x + i * 5.0;  // 300 blocks/s at 60 fps
        camX = px;
        ff.cameraPos[0] = px;
        auto t0 = std::chrono::steady_clock::now();
        if (aether_gl_render(ctx, &ff) != AETHER_OK) {
            std::printf("FAIL streaming render %d: %s\n", i, aether_last_error(ctx));
            stop = true;
            worker.join();
            return 1;
        }
        frameMs.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
        Sleep(8);  // the rest of a game frame
    }
    stop = true;
    worker.join();
    gl.Finish();
    std::vector<double> sorted = frameMs;
    std::sort(sorted.begin(), sorted.end());
    std::printf("streaming flight: %d clipmap rebuilds during 240 frames; aether_gl_render CPU median %.2f ms, "
                "p99 %.2f ms, max %.2f ms\n", rebuilds.load(), sorted[sorted.size() / 2], sorted[sorted.size() * 99 / 100],
                sorted.back());
    bool smooth = sorted[sorted.size() * 99 / 100] < 8.0;

    aether_gl_detach(ctx);
    aether_destroy(ctx);
    wglMakeCurrent(nullptr, nullptr);
    wglDeleteContext(rc);
    bool ok = c1.color == 0 && c1.depth == 0 && c2.color == 0 && c2.depth == 0 && coveredLit == 0 && e == GL_NO_ERROR &&
              smooth;
    std::printf(ok ? "PASS\n" : "FAIL (gl error 0x%x)\n", e);
    return ok ? 0 : 1;
}
