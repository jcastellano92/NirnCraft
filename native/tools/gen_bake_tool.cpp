// The whole-map coarse bake without Java (docs/ATLAS-GPU-LOOP.md section 3, step 6; gen_bake.cpp), and its launches
// measured: what AtlasCoarseBake does around a spawn point. Loads the program and map the parity test dumped
// (AtlasGenParityTest: NIRNCRAFT_GEN_DUMP=<dir>: prog.bin, map.bin); the ingest table is a stand-in (each generator id its
// own material: the sizes are of the generator's own variety), and no far stages (the canopy) are registered.
//   gen_bake_tool <dump dir> <cache dir> [mode=bake|load] [x=0] [z=0] [seed=1] [fp=0] [lod2=0|1] [bg=0|1] [bgcap=16.7]
//                 [minx= minz= maxx= maxz=] [margin=0] [first=6] [last=3]
// mode=bake: the first launch: bake levels first..last (the loading phase), then (lod2=1) level 2 (the background bake,
// with bg=1 frames rendered on a thread at the game's cadence and their times kept). mode=load: a later launch: a new
// context on the same cache; the boot load (every tile of the coarse levels' windows from the cache, nearest first), the
// time to the grid holding it all; bake_begin must find every tile known. Prints one JSON line per phase.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <string>
#include <thread>
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

// the bytes of the far files by level under dir (every gen-* set below it)
static void farBytes(const std::string& dir, uint64_t out[8]) {
    std::memset(out, 0, 8 * sizeof(uint64_t));
    std::error_code ec;
    for (auto& e : std::filesystem::recursive_directory_iterator(dir, ec)) {
        if (!e.is_regular_file(ec) || e.path().extension() != ".gch") continue;
        std::string n = e.path().filename().string();
        if (n.size() > 2 && n[0] == 'f' && n[1] >= '1' && n[1] <= '6') out[n[1] - '0'] += e.file_size(ec);
    }
}

static std::string jsonBytes(const uint64_t b[8]) {
    char s[256];
    std::snprintf(s, sizeof s, "[%.2f,%.2f,%.2f,%.2f,%.2f,%.2f]", b[1] / 1048576.0, b[2] / 1048576.0, b[3] / 1048576.0, b[4] / 1048576.0,
                  b[5] / 1048576.0, b[6] / 1048576.0);
    return s;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "gen_bake_tool <dump dir> <cache dir> [mode=bake|load] [x= z= seed= fp= lod2= bg= bgcap=]\n");
        return 2;
    }
    std::string dump = argv[1], cache = argv[2], mode = arg(argc, argv, "mode", "bake");
    double x = std::atof(arg(argc, argv, "x", "0").c_str()), z = std::atof(arg(argc, argv, "z", "0").c_str());
    uint64_t seed = std::strtoull(arg(argc, argv, "seed", "1").c_str(), nullptr, 10);
    std::vector<uint32_t> prog = load(dump + "/prog.bin"), map = load(dump + "/map.bin");
    if (prog.empty() || map.empty()) {
        std::fprintf(stderr, "no prog.bin / map.bin in %s\n", dump.c_str());
        return 2;
    }
    if (!arg(argc, argv, "pipes", "").empty()) atlas_set_pipeline_cache(arg(argc, argv, "pipes", "").c_str());
    double t0 = nowMs();
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) {
        std::fprintf(stderr, "no Vulkan context\n");
        return 1;
    }
    std::vector<AtlasMaterial> mats(256, AtlasMaterial{{0.5f, 0.5f, 0.5f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
    atlas_set_materials(ctx, 1, mats.data(), uint32_t(mats.size()));
    AtlasGen* g = nullptr;
    if (atlas_gen_create_for(ctx, &g) != ATLAS_OK) {
        std::fprintf(stderr, "no generator on the context's device\n");
        return 1;
    }
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
    atlas_gen_set_fingerprint(g, std::strtoull(arg(argc, argv, "fp", "0").c_str(), nullptr, 10));
    atlas_set_cache(ctx, cache.c_str(), seed, 1, 0, 0);
    double tReady = nowMs();
    AtlasFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = std::atof(arg(argc, argv, "y", "360").c_str());
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
    // bg=1: a thread renders the frames from the first bake step on (the game's cadence, bgcap ms a frame), their wall times
    // kept; else the main thread renders one frame between steps
    std::atomic<bool> bgOn{false}, bgStop{false};
    std::vector<float> bgMs;
    std::thread bgThread;
    double bgCap = std::atof(arg(argc, argv, "bgcap", "16.7").c_str());
    bool bgWanted = arg(argc, argv, "bg", "0") != "0";
    auto renderMain = [&]() {
        if (bgOn) std::this_thread::sleep_for(std::chrono::milliseconds(2));
        else atlas_render(ctx, &f, &out);
    };
    auto startBg = [&] {
        if (!bgWanted || bgOn) return;
        bgOn = true;
        bgMs.clear();
        bgThread = std::thread([&] {
            std::vector<uint32_t> img(320 * 180);
            AtlasFrameOutput o2{img.data(), nullptr, nullptr};
            while (!bgStop) {
                auto a = std::chrono::steady_clock::now();
                atlas_render(ctx, &f, &o2);
                double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count();
                bgMs.push_back(float(ms));
                if (bgCap > ms) std::this_thread::sleep_for(std::chrono::microseconds(int64_t((bgCap - ms) * 1000.0)));
            }
        });
    };
    auto stopBg = [&]() -> std::string {
        if (!bgOn) return "";
        bgStop = true;
        bgThread.join();
        bgOn = false;
        bgStop = false;
        std::vector<float> v = bgMs;
        if (v.empty()) return "";
        std::sort(v.begin(), v.end());
        double sum = 0;
        for (float q : v) sum += q;
        char b[400];
        std::snprintf(b, sizeof b,
                      ",\"frames\":{\"n\":%zu,\"p50\":%.1f,\"p90\":%.1f,\"p99\":%.1f,\"max\":%.1f,\"meanFps\":%.1f,\"over25ms\":%zu}", v.size(),
                      v[v.size() / 2], v[v.size() * 9 / 10], v[v.size() * 99 / 100], v.back(), 1000.0 * v.size() / sum,
                      size_t(std::count_if(v.begin(), v.end(), [](float q) { return q > 25.0f; })));
        return b;
    };
    atlas_update(ctx, centre, 0);
    for (int i = 0; i < 4; i++) atlas_render(ctx, &f, &out);  // (the grid placed: its windows)
    double tPlaced = nowMs();

    AtlasGenBakeSpec spec{};
    spec.minX = std::atoi(arg(argc, argv, "minx", "-15360").c_str());  // TamrielMap.MIN_X
    spec.minZ = std::atoi(arg(argc, argv, "minz", "-5120").c_str());   // TamrielMap.MIN_Z
    spec.maxX = std::atoi(arg(argc, argv, "maxx", "12800").c_str());   // MIN_X + 880 * 32
    spec.maxZ = std::atoi(arg(argc, argv, "maxz", "16384").c_str());   // MIN_Z + 672 * 32
    spec.centerX = int32_t(x);
    spec.centerZ = int32_t(z);
    spec.marginBlocks = std::atoi(arg(argc, argv, "margin", "0").c_str());

    auto phase = [&](int first, int last, const char* name) -> int {
        spec.lodFirst = first;
        spec.lodLast = last;
        AtlasGenBakeInfo info{};
        if (atlas_gen_bake_begin(g, &spec, &info) != ATLAS_OK) {
            std::fprintf(stderr, "bake_begin: %s\n", atlas_gen_last_error(g));
            return 1;
        }
        std::printf("{\"phase\":\"%s begin\",\"tiles\":%u,\"known\":%u}\n", name, info.total, info.knownTotal);
        std::fflush(stdout);
        double p0 = nowMs(), lastNote = p0;
        uint32_t lastMade[8] = {};
        double levelMs[8] = {};
        double levelAt = p0;
        size_t steps = 0, busySteps = 0, frames = 0;
        int curLod = first;
        for (;;) {
            AtlasStatus s = atlas_gen_bake_step(g, 2, &info);
            steps++;
            if (s != ATLAS_OK) {
                std::fprintf(stderr, "bake_step: %s\n", atlas_gen_last_error(g));
                break;
            }
            for (int k = 1; k <= 6; k++)
                if (info.made[k] + info.failed[k] != lastMade[k]) {
                    lastMade[k] = info.made[k] + info.failed[k];
                    levelMs[k] += nowMs() - levelAt;
                    levelAt = nowMs();
                }
            (void)curLod;
            if (info.busy) busySteps++;
            renderMain();
            frames++;
            bool allSettled = info.madeTotal + info.failedTotal + info.knownTotal >= info.total;
            if (allSettled) break;
            if (nowMs() - lastNote > 5000) {
                lastNote = nowMs();
                std::printf("{\"progress\":\"%s\",\"made\":%u,\"failed\":%u,\"of\":%u,\"queued\":%u,\"s\":%.1f}\n", name, info.madeTotal,
                            info.failedTotal, info.total, info.queued, (lastNote - p0) / 1000);
                std::fflush(stdout);
            }
        }
        double tDone = nowMs();
        // (the last packs come back with the next frames; the cache writes them)
        for (int i = 0; i < 400; i++) {
            atlas_gen_bake_info(g, &info);
            if (info.durable >= info.madeTotal && info.queued == 0) break;
            renderMain();
            frames++;
            if (!bgOn) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        double tDurable = nowMs();
        std::string fr = stopBg();
        AtlasGenCacheStats cs{};
        atlas_gen_cache_stats(g, &cs);
        uint64_t bytes[8];
        farBytes(cache, bytes);
        AtlasGenPaceStats ps{};
        atlas_gen_pace_stats(g, &ps);
        std::printf("{\"phase\":\"%s\",\"tiles\":%u,\"known\":%u,\"made\":%u,\"failed\":%u,\"durable\":%u,\"makeMs\":%.0f,"
                    "\"madeS\":%.2f,\"durableS\":%.2f,\"steps\":%zu,\"busySteps\":%zu,\"frames\":%zu,"
                    "\"perLevel\":[",
                    name, info.total, info.knownTotal, info.madeTotal, info.failedTotal, info.durable, info.makeMs, (tDone - p0) / 1000,
                    (tDurable - p0) / 1000, steps, busySteps, frames);
        for (int k = 1; k <= 6; k++)
            std::printf("%s{\"lod\":%d,\"tiles\":%u,\"made\":%u,\"failed\":%u,\"s\":%.2f}", k > 1 ? "," : "", k, info.tiles[k], info.made[k],
                        info.failed[k], levelMs[k] / 1000);
        std::printf("],\"mbPerLevel\":%s,\"farWrites\":%llu,\"encodeMs\":%.0f,\"pace\":{\"duty\":%.2f,\"waitedMs\":%.0f,\"farWaitedMs\":%.0f,"
                    "\"maxSubMs\":%.0f}%s}\n",
                    jsonBytes(bytes).c_str(), (unsigned long long)cs.farWrites, cs.encodeMs, ps.duty, ps.waitedMs, ps.farWaitedMs,
                    ps.maxSubmissionMs, fr.c_str());
        std::fflush(stdout);
        return 0;
    };

    int rc = 0;
    if (mode == "bake") {
        int first = std::atoi(arg(argc, argv, "first", "6").c_str()), last = std::atoi(arg(argc, argv, "last", "3").c_str());
        std::printf("{\"setupMs\":%.0f,\"placedMs\":%.0f}\n", tReady - t0, tPlaced - tReady);
        if (first >= last) rc = phase(first, last, "levels");
        if (rc == 0 && arg(argc, argv, "lod2", "0") != "0") {
            startBg();
            rc = phase(2, 2, "level2");
        }
    } else {
        // the boot load: every tile that covers a coarse level's window, nearest first, from the cache
        AtlasGenBakeInfo info{};
        spec.lodFirst = 6;
        spec.lodLast = 2;
        double b0 = nowMs();
        AtlasStatus bs = atlas_gen_bake_begin(g, &spec, &info);
        double bMs = nowMs() - b0;
        std::printf("{\"phase\":\"begin (levels 6-2)\",\"status\":%d,\"tiles\":%u,\"known\":%u,\"scanMs\":%.0f}\n", int(bs), info.total,
                    info.knownTotal, bMs);
        double l0 = nowMs();
        size_t tiles = 0, served = 0, chunks = 0, taken = 0;
        for (int k = 6; k >= 2; k--) {
            int32_t w[6] = {};
            atlas_gen_coarse_window(g, k, w);
            int span = 256 << k;
            (void)span;
            // (the window's columns: origin w[0], w[1], side w[2], in chunks of the level; a tile is 16 chunks)
            std::vector<std::pair<double, std::pair<int, int>>> order;
            for (int tz = (w[1] >= 0 ? w[1] / 16 : -((-w[1] + 15) / 16)); tz <= ((w[1] + w[2] - 1) >= 0 ? (w[1] + w[2] - 1) / 16 : -((-(w[1] + w[2] - 1) + 15) / 16)); tz++)
                for (int tx = (w[0] >= 0 ? w[0] / 16 : -((-w[0] + 15) / 16)); tx <= ((w[0] + w[2] - 1) >= 0 ? (w[0] + w[2] - 1) / 16 : -((-(w[0] + w[2] - 1) + 15) / 16)); tx++) {
                    double dx = (tx + 0.5) * (256 << k) - x, dz = (tz + 0.5) * (256 << k) - z;
                    order.push_back({std::sqrt(dx * dx + dz * dz) / (256 << k), {tx, tz}});
                }
            std::sort(order.begin(), order.end());
            for (auto& o : order) {
                int32_t t[2] = {o.second.first, o.second.second};
                uint8_t sv = 0;
                uint32_t ch = 0, tk = 0;
                tiles++;
                atlas_gen_far_cache_ingest(g, k, t, 1, &sv, &ch, &tk);
                if (sv) served++, chunks += ch, taken += tk;
            }
        }
        double lMs = nowMs() - l0;
        // until the grid has built what it was given
        for (int i = 0; i < 2000; i++) {
            std::string inf = atlas_info(ctx);
            size_t p = inf.find("\"coarseWaiting\":"), gv = inf.find("\"coarseGiven\":");
            if (p != std::string::npos && std::atoi(inf.c_str() + p + 16) == 0 && i > 2 &&
                (gv == std::string::npos || std::atoi(inf.c_str() + gv + 14) == 0))
                break;
            atlas_render(ctx, &f, &out);
        }
        double fMs = nowMs() - l0;
        std::printf("{\"phase\":\"boot load\",\"windowTiles\":%zu,\"served\":%zu,\"chunks\":%zu,\"taken\":%zu,\"ingestMs\":%.0f,"
                    "\"toGridMs\":%.0f}\n",
                    tiles, served, chunks, taken, lMs, fMs);
    }
    stopBg();
    atlas_gen_destroy(g);
    atlas_destroy(ctx);
    return rc;
}
