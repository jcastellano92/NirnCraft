// Exact-noise kernel tests (aether_height.slang):
//  1. known inputs: frequency 0 makes every layer sample the lattice origin, so every value is exactly 0; a column
//     whose lattice fraction would be a float below 2^-100 must come back flagged
//  2. the kernel (C++ reference build) against an independent plain C++ port of Minecraft 26.3's NoiseStack /
//     PerlinNoise on random tables and coordinates, value by value
//  3. the Vulkan kernel against the C++ reference build on the same inputs, raw bits, every slot of every column
// Java parity of the whole path is the harness's job (run_exact_harness.ps1 -Gpu).
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

#include "aether/aether.h"

namespace {

int failures = 0;
#define CHECK(c, ...)                                         \
    do {                                                      \
        if (!(c)) {                                           \
            std::printf("FAIL %s:%d: ", __FILE__, __LINE__);  \
            std::printf(__VA_ARGS__);                         \
            std::printf("\n");                                \
            failures++;                                       \
        }                                                     \
    } while (0)

constexpr uint32_t SLOTS = AETHER_HEIGHT_SLOTS;

uint32_t bitsOf(float f) {
    uint32_t u;
    std::memcpy(&u, &f, 4);
    return u;
}

struct Layer {
    double freq, ox, oy, oz;
    float amp;
    uint8_t perm[256];
};
using Noises = std::vector<Layer>[4];

std::vector<uint32_t> table(const Noises& noises) {
    std::vector<uint32_t> t(8, 0);
    uint32_t first = 0;
    for (int n = 0; n < 4; n++) {
        t[n * 2] = first;
        t[n * 2 + 1] = uint32_t(noises[n].size());
        first += uint32_t(noises[n].size());
    }
    for (int n = 0; n < 4; n++)
        for (const Layer& l : noises[n]) {
            uint32_t w[AETHER_HEIGHT_LAYER_WORDS] = {};
            std::memcpy(w, &l.freq, 8);
            std::memcpy(w + 2, &l.ox, 8);
            std::memcpy(w + 4, &l.oy, 8);
            std::memcpy(w + 6, &l.oz, 8);
            std::memcpy(w + 8, &l.amp, 4);
            std::memcpy(w + 12, l.perm, 256);
            t.insert(t.end(), w, w + AETHER_HEIGHT_LAYER_WORDS);
        }
    return t;
}

// ---- an independent port of the Java (javap of Minecraft 26.3: NoiseStack$Perlin, PerlinNoise, GradientNoise, Mth)

const int GRADIENT[16][3] = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
                             {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};
float gradDot(int h, float x, float y, float z) {
    const int* g = GRADIENT[h & 15];
    return float(g[0]) * x + float(g[1]) * y + float(g[2]) * z;
}
float smoothstep(float t) { return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f); }
float lerp(float t, float a, float b) { return a + t * (b - a); }
float lerp2(float t1, float t2, float a, float b, float c, float d) { return lerp(t2, lerp(t1, a, b), lerp(t1, c, d)); }
float lerp3(float t1, float t2, float t3, float a, float b, float c, float d, float e, float f, float g, float h) {
    return lerp(t3, lerp2(t1, t2, a, b, c, d), lerp2(t1, t2, e, f, g, h));
}
double wrap(double x) {
    const double half = std::nextafter(16777216.0, 0.0);
    if (x >= -half && x < half) return x;
    return x - std::floor(x / 33554432.0 + 0.5) * 33554432.0;
}
float perlin(const Layer& l, double x, double y, double z) {
    auto p = [&](int i) { return int(l.perm[i & 255]); };
    double X = wrap(x) + l.ox, Y = wrap(y) + l.oy, Z = wrap(z) + l.oz;
    int ix = int(std::floor(X)), iy = int(std::floor(Y)), iz = int(std::floor(Z));
    float fx = float(X - double(ix)), fy = float(Y - double(iy)), fz = float(Z - double(iz));
    int a = p(ix), b = p(ix + 1), aa = p(a + iy), ab = p(a + iy + 1), ba = p(b + iy), bb = p(b + iy + 1);
    float d000 = gradDot(p(aa + iz), fx, fy, fz), d100 = gradDot(p(ba + iz), fx - 1.0f, fy, fz);
    float d010 = gradDot(p(ab + iz), fx, fy - 1.0f, fz), d110 = gradDot(p(bb + iz), fx - 1.0f, fy - 1.0f, fz);
    float d001 = gradDot(p(aa + iz + 1), fx, fy, fz - 1.0f), d101 = gradDot(p(ba + iz + 1), fx - 1.0f, fy, fz - 1.0f);
    float d011 = gradDot(p(ab + iz + 1), fx, fy - 1.0f, fz - 1.0f);
    float d111 = gradDot(p(bb + iz + 1), fx - 1.0f, fy - 1.0f, fz - 1.0f);
    return lerp3(smoothstep(fx), smoothstep(fy), smoothstep(fz), d000, d100, d010, d110, d001, d101, d011, d111);
}
float stack(const std::vector<Layer>& layers, double x, double y, double z) {
    float acc = 0.0f;
    for (const Layer& l : layers) acc = acc + l.amp * perlin(l, x * l.freq, y * l.freq, z * l.freq);
    return acc;
}
// TamrielHeight.height's noise calls (int x, z widened)
float slotRef(const Noises& n, uint32_t s, int bx, int bz) {
    double x = bx, z = bz;
    switch (s) {
        case 0: return stack(n[0], x, 0, z);
        case 1: return stack(n[1], x, 0, z);
        case 2: return stack(n[2], x, 0, z);
        case 3: return stack(n[1], x * 2.1, 37, z * 2.1);
        case 4: return stack(n[2], x * 1.7, 113, z * 1.7);
        case 5: return stack(n[1], x * 3.3, 131, z * 3.3);
        case 6: return stack(n[3], x * 0.22, 61, z * 0.22);
        case 7: return stack(n[3], x * 0.8 + z * 0.35, 0, z * 1.7 - x * 0.2);
        case 8: return stack(n[2], x * 1.9, 57, z * 1.9);
        default: return stack(n[0], x * 3.6 + 400, 23, z * 3.6);
    }
}

struct Run {
    std::vector<float> values;
    std::vector<uint32_t> flags;
    AetherHeightTimes t{};
};

Run run(AetherHeight* h, int32_t x0, int32_t z0, int32_t step, uint32_t width, const std::vector<uint32_t>& slots) {
    Run r;
    uint32_t count = uint32_t(slots.size());
    r.values.assign(size_t(count) * SLOTS, -1.0f);
    r.flags.assign(count, 7u);
    AetherStatus s = aether_height_batch(h, x0, z0, step, width, count, slots.data(), r.values.data(), r.flags.data(), &r.t);
    CHECK(s == AETHER_OK, "batch: %s", aether_height_last_error(h));
    return r;
}

void identity(Layer& l) {
    for (int i = 0; i < 256; i++) l.perm[i] = uint8_t(i);
}

void testKnown(AetherHeight* h, const char* name) {
    Noises n;
    for (int i = 0; i < 4; i++) {
        Layer l{0.0, 0.0, 0.0, 0.0, 0.75f, {}};  // frequency 0: every sample at the lattice origin
        identity(l);
        n[i].push_back(l);
    }
    auto t = table(n);
    CHECK(aether_height_set_noise(h, t.data(), uint32_t(t.size())) == AETHER_OK, "set_noise");
    std::vector<uint32_t> slots = {0x3FF, 0x001, 0x2AA, 0x000, 0x155, 0x3FF, 0x3, 0x200};
    Run r = run(h, -5, 7, 3, 4, slots);
    int bad = 0;
    for (size_t k = 0; k < slots.size(); k++) {
        if (r.flags[k] != 0) bad++;
        for (uint32_t s = 0; s < SLOTS; s++)
            if (bitsOf(r.values[s * slots.size() + k]) != 0u) bad++;
    }
    // a lattice fraction of 2^-112: x * f = -2^-60 at x = -1, offset 2^-60 (1 + 2^-52): must be flagged
    Noises tiny;
    for (int i = 0; i < 4; i++) {
        Layer l{std::ldexp(1.0, -60), std::ldexp(1.0 + std::ldexp(1.0, -52), -60), 0.0, 0.0, 1.0f, {}};
        identity(l);
        tiny[i].push_back(l);
    }
    t = table(tiny);
    CHECK(aether_height_set_noise(h, t.data(), uint32_t(t.size())) == AETHER_OK, "set_noise tiny");
    Run f = run(h, -1, 0, 1, 1, {1u});
    bool flagOk = f.flags[0] == 1u;
    std::printf("known inputs (%s): %d wrong values/flags; tiny fraction flagged: %s\n", name, bad, flagOk ? "yes" : "NO");
    CHECK(bad == 0 && flagOk, "known inputs");
}

void randomNoises(std::mt19937_64& rng, Noises& n) {
    std::uniform_real_distribution<double> u01(0.0, 1.0);
    for (int i = 0; i < 4; i++) {
        n[i].clear();
        int layers = 2 + int(rng() % 7);
        for (int k = 0; k < layers; k++) {
            Layer l{};
            l.freq = std::ldexp(1.0, -int(rng() % 9)) * ((k & 1) ? 1.0181268882175227 : 1.0);
            l.ox = u01(rng) * 256.0;
            l.oy = u01(rng) * 256.0;
            l.oz = u01(rng) * 256.0;
            l.amp = float(0.05 + u01(rng) * 1.5);
            identity(l);
            for (int j = 0; j < 256; j++) std::swap(l.perm[j], l.perm[j + rng() % (256 - j)]);
            n[i].push_back(l);
        }
    }
}

void testParity(AetherHeight* cpu, AetherHeight* gpu) {
    std::mt19937_64 rng(7);
    // origins: around 0, negative, and far out where x * frequency passes the 2^24 wrap
    const int32_t origins[][3] = {{-128, -128, 1}, {-200000, 150000, 3}, {16000000, -17000000, 64}, {40000000, 40000000, 1}};
    uint64_t values = 0, refDiff = 0, gpuDiff = 0, flagDiff = 0, flagged = 0;
    double cpuMs = 0, up = 0, kern = 0, read = 0;
    for (int round = 0; round < 4; round++) {
        Noises n;
        randomNoises(rng, n);
        auto t = table(n);
        CHECK(aether_height_set_noise(cpu, t.data(), uint32_t(t.size())) == AETHER_OK, "cpu set_noise");
        if (gpu) CHECK(aether_height_set_noise(gpu, t.data(), uint32_t(t.size())) == AETHER_OK, "gpu set_noise");
        const uint32_t width = 256, count = 256 * 256;
        std::vector<uint32_t> slots(count);
        for (uint32_t k = 0; k < count; k++) slots[k] = 3u | (uint32_t(rng()) & 0x3FCu);
        const int32_t* o = origins[round];
        Run a = run(cpu, o[0], o[1], o[2], width, slots);
        cpuMs += a.t.totalMs;
        // the independent port, on every 7th column
        for (uint32_t k = 0; k < count; k += 7) {
            if (a.flags[k]) continue;
            int bx = o[0] + int(k % width) * o[2], bz = o[1] + int(k / width) * o[2];
            for (uint32_t s = 0; s < SLOTS; s++) {
                if (!(slots[k] & (1u << s))) continue;
                if (bitsOf(a.values[s * count + k]) != bitsOf(slotRef(n, s, bx, bz))) {
                    if (refDiff < 5)
                        std::printf("  ref differs: col %u slot %u: kernel %.9g ref %.9g\n", k, s, a.values[s * count + k],
                                    slotRef(n, s, bx, bz));
                    refDiff++;
                }
            }
        }
        if (!gpu) continue;
        Run b = run(gpu, o[0], o[1], o[2], width, slots);
        up += b.t.uploadMs;
        kern += b.t.kernelMs;
        read += b.t.readbackMs;
        for (uint32_t k = 0; k < count; k++) {
            if (a.flags[k] != b.flags[k]) flagDiff++;
            flagged += a.flags[k];
            for (uint32_t s = 0; s < SLOTS; s++) {
                values++;
                if (bitsOf(a.values[s * count + k]) != bitsOf(b.values[s * count + k])) gpuDiff++;
            }
        }
    }
    std::printf("kernel vs independent port: %llu values differ\n", (unsigned long long)refDiff);
    CHECK(refDiff == 0, "the kernel differs from the plain port of Minecraft's noise");
    if (gpu) {
        std::printf("vulkan vs c++ reference: %llu values (4 x 65536 columns x %u slots), %llu differ, %llu flags differ "
                    "(%llu flagged); cpu %.1f ms, gpu upload %.2f + kernel %.2f + readback %.2f ms\n",
                    (unsigned long long)values, SLOTS, (unsigned long long)gpuDiff, (unsigned long long)flagDiff,
                    (unsigned long long)flagged, cpuMs, up, kern, read);
        CHECK(gpuDiff == 0 && flagDiff == 0, "the Vulkan kernel differs from the C++ reference");
    }
}

}  // namespace

int main() {
    AetherHeight* cpu = nullptr;
    CHECK(aether_height_create(AETHER_BACKEND_CPU, &cpu) == AETHER_OK, "cpu create");
    if (!cpu) return 1;
    testKnown(cpu, "cpu");
    AetherHeight* gpu = nullptr;
    if (aether_height_create(AETHER_BACKEND_VULKAN, &gpu) == AETHER_OK) {
        std::printf("exact noise device: %s\n", aether_height_device(gpu));
        testKnown(gpu, "vulkan");
    } else {
        std::printf("exact noise: no suitable Vulkan device, GPU checks skipped\n");
        gpu = nullptr;
    }
    testParity(cpu, gpu);
    if (gpu) aether_height_destroy(gpu);
    aether_height_destroy(cpu);
    std::printf(failures ? "height tests: %d FAILED\n" : "height tests: all passed\n", failures);
    return failures ? 1 : 0;
}
