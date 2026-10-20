// The level-0 grid built on the GPU (native/src/voxgrid.hpp: the layout, and the CPU reference these kernels match
// word for word). Per batch: the host copies the sections' raw ids into their pool slots, then
//   vgSectionMain  one group of 64 threads a job: the chunk's word; for a MIXED chunk its 64 block bitmaps and the empty
//                  blocks' boxes (2-bit extents within the chunk)
//   vgSatX/Z/Y     (when a chunk changed state) a summed-volume table of the chunks that stop a box (not EMPTY)
//   vgGrowMain     every EMPTY chunk's box (5-bit extents), grown one layer at a time, round robin over the six sides

struct VgParams {
    uint4 dims;  // D (x), H (y), D (z), job count
    uint4 misc;  // groups per dispatch row (vgSectionMain), 0, 0, 0
};

[[vk::push_constant]] ConstantBuffer<VgParams> pc;
[[vk::binding(0, 0)]] RWStructuredBuffer<uint> chunks;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> blocks;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint2> bits;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> pool;
[[vk::binding(4, 0)]] RWStructuredBuffer<uint4> jobs;  // chunk, word, slot (~0: none), data
[[vk::binding(5, 0)]] RWStructuredBuffer<uint> sat;

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
}

// the summed-volume table S[y][z][x] ((D + 1) x (H + 1) x (D + 1), zeroed by the host first): the count of non-EMPTY
// chunks below (x, y, z); three passes, each a running sum along one axis
uint satIndex(uint x, uint y, uint z) { return (y * (pc.dims.z + 1u) + z) * (pc.dims.x + 1u) + x; }

[shader("compute")]
[numthreads(64, 1, 1)]
void vgSatX(uint3 id : SV_DispatchThreadID) {
    uint X = pc.dims.x, Y = pc.dims.y, Z = pc.dims.z;
    if (id.x >= Y * Z) return;
    uint y = id.x / Z, z = id.x % Z;
    uint acc = 0u;
    for (uint x = 0u; x < X; x++) {
        acc += (chunks[(y * Z + z) * X + x] & 3u) != VG_EMPTY ? 1u : 0u;
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
    uint c = chunks[id.x];
    if ((c & 3u) != VG_EMPTY) return;
    int x = int(id.x % X), z = int((id.x / X) % Z), y = int(id.x / (X * Z));
    chunks[id.x] = growBox(int3(x, y, z), int3(int(X), int(Y), int(Z)), 31, 5u, 1u, uint2(0u, 0u)) << 2 | VG_EMPTY;
}
