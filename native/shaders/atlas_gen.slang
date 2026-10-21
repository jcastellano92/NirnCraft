// The client's world generator (docs/ATLAS-CLIENT-GENERATION.md): whole chunk columns of real blocks from the seed,
// on the GPU (Vulkan compute) and, from this same source compiled to C++, on the CPU. It reproduces Minecraft 26.3's
// NoiseBasedChunkGenerator for the overworld: doFill (the noise router's final density, the aquifer) and buildSurface
// (the material rule), then the mod's map water (its first decoration feature). The mod's Java compiles the world's
// own objects into the program (AtlasGenProgram: the density functions, noise tables, the rule tree, the random
// seeds); the 2D Tamriel inputs (TamrielHeight, the channels, map water, the quart biomes) come per chunk as pages.
//
// Exactness (as atlas_height.slang): float and double arithmetic is IEEE round-to-nearest-even with no contraction
// (slangc -fp-mode precise, spv_exact adds RTE + signed zero/inf/nan preservation and refuses fused or extended
// instructions); Java's evaluation order and promotions are mirrored literally; divisions go through divF / divI
// (correctly rounded by construction, a GPU's division is not). Denormals are not relied on: a perlin fraction small
// enough that its cube could underflow flags the chunk (out bit), the host then takes the server's blocks there.
//
// Passes, one dispatch each (thread id .x = item in the chunk, .y = the chunk's index in the batch):
//   cornerMain   the interpolated functions' inputs at the 5 x 81 x 5 cell corners (4 x 8 x 4 blocks)
//   aquiferMain  the 3 x 57 x 3 aquifer cells around the chunk (centre, fluid status); item 513: skipSamplingAboveY
//   fillMain     per block: final density, aquifer -> stone / water / lava / air
//   topMain      per column: the WORLD_SURFACE_WG height
//   surfaceMain  per column, top down: the material rule, then map water
//   packMain     the 40 sections as 16-bit ids (generator ids or atlas materials), two to a word

struct GenParams {
    int chunks;      // chunks in this batch
    int pageMinCx, pageMinCz, pageW, pageH;  // the page grid (pageIndex) the batch reads
    uint outMode;    // 0: generator block ids, 1: atlas material ids; debug: 2 before the surface, 3 / 4 density bits
    int pad0, pad1;
};
[[vk::push_constant]] ConstantBuffer<GenParams> params;
[[vk::binding(0, 0)]] StructuredBuffer<uint> prog;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> pages;  // written by the page passes (atlas_page.slang)
[[vk::binding(2, 0)]] StructuredBuffer<int> pageIndex;
[[vk::binding(3, 0)]] StructuredBuffer<int> chunkList;     // (cx, cz) per chunk
[[vk::binding(4, 0)]] RWStructuredBuffer<float> corners;   // per chunk CORNER_WORDS * K
[[vk::binding(5, 0)]] RWStructuredBuffer<int> aqCells;     // per chunk AQ_WORDS
[[vk::binding(6, 0)]] RWStructuredBuffer<uint> blocks;     // per chunk 16 * 16 * 640 generator ids
[[vk::binding(7, 0)]] RWStructuredBuffer<int> colInfo;     // per chunk COL_WORDS
[[vk::binding(8, 0)]] RWStructuredBuffer<uint> outIds;     // per chunk 40 * 2048 words; then one flag word per chunk
// (GPU -> GPU, atlas_gen_run) the context's side of the blocks: [0] ids, [1] biomes; per id 2 words: the far state id
// (the section hash's), flags (IG_*); per biome 2 words: grass, foliage (the hash seed's tints); then biomes x ids
// atlas materials (one word each)
[[vk::binding(9, 0)]] StructuredBuffer<uint> ingestTab;
// per chunk INFO_WORDS: [0] lowest ground + 1024, [1] ~(highest top + 1024), [2] the tints' biome, [3]
// pad; then per section 4 words: hash lo, hash hi, kind (SEC_*), pad
[[vk::binding(10, 0)]] RWStructuredBuffer<uint> secInfo;
// the map of Nirn (atlas_map.slang, AtlasGenMap): uploaded once (atlas_gen_set_map)
[[vk::binding(11, 0)]] StructuredBuffer<uint> mapData;
// the page passes' jobs (cx, cz, slot, full) and a flag per page slot (nonzero: a value not proven exact)
[[vk::binding(12, 0)]] StructuredBuffer<int> pageJobs;
[[vk::binding(13, 0)]] RWStructuredBuffer<uint> pageBad;
// far chunks (atlas_page.slang farChunkMain): the counter, the chunks' kinds and slots, the slots' voxels
[[vk::binding(14, 0)]] RWStructuredBuffer<uint> farOut;

#include "atlas_exact.slang"
#include "atlas_map.slang"
#include "atlas_page.slang"

// ---- program layout (AtlasGenProgram writes it; keep in step) --------------------------------------------------
static const uint MAGIC = 0x4E454741u;  // "AGEN"
static const uint H_VERSION = 1, H_REGS = 2, H_K = 3, H_LAYERS = 4, H_STACKS = 5, H_CODE = 6, H_RULES = 7,
                  H_CONDS = 8, H_BIOMESETS = 9, H_BLOCKS = 10, H_NBLOCKS = 11, H_BANDS = 12, H_FIELDS = 13,
                  H_SETWORDS = 14, H_ORES = 15;
static const uint H_ENTRY = 16;  // (start, end) pairs
static const uint E_CORNER = 0, E_FINAL = 1, E_AQ_BARRIER = 2, E_AQ_FLOOD = 3, E_AQ_SPREAD = 4, E_AQ_LAVA = 5,
                  E_AQ_EXCL = 6, E_AQ_SURF = 7, E_PRELIM = 8;
static const uint H_SCALARS = 64;
static const uint S_SEA = 64, S_AQ_LO = 65, S_AQ_HI = 67, S_ORE_LO = 69, S_ORE_HI = 71, S_SURF_LO = 73,
                  S_SURF_HI = 75, S_ZOOM = 77, S_SURFNOISE = 79, S_SECNOISE = 80, S_CLAYNOISE = 81, S_STONE = 82,
                  S_WATER = 83, S_LAVA = 84, S_WAYBELOW = 85, S_LAVALEVEL = 86, S_ORECOUNT = 87, S_RULESTART = 88,
                  S_NBIOMES = 89, S_TEMPLATE_MIN = 90;
// ore vein k (k < 4): 8 words at H_ORES + 8k: density entry, richness entry, gap entry, ore id, raw id, filler id,
// raw chance (float bits), pad (the entries index the entry table)

static const uint LAYER_WORDS = 76;
static const int MIN_Y = -64, NOISE_H = 640, SECTIONS = 40, QY = 176;  // quarts -16..159 (y -64..639)
static const uint CORNERS = 2025;        // 5 x 81 x 5
static const uint AQ_CELLS = 513;        // 3 x 57 x 3
static const uint AQ_WORDS = 513 * 5 + 4;
static const uint COL_WORDS = 256 + 8;   // heights, then [256] skipSamplingAboveY
static const uint BLOCK_WORDS = 16 * 16 * 640;
static const uint OUT_WORDS = 40 * 2048;
// block flags (the program's block table: word 0 flags, word 1 atlas material)
static const uint F_AIR = 1, F_FLUID = 2, F_WATER = 4, F_LAVA = 8, F_MOTION = 16, F_SOFT = 32;
// page layout: fields * 256 floats (field-major, lz * 16 + lx), 256 water words, then 4 * 4 * QY biome ids (u16)
static const uint W_NONE = 0, W_MAP = 1, W_MARSH = 2;

uint hdr(uint i) { return prog[i]; }
uint entryStart(uint e) { return prog[H_ENTRY + e * 2]; }
uint entryEnd(uint e) { return prog[H_ENTRY + e * 2 + 1]; }
float pf(uint w) { return asfloat(prog[w]); }
double pd(uint w) { return asdouble(prog[w], prog[w + 1]); }
uint64_t pu64(uint w) { return (uint64_t(prog[w + 1]) << 32) | uint64_t(prog[w]); }
uint blockFlags(uint id) { return prog[hdr(H_BLOCKS) + id * 2]; }
uint blockMat(uint id) { return prog[hdr(H_BLOCKS) + id * 2 + 1]; }

// ---- exact helpers (Java semantics) -------------------------------------------------------------------------
float absF(float f) { return asfloat(asuint(f) & 0x7FFFFFFFu); }
double absD(double d) { return d < 0.0 ? -d : (d == 0.0 ? 0.0 : d); }
bool isNaNF(float f) { return f != f; }
// Math.min / Math.max (float)
float jminF(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && asuint(b) == 0x80000000u) return b;
    return a <= b ? a : b;
}
float jmaxF(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && asuint(a) == 0x80000000u) return b;
    return a >= b ? a : b;
}
float clampF(float v, float lo, float hi) { return v < lo ? lo : jminF(v, hi); }  // Mth.clamp(float)
double clampD(double v, double lo, double hi) { return v < lo ? lo : (v <= hi ? v : (v != v ? v : hi)); }
float lerpF(float t, float a, float b) { return a + t * (b - a); }  // Mth.lerp(float)

// (int) Math.floor(d) for |d| < 2^31; anything else flags
int floorD(double d) {
    if (!(d > -2147483647.0l && d < 2147483647.0l)) {
        g_bad = 1u;
        return 0;
    }
    int i = int(d);
    if (double(i) > d) i = i - 1;
    return i;
}
int floorF(float f) { return floorD(double(f)); }

// a / b correctly rounded (IEEE float division): the quotient from a double division, then the float among it and its
// two neighbours whose residual a - c * b (exact in double: c * b has at most 48 bits and is within a factor of two
// of a) is smallest. A quotient of two floats is never halfway between two floats, so there is no tie.
[noinline]
float divF(float a, float b) {
    if (a != a || b != b) return asfloat(0x7FC00000u);
    float q = float(double(a) / double(b));
    if (b == 0.0f || a == 0.0f || absF(q) > 3.0e38f || absF(q) < 1.0e-36f) {
        if (b == 0.0f) {
            if (a == 0.0f) return asfloat(0x7FC00000u);
            return asfloat(((asuint(a) ^ asuint(b)) & 0x80000000u) | 0x7F800000u);
        }
        if (a == 0.0f) return asfloat((asuint(a) ^ asuint(b)) & 0x80000000u);
        g_bad = 1u;  // extreme ranges: not proven here
        return q;
    }
    double da = double(a), db = double(b);
    float best = q;
    double r = absD(da - double(q) * db);
    float up = asfloat(asuint(q) + 1u), dn = asfloat(asuint(q) - 1u);
    double ru = absD(da - double(up) * db), rd = absD(da - double(dn) * db);
    if (ru < r) { best = up; r = ru; }
    if (rd < r) { best = dn; }
    return best;
}

// (double) n / (double) d correctly rounded, for integers |n| < 2^31, 0 < d < 2^31: long division to 55 bits + sticky
double divI(int n, int d) {
    if (n == 0) return 0.0;
    bool neg = n < 0;
    uint64_t N = uint64_t(neg ? -int64_t(n) : int64_t(n));
    uint64_t D = uint64_t(d);
    uint64_t q = N / D, r = N - q * D;
    int frac = 0;
    uint64_t lim = uint64_t(1) << 55;
    while (q < lim) {
        r = r << 1;
        q = q << 1;
        if (r >= D) { q = q | 1u; r = r - D; }
        frac++;
    }
    // q has 56+ bits now (55 when the integer part was already big): keep 53, round to nearest even
    int drop = 0;
    uint64_t t = q;
    while (t >= (uint64_t(1) << 53)) { t = t >> 1; drop++; }
    uint64_t rest = q - (t << uint(drop));
    uint64_t half = uint64_t(1) << uint(drop - 1);
    bool sticky = r != 0u;
    if (rest > half || (rest == half && (sticky || (t & 1u) != 0u))) t = t + 1u;
    // value = t * 2^(drop - frac)
    int e = drop - frac;
    double v = double(t);  // t <= 2^53: exact
    // multiply by 2^e (|e| < 1000, no over/underflow for our ranges)
    while (e > 0) { int s = e > 60 ? 60 : e; v = v * double(uint64_t(1) << uint(s)); e -= s; }
    while (e < 0) { int s = -e > 60 ? 60 : -e; v = v * asdouble(0u, uint(1023 - s) << 20); e += s; }
    return neg ? -v : v;
}

// (float) of a non-negative long below 2^63, round to nearest even
float u64ToF(uint64_t v) {
    if (v < (uint64_t(1) << 24)) return float(uint(v));
    int sh = 0;
    uint64_t t = v;
    while (t >= (uint64_t(1) << 24)) { t = t >> 1; sh++; }
    uint64_t rest = v - (t << uint(sh));
    uint64_t half = uint64_t(1) << uint(sh - 1);
    if (rest > half || (rest == half && (t & 1u) != 0u)) t = t + 1u;
    if (t == (uint64_t(1) << 24)) { t = t >> 1; sh++; }
    // t * 2^sh, t < 2^24, sh <= 40: exact in float
    return float(uint(t)) * asfloat(uint(127 + sh) << 23);
}

// Math.round(float) (Java's bit version)
int jroundF(float a) {
    int bits = int(asuint(a));
    int biasedExp = (bits & 0x7F800000) >> 23;
    int shift = (24 - 2 + 127) - biasedExp;
    if ((shift & -32) == 0) {
        int r = (bits & 0x007FFFFF) | 0x00800000;
        if (bits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    float t = a;
    if (t != t) return 0;
    if (t >= 2147483647.0f) return 2147483647;
    if (t <= -2147483648.0f) return int(0x80000000u);
    return int(t);
}

// Java's % and floorMod / floorDiv for ints
// (b > 0) divisions of non-negative operands only: drivers disagree on negative ones (found: floorDiv(y, 40))
int floorDivI(int a, int b) { return a >= 0 ? int(uint(a) / uint(b)) : -int((uint(-(a + 1)) / uint(b)) + 1u); }
int jrem(int a, int b) { return a >= 0 ? int(uint(a) % uint(b)) : -int(uint(-a) % uint(b)); }  // Java's %
int floorModI(int a, int b) { return a - floorDivI(a, b) * b; }

// ---- random: Xoroshiro128++ (XoroshiroRandomSource), Mth.getSeed ------------------------------------------------
struct Xoro {
    uint64_t lo, hi;
};
uint64_t rotl(uint64_t x, uint k) { return (x << k) | (x >> (64u - k)); }
Xoro xoro(uint64_t lo, uint64_t hi) {
    Xoro r;
    r.lo = lo;
    r.hi = hi;
    if ((lo | hi) == 0u) {
        r.lo = (uint64_t(0x9E3779B9u) << 32) | uint64_t(0x7F4A7C15u);  // -7046029254386353131
        r.hi = (uint64_t(0x6A09E667u) << 32) | uint64_t(0xF3BCC909u);  // 7640891576956012809
    }
    return r;
}
uint64_t nextLong(inout Xoro s) {
    uint64_t s0 = s.lo, s1 = s.hi;
    uint64_t result = rotl(s0 + s1, 17u) + s0;
    s1 = s1 ^ s0;
    s.lo = rotl(s0, 49u) ^ s1 ^ (s1 << 21u);
    s.hi = rotl(s1, 28u);
    return result;
}
int nextInt(inout Xoro s) { return int(uint(nextLong(s) & 0xFFFFFFFFu)); }
int nextIntBound(inout Xoro s, int bound) {
    uint64_t randomBits = uint64_t(uint(nextInt(s)));
    uint64_t mult = randomBits * uint64_t(bound);
    uint64_t fractional = mult & 0xFFFFFFFFu;
    if (fractional < uint64_t(bound)) {
        uint unbiased = (~uint(bound) + 1u) % uint(bound);
        while (fractional < uint64_t(unbiased)) {
            randomBits = uint64_t(uint(nextInt(s)));
            mult = randomBits * uint64_t(bound);
            fractional = mult & 0xFFFFFFFFu;
        }
    }
    return int(uint(mult >> 32u));
}
float nextFloat(inout Xoro s) { return float(uint(nextLong(s) >> 40u)) * asfloat(0x33800000u); }  // * 2^-24
double nextDouble(inout Xoro s) {
    // nextBits(53) * 1.110223E-16F: a long times a float is float arithmetic in Java
    return double(u64ToF(nextLong(s) >> 11u) * asfloat(0x25000000u));  // 2^-53
}
// Mth.getSeed(x, y, z)
int64_t mthSeed(int x, int y, int z) {
    // int multiplication (wrapping), then widened; the long products wrap too (done unsigned: no C++ overflow)
    int64_t a = int64_t(int(uint(x) * 3129871u));
    uint64_t b = uint64_t(int64_t(z)) * uint64_t(116129781u);
    uint64_t s = uint64_t(a) ^ b ^ uint64_t(int64_t(y));
    s = s * s * uint64_t(42317861u) + s * uint64_t(11u);
    return int64_t(s) >> 16;
}
Xoro positional(uint w, int x, int y, int z) {
    return xoro(uint64_t(mthSeed(x, y, z)) ^ pu64(w), pu64(w + 2));
}

// ---- noise: 26.3 NoiseStack of PerlinNoise layers -------------------------------------------------------------
static const int GRAD[48] = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
                             0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1};
static const double K_HALF_ROUND_OFF = asdouble(0xFFFFFFFFu, 0x416FFFFFu);
static const double K_ROUND_OFF = asdouble(0x00000000u, 0x41800000u);
static const double K_INV_ROUND_OFF = asdouble(0x00000000u, 0x3E600000u);
static const double K_INV1024 = asdouble(0x00000000u, 0x3F500000u);  // 2^-10
static const double MIN_FRACTION = asdouble(0x00000000u, 0x3D100000u);  // 2^-46: a smaller fraction's cube may be denormal

int permute(uint layer, int i) {
    uint k = uint(i & 255);
    uint word = prog[layer + 12u + (k >> 2)];
    return int((word >> ((k & 3u) * 8u)) & 255u);
}
float gradDot(int h, float x, float y, float z) {
    int g = (h & 15) * 3;
    return float(GRAD[g]) * x + float(GRAD[g + 1]) * y + float(GRAD[g + 2]) * z;
}
float smoothF(float t) { return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f); }
float lerp2F(float t1, float t2, float a, float b, float c, float d) { return lerpF(t2, lerpF(t1, a, b), lerpF(t1, c, d)); }
float lerp3F(float t1, float t2, float t3, float a, float b, float c, float d, float e, float f, float g, float h) {
    return lerpF(t3, lerp2F(t1, t2, a, b, c, d), lerp2F(t1, t2, e, f, g, h));
}
double wrapD(double x) {
    if (x >= -K_HALF_ROUND_OFF && x < K_HALF_ROUND_OFF) return x;
    double t = x * K_INV_ROUND_OFF + 0.5;
    return x - double(floorD(t)) * K_ROUND_OFF;
}
float fracF(double X, int i) {
    double d = X - double(i);
    if (d != 0.0 && d < MIN_FRACTION) g_bad = 1u;
    return float(d);
}
[noinline]
float perlin(uint layer, double x, double y, double z) {
    double X = wrapD(x) + pd(layer + 2u);
    double Y = wrapD(y) + pd(layer + 4u);
    double Z = wrapD(z) + pd(layer + 6u);
    int ix = floorD(X), iy = floorD(Y), iz = floorD(Z);
    float fx = fracF(X, ix), fy = fracF(Y, iy), fz = fracF(Z, iz);
    int a = permute(layer, ix), b = permute(layer, ix + 1);
    int aa = permute(layer, a + iy), ab = permute(layer, a + iy + 1);
    int ba = permute(layer, b + iy), bb = permute(layer, b + iy + 1);
    float d000 = gradDot(permute(layer, aa + iz), fx, fy, fz);
    float d100 = gradDot(permute(layer, ba + iz), fx - 1.0f, fy, fz);
    float d010 = gradDot(permute(layer, ab + iz), fx, fy - 1.0f, fz);
    float d110 = gradDot(permute(layer, bb + iz), fx - 1.0f, fy - 1.0f, fz);
    float d001 = gradDot(permute(layer, aa + iz + 1), fx, fy, fz - 1.0f);
    float d101 = gradDot(permute(layer, ba + iz + 1), fx - 1.0f, fy, fz - 1.0f);
    float d011 = gradDot(permute(layer, ab + iz + 1), fx, fy - 1.0f, fz - 1.0f);
    float d111 = gradDot(permute(layer, bb + iz + 1), fx - 1.0f, fy - 1.0f, fz - 1.0f);
    return lerp3F(smoothF(fx), smoothF(fy), smoothF(fz), d000, d100, d010, d110, d001, d101, d011, d111);
}
// a stack's value at block (bx, by, bz) for a density noise of scales (xzs, ys):
//   point (NoiseFunction.Sampler.sampleValue): NoiseStack.get(bx * xzs, by * ys, bz * xzs): layer at (c * f)
//   volume (sampleVolume -> addToVolume): layer at bx * (xzs * f)
[noinline]
float noiseAt(uint stack, bool point, double bx, double by, double bz, double xzs, double ys) {
    uint first = prog[hdr(H_STACKS) + stack * 2u], count = prog[hdr(H_STACKS) + stack * 2u + 1u];
    float acc = 0.0f;
    double px = bx * xzs, py = by * ys, pz = bz * xzs;
    for (uint l = 0; l < count; l++) {
        uint layer = hdr(H_LAYERS) + (first + l) * LAYER_WORDS;
        double f = pd(layer);
        float amp = asfloat(prog[layer + 8u]);
        float v;
        if (point) v = perlin(layer, px * f, py * f, pz * f);
        else {
            double sxz = xzs * f, sy = ys * f;
            v = perlin(layer, bx * sxz, by * sy, bz * sxz);
        }
        acc = acc + amp * v;
    }
    return acc;
}
// Noise.get(x, y, z) of a stack (material rules, surface depth: the point path at scale 1)
float stackGet(uint stack, double x, double y, double z) {
    return noiseAt(stack, true, x, y, z, 1.0, 1.0);
}

// ---- pages: the Tamriel columns ------------------------------------------------------------------------------
uint pageWords() { return hdr(H_FIELDS) * 256u + 256u + 4u * 4u * uint(QY) / 2u; }
int pageSlot(int cx, int cz) {
    int i = cx - params.pageMinCx, j = cz - params.pageMinCz;
    if (i < 0 || j < 0 || i >= params.pageW || j >= params.pageH) return -1;
    return pageIndex[j * params.pageW + i];
}
// (far columns, atlas_page.slang) a thread's own column fields at one (x, z): no page there
static bool g_colOwn = false;
static int g_colX = 0, g_colZ = 0;
static float g_colVals[16];
float column(uint field, int x, int z) {
    if (g_colOwn && x == g_colX && z == g_colZ && field < 16u) return g_colVals[field];
    int s = pageSlot(x >> 4, z >> 4);
    if (s < 0) { g_bad = g_bad | 2u; return 0.0f; }
    return asfloat(pages[uint(s) * pageWords() + field * 256u + uint(((z & 15) << 4) | (x & 15))]);
}
uint waterWord(int x, int z) {
    int s = pageSlot(x >> 4, z >> 4);
    if (s < 0) { g_bad = g_bad | 2u; return 0u; }
    return pages[uint(s) * pageWords() + hdr(H_FIELDS) * 256u + uint(((z & 15) << 4) | (x & 15))];
}
// the stored noise biome of a quart (ChunkAccess.getNoiseBiome: y clamped to the chunk)
uint quartBiome(int qx, int qy, int qz) {
    int s = pageSlot(qx >> 2, qz >> 2);
    if (s < 0) { g_bad = g_bad | 2u; return 0u; }
    int y = qy < -16 ? -16 : (qy > 159 ? 159 : qy);
    uint i = uint((((qx & 3) * 4 + (qz & 3)) * QY) + (y + 16));
    uint w = pages[uint(s) * pageWords() + hdr(H_FIELDS) * 256u + 256u + (i >> 1)];
    return (i & 1u) != 0u ? (w >> 16) : (w & 0xFFFFu);
}

// ---- the density VM ------------------------------------------------------------------------------------------
// word: op | dst << 8 | a << 16 | b << 24, immediates after. Registers are floats.
static const uint OP_END = 0, OP_CONST = 1, OP_Y = 2, OP_COL = 3, OP_NOISE = 4, OP_GRAD = 5, OP_ADD = 6, OP_SUB = 7,
                  OP_MUL_V = 8, OP_MUL_P = 9, OP_DIV_V = 10, OP_DIV_P = 11, OP_MIN_V = 12, OP_MIN_P = 13,
                  OP_MAX_V = 14, OP_MAX_P = 15, OP_ADDC = 16, OP_MULC = 17, OP_CSUB = 18, OP_CDIV = 19,
                  OP_MINC_V = 20, OP_MINC_P = 21, OP_MAXC_V = 22, OP_MAXC_P = 23, OP_ABS = 24, OP_SQUARE = 25,
                  OP_CUBE = 26, OP_SQUEEZE = 27, OP_NEG = 28, OP_LEAKY = 29, OP_RECIP = 30, OP_SIGN = 31,
                  OP_CLAMP = 32, OP_RANGE = 33, OP_RANGEC = 34, OP_ISEL = 35, OP_INTERP = 36, OP_SLICE = 37,
                  OP_UNSLICE = 38, OP_OUT = 39, OP_MOV = 40, OP_SNOISE = 41, OP_NOISEB = 42;
static const int REGS = 96;

// where an INTERP op reads: the chunk's corner block, and the block's chunk-local position
struct InterpCtx {
    uint base;  // the chunk's first corner word
    int lx, ly, lz;  // block in the chunk (ly from MIN_Y)
    bool valid;
};

float cornerAt(uint base, uint k, int ix, int iy, int iz) {
    return corners[base + ((k * 5u + uint(iz)) * 5u + uint(ix)) * 81u + uint(iy)];
}

// InterpolatedFunction.Sampler.sampleWithBlockStep (cells 4 x 8 x 4): z, then x, then y by repeated steps
float interp(InterpCtx c, uint k) {
    int cx = c.lx >> 2, cy = c.ly >> 3, cz = c.lz >> 2;
    int xi = c.lx & 3, yi = c.ly & 7, zi = c.lz & 3;
    float v000 = cornerAt(c.base, k, cx, cy, cz), v100 = cornerAt(c.base, k, cx + 1, cy, cz);
    float v001 = cornerAt(c.base, k, cx, cy, cz + 1), v101 = cornerAt(c.base, k, cx + 1, cy, cz + 1);
    float v010 = cornerAt(c.base, k, cx, cy + 1, cz), v110 = cornerAt(c.base, k, cx + 1, cy + 1, cz);
    float v011 = cornerAt(c.base, k, cx, cy + 1, cz + 1), v111 = cornerAt(c.base, k, cx + 1, cy + 1, cz + 1);
    float alphaZ = float(zi) * 0.25f;
    float v00_ = lerpF(alphaZ, v000, v001), v01_ = lerpF(alphaZ, v010, v011);
    float v10_ = lerpF(alphaZ, v100, v101), v11_ = lerpF(alphaZ, v110, v111);
    float alphaX = float(xi) * 0.25f;
    float v_0_ = lerpF(alphaX, v00_, v10_), v_1_ = lerpF(alphaX, v01_, v11_);
    float step = (v_1_ - v_0_) * 0.125f;
    float value = v_0_ + step * 0.0f;
    for (int i = 0; i < yi; i++) value = value + step;
    return value;
}

// runs entry e at block (x, y, z); OP_OUT k writes outv[k]
void vmRun(uint e, int x0, int y0, int z0, InterpCtx ic, inout float outv[16]) {
    vmRunRange(entryStart(e), entryEnd(e), x0, y0, z0, ic, outv);
}
// the code words start .. end (relative to H_CODE)
[noinline]
void vmRunRange(uint start, uint stop, int x0, int y0, int z0, InterpCtx ic, inout float outv[16]) {
    float r[REGS];
    int x = x0, y = y0, z = z0;
    int sx[4], sy[4], sz[4];
    int sp = 0;
    uint pc = hdr(H_CODE) + start, end = hdr(H_CODE) + stop;
    while (pc < end) {
        uint w = prog[pc];
        uint op = w & 255u, d = (w >> 8) & 255u, a = (w >> 16) & 255u, b = w >> 24;
        pc++;
        switch (op) {
            case OP_CONST: r[d] = pf(pc); pc++; break;
            case OP_Y: r[d] = float(y); break;
            case OP_COL: r[d] = column(prog[pc], x, z); pc++; break;
            case OP_NOISE: {
                uint stack = prog[pc] & 0xFFFFu;
                bool point = (prog[pc] >> 16) != 0u;
                double xzs = pd(pc + 1), ys = pd(pc + 3);
                pc += 5;
                r[d] = noiseAt(stack, point, double(x), double(y), double(z), xzs, ys);
                break;
            }
            case OP_SNOISE: {
                // NoiseFunction.ShiftedXzSampler.sampleValue (point only): x * xzScale + shift x, y * yScale, z * ...
                uint stack = prog[pc] & 0xFFFFu;
                double xzs = pd(pc + 1), ys = pd(pc + 3);
                pc += 5;
                r[d] = stackGet(stack, double(x) * xzs + double(r[a]), double(y) * ys, double(z) * xzs + double(r[b]));
                break;
            }
            case OP_NOISEB: {
                // ShiftNoiseFunction.ShiftB.sampleValue before its * 4: the offset noise at (z / 4, x / 4, 0)
                uint stack = prog[pc] & 0xFFFFu;
                pc += 1;
                r[d] = stackGet(stack, double(z) * 0.25l, double(x) * 0.25l, 0.0l);
                break;
            }
            case OP_GRAD: {
                // ClampedSampler: axis (0 x, 1 y, 2 z), from, min, max, fromValue, factor
                uint axis = prog[pc];
                int from = int(prog[pc + 1]), lo = int(prog[pc + 2]), hi = int(prog[pc + 3]);
                float fromValue = pf(pc + 4), factor = pf(pc + 5);
                pc += 6;
                int c = axis == 0u ? x : (axis == 1u ? y : z);
                int rel = (c < lo ? lo : (c > hi ? hi : c)) - from;
                r[d] = fromValue + float(rel) * factor;
                break;
            }
            case OP_ADD: r[d] = r[a] + r[b]; break;
            case OP_SUB: r[d] = r[a] - r[b]; break;
            case OP_MUL_V: r[d] = r[a] * r[b]; break;
            case OP_MUL_P: r[d] = r[a] == 0.0f ? 0.0f : r[a] * r[b]; break;
            case OP_DIV_V: r[d] = divF(r[a], r[b]); break;
            case OP_DIV_P: r[d] = r[a] == 0.0f ? 0.0f : divF(r[a], r[b]); break;
            case OP_MIN_V: { float rv = r[b], lv = r[a]; r[d] = rv < lv ? rv : lv; break; }
            case OP_MIN_P: { float lv = r[a]; r[d] = lv <= pf(pc) ? lv : jminF(lv, r[b]); pc++; break; }
            case OP_MAX_V: { float rv = r[b], lv = r[a]; r[d] = rv > lv ? rv : lv; break; }
            case OP_MAX_P: { float lv = r[a]; r[d] = lv >= pf(pc) ? lv : jmaxF(lv, r[b]); pc++; break; }
            case OP_ADDC: r[d] = r[a] + pf(pc); pc++; break;
            case OP_MULC: r[d] = r[a] * pf(pc); pc++; break;
            case OP_CSUB: r[d] = pf(pc) - r[a]; pc++; break;
            case OP_CDIV: r[d] = divF(pf(pc), r[a]); pc++; break;
            case OP_MINC_V: { float c = pf(pc), lv = r[a]; r[d] = c < lv ? c : lv; pc++; break; }
            case OP_MINC_P: r[d] = jminF(r[a], pf(pc)); pc++; break;
            case OP_MAXC_V: { float c = pf(pc), lv = r[a]; r[d] = c > lv ? c : lv; pc++; break; }
            case OP_MAXC_P: r[d] = jmaxF(r[a], pf(pc)); pc++; break;
            case OP_ABS: r[d] = absF(r[a]); break;
            case OP_SQUARE: r[d] = r[a] * r[a]; break;
            case OP_CUBE: r[d] = r[a] * r[a] * r[a]; break;
            case OP_SQUEEZE: {
                float c = clampF(r[a], -1.0f, 1.0f);
                r[d] = c / 2.0f - divF(c * c * c, 24.0f);  // c / 2 is exact
                break;
            }
            case OP_NEG: r[d] = -r[a]; break;
            case OP_LEAKY: { float v = r[a]; r[d] = v > 0.0f ? v : v * pf(pc); pc++; break; }
            case OP_RECIP: r[d] = divF(1.0f, r[a]); break;
            case OP_SIGN: { float v = r[a]; r[d] = v > 0.0f ? 1.0f : (v < 0.0f ? -1.0f : v); break; }
            case OP_CLAMP: r[d] = clampF(r[a], pf(pc), pf(pc + 1)); pc += 2; break;
            case OP_RANGE: {
                // in = r[a]; when in range: r[b]; else r[imm reg]; bounds after
                float in_ = r[a];
                uint other = prog[pc];
                float lo = pf(pc + 1), hi = pf(pc + 2);
                pc += 3;
                r[d] = (in_ >= lo && in_ < hi) ? r[b] : r[other];
                break;
            }
            case OP_RANGEC: {
                float in_ = r[a];
                r[d] = (in_ >= pf(pc) && in_ < pf(pc + 1)) ? pf(pc + 2) : pf(pc + 3);
                pc += 4;
                break;
            }
            case OP_ISEL: {
                // n thresholds, then n + 1 registers (one word each)
                uint n = prog[pc];
                float in_ = r[a];
                uint pick = n;
                for (uint i = 0; i < n; i++) {
                    if (pick == n && in_ < pf(pc + 1 + i)) pick = i;
                }
                r[d] = r[prog[pc + 1 + n + pick]];
                pc += 1 + n + n + 1;
                break;
            }
            case OP_INTERP: {
                if (!ic.valid) { g_bad = g_bad | 4u; r[d] = 0.0f; }
                else r[d] = interp(ic, prog[pc]);
                pc++;
                break;
            }
            case OP_SLICE: {
                // axis a, coordinate imm (a slice's subtree runs at that coordinate)
                if (sp < 4) { sx[sp] = x; sy[sp] = y; sz[sp] = z; }
                sp++;
                int c = int(prog[pc]);
                pc++;
                if (a == 0u) x = c; else if (a == 1u) y = c; else z = c;
                break;
            }
            case OP_UNSLICE: {
                sp--;
                if (sp >= 0 && sp < 4) { x = sx[sp]; y = sy[sp]; z = sz[sp]; }
                break;
            }
            case OP_OUT: outv[d] = r[a]; break;
            case OP_MOV: r[d] = r[a]; break;
            default: g_bad = g_bad | 8u; pc = end; break;
        }
    }
}

float vmPointRange(uint start, uint stop, int x, int y, int z) {
    InterpCtx ic;
    ic.base = 0u; ic.lx = 0; ic.ly = 0; ic.lz = 0; ic.valid = false;
    float o[16];
    for (int i = 0; i < 16; i++) o[i] = 0.0f;
    vmRunRange(start, stop, x, y, z, ic, o);
    return o[0];
}

float vmPoint(uint e, int x, int y, int z) {
    InterpCtx ic;
    ic.base = 0u; ic.lx = 0; ic.ly = 0; ic.lz = 0; ic.valid = false;
    float o[16];
    for (int i = 0; i < 16; i++) o[i] = 0.0f;
    vmRun(e, x, y, z, ic, o);
    return o[0];
}

// ---- per-chunk bases ----------------------------------------------------------------------------------------
uint cornerBase(uint ci) { return ci * CORNERS * hdr(H_K); }
uint aqBase(uint ci) { return ci * AQ_WORDS; }
uint blockBase(uint ci) { return ci * BLOCK_WORDS; }
uint colBase(uint ci) { return ci * COL_WORDS; }
// blocks: x + 16 * (z + 16 * (y - MIN_Y)), like a section stack
uint blockIndex(int lx, int ly, int lz) { return uint(lx + 16 * (lz + 16 * ly)); }
void flagChunk(uint ci, uint flags) {
    if (flags != 0u) outIds[uint(params.chunks) * OUT_WORDS + ci] = 1u;  // racing threads only ever store 1
}

// ---- pass 1: the interpolated inputs at the cell corners -----------------------------------------------------
[shader("compute")]
[numthreads(64, 1, 1)]
void cornerMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, c = id.x;
    if (c >= CORNERS || ci >= uint(params.chunks)) return;
    int cx = chunkList[ci * 2u], cz = chunkList[ci * 2u + 1u];
    int ix = int(c / 405u), rem = int(c % 405u), iz = rem / 81, iy = rem % 81;
    g_bad = 0u;
    InterpCtx ic;
    ic.base = 0u; ic.lx = 0; ic.ly = 0; ic.lz = 0; ic.valid = false;
    float o[16];
    for (int i = 0; i < 16; i++) o[i] = 0.0f;
    vmRun(E_CORNER, cx * 16 + ix * 4, MIN_Y + iy * 8, cz * 16 + iz * 4, ic, o);
    uint K = hdr(H_K);
    for (uint k = 0; k < K && k < 16u; k++)
        corners[cornerBase(ci) + ((k * 5u + uint(iz)) * 5u + uint(ix)) * 81u + uint(iy)] = o[k];
    flagChunk(ci, g_bad);
}

// ---- pass 2: the aquifer's cells (Aquifer.NoiseBasedAquifer) -------------------------------------------------
// cell (gx, gy, gz) of the chunk's grid: gx in cx-1..cx+1, gy in -7..49, gz in cz-1..cz+1; index ((gy+7)*3+dz)*3+dx
// words per cell: location x, y, z, fluid level, fluid type (generator id)
static const int GRID_MIN_Y = -7;

int surfaceLevelAt(int x, int z) {
    // Mth.floor(surface_level.sampleValue(quantised x, 0, quantised z))
    int qx = (x >> 2) << 2, qz = (z >> 2) << 2;
    return floorF(vmPoint(E_AQ_SURF, qx, 0, qz));
}
// the global fluid picker: lava below min(-54, sea level), else the sea
void globalFluid(int y, out int level, out uint type) {
    int sea = int(hdr(S_SEA));
    int lavaY = int(hdr(S_LAVALEVEL));
    if (y < (lavaY < sea ? lavaY : sea)) { level = lavaY; type = hdr(S_LAVA); }
    else { level = sea; type = hdr(S_WATER); }
}
uint fluidAt(int level, uint type, int y) { return y < level ? type : 0u; }  // FluidStatus.at (0 = air)

static const int OFFX[13] = {0, -2, -1, 0, 1, -3, -2, -1, 1, -2, -1, 0, 1};
static const int OFFZ[13] = {0, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1};

void computeFluid(int x, int y, int z, out int outLevel, out uint outType) {
    int gLevel; uint gType;
    globalFluid(y, gLevel, gType);
    int lowest = 2147483647;
    int top = y + 12, bottom = y - 12;
    bool centreUnder = false;
    for (int i = 0; i < 13; i++) {
        int sx = x + OFFX[i] * 16, sz = z + OFFZ[i] * 16;
        int sl = surfaceLevelAt(sx, sz);
        int adj = sl + 8;
        bool start = i == 0;
        if (start && bottom > adj) { outLevel = gLevel; outType = gType; return; }
        bool pokes = top > adj;
        if (pokes || start) {
            int fl; uint ft;
            globalFluid(adj, fl, ft);
            if (fluidAt(fl, ft, adj) != 0u) {
                if (start) centreUnder = true;
                if (pokes) { outLevel = fl; outType = ft; return; }
            }
        }
        lowest = lowest < sl ? lowest : sl;
    }
    // computeSurfaceLevel
    double partially, fully;
    if (vmPoint(E_AQ_EXCL, x, y, z) > 0.0f) {
        partially = -1.0;
        fully = -1.0;
    } else {
        int below = (lowest + 8) - y;
        // Mth.clampedMap(below, 0, 64, 1, 0): clampedLerp(below / 64.0, 1, 0)
        double factor = 0.0;
        if (centreUnder) {
            double t = double(below) * 0.015625l;
            factor = t < 0.0l ? 1.0l : (t > 1.0l ? 0.0l : 1.0l + t * (0.0l - 1.0l));
        }
        double flood = clampD(double(vmPoint(E_AQ_FLOOD, x, y, z)), -1.0, 1.0);
        // Mth.map(factor, 1, 0, a, b) = a + ((factor - 1) / (0 - 1)) * (b - a)
        double inv = (factor - 1.0) * -1.0;
        double fullyT = -0.3l + inv * (0.8l - -0.3l);  // double literals: Slang's plain ones are float
        double partT = -0.8l + inv * (0.4l - -0.8l);
        partially = flood - partT;
        fully = flood - fullyT;
    }
    int level;
    if (fully > 0.0) level = gLevel;
    else if (partially > 0.0) {
        int cellX = floorDivI(x, 16), cellY = floorDivI(y, 40), cellZ = floorDivI(z, 16);
        int middle = cellY * 40 + 20;
        float spreadF = vmPoint(E_AQ_SPREAD, cellX, cellY, cellZ) * 10.0f;
        // Mth.quantize(spread, 3) = floor(spread / 3.0) * 3, exact: floor of the exact quotient
        double sd = double(spreadF);
        if (!(absF(spreadF) < 1.0e6f)) {  // (inf / NaN: the loops below would not end)
            g_bad = g_bad | 1u;
            sd = 0.0l;
        }
        int q = floorD(sd * (1.0l / 3.0l));
        while (double(q + 1) * 3.0 <= sd) q++;
        while (double(q) * 3.0 > sd) q--;
        int target = middle + q * 3;
        level = lowest < target ? lowest : target;
    } else level = int(hdr(S_WAYBELOW));
    uint type = gType;
    if (level <= -10 && level != int(hdr(S_WAYBELOW)) && gType != hdr(S_LAVA)) {
        int tx = floorDivI(x, 64), ty = floorDivI(y, 40), tz = floorDivI(z, 64);
        float lava = vmPoint(E_AQ_LAVA, tx, ty, tz);
        if (double(absF(lava)) > 0.3l) type = hdr(S_LAVA);
    }
    outLevel = level;
    outType = type;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void aquiferMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, c = id.x;
    if (c > AQ_CELLS || ci >= uint(params.chunks)) return;
    int cx = chunkList[ci * 2u], cz = chunkList[ci * 2u + 1u];
    g_bad = 0u;
    if (c == AQ_CELLS) {
        // skipSamplingAboveY: the highest preliminary surface over the grid's blocks, at quart positions
        int minX = (cx - 1) * 16, maxX = (cx + 1) * 16 + 9, minZ = (cz - 1) * 16, maxZ = (cz + 1) * 16 + 9;
        int best = -2147483647 - 1;
        for (int qx = minX >> 2; qx <= (maxX >> 2); qx++)
            for (int qz = minZ >> 2; qz <= (maxZ >> 2); qz++) {
                int sl = surfaceLevelAt(qx << 2, qz << 2);
                best = best > sl ? best : sl;
            }
        int adj = best + 8;
        int skipGrid = floorDivI(adj + 12, 12) + 1;
        aqCells[aqBase(ci) + AQ_CELLS * 5u] = skipGrid * 12 + 11 - 1;
        flagChunk(ci, g_bad);
        return;
    }
    int dx = int(c % 3u), dz = int((c / 3u) % 3u), gy = int(c / 9u) + GRID_MIN_Y;
    int gx = cx - 1 + dx, gz = cz - 1 + dz;
    Xoro rnd = positional(S_AQ_LO, gx, gy, gz);
    int lx = (gx << 4) + nextIntBound(rnd, 10);
    int ly = gy * 12 + nextIntBound(rnd, 9);
    int lz = (gz << 4) + nextIntBound(rnd, 10);
    int level; uint type;
    computeFluid(lx, ly, lz, level, type);
    uint w = aqBase(ci) + c * 5u;
    aqCells[w] = lx;
    aqCells[w + 1] = ly;
    aqCells[w + 2] = lz;
    aqCells[w + 3] = level;
    aqCells[w + 4] = int(type);
    flagChunk(ci, g_bad);
}

// ---- pass 3: density and substance per block ------------------------------------------------------------------
double similarity(int d1, int d2) { return 1.0 - divI(d2 - d1, 25); }

double pressure(int x, int y, int z, inout double barrier, inout bool haveBarrier, int l1, uint t1, int l2, uint t2) {
    uint a = fluidAt(l1, t1, y), b = fluidAt(l2, t2, y);
    uint lava = hdr(S_LAVA), water = hdr(S_WATER);
    if ((a == lava && b == water) || (a == water && b == lava)) return 2.0;
    int diff = l1 - l2;
    diff = diff < 0 ? -diff : diff;
    if (diff == 0) return 0.0;
    // average (l1 + l2) / 2 and y + 0.5 are halves: work in halves (m2 = 2 * value) for the exact divisions
    double average = 0.5 * double(l1 + l2);
    double above = double(y) + 0.5 - average;
    double base = double(diff) * 0.5;
    double toMiddle = base - absD(above);
    double gradient;
    // centrePoint = c (a half-integer); c / 1.5 = 2c / 3 etc.: exact via divI on 2c
    if (above > 0.0) {
        double centre = 0.0 + toMiddle;
        int c2 = int(centre * 2.0);
        gradient = centre > 0.0 ? divI(c2, 3) : divI(c2, 5);
    } else {
        double centre = 3.0 + toMiddle;
        int c2 = int(centre * 2.0);
        gradient = centre > 0.0 ? divI(c2, 6) : divI(c2, 20);
    }
    double noise = 0.0;
    if (!(gradient < -2.0) && !(gradient > 2.0)) {
        if (!haveBarrier) {
            barrier = double(vmPoint(E_AQ_BARRIER, x, y, z));
            haveBarrier = true;
        }
        noise = barrier;
    }
    return 2.0 * (noise + gradient);
}

uint substance(uint ci, int x, int y, int z, float density) {
    if (double(density) > 0.0) return hdr(S_STONE);
    int gLevel; uint gType;
    globalFluid(y, gLevel, gType);
    uint ab = aqBase(ci);
    if (y > aqCells[ab + AQ_CELLS * 5u]) return fluidAt(gLevel, gType, y);
    if (fluidAt(gLevel, gType, y) == hdr(S_LAVA)) return hdr(S_LAVA);
    int cx = chunkList[ci * 2u], cz = chunkList[ci * 2u + 1u];
    int xa = (x - 5) >> 4, ya = floorDivI(y + 1, 12), za = (z - 5) >> 4;
    int d1 = 2147483647, d2 = 2147483647, d3 = 2147483647, d4 = 2147483647;
    uint i1 = 0u, i2 = 0u, i3 = 0u, i4 = 0u;
    for (int x1 = 0; x1 <= 1; x1++)
        for (int y1 = -1; y1 <= 1; y1++)
            for (int z1 = 0; z1 <= 1; z1++) {
                int gx = xa + x1, gy = ya + y1, gz = za + z1;
                int ddx = gx - (cx - 1), ddz = gz - (cz - 1), ddy = gy - GRID_MIN_Y;
                if (ddx < 0 || ddx > 2 || ddz < 0 || ddz > 2 || ddy < 0 || ddy > 56) { g_bad = g_bad | 16u; continue; }
                uint idx = uint((ddy * 3 + ddz) * 3 + ddx);
                uint w = ab + idx * 5u;
                int dx = aqCells[w] - x, dy = aqCells[w + 1] - y, dz = aqCells[w + 2] - z;
                int nd = dx * dx + dy * dy + dz * dz;
                if (d1 >= nd) { i4 = i3; i3 = i2; i2 = i1; i1 = idx; d4 = d3; d3 = d2; d2 = d1; d1 = nd; }
                else if (d2 >= nd) { i4 = i3; i3 = i2; i2 = idx; d4 = d3; d3 = d2; d2 = nd; }
                else if (d3 >= nd) { i4 = i3; i3 = idx; d4 = d3; d3 = nd; }
                else if (d4 >= nd) { i4 = idx; d4 = nd; }
            }
    int l1 = aqCells[ab + i1 * 5u + 3], l2 = aqCells[ab + i2 * 5u + 3], l3 = aqCells[ab + i3 * 5u + 3];
    uint t1 = uint(aqCells[ab + i1 * 5u + 4]), t2 = uint(aqCells[ab + i2 * 5u + 4]), t3 = uint(aqCells[ab + i3 * 5u + 4]);
    double s12 = similarity(d1, d2);
    uint fluid = fluidAt(l1, t1, y);
    if (s12 <= 0.0) return fluid;
    if (fluid == hdr(S_WATER)) {
        int gl; uint gt;
        globalFluid(y - 1, gl, gt);
        if (fluidAt(gl, gt, y - 1) == hdr(S_LAVA)) return fluid;
    }
    double barrier = 0.0;
    bool haveBarrier = false;
    double dd = double(density);
    double b12 = s12 * pressure(x, y, z, barrier, haveBarrier, l1, t1, l2, t2);
    if (dd + b12 > 0.0) return hdr(S_STONE);
    double s13 = similarity(d1, d3);
    if (s13 > 0.0) {
        double b13 = s12 * s13 * pressure(x, y, z, barrier, haveBarrier, l1, t1, l3, t3);
        if (dd + b13 > 0.0) return hdr(S_STONE);
    }
    double s23 = similarity(d2, d3);
    if (s23 > 0.0) {
        double b23 = s12 * s23 * pressure(x, y, z, barrier, haveBarrier, l2, t2, l3, t3);
        if (dd + b23 > 0.0) return hdr(S_STONE);
    }
    return fluid;
}

uint debugAquifer(uint ci, int x, int y, int z, float density, uint sel) {
    uint ab = aqBase(ci);
    int cx = chunkList[ci * 2u], cz = chunkList[ci * 2u + 1u];
    int xa = (x - 5) >> 4, ya = floorDivI(y + 1, 12), za = (z - 5) >> 4;
    int d1 = 2147483647, d2 = 2147483647, d3 = 2147483647, d4 = 2147483647;
    uint i1 = 0u, i2 = 0u, i3 = 0u, i4 = 0u;
    for (int x1 = 0; x1 <= 1; x1++)
        for (int y1 = -1; y1 <= 1; y1++)
            for (int z1 = 0; z1 <= 1; z1++) {
                int gx = xa + x1, gy = ya + y1, gz = za + z1;
                int ddx = gx - (cx - 1), ddz = gz - (cz - 1), ddy = gy - GRID_MIN_Y;
                if (ddx < 0 || ddx > 2 || ddz < 0 || ddz > 2 || ddy < 0 || ddy > 56) continue;
                uint idx = uint((ddy * 3 + ddz) * 3 + ddx);
                uint w = ab + idx * 5u;
                int dx = aqCells[w] - x, dy = aqCells[w + 1] - y, dz = aqCells[w + 2] - z;
                int nd = dx * dx + dy * dy + dz * dz;
                if (d1 >= nd) { i4 = i3; i3 = i2; i2 = i1; i1 = idx; d4 = d3; d3 = d2; d2 = d1; d1 = nd; }
                else if (d2 >= nd) { i4 = i3; i3 = i2; i2 = idx; d4 = d3; d3 = d2; d2 = nd; }
                else if (d3 >= nd) { i4 = i3; i3 = idx; d4 = d3; d3 = nd; }
                else if (d4 >= nd) { i4 = idx; d4 = nd; }
            }
    int l1 = aqCells[ab + i1 * 5u + 3], l2 = aqCells[ab + i2 * 5u + 3];
    uint t1 = uint(aqCells[ab + i1 * 5u + 4]), t2 = uint(aqCells[ab + i2 * 5u + 4]);
    double s12 = similarity(d1, d2);
    double barrier = 0.0;
    bool have = false;
    double p12 = pressure(x, y, z, barrier, have, l1, t1, l2, t2);
    if (sel == 2u) return uint(l1);
    if (sel == 3u) return uint(l2);
    if (sel == 4u) return asuint(float(s12));
    if (sel == 5u) return asuint(float(p12));
    return i1 | (i2 << 8) | (uint(d1) << 16);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void fillMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, i = id.x;
    if (i >= BLOCK_WORDS || ci >= uint(params.chunks)) return;
    int cx = chunkList[ci * 2u], cz = chunkList[ci * 2u + 1u];
    int lx = int(i & 15u), lz = int((i >> 4) & 15u), ly = int(i >> 8);
    int x = cx * 16 + lx, y = MIN_Y + ly, z = cz * 16 + lz;
    g_bad = 0u;
    InterpCtx ic;
    ic.base = cornerBase(ci); ic.lx = lx; ic.ly = ly; ic.lz = lz; ic.valid = true;
    float o[16];
    for (int k = 0; k < 16; k++) o[k] = 0.0f;
    vmRun(E_FINAL, x, y, z, ic, o);
    // debug modes (parity tool): 3, 4 the final density's bits
    uint value;
    if (params.outMode < 3u) value = substance(ci, x, y, z, o[0]);
    else {
        uint sel = (params.outMode - 3u) >> 1;
        if (sel == 0u) value = asuint(o[0]);
        else if (sel == 1u) value = asuint(vmPoint(E_AQ_BARRIER, x, y, z));
        else value = debugAquifer(ci, x, y, z, o[0], sel);
    }
    blocks[blockBase(ci) + i] = value;
    flagChunk(ci, g_bad);
}

// ---- pass 4: heights ----------------------------------------------------------------------------------------------
// colInfo: [0..255] the WORLD_SURFACE_WG top (highest non-air y; MIN_Y - 1 when none)
[shader("compute")]
[numthreads(64, 1, 1)]
void topMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, c = id.x;
    if (c >= 256u || ci >= uint(params.chunks)) return;
    if (params.outMode >= 3u) return;  // debug: the blocks hold densities
    int lx = int(c & 15u), lz = int(c >> 4);
    int top = MIN_Y - 1;
    for (int ly = NOISE_H - 1; ly >= 0; ly--) {
        if ((blockFlags(blocks[blockBase(ci) + blockIndex(lx, ly, lz)]) & F_AIR) == 0u) { top = MIN_Y + ly; break; }
    }
    colInfo[colBase(ci) + c] = top;
}

// ---- pass 5: the material rule (MaterialSystem.buildSurface), then map water --------------------------------------
// rule code words: op | arg << 8; R_TEST cond target; R_BLOCK id; R_ORE vein; R_BANDS; R_END
static const uint R_END = 0, R_TEST = 1, R_BLOCK = 2, R_ORE = 3, R_BANDS = 4;
// conditions: 8 words each: kind, then parameters
static const uint C_STONE_DEPTH = 1, C_WATER = 2, C_Y = 3, C_STEEP = 4, C_HOLE = 5, C_NOISE = 6, C_VGRAD = 7,
                  C_ABOVE_PRELIM = 8, C_BIOME = 9, C_NOT = 10, C_TRUE = 11, C_FALSE = 12;

struct SurfaceCtx {
    int x, z, gradX, gradZ, surfaceDepth;
    bool haveSecondary;
    double secondary;
    bool haveMinSurface;
    int minSurface;
    int y, stoneAbove, stoneBelow, waterHeight;
    bool haveBiome;
    uint biome;
    uint ci;
    int lx, lz;
    bool far;  // a far column (atlas_page.slang tileSurfMain): biomes from the biome kernels, ore veins left out
};

uint biomeAt(int x, int y, int z) {
    int3 q = zoomQuart(x, y, z);
    return quartBiome(q.x, q.y, q.z);
}
// BiomeManager.getBiome's quart: the seeded voronoi zoom
[noinline]
int3 zoomQuart(int x, int y, int z) {
    int absX = x - 2, absY = y - 2, absZ = z - 2;
    int px = absX >> 2, py = absY >> 2, pz = absZ >> 2;
    double fx = double(absX & 3) * 0.25, fy = double(absY & 3) * 0.25, fz = double(absZ & 3) * 0.25;
    uint64_t seed = pu64(S_ZOOM);
    int minI = 0;
    double minD = asdouble(0u, 0x7FF00000u);
    for (int i = 0; i < 8; i++) {
        bool xe = (i & 4) == 0, ye = (i & 2) == 0, ze = (i & 1) == 0;
        int cx = xe ? px : px + 1, cy = ye ? py : py + 1, cz = ze ? pz : pz + 1;
        double ddx = xe ? fx : fx - 1.0, ddy = ye ? fy : fy - 1.0, ddz = ze ? fz : fz - 1.0;
        uint64_t mul = (uint64_t(0x5851F42Du) << 32) | uint64_t(0x4C957F2Du);   // 6364136223846793005
        uint64_t inc = (uint64_t(0x14057B7Eu) << 32) | uint64_t(0xF767814Fu);   // 1442695040888963407
        uint64_t r = seed;
        r = r * (r * mul + inc) + uint64_t(int64_t(cx));
        r = r * (r * mul + inc) + uint64_t(int64_t(cy));
        r = r * (r * mul + inc) + uint64_t(int64_t(cz));
        r = r * (r * mul + inc) + uint64_t(int64_t(cx));
        r = r * (r * mul + inc) + uint64_t(int64_t(cy));
        r = r * (r * mul + inc) + uint64_t(int64_t(cz));
        // Math.floorMod(rval >> 24, 1024) on a long: the low 10 bits of rval >> 24; / 1024.0 is exact (* 2^-10)
        double fidX = (double(int((uint64_t(int64_t(r) >> 24)) & 1023u)) * K_INV1024 - 0.5) * 0.9l;
        r = r * (r * mul + inc) + seed;
        double fidY = (double(int((uint64_t(int64_t(r) >> 24)) & 1023u)) * K_INV1024 - 0.5) * 0.9l;
        r = r * (r * mul + inc) + seed;
        double fidZ = (double(int((uint64_t(int64_t(r) >> 24)) & 1023u)) * K_INV1024 - 0.5) * 0.9l;
        double a = ddz + fidZ, b = ddy + fidY, c = ddx + fidX;
        double dist = a * a + b * b + c * c;
        if (minD > dist) { minI = i; minD = dist; }
    }
    int bx = (minI & 4) == 0 ? px : px + 1, by = (minI & 2) == 0 ? py : py + 1, bz = (minI & 1) == 0 ? pz : pz + 1;
    return int3(bx, by, bz);
}

int surfaceDepthAt(int x, int z) {
    double n = double(stackGet(hdr(S_SURFNOISE), double(x), 0.0, double(z)));
    Xoro rnd = positional(S_SURF_LO, x, 0, z);
    double v = n * 2.75 + 3.0 + nextDouble(rnd) * 0.25;
    return int(v);  // (int) truncation; |v| small
}

[noinline]
bool condition(uint cond, inout SurfaceCtx c) {
    bool negate = false;
    for (int depth = 0; depth < 8; depth++) {
        uint w = hdr(H_CONDS) + cond * 8u;
        uint kind = prog[w];
        bool res = false;
        if (kind == C_NOT) { negate = !negate; cond = prog[w + 1]; continue; }
        if (kind == C_TRUE) res = true;
        else if (kind == C_FALSE) res = false;
        else if (kind == C_STONE_DEPTH) {
            int offset = int(prog[w + 1]);
            bool addSurface = prog[w + 2] != 0u;
            int secondaryRange = int(prog[w + 3]);
            bool ceiling = prog[w + 4] != 0u;
            int stoneDepth = ceiling ? c.stoneBelow : c.stoneAbove;
            int surfaceDepth = addSurface ? c.surfaceDepth : 0;
            int secondary = 0;
            if (secondaryRange != 0) {
                if (!c.haveSecondary) {
                    c.secondary = double(stackGet(hdr(S_SECNOISE), double(c.x), 0.0, double(c.z)));
                    c.haveSecondary = true;
                }
                // (int) Mth.map(s, -1, 1, 0, range) = (int) (0 + ((s + 1) / 2) * (range - 0))
                secondary = int(0.0 + ((c.secondary - -1.0) * 0.5) * double(secondaryRange));
            }
            res = stoneDepth <= 1 + offset + surfaceDepth + secondary;
        } else if (kind == C_WATER) {
            int offset = int(prog[w + 1]), mult = int(prog[w + 2]);
            bool addStone = prog[w + 3] != 0u;
            res = c.waterHeight == int(0x80000000u) ||
                  c.y + (addStone ? c.stoneAbove : 0) >= c.waterHeight + offset + c.surfaceDepth * mult;
        } else if (kind == C_Y) {
            int anchor = int(prog[w + 1]), mult = int(prog[w + 2]);
            bool addStone = prog[w + 3] != 0u;
            res = c.y + (addStone ? c.stoneAbove : 0) >= anchor + c.surfaceDepth * mult;
        } else if (kind == C_STEEP) res = c.gradX <= -4 || c.gradZ >= 4;
        else if (kind == C_HOLE) res = c.surfaceDepth <= 0;
        else if (kind == C_NOISE) {
            uint stack = prog[w + 1];
            bool is3d = prog[w + 2] != 0u;
            double lo = pd(w + 3), hi = pd(w + 5);
            double v = double(stackGet(stack, double(c.x), is3d ? double(c.y) : 0.0, double(c.z)));
            res = v >= lo && v <= hi;
        } else if (kind == C_VGRAD) {
            int trueAt = int(prog[w + 1]), falseAt = int(prog[w + 2]);
            uint seedWord = prog[w + 3];  // its positional factory's (lo, hi) in the program
            if (c.y <= trueAt) res = true;
            else if (c.y >= falseAt) res = false;
            else {
                double p = 1.0l + divI(c.y - trueAt, falseAt - trueAt) * (0.0l - 1.0l);
                Xoro rnd = positional(seedWord, c.x, c.y, c.z);
                res = double(nextFloat(rnd)) < p;
            }
        } else if (kind == C_ABOVE_PRELIM) {
            if (!c.haveMinSurface) {
                c.minSurface = floorF(vmPoint(E_PRELIM, c.x, 0, c.z)) + c.surfaceDepth - 8;
                c.haveMinSurface = true;
            }
            res = c.y >= c.minSurface;
        } else if (kind == C_BIOME) {
            if (!c.haveBiome) {
                if (c.far) g_bad = g_bad | 128u;  // (far columns come with their biomes)
                c.biome = biomeAt(c.x, c.y, c.z);
                c.haveBiome = true;
            }
            uint set = prog[w + 1];
            uint word = prog[hdr(H_BIOMESETS) + set * hdr(H_SETWORDS) + (c.biome >> 5)];
            res = ((word >> (c.biome & 31u)) & 1u) != 0u;
        } else {
            g_bad = g_bad | 32u;
        }
        return negate ? !res : res;
    }
    g_bad = g_bad | 32u;
    return false;
}

// OreVeinRule.tryApply: -1 when it does not apply
int oreVein(uint vein, inout SurfaceCtx c) {
    uint w = hdr(H_ORES) + vein * 8u;
    InterpCtx ic;
    ic.base = cornerBase(c.ci); ic.lx = c.lx; ic.ly = c.y - MIN_Y; ic.lz = c.lz; ic.valid = true;
    float o[16];
    for (int k = 0; k < 16; k++) o[k] = 0.0f;
    vmRun(prog[w], c.x, c.y, c.z, ic, o);
    float density = o[0];
    if (density <= 0.0f) return -1;
    Xoro rnd = positional(S_ORE_LO, c.x, c.y, c.z);
    if (nextFloat(rnd) > density) return -1;
    o[0] = 0.0f;
    vmRun(prog[w + 1], c.x, c.y, c.z, ic, o);
    float richness = o[0];
    if (nextFloat(rnd) < richness) {
        float gap = vmPoint(prog[w + 2], c.x, c.y, c.z);
        if (gap < 0.0f) return nextFloat(rnd) < asfloat(prog[w + 6]) ? int(prog[w + 4]) : int(prog[w + 3]);
    }
    return int(prog[w + 5]);
}

int bandAt(int x, int y, int z) {
    int offset = jroundF(stackGet(hdr(S_CLAYNOISE), double(x), 0.0, double(z)) * 4.0f);
    int n = 192;
    return int(prog[hdr(H_BANDS) + uint(jrem(y + offset + n, n))]);
}

[noinline]
int runRules(inout SurfaceCtx c) {
    uint base = hdr(H_RULES);
    uint pc = hdr(S_RULESTART);
    for (int guard = 0; guard < 100000; guard++) {
        uint w = prog[base + pc];
        uint op = w & 255u, arg = w >> 8;
        if (op == R_END) return -1;
        if (op == R_TEST) {
            if (!condition(arg, c)) pc = prog[base + pc + 1];
            else pc += 2;
        } else if (op == R_BLOCK) return int(arg);
        else if (op == R_ORE) {
            if (c.far) { pc++; continue; }  // (AtlasSurface: the veins paint deep stone, never a surface)
            int v = oreVein(arg, c);
            if (v >= 0) return v;
            pc++;
        } else if (op == R_BANDS) return bandAt(c.x, c.y, c.z);
        else { g_bad = g_bad | 64u; return -1; }
    }
    g_bad = g_bad | 64u;
    return -1;
}

bool isStoneId(uint id) { uint f = blockFlags(id); return (f & (F_AIR | F_FLUID)) == 0u; }

[shader("compute")]
[numthreads(64, 1, 1)]
void surfaceMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, col = id.x;
    if (col >= 256u || ci >= uint(params.chunks)) return;
    if (params.outMode >= 2u) return;  // debug modes: the fill's output as it is
    int cx = chunkList[ci * 2u], cz = chunkList[ci * 2u + 1u];
    int lx = int(col & 15u), lz = int(col >> 4);
    g_bad = 0u;
    uint bb = blockBase(ci), cb = colBase(ci);
    SurfaceCtx c;
    c.ci = ci; c.lx = lx; c.lz = lz;
    c.far = false;
    c.x = cx * 16 + lx;
    c.z = cz * 16 + lz;
    int top = colInfo[cb + col];
    c.gradX = colInfo[cb + uint(lz * 16 + (lx + 1 > 15 ? 15 : lx + 1))] - colInfo[cb + uint(lz * 16 + (lx - 1 < 0 ? 0 : lx - 1))];
    c.gradZ = colInfo[cb + uint((lz + 1 > 15 ? 15 : lz + 1) * 16 + lx)] - colInfo[cb + uint((lz - 1 < 0 ? 0 : lz - 1) * 16 + lx)];
    c.surfaceDepth = surfaceDepthAt(c.x, c.z);
    c.haveSecondary = false;
    c.secondary = 0.0;
    c.haveMinSurface = false;
    c.minSurface = 0;
    int stoneAbove = 0;
    int waterHeight = int(0x80000000u);
    int nextCeiling = 2147483647;
    int height = top + 1;
    for (int y = height; y >= MIN_Y; y--) {
        uint old = y >= MIN_Y + NOISE_H ? 0u : blocks[bb + blockIndex(lx, y - MIN_Y, lz)];
        uint f = blockFlags(old);
        if ((f & F_AIR) != 0u) {
            stoneAbove = 0;
            waterHeight = int(0x80000000u);
        } else if ((f & F_FLUID) != 0u) {
            if (waterHeight == int(0x80000000u)) waterHeight = y + 1;
        } else {
            if (nextCeiling >= y) {
                nextCeiling = int(hdr(S_WAYBELOW));
                for (int ly = y - 1; ly >= MIN_Y - 1; ly--) {
                    bool stone = ly >= MIN_Y && isStoneId(blocks[bb + blockIndex(lx, ly - MIN_Y, lz)]);
                    if (!stone) { nextCeiling = ly + 1; break; }
                }
            }
            stoneAbove++;
            c.y = y;
            c.stoneAbove = stoneAbove;
            c.stoneBelow = y - nextCeiling + 1;
            c.waterHeight = waterHeight;
            c.haveBiome = false;
            int st = runRules(c);
            if (st >= 0) blocks[bb + blockIndex(lx, y - MIN_Y, lz)] = uint(st);
        }
    }
    // map water (MapWaterFeature, the lakes step): this column only
    uint ww = waterWord(c.x, c.z);
    uint kind = ww & 3u;
    if (kind != W_NONE) {
        int floor = MIN_Y;  // OCEAN_FLOOR_WG: above the highest motion-blocking block
        for (int ly = NOISE_H - 1; ly >= 0; ly--)
            if ((blockFlags(blocks[bb + blockIndex(lx, ly, lz)]) & F_MOTION) != 0u) { floor = MIN_Y + ly + 1; break; }
        int sea = int(hdr(S_SEA));
        if (kind == W_MAP) {
            int wtop = int(ww) >> 16;
            for (int y = floor; y <= wtop && y < MIN_Y + NOISE_H; y++) {
                uint i = bb + blockIndex(lx, y - MIN_Y, lz);
                uint fl = blockFlags(blocks[i]);
                if ((fl & F_AIR) != 0u || (fl & F_SOFT) != 0u) blocks[i] = hdr(S_WATER);
            }
        } else {
            int wtop = sea - 1;
            int surfTop = MIN_Y;  // WORLD_SURFACE_WG first free y
            for (int ly = NOISE_H - 1; ly >= 0; ly--)
                if ((blockFlags(blocks[bb + blockIndex(lx, ly, lz)]) & F_AIR) == 0u) { surfTop = MIN_Y + ly + 1; break; }
            if (!(floor > wtop || floor < sea - 8) && surfTop == floor) {
                for (int y = floor; y <= wtop; y++) {
                    uint i = bb + blockIndex(lx, y - MIN_Y, lz);
                    if ((blockFlags(blocks[i]) & F_AIR) != 0u) blocks[i] = hdr(S_WATER);
                }
            }
        }
    }
    flagChunk(ci, g_bad);
}

// ---- pass 6: sections out --------------------------------------------------------------------------------------
[shader("compute")]
[numthreads(64, 1, 1)]
void packMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, i = id.x;
    if (i >= OUT_WORDS || ci >= uint(params.chunks)) return;
    // word i: blocks 2i, 2i + 1 of the chunk's 40 sections (x + 16 * (z + 16 * y) within each)
    uint b0 = blocks[blockBase(ci) + i * 2u], b1 = blocks[blockBase(ci) + i * 2u + 1u];
    if (params.outMode == 1u) { b0 = blockMat(b0); b1 = blockMat(b1); }
    if (params.outMode >= 3u) {  // debug values: odd modes the high halves, even the low
        if (((params.outMode - 3u) & 1u) == 0u) { b0 = b0 >> 16; b1 = b1 >> 16; }
        else { b0 = b0 & 0xFFFFu; b1 = b1 & 0xFFFFu; }
    }
    outIds[ci * OUT_WORDS + i] = (b0 & 0xFFFFu) | (b1 << 16);
}

// ---- passes 7-9 (atlas_gen_run): the sections as the context takes them -------------------------------------------
// AtlasClientGen.decode / putGen on the GPU: a column's top (highest non-air) and ground (highest motion-blocking or
// fluid, MOTION_BLOCKING_NO_LEAVES), the chunk's lowest ground and highest top; per section air (no block), deep
// rock (all solid render, wholly 16 or more below the lowest ground: AtlasSections.deep, left to the tiles' stone) or
// blocks, and its hash (FNV-1a 64 over the far ids, seeded with the tints: AtlasEditsClient.claim's); the tints from the
// biome at the chunk's centre at the highest top (BiomeManager.getBiome); then the atlas materials, per biome.
static const uint IG_AIR = 1, IG_SOLID = 2, IG_MOTION = 4;
static const uint SEC_AIR = 0, SEC_BLOCKS = 1, SEC_ROCK = 2;
static const uint INFO_WORDS = 4 + 40 * 4;
uint igIds() { return ingestTab[0]; }
uint igFar(uint id) { return ingestTab[2 + id * 2]; }
uint igFlags(uint id) { return ingestTab[2 + id * 2 + 1]; }
uint igTint(uint biome, uint k) { return ingestTab[2 + igIds() * 2 + biome * 2 + k]; }
uint igMat(uint biome, uint id) {
    // (a biome or id past the table: no material, not another's word)
    if (id >= igIds() || biome >= ingestTab[1]) return 0u;
    return ingestTab[2 + igIds() * 2 + ingestTab[1] * 2 + biome * igIds() + id];
}

[shader("compute")]
[numthreads(64, 1, 1)]
void colsMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, c = id.x;
    if (c >= 256u || ci >= uint(params.chunks)) return;
    int lx = int(c & 15u), lz = int(c >> 4);
    int top = -65, ground = -65;
    for (int ly = NOISE_H - 1; ly >= 0 && (top == -65 || ground == -65); ly--) {
        uint f = igFlags(blocks[blockBase(ci) + blockIndex(lx, ly, lz)]);
        if (top == -65 && (f & IG_AIR) == 0u) top = MIN_Y + ly;
        if (ground == -65 && (f & IG_MOTION) != 0u) ground = MIN_Y + ly;
    }
    // (the surface pass is done with the heights: their words take the column's top and ground)
    colInfo[colBase(ci) + c] = int(uint(top + 1024) | (uint(ground + 1024) << 16));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void secsMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, sec = id.x;
    if (sec >= 40u || ci >= uint(params.chunks)) return;
    uint info = ci * INFO_WORDS;
    int low = 1 << 20, high = -(1 << 20);
    for (uint c = 0u; c < 256u; c++) {
        uint w = uint(colInfo[colBase(ci) + c]);
        int t = int(w & 0xFFFFu) - 1024, g = int(w >> 16) - 1024;
        if (t > high) high = t;  // (no min / max: extended instructions are refused)
        if (g < low) low = g;
    }
    if (sec == 0u) {
        secInfo[info] = uint(low + 1024);
        secInfo[info + 1] = ~uint(high + 1024);
    }
    int cx = chunkList[ci * 2], cz = chunkList[ci * 2 + 1];
    g_bad = 0u;
    uint biome = biomeAt(cx * 16 + 8, high, cz * 16 + 8);
    if (sec == 0u) secInfo[info + 2] = biome;
    uint64_t h = (uint64_t(0xCBF29CE4u) << 32) | uint64_t(0x84222325u);
    // seed ^ ((long) grass << 24 ^ foliage)
    h = h ^ ((uint64_t(igTint(biome, 0u)) << 24) ^ uint64_t(igTint(biome, 1u)));
    // what the section holds (any block; all solid render), stopping as soon as both are known
    bool any = false, rock = true;
    uint base = blockBase(ci) + sec * 4096u;
    for (uint b = 0u; b < 4096u && (rock || !any); b++) {
        uint g = blocks[base + b];
        any = any || g != 0u;
        rock = rock && (igFlags(g) & IG_SOLID) != 0u;
    }
    int sy0 = int(sec) - 4;
    bool deep = any && rock && sy0 * 16 + 16 <= low - 16;
    // the hash (not for deep rock: it never goes up). The FNV prime 0x100000001B3 = 2^40 + 435: h * it as
    // (h << 40) + h * 435 (mod 2^64); air (every far id 0) is the seed * prime^4096 = 0x62231D84A3104001
    if (!any && igFar(0u) == 0u) {
        h = h * ((uint64_t(0x62231D84u) << 32) | uint64_t(0xA3104001u));
    } else if (!deep) {
        for (uint b = 0u; b < 4096u; b++) {
            h = h ^ uint64_t(igFar(blocks[base + b]));
            h = (h << 40) + h * uint64_t(0x1B3u);
        }
    } else {
        h = 0ull;
    }
    uint kind = !any ? SEC_AIR : (deep ? SEC_ROCK : SEC_BLOCKS);
    uint o = info + 4u + sec * 4u;
    secInfo[o] = uint(h & 0xFFFFFFFFull);
    secInfo[o + 1u] = uint(h >> 32);
    secInfo[o + 2u] = kind;
    flagChunk(ci, g_bad);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void matsMain(uint3 id : SV_DispatchThreadID) {
    uint ci = id.y, i = id.x;
    if (i >= OUT_WORDS || ci >= uint(params.chunks)) return;
    uint biome = secInfo[ci * INFO_WORDS + 2];
    uint b0 = blocks[blockBase(ci) + i * 2u], b1 = blocks[blockBase(ci) + i * 2u + 1u];
    outIds[ci * OUT_WORDS + i] = (igMat(biome, b0) & 0xFFFFu) | (igMat(biome, b1) << 16);
}

// ---- probes, checked against Java (atlas_gen_probe): pageIndex holds n (x, z) pairs, outIds PROBE_WORDS per probe ----
// outMode 0: TamrielMap.sample (MapS in declaration order, inTamriel 0 / 1), then the flag
static const uint PROBE_WORDS = 32;
[shader("compute")]
[numthreads(64, 1, 1)]
void probeMain(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= uint(params.chunks)) return;
    int x = pageIndex[i * 2], z = pageIndex[i * 2 + 1];
    g_bad = 0u;
    uint o = i * PROBE_WORDS;
    for (uint k = 0u; k < PROBE_WORDS; k++) outIds[o + k] = 0u;
    if (params.outMode == 0u) {
        MapS s = tamrielSample(x, z);
        outIds[o] = uint(s.region);
        outIds[o + 1] = uint(s.blendRegion);
        outIds[o + 2] = asuint(s.blendWeight);
        outIds[o + 3] = asuint(s.elevation);
        outIds[o + 4] = asuint(s.ridge);
        outIds[o + 5] = asuint(s.river);
        outIds[o + 6] = asuint(s.lake);
        outIds[o + 7] = asuint(s.coastDistance);
        outIds[o + 8] = asuint(s.waterLevel);
        outIds[o + 9] = asuint(s.riverDistance);
        outIds[o + 10] = asuint(s.riverHalfWidth);
        outIds[o + 11] = asuint(s.lakeDistance);
        outIds[o + 12] = asuint(s.roughness);
        outIds[o + 13] = asuint(s.crag);
        outIds[o + 14] = asuint(s.dunes);
        outIds[o + 15] = asuint(s.wetland);
        outIds[o + 16] = asuint(s.road);
        outIds[o + 17] = s.inTamriel ? 1u : 0u;
        outIds[o + 18] = asuint(s.peak);
        outIds[o + 19] = asuint(s.calm);
        outIds[o + 20] = asuint(s.slope);
        outIds[o + 21] = asuint(s.rainshadow);
        outIds[o + 22] = asuint(s.heat);
        outIds[o + 23] = asuint(s.aspectX);
        outIds[o + 24] = asuint(s.aspectZ);
    } else if (params.outMode == 1u) {
        // the overhang channel's parts (atlas_page.slang): its map value (double), PlanGround's influence and weights
        MapS s = tamrielSample(x, z);
        double base = channelValue(CHN_OVERHANG, x, z, s);
        Bed bd;
        bedClear(bd);
        float wb = planBed(x, z, bd) ? bd.weight : -1.0f;
        float wp = planPad(x, z, bd) ? bd.padWeight : -1.0f;
        float wq = streetPad(x, z, bd) ? bd.plotWeight : -1.0f;
        float inf = planInfluence(x, z);
        double road = channelValue(CHN_ROAD, x, z, s);
        outIds[o] = uint(loW(base));
        outIds[o + 1] = uint(hiW(base));
        outIds[o + 2] = asuint(inf);
        outIds[o + 3] = asuint(wb);
        outIds[o + 4] = asuint(wp);
        outIds[o + 5] = asuint(wq);
        outIds[o + 6] = uint(loW(road));
        outIds[o + 7] = uint(hiW(road));
        double h = tamrielHeight(x, z, s);
        outIds[o + 8] = uint(loW(h));
        outIds[o + 9] = uint(hiW(h));
    }
    outIds[o + PROBE_WORDS - 1u] = g_bad;
}

// ---- the exact math, checked against Java (atlas_gen_math) --------------------------------------------------------
// prog holds n argument pairs (a, b as double bits), outIds n results (bits) and flags; params.outMode is the op
[shader("compute")]
[numthreads(64, 1, 1)]
void mathMain(uint3 id : SV_DispatchThreadID) {
    uint i = id.y * 65536u * 64u + id.x;
    if (i >= uint(params.chunks)) return;
    double a = asdouble(prog[i * 4u], prog[i * 4u + 1u]), b = asdouble(prog[i * 4u + 2u], prog[i * 4u + 3u]);
    g_bad = 0u;
    double r = 0.0l;
    switch (params.outMode) {
        case 0u: r = divD(a, b); break;
        case 1u: r = sqrtD(a); break;
        case 2u: r = sinS(a); break;
        case 3u: r = cosS(a); break;
        case 4u: r = expS(a); break;
        case 5u: r = powS(a, b); break;
        case 6u: r = atan2S(a, b); break;
        case 7u: r = hypotS(a, b); break;
        case 8u: r = atanS(a); break;
        default: g_bad = 2u; break;
    }
    outIds[i * 3u] = uint(loW(r));
    outIds[i * 3u + 1u] = uint(hiW(r));
    outIds[i * 3u + 2u] = g_bad;
}
