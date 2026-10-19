// atlas_lod_build: atlas_lab's content-shaped groups (docs/ATLAS-LOD.md, "GPU group build") built on the GPU, the
// same tree atlas_lab's CPU builder makes (atlas_lab_scene.hpp buildGroups), written straight into the 16-byte layout
// atlas_lod.slang traces (atlas_lod_build.hpp lodFlattenGroups). Compiled like atlas_lod: Slang -> C++ for the CPU
// backend (no atomics, no group memory: every kernel there runs as plain loops) and Slang -> SPIR-V for Vulkan (the
// same kernels, plus a work-group scan). Host driver: tools/atlas_lod_gpubuild.hpp.
//
// The build is top-down and level-synchronous: every node of a level is split at once.
//  * err     per node the flattening error (how deep the first content lies under its box's faces): one task per
//            face row (a face of the box, a line of columns), each column walked from the face through the brick pool;
//            empty blocks are skipped by their empty runs (runs: per block and direction how many empty blocks
//            follow). A node whose error is 0 (or a single micro-voxel) is a leaf
//  * slices  per node, axis and micro-voxel slice: the content's count and its bounds on the other two axes, from the
//            bricks' 4x4x4 masks (one task per block layer and block row, then a reduction per slice)
//  * sweep   per node, the SAH over every slice plane of the three axes (prefix and suffix boxes, each side's surface
//            times its count), in atlas_lab's float arithmetic and order, so the plane is the one atlas_lab picks;
//            the two children are the tight boxes of each side
// Work counts per level come from exclusive scans; every dispatch size is written by the GPU (indirect dispatch), so
// a whole build is one submission. At the end the breadth-first work nodes are laid out depth first exactly as
// lodFlattenGroup lays out atlas_lab's groups (children side by side, allocated depth first): subtree sizes up the
// levels, positions down them, then one write a node.
//
// Initial nodes are regions (micro-voxel boxes): the full volume for a build, or the sub-volumes an edit invalidated
// for an update (tools/atlas_lod_gpubuild.hpp); each region's subtree is appended after the array's current end and
// its root written into the slot the region names.

struct BuildParams {
    int4 dims;   // the volume in blocks; w: the number of regions
    uint4 caps;  // capacities: x work nodes, y slices a level, z partial records a level, w output nodes
    uint4 out;   // x: the output array's end before this build (subtrees are appended from there), y: the regions (the
                 // refit list follows them); Vulkan: z the err tasks, w the slices from which a work group takes a node
};

struct Step {
    int4 k;  // x: the level (relayout passes), the axis (runs); refit: x the first entry of the depth, y their count
    int4 r;  // runs: the lines (block coordinates on the next axis from x, y of them; on the one after from z, w of them)
};

[[vk::binding(0, 0)]] ConstantBuffer<BuildParams> P;
[[vk::push_constant]] ConstantBuffer<Step> step;
[[vk::binding(1, 0)]] StructuredBuffer<uint> grid;      // per block (x fastest, then z, then y): its brick, 0 none
[[vk::binding(2, 0)]] StructuredBuffer<uint4> bricks;   // per brick: occupancy lo, hi, state, 0 (atlas_lod.slang)
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> runs;    // per block: 6 x 5 bits, empty blocks from here (dir 2a + positive)
[[vk::binding(4, 0)]] RWStructuredBuffer<uint> state;   // counters, level ranges, dispatch arguments (S_*)
[[vk::binding(5, 0)]] RWStructuredBuffer<uint4> nbox;   // per work node: lo | hi << 16 per axis; w: err | flags << 16
[[vk::binding(6, 0)]] RWStructuredBuffer<uint4> naux;   // per work node: first child, subtree size below, slot, first slot
[[vk::binding(7, 0)]] RWStructuredBuffer<uint4> scanIn;   // per node of the level: counts (slices, tasks, children, err tasks)
[[vk::binding(8, 0)]] RWStructuredBuffer<uint4> scanOut;  // their exclusive prefix sums
[[vk::binding(9, 0)]] RWStructuredBuffer<uint4> scanSum;  // per scan group (Vulkan)
[[vk::binding(10, 0)]] RWStructuredBuffer<uint4> part;    // partial records: 4 per slice task, 1 per err task
[[vk::binding(11, 0)]] RWStructuredBuffer<uint4> slices;  // per slice: count, bounds on the next axis, on the one after
[[vk::binding(12, 0)]] RWStructuredBuffer<uint4> suffix;  // per slice: the box of the content from it on
[[vk::binding(13, 0)]] RWStructuredBuffer<uint4> outNodes;  // atlas_lod.slang's node layout
[[vk::binding(14, 0)]] StructuredBuffer<uint4> regions;    // per region: lo | hi << 16 per axis; w: its slot

static const uint F_EMPTY = 1, F_LEAF = 2, F_REGION = 4, F_INTERNAL = 8;
static const uint MAXL = 100;  // levels a build may have (atlas_lod.slang walks 64)

static const uint S_LEVEL = 0, S_NLEVELS = 1, S_DONE = 2, S_OVERFLOW = 3, S_PHASE = 4, S_NEXT = 5, S_TOTAL = 6,
                  S_SLICES = 7, S_TASKS = 8, S_CHILDREN = 9, S_ERRT = 10, S_END = 11, S_LS = 12, S_LE = 13;
static const uint ARG_NODES = 16, ARG_SCAN = 19, ARG_TASKS = 22, ARG_SLICES = 25, ARG_ERRT = 28, ARG_ONE = 31,
                  ARG_PACK = 34, ARG_ERRWIDE = 37, ARG_SWEEPWIDE = 40;
static const uint S_LSTART = 64;   // MAXL + 1 level starts
static const uint S_LARG = 192;    // per level: the dispatch of its nodes (3 words)
// S_OVERFLOW bits
static const uint O_NODES = 1, O_SLICES = 2, O_PARTS = 4, O_LEVELS = 8, O_OUT = 16;

static const uint EMPTY_RANGE = 0xFFFFu;  // lo 0xFFFF, hi 0: merges as nothing

// ---- helpers -------------------------------------------------------------------------------------------------------

uint lin(uint3 id) { return id.x + id.y * 65536u; }  // 64-thread groups, at most 1024 a row

void setArgs(uint at, uint threads, uint perGroup) {
    uint g = (threads + perGroup - 1u) / perGroup;
    state[at] = min(g, 1024u);
    state[at + 1] = (g + 1023u) / 1024u;
    state[at + 2] = 1;
}

int3 boxLo(uint4 b) { return int3(int(b.x & 0xFFFFu), int(b.y & 0xFFFFu), int(b.z & 0xFFFFu)); }
int3 boxHi(uint4 b) { return int3(int(b.x >> 16), int(b.y >> 16), int(b.z >> 16)); }
uint range(int lo, int hi) { return uint(lo) | uint(hi) << 16; }
uint merge(uint a, uint b) { return min(a & 0xFFFFu, b & 0xFFFFu) | max(a >> 16, b >> 16) << 16; }
bool emptyRange(uint r) { return (r >> 16) == 0; }

uint blockIndex(int3 b) { return uint(b.x + P.dims.x * (b.z + P.dims.z * b.y)); }

// 64-bit counts as uint2 (lo, hi)
uint2 add64(uint2 a, uint2 b) {
    uint lo = a.x + b.x;
    return uint2(lo, a.y + b.y + (lo < a.x ? 1u : 0u));
}
uint2 sub64(uint2 a, uint2 b) { return uint2(a.x - b.x, a.y - b.y - (a.x < b.x ? 1u : 0u)); }

// a 64-bit count to float, rounded to nearest even as the C++ conversion of atlas_lab's long long is
float u64ToFloat(uint2 v) {
    if (v.y == 0) return float(v.x);
    int sh = int(firstbithigh(v.y)) + 32 - 23;  // the bits below the 24 the float keeps: 9..40
    uint m;
    uint2 rest, half;
    if (sh < 32) {
        m = (v.x >> uint(sh)) | (v.y << uint(32 - sh));
        rest = uint2(v.x & ((1u << uint(sh)) - 1u), 0u);
        half = uint2(1u << uint(sh - 1), 0u);
    } else {
        m = v.y >> uint(sh - 32);
        rest = uint2(v.x, v.y & ((1u << uint(sh - 32)) - 1u));
        half = sh == 32 ? uint2(0x80000000u, 0u) : uint2(0u, 1u << uint(sh - 33));
    }
    bool up = rest.y > half.y || (rest.y == half.y && (rest.x > half.x || (rest.x == half.x && (m & 1u) != 0)));
    if (up) m++;
    return float(m) * asfloat(uint(127 + sh) << 23);  // m <= 2^24 and a power of two: both exact
}

// atlas_lab's boxArea, operation for operation
float boxArea(uint4 b) {
    int3 lo = boxLo(b), hi = boxHi(b);
    float dx = float(hi.x - lo.x), dy = float(hi.y - lo.y), dz = float(hi.z - lo.z);
    return dx * dy + dy * dz + dz * dx;
}

// the bits of block b's 4x4x4 occupancy inside micro-voxel box [lo, hi) (bit x + 4 * (z + 4 * y))
uint2 boxMask(int3 b, int3 lo, int3 hi, out bool whole) {
    int3 o = b * 4;
    int3 l = max(lo - o, int3(0, 0, 0)), h = min(hi - o, int3(4, 4, 4));
    whole = all(l == int3(0, 0, 0)) && all(h == int3(4, 4, 4));
    if (whole) return uint2(0xFFFFFFFFu, 0xFFFFFFFFu);
    uint row = ((1u << uint(h.x)) - 1u) & ~((1u << uint(l.x)) - 1u);
    uint2 m = uint2(0, 0);
    for (int y = l.y; y < h.y; y++)
        for (int z = l.z; z < h.z; z++) {
            int at = 4 * z + 16 * y;
            if (at < 32) m.x |= row << uint(at);
            else m.y |= row << uint(at - 32);
        }
    return m;
}

// the axes a node's slices cover: all three, or (a region finding its tight box) y alone
uint axisMask(uint flags) { return (flags & F_REGION) != 0 ? 2u : 7u; }

// per axis: block layers along it, block rows along the axis after the next (a2), blocks along the next (a1)
int blocksOn(int lo, int hi) { return ((hi - 1) >> 2) - (lo >> 2) + 1; }

// the largest item i < n whose exclusive offset (component c) is at most t: the item task t belongs to
uint findItem(uint t, uint n, int c) {
    uint lo = 0, hi = n;
    while (hi - lo > 1u) {
        uint mid = (lo + hi) / 2u;
        if (scanOut[mid][c] <= t) lo = mid;
        else hi = mid;
    }
    return lo;
}

// ---- empty runs ----------------------------------------------------------------------------------------------------

// one thread a line of blocks along axis step.k.x (the lines step.r names): per block how many empty blocks start
// there in each direction (saturating at 31). Each axis writes its own bits.
[numthreads(64, 1, 1)]
void lbRuns(uint3 id: SV_DispatchThreadID) {
    int a = step.k.x, a1 = (a + 1) % 3, a2 = (a + 2) % 3;
    int3 d = P.dims.xyz;
    uint line = lin(id);
    if (line >= uint(step.r.y * step.r.w)) return;
    int3 b = int3(0, 0, 0);
    b[a1] = step.r.x + int(line % uint(step.r.y));
    b[a2] = step.r.z + int(line / uint(step.r.y));
    uint shNeg = uint(5 * (2 * a)), shPos = uint(5 * (2 * a + 1));
    uint run = 0;
    for (int k = 0; k < d[a]; k++) {  // runs towards -a
        b[a] = k;
        uint idx = blockIndex(b);
        run = grid[idx] == 0 ? min(run + 1u, 31u) : 0u;
        runs[idx] = (runs[idx] & ~(31u << shNeg)) | run << shNeg;
    }
    run = 0;
    for (int k = d[a] - 1; k >= 0; k--) {  // towards +a
        b[a] = k;
        uint idx = blockIndex(b);
        run = grid[idx] == 0 ? min(run + 1u, 31u) : 0u;
        runs[idx] = (runs[idx] & ~(31u << shPos)) | run << shPos;
    }
}

// ---- the flattening error ------------------------------------------------------------------------------------------

// the first content of the micro-voxel columns uLo..uHi-1 (on a1, inside one block) at v (on a2) of box [loA, hiA)
// along axis a, seen from its low face (side 0) or its high face (atlas_lab's firstHit, the four walked together through
// the brick pool, empty blocks skipped by their runs): the deepest of their depths, -1 when none has content
int firstHits4(int a, int side, int uLo, int uHi, int v, int loA, int hiA) {
    int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
    int3 m = int3(0, 0, 0);
    m[a1] = uLo;
    m[a2] = v;
    int dir = side == 0 ? 1 : -1, start = side == 0 ? loA : hiA - 1, q = start;
    uint sh = uint(5 * (2 * a + (side == 0 ? 1 : 0)));
    int strideA = a == 0 ? 1 : a == 1 ? 16 : 4, stride1 = a1 == 0 ? 1 : a1 == 1 ? 16 : 4;
    int bit0 = (a2 == 0 ? 1 : a2 == 1 ? 16 : 4) * (v & 3) + stride1 * (uLo & 3);  // sub-column uLo at a = 0
    uint pending = (1u << uint(uHi - uLo)) - 1u;
    int deepest = -1;
    while (pending != 0 && (side == 0 ? q < hiA : q >= loA)) {
        m[a] = q;
        int3 b = m >> 2;
        uint idx = blockIndex(b);
        uint br = grid[idx];
        if (br == 0) {  // an empty block: skip its run
            int nb = b[a] + dir * int((runs[idx] >> sh) & 31u);
            q = side == 0 ? nb * 4 : nb * 4 + 3;
            continue;
        }
        uint4 k = bricks[br];
        int end = side == 0 ? min(hiA, b[a] * 4 + 4) : max(loA, b[a] * 4) - 1;
        for (; q != end && pending != 0; q += dir) {
            int at = bit0 + strideA * (q & 3);
            for (uint c = 0; c < 4u; c++) {
                if (((pending >> c) & 1u) == 0) continue;
                uint bit = uint(at + stride1 * int(c));
                if ((((bit < 32u ? k.x >> bit : k.y >> (bit - 32u))) & 1u) != 0) {
                    pending &= ~(1u << c);
                    deepest = max(deepest, abs(q - start));
                }
            }
        }
    }
    return deepest;
}

// a node's err tasks: per axis a, per side, per row v (on a2), per chunk of ERR_CHUNK columns (on a1)
static const int ERR_CHUNK = 32;
int errChunks(int3 e, int a) { return (e[(a + 1) % 3] + ERR_CHUNK - 1) / ERR_CHUNK; }
uint errTasks(int3 e) {
    uint n = 0;
    for (int a = 0; a < 3; a++) n += uint(2 * e[(a + 2) % 3] * errChunks(e, a));
    return n;
}
// a node whose partial errors a work group reduces (Vulkan: P.out.z, its least err tasks; 0 never)
bool wideErr(uint tasks) { return P.out.z != 0 && tasks >= P.out.z; }

// per node: its err tasks; x: 1 when a work group reduces them
[numthreads(64, 1, 1)]
void lbErrCount(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id), ls = state[S_LS], n = state[S_LE] - ls;
    if (i == 0) state[S_PHASE] = 0;
    if (i >= n) return;
    uint4 b = nbox[ls + i];
    uint tasks = 0;
    if (((b.w >> 16) & F_EMPTY) == 0) tasks = errTasks(boxHi(b) - boxLo(b));
    scanIn[i] = uint4(wideErr(tasks) ? 1u : 0u, 0, 0, tasks);
}

[numthreads(64, 1, 1)]
void lbErrTask(uint3 id: SV_DispatchThreadID) {
    uint t = lin(id);
    if (t >= state[S_ERRT]) return;
    uint ls = state[S_LS], n = state[S_LE] - ls;
    uint i = findItem(t, n, 3);
    uint4 b = nbox[ls + i];
    int3 lo = boxLo(b), hi = boxHi(b), e = hi - lo;
    int local = int(t - scanOut[i].w);
    int a = 0;
    for (; a < 2; a++) {
        int per = 2 * e[(a + 2) % 3] * errChunks(e, a);
        if (local < per) break;
        local -= per;
    }
    int a1 = (a + 1) % 3, a2 = (a + 2) % 3, ch = errChunks(e, a);
    int side = local / (e[a2] * ch), rest = local % (e[a2] * ch);
    int v = lo[a2] + rest / ch, u0 = lo[a1] + (rest % ch) * ERR_CHUNK, u1 = min(hi[a1], u0 + ERR_CHUNK);
    int err = 0;
    for (int u = u0; u < u1; u = (u & ~3) + 4)  // the chunk's columns, a block's four at a time
        err = max(err, firstHits4(a, side, u, min(u1, (u & ~3) + 4), v, lo[a], hi[a]));
    part[t] = uint4(uint(err), 0, 0, 0);
}

// Vulkan: one work group per node with many err tasks, their largest into the node's first partial
groupshared uint shMax[256];
[numthreads(256, 1, 1)]
void lbErrMax(uint3 gid: SV_GroupID, uint3 gt: SV_GroupThreadID) {
    uint g = gid.x + gid.y * 1024u, t = gt.x;
    uint n = state[S_LE] - state[S_LS];
    uint i = findItem(g, n, 0);
    uint at = scanOut[i].w, k = scanIn[i].w, m = 0;
    for (uint j = t; j < k; j += 256u) m = max(m, part[at + j].x);
    shMax[t] = m;
    GroupMemoryBarrierWithGroupSync();
    for (uint s = 128; s > 0; s /= 2u) {
        if (t < s) shMax[t] = max(shMax[t], shMax[t + s]);
        GroupMemoryBarrierWithGroupSync();
    }
    if (t == 0) part[at] = uint4(shMax[0], 0, 0, 0);
}

// a node's error: the largest of its partials (one, when lbErrMax reduced them)
uint nodeErr(uint i) {
    uint at = scanOut[i].w, k = scanIn[i].w, err = 0;
    if (wideErr(k)) k = 1;
    for (uint j = 0; j < k; j++) err = max(err, part[at + j].x);
    return err;
}

// a node whose SAH sweep a work group runs (Vulkan: P.out.w, its least slices; 0 never)
bool wideSweep(int3 e) { return P.out.w != 0 && uint(e.x + e.y + e.z) >= P.out.w; }

// per node: its error, leaf or not; an inner node's split work counted for the next scan (w: 1 when a work group sweeps it)
[numthreads(64, 1, 1)]
void lbErrReduce(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id), ls = state[S_LS], n = state[S_LE] - ls;
    if (i == 0) state[S_PHASE] = 1;
    if (i >= n) return;
    uint node = ls + i;
    uint4 b = nbox[node];
    uint flags = b.w >> 16;
    uint4 counts = uint4(0, 0, 0, 0);
    if ((flags & F_EMPTY) == 0) {
        uint err = nodeErr(i);
        int3 lo = boxLo(b), hi = boxHi(b), e = hi - lo;
        bool leaf = err == 0 || e.x * e.y * e.z <= 1;
        flags = leaf ? F_LEAF : F_INTERNAL;
        nbox[node] = uint4(b.xyz, err | flags << 16);
        if (!leaf) {
            uint tasks = 0;
            for (int a = 0; a < 3; a++)
                tasks += uint(blocksOn(lo[a], hi[a]) * blocksOn(lo[(a + 2) % 3], hi[(a + 2) % 3]));
            counts = uint4(uint(e.x + e.y + e.z), tasks, 2, wideSweep(e) ? 1u : 0u);
        }
    }
    scanIn[i] = counts;
}

// ---- slices --------------------------------------------------------------------------------------------------------

// node box b's slice work, axis by axis in its axis mask: (slices, tasks) before axis a, and axis a's own
void axisBases(uint4 b, uint mask, int a, out uint sliceBase, out uint taskBase) {
    int3 lo = boxLo(b), hi = boxHi(b);
    sliceBase = 0;
    taskBase = 0;
    for (int k = 0; k < a; k++) {
        if (((mask >> uint(k)) & 1u) == 0) continue;
        sliceBase += uint(hi[k] - lo[k]);
        taskBase += uint(blocksOn(lo[k], hi[k]) * blocksOn(lo[(k + 2) % 3], hi[(k + 2) % 3]));
    }
}

// a brick's micro-voxel layer s across axis a: how many it holds, and which positions on a1 and on a2 (4-bit masks)
uint yLayer(uint2 m, int y) { return ((y < 2 ? m.x : m.y) >> uint(16 * (y & 1))) & 0xFFFFu; }
void layerStats(uint2 m, int a, int s, out uint cnt, out uint m1, out uint m2) {
    cnt = 0;
    m1 = 0;
    m2 = 0;
    if (a == 1) {  // y = s: bits x + 4 z; a1 = z, a2 = x
        uint L = yLayer(m, s);
        cnt = countbits(L);
        m1 = ((L & 0xFu) != 0 ? 1u : 0u) | ((L & 0xF0u) != 0 ? 2u : 0u) | ((L & 0xF00u) != 0 ? 4u : 0u) | ((L & 0xF000u) != 0 ? 8u : 0u);
        m2 = (L | L >> 4 | L >> 8 | L >> 12) & 0xFu;
    } else if (a == 2) {  // z = s: a1 = x, a2 = y
        for (int y = 0; y < 4; y++) {
            uint nib = (yLayer(m, y) >> uint(4 * s)) & 0xFu;
            cnt += countbits(nib);
            m1 |= nib;
            if (nib != 0) m2 |= 1u << uint(y);
        }
    } else {  // x = s: a1 = y, a2 = z
        for (int y = 0; y < 4; y++) {
            uint L = yLayer(m, y) >> uint(s);
            uint c = (L & 1u) | ((L >> 3) & 2u) | ((L >> 6) & 4u) | ((L >> 9) & 8u);
            cnt += countbits(c);
            if (c != 0) m1 |= 1u << uint(y);
            m2 |= c;
        }
    }
}

// one block layer (li along a) and block row (r along a2) of a node: the content of its 4 micro-voxel slices, each a
// count and bounds on a1 and a2
[numthreads(64, 1, 1)]
void lbSliceTask(uint3 id: SV_DispatchThreadID) {
    uint t = lin(id);
    if (t >= state[S_TASKS]) return;
    uint ls = state[S_LS], n = state[S_LE] - ls;
    uint i = findItem(t, n, 1);
    uint4 b = nbox[ls + i];
    uint mask = axisMask(b.w >> 16);
    int3 lo = boxLo(b), hi = boxHi(b);
    int local = int(t - scanOut[i].y), a = 0;
    for (; a < 2; a++) {
        if (((mask >> uint(a)) & 1u) == 0) continue;
        int per = blocksOn(lo[a], hi[a]) * blocksOn(lo[(a + 2) % 3], hi[(a + 2) % 3]);
        if (local < per) break;
        local -= per;
    }
    int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
    int rows = blocksOn(lo[a2], hi[a2]);
    int3 blk = int3(0, 0, 0);
    blk[a] = (lo[a] >> 2) + local / rows;
    blk[a2] = (lo[a2] >> 2) + local % rows;
    uint cnt[4] = { 0, 0, 0, 0 };
    uint r1[4] = { EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE };
    uint r2[4] = { EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE };
    for (int c = lo[a1] >> 2; c <= (hi[a1] - 1) >> 2; c++) {
        blk[a1] = c;
        uint br = grid[blockIndex(blk)];
        if (br == 0) continue;
        uint4 k = bricks[br];
        bool whole;
        uint2 m = boxMask(blk, lo, hi, whole) & k.xy;
        int3 o = blk * 4;
        if (whole && m.x == 0xFFFFFFFFu && m.y == 0xFFFFFFFFu) {  // a full block inside the box
            for (int s = 0; s < 4; s++) {
                cnt[s] += 16;
                r1[s] = merge(r1[s], range(o[a1], o[a1] + 4));
                r2[s] = merge(r2[s], range(o[a2], o[a2] + 4));
            }
            continue;
        }
        for (int s = 0; s < 4; s++) {
            uint c, m1, m2;
            layerStats(m, a, s, c, m1, m2);
            if (c == 0) continue;
            cnt[s] += c;
            r1[s] = merge(r1[s], range(o[a1] + int(firstbitlow(m1)), o[a1] + int(firstbithigh(m1)) + 1));
            r2[s] = merge(r2[s], range(o[a2] + int(firstbitlow(m2)), o[a2] + int(firstbithigh(m2)) + 1));
        }
    }
    for (int s = 0; s < 4; s++) part[t * 4u + uint(s)] = uint4(cnt[s], r1[s], r2[s], 0);
}

// per slice: its rows' partial records combined
[numthreads(64, 1, 1)]
void lbSliceReduce(uint3 id: SV_DispatchThreadID) {
    uint t = lin(id);
    if (t >= state[S_SLICES]) return;
    uint ls = state[S_LS], n = state[S_LE] - ls;
    uint i = findItem(t, n, 0);
    uint4 b = nbox[ls + i];
    uint mask = axisMask(b.w >> 16);
    int3 lo = boxLo(b), hi = boxHi(b);
    int local = int(t - scanOut[i].x), a = 0;
    for (; a < 2; a++) {
        if (((mask >> uint(a)) & 1u) == 0) continue;
        if (local < hi[a] - lo[a]) break;
        local -= hi[a] - lo[a];
    }
    uint sliceBase, taskBase;
    axisBases(b, mask, a, sliceBase, taskBase);
    int p = lo[a] + local, a2 = (a + 2) % 3;
    int rows = blocksOn(lo[a2], hi[a2]);
    uint task = scanOut[i].y + taskBase + uint(((p >> 2) - (lo[a] >> 2)) * rows);
    uint4 rec = uint4(0, EMPTY_RANGE, EMPTY_RANGE, 0);
    for (int r = 0; r < rows; r++) {
        uint4 q = part[(task + uint(r)) * 4u + uint(p & 3)];
        rec = uint4(rec.x + q.x, merge(rec.y, q.y), merge(rec.z, q.z), 0);
    }
    slices[t] = rec;
}

// slice k (axis a, at micro-voxel p) as a box of all three axes
uint4 sliceBox(uint4 rec, int a, int p) {
    uint4 bx = uint4(EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, 0);
    if (rec.x == 0) return bx;
    bx[a] = range(p, p + 1);
    bx[(a + 1) % 3] = rec.y;
    bx[(a + 2) % 3] = rec.z;
    return bx;
}
uint4 mergeBox(uint4 x, uint4 y) { return uint4(merge(x.x, y.x), merge(x.y, y.y), merge(x.z, y.z), 0); }

// ---- the split -----------------------------------------------------------------------------------------------------

// a split node's children (each side's tight box) appended to the next level; none found (impossible for a tight box
// of more than one micro-voxel): it stays a leaf, its two slots empty
void writeChildren(uint i, uint node, uint4 b, int bestAxis, uint4 bestL, uint4 bestR) {
    uint c0 = state[S_LE] + scanOut[i].z;
    naux[c0] = uint4(0, 0, 0, 0);
    naux[c0 + 1] = uint4(0, 0, 0, 0);
    if (bestAxis < 0) {
        nbox[node] = uint4(b.xyz, (b.w & 0xFFFFu) | F_LEAF << 16);
        nbox[c0] = uint4(0, 0, 0, F_EMPTY << 16);
        nbox[c0 + 1] = uint4(0, 0, 0, F_EMPTY << 16);
        return;
    }
    nbox[c0] = uint4(bestL.xyz, 0);
    nbox[c0 + 1] = uint4(bestR.xyz, 0);
    naux[node].x = c0;
}

// per inner node: atlas_lab's SAH over every slice plane of the three axes (a node a work group sweeps excepted)
[numthreads(64, 1, 1)]
void lbSweep(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id), ls = state[S_LS], le = state[S_LE];
    if (i >= le - ls) return;
    uint node = ls + i;
    uint4 b = nbox[node];
    if (((b.w >> 16) & F_INTERNAL) == 0) return;
    int3 lo = boxLo(b), hi = boxHi(b);
    if (wideSweep(hi - lo)) return;
    float best = 1e30f;
    int bestAxis = -1;
    uint4 bestL = uint4(0, 0, 0, 0), bestR = uint4(0, 0, 0, 0);
    uint base = scanOut[i].x;
    for (int a = 0; a < 3; a++) {
        int n = hi[a] - lo[a];
        if (n >= 2) {
            // suffix boxes and the total
            uint4 suf = uint4(EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, 0);
            uint2 total = uint2(0, 0);
            for (int k = n - 1; k >= 0; k--) {
                uint4 rec = slices[base + uint(k)];
                suf = mergeBox(suf, sliceBox(rec, a, lo[a] + k));
                total = add64(total, uint2(rec.x, 0));
                suffix[base + uint(k)] = suf;
            }
            uint4 pre = uint4(EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, 0);
            uint2 cpre = uint2(0, 0);
            for (int k = 1; k < n; k++) {  // split before slice k
                uint4 rec = slices[base + uint(k - 1)];
                pre = mergeBox(pre, sliceBox(rec, a, lo[a] + k - 1));
                cpre = add64(cpre, uint2(rec.x, 0));
                uint4 R = suffix[base + uint(k)];
                if (emptyRange(pre.x) || emptyRange(R.x)) continue;
                float c = boxArea(pre) * u64ToFloat(cpre) + boxArea(R) * u64ToFloat(sub64(total, cpre));
                if (c < best) {
                    best = c;
                    bestAxis = a;
                    bestL = pre;
                    bestR = R;
                }
            }
        }
        base += uint(n);
    }
    writeChildren(i, node, b, bestAxis, bestL, bestR);
}

// Vulkan: the same sweep by one work group per large node, 256 slices at a time (suffix and prefix boxes by group
// scans, each plane's cost as lbSweep computes it, the least cost and then the lowest plane kept: lbSweep's choice)
groupshared uint4 shBox[256];
groupshared uint2 shCnt[256];
groupshared float shCost[256];
groupshared uint shAt[256];

// inclusive scan of (box, count) in thread order; afterwards shBox / shCnt hold every thread's
void scanBoxes(uint t, inout uint4 box, inout uint2 cnt) {
    shBox[t] = box;
    shCnt[t] = cnt;
    GroupMemoryBarrierWithGroupSync();
    for (uint off = 1; off < 256u; off *= 2u) {
        bool has = t >= off;
        uint4 b2 = shBox[has ? t - off : t];
        uint2 c2 = has ? shCnt[has ? t - off : t] : uint2(0, 0);
        GroupMemoryBarrierWithGroupSync();
        if (has) {
            box = mergeBox(box, b2);
            cnt = add64(cnt, c2);
            shBox[t] = box;
            shCnt[t] = cnt;
        }
        GroupMemoryBarrierWithGroupSync();
    }
}

[numthreads(256, 1, 1)]
void lbSweepWide(uint3 gid: SV_GroupID, uint3 gt: SV_GroupThreadID) {
    uint g = gid.x + gid.y * 1024u, t = gt.x;
    uint ls = state[S_LS], n = state[S_LE] - ls;
    uint i = findItem(g, n, 3);
    uint node = ls + i;
    uint4 b = nbox[node];
    int3 lo = boxLo(b), hi = boxHi(b);
    float best = 1e30f;
    int bestAxis = -1;
    uint4 bestL = uint4(0, 0, 0, 0), bestR = uint4(0, 0, 0, 0);
    uint base = scanOut[i].x;
    const uint4 none = uint4(EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, 0);
    for (int a = 0; a < 3; a++) {
        int na = hi[a] - lo[a];
        if (na >= 2) {
            // suffix boxes, chunks from the end (thread 0 the chunk's last slice), and the total
            uint4 carry = none;
            uint2 total = uint2(0, 0);
            for (int c1 = na; c1 > 0; c1 -= 256) {
                int k = c1 - 1 - int(t);
                uint4 box = none;
                uint2 cnt = uint2(0, 0);
                if (k >= 0) {
                    uint4 rec = slices[base + uint(k)];
                    box = sliceBox(rec, a, lo[a] + k);
                    cnt = uint2(rec.x, 0);
                }
                scanBoxes(t, box, cnt);
                if (k >= 0) suffix[base + uint(k)] = mergeBox(box, carry);
                carry = mergeBox(carry, shBox[255]);
                total = add64(total, shCnt[255]);
                GroupMemoryBarrierWithGroupSync();
            }
            DeviceMemoryBarrierWithGroupSync();  // the suffix boxes, read by other threads below
            // prefix boxes, each plane's cost, the chunk's least
            uint4 pcarry = none;
            uint2 ccarry = uint2(0, 0);
            for (int c0 = 0; c0 < na; c0 += 256) {
                int k = c0 + int(t), split = k + 1;  // the plane before slice k + 1: the prefix through slice k
                uint4 box = none;
                uint2 cnt = uint2(0, 0);
                if (k < na) {
                    uint4 rec = slices[base + uint(k)];
                    box = sliceBox(rec, a, lo[a] + k);
                    cnt = uint2(rec.x, 0);
                }
                scanBoxes(t, box, cnt);
                uint4 pre = mergeBox(box, pcarry);
                uint2 cpre = add64(cnt, ccarry);
                float c = 1e30f;
                if (split < na) {
                    uint4 R = suffix[base + uint(split)];
                    if (!emptyRange(pre.x) && !emptyRange(R.x))
                        c = boxArea(pre) * u64ToFloat(cpre) + boxArea(R) * u64ToFloat(sub64(total, cpre));
                }
                shCost[t] = c;
                shAt[t] = t;
                GroupMemoryBarrierWithGroupSync();
                for (uint s = 128; s > 0; s /= 2u) {
                    if (t < s) {
                        float co = shCost[t + s];
                        uint ao = shAt[t + s];
                        if (co < shCost[t] || (co == shCost[t] && ao < shAt[t])) {
                            shCost[t] = co;
                            shAt[t] = ao;
                        }
                    }
                    GroupMemoryBarrierWithGroupSync();
                }
                float cm = shCost[0];
                uint w = shAt[0];
                if (cm < best) {
                    best = cm;
                    bestAxis = a;
                    bestL = mergeBox(shBox[w], pcarry);
                    bestR = suffix[base + uint(c0) + w + 1u];
                }
                pcarry = mergeBox(pcarry, shBox[255]);
                ccarry = add64(ccarry, shCnt[255]);
                GroupMemoryBarrierWithGroupSync();
            }
        }
        base += uint(na);
    }
    if (t == 0) writeChildren(i, node, b, bestAxis, bestL, bestR);
}

// ---- regions -------------------------------------------------------------------------------------------------------

// the first level: one work node per region, its y slices counted (to find the content's tight box)
[numthreads(1, 1, 1)]
void lbRegionInit(uint3 id: SV_DispatchThreadID) {
    uint n = uint(P.dims.w);
    for (uint i = 0; i < n; i++) {
        uint4 r = regions[i];
        nbox[i] = uint4(r.xyz, F_REGION << 16);
        naux[i] = uint4(0, 0, r.w, 0);
    }
    state[S_LEVEL] = 0;
    state[S_LS] = 0;
    state[S_LE] = n;
    state[S_LSTART] = 0;
    state[S_LSTART + 1] = n;
    state[S_NLEVELS] = n > 0 ? 1 : 0;
    state[S_END] = P.out.x;
    state[S_PHASE] = 2;
    setArgs(ARG_NODES, n, 64u);
    setArgs(ARG_SCAN, n, 1024u);
    setArgs(S_LARG, n, 64u);
    state[ARG_ONE] = 1;
    state[ARG_ONE + 1] = 1;
    state[ARG_ONE + 2] = 1;
    if (n > P.caps.x) {
        state[S_OVERFLOW] = O_NODES;
        state[S_DONE] = 1;
    }
}

[numthreads(64, 1, 1)]
void lbRegionCount(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id), ls = state[S_LS], n = state[S_LE] - ls;
    if (i >= n) return;
    uint4 b = nbox[ls + i];
    int3 lo = boxLo(b), hi = boxHi(b);
    bool none = any(lo >= hi);
    scanIn[i] = none ? uint4(0, 0, 0, 0) : uint4(uint(hi.y - lo.y), uint(blocksOn(lo.y, hi.y) * blocksOn(lo.x, hi.x)), 0, 0);
    if (none) nbox[ls + i] = uint4(b.xyz, F_EMPTY << 16);
}

// a region's work node becomes the tight box of its content (or empty)
[numthreads(64, 1, 1)]
void lbRegionTight(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id), ls = state[S_LS], n = state[S_LE] - ls;
    if (i >= n) return;
    uint4 b = nbox[ls + i];
    if (((b.w >> 16) & F_EMPTY) != 0) return;
    int3 lo = boxLo(b), hi = boxHi(b);
    uint4 box = uint4(EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, 0);
    uint base = scanOut[i].x;
    for (int k = 0; k < hi.y - lo.y; k++) box = mergeBox(box, sliceBox(slices[base + uint(k)], 1, lo.y + k));
    nbox[ls + i] = emptyRange(box.x) ? uint4(b.xyz, F_EMPTY << 16) : uint4(box.xyz, 0);
}

// ---- scans and the level loop --------------------------------------------------------------------------------------

// after a scan of the level's counts (totals in hand): the next dispatches' sizes, the capacities checked
void afterScan(uint4 total) {
    if (state[S_DONE] != 0) return;  // the levels recorded past the last one do nothing
    uint phase = state[S_PHASE];
    bool over = false;
    if (phase == 0) {
        state[S_ERRT] = total.w;
        if (total.w > P.caps.z) {
            state[S_OVERFLOW] |= O_PARTS;
            over = true;
        }
        setArgs(ARG_ERRT, total.w, 64u);
        setArgs(ARG_ERRWIDE, total.x, 1u);
    } else {
        state[S_SLICES] = total.x;
        state[S_TASKS] = total.y;
        state[S_CHILDREN] = total.z;
        state[S_NEXT] = state[S_LE] + total.z;
        if (total.x > P.caps.y) {
            state[S_OVERFLOW] |= O_SLICES;
            over = true;
        }
        if (total.y * 4u > P.caps.z || total.y > P.caps.z / 4u) {
            state[S_OVERFLOW] |= O_PARTS;
            over = true;
        }
        if (state[S_LE] + total.z > P.caps.x) {
            state[S_OVERFLOW] |= O_NODES;
            over = true;
        }
        setArgs(ARG_TASKS, total.y, 64u);
        setArgs(ARG_SLICES, total.x, 64u);
        setArgs(ARG_SWEEPWIDE, phase == 1 ? total.w : 0u, 1u);
    }
    if (over) {  // stop: every later dispatch is empty, the host grows the buffers and builds again
        state[S_DONE] = 1;
        for (uint k = ARG_NODES; k < ARG_ONE; k++) state[k] = 0;
        for (uint k = ARG_ERRWIDE; k < ARG_SWEEPWIDE + 3u; k++) state[k] = 0;
    }
}

// the CPU backend's scan: one thread over the level
[numthreads(1, 1, 1)]
void lbScanSerial(uint3 id: SV_DispatchThreadID) {
    uint n = state[S_LE] - state[S_LS];
    uint4 run = uint4(0, 0, 0, 0);
    for (uint i = 0; i < n; i++) {
        scanOut[i] = run;
        run += scanIn[i];
    }
    afterScan(run);
}

// Vulkan's: 1024 items a group, group sums scanned by one group, then added
groupshared uint4 shScan[256];

uint4 groupScan(uint t, uint4 v) {  // exclusive over the group's 256 threads; the total in shScan[255] + v of 255
    shScan[t] = v;
    GroupMemoryBarrierWithGroupSync();
    for (uint off = 1; off < 256u; off *= 2u) {
        uint4 x = t >= off ? shScan[t - off] : uint4(0, 0, 0, 0);
        GroupMemoryBarrierWithGroupSync();
        shScan[t] += x;
        GroupMemoryBarrierWithGroupSync();
    }
    uint4 incl = shScan[t];
    GroupMemoryBarrierWithGroupSync();
    return incl - v;
}

[numthreads(256, 1, 1)]
void lbScanBlocks(uint3 gid: SV_GroupID, uint3 gt: SV_GroupThreadID) {
    uint g = gid.x + gid.y * 1024u, t = gt.x;
    uint n = state[S_LE] - state[S_LS];
    uint at = g * 1024u + t * 4u;
    uint4 v[4];
    uint4 sum = uint4(0, 0, 0, 0);
    for (uint k = 0; k < 4; k++) {
        v[k] = at + k < n ? scanIn[at + k] : uint4(0, 0, 0, 0);
        sum += v[k];
    }
    uint4 run = groupScan(t, sum);
    for (uint k = 0; k < 4; k++) {
        if (at + k < n) scanOut[at + k] = run;
        run += v[k];
    }
    if (t == 255u) scanSum[g] = run;
}

[numthreads(256, 1, 1)]
void lbScanTop(uint3 gt: SV_GroupThreadID) {
    uint t = gt.x;
    uint n = state[S_LE] - state[S_LS];
    uint groups = (n + 1023u) / 1024u;
    uint per = (groups + 255u) / 256u;
    uint4 sum = uint4(0, 0, 0, 0);
    for (uint k = 0; k < per; k++) {
        uint j = t * per + k;
        if (j < groups) sum += scanSum[j];
    }
    uint4 run = groupScan(t, sum);
    for (uint k = 0; k < per; k++) {
        uint j = t * per + k;
        if (j < groups) {
            uint4 s = scanSum[j];
            scanSum[j] = run;
            run += s;
        }
    }
    if (t == 255u) afterScan(run);
}

[numthreads(256, 1, 1)]
void lbScanAdd(uint3 gid: SV_GroupID, uint3 gt: SV_GroupThreadID) {
    uint g = gid.x + gid.y * 1024u;
    uint n = state[S_LE] - state[S_LS];
    uint4 off = scanSum[g];
    for (uint k = 0; k < 4; k++) {
        uint at = g * 1024u + gt.x * 4u + k;
        if (at < n) scanOut[at] += off;
    }
}

// the next level: the children the split appended
[numthreads(1, 1, 1)]
void lbAdvance(uint3 id: SV_DispatchThreadID) {
    if (state[S_DONE] != 0) return;
    uint L = state[S_LEVEL], le = state[S_LE], next = state[S_NEXT];
    if (next == le) {  // no node split: the tree is complete
        state[S_DONE] = 1;
        state[S_TOTAL] = le;
        for (uint k = ARG_NODES; k < ARG_ONE; k++) state[k] = 0;
        for (uint k = ARG_ERRWIDE; k < ARG_SWEEPWIDE + 3u; k++) state[k] = 0;
        setArgs(ARG_PACK, le, 64u);
        return;
    }
    if (L + 2u >= MAXL) {
        state[S_OVERFLOW] |= O_LEVELS;
        state[S_DONE] = 1;
        for (uint k = ARG_NODES; k < ARG_ONE; k++) state[k] = 0;
        return;
    }
    state[S_LEVEL] = L + 1u;
    state[S_LS] = le;
    state[S_LE] = next;
    state[S_LSTART + L + 2u] = next;
    state[S_NLEVELS] = L + 2u;
    state[S_TOTAL] = next;
    setArgs(ARG_NODES, next - le, 64u);
    setArgs(ARG_SCAN, next - le, 1024u);
    setArgs(S_LARG + 3u * (L + 1u), next - le, 64u);
}

// ---- the depth-first layout (lodFlattenGroup's) --------------------------------------------------------------------

// level step.k.x, deepest first: the nodes below each node
[numthreads(64, 1, 1)]
void lbDescUp(uint3 id: SV_DispatchThreadID) {
    uint L = uint(step.k.x), ls = state[S_LSTART + L], le = state[S_LSTART + L + 1u], i = lin(id);
    if (L >= state[S_NLEVELS] || i >= le - ls) return;
    uint node = ls + i;
    uint flags = nbox[node].w >> 16;
    uint desc = 0;
    if ((flags & F_INTERNAL) != 0) {
        uint c = naux[node].x;
        desc = 2u + naux[c].y + naux[c + 1u].y;
    }
    naux[node].y = desc;
}

// the regions' roots: their slots, their subtrees appended one after another from the array's end
[numthreads(1, 1, 1)]
void lbRootBases(uint3 id: SV_DispatchThreadID) {
    uint n = uint(P.dims.w), end = P.out.x;
    for (uint i = 0; i < n; i++) {
        if (((nbox[i].w >> 16) & F_EMPTY) != 0) continue;
        naux[i].w = end;
        end += naux[i].y;
    }
    state[S_END] = end;
    if (end > P.caps.w) state[S_OVERFLOW] |= O_OUT;
}

// level step.k.x, top down: each inner node's children take its first slots, the left subtree right after them
[numthreads(64, 1, 1)]
void lbPosDown(uint3 id: SV_DispatchThreadID) {
    uint L = uint(step.k.x), ls = state[S_LSTART + L], le = state[S_LSTART + L + 1u], i = lin(id);
    if (L >= state[S_NLEVELS] || i >= le - ls) return;
    uint node = ls + i;
    if (((nbox[node].w >> 16) & F_INTERNAL) == 0) return;
    uint4 x = naux[node];
    uint c = x.x, first = x.w;
    naux[c].z = first;
    naux[c + 1u].z = first + 1u;
    naux[c].w = first + 2u;
    naux[c + 1u].w = first + 2u + naux[c].y;
}

// ---- updates: the nodes above rebuilt subtrees, refitted -----------------------------------------------------------

int3 nodeLo(uint4 w) { return int3(int(w.x & 0xFFFu), int(w.y & 0xFFFu), int(w.z & 0xFFFu)); }
int3 nodeHi(uint4 w) { return int3(int((w.x >> 12) & 0xFFFu), int((w.y >> 12) & 0xFFFu), int((w.z >> 12) & 0xFFFu)); }

// the refitted nodes (the step.k.y entries of the refit list after the regions, deepest first), one thread: each
// node's box the union of its children's (an emptied child dropped from its mask), written into its slot at once (its
// parent, later in the list, reads it; the error is measured next), and the list made a level for the error passes
[numthreads(1, 1, 1)]
void lbRefitBox(uint3 id: SV_DispatchThreadID) {
    uint n = uint(step.k.y);
    if (state[S_OVERFLOW] != 0) return;
    state[S_DONE] = 0;  // the build is over: its scans serve the refit
    state[S_LS] = 0;
    state[S_LE] = n;
    setArgs(ARG_NODES, n, 64u);
    setArgs(ARG_SCAN, n, 1024u);
    for (uint i = 0; i < n; i++) {
        uint slot = regions[P.out.y + i].w;
        uint4 w = outNodes[slot];
        uint mask = (w.x >> 24) & 3u, first = w.w, kept = 0, keptFirst = 0;
        uint4 box = uint4(EMPTY_RANGE, EMPTY_RANGE, EMPTY_RANGE, 0);
        uint j = first;
        for (uint c = 0; c < 2u; c++) {
            if (((mask >> c) & 1u) == 0) continue;
            uint4 k = outNodes[j];
            int3 lo = nodeLo(k), hi = nodeHi(k);
            if (lo.x < hi.x) {
                if (kept == 0) keptFirst = j;
                kept |= 1u << c;
                box = mergeBox(box, uint4(range(lo.x, hi.x), range(lo.y, hi.y), range(lo.z, hi.z), 0));
            }
            j++;
        }
        bool none = kept == 0;
        nbox[i] = none ? uint4(box.xyz, F_EMPTY << 16) : uint4(box.xyz, 0);
        naux[i] = uint4(slot, kept, keptFirst, 0);
        int3 lo = boxLo(box), hi = boxHi(box);
        outNodes[slot] = none ? uint4(0, 0, 0, 0)
                              : uint4(uint(lo.x) | uint(hi.x) << 12 | kept << 24, uint(lo.y) | uint(hi.y) << 12, uint(lo.z) | uint(hi.z) << 12,
                                      keptFirst);
    }
}
// their errors measured (the err passes ran on them), written into their slots
[numthreads(64, 1, 1)]
void lbRefitPack(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id), ls = state[S_LS], n = state[S_LE] - ls;
    if (i >= n || state[S_OVERFLOW] != 0) return;
    uint4 b = nbox[i], x = naux[i];
    if (((b.w >> 16) & F_EMPTY) != 0) {
        outNodes[x.x] = uint4(0, 0, 0, 0);
        return;
    }
    uint e = min(nodeErr(i), 4095u);
    int3 lo = boxLo(b), hi = boxHi(b);
    outNodes[x.x] = uint4(uint(lo.x) | uint(hi.x) << 12 | x.y << 24, uint(lo.y) | uint(hi.y) << 12 | (e & 255u) << 24,
                          uint(lo.z) | uint(hi.z) << 12 | (e >> 8) << 24, x.z);
}

// every node in its slot: lodPack's words
[numthreads(64, 1, 1)]
void lbPack(uint3 id: SV_DispatchThreadID) {
    uint i = lin(id);
    if (i >= state[S_TOTAL] || state[S_OVERFLOW] != 0) return;
    uint4 b = nbox[i];
    uint flags = b.w >> 16;
    uint4 x = naux[i];
    if ((flags & F_EMPTY) != 0) {
        if (i < uint(P.dims.w)) outNodes[x.z] = uint4(0, 0, 0, 0);  // a region left empty: its slot says so (lo = hi)
        return;
    }
    bool inner = (flags & F_INTERNAL) != 0;
    uint mask = inner ? 3u : 0u, e = min(b.w & 0xFFFFu, 4095u);
    int3 lo = boxLo(b), hi = boxHi(b);
    outNodes[x.z] = uint4(uint(lo.x) | uint(hi.x) << 12 | mask << 24, uint(lo.y) | uint(hi.y) << 12 | (e & 255u) << 24,
                          uint(lo.z) | uint(hi.z) << 12 | (e >> 8) << 24, inner ? x.w : 0u);
}
