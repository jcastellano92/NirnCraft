#include "gl_api.hpp"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

namespace aether {
namespace {

void* proc(HMODULE gl, const char* name) {
    void* p = reinterpret_cast<void*>(wglGetProcAddress(name));
    // wglGetProcAddress returns small sentinels for unsupported names and nothing for GL 1.1 core functions
    auto v = reinterpret_cast<intptr_t>(p);
    if (v == 0 || v == 1 || v == 2 || v == 3 || v == -1) p = reinterpret_cast<void*>(GetProcAddress(gl, name));
    return p;
}

}  // namespace

bool GlApi::load(std::string& err) {
    if (!wglGetCurrentContext()) {
        err = "no current OpenGL context on this thread";
        return false;
    }
    HMODULE gl = GetModuleHandleA("opengl32.dll");
    if (!gl) gl = LoadLibraryA("opengl32.dll");
    std::string missing;
#define LOAD(field, name)                                                  \
    field = reinterpret_cast<decltype(field)>(proc(gl, name));             \
    if (!field) missing += std::string(missing.empty() ? "" : ", ") + name;
    LOAD(GetString, "glGetString")
    LOAD(GetError, "glGetError")
    LOAD(Flush, "glFlush")
    LOAD(Finish, "glFinish")
    LOAD(GetIntegerv, "glGetIntegerv")
    LOAD(DeleteTextures, "glDeleteTextures")
    LOAD(CreateTextures, "glCreateTextures")
    LOAD(TextureParameteri, "glTextureParameteri")
    LOAD(GetTextureImage, "glGetTextureImage")
    LOAD(ClearTexImage, "glClearTexImage")
    LOAD(ClearTexSubImage, "glClearTexSubImage")
    LOAD(CreateMemoryObjectsEXT, "glCreateMemoryObjectsEXT")
    LOAD(DeleteMemoryObjectsEXT, "glDeleteMemoryObjectsEXT")
    LOAD(MemoryObjectParameterivEXT, "glMemoryObjectParameterivEXT")
    LOAD(ImportMemoryWin32HandleEXT, "glImportMemoryWin32HandleEXT")
    LOAD(TextureStorageMem2DEXT, "glTextureStorageMem2DEXT")
    LOAD(GenSemaphoresEXT, "glGenSemaphoresEXT")
    LOAD(DeleteSemaphoresEXT, "glDeleteSemaphoresEXT")
    LOAD(ImportSemaphoreWin32HandleEXT, "glImportSemaphoreWin32HandleEXT")
    LOAD(SignalSemaphoreEXT, "glSignalSemaphoreEXT")
    LOAD(WaitSemaphoreEXT, "glWaitSemaphoreEXT")
    LOAD(GetUnsignedBytei_vEXT, "glGetUnsignedBytei_vEXT")
    LOAD(CreateShader, "glCreateShader")
    LOAD(ShaderSource, "glShaderSource")
    LOAD(CompileShader, "glCompileShader")
    LOAD(GetShaderiv, "glGetShaderiv")
    LOAD(GetShaderInfoLog, "glGetShaderInfoLog")
    LOAD(DeleteShader, "glDeleteShader")
    LOAD(CreateProgram, "glCreateProgram")
    LOAD(AttachShader, "glAttachShader")
    LOAD(LinkProgram, "glLinkProgram")
    LOAD(GetProgramiv, "glGetProgramiv")
    LOAD(DeleteProgram, "glDeleteProgram")
    LOAD(UseProgram, "glUseProgram")
    LOAD(GetUniformLocation, "glGetUniformLocation")
    LOAD(ProgramUniformMatrix4fv, "glProgramUniformMatrix4fv")
    LOAD(ProgramUniform2f, "glProgramUniform2f")
    LOAD(ProgramUniform1i, "glProgramUniform1i")
    LOAD(ProgramUniform1f, "glProgramUniform1f")
    LOAD(BindTextureUnit, "glBindTextureUnit")
    LOAD(BindImageTexture, "glBindImageTexture")
    LOAD(DispatchCompute, "glDispatchCompute")
    LOAD(MemoryBarrierGL, "glMemoryBarrier")
    LOAD(CopyImageSubData, "glCopyImageSubData")
    LOAD(GetIntegeri_v, "glGetIntegeri_v")
    LOAD(ActiveTexture, "glActiveTexture")
    LOAD(BindTexture, "glBindTexture")
    LOAD(TextureStorage2D, "glTextureStorage2D")
    LOAD(CreateSamplers, "glCreateSamplers")
    LOAD(DeleteSamplers, "glDeleteSamplers")
    LOAD(SamplerParameteri, "glSamplerParameteri")
    LOAD(BindSampler, "glBindSampler")
    LOAD(TextureSubImage2D, "glTextureSubImage2D")
    LOAD(GetNamedFramebufferAttachmentParameteriv, "glGetNamedFramebufferAttachmentParameteriv")
    LOAD(CreateFramebuffers, "glCreateFramebuffers")
    LOAD(DeleteFramebuffers, "glDeleteFramebuffers")
    LOAD(NamedFramebufferTexture, "glNamedFramebufferTexture")
    LOAD(BindFramebuffer, "glBindFramebuffer")
#undef LOAD
    if (!missing.empty()) {
        err = "OpenGL lacks " + missing + " (needs GL 4.5 + EXT_memory_object_win32 + EXT_semaphore_win32)";
        return false;
    }
    return true;
}

}  // namespace aether
