// The game's GLSL air (mod/src/main/resources/assets/nirncraft/shaders/include/atlas_air.glsl: the sky AtlasSky draws
// behind everything, a port of native/src/air.hpp) must match atlas's own (native/shaders/atlas_air.slang): the far
// field's haze fades into that sky, so a mismatch is a seam at the horizon. A GL compute shader built from the mod's
// own file (its AirInfo block filled from atlas_air_coeffs, AirMs from atlas_air_multiscatter, as AtlasSky fills them)
// evaluates airSkyRadiance() for every pixel of a camera; atlas renders the same camera over an empty world (all sky,
// ATLAS_FRAME_SKY). They must agree to float precision over the 24-step march (0.5 %: GL's filtered fetch of the LUT,
// its 8-bit weights, and its exp at a low sun's optical depths). Compiling the file here is also the offline check that it
// builds at all. (Until 2026-10-20 this compared the Iris pack's lib/atmosphere.glsl, whose analytic sky the air
// replaced; the Iris path is retired at R4.)
//   atlas_pack_test [mod/src/main/resources/assets/nirncraft/shaders/include/atlas_air.glsl]

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "camera.hpp"
#include "gl_api.hpp"
#include "gl_context.hpp"

using namespace atlas;

int main(int argc, char** argv) {
    std::string libPath = argc > 1 ? argv[1] : "mod/src/main/resources/assets/nirncraft/shaders/include/atlas_air.glsl";
    std::ifstream in(libPath);
    if (!in) {
        std::printf("SKIP: %s not found\n", libPath.c_str());
        return 0;
    }
    std::stringstream lib;
    lib << in.rdbuf();
    atlas_test::GlContext glc;
    GlApi gl;
    std::string err;
    if (!glc.create(err) || !gl.load(err)) {
        std::printf("SKIP: %s\n", err.c_str());
        return 0;
    }
    const uint32_t W = 320, H = 180;
    float inv[16];
    cameraMatrix(140, 4, 80, double(W) / H, inv);
    struct Case {
        float sun[3];
    } cases[] = {{{0.3f, 0.55f, -0.78f}}, {{0.9f, 0.08f, 0.42f}}, {{0.95f, 0.03f, -0.3f}}, {{-0.2f, -0.06f, 0.97f}}};

    std::string src = std::string("#version 430\n") + lib.str() + R"GLSL(
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, binding = 0) buffer Out { vec4 result[]; };
uniform mat4 invVP;
uniform vec2 size;
void main() {
    uvec2 p = gl_GlobalInvocationID.xy;
    if (p.x >= uint(size.x) || p.y >= uint(size.y)) return;
    vec2 ndc = vec2((float(p.x) + 0.5) / size.x * 2.0 - 1.0, 1.0 - (float(p.y) + 0.5) / size.y * 2.0);
    vec4 q = invVP * vec4(ndc, 0.5, 1.0);
    vec3 dir = normalize(q.xyz / q.w);
    result[p.y * uint(size.x) + p.x] = vec4(airSkyRadiance(dir), 1.0);
}
)GLSL";
    const char* s = src.c_str();
    GLuint sh = gl.CreateShader(GL_COMPUTE_SHADER);
    gl.ShaderSource(sh, 1, &s, nullptr);
    gl.CompileShader(sh);
    GLint ok = 0;
    gl.GetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096] = {};
        gl.GetShaderInfoLog(sh, sizeof(log), nullptr, log);
        std::printf("FAIL: %s does not compile as GLSL 4.30:\n%s\n", libPath.c_str(), log);
        return 1;
    }
    GLuint prog = gl.CreateProgram();
    gl.AttachShader(prog, sh);
    gl.LinkProgram(prog);
    auto genBuffers = reinterpret_cast<void(ATLAS_GLAPI*)(GLsizei, GLuint*)>(GlApi::proc("glCreateBuffers"));
    auto bufStorage = reinterpret_cast<void(ATLAS_GLAPI*)(GLuint, intptr_t, const void*, unsigned)>(GlApi::proc("glNamedBufferStorage"));
    auto bufSub = reinterpret_cast<void(ATLAS_GLAPI*)(GLuint, intptr_t, intptr_t, const void*)>(GlApi::proc("glNamedBufferSubData"));
    auto bindBase = reinterpret_cast<void(ATLAS_GLAPI*)(GLenum, GLuint, GLuint)>(GlApi::proc("glBindBufferBase"));
    auto getSub = reinterpret_cast<void(ATLAS_GLAPI*)(GLuint, intptr_t, intptr_t, void*)>(GlApi::proc("glGetNamedBufferSubData"));
    auto blockIndex = reinterpret_cast<GLuint(ATLAS_GLAPI*)(GLuint, const char*)>(GlApi::proc("glGetUniformBlockIndex"));
    auto blockBinding = reinterpret_cast<void(ATLAS_GLAPI*)(GLuint, GLuint, GLuint)>(GlApi::proc("glUniformBlockBinding"));
    GLuint out = 0, ubo = 0;
    genBuffers(1, &out);
    bufStorage(out, intptr_t(W) * H * 16, nullptr, 0x0100 /* GL_DYNAMIC_STORAGE_BIT */);
    bindBase(0x90D2 /* GL_SHADER_STORAGE_BUFFER */, 0, out);
    genBuffers(1, &ubo);
    bufStorage(ubo, 7 * 16, nullptr, 0x0100);
    bindBase(0x8A11 /* GL_UNIFORM_BUFFER */, 1, ubo);
    blockBinding(prog, blockIndex(prog, "AirInfo"), 1);

    // the air both draw in: the context's default (atlas_air_defaults), as AtlasSky would fill it
    AtlasAir air;
    atlas_air_defaults(&air);
    float coeffs[16];
    atlas_air_coeffs(&air, coeffs);
    std::vector<float> ms(32 * 32 * 4);
    atlas_air_multiscatter(&air, ms.data());
    GLuint tex = 0;
    gl.CreateTextures(GL_TEXTURE_2D, 1, &tex);
    gl.TextureStorage2D(tex, 1, 0x8814 /* GL_RGBA32F */, 32, 32);
    gl.TextureSubImage2D(tex, 0, 0, 0, 32, 32, GL_RGBA, GL_FLOAT, ms.data());
    gl.TextureParameteri(tex, GL_TEXTURE_MIN_FILTER, 0x2601 /* GL_LINEAR */);
    gl.TextureParameteri(tex, GL_TEXTURE_MAG_FILTER, 0x2601);
    gl.TextureParameteri(tex, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    gl.TextureParameteri(tex, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    gl.BindTextureUnit(0, tex);
    gl.ProgramUniform1i(prog, gl.GetUniformLocation(prog, "AirMs"), 0);

    AtlasContext* ctx = nullptr;
    atlas_create(ATLAS_BACKEND_CPU, &ctx);
    double centre[3] = {0, 120, 0};
    atlas_update(ctx, centre, 0);  // no tiles: every ray misses, the frame is all sky
    int failures = 0;
    for (const Case& c : cases) {
        float l = std::sqrt(c.sun[0] * c.sun[0] + c.sun[1] * c.sun[1] + c.sun[2] * c.sun[2]);
        float sun[3] = {c.sun[0] / l, c.sun[1] / l, c.sun[2] / l};
        // AirSun (w the eye's altitude: 57 blocks over the sea, km of air), the moon: the default opposite the sun,
        // its irradiance pi x the core's fallback moon (a real full moon, api.cpp moonOf), faded out while the sun is
        // up (air.hpp lightsOf, AtlasAir.moonFor)
        float t = std::fmin(1.0f, std::fmax(0.0f, (sun[1] - 0.1f) / (0.0f - 0.1f)));
        float fade = 3.14159265f * t * t * (3 - 2 * t);
        float block[28];
        std::memcpy(block, coeffs, sizeof(coeffs));
        float tail[12] = {sun[0], sun[1], sun[2], (120.0f - 63.0f) * coeffs[11], -sun[0], -sun[1], -sun[2], 0,
                          8.7e-6f * fade, 1.25e-5f * fade, 2.2e-5f * fade, 0};
        std::memcpy(block + 16, tail, sizeof(tail));
        bufSub(ubo, 0, sizeof(block), block);
        gl.ProgramUniformMatrix4fv(prog, gl.GetUniformLocation(prog, "invVP"), 1, 0, inv);
        gl.ProgramUniform2f(prog, gl.GetUniformLocation(prog, "size"), float(W), float(H));
        gl.UseProgram(prog);
        gl.DispatchCompute((W + 7) / 8, (H + 7) / 8, 1);
        gl.MemoryBarrierGL(GL_ALL_BARRIER_BITS);
        std::vector<float> glsl(size_t(W) * H * 4);
        getSub(out, 0, intptr_t(glsl.size() * 4), glsl.data());

        AtlasFrame f{};
        f.cameraPos[1] = 120;
        std::memcpy(f.invViewProj, inv, sizeof(inv));
        f.width = W;
        f.height = H;
        f.maxDistance = 40000;
        f.sunDir[0] = sun[0];
        f.sunDir[1] = sun[1];
        f.sunDir[2] = sun[2];
        f.lodBias = 1;
        f.flags = ATLAS_FRAME_SKY;
        std::vector<float> slang(size_t(W) * H * 4);
        AtlasFrameOutput o{nullptr, nullptr, nullptr, slang.data()};
        atlas_render(ctx, &f, &o);
        double worst = 0;
        size_t bad = 0;
        for (size_t i = 0; i < size_t(W) * H; i++)
            for (int k = 0; k < 3; k++) {
                double a = slang[i * 4 + k], b = glsl[i * 4 + k];
                double rel = std::fabs(a - b) / std::fmax(1e-3, std::fabs(a));
                worst = std::fmax(worst, rel);
                if (rel > 5e-3) bad++;
            }
        std::printf("sun (%.2f, %.2f, %.2f): the game's GLSL sky vs atlas's worst rel diff %.2e, %zu channels over 5e-3\n",
                    sun[0], sun[1], sun[2], worst, bad);
        if (bad) failures++;
    }
    atlas_destroy(ctx);
    std::printf(failures ? "FAIL\n" : "PASS\n");
    return failures ? 1 : 0;
}
