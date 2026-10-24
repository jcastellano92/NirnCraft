// The self-baking cache measured without Java (docs/ATLAS-WORLD-STORE.md section 1): what AtlasClientGen does around a
// spawn point, a batch at a time nearest first (atlas_gen_cache_lookup, atlas_gen_cache_ingest for the chunks the cache
// knows; atlas_gen_run + atlas_gen_ingest for the others), frames rendered between, until every chunk of the spawn
// radius is in the grid and the grid has built them. Loads the program and map the parity test dumped
// (AtlasGenParityTest: NIRNCRAFT_GEN_DUMP=<dir>: prog.bin, map.bin); the ingest table is a stand-in (each generator id its
// own material: the sizes are of the generator's own variety).
//   gen_cache_bench <dump dir> <cache dir|-> [x=0] [z=0] [radius=24] [seed=1]
// Prints one JSON line: the time to the full spawn radius, chunks generated and read from the cache, the cache's size.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <tuple>
#include <array>
#include <atomic>
#include <mutex>
#include <set>
#include <thread>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "../src/camera.hpp"

static std::vector<uint32_t> load(const std::string& path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return {};
    std::vector<uint32_t> w(size_t(f.tellg()) / 4);
    f.seekg(0);
    f.read(reinterpret_cast<char*>(w.data()), std::streamsize(w.size() * 4));
    return w;
}

static double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

static std::string arg(int argc, char** argv, const char* key, const char* def) {
    size_t n = std::strlen(key);
    for (int i = 3; i < argc; i++)
        if (std::strncmp(argv[i], key, n) == 0 && argv[i][n] == '=') return argv[i] + n + 1;
    return def;
}

// ---- store=1: AtlasClientGen's store mode (the chunk store and its stages), for the churn ----
// Two stages of no work at radius 2 and 1 (the decoration and its finishing: the reach the features have), a store of
// storeSlots chunks; the loop as AtlasClientGen.directBatch: the cache first, then (old=1) every chunk through the
// store as before the fix, or the misses only with their neighbourhood ensured; claims by hash (AtlasEditsClient); the
// grid's reports (dropped / wanted) as gridReports. After the window is in, steady=<s> seconds with the camera still:
// the chunks planned, made into the store, through a stage, packed, a second.
static int32_t noStage(void*, const AtlasGenStoreBatch*) { return 0; }

static int storeMode(AtlasContext* ctx, AtlasGen* g, AtlasFrame& f, AtlasFrameOutput& out, int ccx, int ccz, int R, bool old,
                     double steadyS, uint32_t slots) {
    uint32_t id = 0;
    if (atlas_gen_store_enable(g, slots) != ATLAS_OK || atlas_gen_add_store_stage(g, 1000, 2, noStage, nullptr, &id) != ATLAS_OK ||
        atlas_gen_add_store_stage(g, 1100, 1, noStage, nullptr, &id) != ATLAS_OK) {
        std::fprintf(stderr, "store: %s\n", atlas_gen_last_error(g));
        return 1;
    }
    uint32_t reach = atlas_gen_store_reach(g);
    const uint32_t INFO = 164;
    std::set<std::pair<int, int>> done;
    std::map<std::tuple<int, int, int>, uint64_t> claimed;  // (AtlasEditsClient.UPLOADED)
    auto inWin = [&](int cx, int cz, int r) { return (cx - ccx) * (cx - ccx) + (cz - ccz) * (cz - ccz) <= r * r; };
    std::vector<std::pair<int, int>> window;
    for (int dz = -R; dz <= R; dz++)
        for (int dx = -R; dx <= R; dx++)
            if (dx * dx + dz * dz <= R * R) window.push_back({ccx + dx, ccz + dz});
    // (old: nearest first, a ring; now: 8 x 8 patches nearest first, a patch a batch: what waits in the store, with the
    // neighbourhood its stages read, stays one patch's (14 x 14) whatever the window's radius)
    auto d2 = [&](int x, int z) { return (x - ccx) * (x - ccx) + (z - ccz) * (z - ccz); };
    auto pd = [&](const std::pair<int, int>& c) {
        int px = (c.first >> 3) * 8 + 4, pz = (c.second >> 3) * 8 + 4;
        return d2(px, pz);
    };
    std::sort(window.begin(), window.end(), [&](auto& a, auto& b) {
        if (!old) {
            int pa = pd(a), pb = pd(b);
            if (pa != pb) return pa < pb;
            if ((a.first >> 3) != (b.first >> 3) || (a.second >> 3) != (b.second >> 3))
                return (a.first >> 3) != (b.first >> 3) ? (a.first >> 3) < (b.first >> 3) : (a.second >> 3) < (b.second >> 3);
        }
        return d2(a.first, a.second) < d2(b.first, b.second);
    });
    uint64_t planned = 0, upChunks = 0, sentSections = 0;
    std::set<std::pair<int, int>> up;
    std::vector<uint32_t> info(64 * INFO), flags(64);
    std::vector<uint8_t> hit(64);
    std::vector<int32_t> outXZ(128);
    auto masksOf = [&](int cx, int cz, const uint32_t* row) {
        int highest = -1;
        for (int s = 0; s < 40; s++)
            if (row[4 + s * 4 + 2] == 1) highest = s;
        uint64_t m = 0;
        bool started = false;
        for (int s = 0; s <= highest; s++) {
            uint32_t kind = row[4 + s * 4 + 2];
            if (kind == 2 || (kind == 0 && !started)) continue;
            started = true;
            uint64_t h = uint64_t(row[4 + s * 4]) | uint64_t(row[4 + s * 4 + 1]) << 32;
            auto& c = claimed[{cx, s, cz}];
            if (c == h && h) continue;  // (identical: up already)
            c = h;
            m |= uint64_t(1) << s;
        }
        return m;
    };
    auto unclaimAll = [&](int cx, int cz) {
        for (int s = 0; s < 40; s++) claimed.erase({cx, s, cz});
    };
    double tStart = nowMs(), tWindow = 0, tEnd = 0;
    uint64_t made0 = 0, runs0 = 0, packs0 = 0, planned0 = 0;
    for (int iter = 0; iter < 200000; iter++) {
        // the grid's reports (gridReports): wanted -> made again; dropped inside the window: wait
        {
            std::vector<int32_t> dr(4 * 4096), wa(4 * 4096);
            uint32_t nd = 0, nw = 0;
            atlas_gen_grid_reports(g, dr.data(), 4096, &nd, wa.data(), 4096, &nw);
            for (uint32_t i = 0; i < std::min(nw, 4096u); i++)
                if (wa[i * 4] == 0) {
                    claimed.erase({wa[i * 4 + 1], wa[i * 4 + 2] + 4, wa[i * 4 + 3]});
                    done.erase({wa[i * 4 + 1], wa[i * 4 + 3]});
                }
            for (uint32_t i = 0; i < std::min(nd, 4096u); i++)
                if (dr[i * 4] == 0 && !inWin(dr[i * 4 + 1], dr[i * 4 + 3], R + 1)) {
                    claimed.erase({dr[i * 4 + 1], dr[i * 4 + 2] + 4, dr[i * 4 + 3]});
                    done.erase({dr[i * 4 + 1], dr[i * 4 + 3]});
                }
        }
        std::vector<int32_t> batch;
        uint32_t held = 0, waitingNow = 0, readyNow = 0;
        atlas_gen_store_stats(g, &held, &waitingNow, &readyNow);
        bool room = old || waitingNow < 64;  // (what waits holds its neighbourhood: (2 reach + 1)^2 chunks at most)
        int patchX = INT32_MIN, patchZ = INT32_MIN;
        for (auto& c : window) {
            if (batch.size() >= 128 || !room) break;
            if (done.count(c)) continue;
            if (!old) {
                // (one patch a batch)
                if (patchX == INT32_MIN) patchX = c.first >> 3, patchZ = c.second >> 3;
                if ((c.first >> 3) != patchX || (c.second >> 3) != patchZ) continue;
            }
            done.insert(c);
            batch.push_back(c.first), batch.push_back(c.second);
        }
        planned += batch.size() / 2;
        uint32_t n = uint32_t(batch.size() / 2);
        // the cache first
        std::vector<int32_t> miss;
        if (n) {
            atlas_gen_cache_lookup(g, batch.data(), n, info.data(), hit.data());
            std::vector<int32_t> hits;
            std::vector<uint64_t> hm;
            for (uint32_t i = 0; i < n; i++) {
                if (hit[i]) {
                    hits.push_back(batch[i * 2]), hits.push_back(batch[i * 2 + 1]);
                    hm.push_back(masksOf(batch[i * 2], batch[i * 2 + 1], info.data() + size_t(i) * INFO));
                } else {
                    miss.push_back(batch[i * 2]), miss.push_back(batch[i * 2 + 1]);
                }
            }
            if (!hits.empty()) {
                std::vector<uint64_t> asked = hm;
                uint32_t sent = 0;
                atlas_gen_cache_ingest(g, hits.data(), uint32_t(hits.size() / 2), hm.data(), &sent);
                sentSections += sent;
                for (size_t i = 0; i < hm.size(); i++) {
                    if (asked[i] & ~hm[i]) {
                        done.erase({hits[i * 2], hits[i * 2 + 1]});
                        unclaimAll(hits[i * 2], hits[i * 2 + 1]);
                    } else {
                        up.insert({hits[i * 2], hits[i * 2 + 1]});
                    }
                }
            }
        }
        // into the store: (old) everything asked; (now) the misses, their neighbourhood ensured
        std::vector<int32_t> toStore = old ? batch : miss;
        if (false && !old && !miss.empty()) {  // (the store keeps the neighbourhood itself: atlas_gen_run_store 1b)
            std::set<std::pair<int, int>> nb;
            for (size_t i = 0; i < miss.size(); i += 2)
                for (int dz = -int(reach); dz <= int(reach); dz++)
                    for (int dx = -int(reach); dx <= int(reach); dx++)
                        if (dx || dz) nb.insert({miss[i] + dx, miss[i + 1] + dz});
            for (size_t i = 0; i < miss.size(); i += 2) nb.erase({miss[i], miss[i + 1]});
            std::vector<int32_t> e;
            for (auto& c : nb) e.push_back(c.first), e.push_back(c.second);
            for (size_t i = 0; i < e.size(); i += 128)
                atlas_gen_store_ensure(g, e.data() + i, uint32_t(std::min<size_t>(128, e.size() - i) / 2));
        }
        for (size_t i0 = 0; i0 < std::max<size_t>(toStore.size(), 1); i0 += 128) {
            uint32_t k = uint32_t(std::min<size_t>(128, toStore.size() - std::min(i0, toStore.size())) / 2);
            uint32_t m = 0;
            if (atlas_gen_run_store(g, k ? toStore.data() + i0 : nullptr, k, outXZ.data(), &m, nullptr, flags.data(), info.data(), nullptr) != ATLAS_OK) {
                std::fprintf(stderr, "run_store: %s\n", atlas_gen_last_error(g));
                return 1;
            }
            if (!m) continue;
            std::vector<uint64_t> masks(m), asked;
            for (uint32_t i = 0; i < m; i++) {
                int cx = outXZ[i * 2], cz = outXZ[i * 2 + 1];
                if (flags[i] || !inWin(cx, cz, R + 1)) {
                    if (!flags[i]) done.erase({cx, cz});
                    continue;
                }
                masks[i] = masksOf(cx, cz, info.data() + size_t(i) * INFO);
            }
            asked = masks;
            uint32_t sent = 0;
            AtlasStatus s = atlas_gen_ingest(g, masks.data(), &sent, nullptr);
            sentSections += sent;
            for (uint32_t i = 0; i < m; i++) {
                int cx = outXZ[i * 2], cz = outXZ[i * 2 + 1];
                if (s != ATLAS_OK || (asked[i] & ~masks[i])) {
                    done.erase({cx, cz});
                    for (int q = 0; q < 40; q++)
                        if ((asked[i] & ~masks[i]) >> q & 1u) claimed.erase({cx, q, cz});
                } else if (inWin(cx, cz, R)) {
                    up.insert({cx, cz});
                }
            }
        }
        atlas_render(ctx, &f, &out);
        // the window in: steady state from here, the camera still
        if (!tWindow && up.size() >= window.size()) {
            tWindow = nowMs();
            atlas_gen_store_counters(g, &made0, &runs0, &packs0);
            planned0 = planned;
        }
        if (tWindow && nowMs() - tWindow > steadyS * 1000.0) {
            tEnd = nowMs();
            break;
        }
        if (!tWindow && nowMs() - tStart > 240000.0) break;
    }
    uint64_t made = 0, runs = 0, packs = 0;
    atlas_gen_store_counters(g, &made, &runs, &packs);
    double s = tEnd && tWindow ? (tEnd - tWindow) / 1000.0 : 0;
    std::printf("{\"store\":{\"old\":%d,\"reach\":%u,\"slots\":%u,\"window\":%zu,\"up\":%zu,\"windowMs\":%.0f,\"steadyS\":%.1f,"
                "\"total\":{\"planned\":%llu,\"made\":%llu,\"stageRuns\":%llu,\"packs\":%llu,\"sections\":%llu},"
                "\"perSecondSteady\":{\"planned\":%.1f,\"made\":%.1f,\"stageRuns\":%.1f,\"packs\":%.1f}}}\n",
                int(old), reach, slots, window.size(), up.size(), tWindow ? tWindow - tStart : -1.0, s,
                (unsigned long long)planned, (unsigned long long)made, (unsigned long long)runs, (unsigned long long)packs,
                (unsigned long long)sentSections, s ? (planned - planned0) / s : 0.0, s ? (made - made0) / s : 0.0,
                s ? (runs - runs0) / s : 0.0, s ? (packs - packs0) / s : 0.0);
    return 0;
}

// ---- walk=1: a player walking, as the mod does it ----
// A render thread (the game's frames: atlas_update + atlas_render back to back at w x h, the camera moving at `speed`
// blocks a second along +x) feeds the frame cadence; the main thread plans the near window and the far tiles the way
// AtlasClientGen does (nearest chunks first, 64 a batch, the cache first; the far tile nearest by distance in tiles,
// each tile from the cache or made; the tiles of a level the camera left a tile of forgotten), until `seconds` are up.
// A line every 2 s: the frames since the last (p50, p99, max ms), the chunks and tiles done, the coverage (holes per
// level, far reach), the grid's staging time, the budget.
static int walkMode(AtlasContext* ctx, AtlasGen* g, double x0, double z0, double y, int R, double speed, double seconds, int w, int h) {
    std::atomic<bool> stop{false};
    std::atomic<double> camX{x0}, camZ{z0};
    std::mutex fm;
    std::vector<std::pair<double, float>> frameLog;  // (time s, ms)
    double tStart = nowMs();
    std::thread render([&] {
        AtlasFrame f{};
        f.cameraPos[1] = y;
        atlas::cameraMatrix(90, -30, 70, double(w) / h, f.invViewProj);  // (east, down a little: the way it walks)
        f.width = uint32_t(w);
        f.height = uint32_t(h);
        f.maxDistance = 40000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        std::vector<uint32_t> img(size_t(w) * h);
        AtlasFrameOutput o{img.data(), nullptr, nullptr};
        double last = nowMs();
        while (!stop) {
            double now = nowMs();
            double dt = std::min(0.25, (now - last) / 1000.0);
            last = now;
            camX = camX + speed * dt;
            f.cameraPos[0] = camX;
            f.cameraPos[2] = camZ;
            double c[3] = {f.cameraPos[0], y, f.cameraPos[2]};
            atlas_update(ctx, c, 0);
            double t0 = nowMs();
            atlas_render(ctx, &f, &o);
            double ms = nowMs() - t0;
            std::lock_guard<std::mutex> l(fm);
            frameLog.push_back({(nowMs() - tStart) / 1000.0, float(ms)});
        }
    });
    std::set<std::pair<int, int>> done;
    std::map<std::tuple<int, int, int>, std::array<int, 3>> farWin;  // tile -> the window it was made under
    const uint32_t INFO = 164;
    std::vector<uint32_t> info(64 * INFO), flags(64), kinds(256 * 22 * 2);
    std::vector<uint8_t> hit(64);
    auto masksOf = [&](const uint32_t* row) {
        int highest = -1;
        for (int s = 0; s < 40; s++)
            if (row[4 + s * 4 + 2] == 1) highest = s;
        uint64_t m = 0;
        bool started = false;
        for (int s = 0; s <= highest; s++) {
            uint32_t kind = row[4 + s * 4 + 2];
            if (kind == 2 || (kind == 0 && !started)) continue;
            started = true;
            m |= uint64_t(1) << s;
        }
        return m;
    };
    uint64_t nearGen = 0, nearCache = 0, farMade = 0, farCached = 0, farTaken = 0, farChunks = 0;
    double lastReport = 0;
    size_t reportedFrames = 0;
    size_t todo = 0;
    while ((nowMs() - tStart) / 1000.0 < seconds) {
        double cx = camX, cz = camZ;
        int ccx = int(std::floor(cx / 16)), ccz = int(std::floor(cz / 16));
        bool didWork = false;
        // the near window: forget what is far, plan the nearest not done
        for (auto it = done.begin(); it != done.end();)
            it = std::abs(it->first - ccx) > R + 2 || std::abs(it->second - ccz) > R + 2 ? done.erase(it) : std::next(it);
        std::vector<std::pair<int, std::pair<int, int>>> cand;
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                if (dx * dx + dz * dz > R * R || done.count({ccx + dx, ccz + dz})) continue;
                int d2 = dx * dx + dz * dz + (dx < 0 ? 2 * dx * dx : 0);  // (ahead before behind)
                cand.push_back({d2, {ccx + dx, ccz + dz}});
            }
        std::sort(cand.begin(), cand.end());
        todo = cand.size();
        if (!cand.empty()) {
            std::vector<int32_t> batch;
            for (size_t i = 0; i < cand.size() && batch.size() < 128; i++) {
                batch.push_back(cand[i].second.first), batch.push_back(cand[i].second.second);
                done.insert(cand[i].second);
            }
            uint32_t n = uint32_t(batch.size() / 2);
            atlas_gen_cache_lookup(g, batch.data(), n, info.data(), hit.data());
            std::vector<int32_t> hits, miss;
            std::vector<uint64_t> hm;
            for (uint32_t i = 0; i < n; i++) {
                if (hit[i]) {
                    hits.push_back(batch[i * 2]), hits.push_back(batch[i * 2 + 1]);
                    hm.push_back(masksOf(info.data() + size_t(i) * INFO));
                } else {
                    miss.push_back(batch[i * 2]), miss.push_back(batch[i * 2 + 1]);
                }
            }
            if (!hits.empty()) {
                std::vector<uint64_t> asked = hm;
                uint32_t sent = 0;
                atlas_gen_cache_ingest(g, hits.data(), uint32_t(hits.size() / 2), hm.data(), &sent);
                for (size_t i = 0; i < hm.size(); i++) {
                    if (asked[i] & ~hm[i]) done.erase({hits[i * 2], hits[i * 2 + 1]});
                    else nearCache++;
                }
            }
            if (!miss.empty()) {
                uint32_t m = uint32_t(miss.size() / 2);
                AtlasGenTimes t{};
                if (atlas_gen_run(g, miss.data(), m, nullptr, flags.data(), info.data(), &t) != ATLAS_OK) {
                    std::fprintf(stderr, "run: %s\n", atlas_gen_last_error(g));
                    break;
                }
                std::vector<uint64_t> masks(m), asked;
                for (uint32_t i = 0; i < m; i++) masks[i] = flags[i] ? 0 : masksOf(info.data() + size_t(i) * INFO);
                asked = masks;
                uint32_t sent = 0;
                AtlasStatus s = atlas_gen_ingest(g, masks.data(), &sent, nullptr);
                for (uint32_t i = 0; i < m; i++) {
                    if (s != ATLAS_OK || (asked[i] & ~masks[i])) done.erase({miss[i * 2], miss[i * 2 + 1]});
                    else nearGen++;
                }
            }
            didWork = true;
        } else {
            // the far tiles that cover each level's window, the nearest (in tiles of its level) first; a tile made when
            // part of it lay outside the window is asked for again once the window slid over 16 more of its columns
            // (and when the level was placed afresh)
            double best = 1e30;
            int bk = 0, btx = 0, btz = 0;
            int bwin[6] = {};
            for (int k = 1; k <= 6; k++) {
                int win[6];
                atlas_gen_coarse_window(g, k, win);
                if (!win[3]) continue;
                int span = 256 << k;
                int ox = win[0], oz = win[1], D = win[2];
                auto cover = [&](int tx, int tz, int wox, int woz) {
                    int ovx = std::max(0, std::min(16 * (tx + 1), wox + D) - std::max(16 * tx, wox));
                    int ovz = std::max(0, std::min(16 * (tz + 1), woz + D) - std::max(16 * tz, woz));
                    return ovx * ovz;
                };
                auto fdv = [](int a) { return a >= 0 ? a / 16 : -((-a + 15) / 16); };
                for (int tz = fdv(oz); tz <= fdv(oz + D - 1); tz++)
                    for (int tx = fdv(ox); tx <= fdv(ox + D - 1); tx++) {
                        auto it = farWin.find({k, tx, tz});
                        int now = cover(tx, tz, ox, oz);
                        if (now == 0) continue;
                        if (it != farWin.end()) {
                            const auto& sw = it->second;  // (window origin x, z and jumps at its making)
                            if (sw[2] == win[4] && now - cover(tx, tz, sw[0], sw[1]) < 16) continue;
                        }
                        double ex = (tx + 0.5) * span - cx, ez = (tz + 0.5) * span - cz;
                        double d = std::sqrt(ex * ex + ez * ez) / span + k * 0.01;
                        if (d < best) {
                            best = d, bk = k, btx = tx, btz = tz;
                            for (int q = 0; q < 6; q++) bwin[q] = win[q];
                        }
                    }
            }
            for (auto it = farWin.begin(); it != farWin.end();) {
                // (tiles no window covers any more are forgotten)
                int win[6];
                atlas_gen_coarse_window(g, std::get<0>(it->first), win);
                int tx = std::get<1>(it->first), tz = std::get<2>(it->first);
                bool out = tx * 16 + 15 < win[0] - 16 || tx * 16 > win[0] + win[2] + 16 || tz * 16 + 15 < win[1] - 16 || tz * 16 > win[1] + win[2] + 16;
                it = out ? farWin.erase(it) : std::next(it);
            }
            if (bk) {
                int32_t t[2] = {btx, btz};
                uint8_t served = 0;
                uint32_t ch = 0, tk = 0;
                atlas_gen_far_cache_ingest(g, bk, t, 1, &served, &ch, &tk);
                if (served) {
                    farCached++;
                } else {
                    uint32_t mixed = 0, taken = 0, ff = 0;
                    uint32_t rows = (44u + (1u << bk) - 1u) >> bk;
                    AtlasStatus s = atlas_gen_far_ingest(g, bk, t, 1, 2000, kinds.data(), &mixed, &taken, &ff, nullptr);
                    if (s == ATLAS_OK) {
                        farMade++;
                        farChunks += 256 * rows, farTaken += taken;
                    } else {
                        std::this_thread::sleep_for(std::chrono::milliseconds(10));
                        continue;  // (busy: later)
                    }
                }
                farWin[{bk, btx, btz}] = {bwin[0], bwin[1], bwin[4]};
                didWork = true;
            }
        }
        if (!didWork) std::this_thread::sleep_for(std::chrono::milliseconds(20));
        double now = (nowMs() - tStart) / 1000.0;
        if (now - lastReport >= 2.0) {
            lastReport = now;
            std::vector<float> ms;
            {
                std::lock_guard<std::mutex> l(fm);
                for (size_t i = reportedFrames; i < frameLog.size(); i++) ms.push_back(frameLog[i].second);
                reportedFrames = frameLog.size();
            }
            std::sort(ms.begin(), ms.end());
            std::string inf = atlas_info(ctx);
            size_t cp = inf.find("\"coverage\":");
            std::string cov = cp == std::string::npos ? "" : inf.substr(cp, inf.find("\"columns\"", cp) - cp) + "\"_\":0},";
            double st = 0, stMax = 0, tkMax = 0;
            if (size_t p = inf.find("\"stageCpuMs\":"); p != std::string::npos) st = std::atof(inf.c_str() + p + 13);
            if (size_t p = inf.find("\"stageCpuMaxMs\":"); p != std::string::npos) stMax = std::atof(inf.c_str() + p + 16);
            if (size_t p = inf.find("\"stageCpuRecentMs\":"); p != std::string::npos) tkMax = std::atof(inf.c_str() + p + 19);
            AtlasGenPaceStats ps{};
            atlas_gen_pace_stats(g, &ps);
            std::printf("{\"t\":%.0f,\"x\":%.0f,\"frames\":{\"n\":%zu,\"p50\":%.1f,\"p99\":%.1f,\"max\":%.1f},\"near\":{\"generated\":%llu,"
                        "\"cached\":%llu,\"todo\":%zu},\"far\":{\"made\":%llu,\"cached\":%llu,\"tilesDone\":%zu},%s\"stageCpuMs\":%.2f,"
                        "\"stageCpuMaxMs\":%.1f,\"stageRecentMs\":%.1f,\"pace\":{\"period\":%.1f,\"duty\":%.2f,\"used\":%.0f,\"waited\":%.0f,\"longest\":%.0f,\"in\":\"%s\"}}\n",
                        now, camX.load(), ms.size(), ms.empty() ? 0 : ms[ms.size() / 2], ms.empty() ? 0 : ms[ms.size() * 99 / 100],
                        ms.empty() ? 0 : ms.back(), (unsigned long long)nearGen, (unsigned long long)nearCache, todo,
                        (unsigned long long)farMade, (unsigned long long)farCached, farWin.size(), cov.c_str(), st, stMax, tkMax, ps.periodMs,
                        ps.duty, ps.usedMs, ps.waitedMs, ps.maxSubmissionMs, ps.maxPhase);
            std::fflush(stdout);
        }
    }
    stop = true;
    render.join();
    (void)farTaken;
    (void)farChunks;
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "gen_cache_bench <dump dir> <cache dir|-> [x=] [z=] [radius=] [seed=]\n");
        return 2;
    }
    std::string dump = argv[1], cache = argv[2];
    double x = std::atof(arg(argc, argv, "x", "0").c_str()), z = std::atof(arg(argc, argv, "z", "0").c_str());
    int R = std::atoi(arg(argc, argv, "radius", "24").c_str());
    uint64_t seed = std::strtoull(arg(argc, argv, "seed", "1").c_str(), nullptr, 10);
    std::vector<uint32_t> prog = load(dump + "/prog.bin"), map = load(dump + "/map.bin");
    if (prog.empty() || map.empty()) {
        std::fprintf(stderr, "no prog.bin / map.bin in %s\n", dump.c_str());
        return 2;
    }
    // pipes=<file>: the pipelines kept between runs (atlas_set_pipeline_cache: the loading phase's warm start)
    if (!arg(argc, argv, "pipes", "").empty()) atlas_set_pipeline_cache(arg(argc, argv, "pipes", "").c_str());
    double t0 = nowMs();
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) {
        std::fprintf(stderr, "no Vulkan context\n");
        return 1;
    }
    std::vector<AtlasMaterial> mats(256, AtlasMaterial{{0.5f, 0.5f, 0.5f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
    atlas_set_materials(ctx, 1, mats.data(), uint32_t(mats.size()));
    // order=game: the cache directory set after the generator and its fingerprint, as the mod does (startFor)
    bool lateCache = arg(argc, argv, "order", "") == "game";
    if (cache != "-" && !lateCache) atlas_set_cache(ctx, cache.c_str(), seed, 1, 0, 0);
    AtlasGen* g = nullptr;
    if (atlas_gen_create_for(ctx, &g) != ATLAS_OK) {
        std::fprintf(stderr, "no generator on the context's device\n");
        return 1;
    }
    // the ingest table: ids x biomes, each generator id its own material (1..255), id 0 air
    const uint32_t IDS = 4096, BIOMES = 128;
    std::vector<uint32_t> ing{IDS, BIOMES};
    for (uint32_t i = 0; i < IDS; i++) ing.push_back(i), ing.push_back(i == 0 ? 1u : 2u);
    for (uint32_t b = 0; b < BIOMES; b++) ing.push_back(0x7FB24Du), ing.push_back(0x59AE30u);
    for (uint32_t b = 0; b < BIOMES; b++)
        for (uint32_t i = 0; i < IDS; i++) ing.push_back(i == 0 ? 0u : 1u + i % 255u);
    if (atlas_gen_set_program(g, prog.data(), uint32_t(prog.size())) != ATLAS_OK ||
        atlas_gen_set_map(g, map.data(), uint32_t(map.size())) != ATLAS_OK ||
        atlas_gen_set_ingest(g, ing.data(), uint32_t(ing.size())) != ATLAS_OK) {
        std::fprintf(stderr, "generator refused: %s\n", atlas_gen_last_error(g));
        return 1;
    }
    // fp=<n>: the caller's part of the fingerprint (atlas_gen_set_fingerprint: another generator, another cache)
    if (!arg(argc, argv, "fp", "").empty()) atlas_gen_set_fingerprint(g, std::strtoull(arg(argc, argv, "fp", "0").c_str(), nullptr, 10));
    if (cache != "-" && lateCache) atlas_set_cache(ctx, cache.c_str(), seed, 1, 0, 0);
    double tReady = nowMs();
    // the frame: above the spawn, looking down the slope (the grid places its window on the first)
    AtlasFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = std::atof(arg(argc, argv, "y", "360").c_str());  // (above the build height by default: the ground below)
    f.cameraPos[2] = z;
    atlas::cameraMatrix(0, -35, 70, 16.0 / 9.0, f.invViewProj);
    f.width = 320;
    f.height = 180;
    f.maxDistance = 4000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<uint32_t> color(320 * 180);
    AtlasFrameOutput out{color.data(), nullptr, nullptr};
    double centre[3] = {x, f.cameraPos[1], z};
    // bg=1: a thread renders frames from here on (the game's cadence: back to back), their wall times kept; the generation
    // below only waits on them. bgcap=<ms> paces the thread to that period (a 60 Hz game: 16.7).
    std::atomic<bool> bgOn{false}, bgStop{false};
    std::vector<float> bgMs;
    std::thread bgThread;
    double bgCap = std::atof(arg(argc, argv, "bgcap", "0").c_str());
    auto renderMain = [&]() {
        if (bgOn) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        else atlas_render(ctx, &f, &out);
    };
    atlas_update(ctx, centre, 0);
    renderMain();
    // the spawn radius's chunks, nearest first
    int ccx = int(std::floor(x / 16)), ccz = int(std::floor(z / 16));
    if (arg(argc, argv, "bg", "0") != "0") {
        for (int i = 0; i < 3; i++) atlas_render(ctx, &f, &out);  // (the grid placed)
        bgOn = true;
        bgThread = std::thread([&] {
            std::vector<uint32_t> img(320 * 180);
            AtlasFrameOutput o2{img.data(), nullptr, nullptr};
            while (!bgStop) {
                auto t0 = std::chrono::steady_clock::now();
                atlas_render(ctx, &f, &o2);
                double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                bgMs.push_back(float(ms));
                if (bgCap > ms) std::this_thread::sleep_for(std::chrono::microseconds(int64_t((bgCap - ms) * 1000.0)));
            }
        });
    }
    if (arg(argc, argv, "walk", "0") != "0") {
        // (the main flow's own frames stop: the walk's render thread owns the context from here)
        int r = walkMode(ctx, g, x, z, f.cameraPos[1], R, std::atof(arg(argc, argv, "speed", "20").c_str()),
                         std::atof(arg(argc, argv, "seconds", "60").c_str()), std::atoi(arg(argc, argv, "w", "640").c_str()),
                         std::atoi(arg(argc, argv, "h", "360").c_str()));
        atlas_gen_destroy(g);
        atlas_destroy(ctx);
        return r;
    }
    if (arg(argc, argv, "store", "0") != "0") {
        int r = storeMode(ctx, g, f, out, ccx, ccz, R, arg(argc, argv, "old", "0") != "0",
                          std::atof(arg(argc, argv, "steady", "20").c_str()), uint32_t(std::atoi(arg(argc, argv, "slots", "768").c_str())));
        atlas_gen_destroy(g);
        atlas_destroy(ctx);
        return r;
    }
    std::vector<std::pair<int, int>> todo;
    for (int dz = -R; dz <= R; dz++)
        for (int dx = -R; dx <= R; dx++)
            if (dx * dx + dz * dz <= R * R) todo.push_back({ccx + dx, ccz + dz});
    std::sort(todo.begin(), todo.end(), [&](auto& a, auto& b) {
        return (a.first - ccx) * (a.first - ccx) + (a.second - ccz) * (a.second - ccz) <
               (b.first - ccx) * (b.first - ccx) + (b.second - ccz) * (b.second - ccz);
    });
    const uint32_t INFO = 164;
    size_t generated = 0, cached = 0, sections = 0, frames = 1, flagged = 0;
    double genMs = 0, cacheMs = 0, renderMs = 0;
    std::vector<uint32_t> info(64 * INFO), flags(64);
    std::vector<uint8_t> hit(64);
    // masks as AtlasClientGen.directBatch makes them: from the lowest kept section to the highest with blocks
    auto masksOf = [&](const uint32_t* row) {
        int highest = -1;
        for (int s = 0; s < 40; s++)
            if (row[4 + s * 4 + 2] == 1) highest = s;
        uint64_t m = 0;
        bool started = false;
        for (int s = 0; s <= highest; s++) {
            uint32_t kind = row[4 + s * 4 + 2];
            if (kind == 2 || (kind == 0 && !started)) continue;
            started = true;
            m |= uint64_t(1) << s;
        }
        return m;
    };
    size_t at = 0;
    std::vector<std::pair<int, int>> retry;
    while (at < todo.size() || !retry.empty()) {
        std::vector<int32_t> batch;
        while (!retry.empty() && batch.size() < 128) batch.push_back(retry.back().first), batch.push_back(retry.back().second), retry.pop_back();
        while (at < todo.size() && batch.size() < 128) batch.push_back(todo[at].first), batch.push_back(todo[at].second), at++;
        uint32_t n = uint32_t(batch.size() / 2);
        // the cache first
        double c0 = nowMs();
        std::vector<int32_t> miss, hits;
        std::vector<uint64_t> hitMasks;
        atlas_gen_cache_lookup(g, batch.data(), n, info.data(), hit.data());
        for (uint32_t i = 0; i < n; i++) {
            if (hit[i]) {
                hits.push_back(batch[i * 2]), hits.push_back(batch[i * 2 + 1]);
                hitMasks.push_back(masksOf(info.data() + size_t(i) * INFO));
            } else {
                miss.push_back(batch[i * 2]), miss.push_back(batch[i * 2 + 1]);
            }
        }
        if (!hits.empty()) {
            std::vector<uint64_t> asked = hitMasks;
            uint32_t sent = 0;
            atlas_gen_cache_ingest(g, hits.data(), uint32_t(hits.size() / 2), hitMasks.data(), &sent);
            sections += sent;
            for (size_t i = 0; i < hitMasks.size(); i++)
                if (asked[i] & ~hitMasks[i]) retry.push_back({hits[i * 2], hits[i * 2 + 1]});
                else cached++;
        }
        cacheMs += nowMs() - c0;
        if (!miss.empty()) {
            double g0 = nowMs();
            uint32_t m = uint32_t(miss.size() / 2);
            AtlasGenTimes t{};
            if (atlas_gen_run(g, miss.data(), m, nullptr, flags.data(), info.data(), &t) != ATLAS_OK) {
                std::fprintf(stderr, "run: %s\n", atlas_gen_last_error(g));
                return 1;
            }
            std::vector<uint64_t> masks(m), asked;
            for (uint32_t i = 0; i < m; i++) masks[i] = flags[i] ? 0 : masksOf(info.data() + size_t(i) * INFO);
            for (uint32_t i = 0; i < m; i++) flagged += flags[i] != 0;
            asked = masks;
            uint32_t sent = 0;
            AtlasStatus s = atlas_gen_ingest(g, masks.data(), &sent, nullptr);
            sections += sent;
            for (uint32_t i = 0; i < m; i++)
                if (s != ATLAS_OK || (asked[i] & ~masks[i])) retry.push_back({miss[i * 2], miss[i * 2 + 1]});
                else generated++;
            genMs += nowMs() - g0;
        }
        double r0 = nowMs();
        renderMain();
        renderMs += nowMs() - r0;
        frames++;
        if (frames > 20000) break;
    }
    // until the grid has built what it took
    double tSent = nowMs();
    for (int i = 0; i < 2000; i++) {
        std::string inf = atlas_info(ctx);
        size_t p = inf.find("\"pending\":"), q = inf.find("\"baking\":");
        if (p != std::string::npos && std::atoi(inf.c_str() + p + 10) == 0 && i > 2 &&
            (q == std::string::npos || std::atoi(inf.c_str() + q + 9) == 0))
            break;
        renderMain();
        frames++;
    }
    double tFull = nowMs();
    // the horizon (AtlasClientGen.farStep's tiles: each coarse level's 5 x 5 tiles around the camera): from the cache
    // when known (atlas_gen_far_cache_ingest), else made on the device (atlas_gen_far_ingest, baked)
    size_t farTiles = 0, farCached = 0, farMade = 0, farBusy = 0, farChunks = 0, farTaken = 0;
    size_t lvChunks[7] = {}, lvTaken[7] = {}, lvFlag[7] = {};
    double tFar0 = nowMs();
    for (int k = 1; k <= 6; k++) {
        int span = 256 << k;
        int ctx0 = int(std::floor(x / span)), ctz0 = int(std::floor(z / span));
        uint32_t rows = (44u + (1u << k) - 1u) >> k;
        std::vector<uint32_t> kinds(256 * size_t(rows) * 2), ff(1);
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int32_t t[2] = {ctx0 + dx, ctz0 + dz};
                farTiles++;
                uint8_t served = 0;
                uint32_t ch = 0, tk = 0;
                atlas_gen_far_cache_ingest(g, k, t, 1, &served, &ch, &tk);
                if (served) {
                    farCached++;
                    farChunks += ch, farTaken += tk;
                    continue;
                }
                for (int tries = 0; tries < 200; tries++) {
                    uint32_t mixed = 0, taken = 0;
                    AtlasStatus s = atlas_gen_far_ingest(g, k, t, 1, 2000, kinds.data(), &mixed, &taken, ff.data(), nullptr);
                    if (s == ATLAS_OK) {
                        farMade++;
                        farChunks += 256 * rows, farTaken += taken;
                        lvChunks[k] += 256 * rows, lvTaken[k] += taken, lvFlag[k] += ff[0] != 0;
                        break;
                    }
                    farBusy++;
                    renderMain();  // (every far buffer still read: a frame takes them)
                    frames++;
                }
            }
        renderMain();
        frames++;
    }
    for (int i = 0; i < 2000; i++) {
        std::string inf = atlas_info(ctx);
        size_t p = inf.find("\"coarseWaiting\":"), q = inf.find("\"baking\":"), g = inf.find("\"coarseGiven\":");
        if (p != std::string::npos && std::atoi(inf.c_str() + p + 16) == 0 && i > 2 &&
            (q == std::string::npos || std::atoi(inf.c_str() + q + 9) == 0) &&
            (g == std::string::npos || std::atoi(inf.c_str() + g + 14) == 0))
            break;
        renderMain();
        frames++;
    }
    double tFar = nowMs();
    // (the last batches' packs come back with the next frames: the cache gets them before the stats)
    for (int i = 0; i < 6; i++) renderMain(), frames++;
    // the view's coverage (atlas_info voxgrid.coverage) and what reading it cost
    std::string covJson;
    for (int i = 0; i < std::atoi(arg(argc, argv, "settle", "0").c_str()); i++) renderMain();  // (settle=<frames> more before reading)
    std::this_thread::sleep_for(std::chrono::milliseconds(1700));  // (the coverage in atlas_info is cached 1.5 s: a fresh one)
    {
        auto c0 = std::chrono::steady_clock::now();
        std::string inf = atlas_info(ctx);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count();
        size_t p = inf.find("\"coverage\":");
        if (p != std::string::npos) covJson = inf.substr(p, inf.find("]}", inf.find("\"slotCap\"", p)) + 2 - p) + ",\"infoMs\":" + std::to_string(int(ms)) + ",";
    }
    std::string lvJson = "\"levels\":[";
    for (int k = 1; k <= 6; k++)
        lvJson += (k > 1 ? "," : "") + std::string("{\"chunks\":") + std::to_string(lvChunks[k]) + ",\"taken\":" + std::to_string(lvTaken[k]) +
                  ",\"flaggedTiles\":" + std::to_string(lvFlag[k]) + "}";
    covJson += lvJson + "],";
    std::string bgJson;
    if (bgOn) {
        bgStop = true;
        bgThread.join();
        bgOn = false;
        std::vector<float> v = bgMs;
        std::sort(v.begin(), v.end());
        AtlasGenPaceStats ps{};
        atlas_gen_pace_stats(g, &ps);
        double sum = 0;
        for (float q : v) sum += q;
        char b[512];
        if (!v.empty())
            std::snprintf(b, sizeof b,
                          "\"frames\":{\"n\":%zu,\"p50\":%.1f,\"p90\":%.1f,\"p99\":%.1f,\"max\":%.1f,\"meanFps\":%.1f,"
                          "\"over25ms\":%zu,\"pace\":{\"periodMs\":%.1f,\"duty\":%.2f,\"usedMs\":%.0f,\"waitedMs\":%.0f,"
                          "\"farWaitedMs\":%.0f,\"maxSubMs\":%.0f,\"subs\":%llu}},",
                          v.size(), v[v.size() / 2], v[v.size() * 9 / 10], v[v.size() * 99 / 100], v.back(), 1000.0 * v.size() / sum,
                          size_t(std::count_if(v.begin(), v.end(), [](float q) { return q > 25.0f; })), ps.periodMs, ps.duty, ps.usedMs,
                          ps.waitedMs, ps.farWaitedMs, ps.maxSubmissionMs, (unsigned long long)ps.submissions);
        bgJson = b;
    }
    AtlasGenCacheStats cs{};
    atlas_gen_cache_stats(g, &cs);
    double km2 = double(todo.size()) * 256.0 / 1e6;
    std::printf("{%s%s\"horizon\":{\"tiles\":%zu,\"fromCache\":%zu,\"made\":%zu,\"busy\":%zu,\"ms\":%.0f,\"chunks\":%zu,\"taken\":%zu,\"farWrites\":%llu,"
                "\"farHits\":%llu},", covJson.c_str(), bgJson.c_str(), farTiles, farCached, farMade, farBusy, tFar - tFar0, farChunks, farTaken, (unsigned long long)cs.farWrites,
                (unsigned long long)cs.farHits);
    std::printf("\"radius\":%d,\"chunks\":%zu,\"generated\":%zu,\"fromCache\":%zu,\"flagged\":%zu,\"sections\":%zu,\"frames\":%zu,"
                "\"setupMs\":%.0f,\"toFullRadiusMs\":%.0f,\"sentMs\":%.0f,\"genMs\":%.0f,\"cacheMs\":%.0f,\"renderMs\":%.0f,"
                "\"cache\":{\"on\":%u,\"fingerprint\":\"%016llx\",\"chunks\":%llu,\"sections\":%llu,\"mb\":%.2f,\"mbPerKm2\":%.2f,"
                "\"hits\":%llu,\"misses\":%llu,\"writes\":%llu,\"reads\":%llu,\"encodeMs\":%.0f,\"decodeMs\":%.0f,\"hashErrors\":%llu}}\n",
                R, todo.size(), generated, cached, flagged, sections, frames, tReady - t0, tFull - tReady, tSent - tReady, genMs,
                cacheMs, renderMs, cs.on, (unsigned long long)cs.fingerprint, (unsigned long long)cs.chunks,
                (unsigned long long)cs.sections, double(cs.fileBytes) / 1048576.0, double(cs.fileBytes) / 1048576.0 / km2,
                (unsigned long long)cs.chunkHits, (unsigned long long)cs.chunkMisses, (unsigned long long)cs.sectionWrites,
                (unsigned long long)cs.sectionReads, cs.encodeMs, cs.decodeMs, (unsigned long long)cs.hashErrors);
    atlas_gen_destroy(g);
    atlas_destroy(ctx);
    return 0;
}
