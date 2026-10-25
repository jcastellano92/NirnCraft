// The client's decoration on the GPU (docs/ATLAS-CLIENT-GENERATION.md, stage 4): Minecraft 26.3's
// ChunkGenerator.applyBiomeDecoration over the generated terrain, with the world's own placed features compiled by the
// mod (AtlasFeatureProgram: placements, configs, block tables, RNG seeds), the same decoration and feature seeds, the
// same random number streams (WorldgenRandom over Xoroshiro128++), so a feature lands where Java's lands, block for
// block. Same exact float contract as atlas_gen.slang (-fp-mode precise + spv_exact; divisions and square roots
// correctly rounded through atlas_exact.slang; the transcendentals are fdlibm's).
//
// Order (the gather formulation): every chunk of the batch is an "origin" decorated in isolation (its 3 x 3 terrain
// plus its own earlier writes, exactly what Java's decoration sees when no neighbour was decorated before it), its
// writes kept in its own table (featMain, one dispatch per (step, feature) in Java's order, one thread per origin).
// A target chunk's final blocks are its terrain with the writes of its 3 x 3 origins applied in a fixed order (origins
// z-major then x, ascending: the order AtlasGenParityTest decorates a 3 x 3 in), the later origin winning
// (copyMain, then applyMain once per neighbour slot). No two threads ever write one block in one dispatch.
//
// Kernels (thread .x = item, .y = origin / target where noted):
//   hmMain      per terrain slot and column: the six heightmaps of the terrain (Heightmap.primeHeightmaps)
//   initMain    per origin: its tables cleared, the final heightmaps of its 3 x 3 copied, the biomes it holds
//   featMain    per origin: one placed feature (params.rec) with its placements, if a biome of its 3 x 3 lists it
//   copyMain    per target and word: the terrain ids
//   applyMain   per origin table slot (.y = target): the writes of the target's neighbour params.k landing in it

struct FeatParams {
    int origins;   // origins in this batch
    int targets;   // targets in this batch
    uint rec;      // featMain: the feature record (fprog word offset)
    uint k;        // applyMain: the neighbour slot (0..8, z-major)
    int slots;     // terrain slots (hmMain)
    uint first;    // featMain: 1 the feature's first dispatch (seed, start), 0 a later one (resume)
    uint budget;   // featMain: placements a thread makes in this dispatch (the rest the next: the GPU watchdog)
    uint store;    // 1: the generator's chunk store (atlas.h atlas_gen_add_store_stage): chunkIds its terrain (generator
                   // ids, STORE_TERRAIN_W a slot, y -64..575), outIds its blocks, outWide its aux, targets a slot list;
                   // | 2: an ore vein's blocks decided by featVeinMain on many threads (VEIN_*), applied the next round
};
[[vk::push_constant]] ConstantBuffer<FeatParams> params;
[[vk::binding(0, 0)]] StructuredBuffer<uint> fprog;
[[vk::binding(1, 0)]] StructuredBuffer<uint> chunkIds;    // per terrain slot CHUNK_W words: u16 ids, b = x + 16 z + 256 (y + 64)
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> chunkAux;  // per terrain slot AUX_W words: quart biomes, mask, heightmaps
[[vk::binding(3, 0)]] StructuredBuffer<int> origins;      // per origin ORIGIN_W ints: cx, cz, the 5 x 5 terrain slots (-1 none)
[[vk::binding(4, 0)]] RWStructuredBuffer<uint> scratch;   // per origin SCR_W words
[[vk::binding(5, 0)]] RWStructuredBuffer<uint> outIds;    // per target CHUNK_W words
[[vk::binding(6, 0)]] StructuredBuffer<int> targets;      // per target TARGET_W ints: own terrain slot, the 9 origins (-1 none)
[[vk::binding(7, 0)]] RWStructuredBuffer<uint> outWide;
[[vk::binding(9, 0)]] StructuredBuffer<uint> mapData;     // (the store) the generator's map words (AtlasGenMap)
[[vk::binding(10, 0)]] StructuredBuffer<uint> ovlTapes;   // (the store) the overlays' tapes (atlas_ovl_store_boxes)
[[vk::binding(11, 0)]] StructuredBuffer<int> ovlRecords;  // (the store) each store slot's tape record, -1 none
[[vk::binding(12, 0)]] RWStructuredBuffer<uint> farPlanes; // (the far stage) the generator's far tiles' planes (AtlasGenFarBatch)
[[vk::binding(8, 0)]] RWStructuredBuffer<uint> counters;  // [0] origins with placements left after this dispatch   // per target WIDE_W words: one id a block (the gather, then packed)

#include "atlas_feat_math.slang"  // its own copy of the exact math (not atlas_exact.slang: see there)

// (dev: CMake ATLAS_FEAT_CLOCK, slangc -DFEAT_CLOCK=1; the device needs VK_KHR_shader_clock: ATLAS_FEAT_CLOCK=1) the
// lane's clock around a part of the work: counters[16 + k] the spans' sum, counters[48 + k] the longest, in units of
// 64 cycles (features_engine.cpp prints them per record with ATLAS_FEAT_PART_TRACE). Off: the macros are empty.
#ifndef FEAT_CLOCK
#define FEAT_CLOCK 0
#endif
#if FEAT_CLOCK
[ForceInline]
void clkAdd(uint k, uint64_t t0) {
    uint64_t d = (clockARB() - t0) >> 6;
    uint v = d > 0xFFFFFFFFull ? 0xFFFFFFFFu : uint(d);
    InterlockedAdd(counters[16u + k], v);
    InterlockedMax(counters[48u + k], v);
}
#define CLK0(v) uint64_t v = clockARB()
#define CLK1(k, v) clkAdd(k, v)
#define CLKN(k) InterlockedAdd(counters[16u + (k)], 1u)
#else
#define CLK0(v)
#define CLK1(k, v)
#define CLKN(k)
#endif

// the exact functions without their flag argument: a call that passed a thread-private flag word itself made Slang
// clone the callee for every call site (megabytes of SPIR-V), so the flag goes through a local
[ForceInline]
uint arraySize_X(uint expected) {
    uint lb = 0u;
    uint v = arraySize(expected, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double atan2S_X(double y, double x) {
    uint lb = 0u;
    double v = atan2S(y, x, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
int ceilD_X(double d) {
    uint lb = 0u;
    int v = ceilD(d, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
int ceilF_X(float f) {
    uint lb = 0u;
    int v = ceilF(f, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double cosS_X(double x) {
    uint lb = 0u;
    double v = cosS(x, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double divD_X(double a, double b) {
    uint lb = 0u;
    double v = divD(a, b, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
float divF_X(float a, float b) {
    uint lb = 0u;
    float v = divF(a, b, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double expS_X(double x) {
    uint lb = 0u;
    double v = expS(x, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double fbm_X(double x, double z, int64_t seed, int octaves) {
    uint lb = 0u;
    double v = fbm(x, z, seed, octaves, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double fieldSample_X(uint f, int64_t worldSeed, double x, double z) {
    uint lb = 0u;
    double v = fieldSample(f, worldSeed, x, z, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
int floorD_X(double d) {
    uint lb = 0u;
    int v = floorD(d, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double hypotS_X(double x, double y) {
    uint lb = 0u;
    double v = hypotS(x, y, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double powD_X(double a, double b) {
    uint lb = 0u;
    double v = powD(a, b, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double sinS_X(double x) {
    uint lb = 0u;
    double v = sinS(x, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double sqrtD_X(double x) {
    uint lb = 0u;
    double v = sqrtD(x, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double valueNoise_X(double x, double z, int64_t seed) {
    uint lb = 0u;
    double v = valueNoise(x, z, seed, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}
[ForceInline]
double wrapDegrees_X(double a) {
    uint lb = 0u;
    double v = wrapDegrees(a, lb);
    if (lb != 0u) flag(FL_BAD_MATH);
    return v;
}

// the same without the flag, [noinline] (pure: NVIDIA keeps their calls apart then), for the trees' code: inlined at
// its hundreds of call sites the driver's compile of the tree kernel took minutes
[noinline]
uint arraySize_N(uint expected) {
    uint lb = 0u;
    return arraySize(expected, lb);
}
[noinline]
double atan2S_N(double y, double x) {
    uint lb = 0u;
    return atan2S(y, x, lb);
}
[noinline]
int ceilD_N(double d) {
    uint lb = 0u;
    return ceilD(d, lb);
}
[noinline]
int ceilF_N(float f) {
    uint lb = 0u;
    return ceilF(f, lb);
}
[noinline]
double cosS_N(double x) {
    uint lb = 0u;
    return cosS(x, lb);
}
[noinline]
double divD_N(double a, double b) {
    uint lb = 0u;
    return divD(a, b, lb);
}
[noinline]
float divF_N(float a, float b) {
    uint lb = 0u;
    return divF(a, b, lb);
}
[noinline]
double expS_N(double x) {
    uint lb = 0u;
    return expS(x, lb);
}
[noinline]
double fbm_N(double x, double z, int64_t seed, int octaves) {
    uint lb = 0u;
    return fbm(x, z, seed, octaves, lb);
}
[noinline]
double fieldSample_N(uint f, int64_t worldSeed, double x, double z) {
    uint lb = 0u;
    return fieldSample(f, worldSeed, x, z, lb);
}
[noinline]
int floorD_N(double d) {
    uint lb = 0u;
    return floorD(d, lb);
}
[noinline]
double hypotS_N(double x, double y) {
    uint lb = 0u;
    return hypotS(x, y, lb);
}
[noinline]
double powD_N(double a, double b) {
    uint lb = 0u;
    return powD(a, b, lb);
}
[noinline]
double sinS_N(double x) {
    uint lb = 0u;
    return sinS(x, lb);
}
[noinline]
double sqrtD_N(double x) {
    uint lb = 0u;
    return sqrtD(x, lb);
}
[noinline]
double valueNoise_N(double x, double z, int64_t seed) {
    uint lb = 0u;
    return valueNoise(x, z, seed, lb);
}
[noinline]
double wrapDegrees_N(double a) {
    uint lb = 0u;
    return wrapDegrees(a, lb);
}

// ---- layout (AtlasFeatureProgram and features_engine.cpp keep in step) ------------------------------------------
static const int MIN_Y = -64, HEIGHT = 704, MAX_Y = 639;  // the overworld: y -64..639 (44 sections)
static const uint SECTIONS = 44;
static const uint CHUNK_W = 44 * 2048, WIDE_W = 44 * 4096;
static const uint QY = 176;                          // quarts -16..159
static const uint AUX_QUARTS = 0, AUX_MASK = 1408, AUX_ALCH = 1472, AUX_TEMP = 1664, AUX_FROZEN = 1920, AUX_HM = 1928,
                  AUX_W = 1928 + 6 * 256;
// aux: quart biomes (u16), the placement mask (bytes), three alchemy class bytes a column (placeCover), the temperature
// noise (float a column) and the frozen modifier's bits (placeFreeze), then the heightmaps hmMain computes
static const uint ORIGIN_W = 28;                     // cx, cz, 25 slots, pad
static const uint TARGET_W = 12;                     // own slot, 9 origins, cx, cz
// scratch per origin: meta, the write table, the final heightmaps of the 3 x 3, the biomes held, the ore tested marks,
// the trees' working sets (atlas_features_trees)
static const uint HT_CAP = 32768;                    // write table slots (power of two)
static const uint SCR_META = 0, SCR_HT = 16, SCR_HM = 16 + HT_CAP * 2, SCR_BIOMES = SCR_HM + 4 * 2304,
                  SCR_TESTED = SCR_BIOMES + 8, SCR_TREE = SCR_TESTED + 12288;
static const uint TREE_W = 272448;                   // atlas_features trees: T_LAST + 8
// SCR_MISC: the trunk line (2 x 80 doubles), the ore blob's spheres (64 x 4 doubles), the placement stack (288 x 4
// ints) and one modifier's outputs (288 x 3 ints): kept out of registers (large local arrays blow the SPIR-V up)
static const uint SCR_MISC = SCR_TREE + TREE_W, M_LINE = 0, M_ORE = 320, M_STACK = 320 + 512, M_OUT = 320 + 512 + 1152,
                  M_LAKE = 320 + 512 + 1152 + 864, M_DBG = M_LAKE + 64, MISC_W = M_DBG + 1024;  // M_LAKE: the lake's 2048 bits;
// M_DBG: the trees' trace (dev; count, then 6 words an entry: stage, x, y, z, draws, extra)
static const uint SCR_W = SCR_MISC + MISC_W;
// meta words: [0] table entries, [1] flags (FL_*), [2] ore stamp, [3] writes, [4] reads outside the 5 x 5, [6..9] the
// random, [10] the feature's state (0 / 1 more to place / 2 done), [11] random draws (next() calls) so far
static const uint FL_TABLE_FULL = 1, FL_OUTSIDE = 2, FL_UNSUPPORTED = 4, FL_STACK = 8, FL_BAD_MATH = 16;

// fprog header
static const uint MAGIC = 0x41454641u;  // "AFEA"
// the store's tables (features_engine.cpp appends them): generator id -> feature id, feature id -> generator id (0: the
// same ids); H_NGEN the generator ids the first holds
static const uint H_GEN2FEAT = 24, H_FEAT2GEN = 25, H_NGEN = 26;
// the store's layout (atlas.h ATLAS_GEN_STORE_*)
static const uint STORE_TERRAIN_W = 16u * 16u * 640u / 2u, STORE_BLOCK_W = 16u * 16u * 640u, STORE_AUX_W = 2048u,
                  STORE_BLOCKS = 16u * 16u * 640u;
static const uint H_VERSION = 1, H_NBLOCKS = 2, H_BLOCKS = 3, H_NBIOMES = 4, H_BIOMEWORDS = 5, H_SEED = 6, H_ZOOM = 8,
                  H_SEA = 10, H_SIN = 11, H_FIELDS = 12, H_PREDS = 13, H_SURVIVE = 14, H_SURVIVE_N = 15,
                  H_CONSTS = 16, H_GENMINY = 17, H_GENHEIGHT = 18, H_DISPATCH = 19,
                  H_NDISPATCH = 20, H_ALCH = 21, H_TREES = 22, H_BIOMETAB = 23;
static const uint BLK_W = 16;  // per block id: flags, flags2, then transforms (see T_*)
static const uint F2_LIQUID = 1u, F2_SNOWY = 2u, F2_LIQUID_PROP = 4u;  // .. state.liquid()  // flags2: a LiquidBlock; has the snowy property
// block flags (word 0)
static const uint B_AIR = 1u << 0, B_WATER = 1u << 1, B_LAVA = 1u << 2, B_SOURCE = 1u << 3, B_MOTION = 1u << 4,
                  B_LEAVES_TAG = 1u << 5, B_LEAVES_BLOCK = 1u << 6, B_LOGS = 1u << 7, B_SOFT = 1u << 8,
                  B_SUBSTRATE = 1u << 9, B_SAND = 1u << 10, B_CANOPY = 1u << 11, B_STURDY_UP = 1u << 12,
                  B_SOLID = 1u << 13, B_GROUND = 1u << 14, B_REPLACEABLE = 1u << 15, B_FLUID = 1u << 16,
                  B_WATERLOGGABLE = 1u << 17, B_SNOW_LAYER = 1u << 18, B_SOIL_DIRT = 1u << 19, B_SOIL_SAND = 1u << 20,
                  B_SOIL_STONE = 1u << 21, B_SOIL_ASH = 1u << 22, B_SOIL_SNOW = 1u << 23, B_BAMBOO_SOIL = 1u << 24,
                  B_DOUBLE_PLANT = 1u << 25, B_STONE_ORE = 1u << 26, B_DEEPSLATE_ORE = 1u << 27,
                  B_SURVIVE_SPECIAL = 1u << 28, B_FLUID_WATER_TAG = 1u << 29,
                  B_MOTION_NL = 1u << 30, B_WATER_BLOCK = 1u << 31;  // B_MOTION / B_MOTION_NL: the heightmap tags blocks_motion_in_heightmap(_no_leaves)
// feature record: type, step, gi, mods, modsOffset, cfgOffset, biomesHere offset, biomesAny offset
static const uint R_TYPE = 0, R_STEP = 1, R_GI = 2, R_NMODS = 3, R_MODS = 4, R_CFG = 5, R_HERE = 6, R_ANY = 7;
static const uint FT_NONE = 0, FT_ORE = 1, FT_COVER = 2, FT_ROCK = 3, FT_TREE = 4, FT_FREEZE = 5, FT_DISK = 6, FT_SPRING = 7, FT_LAKE = 8, FT_TYPES = 9;
// placement modifiers, MOD_W words each: kind, then arguments
static const uint MOD_W = 16;
static const uint M_COUNT = 1, M_IN_SQUARE = 2, M_HEIGHTMAP = 3, M_BIOME = 4, M_RARITY = 5, M_HEIGHT_RANGE = 6,
                  M_FLORA_SCATTER = 7, M_FLORA_DENSITY = 8, M_MASK = 9, M_SURF_REL = 10, M_ENV_SCAN = 11,
                  M_OFFSET = 12, M_BLOCK_PRED = 13;
// int providers (4 words): kind, a, b, c
static const uint IP_CONST = 0, IP_UNIFORM = 1, IP_BIASED = 2, IP_TRAPEZOID = 3, IP_CLAMPED_UNIFORM = 4;
// height providers: kind, min, max, plateau / inner
static const uint HP_CONST = 0, HP_UNIFORM = 1, HP_TRAPEZOID = 2, HP_BIASED = 3, HP_VERY_BIASED = 4;
// heightmap types (Heightmap.Types ordinals)
static const uint HM_WORLD_SURFACE_WG = 0, HM_WORLD_SURFACE = 1, HM_OCEAN_FLOOR_WG = 2, HM_OCEAN_FLOOR = 3,
                  HM_MOTION_BLOCKING = 4, HM_MOTION_BLOCKING_NO_LEAVES = 5;

uint hdr(uint i) { return fprog[i]; }
double scrD(uint at) { return asdouble(scratch[at], scratch[at + 1]); }
[noinline]
void scrSetD(uint at, double v) {
    uint lo, hi;
    asuint(v, lo, hi);
    scratch[at] = lo;
    scratch[at + 1] = hi;
}
float pfF(uint w) { return asfloat(fprog[w]); }
double pfD(uint w) { return asdouble(fprog[w], fprog[w + 1]); }
uint64_t pfU64(uint w) { return (uint64_t(fprog[w + 1]) << 32) | uint64_t(fprog[w]); }
uint bflags(uint id) { return fprog[hdr(H_BLOCKS) + id * BLK_W]; }
uint bflags2(uint id) { return fprog[hdr(H_BLOCKS) + id * BLK_W + 1]; }
uint btrans(uint id, uint t) { return fprog[hdr(H_BLOCKS) + id * BLK_W + 2 + t]; }
bool bis(uint id, uint f) { return (bflags(id) & f) != 0u; }
static const uint AIR = 0u;

// ---- exact helpers (as atlas_gen.slang) ------------------------------------------------------------------------
float absF(float f) { return asfloat(asuint(f) & 0x7FFFFFFFu); }
double absD(double d) { return d < 0.0l ? -d : (d == 0.0l ? 0.0l : d); }
// (int) Math.floor(d) for |d| < 2^31
[noinline]
int floorD(double d, inout uint bad) {
    if (!(d > -2147483647.0l && d < 2147483647.0l)) { bad = 1u; return 0; }
    int i = int(d);
    if (double(i) > d) i = i - 1;
    return i;
}
[noinline]
int ceilD(double d, inout uint bad) {
    if (!(d > -2147483647.0l && d < 2147483647.0l)) { bad = 1u; return 0; }
    int i = int(d);
    if (double(i) < d) i = i + 1;
    return i;
}
int floorF(float f, inout uint bad) { return floorD(double(f), bad); }
int ceilF(float f, inout uint bad) { return ceilD(double(f), bad); }
// (int) of a double (Java: NaN 0, saturating)
int d2i(double d) {
    if (d != d) return 0;
    if (d >= 2147483647.0l) return 2147483647;
    if (d <= -2147483648.0l) return int(0x80000000u);
    return int(d);
}
// (long) of a double for |d| < 2^62
int64_t d2l(double d) {
    if (d != d) return 0;
    return int64_t(d);
}
// float division correctly rounded (atlas_gen.slang's divF)
[noinline]
float divF(float a, float b, inout uint bad) {
    if (a != a || b != b) return asfloat(0x7FC00000u);
    float q = float(double(a) / double(b));
    if (b == 0.0f || a == 0.0f || absF(q) > 3.0e38f || absF(q) < 1.0e-36f) {
        if (b == 0.0f) {
            if (a == 0.0f) return asfloat(0x7FC00000u);
            return asfloat(((asuint(a) ^ asuint(b)) & 0x80000000u) | 0x7F800000u);
        }
        if (a == 0.0f) return asfloat((asuint(a) ^ asuint(b)) & 0x80000000u);
        bad = 1u;
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
// (float) of a long (round to nearest even), any sign
[ForceInline]
float l2f(int64_t sv) {
    bool neg = sv < 0;
    uint64_t v = neg ? uint64_t(-sv) : uint64_t(sv);
    float f;
    if (v < (uint64_t(1) << 24)) f = float(uint(v));
    else {
        int sh = 0;
        uint64_t t = v;
        while (t >= (uint64_t(1) << 24)) { t = t >> 1; sh++; }
        uint64_t rest = v - (t << uint(sh));
        uint64_t half = uint64_t(1) << uint(sh - 1);
        if (rest > half || (rest == half && (t & 1u) != 0u)) t = t + 1u;
        if (t == (uint64_t(1) << 24)) { t = t >> 1; sh++; }
        f = float(uint(t)) * asfloat(uint(127 + sh) << 23);
    }
    return neg ? -f : f;
}
// Math.round(float)
[noinline]
int jroundF(float a) {
    int bits = int(asuint(a));
    int biasedExp = (bits & 0x7F800000) >> 23;
    int shift = (24 - 2 + 127) - biasedExp;
    if ((shift & -32) == 0) {
        int r = (bits & 0x007FFFFF) | 0x00800000;
        if (bits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    if (a != a) return 0;
    if (a >= 2147483647.0f) return 2147483647;
    if (a <= -2147483648.0f) return int(0x80000000u);
    return int(a);
}
// Math.round(double) for |a| < 2^62: floor(a + 0.5) computed exactly (Java's bit version gives the same)
int64_t jroundD(double a) {
    int64_t bits = int64_t((uint64_t(uint(hiW(a))) << 32) | uint64_t(uint(loW(a))));
    int64_t biasedExp = (bits & int64_t(0x7FF0000000000000l)) >> 52;
    int64_t shift = (52 - 1 + 1023) - biasedExp;
    if ((shift & int64_t(-64)) == 0) {
        int64_t r = (bits & int64_t(0x000FFFFFFFFFFFFFl)) | int64_t(0x0010000000000000l);
        if (bits < 0) r = -r;
        return ((r >> uint(shift)) + 1) >> 1;
    }
    if (a != a) return 0;
    return int64_t(a);
}
int signumI(int v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }
int floorDivI(int a, int b) { return a >= 0 ? int(uint(a) / uint(b)) : -int((uint(-(a + 1)) / uint(b)) + 1u); }
int imin(int a, int b) { return a < b ? a : b; }
int imax(int a, int b) { return a > b ? a : b; }
int iabs(int a) { return a < 0 ? -a : a; }
double dmin(double a, double b) { return (a != a) ? a : (a <= b ? (a == 0.0l && b == 0.0l && hiW(b) < 0 ? b : a) : b); }
double dmax(double a, double b) { return (a != a) ? a : (a >= b ? (a == 0.0l && b == 0.0l && hiW(a) < 0 ? b : a) : b); }

// ---- random: WorldgenRandom over XoroshiroRandomSource (BitRandomSource's methods), and LegacyRandomSource -------
// The feature's random (WorldgenRandom over Xoroshiro) lives in the origin's scratch meta words (sRA: lo low, lo high,
// hi low, hi high), not in a thread-private static: NVIDIA's compiler took the [noinline] random functions writing a
// Private variable for side-effect free and re-evaluated or merged their calls (ore's two nextInt(3) drew three
// times). A storage buffer write it must keep. It also carries the random from one dispatch to the next.
static uint sRA;
// the trees' trace (dev): each placement's stages and marks with the draws so far into M_DBG, against the Java
// reference's (FloraTreeFeature.trace, the dump's .trees.txt; tools: treecmp.py). Off: it costs a store a draw.
static const bool TREE_TRACE = false;
[ForceInline]
uint64_t rngLo() { return (uint64_t(scratch[sRA + 1u]) << 32) | uint64_t(scratch[sRA]); }
[ForceInline]
uint64_t rngHi() { return (uint64_t(scratch[sRA + 3u]) << 32) | uint64_t(scratch[sRA + 2u]); }
[ForceInline]
void rngStore(uint64_t lo, uint64_t hi) {
    scratch[sRA] = uint(lo & 0xFFFFFFFFu);
    scratch[sRA + 1u] = uint(lo >> 32u);
    scratch[sRA + 2u] = uint(hi & 0xFFFFFFFFu);
    scratch[sRA + 3u] = uint(hi >> 32u);
}
uint64_t rotl(uint64_t x, uint k) { return (x << k) | (x >> (64u - k)); }
uint64_t mixStafford13(uint64_t z) {
    z = (z ^ (z >> 30u)) * ((uint64_t(0xBF58476Du) << 32) | uint64_t(0x1CE4E5B9u));
    z = (z ^ (z >> 27u)) * ((uint64_t(0x94D049BBu) << 32) | uint64_t(0x133111EBu));
    return z ^ (z >> 31u);
}
// XoroshiroRandomSource.setSeed: RandomSupport.upgradeSeedTo128bit(seed).mixed()
[noinline]
void rngSeed(int64_t seed) {
    uint64_t lo = uint64_t(seed) ^ ((uint64_t(0x6A09E667u) << 32) | uint64_t(0xF3BCC909u));   // SILVER_RATIO_64
    uint64_t hi = lo + ((uint64_t(0x9E3779B9u) << 32) | uint64_t(0x7F4A7C15u));                // + GOLDEN_RATIO_64
    uint64_t a = mixStafford13(lo), b = mixStafford13(hi);
    if ((a | b) == 0u) {
        a = (uint64_t(0x9E3779B9u) << 32) | uint64_t(0x7F4A7C15u);
        b = (uint64_t(0x6A09E667u) << 32) | uint64_t(0xF3BCC909u);
    }
    rngStore(a, b);
}
[ForceInline]
uint64_t xoroNext() {
    uint64_t s0 = rngLo(), s1 = rngHi();
    if (TREE_TRACE) scratch[sRA + 5u] = scratch[sRA + 5u] + 1u;  // the draws (WorldgenRandom.getCount: the trees' trace)
    uint64_t result = rotl(s0 + s1, 17u) + s0;
    s1 = s1 ^ s0;
    rngStore(rotl(s0, 49u) ^ s1 ^ (s1 << 21u), rotl(s1, 28u));
    return result;
}
// WorldgenRandom.next(bits) = (int) (nextLong() >>> 64 - bits): one [noinline] draw (inlined at every call site the
// tree kernel's driver compile took minutes), its result through a pointer argument (a local of each call site: NVIDIA
// merges or mixes the returned values of calls to a [noinline] function with side effects, see sRA)
[noinline]
void rngNextP(uint bits, inout int r) { r = int(uint(xoroNext() >> (64u - bits))); }
[ForceInline]
int rngNext(uint bits) {
    int r = 0;
    rngNextP(bits, r);
    return r;
}
[ForceInline]
int rngInt() { return rngNext(32u); }
// BitRandomSource.nextInt(bound) (as rngNext: a void [noinline] with its result through a pointer)
[ForceInline]
int rngIntB(int bound) {
    int r = 0;
    rngIntBP(bound, r);
    return r;
}
[noinline]
void rngIntBP(int bound, inout int r) { r = rngIntBV(bound); }
[ForceInline]
int rngIntBV(int bound) {
    if (bound <= 0) return 0;
    if ((bound & (bound - 1)) == 0) return int((int64_t(bound) * int64_t(rngNext(31u))) >> 31);
    // Java: do { sample = next(31); modulo = sample % bound; } while (sample - modulo + (bound - 1) < 0), the sum
    // overflowing int; here in 64 bits (no signed overflow), the redraw a plain while loop
    int sample = rngNext(31u);
    int modulo = int(uint(sample) % uint(bound));  // sample >= 0
    int guard = 0;
    while (int64_t(sample) - int64_t(modulo) + int64_t(bound - 1) > int64_t(0x7FFFFFFF) && guard < 64) {
        sample = rngNext(31u);
        modulo = int(uint(sample) % uint(bound));
        guard++;
    }
    return modulo;
}
[ForceInline]
int64_t rngLong() {
    int upper = rngNext(32u);
    int lower = rngNext(32u);
    return (int64_t(upper) << 32) + int64_t(lower);
}
[ForceInline]
bool rngBool() { return rngNext(1u) != 0; }
[ForceInline]
float rngFloat() { return float(rngNext(24u)) * asfloat(0x33800000u); }  // * 5.9604645E-8F (2^-24)
[ForceInline]
double rngDouble() {
    double r = 0.0l;
    rngDoubleP(r);
    return r;
}
[noinline]
void rngDoubleP(inout double r) { r = rngDoubleV(); }
[ForceInline]
double rngDoubleV() {
    int upper = rngNext(26u);
    int lower = rngNext(27u);
    int64_t combined = (int64_t(upper) << 27) + int64_t(lower);
    return double(l2f(combined) * asfloat(0x25000000u));  // a long times 1.110223E-16F: float arithmetic
}
[ForceInline]
int rngBetween(int lo, int hi) { return rngIntB(hi - lo + 1) + lo; }  // Mth.randomBetweenInclusive
// WorldgenRandom.setDecorationSeed / setFeatureSeed
[noinline]
int64_t decorationSeed(int64_t seed, int x0, int z0) {
    rngSeed(seed);
    int64_t xs = rngLong() | 1, zs = rngLong() | 1;
    int64_t res = int64_t(uint64_t(int64_t(x0)) * uint64_t(xs) + uint64_t(int64_t(z0)) * uint64_t(zs)) ^ seed;
    return res;
}
[ForceInline]
void featureSeed(int64_t deco, int gi, int step) { rngSeed(deco + int64_t(gi) + int64_t(10000 * step)); }

// ---- per origin state --------------------------------------------------------------------------------------------
struct Org {
    uint o;      // origin index
    uint base;   // scratch base
    int cx, cz;  // the decorated chunk
};
// FL_* of this thread's call (a thread-private variable: passing a struct member as inout makes Slang inline the
// callee at every call site, which blew the tree kernel up to megabytes)
static Org sG;   // the origin this thread decorates (thread-private)
// a flag (FL_*) on the origin, straight into its meta word: thread-private statics written in [noinline] callees are
// not reliable on NVIDIA (see sRA)
[ForceInline]
void flag(uint f) { scratch[sG.base + SCR_META + 1u] = scratch[sG.base + SCR_META + 1u] | f; }
[noinline]
Org orgOf(uint o) {
    Org g;
    g.o = o;
    g.base = o * SCR_W;
    g.cx = origins[o * ORIGIN_W];
    g.cz = origins[o * ORIGIN_W + 1];
    return g;
}
[noinline]
int slotOf(int cx, int cz) {
    int dx = cx - sG.cx, dz = cz - sG.cz;
    if (dx < -2 || dx > 2 || dz < -2 || dz > 2) return -1;
    return origins[sG.o * ORIGIN_W + 2 + uint((dz + 2) * 5 + (dx + 2))];
}
bool inBox(int x, int z) {
    int dx = (x >> 4) - sG.cx, dz = (z >> 4) - sG.cz;
    return dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1;
}
// the writable box of a decoration (AtlasGenFeatures.Level.writable)
bool writable(int x, int y, int z) { return inBox(x, z) && y >= MIN_Y && y <= MAX_Y; }
// the write table key of a block in the 3 x 3 (+1: 0 is empty)
uint keyOf(int x, int y, int z) {
    uint lx = uint(x - (sG.cx - 1) * 16), lz = uint(z - (sG.cz - 1) * 16), ly = uint(y - MIN_Y);
    return ((ly * 48u + lz) * 48u + lx) + 1u;
}
uint htSlot(uint key) { return (key * 0x9E3779B1u) >> (32u - 15u); }  // log2(HT_CAP) = 15
// the write table: the block written at a key, or 0xFFFFFFFF
[noinline]
uint htGet(uint key) {
    uint s = htSlot(key);
    for (uint n = 0; n < HT_CAP; n++) {
        uint k = scratch[sG.base + SCR_HT + s * 2u];
        if (k == key) return scratch[sG.base + SCR_HT + s * 2u + 1u];
        if (k == 0u) return 0xFFFFFFFFu;
        s = (s + 1u) & (HT_CAP - 1u);
    }
    return 0xFFFFFFFFu;
}
[noinline]
void htPut(uint key, uint id) {
    uint s = htSlot(key);
    for (uint n = 0; n < HT_CAP; n++) {
        uint k = scratch[sG.base + SCR_HT + s * 2u];
        if (k == key) { scratch[sG.base + SCR_HT + s * 2u + 1u] = id; return; }
        if (k == 0u) {
            uint used = scratch[sG.base + SCR_META];
            if (used >= HT_CAP * 3u / 4u) { flag(FL_TABLE_FULL); return; }
            scratch[sG.base + SCR_META] = used + 1u;
            scratch[sG.base + SCR_HT + s * 2u] = key;
            scratch[sG.base + SCR_HT + s * 2u + 1u] = id;
            return;
        }
        s = (s + 1u) & (HT_CAP - 1u);
    }
}
// block b (x + 16 z + 256 (y + 64)) of a terrain slot, a feature id
[noinline]
uint slotBlock(uint slot, uint b) {
    if (params.store != 0u) {
        if (b >= STORE_BLOCKS) return AIR;  // (the store holds y -64..575: air above)
        uint w = chunkIds[slot * STORE_TERRAIN_W + (b >> 1)];
        uint g = (b & 1u) != 0u ? (w >> 16) : (w & 0xFFFFu);
        uint t = hdr(H_GEN2FEAT);
        return t == 0u ? g : (g < hdr(H_NGEN) ? fprog[t + g] : AIR);
    }
    uint w = chunkIds[slot * CHUNK_W + (b >> 1)];
    return (b & 1u) != 0u ? (w >> 16) : (w & 0xFFFFu);
}
[noinline]
uint terrainAt(int x, int y, int z) {
    int slot = slotOf(x >> 4, z >> 4);
    if (slot < 0) { flag(FL_OUTSIDE); return AIR; }
    uint b = uint(x & 15) + 16u * uint(z & 15) + 256u * uint(y - MIN_Y);
    return slotBlock(uint(slot), b);
}
// Level.getBlockState: what this decoration sees
[noinline]
uint blockAt(int x, int y, int z) {
    if (y < MIN_Y || y > MAX_Y) return AIR;  // (VOID_AIR: air for every test made here)
    if (inBox(x, z)) {
        uint v = htGet(keyOf(x, y, z));
        if (v != 0xFFFFFFFFu) return v;
    }
    return terrainAt(x, y, z);
}

// ---- heightmaps ----------------------------------------------------------------------------------------------------
[noinline]
bool hmOpaque(uint type, uint id) {
    uint f = bflags(id);
    if (type == HM_WORLD_SURFACE_WG || type == HM_WORLD_SURFACE) return (f & B_AIR) == 0u;
    if (type == HM_OCEAN_FLOOR_WG || type == HM_OCEAN_FLOOR) return (f & B_MOTION) != 0u;
    if (type == HM_MOTION_BLOCKING) return (f & (B_MOTION | B_FLUID)) != 0u;
    return (f & (B_MOTION_NL | B_FLUID)) != 0u;
}
int finalIndex(uint type) { return type == HM_WORLD_SURFACE ? 0 : type == HM_OCEAN_FLOOR ? 1 : type == HM_MOTION_BLOCKING ? 2 : type == HM_MOTION_BLOCKING_NO_LEAVES ? 3 : -1; }
uint hmTypeOfFinal(int f) { return f == 0 ? HM_WORLD_SURFACE : f == 1 ? HM_OCEAN_FLOOR : f == 2 ? HM_MOTION_BLOCKING : HM_MOTION_BLOCKING_NO_LEAVES; }
uint hmLocal(int x, int z) { return uint(z - (sG.cz - 1) * 16) * 48u + uint(x - (sG.cx - 1) * 16); }
// level.getHeight(type, x, z): the first free y above the heightmap's top (AtlasGenFeatures' proxy: c.getHeight + 1)
[noinline]
int heightAt(uint type, int x, int z) {
    int f = finalIndex(type);
    if (f >= 0 && inBox(x, z)) return int(scratch[sG.base + SCR_HM + uint(f) * 2304u + hmLocal(x, z)]);
    int slot = slotOf(x >> 4, z >> 4);
    if (slot < 0) { flag(FL_OUTSIDE); return MIN_Y; }
    return int(chunkAux[uint(slot) * AUX_W + AUX_HM + type * 256u + uint(z & 15) * 16u + uint(x & 15)]);
}
// Heightmap.update after a ProtoChunk write
[noinline]
void hmUpdate(int f, int x, int y, int z, uint id) {
    uint type = hmTypeOfFinal(f);
    uint at = sG.base + SCR_HM + uint(f) * 2304u + hmLocal(x, z);
    int first = int(scratch[at]);
    if (y <= first - 2) return;
    if (hmOpaque(type, id)) {
        if (y >= first) scratch[at] = uint(y + 1);
    } else if (first - 1 == y) {
        for (int yy = y - 1; yy >= MIN_Y; yy--) {
            if (hmOpaque(type, blockAt(x, yy, z))) { scratch[at] = uint(yy + 1); return; }
        }
        scratch[at] = uint(MIN_Y);
    }
}
// Level.setBlock (the proxy: inside the 3 x 3, then ProtoChunk.setBlockState with its heightmaps); sections written
// directly (ores: BulkSectionAccess) pass heightmaps = false
[noinline]
bool setBlock(int x, int y, int z, uint id, bool heightmaps) {
    if (!writable(x, y, z)) return false;
    htPut(keyOf(x, y, z), id);
    scratch[sG.base + SCR_META + 3u] = scratch[sG.base + SCR_META + 3u] + 1u;
    if (heightmaps)
        for (int f = 0; f < 4; f++) hmUpdate(f, x, y, z, id);
    return true;
}

// ---- biomes ----------------------------------------------------------------------------------------------------------
// a proto chunk's noise biome (clamped to its quarts), -1 outside the 5 x 5
[noinline]
int quartBiome(int qx, int qy, int qz) {
    int slot = slotOf(qx >> 2, qz >> 2);
    if (slot < 0) { flag(FL_OUTSIDE); return 0; }
    int y = qy < -16 ? -16 : (qy > 159 ? 159 : qy);
    uint i = uint((((qx & 3) * 4 + (qz & 3)) * int(QY)) + (y + 16));
    uint w = chunkAux[uint(slot) * AUX_W + AUX_QUARTS + (i >> 1)];
    return int((i & 1u) != 0u ? (w >> 16) : (w & 0xFFFFu));
}
static const double K_INV1024 = asdouble(0x00000000u, 0x3F500000u);  // 2^-10
// BiomeManager.getBiome: the seeded voronoi zoom
[noinline]
int biomeAt(int x, int y, int z) {
    int absX = x - 2, absY = y - 2, absZ = z - 2;
    int px = absX >> 2, py = absY >> 2, pz = absZ >> 2;
    double fx = double(absX & 3) * 0.25l, fy = double(absY & 3) * 0.25l, fz = double(absZ & 3) * 0.25l;
    uint64_t seed = pfU64(H_ZOOM);
    int minI = 0;
    double minD = asdouble(0u, 0x7FF00000u);
    uint64_t mul = (uint64_t(0x5851F42Du) << 32) | uint64_t(0x4C957F2Du);
    uint64_t inc = (uint64_t(0x14057B7Eu) << 32) | uint64_t(0xF767814Fu);
    for (int i = 0; i < 8; i++) {
        bool xe = (i & 4) == 0, ye = (i & 2) == 0, ze = (i & 1) == 0;
        int cx = xe ? px : px + 1, cy = ye ? py : py + 1, cz = ze ? pz : pz + 1;
        double ddx = xe ? fx : fx - 1.0l, ddy = ye ? fy : fy - 1.0l, ddz = ze ? fz : fz - 1.0l;
        uint64_t r = seed;
        r = r * (r * mul + inc) + uint64_t(int64_t(cx));
        r = r * (r * mul + inc) + uint64_t(int64_t(cy));
        r = r * (r * mul + inc) + uint64_t(int64_t(cz));
        r = r * (r * mul + inc) + uint64_t(int64_t(cx));
        r = r * (r * mul + inc) + uint64_t(int64_t(cy));
        r = r * (r * mul + inc) + uint64_t(int64_t(cz));
        double fidX = (double(int((uint64_t(int64_t(r) >> 24)) & 1023u)) * K_INV1024 - 0.5l) * 0.9l;
        r = r * (r * mul + inc) + seed;
        double fidY = (double(int((uint64_t(int64_t(r) >> 24)) & 1023u)) * K_INV1024 - 0.5l) * 0.9l;
        r = r * (r * mul + inc) + seed;
        double fidZ = (double(int((uint64_t(int64_t(r) >> 24)) & 1023u)) * K_INV1024 - 0.5l) * 0.9l;
        double a = ddz + fidZ, b = ddy + fidY, c = ddx + fidX;
        double dist = a * a + b * b + c * c;
        if (minD > dist) { minI = i; minD = dist; }
    }
    int bx = (minI & 4) == 0 ? px : px + 1, by = (minI & 2) == 0 ? py : py + 1, bz = (minI & 1) == 0 ? pz : pz + 1;
    return quartBiome(bx, by, bz);
}
bool biomeBit(uint set, int b) { return b >= 0 && (fprog[set + uint(b >> 5)] >> uint(b & 31) & 1u) != 0u; }

// ---- the placement mask (PlacementMask.clearance: a page per chunk) ------------------------------------------------
[noinline]
int clearance(int x, int z) {
    int slot = slotOf(x >> 4, z >> 4);
    if (slot < 0) { flag(FL_OUTSIDE); return 0; }
    uint i = uint(z & 15) * 16u + uint(x & 15);
    uint w = chunkAux[uint(slot) * AUX_W + AUX_MASK + (i >> 2)];
    return int((w >> ((i & 3u) * 8u)) & 0xFFu);
}
bool maskClear(int x, int z, int radius) { return clearance(x, z) > radius; }

// ---- FloraNoise / FloraField (tamriel.biome.flora) ---------------------------------------------------------------
[noinline]
int floraHash(int x, int z, int64_t seed) {
    uint64_t h = uint64_t(seed) ^ ((uint64_t(0x9E3779B9u) << 32) | uint64_t(0x7F4A7C15u));
    h = h ^ (uint64_t(int64_t(x)) * ((uint64_t(0xC2B2AE3Du) << 32) | uint64_t(0x27D4EB4Fu)));
    h = rotl(h, 31u) * ((uint64_t(0x165667B1u) << 32) | uint64_t(0x9E3779F9u));
    h = h ^ (uint64_t(int64_t(z)) * ((uint64_t(0xD6E8FEB8u) << 32) | uint64_t(0x6659FD93u)));
    h = h ^ (h >> 29u);
    h = h * ((uint64_t(0xBF58476Du) << 32) | uint64_t(0x1CE4E5B9u));
    h = h ^ (h >> 32u);
    return int(uint(h & 0xFFFFFFFFu));
}
double unit01(int h) { return double(int(uint(h) >> 8u)) * asdouble(0u, 0x3E700000u); }  // 2^-24
double unitS(int h) { return unit01(h) * 2.0l - 1.0l; }
double fade(double t) { return t * t * t * (t * (t * 6.0l - 15.0l) + 10.0l); }
[noinline]
double valueNoise(double x, double z, int64_t seed, inout uint bad) {
    int x0 = floorD(x, bad), z0 = floorD(z, bad);
    double fx = fade(x - double(x0)), fz = fade(z - double(z0));
    double a = unitS(floraHash(x0, z0, seed)), b = unitS(floraHash(x0 + 1, z0, seed));
    double c = unitS(floraHash(x0, z0 + 1, seed)), d = unitS(floraHash(x0 + 1, z0 + 1, seed));
    double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
    return ab + (cd - ab) * fz;
}
[noinline]
double fbm(double x, double z, int64_t seed, int octaves, inout uint bad) {
    double sum = 0.0l, amp = 1.0l, norm = 0.0l;
    int64_t step = int64_t((uint64_t(0x632BE59Bu) << 32) | uint64_t(0xD9B4E019u));
    for (int o = 0; o < octaves; o++) {
        sum += valueNoise(x, z, seed + int64_t(o) * step, bad) * amp;
        norm += amp;
        amp *= 0.5l;
        double nx = (x * 0.799l - z * 0.602l) * 2.03l + 17.3l;
        double nz = (x * 0.602l + z * 0.799l) * 2.03l - 9.1l;
        x = nx;
        z = nz;
    }
    return divD(sum, norm, bad);
}
[noinline]
double worley(double x, double z, int64_t seed, inout uint bad) {
    int cx = floorD(x, bad), cz = floorD(z, bad);
    double best = 9.0l;
    for (int dx = -1; dx <= 1; dx++)
        for (int dz = -1; dz <= 1; dz++) {
            int h = floraHash(cx + dx, cz + dz, seed);
            double px = double(cx + dx) + 0.15l + 0.7l * unit01(h);
            double pz = double(cz + dz) + 0.15l + 0.7l * unit01(int(uint(h) * 0x2C1B3C6Du + 0x297A2D39u));
            double d = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d < best) best = d;
        }
    return sqrtD(best, bad);
}
[noinline]
double smoothstep(double lo, double hi, double v, inout uint bad) {
    if (v <= lo) return 0.0l;
    if (v >= hi) return 1.0l;
    double t = divD(v - lo, hi - lo, bad);
    return t * t * (3.0l - 2.0l * t);
}
// field table: 8 words each: salt, scale (float), coverage (float), softness (float), kind (0 fbm, 1 worley),
// mode (0 inside, 1 outside, 2 edge, 3 any), then the doubles sqrt(coverage / PI) min 0.75 ... (constants below)
static const uint FIELD_W = 8;
int64_t fieldSeed(uint f, int64_t worldSeed) {
    int salt = int(fprog[f]);
    return int64_t(uint64_t(worldSeed) * uint64_t(0x5DEECE66Dl) + uint64_t(int64_t(salt)) * ((uint64_t(0x9E3779B9u) << 32) | uint64_t(0x7F4A7C15u)));
}
[noinline]
double fieldRaw(uint f, int64_t seed, double x, double z, inout uint bad) {
    double scale = double(pfF(f + 1)), coverage = double(pfF(f + 2)), softness = double(pfF(f + 3));
    double s = dmax(0.02l, softness);
    if (fprog[f + 4] == 1u) {
        double d = worley(divD(x, scale, bad), divD(z, scale, bad), seed, bad);
        double r = dmin(0.75l, sqrtD(divD(coverage, asdouble(0x54442D18u, 0x400921FBu), bad), bad));  // Math.PI
        d += valueNoise(divD(x, scale * 0.4l, bad), divD(z, scale * 0.4l, bad), seed + 77, bad) * 0.12l;
        return 1.0l - smoothstep(r - s, r + s, d, bad);
    }
    double n = fbm(divD(x, scale, bad), divD(z, scale, bad), seed, 3, bad);
    // 1.702 / 0.27: javac folds it to the double the program carries
    double u = divD(1.0l, 1.0l + expS(-n * pfD(hdr(H_CONSTS)), bad), bad);
    double t = 1.0l - coverage;
    return smoothstep(t - s, t + s, u, bad);
}
[noinline]
double fieldSample(uint f, int64_t worldSeed, double x, double z, inout uint bad) {
    uint mode = fprog[f + 5];
    if (mode == 3u) return 1.0l;
    double v = fieldRaw(f, fieldSeed(f, worldSeed), x, z, bad);
    if (mode == 1u) return 1.0l - v;
    if (mode == 2u) return dmin(1.0l, 4.0l * v * (1.0l - v));
    return v;
}

// ---- int and height providers ------------------------------------------------------------------------------------
[noinline]
int intProvider(uint w) {
    uint kind = fprog[w];
    int a = int(fprog[w + 1]), b = int(fprog[w + 2]), c = int(fprog[w + 3]);
    if (kind == IP_CONST) return a;
    if (kind == IP_UNIFORM) return rngBetween(a, b);
    if (kind == IP_BIASED) return a + rngIntB(rngIntB(b - a + 1) + 1);
    if (kind == IP_TRAPEZOID) {  // TrapezoidInt(min a, max b, plateau c)
        if (c == 0 && b == -a) {
            int first = rngIntB(b + 1);  // Java: left operand first
            return first - rngIntB(b + 1);
        }
        int range = b - a;
        if (c == range) return rngBetween(a, b);
        int plateauStart = (range - c) / 2, plateauEnd = range - plateauStart;
        int v1 = rngBetween(0, plateauEnd);
        return a + v1 + rngBetween(0, plateauStart);
    }
    // clamped uniform: [a, b] sampled, clamped to the low / high halves of c
    int v = rngBetween(a, b);
    int lo = int(c << 16) >> 16, hi = c >> 16;
    return v < lo ? lo : (v > hi ? hi : v);
}
[noinline]
int heightProvider(uint w) {
    uint kind = fprog[w];
    int mn = int(fprog[w + 1]), mx = int(fprog[w + 2]), p = int(fprog[w + 3]);
    if (kind == HP_CONST) return mn;
    if (mn > mx) return mn;
    if (kind == HP_UNIFORM) return rngBetween(mn, mx);
    if (kind == HP_TRAPEZOID) {
        int range = mx - mn;
        if (p >= range) return rngBetween(mn, mx);
        int plateauStart = (range - p) / 2, plateauEnd = range - plateauStart;
        int v1 = rngBetween(0, plateauEnd);
        return mn + v1 + rngBetween(0, plateauStart);
    }
    if (kind == HP_BIASED) {
        int limit = rngIntB(mx - mn - p + 1);
        return rngIntB(limit + p) + mn;
    }
    // very biased to bottom: Mth.nextInt(random, a, b) = a >= b ? a : random.nextInt(b - a + 1) + a
    int b1 = mn + p >= mx ? mn + p : rngIntB(mx - (mn + p) + 1) + (mn + p);
    int b2 = mn >= b1 - 1 ? mn : rngIntB((b1 - 1) - mn + 1) + mn;
    int top = b2 - 1 + p;
    return mn >= top ? mn : rngIntB(top - mn + 1) + mn;
}

// ---- block predicates and survival -------------------------------------------------------------------------------
// a predicate is postfix code, 5 words an op: op, dx, dy, dz, arg; P_ALL / P_ANY pop arg values, P_NOT one
static const uint P_END = 0, P_SET = 1, P_SURVIVE = 2, P_BOUNDS = 3, P_TRUE = 4, P_ALL = 5, P_ANY = 6, P_NOT = 7;
// survival table: 4 words a plant state: its id, the bit set of the blocks it may stand on, its kind, pad
static const uint SK_BELOW = 0, SK_CANE = 1, SK_CACTUS = 2, SK_UNSUPPORTED = 3;
bool inSet(uint set, uint id) { return (fprog[set + (id >> 5)] >> (id & 31u) & 1u) != 0u; }
[noinline]
bool survives(uint s, int x, int y, int z) {
    uint e = hdr(H_SURVIVE) + s * 4u;
    uint kind = fprog[e + 2];
    uint below = blockAt(x, y - 1, z);
    bool ok = inSet(fprog[e + 1], below);
    if (kind == SK_BELOW) return ok;
    if (kind == SK_CANE) {
        if (below == fprog[e]) return true;
        if (!ok) return false;
        // Direction.Plane.HORIZONTAL: north, east, south, west
        if (bis(blockAt(x, y - 1, z - 1), B_FLUID_WATER_TAG)) return true;
        if (bis(blockAt(x + 1, y - 1, z), B_FLUID_WATER_TAG)) return true;
        if (bis(blockAt(x, y - 1, z + 1), B_FLUID_WATER_TAG)) return true;
        return bis(blockAt(x - 1, y - 1, z), B_FLUID_WATER_TAG);
    }
    if (kind == SK_CACTUS) {
        uint n0 = blockAt(x, y, z - 1), n1 = blockAt(x + 1, y, z), n2 = blockAt(x, y, z + 1), n3 = blockAt(x - 1, y, z);
        if (bis(n0, B_SOLID) || bis(n0, B_LAVA) || bis(n1, B_SOLID) || bis(n1, B_LAVA)) return false;
        if (bis(n2, B_SOLID) || bis(n2, B_LAVA) || bis(n3, B_SOLID) || bis(n3, B_LAVA)) return false;
        if (bis(blockAt(x, y + 1, z), B_FLUID)) return false;
        return ok;
    }
    flag(FL_UNSUPPORTED);
    return false;
}
[noinline]
bool predicate(uint code, int x, int y, int z) {
    bool st[16];
    int sp = 0;
    for (uint pc = code; pc < code + 5u * 64u; pc += 5u) {
        uint op = fprog[pc];
        if (op == P_END) break;
        int px = x + int(fprog[pc + 1]), py = y + int(fprog[pc + 2]), pz = z + int(fprog[pc + 3]);
        uint arg = fprog[pc + 4];
        bool v = false;
        if (op == P_SET) v = inSet(arg, blockAt(px, py, pz));
        else if (op == P_SURVIVE) v = survives(arg, px, py, pz);
        else if (op == P_BOUNDS) v = py >= MIN_Y && py <= MAX_Y;
        else if (op == P_TRUE) v = true;
        else if (op == P_NOT) { v = sp > 0 ? !st[sp - 1] : true; if (sp > 0) sp--; }
        else {
            bool acc = op == P_ALL;
            for (uint i = 0; i < arg; i++) {
                bool b = sp - 1 - int(i) >= 0 ? st[sp - 1 - int(i)] : false;
                acc = op == P_ALL ? (acc && b) : (acc || b);
            }
            sp -= int(arg);
            if (sp < 0) sp = 0;
            v = acc;
        }
        if (sp < 16) { st[sp] = v; sp++; }
    }
    return sp > 0 && st[sp - 1];
}

// ---- features --------------------------------------------------------------------------------------------------
float mthSin(double v) {  // Mth.sin: the table (uploaded from Java's own)
    return asfloat(fprog[hdr(H_SIN) + uint(uint64_t(d2l(v * 10430.378350470453l)) & 65535u)]);
}

// OreFeature (26.3: AbstractOreFeature): cfg size, discard chance (float), targets, then per target rule kind, set,
// probability (float), the state
static const uint RT_TRUE = 0, RT_SET = 1, RT_RANDOM_SET = 2;
[noinline]
bool oreTest(uint t, uint state) {
    uint kind = fprog[t];
    if (kind == RT_TRUE) return true;
    if (!inSet(fprog[t + 1], state)) return false;
    if (kind == RT_RANDOM_SET) return rngFloat() < pfF(t + 2);
    return true;
}
[noinline]
bool adjacentAir(int x, int y, int z) {
    // Direction.values(): DOWN, UP, NORTH, SOUTH, WEST, EAST
    if (bis(blockAt(x, y - 1, z), B_AIR)) return true;
    if (bis(blockAt(x, y + 1, z), B_AIR)) return true;
    if (bis(blockAt(x, y, z - 1), B_AIR)) return true;
    if (bis(blockAt(x, y, z + 1), B_AIR)) return true;
    if (bis(blockAt(x - 1, y, z), B_AIR)) return true;
    return bis(blockAt(x + 1, y, z), B_AIR);
}
// (the vein kernel) whether an ore placement's block loop may run on many threads: see placeOre
static const uint VEIN_PENDING = 0x5645494Eu;  // M_LINE[0]: a vein waits for featVeinMain and its next round's apply
static const uint VEIN_W = 12288u;             // its tested indices (SCR_TESTED: the first visit's key; SCR_TREE: the fate)
[noinline]
bool veinFits(uint cfg, float discard, uint nt) {
    // (the box's size is tested by the caller: passed in here it read wrong on NVIDIA (sizeY 17 where the caller's is
    // 14), so no vein ever took the many threads)
    if (!(discard <= 0.0f || discard >= 1.0f)) { CLKN(20); return false; }
    if (nt > 254u) { CLKN(21); return false; }
    for (uint t = 0; t < nt; t++) {
        uint te = cfg + 3u + t * 4u;
        if (fprog[te] != RT_SET) { CLKN(23); return false; }
        if (inSet(fprog[te + 1], AIR)) { CLKN(24); return false; }
        if (discard >= 1.0f) {
            // (the air test reads neighbours: no target may be air of any kind, no replacement either)
            if (bis(fprog[te + 3], B_AIR)) return false;
            uint nb = hdr(H_NBLOCKS), set = fprog[te + 1];
            for (uint w = 0; w * 32u < nb; w++) {
                uint bits = fprog[set + w];
                for (uint b = 0; b < 32u && bits != 0u; b++)  // (no FindILsb: spv_exact admits no such instruction)
                    if ((bits >> b & 1u) != 0u && w * 32u + b < nb && bis(w * 32u + b, B_AIR)) return false;
            }
        }
    }
    return true;
}
[noinline]
bool placeOre(uint cfg, int ox, int oy, int oz) {
    int size = int(fprog[cfg]);
    float discard = pfF(cfg + 1);
    uint nt = fprog[cfg + 2];
    float PIF = asfloat(0x40490FDBu);  // (float) Math.PI
    CLK0(c0);
    float dir = rngFloat() * PIF;
    float spreadXY = float(size) / 8.0f;  // exact (a power of two)
    int maxRadius = ceilF_N((float(size) / 16.0f * 2.0f + 1.0f) / 2.0f);
    double sd = sinS_N(double(dir)), cd = cosS_N(double(dir));
    double x0 = double(ox) + sd * double(spreadXY);
    double x1 = double(ox) - sd * double(spreadXY);
    double z0 = double(oz) + cd * double(spreadXY);
    double z1 = double(oz) - cd * double(spreadXY);
    int ry0 = rngIntB(3);
    double y0 = double(oy + ry0 - 2);
    int ry1 = rngIntB(3);
    double y1 = double(oy + ry1 - 2);
    int cs = ceilF_N(spreadXY);
    int xStart = ox - cs - maxRadius, yStart = oy - 2 - maxRadius, zStart = oz - cs - maxRadius;
    int sizeXZ = 2 * (cs + maxRadius);
    // sizeY = 2 * (2 + maxRadius), written another way: as that expression NVIDIA's driver (616.56, this -O0 SPIR-V)
    // computed 17 for a maxRadius of 5 (the ore's tested-index aliasing then not vanilla's, and no vein took the many
    // threads: its box looked too tall)
    int sizeY = (yStart - oy) * -2;
    CLK1(2, c0);
    CLK0(c1);
    bool any = false;
    for (int xp = xStart; xp <= xStart + sizeXZ && !any; xp++)
        for (int zp = zStart; zp <= zStart + sizeXZ; zp++)
            if (yStart <= heightAt(HM_OCEAN_FLOOR_WG, xp, zp)) { any = true; break; }
    CLK1(3, c1);
    if (!any) return false;
    // doPlace
    CLK0(c2);
    uint data = sG.base + SCR_MISC + M_ORE;  // size <= 64: 4 doubles each (8 words)
    for (int i = 0; i < size; i++) {
        float step = divF_N(float(i), float(size));
        double xx = x0 + double(step) * (x1 - x0);
        double yy = y0 + double(step) * (y1 - y0);
        double zz = z0 + double(step) * (z1 - z0);
        double ss = rngDouble() * double(size) / 16.0l;
        double rr = ((double(mthSin(double(PIF * step)) + 1.0f)) * ss + 1.0l) / 2.0l;
        scrSetD(data + uint(i) * 8u, xx);
        scrSetD(data + uint(i) * 8u + 2u, yy);
        scrSetD(data + uint(i) * 8u + 4u, zz);
        scrSetD(data + uint(i) * 8u + 6u, rr);
    }
    CLK1(4, c2);
    CLK0(c3);
    // (the vein kernel's capacity: 64 spheres, its index field widths, a thread a position of the box)
    bool boxFits = size <= 64 && sizeXZ <= 31 && sizeY <= 15 && uint(sizeXZ + 1) * uint(sizeY + 1) * uint(sizeXZ + 1) <= 12288u;
    if (!boxFits) CLKN(22);
#if FEAT_CLOCK
    InterlockedAdd(counters[16u + 25u], 1u); InterlockedMax(counters[48u + 25u], uint(sizeXZ));
    InterlockedAdd(counters[16u + 26u], 1u); InterlockedMax(counters[48u + 26u], uint(sizeY));
    InterlockedAdd(counters[16u + 27u], 1u); InterlockedMax(counters[48u + 27u], uint(maxRadius));
    InterlockedAdd(counters[16u + 28u], 1u); InterlockedMax(counters[48u + 28u], uint(cs));
    InterlockedAdd(counters[16u + 29u], 1u); InterlockedMax(counters[48u + 29u], uint(size));
#endif
    // (a small blob stays on the one lane: its loop is short, and a vein ends the origin's round, five passes after it)
    bool toVein = (params.store & 2u) != 0u && size >= 33 && boxFits && veinFits(cfg, discard, nt);
    CLK1(5, c3);
    if (toVein) CLKN(7); else CLKN(8);
    CLK0(c4);
    for (int i1 = 0; i1 < size - 1 && !toVein; i1++) {  // (the vein kernel's pass 3 instead: featVeinMain)
        if (scrD(data + uint(i1) * 8u + 6u) <= 0.0l) continue;
        for (int i2 = i1 + 1; i2 < size; i2++) {
            if (scrD(data + uint(i2) * 8u + 6u) <= 0.0l) continue;
            double dx = scrD(data + uint(i1) * 8u) - scrD(data + uint(i2) * 8u), dy = scrD(data + uint(i1) * 8u + 2u) - scrD(data + uint(i2) * 8u + 2u), dz = scrD(data + uint(i1) * 8u + 4u) - scrD(data + uint(i2) * 8u + 4u);
            double dr = scrD(data + uint(i1) * 8u + 6u) - scrD(data + uint(i2) * 8u + 6u);
            if (dr * dr > dx * dx + dy * dy + dz * dz) {
                if (dr > 0.0l) scrSetD(data + uint(i2) * 8u + 6u, -1.0l);
                else scrSetD(data + uint(i1) * 8u + 6u, -1.0l);
            }
        }
    }
    // (the store, | 2) the block loop on the vein kernel's threads (featVeinMain), when no draw of the random falls in
    // it (discard 0 or 1, no random targets) and no target matches air: then each position's fate is its own (its
    // block, its neighbours' airness: this vein turns no air into ore), and only the first visit of each tested
    // index counts (vanilla's BitSet, aliasing kept), as here. Its writes come at the origin's next round.
    if (toVein) {
        uint v = sG.base + SCR_MISC + M_LINE;
        scratch[v + 1u] = cfg;
        scratch[v + 2u] = uint(xStart); scratch[v + 3u] = uint(yStart); scratch[v + 4u] = uint(zStart);
        scratch[v + 5u] = uint(sizeXZ); scratch[v + 6u] = uint(sizeY); scratch[v + 7u] = uint(size);
        scratch[v + 8u] = asuint(discard); scratch[v + 9u] = nt;
        scratch[v] = VEIN_PENDING;
        return true;
    }
    // the tested marks: a stamp per index of this placement
    uint stamp = scratch[sG.base + SCR_META + 2u] + 1u;
    scratch[sG.base + SCR_META + 2u] = stamp;
    int placed = 0;
    for (int i = 0; i < size; i++) {
        double rr = scrD(data + uint(i) * 8u + 6u);
        if (rr < 0.0l) continue;
        double xx = scrD(data + uint(i) * 8u), yy = scrD(data + uint(i) * 8u + 2u), zz = scrD(data + uint(i) * 8u + 4u);
        int xMin = imax(floorD_N(xx - rr), xStart), yMin = imax(floorD_N(yy - rr), yStart), zMin = imax(floorD_N(zz - rr), zStart);
        int xMax = imax(floorD_N(xx + rr), xMin), yMax = imax(floorD_N(yy + rr), yMin), zMax = imax(floorD_N(zz + rr), zMin);
        for (int x = xMin; x <= xMax; x++) {
            double xd = divD_N(double(x) + 0.5l - xx, rr);
            if (!(xd * xd < 1.0l)) continue;
            for (int y = yMin; y <= yMax; y++) {
                double yd = divD_N(double(y) + 0.5l - yy, rr);
                if (!(xd * xd + yd * yd < 1.0l)) continue;
                for (int z = zMin; z <= zMax; z++) {
                    double zd = divD_N(double(z) + 0.5l - zz, rr);
                    if (!(xd * xd + yd * yd + zd * zd < 1.0l) || y < MIN_Y || y > MAX_Y) continue;
                    int bi = (x - xStart) + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;
                    if (bi < 0 || bi >= 12288) { flag(FL_STACK); continue; }
                    uint ta = sG.base + SCR_TESTED + uint(bi);
                    if (scratch[ta] == stamp) continue;
                    scratch[ta] = stamp;
                    if (!writable(x, y, z)) continue;
                    uint state = blockAt(x, y, z);
                    for (uint t = 0; t < nt; t++) {
                        uint te = cfg + 3u + t * 4u;
                        if (!oreTest(te, state)) continue;
                        bool skipAir = discard <= 0.0f ? true : (discard >= 1.0f ? false : rngFloat() >= discard);
                        if (!skipAir && adjacentAir(x, y, z)) continue;
                        setBlock(x, y, z, fprog[te + 3], false);
                        placed++;
                        break;
                    }
                }
            }
        }
    }
    CLK1(6, c4);
    return placed > 0;
}


// ---- GenBox (tamriel.biome.feature): the 3 x 3 around the chunk of a placement's origin ---------------------------
struct GBox {
    int minX, maxX, minZ, maxZ, ccx, ccz;
};
[noinline]
GBox gbox(int x, int z) {
    GBox b;
    b.ccx = x >> 4;
    b.ccz = z >> 4;
    b.minX = b.ccx * 16 - 16;
    b.maxX = b.ccx * 16 + 15 + 16;
    b.minZ = b.ccz * 16 - 16;
    b.maxZ = b.ccz * 16 + 15 + 16;
    return b;
}
bool gContains(GBox b, int x, int y, int z) { return x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ && y >= MIN_Y + 1 && y < MAX_Y; }
uint gGet(GBox b, int x, int y, int z) { return gContains(b, x, y, z) ? blockAt(x, y, z) : AIR; }
bool gSet(GBox b, int x, int y, int z, uint id) { return gContains(b, x, y, z) && setBlock(x, y, z, id, true); }
int gReach(GBox b, int x, int z) { return imin(imin(x - b.minX, b.maxX - x), imin(z - b.minZ, b.maxZ - z)); }
// TreeShapes.ground: the floor height (first block above the ground, under leaves and water), INT_MIN outside
static const int NONE = int(0x80000000u);
[noinline]
int floraGround(GBox b, int x, int z, int hintY) {
    if (x < b.minX || x > b.maxX || z < b.minZ || z > b.maxZ) return NONE;
    int top = ((x >> 4) == b.ccx && (z >> 4) == b.ccz) ? heightAt(HM_MOTION_BLOCKING_NO_LEAVES, x, z) : hintY + 5;
    for (int y = top; y >= top - 14; y--) {
        uint below = gGet(b, x, y - 1, z);
        if (!bis(below, B_SOFT) || bis(below, B_LOGS)) return y;
    }
    return NONE;
}

// state providers: kind 0 simple (no random), 1 weighted (nextInt(total), the table), then total, then the ids
[noinline]
uint provider(uint p) {
    if (fprog[p] == 0u) return fprog[p + 2];
    uint total = fprog[p + 1];
    return fprog[p + 2 + uint(rngIntB(int(total)))];
}

// ---- GroundCoverFeature (flora.ground_cover) ------------------------------------------------------------------
// cfg: [0] entries, [1] scale (float), [2] patchiness (float), [3..6] cluster (int provider), [7] spread, [8] salt,
// [9] near water, [10] pad, then per entry (state, upper half or 0, weight, survivor)
static const uint CV_ENTRIES = 11;
bool canopy(uint id) { return bis(id, B_CANOPY); }
// GroundCoverFeature.findTrueGroundY
[noinline]
int trueGround(int x, int z) {
    int top = heightAt(HM_MOTION_BLOCKING_NO_LEAVES, x, z);
    for (int y = top; y > MIN_Y + 1; y--) {
        uint below = blockAt(x, y - 1, z);
        if (bis(below, B_AIR) || canopy(below)) continue;
        if (bis(blockAt(x, y, z), B_AIR)) return y;
        return -1;
    }
    return -1;
}
bool substrate(uint below) { return !bis(below, B_AIR) && !bis(below, B_FLUID) && !canopy(below) && bis(below, B_STURDY_UP); }
[noinline]
bool nearWaterAt(GBox b, int x, int y, int z) {
    for (int dx = -2; dx <= 2; dx++)
        for (int dz = -2; dz <= 2; dz++) {
            if (iabs(dx) + iabs(dz) > 3 || (dx == 0 && dz == 0)) continue;
            if (bis(gGet(b, x + dx, y, z + dz), B_FLUID_WATER_TAG)) return true;
        }
    return false;
}
// the weighted pick by a slow noise per entry (GroundCoverFeature.pick); -1 none
[noinline]
int coverPick(uint cfg, int x, int z, double roll) {
    uint n = fprog[cfg];
    if (n == 0u) return -1;
    if (n == 1u) return 0;
    double scale = double(pfF(cfg + 1));
    float patch = pfF(cfg + 2);
    int64_t base = int64_t(pfU64(H_SEED)) + int64_t(uint64_t(int64_t(int(fprog[cfg + 8]))) * ((uint64_t(0x2545F491u) << 32) | uint64_t(0x4F6CDD1Du)));
    double w[16];
    double total = 0.0l;
    for (uint i = 0; i < n && i < 16u; i++) {
        double nz = patch > 0.0f ? fbm_N(divD_N(double(x), scale), divD_N(double(z), scale), base + 7919 * int64_t(i + 1u), 2) : 0.0l;
        w[i] = double(int(fprog[cfg + CV_ENTRIES + i * 4u + 2u])) * expS_N(double(patch) * 7.0l * nz);
        total += w[i];
    }
    double t = roll * total;
    for (uint i = 0; i < n && i < 16u; i++) {
        t -= w[i];
        if (t <= 0.0l) return int(i);
    }
    return int(n) - 1;
}
// the regional alchemy plant (placeRegionalAlchemyFlora): lists per column class from the map (aux), drawn as Java
// draws them: kind 0 one nextInt(n) over the list; kind 1 nextInt(3) == 0 ? a : nextBoolean() ? b : c
[noinline]
uint alchClass(int x, int z, uint variant) {
    int slot = slotOf(x >> 4, z >> 4);
    if (slot < 0) { flag(FL_OUTSIDE); return 0u; }
    uint i = variant * 256u + uint(z & 15) * 16u + uint(x & 15);
    uint w = chunkAux[uint(slot) * AUX_W + AUX_ALCH + (i >> 2)];
    return (w >> ((i & 3u) * 8u)) & 0xFFu;
}
[noinline]
void alchemy(uint lists, GBox b, int ox, int oz, bool nearWaterCfg) {
    int x = ox + rngIntB(5) - 2;
    int z = oz + rngIntB(5) - 2;
    if ((x >> 4) != b.ccx || (z >> 4) != b.ccz) return;
    if (!maskClear(x, z, 0)) return;
    int y = trueGround(x, z);
    if (y <= MIN_Y + 1) return;
    if (!bis(blockAt(x, y, z), B_AIR)) return;
    if (!substrate(blockAt(x, y - 1, z))) return;
    bool byWater = nearWaterCfg || nearWaterAt(b, x, y - 1, z);
    uint cls = alchClass(x, z, byWater ? 2u : (y > 110 ? 1u : 0u));
    uint l = fprog[lists + cls];  // a list: kind, n, then n (id, survivor) pairs
    uint kind = fprog[l], n = fprog[l + 1];
    uint pick;
    if (kind == 0u) pick = uint(rngIntB(int(n)));
    else pick = rngIntB(3) == 0 ? 0u : (rngBool() ? 1u : 2u);
    uint id = fprog[l + 2 + pick * 2], surv = fprog[l + 3 + pick * 2];
    if (survives(surv, x, y, z)) setBlock(x, y, z, id, true);
}
[noinline]
bool placeCover(uint cfg, int ox, int oy, int oz) {
    GBox b = gbox(ox, oz);
    int e = coverPick(cfg, ox, oz, rngDouble());
    if (e < 0) return false;
    uint ent = cfg + CV_ENTRIES + uint(e) * 4u;
    uint state = fprog[ent], upper = fprog[ent + 1], surv = fprog[ent + 3];
    int n = intProvider(cfg + 3);
    int spread = int(fprog[cfg + 7]);
    bool nearWaterCfg = fprog[cfg + 9] != 0u;
    int placed = 0;
    for (int k = 0; k < n; k++) {
        int x = ox, z = oz;
        if (k > 0 && spread > 0) {
            float a1 = rngFloat(), a2 = rngFloat();
            x += jroundF((a1 - a2) * float(spread) * 1.3f);
            float b1 = rngFloat(), b2 = rngFloat();
            z += jroundF((b1 - b2) * float(spread) * 1.3f);
        }
        if ((x >> 4) != b.ccx || (z >> 4) != b.ccz) continue;
        if (!maskClear(x, z, 0)) continue;
        int y = trueGround(x, z);
        if (y <= MIN_Y + 1) continue;
        if (!bis(blockAt(x, y, z), B_AIR)) continue;
        if (!substrate(blockAt(x, y - 1, z))) continue;
        if (nearWaterCfg && !nearWaterAt(b, x, y - 1, z)) continue;
        if (!survives(surv, x, y, z)) continue;
        if (upper != 0u) {
            if (!bis(blockAt(x, y + 1, z), B_AIR)) continue;
            setBlock(x, y, z, state, true);      // DoublePlantBlock.placeAt (both halves, air: not waterlogged)
            setBlock(x, y + 1, z, upper, true);
        } else {
            setBlock(x, y, z, state, true);
        }
        placed++;
    }
    if (placed > 0 && rngIntB(4) == 0) alchemy(hdr(H_ALCH), b, ox, oz, nearWaterCfg);
    return placed > 0;
}

// ---- RockFeature (flora.flora_rock) ---------------------------------------------------------------------------
// cfg: [0] stone provider offset, [1] moss provider offset (0 none), [2] moss chance (float), [3..6] size, [7] style
// (0 boulder, 1 slab, 2 standing, 3 rubble, 4 mixed)
// PI_D: Math.PI (atlas_exact.slang)
void rockOne(GBox b, uint cfg, int64_t seed, double cx, double cy, double cz, double rx, double ry,
             double rz, double yaw, double tiltX, double tiltZ) {
    double cs = cosS_N(yaw), sn = sinS_N(yaw);
    int ext = ceilD_N(dmax(rx, rz) + 1.0l);
    int ey = ceilD_N(ry + 2.0l + double(ext) * (absD(tiltX) + absD(tiltZ)));
    uint stone = fprog[cfg], moss = fprog[cfg + 1];
    float mossChance = pfF(cfg + 2);
    int fcx = floorD_N(cx), fcz = floorD_N(cz), fcy = floorD_N(cy);
    for (int dx = -ext; dx <= ext; dx++) {
        for (int dz = -ext; dz <= ext; dz++) {
            int bx = fcx + dx, bz = fcz + dz;
            double fx = double(bx) + 0.5l - cx, fz = double(bz) + 0.5l - cz;
            double u = fx * cs + fz * sn, v = -fx * sn + fz * cs;
            double lift = fx * tiltX + fz * tiltZ;
            int lowest = 2147483647, highest = NONE;
            for (int dy = -ey; dy <= ey; dy++) {
                int by = fcy + dy;
                double fy = double(by) + 0.5l - cy - lift;
                double ur = divD_N(u, rx), fr = divD_N(fy, ry), vr = divD_N(v, rz);
                double d = ur * ur + fr * fr + vr * vr;
                double warp = valueNoise_N(double(bx) * 0.45l + double(by) * 0.31l, double(bz) * 0.45l - double(by) * 0.27l, seed) * 0.28l;
                if (d > 1.0l + warp) continue;
                uint here = gGet(b, bx, by, bz);
                if (!bis(here, B_SOFT) && !bis(here, B_SUBSTRATE) && !bis(here, B_SAND)) continue;
                gSet(b, bx, by, bz, provider(stone));
                lowest = imin(lowest, by);
                highest = imax(highest, by);
            }
            if (lowest == 2147483647) continue;
            for (int fy = lowest - 1, n = 0; n < 4 && bis(gGet(b, bx, fy, bz), B_SOFT); fy--, n++)
                gSet(b, bx, fy, bz, provider(stone));
            if (moss != 0u && rngFloat() < mossChance && bis(gGet(b, bx, highest + 1, bz), B_AIR)) {
                if (rngIntB(3) == 0) gSet(b, bx, highest + 1, bz, fprog[cfg + 8]);  // moss carpet
                else gSet(b, bx, highest, bz, provider(moss));
            }
        }
    }
}
// (the store, | 16) a rock placement in parts: its state in M_ORE (free outside the ores' records): magic, the
// phase (0 the shape next, 1 its blocks), the column cursor, then placeRock's own (config, origin, style, size, seed,
// its stage and the rubble's loop), the rock under way (its rockOne arguments and what rockOne derives from them);
// the shape's bits (a cell inside the rock's warped ellipsoid) in SCR_TREE (free outside the trees' records)
static const uint ROCK_PENDING = 0x524F434Bu;  // "ROCK"
static const uint RK_MAGIC = 0, RK_PHASE = 1, RK_COL = 2, RK_CFG = 3, RK_X = 4, RK_Y = 5, RK_Z = 6, RK_STYLE = 7, RK_SIZE = 8,
                  RK_SEED = 9, RK_STAGE = 11, RK_I = 12, RK_N = 13, RK_EXT = 14, RK_EY = 15, RK_FCX = 16, RK_FCY = 17,
                  RK_FCZ = 18, RK_INLINE = 19, RK_RSEED = 20, RK_D = 22;  // RK_D: doubles (2 words each) from here
static const uint RD_CX = 0, RD_CY = 1, RD_CZ = 2, RD_RX = 3, RD_RY = 4, RD_RZ = 5, RD_TX = 6, RD_TZ = 7, RD_CS = 8, RD_SN = 9,
                  RD_SRX = 10, RD_SRZ = 11, RD_SRR = 12;  // (RD_SR*: the boulder's own rx, rz, rr for its second rock)
static const uint ROCK_BITS = 65536u;  // (the shape's capacity: a larger rock tests its cells inline, as the serial code)
uint rkAt() { return sG.base + SCR_MISC + M_ORE; }
double rkD(uint i) { return scrD(rkAt() + RK_D + i * 2u); }
void rkSetD(uint i, double v) { scrSetD(rkAt() + RK_D + i * 2u, v); }
// rockOne's start: its arguments kept, what it derives from them (no draws), the shape next
[noinline]
void rockIssue(int64_t seed, double cx, double cy, double cz, double rx, double ry, double rz, double yaw, double tiltX, double tiltZ) {
    uint rk = rkAt();
    double cs = cosS_N(yaw), sn = sinS_N(yaw);
    int ext = ceilD_N(dmax(rx, rz) + 1.0l);
    int ey = ceilD_N(ry + 2.0l + double(ext) * (absD(tiltX) + absD(tiltZ)));
    rkSetD(RD_CX, cx); rkSetD(RD_CY, cy); rkSetD(RD_CZ, cz);
    rkSetD(RD_RX, rx); rkSetD(RD_RY, ry); rkSetD(RD_RZ, rz);
    rkSetD(RD_TX, tiltX); rkSetD(RD_TZ, tiltZ); rkSetD(RD_CS, cs); rkSetD(RD_SN, sn);
    scratch[rk + RK_RSEED] = uint(uint64_t(seed) & 0xFFFFFFFFull); scratch[rk + RK_RSEED + 1u] = uint(uint64_t(seed) >> 32);
    scratch[rk + RK_EXT] = uint(ext);
    scratch[rk + RK_EY] = uint(ey);
    scratch[rk + RK_FCX] = uint(floorD_N(cx)); scratch[rk + RK_FCZ] = uint(floorD_N(cz)); scratch[rk + RK_FCY] = uint(floorD_N(cy));
    uint cells = ext < 0 || ey < 0 ? 0u : uint(2 * ext + 1) * uint(2 * ext + 1) * uint(2 * ey + 1);
    scratch[rk + RK_INLINE] = ext < 0 || ey < 0 || cells > ROCK_BITS ? 1u : 0u;
    scratch[rk + RK_COL] = 0u;
    scratch[rk + RK_PHASE] = 0u;
    scratch[rk + RK_MAGIC] = ROCK_PENDING;
}
// whether rockOne's cell (bx, by, bz) is inside its rock (the serial loop's test, a pure function of the arguments)
[noinline]
bool rockInside(int bx, int by, int bz) {
    uint rk = rkAt();
    double cx = rkD(RD_CX), cy = rkD(RD_CY), cz = rkD(RD_CZ), rx = rkD(RD_RX), ry = rkD(RD_RY), rz = rkD(RD_RZ);
    double tiltX = rkD(RD_TX), tiltZ = rkD(RD_TZ), cs = rkD(RD_CS), sn = rkD(RD_SN);
    int64_t seed = int64_t((uint64_t(scratch[rk + RK_RSEED + 1u]) << 32) | uint64_t(scratch[rk + RK_RSEED]));
    double fx = double(bx) + 0.5l - cx, fz = double(bz) + 0.5l - cz;
    double u = fx * cs + fz * sn, v = -fx * sn + fz * cs;
    double lift = fx * tiltX + fz * tiltZ;
    double fy = double(by) + 0.5l - cy - lift;
    double ur = divD_N(u, rx), fr = divD_N(fy, ry), vr = divD_N(v, rz);
    double d = ur * ur + fr * fr + vr * vr;
    double warp = valueNoise_N(double(bx) * 0.45l + double(by) * 0.31l, double(bz) * 0.45l - double(by) * 0.27l, seed) * 0.28l;
    return !(d > 1.0l + warp);
}
// placeRock's control flow after a rock (or at its start): the draws up to the next rock, which it issues; or the end
[noinline]
void rockNext() {
    uint rk = rkAt();
    uint style = scratch[rk + RK_STYLE], stage = scratch[rk + RK_STAGE];
    int x = int(scratch[rk + RK_X]), y = int(scratch[rk + RK_Y]), z = int(scratch[rk + RK_Z]), size = int(scratch[rk + RK_SIZE]);
    int64_t seed = int64_t((uint64_t(scratch[rk + RK_SEED + 1u]) << 32) | uint64_t(scratch[rk + RK_SEED]));
    GBox b = gbox(x, z);
    scratch[rk + RK_MAGIC] = 0u;  // (unless a rock is issued below)
    if (style == 3u) {
        int n = int(scratch[rk + RK_N]);
        for (int i = int(scratch[rk + RK_I]); i < n; i++) {
            double a = rngDouble() * PI_D * 2.0l;
            double d = 1.0l + rngDouble() * double(2 + size);
            int sx = x + int(jroundD(cosS_N(a) * d)), sz = z + int(jroundD(sinS_N(a) * d));
            int sy = floraGround(b, sx, sz, y);
            if (sy == NONE || iabs(sy - y) > 2) continue;
            double rr = 0.7l + rngDouble() * 0.8l;
            double yaw = rngDouble() * 3.0l;
            scratch[rk + RK_I] = uint(i + 1);
            rockIssue(seed + int64_t(i), double(sx) + 0.5l, double(sy) - 0.3l, double(sz) + 0.5l, rr, rr * 0.8l, rr, yaw, 0.0l, 0.0l);
            return;
        }
        return;
    }
    if (stage != 0u) {
        // (the boulder's second rock, a third of the time; the other styles: one rock)
        if (style != 1u && style != 2u && stage == 1u && rngIntB(3) == 0) {
            double rr = rkD(RD_SRR), rx = rkD(RD_SRX), rz = rkD(RD_SRZ);
            double a = rngDouble() * PI_D * 2.0l;
            double ccx = double(x) + 0.5l + cosS_N(a) * rx, ccz = double(z) + 0.5l + sinS_N(a) * rz;
            double yaw = rngDouble() * 3.0l;
            scratch[rk + RK_STAGE] = 2u;
            rockIssue(seed + 99, ccx, double(y) - 0.2l, ccz, rr * 0.5l, rr * 0.45l, rr * 0.5l, yaw, 0.0l, 0.0l);
        }
        return;
    }
    scratch[rk + RK_STAGE] = 1u;
    if (style == 1u) {
        double rr = double(size) + 0.8l;
        double a1 = rr * (1.1l + rngDouble() * 0.5l);
        double a2 = 0.55l + rngDouble() * 0.5l;
        double a3 = rr * (0.7l + rngDouble() * 0.4l);
        double a4 = rngDouble() * PI_D;
        double a5 = (rngDouble() - 0.5l) * 0.7l;
        double a6 = (rngDouble() - 0.5l) * 0.5l;
        rockIssue(seed, double(x) + 0.5l, double(y) - 0.2l, double(z) + 0.5l, a1, a2, a3, a4, a5, a6);
    } else if (style == 2u) {
        double rr = dmax(1.0l, double(size) * 0.6l);
        double a4 = rngDouble() * PI_D;
        double a5 = (rngDouble() - 0.5l) * 0.35l;
        double a6 = (rngDouble() - 0.5l) * 0.35l;
        rockIssue(seed, double(x) + 0.5l, double(y) + double(size) * 0.9l, double(z) + 0.5l, rr, double(size) * 1.4l + 1.5l, rr * 0.75l, a4, a5, a6);
    } else {
        double rr = double(size) + 0.5l;
        double rx = rr * (0.8l + rngDouble() * 0.5l), ry = rr * (0.55l + rngDouble() * 0.4l);
        double rz = rr * (0.7l + rngDouble() * 0.4l);
        double a4 = rngDouble() * PI_D;
        double a5 = (rngDouble() - 0.5l) * 0.3l;
        double a6 = (rngDouble() - 0.5l) * 0.3l;
        rkSetD(RD_SRR, rr); rkSetD(RD_SRX, rx); rkSetD(RD_SRZ, rz);
        rockIssue(seed, double(x) + 0.5l, double(y) - ry * 0.35l, double(z) + 0.5l, rx, ry, rz, a4, a5, a6);
    }
}
// (featBody) the rock under way: rockOne's walk over its columns within `budget` operations (a cell 1, a cell
// written 16), its shape from the bits (or inline), then the rest of the placement; true when the placement ended
[noinline]
bool rockResume(uint budget) {
    uint rk = rkAt(), bits = sG.base + SCR_TREE;
    uint cfg = scratch[rk + RK_CFG];
    int x = int(scratch[rk + RK_X]), z = int(scratch[rk + RK_Z]);
    GBox b = gbox(x, z);
    int ext = int(scratch[rk + RK_EXT]), ey = int(scratch[rk + RK_EY]);
    int fcx = int(scratch[rk + RK_FCX]), fcy = int(scratch[rk + RK_FCY]), fcz = int(scratch[rk + RK_FCZ]);
    bool inl = scratch[rk + RK_INLINE] != 0u;
    uint stone = fprog[cfg], moss = fprog[cfg + 1];
    float mossChance = pfF(cfg + 2);
    uint side = ext < 0 ? 0u : uint(2 * ext + 1), tall = ey < 0 ? 0u : uint(2 * ey + 1), ops = 0u;
    scratch[rk + RK_PHASE] = 1u;
    for (uint col = scratch[rk + RK_COL]; col < side * side; col++) {
        if (ops >= budget) {
            scratch[rk + RK_COL] = col;
            return false;
        }
        int dx = int(col / side) - ext, dz = int(col % side) - ext;
        int bx = fcx + dx, bz = fcz + dz;
        int lowest = 2147483647, highest = NONE;
        for (int dy = -ey; dy <= ey; dy++) {
            int by = fcy + dy;
            ops++;
            uint i = col * tall + uint(dy + ey);
            bool inside = inl ? rockInside(bx, by, bz) : ((scratch[bits + (i >> 5)] >> (i & 31u)) & 1u) != 0u;
            if (!inside) continue;
            uint here = gGet(b, bx, by, bz);
            if (!bis(here, B_SOFT) && !bis(here, B_SUBSTRATE) && !bis(here, B_SAND)) continue;
            ops += 15u;
            gSet(b, bx, by, bz, provider(stone));
            lowest = imin(lowest, by);
            highest = imax(highest, by);
        }
        if (lowest == 2147483647) continue;
        for (int fy = lowest - 1, n = 0; n < 4 && bis(gGet(b, bx, fy, bz), B_SOFT); fy--, n++)
            gSet(b, bx, fy, bz, provider(stone));
        if (moss != 0u && rngFloat() < mossChance && bis(gGet(b, bx, highest + 1, bz), B_AIR)) {
            if (rngIntB(3) == 0) gSet(b, bx, highest + 1, bz, fprog[cfg + 8]);  // moss carpet
            else gSet(b, bx, highest, bz, provider(moss));
        }
    }
    rockNext();
    return scratch[rk + RK_MAGIC] != ROCK_PENDING;
}

[noinline]
bool placeRock(uint cfg, int x, int y, int z) {
#if FEAT_CLOCK
    CLK0(r0);
    bool r = placeRockAt(cfg, x, y, z);
    CLK1(15, r0);
    return r;
}
[noinline]
bool placeRockAt(uint cfg, int x, int y, int z) {
#endif
    GBox b = gbox(x, z);
    if (bis(gGet(b, x, y, z), B_FLUID) || bis(gGet(b, x, y - 1, z), B_FLUID)) return false;
    if (bis(gGet(b, x, y - 1, z), B_AIR) || gReach(b, x, z) < 9) return false;
    if (!maskClear(x, z, 4)) return false;
    uint style = fprog[cfg + 7];
    if (style == 4u) {
        int roll = rngIntB(10);
        style = roll < 6 ? 0u : (roll < 8 ? 1u : (roll < 9 ? 3u : 2u));
    }
    int size = intProvider(cfg + 3);
    int64_t seed = int64_t(uint64_t(pfU64(H_SEED)) + uint64_t(int64_t(x)) * uint64_t(341873128712l) + uint64_t(int64_t(z)) * uint64_t(132897987541l));
    if ((params.store & 16u) != 0u) {
        // (the store, | 16) each rock's cells' shape on many threads (featRockWideMain, right after the round that
        // issued it), its blocks and the draws between rocks at the origin's next rounds (rockResume): placeRock's
        // control flow as a state machine, the same draws and writes in the same order
        uint rk = rkAt();
        scratch[rk + RK_CFG] = cfg;
        scratch[rk + RK_X] = uint(x); scratch[rk + RK_Y] = uint(y); scratch[rk + RK_Z] = uint(z);
        scratch[rk + RK_STYLE] = style;
        scratch[rk + RK_SIZE] = uint(size);
        scratch[rk + RK_SEED] = uint(uint64_t(seed) & 0xFFFFFFFFull); scratch[rk + RK_SEED + 1u] = uint(uint64_t(seed) >> 32);
        scratch[rk + RK_STAGE] = 0u;
        scratch[rk + RK_I] = 0u;
        scratch[rk + RK_N] = style == 3u ? uint(3 + rngIntB(4)) : 0u;
        rockNext();
        return true;
    }
    if (style == 3u) {
        int n = 3 + rngIntB(4);
        for (int i = 0; i < n; i++) {
            double a = rngDouble() * PI_D * 2.0l;
            double d = 1.0l + rngDouble() * double(2 + size);
            int sx = x + int(jroundD(cosS_N(a) * d)), sz = z + int(jroundD(sinS_N(a) * d));
            int sy = floraGround(b, sx, sz, y);
            if (sy == NONE || iabs(sy - y) > 2) continue;
            double rr = 0.7l + rngDouble() * 0.8l;
            double yaw = rngDouble() * 3.0l;
            rockOne(b, cfg, seed + int64_t(i), double(sx) + 0.5l, double(sy) - 0.3l, double(sz) + 0.5l, rr, rr * 0.8l, rr, yaw, 0.0l, 0.0l);
        }
    } else if (style == 1u) {
        double rr = double(size) + 0.8l;
        double a1 = rr * (1.1l + rngDouble() * 0.5l);
        double a2 = 0.55l + rngDouble() * 0.5l;
        double a3 = rr * (0.7l + rngDouble() * 0.4l);
        double a4 = rngDouble() * PI_D;
        double a5 = (rngDouble() - 0.5l) * 0.7l;
        double a6 = (rngDouble() - 0.5l) * 0.5l;
        rockOne(b, cfg, seed, double(x) + 0.5l, double(y) - 0.2l, double(z) + 0.5l, a1, a2, a3, a4, a5, a6);
    } else if (style == 2u) {
        double rr = dmax(1.0l, double(size) * 0.6l);
        double a4 = rngDouble() * PI_D;
        double a5 = (rngDouble() - 0.5l) * 0.35l;
        double a6 = (rngDouble() - 0.5l) * 0.35l;
        rockOne(b, cfg, seed, double(x) + 0.5l, double(y) + double(size) * 0.9l, double(z) + 0.5l, rr, double(size) * 1.4l + 1.5l, rr * 0.75l, a4, a5, a6);
    } else {
        double rr = double(size) + 0.5l;
        double rx = rr * (0.8l + rngDouble() * 0.5l), ry = rr * (0.55l + rngDouble() * 0.4l);
        double rz = rr * (0.7l + rngDouble() * 0.4l);
        double a4 = rngDouble() * PI_D;
        double a5 = (rngDouble() - 0.5l) * 0.3l;
        double a6 = (rngDouble() - 0.5l) * 0.3l;
        rockOne(b, cfg, seed, double(x) + 0.5l, double(y) - ry * 0.35l, double(z) + 0.5l, rx, ry, rz, a4, a5, a6);
        if (rngIntB(3) == 0) {
            double a = rngDouble() * PI_D * 2.0l;
            double ccx = double(x) + 0.5l + cosS_N(a) * rx, ccz = double(z) + 0.5l + sinS_N(a) * rz;
            double yaw = rngDouble() * 3.0l;
            rockOne(b, cfg, seed + 99, ccx, double(y) - 0.2l, ccz, rr * 0.5l, rr * 0.45l, rr * 0.5l, yaw, 0.0l, 0.0l);
        }
    }
    return true;
}


// ---- SnowAndFreezeFeature (freeze_top_layer): ice on still water, snow layers where the biome is cold enough --------
// cfg: [0] ice, [1] snow layer, [2] the snow layer's survivor, [3] block light below 10 where nothing is loaded.
// The biome table (H_BIOMETAB, 4 words a biome): base temperature (float), precipitation, frozen modifier, pad. The
// temperature noise of a column and the frozen modifier's verdict come with the pages (aux).
[noinline]
float biomeTemperature(int b, int x, int y, int z) {
    uint e = hdr(H_BIOMETAB) + uint(imax(b, 0)) * 4u;
    float base = asfloat(fprog[e]);
    int slot = slotOf(x >> 4, z >> 4);
    if (slot < 0) { flag(FL_OUTSIDE); return 1.0f; }
    uint c = uint(z & 15) * 16u + uint(x & 15);
    if (fprog[e + 2] != 0u && ((chunkAux[uint(slot) * AUX_W + AUX_FROZEN + (c >> 5)] >> (c & 31u)) & 1u) != 0u) base = 0.2f;
    int snowLevel = int(hdr(H_SEA)) + 17;
    if (y > snowLevel) {
        float v = asfloat(chunkAux[uint(slot) * AUX_W + AUX_TEMP + c]);
        return base - divF_N((v + float(y) - float(snowLevel)) * 0.05f, 40.0f);
    }
    return base;
}
[noinline]
bool placeFreeze(uint cfg, int ox, int oy, int oz) {
    bool light = fprog[cfg + 3] != 0u;
    uint ice = fprog[cfg], snow = fprog[cfg + 1], surv = fprog[cfg + 2];
    for (int dx = 0; dx < 16; dx++)
        for (int dz = 0; dz < 16; dz++) {
            int x = ox + dx, z = oz + dz;
            int y = heightAt(HM_MOTION_BLOCKING, x, z);
            int b = biomeAt(x, y, z);
            uint e = hdr(H_BIOMETAB) + uint(imax(b, 0)) * 4u;
            // shouldFreeze(below, false)
            if (!(biomeTemperature(b, x, y - 1, z) >= 0.15f) && y - 1 >= MIN_Y && y - 1 <= MAX_Y && light) {
                uint bs = blockAt(x, y - 1, z);
                if (bis(bs, B_WATER) && (bflags2(bs) & F2_LIQUID) != 0u) setBlock(x, y - 1, z, ice, true);
            }
            // shouldSnow(top)
            if (fprog[e + 1] != 0u && !(biomeTemperature(b, x, y, z) >= 0.15f) && y >= MIN_Y && y <= MAX_Y && light) {
                uint st = blockAt(x, y, z);
                if ((bis(st, B_AIR) || bis(st, B_SNOW_LAYER)) && survives(surv, x, y, z)) {
                    setBlock(x, y, z, snow, true);
                    uint below = blockAt(x, y - 1, z);
                    if ((bflags2(below) & F2_SNOWY) != 0u) setBlock(x, y - 1, z, btrans(below, T_SNOWY), true);
                }
            }
        }
    return true;
}

// ==== trees (flora.flora_tree: FloraTreeFeature, TreeBuilder, TreeShapes) ========================================
// The builder's sets live in the origin's scratch, one tree at a time. The leaves and the trimmings are kept exactly as
// fastutil's Long2ObjectOpenHashMap keeps them (same table size, hash, probing, rehash and removal shifts), because the
// tree draws randoms while it walks them in table order (tint, hangVines, drape) and sorts the trimmings stably.
// BlockPos.asLong keys: x 26 bits << 38, z 26 bits << 12, y 12 bits.
[noinline]
int64_t posKey(int x, int y, int z) {
    uint64_t k = (uint64_t(int64_t(x)) & 0x3FFFFFFu) << 38u;
    k = k | (uint64_t(int64_t(y)) & 0xFFFu);
    k = k | ((uint64_t(int64_t(z)) & 0x3FFFFFFu) << 12u);
    return int64_t(k);
}
int keyX(int64_t k) { return int(k >> 38); }
int keyY(int64_t k) { return int((k << 52) >> 52); }
int keyZ(int64_t k) { return int((k << 26) >> 38); }
uint64_t hmix(int64_t x) {
    uint64_t h = uint64_t(x) * ((uint64_t(0x9E3779B9u) << 32) | uint64_t(0x7F4A7C15u));
    h = h ^ (h >> 32u);
    return h ^ (h >> 16u);
}
// a map in scratch: [0] n, [1] size, [2] maxFill, [3] minN, [4] cap (largest n), then cap slots of 3 words (key lo,
// key hi, value); the shared temp area holds a table during a rehash
static const uint FM_HDR = 8;
struct FMap {
    uint at;   // absolute scratch offset of the header
    uint cap;
};
uint fmN(FMap m) { return scratch[m.at]; }
uint fmSize(FMap m) { return scratch[m.at + 1]; }
int64_t fmKey(FMap m, uint pos) {
    uint w = m.at + FM_HDR + pos * 3u;
    return int64_t((uint64_t(scratch[w + 1]) << 32) | uint64_t(scratch[w]));
}
uint fmVal(FMap m, uint pos) { return scratch[m.at + FM_HDR + pos * 3u + 2u]; }
[noinline]
void fmSetSlot(FMap m, uint pos, int64_t k, uint v) {
    uint w = m.at + FM_HDR + pos * 3u;
    scratch[w] = uint(uint64_t(k) & 0xFFFFFFFFu);
    scratch[w + 1] = uint(uint64_t(k) >> 32u);
    scratch[w + 2] = v;
}
void fmSetVal(FMap m, uint pos, uint v) { scratch[m.at + FM_HDR + pos * 3u + 2u] = v; }
[noinline]
uint nextPow2(uint v) {
    uint p = 1u;
    while (p < v) p = p << 1;
    return p;
}
// HashCommon.arraySize(expected, 0.75f): max(2, nextPowerOfTwo(ceil(expected / f)))
[noinline]
uint arraySize(uint expected, inout uint bad) {
    float q = divF(float(expected), 0.75f, bad);
    uint c = uint(ceilF(q, bad));
    uint p = nextPow2(c);
    return p < 2u ? 2u : p;
}
[noinline]
uint maxFillOf(uint n) {
    uint none = 0u;
    uint f = uint(ceilF(float(n) * 0.75f, none));
    return f < n - 1u ? f : n - 1u;  // (builtin min / max are extended instructions spv_exact refuses)
}
// new Long2ObjectOpenHashMap(expected)
[noinline]
void fmInit(FMap m, uint expected) {
    uint n = arraySize_N(expected);
    if (n > m.cap) { flag(FL_TABLE_FULL); n = m.cap; }
    scratch[m.at] = n;
    scratch[m.at + 1] = 0u;
    scratch[m.at + 2] = maxFillOf(n);
    scratch[m.at + 3] = n;
    for (uint i = 0; i < n; i++) fmSetSlot(m, i, 0, 0u);
}
// the table into the temp area, then reinserted in fastutil's order (old index downward)
[noinline]
void fmRehash(FMap m, uint newN, uint tmp) {
    uint n = fmN(m);
    if (newN > m.cap) { flag(FL_TABLE_FULL); return; }
    for (uint i = 0; i < n * 3u; i++) scratch[tmp + i] = scratch[m.at + FM_HDR + i];
    for (uint i = 0; i < newN; i++) fmSetSlot(m, i, 0, 0u);
    uint mask = newN - 1u;
    for (int i = int(n) - 1; i >= 0; i--) {
        uint w = tmp + uint(i) * 3u;
        int64_t k = int64_t((uint64_t(scratch[w + 1]) << 32) | uint64_t(scratch[w]));
        if (k == 0) continue;
        uint pos = uint(hmix(k) & 0xFFFFFFFFu) & mask;
        while (fmKey(m, pos) != 0) pos = (pos + 1u) & mask;
        fmSetSlot(m, pos, k, scratch[w + 2]);
    }
    scratch[m.at] = newN;
    scratch[m.at + 2] = maxFillOf(newN);
}
// the slot of a key, or -1
[noinline]
int fmFind(FMap m, int64_t k) {
    uint n = fmN(m), mask = n - 1u;
    uint pos = uint(hmix(k) & 0xFFFFFFFFu) & mask;
    for (uint guard = 0; guard <= n; guard++) {
        int64_t c = fmKey(m, pos);
        if (c == 0) return -1;
        if (c == k) return int(pos);
        pos = (pos + 1u) & mask;
    }
    return -1;
}
bool fmHas(FMap m, int64_t k) { return fmFind(m, k) >= 0; }
uint fmGet(FMap m, int64_t k, uint dflt) {
    int p = fmFind(m, k);
    return p < 0 ? dflt : fmVal(m, uint(p));
}
// put: true when the key is new
[noinline]
bool fmPut(FMap m, int64_t k, uint v, uint tmp) {
    if (k == 0) { flag(FL_UNSUPPORTED); return false; }  // fastutil's null key: not reproduced (BlockPos 0, 0, 0)
    uint n = fmN(m), mask = n - 1u;
    uint pos = uint(hmix(k) & 0xFFFFFFFFu) & mask;
    for (uint guard = 0; guard <= n; guard++) {
        int64_t c = fmKey(m, pos);
        if (c == k) { fmSetVal(m, pos, v); return false; }
        if (c == 0) break;
        pos = (pos + 1u) & mask;
    }
    fmSetSlot(m, pos, k, v);
    uint size = fmSize(m);
    scratch[m.at + 1] = size + 1u;
    if (size >= scratch[m.at + 2]) fmRehash(m, arraySize_N(size + 2u), tmp);
    return true;
}
[noinline]
void fmShiftKeys(FMap m, uint pos) {
    uint mask = fmN(m) - 1u;
    for (uint guard = 0; guard < 1000000u; guard++) {
        uint last = pos;
        pos = (last + 1u) & mask;
        int64_t curr;
        for (;;) {
            curr = fmKey(m, pos);
            if (curr == 0) { fmSetSlot(m, last, 0, 0u); return; }
            uint slot = uint(hmix(curr) & 0xFFFFFFFFu) & mask;
            if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) break;
            pos = (pos + 1u) & mask;
        }
        fmSetSlot(m, last, curr, fmVal(m, pos));
    }
}
[noinline]
void fmRemove(FMap m, int64_t k, uint tmp) {
    int p = fmFind(m, k);
    if (p < 0) return;
    scratch[m.at + 1] = fmSize(m) - 1u;
    fmShiftKeys(m, uint(p));
    uint n = fmN(m);
    if (n > scratch[m.at + 3] && fmSize(m) < scratch[m.at + 2] / 4u && n > 16u) fmRehash(m, n / 2u, tmp);
}

// the tree's region of scratch (TREE_W words): the maps, the temp area, the queue, the trimmings' sort list
static const uint TM_LEAVES = 0, TC_LEAVES = 16384, TM_LOGS = FM_HDR + 16384 * 3, TC_LOGS = 8192,
                  TM_EXTRAS = TM_LOGS + FM_HDR + 8192 * 3, TC_EXTRAS = 4096,
                  TM_ANCHORS = TM_EXTRAS + FM_HDR + 4096 * 3, TC_ANCHORS = 2048,
                  TM_ROOT = TM_ANCHORS + FM_HDR + 2048 * 3, TC_ROOT = 8192,       // writable (1) and rooted (2) logs
                  TM_KEPT = TM_ROOT + FM_HDR + 8192 * 3, TC_KEPT = 16384,         // kept leaves (value: distance)
                  TM_PLACED = TM_KEPT + FM_HDR + 16384 * 3, TC_PLACED = 4096,
                  T_TMP = TM_PLACED + FM_HDR + 4096 * 3,                          // 16384 * 3
                  T_QUEUE = T_TMP + 16384 * 3, TQ_CAP = 16384,                    // 2 words a key
                  T_SORT = T_QUEUE + 16384 * 2,                                   // 4096 * 3
                  T_LAST = T_SORT + 4096 * 3;                                     // the chain's last point
// (TREE_W = T_LAST + 8)

// block transforms (block table words 2..15): axis x, y, z; waterlogged true, false; leaf distance 1..7 (7 persistent);
// snowy
static const uint T_AX = 0, T_WL = 3, T_DIST = 5, T_SNOWY = 12;
uint axisOf(uint id, uint axis) { return btrans(id, T_AX + axis); }  // 0 x, 1 y, 2 z

struct TreeB {
    FMap leaves, logs, extras, anchors, root, kept, placed;
    uint tmp, queue, sortAt;
    GBox box;
};
static TreeB sB;  // the tree being grown, thread-private
[noinline]
TreeB treeB(GBox box) {
    TreeB b;
    uint t = sG.base + SCR_TREE;
    b.leaves.at = t + TM_LEAVES; b.leaves.cap = TC_LEAVES;
    b.logs.at = t + TM_LOGS; b.logs.cap = TC_LOGS;
    b.extras.at = t + TM_EXTRAS; b.extras.cap = TC_EXTRAS;
    b.anchors.at = t + TM_ANCHORS; b.anchors.cap = TC_ANCHORS;
    b.root.at = t + TM_ROOT; b.root.cap = TC_ROOT;
    b.kept.at = t + TM_KEPT; b.kept.cap = TC_KEPT;
    b.placed.at = t + TM_PLACED; b.placed.cap = TC_PLACED;
    b.tmp = t + T_TMP;
    b.queue = t + T_QUEUE;
    b.sortAt = t + T_SORT;
    scratch[t + T_LAST] = 0u;
    b.box = box;
    return b;
}

// ---- TreeBuilder ----
[noinline]
void tLog(int x, int y, int z, uint state) {
    int64_t k = posKey(x, y, z);
    fmPut(sB.logs, k, state, sB.tmp);
    fmRemove(sB.leaves, k, sB.tmp);
}
void tAnchor(int x, int y, int z, uint state) {
    tLog(x, y, z, state);
    fmPut(sB.anchors, posKey(x, y, z), 1u, sB.tmp);
}
bool tIsLog(int x, int y, int z) { return fmHas(sB.logs, posKey(x, y, z)); }
[noinline]
void tSink(int x, int y, int z, uint state, int maxD) {
    for (int d = 1; d <= maxD; d++) {
        uint here = gGet(sB.box, x, y - d, z);
        bool soft = bis(here, B_SOFT) || bis(here, B_WATER_BLOCK);
        tAnchor(x, y - d, z, state);
        if (!soft) break;
    }
}
[noinline]
void tDisc(double cx, int y, double cz, double r, uint state) {
    if (r < 0.75l) {
        tLog(floorD_N(cx), y, floorD_N(cz), state);
        return;
    }
    int ri = ceilD_N(r);
    int bx = floorD_N(cx), bz = floorD_N(cz);
    for (int dx = -ri; dx <= ri; dx++)
        for (int dz = -ri; dz <= ri; dz++) {
            double fx = double(bx + dx) + 0.5l - cx, fz = double(bz + dz) + 0.5l - cz;
            if (fx * fx + fz * fz <= r * r + 0.15l) tLog(bx + dx, y, bz + dz, state);
        }
}
[noinline]
void tChain(int x, int y, int z, uint state, uint base) {
    uint last = sG.base + SCR_TREE + T_LAST;  // has, x, y, z (in scratch: see sRA)
    if (scratch[last] != 0u) {
        int cx = int(scratch[last + 1u]), cy = int(scratch[last + 2u]), cz = int(scratch[last + 3u]);
        for (int guard = 0; guard < 4096 && iabs(x - cx) + iabs(y - cy) + iabs(z - cz) > 1; guard++) {
            if (x != cx) {
                cx += signumI(x - cx);
                tLog(cx, cy, cz, axisOf(base, 0u));
            } else if (z != cz) {
                cz += signumI(z - cz);
                tLog(cx, cy, cz, axisOf(base, 2u));
            } else {
                cy += signumI(y - cy);
                tLog(cx, cy, cz, axisOf(base, 1u));
            }
        }
    }
    if (!tIsLog(x, y, z)) tLog(x, y, z, state);
    scratch[last + 1u] = uint(x); scratch[last + 2u] = uint(y); scratch[last + 3u] = uint(z);
    scratch[last] = 1u;
}
[noinline]
void tBranch(double x0, double y0, double z0, double x1, double y1, double z1, double r0, double r1, uint log) {
    double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
    double len = dmax(absD2(dx), dmax(absD2(dy), absD2(dz)));
    int steps = imax(1, ceilD_N(len * 1.2l));
    uint axis = absD2(dy) >= dmax(absD2(dx), absD2(dz)) ? 1u : (absD2(dx) >= absD2(dz) ? 0u : 2u);
    uint state = axisOf(log, axis);
    scratch[sG.base + SCR_TREE + T_LAST] = 0u;
    for (int s = 0; s <= steps; s++) {
        double t = divD_N(double(s), double(steps));
        double x = x0 + dx * t, y = y0 + dy * t, z = z0 + dz * t;
        double r = r0 + (r1 - r0) * t;
        if (r >= 0.75l) {
            int ri = ceilD_N(r);
            int fx = floorD_N(x), fy = floorD_N(y), fz = floorD_N(z);
            for (int ox = -ri; ox <= ri; ox++)
                for (int oy = -ri; oy <= ri; oy++)
                    for (int oz = -ri; oz <= ri; oz++)
                        if (double(ox * ox + oy * oy + oz * oz) <= r * r + 0.2l) tLog(fx + ox, fy + oy, fz + oz, state);
        } else {
            tChain(floorD_N(x), floorD_N(y), floorD_N(z), state, log);
        }
    }
    scratch[sG.base + SCR_TREE + T_LAST] = 0u;
}
void tLeaf(int x, int y, int z, uint state) {
    int64_t k = posKey(x, y, z);
    if (!fmHas(sB.logs, k)) fmPut(sB.leaves, k, state, sB.tmp);
}
uint bitsMix(double a, double b, double c) {
    uint lo, hi;
    asuint(a, lo, hi);
    uint64_t h = (uint64_t(hi) << 32) | uint64_t(lo);
    asuint(b, lo, hi);
    h = h * 31u + ((uint64_t(hi) << 32) | uint64_t(lo));
    asuint(c, lo, hi);
    h = h * 31u + ((uint64_t(hi) << 32) | uint64_t(lo));
    return uint(h & 0xFFFFFFFFu);
}
[ForceInline]
void tMark(uint stage, double a, double b, double c) {
    if (!TREE_TRACE) return;
}
[noinline]
void tClump(double cx, double cy, double cz, double rx, double ry, double rz, uint state, double ragged) {
    int x0 = floorD_N(cx - rx), x1 = floorD_N(cx + rx);
    int y0 = floorD_N(cy - ry), y1 = floorD_N(cy + ry);
    int z0 = floorD_N(cz - rz), z1 = floorD_N(cz + rz);
    for (int x = x0; x <= x1; x++)
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++) {
                double fx = divD_N(double(x) + 0.5l - cx, rx), fy = divD_N(double(y) + 0.5l - cy, ry), fz = divD_N(double(z) + 0.5l - cz, rz);
                double d = fx * fx + fy * fy + fz * fz;
                if (d > 1.0l) continue;
                if (d > 0.45l && double(rngFloat()) * ragged > (1.0l - d) * 0.9l) continue;
                tLeaf(x, y, z, state);
            }
}
[noinline]
void tPad(double cx, int y, double cz, double rx, double rz, uint state, double ragged, bool domed) {
    int x0 = floorD_N(cx - rx), x1 = floorD_N(cx + rx), z0 = floorD_N(cz - rz), z1 = floorD_N(cz + rz);
    for (int x = x0; x <= x1; x++)
        for (int z = z0; z <= z1; z++) {
            double fx = divD_N(double(x) + 0.5l - cx, rx), fz = divD_N(double(z) + 0.5l - cz, rz);
            double d = fx * fx + fz * fz;
            if (d > 1.0l) continue;
            if (d > 0.5l && double(rngFloat()) * ragged > (1.0l - d)) continue;
            tLeaf(x, y, z, state);
            if (domed && d < 0.3l) tLeaf(x, y + 1, z, state);
        }
}
[noinline]
void tTint(uint from, uint to, double chance, bool topOnly) {
    uint n = fmN(sB.leaves);
    for (int pos = int(n) - 1; pos >= 0; pos--) {
        int64_t k = fmKey(sB.leaves, uint(pos));
        if (k == 0) continue;
        if (fmVal(sB.leaves, uint(pos)) != from) continue;
        if (topOnly && fmHas(sB.leaves, posKey(keyX(k), keyY(k) + 1, keyZ(k)))) continue;
        if (rngDouble() < chance) fmSetVal(sB.leaves, uint(pos), to);
    }
}
void tExtra(int x, int y, int z, uint state) {
    int64_t k = posKey(x, y, z);
    if (!fmHas(sB.logs, k) && !fmHas(sB.leaves, k)) fmPut(sB.extras, k, state, sB.tmp);
}
// the leaves' keys as toLongArray gives them (table order), into the queue area
[noinline]
uint tLeafKeys() {
    uint n = fmN(sB.leaves), c = 0;
    for (int pos = int(n) - 1; pos >= 0; pos--) {
        int64_t k = fmKey(sB.leaves, uint(pos));
        if (k == 0) continue;
        if (c < TQ_CAP) {
            scratch[sB.queue + c * 2u] = uint(uint64_t(k) & 0xFFFFFFFFu);
            scratch[sB.queue + c * 2u + 1u] = uint(uint64_t(k) >> 32u);
        }
        c++;
    }
    return c < TQ_CAP ? c : TQ_CAP;
}
int64_t qKey(uint i) { return int64_t((uint64_t(scratch[sB.queue + i * 2u + 1u]) << 32) | uint64_t(scratch[sB.queue + i * 2u])); }
// Direction.Plane.HORIZONTAL: north, east, south, west
int hdx(uint d) { return d == 1u ? 1 : (d == 3u ? -1 : 0); }
int hdz(uint d) { return d == 0u ? -1 : (d == 2u ? 1 : 0); }
// tree constants (TC_* words of the program's tree table)
static const uint TCN_VINE = 0;      // 4: the vine hanging on the face opposite each horizontal direction
static const uint TCN_COCOA = 4;     // 12: facing opposite direction d, age a: 4 + d * 3 + a
static const uint TCN_MOSS = 16, TCN_BROWN = 17, TCN_RED = 18, TCN_COARSE = 19, TCN_ROOTED = 20;
static const uint TCN_BAMBOO = 21;   // 6: leaves (none, small, large) x stage (0, 1): 21 + lv * 2 + stage
static const uint TCN_MINX = 27, TCN_MAXX = 28, TCN_MINZ = 29, TCN_MAXZ = 30;  // Tamriel's bounds
uint tconst(uint i) { return fprog[hdr(H_TREES) + i]; }
[noinline]
void tHangVines(double chance, int maxLength) {
    if (chance <= 0.0l) return;
    uint n = tLeafKeys();
    for (uint i = 0; i < n; i++) {
        int64_t k = qKey(i);
        if (rngDouble() >= chance) continue;
        uint d = uint(rngIntB(4));
        int sx = keyX(k) + hdx(d), sy = keyY(k), sz = keyZ(k) + hdz(d);
        int64_t side = posKey(sx, sy, sz);
        if (fmHas(sB.leaves, side) || fmHas(sB.logs, side)) continue;
        uint vine = tconst(TCN_VINE + d);
        int len = 1 + rngIntB(maxLength);
        for (int j = 0; j < len; j++) {
            int64_t p = posKey(sx, sy - j, sz);
            if (fmHas(sB.leaves, p) || fmHas(sB.logs, p)) break;
            tExtra(sx, sy - j, sz, vine);
        }
    }
}
[noinline]
void tDrape(double cx, double cz, double minDist, double chance, int maxLength) {
    uint n = tLeafKeys();
    for (uint i = 0; i < n; i++) {
        int64_t k = qKey(i);
        int x = keyX(k), y = keyY(k), z = keyZ(k);
        double dx = double(x) + 0.5l - cx, dz = double(z) + 0.5l - cz;
        if (dx * dx + dz * dz < minDist * minDist) continue;
        if (fmHas(sB.leaves, posKey(x, y - 1, z)) || fmHas(sB.logs, posKey(x, y - 1, z))) continue;
        if (rngDouble() >= chance) continue;
        uint state = fmGet(sB.leaves, k, 0u);
        int len = 1 + rngIntB(maxLength);
        for (int j = 1; j <= len; j++) {
            if (fmHas(sB.logs, posKey(x, y - j, z))) break;
            fmPut(sB.leaves, posKey(x, y - j, z), state, sB.tmp);
        }
    }
}
[noinline]
void tTrunkVines(int x, int y0, int y1, int z, double chance) {
    for (uint d = 0; d < 4u; d++) {
        if (rngDouble() >= chance) continue;
        uint vine = tconst(TCN_VINE + d);
        int top = y0 + rngIntB(imax(1, y1 - y0));
        for (int y = y0; y <= top; y++) tExtra(x + hdx(d), y, z + hdz(d), vine);
    }
}
[noinline]
uint waterlog(uint state, uint here) {
    if (!bis(state, B_WATERLOGGABLE)) return state;
    bool wl = bis(here, B_WATER) && bis(here, B_SOURCE);
    return btrans(state, T_WL + (wl ? 0u : 1u));
}
// the queue (ring)
[noinline]
void qPush(inout uint tail, int64_t k) {
    uint i = tail % TQ_CAP;
    scratch[sB.queue + i * 2u] = uint(uint64_t(k) & 0xFFFFFFFFu);
    scratch[sB.queue + i * 2u + 1u] = uint(uint64_t(k) >> 32u);
    tail++;
}
[noinline]
int64_t qPop(inout uint head) {
    uint i = head % TQ_CAP;
    head++;
    return int64_t((uint64_t(scratch[sB.queue + i * 2u + 1u]) << 32) | uint64_t(scratch[sB.queue + i * 2u]));
}
// The commit as resumable phases (the store, params.store | 4: a tree's commit spread over rounds within an operation
// budget, so no dispatch waits for a whole tree; otherwise all at once, the same code): its state in M_LAKE (the
// lakes' words: no lake runs while a tree is pending), TC_* below. The phases are the serial ones, in order, each
// loop resumed at its cursor and each breadth-first search at its queue's head and tail.
static const uint TREE_PENDING = 0x54524545u;  // TC_MAGIC: a tree's commit is under way
static const uint TC_MAGIC = 0, TC_PHASE = 1, TC_X = 2, TC_Y = 3, TC_Z = 4, TC_POS = 5, TC_HEAD = 6, TC_TAIL = 7, TC_ANY = 8,
                  TC_C = 9, TC_RES = 10;
uint tcAt() { return sG.base + SCR_MISC + M_LAKE; }
// begins a commit at (baseX, baseY, baseZ): the phases from the first
void tCommitBegin(int baseX, int baseY, int baseZ) {
    uint h = tcAt();
    scratch[h + TC_PHASE] = 0u;
    scratch[h + TC_X] = uint(baseX); scratch[h + TC_Y] = uint(baseY); scratch[h + TC_Z] = uint(baseZ);
    scratch[h + TC_POS] = 0u; scratch[h + TC_HEAD] = 0u; scratch[h + TC_TAIL] = 0u; scratch[h + TC_ANY] = 0u; scratch[h + TC_C] = 0u;
    scratch[h + TC_MAGIC] = TREE_PENDING;
}
// runs the commit's phases until it ends or `budget` operations are spent (a loop's step, a neighbour probed);
// returns true when it has ended (TC_MAGIC cleared)
[noinline]
bool tCommitStep(uint budget) {
    uint h = tcAt();
    GBox box = sB.box;
    int baseX = int(scratch[h + TC_X]), baseY = int(scratch[h + TC_Y]), baseZ = int(scratch[h + TC_Z]);
    uint phase = scratch[h + TC_PHASE];
    int pos = int(scratch[h + TC_POS]);
    uint head = scratch[h + TC_HEAD], tail = scratch[h + TC_TAIL];
    uint ops = 0;
    bool done = false;
    for (int guard = 0; guard < 64 && !done && ops < budget; guard++) {
#if FEAT_CLOCK
        uint64_t pc0 = clockARB();
        uint ph0 = phase;
#endif
        if (phase == 0u) {
            uint atBase = gGet(box, baseX, baseY, baseZ);
            if ((!bis(atBase, B_SOFT) && !tIsLog(baseX, baseY, baseZ)) || bis(atBase, B_LOGS)) { scratch[h + TC_RES] = 0u; done = true; break; }
            // logs that can be written (value 1), then rooted (value 3)
            fmInit(sB.root, 16u);
            pos = int(fmN(sB.logs)) - 1;
            phase = 1u;
        } else if (phase == 1u) {
            for (; pos >= 0 && ops < budget; pos--, ops++) {
                int64_t k = fmKey(sB.logs, uint(pos));
                if (k == 0) continue;
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                if (!gContains(box, x, y, z)) continue;
                uint here = gGet(box, x, y, z);
                if (bis(here, B_SOFT) || (fmHas(sB.anchors, k) && bis(here, B_GROUND))) fmPut(sB.root, k, 1u, sB.tmp);
            }
            if (pos < 0) {
                head = 0u; tail = 0u;
                scratch[h + TC_ANY] = 0u;
                pos = int(fmN(sB.root)) - 1;
                phase = 2u;
            }
        } else if (phase == 2u) {
            for (; pos >= 0 && ops < budget; pos--, ops++) {
                int64_t k = fmKey(sB.root, uint(pos));
                if (k == 0) continue;
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                if (fmHas(sB.root, posKey(x, y - 1, z))) continue;
                if (!gContains(box, x, y - 1, z)) continue;
                uint below = gGet(box, x, y - 1, z);
                if (!bis(below, B_SOFT) && !bis(below, B_LOGS) && bis(below, B_SOLID) && fmVal(sB.root, uint(pos)) == 1u) {
                    fmSetVal(sB.root, uint(pos), 3u);
                    qPush(tail, k);
                    scratch[h + TC_ANY] = 1u;
                }
            }
            if (pos < 0) {
                if (scratch[h + TC_ANY] == 0u) { scratch[h + TC_RES] = 0u; done = true; break; }
                phase = 3u;
            }
        } else if (phase == 3u) {
            bool full = false;
            while (head != tail && ops < budget) {
                int64_t k = qPop(head);
                ops += 26u;
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    int p = fmFind(sB.root, posKey(x + dx, y + dy, z + dz));
                    if (p >= 0 && fmVal(sB.root, uint(p)) == 1u) {
                        fmSetVal(sB.root, uint(p), 3u);
                        qPush(tail, fmKey(sB.root, uint(p)));
                    }
                }
                if (tail - head > TQ_CAP) { flag(FL_TABLE_FULL); full = true; break; }
            }
            if (head == tail || full) {
                pos = int(fmN(sB.root)) - 1;
                phase = 4u;
            }
        } else if (phase == 4u) {
            // write the rooted logs
            for (; pos >= 0 && ops < budget; pos--, ops++) {
                int64_t k = fmKey(sB.root, uint(pos));
                if (k == 0 || fmVal(sB.root, uint(pos)) != 3u) continue;
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                uint here = gGet(box, x, y, z);
                gSet(box, x, y, z, waterlog(fmGet(sB.logs, k, 0u), here));
            }
            if (pos < 0) {
                // leaves reachable from the written logs through leaves (value: distance, 99 unknown)
                fmInit(sB.kept, 16u);
                head = 0u; tail = 0u;
                pos = int(fmN(sB.root)) - 1;
                phase = 5u;
            }
        } else if (phase == 5u || phase == 7u) {
            // the written logs into the queue (the reach's seeds, then the distances')
            for (; pos >= 0 && ops < budget; pos--, ops++) {
                int64_t k = fmKey(sB.root, uint(pos));
                if (k != 0 && fmVal(sB.root, uint(pos)) == 3u) qPush(tail, k);
            }
            if (pos < 0) phase = phase + 1u;
        } else if (phase == 6u) {
            bool full = false;
            while (head != tail && ops < budget) {
                int64_t k = qPop(head);
                ops += 26u;
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    int64_t n = posKey(x + dx, y + dy, z + dz);
                    if (fmHas(sB.leaves, n) && gContains(box, x + dx, y + dy, z + dz) && !fmHas(sB.kept, n)) {
                        fmPut(sB.kept, n, 99u, sB.tmp);
                        qPush(tail, n);
                    }
                }
                if (tail - head > TQ_CAP) { flag(FL_TABLE_FULL); full = true; break; }
            }
            if (head == tail || full) {
                // leaf distances: breadth first from the logs through the kept leaves (logs 0)
                head = 0u; tail = 0u;
                pos = int(fmN(sB.root)) - 1;
                phase = 7u;
            }
        } else if (phase == 8u) {
            bool full = false;
            while (head != tail && ops < budget) {
                int64_t k = qPop(head);
                ops += 6u;
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                int kp = fmFind(sB.kept, k);
                uint d = kp >= 0 ? fmVal(sB.kept, uint(kp)) : 0u;  // a log
                if (d >= 6u) continue;
                // Direction.values(): down, up, north, south, west, east
                for (uint dir = 0; dir < 6u; dir++) {
                    int nx = x + (dir == 4u ? -1 : (dir == 5u ? 1 : 0)), ny = y + (dir == 0u ? -1 : (dir == 1u ? 1 : 0)),
                        nz = z + (dir == 2u ? -1 : (dir == 3u ? 1 : 0));
                    int np = fmFind(sB.kept, posKey(nx, ny, nz));
                    if (np >= 0 && fmVal(sB.kept, uint(np)) > d + 1u) {
                        fmSetVal(sB.kept, uint(np), d + 1u);
                        qPush(tail, posKey(nx, ny, nz));
                    }
                }
                if (tail - head > TQ_CAP) { flag(FL_TABLE_FULL); full = true; break; }
            }
            if (head == tail || full) {
                pos = int(fmN(sB.kept)) - 1;
                phase = 9u;
            }
        } else if (phase == 9u) {
            for (; pos >= 0 && ops < budget; pos--, ops++) {
                int64_t k = fmKey(sB.kept, uint(pos));
                if (k == 0) continue;
                uint state = fmGet(sB.leaves, k, 0u);
                uint d = fmVal(sB.kept, uint(pos));
                state = btrans(state, T_DIST + (d <= 6u ? d - 1u : 6u));
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                uint here = gGet(box, x, y, z);
                if ((bis(here, B_SOFT) && !bis(here, B_LEAVES_TAG)) || bis(here, B_LEAVES_TAG)) gSet(box, x, y, z, waterlog(state, here));
            }
            if (pos < 0) {
                // trimmings: hung on a written leaf or log or a trimming above, highest first (a stable sort of the
                // table order)
                uint ne = fmN(sB.extras), c = 0;
                for (int q = int(ne) - 1; q >= 0; q--) {
                    int64_t k = fmKey(sB.extras, uint(q));
                    if (k == 0) continue;
                    uint w = sB.sortAt + c * 3u;
                    scratch[w] = uint(uint64_t(k) & 0xFFFFFFFFu);
                    scratch[w + 1] = uint(uint64_t(k) >> 32u);
                    scratch[w + 2] = fmVal(sB.extras, uint(q));
                    c++;
                }
                ops += ne;
                scratch[h + TC_C] = c;
                pos = 1;
                phase = 10u;
            }
        } else if (phase == 10u) {
            // insertion sort by y descending (stable, like the list sort): i at pos
            uint c = scratch[h + TC_C];
            for (; uint(pos) < c && ops < budget; pos++) {
                uint i = uint(pos);
                uint a0 = scratch[sB.sortAt + i * 3u], a1 = scratch[sB.sortAt + i * 3u + 1u], a2 = scratch[sB.sortAt + i * 3u + 2u];
                int ay = keyY(int64_t((uint64_t(a1) << 32) | uint64_t(a0)));
                uint j = i;
                while (j > 0u) {
                    uint p = sB.sortAt + (j - 1u) * 3u;
                    int py = keyY(int64_t((uint64_t(scratch[p + 1]) << 32) | uint64_t(scratch[p])));
                    if (py >= ay) break;
                    scratch[p + 3] = scratch[p]; scratch[p + 4] = scratch[p + 1]; scratch[p + 5] = scratch[p + 2];
                    j--;
                    ops++;
                }
                uint q = sB.sortAt + j * 3u;
                scratch[q] = a0; scratch[q + 1] = a1; scratch[q + 2] = a2;
                ops++;
            }
            if (uint(pos) >= c) {
                fmInit(sB.placed, 16u);
                pos = 0;
                phase = 11u;
            }
        } else {
            uint c = scratch[h + TC_C];
            for (; uint(pos) < c && ops < budget; pos++) {
                ops += 6u;
                uint w = sB.sortAt + uint(pos) * 3u;
                int64_t k = int64_t((uint64_t(scratch[w + 1]) << 32) | uint64_t(scratch[w]));
                int x = keyX(k), y = keyY(k), z = keyZ(k);
                bool held = false;
                for (uint dir = 0; dir < 6u && !held; dir++) {
                    int64_t n = posKey(x + (dir == 4u ? -1 : (dir == 5u ? 1 : 0)), y + (dir == 0u ? -1 : (dir == 1u ? 1 : 0)), z + (dir == 2u ? -1 : (dir == 3u ? 1 : 0)));
                    int rp = fmFind(sB.root, n);
                    if ((rp >= 0 && fmVal(sB.root, uint(rp)) == 3u) || fmHas(sB.kept, n) || fmHas(sB.placed, n)) held = true;
                }
                if (!held) continue;
                if (bis(gGet(box, x, y, z), B_AIR) && gSet(box, x, y, z, scratch[w + 2])) fmPut(sB.placed, k, 1u, sB.tmp);
            }
            if (uint(pos) >= c) { scratch[h + TC_RES] = 1u; done = true; break; }
        }
#if FEAT_CLOCK
        clkAdd(20u + (ph0 < 11u ? ph0 : 11u), pc0);  // (dev: the commit's phases, 20 + phase)
#endif
    }
    if (done) {
        scratch[h + TC_MAGIC] = 0u;
        return true;
    }
    scratch[h + TC_PHASE] = phase;
    scratch[h + TC_POS] = uint(pos);
    scratch[h + TC_HEAD] = head;
    scratch[h + TC_TAIL] = tail;
    return false;
}
// the commit at once (the serial path, and the store's without | 4): the same phases without a budget
[noinline]
bool tCommit(int baseX, int baseY, int baseZ) {
    tCommitBegin(baseX, baseY, baseZ);
    for (int i = 0; i < 1000000; i++)
        if (tCommitStep(0xFFFFFFFFu)) break;
    return scratch[tcAt() + TC_RES] != 0u;
}
// (featBody, the store | 4) the pending commit of the round before, within the budget; true when it ended
[noinline]
bool treeResume(uint budget) {
    uint h = tcAt();
    if (scratch[h + TC_MAGIC] != TREE_PENDING) return true;
    sB = treeB(gbox(int(scratch[h + TC_X]), int(scratch[h + TC_Z])));
    return tCommitStep(budget);
}

// ---- TreeShapes: the growth forms (the species table: SPEC_W words a species) -----------------------------------
static const uint S_SHAPE = 0, S_LOG = 1, S_LEAVES = 2, S_ACCENT = 3, S_EXTRA = 4, S_ACCENT_CH = 5, S_ACCENT_TOP = 6,
                  S_MINH = 7, S_MAXH = 8, S_RADIUS = 9, S_BASE0 = 10, S_BASE1 = 11, S_CLUMP = 12, S_SPREAD = 13,
                  S_LIMBS = 14, S_TRUNK = 15, S_LEAN = 16, S_DROOP = 17, S_GAPS = 18, S_DEAD = 19, S_VINES = 20,
                  S_ROOTS = 21, S_RAGGED = 22, S_WIND = 23, S_UMBRELLA = 24, S_WHORLS = 25, S_SOIL = 26, S_MAXWATER = 27,
                  S_REACH = 28, S_JUNGLELOG = 29, S_EXTRA_FACING = 30, S_EXTRA_UP = 31;
static const uint NO_STATE = 0xFFFFFFFFu;
static const uint SH_CONIFER = 0, SH_BROADLEAF = 1, SH_ASPEN = 2, SH_WILLOW = 3, SH_COLUMNAR = 4, SH_BALD_CYPRESS = 5,
                  SH_MANGROVE = 6, SH_PALM = 7, SH_ACACIA = 8, SH_BAOBAB = 9, SH_JUNGLE = 10, SH_TWISTED = 11, SH_SHRUB = 12,
                  SH_DEAD = 13, SH_FALLEN_LOG = 14, SH_STUMP = 15, SH_BAMBOO = 16, SH_CRYSTAL = 17;
uint spU(uint sp, uint f) { return fprog[sp + f]; }
int spI(uint sp, uint f) { return int(fprog[sp + f]); }
float spF(uint sp, uint f) { return asfloat(fprog[sp + f]); }
static const double TWO_PI_HALF = asdouble(0x54442D18u, 0x3FF921FBu);  // Math.PI / 2 (javac folds it)
static const double DEG2RAD = 0.017453292519943295l, RAD2DEG = 57.29577951308232l;
static const int MAXC = 80;  // trunk line rows

struct Grow {
    uint sp;
    int x, y, z;
    double windAngle;
    int water;
    uint leaves, accent;  // accent NO_STATE: none
};
// the trunk line in scratch: row i at 4 i words (x, then z)
struct Line {
    uint at;
};
Line lineOf() {
    Line c;
    c.at = sG.base + SCR_MISC + M_LINE;
    return c;
}
double LX(Line c, int i) { return scrD(c.at + uint(i) * 4u); }
double LZ(Line c, int i) { return scrD(c.at + uint(i) * 4u + 2u); }
void setL(Line c, int i, double x, double z) {
    scrSetD(c.at + uint(i) * 4u, x);
    scrSetD(c.at + uint(i) * 4u + 2u, z);
}
[ForceInline]
double gu(double lo, double hi) { return lo + rngDouble() * (hi - lo); }
[ForceInline]
int gHeight(Grow t) {
    int lo = spI(t.sp, S_MINH), hi = spI(t.sp, S_MAXH);
    return lo + rngIntB(imax(1, hi - lo + 1));
}
[ForceInline]
double gAngle() { return rngDouble() * PI_D * 2.0l; }
[ForceInline]
double gLeanAngle(Grow t) {
    float w = spF(t.sp, S_WIND);
    return w > 0.0f ? t.windAngle + (rngDouble() - 0.5l) * (1.0l - double(w)) * PI_D : gAngle();
}
uint leafFor(Grow t, bool accentPick) { return accentPick && t.accent != NO_STATE ? t.accent : t.leaves; }
[ForceInline]
uint clumpLeaves(Grow t) {
    bool pick = spU(t.sp, S_ACCENT_TOP) == 0u && t.accent != NO_STATE && rngFloat() < spF(t.sp, S_ACCENT_CH);
    return leafFor(t, pick);
}
double powD(double a, double b, inout uint bad) { return powS(a, b, bad); }
// Mth.wrapDegrees(double): angle % 360 (exact), folded into [-180, 180)
[noinline]
double fmod360(double a, inout uint bad) {
    double ab = absD2(a);
    if (ab < 360.0l) return a;
    double q = double(int64_t(divD(ab, 360.0l, bad)));
    double rr = ab - q * 360.0l;
    if (rr < 0.0l) rr += 360.0l;
    if (rr >= 360.0l) rr -= 360.0l;
    return a < 0.0l ? -rr : rr;
}
[noinline]
double wrapDegrees(double a, inout uint bad) {
    double n = fmod360(a, bad);
    if (n >= 180.0l) n -= 360.0l;
    if (n < -180.0l) n += 360.0l;
    return n;
}

[noinline]
void trunkLine(Grow t, int h, double leanScale, Line c) {
    double la = gLeanAngle(t);
    double lm = double(spF(t.sp, S_LEAN) * float(h)) * gu(0.3l, 1.0l) * leanScale;
    double wob = gu(0.0l, 0.35l), ph = gAngle(), freq = gu(0.25l, 0.5l);
    if (h + 2 > MAXC) { flag(FL_STACK); return; }
    double cl = cosS_N(la), sl = sinS_N(la);
    for (int dy = 0; dy <= h + 1; dy++) {
        double tt = divD_N(double(dy), double(imax(1, h)));
        double off = lm * powD_N(tt, 1.6l);
        setL(c, dy, double(t.x) + 0.5l + cl * off + sinS_N(ph + double(dy) * freq) * wob * tt,
             double(t.z) + 0.5l + sl * off + cosS_N(ph + double(dy) * freq) * wob * tt);
    }
}
[noinline]
void trunkDraw(Grow t, Line c, int from, int to, double r0, double r1, double flare) {
    uint log = axisOf(spU(t.sp, S_LOG), 1u);
    for (int dy = from; dy <= to; dy++) {
        double tt = divD_N(double(dy - from), double(imax(1, to - from)));
        double rr = r0 + (r1 - r0) * tt + (dy - from < 3 ? divD_N(flare * double(3 - (dy - from)), 3.0l) : 0.0l);
        if (rr < 0.75l) tLog(floorD_N(LX(c, dy)), t.y + dy, floorD_N(LZ(c, dy)), log);
        else tDisc(LX(c, dy), t.y + dy, LZ(c, dy), rr, log);
    }
    double rb = r0 + flare;
    int ri = ceilD_N(rb);
    for (int dx = -ri; dx <= ri; dx++)
        for (int dz = -ri; dz <= ri; dz++) {
            int bx = floorD_N(LX(c, from)) + dx, bz = floorD_N(LZ(c, from)) + dz;
            if (tIsLog(bx, t.y + from, bz)) tSink(bx, t.y + from, bz, log, 4 + t.water);
        }
}
[noinline]
void roots(Grow t, double cx, double cz, double r0, int count, double len0, double len1, int rise) {
    uint log = spU(t.sp, S_LOG);
    for (int i = 0; i < count; i++) {
        double a = divD_N((double(i) + rngDouble() * 0.6l) * PI_D * 2.0l, double(count));
        double len = gu(len0, len1);
        double ca = cosS_N(a), sa = sinS_N(a);
        double sx = cx + ca * r0, sz = cz + sa * r0;
        double mx = cx + ca * (r0 + len * 0.45l), mz = cz + sa * (r0 + len * 0.45l);
        double ex = cx + ca * (r0 + len), ez = cz + sa * (r0 + len);
        double my = double(t.y) + double(rise) * 0.45l;
        tBranch(sx, double(t.y + rise), sz, mx, my, mz, 0.0l, 0.0l, log);
        tBranch(mx, my, mz, ex, double(t.y - 1), ez, 0.0l, 0.0l, log);
        tSink(floorD_N(ex), t.y - 1, floorD_N(ez), axisOf(log, 1u), 2);
    }
}
[noinline]
void tier(Grow t, double cx, int y, double cz, double rad, double tr) {
    int n = 4 + rngIntB(3);
    double a0 = gAngle();
    double ang[8], len[8];
    for (int i = 0; i < n; i++) {
        ang[i] = a0 + divD_N(double(i) * PI_D * 2.0l, double(n)) + (rngDouble() - 0.5l) * 0.7l;
        len[i] = rad * gu(0.72l, 1.08l);
        if (rad >= 2.6l) {
            double bl = len[i] * 0.35l + tr;
            tBranch(cx, double(y) + 0.5l, cz, cx + cosS_N(ang[i]) * bl, double(y) + 0.5l - double(spF(t.sp, S_DROOP)) * 0.4l,
                    cz + sinS_N(ang[i]) * bl, 0.0l, 0.0l, spU(t.sp, S_LOG));
        }
    }
    int ri = ceilD_N(rad + tr);
    int bx = floorD_N(cx), bz = floorD_N(cz);
    for (int dx = -ri; dx <= ri; dx++)
        for (int dz = -ri; dz <= ri; dz++) {
            double fx = double(bx + dx) + 0.5l - cx, fz = double(bz + dz) + 0.5l - cz;
            double d = sqrtD_N(fx * fx + fz * fz) - tr;
            if (d > rad + 0.5l) continue;
            double th = atan2S_N(fz, fx);
            double reach = 0.0l;
            for (int i = 0; i < n; i++) {
                double cs = cosS_N(th - ang[i]);
                if (cs > 0.0l) reach = dmax(reach, len[i] * (0.55l + 0.45l * cs * cs * cs));
            }
            reach = dmax(reach, dmin(1.5l, rad));
            if (d > reach + (rngDouble() - 0.5l) * 0.8l) continue;
            double rel = divD_N(d, dmax(1.0l, rad));
            int droop = rel > 0.5l ? int(jroundD(double(spF(t.sp, S_DROOP)) * (rel - 0.5l) * 2.2l)) : 0;
            tLeaf(bx + dx, y - droop, bz + dz, t.leaves);
            if (rel < 0.4l || (rel < 0.65l && rngIntB(3) == 0)) tLeaf(bx + dx, y + 1 - droop, bz + dz, t.leaves);
            if (droop > 0 && rel > 0.85l && rngIntB(3) == 0) tLeaf(bx + dx, y - droop - 1, bz + dz, t.leaves);
        }
}
[noinline]
void shConifer(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 1.0l, c);
    double tr = spI(sp, S_TRUNK) >= 2 ? gu(1.0l, 1.3l) : 0.0l;
    int rise = 0;
    if (spF(sp, S_ROOTS) > 0.0f && rngFloat() < spF(sp, S_ROOTS) && spI(sp, S_TRUNK) < 2) {
        rise = 1 + rngIntB(2) + imin(1, t.water);
        for (int dy = 0; dy < rise; dy++) setL(c, dy, LX(c, rise), LZ(c, rise));
        int cnt = 3 + rngIntB(2);
        roots(t, LX(c, 0), LZ(c, 0), 0.5l, cnt, 1.5l, 3.0l, rise);
    }
    trunkDraw(t, c, rise, h, tr, tr * 0.35l, spI(sp, S_TRUNK) >= 2 ? 0.45l : 0.0l);
    if (spI(sp, S_TRUNK) >= 2 && spF(sp, S_ROOTS) > 0.0f) {
        int cnt = 3 + rngIntB(2);
        roots(t, LX(c, 0), LZ(c, 0), tr + 0.2l, cnt, 1.2l, 2.6l, 1);
    }
    int cb = imax(1, d2i(double(h) * gu(double(spF(sp, S_BASE0)), double(spF(sp, S_BASE1)))));
    double rMax = double(spF(sp, S_RADIUS)) * gu(0.82l, 1.15l);
    for (int dy = 2; dy < cb; dy += 1 + rngIntB(2)) {
        if (rngFloat() < spF(sp, S_DEAD)) {
            double a = gAngle();
            double len = double(1 + rngIntB(2) + (tr > 0.0l ? 1 : 0));
            double ex = LX(c, dy) + cosS_N(a) * (len + tr);
            double ey = double(t.y + dy) + 0.5l - double(rngIntB(2));
            tBranch(LX(c, dy), double(t.y + dy) + 0.5l, LZ(c, dy), ex, ey, LZ(c, dy) + sinS_N(a) * (len + tr), 0.0l, 0.0l, spU(sp, S_LOG));
        }
    }
    int top = h;
    int dy = top - 1;
    for (int guard = 0; guard < 256 && dy >= cb; guard++) {
        double tt = divD_N(double(dy - cb), double(imax(1, top - cb)));
        double rad;
        if (spU(sp, S_UMBRELLA) != 0u)
            rad = 1.2l + rMax * powD_N(sinS_N(PI_D * dmin(1.0l, 0.25l + tt * 0.85l)), 0.7l) * gu(0.8l, 1.1l);
        else
            rad = 0.9l + rMax * powD_N(1.0l - tt, 0.85l) * gu(0.78l, 1.15l);
        bool gap = tt > 0.12l && tt < 0.8l && rngFloat() < spF(sp, S_GAPS);
        if (!gap) tier(t, LX(c, dy), t.y + dy, LZ(c, dy), rad, tr);
        dy -= rad > 3.2l ? 2 + rngIntB(2) : 2;
    }
    double sx = LX(c, top), sz = LZ(c, top);
    int sy = t.y + top;
    int fsx = floorD_N(sx), fsz = floorD_N(sz);
    tLeaf(fsx, sy + 1, fsz, t.leaves);
    if (rngBool()) tLeaf(fsx, sy + 2, fsz, t.leaves);
    for (uint d = 0; d < 4u; d++) {
        tLeaf(fsx + hdx(d), sy, fsz + hdz(d), t.leaves);
        if (rngIntB(3) > 0) tLeaf(fsx + hdx(d), sy - 1, fsz + hdz(d), t.leaves);
    }
}
[ForceInline]
void leafClump(Grow t, double x, double y, double z, double rr, uint lv) {
    double rx = rr * gu(0.85l, 1.15l), rz = rr * gu(0.85l, 1.15l), ry = rr * gu(0.55l, 0.8l);
    tClump(x, y, z, rx, dmax(1.2l, ry), rz, lv, double(spF(t.sp, S_RAGGED)));
}
[noinline]
void shBroadleaf(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 1.0l, c);
    int trunk = spI(sp, S_TRUNK);
    double tr = trunk >= 3 ? gu(1.5l, 2.2l) : (trunk == 2 ? gu(0.85l, 1.15l) : 0.0l);
    int fork = imax(2, d2i(double(h) * gu(double(spF(sp, S_BASE0)), double(spF(sp, S_BASE1)))));
    trunkDraw(t, c, 0, fork, tr, tr * 0.8l, tr > 0.0l ? 0.7l + tr * 0.25l : 0.0l);
    if (spF(sp, S_ROOTS) > 0.0f && rngFloat() < spF(sp, S_ROOTS)) {
        int cnt = 3 + rngIntB(3);
        roots(t, LX(c, 0), LZ(c, 0), tr * 0.8l + 0.3l, cnt, 1.2l, 2.5l + tr, tr > 1.0l ? 2 : 1);
    }
    double crownR = double(spF(sp, S_RADIUS)) * gu(0.85l, 1.15l);
    double fx = LX(c, fork), fz = LZ(c, fork);
    int limbs = spI(sp, S_LIMBS) + rngIntB(2);
    double a0 = gAngle();
    double wind = double(spF(sp, S_WIND));
    double crownH = double(h - fork);
    uint log = spU(sp, S_LOG);
    double clumpF = double(spF(sp, S_CLUMP));
    for (int i = 0; i < limbs; i++) {
        double az = a0 + divD_N(double(i) * PI_D * 2.0l, double(limbs)) + (rngDouble() - 0.5l) * 0.9l;
        if (wind > 0.0l) {
            double rel = divD_N(wrapDegrees_N((az - t.windAngle) * RAD2DEG), 180.0l);
            az = t.windAngle + rel * PI_D * (1.0l - wind * 0.6l);
        }
        float lerp = 72.0f + spF(sp, S_SPREAD) * (18.0f - 72.0f);  // Mth.lerp(float, 72, 18)
        double elev = (double(lerp) + (rngDouble() - 0.5l) * 24.0l) * DEG2RAD;
        double len = dmax(2.5l, hypotS_N(crownR * 0.72l, crownH * 0.75l) * gu(0.75l, 1.05l));
        int ri2 = rngIntB(2);
        double sy = double(t.y + fork + ri2 - (i % 2));
        double ca = cosS_N(az), sa = sinS_N(az), ce = cosS_N(elev), se = sinS_N(elev);
        double ex = fx + ca * ce * len, ez = fz + sa * ce * len, ey = sy + se * len;
        bool deadLimb = rngFloat() < spF(sp, S_DEAD);
        tBranch(fx, sy, fz, ex, ey, ez, tr > 1.0l ? tr * 0.55l : 0.0l, 0.0l, log);
        uint lv = clumpLeaves(t);
        int shoots = 1 + rngIntB(2) + (trunk >= 3 ? 1 : 0);
        for (int k = 0; k < shoots; k++) {
            double tt = gu(0.45l, 0.85l);
            double px = fx + (ex - fx) * tt, py = sy + (ey - sy) * tt, pz = fz + (ez - fz) * tt;
            double sign = rngBool() ? 1.0l : -1.0l;
            double saz = az + sign * gu(0.5l, 1.1l);
            double sel = elev + gu(-0.1l, 0.35l);
            double sl = len * gu(0.35l, 0.6l);
            double cs2 = cosS_N(sel);
            double qx = px + cosS_N(saz) * cs2 * sl, qz = pz + sinS_N(saz) * cs2 * sl;
            double qy = py + sinS_N(sel) * sl;
            tBranch(px, py, pz, qx, qy, qz, 0.0l, 0.0l, log);
            if (!deadLimb) leafClump(t, qx, qy + 0.5l, qz, clumpF * gu(0.75l, 1.0l), lv);
        }
        if (!deadLimb) leafClump(t, ex, ey + 0.5l, ez, clumpF * gu(0.9l, 1.2l), lv);
    }
    double lx = LX(c, h) + (wind > 0.0l ? cosS_N(t.windAngle) * wind * 1.5l : 0.0l);
    double lz = LZ(c, h) + (wind > 0.0l ? sinS_N(t.windAngle) * wind * 1.5l : 0.0l);
    tBranch(fx, double(t.y + fork), fz, lx, double(t.y + h - 1), lz, tr > 1.0l ? tr * 0.5l : 0.0l, 0.0l, log);
    double lr = clumpF * gu(0.95l, 1.25l);
    uint llv = clumpLeaves(t);
    leafClump(t, lx, double(t.y + h), lz, lr, llv);
    double midY = double(t.y + fork) + crownH * 0.65l;
    uint mlv = clumpLeaves(t);
    tClump(fx + (lx - fx) * 0.6l, midY, fz + (lz - fz) * 0.6l, crownR * 0.55l, dmax(1.5l, crownH * 0.35l), crownR * 0.55l, mlv, 0.7l);
    if (spF(sp, S_VINES) > 0.0f) tHangVines(double(spF(sp, S_VINES)), 4);
}
[noinline]
void shAspen(Grow t) {
    uint sp = t.sp;
    int stems = rngIntB(6) == 0 ? 2 : 1;
    for (int s = 0; s < stems; s++) {
        int hh = gHeight(t);
        int h = s == 0 ? hh : d2i(double(hh) * gu(0.65l, 0.85l));
        int ox = s == 0 ? 0 : (rngBool() ? 1 : -1);
        int oz = s == 0 ? 0 : (rngBool() ? 1 : -1);
        Grow gs = t;
        if (s != 0) { gs.x = t.x + ox; gs.z = t.z + oz; gs.leaves = spU(sp, S_LEAVES); gs.accent = spU(sp, S_ACCENT); }
        Line c = lineOf();
        trunkLine(gs, h, 1.0l, c);
        trunkDraw(gs, c, 0, h - 1, 0.0l, 0.0l, 0.0l);
        int cb = d2i(double(h) * gu(double(spF(sp, S_BASE0)), double(spF(sp, S_BASE1))));
        int clumps = 3 + rngIntB(3);
        uint mainLv = clumpLeaves(t);
        for (int k = 0; k < clumps; k++) {
            double tt = divD_N(double(k) + rngDouble() * 0.6l, double(clumps));
            int dy = cb + int(jroundD(double(h - cb) * tt));
            dy = imin(dy, h);
            double a = gAngle(), off = gu(0.2l, 1.3l) * (1.0l - tt * 0.6l);
            double px = LX(c, dy) + cosS_N(a) * off, pz = LZ(c, dy) + sinS_N(a) * off;
            double rr = double(spF(sp, S_RADIUS)) * gu(0.75l, 1.1l) * (1.05l - tt * 0.35l);
            uint lv = rngFloat() < spF(sp, S_ACCENT_CH) ? leafFor(t, true) : mainLv;
            if (off > 0.8l) tBranch(LX(c, dy), double(t.y + dy), LZ(c, dy), px, double(t.y + dy + 1), pz, 0.0l, 0.0l, spU(sp, S_LOG));
            double ry = rr * gu(0.8l, 1.05l);
            tClump(px, double(t.y + dy) + 0.5l, pz, rr, ry, rr, lv, double(spF(sp, S_RAGGED)) + 0.1l);
        }
        tClump(LX(c, h), double(t.y + h) + 0.5l, LZ(c, h), 1.3l, 1.5l, 1.3l, mainLv, 0.2l);
    }
}
[noinline]
void shWillow(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 1.2l, c);
    double tr = spI(sp, S_TRUNK) >= 2 ? gu(0.6l, 1.0l) : 0.0l;
    int fork = imax(2, d2i(double(h) * gu(double(spF(sp, S_BASE0)), double(spF(sp, S_BASE1)))));
    trunkDraw(t, c, 0, fork, tr, tr * 0.7l, 0.6l);
    double fx = LX(c, fork), fz = LZ(c, fork);
    double crownR = double(spF(sp, S_RADIUS)) * gu(0.85l, 1.15l);
    int limbs = spI(sp, S_LIMBS) + rngIntB(2);
    double a0 = gAngle();
    double clumpF = double(spF(sp, S_CLUMP));
    for (int i = 0; i < limbs; i++) {
        double az = a0 + divD_N(double(i) * PI_D * 2.0l, double(limbs)) + (rngDouble() - 0.5l) * 0.7l;
        double outv = crownR * gu(0.5l, 0.8l), up = gu(2.0l, double(h - fork) + 1.5l);
        double ex = fx + cosS_N(az) * outv, ez = fz + sinS_N(az) * outv, ey = double(t.y + fork) + up;
        tBranch(fx, double(t.y + fork), fz, ex, ey, ez, 0.0l, 0.0l, spU(sp, S_LOG));
        double rx = clumpF * gu(0.9l, 1.2l);
        double rz = clumpF * gu(0.9l, 1.2l);
        uint lv = clumpLeaves(t);
        tClump(ex, ey, ez, rx, clumpF * 0.7l, rz, lv, 0.35l);
    }
    tClump(fx, double(t.y + h), fz, crownR * 0.6l, 2.0l, crownR * 0.6l, t.leaves, 0.4l);
    tDrape(fx, fz, crownR * 0.35l, 0.5l, 6);
}
[noinline]
void shColumnar(Grow t) {
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 0.4l, c);
    trunkDraw(t, c, 0, h - 2, 0.0l, 0.0l, 0.0l);
    double rMax = double(spF(t.sp, S_RADIUS)) * gu(0.8l, 1.2l);
    double peak = gu(0.22l, 0.38l);
    for (int dy = 1; dy <= h + 1; dy++) {
        double tt = divD_N(double(dy), double(h + 1));
        double rr = tt < peak ? rMax * powD_N(divD_N(tt, peak), 0.5l) : rMax * powD_N(divD_N(1.0l - tt, 1.0l - peak), 0.8l);
        rr = dmax(rr, 0.6l);
        int cy = imin(dy, h);
        int bx = floorD_N(LX(c, cy)), bz = floorD_N(LZ(c, cy));
        int ri = ceilD_N(rr);
        for (int dx = -ri; dx <= ri; dx++)
            for (int dz = -ri; dz <= ri; dz++) {
                double d = sqrtD_N(double(dx * dx + dz * dz));
                if (d <= rr + (rngDouble() - 0.5l) * 0.7l) tLeaf(bx + dx, t.y + dy, bz + dz, t.leaves);
            }
    }
    tLeaf(floorD_N(LX(c, h)), t.y + h + 2, floorD_N(LZ(c, h)), t.leaves);
}
[noinline]
void shBaldCypress(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 0.5l, c);
    double flare = 1.1l + gu(0.0l, 0.5l);
    trunkDraw(t, c, 0, h, 0.9l, 0.5l, flare);
    int knees = 2 + rngIntB(4);
    for (int i = 0; i < knees; i++) {
        double a = gAngle(), d = gu(2.5l, 5.5l);
        int kx = floorD_N(LX(c, 0) + cosS_N(a) * d), kz = floorD_N(LZ(c, 0) + sinS_N(a) * d);
        int fl = floraGround(sB.box, kx, kz, t.y);
        if (fl == NONE || iabs(fl - t.y) > 2) continue;
        int kh = 1 + t.water / 2 + (rngIntB(3) == 0 ? 1 : 0);
        for (int k = -1; k < kh; k++) tAnchor(kx, fl + k, kz, axisOf(spU(sp, S_LOG), 1u));
    }
    int pads = 2 + rngIntB(2);
    double rTop = double(spF(sp, S_RADIUS)) * gu(0.8l, 1.15l);
    for (int p = 0; p < pads; p++) {
        int py = t.y + h - p * (3 + rngIntB(2));
        double pr = rTop * (1.0l - double(p) * 0.12l) * gu(0.85l, 1.1l);
        int ci = imin(h, py - t.y);
        if (ci < 0) { flag(FL_UNSUPPORTED); return; }  // Java throws here (a feature failure)
        double px = LX(c, ci), pz = LZ(c, ci);
        int arms = 3 + rngIntB(3);
        double a0 = gAngle();
        for (int i = 0; i < arms; i++) {
            double a = a0 + divD_N(double(i) * PI_D * 2.0l, double(arms)) + gu(-0.4l, 0.4l);
            double al = pr * gu(0.45l, 0.75l);
            double ax = px + cosS_N(a) * al, az = pz + sinS_N(a) * al;
            tBranch(px, double(py - 1), pz, ax, double(py), az, 0.0l, 0.0l, spU(sp, S_LOG));
            tPad(ax, py + 1, az, pr * 0.5l, pr * 0.5l, t.leaves, 0.6l, true);
        }
        tPad(px, py + 1, pz, pr * 0.65l, pr * 0.65l, t.leaves, 0.45l, true);
    }
    tHangVines(double(spF(sp, S_VINES)), 6);
}
[noinline]
void shMangrove(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    int lift = t.water + 2 + rngIntB(2);
    Line c = lineOf();
    trunkLine(t, h + lift, 1.0l, c);
    uint rootBlock = spU(sp, S_EXTRA) != NO_STATE ? spU(sp, S_EXTRA) : spU(sp, S_LOG);
    int nroots = 5 + rngIntB(4);
    for (int i = 0; i < nroots; i++) {
        double a = divD_N((double(i) + rngDouble() * 0.6l) * PI_D * 2.0l, double(nroots));
        double outv = gu(2.0l, 4.2l);
        int startY = lift - rngIntB(2);
        double sx = LX(c, startY), sz = LZ(c, startY);
        int steps = ceilD_N(outv * 2.0l);
        double ca = cosS_N(a), sa = sinS_N(a);
        for (int s = 0; s <= steps; s++) {
            double tt = divD_N(double(s), double(steps));
            double rx = sx + ca * outv * tt, rz = sz + sa * outv * tt;
            int ry = t.y + int(jroundD(double(startY) * (1.0l - tt * tt))) - (tt > 0.9l ? 1 : 0);
            tAnchor(floorD_N(rx), ry, floorD_N(rz), rootBlock);
            if (tt > 0.85l) tSink(floorD_N(rx), ry, floorD_N(rz), rootBlock, 3);
        }
    }
    trunkDraw(t, c, lift, h + lift, 0.0l, 0.0l, 0.0l);
    int top = h + lift;
    double crownR = double(spF(sp, S_RADIUS)) * gu(0.85l, 1.15l);
    int clumps = 3 + rngIntB(3);
    for (int k = 0; k < clumps; k++) {
        double a = gAngle(), d = gu(0.5l, crownR * 0.6l);
        double px = LX(c, top) + cosS_N(a) * d, pz = LZ(c, top) + sinS_N(a) * d;
        int py = t.y + top - rngIntB(3);
        tBranch(LX(c, top - 2), double(t.y + top - 2), LZ(c, top - 2), px, double(py), pz, 0.0l, 0.0l, spU(sp, S_LOG));
        leafClump(t, px, double(py) + 0.5l, pz, double(spF(sp, S_CLUMP)) * gu(0.9l, 1.2l), t.leaves);
    }
    tHangVines(double(spF(sp, S_VINES)), 5);
}
[noinline]
void shPalm(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    double a = gLeanAngle(t);
    double bend = double(spF(sp, S_LEAN)) * gu(0.4l, 1.2l);
    double sx = 0.0l, sz = 0.0l;
    int tx = t.x, tz = t.z;
    double ca = cosS_N(a), sa = sinS_N(a);
    uint log = spU(sp, S_LOG);
    for (int dy = 0; dy < h; dy++) {
        double tt = divD_N(double(dy), double(h));
        sx = double(t.x) + 0.5l + ca * bend * double(h) * tt * tt;
        sz = double(t.z) + 0.5l + sa * bend * double(h) * tt * tt;
        int nx = floorD_N(sx), nz = floorD_N(sz);
        if (dy > 0 && (nx != tx || nz != tz)) tLog(nx, t.y + dy - 1, nz, log);
        tx = nx;
        tz = nz;
        tLog(tx, t.y + dy, tz, log);
    }
    tSink(t.x, t.y, t.z, log, 3);
    int top = t.y + h;
    tLeaf(tx, top, tz, t.leaves);
    tLeaf(tx, top + 1, tz, t.leaves);
    bool date = spI(sp, S_LIMBS) > 8;
    int n = date ? spI(sp, S_LIMBS) + rngIntB(3) : 7 + rngIntB(4);
    double a0 = gAngle();
    for (int i = 0; i < n; i++) {
        double fa = a0 + divD_N(double(i) * PI_D * 2.0l, double(n)) + gu(-0.3l, 0.3l);
        double len = double(spF(sp, S_RADIUS)) * gu(0.75l, 1.15l);
        double rise = date ? gu(0.3l, 1.0l) : gu(0.6l, 1.4l);
        int px = NONE, py = 0, pz = 0;
        double cf = cosS_N(fa), sf = sinS_N(fa);
        double pa = fa + TWO_PI_HALF;
        double cp = cosS_N(pa), sp2 = sinS_N(pa);
        for (double s = 0.8l; s <= len; s += 0.5l) {
            double tt = divD_N(s, len);
            int fx = floorD_N(double(tx) + 0.5l + cf * s), fz = floorD_N(double(tz) + 0.5l + sf * s);
            int fy = top + int(jroundD(rise * 2.0l * tt - (rise * 2.0l + (date ? 2.2l : 3.4l)) * tt * tt));
            if (px != NONE && fy < py - 1) tLeaf(fx, fy + 1, fz, t.leaves);
            tLeaf(fx, fy, fz, t.leaves);
            if (tt > 0.25l && tt < 0.75l) {
                tLeaf(floorD_N(double(fx) + 0.5l + cp), fy, floorD_N(double(fz) + 0.5l + sp2), t.leaves);
                tLeaf(floorD_N(double(fx) + 0.5l - cp), fy, floorD_N(double(fz) + 0.5l - sp2), t.leaves);
            }
            px = fx;
            py = fy;
            pz = fz;
        }
        if (px != NONE) tLeaf(px, py - 1, pz, t.leaves);
    }
}
[noinline]
void shAcacia(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    int fork = 1 + rngIntB(3);
    Line c = lineOf();
    trunkLine(t, h, 0.6l, c);
    trunkDraw(t, c, 0, fork, 0.0l, 0.0l, 0.0l);
    int limbs = spI(sp, S_LIMBS) - 1 + rngIntB(2);
    double a0 = gAngle();
    double crownY = double(t.y + h);
    double radius = double(spF(sp, S_RADIUS));
    for (int i = 0; i < limbs; i++) {
        double az = a0 + divD_N(double(i) * PI_D * 2.0l, double(limbs)) + gu(-0.5l, 0.5l);
        double outv = radius * gu(0.45l, 0.85l);
        double ex = LX(c, fork) + cosS_N(az) * outv, ez = LZ(c, fork) + sinS_N(az) * outv;
        double ey = crownY - double(rngIntB(2));
        tBranch(LX(c, fork), double(t.y + fork), LZ(c, fork), ex, ey, ez, 0.0l, 0.0l, spU(sp, S_LOG));
        double pr = radius * gu(0.6l, 0.95l);
        double prz = pr * gu(0.7l, 1.0l);
        tPad(ex, d2i(ey) + 1, ez, pr, prz, t.leaves, 0.45l, false);
        tPad(ex, d2i(ey) + 2, ez, pr * 0.55l, pr * 0.5l, t.leaves, 0.5l, false);
    }
}
[noinline]
void shBaobab(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    double rb = double(spF(sp, S_RADIUS)) * gu(0.85l, 1.15l);
    Line c = lineOf();
    trunkLine(t, h, 0.2l, c);
    uint log = axisOf(spU(sp, S_LOG), 1u);
    for (int dy = 0; dy <= h; dy++) {
        double tt = divD_N(double(dy), double(h));
        double rr = rb * (1.0l + 0.12l * sinS_N(tt * PI_D * 1.2l) - 0.55l * powD_N(tt, 1.6l));
        tDisc(LX(c, dy), t.y + dy, LZ(c, dy), rr, log);
    }
    int ri = ceilD_N(rb);
    int fx0 = floorD_N(LX(c, 0)), fz0 = floorD_N(LZ(c, 0));
    for (int dx = -ri; dx <= ri; dx++)
        for (int dz = -ri; dz <= ri; dz++)
            if (tIsLog(fx0 + dx, t.y, fz0 + dz)) tSink(fx0 + dx, t.y, fz0 + dz, log, 4);
    int n = spI(sp, S_LIMBS) + rngIntB(3);
    double a0 = gAngle();
    for (int i = 0; i < n; i++) {
        double az = a0 + divD_N(double(i) * PI_D * 2.0l, double(n)) + gu(-0.4l, 0.4l);
        double outv = gu(2.5l, 5.0l), up = gu(1.0l, 3.5l);
        double ca = cosS_N(az), sa = sinS_N(az);
        double sx = LX(c, h) + ca * 0.8l, sz = LZ(c, h) + sa * 0.8l;
        double ex = sx + ca * outv, ez = sz + sa * outv, ey = double(t.y + h) + up;
        tBranch(sx, double(t.y + h), sz, ex, ey, ez, 0.0l, 0.0l, spU(sp, S_LOG));
        double rx = gu(1.4l, 2.2l), ry = gu(1.0l, 1.4l), rz = gu(1.4l, 2.2l);
        tClump(ex, ey + 0.7l, ez, rx, ry, rz, t.leaves, 0.6l);
    }
}
[noinline]
void shJungle(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 0.6l, c);
    double tr = spI(sp, S_TRUNK) >= 2 ? gu(0.95l, 1.25l) : 0.0l;
    trunkDraw(t, c, 0, h, tr, tr * 0.6l, tr > 0.0l ? 1.0l : 0.4l);
    if (spF(sp, S_ROOTS) > 0.0f) {
        int cnt = 3 + rngIntB(3);
        roots(t, LX(c, 0), LZ(c, 0), tr + 0.4l, cnt, 2.0l, 3.5l + tr * 1.5l, 3 + d2i(tr));
    }
    double clumpF = double(spF(sp, S_CLUMP));
    uint log = spU(sp, S_LOG);
    int side = 1 + rngIntB(3);
    for (int k = 0; k < side; k++) {
        int dy = d2i(double(h) * gu(0.5l, 0.82l));
        double a = gAngle(), l = gu(2.0l, 4.0l) + tr;
        double ex = LX(c, dy) + cosS_N(a) * l, ez = LZ(c, dy) + sinS_N(a) * l;
        tBranch(LX(c, dy), double(t.y + dy), LZ(c, dy), ex, double(t.y + dy + 2), ez, 0.0l, 0.0l, log);
        tClump(ex, double(t.y + dy) + 2.5l, ez, clumpF * 0.7l, 1.4l, clumpF * 0.7l, t.leaves, 0.45l);
    }
    int clumps = 3 + rngIntB(3);
    double crownR = double(spF(sp, S_RADIUS)) * gu(0.85l, 1.15l);
    for (int k = 0; k < clumps; k++) {
        double a = gAngle(), d = gu(1.0l, crownR * 0.65l);
        double ex = LX(c, h) + cosS_N(a) * d, ez = LZ(c, h) + sinS_N(a) * d;
        int ey = t.y + h - rngIntB(3);
        tBranch(LX(c, h - 3), double(t.y + h - 3), LZ(c, h - 3), ex, double(ey), ez, 0.0l, 0.0l, log);
        double rx = clumpF * gu(0.9l, 1.2l);
        double rz = clumpF * gu(0.9l, 1.2l);
        tClump(ex, double(ey + 1), ez, rx, clumpF * 0.5l, rz, t.leaves, 0.4l);
    }
    tClump(LX(c, h), double(t.y + h) + 1.5l, LZ(c, h), clumpF * 0.9l, clumpF * 0.55l, clumpF * 0.9l, t.leaves, 0.3l);
    tHangVines(double(spF(sp, S_VINES)), 7);
    int bx = floorD_N(LX(c, 0)), bz = floorD_N(LZ(c, 0));
    tTrunkVines(bx + int(jroundD(tr)), t.y, t.y + h - 4, bz, 0.5l);
    if (tr == 0.0l && spU(sp, S_JUNGLELOG) != 0u) {
        for (uint d = 0; d < 4u; d++) {
            if (rngIntB(4) != 0) continue;
            int dy = 2 + rngIntB(imax(1, h / 2));
            int age = rngIntB(3);
            tExtra(floorD_N(LX(c, dy)) + hdx(d), t.y + dy, floorD_N(LZ(c, dy)) + hdz(d), tconst(TCN_COCOA + d * 3u + uint(age)));
        }
    }
}
[noinline]
void shTwisted(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    double la = gLeanAngle(t);
    double cx = double(t.x) + 0.5l, cz = double(t.z) + 0.5l;
    int straight = 1 + rngIntB(2);
    if (h + 1 > MAXC) { flag(FL_STACK); return; }
    Line c = lineOf();
    for (int dy = 0; dy <= h; dy++) {
        setL(c, dy, cx, cz);
        if (dy >= straight) {
            double step = double(spF(sp, S_LEAN)) * 2.2l * gu(0.5l, 1.3l);
            cx += cosS_N(la) * step;
            cz += sinS_N(la) * step;
            la += gu(-0.7l, 0.7l);
        }
    }
    uint log = spU(sp, S_LOG);
    for (int dy = 0; dy < h; dy++) tBranch(LX(c, dy), double(t.y + dy), LZ(c, dy), LX(c, dy + 1), double(t.y + dy + 1), LZ(c, dy + 1), 0.0l, 0.0l, log);
    tSink(floorD_N(LX(c, 0)), t.y, floorD_N(LZ(c, 0)), axisOf(log, 1u), 3);
    int limbs = 3 + rngIntB(3);
    double a0 = gAngle();
    double radius = double(spF(sp, S_RADIUS));
    float wind = spF(sp, S_WIND);
    for (int i = 0; i < limbs; i++) {
        int dy = imax(1, imin(h - 1, int(jroundD(double(h) * (0.3l + divD_N(0.6l * double(i), double(limbs))))) + rngIntB(2)));
        double a = wind > 0.0f && rngFloat() < wind ? t.windAngle + gu(-1.0l, 1.0l) : a0 + double(i) * 2.4l + gu(-0.5l, 0.5l);
        double l = gu(1.5l, radius + 1.5l) * (1.1l - divD_N(0.4l * double(dy), double(h)));
        double ex = LX(c, dy) + cosS_N(a) * l, ez = LZ(c, dy) + sinS_N(a) * l;
        int ey = t.y + dy + rngIntB(2);
        tBranch(LX(c, dy), double(t.y + dy), LZ(c, dy), ex, double(ey), ez, 0.0l, 0.0l, log);
        if (rngFloat() < spF(sp, S_DEAD)) continue;
        double pr = radius * gu(0.6l, 1.0l);
        uint lv = clumpLeaves(t);
        double prz = pr * gu(0.6l, 0.95l);
        tPad(ex, ey, ez, pr, prz, lv, 0.55l, false);
        tPad(ex, ey + 1, ez, pr * 0.65l, pr * 0.55l, lv, 0.5l, false);
    }
    double pr = radius * gu(0.75l, 1.15l);
    uint lv = clumpLeaves(t);
    double prz = pr * gu(0.65l, 1.0l);
    tPad(LX(c, h), t.y + h, LZ(c, h), pr, prz, lv, 0.5l, false);
    tPad(LX(c, h), t.y + h + 1, LZ(c, h), pr * 0.6l, pr * 0.5l, lv, 0.45l, false);
}
[noinline]
void shShrub(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    uint log = axisOf(spU(sp, S_LOG), 1u);
    tLog(t.x, t.y, t.z, log);
    for (int dy = 1; dy <= h; dy++) tLog(t.x, t.y + dy, t.z, log);
    int blobs = 1 + rngIntB(3);
    double radius = double(spF(sp, S_RADIUS));
    for (int i = 0; i < blobs; i++) {
        double a = gAngle();
        double d = i == 0 ? 0.0l : gu(0.6l, 1.4l);
        double rr = radius * gu(0.7l, 1.15l);
        double ry = dmax(1.0l, rr * gu(0.6l, 0.85l));
        double rz = rr * gu(0.8l, 1.1l);
        uint lv = clumpLeaves(t);
        tClump(double(t.x) + 0.5l + cosS_N(a) * d, double(t.y) + double(h) * 0.6l + 0.8l, double(t.z) + 0.5l + sinS_N(a) * d,
               rr, ry, rz, lv, double(spF(sp, S_RAGGED)) + 0.2l);
    }
}
[noinline]
int lying(Grow t, int x0, int z0, double a, int len, uint logBase, bool dress) {
    double ca = cosS_N(a), sa = sinS_N(a);
    uint axis = absD2(ca) >= absD2(sa) ? 0u : 2u;
    uint log = axisOf(logBase, axis);
    int prevY = NONE, laid = 0, lx = NONE, lz = 0;
    for (double s = 0.0l; s < double(len); s += 0.75l) {
        int x = floorD_N(double(x0) + 0.5l + ca * s), z = floorD_N(double(z0) + 0.5l + sa * s);
        if (x == lx && z == lz) continue;
        int y = floraGround(sB.box, x, z, prevY == NONE ? t.y : prevY);
        if (y == NONE) break;
        if (prevY != NONE && iabs(y - prevY) > 1) break;
        if (prevY != NONE && y > prevY) y = prevY;
        uint here = gGet(sB.box, x, y, z);
        if (!bis(here, B_SOFT) || bis(here, B_FLUID)) break;
        if (lx != NONE && x != lx && z != lz) tLog(lx, prevY, z, log);
        tLog(x, y, z, log);
        if (dress) {
            int roll = rngIntB(10);
            if (roll < 3) tExtra(x, y + 1, z, tconst(TCN_MOSS));
            else if (roll == 3) tExtra(x, y + 1, z, rngBool() ? tconst(TCN_BROWN) : tconst(TCN_RED));
        }
        prevY = y;
        lx = x;
        lz = z;
        laid++;
    }
    return laid;
}
[noinline]
void shDead(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    bool snapped = rngIntB(4) == 0;
    int standing = snapped ? imax(3, d2i(double(h) * gu(0.4l, 0.7l))) : h;
    Line c = lineOf();
    trunkLine(t, h, 1.6l, c);
    uint log = axisOf(spU(sp, S_LOG), 1u);
    uint weathered = spU(sp, S_EXTRA) != NO_STATE ? axisOf(spU(sp, S_EXTRA), 1u) : log;
    for (int dy = 0; dy < standing; dy++) {
        uint s = rngIntB(6) == 0 ? weathered : log;
        tLog(floorD_N(LX(c, dy)), t.y + dy, floorD_N(LZ(c, dy)), s);
    }
    tSink(floorD_N(LX(c, 0)), t.y, floorD_N(LZ(c, 0)), log, 3 + t.water);
    uint raw = spU(sp, S_LOG);
    if (spU(sp, S_WHORLS) != 0u) {
        for (int dy = d2i(double(standing) * 0.3l); dy < standing - 1; dy += 2 + rngIntB(2)) {
            int n = 3 + rngIntB(3);
            double a0 = gAngle();
            double tt = divD_N(double(dy), double(h));
            for (int i = 0; i < n; i++) {
                if (rngIntB(3) == 0) continue;
                double a = a0 + divD_N(double(i) * PI_D * 2.0l, double(n)) + gu(-0.6l, 0.6l);
                double l = 0.8l + (1.0l - tt) * gu(0.3l, 1.6l);
                int sy = t.y + dy + (rngIntB(3) == 0 ? 1 : 0);
                double ex = LX(c, dy) + cosS_N(a) * l, ez = LZ(c, dy) + sinS_N(a) * l;
                int dropv = (l > 1.6l && rngBool()) ? 1 : 0;
                tBranch(LX(c, dy), double(sy), LZ(c, dy), ex, double(sy - dropv), ez, 0.0l, 0.0l, raw);
                if (spF(sp, S_ACCENT_CH) > 0.0f && rngFloat() < spF(sp, S_ACCENT_CH)) tClump(ex, double(sy), ez, 1.4l, 0.9l, 1.4l, t.leaves, 0.6l);
            }
        }
    } else {
        int limbs = rngIntB(5) == 0 ? 0 : 1 + rngIntB(3);
        for (int i = 0; i < limbs; i++) {
            int dy = imax(2, d2i(double(standing) * gu(0.45l, 0.95l)));
            dy = imin(dy, standing - 1);
            double a = gAngle(), el = gu(10.0l, 50.0l) * DEG2RAD, l = gu(1.2l, 3.2l);
            double ce = cosS_N(el);
            double ex = LX(c, dy) + cosS_N(a) * ce * l, ez = LZ(c, dy) + sinS_N(a) * ce * l;
            double ey = double(t.y + dy) + sinS_N(el) * l;
            tBranch(LX(c, dy), double(t.y + dy), LZ(c, dy), ex, ey, ez, 0.0l, 0.0l, raw);
            if (l > 2.5l && rngIntB(3) == 0) {
                double a2 = a + gu(-1.0l, 1.0l);
                tBranch(ex, ey, ez, ex + cosS_N(a2), ey + 1.0l, ez + sinS_N(a2), 0.0l, 0.0l, raw);
            }
        }
    }
    if (snapped) {
        double a = gAngle();
        int len = imin(8, h - standing + 1);
        lying(t, floorD_N(LX(c, 0) + cosS_N(a) * 2.0l), floorD_N(LZ(c, 0) + sinS_N(a) * 2.0l), a, len, raw, false);
    }
}
[noinline]
void shFallenLog(Grow t) {
    uint sp = t.sp;
    int len = gHeight(t);
    double a = gAngle();
    uint raw = spU(sp, S_LOG);
    int laid = lying(t, t.x, t.z, a, len, raw, true);
    if (laid < 3) return;
    if (spI(sp, S_TRUNK) >= 2) {
        double pa = a + TWO_PI_HALF;
        lying(t, floorD_N(double(t.x) + 0.5l + cosS_N(pa)), floorD_N(double(t.z) + 0.5l + sinS_N(pa)), a, laid, raw, true);
    }
    int bx = floorD_N(double(t.x) + 0.5l - cosS_N(a)), bz = floorD_N(double(t.z) + 0.5l - sinS_N(a));
    if (rngIntB(3) == 0) {
        double pa = a + TWO_PI_HALF;
        double cp = cosS_N(pa), sp2 = sinS_N(pa);
        for (int w = -2; w <= 2; w++)
            for (int up = 0; up <= 2; up++) {
                if (iabs(w) + up > 3 || rngIntB(5) == 0) continue;
                int px = floorD_N(double(bx) + 0.5l + cp * double(w)), pz = floorD_N(double(bz) + 0.5l + sp2 * double(w));
                tExtra(px, t.y + up, pz, rngIntB(3) == 0 ? tconst(TCN_COARSE) : tconst(TCN_ROOTED));
            }
    } else if (rngBool()) {
        tLog(bx, t.y, bz, axisOf(raw, 1u));
    }
}
[noinline]
void shStump(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    uint raw = spU(sp, S_LOG);
    uint log = axisOf(raw, 1u);
    for (int dy = 0; dy < h; dy++) tLog(t.x, t.y + dy, t.z, log);
    tSink(t.x, t.y, t.z, log, 3);
    for (uint d = 0; d < 4u; d++) {
        if (rngIntB(3) == 0) continue;
        uint ax = (d == 1u || d == 3u) ? 0u : 2u;  // east / west: x; north / south: z
        tAnchor(t.x + hdx(d), t.y - 1, t.z + hdz(d), axisOf(raw, ax));
        if (rngIntB(3) == 0) tLog(t.x + hdx(d), t.y, t.z + hdz(d), axisOf(raw, ax));
    }
    int roll = rngIntB(4);
    if (roll == 0) tExtra(t.x, t.y + h, t.z, tconst(TCN_MOSS));
    else if (roll == 1) tExtra(t.x, t.y + h, t.z, tconst(TCN_BROWN));
}
[noinline]
void shBamboo(Grow t) {
    int n = 8 + rngIntB(14);
    double rad = double(spF(t.sp, S_RADIUS)) * gu(0.8l, 1.2l);
    for (int i = 0; i < n; i++) {
        double a = gAngle();
        double d = sqrtD_N(rngDouble()) * rad;
        int x = floorD_N(double(t.x) + 0.5l + cosS_N(a) * d), z = floorD_N(double(t.z) + 0.5l + sinS_N(a) * d);
        int y = floraGround(sB.box, x, z, t.y);
        if (y == NONE || iabs(y - t.y) > 3) continue;
        if (!bis(gGet(sB.box, x, y - 1, z), B_BAMBOO_SOIL)) continue;
        int hh = gHeight(t);
        int h = d2i(double(hh) * (1.1l - divD_N(0.5l * d, rad)));
        for (int k = 0; k < h; k++) {
            uint lv = k >= h - 2 ? 2u : (k == h - 3 ? 1u : 0u);
            tExtra(x, y + k, z, tconst(TCN_BAMBOO + lv * 2u + (k == h - 1 ? 1u : 0u)));
        }
    }
}
[noinline]
void shCrystal(Grow t) {
    uint sp = t.sp;
    int h = gHeight(t);
    Line c = lineOf();
    trunkLine(t, h, 1.0l, c);
    trunkDraw(t, c, 0, h - 2, 0.0l, 0.0l, 0.8l);
    int limbs = spI(sp, S_LIMBS) + rngIntB(2);
    bool facing = spU(sp, S_EXTRA_FACING) != 0u;
    for (int i = 0; i < limbs; i++) {
        int dy = imax(2, d2i(double(h) * gu(0.45l, 0.9l)));
        double a = gAngle(), el = gu(30.0l, 65.0l) * DEG2RAD, l = gu(2.0l, double(spF(sp, S_RADIUS) + 1.0f));  // float + int: float
        double ce = cosS_N(el);
        double ex = LX(c, dy) + cosS_N(a) * ce * l, ez = LZ(c, dy) + sinS_N(a) * ce * l;
        double ey = double(t.y + dy) + sinS_N(el) * l;
        tBranch(LX(c, dy), double(t.y + dy), LZ(c, dy), ex, ey, ez, 0.0l, 0.0l, t.leaves);
        if (facing) tExtra(floorD_N(ex), floorD_N(ey) + 1, floorD_N(ez), spU(sp, S_EXTRA_UP));
    }
    if (facing) {
        tBranch(LX(c, h - 2), double(t.y + h - 2), LZ(c, h - 2), LX(c, h), double(t.y + h), LZ(c, h), 0.0l, 0.0l, t.leaves);
        tExtra(floorD_N(LX(c, h)), t.y + h + 1, floorD_N(LZ(c, h)), spU(sp, S_EXTRA_UP));
    }
}

// FloraTreeFeature.place: cfg [0] species, [1] stands (float), [2] stand scale (float), then (species offset, weight)
[noinline]
uint treePick(uint cfg, int x, int z, double roll) {
    uint n = fprog[cfg];
    if (n == 0u) return NO_STATE;
    if (n == 1u) return fprog[cfg + 3];
    float stands = pfF(cfg + 1);
    double scale = double(pfF(cfg + 2));
    int64_t seed = int64_t(pfU64(H_SEED));
    double w[24];
    double total = 0.0l;
    for (uint i = 0; i < n && i < 24u; i++) {
        double nz = stands > 0.0f ? fbm_N(divD_N(double(x), scale), divD_N(double(z), scale), seed + 1013 * int64_t(i + 1u), 2) : 0.0l;
        w[i] = double(int(fprog[cfg + 4 + i * 2])) * expS_N(double(stands) * 3.2l * nz);
        total += w[i];
    }
    double tt = roll * total;
    for (uint i = 0; i < n && i < 24u; i++) {
        tt -= w[i];
        if (tt <= 0.0l) return fprog[cfg + 3 + i * 2];
    }
    return fprog[cfg + 3 + (n - 1u) * 2];
}
[ForceInline]
void treeTrace(uint stage, int x, int y, int z, uint extra) {
    if (!TREE_TRACE) return;
    uint at = sG.base + SCR_MISC + M_DBG, n = scratch[at];
    if (n >= 170u) return;
    uint w = at + 1u + n * 6u;
    scratch[w] = stage; scratch[w + 1] = uint(x); scratch[w + 2] = uint(y); scratch[w + 3] = uint(z);
    scratch[w + 4] = scratch[sRA + 5u]; scratch[w + 5] = extra;
    scratch[at] = n + 1u;
}
[noinline]
bool placeTree(uint cfg, int x, int y, int z) {
    bool placed = placeTreeAt(cfg, x, y, z);
    return placed;
}
[noinline]
bool placeTreeAt(uint cfg, int x, int y, int z) {
    GBox box = gbox(x, z);
    int64_t seed = int64_t(pfU64(H_SEED));
    uint sp = treePick(cfg, x, z, rngDouble());
    if (sp == NO_STATE) return false;
    if (!maskClear(x, z, spI(sp, S_REACH))) return false;
    int water = imax(0, heightAt(HM_WORLD_SURFACE, x, z) - y);
    if (water > spI(sp, S_MAXWATER)) return false;
    uint here = gGet(box, x, y, z);
    if (!bis(here, B_SOFT) || bis(here, B_LOGS)) return false;
    uint soil = spU(sp, S_SOIL), ground = gGet(box, x, y - 1, z);
    uint fits = ((soil & 1u) != 0u && bis(ground, B_SOIL_DIRT)) || ((soil & 2u) != 0u && bis(ground, B_SOIL_SAND)) ||
                ((soil & 4u) != 0u && bis(ground, B_SOIL_STONE)) || ((soil & 8u) != 0u && bis(ground, B_SOIL_ASH)) ||
                ((soil & 16u) != 0u && bis(ground, B_SOIL_SNOW)) ? 1u : 0u;
    if (fits == 0u) return false;
    if (y > (MAX_Y + 1) - spI(sp, S_MAXH) - 12) return false;
    sB = treeB(box);
    fmInit(sB.leaves, 16u);
    fmInit(sB.logs, 16u);
    fmInit(sB.extras, 16u);
    fmInit(sB.anchors, 16u);
    double wind = valueNoise_N(divD_N(double(x), 900.0l), divD_N(double(z), 900.0l), seed + 31) * PI_D * 0.8l + PI_D * 0.15l;
    Grow t;
    t.sp = sp;
    t.x = x; t.y = y; t.z = z;
    t.windAngle = wind;
    t.water = water;
    t.leaves = spU(sp, S_LEAVES);
    t.accent = spU(sp, S_ACCENT);
    // realmTint: the lands beyond Tamriel recolour some foliage (by the map's realm): not here yet
    if (!(x >= int(tconst(TCN_MINX)) && x < int(tconst(TCN_MAXX)) && z >= int(tconst(TCN_MINZ)) && z < int(tconst(TCN_MAXZ)))) {
        flag(FL_UNSUPPORTED);
        return false;
    }
    uint shape = spU(sp, S_SHAPE);
    if (shape == SH_CONIFER) shConifer(t);
    else if (shape == SH_BROADLEAF) shBroadleaf(t);
    else if (shape == SH_ASPEN) shAspen(t);
    else if (shape == SH_WILLOW) shWillow(t);
    else if (shape == SH_COLUMNAR) shColumnar(t);
    else if (shape == SH_BALD_CYPRESS) shBaldCypress(t);
    else if (shape == SH_MANGROVE) shMangrove(t);
    else if (shape == SH_PALM) shPalm(t);
    else if (shape == SH_ACACIA) shAcacia(t);
    else if (shape == SH_BAOBAB) shBaobab(t);
    else if (shape == SH_JUNGLE) shJungle(t);
    else if (shape == SH_TWISTED) shTwisted(t);
    else if (shape == SH_SHRUB) shShrub(t);
    else if (shape == SH_DEAD) shDead(t);
    else if (shape == SH_FALLEN_LOG) shFallenLog(t);
    else if (shape == SH_STUMP) shStump(t);
    else if (shape == SH_BAMBOO) shBamboo(t);
    else shCrystal(t);
    if (spU(sp, S_ACCENT_TOP) != 0u && t.accent != NO_STATE) tTint(t.leaves, t.accent, double(spF(sp, S_ACCENT_CH)), true);
    if ((params.store & 4u) != 0u) {
        tCommitBegin(x, y, z);  // (its phases at the origin's next rounds, within their budget: featBody)
        return true;
    }
    return tCommit(x, y, z);
}


// ---- DiskFeature (disk_sand, disk_clay, disk_gravel) -----------------------------------------------------------
// cfg: [0] the state provider (rule based), [1] the target predicate, [2..5] the radius, [6] half height
// providers here: kind 2 rule based: n, fallback (0 none), then n (predicate, provider) pairs; the inner providers
// simple or weighted (provider()), NO_STATE when nothing applies (getOptionalState's null)
[noinline]
uint providerOpt(uint p, int x, int y, int z) {
    uint kind = fprog[p];
    if (kind != 2u) return provider(p);
    uint n = fprog[p + 1];
    for (uint i = 0; i < n; i++) {
        uint pred = fprog[p + 3 + i * 2], then = fprog[p + 4 + i * 2];
        if (predicate(pred, x, y, z)) return provider(then);
    }
    uint fb = fprog[p + 2];
    return fb == 0u ? NO_STATE : provider(fb);
}
[noinline]
bool placeDisk(uint cfg, int ox, int oy, int oz) {
    int half = int(fprog[cfg + 6]);
    int top = oy + half, bottom = oy - half - 1;
    int rr = intProvider(cfg + 2);
    bool any = false;
    // BlockPos.betweenClosed: x fastest, then y, then z
    for (int dz = -rr; dz <= rr; dz++)
        for (int dx = -rr; dx <= rr; dx++) {
            if (dx * dx + dz * dz > rr * rr) continue;
            int x = ox + dx, z = oz + dz;
            for (int y = top; y > bottom; y--) {
                if (!predicate(fprog[cfg + 1], x, y, z)) continue;
                uint st = providerOpt(fprog[cfg], x, y, z);
                if (st == NO_STATE) continue;
                setBlock(x, y, z, st, true);
                any = true;
            }
        }
    return any;
}

// SpringFeature: cfg [0] the fluid's block, [1] requires a block below, [2] rock count, [3] hole count, [4] the
// valid blocks' set
[noinline]
bool placeSpring(uint cfg, int x, int y, int z) {
    uint valid = fprog[cfg + 4];
    if (!inSet(valid, blockAt(x, y + 1, z))) return false;
    if (fprog[cfg + 1] != 0u && !inSet(valid, blockAt(x, y - 1, z))) return false;
    uint here = blockAt(x, y, z);
    if (!bis(here, B_AIR) && !inSet(valid, here)) return false;
    uint w = blockAt(x - 1, y, z), e = blockAt(x + 1, y, z), n = blockAt(x, y, z - 1), s = blockAt(x, y, z + 1),
         d = blockAt(x, y - 1, z);
    int rocks = (inSet(valid, w) ? 1 : 0) + (inSet(valid, e) ? 1 : 0) + (inSet(valid, n) ? 1 : 0) + (inSet(valid, s) ? 1 : 0) +
                (inSet(valid, d) ? 1 : 0);
    int holes = (bis(w, B_AIR) ? 1 : 0) + (bis(e, B_AIR) ? 1 : 0) + (bis(n, B_AIR) ? 1 : 0) + (bis(s, B_AIR) ? 1 : 0) + (bis(d, B_AIR) ? 1 : 0);
    if (rocks == int(fprog[cfg + 2]) && holes == int(fprog[cfg + 3])) {
        setBlock(x, y, z, fprog[cfg], true);
        return true;
    }
    return false;
}

// LakeFeature (lake_lava_underground, lake_lava_surface): cfg [0] fluid provider, [1] barrier provider, [2] can place,
// [3] can replace with air or fluid, [4] can replace with barrier (predicates), [5] cave air, [6] ice, [7] block light
// below 10 where nothing is loaded, [8] the fluid is water
bool lakeBit(uint at, int i) { return ((scratch[at + uint(i >> 5)] >> uint(i & 31)) & 1u) != 0u; }
[noinline]
bool lakeEdge(uint at, int xx, int zz, int yy) {
    return !lakeBit(at, (xx * 16 + zz) * 8 + yy) &&
           ((xx < 15 && lakeBit(at, ((xx + 1) * 16 + zz) * 8 + yy)) || (xx > 0 && lakeBit(at, ((xx - 1) * 16 + zz) * 8 + yy)) ||
            (zz < 15 && lakeBit(at, (xx * 16 + zz + 1) * 8 + yy)) || (zz > 0 && lakeBit(at, (xx * 16 + (zz - 1)) * 8 + yy)) ||
            (yy < 7 && lakeBit(at, (xx * 16 + zz) * 8 + yy + 1)) || (yy > 0 && lakeBit(at, (xx * 16 + zz) * 8 + (yy - 1))));
}
[noinline]
bool placeLake(uint cfg, int ox, int oy, int oz) {
    if (oy <= MIN_Y + 4) return false;
    int x0 = ox - 8, y0 = oy - 4, z0 = oz - 8;
    uint grid = sG.base + SCR_MISC + M_LAKE;
    for (uint i = 0; i < 64u; i++) scratch[grid + i] = 0u;
    CLK0(l0);
    int spots = rngIntB(4) + 4;
    if ((params.store & 8u) != 0u) {
        // (the store, | 8) the spots' draws here, their cells and the edge tests on many threads (featLakeWideMain,
        // right after this round), the fill, the barrier and the freeze at the origin's next rounds within their
        // budget (lakeResume): the same draws in the same order, the same writes in the same order
        uint lk = lkAt();
        for (int i = 0; i < spots; i++) {
            uint sp = lk + LK_SPOTS + uint(i) * 12u;
            double xr = rngDouble() * 6.0l + 3.0l;
            double yr = rngDouble() * 4.0l + 2.0l;
            double zr = rngDouble() * 6.0l + 3.0l;
            double xp = rngDouble() * (16.0l - xr - 2.0l) + 1.0l + xr / 2.0l;
            double yp = rngDouble() * (8.0l - yr - 4.0l) + 2.0l + yr / 2.0l;
            double zp = rngDouble() * (16.0l - zr - 2.0l) + 1.0l + zr / 2.0l;
            scrSetD(sp, xr); scrSetD(sp + 2u, yr); scrSetD(sp + 4u, zr);
            scrSetD(sp + 6u, xp); scrSetD(sp + 8u, yp); scrSetD(sp + 10u, zp);
        }
        scratch[lk + LK_FLUID] = provider(fprog[cfg]);
        scratch[lk + LK_CFG] = cfg;
        scratch[lk + LK_X0] = uint(x0); scratch[lk + LK_Y0] = uint(y0); scratch[lk + LK_Z0] = uint(z0);
        scratch[lk + LK_NSPOTS] = uint(spots);
        scratch[lk + LK_FAIL] = 0u;
        scratch[lk + LK_PHASE] = 0u;
        scratch[lk + LK_CUR] = 0u;
        scratch[lk + LK_MAGIC] = LAKE_PENDING;
        CLK1(9, l0);
        return true;
    }
    for (int i = 0; i < spots; i++) {
        double xr = rngDouble() * 6.0l + 3.0l;
        double yr = rngDouble() * 4.0l + 2.0l;
        double zr = rngDouble() * 6.0l + 3.0l;
        double xp = rngDouble() * (16.0l - xr - 2.0l) + 1.0l + xr / 2.0l;
        double yp = rngDouble() * (8.0l - yr - 4.0l) + 2.0l + yr / 2.0l;
        double zp = rngDouble() * (16.0l - zr - 2.0l) + 1.0l + zr / 2.0l;
        for (int xx = 1; xx < 15; xx++)
            for (int zz = 1; zz < 15; zz++)
                for (int yy = 1; yy < 7; yy++) {
                    double xd = divD_N(double(xx) - xp, xr / 2.0l);
                    double yd = divD_N(double(yy) - yp, yr / 2.0l);
                    double zd = divD_N(double(zz) - zp, zr / 2.0l);
                    if (xd * xd + yd * yd + zd * zd < 1.0l) {
                        int b = (xx * 16 + zz) * 8 + yy;
                        scratch[grid + uint(b >> 5)] |= 1u << uint(b & 31);
                    }
                }
    }
    CLK1(9, l0);
    CLK0(l1);
    uint fluid = provider(fprog[cfg]);
    for (int xx = 0; xx < 16; xx++)
        for (int zz = 0; zz < 16; zz++)
            for (int yy = 0; yy < 8; yy++) {
                if (!lakeEdge(grid, xx, zz, yy)) continue;
                uint st = blockAt(x0 + xx, y0 + yy, z0 + zz);
                if (yy >= 4 && (bflags2(st) & F2_LIQUID_PROP) != 0u) return false;
                if (yy < 4 && !bis(st, B_SOLID) && st != fluid) return false;
                if (!predicate(fprog[cfg + 2], x0 + xx, y0 + yy, z0 + zz)) return false;
            }
    CLK1(10, l1);
    CLK0(l2);
    for (int xx = 0; xx < 16; xx++)
        for (int zz = 0; zz < 16; zz++)
            for (int yy = 0; yy < 8; yy++) {
                if (!lakeBit(grid, (xx * 16 + zz) * 8 + yy)) continue;
                if (predicate(fprog[cfg + 3], x0 + xx, y0 + yy, z0 + zz)) setBlock(x0 + xx, y0 + yy, z0 + zz, yy >= 4 ? fprog[cfg + 5] : fluid, true);
            }
    CLK1(11, l2);
    CLK0(l3);
    uint barrier = provider(fprog[cfg + 1]);
    if (!bis(barrier, B_AIR)) {
        for (int xx = 0; xx < 16; xx++)
            for (int zz = 0; zz < 16; zz++)
                for (int yy = 0; yy < 8; yy++) {
                    if (!lakeEdge(grid, xx, zz, yy)) continue;
                    if (!(yy < 4 || rngIntB(2) != 0)) continue;
                    uint st = blockAt(x0 + xx, y0 + yy, z0 + zz);
                    if (bis(st, B_SOLID) && predicate(fprog[cfg + 4], x0 + xx, y0 + yy, z0 + zz)) setBlock(x0 + xx, y0 + yy, z0 + zz, barrier, true);
                }
    }
    CLK1(12, l3);
    if (fprog[cfg + 8] != 0u) {
        for (int xx = 0; xx < 16; xx++)
            for (int zz = 0; zz < 16; zz++) {
                int x = x0 + xx, y = y0 + 4, z = z0 + zz;
                int b = biomeAt(x, y, z);
                bool freeze = !(biomeTemperature(b, x, y, z) >= 0.15f) && y >= MIN_Y && y <= MAX_Y && fprog[cfg + 7] != 0u;
                if (freeze) {
                    uint bs = blockAt(x, y, z);
                    freeze = bis(bs, B_WATER) && (bflags2(bs) & F2_LIQUID) != 0u;
                }
                if (freeze && predicate(fprog[cfg + 3], x, y, z)) setBlock(x, y, z, fprog[cfg + 6], true);
            }
    }
    return true;
}

// (the store, | 8) a lake in parts: its state in M_ORE (free outside the ores' records; the cells stay in M_LAKE):
// magic, the phase (0 the wide passes next, 1 the fill, 2 the barrier, 3 the freeze), the phase's cursor, the box's
// corner, the config, the fluid, the barrier, the edge tests' verdict, the spots, then each spot's six doubles
static const uint LAKE_PENDING = 0x4C414B45u;  // "LAKE"
static const uint LK_MAGIC = 0, LK_PHASE = 1, LK_CUR = 2, LK_X0 = 3, LK_Y0 = 4, LK_Z0 = 5, LK_CFG = 6, LK_FLUID = 7, LK_BARRIER = 8,
                  LK_FAIL = 9, LK_NSPOTS = 10, LK_SPOTS = 16;  // (16 + 7 spots x 12 words = 100 < M_ORE's 512)
uint lkAt() { return sG.base + SCR_MISC + M_ORE; }
// (featBody) the pending lake's rest, as placeLake's serial code goes, within `budget` operations (a cell skipped 1, a
// cell tested or written 16, a column frozen 16); true when it ended
[noinline]
bool lakeResume(uint budget) {
    uint lk = lkAt(), grid = sG.base + SCR_MISC + M_LAKE;
    uint cfg = scratch[lk + LK_CFG], fluid = scratch[lk + LK_FLUID];
    int x0 = int(scratch[lk + LK_X0]), y0 = int(scratch[lk + LK_Y0]), z0 = int(scratch[lk + LK_Z0]);
    uint phase = scratch[lk + LK_PHASE], cur = scratch[lk + LK_CUR], ops = 0u;
    if (phase == 0u) {
        // (the edge tests: any failing one ends the lake, placeLake's false)
        if (scratch[lk + LK_FAIL] != 0u) {
            scratch[lk + LK_MAGIC] = 0u;
            return true;
        }
        phase = 1u;
        cur = 0u;
    }
    if (phase == 1u) {
        CLK0(l2);
        for (; cur < 2048u && ops < budget; cur++) {
            int xx = int(cur >> 7), zz = int((cur >> 3) & 15u), yy = int(cur & 7u);
            ops++;
            if (!lakeBit(grid, (xx * 16 + zz) * 8 + yy)) continue;
            ops += 15u;
            if (predicate(fprog[cfg + 3], x0 + xx, y0 + yy, z0 + zz)) setBlock(x0 + xx, y0 + yy, z0 + zz, yy >= 4 ? fprog[cfg + 5] : fluid, true);
        }
        CLK1(11, l2);
        if (cur < 2048u) {
            scratch[lk + LK_PHASE] = phase;
            scratch[lk + LK_CUR] = cur;
            return false;
        }
        uint barrier = provider(fprog[cfg + 1]);
        scratch[lk + LK_BARRIER] = barrier;
        phase = bis(barrier, B_AIR) ? 3u : 2u;
        cur = 0u;
    }
    if (phase == 2u) {
        CLK0(l3);
        uint barrier = scratch[lk + LK_BARRIER];
        for (; cur < 2048u && ops < budget; cur++) {
            int xx = int(cur >> 7), zz = int((cur >> 3) & 15u), yy = int(cur & 7u);
            ops++;
            if (!lakeEdge(grid, xx, zz, yy)) continue;
            ops += 15u;
            if (!(yy < 4 || rngIntB(2) != 0)) continue;
            uint st = blockAt(x0 + xx, y0 + yy, z0 + zz);
            if (bis(st, B_SOLID) && predicate(fprog[cfg + 4], x0 + xx, y0 + yy, z0 + zz)) setBlock(x0 + xx, y0 + yy, z0 + zz, barrier, true);
        }
        CLK1(12, l3);
        if (cur < 2048u) {
            scratch[lk + LK_PHASE] = phase;
            scratch[lk + LK_CUR] = cur;
            return false;
        }
        phase = 3u;
        cur = 0u;
    }
    if (fprog[cfg + 8] != 0u) {
        CLK0(l4);
        for (; cur < 256u && ops < budget; cur++) {
            int xx = int(cur >> 4), zz = int(cur & 15u);
            ops += 16u;
            int x = x0 + xx, y = y0 + 4, z = z0 + zz;
            int b = biomeAt(x, y, z);
            bool freeze = !(biomeTemperature(b, x, y, z) >= 0.15f) && y >= MIN_Y && y <= MAX_Y && fprog[cfg + 7] != 0u;
            if (freeze) {
                uint bs = blockAt(x, y, z);
                freeze = bis(bs, B_WATER) && (bflags2(bs) & F2_LIQUID) != 0u;
            }
            if (freeze && predicate(fprog[cfg + 3], x, y, z)) setBlock(x, y, z, fprog[cfg + 6], true);
        }
        CLK1(13, l4);
        if (cur < 256u) {
            scratch[lk + LK_PHASE] = phase;
            scratch[lk + LK_CUR] = cur;
            return false;
        }
    }
    scratch[lk + LK_MAGIC] = 0u;
    return true;
}

[noinline]
bool placeFeature<let T : uint>(uint rec, int x, int y, int z) {
    uint type = T == 0u ? fprog[rec + R_TYPE] : T, cfg = fprog[rec + R_CFG];
#if FEAT_CLOCK
    CLK0(p0);
    bool r = placeFeatureOf<T>(type, cfg, x, y, z);
    CLK1(1, p0);
    return r;
}
[noinline]
bool placeFeatureOf<let T : uint>(uint type, uint cfg, int x, int y, int z) {
#endif
    if (type == FT_ORE) return placeOre(cfg, x, y, z);
    if (type == FT_COVER) return placeCover(cfg, x, y, z);
    if (type == FT_ROCK) return placeRock(cfg, x, y, z);
    if (type == FT_TREE) return placeTree(cfg, x, y, z);
    if (type == FT_FREEZE) return placeFreeze(cfg, x, y, z);
    if (type == FT_DISK) return placeDisk(cfg, x, y, z);
    if (type == FT_SPRING) return placeSpring(cfg, x, y, z);
    if (type == FT_LAKE) return placeLake(cfg, x, y, z);
    flag(FL_UNSUPPORTED);
    return false;
}

// ---- placements (FeaturePlacer: a stack of positions, each modifier's outputs pushed in reverse) ----------------
static const int MAXP = 288;
// ScatterPlacement (flora_scatter): the live, uncrowded points of a world-wide jittered grid inside the chunk
[noinline]
bool scatterAlive(uint m, int64_t worldSeed, int h, int x, int z) {
    // the words: spacing, min distance, pad, density, floor, field
    float density = pfF(m + 4), floorV = pfF(m + 5);
    uint field = hdr(H_FIELDS) + fprog[m + 6] * FIELD_W;
    double p = double(floorV) + double(density - floorV) * fieldSample_N(field, worldSeed, double(x), double(z));
    return unit01(int(uint(h) * 0x5BD1E995u ^ 0x68E31DA4u)) < p;
}
// the stack (x, y, z, modifier) and the outputs (x, y, z) in scratch
struct PosOut {
    uint at;  // scratch offset of the outputs
    int n;
};
[noinline]
void out1(inout PosOut o, int x, int y, int z) {
    if (o.n >= MAXP) return;
    uint w = o.at + uint(o.n) * 3u;
    scratch[w] = uint(x); scratch[w + 1] = uint(y); scratch[w + 2] = uint(z);
    o.n++;
}
[noinline]
// The placement interpreter, resumable: the stack lives in scratch, an entry whose modifier index is the modifier
// count is a placement. Runs until the stack is empty or `budget` placements are made; true when work is left (the
// stack depth and the random are kept in the origin's meta words for the next dispatch).
[ForceInline]
bool runPlaced<let T : uint>(uint rec, bool first, uint budget) {
    uint nm = fprog[rec + R_NMODS], mods = fprog[rec + R_MODS];
    int ox = sG.cx * 16, oy = MIN_Y, oz = sG.cz * 16;
    int64_t worldSeed = int64_t(pfU64(H_SEED));
    uint stack = sG.base + SCR_MISC + M_STACK;
    int sp = 0;
    if (first) {
        scratch[stack] = uint(ox); scratch[stack + 1] = uint(oy); scratch[stack + 2] = uint(oz); scratch[stack + 3] = 0u;
        sp = 1;
    } else {
        sp = int(scratch[sG.base + SCR_META + 5u]);
    }
    uint placed = 0u;
    PosOut o;
    o.at = sG.base + SCR_MISC + M_OUT;
    for (int guard = 0; guard < 100000 && sp > 0 && placed < budget; guard++) {
        sp--;
        uint sw = stack + uint(sp) * 4u;
        int x = int(scratch[sw]), y = int(scratch[sw + 1]), z = int(scratch[sw + 2]), mi = int(scratch[sw + 3]);
        if (uint(mi) >= nm) {
            placeFeature<T>(rec, x, y, z);
            placed++;
            if ((params.store & 2u) != 0u && scratch[sG.base + SCR_MISC + M_LINE] == VEIN_PENDING) break;  // (the vein kernel next)
            if ((params.store & 4u) != 0u && scratch[tcAt() + TC_MAGIC] == TREE_PENDING) break;  // (its commit next rounds)
            if ((params.store & 8u) != 0u && scratch[lkAt() + LK_MAGIC] == LAKE_PENDING) break;  // (its cells, then its rest)
            if ((params.store & 16u) != 0u && scratch[rkAt() + RK_MAGIC] == ROCK_PENDING) break;  // (its rocks' shapes)
            continue;
        }
        uint m = mods + uint(mi) * MOD_W;
        uint kind = fprog[m];
        o.n = 0;
        if (kind == M_COUNT) {
            int n = intProvider(m + 1);
            if (n > MAXP) flag(FL_STACK);
            for (int i = 0; i < n; i++) out1(o, x, y, z);
        } else if (kind == M_IN_SQUARE) {
            int ax = rngIntB(16) + x;
            int az = rngIntB(16) + z;
            out1(o, ax, y, az);
        } else if (kind == M_HEIGHTMAP) {
            int h = heightAt(fprog[m + 1], x, z);
            if (h > MIN_Y) out1(o, x, h, z);
        } else if (kind == M_BIOME) {
            if (biomeBit(fprog[rec + R_ANY], biomeAt(x, y, z))) out1(o, x, y, z);
        } else if (kind == M_RARITY) {
            if (rngFloat() < pfF(m + 1)) out1(o, x, y, z);
        } else if (kind == M_HEIGHT_RANGE) {
            int hy = heightProvider(m + 1);
            out1(o, x, hy, z);
        } else if (kind == M_FLORA_SCATTER) {
            int s = int(fprog[m + 1]);
            float minDistance = pfF(m + 2);
            uint field = hdr(H_FIELDS) + fprog[m + 6] * FIELD_W;
            int64_t seed = fieldSeed(field, worldSeed) ^ int64_t(uint64_t(0x7F4A7C15u) * uint64_t(int64_t(s)));
            int x0 = x & ~15, z0 = z & ~15;
            int ci0 = floorDivI(x0, s), ci1 = floorDivI(x0 + 15, s);
            int cj0 = floorDivI(z0, s), cj1 = floorDivI(z0 + 15, s);
            double minD2 = double(float(s) * minDistance * float(s) * minDistance);
            for (int i = ci0; i <= ci1; i++)
                for (int j = cj0; j <= cj1; j++) {
                    int h = floraHash(i, j, seed);
                    int qx0 = i * s + d2i(unit01(h) * double(s));
                    int qz0 = j * s + d2i(unit01(int(uint(h) * 0x2C1B3C6Du + 0x297A2D39u)) * double(s));
                    if (qx0 < x0 || qx0 > x0 + 15 || qz0 < z0 || qz0 > z0 + 15) continue;
                    if (!scatterAlive(m, worldSeed, h, qx0, qz0)) continue;
                    bool keep = true;
                    for (int di = -1; di <= 1 && keep; di++)
                        for (int dj = -1; dj <= 1; dj++) {
                            if (di == 0 && dj == 0) continue;
                            int hn = floraHash(i + di, j + dj, seed);
                            if ((uint(hn) * 0x165667B1u) <= (uint(h) * 0x165667B1u)) continue;
                            int qx = (i + di) * s + d2i(unit01(hn) * double(s));
                            int qz = (j + dj) * s + d2i(unit01(int(uint(hn) * 0x2C1B3C6Du + 0x297A2D39u)) * double(s));
                            double d2 = double(qx - qx0) * double(qx - qx0) + double(qz - qz0) * double(qz - qz0);
                            if (d2 < minD2 && scatterAlive(m, worldSeed, hn, qx, qz)) { keep = false; break; }
                        }
                    if (keep) out1(o, qx0, y, qz0);
                }
        } else if (kind == M_FLORA_DENSITY) {
            float density = pfF(m + 1), floorV = pfF(m + 2);
            uint field = hdr(H_FIELDS) + fprog[m + 3] * FIELD_W;
            double keepP = double(floorV) + double(density - floorV) * fieldSample_N(field, worldSeed, double(x), double(z));
            if (rngDouble() < keepP) out1(o, x, y, z);
        } else if (kind == M_MASK) {
            if (maskClear(x, z, int(fprog[m + 1]))) out1(o, x, y, z);
        } else if (kind == M_SURF_REL) {
            int64_t sy = int64_t(heightAt(fprog[m + 1], x, z));
            if (sy + int64_t(int(fprog[m + 2])) <= int64_t(y) && int64_t(y) <= sy + int64_t(int(fprog[m + 3]))) out1(o, x, y, z);
        } else if (kind == M_ENV_SCAN) {
            int dx = int(fprog[m + 1]), dy = int(fprog[m + 2]), dz = int(fprog[m + 3]);
            uint target = fprog[m + 4], allowed = fprog[m + 5];
            int steps = int(fprog[m + 6]);
            int cxp = x, cyp = y, czp = z;
            if (predicate(allowed, cxp, cyp, czp)) {
                bool done = false;
                for (int i = 0; i < steps; i++) {
                    if (predicate(target, cxp, cyp, czp)) { out1(o, cxp, cyp, czp); done = true; break; }
                    cxp += dx; cyp += dy; czp += dz;
                    if (cyp < MIN_Y || cyp > MAX_Y) { done = true; break; }
                    if (!predicate(allowed, cxp, cyp, czp)) break;
                }
                if (!done && predicate(target, cxp, cyp, czp)) out1(o, cxp, cyp, czp);
            }
        } else if (kind == M_OFFSET) {
            int ax = intProvider(m + 1);
            int ay = intProvider(m + 5);
            int az = intProvider(m + 9);
            out1(o, x + ax, y + ay, z + az);
        } else if (kind == M_BLOCK_PRED) {
            if (predicate(fprog[m + 1], x, y, z)) out1(o, x, y, z);
        } else {
            flag(FL_UNSUPPORTED);
        }
        // the outputs, reversed so they come off in order (after the last modifier: placements)
        for (int i = o.n - 1; i >= 0; i--) {
            if (sp >= MAXP) { flag(FL_STACK); break; }
            uint ow = o.at + uint(i) * 3u, nw = stack + uint(sp) * 4u;
            scratch[nw] = scratch[ow]; scratch[nw + 1] = scratch[ow + 1]; scratch[nw + 2] = scratch[ow + 2]; scratch[nw + 3] = uint(mi + 1);
            sp++;
        }
    }
    scratch[sG.base + SCR_META + 5u] = uint(sp);
    return sp > 0;
}

// ---- kernels ---------------------------------------------------------------------------------------------------
[shader("compute")]
[numthreads(64, 1, 1)]
void featHmMain(uint3 id : SV_DispatchThreadID) {
    uint slot = id.y, c = id.x;
    if (c >= 256u || slot >= uint(params.slots)) return;
    if (params.store != 0u) slot = uint(targets[slot * 3u]);  // (the store: the slots listed: slot, cx, cz)
    uint lx = c & 15u, lz = c >> 4;
    for (uint t = 0; t < 6u; t++) {
        int h = MIN_Y;
        for (int y = MAX_Y; y >= MIN_Y; y--) {
            uint b = lx + 16u * lz + 256u * uint(y - MIN_Y);
            uint bid = slotBlock(slot, b);
            if (hmOpaque(t, bid)) { h = y + 1; break; }
        }
        chunkAux[slot * AUX_W + AUX_HM + t * 256u + c] = uint(h);
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void featInitMain(uint3 id : SV_DispatchThreadID) {
    uint o = id.y, i = id.x;
    if (o >= uint(params.origins)) return;
    sG = orgOf(o);
    // dispatched 64 x 64 threads per origin: the table and the marks cleared, the heightmaps of the 3 x 3 copied
    for (uint s = i; s < HT_CAP * 2u; s += 4096u) scratch[sG.base + SCR_HT + s] = 0u;
    for (uint s = i; s < 12288u; s += 4096u) scratch[sG.base + SCR_TESTED + s] = 0u;
    if (i < 9u * 256u) {
        int dcx = int((i / 256u) % 3u) - 1, dcz = int(i / 768u) - 1;
        uint c = i & 255u;
        int x = (sG.cx + dcx) * 16 + int(c & 15u), z = (sG.cz + dcz) * 16 + int(c >> 4);
        int slot = slotOf(sG.cx + dcx, sG.cz + dcz);
        for (int f = 0; f < 4; f++) {
            uint v = slot < 0 ? uint(MIN_Y) : chunkAux[uint(slot) * AUX_W + AUX_HM + hmTypeOfFinal(f) * 256u + c];
            scratch[sG.base + SCR_HM + uint(f) * 2304u + hmLocal(x, z)] = v;
        }
    }
    if (i == 0u) {
        for (uint m = 0; m < 16u; m++) scratch[sG.base + SCR_META + m] = 0u;
        scratch[sG.base + SCR_MISC + M_DBG] = 0u;
        uint bits[8] = {0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u};
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                int slot = slotOf(sG.cx + dx, sG.cz + dz);
                if (slot < 0) continue;
                for (uint w = 0; w < 1408u; w++) {
                    uint v = chunkAux[uint(slot) * AUX_W + AUX_QUARTS + w];
                    uint a = v & 0xFFFFu, b = v >> 16;
                    if (a < 256u) bits[a >> 5] |= 1u << (a & 31u);
                    if (b < 256u) bits[b >> 5] |= 1u << (b & 31u);
                }
            }
        for (uint w = 0; w < 8u; w++) scratch[sG.base + SCR_BIOMES + w] = bits[w];
    }
}

[noinline]
void featBody<let T : uint>(uint o) {
    sG = orgOf(o);
    uint rec = params.rec;
    uint meta = sG.base + SCR_META;
    sRA = meta + 6u;
    bool first = params.first != 0u;
    if ((params.store & 2u) != 0u) {
        // (a vein of the round before: its writes first; none can wait at a feature's first round, whatever the
        // trees left in M_LINE)
        if (first) scratch[sG.base + SCR_MISC + M_LINE] = 0u;
        else veinApply();
    }
    if ((params.store & 4u) != 0u) {
        // (a tree's commit under way: its next phases within the round's operation budget, and nothing else this
        // round; none can be pending at a feature's first round, whatever the lakes left in M_LAKE)
        if (first) {
            scratch[tcAt() + TC_MAGIC] = 0u;
        } else if (scratch[tcAt() + TC_MAGIC] == TREE_PENDING) {
            if (scratch[meta + 10u] != 1u) return;
            CLK0(t0);
            treeResume(params.budget);
            CLK1(16, t0);
            scratch[meta + 10u] = 1u;  // (more: the rest of the commit, or the placements after it)
            InterlockedAdd(counters[0], 1u);
            return;
        }
    }
    if ((params.store & 8u) != 0u) {
        // (a lake under way: its rest within the round's operation budget; once it ends, the placements after it in
        // the same round. None can be pending at a feature's first round, whatever the ores left in M_ORE)
        if (first) {
            scratch[lkAt() + LK_MAGIC] = 0u;
        } else if (scratch[lkAt() + LK_MAGIC] == LAKE_PENDING) {
            if (scratch[meta + 10u] != 1u) return;
            CLK0(t1);
            bool ended = lakeResume(params.budget);
            CLK1(17, t1);
            if (!ended) {
                InterlockedAdd(counters[0], 1u);  // (meta 10 stays 1: more)
                return;
            }
        }
    }
    if ((params.store & 16u) != 0u) {
        // (a rock placement under way: likewise)
        if (first) {
            scratch[rkAt() + RK_MAGIC] = 0u;
        } else if (scratch[rkAt() + RK_MAGIC] == ROCK_PENDING) {
            if (scratch[meta + 10u] != 1u) return;
            CLK0(t2);
            bool ended = rockResume(params.budget);
            CLK1(18, t2);
            if (!ended) {
                InterlockedAdd(counters[0], 1u);
                return;
            }
        }
    }
    if (first) {
        // applyBiomeDecoration: the feature runs when a biome the 3 x 3 holds lists it at this step
        uint here = fprog[rec + R_HERE];
        bool listed = false;
        for (uint w = 0; w < hdr(H_BIOMEWORDS) && w < 8u; w++)
            if ((scratch[sG.base + SCR_BIOMES + w] & fprog[here + w]) != 0u) { listed = true; break; }
        if (!listed) { scratch[meta + 10u] = 2u; return; }
        int64_t deco = decorationSeed(int64_t(pfU64(H_SEED)), sG.cx * 16, sG.cz * 16);
        featureSeed(deco, int(fprog[rec + R_GI]), int(fprog[rec + R_STEP]));
    } else {
        if (scratch[meta + 10u] != 1u) return;  // the random is still in meta 6..9
    }
    CLK0(b0);
    // (| 4: a tree a round, the budget the commit's; | 8, 16: the budget is the lake's or the rock's, the placements go on
    // until one is under way, which ends the round)
    uint placements = (params.store & 4u) != 0u ? 1u : ((params.store & 24u) != 0u ? 0x40000000u : params.budget);
    bool more = runPlaced<T>(rec, first, placements);
    CLK1(0, b0);
    more = more || ((params.store & 2u) != 0u && scratch[sG.base + SCR_MISC + M_LINE] == VEIN_PENDING);  // (its writes next round)
    more = more || ((params.store & 4u) != 0u && scratch[tcAt() + TC_MAGIC] == TREE_PENDING);  // (its commit next rounds)
    more = more || ((params.store & 8u) != 0u && scratch[lkAt() + LK_MAGIC] == LAKE_PENDING);  // (its rest next rounds)
    more = more || ((params.store & 16u) != 0u && scratch[rkAt() + RK_MAGIC] == ROCK_PENDING);  // (its rocks next rounds)
    scratch[meta + 10u] = more ? 1u : 2u;
    if (more) InterlockedAdd(counters[0], 1u);
}

// one kernel a feature type (each module holds only its own code): the host picks it by the record's type
// One origin a warp (a group of 32, one lane working): the decoration is serial code that diverges at once, and
// origins sharing a warp would run one after the other (a dispatch of trees then outlasted the watchdog)
[shader("compute")]
[numthreads(32, 1, 1)]
void featOreMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_ORE>(g.x);
}
[shader("compute")]
[numthreads(32, 1, 1)]
void featCoverMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_COVER>(g.x);
}
[shader("compute")]
[numthreads(32, 1, 1)]
void featRockMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_ROCK>(g.x);
}
[shader("compute")]
[numthreads(32, 1, 1)]
void featTreeMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_TREE>(g.x);
}
[shader("compute")]
[numthreads(32, 1, 1)]
void featFreezeMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_FREEZE>(g.x);
}
[shader("compute")]
[numthreads(32, 1, 1)]
void featDiskMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_DISK>(g.x);
}

[shader("compute")]
[numthreads(32, 1, 1)]
void featSpringMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_SPRING>(g.x);
}

[shader("compute")]
[numthreads(32, 1, 1)]
void featLakeMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (t.x == 0u && g.x < uint(params.origins)) featBody<FT_LAKE>(g.x);
}

// the C++ build: one origin a group (the host's threads take groups), every type
[shader("compute")]
[numthreads(1, 1, 1)]
void featOneMain(uint3 id : SV_DispatchThreadID) {
    if (id.x < uint(params.origins)) featBody<0u>(id.x);
}

// ---- an ore vein on many threads (the store, params.store | 2) --------------------------------------------------------
// placeOre left the vein's spheres (M_ORE) and its box (M_LINE: pending, cfg, xStart, yStart, zStart, sizeXZ, sizeY,
// size, discard, targets); featVeinMain's three passes (params.k) decide its blocks, a thread a position of the box:
// 0 clears the indices' words, 1 keeps each tested index's first visit (the smallest key: sphere, then x, y, z as the
// serial loops go), 2 decides that visit's block into the index's fate (target + 1 << 24 | the position). The origin's
// next round writes them (veinApply) before anything else.
// (featVeinMain's pass 3) each live sphere's box as the serial loop bounds it (xMin, yMin, zMin, xMax, yMax, zMax;
// a dead sphere's xMin above its xMax), after the pair loop: the passes after it test a position against six ints,
// not six exact floors a sphere
static const uint VEIN_BOX = SCR_TREE + 12288u;  // (64 spheres x 6 ints, past the fates)
[noinline]
void veinBox(uint i) {
    uint v = sG.base + SCR_MISC + M_LINE, data = sG.base + SCR_MISC + M_ORE, at = sG.base + VEIN_BOX + i * 6u;
    int xStart = int(scratch[v + 2u]), yStart = int(scratch[v + 3u]), zStart = int(scratch[v + 4u]);
    double rr = scrD(data + i * 8u + 6u);
    if (rr < 0.0l) {
        scratch[at] = 1u;
        scratch[at + 3u] = 0u;
        return;
    }
    double xx = scrD(data + i * 8u), yy = scrD(data + i * 8u + 2u), zz = scrD(data + i * 8u + 4u);
    int xMin = imax(floorD_N(xx - rr), xStart), yMin = imax(floorD_N(yy - rr), yStart), zMin = imax(floorD_N(zz - rr), zStart);
    int xMax = imax(floorD_N(xx + rr), xMin), yMax = imax(floorD_N(yy + rr), yMin), zMax = imax(floorD_N(zz + rr), zMin);
    scratch[at] = uint(xMin); scratch[at + 1u] = uint(yMin); scratch[at + 2u] = uint(zMin);
    scratch[at + 3u] = uint(xMax); scratch[at + 4u] = uint(yMax); scratch[at + 5u] = uint(zMax);
}
[noinline]
uint veinKeyOf(int x, int y, int z) {
    uint v = sG.base + SCR_MISC + M_LINE, data = sG.base + SCR_MISC + M_ORE;
    int xStart = int(scratch[v + 2u]), yStart = int(scratch[v + 3u]), zStart = int(scratch[v + 4u]);
    int sizeXZ = int(scratch[v + 5u]), sizeY = int(scratch[v + 6u]), size = int(scratch[v + 7u]);
    for (int i = 0; i < size; i++) {
        uint at = sG.base + VEIN_BOX + uint(i) * 6u;
        int xMin = int(scratch[at]), xMax = int(scratch[at + 3u]);
        if (xMin > xMax || x < xMin || x > xMax) continue;  // (dead, or not in its box: as the serial loops bound it)
        int yMin = int(scratch[at + 1u]), zMin = int(scratch[at + 2u]), yMax = int(scratch[at + 4u]), zMax = int(scratch[at + 5u]);
        if (y < yMin || y > yMax || z < zMin || z > zMax) continue;
        double rr = scrD(data + uint(i) * 8u + 6u);
        double xx = scrD(data + uint(i) * 8u), yy = scrD(data + uint(i) * 8u + 2u), zz = scrD(data + uint(i) * 8u + 4u);
        double xd = divD_N(double(x) + 0.5l - xx, rr);
        if (!(xd * xd < 1.0l)) continue;
        double yd = divD_N(double(y) + 0.5l - yy, rr);
        if (!(xd * xd + yd * yd < 1.0l)) continue;
        double zd = divD_N(double(z) + 0.5l - zz, rr);
        if (!(xd * xd + yd * yd + zd * zd < 1.0l) || y < MIN_Y || y > MAX_Y) continue;
        // (the top bit: a key never equals the serial loop's stamps, which share SCR_TESTED)
        return 0x80000000u | uint(i) << 14 | uint(x - xStart) << 9 | uint(y - yStart) << 5 | uint(z - zStart);
    }
    return 0xFFFFFFFFu;
}
groupshared uint gVeinFirst;    // (featVeinMain's pass 3: the first i2 that kills i1)
groupshared double gVeinR[64];  // (featVeinMain's pass 3: the spheres' radii)
[shader("compute")]
[numthreads(64, 1, 1)]
void featVeinMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (g.y >= uint(params.origins)) return;
    sG = orgOf(g.y);
    uint v = sG.base + SCR_MISC + M_LINE;
    if (scratch[v] != VEIN_PENDING) return;
    uint id = g.x * 64u + t.x;
    if (params.k == 3u) {
        // the spheres that hold another (placeOre's pair loop): for each i1 in order, the i2 after it in step, a thread
        // each. As the serial loop goes: i2 dies (dr > 0) only before the first i2 that kills i1 (dr <= 0), and after
        // i1's death nothing more changes in that pass (its radius -1 kills nothing)
        if (g.x != 0u) return;
        uint data = sG.base + SCR_MISC + M_ORE;
        int size = int(scratch[v + 7u]);
        int i2 = int(t.x);
        // (the radii in groupshared memory for the pass, written back at its end: every thread reads the same r1)
        if (i2 < size) gVeinR[i2] = scrD(data + uint(i2) * 8u + 6u);
        for (int i1 = 0; i1 < size - 1; i1++) {
            GroupMemoryBarrierWithGroupSync();
            double r1 = gVeinR[i1];
            if (t.x == 0u) gVeinFirst = uint(size);
            GroupMemoryBarrierWithGroupSync();
            if (r1 <= 0.0l) continue;  // (uniform: every thread read the same)
            bool kill2 = false;
            if (i2 > i1 && i2 < size) {
                double r2 = gVeinR[i2];
                if (!(r2 <= 0.0l)) {
                    double dx = scrD(data + uint(i1) * 8u) - scrD(data + uint(i2) * 8u), dy = scrD(data + uint(i1) * 8u + 2u) - scrD(data + uint(i2) * 8u + 2u),
                           dz = scrD(data + uint(i1) * 8u + 4u) - scrD(data + uint(i2) * 8u + 4u);
                    double dr = r1 - r2;
                    if (dr * dr > dx * dx + dy * dy + dz * dz) {
                        if (dr > 0.0l) kill2 = true;
                        else InterlockedMin(gVeinFirst, uint(i2));
                    }
                }
            }
            GroupMemoryBarrierWithGroupSync();
            uint first = gVeinFirst;
            if (kill2 && uint(i2) < first) gVeinR[i2] = -1.0l;
            if (t.x == 0u && first < uint(size)) gVeinR[i1] = -1.0l;
        }
        GroupMemoryBarrierWithGroupSync();
        if (i2 < size) {
            scrSetD(data + uint(i2) * 8u + 6u, gVeinR[i2]);
            veinBox(uint(i2));  // (its box from its final radius)
        }
        return;
    }
    if (params.k == 4u) {
        if (id >= VEIN_W) return;
        uint r = scratch[sG.base + SCR_TREE + id];
        if (r == 0u) return;
        uint cfg = scratch[v + 1u], tt = (r >> 24) - 1u;
        int x = int(scratch[v + 2u]) + int((r >> 10) & 31u), y = int(scratch[v + 3u]) + int((r >> 5) & 31u),
            z = int(scratch[v + 4u]) + int(r & 31u);
        htPutShared(keyOf(x, y, z), fprog[cfg + 3u + tt * 4u + 3u]);
        InterlockedAdd(scratch[sG.base + SCR_META + 3u], 1u);  // (setBlock's count of writes)
        return;
    }
    if (params.k == 0u) {
        if (id < VEIN_W) {
            scratch[sG.base + SCR_TESTED + id] = 0xFFFFFFFFu;
            scratch[sG.base + SCR_TREE + id] = 0u;
        }
        return;
    }
    int sizeXZ = int(scratch[v + 5u]), sizeY = int(scratch[v + 6u]);
    uint nx = uint(sizeXZ + 1), ny = uint(sizeY + 1);
    if (id >= nx * ny * nx) return;
    int xo = int(id % nx), yo = int((id / nx) % ny), zo = int(id / (nx * ny));
    int x = int(scratch[v + 2u]) + xo, y = int(scratch[v + 3u]) + yo, z = int(scratch[v + 4u]) + zo;
    uint key = veinKeyOf(x, y, z);  // (the first sphere that visits it)
    if (key == 0xFFFFFFFFu) return;
    int b = xo + yo * sizeXZ + zo * sizeXZ * sizeY;  // (vanilla's index: aliases kept)
    if (b < 0 || b >= 12288) {
        if (params.k == 1u) InterlockedOr(scratch[sG.base + SCR_META + 1u], FL_STACK);  // (as the serial loop's flag)
        return;
    }
    uint bi = uint(b);
    if (params.k == 1u) {
        InterlockedMin(scratch[sG.base + SCR_TESTED + bi], key);
        return;
    }
    if (scratch[sG.base + SCR_TESTED + bi] != key) return;  // (an earlier visit of its index had it)
    if (!writable(x, y, z)) return;
    uint cfg = scratch[v + 1u], nt = scratch[v + 9u];
    float discard = asfloat(scratch[v + 8u]);
    uint state = blockAt(x, y, z);
    for (uint tt = 0; tt < nt; tt++) {
        uint te = cfg + 3u + tt * 4u;
        if (!inSet(fprog[te + 1], state)) continue;
        if (discard >= 1.0f && adjacentAir(x, y, z)) continue;
        scratch[sG.base + SCR_TREE + bi] = (tt + 1u) << 24 | uint(xo) << 10 | uint(yo) << 5 | uint(zo);
        return;
    }
}
// ---- a lake's cells on many threads (the store, params.store | 8) ----------------------------------------------------
// placeLake left its spots (lkAt(), phase 0); right after the round, k 0: a thread a (spot, cell) sets the cell's bit
// when the spot's ellipsoid holds it (bits only ever set: any order gives the serial loop's grid); k 1: a thread a cell
// of the box, an edge cell's tests (placeLake's first loop returns false at the first failing one: the verdict is
// whether any fails, the tests only read)
[shader("compute")]
[numthreads(64, 1, 1)]
void featLakeWideMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (g.y >= uint(params.origins)) return;
    sG = orgOf(g.y);
    uint lk = lkAt(), grid = sG.base + SCR_MISC + M_LAKE;
    if (scratch[lk + LK_MAGIC] != LAKE_PENDING || scratch[lk + LK_PHASE] != 0u) return;
    uint id = g.x * 64u + t.x;
    if (params.k == 0u) {
        uint spot = id / 1176u, r = id % 1176u;  // (14 x 14 x 6 cells a spot: xx, zz 1..14, yy 1..6)
        if (spot >= scratch[lk + LK_NSPOTS]) return;
        int xx = 1 + int(r / 84u), zz = 1 + int((r / 6u) % 14u), yy = 1 + int(r % 6u);
        uint sp = lk + LK_SPOTS + spot * 12u;
        double xr = scrD(sp), yr = scrD(sp + 2u), zr = scrD(sp + 4u), xp = scrD(sp + 6u), yp = scrD(sp + 8u), zp = scrD(sp + 10u);
        double xd = divD_N(double(xx) - xp, xr / 2.0l);
        double yd = divD_N(double(yy) - yp, yr / 2.0l);
        double zd = divD_N(double(zz) - zp, zr / 2.0l);
        if (xd * xd + yd * yd + zd * zd < 1.0l) {
            int b = (xx * 16 + zz) * 8 + yy;
            InterlockedOr(scratch[grid + uint(b >> 5)], 1u << uint(b & 31));
        }
        return;
    }
    if (id >= 2048u) return;
    int xx = int(id >> 7), zz = int((id >> 3) & 15u), yy = int(id & 7u);
    if (!lakeEdge(grid, xx, zz, yy)) return;
    uint cfg = scratch[lk + LK_CFG], fluid = scratch[lk + LK_FLUID];
    int x0 = int(scratch[lk + LK_X0]), y0 = int(scratch[lk + LK_Y0]), z0 = int(scratch[lk + LK_Z0]);
    uint st = blockAt(x0 + xx, y0 + yy, z0 + zz);
    bool bad = (yy >= 4 && (bflags2(st) & F2_LIQUID_PROP) != 0u) || (yy < 4 && !bis(st, B_SOLID) && st != fluid) ||
               !predicate(fprog[cfg + 2], x0 + xx, y0 + yy, z0 + zz);
    if (bad) InterlockedOr(scratch[lk + LK_FAIL], 1u);
}

// ---- a rock's shape on many threads (the store, params.store | 16) ----------------------------------------------------
// rockIssue left the rock under way (rkAt(), phase 0); right after the round, a thread a word of its shape: the 32 cells
// rockOne's loop tests there (columns dx then dz, each dy upward), a bit each
[shader("compute")]
[numthreads(64, 1, 1)]
void featRockWideMain(uint3 g : SV_GroupID, uint3 t : SV_GroupThreadID) {
    if (g.y >= uint(params.origins)) return;
    sG = orgOf(g.y);
    uint rk = rkAt();
    if (scratch[rk + RK_MAGIC] != ROCK_PENDING || scratch[rk + RK_PHASE] != 0u || scratch[rk + RK_INLINE] != 0u) return;
    int ext = int(scratch[rk + RK_EXT]), ey = int(scratch[rk + RK_EY]);
    int fcx = int(scratch[rk + RK_FCX]), fcy = int(scratch[rk + RK_FCY]), fcz = int(scratch[rk + RK_FCZ]);
    uint side = uint(2 * ext + 1), tall = uint(2 * ey + 1), cells = side * side * tall;
    uint w = g.x * 64u + t.x;
    if (w * 32u >= cells) return;
    uint word = 0u;
    for (uint j = 0u; j < 32u; j++) {
        uint i = w * 32u + j;
        if (i >= cells) break;
        uint col = i / tall;
        int dy = int(i % tall) - ey, dx = int(col / side) - ext, dz = int(col % side) - ext;
        if (rockInside(fcx + dx, fcy + dy, fcz + dz)) word |= 1u << j;
    }
    scratch[sG.base + SCR_TREE + w] = word;
}

// htPut from many threads of one origin at once, each with its own key: a slot claimed by compare-exchange, the count
// by an atomic add (as htPut: a full table flags and drops the write)
[noinline]
void htPutShared(uint key, uint id) {
    uint s = htSlot(key);
    for (uint n = 0; n < HT_CAP; n++) {
        uint at = sG.base + SCR_HT + s * 2u;
        uint k = scratch[at];
        if (k == 0u) {
            // (the count reserved first: a full table drops the write and flags, as htPut)
            uint used;
            InterlockedAdd(scratch[sG.base + SCR_META], 1u, used);
            if (used >= HT_CAP * 3u / 4u) {
                InterlockedAdd(scratch[sG.base + SCR_META], 0xFFFFFFFFu);
                InterlockedOr(scratch[sG.base + SCR_META + 1u], FL_TABLE_FULL);
                return;
            }
            uint was;
            InterlockedCompareExchange(scratch[at], 0u, key, was);
            if (was == 0u) {
                scratch[at + 1u] = id;
                return;
            }
            InterlockedAdd(scratch[sG.base + SCR_META], 0xFFFFFFFFu);  // (another key took the slot: not ours)
            k = was;
        }
        if (k == key) {
            scratch[at + 1u] = id;
            return;
        }
        s = (s + 1u) & (HT_CAP - 1u);
    }
}
// (featBody, the store | 2) a vein's blocks were written by featVeinMain's pass 4 (its threads, into the table): no
// vein pending from here
[noinline]
void veinApply() {
    uint v = sG.base + SCR_MISC + M_LINE;
    if (scratch[v] == VEIN_PENDING) scratch[v] = 0u;
}

// ---- the store (atlas.h atlas_gen_add_store_stage) ------------------------------------------------------------------
// the generator's map (atlas_map.slang, atlas_page.slang): its far section holds Biome's noises
// ([12] TEMPERATURE_NOISE, [13] BIOME_INFO_NOISE, [14] FROZEN_TEMPERATURE_NOISE's layers; [8..11] SimplexNoise F2, G2)
static const uint MH_FAR = 13;
uint mapW(uint i) { return mapData[i]; }
double mapD(uint i) { return asdouble(mapData[i], mapData[i + 1]); }
float mapF(uint i) { return asfloat(mapData[i]); }
uint farW(uint k) { return mapW(mapW(MH_FAR) + k); }
static const int SN_GX[12] = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0};
static const int SN_GY[12] = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1};
int snPerm(uint rec, int x) {
    uint i = uint(x & 255);
    return int((mapW(rec + (i >> 2)) >> ((i & 3u) * 8u)) & 255u);
}
double snCorner(int gi, double x, double y) {
    double t0 = 0.5l - x * x - y * y - 0.0l * 0.0l;
    if (t0 < 0.0l) return 0.0l;
    t0 = t0 * t0;
    return t0 * t0 * (double(SN_GX[gi]) * x + double(SN_GY[gi]) * y + 0.0l * 0.0l);
}
// SimplexNoise.get(x, y): a record of 64 words of permutation, offsetX, offsetY
[noinline]
float simplex2(uint rec, double xi, double yi) {
    double xin = xi + mapD(rec + 64u), yin = yi + mapD(rec + 66u);
    double F2 = mapD(mapW(MH_FAR) + 8u), G2 = mapD(mapW(MH_FAR) + 10u);
    double s = (xin + yin) * F2;
    int i = floorD_X(xin + s), j = floorD_X(yin + s);
    double t = double(i + j) * G2;
    double X0 = double(i) - t, Y0 = double(j) - t;
    double x0 = xin - X0, y0 = yin - Y0;
    int i1 = x0 > y0 ? 1 : 0, j1 = x0 > y0 ? 0 : 1;
    double x1 = x0 - double(i1) + G2, y1 = y0 - double(j1) + G2;
    double x2 = x0 - 1.0l + 2.0l * G2, y2 = y0 - 1.0l + 2.0l * G2;
    int ii = i & 255, jj = j & 255;
    int gi0 = snPerm(rec, ii + snPerm(rec, jj)) % 12;
    int gi1 = snPerm(rec, ii + i1 + snPerm(rec, jj + j1)) % 12;
    int gi2 = snPerm(rec, ii + 1 + snPerm(rec, jj + 1)) % 12;
    double n0 = snCorner(gi0, x0, y0), n1 = snCorner(gi1, x1, y1), n2 = snCorner(gi2, x2, y2);
    return float(70.0l * (n0 + n1 + n2));
}
// NoiseStack.get(x, y): count, then per layer the noise, its frequency (double) and amplitude (float)
[noinline]
float simplexStack2(uint at, double x, double y) {
    uint n = mapW(at);
    float v = 0.0f;
    for (uint k = 0u; k < n; k++) {
        uint L = at + 1u + k * 71u;
        double f = mapD(L + 68u);
        v = v + mapF(L + 70u) * simplex2(L, x * f, y * f);
    }
    return v;
}
// GroundCoverFeature.chooseRegionalFloraId's decision (AtlasFeatureProgram.alchLeaf): the leaf of a region word
// (province code | flags << 8), the map elevation, the y asked for and by water; the program's tables give each
// region index its word ([H_ALCHREG]: count, words) and each leaf its class ([H_ALCHLEAF], 255 unknown)
static const uint H_ALCHREG = 27, H_ALCHLEAF = 28;
static const uint AL_MW = 1, AL_BM = 2, AL_ELSWEYR = 3, AL_HAMMERFELL = 4, AL_VALENWOOD = 5, AL_SUMMERSET = 6, AL_CYRODIIL = 7,
                  AL_HIGH_ROCK = 8, AL_ATMORA = 9;
uint alchLeaf(uint rw, float elevation, int y, bool water) {
    uint prov = rw & 255u, f = rw >> 8;
    if (water) return prov == AL_MW ? 0u : (prov == AL_BM ? 1u : 2u);
    if (prov == AL_MW) return (f & 1u) != 0u ? 3u : ((f & 2u) != 0u ? 4u : 5u);
    if (prov == AL_BM) return 6u;
    if (prov == AL_ELSWEYR || prov == AL_HAMMERFELL) return 7u;
    if (prov == AL_VALENWOOD || prov == AL_SUMMERSET) return 8u;
    if (prov == AL_CYRODIIL || prov == AL_HIGH_ROCK) return 9u;
    if ((f & 4u) != 0u) return (f & 128u) != 0u ? 16u : 10u;
    if ((f & 8u) != 0u) return 11u;
    if ((f & 16u) != 0u) return 12u;
    if ((f & 32u) != 0u) return 13u;
    if (y > 110 || elevation > 140.0f || (f & 64u) != 0u || prov == AL_ATMORA) return 14u;
    return 15u;
}
uint alchClassOf(int region, float elevation, uint variant) {
    uint reg = hdr(H_ALCHREG), leafAt = hdr(H_ALCHLEAF);
    if (reg == 0u || leafAt == 0u) return 255u;
    uint rw = region >= 0 && uint(region) < fprog[reg] ? fprog[reg + 1u + uint(region)] : 0u;  // (generated realms: 0)
    return fprog[leafAt + alchLeaf(rw, elevation, variant == 1u ? 200 : 64, variant == 2u)] & 255u;
}
// ---- PlacementMask.clearance (the store: featMaskMain) -----------------------------------------------------------
// The plans first (PlacementMask.bakedPlans: the settlements' cores and the registered sources, RoadMask and
// StreetMask), then the structure pieces (the overlays' boxes); the minor sites' and landmarks' pads are not here yet.
static const int MASK_CAP = 63;
static const uint H_PLACES = 29;  // the settlements: count, then (x, z, radius) each
int maskLower(int cur, double d) {  // PlacementMask.lower
    int v = d <= 0.0l ? 0 : (d >= double(MASK_CAP) ? MASK_CAP : int(d));
    return v < cur ? v : cur;
}
static const uint MH_ROADS_F = 9, MH_STREETS_F = 10;
uint rdW(uint k) { return mapW(mapW(MH_ROADS_F) + k); }
uint stW(uint k) { return mapW(mapW(MH_STREETS_F) + k); }
float mapFI(uint arr, int i) { return mapF(arr + uint(i)); }
int mapII(uint arr, int i) { return int(mapW(arr + uint(i))); }
// (bx, bz, k) triples sorted by (bx, bz): k of the key, or -1 (atlas_page.slang keyFind)
[noinline]
int mapKeyFind(uint at, uint n, int bx, int bz) {
    int lo = 0, hi = int(n) - 1;
    for (int guard = 0; guard < 64 && lo <= hi; guard++) {
        int mid = int(uint(lo + hi) >> 1);
        uint w = at + uint(mid) * 3u;
        int kx = int(mapW(w)), kz = int(mapW(w + 1u));
        if (kx == bx && kz == bz) return int(mapW(w + 2u));
        if (kx < bx || (kx == bx && kz < bz)) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
// RoadPlan.at (the authored plan: the map's far section [19..24]) as RoadMask asks it: the distance to the nearest
// centre line within REACH (32) less its segment's half width and the verge (2), or 1e9 when none (beyond Tamriel the
// plan asks TilePlanner's tiles: not on the device)
[noinline]
float roadClear(double x, double z) {
    if (farW(24) == 0u) return 1.0e9f;
    int k = mapKeyFind(farW(20), farW(19), floorD_N(x) >> 6, floorD_N(z) >> 6);
    if (k < 0) return 1.0e9f;
    float px = float(x), pz = float(z);
    float best = 32.0f * 32.0f;
    int bestEdge = -1;
    int j1 = int(mapW(farW(21) + uint(k) + 1u));
    for (int j = int(mapW(farW(21) + uint(k))); j < j1; j++) {
        int i = int(mapW(farW(22) + uint(j)));
        float ax = mapFI(rdW(4), i), az = mapFI(rdW(5), i);
        float dx = mapFI(rdW(4), i + 1) - ax, dz = mapFI(rdW(5), i + 1) - az;
        float t = ((px - ax) * dx + (pz - az) * dz) * mapF(farW(23) + uint(i));
        t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
        float qx = ax + dx * t - px, qz = az + dz * t - pz;
        float d2 = qx * qx + qz * qz;
        if (d2 < best) {
            best = d2;
            bestEdge = i;
        }
    }
    if (bestEdge < 0) return 1.0e9f;
    float half = mapFI(rdW(12), mapII(rdW(10), bestEdge));
    return float(sqrtD_N(double(best))) - (half + 2.0f);
}
// PlacementMask.places: the core of every named settlement near the chunk (Places.overlapping, then circle)
[noinline]
int maskPlaces(int cur, int x0, int z0, int x, int z) {
    uint at = hdr(H_PLACES);
    if (at == 0u) return cur;
    uint n = fprog[at];
    int minX = x0 - MASK_CAP, minZ = z0 - MASK_CAP, maxX = x0 + 15 + MASK_CAP, maxZ = z0 + 15 + MASK_CAP;
    for (uint i = 0u; i < n; i++) {
        int px = int(fprog[at + 1u + i * 3u]), pz = int(fprog[at + 2u + i * 3u]), pr = int(fprog[at + 3u + i * 3u]);
        if (!(px + pr >= minX && px - pr <= maxX && pz + pr >= minZ && pz - pr <= maxZ)) continue;
        double radius = double(float(pr) * 0.5f + 4.0f);  // radius * CORE_FRACTION + CORE_PAD (a float)
        if (double(px) + radius + double(MASK_CAP) < double(x0) || double(px) - radius - double(MASK_CAP) > double(x0 + 15) ||
            double(pz) + radius + double(MASK_CAP) < double(z0) || double(pz) - radius - double(MASK_CAP) > double(z0 + 15)) continue;
        double ex = double(x - px), ez = double(z - pz);
        cur = maskLower(cur, sqrtD_N(ex * ex + ez * ez) - radius);
    }
    return cur;
}
// StreetMask: the street plan's plots (their boxes) and walks (their segments) the chunk's bucket lists
[noinline]
int maskStreets(int cur, int cx, int cz, int x, int z) {
    if (stW(0) == 0u) return cur;
    int k = mapKeyFind(stW(1), stW(0), cx, cz);
    if (k < 0) return cur;
    int x0 = cx << 4, z0 = cz << 4;
    int j1 = mapII(stW(2), k + 1);
    for (int j = mapII(stW(2), k); j < j1; j++) {
        int p = mapII(stW(3), j);
        int ax = mapII(stW(7), p), bx = mapII(stW(9), p), az = mapII(stW(8), p), bz = mapII(stW(10), p);
        if (ax - x0 > 15 + MASK_CAP || x0 - bx > MASK_CAP || az - z0 > 15 + MASK_CAP || z0 - bz > MASK_CAP) continue;
        int oz = z < az ? az - z : (z > bz ? z - bz : 0);
        int ox = x < ax ? ax - x : (x > bx ? x - bx : 0);
        cur = maskLower(cur, double(float(ox > oz ? ox : oz) - 1.0f));
    }
    int e1 = mapII(stW(4), k + 1);
    for (int j = mapII(stW(4), k); j < e1; j++) {
        int e = mapII(stW(5), j);
        int ia = mapII(stW(12), e), ib = mapII(stW(13), e);
        float ax = mapFI(stW(14), ia), az = mapFI(stW(15), ia);
        float ddx = mapFI(stW(14), ib) - ax, ddz = mapFI(stW(15), ib) - az;
        float l2 = ddx * ddx + ddz * ddz, half = divF_N(mapFI(stW(17), e), 2.0f);
        float px = float(x) + 0.5f - ax, pz = float(z) + 0.5f - az;
        float t = 0.0f;
        if (!(l2 < 1.0e-6f)) {
            float u = divF_N(px * ddx + pz * ddz, l2);
            t = u < 0.0f ? 0.0f : (u > 1.0f ? 1.0f : u);  // Math.max(0, Math.min(1, u))
        }
        float qx = px - t * ddx, qz = pz - t * ddz;
        cur = maskLower(cur, double(float(sqrtD_N(double(qx * qx + qz * qz))) - half - 1.0f));
    }
    return cur;
}
// the listed store slot of chunk (cx, cz) (featHm's list: slot, cx, cz each), or -1
[noinline]
int listedSlot(int cx, int cz) {
    for (int i = 0; i < params.slots; i++)
        if (targets[uint(i) * 3u + 1u] == cx && targets[uint(i) * 3u + 2u] == cz) return targets[uint(i) * 3u];
    return -1;
}
// The mask of every listed slot, four columns a thread: the plans, then (params.k: the overlays' boxes bound) the pieces
// the overlays list for the chunk and its 8 neighbours (every box within 16 blocks of a column; the decoration asks
// the mask of its 3 x 3 only, whose neighbours the 5 x 5 always lists), pieces buried more than 4 below the chunk's
// own WORLD_SURFACE_WG left out, a box grown by 2 (PlacementMask.structures)
[shader("compute")]
[numthreads(64, 1, 1)]
void featMaskMain(uint3 id : SV_DispatchThreadID) {
    uint i = id.y, w = id.x;
    if (i >= uint(params.slots) || w >= 64u) return;
    int slot = targets[i * 3u], cx = targets[i * 3u + 1u], cz = targets[i * 3u + 2u];
    int x0 = cx << 4, z0 = cz << 4;
    // RoadMask: none within the plan's reach of the centre, none on the chunk
    bool roads = roadClear(double(x0 + 8), double(z0 + 8)) < 1.0e8f;
    int m0 = MASK_CAP, m1 = MASK_CAP, m2 = MASK_CAP, m3 = MASK_CAP;
    for (uint k = 0u; k < 4u; k++) {
        uint c = w * 4u + k;
        int x = x0 + int(c & 15u), z = z0 + int(c >> 4);
        int m = maskPlaces(MASK_CAP, x0, z0, x, z);
        if (roads) {
            float rc = roadClear(double(x) + 0.5l, double(z) + 0.5l);
            if (rc < 1.0e8f) m = maskLower(m, double(rc));
        }
        m = maskStreets(m, cx, cz, x, z);
        if (k == 0u) m0 = m; else if (k == 1u) m1 = m; else if (k == 2u) m2 = m; else m3 = m;
    }
    if (params.k != 0u) {
        uint hm = uint(slot) * AUX_W + AUX_HM + HM_WORLD_SURFACE_WG * 256u + w * 4u;
        int g0 = int(chunkAux[hm]), g1 = int(chunkAux[hm + 1u]), g2 = int(chunkAux[hm + 2u]), g3 = int(chunkAux[hm + 3u]);
        int z = z0 + int((w * 4u) >> 4), xa = x0 + int((w * 4u) & 15u);
        for (int j = 0; j < 9; j++) {
            int sl = listedSlot(cx + j % 3 - 1, cz + j / 3 - 1);
            if (sl < 0) continue;
            int rec = ovlRecords[uint(sl)];
            if (rec < 0) continue;
            uint at = uint(rec) + 513u + ovlTapes[uint(rec) + 256u];
            uint n = ovlTapes[at];
            for (uint b = 0u; b < n; b++) {
                uint bw = at + 1u + b * 5u;
                int minX = int(ovlTapes[bw]), minZ = int(ovlTapes[bw + 1u]), maxX = int(ovlTapes[bw + 2u]), maxZ = int(ovlTapes[bw + 3u]);
                int maxY = int(ovlTapes[bw + 4u]);
                int ez = imax(imax(minZ - z, z - maxZ), 0);
                for (uint k = 0u; k < 4u; k++) {
                    int best = k == 0u ? m0 : (k == 1u ? m1 : (k == 2u ? m2 : m3));
                    int ground = k == 0u ? g0 : (k == 1u ? g1 : (k == 2u ? g2 : g3));
                    if (best == 0 || maxY < ground - 4) continue;  // (buried: dungeon halls, cellars)
                    int x = xa + int(k);
                    int ex = imax(imax(minX - x, x - maxX), 0);
                    if (ex > best + 2 || ez > best + 2) continue;
                    double d = sqrtD_N(double(ex) * double(ex) + double(ez) * double(ez)) - 2.0l;
                    if (d < double(best)) best = d <= 0.0l ? 0 : int(d);
                    if (k == 0u) m0 = best; else if (k == 1u) m1 = best; else if (k == 2u) m2 = best; else m3 = best;
                }
            }
        }
    }
    chunkAux[uint(slot) * AUX_W + AUX_MASK + w] = uint(m0) | uint(m1) << 8 | uint(m2) << 16 | uint(m3) << 24;
}

// Biome.TemperatureModifier.FROZEN turning a column's temperature to 0.2
[noinline]
bool frozenAt(int x, int z) {
    double large = double(simplexStack2(farW(14), double(x) * 0.05l, double(z) * 0.05l) * 7.0f);
    double edge = double(simplex2(farW(13), double(x) * 0.2l, double(z) * 0.2l));
    if (!(large + edge < 0.3l)) return false;
    return double(simplex2(farW(13), double(x) * 0.09l, double(z) * 0.09l)) < 0.8l;
}
// a listed slot's aux (the list: slot, cx, cz): the quart biomes from the store's (generator ids are the feature
// program's biome ids: it is compiled over the generator's biomes); Biome's temperature noise (x / 8, z / 8, times 8)
// and the frozen modifier's bits from the map's noises; the alchemy classes from each column's map region and
// elevation (the store's aux: atlas_store.slang); the placement mask: (to come from its kernel) open
[shader("compute")]
[numthreads(64, 1, 1)]
void featAuxMain(uint3 id : SV_DispatchThreadID) {
    uint item = id.y, i = id.x;
    if (item >= uint(params.slots) || i >= AUX_HM) return;
    uint slot = uint(targets[item * 3u]);
    int cx = targets[item * 3u + 1u], cz = targets[item * 3u + 2u];
    uint v = 0u;
    if (i < AUX_MASK) {
        v = outWide[slot * STORE_AUX_W + i];
    } else if (i < AUX_ALCH) {
        v = 0x3F3F3F3Fu;  // PlacementMask.CAP everywhere
    } else if (i < AUX_TEMP) {
        // four bytes: variant (0 dry and low, 1 dry above y 110, 2 by water) * 256 + column
        for (uint k = 0u; k < 4u; k++) {
            uint idx = (i - AUX_ALCH) * 4u + k, variant = idx >> 8, c = idx & 255u;
            int region = int(outWide[slot * STORE_AUX_W + 1408u + c]);
            float elev = asfloat(outWide[slot * STORE_AUX_W + 1664u + c]);
            v = v | (alchClassOf(region, elev, variant) << (k * 8u));
        }
    } else if (i >= AUX_TEMP && i < AUX_FROZEN) {
        uint c = i - AUX_TEMP;
        int x = cx * 16 + int(c & 15u), z = cz * 16 + int(c >> 4);
        // (x / 8.0F: a power of two, exact as the product)
        v = asuint(simplex2(farW(12), double(float(x) * 0.125f), double(float(z) * 0.125f)) * 8.0f);
    } else if (i >= AUX_FROZEN && i < AUX_HM) {
        uint w0 = (i - AUX_FROZEN) * 32u;
        for (uint k = 0u; k < 32u; k++) {
            uint c = w0 + k;
            if (frozenAt(cx * 16 + int(c & 15u), cz * 16 + int(c >> 4))) v = v | (1u << k);
        }
    }
    chunkAux[slot * AUX_W + i] = v;
}
// an origin's writes into the store's blocks: each block keeps the write of the origin latest in the gather's order
// (atomic max of (k + 1) << 16 | id, k its index z-major among the block's chunk's 3 x 3 origins), whichever origin
// runs first: the same blocks as featApply's order
[shader("compute")]
[numthreads(64, 1, 1)]
void featStoreApplyMain(uint3 id : SV_DispatchThreadID) {
    uint o = id.y, s = id.x;
    if (o >= uint(params.origins) || s >= HT_CAP) return;
    uint base = o * SCR_W;
    uint key = scratch[base + SCR_HT + s * 2u];
    if (key == 0u) return;
    uint idv = scratch[base + SCR_HT + s * 2u + 1u];
    uint li = key - 1u;
    int lx = int(li % 48u), lz = int((li / 48u) % 48u), ly = int(li / 2304u);
    if (uint(ly) >= 640u) return;  // (above the store's rows: nothing there)
    int dx = lx / 16 - 1, dz = lz / 16 - 1;  // the target: the origin's neighbour (dx, dz)
    int slot = origins[o * ORIGIN_W + 2u + uint((dz + 2) * 5 + (dx + 2))];
    if (slot < 0) return;
    uint k = uint((1 - dz) * 3 + (1 - dx));  // the origin seen from the target: offset (-dx, -dz)
    uint t = hdr(H_FEAT2GEN);
    uint gid = t == 0u ? idv : fprog[t + idv];
    uint b = uint(lx & 15) + 16u * uint(lz & 15) + 256u * uint(ly);
    InterlockedMax(outIds[uint(slot) * STORE_BLOCK_W + b], ((k + 1u) << 16) | (gid & 0xFFFFu));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void featCopyMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.y, b = id.x;
    if (t >= uint(params.targets) || b >= WIDE_W) return;
    int slot = targets[t * TARGET_W];
    uint v = 0u;
    if (slot >= 0) {
        uint w = chunkIds[uint(slot) * CHUNK_W + (b >> 1)];
        v = (b & 1u) != 0u ? (w >> 16) : (w & 0xFFFFu);
    }
    outWide[t * WIDE_W + b] = v;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void featApplyMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.y, s = id.x;
    if (t >= uint(params.targets) || s >= HT_CAP) return;
    int o = targets[t * TARGET_W + 1u + params.k];
    if (o < 0) return;
    uint base = uint(o) * SCR_W;
    uint key = scratch[base + SCR_HT + s * 2u];
    if (key == 0u) return;
    uint idv = scratch[base + SCR_HT + s * 2u + 1u];
    uint li = key - 1u;
    int lx = int(li % 48u), lz = int((li / 48u) % 48u), ly = int(li / 2304u);
    // the origin's 3 x 3 starts one chunk west and north of it; the target is its neighbour k
    int ocx = origins[uint(o) * ORIGIN_W], ocz = origins[uint(o) * ORIGIN_W + 1];
    int tcx = targets[t * TARGET_W + 10u], tcz = targets[t * TARGET_W + 11u];
    int x = (ocx - 1) * 16 + lx, z = (ocz - 1) * 16 + lz;
    if ((x >> 4) != tcx || (z >> 4) != tcz) return;
    outWide[t * WIDE_W + uint(x & 15) + 16u * uint(z & 15) + 256u * uint(ly)] = idv;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void featPackMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.y, w = id.x;
    if (t >= uint(params.targets) || w >= CHUNK_W) return;
    outIds[t * CHUNK_W + w] = (outWide[t * WIDE_W + w * 2u] & 0xFFFFu) | (outWide[t * WIDE_W + w * 2u + 1u] << 16);
}

// ---- the far trees (atlas.h atlas_gen_add_far_stage): the decoration's trees over a far tile, as canopy --------------
// A far tile is 256 x 256 columns of 2^lod blocks (planes: 0 the ground's surface, 1 the water's, 2 the top block |
// the one under << 16, 3 its biome | flags << 16; generator ids). Each real chunk of the tile (lod <= 4; coarser: one
// chunk a column, standing for the column's 2^(lod-4) x 2^(lod-4)) runs the tree records' placements as the
// decoration does (its seeds, its draws, the scatter's grid) over the tile's ground instead of the terrain, then the
// species' pick (the decoration's first draw at the tree); the growth's draws are not made, so after a chunk's first
// tree the count / in_square trees are the decoration's in number and in kind, not in place. Each tree adds its crown's
// area to the columns under it (plane 5; bit 31: a trunk stands in it) and its key, (crown top + 64) << 21 | species,
// the highest winning (plane 6); featFarCanopyMain then gives a column its crown when the crowns cover it (dithered,
// so a coarse column shows the forest's density), as AtlasGenFarBatch's planes 5 and 6.
// params: origins tx, targets tz, k lod, slots the tile's index in the planes, rec the places list's count (scratch 0)
static const uint FAR_THR = 16384u, FAR_THR_W = 12u, FAR_PLACES = 16000u;
uint farP(uint plane, uint c) { return uint(params.slots) * TILE_COLS_F * TILE_PLANES_F + plane * TILE_COLS_F + c; }
static const uint TILE_COLS_F = 65536u, TILE_PLANES_F = 7u;
// the tile's column of block (x, z), or -1 outside
int farCol(int x, int z) {
    int lod = int(params.k);
    int i = (x - params.origins * (256 << lod)) >> lod, j = (z - params.targets * (256 << lod)) >> lod;
    return i < 0 || i >= 256 || j < 0 || j >= 256 ? -1 : j * 256 + i;
}
float farSurf(int c) { return asfloat(farPlanes[farP(0u, uint(c))]); }
// Heightmap.Types: WORLD_SURFACE(_WG), MOTION_BLOCKING(_NO_LEAVES) over the water; OCEAN_FLOOR(_WG) the ground
int farHeight(uint type, int c) {
    float g = farSurf(c), w = asfloat(farPlanes[farP(1u, uint(c))]);
    float top = (type == HM_OCEAN_FLOOR_WG || type == HM_OCEAN_FLOOR || (w != w) || w <= g) ? g : w;
    return floorD_N(double(top) + 0.5l);
}
// the block the tree stands on (feature ids): the top block, or under a snow cover the one below it
uint farGround(int c) {
    uint b = farPlanes[farP(2u, uint(c))], flags = farPlanes[farP(3u, uint(c))] >> 16;
    uint g = (flags & 1u) != 0u ? (b >> 16) : (b & 0xFFFFu);  // (COL_SNOW, atlas_page.slang)
    uint t = hdr(H_GEN2FEAT);
    return t == 0u ? g : (g < hdr(H_NGEN) ? fprog[t + g] : AIR);
}
int farBiome(int c) { return int(farPlanes[farP(3u, uint(c))] & 0xFFFFu); }
uint farHash(int x, int z, uint salt) {
    uint h = uint(x) * 0x9E3779B1u ^ uint(z) * 0x85EBCA77u ^ salt * 0xC2B2AE3Du;
    h = (h ^ (h >> 15)) * 0x2C1B3C6Du;
    h = (h ^ (h >> 12)) * 0x297A2D39u;
    return h ^ (h >> 15);
}
// the places near the tile (featFarPlacesMain's list in scratch) as PlacementMask.places
[noinline]
int farMaskPlaces(int cur, int x0, int z0, int x, int z) {
    uint at = hdr(H_PLACES), n = imin(int(scratch[0]), int(FAR_PLACES));
    for (uint q = 0u; q < uint(n); q++) {
        uint i = scratch[1u + q];
        int px = int(fprog[at + 1u + i * 3u]), pz = int(fprog[at + 2u + i * 3u]), pr = int(fprog[at + 3u + i * 3u]);
        int minX = x0 - MASK_CAP, minZ = z0 - MASK_CAP, maxX = x0 + 15 + MASK_CAP, maxZ = z0 + 15 + MASK_CAP;
        if (!(px + pr >= minX && px - pr <= maxX && pz + pr >= minZ && pz - pr <= maxZ)) continue;
        double radius = double(float(pr) * 0.5f + 4.0f);
        double ex = double(x - px), ez = double(z - pz);
        cur = maskLower(cur, sqrtD_N(ex * ex + ez * ez) - radius);
    }
    return cur;
}
// the baked plans' clearance at a column (the far field has no structure pieces)
[noinline]
int farClearance(int x, int z) {
    int cx = x >> 4, cz = z >> 4, x0 = cx << 4, z0 = cz << 4;
    int m = farMaskPlaces(MASK_CAP, x0, z0, x, z);
    if (roadClear(double(x0 + 8), double(z0 + 8)) < 1.0e8f) {
        float rc = roadClear(double(x) + 0.5l, double(z) + 0.5l);
        if (rc < 1.0e8f) m = maskLower(m, double(rc));
    }
    return maskStreets(m, cx, cz, x, z);
}
// the crown's bottom as a share of the tree's height, by growth form (-1: no crown)
float farCrownBase(uint shape) {
    if (shape == SH_CONIFER) return 0.25f;
    if (shape == SH_COLUMNAR) return 0.2f;
    if (shape == SH_WILLOW || shape == SH_BAMBOO || shape == SH_CRYSTAL) return 0.35f;
    if (shape == SH_MANGROVE) return 0.4f;
    if (shape == SH_BROADLEAF) return 0.45f;
    if (shape == SH_ASPEN || shape == SH_TWISTED) return 0.5f;
    if (shape == SH_BALD_CYPRESS || shape == SH_JUNGLE) return 0.55f;
    if (shape == SH_BAOBAB) return 0.6f;
    if (shape == SH_ACACIA) return 0.65f;
    if (shape == SH_PALM) return 0.8f;
    if (shape == SH_SHRUB) return 0.0f;
    return -1.0f;  // dead trees, fallen logs, stumps
}
// a tree at (x, y, z), its species picked (placeTreeAt's checks on the tile's ground): its crown into the columns
[noinline]
void farTree(uint cfg, int x, int y, int z, uint weight) {
    uint sp = treePick(cfg, x, z, rngDouble());
    if (sp == NO_STATE) return;
    int c = farCol(x, z);
    if (c < 0) return;
    float base = farCrownBase(spU(sp, S_SHAPE));
    if (base < 0.0f || spU(sp, S_LEAVES) == NO_STATE) return;
    if (!(x >= int(tconst(TCN_MINX)) && x < int(tconst(TCN_MAXX)) && z >= int(tconst(TCN_MINZ)) && z < int(tconst(TCN_MAXZ)))) return;
    if (!(farClearance(x, z) > spI(sp, S_REACH))) return;
    int water = imax(0, farHeight(HM_WORLD_SURFACE, c) - y);
    if (water > spI(sp, S_MAXWATER)) return;
    // on the ground: a placement that left y off the tile's ground (a height range, an offset, a scan whose block tests
    // the far field cannot make) planted its crown in the air or in the rock: green squares in the sky far off
    int lodG = int(params.k), groundY = farHeight(HM_OCEAN_FLOOR_WG, c);
    int dyG = y - groundY, limG = 2 + (1 << lodG);  // (no abs: spv_exact's contract)
    if (dyG > limG || dyG < -limG) return;
    uint soil = spU(sp, S_SOIL), ground = farGround(c);
    bool fits = ((soil & 1u) != 0u && bis(ground, B_SOIL_DIRT)) || ((soil & 2u) != 0u && bis(ground, B_SOIL_SAND)) ||
                ((soil & 4u) != 0u && bis(ground, B_SOIL_STONE)) || ((soil & 8u) != 0u && bis(ground, B_SOIL_ASH)) ||
                ((soil & 16u) != 0u && bis(ground, B_SOIL_SNOW));
    if (!fits) return;
    int lo = spI(sp, S_MINH), hi = spI(sp, S_MAXH);
    int height = lo + int(farHash(x, z, 7u) % uint(imax(1, hi - lo + 1)));
    if (y > (MAX_Y + 1) - hi - 12) return;
    float r = spF(sp, S_RADIUS);
    int lod = int(params.k), cs = 1 << lod;
    uint key = (uint(y + height + 64) << 21) | (sp & 0x1FFFFFu);
    // the crown's columns: those it reaches into (all at once), or the one under the trunk with its area when
    // a column is wider than the crown
    float area = 3.14159265f * r * r;
    bool cone = spU(sp, S_SHAPE) == SH_CONIFER || spU(sp, S_SHAPE) == SH_COLUMNAR;
    if (2.0f * r < float(cs)) {
        InterlockedAdd(farPlanes[farP(5u, uint(c))], uint(area) * weight);
        InterlockedMax(farPlanes[farP(6u, uint(c))], key);
    } else {
        int ci = c & 255, cj = c >> 8;
        int reach = ceilF_N(r / float(cs)) + 1;
        float px = float(x) + 0.5f, pz = float(z) + 0.5f;
        int X0 = params.origins * (256 << lod), Z0 = params.targets * (256 << lod);
        for (int dj = -reach; dj <= reach; dj++)
            for (int di = -reach; di <= reach; di++) {
                int i = ci + di, j = cj + dj;
                if (i < 0 || i >= 256 || j < 0 || j >= 256) continue;
                // (the column's nearest point to the trunk within the crown: leaves somewhere in it, as the real
                // crowns' columns come down)
                float ax = float(X0 + i * cs), az = float(Z0 + j * cs);
                float qx = px < ax ? ax - px : (px > ax + float(cs) ? px - ax - float(cs) : 0.0f);
                float qz = pz < az ? az - pz : (pz > az + float(cs) ? pz - az - float(cs) : 0.0f);
                if (qx * qx + qz * qz > r * r) continue;
                uint cc = uint(j * 256 + i);
                // (not over a column whose ground lies well under the trunk's: the crown hung in the air over the
                // slope below a ridge, a coarse column's whole height of it)
                if (float(y) - farSurf(int(cc)) > float(cs) + 3.0f) continue;
                // the crown's top over this column: a cone (conifers) or a dome, from the trunk's out to the rim
                float ex = float(X0 + i * cs) + float(cs) * 0.5f - px, ez = float(Z0 + j * cs) + float(cs) * 0.5f - pz;
                float u = (ex * ex + ez * ez) / (r * r);
                u = u > 1.0f ? 1.0f : u;
                float drop = float(height) * (1.0f - base) * (cone ? 0.85f * float(sqrtD_N(double(u))) : 0.6f * u);
                int ctop = y + height - int(drop);
                uint ckey = (uint(ctop + 64) << 21) | (sp & 0x1FFFFFu);
                InterlockedAdd(farPlanes[farP(5u, cc)], uint(cs * cs) * weight);
                InterlockedMax(farPlanes[farP(6u, cc)], ckey);
            }
    }
    if (lod <= 1) InterlockedOr(farPlanes[farP(5u, uint(c))], 0x80000000u);  // (the trunk: seen at 1 and 2 block columns)
}
// the scatter's next live point of chunk (x0, z0) from grid cell `idx` on (ScatterPlacement, as runPlaced): false none
[noinline]
bool farScatterNext(uint m, int x0, int z0, inout int idx, inout int qx, inout int qz) {
    int64_t worldSeed = int64_t(pfU64(H_SEED));
    int s = int(fprog[m + 1]);
    float minDistance = pfF(m + 2);
    uint field = hdr(H_FIELDS) + fprog[m + 6] * FIELD_W;
    int64_t seed = fieldSeed(field, worldSeed) ^ int64_t(uint64_t(0x7F4A7C15u) * uint64_t(int64_t(s)));
    int ci0 = floorDivI(x0, s), ci1 = floorDivI(x0 + 15, s);
    int cj0 = floorDivI(z0, s), cj1 = floorDivI(z0 + 15, s);
    int nj = cj1 - cj0 + 1, total = (ci1 - ci0 + 1) * nj;
    double minD2 = double(float(s) * minDistance * float(s) * minDistance);
    for (; idx < total; idx++) {
        int i = ci0 + idx / nj, j = cj0 + idx % nj;
        int h = floraHash(i, j, seed);
        int px0 = i * s + d2i(unit01(h) * double(s));
        int pz0 = j * s + d2i(unit01(int(uint(h) * 0x2C1B3C6Du + 0x297A2D39u)) * double(s));
        if (px0 < x0 || px0 > x0 + 15 || pz0 < z0 || pz0 > z0 + 15) continue;
        if (!scatterAlive(m, worldSeed, h, px0, pz0)) continue;
        bool keep = true;
        for (int di = -1; di <= 1 && keep; di++)
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int hn = floraHash(i + di, j + dj, seed);
                if ((uint(hn) * 0x165667B1u) <= (uint(h) * 0x165667B1u)) continue;
                int nx = (i + di) * s + d2i(unit01(hn) * double(s));
                int nz = (j + dj) * s + d2i(unit01(int(uint(hn) * 0x2C1B3C6Du + 0x297A2D39u)) * double(s));
                double d2 = double(nx - px0) * double(nx - px0) + double(nz - pz0) * double(nz - pz0);
                if (d2 < minD2 && scatterAlive(m, worldSeed, hn, nx, nz)) { keep = false; break; }
            }
        if (keep) {
            qx = px0;
            qz = pz0;
            idx++;
            return true;
        }
    }
    return false;
}
// one tree record's placements over chunk (cx, cz), depth first in the decoration's order (the expanding modifiers,
// count and the scatter, as frames; the others filter or move the position)
[noinline]
void farRecord(uint rec, int cx, int cz, uint weight) {
    uint nm = fprog[rec + R_NMODS], mods = fprog[rec + R_MODS], cfg = fprog[rec + R_CFG];
    int64_t worldSeed = int64_t(pfU64(H_SEED));
    int fmi[3], fx[3], fy[3], fz[3], fleft[3];
    bool fscat[3];
    int depth = 0;
    int mi = 0, x = cx * 16, y = MIN_Y, z = cz * 16;
    for (int guard = 0; guard < 4096; guard++) {
        bool pop = false;
        if (uint(mi) >= nm) {
            farTree(cfg, x, y, z, weight);
            pop = true;
        } else {
            uint m = mods + uint(mi) * MOD_W, kind = fprog[m];
            int c = farCol(x, z);
            if (kind == M_COUNT) {
                int n = intProvider(m + 1);
                if (n <= 0 || depth >= 3) pop = true;
                else {
                    fmi[depth] = mi + 1; fx[depth] = x; fy[depth] = y; fz[depth] = z; fleft[depth] = n - 1; fscat[depth] = false;
                    depth++;
                    mi++;
                }
            } else if (kind == M_FLORA_SCATTER) {
                int idx = 0, qx = 0, qz = 0;
                if (depth >= 3 || !farScatterNext(m, x & ~15, z & ~15, idx, qx, qz)) pop = true;
                else {
                    fmi[depth] = mi; fx[depth] = x; fy[depth] = y; fz[depth] = z; fleft[depth] = idx; fscat[depth] = true;
                    depth++;
                    x = qx; z = qz;
                    mi++;
                }
            } else if (kind == M_IN_SQUARE) {
                int ax = rngIntB(16) + x;
                int az = rngIntB(16) + z;
                x = ax; z = az;
                mi++;
            } else if (kind == M_HEIGHTMAP) {
                if (c < 0) pop = true;
                else {
                    int h = farHeight(fprog[m + 1], c);
                    if (h > MIN_Y) { y = h; mi++; } else pop = true;
                }
            } else if (kind == M_BIOME) {
                if (c >= 0 && biomeBit(fprog[rec + R_ANY], farBiome(c))) mi++; else pop = true;
            } else if (kind == M_RARITY) {
                if (rngFloat() < pfF(m + 1)) mi++; else pop = true;
            } else if (kind == M_HEIGHT_RANGE) {
                y = heightProvider(m + 1);
                mi++;
            } else if (kind == M_FLORA_DENSITY) {
                float density = pfF(m + 1), floorV = pfF(m + 2);
                uint field = hdr(H_FIELDS) + fprog[m + 3] * FIELD_W;
                double keepP = double(floorV) + double(density - floorV) * fieldSample_N(field, worldSeed, double(x), double(z));
                if (rngDouble() < keepP) mi++; else pop = true;
            } else if (kind == M_MASK) {
                if (farClearance(x, z) > int(fprog[m + 1])) mi++; else pop = true;
            } else if (kind == M_SURF_REL) {
                if (c < 0) pop = true;
                else {
                    int64_t sy = int64_t(farHeight(fprog[m + 1], c));
                    if (sy + int64_t(int(fprog[m + 2])) <= int64_t(y) && int64_t(y) <= sy + int64_t(int(fprog[m + 3]))) mi++;
                    else pop = true;
                }
            } else if (kind == M_OFFSET) {
                int ax = intProvider(m + 1);
                int ay = intProvider(m + 5);
                int az = intProvider(m + 9);
                x += ax; y += ay; z += az;
                mi++;
            } else {
                mi++;  // (block predicates and scans: the far field has no blocks to ask; passed)
            }
        }
        if (!pop) continue;
        // the next position of the innermost frame with one left
        bool next = false;
        while (depth > 0 && !next) {
            int d = depth - 1;
            if (fscat[d]) {
                int idx = fleft[d], qx = 0, qz = 0;
                if (farScatterNext(mods + uint(fmi[d]) * MOD_W, fx[d] & ~15, fz[d] & ~15, idx, qx, qz)) {
                    fleft[d] = idx;
                    x = qx; y = fy[d]; z = qz;
                    mi = fmi[d] + 1;
                    next = true;
                } else depth--;
            } else if (fleft[d] > 0) {
                fleft[d]--;
                x = fx[d]; y = fy[d]; z = fz[d];
                mi = fmi[d];
                next = true;
            } else depth--;
        }
        if (!next) return;
    }
}
// the settlements near the tile (PlacementMask.places' candidates) into scratch: count, then their indices
[shader("compute")]
[numthreads(64, 1, 1)]
void featFarPlacesMain(uint3 id : SV_DispatchThreadID) {
    uint at = hdr(H_PLACES);
    if (at == 0u) return;
    uint i = id.x;
    if (i >= fprog[at]) return;
    int lod = int(params.k), span = 256 << lod;
    int x0 = params.origins * span, z0 = params.targets * span;
    int px = int(fprog[at + 1u + i * 3u]), pz = int(fprog[at + 2u + i * 3u]), pr = int(fprog[at + 3u + i * 3u]);
    int pad = pr + MASK_CAP + 16;
    if (px + pad < x0 || px - pad > x0 + span || pz + pad < z0 || pz - pad > z0 + span) return;
    uint slot;
    InterlockedAdd(scratch[0], 1u, slot);
    if (slot < FAR_PLACES) scratch[1u + slot] = i;
}
// one real chunk a thread: every tree record the chunk's biomes list, as the decoration seeds it
[shader("compute")]
[numthreads(64, 1, 1)]
void featFarTreesMain(uint3 id : SV_DispatchThreadID) {
    int lod = int(params.k);
    int side = lod <= 4 ? (16 << lod) : 256;  // chunks a tile side evaluated
    uint t = id.x + params.first;  // (params.first: the first chunk of this dispatch's slice; a chunk's trees are its own)
    if (t >= uint(side * side)) return;
    int per = lod <= 4 ? 1 : (1 << (lod - 4));  // chunks a column side (lod > 4: one stands for them)
    int cx = params.origins * (16 << lod) + int(t % uint(side)) * per + (per >> 1);
    int cz = params.targets * (16 << lod) + int(t / uint(side)) * per + (per >> 1);
    uint weight = uint(per * per);
    sG.base = FAR_THR + t * FAR_THR_W;  // (flag()'s word and the random: this thread's own)
    sRA = sG.base + SCR_META + 6u;
    // the biomes the chunk holds (its centre and corners: applyBiomeDecoration's listing)
    int b[5];
    int x0 = cx * 16, z0 = cz * 16;
    int cs[5] = {farCol(x0 + 8, z0 + 8), farCol(x0, z0), farCol(x0 + 15, z0), farCol(x0, z0 + 15), farCol(x0 + 15, z0 + 15)};
    for (int q = 0; q < 5; q++) b[q] = cs[q] < 0 ? -1 : farBiome(cs[q]);
    int64_t deco = decorationSeed(int64_t(pfU64(H_SEED)), x0, z0);
    uint dl = hdr(H_DISPATCH), nd = hdr(H_NDISPATCH);
    // params.rec: that tree record only, a dispatch each (the far stage in parts: features_engine.cpp far(); each record
    // is seeded on its own and writes by atomics, so the split is exact); 0: every tree record
    for (uint r = params.rec != 0u ? nd - 1u : 0u; r < nd; r++) {
        uint rec = params.rec != 0u ? params.rec : fprog[dl + r];
        if (fprog[rec + R_TYPE] != FT_TREE) continue;
        bool listed = false;
        for (int q = 0; q < 5 && !listed; q++) listed = b[q] >= 0 && biomeBit(fprog[rec + R_HERE], b[q]);
        if (!listed) continue;
        featureSeed(deco, int(fprog[rec + R_GI]), int(fprog[rec + R_STEP]));
        farRecord(rec, cx, cz, weight);
    }
}
// each column's canopy from what the trees left: crowns covering it (its share against a dither), the tallest's
[shader("compute")]
[numthreads(64, 1, 1)]
void featFarCanopyMain(uint3 id : SV_DispatchThreadID) {
    uint c = id.x;
    if (c >= TILE_COLS_F) return;
    uint cov = farPlanes[farP(5u, c)], key = farPlanes[farP(6u, c)];
    farPlanes[farP(5u, c)] = 0u;
    farPlanes[farP(6u, c)] = 0u;
    if (key == 0u) return;
    int lod = int(params.k), cs = 1 << lod;
    bool trunk = (cov & 0x80000000u) != 0u;
    cov = cov & 0x7FFFFFFFu;
    int i = int(c & 255u), j = int(c >> 8);
    int wx = params.origins * (256 << lod) + i * cs, wz = params.targets * (256 << lod) + j * cs;
    float dither = float(farHash(wx, wz, 3u + uint(lod)) & 0xFFFFu) / 65536.0f;
    bool crown = float(cov) >= dither * float(cs * cs);
    if (!crown && !trunk) return;
    uint sp = key & 0x1FFFFFu;
    int top = int(key >> 21) - 64;
    float ground = farSurf(int(c));
    float base = farCrownBase(spU(sp, S_SHAPE));
    float h = float(top) - ground;
    if (h < 1.0f) return;
    int bottom = floorD_N(double(ground + (h * base > 1.0f ? h * base : 1.0f)) + 0.5l);
    if (!crown) bottom = top;  // (the trunk alone)
    uint ft = hdr(H_FEAT2GEN);
    uint leaf = spU(sp, S_LEAVES), logId = trunk ? spU(sp, S_LOG) : 0u;
    if (ft != 0u) {
        leaf = fprog[ft + leaf];
        logId = trunk ? fprog[ft + logId] : 0u;
    }
    // the crown's leaves: a 2 block column shows its gaps, coarser ones are mostly leaf (as the real crowns come down)
    uint fill = lod <= 1 ? 21u : (lod == 2 ? 26u : 0u);
    farPlanes[farP(5u, c)] = (fill << 27) | (uint(top + 1024) << 16) | uint(bottom + 1024);
    farPlanes[farP(6u, c)] = (leaf & 0xFFFFu) | ((logId & 0xFFFFu) << 16);
}
