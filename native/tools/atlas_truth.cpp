// atlas_truth: the exact offline reference (docs/ATLAS-LOD.md, "Ground truth against Minecraft itself"). Minecraft's own
// chunk mesh, traced on the CPU: every block's real model quads and sprites (/nirncraft-atlaslab quads), lit the way the
// game lights them, in a live capture's own camera, so the harness compares the game with the same blocks drawn the
// game's way rather than with an approximation of them.
//
//   atlas_truth quads=<dir>/quads.bin volume=<dir>/falkreath-wide.alv2 eye=x,y,z yaw=Y pitch=P fov=70 w=1280 h=720
//               out=frame.png [sprites=<dir>/sprites.bin] [json=<volume>.json] [time=6000] [gamma=0.5] [spp=1]
//               [threads=N] [light=data|compute] [probe=x,y;x,y] [ray=ox,oy,oz,dx,dy,dz;...]
//               [blocks=<dir>/blocks.bin: the old library, used when there is no quads.bin]
//
// Writes out (the frame), out_mask.png (where the frame shows exported blocks), out_depth.png (the distance to them in
// 1/16 blocks, 24 bits, all ones for sky: atlas_lab's encoding) and a JSON line. ray= traces single rays instead (world
// coordinates; one JSON line each, for tests). camera=, shade=, frames= and variants= (atlas_lab's) are accepted and
// ignored.
//
// What it reproduces, each from the 26.3 / NeoForge 26.3.0.90 sources:
//   geometry   each block's model quads (the part list the mesher picks at that position: ALV2/ALS2 carry the variant),
//              the random offset of plants (Mth.getSeed), the culling against neighbours (Block.shouldRenderFace: face
//              occlusion shapes as 16 x 16 masks, glass against glass), back faces culled; fluids by FluidRenderer's
//              rules (corner heights, flow direction, side and bottom faces, the overlay texture beside glass)
//   textures   the sprite's own texels: nearest up close, a box-filtered mip chain when a texel is under a pixel
//              (the mip level from the distance and the surface's slant); cutout discards alpha < 0.5, translucent
//              < 0.1 and blends front to back (glass, water, ice, stained glass), solid ignores alpha
//   tint       grass, foliage, water and dry foliage from the column's biome tints (ALV2: as the game blended them;
//              ALS2: raw, blended here over the game's radius), constants (spruce, birch, lily pad), per tint index
//   lighting   NeoForge's enhanced smooth lighting (FullFaceCalculator + AoFace: per-vertex shade and light from the
//              face's neighbours, irregular quads by their normals), flat lighting where the model or quad turns AO
//              off, the cardinal face shade, emissive quads and blocks, and the lightmap (Lightmap's shader: sky and
//              block light curves, the warm block light tint, ambient, the gamma option), sampled per vertex and
//              interpolated over the quad's two triangles as the GPU does
// What it does not: block entities (chests, campfire flames, signs, banners, beds), entities, particles, animated
// textures past their first frame, fog, sky and clouds (a miss is a flat sky colour), the atlas's exact mip chain,
// the lightmap's flicker, light through shapes (the computed light of old volumes spreads block to block).
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

#ifdef _WIN32
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

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

struct V3 {
    float x = 0, y = 0, z = 0;
    float operator[](int i) const { return i == 0 ? x : i == 1 ? y : z; }
    float& operator[](int i) { return i == 0 ? x : i == 1 ? y : z; }
};
V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V3 operator*(V3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
float dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
float len(V3 a) { return std::sqrt(dot(a, a)); }
V3 norm(V3 a) { return a * (1.0f / std::sqrt(dot(a, a))); }

struct RGB {
    float r = 0, g = 0, b = 0;
};

// ---- reading ------------------------------------------------------------------------------------------------------

struct Reader {
    std::vector<uint8_t> d;
    size_t p = 0;
    bool ok = true;
    bool load(const std::string& path) {
        std::ifstream f(path, std::ios::binary);
        if (!f) return false;
        d.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
        return true;
    }
    bool need(size_t n) {
        if (p + n > d.size()) ok = false;
        return ok;
    }
    bool magic(const char* m) { return d.size() >= 4 && std::memcmp(d.data(), m, 4) == 0 && ((p = 4), true); }
    uint8_t u8() { return need(1) ? d[p++] : 0; }
    template <class T>
    T get() {
        T v{};
        if (need(sizeof(T))) std::memcpy(&v, &d[p], sizeof(T)), p += sizeof(T);
        return v;
    }
    uint16_t u16() { return get<uint16_t>(); }
    uint32_t u32() { return get<uint32_t>(); }
    int32_t i32() { return get<int32_t>(); }
    float f32() { return get<float>(); }
    std::string bytes(size_t n) {
        if (!need(n)) return {};
        std::string s(reinterpret_cast<const char*>(&d[p]), n);
        p += n;
        return s;
    }
};

// ---- sprites (sprites.bin): native texels and a box-filtered mip chain ---------------------------------------------

using RGBA = std::array<float, 4>;  // 0..1

struct Sprite {
    std::string name;
    int w = 1, h = 1;
    bool animated = false;
    std::vector<std::vector<RGBA>> mip;  // level -> texels, rows from the top
    int mw(int l) const { return std::max(1, w >> l); }
    int mh(int l) const { return std::max(1, h >> l); }
    const RGBA& at(int l, int x, int y) const {
        x = std::clamp(x, 0, mw(l) - 1), y = std::clamp(y, 0, mh(l) - 1);
        return mip[size_t(l)][size_t(x + mw(l) * y)];
    }
};
std::vector<Sprite> sprites;
int mipLevels = 4;  // the game's mipmap option (quads.json)

RGBA argb(uint32_t c) {
    return {float((c >> 16) & 255) / 255.f, float((c >> 8) & 255) / 255.f, float(c & 255) / 255.f, float(c >> 24) / 255.f};
}

void buildMips(Sprite& s) {
    int levels = 0;
    while (levels < mipLevels && (s.w >> (levels + 1)) >= 1 && (s.h >> (levels + 1)) >= 1) levels++;
    for (int l = 1; l <= levels; l++) {
        int w = s.mw(l), h = s.mh(l);
        std::vector<RGBA> m(size_t(w) * h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                RGBA acc{0, 0, 0, 0};
                float wsum = 0;
                for (int k = 0; k < 4; k++) {
                    const RGBA& c = s.at(l - 1, 2 * x + (k & 1), 2 * y + (k >> 1));
                    float wa = std::max(c[3], 1e-3f);  // colour weighted by alpha: transparent texels do not bleed in
                    for (int i = 0; i < 3; i++) acc[i] += c[i] * wa;
                    wsum += wa;
                    acc[3] += c[3] * 0.25f;
                }
                for (int i = 0; i < 3; i++) acc[i] /= wsum;
                m[size_t(x + w * y)] = acc;
            }
        s.mip.push_back(std::move(m));
    }
}

RGBA bilinear(const Sprite& s, int l, float u, float v) {
    float x = u * float(s.mw(l)) - 0.5f, y = v * float(s.mh(l)) - 0.5f;
    int x0 = int(std::floor(x)), y0 = int(std::floor(y));
    float fx = x - float(x0), fy = y - float(y0);
    RGBA out;
    for (int i = 0; i < 4; i++)
        out[i] = (s.at(l, x0, y0)[i] * (1 - fx) + s.at(l, x0 + 1, y0)[i] * fx) * (1 - fy) +
                 (s.at(l, x0, y0 + 1)[i] * (1 - fx) + s.at(l, x0 + 1, y0 + 1)[i] * fx) * fy;
    return out;
}

// the texel under (u, v) at mip level lod: nearest when magnified (the terrain shader's sampleNearest), trilinear when a
// texel is smaller than a pixel
RGBA sample(int sprite, float u, float v, float lod) {
    if (sprite < 0 || size_t(sprite) >= sprites.size()) return {1, 0, 1, 1};
    const Sprite& s = sprites[size_t(sprite)];
    if (lod <= 0 || s.mip.size() == 1)
        return s.at(0, int(std::floor(u * float(s.w))), int(std::floor(v * float(s.h))));
    float maxL = float(s.mip.size() - 1);
    lod = std::min(lod, maxL);
    int l0 = int(std::floor(lod));
    float f = lod - float(l0);
    RGBA a = bilinear(s, l0, u, v);
    if (f <= 0 || l0 + 1 > int(maxL)) return a;
    RGBA b = bilinear(s, l0 + 1, u, v);
    for (int i = 0; i < 4; i++) a[i] = a[i] * (1 - f) + b[i] * f;
    return a;
}

bool loadSprites(const std::string& path) {
    Reader r;
    if (!r.load(path) || !r.magic("ALT1")) return false;
    uint32_t n = r.u32();
    sprites.resize(n);
    for (uint32_t i = 0; i < n && r.ok; i++) {
        Sprite& s = sprites[i];
        s.name = r.bytes(r.u16());
        s.w = std::max<int>(1, r.u16());
        s.h = std::max<int>(1, r.u16());
        s.animated = r.u8() & 1;
        std::vector<RGBA> px(size_t(s.w) * s.h);
        for (auto& c : px) c = argb(r.u32());
        s.mip.push_back(std::move(px));
        buildMips(s);
    }
    return r.ok;
}

// ---- the block-state library (quads.bin) --------------------------------------------------------------------------

enum Dir { DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5, NONE = 6 };
const int DV[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
int axisOf(int d) { return d <= UP ? 1 : d <= SOUTH ? 2 : 0; }

enum Flag : uint16_t {
    F_SOLID_RENDER = 1, F_LIGHT_PERMEABLE = 2, F_COLLISION_FULL = 4, F_EMISSIVE = 8, F_LEGACY_SOLID = 16, F_CAN_OCCLUDE = 32,
    F_LEAVES = 64, F_MODEL = 128, F_FLUID_OVERLAY = 256, F_AIR = 512
};
enum Layer { SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2 };

struct Quad {
    V3 p[4];
    float u[4] = {}, v[4] = {};
    V3 n[4];
    int sprite = -1;
    uint8_t dir = UP, cull = NONE, shade = NONE, layer = SOLID;
    int16_t tint = -1;
    uint8_t emit = 0, ao = 1 | (2 << 1);
    float rho = 16.f;  // texels per block along the surface: the mip level's scale
    // fluids: lit and tinted when built (FluidRenderer gives a face one colour and one light)
    bool baked = false;
    uint32_t bakedColor = 0xFFFFFFFF;
    int bakedLight = 0;
};
struct Model {
    uint8_t firstAo = 2;
    std::vector<Quad> quads;
    V3 lo{0, 0, 0}, hi{1, 1, 1};
};
struct FluidModel {
    int still = -1, flow = -1, overlay = -1;
    uint8_t layer = TRANSLUCENT, tintKind = 0;
    uint32_t tintConst = 0xFFFFFFFF;
};
struct Tint {
    uint8_t kind = 0;  // 0 white, 1 grass, 2 foliage, 3 water, 4 dry foliage, 5 constant
    uint32_t c = 0xFFFFFFFF;
};
struct State {
    bool present = false;
    std::string name;
    uint32_t block = 0;
    uint16_t flags = F_AIR | F_LIGHT_PERMEABLE;
    uint8_t emit = 0, damp = 0;
    float shade = 1.f;
    uint8_t offType = 0;
    float maxH = 0.25f, maxV = 0.2f;
    int fluid = -1;
    float fluidH = 0;
    uint8_t amount = 0, fluidFlags = 0;
    uint8_t faceFull = 0, facePartial = 0, skipSame = 0;
    std::array<std::array<uint16_t, 16>, 6> mask{};
    std::vector<Tint> tints;
    std::vector<int> variants;
    bool overflow = false;  // geometry beyond its own cell: rays through the neighbours test it too
    bool has(uint16_t f) const { return (flags & f) != 0; }
};
std::vector<Model> models;
std::vector<FluidModel> fluidModels;
std::vector<State> states;
State AIR;

const State& stateOf(uint32_t id) { return id < states.size() && states[id].present ? states[id] : AIR; }

struct Options {
    bool ao = true, cutoutLeaves = true, perPartAo = false, enhanced = true;
    int blend = 2;
} opt;
float CARD[6] = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};  // CardinalLighting.DEFAULT: down, up, north, south, west, east

void finishQuad(Quad& q) {
    V3 e1 = q.p[1] - q.p[0], e2 = q.p[2] - q.p[0], e3 = q.p[3] - q.p[0];
    float world = 0.5f * (len(cross(e1, e2)) + len(cross(e2, e3)));
    float tex = 0;
    if (q.sprite >= 0 && size_t(q.sprite) < sprites.size()) {
        float sw = float(sprites[size_t(q.sprite)].w), sh = float(sprites[size_t(q.sprite)].h);
        auto uv = [&](int i) { return V3{q.u[i] * sw, q.v[i] * sh, 0}; };
        tex = 0.5f * (std::fabs(cross(uv(1) - uv(0), uv(2) - uv(0)).z) + std::fabs(cross(uv(2) - uv(0), uv(3) - uv(0)).z));
    }
    q.rho = world > 1e-8f && tex > 0 ? std::sqrt(tex / world) : 16.f;
}

void finishModel(Model& m) {
    m.lo = {1e9f, 1e9f, 1e9f}, m.hi = {-1e9f, -1e9f, -1e9f};
    for (Quad& q : m.quads) {
        finishQuad(q);
        for (int i = 0; i < 4; i++)
            for (int a = 0; a < 3; a++) m.lo[a] = std::min(m.lo[a], q.p[i][a]), m.hi[a] = std::max(m.hi[a], q.p[i][a]);
    }
    if (m.quads.empty()) m.lo = {0, 0, 0}, m.hi = {1, 1, 1};
}

void finishStates() {
    for (State& s : states) {
        if (!s.present) continue;
        bool out = s.offType != 0;
        for (int v : s.variants)
            if (v >= 0 && size_t(v) < models.size()) {
                const Model& m = models[size_t(v)];
                for (int a = 0; a < 3; a++) out |= m.lo[a] < -1e-4f || m.hi[a] > 1.0001f;
            }
        s.overflow = out;
    }
    AIR.present = true;
    AIR.name = "air";
}

bool loadQuads(const std::string& path) {
    Reader r;
    if (!r.load(path) || !r.magic("ALQ1")) return false;
    uint32_t nm = r.u32();
    models.resize(nm);
    for (uint32_t i = 0; i < nm && r.ok; i++) {
        Model& m = models[i];
        m.firstAo = r.u8();
        uint32_t nq = r.u32();
        m.quads.resize(nq);
        for (Quad& q : m.quads) {
            for (int k = 0; k < 4; k++) {
                q.p[k].x = r.f32(), q.p[k].y = r.f32(), q.p[k].z = r.f32();
                q.u[k] = r.f32(), q.v[k] = r.f32();
            }
            for (int k = 0; k < 4; k++) {
                uint32_t pn = r.u32();
                q.n[k] = {float(int8_t(pn & 255)) / 127.f, float(int8_t((pn >> 8) & 255)) / 127.f, float(int8_t((pn >> 16) & 255)) / 127.f};
            }
            q.sprite = r.i32();
            q.dir = r.u8(), q.cull = r.u8(), q.shade = r.u8(), q.layer = r.u8();
            q.tint = int16_t(r.u16());
            q.emit = r.u8(), q.ao = r.u8();
        }
        finishModel(m);
    }
    uint32_t nf = r.u32();
    fluidModels.resize(nf);
    for (FluidModel& f : fluidModels) {
        f.still = r.i32(), f.flow = r.i32(), f.overlay = r.i32();
        f.layer = r.u8(), f.tintKind = r.u8();
        f.tintConst = r.u32();
    }
    uint32_t ns = r.u32();
    for (uint32_t i = 0; i < ns && r.ok; i++) {
        uint32_t id = r.u32();
        if (id >= states.size()) states.resize(size_t(id) + 1);
        State& s = states[id];
        s.present = true;
        s.name = r.bytes(r.u16());
        s.block = r.u32();
        s.flags = r.u16();
        s.emit = r.u8(), s.damp = r.u8();
        s.shade = r.f32();
        s.offType = r.u8();
        if (s.offType) s.maxH = r.f32(), s.maxV = r.f32();
        s.fluid = int8_t(r.u8());
        if (s.fluid >= 0) s.fluidH = r.f32(), s.amount = r.u8(), s.fluidFlags = r.u8();
        s.faceFull = r.u8(), s.facePartial = r.u8();
        for (int d = 0; d < 6; d++)
            if (s.facePartial & (1 << d))
                for (auto& row : s.mask[size_t(d)]) row = r.u16();
        for (int d = 0; d < 6; d++)
            if (s.faceFull & (1 << d)) s.mask[size_t(d)].fill(0xFFFF);
        s.skipSame = r.u8();
        s.tints.resize(r.u8());
        for (Tint& t : s.tints) t.kind = r.u8(), t.c = r.u32();
        s.variants.resize(r.u8());
        for (int& v : s.variants) v = r.i32();
    }
    finishStates();
    return r.ok;
}

// the old library (blocks.bin, ALB1): each state's 4x4x4 shape as micro-voxel faces with its 4x4 face tiles as
// sprites, so old exports still render (with the old library's coarseness)
bool loadOldBlocks(const std::string& path) {
    Reader r;
    if (!r.load(path) || !r.magic("ALB1")) return false;
    uint32_t n = r.u32();
    for (uint32_t i = 0; i < n && r.ok; i++) {
        uint32_t id = r.u32();
        std::string name = r.bytes(r.u16());
        uint8_t cls = r.u8(), emit = r.u8(), flags = r.u8(), tintKind = r.u8();
        uint64_t mask = r.get<uint64_t>();
        uint32_t mean = r.u32();
        uint32_t face[6][16];
        for (auto& f : face)
            for (auto& t : f) t = r.u32();
        if (id >= states.size()) states.resize(size_t(id) + 1);
        State& s = states[id];
        s.present = true;
        s.name = name;
        s.block = id;
        bool air = name.rfind("Block{minecraft:air}", 0) == 0 || name.rfind("Block{minecraft:cave_air}", 0) == 0 ||
                   name.rfind("Block{minecraft:void_air}", 0) == 0;
        bool water = cls == 2 && !(flags & 4);
        bool full = mask == ~0ull && cls == 0 && !(flags & 2);
        s.flags = uint16_t((air ? F_AIR : 0) | (full ? F_SOLID_RENDER | F_COLLISION_FULL | F_CAN_OCCLUDE | F_LEGACY_SOLID : F_LIGHT_PERMEABLE) |
                           (cls == 1 ? F_LEAVES : 0) | (!air && !water ? F_MODEL : 0));
        s.emit = emit;
        s.damp = uint8_t(full ? 15 : cls == 1 ? 1 : 0);
        s.shade = full ? 0.2f : 1.f;
        if (full) s.faceFull = 63;
        if (tintKind) s.tints.push_back({uint8_t(tintKind == 4 ? 0 : tintKind), 0xFFFFFFFF});
        if (water) {
            if (fluidModels.empty()) {
                Sprite sp;
                sp.name = "water (old library: its mean colour)";
                sp.mip.push_back({argb(0xB4000000u | (mean & 0xFFFFFF ? mean & 0xFFFFFF : 0x3F76E4))});
                sprites.push_back(sp);
                fluidModels.push_back({int(sprites.size() - 1), int(sprites.size() - 1), -1, TRANSLUCENT, 3, 0xFFFFFFFF});
            }
            s.fluid = 0, s.fluidH = 8.f / 9.f, s.amount = 8, s.fluidFlags = 1;
            continue;
        }
        if (air || !mask) continue;
        // one 4x4 sprite per face, then a quad for each micro-voxel face not covered by its neighbour in the block
        int base = int(sprites.size());
        bool cut = false;
        for (int f = 0; f < 6; f++) {
            Sprite sp;
            sp.name = name + " face " + std::to_string(f);
            sp.w = sp.h = 4;
            std::vector<RGBA> px(16);
            for (int t = 0; t < 16; t++) {
                uint32_t c = face[f][t];
                px[size_t(t)] = argb((c >> 24) ? c : (mean & 0xFFFFFF));
                cut |= (c >> 24) < 255;
            }
            sp.mip.push_back(std::move(px));
            buildMips(sp);
            sprites.push_back(std::move(sp));
        }
        Model m;
        auto bit = [&](int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < 4 && y < 4 && z < 4 && ((mask >> (x + 4 * (z + 4 * y))) & 1);
        };
        for (int y = 0; y < 4; y++)
            for (int z = 0; z < 4; z++)
                for (int x = 0; x < 4; x++) {
                    if (!bit(x, y, z)) continue;
                    for (int f = 0; f < 6; f++) {
                        if (bit(x + DV[f][0], y + DV[f][1], z + DV[f][2])) continue;
                        V3 lo{x * 0.25f, y * 0.25f, z * 0.25f}, hi{lo.x + 0.25f, lo.y + 0.25f, lo.z + 0.25f};
                        // the corners in the game's order for that face (FaceInfo), counter-clockwise from outside
                        static const int C[6][4][3] = {
                            {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}}, {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
                            {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}}, {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
                            {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}}, {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}}};
                        Quad q;
                        for (int k = 0; k < 4; k++) {
                            q.p[k] = {C[f][k][0] ? hi.x : lo.x, C[f][k][1] ? hi.y : lo.y, C[f][k][2] ? hi.z : lo.z};
                            // the tile's (u, v) as AtlasLabExport laid it out: up/down (x, z), north/south (x, 1 - y),
                            // west/east (z, 1 - y)
                            float a = f <= UP ? q.p[k].x : f <= SOUTH ? q.p[k].x : q.p[k].z;
                            float b = f <= UP ? q.p[k].z : 1.f - q.p[k].y;
                            q.u[k] = a, q.v[k] = b;
                            q.n[k] = {float(DV[f][0]), float(DV[f][1]), float(DV[f][2])};
                        }
                        q.sprite = base + f;
                        q.dir = uint8_t(f);
                        bool boundary = (f == DOWN && y == 0) || (f == UP && y == 3) || (f == NORTH && z == 0) || (f == SOUTH && z == 3) ||
                                        (f == WEST && x == 0) || (f == EAST && x == 3);
                        q.cull = uint8_t(boundary ? f : NONE);
                        q.layer = uint8_t((flags & 2) ? TRANSLUCENT : cut || cls == 1 || cls == 4 ? CUTOUT : SOLID);
                        q.tint = int16_t(tintKind ? 0 : -1);
                        m.quads.push_back(q);
                    }
                }
        finishModel(m);
        s.variants.push_back(int(models.size()));
        models.push_back(std::move(m));
    }
    finishStates();
    return r.ok;
}

// ---- the volume: ALV1 / ALV2 dense boxes, ALS1 / ALS2 sparse views ------------------------------------------------

struct Section {
    std::vector<uint32_t> pal;
    std::vector<uint16_t> idx;   // x + 16 * (z + 16 * y)
    std::vector<uint8_t> light;  // sky << 4 | block
    std::vector<uint8_t> var;
    std::vector<uint8_t> touched;  // a neighbour's geometry reaches into the block
};

struct Volume {
    int x0 = 0, y0 = 0, z0 = 0, sx = 0, sy = 0, sz = 0;  // world origin and size; sparse: section-aligned
    bool sparse = false, hasLight = false, hasVar = false, hasTints = false;
    std::vector<uint32_t> ids;
    std::vector<uint8_t> light, var, touched;
    std::vector<uint32_t> tints;  // dense: per column (x + sx * z) grass, foliage, water, dry foliage (blended)
    int nsx = 0, nsy = 0, nsz = 0;
    std::vector<Section> secs;
    std::vector<int32_t> secAt;  // section grid -> secs, -1 none
    std::vector<int32_t> chunkAt;  // sparse tints: chunk column grid (nsx * nsz) -> ctints / 1024, -1 none
    std::vector<uint32_t> ctints;  // per chunk 256 columns x 4 (blended at load)

    bool inside(int x, int y, int z) const { return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz; }
    Section* section(int gx, int gy, int gz) {
        if (gx < 0 || gy < 0 || gz < 0 || gx >= nsx || gy >= nsy || gz >= nsz) return nullptr;
        int32_t i = secAt[size_t(gx + nsx * (gz + nsz * gy))];
        return i < 0 ? nullptr : &secs[size_t(i)];
    }
    const Section* section(int gx, int gy, int gz) const { return const_cast<Volume*>(this)->section(gx, gy, gz); }
    size_t dense(int x, int y, int z) const { return size_t(x + sx * (z + sz * y)); }
    static size_t local(int x, int y, int z) { return size_t((x & 15) + 16 * ((z & 15) + 16 * (y & 15))); }

    uint32_t id(int x, int y, int z) const {
        if (!inside(x, y, z)) return 0;
        if (!sparse) return ids[dense(x, y, z)];
        const Section* c = section(x >> 4, y >> 4, z >> 4);
        if (!c) return 0;
        return c->idx.empty() ? c->pal[0] : c->pal[c->idx[local(x, y, z)]];
    }
    uint8_t lightAt(int x, int y, int z) const {  // outside and in all-air sections: open sky
        if (!inside(x, y, z)) return 0xF0;
        if (!sparse) return light.empty() ? 0xF0 : light[dense(x, y, z)];
        const Section* c = section(x >> 4, y >> 4, z >> 4);
        return !c || c->light.empty() ? 0xF0 : c->light[local(x, y, z)];
    }
    int variant(int x, int y, int z) const {
        if (!inside(x, y, z)) return 0;
        if (!sparse) return var.empty() ? 0 : var[dense(x, y, z)];
        const Section* c = section(x >> 4, y >> 4, z >> 4);
        return !c || c->var.empty() ? 0 : c->var[local(x, y, z)];
    }
    bool touchedAt(int x, int y, int z) const {
        if (!inside(x, y, z)) return false;
        if (!sparse) return !touched.empty() && touched[dense(x, y, z)];
        const Section* c = section(x >> 4, y >> 4, z >> 4);
        return c && !c->touched.empty() && c->touched[local(x, y, z)];
    }
    void touch(int x, int y, int z) {
        if (!inside(x, y, z)) return;
        if (!sparse) {
            if (touched.empty()) touched.assign(ids.size(), 0);
            touched[dense(x, y, z)] = 1;
            return;
        }
        Section* c = section(x >> 4, y >> 4, z >> 4);
        if (!c) return;
        if (c->touched.empty()) c->touched.assign(4096, 0);
        c->touched[local(x, y, z)] = 1;
    }
} vol;

uint32_t tintGlobal[4] = {0x91BD59, 0x77AB2F, 0x3F76E4, 0xA0A069};  // the sidecar's (old formats): one for all

uint32_t columnTint(int x, int z, int kind) {  // kind 0 grass, 1 foliage, 2 water, 3 dry foliage
    if (vol.hasTints) {
        x = std::clamp(x, 0, vol.sx - 1), z = std::clamp(z, 0, vol.sz - 1);
        if (!vol.sparse) return vol.tints[size_t(x + vol.sx * z) * 4 + size_t(kind)];
        int32_t c = vol.chunkAt[size_t((x >> 4) + vol.nsx * (z >> 4))];
        if (c >= 0) return vol.ctints[size_t(c) * 1024 + size_t((x & 15) + 16 * (z & 15)) * 4 + size_t(kind)];
    }
    return tintGlobal[kind];
}

bool loadDense(Reader& r, bool v2) {
    vol.x0 = r.i32(), vol.y0 = r.i32(), vol.z0 = r.i32(), vol.sx = r.i32(), vol.sy = r.i32(), vol.sz = r.i32();
    uint32_t flags = v2 ? r.u32() : 0;
    size_t n = size_t(vol.sx) * vol.sy * vol.sz;
    if (!r.need(n * 4)) return false;
    vol.ids.resize(n);
    std::memcpy(vol.ids.data(), &r.d[r.p], n * 4);
    r.p += n * 4;
    if (flags & 1) {
        if (!r.need(n)) return false;
        vol.light.assign(r.d.begin() + long(r.p), r.d.begin() + long(r.p + n));
        r.p += n, vol.hasLight = true;
    }
    if (flags & 2) {
        if (!r.need(n)) return false;
        vol.var.assign(r.d.begin() + long(r.p), r.d.begin() + long(r.p + n));
        r.p += n, vol.hasVar = true;
    }
    if (flags & 4) {
        size_t m = size_t(vol.sx) * vol.sz * 4;
        vol.tints.resize(m);
        for (auto& t : vol.tints) t = r.u32();
        vol.hasTints = r.ok;
    }
    return r.ok;
}

// ALS2's raw column tints blended as ClientLevel.calculateBlockTint blends them: the mean over (2r + 1)^2 columns
void blendSparseTints(const std::map<std::pair<int, int>, std::vector<uint32_t>>& raw, int cx0, int cz0) {
    int r = opt.blend;
    vol.chunkAt.assign(size_t(vol.nsx) * vol.nsz, -1);
    std::vector<const std::vector<uint32_t>*> grid(size_t(vol.nsx) * vol.nsz, nullptr);
    for (auto& [c, cols] : raw) {
        int gx = c.first - cx0, gz = c.second - cz0;
        if (gx >= 0 && gz >= 0 && gx < vol.nsx && gz < vol.nsz) grid[size_t(gx + vol.nsx * gz)] = &cols;
    }
    auto rawAt = [&](int wx, int wz, int k, uint32_t& out) {
        int gx = (wx >> 4) - cx0, gz = (wz >> 4) - cz0;
        if (gx < 0 || gz < 0 || gx >= vol.nsx || gz >= vol.nsz || !grid[size_t(gx + vol.nsx * gz)]) return false;
        out = (*grid[size_t(gx + vol.nsx * gz)])[size_t((wx & 15) + 16 * (wz & 15)) * 4 + size_t(k)];
        return true;
    };
    for (auto& [c, cols] : raw) {
        int gx = c.first - cx0, gz = c.second - cz0;
        if (gx < 0 || gz < 0 || gx >= vol.nsx || gz >= vol.nsz) continue;
        vol.chunkAt[size_t(gx + vol.nsx * gz)] = int32_t(vol.ctints.size() / 1024);
        size_t base = vol.ctints.size();
        vol.ctints.resize(base + 1024);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                for (int k = 0; k < 4; k++) {
                    int wx = c.first * 16 + x, wz = c.second * 16 + z, n = 0, sr = 0, sg = 0, sb = 0;
                    for (int dz = -r; dz <= r; dz++)
                        for (int dx = -r; dx <= r; dx++) {
                            uint32_t t;
                            if (!rawAt(wx + dx, wz + dz, k, t)) continue;
                            sr += int((t >> 16) & 255), sg += int((t >> 8) & 255), sb += int(t & 255), n++;
                        }
                    vol.ctints[base + size_t(x + 16 * z) * 4 + size_t(k)] =
                        n ? uint32_t(sr / n) << 16 | uint32_t(sg / n) << 8 | uint32_t(sb / n) : cols[size_t(x + 16 * z) * 4 + size_t(k)];
                }
    }
    vol.hasTints = true;
}

bool loadSparse(Reader& r, bool v2) {
    uint32_t flags = 0, blend = 2;
    if (v2) flags = r.u32(), blend = r.u32();
    if (v2) opt.blend = int(blend);
    uint32_t n = r.u32();
    std::vector<std::array<int, 3>> at;
    int lo[3] = {1 << 30, 1 << 30, 1 << 30}, hi[3] = {-(1 << 30), -(1 << 30), -(1 << 30)};
    for (uint32_t k = 0; k < n && r.ok; k++) {
        std::array<int, 3> c{r.i32(), r.i32(), r.i32()};  // cx, sy, cz
        Section sec;
        uint16_t pn = r.u16();
        sec.pal.resize(pn);
        for (auto& v : sec.pal) v = r.u32();
        if (pn > 1) {
            sec.idx.resize(4096);
            for (auto& v : sec.idx) v = pn <= 256 ? r.u8() : r.u16();
        }
        if (v2) {
            uint8_t sf = r.u8();
            if ((sf & 1) && r.need(4096)) sec.light.assign(r.d.begin() + long(r.p), r.d.begin() + long(r.p + 4096)), r.p += 4096, vol.hasLight = true;
            if ((sf & 2) && r.need(4096)) sec.var.assign(r.d.begin() + long(r.p), r.d.begin() + long(r.p + 4096)), r.p += 4096, vol.hasVar = true;
        }
        for (int a = 0; a < 3; a++) lo[a] = std::min(lo[a], c[size_t(a)]), hi[a] = std::max(hi[a], c[size_t(a)] + 1);
        at.push_back(c);
        vol.secs.push_back(std::move(sec));
    }
    if (vol.secs.empty() || !r.ok) return false;
    vol.sparse = true;
    vol.nsx = hi[0] - lo[0], vol.nsy = hi[1] - lo[1], vol.nsz = hi[2] - lo[2];
    vol.x0 = lo[0] * 16, vol.y0 = lo[1] * 16, vol.z0 = lo[2] * 16;
    vol.sx = vol.nsx * 16, vol.sy = vol.nsy * 16, vol.sz = vol.nsz * 16;
    vol.secAt.assign(size_t(vol.nsx) * vol.nsy * vol.nsz, -1);
    for (size_t k = 0; k < at.size(); k++)
        vol.secAt[size_t((at[k][0] - lo[0]) + vol.nsx * ((at[k][2] - lo[2]) + vol.nsz * (at[k][1] - lo[1])))] = int32_t(k);
    if (v2 && (flags & 1)) {
        uint32_t nc = r.u32();
        std::map<std::pair<int, int>, std::vector<uint32_t>> raw;
        for (uint32_t k = 0; k < nc && r.ok; k++) {
            int cx = r.i32(), cz = r.i32();
            std::vector<uint32_t> cols(1024);
            for (auto& t : cols) t = r.u32();
            raw[{cx, cz}] = std::move(cols);
        }
        if (r.ok) blendSparseTints(raw, lo[0], lo[2]);
    }
    std::fprintf(stderr, "atlas_truth: sparse view: %zu sections, %d x %d x %d blocks%s\n", vol.secs.size(), vol.sx, vol.sy, vol.sz,
                 vol.hasLight ? ", with light" : "");
    return true;
}

bool loadVolume(const std::string& path) {
    Reader r;
    if (!r.load(path)) return false;
    if (r.magic("ALV1")) return loadDense(r, false);
    if (r.magic("ALV2")) return loadDense(r, true);
    if (r.magic("ALS1")) return loadSparse(r, false);
    if (r.magic("ALS2")) return loadSparse(r, true);
    return false;
}

const State& stateAt(int x, int y, int z) { return stateOf(vol.id(x, y, z)); }

// old volumes carry no light: sky light straight down the columns, then both lights spread a block at a time (the
// light engine's rule without shapes: each step costs max(1, the block's dampening))
void computeLight() {
    if (vol.sparse) {  // a view: open sky down each column to the blocks that dampen it (all-air sections pass it on)
        for (Section& s : vol.secs) s.light.assign(4096, 0);
        for (int gz = 0; gz < vol.nsz; gz++)
            for (int gx = 0; gx < vol.nsx; gx++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        int sky = 15, x = gx * 16 + lx, z = gz * 16 + lz;
                        for (int gy = vol.nsy - 1; gy >= 0 && sky > 0; gy--) {
                            Section* c = vol.section(gx, gy, gz);
                            if (!c) continue;
                            for (int ly = 15; ly >= 0; ly--) {
                                const State& st = stateAt(x, gy * 16 + ly, z);
                                if (st.damp) sky = std::max(0, sky - std::max<int>(1, st.damp));
                                c->light[Volume::local(x, ly, z)] = uint8_t(sky << 4 | std::min<int>(15, st.emit));
                            }
                        }
                    }
        return;
    }
    size_t n = vol.ids.size();
    std::vector<uint8_t> sky(n, 0), block(n, 0);
    std::vector<uint32_t> q;
    for (int z = 0; z < vol.sz; z++)
        for (int x = 0; x < vol.sx; x++) {
            int s = 15;
            for (int y = vol.sy - 1; y >= 0; y--) {
                const State& st = stateAt(x, y, z);
                if (st.damp) s = std::max(0, s - std::max<int>(1, st.damp));
                sky[vol.dense(x, y, z)] = uint8_t(s);
            }
        }
    auto spread = [&](std::vector<uint8_t>& L) {
        q.clear();
        for (size_t i = 0; i < n; i++)
            if (L[i] > 1) q.push_back(uint32_t(i));
        for (size_t h = 0; h < q.size(); h++) {
            uint32_t i = q[h];
            int x = int(i % uint32_t(vol.sx)), z = int((i / uint32_t(vol.sx)) % uint32_t(vol.sz)), y = int(i / (uint32_t(vol.sx) * uint32_t(vol.sz)));
            for (int d = 0; d < 6; d++) {
                int nx = x + DV[d][0], ny = y + DV[d][1], nz = z + DV[d][2];
                if (!vol.inside(nx, ny, nz)) continue;
                size_t j = vol.dense(nx, ny, nz);
                int v = int(L[i]) - std::max<int>(1, stateAt(nx, ny, nz).damp);
                if (v > int(L[j])) L[j] = uint8_t(v), q.push_back(uint32_t(j));
            }
        }
    };
    for (size_t i = 0; i < n; i++) block[i] = std::min<uint8_t>(15, stateOf(vol.ids[i]).emit);
    spread(sky);
    spread(block);
    vol.light.resize(n);
    for (size_t i = 0; i < n; i++) vol.light[i] = uint8_t(sky[i] << 4 | block[i]);
}

// ---- the offset of plants (BlockBehaviour.offsetType, Mth.getSeed) ----------------------------------------------

int64_t mthSeed(int x, int y, int z) {
    int32_t xi = int32_t(uint32_t(x) * 3129871u);  // Java int multiplication, wrapping
    int64_t seed = int64_t(xi) ^ int64_t(uint64_t(int64_t(z)) * 116129781ull) ^ int64_t(y);
    seed = int64_t(uint64_t(seed) * uint64_t(seed) * 42317861ull + uint64_t(seed) * 11ull);
    return seed >> 16;
}

V3 offsetOf(const State& s, int wx, int wz) {
    if (!s.offType) return {0, 0, 0};
    int64_t seed = mthSeed(wx, 0, wz);
    double x = std::clamp((double(float(seed & 15) / 15.0f) - 0.5) * 0.5, double(-s.maxH), double(s.maxH));
    double z = std::clamp((double(float((seed >> 8) & 15) / 15.0f) - 0.5) * 0.5, double(-s.maxH), double(s.maxH));
    double y = s.offType == 2 ? (double(float((seed >> 4) & 15) / 15.0f) - 1.0) * double(s.maxV) : 0.0;
    return {float(x), float(y), float(z)};
}

const Model* modelAt(const State& s, int x, int y, int z) {
    if (!s.has(F_MODEL) || s.variants.empty()) return nullptr;
    int v = vol.variant(x, y, z);
    int m = s.variants[size_t(v < int(s.variants.size()) ? v : 0)];
    return m >= 0 && size_t(m) < models.size() ? &models[size_t(m)] : nullptr;
}

// mark the cells a block's geometry reaches beyond its own (offset plants, models larger than a block)
void markOverflow() {
    auto mark = [&](int x, int y, int z) {
        const State& s = stateAt(x, y, z);
        if (!s.overflow) return;
        const Model* m = modelAt(s, x, y, z);
        V3 lo = m ? m->lo : V3{0, 0, 0}, hi = m ? m->hi : V3{1, 1, 1};
        if (s.offType) lo.x -= s.maxH, lo.z -= s.maxH, hi.x += s.maxH, hi.z += s.maxH, lo.y -= s.offType == 2 ? s.maxV : 0;
        for (int cy = int(std::floor(y + lo.y)); cy <= int(std::floor(y + hi.y - 1e-4f)); cy++)
            for (int cz = int(std::floor(z + lo.z)); cz <= int(std::floor(z + hi.z - 1e-4f)); cz++)
                for (int cx = int(std::floor(x + lo.x)); cx <= int(std::floor(x + hi.x - 1e-4f)); cx++)
                    if (cx != x || cy != y || cz != z) vol.touch(cx, cy, cz);
    };
    if (!vol.sparse) {
        for (int y = 0; y < vol.sy; y++)
            for (int z = 0; z < vol.sz; z++)
                for (int x = 0; x < vol.sx; x++) mark(x, y, z);
        return;
    }
    for (int gy = 0; gy < vol.nsy; gy++)
        for (int gz = 0; gz < vol.nsz; gz++)
            for (int gx = 0; gx < vol.nsx; gx++) {
                const Section* c = vol.section(gx, gy, gz);
                if (!c) continue;
                bool any = false;
                for (uint32_t id : c->pal) any |= stateOf(id).overflow;
                if (!any) continue;
                for (int i = 0; i < 4096; i++) mark(gx * 16 + (i & 15), gy * 16 + (i >> 8), gz * 16 + ((i >> 4) & 15));
            }
}

// ---- culling (Block.shouldRenderFace) ------------------------------------------------------------------------------

bool sameBlockSkip(const State& s, const State& n) {
    return s.block == n.block || (!opt.cutoutLeaves && s.has(F_LEAVES) && n.has(F_LEAVES));
}

// whether a quad culled against face d of the block at (x, y, z) is drawn
bool faceVisible(const State& s, int x, int y, int z, int d) {
    const State& n = stateAt(x + DV[d][0], y + DV[d][1], z + DV[d][2]);
    int o = d ^ 1;
    if (n.faceFull & (1 << o)) return false;  // the neighbour's face is the full block
    if ((s.skipSame & (1 << d)) && sameBlockSkip(s, n)) return false;
    if (!((n.faceFull | n.facePartial) & (1 << o))) return true;  // nothing there
    if (!((s.faceFull | s.facePartial) & (1 << d))) return true;  // the face itself occludes nothing
    for (int r = 0; r < 16; r++)  // drawn when some of its own face shape is not covered by the neighbour's
        if (s.mask[size_t(d)][size_t(r)] & ~n.mask[size_t(o)][size_t(r)]) return true;
    return false;
}

// ---- lighting: LightCoordsUtil, NeoForge's FullFaceCalculator / AoFace / EnhancedBlockModelLighter ---------------

const int FULL_BRIGHT = 15728880;
inline int packL(int block, int sky) { return block << 4 | sky << 20; }
inline int sBlock(int c) { return c & 0xFF; }
inline int sSky(int c) { return (c >> 16) & 0xFF; }
inline int smoothPack(int block, int sky) { return (block & 0xFF) | (sky & 0xFF) << 16; }

// LightCoordsUtil.getLightCoords: emissive rendering is full bright, else the light at pos with the state's emission
int lightCoords(const State& st, int x, int y, int z) {
    if (st.has(F_EMISSIVE)) return FULL_BRIGHT;
    uint8_t l = vol.lightAt(x, y, z);
    return packL(std::max<int>(l & 15, st.emit), l >> 4);
}

int withEmission(int c, int e) {
    if (e == 0) return c;
    return packL(std::max((c >> 4) & 15, e), std::max((c >> 20) & 15, e));
}

int maxL(int a, int b) { return smoothPack(std::max(sBlock(a), sBlock(b)), std::max(sSky(a), sSky(b))); }

int lerpL(int a, float wa, int b, float wb) {
    int block = int(std::lround(float(sBlock(a)) * wa + float(sBlock(b)) * wb)) & 0xFF;
    int sky = int(std::lround(float(sSky(a)) * wa + float(sSky(b)) * wb)) & 0xFF;
    return smoothPack(block, sky);
}

int weightedBlend(const int c[4], const float w[4]) {
    float sky = 0, block = 0;
    for (int i = 0; i < 4; i++) sky += float(sSky(c[i])) * w[i], block += float(sBlock(c[i])) * w[i];
    return smoothPack(int(block), int(sky));
}

// FullFaceCalculator.blend: the improved corner blend (light from closed blocks raised to the minimum of the open ones)
int blendCorner(int a, int b, int c, int in, bool ca, bool cb, bool cc, bool ci) {
    int minB = 0x10000, minS = 0x10000;
    if (ca) minB = sBlock(a), minS = sSky(a);
    if (cb) minB = std::min(minB, sBlock(b)), minS = std::min(minS, sSky(b));
    if (cc) minB = std::min(minB, sBlock(c)), minS = std::min(minS, sSky(c));
    if (ci) minB = std::min(minB, sBlock(in)), minS = std::min(minS, sSky(in));
    minB &= 0xFFFF, minS &= 0xFFFF;
    a = smoothPack(std::max(minB, sBlock(a)), std::max(minS, sSky(a)));
    b = smoothPack(std::max(minB, sBlock(b)), std::max(minS, sSky(b)));
    c = smoothPack(std::max(minB, sBlock(c)), std::max(minS, sSky(c)));
    in = smoothPack(std::max(minB, sBlock(in)), std::max(minS, sSky(in)));
    return ((a + b + c + in) >> 2) & 0xFF00FF;
}

// BlockModelLighter.AdjacencyInfo's "corners" (the four sides of each face)
const int SIDES[6][4] = {{WEST, EAST, NORTH, SOUTH}, {EAST, WEST, NORTH, SOUTH}, {UP, DOWN, EAST, WEST},
                         {WEST, EAST, DOWN, UP},     {UP, DOWN, NORTH, SOUTH},   {DOWN, UP, NORTH, SOUTH}};

struct AoResult {
    float b[4];
    int l[4];
};

AoResult calcFace(int x, int y, int z, const State& rendered, int dir, int shadeOverride, bool outside) {
    int px = x + (outside ? DV[dir][0] : 0), py = y + (outside ? DV[dir][1] : 0), pz = z + (outside ? DV[dir][2] : 0);
    float sb[4];
    int sl[4];
    bool sc[4];
    for (int i = 0; i < 4; i++) {
        int d = SIDES[dir][i];
        int qx = px + DV[d][0], qy = py + DV[d][1], qz = pz + DV[d][2];
        const State& st = stateAt(qx, qy, qz);
        sl[i] = lightCoords(st, qx, qy, qz), sb[i] = st.shade, sc[i] = st.has(F_LIGHT_PERMEABLE);
    }
    auto corner = [&](int s1, int s2, int fallback, float& b, int& l, bool& c) {
        if (!sc[s2] && !sc[s1]) {
            b = sb[fallback], l = sl[fallback], c = false;
            return;
        }
        int d1 = SIDES[dir][s1], d2 = SIDES[dir][s2];
        int qx = px + DV[d1][0] + DV[d2][0], qy = py + DV[d1][1] + DV[d2][1], qz = pz + DV[d1][2] + DV[d2][2];
        const State& st = stateAt(qx, qy, qz);
        b = st.shade, l = lightCoords(st, qx, qy, qz), c = st.has(F_LIGHT_PERMEABLE);
    };
    float cb[4];
    int cl[4];
    bool cc[4];
    corner(0, 2, 0, cb[0], cl[0], cc[0]);
    corner(0, 3, 0, cb[1], cl[1], cc[1]);
    corner(1, 2, 1, cb[2], cl[2], cc[2]);
    corner(1, 3, 1, cb[3], cl[3], cc[3]);
    const State& in = outside ? stateAt(px, py, pz) : rendered;
    float ib = in.shade;
    int il = lightCoords(in, px, py, pz);
    bool ic = in.has(F_LIGHT_PERMEABLE);
    float lb = CARD[shadeOverride != NONE ? shadeOverride : dir];
    AoResult r;
    r.b[0] = (sb[3] + sb[0] + cb[1] + ib) * 0.25f * lb;
    r.b[1] = (sb[2] + sb[0] + cb[0] + ib) * 0.25f * lb;
    r.b[2] = (sb[2] + sb[1] + cb[2] + ib) * 0.25f * lb;
    r.b[3] = (sb[3] + sb[1] + cb[3] + ib) * 0.25f * lb;
    r.l[0] = blendCorner(sl[3], sl[0], cl[1], il, sc[3], sc[0], cc[1], ic);
    r.l[1] = blendCorner(sl[2], sl[0], cl[0], il, sc[2], sc[0], cc[0], ic);
    r.l[2] = blendCorner(sl[2], sl[1], cl[2], il, sc[2], sc[1], cc[2], ic);
    r.l[3] = blendCorner(sl[3], sl[1], cl[3], il, sc[3], sc[1], cc[3], ic);
    return r;
}

// AoFace.computeCornerWeights
void cornerWeights(int dir, V3 p, float w[4]) {
    float x = std::clamp(p.x, 0.f, 1.f), y = std::clamp(p.y, 0.f, 1.f), z = std::clamp(p.z, 0.f, 1.f);
    switch (dir) {
        case DOWN: w[0] = (1 - x) * z, w[1] = (1 - x) * (1 - z), w[2] = x * (1 - z), w[3] = x * z; break;
        case UP: w[0] = x * z, w[1] = x * (1 - z), w[2] = (1 - x) * (1 - z), w[3] = (1 - x) * z; break;
        case NORTH: w[0] = y * (1 - x), w[1] = y * x, w[2] = (1 - y) * x, w[3] = (1 - y) * (1 - x); break;
        case SOUTH: w[0] = y * (1 - x), w[1] = (1 - y) * (1 - x), w[2] = (1 - y) * x, w[3] = y * x; break;
        case WEST: w[0] = y * z, w[1] = y * (1 - z), w[2] = (1 - y) * (1 - z), w[3] = (1 - y) * z; break;
        default: w[0] = (1 - y) * z, w[1] = (1 - y) * (1 - z), w[2] = y * (1 - z), w[3] = y * z; break;
    }
}

float depthIn(int dir, V3 p) {
    switch (dir) {
        case DOWN: return p.y;
        case UP: return 1 - p.y;
        case NORTH: return p.z;
        case SOUTH: return 1 - p.z;
        case WEST: return p.x;
        default: return 1 - p.x;
    }
}

struct VertLight {
    float b[4];  // brightness (shade x AO)
    int l[4];    // light coords (smooth: block and sky 0..240)
    int mode = 0;  // 0 flat, 1 smooth (axis aligned), 2 smooth (irregular)
};

// ModelBlockRenderer's choice per quad, then EnhancedBlockModelLighter's prepareQuadFlat / prepareQuadAmbientOcclusion
VertLight lightQuad(const State& st, int x, int y, int z, const Quad& q, const Model& m) {
    VertLight r;
    int E = st.emit;
    bool useAo = opt.ao && (opt.perPartAo || m.firstAo == 1 || (m.firstAo == 2 && E == 0));
    int partMode = (q.ao >> 1) & 3;
    bool partAo = !opt.perPartAo || partMode == 1 || (partMode == 2 && E == 0);
    bool smooth = useAo && partAo && (q.ao & 1);
    float mn[3] = {32, 32, 32}, mx[3] = {-32, -32, -32};
    for (int i = 0; i < 4; i++)
        for (int a = 0; a < 3; a++) mn[a] = std::min(mn[a], q.p[i][a]), mx[a] = std::max(mx[a], q.p[i][a]);
    bool full = st.has(F_COLLISION_FULL);
    int ax = axisOf(q.dir);
    bool positive = q.dir == UP || q.dir == SOUTH || q.dir == EAST;
    // BlockModelLighter.prepareQuadShape's faceCubic: flat on the block's face (or a full block): light from outside
    bool cubic = mn[ax] == mx[ax] && ((positive ? mx[ax] > 0.9999f : mn[ax] < 1e-4f) || full);
    if (!smooth) {
        int lc;
        if (q.cull != NONE && !(useAo && partAo)) {  // culled faces without AO: the light of the block they face
            lc = lightCoords(st, x + DV[q.cull][0], y + DV[q.cull][1], z + DV[q.cull][2]);
        } else {
            int lx = x, ly = y, lz = z;
            if (cubic) lx += DV[q.dir][0], ly += DV[q.dir][1], lz += DV[q.dir][2];
            lc = lightCoords(st, lx, ly, lz);
        }
        for (int i = 0; i < 4; i++) {
            r.l[i] = lc;
            if (q.shade != NONE) {
                r.b[i] = CARD[q.shade];
            } else {  // per vertex: the cardinal shades weighted by the normal's squared components
                float w = 0;
                for (int a = 0; a < 3; a++) {
                    float c = q.n[i][a];
                    if (c == 0) continue;
                    int d = a == 0 ? (c > 0 ? EAST : WEST) : a == 1 ? (c > 0 ? UP : DOWN) : (c > 0 ? SOUTH : NORTH);
                    w += CARD[d] * c * c;
                }
                r.b[i] = std::min(w, 1.f);
            }
        }
        return r;
    }
    if (mn[ax] == mx[ax]) {  // axis aligned: one full face, interpolated at each vertex
        r.mode = 1;
        AoResult f = calcFace(x, y, z, st, q.dir, q.shade, cubic);
        for (int i = 0; i < 4; i++) {
            float w[4];
            cornerWeights(q.dir, q.p[i], w);
            r.b[i] = std::clamp(f.b[0] * w[0] + f.b[1] * w[1] + f.b[2] * w[2] + f.b[3] * w[3], 0.f, 1.f);
            r.l[i] = weightedBlend(f.l, w);
        }
        return r;
    }
    r.mode = 2;  // irregular: each axis of the vertex normal, weighted by its square, mixed with the brightest
    for (int i = 0; i < 4; i++) {
        float wb = 0, maxB = 0;
        int wl = 0, ml = 0;
        for (int a = 0; a < 3; a++) {
            float c = q.n[i][a];
            if (c == 0) continue;
            int d = a == 0 ? (c > 0 ? EAST : WEST) : a == 1 ? (c > 0 ? UP : DOWN) : (c > 0 ? SOUTH : NORTH);
            bool outside = depthIn(d, q.p[i]) < 1e-4f || full;
            AoResult f = calcFace(x, y, z, st, d, q.shade, outside);
            float w[4];
            cornerWeights(d, q.p[i], w);
            float b = std::clamp(f.b[0] * w[0] + f.b[1] * w[1] + f.b[2] * w[2] + f.b[3] * w[3], 0.f, 1.f);
            int l = weightedBlend(f.l, w);
            wb += b * c * c;
            wl = lerpL(wl, 1, l, c * c);
            maxB = std::max(maxB, b);
            ml = maxL(ml, l);
        }
        r.b[i] = std::clamp(wb * 0.75f + maxB * 0.25f, 0.f, 1.f);
        r.l[i] = lerpL(wl, 0.75f, ml, 0.25f);
    }
    return r;
}

// ---- the lightmap (lightmap.fsh) -----------------------------------------------------------------------------------

struct LightmapParams {
    float skyFactor = 1, blockFactor = 1.4f, gamma = 0.5f;
    RGB skyLight{1, 1, 1}, blockTint{1.f, 216.f / 255.f, 140.f / 255.f}, ambient{10.f / 255.f, 10.f / 255.f, 10.f / 255.f};
} lmp;
RGB lightmap[16][16];  // [sky][block], 8-bit quantised as the texture holds it

void buildLightmap() {
    auto bright = [](float l) { return l / (4.f - 3.f * l); };
    for (int s = 0; s < 16; s++)
        for (int b = 0; b < 16; b++) {
            float bl = float(b) / 15.f, sl = float(s) / 15.f;
            float bb = bright(bl) * lmp.blockFactor, skb = bright(sl) * lmp.skyFactor;
            float mixf = 0.9f * (2 * bl - 1) * (2 * bl - 1);
            RGB c = lmp.ambient;
            c.r += lmp.skyLight.r * skb, c.g += lmp.skyLight.g * skb, c.b += lmp.skyLight.b * skb;
            c.r += (lmp.blockTint.r + (1 - lmp.blockTint.r) * mixf) * bb;
            c.g += (lmp.blockTint.g + (1 - lmp.blockTint.g) * mixf) * bb;
            c.b += (lmp.blockTint.b + (1 - lmp.blockTint.b) * mixf) * bb;
            c.r = std::clamp(c.r, 0.f, 1.f), c.g = std::clamp(c.g, 0.f, 1.f), c.b = std::clamp(c.b, 0.f, 1.f);
            float m = std::max({c.r, c.g, c.b});
            if (m > 0) {  // notGamma, mixed in by the brightness option
                float inv = 1 - m, scaled = 1 - inv * inv * inv * inv, k = scaled / m;
                c.r += (c.r * k - c.r) * lmp.gamma, c.g += (c.g * k - c.g) * lmp.gamma, c.b += (c.b * k - c.b) * lmp.gamma;
            }
            auto q8 = [](float v) { return std::round(std::clamp(v, 0.f, 1.f) * 255.f) / 255.f; };
            lightmap[s][b] = {q8(c.r), q8(c.g), q8(c.b)};
        }
}

// sample_lightmap: UV2 / 256 + 1/32 with linear filtering, i.e. bilinear between whole light levels
RGB sampleLightmap(int c) {
    float fb = std::clamp(float(sBlock(c)) / 16.f, 0.f, 15.f), fs = std::clamp(float(sSky(c)) / 16.f, 0.f, 15.f);
    int b0 = int(fb), s0 = int(fs), b1 = std::min(15, b0 + 1), s1 = std::min(15, s0 + 1);
    float tb = fb - float(b0), ts = fs - float(s0);
    auto mix = [](RGB a, RGB b, float t) { return RGB{a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t}; };
    return mix(mix(lightmap[s0][b0], lightmap[s0][b1], tb), mix(lightmap[s1][b0], lightmap[s1][b1], tb), ts);
}

// the overworld's day (Timelines: sky light colour and factor keyframes, linear between them)
void timeOfDay(float t) {
    struct K {
        float t, f;
        RGB c;
    };
    const RGB night{0.48f, 0.48f, 1.f}, day{1, 1, 1};
    const K ks[] = {{730, 1, day}, {11270, 1, day}, {13140, 0.24f, night}, {22860, 0.24f, night}, {730 + 24000, 1, day}};
    t = std::fmod(std::fmod(t, 24000.f) + 24000.f, 24000.f);
    if (t < 730) t += 24000;
    for (int i = 0; i + 1 < 5; i++)
        if (t >= ks[i].t && t <= ks[i + 1].t) {
            float f = (t - ks[i].t) / (ks[i + 1].t - ks[i].t);
            lmp.skyFactor = ks[i].f + (ks[i + 1].f - ks[i].f) * f;
            lmp.skyLight = {ks[i].c.r + (ks[i + 1].c.r - ks[i].c.r) * f, ks[i].c.g + (ks[i + 1].c.g - ks[i].c.g) * f,
                            ks[i].c.b + (ks[i + 1].c.b - ks[i].c.b) * f};
            return;
        }
}

// ---- tint (BlockColors through the column's biome tints) ----------------------------------------------------------

uint32_t tintOf(const Tint& t, int x, int z) {
    switch (t.kind) {
        case 1: return 0xFF000000u | columnTint(x, z, 0);
        case 2: return 0xFF000000u | columnTint(x, z, 1);
        case 3: return 0xFF000000u | columnTint(x, z, 2);
        case 4: return 0xFF000000u | columnTint(x, z, 3);
        case 5: return t.c;
        default: return 0xFFFFFFFFu;
    }
}

// ARGB.multiply on the RGB bytes
uint32_t mul8(uint32_t a, uint32_t b) {
    uint32_t r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
    return 0xFF000000u | r << 16 | g << 8 | bl;
}
uint32_t gray8(float f) {
    uint32_t c = uint32_t(std::clamp(int(std::floor(f * 255.f)), 0, 255));
    return 0xFF000000u | c << 16 | c << 8 | c;
}
uint32_t scale8(uint32_t c, float s) {
    auto ch = [&](uint32_t v) { return uint32_t(std::clamp(int(float(v) * s), 0, 255)); };
    return 0xFF000000u | ch((c >> 16) & 255) << 16 | ch((c >> 8) & 255) << 8 | ch(c & 255);
}

// ---- fluids (FluidRenderer) ----------------------------------------------------------------------------------------

bool sameFluid(const State& a, const State& b) { return a.fluid >= 0 && b.fluid >= 0 && a.fluid == b.fluid; }

float fluidHeight(const State& self, int x, int y, int z) {
    const State& s = stateAt(x, y, z);
    if (sameFluid(self, s)) return sameFluid(self, stateAt(x, y + 1, z)) ? 1.f : s.fluidH;
    return !s.has(F_LEGACY_SOLID) ? 0.f : -1.f;
}

float averageHeight(const State& self, float hSelf, float h2, float h1, int cx, int cy, int cz) {
    if (h1 >= 1 || h2 >= 1) return 1;
    float w[2] = {0, 0};
    auto add = [&](float h) {
        if (h >= 0.8f) w[0] += h * 10, w[1] += 10;
        else if (h >= 0) w[0] += h, w[1] += 1;
    };
    if (h1 > 0 || h2 > 0) {
        float hc = fluidHeight(self, cx, cy, cz);
        if (hc >= 1) return 1;
        add(hc);
    }
    add(hSelf), add(h1), add(h2);
    return w[0] / w[1];
}

// isFaceOccludedByState: the occluder's face (towards the fluid) covers the fluid's box face up to height
bool fluidFaceOccluded(int dir, float height, const State& st) {
    int o = dir ^ 1;
    if (st.faceFull & (1 << o)) return dir != UP || height == 1.f;
    if (!(st.facePartial & (1 << o))) return false;
    if (dir == UP && std::fabs(height - 1.f) > 1e-7f) return false;
    for (int b = 0; b < 16; b++) {
        uint16_t need = dir <= UP || (float(b) + 0.5f) / 16.f < height ? 0xFFFF : 0;
        if (need & ~st.mask[size_t(o)][size_t(b)]) return false;
    }
    return true;
}

V3 fluidFlow(const State& self, int x, int y, int z) {
    float fx = 0, fz = 0;
    for (int d = NORTH; d <= EAST; d++) {
        int nx = x + DV[d][0], nz = z + DV[d][2];
        const State& n = stateAt(nx, y, nz);
        if (n.fluid >= 0 && !sameFluid(self, n)) continue;  // affectsFlow: empty or the same fluid
        float nh = n.fluid >= 0 ? n.fluidH : 0, dist = 0;
        if (nh == 0) {
            if (!n.has(F_SOLID_RENDER)) {
                const State& b = stateAt(nx, y - 1, nz);
                if (b.fluid < 0 || sameFluid(self, b)) {
                    float bh = b.fluid >= 0 ? b.fluidH : 0;
                    if (bh > 0) dist = self.fluidH - (bh - 0.8888889f);
                }
            }
        } else if (nh > 0) {
            dist = self.fluidH - nh;
        }
        if (dist != 0) fx += float(DV[d][0]) * dist, fz += float(DV[d][2]) * dist;
    }
    float l = std::sqrt(fx * fx + fz * fz);
    return l < 1e-4f ? V3{0, 0, 0} : V3{fx / l, 0, fz / l};
}

int fluidLight(int x, int y, int z) {
    int a = lightCoords(stateAt(x, y, z), x, y, z), b = lightCoords(stateAt(x, y + 1, z), x, y + 1, z);
    return packL(std::max((a >> 4) & 15, (b >> 4) & 15), std::max((a >> 20) & 15, (b >> 20) & 15));
}

void fluidQuads(const State& self, int x, int y, int z, std::vector<Quad>& out) {
    const FluidModel& fm = fluidModels[size_t(self.fluid)];
    const State& dn = stateAt(x, y - 1, z);
    const State& upS = stateAt(x, y + 1, z);
    const State* nb[6] = {&dn, &upS, &stateAt(x, y, z - 1), &stateAt(x, y, z + 1), &stateAt(x - 1, y, z), &stateAt(x + 1, y, z)};
    // shouldRenderFace: not against the same fluid, nor where the fluid's own block (a waterlogged stair) covers the face
    auto shouldRender = [&](int d) { return !sameFluid(self, *nb[d]) && !fluidFaceOccluded(d ^ 1, 1.f, self); };
    bool rUp = !sameFluid(self, upS);
    bool rDown = shouldRender(DOWN) && !fluidFaceOccluded(DOWN, 0.8888889f, dn);
    bool rSide[6] = {false, false, shouldRender(NORTH), shouldRender(SOUTH), shouldRender(WEST), shouldRender(EAST)};
    if (!rUp && !rDown && !rSide[2] && !rSide[3] && !rSide[4] && !rSide[5]) return;
    uint32_t tint = 0xFFFFFFFFu;
    if (fm.tintKind) tint = tintOf({fm.tintKind, fm.tintConst}, x, z);
    float hSelf = fluidHeight(self, x, y, z), hNE = 1, hNW = 1, hSE = 1, hSW = 1;
    if (hSelf < 1) {
        float hN = fluidHeight(self, x, y, z - 1), hS = fluidHeight(self, x, y, z + 1), hE = fluidHeight(self, x + 1, y, z),
              hW = fluidHeight(self, x - 1, y, z);
        hNE = averageHeight(self, hSelf, hN, hE, x + 1, y, z - 1);
        hNW = averageHeight(self, hSelf, hN, hW, x - 1, y, z - 1);
        hSE = averageHeight(self, hSelf, hS, hE, x + 1, y, z + 1);
        hSW = averageHeight(self, hSelf, hS, hW, x - 1, y, z + 1);
    }
    float bottom = rDown ? 0.001f : 0.f;
    auto face = [&](V3 p0, float u0, float v0, V3 p1, float u1, float v1, V3 p2, float u2, float v2, V3 p3, float u3, float v3,
                    int sprite, uint32_t color, int light, int dir, bool back) {
        Quad q;
        V3 ps[4] = {p0, p1, p2, p3};
        float us[4] = {u0, u1, u2, u3}, vs[4] = {v0, v1, v2, v3};
        for (int i = 0; i < 4; i++) q.p[i] = ps[i], q.u[i] = us[i], q.v[i] = vs[i];
        q.sprite = sprite, q.dir = uint8_t(dir), q.layer = fm.layer, q.baked = true, q.bakedColor = color, q.bakedLight = light;
        finishQuad(q);
        out.push_back(q);
        if (back) {  // addFace's back face: the same vertices in reverse order
            Quad b = q;
            int order[4] = {0, 3, 2, 1};
            for (int i = 0; i < 4; i++) b.p[i] = q.p[order[i]], b.u[i] = q.u[order[i]], b.v[i] = q.v[order[i]];
            b.dir = uint8_t(dir ^ 1);
            out.push_back(b);
        }
    };
    if (rUp && !fluidFaceOccluded(UP, std::min(std::min(hNW, hSW), std::min(hSE, hNE)), upS)) {
        hNW -= 0.001f, hSW -= 0.001f, hSE -= 0.001f, hNE -= 0.001f;
        V3 flow = fluidFlow(self, x, y, z);
        float u00, u01, u10, u11, v00, v01, v10, v11;
        int sp;
        if (flow.x == 0 && flow.z == 0) {
            sp = fm.still;
            u00 = 0, v00 = 0, u01 = 0, v01 = 1, u10 = 1, v10 = 1, u11 = 1, v11 = 0;
        } else {
            sp = fm.flow;
            float ang = std::atan2(flow.z, flow.x) - 3.14159265f / 2, s = std::sin(ang) * 0.25f, c = std::cos(ang) * 0.25f;
            u00 = 0.5f + (-c - s), v00 = 0.5f + (-c + s), u01 = 0.5f + (-c + s), v01 = 0.5f + (c + s);
            u10 = 0.5f + (c + s), v10 = 0.5f + (c - s), u11 = 0.5f + (c - s), v11 = 0.5f + (-c - s);
        }
        // shouldRenderBackwardUpFace: the surface seen from below where the block above lets it be seen
        bool back = !upS.has(F_SOLID_RENDER) && !sameFluid(self, upS);
        face({0, hNW, 0}, u00, v00, {0, hSW, 1}, u01, v01, {1, hSE, 1}, u10, v10, {1, hNE, 0}, u11, v11, sp, scale8(tint, CARD[UP]),
             fluidLight(x, y, z), UP, back);
    }
    if (rDown)
        face({0, bottom, 0}, 0, 0, {1, bottom, 0}, 1, 0, {1, bottom, 1}, 1, 1, {0, bottom, 1}, 0, 1, fm.still, scale8(tint, CARD[DOWN]),
             fluidLight(x, y - 1, z), DOWN, false);
    int side = fluidLight(x, y, z);
    for (int d = NORTH; d <= EAST; d++) {
        float h0, h1, x0, z0, x1, z1;
        switch (d) {
            case NORTH: h0 = hNW, h1 = hNE, x0 = 0, x1 = 1, z0 = z1 = 0.001f; break;
            case SOUTH: h0 = hSE, h1 = hSW, x0 = 1, x1 = 0, z0 = z1 = 1 - 0.001f; break;
            case WEST: h0 = hSW, h1 = hNW, x0 = x1 = 0.001f, z0 = 1, z1 = 0; break;
            default: h0 = hNE, h1 = hSE, x0 = x1 = 1 - 0.001f, z0 = 0, z1 = 1; break;
        }
        if (!rSide[d] || fluidFaceOccluded(d, std::max(h0, h1), *nb[d])) continue;
        int sp = fm.flow;
        bool overlay = false;
        if (fm.overlay >= 0 && nb[d]->has(F_FLUID_OVERLAY)) sp = fm.overlay, overlay = true;
        float shadeSide = d <= SOUTH ? CARD[NORTH] : CARD[WEST];
        face({x0, h0, z0}, 0, (1 - h0) * 0.5f, {x1, h1, z1}, 0.5f, (1 - h1) * 0.5f, {x1, bottom, z1}, 0.5f, 0.5f, {x0, bottom, z0}, 0, 0.5f, sp,
             scale8(tint, CARD[UP] * shadeSide), side, d, !overlay);
    }
}

// ---- tracing -------------------------------------------------------------------------------------------------------

struct Hit {
    bool hit = false;
    float t = 1e30f;
    int bx = 0, by = 0, bz = 0;
    const State* st = nullptr;
    const Model* model = nullptr;
    Quad q;  // a copy: fluid faces are built per ray
    int tri = 0;
    float b1 = 0, b2 = 0;
    RGBA tex{};
    uint8_t layer = SOLID;
};

float pixelAngle = 0.001f;  // the camera's pixel size on the tangent plane (the mip level's scale)

// one-sided ray / quad (its two triangles, 0-1-2 and 0-2-3 as the GPU draws a quad), nearest beyond tmin
bool hitQuad(const Quad& q, V3 base, V3 o, V3 d, float tmin, float& t, int& tri, float& b1, float& b2) {
    bool any = false;
    for (int k = 0; k < 2; k++) {
        V3 v0 = q.p[0] + base, v1 = q.p[k == 0 ? 1 : 2] + base, v2 = q.p[k == 0 ? 2 : 3] + base;
        V3 e1 = v1 - v0, e2 = v2 - v0, pv = cross(d, e2);
        float det = dot(e1, pv);
        if (det <= 1e-12f) continue;  // back face or edge-on: culled as the terrain pipelines cull
        float inv = 1.f / det;
        V3 tv = o - v0;
        float u = dot(tv, pv) * inv;
        if (u < 0 || u > 1) continue;
        V3 qv = cross(tv, e1);
        float v = dot(d, qv) * inv;
        if (v < 0 || u + v > 1) continue;
        float tt = dot(e2, qv) * inv;
        if (tt <= tmin || tt >= t) continue;
        t = tt, tri = k, b1 = u, b2 = v, any = true;
    }
    return any;
}

// the vertex indices and weights of a hit inside its triangle
void triWeights(int tri, float b1, float b2, int idx[3], float w[3]) {
    idx[0] = 0, idx[1] = tri == 0 ? 1 : 2, idx[2] = tri == 0 ? 2 : 3;
    w[0] = 1 - b1 - b2, w[1] = b1, w[2] = b2;
}

// test one block's quads (and fluid faces) against the ray; keep the nearest that survives its layer's alpha test
void testBlock(int x, int y, int z, V3 o, V3 d, float tmin, Hit& best, std::vector<Quad>& scratch) {
    const State& st = stateAt(x, y, z);
    if (st.has(F_AIR) && st.fluid < 0) return;
    V3 base{float(x), float(y), float(z)};
    auto consider = [&](const Quad& q, V3 at, const Model* m) {
        float t = best.t, b1, b2;
        int tri;
        if (!hitQuad(q, at, o, d, tmin, t, tri, b1, b2)) return;
        int idx[3];
        float w[3];
        triWeights(tri, b1, b2, idx, w);
        float u = 0, v = 0;
        for (int i = 0; i < 3; i++) u += q.u[idx[i]] * w[i], v += q.v[idx[i]] * w[i];
        // the mip level: texels per pixel along the most stretched screen axis
        V3 nrm = cross(q.p[idx[1]] - q.p[idx[0]], q.p[idx[2]] - q.p[idx[0]]);
        float cosT = std::fabs(dot(norm(nrm), d));
        float lod = std::log2(std::max(1e-6f, q.rho * t * pixelAngle / std::max(cosT, 0.02f)));
        RGBA tex = sample(q.sprite, u, v, lod);
        uint8_t layer = q.layer;
        if (!opt.cutoutLeaves && st.has(F_LEAVES)) layer = SOLID;  // ModelBlockRenderer.forceOpaque
        if (layer == CUTOUT && tex[3] < 0.5f) return;
        if (layer == TRANSLUCENT && tex[3] < 0.1f) return;
        best.hit = true, best.t = t, best.bx = x, best.by = y, best.bz = z, best.st = &st, best.model = m, best.q = q, best.tri = tri;
        best.b1 = b1, best.b2 = b2, best.tex = tex, best.layer = layer;
    };
    if (st.fluid >= 0 && size_t(st.fluid) < fluidModels.size()) {
        scratch.clear();
        fluidQuads(st, x, y, z, scratch);
        for (const Quad& q : scratch) consider(q, base, nullptr);
    }
    const Model* m = modelAt(st, x, y, z);
    if (!m) return;
    V3 at = base + offsetOf(st, x + vol.x0, z + vol.z0);
    for (const Quad& q : m->quads) {
        if (q.cull != NONE && !faceVisible(st, x, y, z, q.cull)) continue;
        consider(q, at, m);
    }
}

// Amanatides-Woo over n cells of size cs from the origin, from t = tStart; visit(ix, iy, iz, tEnter) -> true stops
template <class F>
void dda(V3 o, V3 d, float tStart, float cs, int nx, int ny, int nz, F&& visit) {
    float lo[3] = {0, 0, 0}, hi[3] = {nx * cs, ny * cs, nz * cs};
    float t0 = tStart, t1 = 1e30f;
    for (int a = 0; a < 3; a++) {
        if (std::fabs(d[a]) < 1e-12f) {
            if (o[a] < lo[a] || o[a] > hi[a]) return;
            continue;
        }
        float ta = (lo[a] - o[a]) / d[a], tb = (hi[a] - o[a]) / d[a];
        if (ta > tb) std::swap(ta, tb);
        t0 = std::max(t0, ta), t1 = std::min(t1, tb);
    }
    if (t0 > t1) return;
    V3 p = o + d * (t0 + 1e-5f * cs);
    int n[3] = {nx, ny, nz}, c[3], step[3];
    float tNext[3], tDelta[3];
    for (int a = 0; a < 3; a++) {
        c[a] = std::clamp(int(std::floor(p[a] / cs)), 0, n[a] - 1);
        step[a] = d[a] > 0 ? 1 : -1;
        tDelta[a] = std::fabs(d[a]) < 1e-12f ? 1e30f : cs / std::fabs(d[a]);
        float edge = float(c[a] + (d[a] > 0 ? 1 : 0)) * cs;
        tNext[a] = std::fabs(d[a]) < 1e-12f ? 1e30f : (edge - o[a]) / d[a];
    }
    float tEnter = t0;
    for (;;) {
        if (visit(c[0], c[1], c[2], tEnter)) return;
        int a = tNext[0] < tNext[1] ? (tNext[0] < tNext[2] ? 0 : 2) : (tNext[1] < tNext[2] ? 1 : 2);
        tEnter = tNext[a];
        c[a] += step[a];
        if (c[a] < 0 || c[a] >= n[a]) return;
        tNext[a] += tDelta[a];
    }
}

// the nearest surviving surface beyond tmin
Hit nearest(V3 o, V3 d, float tmin) {
    Hit best;
    std::vector<Quad> scratch;
    auto cell = [&](int x, int y, int z, float tEnter) {
        if (best.hit && tEnter > best.t) return true;
        testBlock(x, y, z, o, d, tmin, best, scratch);
        if (vol.touchedAt(x, y, z))
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        if ((dx || dy || dz) && stateAt(x + dx, y + dy, z + dz).overflow) testBlock(x + dx, y + dy, z + dz, o, d, tmin, best, scratch);
        return false;
    };
    if (!vol.sparse) {
        dda(o, d, tmin, 1.f, vol.sx, vol.sy, vol.sz, cell);
        return best;
    }
    dda(o, d, tmin, 16.f, vol.nsx, vol.nsy, vol.nsz, [&](int gx, int gy, int gz, float tEnter) {
        if (best.hit && tEnter > best.t) return true;
        if (!vol.section(gx, gy, gz)) return false;
        V3 so{float(gx * 16), float(gy * 16), float(gz * 16)};
        bool stop = false;
        dda(o - so, d, std::max(tmin, tEnter - 1e-3f), 1.f, 16, 16, 16, [&](int lx, int ly, int lz, float te) {
            stop = cell(gx * 16 + lx, gy * 16 + ly, gz * 16 + lz, te);
            return stop;
        });
        return stop;
    });
    return best;
}

struct Shaded {
    RGB c;          // 0..1
    float sky = 0, block = 0, bright = 0;  // interpolated light levels and shade (for probes)
    int mode = 0;
};

// the colour the GPU writes for a hit: the texel times the vertex colour (shade x AO x tint, times the lightmap at the
// vertex's light), the vertex colour interpolated over the triangle
Shaded shade(const Hit& h) {
    const Quad& q = h.q;
    uint32_t vc[4];
    int vl[4];
    float vb[4] = {1, 1, 1, 1};
    Shaded s;
    if (q.baked) {
        for (int i = 0; i < 4; i++) vc[i] = q.bakedColor, vl[i] = q.bakedLight;
    } else {
        VertLight L = lightQuad(*h.st, h.bx, h.by, h.bz, q, *h.model);
        s.mode = L.mode;
        uint32_t tint = 0xFFFFFFFFu;
        if (q.tint >= 0 && size_t(q.tint) < h.st->tints.size()) tint = tintOf(h.st->tints[size_t(q.tint)], h.bx, h.bz);
        for (int i = 0; i < 4; i++) {
            vc[i] = mul8(gray8(L.b[i]), tint);
            vl[i] = withEmission(L.l[i], q.emit);
            vb[i] = L.b[i];
        }
    }
    int idx[3];
    float w[3];
    triWeights(h.tri, h.b1, h.b2, idx, w);
    RGB v{0, 0, 0};
    for (int k = 0; k < 3; k++) {
        int i = idx[k];
        RGB lm = sampleLightmap(vl[i]);
        v.r += float((vc[i] >> 16) & 255) / 255.f * lm.r * w[k];
        v.g += float((vc[i] >> 8) & 255) / 255.f * lm.g * w[k];
        v.b += float(vc[i] & 255) / 255.f * lm.b * w[k];
        s.sky += float(sSky(vl[i])) / 16.f * w[k];
        s.block += float(sBlock(vl[i])) / 16.f * w[k];
        s.bright += vb[i] * w[k];
    }
    s.c = {h.tex[0] * v.r, h.tex[1] * v.g, h.tex[2] * v.b};
    return s;
}

const RGB SKY{0x9C / 255.f, 0xC4 / 255.f, 0xEC / 255.f};

struct Pixel {
    RGB c;
    bool hit = false, translucent = false;
    float depth = 1e30f;
};

// front to back: translucent surfaces blend over what is behind them (as the sorted translucent pass does) until an
// opaque one, or the sky
Pixel traceRay(V3 o, V3 d, std::string* log = nullptr) {
    Pixel px;
    float trans = 1, tmin = 0;
    for (int layer = 0; layer < 24; layer++) {
        Hit h = nearest(o, d, tmin);
        if (!h.hit) {
            px.c.r += trans * SKY.r, px.c.g += trans * SKY.g, px.c.b += trans * SKY.b;
            break;
        }
        if (!px.hit) px.depth = h.t;
        px.hit = true;
        Shaded s = shade(h);
        float a = h.layer == TRANSLUCENT ? h.tex[3] : 1.f;
        if (log) {
            char buf[1024];
            const char* ln[3] = {"solid", "cutout", "translucent"};
            std::snprintf(buf, sizeof buf,
                          "%s{\"t\":%.5f,\"block\":[%d,%d,%d],\"state\":\"%s\",\"dir\":%d,\"layer\":\"%s\",\"sprite\":\"%s\",\"point\":[%.5f,%.5f,%.5f],"
                          "\"texel\":[%.4f,%.4f,%.4f,%.4f],\"sky\":%.3f,\"blockLight\":%.3f,\"shade\":%.4f,\"lighting\":%d,\"rgb\":[%.2f,%.2f,%.2f]}",
                          log->empty() ? "" : ",", h.t, h.bx + vol.x0, h.by + vol.y0, h.bz + vol.z0, h.st->name.c_str(), h.q.dir, ln[h.layer],
                          h.q.sprite >= 0 && size_t(h.q.sprite) < sprites.size() ? sprites[size_t(h.q.sprite)].name.c_str() : "?",
                          o.x + d.x * h.t + float(vol.x0), o.y + d.y * h.t + float(vol.y0), o.z + d.z * h.t + float(vol.z0), h.tex[0], h.tex[1],
                          h.tex[2], h.tex[3], s.sky, s.block, s.bright, s.mode, s.c.r * 255, s.c.g * 255, s.c.b * 255);
            *log += buf;
        }
        px.c.r += trans * a * s.c.r, px.c.g += trans * a * s.c.g, px.c.b += trans * a * s.c.b;
        if (h.layer != TRANSLUCENT) {
            trans = 0;
            break;
        }
        px.translucent = true;
        trans *= 1 - a;
        tmin = h.t + 1e-4f;
        if (trans < 1e-3f) break;
    }
    return px;
}

// ---- the sidecars: quads.json (options) and the volume's json (tints, lighting) ------------------------------------

std::string readText(const std::string& path) {
    std::ifstream f(path);
    return std::string((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}
bool jsonNum(const std::string& txt, const char* k, float& out) {
    auto i = txt.find(std::string("\"") + k + "\"");
    if (i == std::string::npos) return false;
    auto c = txt.find(':', i);
    if (c == std::string::npos) return false;
    std::string v = txt.substr(c + 1, 16);
    size_t s = v.find_first_not_of(" \t");
    if (s == std::string::npos) return false;
    if (v.compare(s, 4, "true") == 0) return out = 1, true;
    if (v.compare(s, 5, "false") == 0) return out = 0, true;
    out = float(std::atof(v.c_str() + s));
    return true;
}
bool jsonHex(const std::string& txt, const char* k, uint32_t& out) {
    auto i = txt.find(std::string("\"") + k + "\"");
    if (i == std::string::npos) return false;
    auto q = txt.find('"', txt.find(':', i) + 1);
    if (q == std::string::npos) return false;
    out = uint32_t(std::strtoul(txt.c_str() + q + 1, nullptr, 16));
    return true;
}
bool jsonArr(const std::string& txt, const char* k, float* out, int n) {
    auto i = txt.find(std::string("\"") + k + "\"");
    if (i == std::string::npos) return false;
    auto b = txt.find('[', i);
    if (b == std::string::npos) return false;
    const char* p = txt.c_str() + b + 1;
    for (int j = 0; j < n; j++) {
        char* e;
        out[j] = std::strtof(p, &e);
        if (e == p) return false;
        p = e;
        while (*p == ',' || *p == ' ') p++;
    }
    return true;
}

std::string dirOf(const std::string& p) {
    auto s = p.find_last_of("/\\");
    return s == std::string::npos ? std::string() : p.substr(0, s + 1);
}
std::string stem(const std::string& p) {
    auto s = p.find_last_of('.'), sl = p.find_last_of("/\\");
    return s == std::string::npos || (sl != std::string::npos && s < sl) ? p : p.substr(0, s);
}

}  // namespace

int main(int argc, char** argv) {
#ifdef _WIN32
    SetPriorityClass(GetCurrentProcess(), BELOW_NORMAL_PRIORITY_CLASS);  // a tool beside the game: never ahead of it
#endif
    auto a = parse(argc, argv);
    std::string quadsPath = str(a, "quads", ""), volPath = str(a, "volume", ""), out = str(a, "out", "atlas_truth.png");
    std::string spritesPath = str(a, "sprites", quadsPath.empty() ? "" : dirOf(quadsPath) + "sprites.bin");
    bool library = false;
    if (!quadsPath.empty()) {
        std::string qj = readText(stem(quadsPath) + ".json");
        float v;
        if (jsonNum(qj, "ao", v)) opt.ao = v != 0;
        if (jsonNum(qj, "cutoutLeaves", v)) opt.cutoutLeaves = v != 0;
        if (jsonNum(qj, "perPartAo", v)) opt.perPartAo = v != 0;
        if (jsonNum(qj, "enhancedLighting", v)) opt.enhanced = v != 0;
        if (jsonNum(qj, "biomeBlend", v)) opt.blend = int(v);
        if (jsonNum(qj, "gamma", v)) lmp.gamma = v;
        if (jsonNum(qj, "mipmaps", v)) mipLevels = int(v);
        if (!loadSprites(spritesPath)) {
            std::fprintf(stderr, "atlas_truth: no sprites at '%s' (in game: /nirncraft-atlaslab quads)\n", spritesPath.c_str());
            return 2;
        }
        library = loadQuads(quadsPath);
        if (!library) {
            std::fprintf(stderr, "atlas_truth: no quads at '%s' (in game: /nirncraft-atlaslab quads)\n", quadsPath.c_str());
            return 2;
        }
        if (!opt.enhanced)
            std::fprintf(stderr, "atlas_truth: the game had NeoForge's enhanced lighting off; this renders its smooth lighting (they differ in corners)\n");
    } else if (a.count("blocks")) {
        library = loadOldBlocks(a["blocks"]);
        if (library) std::fprintf(stderr, "atlas_truth: the old library (4x4x4 shapes, 4x4 face tiles): an approximation, not the mesh\n");
    }
    if (!library) {
        std::fprintf(stderr, "atlas_truth: no block library (quads=<dir>/quads.bin, or the old blocks=<dir>/blocks.bin)\n");
        return 2;
    }
    if (!loadVolume(volPath)) {
        std::fprintf(stderr, "atlas_truth: no volume at '%s' (in game: /nirncraft-atlaslab here <name> <r> <down> <up> light)\n", volPath.c_str());
        return 2;
    }
    {  // the volume's sidecar: tints (the old formats' only ones) and the lighting the camera saw
        std::string vj = readText(str(a, "json", stem(volPath) + ".json"));
        const char* keys[4] = {"grass", "foliage", "water", "dryFoliage"};
        for (int k = 0; k < 4; k++) jsonHex(vj, keys[k], tintGlobal[k]);
        float v, arr[6];
        if (a.count("time")) {
            timeOfDay(float(num(a, "time", 6000)));
        } else {
            timeOfDay(6000);
            if (jsonNum(vj, "skyFactor", v)) lmp.skyFactor = v;
            if (jsonArr(vj, "skyLight", arr, 3)) lmp.skyLight = {arr[0], arr[1], arr[2]};
        }
        if (jsonArr(vj, "blockTint", arr, 3)) lmp.blockTint = {arr[0], arr[1], arr[2]};
        if (jsonArr(vj, "ambient", arr, 3)) lmp.ambient = {arr[0], arr[1], arr[2]};
        if (jsonNum(vj, "gamma", v)) lmp.gamma = v;
        if (jsonArr(vj, "cardinal", arr, 6))
            for (int k = 0; k < 6; k++) CARD[k] = arr[k];
    }
    if (a.count("grass")) tintGlobal[0] = uint32_t(std::strtoul(a["grass"].c_str(), nullptr, 16));
    if (a.count("foliage")) tintGlobal[1] = uint32_t(std::strtoul(a["foliage"].c_str(), nullptr, 16));
    if (a.count("gamma")) lmp.gamma = float(num(a, "gamma", 0.5));
    std::string lightSrc = vol.hasLight ? "data" : "computed";
    if (!vol.hasLight || str(a, "light", "data") == "compute") {
        computeLight();
        lightSrc = vol.sparse ? "columns" : "computed";
    }
    buildLightmap();
    auto tb = std::chrono::steady_clock::now();
    markOverflow();
    int threads = int(num(a, "threads", std::max(1u, std::thread::hardware_concurrency() / 2)));
    size_t nquads = 0;
    for (const Model& m : models) nquads += m.quads.size();
    std::fprintf(stderr, "atlas_truth: %zu states, %zu models (%zu quads), %zu sprites; volume %d x %d x %d at %d %d %d, light %s, tints %s (%.0f ms)\n",
                 states.size(), models.size(), nquads, sprites.size(), vol.sx, vol.sy, vol.sz, vol.x0, vol.y0, vol.z0, lightSrc.c_str(),
                 vol.hasTints ? "per column" : "one for all",
                 std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tb).count());

    // single rays (tests): ray=ox,oy,oz,dx,dy,dz;... in world coordinates, a JSON line each
    if (a.count("ray")) {
        std::stringstream ss(a["ray"]);
        for (std::string one; std::getline(ss, one, ';');) {
            float r[6];
            if (std::sscanf(one.c_str(), "%f,%f,%f,%f,%f,%f", &r[0], &r[1], &r[2], &r[3], &r[4], &r[5]) != 6) continue;
            V3 o{r[0] - float(vol.x0), r[1] - float(vol.y0), r[2] - float(vol.z0)}, d = norm({r[3], r[4], r[5]});
            pixelAngle = float(num(a, "pixel", 0.001));
            std::string log;
            Pixel px = traceRay(o, d, &log);
            std::printf("{\"ray\":[%g,%g,%g,%g,%g,%g],\"hit\":%s,\"rgb\":[%.2f,%.2f,%.2f],\"layers\":[%s]}\n", r[0], r[1], r[2], r[3], r[4], r[5],
                        px.hit ? "true" : "false", px.c.r * 255, px.c.g * 255, px.c.b * 255, log.c_str());
        }
        return 0;
    }

    // a Minecraft camera (a live capture's own: eye in world blocks, yaw 0 = south, pitch > 0 looks down, fov vertical)
    float ex = 0, ey = 0, ez = 0;
    std::sscanf(str(a, "eye", "0,0,0").c_str(), "%f,%f,%f", &ex, &ey, &ez);
    float yaw = float(num(a, "yaw", 0)) * 3.14159265f / 180.f, pitch = float(num(a, "pitch", 0)) * 3.14159265f / 180.f;
    float fov = float(num(a, "fov", 70)) * 3.14159265f / 180.f;
    int w = int(num(a, "w", 1280)), h = int(num(a, "h", 720)), spp = std::max(1, int(num(a, "spp", 1)));
    V3 cf = norm({-std::sin(yaw) * std::cos(pitch), -std::sin(pitch), std::cos(yaw) * std::cos(pitch)});
    V3 cr = norm(cross(cf, {0, 1, 0})), cu = cross(cr, cf);
    float cpa = 2.f * std::tan(fov / 2) / float(h);
    pixelAngle = cpa;
    V3 eye{ex - float(vol.x0), ey - float(vol.y0), ez - float(vol.z0)};
    auto rayDir = [&](float sx, float sy) { return norm(cf + cr * ((sx - float(w) * 0.5f) * cpa) - cu * ((sy - float(h) * 0.5f) * cpa)); };
    {  // probe=x,y;x,y: what each of those pixels shows, layer by layer
        std::stringstream ss(str(a, "probe", ""));
        for (std::string one; std::getline(ss, one, ';');) {
            int qx = 0, qy = 0;
            if (std::sscanf(one.c_str(), "%d,%d", &qx, &qy) != 2) continue;
            std::string log;
            Pixel px = traceRay(eye, rayDir(float(qx) + 0.5f, float(qy) + 0.5f), &log);
            std::fprintf(stderr, "probe %d,%d: %s rgb %.0f %.0f %.0f [%s]\n", qx, qy, px.hit ? "hit" : "sky", px.c.r * 255, px.c.g * 255, px.c.b * 255,
                         log.c_str());
        }
    }
    std::vector<RGB> acc(size_t(w) * h);
    std::vector<float> hit(size_t(w) * h, 0.f), depth(size_t(w) * h, 1e30f);
    std::atomic<int> next{0}, translucentPx{0};
    auto t0 = std::chrono::steady_clock::now();
    auto work = [&]() {
        for (int py; (py = next++) < h;)
            for (int px = 0; px < w; px++) {
                size_t i = size_t(px + w * py);
                bool tr = false;
                for (int s = 0; s < spp; s++) {
                    float jx = 0.5f, jy = 0.5f;
                    if (spp > 1) {  // Halton (2, 3) jitter, as atlas_lab's frames
                        jx = 0, jy = 0;
                        float fr = 0.5f;
                        for (int k = s + 1; k; k /= 2, fr *= 0.5f) jx += fr * float(k % 2);
                        fr = 1.f / 3;
                        for (int k = s + 1; k; k /= 3, fr /= 3) jy += fr * float(k % 3);
                    }
                    Pixel p = traceRay(eye, rayDir(float(px) + jx, float(py) + jy));
                    float wt = 1.f / float(spp);
                    acc[i].r += p.c.r * wt, acc[i].g += p.c.g * wt, acc[i].b += p.c.b * wt;
                    hit[i] += p.hit ? wt : 0.f;
                    if (p.hit) depth[i] = std::min(depth[i], p.depth);
                    tr |= p.translucent;
                }
                if (tr) translucentPx++;
            }
    };
    std::vector<std::thread> ts;
    for (int t = 0; t < threads; t++) ts.emplace_back(work);
    for (auto& t : ts) t.join();
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::vector<uint32_t> img(size_t(w) * h), mask(size_t(w) * h), dp(size_t(w) * h);
    auto ch = [](float v) { return uint32_t(std::clamp(int(std::lround(v * 255.f)), 0, 255)); };
    double hits = 0;
    for (size_t i = 0; i < img.size(); i++) {
        img[i] = 0xFF000000u | ch(acc[i].b) << 16 | ch(acc[i].g) << 8 | ch(acc[i].r);
        uint32_t m = ch(hit[i]);
        mask[i] = 0xFF000000u | m << 16 | m << 8 | m;
        hits += hit[i];
        // the distance to what each pixel shows, 1/16 block steps in 24 bits (R high, G, B low), all ones for sky
        uint32_t v = depth[i] >= 1e29f ? 0xFFFFFFu : uint32_t(std::min(depth[i] * 16.f, 16777214.f));
        dp[i] = 0xFF000000u | (v & 255u) << 16 | ((v >> 8) & 255u) << 8 | (v >> 16);
    }
    std::string base = stem(out);
    atlas::writePng(out, img.data(), uint32_t(w), uint32_t(h));
    atlas::writePng(base + "_mask.png", mask.data(), uint32_t(w), uint32_t(h));
    atlas::writePng(base + "_depth.png", dp.data(), uint32_t(w), uint32_t(h));
    std::fprintf(stderr, "atlas_truth: %dx%d (%d spp) in %.0f ms on %d threads -> %s\n", w, h, spp, ms, threads, out.c_str());
    std::printf("{\"tool\":\"atlas_truth\",\"mode\":\"camera\",\"eye\":[%f,%f,%f],\"w\":%d,\"h\":%d,\"spp\":%d,\"ms\":%d,\"hitShare\":%.4f,"
                "\"translucentShare\":%.4f,\"light\":\"%s\",\"tints\":\"%s\",\"states\":%zu,\"models\":%zu,\"quads\":%zu,\"sprites\":%zu,"
                "\"frames\":[{\"variant\":\"truth\",\"ms\":%d}]}\n",
                ex, ey, ez, w, h, spp, int(ms), hits / double(img.size()), double(translucentPx.load()) / double(img.size()), lightSrc.c_str(),
                vol.hasTints ? "column" : "global", states.size(), models.size(), nquads, sprites.size(), int(ms));
    return 0;
}
