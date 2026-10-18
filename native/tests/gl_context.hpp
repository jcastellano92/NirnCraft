// A hidden OpenGL 4.5 context for the GL tests, current on the calling thread.
//  Windows: a hidden window + wglCreateContext (what a Mesa opengl32.dll placed next to the exe also answers).
//  Linux:   EGL surfaceless (works headless, on Wayland, on gamescope, in WSL with llvmpipe) by default, or GLX with a
//           pbuffer when AETHER_TEST_GL=glx (X11 / XWayland: what GLFW uses unless it runs on native Wayland).
// Core profile like Minecraft's (it asks for 3.2 core and drivers hand out their newest core version).
// Libraries are opened at run time: no GL / EGL / X11 development packages are needed to build.
#pragma once
#include <cstdlib>
#include <cstring>
#include <string>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace aether_test {

#if defined(_WIN32)

struct GlContext {
    HDC dc = nullptr;
    HGLRC rc = nullptr;
    const char* kind = "wgl";
    bool create(std::string& err) {
        WNDCLASSA wc{};
        wc.lpfnWndProc = DefWindowProcA;
        wc.hInstance = GetModuleHandleA(nullptr);
        wc.lpszClassName = "aether_gl_context";
        RegisterClassA(&wc);
        HWND wnd = CreateWindowA("aether_gl_context", "aether", WS_OVERLAPPEDWINDOW, 0, 0, 64, 64, nullptr, nullptr,
                                 wc.hInstance, nullptr);
        dc = GetDC(wnd);
        PIXELFORMATDESCRIPTOR pfd{sizeof(pfd), 1, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER, PFD_TYPE_RGBA, 32};
        int pf = ChoosePixelFormat(dc, &pfd);
        SetPixelFormat(dc, pf, &pfd);
        rc = wglCreateContext(dc);
        if (!rc || !wglMakeCurrent(dc, rc)) {
            err = "wglCreateContext / wglMakeCurrent failed";
            return false;
        }
        return true;
    }
    void destroy() {
        wglMakeCurrent(nullptr, nullptr);
        if (rc) wglDeleteContext(rc);
        rc = nullptr;
    }
};

#else

struct GlContext {
    const char* kind = "egl";
    // EGL
    void* egl = nullptr;
    void* dpy = nullptr;
    void* ctx = nullptr;
    // GLX
    void* x11 = nullptr;
    void* glx = nullptr;
    void* xdpy = nullptr;
    unsigned long pbuf = 0;

    bool create(std::string& err) {
        const char* want = std::getenv("AETHER_TEST_GL");
        if (want && std::strcmp(want, "glx") == 0) return createGlx(err);
        return createEgl(err);
    }

    bool createEgl(std::string& err) {
        kind = "egl";
        egl = dlopen("libEGL.so.1", RTLD_NOW | RTLD_GLOBAL);
        if (!egl) return fail(err, "libEGL.so.1 not found");
        using GetProc = void* (*)(const char*);
        auto gp = reinterpret_cast<GetProc>(dlsym(egl, "eglGetProcAddress"));
        auto getPlatformDisplay = reinterpret_cast<void* (*)(unsigned, void*, const intptr_t*)>(dlsym(egl, "eglGetPlatformDisplay"));
        auto getDisplay = reinterpret_cast<void* (*)(void*)>(dlsym(egl, "eglGetDisplay"));
        auto initialize = reinterpret_cast<unsigned (*)(void*, int*, int*)>(dlsym(egl, "eglInitialize"));
        auto bindApi = reinterpret_cast<unsigned (*)(unsigned)>(dlsym(egl, "eglBindAPI"));
        auto chooseConfig = reinterpret_cast<unsigned (*)(void*, const int*, void**, int, int*)>(dlsym(egl, "eglChooseConfig"));
        auto createContext = reinterpret_cast<void* (*)(void*, void*, void*, const int*)>(dlsym(egl, "eglCreateContext"));
        auto makeCurrent = reinterpret_cast<unsigned (*)(void*, void*, void*, void*)>(dlsym(egl, "eglMakeCurrent"));
        if (!gp || !getDisplay || !initialize || !bindApi || !chooseConfig || !createContext || !makeCurrent)
            return fail(err, "libEGL lacks core entry points");
        const unsigned EGL_PLATFORM_SURFACELESS_MESA = 0x31DD, EGL_OPENGL_API = 0x30A2;
        if (getPlatformDisplay) dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, nullptr, nullptr);
        if (!dpy) dpy = getDisplay(nullptr);  // EGL_DEFAULT_DISPLAY
        int maj = 0, min = 0;
        if (!dpy || !initialize(dpy, &maj, &min)) return fail(err, "eglInitialize failed");
        if (!bindApi(EGL_OPENGL_API)) return fail(err, "eglBindAPI(OpenGL) failed");
        const int cfgAttr[] = {0x3040 /*EGL_RENDERABLE_TYPE*/, 0x0008 /*EGL_OPENGL_BIT*/, 0x3033 /*EGL_SURFACE_TYPE*/, 0,
                               0x3038 /*EGL_NONE*/};
        void* cfg = nullptr;
        int n = 0;
        if (!chooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n < 1) {
            // some stacks only list configs with a surface type
            const int cfgAttr2[] = {0x3040, 0x0008, 0x3038};
            if (!chooseConfig(dpy, cfgAttr2, &cfg, 1, &n) || n < 1) return fail(err, "no EGL config for desktop OpenGL");
        }
        const int ctxAttr[] = {0x3098 /*EGL_CONTEXT_MAJOR_VERSION*/, 4, 0x30FB /*EGL_CONTEXT_MINOR_VERSION*/, 5,
                               0x30FD /*EGL_CONTEXT_OPENGL_PROFILE_MASK*/, 1 /*CORE*/, 0x3038};
        ctx = createContext(dpy, cfg, nullptr, ctxAttr);
        if (!ctx) return fail(err, "eglCreateContext (OpenGL 4.5 core) failed");
        if (!makeCurrent(dpy, nullptr, nullptr, ctx)) return fail(err, "eglMakeCurrent (surfaceless) failed");
        return true;
    }

    bool createGlx(std::string& err) {
        kind = "glx";
        x11 = dlopen("libX11.so.6", RTLD_NOW | RTLD_GLOBAL);
        glx = dlopen("libGLX.so.0", RTLD_NOW | RTLD_GLOBAL);
        if (!glx) glx = dlopen("libGL.so.1", RTLD_NOW | RTLD_GLOBAL);
        if (!x11 || !glx) return fail(err, "libX11.so.6 / libGLX.so.0 not found");
        auto openDisplay = reinterpret_cast<void* (*)(const char*)>(dlsym(x11, "XOpenDisplay"));
        auto defaultScreen = reinterpret_cast<int (*)(void*)>(dlsym(x11, "XDefaultScreen"));
        auto chooseFb = reinterpret_cast<void** (*)(void*, int, const int*, int*)>(dlsym(glx, "glXChooseFBConfig"));
        auto getProc = reinterpret_cast<void* (*)(const unsigned char*)>(dlsym(glx, "glXGetProcAddressARB"));
        auto createPbuffer = reinterpret_cast<unsigned long (*)(void*, void*, const int*)>(dlsym(glx, "glXCreatePbuffer"));
        auto makeCurrent = reinterpret_cast<int (*)(void*, unsigned long, unsigned long, void*)>(dlsym(glx, "glXMakeContextCurrent"));
        if (!openDisplay || !defaultScreen || !chooseFb || !getProc || !createPbuffer || !makeCurrent)
            return fail(err, "libX11 / libGLX lack entry points");
        xdpy = openDisplay(nullptr);
        if (!xdpy) return fail(err, "XOpenDisplay failed (no DISPLAY)");
        const int fbAttr[] = {0x8011 /*GLX_DRAWABLE_TYPE*/, 0x4 /*GLX_PBUFFER_BIT*/, 0x8014 /*GLX_RENDER_TYPE*/, 0x1, 0};
        int n = 0;
        void** cfgs = chooseFb(xdpy, defaultScreen(xdpy), fbAttr, &n);
        if (!cfgs || n < 1) return fail(err, "no GLX pbuffer config");
        using CreateCtx = void* (*)(void*, void*, void*, int, const int*);
        auto createCtx = reinterpret_cast<CreateCtx>(getProc(reinterpret_cast<const unsigned char*>("glXCreateContextAttribsARB")));
        const int ctxAttr[] = {0x2091 /*MAJOR*/, 4, 0x2092 /*MINOR*/, 5, 0x9126 /*PROFILE_MASK*/, 1 /*CORE*/, 0};
        ctx = createCtx ? createCtx(xdpy, cfgs[0], nullptr, 1, ctxAttr) : nullptr;
        if (!ctx) return fail(err, "glXCreateContextAttribsARB (OpenGL 4.5 core) failed");
        const int pbAttr[] = {0x8041 /*GLX_PBUFFER_WIDTH*/, 16, 0x8040 /*GLX_PBUFFER_HEIGHT*/, 16, 0};
        pbuf = createPbuffer(xdpy, cfgs[0], pbAttr);
        if (!makeCurrent(xdpy, pbuf, pbuf, ctx)) return fail(err, "glXMakeContextCurrent failed");
        return true;
    }

    void destroy() {
        if (kind[0] == 'e' && egl && dpy) {
            auto makeCurrent = reinterpret_cast<unsigned (*)(void*, void*, void*, void*)>(dlsym(egl, "eglMakeCurrent"));
            auto destroyContext = reinterpret_cast<unsigned (*)(void*, void*)>(dlsym(egl, "eglDestroyContext"));
            makeCurrent(dpy, nullptr, nullptr, nullptr);
            if (ctx) destroyContext(dpy, ctx);
        } else if (glx && xdpy) {
            auto makeCurrent = reinterpret_cast<int (*)(void*, unsigned long, unsigned long, void*)>(dlsym(glx, "glXMakeContextCurrent"));
            auto destroyContext = reinterpret_cast<void (*)(void*, void*)>(dlsym(glx, "glXDestroyContext"));
            makeCurrent(xdpy, 0, 0, nullptr);
            if (ctx) destroyContext(xdpy, ctx);
        }
        ctx = nullptr;
    }

    static bool fail(std::string& err, const char* m) {
        err = m;
        return false;
    }
};

#endif

}  // namespace aether_test
