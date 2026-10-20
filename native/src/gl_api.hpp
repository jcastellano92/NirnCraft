// The handful of OpenGL entry points the Vulkan<->GL bridge needs (EXT_memory_object + _win32 / _fd,
// EXT_semaphore + _win32 / _fd, DSA textures). Loaded from the caller's current context on the render thread:
// wglGetProcAddress on Windows; on Linux eglGetProcAddress or glXGetProcAddressARB, whichever window system owns the
// current context (GLFW uses GLX on X11/XWayland, EGL on Wayland). No GL headers or loader library.
#pragma once
#include <cstdint>
#include <string>

#if defined(_WIN32)
#define ATLAS_GLAPI __stdcall
#else
#define ATLAS_GLAPI
#endif

namespace atlas {

using GLenum = unsigned int;
using GLuint = unsigned int;
using GLint = int;
using GLsizei = int;
using GLuint64 = uint64_t;
using GLubyte = unsigned char;

constexpr GLenum GL_TEXTURE_2D = 0x0DE1, GL_RGBA8 = 0x8058, GL_RGBA16F = 0x881A, GL_R32F = 0x822E, GL_RG32F = 0x8230, GL_RG = 0x8227, GL_RGBA = 0x1908, GL_RED = 0x1903,
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
                 GL_CLIP_DEPTH_MODE = 0x935D, GL_ZERO_TO_ONE = 0x935F,
                 GL_HANDLE_TYPE_OPAQUE_FD_EXT = 0x9586, GL_DRIVER_UUID_EXT = 0x9598, GL_VENDOR = 0x1F00,
                 GL_EXTENSIONS = 0x1F03, GL_NUM_EXTENSIONS = 0x821D, GL_MAJOR_VERSION = 0x821B,
                 GL_MINOR_VERSION = 0x821C, GL_HALF_FLOAT = 0x140B, GL_PIXEL_UNPACK_BUFFER = 0x88EC,
                 GL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF, GL_UNPACK_ROW_LENGTH = 0x0CF2, GL_UNPACK_SKIP_ROWS = 0x0CF3,
                 GL_UNPACK_SKIP_PIXELS = 0x0CF4, GL_UNPACK_ALIGNMENT = 0x0CF5, GL_UNPACK_SWAP_BYTES = 0x0CF0,
                 GL_UNPACK_LSB_FIRST = 0x0CF1, GL_PIXEL_PACK_BUFFER = 0x88EB, GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED,
                 GL_PACK_ROW_LENGTH = 0x0D02, GL_PACK_SKIP_ROWS = 0x0D03, GL_PACK_SKIP_PIXELS = 0x0D04,
                 GL_PACK_ALIGNMENT = 0x0D05, GL_PACK_SWAP_BYTES = 0x0D00, GL_PACK_LSB_FIRST = 0x0D01;

struct GlApi {
    const GLubyte*(ATLAS_GLAPI* GetString)(GLenum) = nullptr;
    GLenum(ATLAS_GLAPI* GetError)() = nullptr;
    void(ATLAS_GLAPI* Flush)() = nullptr;
    void(ATLAS_GLAPI* Finish)() = nullptr;
    void(ATLAS_GLAPI* GetIntegerv)(GLenum, GLint*) = nullptr;
    void(ATLAS_GLAPI* DeleteTextures)(GLsizei, const GLuint*) = nullptr;
    void(ATLAS_GLAPI* CreateTextures)(GLenum, GLsizei, GLuint*) = nullptr;
    void(ATLAS_GLAPI* TextureParameteri)(GLuint, GLenum, GLint) = nullptr;
    void(ATLAS_GLAPI* GetTextureImage)(GLuint, GLint, GLenum, GLenum, GLsizei, void*) = nullptr;
    void(ATLAS_GLAPI* ClearTexImage)(GLuint, GLint, GLenum, GLenum, const void*) = nullptr;
    void(ATLAS_GLAPI* ClearTexSubImage)(GLuint, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei, GLenum, GLenum,
                                      const void*) = nullptr;
    void(ATLAS_GLAPI* CreateMemoryObjectsEXT)(GLsizei, GLuint*) = nullptr;
    void(ATLAS_GLAPI* DeleteMemoryObjectsEXT)(GLsizei, const GLuint*) = nullptr;
    void(ATLAS_GLAPI* MemoryObjectParameterivEXT)(GLuint, GLenum, const GLint*) = nullptr;
    void(ATLAS_GLAPI* ImportMemoryWin32HandleEXT)(GLuint, GLuint64, GLenum, void*) = nullptr;
    void(ATLAS_GLAPI* TextureStorageMem2DEXT)(GLuint, GLsizei, GLenum, GLsizei, GLsizei, GLuint, GLuint64) = nullptr;
    void(ATLAS_GLAPI* GenSemaphoresEXT)(GLsizei, GLuint*) = nullptr;
    void(ATLAS_GLAPI* DeleteSemaphoresEXT)(GLsizei, const GLuint*) = nullptr;
    void(ATLAS_GLAPI* ImportSemaphoreWin32HandleEXT)(GLuint, GLenum, void*) = nullptr;
    void(ATLAS_GLAPI* SignalSemaphoreEXT)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*) = nullptr;
    void(ATLAS_GLAPI* WaitSemaphoreEXT)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*) = nullptr;
    void(ATLAS_GLAPI* GetUnsignedBytei_vEXT)(GLenum, GLuint, GLubyte*) = nullptr;
    // near-depth linearisation pass and destination copies
    GLuint(ATLAS_GLAPI* CreateShader)(GLenum) = nullptr;
    void(ATLAS_GLAPI* ShaderSource)(GLuint, GLsizei, const char* const*, const GLint*) = nullptr;
    void(ATLAS_GLAPI* CompileShader)(GLuint) = nullptr;
    void(ATLAS_GLAPI* GetShaderiv)(GLuint, GLenum, GLint*) = nullptr;
    void(ATLAS_GLAPI* GetShaderInfoLog)(GLuint, GLsizei, GLsizei*, char*) = nullptr;
    void(ATLAS_GLAPI* DeleteShader)(GLuint) = nullptr;
    GLuint(ATLAS_GLAPI* CreateProgram)() = nullptr;
    void(ATLAS_GLAPI* AttachShader)(GLuint, GLuint) = nullptr;
    void(ATLAS_GLAPI* LinkProgram)(GLuint) = nullptr;
    void(ATLAS_GLAPI* GetProgramiv)(GLuint, GLenum, GLint*) = nullptr;
    void(ATLAS_GLAPI* DeleteProgram)(GLuint) = nullptr;
    void(ATLAS_GLAPI* UseProgram)(GLuint) = nullptr;
    GLint(ATLAS_GLAPI* GetUniformLocation)(GLuint, const char*) = nullptr;
    void(ATLAS_GLAPI* ProgramUniformMatrix4fv)(GLuint, GLint, GLsizei, unsigned char, const float*) = nullptr;
    void(ATLAS_GLAPI* ProgramUniform2f)(GLuint, GLint, float, float) = nullptr;
    void(ATLAS_GLAPI* ProgramUniform1i)(GLuint, GLint, GLint) = nullptr;
    void(ATLAS_GLAPI* ProgramUniform1f)(GLuint, GLint, float) = nullptr;
    void(ATLAS_GLAPI* BindTextureUnit)(GLuint, GLuint) = nullptr;
    void(ATLAS_GLAPI* BindImageTexture)(GLuint, GLuint, GLint, unsigned char, GLint, GLenum, GLenum) = nullptr;
    void(ATLAS_GLAPI* DispatchCompute)(GLuint, GLuint, GLuint) = nullptr;
    void(ATLAS_GLAPI* MemoryBarrierGL)(unsigned int) = nullptr;  // glMemoryBarrier (MemoryBarrier is a Windows macro)
    void(ATLAS_GLAPI* CopyImageSubData)(GLuint, GLenum, GLint, GLint, GLint, GLint, GLuint, GLenum, GLint, GLint, GLint,
                                      GLint, GLsizei, GLsizei, GLsizei) = nullptr;
    void(ATLAS_GLAPI* GetIntegeri_v)(GLenum, GLuint, GLint*) = nullptr;
    void(ATLAS_GLAPI* ActiveTexture)(GLenum) = nullptr;
    void(ATLAS_GLAPI* BindTexture)(GLenum, GLuint) = nullptr;
    void(ATLAS_GLAPI* TextureStorage2D)(GLuint, GLsizei, GLenum, GLsizei, GLsizei) = nullptr;
    void(ATLAS_GLAPI* CreateSamplers)(GLsizei, GLuint*) = nullptr;
    void(ATLAS_GLAPI* DeleteSamplers)(GLsizei, const GLuint*) = nullptr;
    void(ATLAS_GLAPI* SamplerParameteri)(GLuint, GLenum, GLint) = nullptr;
    void(ATLAS_GLAPI* BindSampler)(GLuint, GLuint) = nullptr;
    void(ATLAS_GLAPI* TextureSubImage2D)(GLuint, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void*) = nullptr;
    void(ATLAS_GLAPI* GetNamedFramebufferAttachmentParameteriv)(GLuint, GLenum, GLenum, GLint*) = nullptr;
    void(ATLAS_GLAPI* CreateFramebuffers)(GLsizei, GLuint*) = nullptr;
    void(ATLAS_GLAPI* DeleteFramebuffers)(GLsizei, const GLuint*) = nullptr;
    void(ATLAS_GLAPI* NamedFramebufferTexture)(GLuint, GLenum, GLuint, GLint) = nullptr;
    void(ATLAS_GLAPI* BindFramebuffer)(GLenum, GLuint) = nullptr;
    const GLubyte*(ATLAS_GLAPI* GetStringi)(GLenum, GLuint) = nullptr;
    void(ATLAS_GLAPI* GetUnsignedBytevEXT)(GLenum, GLubyte*) = nullptr;
    void(ATLAS_GLAPI* ImportMemoryFdEXT)(GLuint, GLuint64, GLenum, GLint) = nullptr;
    void(ATLAS_GLAPI* ImportSemaphoreFdEXT)(GLuint, GLenum, GLint) = nullptr;
    void(ATLAS_GLAPI* PixelStorei)(GLenum, GLint) = nullptr;
    void(ATLAS_GLAPI* BindBuffer)(GLenum, GLuint) = nullptr;

    // What the context offers (filled by load). Interop needs memoryObject + semaphore and the platform's handle
    // flavour (win32 on Windows, fd on Linux); without them atlas falls back to copying frames through the host.
    int major = 0, minor = 0;
    bool memoryObject = false, memoryObjectWin32 = false, memoryObjectFd = false;
    bool semaphore = false, semaphoreWin32 = false, semaphoreFd = false;
    std::string extensions;  // space separated, with a leading and trailing space

    // Loads from the current context: false + err when it lacks the GL 4.5 core this bridge needs (the interop
    // extensions are optional and reported in the flags above).
    bool load(std::string& err);
    bool hasExtension(const char* name) const;
    // The context's device / driver UUIDs (EXT_memory_object), false when it cannot tell.
    bool deviceUuids(uint8_t (*out)[16], int max, int& count) const;
    bool driverUuid(uint8_t out[16]) const;

    // Whether this thread has a current GL context, and which window system API owns it ("wgl", "egl", "glx").
    static bool contextCurrent();
    static const char* windowSystem();
    // Any GL entry point of the current context (null when unknown).
    static void* proc(const char* name);
};

}  // namespace atlas
