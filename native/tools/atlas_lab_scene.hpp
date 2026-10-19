// atlas_lab's scene and its two finalist tracers (docs/ATLAS-LOD.md, "Experiments"): the block-state library, the
// exported volume, the micro-voxels, the reference tracer (ref, micro), adaptive boxes and content-shaped groups, and
// the colour metrics. One copy for atlas_lab (the experiment bed) and atlas_lab_gpu (their Slang port, which checks
// its images against these tracers); each tool is one translation unit, so everything here is file-local.
#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

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

// the visual hull of a block's six face tiles: micro-voxel (x, y, z) is in when each axis's pair of faces covers its
// projection (alpha at least a quarter: a 4/16 post centred on a texel boundary covers two texels by half)
uint64_t hull(const Block& b) {
    auto cov = [&](int f, int x, int y, int z) { return (b.face[f][texel(f, x, y, z)] >> 24) >= 64; };
    uint64_t m = 0;
    for (int y = 0; y < 4; y++)
        for (int z = 0; z < 4; z++)
            for (int x = 0; x < 4; x++)
                if ((cov(DOWN, x, y, z) || cov(UP, x, y, z)) && (cov(NORTH, x, y, z) || cov(SOUTH, x, y, z)) &&
                    (cov(WEST, x, y, z) || cov(EAST, x, y, z)))
                    m |= 1ull << (x + 4 * (z + 4 * y));
    return m;
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
        // thinner than a micro-voxel (a snow layer, a fence post, a torch, a chain; grass and flowers have no outline at
        // all): its outline fills none of the 4 x 4 x 4, so its shape is the visual hull of its face tiles, a voxel
        // filled where all three of its projections are covered (a snow layer's bottom layer, a post's core column)
        if (!b.mask && (b.flags & 4) && !water) b.mask = hull(b);
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
            // cutout coverage is for leaves and plants; a solid block's partly transparent tiles (grass's side overlay)
            // are drawn over its opaque base, not holes
            b.alpha[m] = (b.cls == 1 || b.cls == 4) && k ? s / k : 1.f;
        }
        bool air = name.rfind("Block{minecraft:air}", 0) == 0 || name.rfind("Block{minecraft:cave_air}", 0) == 0 ||
                   name.rfind("Block{minecraft:void_air}", 0) == 0;
        b.any = !air && (b.mask != 0 || (b.flags & 4));
        // today's far field keeps (AtlasSections.kept): solids, leaves, fluids, lights, slabs, stairs, snow layers, carpets
        b.kept = !air && (b.cls <= 3 || name.find("_slab") != std::string::npos || name.find("_stairs") != std::string::npos ||
                          name.rfind("Block{minecraft:snow}", 0) == 0 || name.find("_carpet}") != std::string::npos ||
                          name.find("_fence}") != std::string::npos || name.find("_fence_gate}") != std::string::npos ||
                          name.find("_wall}") != std::string::npos);
        if (!b.mask) b.kept = false;
    }
    return true;
}

// ---- the volume (AtlasLabExport *.alv) ------------------------------------------------------------------------------

// a sparse volume's section (AtlasLabExport view: *.als): its palette and, when it has more than one entry, indices
struct Sec {
    std::vector<uint32_t> pal;
    std::vector<uint16_t> idx;  // x + 16 * (z + 16 * y)
};

struct Volume {
    int x0 = 0, y0 = 0, z0 = 0, sx = 0, sy = 0, sz = 0;  // origin (world blocks) and size; sparse: section-aligned
    std::vector<uint32_t> ids;
    bool sparse = false;
    int nsx = 0, nsy = 0, nsz = 0;  // sparse: the section grid
    std::vector<Sec> secs;
    std::vector<int32_t> secAt;  // sparse: section grid -> index in secs, -1 none (nsx * nsy * nsz)
    const Sec* section(int gx, int gy, int gz) const {
        if (gx < 0 || gy < 0 || gz < 0 || gx >= nsx || gy >= nsy || gz >= nsz) return nullptr;
        int32_t i = secAt[size_t(gx + nsx * (gz + nsz * gy))];
        return i < 0 ? nullptr : &secs[size_t(i)];
    }
    bool cropped = false;
    int c0[3] = {0, 0, 0}, c1[3] = {0, 0, 0};  // box= (volume coordinates, inclusive): one element, the rest air
    const Block* at(int x, int y, int z) const {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return nullptr;
        if (cropped && (x < c0[0] || y < c0[1] || z < c0[2] || x > c1[0] || y > c1[1] || z > c1[2])) return nullptr;
        uint32_t id;
        if (sparse) {
            const Sec* c = section(x >> 4, y >> 4, z >> 4);
            if (!c) return nullptr;
            id = c->idx.empty() ? c->pal[0] : c->pal[c->idx[size_t((x & 15) + 16 * ((z & 15) + 16 * (y & 15)))]];
        } else {
            id = ids[size_t(x + sx * (z + sz * y))];
        }
        return id < lib.size() && lib[id].present ? &lib[id] : nullptr;
    }
} vol;

bool loadSparse(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (d.size() < 8 || std::memcmp(d.data(), "ALS1", 4) != 0) return false;
    size_t p = 4;
    auto i32 = [&]() { int32_t v; std::memcpy(&v, &d[p], 4); p += 4; return v; };
    auto u16 = [&]() { uint16_t v; std::memcpy(&v, &d[p], 2); p += 2; return v; };
    uint32_t n = uint32_t(i32());
    std::vector<std::array<int, 3>> at;
    int lo[3] = {1 << 30, 1 << 30, 1 << 30}, hi[3] = {-(1 << 30), -(1 << 30), -(1 << 30)};
    for (uint32_t k = 0; k < n; k++) {
        std::array<int, 3> c{i32(), i32(), i32()};  // cx, sy, cz
        Sec sec;
        uint16_t pn = u16();
        sec.pal.resize(pn);
        for (auto& v : sec.pal) v = uint32_t(i32());
        if (pn > 1) {
            sec.idx.resize(4096);
            for (auto& v : sec.idx) v = pn <= 256 ? d[p++] : u16();
        }
        int g[3] = {c[0], c[1], c[2]};  // x, y, z in sections
        g[1] = c[1];
        for (int a = 0; a < 3; a++) lo[a] = std::min(lo[a], g[a]), hi[a] = std::max(hi[a], g[a] + 1);
        at.push_back({c[0], c[1], c[2]});
        vol.secs.push_back(std::move(sec));
    }
    if (vol.secs.empty()) return false;
    vol.sparse = true;
    vol.nsx = hi[0] - lo[0], vol.nsy = hi[1] - lo[1], vol.nsz = hi[2] - lo[2];
    vol.x0 = lo[0] * 16, vol.y0 = lo[1] * 16, vol.z0 = lo[2] * 16;
    vol.sx = vol.nsx * 16, vol.sy = vol.nsy * 16, vol.sz = vol.nsz * 16;
    vol.secAt.assign(size_t(vol.nsx) * vol.nsy * vol.nsz, -1);
    for (size_t k = 0; k < at.size(); k++)
        vol.secAt[size_t((at[k][0] - lo[0]) + vol.nsx * ((at[k][2] - lo[2]) + vol.nsz * (at[k][1] - lo[1])))] = int32_t(k);
    std::fprintf(stderr, "atlas_lab: sparse view: %zu sections, %d x %d x %d blocks\n", vol.secs.size(), vol.sx, vol.sy, vol.sz);
    return true;
}

bool loadVolume(const std::string& path) {
    if (path.size() > 4 && path.substr(path.size() - 4) == ".als") return loadSparse(path);
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

// leaves=cards (with surface cards): leaves are whole voxels and their cards' alpha is the cutout, per texel up close
// and as the mip's coverage further away (a masked face texture), instead of hashed micro-voxels. Leaves only: a
// plant is a cross of diagonal quads, its card seen straight on nearly all covered, and as a whole voxel it became a
// solid cube (crops as bright green blocks); plants keep their hashed micro-voxels
bool leafCards = false;
inline bool cutoutClass(const Block* b) { return b && b->cls == 1; }

// micro-voxels: 4 per block side
// a second trace through water (translucent water: what lies behind it), as the game draws no faces between water
thread_local bool skipWater = false;

inline bool microAt(int mx, int my, int mz) {
    const Block* b = vol.at(mx >> 2, my >> 2, mz >> 2);
    if (!b || !b->any) return false;
    if (skipWater && b->cls == 2) return false;
    int bit = (mx & 3) + 4 * ((mz & 3) + 4 * (my & 3));
    if (!((b->mask >> bit) & 1)) return false;
    if (leafCards && cutoutClass(b)) return true;
    float a = b->alpha[bit];
    // hashed in world micro-voxels: the same cutout whatever box or view the blocks were exported in
    return a >= 0.999f || hashf(mx + vol.x0 * 4, my + vol.y0 * 4, mz + vol.z0 * 4, 0x5EEDu) < a;
}
inline RGB microCol(int mx, int my, int mz, int f) {
    const Block* b = vol.at(mx >> 2, my >> 2, mz >> 2);
    uint32_t c = b->face[f][texel(f, mx & 3, my & 3, mz & 3)];
    return tinted(*b, (c >> 24) ? unpack(c) : b->mean);
}

// ---- surface cards (atlas_truth bake=: *.alc) -----------------------------------------------------------------------
// Every face of every block seen straight on, 16 x 16 texels of what the game draws there: texture, tint, shade, AO and
// the lightmap at its light. With cards loaded a hit takes its colour from them (the surface cache) instead of the 4 x 4
// mean tiles under a fixed sun, so what is left between ours and the reference is the representation: shape, coverage,
// filtering. Texel (u, v) of face f: u along (axis + 1) % 3, v along (axis + 2) % 3 of f's normal axis.

struct Card {
    uint32_t c[256];
    RGB mean;  // of its covered texels: where the micro-voxels hold content the exact quads do not
    RGB mip[341];  // the mip chain 16, 8, 4, 2, 1 (offsets 0, 256, 320, 336, 340): covered texels' means
    float cov[341];  // and how much of each mip texel is covered
};
const int MIP_AT[5] = {0, 256, 320, 336, 340};
std::unordered_map<uint64_t, Card> cards;
float cardMipBias = 0;  // mipbias=: levels added to the footprint's (a probe of how sharp the cards are sampled)
inline uint64_t cardKey(int wx, int wy, int wz, int f) {
    return (uint64_t(uint32_t(wx) & 0xFFFFF) << 40) | (uint64_t(uint32_t(wy) & 0xFFFF) << 24) | (uint64_t(uint32_t(wz) & 0xFFFFF) << 4) | uint64_t(f);
}

bool loadCards(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    char magic[4];
    int32_t n;
    if (!f.read(magic, 4) || std::memcmp(magic, "ALC1", 4) != 0 || !f.read(reinterpret_cast<char*>(&n), 4)) return false;
    for (int32_t i = 0; i < n; i++) {
        int32_t h[4];
        Card cd;
        uint8_t light[512];
        f.read(reinterpret_cast<char*>(h), sizeof h);
        f.read(reinterpret_cast<char*>(cd.c), sizeof cd.c);
        f.read(reinterpret_cast<char*>(light), sizeof light);
        if (!f) return false;
        RGB s{};
        int k = 0;
        float w[341] = {};
        for (int t = 0; t < 256; t++) {
            uint32_t c = cd.c[t];
            cd.mip[t] = unpack(c), w[t] = (c >> 24) ? 1.f : 0.f;
            if (c >> 24) s.r += cd.mip[t].r, s.g += cd.mip[t].g, s.b += cd.mip[t].b, k++;
        }
        cd.mean = k ? RGB{s.r / k, s.g / k, s.b / k} : RGB{};
        for (int l = 1; l < 5; l++) {
            int n2 = 16 >> l;
            for (int v = 0; v < n2; v++)
                for (int u = 0; u < n2; u++) {
                    RGB acc{};
                    float ws = 0;
                    for (int j = 0; j < 4; j++) {
                        int src = MIP_AT[l - 1] + (2 * u + (j & 1)) + (2 * n2) * (2 * v + (j >> 1));
                        acc.r += cd.mip[src].r * w[src], acc.g += cd.mip[src].g * w[src], acc.b += cd.mip[src].b * w[src];
                        ws += w[src];
                    }
                    int dst = MIP_AT[l] + u + n2 * v;
                    w[dst] = ws * 0.25f;
                    cd.mip[dst] = ws > 0 ? RGB{acc.r / ws, acc.g / ws, acc.b / ws} : cd.mean;
                }
        }
        for (int t = 0; t < 256; t++)
            if (!(cd.c[t] >> 24)) cd.mip[t] = cd.mean;
        // coverage per mip texel: the mean alpha under it (leaves' texels are 0 or 1; water's its translucency, which
        // a covered-or-not mean made opaque from the first coarser level on)
        for (int t = 0; t < 256; t++) cd.cov[t] = float(cd.c[t] >> 24) / 255.f;
        for (int l = 1; l < 5; l++) {
            int n2 = 16 >> l;
            for (int v = 0; v < n2; v++)
                for (int u = 0; u < n2; u++) {
                    float a = 0;
                    for (int j = 0; j < 4; j++) a += cd.cov[MIP_AT[l - 1] + (2 * u + (j & 1)) + (2 * n2) * (2 * v + (j >> 1))];
                    cd.cov[MIP_AT[l] + u + n2 * v] = a * 0.25f;
                }
        }
        cards[cardKey(h[0], h[1], h[2], h[3])] = cd;
    }
    std::fprintf(stderr, "atlas_lab: %zu surface cards\n", cards.size());
    return true;
}

// the card's colour at plane point (pu, pv) in blocks on face f of block (bx, by, bz) (volume coordinates), filtered
// over a footprint of fp blocks (its mip, blended between the two nearest levels as a trilinear sampler would); false:
// no card there
inline bool cardCol(int bx, int by, int bz, int f, float pu, float pv, RGB& out, float fp = 0, float* cov = nullptr) {
    if (cards.empty()) return false;
    auto it = cards.find(cardKey(bx + vol.x0, by + vol.y0, bz + vol.z0, f));
    if (it == cards.end()) return false;
    int ax = f <= UP ? 1 : f <= SOUTH ? 2 : 0, a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
    int b[3] = {bx, by, bz};
    float u = std::clamp(pu - float(b[a1]), 0.f, 0.9999f), v = std::clamp(pv - float(b[a2]), 0.f, 0.9999f);
    // as Minecraft samples its atlas (atlas_truth's sample()): nearest when a texel covers more than a pixel, else
    // bilinear within the two nearest mip levels, blended (nearest texels at every level were twice as sharp as the
    // game from 30 to 300 m, and shimmered on every fly-in)
    float raw = std::log2(std::max(fp * 16.f, 1e-6f)) + cardMipBias;
    float lv = std::clamp(raw, 0.f, 4.f);
    int l0 = int(lv), l1 = std::min(4, l0 + 1);
    float t = lv - float(l0);
    const Card& cd = it->second;
    auto idx = [&](int l) {
        int n2 = 16 >> l;
        return MIP_AT[l] + std::min(n2 - 1, int(u * n2)) + n2 * std::min(n2 - 1, int(v * n2));
    };
    auto bilinear = [&](int l) {
        int n2 = 16 >> l;
        float x = u * n2 - 0.5f, y = v * n2 - 0.5f;
        int x0 = int(std::floor(x)), y0 = int(std::floor(y));
        float fx = x - float(x0), fy = y - float(y0);
        auto px = [&](int i, int j) {
            return cd.mip[MIP_AT[l] + std::clamp(i, 0, n2 - 1) + n2 * std::clamp(j, 0, n2 - 1)];
        };
        RGB a = px(x0, y0), b = px(x0 + 1, y0), c = px(x0, y0 + 1), d = px(x0 + 1, y0 + 1);
        auto mix = [](RGB p, RGB q, float k) { return RGB{p.r + (q.r - p.r) * k, p.g + (q.g - p.g) * k, p.b + (q.b - p.b) * k}; };
        return mix(mix(a, b, fx), mix(c, d, fx), fy);
    };
    if (cov) *cov = cd.cov[idx(l0)] + (cd.cov[idx(l1)] - cd.cov[idx(l0)]) * t;
    if (raw <= 0) {
        out = cd.mip[idx(0)];
        return true;
    }
    RGB c0 = bilinear(l0), c1 = bilinear(l1);
    out = {c0.r + (c1.r - c0.r) * t, c0.g + (c1.g - c0.g) * t, c0.b + (c1.b - c0.b) * t};
    return true;
}

// ---- traversal ------------------------------------------------------------------------------------------------------

const int FACE_LOW[3] = {WEST, DOWN, NORTH}, FACE_HIGH[3] = {EAST, UP, SOUTH};
const V3 NORMALS[6] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
V3 SUN = norm({0.45f, 0.8f, 0.35f});

inline int enteredFace(int axis, const V3& d) { return d[axis] > 0 ? FACE_LOW[axis] : FACE_HIGH[axis]; }

bool mcShade = false;  // shade=mc: vanilla's fixed face shading (as a near screenshot without a shader pack)
const float MC_SHADE[6] = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

RGB lit(RGB c, int face) {
    if (mcShade) return {c.r * MC_SHADE[face], c.g * MC_SHADE[face], c.b * MC_SHADE[face]};
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
    float t = 0;  // how far along the ray the hit is (blocks): reprojection for the moving-camera TAA
    uint32_t id = 0;  // which surface was hit (block, voxel, box or group): TAA keeps history only on the same one
    float alpha = 1;  // a translucent hit's opacity (water's card): what is behind it shows through the rest
};

// where a ray enters the cube [lo, lo + size) (0 when it starts inside)
inline float boxEntry(V3 o, V3 d, V3 lo, float size) {
    float t0 = 0;
    for (int a = 0; a < 3; a++) {
        if (std::fabs(d[a]) < 1e-12f) continue;
        float ta = (lo[a] - o[a]) / d[a], tb = (lo[a] + size - o[a]) / d[a];
        t0 = std::max(t0, std::min(ta, tb));
    }
    return t0;
}

// vanilla smooth lighting (shade=mc): each corner of a face averages the shade of the four blocks in front of it
// (the one the face looks into and its three neighbours around that corner), 0.2 for an opaque full block and 1.0
// otherwise, the two sides hiding the diagonal when both are opaque; the hit point blends its face's four corners
inline bool opaqueAt(int x, int y, int z) {
    const Block* b = vol.at(x, y, z);
    return b && b->cls == 0 && (b->flags & 8) && !(b->flags & 2);
}
float vanillaAo(int bx, int by, int bz, int f, V3 p) {
    const int n[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    int ax = f <= UP ? 1 : f <= SOUTH ? 2 : 0;  // the face's normal axis
    int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
    int o[3] = {bx + n[f][0], by + n[f][1], bz + n[f][2]};  // the block the face looks into
    float pu = p[a1] - float(a1 == 0 ? bx : a1 == 1 ? by : bz), pv = p[a2] - float(a2 == 0 ? bx : a2 == 1 ? by : bz);
    pu = std::clamp(pu, 0.f, 1.f), pv = std::clamp(pv, 0.f, 1.f);
    auto shade = [&](int du, int dv) {
        int q[3] = {o[0], o[1], o[2]};
        q[a1] += du, q[a2] += dv;
        return opaqueAt(q[0], q[1], q[2]) ? 0.2f : 1.0f;
    };
    float c0 = shade(0, 0);
    float corner[4];
    for (int k = 0; k < 4; k++) {
        int du = (k & 1) ? 1 : -1, dv = (k & 2) ? 1 : -1;
        float s1 = shade(du, 0), s2 = shade(0, dv);
        float d = (s1 < 1.f && s2 < 1.f) ? 0.2f : shade(du, dv);
        corner[k] = (c0 + s1 + s2 + d) * 0.25f;
    }
    return (corner[0] * (1 - pu) + corner[1] * pu) * (1 - pv) + (corner[2] * (1 - pu) + corner[3] * pu) * pv;
}

Sample traceMicroDense(V3 o, V3 d);

// micro-voxels inside the non-air blocks (ref, micro, and the cov chain's level 0); a sparse view's rays go section
// by section first (a kilometre is ~60 sections, not 1000 blocks)
Sample traceMicro(V3 o, V3 d) {
    if (!vol.sparse) return traceMicroDense(o, d);
    Sample s;
    s.steps = dda(o, d, 16.f, vol.nsx, vol.nsy, vol.nsz, [&](int gx, int gy, int gz, int) {
        if (!vol.section(gx, gy, gz)) return false;
        V3 so{float(gx * 16), float(gy * 16), float(gz * 16)};
        s.steps += dda(o - so, d, 1.f, 16, 16, 16, [&](int lx, int ly, int lz, int) {
            int bx = gx * 16 + lx, by = gy * 16 + ly, bz = gz * 16 + lz;
            const Block* b = vol.at(bx, by, bz);
            if (!b || !b->any || !b->mask) return false;
            V3 lo{float(bx), float(by), float(bz)};
            s.steps += dda(o - lo, d, 0.25f, 4, 4, 4, [&](int x, int y, int z, int axis) {
                int mx = bx * 4 + x, my = by * 4 + y, mz = bz * 4 + z;
                if (!microAt(mx, my, mz)) return false;
                int f = enteredFace(axis, d);
                s.hit = true;
                s.t = boxEntry(o, d, {mx * 0.25f, my * 0.25f, mz * 0.25f}, 0.25f);
                s.id = hash(uint32_t(bx) * 73856093u ^ uint32_t(by) * 19349663u ^ uint32_t(bz) * 83492791u) | 1u;
                V3 hp = o + d * s.t;
                int ax = f <= UP ? 1 : f <= SOUTH ? 2 : 0;
                if (cardCol(bx, by, bz, f, hp[(ax + 1) % 3], hp[(ax + 2) % 3], s.c)) return true;
                s.c = lit(microCol(mx, my, mz, f), f);
                if (mcShade) {
                    float ao = vanillaAo(bx, by, bz, f, hp);
                    s.c = {s.c.r * ao, s.c.g * ao, s.c.b * ao};
                }
                return true;
            });
            return s.hit;
        });
        return s.hit;
    });
    return s;
}

Sample traceMicroDense(V3 o, V3 d) {
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
            s.t = boxEntry(o, d, {mx * 0.25f, my * 0.25f, mz * 0.25f}, 0.25f);
            s.id = hash(uint32_t(bx) * 73856093u ^ uint32_t(by) * 19349663u ^ uint32_t(bz) * 83492791u) | 1u;
            V3 hp = o + d * s.t;
            int ax = f <= UP ? 1 : f <= SOUTH ? 2 : 0;
            if (cardCol(bx, by, bz, f, hp[(ax + 1) % 3], hp[(ax + 2) % 3], s.c)) return true;
            s.c = lit(microCol(mx, my, mz, f), f);
            if (mcShade) {
                float ao = vanillaAo(bx, by, bz, f, hp);
                s.c = {s.c.r * ao, s.c.g * ao, s.c.b * ao};
            }
            return true;
        });
        return s.hit;
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
bool firstHit(const int lo[3], const int hi[3], int axis, bool high, int u, int v, int& depth, int p[3], int from = 0) {
    int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
    int n = hi[axis] - lo[axis];
    for (int t = from; t < n; t++) {
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
                RGB m;
                float su = (u + 0.5f) * 0.25f, sv = (v + 0.5f) * 0.25f;
                if (!cardCol(q[0] >> 2, q[1] >> 2, q[2] >> 2, f, su, sv, m, fp * 0.25f / k)) m = lit(microCol(q[0], q[1], q[2], f), f);
                acc.r += m.r, acc.g += m.g, acc.b += m.b;
                hits++;
            }
        if (!hits || hashf(int(p[a1] * 64.f), int(p[a2] * 64.f), axis, c.seed) >= float(hits) / float(k * k)) return false;
        c.s->hit = true;
    c.s->t = t;
        c.s->c = {acc.r / hits, acc.g / hits, acc.b / hits};
        return true;
    }
    int u = std::clamp(int(std::floor(p[a1] * 4.f)), lo[a1], hi[a1] - 1), v = std::clamp(int(std::floor(p[a2] * 4.f)), lo[a2], hi[a2] - 1);
    int dep, q[3], from = 0;
    float fpb = t * c.pa / std::max(std::fabs(c.d[axis]), 0.02f);  // the footprint on the face, blocks (atlas_truth's clamp)
    for (;;) {
        if (!firstHit(lo, hi, axis, high, u, v, dep, q, from)) return false;
        float cv = 1;
        RGB col;
        bool card = cardCol(q[0] >> 2, q[1] >> 2, q[2] >> 2, f, p[a1], p[a2], col, fpb, &cv);
        if (card && leafCards && cutoutClass(vol.at(q[0] >> 2, q[1] >> 2, q[2] >> 2)) &&
            float(hash(c.seed ^ hash(uint32_t(dep) * 0x9E3779B9u)) >> 8) * (1.0f / 16777216.0f) >= cv) {
            // through the cutout: on past this block along the column
            int qa = q[axis], next = high ? (qa & ~3) - 1 : (qa | 3) + 1;
            from = high ? hi[axis] - 1 - next : next - lo[axis];
            continue;
        }
        c.s->hit = true;
        c.s->t = t;
        c.s->c = card ? col : lit(microCol(q[0], q[1], q[2], f), f);
        const Block* hb = vol.at(q[0] >> 2, q[1] >> 2, q[2] >> 2);
        c.s->alpha = card && hb && hb->cls == 2 ? cv : 1.f;
        return true;
    }
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
        c.s->id = uint32_t(idx) * 2u + 1u;
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
        c.s->id = uint32_t(idx) * 2u + 1u;
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
    if (s.hit && s.alpha < 0.999f) {  // translucent water over what the ray meets without it (or the sky), as blended
        Sample b;
        BoxCtx cb{o, d, pa, tol, &b, prefilter, seed};
        skipWater = true;
        traceGroup(rootGroup, cb);
        skipWater = false;
        RGB under = b.hit ? b.c : RGB{0x9C, 0xC4, 0xEC};
        float a = s.alpha;
        s.c = {s.c.r * a + under.r * (1 - a), s.c.g * a + under.g * (1 - a), s.c.b * a + under.b * (1 - a)};
        s.steps += b.steps;
    }
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

// what a miss shows: atlas_truth's flat sky (sky=RRGGBB: another, e.g. the realistic reference's for its time of day)
RGB SKY{0x9C, 0xC4, 0xEC};

}  // namespace
