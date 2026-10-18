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
                 GL_DEVICE_UUID_EXT = 0x9597, GL_NUM_DEVICE_UUIDS_EXT = 0x9596, GL_NO_ERROR = 0;

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

    // Loads from the current context; false + err names what is missing.
    bool load(std::string& err);
};

}  // namespace aether
