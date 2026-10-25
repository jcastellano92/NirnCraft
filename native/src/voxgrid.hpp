// The level-0 grid: real blocks traced straight from the chunk sections, built on the GPU (docs/ATLAS-RENDERER-PLAN.md
// "GPU-first"; after NAADF, Ulschmid et al. 2026: nested 16^3 chunks of 4^3 blocks of 4^3 voxels, axis-aligned
// distance fields in the empty cells).
//
// A dense window of D x H x D sections (chunks) around the camera, every one a 32-bit word:
//   bits 0-1  state: VG_EMPTY (air), VG_MIXED (has blocks), VG_UNLOADED (no data: the coarser levels answer there),
//             VG_UNIFORM (one block id throughout: rock inside a mountain, a lake's water; no slot)
//   EMPTY:    bits 2-31 six 5-bit extents (-x, +x, -y, +y, -z, +z): an axis-aligned box of empty chunks around it
//   MIXED:    bits 2-31 its slot in the pools
//   UNIFORM:  bits 2-17 the id (traced as a MIXED chunk every voxel of which is that id; bits 18-31 zero)
// per slot: the section's raw ids (2048 words, two 16-bit ids each, x + 16 * (z + 16 * y): World::putSection's), and
// its 64 blocks (4^3 voxels each, block index bx + 4 * (bz + 4 * by)): a word (bit 0 set: has voxels; else bits 2-13
// six 2-bit extents of an empty box of blocks within the chunk) and a 64-bit occupancy (bit x + 4 * z + 16 * y, as the
// SVDAG's leaves). A ray step reads at most the chunk word, the block word and the bitmap: three dependent loads.
//
// The host only keeps the books (which section sits in which slot, which chunks changed) and hands batches of raw
// sections to the backend: the Vulkan backend copies them into its pool and builds bitmaps and distance fields with
// compute shaders (atlas_voxgrid.slang); the CPU backend builds the same words here (applyCpu), the reference.
//
// The coarse levels (k = 1 .. coarseLevels): the same grid of 2^k-block voxels, one continuous structure from level 0
// out. A chunk is 16 voxels a side (2^k sections), its window D x H_k x D chunks around the camera (k: 2^k times level
// 0's reach), its ids in a pool slot as level 0's. Built on the GPU from the sections' raw ids alone: per section the
// GPU makes its generated levels 1-4 (Section::buildMips' vote, atlas_voxgrid.slang vgIngestMain) and writes each into
// its place in that level's chunk (levels 5+: a chunk from its 8 children of the level below, vgDownMain); then the
// bitmaps and boxes as level 0's. A chunk is known (MIXED / EMPTY) once every section column it covers has come: a
// column's sections from its lowest sent one up (below it solid rock, the stone material; above its highest air); one
// with a column not seen yet is UNLOADED (the SVDAG regions, made from the tiles alone, answer there). A level keeps
// what it was given while its window holds it: what the camera saw stays at the coarser levels as it moves on.
#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <deque>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include "world.hpp"  // (Key3, Section)

namespace atlas {

class World;

constexpr uint32_t VG_EMPTY = 0, VG_MIXED = 1, VG_UNLOADED = 2, VG_UNIFORM = 3;
constexpr uint32_t VG_NOT_UNIFORM = 0xFFFFFFFFu;  // (VoxGrid::ingestGpu's hint) not known to be one id
constexpr int VG_SECTION_WORDS = 2048;  // a section's 4096 16-bit ids
constexpr int VG_MIN_SY = -4;           // y -64 (World::WORLD_MIN_Y / 16)
constexpr uint32_t VG_COLUMN = 0xFFFFFFFEu;  // (VgJob::slot)
constexpr uint32_t VG_NO_COPY = 0xFFFFFFFFu;  // (VgJob::data) the slot's ids are in the pool already (a coarse chunk)
constexpr uint32_t VG_GPU_SRC = 0x80000000u;  // (VgJob::data, VgIngest::src) | index in VgBatch::gsecs: ids on the GPU
constexpr uint32_t VG_PACK_SRC = 0x40000000u; // (VgJob::data, VgIngest::src) | index in VgBatch::packs: packed ids
constexpr uint32_t VG_POOL_SRC = 0x40000000u; // (VgBatch::PackJob::src) | a level-0 slot: its ids as the pool holds them

// (VoxGrid::ingestGpu) a section's ids in a GPU buffer: the backend's buffer handle (Vulkan: a VkBuffer), byte offset
struct VgGpuRef {
    uint64_t buffer = 0, offset = 0;
};
constexpr int VG_LEVELS = 7;                  // level 0 and the coarse levels 1..6

// a section's ids: the world's own Section::mats (shared, never copied) or an upload's copy
using VgIds = std::shared_ptr<const std::array<uint16_t, 4096>>;
// a section's ids packed (sectionpack.hpp: the host's tiers), expanded on the GPU (vgUnpackMain)
using VgPacked = std::shared_ptr<const std::vector<uint32_t>>;
// where a section's ids are: raw on the host, packed on the host, or (both null) on the GPU (ingestGpu)
struct VgSrc {
    VgIds ids;
    VgPacked packed;
    bool gpu() const { return !ids && !packed; }
};

// One chunk to (re)write: its word (EMPTY / UNLOADED: as is; MIXED: built from the slot's ids, which the batch carries)
struct VgJob {
    uint32_t chunk;  // index in the chunk words (level 0: (y * D + z) * D + x; a coarse level's after its chunkBase)
    uint32_t word;   // the chunk's word (for MIXED: slot << 2 | VG_MIXED); a MIXED chunk none of whose voxels is
                     // solid is written EMPTY by the build (a coarse chunk whose content the vote left out)
    uint32_t slot;   // MIXED: the slot (its ids: Batch::secs[data]); ~0u: no build; VG_COLUMN: `word` into every cell
                     // of the column whose lowest cell is `chunk` (a column the window left)
    uint32_t data;   // MIXED: index of its ids in Batch::secs (VG_NO_COPY: in the pool already)
};

// (coarse levels) voxels [lo, hi) of a slot's chunk (x | y << 8 | z << 16) set to one id
struct VgFill {
    uint32_t slot, lo, hi, mat;
};
// (coarse levels 1-4) a section's generated levels, made on the GPU from its ids, into the chunks holding it: per level
// 1..4 the target slot << 12 | the section's place in that chunk (x | y << 4 | z << 8, sections), ~0u none
struct VgIngest {
    uint32_t src;
    uint32_t target[4];
    uint32_t pad[3];
};
// (coarse levels 5+) a chunk made from its 8 children of the level below (x + 2 z + 4 y): their slots or VG_KID_*
struct VgDown {
    uint32_t slot, rock, pad[2];
    uint32_t kid[8];
};
constexpr uint32_t VG_KID_AIR = 0xFFFFFFFFu, VG_KID_ROCK = 0xFFFFFFFEu;
constexpr uint32_t VG_KID_UNIFORM = 0xFD000000u;  // | id: a child of one id throughout (no slot)
// (VoxGrid::ingestCoarseGpu) a chunk's kind: its ids at the offset; all air; one id (| id << 8), no ids
constexpr uint32_t VG_GIVEN_MIXED = 0, VG_GIVEN_AIR = 1, VG_GIVEN_UNIFORM = 2;

struct VgBatch {
    bool clear = false;          // the window moved: every level-0 chunk UNLOADED before the jobs
    int ox = 0, oz = 0;          // the window these jobs are for (origin in sections)
    bool chunkStates = false;    // some chunk's state changed: the chunk-level distance fields need a rebuild
    std::vector<VgJob> jobs;     // level 0's, then the coarse levels' (their builds after the ingests)
    std::vector<VgIds> secs;  // the MIXED jobs' ids (two to a word in the pool: their own little-endian bytes)
    // packed sections to expand on the GPU first (into the GPU sections' buffer after gsecs: VG_PACK_SRC | index here)
    std::vector<VgPacked> packs;
    // raw sections to pack on the GPU (the host's warm tier: World::packedDone): staged in secs[src]
    struct PackJob {
        Key3 key;
        uint32_t gen = 0, src = 0;
    };
    std::vector<PackJob> packJobs;
    // (residency on the GPU) the waiting flags that changed (level-0 cell, 0 / 1 / 2), and this batch's ranking: the
    // eye, the view's cone, the reach, the window, the pool (cap, free), the budget, the margin (atlas_voxgrid.slang
    // RankView)
    std::vector<std::pair<uint32_t, uint32_t>> cand;
    struct Rank {
        bool on = false;
        int32_t eye[3] = {0, 0, 0};
        float fwd[3] = {0, 0, -1};
        float cosView = 0.5f, reach = 1e29f, nearBall = 48.0f, viewOut = 3.0f;
        int32_t ox = 0, oz = 0;
        uint32_t cap = 0, free = 0, budget = 256, margin = 3;
    } rank;
    std::vector<VgGpuRef> gsecs;  // ids already on the GPU (ingestGpu): data / src VG_GPU_SRC | index here
    // the coarse levels: whole chunks to air first, then boxes (rock under a column's lowest section, air), the sections'
    // generated levels, the levels 5+ from the level below (in order of level)
    std::vector<VgFill> clears, fills;
    std::vector<VgIngest> ingests;
    std::vector<VgDown> downs[VG_LEVELS];
    // (ingestCoarseGpu) the generator's coarse chunks: pool slot <- its ids on the GPU, copied before the passes
    std::vector<std::pair<uint32_t, VgGpuRef>> poolCopies;
    // (ingestCoarseHost) pool slot <- packs[index] once expanded
    std::vector<std::pair<uint32_t, uint32_t>> packPool;
    struct Level {
        bool clear = false;  // every chunk UNLOADED first (placed, or a jump past the window)
        bool grow = false;   // the chunk-level boxes rebuilt after the jobs
        int ox = 0, oz = 0;  // the window (chunks)
    } levels[VG_LEVELS];
    std::shared_ptr<const std::vector<uint8_t>> classes;  // material -> class, for the ingest's vote
    size_t level0Jobs = 0;  // jobs[0, level0Jobs) are level 0's
    // per level-0 job its cell's generation (VoxGrid::gpuUniform: a finding about an older job is not applied)
    std::vector<uint32_t> gens;
    bool coarse() const {
        if (!clears.empty() || !fills.empty() || !ingests.empty() || jobs.size() > level0Jobs || !gsecs.empty() ||
            !poolCopies.empty() || !packs.empty() || !packJobs.empty() || !cand.empty() || rank.on)
            return true;
        for (int k = 1; k < VG_LEVELS; k++)
            if (levels[k].clear || levels[k].grow || !downs[k].empty()) return true;
        return false;
    }
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
    // level 0's pool slots (8 KiB of ids + 768 B of blocks each): planMemory's share of the device's memory, at most a
    // slot a cell of the window; ATLAS_VOXGRID_SLOTS fixes it. Fixed once the first frame has laid the levels out.
    uint32_t slotCap = 16384;
    static constexpr uint32_t SLOT_BYTES = VG_SECTION_WORDS * 4 + 64 * 12;
    // the memory plan (atlas_memory_info): `deviceBytes` the device-local budget the process may use (0: unknown, the
    // default cap stays); ATLAS_VOXGRID_MEM the share of it level 0's pool takes (0.10)
    void planMemory(uint64_t deviceBytes, uint64_t maxRangeBytes = 0);  // (and what one descriptor sees)
    bool enabled = true;  // ATLAS_VOXGRID=0: off (the SVDAG level 0 alone)

    // a section's ids as World::putSection gets them (any thread)
    void put(int sx, int sy, int sz, const uint16_t* mats);
    // the same with the world's own copy (shared, never copied: World::putSection's, immutable once stored)
    void put(int sx, int sy, int sz, VgIds ids);
    void putLocked(int sx, int sy, int sz, VgIds ids);  // (caller holds mu_)
    // a section's packed words (the gen cache's: sectionpack.hpp), expanded on the GPU
    void putPacked(int sx, int sy, int sz, VgPacked words);
    // (the self-baking cache) generated sections on the GPU to pack for the world's gen cache (World::BAKE): at most
    // bakeBudget a batch; their buffers held (gpuPendingOffsets) until a batch took them
    void bakeGpu(uint64_t buffer, const uint64_t* offsets, const int32_t* pos, size_t count, int level = 0);
    // (the self-baking cache, a known far tile) coarse chunks from the host: kinds as ingestCoarseGpu's, a mixed one's
    // packed words (expanded on the GPU into its slot); returns how many were taken
    size_t ingestCoarseHost(int level, const int32_t* pos, const uint32_t* kinds, const VgPacked* packed, size_t count,
                            uint8_t* accepted);
    int bakeBudget = 512;
    // a coarse level's window now: origin and side (chunks), whether it is placed, how often it was placed afresh (a jump:
    // everything of it given before is gone). The far requests follow it (a tile is asked for again when the window
    // slides over columns it was made without).
    void coarseWindow(int k, int32_t out[6]) const {
        std::lock_guard<std::mutex> lock(mu_);
        for (int i = 0; i < 6; i++) out[i] = 0;
        if (k < 1 || k > activeCoarse_) return;
        const Level& L = lv_[k];
        out[0] = L.ox, out[1] = L.oz, out[2] = L.D, out[3] = L.centred ? 1 : 0, out[4] = int32_t(L.jumps), out[5] = L.H;
    }
    // whether a far chunk the level refused was refused for want of room (true) or is not this level's to hold: outside
    // its window, or its columns come from real sections (false: nothing to ask again for)
    bool coarseRefusedForRoom(int k, int cx, int cz) const;
    // free slots of a coarse level's pool (a refused far chunk is asked for again only when there is room)
    uint32_t coarseFreeSlots(int k) const {
        std::lock_guard<std::mutex> lock(mu_);
        if (k < 1 || k > activeCoarse_) return 0;
        const Level& L = lv_[k];
        return L.cap > L.used ? L.cap - L.used : 0;
    }
    // (atlas_info voxgrid) the view's coverage: per level the window's columns with every row UNLOADED (holes: nothing
    // there at this level) and with some row UNLOADED (partial: a pool gave a cell up); the far reach, the distance
    // to the nearest place around the camera (128-block samples, to 20 km) that no level knows (20000: none)
    struct Coverage {
        uint32_t holes[VG_LEVELS] = {}, partial[VG_LEVELS] = {}, columns[VG_LEVELS] = {};
        float reach = 0.0f;
        // a fine probe (32-block samples within 1.5 km): how many no level knows, the nearest such place (the ring
        // right outside level 0 would show here)
        uint32_t nearHoles = 0;
        float nearReach = 1500.0f;
    };
    Coverage coverage() const;
    // (cached 1.5 s: the scan is of every window's cells) the coverage as atlas_info's fields
    std::string coverageJson() const;
    mutable Coverage covCache_;
    mutable std::mutex covMu_;
    mutable std::chrono::steady_clock::time_point covAt_{};
    // (atlas_info voxgrid) what is still to refine with the camera still: coarse cells given up and waiting for room, GPU
    // sections asked for again and the asks held back (asked lately), the bake's queue
    // (take's phases, the worst of the last 2-4 s: the lock's wait, level 0, the coarse levels, the rest)
    double phaseCur_[4] = {}, phasePrev_[4] = {};
    std::chrono::steady_clock::time_point phaseAt_ = std::chrono::steady_clock::now();
    void notePhases(const double* ms, std::chrono::steady_clock::time_point now) {
        if (now - phaseAt_ > std::chrono::seconds(2)) {
            for (int i = 0; i < 4; i++) phasePrev_[i] = phaseCur_[i], phaseCur_[i] = 0;
            phaseAt_ = now;
        }
        for (int i = 0; i < 4; i++) phaseCur_[i] = std::max(phaseCur_[i], ms[i]);
    }
    std::string phaseInfo() const {
        char t[160];
        std::snprintf(t, sizeof t, "\"takePhases\":{\"lock\":%.2f,\"level0\":%.2f,\"coarse\":%.2f,\"rest\":%.2f},",
                      std::max(phaseCur_[0], phasePrev_[0]), std::max(phaseCur_[1], phasePrev_[1]), std::max(phaseCur_[2], phasePrev_[2]),
                      std::max(phaseCur_[3], phasePrev_[3]));
        return t;
    }
    std::string refineInfo() const {
        char b[160];
        {
            std::lock_guard<std::mutex> lock(mu_);
            size_t lost = 0, given = 0;
            for (int k = 1; k < VG_LEVELS; k++) lost += lostCells_[k].size(), given += cgiven_[k].size();
            std::snprintf(b, sizeof b, "\"coarseLost\":%zu,\"coarseGiven\":%zu,\"wantedAsked\":%llu,\"wantedHeld\":%llu,", lost,
                          given, (unsigned long long)wantedAgain_, (unsigned long long)wantedHeld_);
        }
        return std::string(b) + coverageJson();  // (its own lock)
    }
    size_t bakeQueued() const {
        std::lock_guard<std::mutex> lock(mu_);
        return bake_.size();
    }
    // the world whose tiers the grid takes its sections back from (standby) and feeds packing (World::packOnGpu)
    void setWorld(World* w) { world_ = w; }
    // level 0's ranking on the GPU (a device backend): the host applies the lists rankResults hands it (no host ranking)
    bool gpuRank = false;
    void rankResults(const std::vector<uint32_t>& admits, const std::vector<uint32_t>& evicts, uint32_t cut, uint32_t waitBest);
    // (the backend) a batch it took never reached the GPU (a failure before its submit): every level is placed again
    // next frame (what the books thought written, written again)
    void forceReplace();
    int packBudget = 512;  // sections packed a batch at most (ATLAS_PACK_BUDGET)
    // (the backend's staging budget: the render thread's share of a frame) the batch budgets scaled by this, 0.05..1,
    // eased from the staging's measured time
    float budgetScale = 1.0f;
    int scaled(int b) const { return std::max(8, int(float(b) * budgetScale)); }
    // (the backend) a batch's packed sections, back from the GPU: to the world's warm tier
    void packedResults(const std::vector<World::PackDone>& done);
    // (GPU world generation) sections whose ids are already in a GPU buffer, without a round trip through the CPU:
    // `count` sections of 16^3 block ids as atlas_upload_section takes them (4096 uint16, x + 16 * (z + 16 * y): 8 KiB
    // each) at byte offsets[i] of `buffer` (the Vulkan backend's VkBuffer as a handle, created on atlas's device with
    // storage and transfer-source usage; offsets multiples of 16), at world section positions pos[3 i .. 3 i + 2] =
    // x, y, z. They go into level 0 and the coarse levels as uploads do: the backend copies them device to device
    // (vkCmdCopyBuffer before the frame's grid build). The books take each as holding blocks (a slot; one the GPU
    // finds empty is written EMPTY), the column as seen; the CPU's World does not get them (its regions, the tiles'
    // fallback, stay as they are). Any thread. The buffer's contents at those offsets must stay valid until
    // gpuSectionsPending() is 0 and two frames more have been rendered (the frames in flight that read them). A later
    // put() of the same section replaces it. The CPU backend ignores them.
    // Returns how many it took; `accepted` (optional, count bytes): 1 for each taken. Not taken: outside every level's
    // window, or before the first frame has placed the windows: send them again later. The CPU's World never holds them,
    // so a level whose window leaves a section (or a jump, or level 0's pool full of nearer ones) cannot read it back:
    // takeGpuDropped() lists the sections no level holds any more (each once), to send again when the camera returns.
    // (A coarse level's chunk given up for a nearer one, its pool full, is not listed: the level takes nothing there
    // until its window leaves that column.)
    // Level 0 takes one only while its priority (residency below) is within the cut-off of a full pool; one it could
    // not take, or gave up for a nearer one, it asks for again once it would be taken (takeGpuWanted, each once).
    // `uniform` (optional, count words): per section its one block id when the caller knows it is uniform (the
    // generator's deep rock), else VG_NOT_UNIFORM: such a section takes no slot and is not copied for level 0. (The
    // GPU's build finds the uniform ones among the rest itself and gives their slots back, gpuUniform.)
    size_t ingestGpu(uint64_t buffer, const uint64_t* offsets, const int32_t* pos, size_t count, uint8_t* accepted = nullptr,
                     const uint32_t* uniform = nullptr);
    size_t gpuSectionsPending() const;
    // (level, x, y, z): level 0 sections (section coordinates), level k > 0 the generator's chunks (ingestCoarseGpu)
    std::vector<std::array<int32_t, 4>> takeGpuDropped();
    std::vector<std::array<int32_t, 4>> takeGpuWanted();  // what level 0 would take now: send again
    // (the GPU generator's far terrain) Level-k chunks (k 1..6: 16 voxels of 2^k blocks a side) given whole:
    // pos[3 i ..] = cx, cy, cz in level-k chunks (block x = cx * 16 * 2^k; cy the level's row from y -64);
    // kinds[i] VG_GIVEN_MIXED (4096 ids at offsets[i] of `buffer`, a section's layout, 16-aligned: the level's voxel
    // ids), VG_GIVEN_AIR, or VG_GIVEN_UNIFORM | id << 8 (no buffer, no slot). A chunk given is the level's own until
    // sections covering it arrive: they refine it in place (their voted voxels over their own part); one whose
    // columns hold sections already is not taken (sections first). Levels 5-6: until its children change.
    // Admission by the same priority as level 0's against the level's pool (a full pool's worst slot by a margin).
    // Returns how many it took; accepted[i] 1 each; not taken: outside the level's window (or no level k): later.
    // A taken one that the level could not hold, or gives up (its window moved on, a nearer chunk), is listed by
    // takeGpuDropped with its level. Buffer lifetime as ingestGpu's (gpuPendingOffsets, then two frames).
    size_t ingestCoarseGpu(int level, uint64_t buffer, const uint64_t* offsets, const int32_t* pos, const uint32_t* kinds,
                           size_t count, uint8_t* accepted = nullptr);
    // (the client generator) the byte offsets of `buffer` whose sections still wait (it reuses the others)
    void gpuPendingOffsets(uint64_t buffer, std::vector<uint64_t>& out) const;
    // (the Vulkan backend) its build found these level-0 jobs' sections to be one id (atlas_voxgrid.slang
    // vgSectionMain wrote the chunk VG_UNIFORM / VG_EMPTY itself): their slots are free again
    struct UniformHit {
        uint32_t chunk, slot, gen, id;
    };
    void gpuUniform(const std::vector<UniformHit>& hits);
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

    // ---- the coarse levels (above)
    int coarseLevels = 6;  // levels 1..coarseLevels (ATLAS_VOXGRID_COARSE; 0: none); clamped to the world's LODs - 1
    int coarseD = 64;      // their windows' side in chunks (ATLAS_VOXGRID_COARSE_D)
    // per level its pool slots (ATLAS_VOXGRID_COARSE_SLOTS, comma separated from level 1); full: the farthest go
    uint32_t coarseCap[VG_LEVELS] = {0, 6144, 2048, 1024, 512, 512, 256};
    bool coarseCapByHand = false;  // (ATLAS_VOXGRID_COARSE_SLOTS: planMemory leaves them)
    int coarseBudget = 512;  // sections taken into the coarse levels a batch at most (ATLAS_VOXGRID_COARSE_BUDGET)
    uint16_t stoneMat = 1;   // what lies under a column's lowest section
    void setClasses(const std::vector<uint8_t>& cls);  // the palette's material classes (the vote's kinds)
    // the camera's view (horizontal forward): what lies behind it is taken after what it sees
    void setView(float fx, float fz);
    // Residency (level 0; the one priority step 5's residency manager grows from): a section's priority is its
    // distance from the eye to its box, times VIEW_OUT outside the view's cone (but within NEAR_BALL blocks); past
    // the footprint's reach (where a pixel covers FOOT blocks, level 0's share of the continuous level gone) level 0
    // does not want it at all: the coarse levels serve it. Admission and eviction by that priority alone, never
    // arrival: a pool full gives the farthest slot to a nearer section (by a margin, no thrash); what it gives up or
    // cannot take waits (a CPU section by its ids, a GPU one asked for again) and is taken back when it ranks again.
    // The full view: unit forward (fx, fy, fz), the cos of the screen's half diagonal, the pixel's angle (radians,
    // times the frame's lod bias; 0: no reach) and the continuous level's bias (ATLAS_LOD's)
    void setView(const float fwd[3], float cosHalf, float pixelAngle, float levelBias);
    static constexpr float NEAR_BALL = 48.0f, VIEW_OUT = 3.0f;
    struct Residency {
        uint32_t resident = 0, cap = 0, uniform = 0;  // slots in use, of the pool; uniform cells (no slot)
        size_t waiting = 0, gpuWaiting = 0;           // known sections not resident (CPU ids kept; GPU ones to ask for)
        size_t coarseWaiting = 0;                     // sections the coarse levels have yet to take in
        uint64_t evicted = 0, misses = 0, gpuUniform = 0;  // given up for nearer; could not be taken; found by the GPU
        float cutoff = 0, reach = 0;  // the priority (blocks) past which a full pool takes nothing; the footprint's reach
        uint32_t nearestMissing = 0;  // (blocks) the nearest in-reach section known but not resident (0: none)
    };
    Residency residency() const;
    // the layout of every level in the backend's buffers (fixed once the first frame placed the windows)
    struct LevelInfo {
        int D = 0, H = 0;
        uint32_t chunkBase = 0, slotBase = 0, cap = 0;
        bool active = false;
        int ox = 0, oz = 0;  // the window's origin (chunks)
        uint32_t used = 0;
        uint64_t evicted = 0, overflow = 0, jumps = 0, shifts = 0, ingested = 0;
        size_t pending = 0;
    };
    LevelInfo levelInfo(int k) const;
    int levels() const { return 1 + activeCoarse_; }  // level 0 and the coarse ones
    size_t chunkWords() const;    // all levels' chunk words
    uint32_t totalSlots() const;  // all levels' slots
    // FrameGpu::vgLevel[k - 1]: origin x, z (chunks), first chunk word, D | H << 8 | 1 << 16 when active
    void levelFrame(int k, int32_t out[4]) const;

    // ---- the CPU reference (the CPU backend traces these; the tests compare the GPU's against them)
    std::vector<uint32_t> chunks, blocks, pool;
    std::vector<uint64_t> bits;
    int cpuOx = 0, cpuOz = 0;  // the window the CPU arrays hold
    int cpuLevelO[VG_LEVELS][2] = {};  // and the coarse levels' windows
    void applyCpu(const VgBatch& b);
    // the chunk-level distance fields over the whole window from the chunk words (CPU; the GPU's vgGrow does the same)
    void growChunksCpu();
    void growLevelCpu(int D, int H, uint32_t base, int ox, int oz);
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
    using Ids = VgSrc;
    std::unordered_map<Key, Ids, KeyHash> pending_;
    World* world_ = nullptr;
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
    std::vector<uint8_t> slotCpu_;      // per slot: its section came from the host (1: the world's tiers hold it)
    std::vector<uint32_t> cellGen_;     // per cell: bumped with each job (gpuUniform's findings are for one job)
    uint32_t uniform_ = 0;              // cells VG_UNIFORM
    // the in-window sections known but not resident: CPU ones by their ids, GPU ones by key (asked for again)
    std::unordered_map<Key, uint8_t, KeyHash> standby_;  // (host sections: their ids from World::sectionSource)
    std::unordered_map<Key, uint8_t, KeyHash> gpuStandby_;  // (1: asked for already)
    std::vector<std::array<int32_t, 4>> gpuWanted_;
    // (the reports, bounded: a consumer that never takes them loses the oldest, never memory)
    void reportDropped(const std::array<int32_t, 4>& r);
    void reportWanted(const std::array<int32_t, 4>& r);
    // (takeCoarse) a coarse chunk's job in this batch (global chunk index -> job), one job a chunk; and the coarse
    // levels' cells given up for nearer chunks, taken back when the view moves on or slots come free (coarse standby)
    std::unordered_map<uint32_t, uint32_t> coarseJobAt_;
    void coarseJob(VgBatch& b, const VgJob& j);
    std::vector<uint32_t> lostCells_[VG_LEVELS];
    bool coarseRankDue_ = true;
    int coarseRankedSx_ = INT32_MIN, coarseRankedSz_ = INT32_MIN;
    void coarseReadmit(VgBatch& b);
    std::unordered_map<Key, uint32_t, KeyHash> gpuUniformHint_;  // (ingestGpu's hint) for the pending GPU sections
    // (edit layers, World::takeBaseWanted) sections whose GPU base the world waits for: packed on the GPU at their build
    std::unordered_set<Key, KeyHash> baseFetch_;
    // (the full pool's loop) GPU sections asked for again (reportWanted) and the batch: a slot is made for them, their
    // ingest takes it past the cut-off, and one not taken is asked again only after WANT_AGAIN batches
    std::unordered_map<Key, uint64_t, KeyHash> wantedAt_;
    static constexpr uint64_t WANT_AGAIN = 240;
    uint64_t wantedAgain_ = 0, wantedHeld_ = 0;  // (stats: asks, asks held back)
    std::deque<std::pair<Key3, VgGpuRef>> bake_;  // (lod: 0 a section, k a level-k chunk)
    void putSrc(int sx, int sy, int sz, VgSrc src);  // (caller holds mu_)
    int baseOx_ = INT32_MIN, baseOz_ = INT32_MIN;
    std::vector<uint16_t> uniId_;  // per cell: its one id while VG_UNIFORM
    // the view (setView), the footprint's reach, the cut-off of a full pool, when the waiting were last ranked
    float fwd_[3] = {0, 0, -1}, cosView_ = 0.5f, pixelAngle_ = 0.0f, levelBias_ = 0.0f, reach_ = 1e30f, cutoff_ = 1e30f;
    int camB_[3] = {0, 0, 0};
    int rankedSx_ = INT32_MIN, rankedSy_ = 0, rankedSz_ = 0;
    float rankedFwd_[3] = {0, 0, 0};
    bool rankDue_ = true;
    uint64_t misses_ = 0, gpuUniformHits_ = 0;
    uint32_t nearestMissing_ = 0;
    float priority(int sx, int sy, int sz) const;  // (mu_ held) blocks; 1e30: past the reach
    void dropOutside();                            // (mu_ held) the waiting outside the window forgotten
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

    // ---- the coarse levels' books
    struct Col {
        uint64_t recv = 0, solid = 0;  // its sections that came (bit sy - VG_MIN_SY), and those with blocks
    };
    struct Level {
        int k = 0, D = 0, H = 0;
        uint32_t cap = 0, slotBase = 0, chunkBase = 0;
        bool centred = false, clearPending = false;
        int ox = 0, oz = 0;  // window origin, chunks
        std::vector<uint32_t> slotOf;    // per cell: its slot (level-local) or ~0u
        std::vector<uint32_t> word;      // per cell: the word last sent
        std::vector<uint8_t> lost;       // per cell: its slot taken for a nearer one: unknown until its column leaves
        std::vector<uint8_t> rock;       // per cell: known, and under every column's lowest section (UNLOADED: rock)
        // per cell: given by the generator (ingestCoarseGpu): 0 not, 1 air, 2 one id (givenMat), 3 its slot holds
        // the generator's ids (sections arriving since refine it in place)
        std::vector<uint8_t> given;
        std::vector<uint16_t> givenMat;
        bool slotless = false;  // some cell holds blocks without a slot (given, or one id): it holds blocks
        std::vector<uint8_t> touched;    // per cell: written this batch (rebuilt)
        std::vector<uint8_t> dirty;      // per cell: to look at again this batch
        std::vector<uint32_t> dirtyList;
        std::vector<uint8_t> fresh;      // per chunk column: entered, the world's sections not read yet
        uint32_t freshCount = 0;
        std::vector<uint32_t> vacated;   // cells to send UNLOADED (the columns the window left)
        std::vector<uint32_t> slotCell;  // per slot: its cell or ~0u
        std::vector<uint32_t> freeSlots;
        uint32_t nextSlot = 0, used = 0;
        std::unordered_map<uint64_t, Col> cols;  // the window's section columns this level was given
        // (levels 5+) cells whose children changed
        std::vector<uint8_t> downDirty;
        std::vector<uint32_t> downList;
        uint64_t evicted = 0, overflow = 0, jumps = 0, shifts = 0, ingested = 0;
        int span() const { return 1 << k; }  // sections a chunk side
        uint32_t cell(int cx, int cy, int cz) const {
            return uint32_t((size_t(cy) * D + size_t(pmod(cz, D))) * D + size_t(pmod(cx, D)));
        }
        bool inWindow(int cx, int cz) const { return cx >= ox && cx < ox + D && cz >= oz && cz < oz + D; }
    };
    Level lv_[VG_LEVELS];
    int activeCoarse_ = 0;  // coarse levels laid out (fixed at the first frame)
    bool laidOut_ = false;
    // sections for the coarse levels: their ids and which levels want them (bit k)
    struct CPending {
        VgSrc ids;
        uint32_t levels = 0;
    };
    std::unordered_map<Key, CPending, KeyHash> cpending_;
    std::unordered_map<Key, VgGpuRef, KeyHash> gpuRef_;  // (ingestGpu) sections waiting whose ids are on the GPU
    // (ingestCoarseGpu) per level the chunks given and not yet taken into a batch, by cell
    struct CGiven {
        uint32_t kind = 0, mat = 0;
        VgGpuRef ref;
        VgPacked packed;  // (ingestCoarseHost: a mixed chunk's words from the host, not ref)
        int cx = 0, cy = 0, cz = 0;
    };
    std::unordered_map<uint32_t, CGiven> cgiven_[VG_LEVELS];
    float chunkPriority(int k, int cx, int cy, int cz) const;  // (mu_ held) a level-k chunk's priority (blocks)
    // (mu_ held) whether a level-k chunk at that priority would get a slot now (coarseSlot's rule, nothing taken)
    bool coarseRoom(Level& L, float dist);
    void coarseVictims(Level& L);
    // (ingestGpu) the levels holding each section taken (bit k), by column; those no level holds any more
    std::unordered_map<Key, uint32_t, KeyHash> gpuHold_;
    std::unordered_map<uint64_t, std::vector<int16_t>> gpuCol_;
    std::vector<std::array<int32_t, 4>> gpuDropped_;
    void gpuLetGo(int sx, int sy, int sz, uint32_t bits);  // (mu_ held)
    void gpuLetGoColumn(int sx, int sz, uint32_t bits);
    void gpuLetGoAll(uint32_t bits);
    std::shared_ptr<const std::vector<uint8_t>> classes_ = std::make_shared<std::vector<uint8_t>>();
    float viewX_ = 0.0f, viewZ_ = 0.0f;
    int camSy_ = 0;
    void take0(VgBatch& b, int maxSections);             // (mu_ held) level 0's share of a batch (ranked here)
    void take0Gpu(VgBatch& b, int maxSections);          // (mu_ held) ... ranked on the GPU (gpuRank)
    std::vector<uint32_t> rankAdmits_, rankEvicts_;      // (rankResults) the last lists, cells
    uint32_t rankCut_ = 255, rankWaitBest_ = 255;        // (buckets)
    bool rankSeen_ = false;
    float lastFwd_[3] = {0, 0, -1}, vel_[3] = {0, 0, 0}, turnRate_ = 0.0f;  // (predicted motion)
    int lastEye_[3] = {0, 0, 0};
    void layOut();                                       // (mu_ held)
    void placeLevel(Level& L, int camSx, int camSz);     // (mu_ held) follow the camera
    void refillCoarse(World& world, bool wait);          // (mu_ held) the entered columns' sections from the world
    void takeCoarse(VgBatch& b);                         // (mu_ held)
    // (takeCoarse) a slot for the cell (level-local; ~0u: none), its chunk cleared; the cells whose section columns'
    // rock and air are to be written at the batch's end; the victims for a full pool (farthest last)
    // (raw: no clear, no column fills: a generator chunk's ids are copied in whole)
    uint32_t coarseSlot(Level& L, uint32_t cell, VgBatch& b, bool raw = false);
    std::vector<std::pair<float, uint32_t>> victims_[VG_LEVELS];
    bool victimsBuilt_[VG_LEVELS] = {};
    struct ColFill {
        int k;
        uint32_t cell;
        int sx, sz;
        bool operator==(const ColFill&) const = default;
    };
    std::vector<ColFill> colFills_;
    void markDirty(Level& L, uint32_t cell) {
        if (!L.dirty[cell]) L.dirty[cell] = 1, L.dirtyList.push_back(cell);
    }
    void cellCoords(const Level& L, uint32_t cell, int& cx, int& cy, int& cz) const;  // (logical, in the window)
    uint32_t evalCell(Level& L, uint32_t cell, VgBatch& b);  // its word now (and L.rock)
    int64_t viewDist(int64_t dx, int64_t dz) const;  // a distance for ordering: squared, 4x behind the camera
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
