// The in-game exports (AtlasLabExport: /nirncraft-atlaslab blocks, view, here) loaded into atlas_core the way the game
// feeds it (docs/ATLAS-LOD.md, "The renderer's groups"): every block state the export uses becomes a far material
// (its mean colour in the palette, its 4x4x4 shape and face tiles through atlas_set_material_shapes, tints applied as
// the lab applies them), the exported sections go up with atlas_upload_section (only the blocks the game's far field
// keeps, AtlasSections.kept, unless keep=all), and the column tiles under them are replaced by ones derived from the
// sections (the real ground and its blocks), so the preview raster's dressing shows only outside the export.
// Plus what atlas_render needs to compare a frame with atlas_truth's: a reader for the stored-deflate PNGs our tools
// write and the harness's colour metric (CIE76 in D65 Lab, results.py _lab) by distance band.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>

#include "atlas/atlas.h"

namespace exportdata {

struct State {
    bool present = false;
    std::string name;
    uint8_t cls = 0, emit = 0, flags = 0, tint = 0;
    uint64_t mask = 0;
    uint32_t mean = 0;
    uint32_t faces[6][16] = {};
    // its tint source's answer for tint index 0 (quads.bin; 255: not known, blocks.bin's tint kind decides): 0 none, 1
    // grass, 2 foliage, 3 water, 4 dry foliage, 5 the constant tintConst
    uint8_t tintSrc = 255;
    uint32_t tintConst = 0;
};

// The tint sources quads.bin records per state (AtlasLabExport.quads), into lib: what the game's far materials take
// (AtlasMaterials.tint): spruce and birch leaves their constants, NirnCraft's snowy spruce leaves none, plants grass.
inline bool loadTintSources(const std::string& path, std::vector<State>& lib) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    size_t p = 4;
    auto u8 = [&]() { return p < d.size() ? d[p++] : uint8_t(0); };
    auto u32 = [&]() {
        uint32_t v = 0;
        if (p + 4 <= d.size()) std::memcpy(&v, &d[p], 4);
        p += 4;
        return v;
    };
    if (d.size() < 8 || std::memcmp(d.data(), "ALQ1", 4) != 0) return false;
    for (uint32_t m = 0, nm = u32(); m < nm && p < d.size(); m++) {
        p += 1;
        p += size_t(u32()) * 108;  // a quad: 4 x (x y z u v), 4 normals, sprite, 4 u8, i16 tint, 2 u8
    }
    p += size_t(u32()) * 18;  // fluid models
    for (uint32_t i = 0, ns = u32(); i < ns && p < d.size(); i++) {
        uint32_t id = u32();
        uint16_t nl = u8();
        nl = uint16_t(nl | u8() << 8);
        p += nl + 4 + 2 + 2 + 4;  // name, block, flags, emission, dampening, shade
        if (u8()) p += 8;  // offset
        if (int8_t(u8()) >= 0) p += 6;  // fluid
        uint8_t full = u8(), partial = u8();
        (void)full;
        for (int k = 0; k < 6; k++) p += (partial >> k & 1) ? 32 : 0;
        p += 1;  // skip-same faces
        uint8_t nt = u8();
        for (int t = 0; t < nt; t++) {
            uint8_t kind = u8();
            uint32_t c = u32();
            if (t == 0 && id < lib.size()) lib[id].tintSrc = kind, lib[id].tintConst = c;
        }
        p += size_t(u8()) * 4;  // variants
        if (nt == 0 && id < lib.size()) lib[id].tintSrc = 0;
    }
    return p <= d.size();
}

// The model quads and sprites of quads.bin / sprites.bin (AtlasLabExport.quads), as the game hands them to
// atlas_set_material_bricks (AtlasMaterials: a state that is not a full solid cube or leaves gets a brick).
struct ModelLib {
    struct Quad {
        float p[4][3], uv[4][2];
        int32_t sprite;
        uint8_t dir, layer;
        int16_t tint;
    };
    struct StateInfo {
        bool present = false;
        uint16_t flags = 0;  // 1 solid render, 64 leaves, 128 rendered by its model, 512 air
        uint8_t emit = 0;
        std::vector<std::pair<uint8_t, uint32_t>> tints;  // per tint index: kind (1 grass, 2 foliage, 5 constant), colour
        int model = -1;  // its first variant
        std::string name;
        int fluid = -1;  // its fluid model (quads.bin), -1 none
        float fluidH = 0;  // the fluid's own height
    };
    struct Fluid {
        int32_t still = -1, flow = -1;
        uint8_t tintKind = 0;  // 3: water
    };
    std::vector<Fluid> fluids;
    std::vector<std::vector<Quad>> models;
    std::vector<StateInfo> states;
    struct Sprite {
        uint32_t w = 0, h = 0, first = 0;
        bool animated = false;
        bool glassy = false;  // (its name: glass, a pane, ice)
    };
    std::vector<Sprite> sprites;
    std::vector<uint32_t> texels;
};

inline bool loadModels(const std::string& dir, ModelLib& ml) {
    auto read = [](const std::string& path, std::vector<uint8_t>& d) {
        std::ifstream f(path, std::ios::binary);
        if (!f) return false;
        d.assign((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        return true;
    };
    std::vector<uint8_t> d;
    if (!read(dir + "quads.bin", d) || d.size() < 8 || std::memcmp(d.data(), "ALQ1", 4) != 0) return false;
    size_t p = 4;
    auto u8 = [&]() { return p < d.size() ? d[p++] : uint8_t(0); };
    auto u16 = [&]() { uint16_t v = 0; if (p + 2 <= d.size()) std::memcpy(&v, &d[p], 2); p += 2; return v; };
    auto u32 = [&]() { uint32_t v = 0; if (p + 4 <= d.size()) std::memcpy(&v, &d[p], 4); p += 4; return v; };
    auto f32 = [&]() { float v = 0; if (p + 4 <= d.size()) std::memcpy(&v, &d[p], 4); p += 4; return v; };
    uint32_t nm = u32();
    ml.models.resize(nm);
    for (uint32_t i = 0; i < nm && p < d.size(); i++) {
        u8();  // AO mode
        uint32_t nq = u32();
        ml.models[i].resize(nq);
        for (auto& q : ml.models[i]) {
            for (int k = 0; k < 4; k++) {
                q.p[k][0] = f32(), q.p[k][1] = f32(), q.p[k][2] = f32();
                q.uv[k][0] = f32(), q.uv[k][1] = f32();
            }
            p += 16;  // normals
            q.sprite = int32_t(u32());
            q.dir = u8();
            u8(), u8();  // cull, shade
            q.layer = u8();
            q.tint = int16_t(u16());
            u8(), u8();  // emission, AO flags
        }
    }
    ml.fluids.resize(u32());
    for (auto& fl : ml.fluids) {
        fl.still = int32_t(u32()), fl.flow = int32_t(u32());
        u32(), u8();  // overlay, layer
        fl.tintKind = u8();
        u32();
    }
    uint32_t ns = u32();
    for (uint32_t i = 0; i < ns && p < d.size(); i++) {
        uint32_t id = u32();
        if (id >= ml.states.size()) ml.states.resize(size_t(id) + 1);
        auto& s = ml.states[id];
        s.present = true;
        {
            uint16_t nl = u16();
            if (p + nl <= d.size()) s.name.assign(reinterpret_cast<const char*>(&d[p]), nl);
            p += nl;
        }
        u32();       // block
        s.flags = u16();
        s.emit = u8();
        u8();
        f32();
        if (u8()) p += 8;  // offset
        s.fluid = int8_t(u8());
        if (s.fluid >= 0) s.fluidH = f32(), p += 2;  // own height, amount, flags
        u8();
        uint8_t partial = u8();
        for (int k = 0; k < 6; k++) p += (partial >> k & 1) ? 32 : 0;
        u8();
        uint8_t nt = u8();
        for (int t = 0; t < nt; t++) {
            uint8_t kind = u8();
            uint32_t c = u32();
            s.tints.emplace_back(kind, c);
        }
        uint8_t nv = u8();
        for (int v = 0; v < nv; v++) {
            int32_t m = int32_t(u32());
            if (v == 0) s.model = m;
        }
    }
    if (!read(dir + "sprites.bin", d) || d.size() < 8 || std::memcmp(d.data(), "ALT1", 4) != 0) return false;
    p = 4;
    uint32_t n = u32();
    ml.sprites.resize(n);
    for (uint32_t i = 0; i < n && p < d.size(); i++) {
        uint16_t nl = u16();
        std::string nm(reinterpret_cast<const char*>(d.data() + std::min(p, d.size())), std::min<size_t>(nl, d.size() - std::min(p, d.size())));
        p += nl;
        auto& sp = ml.sprites[i];
        sp.glassy = nm.find("glass") != std::string::npos || nm.find("ice") != std::string::npos;
        sp.w = u16(), sp.h = u16();
        sp.animated = (u8() & 1) != 0;
        sp.first = uint32_t(ml.texels.size());
        for (uint32_t k = 0; k < sp.w * sp.h; k++) ml.texels.push_back(u32());
    }
    return p <= d.size();
}

// Whether a model is a plain cube: on each face a quad on the block's boundary covering it whole (AtlasShapes.fullCubeModel).
inline bool fullCube(const std::vector<ModelLib::Quad>& qs) {
    int faces = 0;
    static const int AX[6] = {1, 1, 2, 2, 0, 0};
    for (const auto& q : qs) {
        if (q.dir > 5) continue;
        int a = AX[q.dir];
        float plane = (q.dir & 1) ? 1.0f : 0.0f;
        float lo0 = 1, hi0 = 0, lo1 = 1, hi1 = 0;
        bool on = true;
        for (int i = 0; i < 4; i++) {
            if (std::fabs(q.p[i][a] - plane) > 1e-3f) on = false;
            float u = q.p[i][(a + 1) % 3], v = q.p[i][(a + 2) % 3];
            lo0 = std::min(lo0, u), hi0 = std::max(hi0, u), lo1 = std::min(lo1, v), hi1 = std::max(hi1, v);
        }
        if (on && lo0 < 1e-3f && lo1 < 1e-3f && hi0 > 1 - 1e-3f && hi1 > 1 - 1e-3f) faces |= 1 << q.dir;
    }
    return faces == 63;
}

// Whether state id is a plain cube in the cut-out layer (AtlasShapes.holedCube: a grate, roots; whether its faces show
// holes is upload's, by the face tiles).
inline bool holedCube(const ModelLib& ml, uint32_t id) {
    if (id >= ml.states.size() || !ml.states[id].present) return false;
    const auto& s = ml.states[id];
    if (s.model < 0 || size_t(s.model) >= ml.models.size()) return false;
    const auto& qs = ml.models[size_t(s.model)];
    bool cut = false;
    for (const auto& q : qs) cut |= q.layer == 1;
    return cut && fullCube(qs);
}

// Whether state id takes a brick (AtlasShapes.brickable / fluidBrick): a fluid other than water, or a block drawn by its
// model that is not a plain-cube leaves, not a full solid (occluding) cube unless its texture moves, and not glass,
// a pane, ice, slime or honey in the translucent layer (redstone dust and soft-edged plants are bricks of their texels).
inline bool brickWorthy(const ModelLib& ml, uint32_t id) {
    if (id >= ml.states.size() || !ml.states[id].present) return false;
    const auto& s = ml.states[id];
    if (!(s.flags & 128) && s.fluid >= 0 && size_t(s.fluid) < ml.fluids.size()) return ml.fluids[size_t(s.fluid)].tintKind != 3;
    if ((s.flags & 512) || !(s.flags & 128) || s.model < 0 || size_t(s.model) >= ml.models.size()) return false;
    const auto& qs = ml.models[size_t(s.model)];
    if (qs.empty()) return false;
    bool cube = fullCube(qs);
    if ((s.flags & 64) && cube) return false;  // leaves: textured cut-out cubes
    bool animated = false;
    for (const auto& q : qs) animated |= q.sprite >= 0 && size_t(q.sprite) < ml.sprites.size() && ml.sprites[size_t(q.sprite)].animated;
    if (((s.flags & 1) || (cube && (s.flags & 32))) && !animated) return false;  // (1 solid render, 32 can occlude)
    auto has = [&](const char* k) { return s.name.find(k) != std::string::npos; };
    bool glassy = has("glass") || has("ice}") || has("ice]") || has("_ice") || has("slime_block") || has("honey_block");
    for (const auto& q : qs)
        if (q.layer == 2 && glassy) return false;
    return true;
}

// A state's six 16 x 16 face textures as the game's AtlasShapes.textures makes them (each texel the outermost quad
// facing that way over it, its sprite there, the quad's tint when it has one; alpha 0 where none shows), into out
// (6 x 256 ARGB). False when it has no model quads.
inline bool faceTextures(const ModelLib& ml, uint32_t id, uint32_t grass, uint32_t foliage, uint32_t* out) {
    if (id >= ml.states.size() || !ml.states[id].present) return false;
    const auto& s = ml.states[id];
    if (s.model < 0 || size_t(s.model) >= ml.models.size() || ml.models[size_t(s.model)].empty()) return false;
    const auto& qs = ml.models[size_t(s.model)];
    for (int d = 0; d < 6; d++) {
        float depth[256];
        for (float& v : depth) v = -1e30f;
        uint32_t* tile = out + d * 256;
        std::fill(tile, tile + 256, 0u);
        for (const auto& q : qs) {
            if (q.dir != d || q.sprite < 0 || size_t(q.sprite) >= ml.sprites.size()) continue;
            const auto& sp = ml.sprites[size_t(q.sprite)];
            // (a, b, depth) in the face's plane, as AtlasShapes.plane
            auto plane = [&](int i, float& a, float& b, float& dep) {
                float x = q.p[i][0], y = q.p[i][1], z = q.p[i][2];
                switch (d) {
                    case 0: a = x, b = z, dep = -y; break;
                    case 1: a = x, b = z, dep = y; break;
                    case 2: a = x, b = y, dep = -z; break;
                    case 3: a = x, b = y, dep = z; break;
                    case 4: a = z, b = y, dep = -x; break;
                    default: a = z, b = y, dep = x; break;
                }
            };
            float pa[4], pb[4], a0 = 1e9f, a1 = -1e9f, b0 = 1e9f, b1 = -1e9f, dep = 0;
            for (int i = 0; i < 4; i++) {
                float dd;
                plane(i, pa[i], pb[i], dd);
                dep += dd / 4;
                a0 = std::min(a0, pa[i]), a1 = std::max(a1, pa[i]), b0 = std::min(b0, pb[i]), b1 = std::max(b1, pb[i]);
            }
            if (a1 - a0 < 1e-4f || b1 - b0 < 1e-4f) continue;
            auto corner = [&](float a, float b) {
                int best = 0;
                float bd = 1e30f;
                for (int i = 0; i < 4; i++) {
                    float dd = (pa[i] - a) * (pa[i] - a) + (pb[i] - b) * (pb[i] - b);
                    if (dd < bd) bd = dd, best = i;
                }
                return best;
            };
            int c00 = corner(a0, b0), c10 = corner(a1, b0), c01 = corner(a0, b1), c11 = corner(a1, b1);
            uint32_t tint = 0xFFFFFFu;
            if (q.tint >= 0 && size_t(q.tint) < s.tints.size()) {
                auto [kind, c] = s.tints[size_t(q.tint)];
                tint = kind == 1 ? grass : kind == 2 ? foliage : kind == 4 ? 0xA0825Au : kind == 5 ? c & 0xFFFFFFu : 0xFFFFFFu;
            }
            for (int tv = 0; tv < 16; tv++)
                for (int tu = 0; tu < 16; tu++) {
                    int k = tu + 16 * tv;
                    float ca = (tu + 0.5f) / 16, cb = d <= 1 ? (tv + 0.5f) / 16 : 1.0f - (tv + 0.5f) / 16;
                    if (ca < a0 || ca > a1 || cb < b0 || cb > b1 || dep < depth[k]) continue;
                    float ss = (ca - a0) / (a1 - a0), tt = (cb - b0) / (b1 - b0);
                    auto lerp2 = [&](float v00, float v10, float v01, float v11) {
                        return (v00 * (1 - ss) + v10 * ss) * (1 - tt) + (v01 * (1 - ss) + v11 * ss) * tt;
                    };
                    float u = lerp2(q.uv[c00][0], q.uv[c10][0], q.uv[c01][0], q.uv[c11][0]);
                    float v = lerp2(q.uv[c00][1], q.uv[c10][1], q.uv[c01][1], q.uv[c11][1]);
                    int px = std::clamp(int(u * float(sp.w)), 0, int(sp.w) - 1), py = std::clamp(int(v * float(sp.h)), 0, int(sp.h) - 1);
                    uint32_t c = ml.texels[sp.first + uint32_t(py) * sp.w + uint32_t(px)];
                    if ((c >> 24) < 26) continue;  // (see-through here: what is behind it shows)
                    depth[k] = dep;
                    uint32_t r = ((c >> 16) & 255) * ((tint >> 16) & 255) / 255, g = ((c >> 8) & 255) * ((tint >> 8) & 255) / 255,
                             b = (c & 255) * (tint & 255) / 255;
                    tile[k] = 0xFF000000u | r << 16 | g << 8 | b;  // (AtlasShapes.face: a shown texel, opaque)
                }
        }
    }
    return true;
}

// The near field's 16 x 16 face textures of materials [first, first + ids.size()) (atlas_set_material_textures), as
// the game sets them for its exact materials.
inline bool uploadTextures(AtlasContext* ctx, const ModelLib& ml, uint16_t first, const std::vector<uint32_t>& ids,
                           uint32_t grass, uint32_t foliage, std::string& why) {
    size_t n = ids.size();
    std::vector<uint32_t> tex(n * 6 * 256 + n, 0u);
    for (size_t m = 0; m < n; m++)
        if (faceTextures(ml, ids[m], grass, foliage, tex.data() + m * 6 * 256)) tex[n * 6 * 256 + m] = 1u;
    if (n && atlas_set_material_textures(ctx, first, tex.data(), uint32_t(n)) != ATLAS_OK) return why = atlas_last_error(ctx), false;
    return true;
}

// The bricks of materials [first, first + ids.size()) for state ids `ids` (0 / not brick-worthy: none), tinted with
// the export's colours, through atlas_set_material_bricks.
inline bool uploadBricks(AtlasContext* ctx, const ModelLib& ml, uint16_t first, const std::vector<uint32_t>& ids,
                         uint32_t grass, uint32_t foliage, size_t& bricked, std::string& why, bool animTest = false) {
    std::vector<AtlasBrickQuad> quads;
    std::vector<float> emission(ids.size(), 0.0f);
    for (size_t m = 0; m < ids.size(); m++) {
        uint32_t id = ids[m];
        if (id >= ml.states.size() || !ml.states[id].present) continue;
        const auto& s = ml.states[id];
        bool fluidOnly = !(s.flags & 128) && s.fluid >= 0 && size_t(s.fluid) < ml.fluids.size() && ml.fluids[size_t(s.fluid)].tintKind != 3;
        if (fluidOnly) {
            // a fluid other than water (lava): a box to its own height, still on top and bottom, flowing on the sides
            // (AtlasShapes.fluidBrick)
            const auto& fl = ml.fluids[size_t(s.fluid)];
            if (fl.still < 0 || fl.flow < 0 || size_t(std::max(fl.still, fl.flow)) >= ml.sprites.size()) continue;
            emission[m] = float(s.emit) / 15.0f;
            float h = std::clamp(s.fluidH, 1.0f / 16.0f, 1.0f);
            auto add = [&](std::initializer_list<float> pos, std::initializer_list<float> uv, int32_t sprite, int dir) {
                AtlasBrickQuad b{};
                std::copy(pos.begin(), pos.end(), &b.pos[0][0]);
                std::copy(uv.begin(), uv.end(), &b.uv[0][0]);
                b.sprite = uint32_t(sprite), b.tint = 0xFFFFFFu, b.dir = dir, b.material = uint32_t(m);
                quads.push_back(b);
            };
            add({0, h, 0, 1, h, 0, 1, h, 1, 0, h, 1}, {0, 0, 1, 0, 1, 1, 0, 1}, fl.still, 1);
            add({0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 1, 1, 0, 1}, fl.still, 0);
            float v = 0.5f * h;
            add({0, h, 0, 1, h, 0, 1, 0, 0, 0, 0, 0}, {0, 0, 0.5f, 0, 0.5f, v, 0, v}, fl.flow, 2);
            add({0, h, 1, 1, h, 1, 1, 0, 1, 0, 0, 1}, {0, 0, 0.5f, 0, 0.5f, v, 0, v}, fl.flow, 3);
            add({0, h, 0, 0, h, 1, 0, 0, 1, 0, 0, 0}, {0, 0, 0.5f, 0, 0.5f, v, 0, v}, fl.flow, 4);
            add({1, h, 0, 1, h, 1, 1, 0, 1, 1, 0, 0}, {0, 0, 0.5f, 0, 0.5f, v, 0, v}, fl.flow, 5);
            bricked++;
            continue;
        }
        if (!brickWorthy(ml, id)) continue;
        emission[m] = float(s.emit) / 15.0f;
        bool any = false;
        for (const auto& q : ml.models[size_t(s.model)]) {
            if (q.sprite < 0 || size_t(q.sprite) >= ml.sprites.size()) continue;
            AtlasBrickQuad b{};
            std::memcpy(b.pos, q.p, sizeof b.pos);
            std::memcpy(b.uv, q.uv, sizeof b.uv);
            b.sprite = uint32_t(q.sprite);
            b.dir = q.dir;
            b.material = uint32_t(m);
            b.tint = 0xFFFFFFu;
            if (q.tint >= 0 && size_t(q.tint) < s.tints.size()) {
                auto [kind, c] = s.tints[size_t(q.tint)];
                b.tint = kind == 1 ? grass : kind == 2 ? foliage : kind == 4 ? 0xA0825Au : kind == 5 ? c & 0xFFFFFFu : 0xFFFFFFu;  // (4 dry foliage: AtlasMaterials.DRY_FOLIAGE)
            }
            quads.push_back(b);
            any = true;
        }
        bricked += any ? 1 : 0;
    }
    std::vector<AtlasBrickSprite> sp(ml.sprites.size());
    for (size_t i = 0; i < sp.size(); i++) sp[i] = {ml.sprites[i].w, ml.sprites[i].h, ml.sprites[i].first, 1};
    // animtest=1: every animated sprite (sprites.bin holds frame 0) as two frames, the second its colours inverted, a
    // tick each: what the frame's time shows can be checked offline (time=0 vs time=0.05)
    std::vector<uint32_t> texels = ml.texels;
    if (animTest)
        for (size_t i = 0; i < sp.size(); i++) {
            if (!ml.sprites[i].animated) continue;
            uint32_t n = sp[i].width * sp[i].height, at = uint32_t(texels.size());
            for (uint32_t k = 0; k < n; k++) texels.push_back(ml.texels[sp[i].firstTexel + k]);
            for (uint32_t k = 0; k < n; k++) texels.push_back(ml.texels[sp[i].firstTexel + k] ^ 0x00FFFFFFu);
            sp[i].firstTexel = at;
            sp[i].frames = 2u | 1u << 16;
        }
    if (atlas_set_material_bricks(ctx, first, uint32_t(ids.size()), quads.data(), uint32_t(quads.size()), sp.data(),
                                  uint32_t(sp.size()), texels.data(), uint32_t(texels.size()), emission.data()) != ATLAS_OK)
        return why = atlas_last_error(ctx), false;
    return true;
}

inline bool loadBlocks(const std::string& path, std::vector<State>& lib) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (d.size() < 8 || std::memcmp(d.data(), "ALB1", 4) != 0) return false;
    size_t p = 4;
    auto get = [&](void* out, size_t n) {
        if (p + n > d.size()) return false;
        std::memcpy(out, &d[p], n);
        p += n;
        return true;
    };
    uint32_t n = 0;
    get(&n, 4);
    for (uint32_t i = 0; i < n; i++) {
        uint32_t id = 0;
        uint16_t nl = 0;
        if (!get(&id, 4) || !get(&nl, 2) || p + nl > d.size()) return false;
        if (id >= lib.size()) lib.resize(size_t(id) + 1);
        State& s = lib[id];
        s.present = true;
        s.name.assign(reinterpret_cast<const char*>(&d[p]), nl);
        p += nl;
        if (!get(&s.cls, 1) || !get(&s.emit, 1) || !get(&s.flags, 1) || !get(&s.tint, 1) || !get(&s.mask, 8) ||
            !get(&s.mean, 4) || !get(s.faces, sizeof s.faces))
            return false;
        // thinner than a micro-voxel (a snow layer, a torch, a chain): its outline fills none of the 4 x 4 x 4, so its
        // shape is the visual hull of its face tiles (AtlasShapes.hull): a layer's bottom layer, a post's core column
        if (!s.mask && (s.flags & 4) && s.cls != 2) {
            auto cov = [&](int f, int u, int v) { return (s.faces[f][u + 4 * v] >> 24) >= 64; };
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    for (int x = 0; x < 4; x++)
                        if ((cov(0, x, z) || cov(1, x, z)) && (cov(2, x, 3 - y) || cov(3, x, 3 - y)) && (cov(4, z, 3 - y) || cov(5, z, 3 - y)))
                            s.mask |= 1ull << (x + 4 * (z + 4 * y));
        }
    }
    // the tint sources, from quads.bin beside it (an export without one keeps blocks.bin's tint kinds)
    auto slash = path.find_last_of("/\\");
    loadTintSources((slash == std::string::npos ? std::string() : path.substr(0, slash + 1)) + "quads.bin", lib);
    return true;
}

inline bool isAir(const State& s) {
    return s.name.rfind("Block{minecraft:air}", 0) == 0 || s.name.rfind("Block{minecraft:cave_air}", 0) == 0 ||
           s.name.rfind("Block{minecraft:void_air}", 0) == 0;
}

// what the game's far field keeps as a voxel (AtlasSections.kept, as atlas_lab reads it from the library): solids,
// leaves, fluids, lights, slabs and stairs, snow layers and carpets (the cover a far snowfield or a floor shows)
inline bool keptByGame(const State& s) {
    if (isAir(s)) return false;
    bool water = s.cls == 2 && !(s.flags & 4);
    if (!s.mask && !water) return false;
    return s.cls <= 3 || s.name.find("_slab") != std::string::npos || s.name.find("_stairs") != std::string::npos ||
           s.name.rfind("Block{minecraft:snow}", 0) == 0 || s.name.find("_carpet}") != std::string::npos ||
           s.name.find("_fence}") != std::string::npos || s.name.find("_fence_gate}") != std::string::npos ||
           s.name.find("_wall}") != std::string::npos;
}

inline bool anyContent(const State& s) {
    bool water = s.cls == 2 && !(s.flags & 4);
    return !isAir(s) && (s.mask != 0 || water);
}

// ---- volumes: ALS1 / ALS2 sparse views, ALV1 / ALV2 dense boxes, as sections --------------------------------------

struct Section {
    int cx, sy, cz;
    std::vector<uint32_t> pal;
    std::vector<uint16_t> idx;  // x + 16 * (z + 16 * y); empty: all pal[0]
    uint32_t at(int i) const { return idx.empty() ? pal[0] : pal[idx[size_t(i)]]; }
};

inline bool loadVolume(const std::string& path, std::vector<Section>& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (d.size() < 8) return false;
    size_t p = 4;
    bool ok = true;
    auto get = [&](void* o, size_t n) {
        if (p + n > d.size()) {
            ok = false;
            std::memset(o, 0, n);
            return;
        }
        std::memcpy(o, &d[p], n);
        p += n;
    };
    auto i32 = [&]() { int32_t v; get(&v, 4); return v; };
    auto u32 = [&]() { uint32_t v; get(&v, 4); return v; };
    auto u16 = [&]() { uint16_t v; get(&v, 2); return v; };
    auto u8 = [&]() { uint8_t v; get(&v, 1); return v; };
    bool als1 = std::memcmp(d.data(), "ALS1", 4) == 0, als2 = std::memcmp(d.data(), "ALS2", 4) == 0;
    bool alv1 = std::memcmp(d.data(), "ALV1", 4) == 0, alv2 = std::memcmp(d.data(), "ALV2", 4) == 0;
    if (als1 || als2) {
        if (als2) u32(), u32();  // flags, blend radius
        uint32_t n = u32();
        out.reserve(n);
        for (uint32_t k = 0; k < n && ok; k++) {
            Section s;
            s.cx = i32(), s.sy = i32(), s.cz = i32();
            uint16_t pn = u16();
            s.pal.resize(pn);
            for (auto& v : s.pal) v = u32();
            if (pn > 1) {
                s.idx.resize(4096);
                for (auto& v : s.idx) v = pn <= 256 ? u8() : u16();
            }
            if (als2) {
                uint8_t sf = u8();
                if (sf & 1) p += 4096;  // light
                if (sf & 2) p += 4096;  // variants
            }
            if (!s.pal.empty()) out.push_back(std::move(s));
        }
        return ok;
    }
    if (alv1 || alv2) {
        int x0 = i32(), y0 = i32(), z0 = i32(), sx = i32(), sy = i32(), sz = i32();
        if (alv2) u32();
        size_t n = size_t(sx) * sy * sz;
        if (p + n * 4 > d.size()) return false;
        const uint8_t* ids = &d[p];
        auto id = [&](int x, int y, int z) {
            uint32_t v;
            std::memcpy(&v, ids + (size_t(x) + size_t(sx) * (size_t(z) + size_t(sz) * size_t(y))) * 4, 4);
            return v;
        };
        // section-aligned cover of the box; outside it air
        auto fd = [](int a) { return a >= 0 ? a / 16 : -((-a + 15) / 16); };
        for (int gy = fd(y0); gy <= fd(y0 + sy - 1); gy++)
            for (int gz = fd(z0); gz <= fd(z0 + sz - 1); gz++)
                for (int gx = fd(x0); gx <= fd(x0 + sx - 1); gx++) {
                    Section s{gx, gy, gz, {0}, std::vector<uint16_t>(4096, 0)};
                    std::map<uint32_t, uint16_t> pal{{0u, uint16_t(0)}};
                    bool any = false;
                    for (int ly = 0; ly < 16; ly++)
                        for (int lz = 0; lz < 16; lz++)
                            for (int lx = 0; lx < 16; lx++) {
                                int x = gx * 16 + lx - x0, y = gy * 16 + ly - y0, z = gz * 16 + lz - z0;
                                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue;
                                uint32_t v = id(x, y, z);
                                auto it = pal.find(v);
                                if (it == pal.end()) {
                                    it = pal.emplace(v, uint16_t(s.pal.size())).first;
                                    s.pal.push_back(v);
                                }
                                s.idx[size_t(lx + 16 * (lz + 16 * ly))] = it->second;
                                any |= v != 0;
                            }
                    if (any) out.push_back(std::move(s));
                }
        return true;
    }
    return false;
}

// the tints the game blended at the export's centre (its .json beside it), "RRGGBB"
inline uint32_t jsonColour(const std::string& path, const char* key, uint32_t def) {
    std::ifstream f(path);
    std::string txt((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    auto i = txt.find(std::string("\"") + key + "\"");
    if (i == std::string::npos) return def;
    auto q = txt.find('"', txt.find(':', i) + 1);
    return q == std::string::npos ? def : uint32_t(std::strtoul(txt.c_str() + q + 1, nullptr, 16));
}

inline float lin8(uint32_t c) { return std::pow(float(c) / 255.0f, 2.2f); }

inline uint32_t mulTint(uint32_t argb, uint32_t tint) {
    uint32_t r = ((argb >> 16) & 255) * ((tint >> 16) & 255) / 255, g = ((argb >> 8) & 255) * ((tint >> 8) & 255) / 255,
             b = (argb & 255) * (tint & 255) / 255;
    return (argb & 0xFF000000u) | r << 16 | g << 8 | b;
}

// ---- the export in atlas_core ---------------------------------------------------------------------------------------

// a section held back for the server's sections window (atlas_render's emulation of AtlasSections.push)
struct Held {
    int cx, sy, cz;
    std::vector<uint16_t> mats;  // far materials, x + 16 * (z + 16 * y)
    bool sent = false;
};

struct Loaded {
    std::unordered_map<uint32_t, uint16_t> matOf;  // state id -> far material (0: not kept)
    std::vector<Held> held;  // upload(..., hold = true): the sections, not uploaded
    std::unordered_map<uint64_t, std::pair<int, int>> chunks;  // exported chunk columns -> (lowest, highest) section
    size_t sections = 0, states = 0, shaped = 0;
    std::vector<AtlasMaterialShape> shapes;  // the materials' shapes from firstMat on, as set (textest: their tiles)
    std::vector<uint32_t> matState;  // per material from firstMat on: its state id
    uint16_t firstMat = 0;
    int x0 = 0, z0 = 0, x1 = 0, z1 = 0;  // block bounds of the exported chunks
    static uint64_t key(int cx, int cz) { return uint64_t(uint32_t(cx)) << 32 | uint32_t(cz); }
    bool inExport(double x, double z) const {
        int cx = int(std::floor(x / 16.0)), cz = int(std::floor(z / 16.0));
        return chunks.count(key(cx, cz)) != 0;
    }
};

// Registers the export's states as far materials from `firstMat` on (palette + shapes) and uploads its sections.
// box (world blocks, inclusive; null: everything): only the blocks inside it exist (one element cut from the export)
inline bool upload(AtlasContext* ctx, const std::vector<State>& lib, std::vector<Section>& secs, bool keepAll,
                   uint32_t grass, uint32_t foliage, uint32_t water, uint16_t firstMat, Loaded& out, std::string& why,
                   const int* box = nullptr, bool cutouts = true, bool hold = false,
                   const std::vector<uint8_t>* modelled = nullptr, bool perBlock = false) {
    std::unordered_map<std::string, uint16_t> blockMat;  // (perBlock: a block's first state's material for all of them)
    if (box) {
        std::vector<Section> kept;
        for (Section& s : secs) {
            if (s.cx * 16 + 15 < box[0] || s.cx * 16 > box[3] || s.sy * 16 + 15 < box[1] || s.sy * 16 > box[4] ||
                s.cz * 16 + 15 < box[2] || s.cz * 16 > box[5])
                continue;
            // the blocks outside the box become air (palette entry 0 added as id 0)
            std::vector<uint16_t> idx(4096);
            std::vector<uint32_t> pal = s.pal;
            uint16_t air = uint16_t(pal.size());
            pal.push_back(0);
            for (int i = 0; i < 4096; i++) {
                int x = s.cx * 16 + (i & 15), z = s.cz * 16 + ((i >> 4) & 15), y = s.sy * 16 + (i >> 8);
                bool in = x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
                idx[size_t(i)] = in ? (s.idx.empty() ? uint16_t(0) : s.idx[size_t(i)]) : air;
            }
            s.pal = std::move(pal);
            s.idx = std::move(idx);
            kept.push_back(std::move(s));
        }
        secs = std::move(kept);
    }
    std::vector<AtlasMaterial> mats;
    std::vector<AtlasMaterialShape> shapes;
    auto materialOf = [&](uint32_t id) -> uint16_t {
        auto it = out.matOf.find(id);
        if (it != out.matOf.end()) return it->second;
        uint16_t m = 0;
        // perblock=1 (a measurement): every state of a block takes its first state's material, as the game's per-block
        // palette ids did once its exact ids ran out (a fence's post for every fence, one half for both of a plant)
        std::string blk = id < lib.size() ? lib[id].name.substr(0, lib[id].name.find('}')) : std::string();
        if (perBlock && !blk.empty()) {
            auto b = blockMat.find(blk);
            if (b != blockMat.end()) {
                out.matOf.emplace(id, b->second);
                return b->second;
            }
        }
        // (keep=near, `modelled`: every state with a model, as the game's client feed keeps them: AtlasSections.nearKept)
        bool near = modelled && id < modelled->size() && (*modelled)[id] && id < lib.size() && !isAir(lib[id]);
        if (id < lib.size() && lib[id].present && (near || (keepAll ? anyContent(lib[id]) : keptByGame(lib[id])))) {
            const State& s = lib[id];
            bool wat = s.cls == 2 && !(s.flags & 4);
            uint32_t tint = s.tint == 1 ? grass : s.tint == 2 ? foliage : s.tint == 3 ? water : 0xFFFFFFu;
            // the tint the game's far field gives it (AtlasMaterials.tint: its tint source's answer; water as before)
            if (s.tintSrc != 255 && !wat)
                tint = s.tintSrc == 1 ? grass : s.tintSrc == 2 ? foliage : s.tintSrc == 5 ? s.tintConst & 0xFFFFFFu : 0xFFFFFFu;
            uint32_t mean = s.mean & 0xFFFFFFu;
            if (wat) mean = water;
            else if (!mean) mean = 0x808080u;
            uint32_t tm = mulTint(0xFF000000u | mean, tint);
            AtlasMaterial am{};
            am.albedo[0] = lin8((tm >> 16) & 255);
            am.albedo[1] = lin8((tm >> 8) & 255);
            am.albedo[2] = lin8(tm & 255);
            am.roughness = wat ? 0.05f : 0.85f;
            // (keep=near: every emitter at its level, as AtlasMaterials.material now gives the game's; else the old 10)
            int minEmit = modelled ? 1 : 10;
            am.emissive = s.emit >= minEmit ? float(s.emit) / 15.0f : 0.0f;
            // (keep=near: plants are foliage as AtlasMaterials.material classes them; else blocks.bin's leaves alone)
            bool fol = s.cls == 1 || (modelled && s.cls == 4);
            am.cls = wat ? ATLAS_CLASS_WATER : s.emit >= minEmit ? ATLAS_CLASS_EMISSIVE : fol ? ATLAS_CLASS_FOLIAGE : ATLAS_CLASS_TERRAIN;
            AtlasMaterialShape sh{};
            sh.occupancy = wat ? ~0ull : s.mask;
            sh.mean = tm;
            for (int f = 0; f < 6; f++)
                for (int t = 0; t < 16; t++) {
                    uint32_t c = s.faces[f][t];
                    // the game tints the tinted quads' texels (AtlasShapes.face), which are grey: a grass block's
                    // overlay, not the dirt beside and under it (blocks.bin's tiles are untinted and do not say)
                    int r = int((c >> 16) & 255), g = int((c >> 8) & 255), b = int(c & 255);
                    bool grey = std::max(r, std::max(g, b)) - std::min(r, std::min(g, b)) <= 12;
                    sh.faces[f][t] = (c >> 24) ? (grey || wat || s.tintSrc == 255 ? mulTint(c, tint) : c) : 0u;
                }
            // cut-out coverage for leaves and plants only (a grass block's overlay alpha is no hole in it)
            sh.flags = (s.cls == 1 || s.cls == 4) && cutouts ? ATLAS_SHAPE_CUTOUT : 0u;
            // (keep=near: a cut-out cube with holes, modelled 2: AtlasShapes.holedCube)
            if (modelled && id < modelled->size() && (*modelled)[id] == 2 && cutouts) {
                bool holes = false;
                for (int f = 0; f < 6; f++)
                    for (int t = 0; t < 16; t++) holes |= (s.faces[f][t] >> 24) < 250;
                if (holes) sh.flags = ATLAS_SHAPE_CUTOUT;
            }
            if (wat) sh.flags = 0;
            m = uint16_t(firstMat + mats.size());
            out.matState.push_back(id);
            mats.push_back(am);
            shapes.push_back(sh);
            out.shaped += sh.occupancy != ~0ull || sh.flags ? 1 : 0;
        }
        out.matOf.emplace(id, m);
        if (perBlock && m && !blk.empty()) blockMat.emplace(blk, m);
        return m;
    };
    std::vector<uint16_t> ids(4096);
    std::vector<std::vector<uint16_t>> conv(secs.size());
    for (size_t k = 0; k < secs.size(); k++) {
        const Section& s = secs[k];
        conv[k].resize(4096);
        for (int i = 0; i < 4096; i++) conv[k][size_t(i)] = materialOf(s.at(i));
        auto [it, fresh] = out.chunks.try_emplace(Loaded::key(s.cx, s.cz), s.sy, s.sy);
        if (!fresh) it->second.first = std::min(it->second.first, s.sy), it->second.second = std::max(it->second.second, s.sy);
        if (k == 0) out.x0 = s.cx * 16, out.z0 = s.cz * 16, out.x1 = s.cx * 16 + 16, out.z1 = s.cz * 16 + 16;
        out.x0 = std::min(out.x0, s.cx * 16), out.z0 = std::min(out.z0, s.cz * 16);
        out.x1 = std::max(out.x1, s.cx * 16 + 16), out.z1 = std::max(out.z1, s.cz * 16 + 16);
    }
    if (firstMat + mats.size() > 16383) return why = "too many materials for the brick pool (ids under 16384)", false;
    if (!mats.empty() && atlas_set_materials(ctx, firstMat, mats.data(), uint32_t(mats.size())) != ATLAS_OK)
        return why = atlas_last_error(ctx), false;
    // the shapes (an older core has no such call: the groups then draw plain cubes)
    if (!shapes.empty() && atlas_set_material_shapes(ctx, firstMat, shapes.data(), uint32_t(shapes.size())) != ATLAS_OK)
        return why = atlas_last_error(ctx), false;
    out.shapes = shapes;
    out.firstMat = firstMat;
    for (size_t k = 0; k < secs.size(); k++) {
        bool any = false;
        for (uint16_t v : conv[k]) any |= v != 0;
        if (!any) continue;
        out.sections++;
        if (hold) {
            out.held.push_back({secs[k].cx, secs[k].sy, secs[k].cz, conv[k], false});
            continue;
        }
        if (atlas_upload_section(ctx, secs[k].cx, secs[k].sy, secs[k].cz, conv[k].data()) != ATLAS_OK)
            return why = atlas_last_error(ctx), false;
    }
    out.states = mats.size();
    return true;
}

// The ground under the export, per block column: the top of its highest kept block that is not foliage (+1), its
// material and the one under it; NaN where the export has nothing.
struct Ground {
    int x0 = 0, z0 = 0, w = 0, h = 0;
    std::vector<float> top;
    std::vector<uint16_t> topMat, underMat;
    bool at(int x, int z, float& t, uint16_t& m, uint16_t& u) const {
        x -= x0, z -= z0;
        if (x < 0 || z < 0 || x >= w || z >= h) return false;
        size_t i = size_t(z) * size_t(w) + size_t(x);
        if (std::isnan(top[i])) return false;
        t = top[i], m = topMat[i], u = underMat[i];
        return true;
    }
};

inline Ground ground(const std::vector<Section>& secs, const std::vector<State>& lib, const Loaded& ld) {
    Ground g;
    g.x0 = ld.x0, g.z0 = ld.z0, g.w = ld.x1 - ld.x0, g.h = ld.z1 - ld.z0;
    size_t n = size_t(g.w) * size_t(g.h);
    g.top.assign(n, NAN);
    g.topMat.assign(n, 0);
    g.underMat.assign(n, 0);
    std::vector<int> best(n, -100000);
    for (const Section& s : secs)
        for (int i = 0; i < 4096; i++) {
            uint32_t id = s.at(i);
            if (!id) continue;
            auto it = ld.matOf.find(id);
            if (it == ld.matOf.end() || !it->second) continue;
            if (id < lib.size() && lib[id].cls == 1) continue;  // leaves: not the ground
            int lx = i & 15, lz = (i >> 4) & 15, ly = i >> 8;
            int x = s.cx * 16 + lx - g.x0, z = s.cz * 16 + lz - g.z0, y = s.sy * 16 + ly;
            size_t c = size_t(z) * size_t(g.w) + size_t(x);
            if (y > best[c]) {
                if (best[c] == y - 1) g.underMat[c] = g.topMat[c];
                best[c] = y;
                g.top[c] = float(y + 1);
                g.topMat[c] = it->second;
            }
        }
    for (size_t c = 0; c < n; c++)
        if (!std::isnan(g.top[c]) && !g.underMat[c]) g.underMat[c] = g.topMat[c];
    return g;
}

// atlas_truth bake= (ALC1): per card i32 x y z face, 256 u32 ARGB texels, 256 u8 sky and 256 u8 block light
inline bool readCards(const std::string& path, std::vector<AtlasSurfaceCard>& out) {
    std::ifstream f(path, std::ios::binary);
    char magic[4];
    int32_t n = 0;
    if (!f.read(magic, 4) || std::memcmp(magic, "ALC1", 4) != 0 || !f.read(reinterpret_cast<char*>(&n), 4) || n < 0) return false;
    out.resize(size_t(n));
    for (auto& c : out) {
        int32_t h[4];
        f.read(reinterpret_cast<char*>(h), sizeof h);
        c.x = h[0], c.y = h[1], c.z = h[2], c.face = uint32_t(h[3]);
        f.read(reinterpret_cast<char*>(c.texels), sizeof c.texels);
        f.read(reinterpret_cast<char*>(c.sky), sizeof c.sky);
        f.read(reinterpret_cast<char*>(c.block), sizeof c.block);
    }
    return bool(f);
}

// ---- PNG reading (our tools' stored-deflate PNGs) and the harness's colour metric ----------------------------------

inline bool readPng(const std::string& path, std::vector<uint32_t>& rgba, uint32_t& w, uint32_t& h) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (d.size() < 33 || d[1] != 'P') return false;
    auto be = [&](size_t p) { return uint32_t(d[p]) << 24 | uint32_t(d[p + 1]) << 16 | uint32_t(d[p + 2]) << 8 | d[p + 3]; };
    size_t p = 8;
    std::vector<uint8_t> z;
    uint8_t bitDepth = 0, colour = 0;
    while (p + 8 <= d.size()) {
        uint32_t len = be(p);
        std::string type(reinterpret_cast<const char*>(&d[p + 4]), 4);
        if (p + 12 + len > d.size()) return false;
        if (type == "IHDR") w = be(p + 8), h = be(p + 12), bitDepth = d[p + 16], colour = d[p + 17];
        if (type == "IDAT") z.insert(z.end(), d.begin() + long(p + 8), d.begin() + long(p + 8 + len));
        p += 12 + len;
    }
    if (bitDepth != 8 || (colour != 6 && colour != 2)) return false;
    int bpp = colour == 6 ? 4 : 3;
    std::vector<uint8_t> raw;
    size_t q = 2;  // zlib header
    for (;;) {
        if (q + 5 > z.size()) return false;
        uint8_t hdr = z[q];
        if ((hdr >> 1) & 3) return false;  // compressed blocks: not ours
        uint16_t len = uint16_t(z[q + 1] | z[q + 2] << 8);
        raw.insert(raw.end(), z.begin() + long(q + 5), z.begin() + long(q + 5 + len));
        q += 5 + len;
        if (hdr & 1) break;
    }
    size_t stride = size_t(w) * bpp + 1;
    if (raw.size() < stride * h) return false;
    rgba.resize(size_t(w) * h);
    for (uint32_t y = 0; y < h; y++) {
        if (raw[y * stride] != 0) return false;  // filtered rows: not ours
        for (uint32_t x = 0; x < w; x++) {
            const uint8_t* px = &raw[y * stride + 1 + size_t(x) * bpp];
            rgba[size_t(y) * w + x] = uint32_t(px[0]) | uint32_t(px[1]) << 8 | uint32_t(px[2]) << 16 | (bpp == 4 ? uint32_t(px[3]) << 24 : 0xFF000000u);
        }
    }
    return true;
}

// tools/harness/results.py _lab: sRGB 8-bit -> CIE Lab (D65)
inline void lab(uint32_t c, float out[3]) {
    float v[3];
    for (int k = 0; k < 3; k++) {
        float a = float((c >> (8 * k)) & 255) / 255.0f;
        v[k] = a <= 0.04045f ? a / 12.92f : std::pow((a + 0.055f) / 1.055f, 2.4f);
    }
    float X = (0.4124f * v[0] + 0.3576f * v[1] + 0.1805f * v[2]) / 0.95047f;
    float Y = 0.2126f * v[0] + 0.7152f * v[1] + 0.0722f * v[2];
    float Z = (0.0193f * v[0] + 0.1192f * v[1] + 0.9505f * v[2]) / 1.08883f;
    auto fn = [](float t) { return t > 0.008856f ? std::cbrt(t) : 7.787f * t + 16.0f / 116.0f; };
    out[0] = 116.0f * fn(Y) - 16.0f, out[1] = 500.0f * (fn(X) - fn(Y)), out[2] = 200.0f * (fn(Y) - fn(Z));
}

}  // namespace exportdata
