#include "voxgrid.hpp"

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>

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
}

void VoxGrid::put(int sx, int sy, int sz, const uint16_t* mats) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mu_);
    // before the window is placed the world keeps them (frame() reads them from there); outside it they wait there too
    if (!centred_ || !inWindow(sx, sy, sz)) return;
    pending_[{sx, sy, sz}].assign(mats, mats + 4096);
}

void VoxGrid::origin(int32_t o[4]) const {
    std::lock_guard<std::mutex> lock(mu_);
    o[0] = ox_;
    o[1] = VG_MIN_SY;
    o[2] = oz_;
    o[3] = active() ? 1 : 0;
}

size_t VoxGrid::pending() const {
    std::lock_guard<std::mutex> lock(mu_);
    return pending_.size() + (clearPending_ ? 1 : 0);
}

void VoxGrid::frame(const int32_t camBlock[3], World& world) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mu_);
    camSx_ = fdiv(camBlock[0], 16);
    camSz_ = fdiv(camBlock[2], 16);
    int cx = ox_ + D / 2, cz = oz_ + D / 2;
    if (centred_ && std::abs(camSx_ - cx) <= D / 4 && std::abs(camSz_ - cz) <= D / 4) return;
    // (re)centre: every chunk unknown again, the world's sections in the new window queued (nearest first by take)
    ox_ = camSx_ - D / 2;
    oz_ = camSz_ - D / 2;
    centred_ = true;
    size_t n = size_t(D) * H * D;
    slotOf_.assign(n, ~0u);
    state_.assign(n, uint8_t(VG_UNLOADED));
    colTop_.assign(size_t(D) * D, NO_TOP);
    freeSlots_.clear();
    nextSlot_ = 0;
    slotsUsed_ = 0;
    pending_.clear();
    clearPending_ = true;
    world.setGridWindow(ox_, oz_, D);
    std::vector<std::pair<Key3, std::shared_ptr<const Section>>> secs;
    world.sectionsIn(ox_, oz_, ox_ + D, oz_ + D, secs);
    for (auto& [k, s] : secs)
        if (inWindow(k.x, k.y, k.z)) pending_[{k.x, k.y, k.z}].assign(s->mats.begin(), s->mats.end());
}

VgBatch VoxGrid::take(int maxSections) {
    VgBatch b;
    std::lock_guard<std::mutex> lock(mu_);
    if (!enabled || !centred_ || (pending_.empty() && !clearPending_)) return b;
    b.clear = clearPending_;
    b.chunkStates = clearPending_;
    b.ox = ox_;
    b.oz = oz_;
    clearPending_ = false;
    // nearest the camera first (columns, then height: a teleport's own chunks before the ring around it)
    std::vector<std::pair<int64_t, Key>> order;
    order.reserve(pending_.size());
    for (auto& [k, v] : pending_) {
        int64_t dx = k.x - camSx_, dz = k.z - camSz_;
        order.push_back({(dx * dx + dz * dz) * 64 + (k.y - VG_MIN_SY), k});
    }
    std::sort(order.begin(), order.end(), [](const auto& a, const auto& c) { return a.first < c.first; });
    std::unordered_map<uint32_t, size_t> jobAt;  // one job per chunk per batch (the GPU builds them in parallel)
    auto emit = [&](uint32_t chunk, uint32_t word, uint32_t slot, uint32_t data) {
        uint8_t st = uint8_t(word & 3u);
        // empty and empty before: its word (with the box grown around it) stands
        if (st == VG_EMPTY && state_[chunk] == VG_EMPTY && !b.clear && !jobAt.count(chunk)) return;
        if (st != state_[chunk]) b.chunkStates = true;
        state_[chunk] = st;
        auto it = jobAt.find(chunk);
        if (it != jobAt.end()) b.jobs[it->second] = {chunk, word, slot, data};
        else {
            jobAt[chunk] = b.jobs.size();
            b.jobs.push_back({chunk, word, slot, data});
        }
    };
    auto freeSlot = [&](uint32_t chunk) {
        if (slotOf_[chunk] == ~0u) return;
        freeSlots_.push_back(slotOf_[chunk]);
        slotOf_[chunk] = ~0u;
        slotsUsed_--;
    };
    int built = 0;
    for (auto& [dist, k] : order) {
        auto it = pending_.find(k);
        const std::vector<uint16_t>& m = it->second;
        bool mixed = std::any_of(m.begin(), m.end(), [](uint16_t v) { return v != 0; });
        if (mixed && built >= maxSections) continue;  // the rest next time
        uint32_t c = index(k.x, k.y, k.z);
        if (mixed) {
            uint32_t slot = slotOf_[c];
            if (slot == ~0u) {
                if (!freeSlots_.empty()) {
                    slot = freeSlots_.back();
                    freeSlots_.pop_back();
                } else if (nextSlot_ < slotCap) {
                    slot = nextSlot_++;
                }
                if (slot != ~0u) {
                    slotOf_[c] = slot;
                    slotsUsed_++;
                }
            }
            if (slot == ~0u) {
                emit(c, VG_UNLOADED, ~0u, 0);  // the pool is full: the SVDAG levels answer here
            } else {
                uint32_t at = uint32_t(b.data.size() / VG_SECTION_WORDS);
                b.data.resize(b.data.size() + VG_SECTION_WORDS);
                uint32_t* w = b.data.data() + size_t(at) * VG_SECTION_WORDS;
                for (int i = 0; i < VG_SECTION_WORDS; i++) w[i] = uint32_t(m[size_t(2 * i)]) | uint32_t(m[size_t(2 * i + 1)]) << 16;
                emit(c, slot << 2 | VG_MIXED, slot, at);
                built++;
            }
        } else {
            freeSlot(c);
            emit(c, VG_EMPTY, ~0u, 0);
        }
        // the column's top: the game sends a chunk's sections up to its highest block, never the air above; below its
        // lowest only solid rock is left out (the tiles' stone: those stay UNLOADED, the SVDAG answers)
        size_t col = size_t(k.z - oz_) * D + size_t(k.x - ox_);
        int top = colTop_[col];
        static const bool topAir = [] {  // ATLAS_VOXGRID_TOPAIR=0: above a column's top section stays UNLOADED
            const char* e = std::getenv("ATLAS_VOXGRID_TOPAIR");
            return !e || std::atoi(e) != 0;
        }();
        if (!topAir) {
        } else if (top == NO_TOP) {
            for (int sy = k.y + 1; sy < VG_MIN_SY + H; sy++) {
                uint32_t a = index(k.x, sy, k.z);
                if (state_[a] == VG_UNLOADED && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z})) emit(a, VG_EMPTY, ~0u, 0);
            }
        } else if (k.y > top) {
            for (int sy = top + 1; sy < k.y; sy++) {  // air until now, data to come
                uint32_t a = index(k.x, sy, k.z);
                if (state_[a] == VG_EMPTY && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z})) emit(a, VG_UNLOADED, ~0u, 0);
            }
        }
        if (top == NO_TOP || k.y > top) colTop_[col] = int16_t(k.y);
        pending_.erase(it);
    }
    sectionsBuilt += uint64_t(built);
    batches++;
    return b;
}

// ---- the CPU reference build -----------------------------------------------------------------------------------

void VoxGrid::applyCpu(const VgBatch& b) {
    size_t n = size_t(D) * H * D;
    if (chunks.size() != n || b.clear) chunks.assign(n, VG_UNLOADED);
    cpuOx = b.ox;
    cpuOz = b.oz;
    for (const VgJob& j : b.jobs) {
        chunks[j.chunk] = j.word;
        if (j.slot == ~0u) continue;
        size_t s = j.slot;
        if (pool.size() < (s + 1) * VG_SECTION_WORDS) {
            pool.resize((s + 1) * VG_SECTION_WORDS);
            blocks.resize((s + 1) * 64);
            bits.resize((s + 1) * 64);
        }
        std::copy_n(b.data.begin() + ptrdiff_t(j.data) * VG_SECTION_WORDS, VG_SECTION_WORDS, pool.begin() + ptrdiff_t(s * VG_SECTION_WORDS));
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
    auto chunkAt = [&](int x, int y, int z) { return chunks[(size_t(y) * D + z) * D + x]; };
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

void VoxGrid::growChunksCpu() {
    // a summed-volume table of the cells that stop a box (anything not EMPTY), then each EMPTY cell's box
    int X = D, Y = H, Z = D;
    std::vector<uint32_t> S(size_t(X + 1) * (Y + 1) * (Z + 1), 0);
    auto at = [&](int x, int y, int z) -> uint32_t& { return S[(size_t(y) * (Z + 1) + size_t(z)) * (X + 1) + size_t(x)]; };
    for (int y = 0; y < Y; y++)
        for (int z = 0; z < Z; z++)
            for (int x = 0; x < X; x++) {
                uint32_t occ = (chunks[(size_t(y) * Z + z) * X + x] & 3u) != VG_EMPTY ? 1u : 0u;
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
                uint32_t& c = chunks[(size_t(y) * Z + z) * X + x];
                if ((c & 3u) != VG_EMPTY) continue;
                c = growBox(x, y, z, X, Y, Z, 31, 5, boxOcc) << 2 | VG_EMPTY;
            }
}

}  // namespace atlas
