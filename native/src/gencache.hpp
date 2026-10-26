// The self-baking cache (docs/ATLAS-WORLD-STORE.md section 1): what the client's generator made, kept on disk per world
// and per generator, so a known chunk streams from disk instead of being generated again.
//
// Keyed by the world's seed (the store's directory), the generator's fingerprint (its program, ingest table, map and
// stages: gen-<fingerprint>/; another generator is another directory, so a change means generating again) and the
// position. Per region of 32 x 32 chunks one file, r.<rx>.<rz>.gch, records appended: a chunk's header (atlas_gen_run's
// info row: its lowest ground, highest top, biome, and per section its hash and kind) and its sections with blocks
// (packed on the GPU by the grid's pack kernel, VoxGrid::bakeGpu; compressed here, sectioncodec.hpp). A chunk is known
// when its header and every section of kind "blocks" are on disk. Air and deep rock need nothing.
//
// Writes go through a thread of the cache's own (the compression and the file I/O: never the render thread's); reads
// are the generator's threads' (decoded there). Thread-safe.
#pragma once
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "world.hpp"  // Key3

namespace atlas {

class GenCache {
public:
    static constexpr uint32_t INFO_WORDS = 4 + 40 * 4;  // (atlas_gen_run's info row)
    static constexpr uint32_t KIND_AIR = 0, KIND_BLOCKS = 1, KIND_ROCK = 2;
    struct Stats {
        bool on = false;
        uint64_t fingerprint = 0;
        uint64_t chunks = 0, sections = 0, fileBytes = 0, queued = 0;
        uint64_t chunkWrites = 0, sectionWrites = 0, chunkHits = 0, chunkMisses = 0, sectionReads = 0;
        uint64_t farWrites = 0, farHits = 0, farMisses = 0;
        uint64_t hashErrors = 0;  // (records whose ids did not match their hash: refused, generated again)
        uint64_t chunkIncomplete = 0;  // (misses of a chunk whose header is there, a section of it not yet)
        double encodeMs = 0, decodeMs = 0;
    };
    GenCache();
    ~GenCache();
    // root: the world's store directory (empty: off); the generator's fingerprint (0: none yet: off)
    void configure(const std::string& root, uint64_t fingerprint);
    bool on() const { return on_.load(std::memory_order_relaxed); }
    // (written by the cache's thread) a chunk's header; a section packed on the GPU (sectionpack.hpp's words)
    void putChunk(int cx, int cz, const uint32_t* info);
    // (hash: the GPU pack's content hash, sectionpack.hpp sectionHash: every read checks the decoded ids against it)
    void putSection(const Key3& k, std::shared_ptr<const std::vector<uint32_t>> words, uint32_t hash);
    // a known chunk's header (true: it and all its sections with blocks are on disk)
    bool chunk(int cx, int cz, uint32_t* info);
    bool hasChunk(int cx, int cz);  // (its header, whether complete or not)
    bool complete(int cx, int cz);  // (its header and every section with blocks: from the index alone)
    // a chunk made again (its stages' data changed): its header and sections no longer known, and a tombstone on disk
    // (a later scan drops what came before it; the new ones, written after, are kept). Only a chunk written this
    // session: one from disk was made with its stages' data (the generator bakes nothing where data is still to come,
    // atlas_gen_set_no_bake), and forgetting it made every relaunch generate a settlement again. Returns whether.
    bool forget(int cx, int cz);
    // a section's packed words from disk (null: none)
    std::shared_ptr<const std::vector<uint32_t>> section(const Key3& k);
    // The far terrain (the grid's coarse levels, atlas_gen_far_ingest): a level-lod tile's chunk kinds (16 x 16 columns x
    // rows, as atlas_gen_far's: 0 mixed, 1 air, 2 | material << 8 one id) and its mixed chunks (packed on the GPU,
    // Key3{lod, cx, cy, cz}); a tile is known when its kinds and every mixed chunk are on disk. In f<lod>.<rx>.<rz>.gch,
    // regions of 8 x 8 tiles.
    // standIn: the coarse generator's own terrain (the far path's placeholder: the default, and every record already on disk);
    // false: true data, downsampled from level-0 chunks of the exact generator, which replaces a stand-in on disk (a later
    // record of the same tile or chunk wins when it is true; a stand-in never replaces a true one) and in the grid
    void putFarTile(int lod, int tx, int tz, const std::vector<uint32_t>& kinds, bool standIn = true);
    void putCoarse(const Key3& k, std::shared_ptr<const std::vector<uint32_t>> words, uint32_t hash, bool standIn = true);
    bool farTile(int lod, int tx, int tz, std::vector<uint32_t>& kinds);  // (true: known, whole)
    bool farKnown(int lod, int tx, int tz);                              // (the index alone)
    // A tile made again (the idle verifier found it differing from the generator's): its kinds, its coarse chunks and their
    // count no longer known at once, and a tombstone record on disk (a later scan drops what came before it), so that the
    // records written after it are the tile's, in the file's order.
    void forgetFarTile(int lod, int tx, int tz);
    bool farStandIn(int lod, int tx, int tz);                            // (known, and still the placeholder: not true data)
    std::shared_ptr<const std::vector<uint32_t>> coarse(const Key3& k);
    // Never blocks (the render thread's per-tick atlas_info reads it): the numbers as the last caller that got the lock
    // saw them when the lock is busy (the writer's index updates, a scan), else fresh.
    Stats stats();
    void flush();  // (waits for the queue: tests, shutdown)
    // (tests) holds mu_ until release is set, as a thread of the writer would while it scans a region file
    void holdLockForTest(const std::atomic<bool>& release, std::atomic<bool>& held);

private:
    struct Loc {
        uint64_t offset = 0;
        uint32_t bytes = 0;
    };
    struct Region {
        bool scanned = false;
        bool scanning = false;  // (a thread reads the file's index now, mu_ let go: the others wait on scanCv_)
        uint64_t fileBytes = 0;
        struct Chunk {
            Loc loc;
            uint64_t blocks = 0;  // (its sections with blocks: bit s, section y s - 4)
        };
        std::unordered_map<uint64_t, Chunk> chunks;  // (cx, cz) packed
        std::unordered_map<Key3, Loc, Key3Hash> secs;
        std::shared_ptr<std::vector<uint8_t>> data;  // (the file as first read: the reads' copy, a budget of them)
        uint64_t dataUse = 0;
        // (a far region) its tiles (tx, tz) with their mixed chunks' count, its coarse chunks, the baked ones a tile
        struct Tile {
            Loc loc;
            uint32_t mixed = 0;
            bool trueData = false;  // (bit 31 of the record's word 1: downsampled from level 0; clear: a stand-in)
        };
        std::unordered_map<uint64_t, Tile> tiles;
        std::unordered_map<Key3, Loc, Key3Hash> coarse;
        std::unordered_set<Key3, Key3Hash> coarseTrue;  // (the coarse chunks whose record is true data)
        std::unordered_map<uint64_t, uint32_t> baked;
    };
    struct Job {
        int type = 1;  // 0 chunk, 1 section, 2 far tile, 3 coarse chunk, 4 a chunk's tombstone, 5 a far tile's tombstone
        bool isChunk = false;
        Key3 k;
        std::vector<uint32_t> info;
        std::shared_ptr<const std::vector<uint32_t>> words;
        uint32_t hash = 0;
        bool standIn = true;
    };
    // The index and the queue. Never held across file I/O (a region's scan, a record's append, a region's whole read): a
    // write of the writer's thread that Windows stretched to seconds held every thread that asked the cache anything,
    // the render thread's atlas_info and its packedDone among them.
    std::mutex mu_;
    std::condition_variable cv_, idle_, scanCv_;
    int scanning_ = 0;  // (regions being scanned now: configure waits for none)
    Stats snap_;        // (stats(): what the last caller that got mu_ saw)
    std::mutex snapMu_;
    std::deque<Job> queue_;
    bool stop_ = false, busy_ = false;
    std::thread writer_;
    std::atomic<bool> on_{false};
    std::string dir_;
    uint64_t fp_ = 0;
    std::unordered_map<uint64_t, Region> regions_;
    uint64_t dataBytes_ = 0, dataClock_ = 0;
    static constexpr uint64_t DATA_BUDGET = 64ull << 20;  // (the regions' in-memory copies, least recently read out)
    Stats st_;
    static uint64_t regionOf(int cx, int cz);
    static uint64_t farRegionOf(int lod, int tx, int tz) {  // (lod in the top byte: never a near region's)
        auto d = [](int a) { return a >= 0 ? a / 8 : -((-a + 7) / 8); };
        return uint64_t(0x80u | uint32_t(lod)) << 56 | uint64_t(uint32_t(d(tx)) & 0xFFFFFFu) << 28 | (uint32_t(d(tz)) & 0xFFFFFFFu);
    }
    static uint64_t chunkKey(int cx, int cz) { return uint64_t(uint32_t(cx)) << 32 | uint32_t(cz); }
    std::unordered_set<uint64_t> fresh_;  // (chunks whose header this session wrote: chunkKey)
    std::vector<std::filesystem::path> oldSets_;  // (other sets in the root: deleted at this one's first write)
    void dropOldSets();  // (caller holds mu_)
    void eraseFarTile(Region& r, int lod, int tx, int tz);  // (caller holds mu_)
    std::string regionPath(uint64_t rk) const;
    // (caller holds l on mu_) the region, its file's index read on first use: l is let go while the file is read, so
    // anything may have changed on return; the reference stays (nodes of regions_ do not move; configure waits for scans)
    Region& region(std::unique_lock<std::mutex>& l, uint64_t rk);
    void scanFile(const std::string& path, Region& r);  // (no lock: fills a region that no other thread touches)
    void writerLoop();
    bool readBytes(uint64_t rk, const Loc& l, std::vector<uint8_t>& out);
};

}  // namespace atlas
