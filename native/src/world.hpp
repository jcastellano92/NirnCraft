// The far-field world: column tiles and chunk sections in, a clipmap of SVDAG regions out, packed for the kernels.
#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "gpu_layout.hpp"
#include "svdag.hpp"

namespace aether {

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

struct Tile {
    int lod = 0, tx = 0, tz = 0;
    std::vector<float> surface, water, canopy;
    std::vector<uint16_t> top, fill, canopyMat;
    // min of surface and max of the highest occupied point per aligned n x n block, n = 2^level, level 0..8
    std::array<std::vector<float>, 9> minSurf, maxTop;
    void buildPyramids();
};

struct Section {
    std::array<uint16_t, 4096> mats;  // x + 16 * (z + 16 * y)
};

struct Region {
    Key3 key;
    Svdag dag;
    bool empty() const { return dag.empty(); }
};

// Packed scene: what the kernels read.
struct PackedScene {
    std::vector<int32_t> table;
    std::vector<RegionGpu> regions;
    std::vector<uint32_t> nodes;
    std::vector<uint64_t> leaves;
    std::vector<uint32_t> runEnd;
    std::vector<uint32_t> runMat;
    LodGpu lods[MAX_LODS] = {};
    int lodCount = 0;
    uint64_t revision = 0;
};

class World {
public:
    int lodCount = 7;
    int windowRadius = 2;  // regions either side of the camera's region, per LOD
    uint16_t stoneMat = 1;
    uint16_t waterMat = 6;

    void putTile(std::unique_ptr<Tile> t);
    void putSection(int sx, int sy, int sz, const uint16_t* mats);
    void editBlock(int x, int y, int z, uint16_t mat);
    const Tile* tile(int lod, int tx, int tz) const;  // caller holds mu_ (or is single threaded)

    // Build what the window around `centre` needs; returns regions built. budgetMs <= 0: no limit.
    int update(const double centre[3], double budgetMs, double* buildMs);
    // Immutable snapshot of the packed scene; a frame holds it for as long as it reads it.
    std::shared_ptr<const PackedScene> packed() const {
        std::lock_guard<std::mutex> lock(snapMu_);
        return packed_;
    }

    static int regionRows(int lod);  // vertical regions needed to span y -64..640

    size_t regionCount() const { return regions_.size(); }
    uint64_t nodeBytes() const, leafBytes() const, materialBytes() const;

private:
    using SectionMap = std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash>;
    struct Job {
        Key3 key;
        std::shared_ptr<const Tile> tile;
        SectionMap sections;
    };
    Job snapshot(const Key3& k) const;  // caller holds mu_
    Region buildRegion(const Job& j) const;
    void repack(const int centreRegion[MAX_LODS][2]);

    mutable std::mutex mu_;
    // immutable once published: builders hold snapshots while uploads replace entries (copy on write)
    std::unordered_map<Key3, std::shared_ptr<const Tile>, Key3Hash> tiles_;
    std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash> sections_;
    std::unordered_map<Key3, std::shared_ptr<const Region>, Key3Hash> regions_;
    std::unordered_set<Key3, Key3Hash> dirty_;
    mutable std::mutex snapMu_;
    std::shared_ptr<const PackedScene> packed_ = std::make_shared<PackedScene>();
    int lastCentre_[MAX_LODS][2] = {};
    bool havePacked_ = false;
};

}  // namespace aether
