// Exact-height kernel tests (aether_height.slang):
//  1. divRnFrom: correctly rounded division from perturbed first guesses, against the CPU's IEEE division
//  2. known inputs: identity permutations, zero offsets and lattice coordinates make every noise exactly 0, so each
//     branch's result has a closed form computed here in plain C++ double arithmetic
//  3. the Vulkan kernel against the C++ reference on random noise tables and column records (raw bits, all columns)
// Java parity of the whole path is the harness's job (run_exact_harness.ps1 -Main GpuHeightHarness).
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

#include "aether/aether.h"

struct GroupRange {
    uint32_t start[3];
    uint32_t end[3];
};
template <typename T>
struct CpuBuf {
    T* data;
    size_t count;
};
struct HeightGlobals {
    CpuBuf<const int32_t> params;
    CpuBuf<const uint32_t> noiseTab;
    CpuBuf<const uint32_t> columns;
    CpuBuf<uint32_t> outBits;
    CpuBuf<uint32_t> outFlags;
    CpuBuf<double> divTest;
};
extern "C" __declspec(dllimport) void divTestMain(void* varyingInput, void* entryPointParams, void* globalParams);

namespace {

int failures = 0;
#define CHECK(c, ...)                                  \
    do {                                               \
        if (!(c)) {                                    \
            std::printf("FAIL %s:%d: ", __FILE__, __LINE__); \
            std::printf(__VA_ARGS__);                  \
            std::printf("\n");                         \
            failures++;                                \
        }                                              \
    } while (0)

uint64_t bitsOf(double d) {
    uint64_t u;
    std::memcpy(&u, &d, 8);
    return u;
}
double fromBits(uint64_t u) {
    double d;
    std::memcpy(&d, &u, 8);
    return d;
}

// column record helpers (layout: aether_height.slang)
enum : uint32_t {
    F_OCEAN = 1, F_RIDGE = 2, F_PEAK = 4, F_CRAG = 8, F_DUNES = 16, F_WET = 32, F_WETCH = 64, F_LAKE_MIN = 128,
    F_LAKE_MAX = 256, F_RIVER_MIN = 512, F_RIVER_MAX = 1024, F_PAD = 2048, F_PLOT = 4096, F_BED = 8192, F_CPU = 16384
};
void setD(uint32_t* rec, int i, double v) { std::memcpy(rec + 2 + i * 2, &v, 8); }

struct Layer {
    double freq, ox, oy, oz;
    float amp;
    uint8_t perm[256];
};

std::vector<uint32_t> table(const std::vector<Layer> (&noises)[4]) {
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

void testDivision() {
    std::mt19937_64 rng(42);
    const uint32_t n = 1 << 20;
    std::vector<double> rec(size_t(n) * 4);
    std::uniform_int_distribution<int> ex(-60, 60), ulps(-3, 3), mode(0, 3);
    std::uniform_real_distribution<double> m(1.0, 2.0), rel(-1e-9, 1e-9);
    for (uint32_t k = 0; k < n; k++) {
        double a = std::ldexp(m(rng), ex(rng)), b = std::ldexp(m(rng), ex(rng));
        if (k % 7 == 0) b = std::ldexp(1.0, ex(rng));                      // power-of-two divisors
        if (k % 11 == 0) a = b * std::ldexp(1.0, ex(rng) / 4);             // quotients on a binade edge
        if (k % 13 == 0) a = std::nextafter(b, 0.0) * std::ldexp(1.0, 3);  // just under one
        double q = a / b, guess = q;
        switch (mode(rng)) {
            case 0: break;
            case 1: {
                int u = ulps(rng);
                for (int i = 0; i < std::abs(u); i++) guess = std::nextafter(guess, u > 0 ? 1e300 : 0.0);
                break;
            }
            default: guess = q * (1.0 + rel(rng)); break;
        }
        rec[k * 4] = a;
        rec[k * 4 + 1] = b;
        rec[k * 4 + 2] = guess;
        rec[k * 4 + 3] = 0;
    }
    int32_t params[5] = {0, 0, 1, int32_t(n), int32_t(n)};
    HeightGlobals g{{params, 5}, {nullptr, 0}, {nullptr, 0}, {nullptr, 0}, {nullptr, 0}, {rec.data(), rec.size()}};
    GroupRange r{{0, 0, 0}, {n / 64, 1, 1}};
    divTestMain(&r, nullptr, &g);
    uint32_t wrong = 0, flagged = 0;
    for (uint32_t k = 0; k < n; k++) {
        double want = rec[k * 4] / rec[k * 4 + 1];
        if (rec[k * 4 + 3] == -1.0) flagged++;
        else if (bitsOf(rec[k * 4 + 3]) != bitsOf(want)) wrong++;
    }
    std::printf("divRn: %u quotients from perturbed guesses: %u wrong, %u flagged\n", n, wrong, flagged);
    CHECK(wrong == 0 && flagged == 0, "divRn is not correctly rounded");
}

// Closed forms with every noise exactly 0 (frequency 0 and zero offsets: every layer samples the lattice origin):
// the noise-free remainder of TamrielHeight.height in plain double arithmetic.
double smoothstep(double e0, double e1, double x) {
    double t = (x - e0) / (e1 - e0);
    t = t < 0 ? 0 : (t > 1 ? 1 : t);
    return t * t * (3 - 2 * t);
}

void testKnown(AetherHeight* h, const char* name) {
    std::vector<Layer> noises[4];
    for (int n = 0; n < 4; n++) {
        Layer l{0.0, 0.0, 0.0, 0.0, 0.75f, {}};  // frequency 0: every sample at the lattice origin
        for (int i = 0; i < 256; i++) l.perm[i] = uint8_t(i);
        noises[n].push_back(l);
    }
    auto t = table(noises);
    CHECK(aether_height_set_noise(h, t.data(), uint32_t(t.size())) == AETHER_OK, "set_noise");
    const uint32_t cols = 8;
    std::vector<uint32_t> rec(cols * AETHER_HEIGHT_COLUMN_WORDS, 0);
    std::vector<double> want(cols);
    auto r = [&](uint32_t k) { return rec.data() + k * AETHER_HEIGHT_COLUMN_WORDS; };
    // 0: ocean floor capped at sea level - 1
    r(0)[0] = F_OCEAN;
    setD(r(0), 0, 70.0);
    setD(r(0), 1, 0.9);
    want[0] = std::fmin(70.0 + 0.0 * 0.9, 62.0);
    // 1: plain land, hills only
    setD(r(1), 0, 101.5);
    setD(r(1), 1, 3.0);
    want[1] = 101.5 + 3.0 * 0.0;
    // 2: crag terraces with zero noise: step 8, off 0
    r(2)[0] = F_CRAG;
    setD(r(2), 0, 133.7);
    setD(r(2), 6, 0.4);
    {
        double hh = 133.7, tt = (hh + 0.0) / 8.0, fl = std::floor(tt);
        double terraced = (fl + smoothstep(0.5, 0.9, tt - fl)) * 8.0 - 0.0;
        want[2] = hh + (terraced - hh) * 0.4;
    }
    // 3: ridge with zero noise: r1 = r2 = 1
    r(3)[0] = F_RIDGE;
    setD(r(3), 0, 200.0);
    setD(r(3), 3, 0.7);
    setD(r(3), 4, 2.5);
    {
        double hh = 200.0, high = 1 + 0.6 * smoothstep(150, 300, hh);
        want[3] = hh + 2.5 * high * (1.0 * 1.0 * 36 + 1.0 * 1.0 * 1.0 * 12 * 0.7 - 15);
    }
    // 4: lake cap, then river floor, then a pad and the road bed
    r(4)[0] = F_LAKE_MIN | F_RIVER_MAX | F_PAD | F_BED;
    setD(r(4), 0, 90.0);
    setD(r(4), 11, 80.25);
    setD(r(4), 12, 82.0);
    setD(r(4), 13, 85.0);
    setD(r(4), 14, 0.5);
    setD(r(4), 17, 81.0);
    setD(r(4), 18, 0.25);
    {
        double hh = std::fmin(90.0, 80.25);
        hh = std::fmax(hh, 82.0);
        hh = hh + (85.0 - hh) * 0.5;
        want[4] = hh + (81.0 - hh) * 0.25;
    }
    // 5: capped at MAX_SURFACE
    setD(r(5), 0, 600.0);
    want[5] = 568.0;
    // 6: a CPU column
    r(6)[0] = F_CPU;
    want[6] = 0.0;
    // 7: dunes with zero noise: crest 1
    r(7)[0] = F_DUNES;
    setD(r(7), 0, 70.0);
    setD(r(7), 7, 0.5);
    want[7] = 70.0 + 0.5 * (1.0 * 1.0 * 11 - 2);
    std::vector<double> got(cols);
    std::vector<uint32_t> flags(cols);
    CHECK(aether_height_batch(h, -3, 5, 1, 1, cols, rec.data(), got.data(), flags.data(), nullptr) == AETHER_OK,
          "batch: %s", aether_height_last_error(h));
    int bad = 0;
    for (uint32_t k = 0; k < cols; k++) {
        bool flagOk = flags[k] == (k == 6 ? 1u : 0u);
        if (!flagOk || bitsOf(got[k]) != bitsOf(want[k])) {
            std::printf("  %s column %u: got %.17g (flag %u), want %.17g\n", name, k, got[k], flags[k], want[k]);
            bad++;
        }
    }
    std::printf("known inputs (%s): %u columns, %d wrong\n", name, cols, bad);
    CHECK(bad == 0, "known-input heights differ");
}

void randomInputs(std::mt19937_64& rng, std::vector<uint32_t>& t, std::vector<uint32_t>& rec, uint32_t cols) {
    std::vector<Layer> noises[4];
    std::uniform_real_distribution<double> u01(0.0, 1.0);
    for (int n = 0; n < 4; n++) {
        int layers = 2 + int(rng() % 9);
        for (int i = 0; i < layers; i++) {
            Layer l{};
            l.freq = std::ldexp(1.0, -int(rng() % 9)) * ((i & 1) ? 1.0181268882175227 : 1.0);
            l.ox = u01(rng) * 256.0;
            l.oy = u01(rng) * 256.0;
            l.oz = u01(rng) * 256.0;
            l.amp = float(u01(rng) * 1.5);
            for (int k = 0; k < 256; k++) l.perm[k] = uint8_t(k);
            for (int k = 0; k < 256; k++) std::swap(l.perm[k], l.perm[k + rng() % (256 - k)]);
            noises[n].push_back(l);
        }
    }
    t = table(noises);
    rec.assign(size_t(cols) * AETHER_HEIGHT_COLUMN_WORDS, 0);
    for (uint32_t k = 0; k < cols; k++) {
        uint32_t* r = rec.data() + size_t(k) * AETHER_HEIGHT_COLUMN_WORDS;
        uint32_t f = uint32_t(rng()) & 0x3FFFu;  // any combination of the branch flags
        if (k % 97 == 0) f |= F_CPU;
        r[0] = f;
        setD(r, 0, -40.0 + u01(rng) * 600.0);
        for (int i = 1; i < 19; i++) setD(r, i, u01(rng) * (i == 11 || i == 12 || i == 13 || i == 15 || i == 17 ? 300.0 : 1.2));
    }
}

void testParity(AetherHeight* cpu, AetherHeight* gpu) {
    std::mt19937_64 rng(7);
    // origins: around 0, negative, and far out where x * frequency passes the 2^24 wrap
    const int32_t origins[][3] = {{-128, -128, 1}, {-200000, 150000, 3}, {16000000, -17000000, 64}, {40000000, 40000000, 1}};
    uint64_t total = 0, differ = 0, flaggedDiffer = 0, gpuFlagged = 0;
    double gpuKernel = 0, gpuUpload = 0, gpuRead = 0, cpuMs = 0;
    for (int round = 0; round < 4; round++) {
        const uint32_t width = 256, cols = 256 * 256;
        std::vector<uint32_t> t, rec;
        randomInputs(rng, t, rec, cols);
        CHECK(aether_height_set_noise(cpu, t.data(), uint32_t(t.size())) == AETHER_OK, "cpu set_noise");
        CHECK(aether_height_set_noise(gpu, t.data(), uint32_t(t.size())) == AETHER_OK, "gpu set_noise");
        std::vector<double> a(cols), b(cols);
        std::vector<uint32_t> fa(cols), fb(cols);
        AetherHeightTimes ta{}, tb{};
        const int32_t* o = origins[round];
        CHECK(aether_height_batch(cpu, o[0], o[1], o[2], width, cols, rec.data(), a.data(), fa.data(), &ta) == AETHER_OK, "cpu batch");
        CHECK(aether_height_batch(gpu, o[0], o[1], o[2], width, cols, rec.data(), b.data(), fb.data(), &tb) == AETHER_OK,
              "gpu batch: %s", aether_height_last_error(gpu));
        for (uint32_t k = 0; k < cols; k++) {
            total++;
            if (fa[k] != fb[k]) flaggedDiffer++;
            else if (bitsOf(a[k]) != bitsOf(b[k])) {
                if (differ < 5) std::printf("  column %u: cpu %.17g gpu %.17g\n", k, a[k], b[k]);
                differ++;
            }
            gpuFlagged += fb[k];
        }
        cpuMs += ta.totalMs;
        gpuKernel += tb.kernelMs;
        gpuUpload += tb.uploadMs;
        gpuRead += tb.readbackMs;
    }
    std::printf("vulkan vs c++ reference: %llu columns, %llu heights differ, %llu flags differ (%llu flagged); "
                "cpu %.1f ms, gpu upload %.1f + kernel %.1f + readback %.1f ms\n",
                (unsigned long long)total, (unsigned long long)differ, (unsigned long long)flaggedDiffer,
                (unsigned long long)gpuFlagged, cpuMs, gpuUpload, gpuKernel, gpuRead);
    CHECK(differ == 0 && flaggedDiffer == 0, "the Vulkan kernel differs from the C++ reference");
}

}  // namespace

int main() {
    testDivision();
    AetherHeight* cpu = nullptr;
    CHECK(aether_height_create(AETHER_BACKEND_CPU, &cpu) == AETHER_OK, "cpu create");
    if (cpu) testKnown(cpu, "cpu");
    AetherHeight* gpu = nullptr;
    if (aether_height_create(AETHER_BACKEND_VULKAN, &gpu) == AETHER_OK) {
        std::printf("exact height device: %s\n", aether_height_device(gpu));
        testKnown(gpu, "vulkan");
        testParity(cpu, gpu);
        aether_height_destroy(gpu);
    } else {
        std::printf("exact height: no suitable Vulkan device, GPU checks skipped\n");
    }
    aether_height_destroy(cpu);
    std::printf(failures ? "height tests: %d FAILED\n" : "height tests: all passed\n", failures);
    return failures ? 1 : 0;
}
