#include "voxgrid.hpp"

#include <algorithm>
#include <bit>
#include <climits>
#include <tuple>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "groups.hpp"
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
    if (const char* e = std::getenv("ATLAS_VOXGRID_COARSE_SLOTS")) {
        int k = 1;
        for (const char* p = e; *p && k < VG_LEVELS; k++) {
            coarseCap[k] = uint32_t(std::clamp(std::atoi(p), 16, 1 << 18));
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

void VoxGrid::planMemory(uint64_t deviceBytes) {
    std::lock_guard<std::mutex> lock(mu_);
    if (laidOut_ || centred_ || !deviceBytes || std::getenv("ATLAS_VOXGRID_SLOTS")) return;  // (fixed, or by hand)
    double share = 0.10;
    if (const char* e = std::getenv("ATLAS_VOXGRID_MEM")) share = std::clamp(std::atof(e), 0.01, 0.6);
    uint64_t slots = uint64_t(double(deviceBytes) * share) / SLOT_BYTES;
    // (never more than a slot a cell of the window, never fewer than the old fixed pool)
    slotCap = uint32_t(std::clamp<uint64_t>(slots, 16384, uint64_t(D) * H * D));
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
    Residency r;
    r.resident = slotsUsed_;
    r.cap = slotCap;
    r.uniform = uniform_;
    r.waiting = standby_.size();
    r.gpuWaiting = gpuStandby_.size();
    r.evicted = stats_.evicted;
    r.misses = misses_;
    r.gpuUniform = gpuUniformHits_;
    r.cutoff = std::min(cutoff_, reach_);
    r.reach = reach_;
    r.nearestMissing = nearestMissing_;
    return r;
}

std::vector<std::array<int32_t, 3>> VoxGrid::takeGpuWanted() {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<std::array<int32_t, 3>> r;
    r.swap(gpuWanted_);
    return r;
}

void VoxGrid::gpuPendingOffsets(uint64_t buffer, std::vector<uint64_t>& out) const {
    out.clear();
    std::lock_guard<std::mutex> lock(mu_);
    for (auto& [k, g] : gpuRef_)
        if (g.buffer == buffer) out.push_back(g.offset);
}

void VoxGrid::gpuUniform(const std::vector<UniformHit>& hits) {
    std::lock_guard<std::mutex> lock(mu_);
    for (const UniformHit& h : hits) {
        // (only the job it was found for: the cell not rewritten since, its slot still its own)
        if (h.chunk >= slotOf_.size() || h.chunk >= cellGen_.size() || h.slot >= slotChunk_.size()) continue;
        if (slotOf_[h.chunk] != h.slot || slotChunk_[h.slot] != h.chunk || cellGen_[h.chunk] != h.gen || state_[h.chunk] != VG_MIXED) continue;
        freeSlots_.push_back(h.slot);
        slotChunk_[h.slot] = ~0u;
        slotIds_[h.slot] = nullptr;
        slotOf_[h.chunk] = ~0u;
        slotsUsed_--;
        // (the build wrote its word already: VG_UNIFORM with the id, or VG_EMPTY)
        state_[h.chunk] = uint8_t(h.id ? VG_UNIFORM : VG_EMPTY);
        if (h.id) uniform_++;
        gpuUniformHits_++;
        rankDue_ = true;
    }
}

void VoxGrid::dropOutside() {
    auto out = [&](const Key& k) { return !inWindow(k.x, k.y, k.z); };
    for (auto it = standby_.begin(); it != standby_.end();) it = out(it->first) ? standby_.erase(it) : std::next(it);
    for (auto it = gpuStandby_.begin(); it != gpuStandby_.end();) it = out(it->first) ? gpuStandby_.erase(it) : std::next(it);
    for (auto it = gpuUniformHint_.begin(); it != gpuUniformHint_.end();)
        it = pending_.count(it->first) || cpending_.count(it->first) ? std::next(it) : gpuUniformHint_.erase(it);
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
        if (level0) gpuStandby_.erase({sx, sy, sz});
        if (level0 && uni == VG_NOT_UNIFORM && slotOf_[phys(sx, sy, sz)] == ~0u) {
            // a full pool takes a new one only within its cut-off; past the footprint's reach level 0 wants none: it
            // waits, asked for again once it ranks (takeGpuWanted)
            float p = priority(sx, sy, sz);
            if (p >= 1e29f || (slotsUsed_ >= slotCap && freeSlots_.empty() && p > cutoff_)) {
                gpuStandby_[{sx, sy, sz}] = 0;
                level0 = false;
            }
        }
        uint32_t levels = 0;
        for (int k = 1; k <= activeCoarse_; k++) {
            const Level& L = lv_[k];
            int s = L.span();
            if (L.centred && L.inWindow(fdiv(sx, s), fdiv(sz, s)) && sy >= VG_MIN_SY && (sy - VG_MIN_SY) / s < L.H) levels |= 1u << k;
        }
        if (!level0 && !levels) continue;  // (outside every window, or before the first frame: not taken)
        Key key{sx, sy, sz};
        if (accepted) accepted[i] = 1;
        n++;
        // the levels that will hold it (it is let go, takeGpuDropped, once none does)
        uint32_t& hold = gpuHold_[key];
        if (!hold) gpuCol_[colKey(sx, sz)].push_back(int16_t(sy));
        hold |= levels | (level0 ? 1u : 0u);
        gpuRef_[key] = {buffer, offsets[i]};
        if (uni != VG_NOT_UNIFORM) gpuUniformHint_[key] = uni;
        else gpuUniformHint_.erase(key);
        if (level0) pending_[key] = nullptr;  // (null: its ids are gpuRef_'s)
        if (levels) {
            CPending& p = cpending_[key];
            p.ids = nullptr;
            p.levels |= levels;
        }
    }
    return n;
}

size_t VoxGrid::gpuSectionsPending() const {
    std::lock_guard<std::mutex> lock(mu_);
    return gpuRef_.size();
}

std::vector<std::array<int32_t, 3>> VoxGrid::takeGpuDropped() {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<std::array<int32_t, 3>> r;
    r.swap(gpuDropped_);
    return r;
}

void VoxGrid::gpuLetGo(int sx, int sy, int sz, uint32_t bits) {
    auto it = gpuHold_.find({sx, sy, sz});
    if (it == gpuHold_.end() || !(it->second & bits)) return;
    it->second &= ~bits;
    if (it->second) return;
    gpuHold_.erase(it);
    gpuDropped_.push_back({sx, sy, sz});
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
    // before the window is placed the world keeps them (frame() reads them from there); outside it they wait there too
    bool level0 = centred_ && inWindow(sx, sy, sz);
    uint32_t levels = 0;
    for (int k = 1; k <= activeCoarse_; k++) {
        const Level& L = lv_[k];
        int s = L.span();
        if (L.centred && L.inWindow(fdiv(sx, s), fdiv(sz, s)) && sy >= VG_MIN_SY && (sy - VG_MIN_SY) / s < L.H) levels |= 1u << k;
    }
    if (!level0 && !levels) return;
    // (one entry a section whatever its source: the newest ids replace what waited)
    standby_.erase({sx, sy, sz});
    gpuStandby_.erase({sx, sy, sz});
    gpuUniformHint_.erase({sx, sy, sz});
    if (level0) pending_[{sx, sy, sz}] = a;
    if (levels) {
        CPending& p = cpending_[{sx, sy, sz}];
        p.ids = std::move(a);
        p.levels |= levels;
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
    return pending_.size() + (clearPending_ ? 1 : 0) + (freshColumns_ ? 1 : 0) + (vacated_.empty() ? 0 : 1);
}

VoxGrid::Stats VoxGrid::stats() const {
    std::lock_guard<std::mutex> lock(mu_);
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
    std::lock_guard<std::mutex> lock(mu_);
    camSx_ = fdiv(camBlock[0], 16);
    camSz_ = fdiv(camBlock[2], 16);
    for (int i = 0; i < 3; i++) camB_[i] = camBlock[i];
    // level 0's reach: where a pixel covers FOOT blocks (ATLAS_VOXGRID_FOOT, 2: level 0's share of the continuous
    // level, floor(log2(d x angle) + bias + xi), is gone there)
    static const float foot = [] {
        const char* e = std::getenv("ATLAS_VOXGRID_FOOT");
        return e ? std::clamp(float(std::atof(e)), 0.25f, 64.0f) : 2.0f;
    }();
    reach_ = pixelAngle_ > 0.0f ? foot * std::exp2(-levelBias_) / pixelAngle_ : 1e30f;
    stats_.frames++;
    // the window follows the camera a few sections behind (each move frees the columns it leaves and reads the ones
    // it enters: the cells keep their place in memory, chunk (x, y, z) at (x mod D, y, z mod D))
    int cx = ox_ + D / 2, cz = oz_ + D / 2;
    if (!centred_ || std::abs(camSx_ - cx) > FOLLOW || std::abs(camSz_ - cz) > FOLLOW) shift(camSx_ - D / 2, camSz_ - D / 2, world);
    // (placed, or a jump past the whole window: read here at once, a few ms this one frame, nothing of the window known;
    // a shift's strips are the update thread's, here only when it has not come for half a second)
    if (freshColumns_ && (freshColumns_ == uint32_t(D * D) || stats_.frames - stats_.lastShiftFrame > 30)) refillLocked(world, false);
    // the coarse levels follow too; placed (or a jump) they read the world here at once when its lock is free
    camSy_ = fdiv(camBlock[1], 16);
    if (coarseLevels > 0 && !laidOut_) layOut();
    bool placed = false;
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        placeLevel(L, camSx_, camSz_);
        placed |= L.freshCount == uint32_t(L.D * L.D);
    }
    if (placed) refillCoarse(world, false);
}

void VoxGrid::refill(World& world) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mu_);
    if (freshColumns_) refillLocked(world, true);
    bool fresh = false;
    for (int k = 1; k <= activeCoarse_; k++) fresh |= lv_[k].freshCount > 0;
    if (fresh) refillCoarse(world, true);
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
        L.touched.assign(n, 0);
        L.dirty.assign(n, 0);
        L.downDirty.assign(n, 0);
        L.slotCell.assign(L.cap, ~0u);
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
    out[3] = L.D | L.H << 8 | 1 << 16 | (L.used > 0 || !cpending_.empty() ? 1 << 17 : 0);
}

void VoxGrid::placeLevel(Level& L, int camSx, int camSz) {
    int s = L.span();
    int ccx = fdiv(camSx, s), ccz = fdiv(camSz, s);
    int follow = std::max(1, 8 >> L.k);  // (chunks: 4 at level 1, then 2, then 1)
    int nox = ccx - L.D / 2, noz = ccz - L.D / 2;
    if (L.centred && std::abs(ccx - (L.ox + L.D / 2)) <= follow && std::abs(ccz - (L.oz + L.D / 2)) <= follow) return;
    size_t n = size_t(L.D) * L.H * L.D;
    if (!L.centred || std::abs(nox - L.ox) >= L.D || std::abs(noz - L.oz) >= L.D) {
        // placed, or a jump past the window: nothing of it known
        L.slotOf.assign(n, ~0u);
        L.word.assign(n, VG_UNLOADED);
        L.lost.assign(n, 0);
        L.rock.assign(n, 0);
        L.slotCell.assign(L.cap, ~0u);
        L.freeSlots.clear();
        L.nextSlot = 0;
        L.used = 0;
        L.cols.clear();
        L.vacated.clear();
        L.fresh.assign(size_t(L.D) * L.D, 1);
        L.freshCount = uint32_t(L.D * L.D);
        L.clearPending = true;
        L.jumps++;
        for (auto& [key, p] : cpending_) p.levels &= ~(1u << L.k);  // (read again from the world)
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
                    if (L.word[c] != VG_UNLOADED) L.vacated.push_back(c);
                    L.word[c] = VG_UNLOADED;
                }
                for (int dz = 0; dz < s; dz++)
                    for (int dx = 0; dx < s; dx++) {
                        L.cols.erase(colKey(cx * s + dx, cz * s + dz));
                        if (!gpuCol_.empty()) gpuLetGoColumn(cx * s + dx, cz * s + dz, 1u << L.k);
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

void VoxGrid::refillCoarse(World& world, bool wait) {
    // the union of the levels' windows with columns to read (the sections the world still holds there)
    int x0 = INT_MAX, z0 = INT_MAX, x1 = INT_MIN, z1 = INT_MIN;
    for (int k = 1; k <= activeCoarse_; k++) {
        const Level& L = lv_[k];
        if (!L.freshCount) continue;
        int s = L.span();
        x0 = std::min(x0, L.ox * s), z0 = std::min(z0, L.oz * s);
        x1 = std::max(x1, (L.ox + L.D) * s), z1 = std::max(z1, (L.oz + L.D) * s);
    }
    if (x0 > x1) return;
    std::vector<std::pair<Key3, std::shared_ptr<const Section>>> secs;
    if (wait) world.sectionsIn(x0, z0, x1, z1, secs);
    else if (!world.trySectionsIn(x0, z0, x1, z1, secs)) return;
    for (auto& [key, sec] : secs) {
        uint32_t levels = 0;
        for (int k = 1; k <= activeCoarse_; k++) {
            const Level& L = lv_[k];
            if (!L.freshCount) continue;
            int s = L.span(), cx = fdiv(key.x, s), cz = fdiv(key.z, s);
            if (!L.inWindow(cx, cz) || key.y < VG_MIN_SY || (key.y - VG_MIN_SY) / s >= L.H) continue;
            if (L.fresh[size_t(pmod(cz, L.D)) * L.D + size_t(pmod(cx, L.D))]) levels |= 1u << k;
        }
        if (!levels) continue;
        CPending& p = cpending_[{key.x, key.y, key.z}];
        if (!p.ids) p.ids = Ids(sec, &sec->mats);  // (an upload since is newer)
        p.levels |= levels;
    }
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        if (!L.freshCount) continue;
        std::fill(L.fresh.begin(), L.fresh.end(), uint8_t(0));
        L.freshCount = 0;
    }
}

void VoxGrid::refillLocked(World& world, bool wait) {
    // the world's sections of the columns the window entered (shared, not copied); on the render thread only when the
    // world's lock is free (never waiting for an update building regions), on the update thread always (refill)
    std::vector<std::pair<Key3, std::shared_ptr<const Section>>> secs;
    if (wait) world.sectionsIn(ox_, oz_, ox_ + D, oz_ + D, secs);
    else if (!world.trySectionsIn(ox_, oz_, ox_ + D, oz_ + D, secs)) return;
    for (auto& [k, s] : secs) {
        if (!inWindow(k.x, k.y, k.z) || !colFresh_[physCol(k.x, k.z)]) continue;
        if (!pending_.count({k.x, k.y, k.z})) pending_[{k.x, k.y, k.z}] = Ids(s, &s->mats);  // (an upload since is newer)
    }
    std::fill(colFresh_.begin(), colFresh_.end(), uint8_t(0));
    freshColumns_ = 0;
    stats_.refills++;
    stats_.lastRefillFrame = stats_.frames;
}

void VoxGrid::shift(int nox, int noz, World& world) {
    size_t n = size_t(D) * H * D;
    if (!centred_ || std::abs(nox - ox_) >= D || std::abs(noz - oz_) >= D) {
        // placed (or a jump past the whole window): every chunk unknown, every column read again
        slotOf_.assign(n, ~0u);
        state_.assign(n, uint8_t(VG_UNLOADED));
        colTop_.assign(size_t(D) * D, NO_TOP);
        colFresh_.assign(size_t(D) * D, uint8_t(1));
        freshColumns_ = uint32_t(D * D);
        slotChunk_.assign(slotCap, ~0u);
        slotIds_.assign(slotCap, nullptr);
        cellGen_.assign(n, 0);
        freeSlots_.clear();
        nextSlot_ = 0;
        slotsUsed_ = 0;
        uniform_ = 0;
        pending_.clear();
        standby_.clear();
        gpuStandby_.clear();
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
                        slotIds_[slotOf_[c]] = nullptr;
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
    std::lock_guard<std::mutex> lock(mu_);
    if (!enabled) return b;
    if (centred_) take0(b, maxSections);
    b.level0Jobs = b.jobs.size();
    if (activeCoarse_) takeCoarse(b);
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
    if (slotIds_.size() != slotCap) slotIds_.resize(slotCap);
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
        slotIds_[slotOf_[chunk]] = nullptr;
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
        if (slotIds_[slot]) {
            standby_[{vx, vy, vz}] = slotIds_[slot];
        } else {
            gpuLetGo(vx, vy, vz, 1u);
            gpuStandby_[{vx, vy, vz}] = 0;
        }
        slotIds_[slot] = nullptr;
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
            onGpu = !ids;  // (ingestGpu's: their ids on the GPU)
        } else if (cand.from == 1) {
            auto it = standby_.find(k);
            if (it == standby_.end()) continue;
            if (!canTake(cand.p) && slotOf_[c] == ~0u) continue;
            ids = it->second;
        } else {
            // a GPU section level 0 gave up or did not take: asked for again (once) when it would be taken now
            auto it = gpuStandby_.find(k);
            if (it == gpuStandby_.end() || it->second || !canTake(cand.p)) continue;
            it->second = 1;
            gpuWanted_.push_back({k.x, k.y, k.z});
            continue;
        }
        // one id throughout (air, rock, a lake's water): no slot, the id in the chunk's word
        uint32_t uni = VG_NOT_UNIFORM;
        if (onGpu) {
            auto h = gpuUniformHint_.find(k);
            if (h != gpuUniformHint_.end()) uni = h->second;
        } else {
            uni = uniformIdOf(*ids);
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
                    standby_[k] = ids;
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
                } else {
                    b.secs.push_back(ids);  // (shared: the backend copies it once, into its staging)
                }
                slotIds_[slot] = onGpu ? nullptr : ids;
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
    if (pending_.empty() && !freshColumns_ && stats_.lastShiftFrame >= stats_.settledFrame) {
        stats_.settledFrame = stats_.frames;  // (the window's content complete again: frames since its last move)
        stats_.lastSettleFrames = stats_.frames - stats_.lastShiftFrame;
    }
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

uint32_t VoxGrid::coarseSlot(Level& L, uint32_t cell, VgBatch& b) {
    int k = L.k;
    int cx, cy, cz;
    cellCoords(L, cell, cx, cy, cz);
    int ccx = fdiv(camSx_, L.span()), ccz = fdiv(camSz_, L.span());
    int64_t dist = viewDist(cx - ccx, cz - ccz) * 64 + cy;
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
        if (!victimsBuilt_[k]) {
            victimsBuilt_[k] = true;
            vs.clear();
            for (uint32_t s = 0; s < L.nextSlot; s++) {
                uint32_t vc = L.slotCell[s];
                if (vc == ~0u) continue;
                int vx, vy, vz;
                cellCoords(L, vc, vx, vy, vz);
                vs.push_back({viewDist(vx - ccx, vz - ccz) * 64 + vy, vc});
            }
            std::sort(vs.begin(), vs.end());
        }
        while (!vs.empty() && (L.slotOf[vs.back().second] == ~0u || L.touched[vs.back().second])) vs.pop_back();
        if (!vs.empty() && vs.back().first > dist) {
            uint32_t vc = vs.back().second;
            vs.pop_back();
            slot = L.slotOf[vc];
            L.slotOf[vc] = ~0u;
            L.slotCell[slot] = ~0u;
            L.used--;
            L.lost[vc] = 1;
            if (L.word[vc] != VG_UNLOADED) {
                b.jobs.push_back({L.chunkBase + vc, VG_UNLOADED, ~0u, 0});
                b.levels[k].grow = true;
            }
            L.word[vc] = VG_UNLOADED;
            L.evicted++;
        }
    }
    if (slot == ~0u) {
        L.lost[cell] = 1;
        L.overflow++;
        return ~0u;
    }
    L.slotOf[cell] = slot;
    L.slotCell[slot] = cell;
    L.used++;
    L.touched[cell] = 1;
    markDirty(L, cell);
    if (k <= 4) {
        // levels made from sections: the chunk air, then each known column's rock under its lowest section
        b.clears.push_back({L.slotBase + slot, 0, packV(16, 16, 16), 0});
        int s = L.span();
        for (int dz = 0; dz < s; dz++)
            for (int dx = 0; dx < s; dx++)
                if (L.cols.count(colKey(cx * s + dx, cz * s + dz))) colFills_.push_back({k, cell, cx * s + dx, cz * s + dz});
    }
    return slot;
}

uint32_t VoxGrid::evalCell(Level& L, uint32_t cell, VgBatch& b) {
    L.rock[cell] = 0;
    if (L.lost[cell]) return VG_UNLOADED;
    int cx, cy, cz;
    cellCoords(L, cell, cx, cy, cz);
    int s = L.span(), y0 = cy * s, y1 = y0 + s;
    bool allAbove = true, allBelow = true;
    // known when every section column it covers came; above every column's highest section air, under every column's
    // lowest rock
    for (int dz = 0; dz < s; dz++)
        for (int dx = 0; dx < s; dx++) {
            auto it = L.cols.find(colKey(cx * s + dx, cz * s + dz));
            if (it == L.cols.end()) return VG_UNLOADED;
            if (y0 <= highBit(it->second.recv)) allAbove = false;
            if (y1 > lowBit(it->second.recv)) allBelow = false;
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

void VoxGrid::takeCoarse(VgBatch& b) {
    b.classes = classes_;
    for (int k = 1; k <= activeCoarse_; k++) {
        Level& L = lv_[k];
        b.levels[k].ox = L.ox;
        b.levels[k].oz = L.oz;
        victimsBuilt_[k] = false;
        if (!L.centred) continue;
        if (L.clearPending) {
            b.levels[k].clear = b.levels[k].grow = true;
            L.clearPending = false;
            L.vacated.clear();
        }
        for (uint32_t c : L.vacated) b.jobs.push_back({L.chunkBase + c, VG_UNLOADED, ~0u, 0});
        if (!L.vacated.empty()) b.levels[k].grow = true;
        L.vacated.clear();
    }
    colFills_.clear();
    // the batch's sections: nearest the camera first, what it sees before what lies behind it
    std::vector<std::pair<int64_t, Key>> order;
    order.reserve(cpending_.size());
    for (auto it = cpending_.begin(); it != cpending_.end();) {
        if (!it->second.levels) {
            it = cpending_.erase(it);
            continue;
        }
        order.push_back({viewDist(it->first.x - camSx_, it->first.z - camSz_) * 64 + std::abs(it->first.y - camSy_), it->first});
        ++it;
    }
    auto byDist = [](const auto& a, const auto& c) { return a.first < c.first; };
    size_t share = std::min(order.size(), size_t(coarseBudget));
    if (share < order.size()) {
        std::nth_element(order.begin(), order.begin() + ptrdiff_t(share), order.end(), byDist);
        order.resize(share);
    }
    std::sort(order.begin(), order.end(), byDist);
    std::unordered_map<const void*, uint32_t> staged;  // ids level 0 sends already
    for (size_t i = 0; i < b.secs.size(); i++) staged[b.secs[i].get()] = uint32_t(i);
    int sectionLevels = std::min(4, activeCoarse_);
    for (auto& [dist, key] : order) {
        auto pit = cpending_.find(key);
        CPending p = std::move(pit->second);
        cpending_.erase(pit);
        bool onGpu = !p.ids;  // (ingestGpu's: taken as having blocks unless the caller said it is one id)
        uint32_t uni = VG_NOT_UNIFORM;
        if (onGpu) {
            auto h = gpuUniformHint_.find(key);
            if (h != gpuUniformHint_.end()) uni = h->second;
        } else {
            uni = uniformIdOf(*p.ids);
        }
        bool nonAir = uni != 0;
        VgIngest ing{};
        ing.src = ~0u;
        for (uint32_t& t : ing.target) t = ~0u;
        bool any = false;
        for (int k = 1; k <= sectionLevels; k++) {
            Level& L = lv_[k];
            if (!((p.levels >> k) & 1u) || !L.centred) continue;
            int s = L.span(), cx = fdiv(key.x, s), cz = fdiv(key.z, s), by = key.y - VG_MIN_SY;
            if (by < 0 || by >= 64 || by / s >= L.H || !L.inWindow(cx, cz)) continue;
            int cy = by / s;
            Col& col = L.cols[colKey(key.x, key.z)];
            int oldBottom = lowBit(col.recv), oldTop = highBit(col.recv);
            col.recv |= 1ull << by;
            if (nonAir) col.solid |= 1ull << by;
            else col.solid &= ~(1ull << by);
            int bottom = lowBit(col.recv), top = highBit(col.recv);
            uint32_t cell = L.cell(cx, cy, cz);
            L.ingested++;
            if (!L.lost[cell]) {
                uint32_t slot = L.slotOf[cell];
                if (slot == ~0u && nonAir) slot = coarseSlot(L, cell, b);
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
                    if (bottom != oldBottom && L.slotOf[c2] != ~0u && !L.lost[c2]) colFills_.push_back({k, c2, key.x, key.z});
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
        } else if (any) {
            auto f = staged.find(p.ids.get());
            if (f == staged.end()) {
                f = staged.emplace(p.ids.get(), uint32_t(b.secs.size())).first;
                b.secs.push_back(p.ids);
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
        if (L.slotOf[f.cell] == ~0u || L.lost[f.cell]) continue;
        auto it = L.cols.find(colKey(f.sx, f.sz));
        if (it == L.cols.end()) continue;
        int cx, cy, cz;
        cellCoords(L, f.cell, cx, cy, cz);
        int s = L.span(), n = 16 >> f.k, y0 = cy * s, y1 = y0 + s;
        int lx = f.sx - cx * s, lz = f.sz - cz * s;
        uint32_t gs = L.slotBase + L.slotOf[f.cell];
        uint64_t recv = it->second.recv;
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
        b.jobs.push_back({L.chunkBase + cell, word, mixed ? word >> 2 : ~0u, VG_NO_COPY});
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
            int air = 0, rock = 0;
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
                else if (C.rock[cc]) d.kid[o] = VG_KID_ROCK, rock++;
                else incomplete = true;
            }
            uint32_t word = VG_UNLOADED;
            L.rock[parent] = 0;
            if (L.lost[parent] || incomplete) {
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
            L.touched[parent] = 0;
            L.downDirty[parent] = 0;
        }
        L.downList.clear();
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
    auto kind = [&](uint16_t m) -> int {
        if (m == 0) return -1;
        uint8_t c = m < cls.size() ? cls[m] : 0;
        return c == 1 ? 1 : c == 2 ? 2 : 0;
    };
    int counts[3] = {0, 0, 0}, kd[8];
    for (int k = 0; k < 8; k++) {
        kd[k] = kind(ch[k]);
        if (kd[k] >= 0) counts[kd[k]]++;
    }
    int want = counts[0] >= 3 ? 0 : counts[1] >= 2 ? 1 : counts[2] >= 4 ? 2 : -1;
    if (want < 0) return 0;
    bool shown[8];
    for (int a = 0; a < 8; a++) shown[a] = a >= 4 || !ch[a + 4] || kd[a + 4] != kd[a];
    uint16_t best = 0;
    int bestN = 0;
    for (int a = 7; a >= 0; a--) {
        if (!ch[a] || kd[a] != want || !shown[a]) continue;
        int c = 0;
        for (int q = 0; q < 8; q++) c += ch[q] == ch[a] && shown[q] ? 1 : 0;
        if (c > bestN) bestN = c, best = ch[a];
    }
    return best;
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
        if (ing.src & VG_GPU_SRC) continue;  // (ingestGpu's: the Vulkan backend's alone)
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
                if (kid != VG_KID_AIR && kid != VG_KID_ROCK) ensureSlot(kid);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        uint32_t kid = d.kid[(x >> 3) + 2 * (z >> 3) + 4 * (y >> 3)];
                        uint16_t ch[8];
                        for (int c = 0; c < 8; c++) {
                            int cx = 2 * (x & 7) + (c & 1), cz = 2 * (z & 7) + ((c >> 1) & 1), cy = 2 * (y & 7) + (c >> 2);
                            ch[c] = kid == VG_KID_AIR ? 0 : kid == VG_KID_ROCK ? uint16_t(d.rock) : uint16_t(getId(kid, cx + 16 * (cz + 16 * cy)));
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
        if (j.data != VG_NO_COPY && (j.data & VG_GPU_SRC)) {  // (ingestGpu's: not here)
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
