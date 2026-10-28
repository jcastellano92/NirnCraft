// The far-field world: column tiles and chunk sections in, a clipmap of SVDAG regions out, packed for the kernels.
#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <thread>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "gpu_layout.hpp"
#include "groups.hpp"
#include "svdag.hpp"

namespace atlas {

// A material's kind for the coarse vote by its class byte (api.cpp syncClasses): -1 air, 0 solid, 1 foliage, 2 water,
// 3 a solid brick, 4 a foliage brick (128 | foliage 64 | coverage 0..63), its coverage in cover (atlas_voxgrid.slang vKind).
inline int coarseKind(uint16_t m, const std::vector<uint8_t>& cls, float& cover) {
    cover = 1.0f;
    if (m == 0) return -1;
    uint8_t c = m < cls.size() ? cls[m] : 0;
    if (c >= 128) {
        cover = float(c & 63) / 63.0f;
        return (c & 64) ? 4 : 3;
    }
    return c == 1 ? 1 : c == 2 ? 2 : 0;  // emissive and falls count as solid blocks
}

// A coarse voxel from its 8 children (x + 2 z + 4 y), atlas_voxgrid.slang voteMip's rule: solid when 3 or more are
// (bricks by their coverage), foliage when 2 are leaves or half its volume is foliage (plants by their coverage), water
// when 4, else air (0); the commonest of the winning kind's children seen from above, full blocks before bricks, ties up.
inline uint16_t voteCoarse(const uint16_t ch[8], const std::vector<uint8_t>& cls) {
    int kd[8];
    float cv[8], c0 = 0, plants = 0;
    int c1 = 0, c2 = 0;
    for (int k = 0; k < 8; k++) {
        kd[k] = coarseKind(ch[k], cls, cv[k]);
        c0 += kd[k] == 0 ? 1.0f : kd[k] == 3 ? cv[k] : 0.0f;
        c1 += kd[k] == 1 ? 1 : 0;
        plants += kd[k] == 4 ? cv[k] : 0.0f;
        c2 += kd[k] == 2 ? 1 : 0;
    }
    int want = c0 >= 3.0f ? 0 : (c1 >= 2 || float(c1) + plants >= 4.0f) ? 1 : c2 >= 4 ? 2 : -1;
    if (want < 0) return 0;
    bool shown[8];
    for (int a = 0; a < 8; a++) shown[a] = a >= 4 || !ch[a + 4] || kd[a + 4] != kd[a];
    uint16_t best = 0;
    int bestN = 0;
    for (int pass = 0; pass < 2 && !best; pass++) {
        int kindOf = pass == 0 ? want : (want == 0 ? 3 : 4);
        for (int a = 7; a >= 0; a--) {
            if (!ch[a] || kd[a] != kindOf || !shown[a]) continue;
            int c = 0;
            for (int b = 0; b < 8; b++) c += ch[b] == ch[a] && shown[b] ? 1 : 0;
            if (c > bestN) bestN = c, best = ch[a];
        }
    }
    return best;
}


constexpr int WORLD_MIN_Y = -64;
constexpr int WORLD_TOP_Y = 640;
constexpr int TILE = 256;
constexpr float FILL_DEPTH = 3.0f;  // blocks of fill material under the top voxel

struct Key3 {
    int lod, x, y, z;
    bool operator==(const Key3&) const = default;
};
class SectionStore;  // (sectionstore.hpp)
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
    World();
    World(const World&) = delete;
    World& operator=(const World&) = delete;
    ~World();
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
    // (returns the stored section: immutable, shared with the GPU grid's books, VoxGrid::put). flags: SectionStore's
    // REGEN (the client generates it again when wanted: dropped, not kept, unless edited)
    std::shared_ptr<const Section> putSection(int sx, int sy, int sz, const uint16_t* mats, uint8_t flags = 0);
    // A section's ids as the host holds them: raw (an upload not yet packed; the CPU backend's) or packed (the warm or
    // cold tier: sectionstore.hpp; sectionpack.hpp's words)
    struct SectionSrc {
        std::shared_ptr<const Section> raw;
        std::shared_ptr<const std::vector<uint32_t>> packed;
        explicit operator bool() const { return raw || packed; }
    };
    // the real sections of columns [sx0, sx1) x [sz0, sz1) (the GPU's level-0 grid, voxgrid.hpp, re-reads them when
    // its window moves; the cold tier's read back); one section's ids (false: none); one section as held (or none)
    void sectionsIn(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, SectionSrc>>& out);
    struct SectionSnapshot {
        uint64_t revision = 0;
        std::vector<std::pair<Key3, SectionSrc>> entries;
    };
    // wait=true is the existing update thread: cold I/O runs outside mu_. The render path only tries memory.
    bool snapshotSections(int sx0, int sz0, int sx1, int sz1, bool wait, SectionSnapshot& out, bool outsideKeep = false,
                          bool collectEntries = true);
    // The caller holds its grid lock; never wait for the world. Validation and publication are one operation.
    bool applySections(const SectionSnapshot& snapshot, const std::function<void()>& apply) const;
    bool trySectionSource(int sx, int sy, int sz, SectionSrc& out);
    bool trySectionsInBox(int x0, int y0, int z0, int x1, int y1, int z1, std::vector<std::pair<Key3, SectionSrc>>& out);
    bool tryLayerNeed(const Key3& k, int& need);
    bool sectionMats(int sx, int sy, int sz, uint16_t out[4096]);
    SectionSrc sectionSource(int sx, int sy, int sz);
    bool resolveSectionSource(int sx, int sy, int sz, SectionSrc& out);  // update thread; false means retry, not absent
    // the sections of a box [x0, x1) x [y0, y1) x [z0, z1) by direct lookups (small boxes: a coarse chunk's)
    void sectionsInBox(int x0, int y0, int z0, int x1, int y1, int z1, std::vector<std::pair<Key3, SectionSrc>>& out);
    // The host's tiers (step 5's residency): uploads are packed on the GPU (the grid's batches: packOnGpu, set by the
    // Vulkan backend's context) into the warm tier; the raw copy goes once its packed words came back. The queue of
    // raw sections to pack (each with its generation: a newer upload makes an older pack stale), and the results.
    std::atomic<bool> packOnGpu{false};
    void setPackOnGpu(bool on);  // (turned on: the raw sections held already are queued too)
    struct PackReq {
        Key3 key;
        uint32_t gen = 0;
        std::shared_ptr<const Section> sec;
    };
    void takePackQueue(size_t max, std::vector<PackReq>& out, size_t burst = 0);  // (burst: up to queue / 8, this many)
    struct PackDone {
        Key3 key;
        uint32_t gen = 0, hash = 0;
        std::shared_ptr<const std::vector<uint32_t>> words;
    };
    // (the sections an edit layer's base made: for the grid, VoxGrid::put)
    using Made = std::vector<std::pair<Key3, std::shared_ptr<const Section>>>;
    // Never waits for the world's lock (the render thread calls it): when the lock is busy, results that need no `made` go to an
    // inbox the next holder of the lock applies (update, the spiller, the next call)
    void packedDone(const std::vector<PackDone>& done, Made* made = nullptr);
    // Edits are kept as layers over their generated base (SectionStore's EditLayer). An edit of a section the host does
    // not hold (the generator's own, on the GPU) waits for its base: the grid packs it on the GPU and reads it back
    // (packedDone with BASE_FETCH in its generation), or knows it as one id (baseUniform); the layer applied, the section
    // is the host's from then on (never the tile's stand-in).
    static constexpr uint32_t BASE_FETCH = 0x80000000u;
    // (the self-baking cache, gencache.hpp) a generated section packed on the GPU for it (VoxGrid::bakeGpu): BAKE in the
    // generation; the generator's fingerprint (its program, ingest table, map, stages: atlas_gen_*), and the cache
    static constexpr uint32_t BAKE = 0x40000000u;
    void setGenFingerprint(uint64_t fp);
    class GenCache* genCache() { return genCache_.get(); }
    void takeBaseWanted(std::vector<Key3>& out);
    void takeGridMade(Made& out);
    void baseUniform(const Key3& k, uint16_t id, Made& made);
    bool tryBaseUniform(const Key3& k, uint16_t id, Made& made);
    // 0: no layer; 1: a layer whose section the host holds (a GPU base must not replace it); 2: a layer waiting for a base
    int layerNeed(const Key3& k);
    std::atomic<size_t> layerCount{0};  // (none: no lookups on the hot paths)
    std::atomic<size_t> baseWantedN{0};
    // the cold tier's directory (empty: none) and the world's identity, the budgets (bytes; 0: the defaults)
    // (made: the sections held already that the layers loaded from disk changed, for the grid)
    void configureStore(const std::string& dir, uint64_t seed, uint32_t genVersion, uint64_t warmBytes, uint64_t coldBytes,
                        std::vector<std::pair<Key3, std::shared_ptr<const Section>>>* made = nullptr);
    struct StoreStats {
        size_t raw = 0, packQueue = 0;
        uint64_t rawBytes = 0, packed = 0;
    };
    StoreStats storeStats(void* tiers) const;  // (tiers: a SectionStore::Stats to fill, or null)
    StoreStats storeStatsLocked(void* tiers) const;  // (caller holds mu_)
    // The GPU's level-0 grid (voxgrid.hpp) owns level 0 (ATLAS_VOXGRID_LEVEL0=1): a section it holds (inside its window)
    // no longer rebuilds its level-0 region on the CPU (the grid is rebuilt on the GPU; the region stays as it was, the
    // fallback past the grid). setGridWindow (the grid's window moved: x, z origin and size in sections) rebuilds the
    // regions skipped so whose sections the window has left. Levels 1-4 are rebuilt as before.
    std::atomic<bool> gridOwnsLevel0{false};
    // The grid's coarse levels (voxgrid.hpp) own levels 1..gridCoarse: their real blocks are built on the GPU, the CPU
    // builds those levels' regions from the tiles alone (the fallback where the grid has no data), and a section no
    // longer rebuilds them, nor is digested for them (the coarse cubes: only the levels above gridCoarse)
    std::atomic<int> gridCoarse{0};
    // the grid owns every level's real blocks (level 0 and the coarse levels up to the coarsest that reads sections,
    // 4, or the world's): no CPU stage per section at all (no generated levels, no region rebuilt; level 0's regions,
    // and every level's, are the tiles' fallback where the grid has no data)
    bool gridOwnsReal() const {
        int c = gridCoarse.load(std::memory_order_relaxed);
        return gridOwnsLevel0.load(std::memory_order_relaxed) && c >= std::min(4, lodCount - 1) && c >= coarseTop();
    }
    void setGridWindow(int ox, int oz, int d);  // (no lock: the skipped regions are looked at by the next update)
    // sections inside the grid's window it does not hold (its pool full of nearer ones: VoxGrid::takeReleased): their
    // level-0 regions rebuilt here, and their uploads no longer skipped, until the window moves on
    void gridReleased(const std::vector<Key3>& secs);
    // sectionsIn, or false without waiting when an update holds the world's lock (the render thread's grid)
    bool trySectionsIn(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, SectionSrc>>& out);
    size_t gridSkippedCount() const;
    // the palette's material classes (for the sections' generated levels); set when the palette changes
    void setClasses(std::vector<uint8_t> cls);
    void markSectionDirty(int sx, int sy, int sz);  // caller holds mu_
    // (false: the section's base is not here yet: the edit shows once it comes)
    bool editBlock(int x, int y, int z, uint16_t mat);
    const Tile* tile(int lod, int tx, int tz) const;  // caller holds mu_ (or is single threaded)
    bool hasTile(int lod, int tx, int tz) const;      // locks
    // Buildings of tile (lod, tx, tz), kept across later uploads of its ground; null removes them.
    void putStructures(int lod, int tx, int tz, std::shared_ptr<const Structures> s);

    // Build what the window around `centre` needs; returns regions built. budgetMs <= 0: everything, here (offline,
    // tests). budgetMs > 0 (the game): the regions are built by a pool of builder threads of the world's own (low
    // priority), the update only collects what they finished and hands them what is wanted now, in order, and returns
    // within about its budget: a region's build (a level-1 tree of real content: tens of seconds) never holds it up.
    // the camera's view (atlas_render, every frame: its forward direction, horizontal): what lies behind it is built
    // after what it sees
    void setView(float fx, float fz) {
        viewX_ = fx;
        viewZ_ = fz;
    }
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
    GroupStats groupStatsLocked() const;  // (caller holds mu_)

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
    CoarseStats coarseStatsLocked() const;  // (caller holds mu_)
    // atlas_info's reads of the world in one hold of mu_ (the store's tiers, the grid-skipped regions, with `groups` the
    // groups' and the coarse levels' numbers): false, nothing written, when the lock is busy. Never waits: the per-tick
    // caller on the render thread must not sit behind an update's commit or a spill slice.
    bool tryInfo(StoreStats& ws, void* tiers, size_t& skipped, bool groups, GroupStats& g, CoarseStats& c) const;
    // (tests) holds mu_ until release is set, as an update's commit or a spill slice does for a while
    void holdLockForTest(const std::atomic<bool>& release, std::atomic<bool>& held) const;
    void sectionReadBarrierForTest(const std::atomic<bool>* release, std::atomic<bool>* held);

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
    uint64_t sectionRevision_ = 0;
    const std::atomic<bool>* readReleaseForTest_ = nullptr;
    std::atomic<bool>* readHeldForTest_ = nullptr;
    // immutable once published: builders hold snapshots while uploads replace entries (copy on write)
    std::unordered_map<Key3, std::shared_ptr<const Tile>, Key3Hash> tiles_;
    std::unordered_map<Key3, std::shared_ptr<const Structures>, Key3Hash> structures_;
    std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash> sections_;
    // (the tiers) per raw section its generation and flags; the raw sections to pack; the warm and cold tiers
    struct RawMeta {
        uint32_t gen = 0;
        uint8_t flags = 0;
    };
    std::unordered_map<Key3, RawMeta, Key3Hash> rawMeta_;
    std::vector<std::pair<Key3, uint32_t>> packQueue_;
    uint32_t gen_ = 0;
    uint64_t packedCount_ = 0;
    std::unique_ptr<SectionStore> store_;
    std::unique_ptr<class GenCache> genCache_;
    std::string storeRoot_;           // (the cold tier's directory for this world: the gen cache's root)
    uint64_t genFp_ = 0;
    std::vector<Key3> baseWanted_;                  // (edit layers waiting for a base the grid has: asked once)
    std::unordered_set<Key3, Key3Hash> baseAsked_;
    // caller holds mu_: a base (regenerated, uploaded, fetched) and the section's layer: s becomes base + edits (flags
    // EDITED), or the old section when the base's hash differs (BASE_LOST); non-null: a whole copy that stays instead
    std::shared_ptr<const Section> layerOver(const Key3& k, Section& s, uint8_t& flags);
    void commitSection(const Key3& k, std::shared_ptr<Section> s, uint8_t flags);  // caller holds mu_
    void baseIn(const Key3& k, const uint16_t* base, Made* made);                   // caller holds mu_
    bool baseNeedsIo(const Key3& k, const uint16_t* base) const;
    Made gridMade_;
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
    std::unordered_set<Key3, Key3Hash> gridNotHeld_;  // (gridReleased; cleared when the window moves)
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
    // (budgeted updates) the builders: they take the queue's first key (mu_), snapshot it, build without the lock and
    // leave the region in done_ for the next update
    struct Built {
        Key3 key;
        std::shared_ptr<const Region> region;  // null: the build failed (out of memory): still to do
        bool wasDirty = false, wasTreeDirty = false;
        std::shared_ptr<const Region> keep;  // a tree-only job's region (replaced meanwhile: dropped)
    };
    std::vector<std::thread> builders_;
    std::condition_variable buildCv_;
    std::deque<std::pair<Key3, bool>> queue_;      // (mu_) region, with its tree
    std::unordered_set<Key3, Key3Hash> inFlight_;  // (mu_)
    std::vector<Built> done_;                      // (mu_)
    std::atomic<bool> stopBuilders_{false};        // (set under mu_; the packer reads it too)
    void builder();
    bool coveredByFiner(const Key3& k, const int centreRegion[MAX_LODS][2]) const;  // its extent inside lod - 1's window
    // (budgeted updates) the packer thread: the scene packed from the latest centre it was handed, off the update
    std::thread packerThread_;
    // the warm tier's spill to disk, in slices with mu_ let go between them (SectionStore::drainSpills)
    std::thread spillThread_;
    std::mutex inboxMu_;  // (never held long: pack results that found mu_ busy wait here)
    std::vector<PackDone> inbox_;
    std::atomic<bool> inboxFull_{false};
public:
    std::atomic<size_t> packBacklog_{0};  // (pack results left in the inbox by packedDone's last slice)
private:
    void packedApply(const std::vector<PackDone>& done, Made* made);  // (mu_ held)
    void packedApplyOne(const PackDone& d, Made* made);                // (mu_ held)
    void drainInbox();                                                 // (mu_ held)
    std::condition_variable spillCv_;
    bool spillStop_ = false;
    void spiller();
    std::mutex packMu_;
    std::condition_variable packCv_;
    bool packWanted_ = false;                   // (packMu_)
    int packCentre_[MAX_LODS][2] = {};          // (packMu_)
    void packer();
    std::vector<std::shared_ptr<const Region>> doneRegions_;  // (update only) the collected regions, by want's index
    std::atomic<float> viewX_{0.0f}, viewZ_{0.0f};
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
