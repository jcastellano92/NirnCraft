// The level-0 grid: real blocks traced straight from the chunk sections, built on the GPU (docs/ATLAS-RENDERER-PLAN.md
// "GPU-first"; after NAADF, Ulschmid et al. 2026: nested 16^3 chunks of 4^3 blocks of 4^3 voxels, axis-aligned
// distance fields in the empty cells).
//
// A dense window of D x H x D sections (chunks) around the camera, every one a 32-bit word:
//   bits 0-1  state: VG_EMPTY (air), VG_MIXED (has blocks), VG_UNLOADED (no data: the SVDAG levels answer there)
//   EMPTY:    bits 2-31 six 5-bit extents (-x, +x, -y, +y, -z, +z): an axis-aligned box of empty chunks around it
//   MIXED:    bits 2-31 its slot in the pools
// per slot: the section's raw ids (2048 words, two 16-bit ids each, x + 16 * (z + 16 * y): World::putSection's), and
// its 64 blocks (4^3 voxels each, block index bx + 4 * (bz + 4 * by)): a word (bit 0 set: has voxels; else bits 2-13
// six 2-bit extents of an empty box of blocks within the chunk) and a 64-bit occupancy (bit x + 4 * z + 16 * y, as the
// SVDAG's leaves). A ray step reads at most the chunk word, the block word and the bitmap: three dependent loads.
//
// The host only keeps the books (which section sits in which slot, which chunks changed) and hands batches of raw
// sections to the backend: the Vulkan backend copies them into its pool and builds bitmaps and distance fields with
// compute shaders (atlas_voxgrid.slang); the CPU backend builds the same words here (applyCpu), the reference.
#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "world.hpp"  // (Key3, Section)

namespace atlas {

class World;

constexpr uint32_t VG_EMPTY = 0, VG_MIXED = 1, VG_UNLOADED = 2;
constexpr int VG_SECTION_WORDS = 2048;  // a section's 4096 16-bit ids
constexpr int VG_MIN_SY = -4;           // y -64 (World::WORLD_MIN_Y / 16)
constexpr uint32_t VG_COLUMN = 0xFFFFFFFEu;  // (VgJob::slot)

// a section's ids: the world's own Section::mats (shared, never copied) or an upload's copy
using VgIds = std::shared_ptr<const std::array<uint16_t, 4096>>;

// One chunk to (re)write: its word (EMPTY / UNLOADED: as is; MIXED: built from the slot's ids, which the batch carries)
struct VgJob {
    uint32_t chunk;  // index in the window: (y * D + z) * D + x
    uint32_t word;   // the chunk's word (for MIXED: slot << 2 | VG_MIXED)
    uint32_t slot;   // MIXED: the slot (its ids: Batch::secs[data]); ~0u: no build; VG_COLUMN: `word` into every cell
                     // of the column whose lowest cell is `chunk` (a column the window left)
    uint32_t data;   // MIXED: index of its ids in Batch::secs
};

struct VgBatch {
    bool clear = false;          // the window moved: every chunk UNLOADED before the jobs
    int ox = 0, oz = 0;          // the window these jobs are for (origin in sections)
    bool chunkStates = false;    // some chunk's state changed: the chunk-level distance fields need a rebuild
    std::vector<VgJob> jobs;
    std::vector<VgIds> secs;  // the MIXED jobs' ids (two to a word in the pool: their own little-endian bytes)
    int sections() const { return int(secs.size()); }
};

// (abi 0.16) The near field's textures as the kernels read them (atlas_far.slang vgTexture / texSample): word 0 the
// table's length N, words 1..N per material its texture's slot + 1 (0: none), then from word 1 + N each slot's six faces
// (down, up, north, south, west, east) as mip chains of 16, 8, 4, 2, 1 texels a side (341 words a face, ARGB sRGB,
// alpha the share of texels covered, colour averaged over the covered ones).
constexpr uint32_t TEX_FACE_WORDS = 341;
struct TextureSet;
void packTextures(const TextureSet& t, std::vector<uint32_t>& out);

class VoxGrid {
public:
    VoxGrid();
    int D = 64, H = 44;  // window in sections (ATLAS_VOXGRID_D); H spans y -64..640
    uint32_t slotCap = 16384;  // pool slots (ATLAS_VOXGRID_SLOTS): 8 KiB of ids + 768 B of blocks each
    bool enabled = true;  // ATLAS_VOXGRID=0: off (the SVDAG level 0 alone)

    // a section's ids as World::putSection gets them (any thread)
    void put(int sx, int sy, int sz, const uint16_t* mats);
    // render thread, once a frame: the window follows the camera's block (FOLLOW sections behind; the columns it enters
    // are read from the world when its lock is free); refill: the same read on the update thread (atlas_update), waiting
    void frame(const int32_t camBlock[3], World& world);
    void refill(World& world);
    static constexpr int FOLLOW = 4;
    // the sections the grid gave up or could not take while they lie in its window (the pool full: the farthest go
    // first): their level 0 is the CPU's again (World::gridReleased, on the update thread)
    std::vector<Key3> takeReleased();
    struct Stats {
        uint64_t frames = 0, jumps = 0, shifts = 0, refills = 0, evicted = 0, overflow = 0;
        uint64_t lastShiftFrame = 0, lastRefillFrame = 0, settledFrame = 0, lastSettleFrames = 0;
        int ox = 0, oz = 0, camSx = 0, camSz = 0;
        size_t pending = 0;
        uint32_t freshColumns = 0, slots = 0;
    };
    Stats stats() const;
    // the next batch, at most maxSections MIXED sections (nearest the camera first); empty when nothing is pending
    VgBatch take(int maxSections);
    // the frame's view of it (FrameGpu::vgOrigin / vgDims)
    bool active() const { return enabled && centred_; }
    void origin(int32_t o[4]) const;
    size_t pending() const;

    // ---- the CPU reference (the CPU backend traces these; the tests compare the GPU's against them)
    std::vector<uint32_t> chunks, blocks, pool;
    std::vector<uint64_t> bits;
    int cpuOx = 0, cpuOz = 0;  // the window the CPU arrays hold
    void applyCpu(const VgBatch& b);
    // the chunk-level distance fields over the whole window from the chunk words (CPU; the GPU's vgGrow does the same)
    void growChunksCpu();
    size_t verifyCpu() const;  // (ATLAS_VOXGRID_VERIFY) every box empty, every bitmap and block word right: the error count

    // stats (atlas_info "voxgrid")
    uint32_t slotsUsed() const { return slotsUsed_; }
    uint64_t sectionsBuilt = 0, batches = 0;

    // one empty cell's box in a grid of n^3 / nx x ny x nz cells: the extents it may grow to, the cells' occupancy test
    // (same order and rule as the GPU's: round robin over -x, +x, -y, +y, -z, +z, one layer at a time)
    template <class Occupied>
    static uint32_t growBox(int x, int y, int z, int nx, int ny, int nz, int maxExt, int bitsPer, Occupied occ);

private:
    struct Key {
        int x, y, z;
        bool operator==(const Key&) const = default;
    };
    struct KeyHash {
        size_t operator()(const Key& k) const {
            uint64_t h = uint64_t(uint32_t(k.x)) * 0x9E3779B97F4A7C15ull ^ uint64_t(uint32_t(k.z)) * 0xC2B2AE3D27D4EB4Full ^
                         uint64_t(uint32_t(k.y)) * 0x165667B19E3779F9ull;
            return size_t(h ^ (h >> 31));
        }
    };
    mutable std::mutex mu_;
    bool centred_ = false;
    int ox_ = 0, oz_ = 0;  // window origin in sections (x, z); y is VG_MIN_SY
    // sections waiting for a batch: the world's own (shared, never copied) or an upload's copy
    using Ids = VgIds;
    std::unordered_map<Key, Ids, KeyHash> pending_;
    bool clearPending_ = false;
    // per cell (in memory: chunk (x, y, z) at ((y - VG_MIN_SY) * D + z mod D) * D + x mod D)
    std::vector<uint32_t> slotOf_;      // its slot or ~0u
    std::vector<uint8_t> state_;        // the state its last job gave it
    std::vector<uint32_t> vacated_;     // columns the window left (their lowest cell): UNLOADED with the next batch
    std::vector<uint32_t> jobAt_;       // (take) per cell its job in the batch being made, ~0u none
    // per column (z mod D) * D + x mod D: highest section y with data (INT16_MIN: none); entered, the world's
    // sections not read yet (freshColumns_ of them)
    std::vector<int16_t> colTop_;
    std::vector<uint8_t> colFresh_;
    uint32_t freshColumns_ = 0;
    std::vector<uint32_t> slotChunk_;   // per slot: its cell or ~0u
    std::vector<uint32_t> freeSlots_;
    uint32_t nextSlot_ = 0, slotsUsed_ = 0;
    int camSx_ = 0, camSz_ = 0;
    std::vector<Key3> released_;
    Stats stats_;
    void shift(int nox, int noz, World& world);    // (mu_ held)
    void refillLocked(World& world, bool wait);    // (mu_ held)
    static int pmod(int a, int d) { return ((a % d) + d) % d; }
    bool inWindow(int sx, int sy, int sz) const {
        return sx >= ox_ && sx < ox_ + D && sz >= oz_ && sz < oz_ + D && sy >= VG_MIN_SY && sy < VG_MIN_SY + H;
    }
    size_t physCol(int sx, int sz) const { return size_t(pmod(sz, D)) * D + size_t(pmod(sx, D)); }
    uint32_t phys(int sx, int sy, int sz) const { return uint32_t(size_t(sy - VG_MIN_SY) * D * D + physCol(sx, sz)); }
};

template <class Occupied>
uint32_t VoxGrid::growBox(int x, int y, int z, int nx, int ny, int nz, int maxExt, int bitsPer, Occupied occ) {
    int e[6] = {0, 0, 0, 0, 0, 0};
    bool done[6] = {false, false, false, false, false, false};
    for (int round = 0; round < maxExt; round++) {
        bool any = false;
        for (int d = 0; d < 6; d++) {
            if (done[d]) continue;
            int lo[3] = {x - e[0], y - e[2], z - e[4]}, hi[3] = {x + e[1], y + e[3], z + e[5]};  // inclusive
            int ax = d >> 1;
            bool pos = (d & 1) != 0;
            int n[3] = {nx, ny, nz};
            int layer = pos ? hi[ax] + 1 : lo[ax] - 1;
            if (e[d] >= maxExt || layer < 0 || layer >= n[ax]) {
                done[d] = true;
                continue;
            }
            lo[ax] = hi[ax] = layer;
            if (occ(lo, hi)) {
                done[d] = true;
                continue;
            }
            e[d]++;
            any = true;
        }
        if (!any) break;
    }
    uint32_t w = 0;
    for (int d = 0; d < 6; d++) w |= uint32_t(e[d]) << (bitsPer * d);
    return w;
}

}  // namespace atlas
