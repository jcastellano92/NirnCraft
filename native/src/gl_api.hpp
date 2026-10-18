// The handful of OpenGL entry points the Vulkan<->GL bridge needs (EXT_memory_object(_win32), EXT_semaphore(_win32),
// DSA textures). Loaded from the caller's current context on the render thread; no GL headers or loader library.
#pragma once
#include <cstdint>
#include <string>

namespace aether {

using GLenum = unsigned int;
using GLuint = unsigned int;
using GLint = int;
using GLsizei = int;
using GLuint64 = uint64_t;
using GLubyte = unsigned char;

constexpr GLenum GL_TEXTURE_2D = 0x0DE1, GL_RGBA8 = 0x8058, GL_RGBA16F = 0x881A, GL_R32F = 0x822E, GL_RGBA = 0x1908, GL_RED = 0x1903,
                 GL_UNSIGNED_BYTE = 0x1401, GL_FLOAT = 0x1406, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02,
                 GL_TEXTURE_MIN_FILTER = 0x2801, GL_TEXTURE_MAG_FILTER = 0x2800, GL_NEAREST = 0x2600,
                 GL_TEXTURE_WRAP_S = 0x2802, GL_TEXTURE_WRAP_T = 0x2803, GL_CLAMP_TO_EDGE = 0x812F,
                 GL_HANDLE_TYPE_OPAQUE_WIN32_EXT = 0x9587, GL_DEDICATED_MEMORY_OBJECT_EXT = 0x9581,
                 GL_TEXTURE_TILING_EXT = 0x9580, GL_OPTIMAL_TILING_EXT = 0x9584, GL_LAYOUT_GENERAL_EXT = 0x958D,
                 GL_DEVICE_UUID_EXT = 0x9597, GL_NUM_DEVICE_UUIDS_EXT = 0x9596, GL_NO_ERROR = 0,
                 GL_COMPUTE_SHADER = 0x91B9, GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82,
                 GL_CURRENT_PROGRAM = 0x8B8D, GL_READ_ONLY = 0x88B8, GL_WRITE_ONLY = 0x88B9,
                 GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x20, GL_TEXTURE_FETCH_BARRIER_BIT = 0x8, GL_ALL_BARRIER_BITS = 0xFFFFFFFF,
                 GL_DEPTH_COMPONENT32F = 0x8CAC, GL_DEPTH_COMPONENT = 0x1902, GL_TEXTURE_BINDING_2D = 0x8069,
                 GL_ACTIVE_TEXTURE = 0x84E0, GL_TEXTURE0 = 0x84C0, GL_IMAGE_BINDING_NAME = 0x8F3A,
                 GL_TEXTURE_COMPARE_MODE = 0x884C, GL_NONE = 0, GL_SAMPLER_BINDING = 0x8919,
                 GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6, GL_DEPTH_ATTACHMENT = 0x8D00, GL_FRAMEBUFFER = 0x8D40,
                 GL_DRAW_FRAMEBUFFER = 0x8CA9, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE = 0x8CD0,
                 GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME = 0x8CD1, GL_TEXTURE = 0x1702,
                 GL_CLIP_DEPTH_MODE = 0x935D, GL_ZERO_TO_ONE = 0x935F;

struct GlApi {
    const GLubyte*(__stdcall* GetString)(GLenum) = nullptr;
    GLenum(__stdcall* GetError)() = nullptr;
    void(__stdcall* Flush)() = nullptr;
    void(__stdcall* Finish)() = nullptr;
    void(__stdcall* GetIntegerv)(GLenum, GLint*) = nullptr;
    void(__stdcall* DeleteTextures)(GLsizei, const GLuint*) = nullptr;
    void(__stdcall* CreateTextures)(GLenum, GLsizei, GLuint*) = nullptr;
    void(__stdcall* TextureParameteri)(GLuint, GLenum, GLint) = nullptr;
    void(__stdcall* GetTextureImage)(GLuint, GLint, GLenum, GLenum, GLsizei, void*) = nullptr;
    void(__stdcall* ClearTexImage)(GLuint, GLint, GLenum, GLenum, const void*) = nullptr;
    void(__stdcall* ClearTexSubImage)(GLuint, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei, GLenum, GLenum,
                                      const void*) = nullptr;
    void(__stdcall* CreateMemoryObjectsEXT)(GLsizei, GLuint*) = nullptr;
    void(__stdcall* DeleteMemoryObjectsEXT)(GLsizei, const GLuint*) = nullptr;
    void(__stdcall* MemoryObjectParameterivEXT)(GLuint, GLenum, const GLint*) = nullptr;
    void(__stdcall* ImportMemoryWin32HandleEXT)(GLuint, GLuint64, GLenum, void*) = nullptr;
    void(__stdcall* TextureStorageMem2DEXT)(GLuint, GLsizei, GLenum, GLsizei, GLsizei, GLuint, GLuint64) = nullptr;
    void(__stdcall* GenSemaphoresEXT)(GLsizei, GLuint*) = nullptr;
    void(__stdcall* DeleteSemaphoresEXT)(GLsizei, const GLuint*) = nullptr;
    void(__stdcall* ImportSemaphoreWin32HandleEXT)(GLuint, GLenum, void*) = nullptr;
    void(__stdcall* SignalSemaphoreEXT)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*) = nullptr;
    void(__stdcall* WaitSemaphoreEXT)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*) = nullptr;
    void(__stdcall* GetUnsignedBytei_vEXT)(GLenum, GLuint, GLubyte*) = nullptr;
    // near-depth linearisation pass and destination copies
    GLuint(__stdcall* CreateShader)(GLenum) = nullptr;
    void(__stdcall* ShaderSource)(GLuint, GLsizei, const char* const*, const GLint*) = nullptr;
    void(__stdcall* CompileShader)(GLuint) = nullptr;
    void(__stdcall* GetShaderiv)(GLuint, GLenum, GLint*) = nullptr;
    void(__stdcall* GetShaderInfoLog)(GLuint, GLsizei, GLsizei*, char*) = nullptr;
    void(__stdcall* DeleteShader)(GLuint) = nullptr;
    GLuint(__stdcall* CreateProgram)() = nullptr;
    void(__stdcall* AttachShader)(GLuint, GLuint) = nullptr;
    void(__stdcall* LinkProgram)(GLuint) = nullptr;
    void(__stdcall* GetProgramiv)(GLuint, GLenum, GLint*) = nullptr;
    void(__stdcall* DeleteProgram)(GLuint) = nullptr;
    void(__stdcall* UseProgram)(GLuint) = nullptr;
    GLint(__stdcall* GetUniformLocation)(GLuint, const char*) = nullptr;
    void(__stdcall* ProgramUniformMatrix4fv)(GLuint, GLint, GLsizei, unsigned char, const float*) = nullptr;
    void(__stdcall* ProgramUniform2f)(GLuint, GLint, float, float) = nullptr;
    void(__stdcall* ProgramUniform1i)(GLuint, GLint, GLint) = nullptr;
    void(__stdcall* ProgramUniform1f)(GLuint, GLint, float) = nullptr;
    void(__stdcall* BindTextureUnit)(GLuint, GLuint) = nullptr;
    void(__stdcall* BindImageTexture)(GLuint, GLuint, GLint, unsigned char, GLint, GLenum, GLenum) = nullptr;
    void(__stdcall* DispatchCompute)(GLuint, GLuint, GLuint) = nullptr;
    void(__stdcall* MemoryBarrierGL)(unsigned int) = nullptr;  // glMemoryBarrier (MemoryBarrier is a Windows macro)
    void(__stdcall* CopyImageSubData)(GLuint, GLenum, GLint, GLint, GLint, GLint, GLuint, GLenum, GLint, GLint, GLint,
                                      GLint, GLsizei, GLsizei, GLsizei) = nullptr;
    void(__stdcall* GetIntegeri_v)(GLenum, GLuint, GLint*) = nullptr;
    void(__stdcall* ActiveTexture)(GLenum) = nullptr;
    void(__stdcall* BindTexture)(GLenum, GLuint) = nullptr;
    void(__stdcall* TextureStorage2D)(GLuint, GLsizei, GLenum, GLsizei, GLsizei) = nullptr;
    void(__stdcall* CreateSamplers)(GLsizei, GLuint*) = nullptr;
    void(__stdcall* DeleteSamplers)(GLsizei, const GLuint*) = nullptr;
    void(__stdcall* SamplerParameteri)(GLuint, GLenum, GLint) = nullptr;
    void(__stdcall* BindSampler)(GLuint, GLuint) = nullptr;
    void(__stdcall* TextureSubImage2D)(GLuint, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void*) = nullptr;
    void(__stdcall* GetNamedFramebufferAttachmentParameteriv)(GLuint, GLenum, GLenum, GLint*) = nullptr;
    void(__stdcall* CreateFramebuffers)(GLsizei, GLuint*) = nullptr;
    void(__stdcall* DeleteFramebuffers)(GLsizei, const GLuint*) = nullptr;
    void(__stdcall* NamedFramebufferTexture)(GLuint, GLenum, GLuint, GLint) = nullptr;
    void(__stdcall* BindFramebuffer)(GLenum, GLuint) = nullptr;

    // Loads from the current context; false + err names what is missing.
    bool load(std::string& err);
};

}  // namespace aether
