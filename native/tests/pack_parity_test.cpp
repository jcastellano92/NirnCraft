// The shader pack's atmosphere (shaderpack/SkycraftAether/shaders/lib/atmosphere.glsl) must match aether's
// (native/shaders/aether_shared.slang): the near field fogs into the far field's haze, so a mismatch is a visible
// seam at the hand-off. A GL compute shader built from the pack's own file evaluates skyColor() for every pixel of a
// camera; aether renders the same camera over an empty world (all sky). They must agree to float precision.
//   aether_pack_test [shaderpack/SkycraftAether/shaders/lib/atmosphere.glsl]

#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "camera.hpp"
#include "gl_api.hpp"
#include "gl_context.hpp"

using namespace aether;

int main(int argc, char** argv) {
    std::string libPath = argc > 1 ? argv[1] : "shaderpack/SkycraftAether/shaders/lib/atmosphere.glsl";
    std::ifstream in(libPath);
    if (!in) {
        std::printf("SKIP: %s not found\n", libPath.c_str());
        return 0;
    }
    std::stringstream lib;
    lib << in.rdbuf();
    aether_test::GlContext glc;
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
    } cases[] = {{{0.3f, 0.55f, -0.78f}}, {{0.9f, 0.08f, 0.42f}}, {{-0.2f, -0.06f, 0.97f}}};

    std::string src = std::string("#version 430\n") + lib.str() + R"GLSL(
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, binding = 0) buffer Out { vec4 result[]; };
uniform mat4 invVP;
uniform vec3 sunDir;
uniform vec2 size;
void main() {
    uvec2 p = gl_GlobalInvocationID.xy;
    if (p.x >= uint(size.x) || p.y >= uint(size.y)) return;
    vec2 ndc = vec2((float(p.x) + 0.5) / size.x * 2.0 - 1.0, 1.0 - (float(p.y) + 0.5) / size.y * 2.0);
    vec4 q = invVP * vec4(ndc, 0.5, 1.0);
    vec3 dir = normalize(q.xyz / q.w);
    result[p.y * uint(size.x) + p.x] = vec4(skyColor(dir, sunDir), 1.0);
}
)GLSL";
    const char* s = src.c_str();
    GLuint sh = gl.CreateShader(GL_COMPUTE_SHADER);
    gl.ShaderSource(sh, 1, &s, nullptr);
    gl.CompileShader(sh);
    GLint ok = 0;
    gl.GetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048] = {};
        gl.GetShaderInfoLog(sh, sizeof(log), nullptr, log);
        std::printf("FAIL: the pack's atmosphere.glsl does not compile as GLSL 4.30:\n%s\n", log);
        return 1;
    }
    GLuint prog = gl.CreateProgram();
    gl.AttachShader(prog, sh);
    gl.LinkProgram(prog);
    // a storage buffer through the minimal loader: use a texture-free path with glBindBufferBase
    auto genBuffers = reinterpret_cast<void(AETHER_GLAPI*)(GLsizei, GLuint*)>(GlApi::proc("glCreateBuffers"));
    auto bufStorage = reinterpret_cast<void(AETHER_GLAPI*)(GLuint, intptr_t, const void*, unsigned)>(GlApi::proc("glNamedBufferStorage"));
    auto bindBase = reinterpret_cast<void(AETHER_GLAPI*)(GLenum, GLuint, GLuint)>(GlApi::proc("glBindBufferBase"));
    auto getSub = reinterpret_cast<void(AETHER_GLAPI*)(GLuint, intptr_t, intptr_t, void*)>(GlApi::proc("glGetNamedBufferSubData"));
    GLuint buf = 0;
    genBuffers(1, &buf);
    bufStorage(buf, intptr_t(W) * H * 16, nullptr, 0x0100 /* GL_DYNAMIC_STORAGE_BIT */);
    bindBase(0x90D2 /* GL_SHADER_STORAGE_BUFFER */, 0, buf);

    AetherContext* ctx = nullptr;
    aether_create(AETHER_BACKEND_CPU, &ctx);
    double centre[3] = {0, 120, 0};
    aether_update(ctx, centre, 0);  // no tiles: every ray misses, the frame is all sky
    int failures = 0;
    for (const Case& c : cases) {
        float l = std::sqrt(c.sun[0] * c.sun[0] + c.sun[1] * c.sun[1] + c.sun[2] * c.sun[2]);
        float sun[3] = {c.sun[0] / l, c.sun[1] / l, c.sun[2] / l};
        gl.ProgramUniformMatrix4fv(prog, gl.GetUniformLocation(prog, "invVP"), 1, 0, inv);
        auto u3 = reinterpret_cast<void(AETHER_GLAPI*)(GLuint, GLint, float, float, float)>(GlApi::proc("glProgramUniform3f"));
        u3(prog, gl.GetUniformLocation(prog, "sunDir"), sun[0], sun[1], sun[2]);
        gl.ProgramUniform2f(prog, gl.GetUniformLocation(prog, "size"), float(W), float(H));
        gl.UseProgram(prog);
        gl.DispatchCompute((W + 7) / 8, (H + 7) / 8, 1);
        gl.MemoryBarrierGL(GL_ALL_BARRIER_BITS);
        std::vector<float> glsl(size_t(W) * H * 4);
        getSub(buf, 0, intptr_t(glsl.size() * 4), glsl.data());

        AetherFrame f{};
        f.cameraPos[1] = 120;
        std::memcpy(f.invViewProj, inv, sizeof(inv));
        f.width = W;
        f.height = H;
        f.maxDistance = 40000;
        f.sunDir[0] = sun[0];
        f.sunDir[1] = sun[1];
        f.sunDir[2] = sun[2];
        f.lodBias = 1;
        f.flags = AETHER_FRAME_SKY;
        std::vector<float> slang(size_t(W) * H * 4);
        AetherFrameOutput o{nullptr, nullptr, nullptr, slang.data()};
        aether_render(ctx, &f, &o);
        double worst = 0;
        size_t bad = 0;
        for (size_t i = 0; i < size_t(W) * H; i++)
            for (int k = 0; k < 3; k++) {
                double a = slang[i * 4 + k], b = glsl[i * 4 + k];
                double rel = std::fabs(a - b) / std::fmax(1e-3, std::fabs(a));
                worst = std::fmax(worst, rel);
                if (rel > 2e-3) bad++;
            }
        std::printf("sun (%.2f, %.2f, %.2f): pack vs aether sky worst rel diff %.2e, %zu channels over 2e-3\n", sun[0],
                    sun[1], sun[2], worst, bad);
        if (bad) failures++;
    }
    aether_destroy(ctx);
    std::printf(failures ? "FAIL\n" : "PASS\n");
    return failures ? 1 : 0;
}
