// The near field's inputs on the GL side, shared by both backends' GL paths: a small compute pass that turns the
// game's depth buffer into view distances at atlas's resolution (R32F, 1e30 at a near silhouette's edge, 2e30 where
// the near field drew nothing) and, with the near light, takes the surface info of the same sample (RGBA8). Rows stay
// bottom-up (GL); whoever feeds the far kernel flips them. Every piece of GL state the pass touches is saved and
// restored (Minecraft's renderer caches its own bindings).
#pragma once
#include <cstdint>
#include <string>

#include "backend.hpp"
#include "gl_api.hpp"

namespace atlas {

inline const char* const NEAR_LINEARIZE_GLSL = R"GLSL(#version 430
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 15) uniform sampler2D atlasNearDepth;
layout(binding = 14) uniform sampler2D atlasNearInfo;
layout(r32f, binding = 7) uniform writeonly image2D atlasNearOut;
layout(rgba8, binding = 6) uniform writeonly image2D atlasInfoOut;
uniform mat4 atlasInvProj;
uniform vec2 atlasSize;
uniform int atlasZeroToOne;
uniform int atlasReversed;
uniform int atlasInfo;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= int(atlasSize.x) || p.y >= int(atlasSize.y)) return;
    // atlas may render below the window's resolution (dynamic scale): an atlas pixel takes the farthest of four
    // samples over its footprint, so it is skipped only where near geometry covers all of it (at scale 1 all four
    // land on the same texel: the exact depth)
    ivec2 full = textureSize(atlasNearDepth, 0);
    vec2 f = vec2(full) / atlasSize;
    float dist = 0.0;
    int skies = 0;
    ivec2 pick = ivec2(0);
    for (int k = 0; k < 4; k++) {
        vec2 o = vec2(k & 1, k >> 1) * 0.5 + 0.25;
        ivec2 q = clamp(ivec2((vec2(p) + o) * f), ivec2(0), full - 1);
        float d = texelFetch(atlasNearDepth, q, 0).r;
        bool sky = atlasReversed != 0 ? d <= 0.0 : d >= 1.0;
        float z = atlasZeroToOne != 0 ? d : d * 2.0 - 1.0;
        vec2 ndc = (vec2(q) + 0.5) / vec2(full) * 2.0 - 1.0;
        vec4 v = atlasInvProj * vec4(ndc, z, 1.0);
        float dk = sky ? 1e30 : length(v.xyz / v.w);
        if (k == 0 || dk > dist) pick = q;
        dist = max(dist, dk);
        skies += sky ? 1 : 0;
    }
    // all four sky: 2e30 (the near field drew nothing here: a chunk not loaded, the far field may fill from the
    // camera); some sky: 1e30 (a near silhouette's edge: the far field only behind it, never its own copy of the
    // near object, which drew a moving outline around every tree and ridge against the sky)
    imageStore(atlasNearOut, p, vec4(skies == 4 ? 2e30 : dist));
    // the surface whose distance was taken (the near light lights the point at that distance)
    if (atlasInfo != 0) {
        ivec2 fi = textureSize(atlasNearInfo, 0);
        imageStore(atlasInfoOut, p, texelFetch(atlasNearInfo, clamp(pick * fi / full, ivec2(0), fi - 1), 0));
    }
}
)GLSL";

struct GlNearPass {
    GLuint program = 0, sampler = 0;
    GLint uInvProj = -1, uSize = -1, uZeroToOne = -1, uReversed = -1, uInfo = -1;

    // io's near depth (a texture, or ATLAS_GL_BOUND_DEPTH: the bound draw framebuffer's depth attachment) into
    // dstDepth (R32F, w x h), and with info io.nearInfoTex into dstInfo (RGBA8). No depth to read this frame: dstDepth
    // is cleared to uncovered rather than left stale. False + err when the program does not build.
    bool run(GlApi& gl, const GlFrameIO& io, uint32_t w, uint32_t h, bool info, GLuint dstDepth, GLuint dstInfo,
             std::string& err) {
        GLuint depthTex = io.nearDepthTex;
        if (depthTex == 0xFFFFFFFFu) {
            GLint fbo = 0, type = 0, name = 0;
            gl.GetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fbo);
            if (fbo != 0) {
                gl.GetNamedFramebufferAttachmentParameteriv(GLuint(fbo), GL_DEPTH_ATTACHMENT,
                                                            GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, &type);
                if (GLenum(type) == GL_TEXTURE)  // a renderbuffer cannot be sampled
                    gl.GetNamedFramebufferAttachmentParameteriv(GLuint(fbo), GL_DEPTH_ATTACHMENT,
                                                                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, &name);
            }
            depthTex = GLuint(name);
            if (!depthTex) {
                float uncovered = 1e30f;
                gl.ClearTexImage(dstDepth, 0, GL_RED, GL_FLOAT, &uncovered);
                return true;
            }
        }
        if (!program) {
            GLuint sh = gl.CreateShader(GL_COMPUTE_SHADER);
            gl.ShaderSource(sh, 1, &NEAR_LINEARIZE_GLSL, nullptr);
            gl.CompileShader(sh);
            GLint ok = 0;
            gl.GetShaderiv(sh, GL_COMPILE_STATUS, &ok);
            if (!ok) {
                char log[1024] = {};
                gl.GetShaderInfoLog(sh, sizeof(log), nullptr, log);
                gl.DeleteShader(sh);
                err = std::string("near-depth GL shader: ") + log;
                return false;
            }
            program = gl.CreateProgram();
            gl.AttachShader(program, sh);
            gl.LinkProgram(program);
            gl.DeleteShader(sh);
            gl.GetProgramiv(program, GL_LINK_STATUS, &ok);
            if (!ok) {
                err = "near-depth GL program link failed";
                return false;
            }
            uInvProj = gl.GetUniformLocation(program, "atlasInvProj");
            uSize = gl.GetUniformLocation(program, "atlasSize");
            uZeroToOne = gl.GetUniformLocation(program, "atlasZeroToOne");
            uReversed = gl.GetUniformLocation(program, "atlasReversed");
            uInfo = gl.GetUniformLocation(program, "atlasInfo");
            gl.CreateSamplers(1, &sampler);
            gl.SamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            gl.SamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            gl.SamplerParameteri(sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        }
        gl.ProgramUniformMatrix4fv(program, uInvProj, 1, 0, io.nearInvProjection);
        gl.ProgramUniform2f(program, uSize, float(w), float(h));
        GLint zeroToOne = GLint(io.depthZeroToOne);
        if (io.depthZeroToOne == 0xFFFFFFFFu) {
            GLint mode = 0;
            gl.GetIntegerv(GL_CLIP_DEPTH_MODE, &mode);
            zeroToOne = GLenum(mode) == GL_ZERO_TO_ONE ? 1 : 0;
        }
        gl.ProgramUniform1i(program, uZeroToOne, zeroToOne);
        gl.ProgramUniform1i(program, uReversed, GLint(io.reversedZ));
        gl.ProgramUniform1i(program, uInfo, info ? 1 : 0);
        GLint prevProgram = 0, prevActive = 0, prevTex = 0, prevSampler = 0, prevImage = 0;
        GLint prevInfoTex = 0, prevInfoSampler = 0, prevInfoImage = 0;
        gl.GetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
        gl.GetIntegerv(GL_ACTIVE_TEXTURE, &prevActive);
        gl.ActiveTexture(GL_TEXTURE0 + 15);
        gl.GetIntegerv(GL_TEXTURE_BINDING_2D, &prevTex);
        gl.GetIntegerv(GL_SAMPLER_BINDING, &prevSampler);
        gl.GetIntegeri_v(GL_IMAGE_BINDING_NAME, 7, &prevImage);
        if (info) {
            gl.ActiveTexture(GL_TEXTURE0 + 14);
            gl.GetIntegerv(GL_TEXTURE_BINDING_2D, &prevInfoTex);
            gl.GetIntegerv(GL_SAMPLER_BINDING, &prevInfoSampler);
            gl.GetIntegeri_v(GL_IMAGE_BINDING_NAME, 6, &prevInfoImage);
        }
        gl.ActiveTexture(GLenum(prevActive));

        gl.UseProgram(program);
        gl.BindTextureUnit(15, depthTex);
        gl.BindSampler(15, sampler);
        gl.BindImageTexture(7, dstDepth, 0, 0, 0, GL_WRITE_ONLY, GL_R32F);
        if (info) {
            gl.BindTextureUnit(14, io.nearInfoTex);
            gl.BindSampler(14, sampler);
            gl.BindImageTexture(6, dstInfo, 0, 0, 0, GL_WRITE_ONLY, GL_RGBA8);
        }
        gl.DispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
        gl.MemoryBarrierGL(GL_ALL_BARRIER_BITS);

        gl.UseProgram(GLuint(prevProgram));
        gl.BindTextureUnit(15, GLuint(prevTex));
        gl.BindSampler(15, GLuint(prevSampler));
        gl.BindImageTexture(7, GLuint(prevImage), 0, 0, 0, GL_WRITE_ONLY, GL_R32F);
        if (info) {
            gl.BindTextureUnit(14, GLuint(prevInfoTex));
            gl.BindSampler(14, GLuint(prevInfoSampler));
            gl.BindImageTexture(6, GLuint(prevInfoImage), 0, 0, 0, GL_WRITE_ONLY, GL_RGBA8);
        }
        return true;
    }

    // GL objects freed (glOk: the context is current), the pass reset either way.
    void destroy(GlApi& gl, bool glOk) {
        if (glOk) {
            if (program) gl.DeleteProgram(program);
            if (sampler) gl.DeleteSamplers(1, &sampler);
        }
        program = 0;
        sampler = 0;
    }
};

}  // namespace atlas
