#include "voxgrid.hpp"
#include "vgask.hpp"
#include "reachstep.hpp"

#include <algorithm>
#include <bit>
#include <climits>
#include <tuple>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "groups.hpp"
#include "sectionpack.hpp"
#include "world.hpp"

namespace atlas {

namespace {
int fdiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
constexpr int16_t NO_TOP = INT16_MIN;
}  // namespace

VoxGrid::VoxGrid() {
    if (const char* e = std::getenv("ATLAS_VOXGRID")) enabled = std::atoi(e) != 0;
    if (const char* e = std::getenv("ATLAS_VOXGRID_D")) D = std::clamp(std::atoi(e), 8, 128);
    if (const char* e = std::getenv("ATLAS_VOXGRID_SLOTS")) slotCap = uint32_t(std::clamp(std::atoi(e), 64, 1 << 20));
    if (const char* e = std::getenv("ATLAS_VOXGRID_COARSE")) coarseLevels = std::clamp(std::atoi(e), 0, VG_LEVELS - 1);
    if (const char* e = std::getenv("ATLAS_VOXGRID_COARSE_D")) coarseD = std::clamp(std::atoi(e), 8, 128);
    if (const char* e = std::getenv("ATLAS_VOXGRID_COARSE_BUDGET")) coarseBudget = std::max(1, std::atoi(e));
    if (const char* e = std::getenv("ATLAS_PACK_BUDGET")) packBudget = std::max(0, std::atoi(e));
    if (const char* e = std::getenv("ATLAS_VOXGRID_COARSE_SLOTS")) {
        int k = 1;
        for (const char* p = e; *p && k < VG_LEVELS; k++) {
            coarseCap[k] = uint32_t(std::clamp(std::atoi(p), 16, 1 << 18));
            coarseCapByHand = true;
            p = std::strchr(p, ',');
            if (!p) break;
            p++;
        }
    }
}

namespace {
uint64_t colKey(int sx, int sz) { return uint64_t(uint32_t(sx)) << 32 | uint32_t(sz); }
// a section's one id (0: air), or VG_NOT_UNIFORM
uint32_t uniformIdOf(const std::array<uint16_t, 4096>& a) {
    uint64_t w;
    std::memcpy(&w, a.data(), 8);
    if ((w & 0xFFFFu) * 0x0001000100010001ull != w) return VG_NOT_UNIFORM;
    for (size_t i = 4; i < 4096; i += 4) {
        uint64_t v;
        std::memcpy(&v, a.data() + i, 8);
        if (v != w) return VG_NOT_UNIFORM;
    }
    return uint32_t(w & 0xFFFFu);
}
int lowBit(uint64_t m) { return m ? std::countr_zero(m) : INT_MAX; }
int highBit(uint64_t m) { return m ? 63 - std::countl_zero(m) : -1; }
}  // namespace

void VoxGrid::setClasses(const std::vector<uint8_t>& cls) {
    std::lock_guard<std::mutex> lock(mu_);
    classes_ = std::make_shared<const std::vector<uint8_t>>(cls);
}

void VoxGrid::setView(float fx, float fz) {
    std::lock_guard<std::mutex> lock(mu_);
    viewX_ = fx;
    viewZ_ = fz;
}

void VoxGrid::setView(const float fwd[3], float cosHalf, float pixelAngle, float levelBias) {
    std::lock_guard<std::mutex> lock(mu_);
    float l = std::sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2]);
    if (l > 1e-6f)
        for (int i = 0; i < 3; i++) fwd_[i] = fwd[i] / l;
    cosView_ = std::clamp(cosHalf, -1.0f, 1.0f);
    pixelAngle_ = std::max(pixelAngle, 0.0f);
    levelBias_ = levelBias;
}

void VoxGrid::planMemory(uint64_t deviceBytes, uint64_t maxRangeBytes, bool integrated) {
    std::lock_guard<std::mutex> lock(mu_);
    if (laidOut_ || centred_ || !deviceBytes || std::getenv("ATLAS_VOXGRID_SLOTS")) return;  // (fixed, or by hand)
    double share = 0.10;
    const char* memEnv = std::getenv("ATLAS_VOXGRID_MEM");
    const bool explicitMem = memEnv && *memEnv;
    if (explicitMem) share = std::clamp(std::atof(memEnv), 0.01, 0.6);
    else if (integrated) deviceBytes = std::min<uint64_t>(deviceBytes, 10ull << 30);  // (UMA / Steam Deck: <= 1.5 GiB total pool)
    uint64_t slots = uint64_t(double(deviceBytes) * share) / SLOT_BYTES;
    // (never more than a slot per active shell/cave section of the window, <= 10 mixed sections/column by default, or all H
    // when ATLAS_VOXGRID_MEM is set; never fewer than the old fixed pool)
    uint64_t cells = uint64_t(D) * uint64_t(explicitMem ? H : std::min(H, 8)) * uint64_t(D);
    // (at most a slot a cell, what one descriptor sees of the pool, and 2^19: a coarse target's slot << 12 in 32 bits)
    uint64_t hi = std::min<uint64_t>({cells, maxRangeBytes ? maxRangeBytes / (VG_SECTION_WORDS * 4) : cells, 1u << 19});
    slotCap = uint32_t(std::clamp<uint64_t>(slots, std::min<uint64_t>(16384, hi), hi));
    // the coarse levels' pools from the plan too (their windows' surface chunks: a few a column; fixed small pools
    // evicted and refused all the time, the far field never whole): 5 % of the device, never under the old pools
    if (!coarseCapByHand) {
        static const double rows[VG_LEVELS] = {0, 5.0, 3.0, 2.5, 2.0, 2.0, 2.0};  // (chunks a window column, to a full pool)
        double want = 0;
        for (int k = 1; k < VG_LEVELS; k++) want += double(coarseD) * coarseD * rows[k];
        double budget = double(deviceBytes) * 0.05 / SLOT_BYTES;
        uint64_t room = (1u << 19) > slotCap ? (1u << 19) - slotCap : 0;
        if (maxRangeBytes) room = std::min<uint64_t>(room, maxRangeBytes / (VG_SECTION_WORDS * 4) > slotCap
                                                              ? maxRangeBytes / (VG_SECTION_WORDS * 4) - slotCap : 0);
        double scale = std::min({1.0, budget / std::max(want, 1.0), double(room) / std::max(want, 1.0)});
        for (int k = 1; k < VG_LEVELS; k++)
            coarseCap[k] = std::max(coarseCap[k], uint32_t(double(coarseD) * coarseD * rows[k] * scale));
    }
}

bool VoxGrid::coarseRefusedForRoom(int k, int cx, int cz) const {
    std::lock_guard<std::mutex> lock(mu_);
    if (k < 1 || k > activeCoarse_) return false;
    if (!lv_[k].centred) return true;  // (not placed yet: the first frame places it; ask again)
    if (!lv_[k].inWindow(cx, cz)) return false;
    const Level& L = lv_[k];
    if (k <= 4 && L.colKnown[L.ccol(cx, cz)]) return false;
    return true;
}

VoxGrid::Coverage VoxGrid::coverage() const {
    std::lock_guard<std::mutex> lock(mu_);
    return coverageLocked();
}

VoxGrid::Coverage VoxGrid::coverageLocked() const {
    Coverage c;
    if (!centred_) return c;
    constexpr int CELL = 128, RMAX = 20000, N = 2 * RMAX / CELL;
    // per level the known columns (some row not UNLOADED), by window cell
    std::vector<uint8_t> known[VG_LEVELS];
    {
        known[0].assign(size_t(D) * D, 0);
        for (int z = 0; z < D; z++)
            for (int x = 0; x < D; x++) {
                int unl = 0;
                for (int y = 0; y < H; y++) unl += state_[size_t(y) * D * D + size_t(z) * D + size_t(x)] == VG_UNLOADED ? 1 : 0;
                c.columns[0]++;
                if (unl == H) c.holes[0]++;
                else if (unl) c.partial[0]++;
                known[0][size_t(z) * D + size_t(x)] = unl < H ? 1 : 0;
            }
    }
    for (int k = 1; k <= activeCoarse_; k++) {
        const Level& L = lv_[k];
        if (!L.centred) continue;
        known[k].assign(size_t(L.D) * L.D, 0);
        for (int z = 0; z < L.D; z++)
            for (int x = 0; x < L.D; x++) {
                int unl = 0;
                for (int y = 0; y < L.H; y++) {
                    uint32_t cl = L.cell(L.ox + x, y, L.oz + z);
                    unl += (L.word[cl] & 3u) == VG_UNLOADED && !L.rock[cl] ? 1 : 0;  // (rock under the surface is known)
                }
                c.columns[k]++;
                if (unl == L.H) c.holes[k]++;
                else if (unl) c.partial[k]++;
                known[k][size_t(z) * L.D + size_t(x)] = unl < L.H ? 1 : 0;
            }
    }
    {
        // level 0 within 6 chunks of the camera: the columns the player stands among, none may be missing
        int cx0 = fdiv(camB_[0], 16), cz0 = fdiv(camB_[2], 16);
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                if (dx * dx + dz * dz > 36) continue;
                int sx = cx0 + dx, sz = cz0 + dz;
                if (!(inWindow(sx, VG_MIN_SY, sz) && known[0][size_t(pmod(sz, D)) * D + size_t(pmod(sx, D))])) c.l0Near6++;
            }
    }
    // the far reach: the nearest sample no level knows
    float best = float(RMAX);
    for (int j = 0; j < N; j++)
        for (int i = 0; i < N; i++) {
            float dx = float((i - N / 2) * CELL + CELL / 2), dz = float((j - N / 2) * CELL + CELL / 2);
            float d = std::sqrt(dx * dx + dz * dz);
            if (d >= best || d > float(RMAX)) continue;
            int wx = camB_[0] + int(dx), wz = camB_[2] + int(dz);
            bool seen = false;
            int sx = fdiv(wx, 16), sz = fdiv(wz, 16);
            // (level 0's columns by their place in the ring: the window slides over it)
            if (inWindow(sx, VG_MIN_SY, sz) && known[0][size_t(pmod(sz, D)) * D + size_t(pmod(sx, D))]) seen = true;
            for (int k = 1; k <= activeCoarse_ && !seen; k++) {
                const Level& L = lv_[k];
                if (!L.centred) continue;
                int cx = fdiv(wx, 16 << k), cz = fdiv(wz, 16 << k);
                if (L.inWindow(cx, cz) && known[k][size_t(cz - L.oz) * L.D + size_t(cx - L.ox)]) seen = true;
            }
            if (!seen) best = d;
        }
    c.reach = best;
    {
        constexpr int FC = 32, FR = 1500, FN = 2 * FR / FC;
        for (int j = 0; j < FN; j++)
            for (int i = 0; i < FN; i++) {
                float dx = float((i - FN / 2) * FC + FC / 2), dz = float((j - FN / 2) * FC + FC / 2);
                float d = std::sqrt(dx * dx + dz * dz);
                if (d > float(FR)) continue;
                int wx = camB_[0] + int(dx), wz = camB_[2] + int(dz);
                bool seen = false;
                int sx = fdiv(wx, 16), sz = fdiv(wz, 16);
                if (inWindow(sx, VG_MIN_SY, sz) && known[0][size_t(pmod(sz, D)) * D + size_t(pmod(sx, D))]) seen = true;
                for (int k = 1; k <= activeCoarse_ && !seen; k++) {
                    const Level& L = lv_[k];
                    if (!L.centred) continue;
                    int cx = fdiv(wx, 16 << k), cz = fdiv(wz, 16 << k);
                    if (L.inWindow(cx, cz) && known[k][size_t(cz - L.oz) * L.D + size_t(cx - L.ox)]) seen = true;
                }
                if (!seen) {
                    c.nearHoles++;
                    c.nearReach = std::min(c.nearReach, d);
                }
            }
    }
    return c;
}

std::string VoxGrid::coverageJson() const {
    auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> cl(covMu_);
    if (covAt_ == std::chrono::steady_clock::time_point{} || now - covAt_ > std::chrono::milliseconds(1500)) {
        covCache_ = coverage();
        covAt_ = now;
    }
    const Coverage& c = covCache_;
    std::string o = "\"coverage\":{\"farReach\":" + std::to_string(int(c.reach)) + ",\"nearHoles\":" + std::to_string(c.nearHoles) + ",\"l0Near6\":" + std::to_string(c.l0Near6) +
                    ",\"nearReach\":" + std::to_string(int(c.nearReach)) + ",\"holes\":[";
    for (int k = 0; k <= activeCoarse_; k++) o += (k ? "," : "") + std::to_string(c.holes[k]);
    o += "],\"partial\":[";
    for (int k = 0; k <= activeCoarse_; k++) o += (k ? "," : "") + std::to_string(c.partial[k]);
    o += "],\"columns\":[";
    for (int k = 0; k <= activeCoarse_; k++) o += (k ? "," : "") + std::to_string(c.columns[k]);
    // (the pools: slots used / of, per coarse level)
    o += "],\"slotsUsed\":[";
    {
        std::lock_guard<std::mutex> lock(mu_);
        o += std::to_string(slotsUsed_);
        for (int k = 1; k <= activeCoarse_; k++) o += "," + std::to_string(lv_[k].used);
        o += "],\"slotCap\":[" + std::to_string(slotCap);
        for (int k = 1; k <= activeCoarse_; k++) o += "," + std::to_string(lv_[k].cap);
    }
    return o + "]},";
}

VoxGrid::InfoSnap VoxGrid::infoSnap() const {
    std::unique_lock<std::mutex> l(mu_, std::try_to_lock);
    if (!l.owns_lock()) {  // (busy: the ingest's put, the frame's take ...: the last snapshot, never a wait)
        std::lock_guard<std::mutex> g(infoMu_);
        return infoCache_;
    }
    InfoSnap prev;
    {
        std::lock_guard<std::mutex> g(infoMu_);
        prev = infoCache_;
    }
    InfoSnap s;
    s.valid = true;
    s.res = residencyLocked();
    s.st = statsLocked();
    s.pending = pendingLocked();
    s.bakeQueued = bake_.size();
    s.chunkWords = size_t(D) * H * D;
    s.totalSlots = slotCap;
    s.levels = 1 + activeCoarse_;
    s.slotsUsed[0] = slotsUsed_;
    s.slotCap[0] = slotCap;
    for (int k = 1; k < VG_LEVELS; k++) {
        s.coarseLost += lostCells_[k].size();
        s.coarseGiven += cgiven_[k].size();
    }
    for (int k = 1; k <= activeCoarse_; k++) {
        s.chunkWords += size_t(lv_[k].D) * lv_[k].H * lv_[k].D;
        s.totalSlots += lv_[k].cap;
        s.lv[k] = levelInfoLocked(k);
        s.slotsUsed[k] = lv_[k].used;
        s.slotCap[k] = lv_[k].cap;
    }
    s.wantedAgain = wantedAgain_;
    s.wantedHeld = wantedHeld_;
    s.wantedReasked = reasked_;
    s.rankCut = rankCut_;
    s.rankWaitBest = rankWaitBest_;
    s.rankAdmits = rankAdmits_.size();
    s.rankEvicts = rankEvicts_.size();
    s.wantedPending = gpuWanted_.size();
    auto now = std::chrono::steady_clock::now();
    if (prev.valid && prev.covAt != std::chrono::steady_clock::time_point{} && now - prev.covAt < std::chrono::milliseconds(1500)) {
        s.cov = prev.cov;
        s.covAt = prev.covAt;
    } else {
        s.cov = coverageLocked();
        s.covAt = now;
    }
    l.unlock();
    std::lock_guard<std::mutex> g(infoMu_);
    infoCache_ = s;
    return s;
}

std::string VoxGrid::refineJson(const InfoSnap& s) {
    char b[420];
    std::snprintf(b, sizeof b, "\"coarseLost\":%zu,\"coarseGiven\":%zu,\"wantedAsked\":%llu,\"wantedHeld\":%llu,\"wantedReasked\":%llu,\"rankCut\":%u,\"rankWaitBest\":%u,\"rankAdmits\":%zu,\"rankEvicts\":%zu,\"wantedPending\":%zu,", s.coarseLost, s.coarseGiven,
                  (unsigned long long)s.wantedAgain, (unsigned long long)s.wantedHeld, (unsigned long long)s.wantedReasked, s.rankCut, s.rankWaitBest,
                  s.rankAdmits, s.rankEvicts, s.wantedPending);
    const Coverage& c = s.cov;
    int active = s.levels - 1;
    std::string o = std::string(b) + "\"coverage\":{\"farReach\":" + std::to_string(int(c.reach)) + ",\"nearHoles\":" + std::to_string(c.nearHoles) +
                    ",\"l0Near6\":" + std::to_string(c.l0Near6) + ",\"nearReach\":" + std::to_string(int(c.nearReach)) + ",\"holes\":[";
    for (int k = 0; k <= active; k++) o += (k ? "," : "") + std::to_string(c.holes[k]);
    o += "],\"partial\":[";
    for (int k = 0; k <= active; k++) o += (k ? "," : "") + std::to_string(c.partial[k]);
    o += "],\"columns\":[";
    for (int k = 0; k <= active; k++) o += (k ? "," : "") + std::to_string(c.columns[k]);
    o += "],\"slotsUsed\":[";
    for (int k = 0; k <= active; k++) o += (k ? "," : "") + std::to_string(s.slotsUsed[k]);
    o += "],\"slotCap\":[";
    for (int k = 0; k <= active; k++) o += (k ? "," : "") + std::to_string(s.slotCap[k]);
    return o + "]},";
}

float VoxGrid::priority(int sx, int sy, int sz) const {
    // the eye (the camera's block) to the section's box
    float cx = float(sx * 16 + 8 - camB_[0]), cy = float(sy * 16 + 8 - camB_[1]), cz = float(sz * 16 + 8 - camB_[2]);
    float ex = std::max(std::fabs(cx) - 8.0f, 0.0f), ey = std::max(std::fabs(cy) - 8.0f, 0.0f), ez = std::max(std::fabs(cz) - 8.0f, 0.0f);
    float d = std::sqrt(ex * ex + ey * ey + ez * ez);
    if (d > reach_) return 1e30f;
    if (d <= NEAR_BALL) return d;
    // in the view's cone (its half diagonal widened by the section's own half diagonal, 13.9 blocks, as seen from here)
    float c = std::sqrt(cx * cx + cy * cy + cz * cz);
    float cosA = (cx * fwd_[0] + cy * fwd_[1] + cz * fwd_[2]) / c;
    float sinM = std::min(1.0f, 13.9f / c), cosM = std::sqrt(1.0f - sinM * sinM);
    float sinV = std::sqrt(std::max(0.0f, 1.0f - cosView_ * cosView_));
    return cosA >= cosView_ * cosM - sinV * sinM ? d : d * VIEW_OUT;
}

VoxGrid::Residency VoxGrid::residency() const {
    std::lock_guard<std::mutex> lock(mu_);
    return residencyLocked();
}

VoxGrid::Residency VoxGrid::residencyLocked() const {
    Residency r;
    r.resident = slotsUsed_;
    r.cap = slotCap;
    r.uniform = uniform_;
    r.waiting = standby_.size();
    r.gpuWaiting = gpuStandby_.size();
    r.coarseWaiting = cpending_.size();
    r.evicted = stats_.evicted;
    r.misses = misses_;
    r.gpuUniform = gpuUniformHits_;
    r.cutoff = std::min(cutoff_, reach_);
    r.reach = reach_;
    // (measured against the view as it is, not the ranking's predicted one: atlas_info's path, not the frame's)
    float nm = 1e30f;
    if (gpuRank) {
        for (auto& [k, v] : standby_) nm = std::min(nm, priority(k.x, k.y, k.z));
        for (auto& [k, v] : gpuStandby_) nm = std::min(nm, priority(k.x, k.y, k.z));
        r.nearestMissing = nm < 1e29f ? uint32_t(nm) : 0u;
    } else {
        r.nearestMissing = nearestMissing_;
    }
    return r;
}

std::vector<std::array<int32_t, 4>> VoxGrid::takeGpuWanted() {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<std::array<int32_t, 4>> r;
    r.swap(gpuWanted_);
    return r;
}

void VoxGrid::forceReplace() {
    std::lock_guard<std::mutex> lock(mu_);
    ++windowRevision_;
    centred_ = false;
    for (int k = 1; k <= activeCoarse_; k++) lv_[k].centred = false;
}

void VoxGrid::reportDropped(const std::array<int32_t, 4>& r) {
    if (gpuDropped_.size() >= 65536) gpuDropped_.erase(gpuDropped_.begin(), gpuDropped_.begin() + 32768);
    gpuDropped_.push_back(r);
}

void VoxGrid::reportWanted(const std::array<int32_t, 4>& r) {
    if (gpuWanted_.size() >= 65536) gpuWanted_.erase(gpuWanted_.begin(), gpuWanted_.begin() + 32768);
    gpuWanted_.push_back(r);
}

void VoxGrid::packedResults(const std::vector<World::PackDone>& done) {
    // (no grid lock: the world's own); the sections edit layers made over their bases come back to the grid
    World::Made made;
    if (world_ && !done.empty()) world_->packedDone(done, &made);
    for (auto& [k, s] : made) put(k.x, k.y, k.z, VgIds(s, &s->mats));
}

void VoxGrid::gpuPendingOffsets(uint64_t buffer, std::vector<uint64_t>& out) const {
    out.clear();
    std::lock_guard<std::mutex> lock(mu_);
    for (auto& [k, g] : gpuRef_)
        if (g.buffer == buffer) out.push_back(g.offset);
    for (auto& [k, g] : bake_)
        if (g.buffer == buffer) out.push_back(g.offset);
    for (int k = 1; k < VG_LEVELS; k++)
        for (auto& [c, g] : cgiven_[k])
            if (g.kind == VG_GIVEN_MIXED && !g.packed && g.ref.buffer == buffer) out.push_back(g.ref.offset);
}

float VoxGrid::chunkPriority(int k, int cx, int cy, int cz) const {
    // the eye to the chunk's box (16 << k blocks a side), x VIEW_OUT outside the view's cone (as priority(): one rule)
    float side = float(16 << k), h = side * 0.5f;
    float cxr = float(cx) * side + h - float(camB_[0]), cyr = float(cy) * side + h + float(VG_MIN_SY * 16) - float(camB_[1]);
    float czr = float(cz) * side + h - float(camB_[2]);
    float ex = std::max(std::fabs(cxr) - h, 0.0f), ey = std::max(std::fabs(cyr) - h, 0.0f), ez = std::max(std::fabs(czr) - h, 0.0f);
    float d = std::sqrt(ex * ex + ey * ey + ez * ez);
    if (d <= NEAR_BALL) return d;
    float c = std::sqrt(cxr * cxr + cyr * cyr + czr * czr);
    float cosA = (cxr * fwd_[0] + cyr * fwd_[1] + czr * fwd_[2]) / c;
    float sinM = std::min(1.0f, h * 1.732f / c), cosM = std::sqrt(1.0f - sinM * sinM);
    float sinV = std::sqrt(std::max(0.0f, 1.0f - cosView_ * cosView_));
    return cosA >= cosView_ * cosM - sinV * sinM ? d : d * VIEW_OUT;
}

size_t VoxGrid::ingestCoarseGpu(int k, uint64_t buffer, const uint64_t* offsets, const int32_t* pos, const uint32_t* kinds,
                                size_t count, uint8_t* accepted) {
    if (accepted) std::memset(accepted, 0, count);
    if (!enabled) return 0;
    std::lock_guard<std::mutex> lock(mu_);
    if (k < 1 || k > activeCoarse_ || !lv_[k].centred) return 0;
    Level& L = lv_[k];
    size_t n = 0;
    for (size_t i = 0; i < count; i++) {
        int cx = pos[3 * i], cy = pos[3 * i + 1], cz = pos[3 * i + 2];
        uint32_t kind = kinds[i] & 255u;
        if (!L.inWindow(cx, cz) || cy < 0 || cy >= L.H || kind > VG_GIVEN_UNIFORM) {
            continue;
        }
        if (kind == VG_GIVEN_MIXED && !buffer) continue;
        if (kind == VG_GIVEN_UNIFORM && (kinds[i] >> 8) > 0xFFFFu) continue;
        bool sections = (k <= 4) && (L.colKnown[L.ccol(cx, cz)] != 0);
        if (sections) continue;
        // (a mixed one only where a slot would hold it: counted taken, then dropped at the batch, the tile went again
        // and again)
        if (kind == VG_GIVEN_MIXED && L.slotOf[L.cell(cx, cy, cz)] == ~0u && !coarseRoom(L, chunkPriority(k, cx, cy, cz))) {
            continue;
        }
        CGiven g;
        g.kind = kind;
        g.mat = kinds[i] >> 8;
        g.ref = {buffer, kind == VG_GIVEN_MIXED ? offsets[i] : 0};
        g.cx = cx, g.cy = cy, g.cz = cz;
        cgiven_[k][L.cell(cx, cy, cz)] = g;  // (the newest replaces one waiting)
        if (accepted) accepted[i] = 1;
        n++;
    }
    return n;
}

void VoxGrid::gpuUniform(const std::vector<UniformHit>& hits) {
    std::lock_guard<std::mutex> lock(mu_);
    for (const UniformHit& h : hits) {
        // (only the job it was found for: the cell not rewritten since, its slot still its own)
        if (h.chunk >= slotOf_.size() || h.chunk >= cellGen_.size() || h.slot >= slotChunk_.size()) continue;
        if (slotOf_[h.chunk] != h.slot || slotChunk_[h.slot] != h.chunk || cellGen_[h.chunk] != h.gen || state_[h.chunk] != VG_MIXED) continue;
        freeSlots_.push_back(h.slot);
        slotChunk_[h.slot] = ~0u;
        slotCpu_[h.slot] = 0;
        slotOf_[h.chunk] = ~0u;
        slotsUsed_--;
        // (the build wrote its word already: VG_UNIFORM with the id, or VG_EMPTY)
        state_[h.chunk] = uint8_t(h.id ? VG_UNIFORM : VG_EMPTY);
        if (h.id && h.chunk < uniId_.size()) uniId_[h.chunk] = uint16_t(h.id);
        if (h.id) uniform_++;
        gpuUniformHits_++;
        rankDue_ = true;
    }
}

void VoxGrid::dropOutside() {
    auto out = [&](const Key& k) { return !inWindow(k.x, k.y, k.z); };
    for (auto it = standby_.begin(); it != standby_.end();) it = out(it->first) ? standby_.erase(it) : std::next(it);
    for (auto it = gpuStandby_.begin(); it != gpuStandby_.end();) it = out(it->first) ? gpuStandby_.erase(it) : std::next(it);
    for (auto it = gpuCandDirty_.begin(); it != gpuCandDirty_.end();) it = out(*it) ? gpuCandDirty_.erase(it) : std::next(it);
    for (auto it = wantedAt_.begin(); it != wantedAt_.end();) it = out(it->first) ? wantedAt_.erase(it) : std::next(it);
    for (auto it = gpuUniformHint_.begin(); it != gpuUniformHint_.end();)
        it = pending_.count(it->first) || cpending_.count(it->first) ? std::next(it) : gpuUniformHint_.erase(it);
    auto outsideAll = [&](const Key& key) {
        if (inWindow(key.x, key.y, key.z)) return false;
        for (int k = 1; k <= activeCoarse_; k++) {
            const Level& L = lv_[k];
            if (L.centred && L.inWindow(fdiv(key.x, L.span()), fdiv(key.z, L.span()))) return false;
        }
        return true;
    };
    for (auto it = inputRevision_.begin(); it != inputRevision_.end();)
        it = outsideAll(it->first) ? inputRevision_.erase(it) : std::next(it);
    for (auto it = lookupWanted_.begin(); it != lookupWanted_.end();)
        it = outsideAll(*it) ? lookupWanted_.erase(it) : std::next(it);
    for (auto it = worldSources_.begin(); it != worldSources_.end();)
        it = outsideAll(*it) ? worldSources_.erase(it) : std::next(it);

}

int64_t VoxGrid::viewDist(int64_t dx, int64_t dz) const {
    int64_t d2 = dx * dx + dz * dz;
    // behind the camera: as if twice as far (what it sees first)
    float dot = float(dx) * viewX_ + float(dz) * viewZ_;
    if (d2 > 4 && dot < -0.2f * std::sqrt(float(d2))) d2 *= 4;
    return d2;
}

size_t VoxGrid::ingestGpu(uint64_t buffer, const uint64_t* offsets, const int32_t* pos, size_t count, uint8_t* accepted,
                          const uint32_t* uniform) {
    if (accepted) std::memset(accepted, 0, count);
    if (!enabled || !buffer) return 0;
    std::lock_guard<std::mutex> lock(mu_);
    size_t n = 0;
    for (size_t i = 0; i < count; i++) {
        int sx = pos[3 * i], sy = pos[3 * i + 1], sz = pos[3 * i + 2];
        bool level0 = centred_ && inWindow(sx, sy, sz);
        uint32_t uni = uniform ? uniform[i] : VG_NOT_UNIFORM;
        if (uni != VG_NOT_UNIFORM && uni > 0xFFFFu) uni = VG_NOT_UNIFORM;  // (ids are 16 bits)
        // (an edited section: the host's copy stands, the generator's base never replaces it; or the layer waits for
        // this base: packed on the GPU at its build, the edits applied on the host)
        int need = 0;
        if (world_ && !world_->tryLayerNeed({0, sx, sy, sz}, need)) continue;
        if (level0) {
            gpuStandby_.erase({sx, sy, sz});
            gpuCandDirty_.erase({sx, sy, sz});
        }
        if (need == 1) level0 = false;
        if (need == 2 && level0) baseFetch_.insert({sx, sy, sz});
        if (level0 && uni == VG_NOT_UNIFORM && slotOf_[phys(sx, sy, sz)] == ~0u) {
            // a full pool takes a new one only within its cut-off; past the footprint's reach level 0 wants none: it
            // waits, asked for again once it ranks (takeGpuWanted)
            float p = priority(sx, sy, sz);
            bool asked = wantedAt_.count({sx, sy, sz}) != 0;  // (asked for: a slot was made for it)
            if (p >= 1e29f || (!asked && slotsUsed_ >= slotCap && freeSlots_.empty() && p > cutoff_)) {
                gpuStandby_[{sx, sy, sz}] = 0;
                if (gpuRank) gpuCandDirty_.insert({sx, sy, sz});
                level0 = false;
            }
        }
        uint32_t levels = 0;
        for (int k = 1; k <= std::min(4, activeCoarse_); k++) {  // (levels 5-6: made from the level below)
            const Level& L = lv_[k];
            int s = L.span();
            if (L.centred && L.inWindow(fdiv(sx, s), fdiv(sz, s)) && sy >= VG_MIN_SY && (sy - VG_MIN_SY) / s < L.H) levels |= 1u << k;
        }
        if (!level0 && !levels) continue;  // (outside every window, or before the first frame: not taken)
        Key key{sx, sy, sz};
        inputRevision_[key] = ++inputSerial_;
        worldSources_.erase(key);
        if (accepted) accepted[i] = 1;
        n++;
        // the levels that will hold it (it is let go, takeGpuDropped, once none does)
        uint32_t& hold = gpuHold_[key];
        if (!hold) gpuCol_[colKey(sx, sz)].push_back(int16_t(sy));
        hold |= levels | (level0 ? 1u : 0u);
        gpuRef_[key] = {buffer, offsets[i]};
        if (uni != VG_NOT_UNIFORM) gpuUniformHint_[key] = uni;
        else gpuUniformHint_.erase(key);
        if (level0) pending_[key] = VgSrc{};  // (none: its ids are gpuRef_'s)
        if (levels) {
            CPending& p = cpending_[key];
            p.ids = VgSrc{};
            p.levels |= levels;
            ++cpendingRev_;
        }
    }
    return n;
}

size_t VoxGrid::gpuSectionsPending() const {
    std::lock_guard<std::mutex> lock(mu_);
    size_t n = gpuRef_.size();
    for (int k = 1; k < VG_LEVELS; k++) n += cgiven_[k].size();
    return n;
}

std::vector<std::array<int32_t, 4>> VoxGrid::takeGpuDropped() {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<std::array<int32_t, 4>> r;
    r.swap(gpuDropped_);
    return r;
}

void VoxGrid::gpuLetGo(int sx, int sy, int sz, uint32_t bits) {
    auto it = gpuHold_.find({sx, sy, sz});
    if (it == gpuHold_.end() || !(it->second & bits)) return;
    it->second &= ~bits;
    if (it->second) return;
    gpuHold_.erase(it);
    reportDropped({0, sx, sy, sz});
    auto c = gpuCol_.find(colKey(sx, sz));
    if (c != gpuCol_.end()) {
        c->second.erase(std::remove(c->second.begin(), c->second.end(), int16_t(sy)), c->second.end());
        if (c->second.empty()) gpuCol_.erase(c);
    }
}

void VoxGrid::gpuLetGoColumn(int sx, int sz, uint32_t bits) {
    auto c = gpuCol_.find(colKey(sx, sz));
    if (c == gpuCol_.end()) return;
    std::vector<int16_t> ys = c->second;  // (gpuLetGo edits the list)
    for (int16_t sy : ys) gpuLetGo(sx, sy, sz, bits);
}

void VoxGrid::gpuLetGoAll(uint32_t bits) {
    std::vector<Key> keys;
    for (auto& [k, m] : gpuHold_)
        if (m & bits) keys.push_back(k);
    for (const Key& k : keys) gpuLetGo(k.x, k.y, k.z, bits);
}

void VoxGrid::put(int sx, int sy, int sz, const uint16_t* mats) {
    if (!enabled) return;
    auto a = std::make_shared<std::array<uint16_t, 4096>>();
    std::memcpy(a->data(), mats, 4096 * 2);
    put(sx, sy, sz, VgIds(std::move(a)));
}

void VoxGrid::put(int sx, int sy, int sz, VgIds a) {
    if (!enabled || !a) return;
    std::lock_guard<std::mutex> lock(mu_);
    putLocked(sx, sy, sz, std::move(a));
}

void VoxGrid::putPackedBatch(const PackedEntry* entries, size_t count) {
    if (!enabled || !entries || count == 0) return;
    std::lock_guard<std::mutex> lock(mu_);
    for (size_t i = 0; i < count; i++) {
        if (!entries[i].words) continue;
        putSrc(entries[i].sx, entries[i].sy, entries[i].sz, VgSrc{nullptr, entries[i].words, entries[i].uni});
    }
}

void VoxGrid::putPacked(int sx, int sy, int sz, VgPacked words) {
    if (!enabled || !words) return;
    std::lock_guard<std::mutex> lock(mu_);
    putSrc(sx, sy, sz, VgSrc{nullptr, std::move(words)});
}

void VoxGrid::bakeGpu(uint64_t buffer, const uint64_t* offsets, const int32_t* pos, size_t count, int level) {
    if (!enabled || !buffer) return;
    std::lock_guard<std::mutex> lock(mu_);
    for (size_t i = 0; i < count; i++)
        bake_.push_back({Key3{level, pos[3 * i], pos[3 * i + 1], pos[3 * i + 2]}, VgGpuRef{buffer, offsets[i]}});
}

size_t VoxGrid::ingestCoarseHost(int k, const int32_t* pos, const uint32_t* kinds, const VgPacked* packed, size_t count,
                                 uint8_t* accepted) {
    if (accepted) std::memset(accepted, 0, count);
    if (!enabled) return 0;
    std::lock_guard<std::mutex> lock(mu_);
    if (k < 1 || k > activeCoarse_ || !lv_[k].centred) return 0;
    Level& L = lv_[k];
    size_t n = 0;
    for (size_t i = 0; i < count; i++) {
        int cx = pos[3 * i], cy = pos[3 * i + 1], cz = pos[3 * i + 2];
        uint32_t kind = kinds[i] & 255u;
        if (!L.inWindow(cx, cz) || cy < 0 || cy >= L.H || kind > VG_GIVEN_UNIFORM) continue;
        if (kind == VG_GIVEN_MIXED && !packed[i]) continue;
        if (kind == VG_GIVEN_UNIFORM && (kinds[i] >> 8) > 0xFFFFu) continue;
        bool sections = (k <= 4) && (L.colKnown[L.ccol(cx, cz)] != 0);
        if (sections) continue;
        if (kind == VG_GIVEN_MIXED && L.slotOf[L.cell(cx, cy, cz)] == ~0u && !coarseRoom(L, chunkPriority(k, cx, cy, cz))) continue;
        CGiven g;
        g.kind = kind;
        g.mat = kinds[i] >> 8;
        if (kind == VG_GIVEN_MIXED) g.packed = packed[i];
        g.cx = cx, g.cy = cy, g.cz = cz;
        cgiven_[k][L.cell(cx, cy, cz)] = g;
        if (accepted) accepted[i] = 1;
        n++;
    }
    return n;
}

void VoxGrid::putLocked(int sx, int sy, int sz, VgIds a) {
    if (!enabled || !a) return;
    putSrc(sx, sy, sz, VgSrc{std::move(a), nullptr});
}

void VoxGrid::putSrc(int sx, int sy, int sz, VgSrc a) {
    if (!enabled || (!a.ids && !a.packed)) return;
    // before the window is placed the world keeps them (frame() reads them from there); outside it they wait there too
    bool level0 = centred_ && inWindow(sx, sy, sz);
    uint32_t levels = 0;
    for (int k = 1; k <= std::min(4, activeCoarse_); k++) {  // (levels 5-6: made from the level below)
        const Level& L = lv_[k];
        int s = L.span();
        if (L.centred && L.inWindow(fdiv(sx, s), fdiv(sz, s)) && sy >= VG_MIN_SY && (sy - VG_MIN_SY) / s < L.H) levels |= 1u << k;
    }
    if (!level0 && !levels) return;
    inputRevision_[{sx, sy, sz}] = ++inputSerial_;
    worldSources_.erase({sx, sy, sz});
    // (one entry a section whatever its source: the newest ids replace what waited)
    standby_.erase({sx, sy, sz});
    gpuStandby_.erase({sx, sy, sz});
    gpuCandDirty_.erase({sx, sy, sz});
    gpuUniformHint_.erase({sx, sy, sz});
    if (!a.gpu() && a.uni == VG_UNI_UNSET)
        a.uni = a.packed ? packUniform(a.packed->data()) : uniformIdOf(*a.ids);
    if (level0) pending_[{sx, sy, sz}] = a;
    if (levels) {
        CPending& p = cpending_[{sx, sy, sz}];
        p.ids = std::move(a);
        p.levels |= levels;
        ++cpendingRev_;
    }
    gpuRef_.erase({sx, sy, sz});  // (an upload replaces a GPU section waiting, and holding it is the world's again)
    if (gpuHold_.erase({sx, sy, sz})) {
        auto c = gpuCol_.find(colKey(sx, sz));
        if (c != gpuCol_.end()) {
            c->second.erase(std::remove(c->second.begin(), c->second.end(), int16_t(sy)), c->second.end());
            if (c->second.empty()) gpuCol_.erase(c);
        }
    }
}

void VoxGrid::origin(int32_t o[4]) const {
    std::lock_guard<std::mutex> lock(mu_);
    o[0] = ox_;
    o[1] = VG_MIN_SY;
    o[2] = oz_;
    // (bit 2: it holds blocks somewhere, or will once this frame's batch is built: the backend takes it after this)
    o[3] = active() ? (slotsUsed_ > 0 || uniform_ > 0 || !pending_.empty() ? 3 : 1) : 0;
}

size_t VoxGrid::pending() const {
    std::lock_guard<std::mutex> lock(mu_);
    return pendingLocked();
}

size_t VoxGrid::pendingLocked() const {
    bool freeSlot = nextSlot_ < slotCap || !freeSlots_.empty();
    // (waiting with free slots: until a ranking says none in reach waits)
    size_t waiting = gpuRank && freeSlot && (!rankSeen_ || rankWaitBest_ < 255) ? standby_.size() : 0;
    return pending_.size() + waiting + (clearPending_ ? 1 : 0) + (freshColumns_ ? 1 : 0) + (vacated_.empty() ? 0 : 1);
}

VoxGrid::Stats VoxGrid::stats() const {
    std::lock_guard<std::mutex> lock(mu_);
    return statsLocked();
}

VoxGrid::Stats VoxGrid::statsLocked() const {
    Stats s = stats_;
    s.ox = ox_;
    s.oz = oz_;
    s.camSx = camSx_;
    s.camSz = camSz_;
    s.pending = pending_.size();
    s.freshColumns = freshColumns_;
    s.slots = slotsUsed_;
    return s;
}

void VoxGrid::frame(const int32_t camBlock[3], World& world) {
    if (!enabled) return;
    std::unique_lock<std::mutex> lock(mu_);
    camSx_ = fdiv(camBlock[0], 16);
    camSz_ = fdiv(camBlock[2], 16);
    for (int i = 0; i < 3; i++) camB_[i] = camBlock[i];
    // level 0's reach: where a pixel covers FOOT blocks (ATLAS_VOXGRID_FOOT, 2: level 0's share of the continuous
    // level, floor(log2(d x angle) + bias + xi), is gone there)
    static const float foot = [] {
        const char* e = std::getenv("ATLAS_VOXGRID_FOOT");
        return e ? std::clamp(float(std::atof(e)), 0.25f, 64.0f) : 2.0f;
    }();
    // (filtered: each step of the reach is real streaming work, and the render scale and LOD bias the adaptive quality
    // moves would take it from 126 to 1208 blocks in 8 s and back in one: up at most ATLAS_REACH_UP (10 %) a second, down
    // at most 30 %, changes within 10 % ignored)
    float want = pixelAngle_ > 0.0f ? foot * std::exp2(-levelBias_) / pixelAngle_ : 1e30f;
    reachWant_ = want;
    auto rnow = std::chrono::steady_clock::now();
    double rdt = std::clamp(std::chrono::duration<double>(rnow - reachAt_).count(), 0.0, 1.0);
    reachAt_ = rnow;
    reach_ = reachStep(reach_, want, rdt);
    stats_.frames++;
    // the window follows the camera a few sections behind (each move frees the columns it leaves and reads the ones
    // it enters: the cells keep their place in memory, chunk (x, y, z) at (x mod D, y, z mod D))
    int cx = ox_ + D / 2, cz = oz_ + D / 2;
    if (!centred_ || std::abs(camSx_ - cx) > FOLLOW || std::abs(camSz_ - cz) > FOLLOW) shift(camSx_ - D / 2, camSz_ - D / 2, world);
    // (placed, or a jump past the whole window: read here at once, a few ms this one frame, nothing of the window known;
    // a shift's strips are the update thread's, here only when it has not come for half a second)
    bool readNow = freshColumns_ && (freshColumns_ == uint32_t(D * D) || stats_.frames - stats_.lastShiftFrame > 30);
    // the coarse levels follow too; placed (or a jump) they read the world here at once when its lock is free
    camSy_ = fdiv(camBlock[1], 16);
    if (coarseLevels > 0 && !laidOut_) layOut();
    bool placed = false;
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        placeLevel(L, camSx_, camSz_);
        placed |= L.freshCount == uint32_t(L.D * L.D);
    }
    lock.unlock();
    if (readNow || placed) refillSnapshot(world, false);
}

void VoxGrid::prepare(World& world, const double centre[3]) {
    int x0 = 0, z0 = 0, x1 = 0, z1 = 0;
    bool needed = false;
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (!enabled) return;
        int sx = int(std::floor(centre[0] / 16)), sz = int(std::floor(centre[2] / 16));
        auto window = [&](int ox, int oz, int side, int span) {
            if (!needed) { x0 = ox * span; z0 = oz * span; x1 = (ox + side) * span; z1 = (oz + side) * span; }
            else { x0 = std::min(x0, ox * span); z0 = std::min(z0, oz * span);
                   x1 = std::max(x1, (ox + side) * span); z1 = std::max(z1, (oz + side) * span); }
            needed = true;
        };
        if (!centred_ || std::abs(sx - (ox_ + D / 2)) > FOLLOW || std::abs(sz - (oz_ + D / 2)) > FOLLOW)
            window(sx - D / 2, sz - D / 2, D, 1);
        int levels = std::clamp(coarseLevels, 0, VG_LEVELS - 1);
        for (int k = 1; k <= levels; ++k) {
            int span = 1 << k, cx = fdiv(sx, span), cz = fdiv(sz, span), follow = std::max(1, 8 >> k);
            const Level& level = lv_[k];
            if (!laidOut_ || !level.centred || std::abs(cx - (level.ox + level.D / 2)) > follow ||
                std::abs(cz - (level.oz + level.D / 2)) > follow)
                window(cx - coarseD / 2, cz - coarseD / 2, coarseD, span);
        }
    }
    if (needed) {
        World::SectionSnapshot snapshot;
        world.snapshotSections(x0, z0, x1, z1, true, snapshot);  // ticket I/O without either shared lock
    }
}

void VoxGrid::refill(World& world) {
    if (!enabled) return;
    World::Made made;
    world.takeGridMade(made);  // existing update thread, no grid lock
    {
        std::lock_guard<std::mutex> lock(mu_);
        for (const auto& [k, sec] : made) requestHostRefill(k.x, k.y, k.z);
    }
    refillSnapshot(world, true);
}

// ---- the coarse levels' books ----------------------------------------------------------------------------------

void VoxGrid::layOut() {
    laidOut_ = true;
    activeCoarse_ = enabled ? std::clamp(coarseLevels, 0, VG_LEVELS - 1) : 0;
    uint32_t chunkBase = uint32_t(size_t(D) * H * D), slotBase = slotCap;
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        L.k = k;
        L.D = coarseD;
        L.H = (H + L.span() - 1) / L.span();
        L.cap = coarseCap[k];
        L.chunkBase = chunkBase;
        L.slotBase = slotBase;
        size_t n = size_t(L.D) * L.H * L.D;
        chunkBase += uint32_t(n);
        slotBase += L.cap;
        L.slotOf.assign(n, ~0u);
        L.word.assign(n, VG_UNLOADED);
        L.lost.assign(n, 0);
        L.rock.assign(n, 0);
        L.given.assign(n, 0);
        L.givenMat.assign(n, 0);
        L.touched.assign(n, 0);
        L.dirty.assign(n, 0);
        L.downDirty.assign(n, 0);
        L.slotCell.assign(L.cap, ~0u);
        if (k <= 4) {
            size_t nc = size_t(L.D) * L.D;
            size_t ns = nc * size_t(L.span()) * size_t(L.span());
            L.colRecv.assign(ns, 0ull);
            L.colKnown.assign(nc, 0u);
            L.colMaxHigh.assign(nc, -1);
        }
        L.centred = false;
    }
}

size_t VoxGrid::chunkWords() const {
    std::lock_guard<std::mutex> lock(mu_);
    size_t n = size_t(D) * H * D;
    for (int k = 1; k <= activeCoarse_; k++) n += size_t(lv_[k].D) * lv_[k].H * lv_[k].D;
    return n;
}

uint32_t VoxGrid::totalSlots() const {
    std::lock_guard<std::mutex> lock(mu_);
    uint32_t n = slotCap;
    for (int k = 1; k <= activeCoarse_; k++) n += lv_[k].cap;
    return n;
}

VoxGrid::LevelInfo VoxGrid::levelInfo(int k) const {
    std::lock_guard<std::mutex> lock(mu_);
    return levelInfoLocked(k);
}

VoxGrid::LevelInfo VoxGrid::levelInfoLocked(int k) const {
    LevelInfo i;
    if (k < 1 || k > activeCoarse_) return i;
    const Level& L = lv_[k];
    i.D = L.D;
    i.H = L.H;
    i.chunkBase = L.chunkBase;
    i.slotBase = L.slotBase;
    i.cap = L.cap;
    i.active = L.centred;
    i.ox = L.ox;
    i.oz = L.oz;
    i.used = L.used;
    i.evicted = L.evicted;
    i.overflow = L.overflow;
    i.jumps = L.jumps;
    i.shifts = L.shifts;
    i.ingested = L.ingested;
    for (auto& [key, p] : cpending_) i.pending += (p.levels >> k) & 1u;
    return i;
}

void VoxGrid::levelFrame(int k, int32_t out[4]) const {
    std::lock_guard<std::mutex> lock(mu_);
    out[0] = out[1] = out[2] = out[3] = 0;
    if (k < 1 || k > activeCoarse_ || !lv_[k].centred) return;
    const Level& L = lv_[k];
    out[0] = L.ox;
    out[1] = L.oz;
    out[2] = int32_t(L.chunkBase);
    // (bit 17: it holds blocks somewhere, or may once this frame's batch is built)
    out[3] = L.D | L.H << 8 | 1 << 16 | (L.used > 0 || L.slotless || !cpending_.empty() || !cgiven_[k].empty() ? 1 << 17 : 0);
}

void VoxGrid::placeLevel(Level& L, int camSx, int camSz) {
    int s = L.span();
    int ccx = fdiv(camSx, s), ccz = fdiv(camSz, s);
    int follow = std::max(1, 8 >> L.k);  // (chunks: 4 at level 1, then 2, then 1)
    int nox = ccx - L.D / 2, noz = ccz - L.D / 2;
    if (L.centred && std::abs(ccx - (L.ox + L.D / 2)) <= follow && std::abs(ccz - (L.oz + L.D / 2)) <= follow) return;
    ++windowRevision_;
    size_t n = size_t(L.D) * L.H * L.D;
    // (the generator's chunks the window gives up, held or still waiting: listed to give again when it returns)
    auto letGo = [&](uint32_t c) {
        if (!L.given[c]) return;
        int gx, gy, gz;
        cellCoords(L, c, gx, gy, gz);
        reportDropped({L.k, gx, gy, gz});
        L.given[c] = 0;
    };
    if (!L.centred || std::abs(nox - L.ox) >= L.D || std::abs(noz - L.oz) >= L.D) {
        // placed, or a jump past the window: nothing of it known
        if (L.centred)
            for (uint32_t c = 0; c < uint32_t(n); c++) letGo(c);
        for (auto& [c, g] : cgiven_[L.k]) reportDropped({L.k, g.cx, g.cy, g.cz});
        cgiven_[L.k].clear();
        L.slotOf.assign(n, ~0u);
        L.word.assign(n, VG_UNLOADED);
        L.lost.assign(n, 0);
        L.rock.assign(n, 0);
        L.slotCell.assign(L.cap, ~0u);
        L.freeSlots.clear();
        L.nextSlot = 0;
        L.used = 0;
        L.slotless = false;
        if (L.k <= 4) {
            std::fill(L.colRecv.begin(), L.colRecv.end(), 0ull);
            std::fill(L.colKnown.begin(), L.colKnown.end(), uint16_t(0));
            std::fill(L.colMaxHigh.begin(), L.colMaxHigh.end(), int8_t(-1));
        }
        L.vacated.clear();
        L.fresh.assign(size_t(L.D) * L.D, 1);
        L.freshCount = uint32_t(L.D * L.D);
        L.clearPending = true;
        L.jumps++;
        for (auto& [key, p] : cpending_) p.levels &= ~(1u << L.k);  // (read again from the world)
        ++cpendingRev_;
        gpuLetGoAll(1u << L.k);
    } else {
        // the chunk columns the window leaves: their cells are the entering ones'
        for (int cz = L.oz; cz < L.oz + L.D; cz++)
            for (int cx = L.ox; cx < L.ox + L.D; cx++) {
                if (cx >= nox && cx < nox + L.D && cz >= noz && cz < noz + L.D) continue;
                for (int cy = 0; cy < L.H; cy++) {
                    uint32_t c = L.cell(cx, cy, cz);
                    if (L.slotOf[c] != ~0u) {
                        L.freeSlots.push_back(L.slotOf[c]);
                        L.slotCell[L.slotOf[c]] = ~0u;
                        L.slotOf[c] = ~0u;
                        L.used--;
                    }
                    L.lost[c] = 0;
                    L.rock[c] = 0;
                    letGo(c);
                    if (auto g = cgiven_[L.k].find(c); g != cgiven_[L.k].end()) {
                        reportDropped({L.k, g->second.cx, g->second.cy, g->second.cz});
                        cgiven_[L.k].erase(g);
                    }
                    if (L.word[c] != VG_UNLOADED) L.vacated.push_back(c);
                    L.word[c] = VG_UNLOADED;
                }
                if (L.k <= 4) {
                    size_t cc = L.ccol(cx, cz);
                    if (L.colKnown[cc]) {
                        std::fill_n(&L.colRecv[cc * size_t(s) * size_t(s)], size_t(s) * size_t(s), 0ull);
                        L.colKnown[cc] = 0;
                        L.colMaxHigh[cc] = -1;
                    }
                }
                if (!gpuCol_.empty()) {
                    for (int dz = 0; dz < s; dz++)
                        for (int dx = 0; dx < s; dx++)
                            gpuLetGoColumn(cx * s + dx, cz * s + dz, 1u << L.k);
                }
                size_t col = size_t(pmod(cz, L.D)) * L.D + size_t(pmod(cx, L.D));
                if (!L.fresh[col]) L.freshCount++;
                L.fresh[col] = 1;
            }
        L.shifts++;
    }
    L.ox = nox;
    L.oz = noz;
    L.centred = true;
}

void VoxGrid::requestHostRefill(int sx, int sy, int sz) {
    bool changed = lookupWanted_.insert({sx, sy, sz}).second;
    if (centred_ && inWindow(sx, sy, sz)) {
        size_t col = physCol(sx, sz);
        if (!colFresh_[col]) colFresh_[col] = 1, freshColumns_++, changed = true;
    }
    for (int k = 1; k <= std::min(4, activeCoarse_); k++) {
        Level& level = lv_[k];
        int span = level.span(), cx = fdiv(sx, span), cz = fdiv(sz, span);
        if (!level.centred || !level.inWindow(cx, cz)) continue;
        size_t col = size_t(pmod(cz, level.D)) * level.D + size_t(pmod(cx, level.D));
        if (!level.fresh[col]) level.fresh[col] = 1, level.freshCount++, changed = true;
    }
    if (changed) ++windowRevision_;
}

bool VoxGrid::refreshWorldSource(const Key& key, VgSrc& source) {
    if (!worldSources_.count(key)) return true;
    World::SectionSrc current;
    if (!world_ || !world_->trySectionSource(key.x, key.y, key.z, current) || !current) {
        requestHostRefill(key.x, key.y, key.z);
        return false;
    }
    if ((current.raw && source.ids.get() == &current.raw->mats) ||
        (!current.raw && current.packed && source.packed == current.packed)) {
        return true;
    }
    source = current.raw ? VgSrc{VgIds(current.raw, &current.raw->mats), nullptr} : VgSrc{nullptr, current.packed};
    if (!source.gpu()) source.uni = source.packed ? packUniform(source.packed->data()) : uniformIdOf(*source.ids);
    return true;
}

void VoxGrid::refillSnapshot(World& world, bool wait) {
    int x0 = INT_MAX, z0 = INT_MAX, x1 = INT_MIN, z1 = INT_MIN;
    uint64_t window, input;
    std::vector<Key> wanted;
    const std::atomic<bool>* publishRelease;
    std::atomic<bool>* publishHeld;
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (!wait && !lookupWanted_.empty()) return;
        if (freshColumns_) x0 = ox_, z0 = oz_, x1 = ox_ + D, z1 = oz_ + D;
        for (int k = 1; k <= activeCoarse_; k++) {
            const Level& level = lv_[k];
            if (!level.freshCount) continue;
            int span = level.span();
            x0 = std::min(x0, level.ox * span); z0 = std::min(z0, level.oz * span);
            x1 = std::max(x1, (level.ox + level.D) * span); z1 = std::max(z1, (level.oz + level.D) * span);
        }
        if (x0 >= x1 || z0 >= z1) return;
        window = windowRevision_; input = inputSerial_;
        publishRelease = publishReleaseForTest_; publishHeld = publishHeldForTest_;
        if (wait) wanted.assign(lookupWanted_.begin(), lookupWanted_.end());
    }
    // Cache/standby lookups, cold reads and decoding are all existing update-thread work, with no grid lock.
    for (const Key& key : wanted) {
        World::SectionSrc source;
        if (!world.resolveSectionSource(key.x, key.y, key.z, source)) return;
    }
    World::SectionSnapshot snapshot;
    if (!world.snapshotSections(x0, z0, x1, z1, wait, snapshot)) return;
    std::unordered_set<Key, KeyHash> found;
    for (const auto& [key, sec] : snapshot.entries) found.insert({key.x, key.y, key.z});
    size_t next = 0;
    bool paused = false;
    do {
        std::unique_lock<std::mutex> lock(mu_);
        if (windowRevision_ != window) return;  // moved/reset while reading/publishing; retry work stays fresh
        bool applied = world.applySections(snapshot, [&] {
            size_t end = std::min(next + size_t(128), snapshot.entries.size());
            for (; next < end; ++next) {
                const auto& [key, sec] = snapshot.entries[next];
                Key k{key.x, key.y, key.z};
                auto changed = inputRevision_.find(k);
                if ((changed != inputRevision_.end() && changed->second > input) || gpuRef_.count(k)) continue;
                bool want0 = inWindow(k.x, k.y, k.z) && colFresh_[physCol(k.x, k.z)];
                uint32_t levels = 0;
                for (int level = 1; level <= std::min(4, activeCoarse_); level++) {
                    const Level& L = lv_[level];
                    int span = L.span(), cx = fdiv(k.x, span), cz = fdiv(k.z, span);
                    if (L.freshCount && L.inWindow(cx, cz) && k.y >= VG_MIN_SY && (k.y - VG_MIN_SY) / span < L.H &&
                        L.fresh[size_t(pmod(cz, L.D)) * L.D + size_t(pmod(cx, L.D))]) levels |= 1u << level;
                }
                if (!want0 && !levels) continue;
                VgSrc source = sec.raw ? VgSrc{VgIds(sec.raw, &sec.raw->mats), nullptr} : VgSrc{nullptr, sec.packed};
                if (!source.gpu()) source.uni = source.packed ? packUniform(source.packed->data()) : uniformIdOf(*source.ids);
                if (want0) {
                    pending_[k] = source; worldSources_.insert(k);
                }
                if (levels) { CPending& p = cpending_[k]; p.ids = std::move(source); p.levels |= levels; worldSources_.insert(k); ++cpendingRev_; }
            }
            if (next != snapshot.entries.size()) return;
            for (const Key& key : wanted) {
                auto changed = inputRevision_.find(key);
                if (changed != inputRevision_.end() && changed->second > input) continue;
                lookupWanted_.erase(key);
                // An actual absent source is forgotten only after the background read resolved, never on try-lock miss.
                if (!found.count(key)) standby_.erase(key);
            }
            if (freshColumns_) {
                std::fill(colFresh_.begin(), colFresh_.end(), uint8_t(0)); freshColumns_ = 0;
                stats_.refills++; stats_.lastRefillFrame = stats_.frames;
            }
            for (int k = 1; k <= activeCoarse_; k++) {
                Level& L = lv_[k];
                std::fill(L.fresh.begin(), L.fresh.end(), uint8_t(0)); L.freshCount = 0;
            }
        });
        if (!applied) return;  // failed revision/try-lock validation never consumes fresh work
        lock.unlock();
        if (next < snapshot.entries.size()) {
            if (!paused && publishRelease && publishHeld) {
                paused = true; publishHeld->store(true);
                while (!publishRelease->load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            std::this_thread::yield();
        }
    } while (next < snapshot.entries.size());
}

void VoxGrid::shift(int nox, int noz, World& world) {
    ++windowRevision_;
    size_t n = size_t(D) * H * D;
    if (!centred_ || std::abs(nox - ox_) >= D || std::abs(noz - oz_) >= D) {
        // placed (or a jump past the whole window): every chunk unknown, every column read again
        slotOf_.assign(n, ~0u);
        state_.assign(n, uint8_t(VG_UNLOADED));
        uniId_.assign(n, 0);
        colTop_.assign(size_t(D) * D, NO_TOP);
        colFresh_.assign(size_t(D) * D, uint8_t(1));
        freshColumns_ = uint32_t(D * D);
        slotChunk_.assign(slotCap, ~0u);
        slotCpu_.assign(slotCap, 0);
        if (cellGen_.size() != n) cellGen_.assign(n, 0);  // (never reset: stale findings must not match)
        freeSlots_.clear();
        nextSlot_ = 0;
        slotsUsed_ = 0;
        uniform_ = 0;
        pending_.clear();
        standby_.clear();
        gpuStandby_.clear();
        gpuCandDirty_.clear();
        vacated_.clear();
        clearPending_ = true;
        stats_.jumps++;
        gpuLetGoAll(1u);
    } else {
        // the columns the window leaves: their cells are the entering columns' (the same x mod D, z mod D)
        for (int z = oz_; z < oz_ + D; z++)
            for (int x = ox_; x < ox_ + D; x++) {
                if (x >= nox && x < nox + D && z >= noz && z < noz + D) continue;
                size_t col = physCol(x, z);
                for (int sy = VG_MIN_SY; sy < VG_MIN_SY + H; sy++) {
                    uint32_t c = phys(x, sy, z);
                    if (slotOf_[c] != ~0u) {
                        freeSlots_.push_back(slotOf_[c]);
                        slotChunk_[slotOf_[c]] = ~0u;
                        slotCpu_[slotOf_[c]] = 0;
                        slotOf_[c] = ~0u;
                        slotsUsed_--;
                    }
                    if (state_[c] == VG_UNIFORM) uniform_--;
                    state_[c] = VG_UNLOADED;
                }
                vacated_.push_back(uint32_t(col));  // (the column's lowest cell)
                gpuLetGoColumn(x, z, 1u);
                colTop_[col] = NO_TOP;
                if (!colFresh_[col]) freshColumns_++;
                colFresh_[col] = 1;
            }
        for (auto it = pending_.begin(); it != pending_.end();)
            it = it->first.x >= nox && it->first.x < nox + D && it->first.z >= noz && it->first.z < noz + D ? std::next(it) : pending_.erase(it);
        stats_.shifts++;
    }
    ox_ = nox;
    oz_ = noz;
    centred_ = true;
    dropOutside();
    rankDue_ = true;
    stats_.lastShiftFrame = stats_.frames;
    world.setGridWindow(ox_, oz_, D);
}

std::vector<Key3> VoxGrid::takeReleased() {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<Key3> r;
    r.swap(released_);
    return r;
}

VgBatch VoxGrid::take(int maxSections) {
    VgBatch b;
    auto p0 = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(mu_);
    auto p1 = std::chrono::steady_clock::now();
    if (!enabled) return b;
    if (centred_) {
        if (gpuRank) take0Gpu(b, maxSections);
        else take0(b, maxSections);
    }
    b.level0Jobs = b.jobs.size();
    auto p2 = std::chrono::steady_clock::now();
    if (activeCoarse_) takeCoarse(b);
    auto p3 = std::chrono::steady_clock::now();
    struct PhaseNote {
        VoxGrid* g;
        std::chrono::steady_clock::time_point t[4];
        ~PhaseNote() {
            auto t4 = std::chrono::steady_clock::now();
            double ms[4] = {std::chrono::duration<double, std::milli>(t[1] - t[0]).count(),
                            std::chrono::duration<double, std::milli>(t[2] - t[1]).count(),
                            std::chrono::duration<double, std::milli>(t[3] - t[2]).count(),
                            std::chrono::duration<double, std::milli>(t4 - t[3]).count()};
            g->notePhases(ms, t4);
        }
    } note{this, {p0, p1, p2, p3}};
    // the host's warm tier: uploads packed on the GPU (staged with the batch's raw sections, read back next time)
    if (world_ && world_->packOnGpu.load(std::memory_order_relaxed) && packBudget > 0) {
        std::vector<World::PackReq> reqs;
        // (a backlog: more a batch, up to an eighth of it, 4096 at most: raw sections wait in host RAM until packed)
        world_->takePackQueue(size_t(scaled(packBudget)), reqs, size_t(scaled(4096)));
        std::unordered_map<const void*, uint32_t> staged;
        for (size_t i = 0; i < b.secs.size(); i++) staged[b.secs[i].get()] = uint32_t(i);
        for (World::PackReq& r : reqs) {
            VgIds ids(r.sec, &r.sec->mats);
            auto f = staged.find(ids.get());
            if (f == staged.end()) {
                f = staged.emplace(ids.get(), uint32_t(b.secs.size())).first;
                b.secs.push_back(ids);
            }
            b.packJobs.push_back({r.key, r.gen, f->second});
        }
    }
    // (the self-baking cache) generated sections packed for the world's gen cache, read back with the batch
    if (world_ && !bake_.empty()) {
        for (int i = 0, nb = scaled(bakeBudget); i < nb && !bake_.empty(); i++) {
            auto [k, ref] = bake_.front();
            bake_.pop_front();
            uint32_t at = VG_GPU_SRC | uint32_t(b.gsecs.size());
            b.gsecs.push_back(ref);
            b.packJobs.push_back({k, World::BAKE, at});
        }
    }
    // (ingestGpu's sections no level waits for any more: their windows moved on)
    for (auto it = gpuRef_.begin(); it != gpuRef_.end();)
        it = pending_.count(it->first) || cpending_.count(it->first) ? std::next(it) : gpuRef_.erase(it);
    return b;
}

void VoxGrid::take0(VgBatch& b, int maxSections) {
    if (!enabled || !centred_) return;
    // the waiting are ranked again once the view has moved on (a section, or ~10 degrees) or slots came free
    int camSy = fdiv(camB_[1], 16);
    if (camSx_ != rankedSx_ || camSy != rankedSy_ || camSz_ != rankedSz_ ||
        fwd_[0] * rankedFwd_[0] + fwd_[1] * rankedFwd_[1] + fwd_[2] * rankedFwd_[2] < 0.985f)
        rankDue_ = true;
    bool rankWaiting = rankDue_ && (!standby_.empty() || !gpuStandby_.empty());
    if (pending_.empty() && !clearPending_ && vacated_.empty() && !rankWaiting) return;
    b.clear = clearPending_;
    b.chunkStates = clearPending_;
    b.ox = ox_;
    b.oz = oz_;
    clearPending_ = false;
    // the columns the window left: unknown until their new columns' sections come, a job a column; alone in their batch
    // (the GPU runs a batch's jobs at once: a section's job in the same cells would race them)
    if (!vacated_.empty()) {
        if (!b.clear) {
            for (uint32_t col : vacated_) b.jobs.push_back({col, VG_UNLOADED, VG_COLUMN, 0});
            b.chunkStates = true;
        }
        vacated_.clear();
        if (!b.clear) {
            b.gens.assign(b.jobs.size(), 0u);
            return;
        }
    }
    size_t n = size_t(D) * H * D;
    if (jobAt_.size() != n) jobAt_.assign(n, ~0u);
    if (cellGen_.size() != n) cellGen_.assign(n, 0u);
    if (slotCpu_.size() != slotCap) slotCpu_.resize(slotCap);
    std::vector<uint32_t> touched;  // (jobAt_ put back after the batch)
    auto emit = [&](uint32_t chunk, uint32_t word, uint32_t slot, uint32_t data) {
        uint8_t st = uint8_t(word & 3u);
        // empty and empty before: its word (with the box grown around it) stands
        if (st == VG_EMPTY && state_[chunk] == VG_EMPTY && !b.clear && jobAt_[chunk] == ~0u) return;
        if (st != state_[chunk]) b.chunkStates = true;
        if (state_[chunk] == VG_UNIFORM && st != VG_UNIFORM) uniform_--;
        if (st == VG_UNIFORM && state_[chunk] != VG_UNIFORM) uniform_++;
        state_[chunk] = st;
        uint32_t gen = ++cellGen_[chunk];
        if (jobAt_[chunk] != ~0u) {
            b.jobs[jobAt_[chunk]] = {chunk, word, slot, data};
            b.gens[jobAt_[chunk]] = gen;
        } else {
            jobAt_[chunk] = uint32_t(b.jobs.size());
            touched.push_back(chunk);
            b.jobs.push_back({chunk, word, slot, data});
            b.gens.push_back(gen);
        }
    };
    b.gens.assign(b.jobs.size(), 0u);
    auto freeSlot = [&](uint32_t chunk) {
        if (slotOf_[chunk] == ~0u) return;
        freeSlots_.push_back(slotOf_[chunk]);
        slotChunk_[slotOf_[chunk]] = ~0u;
        slotCpu_[slotOf_[chunk]] = 0;
        slotOf_[chunk] = ~0u;
        slotsUsed_--;
    };
    auto worldOf = [&](uint32_t c, int& sx, int& sy, int& sz) {
        int px = int(c % uint32_t(D)), pz = int((c / uint32_t(D)) % uint32_t(D));
        sy = int(c / uint32_t(D * D)) + VG_MIN_SY;
        sx = ox_ + pmod(px - ox_, D);
        sz = oz_ + pmod(pz - oz_, D);
    };
    // The candidates by priority (never by arrival): the new sections, and when the view moved on the waiting ones;
    // only the batch's share is put in order (a window's thousands wait their turn unsorted)
    struct Cand {
        float p;
        Key k;
        uint8_t from;  // 0 new (pending_), 1 waiting with its ids (standby_), 2 waiting on the GPU's side (gpuStandby_)
    };
    std::vector<Cand> order;
    order.reserve(pending_.size() + (rankWaiting ? standby_.size() + gpuStandby_.size() : 0));
    for (auto& [k, v] : pending_) order.push_back({priority(k.x, k.y, k.z), k, 0});
    if (rankWaiting) {
        for (auto& [k, v] : standby_)
            if (!pending_.count(k)) order.push_back({priority(k.x, k.y, k.z), k, 1});
        for (auto& [k, v] : gpuStandby_)
            if (!pending_.count(k)) order.push_back({priority(k.x, k.y, k.z), k, 2});
        rankDue_ = false;
        rankedSx_ = camSx_, rankedSy_ = camSy, rankedSz_ = camSz_;
        for (int i = 0; i < 3; i++) rankedFwd_[i] = fwd_[i];
    }
    auto byP = [](const Cand& a, const Cand& c) { return a.p < c.p || (a.p == c.p && a.k.y < c.k.y); };
    size_t share = std::min(order.size(), size_t(std::max(maxSections, 1)) * 8);
    bool cut = share < order.size();
    if (share < order.size()) {
        std::nth_element(order.begin(), order.begin() + ptrdiff_t(share), order.end(), byP);
        order.resize(share);
    }
    std::sort(order.begin(), order.end(), byP);
    // a slot for priority p: a free one, else the worst resident's when it is worse by a margin (no thrash), never one
    // written in this batch (two copies into one slot would race); built once, when the pool is first found full
    std::vector<std::pair<float, uint32_t>> victims;  // (priority, cell), worst last
    bool victimsBuilt = false;
    constexpr float MARGIN = 1.15f, SLACK = 16.0f;
    auto acquire = [&](float p) -> uint32_t {
        if (!freeSlots_.empty()) {
            uint32_t s = freeSlots_.back();
            freeSlots_.pop_back();
            return s;
        }
        if (nextSlot_ < slotCap) return nextSlot_++;
        if (!victimsBuilt) {
            victimsBuilt = true;
            for (uint32_t s = 0; s < nextSlot_; s++) {
                uint32_t vc = slotChunk_[s];
                if (vc == ~0u) continue;
                int vx, vy, vz;
                worldOf(vc, vx, vy, vz);
                victims.push_back({priority(vx, vy, vz), vc});
            }
            std::sort(victims.begin(), victims.end());
        }
        while (!victims.empty() && (slotOf_[victims.back().second] == ~0u || jobAt_[victims.back().second] != ~0u)) victims.pop_back();
        if (victims.empty() || !(victims.back().first > p * MARGIN + SLACK)) return ~0u;
        // the worst resident gives its slot: unknown here from then on (the coarse levels answer), it waits
        uint32_t vc = victims.back().second;
        victims.pop_back();
        uint32_t slot = slotOf_[vc];
        int vx, vy, vz;
        worldOf(vc, vx, vy, vz);
        if (slotCpu_[slot]) {
            standby_[{vx, vy, vz}] = 1;  // (its ids: the world's tiers)
        } else {
            gpuLetGo(vx, vy, vz, 1u);
            gpuStandby_[{vx, vy, vz}] = 0;
        }
        slotCpu_[slot] = 0;
        slotOf_[vc] = ~0u;
        slotChunk_[slot] = ~0u;
        slotsUsed_--;
        emit(vc, VG_UNLOADED, ~0u, 0);
        released_.push_back({0, vx, vy, vz});
        stats_.evicted++;
        return slot;
    };
    // a full pool's cut-off before this batch (ingestGpu's test between batches)
    auto canTake = [&](float p) {
        if (p >= 1e29f) return false;
        if (!freeSlots_.empty() || nextSlot_ < slotCap) return true;
        return p <= cutoff_;
    };
    static const bool topAir = [] {  // ATLAS_VOXGRID_TOPAIR=0: above a column's top section stays UNLOADED
        const char* e = std::getenv("ATLAS_VOXGRID_TOPAIR");
        return !e || std::atoi(e) != 0;
    }();
    int built = 0;
    for (const Cand& cand : order) {
        if (built >= maxSections) break;  // the rest next time
        const Key& k = cand.k;
        uint32_t c = phys(k.x, k.y, k.z);
        Ids ids;
        bool onGpu = false;
        if (cand.from == 0) {
            auto it = pending_.find(k);
            if (it == pending_.end()) continue;
            ids = it->second;
            if (!refreshWorldSource(k, ids)) continue;
            onGpu = ids.gpu();  // (ingestGpu's: their ids on the GPU)
        } else if (cand.from == 1) {
            auto it = standby_.find(k);
            if (it == standby_.end()) continue;
            if (!canTake(cand.p) && slotOf_[c] == ~0u) continue;
            // (its ids from the world's tiers: raw, packed warm, or read back from the cold tier; gone: forgotten)
            World::SectionSrc src;
            if (!world_ || !world_->trySectionSource(k.x, k.y, k.z, src) || !src) {
                requestHostRefill(k.x, k.y, k.z);
                continue;  // busy/cold is not absent: the existing update thread resolves it
            }
            ids = src.raw ? VgSrc{VgIds(src.raw, &src.raw->mats), nullptr} : VgSrc{nullptr, src.packed};
        } else {
            // a GPU section level 0 gave up or did not take: asked for again when it would be taken now, and again
            // after WANT_AGAIN batches if the answer never came (vgask)
            auto it = gpuStandby_.find(k);
            if (it == gpuStandby_.end() || !canTake(cand.p)) continue;
            auto w = wantedAt_.find(k);
            if (it->second && w == wantedAt_.end()) {
                wantedAt_[k] = batches;  // (asked elsewhere: its clock starts)
                continue;
            }
            if (it->second && !vgask::due(true, w->second, batches, WANT_AGAIN)) continue;
            if (it->second) reasked_++;
            it->second = 1;
            wantedAt_[k] = batches;
            reportWanted({0, k.x, k.y, k.z});
            continue;
        }
        // one id throughout (air, rock, a lake's water): no slot, the id in the chunk's word
        uint32_t uni = VG_NOT_UNIFORM;
        if (onGpu) {
            auto h = gpuUniformHint_.find(k);
            if (h != gpuUniformHint_.end()) uni = h->second;
        } else {
            if (ids.uni == VG_UNI_UNSET)
                ids.uni = ids.packed ? packUniform(ids.packed->data()) : uniformIdOf(*ids.ids);
            uni = ids.uni;
        }
        static const bool uniformOn = [] {  // (ATLAS_VOXGRID_UNIFORM=0, measurements: a slot for every non-air section)
            const char* e = std::getenv("ATLAS_VOXGRID_UNIFORM");
            return !e || std::atoi(e) != 0;
        }();
        if (!uniformOn && uni != 0) uni = VG_NOT_UNIFORM;
        if (uni != VG_NOT_UNIFORM) {
            freeSlot(c);
            emit(c, uni == 0 ? VG_EMPTY : (uni & 0xFFFFu) << 2 | VG_UNIFORM, ~0u, 0);
            standby_.erase(k);
        } else {
            uint32_t slot = slotOf_[c];
            if (slot == ~0u) {
                slot = acquire(cand.p);
                if (slot != ~0u) {
                    slotOf_[c] = slot;
                    slotChunk_[slot] = c;
                    slotsUsed_++;
                }
            }
            if (slot == ~0u) {
                // not now (past the reach, or a pool full of nearer ones): unknown here, the coarse levels answer; it
                // waits for its turn (a CPU section by its ids; a GPU one is asked for again)
                if (state_[c] != VG_UNLOADED) emit(c, VG_UNLOADED, ~0u, 0);
                if (onGpu) {
                    gpuLetGo(k.x, k.y, k.z, 1u);
                    gpuStandby_[k] = 0;
                } else {
                    standby_[k] = 1;
                }
                if (cand.p < 1e29f) {
                    misses_++;
                    stats_.overflow++;
                    released_.push_back({0, k.x, k.y, k.z});
                }
            } else {
                uint32_t at = uint32_t(b.secs.size());
                if (onGpu) {
                    at = VG_GPU_SRC | uint32_t(b.gsecs.size());
                    b.gsecs.push_back(gpuRef_[k]);
                } else if (ids.packed) {
                    at = VG_PACK_SRC | uint32_t(b.packs.size());  // (expanded on the GPU, then copied)
                    b.packs.push_back(ids.packed);
                } else {
                    b.secs.push_back(ids.ids);  // (shared: the backend copies it once, into its staging)
                }
                slotCpu_[slot] = onGpu ? 0 : 1;
                emit(c, slot << 2 | VG_MIXED, slot, at);
                standby_.erase(k);
                built++;
            }
        }
        if (cand.from != 0) continue;
        // the column's top: the game sends a chunk's sections up to its highest block, never the air above; below its
        // lowest only solid rock is left out (the tiles' stone: those stay UNLOADED, the coarser levels answer)
        size_t col = physCol(k.x, k.z);
        int top = colTop_[col];
        if (!topAir) {
        } else if (top == NO_TOP) {
            for (int sy = k.y + 1; sy < VG_MIN_SY + H; sy++) {
                uint32_t a = phys(k.x, sy, k.z);
                if (state_[a] == VG_UNLOADED && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z}) &&
                    !standby_.count({k.x, sy, k.z}) && !gpuStandby_.count({k.x, sy, k.z}))
                    emit(a, VG_EMPTY, ~0u, 0);
            }
        } else if (k.y > top) {
            for (int sy = top + 1; sy < k.y; sy++) {  // air until now, data to come
                uint32_t a = phys(k.x, sy, k.z);
                if (state_[a] == VG_EMPTY && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z})) emit(a, VG_UNLOADED, ~0u, 0);
            }
        }
        if (top == NO_TOP || k.y > top) colTop_[col] = int16_t(k.y);
        pending_.erase(k);
        if (onGpu) {
            if (!cpending_.count(k)) {
                gpuRef_.erase(k);
                gpuUniformHint_.erase(k);
            }
        }
    }
    for (uint32_t c : touched) jobAt_[c] = ~0u;
    // a full pool's cut-off: the priority below which a section still takes the worst resident's slot
    if (nextSlot_ < slotCap || !freeSlots_.empty()) {
        cutoff_ = reach_;
    } else if (victimsBuilt) {
        while (!victims.empty() && slotOf_[victims.back().second] == ~0u) victims.pop_back();
        cutoff_ = victims.empty() ? 0.0f : std::max(0.0f, (victims.back().first - SLACK) / MARGIN);
    }
    // the nearest section level 0 knows of and does not hold (in the reach): what "nearest always resident" measures
    if (rankWaiting || built) {
        float nm = 1e30f;
        for (auto& [k, v] : standby_) nm = std::min(nm, priority(k.x, k.y, k.z));
        for (auto& [k, v] : gpuStandby_) nm = std::min(nm, priority(k.x, k.y, k.z));
        nearestMissing_ = nm < 1e29f ? uint32_t(nm) : 0u;
    }
    sectionsBuilt += uint64_t(built);
    batches++;
    // (still to rank next time: free slots while a section in reach waits; a full pool's waiting are ranked when the
    // view moves on, never every batch: they would fill the batch's share and starve the new sections)
    (void)cut;
    if (rankWaiting && (nextSlot_ < slotCap || !freeSlots_.empty()) && nearestMissing_ > 0) rankDue_ = true;
    if (pending_.empty() && !freshColumns_ && stats_.lastShiftFrame >= stats_.settledFrame) {
        stats_.settledFrame = stats_.frames;  // (the window's content complete again: frames since its last move)
        stats_.lastSettleFrames = stats_.frames - stats_.lastShiftFrame;
    }
}

// Level 0's share of a batch on a device (gpuRank): no ranking here. The GPU scores every cell holding or waiting for a
// section (atlas_voxgrid.slang vgRankMain .. vgRankListMain: one priority) and sends back what to admit and what gives
// way (rankResults); the host applies those lists and keeps the books: a new section of one id is written at once, one
// whose cell holds a slot is rewritten in place, a GPU section is taken while slots are free (the generator sends the
// nearest first), every other waits (its cell flagged for the GPU's ranking: 1 its ids in the world's tiers, 2 the GPU's
// own, asked for again when admitted).
void VoxGrid::take0Gpu(VgBatch& b, int maxSections) {
    if (!enabled || !centred_) return;
    bool lists = !rankAdmits_.empty() || !rankEvicts_.empty();
    bool waiting = !standby_.empty() || !gpuStandby_.empty();
    bool bases = world_ && world_->layerCount.load(std::memory_order_relaxed) &&
                 (!baseFetch_.empty() || world_->baseWantedN.load(std::memory_order_relaxed));
    if (pending_.empty() && !clearPending_ && vacated_.empty() && !lists && !waiting && !bases) return;
    b.clear = clearPending_;
    b.chunkStates = clearPending_;
    b.ox = ox_;
    b.oz = oz_;
    clearPending_ = false;
    if (b.clear) {
        // (the lists are of the cells before the jump: stale)
        rankAdmits_.clear();
        rankEvicts_.clear();
        lists = false;
    }
    // the columns the window left: unknown until their new columns' sections come, a job a column; alone in their batch
    if (!vacated_.empty()) {
        if (!b.clear) {
            for (uint32_t col : vacated_) b.jobs.push_back({col, VG_UNLOADED, VG_COLUMN, 0});
            b.chunkStates = true;
        }
        vacated_.clear();
        if (!b.clear) {
            b.gens.assign(b.jobs.size(), 0u);
            rankAdmits_.clear();  // (cells of the columns that left: stale)
            rankEvicts_.clear();
            return;
        }
    }
    size_t n = size_t(D) * H * D;
    if (jobAt_.size() != n) jobAt_.assign(n, ~0u);
    if (cellGen_.size() != n) cellGen_.assign(n, 0u);
    if (slotCpu_.size() != slotCap) slotCpu_.resize(slotCap);
    if (uniId_.size() != n) uniId_.assign(n, 0);
    World::Made made;  // (sections edit layers made over a base the grid knew: put once the batch is made)
    std::vector<uint32_t> touched;  // (jobAt_ put back after the batch)
    auto emit = [&](uint32_t chunk, uint32_t word, uint32_t slot, uint32_t data) {
        uint8_t st = uint8_t(word & 3u);
        if (st == VG_EMPTY && state_[chunk] == VG_EMPTY && !b.clear && jobAt_[chunk] == ~0u) return;
        if (st == VG_UNIFORM) uniId_[chunk] = uint16_t((word >> 2) & 0xFFFFu);
        if (st != state_[chunk]) b.chunkStates = true;
        if (state_[chunk] == VG_UNIFORM && st != VG_UNIFORM) uniform_--;
        if (st == VG_UNIFORM && state_[chunk] != VG_UNIFORM) uniform_++;
        state_[chunk] = st;
        uint32_t gen = ++cellGen_[chunk];
        if (jobAt_[chunk] != ~0u) {
            b.jobs[jobAt_[chunk]] = {chunk, word, slot, data};
            b.gens[jobAt_[chunk]] = gen;
        } else {
            jobAt_[chunk] = uint32_t(b.jobs.size());
            touched.push_back(chunk);
            b.jobs.push_back({chunk, word, slot, data});
            b.gens.push_back(gen);
        }
    };
    b.gens.assign(b.jobs.size(), 0u);
    auto setCand = [&](uint32_t c, uint32_t v) { b.cand.push_back({c, v}); };
    auto freeSlot = [&](uint32_t chunk) {
        if (slotOf_[chunk] == ~0u) return;
        freeSlots_.push_back(slotOf_[chunk]);
        slotChunk_[slotOf_[chunk]] = ~0u;
        slotCpu_[slotOf_[chunk]] = 0;
        slotOf_[chunk] = ~0u;
        slotsUsed_--;
    };
    auto takeSlot = [&](uint32_t c) -> uint32_t {
        uint32_t slot = ~0u;
        if (!freeSlots_.empty()) {
            slot = freeSlots_.back();
            freeSlots_.pop_back();
        } else if (nextSlot_ < slotCap) {
            slot = nextSlot_++;
        }
        if (slot != ~0u) {
            slotOf_[c] = slot;
            slotChunk_[slot] = c;
            slotsUsed_++;
        }
        return slot;
    };
    auto worldOf = [&](uint32_t c, int& sx, int& sy, int& sz) {
        int px = int(c % uint32_t(D)), pz = int((c / uint32_t(D)) % uint32_t(D));
        sy = int(c / uint32_t(D * D)) + VG_MIN_SY;
        sx = ox_ + pmod(px - ox_, D);
        sz = oz_ + pmod(pz - oz_, D);
    };
    // a section into its slot: its ids staged (raw), expanded on the GPU (packed), or copied (the GPU's own)
    int built = 0;
    auto build = [&](uint32_t c, uint32_t slot, const Key& k, const Ids& ids) {
        uint32_t at = uint32_t(b.secs.size());
        if (ids.gpu()) {
            at = VG_GPU_SRC | uint32_t(b.gsecs.size());
            b.gsecs.push_back(gpuRef_[k]);
            // (an edit layer waits for this base: packed here, read back, the edits applied on the host)
            if (baseFetch_.erase(k)) b.packJobs.push_back({Key3{0, k.x, k.y, k.z}, World::BASE_FETCH, at});
        } else if (ids.packed) {
            at = VG_PACK_SRC | uint32_t(b.packs.size());
            b.packs.push_back(ids.packed);
        } else {
            b.secs.push_back(ids.ids);
        }
        slotCpu_[slot] = ids.gpu() ? 0 : 1;
        emit(c, slot << 2 | VG_MIXED, slot, at);
        built++;
    };
    // 1. what the GPU's ranking gave way: the worst residents (never one written in this batch); they wait. Only as many
    // as the admits this batch applies need beyond the free slots (the lists of two frames overlap: both ask for the
    // same room, made before either was applied)
    size_t wantSlots = 0;
    for (uint32_t c : rankAdmits_) {
        if (wantSlots >= size_t(std::max(maxSections, 1))) break;
        if (c >= n || slotOf_[c] != ~0u) continue;
        int sx, sy, sz;
        worldOf(c, sx, sy, sz);
        if (standby_.count({sx, sy, sz}) || gpuStandby_.count({sx, sy, sz})) wantSlots++;  // (the GPU's too: their slot)
    }
    size_t freeNow = freeSlots_.size() + (slotCap - nextSlot_);
    size_t evictLeft = wantSlots > freeNow ? wantSlots - freeNow : 0;
    for (uint32_t c : rankEvicts_) {
        if (!evictLeft) break;
        if (c >= n || slotOf_[c] == ~0u || jobAt_[c] != ~0u) continue;
        evictLeft--;
        uint32_t slot = slotOf_[c];
        int vx, vy, vz;
        worldOf(c, vx, vy, vz);
        if (slotCpu_[slot]) {
            standby_[{vx, vy, vz}] = 1;
            setCand(c, 1);
        } else {
            gpuLetGo(vx, vy, vz, 1u);
            gpuStandby_[{vx, vy, vz}] = 0;
            setCand(c, 2);
        }
        freeSlot(c);
        emit(c, VG_UNLOADED, ~0u, 0);
        released_.push_back({0, vx, vy, vz});
        stats_.evicted++;
    }
    rankEvicts_.clear();
    // 2. what it admits: nearest first, into the free slots (its ids from the world's tiers; a GPU one asked for)
    std::vector<uint32_t> retryAdmits;
    for (uint32_t c : rankAdmits_) {
        if (built >= maxSections) break;  // (the rest: the next lists hold them again)
        if (c >= n) continue;
        if (slotOf_[c] != ~0u) {
            setCand(c, 0);
            continue;
        }
        int sx, sy, sz;
        worldOf(c, sx, sy, sz);
        Key k{sx, sy, sz};
        if (auto g = gpuStandby_.find(k); g != gpuStandby_.end()) {
            auto w = wantedAt_.find(k);
            bool ever = w != wantedAt_.end();
            if (g->second && !ever) {
                wantedAt_[k] = batches;  // (asked elsewhere, an edit's base: its clock starts)
                continue;
            }
            // never asked; or asked lately and not taken: not again yet, else the generator remakes it each batch; or
            // asked and never answered (a lost answer kept the hole for good): again after WANT_AGAIN batches
            if (!vgask::due(ever, ever ? w->second : 0, batches, WANT_AGAIN)) {
                wantedHeld_++;
                continue;
            }
            if (g->second) reasked_++;
            g->second = 1;
            wantedAt_[k] = batches;
            wantedAgain_++;
            reportWanted({0, sx, sy, sz});
            continue;
        }
        auto it = standby_.find(k);
        if (it == standby_.end()) {
            setCand(c, 0);  // (a flag the books no longer hold: the cell's columns moved, it came since)
            continue;
        }
        World::SectionSrc src;
        bool memoryReady = world_ && world_->trySectionSource(sx, sy, sz, src);
        if (!memoryReady || !src) {
            int need = 0;
            if (memoryReady && world_->tryLayerNeed({0, sx, sy, sz}, need) && need == 2) {
                standby_.erase(it);
                gpuStandby_[k] = 1;
                baseFetch_.insert(k);
                reportWanted({0, sx, sy, sz});
                setCand(c, 2);
            } else {
                requestHostRefill(sx, sy, sz);
                retryAdmits.push_back(c);
            }
            continue;
        }
        uint32_t slot = takeSlot(c);
        if (slot == ~0u) break;  // (no slot came free: the next lists)
        build(c, slot, k, src.raw ? VgSrc{VgIds(src.raw, &src.raw->mats), nullptr} : VgSrc{nullptr, src.packed});
        standby_.erase(it);
        setCand(c, 0);
    }
    rankAdmits_.swap(retryAdmits);
    // 3. the new sections
    static const bool topAir = [] {  // ATLAS_VOXGRID_TOPAIR=0: above a column's top section stays UNLOADED
        const char* e = std::getenv("ATLAS_VOXGRID_TOPAIR");
        return !e || std::atoi(e) != 0;
    }();
    static const bool uniformOn = [] {  // (ATLAS_VOXGRID_UNIFORM=0, measurements: a slot for every non-air section)
        const char* e = std::getenv("ATLAS_VOXGRID_UNIFORM");
        return !e || std::atoi(e) != 0;
    }();
    size_t share = size_t(std::max(maxSections, 1)) * 4, done = 0;
    for (auto it = pending_.begin(); it != pending_.end() && done < share;) {
        Key k = it->first;
        Ids ids = it->second;
        if (!refreshWorldSource(k, ids)) { ++it; continue; }
        bool onGpu = ids.gpu();
        uint32_t c = phys(k.x, k.y, k.z);
        uint32_t uni = VG_NOT_UNIFORM;
        if (onGpu) {
            auto h = gpuUniformHint_.find(k);
            if (h != gpuUniformHint_.end()) uni = h->second;
        } else {
            if (ids.uni == VG_UNI_UNSET)
                ids.uni = ids.packed ? packUniform(ids.packed->data()) : uniformIdOf(*ids.ids);
            uni = ids.uni;
        }
        if (!uniformOn && uni != 0) uni = VG_NOT_UNIFORM;
        // (a free slot takes it at once, from either source: only a full pool waits for the GPU's ranking)
        bool fits = slotOf_[c] != ~0u || nextSlot_ < slotCap || !freeSlots_.empty();
        if (uni == VG_NOT_UNIFORM && fits && built >= maxSections) {
            ++it;  // (a copy for the next batch)
            continue;
        }
        done++;
        if (uni != VG_NOT_UNIFORM && onGpu && world_ && baseFetch_.count(k)) {
            if (!world_->tryBaseUniform(Key3{0, k.x, k.y, k.z}, uint16_t(uni), made)) {
                requestHostRefill(k.x, k.y, k.z);
                ++it;
                continue;  // retain the GPU reference and edits until their base can be applied
            }
            baseFetch_.erase(k);
        }
        if (uni != VG_NOT_UNIFORM) {
            freeSlot(c);
            emit(c, uni == 0 ? VG_EMPTY : (uni & 0xFFFFu) << 2 | VG_UNIFORM, ~0u, 0);
            standby_.erase(k);
            gpuStandby_.erase(k);
            wantedAt_.erase(k);
            setCand(c, 0);
        } else if (fits) {
            uint32_t slot = slotOf_[c] != ~0u ? slotOf_[c] : takeSlot(c);
            build(c, slot, k, ids);
            standby_.erase(k);
            gpuStandby_.erase(k);
            wantedAt_.erase(k);
            setCand(c, 0);
        } else {
            // waits for the GPU's ranking (unknown here meanwhile: the coarse levels answer)
            if (state_[c] != VG_UNLOADED) emit(c, VG_UNLOADED, ~0u, 0);
            if (onGpu) {
                gpuLetGo(k.x, k.y, k.z, 1u);
                gpuStandby_[k] = 0;
                setCand(c, 2);
            } else {
                standby_[k] = 1;
                setCand(c, 1);
            }
        }
        // the column's top: the game sends a chunk's sections up to its highest block, never the air above
        size_t col = physCol(k.x, k.z);
        int top = colTop_[col];
        if (!topAir) {
        } else if (top == NO_TOP) {
            for (int sy = k.y + 1; sy < VG_MIN_SY + H; sy++) {
                uint32_t a = phys(k.x, sy, k.z);
                if (state_[a] == VG_UNLOADED && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z}) &&
                    !standby_.count({k.x, sy, k.z}) && !gpuStandby_.count({k.x, sy, k.z}))
                    emit(a, VG_EMPTY, ~0u, 0);
            }
        } else if (k.y > top) {
            for (int sy = top + 1; sy < k.y; sy++) {
                uint32_t a = phys(k.x, sy, k.z);
                if (state_[a] == VG_EMPTY && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z})) emit(a, VG_UNLOADED, ~0u, 0);
            }
        }
        if (top == NO_TOP || k.y > top) colTop_[col] = int16_t(k.y);
        it = pending_.erase(it);
        if (onGpu && !cpending_.count(k)) {
            gpuRef_.erase(k);
            gpuUniformHint_.erase(k);
        }
    }
    // 4. the bases edit layers wait for (World::editBlock): a GPU section in its slot packed (after this batch's copies:
    // a slot written now holds the same section), one of one id answered at once, the rest at their build
    if (world_ && world_->layerCount.load(std::memory_order_relaxed)) {
        std::vector<Key3> want;
        world_->takeBaseWanted(want);
        // (those waiting outside the window, or for a cell not known yet: again once the window moved, or now and then)
        if (ox_ != baseOx_ || oz_ != baseOz_ || batches % 16 == 0) {
            baseOx_ = ox_, baseOz_ = oz_;
            for (const Key& k : baseFetch_)
                if (inWindow(k.x, k.y, k.z) && !gpuRef_.count(k)) want.push_back({0, k.x, k.y, k.z});
        }
        for (const Key3& k3 : want) {
            Key k{k3.x, k3.y, k3.z};
            if (!inWindow(k.x, k.y, k.z)) {
                baseFetch_.insert(k);  // (when the window holds it, or its section comes)
                continue;
            }
            uint32_t c = phys(k.x, k.y, k.z);
            uint32_t slot = slotOf_[c];
            bool done = true;
            if (slot != ~0u && !slotCpu_[slot]) {
                b.packJobs.push_back({k3, World::BASE_FETCH, VG_POOL_SRC | slot});
            } else if (slot == ~0u && state_[c] == VG_EMPTY) {
                done = world_->tryBaseUniform(k3, 0, made);
            } else if (slot == ~0u && state_[c] == VG_UNIFORM) {
                done = world_->tryBaseUniform(k3, uniId_[c], made);
            } else {
                done = false;  // (a GPU section waiting: at its build; a cell not known yet: later)
            }
            if (done) baseFetch_.erase(k);
            else { baseFetch_.insert(k); requestHostRefill(k.x, k.y, k.z); }
        }
    }
    for (auto& [k3, s] : made) putLocked(k3.x, k3.y, k3.z, VgIds(s, &s->mats));
    for (uint32_t c : touched) jobAt_[c] = ~0u;
    // 5. this batch's ranking on the GPU (after its jobs: the words it reads are this batch's)
    if (!gpuCandDirty_.empty()) {
        // A delivery rejected by level 0 can still feed coarse levels, without entering pending_. Register its
        // waiting flag here. Keep changes across the vacated-column-only batch and avoid two writes to one cell
        // in vgCandMain: if this batch already updates it, recheck the authoritative books next batch instead.
        std::unordered_set<uint32_t> changed;
        for (const auto& cand : b.cand) changed.insert(cand.first);
        for (auto it = gpuCandDirty_.begin(); it != gpuCandDirty_.end();) {
            const Key& k = *it;
            if (inWindow(k.x, k.y, k.z) && gpuStandby_.count(k) && slotOf_[phys(k.x, k.y, k.z)] == ~0u) {
                uint32_t c = phys(k.x, k.y, k.z);
                if (changed.count(c)) { ++it; continue; }
                setCand(c, 2);
            }
            it = gpuCandDirty_.erase(it);
        }
    }
    b.rank.on = !standby_.empty() || !gpuStandby_.empty();
    // predicted motion (the residency priority's fourth term): the lists come back RANK_LAG frames after they are made,
    // so the view they rank for is where the camera will be by then: the eye moved on by its velocity, the cone widened
    // by the turn (blocks and radians a batch, eased)
    constexpr float RANK_LAG = 3.0f;
    float turn = std::acos(std::clamp(fwd_[0] * lastFwd_[0] + fwd_[1] * lastFwd_[1] + fwd_[2] * lastFwd_[2], -1.0f, 1.0f));
    turnRate_ += (turn - turnRate_) * 0.5f;
    for (int i = 0; i < 3; i++) {
        vel_[i] += (float(camB_[i] - lastEye_[i]) - vel_[i]) * 0.5f;
        if (std::fabs(vel_[i]) > 256.0f) vel_[i] = 0.0f;  // (a jump, not a motion)
        lastEye_[i] = camB_[i];
        lastFwd_[i] = fwd_[i];
    }
    if (b.rank.on) {
        for (int i = 0; i < 3; i++) b.rank.eye[i] = camB_[i] + int32_t(std::lround(vel_[i] * RANK_LAG)), b.rank.fwd[i] = fwd_[i];
        float half = std::acos(std::clamp(cosView_, -1.0f, 1.0f)) + turnRate_ * RANK_LAG;
        b.rank.cosView = half >= 3.14159f ? -1.0f : std::cos(half);
        b.rank.reach = std::min(reach_, 1e29f);
        b.rank.nearBall = NEAR_BALL;
        b.rank.viewOut = VIEW_OUT;
        b.rank.ox = ox_;
        b.rank.oz = oz_;
        b.rank.cap = slotCap;
        b.rank.free = slotCap - slotsUsed_;
        b.rank.budget = uint32_t(std::max(maxSections, 1)) * 2;  // (read two frames later, made before the last applied)
        b.rank.margin = 3;  // (buckets of 1/16 octave: 14%, no thrash)
    }
    // the cut-off and the nearest waiting from the last ranking (buckets of 1/16 of an octave of 1 + blocks)
    cutoff_ = rankCut_ >= 255 ? reach_ : std::exp2(float(rankCut_) / 16.0f) - 1.0f;
    nearestMissing_ = rankWaitBest_ >= 255 || !b.rank.on ? 0u : uint32_t(std::exp2(float(rankWaitBest_) / 16.0f) - 1.0f);
    sectionsBuilt += uint64_t(built);
    batches++;
    if (pending_.empty() && !freshColumns_ && stats_.lastShiftFrame >= stats_.settledFrame) {
        stats_.settledFrame = stats_.frames;
        stats_.lastSettleFrames = stats_.frames - stats_.lastShiftFrame;
    }
}

void VoxGrid::rankResults(const std::vector<uint32_t>& admits, const std::vector<uint32_t>& evicts, uint32_t cut, uint32_t waitBest) {
    std::lock_guard<std::mutex> lock(mu_);
    rankAdmits_ = admits;
    rankEvicts_ = evicts;
    rankCut_ = cut;
    rankWaitBest_ = waitBest;
    rankSeen_ = true;
}

namespace {
uint32_t packV(int x, int y, int z) { return uint32_t(x) | uint32_t(y) << 8 | uint32_t(z) << 16; }
}  // namespace

void VoxGrid::cellCoords(const Level& L, uint32_t cell, int& cx, int& cy, int& cz) const {
    int px = int(cell % uint32_t(L.D)), pz = int((cell / uint32_t(L.D)) % uint32_t(L.D));
    cy = int(cell / uint32_t(L.D * L.D));
    cx = L.ox + pmod(px - L.ox, L.D);
    cz = L.oz + pmod(pz - L.oz, L.D);
}

void VoxGrid::coarseVictims(Level& L) {
    int k = L.k;
    auto& vs = victims_[k];
    if (victimsBuilt_[k]) return;
    victimsBuilt_[k] = true;
    vs.clear();
    for (uint32_t s = 0; s < L.nextSlot; s++) {
        uint32_t vc = L.slotCell[s];
        if (vc == ~0u) continue;
        int vx, vy, vz;
        cellCoords(L, vc, vx, vy, vz);
        vs.push_back({chunkPriority(k, vx, vy, vz), vc});
    }
    std::sort(vs.begin(), vs.end());
}

bool VoxGrid::coarseRoom(Level& L, float dist) {
    if (!L.freeSlots.empty() || L.nextSlot < L.cap) return true;
    coarseVictims(L);
    auto& vs = victims_[L.k];
    while (!vs.empty() && (L.slotOf[vs.back().second] == ~0u || L.touched[vs.back().second])) vs.pop_back();
    return !vs.empty() && vs.back().first > dist * 1.15f + float(16 << L.k);
}

uint32_t VoxGrid::coarseSlot(Level& L, uint32_t cell, VgBatch& b, bool raw) {
    int k = L.k;
    int cx, cy, cz;
    cellCoords(L, cell, cx, cy, cz);
    float dist = chunkPriority(k, cx, cy, cz);
    uint32_t slot = ~0u;
    if (!L.freeSlots.empty()) {
        slot = L.freeSlots.back();
        L.freeSlots.pop_back();
    } else if (L.nextSlot < L.cap) {
        slot = L.nextSlot++;
    } else {
        // the pool full: the farthest chunk gives its slot when it is farther than this one (unknown from then on, until
        // the window leaves its column; never one written in this batch)
        auto& vs = victims_[k];
        coarseVictims(L);
        while (!vs.empty() && (L.slotOf[vs.back().second] == ~0u || L.touched[vs.back().second])) vs.pop_back();
        // (by a margin, as level 0's: no thrash)
        if (!vs.empty() && vs.back().first > dist * 1.15f + float(16 << k)) {
            uint32_t vc = vs.back().second;
            vs.pop_back();
            slot = L.slotOf[vc];
            if (L.given[vc]) {
                // (a generator's chunk given up: listed, to give again when it ranks)
                int vx, vy, vz;
                cellCoords(L, vc, vx, vy, vz);
                reportDropped({k, vx, vy, vz});
                L.given[vc] = 0;
            }
            L.slotOf[vc] = ~0u;
            L.slotCell[slot] = ~0u;
            L.used--;
            L.lost[vc] = 1;
            lostCells_[k].push_back(vc);
            if (L.word[vc] != VG_UNLOADED) {
                coarseJob(b, {L.chunkBase + vc, VG_UNLOADED, ~0u, 0});
                b.levels[k].grow = true;
            }
            L.word[vc] = VG_UNLOADED;
            L.evicted++;
        }
    }
    if (slot == ~0u) {
        L.lost[cell] = 1;
        lostCells_[k].push_back(cell);
        L.overflow++;
        return ~0u;
    }
    L.slotOf[cell] = slot;
    L.slotCell[slot] = cell;
    L.used++;
    L.touched[cell] = 1;
    markDirty(L, cell);
    if (raw) return slot;
    if (L.given[cell]) {
        // a generator's air or one-id chunk that sections now refine: the chunk that, the sections' parts over it
        b.clears.push_back({L.slotBase + slot, 0, packV(16, 16, 16), L.given[cell] == 2 ? uint32_t(L.givenMat[cell]) : 0u});
        L.given[cell] = 3;
        return slot;
    }
    if (k <= 4) {
        // levels made from sections: the chunk air, then each known column's rock under its lowest section
        b.clears.push_back({L.slotBase + slot, 0, packV(16, 16, 16), 0});
        int s = L.span();
        size_t cc = L.ccol(cx, cz);
        if (L.colKnown[cc]) {
            const uint64_t* cr = &L.colRecv[cc * size_t(s) * size_t(s)];
            for (int dz = 0; dz < s; dz++)
                for (int dx = 0; dx < s; dx++)
                    if (cr[size_t(dz) * size_t(s) + size_t(dx)]) colFills_.push_back({k, cell, cx * s + dx, cz * s + dz});
        }
    }
    return slot;
}

uint32_t VoxGrid::evalCell(Level& L, uint32_t cell, VgBatch& b) {
    L.rock[cell] = 0;
    if (L.lost[cell]) return VG_UNLOADED;
    if (L.given[cell]) {
        // the generator's chunk (and the sections' parts over it, in its slot)
        if (L.slotOf[cell] != ~0u) return (L.slotBase + L.slotOf[cell]) << 2 | VG_MIXED;
        if (L.given[cell] == 1) return VG_EMPTY;
        if (L.given[cell] == 2) return uint32_t(L.givenMat[cell]) << 2 | VG_UNIFORM;
        return VG_UNLOADED;
    }
    int cx, cy, cz;
    cellCoords(L, cell, cx, cy, cz);
    int s = L.span(), y0 = cy * s, y1 = y0 + s;
    size_t cc = L.ccol(cx, cz);
    if (L.colKnown[cc] < uint16_t(s * s)) return VG_UNLOADED;
    bool allAbove = true, allBelow = true;
    if (y0 > int(L.colMaxHigh[cc])) {
        allBelow = false;
    } else {
        const uint64_t* cr = &L.colRecv[cc * size_t(s) * size_t(s)];
        int n2 = s * s;
        for (int i = 0; i < n2 && (allAbove || allBelow); i++) {
            uint64_t rv = cr[i];
            if (y0 <= highBit(rv)) allAbove = false;
            if (y1 > lowBit(rv)) allBelow = false;
        }
    }
    if (L.slotOf[cell] == ~0u) {
        if (allAbove) return VG_EMPTY;
        if (allBelow) {
            L.rock[cell] = 1;
            return VG_UNLOADED;
        }
        if (coarseSlot(L, cell, b) == ~0u) return VG_UNLOADED;
    }
    return (L.slotBase + L.slotOf[cell]) << 2 | VG_MIXED;
}

void VoxGrid::coarseJob(VgBatch& b, const VgJob& j) {
    // (the GPU runs a pass's jobs at once: two for one chunk would race; the later one is what holds)
    auto [it, fresh] = coarseJobAt_.emplace(j.chunk, uint32_t(b.jobs.size()));
    if (fresh) b.jobs.push_back(j);
    else b.jobs[it->second] = j;
}

void VoxGrid::coarseReadmit(VgBatch& b) {
    // the coarse cells given up for nearer chunks (or that found no slot): taken back when the view moved on or slots
    // came free; their sections read again from the world's tiers (GPU ones asked for again), levels 5-6 made again
    // from the level below. A cell still beaten by nearer ones is given up again (coarseSlot).
    if (!coarseRankDue_ || !world_) return;
    coarseRankDue_ = false;
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        auto& lost = lostCells_[k];
        if (!L.centred || lost.empty()) continue;
        std::vector<std::pair<float, uint32_t>> order;
        for (uint32_t c : lost)
            if (L.lost[c]) {
                int cx, cy, cz;
                cellCoords(L, c, cx, cy, cz);
                order.push_back({chunkPriority(k, cx, cy, cz), c});
            }
        std::sort(order.begin(), order.end());
        order.erase(std::unique(order.begin(), order.end()), order.end());
        lost.clear();
        size_t take = std::min(order.size(), size_t(64));  // (a few a batch, nearest first)
        for (size_t i = 0; i < order.size(); i++) {
            uint32_t c = order[i].second;
            if (i >= take) {
                lost.push_back(c);
                continue;
            }
            int cx, cy, cz;
            cellCoords(L, c, cx, cy, cz);
            if (!coarseRoom(L, order[i].first)) {
                lost.push_back(c);  // (no slot would take it now: no sections asked for, nothing made again; later)
                continue;
            }
            if (k >= 5) {
                L.lost[c] = 0;
                if (!L.downDirty[c]) L.downDirty[c] = 1, L.downList.push_back(c);
                continue;
            }
            int s = L.span();
            std::vector<std::pair<Key3, World::SectionSrc>> secs;
            int y0 = VG_MIN_SY + cy * s, y1 = y0 + s;
            if (!world_->trySectionsInBox(cx * s, y0, cz * s, cx * s + s, y1, cz * s + s, secs)) {
                lost.push_back(c);
                coarseRankDue_ = true;
                continue;
            }
            L.lost[c] = 0;
            for (auto& [key, sec] : secs) {
                if (key.y < y0 || key.y >= y1) continue;
                CPending& p = cpending_[{key.x, key.y, key.z}];
                if (p.ids.gpu() && !gpuRef_.count({key.x, key.y, key.z}))
                    p.ids = sec.raw ? VgSrc{VgIds(sec.raw, &sec.raw->mats), nullptr} : VgSrc{nullptr, sec.packed};
                if (!p.ids.gpu()) worldSources_.insert({key.x, key.y, key.z});
                p.levels |= 1u << k;
                ++cpendingRev_;
            }
            for (int dz = 0; dz < s; dz++)
                for (int dx = 0; dx < s; dx++) {
                    auto col = gpuCol_.find(colKey(cx * s + dx, cz * s + dz));
                    if (col == gpuCol_.end()) continue;
                    for (int16_t sy : col->second) {
                        if (sy < y0 || sy >= y1) continue;
                        auto hold = gpuHold_.find({cx * s + dx, sy, cz * s + dz});
                        if (hold != gpuHold_.end() && (hold->second & 1u)) continue;  // (level 0 holds it: its words come)
                        reportWanted({0, cx * s + dx, sy, cz * s + dz});  // (the GPU's own: again)
                    }
                }
            markDirty(L, c);
        }
    }
}

void VoxGrid::takeCoarse(VgBatch& b) {
    b.classes = classes_;
    coarseJobAt_.clear();
    // (the coarse standby is ranked again with level 0's waiting: the view moved on, or slots came free; and, the camera
    // still, every 8 batches while any wait: a still camera keeps refining, 64 cells a time)
    if (camSx_ != coarseRankedSx_ || camSz_ != coarseRankedSz_) coarseRankDue_ = true, coarseRankedSx_ = camSx_, coarseRankedSz_ = camSz_;
    if (batches % 8 == 0)
        for (int k = 1; k <= activeCoarse_ && !coarseRankDue_; k++) coarseRankDue_ = !lostCells_[k].empty();
    for (int k = 1; k <= activeCoarse_; k++) victimsBuilt_[k] = false;
    coarseReadmit(b);
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        b.levels[k].ox = L.ox;
        b.levels[k].oz = L.oz;
        if (!L.centred) continue;
        if (L.clearPending) {
            b.levels[k].clear = b.levels[k].grow = true;
            L.clearPending = false;
            L.vacated.clear();
        }
        for (uint32_t c : L.vacated) coarseJob(b, {L.chunkBase + c, VG_UNLOADED, ~0u, 0});
        if (!L.vacated.empty()) b.levels[k].grow = true;
        L.vacated.clear();
    }
    colFills_.clear();
    // the generator's chunks (ingestCoarseGpu), before the sections, which refine them in place; a chunk any of whose
    // section columns came already is the sections' (levels 1-4); a coarseBudget of them a level a batch
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        if (!L.centred || cgiven_[k].empty()) continue;
        int s = L.span();
        // (nearest first, a coarseBudget a batch)
        std::vector<std::pair<float, uint32_t>> order;
        for (auto& [c, g] : cgiven_[k]) order.push_back({chunkPriority(k, g.cx, g.cy, g.cz), c});
        size_t share = std::min(order.size(), size_t(scaled(coarseBudget)));
        if (share < order.size()) {
            std::nth_element(order.begin(), order.begin() + ptrdiff_t(share), order.end());
            order.resize(share);
        }
        std::sort(order.begin(), order.end());
        for (auto& [pr, cell] : order) {
            auto it = cgiven_[k].find(cell);
            if (it == cgiven_[k].end()) continue;
            const CGiven g = it->second;
            cgiven_[k].erase(it);
            bool sections = (k <= 4) && (L.colKnown[L.ccol(g.cx, g.cz)] != 0);
            if (sections || !L.inWindow(g.cx, g.cz)) {
                reportDropped({k, g.cx, g.cy, g.cz});
                continue;
            }
            L.lost[cell] = 0;
            if (g.kind != VG_GIVEN_MIXED) {
                if (L.slotOf[cell] != ~0u) {
                    L.freeSlots.push_back(L.slotOf[cell]);
                    L.slotCell[L.slotOf[cell]] = ~0u;
                    L.slotOf[cell] = ~0u;
                    L.used--;
                }
                L.given[cell] = g.kind == VG_GIVEN_AIR ? 1 : 2;
                L.slotless |= g.kind == VG_GIVEN_UNIFORM;
                L.givenMat[cell] = uint16_t(g.mat);
            } else {
                uint32_t slot = L.slotOf[cell];
                if (slot == ~0u) slot = coarseSlot(L, cell, b, true);
                if (slot == ~0u) {
                    // (a pool full of nearer chunks: not held, listed; the cell stays open to its sections)
                    L.lost[cell] = 0;
                    L.given[cell] = 0;
                    reportDropped({k, g.cx, g.cy, g.cz});
                    continue;
                }
                if (g.packed) {
                    b.packPool.push_back({L.slotBase + slot, uint32_t(b.packs.size())});
                    b.packs.push_back(g.packed);
                } else {
                    b.poolCopies.push_back({L.slotBase + slot, g.ref});
                }
                L.given[cell] = 3;
                L.touched[cell] = 1;
            }
            markDirty(L, cell);
        }
    }
    // the batch's sections: nearest the camera first, what it sees before what lies behind it
    auto byDist = [](const auto& a, const auto& c) {
        return std::tie(a.first, a.second.x, a.second.y, a.second.z) <
               std::tie(c.first, c.second.x, c.second.y, c.second.z);
    };
    bool sameView = (cpendingCam_[0] == camSx_ && cpendingCam_[1] == camSy_ && cpendingCam_[2] == camSz_ &&
                     cpendingView_[0] == viewX_ && cpendingView_[1] == viewZ_);
    if (!sameView || cpendingOrderRev_ != cpendingRev_ || cpendingCursor_ >= cpendingOrder_.size()) {
        cpendingOrder_.clear();
        cpendingOrder_.reserve(cpending_.size());
        for (auto it = cpending_.begin(); it != cpending_.end();) {
            if (!it->second.levels) {
                it = cpending_.erase(it);
                continue;
            }
            cpendingOrder_.push_back({viewDist(it->first.x - camSx_, it->first.z - camSz_) * 64 + std::abs(it->first.y - camSy_), it->first});
            ++it;
        }
        std::sort(cpendingOrder_.begin(), cpendingOrder_.end(), byDist);
        cpendingCursor_ = 0;
        cpendingOrderRev_ = cpendingRev_;
        cpendingCam_[0] = camSx_, cpendingCam_[1] = camSy_, cpendingCam_[2] = camSz_;
        cpendingView_[0] = viewX_, cpendingView_[1] = viewZ_;
    }
    size_t rem = cpendingOrder_.size() - cpendingCursor_;
    size_t share = std::min(rem, std::max(size_t(scaled(coarseBudget)), std::min(rem / 8, size_t(scaled(4096)))));
    std::vector<std::pair<int64_t, Key>> order(cpendingOrder_.begin() + ptrdiff_t(cpendingCursor_),
                                               cpendingOrder_.begin() + ptrdiff_t(cpendingCursor_ + share));
    cpendingCursor_ += share;
    std::unordered_map<const void*, uint32_t> staged;  // ids level 0 sends already
    for (size_t i = 0; i < b.secs.size(); i++) staged[b.secs[i].get()] = uint32_t(i);
    std::unordered_map<const void*, uint32_t> stagedPacks;  // (and packed ones)
    for (size_t i = 0; i < b.packs.size(); i++) stagedPacks[b.packs[i].get()] = uint32_t(i);
    int sectionLevels = std::min(4, activeCoarse_);
    for (auto& [dist, key] : order) {
        auto pit = cpending_.find(key);
        if (!refreshWorldSource(key, pit->second.ids)) { ++cpendingRev_; continue; }
        CPending p = std::move(pit->second);
        cpending_.erase(pit);
        bool onGpu = p.ids.gpu();  // (ingestGpu's: taken as having blocks unless the caller said it is one id)
        uint32_t uni = VG_NOT_UNIFORM;
        if (onGpu) {
            auto h = gpuUniformHint_.find(key);
            if (h != gpuUniformHint_.end()) uni = h->second;
        } else {
            if (p.ids.uni == VG_UNI_UNSET)
                p.ids.uni = p.ids.packed ? packUniform(p.ids.packed->data()) : uniformIdOf(*p.ids.ids);
            uni = p.ids.uni;
        }
        bool nonAir = uni != 0;
        VgIngest ing{};
        ing.src = ~0u;
        for (uint32_t& t : ing.target) t = ~0u;
        // (the section's place: the vote's dither, atlas_voxgrid.slang vgIngestMain)
        ing.pad[0] = uint32_t(key.x), ing.pad[1] = uint32_t(key.y - VG_MIN_SY), ing.pad[2] = uint32_t(key.z);
        bool any = false;
        for (int k = 1; k <= sectionLevels; k++) {
            Level& L = lv_[k];
            if (!((p.levels >> k) & 1u) || !L.centred) continue;
            int s = L.span(), cx = fdiv(key.x, s), cz = fdiv(key.z, s), by = key.y - VG_MIN_SY;
            if (by < 0 || by >= 64 || by / s >= L.H || !L.inWindow(cx, cz)) continue;
            int cy = by / s;
            size_t cc = L.ccol(cx, cz);
            uint64_t& recv = L.colRecv[ (cc * size_t(s) + size_t(key.z - cz * s)) * size_t(s) + size_t(key.x - cx * s) ];
            if (!recv) L.colKnown[cc]++;
            int oldBottom = lowBit(recv), oldTop = highBit(recv);
            recv |= 1ull << by;
            int bottom = lowBit(recv), top = highBit(recv);
            if (top > int(L.colMaxHigh[cc])) L.colMaxHigh[cc] = int8_t(top);
            uint32_t cell = L.cell(cx, cy, cz);
            L.ingested++;
            if (!L.lost[cell]) {
                uint32_t slot = L.slotOf[cell];
                // (an air section in a generator's one-id chunk needs the chunk's slot too: the hole is real)
                if (slot == ~0u && (nonAir || L.given[cell] == 2)) slot = coarseSlot(L, cell, b);
                if (slot != ~0u) {
                    uint32_t gs = L.slotBase + slot;
                    int lx = key.x - cx * s, ly = by - cy * s, lz = key.z - cz * s, n = 16 >> k;
                    if (nonAir && uni != VG_NOT_UNIFORM) {
                        // one id throughout: its box in the chunk that id at every level (the vote of eight alike)
                        b.fills.push_back({gs, packV(lx * n, ly * n, lz * n), packV((lx + 1) * n, (ly + 1) * n, (lz + 1) * n), uni});
                    } else if (nonAir) {
                        ing.target[k - 1] = gs << 12 | uint32_t(lx | ly << 4 | lz << 8);
                        any = true;
                    } else {
                        b.fills.push_back({gs, packV(lx * n, ly * n, lz * n), packV((lx + 1) * n, (ly + 1) * n, (lz + 1) * n), 0});
                    }
                    L.touched[cell] = 1;
                }
            }
            markDirty(L, cell);
            if (bottom != oldBottom || top != oldTop) {
                // the column's extent changed: every cell over it may change state; its rock lies lower now
                for (int y = 0; y < L.H; y++) {
                    uint32_t c2 = L.cell(cx, y, cz);
                    markDirty(L, c2);
                    // (a generator's chunk in its slot, given 3, too: its far voxels over this real column go)
                    if (bottom != oldBottom && L.slotOf[c2] != ~0u && !L.lost[c2] && (L.given[c2] == 0 || L.given[c2] == 3))
                        colFills_.push_back({k, c2, key.x, key.z});
                }
            }
        }
        if (any && onGpu) {
            auto g = gpuRef_.find(key);
            if (g != gpuRef_.end()) {
                ing.src = VG_GPU_SRC | uint32_t(b.gsecs.size());
                b.gsecs.push_back(g->second);
                b.ingests.push_back(ing);
            }
        } else if (any && p.ids.packed) {
            // (packed: expanded on the GPU first, read there as the GPU's own sections are)
            auto f = stagedPacks.find(p.ids.packed.get());
            if (f == stagedPacks.end()) {
                f = stagedPacks.emplace(p.ids.packed.get(), uint32_t(b.packs.size())).first;
                b.packs.push_back(p.ids.packed);
            }
            ing.src = VG_PACK_SRC | f->second;
            b.ingests.push_back(ing);
        } else if (any) {
            auto f = staged.find(p.ids.ids.get());
            if (f == staged.end()) {
                f = staged.emplace(p.ids.ids.get(), uint32_t(b.secs.size())).first;
                b.secs.push_back(p.ids.ids);
            }
            ing.src = f->second;
            b.ingests.push_back(ing);
        }
        if (onGpu && !pending_.count(key)) {
            gpuRef_.erase(key);
            gpuUniformHint_.erase(key);
        }
    }
    // the cells' words (a known chunk with blocks gets its slot here, when it has none yet)
    std::vector<std::pair<uint32_t, uint32_t>> evals[VG_LEVELS];
    for (int k = 1; k <= sectionLevels; k++) {
        Level& L = lv_[k];
        for (size_t i = 0; i < L.dirtyList.size(); i++) evals[k].push_back({L.dirtyList[i], evalCell(L, L.dirtyList[i], b)});
    }
    // each column's rock under its lowest section, and air over it where no section came (rock before it went lower)
    std::sort(colFills_.begin(), colFills_.end(), [](const ColFill& a, const ColFill& c) {
        return std::tie(a.k, a.cell, a.sx, a.sz) < std::tie(c.k, c.cell, c.sx, c.sz);
    });
    colFills_.erase(std::unique(colFills_.begin(), colFills_.end()), colFills_.end());
    for (const ColFill& f : colFills_) {
        Level& L = lv_[f.k];
        // (a generator's chunk in its slot, given == 3, takes its real columns' fills too: rock under, air over what came,
        // so its far voxels above a real column (far tree crowns) do not float there; one of one id has no slot)
        if (L.slotOf[f.cell] == ~0u || L.lost[f.cell] || (L.given[f.cell] && L.given[f.cell] != 3)) continue;
        int cx, cy, cz;
        cellCoords(L, f.cell, cx, cy, cz);
        int s = L.span(), n = 16 >> f.k, y0 = cy * s, y1 = y0 + s;
        int lx = f.sx - cx * s, lz = f.sz - cz * s;
        uint64_t recv = L.colRecv[(L.ccol(cx, cz) * size_t(s) + size_t(lz)) * size_t(s) + size_t(lx)];
        if (!recv) continue;
        uint32_t gs = L.slotBase + L.slotOf[f.cell];
        int bottom = lowBit(recv);
        int rockTop = std::min(bottom, y1);
        if (rockTop > y0) b.fills.push_back({gs, packV(lx * n, 0, lz * n), packV((lx + 1) * n, (rockTop - y0) * n, (lz + 1) * n), stoneMat});
        for (int y = std::max(bottom, y0); y < y1;) {
            if ((recv >> y) & 1u) {
                y++;
                continue;
            }
            int e = y;
            while (e < y1 && !((recv >> e) & 1u)) e++;
            b.fills.push_back({gs, packV(lx * n, (y - y0) * n, lz * n), packV((lx + 1) * n, (e - y0) * n, (lz + 1) * n), 0});
            y = e;
        }
        L.touched[f.cell] = 1;
    }
    // the words that changed, the chunks rewritten: built (levels 5+ from the level below, which marks them here)
    auto emit = [&](Level& L, uint32_t cell, uint32_t word) {
        if (L.lost[cell]) word = VG_UNLOADED;
        bool mixed = (word & 3u) == VG_MIXED;
        if (word == L.word[cell] && !(mixed && L.touched[cell])) return false;
        coarseJob(b, {L.chunkBase + cell, word, mixed ? word >> 2 : ~0u, VG_NO_COPY});
        L.word[cell] = word;
        b.levels[L.k].grow = true;
        return true;
    };
    auto markParent = [&](Level& L, uint32_t cell) {
        if (L.k + 1 > activeCoarse_ || L.k + 1 < 5) return;
        Level& P = lv_[L.k + 1];
        if (!P.centred) return;
        int cx, cy, cz;
        cellCoords(L, cell, cx, cy, cz);
        int px = fdiv(cx, 2), pz = fdiv(cz, 2), py = cy / 2;
        if (!P.inWindow(px, pz) || py >= P.H) return;
        uint32_t pc = P.cell(px, py, pz);
        if (!P.downDirty[pc]) P.downDirty[pc] = 1, P.downList.push_back(pc);
    };
    for (int k = 1; k <= sectionLevels; k++) {
        Level& L = lv_[k];
        for (auto& [cell, word] : evals[k])
            if (emit(L, cell, word)) markParent(L, cell);
        for (uint32_t c : L.dirtyList) L.dirty[c] = 0, L.touched[c] = 0;
        L.dirtyList.clear();
    }
    for (int k = 5; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        const Level& C = lv_[k - 1];
        for (size_t i = 0; i < L.downList.size(); i++) {
            uint32_t parent = L.downList[i];
            int cx, cy, cz;
            cellCoords(L, parent, cx, cy, cz);
            VgDown d{};
            // (the chunk's place and level, the vote's dither: atlas_voxgrid.slang vgDownMain)
            d.pad[0] = uint32_t(cx);
            d.pad[1] = (uint32_t(cz) & 0xFFFFFu) | uint32_t(cy & 127) << 20 | uint32_t(k & 15) << 27;
            int air = 0, rock = 0, uni = 0;
            uint32_t uniId = ~0u;
            bool uniSame = true;
            bool incomplete = !C.centred;
            for (int o = 0; o < 8 && !incomplete; o++) {
                int ccx = 2 * cx + (o & 1), ccz = 2 * cz + ((o >> 1) & 1), ccy = 2 * cy + (o >> 2);
                if (ccy >= C.H) {
                    d.kid[o] = VG_KID_AIR, air++;
                    continue;
                }
                if (!C.inWindow(ccx, ccz)) {
                    incomplete = true;
                    break;
                }
                uint32_t cc = C.cell(ccx, ccy, ccz), w = C.word[cc];
                if ((w & 3u) == VG_MIXED) d.kid[o] = w >> 2;
                else if ((w & 3u) == VG_EMPTY) d.kid[o] = VG_KID_AIR, air++;
                else if ((w & 3u) == VG_UNIFORM) {
                    uint32_t id = (w >> 2) & 0xFFFFu;
                    d.kid[o] = VG_KID_UNIFORM | id, uni++;
                    uniSame = uniSame && (uniId == ~0u || uniId == id);
                    uniId = id;
                }
                else if (C.rock[cc]) d.kid[o] = VG_KID_ROCK, rock++;
                else incomplete = true;
            }
            if (L.given[parent]) {
                // the generator's chunk stands until its children are all known; then it is made from them
                if (incomplete) {
                    L.downDirty[parent] = 0;
                    continue;
                }
                L.given[parent] = 0;
            }
            uint32_t word = VG_UNLOADED;
            L.rock[parent] = 0;
            if (L.lost[parent] || incomplete) {
                // (unknown until its children are: its slot back, it holds nothing)
                if (!L.lost[parent] && L.slotOf[parent] != ~0u) {
                    L.freeSlots.push_back(L.slotOf[parent]);
                    L.slotCell[L.slotOf[parent]] = ~0u;
                    L.slotOf[parent] = ~0u;
                    L.used--;
                }
            } else if (uni == 8 && uniSame) {
                // eight children of one id: one id, no slot
                if (L.slotOf[parent] != ~0u) {
                    L.freeSlots.push_back(L.slotOf[parent]);
                    L.slotCell[L.slotOf[parent]] = ~0u;
                    L.slotOf[parent] = ~0u;
                    L.used--;
                }
                word = uniId << 2 | VG_UNIFORM;
                L.slotless = true;
            } else if (air == 8) {
                word = VG_EMPTY;
            } else if (rock == 8) {
                L.rock[parent] = 1;
            } else {
                uint32_t slot = L.slotOf[parent];
                if (slot == ~0u) slot = coarseSlot(L, parent, b);
                if (slot != ~0u) {
                    d.slot = L.slotBase + slot;
                    d.rock = stoneMat;
                    b.downs[k].push_back(d);
                    L.touched[parent] = 1;
                    word = d.slot << 2 | VG_MIXED;
                }
            }
            if (emit(L, parent, word)) markParent(L, parent);
            L.downDirty[parent] = 0;
        }
        // (touched until the batch is made: a parent rebuilt here is never another's victim in it)
        for (uint32_t p : L.downList) L.touched[p] = 0;
        L.downList.clear();
        // (the generator's chunks given at this level: their words)
        for (uint32_t c : L.dirtyList)
            if (L.given[c] && emit(L, c, evalCell(L, c, b))) markParent(L, c);
        for (uint32_t c : L.dirtyList) L.dirty[c] = 0, L.touched[c] = 0;  // (coarseSlot's marks)
        L.dirtyList.clear();
    }
}

void packTextures(const TextureSet& t, std::vector<uint32_t>& out) {
    uint32_t n = uint32_t(t.byMaterial.size()), slots = 0;
    out.assign(1 + size_t(n), 0u);
    out[0] = n;
    for (uint32_t m = 0; m < n; m++) {
        const MaterialTexture* tx = t.at(m);
        if (!tx) continue;
        out[1 + m] = ++slots;
        for (int f = 0; f < 6; f++) {
            // the mip chain: each texel the mean of its four children, colour weighted by their alpha
            std::vector<float> cur(256 * 4);
            for (int i = 0; i < 256; i++) {
                uint32_t c = tx->faces[f][i];
                cur[size_t(i) * 4 + 0] = float((c >> 16) & 255u);
                cur[size_t(i) * 4 + 1] = float((c >> 8) & 255u);
                cur[size_t(i) * 4 + 2] = float(c & 255u);
                cur[size_t(i) * 4 + 3] = float(c >> 24);
            }
            int side = 16;
            for (;;) {
                for (int i = 0; i < side * side; i++) {
                    auto q = [](float v) { return uint32_t(std::clamp(v + 0.5f, 0.0f, 255.0f)); };
                    out.push_back(q(cur[size_t(i) * 4 + 3]) << 24 | q(cur[size_t(i) * 4]) << 16 | q(cur[size_t(i) * 4 + 1]) << 8 |
                                  q(cur[size_t(i) * 4 + 2]));
                }
                if (side == 1) break;
                int h = side / 2;
                std::vector<float> nx(size_t(h) * h * 4, 0.0f);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < h; x++) {
                        float rgb[3] = {0, 0, 0}, wsum = 0, a = 0;
                        for (int k = 0; k < 4; k++) {
                            size_t c = (size_t(2 * y + (k >> 1)) * side + size_t(2 * x + (k & 1))) * 4;
                            float w = cur[c + 3] + 1e-3f;
                            for (int j = 0; j < 3; j++) rgb[j] += cur[c + size_t(j)] * w;
                            wsum += w;
                            a += cur[c + 3];
                        }
                        size_t o = (size_t(y) * h + size_t(x)) * 4;
                        for (int j = 0; j < 3; j++) nx[o + size_t(j)] = rgb[j] / wsum;
                        nx[o + 3] = a / 4.0f;
                    }
                cur.swap(nx);
                side = h;
            }
        }
    }
}

// ---- the CPU reference build -----------------------------------------------------------------------------------

namespace {
// Section::buildMips' vote over a coarse voxel's 8 children (x + 2 z + 4 y), for the levels made from the level below
// (atlas_voxgrid.slang voteMip)
uint16_t voteMip(const uint16_t ch[8], const std::vector<uint8_t>& cls) {
    return voteCoarse(ch, cls);  // (world.hpp: bricks by their coverage, atlas_voxgrid.slang voteMip)
}
}  // namespace

void VoxGrid::applyCpu(const VgBatch& b) {
    size_t n = size_t(D) * H * D;
    size_t total = n;
    for (int k = 1; k <= activeCoarse_; k++) total += size_t(lv_[k].D) * lv_[k].H * lv_[k].D;
    if (chunks.size() != total) chunks.assign(total, VG_UNLOADED);
    if (b.clear) std::fill(chunks.begin(), chunks.begin() + ptrdiff_t(n), VG_UNLOADED);
    cpuOx = b.ox;
    cpuOz = b.oz;
    auto ensureSlot = [&](size_t s) {
        if (pool.size() < (s + 1) * VG_SECTION_WORDS) {
            pool.resize((s + 1) * VG_SECTION_WORDS);
            blocks.resize((s + 1) * 64);
            bits.resize((s + 1) * 64);
        }
    };
    auto setId = [&](size_t s, int i, uint32_t id) {
        uint32_t& w = pool[s * VG_SECTION_WORDS + size_t(i >> 1)];
        w = (w & ~(0xFFFFu << (16 * (i & 1)))) | id << (16 * (i & 1));
    };
    auto getId = [&](size_t s, int i) { return (pool[s * VG_SECTION_WORDS + size_t(i >> 1)] >> (16 * (i & 1))) & 0xFFFFu; };
    // the coarse levels: windows, fills, the sections' generated levels, the levels made from the level below
    for (int k = 1; k <= activeCoarse_; k++) {
        const Level& L = lv_[k];
        cpuLevelO[k][0] = b.levels[k].ox;
        cpuLevelO[k][1] = b.levels[k].oz;
        if (b.levels[k].clear)
            std::fill(chunks.begin() + ptrdiff_t(L.chunkBase), chunks.begin() + ptrdiff_t(L.chunkBase + size_t(L.D) * L.H * L.D), VG_UNLOADED);
    }
    for (const auto* list : {&b.clears, &b.fills})
        for (const VgFill& f : *list) {
            ensureSlot(f.slot);
            for (uint32_t y = (f.lo >> 8) & 255u; y < ((f.hi >> 8) & 255u); y++)
                for (uint32_t z = f.lo >> 16; z < (f.hi >> 16); z++)
                    for (uint32_t x = f.lo & 255u; x < (f.hi & 255u); x++) setId(f.slot, int(x + 16 * (z + 16 * y)), f.mat);
        }
    static const std::vector<uint8_t> noClasses;
    const std::vector<uint8_t>& cls = b.classes ? *b.classes : noClasses;
    for (const VgIngest& ing : b.ingests) {
        if (ing.src & (VG_GPU_SRC | VG_PACK_SRC)) continue;  // (ingestGpu's, packed ones: the Vulkan backend's alone)
        Section sec;
        std::memcpy(sec.mats.data(), b.secs[ing.src]->data(), 4096 * 2);
        sec.buildMips(cls);
        for (int t = 0; t < 4; t++) {
            if (ing.target[t] == ~0u) continue;
            size_t s = ing.target[t] >> 12;
            int k = t + 1, nv = 16 >> k;
            int ox = int(ing.target[t] & 15u) * nv, oy = int((ing.target[t] >> 4) & 15u) * nv, oz = int((ing.target[t] >> 8) & 15u) * nv;
            ensureSlot(s);
            for (int y = 0; y < nv; y++)
                for (int z = 0; z < nv; z++)
                    for (int x = 0; x < nv; x++) setId(s, (ox + x) + 16 * ((oz + z) + 16 * (oy + y)), sec.at(k, x, y, z));
        }
    }
    for (int k = 5; k < VG_LEVELS; k++)
        for (const VgDown& d : b.downs[k]) {
            ensureSlot(d.slot);
            for (uint32_t kid : d.kid)
                if (kid != VG_KID_AIR && kid != VG_KID_ROCK && kid < VG_KID_UNIFORM) ensureSlot(kid);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        uint32_t kid = d.kid[(x >> 3) + 2 * (z >> 3) + 4 * (y >> 3)];
                        uint16_t ch[8];
                        for (int c = 0; c < 8; c++) {
                            int cx = 2 * (x & 7) + (c & 1), cz = 2 * (z & 7) + ((c >> 1) & 1), cy = 2 * (y & 7) + (c >> 2);
                            ch[c] = kid == VG_KID_AIR ? 0 : kid == VG_KID_ROCK ? uint16_t(d.rock)
                                  : kid >= VG_KID_UNIFORM ? uint16_t(kid & 0xFFFFu) : uint16_t(getId(kid, cx + 16 * (cz + 16 * cy)));
                        }
                        setId(d.slot, x + 16 * (z + 16 * y), voteMip(ch, cls));
                    }
        }
    for (const VgJob& j : b.jobs) {
        if (j.slot == VG_COLUMN) {
            for (int y = 0; y < H; y++) chunks[j.chunk + size_t(y) * D * D] = j.word;
            continue;
        }
        chunks[j.chunk] = j.word;
        if (j.slot == ~0u) continue;
        if (j.data != VG_NO_COPY && (j.data & (VG_GPU_SRC | VG_PACK_SRC))) {  // (ingestGpu's, packed ones: not here)
            chunks[j.chunk] = VG_UNLOADED;
            continue;
        }
        size_t s = j.slot;
        ensureSlot(s);
        if (j.data != VG_NO_COPY) std::memcpy(pool.data() + s * VG_SECTION_WORDS, b.secs[j.data]->data(), VG_SECTION_WORDS * 4);
        const uint32_t* w = pool.data() + s * VG_SECTION_WORDS;
        uint64_t mask = 0;
        uint64_t bb[64];
        for (int bi = 0; bi < 64; bi++) {
            int bx = bi & 3, bz = (bi >> 2) & 3, by = bi >> 4;
            uint64_t v = 0;
            for (int ly = 0; ly < 4; ly++)
                for (int lz = 0; lz < 4; lz++)
                    for (int lx = 0; lx < 4; lx++) {
                        int x = bx * 4 + lx, y = by * 4 + ly, z = bz * 4 + lz;
                        int i = x + 16 * (z + 16 * y);
                        uint32_t id = (w[i >> 1] >> (16 * (i & 1))) & 0xFFFFu;
                        if (id) v |= uint64_t(1) << (lx + 4 * lz + 16 * ly);
                    }
            bb[bi] = v;
            if (v) mask |= uint64_t(1) << bi;
        }
        if (!mask) chunks[j.chunk] = VG_EMPTY;  // (a coarse chunk the vote left empty)
        for (int bi = 0; bi < 64; bi++) {
            bits[s * 64 + size_t(bi)] = bb[bi];
            if (bb[bi]) {
                blocks[s * 64 + size_t(bi)] = 1u;
                continue;
            }
            int bx = bi & 3, bz = (bi >> 2) & 3, by = bi >> 4;
            uint32_t e = growBox(bx, by, bz, 4, 4, 4, 3, 2, [&](const int lo[3], const int hi[3]) {
                for (int y = lo[1]; y <= hi[1]; y++)
                    for (int z = lo[2]; z <= hi[2]; z++)
                        for (int x = lo[0]; x <= hi[0]; x++)
                            if ((mask >> (x + 4 * (z + 4 * y))) & 1) return true;
                return false;
            });
            blocks[s * 64 + size_t(bi)] = e << 2;
        }
    }
    if (b.chunkStates) growChunksCpu();
    for (int k = 1; k <= activeCoarse_; k++)
        if (b.levels[k].grow) growLevelCpu(lv_[k].D, lv_[k].H, lv_[k].chunkBase, cpuLevelO[k][0], cpuLevelO[k][1]);
    if (std::getenv("ATLAS_VOXGRID_VERIFY")) std::fprintf(stderr, "voxgrid verify: %zu errors\n", verifyCpu());
    if (const char* pr = std::getenv("ATLAS_VOXGRID_PROBE")) {  // (debugging) one world block: its chunk word and id
        int wx, wy, wz;
        if (std::sscanf(pr, "%d,%d,%d", &wx, &wy, &wz) == 3) {
            int cx = fdiv(wx, 16) - cpuOx, cy = fdiv(wy, 16) - VG_MIN_SY, cz = fdiv(wz, 16) - cpuOz;
            if (cx >= 0 && cy >= 0 && cz >= 0 && cx < D && cy < H && cz < D) {
                uint32_t c = chunks[(size_t(cy) * D + cz) * D + cx];
                int id = -1;
                if ((c & 3u) == VG_MIXED) {
                    int i = (wx & 15) + 16 * ((wz & 15) + 16 * (wy & 15));
                    id = int((pool[(c >> 2) * VG_SECTION_WORDS + size_t(i >> 1)] >> (16 * (i & 1))) & 0xFFFFu);
                }
                std::fprintf(stderr, "voxgrid probe %d,%d,%d: chunk %08x id %d\n", wx, wy, wz, c, id);
            }
        }
    }
}

size_t VoxGrid::verifyCpu() const {
    // every empty box holds only empty cells, every bitmap matches its ids, every block word its bitmap
    size_t errors = 0;
    auto chunkAt = [&](int x, int y, int z) { return chunks[(size_t(y) * D + size_t(pmod(z + cpuOz, D))) * D + size_t(pmod(x + cpuOx, D))]; };
    for (int y = 0; y < H; y++)
        for (int z = 0; z < D; z++)
            for (int x = 0; x < D; x++) {
                uint32_t c = chunkAt(x, y, z);
                if ((c & 3u) == VG_EMPTY) {
                    int e[6];
                    for (int d = 0; d < 6; d++) e[d] = int((c >> (2 + 5 * d)) & 31u);
                    for (int yy = y - e[2]; yy <= y + e[3]; yy++)
                        for (int zz = z - e[4]; zz <= z + e[5]; zz++)
                            for (int xx = x - e[0]; xx <= x + e[1]; xx++)
                                if (xx < 0 || yy < 0 || zz < 0 || xx >= D || yy >= H || zz >= D || (chunkAt(xx, yy, zz) & 3u) != VG_EMPTY)
                                    errors++;
                } else if ((c & 3u) == VG_MIXED) {
                    size_t s = c >> 2;
                    const uint32_t* w = pool.data() + s * VG_SECTION_WORDS;
                    for (int i = 0; i < 4096; i++) {
                        int vx = i & 15, vz = (i >> 4) & 15, vy = i >> 8;
                        int bi = (vx >> 2) + 4 * ((vz >> 2) + 4 * (vy >> 2));
                        bool solid = ((w[i >> 1] >> (16 * (i & 1))) & 0xFFFFu) != 0;
                        bool bit = (bits[s * 64 + size_t(bi)] >> ((vx & 3) + 4 * (vz & 3) + 16 * (vy & 3))) & 1;
                        if (solid != bit) errors++;
                    }
                    for (int bi = 0; bi < 64; bi++) {
                        uint32_t bw = blocks[s * 64 + size_t(bi)];
                        if ((bw & 1u) != (bits[s * 64 + size_t(bi)] ? 1u : 0u)) errors++;
                        if (bw & 1u) continue;
                        int bx = bi & 3, bz = (bi >> 2) & 3, by = bi >> 4, e[6];
                        for (int d = 0; d < 6; d++) e[d] = int((bw >> (2 + 2 * d)) & 3u);
                        for (int yy = by - e[2]; yy <= by + e[3]; yy++)
                            for (int zz = bz - e[4]; zz <= bz + e[5]; zz++)
                                for (int xx = bx - e[0]; xx <= bx + e[1]; xx++)
                                    if (xx < 0 || yy < 0 || zz < 0 || xx > 3 || yy > 3 || zz > 3 ||
                                        bits[s * 64 + size_t(xx + 4 * (zz + 4 * yy))])
                                        errors++;
                    }
                }
            }
    return errors;
}

void VoxGrid::growChunksCpu() { growLevelCpu(D, H, 0, cpuOx, cpuOz); }

void VoxGrid::growLevelCpu(int Dl, int Hl, uint32_t base, int ox, int oz) {
    // a summed-volume table of the cells that stop a box (anything not EMPTY), then each EMPTY cell's box; in the
    // window's own order (logical x, z from its origin; the cells in memory at x mod D, z mod D)
    int X = Dl, Y = Hl, Z = Dl;
    auto cell = [&](int x, int y, int z) -> uint32_t& {
        return chunks[base + (size_t(y) * Z + size_t(pmod(z + oz, Z))) * X + size_t(pmod(x + ox, X))];
    };
    std::vector<uint32_t> S(size_t(X + 1) * (Y + 1) * (Z + 1), 0);
    auto at = [&](int x, int y, int z) -> uint32_t& { return S[(size_t(y) * (Z + 1) + size_t(z)) * (X + 1) + size_t(x)]; };
    // (twice: the EMPTY cells' boxes of empty cells, then the UNLOADED cells' of unloaded ones, as vgGrowMain)
    for (uint32_t target : {VG_EMPTY, VG_UNLOADED}) {
    std::fill(S.begin(), S.end(), 0u);
    for (int y = 0; y < Y; y++)
        for (int z = 0; z < Z; z++)
            for (int x = 0; x < X; x++) {
                uint32_t occ = (cell(x, y, z) & 3u) != target ? 1u : 0u;
                at(x + 1, y + 1, z + 1) = occ + at(x, y + 1, z + 1) + at(x + 1, y, z + 1) + at(x + 1, y + 1, z) - at(x, y, z + 1) -
                                          at(x, y + 1, z) - at(x + 1, y, z) + at(x, y, z);
            }
    auto boxOcc = [&](const int lo[3], const int hi[3]) {
        int x0 = lo[0], y0 = lo[1], z0 = lo[2], x1 = hi[0] + 1, y1 = hi[1] + 1, z1 = hi[2] + 1;
        int64_t s = int64_t(at(x1, y1, z1)) - at(x0, y1, z1) - at(x1, y0, z1) - at(x1, y1, z0) + at(x0, y0, z1) + at(x0, y1, z0) +
                    at(x1, y0, z0) - at(x0, y0, z0);
        return s != 0;
    };
    for (int y = 0; y < Y; y++)
        for (int z = 0; z < Z; z++)
            for (int x = 0; x < X; x++) {
                uint32_t& c = cell(x, y, z);
                if ((c & 3u) != target) continue;
                c = growBox(x, y, z, X, Y, Z, 31, 5, boxOcc) << 2 | target;
            }
    }
}

}  // namespace atlas
