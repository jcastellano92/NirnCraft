// The level-0 grid built on the GPU (native/src/voxgrid.hpp: the layout, and the CPU reference these kernels match
// word for word). Per batch: the host copies the sections' raw ids into their pool slots, then
//   vgSectionMain  one group of 64 threads a job: the chunk's word; for a MIXED chunk its 64 block bitmaps and the empty
//                  blocks' boxes (2-bit extents within the chunk)
//   vgSatX/Z/Y     (when a chunk changed state) a summed-volume table of the chunks that stop a box (not EMPTY)
//   vgGrowMain     every EMPTY chunk's box (5-bit extents), grown one layer at a time, round robin over the six sides
// The coarse levels (voxgrid.hpp), before vgSectionMain builds their chunks' bitmaps:
//   vgFillMain     boxes of a chunk set to one id (a new chunk to air; rock under a column's lowest section; air)
//   vgIngestMain   a section's generated levels 1-4 from its raw ids (Section::buildMips' vote), each into its place
//                  in that level's chunk
//   vgDownMain     (levels 5+) a chunk from its 8 children of the level below, by the same vote

struct VgParams {
    uint4 dims;  // D (x), H (y), D (z), job count
    // groups per dispatch row; then (sat, grow) the window's origin x mod D, z mod D, its first chunk word; (fill,
    // ingest, down) the jobs' first uint4, the class table's first uint4, the table's length (materials)
    uint4 misc;
};

[[vk::push_constant]] ConstantBuffer<VgParams> pc;
[[vk::binding(0, 0)]] RWStructuredBuffer<uint> chunks;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> blocks;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint2> bits;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> pool;
[[vk::binding(4, 0)]] RWStructuredBuffer<uint4> jobs;  // chunk, word, slot (~0: none), data; then the coarse jobs
[[vk::binding(5, 0)]] RWStructuredBuffer<uint> sat;
[[vk::binding(6, 0)]] RWStructuredBuffer<uint> src;  // the batch's sections' raw ids (2048 words each)

static const uint VG_EMPTY = 0u;

groupshared uint gMaskLo;
groupshared uint gMaskHi;

bool maskBit(uint2 m, int i) { return ((i < 32 ? m.x >> uint(i) : m.y >> uint(i - 32)) & 1u) != 0u; }

bool blockLayerOcc(uint2 m, int3 lo, int3 hi) {
    for (int y = lo.y; y <= hi.y; y++)
        for (int z = lo.z; z <= hi.z; z++)
            for (int x = lo.x; x <= hi.x; x++)
                if (maskBit(m, x + 4 * (z + 4 * y))) return true;
    return false;
}

uint satAt(int x, int y, int z) {
    uint X = pc.dims.x, Z = pc.dims.z;
    return sat[(uint(y) * (Z + 1u) + uint(z)) * (X + 1u) + uint(x)];
}

bool chunkLayerOcc(int3 lo, int3 hi) {
    int x0 = lo.x, y0 = lo.y, z0 = lo.z, x1 = hi.x + 1, y1 = hi.y + 1, z1 = hi.z + 1;
    int s = int(satAt(x1, y1, z1)) - int(satAt(x0, y1, z1)) - int(satAt(x1, y0, z1)) - int(satAt(x1, y1, z0)) +
            int(satAt(x0, y0, z1)) + int(satAt(x0, y1, z0)) + int(satAt(x1, y0, z0)) - int(satAt(x0, y0, z0));
    return s != 0;
}

// VoxGrid::growBox: mode 0 blocks of a chunk (mask), 1 chunks of the window (sat)
uint growBox(int3 c, int3 n, int maxExt, uint bitsPer, uint mode, uint2 mask) {
    int e[6] = { 0, 0, 0, 0, 0, 0 };
    bool done[6] = { false, false, false, false, false, false };
    for (int round = 0; round < maxExt; round++) {
        bool any = false;
        for (int d = 0; d < 6; d++) {
            if (done[d]) continue;
            int3 lo = int3(c.x - e[0], c.y - e[2], c.z - e[4]);
            int3 hi = int3(c.x + e[1], c.y + e[3], c.z + e[5]);
            int ax = d >> 1;
            bool pos = (d & 1) != 0;
            int layer = pos ? hi[ax] + 1 : lo[ax] - 1;
            if (e[d] >= maxExt || layer < 0 || layer >= n[ax]) {
                done[d] = true;
                continue;
            }
            lo[ax] = layer;
            hi[ax] = layer;
            bool occ = mode == 0u ? blockLayerOcc(mask, lo, hi) : chunkLayerOcc(lo, hi);
            if (occ) {
                done[d] = true;
                continue;
            }
            e[d]++;
            any = true;
        }
        if (!any) break;
    }
    uint w = 0u;
    for (int d = 0; d < 6; d++) w |= uint(e[d]) << (bitsPer * uint(d));
    return w;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void vgSectionMain(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID) {
    uint j = gid.y * pc.misc.x + gid.x;
    if (j >= pc.dims.w) return;  // (uniform across the group)
    uint4 job = jobs[j];
    uint bi = tid.x;
    if (job.z == 0xFFFFFFFEu) {  // (VG_COLUMN) a column the window left: its word into every cell of it
        if (bi < pc.dims.y) chunks[job.x + bi * pc.dims.x * pc.dims.z] = job.y;
        return;
    }
    if (bi == 0u) {
        chunks[job.x] = job.y;
        gMaskLo = 0u;
        gMaskHi = 0u;
    }
    if (job.z == 0xFFFFFFFFu) return;
    GroupMemoryBarrierWithGroupSync();
    uint slot = job.z;
    int bx = int(bi & 3u), bz = int((bi >> 2) & 3u), by = int(bi >> 4);
    uint2 v = uint2(0u, 0u);
    for (int ly = 0; ly < 4; ly++)
        for (int lz = 0; lz < 4; lz++) {
            int y = by * 4 + ly, z = bz * 4 + lz, x = bx * 4;
            uint i = uint(x + 16 * (z + 16 * y));  // even: 4 ids in two words
            uint w0 = pool[slot * 2048u + (i >> 1)], w1 = pool[slot * 2048u + (i >> 1) + 1u];
            uint row = ((w0 & 0xFFFFu) != 0u ? 1u : 0u) | ((w0 >> 16) != 0u ? 2u : 0u) | ((w1 & 0xFFFFu) != 0u ? 4u : 0u) |
                       ((w1 >> 16) != 0u ? 8u : 0u);
            uint b = uint(4 * lz + 16 * ly);
            if (b < 32u) v.x |= row << b; else v.y |= row << (b - 32u);
        }
    bits[slot * 64u + bi] = v;
    bool full = (v.x | v.y) != 0u;
    if (full) {
        if (bi < 32u) InterlockedOr(gMaskLo, 1u << bi);
        else InterlockedOr(gMaskHi, 1u << (bi - 32u));
    }
    GroupMemoryBarrierWithGroupSync();
    uint2 mask = uint2(gMaskLo, gMaskHi);
    blocks[slot * 64u + bi] = full ? 1u : growBox(int3(bx, by, bz), int3(4, 4, 4), 3, 2u, 0u, mask) << 2;
    // (a coarse chunk whose content the vote left out: empty, its box grown with the rest)
    if (bi == 0u && mask.x == 0u && mask.y == 0u) chunks[job.x] = VG_EMPTY;
}

// ---- the coarse levels ------------------------------------------------------------------------------------------

// a 16-bit id of a slot's chunk, voxel i = x + 16 (z + 16 y); set with atomics when the word's other half may be
// another job's (a box with an odd x edge)
uint poolId(uint slot, uint i) { return (pool[slot * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu; }
void setIdAtomic(uint slot, uint i, uint id) {
    uint sh = 16u * (i & 1u);
    InterlockedAnd(pool[slot * 2048u + (i >> 1)], ~(0xFFFFu << sh));
    InterlockedOr(pool[slot * 2048u + (i >> 1)], id << sh);
}

// a material's kind for the vote (Section::buildMips): -1 air, 0 solid, 1 foliage, 2 water (the class table: 16 a
// uint4, a byte each)
int vKind(uint m) {
    if (m == 0u) return -1;
    uint c = 0u;
    if (m < pc.misc.w) {
        uint4 w4 = jobs[pc.misc.z + (m >> 4)];
        uint q = (m >> 2) & 3u;
        uint w = q == 0u ? w4.x : q == 1u ? w4.y : q == 2u ? w4.z : w4.w;
        c = (w >> ((m & 3u) * 8u)) & 255u;
    }
    return c == 1u ? 1 : (c == 2u ? 2 : 0);
}

// a coarse voxel from its 8 children (x + 2 z + 4 y): solid when 3 or more are, foliage when 2, water when 4, else air;
// the commonest of the winning kind's children seen from above, ties to the higher (world.cpp shownKid)
uint voteMip(uint ch[8]) {
    int kd[8];
    int c0 = 0, c1 = 0, c2 = 0;
    for (int k = 0; k < 8; k++) {
        kd[k] = vKind(ch[k]);
        c0 += kd[k] == 0 ? 1 : 0;
        c1 += kd[k] == 1 ? 1 : 0;
        c2 += kd[k] == 2 ? 1 : 0;
    }
    int want = c0 >= 3 ? 0 : (c1 >= 2 ? 1 : (c2 >= 4 ? 2 : -1));
    if (want < 0) return 0u;
    bool shown[8];
    for (int a = 0; a < 8; a++) shown[a] = a >= 4 || ch[a + 4] == 0u || kd[a + 4] != kd[a];
    uint best = 0u;
    int bestN = 0;
    for (int a = 7; a >= 0; a--) {
        if (ch[a] == 0u || kd[a] != want || !shown[a]) continue;
        int c = 0;
        for (int q = 0; q < 8; q++) c += ch[q] == ch[a] && shown[q] ? 1 : 0;
        if (c > bestN) {
            bestN = c;
            best = ch[a];
        }
    }
    return best;
}

// one group a fill job (slot, lo, hi, id): voxels [lo, hi) (x | y << 8 | z << 16)
[shader("compute")]
[numthreads(64, 1, 1)]
void vgFillMain(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID) {
    uint j = gid.y * pc.misc.x + gid.x;
    if (j >= pc.dims.w) return;
    uint4 f = jobs[pc.misc.y + j];
    uint3 lo = uint3(f.y & 255u, (f.y >> 8) & 255u, f.y >> 16), hi = uint3(f.z & 255u, (f.z >> 8) & 255u, f.z >> 16);
    uint3 n = hi - lo;
    if ((lo.x & 1u) == 0u && (n.x & 1u) == 0u) {
        // whole words: two voxels of the box each
        uint pairs = n.x / 2u, count = pairs * n.y * n.z;
        for (uint q = tid.x; q < count; q += 64u) {
            uint px = q % pairs, rest = q / pairs, z = rest % n.z, y = rest / n.z;
            uint i = (lo.x + 2u * px) + 16u * ((lo.z + z) + 16u * (lo.y + y));
            pool[f.x * 2048u + (i >> 1)] = f.w | f.w << 16;
        }
    } else {
        uint count = n.x * n.y * n.z;
        for (uint q = tid.x; q < count; q += 64u) {
            uint x = q % n.x, rest = q / n.x, z = rest % n.z, y = rest / n.z;
            setIdAtomic(f.x, (lo.x + x) + 16u * ((lo.z + z) + 16u * (lo.y + y)), f.w);
        }
    }
}

groupshared uint gS0[2048];  // the section's ids, two to a word
groupshared uint gL1[512];   // its levels 1-4, one id a word, x + n (z + n y)
groupshared uint gL2[64];
groupshared uint gL3[8];
groupshared uint gL4;

uint s0Id(uint x, uint y, uint z) {
    uint i = x + 16u * (z + 16u * y);
    return (gS0[i >> 1] >> (16u * (i & 1u))) & 0xFFFFu;
}

// a section's level k (n = 16 >> k voxels a side, from groupshared) into its place in a chunk: target slot << 12 |
// x | y << 4 | z << 8 (sections)
void writeLevel(uint target, uint k, uint t) {
    if (target == 0xFFFFFFFFu) return;
    uint slot = target >> 12;
    uint n = 16u >> k;
    uint3 o = uint3(target & 15u, (target >> 4) & 15u, (target >> 8) & 15u) * n;
    if (n == 1u) {
        if (t == 0u) setIdAtomic(slot, o.x + 16u * (o.z + 16u * o.y), gL4);
        return;
    }
    uint pairs = n * n * n / 2u;
    for (uint q = t; q < pairs; q += 64u) {
        uint v = 2u * q;  // x even: the pair (x, x + 1)
        uint x = v % n, z = (v / n) % n, y = v / (n * n);
        uint a, b;
        if (k == 1u) { a = gL1[v]; b = gL1[v + 1u]; }
        else if (k == 2u) { a = gL2[v]; b = gL2[v + 1u]; }
        else { a = gL3[v]; b = gL3[v + 1u]; }
        uint i = (o.x + x) + 16u * ((o.z + z) + 16u * (o.y + y));
        pool[slot * 2048u + (i >> 1)] = a | b << 16;
    }
}

// one group an ingest job (2 uint4: the section's index in src, its targets at levels 1, 2, 3; level 4's)
[shader("compute")]
[numthreads(64, 1, 1)]
void vgIngestMain(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID) {
    uint j = gid.y * pc.misc.x + gid.x;
    if (j >= pc.dims.w) return;  // (uniform across the group)
    uint4 a = jobs[pc.misc.y + 2u * j], b = jobs[pc.misc.y + 2u * j + 1u];
    uint t = tid.x;
    for (uint w = t; w < 2048u; w += 64u) gS0[w] = src[a.x * 2048u + w];
    GroupMemoryBarrierWithGroupSync();
    for (uint v = t; v < 512u; v += 64u) {
        uint x = v & 7u, z = (v >> 3) & 7u, y = v >> 6;
        uint ch[8];
        for (uint c = 0u; c < 8u; c++) ch[c] = s0Id(2u * x + (c & 1u), 2u * y + (c >> 2), 2u * z + ((c >> 1) & 1u));
        gL1[v] = voteMip(ch);
    }
    GroupMemoryBarrierWithGroupSync();
    {
        uint x = t & 3u, z = (t >> 2) & 3u, y = t >> 4;
        uint ch[8];
        for (uint c = 0u; c < 8u; c++) ch[c] = gL1[(2u * x + (c & 1u)) + 8u * ((2u * z + ((c >> 1) & 1u)) + 8u * (2u * y + (c >> 2)))];
        gL2[t] = voteMip(ch);
    }
    GroupMemoryBarrierWithGroupSync();
    if (t < 8u) {
        uint x = t & 1u, z = (t >> 1) & 1u, y = t >> 2;
        uint ch[8];
        for (uint c = 0u; c < 8u; c++) ch[c] = gL2[(2u * x + (c & 1u)) + 4u * ((2u * z + ((c >> 1) & 1u)) + 4u * (2u * y + (c >> 2)))];
        gL3[t] = voteMip(ch);
    }
    GroupMemoryBarrierWithGroupSync();
    if (t == 0u) {
        uint ch[8];
        for (uint c = 0u; c < 8u; c++) ch[c] = gL3[c];
        gL4 = voteMip(ch);
    }
    GroupMemoryBarrierWithGroupSync();
    writeLevel(a.y, 1u, t);
    writeLevel(a.z, 2u, t);
    writeLevel(a.w, 3u, t);
    writeLevel(b.x, 4u, t);
}

// one group a downsample job (3 uint4: the slot, the rock id; its children 0-3; 4-7: slots, or ~0 air, ~0 - 1 rock):
// its 4096 voxels, two to a word
[shader("compute")]
[numthreads(64, 1, 1)]
void vgDownMain(uint3 gid : SV_GroupID, uint3 tid : SV_GroupThreadID) {
    uint j = gid.y * pc.misc.x + gid.x;
    if (j >= pc.dims.w) return;
    uint4 h = jobs[pc.misc.y + 3u * j], k0 = jobs[pc.misc.y + 3u * j + 1u], k1 = jobs[pc.misc.y + 3u * j + 2u];
    uint kids[8] = { k0.x, k0.y, k0.z, k0.w, k1.x, k1.y, k1.z, k1.w };
    for (uint q = tid.x; q < 2048u; q += 64u) {
        uint v = 2u * q;
        uint ids[2];
        for (uint e = 0u; e < 2u; e++) {
            uint x = (v + e) & 15u, z = ((v + e) >> 4) & 15u, y = (v + e) >> 8;
            uint kid = kids[(x >> 3) + 2u * (z >> 3) + 4u * (y >> 3)];
            uint ch[8];
            for (uint c = 0u; c < 8u; c++) {
                uint cx = 2u * (x & 7u) + (c & 1u), cz = 2u * (z & 7u) + ((c >> 1) & 1u), cy = 2u * (y & 7u) + (c >> 2);
                ch[c] = kid == 0xFFFFFFFFu ? 0u : (kid == 0xFFFFFFFEu ? h.y : poolId(kid, cx + 16u * (cz + 16u * cy)));
            }
            ids[e] = voteMip(ch);
        }
        pool[h.x * 2048u + q] = ids[0] | ids[1] << 16;
    }
}

// the summed-volume table S[y][z][x] ((D + 1) x (H + 1) x (D + 1), zeroed by the host first): the count of non-EMPTY
// chunks below (x, y, z); three passes, each a running sum along one axis
uint satIndex(uint x, uint y, uint z) { return (y * (pc.dims.z + 1u) + z) * (pc.dims.x + 1u) + x; }

// the cell of the window's logical chunk (x, y, z) (from its origin): in memory at (x + origin) mod D (voxgrid.hpp phys)
uint chunkAt(uint x, uint y, uint z) {
    uint X = pc.dims.x, Z = pc.dims.z;
    return pc.misc.w + (y * Z + (z + pc.misc.z) % Z) * X + (x + pc.misc.y) % X;  // (misc.w: the level's first word)
}

[shader("compute")]
[numthreads(64, 1, 1)]
void vgSatX(uint3 id : SV_DispatchThreadID) {
    uint X = pc.dims.x, Y = pc.dims.y, Z = pc.dims.z;
    if (id.x >= Y * Z) return;
    uint y = id.x / Z, z = id.x % Z;
    uint acc = 0u;
    for (uint x = 0u; x < X; x++) {
        acc += (chunks[chunkAt(x, y, z)] & 3u) != VG_EMPTY ? 1u : 0u;
        sat[satIndex(x + 1u, y + 1u, z + 1u)] = acc;
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void vgSatZ(uint3 id : SV_DispatchThreadID) {
    uint X = pc.dims.x, Y = pc.dims.y, Z = pc.dims.z;
    if (id.x >= Y * X) return;
    uint y = id.x / X + 1u, x = id.x % X + 1u;
    uint acc = 0u;
    for (uint z = 1u; z <= Z; z++) {
        acc += sat[satIndex(x, y, z)];
        sat[satIndex(x, y, z)] = acc;
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void vgSatY(uint3 id : SV_DispatchThreadID) {
    uint X = pc.dims.x, Y = pc.dims.y, Z = pc.dims.z;
    if (id.x >= Z * X) return;
    uint z = id.x / X + 1u, x = id.x % X + 1u;
    uint acc = 0u;
    for (uint y = 1u; y <= Y; y++) {
        acc += sat[satIndex(x, y, z)];
        sat[satIndex(x, y, z)] = acc;
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void vgGrowMain(uint3 id : SV_DispatchThreadID) {
    uint X = pc.dims.x, Y = pc.dims.y, Z = pc.dims.z;
    if (id.x >= X * Y * Z) return;
    int x = int(id.x % X), z = int((id.x / X) % Z), y = int(id.x / (X * Z));  // (logical)
    uint at = chunkAt(uint(x), uint(y), uint(z));
    uint c = chunks[at];
    if ((c & 3u) != VG_EMPTY) return;
    chunks[at] = growBox(int3(x, y, z), int3(int(X), int(Y), int(Z)), 31, 5u, 1u, uint2(0u, 0u)) << 2 | VG_EMPTY;
}
