// Geometry SVDAG for one 256^3 region with a separate run-length material stream (after Aokana, 2025: geometry and
// attributes decoupled; materials indexed by the voxel's rank in depth-first order).
//
// Layout (all region-local; the packer adds the region's bases):
//  * nodes: uint32 words. A node is [mask | log2(size) << 8][ptr x n][prefix x n] where n = popcount(mask), ptr is the child node
//    word offset (or, under the last internal level, a leaf index), and prefix is the number of solid voxels in
//    the node's earlier children (prefix of the first child is 0). Child index c = x | z << 1 | y << 2 of the half (y-major).
//  * levels: node sizes 256, 128, 64, 32, 16, 8; the children of an 8-node are 4^3 leaf bricks.
//  * leaves: uint64 bitmaps, bit = x + 4 * z + 16 * y (y-major: horizontal layers are contiguous runs).
//  * materials: runs (end, mat) over the rank of each solid voxel; end is exclusive and cumulative.
// Identical subtrees are shared (hash-consed), so solid interiors and repeated air/ground patterns cost one node.
#pragma once
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace atlas {

constexpr int REGION = 256;
constexpr int BRICK = 4;
constexpr uint32_t EMPTY_ROOT = 0xFFFFFFFFu;

enum class BoxClass : uint8_t { Air, Uniform, Mixed };

// What a region is built from. Coordinates are region-local voxels.
struct VoxelSource {
    virtual ~VoxelSource() = default;
    // Classify the cube [x, x+n) x [y, y+n) x [z, z+n). Uniform means every voxel is solid with material `mat`.
    // Mixed is always a safe answer.
    virtual BoxClass classify(int x, int y, int z, int n, uint16_t& mat) = 0;
    // Material ids of a 4^3 brick, index x + 4 * y + 16 * z, 0 = air.
    virtual void brick(int x, int y, int z, uint16_t out[64]) = 0;
};

struct Svdag {
    uint32_t root = EMPTY_ROOT;
    std::vector<uint32_t> nodes;
    std::vector<uint64_t> leaves;
    std::vector<uint32_t> runEnd;
    std::vector<uint16_t> runMat;
    uint64_t solidVoxels = 0;
    // 2D max-height mip of the occupied voxels: level k holds (64 >> k)^2 cells of 4 << k voxel columns each, value =
    // highest occupied voxel y + 1 in the cell (0 = empty). Levels 0..6 concatenated (64^2 + 32^2 + ... + 1).
    std::vector<uint16_t> heightMip;

    bool empty() const { return root == EMPTY_ROOT; }
    size_t bytes() const { return nodes.size() * 4 + leaves.size() * 8 + runEnd.size() * 6; }

    // Reference queries (CPU, used by tests and the hydrator): material at a voxel, 0 for air.
    uint16_t at(int x, int y, int z) const;
    uint16_t materialOfRank(uint64_t rank) const;
};

Svdag buildSvdag(VoxelSource& src);

}  // namespace atlas
