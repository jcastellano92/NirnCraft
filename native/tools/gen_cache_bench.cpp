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
#include <set>
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
    double t0 = nowMs();
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) {
        std::fprintf(stderr, "no Vulkan context\n");
        return 1;
    }
    std::vector<AtlasMaterial> mats(256, AtlasMaterial{{0.5f, 0.5f, 0.5f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
    atlas_set_materials(ctx, 1, mats.data(), uint32_t(mats.size()));
    if (cache != "-") atlas_set_cache(ctx, cache.c_str(), seed, 1, 0, 0);
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
    atlas_update(ctx, centre, 0);
    atlas_render(ctx, &f, &out);
    // the spawn radius's chunks, nearest first
    int ccx = int(std::floor(x / 16)), ccz = int(std::floor(z / 16));
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
        atlas_render(ctx, &f, &out);
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
        atlas_render(ctx, &f, &out);
        frames++;
    }
    double tFull = nowMs();
    // the horizon (AtlasClientGen.farStep's tiles: each coarse level's 5 x 5 tiles around the camera): from the cache
    // when known (atlas_gen_far_cache_ingest), else made on the device (atlas_gen_far_ingest, baked)
    size_t farTiles = 0, farCached = 0, farMade = 0, farBusy = 0;
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
                    continue;
                }
                for (int tries = 0; tries < 200; tries++) {
                    uint32_t mixed = 0, taken = 0;
                    AtlasStatus s = atlas_gen_far_ingest(g, k, t, 1, 2000, kinds.data(), &mixed, &taken, ff.data(), nullptr);
                    if (s == ATLAS_OK) {
                        farMade++;
                        break;
                    }
                    farBusy++;
                    atlas_render(ctx, &f, &out);  // (every far buffer still read: a frame takes them)
                    frames++;
                }
            }
        atlas_render(ctx, &f, &out);
        frames++;
    }
    for (int i = 0; i < 2000; i++) {
        std::string inf = atlas_info(ctx);
        size_t p = inf.find("\"coarseWaiting\":"), q = inf.find("\"baking\":");
        if (p != std::string::npos && std::atoi(inf.c_str() + p + 16) == 0 && i > 2 &&
            (q == std::string::npos || std::atoi(inf.c_str() + q + 9) == 0))
            break;
        atlas_render(ctx, &f, &out);
        frames++;
    }
    double tFar = nowMs();
    // (the last batches' packs come back with the next frames: the cache gets them before the stats)
    for (int i = 0; i < 6; i++) atlas_render(ctx, &f, &out), frames++;
    AtlasGenCacheStats cs{};
    atlas_gen_cache_stats(g, &cs);
    double km2 = double(todo.size()) * 256.0 / 1e6;
    std::printf("{\"horizon\":{\"tiles\":%zu,\"fromCache\":%zu,\"made\":%zu,\"busy\":%zu,\"ms\":%.0f,\"farWrites\":%llu,"
                "\"farHits\":%llu},", farTiles, farCached, farMade, farBusy, tFar - tFar0, (unsigned long long)cs.farWrites,
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
