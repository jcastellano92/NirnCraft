// Vulkan -> OpenGL bridge test: a hidden GL context (WGL; EGL or GLX on Linux, see gl_context.hpp) imports
// atlas_core's shared images; frames rendered through the semaphores must read back from GL matching the host-path
// render of the same frame (colour to half-float precision, depth exactly). GL also writes the near field's distances
// into the shared nearDepth image, and the covered pixels must come back empty. When the driver pair cannot share
// memory, atlas falls back to host copies (ATLAS_INTEROP=hostcopy forces it): the same frames must still match;
// the near-depth skip is off there (every pixel is marched, so the references are the plain renders), except with the
// near light (abi 0.15), whose frames read the near inputs back and match the host render in both modes.
// Two contexts, one after the other (gl_context.hpp): the platform's default one, then the production one, an OpenGL
// 3.3 core forward-compatible profile like Minecraft's. The second must pass too, and land on the same interop path as
// the first (zero-copy where the interop extensions exist, host-copy otherwise): a driver that drops an extension, or a
// GL 4.5-only call, in the 3.3 core profile would otherwise only show up in game.
// First, the CPU backend's GL path (no Vulkan device) in the 3.3 core context: synchronous and asynchronous frames must
// end on the host render of the same inputs; its cost at a 1080p window is printed (runCpuSuite).
//   atlas_gl_test [native/data] [--context=both|default|core33] [--backend=both|cpu|vulkan]

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

#include "atlas/atlas.h"
#include "camera.hpp"
#include "gl_api.hpp"
#include "gl_context.hpp"
static void setEnv(const char* k, const char* v);  // (below)

using namespace atlas;


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

enum Result { PASS, FAIL, SKIP };

static Result runSuite(const std::string& data, atlas_test::Profile profile, std::string& mode) {
    atlas_test::GlContext glc;
    GlApi gl;
    std::string err;
    if (!glc.create(err, profile) || !gl.load(err)) {
        std::printf("SKIP: %s\n", err.c_str());
        return SKIP;
    }
    std::printf("GL (%s): %s | %s\n", glc.kind, gl.GetString(GL_RENDERER), gl.GetString(GL_VERSION));
    if (profile == atlas_test::Profile::Core33) {
        // the context really is what the game runs on: core profile (no compatibility functions), forward-compatible
        const GLenum GL_CONTEXT_PROFILE_MASK_ = 0x9126, GL_CONTEXT_FLAGS_ = 0x821E;
        GLint mask = 0, flags = 0;
        gl.GetIntegerv(GL_CONTEXT_PROFILE_MASK_, &mask);
        gl.GetIntegerv(GL_CONTEXT_FLAGS_, &flags);
        while (gl.GetError() != GL_NO_ERROR) {}
        std::printf("context: OpenGL %d.%d, profile mask 0x%x (core %s), forward-compatible %s\n", gl.major, gl.minor,
                    unsigned(mask), mask & 0x1 ? "yes" : "NO", flags & 0x1 ? "yes" : "no");
        if (!(mask & 0x1)) {
            std::printf("FAIL: asked for a 3.3 core profile, got profile mask 0x%x\n", unsigned(mask));
            return FAIL;
        }
    }
    // a strict driver (Mesa) flags what a lenient one lets pass: no GL error may be left over at any step
    auto glCheck = [&](const char* where) {
        GLenum e = gl.GetError();
        if (e != GL_NO_ERROR) std::printf("  GL error 0x%x after %s\n", e, where);
        return e == GL_NO_ERROR;
    };
    bool glClean = true;

    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) {
        std::printf("SKIP: no Vulkan backend (%s)\n", atlas_last_error(nullptr));
        return SKIP;
    }
    if (atlas_preview_load_raster(ctx, (data + "/tamriel_raster.bin").c_str()) != ATLAS_OK) {
        std::printf("SKIP: %s\n", atlas_last_error(ctx));
        return SKIP;
    }
    double x = 0, z = 0, y = std::fmax(atlas_preview_height(ctx, x, z), 63.0) + 60;
    for (int lod = 0; lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        atlas_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    double centre[3] = {x, y, z};
    atlas_update(ctx, centre, 0);

    const uint32_t W = 960, H = 540;
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY;  // weather is zero here: no clouds

    std::vector<float> refColor(size_t(W) * H * 4), refDepth(size_t(W) * H);
    AtlasFrameOutput o{nullptr, nullptr, refDepth.data(), refColor.data()};
    if (atlas_render(ctx, &f, &o) != ATLAS_OK) {
        std::printf("FAIL host render: %s\n", atlas_last_error(ctx));
        return FAIL;
    }

    AtlasGlTargets t{};
    if (atlas_gl_attach(ctx, W, H, &t) != ATLAS_OK) {
        std::printf("FAIL attach: %s\n", atlas_last_error(ctx));
        return FAIL;
    }
    std::printf("attached: color %u normal %u depth %u nearDepth %u (%ux%u)\n", t.color, t.normal, t.depth,
                t.nearDepth, t.width, t.height);
    std::string info = atlas_info(ctx);
    std::printf("info: %s\n", info.c_str());
    const bool host = info.find("\"interop\":\"host-copy\"") != std::string::npos;
    mode = host ? "host-copy" : "zero-copy";
    std::printf("mode: %s\n", mode.c_str());
    glClean &= glCheck("attach");
    // host copies march every pixel (no near-depth skip): the near-field references are the plain renders
    std::vector<float> plainColor = refColor, plainDepth = refDepth;

    // several frames: exercises both frame slots, the semaphore ping-pong and the queue ownership transfers
    const int FRAMES = 60;
    auto c0 = std::chrono::steady_clock::now();
    for (int i = 0; i < FRAMES; i++) {
        if (atlas_gl_render(ctx, &f) != ATLAS_OK) {
            std::printf("FAIL gl render %d: %s\n", i, atlas_last_error(ctx));
            return FAIL;
        }
    }
    double cpuMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count() / FRAMES;
    gl.Finish();
    double wallMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count() / FRAMES;
    std::vector<float> glColor(size_t(W) * H * 4), glDepth(size_t(W) * H);
    gl.GetTextureImage(t.color, 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(t.depth, 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c1 = compare(refColor, refDepth, glColor, glDepth, W, H);
    AtlasStats st{};
    atlas_get_stats(ctx, &st);
    std::printf("%d frames: %.3f ms CPU per atlas_gl_render, %.2f ms wall incl. final glFinish, GPU %.2f ms/frame\n",
                FRAMES, cpuMs, wallMs, st.lastFrameMs);
    if (host) std::printf("host-copy cost, last frame: %s\n", std::strstr(atlas_info(ctx), "\"hostCopy\""));
    glClean &= glCheck("frames");
    std::printf("GL readback vs host render: %zu colour (worst rel %.1e) and %zu depth pixels differ\n", c1.color,
                c1.worstRel, c1.depth);

    // near depth written on the GL side: the left half is covered by "near field" at 20 blocks
    float covered = 20.0f;
    gl.ClearTexSubImage(t.nearDepth, 0, 0, 0, 0, GLsizei(W / 2), GLsizei(H), 1, GL_RED, GL_FLOAT, &covered);
    std::vector<float> nearDist(size_t(W) * H);
    for (uint32_t yy = 0; yy < H; yy++)
        for (uint32_t xx = 0; xx < W; xx++) nearDist[size_t(yy) * W + xx] = xx < W / 2 ? covered : 1e30f;
    AtlasFrame fn = f;
    fn.flags |= ATLAS_FRAME_NEAR_DEPTH;
    fn.nearDepth = nearDist.data();
    AtlasFrameOutput on{nullptr, nullptr, refDepth.data(), refColor.data()};
    if (atlas_render(ctx, &fn, &on) != ATLAS_OK) {
        std::printf("FAIL host near render: %s\n", atlas_last_error(ctx));
        return FAIL;
    }
    if (host) {
        refColor = plainColor;
        refDepth = plainDepth;
    }
    fn.nearDepth = nullptr;  // the GL path reads the shared image
    for (int i = 0; i < 3; i++) atlas_gl_render(ctx, &fn);
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
    // the game's own depth buffer: GL depth texture -> atlas linearises it; plus copies into caller textures
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
    AtlasGlFrameIO io{};
    io.nearDepthTex = depthTex;
    std::memcpy(io.nearInvProjection, invProj, sizeof(invProj));
    io.dstColor = dst[0];
    io.dstNormal = dst[1];
    io.dstDepth = dst[2];
    AtlasFrame fd = f;  // no NEAR_DEPTH flag: the io depth texture turns it on
    for (int i = 0; i < 3; i++)
        if (atlas_gl_render_io(ctx, &fd, &io) != ATLAS_OK) {
            std::printf("FAIL gl render io: %s\n", atlas_last_error(ctx));
            return FAIL;
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
    AtlasGlFrameIO iob = io;
    iob.nearDepthTex = ATLAS_GL_BOUND_DEPTH;
    for (int i = 0; i < 3; i++) atlas_gl_render_io(ctx, &fd, &iob);
    gl.BindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    gl.Finish();
    gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c4 = compare(refColor, refDepth, glColor, glDepth, W, H);
    std::printf("depth via the bound framebuffer: %zu colour, %zu depth pixels differ from host\n", c4.color, c4.depth);
    glClean &= glCheck("depth-buffer frames");
    gl.DeleteFramebuffers(1, &fbo);
    // dynamic resolution: the window's depth at twice atlas's size, the same left half covered, plus one covered
    // full-resolution column that covers only half of each atlas pixel it falls in: that one must not be skipped
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
    AtlasGlFrameIO io2 = io;
    io2.nearDepthTex = depthTex2;
    for (int i = 0; i < 3; i++) atlas_gl_render_io(ctx, &fd, &io2);
    gl.Finish();
    gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
    gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
    Compare c5 = compare(refColor, refDepth, glColor, glDepth, W, H);
    std::printf("near depth at twice atlas's resolution: %zu colour, %zu depth pixels differ from host\n", c5.color, c5.depth);
    gl.DeleteTextures(1, &depthTex2);
    // the near light (abi 0.15): GL hands the near field's surface info beside its depth (the left half at 20 blocks,
    // normals in four directions), atlas lights the covered pixels with the far light into the caller's RGBA16F
    // texture and writes (inscatter, transmittance) as their colour; in both interop modes (host copies read the near
    // inputs back for it) the frame must match the host-path render of the same inputs
    bool nearLightOk = false;
    {
        auto oct = [](float x, float y, float z) {  // atlas_shared.slang octEncode, to bytes
            float l = std::fabs(x) + std::fabs(y) + std::fabs(z);
            float ex = x / l, ey = z / l;
            if (y < 0) {
                float ox = (1 - std::fabs(ey)) * (ex >= 0 ? 1.0f : -1.0f), oy = (1 - std::fabs(ex)) * (ey >= 0 ? 1.0f : -1.0f);
                ex = ox;
                ey = oy;
            }
            return uint32_t((ex * 0.5f + 0.5f) * 255.0f + 0.5f) | uint32_t((ey * 0.5f + 0.5f) * 255.0f + 0.5f) << 8;
        };
        const uint32_t normals[4] = {oct(0, 1, 0), oct(1, 0, 0), oct(-0.6f, 0.8f, 0), oct(0, 0, -1)};
        std::vector<uint32_t> infoGl(size_t(W) * H), infoHost(size_t(W) * H);
        for (uint32_t yy = 0; yy < H; yy++)  // GL rows: yy = 0 is the bottom
            for (uint32_t xx = 0; xx < W; xx++) {
                uint32_t v = 7u * 17u | (15u * 17u) << 8 | normals[(xx / 16 + yy / 16) % 4] << 16;  // block 7, sky 15
                infoGl[size_t(yy) * W + xx] = v;
                infoHost[size_t(H - 1 - yy) * W + xx] = v;
            }
        GLuint infoTex = 0, lightTex = 0;
        gl.CreateTextures(GL_TEXTURE_2D, 1, &infoTex);
        gl.TextureStorage2D(infoTex, 1, GL_RGBA8, GLsizei(W), GLsizei(H));
        gl.TextureSubImage2D(infoTex, 0, 0, 0, GLsizei(W), GLsizei(H), GL_RGBA, GL_UNSIGNED_BYTE, infoGl.data());
        gl.CreateTextures(GL_TEXTURE_2D, 1, &lightTex);
        gl.TextureStorage2D(lightTex, 1, GL_RGBA16F, GLsizei(W), GLsizei(H));
        AtlasFrame fl = f;
        fl.flags |= ATLAS_FRAME_NEAR_DEPTH | ATLAS_FRAME_NEAR_LIGHT;
        fl.nearDepth = nearDist.data();
        fl.nearInfo = infoHost.data();
        std::vector<float> refLight(size_t(W) * H * 4), nlColor(size_t(W) * H * 4), nlDepth(size_t(W) * H);
        AtlasFrameOutput ol{nullptr, nullptr, nlDepth.data(), nlColor.data(), refLight.data()};
        bool rendered = atlas_render(ctx, &fl, &ol) == ATLAS_OK;
        AtlasGlFrameIO iol = io;
        iol.nearInfoTex = infoTex;
        iol.dstNearLight = lightTex;
        AtlasFrame fg = f;  // the io depth texture turns NEAR_DEPTH on
        fg.flags |= ATLAS_FRAME_NEAR_LIGHT;
        // every pixel traced, as the host render does: the game's half-resolution near light is compared after
        setEnv("ATLAS_NEAR_FULLRES", "1");
        for (int i = 0; i < 3 && rendered; i++) rendered = atlas_gl_render_io(ctx, &fg, &iol) == ATLAS_OK;
        setEnv("ATLAS_NEAR_FULLRES", nullptr);
        if (!rendered) std::printf("  near light render: %s\n", atlas_last_error(ctx));
        gl.Finish();
        std::vector<float> glLight(size_t(W) * H * 4), zeroD(size_t(W) * H, 1e30f);
        gl.GetTextureImage(lightTex, 0, GL_RGBA, GL_FLOAT, GLsizei(glLight.size() * 4), glLight.data());
        gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(glColor.size() * 4), glColor.data());
        gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(glDepth.size() * 4), glDepth.data());
        Compare cl = compare(refLight, zeroD, glLight, zeroD, W, H);
        Compare cc = compare(nlColor, nlDepth, glColor, glDepth, W, H);
        size_t lit = 0, hazed = 0;
        for (uint32_t yy = 0; yy < H; yy++)
            for (uint32_t xx = 0; xx < W / 2; xx++) {
                size_t i = (size_t(yy) * W + xx) * 4;
                lit += refLight[i] > 0.0f && refLight[i + 1] > 0.0f && refLight[i + 2] > 0.0f;
                hazed += nlColor[i + 3] > 0.9f && nlColor[i + 3] < 1.0f && nlColor[i + 2] > 0.0f;  // T < 1, inscatter
            }
        std::printf("near light via GL (%s): %zu light and %zu colour pixels differ from host (worst rel %.1e); %zu of %u "
                    "covered px lit, %zu hazed\n", mode.c_str(), cl.color, cc.color, cl.worstRel, lit, W / 2 * H, hazed);
        nearLightOk = rendered && cl.color == 0 && cc.color == 0 && cc.depth == 0 && lit == size_t(W / 2) * H &&
                      hazed == size_t(W / 2) * H;
        // the game's near light (zero-copy: traced at half resolution, upsampled, with its history): a reconstruction,
        // so close to the host's on average rather than equal (the history converges over frames: a few first)
        if (!host && rendered) {
            for (int i = 0; i < 8 && rendered; i++) rendered = atlas_gl_render_io(ctx, &fg, &iol) == ATLAS_OK;
            gl.Finish();
            std::vector<float> splitLight(size_t(W) * H * 4);
            gl.GetTextureImage(lightTex, 0, GL_RGBA, GL_FLOAT, GLsizei(splitLight.size() * 4), splitLight.data());
            // each is one dithered ray of each kind a pixel, so two traces differ pixel by pixel even when both are
            // right: compared as means (bias) and over 8 x 8 blocks (the noise averaged, the shapes kept)
            double sumS = 0, sumR = 0, blockDiff = 0, blockRef = 0;
            size_t splitLit = 0;
            for (uint32_t by = 0; by + 8 <= H; by += 8)
                for (uint32_t bx = 0; bx + 8 <= W / 2; bx += 8) {
                    double bs = 0, br = 0;
                    for (uint32_t yy = by; yy < by + 8; yy++)
                        for (uint32_t xx = bx; xx < bx + 8; xx++) {
                            // (GL rows bottom-up, the host's top-down: as compare)
                            size_t i = (size_t(yy) * W + xx) * 4, g = (size_t(H - 1 - yy) * W + xx) * 4;
                            for (int c = 0; c < 3; c++) bs += splitLight[g + c], br += refLight[i + c];
                            splitLit += splitLight[g] > 0.0f && splitLight[g + 1] > 0.0f && splitLight[g + 2] > 0.0f;
                        }
                    sumS += bs, sumR += br, blockDiff += std::fabs(bs - br), blockRef += br;
                }
            double bias = sumR > 0 ? sumS / sumR - 1.0 : 1.0, blocks = blockRef > 0 ? blockDiff / blockRef : 1.0;
            size_t covered = size_t((W / 2) / 8 * 8) * (H / 8 * 8);
            std::printf("near light at half resolution vs the host's full trace: mean %+.1f%%, 8 x 8 blocks %.1f%% apart; "
                        "%zu of %zu covered px lit\n", 100.0 * bias, 100.0 * blocks, splitLit, covered);
            nearLightOk &= rendered && std::fabs(bias) < 0.05 && blocks < 0.10 && splitLit == covered;
        }
        glClean &= glCheck("near light frames");
        // the cost at 1920x1080 (zero-copy; GPU time of atlas's own work): a view down onto the ground, the near
        // field covering what lies within 256 blocks with the far field's own surfaces and normals, near light on vs off
        if (!host && rendered) {
            const uint32_t BW = 1920, BH = 1080;
            AtlasFrame fb = f;
            fb.width = BW;
            fb.height = BH;
            fb.nearFieldEnd = 0;
            cameraMatrix(105, -30, 70, double(BW) / BH, fb.invViewProj);
            std::vector<float> bd(size_t(BW) * BH);
            std::vector<uint32_t> bn(size_t(BW) * BH);
            AtlasFrameOutput ob{nullptr, bn.data(), bd.data(), nullptr};
            atlas_render(ctx, &fb, &ob);
            const double fyb = 1.0 / std::tan(70.0 * 3.14159265358979323846 / 360.0), fxb = fyb / (double(BW) / BH);
            float invProjB[16] = {float(1 / fxb), 0, 0, 0, 0, float(1 / fyb), 0, 0, 0, 0, 0, float((n0 - f0) / (2 * n0 * f0)),
                                  0, 0, -1, float((n0 + f0) / (2 * n0 * f0))};
            std::vector<float> bz(size_t(BW) * BH);
            std::vector<uint32_t> binfo(size_t(BW) * BH);
            size_t bcov = 0;
            for (uint32_t yy = 0; yy < BH; yy++)  // GL rows bottom-up; the host frame's top-down
                for (uint32_t xx = 0; xx < BW; xx++) {
                    size_t src = size_t(BH - 1 - yy) * BW + xx, dsti = size_t(yy) * BW + xx;
                    double dist = bd[src];
                    if (dist >= 256.0) {
                        bz[dsti] = 1.0f;
                        continue;
                    }
                    double nx = (xx + 0.5) / BW * 2 - 1, ny = (yy + 0.5) / BH * 2 - 1;
                    double vx = nx / fxb, vy = ny / fyb, l = std::sqrt(vx * vx + vy * vy + 1.0);
                    double zv = -dist / l;
                    double zn = ((f0 + n0) / (n0 - f0) * zv + 2 * f0 * n0 / (n0 - f0)) / -zv;
                    bz[dsti] = float(zn * 0.5 + 0.5);
                    binfo[dsti] = (255u << 8) | ((bn[src] & 0xFFFFu) << 16);
                    bcov++;
                }
            GLuint bDepth = 0, bInfo = 0, bLight = 0;
            gl.CreateTextures(GL_TEXTURE_2D, 1, &bDepth);
            gl.TextureStorage2D(bDepth, 1, GL_DEPTH_COMPONENT32F, GLsizei(BW), GLsizei(BH));
            gl.TextureSubImage2D(bDepth, 0, 0, 0, GLsizei(BW), GLsizei(BH), GL_DEPTH_COMPONENT, GL_FLOAT, bz.data());
            gl.CreateTextures(GL_TEXTURE_2D, 1, &bInfo);
            gl.TextureStorage2D(bInfo, 1, GL_RGBA8, GLsizei(BW), GLsizei(BH));
            gl.TextureSubImage2D(bInfo, 0, 0, 0, GLsizei(BW), GLsizei(BH), GL_RGBA, GL_UNSIGNED_BYTE, binfo.data());
            gl.CreateTextures(GL_TEXTURE_2D, 1, &bLight);
            gl.TextureStorage2D(bLight, 1, GL_RGBA16F, GLsizei(BW), GLsizei(BH));
            AtlasGlTargets tb{};
            double ms[2] = {0, 0}, lo[2] = {0, 0}, hi[2] = {0, 0};
            if (atlas_gl_attach(ctx, BW, BH, &tb) == ATLAS_OK) {
                AtlasGlFrameIO iob2{};
                iob2.nearDepthTex = bDepth;
                std::memcpy(iob2.nearInvProjection, invProjB, sizeof(invProjB));
                iob2.nearInfoTex = bInfo;
                iob2.dstNearLight = bLight;
                fb.nearFieldEnd = 256;
                for (int pass = 0; pass < 2; pass++) {
                    AtlasFrame fp = fb;
                    if (pass == 1) fp.flags |= ATLAS_FRAME_NEAR_LIGHT;
                    std::vector<double> gpu;
                    for (int i = 0; i < 24; i++) {
                        atlas_gl_render_io(ctx, &fp, &iob2);
                        AtlasStats sb{};
                        atlas_get_stats(ctx, &sb);
                        if (i >= 4) gpu.push_back(sb.lastFrameMs);  // (the previous frame's GPU time)
                    }
                    gl.Finish();
                    std::sort(gpu.begin(), gpu.end());
                    ms[pass] = gpu[gpu.size() / 2];
                    lo[pass] = gpu.front();
                    hi[pass] = gpu.back();
                }
                std::printf("near light cost, 1920x1080 zero-copy, %.0f%% of the screen near: GPU median %.3f ms without "
                            "(%.3f..%.3f), %.3f ms with (%.3f..%.3f): +%.3f ms\n", 100.0 * bcov / (double(BW) * BH), ms[0],
                            lo[0], hi[0], ms[1], lo[1], hi[1], ms[1] - ms[0]);
                rendered = atlas_gl_attach(ctx, W, H, &t) == ATLAS_OK;  // back to the suite's size
                nearLightOk &= rendered;
            }
            gl.DeleteTextures(1, &bDepth);
            gl.DeleteTextures(1, &bInfo);
            gl.DeleteTextures(1, &bLight);
            glClean &= glCheck("near light timing");
        }
        gl.DeleteTextures(1, &infoTex);
        gl.DeleteTextures(1, &lightTex);
    }
    // the sun map through GL: copied into the caller's RG32F texture, identical to the device buffer
    const uint32_t SM = 128;
    atlas_set_sun_map(ctx, SM, 512.0f);
    GLuint sunTex = 0;
    gl.CreateTextures(GL_TEXTURE_2D, 1, &sunTex);
    gl.TextureStorage2D(sunTex, 1, GL_RG32F, GLsizei(SM), GLsizei(SM + 1));
    AtlasGlFrameIO io3 = io;
    io3.dstSunMap = sunTex;
    for (int i = 0; i < 3; i++) atlas_gl_render_io(ctx, &fd, &io3);
    gl.Finish();
    std::vector<float> sunGl(size_t(SM) * (SM + 1) * 2), sunDev(sunGl.size());
    gl.GetTextureImage(sunTex, 0, GL_RG, GL_FLOAT, GLsizei(sunGl.size() * 4), sunGl.data());
    bool sunRead = atlas_read_sun_map(ctx, sunDev.data(), uint32_t(sunDev.size())) == ATLAS_OK;
    size_t sunOff = 0;
    for (size_t k = 0; k < sunGl.size(); k++)
        if (std::memcmp(&sunGl[k], &sunDev[k], 4) != 0) {  // bit-exact copies (a NaN never equals itself)
            if (sunOff < 4) std::printf("  sun map value %zu: GL %a, device %a\n", k, sunGl[k], sunDev[k]);
            sunOff++;
        }
    bool sunParams = sunGl[size_t(SM) * SM * 2 + 13] == 1.0f;  // the parameter row's valid flag
    std::printf("sun map via GL: %zu of %zu values differ from the device buffer; parameter row %s\n", sunOff,
                sunGl.size(), sunParams ? "present" : "MISSING");
    atlas_set_sun_map(ctx, 0, 512.0f);
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
                atlas_preview_generate(ctx, lod, (rx - 2) * span, (rz - 2) * span, (rx + 3) * span, (rz + 3) * span);
            }
            double c[3] = {cx, y, z};
            atlas_update(ctx, c, 0);
            rebuilds++;
        }
    });
    std::vector<double> frameMs;
    AtlasFrame ff = f;
    for (int i = 0; i < 240; i++) {
        double px = x + i * 5.0;  // 300 blocks/s at 60 fps
        camX = px;
        ff.cameraPos[0] = px;
        auto t0 = std::chrono::steady_clock::now();
        if (atlas_gl_render(ctx, &ff) != ATLAS_OK) {
            std::printf("FAIL streaming render %d: %s\n", i, atlas_last_error(ctx));
            stop = true;
            worker.join();
            return FAIL;
        }
        frameMs.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
        std::this_thread::sleep_for(std::chrono::milliseconds(8));  // the rest of a game frame
    }
    stop = true;
    worker.join();
    gl.Finish();
    std::vector<double> sorted = frameMs;
    std::sort(sorted.begin(), sorted.end());
    std::printf("streaming flight: %d clipmap rebuilds during 240 frames; atlas_gl_render CPU median %.2f ms, "
                "p99 %.2f ms, max %.2f ms\n", rebuilds.load(), sorted[sorted.size() / 2], sorted[sorted.size() * 99 / 100],
                sorted.back());
    // host copies wait for the GPU inside the call, so only zero-copy is held to the 8 ms p99 (software drivers set
    // ATLAS_TEST_SLOW=1: they take seconds per frame)
    const char* slow = std::getenv("ATLAS_TEST_SLOW");
    bool smooth = host || (slow && *slow == '1') || sorted[sorted.size() * 99 / 100] < 8.0;
    glClean &= glCheck("streaming");

    // a lost device mid-game (the test hook, as a TDR / GPU reset would): that frame fails with
    // ATLAS_ERR_DEVICE_LOST before GL is asked to wait on anything, every later frame fails the same way, GL stays
    // clean and usable, detach and destroy are safe; then a fresh context (the mod's retry) renders again
    bool lossOk = atlas_debug_fault(ctx, ATLAS_FAULT_DEVICE_LOST, 0) == ATLAS_OK;
    lossOk &= atlas_gl_render(ctx, &ff) == ATLAS_ERR_DEVICE_LOST;
    lossOk &= atlas_gl_render(ctx, &ff) == ATLAS_ERR_DEVICE_LOST;
    lossOk &= std::string(atlas_info(ctx)).find("\"deviceLost\":true") != std::string::npos;
    glClean &= glCheck("device lost");
    atlas_gl_detach(ctx);
    glClean &= glCheck("detach");
    atlas_destroy(ctx);
    {
        // GL still runs (nothing waits on a semaphore the lost device never signals)
        GLuint probe = 0;
        float one[4] = {1, 1, 1, 1};
        gl.CreateTextures(GL_TEXTURE_2D, 1, &probe);
        gl.TextureStorage2D(probe, 1, GL_RGBA16F, 4, 4);
        gl.ClearTexImage(probe, 0, GL_RGBA, GL_FLOAT, one);
        gl.Finish();
        gl.DeleteTextures(1, &probe);
    }
    glClean &= glCheck("after the loss");
    {
        AtlasContext* again = nullptr;
        bool retry = atlas_create(ATLAS_BACKEND_VULKAN, &again) == ATLAS_OK;
        if (retry) {
            std::vector<float> s0(256 * 256, 100.0f), w0(256 * 256, NAN);
            std::vector<uint16_t> t0(256 * 256, 2), f0(256 * 256, 3);
            AtlasColumnTile tile{0, int32_t(std::floor(x / 256)), int32_t(std::floor(z / 256)), s0.data(), w0.data(),
                                  t0.data(), f0.data(), nullptr, nullptr};
            AtlasGlTargets t2{};
            AtlasFrame small = ff;
            small.width = 160;
            small.height = 90;
            retry = atlas_upload_column_tile(again, &tile) == ATLAS_OK && atlas_update(again, centre, 0) == ATLAS_OK &&
                    atlas_gl_attach(again, 160, 90, &t2) == ATLAS_OK && atlas_gl_render(again, &small) == ATLAS_OK &&
                    atlas_gl_render(again, &small) == ATLAS_OK;
            if (!retry) std::printf("  retry context: %s\n", atlas_last_error(again));
            gl.Finish();
            atlas_gl_detach(again);
            atlas_destroy(again);
        }
        lossOk &= retry;
        glClean &= glCheck("retry context");
    }
    std::printf("device loss: %s\n", lossOk ? "frame fails with ATLAS_ERR_DEVICE_LOST, GL clean, a new context renders"
                                             : "FAILED");
    glc.destroy();
    bool ok = c1.color == 0 && c1.depth == 0 && c2.color == 0 && c2.depth == 0 && coveredLit == 0 && glClean &&
              c3.color == 0 && c3.depth == 0 && c4.color == 0 && c4.depth == 0 && c5.color == 0 && c5.depth == 0 && sunOk &&
              smooth && lossOk && nearLightOk;
    std::printf(ok ? "PASS\n" : "FAIL (gl error 0x%x)\n", e);
    return ok ? PASS : FAIL;
}

static void setEnv(const char* k, const char* v) {
#if defined(_WIN32)
    _putenv_s(k, v ? v : "");
#else
    if (v) setenv(k, v, 1);
    else unsetenv(k);
#endif
}

static double infoNumber(const char* json, const char* key) {
    const char* p = std::strstr(json, key);
    return p ? std::atof(p + std::strlen(key)) : -1;
}

// The CPU backend's GL path (a machine without a usable Vulkan device): the near field's depth and info read back from
// GL, the frame rendered on the CPU, uploaded into GL textures. Synchronous (ATLAS_CPU_GL=sync) and the default
// asynchronous mode (readback through a pixel-pack buffer and a fence, the render on a worker thread, the textures a
// few frames behind) must both end on the host-path render of the same inputs; then the cost at a 1920x1080 window
// with atlas at a third of it, as the game runs it.
static Result runCpuSuite(const std::string& data, atlas_test::Profile profile) {
    atlas_test::GlContext glc;
    GlApi gl;
    std::string err;
    if (!glc.create(err, profile) || !gl.load(err)) {
        std::printf("SKIP: %s\n", err.c_str());
        return SKIP;
    }
    std::printf("GL (%s): %s | %s; async readback %s\n", glc.kind, gl.GetString(GL_RENDERER), gl.GetString(GL_VERSION),
                gl.asyncReadback ? "available" : "MISSING (sync only)");
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_CPU, &ctx) != ATLAS_OK ||
        atlas_preview_load_raster(ctx, (data + "/tamriel_raster.bin").c_str()) != ATLAS_OK) {
        std::printf("SKIP: %s\n", atlas_last_error(ctx));
        return SKIP;
    }
    double x = 0, z = 0, y = std::fmax(atlas_preview_height(ctx, x, z), 63.0) + 60;
    for (int lod = 0; lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        atlas_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    double centre[3] = {x, y, z};
    atlas_update(ctx, centre, 0);
    bool glClean = true;
    auto glCheck = [&](const char* where) {
        GLenum e = gl.GetError();
        if (e != GL_NO_ERROR) std::printf("  GL error 0x%x after %s\n", e, where);
        return e == GL_NO_ERROR;
    };

    // the correctness frame: 320 x 180, the near field's depth at twice that (as a window above atlas's scale), its
    // left half covered at 20 blocks with surface info (normals in four directions), the near light on
    const uint32_t W = 320, H = 180;
    AtlasFrame f{};
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
    f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY | ATLAS_FRAME_NEAR_LIGHT;
    const double n0 = 0.05, f0 = 100000.0, fov = 70.0, aspect = double(W) / H;
    const double fy = 1.0 / std::tan(fov * 3.14159265358979323846 / 360.0), fxp = fy / aspect;
    float invProj[16] = {float(1 / fxp), 0, 0, 0, 0, float(1 / fy), 0, 0, 0, 0, 0, float((n0 - f0) / (2 * n0 * f0)),
                         0, 0, -1, float((n0 + f0) / (2 * n0 * f0))};
    auto oct = [](float nx, float ny, float nz) {  // atlas_shared.slang octEncode, to bytes
        float l = std::fabs(nx) + std::fabs(ny) + std::fabs(nz);
        float ex = nx / l, ey = nz / l;
        if (ny < 0) {
            float ox = (1 - std::fabs(ey)) * (ex >= 0 ? 1.0f : -1.0f), oy = (1 - std::fabs(ex)) * (ey >= 0 ? 1.0f : -1.0f);
            ex = ox;
            ey = oy;
        }
        return uint32_t((ex * 0.5f + 0.5f) * 255.0f + 0.5f) | uint32_t((ey * 0.5f + 0.5f) * 255.0f + 0.5f) << 8;
    };
    const uint32_t normals[4] = {oct(0, 1, 0), oct(1, 0, 0), oct(-0.6f, 0.8f, 0), oct(0, 0, -1)};
    // GL inputs at 2W x 2H (rows bottom-up); the host's at W x H (top-down): what the four-sample pass yields
    std::vector<float> depthGl(size_t(4) * W * H), nearHost(size_t(W) * H);
    std::vector<uint32_t> infoGl(size_t(4) * W * H), infoHost(size_t(W) * H);
    for (uint32_t yy = 0; yy < 2 * H; yy++)
        for (uint32_t xx = 0; xx < 2 * W; xx++) {
            double nx = (xx + 0.5) / (2 * W) * 2 - 1, ny = (yy + 0.5) / (2 * H) * 2 - 1;
            double vx = nx / fxp, vy = ny / fy, l = std::sqrt(vx * vx + vy * vy + 1.0), zv = -20.0 / l;
            double zn = ((f0 + n0) / (n0 - f0) * zv + 2 * f0 * n0 / (n0 - f0)) / -zv;
            depthGl[size_t(yy) * 2 * W + xx] = xx < W ? float(zn * 0.5 + 0.5) : 1.0f;
            infoGl[size_t(yy) * 2 * W + xx] = 7u * 17u | (15u * 17u) << 8 | normals[(xx / 32 + yy / 32) % 4] << 16;
        }
    GLuint depthTex = 0, infoTex = 0, dst[4] = {};
    gl.CreateTextures(GL_TEXTURE_2D, 1, &depthTex);
    gl.TextureStorage2D(depthTex, 1, GL_DEPTH_COMPONENT32F, GLsizei(2 * W), GLsizei(2 * H));
    gl.TextureSubImage2D(depthTex, 0, 0, 0, GLsizei(2 * W), GLsizei(2 * H), GL_DEPTH_COMPONENT, GL_FLOAT, depthGl.data());
    gl.CreateTextures(GL_TEXTURE_2D, 1, &infoTex);
    gl.TextureStorage2D(infoTex, 1, GL_RGBA8, GLsizei(2 * W), GLsizei(2 * H));
    gl.TextureSubImage2D(infoTex, 0, 0, 0, GLsizei(2 * W), GLsizei(2 * H), GL_RGBA, GL_UNSIGNED_BYTE, infoGl.data());
    const GLenum dstFmt[4] = {GL_RGBA16F, GL_RGBA8, GL_R32F, GL_RGBA16F};
    for (int i = 0; i < 4; i++) {
        gl.CreateTextures(GL_TEXTURE_2D, 1, &dst[i]);
        gl.TextureStorage2D(dst[i], 1, dstFmt[i], GLsizei(W), GLsizei(H));
    }
    // the reference reads back what the GL pass made of the depth and info (the distances in float, not recomputed)
    AtlasGlFrameIO io{};
    io.nearDepthTex = depthTex;
    std::memcpy(io.nearInvProjection, invProj, sizeof(invProj));
    io.depthZeroToOne = 0;  // the depths above: [-1,1] clip depth through the default depth range
    io.dstColor = dst[0];
    io.dstNormal = dst[1];
    io.dstDepth = dst[2];
    io.nearInfoTex = infoTex;
    io.dstNearLight = dst[3];

    auto readOut = [&](std::vector<float>& c, std::vector<float>& d, std::vector<float>& l) {
        c.assign(size_t(W) * H * 4, 0);
        d.assign(size_t(W) * H, 0);
        l.assign(size_t(W) * H * 4, 0);
        gl.Finish();
        gl.GetTextureImage(dst[0], 0, GL_RGBA, GL_FLOAT, GLsizei(c.size() * 4), c.data());
        gl.GetTextureImage(dst[2], 0, GL_RED, GL_FLOAT, GLsizei(d.size() * 4), d.data());
        gl.GetTextureImage(dst[3], 0, GL_RGBA, GL_FLOAT, GLsizei(l.size() * 4), l.data());
    };
    AtlasGlTargets t{};
    setEnv("ATLAS_CPU_GL", "sync");
    bool ok = atlas_gl_attach(ctx, W, H, &t) == ATLAS_OK;
    ok = ok && atlas_gl_render_io(ctx, &f, &io) == ATLAS_OK;
    if (!ok) {
        std::printf("FAIL cpu gl (sync): %s\n", atlas_last_error(ctx));
        setEnv("ATLAS_CPU_GL", nullptr);
        return FAIL;
    }
    std::printf("info: %s\n", atlas_info(ctx));
    // the near inputs as the GL pass wrote them (rows bottom-up), for the host reference
    {
        std::vector<float> nd(size_t(W) * H);
        std::vector<uint32_t> ni(size_t(W) * H);
        gl.Finish();
        gl.GetTextureImage(t.nearDepth, 0, GL_RED, GL_FLOAT, GLsizei(nd.size() * 4), nd.data());
        // (the info image is not a target; its pick is the farthest sample, the same texel row the depth took: here
        // every sample of a covered pixel is at 20 blocks, so the first sample's texel, as the pass picks)
        for (uint32_t yy = 0; yy < H; yy++)
            for (uint32_t xx = 0; xx < W; xx++) {
                nearHost[size_t(H - 1 - yy) * W + xx] = nd[size_t(yy) * W + xx];
                uint32_t qx = std::min(uint32_t((xx + 0.25) * 2), 2 * W - 1), qy = std::min(uint32_t((yy + 0.25) * 2), 2 * H - 1);
                infoHost[size_t(H - 1 - yy) * W + xx] = infoGl[size_t(qy) * 2 * W + qx];
            }
    }
    AtlasFrame fh = f;
    fh.flags |= ATLAS_FRAME_NEAR_DEPTH;
    fh.nearDepth = nearHost.data();
    fh.nearInfo = infoHost.data();
    std::vector<float> refC(size_t(W) * H * 4), refD(size_t(W) * H), refL(size_t(W) * H * 4), zeroD(size_t(W) * H, 1e30f);
    AtlasFrameOutput o{nullptr, nullptr, refD.data(), refC.data(), refL.data()};
    ok = atlas_render(ctx, &fh, &o) == ATLAS_OK;
    size_t lit = 0;
    for (uint32_t yy = 0; yy < H; yy++)
        for (uint32_t xx = 0; xx < W / 2; xx++) lit += refL[(size_t(yy) * W + xx) * 4] > 0.0f;
    // the host render above moved the context's history: the GL frame again, then compare
    ok = ok && atlas_gl_render_io(ctx, &f, &io) == ATLAS_OK;
    std::vector<float> c, d, l;
    readOut(c, d, l);
    Compare sc = compare(refC, refD, c, d, W, H), sl = compare(refL, zeroD, l, zeroD, W, H);
    glClean &= glCheck("sync frames");
    std::printf("sync: %zu colour, %zu depth, %zu near-light pixels differ from the host render (%zu of %u covered lit)\n",
                sc.color, sc.depth, sl.color, lit, W / 2 * H);
    bool syncOk = ok && sc.color == 0 && sc.depth == 0 && sl.color == 0 && lit == size_t(W / 2) * H;

    // asynchronous (the default): frames keep coming; once the worker has caught up the textures hold the same frame
    setEnv("ATLAS_CPU_GL", nullptr);
    bool asyncOk = atlas_gl_attach(ctx, W, H, &t) == ATLAS_OK;
    for (int i = 0; i < 12 && asyncOk; i++) {
        asyncOk = atlas_gl_render_io(ctx, &f, &io) == ATLAS_OK;
        gl.Finish();
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
    }
    if (!asyncOk) std::printf("  async: %s\n", atlas_last_error(ctx));
    readOut(c, d, l);
    Compare ac = compare(refC, refD, c, d, W, H), al = compare(refL, zeroD, l, zeroD, W, H);
    std::string ainfo = atlas_info(ctx);
    bool isAsync = ainfo.find("\"async\":true") != std::string::npos;
    glClean &= glCheck("async frames");
    std::printf("async (%s): %zu colour, %zu depth, %zu near-light pixels differ from the host render; %s\n",
                isAsync ? "worker" : "NOT async", ac.color, ac.depth, al.color, ainfo.c_str());
    asyncOk = asyncOk && ac.color == 0 && ac.depth == 0 && al.color == 0 && (isAsync || !gl.asyncReadback);

    // the cost: a 1920x1080 window, atlas at a third (640 x 360), the near field (the far field's own surfaces within 256
    // blocks, looking down 30 degrees from 60 above the ground: about two thirds of the screen) lit by atlas, as in game
    {
        const uint32_t WW = 1920, WH = 1080, AW = 640, AH = 360;
        AtlasFrame fb = f;
        fb.width = WW;
        fb.height = WH;
        fb.nearFieldEnd = 0;
        fb.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY;
        cameraMatrix(105, -30, 70, double(WW) / WH, fb.invViewProj);
        std::vector<float> bd(size_t(WW) * WH);
        std::vector<uint32_t> bn(size_t(WW) * WH);
        AtlasFrameOutput ob{nullptr, bn.data(), bd.data(), nullptr};
        atlas_render(ctx, &fb, &ob);
        const double fyb = fy, fxb = fyb / (double(WW) / WH);
        float invProjB[16] = {float(1 / fxb), 0, 0, 0, 0, float(1 / fyb), 0, 0, 0, 0, 0, float((n0 - f0) / (2 * n0 * f0)),
                              0, 0, -1, float((n0 + f0) / (2 * n0 * f0))};
        std::vector<float> bz(size_t(WW) * WH);
        std::vector<uint32_t> binfo(size_t(WW) * WH);
        size_t bcov = 0;
        for (uint32_t yy = 0; yy < WH; yy++)
            for (uint32_t xx = 0; xx < WW; xx++) {
                size_t src = size_t(WH - 1 - yy) * WW + xx, di = size_t(yy) * WW + xx;
                double dist = bd[src];
                if (dist >= 256.0) {
                    bz[di] = 1.0f;
                    continue;
                }
                double nx = (xx + 0.5) / WW * 2 - 1, ny = (yy + 0.5) / WH * 2 - 1;
                double vx = nx / fxb, vy = ny / fyb, ll = std::sqrt(vx * vx + vy * vy + 1.0), zv = -dist / ll;
                double zn = ((f0 + n0) / (n0 - f0) * zv + 2 * f0 * n0 / (n0 - f0)) / -zv;
                bz[di] = float(zn * 0.5 + 0.5);
                binfo[di] = (255u << 8) | ((bn[src] & 0xFFFFu) << 16);
                bcov++;
            }
        GLuint bDepth = 0, bInfo = 0, bDst[4] = {};
        gl.CreateTextures(GL_TEXTURE_2D, 1, &bDepth);
        gl.TextureStorage2D(bDepth, 1, GL_DEPTH_COMPONENT32F, GLsizei(WW), GLsizei(WH));
        gl.TextureSubImage2D(bDepth, 0, 0, 0, GLsizei(WW), GLsizei(WH), GL_DEPTH_COMPONENT, GL_FLOAT, bz.data());
        gl.CreateTextures(GL_TEXTURE_2D, 1, &bInfo);
        gl.TextureStorage2D(bInfo, 1, GL_RGBA8, GLsizei(WW), GLsizei(WH));
        gl.TextureSubImage2D(bInfo, 0, 0, 0, GLsizei(WW), GLsizei(WH), GL_RGBA, GL_UNSIGNED_BYTE, binfo.data());
        for (int i = 0; i < 4; i++) {
            gl.CreateTextures(GL_TEXTURE_2D, 1, &bDst[i]);
            gl.TextureStorage2D(bDst[i], 1, dstFmt[i], GLsizei(AW), GLsizei(AH));
        }
        AtlasGlFrameIO iob{};
        iob.nearDepthTex = bDepth;
        std::memcpy(iob.nearInvProjection, invProjB, sizeof(invProjB));
        iob.dstColor = bDst[0];
        iob.dstDepth = bDst[2];
        iob.nearInfoTex = bInfo;
        iob.dstNearLight = bDst[3];
        AtlasFrame fa = fb;
        fa.width = AW;
        fa.height = AH;
        fa.nearFieldEnd = 256;
        fa.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_NEAR_LIGHT | ATLAS_FRAME_LOD_DITHER;
        cameraMatrix(105, -30, 70, double(AW) / AH, fa.invViewProj);
        // the rest of a game frame, as work on this (normal priority) thread: what takes 12 ms with atlas idle, timed
        // again while atlas's worker runs beside it (how much the worker slows the game's render thread)
        volatile double sink = 0;
        auto spin = [&](uint64_t n) {
            double s = 0;
            for (uint64_t k = 0; k < n; k++) s += std::sqrt(double(k));
            sink = sink + s;
        };
        uint64_t quantum = 1u << 16;
        for (;;) {
            auto s0 = std::chrono::steady_clock::now();
            spin(quantum);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s0).count();
            if (ms >= 12.0) {
                quantum = uint64_t(double(quantum) * 12.0 / ms);
                break;
            }
            quantum *= 2;
        }
        for (int mode = 0; mode < 2; mode++) {  // 0 sync, 1 async
            setEnv("ATLAS_CPU_GL", mode == 0 ? "sync" : nullptr);
            if (atlas_gl_attach(ctx, AW, AH, &t) != ATLAS_OK) break;
            std::vector<double> call, work, game;
            auto t0 = std::chrono::steady_clock::now();
            const int N = mode == 0 ? 12 : 90;
            for (int i = 0; i < N; i++) {
                auto c0 = std::chrono::steady_clock::now();
                if (atlas_gl_render_io(ctx, &fa, &iob) != ATLAS_OK) {
                    std::printf("  timing: %s\n", atlas_last_error(ctx));
                    break;
                }
                call.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count());
                work.push_back(infoNumber(atlas_info(ctx), "\"renderMs\":"));
                // the rest of a game frame
                if (mode == 1) {
                    auto s0 = std::chrono::steady_clock::now();
                    spin(quantum);
                    game.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s0).count());
                }
            }
            gl.Finish();
            double wall = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::sort(call.begin(), call.end());
            std::sort(work.begin(), work.end());
            std::string inf = atlas_info(ctx);
            std::printf("cost %s, window 1920x1080, atlas 640x360, %.0f%% near (lit by atlas): render-thread call median "
                        "%.2f ms (p95 %.2f), CPU render median %.1f ms, convert %.1f ms; latency %.1f frames; %d frames "
                        "in %.0f ms\n", mode == 0 ? "sync" : "async", 100.0 * bcov / (double(WW) * WH),
                        call[call.size() / 2], call[call.size() * 95 / 100], work[work.size() / 2],
                        infoNumber(inf.c_str(), "\"convertMs\":"), infoNumber(inf.c_str(), "\"latencyFrames\":"), N, wall);
            if (!game.empty()) {
                std::sort(game.begin(), game.end());
                std::printf("  12 ms of render-thread work beside the worker: median %.1f ms, p95 %.1f ms\n",
                            game[game.size() / 2], game[game.size() * 95 / 100]);
            }
        }
        setEnv("ATLAS_CPU_GL", nullptr);
        gl.DeleteTextures(1, &bDepth);
        gl.DeleteTextures(1, &bInfo);
        gl.DeleteTextures(4, bDst);
        glClean &= glCheck("timing");
    }
    atlas_gl_detach(ctx);
    glClean &= glCheck("detach");
    atlas_destroy(ctx);
    gl.DeleteTextures(1, &depthTex);
    gl.DeleteTextures(1, &infoTex);
    gl.DeleteTextures(4, dst);
    glc.destroy();
    bool pass = syncOk && asyncOk && glClean;
    std::printf(pass ? "PASS\n" : "FAIL\n");
    return pass ? PASS : FAIL;
}

int main(int argc, char** argv) {
    std::string data = "native/data", which = "both", backend = "both";
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a.rfind("--context=", 0) == 0) which = a.substr(10);
        else if (a.rfind("--backend=", 0) == 0) backend = a.substr(10);
        else data = a;
    }
    // the CPU backend's GL path, in the game's context (once: not again under ATLAS_INTEROP, a Vulkan setting)
    Result cpu = SKIP;
    if (backend != "vulkan" && !std::getenv("ATLAS_INTEROP")) {
        std::printf("== CPU backend, OpenGL 3.3 core profile (no Vulkan device)\n");
        cpu = runCpuSuite(data, atlas_test::Profile::Core33);
        if (backend == "cpu") return cpu == FAIL ? 1 : 0;
    }
    if (which != "both" && which != "default" && which != "core33") {
        std::printf("usage: atlas_gl_test [native/data] [--context=both|default|core33]\n");
        return 2;
    }
    const char* names[3] = {"PASS", "FAIL", "SKIP"};
    Result def = SKIP, core = SKIP;
    std::string defMode, coreMode;
    if (which != "core33") {
        std::printf("== default context\n");
        def = runSuite(data, atlas_test::Profile::Default, defMode);
    }
    if (which != "default") {
        std::printf("== OpenGL 3.3 core profile (the game's context)\n");
        core = runSuite(data, atlas_test::Profile::Core33, coreMode);
    }
    bool ok = def != FAIL && core != FAIL && cpu != FAIL;
    if (which == "both") {
        std::printf("default context: %s (%s); 3.3 core: %s (%s)\n", names[def], defMode.empty() ? "-" : defMode.c_str(),
                    names[core], coreMode.empty() ? "-" : coreMode.c_str());
        if (def == PASS && core == SKIP) {
            std::printf("FAIL: the 3.3 core context is unusable where the default context works\n");
            ok = false;
        }
        if (def == PASS && core == PASS && defMode != coreMode) {
            std::printf("FAIL: the 3.3 core context lands on %s where the default context gets %s\n", coreMode.c_str(),
                        defMode.c_str());
            ok = false;
        }
    }
    return ok ? 0 : 1;
}
