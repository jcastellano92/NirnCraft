// The generator's chunk store (gen_engine.cpp, atlas.h atlas_gen_store_*): chunks waiting on the device until their
// neighbourhood is ready for each store stage. Two passes move a chunk between the generator's batch buffers and its
// store slot:
//   storeInMain   a batch chunk's blocks (just made: terrain, surface, map water) into its slot: the terrain as made
//                 (two 16-bit ids a word), the working blocks (one 32-bit word a block: priority << 16 | id, priority 0),
//                 its quart biomes (from its page) and per column the map's region index and elevation (TamrielMap.sample,
//                 atlas_map.slang: TamrielMap.regionIndex is the sample's region)
//   storeOutMain  a slot's working blocks back into the batch's blocks (the low 16 bits) for the pack and the ingest
// Block b = x + 16 * (z + 16 * (y + 64)) as atlas_gen.slang's blocks.

[[vk::binding(0, 0)]] RWStructuredBuffer<uint> genBlocks;   // the batch's: per chunk BLOCK_WORDS
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> terrain;     // per slot TERRAIN_WORDS
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> blocks;      // per slot BLOCK_WORDS
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> aux;         // per slot AUX_WORDS
[[vk::binding(4, 0)]] StructuredBuffer<int> list;           // per item: batch chunk, slot, cx, cz
[[vk::binding(5, 0)]] StructuredBuffer<uint> pages;
[[vk::binding(6, 0)]] StructuredBuffer<int> pageIndex;
[[vk::binding(7, 0)]] StructuredBuffer<uint> mapData;      // the map (AtlasGenMap)

#include "atlas_exact.slang"
// (atlas_map.slang's helpers, as atlas_gen.slang has them)
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

#include "atlas_map.slang"

struct StoreParams {
    int count;
    int pageMinCx, pageMinCz, pageW, pageH;
    uint pageWords;
    uint quartOffset;  // the quart biomes' offset in a page (fields * 256 + 256)
    uint pad;
};
[[vk::push_constant]] StoreParams params;

static const uint BLOCK_WORDS = 16u * 16u * 640u, TERRAIN_WORDS = BLOCK_WORDS / 2u, AUX_WORDS = 2048u, QUART_WORDS = 1408u;
// the aux words: [0, 1408) the quart biomes, [1408, 1664) each column's region index, [1664, 1920) its elevation (float)
static const uint AUX_REGION = 1408u, AUX_ELEV = 1664u;

[shader("compute")]
[numthreads(64, 1, 1)]
void storeInMain(uint3 id : SV_DispatchThreadID) {
    uint item = id.y, i = id.x;
    if (item >= uint(params.count) || i >= TERRAIN_WORDS) return;
    uint ci = uint(list[item * 4u]), slot = uint(list[item * 4u + 1u]);
    uint b0 = genBlocks[ci * BLOCK_WORDS + i * 2u] & 0xFFFFu, b1 = genBlocks[ci * BLOCK_WORDS + i * 2u + 1u] & 0xFFFFu;
    terrain[slot * TERRAIN_WORDS + i] = b0 | (b1 << 16);
    blocks[slot * BLOCK_WORDS + i * 2u] = b0;
    blocks[slot * BLOCK_WORDS + i * 2u + 1u] = b1;
    if (i < AUX_WORDS) {
        uint v = 0u;
        int cx = list[item * 4u + 2u], cz = list[item * 4u + 3u];
        if (i < QUART_WORDS) {
            int px = cx - params.pageMinCx, pz = cz - params.pageMinCz;
            int ps = (px < 0 || pz < 0 || px >= params.pageW || pz >= params.pageH) ? -1 : pageIndex[pz * params.pageW + px];
            if (ps >= 0) v = pages[uint(ps) * params.pageWords + params.quartOffset + i];
        } else if (i < AUX_ELEV + 256u) {
            uint c = (i - AUX_REGION) & 255u;
            MapS ms = tamrielSample(cx * 16 + int(c & 15u), cz * 16 + int(c >> 4));
            v = i < AUX_ELEV ? uint(ms.region) : asuint(ms.elevation);
        }
        aux[slot * AUX_WORDS + i] = v;
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void storeOutMain(uint3 id : SV_DispatchThreadID) {
    uint item = id.y, i = id.x;
    if (item >= uint(params.count) || i >= BLOCK_WORDS) return;
    uint ci = uint(list[item * 4u]), slot = uint(list[item * 4u + 1u]);
    genBlocks[ci * BLOCK_WORDS + i] = blocks[slot * BLOCK_WORDS + i] & 0xFFFFu;
}
