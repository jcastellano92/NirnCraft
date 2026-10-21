// Structures from the plans, stamped on the GPU (atlas_ovl_* in atlas.h, overlays_engine.cpp; the Java side is
// com.nirncraft.atlas.AtlasOverlayTapes): after the terrain (and the decoration steps before the structures' step),
// before ingest, in place on chunk columns of block ids (the overlay block table's, whose first ids are the
// generator's), b = x + 16 z + 256 (y + 64): two 16-bit ids a word (atlas_ovl_run) or one a word (the generator's
// blocks buffer, as a stage of its batch: atlas_ovl_create_for_gen).
//
// The gather formulation: one thread per chunk column replays only that column's ops, in the server's order (piece
// order, then op order), reading and writing only its own column; no two threads write one block (two columns share a
// 32-bit word: each thread changes its own half with atomic and / or), and a chunk needs no neighbour.
//
//   piecesMain   the named settlements' pieces (SettlementPiece -> Builders on a Canvas): the tapes' ops, each
//                predicate tested on the blocks there (Canvas.wset / setIfOpen / air / clear / clearPlants /
//                foundation / bed / furniture, ported literally)
//   streetsMain  the street painter (worldplan.street.StreetPaint.Painter): doorstep paths, stone stairs and gate
//                lanes from the street plan's bucket of the chunk, per cell as Java paints it
//
// No [noinline] here: every helper reads or writes the ids (state), and NVIDIA's compiler takes a noinline call as
// pure and merges calls with equal arguments (the decoration's RNG hit it); the kernels are small inlined.
//
// Exactness: integers only in piecesMain; streetsMain mirrors Java's float expressions literally under the exact
// float contract of atlas_gen.slang (-fp-mode precise, spv_exact: RTE, no contraction), the one division correctly
// rounded (divF), Math.round(float) and Math.max / min (signed zeros) as Java defines them, Math.hypot as StrictMath's.

#include "atlas_exact.slang"

struct OvlParams {
    int count;        // chunks in this batch
    int levels;       // y levels per chunk column (from -64)
    uint packed;      // 1: two 16-bit ids a word (atlas_ovl_run); 0: one id a word (the generator's blocks, a stage)
    uint chunkWords;  // words per chunk in ids
};
[[vk::push_constant]] ConstantBuffer<OvlParams> params;
[[vk::binding(0, 0)]] StructuredBuffer<uint> blocks;   // AtlasOverlayTapes.blockWords
[[vk::binding(1, 0)]] StructuredBuffer<uint> tapes;    // AtlasOverlayTapes.tapeWords
[[vk::binding(2, 0)]] StructuredBuffer<uint> streets;  // AtlasOverlayTapes.streetWords
[[vk::binding(3, 0)]] StructuredBuffer<int> chunks;    // per chunk (CHUNK_INTS): cx, cz, tape record (-1), street bucket (-1), N E S W slots (-1)
[[vk::binding(4, 0)]] RWStructuredBuffer<uint> ids;    // per chunk levels * 128 words
[[vk::binding(5, 0)]] StructuredBuffer<uint> snap;     // ids as the pieces left them (the sweeps' neighbour reads)

static const uint CHUNK_INTS = 8u, ID_WORDS = 5u, REC_OPS = 513u;

static const uint HEADER = 16u;
// op kinds (Canvas.Tape) and flags (AtlasOverlayTapes.OF_*)
static const uint OP_W = 1u, OP_OPEN = 2u, OP_NOT_AIR = 3u, OP_PLANT = 4u, OP_FOUNDATION = 5u, OP_BED = 6u, OP_FURNITURE = 7u;
static const uint OF_NOSMOTHER = 1u, OF_VAR = 2u, OF_BOTTOMCLIP = 4u;
// block flags (AtlasOverlayTapes.F_*)
static const uint F_AIR = 1u, F_FLUID = 2u, F_OPEN = 4u, F_SOLIDGROUND = 8u, F_FCR = 16u, F_BED = 32u, F_DOOR = 64u,
                  F_SNOWY = 128u, F_SMOTHERS = 256u, F_PLANT = 512u, F_CARPET = 1024u, F_REPL = 2048u, F_LEAVES = 4096u,
                  F_SPNAT = 8192u, F_FENCE = 16384u, F_GATE = 32768u, F_LADDER = 65536u, F_TALLPOLE = 131072u,
                  F_WBS = 262144u, F_SOLIDRENDER = 524288u, F_BASESTONE = 1048576u, F_NATLOG = 2097152u,
                  F_LANTERN = 4194304u, F_DIRTPATH = 8388608u, F_GATEOPEN = 16777216u;

// ---- this thread's column (thread-private statics: nothing forwarded through calls) -------------------------------

static uint gBase;  // the chunk's first word in ids
static uint gCol;   // x + 16 z
static int gX, gZ;  // the column's world x, z

uint flagsOf(uint id) { return blocks[HEADER + id * ID_WORDS]; }
uint xformOf(uint id) { return blocks[HEADER + id * ID_WORDS + 1u]; }
uint sweepOf(uint id) { return blocks[HEADER + id * ID_WORDS + 2u]; }
uint miscOf(uint id) { return blocks[HEADER + id * ID_WORDS + 3u]; }
uint shapeOf(uint id) { return blocks[HEADER + id * ID_WORDS + 4u]; }
uint idWater() { return blocks[7]; }
uint idAir() { return blocks[2]; }
uint idDirt() { return blocks[3]; }
uint idCoarse() { return blocks[4]; }
uint idPath() { return blocks[5]; }
uint idPlanks() { return blocks[6]; }

uint getB(int y) {
    if (y < -64 || y >= -64 + params.levels) return 0u;
    uint b = gCol + 256u * uint(y + 64);
    if (params.packed == 0u) return ids[gBase + b];
    uint w = ids[gBase + (b >> 1)];
    return (b & 1u) != 0u ? w >> 16 : w & 0xFFFFu;
}

void setB(int y, uint v) {
    if (y < -64 || y >= -64 + params.levels) return;
    uint b = gCol + 256u * uint(y + 64);
    if (params.packed == 0u) {
        ids[gBase + b] = v;  // one id a word: this thread's own
        return;
    }
    uint i = gBase + (b >> 1);
    uint old;
    if ((b & 1u) != 0u) {
        InterlockedAnd(ids[i], 0x0000FFFFu, old);
        InterlockedOr(ids[i], v << 16, old);
    } else {
        InterlockedAnd(ids[i], 0xFFFF0000u, old);
        InterlockedOr(ids[i], v & 0xFFFFu, old);
    }
}

int lo16(uint w) { return int(w << 16) >> 16; }
int hi16(uint w) { return int(w) >> 16; }

// ---- piecesMain: Canvas, op by op --------------------------------------------------------------------------------

// Canvas.wset: nothing over FEATURES_CANNOT_REPLACE, no block over a door or bed (but a door or bed), then the write,
// then grass under a sturdy block (or a full fluid) to dirt (smotherGrassBelow, unless y - 1 is outside the clip)
void wset(int y, uint s, bool nosmother) {
    uint old = getB(y);
    uint fo = flagsOf(old), fs = flagsOf(s);
    if ((fo & F_FCR) != 0u) return;
    if ((fs & F_AIR) == 0u && (fo & (F_BED | F_DOOR)) != 0u && (fs & (F_BED | F_DOOR)) == 0u) return;
    setB(y, s);
    if ((fs & F_AIR) == 0u && !nosmother && (fs & F_SMOTHERS) != 0u && (flagsOf(getB(y - 1)) & F_SNOWY) != 0u) setB(y - 1, idDirt());
}

// one op at word i of the tapes; returns its length in words
uint applyOp(uint i) {
    uint w0 = tapes[i], w1 = tapes[i + 1u];
    uint kind = w0 & 15u, fl = (w0 >> 4) & 0xFFFu, s = w0 >> 16;
    int y0 = lo16(w1), y1 = hi16(w1);
    bool nosm = (fl & OF_NOSMOTHER) != 0u;
    if (kind == OP_W) {
        for (int y = y0; y <= y1; y++) wset(y, s, nosm && y == y0);
        return 2u;
    }
    if (kind == OP_OPEN) {
        if ((flagsOf(getB(y0)) & F_OPEN) != 0u) wset(y0, s, nosm);
        return 2u;
    }
    if (kind == OP_NOT_AIR) {
        for (int y = y0; y <= y1; y++)
            if ((flagsOf(getB(y)) & F_AIR) == 0u) wset(y, s, false);
        return 2u;
    }
    if (kind == OP_PLANT) {
        for (int y = y0; y <= y1; y++) {
            uint f = flagsOf(getB(y));
            if ((f & F_AIR) == 0u && (f & F_PLANT) != 0u) wset(y, s, false);
        }
        return 2u;
    }
    if (kind == OP_FOUNDATION) {
        // y0 = top, y1 = the lowest y, word 2 = the clip's top; Canvas.foundation: down until solid natural ground
        int clipTop = int(tapes[i + 2u]);
        bool var = (fl & OF_VAR) != 0u, bottomClip = (fl & OF_BOTTOMCLIP) != 0u;
        for (int y = y0; y >= y1; y--) {
            if (y < y0 && (flagsOf(getB(y)) & F_SOLIDGROUND) != 0u) break;
            if (y <= clipTop) {
                uint st = s;
                if (var) {
                    uint k = uint(y0 - y);
                    uint w = tapes[i + 3u + (k >> 1)];
                    st = (k & 1u) != 0u ? w >> 16 : w & 0xFFFFu;
                }
                wset(y, st, bottomClip && y == y1);
            }
        }
        return var ? 3u + (uint(y0 - y1) + 2u) / 2u : 3u;
    }
    if (kind == OP_BED) {
        // one half of Canvas.bed: where the half may stand, plank support under it if open, then the half itself
        uint f = flagsOf(getB(y0));
        if ((f & (F_OPEN | F_BED | F_CARPET)) != 0u) {
            uint fb = flagsOf(getB(y0 - 1));
            if ((fb & (F_AIR | F_OPEN)) != 0u) setB(y0 - 1, idPlanks());
            setB(y0, s);
        }
        return 2u;
    }
    if (kind == OP_FURNITURE) {
        if ((flagsOf(getB(y0)) & (F_OPEN | F_CARPET)) != 0u) wset(y0, s, nosm);
        return 2u;
    }
    return 2u;  // unknown: skipped
}

[shader("compute")]
[numthreads(64, 1, 1)]
void piecesMain(uint3 id : SV_DispatchThreadID) {
    if (int(id.y) >= params.count || id.x >= 256u) return;
    int rec = chunks[id.y * CHUNK_INTS + 2u];
    if (rec < 0) return;
    gBase = id.y * params.chunkWords;
    gCol = id.x;
    gX = chunks[id.y * CHUNK_INTS] * 16 + int(id.x & 15u);
    gZ = chunks[id.y * CHUNK_INTS + 1u] * 16 + int(id.x >> 4);
    uint r = uint(rec);
    uint a = tapes[r + id.x], e = tapes[r + id.x + 1u], ops = r + REC_OPS;
    uint i = a;
    while (i < e) i += applyOp(ops + i);
}

// ---- streetsMain: StreetPaint.Painter ---------------------------------------------------------------------------

static const uint N_STREET = 0u, N_GATE = 1u, N_PLAZA = 4u, N_ROAD = 5u;
static const uint C_PATH = 3u, C_STAIRS = 4u;
static const uint S_PLANKS = 2u, S_STONE = 3u;
static const uint PLOT_WORK = 2u;
static const float MAX_LANE = 28.0f;

static bool gSolidPlot;
static int gMinY, gMaxY;

float nodeX(uint n) { return asfloat(streets[streets[5] + n * 4u]); }
float nodeZ(uint n) { return asfloat(streets[streets[5] + n * 4u + 1u]); }
float nodeH(uint n) { return asfloat(streets[streets[5] + n * 4u + 2u]); }
uint nodeClass(uint n) { return streets[streets[5] + n * 4u + 3u]; }
uint edgeW(uint e, uint k) { return streets[streets[6] + e * 5u + k]; }

float absF(float a) { return asfloat(asuint(a) & 0x7FFFFFFFu); }
// Math.max / Math.min (float), as Java defines them (no NaN here)
float jmaxF(float a, float b) {
    if (a == 0.0f && b == 0.0f && asuint(a) == 0x80000000u) return b;
    return a >= b ? a : b;
}
float jminF(float a, float b) {
    if (a == 0.0f && b == 0.0f && asuint(b) == 0x80000000u) return b;
    return a <= b ? a : b;
}

// a / b correctly rounded (atlas_gen.slang's divF)
float divF(float a, float b) {
    float q = float(double(a) / double(b));
    if (b == 0.0f || a == 0.0f) return q;
    double da = double(a), db = double(b);
    float best = q;
    double r = absD2(da - double(q) * db);
    float up = asfloat(asuint(q) + 1u), dn = asfloat(asuint(q) - 1u);
    double ru = absD2(da - double(up) * db), rd = absD2(da - double(dn) * db);
    if (ru < r) { best = up; r = ru; }
    if (rd < r) { best = dn; }
    return best;
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

// (int) Math.floor / Math.ceil of a float (promoted to double), |f| < 2^31: truncation, then a step (no extended
// instructions: spv_exact admits none)
int floorFI(float f) {
    int i = int(f);
    return float(i) > f ? i - 1 : i;
}
int ceilFI(float f) {
    int i = int(f);
    return float(i) < f ? i + 1 : i;
}
int minI(int a, int b) { return a < b ? a : b; }
int maxI(int a, int b) { return a > b ? a : b; }

bool solidTop(uint f) { return (f & (F_AIR | F_FLUID | F_REPL | F_LEAVES)) == 0u; }

void setIfDiffer(int y, uint v) {
    if (getB(y) != v) setB(y, v);
}

// dirt down a column from y while nothing solid or wet (Painter.cell's two fills)
void dirtDown(int h) {
    int y = h - 1;
    for (int k = 0; k < 24 && y >= gMinY; k++) {
        uint f = flagsOf(getB(y));
        if (solidTop(f) || (f & F_FLUID) != 0u) break;
        setIfDiffer(y, idDirt());
        y--;
    }
}

// Painter.cell for this thread's column: a walk cell whose top block is at y = h (along: 0 east, 1 south)
void cell(int h, bool soft, uint along, bool planks) {
    if (h < gMinY || h > gMaxY) return;
    if (soft || gSolidPlot) {
        for (int y = h + 1; y <= h + 2; y++) {
            uint f = flagsOf(getB(y));
            if ((f & F_AIR) == 0u && (f & F_REPL) != 0u && (f & F_FLUID) == 0u) setIfDiffer(y, idAir());
        }
        return;
    }
    uint top = getB(h), ft = flagsOf(top);
    bool water = (ft & F_FLUID) != 0u || ((flagsOf(getB(h - 1)) & F_FLUID) != 0u && (ft & F_AIR) != 0u);
    if (water || (planks && (ft & F_AIR) != 0u)) {
        setIfDiffer(h, idPlanks());
    } else if (!solidTop(ft)) {
        setIfDiffer(h, idPath());
        dirtDown(h);
    } else {
        if ((ft & F_SPNAT) != 0u) setIfDiffer(h, idPath());
        uint fb = flagsOf(getB(h - 1));
        if (h - 1 >= gMinY && !solidTop(fb) && (fb & F_FLUID) == 0u) dirtDown(h);
    }
    for (int y = h + 1; y <= h + 2; y++) {
        uint s = getB(y), f = flagsOf(s);
        if ((f & F_AIR) != 0u) continue;
        if ((f & F_GATE) != 0u) {
            if ((f & F_GATEOPEN) == 0u) setIfDiffer(y, xformOf(s) & 0xFFFFu);
            continue;
        }
        if ((f & (F_DOOR | F_LADDER)) != 0u) continue;
        if ((f & F_FENCE) != 0u && y == h + 1 && (flagsOf(getB(h + 2)) & F_FENCE) == 0u) {
            uint x = xformOf(s);
            setIfDiffer(y, along == 0u ? (x & 0xFFFFu) : (x >> 16));
            continue;
        }
        if ((f & F_WBS) != 0u && h + 3 <= gMaxY && (flagsOf(getB(h + 3)) & F_TALLPOLE) != 0u) continue;
        if ((f & F_SOLIDRENDER) != 0u && (f & F_SPNAT) == 0u && (f & F_BASESTONE) == 0u && (f & F_NATLOG) == 0u && (f & F_LEAVES) == 0u)
            continue;
        bool wasLog = (f & F_NATLOG) != 0u;
        setIfDiffer(y, idAir());
        if (y == h + 2 && h + 3 <= gMaxY && (flagsOf(getB(h + 3)) & F_LANTERN) != 0u) setIfDiffer(h + 3, idAir());
        if (wasLog) {
            int last = minI(gMaxY, h + 16);
            for (int ty = y + 1; ty <= last; ty++) {
                uint tf = flagsOf(getB(ty));
                if ((tf & (F_NATLOG | F_LEAVES)) != 0u) setIfDiffer(ty, idAir());
                else break;
            }
        }
    }
    // a path block needs air above it (or it turns to dirt)
    if ((flagsOf(getB(h)) & F_DIRTPATH) != 0u && (flagsOf(getB(h + 1)) & F_AIR) == 0u) setIfDiffer(h, idCoarse());
}

bool joins(uint n) {
    uint c = nodeClass(n);
    return c == N_STREET || c == N_PLAZA || c == N_GATE || c == N_ROAD;
}

bool gateLane(uint a, uint b) {
    uint ca = nodeClass(a), cb = nodeClass(b);
    return ca == N_GATE || ca == N_ROAD || cb == N_GATE || cb == N_ROAD;
}

// Painter.path: the cells from node a to node b; this column's only
void path(uint e, uint a, uint b, bool soft) {
    float ax = nodeX(a), az = nodeZ(a), bx = nodeX(b), bz = nodeZ(b);
    float ddx = bx - ax, ddz = bz - az;
    int n = ceilFI(jmaxF(absF(ddx), absF(ddz)));
    uint along = absF(ddx) >= absF(ddz) ? 0u : 1u;
    float ha = nodeH(a), hb = nodeH(b);
    bool planks = edgeW(e, 4u) == S_PLANKS;
    for (int i = 0; i <= n; i++) {
        float t = n == 0 ? 0.0f : divF(float(i), float(n));
        int x = floorFI(ax + (bx - ax) * t), z = floorFI(az + (bz - az) * t);
        if (x != gX || z != gZ) continue;
        int h = jroundF(ha + (hb - ha) * t);
        cell(h, soft, along, planks);
    }
}

// Painter.band: a gate lane over its width; this column only
void band(uint e, uint a, uint b) {
    float ax = nodeX(a), az = nodeZ(a), bx = nodeX(b), bz = nodeZ(b);
    float dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
    float half = jmaxF(0.5f, divF(asfloat(edgeW(e, 2u)), 2.0f));
    uint along = absF(dx) >= absF(dz) ? 0u : 1u;
    float ha = nodeH(a), hb = nodeH(b);
    float px = float(gX) + 0.5f - ax, pz = float(gZ) + 0.5f - az;
    float t = l2 < 1.0e-6f ? 0.0f : jmaxF(0.0f, jminF(1.0f, divF(px * dx + pz * dz, l2)));
    float qx = px - t * dx, qz = pz - t * dz;
    if (jmaxF(absF(qx), absF(qz)) > half + 0.01f) return;
    cell(jroundF(ha + (hb - ha) * t), false, along, false);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void streetsMain(uint3 id : SV_DispatchThreadID) {
    if (int(id.y) >= params.count || id.x >= 256u) return;
    int bucket = chunks[id.y * CHUNK_INTS + 3u];
    if (bucket < 0) return;
    gBase = id.y * params.chunkWords;
    gCol = id.x;
    int x0 = chunks[id.y * CHUNK_INTS] * 16, z0 = chunks[id.y * CHUNK_INTS + 1u] * 16;
    gX = x0 + int(id.x & 15u);
    gZ = z0 + int(id.x >> 4);
    gMinY = int(streets[11]);
    gMaxY = int(streets[12]);
    uint bk = streets[8] + uint(bucket) * 4u;
    uint ef = streets[bk], et = streets[bk + 1u], pf = streets[bk + 2u], pt = streets[bk + 3u];
    // the building / wall plots over this column (painted softly)
    gSolidPlot = false;
    for (uint j = pf; j < pt; j++) {
        uint p = streets[streets[10] + j], o = streets[7] + p * 5u;
        if (streets[o + 4u] == PLOT_WORK) continue;
        int ax = maxI(x0, int(streets[o])), bx = minI(x0 + 15, int(streets[o + 2u]));
        int az = maxI(z0, int(streets[o + 1u])), bz = minI(z0 + 15, int(streets[o + 3u]));
        if (gX >= ax && gX <= bx && gZ >= az && gZ <= bz) gSolidPlot = true;
    }
    for (uint j = ef; j < et; j++) {
        uint e = streets[streets[9] + j];
        uint cls = edgeW(e, 3u), surf = edgeW(e, 4u);
        uint a = edgeW(e, 0u), b = edgeW(e, 1u);
        if (cls == C_PATH) {
            if (joins(a) || joins(b)) continue;
            path(e, a, b, surf == S_STONE);
        } else if (cls == C_STAIRS && surf == S_STONE) {
            path(e, a, b, true);
        } else if (gateLane(a, b)) {
            uint bad = 0u;
            float len = float(hypotS(double(nodeX(b) - nodeX(a)), double(nodeZ(b) - nodeZ(a)), bad));
            if (len <= MAX_LANE) band(e, a, b);
        }
    }
}

// ---- sanityMain, connectMain: SettlementPiece.postProcess's Sanity sweep (build/Sanity.java) -----------------------
//
// Over each column's sweep range (the tape record's: the union of the pieces' clips and their wide boxes, as Sanity
// covers them chunk by chunk), two passes (up, then down) remove what the builders left without support or sense:
// unsupported doors, beds, trapdoors, stray stairs, carpets, plates, lanterns, ladders, unreachable chests and barrels,
// fences and walls on nothing, and any block with no supported neighbour (the server's rule includes fluids and
// terrain: open water in a piece's clip goes). Then connectMain gives every fence, wall and pane its sides from its
// neighbours (Sanity.connect: worldgen does no neighbour updates). Differences from the server, by construction:
// one sweep after all of a chunk's pieces instead of one per piece, and the neighbour columns read as the pieces
// left them (snap) instead of as the sweep goes (only a chain of removals across columns differs).

static const uint S_DOOR_LOWER = 1u, S_DOOR_UPPER = 2u, S_BED = 4u, S_BED_HEAD = 8u, S_TRAPDOOR = 16u, S_STAIR = 32u,
                  S_CARPET_PLATE = 64u, S_LANTERN = 128u, S_HANGING = 256u, S_LADDER = 512u, S_CHEST = 1024u,
                  S_BARREL = 2048u, S_FENCE = 4096u, S_WALL = 8192u, S_BARS = 16384u, S_WATERLOGGED = 32768u,
                  S_STURDY_N = 65536u, S_EXC = 1048576u, S_WOODFENCE = 2097152u, S_FENCETAG = 4194304u,
                  S_GATE = 8388608u, S_GATE_AXIS_X = 16777216u, S_WALLS = 33554432u, S_POST_OVERRIDE = 67108864u,
                  S_WALL_UP = 134217728u, S_COVER_POST = 268435456u, S_SUPPORTED = 536870912u;
static const uint UNKNOWN = 0xFFFFFFFFu;

static uint gSlot;     // this chunk's slot in the batch
static int gNb0, gNb1, gNb2, gNb3;  // its N, E, S, W neighbours' slots (-1 none)

int dxOf(uint d) { return d == 1u ? 1 : d == 3u ? -1 : 0; }  // N 0, E 1, S 2, W 3
int dzOf(uint d) { return d == 2u ? 1 : d == 0u ? -1 : 0; }

// the word index of the block at this column + (dx, dz) (|dx| + |dz| <= 1), its half in bit 31; UNKNOWN outside
// the batch, UNKNOWN - 1 outside the world's heights (air)
uint nbWord(int dx, int dz, int y) {
    int lx = int(gCol & 15u) + dx, lz = int(gCol >> 4) + dz;
    int slot = int(gSlot);
    if (lx < 0) { slot = gNb3; lx += 16; }
    else if (lx > 15) { slot = gNb1; lx -= 16; }
    else if (lz < 0) { slot = gNb0; lz += 16; }
    else if (lz > 15) { slot = gNb2; lz -= 16; }
    if (slot < 0) return UNKNOWN;
    if (y < -64 || y >= -64 + params.levels) return UNKNOWN - 1u;
    uint b = uint(lx) + 16u * uint(lz) + 256u * uint(y + 64);
    if (params.packed == 0u) return uint(slot) * params.chunkWords + b;
    return (uint(slot) * params.chunkWords + (b >> 1)) | ((b & 1u) << 31);
}

// the block there as the pieces left it (snap); UNKNOWN outside the batch
uint snapAt(int dx, int dz, int y) {
    uint i = nbWord(dx, dz, y);
    if (i == UNKNOWN) return UNKNOWN;
    if (i == UNKNOWN - 1u) return 0u;
    if (params.packed == 0u) return snap[i];
    uint w = snap[i & 0x7FFFFFFFu];
    return (i >> 31) != 0u ? w >> 16 : w & 0xFFFFu;
}

// the same, live (connectMain: a neighbour changes only its shape there, never its class)
uint liveAt(int dx, int dz, int y) {
    uint i = nbWord(dx, dz, y);
    if (i == UNKNOWN) return UNKNOWN;
    if (i == UNKNOWN - 1u) return 0u;
    if (params.packed == 0u) return ids[i];
    uint w = ids[i & 0x7FFFFFFFu];
    return (i >> 31) != 0u ? w >> 16 : w & 0xFFFFu;
}

// Sanity.supported: a solid-ish block; unknown (outside what the chunk may read) counts as yes
bool supportedId(uint id) { return id == UNKNOWN || (sweepOf(id) & S_SUPPORTED) != 0u; }
bool supportedAt(int dx, int dz, int y) {
    if (dx == 0 && dz == 0) return supportedId(getB(y));
    return supportedId(snapAt(dx, dz, y));
}

bool anyNeighbour(int y) {
    return supportedAt(0, 0, y - 1) || supportedAt(0, 0, y + 1) || supportedAt(0, -1, y) || supportedAt(1, 0, y)
        || supportedAt(0, 1, y) || supportedAt(-1, 0, y);
}

uint blockIndexOf(uint id) { return miscOf(id) & 0xFFFFu; }
uint facingOf(uint id) { return (miscOf(id) >> 16) & 3u; }

// Sanity.doorInWall: wall or frame beside the door at foot or head height, and ground under it
bool doorInWall(int y, uint s) {
    if ((flagsOf(getB(y - 1)) & F_AIR) != 0u) return false;
    uint side = (facingOf(s) + 1u) & 3u;
    for (uint k = 0u; k < 2u; k++) {
        uint d = k == 0u ? side : (side + 2u) & 3u;
        for (int dy = 0; dy <= 1; dy++) {
            uint n = snapAt(dxOf(d), dzOf(d), y + dy);
            if (n == UNKNOWN || (sweepOf(n) & S_SUPPORTED) != 0u) return true;
        }
    }
    return false;
}

// Sanity.strayStair: nothing under it, beside it (a step either way) or over it
bool strayStair(int y) {
    if (supportedAt(0, 0, y - 1)) return false;
    for (uint d = 0u; d < 4u; d++)
        for (int dy = -1; dy <= 1; dy++)
            if (supportedAt(dxOf(d), dzOf(d), y + dy)) return false;
    return !supportedAt(0, 0, y + 1);
}

// Sanity.reachable: opens upwards (a chest: nothing solid or a ladder over it) and has a free side
bool reachable(int y, uint s) {
    if ((sweepOf(s) & S_CHEST) != 0u) {
        uint u = getB(y + 1);
        if ((flagsOf(u) & F_AIR) == 0u && ((flagsOf(u) & (F_SOLIDRENDER | F_LADDER)) != 0u)) return false;
    }
    for (uint d = 0u; d < 4u; d++) {
        uint n = snapAt(dxOf(d), dzOf(d), y);
        if (n == UNKNOWN) return true;
        uint f = flagsOf(n);
        if ((f & (F_AIR | F_REPL)) != 0u || (f & F_SOLIDRENDER) == 0u) return true;
    }
    return false;
}

// one block of a Sanity pass
void sanityBlock(int y) {
    uint s = getB(y);
    uint f = flagsOf(s);
    if ((f & F_AIR) != 0u) return;
    uint sb = sweepOf(s);
    if ((f & F_DOOR) != 0u) {
        if ((sb & S_DOOR_UPPER) != 0u) {
            if (blockIndexOf(getB(y - 1)) != blockIndexOf(s)) setB(y, idAir());
            return;
        }
        if (!doorInWall(y, s)) {
            setB(y, idAir());
            setB(y + 1, idAir());
        }
        return;
    }
    if ((sb & S_BED) != 0u) {
        // the partner half: towards the head from the foot, back from the head (its own check removes it as well)
        uint fc = facingOf(s);
        uint d = (sb & S_BED_HEAD) != 0u ? (fc + 2u) & 3u : fc;
        bool ok = supportedAt(0, 0, y - 1) && supportedAt(dxOf(d), dzOf(d), y - 1);
        uint ps = snapAt(dxOf(d), dzOf(d), y);
        if (ok && ps != UNKNOWN) {
            uint pb = sweepOf(ps);
            bool expectHead = (sb & S_BED_HEAD) == 0u;
            if ((pb & S_BED) == 0u || ((pb & S_BED_HEAD) != 0u) != expectHead || facingOf(ps) != fc) ok = false;
        }
        if (!ok) setB(y, idAir());
        return;
    }
    if ((sb & S_TRAPDOOR) != 0u) {
        if (!anyNeighbour(y)) setB(y, idAir());
        return;
    }
    if ((sb & S_STAIR) != 0u) {
        if (strayStair(y) || !anyNeighbour(y)) setB(y, idAir());
        return;
    }
    if ((sb & S_CARPET_PLATE) != 0u) {
        if (!supportedAt(0, 0, y - 1)) setB(y, idAir());
        return;
    }
    if ((sb & S_LANTERN) != 0u) {
        if (!supportedAt(0, 0, (sb & S_HANGING) != 0u ? y + 1 : y - 1)) setB(y, idAir());
        return;
    }
    if ((sb & S_LADDER) != 0u) {
        uint back = (facingOf(s) + 2u) & 3u;
        uint bi = blockIndexOf(s);
        bool climbs = blockIndexOf(getB(y - 1)) == bi || blockIndexOf(getB(y + 1)) == bi;
        if (!supportedAt(dxOf(back), dzOf(back), y) && !climbs) setB(y, idAir());
        return;
    }
    if ((sb & (S_CHEST | S_BARREL)) != 0u) {
        uint gone = (sb & S_WATERLOGGED) != 0u ? idWater() : idAir();
        if (y - 1 >= -64 + 1) {
            uint uf = flagsOf(getB(y - 1));
            if ((uf & (F_AIR | F_REPL | F_FLUID)) != 0u) {
                setB(y, gone);
                return;
            }
        }
        if (!reachable(y, s)) setB(y, gone);
        return;
    }
    if ((sb & (S_FENCE | S_WALL | S_BARS)) != 0u) {
        if ((sb & (S_FENCE | S_WALL)) != 0u && !supportedAt(0, 0, y - 1) && !supportedAt(0, 0, y + 1)) setB(y, idAir());
        else if (!anyNeighbour(y)) setB(y, idAir());
        return;
    }
    if (!anyNeighbour(y)) setB(y, idAir());
}

// this column's sweep range (the tape record's), or false
bool sweepRange(uint slot, out int y0, out int y1) {
    y0 = 1;
    y1 = 0;
    int rec = chunks[slot * CHUNK_INTS + 2u];
    if (rec < 0) return false;
    uint r = tapes[uint(rec) + 257u + gCol];
    y0 = lo16(r);
    y1 = hi16(r);
    return y0 <= y1;
}

void sweepSetup(uint3 id) {
    gSlot = id.y;
    gBase = id.y * params.chunkWords;
    gCol = id.x;
    gNb0 = chunks[id.y * CHUNK_INTS + 4u];
    gNb1 = chunks[id.y * CHUNK_INTS + 5u];
    gNb2 = chunks[id.y * CHUNK_INTS + 6u];
    gNb3 = chunks[id.y * CHUNK_INTS + 7u];
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sanityMain(uint3 id : SV_DispatchThreadID) {
    if (int(id.y) >= params.count || id.x >= 256u) return;
    sweepSetup(id);
    int y0, y1;
    if (!sweepRange(id.y, y0, y1)) return;
    for (int y = y0; y <= y1; y++) sanityBlock(y);
    for (int y = y1; y >= y0; y--) sanityBlock(y);
}

// FenceBlock / IronBarsBlock / WallBlock.connectsTo for the neighbour n in direction d (UNKNOWN: air there)
bool connects(uint self, uint n, uint d) {
    if (n == UNKNOWN) return false;
    uint nb = sweepOf(n), me = sweepOf(self);
    bool sturdy = (nb & (S_STURDY_N << ((d + 2u) & 3u))) != 0u && (nb & S_EXC) == 0u;
    bool gate = (nb & S_GATE) != 0u && (((nb & S_GATE_AXIS_X) != 0u) != (d == 1u || d == 3u));
    if ((me & S_FENCE) != 0u) {
        bool same = (nb & S_FENCETAG) != 0u && ((nb & S_WOODFENCE) != 0u) == ((me & S_WOODFENCE) != 0u);
        return sturdy || same || gate;
    }
    if ((me & S_WALL) != 0u) return (nb & S_WALLS) != 0u || sturdy || (nb & S_BARS) != 0u || gate;
    return sturdy || (nb & (S_BARS | S_WALLS)) != 0u;
}

void connectBlock(int y) {
    uint s = getB(y);
    uint sb = sweepOf(s);
    if ((sb & (S_FENCE | S_WALL | S_BARS)) == 0u) return;
    uint table = shapeOf(s);
    if (table == 0u) return;
    bool c0 = connects(s, liveAt(0, -1, y), 0u), c1 = connects(s, liveAt(1, 0, y), 1u);
    bool c2 = connects(s, liveAt(0, 1, y), 2u), c3 = connects(s, liveAt(-1, 0, y), 3u);
    uint r;
    if ((sb & S_WALL) == 0u) {
        r = blocks[table + (c0 ? 1u : 0u) + (c1 ? 2u : 0u) + (c2 ? 4u : 0u) + (c3 ? 8u : 0u)];
    } else {
        // WallBlock.updateShape: a connected side is tall under a block whose bottom covers it; the post by
        // shouldRaisePost
        uint above = getB(y + 1);
        uint cov = (miscOf(above) >> 20) & 15u, ab = sweepOf(above);
        uint n = c0 ? ((cov & 1u) != 0u ? 2u : 1u) : 0u, e = c1 ? ((cov & 2u) != 0u ? 2u : 1u) : 0u;
        uint so = c2 ? ((cov & 4u) != 0u ? 2u : 1u) : 0u, w = c3 ? ((cov & 8u) != 0u ? 2u : 1u) : 0u;
        bool up;
        if ((ab & S_WALL_UP) != 0u) up = true;
        else {
            bool nn = n == 0u, sn = so == 0u, en = e == 0u, wn = w == 0u;
            bool corner = (nn && sn && wn && en) || nn != sn || wn != en;
            if (corner) up = true;
            else if ((n == 2u && so == 2u) || (e == 2u && w == 2u)) up = false;
            else up = (ab & (S_POST_OVERRIDE | S_COVER_POST)) != 0u;
        }
        r = blocks[table + n + 3u * e + 9u * so + 27u * w + (up ? 81u : 0u)];
    }
    if (r != s) setB(y, r);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void connectMain(uint3 id : SV_DispatchThreadID) {
    if (int(id.y) >= params.count || id.x >= 256u) return;
    sweepSetup(id);
    int y0, y1;
    if (!sweepRange(id.y, y0, y1)) return;
    for (int y = y0; y <= y1; y++) connectBlock(y);
}
