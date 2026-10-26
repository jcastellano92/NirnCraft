// The whole-map coarse bake without Java (docs/ATLAS-GPU-LOOP.md section 3, step 6; gen_bake.cpp), and its launches
// measured: what AtlasCoarseBake does around a spawn point. Loads the program and map the parity test dumped
// (AtlasGenParityTest: NIRNCRAFT_GEN_DUMP=<dir>: prog.bin, map.bin); the ingest table is a stand-in (each generator id its
// own material: the sizes are of the generator's own variety), and no far stages (the canopy) are registered.
//   gen_bake_tool <dump dir> <cache dir> [mode=bake|load] [x=0] [z=0] [seed=1] [fp=0] [lod2=0|1] [bg=0|1] [bgcap=16.7]
//                 [minx= minz= maxx= maxz=] [margin=0] [first=6] [last=3] [feat=1]
// feat=1: the decoration's program from the dump (feat.bin, g2f.bin, f2g.bin: the game writes them with
// -Dnirncraft.atlas.dump=<dir>, or NIRNCRAFT_ATLAS_DUMP=<dir> in the harness) attached on the generator, so the far stage (the
// trees' canopy) runs as in the game; the JSON of each phase then has "subs": the generator's submissions by phase.
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
#include "../src/png.hpp"

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

static const char bytes_nl[] = {10, 0};

extern "C" ATLAS_API const char* atlas_fr_dump(const char* reason);  // (flight_recorder.cpp)
extern "C" ATLAS_API void atlas_fr_frame(void);
extern "C" ATLAS_API double atlas_fr_pass_ms(const char* name, uint64_t* seq);

static int infoInt(const std::string& inf, const char* key) {
    size_t p = inf.find(key);
    return p == std::string::npos ? -1 : std::atoi(inf.c_str() + p + std::strlen(key));
}

// mode=views: the grid alone as the far structure, as the game has it (the generator's terrain in every level, no SVDAG
// regions: no preview raster is loaded), its far pass timed at the game's resolution in the unified field, per view:
//   views=x,z,above,yaw,pitch[;...] (atlas_render's conventions: yaw 0 north, pitch < 0 down; above: blocks over the
//   generator's ground at x, z) w=2560 h=1440 frames=12 near=16 (level-0 chunks generated around the camera) out=<prefix>
// For each view: the grid's windows placed at the camera, every coarse level's 5 x 5 tiles around it made by the far path
// (atlas_gen_far_ingest: the bake's own), the near chunks generated, the grid settled; then the timed frames (the far pass
// GPU ms from the flight recorder, ATLAS_RECORDER=1), the steps a pixel, the share of grazing primary hits (the view ray
// within 15 degrees of the surface: |cos| < 0.26), the image <out>_<i>.png. One JSON line a view.
// (the settle: nothing pending in any level, then (ATLAS_RECORDER=1) the far work's GPU time, farTrace + far, within
// 3 % over 8 frames with the streaming queues empty (settle=<cap> frames at most, 150): the levels still fill in after
// nothing is pending, and a bench timed then measured the fill, not the kernel (the horizon's far pass +-20 %).
// stableFrames on the line; farTraceMs / farShadeMs the timed frames' medians, farTraceP10 / farShadeP10 their tenth
// percentiles: the GPU's clocks step between blocks of frames, the median moves with the mix of blocks. Neither the
// steady phase nor the timed frames read the outputs back (a G-buffer written across the bus costs the far kernel
// ~8 ms: the game keeps them on the device). ATLAS_AB (vk_backend.cpp): every other frame side B; its ms as bTraceMs ...,
// and abRatio, side B's far work over side A's frame by frame (median; abRatioP10 / P90 its spread over abPairs):
// the decision number, the ms indicative)
struct FarSide {
    const char* tr;
    const char* fa;
    uint64_t seq = 0;
    std::vector<double> trMs, faMs;
    // a new sample when the far pass's resolves moved (the trace's from the same resolve: no trace, 0)
    void sample() {
        uint64_t q = 0;
        double fa = atlas_fr_pass_ms(this->fa, &q);
        if (q == seq || fa < 0) return;
        seq = q;
        double t = atlas_fr_pass_ms(this->tr, nullptr);
        trMs.push_back(t > 0 ? t : 0.0);
        faMs.push_back(fa);
    }
    void reset() {
        sample();
        trMs.clear();
        faMs.clear();
    }
};
static int viewsMode(AtlasContext* ctx, AtlasGen* g, int argc, char** argv) {
    uint32_t W = uint32_t(std::atoi(arg(argc, argv, "w", "2560").c_str())), H = uint32_t(std::atoi(arg(argc, argv, "h", "1440").c_str()));
    int frames = std::atoi(arg(argc, argv, "frames", "12").c_str()), nearR = std::atoi(arg(argc, argv, "near", "16").c_str());
    // (tiles=r: each coarse level's tiles within r of the camera's, 2: 5 x 5; tilesK=r1,r2,..: per level from 1)
    int tileR[7] = {2, 2, 2, 2, 2, 2, 2};
    {
        int r = std::atoi(arg(argc, argv, "tiles", "2").c_str());
        for (int k = 0; k < 7; k++) tileR[k] = std::clamp(r, 0, 16);
        std::string tk = arg(argc, argv, "tilesK", "");
        int k = 1;
        for (size_t a = 0; a < tk.size() && k <= 6; k++) {
            size_t e = tk.find(',', a);
            tileR[k] = std::clamp(std::atoi(tk.substr(a, e == std::string::npos ? std::string::npos : e - a).c_str()), 0, 16);
            a = e == std::string::npos ? tk.size() : e + 1;
        }
    }
    std::string outPrefix = arg(argc, argv, "out", "views"), list = arg(argc, argv, "views", "0,0,60,90,-6");
    const uint32_t INFO = 164;
    std::vector<uint32_t> color(size_t(W) * H), normal(size_t(W) * H);
    std::vector<float> depth(size_t(W) * H), linear(size_t(W) * H * 4), nearLight(size_t(W) * H * 4);
    AtlasFrameOutput out{color.data(), normal.data(), depth.data(), linear.data(), nearLight.data()};
    int vi = 0;
    size_t s0 = 0;
    while (s0 < list.size()) {
        size_t s1 = list.find(';', s0);
        std::string v = list.substr(s0, s1 == std::string::npos ? std::string::npos : s1 - s0);
        s0 = s1 == std::string::npos ? list.size() : s1 + 1;
        double vx = 0, vz = 0, above = 60, yaw = 0, pitch = 0;
        if (std::sscanf(v.c_str(), "%lf,%lf,%lf,%lf,%lf", &vx, &vz, &above, &yaw, &pitch) != 5) continue;
        // the generator's ground at the camera: one chunk made, its highest solid section's top
        int32_t c0[2] = {int32_t(std::floor(vx / 16)), int32_t(std::floor(vz / 16))};
        std::vector<uint32_t> info(64 * INFO), flags(64);
        double ground = 64;
        if (atlas_gen_run(g, c0, 1, nullptr, flags.data(), info.data(), nullptr) == ATLAS_OK) {
            // (the info row: 4 words, then per section 4 words, the third its kind: 1 mixed, 2 solid; section 0 at y -64)
            for (int s = 39; s >= 0; s--)
                if (info[4 + s * 4 + 2] != 0) { ground = -64 + 16.0 * (s + 1); break; }
        }
        AtlasFrame f{};
        f.cameraPos[0] = vx;
        f.cameraPos[1] = ground + above;
        f.cameraPos[2] = vz;
        atlas::cameraMatrix(yaw, pitch, 70, double(W) / H, f.invViewProj);
        f.width = W;
        f.height = H;
        f.maxDistance = 40000;
        f.sunDir[0] = 0.3f, f.sunDir[1] = 0.6f, f.sunDir[2] = -0.74f;
        f.lodBias = 1;
        f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY | ATLAS_FRAME_NEAR_LIGHT | ATLAS_FRAME_PRIMARY_NEAR_LIT;
        double cam[3] = {f.cameraPos[0], f.cameraPos[1], f.cameraPos[2]};
        atlas_update(ctx, cam, 0);
        for (int i = 0; i < 4; i++) atlas_render(ctx, &f, &out);  // (the grid's windows placed)
        double t0 = nowMs();
        // every coarse level's tiles around the camera, by the far path (coarsest first)
        size_t made = 0, busy = 0, cachedTiles = 0, cachedChunks = 0;
        size_t tilesAll = 0;
        for (int k = 1; k <= 6; k++) tilesAll += size_t(2 * tileR[k] + 1) * (2 * tileR[k] + 1);
        double lastSaid = nowMs();
        // (progress for the queue's watchers: every half minute)
        auto say = [&](const char* what, size_t done, size_t all) {
            if (nowMs() - lastSaid < 30000.0) return;
            lastSaid = nowMs();
            std::fprintf(stderr, "TRACE progress view %d %s %zu / %zu\n", vi, what, done, all);
        };
        for (int k = 6; k >= 1; k--) {
            int span = 256 << k;
            int tx0 = int(std::floor(vx / span)), tz0 = int(std::floor(vz / span));
            uint32_t rows = (44u + (1u << k) - 1u) >> k;
            std::vector<uint32_t> kinds(256 * size_t(rows) * 2), ff(1);
            for (int dz = -tileR[k]; dz <= tileR[k]; dz++)
                for (int dx = -tileR[k]; dx <= tileR[k]; dx++) {
                    int32_t t[2] = {tx0 + dx, tz0 + dz};
                    say("tiles", made + cachedTiles, tilesAll);
                    // (a saved scene: the tile from the cache, the inner loop's seconds, when the cache knows it)
                    uint8_t served = 0;
                    uint32_t ch = 0, tk = 0;
                    if (atlas_gen_far_cache_ingest(g, k, t, 1, &served, &ch, &tk) == ATLAS_OK && served) {
                        cachedTiles++;
                        continue;
                    }
                    for (int tries = 0; tries < 200; tries++) {
                        uint32_t mixed = 0, taken = 0;
                        if (atlas_gen_far_ingest(g, k, t, 1, 256 * rows, kinds.data(), &mixed, &taken, ff.data(), nullptr) == ATLAS_OK) {
                            made++;
                            break;
                        }
                        busy++;
                        atlas_render(ctx, &f, &out);  // (every far buffer still read: a frame frees them)
                    }
                }
            atlas_render(ctx, &f, &out);
        }
        double tFar = nowMs();
        // level 0 around the camera, nearest first
        std::vector<std::pair<int, int>> todo;
        for (int dz = -nearR; dz <= nearR; dz++)
            for (int dx = -nearR; dx <= nearR; dx++)
                if (dx * dx + dz * dz <= nearR * nearR) todo.push_back({c0[0] + dx, c0[1] + dz});
        std::sort(todo.begin(), todo.end(), [&](auto& a, auto& b) {
            return (a.first - c0[0]) * (a.first - c0[0]) + (a.second - c0[1]) * (a.second - c0[1]) <
                   (b.first - c0[0]) * (b.first - c0[0]) + (b.second - c0[1]) * (b.second - c0[1]);
        });
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
        for (size_t at = 0; at < todo.size();) {
            say("chunks", at, todo.size());
            std::vector<int32_t> batch;
            while (at < todo.size() && batch.size() < 128) batch.push_back(todo[at].first), batch.push_back(todo[at].second), at++;
            uint32_t n0 = uint32_t(batch.size() / 2);
            // (the cache first: a saved scene's chunks read back)
            std::vector<uint8_t> hit(n0);
            std::vector<int32_t> miss, hits;
            std::vector<uint64_t> hitMasks;
            if (atlas_gen_cache_lookup(g, batch.data(), n0, info.data(), hit.data()) == ATLAS_OK) {
                for (uint32_t i = 0; i < n0; i++)
                    if (hit[i]) hits.push_back(batch[i * 2]), hits.push_back(batch[i * 2 + 1]), hitMasks.push_back(masksOf(info.data() + size_t(i) * INFO));
                    else miss.push_back(batch[i * 2]), miss.push_back(batch[i * 2 + 1]);
            } else {
                miss = batch;
            }
            if (!hits.empty()) {
                uint32_t sent = 0;
                atlas_gen_cache_ingest(g, hits.data(), uint32_t(hits.size() / 2), hitMasks.data(), &sent);
                cachedChunks += hits.size() / 2;
            }
            if (miss.empty()) {
                atlas_render(ctx, &f, &out);
                continue;
            }
            batch = miss;
            uint32_t m = uint32_t(batch.size() / 2);
            if (atlas_gen_run(g, batch.data(), m, nullptr, flags.data(), info.data(), nullptr) != ATLAS_OK) break;
            std::vector<uint64_t> masks(m);
            for (uint32_t i = 0; i < m; i++) masks[i] = flags[i] ? 0 : masksOf(info.data() + size_t(i) * INFO);
            uint32_t sent = 0;
            atlas_gen_ingest(g, masks.data(), &sent, nullptr);
            atlas_render(ctx, &f, &out);
        }
        // settled: nothing pending in any level
        int settle = 0;
        for (; settle < 3000; settle++) {
            std::string inf = atlas_info(ctx);
            if (settle > 4 && infoInt(inf, "\"pending\":") == 0 && infoInt(inf, "\"coarseWaiting\":") <= 0) break;
            say("settle frames (pending)", size_t(settle), size_t(infoInt(inf, "\"pending\":")));
            atlas_render(ctx, &f, &out);
        }
        // stable: the far work's time steady and the streaming idle (recorder on; else as before)
        int stableFrames = 0;
        bool idle = false;
        FarSide sa{"farTrace", "far"}, sb{"farTrace B", "far B"};
        _putenv_s("ATLAS_TIMING_DEVICE_OUTPUTS", "1");  // (no read back from here: the timed frames' conditions)
        if (std::getenv("ATLAS_RECORDER")) {
            int cap = std::atoi(arg(argc, argv, "settle", "150").c_str());
            std::vector<double> win;
            for (; stableFrames < cap; stableFrames++) {
                atlas_render(ctx, &f, &out);
                atlas_fr_frame();
                size_t had = sa.faMs.size();
                sa.sample();
                if (sa.faMs.size() == had) continue;
                win.push_back(sa.trMs.back() + sa.faMs.back());
                if (win.size() > 8) win.erase(win.begin());
                std::string inf = atlas_info(ctx);
                idle = infoInt(inf, "\"pending\":") == 0 && infoInt(inf, "\"waiting\":") <= 0 && infoInt(inf, "\"gpuWaiting\":") <= 0 &&
                       infoInt(inf, "\"coarseWaiting\":") <= 0 && infoInt(inf, "\"baking\":") <= 0;
                double lo = *std::min_element(win.begin(), win.end()), hi = *std::max_element(win.begin(), win.end());
                if (win.size() == 8 && lo > 0 && hi <= 1.03 * lo && idle) break;
                if (stableFrames % 10 == 0) say("settle frames (far ms steady)", size_t(stableFrames), size_t(hi * 100));
            }
        }
        double tSettled = nowMs();
        // the timed frames (outputs on the device, as in game), then one read back for the image and the hit statistics
        sa.reset();
        sb.reset();
        std::vector<double> ms;
        uint64_t steps = 0;
        for (int i = 0; i < frames; i++) {
            atlas_render(ctx, &f, &out);
            atlas_fr_frame();
            AtlasStats st{};
            atlas_get_stats(ctx, &st);
            ms.push_back(st.lastFrameMs);
            steps = st.raySteps;
            sa.sample();
            sb.sample();
            if (std::getenv("ATLAS_SETTLE_DEBUG"))
                std::fprintf(stderr, "timed %d A %zu %.3f %.3f B %zu %.3f %.3f\n", i, sa.faMs.size(), sa.trMs.empty() ? -1 : sa.trMs.back(),
                             sa.faMs.empty() ? -1 : sa.faMs.back(), sb.faMs.size(), sb.trMs.empty() ? -1 : sb.trMs.back(), sb.faMs.empty() ? -1 : sb.faMs.back());
        }
        const std::vector<double>&trMs = sa.trMs, &faMs = sa.faMs;
        // (the pairs: side A's k-th sample and side B's, adjacent frames)
        std::vector<double> ratio, trRatio;
        for (size_t k = 0; k < std::min(sa.faMs.size(), sb.faMs.size()); k++) {
            double a = sa.trMs[k] + sa.faMs[k], b = sb.trMs[k] + sb.faMs[k];
            if (a > 0) ratio.push_back(b / a);
            if (sa.trMs[k] > 0 && sb.trMs[k] > 0) trRatio.push_back(sb.trMs[k] / sa.trMs[k]);
        }
        // (the median, and the tenth percentile: the GPU's clocks step between blocks of frames, the low end the kernel's)
        auto pct = [](std::vector<double> v, double q) {
            std::sort(v.begin(), v.end());
            return v.empty() ? -1.0 : v[size_t(q * double(v.size() - 1) + 0.5)];
        };
        _putenv_s("ATLAS_TIMING_DEVICE_OUTPUTS", "");
        atlas_render(ctx, &f, &out);
        AtlasStats st{};
        atlas_get_stats(ctx, &st);
        steps = st.raySteps;
        std::string dump = std::getenv("ATLAS_RECORDER") ? atlas_fr_dump(("view " + std::to_string(vi)).c_str()) : "";
        // grazing primary hits: the view ray within 15 degrees of the hit's face (its normal, packed as packNormal)
        double yr = yaw * 3.14159265358979323846 / 180, pr = pitch * 3.14159265358979323846 / 180;
        double fx = std::sin(yr) * std::cos(pr), fy = std::sin(pr), fz = -std::cos(yr) * std::cos(pr);
        double rx = std::cos(yr), rz = std::sin(yr);
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double th = std::tan(70.0 * 3.14159265358979323846 / 360), asp = double(W) / H;
        size_t hits = 0, grazing = 0;
        for (uint32_t y = 0; y < H; y++)
            for (uint32_t x = 0; x < W; x++) {
                uint32_t n = normal[size_t(y) * W + x];
                if (((n >> 16) & 0x80u) == 0u) continue;  // (not a surface of the field)
                double ex = (n & 255u) / 255.0 * 2 - 1, ey = ((n >> 8) & 255u) / 255.0 * 2 - 1;
                double nx = ex, nz = ey, ny = 1 - std::fabs(ex) - std::fabs(ey);
                if (ny < 0) {
                    double ox = (1 - std::fabs(nz)) * (nx >= 0 ? 1 : -1), oz = (1 - std::fabs(nx)) * (nz >= 0 ? 1 : -1);
                    nx = ox, nz = oz;
                }
                double nl = std::sqrt(nx * nx + ny * ny + nz * nz);
                double sx = ((x + 0.5) / W * 2 - 1) * asp * th, sy = (1 - (y + 0.5) / H * 2) * th;
                double dx = fx + rx * sx + ux * sy, dy = fy + uy * sy, dz = fz + rz * sx + uz * sy;
                double dl = std::sqrt(dx * dx + dy * dy + dz * dz);
                double c = std::fabs((dx * nx + dy * ny + dz * nz) / (dl * nl));
                hits++;
                grazing += c < 0.26;
            }
        std::vector<double> sorted = ms;
        std::sort(sorted.begin(), sorted.end());
        std::string png = outPrefix + "_" + std::to_string(vi) + ".png";
        atlas::writePng(png, color.data(), W, H);
        {
            // (the hit distance per pixel, float32, -1 the sky: <out>_<i>.dist, for hit masks and distance agreement
            // between two passes; the host depth unprojected through the frame's inverse view-projection)
            std::vector<float> dist(size_t(W) * H, -1.0f);
            const float* m = f.invViewProj;
            for (uint32_t y = 0; y < H; y++)
                for (uint32_t x = 0; x < W; x++) {
                    float z = depth[size_t(y) * W + x];
                    if (z < 0.0f) {
                        dist[size_t(y) * W + x] = z <= -1e7f ? -1.0f : -z;  // (past the far plane: minus its distance)
                        continue;
                    }
                    float nx = (x + 0.5f) / W * 2 - 1, ny = 1 - (y + 0.5f) / H * 2;
                    float p[4];
                    for (int i = 0; i < 4; i++) p[i] = m[i] * nx + m[4 + i] * ny + m[8 + i] * z + m[12 + i];
                    if (std::fabs(p[3]) < 1e-20f) continue;
                    dist[size_t(y) * W + x] = std::sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) / std::fabs(p[3]);
                }
            if (FILE* df = std::fopen((outPrefix + "_" + std::to_string(vi) + ".dist").c_str(), "wb")) {
                std::fwrite(dist.data(), 4, dist.size(), df);
                std::fclose(df);
            }
        }
        {  // the grid's coverage after the settle (atlas_info voxgrid.coverage: holes and partial columns per level)
            std::this_thread::sleep_for(std::chrono::milliseconds(1700));  // (cached 1.5 s: a fresh one)
            std::string inf = atlas_info(ctx);
            size_t p = inf.find("\"coverage\":");
            if (p != std::string::npos)
                std::printf("{\"view\":%d,%s}%s", vi, inf.substr(p, inf.find("]}", inf.find("\"slotCap\"", p)) + 2 - p).c_str(), bytes_nl);
        }
        std::printf("{\"view\":%d,\"camera\":[%.0f,%.0f,%.0f],\"yaw\":%.0f,\"pitch\":%.0f,\"cachedTiles\":%zu,\"cachedChunks\":%zu,\"farTiles\":%zu,\"farBusy\":%zu,\"farMs\":%.0f,"
                    "\"settleFrames\":%d,\"stableFrames\":%d,\"idle\":%s,\"farTraceMs\":%.3f,\"farShadeMs\":%.3f,\"farTraceP10\":%.3f,\"farShadeP10\":%.3f,"
                    "\"bTraceMs\":%.3f,\"bShadeMs\":%.3f,\"bTraceP10\":%.3f,\"bShadeP10\":%.3f,\"abRatio\":%.4f,\"abRatioP10\":%.4f,"
                    "\"abRatioP90\":%.4f,\"abTraceRatio\":%.4f,\"abPairs\":%zu,\"settleMs\":%.0f,\"frameMsBest\":%.2f,\"frameMsMedian\":%.2f,\"stepsPerPixel\":%.2f,"
                    "\"hitShare\":%.3f,\"grazingShare\":%.3f,\"png\":\"%s\",\"dump\":\"%s\"}\n",
                    vi, cam[0], cam[1], cam[2], yaw, pitch, cachedTiles, cachedChunks, made, busy, tFar - t0, settle, stableFrames, idle ? "true" : "false",
                    pct(trMs, 0.5), pct(faMs, 0.5), pct(trMs, 0.1), pct(faMs, 0.1), pct(sb.trMs, 0.5), pct(sb.faMs, 0.5),
                    pct(sb.trMs, 0.1), pct(sb.faMs, 0.1), pct(ratio, 0.5), pct(ratio, 0.1), pct(ratio, 0.9), pct(trRatio, 0.5), ratio.size(),
                    tSettled - tFar, sorted.front(),
                    sorted[sorted.size() / 2], double(steps) / (double(W) * H), double(hits) / (double(W) * H),
                    hits ? double(grazing) / hits : 0.0, png.c_str(), dump.c_str());
        std::fflush(stdout);
        vi++;
    }
    return 0;
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
    // mode=pipes (the inner loop's prewarm, beside a running bench): the context's pipelines built (ATLAS_SHADER_DIR's
    // reloaded kernels too, with ATLAS_SHADER_WAIT=1) into the pipeline cache (pipes=) and the driver's own, then out:
    // the next bench's context loads them at once. CPU work (the driver's compile), no frame drawn
    if (mode == "pipes") {
        // (a frame of the views' kind, tiny: a shader folder's kernels are reloaded on the first frame, not at create)
        AtlasFrame f{};
        f.cameraPos[1] = 200;
        atlas::cameraMatrix(90, -3, 70, 1.0, f.invViewProj);
        f.width = f.height = 16;
        f.maxDistance = 1000;
        f.sunDir[1] = 1;
        f.lodBias = 1;
        f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY | ATLAS_FRAME_NEAR_LIGHT | ATLAS_FRAME_PRIMARY_NEAR_LIT;
        std::vector<uint32_t> c(256), n(256);
        std::vector<float> dp(256), lin(256 * 4), nl(256 * 4);
        AtlasFrameOutput o{c.data(), n.data(), dp.data(), lin.data(), nl.data()};
        double cam[3] = {0, 200, 0};
        atlas_update(ctx, cam, 0);
        for (int i = 0; i < 2; i++) atlas_render(ctx, &f, &o);
        std::printf("{\"pipes\":true,\"ms\":%.0f}\n", nowMs() - t0);
        atlas_destroy(ctx);
        return 0;
    }
    std::vector<AtlasMaterial> mats(256, AtlasMaterial{{0.5f, 0.5f, 0.5f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
    if (mode == "views")  // (the stand-in ids told apart: earthy colours of their own)
        for (size_t i = 1; i < mats.size(); i++) {
            uint32_t h = uint32_t(i) * 2654435761u;
            float t = float(h & 255u) / 255.0f, u = float((h >> 8) & 255u) / 255.0f;
            mats[i].albedo[0] = 0.25f + 0.35f * t, mats[i].albedo[1] = 0.3f + 0.3f * u, mats[i].albedo[2] = 0.2f + 0.2f * t * u;
        }
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
    if (arg(argc, argv, "feat", "0") != "0") {
        std::vector<uint32_t> fw = load(dump + "/feat.bin"), g2f = load(dump + "/g2f.bin"), f2g = load(dump + "/f2g.bin");
        if (fw.empty() || g2f.empty() || f2g.empty()) {
            std::fprintf(stderr, "feat=1 needs feat.bin, g2f.bin and f2g.bin in %s\n", dump.c_str());
            return 2;
        }
        if (atlas_gen_store_enable(g, 768) != ATLAS_OK) {
            std::fprintf(stderr, "store_enable: %s\n", atlas_gen_last_error(g));
            return 1;
        }
        AtlasFeat* feat = nullptr;
        double a0 = nowMs();
        if (atlas_feat_attach(g, fw.data(), uint32_t(fw.size()), g2f.data(), uint32_t(g2f.size()), f2g.data(), uint32_t(f2g.size()), &feat) !=
            ATLAS_OK) {
            std::fprintf(stderr, "feat_attach: %s\n", atlas_gen_last_error(g));
            return 1;
        }
        std::fprintf(stderr, "decoration attached in %.0f ms\n", nowMs() - a0);
    }
    atlas_gen_set_fingerprint(g, std::strtoull(arg(argc, argv, "fp", "0").c_str(), nullptr, 10));
    if (cache != "-") atlas_set_cache(ctx, cache.c_str(), seed, 1, 0, 0);
    double tReady = nowMs();
    if (mode == "views") {
        int r = viewsMode(ctx, g, argc, argv);
        atlas_gen_destroy(g);
        atlas_destroy(ctx);
        return r;
    }
    AtlasFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = std::atof(arg(argc, argv, "y", "360").c_str());
    f.cameraPos[2] = z;
    atlas::cameraMatrix(0, -35, 70, 16.0 / 9.0, f.invViewProj);
    const int FW = std::atoi(arg(argc, argv, "w", "320").c_str()), FH = std::atoi(arg(argc, argv, "h", "180").c_str());  // (w=2560 h=1440: the game's frames)
    f.width = FW;
    f.height = FH;
    f.maxDistance = 4000;
    f.sunDir[1] = 1;
    f.lodBias = 1;
    std::vector<uint32_t> color(size_t(FW) * FH);
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
            std::vector<uint32_t> img(size_t(FW) * FH);
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

    // boost=1 (the default): the generator's submissions unpaced, as the loading screen holds the player (else paced by the frames' cadence)
    if (arg(argc, argv, "boost", "1") != "0") atlas_gen_pace_boost(g, 1);
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
        uint32_t lastDone = 0;
        double lastProgress = nowMs();
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
            if (info.busy && busySteps % 300 == 1) {
                std::string inf = atlas_info(ctx);
                size_t p = inf.find("\"coarse");
                std::fprintf(stderr, "STALL busy=%u made=%u: %s\n", info.busy, info.madeTotal,
                             inf.substr(p == std::string::npos ? 0 : p, 700).c_str());
            }
            if (nowMs() - lastProgress > 90000) {
                std::fprintf(stderr, "no tile finished for 90 s: giving up\n");
                break;
            }
            if (info.madeTotal + info.failedTotal != lastDone) lastDone = info.madeTotal + info.failedTotal, lastProgress = nowMs();
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
        char subs[6000] = {};
        atlas_gen_sub_stats(g, subs, sizeof subs, 1);
        std::printf("],\"subs\":\"%s\",\"mbPerLevel\":%s,\"farWrites\":%llu,\"encodeMs\":%.0f,\"pace\":{\"duty\":%.2f,\"waitedMs\":%.0f,\"farWaitedMs\":%.0f,"
                    "\"maxSubMs\":%.0f}%s}\n",
                    subs, jsonBytes(bytes).c_str(), (unsigned long long)cs.farWrites, cs.encodeMs, ps.duty, ps.waitedMs, ps.farWaitedMs,
                    ps.maxSubmissionMs, fr.c_str());
        std::fflush(stdout);
        return 0;
    };

    int rc = 0;
    if (mode == "l0") {
        // level 0 of a block of chunks around (x, z), terrain only (no decoration stages): ms per chunk by pass, the share of
        // the sections that are air / shell (reach a coarse voxel's vote from above or the side) / buried (blocks wholly
        // below the lowest ground less 16: caves, ores, aquifers, sealed in rock) / deep rock, and the cache's bytes per chunk
        int R = std::atoi(arg(argc, argv, "radius", "16").c_str());
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
        std::vector<uint32_t> info(64 * INFO), flags(64);
        double passMs[6] = {}, kernelMs = 0, totalMs = 0, wall0 = nowMs();
        size_t air = 0, shell = 0, buried = 0, rock = 0, chunks = 0, flagged = 0, batches = 0;
        for (size_t at = 0; at < todo.size(); at += 64) {
            std::vector<int32_t> batch;
            for (size_t i = at; i < std::min(todo.size(), at + 64); i++) batch.push_back(todo[i].first), batch.push_back(todo[i].second);
            uint32_t n = uint32_t(batch.size() / 2);
            AtlasGenTimes t{};
            if (atlas_gen_run(g, batch.data(), n, nullptr, flags.data(), info.data(), &t) != ATLAS_OK) {
                std::fprintf(stderr, "run: %s\n", atlas_gen_last_error(g));
                return 1;
            }
            kernelMs += t.kernelMs, totalMs += t.totalMs;
            for (int k = 0; k < 6; k++) passMs[k] += t.passMs[k];
            std::vector<uint64_t> masks(n);
            for (uint32_t c = 0; c < n; c++) {
                const uint32_t* row = info.data() + size_t(c) * INFO;
                int lowest = int(row[0]), top = int(row[1]);
                if (at == 0 && c < 3) {  // (a look at the first rows: lowest ground, top, biome, kinds by section)
                    std::fprintf(stderr, "ROW chunk %d,%d lowest %d top %d biome %u kinds ", batch[c * 2], batch[c * 2 + 1], lowest, top, row[2]);
                    for (int sct = 0; sct < 40; sct++) std::fprintf(stderr, "%u", row[4 + sct * 4 + 2]);
                    std::fprintf(stderr, bytes_nl);
                }
                flagged += flags[c] != 0;
                uint64_t m = 0;
                for (int sct = 0; sct < 40; sct++) {
                    uint32_t kind = row[4 + sct * 4 + 2];
                    int y0 = (sct - 4) * 16;
                    if (kind == 0) air++;
                    else if (kind == 2) rock++;
                    else {
                        if (y0 + 15 < lowest - 16) buried++;
                        else shell++;
                        m |= uint64_t(1) << sct;
                    }
                }
                (void)top;
                masks[c] = flags[c] ? 0 : m;
            }
            uint32_t sent = 0;
            atlas_gen_ingest(g, masks.data(), &sent, nullptr);
            chunks += n;
            batches++;
            for (int i = 0; i < 3; i++) atlas_render(ctx, &f, &out);
        }
        double wall = nowMs() - wall0;
        for (int i = 0; i < 200; i++) atlas_render(ctx, &f, &out);
        AtlasGenCacheStats cs{};
        atlas_gen_cache_stats(g, &cs);
        double secs = double(air + shell + buried + rock);
        std::printf("{\"phase\":\"level0\",\"chunks\":%zu,\"flagged\":%zu,\"wallMs\":%.0f,\"msPerChunkWall\":%.2f,\"kernelMsPerChunk\":%.2f,"
                    "\"passMsPerChunk\":[%.3f,%.3f,%.3f,%.3f,%.3f,%.3f],\"sections\":{\"air\":%.3f,\"shell\":%.3f,\"buriedBlocks\":%.3f,"
                    "\"deepRock\":%.3f},\"cacheMB\":%.2f,\"bytesPerChunk\":%.0f}\n",
                    chunks, flagged, wall, wall / chunks, kernelMs / chunks, passMs[0] / chunks, passMs[1] / chunks, passMs[2] / chunks,
                    passMs[3] / chunks, passMs[4] / chunks, passMs[5] / chunks, air / secs, shell / secs, buried / secs, rock / secs,
                    cs.fileBytes / 1048576.0, double(cs.fileBytes) / chunks);
    } else if (mode == "bake") {
        int first = std::atoi(arg(argc, argv, "first", "6").c_str()), last = std::atoi(arg(argc, argv, "last", "3").c_str());
        std::printf("{\"setupMs\":%.0f,\"placedMs\":%.0f}\n", tReady - t0, tPlaced - tReady);
        if (first >= last) rc = phase(first, last, "levels");
        if (rc == 0 && arg(argc, argv, "lod2", "0") != "0") {
            atlas_gen_pace_boost(g, 0);  // (after control the bake is paced by the frames)
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
