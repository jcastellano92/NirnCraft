// The host's tiers of real sections (docs/ATLAS-STATUS.md step 5, the residency manager): warm, sections packed on the
// GPU (sectionpack.hpp) in a byte budget; cold, on disk in a per-world cache directory (a setting: the game's instance,
// `.minecraft/atlas-cache/<world>/`), compressed (sectioncodec.hpp), in its own byte budget, so coming back somewhere
// reloads it from disk instead of the server sending it again. A section the client can generate again
// (ATLAS_SECTION_REGENERABLE) and nobody edited is dropped, not kept: it is made again when wanted.
//
// Edits of a generated section are kept as differences from it (an EditLayer: the voxels changed and their new ids),
// keyed by the section's position and its generated base's content hash (seed: the directory; the generator's version:
// each record's). Reloading it is the base generated again (on the GPU, or the client's upload) with the layer applied.
// A layer whose base was one id throughout (air above the ground, a builder's tower) needs no generation: it is whole
// on its own. A rewrite whose delta would be bigger than the whole section is stored whole instead. Each delta keeps the
// edited section whole beside it, compressed, as a fallback: when a generator change makes the base's hash differ the
// delta cannot apply, the old section is kept and flagged (BASE_LOST).
//
// Over its budget the warm tier gives up its farthest sections first (the residency priority's proximity: the one rule
// level 0's admission ranks by); the cold tier its least recently used regions (whole sections only: edit layers are
// small and never evicted). World holds its lock around every call (not thread-safe on its own).
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "world.hpp"  // Key3

namespace atlas {

using PackedIds = std::shared_ptr<const std::vector<uint32_t>>;

// an edited generated section: the base it was made on and the voxels changed since
struct EditLayer {
    static constexpr uint32_t MIXED = ~0u;
    uint32_t baseHash = 0;      // the generated base's content hash (sectionHash)
    uint32_t baseUni = MIXED;   // the base's one id when it was one id throughout (the layer is whole on its own)
    bool baseKnown = false;     // (false: edited before its base came; applied once it does)
    bool replaced = false;      // whole elsewhere (a rewrite, a server's section): a regenerated base never replaces it
    bool baseLost = false;      // the regenerated base differs (a generator change): the old section is kept whole
    bool dirty = true;          // changed since its record was written
    std::vector<uint32_t> edits;  // sorted: voxel index << 16 | id
    uint64_t fbOffset = 0;      // the fallback (the whole section, compressed) in its region's .dlt file
    uint32_t fbBytes = 0;
    uint32_t recBytes = 0;      // (its last record's size: the dead bytes once written again)
    void set(uint32_t voxel, uint16_t id);
    void apply(uint16_t* ids) const {
        for (uint32_t e : edits) ids[e >> 16] = uint16_t(e & 0xFFFFu);
    }
    bool selfContained() const { return baseKnown && baseUni != MIXED && !replaced && !baseLost; }
};
using LayerPtr = std::shared_ptr<EditLayer>;

class SectionStore {
public:
    enum : uint8_t { REGEN = 1, EDITED = 2, BASE_LOST = 4 };
    struct Stats {
        size_t warm = 0, coldRecords = 0, coldRegions = 0, layers = 0, layersWaiting = 0;
        uint64_t warmBytes = 0, warmBudget = 0, coldBytes = 0, coldBudget = 0, deltaBytes = 0;
        uint64_t hits = 0, misses = 0, coldHits = 0, coldWrites = 0, dropped = 0, spilled = 0, lost = 0, coldEvicted = 0;
        // (the edit layers) records written: deltas, whole instead (a rewrite), their bytes as written and as the
        // uncompressed whole-section records before them would have been; bases applied, mismatched, encode time
        uint64_t deltaWrites = 0, rewrites = 0, writtenBytes = 0, wholeEquivBytes = 0, baseApplied = 0, baseMismatch = 0;
        double codecMs = 0;
        bool coldOn = false;
    };
    ~SectionStore();
    // the cold tier's directory (empty: none), the world's identity, the budgets (bytes); the edit layers on disk loaded
    void configure(const std::string& dir, uint64_t seed, uint32_t genVersion, uint64_t warmBudget, uint64_t coldBudget);
    void put(const Key3& k, PackedIds words, uint32_t hash, uint8_t flags);
    PackedIds get(const Key3& k);  // warm, else cold, else a self-contained layer (then warm again); null: none
    PackedIds peek(const Key3& k) const {  // (warm only, not counted)
        auto it = warm_.find(k);
        return it == warm_.end() ? nullptr : it->second.words;
    }
    // a section's hash and flags (warm, or the cold tier's whole record); false: neither holds it
    bool meta(const Key3& k, uint32_t& hash, uint8_t& flags);
    void erase(const Key3& k);     // (a newer upload replaces it)
    bool has(const Key3& k) const { return warm_.count(k) != 0; }
    // every section of columns [sx0, sx1) x [sz0, sz1) the tiers hold (the cold ones read back into warm)
    void inRange(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, PackedIds>>& out);
    // over budget, or outside `keep` regions (of 256 blocks) of the camera's: the farthest go (cold, dropped)
    void enforce(int camSx, int camSz, int keepRegions);
    Stats stats() const;
    uint64_t warmBytes() const { return warmBytes_; }

    // the edit layers (all in memory: small; on disk in the region's .dlt file once their section leaves warm)
    LayerPtr layer(const Key3& k) const {
        auto it = layers_.find(k);
        return it == layers_.end() ? nullptr : it->second;
    }
    LayerPtr makeLayer(const Key3& k);
    size_t layerCount() const { return layers_.size(); }
    template <class F> void forEachLayer(F&& f) const {
        for (auto& [k, l] : layers_) f(k, *l);
    }
    // the whole section a delta keeps beside it (a base mismatch's fallback); null: none
    PackedIds fallback(const Key3& k);
    void countBase(bool applied) { (applied ? st_.baseApplied : st_.baseMismatch)++; }
    // a layer's record now (its section's words: the fallback); every dirty one at shutdown
    void writeLayer(const Key3& k, const PackedIds& words);
    void flush();

private:
    struct Rec {
        PackedIds words;
        uint32_t hash = 0;
        uint8_t flags = 0;
        bool onDisk = false;  // (its words as they are on disk already)
    };
    std::unordered_map<Key3, Rec, Key3Hash> warm_;
    uint64_t warmBytes_ = 0, warmBudget_ = 256ull << 20;
    // the cold tier: a file a region of 16 x 16 section columns, records appended (the newest of a section wins); whole
    // sections in r.<x>.<z>.sec (an LRU budget), edit layers in r.<x>.<z>.dlt (never evicted)
    struct Loc {
        uint64_t offset = 0;  // (the payload's)
        uint32_t bytes = 0, hash = 0;
        uint8_t flags = 0;
    };
    struct ColdRegion {
        bool scanned = false;
        std::unordered_map<Key3, Loc, Key3Hash> recs;
        uint64_t fileBytes = 0, liveBytes = 0, lastUse = 0;
    };
    std::string dir_;
    uint32_t genVersion_ = 0;
    uint64_t coldBudget_ = 2ull << 30, coldBytes_ = 0, clock_ = 0, deltaBytes_ = 0;
    // the kept regions (enforce's): the cold tier is read back only inside them (what warm would let go again at once)
    int keepRx_ = 0, keepRz_ = 0, keep_ = -1;
    std::unordered_map<uint64_t, ColdRegion> cold_;
    std::unordered_map<Key3, LayerPtr, Key3Hash> layers_;
    std::unordered_map<uint64_t, uint64_t> dltBytes_;  // (a region's .dlt file length, its live bytes: compaction)
    std::unordered_map<uint64_t, uint64_t> dltLive_;
    Stats st_;
    static uint64_t regionKey(int sx, int sz);
    std::string regionPath(uint64_t rk, const char* ext = "sec") const;
    ColdRegion& coldRegion(uint64_t rk);       // (scanned from its file on first use)
    bool writeCold(const Key3& k, const Rec& r);
    PackedIds readCold(const Key3& k);
    void compact(uint64_t rk, ColdRegion& cr);
    void coldBudget();
    void loadLayers();
    void compactLayers(uint64_t rk);
    void drop(std::unordered_map<Key3, Rec, Key3Hash>::iterator it);  // warm out: cold, dropped or lost
};

}  // namespace atlas
