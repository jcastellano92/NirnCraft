// The host's tiers of real sections (docs/ATLAS-STATUS.md step 5, the residency manager): warm, sections packed on the
// GPU (sectionpack.hpp) in a byte budget; cold, the same words on disk in a per-world cache directory (a setting: the
// game's instance, `.minecraft/atlas-cache/<world>/`), in its own byte budget, keyed by the world's seed, the generator's
// version and each section's content hash, so coming back somewhere reloads it from disk instead of the server
// sending it again. A section the client can generate again (ATLAS_SECTION_REGENERABLE) and nobody edited is dropped,
// not kept: it is made again when wanted. Over its budget the warm tier gives up its farthest sections first (the
// residency priority's proximity: the one rule level 0's admission ranks by); the cold tier its least recently used
// regions. World holds its lock around every call (not thread-safe on its own).
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "world.hpp"  // Key3

namespace atlas {

using PackedIds = std::shared_ptr<const std::vector<uint32_t>>;

class SectionStore {
public:
    enum : uint8_t { REGEN = 1, EDITED = 2 };
    struct Stats {
        size_t warm = 0, coldRecords = 0, coldRegions = 0;
        uint64_t warmBytes = 0, warmBudget = 0, coldBytes = 0, coldBudget = 0;
        uint64_t hits = 0, misses = 0, coldHits = 0, coldWrites = 0, dropped = 0, spilled = 0, lost = 0, coldEvicted = 0;
        bool coldOn = false;
    };
    // the cold tier's directory (empty: none), the world's identity, the budgets (bytes)
    void configure(const std::string& dir, uint64_t seed, uint32_t genVersion, uint64_t warmBudget, uint64_t coldBudget);
    void put(const Key3& k, PackedIds words, uint32_t hash, uint8_t flags);
    PackedIds get(const Key3& k);  // warm, else cold (then warm again); null: none
    PackedIds peek(const Key3& k) const {  // (warm only, not counted)
        auto it = warm_.find(k);
        return it == warm_.end() ? nullptr : it->second.words;
    }
    void erase(const Key3& k);     // (a newer upload replaces it)
    bool has(const Key3& k) const { return warm_.count(k) != 0; }
    // every section of columns [sx0, sx1) x [sz0, sz1) the tiers hold (the cold ones read back into warm)
    void inRange(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, PackedIds>>& out);
    // over budget, or outside `keep` regions (of 256 blocks) of the camera's: the farthest go (cold, dropped)
    void enforce(int camSx, int camSz, int keepRegions);
    Stats stats() const;
    uint64_t warmBytes() const { return warmBytes_; }

private:
    struct Rec {
        PackedIds words;
        uint32_t hash = 0;
        uint8_t flags = 0;
        bool onDisk = false;  // (its words as they are on disk already)
    };
    std::unordered_map<Key3, Rec, Key3Hash> warm_;
    uint64_t warmBytes_ = 0, warmBudget_ = 256ull << 20;
    // the cold tier: a file a region of 16 x 16 section columns, records appended (the newest of a section wins)
    struct Loc {
        uint64_t offset = 0;
        uint32_t words = 0, hash = 0;
        uint8_t flags = 0;
    };
    struct ColdRegion {
        bool scanned = false;
        std::unordered_map<Key3, Loc, Key3Hash> recs;
        uint64_t fileBytes = 0, liveBytes = 0, lastUse = 0;
    };
    std::string dir_;
    uint64_t coldBudget_ = 2ull << 30, coldBytes_ = 0, clock_ = 0;
    // the kept regions (enforce's): the cold tier is read back only inside them (what warm would let go again at once)
    int keepRx_ = 0, keepRz_ = 0, keep_ = -1;
    std::unordered_map<uint64_t, ColdRegion> cold_;
    bool coldScannedAll_ = false;
    Stats st_;
    static uint64_t regionKey(int sx, int sz);
    std::string regionPath(uint64_t rk) const;
    ColdRegion& coldRegion(uint64_t rk);       // (scanned from its file on first use)
    bool writeCold(const Key3& k, const Rec& r);
    PackedIds readCold(const Key3& k);
    void compact(uint64_t rk, ColdRegion& cr);
    void coldBudget();
    void drop(std::unordered_map<Key3, Rec, Key3Hash>::iterator it);  // warm out: cold, dropped or lost
};

}  // namespace atlas
