// The GPU layouts of atlas_lab's two finalists (docs/ATLAS-LOD.md, "Slang port"; the kernels: shaders/atlas_lod.slang),
// built from atlas_lab's own hierarchies (atlas_lab_scene.hpp: buildBoxes, buildGroups), so the Slang kernels walk the
// very trees the C++ reference walks:
//   nodes   16 bytes a node for both: the tight box (12-bit micro-voxel coordinates), the flattening error (12 bits),
//           a child mask and the first child (children contiguous); the octree folds its single micro-voxels into
//           their 2^3 parents (a mask bit each, nothing stored)
//   pool    the face texels are not stored per node: a box face finds them through the micro-voxel brick pool (per
//           block a brick index; per brick the block's resolved 4x4x4 occupancy and its state; per state 6 x 4 x 4
//           texels), exactly as atlas_lab's boxFace walks microAt
#pragma once
#include <string>
#include <unordered_map>
#include <vector>

#include "atlas_lab_scene.hpp"

namespace {

constexpr uint32_t LOD_F_MICRO = 1;           // atlas_lod.slang F_MICRO
constexpr int LOD_COORD_MAX = 4095;           // 12 bits: volumes up to 1023 blocks a side
constexpr int LOD_OCT_STACK = 16, LOD_GROUP_STACK = 64;  // atlas_lod.slang OCT_STACK, GROUP_STACK
constexpr int LOD_STATE_WORDS = 100;

struct LodTree {
    std::vector<uint32_t> nodes;  // 4 words a node
    int root = -1;
    int depth = 0;     // the most stack entries a ray can need (levels below the root)
    size_t micro = 0;  // octree: the micro-voxel leaves folded into their parents
    size_t count() const { return nodes.size() / 4; }
    // the alternative layout, for comparison: every node's 6 face textures stored at micro-voxel resolution (4 bytes
    // a texel; a folded micro-voxel 6 texels)
    size_t faceTexelBytes() const {
        size_t texels = micro * 6;
        for (size_t i = 0; i < count(); i++) {
            const uint32_t* w = &nodes[i * 4];
            size_t e[3];
            for (int a = 0; a < 3; a++) e[a] = ((w[a] >> 12) & 0xFFFu) - (w[a] & 0xFFFu);
            texels += 2 * (e[0] * e[1] + e[1] * e[2] + e[2] * e[0]);
        }
        return texels * 4;
    }
};

struct LodPool {
    std::vector<uint32_t> grid;    // per block (x fastest, then z, then y): its brick, 0 none
    std::vector<uint32_t> bricks;  // 4 words a brick: occupancy lo, hi, state, 0; brick 0 empty
    std::vector<uint32_t> states;  // LOD_STATE_WORDS a state
    size_t bytes() const { return (grid.size() + bricks.size() + states.size()) * 4; }
};

bool lodPack(const int16_t lo[3], const int16_t hi[3], float err, uint32_t mask, uint32_t flags, uint32_t first, uint32_t* w,
             std::string& why) {
    for (int a = 0; a < 3; a++)
        if (lo[a] < 0 || hi[a] > LOD_COORD_MAX || lo[a] >= hi[a]) return why = "a box outside 12-bit coordinates (volume over 1023 blocks?)", false;
    if (err != std::floor(err) || err < 0) return why = "a flattening error that is not a whole number of micro-voxels", false;
    uint32_t e = uint32_t(std::min(err, 4095.f));  // saturated: drawn later, never earlier
    w[0] = uint32_t(lo[0]) | uint32_t(hi[0]) << 12 | mask << 24;
    w[1] = uint32_t(lo[1]) | uint32_t(hi[1]) << 12 | (e & 255u) << 24;
    w[2] = uint32_t(lo[2]) | uint32_t(hi[2]) << 12 | (e >> 8) << 24 | flags << 28;
    w[3] = first;
    return true;
}

// octree node `ref` written at `at`; its children (contiguous, octant order) are allocated here, depth first
bool lodFlattenNode(LodTree& t, int ref, size_t at, int depth, std::string& why) {
    const Node& n = nodes[size_t(ref)];
    uint32_t mask = 0, flags = 0, first = 0;
    t.depth = std::max(t.depth, depth);
    if (n.child >= 0) {
        int live[8], k = 0, micro = 0;
        for (int i = 0; i < 8; i++) {
            const Node& c = nodes[size_t(n.child + i)];
            if (c.lo[0] >= c.hi[0]) continue;
            mask |= 1u << i;
            live[k++] = n.child + i;
            micro += c.child < 0;
        }
        if (micro) {
            // a 2^3 cube whose octants are single micro-voxels: the kernel finds them again from its box
            for (int j = 0; j < k; j++) {
                const Node& c = nodes[size_t(live[j])];
                int i = live[j] - n.child, off[3] = {i & 1, i >> 2, (i >> 1) & 1};
                for (int a = 0; a < 3; a++)
                    if (c.child >= 0 || c.lo[a] != ((n.lo[a] & ~1) + off[a]) || c.hi[a] != c.lo[a] + 1)
                        return why = "an octree node mixing micro-voxels and larger children", false;
            }
            flags = LOD_F_MICRO;
            t.micro += size_t(k);
            t.depth = std::max(t.depth, depth + 1);
        } else {
            first = uint32_t(t.count());
            t.nodes.resize(t.nodes.size() + size_t(k) * 4);
            for (int j = 0; j < k; j++)
                if (!lodFlattenNode(t, live[j], first + size_t(j), depth + 1, why)) return false;
        }
    }
    return lodPack(n.lo, n.hi, n.err, mask, flags, first, &t.nodes[at * 4], why);
}

bool lodFlattenBoxes(LodTree& t, std::string& why) {
    t = {};
    if (rootNode < 0) return true;
    t.nodes.resize(4);
    t.root = 0;
    return lodFlattenNode(t, rootNode, 0, 0, why);
}

// group `ref` written at `at`; its children (left, then right) are allocated here, depth first
bool lodFlattenGroup(LodTree& t, int ref, size_t at, int depth, std::string& why) {
    const Group& g = groups[size_t(ref)];
    uint32_t mask = (g.left >= 0 ? 1u : 0u) | (g.right >= 0 ? 2u : 0u), first = 0;
    t.depth = std::max(t.depth, depth);
    if (mask) {
        first = uint32_t(t.count());
        int k = (g.left >= 0) + (g.right >= 0), j = 0;
        t.nodes.resize(t.nodes.size() + size_t(k) * 4);
        if (g.left >= 0 && !lodFlattenGroup(t, g.left, first + size_t(j++), depth + 1, why)) return false;
        if (g.right >= 0 && !lodFlattenGroup(t, g.right, first + size_t(j), depth + 1, why)) return false;
    }
    return lodPack(g.lo, g.hi, g.err, mask, 0, first, &t.nodes[at * 4], why);
}

bool lodFlattenGroups(LodTree& t, std::string& why) {
    t = {};
    if (rootGroup < 0) return true;
    t.nodes.resize(4);
    t.root = 0;
    return lodFlattenGroup(t, rootGroup, 0, 0, why);
}

// the brick pool: every block with content, its micro-voxels resolved as microAt resolves them (shape and the hashed
// cutout of leaves), deduplicated by (occupancy, state); only the states the volume uses
bool lodBuildPool(LodPool& p, std::string& why) {
    p = {};
    if (vol.sparse) return why = "boxes and groups build from dense volumes (.alv)", false;
    p.grid.assign(size_t(vol.sx) * vol.sy * vol.sz, 0);
    p.bricks.assign(4, 0);
    std::unordered_map<uint32_t, uint32_t> stateOf;
    std::map<std::pair<uint64_t, uint32_t>, uint32_t> bricks;
    for (int y = 0; y < vol.sy; y++)
        for (int z = 0; z < vol.sz; z++)
            for (int x = 0; x < vol.sx; x++) {
                const Block* b = vol.at(x, y, z);
                if (!b || !b->any || !b->mask) continue;
                uint64_t m = 0;
                for (int bit = 0; bit < 64; bit++)
                    if (microAt(x * 4 + (bit & 3), y * 4 + (bit >> 4), z * 4 + ((bit >> 2) & 3))) m |= 1ull << bit;
                if (!m) continue;
                uint32_t id = uint32_t(b - lib.data());
                auto st = stateOf.find(id);
                if (st == stateOf.end()) {
                    uint32_t s = uint32_t(p.states.size() / LOD_STATE_WORDS);
                    st = stateOf.emplace(id, s).first;
                    p.states.resize(p.states.size() + LOD_STATE_WORDS, 0);
                    uint32_t* w = &p.states[size_t(s) * LOD_STATE_WORDS];
                    for (float c : {b->mean.r, b->mean.g, b->mean.b})
                        if (c != std::floor(c) || c < 0 || c > 255) return why = "a mean colour that is not 8-bit", false;
                    w[0] = uint32_t(b->mean.r) << 16 | uint32_t(b->mean.g) << 8 | uint32_t(b->mean.b);
                    w[1] = b->tint;
                    for (int f = 0; f < 6; f++)
                        for (int t = 0; t < 16; t++) w[4 + f * 16 + t] = b->face[f][t];
                }
                auto key = std::make_pair(m, st->second);
                auto br = bricks.find(key);
                if (br == bricks.end()) {
                    br = bricks.emplace(key, uint32_t(p.bricks.size() / 4)).first;
                    p.bricks.insert(p.bricks.end(), {uint32_t(m), uint32_t(m >> 32), st->second, 0u});
                }
                p.grid[size_t(x + vol.sx * (z + vol.sz * y))] = br->second;
            }
    return true;
}

}  // namespace
