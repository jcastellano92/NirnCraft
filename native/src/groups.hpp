// Content-shaped groups in the far field (docs/ATLAS-LOD.md: "Content-shaped groups", "Slang port", "The renderer's
// groups"): per region a binary hierarchy of boxes over the region's micro-voxels (4 per voxel side), split where the
// two sides' tight boxes cost least (the SAH), each node carrying how deep its content lies under each of its six faces
// (the flattening error). A ray draws a node whole, as a box whose faces are masked textures of the content under them,
// where that error is under the pixel there, and opens it otherwise (shaders/atlas_groups.slang). The face texels are
// not stored per node: a drawn face finds them through the region's brick pool (per voxel its material) and the
// material's shape (its 4 x 4 x 4 occupancy and six 4 x 4 face tiles, atlas_set_material_shapes).
//
// Only voxels a ray can reach are content: a voxel with an open neighbour (air, a shaped block's empty part, cut-out
// leaves). Leaves of the hierarchy are flat solid boxes (every face covered at depth 0: a ground strip, a wall) or
// small boxes of at most 4 x 4 x 4 voxels that a ray steps through voxel by voxel (and micro-voxel by micro-voxel in a
// shaped block) when they are bigger than its pixel. Layouts: GroupTree below; the kernel reads them through one
// buffer of uint4 (PackedScene::groups).
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace atlas {

// ---- material shapes ------------------------------------------------------------------------------------------------

constexpr uint32_t SHAPE_CUTOUT = 1;  // atlas.h ATLAS_SHAPE_CUTOUT
constexpr uint64_t SHAPE_FULL = ~0ull;
// a shape in the scene buffer: 32 uint4 = mask lo, mask hi, flags, mean; 96 face texels; 64 alpha bytes; padding
constexpr uint32_t SHAPE_UINT4 = 32;

struct MaterialShape {
    uint64_t mask = SHAPE_FULL;  // micro-voxels, bit x + 4 * (z + 4 * y)
    uint32_t mean = 0;           // ARGB, sRGB
    uint32_t flags = 0;          // SHAPE_CUTOUT
    uint32_t faces[6][16] = {};  // down, up, north, south, west, east (atlas.h AtlasMaterialShape)
    uint8_t alpha[64] = {};      // per micro-voxel coverage, 255 = solid (cut-out: the face texels' alpha over it)
    // whether a ray can pass some of it (a shaped block's empty part, cut-out texels): its neighbours are exposed
    bool open() const { return mask != SHAPE_FULL || (flags & SHAPE_CUTOUT) != 0; }
    void deriveAlpha();  // alpha from the faces (cut-out) or 255
};

// The shapes in force: an immutable snapshot (replaced whole when shapes change), material id -> shape.
struct ShapeSet {
    std::vector<std::shared_ptr<const MaterialShape>> byMaterial;
    uint64_t revision = 0;
    const MaterialShape* at(uint32_t m) const { return m < byMaterial.size() ? byMaterial[m].get() : nullptr; }
};

// A surface card (atlas_set_surface_cards): a block face's colour as the game draws it, as a mip chain of its covered
// texels (16, 8, 4, 2, 1 a side: 341 RGB words; a texel showing nothing takes the face's mean).
struct SurfaceCard {
    int32_t x, y, z;
    uint32_t face;
    uint32_t mip[341];
};
constexpr int CARD_WORDS = 341;
struct CardSet {
    std::vector<SurfaceCard> cards;
    uint64_t revision = 0;
};

// ---- one region's groups --------------------------------------------------------------------------------------------

// A voxel as the builder and the brick pool hold it: palette id, | VOXEL_SHAPED when it is a real block (a section at
// LOD 0) and takes its material's shape, | VOXEL_MASKED when it carries micro-voxels of its own (a coarser level made
// from real blocks: what of its blocks is there, at its micro-voxels, GroupInput::masks). In the pool's 16-bit cells
// the flags are bits 15 and 14 (ids under 16384).
constexpr uint32_t VOXEL_SHAPED = 0x10000u;
constexpr uint32_t VOXEL_MASKED = 0x20000u;
constexpr uint32_t CELL_SHAPED = 0x8000u;
constexpr uint32_t CELL_MASKED = 0x4000u;
constexpr uint32_t CELL_MATERIAL = 0x3FFFu;
constexpr uint32_t SLOT_MASKS = 0x80000000u;  // a slot whose brick carries a mask table

// Node types (bits 30-31 of a node's w): an inner node (its two children at w & CHILD_MASK and the one after), a flat
// solid leaf (every face covered at depth 0: drawn as its box), a voxel leaf (at most 4 x 4 x 4 voxels: drawn as its
// box when its error is under the pixel, else stepped through).
constexpr uint32_t NODE_INNER = 0, NODE_FLAT = 1, NODE_VOXELS = 2;
constexpr uint32_t CHILD_MASK = 0x3FFFFFFFu;
constexpr int GROUP_STACK = 64;  // shaders/atlas_groups.slang: the deepest chain the kernel walks

// Flattening errors are kept per face as a 4-bit code: the smallest of these at least as large (drawn later, never
// earlier). 15 = more than 128 micro-voxels (only drawn when 4096 of them are under the pixel).
constexpr uint16_t ERR_CODE[16] = {0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32, 64, 128, 4096};
inline uint32_t errCode(uint32_t e) {
    uint32_t c = 0;
    while (c < 15 && ERR_CODE[c] < e) c++;
    return c;
}

struct GroupTree {
    std::vector<uint32_t> nodes;   // 4 words a node, node 0 the root; empty = no content
    // x / y / z: lo | hi << 12 (micro-voxels, region-local, [lo, hi)) | low face's error code << 24 | high face's << 28
    // w: type << 30 | first child (inner nodes; relative to the region's first node)
    std::vector<uint32_t> dir;     // the brick pool: 64 x 64 columns of 4 x 4 voxels (x fastest): slot offset (18 bits)
                                   // | first brick row << 18 (6 bits) | rows << 24 (7 bits; 0 = none)
    std::vector<uint32_t> slots;   // per brick row of a column: the brick's first word + 1 (0 = nothing there), | SLOT_MASKS
    std::vector<uint32_t> bricks;  // a brick: which of its 64 voxels (x + 4 * (z + 4 * y)) hold something (2 words), with
                                   // SLOT_MASKS which of those carry their own micro-voxels (2 words), then the occupied
                                   // voxels' 16-bit cells in that order, two to a word (low half first), then (SLOT_MASKS)
                                   // the masked ones' micro-voxels (2 words each, in that order)
    uint32_t prims = 0;            // content voxels
    uint32_t brickCount = 0;       // distinct bricks
    int depth = 0;
    double buildMs = 0;
    bool refused = false;          // too large (GroupLimits): the region keeps the old path
    bool empty() const { return nodes.empty(); }
    size_t bytes() const { return (nodes.size() + dir.size() + slots.size() + bricks.size()) * 4; }
};

// The voxels a region's groups are built from, gathered by World: columns over [-1, 257)^2 region-local (the ring
// outside: the neighbours' columns, for exposure), each the voxels of rows [lo, hi) (world voxel rows from the floor);
// below lo a column is solid and closed, from hi up air.
struct GroupInput {
    int lod = 0;
    int rowY0 = 0;               // the region's first voxel row (world rows from the floor, y -64)
    int microOrigin[3] = {};     // world micro-voxel of the region's micro (0, 0, 0) (LOD 0: blocks * 4): cut-out hashing
    std::vector<int32_t> lo, hi;  // per column, (z + 1) * 258 + (x + 1)
    std::vector<uint32_t> start;  // per column: its first voxel in vox
    std::vector<uint32_t> vox;    // material | VOXEL_SHAPED | VOXEL_MASKED
    std::unordered_map<uint32_t, uint64_t> masks;  // VOXEL_MASKED voxels (by their index in vox): their micro-voxels
    static int col(int x, int z) { return (z + 1) * 258 + (x + 1); }
    int64_t index(int x, int row, int z) const {  // world row; -1 above what was evaluated (air), -2 below (solid)
        int c = col(x, z);
        if (row >= hi[size_t(c)]) return -1;
        if (row < lo[size_t(c)]) return -2;
        return int64_t(start[size_t(c)]) + int64_t(row - lo[size_t(c)]);
    }
    uint32_t at(int x, int row, int z) const {
        int64_t i = index(x, row, z);
        return i == -1 ? 0u : i == -2 ? 0xFFFFFFFFu : vox[size_t(i)];  // (below: closed solid)
    }
};

struct GroupLimits {
    uint32_t maxNodes = 1u << 20;  // a region whose tree would be larger keeps the old path (16 MB of nodes)
};

// Builds a region's groups; an empty tree when it has no content (or `why` set when it was refused).
GroupTree buildGroupTree(const GroupInput& in, const ShapeSet& shapes, const GroupLimits& lim, std::string* why);

// The micro-voxel cut-out hash both the builder and the kernel use (shaders/atlas_groups.slang cutHash): a texel
// alpha a (0..255) keeps the micro-voxel when cutHash(world micro) >> 24 < a.
inline uint32_t cutHash(int x, int y, int z) {
    auto h = [](uint32_t v) {
        v ^= v >> 16;
        v *= 0x7feb352dU;
        v ^= v >> 15;
        v *= 0x846ca68bU;
        v ^= v >> 16;
        return v;
    };
    return h(uint32_t(x) * 73856093u ^ h(uint32_t(y) * 19349663u ^ h(uint32_t(z) * 83492791u ^ 0x5EEDu)));
}

}  // namespace atlas
