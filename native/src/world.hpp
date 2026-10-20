// The far-field world: column tiles and chunk sections in, a clipmap of SVDAG regions out, packed for the kernels.
#pragma once
#include <algorithm>
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

// ---- coarse levels of real content (docs/ATLAS-LOD.md, "Coarse levels from real blocks") ---------------------------
// The levels 2..6 of real sections, derived from each section when it arrives and kept after the section itself is
// dropped, so a real chunk, tree or building is drawn from its own blocks at every distance. Per level a clipmap of
// cubes of 256 blocks (COARSE_CUBE: an LOD-0 region's side), each holding that level's voxels made from real blocks as
// buildGroups makes them (a material, 64 micro-voxels, the sub-voxels of those only partly covered: their content a
// quarter finer, groups.hpp SubVoxels; the content's box) and which of its 4096 sections it has seen. A level's voxel is at most 16 blocks (levels 2-4) or its micro-voxels are (5:
// 8 blocks, 6: 16): either way each lies in one section, so a section that arrives again replaces exactly its part.
constexpr int COARSE_CUBE = 256;
constexpr int COARSE_MIN = 2, COARSE_MAX = 6;  // the levels kept (0 and 1 are read from the sections themselves)
constexpr uint32_t BOX_NONE = 0xFFFFFFFFu;     // (CoarseCube::kidBoxes) a child with nothing in it

struct CoarseCube {
    struct Entry {
        uint32_t idx;  // the voxel in the cube: x + n * (z + n * y), n = COARSE_CUBE >> level
        uint16_t mat;  // its material (levels >= 5: from its children's, kids)
        uint16_t pad;
        uint64_t mask;  // its micro-voxels, bit x + 4 * (z + 4 * y) (SHAPE_FULL: all); 0 with a material: too sparse to
                        // show at this level, kept for the level above's material
        uint32_t box;   // its content's box (groups.hpp packBox: 16ths of the voxel; levels >= 5 its children's union)
        uint32_t faceAt;  // its sub-voxels in `faces`: one per micro-voxel of `partial`, in order
        uint64_t partial;  // its micro-voxels only partly covered (groups.hpp SubVoxels)
    };
    std::vector<Entry> entries;  // sorted by idx
    std::vector<uint16_t> kids;  // levels >= 5: per entry its 8 children's materials (level 5: sections' level-4
                                 // voxels; 6: level-5 voxels), octant x + 2 z + 4 y
    std::vector<uint32_t> kidBoxes;  // and their content's boxes in this voxel's 16ths (BOX_NONE: nothing)
    std::vector<uint64_t> faces;     // the entries' sub-voxels (Entry::faceAt)
    void facesOf(const Entry& e, SubVoxels& f) const {  // an entry's sub-voxels, whole
        f.partial = e.partial;
        uint32_t at = e.faceAt;
        for (int b = 0; b < 64; b++)
            if ((e.partial >> b) & 1) f.sub[size_t(b)] = faces[at++];
    }
    uint64_t seen[64] = {};      // the sections this level has data of (bit sx + 16 * (sz + 16 * sy), cube-local)
    bool sawSection(int lx, int ly, int lz) const {
        int b = lx + 16 * (lz + 16 * ly);
        return (seen[b >> 6] >> (b & 63)) & 1;
    }
    const Entry* find(uint32_t idx) const;
    size_t bytes() const { return sizeof(CoarseCube) + entries.size() * sizeof(Entry) + kids.size() * 6 + faces.size() * 8; }
};

struct Region {
    Key3 key;
    std::shared_ptr<const Svdag> dag;
    // its content-shaped groups (groups.hpp), built once a frame asks for them (World::groupsWanted); null: not yet
    std::shared_ptr<const GroupTree> groups;
    // nothing to draw: no voxels, and no groups (a coarse level of a few real blocks the voxels' mip vote dropped can
    // still have them: their coverage)
    bool empty() const { return (!dag || dag->empty()) && (!groups || groups->empty() || groups->refused); }
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
    std::vector<uint32_t> groups = std::vector<uint32_t>(4, 0u);  // (at least the header: the kernels read it always)
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
    // the real sections of columns [sx0, sx1) x [sz0, sz1) (the GPU's level-0 grid, voxgrid.hpp, re-reads them when
    // its window moves); and one section's ids (false: none)
    void sectionsIn(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, std::shared_ptr<const Section>>>& out) const;
    bool sectionMats(int sx, int sy, int sz, uint16_t out[4096]) const;
    // The GPU's level-0 grid (voxgrid.hpp) owns level 0 (ATLAS_VOXGRID_LEVEL0=1): a section it holds (inside its window)
    // no longer rebuilds its level-0 region on the CPU (the grid is rebuilt on the GPU; the region stays as it was, the
    // fallback past the grid). setGridWindow (the grid's window moved: x, z origin and size in sections) rebuilds the
    // regions skipped so whose sections the window has left. Levels 1-4 are rebuilt as before.
    std::atomic<bool> gridOwnsLevel0{false};
    void setGridWindow(int ox, int oz, int d);  // (no lock: the skipped regions are looked at by the next update)
    // sectionsIn, or false without waiting when an update holds the world's lock (the render thread's grid)
    bool trySectionsIn(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, std::shared_ptr<const Section>>>& out) const;
    size_t gridSkippedCount() const;
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
    // regions waiting for a build: dirty ones, and those the window wants that do not exist yet (last update's count)
    size_t pendingCount() const { return lastPending_.load(); }
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
    // surface cards (atlas_set_surface_cards), replaced whole
    void setCards(std::shared_ptr<const CardSet> cards);
    // material shapes (atlas_set_material_shapes); first >= 1, null entries remove a shape
    void setShapes(uint32_t first, const std::vector<std::shared_ptr<const MaterialShape>>& shapes);
    void setTextures(uint32_t first, const std::vector<std::shared_ptr<const MaterialTexture>>& textures);
    // (its own lock: every frame asks, and must not wait for an update holding the world's)
    std::shared_ptr<const TextureSet> textures() const {
        std::lock_guard<std::mutex> lock(texMu_);
        return textures_;
    }
    struct GroupStats {
        uint32_t regions = 0, refused = 0;  // regions with a tree; regions whose tree was refused (too large)
        uint64_t bytes = 0, maxRegionBytes = 0, prims = 0, nodes = 0, bricks = 0;
        double buildMs = 0, maxBuildMs = 0;  // summed over the trees in the window; the slowest one
        uint32_t built = 0;                  // trees built by the last update
        uint32_t maxDepth = 0;               // the deepest tree (the kernel's stack)
        double lastUpdateMs = 0;             // what the last update spent building trees (summed over threads)
    };
    GroupStats groupStats() const;

    // ---- coarse levels of real content (CoarseCube)
    // their memory budget (ATLAS_COARSE_BUDGET_MB): over it the cubes farthest from the camera for their level's size
    // go first (a fine level's far cubes before a coarse level's), never one within its level's window + 1 region
    uint64_t coarseBudget = 256ull << 20;
    struct CoarseStats {
        uint64_t bytes = 0;
        uint32_t cubes[MAX_LODS] = {}, entries = 0;
        uint64_t levelBytes[MAX_LODS] = {};
        uint32_t digested = 0, evicted = 0;  // sections taken in by the last update; cubes it dropped
        double digestMs = 0;                 // what that cost (summed over threads)
    };
    CoarseStats coarseStats() const;

    using CubeMap = std::unordered_map<Key3, std::shared_ptr<const CoarseCube>, Key3Hash>;

private:
    using SectionMap = std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash>;
    struct Job {
        Key3 key;
        std::shared_ptr<const Tile> tile;
        SectionMap sections;
        CubeMap coarse;  // groups: the coarse levels' cubes over the region and its ring (levels >= max(lod, 2))
        // groups: the neighbour tiles (x - 1, x + 1, z - 1, z + 1: the exposure of the region's border voxels), the
        // region whose SVDAG stays (a tree-only job), whether to build the tree
        std::shared_ptr<const Tile> nbr[4];
        std::shared_ptr<const Region> keep;
        bool groups = false;
        bool wasDirty = false;  // taken off the dirty set when snapshot (put back if the budget runs out first)
        bool wasTreeDirty = false;  // (treeDirty_)
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
    // levels 1-4 a section upload dirtied: rebuilt once the uploads pause (SECTION_QUIET_MS), not after every one
    std::unordered_set<Key3, Key3Hash> sectionDirty_;
    uint32_t lastSectionMs_ = 0;
    std::atomic<size_t> lastPending_{0};
    std::atomic<int> lastHidden_{0};  // wanted regions the last update found behind the horizon
    std::unordered_set<Key3, Key3Hash> treeDirty_;  // regions whose groups only are out of date (levels 5+: sections)
    // (gridOwnsLevel0) level-0 regions whose sections changed while the grid held them, and the grid's window
    std::unordered_set<Key3, Key3Hash> gridSkipped_;
    std::atomic<int> gridOx_{0}, gridOz_{0}, gridD_{0};
    std::atomic<bool> gridMoved_{false};
    void gridWindowMoved();  // (mu_ held) the skipped regions the window left, dirty
    bool gridHolds(int sx, int sz) const {
        int d = gridD_.load(std::memory_order_relaxed), ox = gridOx_.load(std::memory_order_relaxed),
            oz = gridOz_.load(std::memory_order_relaxed);
        return d > 0 && sx >= ox && sx < ox + d && sz >= oz && sz < oz + d;
    }
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
    std::shared_ptr<const TextureSet> textures_ = std::make_shared<TextureSet>();
    mutable std::mutex texMu_;  // (textures_)
    uint64_t packedShapes_ = 0;  // the shape revision the packed scene carries
    std::shared_ptr<const CardSet> cards_ = std::make_shared<CardSet>();
    uint64_t packedCards_ = 0;
    uint32_t lastGroupsBuilt_ = 0;
    double lastGroupsMs_ = 0;
    // the coarse levels (CoarseCube): key (level, cube x, y, z), cube y from the world floor; copy on write
    CubeMap coarse_;
    std::unordered_set<Key3, Key3Hash> undigested_;  // sections (level 0 keys) not yet taken into coarse_
    uint64_t digestShapes_ = ~0ull;                  // the shape revision the digests were made with
    uint64_t coarseBytes_ = 0;
    uint32_t lastDigested_ = 0, lastEvicted_ = 0;
    double lastDigestMs_ = 0;
    // takes undigested_ into coarse_ (within half the update's budget: nearest first) and enforces the memory budget
    void digest(const int centreRegion[MAX_LODS][2], double budgetMs);
    double digestMsPerSection_ = 0;
    // marks section (sx, sy, sz)'s regions at levels lo..hi in d, and with nbrs the ones across a border it touches
    void markRegions(int sx, int sy, int sz, int lo, int hi, std::unordered_set<Key3, Key3Hash>& d, bool nbrs);
    int coarseTop() const { return std::min(COARSE_MAX, lodCount - 1); }
    // the cubes a level-k.lod region's groups read (levels max(lod, COARSE_MIN)..coarseTop over the region and its
    // ring of one voxel; caller holds mu_)
    template <class F> void forCoarse(const Key3& k, F&& f) const;
    bool coarseNear(const Key3& k) const;  // any of them (caller holds mu_)
};

// Milliseconds on a steady clock (wraps after 49 days; compared as signed differences).
uint32_t nowMs();

}  // namespace atlas
