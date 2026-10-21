// The generator's chunk store (gen_engine.cpp, atlas.h atlas_gen_store_*): chunks waiting on the device until their
// neighbourhood is ready for each store stage. Two passes move a chunk between the generator's batch buffers and its
// store slot:
//   storeInMain   a batch chunk's blocks (just made: terrain, surface, map water) into its slot: the terrain as made
//                 (two 16-bit ids a word), the working blocks (one 32-bit word a block: priority << 16 | id, priority 0)
//                 and its quart biomes (from its page)
//   storeOutMain  a slot's working blocks back into the batch's blocks (the low 16 bits) for the pack and the ingest
// Block b = x + 16 * (z + 16 * (y + 64)) as atlas_gen.slang's blocks; no floating point here.

[[vk::binding(0, 0)]] RWStructuredBuffer<uint> genBlocks;   // the batch's: per chunk BLOCK_WORDS
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> terrain;     // per slot TERRAIN_WORDS
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> blocks;      // per slot BLOCK_WORDS
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> aux;         // per slot AUX_WORDS
[[vk::binding(4, 0)]] StructuredBuffer<int> list;           // per item: batch chunk, slot, cx, cz
[[vk::binding(5, 0)]] StructuredBuffer<uint> pages;
[[vk::binding(6, 0)]] StructuredBuffer<int> pageIndex;

struct StoreParams {
    int count;
    int pageMinCx, pageMinCz, pageW, pageH;
    uint pageWords;
    uint quartOffset;  // the quart biomes' offset in a page (fields * 256 + 256)
    uint pad;
};
[[vk::push_constant]] StoreParams params;

static const uint BLOCK_WORDS = 16u * 16u * 640u, TERRAIN_WORDS = BLOCK_WORDS / 2u, AUX_WORDS = 2048u, QUART_WORDS = 1408u;

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
        if (i < QUART_WORDS) {
            int cx = list[item * 4u + 2u], cz = list[item * 4u + 3u];
            int px = cx - params.pageMinCx, pz = cz - params.pageMinCz;
            int ps = (px < 0 || pz < 0 || px >= params.pageW || pz >= params.pageH) ? -1 : pageIndex[pz * params.pageW + px];
            if (ps >= 0) v = pages[uint(ps) * params.pageWords + params.quartOffset + i];
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
