// The road painter on the GPU (the overlays' road stage, ATLAS_GEN_STAGE_PAINT): com.nirncraft.roads.RoadPaint's
// Painter ported literally, over the generator's blocks before they are packed. One thread a chunk walks its columns
// in Java's order (z, then x): a column's painting reads and writes mostly its own column, but a lamp post's arm, a
// felled tree's crown and a bridge's rails reach the next, so the order is kept rather than a column a thread.
//
// Data: the baked road plan, every culture's materials as block ids and the region cultures (RoadPaintGpu.words,
// binding 6); the generator's map (binding 7: TamrielMap.sample for culverts and a column's culture, atlas_map.slang);
// the overlay block table's road classes (word 6 per id: RoadPaintGpu.R_*); the street plan (StreetPaint.workOnly);
// the settlement pieces' boxes of the chunk from the tapes (PlacementMask.structureGrid: a road only blends inside a
// piece's box); MOTION_BLOCKING_NO_LEAVES as the server primed it before the decoration (mbnlMain, a stage before it).
//
// Exactness: the plan's float geometry term for term under the exact float contract (atlas_gen.slang's: -fp-mode
// precise, spv_exact), square roots and divisions correctly rounded. Not done: a felled tree's crown in a neighbour
// chunk (only inside this one; the server clears into the region's other chunks too).

#include "atlas_exact.slang"

struct OvlParams {
    int count;
    int levels;
    uint packed;
    uint chunkWords;
};
[[vk::push_constant]] ConstantBuffer<OvlParams> params;
[[vk::binding(0, 0)]] StructuredBuffer<uint> blocks;   // AtlasOverlayTapes.blockWords
[[vk::binding(1, 0)]] StructuredBuffer<uint> tapes;    // AtlasOverlayTapes.tapeWords
[[vk::binding(2, 0)]] StructuredBuffer<uint> streets;  // AtlasOverlayTapes.streetWords
[[vk::binding(3, 0)]] StructuredBuffer<int> chunks;    // per chunk (CHUNK_INTS): cx, cz, tape record, street bucket, N E S W
[[vk::binding(4, 0)]] RWStructuredBuffer<uint> ids;    // the chunks' blocks
[[vk::binding(6, 0)]] StructuredBuffer<uint> roads;    // RoadPaintGpu.words
[[vk::binding(7, 0)]] StructuredBuffer<uint> mapData;  // the generator's map (AtlasGenMap)
[[vk::binding(8, 0)]] RWStructuredBuffer<int> mbnl;    // per chunk 256: MOTION_BLOCKING_NO_LEAVES before the decoration

#include "atlas_ovl_javamath.slang"
#include "atlas_map.slang"

static const uint HEADER = 16u, CHUNK_INTS = 8u, ID_WORDS = 7u, REC_OPS = 513u;
static const uint R_GROUND = 1u, R_SUPPORT = 2u, R_CLEARABLE = 4u, R_NATURAL = 8u, R_LOGS = 16u, R_LEAVES = 32u,
                  R_FALLING = 64u, R_VERGE = 128u, R_MOTION_NL = 256u, R_SNOWLAYER = 512u;
static const uint F_AIR = 1u, F_FLUID = 2u;

uint idWord(uint id, uint k) { return blocks[HEADER + (id < blocks[1] ? id : 0u) * ID_WORDS + k]; }
uint flagsOf(uint id) { return idWord(id, 0u); }
uint roadOf(uint id) { return idWord(id, 6u); }
bool isAirId(uint id) { return (flagsOf(id) & F_AIR) != 0u; }
bool fluidId(uint id) { return (flagsOf(id) & F_FLUID) != 0u; }

// ---- this thread's chunk -----------------------------------------------------------------------------------------

static uint gSlot, gBase;
static int gX0, gZ0;
static const int MIN_Y = -64;
int maxYW() { return MIN_Y + params.levels - 1; }

uint getW(int x, int y, int z) {
    int lx = x - gX0, lz = z - gZ0;
    if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y < MIN_Y || y > maxYW()) return 0u;  // (outside: air)
    uint b = uint(lx) + 16u * uint(lz) + 256u * uint(y + 64);
    if (params.packed == 0u) return ids[gBase + b];
    uint w = ids[gBase + (b >> 1)];
    return (b & 1u) != 0u ? w >> 16 : w & 0xFFFFu;
}

// Painter.set: inside the world's heights, only a change (and only in this chunk: the region's others are theirs)
void setW(int x, int y, int z, uint v) {
    int lx = x - gX0, lz = z - gZ0;
    if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y < MIN_Y || y > maxYW()) return;
    if (getW(x, y, z) == v) return;
    uint b = uint(lx) + 16u * uint(lz) + 256u * uint(y + 64);
    if (params.packed == 0u) {
        ids[gBase + b] = v;
        return;
    }
    uint i = gBase + (b >> 1), sh = (b & 1u) * 16u, old;
    uint cur = (ids[i] >> sh) & 0xFFFFu;
    InterlockedXor(ids[i], ((cur ^ v) & 0xFFFFu) << sh, old);
}

// ---- the road plan (RoadPaintGpu.words) --------------------------------------------------------------------------

static const uint RH_VX = 8, RH_VZ = 9, RH_VH = 10, RH_VG = 11, RH_VW = 12, RH_VARC = 13, RH_VSPAN = 14, RH_VSEG = 15,
                  RH_INV = 16, RH_SEGLINK = 17, RH_SEGKIND = 18, RH_SEGHW = 19, RH_SEGSTART = 20, RH_SEGEND = 21,
                  RH_JOINA = 22, RH_JOINB = 23, RH_SPANOFF = 24, RH_SPANFROM = 25, RH_SPANTO = 26, RH_PIEROFF = 27,
                  RH_PIERAT = 28, RH_KEYS = 29, RH_BOFF = 30, RH_BEDGES = 31, RH_CULTURES = 32, RH_CULT = 33, RH_GLOBALS = 34,
                  RH_PLACES = 35;
float rf(uint a, int i) { return asfloat(roads[roads[a] + uint(i)]); }
int ri(uint a, int i) { return int(roads[roads[a] + uint(i)]); }
uint gid(uint k) { return roads[roads[RH_GLOBALS] + k]; }  // air, dirt, stone, coarse dirt, gravel, cobblestone, mossy cobblestone
static const uint G_AIR = 0, G_DIRT = 1, G_STONE = 2, G_COARSE = 3, G_GRAVEL = 4, G_COBBLE = 5, G_MOSSY_COBBLE = 6;

static const int SPAN_NORMAL = 0, SPAN_BRIDGE = 3, SPAN_CULVERT = 4, SPAN_FORD = 5, SPAN_STAIRS = 7, SPAN_TUNNEL = 8;
static const float REACH = 32.0f, VERGE = 1.5f;
static const int HEADROOM = 4, MAX_FILL = 28;

// a culture's materials (RoadPaintGpu C_* / M_*)
static const uint CULT_WORDS = 600u, C_EDGE = 400u, C_MISC = 500u;
static const uint M_DECK = 0, M_DECK_SLAB = 1, M_FENCE = 2, M_PLANKS = 3, M_WOOD_SLAB = 4, M_LOG_X = 5, M_LOG_Z = 6, M_LOG_Y = 7,
                  M_STONE = 8, M_MOSSY = 9, M_STONE_SLAB = 10, M_WALL = 11, M_WALL_UP = 12, M_STONE_STAIRS = 13, M_WOOD_STAIRS = 17,
                  M_LAMP = 21, M_LAMP_HANGING = 22, M_TORCH = 23, M_TORCH_OUT = 24, M_WALL_TORCH = 25, M_WALL_TORCH_OUT = 29,
                  M_FENCE_ARM = 33, M_FENCE_CONNECT = 37, M_WALL_CONNECT = 53;
static const uint CULT_IMPERIAL = 1, CULT_DUNMER = 2, CULT_ARGONIAN = 3, CULT_KHAJIIT = 4, CULT_BOSMER = 5, CULT_ALTMER = 6,
                  CULT_REDGUARD = 7, CULT_BRETON = 8, CULT_ORC = 9, CULT_AKAVIRI = 10;
uint cw(uint c, uint k) { return roads[roads[RH_CULT] + c * CULT_WORDS + k]; }
uint cm(uint c, uint k) { return cw(c, C_MISC + k); }
uint surfaceOf(uint c, int kind, int roll) { return cw(c, uint(kind < 0 ? 0 : (kind > 3 ? 3 : kind)) * 100u + uint(roll)); }
uint edgeOf(uint c, int roll) { return cw(c, C_EDGE + uint(roll)); }

struct Hit {
    bool ok;
    int segment, vertex, kind, span;
    float halfWidth, distance, height, ground, water, t, along, dirX, dirZ;
};
Hit noHit() {
    Hit h;
    h.ok = false;
    h.segment = -1;
    h.vertex = -1;
    h.kind = 0;
    h.span = 0;
    h.halfWidth = 0.0f;
    h.distance = 0.0f;
    h.height = 0.0f;
    h.ground = 0.0f;
    h.water = 0.0f;
    h.t = 0.0f;
    h.along = 0.0f;
    h.dirX = 0.0f;
    h.dirZ = 0.0f;
    return h;
}

// RoadPlan's bucket of (bx, bz) (BUCKET_SHIFT 6), or -1
int bucketOf(int bx, int bz) {
    int lo = 0, hi = int(roads[4]) - 1;
    uint at = roads[RH_KEYS];
    while (lo <= hi) {
        int mid = int(uint(lo + hi) >> 1);
        int kx = int(roads[at + uint(mid) * 3u]), kz = int(roads[at + uint(mid) * 3u + 1u]);
        if (kx == bx && kz == bz) return int(roads[at + uint(mid) * 3u + 2u]);
        if (kx < bx || (kx == bx && kz < bz)) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

// RoadPlan.at (segment < 0) / atSegment (only that segment's edges), at the double point (x, z)
Hit roadAt(double x, double z, int segment) {
    Hit h = noHit();
    int ix = floorD(x), iz = floorD(z);
    int k = bucketOf(ix >> 6, iz >> 6);
    if (k < 0) return h;
    float px = float(x), pz = float(z);
    float best = REACH * REACH;
    int bestEdge = -1;
    float bestT = 0.0f;
    int j0 = ri(RH_BOFF, k), j1 = ri(RH_BOFF, k + 1);
    for (int j = j0; j < j1; j++) {
        int i = ri(RH_BEDGES, j);
        if (segment >= 0 && ri(RH_VSEG, i) != segment) continue;
        float ax = rf(RH_VX, i), az = rf(RH_VZ, i);
        float dx = rf(RH_VX, i + 1) - ax, dz = rf(RH_VZ, i + 1) - az;
        float t = ((px - ax) * dx + (pz - az) * dz) * rf(RH_INV, i);
        if (t < 0.0f) t = 0.0f;
        else if (t > 1.0f) t = 1.0f;
        float qx = ax + dx * t - px, qz = az + dz * t - pz;
        float d2 = qx * qx + qz * qz;
        if (d2 < best) {
            best = d2;
            bestEdge = i;
            bestT = t;
        }
    }
    if (bestEdge < 0) return h;
    int i = bestEdge, s = ri(RH_VSEG, i);
    h.ok = true;
    h.segment = s;
    h.kind = ri(RH_SEGKIND, s);
    h.halfWidth = rf(RH_SEGHW, s);
    h.vertex = i;
    h.t = bestT;
    h.distance = float(sqrtD(double(best)));
    h.height = rf(RH_VH, i) + (rf(RH_VH, i + 1) - rf(RH_VH, i)) * bestT;
    h.ground = rf(RH_VG, i) + (rf(RH_VG, i + 1) - rf(RH_VG, i)) * bestT;
    float w0 = rf(RH_VW, i), w1 = rf(RH_VW, i + 1);
    h.water = isNaNF(w0) ? w1 : isNaNF(w1) ? w0 : w0 + (w1 - w0) * bestT;
    h.span = ri(RH_VSPAN, bestT < 0.5f ? i : i + 1);
    h.along = rf(RH_VARC, i) + (rf(RH_VARC, i + 1) - rf(RH_VARC, i)) * bestT;
    float dx = rf(RH_VX, i + 1) - rf(RH_VX, i), dz = rf(RH_VZ, i + 1) - rf(RH_VZ, i);
    float len = float(sqrtD(double(dx * dx + dz * dz)));
    h.dirX = len < 1.0e-6f ? 1.0f : divF(dx, len);
    h.dirZ = len < 1.0e-6f ? 0.0f : divF(dz, len);
    return h;
}

int spanAt(int s, int v) {
    for (int k = ri(RH_SPANOFF, s); k < ri(RH_SPANOFF, s + 1); k++)
        if (v >= ri(RH_SPANFROM, k) && v <= ri(RH_SPANTO, k)) return k;
    return -1;
}

// Paver.roll
int rollOf(int x, int z) {
    int h = int(uint(x) * 73428767u) ^ int(uint(z) * 912931u);
    h = h ^ int(uint(h) >> 13);
    h = int(uint(h) * 0x5bd1e995u);
    h = h ^ int(uint(h) >> 15);
    return int(uint(h & 0x7fffffff) % 100u);
}

// RoadStyle.cultureAt: the region's culture (TamrielMap.region: the map sample's region)
uint cultureAt(int x, int z) {
    MapS s = tamrielSample(x, z);
    int r = s.region;
    if (r < 0 || r >= int(roads[5])) return 0u;  // (generated lands beyond: NORD, as the endless lands are off)
    return uint(ri(RH_CULTURES, r));
}

// ---- the painter's state (thread-private: nothing forwarded through calls) -----------------------------------------

static Hit gHit;
static float gHeight;
static int gMinY, gMaxY;

bool isGroundId(uint id) { return (roadOf(id) & R_GROUND) != 0u; }
bool isSupportId(uint id) { return (roadOf(id) & R_SUPPORT) != 0u; }
bool clearableId(uint id) { return (roadOf(id) & R_CLEARABLE) != 0u; }
bool logsId(uint id) { return (roadOf(id) & R_LOGS) != 0u; }
bool leavesId(uint id) { return (roadOf(id) & R_LEAVES) != 0u; }

// Painter.solidBelow
bool solidBelow(int x, int z, int y, int depth) {
    for (int k = 1; k <= depth; k++)
        if (!isSupportId(getW(x, y - k, z))) return false;
    return true;
}

// Painter.clearTreeCanopy (this chunk's columns only)
void clearTreeCanopy(int cx, int cy, int cz) {
    for (int dx = -3; dx <= 3; dx++)
        for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dz * dz > 10) continue;
            int x = cx + dx, z = cz + dz;
            int y0 = cy - 2 > gMinY ? cy - 2 : gMinY, y1 = cy + 18 < gMaxY ? cy + 18 : gMaxY;
            for (int y = y0; y <= y1; y++) {
                uint s = getW(x, y, z), r = roadOf(s);
                if ((r & R_LEAVES) != 0u || (dx * dx + dz * dz <= 2 && (r & R_LOGS) != 0u) || (r & R_SNOWLAYER) != 0u)
                    setW(x, y, z, gid(G_AIR));
            }
        }
}

// Painter.fillBelow
void fillBelow(int x, int z, int sy) {
    int n = 0;
    for (int y = sy - 1; y > gMinY && n < MAX_FILL; y--) {
        uint s = getW(x, y, z);
        if (isSupportId(s) && !fluidId(s)) return;
        setW(x, y, z, n < 3 ? gid(G_DIRT) : gid(G_STONE));
        n++;
    }
}

// Painter.clearAbove
void clearAbove(int x, int z, int y, bool keepFluid) {
    for (int yy = y + 1; yy <= y + HEADROOM && yy <= gMaxY; yy++) {
        uint s = getW(x, yy, z);
        if (isAirId(s) || fluidId(s)) continue;
        bool isLog = logsId(s);
        setW(x, yy, z, gid(G_AIR));
        if (isLog) clearTreeCanopy(x, yy, z);
    }
    for (int yy = y + HEADROOM + 1; yy <= y + 28 && yy <= gMaxY; yy++) {
        uint s = getW(x, yy, z);
        if (isAirId(s) || fluidId(s)) continue;
        if (logsId(s)) {
            setW(x, yy, z, gid(G_AIR));
            clearTreeCanopy(x, yy, z);
        } else if (leavesId(s) || clearableId(s)) {
            setW(x, yy, z, gid(G_AIR));
        }
    }
}

// Painter.naturalColumn
bool naturalColumn(int x, int z, int y0, int y1) {
    int a = gMinY > y0 ? gMinY : y0, b = gMaxY < y1 ? gMaxY : y1;
    for (int y = a; y <= b; y++) {
        uint s = getW(x, y, z);
        if (isAirId(s)) continue;
        if (fluidId(s)) return false;
        if ((roadOf(s) & R_NATURAL) != 0u) continue;
        return false;
    }
    return true;
}

// Painter.blend: inside a structure piece, only a plain ground top with free air above becomes road
void blend(int x, int z, int sy, uint c, int kind, int roll, bool onRoad) {
    if (!onRoad) return;
    for (int y = sy + 1; y >= sy - 1; y--) {
        uint s = getW(x, y, z);
        if (!isGroundId(s) || fluidId(s)) continue;
        uint a1 = getW(x, y + 1, z), a2 = getW(x, y + 2, z);
        if (!(isAirId(a1) || clearableId(a1)) || !(isAirId(a2) || clearableId(a2))) return;
        if (!isAirId(a1)) setW(x, y + 1, z, gid(G_AIR));
        setW(x, y, z, surfaceOf(c, kind, roll));
        return;
    }
}

// Painter.groundedPost
void groundedPost(int x, int z, int sy, int topY, uint post, uint cap) {
    int ground = int(0x80000000u);
    for (int y = topY + 1; y >= topY - 4; y--) {
        uint s = getW(x, y, z);
        if (isSupportId(s) && !fluidId(s)) {
            ground = y;
            break;
        }
    }
    if (ground == int(0x80000000u) || ground >= topY || topY - ground > 3 || !solidBelow(x, z, ground, 2)) return;
    for (int y = ground + 1; y <= topY + 1; y++) {
        uint s = getW(x, y, z);
        if (!isAirId(s) && !clearableId(s)) return;
    }
    for (int y = ground + 1; y <= topY; y++) setW(x, y, z, post);
    setW(x, topY + 1, z, cap);
}

// the dir index of Direction.getApproximateNearest(ux, 0, uz) (N 0, E 1, S 2, W 3), Direction.values' order
uint nearestDir(float ux, float uz) {
    // DOWN, UP: their dot products are 0; NORTH (0,-1), SOUTH (0,1), WEST (-1,0), EAST (1,0)
    float best = asfloat(1u);  // Float.MIN_VALUE
    uint d = 0u;
    bool any = false;
    float dn = ux * 0.0f + 0.0f * 0.0f + uz * -1.0f;
    if (dn > best) { best = dn; d = 0u; any = true; }
    float ds = ux * 0.0f + 0.0f * 0.0f + uz * 1.0f;
    if (ds > best) { best = ds; d = 2u; any = true; }
    float dw = ux * -1.0f + 0.0f * 0.0f + uz * 0.0f;
    if (dw > best) { best = dw; d = 3u; any = true; }
    float de = ux * 1.0f + 0.0f * 0.0f + uz * 0.0f;
    if (de > best) { best = de; d = 1u; any = true; }
    return any ? d : 0u;  // (none: NORTH, getApproximateNearest's start)
}
int dxOf(uint d) { return d == 1u ? 1 : d == 3u ? -1 : 0; }
int dzOf(uint d) { return d == 2u ? 1 : d == 0u ? -1 : 0; }

// Painter.heightAtArc
float heightAtArc(float arc) {
    int s = gHit.segment;
    int a = ri(RH_SEGSTART, s), b = ri(RH_SEGEND, s) - 1;
    if (arc <= rf(RH_VARC, a)) return rf(RH_VH, a);
    if (arc >= rf(RH_VARC, b)) return rf(RH_VH, b);
    int i = gHit.vertex;
    if (i > b - 1) i = b - 1;
    if (i < a) i = a;
    while (i > a && rf(RH_VARC, i) > arc) i--;
    while (i < b - 1 && rf(RH_VARC, i + 1) < arc) i++;
    float l = rf(RH_VARC, i + 1) - rf(RH_VARC, i);
    float t = l < 1.0e-4f ? 0.0f : divF(arc - rf(RH_VARC, i), l);
    return rf(RH_VH, i) + (rf(RH_VH, i + 1) - rf(RH_VH, i)) * t;
}

// Painter.stairFloor
uint stairFloor(int sy, uint c, int kind, uint flat) {
    int i = gHit.vertex;
    float dh = rf(RH_VH, i + 1) - rf(RH_VH, i);
    float len = rf(RH_VARC, i + 1) - rf(RH_VARC, i);
    if (len < 1.0e-3f || divF(absF(dh), len) <= 0.25f) return flat;
    float ux = gHit.dirX, uz = gHit.dirZ;
    if (dh < 0.0f) {
        ux = -ux;
        uz = -uz;
    }
    float lower = heightAtArc(gHit.along + (dh >= 0.0f ? -1.0f : 1.0f));
    if (floorF(lower) >= sy) return flat;
    uint d = nearestDir(ux, uz);
    return cm(c, (kind >= 2 ? M_WOOD_STAIRS : M_STONE_STAIRS) + d);
}

// Painter.railAt / connect (a rail's neighbours from the plan: deterministic)
bool railAt(int x, int z) {
    Hit o = roadAt(double(x) + 0.5l, double(z) + 0.5l, -1);
    if (!o.ok) return false;
    return o.span == SPAN_BRIDGE && o.distance > o.halfWidth && o.distance <= o.halfWidth + 1.0f;
}
uint connectRail(uint c, bool stone, int x, int z) {
    uint b = (railAt(x, z - 1) ? 1u : 0u) | (railAt(x + 1, z) ? 2u : 0u) | (railAt(x, z + 1) ? 4u : 0u) | (railAt(x - 1, z) ? 8u : 0u);
    return cm(c, (stone ? M_WALL_CONNECT : M_FENCE_CONNECT) + b);
}

// Painter.pierDown
void pierDown(int x, int z, int fromY, uint material) {
    int n = 0;
    for (int y = fromY; y > gMinY && n < 80; y--) {
        uint s = getW(x, y, z);
        if (isSupportId(s) && !fluidId(s)) return;
        setW(x, y, z, material);
        n++;
    }
}

// Painter.stoneSupports
void stoneSupports(int x, int z, int sy, float d, float hw, int span, float at, float len, int rise, uint c) {
    uint stb = cm(c, M_STONE);
    if (span < 0) {
        setW(x, sy - 1, z, stb);
        return;
    }
    int k0 = ri(RH_PIEROFF, span), k1 = ri(RH_PIEROFF, span + 1);
    float lo = 0.0f, hi = len;
    bool pier = false;
    for (int k = k0; k < k1; k++) {
        float p = rf(RH_PIERAT, k);
        if (absF(at - p) <= 1.0f) pier = true;
        if (p <= at && p > lo) lo = p;
        if (p > at && p < hi) hi = p;
    }
    if (pier && d <= hw + 1.0f) {
        pierDown(x, z, sy - 1, stb);
        return;
    }
    float bay = hi - lo;
    if (rise <= 0 || bay < 4.0f) {
        setW(x, sy - 1, z, stb);
        if (rise <= 0) return;
    }
    float u = bay <= 0.0f ? 0.5f : divF(at - lo, bay);
    float t = 2.0f * u - 1.0f;
    // (int) Math.round(rise * Math.sqrt(Math.max(0.0, 1.0 - t * t)))
    double q = 1.0l - double(t * t);
    double m = q > 0.0l ? q : 0.0l;  // (Math.max(0.0, v): no NaN, no signed zero matters here)
    double v = double(rise) * sqrtD(m);
    int open = floorD(v + 0.5l);  // (int) Math.round
    int spring = sy - 2 - rise;
    int top = spring + open;
    for (int y = sy - 1; y > top; y--) setW(x, y, z, stb);
}

// Painter.woodSupports
void woodSupports(int x, int z, int sy, float d, float hw, int span, float at, uint c) {
    bool edge = d > hw - 1.0f;
    if (edge) setW(x, sy - 1, z, cm(c, absF(gHit.dirX) >= absF(gHit.dirZ) ? M_LOG_X : M_LOG_Z));
    if (span < 0 || !edge) return;
    for (int k = ri(RH_PIEROFF, span); k < ri(RH_PIEROFF, span + 1); k++) {
        if (absF(at - rf(RH_PIERAT, k)) <= 1.0f) {
            pierDown(x, z, sy - 2, cm(c, M_LOG_Y));
            return;
        }
    }
}

// Painter.bridgeColumn
void bridgeColumn(int x, int z, float d, float hw, int sy, bool halfStep, uint c, int kind, int roll) {
    bool stone = kind <= 1;
    bool rail = d > hw;
    uint deck = stone ? (roll % 7 == 0 ? cm(c, M_MOSSY) : cm(c, M_STONE)) : cm(c, M_PLANKS);
    int span = spanAt(gHit.segment, gHit.t < 0.5f ? gHit.vertex : gHit.vertex + 1);
    float from = 0.0f, len = 0.0f;
    if (span >= 0) {
        from = rf(RH_VARC, ri(RH_SPANFROM, span));
        len = rf(RH_VARC, ri(RH_SPANTO, span)) - from;
    }
    float at = gHit.along - from;
    bool abutment = span < 0 || at < 3.5f || len - at < 3.5f;
    if (abutment) {
        fillBelow(x, z, sy);
    } else {
        float base = isNaNF(gHit.water) ? gHit.ground : gHit.water;
        int r = sy - 3 - floorF(base);
        int rise = r < 0 ? 0 : (r > 6 ? 6 : r);
        if (stone) stoneSupports(x, z, sy, d, hw, span, at, len, rise, c);
        else woodSupports(x, z, sy, d, hw, span, at, c);
    }
    clearAbove(x, z, sy, false);
    setW(x, sy, z, deck);
    if (!rail) {
        if (halfStep) setW(x, sy + 1, z, stone ? cm(c, M_STONE_SLAB) : cm(c, M_WOOD_SLAB));
        return;
    }
    setW(x, sy + 1, z, connectRail(c, stone, x, z));
    int lampEvery = kind <= 1 ? 8 : kind == 2 ? 16 : 0;
    if (lampEvery > 0 && jrem(floorF(gHit.along), lampEvery) == 0) {
        setW(x, sy + 2, z, stone ? cm(c, M_WALL) : cm(c, M_FENCE));
        setW(x, sy + 3, z, cm(c, M_LAMP));
    }
}

// Painter.channel: TamrielMap.sample(x, z).inWater()
bool channel(int x, int z) {
    MapS s = tamrielSample(x, z);
    if (isNaNF(s.waterLevel)) return false;
    return s.lakeDistance > 0.0f || s.riverDistance < s.riverHalfWidth;
}

// Painter.culvertColumn
bool culvertColumn(int x, int z, float d, float hw, int sy, uint c, int kind, int roll) {
    bool ch = channel(x, z);
    bool lining = false;
    if (!ch) lining = channel(x + 1, z) || channel(x - 1, z) || channel(x, z + 1) || channel(x, z - 1);
    if (!ch && !lining) return false;
    float w = isNaNF(gHit.water) ? float(sy - 2) : gHit.water;
    int water = floorF(w);
    uint surf = surfaceOf(c, kind, roll);
    if ((roadOf(surf) & R_FALLING) != 0u) surf = gid(G_COBBLE);
    if (ch) {
        for (int y = water; y < sy - 1; y++) {
            uint s = getW(x, y, z);
            if (!isAirId(s) && !fluidId(s)) setW(x, y, z, gid(G_AIR));
        }
        setW(x, sy - 1, z, cm(c, M_STONE));
        clearAbove(x, z, sy, false);
        setW(x, sy, z, surf);
    } else {
        for (int y = water; y <= sy - 1; y++) {
            uint s = getW(x, y, z);
            if (y == water && fluidId(s)) continue;
            setW(x, y, z, cm(c, M_STONE));
        }
        fillBelow(x, z, water);
        clearAbove(x, z, sy, false);
        setW(x, sy, z, surf);
    }
    return true;
}

// Painter.wallTorch
void wallTorch(int x, int z, int sy, int kind, int roll) {
    int every = kind == 2 ? 10 : 14;
    int cell = floorF(gHit.along);
    if (jrem(cell, every) != 0) return;
    int out = -1;
    float far = gHit.halfWidth;
    // Direction.Plane.HORIZONTAL: NORTH, EAST, SOUTH, WEST
    for (uint d = 0u; d < 4u; d++) {
        int nx = x + dxOf(d), nz = z + dzOf(d);
        Hit o = roadAt(double(nx) + 0.5l, double(nz) + 0.5l, -1);
        float dn = o.ok ? o.distance : asfloat(0x7F7FFFFFu);
        if (dn > far) {
            far = dn;
            out = int(d);
        }
    }
    if (out < 0) return;
    float side = gHit.dirX * float(dzOf(uint(out))) - gHit.dirZ * float(dxOf(uint(out)));
    bool left = side > 0.0f;
    if (left != (jrem(jdivI(cell, every), 2) == 0)) return;
    bool lit = kind == 2 ? jrem(roll, 10) != 0 : jrem(roll, 2) == 0;
    uint facing = (uint(out) + 2u) & 3u;  // out.getOpposite()
    setW(x, sy + 2, z, cm(cultureAt(x, z), (lit ? M_WALL_TORCH : M_WALL_TORCH_OUT) + facing));
}
int jdivI(int a, int b) { return a >= 0 ? int(uint(a) / uint(b)) : -int(uint(-a) / uint(b)); }  // Java's int /

// Painter.tunnelColumn
bool tunnelColumn(int x, int z, float d, float hw, int sy, uint c, int kind, int roll) {
    int span = spanAt(gHit.segment, gHit.t < 0.5f ? gHit.vertex : gHit.vertex + 1);
    if (span < 0) return false;
    float from = rf(RH_VARC, ri(RH_SPANFROM, span)), len = rf(RH_VARC, ri(RH_SPANTO, span)) - from;
    float at = gHit.along - from;
    bool portal = at < 2.5f || len - at < 2.5f;
    int height = kind <= 1 ? 5 : 4;
    uint wall = jrem(roll, 5) == 0 ? cm(c, M_MOSSY) : cm(c, M_STONE);
    float reach = hw + (portal ? 2.0f : 1.0f);
    if (d > reach) return false;
    fillBelow(x, z, sy);
    setW(x, sy, z, stairFloor(sy, c, kind, surfaceOf(c, kind, roll)));
    int top = height + (portal ? 2 : 1);
    bool inside = d <= hw;
    bool lamp = inside && jrem(floorF(gHit.along), 8) == 0 && d < 0.8f + (portal ? hw : 0.0f);
    for (int y = sy + 1; y <= sy + top && y <= gMaxY; y++) {
        uint s;
        if (inside) {
            s = y <= sy + height ? gid(G_AIR) : wall;
        } else if ((portal && d <= hw + 2.0f) || (!portal && d <= hw + 1.0f)) {
            s = wall;
        } else {
            continue;
        }
        setW(x, y, z, s);
    }
    if (portal && inside) setW(x, sy + height + 2, z, wall);
    if (lamp && kind <= 1 && sy + height + 2 <= gMaxY) {
        setW(x, sy + height + 2, z, wall);
        setW(x, sy + height + 1, z, cm(c, M_LAMP_HANGING));
    }
    if (kind >= 2 && inside && d > hw - 1.0f) wallTorch(x, z, sy, kind, roll);
    return true;
}

// Painter.lampPeriod
int lampPeriod(int kind, uint c) {
    if (kind == 0) {
        if (c == CULT_IMPERIAL || c == CULT_ALTMER || c == CULT_BRETON) return 32;
        if (c == 0u || c == CULT_DUNMER || c == CULT_ORC || c == CULT_REDGUARD || c == CULT_AKAVIRI) return 40;
        return 48;
    }
    return kind == 1 ? 48 : 56;
}

// Painter.lampPost
void lampPost(int x, int z, int groundY, uint c, int kind) {
    for (int y = groundY + 1; y <= groundY + 5; y++) {
        uint s = getW(x, y, z);
        if (!isAirId(s) && !clearableId(s)) return;
    }
    uint fence = cm(c, M_FENCE);
    if (kind <= 1) {
        setW(x, groundY, z, cm(c, M_STONE));
        setW(x, groundY + 1, z, cm(c, M_WALL_UP));
    } else {
        setW(x, groundY + 1, z, fence);
    }
    setW(x, groundY + 2, z, fence);
    setW(x, groundY + 3, z, fence);
    int arm = -1;
    float best = gHit.distance;
    for (uint d = 0u; d < 4u; d++) {
        int nx = x + dxOf(d), nz = z + dzOf(d);
        Hit o = roadAt(double(nx) + 0.5l, double(nz) + 0.5l, -1);
        if (!o.ok || o.distance >= best) continue;
        best = o.distance;
        arm = int(d);
    }
    int ax = arm < 0 ? x : x + dxOf(uint(arm)), az = arm < 0 ? z : z + dzOf(uint(arm));
    bool armOk = arm >= 0 && (ax >> 4) == (x >> 4) && (az >> 4) == (z >> 4) && isAirId(getW(ax, groundY + 3, az))
                 && isAirId(getW(ax, groundY + 4, az));
    if (armOk) {
        setW(x, groundY + 4, z, cm(c, M_FENCE_ARM + uint(arm)));
        setW(ax, groundY + 4, az, cm(c, M_FENCE_ARM + ((uint(arm) + 2u) & 3u)));
        setW(ax, groundY + 3, az, cm(c, M_LAMP_HANGING));
        setW(x, groundY + 5, z, kind <= 1 ? cm(c, M_STONE_SLAB) : cm(c, M_WOOD_SLAB));
    } else {
        setW(x, groundY + 4, z, cm(c, M_LAMP));
    }
}

// StreetPaint.workOnly for a column of this chunk (the chunk's street bucket)
bool workOnly(int x, int z) {
    int bucket = chunks[gSlot * CHUNK_INTS + 3u];
    if (bucket < 0 || streets[0] == 0u) return false;
    uint bk = streets[8] + uint(bucket) * 4u;
    bool work = false;
    for (uint j = streets[bk + 2u]; j < streets[bk + 3u]; j++) {
        uint p = streets[streets[10] + j], o = streets[7] + p * 5u;
        if (x < int(streets[o]) || x > int(streets[o + 2u]) || z < int(streets[o + 1u]) || z > int(streets[o + 3u])) continue;
        if (streets[o + 4u] != 2u) return false;  // PLOT_WORK
        work = true;
    }
    return work;
}

// PlacementMask.structureGrid's 0: inside a settlement piece's box (the tapes' boxes of this chunk) that is not
// buried (its top within 4 of the column's WORLD_SURFACE_WG)
bool reservedAt(int x, int z) {
    int rec = chunks[gSlot * CHUNK_INTS + 2u];
    if (rec < 0) return false;
    uint r = uint(rec);
    uint at = r + REC_OPS + tapes[r + 256u];
    uint n = tapes[at];
    if (n == 0u) return false;
    int ground = MIN_Y;
    for (int y = maxYW(); y >= MIN_Y; y--)
        if (!isAirId(getW(x, y, z))) {
            ground = y + 1;
            break;
        }
    for (uint i = 0u; i < n; i++) {
        uint b = at + 1u + i * 5u;
        if (int(tapes[b + 4u]) < ground - 4) continue;
        if (x >= int(tapes[b]) && x <= int(tapes[b + 2u]) && z >= int(tapes[b + 1u]) && z <= int(tapes[b + 3u])) return true;
    }
    return false;
}


// Painter.verge
void verge(int x, int z, float d, float hw, int sy, int span, uint c, int kind, int roll) {
    int top = mbnl[gSlot * 256u + uint((z - gZ0) * 16 + (x - gX0))];
    int last = gMaxY < top + 28 ? gMaxY : top + 28;
    for (int y = top; y <= last; y++) {
        uint s = getW(x, y, z);
        if (isAirId(s)) continue;
        if (logsId(s)) {
            setW(x, y, z, gid(G_AIR));
            clearTreeCanopy(x, y, z);
        } else if (leavesId(s) || clearableId(s)) {
            setW(x, y, z, gid(G_AIR));
        }
    }
    int groundY = top - 1;
    if (kind <= 1) {
        uint g = getW(x, groundY, z);
        if ((roadOf(g) & R_VERGE) != 0u) {
            if (d <= hw + 0.65f && jrem(roll, 100) < 55) setW(x, groundY, z, edgeOf(c, roll));
            else if (jrem(roll, 100) < 42) setW(x, groundY, z, gid(G_COARSE));
        }
        if (d > hw + 0.35f && d <= hw + 1.25f && groundY - sy >= 2 && groundY - sy <= 5) {
            for (int y = sy + 1; y <= groundY; y++) setW(x, y, z, jrem(y + roll, 4) == 0 ? cm(c, M_MOSSY) : cm(c, M_STONE));
            return;
        }
    }
    uint gs = getW(x, groundY, z);
    if (kind <= 2 && span == SPAN_NORMAL && d > hw + 0.45f && d <= hw + 1.15f && (groundY - sy <= 1 && sy - groundY <= 1) && !fluidId(gs) && isSupportId(gs)
        && solidBelow(x, z, groundY, 3)) {
        int arcCell = floorF(gHit.along);
        float arcFrac = gHit.along - float(arcCell);
        if (arcFrac < 0.32f && ((x * 31 + z * 17) & 1) == 0) {
            int lanternPeriod = lampPeriod(kind, c);
            bool lit = kind == 0 || (kind == 1 && curbAt(x, z)) || (kind == 2 && keptUpAt(x, z));
            if (arcCell > 8 && jrem(arcCell, lanternPeriod) == 0 && lit) {
                lampPost(x, z, groundY, c, kind);
            } else if (kind <= 1 && arcCell > 12 && jrem(arcCell, 72) == 24) {
                setW(x, groundY, z, cm(c, M_MOSSY));
                setW(x, groundY + 1, z, cm(c, M_STONE));
                setW(x, groundY + 2, z, cm(c, M_WALL));
                setW(x, groundY + 3, z, cm(c, M_STONE_SLAB));
            }
        }
    }
}

// ---- PlanGround.pad (the generator's map: atlas_page.slang's planPad, copied: keep in step) ----------------------
static const uint MH_ROADS = 9, MH_CONSTS = 12, KC_PADBLEND = 8;
uint rdw(uint k) { return mw(mh(MH_ROADS) + k); }
float rdF(uint arr, int i) { return mf(rdw(arr) + uint(i)); }
int rdI(uint arr, int i) { return mi(rdw(arr) + uint(i)); }
float mconstF(uint k) { return asfloat(mw(mh(MH_CONSTS) + k)); }
int keyFind(uint at, uint n, int bx, int bz) {
    int lo = 0, hi = int(n) - 1;
    while (lo <= hi) {
        int mid = int(uint(lo + hi) >> 1);
        uint w = at + uint(mid) * 3u;
        int kx = mi(w), kz = mi(w + 1u);
        if (kx == bx && kz == bz) return mi(w + 2u);
        if (kx < bx || (kx == bx && kz < bz)) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
// PlanGround.pad's weight (0: no pad)
float padWeight(int x, int z) {
    if (rdw(17) == 0u) return 0.0f;
    int k = keyFind(rdw(18), rdw(17), x >> 8, z >> 8);
    if (k < 0) return 0.0f;
    float BLEND = mconstF(KC_PADBLEND);
    float bestW = 0.0f;
    int j1 = rdI(19, k + 1);
    for (int j = rdI(19, k); j < j1; j++) {
        int i = rdI(20, j);
        float dx = float(x - rdI(21, i)), dz = float(z - rdI(22, i));
        float d = float(sqrtD(double(dx * dx + dz * dz))), r = float(rdI(23, i));
        if (d >= r + BLEND) continue;
        float w = d <= r ? 1.0f : 1.0f - divF(d - r, BLEND);
        w = w * w * (3.0f - 2.0f * w);
        if (w > bestW) bestW = w;
    }
    return bestW;
}

// Painter.curb: town approaches (a site's pad over 0.35)
bool curbAt(int x, int z) { return padWeight(x, z) > 0.35f; }

// Painter.keptUp: a settlement within 60 blocks of its edge, or a fort within 90 (Places.overlapping +-120, then
// sqrt(distSqr) - radius)
bool keptUpAt(int x, int z) {
    uint at = roads[RH_PLACES];
    uint n = roads[at];
    for (uint i = 0u; i < n; i++) {
        uint w = at + 1u + i * 4u;
        int px = int(roads[w]), pz = int(roads[w + 1u]), r = int(roads[w + 2u]);
        if (!(px + r >= x - 120 && px - r <= x + 120 && pz + r >= z - 120 && pz - r <= z + 120)) continue;
        int64_t ddx = int64_t(x - px), ddz = int64_t(z - pz);
        double edge = sqrtD(double(ddx * ddx + ddz * ddz)) - double(r);
        if (edge <= (roads[w + 3u] != 0u ? 90.0l : 60.0l)) return true;
    }
    return false;
}

// Painter.paint: one column
void paintColumn(int x, int z, float d, float hw, bool reserved) {
    int kind = gHit.kind, span = gHit.span;
    uint c = cultureAt(x, z);
    int roll = rollOf(x, z);
    float h = gHeight;
    int sy = floorF(h);
    bool halfStep = kind <= 1 && h - float(sy) >= 0.5f && span != SPAN_FORD && span != SPAN_STAIRS;
    bool onRoad = d <= hw;
    bool bridge = span == SPAN_BRIDGE, tunnel = span == SPAN_TUNNEL;
    if (reserved && !naturalColumn(x, z, sy - 1, sy + (tunnel ? 7 : HEADROOM + 1))) {
        blend(x, z, sy, c, kind, roll, onRoad);
        return;
    }
    if (bridge && d <= hw + 1.0f) {
        bridgeColumn(x, z, d, hw, sy, halfStep, c, kind, roll);
        return;
    }
    if (span == SPAN_CULVERT && d <= hw + 1.0f && culvertColumn(x, z, d, hw, sy, c, kind, roll)) return;
    if (tunnel && tunnelColumn(x, z, d, hw, sy, c, kind, roll)) return;
    if (!onRoad) {
        verge(x, z, d, hw, sy, span, c, kind, roll);
        int row = floorF(gHit.along);
        if (span == SPAN_STAIRS && d <= hw + 1.0f && kind <= 2 && jrem(row, kind <= 1 ? 8 : 16) == 0) {
            uint post = kind >= 2 ? cm(c, M_FENCE) : cm(c, M_WALL);
            uint cap = kind <= 1 ? cm(c, M_LAMP) : cm(c, jrem(roll, 10) != 0 ? M_TORCH : M_TORCH_OUT);
            groundedPost(x, z, sy, sy + 1, post, cap);
        }
        return;
    }
    uint surf;
    if (span == SPAN_FORD) {
        surf = roll < 55 ? gid(G_GRAVEL) : roll < 85 ? gid(G_COBBLE) : gid(G_MOSSY_COBBLE);
    } else if (kind <= 1 && d > hw - 0.85f) {
        surf = edgeOf(c, roll);
    } else if (kind == 0 && d < 0.7f && jrem(roll, 3) != 0) {
        surf = cm(c, M_DECK);
    } else if (kind <= 1 && absF(d - hw * 0.52f) < 0.42f && jrem(roll, 4) == 0) {
        surf = surfaceOf(c, kind + 1 < 3 ? kind + 1 : 3, roll);
    } else {
        surf = surfaceOf(c, kind, roll);
    }
    if (span == SPAN_STAIRS) {
        uint flat = kind >= 2 ? cm(c, M_PLANKS) : cm(c, M_STONE);
        fillBelow(x, z, sy);
        clearAbove(x, z, sy, false);
        setW(x, sy, z, stairFloor(sy, c, kind, flat));
        return;
    }
    if ((roadOf(surf) & R_FALLING) != 0u) {
        if (span != SPAN_FORD && jrem(roll, 3) == 0) surf = gid(G_COARSE);
    }
    fillBelow(x, z, sy);
    clearAbove(x, z, sy + (halfStep ? 1 : 0), span == SPAN_FORD);
    setW(x, sy, z, surf);
    if (halfStep) setW(x, sy + 1, z, cm(c, M_DECK_SLAB));
    else if (kind <= 1 && onRoad && d > hw - 1.0f && curbAt(x, z)) setW(x, sy + 1, z, cm(c, M_DECK_SLAB));
}

// Painter.junctionOf
int junctionOf(Hit h) {
    int s = h.segment;
    if (s < 0) return -1;
    float len = rf(RH_VARC, ri(RH_SEGEND, s) - 1);
    if (h.along <= 40.0f && ri(RH_JOINA, s) >= 0) return ri(RH_JOINA, s);
    if (len - h.along <= 40.0f && ri(RH_JOINB, s) >= 0) return ri(RH_JOINB, s);
    return -1;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void roadsMain(uint3 id : SV_DispatchThreadID) {
    if (int(id.x) >= params.count) return;
    gSlot = id.x;
    gBase = id.x * params.chunkWords;
    gX0 = chunks[id.x * CHUNK_INTS] * 16;
    gZ0 = chunks[id.x * CHUNK_INTS + 1u] * 16;
    gMinY = MIN_Y;
    gMaxY = maxYW();
    // the plan answers within 32 blocks: nothing near the chunk's centre, nothing in it
    if (!roadAt(double(gX0 + 8), double(gZ0 + 8), -1).ok) return;
    for (int dz = 0; dz < 16; dz++)
        for (int dx = 0; dx < 16; dx++) {
            int x = gX0 + dx, z = gZ0 + dz;
            Hit hit = roadAt(double(x) + 0.5l, double(z) + 0.5l, -1);
            if (!hit.ok) continue;
            int join = junctionOf(hit);
            if (join >= 0) {
                Hit j = roadAt(double(x) + 0.5l, double(z) + 0.5l, join);
                if (j.ok && j.distance <= j.halfWidth + 0.5f) hit = j;
            }
            float d = hit.distance, hw = hit.halfWidth;
            float reach = hw + (hit.span == SPAN_TUNNEL ? 2.0f : hit.span == SPAN_BRIDGE ? 1.0f : VERGE);
            if (d > reach) continue;
            gHit = hit;
            Hit corner = roadAt(double(x), double(z), hit.segment);
            gHeight = corner.ok ? corner.height : hit.height;
            int ev = hit.vertex;
            float el = rf(RH_VARC, ev + 1) - rf(RH_VARC, ev);
            if (el > 1.0e-3f && divF(absF(rf(RH_VH, ev + 1) - rf(RH_VH, ev)), el) > 0.2f) gHeight = hit.height;
            gHeight = gHeight + 0.002f;
            bool reserved = reservedAt(x, z);
            if (reserved && workOnly(x, z)) reserved = false;
            paintColumn(x, z, d, hw, reserved);
        }
}

// MOTION_BLOCKING_NO_LEAVES as the server primes it before a chunk's decoration (a stage before the decoration):
// per column the first free y above its top motion-blocking, non-leaf block (or the world's bottom)
[shader("compute")]
[numthreads(64, 1, 1)]
void mbnlMain(uint3 id : SV_DispatchThreadID) {
    if (int(id.y) >= params.count || id.x >= 256u) return;
    gSlot = id.y;
    gBase = id.y * params.chunkWords;
    gX0 = chunks[id.y * CHUNK_INTS] * 16;
    gZ0 = chunks[id.y * CHUNK_INTS + 1u] * 16;
    int x = gX0 + int(id.x & 15u), z = gZ0 + int(id.x >> 4);
    int top = MIN_Y;
    for (int y = maxYW(); y >= MIN_Y; y--)
        if ((roadOf(getW(x, y, z)) & R_MOTION_NL) != 0u) {
            top = y + 1;
            break;
        }
    mbnl[id.y * 256u + id.x] = top;
}
