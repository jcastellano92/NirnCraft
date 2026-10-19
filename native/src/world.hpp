// The far-field world: column tiles and chunk sections in, a clipmap of SVDAG regions out, packed for the kernels.
#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "gpu_layout.hpp"
#include "groups.hpp"
#include "svdag.hpp"

namespace atlas {

constexpr int WORLD_MIN_Y = -64;
constexpr int WORLD_TOP_Y = 640;
constexpr int TILE = 256;
constexpr float FILL_DEPTH = 3.0f;  // blocks of fill material under the top voxel

struct Key3 {
    int lod, x, y, z;
    bool operator==(const Key3&) const = default;
};
struct Key3Hash {
    size_t operator()(const Key3& k) const {
        uint64_t h = uint64_t(uint32_t(k.x)) * 0x9E3779B97F4A7C15ull ^ uint64_t(uint32_t(k.z)) * 0xC2B2AE3D27D4EB4Full ^
                     uint64_t(uint32_t(k.y)) * 0x165667B19E3779F9ull ^ uint64_t(k.lod) * 0x27D4EB2F165667C5ull;
        return size_t(h ^ (h >> 31));
    }
};

// Buildings over a tile's columns (dense, TILE^2 each; roofTop <= the surface or NaN = none): walls from the ground
// up to wallTop, roof up to roofTop. base (empty, or TILE^2): where the span starts instead of the ground (an arch's
// deck, a canopy, a floating rock: air below it); -inf = from the ground.
struct Structures {
    std::vector<float> wallTop, roofTop, base;
    std::vector<uint16_t> wallMat, roofMat;
    bool replacesCanopy = false;  // the tile's real trees: its dressing's canopy goes (ATLAS_STRUCTURES_REPLACE_CANOPY)
};

struct Tile {
    int lod = 0, tx = 0, tz = 0;
    std::vector<float> surface, water, canopy;
    std::vector<uint16_t> top, fill, canopyMat;
    std::vector<uint16_t> waterMat;  // per column (flowing water, ice), or empty: World::waterMat everywhere
    std::shared_ptr<const Structures> structures;  // null: none
    // min of surface and max of the highest occupied point per aligned n x n block, n = 2^level, level 0..8
    std::array<std::vector<float>, 9> minSurf, maxTop;
    void buildPyramids();
};

struct Section {
    std::array<uint16_t, 4096> mats;  // x + 16 * (z + 16 * y)
    // the generated coarser levels (docs/ATLAS-LOD.md): mip[k - 1] is level k, (16 >> k)^3 voxels, same layout
    std::array<std::vector<uint16_t>, 4> mip;
    // builds mip from mats: cls[material] = its class (0 terrain / solid, 1 foliage, 2 water, 3 emissive, 5 falls)
    void buildMips(const std::vector<uint8_t>& cls);
    uint16_t at(int level, int lx, int ly, int lz) const {  // level 0..4, coordinates in that level's voxels
        if (level == 0) return mats[size_t(lx + 16 * (lz + 16 * ly))];
        int n = 16 >> level;
        return mip[size_t(level - 1)][size_t(lx + n * (lz + n * ly))];
    }
};

struct Region {
    Key3 key;
    std::shared_ptr<const Svdag> dag;
    // its content-shaped groups (groups.hpp), built once a frame asks for them (World::groupsWanted); null: not yet
    std::shared_ptr<const GroupTree> groups;
    bool empty() const { return !dag || dag->empty(); }
};

// Packed scene: what the kernels read.
struct PackedScene {
    std::vector<int32_t> table;
    std::vector<RegionGpu> regions;
    std::vector<uint32_t> nodes;
    std::vector<uint64_t> leaves;
    std::vector<uint32_t> runEnd;
    std::vector<uint32_t> runMat;
    std::vector<uint32_t> heightMip;  // per region 5461 entries (see Svdag::heightMip), at RegionGpu.bases.w
    // the groups (shaders/atlas_groups.slang), uint4 aligned: a header (the material shape table's word offset, the
    // shapes' uint4 offset, the table's length), the shapes (SHAPE_UINT4 each), the table (material -> shape + 1), then
    // per region its nodes, brick columns, slots and bricks (RegionGpu.groups)
    std::vector<uint32_t> groups;
    LodGpu lods[MAX_LODS] = {};
    int lodCount = 0;
    uint64_t revision = 0;
};

class World {
public:
    int lodCount = 7;
    int windowRadius = 2;  // regions either side of the camera's region, per LOD
    // real sections are kept this many LOD-0 regions either side of the camera (0: windowRadius + 2, ~1.2 km; the
    // server sends them out to ~1.5 km). ATLAS_SECTION_KEEP: farther, for measurements of elements kilometres away
    int sectionKeep = 0;
    uint16_t stoneMat = 1;
    uint16_t waterMat = 6;
    // refinement fades (atlas_set_transitions): a region rebuilt over a resident one (an exact tile replacing the
    // stand-in, buildings arriving) keeps the old one packed for fadeMs, and the kernel dissolves from old to new
    // per pixel over that time. 0 = off (swap at once).
    std::atomic<uint32_t> fadeMs{0};

    void putTile(std::unique_ptr<Tile> t);
    void putSection(int sx, int sy, int sz, const uint16_t* mats);
    // the palette's material classes (for the sections' generated levels); set when the palette changes
    void setClasses(std::vector<uint8_t> cls);
    void markSectionDirty(int sx, int sy, int sz);  // caller holds mu_
    void editBlock(int x, int y, int z, uint16_t mat);
    const Tile* tile(int lod, int tx, int tz) const;  // caller holds mu_ (or is single threaded)
    bool hasTile(int lod, int tx, int tz) const;      // locks
    // Buildings of tile (lod, tx, tz), kept across later uploads of its ground; null removes them.
    void putStructures(int lod, int tx, int tz, std::shared_ptr<const Structures> s);

    // Build what the window around `centre` needs; returns regions built. budgetMs <= 0: no limit.
    int update(const double centre[3], double budgetMs, double* buildMs);
    // Immutable snapshot of the packed scene; a frame holds it for as long as it reads it.
    std::shared_ptr<const PackedScene> packed() const {
        std::lock_guard<std::mutex> lock(snapMu_);
        return packed_;
    }

    static int regionRows(int lod);  // vertical regions needed to span y -64..640

    size_t regionCount() const { return regions_.size(); }
    size_t tileCount() const {
        std::lock_guard<std::mutex> lock(mu_);
        return tiles_.size();
    }
    uint64_t nodeBytes() const, leafBytes() const, materialBytes() const;

    // ---- the content-shaped groups (docs/ATLAS-LOD.md; groups.hpp)
    // set by the first frame that traces them (ATLAS_FRAME_GROUPS): from then on update() builds every region's tree too
    std::atomic<bool> groupsWanted{false};
    int groupLods = 7;  // levels that get trees (ATLAS_GROUP_LODS); coarser ones keep the old path
    GroupLimits groupLimits;
    // material shapes (atlas_set_material_shapes); first >= 1, null entries remove a shape
    void setShapes(uint32_t first, const std::vector<std::shared_ptr<const MaterialShape>>& shapes);
    struct GroupStats {
        uint32_t regions = 0, refused = 0;  // regions with a tree; regions whose tree was refused (too large)
        uint64_t bytes = 0, maxRegionBytes = 0, prims = 0, nodes = 0, bricks = 0;
        double buildMs = 0, maxBuildMs = 0;  // summed over the trees in the window; the slowest one
        uint32_t built = 0;                  // trees built by the last update
        double lastUpdateMs = 0;             // what the last update spent building trees (summed over threads)
    };
    GroupStats groupStats() const;

private:
    using SectionMap = std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash>;
    struct Job {
        Key3 key;
        std::shared_ptr<const Tile> tile;
        SectionMap sections;
        // groups: the neighbour tiles (x - 1, x + 1, z - 1, z + 1: the exposure of the region's border voxels), the
        // region whose SVDAG stays (a tree-only job), whether to build the tree
        std::shared_ptr<const Tile> nbr[4];
        std::shared_ptr<const Region> keep;
        bool groups = false;
        bool wasDirty = false;  // taken off the dirty set when snapshot (put back if the budget runs out first)
    };
    Job snapshot(const Key3& k, bool groups) const;  // caller holds mu_
    Region buildRegion(const Job& j) const;
    std::shared_ptr<const GroupTree> buildGroups(const Job& j, const ShapeSet& shapes) const;
    void repack(const int centreRegion[MAX_LODS][2]);

    mutable std::mutex mu_;
    // immutable once published: builders hold snapshots while uploads replace entries (copy on write)
    std::unordered_map<Key3, std::shared_ptr<const Tile>, Key3Hash> tiles_;
    std::unordered_map<Key3, std::shared_ptr<const Structures>, Key3Hash> structures_;
    std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash> sections_;
    std::vector<uint8_t> classes_;  // material -> class (setClasses), for the sections' generated levels
    std::unordered_map<Key3, std::shared_ptr<const Region>, Key3Hash> regions_;
    std::unordered_set<Key3, Key3Hash> dirty_;
    mutable std::mutex snapMu_;
    std::shared_ptr<const PackedScene> packed_ = std::make_shared<PackedScene>();
    int lastCentre_[MAX_LODS][2] = {};
    bool havePacked_ = false;
    // regions mid-fade: the one they replaced (null: there was nothing, the new one dissolves in) and when
    struct Fade {
        std::shared_ptr<const Region> old;  // null: empty before (wasEmpty) or new to the window
        uint32_t startMs;
        bool wasEmpty;
    };
    std::unordered_map<Key3, Fade, Key3Hash> fading_;
    std::shared_ptr<const ShapeSet> shapes_ = std::make_shared<ShapeSet>();
    uint64_t packedShapes_ = 0;  // the shape revision the packed scene carries
    uint32_t lastGroupsBuilt_ = 0;
    double lastGroupsMs_ = 0;
};

// Milliseconds on a steady clock (wraps after 49 days; compared as signed differences).
uint32_t nowMs();

}  // namespace atlas
