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
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
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
        double encodeMs = 0, decodeMs = 0;
    };
    GenCache();
    ~GenCache();
    // root: the world's store directory (empty: off); the generator's fingerprint (0: none yet: off)
    void configure(const std::string& root, uint64_t fingerprint);
    bool on() const { return on_.load(std::memory_order_relaxed); }
    // (written by the cache's thread) a chunk's header; a section packed on the GPU (sectionpack.hpp's words)
    void putChunk(int cx, int cz, const uint32_t* info);
    void putSection(const Key3& k, std::shared_ptr<const std::vector<uint32_t>> words);
    // a known chunk's header (true: it and all its sections with blocks are on disk)
    bool chunk(int cx, int cz, uint32_t* info);
    bool hasChunk(int cx, int cz);  // (its header, whether complete or not)
    bool complete(int cx, int cz);  // (its header and every section with blocks: from the index alone)
    // a section's packed words from disk (null: none)
    std::shared_ptr<const std::vector<uint32_t>> section(const Key3& k);
    Stats stats();
    void flush();  // (waits for the queue: tests, shutdown)

private:
    struct Loc {
        uint64_t offset = 0;
        uint32_t bytes = 0;
    };
    struct Region {
        bool scanned = false;
        uint64_t fileBytes = 0;
        struct Chunk {
            Loc loc;
            uint64_t blocks = 0;  // (its sections with blocks: bit s, section y s - 4)
        };
        std::unordered_map<uint64_t, Chunk> chunks;  // (cx, cz) packed
        std::unordered_map<Key3, Loc, Key3Hash> secs;
        std::shared_ptr<std::vector<uint8_t>> data;  // (the file as last read: the reads' copy)
    };
    struct Job {
        bool isChunk = false;
        Key3 k;
        std::vector<uint32_t> info;
        std::shared_ptr<const std::vector<uint32_t>> words;
    };
    std::mutex mu_;  // the index, the queue, the files' appends
    std::condition_variable cv_, idle_;
    std::deque<Job> queue_;
    bool stop_ = false, busy_ = false;
    std::thread writer_;
    std::atomic<bool> on_{false};
    std::string dir_;
    uint64_t fp_ = 0;
    std::unordered_map<uint64_t, Region> regions_;
    Stats st_;
    static uint64_t regionOf(int cx, int cz);
    static uint64_t chunkKey(int cx, int cz) { return uint64_t(uint32_t(cx)) << 32 | uint32_t(cz); }
    std::string regionPath(uint64_t rk) const;
    Region& region(uint64_t rk);  // (caller holds mu_; scanned on first use)
    void writerLoop();
    bool readBytes(uint64_t rk, const Loc& l, std::vector<uint8_t>& out);
};

}  // namespace atlas
