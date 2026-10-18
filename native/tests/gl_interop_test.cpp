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
    // the game's own depth buffer: GL depth texture -> aether linearises it; plus copies into caller textures
    const double n0 = 0.05, f0 = 100000.0, fov = 70.0, aspect = double(W) / H;
    const double fy = 1.0 / std::tan(fov * 3.14159265358979323846 / 360.0), fxp = fy / aspect;
    float invProj[16] = {float(1 / fxp), 0, 0, 0, 0, float(1 / fy), 0, 0, 0, 0, 0, float((n0 - f0) / (2 * n0 * f0)),
                         0, 0, -1, float((n0 + f0) / (2 * n0 * f0))};
    std::vector<float> depthPx(size_t(W) * H);
    for (uint32_t yy = 0; yy < H; yy++)  // GL rows: yy = 0 is the bottom
        for (uint32_t xx = 0; xx < W; xx++) {
            double nx = (xx + 0.5) / W * 2 - 1, ny = (yy + 0.5) / H * 2 - 1;
            double vx = nx / fxp, vy = ny / fy, vz = -1.0, l = std::sqrt(vx * vx + vy * vy + 1.0);
            double dist = 20.0, zv = vz * dist / l;  // view-space z of the point 20 blocks along this pixel's ray
            double zn = ((f0 + n0) / (n0 - f0) * zv + 2 * f0 * n0 / (n0 - f0)) / -zv;
            depthPx[size_t(yy) * W + xx] = xx < W / 2 ? float(zn * 0.5 + 0.5) : 1.0f;
        }
    GLuint depthTex = 0, dst[3] = {};
    gl.CreateTextures(GL_TEXTURE_2D, 1, &depthTex);
    gl.TextureStorage2D(depthTex, 1, GL_DEPTH_COMPONENT32F, GLsizei(W), GLsizei(H));
    gl.TextureSubImage2D(depthTex, 0, 0, 0, GLsizei(W), GLsizei(H), GL_DEPTH_COMPONENT, GL_FLOAT, depthPx.data());
    const GLenum dstFmt[3] = {GL_RGBA16F, GL_RGBA8, GL_R32F};
    for (int i = 0; i < 3; i++) {
        gl.CreateTextures(GL_TEXTURE_2D, 1, &dst[i]);
        gl.TextureStorage2D(dst[i], 1, dstFmt[i], GLsizei(W), GLsizei(H));
    }
    AetherGlFrameIO io{};
    io.nearDepthTex = depthTex;
    std::memcpy(io.nearInvProjection, invProj, sizeof(invProj));
    io.dstColor = dst[0];
    io.dstNormal = dst[1];
    io.dstDepth = dst[2];
    AetherFrame fd = f;  // no NEAR_DEPTH flag: the io depth texture turns it on
    for (int i = 0; i < 3; i++)
        if (aether_gl_render_io(ctx, &fd, &io) != AETHER_OK) {
            std::printf("FAIL gl render io: %s\n", aether_last_error(ctx));
            return 1;
        }
    gl.Finish();
    gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c3 = compare(refColor, refDepth, glColor, glDepth, W, H);  // host ref: nearDist, left half at 20
    std::printf("GL depth buffer input + caller copies: %zu colour, %zu depth pixels differ from host\n", c3.color, c3.depth);
    // the same depth reached only through the bound draw framebuffer (how a shader pack's gbuffer target is read)
    GLuint fbo = 0;
    gl.CreateFramebuffers(1, &fbo);
    gl.NamedFramebufferTexture(fbo, GL_DEPTH_ATTACHMENT, depthTex, 0);
    gl.BindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
    AetherGlFrameIO iob = io;
    iob.nearDepthTex = AETHER_GL_BOUND_DEPTH;
    for (int i = 0; i < 3; i++) aether_gl_render_io(ctx, &fd, &iob);
    gl.BindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    gl.Finish();
    gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c4 = compare(refColor, refDepth, glColor, glDepth, W, H);
    std::printf("depth via the bound framebuffer: %zu colour, %zu depth pixels differ from host\n", c4.color, c4.depth);
    gl.DeleteFramebuffers(1, &fbo);
    // dynamic resolution: the window's depth at twice aether's size, the same left half covered, plus one covered
    // full-resolution column that covers only half of each aether pixel it falls in: that one must not be skipped
    std::vector<float> depth2(size_t(4) * W * H);
    for (uint32_t yy = 0; yy < 2 * H; yy++)
        for (uint32_t xx = 0; xx < 2 * W; xx++) {
            double nx = (xx + 0.5) / (2 * W) * 2 - 1, ny = (yy + 0.5) / (2 * H) * 2 - 1;
            double vx = nx / fxp, vy = ny / fy, l = std::sqrt(vx * vx + vy * vy + 1.0);
            double zv = -20.0 / l;
            double zn = ((f0 + n0) / (n0 - f0) * zv + 2 * f0 * n0 / (n0 - f0)) / -zv;
            bool covered = xx < W || xx == W + 9;
            depth2[size_t(yy) * 2 * W + xx] = covered ? float(zn * 0.5 + 0.5) : 1.0f;
        }
    GLuint depthTex2 = 0;
    gl.CreateTextures(GL_TEXTURE_2D, 1, &depthTex2);
    gl.TextureStorage2D(depthTex2, 1, GL_DEPTH_COMPONENT32F, GLsizei(2 * W), GLsizei(2 * H));
    gl.TextureSubImage2D(depthTex2, 0, 0, 0, GLsizei(2 * W), GLsizei(2 * H), GL_DEPTH_COMPONENT, GL_FLOAT, depth2.data());
    AetherGlFrameIO io2 = io;
    io2.nearDepthTex = depthTex2;
    for (int i = 0; i < 3; i++) aether_gl_render_io(ctx, &fd, &io2);
    gl.Finish();
    gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c5 = compare(refColor, refDepth, glColor, glDepth, W, H);
    std::printf("near depth at twice aether's resolution: %zu colour, %zu depth pixels differ from host\n", c5.color, c5.depth);
    gl.DeleteTextures(1, &depthTex2);
    gl.DeleteTextures(1, &depthTex);
    gl.DeleteTextures(3, dst);
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
              c3.color == 0 && c3.depth == 0 && c4.color == 0 && c4.depth == 0 && c5.color == 0 && c5.depth == 0 &&
              smooth;
    std::printf(ok ? "PASS\n" : "FAIL (gl error 0x%x)\n", e);
    return ok ? 0 : 1;
}
