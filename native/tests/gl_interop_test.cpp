// Vulkan -> OpenGL bridge test: a hidden GL context (WGL; EGL or GLX on Linux, see gl_context.hpp) imports
// aether_core's shared images; frames rendered through the semaphores must read back from GL matching the host-path
// render of the same frame (colour to half-float precision, depth exactly). GL also writes the near field's distances
// into the shared nearDepth image, and the covered pixels must come back empty. When the driver pair cannot share
// memory, aether falls back to host copies (AETHER_INTEROP=hostcopy forces it): the same frames must still match;
// the near-depth skip is off there (every pixel is marched, so the references are the plain renders).
//   aether_gl_test [native/data]

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "camera.hpp"
#include "gl_api.hpp"
#include "gl_context.hpp"

using namespace aether;


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
    aether_test::GlContext glc;
    GlApi gl;
    std::string err;
    if (!glc.create(err) || !gl.load(err)) {
        std::printf("SKIP: %s\n", err.c_str());
        return 0;
    }
    std::printf("GL (%s): %s | %s\n", glc.kind, gl.GetString(GL_RENDERER), gl.GetString(GL_VERSION));
    // a strict driver (Mesa) flags what a lenient one lets pass: no GL error may be left over at any step
    auto glCheck = [&](const char* where) {
        GLenum e = gl.GetError();
        if (e != GL_NO_ERROR) std::printf("  GL error 0x%x after %s\n", e, where);
        return e == GL_NO_ERROR;
    };
    bool glClean = true;

    AetherContext* ctx = nullptr;
    if (aether_create(AETHER_BACKEND_VULKAN, &ctx) != AETHER_OK) {
        std::printf("SKIP: no Vulkan backend (%s)\n", aether_last_error(nullptr));
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
    std::string info = aether_info(ctx);
    std::printf("info: %s\n", info.c_str());
    const bool host = info.find("\"interop\":\"host-copy\"") != std::string::npos;
    std::printf("mode: %s\n", host ? "host-copy" : "zero-copy");
    glClean &= glCheck("attach");
    // host copies march every pixel (no near-depth skip): the near-field references are the plain renders
    std::vector<float> plainColor = refColor, plainDepth = refDepth;

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
    if (host) std::printf("host-copy cost, last frame: %s\n", std::strstr(aether_info(ctx), "\"hostCopy\""));
    glClean &= glCheck("frames");
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
    if (host) {
        refColor = plainColor;
        refDepth = plainDepth;
    }
    fn.nearDepth = nullptr;  // the GL path reads the shared image
    for (int i = 0; i < 3; i++) aether_gl_render(ctx, &fn);
    gl.Finish();
    gl.GetTextureImage(t.color, 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(t.depth, 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c2 = compare(refColor, refDepth, glColor, glDepth, W, H);
    size_t coveredLit = 0;
    if (!host)
        for (uint32_t yy = 0; yy < H; yy++)
            for (uint32_t xx = 0; xx < W / 2; xx++)
                coveredLit += glColor[(size_t(yy) * W + xx) * 4 + 3] != 0.0f;
    glClean &= glCheck("near depth frames");
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
    glClean &= glCheck("depth-buffer frames");
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
    // the sun map through GL: copied into the caller's RG32F texture, identical to the device buffer
    const uint32_t SM = 128;
    aether_set_sun_map(ctx, SM, 512.0f);
    GLuint sunTex = 0;
    gl.CreateTextures(GL_TEXTURE_2D, 1, &sunTex);
    gl.TextureStorage2D(sunTex, 1, GL_RG32F, GLsizei(SM), GLsizei(SM + 1));
    AetherGlFrameIO io3 = io;
    io3.dstSunMap = sunTex;
    for (int i = 0; i < 3; i++) aether_gl_render_io(ctx, &fd, &io3);
    gl.Finish();
    std::vector<float> sunGl(size_t(SM) * (SM + 1) * 2), sunDev(sunGl.size());
    gl.GetTextureImage(sunTex, 0, GL_RG, GL_FLOAT, GLsizei(sunGl.size() * 4), sunGl.data());
    bool sunRead = aether_read_sun_map(ctx, sunDev.data(), uint32_t(sunDev.size())) == AETHER_OK;
    size_t sunOff = 0;
    for (size_t k = 0; k < sunGl.size(); k++)
        if (std::memcmp(&sunGl[k], &sunDev[k], 4) != 0) {  // bit-exact copies (a NaN never equals itself)
            if (sunOff < 4) std::printf("  sun map value %zu: GL %a, device %a\n", k, sunGl[k], sunDev[k]);
            sunOff++;
        }
    bool sunParams = sunGl[size_t(SM) * SM * 2 + 13] == 1.0f;  // the parameter row's valid flag
    std::printf("sun map via GL: %zu of %zu values differ from the device buffer; parameter row %s\n", sunOff,
                sunGl.size(), sunParams ? "present" : "MISSING");
    aether_set_sun_map(ctx, 0, 512.0f);
    gl.DeleteTextures(1, &sunTex);
    bool sunOk = sunRead && sunOff == 0 && sunParams;
    gl.DeleteTextures(1, &depthTex);
    gl.DeleteTextures(3, dst);
    GLenum e = gl.GetError();
    glClean &= e == GL_NO_ERROR;

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
        std::this_thread::sleep_for(std::chrono::milliseconds(8));  // the rest of a game frame
    }
    stop = true;
    worker.join();
    gl.Finish();
    std::vector<double> sorted = frameMs;
    std::sort(sorted.begin(), sorted.end());
    std::printf("streaming flight: %d clipmap rebuilds during 240 frames; aether_gl_render CPU median %.2f ms, "
                "p99 %.2f ms, max %.2f ms\n", rebuilds.load(), sorted[sorted.size() / 2], sorted[sorted.size() * 99 / 100],
                sorted.back());
    // host copies wait for the GPU inside the call, so only zero-copy is held to the 8 ms p99 (software drivers set
    // AETHER_TEST_SLOW=1: they take seconds per frame)
    const char* slow = std::getenv("AETHER_TEST_SLOW");
    bool smooth = host || (slow && *slow == '1') || sorted[sorted.size() * 99 / 100] < 8.0;
    glClean &= glCheck("streaming");

    aether_gl_detach(ctx);
    glClean &= glCheck("detach");
    aether_destroy(ctx);
    glc.destroy();
    bool ok = c1.color == 0 && c1.depth == 0 && c2.color == 0 && c2.depth == 0 && coveredLit == 0 && glClean &&
              c3.color == 0 && c3.depth == 0 && c4.color == 0 && c4.depth == 0 && c5.color == 0 && c5.depth == 0 && sunOk &&
              smooth;
    std::printf(ok ? "PASS\n" : "FAIL (gl error 0x%x)\n", e);
    return ok ? 0 : 1;
}
