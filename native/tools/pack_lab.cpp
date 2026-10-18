// pack_lab: the Skycraft Aether shader pack's screen passes (deferred, composite, composite1, final) run offline on a
// scene aether renders, so lighting, water, fog, sky, shafts and bloom can be judged without Minecraft.
//
// aether provides the G-buffer within a near radius (albedo, normal, depth, the bed under water), its own lit far
// field beyond it (with clouds), and the real sun map; a lab-only pass mirrors gbuffers_water for water surfaces.
// The pack's programs are compiled as Iris would (includes resolved, RENDERTARGETS honoured, ping-pong when a pass
// reads and writes the same buffer) in a GL compatibility context, with Iris-style uniforms.
//
// usage: pack_lab out.png [x=0 z=0 above=40 yaw=105 pitch=-4 time=0.25 rain=0 water=0 near=256 w=1280 h=720
//                          clouds=1 temp=0.6 cold=0 aether=1]
//   aether=0: aether's frame absent (1x1 blanks): the pack's fallback cloud deck and plain fog
//   time: 0 sunrise, 0.25 noon, 0.5 sunset, 0.75 midnight; water=1 puts the eye under water
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif
#include <windows.h>
#include <GL/gl.h>

#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "png.hpp"

namespace {

// ---------------------------------------------------------------- GL (the few modern entry points the lab needs)
typedef char GLchar;
#define GLF(ret, name, args) typedef ret(APIENTRY* PFN_##name) args; PFN_##name p##name = nullptr;
GLF(GLuint, glCreateShader, (GLenum))
GLF(void, glShaderSource, (GLuint, GLsizei, const GLchar* const*, const GLint*))
GLF(void, glCompileShader, (GLuint))
GLF(void, glGetShaderiv, (GLuint, GLenum, GLint*))
GLF(void, glGetShaderInfoLog, (GLuint, GLsizei, GLsizei*, GLchar*))
GLF(GLuint, glCreateProgram, ())
GLF(void, glAttachShader, (GLuint, GLuint))
GLF(void, glLinkProgram, (GLuint))
GLF(void, glGetProgramiv, (GLuint, GLenum, GLint*))
GLF(void, glGetProgramInfoLog, (GLuint, GLsizei, GLsizei*, GLchar*))
GLF(void, glUseProgram, (GLuint))
GLF(GLint, glGetUniformLocation, (GLuint, const GLchar*))
GLF(void, glUniform1i, (GLint, GLint))
GLF(void, glUniform1f, (GLint, GLfloat))
GLF(void, glUniform2i, (GLint, GLint, GLint))
GLF(void, glUniform3f, (GLint, GLfloat, GLfloat, GLfloat))
GLF(void, glUniformMatrix4fv, (GLint, GLsizei, GLboolean, const GLfloat*))
GLF(void, glGenFramebuffers, (GLsizei, GLuint*))
GLF(void, glBindFramebuffer, (GLenum, GLuint))
GLF(void, glFramebufferTexture2D, (GLenum, GLenum, GLenum, GLuint, GLint))
GLF(GLenum, glCheckFramebufferStatus, (GLenum))
GLF(void, glDrawBuffers, (GLsizei, const GLenum*))
GLF(void, glActiveTexture, (GLenum))
GLF(void, glGenerateMipmap, (GLenum))
GLF(void, glDeleteFramebuffers, (GLsizei, const GLuint*))

bool loadGl() {
#define LOAD(n) if (!(p##n = reinterpret_cast<PFN_##n>(wglGetProcAddress(#n)))) { std::printf("missing %s\n", #n); return false; }
    LOAD(glCreateShader) LOAD(glShaderSource) LOAD(glCompileShader) LOAD(glGetShaderiv) LOAD(glGetShaderInfoLog)
    LOAD(glCreateProgram) LOAD(glAttachShader) LOAD(glLinkProgram) LOAD(glGetProgramiv) LOAD(glGetProgramInfoLog)
    LOAD(glUseProgram) LOAD(glGetUniformLocation) LOAD(glUniform1i) LOAD(glUniform1f) LOAD(glUniform2i) LOAD(glUniform3f)
    LOAD(glUniformMatrix4fv) LOAD(glGenFramebuffers) LOAD(glBindFramebuffer) LOAD(glFramebufferTexture2D)
    LOAD(glCheckFramebufferStatus) LOAD(glDrawBuffers) LOAD(glActiveTexture) LOAD(glGenerateMipmap) LOAD(glDeleteFramebuffers)
    return true;
}

constexpr GLenum GL_FRAGMENT_SHADER_ = 0x8B30, GL_VERTEX_SHADER_ = 0x8B31, GL_COMPILE_STATUS_ = 0x8B81,
                 GL_LINK_STATUS_ = 0x8B82, GL_FRAMEBUFFER_ = 0x8D40, GL_COLOR_ATTACHMENT0_ = 0x8CE0,
                 GL_TEXTURE0_ = 0x84C0, GL_RGBA16F_ = 0x881A, GL_RGBA16_ = 0x805B, GL_RG32F_ = 0x8230, GL_RG_ = 0x8227,
                 GL_R32F_ = 0x822E, GL_DEPTH_COMPONENT32F_ = 0x8CAC, GL_TEXTURE_COMPARE_MODE_ = 0x884C,
                 GL_COMPARE_REF_TO_TEXTURE_ = 0x884E, GL_CLAMP_TO_EDGE_ = 0x812F, GL_LINEAR_MIPMAP_LINEAR_ = 0x2703,
                 GL_TEXTURE_MAX_LEVEL_ = 0x813D;

HGLRC makeContext() {
    WNDCLASSA wc{};
    wc.lpfnWndProc = DefWindowProcA;
    wc.hInstance = GetModuleHandleA(nullptr);
    wc.lpszClassName = "pack_lab";
    RegisterClassA(&wc);
    HWND wnd = CreateWindowA("pack_lab", "pack_lab", WS_OVERLAPPEDWINDOW, 0, 0, 64, 64, nullptr, nullptr, wc.hInstance, nullptr);
    HDC dc = GetDC(wnd);
    PIXELFORMATDESCRIPTOR pfd{sizeof(pfd), 1, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER, PFD_TYPE_RGBA, 32};
    SetPixelFormat(dc, ChoosePixelFormat(dc, &pfd), &pfd);
    HGLRC rc = wglCreateContext(dc);  // legacy creation: a compatibility profile, as Iris packs expect
    wglMakeCurrent(dc, rc);
    return rc;
}

// ---------------------------------------------------------------- the pack's programs
std::string PACK;  // .../shaderpack/SkycraftAether/shaders

std::string readFile(const std::string& p) {
    std::ifstream f(p, std::ios::binary);
    std::stringstream s;
    s << f.rdbuf();
    return s.str();
}

std::string resolve(const std::string& path, int depth = 0) {
    std::string src = readFile(path), out;
    if (src.empty()) std::printf("pack_lab: cannot read %s\n", path.c_str());
    std::istringstream in(src);
    std::string line;
    std::regex inc(R"(^\s*#include\s+"([^"]+)\"\s*$)");
    std::smatch m;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (depth < 16 && std::regex_match(line, m, inc)) {
            std::string p = m[1].str();
            std::string target = p[0] == '/' ? PACK + p : path.substr(0, path.find_last_of("/\\") + 1) + p;
            out += resolve(target, depth + 1) + "\n";
        } else {
            out += line + "\n";
        }
    }
    return out;
}

struct Program {
    GLuint id = 0;
    std::vector<int> targets;  // RENDERTARGETS
};

GLuint compile(GLenum type, const std::string& src, const std::string& name) {
    GLuint s = pglCreateShader(type);
    const char* p = src.c_str();
    pglShaderSource(s, 1, &p, nullptr);
    pglCompileShader(s);
    GLint ok = 0;
    pglGetShaderiv(s, GL_COMPILE_STATUS_, &ok);
    if (!ok) {
        char log[4096];
        pglGetShaderInfoLog(s, sizeof(log), nullptr, log);
        std::printf("pack_lab: %s does not compile:\n%s\n", name.c_str(), log);
        return 0;
    }
    return s;
}

Program program(const std::string& vsh, const std::string& fsh) {
    Program pr;
    std::string vs = resolve(vsh), fs = resolve(fsh);
    std::smatch m;
    if (std::regex_search(fs, m, std::regex(R"(RENDERTARGETS:\s*([0-9,]+))"))) {
        std::string t = m[1].str();
        std::stringstream ss(t);
        std::string tok;
        while (std::getline(ss, tok, ',')) pr.targets.push_back(std::stoi(tok));
    } else {
        pr.targets = {0};
    }
    GLuint v = compile(GL_VERTEX_SHADER_, vs, vsh), f = compile(GL_FRAGMENT_SHADER_, fs, fsh);
    if (!v || !f) return pr;
    pr.id = pglCreateProgram();
    pglAttachShader(pr.id, v);
    pglAttachShader(pr.id, f);
    pglLinkProgram(pr.id);
    GLint ok = 0;
    pglGetProgramiv(pr.id, GL_LINK_STATUS_, &ok);
    if (!ok) {
        char log[4096];
        pglGetProgramInfoLog(pr.id, sizeof(log), nullptr, log);
        std::printf("pack_lab: %s does not link:\n%s\n", fsh.c_str(), log);
        pr.id = 0;
    }
    return pr;
}

GLuint texture2d(GLint internal, int w, int h, GLenum fmt, GLenum type, const void* data, bool linear = false) {
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, fmt, type, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE_);
    return t;
}

double arg(int argc, char** argv, const char* k, double d) {
    size_t n = std::strlen(k);
    for (int i = 2; i < argc; i++)
        if (std::strncmp(argv[i], k, n) == 0 && argv[i][n] == '=') return std::atof(argv[i] + n + 1);
    return d;
}

// GPU utilisation over ~0.3 s from NVIDIA's NVML (shipped with the driver); 0 when there is no NVML (other vendors:
// assume free)
int gpuBusyPercent() {
    HMODULE nv = LoadLibraryA("nvml.dll");
    if (!nv) return 0;
    struct Util { unsigned gpu, memory; };
    typedef int (*Init)();
    typedef int (*ByIndex)(unsigned, void**);
    typedef int (*Rates)(void*, Util*);
    auto init = reinterpret_cast<Init>(GetProcAddress(nv, "nvmlInit_v2"));
    auto byIndex = reinterpret_cast<ByIndex>(GetProcAddress(nv, "nvmlDeviceGetHandleByIndex_v2"));
    auto rates = reinterpret_cast<Rates>(GetProcAddress(nv, "nvmlDeviceGetUtilizationRates"));
    auto shut = reinterpret_cast<Init>(GetProcAddress(nv, "nvmlShutdown"));
    int busy = 0;
    void* dev = nullptr;
    if (init && byIndex && rates && init() == 0) {
        if (byIndex(0, &dev) == 0)
            for (int i = 0; i < 3; i++) {
                Util u{};
                if (rates(dev, &u) == 0) busy = std::max(busy, int(u.gpu));
                Sleep(100);
            }
        if (shut) shut();
    }
    FreeLibrary(nv);
    return busy;
}

// column-major 4x4
struct M4 {
    double m[16] = {};
};

M4 mul(const M4& a, const M4& b) {
    M4 r;
    for (int c = 0; c < 4; c++)
        for (int rr = 0; rr < 4; rr++) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k];
            r.m[c * 4 + rr] = s;
        }
    return r;
}

bool invert(const M4& a, M4& out) {
    const double* m = a.m;
    double inv[16];
    inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
    inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
    inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
    double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
    if (std::abs(det) < 1e-30) return false;
    for (int i = 0; i < 16; i++) out.m[i] = inv[i] / det;
    return true;
}

void toFloat(const M4& a, float* out) {
    for (int i = 0; i < 16; i++) out[i] = float(a.m[i]);
}

// octahedral: aether's packNormal (normal buffer rg) and the pack's octEncode are the same mapping
void octDecode(float ex, float ez, float n[3]) {
    ex = ex * 2 - 1;
    ez = ez * 2 - 1;
    float y = 1 - std::abs(ex) - std::abs(ez), x = ex, z = ez;
    if (y < 0) {
        x = (1 - std::abs(ez)) * (ex >= 0 ? 1.0f : -1.0f);
        z = (1 - std::abs(ex)) * (ez >= 0 ? 1.0f : -1.0f);
    }
    float l = std::sqrt(x * x + y * y + z * z);
    n[0] = x / l;
    n[1] = y / l;
    n[2] = z / l;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("usage: pack_lab out.png [x= z= above= yaw= pitch= time= rain= water= near= w= h=]\n");
        return 2;
    }
    const char* outPng = argv[1];
    PACK = "../shaderpack/SkycraftAether/shaders";  // run from native/
    const uint32_t W = uint32_t(arg(argc, argv, "w", 1280)), H = uint32_t(arg(argc, argv, "h", 720));
    double x = arg(argc, argv, "x", 0), z = arg(argc, argv, "z", 0), yaw = arg(argc, argv, "yaw", 105),
           pitch = arg(argc, argv, "pitch", -4), timeOfDay = arg(argc, argv, "time", 0.25), rain = arg(argc, argv, "rain", 0),
           nearR = arg(argc, argv, "near", 256), temp = arg(argc, argv, "temp", 0.6);
    int eyeInWater = int(arg(argc, argv, "water", 0));
    bool clouds = arg(argc, argv, "clouds", 1) != 0;

    // ---- aether: the scene
    AetherContext* ctx = nullptr;
    // a good neighbour on a shared machine: below-normal priority, and aether on its CPU reference backend (bit-exact
    // with Vulkan, tested) unless gpu=1, so only the pack's few full-screen GL passes touch the GPU
    SetPriorityClass(GetCurrentProcess(), BELOW_NORMAL_PRIORITY_CLASS);
    // gpu=1 Vulkan, gpu=0 CPU, gpu=auto (-1, default): Vulkan only while the GPU is idle (someone else's game or test
    // keeps it), else CPU
    int gpuArg = int(arg(argc, argv, "gpu", -1));
    int busy = gpuArg < 0 ? gpuBusyPercent() : 0;
    bool gpu = gpuArg > 0 || (gpuArg < 0 && busy < 40);
    if (gpuArg < 0) std::printf("pack_lab: GPU %d%% busy -> %s\n", busy, gpu ? "Vulkan" : "CPU");
    if (gpu && aether_create(AETHER_BACKEND_VULKAN, &ctx) != AETHER_OK) gpu = false;
    if (!gpu && !ctx && aether_create(AETHER_BACKEND_CPU, &ctx) != AETHER_OK) return 1;
    auto clock0 = std::chrono::steady_clock::now();
    auto lap = [&](const char* what) {
        auto now = std::chrono::steady_clock::now();
        std::printf("pack_lab: %-10s %7.1f ms\n", what, std::chrono::duration<double, std::milli>(now - clock0).count());
        clock0 = now;
    };
    if (aether_preview_load_raster(ctx, "data/tamriel_raster.bin") != AETHER_OK) {
        std::printf("pack_lab: %s\n", aether_last_error(ctx));
        return 1;
    }
    if (arg(argc, argv, "scan", 0) != 0) {  // shore finder: points within 6 km whose ground is below sea level
        for (int dz = -6000; dz <= 6000; dz += 500)
            for (int dx = -6000; dx <= 6000; dx += 500) {
                float h = aether_preview_height(ctx, x + dx, z + dz);
                if (h < 58) std::printf("water at %d %d (ground %.0f)\n", int(x + dx), int(z + dz), h);
            }
        return 0;
    }
    lap("raster");
    double ground = aether_preview_height(ctx, x, z);
    double y = std::max(ground, 63.0) + arg(argc, argv, "above", 40);
    if (eyeInWater) y = std::max(ground + 2.0, 60.0);
    std::printf("pack_lab: camera %.0f %.0f %.0f (ground %.0f)\n", x, y, z, ground);
    for (int lod = 0; lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        aether_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    double centre[3] = {x, y, z};
    lap("generate");
    aether_update(ctx, centre, 0);
    lap("svdag");
    std::vector<uint32_t> noise(64 * 64 * 64);
    bool haveNoise = false;
    if (FILE* nf = std::fopen("data/cloud_noise.bin", "rb")) {
        haveNoise = std::fread(noise.data(), 4, noise.size(), nf) == noise.size();
        std::fclose(nf);
    }
    if (haveNoise) aether_set_cloud_noise(ctx, noise.data());
    std::vector<uint32_t> fld(128 * 128), dyn(128 * 128);
    double groundFog = arg(argc, argv, "fog", 0);  // the mod's ground-fog drive (weather_dynamics a)
    int band = int(arg(argc, argv, "band", 0));     // aerosol band: 0 rain, 1 snow, 2 dust, 3 ash
    for (int i = 0; i < 128 * 128; i++) {
        // smooth value noise over the cells (two octaves): patchy cover rather than a regular pattern
        auto hash = [](int x, int y) { uint32_t h = uint32_t(x) * 374761393u + uint32_t(y) * 668265263u; h = (h ^ (h >> 13)) * 1274126177u; return (h & 0xFFFFFF) / 16777215.0; };
        auto vnoise = [&](double fx, double fy) {
            int ix = int(std::floor(fx)), iy = int(std::floor(fy));
            double tx = fx - ix, ty = fy - iy;
            tx = tx * tx * (3 - 2 * tx);
            ty = ty * ty * (3 - 2 * ty);
            double a = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * tx;
            double b = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * tx;
            return a + (b - a) * ty;
        };
        double cx = i % 128, cy = i / 128;
        double c = 0.2 + 0.25 * rain + 0.35 * vnoise(cx / 9, cy / 9) + 0.2 * vnoise(cx / 3.5 + 11, cy / 3.5 + 5);
        fld[i] = uint32_t(std::min(c, 1.0) * 255) | uint32_t(rain * 255) << 8 | uint32_t(rain * 0.6 * 255) << 16
               | uint32_t(std::lround((band * 0.25 + 0.02) * 255)) << 24;
        dyn[i] = uint32_t(240.0 / 1024 * 255) | uint32_t(120.0 / 1024 * 255) << 8 | uint32_t(0.1024 * 255) << 16
               | uint32_t(std::lround(groundFog * 255)) << 24;
    }
    AetherWeather wth{fld.data(), dyn.data(), fld.data(), dyn.data(), float(rain)};
    aether_set_weather(ctx, &wth);
    aether_set_sun_map(ctx, 1024, 2048.0f);

    // camera: the GL view / projection the pack sees, and aether's inverse from the same matrices
    const double PI = 3.14159265358979323846;
    double yr = yaw * PI / 180, pr = pitch * PI / 180;
    double f[3] = {std::sin(yr) * std::cos(pr), std::sin(pr), -std::cos(yr) * std::cos(pr)};
    double r[3] = {std::cos(yr), 0, std::sin(yr)};
    double u[3] = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
    M4 V;
    for (int i = 0; i < 3; i++) {
        V.m[i * 4 + 0] = r[i];
        V.m[i * 4 + 1] = u[i];
        V.m[i * 4 + 2] = -f[i];
    }
    V.m[15] = 1;
    double fov = 70 * PI / 180, nearP = 0.05, farP = 4096.0, asp = double(W) / H, ft = 1.0 / std::tan(fov / 2);
    M4 P;
    P.m[0] = ft / asp;
    P.m[5] = ft;
    P.m[10] = (farP + nearP) / (nearP - farP);
    P.m[11] = -1;
    P.m[14] = 2 * farP * nearP / (nearP - farP);
    M4 VP = mul(P, V), iVP, iP, iV;
    invert(VP, iVP);
    invert(P, iP);
    invert(V, iV);
    // the sun's path: time 0 sunrise (east, +x), 0.25 noon, tilted like sunPathRotation
    double ang = timeOfDay * 2 * PI, tilt = -25.0 * PI / 180;
    double sun[3] = {std::cos(ang), std::sin(ang) * std::cos(tilt), std::sin(ang) * std::sin(tilt)};

    AetherFrame fr{};
    fr.cameraPos[0] = x;
    fr.cameraPos[1] = y;
    fr.cameraPos[2] = z;
    toFloat(iVP, fr.invViewProj);
    fr.width = W;
    fr.height = H;
    fr.maxDistance = 40000;
    fr.planetRadius = 6371000.0f;
    fr.lodBias = 1;
    for (int i = 0; i < 3; i++) fr.sunDir[i] = float(sun[i]);
    fr.timeSec = float(arg(argc, argv, "t", 120.0));
    size_t N = size_t(W) * H;
    std::vector<float> farLin(N * 4), farDepth(N), bedLin(N * 4), bedDepth(N), surfLin(N * 4), surfDepth(N);
    std::vector<uint32_t> bedNormal(N), surfNormal(N);
    // the far field: aether's own lit render (with clouds), as the game composites it
    fr.flags = AETHER_FRAME_ATMOS | AETHER_FRAME_SHADOWS | (clouds && haveNoise ? AETHER_FRAME_CLOUDS : 0);
    AetherFrameOutput o1{nullptr, nullptr, farDepth.data(), farLin.data()};
    for (int i = 0; i < 3; i++) aether_render(ctx, &fr, &o1);
    // the near field's G-buffer: albedo, normals and depth, through water (the bed) and to the water's surface
    fr.flags = AETHER_FRAME_DEBUG_ALBEDO | AETHER_FRAME_SEE_THROUGH_WATER | AETHER_FRAME_SHADOWS;
    AetherFrameOutput o2{nullptr, bedNormal.data(), bedDepth.data(), bedLin.data()};
    aether_render(ctx, &fr, &o2);
    fr.flags = AETHER_FRAME_DEBUG_ALBEDO | AETHER_FRAME_SHADOWS;
    AetherFrameOutput o3{nullptr, surfNormal.data(), surfDepth.data(), surfLin.data()};
    aether_render(ctx, &fr, &o3);
    std::vector<float> sunMap(size_t(1024) * 1025 * 2);
    bool haveSunMap = arg(argc, argv, "sunmap", 1) != 0 && aether_read_sun_map(ctx, sunMap.data(), uint32_t(sunMap.size())) == AETHER_OK;
    lap("render");

    // ---- GL
    if (!makeContext() || !loadGl()) return 1;
    std::printf("pack_lab: %s\n", reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
    // G-buffer textures (GL rows bottom-up: flip)
    auto row = [&](size_t i) { size_t py = i / W, px = i % W; return (H - 1 - py) * W + px; };
    std::vector<uint8_t> albedo(N * 4), material(N * 4), specularBuf(N * 4), waterMask(N * 4);
    std::vector<uint16_t> normalLm(N * 4);
    std::vector<float> depth0(N), depth1(N), aColor(N * 4), aDepth(N);
    auto ndcDepth = [&](size_t i, float dist) {
        // the depth-buffer value of the point `dist` along pixel i's ray
        size_t py = i / W, px = i % W;
        double nx = (px + 0.5) / W * 2 - 1, ny = 1 - (py + 0.5) / H * 2;
        double p[4];
        for (int k = 0; k < 4; k++) p[k] = iVP.m[k] * nx + iVP.m[4 + k] * ny + iVP.m[8 + k] * 0.5 + iVP.m[12 + k];
        double d[3] = {p[0] / p[3], p[1] / p[3], p[2] / p[3]}, l = std::sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double wp[3] = {d[0] / l * dist, d[1] / l * dist, d[2] / l * dist};
        double cz = V.m[2] * wp[0] + V.m[6] * wp[1] + V.m[10] * wp[2];
        double ndc = (P.m[10] * cz + P.m[14]) / -cz;
        return float(std::min(1.0, ndc * 0.5 + 0.5));
    };
    // ---- the mod's data textures, synthetic (see lib/modtex.glsl for the packing): lamps (fog_radiance block-light
    // cells + the G-buffer's block light), a dungeon room, a point light, the volcano / a fire plume, a discharge,
    // the sun horizon. lamps=1 room=1 light=1 volcano=1 fire=1 strike=1 horizon=0.3 fog=0..1 (ground-fog drive)
    struct Lamp { double p[3]; double c[3]; };
    std::vector<Lamp> lamps;
    double fwd[3] = {std::sin(yaw * PI / 180), 0, -std::cos(yaw * PI / 180)}, side[3] = {-fwd[2], 0, fwd[0]};
    auto ahead = [&](double along, double across, double up, double out[3]) {
        for (int k = 0; k < 3; k++) out[k] = (k == 0 ? x : k == 1 ? y : z) + fwd[k] * along + side[k] * across;
        out[1] = y + up;
        double g = aether_preview_height(ctx, out[0], out[2]);
        if (up < 0) out[1] = std::max(g, 63.0) + 1.5;  // on the ground
    };
    if (arg(argc, argv, "lamps", 0) != 0) {
        Lamp a, b, c;
        ahead(10, -3, -1, a.p); a.c[0] = 1.0; a.c[1] = 0.69; a.c[2] = 0.44;   // a torch (0xFFB070)
        ahead(16, 5, -1, b.p); b.c[0] = 0.44; b.c[1] = 0.86; b.c[2] = 1.0;    // a soul lantern (0x71DBFF)
        ahead(22, -8, -1, c.p); c.c[0] = 1.0; c.c[1] = 0.44; c.c[2] = 0.16;   // lava light (0xFF7028)
        lamps = {a, b, c};
    }
    auto lampLight = [&](const double w[3], double rgb[3]) {  // Minecraft-like: falls 1/15 per block
        double best = 0;
        rgb[0] = rgb[1] = rgb[2] = 0;
        for (auto& l : lamps) {
            double d = std::abs(w[0] - l.p[0]) + std::abs(w[1] - l.p[1]) + std::abs(w[2] - l.p[2]);
            double v = std::max(0.0, 1.0 - d / 15.0);
            if (v > best) { best = v; for (int k = 0; k < 3; k++) rgb[k] = l.c[k]; }
        }
        return best;
    };
    auto rayOf = [&](size_t i, double d[3]) {
        size_t py = i / W, px = i % W;
        double nx = (px + 0.5) / W * 2 - 1, ny = 1 - (py + 0.5) / H * 2, p[4];
        for (int k = 0; k < 4; k++) p[k] = iVP.m[k] * nx + iVP.m[4 + k] * ny + iVP.m[8 + k] * 0.5 + iVP.m[12 + k];
        double l = 0;
        for (int k = 0; k < 3; k++) { d[k] = p[k] / p[3]; l += d[k] * d[k]; }
        l = std::sqrt(l);
        for (int k = 0; k < 3; k++) d[k] /= l;
    };
    size_t nearPx = 0, waterPx = 0;
    for (size_t i = 0; i < N; i++) {
        size_t g = row(i);
        float bd = bedDepth[i], sd = surfDepth[i];
        bool nearBed = bd < nearR, nearSurf = sd < nearR;
        uint32_t nb = bedNormal[i];
        float n[3];
        octDecode((nb & 255) / 255.0f, (nb >> 8 & 255) / 255.0f, n);
        uint32_t cls = nb >> 16 & 255;
        auto srgb = [](float c) { return uint8_t(std::lround(std::pow(std::min(std::max(c, 0.0f), 1.0f), 1 / 2.2) * 255)); };
        for (int k = 0; k < 3; k++) albedo[g * 4 + k] = srgb(bedLin[i * 4 + k]);
        albedo[g * 4 + 3] = 255;
        // the pack's octahedral encoding of the same normal
        float s = std::abs(n[0]) + std::abs(n[1]) + std::abs(n[2]);
        float ex = n[0] / s, ez = n[2] / s;
        if (n[1] < 0) {
            float ox = (1 - std::abs(ez)) * (ex >= 0 ? 1.0f : -1.0f), oz = (1 - std::abs(ex)) * (ez >= 0 ? 1.0f : -1.0f);
            ex = ox;
            ez = oz;
        }
        normalLm[g * 4 + 0] = uint16_t(std::lround((ex * 0.5 + 0.5) * 65535));
        normalLm[g * 4 + 1] = uint16_t(std::lround((ez * 0.5 + 0.5) * 65535));
        normalLm[g * 4 + 2] = 0;      // block light
        if (!lamps.empty() && nearBed) {
            double d[3], w[3], rgb[3];
            rayOf(i, d);
            for (int k = 0; k < 3; k++) w[k] = (k == 0 ? x : k == 1 ? y : z) + d[k] * bd;
            normalLm[g * 4 + 2] = uint16_t(std::lround(lampLight(w, rgb) * 65535));
        }
        normalLm[g * 4 + 3] = 65535;  // sky light: outdoors
        bool foliage = cls == 1;      // AETHER_CLASS_FOLIAGE
        specularBuf[g * 4 + 0] = uint8_t(foliage ? 0.35 * 255 : 0.25 * 255);
        specularBuf[g * 4 + 1] = uint8_t(0.03 * 255);
        specularBuf[g * 4 + 2] = uint8_t(foliage ? 0.6 * 255 : 0);
        specularBuf[g * 4 + 3] = cls == 3 ? 255 : 0;  // emissive class
        material[g * 4 + 0] = uint8_t(foliage ? 1 : 0);  // MAT_LEAVES
        material[g * 4 + 1] = 255;  // AO
        depth1[g] = nearBed ? ndcDepth(i, bd) : 1.0f;
        bool water = nearSurf && (surfNormal[i] >> 16 & 255) == 2 && sd < bd - 0.01f;
        depth0[g] = water ? ndcDepth(i, sd) : depth1[g];
        if (water) {
            for (int k = 0; k < 3; k++) waterMask[g * 4 + k] = srgb(surfLin[i * 4 + k]);
            waterMask[g * 4 + 3] = 255;
            waterPx++;
        }
        nearPx += nearBed;
        for (int k = 0; k < 4; k++) aColor[g * 4 + k] = farLin[i * 4 + k];
        aDepth[g] = farDepth[i];
    }
    std::printf("pack_lab: %zu near px (%zu water), sun map %s\n", nearPx, waterPx, haveSunMap ? "on" : "off");
    if (arg(argc, argv, "dump", 0) != 0) {  // the G-buffer the pack receives: albedo and normals
        std::vector<uint32_t> a(N), nn(N);
        for (size_t i = 0; i < N; i++) {
            size_t g = row(i);
            a[i] = albedo[g * 4] | albedo[g * 4 + 1] << 8 | albedo[g * 4 + 2] << 16 | 0xFF000000u;
            nn[i] = (normalLm[g * 4] >> 8) | (normalLm[g * 4 + 1] >> 8) << 8 | (bedNormal[i] >> 16 & 255) * 60 << 16 | 0xFF000000u;
        }
        std::string base = std::string(outPng).substr(0, std::strlen(outPng) - 4);
        aether::writePng(base + "_albedo.png", a.data(), W, H);
        aether::writePng(base + "_normal.png", nn.data(), W, H);
    }

    auto rgba8 = [](double r, double g, double b, double a) {
        auto c = [](double v) { return uint32_t(std::lround(std::min(std::max(v, 0.0), 1.0) * 255)); };
        return c(r) | c(g) << 8 | c(b) << 16 | c(a) << 24;
    };
    auto pack16 = [](int lo, int hi) { return uint32_t(lo & 0xFFFF) | uint32_t(hi & 0xFFFF) << 16; };
    std::vector<uint32_t> fogRad(128 * 128, 0u), smoke(32 * 4, 0u), bolt(32 * 4, 0u);
    {
        int cx = int(std::floor(x / 4)), cy = int(std::floor(y / 4)), cz = int(std::floor(z / 4));
        auto nearest = [](int centre, int texel) { int start = centre - 8; return start + (((texel - start) % 16) + 16) % 16; };
        for (int ly = 0; ly < 16; ly++)
            for (int tz = 0; tz < 16; tz++)
                for (int tx = 0; tx < 16; tx++) {
                    double w[3] = {nearest(cx, tx) * 4 + 2.0, nearest(cy, ly) * 4 + 2.0, nearest(cz, tz) * 4 + 2.0}, rgb[3];
                    double light = lampLight(w, rgb);
                    int px = (ly & 3) * 16 + tx, pz = (ly >> 2) * 16 + tz;
                    fogRad[pz * 128 + px] = uint32_t(std::lround(light * 255));  // roof unknown (a = 0)
                    fogRad[pz * 128 + px + 64] = rgba8(rgb[0] * light, rgb[1] * light, rgb[2] * light, 1);
                }
        if (arg(argc, argv, "roof", 0) != 0) {  // a roof 3 blocks over the eye across the whole 128-block cache
            uint32_t roofY = uint32_t(int(std::floor(y)) + 3 + 64);
            for (int i = 0; i < 64 * 64; i++) {
                int px = i % 64, pz = i / 64;
                fogRad[pz * 128 + px] = (fogRad[pz * 128 + px] & 255u) | (roofY & 255u) << 8 | (roofY >> 8 & 255u) << 16 | 255u << 24;
            }
        }
        double ox = std::floor(x / 256) * 256, oy = std::floor(y / 256) * 256, oz = std::floor(z / 256) * 256;
        auto q = [](double v) { return int(std::lround(v * 4)) + 32768; };
        auto point = [&](int col, int row, double px, double py, double pz) {
            fogRad[row * 128 + col] = pack16(q(px - ox), q(py - oy));
            fogRad[row * 128 + col + 1] = uint32_t(q(pz - oz));
        };
        fogRad[74 * 128 + 60] = uint32_t(int32_t(ox));
        fogRad[74 * 128 + 61] = uint32_t(int32_t(oy));
        fogRad[74 * 128 + 62] = uint32_t(int32_t(oz));
        fogRad[75 * 128 + 0] = 2400;  // the game clock (ticks)
        fogRad[75 * 128 + 2] = 1;     // local media allowed (the Overworld)
        if (arg(argc, argv, "room", 0) != 0) {  // a dungeon hall ahead, Falmer-style air (style 3)
            double lo[3], hi[3];
            ahead(6, -14, -1, lo); ahead(40, 14, -1, hi);
            double fl = std::min(lo[1], hi[1]) - 1.5;
            point(0, 64, std::min(lo[0], hi[0]), fl, std::min(lo[2], hi[2]));
            point(0, 65, std::max(lo[0], hi[0]), fl + 14, std::max(lo[2], hi[2]));
            fogRad[66 * 128 + 0] = uint32_t(q(fl + 1 - oy)) | 3u << 16 | 255u << 24;
            fogRad[66 * 128 + 63] = 1;
        }
        if (arg(argc, argv, "light", 0) != 0) {  // a frost atronach's glow (0x83D8FF, energy 1, radius 10)
            double p[3];
            ahead(14, 2, 2, p);
            point(0, 68, p[0], p[1], p[2]);
            fogRad[69 * 128 + 0] = rgba8(0x83 / 255.0 / 4, 0xD8 / 255.0 / 4, 1.0 / 4, 10.0 / 32);
            fogRad[69 * 128 + 63] = 1;
        }
        // smoke sources: 0 the volcano (height 1000, radius 190, heavy), 1 a fire
        auto source = [&](int index, double sx, double sy, double sz, double intensity, double height, double radius, int heavy) {
            int ix = int(std::lround(sx)) + 32768, iz = int(std::lround(sz)) + 32768, iy = int(std::lround(sy)) + 64;
            int row = index < 8 ? 0 : 2, slot = index < 8 ? index : index - 8;
            smoke[row * 32 + slot * 2] = pack16(ix, iz);
            smoke[row * 32 + slot * 2 + 1] = uint32_t(iy & 0xFFFF) | uint32_t(std::lround(intensity * 100)) << 16 | uint32_t(heavy * 70) << 24;
            smoke[(row + 1) * 32 + slot * 2] = uint32_t(int(std::lround(height)) & 0xFFFF) | uint32_t(std::lround(radius)) << 16;
        };
        int sources = 0;
        if (arg(argc, argv, "volcano", 0) != 0) {
            double p[3];
            ahead(arg(argc, argv, "vdist", 2600), arg(argc, argv, "vside", 300), -1, p);
            source(0, p[0], p[1], p[2], 1.0, 1000, 190, 3);
            sources = 1;
        }
        if (arg(argc, argv, "fire", 0) != 0) {
            double p[3];
            ahead(70, 10, -1, p);
            source(1, p[0], p[1], p[2], 1.5, 14 + 18 * 3 + 8, 6, 3);
            sources = 2;
        }
        smoke[3 * 32 + 31] = uint32_t(sources);
        smoke[1 * 32 + 11] = rgba8(0, 1, 0.4, 0);  // partial tick, budget, wind
        // the mod's sky (moons=1): the clock (day= days; 0.75 puts Masser full and high at midnight), night-sky
        // state, options, and an aurora (aurora=1: one curtain in the north and its light on the ground)
        if (arg(argc, argv, "moons", 0) != 0) {
            smoke[1 * 32 + 1] = uint32_t(std::lround(arg(argc, argv, "day", 0.75) * 24000));
            smoke[1 * 32 + 13] = 2400;
            smoke[1 * 32 + 3] = rgba8(1, 0, 1, 1);
            smoke[1 * 32 + 7] = rgba8(1, 1, 1, arg(argc, argv, "aurora", 0) != 0 ? 1 : 0);
            if (arg(argc, argv, "aurora", 0) != 0) {
                smoke[1 * 32 + 15] = rgba8(0, 0, 0, 1);
                smoke[1 * 32 + 5] = rgba8(0.8, 1, 0.2, 1);
                int amp = 52000;
                for (int k = 0; k < 2; k++) {
                    smoke[1 * 32 + 16 + k * 2] = rgba8(0.5 + k * 0.06, 0.5 / 1.5, (0.22 + k * 0.08) / 1.5, 0.3);
                    smoke[1 * 32 + 17 + k * 2] = uint32_t(amp & 255) | uint32_t(amp >> 8) << 8 | uint32_t(70 + k * 60) << 16 | 128u << 24;
                }
                smoke[1 * 32 + 28] = rgba8(0.15, 0.6, 0.3, 1);
                for (int k = 29; k < 32; k++) smoke[1 * 32 + k] = rgba8(0.5, 0.5, 0.5, 1);
            }
        }
        if (arg(argc, argv, "strike", 0) != 0) {  // a cloud-to-ground discharge ahead, at full strobe
            double g[3];
            ahead(arg(argc, argv, "sdist", 260), arg(argc, argv, "sside", 60), -1, g);
            bolt[0] = rgba8(1.0, 1.0, 1.0, 1.0 / 1.25);
            for (int k = 0; k < 6; k++) {
                double t = k < 4 ? k / 3.0 : (k == 4 ? 0.0 : 0.5);
                double px = g[0] - x + (k == 5 ? 60 : 0) + std::sin(k * 2.1) * 14, py = g[1] - y + 420 * (1 - t),
                       pz = g[2] - z + std::cos(k * 1.7) * 10;
                auto e = [](double v) { return int(std::lround(v + 32768)); };
                bolt[1 + k * 2] = pack16(e(px), e(py));
                bolt[2 + k * 2] = uint32_t(e(pz));
            }
            bolt[31] = 1;
        }
    }
    uint32_t horizon = rgba8(arg(argc, argv, "horizon", 0) / 0.5, 0, 0, 1);
    // stand-ins for the mod's Masser / Secunda phase atlases (4 x 4 tiles of 16 phases): a cratered grey disc lit
    // from the side the phase puts the sun on
    std::vector<uint32_t> phases(256 * 256, 0u);
    for (int py = 0; py < 256; py++)
        for (int px = 0; px < 256; px++) {
            int tile = (py / 64) * 4 + px / 64;
            double u = (px % 64 + 0.5) / 32.0 - 1, v = (py % 64 + 0.5) / 32.0 - 1, r2 = u * u + v * v;
            if (r2 >= 1) continue;
            double nz = std::sqrt(1 - r2), ang = tile / 16.0 * 2 * PI;  // 0 full .. 8 new
            double lit = std::max(0.0, u * -std::sin(ang) + nz * std::cos(ang)) * 0.9 + 0.02;
            double crater = 0.85 + 0.15 * std::sin(u * 23) * std::cos(v * 19);
            phases[py * 256 + px] = rgba8(lit * crater * 0.8, lit * crater * 0.78, lit * crater * 0.76, 1);
        }
    GLuint phaseTex = texture2d(GL_RGBA8, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, phases.data(), true);
    GLuint fogRadTex = texture2d(GL_RGBA8, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, fogRad.data());
    GLuint smokeTex = texture2d(GL_RGBA8, 32, 4, GL_RGBA, GL_UNSIGNED_BYTE, smoke.data());
    GLuint boltTex = texture2d(GL_RGBA8, 32, 4, GL_RGBA, GL_UNSIGNED_BYTE, bolt.data());
    GLuint horizonTex = texture2d(GL_RGBA8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, &horizon);

    GLuint tex[8];
    tex[0] = texture2d(GL_RGBA8, W, H, GL_RGBA, GL_UNSIGNED_BYTE, albedo.data());
    tex[1] = texture2d(GL_RGBA16_, W, H, GL_RGBA, GL_UNSIGNED_SHORT, normalLm.data());
    tex[2] = texture2d(GL_RGBA8, W, H, GL_RGBA, GL_UNSIGNED_BYTE, specularBuf.data());
    tex[3] = texture2d(GL_RGBA8, W, H, GL_RGBA, GL_UNSIGNED_BYTE, material.data());
    std::vector<float> zeroes(N * 4, 0.0f);  // Iris clears the translucent and realm-sky buffers to 0
    for (int i = 4; i < 8; i++) tex[i] = texture2d(i == 6 ? GL_RGBA16_ : GL_RGBA16F_, W, H, GL_RGBA, GL_FLOAT, zeroes.data(), i == 4);
    GLuint alt4 = texture2d(GL_RGBA16F_, W, H, GL_RGBA, GL_FLOAT, nullptr, true), alt0 = texture2d(GL_RGBA16F_, W, H, GL_RGBA, GL_FLOAT, nullptr);
    GLuint d0 = texture2d(GL_DEPTH_COMPONENT32F_, W, H, GL_DEPTH_COMPONENT, GL_FLOAT, depth0.data());
    GLuint d1 = texture2d(GL_DEPTH_COMPONENT32F_, W, H, GL_DEPTH_COMPONENT, GL_FLOAT, depth1.data());
    GLuint labWater = texture2d(GL_RGBA8, W, H, GL_RGBA, GL_UNSIGNED_BYTE, waterMask.data());
    // aether=0: the mod's renderer absent (other dimensions, aether off): 1x1 blanks, as the mod registers them
    bool aetherOn = arg(argc, argv, "aether", 1) != 0;
    float zero4[4] = {0, 0, 0, 0};
    GLuint aetherColor = aetherOn ? texture2d(GL_RGBA16F_, W, H, GL_RGBA, GL_FLOAT, aColor.data())
                                  : texture2d(GL_RGBA16F_, 1, 1, GL_RGBA, GL_FLOAT, zero4);
    GLuint aetherDepth = aetherOn ? texture2d(GL_R32F_, W, H, GL_RED, GL_FLOAT, aDepth.data())
                                  : texture2d(GL_R32F_, 1, 1, GL_RED, GL_FLOAT, zero4);
    GLuint aetherSunMap = haveSunMap ? texture2d(GL_RG32F_, 1024, 1025, GL_RG_, GL_FLOAT, sunMap.data())
                                     : texture2d(GL_RG32F_, 1, 2, GL_RG_, GL_FLOAT, nullptr);
    float one = 1.0f;
    GLuint shadowTex = texture2d(GL_DEPTH_COMPONENT32F_, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, &one);
    glBindTexture(GL_TEXTURE_2D, shadowTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE_, GL_COMPARE_REF_TO_TEXTURE_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    GLuint outTex = texture2d(GL_RGBA8, W, H, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    // the weather grids aether got, as the mod's weather_field / weather_dynamics textures (RGBA8, r in the low byte)
    GLuint weatherFieldTex = texture2d(GL_RGBA8, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, fld.data());
    GLuint weatherDynTex = texture2d(GL_RGBA8, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, dyn.data());

    float fV[16], fP[16], fiV[16], fiP[16];
    toFloat(V, fV);
    toFloat(P, fP);
    toFloat(iV, fiV);
    toFloat(iP, fiP);
    float identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    float shadowP[16] = {1e-4f, 0, 0, 0, 0, 1e-4f, 0, 0, 0, 0, 1e-4f, 0, 0, 0, 0, 1};
    double sv[3] = {V.m[0] * sun[0] + V.m[4] * sun[1] + V.m[8] * sun[2], V.m[1] * sun[0] + V.m[5] * sun[1] + V.m[9] * sun[2],
                    V.m[2] * sun[0] + V.m[6] * sun[1] + V.m[10] * sun[2]};
    GLuint fbo;
    pglGenFramebuffers(1, &fbo);

    // one screen pass: binds samplers by name, sets the uniforms Iris would, renders into RENDERTARGETS
    std::map<std::string, GLuint> samplers;
    auto run = [&](const Program& pr, const std::map<int, GLuint>& targetTex, bool deferredDepth) {
        if (!pr.id) return false;
        pglUseProgram(pr.id);
        int unit = 0;
        auto bindS = [&](const char* name, GLuint t) {
            GLint loc = pglGetUniformLocation(pr.id, name);
            if (loc < 0) return;
            pglActiveTexture(GL_TEXTURE0_ + unit);
            glBindTexture(GL_TEXTURE_2D, t);
            pglUniform1i(loc, unit++);
        };
        for (auto& [name, t] : samplers) bindS(name.c_str(), t);
        bindS("depthtex0", deferredDepth ? d1 : d0);
        bindS("depthtex1", d1);
        bindS("shadowtex0", shadowTex);
        auto m4 = [&](const char* n, const float* v) { GLint l = pglGetUniformLocation(pr.id, n); if (l >= 0) pglUniformMatrix4fv(l, 1, GL_FALSE, v); };
        auto f1 = [&](const char* n, float v) { GLint l = pglGetUniformLocation(pr.id, n); if (l >= 0) pglUniform1f(l, v); };
        m4("gbufferModelView", fV);
        m4("gbufferModelViewInverse", fiV);
        m4("gbufferProjection", fP);
        m4("gbufferProjectionInverse", fiP);
        m4("shadowModelView", identity);
        m4("shadowProjection", shadowP);  // everything lands inside the lit 1x1 stand-in map
        GLint l = pglGetUniformLocation(pr.id, "sunPosition");
        if (l >= 0) pglUniform3f(l, float(sv[0] * 100), float(sv[1] * 100), float(sv[2] * 100));
        // Iris' shadow light: the sun by day, the vanilla moon opposite it by night
        double sl = sun[1] > 0 ? 100.0 : -100.0;
        l = pglGetUniformLocation(pr.id, "shadowLightPosition");
        if (l >= 0) pglUniform3f(l, float(sv[0] * sl), float(sv[1] * sl), float(sv[2] * sl));
        l = pglGetUniformLocation(pr.id, "cameraPosition");
        if (l >= 0) pglUniform3f(l, float(x), float(y), float(z));
        f1("rainStrength", float(rain));
        f1("wetness", float(rain));
        f1("frameTimeCounter", fr.timeSec);
        f1("far", float(nearR + 16));
        f1("viewWidth", float(W));
        f1("viewHeight", float(H));
        f1("temperature", float(temp));
        l = pglGetUniformLocation(pr.id, "fogColor");  // the Deadlands' ember fog (realm=1)
        if (l >= 0) pglUniform3f(l, float(arg(argc, argv, "fogr", 0.42)), float(arg(argc, argv, "fogg", 0.10)), float(arg(argc, argv, "fogb", 0.06)));
        f1("fogStart", float(arg(argc, argv, "fogstart", 24)));
        f1("fogEnd", float(arg(argc, argv, "fogend", 180)));
        l = pglGetUniformLocation(pr.id, "isEyeInWater");
        if (l >= 0) pglUniform1i(l, eyeInWater);
        l = pglGetUniformLocation(pr.id, "eyeBrightnessSmooth");
        if (l >= 0) pglUniform2i(l, 0, 240);
        pglBindFramebuffer(GL_FRAMEBUFFER_, fbo);
        std::vector<GLenum> bufs;
        for (size_t k = 0; k < pr.targets.size(); k++) {
            pglFramebufferTexture2D(GL_FRAMEBUFFER_, GLenum(GL_COLOR_ATTACHMENT0_ + k), GL_TEXTURE_2D, targetTex.at(pr.targets[k]), 0);
            bufs.push_back(GLenum(GL_COLOR_ATTACHMENT0_ + k));
        }
        for (size_t k = pr.targets.size(); k < 8; k++)
            pglFramebufferTexture2D(GL_FRAMEBUFFER_, GLenum(GL_COLOR_ATTACHMENT0_ + k), GL_TEXTURE_2D, 0, 0);
        pglDrawBuffers(GLsizei(bufs.size()), bufs.data());
        glViewport(0, 0, W, H);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glBegin(GL_QUADS);
        glTexCoord2f(0, 0); glVertex2f(-1, -1);
        glTexCoord2f(1, 0); glVertex2f(1, -1);
        glTexCoord2f(1, 1); glVertex2f(1, 1);
        glTexCoord2f(0, 1); glVertex2f(-1, 1);
        glEnd();
        return glGetError() == GL_NO_ERROR;
    };
    // realm=1: the realm program set (shaders/realm/: no terrestrial sky or haze; a realm's fog colour and range)
    bool realm = arg(argc, argv, "realm", 0) != 0;
    std::string S = PACK + (realm ? "/realm/" : "/");
    Program pWater = program(S + "composite.vsh", "tools/pack_lab_water.fsh");
    Program pDeferred = program(S + "deferred.vsh", S + "deferred.fsh");
    Program pComposite = program(S + "composite.vsh", S + "composite.fsh");
    Program pBloom = program(S + "composite1.vsh", S + "composite1.fsh");
    Program pFinal = program(S + "final.vsh", S + "final.fsh");
    if (!pWater.id || !pDeferred.id || !pComposite.id || !pBloom.id || !pFinal.id) return 1;

    samplers = {{"colortex7", tex[7]}, {"colortex0", tex[0]}, {"colortex1", tex[1]}, {"colortex2", tex[2]}, {"colortex3", tex[3]},
                {"labWater", labWater}, {"aetherColor", aetherColor}, {"aetherDepth", aetherDepth},
                {"aetherSunMap", aetherSunMap}, {"weatherField", weatherFieldTex}, {"weatherDyn", weatherDynTex},
                {"fogRadianceTex", fogRadTex}, {"smokeSourcesTex", smokeTex}, {"lightningTex", boltTex},
                {"sunHorizonTex", horizonTex}, {"masserPhases", phaseTex}, {"secundaPhases", phaseTex}};
    bool ok = run(pWater, {{5, tex[5]}, {6, tex[6]}}, false);
    ok = ok && run(pDeferred, {{4, tex[4]}}, true);  // deferred sees the opaque depth only
    samplers["colortex4"] = tex[4];
    samplers["colortex5"] = tex[5];
    samplers["colortex6"] = tex[6];
    ok = ok && run(pComposite, {{4, alt4}}, false);  // reads colortex4, writes its other half (Iris flips)
    glBindTexture(GL_TEXTURE_2D, alt4);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR_);
    pglGenerateMipmap(GL_TEXTURE_2D);
    samplers["colortex4"] = alt4;
    ok = ok && run(pBloom, {{0, alt0}}, false);
    samplers["colortex0"] = alt0;
    ok = ok && run(pFinal, {{0, outTex}}, false);
    if (arg(argc, argv, "probe", 0) != 0) {  // HDR after deferred and composite at a few screen points (x, y from top)
        std::vector<float> hd(N * 4), hc(N * 4);
        glBindTexture(GL_TEXTURE_2D, tex[4]);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, hd.data());
        glBindTexture(GL_TEXTURE_2D, alt4);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, hc.data());
        std::vector<std::array<double, 2>> pts = {{0.5, 0.85}, {0.2, 0.7}, {0.5, 0.5}, {0.5, 0.15}, {0.9, 0.42}};
        double ppx = arg(argc, argv, "px", -1), ppy = arg(argc, argv, "py", -1);  // one exact pixel (from the top)
        if (ppx >= 0 && ppy >= 0) {
            pts = {{(ppx + 0.5) / W, (ppy + 0.5) / H}};
            size_t i = size_t(ppy) * W + size_t(ppx);
            std::printf("pixel %d,%d: aether far %.3f %.3f %.3f a %.3f depth %.1f | bed %.1f surf %.1f cls bed %u surf %u | water px %d\n",
                        int(ppx), int(ppy), farLin[i * 4], farLin[i * 4 + 1], farLin[i * 4 + 2], farLin[i * 4 + 3], farDepth[i],
                        bedDepth[i], surfDepth[i], bedNormal[i] >> 16 & 255, surfNormal[i] >> 16 & 255,
                        int(waterMask[row(i) * 4 + 3] > 0));
        }
        for (auto& p : pts) {
            size_t i = size_t(p[1] * H) * W + size_t(p[0] * W), g = row(i);
            std::printf("probe (%.2f,%.2f) albedo %.3f %.3f %.3f  deferred %.3f %.3f %.3f  composite %.3f %.3f %.3f  dist %.0f\n",
                        p[0], p[1], bedLin[i * 4], bedLin[i * 4 + 1], bedLin[i * 4 + 2], hd[g * 4], hd[g * 4 + 1], hd[g * 4 + 2],
                        hc[g * 4], hc[g * 4 + 1], hc[g * 4 + 2], bedDepth[i]);
        }
    }
    std::vector<uint8_t> px(N * 4), img(N * 4);
    glBindTexture(GL_TEXTURE_2D, outTex);
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    for (uint32_t yy = 0; yy < H; yy++) std::memcpy(&img[size_t(yy) * W * 4], &px[size_t(H - 1 - yy) * W * 4], W * 4);
    lap("gl+pack");
    aether::writePng(outPng, reinterpret_cast<const uint32_t*>(img.data()), W, H);
    std::printf("pack_lab: wrote %s (%s)\n", outPng, ok ? "all passes ran" : "a pass FAILED");
    aether_destroy(ctx);
    return ok ? 0 : 1;
}
