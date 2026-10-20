#include "voxgrid.hpp"

#include <algorithm>
#include <climits>
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
}

void VoxGrid::put(int sx, int sy, int sz, const uint16_t* mats) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mu_);
    // before the window is placed the world keeps them (frame() reads them from there); outside it they wait there too
    if (!centred_ || !inWindow(sx, sy, sz)) return;
    auto a = std::make_shared<std::array<uint16_t, 4096>>();
    std::memcpy(a->data(), mats, 4096 * 2);
    pending_[{sx, sy, sz}] = std::move(a);
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
    stats_.frames++;
    // the window follows the camera a few sections behind (each move frees the columns it leaves and reads the ones
    // it enters: the cells keep their place in memory, chunk (x, y, z) at (x mod D, y, z mod D))
    int cx = ox_ + D / 2, cz = oz_ + D / 2;
    if (!centred_ || std::abs(camSx_ - cx) > FOLLOW || std::abs(camSz_ - cz) > FOLLOW) shift(camSx_ - D / 2, camSz_ - D / 2, world);
    // (placed, or a jump past the whole window: read here at once, a few ms this one frame, nothing of the window known;
    // a shift's strips are the update thread's, here only when it has not come for half a second)
    if (freshColumns_ && (freshColumns_ == uint32_t(D * D) || stats_.frames - stats_.lastShiftFrame > 30)) refillLocked(world, false);
}

void VoxGrid::refill(World& world) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mu_);
    if (freshColumns_) refillLocked(world, true);
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
        freeSlots_.clear();
        nextSlot_ = 0;
        slotsUsed_ = 0;
        pending_.clear();
        vacated_.clear();
        clearPending_ = true;
        stats_.jumps++;
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
                        slotOf_[c] = ~0u;
                        slotsUsed_--;
                    }
                    state_[c] = VG_UNLOADED;
                }
                vacated_.push_back(uint32_t(col));  // (the column's lowest cell)
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
    if (!enabled || !centred_ || (pending_.empty() && !clearPending_ && vacated_.empty())) return b;
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
        if (!b.clear) return b;
    }
    size_t n = size_t(D) * H * D;
    if (jobAt_.size() != n) jobAt_.assign(n, ~0u);
    std::vector<uint32_t> touched;  // (jobAt_ put back after the batch)
    auto emit = [&](uint32_t chunk, uint32_t word, uint32_t slot, uint32_t data) {
        uint8_t st = uint8_t(word & 3u);
        // empty and empty before: its word (with the box grown around it) stands
        if (st == VG_EMPTY && state_[chunk] == VG_EMPTY && !b.clear && jobAt_[chunk] == ~0u) return;
        if (st != state_[chunk]) b.chunkStates = true;
        state_[chunk] = st;
        if (jobAt_[chunk] != ~0u) b.jobs[jobAt_[chunk]] = {chunk, word, slot, data};
        else {
            jobAt_[chunk] = uint32_t(b.jobs.size());
            touched.push_back(chunk);
            b.jobs.push_back({chunk, word, slot, data});
        }
    };
    auto freeSlot = [&](uint32_t chunk) {
        if (slotOf_[chunk] == ~0u) return;
        freeSlots_.push_back(slotOf_[chunk]);
        slotChunk_[slotOf_[chunk]] = ~0u;
        slotOf_[chunk] = ~0u;
        slotsUsed_--;
    };
    auto dist2 = [&](int sx, int sz) {
        int64_t dx = sx - camSx_, dz = sz - camSz_;
        return dx * dx + dz * dz;
    };
    // nearest the camera first (columns, then height: a teleport's own chunks before the ring around it); only the
    // batch's share is put in order (a window's thousands of sections wait their turn unsorted)
    std::vector<std::pair<int64_t, Key>> order;
    order.reserve(pending_.size());
    for (auto& [k, v] : pending_) order.push_back({dist2(k.x, k.z) * 64 + (k.y - VG_MIN_SY), k});
    auto byDist = [](const auto& a, const auto& c) { return a.first < c.first; };
    size_t share = std::min(order.size(), size_t(std::max(maxSections, 1)) * 2);
    if (share < order.size()) {
        std::nth_element(order.begin(), order.begin() + ptrdiff_t(share), order.end(), byDist);
        order.resize(share);
    }
    std::sort(order.begin(), order.end(), byDist);
    // the pool full: the farthest chunks give their slots to nearer ones (built once, when first needed)
    std::vector<std::pair<int64_t, uint32_t>> victims;  // (distance, chunk), farthest last
    bool victimsBuilt = false;
    auto worldOf = [&](uint32_t c, int& sx, int& sy, int& sz) {
        int px = int(c % uint32_t(D)), pz = int((c / uint32_t(D)) % uint32_t(D));
        sy = int(c / uint32_t(D * D)) + VG_MIN_SY;
        sx = ox_ + pmod(px - ox_, D);
        sz = oz_ + pmod(pz - oz_, D);
    };
    int built = 0;
    for (auto& [dist, k] : order) {
        if (built >= maxSections) break;  // the rest next time
        auto it = pending_.find(k);
        const std::array<uint16_t, 4096>& m = *it->second;
        static const std::array<uint16_t, 4096> air{};
        bool mixed = std::memcmp(m.data(), air.data(), sizeof air) != 0;
        uint32_t c = phys(k.x, k.y, k.z);
        if (mixed) {
            uint32_t slot = slotOf_[c];
            if (slot == ~0u) {
                if (!freeSlots_.empty()) {
                    slot = freeSlots_.back();
                    freeSlots_.pop_back();
                } else if (nextSlot_ < slotCap) {
                    slot = nextSlot_++;
                } else {
                    if (!victimsBuilt) {
                        victimsBuilt = true;
                        for (uint32_t s = 0; s < nextSlot_; s++) {
                            uint32_t vc = slotChunk_[s];
                            if (vc == ~0u) continue;
                            int vx, vy, vz;
                            worldOf(vc, vx, vy, vz);
                            victims.push_back({dist2(vx, vz) * 64 + (vy - VG_MIN_SY), vc});
                        }
                        std::sort(victims.begin(), victims.end(), byDist);
                    }
                    // evict the farthest when it is farther than this one: back to unknown, its level 0 the CPU's
                    while (!victims.empty() && slotOf_[victims.back().second] == ~0u) victims.pop_back();
                    if (!victims.empty() && victims.back().first > dist) {
                        uint32_t vc = victims.back().second;
                        victims.pop_back();
                        slot = slotOf_[vc];
                        slotOf_[vc] = ~0u;
                        slotChunk_[slot] = ~0u;
                        slotsUsed_--;
                        emit(vc, VG_UNLOADED, ~0u, 0);
                        int vx, vy, vz;
                        worldOf(vc, vx, vy, vz);
                        released_.push_back({0, vx, vy, vz});
                        stats_.evicted++;
                    }
                }
                if (slot != ~0u) {
                    slotOf_[c] = slot;
                    slotChunk_[slot] = c;
                    slotsUsed_++;
                }
            }
            if (slot == ~0u) {
                // the pool is full of nearer chunks: unknown here, the CPU's level 0 answers (World::gridReleased)
                emit(c, VG_UNLOADED, ~0u, 0);
                released_.push_back({0, k.x, k.y, k.z});
                stats_.overflow++;
            } else {
                uint32_t at = uint32_t(b.secs.size());
                b.secs.push_back(it->second);  // (shared: the backend copies it once, into its staging)
                emit(c, slot << 2 | VG_MIXED, slot, at);
                built++;
            }
        } else {
            freeSlot(c);
            emit(c, VG_EMPTY, ~0u, 0);
        }
        // the column's top: the game sends a chunk's sections up to its highest block, never the air above; below its
        // lowest only solid rock is left out (the tiles' stone: those stay UNLOADED, the SVDAG answers)
        size_t col = physCol(k.x, k.z);
        int top = colTop_[col];
        static const bool topAir = [] {  // ATLAS_VOXGRID_TOPAIR=0: above a column's top section stays UNLOADED
            const char* e = std::getenv("ATLAS_VOXGRID_TOPAIR");
            return !e || std::atoi(e) != 0;
        }();
        if (!topAir) {
        } else if (top == NO_TOP) {
            for (int sy = k.y + 1; sy < VG_MIN_SY + H; sy++) {
                uint32_t a = phys(k.x, sy, k.z);
                if (state_[a] == VG_UNLOADED && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z})) emit(a, VG_EMPTY, ~0u, 0);
            }
        } else if (k.y > top) {
            for (int sy = top + 1; sy < k.y; sy++) {  // air until now, data to come
                uint32_t a = phys(k.x, sy, k.z);
                if (state_[a] == VG_EMPTY && slotOf_[a] == ~0u && !pending_.count({k.x, sy, k.z})) emit(a, VG_UNLOADED, ~0u, 0);
            }
        }
        if (top == NO_TOP || k.y > top) colTop_[col] = int16_t(k.y);
        pending_.erase(it);
    }
    for (uint32_t c : touched) jobAt_[c] = ~0u;
    sectionsBuilt += uint64_t(built);
    batches++;
    if (pending_.empty() && !freshColumns_ && stats_.lastShiftFrame >= stats_.settledFrame) {
        stats_.settledFrame = stats_.frames;  // (the window's content complete again: frames since its last move)
        stats_.lastSettleFrames = stats_.frames - stats_.lastShiftFrame;
    }
    return b;
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

void VoxGrid::applyCpu(const VgBatch& b) {
    size_t n = size_t(D) * H * D;
    if (chunks.size() != n || b.clear) chunks.assign(n, VG_UNLOADED);
    cpuOx = b.ox;
    cpuOz = b.oz;
    for (const VgJob& j : b.jobs) {
        if (j.slot == VG_COLUMN) {
            for (int y = 0; y < H; y++) chunks[j.chunk + size_t(y) * D * D] = j.word;
            continue;
        }
        chunks[j.chunk] = j.word;
        if (j.slot == ~0u) continue;
        size_t s = j.slot;
        if (pool.size() < (s + 1) * VG_SECTION_WORDS) {
            pool.resize((s + 1) * VG_SECTION_WORDS);
            blocks.resize((s + 1) * 64);
            bits.resize((s + 1) * 64);
        }
        std::memcpy(pool.data() + s * VG_SECTION_WORDS, b.secs[j.data]->data(), VG_SECTION_WORDS * 4);
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

void VoxGrid::growChunksCpu() {
    // a summed-volume table of the cells that stop a box (anything not EMPTY), then each EMPTY cell's box; in the
    // window's own order (logical x, z from its origin; the cells in memory at x mod D, z mod D)
    int X = D, Y = H, Z = D;
    auto cell = [&](int x, int y, int z) -> uint32_t& {
        return chunks[(size_t(y) * Z + size_t(pmod(z + cpuOz, Z))) * X + size_t(pmod(x + cpuOx, X))];
    };
    std::vector<uint32_t> S(size_t(X + 1) * (Y + 1) * (Z + 1), 0);
    auto at = [&](int x, int y, int z) -> uint32_t& { return S[(size_t(y) * (Z + 1) + size_t(z)) * (X + 1) + size_t(x)]; };
    for (int y = 0; y < Y; y++)
        for (int z = 0; z < Z; z++)
            for (int x = 0; x < X; x++) {
                uint32_t occ = (cell(x, y, z) & 3u) != VG_EMPTY ? 1u : 0u;
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
                if ((c & 3u) != VG_EMPTY) continue;
                c = growBox(x, y, z, X, Y, Z, 31, 5, boxOcc) << 2 | VG_EMPTY;
            }
}

}  // namespace atlas
