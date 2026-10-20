#include "gl_api.hpp"

#include <cstring>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace atlas {
namespace {

#if defined(_WIN32)

void* getProc(const char* name) {
    void* p = reinterpret_cast<void*>(wglGetProcAddress(name));
    // wglGetProcAddress returns small sentinels for unsupported names and nothing for GL 1.1 core functions
    auto v = reinterpret_cast<intptr_t>(p);
    if (v == 0 || v == 1 || v == 2 || v == 3 || v == -1) {
        HMODULE gl = GetModuleHandleA("opengl32.dll");
        if (!gl) gl = LoadLibraryA("opengl32.dll");
        p = gl ? reinterpret_cast<void*>(GetProcAddress(gl, name)) : nullptr;
    }
    return p;
}

bool current() { return wglGetCurrentContext() != nullptr; }
const char* winsys() { return "wgl"; }

#else

// The game's GLFW context is current on this thread; it was made by EGL (Wayland, or GLFW_PLATFORM=wayland) or GLX
// (X11 / XWayland). Both libraries are normally already loaded (RTLD_NOLOAD finds them); otherwise they are opened
// just to ask (a library that is not loaded cannot own the current context, so this only happens for the probe).
using GetProcFn = void* (*)(const char*);
using CurrentFn = void* (*)();

struct WindowSystem {
    const char* name = nullptr;
    void* lib = nullptr;
    CurrentFn current = nullptr;
    GetProcFn getProc = nullptr;
};

void* openLib(const char* const* names, bool load) {
    for (const char* const* n = names; *n; n++)
        if (void* h = dlopen(*n, RTLD_LAZY | RTLD_LOCAL | RTLD_NOLOAD)) return h;
    if (!load) return nullptr;
    for (const char* const* n = names; *n; n++)
        if (void* h = dlopen(*n, RTLD_LAZY | RTLD_LOCAL)) return h;
    return nullptr;
}

WindowSystem makeSystem(const char* name, const char* const* libs, const char* currentSym, const char* procSym) {
    WindowSystem w;
    w.name = name;
    w.lib = openLib(libs, true);
    if (w.lib) {
        w.current = reinterpret_cast<CurrentFn>(dlsym(w.lib, currentSym));
        w.getProc = reinterpret_cast<GetProcFn>(dlsym(w.lib, procSym));
    }
    return w;
}

const WindowSystem& egl() {
    static const char* const libs[] = {"libEGL.so.1", "libEGL.so", nullptr};
    static WindowSystem w = makeSystem("egl", libs, "eglGetCurrentContext", "eglGetProcAddress");
    return w;
}

const WindowSystem& glx() {
    static const char* const libs[] = {"libGLX.so.0", "libGL.so.1", "libGL.so", nullptr};
    static WindowSystem w = makeSystem("glx", libs, "glXGetCurrentContext", "glXGetProcAddressARB");
    return w;
}

const WindowSystem* active() {
    // EGL first: under GLVND a process can have libGL loaded (LWJGL does) while EGL owns the context
    if (egl().current && egl().current()) return &egl();
    if (glx().current && glx().current()) return &glx();
    return nullptr;
}

void* getProc(const char* name) {
    const WindowSystem* w = active();
    void* p = w && w->getProc ? w->getProc(name) : nullptr;
    if (!p) p = dlsym(RTLD_DEFAULT, name);  // GL 1.x core from libGL / libOpenGL when getProc skips them
    if (!p) {
        static const char* const libs[] = {"libOpenGL.so.0", "libGL.so.1", nullptr};
        if (void* gl = openLib(libs, false)) p = dlsym(gl, name);
    }
    return p;
}

bool current() { return active() != nullptr; }
const char* winsys() {
    const WindowSystem* w = active();
    return w ? w->name : "none";
}

#endif

}  // namespace

bool GlApi::contextCurrent() { return current(); }
const char* GlApi::windowSystem() { return winsys(); }
void* GlApi::proc(const char* name) { return getProc(name); }

bool GlApi::hasExtension(const char* name) const {
    std::string key = std::string(" ") + name + " ";
    return extensions.find(key) != std::string::npos;
}

bool GlApi::deviceUuids(uint8_t (*out)[16], int max, int& count) const {
    count = 0;
    if (!memoryObject || !GetUnsignedBytei_vEXT) return false;
    GLint n = 0;
    GetIntegerv(GL_NUM_DEVICE_UUIDS_EXT, &n);
    for (GLint i = 0; i < n && count < max; i++) GetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, GLuint(i), out[count++]);
    while (GetError() != GL_NO_ERROR) {}
    return count > 0;
}

bool GlApi::driverUuid(uint8_t out[16]) const {
    if (!memoryObject || !GetUnsignedBytevEXT) return false;
    std::memset(out, 0, 16);
    GetUnsignedBytevEXT(GL_DRIVER_UUID_EXT, out);
    return GetError() == GL_NO_ERROR;
}

bool GlApi::load(std::string& err) {
    if (!current()) {
        err = "no current OpenGL context on this thread";
        return false;
    }
    std::string missing;
#define LOAD(field, name)                                                  \
    field = reinterpret_cast<decltype(field)>(getProc(name));              \
    if (!field) missing += std::string(missing.empty() ? "" : ", ") + name;
#define OPT(field, name) field = reinterpret_cast<decltype(field)>(getProc(name));
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
    LOAD(GetStringi, "glGetStringi")
    LOAD(PixelStorei, "glPixelStorei")
    LOAD(BindBuffer, "glBindBuffer")
    // interop: optional, and only trusted when the extension is advertised (GLX hands out a stub for any name)
    OPT(CreateMemoryObjectsEXT, "glCreateMemoryObjectsEXT")
    OPT(DeleteMemoryObjectsEXT, "glDeleteMemoryObjectsEXT")
    OPT(MemoryObjectParameterivEXT, "glMemoryObjectParameterivEXT")
    OPT(TextureStorageMem2DEXT, "glTextureStorageMem2DEXT")
    OPT(GetUnsignedBytei_vEXT, "glGetUnsignedBytei_vEXT")
    OPT(GetUnsignedBytevEXT, "glGetUnsignedBytevEXT")
    OPT(ImportMemoryWin32HandleEXT, "glImportMemoryWin32HandleEXT")
    OPT(ImportMemoryFdEXT, "glImportMemoryFdEXT")
    OPT(GenSemaphoresEXT, "glGenSemaphoresEXT")
    OPT(DeleteSemaphoresEXT, "glDeleteSemaphoresEXT")
    OPT(SignalSemaphoreEXT, "glSignalSemaphoreEXT")
    OPT(WaitSemaphoreEXT, "glWaitSemaphoreEXT")
    OPT(ImportSemaphoreWin32HandleEXT, "glImportSemaphoreWin32HandleEXT")
    OPT(ImportSemaphoreFdEXT, "glImportSemaphoreFdEXT")
    OPT(CreateBuffers, "glCreateBuffers")
    OPT(DeleteBuffers, "glDeleteBuffers")
    OPT(NamedBufferStorage, "glNamedBufferStorage")
    OPT(MapNamedBufferRange, "glMapNamedBufferRange")
    OPT(UnmapNamedBuffer, "glUnmapNamedBuffer")
    OPT(FenceSync, "glFenceSync")
    OPT(ClientWaitSync, "glClientWaitSync")
    OPT(DeleteSync, "glDeleteSync")
#undef LOAD
#undef OPT
    if (!missing.empty()) {
        err = "OpenGL lacks " + missing + " (atlas needs OpenGL 4.5)";
        return false;
    }
    GetIntegerv(GL_MAJOR_VERSION, &major);
    GetIntegerv(GL_MINOR_VERSION, &minor);
    while (GetError() != GL_NO_ERROR) {}
    extensions = " ";
    GLint n = 0;
    GetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (GLint i = 0; i < n; i++)
        if (const GLubyte* e = GetStringi(GL_EXTENSIONS, GLuint(i))) extensions += reinterpret_cast<const char*>(e) + std::string(" ");
    // what atlas uses is 4.5 core, or the same as extensions on an older context: Minecraft asks for a 3.3 core
    // profile, and drivers expose direct state access and image copies there (NVIDIA, Mesa, AMD)
    bool core45 = major > 4 || (major == 4 && minor >= 5);
    if (!core45 && !(hasExtension("GL_ARB_direct_state_access") && hasExtension("GL_ARB_copy_image"))) {
        const GLubyte* v = GetString(GL_VERSION);
        err = std::string("OpenGL ") + (v ? reinterpret_cast<const char*>(v) : "?") +
              " has neither 4.5 nor GL_ARB_direct_state_access + GL_ARB_copy_image";
        return false;
    }
    memoryObject = hasExtension("GL_EXT_memory_object") && CreateMemoryObjectsEXT && DeleteMemoryObjectsEXT &&
                   MemoryObjectParameterivEXT && TextureStorageMem2DEXT && GetUnsignedBytei_vEXT;
    memoryObjectWin32 = memoryObject && hasExtension("GL_EXT_memory_object_win32") && ImportMemoryWin32HandleEXT;
    memoryObjectFd = memoryObject && hasExtension("GL_EXT_memory_object_fd") && ImportMemoryFdEXT;
    semaphore = hasExtension("GL_EXT_semaphore") && GenSemaphoresEXT && DeleteSemaphoresEXT && SignalSemaphoreEXT &&
                WaitSemaphoreEXT;
    semaphoreWin32 = semaphore && hasExtension("GL_EXT_semaphore_win32") && ImportSemaphoreWin32HandleEXT;
    semaphoreFd = semaphore && hasExtension("GL_EXT_semaphore_fd") && ImportSemaphoreFdEXT;
    // persistent mapped buffers (4.4 core / ARB_buffer_storage) and fences (3.2 core)
    bool storage = major > 4 || (major == 4 && minor >= 4) || hasExtension("GL_ARB_buffer_storage");
    asyncReadback = storage && CreateBuffers && DeleteBuffers && NamedBufferStorage && MapNamedBufferRange &&
                    UnmapNamedBuffer && FenceSync && ClientWaitSync && DeleteSync;
    return true;
}

}  // namespace atlas
