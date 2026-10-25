// The whole-map coarse bake driver (gen_bake.hpp). Only public entry points: no part of the generator's engine is touched.
#include "gen_bake.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace atlas {
namespace {

enum : uint8_t { TODO = 0, KNOWN = 1, MADE = 2, FAILED = 3 };

struct BakeState {
    std::mutex mu;
    AtlasGenBakeSpec spec{};
    std::vector<BakeTile> plan;
    std::vector<uint8_t> st;
    size_t cursor = 0;
    double makeMs = 0;
    uint32_t busy = 0;
    int32_t last[4] = {0, 0, 0, 0};
};

std::mutex gMu;
std::unordered_map<AtlasGen*, std::shared_ptr<BakeState>> gStates;

std::shared_ptr<BakeState> stateOf(AtlasGen* h) {
    std::lock_guard<std::mutex> l(gMu);
    auto it = gStates.find(h);
    return it == gStates.end() ? nullptr : it->second;
}

int32_t floorDiv(int32_t a, int32_t b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

uint32_t rowsOf(int lod) { return (44u + (1u << uint32_t(lod)) - 1u) >> uint32_t(lod); }

// the records the cache's thread has yet to write before the bake makes more (memory: each holds a chunk's words)
constexpr uint64_t MAX_QUEUED = 8192;

void fill(BakeState& s, AtlasGen* h, AtlasGenBakeInfo* out) {
    if (!out) return;
    std::memset(out, 0, sizeof *out);
    uint32_t durable = 0;
    for (size_t i = 0; i < s.plan.size(); i++) {
        int lod = s.plan[i].lod;
        out->tiles[lod]++;
        out->total++;
        if (s.st[i] == KNOWN) out->known[lod]++, out->knownTotal++;
        else if (s.st[i] == FAILED) out->failed[lod]++, out->failedTotal++;
        else if (s.st[i] == MADE) {
            out->made[lod]++, out->madeTotal++;
            if (atlas_gen_far_known(h, lod, s.plan[i].tx, s.plan[i].tz) == 1) durable++;
        }
    }
    out->durable = durable;
    AtlasGenCacheStats cs{};
    if (atlas_gen_cache_peek(h, &cs) == ATLAS_OK) out->queued = uint32_t(cs.queued);
    out->busy = s.busy;
    out->makeMs = s.makeMs;
    std::memcpy(out->last, s.last, sizeof s.last);
}

}  // namespace

std::vector<BakeTile> planBake(const AtlasGenBakeSpec& spec) {
    std::vector<BakeTile> plan;
    int step = spec.lodFirst <= spec.lodLast ? 1 : -1;
    for (int lod = spec.lodFirst;; lod += step) {
        if (lod >= 1 && lod <= 6) {
            int32_t span = 256 << lod;
            int32_t tx0 = floorDiv(spec.minX - spec.marginBlocks, span), tx1 = floorDiv(spec.maxX - 1 + spec.marginBlocks, span);
            int32_t tz0 = floorDiv(spec.minZ - spec.marginBlocks, span), tz1 = floorDiv(spec.maxZ - 1 + spec.marginBlocks, span);
            if (lod >= 5) {
                // (the two coarsest levels' windows reach 16 and 32 km from the spawn, past the map's edge on a side: the sea
                // there is part of the floor, a few tiles whose tiles cost next to nothing; finer levels stay in the bounds)
                int32_t half = 512 << lod;
                tx0 = std::min(tx0, floorDiv(spec.centerX - half, span)), tx1 = std::max(tx1, floorDiv(spec.centerX + half - 1, span));
                tz0 = std::min(tz0, floorDiv(spec.centerZ - half, span)), tz1 = std::max(tz1, floorDiv(spec.centerZ + half - 1, span));
            }
            std::vector<std::pair<double, BakeTile>> level;
            for (int32_t tz = tz0; tz <= tz1; tz++)
                for (int32_t tx = tx0; tx <= tx1; tx++) {
                    double dx = (tx + 0.5) * span - spec.centerX, dz = (tz + 0.5) * span - spec.centerZ;
                    level.push_back({std::sqrt(dx * dx + dz * dz) / span, BakeTile{lod, tx, tz}});
                }
            std::stable_sort(level.begin(), level.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
            for (auto& e : level) plan.push_back(e.second);
        }
        if (lod == spec.lodLast) break;
    }
    return plan;
}

void bakeForget(AtlasGen* h) {
    std::lock_guard<std::mutex> l(gMu);
    gStates.erase(h);
}

}  // namespace atlas

extern "C" {

ATLAS_API AtlasStatus atlas_gen_bake_begin(AtlasGen* h, const AtlasGenBakeSpec* spec, AtlasGenBakeInfo* out) {
    if (!h || !spec || spec->maxX <= spec->minX || spec->maxZ <= spec->minZ) return ATLAS_ERR_ARG;
    auto s = std::make_shared<atlas::BakeState>();
    s->spec = *spec;
    s->plan = atlas::planBake(*spec);
    if (s->plan.empty()) return ATLAS_ERR_ARG;
    s->st.assign(s->plan.size(), atlas::TODO);
    // what the cache holds already (a later launch: all of it)
    int32_t first = atlas_gen_far_known(h, s->plan[0].lod, s->plan[0].tx, s->plan[0].tz);
    if (first < 0) return ATLAS_ERR_STATE;  // (no cache: nothing to bake into)
    for (size_t i = 0; i < s->plan.size(); i++)
        if (atlas_gen_far_known(h, s->plan[i].lod, s->plan[i].tx, s->plan[i].tz) == 1) s->st[i] = atlas::KNOWN;
    if (!s->spec.capacity) s->spec.capacity = 2000;
    {
        std::lock_guard<std::mutex> l(atlas::gMu);
        atlas::gStates[h] = s;
    }
    std::lock_guard<std::mutex> l(s->mu);
    atlas::fill(*s, h, out);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_gen_bake_step(AtlasGen* h, uint32_t maxTiles, AtlasGenBakeInfo* out) {
    auto s = atlas::stateOf(h);
    if (!h || !s) return ATLAS_ERR_STATE;
    std::lock_guard<std::mutex> l(s->mu);
    s->busy = 0;
    s->last[3] = 0;
    AtlasStatus result = ATLAS_OK;
    for (uint32_t done = 0; done < maxTiles && s->cursor < s->plan.size();) {
        size_t i = s->cursor;
        if (s->st[i] != atlas::TODO) {
            s->cursor++;
            continue;
        }
        const atlas::BakeTile t = s->plan[i];
        // the cache's thread behind: the tiles wait (their chunks' words are held until written)
        AtlasGenCacheStats cs{};
        if (atlas_gen_cache_peek(h, &cs) == ATLAS_OK && cs.queued > atlas::MAX_QUEUED) {
            s->busy = 2;
            break;
        }
        uint32_t rows = atlas::rowsOf(t.lod), chunks = 256 * rows;
        std::vector<uint32_t> kinds(size_t(chunks) * 2);
        int32_t txTz[2] = {t.tx, t.tz};
        uint32_t cap = s->spec.capacity, mixed = 0, taken = 0, flag = 0;
        bool retried = false, ok = false;
        for (;;) {
            AtlasGenTimes times{};
            auto t0 = std::chrono::steady_clock::now();
            AtlasStatus st = atlas_gen_far_ingest(h, t.lod, txTz, 1, cap, kinds.data(), &mixed, &taken, &flag, &times);
            s->makeMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            if (st == ATLAS_ERR_TIMEOUT) {
                s->busy = 1;  // (no window yet, or every far buffer still read by the grid: a frame frees them)
                atlas::fill(*s, h, out);
                return ATLAS_OK;
            }
            if (st != ATLAS_OK) {
                s->st[i] = atlas::FAILED;
                result = st;
                break;
            }
            bool noRoom = false;
            for (uint32_t c = 0; c < chunks && !noRoom; c++) noRoom = kinds[size_t(c) * 2] == 0xFFFFFFFEu;
            if (noRoom && !retried && cap < chunks) {
                retried = true;  // (more mixed chunks than the capacity: once more with room for every chunk)
                cap = chunks;
                continue;
            }
            ok = !noRoom && flag == 0;
            break;
        }
        s->st[i] = ok ? atlas::MADE : atlas::FAILED;
        s->last[0] = t.lod, s->last[1] = t.tx, s->last[2] = t.tz, s->last[3] = ok ? 1 : 2;
        s->cursor++;
        done++;
        if (result != ATLAS_OK) break;
    }
    atlas::fill(*s, h, out);
    return result;
}

ATLAS_API AtlasStatus atlas_gen_bake_info(AtlasGen* h, AtlasGenBakeInfo* out) {
    auto s = atlas::stateOf(h);
    if (!h || !s || !out) return ATLAS_ERR_STATE;
    std::lock_guard<std::mutex> l(s->mu);
    atlas::fill(*s, h, out);
    return ATLAS_OK;
}

ATLAS_API AtlasStatus atlas_gen_bake_end(AtlasGen* h) {
    atlas::bakeForget(h);
    return ATLAS_OK;
}

}  // extern "C"
