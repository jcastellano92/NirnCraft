// atlas_lab: the far-field experiment bed (docs/ATLAS-LOD.md, "Experiments"). One real object (a box of block state ids
// exported in game with /nirncraft-atlaslab) rendered at several distances by each candidate representation, against a
// converged reference, at the game's true pixel size (1080p, fov 70). Quick: a CPU tracer, seconds per run.
//
//   atlas_lab blocks=<dir>/blocks.bin volume=<dir>/tree.alv out=sheet.png [dist=10,50,200,600,1500]
//   atlas_lab ... camera=mc eye=x,y,z yaw=Y pitch=P fov=70 w=1280 h=800 [shade=mc] [surface=x.alc]: one live capture's own view
//             (Minecraft conventions), whole frames, for the offline-vs-live sheet (the harness's camera_from)
//             [variants=ref,current,anymip,micro,cov] [yaw=30] [pitch=-12] [frames=16] [panel=192] [threads=N]
//             [scale=1] [bias=0] [tol=1] [fog=0] [grass=91BD59] [foliage=77AB2F]
//
// Representations (one column each in the sheet, rows are distances):
//   ref      micro-voxels (each block state's 4x4x4 shape and its face texels), 64 samples a pixel: what a perfect near
//            renderer shows at that distance
//   current  today's far field: kept blocks only (solids, leaves, water, lights, slabs, stairs) as whole-block voxels
//            with their mean colour, coarser levels by the 3-of-8 vote, the leaf-gap pass-through at levels 0..1
//   anymip   Voxy's rule: every non-air block, a coarse voxel is filled when any child is
//   micro    the micro-voxels at every distance, one sample a frame (level -1 alone: exact but aliasing and costly far)
//   covbox   cov, and each voxel keeps its content's box (6 small numbers): a ray must cross the box, so a slab, a
//            thin wall or a sheet of ground stays as thick as it is instead of filling its voxel
//   boxes    adaptive boxes: an octree of tight content boxes (rectangles), each drawn as one masked-texture box
//            where flattening it is under the pixel (tol=1, fog-scaled), else split ("adaptive boxes" below)
//   groups   boxes with a hierarchy shaped by the content (splits where the two children's boxes are tightest), not
//            a grid: groups cross objects and chunks ("content-shaped groups" below); "boxes" in the table = the cut
//   boxesf / groupsf   the same with prefiltered face textures (the average under the pixel, holes as coverage)
//
// motion=1 [mframes=48 speed=0.5 strafe=0.3 blend=0.1]: the moving camera with a real TAA (reprojection by hit
// distance, neighbourhood clamp, 10 % blend) at each distance; motion dE against ref at every frame, and flicker:
// the mean frame-to-frame change of each pixel's error (shimmer, crawling edges; a steady blur scores low).
// sweep=N: instead of the table, a fly-in from the farthest to the nearest distance in N steps through one window
// (window=96 half-size); per representation pop (how much more its picture changes per step than ref's: popping),
// popMax, dE against ref, steps; the sheet's rows are the representations, its columns the steps.
//   cov      the unified proposal: micro-voxels and a mip chain from them where each voxel keeps its per-axis coverage
//            (how much of it blocks a ray along x, y, z) and the colour seen from each of its 6 sides; the level is
//            continuous (the pixel's footprint, stochastically between the two nearest levels), a ray passes a voxel in
//            proportion to its coverage; TAA (the frames) resolves the rest
//
// Metrics per (distance, representation) against ref: iou (soft silhouette), dE (mean CIE76 of the converged image),
// dE1 (one frame: what shimmers in motion), steps (voxels visited per ray), ns (per ray). Last stdout line: JSON.
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "png.hpp"

#include "atlas_lab_scene.hpp"

namespace {


// ---- whole-block levels (current, anymip) ---------------------------------------------------------------------------

struct BlockLevel {
    int nx = 0, ny = 0, nz = 0;
    std::vector<uint32_t> id;  // a representative state id; 0 = empty
    uint32_t at(int x, int y, int z) const { return id[size_t(x + nx * (z + nz * y))]; }
};
std::vector<BlockLevel> curLevels, anyLevels;

int kindOf(uint32_t id) {
    if (!id) return -1;
    const Block& b = lib[id];
    return b.cls == 1 ? 1 : b.cls == 2 ? 2 : 0;
}

void buildBlockLevels(std::vector<BlockLevel>& L, bool voxy) {
    BlockLevel b0{vol.sx, vol.sy, vol.sz, std::vector<uint32_t>(vol.ids.size(), 0)};
    for (size_t i = 0; i < vol.ids.size(); i++) {
        uint32_t id = vol.ids[i];
        if (id < lib.size() && lib[id].present && (voxy ? lib[id].any : lib[id].kept)) b0.id[i] = id;
    }
    L.push_back(std::move(b0));
    while (std::max({L.back().nx, L.back().ny, L.back().nz}) > 1 && L.size() < 9) {
        const BlockLevel& p = L.back();
        BlockLevel q{(p.nx + 1) / 2, (p.ny + 1) / 2, (p.nz + 1) / 2, {}};
        q.id.assign(size_t(q.nx) * q.ny * q.nz, 0);
        for (int y = 0; y < q.ny; y++)
            for (int z = 0; z < q.nz; z++)
                for (int x = 0; x < q.nx; x++) {
                    uint32_t ch[8];
                    int counts[3] = {0, 0, 0}, filled = 0;
                    for (int k = 0; k < 8; k++) {
                        int cx = 2 * x + (k & 1), cz = 2 * z + ((k >> 1) & 1), cy = 2 * y + (k >> 2);
                        ch[k] = cx < p.nx && cy < p.ny && cz < p.nz ? p.at(cx, cy, cz) : 0;
                        int kd = kindOf(ch[k]);
                        if (kd >= 0) counts[kd]++, filled++;
                    }
                    int want = voxy ? (filled ? (counts[0] ? 0 : counts[1] ? 1 : 2) : -1)
                                    : counts[0] >= 3 ? 0 : counts[1] >= 2 ? 1 : counts[2] >= 4 ? 2 : -1;
                    if (want < 0) continue;
                    uint32_t best = 0;
                    int bestN = 0;
                    for (int a = 7; a >= 0; a--) {
                        if (kindOf(ch[a]) != want) continue;
                        int c = 0;
                        for (int b = 0; b < 8; b++) c += ch[b] == ch[a];
                        if (c > bestN) bestN = c, best = ch[a];
                    }
                    q.id[size_t(x + q.nx * (z + q.nz * y))] = best;
                }
        L.push_back(std::move(q));
    }
}

Sample traceBlocks(const std::vector<BlockLevel>& L, int level, V3 o, V3 d, bool leafGap, uint32_t seed) {
    level = std::clamp(level, 0, int(L.size()) - 1);
    const BlockLevel& g = L[size_t(level)];
    float cs = float(1 << level);
    Sample s;
    s.steps = dda(o, d, cs, g.nx, g.ny, g.nz, [&](int x, int y, int z, int axis) {
        uint32_t id = g.at(x, y, z);
        if (!id) return false;
        const Block& b = lib[id];
        if (leafGap && b.cls == 1 && level <= 1 && hashf(x, y, z, seed) < 0.3f) return false;
        int f = enteredFace(axis, d);
        s.hit = true;
        s.t = boxEntry(o, d, {x * cs, y * cs, z * cs}, cs);
        s.id = hash(uint32_t(x) * 73856093u ^ uint32_t(y) * 19349663u ^ uint32_t(z) * 83492791u ^ uint32_t(level)) | 1u;
        s.c = lit(tinted(b, b.mean), f);
        return true;
    });
    return s;
}

// ---- the coverage chain (cov) ---------------------------------------------------------------------------------------

struct CovLevel {
    int nx = 0, ny = 0, nz = 0, n = 0;  // n micro-voxels a side
    std::vector<uint8_t> cov;           // 3 a voxel: blocked share along x, y, z (0..255)
    std::vector<RGB> col;               // 6 a voxel: the colour seen from each side (Face order)
    std::vector<uint8_t> box;           // 6 a voxel: its content's bounds in micro-voxels, min x y z, max x y z (0..n)
};
std::vector<CovLevel> covLevels;  // [k - 1] is level k (2^k micro-voxels a side)

void buildCovLevels(int threads) {
    int mx = vol.sx * 4, my = vol.sy * 4, mz = vol.sz * 4;
    for (int k = 1; k <= 8; k++) {
        int n = 1 << k;
        CovLevel L;
        L.n = n;
        L.nx = (mx + n - 1) / n, L.ny = (my + n - 1) / n, L.nz = (mz + n - 1) / n;
        size_t cells = size_t(L.nx) * L.ny * L.nz;
        L.cov.assign(cells * 3, 0);
        L.col.assign(cells * 6, RGB{});
        L.box.assign(cells * 6, 0);
        std::atomic<int> next{0};
        auto work = [&]() {
            for (int y; (y = next++) < L.ny;)
                for (int z = 0; z < L.nz; z++)
                    for (int x = 0; x < L.nx; x++) {
                        int ox = x * n, oy = y * n, oz = z * n;
                        // skip voxels whose blocks are all air
                        bool any = false;
                        for (int by = oy >> 2; by <= (oy + n - 1) >> 2 && !any; by++)
                            for (int bz = oz >> 2; bz <= (oz + n - 1) >> 2 && !any; bz++)
                                for (int bx = ox >> 2; bx <= (ox + n - 1) >> 2 && !any; bx++) {
                                    const Block* b = vol.at(bx, by, bz);
                                    any = b && b->any && b->mask;
                                }
                        if (!any) continue;
                        size_t i = size_t(x + L.nx * (z + L.nz * y));
                        int bmin[3] = {n, n, n}, bmax[3] = {0, 0, 0};
                        for (int a = 0; a < 3; a++) {
                            int hits = 0;
                            RGB lo{}, hi{};
                            for (int u = 0; u < n; u++)
                                for (int v = 0; v < n; v++) {
                                    auto cell = [&](int t, int& px, int& py, int& pz) {
                                        if (a == 0) px = ox + t, py = oy + u, pz = oz + v;
                                        else if (a == 1) px = ox + u, py = oy + t, pz = oz + v;
                                        else px = ox + u, py = oy + v, pz = oz + t;
                                    };
                                    int px, py, pz, first = -1;
                                    for (int t = 0; t < n; t++) {
                                        cell(t, px, py, pz);
                                        if (microAt(px, py, pz)) {
                                            first = t;
                                            break;
                                        }
                                    }
                                    if (first < 0) continue;
                                    hits++;
                                    cell(first, px, py, pz);
                                    {
                                        int q[3] = {px - ox, py - oy, pz - oz};
                                        for (int k2 = 0; k2 < 3; k2++) bmin[k2] = std::min(bmin[k2], q[k2]), bmax[k2] = std::max(bmax[k2], q[k2] + 1);
                                    }
                                    RGB c = microCol(px, py, pz, FACE_LOW[a]);
                                    lo.r += c.r, lo.g += c.g, lo.b += c.b;
                                    for (int t = n - 1; t >= 0; t--) {
                                        cell(t, px, py, pz);
                                        if (microAt(px, py, pz)) {
                                            int q[3] = {px - ox, py - oy, pz - oz};
                                            for (int k2 = 0; k2 < 3; k2++) bmin[k2] = std::min(bmin[k2], q[k2]), bmax[k2] = std::max(bmax[k2], q[k2] + 1);
                                            RGB c2 = microCol(px, py, pz, FACE_HIGH[a]);
                                            hi.r += c2.r, hi.g += c2.g, hi.b += c2.b;
                                            break;
                                        }
                                    }
                                }
                            L.cov[i * 3 + a] = uint8_t(std::lround(255.0 * hits / (n * n)));
                            if (hits) {
                                float inv = 1.f / hits;
                                L.col[i * 6 + FACE_LOW[a]] = {lo.r * inv, lo.g * inv, lo.b * inv};
                                L.col[i * 6 + FACE_HIGH[a]] = {hi.r * inv, hi.g * inv, hi.b * inv};
                            }
                        }
                        for (int k2 = 0; k2 < 3; k2++) L.box[i * 6 + k2] = uint8_t(std::min(bmin[k2], bmax[k2])), L.box[i * 6 + 3 + k2] = uint8_t(bmax[k2]);
                    }
        };
        std::vector<std::thread> ts;
        for (int t = 0; t < threads; t++) ts.emplace_back(work);
        for (auto& t : ts) t.join();
        covLevels.push_back(std::move(L));
        if (std::max({covLevels.back().nx, covLevels.back().ny, covLevels.back().nz}) <= 1) break;
    }
}

Sample traceCov(int level, V3 o, V3 d, uint32_t seed, bool boxed = false) {
    if (level <= 0) return traceMicro(o, d);
    level = std::min(level, int(covLevels.size()));
    const CovLevel& L = covLevels[size_t(level - 1)];
    float cs = L.n * 0.25f;
    float ad[3] = {std::fabs(d.x), std::fabs(d.y), std::fabs(d.z)}, sum = ad[0] + ad[1] + ad[2];
    Sample s;
    s.steps = dda(o, d, cs, L.nx, L.ny, L.nz, [&](int x, int y, int z, int axis) {
        size_t i = size_t(x + L.nx * (z + L.nz * y));
        const uint8_t* c = &L.cov[i * 3];
        if (!(c[0] | c[1] | c[2])) return false;
        float p;
        if (boxed) {
            // the content's box: the ray must cross it (thin sheets stay thin, slabs stay half), and the coverage is
            // of that box's sides, not the voxel's
            const uint8_t* b = &L.box[i * 6];
            float m = 0.25f, lo3[3] = {(x * L.n + b[0]) * m, (y * L.n + b[1]) * m, (z * L.n + b[2]) * m},
                  hi3[3] = {(x * L.n + b[3]) * m, (y * L.n + b[4]) * m, (z * L.n + b[5]) * m};
            float t0 = -1e30f, t1 = 1e30f;
            int ax = axis;
            for (int a2 = 0; a2 < 3; a2++) {
                if (std::fabs(d[a2]) < 1e-12f) {
                    if (o[a2] < lo3[a2] || o[a2] > hi3[a2]) return false;
                    continue;
                }
                float ta = (lo3[a2] - o[a2]) / d[a2], tb2 = (hi3[a2] - o[a2]) / d[a2];
                if (ta > tb2) std::swap(ta, tb2);
                if (ta > t0) t0 = ta, ax = a2;
                t1 = std::min(t1, tb2);
            }
            if (t0 > t1 || t1 < 0) return false;
            axis = ax;
            float ext[3] = {float(b[3] - b[0]), float(b[4] - b[1]), float(b[5] - b[2])};
            float area[3] = {ext[1] * ext[2], ext[0] * ext[2], ext[0] * ext[1]}, cb[3];
            for (int a2 = 0; a2 < 3; a2++) cb[a2] = area[a2] > 0 ? std::min(1.f, c[a2] / 255.f * L.n * L.n / area[a2]) : 0.f;
            p = (ad[0] * cb[0] + ad[1] * cb[1] + ad[2] * cb[2]) / sum;
        } else {
            p = (ad[0] * c[0] + ad[1] * c[1] + ad[2] * c[2]) / (255.f * sum);
        }
        if (hashf(x, y, z, seed) >= p) return false;
        // the side the ray came in by (a buried voxel's other sides are hidden by its neighbours); when nothing is
        // seen from that side (the hit came by the other axes' coverage), the sides facing the ray by their share
        int f = enteredFace(axis, d);
        RGB cc;
        if (c[axis]) {
            cc = lit(L.col[i * 6 + f], f);
        } else {
            float w = 0;
            for (int a2 = 0; a2 < 3; a2++) {
                float wa = ad[a2] * c[a2];
                if (wa <= 0) continue;
                int f2 = enteredFace(a2, d);
                RGB lc = lit(L.col[i * 6 + f2], f2);
                cc.r += wa * lc.r, cc.g += wa * lc.g, cc.b += wa * lc.b;
                w += wa;
            }
            cc = {cc.r / w, cc.g / w, cc.b / w};
        }
        s.hit = true;
        s.t = boxEntry(o, d, {x * cs, y * cs, z * cs}, cs);
        s.id = hash(uint32_t(x) * 73856093u ^ uint32_t(y) * 19349663u ^ uint32_t(z) * 83492791u ^ uint32_t(level)) | 1u;
        s.c = cc;
        return true;
    });
    return s;
}

}  // namespace

int main(int argc, char** argv) {
    auto a = parse(argc, argv);
    std::string blocksPath = str(a, "blocks", ""), volPath = str(a, "volume", ""), out = str(a, "out", "atlas_lab.png");
    if (!loadBlocks(blocksPath)) {
        std::fprintf(stderr, "atlas_lab: no block library at '%s' (in game: /nirncraft-atlaslab blocks)\n", blocksPath.c_str());
        return 2;
    }
    if (!loadVolume(volPath)) {
        std::fprintf(stderr, "atlas_lab: no volume at '%s' (in game: /nirncraft-atlaslab here <name> <r> <down> <up>)\n", volPath.c_str());
        return 2;
    }
    // box=x0,y0,z0,x1,y1,z1 (world blocks, inclusive): only the blocks inside it exist, the rest is air (one element
    // cut from an export: a house, a tree, a chunk; its light keeps what the world gave it)
    if (a.count("box")) {
        int b[6] = {0, 0, 0, 0, 0, 0};
        if (std::sscanf(a["box"].c_str(), "%d,%d,%d,%d,%d,%d", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) == 6) {
            int o[3] = {vol.x0, vol.y0, vol.z0};
            vol.cropped = true;
            for (int k = 0; k < 3; k++)
                vol.c0[k] = std::min(b[k], b[k + 3]) - o[k], vol.c1[k] = std::max(b[k], b[k + 3]) - o[k];
            if (!vol.sparse) {  // a dense volume shrinks to the box: every level and tree is built over the element only
                int n[3], lo[3];
                for (int k = 0; k < 3; k++) {
                    int dim = k == 0 ? vol.sx : k == 1 ? vol.sy : vol.sz;
                    lo[k] = std::clamp(vol.c0[k], 0, dim - 1);
                    n[k] = std::clamp(vol.c1[k], 0, dim - 1) - lo[k] + 1;
                }
                std::vector<uint32_t> ids(size_t(n[0]) * n[1] * n[2]);
                for (int y = 0; y < n[1]; y++)
                    for (int z = 0; z < n[2]; z++)
                        for (int x = 0; x < n[0]; x++)
                            ids[size_t(x + n[0] * (z + n[2] * y))] =
                                vol.ids[size_t((x + lo[0]) + vol.sx * ((z + lo[2]) + vol.sz * (y + lo[1])))];
                vol.ids.swap(ids);
                vol.x0 += lo[0], vol.y0 += lo[1], vol.z0 += lo[2];
                vol.sx = n[0], vol.sy = n[1], vol.sz = n[2];
                vol.cropped = false;
            }
        }
    }
    {  // the tints the game blended at the box's centre (AtlasLabExport writes them beside the volume)
        std::string side = volPath.substr(0, volPath.size() >= 4 ? volPath.size() - 4 : 0) + ".json";
        std::ifstream f(side);
        std::string txt((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        auto field = [&](const char* k, RGB& out) {
            auto i = txt.find(std::string("\"") + k + "\"");
            if (i == std::string::npos) return;
            auto q = txt.find('"', txt.find(':', i) + 1);
            if (q != std::string::npos) out = unpack(uint32_t(std::strtoul(txt.c_str() + q + 1, nullptr, 16)));
        };
        field("grass", tintGrass);
        field("foliage", tintFoliage);
    }
    mcShade = str(a, "shade", "") == "mc";
    // surface=<x>.alc (atlas_truth bake=): hits take their colour from the real faces' cards (see Card)
    if (a.count("surface") && !loadCards(a["surface"])) {
        std::fprintf(stderr, "atlas_lab: no surface cards at '%s'\n", a["surface"].c_str());
        return 2;
    }
    leafCards = !cards.empty() && str(a, "leaves", "") == "cards";
    if (a.count("sky")) SKY = unpack(uint32_t(std::strtoul(a["sky"].c_str(), nullptr, 16)));
    cardMipBias = float(num(a, "mipbias", 0));
    if (a.count("grass")) tintGrass = unpack(uint32_t(std::strtoul(a["grass"].c_str(), nullptr, 16)));
    if (a.count("foliage")) tintFoliage = unpack(uint32_t(std::strtoul(a["foliage"].c_str(), nullptr, 16)));
    int threads = int(num(a, "threads", std::max(1u, std::thread::hardware_concurrency() / 2)));
    int frames = int(num(a, "frames", 16)), panel = int(num(a, "panel", 192));
    float scale = float(num(a, "scale", 1)), bias = float(num(a, "bias", 0));
    float tolPix = float(num(a, "tol", 1)), fog = float(num(a, "fog", 0));  // fog: extinction per block
    std::vector<float> dists;
    for (auto& t : split(str(a, "dist", "10,50,200,600,1500"))) dists.push_back(float(std::atof(t.c_str())));
    std::vector<std::string> variants = split(str(a, "variants", "ref,current,anymip,micro,cov,covbox,boxes,groups"));
    // the sheets compare every variant with ref, so it leads; a camera frame with variants= named renders exactly
    // those, the first to out= with its mask and depth (it had put ref there: the element tier's "groups" were ref)
    bool namedCamera = str(a, "camera", "") == "mc" && a.count("variants");
    if (!namedCamera && (variants.empty() || variants[0] != "ref")) variants.insert(variants.begin(), "ref");

    // the object's bounds (non-air blocks; a sparse view: its whole extent)
    int bmin[3] = {1 << 30, 1 << 30, 1 << 30}, bmax[3] = {-1, -1, -1};
    if (vol.sparse) bmin[0] = bmin[1] = bmin[2] = 0, bmax[0] = vol.sx, bmax[1] = vol.sy, bmax[2] = vol.sz;
    for (int y = 0; y < (vol.sparse ? 0 : vol.sy); y++)
        for (int z = 0; z < vol.sz; z++)
            for (int x = 0; x < vol.sx; x++) {
                const Block* b = vol.at(x, y, z);
                if (!b || !b->any) continue;
                int p[3] = {x, y, z};
                for (int k = 0; k < 3; k++) bmin[k] = std::min(bmin[k], p[k]), bmax[k] = std::max(bmax[k], p[k] + 1);
            }
    if (bmax[0] < 0) {
        std::fprintf(stderr, "atlas_lab: the volume is empty\n");
        return 2;
    }
    V3 centre{(bmin[0] + bmax[0]) * 0.5f, (bmin[1] + bmax[1]) * 0.5f, (bmin[2] + bmax[2]) * 0.5f};
    if (a.count("tx")) centre = {float(num(a, "tx", 0) - vol.x0), float(num(a, "ty", 0) - vol.y0), float(num(a, "tz", 0) - vol.z0)};
    float radius = 0.5f * std::sqrt(float((bmax[0] - bmin[0]) * (bmax[0] - bmin[0]) + (bmax[1] - bmin[1]) * (bmax[1] - bmin[1]) +
                                          (bmax[2] - bmin[2]) * (bmax[2] - bmin[2])));

    if (vol.sparse)  // a view is for the reference and micro-voxels (the other representations build from dense boxes)
        variants.erase(std::remove_if(variants.begin(), variants.end(), [](const std::string& v) { return v != "ref" && v != "micro"; }),
                       variants.end());
    auto tb = std::chrono::steady_clock::now();
    bool needCur = std::find(variants.begin(), variants.end(), "current") != variants.end();
    bool needAny = std::find(variants.begin(), variants.end(), "anymip") != variants.end();
    bool needCov = std::find(variants.begin(), variants.end(), "cov") != variants.end() ||
                   std::find(variants.begin(), variants.end(), "covbox") != variants.end();
    if (needCur) buildBlockLevels(curLevels, false);
    if (needAny) buildBlockLevels(anyLevels, true);
    if (needCov) buildCovLevels(threads);
    auto wants = [&](const char* x) { return std::find(variants.begin(), variants.end(), x) != variants.end(); };
    if (wants("boxes") || wants("boxesf")) buildBoxes();
    if (wants("groups") || wants("groupsf")) buildGroups();
    double buildMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tb).count();
    std::fprintf(stderr, "atlas_lab: %dx%dx%d blocks, object %.0f blocks across, levels built in %.0f ms (octree %zu nodes, groups %zu)\n",
                 vol.sx, vol.sy, vol.sz, 2 * radius, buildMs, nodes.size(), groups.size());

    // the camera: the game's pixel (1080 rows, fov 70), looking at the object from each distance
    float yaw = float(num(a, "yaw", 30)) * 3.14159265f / 180.f, pitch = float(num(a, "pitch", -12)) * 3.14159265f / 180.f;
    float fov = float(num(a, "fov", 70)) * 3.14159265f / 180.f;
    float pa = 2.f * std::tan(fov / 2) / float(num(a, "screen", 1080)) * scale;  // tangent-plane size of one pixel
    V3 fwd = norm({std::sin(yaw) * std::cos(pitch), std::sin(pitch), -std::cos(yaw) * std::cos(pitch)});
    V3 right = norm(cross(fwd, {0, 1, 0})), up = cross(right, fwd);

    // one view: the object from distance D through a window of 2 * half true pixels, converged over the frames (TAA)
    struct View {
        Image img;
        std::vector<RGB> first;  // frame 0 alone
        long long drawn = -1;
        double steps = 0, ns = 0, level = -2;
    };
    // offset: other seeds and jitters; eyeAt: another eye (the moving camera; D still sets the level and fog);
    // tOut: per pixel the hit's distance along its ray (-1 sky); framesN: frames instead of `frames`
    auto render = [&](float D, const std::string& v, int half, int offset = 0, const V3* eyeAt = nullptr,
                      std::vector<float>* tOut = nullptr, int framesN = 0) {
        View out;
        V3 eye = eyeAt ? *eyeAt : centre - fwd * D;
        int nf = framesN > 0 ? framesN : frames;
        int W = 2 * half;
        float foot = D * pa;  // the pixel's size at the object, blocks
        // fog: what it hides may be rougher (an error of e shows as e times the transmittance)
        float trans = std::exp(-fog * D), fogBias = fog > 0 ? -std::log2(std::max(trans, 0.02f)) : 0.f;
        float microLevel = std::log2(std::max(foot * 4.f, 1e-6f)) + bias + fogBias, blockLevel = std::log2(std::max(foot, 1e-6f)) + bias + fogBias;
        bool isRef = v == "ref", grouped = v == "groups" || v == "groupsf", boxed = v == "boxes" || v == "boxesf";
        bool prefilter = v == "groupsf" || v == "boxesf";
        int spp = isRef ? 4 : 1;
        Image& img = out.img;
        img = Image{W, W, std::vector<RGB>(size_t(W) * W), std::vector<float>(size_t(W) * W, 0)};
        out.first.assign(size_t(W) * W, RGB{});
        std::atomic<long long> steps{0}, rays{0};
        auto t0 = std::chrono::steady_clock::now();
        size_t markN = boxed ? nodes.size() : grouped ? groups.size() : 0;
        std::vector<std::atomic<uint8_t>> marks(markN);
        if (tOut) tOut->assign(size_t(W) * W, -1.f);
        for (int f = 0; f < nf; f++) {
            drawnBoxes = f == 0 && markN ? &marks : nullptr;
            std::atomic<int> next{0};
            auto work = [&]() {
                long long st = 0, n = 0;
                for (int py; (py = next++) < W;)
                    for (int px = 0; px < W; px++) {
                        RGB acc{};
                        float hit = 0;
                        for (int s = 0; s < spp; s++) {
                            // Halton (2, 3) jitter: the frame's TAA offset, and ref's extra samples
                            int k = (f + offset) * spp + s + 1;
                            float jx = 0, jy = 0, fr = 0.5f;
                            for (int i = k; i; i /= 2, fr *= 0.5f) jx += fr * (i % 2);
                            fr = 1.f / 3;
                            for (int i = k; i; i /= 3, fr /= 3) jy += fr * (i % 3);
                            float ox = (px - half + jx) * pa, oy = (py - half + jy) * pa;
                            V3 dir = norm(fwd + right * ox - up * oy);
                            uint32_t seed = hash(uint32_t(px + 4096 * py) ^ hash(uint32_t((f + offset) * 977 + s)));
                            Sample sm;
                            if (isRef || v == "micro") sm = traceMicro(eye, dir);
                            else if (v == "current" || v == "anymip") {
                                sm = traceBlocks(v == "current" ? curLevels : anyLevels, std::max(0, int(std::floor(blockLevel + 0.5f))), eye, dir,
                                                 v == "current", seed);
                            } else if (v == "cov" || v == "covbox") {
                                float l = std::max(0.f, microLevel);
                                int l0 = int(std::floor(l));
                                int lvl = l0 + (hashf(px, py, f + offset, 0xC0FFEEu) < l - l0 ? 1 : 0);
                                sm = traceCov(lvl, eye, dir, seed, v == "covbox");
                            } else if (boxed || grouped) {
                                float dither = 0.7f + 0.6f * hashf(px, py, f + offset, 0xB0C5u);
                                float tol = tolPix * dither / std::max(trans, 0.02f);
                                sm = boxed ? traceBoxes(eye, dir, pa, tol, prefilter, seed) : traceGroups(eye, dir, pa, tol, prefilter, seed);
                            }
                            st += sm.steps;
                            n++;
                            if (tOut) (*tOut)[size_t(px + W * py)] = sm.hit ? sm.t : -1.f;
                            RGB c = sm.hit ? sm.c : SKY;
                            if (fog > 0 && sm.hit) {  // the same fog for every representation (at the object's distance)
                                c.r = c.r * trans + SKY.r * (1 - trans), c.g = c.g * trans + SKY.g * (1 - trans), c.b = c.b * trans + SKY.b * (1 - trans);
                            }
                            acc.r += c.r, acc.g += c.g, acc.b += c.b;
                            hit += sm.hit ? 1.f : 0.f;
                        }
                        size_t i = size_t(px + W * py);
                        RGB c{acc.r / spp, acc.g / spp, acc.b / spp};
                        if (f == 0) out.first[i] = c;
                        img.c[i].r += c.r / nf, img.c[i].g += c.g / nf, img.c[i].b += c.b / nf;
                        img.a[i] += hit / spp / nf;
                    }
                steps += st;
                rays += n;
            };
            std::vector<std::thread> ts;
            for (int t = 0; t < threads; t++) ts.emplace_back(work);
            for (auto& t : ts) t.join();
            if (drawnBoxes) {
                out.drawn = 0;
                for (auto& m : marks) out.drawn += m.load();
                drawnBoxes = nullptr;
            }
        }
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        out.steps = rays ? double(steps) / double(rays) : 0;
        out.ns = rays ? ms * 1e6 / double(rays) : 0;
        out.level = v == "cov" || v == "covbox" ? std::max(0.f, microLevel) - 2.f
                    : (v == "current" || v == "anymip") ? std::max(0.f, std::floor(blockLevel + 0.5f)) : -2.f;
        return out;
    };
    // mean colour difference over the pixels where either image shows the object
    auto diff = [](const Image& x, const Image& y) {
        double de = 0;
        int n = 0;
        for (size_t i = 0; i < x.a.size(); i++)
            if (std::max(x.a[i], y.a[i]) > 0.01f) de += deltaE(x.c[i], y.c[i]), n++;
        return n ? de / n : 0.0;
    };
    auto blit = [&](std::vector<uint32_t>& sheet, int sheetW, const Image& img, int ox, int oy, int size) {
        int W = img.w;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int sx0 = x * W / size, sx1 = std::max(sx0 + 1, (x + 1) * W / size);
                int sy0 = y * W / size, sy1 = std::max(sy0 + 1, (y + 1) * W / size);
                RGB c{};
                int k = 0;
                for (int yy = sy0; yy < sy1; yy++)
                    for (int xx = sx0; xx < sx1; xx++, k++) {
                        const RGB& p = img.c[size_t(xx + W * yy)];
                        c.r += p.r, c.g += p.g, c.b += p.b;
                    }
                auto ch = [&](float v2) { return uint32_t(std::clamp(v2 / k, 0.f, 255.f)); };
                sheet[size_t(ox + x) + size_t(sheetW) * size_t(oy + y)] = 0xFF000000u | ch(c.b) << 16 | ch(c.g) << 8 | ch(c.r);
            }
        for (int x = 0; x < size; x++) sheet[size_t(ox + x) + size_t(sheetW) * size_t(oy)] = 0xFF000000u;
        for (int y = 0; y < size; y++) sheet[size_t(ox) + size_t(sheetW) * size_t(oy + y)] = 0xFF000000u;
    };

    if (str(a, "camera", "") == "mc") {
        // cameras=FILE: one frame per line ("out eyex,eyey,eyez yaw pitch fov w h"), everything loaded and built once
        // (an element's distance ladder and fly-ins in one process); otherwise the one camera of the arguments
        auto frame = [&](Args ca, const std::string& outp) {
            // a Minecraft camera (a live capture's own: eye in world blocks, yaw 0 = south, pitch > 0 looks down, fov
            // vertical), the whole w x h frame at true pixels; one frame per representation: out (the first) and
            // out_<name>.png; out_mask.png is where the frame shows the exported blocks (the share of samples that hit)
            float ex = 0, ey = 0, ez = 0;
            std::sscanf(str(ca, "eye", "0,0,0").c_str(), "%f,%f,%f", &ex, &ey, &ez);
            float myaw = float(num(ca, "yaw", 0)) * 3.14159265f / 180.f, mpitch = float(num(ca, "pitch", 0)) * 3.14159265f / 180.f;
            float mfov = float(num(ca, "fov", 70)) * 3.14159265f / 180.f;
            int w = int(num(ca, "w", 1280)), h = int(num(ca, "h", 720));
            V3 cf = norm({-std::sin(myaw) * std::cos(mpitch), -std::sin(mpitch), std::cos(myaw) * std::cos(mpitch)});
            V3 cr = norm(cross(cf, {0, 1, 0})), cu = cross(cr, cf);
            float cpa = 2.f * std::tan(mfov / 2) / float(h);
            V3 eye{ex - vol.x0, ey - vol.y0, ez - vol.z0};
            std::string json = "{\"tool\":\"atlas_lab\",\"mode\":\"camera\",\"eye\":[" + std::to_string(ex) + "," + std::to_string(ey) + "," +
                               std::to_string(ez) + "],\"frames\":[";
            // probe=x,y;x,y...: what the real blocks show at those pixels (pixel centres): distance, block, world position
            {
                std::string ps = str(ca, "probe", "");
                std::stringstream ss(ps);
                for (std::string one; std::getline(ss, one, ';');) {
                    int px = 0, py = 0;
                    if (std::sscanf(one.c_str(), "%d,%d", &px, &py) != 2) continue;
                    V3 dir = norm(cf + cr * ((px + 0.5f - w * 0.5f) * cpa) - cu * ((py + 0.5f - h * 0.5f) * cpa));
                    Sample sm = traceMicro(eye, dir);
                    if (!sm.hit) {
                        std::fprintf(stderr, "probe %d,%d: sky (nothing in the exported blocks)\n", px, py);
                        continue;
                    }
                    V3 hp = eye + dir * (sm.t + 0.01f);
                    const Block* b = vol.at(int(std::floor(hp.x)), int(std::floor(hp.y)), int(std::floor(hp.z)));
                    std::fprintf(stderr, "probe %d,%d: %.1f blocks, %s at %d %d %d\n", px, py, sm.t, b ? b->name.c_str() : "?",
                                 int(std::floor(hp.x)) + vol.x0, int(std::floor(hp.y)) + vol.y0, int(std::floor(hp.z)) + vol.z0);
                }
            }
            for (size_t vi = 0; vi < variants.size(); vi++) {
                const std::string& v = variants[vi];
                int spp = v == "ref" ? 4 : 1;
                std::vector<RGB> acc(size_t(w) * h);
                std::vector<float> hit(size_t(w) * h, 0.f);
                std::vector<float> depth(size_t(w) * h, 1e30f);  // nearest hit over the samples (blocks)
                auto t0 = std::chrono::steady_clock::now();
                for (int f = 0; f < frames; f++) {
                    std::atomic<int> next{0};
                    auto work = [&]() {
                        for (int py; (py = next++) < h;)
                            for (int px = 0; px < w; px++)
                                for (int s2 = 0; s2 < spp; s2++) {
                                    int k = f * spp + s2 + 1;
                                    float jx = 0, jy = 0, fr = 0.5f;
                                    for (int i = k; i; i /= 2, fr *= 0.5f) jx += fr * (i % 2);
                                    fr = 1.f / 3;
                                    for (int i = k; i; i /= 3, fr /= 3) jy += fr * (i % 3);
                                    V3 dir = norm(cf + cr * ((px - w * 0.5f + jx) * cpa) - cu * ((py - h * 0.5f + jy) * cpa));
                                    uint32_t seed = hash(uint32_t(px + 4096 * py) ^ hash(uint32_t(f * 977 + s2)));
                                    float D = 1.f;  // the level is per ray here: the distance to what the ray meets
                                    Sample sm;
                                    if (v == "ref" || v == "micro") sm = traceMicro(eye, dir);
                                    else if (v == "boxes" || v == "groups") {
                                        float dither = 0.7f + 0.6f * hashf(px, py, f, 0xB0C5u);
                                        sm = v == "boxes" ? traceBoxes(eye, dir, cpa, tolPix * dither, false, seed)
                                                          : traceGroups(eye, dir, cpa, tolPix * dither, false, seed);
                                    } else if (v == "current") {
                                        sm = traceBlocks(curLevels, 0, eye, dir, true, seed);
                                    }
                                    (void)D;
                                    size_t i = size_t(px + w * py);
                                    RGB c = sm.hit ? sm.c : SKY;
                                    float wgt = 1.f / float(spp * frames);
                                    acc[i].r += c.r * wgt, acc[i].g += c.g * wgt, acc[i].b += c.b * wgt;
                                    hit[i] += sm.hit ? wgt : 0.f;
                                    if (sm.hit) depth[i] = std::min(depth[i], sm.t);
                                }
                    };
                    std::vector<std::thread> ts;
                    for (int t = 0; t < threads; t++) ts.emplace_back(work);
                    for (auto& t : ts) t.join();
                }
                double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                std::vector<uint32_t> px(size_t(w) * h), mask(size_t(w) * h);
                auto ch = [](float v2) { return uint32_t(std::clamp(v2, 0.f, 255.f)); };
                for (size_t i = 0; i < px.size(); i++) {
                    px[i] = 0xFF000000u | ch(acc[i].b) << 16 | ch(acc[i].g) << 8 | ch(acc[i].r);
                    uint32_t m = uint32_t(std::clamp(hit[i] * 255.f, 0.f, 255.f));
                    mask[i] = 0xFF000000u | m << 16 | m << 8 | m;
                }
                std::string base = outp.size() > 4 ? outp.substr(0, outp.size() - 4) : outp;
                std::string path = vi == 0 ? outp : base + "_" + v + ".png";
                atlas::writePng(path, px.data(), uint32_t(w), uint32_t(h));
                if (vi == 0) {
                    atlas::writePng(base + "_mask.png", mask.data(), uint32_t(w), uint32_t(h));
                    // out_depth.png: the distance to what each pixel shows, 1/16 block steps in 24 bits (R high, G, B low),
                    // all ones for sky: the harness splits the comparison by distance band with it
                    std::vector<uint32_t> dp(size_t(w) * h);
                    for (size_t i = 0; i < dp.size(); i++) {
                        uint32_t v = depth[i] >= 1e29f ? 0xFFFFFFu : uint32_t(std::min(depth[i] * 16.f, 16777214.f));
                        dp[i] = 0xFF000000u | (v & 255u) << 16 | ((v >> 8) & 255u) << 8 | (v >> 16);
                    }
                    atlas::writePng(base + "_depth.png", dp.data(), uint32_t(w), uint32_t(h));
                }
                json += (vi ? "," : "") + std::string("{\"variant\":\"") + v + "\",\"ms\":" + std::to_string(int(ms)) + "}";
                std::fprintf(stderr, "atlas_lab: camera frame %s %dx%d in %.0f ms -> %s\n", v.c_str(), w, h, ms, path.c_str());
            }
            json += "]}";
            std::printf("%s\n", json.c_str());
        };
        if (a.count("cameras")) {
            std::ifstream cf(a["cameras"]);
            int n = 0;
            for (std::string line; std::getline(cf, line);) {
                std::istringstream ls(line);
                std::string o, e, yw, pt, fv, ww, hh;
                if (!(ls >> o >> e >> yw >> pt >> fv >> ww >> hh)) continue;
                Args ca = a;
                ca["eye"] = e, ca["yaw"] = yw, ca["pitch"] = pt, ca["fov"] = fv, ca["w"] = ww, ca["h"] = hh;
                frame(ca, o);
                n++;
            }
            std::fprintf(stderr, "atlas_lab: %d camera frames\n", n);
        } else {
            frame(a, out);
        }
        return 0;
    }

    if (num(a, "motion", 0) > 0) {
        // the moving camera with a real TAA: at each distance, `mframes` frames of one sample each while the camera
        // moves `speed` blocks a frame towards the object and `strafe` sideways (what uncovers hidden parts and breaks
        // history); each frame's history is the last output reprojected through the camera's motion (by each pixel's
        // hit distance), clamped to the current 3 x 3 neighbourhood, blended 10 % new: what a real TAA keeps and
        // throws away. Against ref converged at every frame: dE (what you see while moving) and flicker (frame to
        // frame change beyond ref's own), over the last two thirds of the move (the history has filled)
        int half = int(num(a, "window", 96)), W = 2 * half, mframes = int(num(a, "mframes", 48));
        float speed = float(num(a, "speed", 0.5)), strafe = float(num(a, "strafe", 0.3)), blend = float(num(a, "blend", 0.1));
        int size = int(num(a, "panel", 160));
        int sheetW = int(dists.size()) * size, sheetH = int(variants.size()) * size;
        std::vector<uint32_t> sheet(size_t(sheetW) * sheetH, 0xFF202020u);
        std::string json = "{\"tool\":\"atlas_lab\",\"mode\":\"motion\",\"runs\":[";
        std::printf("%-8s %-8s %8s %8s %7s\n", "dist", "variant", "motiondE", "flicker", "steps");
        bool firstRun = true;
        for (size_t di = 0; di < dists.size(); di++) {
            float D0 = dists[di];
            std::vector<View> refs;
            std::vector<V3> eyes;
            for (int k = 0; k < mframes; k++) {
                float Dk = D0 - speed * k;
                eyes.push_back(centre - fwd * Dk + right * (strafe * k));
                refs.push_back(render(Dk, "ref", half, 0, &eyes.back(), nullptr, 2));
            }
            for (size_t vi = 0; vi < variants.size(); vi++) {
                const std::string& v = variants[vi];
                Image hist{W, W, std::vector<RGB>(size_t(W) * W), std::vector<float>(size_t(W) * W, 0)};
                Image prevOut;
                std::vector<float> prevErr;  // per pixel the last frame's dE against its ref
                bool have = false;
                double de = 0, flick = 0, stepsSum = 0;
                int counted = 0;
                for (int k = 0; k < mframes; k++) {
                    float Dk = D0 - speed * k;
                    std::vector<float> tb;
                    View cur = render(Dk, v, half, k, &eyes[size_t(k)], &tb, 1);
                    stepsSum += cur.steps;
                    Image outI = cur.img;
                    if (have) {
                        V3 pe = eyes[size_t(k - 1)];
                        for (int py = 0; py < W; py++)
                            for (int px = 0; px < W; px++) {
                                size_t i = size_t(px + W * py);
                                float hx = float(px), hy = float(py);
                                if (tb[i] >= 0) {  // the point this pixel sees, where the previous camera saw it
                                    V3 dir = norm(fwd + right * ((px - half + 0.5f) * pa) - up * ((py - half + 0.5f) * pa));
                                    V3 P = eyes[size_t(k)] + dir * tb[i], q = P - pe;
                                    float zf = dot(q, fwd);
                                    if (zf <= 0) continue;
                                    hx = dot(q, right) / zf / pa + half - 0.5f;
                                    hy = -dot(q, up) / zf / pa + half - 0.5f;
                                }
                                if (hx < 0 || hy < 0 || hx > W - 1 || hy > W - 1) continue;  // no history: this frame alone
                                int x0 = int(hx), y0 = int(hy), x1 = std::min(x0 + 1, W - 1), y1 = std::min(y0 + 1, W - 1);
                                float fx = hx - x0, fy = hy - y0;
                                auto H = [&](int x, int y) { return hist.c[size_t(x + W * y)]; };
                                RGB h00 = H(x0, y0), h10 = H(x1, y0), h01 = H(x0, y1), h11 = H(x1, y1);
                                RGB h{(h00.r * (1 - fx) + h10.r * fx) * (1 - fy) + (h01.r * (1 - fx) + h11.r * fx) * fy,
                                      (h00.g * (1 - fx) + h10.g * fx) * (1 - fy) + (h01.g * (1 - fx) + h11.g * fx) * fy,
                                      (h00.b * (1 - fx) + h10.b * fx) * (1 - fy) + (h01.b * (1 - fx) + h11.b * fx) * fy};
                                RGB lo{1e9f, 1e9f, 1e9f}, hi{-1e9f, -1e9f, -1e9f};
                                for (int dy = -1; dy <= 1; dy++)
                                    for (int dx = -1; dx <= 1; dx++) {
                                        int nx = std::clamp(px + dx, 0, W - 1), ny = std::clamp(py + dy, 0, W - 1);
                                        const RGB& c = cur.img.c[size_t(nx + W * ny)];
                                        lo = {std::min(lo.r, c.r), std::min(lo.g, c.g), std::min(lo.b, c.b)};
                                        hi = {std::max(hi.r, c.r), std::max(hi.g, c.g), std::max(hi.b, c.b)};
                                    }
                                h = {std::clamp(h.r, lo.r, hi.r), std::clamp(h.g, lo.g, hi.g), std::clamp(h.b, lo.b, hi.b)};
                                const RGB& c = cur.img.c[i];
                                outI.c[i] = {h.r + blend * (c.r - h.r), h.g + blend * (c.g - h.g), h.b + blend * (c.b - h.b)};
                                outI.a[i] = std::max(cur.img.a[i], refs[size_t(k)].img.a[i]);
                            }
                    }
                    // flicker: how much each pixel's error changes from frame to frame (a steady blur is calm, shimmer
                    // and crawling edges are not), over the pixels showing the object
                    std::vector<float> err(size_t(W) * W, -1.f);
                    for (size_t i = 0; i < err.size(); i++)
                        if (std::max(outI.a[i], refs[size_t(k)].img.a[i]) > 0.01f) err[i] = deltaE(outI.c[i], refs[size_t(k)].img.c[i]);
                    if (k >= mframes / 3) {
                        de += diff(outI, refs[size_t(k)].img);
                        double ch = 0;
                        int n2 = 0;
                        for (size_t i = 0; i < err.size(); i++)
                            if (err[i] >= 0 && !prevErr.empty() && prevErr[i] >= 0) ch += std::fabs(err[i] - prevErr[i]), n2++;
                        flick += n2 ? ch / n2 : 0;
                        counted++;
                    }
                    prevErr = err;
                    hist = outI;
                    prevOut = outI;
                    have = true;
                }
                de /= std::max(1, counted);
                flick /= std::max(1, counted);
                std::printf("%-8.0f %-8s %8.2f %8.2f %7.1f\n", D0, v.c_str(), de, flick, stepsSum / mframes);
                char buf[200];
                std::snprintf(buf, sizeof buf, "%s{\"dist\":%.0f,\"variant\":\"%s\",\"motion_dE\":%.3f,\"flicker\":%.3f,\"steps\":%.2f}",
                              firstRun ? "" : ",", D0, v.c_str(), de, flick, stepsSum / mframes);
                json += buf;
                firstRun = false;
                blit(sheet, sheetW, prevOut, int(di) * size, int(vi) * size, size);
            }
        }
        atlas::writePng(out, sheet.data(), uint32_t(sheetW), uint32_t(sheetH));
        json += "]}";
        std::printf("%s\n", json.c_str());
        return 0;
    }

    int sweep = int(num(a, "sweep", 0));
    if (sweep >= 2) {
        // the fly-in: the camera from the farthest to the nearest distance in `sweep` steps (geometric), one fixed
        // window; pop = how much more a representation's picture changes from step to step than ref's does, beyond
        // its own noise (a second render with other seeds: what TAA leaves) - what the eye sees as popping: geometry
        // or colour jumping as levels or groupings change
        float far = *std::max_element(dists.begin(), dists.end()), near = *std::min_element(dists.begin(), dists.end());
        int half = int(num(a, "window", 96));
        int shown = std::min(sweep, 10), size = int(num(a, "panel", 128));
        int sheetW = shown * size, sheetH = int(variants.size()) * size;
        std::vector<uint32_t> sheet(size_t(sheetW) * sheetH, 0xFF202020u);
        std::vector<View> refViews;
        std::string json = "{\"tool\":\"atlas_lab\",\"mode\":\"sweep\",\"steps\":" + std::to_string(sweep) + ",\"runs\":[";
        std::printf("%-8s %7s %7s %7s %7s %7s\n", "variant", "pop", "popMax", "dE", "steps", "boxes");
        for (size_t vi = 0; vi < variants.size(); vi++) {
            const std::string& v = variants[vi];
            double pop = 0, popMax = 0, de = 0, stepsSum = 0;
            long long boxesMax = -1;
            View prev;
            for (int k = 0; k < sweep; k++) {
                float D = far * std::pow(near / far, float(k) / float(sweep - 1));
                View cur = render(D, v, half);
                double noise = diff(cur.img, render(D, v, half, 1000).img);
                if (v == "ref") refViews.push_back(cur);
                const View& r = refViews[size_t(k)];
                de += diff(cur.img, r.img);
                stepsSum += cur.steps;
                boxesMax = std::max(boxesMax, cur.drawn);
                if (k > 0) {
                    double change = diff(cur.img, prev.img), refChange = diff(r.img, refViews[size_t(k - 1)].img);
                    double excess = std::max(0.0, change - refChange - noise);
                    pop += excess / (sweep - 1);
                    popMax = std::max(popMax, excess);
                }
                int col = k * shown / sweep;
                if ((k * shown) % sweep < shown) blit(sheet, sheetW, cur.img, col * size, int(vi) * size, size);
                prev = std::move(cur);
            }
            de /= sweep;
            std::printf("%-8s %7.2f %7.2f %7.2f %7.1f %7lld\n", v.c_str(), pop, popMax, de, stepsSum / sweep, boxesMax);
            char buf[256];
            std::snprintf(buf, sizeof buf, "%s{\"variant\":\"%s\",\"pop\":%.3f,\"pop_max\":%.3f,\"dE\":%.3f,\"steps\":%.2f,\"boxes_max\":%lld}",
                          vi ? "," : "", v.c_str(), pop, popMax, de, stepsSum / sweep, boxesMax);
            json += buf;
        }
        atlas::writePng(out, sheet.data(), uint32_t(sheetW), uint32_t(sheetH));
        json += "],\"rows\":\"";
        for (size_t c = 0; c < variants.size(); c++) json += (c ? "," : "") + variants[c];
        json += "\"}";
        std::printf("%s\n", json.c_str());
        return 0;
    }

    int cols = int(variants.size()), rows = int(dists.size());
    std::vector<uint32_t> sheet(size_t(cols * panel) * (rows * panel), 0xFF202020u);
    std::string json = "{\"tool\":\"atlas_lab\",\"build_ms\":" + std::to_string(int(buildMs)) + ",\"runs\":[";
    std::printf("%-8s %-8s %6s %7s %6s %6s %7s %8s %7s\n", "dist", "variant", "level", "iou", "dE", "dE1", "steps", "ns/ray", "boxes");
    bool firstRun = true;
    for (int r = 0; r < rows; r++) {
        float D = dists[size_t(r)];
        int half = std::clamp(int(std::ceil(radius / (D * pa) * 1.15f)), 6, 128);  // a window of true pixels
        View ref;
        for (int vi = 0; vi < cols; vi++) {
            const std::string& v = variants[size_t(vi)];
            View cur = render(D, v, half);
            if (v == "ref") ref = cur;
            const Image& img = cur.img;
            double inter = 0, uni = 0, de = 0, de1 = 0;
            int nde = 0;
            for (size_t i = 0; i < img.a.size(); i++) {
                inter += std::min(img.a[i], ref.img.a[i]);
                uni += std::max(img.a[i], ref.img.a[i]);
                if (std::max(img.a[i], ref.img.a[i]) > 0.01f) {
                    de += deltaE(img.c[i], ref.img.c[i]);
                    de1 += deltaE(cur.first[i], ref.img.c[i]);
                    nde++;
                }
            }
            double iou = uni > 0 ? inter / uni : 1, mde = nde ? de / nde : 0, mde1 = nde ? de1 / nde : 0;
            std::printf("%-8.0f %-8s %6.2f %7.3f %6.2f %6.2f %7.1f %8.0f %7lld\n", D, v.c_str(), cur.level, iou, mde, mde1, cur.steps, cur.ns, cur.drawn);
            char buf[320];
            std::snprintf(buf, sizeof buf,
                          "%s{\"dist\":%.0f,\"variant\":\"%s\",\"level_blocks\":%.2f,\"pixels\":%d,\"iou\":%.4f,\"dE\":%.3f,\"dE1\":%.3f,"
                          "\"steps\":%.1f,\"ns_per_ray\":%.0f,\"boxes\":%lld}",
                          firstRun ? "" : ",", D, v.c_str(), cur.level, img.w, iou, mde, mde1, cur.steps, cur.ns, cur.drawn);
            json += buf;
            firstRun = false;
            blit(sheet, cols * panel, img, vi * panel, r * panel, panel);
        }
    }
    atlas::writePng(out, sheet.data(), uint32_t(cols * panel), uint32_t(rows * panel));
    json += "],\"columns\":\"";
    for (int c = 0; c < cols; c++) json += (c ? "," : "") + variants[size_t(c)];
    json += "\",\"rows\":\"";
    for (int r = 0; r < rows; r++) json += (r ? "," : "") + std::to_string(int(dists[size_t(r)]));
    json += "\"}";
    std::printf("%s\n", json.c_str());
    return 0;
}
