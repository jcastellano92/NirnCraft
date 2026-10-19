// atlas_lab: the far-field experiment bed (docs/ATLAS-LOD.md, "Experiments"). One real object (a box of block state ids
// exported in game with /nirncraft-atlaslab) rendered at several distances by each candidate representation, against a
// converged reference, at the game's true pixel size (1080p, fov 70). Quick: a CPU tracer, seconds per run.
//
//   atlas_lab blocks=<dir>/blocks.bin volume=<dir>/tree.alv out=sheet.png [dist=10,50,200,600,1500]
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

namespace {

using Args = std::map<std::string, std::string>;

Args parse(int argc, char** argv) {
    Args a;
    for (int i = 1; i < argc; i++) {
        std::string s = argv[i];
        auto eq = s.find('=');
        if (eq != std::string::npos) a[s.substr(0, eq)] = s.substr(eq + 1);
    }
    return a;
}
double num(const Args& a, const char* k, double d) {
    auto it = a.find(k);
    return it == a.end() ? d : std::atof(it->second.c_str());
}
std::string str(const Args& a, const char* k, const std::string& d) {
    auto it = a.find(k);
    return it == a.end() ? d : it->second;
}
std::vector<std::string> split(const std::string& s) {
    std::vector<std::string> out;
    std::stringstream ss(s);
    for (std::string t; std::getline(ss, t, ',');)
        if (!t.empty()) out.push_back(t);
    return out;
}

struct V3 {
    float x = 0, y = 0, z = 0;
    float operator[](int i) const { return i == 0 ? x : i == 1 ? y : z; }
};
V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V3 operator*(V3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
float dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
V3 norm(V3 a) { return a * (1.0f / std::sqrt(dot(a, a))); }

struct RGB {
    float r = 0, g = 0, b = 0;
};
RGB unpack(uint32_t c) { return {float((c >> 16) & 255), float((c >> 8) & 255), float(c & 255)}; }

inline uint32_t hash(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}
inline float hashf(int x, int y, int z, uint32_t s) {
    uint32_t h = hash(uint32_t(x) * 73856093u ^ hash(uint32_t(y) * 19349663u ^ hash(uint32_t(z) * 83492791u ^ s)));
    return float(h >> 8) * (1.0f / 16777216.0f);
}

// ---- the block-state library (AtlasLabExport blocks.bin) ------------------------------------------------------------

enum Face { DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5 };

struct Block {
    bool present = false, kept = false, any = false;
    uint8_t cls = 0, emit = 0, flags = 0, tint = 0;
    uint64_t mask = 0;
    RGB mean;
    uint32_t face[6][16] = {};
    float alpha[64] = {};  // per micro-voxel: how opaque its texels are (leaves' cutout); 1 for plain blocks
    std::string name;
};
std::vector<Block> lib;
RGB tintGrass{0x91, 0xBD, 0x59}, tintFoliage{0x77, 0xAB, 0x2F};

// the texel of face f over micro-voxel (x, y, z) of a block (as AtlasLabExport lays the tiles out)
inline int texel(int f, int x, int y, int z) {
    if (f <= UP) return x + 4 * z;
    if (f <= SOUTH) return x + 4 * (3 - y);
    return z + 4 * (3 - y);
}

RGB tinted(const Block& b, RGB c) {
    RGB t = b.tint == 1 ? tintGrass : b.tint == 2 ? tintFoliage : b.tint == 3 ? RGB{0x3F, 0x76, 0xE4} : RGB{255, 255, 255};
    return {c.r * t.r / 255.f, c.g * t.g / 255.f, c.b * t.b / 255.f};
}

bool loadBlocks(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    size_t p = 0;
    auto u8 = [&]() { return d[p++]; };
    auto u16 = [&]() { uint16_t v; std::memcpy(&v, &d[p], 2); p += 2; return v; };
    auto u32 = [&]() { uint32_t v; std::memcpy(&v, &d[p], 4); p += 4; return v; };
    auto u64 = [&]() { uint64_t v; std::memcpy(&v, &d[p], 8); p += 8; return v; };
    if (d.size() < 8 || std::memcmp(d.data(), "ALB1", 4) != 0) return false;
    p = 4;
    uint32_t n = u32();
    for (uint32_t i = 0; i < n; i++) {
        uint32_t id = u32();
        uint16_t nl = u16();
        std::string name(reinterpret_cast<const char*>(&d[p]), nl);
        p += nl;
        if (id >= lib.size()) lib.resize(id + 1);
        Block& b = lib[id];
        b.present = true;
        b.name = name;
        b.cls = u8();
        b.emit = u8();
        b.flags = u8();
        b.tint = u8();
        b.mask = u64();
        b.mean = unpack(u32());
        for (int f2 = 0; f2 < 6; f2++)
            for (int t = 0; t < 16; t++) b.face[f2][t] = u32();
        bool water = b.cls == 2 && !(b.flags & 4);
        if (water) b.mask = ~0ull;  // fluids have no block model: a full water voxel
        if (water || (b.mean.r == 0 && b.mean.g == 0 && b.mean.b == 0 && b.mask)) b.mean = water ? RGB{0x3F, 0x76, 0xE4} : RGB{128, 128, 128};
        bool content[6];
        for (int f2 = 0; f2 < 6; f2++) {
            content[f2] = false;
            for (int t = 0; t < 16; t++) content[f2] |= (b.face[f2][t] >> 24) != 0;
        }
        for (int m = 0; m < 64; m++) {
            int x = m & 3, z = (m >> 2) & 3, y = m >> 4;
            float s = 0;
            int k = 0;
            for (int f2 = 0; f2 < 6; f2++)
                if (content[f2]) {
                    s += float(b.face[f2][texel(f2, x, y, z)] >> 24) / 255.f;
                    k++;
                }
            b.alpha[m] = k ? s / k : 1.f;
        }
        bool air = name.rfind("Block{minecraft:air}", 0) == 0 || name.rfind("Block{minecraft:cave_air}", 0) == 0 ||
                   name.rfind("Block{minecraft:void_air}", 0) == 0;
        b.any = !air && (b.mask != 0 || (b.flags & 4));
        // today's far field keeps (AtlasSections.kept): solids, leaves, fluids, lights, slabs, stairs
        b.kept = !air && (b.cls <= 3 || name.find("_slab") != std::string::npos || name.find("_stairs") != std::string::npos);
        if (!b.mask) b.kept = false;
    }
    return true;
}

// ---- the volume (AtlasLabExport *.alv) ------------------------------------------------------------------------------

struct Volume {
    int x0 = 0, y0 = 0, z0 = 0, sx = 0, sy = 0, sz = 0;
    std::vector<uint32_t> ids;
    const Block* at(int x, int y, int z) const {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return nullptr;
        uint32_t id = ids[size_t(x + sx * (z + sz * y))];
        return id < lib.size() && lib[id].present ? &lib[id] : nullptr;
    }
} vol;

bool loadVolume(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    char magic[4];
    f.read(magic, 4);
    if (std::memcmp(magic, "ALV1", 4) != 0) return false;
    int32_t h[6];
    f.read(reinterpret_cast<char*>(h), sizeof h);
    vol.x0 = h[0], vol.y0 = h[1], vol.z0 = h[2], vol.sx = h[3], vol.sy = h[4], vol.sz = h[5];
    vol.ids.resize(size_t(vol.sx) * vol.sy * vol.sz);
    f.read(reinterpret_cast<char*>(vol.ids.data()), std::streamsize(vol.ids.size() * 4));
    return bool(f);
}

// micro-voxels: 4 per block side
inline bool microAt(int mx, int my, int mz) {
    const Block* b = vol.at(mx >> 2, my >> 2, mz >> 2);
    if (!b || !b->any) return false;
    int bit = (mx & 3) + 4 * ((mz & 3) + 4 * (my & 3));
    if (!((b->mask >> bit) & 1)) return false;
    float a = b->alpha[bit];
    return a >= 0.999f || hashf(mx, my, mz, 0x5EEDu) < a;
}
inline RGB microCol(int mx, int my, int mz, int f) {
    const Block* b = vol.at(mx >> 2, my >> 2, mz >> 2);
    uint32_t c = b->face[f][texel(f, mx & 3, my & 3, mz & 3)];
    return tinted(*b, (c >> 24) ? unpack(c) : b->mean);
}

// ---- traversal ------------------------------------------------------------------------------------------------------

const int FACE_LOW[3] = {WEST, DOWN, NORTH}, FACE_HIGH[3] = {EAST, UP, SOUTH};
const V3 NORMALS[6] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
V3 SUN = norm({0.45f, 0.8f, 0.35f});

inline int enteredFace(int axis, const V3& d) { return d[axis] > 0 ? FACE_LOW[axis] : FACE_HIGH[axis]; }

RGB lit(RGB c, int face) {
    float l = 0.42f + 0.58f * std::max(0.f, dot(NORMALS[face], SUN));
    return {c.r * l, c.g * l, c.b * l};
}

// Amanatides-Woo over a grid of n cells of size cs from the box's origin; visit(ix, iy, iz, axis) -> true stops.
template <class F>
int dda(V3 o, V3 d, float cs, int nx, int ny, int nz, F&& visit) {
    float lo[3] = {0, 0, 0}, hi[3] = {nx * cs, ny * cs, nz * cs};
    float t0 = 0, t1 = 1e30f;
    int axis = -1;
    for (int a = 0; a < 3; a++) {
        float da = d[a], oa = o[a];
        if (std::fabs(da) < 1e-12f) {
            if (oa < lo[a] || oa > hi[a]) return 0;
            continue;
        }
        float ta = (lo[a] - oa) / da, tb = (hi[a] - oa) / da;
        if (ta > tb) std::swap(ta, tb);
        if (ta > t0) {
            t0 = ta;
            axis = a;
        }
        t1 = std::min(t1, tb);
    }
    if (t0 > t1) return 0;
    if (axis < 0) axis = std::fabs(d.x) > std::fabs(d.y) ? (std::fabs(d.x) > std::fabs(d.z) ? 0 : 2) : (std::fabs(d.y) > std::fabs(d.z) ? 1 : 2);
    V3 p = o + d * (t0 + 1e-5f * cs);
    int n[3] = {nx, ny, nz}, c[3], step[3];
    float tNext[3], tDelta[3];
    for (int a = 0; a < 3; a++) {
        c[a] = std::clamp(int(std::floor(p[a] / cs)), 0, n[a] - 1);
        step[a] = d[a] > 0 ? 1 : -1;
        tDelta[a] = std::fabs(d[a]) < 1e-12f ? 1e30f : cs / std::fabs(d[a]);
        float edge = (c[a] + (d[a] > 0 ? 1 : 0)) * cs;
        tNext[a] = std::fabs(d[a]) < 1e-12f ? 1e30f : (edge - o[a]) / d[a];
    }
    int steps = 0;
    for (;;) {
        steps++;
        if (visit(c[0], c[1], c[2], axis)) return steps;
        int a = tNext[0] < tNext[1] ? (tNext[0] < tNext[2] ? 0 : 2) : (tNext[1] < tNext[2] ? 1 : 2);
        c[a] += step[a];
        if (c[a] < 0 || c[a] >= n[a]) return steps;
        tNext[a] += tDelta[a];
        axis = a;
    }
}

struct Sample {
    bool hit = false;
    RGB c;
    int steps = 0;
};

// micro-voxels inside the non-air blocks (ref, micro, and the cov chain's level 0)
Sample traceMicro(V3 o, V3 d) {
    Sample s;
    s.steps = dda(o, d, 1.f, vol.sx, vol.sy, vol.sz, [&](int bx, int by, int bz, int) {
        const Block* b = vol.at(bx, by, bz);
        if (!b || !b->any || !b->mask) return false;
        V3 lo{float(bx), float(by), float(bz)};
        s.steps += dda(o - lo, d, 0.25f, 4, 4, 4, [&](int x, int y, int z, int axis) {
            int mx = bx * 4 + x, my = by * 4 + y, mz = bz * 4 + z;
            if (!microAt(mx, my, mz)) return false;
            int f = enteredFace(axis, d);
            s.hit = true;
            s.c = lit(microCol(mx, my, mz, f), f);
            return true;
        });
        return s.hit;
    });
    return s;
}

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
        s.c = cc;
        return true;
    });
    return s;
}

// ---- adaptive boxes (boxes) -----------------------------------------------------------------------------------------
// An octree over the micro-voxels where every node keeps the tight box of its content (a rectangle, not its cube: a
// wall's node is a thin slab) and its flattening error: how deep under its box's faces the first content lies, seen
// along each axis. A ray draws a node as one textured box when that error is under the pixel's footprint there (times
// tol, divided by the fog's transmittance: what fog hides may be rougher), else descends into its children; a box's
// face is a masked texture: the first content of the column under the point the ray enters (none: a hole, the ray
// tries the face it leaves by, then goes on). Big flat things (a ground plane, a wall) are one box at any distance;
// a crown splits until its leaves are under a pixel. The threshold is dithered per pixel and frame (TAA blends it).

struct Node {
    int16_t lo[3], hi[3];  // content box, micro-voxels, [lo, hi)
    float err;             // micro-voxels
    int child;             // first of 8 (an empty one has lo >= hi), -1 = a single micro-voxel
};
std::vector<Node> nodes;

// the first content of the column at (u, v) of face (axis, high side) of box [lo, hi): its depth and position
bool firstHit(const int lo[3], const int hi[3], int axis, bool high, int u, int v, int& depth, int p[3]) {
    int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
    int n = hi[axis] - lo[axis];
    for (int t = 0; t < n; t++) {
        p[axis] = high ? hi[axis] - 1 - t : lo[axis] + t;
        p[a1] = u, p[a2] = v;
        if (microAt(p[0], p[1], p[2])) {
            depth = t;
            return true;
        }
    }
    return false;
}

Node buildNode(int x, int y, int z, int size) {
    Node nd{{0, 0, 0}, {0, 0, 0}, 0.f, -1};
    // empty space: no non-air block under the cube
    bool any = false;
    for (int by = y >> 2; by <= (y + size - 1) >> 2 && !any; by++)
        for (int bz = z >> 2; bz <= (z + size - 1) >> 2 && !any; bz++)
            for (int bx = x >> 2; bx <= (x + size - 1) >> 2 && !any; bx++) {
                const Block* b = vol.at(bx, by, bz);
                any = b && b->any && b->mask;
            }
    if (!any) return nd;
    if (size == 1) {
        if (microAt(x, y, z))
            for (int a = 0; a < 3; a++) {
                int p = a == 0 ? x : a == 1 ? y : z;
                nd.lo[a] = int16_t(p), nd.hi[a] = int16_t(p + 1);
            }
        return nd;
    }
    int first = int(nodes.size());
    nodes.resize(nodes.size() + 8);
    int h = size / 2;
    int lo[3] = {1 << 15, 1 << 15, 1 << 15}, hi[3] = {-1, -1, -1};
    for (int k = 0; k < 8; k++) {
        Node c = buildNode(x + (k & 1) * h, y + (k >> 2) * h, z + ((k >> 1) & 1) * h, h);
        nodes[size_t(first + k)] = c;
        if (c.lo[0] < c.hi[0])
            for (int a = 0; a < 3; a++) lo[a] = std::min(lo[a], int(c.lo[a])), hi[a] = std::max(hi[a], int(c.hi[a]));
    }
    if (hi[0] < 0) {
        nodes.resize(size_t(first));
        return nd;
    }
    for (int a = 0; a < 3; a++) nd.lo[a] = int16_t(lo[a]), nd.hi[a] = int16_t(hi[a]);
    nd.child = first;
    float err = 0;
    for (int a = 0; a < 3; a++) {
        int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
        for (int u = lo[a1]; u < hi[a1]; u++)
            for (int v = lo[a2]; v < hi[a2]; v++)
                for (int side = 0; side < 2; side++) {
                    int d, p[3];
                    if (firstHit(lo, hi, a, side == 1, u, v, d, p)) err = std::max(err, float(d));
                }
    }
    nd.err = err;
    return nd;
}

int rootNode = -1;

void buildBoxes() {
    int n = 1;
    while (n < std::max({vol.sx, vol.sy, vol.sz}) * 4) n *= 2;
    nodes.reserve(1 << 20);
    nodes.push_back({});
    Node r = buildNode(0, 0, 0, n);
    nodes[0] = r;
    rootNode = r.lo[0] < r.hi[0] ? 0 : -1;
}

bool rayBox(V3 o, V3 d, const int16_t lo[3], const int16_t hi[3], float& t0, float& t1, int& axis) {
    t0 = -1e30f, t1 = 1e30f;
    axis = 0;
    for (int a = 0; a < 3; a++) {
        float l = lo[a] * 0.25f, h = hi[a] * 0.25f;
        if (std::fabs(d[a]) < 1e-12f) {
            if (o[a] < l || o[a] > h) return false;
            continue;
        }
        float ta = (l - o[a]) / d[a], tb = (h - o[a]) / d[a];
        if (ta > tb) std::swap(ta, tb);
        if (ta > t0) t0 = ta, axis = a;
        t1 = std::min(t1, tb);
    }
    return t0 <= t1 && t1 > 0;
}

std::vector<std::atomic<uint8_t>>* drawnBoxes = nullptr;  // which boxes a frame drew (the cut), when counting

struct BoxCtx {
    V3 o, d;
    float pa, tol;  // pixel size (tangent plane), threshold factor (dithered, fog-scaled)
    Sample* s;
    bool prefilter = false;  // face textures averaged over the pixel's footprint (their mip), holes as coverage
    uint32_t seed = 0;
};

// the masked texture of face (axis, side) at the ray's point p on it (t along the ray). Prefiltered: the texel is the
// average of what lies under the pixel's footprint on the face (the texture's mip at that distance), its holes a
// coverage the ray passes in proportion; so a pixel becomes 4 texels becomes a window as the camera comes closer,
// with no level where the picture jumps.
bool boxFace(const Node& n, int axis, bool high, V3 p, float t, BoxCtx& c) {
    int lo[3] = {n.lo[0], n.lo[1], n.lo[2]}, hi[3] = {n.hi[0], n.hi[1], n.hi[2]};
    int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
    int f = high ? FACE_HIGH[axis] : FACE_LOW[axis];
    c.s->steps++;  // a texture fetch
    float fp = t * c.pa * 4.f;  // the footprint, micro-voxels
    if (c.prefilter && fp > 1.f) {
        int k = std::min(int(std::ceil(fp)), 6), hits = 0;
        RGB acc{};
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) {
                int u = int(std::floor(p[a1] * 4.f + ((i + 0.5f) / k - 0.5f) * fp)), v = int(std::floor(p[a2] * 4.f + ((j + 0.5f) / k - 0.5f) * fp));
                if (u < lo[a1] || u >= hi[a1] || v < lo[a2] || v >= hi[a2]) continue;
                int dep, q[3];
                if (!firstHit(lo, hi, axis, high, u, v, dep, q)) continue;
                RGB m = microCol(q[0], q[1], q[2], f);
                acc.r += m.r, acc.g += m.g, acc.b += m.b;
                hits++;
            }
        if (!hits || hashf(int(p[a1] * 64.f), int(p[a2] * 64.f), axis, c.seed) >= float(hits) / float(k * k)) return false;
        c.s->hit = true;
        c.s->c = lit({acc.r / hits, acc.g / hits, acc.b / hits}, f);
        return true;
    }
    int u = std::clamp(int(std::floor(p[a1] * 4.f)), lo[a1], hi[a1] - 1), v = std::clamp(int(std::floor(p[a2] * 4.f)), lo[a2], hi[a2] - 1);
    int dep, q[3];
    if (!firstHit(lo, hi, axis, high, u, v, dep, q)) return false;
    c.s->hit = true;
    c.s->c = lit(microCol(q[0], q[1], q[2], f), f);
    return true;
}

bool traceNode(int idx, BoxCtx& c) {
    const Node& n = nodes[size_t(idx)];
    float t0, t1;
    int axis;
    if (!rayBox(c.o, c.d, n.lo, n.hi, t0, t1, axis)) return false;
    c.s->steps++;
    float t = std::max(t0, 0.f);
    bool asBox = n.child < 0 || n.err * 0.25f <= c.tol * t * c.pa;
    if (asBox) {
        if (drawnBoxes) (*drawnBoxes)[size_t(idx)].store(1, std::memory_order_relaxed);
        V3 pin = c.o + c.d * t;
        if (boxFace(n, axis, c.d[axis] < 0, pin, t, c)) return true;
        // a hole: the face the ray leaves by, seen from outside
        int ax2 = 0;
        float best = 1e30f;
        for (int a = 0; a < 3; a++) {
            if (std::fabs(c.d[a]) < 1e-12f) continue;
            float e = ((c.d[a] > 0 ? n.hi[a] : n.lo[a]) * 0.25f - c.o[a]) / c.d[a];
            if (e < best) best = e, ax2 = a;
        }
        return boxFace(n, ax2, c.d[ax2] > 0, c.o + c.d * best, best, c);
    }
    std::pair<float, int> order[8];
    int k = 0;
    for (int i = 0; i < 8; i++) {
        const Node& ch = nodes[size_t(n.child + i)];
        if (ch.lo[0] >= ch.hi[0]) continue;
        float a0, a1v;
        int ax;
        if (rayBox(c.o, c.d, ch.lo, ch.hi, a0, a1v, ax)) order[k++] = {a0, n.child + i};
    }
    std::sort(order, order + k);
    for (int i = 0; i < k; i++)
        if (traceNode(order[i].second, c)) return true;
    return false;
}

Sample traceBoxes(V3 o, V3 d, float pa, float tol, bool prefilter = false, uint32_t seed = 0) {
    Sample s;
    if (rootNode < 0) return s;
    BoxCtx c{o, d, pa, tol, &s, prefilter, seed};
    traceNode(rootNode, c);
    return s;
}

// ---- content-shaped groups (groups) ---------------------------------------------------------------------------------
// As boxes, but the hierarchy follows the content instead of a fixed grid: every group splits in two at the plane (any
// axis, any micro-voxel position) whose two children's tight boxes have the least surface times content (the SAH): a wall
// comes away from the tree beside it whole, gaps are cut along, and nothing is chopped where a grid line happens to
// fall. Groups ignore objects and chunks: far away one group is a stand of trees and the ground under it, up close
// it is a leaf cluster. (A GPU builds the same kind of hierarchy in milliseconds: LBVH from Morton codes.)

struct Group {
    int16_t lo[3], hi[3];
    float err;
    int left = -1, right = -1;  // -1: a leaf (a single micro-voxel, or a box with nothing under its faces to split)
};
std::vector<Group> groups;
int rootGroup = -1;

float boxArea(const int lo[3], const int hi[3]) {
    if (hi[0] <= lo[0]) return 0;
    float dx = float(hi[0] - lo[0]), dy = float(hi[1] - lo[1]), dz = float(hi[2] - lo[2]);
    return dx * dy + dy * dz + dz * dx;
}

// the tight box of the content inside [lo, hi)
bool tightBox(const int lo[3], const int hi[3], int out0[3], int out1[3]) {
    out0[0] = out0[1] = out0[2] = 1 << 15;
    out1[0] = out1[1] = out1[2] = -1;
    for (int y = lo[1]; y < hi[1]; y++)
        for (int z = lo[2]; z < hi[2]; z++)
            for (int x = lo[0]; x < hi[0]; x++) {
                const Block* b = vol.at(x >> 2, y >> 2, z >> 2);
                if (!b || !b->any || !b->mask) {
                    x |= 3;  // the rest of this block's row is air too
                    continue;
                }
                if (!microAt(x, y, z)) continue;
                int p[3] = {x, y, z};
                for (int a = 0; a < 3; a++) out0[a] = std::min(out0[a], p[a]), out1[a] = std::max(out1[a], p[a] + 1);
            }
    return out1[0] >= 0;
}

float flatError(const int lo[3], const int hi[3]) {
    float err = 0;
    for (int a = 0; a < 3; a++) {
        int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
        for (int u = lo[a1]; u < hi[a1]; u++)
            for (int v = lo[a2]; v < hi[a2]; v++)
                for (int side = 0; side < 2; side++) {
                    int d, p[3];
                    if (firstHit(lo, hi, a, side == 1, u, v, d, p)) err = std::max(err, float(d));
                }
    }
    return err;
}

int buildGroup(const int lo[3], const int hi[3], float err) {
    int idx = int(groups.size());
    groups.push_back({});
    Group g;
    for (int a = 0; a < 3; a++) g.lo[a] = int16_t(lo[a]), g.hi[a] = int16_t(hi[a]);
    g.err = err;
    if (err <= 0 || (hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2]) <= 1) {  // flat: one box at every distance
        groups[size_t(idx)] = g;
        return idx;
    }
    // per axis, per slice: the content's bounds on the other two axes; prefix / suffix unions give each split's boxes
    float best = 1e30f;
    int bestAxis = -1, bestAt = 0;
    for (int a = 0; a < 3; a++) {
        int n = hi[a] - lo[a];
        if (n < 2) continue;
        const std::array<int, 6> none{1 << 15, 1 << 15, 1 << 15, -1, -1, -1};
        std::vector<std::array<int, 6>> slice(size_t(n), none);
        std::vector<long long> count(size_t(n), 0);  // micro-voxels per slice: the SAH's weights
        for (int y = lo[1]; y < hi[1]; y++)
            for (int z = lo[2]; z < hi[2]; z++)
                for (int x = lo[0]; x < hi[0]; x++) {
                    if (!microAt(x, y, z)) continue;
                    int p[3] = {x, y, z};
                    count[size_t(p[a] - lo[a])]++;
                    auto& s = slice[size_t(p[a] - lo[a])];
                    for (int k = 0; k < 3; k++) s[k] = std::min(s[k], p[k]), s[3 + k] = std::max(s[3 + k], p[k] + 1);
                }
        std::vector<std::array<int, 6>> pre(slice.size()), suf(slice.size());
        for (int i = 0; i < n; i++) {
            pre[size_t(i)] = slice[size_t(i)];
            if (i)
                for (int k = 0; k < 3; k++)
                    pre[size_t(i)][k] = std::min(pre[size_t(i)][k], pre[size_t(i - 1)][k]),
                    pre[size_t(i)][3 + k] = std::max(pre[size_t(i)][3 + k], pre[size_t(i - 1)][3 + k]);
        }
        for (int i = n - 1; i >= 0; i--) {
            suf[size_t(i)] = slice[size_t(i)];
            if (i < n - 1)
                for (int k = 0; k < 3; k++)
                    suf[size_t(i)][k] = std::min(suf[size_t(i)][k], suf[size_t(i + 1)][k]),
                    suf[size_t(i)][3 + k] = std::max(suf[size_t(i)][3 + k], suf[size_t(i + 1)][3 + k]);
        }
        std::vector<long long> cpre(size_t(n) + 1, 0);
        for (int i = 0; i < n; i++) cpre[size_t(i + 1)] = cpre[size_t(i)] + count[size_t(i)];
        for (int i = 1; i < n; i++) {  // split before slice i
            const auto& L = pre[size_t(i - 1)];
            const auto& R = suf[size_t(i)];
            if (L[3] < 0 || R[3] < 0) continue;
            // the SAH: each side's surface times how much it holds (surface alone peels thin slices off one side
            // at a time: a deep, unbalanced tree on a real town)
            float c = boxArea(&L[0], &L[3]) * float(cpre[size_t(i)]) + boxArea(&R[0], &R[3]) * float(cpre[size_t(n)] - cpre[size_t(i)]);
            if (c < best) best = c, bestAxis = a, bestAt = lo[a] + i;
        }
    }
    if (bestAxis < 0) {
        groups[size_t(idx)] = g;
        return idx;
    }
    int l0[3] = {lo[0], lo[1], lo[2]}, l1[3] = {hi[0], hi[1], hi[2]}, r0[3] = {lo[0], lo[1], lo[2]}, r1[3] = {hi[0], hi[1], hi[2]};
    l1[bestAxis] = bestAt;
    r0[bestAxis] = bestAt;
    int t0[3], t1[3];
    if (tightBox(l0, l1, t0, t1)) g.left = buildGroup(t0, t1, flatError(t0, t1));
    if (tightBox(r0, r1, t0, t1)) g.right = buildGroup(t0, t1, flatError(t0, t1));
    groups[size_t(idx)] = g;
    return idx;
}

void buildGroups() {
    int lo[3] = {0, 0, 0}, hi[3] = {vol.sx * 4, vol.sy * 4, vol.sz * 4}, t0[3], t1[3];
    groups.reserve(1 << 20);
    if (tightBox(lo, hi, t0, t1)) rootGroup = buildGroup(t0, t1, flatError(t0, t1));
}

bool traceGroup(int idx, BoxCtx& c) {
    const Group& g = groups[size_t(idx)];
    float t0, t1;
    int axis;
    if (!rayBox(c.o, c.d, g.lo, g.hi, t0, t1, axis)) return false;
    c.s->steps++;
    float t = std::max(t0, 0.f);
    bool leaf = g.left < 0 && g.right < 0;
    if (leaf || g.err * 0.25f <= c.tol * t * c.pa) {
        if (drawnBoxes) (*drawnBoxes)[size_t(idx)].store(1, std::memory_order_relaxed);
        Node n{{g.lo[0], g.lo[1], g.lo[2]}, {g.hi[0], g.hi[1], g.hi[2]}, g.err, -1};
        if (boxFace(n, axis, c.d[axis] < 0, c.o + c.d * t, t, c)) return true;
        int ax2 = 0;
        float best = 1e30f;
        for (int a = 0; a < 3; a++) {
            if (std::fabs(c.d[a]) < 1e-12f) continue;
            float e = ((c.d[a] > 0 ? g.hi[a] : g.lo[a]) * 0.25f - c.o[a]) / c.d[a];
            if (e < best) best = e, ax2 = a;
        }
        return boxFace(n, ax2, c.d[ax2] > 0, c.o + c.d * best, best, c);
    }
    int kids[2] = {g.left, g.right};
    float tk[2] = {1e30f, 1e30f};
    for (int i = 0; i < 2; i++) {
        if (kids[i] < 0) continue;
        float a0, a1v;
        int ax;
        if (!rayBox(c.o, c.d, groups[size_t(kids[i])].lo, groups[size_t(kids[i])].hi, a0, a1v, ax)) kids[i] = -1;
        else tk[i] = a0;
    }
    int first = tk[0] <= tk[1] ? 0 : 1;
    for (int i : {first, 1 - first})
        if (kids[i] >= 0 && traceGroup(kids[i], c)) return true;
    return false;
}

Sample traceGroups(V3 o, V3 d, float pa, float tol, bool prefilter = false, uint32_t seed = 0) {
    Sample s;
    if (rootGroup < 0) return s;
    BoxCtx c{o, d, pa, tol, &s, prefilter, seed};
    traceGroup(rootGroup, c);
    return s;
}

// ---- metrics --------------------------------------------------------------------------------------------------------

void lab(RGB c, float& L, float& A, float& B) {
    auto lin = [](float v) {
        v /= 255.f;
        return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
    };
    float r = lin(c.r), g = lin(c.g), b = lin(c.b);
    float X = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f, Y = 0.2126f * r + 0.7152f * g + 0.0722f * b,
          Z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f;
    auto f = [](float t) { return t > 0.008856f ? std::cbrt(t) : 7.787f * t + 16.f / 116.f; };
    L = 116.f * f(Y) - 16.f, A = 500.f * (f(X) - f(Y)), B = 200.f * (f(Y) - f(Z));
}
float deltaE(RGB a, RGB b) {
    float l1, a1, b1, l2, a2, b2;
    lab(a, l1, a1, b1);
    lab(b, l2, a2, b2);
    return std::sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2));
}

struct Image {
    int w = 0, h = 0;
    std::vector<RGB> c;
    std::vector<float> a;  // the share of samples that hit the object
};

const RGB SKY{0x9C, 0xC4, 0xEC};

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
    if (a.count("grass")) tintGrass = unpack(uint32_t(std::strtoul(a["grass"].c_str(), nullptr, 16)));
    if (a.count("foliage")) tintFoliage = unpack(uint32_t(std::strtoul(a["foliage"].c_str(), nullptr, 16)));
    int threads = int(num(a, "threads", std::max(1u, std::thread::hardware_concurrency() / 2)));
    int frames = int(num(a, "frames", 16)), panel = int(num(a, "panel", 192));
    float scale = float(num(a, "scale", 1)), bias = float(num(a, "bias", 0));
    float tolPix = float(num(a, "tol", 1)), fog = float(num(a, "fog", 0));  // fog: extinction per block
    std::vector<float> dists;
    for (auto& t : split(str(a, "dist", "10,50,200,600,1500"))) dists.push_back(float(std::atof(t.c_str())));
    std::vector<std::string> variants = split(str(a, "variants", "ref,current,anymip,micro,cov,covbox,boxes,groups"));
    if (variants.empty() || variants[0] != "ref") variants.insert(variants.begin(), "ref");

    // the object's bounds (non-air blocks)
    int bmin[3] = {1 << 30, 1 << 30, 1 << 30}, bmax[3] = {-1, -1, -1};
    for (int y = 0; y < vol.sy; y++)
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
    auto render = [&](float D, const std::string& v, int half, int offset = 0) {  // offset: other seeds and jitters
        View out;
        V3 eye = centre - fwd * D;
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
        for (int f = 0; f < frames; f++) {
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
                        img.c[i].r += c.r / frames, img.c[i].g += c.g / frames, img.c[i].b += c.b / frames;
                        img.a[i] += hit / spp / frames;
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
