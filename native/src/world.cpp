#include "world.hpp"

#include "sectionpack.hpp"
#include "sectionstore.hpp"
#include "parallel.hpp"

#ifdef _WIN32
// (the builders below the game's threads; windows.h itself would bring its near / far macros in)
extern "C" __declspec(dllimport) int __stdcall SetThreadPriority(void* thread, int priority);
extern "C" __declspec(dllimport) void* __stdcall GetCurrentThread();
#endif

#include <algorithm>
#include <bit>
#include <atomic>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <unordered_set>
#include <cstring>
#include <map>
#include <thread>

namespace atlas {
namespace {

int floorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

// Voxelises one region from its LOD's column tile, with chunk sections overriding at LOD 0.
class TileVoxels final : public VoxelSource {
public:
    using SectionMap = std::unordered_map<Key3, std::shared_ptr<const Section>, Key3Hash>;
    TileVoxels(const Tile* tile, const Key3& k, uint16_t stone, uint16_t water, const SectionMap* sections)
        : t_(tile), stone_(stone), water_(water), sections_(sections) {
        s_ = float(1 << k.lod);
        y0_ = float(WORLD_MIN_Y) + float(k.y) * 256.0f * s_;
        bx_ = k.x * 256;
        by_ = WORLD_MIN_Y + k.y * 256;
        bz_ = k.z * 256;
        lod_ = k.lod;
        // real blocks (sections) and their generated levels up to LOD 4 (a section is one 16-block voxel there)
        if (k.lod > 4 || !sections_ || sections_->empty()) sections_ = nullptr;
    }

    BoxClass classify(int x, int y, int z, int n, uint16_t& mat) override {
        if (sections_ && anySection(x, y, z, n)) return BoxClass::Mixed;
        if (!t_) return BoxClass::Air;
        int lv = 0;
        while ((1 << lv) < n) lv++;
        int per = TILE >> lv;
        size_t c = size_t(z >> lv) * per + size_t(x >> lv);
        float minS = t_->minSurf[lv][c], maxT = t_->maxTop[lv][c];
        float lo = y0_ + (float(y) + 0.5f) * s_, hi = y0_ + (float(y + n) - 0.5f) * s_;
        if (lo >= maxT) return BoxClass::Air;
        if (minS - hi > s_ + FILL_DEPTH) {
            mat = stone_;
            return BoxClass::Uniform;
        }
        return BoxClass::Mixed;
    }

    void brick(int x, int y, int z, uint16_t out[64]) override {
        for (int k = 0; k < 4; k++)
            for (int j = 0; j < 4; j++)
                for (int i = 0; i < 4; i++) out[i + 4 * j + 16 * k] = voxel(x + i, y + j, z + k);
    }

    uint16_t voxel(int x, int y, int z) const {
        if (sections_) {
            // the voxel's first block in world blocks, its section, its coordinates in that section's level (the last
            // section found is kept: a region's 16 M voxels looked each one up in the map, seconds a region at level 4)
            int s = 1 << lod_;
            int wx = (bx_ + x) * s, wy = WORLD_MIN_Y + (by_ - WORLD_MIN_Y + y) * s, wz = (bz_ + z) * s;
            Key3 key{0, floorDiv(wx, 16), floorDiv(wy, 16), floorDiv(wz, 16)};
            if (!haveLast_ || !(key == lastKey_)) {
                auto it = sections_->find(key);
                last_ = it != sections_->end() && (lod_ == 0 || !it->second->mip[0].empty()) ? it->second.get() : nullptr;
                lastKey_ = key;
                haveLast_ = true;
            }
            if (last_) return last_->at(lod_, (wx & 15) >> lod_, (wy & 15) >> lod_, (wz & 15) >> lod_);
        }
        if (!t_) return 0;
        size_t c = size_t(z) * TILE + size_t(x);
        float yc = y0_ + (float(y) + 0.5f) * s_;
        return columnVoxel(*t_, c, yc, s_, stone_, water_);
    }

    static uint16_t columnVoxel(const Tile& t, size_t c, float yc, float s, uint16_t stone, uint16_t water) {
        float surf = t.surface[c];
        if (yc < surf) {
            float depth = surf - yc;
            if (depth <= s) {
                // a forest seen from far: crowns too thin for this voxel size are the ground's colour (as forests read
                // from afar), not geometry; tested at the voxel's centre they vanished in most columns, and filling the
                // whole voxel would raise a slab as tall as the voxel
                if (!t.canopy.empty() && t.canopy[c] > 0.0f && 0.65f * t.canopy[c] < 0.75f * s) return t.canopyMat[c];
                return t.top[c];
            }
            if (depth <= s + FILL_DEPTH) return t.fill[c];
            return stone;
        }
        if (t.structures) {
            const Structures& st = *t.structures;
            float rt = st.roofTop[c];
            bool inside = yc < rt && (st.base.empty() || yc >= st.base[c]);  // (rt > surf: no NaN gets here)
            if (!inside && rt > surf) {
                // a span thinner than the voxel (a tree crown, a roof at a coarse LOD): the voxel shows it when they
                // overlap, else a centre-point test lost it in most columns
                float lo = st.base.empty() || std::isnan(st.base[c]) ? surf : st.base[c];
                if (rt - lo < s) inside = yc + 0.5f * s > lo && yc - 0.5f * s < rt;
            }
            if (inside) return yc < st.wallTop[c] ? st.wallMat[c] : st.roofMat[c];
        }
        // canopy: crowns over trunk space (the lowest 35 % stays open, so forest edges are not solid walls), as geometry
        // while the voxel is small enough to resolve the crowns' band (thinner bands colour the ground: above)
        if (!t.canopy.empty() && t.canopy[c] > 0.0f && 0.65f * t.canopy[c] >= 0.75f * s && yc < surf + t.canopy[c]
            && yc >= surf + 0.35f * t.canopy[c])
            return t.canopyMat[c];
        float w = t.water[c];
        if (!std::isnan(w) && yc < w) return t.waterMat.empty() || !t.waterMat[c] ? water : t.waterMat[c];
        return 0;
    }

private:
    bool anySection(int x, int y, int z, int n) const {
        int s = 1 << lod_;
        int sx0 = floorDiv((bx_ + x) * s, 16), sx1 = floorDiv((bx_ + x + n) * s - 1, 16);
        int sy0 = floorDiv(WORLD_MIN_Y + (by_ - WORLD_MIN_Y + y) * s, 16), sy1 = floorDiv(WORLD_MIN_Y + (by_ - WORLD_MIN_Y + y + n) * s - 1, 16);
        int sz0 = floorDiv((bz_ + z) * s, 16), sz1 = floorDiv((bz_ + z + n) * s - 1, 16);
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sz = sz0; sz <= sz1; sz++)
                for (int sx = sx0; sx <= sx1; sx++)
                    if (sections_->count({0, sx, sy, sz})) return true;
        return false;
    }

    const Tile* t_;
    uint16_t stone_, water_;
    const SectionMap* sections_;
    mutable Key3 lastKey_{};
    mutable const Section* last_ = nullptr;
    mutable bool haveLast_ = false;
    int lod_ = 0;
    float s_, y0_;
    int bx_, by_, bz_;
};

// Real blocks at a coarse level (docs/ATLAS-LOD.md, "Coarse levels from real blocks"): each micro-voxel of a level-lod
// voxel (2^lod / 4 blocks a side) that its blocks only partly fill keeps their content a quarter finer, its 4 x 4 x 4
// sub-voxels (groups.hpp SubVoxels; GroupInput::faces), each there by its own coverage of what its blocks hold (a
// shaped block by its shape, cut-out leaves by their texels' alpha), hashed in world space; a ray meets the micro-voxel
// where it meets one of them. So a crown's edge, a trunk, a roof, the top of a slope keep their outline as they coarsen
// (one coverage value a micro-voxel, whatever its rule, drew a far tree at 1.3 - 2 times its area: docs). buildGroups
// (sections at hand) and the coarse levels' digest (World::digest) share it, so a level made from the store is the level
// made from the sections, bit for bit.
class Coverage {
public:
    explicit Coverage(const ShapeSet& shapes) : shapes_(shapes) {}

    // a block's alpha per micro-voxel (its shape; 255 solid, 0 none), and its opacity along each axis (the mean over its
    // 4 x 4 micro columns of 1 - the product of (1 - alpha))
    struct Block {
        uint8_t alpha[64];
        float op[3];
    };
    const Block& block(uint16_t m) {
        if (m >= blocks_.size()) blocks_.resize(size_t(m) + 1), known_.resize(size_t(m) + 1, 0);
        Block& b = blocks_[m];
        if (!known_[m]) {
            known_[m] = 1;
            const MaterialShape* s = shapes_.at(m);
            for (int i = 0; i < 64; i++) b.alpha[i] = !m ? 0 : !s ? 255 : ((s->mask >> i) & 1) ? s->alpha[i] : 0;
            for (int a = 0; a < 3; a++) {
                float sum = 0;
                for (int v = 0; v < 4; v++)
                    for (int u = 0; u < 4; u++) {
                        float t = 1.0f;
                        for (int k = 0; k < 4; k++) {
                            int q[3];
                            q[a] = k, q[(a + 1) % 3] = u, q[(a + 2) % 3] = v;
                            t *= 1.0f - float(b.alpha[q[0] + 4 * (q[2] + 4 * q[1])]) / 255.0f;
                        }
                        sum += 1.0f - t;
                    }
                b.op[a] = sum / 16.0f;
            }
        }
        return b;
    }
    // whether a sub-voxel of coverage v is there: by a hash of its world position (level, quarter micro-voxel)
    static bool keepSub(float v, int lod, int wx, int wy, int wz) {
        if (v <= 0.0f) return false;
        if (v >= 0.999f) return true;
        return (cutHash(wx + lod * 1000003, wy, wz ^ 0x51ED) >> 24) < uint32_t(v * 255.0f + 0.5f);
    }
    // The sub-voxels of a micro-voxel of level lod >= 1 whose first corner is section quarter-block q0 (world quarter
    // block w0): its 4 x 4 x 4 quarters (bit x + 4 (z + 4 y)), each there by its coverage of the blocks inside the
    // section box [clo, chi) (section blocks): from level 4 up a quarter is whole blocks (1, 8, 64 of them: the mean over
    // the axes of the share of its block columns a ray along that axis is stopped in, by the blocks' opacity), below it
    // part of one block (its own micro-voxels' alpha).
    uint64_t micro(const Section& sec, int lod, const int q0[3], const int w0[3], const int clo[3], const int chi[3]) {
        int N = 1 << lod;  // quarter blocks a side
        auto mat = [&](int bx, int by, int bz) -> uint16_t {
            if (bx < clo[0] || by < clo[1] || bz < clo[2] || bx >= chi[0] || by >= chi[1] || bz >= chi[2]) return 0;
            return sec.mats[size_t(bx + 16 * (bz + 16 * by))];
        };
        if (lod >= 2) {  // all air or all solid cubes (most of them): nothing to look at
            int n = N / 4, empty = 0, solid = 0;
            for (int y = 0; y < n; y++)
                for (int z = 0; z < n; z++)
                    for (int x = 0; x < n; x++) {
                        const Block& b = block(mat(q0[0] / 4 + x, q0[1] / 4 + y, q0[2] / 4 + z));
                        empty += b.op[0] + b.op[1] + b.op[2] <= 0.0f;
                        solid += b.op[0] >= 1.0f && b.op[1] >= 1.0f && b.op[2] >= 1.0f;
                    }
            if (empty == n * n * n) return 0;
            if (solid == n * n * n) return ~0ull;
        }
        uint64_t out = 0;
        for (int i = 0; i < 64; i++) {
            int s[3] = {i & 3, i >> 4, (i >> 2) & 3};
            float val = 0;
            int wq[3];
            if (lod >= 4) {
                int B = N / 16;  // blocks a side of a quarter
                int p0[3] = {q0[0] / 4 + s[0] * B, q0[1] / 4 + s[1] * B, q0[2] / 4 + s[2] * B};
                if (B == 1) {
                    const Block& b = block(mat(p0[0], p0[1], p0[2]));
                    val = (b.op[0] + b.op[1] + b.op[2]) / 3.0f;
                } else {
                    for (int a = 0; a < 3; a++) {
                        int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
                        float cov = 0;
                        for (int v = 0; v < B; v++)
                            for (int u = 0; u < B; u++) {
                                float t = 1.0f;
                                for (int k = 0; k < B && t > 0.0f; k++) {
                                    int p[3];
                                    p[a] = p0[a] + k, p[a1] = p0[a1] + u, p[a2] = p0[a2] + v;
                                    t *= 1.0f - block(mat(p[0], p[1], p[2])).op[a];
                                }
                                cov += 1.0f - t;
                            }
                        val += cov / float(B * B);
                    }
                    val /= 3.0f;
                }
                for (int a = 0; a < 3; a++) wq[a] = w0[a] / (N / 4) + s[a];
            } else {
                // a quarter of S x S x S of a block's own micro-voxels (lod 1: half of one, the one holding it)
                int S = std::max(1, N / 4), cnt = 0;
                for (int k = 0; k < S; k++)
                    for (int j = 0; j < S; j++)
                        for (int h = 0; h < S; h++) {
                            int q[3] = {lod == 1 ? q0[0] + (s[0] >> 1) : q0[0] + s[0] * S + h,
                                        lod == 1 ? q0[1] + (s[1] >> 1) : q0[1] + s[1] * S + k,
                                        lod == 1 ? q0[2] + (s[2] >> 1) : q0[2] + s[2] * S + j};
                            const Block& b = block(mat(q[0] >> 2, q[1] >> 2, q[2] >> 2));
                            val += float(b.alpha[(q[0] & 3) + 4 * ((q[2] & 3) + 4 * (q[1] & 3))]) / 255.0f;
                            cnt++;
                        }
                val /= float(cnt);
                for (int a = 0; a < 3; a++) wq[a] = lod == 1 ? w0[a] * 2 + s[a] : w0[a] / S + s[a];
            }
            if (keepSub(val, lod, wq[0], wq[1], wq[2])) out |= 1ull << i;
        }
        return out;
    }
    // the section blocks [lo, hi) holding anything inside the n^3 from section block b (false: none)
    static bool contentBox(const Section& sec, int bx, int by, int bz, int n, int lo[3], int hi[3]) {
        for (int a = 0; a < 3; a++) lo[a] = 1 << 20, hi[a] = -1;
        for (int y = by; y < by + n; y++)
            for (int z = bz; z < bz + n; z++)
                for (int x = bx; x < bx + n; x++) {
                    if (!sec.mats[size_t(x + 16 * (z + 16 * y))]) continue;
                    int q[3] = {x, y, z};
                    for (int a = 0; a < 3; a++) lo[a] = std::min(lo[a], q[a]), hi[a] = std::max(hi[a], q[a] + 1);
                }
        return hi[0] > lo[0];
    }
    // A section's voxel of level lod (1..4) whose first block is world (wx, wy, wz): its material (`mip`, the section's
    // generated level there, or where that vote left it air its blocks' commonest; 0: nothing), its micro-voxels, their
    // face masks and its content's box (groups.hpp packBox: 16ths of the voxel, from its blocks' bounds).
    uint16_t voxel(const Section& sec, int lod, int wx, int wy, int wz, uint16_t mip, uint64_t& mask, uint32_t& box,
                   SubVoxels& fm) {
        fm.partial = 0;
        int bx = wx & 15, by = wy & 15, bz = wz & 15;  // its first block in the section
        int n = 1 << lod;
        mask = 0;
        box = BOX_FULL;
        int clo[3], chi[3];
        if (!contentBox(sec, bx, by, bz, n, clo, chi)) return 0;
        {
            int u = 16 / n, lo[3], hi[3];  // its 16ths a block
            int b0[3] = {bx, by, bz};
            for (int a = 0; a < 3; a++) lo[a] = (clo[a] - b0[a]) * u, hi[a] = (chi[a] - b0[a]) * u;
            box = packBox(lo, hi);
        }
        uint16_t common = 0;
        if (!mip) {
            uint16_t seen[8] = {};
            int counts[8] = {};
            for (int y = 0; y < n; y++)
                for (int z = 0; z < n; z++)
                    for (int x = 0; x < n; x++) {
                        uint16_t m = sec.mats[size_t((bx + x) + 16 * ((bz + z) + 16 * (by + y)))];
                        if (!m) continue;
                        int k = 0;
                        while (k < 8 && seen[k] && seen[k] != m) k++;
                        if (k == 8) continue;
                        seen[k] = m;
                        counts[k]++;
                    }
            int best = 0;
            for (int k = 0; k < 8; k++)
                if (counts[k] > best) best = counts[k], common = seen[k];
        }
        uint16_t mat = mip ? mip : common;
        if (!mat) return 0;
        int N = 1 << lod;  // a micro-voxel's quarter blocks
        for (int b = 0; b < 64; b++) {
            int m3[3] = {b & 3, b >> 4, (b >> 2) & 3};
            int q0[3] = {bx * 4 + m3[0] * N, by * 4 + m3[1] * N, bz * 4 + m3[2] * N};
            int w0[3] = {wx * 4 + m3[0] * N, wy * 4 + m3[1] * N, wz * 4 + m3[2] * N};
            setMicro(mask, fm, b, micro(sec, lod, q0, w0, clo, chi));
        }
        return mask ? mat : 0;
    }
    // a micro-voxel's sub-voxels into a voxel's micro-voxels and sub-voxels (all there: solid, none kept)
    static void setMicro(uint64_t& mask, SubVoxels& fm, int b, uint64_t sub) {
        if (sub) mask |= 1ull << b;
        else mask &= ~(1ull << b);
        if (sub && sub != ~0ull) fm.partial |= 1ull << b;
        else fm.partial &= ~(1ull << b);
        fm.sub[size_t(b)] = sub;
    }

private:
    const ShapeSet& shapes_;
    std::vector<Block> blocks_;
    std::vector<uint8_t> known_;
};

// the voxel of (lod, cube) holding world block (x, y, z): its index in the cube
uint32_t cubeIndex(int level, int x, int y, int z) {
    int n = COARSE_CUBE >> level;
    int lx = (x - floorDiv(x, COARSE_CUBE) * COARSE_CUBE) >> level, lz = (z - floorDiv(z, COARSE_CUBE) * COARSE_CUBE) >> level;
    int yy = y - WORLD_MIN_Y, ly = (yy - floorDiv(yy, COARSE_CUBE) * COARSE_CUBE) >> level;
    return uint32_t(lx + n * (lz + n * ly));
}
Key3 cubeKey(int level, int x, int y, int z) {
    return {level, floorDiv(x, COARSE_CUBE), floorDiv(y - WORLD_MIN_Y, COARSE_CUBE), floorDiv(z, COARSE_CUBE)};
}
// a section's position in its cube (16 a side)
void sectionInCube(int sx, int sy, int sz, int& lx, int& ly, int& lz) {
    lx = sx - floorDiv(sx, 16) * 16;
    lz = sz - floorDiv(sz, 16) * 16;
    int yy = sy - WORLD_MIN_Y / 16;
    ly = yy - floorDiv(yy, 16) * 16;
}
Key3 sectionCube(int level, int sx, int sy, int sz) {
    return {level, floorDiv(sx, 16), floorDiv(sy - WORLD_MIN_Y / 16, 16), floorDiv(sz, 16)};
}

// The material a coarse voxel shows, of its children (x + 2 z + 4 y) of kind `want` (-1: any): the commonest among
// those seen from above (in its upper layer, or under a child of another kind or none), ties to the higher child. The
// commonest of all was the buried one: the dirt under a grass top, a hillside's stone (far land read brown where the
// near field is green, more so the coarser the level, docs/ATLAS-RENDERER-PLAN.md).
template <class Kind>
uint16_t shownKid(const uint16_t ch[8], int want, const Kind& kind) {
    auto shown = [&](int a) { return a >= 4 || !ch[a + 4] || kind(ch[a + 4]) != kind(ch[a]); };
    uint16_t best = 0;
    int bestN = 0;
    for (int a = 7; a >= 0; a--) {  // the upper children first: ties go up
        if (!ch[a] || (want >= 0 && kind(ch[a]) != want) || !shown(a)) continue;
        int c = 0;
        for (int b = 0; b < 8; b++) c += ch[b] == ch[a] && shown(b) ? 1 : 0;
        if (c > bestN) bestN = c, best = ch[a];
    }
    return best;
}

// the material of a coarse voxel from its 8 children's (Section::buildMips' vote: solid when 3 or more are, foliage
// when 2, water when 4; the commonest of the winning kind's shown children, ties up), or where the vote is air the
// commonest child with anything (its coverage decides whether it shows)
uint16_t voteKids(const uint16_t ch[8], const std::vector<uint8_t>& cls) {
    auto kind = [&](uint16_t m) -> int {
        if (m == 0) return -1;
        uint8_t c = m < cls.size() ? cls[m] : 0;
        return c == 1 ? 1 : c == 2 ? 2 : 0;
    };
    int counts[3] = {0, 0, 0};
    for (int k = 0; k < 8; k++)
        if (kind(ch[k]) >= 0) counts[kind(ch[k])]++;
    int want = counts[0] >= 3 ? 0 : counts[1] >= 2 ? 1 : counts[2] >= 4 ? 2 : -1;
    return shownKid(ch, want, kind);
}

}  // namespace

const CoarseCube::Entry* CoarseCube::find(uint32_t idx) const {
    auto it = std::lower_bound(entries.begin(), entries.end(), idx, [](const Entry& e, uint32_t i) { return e.idx < i; });
    return it != entries.end() && it->idx == idx ? &*it : nullptr;
}

void Tile::buildPyramids() {
    for (int lv = 0; lv <= 8; lv++) {
        int per = TILE >> lv;
        minSurf[lv].assign(size_t(per) * per, 0.0f);
        maxTop[lv].assign(size_t(per) * per, 0.0f);
    }
    for (int j = 0; j < TILE; j++) {
        for (int i = 0; i < TILE; i++) {
            size_t c = size_t(j) * TILE + i;
            float s = surface[c];
            float top = s;
            if (!canopy.empty() && canopy[c] > 0.0f) top = std::max(top, s + canopy[c]);
            if (structures && structures->roofTop[c] > s) top = std::max(top, structures->roofTop[c]);
            if (!std::isnan(water[c])) top = std::max(top, water[c]);
            minSurf[0][c] = s;
            maxTop[0][c] = top;
        }
    }
    for (int lv = 1; lv <= 8; lv++) {
        int per = TILE >> lv, prev = TILE >> (lv - 1);
        for (int j = 0; j < per; j++) {
            for (int i = 0; i < per; i++) {
                size_t a = size_t(2 * j) * prev + 2 * i, b = a + 1, c = a + prev, d = c + 1;
                const auto& pm = minSurf[lv - 1];
                const auto& px = maxTop[lv - 1];
                minSurf[lv][size_t(j) * per + i] = std::min(std::min(pm[a], pm[b]), std::min(pm[c], pm[d]));
                maxTop[lv][size_t(j) * per + i] = std::max(std::max(px[a], px[b]), std::max(px[c], px[d]));
            }
        }
    }
}

int World::regionRows(int lod) {
    int span = 256 << lod;
    return (WORLD_TOP_Y - WORLD_MIN_Y + span - 1) / span;
}

void World::putTile(std::unique_ptr<Tile> t) {
    Key3 k{t->lod, t->tx, 0, t->tz};
    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = structures_.find(k);
        t->structures = it == structures_.end() ? nullptr : it->second;
    }
    if (t->structures && t->structures->replacesCanopy) {  // the real trees are up: no canopy lumps under them
        t->canopy.clear();
        t->canopyMat.clear();
    }
    t->buildPyramids();
    std::lock_guard<std::mutex> lock(mu_);
    for (int ry = 0; ry < regionRows(t->lod); ry++) dirty_.insert({t->lod, t->tx, ry, t->tz});
    tiles_[k] = std::shared_ptr<const Tile>(std::move(t));
}

const Tile* World::tile(int lod, int tx, int tz) const {
    auto it = tiles_.find({lod, tx, 0, tz});
    return it == tiles_.end() ? nullptr : it->second.get();
}

void World::putStructures(int lod, int tx, int tz, std::shared_ptr<const Structures> s) {
    Key3 k{lod, tx, 0, tz};
    std::shared_ptr<const Tile> old;
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (s) structures_[k] = s;
        else structures_.erase(k);
        auto it = tiles_.find(k);
        if (it == tiles_.end()) return;  // attached when the ground arrives
        old = it->second;
    }
    // tiles are immutable once published (builders hold snapshots): replace with a copy carrying the new buildings
    auto t = std::make_unique<Tile>(*old);
    t->structures = s;
    if (s && s->replacesCanopy) {
        t->canopy.clear();
        t->canopyMat.clear();
    }
    t->buildPyramids();
    std::lock_guard<std::mutex> lock(mu_);
    auto it = tiles_.find(k);
    if (it == tiles_.end() || it->second != old) return;  // replaced meanwhile: that upload attached the latest
    for (int ry = 0; ry < regionRows(lod); ry++) dirty_.insert({lod, tx, ry, tz});
    it->second = std::shared_ptr<const Tile>(std::move(t));
}

bool World::hasTile(int lod, int tx, int tz) const {
    std::lock_guard<std::mutex> lock(mu_);
    return tiles_.count({lod, tx, 0, tz}) != 0;
}

void World::setClasses(std::vector<uint8_t> cls) {
    std::lock_guard<std::mutex> lock(mu_);
    classes_ = std::move(cls);
}

World::World() : store_(std::make_unique<SectionStore>()) {}

std::shared_ptr<const Section> World::putSection(int sx, int sy, int sz, const uint16_t* mats, uint8_t flags) {
    auto s = std::make_shared<Section>();
    std::copy(mats, mats + 4096, s->mats.begin());
    // (its generated levels outside the world's lock: an upload holds it for the map's update alone; none when the
    // grid's coarse levels own every level that reads them: the GPU makes them, no CPU stage per section)
    if (!gridOwnsReal()) {
        std::vector<uint8_t> cls;
        {
            std::lock_guard<std::mutex> lock(mu_);
            cls = classes_;
        }
        s->buildMips(cls);
    }
    std::lock_guard<std::mutex> lock(mu_);
    Key3 k{0, sx, sy, sz};
    sections_[k] = s;
    if (gridCoarse.load(std::memory_order_relaxed) < coarseTop()) undigested_.insert(k);  // (World::digest)
    markSectionDirty(sx, sy, sz);
    // (the tiers: packed on the GPU, then the raw copy goes; the packed one it replaces is stale)
    RawMeta& m = rawMeta_[k];
    m.gen = ++gen_;
    m.flags = flags;
    store_->erase(k);
    if (packOnGpu.load(std::memory_order_relaxed)) packQueue_.push_back({k, m.gen});
    return s;
}

void World::sectionsIn(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, SectionSrc>>& out) {
    std::lock_guard<std::mutex> lock(mu_);
    for (auto& [k, s] : sections_)
        if (k.x >= sx0 && k.x < sx1 && k.z >= sz0 && k.z < sz1) out.emplace_back(k, SectionSrc{s, nullptr});
    std::vector<std::pair<Key3, PackedIds>> packed;
    store_->inRange(sx0, sz0, sx1, sz1, packed);
    for (auto& [k, w] : packed)
        if (!sections_.count(k)) out.emplace_back(k, SectionSrc{nullptr, w});
}

void World::sectionsInBox(int x0, int y0, int z0, int x1, int y1, int z1, std::vector<std::pair<Key3, SectionSrc>>& out) {
    std::lock_guard<std::mutex> lock(mu_);
    for (int z = z0; z < z1; z++)
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++) {
                Key3 k{0, x, y, z};
                auto it = sections_.find(k);
                if (it != sections_.end()) {
                    out.emplace_back(k, SectionSrc{it->second, nullptr});
                } else if (PackedIds w = store_->peek(k)) {
                    out.emplace_back(k, SectionSrc{nullptr, w});
                }
            }
}

World::SectionSrc World::sectionSource(int sx, int sy, int sz) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = sections_.find({0, sx, sy, sz});
    if (it != sections_.end()) return {it->second, nullptr};
    return {nullptr, store_->get({0, sx, sy, sz})};
}

bool World::sectionMats(int sx, int sy, int sz, uint16_t out[4096]) {
    SectionSrc s = sectionSource(sx, sy, sz);
    if (s.raw) {
        std::copy(s.raw->mats.begin(), s.raw->mats.end(), out);
        return true;
    }
    if (!s.packed) return false;
    unpackSection(s.packed->data(), out);  // (one section: the host's own mirror)
    return true;
}

bool World::trySectionsIn(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, SectionSrc>>& out) {
    std::unique_lock<std::mutex> lock(mu_, std::try_to_lock);
    if (!lock.owns_lock()) return false;
    for (auto& [k, s] : sections_)
        if (k.x >= sx0 && k.x < sx1 && k.z >= sz0 && k.z < sz1) out.emplace_back(k, SectionSrc{s, nullptr});
    std::vector<std::pair<Key3, PackedIds>> packed;
    store_->inRange(sx0, sz0, sx1, sz1, packed);
    for (auto& [k, w] : packed)
        if (!sections_.count(k)) out.emplace_back(k, SectionSrc{nullptr, w});
    return true;
}

void World::setPackOnGpu(bool on) {
    if (packOnGpu.load(std::memory_order_relaxed) == on) return;
    std::lock_guard<std::mutex> lock(mu_);
    packOnGpu = on;
    if (!on) return;
    for (auto& [k, m] : rawMeta_) packQueue_.push_back({k, m.gen});  // (uploaded before: packed now too)
}

void World::takePackQueue(size_t max, std::vector<PackReq>& out, size_t burst) {
    std::lock_guard<std::mutex> lock(mu_);
    if (burst > max) max = std::max(max, std::min(burst, packQueue_.size() / 8));
    size_t i = 0;
    for (; i < packQueue_.size() && out.size() < max; i++) {
        auto& [k, gen] = packQueue_[i];
        auto m = rawMeta_.find(k);
        auto s = sections_.find(k);
        if (m == rawMeta_.end() || m->second.gen != gen || s == sections_.end()) continue;  // (stale: newer, or gone)
        out.push_back({k, gen, s->second});
    }
    packQueue_.erase(packQueue_.begin(), packQueue_.begin() + ptrdiff_t(i));
}

void World::packedDone(const std::vector<PackDone>& done) {
    std::lock_guard<std::mutex> lock(mu_);
    for (const PackDone& d : done) {
        auto m = rawMeta_.find(d.key);
        if (m == rawMeta_.end() || m->second.gen != d.gen || !d.words) continue;  // (an upload since replaced it)
        store_->put(d.key, d.words, d.hash, m->second.flags);
        sections_.erase(d.key);
        undigested_.erase(d.key);
        rawMeta_.erase(m);
        packedCount_++;
    }
}

void World::configureStore(const std::string& dir, uint64_t seed, uint32_t genVersion, uint64_t warmBytes, uint64_t coldBytes) {
    std::lock_guard<std::mutex> lock(mu_);
    store_->configure(dir, seed, genVersion, warmBytes ? warmBytes : 512ull << 20, coldBytes ? coldBytes : 4ull << 30);
}

World::StoreStats World::storeStats(void* tiers) const {
    std::lock_guard<std::mutex> lock(mu_);
    StoreStats s;
    s.raw = sections_.size();
    s.rawBytes = uint64_t(sections_.size()) * sizeof(Section);
    s.packQueue = packQueue_.size();
    s.packed = packedCount_;
    if (tiers) *static_cast<SectionStore::Stats*>(tiers) = store_->stats();
    return s;
}

void World::setGridWindow(int ox, int oz, int d) {
    gridOx_ = ox;
    gridOz_ = oz;
    gridD_ = d;
    gridMoved_ = true;
}

void World::gridReleased(const std::vector<Key3>& secs) {
    if (gridOwnsReal()) return;  // (level 0's regions are the tiles' alone: the grid's unknown chunks fall back to them)
    std::lock_guard<std::mutex> lock(mu_);
    bool nbrs = groupsWanted.load(std::memory_order_relaxed);
    for (const Key3& k : secs) {
        gridNotHeld_.insert(k);
        markRegions(k.x, k.y, k.z, 0, 0, dirty_, nbrs);
    }
}

void World::gridWindowMoved() {
    int ox = gridOx_, oz = gridOz_, d = gridD_;
    gridNotHeld_.clear();
    for (auto it = gridSkipped_.begin(); it != gridSkipped_.end();) {
        // a level-0 region: 16 x 16 columns of sections; rebuilt once any of them lies outside the window
        int sx0 = it->x * 16, sz0 = it->z * 16;
        if (sx0 >= ox && sx0 + 16 <= ox + d && sz0 >= oz && sz0 + 16 <= oz + d) {
            ++it;
            continue;
        }
        dirty_.insert(*it);
        it = gridSkipped_.erase(it);
    }
}

size_t World::gridSkippedCount() const {
    std::lock_guard<std::mutex> lock(mu_);
    return gridSkipped_.size();
}

void World::markSectionDirty(int sx, int sy, int sz) {
    // the section's region at every level that reads sections (0..4); with groups also the region across a border it
    // touches (whose border voxels' exposure it decides). (Levels 5 and up read it through the coarse levels: their
    // trees are marked when it is digested, World::digest.) Level 0 at once (what the near light traces); levels 1-4,
    // seen past the near light's cone and from far off, once the uploads pause: a chunk's sections each rebuilt the
    // same five regions again (a level-4 region spans 4096 blocks), and the client's feed kept a core busy for minutes
    bool nbrs = groupsWanted.load(std::memory_order_relaxed);
    // (the GPU's grid holds it: its level-0 region waits until the grid's window leaves it, setGridWindow)
    bool grid = gridOwnsLevel0.load(std::memory_order_relaxed) && gridHolds(sx, sz) && !gridNotHeld_.count({0, sx, sy, sz});
    // (the grid owns every level's real blocks: level 0's regions are the tiles' alone, nothing to rebuild either)
    if (!gridOwnsReal()) markRegions(sx, sy, sz, 0, 0, grid ? gridSkipped_ : dirty_, nbrs);
    // (levels the grid's coarse levels own: nothing to rebuild here)
    int owned = gridCoarse.load(std::memory_order_relaxed);
    if (owned < 4) markRegions(sx, sy, sz, 1 + owned, 4, sectionDirty_, nbrs);
    lastSectionMs_ = nowMs();
}

void World::markRegions(int sx, int sy, int sz, int lo, int hi, std::unordered_set<Key3, Key3Hash>& d, bool nbrs) {
    for (int lod = lo; lod <= hi; lod++) {
        int span = 256 << lod, per = span / 16;
        int rx = floorDiv(sx * 16, span), ry = floorDiv(sy * 16 - WORLD_MIN_Y, span), rz = floorDiv(sz * 16, span);
        d.insert({lod, rx, ry, rz});
        if (!nbrs) continue;
        int ix = sx - rx * per, iz = sz - rz * per, iy = sy - floorDiv(WORLD_MIN_Y, 16) - ry * per;
        if (ix == 0) d.insert({lod, rx - 1, ry, rz});
        if (ix == per - 1) d.insert({lod, rx + 1, ry, rz});
        if (iz == 0) d.insert({lod, rx, ry, rz - 1});
        if (iz == per - 1) d.insert({lod, rx, ry, rz + 1});
        if (iy == 0 && ry > 0) d.insert({lod, rx, ry - 1, rz});
        if (iy == per - 1 && ry + 1 < regionRows(lod)) d.insert({lod, rx, ry + 1, rz});
    }
}

// docs/ATLAS-LOD.md: a coarse voxel from its 2 x 2 x 2 children. Solid (terrain, walls, trunks, emitters' blocks) when
// 3 or more children are solid; else foliage when 2 or more are leaves; else water when 4 or more are water; else air.
// Its material: the most common among the children of the winning kind seen from above (shownKid), ties to the higher.
void Section::buildMips(const std::vector<uint8_t>& cls) {
    auto kind = [&](uint16_t m) -> int {
        if (m == 0) return -1;
        uint8_t c = m < cls.size() ? cls[m] : 0;
        return c == 1 ? 1 : c == 2 ? 2 : 0;  // emissive and falls count as solid blocks
    };
    const uint16_t* src = mats.data();
    int n = 16;
    std::vector<uint16_t> prev(mats.begin(), mats.end());
    for (int level = 1; level <= 4; level++) {
        int m = n / 2;
        std::vector<uint16_t> out(size_t(m) * m * m, 0);
        for (int y = 0; y < m; y++)
            for (int z = 0; z < m; z++)
                for (int x = 0; x < m; x++) {
                    uint16_t ch[8];
                    int counts[3] = {0, 0, 0};
                    for (int k = 0; k < 8; k++) {
                        int cx = 2 * x + (k & 1), cz = 2 * z + ((k >> 1) & 1), cy = 2 * y + (k >> 2);
                        ch[k] = prev[size_t(cx + n * (cz + n * cy))];
                        int kd = kind(ch[k]);
                        if (kd >= 0) counts[kd]++;
                    }
                    int want = counts[0] >= 3 ? 0 : counts[1] >= 2 ? 1 : counts[2] >= 4 ? 2 : -1;
                    if (want < 0) continue;
                    out[size_t(x + m * (z + m * y))] = shownKid(ch, want, kind);
                }
        mip[size_t(level - 1)] = out;
        prev.swap(out);
        n = m;
    }
    (void)src;
}

void World::editBlock(int x, int y, int z, uint16_t mat) {
    std::lock_guard<std::mutex> lock(mu_);
    int sx = floorDiv(x, 16), sy = floorDiv(y, 16), sz = floorDiv(z, 16);
    Key3 sk{0, sx, sy, sz};
    auto it = sections_.find(sk);
    std::shared_ptr<Section> s;
    uint8_t flags = SectionStore::EDITED;
    if (auto m = rawMeta_.find(sk); m != rawMeta_.end()) flags |= m->second.flags;
    PackedIds held = it == sections_.end() ? store_->get(sk) : nullptr;
    if (it != sections_.end()) {
        s = std::make_shared<Section>(*it->second);  // copy on write: builders may hold the old one
    } else if (held) {
        s = std::make_shared<Section>();
        unpackSection(held->data(), s->mats.data());  // (the warm or cold tier's: one section, the host's mirror)
    } else {
        // materialise the section from the LOD 0 tile so the edit sits in consistent surroundings
        s = std::make_shared<Section>();
        const Tile* t = tile(0, floorDiv(x, 256), floorDiv(z, 256));
        for (int ly = 0; ly < 16; ly++)
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    uint16_t m = 0;
                    if (t) {
                        int wx = sx * 16 + lx, wz = sz * 16 + lz;
                        size_t c = size_t(wz - t->tz * 256) * TILE + size_t(wx - t->tx * 256);
                        m = TileVoxels::columnVoxel(*t, c, float(sy * 16 + ly) + 0.5f, 1.0f, stoneMat, waterMat);
                    }
                    s->mats[size_t(lx + 16 * (lz + 16 * ly))] = m;
                }
    }
    s->mats[size_t((x & 15) + 16 * ((z & 15) + 16 * (y & 15)))] = mat;
    if (!gridOwnsReal()) s->buildMips(classes_);
    sections_[sk] = std::move(s);
    // (edited: kept whatever its source, packed again, its old packed copy stale)
    RawMeta& rm = rawMeta_[sk];
    rm.gen = ++gen_;
    rm.flags = flags;
    store_->erase(sk);
    if (packOnGpu.load(std::memory_order_relaxed)) packQueue_.push_back({sk, rm.gen});
    if (gridCoarse.load(std::memory_order_relaxed) < coarseTop()) undigested_.insert(sk);
    markSectionDirty(sx, sy, sz);
}

// ---- the coarse levels of real content (CoarseCube) ------------------------------------------------------------------

template <class F> void World::forCoarse(const Key3& k, F&& f) const {
    // (a level the grid's coarse levels own is built from the tiles alone; nor are the cubes they own kept)
    int owned = gridCoarse.load(std::memory_order_relaxed);
    if (coarse_.empty() || k.lod <= owned) return;
    int s = 1 << k.lod, span = 256 << k.lod;
    // the region's columns and its ring (one voxel), every row (a column is evaluated over all its content)
    int x0 = floorDiv(k.x * span - s, COARSE_CUBE), x1 = floorDiv(k.x * span + span + s - 1, COARSE_CUBE);
    int z0 = floorDiv(k.z * span - s, COARSE_CUBE), z1 = floorDiv(k.z * span + span + s - 1, COARSE_CUBE);
    int rows = (WORLD_TOP_Y - WORLD_MIN_Y + COARSE_CUBE - 1) / COARSE_CUBE;
    int first = std::max({k.lod, COARSE_MIN, owned + 1}), top = coarseTop();
    if (first > top) return;
    size_t keys = size_t(x1 - x0 + 1) * size_t(z1 - z0 + 1) * size_t(rows) * size_t(top - first + 1);
    if (keys > coarse_.size()) {
        for (auto& [ck, c] : coarse_)
            if (ck.lod >= first && ck.lod <= top && ck.x >= x0 && ck.x <= x1 && ck.z >= z0 && ck.z <= z1) f(ck, c);
        return;
    }
    for (int l = first; l <= top; l++)
        for (int cz = z0; cz <= z1; cz++)
            for (int cx = x0; cx <= x1; cx++)
                for (int cy = 0; cy < rows; cy++) {
                    auto it = coarse_.find({l, cx, cy, cz});
                    if (it != coarse_.end()) f(it->first, it->second);
                }
}

bool World::coarseNear(const Key3& k) const {
    bool any = false;
    forCoarse(k, [&](const Key3&, const std::shared_ptr<const CoarseCube>&) { any = true; });
    return any;
}

// Takes the sections that arrived (undigested_) into the coarse levels and keeps them under the budget. Per section,
// in parallel: its voxels at levels 2..4 exactly as buildGroups makes them from it (Coverage::voxel), its level-5 and
// level-6 micro-voxels (8 and 1 of them: 8 and 16 blocks), its level-4 material (the level-5 voxel's child there).
// Then, under the lock, every touched cube is copied once and the changes merged in: a level-2..4 voxel replaced
// whole, a level-5 voxel's bits and child slot, then the level-6 voxels' from the level-5 voxels that changed.
void World::digest(const int centreRegion[MAX_LODS][2], double budgetMs) {
    auto t0 = std::chrono::steady_clock::now();
    std::vector<std::pair<Key3, std::shared_ptr<const Section>>> todo;
    std::shared_ptr<const ShapeSet> shapes;
    std::vector<uint8_t> cls;
    int top;
    // (the levels the grid's coarse levels own are not kept here: from the next one up)
    int first = std::max(COARSE_MIN, gridCoarse.load(std::memory_order_relaxed) + 1);
    {
        std::lock_guard<std::mutex> lock(mu_);
        top = coarseTop();
        if (top < first) {
            undigested_.clear();
            return;
        }
        if (shapes_->revision != digestShapes_) {  // new shapes: every section at hand again
            digestShapes_ = shapes_->revision;
            for (auto& [sk, sec] : sections_) undigested_.insert(sk);
        }
        for (const Key3& sk : undigested_) {
            auto it = sections_.find(sk);
            if (it != sections_.end()) todo.emplace_back(sk, it->second);
        }
        undigested_.clear();
        // within the update's budget (half of it, by the last digests' cost a section): the nearest first, the rest
        // the next updates'
        if (budgetMs > 0 && digestMsPerSection_ > 0) {
            size_t most = size_t(std::max(64.0, 0.5 * budgetMs / digestMsPerSection_));
            if (todo.size() > most) {
                auto d = [&](const Key3& k) {
                    return std::max(std::abs(floorDiv(k.x * 16, 256) - centreRegion[0][0]), std::abs(floorDiv(k.z * 16, 256) - centreRegion[0][1]));
                };
                std::nth_element(todo.begin(), todo.begin() + long(most), todo.end(),
                                 [&](const auto& a, const auto& b) { return d(a.first) < d(b.first); });
                for (size_t i = most; i < todo.size(); i++) undigested_.insert(todo[i].first);
                todo.resize(most);
            }
        }
        shapes = shapes_;
        cls = classes_;
    }
    lastDigested_ = uint32_t(todo.size());
    struct Vox {
        int level, x, y, z;  // its first block
        uint16_t mat;
        uint64_t mask;
        uint32_t box;
        SubVoxels fm;
    };
    struct D {
        std::vector<Vox> vox;
        uint16_t m4 = 0;
        uint64_t f5[8] = {};  // its level-5 micro-voxels' sub-voxels, cx + 2 cz + 4 cy
        uint64_t f6 = 0;      // its level-6 micro-voxel's
        bool any = false;   // anything in it: its content's box (section blocks [lo, hi))
        int lo[3] = {}, hi[3] = {};
    };
    std::vector<D> ds(todo.size());
    if (!todo.empty()) {
        std::atomic<size_t> next{0};
        parallelRun([&] {
            Coverage cov(*shapes);
            for (size_t i; (i = next.fetch_add(1)) < todo.size();) {
                const Key3& sk = todo[i].first;
                const Section& sec = *todo[i].second;
                D& d = ds[i];
                int bx = sk.x * 16, by = sk.y * 16, bz = sk.z * 16;
                if (first > 4) d.m4 = sec.at(4, 0, 0, 0);  // (level 5's child: the section's generated level)
                for (int level = first; level <= std::min(4, top); level++) {
                    int n = 16 >> level;
                    for (int y = 0; y < n; y++)
                        for (int z = 0; z < n; z++)
                            for (int x = 0; x < n; x++) {
                                int wx = bx + (x << level), wy = by + (y << level), wz = bz + (z << level);
                                uint64_t mask;
                                uint32_t box;
                                SubVoxels fm;
                                uint16_t mat = cov.voxel(sec, level, wx, wy, wz, sec.at(level, x, y, z), mask, box, fm);
                                d.vox.push_back({level, wx, wy, wz, mat, mask, box, fm});
                                if (level == 4) d.m4 = mat;
                            }
                }
                d.any = Coverage::contentBox(sec, 0, 0, 0, 16, d.lo, d.hi);
                if (top >= 5 && first <= 5 && d.any) {
                    for (int c = 0; c < 8; c++) {
                        int cx = c & 1, cz = (c >> 1) & 1, cy = c >> 2;
                        int q0[3] = {cx * 32, cy * 32, cz * 32}, w0[3] = {bx * 4 + q0[0], by * 4 + q0[1], bz * 4 + q0[2]};
                        d.f5[c] = cov.micro(sec, 5, q0, w0, d.lo, d.hi);
                    }
                }
                if (top >= 6 && d.any) {
                    int q0[3] = {0, 0, 0}, w0[3] = {bx * 4, by * 4, bz * 4};
                    d.f6 = cov.micro(sec, 6, q0, w0, d.lo, d.hi);
                }
            }
        });
    }
    if (std::getenv("ATLAS_COARSE_DEBUG"))
        std::fprintf(stderr, "[atlas] coarse digest: %zu sections computed in %.0f ms\n", todo.size(),
                     std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    std::lock_guard<std::mutex> lock(mu_);
    // copies of the touched cubes (copy on write: jobs hold the old ones)
    std::unordered_map<Key3, std::shared_ptr<CoarseCube>, Key3Hash> work;
    auto edit = [&](const Key3& key) -> CoarseCube& {
        auto w = work.find(key);
        if (w != work.end()) return *w->second;
        auto it = coarse_.find(key);
        auto c = it == coarse_.end() ? std::make_shared<CoarseCube>() : std::make_shared<CoarseCube>(*it->second);
        work.emplace(key, c);
        return *c;
    };
    // a voxel's new state per cube and index (levels 2..4: replaced whole; 5, 6: from the old one, edited)
    struct Next {
        uint16_t mat = 0;
        uint64_t mask = 0;
        uint32_t box = BOX_FULL;
        SubVoxels fm;
        uint16_t kids[8] = {};
        uint32_t kidBoxes[8] = {BOX_NONE, BOX_NONE, BOX_NONE, BOX_NONE, BOX_NONE, BOX_NONE, BOX_NONE, BOX_NONE};
    };
    // children's boxes (16ths of the voxel) to the voxel's: their union
    auto unionOf = [](const uint32_t kb[8]) {
        int lo[3] = {16, 16, 16}, hi[3] = {0, 0, 0};
        bool any = false;
        for (int k = 0; k < 8; k++) {
            if (kb[k] == BOX_NONE) continue;
            int l[3], h[3];
            unpackBox(kb[k], l, h);
            for (int a = 0; a < 3; a++) lo[a] = std::min(lo[a], l[a]), hi[a] = std::max(hi[a], h[a]);
            any = true;
        }
        return any ? packBox(lo, hi) : BOX_FULL;
    };
    // a child's box in its parent's 16ths: the child's [lo, hi) in units of `unit` from the parent's first one, at
    // `off` units, the parent's 16th being `step` of them (rounded outwards)
    auto scaled = [](const int lo[3], const int hi[3], const int off[3], int step) {
        int l[3], h[3];
        for (int a = 0; a < 3; a++) {
            l[a] = std::clamp(floorDiv(off[a] + lo[a], step), 0, 15);
            h[a] = std::clamp(-floorDiv(-(off[a] + hi[a]), step), l[a] + 1, 16);
        }
        return packBox(l, h);
    };
    std::unordered_map<Key3, std::map<uint32_t, Next>, Key3Hash> next;
    auto touch = [&](int level, int x, int y, int z) -> Next& {
        Key3 key = cubeKey(level, x, y, z);
        uint32_t idx = cubeIndex(level, x, y, z);
        auto& m = next[key];
        auto it = m.find(idx);
        if (it != m.end()) return it->second;
        Next n;
        CoarseCube& c = edit(key);
        if (const CoarseCube::Entry* e = c.find(idx)) {
            n.mat = e->mat, n.mask = e->mask, n.box = e->box;
            c.facesOf(*e, n.fm);
            if (level >= 5) {
                std::copy_n(&c.kids[size_t(e - c.entries.data()) * 8], 8, n.kids);
                std::copy_n(&c.kidBoxes[size_t(e - c.entries.data()) * 8], 8, n.kidBoxes);
            }
        }
        return m.emplace(idx, n).first->second;
    };
    std::vector<std::array<int, 3>> changed5;  // level-5 voxels (first block) whose material may have changed
    for (size_t i = 0; i < todo.size(); i++) {
        const Key3& sk = todo[i].first;
        const D& d = ds[i];
        int lx, ly, lz;
        sectionInCube(sk.x, sk.y, sk.z, lx, ly, lz);
        int bit = lx + 16 * (lz + 16 * ly);
        for (int level = first; level <= top; level++) edit(sectionCube(level, sk.x, sk.y, sk.z)).seen[bit >> 6] |= 1ull << (bit & 63);
        // the trees of the levels that read it only from here (5 and up)
        if (top >= 5) markRegions(sk.x, sk.y, sk.z, std::max(5, first), lodCount - 1, treeDirty_, true);
        for (const Vox& v : d.vox) {
            Next& n = touch(v.level, v.x, v.y, v.z);
            n.mat = v.mat, n.mask = v.mat ? v.mask : 0, n.box = v.box, n.fm = v.fm;
        }
        int bx = sk.x * 16, by = sk.y * 16, bz = sk.z * 16;
        if (top >= 5 && first <= 5) {
            Next& n = touch(5, bx, by, bz);
            int ox = sk.x & 1, oy = (sk.y - WORLD_MIN_Y / 16) & 1, oz = sk.z & 1;  // the section's octant
            for (int c = 0; c < 8; c++) {
                int b = (ox * 2 + (c & 1)) + 4 * ((oz * 2 + ((c >> 1) & 1)) + 4 * (oy * 2 + (c >> 2)));
                Coverage::setMicro(n.mask, n.fm, b, d.f5[c]);
            }
            n.kids[ox + 2 * oz + 4 * oy] = d.m4;
            // its content's box in the level-5 voxel's 16ths (2 blocks)
            int off[3] = {ox * 16, oy * 16, oz * 16};
            n.kidBoxes[ox + 2 * oz + 4 * oy] = d.any ? scaled(d.lo, d.hi, off, 2) : BOX_NONE;
            changed5.push_back({floorDiv(bx, 32) * 32, WORLD_MIN_Y + floorDiv(by - WORLD_MIN_Y, 32) * 32, floorDiv(bz, 32) * 32});
        }
        if (top >= 6) {
            Next& n = touch(6, bx, by, bz);
            int b = (sk.x & 3) + 4 * ((sk.z & 3) + 4 * ((sk.y - WORLD_MIN_Y / 16) & 3));
            Coverage::setMicro(n.mask, n.fm, b, d.f6);
        }
    }
    // level 5's materials from their children, then level 6's children from them
    std::sort(changed5.begin(), changed5.end());
    changed5.erase(std::unique(changed5.begin(), changed5.end()), changed5.end());
    for (auto& v : changed5) {
        if (first > 5) break;  // (level 6's children are level 5's: not kept)
        Next& n5 = touch(5, v[0], v[1], v[2]);
        n5.mat = voteKids(n5.kids, cls);
        n5.box = unionOf(n5.kidBoxes);
        bool any5 = false;
        for (uint32_t kb : n5.kidBoxes) any5 |= kb != BOX_NONE;
        if (top >= 6) {
            Next& n6 = touch(6, v[0], v[1], v[2]);
            int ox = (v[0] >> 5) & 1, oy = ((v[1] - WORLD_MIN_Y) >> 5) & 1, oz = (v[2] >> 5) & 1;
            n6.kids[ox + 2 * oz + 4 * oy] = n5.mat;
            // the level-5 voxel's box (its 16ths: 2 blocks) in the level-6 voxel's (4 blocks)
            int l5[3], h5[3];
            unpackBox(n5.box, l5, h5);
            int off[3] = {ox * 16, oy * 16, oz * 16};
            n6.kidBoxes[ox + 2 * oz + 4 * oy] = any5 ? scaled(l5, h5, off, 2) : BOX_NONE;
            n6.mat = voteKids(n6.kids, cls);
            n6.box = unionOf(n6.kidBoxes);
        }
    }
    if (std::getenv("ATLAS_COARSE_DEBUG"))  // (measurements) the level-5 and 6 voxels this digest changed
        for (auto& [key, changes] : next)
            for (auto& [idx, n] : changes) {
                if (key.lod < 5) continue;
                int lo[3], hi[3];
                unpackBox(n.box, lo, hi);
                std::string q;
                for (int b = 0; b < 64; b++)
                    if ((n.mask >> b) & 1)
                        q += std::to_string(b) + ":" + std::to_string(((n.fm.partial >> b) & 1) ? std::popcount(n.fm.sub[size_t(b)]) : 64) + " ";
                std::fprintf(stderr, "[atlas] coarse L%d cube (%d,%d,%d) voxel %u mat %u box %d-%d %d-%d %d-%d micro %s\n", key.lod, key.x,
                             key.y, key.z, idx, n.mat, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2], q.c_str());
            }
    // merge each cube's changes into its sorted entries
    for (auto& [key, changes] : next) {
        CoarseCube& c = edit(key);
        bool big = key.lod >= 5;
        std::vector<CoarseCube::Entry> es;
        std::vector<uint16_t> ks;
        std::vector<uint64_t> fs;
        std::vector<uint32_t> kb;
        es.reserve(c.entries.size() + changes.size());
        auto faces = [&](const SubVoxels& f) {  // appended: where they start
            uint32_t at = uint32_t(fs.size());
            for (int b = 0; b < 64; b++)
                if ((f.partial >> b) & 1) fs.push_back(f.sub[size_t(b)]);
            return at;
        };
        auto ch = changes.begin();
        auto put = [&](uint32_t idx, const Next& n) {
            bool kids = false;
            for (uint16_t q : n.kids) kids |= q != 0;
            if (!n.mat && !(big && kids)) return;  // nothing there (a level-5/6 voxel stays while it has children)
            es.push_back({idx, n.mat, 0, n.mask, n.box, faces(n.fm), n.fm.partial});
            if (big) ks.insert(ks.end(), n.kids, n.kids + 8), kb.insert(kb.end(), n.kidBoxes, n.kidBoxes + 8);
        };
        for (size_t i = 0; i <= c.entries.size(); i++) {
            uint32_t idx = i < c.entries.size() ? c.entries[i].idx : UINT32_MAX;
            for (; ch != changes.end() && ch->first < idx; ++ch) put(ch->first, ch->second);
            if (i == c.entries.size()) break;
            if (ch != changes.end() && ch->first == idx) {
                put(ch->first, ch->second);
                ++ch;
                continue;
            }
            es.push_back(c.entries[i]);
            {
                SubVoxels f;
                c.facesOf(c.entries[i], f);
                es.back().faceAt = faces(f);
            }
            if (big) {
                ks.insert(ks.end(), c.kids.begin() + long(i * 8), c.kids.begin() + long(i * 8 + 8));
                kb.insert(kb.end(), c.kidBoxes.begin() + long(i * 8), c.kidBoxes.begin() + long(i * 8 + 8));
            }
        }
        c.entries.swap(es);
        c.kids.swap(ks);
        c.kidBoxes.swap(kb);
        c.faces.swap(fs);
    }
    for (auto& [key, c] : work) {
        auto it = coarse_.find(key);
        if (it != coarse_.end()) coarseBytes_ -= it->second->bytes();
        coarseBytes_ += c->bytes();
        coarse_[key] = std::move(c);
    }
    // the budget: the cubes farthest from the camera for their level's size go first, never one within its level's
    // window + 1 region (what the level draws now); levels the clipmap no longer has go at once
    lastEvicted_ = 0;
    std::vector<std::pair<float, Key3>> far;
    for (auto& [key, c] : coarse_) {
        if (key.lod > top) {
            far.push_back({1e30f, key});
            continue;
        }
        if (coarseBytes_ <= coarseBudget) continue;
        int span = 256 << key.lod;
        int rx = floorDiv(key.x * COARSE_CUBE, span), rz = floorDiv(key.z * COARSE_CUBE, span);
        int dx = std::abs(rx - centreRegion[key.lod][0]), dz = std::abs(rz - centreRegion[key.lod][1]);
        if (std::max(dx, dz) <= windowRadius + 1) continue;
        // its centre's distance from the camera's region centre, in this level's regions
        float cx = (float(key.x) + 0.5f) * COARSE_CUBE / float(span) - (float(centreRegion[key.lod][0]) + 0.5f);
        float cz = (float(key.z) + 0.5f) * COARSE_CUBE / float(span) - (float(centreRegion[key.lod][1]) + 0.5f);
        far.push_back({std::max(std::fabs(cx), std::fabs(cz)), key});
    }
    std::sort(far.begin(), far.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    for (auto& [score, key] : far) {
        if (score < 1e29f && coarseBytes_ <= coarseBudget - coarseBudget / 8) break;  // (an eighth of slack: no churn)
        auto it = coarse_.find(key);
        coarseBytes_ -= it->second->bytes();
        coarse_.erase(it);
        lastEvicted_++;
    }
    lastDigestMs_ = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (!todo.empty()) digestMsPerSection_ = lastDigestMs_ / double(todo.size());
}

World::CoarseStats World::coarseStats() const {
    std::lock_guard<std::mutex> lock(mu_);
    CoarseStats st;
    st.bytes = coarseBytes_;
    for (auto& [key, c] : coarse_) {
        if (key.lod < MAX_LODS) st.cubes[key.lod]++, st.levelBytes[key.lod] += c->bytes();
        st.entries += uint32_t(c->entries.size());
    }
    st.digested = lastDigested_;
    st.evicted = lastEvicted_;
    st.digestMs = lastDigestMs_;
    return st;
}

World::Job World::snapshot(const Key3& k, bool groups) const {
    Job j;
    j.key = k;
    j.groups = groups;
    auto it = tiles_.find({k.lod, k.x, 0, k.z});
    if (it != tiles_.end()) j.tile = it->second;
    if (groups) {
        const int d[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            auto n = tiles_.find({k.lod, k.x + d[i][0], 0, k.z + d[i][1]});
            if (n != tiles_.end()) j.nbr[i] = n->second;
        }
    }
    // (a level the grid's coarse levels own: its regions from the tiles alone, the real blocks the grid's)
    bool real = k.lod == 0 ? !gridOwnsReal() : k.lod > gridCoarse.load(std::memory_order_relaxed);
    if (groups && real && k.lod <= 4 && !sections_.empty()) {
        // the groups read whole section columns around the region (its border ring for exposure, every row: a column
        // is evaluated over all its content)
        int per = 16 << k.lod;
        int sx0 = k.x * per - 1, sx1 = k.x * per + per, sz0 = k.z * per - 1, sz1 = k.z * per + per;
        int sy0 = floorDiv(WORLD_MIN_Y, 16), sy1 = floorDiv(WORLD_TOP_Y - 1, 16);
        size_t keys = size_t(sx1 - sx0 + 1) * size_t(sz1 - sz0 + 1) * size_t(sy1 - sy0 + 1);
        if (keys < sections_.size()) {
            for (int sz = sz0; sz <= sz1; sz++)
                for (int sx = sx0; sx <= sx1; sx++)
                    for (int sy = sy0; sy <= sy1; sy++) {
                        auto f = sections_.find({0, sx, sy, sz});
                        if (f != sections_.end()) j.sections.emplace(f->first, f->second);
                    }
        } else {
            for (auto& [sk, sec] : sections_)
                if (sk.x >= sx0 && sk.x <= sx1 && sk.z >= sz0 && sk.z <= sz1) j.sections.emplace(sk, sec);
        }
    }
    if (groups) forCoarse(k, [&](const Key3& ck, const std::shared_ptr<const CoarseCube>& c) { j.coarse.emplace(ck, c); });
    if (!groups && real && k.lod <= 4 && !sections_.empty()) {
        int per = 16 << k.lod;  // sections per region side at this level
        int sx0 = k.x * per, sy0 = floorDiv(WORLD_MIN_Y + k.y * 256 * (1 << k.lod), 16), sz0 = k.z * per;
        if (k.lod > 0 || sections_.size() < 4096) {
            for (auto& [sk, sec] : sections_)
                if (sk.x >= sx0 && sk.x < sx0 + per && sk.y >= sy0 && sk.y < sy0 + per && sk.z >= sz0 && sk.z < sz0 + per)
                    j.sections.emplace(sk, sec);
        } else {
            for (int sy = sy0; sy < sy0 + 16; sy++)
                for (int sz = sz0; sz < sz0 + 16; sz++)
                    for (int sx = sx0; sx < sx0 + 16; sx++) {
                        auto f = sections_.find({0, sx, sy, sz});
                        if (f != sections_.end()) j.sections.emplace(f->first, f->second);
                    }
        }
    }
    return j;
}

Region World::buildRegion(const Job& j) const {
    TileVoxels src(j.tile.get(), j.key, stoneMat, waterMat, &j.sections);
    Region r;
    r.key = j.key;
    r.dag = std::make_shared<const Svdag>(buildSvdag(src));
    return r;
}

namespace {

// What the coarse levels (CoarseCube) hold for one region job: a voxel's real content from the finest level >= max(its
// level, COARSE_MIN) that saw its sections, resampled when that level is coarser (a level whose cubes were dropped
// for memory borrows the next one's micro-voxels: rougher, never gone).
class CoarseView {
public:
    CoarseView(const World::CubeMap& cubes, int lod) : cubes_(cubes), lod_(lod), first_(std::max(lod, COARSE_MIN)) {
        for (auto& [k, c] : cubes) top_ = std::max(top_, k.lod);
    }
    bool empty() const { return cubes_.empty(); }

    // the section rows (sy0..sy1) the coarse levels saw in chunk column (sx, sz)
    bool seenRows(int sx, int sz, int& sy0, int& sy1) {
        sy0 = INT_MAX, sy1 = INT_MIN;
        int lx = sx - floorDiv(sx, 16) * 16, lz = sz - floorDiv(sz, 16) * 16;
        for (int l = first_; l <= top_; l++)
            for (int cy = 0; cy * COARSE_CUBE < WORLD_TOP_Y - WORLD_MIN_Y; cy++) {
                auto it = cubes_.find({l, floorDiv(sx, 16), cy, floorDiv(sz, 16)});
                if (it == cubes_.end()) continue;
                for (int ly = 0; ly < 16; ly++)
                    if (it->second->sawSection(lx, ly, lz)) {
                        int sy = cy * 16 + ly + WORLD_MIN_Y / 16;
                        sy0 = std::min(sy0, sy), sy1 = std::max(sy1, sy);
                    }
            }
        return sy1 >= sy0;
    }

    // The level-lod voxel whose first block is world (wx, wy, wz): which of its micro-voxels the coarse levels know
    // (0: none, the tile's), and there its material and micro-voxels.
    uint64_t voxel(int wx, int wy, int wz, uint16_t& mat, uint64_t& mask, uint32_t& box, SubVoxels& fm) {
        mat = 0;
        mask = 0;
        box = BOX_FULL;
        fm.partial = 0;
        if (lod_ <= 4) {  // in one section
            int level;
            const CoarseCube* c = source(floorDiv(wx, 16), floorDiv(wy, 16), floorDiv(wz, 16), level);
            if (!c) return 0;
            const CoarseCube::Entry* e = c->find(cubeIndex(level, wx, wy, wz));
            if (!e || !e->mask) return ~0ull;  // seen: air (or too sparse to show)
            box = level == lod_ ? e->box : rebox(e->box, level, wx, wy, wz);
            if (box == BOX_NONE) return ~0ull;  // its content lies elsewhere in the coarser voxel
            mat = e->mat;
            if (level == lod_) {
                mask = e->mask;
                c->facesOf(*e, fm);
            } else {
                SubVoxels cf;
                c->facesOf(*e, cf);
                mask = resample(e->mask, cf, level, wx, wy, wz, fm, ~0ull);
            }
            return ~0ull;
        }
        // levels 5, 6: several sections, each holding whole micro-voxels of this one
        int per = 1 << (lod_ - 4), mps = 64 >> lod_;  // sections a side; micro-voxels a side of a section
        uint64_t known = 0;
        bool own = false;
        uint32_t ubox = BOX_NONE;
        for (int i = 0; i < per; i++)
            for (int k = 0; k < per; k++)
                for (int j = 0; j < per; j++) {
                    int sx = floorDiv(wx, 16) + i, sy = floorDiv(wy, 16) + k, sz = floorDiv(wz, 16) + j;
                    int level;
                    const CoarseCube* c = source(sx, sy, sz, level);
                    if (!c) continue;
                    const CoarseCube::Entry* e = c->find(cubeIndex(level, sx * 16, sy * 16, sz * 16));
                    uint64_t mine = 0;
                    for (int c2 = 0; c2 < mps * mps * mps; c2++) {
                        int mx = i * mps + c2 % mps, mz = j * mps + (c2 / mps) % mps, my = k * mps + c2 / (mps * mps);
                        mine |= 1ull << (mx + 4 * (mz + 4 * my));
                    }
                    known |= mine;
                    if (!e || !e->mask) continue;
                    SubVoxels ef;
                    c->facesOf(*e, ef);
                    if (level == lod_) {
                        uint64_t got = e->mask & mine;
                        mask |= got;
                        fm.partial |= ef.partial & got;
                        for (int b = 0; b < 64; b++)
                            if ((got >> b) & 1) fm.sub[size_t(b)] = ef.sub[size_t(b)];
                    } else {
                        mask |= resample(e->mask, ef, level, wx, wy, wz, fm, mine);
                    }
                    ubox = unionBox(ubox, level == lod_ ? e->box : rebox(e->box, level, wx, wy, wz));
                    if (level == lod_ && !own) mat = e->mat, own = true;
                    else if (!own && !mat) mat = e->mat;
                }
        box = ubox == BOX_NONE ? BOX_FULL : ubox;
        return known;
    }

private:
    // the finest level from first_ that saw section (sx, sy, sz): its cube, or null
    const CoarseCube* source(int sx, int sy, int sz, int& level) {
        int lx, ly, lz;
        sectionInCube(sx, sy, sz, lx, ly, lz);
        for (int l = first_; l <= top_; l++) {
            Key3 k = sectionCube(l, sx, sy, sz);
            Cache& c = cache_[size_t(l)];
            if (!c.valid || !(c.key == k)) {
                auto it = cubes_.find(k);
                c = {k, it == cubes_.end() ? nullptr : it->second.get(), true};
            }
            if (c.cube && c.cube->sawSection(lx, ly, lz)) {
                level = l;
                return c.cube;
            }
        }
        return nullptr;
    }
    // a level-lod_ voxel's micro-voxels (first block world w) from the micro-voxels of the level-`level` voxel holding
    // it (each fine micro-voxel lies in one coarse one, and takes the part of its face masks over it), those of `only`
    uint64_t resample(uint64_t coarse, const SubVoxels& cf, int level, int wx, int wy, int wz, SubVoxels& fm, uint64_t only) const {
        int S = 1 << level;
        int ox = floorDiv(wx, S) * S, oy = WORLD_MIN_Y + floorDiv(wy - WORLD_MIN_Y, S) * S, oz = floorDiv(wz, S) * S;
        uint64_t out = 0;
        for (int b = 0; b < 64; b++) {
            if (!((only >> b) & 1)) continue;
            int mx = b & 3, my = b >> 4, mz = (b >> 2) & 3;
            // in quarter blocks: this micro-voxel's first corner, the coarse voxel's, the coarse micro-voxel's size
            int qx = (wx - ox) * 4 + mx * (1 << lod_), qy = (wy - oy) * 4 + my * (1 << lod_), qz = (wz - oz) * 4 + mz * (1 << lod_);
            int cb = (qx >> level) + 4 * ((qz >> level) + 4 * (qy >> level));
            if (!((coarse >> cb) & 1)) continue;
            out |= 1ull << b;
            fm.partial &= ~(1ull << b);
            if (!((cf.partial >> cb) & 1)) continue;  // solid there
            // each of its quarters from the coarse quarter under its centre (quarter blocks from the coarse micro-voxel)
            int q[3] = {qx, qy, qz}, cq[3] = {(qx >> level) << level, (qy >> level) << level, (qz >> level) << level};
            float Sf = float(1 << lod_) * 0.25f, Sc = float(1 << level) * 0.25f;
            uint64_t cs = cf.sub[size_t(cb)], f = 0;
            for (int i = 0; i < 64; i++) {
                int c3[3], e[3] = {i & 3, i >> 4, (i >> 2) & 3};
                for (int a = 0; a < 3; a++) c3[a] = std::clamp(int((float(q[a] - cq[a]) + (float(e[a]) + 0.5f) * Sf) / Sc), 0, 3);
                if ((cs >> (c3[0] + 4 * (c3[2] + 4 * c3[1]))) & 1) f |= 1ull << i;
            }
            fm.sub[size_t(b)] = f;
            if (f != ~0ull) fm.partial |= 1ull << b;
        }
        return out;
    }
    // a level-`level` voxel's box (its 16ths) as the 16ths of the level-lod_ voxel from block w inside it (BOX_NONE:
    // it holds none of that content), in 16ths of a block
    uint32_t rebox(uint32_t coarse, int level, int wx, int wy, int wz) const {
        if (coarse == BOX_NONE) return BOX_NONE;
        int S = 1 << level, s = 1 << lod_;
        int o[3] = {floorDiv(wx, S) * S, WORLD_MIN_Y + floorDiv(wy - WORLD_MIN_Y, S) * S, floorDiv(wz, S) * S};
        int w[3] = {wx, wy, wz}, clo[3], chi[3], lo[3], hi[3];
        unpackBox(coarse, clo, chi);
        for (int a = 0; a < 3; a++) {
            int l = (o[a] - w[a]) * 16 + clo[a] * S, h = (o[a] - w[a]) * 16 + chi[a] * S;  // 16ths of a block from w
            lo[a] = std::max(0, floorDiv(l, s)), hi[a] = std::min(16, -floorDiv(-h, s));
            if (hi[a] <= lo[a]) return BOX_NONE;
        }
        return packBox(lo, hi);
    }
    static uint32_t unionBox(uint32_t a, uint32_t b) {
        if (a == BOX_NONE) return b;
        if (b == BOX_NONE) return a;
        int al[3], ah[3], bl[3], bh[3];
        unpackBox(a, al, ah);
        unpackBox(b, bl, bh);
        for (int k = 0; k < 3; k++) al[k] = std::min(al[k], bl[k]), ah[k] = std::max(ah[k], bh[k]);
        return packBox(al, ah);
    }
    struct Cache {
        Key3 key{};
        const CoarseCube* cube = nullptr;
        bool valid = false;
    };
    const World::CubeMap& cubes_;
    int lod_, first_, top_ = -1;
    Cache cache_[MAX_LODS];
};

}  // namespace

// The voxels a region's groups are built from (GroupInput): every column of the region and its border ring, each
// evaluated over the rows where it may hold exposed content or decide a neighbour's exposure, exactly as TileVoxels
// evaluates them (real blocks where sections are, LOD 0 voxels marked VOXEL_SHAPED, the column tiles elsewhere), and
// where the sections are gone (or at levels 5 and 6, which never read them) the coarse levels of real content they
// left (CoarseCube): real data wins over the tile wherever a section was seen, micro-voxel by micro-voxel.
std::shared_ptr<const GroupTree> World::buildGroups(const Job& j, const ShapeSet& shapes) const {
    const Key3& k = j.key;
    int lod = k.lod, s = 1 << lod;
    float sf = float(s);
    GroupInput in;
    in.lod = lod;
    in.rowY0 = k.y * 256;
    in.microOrigin[0] = k.x * 256 * 4;
    in.microOrigin[1] = (WORLD_MIN_Y + k.y * 256) * 4;
    in.microOrigin[2] = k.z * 256 * 4;
    const int N = 258;
    in.lo.assign(size_t(N) * N, 0);
    in.hi.assign(size_t(N) * N, 0);
    in.start.assign(size_t(N) * N, 0);
    // sections: per chunk column the rows they span
    bool secs = lod <= 4 && !j.sections.empty();
    std::unordered_map<uint64_t, std::pair<int, int>> secRows;
    auto ckey = [](int cx, int cz) { return uint64_t(uint32_t(cx)) << 32 | uint32_t(cz); };
    if (secs)
        for (auto& [sk, sec] : j.sections) {
            if (lod > 0 && sec->mip[0].empty()) continue;
            auto [it, fresh] = secRows.try_emplace(ckey(sk.x, sk.z), sk.y, sk.y);
            if (!fresh) it->second.first = std::min(it->second.first, sk.y), it->second.second = std::max(it->second.second, sk.y);
        }
    // the coarse levels: per chunk column the section rows they saw
    CoarseView view(j.coarse, lod);
    bool coarse = !view.empty();
    std::unordered_map<uint64_t, std::pair<int, int>> seenRows;
    auto seen = [&](int sx, int sz, int& sy0, int& sy1) {
        auto [it, fresh] = seenRows.try_emplace(ckey(sx, sz), INT_MAX, INT_MIN);
        if (fresh) view.seenRows(sx, sz, it->second.first, it->second.second);
        sy0 = it->second.first, sy1 = it->second.second;
        return sy1 >= sy0;
    };
    auto tileOf = [&](int x, int z, size_t& c) -> const Tile* {
        c = size_t((z & 255) * 256 + (x & 255));
        if (x < 0) return j.nbr[0].get();
        if (x > 255) return j.nbr[1].get();
        if (z < 0) return j.nbr[2].get();
        if (z > 255) return j.nbr[3].get();
        return j.tile.get();
    };
    auto corner = [](int x, int z) { return (x < 0 || x > 255) && (z < 0 || z > 255); };  // no region voxel touches it
    auto rowOf = [&](float y) { return (y - float(WORLD_MIN_Y)) / sf; };
    // pass 1: per column the rows that may hold content: from just under its solid top to above its highest structure,
    // canopy or water, and all its sections (present, or seen by the coarse levels)
    std::vector<int32_t> clo(size_t(N) * N, 0), chi(size_t(N) * N, 0);
    for (int z = -1; z <= 256; z++)
        for (int x = -1; x <= 256; x++) {
            if (corner(x, z)) continue;
            size_t c;
            const Tile* t = tileOf(x, z, c);
            int lo = INT_MAX, hi = INT_MIN;
            if (t) {
                int vs = std::max(0, int(std::ceil(rowOf(t->surface[c]) - 0.5f)));
                lo = vs - 1;
                hi = std::max(vs, int(std::floor(rowOf(t->maxTop[0][c]))) + 2);
            }
            int wx = (k.x * 256 + x) * s, wz = (k.z * 256 + z) * s;
            if (secs) {
                auto it = secRows.find(ckey(floorDiv(wx, 16), floorDiv(wz, 16)));
                if (it != secRows.end()) {
                    lo = std::min(lo, floorDiv(it->second.first * 16 - WORLD_MIN_Y, s));
                    hi = std::max(hi, ((it->second.second + 1) * 16 - WORLD_MIN_Y + s - 1) / s);
                }
            }
            if (coarse) {
                int per = std::max(1, s / 16);  // chunk columns a side of this column
                for (int b = 0; b < per; b++)
                    for (int a = 0; a < per; a++) {
                        int sy0, sy1;
                        if (!seen(floorDiv(wx, 16) + a, floorDiv(wz, 16) + b, sy0, sy1)) continue;
                        lo = std::min(lo, floorDiv(sy0 * 16 - WORLD_MIN_Y, s));
                        hi = std::max(hi, ((sy1 + 1) * 16 - WORLD_MIN_Y + s - 1) / s);
                    }
            }
            if (lo == INT_MAX) lo = hi = 0;
            int ci = GroupInput::col(x, z);
            clo[size_t(ci)] = lo;
            chi[size_t(ci)] = hi;
        }
    // pass 2: a column is evaluated over its own content and a row either side, and over its in-region neighbours'
    // content rows (their exposure reads it); below that it is solid, above it air
    size_t total = 0;
    const int d4[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int z = -1; z <= 256; z++)
        for (int x = -1; x <= 256; x++) {
            if (corner(x, z)) continue;
            int ci = GroupInput::col(x, z);
            int lo = clo[size_t(ci)] - 1, hi = chi[size_t(ci)] + 1;
            for (auto& d : d4) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < 0 || nx > 255 || nz < 0 || nz > 255) continue;
                int ni = GroupInput::col(nx, nz);
                if (chi[size_t(ni)] <= clo[size_t(ni)]) continue;
                lo = std::min(lo, clo[size_t(ni)]);
                hi = std::max(hi, chi[size_t(ni)]);
            }
            in.lo[size_t(ci)] = lo;
            in.hi[size_t(ci)] = hi;
            in.start[size_t(ci)] = uint32_t(total);
            total += size_t(hi - lo);
        }
    in.vox.resize(total);
    Coverage cover(shapes);
    // a voxel with its own micro-voxels: its cell (or 0 / the plain material when they are none / all)
    auto masked = [&](uint16_t mat, uint64_t mask, uint32_t box, const SubVoxels& fm, uint32_t idx) -> uint32_t {
        if (!mat || !mask) return 0;
        bool partial = (fm.partial & mask) != 0;
        if (mask == SHAPE_FULL && box == BOX_FULL && !partial) return mat;
        in.masks[idx] = mask;
        if (box != BOX_FULL) in.boxes[idx] = box;
        if (partial) {
            SubVoxels& f = in.faces[idx];
            f = fm;
            f.partial &= mask;
        }
        return uint32_t(mat) | VOXEL_MASKED;
    };
    float micro = sf * 0.25f;
    // pass 3: the voxels
    for (int z = -1; z <= 256; z++)
        for (int x = -1; x <= 256; x++) {
            if (corner(x, z)) continue;
            int ci = GroupInput::col(x, z);
            size_t c;
            const Tile* t = tileOf(x, z, c);
            int wx = (k.x * 256 + x) * s, wz = (k.z * 256 + z) * s;
            int lastSy = INT_MIN;
            const Section* sec = nullptr;
            uint32_t* out = in.vox.data() + in.start[size_t(ci)];
            for (int row = in.lo[size_t(ci)]; row < in.hi[size_t(ci)]; row++) {
                int wy = WORLD_MIN_Y + row * s;
                uint32_t idx = uint32_t(out - in.vox.data()) + uint32_t(row - in.lo[size_t(ci)]);
                uint32_t v = 0;
                bool done = false;
                if (secs) {
                    int sy = floorDiv(wy, 16);
                    if (sy != lastSy) {
                        lastSy = sy;
                        auto it = j.sections.find({0, floorDiv(wx, 16), sy, floorDiv(wz, 16)});
                        sec = it != j.sections.end() && (lod == 0 || !it->second->mip[0].empty()) ? it->second.get() : nullptr;
                    }
                    if (sec) {
                        uint16_t m = sec->at(lod, (wx & 15) >> lod, (wy & 15) >> lod, (wz & 15) >> lod);
                        v = m && lod == 0 ? (uint32_t(m) | VOXEL_SHAPED) : m;
                        if (lod > 0) {
                            uint64_t mask;
                            uint32_t box;
                            SubVoxels fm;
                            uint16_t mat = cover.voxel(*sec, lod, wx, wy, wz, m, mask, box, fm);
                            v = masked(mat, mask, box, fm, idx);
                        }
                        done = true;
                    }
                }
                if (!done && coarse) {
                    uint16_t mat;
                    uint64_t mask;
                    uint32_t box;
                    SubVoxels fm;
                    uint64_t known = view.voxel(wx, wy, wz, mat, mask, box, fm);
                    if (known) {
                        if (known != ~0ull && t) {
                            uint64_t before = mask;
                            // a voxel only partly seen (levels 5 and 6, at the edge of what was sent): the tile's
                            // content in the rest, sampled at its micro-voxels' centres
                            for (int b = 0; b < 64; b++) {
                                if ((known >> b) & 1) continue;
                                float yc = float(wy) + (float(b >> 4) + 0.5f) * micro;
                                uint16_t tm = TileVoxels::columnVoxel(*t, c, yc, micro, stoneMat, waterMat);
                                if (!tm) continue;
                                mask |= 1ull << b;
                                fm.partial &= ~(1ull << b);
                                if (!mat) mat = tm;
                            }
                            if (mask != before) box = BOX_FULL;  // (the tile's part has no box of its own)
                        }
                        v = masked(mat, mask, box, fm, idx);
                        done = true;
                    }
                }
                if (!done && t) v = TileVoxels::columnVoxel(*t, c, float(wy) + 0.5f * sf, sf, stoneMat, waterMat);
                out[row - in.lo[size_t(ci)]] = v;
            }
        }
    std::string why;
    auto tree = std::make_shared<GroupTree>(buildGroupTree(in, shapes, groupLimits, &why));
    if (std::getenv("ATLAS_GROUP_DEBUG"))
        std::fprintf(stderr, "[atlas] groups (%d, %d, %d, %d): %zu sections, %zu voxels evaluated, %u content, %zu nodes, %zu bricks, %zu bytes, %.1f ms\n",
                     k.lod, k.x, k.y, k.z, j.sections.size(), in.vox.size(), tree->prims, tree->nodes.size() / 4, size_t(tree->brickCount), tree->bytes(), tree->buildMs);
    if (!why.empty()) {
        std::fprintf(stderr, "[atlas] groups of region (%d, %d, %d, %d) refused: %s\n", k.lod, k.x, k.y, k.z, why.c_str());
        tree->refused = true;
    }
    return tree;
}

int World::update(const double centre[3], double budgetMs, double* buildMs) {
    auto start = std::chrono::steady_clock::now();
    int centreRegion[MAX_LODS][2];
    std::vector<Key3> want;
    size_t urgent = 0;  // (budgeted) wanted regions short of the deferred trees: what the view still waits for
    std::vector<Job> jobs;
    bool groups = groupsWanted.load();
    std::shared_ptr<const ShapeSet> shapes;
    for (int lod = 0; lod < lodCount; lod++) {
        int span = 256 << lod;
        centreRegion[lod][0] = int(std::floor(centre[0] / span));
        centreRegion[lod][1] = int(std::floor(centre[2] / span));
    }
    if (gridMoved_.exchange(false)) {
        std::lock_guard<std::mutex> lock(mu_);
        gridWindowMoved();
    }
    // the sections that arrived, into the coarse levels (before the regions they dirtied are snapshot)
    auto tDigest0 = std::chrono::steady_clock::now();
    if (groups) digest(centreRegion, budgetMs);
    auto tDigest1 = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lock(mu_);
        // the coarser levels the sections dirtied, once the uploads have paused (or a region is missing outright)
        constexpr uint32_t SECTION_QUIET_MS = 2000;
        if (!sectionDirty_.empty() && int32_t(nowMs() - lastSectionMs_) >= int32_t(SECTION_QUIET_MS)) {
            dirty_.insert(sectionDirty_.begin(), sectionDirty_.end());
            sectionDirty_.clear();
        }
        for (int lod = 0; lod < lodCount; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(centre[0] / span)), cz = int(std::floor(centre[2] / span));
            centreRegion[lod][0] = cx;
            centreRegion[lod][1] = cz;
            for (int dz = -windowRadius; dz <= windowRadius; dz++)
                for (int dx = -windowRadius; dx <= windowRadius; dx++) {
                    if (!tile(lod, cx + dx, cz + dz)) continue;
                    for (int ry = 0; ry < regionRows(lod); ry++) {
                        Key3 k{lod, cx + dx, ry, cz + dz};
                        auto r = regions_.find(k);
                        // with groups wanted, a region without its tree yet gets one (its SVDAG kept)
                        if (inFlight_.count(k)) continue;  // (a builder has it)
                        if (r == regions_.end() || dirty_.count(k) || (groups && treeDirty_.count(k)) ||
                            (groups && lod < groupLods && !r->second->groups &&
                             (!r->second->empty() || (lod <= 4 && !sections_.empty()) || coarseNear(k))))
                            want.push_back(k);
                    }
                }
        }
        // the horizon around the eye from the tiles' height pyramids: per azimuth bin and ring (distances growing
        // geometrically), the steepest elevation (tangent) the terrain reaches up to that ring. A region whose top stays
        // below the horizon formed in front of it, over every bin it spans, is behind a mountain (or, deep underground,
        // behind the rock above): it is built after everything in view (ATLAS-STREAMING.md, "Hidden things")
        constexpr int BINS = 128, RINGS = 44;
        float ringD[RINGS];
        for (int i = 0; i < RINGS; i++) ringD[i] = 24.0f * std::pow(1.2f, float(i));
        std::vector<float> horizon(size_t(BINS) * RINGS, -1e9f);
        double ex = centre[0], ey = centre[1], ez = centre[2];
        // what blocks the view: the lowest ground across the cell (a peak on one ray hid the far side of a bin whose
        // edges were lower: 1 % of the view went missing until its regions came last)
        auto occluderAt = [&](double wx, double wz, double cell) -> float {
            int lod0 = std::clamp(int(std::floor(std::log2(std::max(1.0, cell)))), 0, lodCount - 1);
            for (int lod = lod0; lod < lodCount; lod++) {  // the finest tile there whose columns are no wider than the cell
                int span = 256 << lod;
                int tx = int(std::floor(wx / span)), tz = int(std::floor(wz / span));
                const Tile* t = tile(lod, tx, tz);
                if (!t || t->minSurf[0].empty()) continue;
                int cx = std::clamp(int((wx - double(tx) * span) / double(1 << lod)), 0, 255);
                int cz = std::clamp(int((wz - double(tz) * span) / double(1 << lod)), 0, 255);
                int lv = std::clamp(int(std::floor(std::log2(std::max(1.0, cell / double(1 << lod))))), 0, 8);
                int per = 256 >> lv;
                return t->minSurf[size_t(lv)][size_t(cz >> lv) * per + size_t(cx >> lv)];
            }
            return -1e9f;
        };
        if (!want.empty())
            for (int b = 0; b < BINS; b++) {
                double az = (b + 0.5) * 2 * 3.14159265358979 / BINS, dx = std::cos(az), dz = std::sin(az);
                float run = -1e9f;
                for (int i = 0; i < RINGS; i++) {
                    float top = occluderAt(ex + dx * ringD[i], ez + dz * ringD[i], ringD[i] * 2 * 3.14159265358979 / BINS);
                    if (top > -1e8f) run = std::max(run, float((top - ey) / ringD[i]));
                    horizon[size_t(b) * RINGS + i] = run;
                }
            }
        auto hidden = [&](const Key3& k) {
            double span = double(256 << k.lod), x0 = k.x * span, z0 = k.z * span;
            double nx = std::clamp(ex, x0, x0 + span), nz = std::clamp(ez, z0, z0 + span);
            double dNear = std::hypot(nx - ex, nz - ez);
            if (dNear < span * 1.5 || dNear < 48) return false;  // the eye's region and its neighbours: always in view
            // the region's top: its tile's highest point (a margin for real blocks above it), and its row's ceiling
            const Tile* t = tile(k.lod, k.x, k.z);
            double top = t && !t->maxTop[8].empty() ? t->maxTop[8][0] + 8.0 : 1e9;
            top = std::min(top, double(WORLD_MIN_Y + (k.y + 1) * (256 << k.lod)));
            double tanTop = (top - ey) / dNear;
            int ring = 0;  // the horizon formed in front of the region (its rings well short of it)
            while (ring + 1 < RINGS && ringD[ring + 1] < dNear * 0.8) ring++;
            double a0 = 1e9, a1 = -1e9;  // the azimuths the region spans (its corners)
            for (int c = 0; c < 4; c++) {
                double a = std::atan2(z0 + (c >> 1) * span - ez, x0 + (c & 1) * span - ex);
                if (a < 0) a += 2 * 3.14159265358979;
                a0 = std::min(a0, a), a1 = std::max(a1, a);
            }
            if (a1 - a0 > 3.14159265358979) return false;  // wraps around the eye's direction: not worth the care
            for (int b = int(a0 / (2 * 3.14159265358979) * BINS); b <= int(a1 / (2 * 3.14159265358979) * BINS); b++)
                if (tanTop >= horizon[size_t(std::clamp(b, 0, BINS - 1)) * RINGS + ring]) return false;  // seen over the horizon there
            return true;
        };
        // regions missing altogether first, coarsest first (one coarse region covers what dozens of fine ones do: an
        // arrival shows the whole view at once, then sharpens), then rebuilds, nearest and finest first; regions hidden
        // behind the horizon after all of those
        int hiddenCount = 0;
        // behind the camera (its forward direction from the frames, setView): after everything in view, before what
        // the horizon hides; the eye's region and its neighbours never count as behind
        float vfx = viewX_.load(), vfz = viewZ_.load();
        bool haveView = vfx * vfx + vfz * vfz > 1e-6f;
        auto behind = [&](const Key3& k) {
            if (!haveView) return false;
            double span = double(256 << k.lod), x0 = k.x * span, z0 = k.z * span;
            double nx = std::clamp(ex, x0, x0 + span), nz = std::clamp(ez, z0, z0 + span);
            if (std::hypot(nx - ex, nz - ez) < span * 1.5) return false;
            // every corner behind (the view's half-width at most ~60 degrees off forward: cos < -0.2 is out of it)
            for (int c = 0; c < 4; c++) {
                double dx = x0 + (c & 1) * span - ex, dz = z0 + (c >> 1) * span - ez, l = std::hypot(dx, dz);
                if (l < 1e-6 || (dx * vfx + dz * vfz) / l > -0.2) return false;
            }
            return true;
        };
        auto order = [&](const Key3& k) {
            int d = std::abs(k.x - centreRegion[k.lod][0]) + std::abs(k.z - centreRegion[k.lod][1]);
            bool missing = regions_.find(k) == regions_.end();
            int coarseFirst = (MAX_LODS - k.lod) * 64 + d;
            // a region with its voxels whose tree alone is wanted, inside the finer level's window: last of all (a
            // primary ray takes the finer level there; a level-1 tree of real content took 30-60 s on one thread)
            if (budgetMs > 0 && !missing && !dirty_.count(k) && coveredByFiner(k, centreRegion)) return std::make_pair(4, coarseFirst);
            if (hidden(k)) {
                hiddenCount++;
                return std::make_pair(3, coarseFirst);
            }
            if (behind(k)) return std::make_pair(2, missing ? coarseFirst : 1000 + d + k.lod);
            return missing ? std::make_pair(0, coarseFirst) : std::make_pair(1, d + k.lod);
        };
        // (budgeted) whether a wanted region's job builds its tree too: not for a region the finer level's window
        // covers (its voxels now, its tree from the last category later)
        std::vector<std::pair<std::pair<int, int>, Key3>> keyed;
        keyed.reserve(want.size());
        for (const Key3& k : want) keyed.push_back({order(k), k});
        std::stable_sort(keyed.begin(), keyed.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
        for (size_t i = 0; i < want.size(); i++) want[i] = keyed[i].second;
        urgent = size_t(std::count_if(keyed.begin(), keyed.end(), [](const auto& e) { return e.first.first < 4; }));
        lastHidden_ = hiddenCount;
        // ATLAS_SKIP_HIDDEN (a measurement): hidden regions are never built, so the settled picture shows what the
        // horizon test hid wrongly
        static const bool skipHidden = std::getenv("ATLAS_SKIP_HIDDEN") != nullptr;
        if (skipHidden)
            while (!keyed.empty() && keyed.back().first.first == 3) keyed.pop_back(), want.pop_back();
        jobs.resize(want.size());
        shapes = shapes_;
    }

    // each job's inputs are snapshot when a worker takes it, not all up front: with a budget, snapshotting all 250
    // wanted regions took longer than the budget itself and nothing was built (ATLAS-STREAMING.md); the budget counts
    // from here
    auto workStart = std::chrono::steady_clock::now();
    if (budgetMs > 0) {
        // the builders' queue: what is wanted now, in order (replacing the last update's); what they finished since
        // the last update, collected (as the synchronous path's results below)
        std::vector<Built> fin;
        {
            std::lock_guard<std::mutex> lock(mu_);
            queue_.clear();
            for (const Key3& k : want) queue_.push_back({k, !coveredByFiner(k, centreRegion) || (regions_.count(k) && !dirty_.count(k))});
            fin.swap(done_);
            if (builders_.empty()) {
                unsigned n = std::max(1u, std::thread::hardware_concurrency() / 2);
                if (threadCap()) n = std::min(n, threadCap());
                try {
                    for (unsigned i = 0; i < n; i++) builders_.emplace_back([this] { builder(); });
                } catch (...) {
                    // (out of threads: those that started do the work; with none, the synchronous path next time)
                }
            }
        }
        buildCv_.notify_all();
        want.clear();
        jobs.clear();
        for (Built& b : fin) {
            want.push_back(b.key);
            Job j;
            j.key = b.key;
            j.wasDirty = b.wasDirty;
            j.wasTreeDirty = b.wasTreeDirty;
            j.keep = b.keep;
            jobs.push_back(std::move(j));
        }
        budgetMs = -1.0;  // (below: nothing to build here, the collected regions taken as results)
        for (size_t i = 0; i < fin.size(); i++) doneRegions_.push_back(std::move(fin[i].region));
    }
    std::atomic<size_t> next{0};
    std::atomic<int> built{0}, treesBuilt{0};
    std::atomic<int64_t> treeUs{0};
    std::vector<std::pair<Key3, std::shared_ptr<const Region>>> results(want.size());
    // (ATLAS_TRACE_UPDATE) per job: snapshot (under the lock), voxels, tree, ms
    struct JobTime {
        Key3 k;
        double snap = 0, dag = 0, tree = 0;
        size_t sections = 0, coarse = 0;
    };
    std::vector<JobTime> jobTimes(want.size());
    if (budgetMs < 0) {  // (collected from the builders)
        for (size_t i = 0; i < want.size(); i++) {
            if (doneRegions_[i]) results[i] = {want[i], doneRegions_[i]}, built++;
        }
        doneRegions_.clear();
    }
    auto worker = [&] {
        for (;;) {
            if (budgetMs < 0) return;
            size_t i = next.fetch_add(1);
            if (i >= want.size()) return;
            if (budgetMs > 0) {
                double el = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - workStart).count();
                if (el > budgetMs) return;
            }
            auto js0 = std::chrono::steady_clock::now();
            {
                std::lock_guard<std::mutex> lock(mu_);
                const Key3& k = want[i];
                jobs[i] = snapshot(k, groups && k.lod < groupLods);
                auto r = regions_.find(k);
                jobs[i].wasDirty = dirty_.erase(k) != 0;  // an edit while it builds marks it again (built next time)
                jobs[i].wasTreeDirty = treeDirty_.erase(k) != 0;
                if (r != regions_.end() && !jobs[i].wasDirty) jobs[i].keep = r->second;  // only its tree is missing
            }
            const Job& j = jobs[i];
            auto js1 = std::chrono::steady_clock::now();
            Region r;
            if (j.keep) {
                r.key = j.key;
                r.dag = j.keep->dag;
            } else {
                r = buildRegion(j);
            }
            auto js2 = std::chrono::steady_clock::now();
            if (j.groups && (!r.empty() || !j.sections.empty() || !j.coarse.empty())) {
                r.groups = buildGroups(j, *shapes);
                treesBuilt++;
                treeUs += int64_t(r.groups->buildMs * 1000.0);
            } else if (j.groups) {
                r.groups = std::make_shared<const GroupTree>();  // (nothing to draw: wanted no more; it was rebuilt every update)
            }
            auto js3 = std::chrono::steady_clock::now();
            auto msOf = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
            jobTimes[i] = {j.key, msOf(js0, js1), msOf(js1, js2), msOf(js2, js3), j.sections.size(), j.coarse.size()};
            results[i] = {want[i], std::make_shared<const Region>(std::move(r))};
            built++;
        }
    };
    if (budgetMs >= 0) parallelRun(worker);  // a worker's exception (bad_alloc) comes back here, after all have stopped

    bool moved = !havePacked_;
    auto tLock0 = std::chrono::steady_clock::now();
    std::chrono::steady_clock::time_point tLock1;
    {
        std::lock_guard<std::mutex> lock(mu_);
        tLock1 = std::chrono::steady_clock::now();
        lastGroupsBuilt_ = uint32_t(treesBuilt.load());
        lastGroupsMs_ = double(treeUs.load()) / 1000.0;
        uint32_t now = nowMs();
        for (size_t i = 0; i < results.size(); i++) {
            auto& [k, r] = results[i];
            if (!r) {
                if (jobs[i].wasDirty) dirty_.insert(want[i]);  // not built within the budget: still to do
                if (jobs[i].wasTreeDirty) treeDirty_.insert(want[i]);
                continue;
            }
            auto old = regions_.find(k);
            if (jobs[i].keep && (old == regions_.end() || old->second->dag != r->dag)) continue;  // replaced meanwhile
            if (fadeMs > 0 && !r->empty() && !jobs[i].keep) {
                // dissolve from what was there (or, for a region new to the window, from the coarser LOD that
                // covered it); a fade already running keeps its start and its old region, so a tile refined twice
                // in quick succession (stand-in, exact, then its buildings) dissolves once
                auto f = fading_.find(k);
                if (f == fading_.end() || int32_t(now - f->second.startMs) >= int32_t(fadeMs)) {
                    std::shared_ptr<const Region> prev = old != regions_.end() ? old->second : nullptr;
                    bool wasEmpty = prev && prev->empty();
                    if (wasEmpty) prev = nullptr;
                    fading_[k] = Fade{prev, now, wasEmpty};
                }
            }
            regions_[k] = r;
        }
        for (auto it = fading_.begin(); it != fading_.end();) {
            int dx = std::abs(it->first.x - centreRegion[it->first.lod][0]), dz = std::abs(it->first.z - centreRegion[it->first.lod][1]);
            if (int32_t(now - it->second.startMs) >= int32_t(fadeMs) || dx > windowRadius + 1 || dz > windowRadius + 1) {
                it = fading_.erase(it);
                moved = true;  // repack without the old region
            } else {
                ++it;
            }
        }
        // evict regions far outside the window (keep one ring of slack against thrashing), and the tiles and
        // sections behind them two rings out, so memory stays bounded however far the player travels
        for (auto it = regions_.begin(); it != regions_.end();) {
            const Key3& k = it->first;
            int dx = std::abs(k.x - centreRegion[k.lod][0]), dz = std::abs(k.z - centreRegion[k.lod][1]);
            if (k.lod >= lodCount || dx > windowRadius + 1 || dz > windowRadius + 1) it = regions_.erase(it);
            else ++it;
        }
        for (auto it = treeDirty_.begin(); it != treeDirty_.end();) {  // (a region built anew there reads it all)
            const Key3& k = *it;
            int dx = std::abs(k.x - centreRegion[k.lod][0]), dz = std::abs(k.z - centreRegion[k.lod][1]);
            if (k.lod >= lodCount || dx > windowRadius + 1 || dz > windowRadius + 1) it = treeDirty_.erase(it);
            else ++it;
        }
        for (auto it = tiles_.begin(); it != tiles_.end();) {
            const Key3& k = it->first;
            int dx = std::abs(k.x - centreRegion[k.lod][0]), dz = std::abs(k.z - centreRegion[k.lod][1]);
            if (k.lod >= lodCount || dx > windowRadius + 2 || dz > windowRadius + 2) it = tiles_.erase(it);
            else ++it;
        }
        for (auto it = structures_.begin(); it != structures_.end();) {
            const Key3& k = it->first;
            int dx = std::abs(k.x - centreRegion[k.lod][0]), dz = std::abs(k.z - centreRegion[k.lod][1]);
            if (k.lod >= lodCount || dx > windowRadius + 2 || dz > windowRadius + 2) it = structures_.erase(it);
            else ++it;
        }
        // LOD-0 regions either side: one ring beyond the server's sections window (AtlasSections.RADIUS_TILES = 4, the
        // same 256-block grid), which forgets a tile once it leaves and sends it again on return; what the core drops
        // inside that window would never come back
        int keep = sectionKeep > 0 ? sectionKeep : windowRadius + 3;
        for (auto it = sections_.begin(); it != sections_.end();) {
            int rx = floorDiv(it->first.x * 16, 256), rz = floorDiv(it->first.z * 16, 256);
            if (std::abs(rx - centreRegion[0][0]) > keep || std::abs(rz - centreRegion[0][1]) > keep)
                undigested_.erase(it->first), rawMeta_.erase(it->first), it = sections_.erase(it);
            else ++it;
        }
        // the packed tiers: past the kept regions out of warm (to the cold tier, or dropped), and the warm one's budget
        store_->enforce(centreRegion[0][0] * 16 + 8, centreRegion[0][1] * 16 + 8, keep);
        for (int lod = 0; lod < lodCount; lod++)
            if (centreRegion[lod][0] != lastCentre_[lod][0] || centreRegion[lod][1] != lastCentre_[lod][1]) moved = true;
    }
    bool reshaped;
    {
        std::lock_guard<std::mutex> lock(mu_);
        reshaped = shapes_->revision != packedShapes_ || cards_->revision != packedCards_;
    }
    {
        std::lock_guard<std::mutex> lock(mu_);
        // (budgeted: what the view still waits for, the deferred trees left out; plus what the builders have in hand)
        lastPending_ = budgetMs < 0 ? urgent + inFlight_.size() : want.size() - size_t(built.load());
    }
    auto tBuilt = std::chrono::steady_clock::now();
    bool repacked = built > 0 || moved || reshaped;
    if (repacked && budgetMs < 0) {
        // (budgeted: packed by the packer thread; atlas_update stages the new revision once it is there)
        {
            std::lock_guard<std::mutex> lock(packMu_);
            std::memcpy(packCentre_, centreRegion, sizeof packCentre_);
            packWanted_ = true;
            if (!packerThread_.joinable()) {
                try {
                    packerThread_ = std::thread([this] { packer(); });
                } catch (...) {
                    packWanted_ = false;
                }
            }
        }
        packCv_.notify_one();
        if (!packerThread_.joinable()) repack(centreRegion);
    } else if (repacked) {
        repack(centreRegion);
    }
    auto tEnd = std::chrono::steady_clock::now();
    if (buildMs) *buildMs = std::chrono::duration<double, std::milli>(tEnd - start).count();
    static const bool trace = std::getenv("ATLAS_TRACE_UPDATE") != nullptr;  // streaming measurements (ATLAS-STREAMING.md)
    if (trace && (built > 0 || repacked)) {
        auto msOf = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
        std::fprintf(stderr, "[atlas] update: digest %.1f ms, choose %.1f ms, build %.1f ms (budget %.0f; waiting for the lock %.1f, "
                     "holding it %.1f), repack %.1f ms\n",
                     msOf(tDigest0, tDigest1), msOf(tDigest1, workStart), msOf(workStart, tBuilt), budgetMs, msOf(tLock0, tLock1),
                     msOf(tLock1, tBuilt), msOf(tBuilt, tEnd));
        std::vector<JobTime> jt;
        for (const JobTime& t : jobTimes)
            if (t.snap + t.dag + t.tree > 0) jt.push_back(t);
        std::sort(jt.begin(), jt.end(), [](const JobTime& a, const JobTime& b) { return a.snap + a.dag + a.tree > b.snap + b.dag + b.tree; });
        for (size_t i = 0; i < std::min<size_t>(jt.size(), 6); i++)
            std::fprintf(stderr, "[atlas]   job lod %d (%d,%d,%d): snapshot %.1f ms (%zu sections, %zu cubes), voxels %.1f ms, tree %.1f ms\n",
                         jt[i].k.lod, jt[i].k.x, jt[i].k.y, jt[i].k.z, jt[i].snap, jt[i].sections, jt[i].coarse, jt[i].dag, jt[i].tree);
        auto p = packed();
        std::fprintf(stderr, "[atlas] update: %d of %zu wanted (%d behind the horizon) built in %.1f ms, repack %.1f ms (%zu regions, %.1f MB)\n",
                     built.load(), want.size(), lastHidden_.load(), std::chrono::duration<double, std::milli>(tBuilt - start).count(),
                     std::chrono::duration<double, std::milli>(tEnd - tBuilt).count(), p->regions.size(),
                     double(p->nodes.size() * 4 + p->leaves.size() * 8 + p->groups.size() * 4 + p->heightMip.size() * 4) / 1e6);
        for (size_t i = 0; i < std::min<size_t>(want.size(), 4); i++)
            std::fprintf(stderr, "[atlas]   integrated lod %d (%d,%d,%d)%s\n", want[i].lod, want[i].x, want[i].y, want[i].z,
                         results[i].second && results[i].second->groups ? " with a tree" : "");
    }
    return built;
}

World::~World() {
    // (store_ first: its type is complete only here)
    store_.reset();
    {
        std::lock_guard<std::mutex> lock(mu_);
        stopBuilders_ = true;
        queue_.clear();
    }
    {
        std::lock_guard<std::mutex> lock(packMu_);  // (the packer waits on its own lock)
    }
    buildCv_.notify_all();
    packCv_.notify_all();
    for (auto& t : builders_)
        if (t.joinable()) t.join();
    if (packerThread_.joinable()) packerThread_.join();
}

bool World::coveredByFiner(const Key3& k, const int centreRegion[MAX_LODS][2]) const {
    if (k.lod < 1) return false;
    int64_t span = int64_t(256) << k.lod, fine = span / 2;
    int64_t x0 = k.x * span, z0 = k.z * span;
    int64_t fx0 = (centreRegion[k.lod - 1][0] - windowRadius) * fine, fx1 = (centreRegion[k.lod - 1][0] + windowRadius + 1) * fine;
    int64_t fz0 = (centreRegion[k.lod - 1][1] - windowRadius) * fine, fz1 = (centreRegion[k.lod - 1][1] + windowRadius + 1) * fine;
    return x0 >= fx0 && x0 + span <= fx1 && z0 >= fz0 && z0 + span <= fz1;
}

void World::builder() {
#ifdef _WIN32
    SetThreadPriority(GetCurrentThread(), -1);  // THREAD_PRIORITY_BELOW_NORMAL  // (the game's threads first)
#endif
    std::unique_lock<std::mutex> lock(mu_);
    for (;;) {
        buildCv_.wait(lock, [&] { return stopBuilders_ || !queue_.empty(); });
        if (stopBuilders_) return;
        auto [k, withTree] = queue_.front();
        queue_.pop_front();
        if (inFlight_.count(k)) continue;
        bool groups = groupsWanted.load() && k.lod < groupLods && withTree;
        Job j = snapshot(k, groups);
        auto r = regions_.find(k);
        j.wasDirty = dirty_.erase(k) != 0;  // an edit while it builds marks it again (built next time)
        j.wasTreeDirty = treeDirty_.erase(k) != 0;
        if (r != regions_.end() && !j.wasDirty) j.keep = r->second;  // only its tree is missing
        std::shared_ptr<const ShapeSet> shapes = shapes_;
        inFlight_.insert(k);
        lock.unlock();
        Built b;
        b.key = k;
        b.wasDirty = j.wasDirty;
        b.wasTreeDirty = j.wasTreeDirty;
        b.keep = j.keep;
        try {
            Region reg;
            if (j.keep) {
                reg.key = j.key;
                reg.dag = j.keep->dag;
            } else {
                reg = buildRegion(j);
            }
            if (j.groups && (!reg.empty() || !j.sections.empty() || !j.coarse.empty())) reg.groups = buildGroups(j, *shapes);
            else if (j.groups) reg.groups = std::make_shared<const GroupTree>();  // (nothing to draw: wanted no more)
            b.region = std::make_shared<const Region>(std::move(reg));
        } catch (...) {
            b.region = nullptr;  // (out of memory: left dirty, the next update queues it again)
        }
        j = Job{};  // (its snapshot let go before the lock)
        lock.lock();
        inFlight_.erase(k);
        done_.push_back(std::move(b));
    }
}

void World::setShapes(uint32_t first, const std::vector<std::shared_ptr<const MaterialShape>>& shapes) {
    std::lock_guard<std::mutex> lock(mu_);
    auto next = std::make_shared<ShapeSet>(*shapes_);  // copy on write: builders hold the old snapshot
    if (next->byMaterial.size() < first + shapes.size()) next->byMaterial.resize(first + shapes.size());
    for (size_t i = 0; i < shapes.size(); i++) next->byMaterial[first + i] = shapes[i];
    next->revision = shapes_->revision + 1;
    shapes_ = std::move(next);
}

void World::setTextures(uint32_t first, const std::vector<std::shared_ptr<const MaterialTexture>>& textures) {
    std::lock_guard<std::mutex> lock(texMu_);
    auto next = std::make_shared<TextureSet>(*textures_);  // copy on write, as the shapes
    if (next->byMaterial.size() < first + textures.size()) next->byMaterial.resize(first + textures.size());
    for (size_t i = 0; i < textures.size(); i++) next->byMaterial[first + i] = textures[i];
    next->revision = textures_->revision + 1;
    textures_ = std::move(next);
}

void World::setCards(std::shared_ptr<const CardSet> cards) {
    std::lock_guard<std::mutex> lock(mu_);
    auto next = std::make_shared<CardSet>(*cards);
    next->revision = cards_->revision + 1;
    cards_ = std::move(next);
}

World::GroupStats World::groupStats() const {
    std::lock_guard<std::mutex> lock(mu_);
    GroupStats st;
    for (auto& [k, r] : regions_) {
        if (!r->groups) continue;
        const GroupTree& t = *r->groups;
        if (t.refused) {
            st.refused++;
            continue;
        }
        if (t.empty()) continue;
        st.regions++;
        st.bytes += t.bytes();
        st.maxRegionBytes = std::max<uint64_t>(st.maxRegionBytes, t.bytes());
        st.maxDepth = std::max(st.maxDepth, uint32_t(t.depth));
        st.prims += t.prims;
        st.nodes += t.nodes.size() / 4;
        st.bricks += t.brickCount;
        st.buildMs += t.buildMs;
        st.maxBuildMs = std::max(st.maxBuildMs, t.buildMs);
    }
    st.built = lastGroupsBuilt_;
    st.lastUpdateMs = lastGroupsMs_;
    return st;
}

void World::repack(const int centreRegion[MAX_LODS][2]) {
    // what the scene is packed from, snapshot under the world's lock (the regions of every level's window, the fades,
    // shapes and cards: shared, not copied); the packing itself (tens of ms for a large scene) without it, so uploads
    // and builders never wait for it
    std::unordered_map<Key3, std::shared_ptr<const Region>, Key3Hash> regions;
    std::unordered_map<Key3, Fade, Key3Hash> fading;
    std::shared_ptr<const ShapeSet> shapesSnap;
    std::shared_ptr<const CardSet> cardsSnap;
    {
        std::lock_guard<std::mutex> lock(mu_);
        int side0 = 2 * windowRadius + 1;
        for (int lod = 0; lod < lodCount; lod++)
            for (int ry = 0; ry < regionRows(lod); ry++)
                for (int z = 0; z < side0; z++)
                    for (int x = 0; x < side0; x++) {
                        Key3 k{lod, centreRegion[lod][0] - windowRadius + x, ry, centreRegion[lod][1] - windowRadius + z};
                        auto it = regions_.find(k);
                        if (it != regions_.end()) regions.emplace(k, it->second);
                        auto f = fading_.find(k);
                        if (f != fading_.end()) fading.emplace(k, f->second);
                    }
        shapesSnap = shapes_;
        cardsSnap = cards_;
    }
    PackedScene p;
    p.lodCount = lodCount;
    p.revision = packed()->revision + 1;
    std::unordered_map<const Region*, int32_t> slot;
    {
        // the groups' header, the material shapes and the material -> shape table (shaders/atlas_groups.slang)
        const ShapeSet& sh = *shapesSnap;
        p.groups.assign(4, 0);
        std::vector<uint32_t> table(sh.byMaterial.size(), 0);
        uint32_t shapeBase = 1, n = 0;
        for (size_t m = 0; m < sh.byMaterial.size(); m++) {
            const MaterialShape* s = sh.byMaterial[m].get();
            if (!s) continue;
            table[m] = ++n;
            size_t at = p.groups.size();
            p.groups.resize(at + SHAPE_UINT4 * 4, 0);
            uint32_t* w = &p.groups[at];
            w[0] = uint32_t(s->mask);
            w[1] = uint32_t(s->mask >> 32);
            w[2] = s->flags;
            w[3] = s->mean;
            std::memcpy(w + 4, s->faces, sizeof s->faces);
            std::memcpy(w + 100, s->alpha, sizeof s->alpha);
        }
        uint32_t tableBase = uint32_t(p.groups.size());
        p.groups.insert(p.groups.end(), table.begin(), table.end());
        p.groups.resize((p.groups.size() + 3) & ~size_t(3), 0);
        p.groups[0] = tableBase;
        p.groups[1] = shapeBase;
        p.groups[2] = uint32_t(table.size());
        // the surface cards: a header (table entries, the table's first uint4, the cards' first word, count), an
        // open-addressing table of (x, y, z, face + 1 | card << 3), then each card's mip chain
        const CardSet& cs = *cardsSnap;
        if (!cs.cards.empty()) {
            uint32_t size = 1;
            while (size < cs.cards.size() * 2) size <<= 1;
            uint32_t head = uint32_t(p.groups.size() / 4);
            p.groups.resize(p.groups.size() + 4 + size_t(size) * 4, 0);
            uint32_t tableAt = head + 1, dataAt = uint32_t(p.groups.size());
            for (size_t i = 0; i < cs.cards.size(); i++) {
                const SurfaceCard& c = cs.cards[i];
                uint32_t h = cutHash(c.x, c.y, c.z ^ int(c.face * 0x9E3779B1u)) & (size - 1);
                for (;;) {
                    uint32_t* e = &p.groups[size_t(tableAt + h) * 4];
                    if (e[3] == 0) {
                        e[0] = uint32_t(c.x), e[1] = uint32_t(c.y), e[2] = uint32_t(c.z), e[3] = (c.face + 1) | uint32_t(i) << 3;
                        break;
                    }
                    h = (h + 1) & (size - 1);
                }
                p.groups.insert(p.groups.end(), c.mip, c.mip + CARD_WORDS);
            }
            p.groups.resize((p.groups.size() + 3) & ~size_t(3), 0);
            uint32_t* hd = &p.groups[size_t(head) * 4];
            hd[0] = size, hd[1] = tableAt, hd[2] = dataAt, hd[3] = uint32_t(cs.cards.size());
            p.groups[3] = head;
        }
    }
    // a region's DAG, materials and height mip appended to the scene; returns its slot
    auto pack = [&](const Region* r, const Key3& k) {
        auto s = slot.find(r);
        if (s != slot.end()) return s->second;
        int lod = k.lod;
        RegionGpu g{};
        g.origin[0] = k.x * (256 << lod);
        g.origin[1] = WORLD_MIN_Y + k.y * (256 << lod);
        g.origin[2] = k.z * (256 << lod);
        g.origin[3] = lod;
        const Svdag& dag = *r->dag;
        g.bases[0] = uint32_t(p.nodes.size());
        g.bases[1] = uint32_t(p.leaves.size());
        g.bases[2] = dag.root;
        g.bases[3] = uint32_t(p.heightMip.size());
        p.heightMip.insert(p.heightMip.end(), dag.heightMip.begin(), dag.heightMip.end());
        g.runs[0] = uint32_t(p.runEnd.size());
        g.runs[1] = uint32_t(dag.runEnd.size());
        p.nodes.insert(p.nodes.end(), dag.nodes.begin(), dag.nodes.end());
        p.leaves.insert(p.leaves.end(), dag.leaves.begin(), dag.leaves.end());
        p.runEnd.insert(p.runEnd.end(), dag.runEnd.begin(), dag.runEnd.end());
        for (uint16_t m : dag.runMat) p.runMat.push_back(m);
        g.groups[0] = 0xFFFFFFFFu;
        if (r->groups && !r->groups->empty() && !r->groups->refused) {
            const GroupTree& t = *r->groups;
            g.groups[0] = uint32_t(p.groups.size() / 4);
            p.groups.insert(p.groups.end(), t.nodes.begin(), t.nodes.end());
            g.groups[1] = uint32_t(p.groups.size());
            p.groups.insert(p.groups.end(), t.dir.begin(), t.dir.end());
            g.groups[2] = uint32_t(p.groups.size());
            p.groups.insert(p.groups.end(), t.slots.begin(), t.slots.end());
            g.groups[3] = uint32_t(p.groups.size());
            p.groups.insert(p.groups.end(), t.bricks.begin(), t.bricks.end());
            p.groups.resize((p.groups.size() + 3) & ~size_t(3), 0);
        }
        int32_t v = int32_t(p.regions.size());
        p.regions.push_back(g);
        slot.emplace(r, v);
        return v;
    };
    int side = 2 * windowRadius + 1;
    {  // every array's final size first: appended unreserved, an 82 MB scene was reallocated and copied over and over
        size_t nodes = 0, leaves = 0, runs = 0, hmip = 0, grp = p.groups.size(), regs = 0;
        std::unordered_set<const Region*> seen;
        auto count = [&](const Region* r) {
            if (!r || r->empty() || !seen.insert(r).second) return;
            regs++;
            nodes += r->dag->nodes.size(), leaves += r->dag->leaves.size(), runs += r->dag->runEnd.size();
            hmip += r->dag->heightMip.size();
            if (r->groups && !r->groups->empty() && !r->groups->refused) {
                const GroupTree& t = *r->groups;
                grp += t.nodes.size() + t.dir.size() + t.slots.size() + t.bricks.size() + 3;
            }
        };
        for (auto& [k, r] : regions) count(r.get());
        for (auto& [k, f] : fading) count(f.old.get());
        p.nodes.reserve(nodes), p.leaves.reserve(leaves), p.runEnd.reserve(runs), p.runMat.reserve(runs);
        p.heightMip.reserve(hmip), p.groups.reserve(grp), p.regions.reserve(regs + 1);
        p.table.reserve(size_t(lodCount) * side * side * 16);
    }
    for (int lod = 0; lod < lodCount; lod++) {
        int rows = regionRows(lod);
        LodGpu& L = p.lods[lod];
        L.origin[0] = centreRegion[lod][0] - windowRadius;
        L.origin[1] = 0;
        L.origin[2] = centreRegion[lod][1] - windowRadius;
        L.origin[3] = WORLD_MIN_Y;
        L.dim[0] = side;
        L.dim[1] = rows;
        L.dim[2] = side;
        L.dim[3] = int32_t(p.table.size());
        for (int ry = 0; ry < rows; ry++)
            for (int z = 0; z < side; z++)
                for (int x = 0; x < side; x++) {
                    Key3 k{lod, L.origin[0] + x, ry, L.origin[2] + z};
                    auto it = regions.find(k);
                    int32_t v = -2;
                    if (it != regions.end()) {
                        const Region* r = it->second.get();
                        if (r->empty()) {
                            v = -1;
                        } else {
                            auto s = slot.find(r);
                            if (s != slot.end()) {
                                v = s->second;
                            } else {
                                v = pack(r, k);
                                auto f = fading.find(k);
                                if (f != fading.end()) {
                                    // the kernel dissolves from runs.z (old slot + 1; ~1: it was empty air, walk on at
                                    // this LOD; ~0: new to the window, look coarser) to this region between runs.w
                                    // and runs.w + fadeMs
                                    uint32_t oldSlot = f->second.old ? uint32_t(pack(f->second.old.get(), k)) + 1u
                                                     : f->second.wasEmpty ? 0xFFFFFFFEu : 0xFFFFFFFFu;
                                    p.regions[size_t(v)].runs[2] = oldSlot;
                                    p.regions[size_t(v)].runs[3] = f->second.startMs;
                                }
                            }
                        }
                    }
                    p.table.push_back(v);
                }
    }
    // kernels index these unconditionally; never hand them an empty buffer
    if (p.nodes.empty()) p.nodes.push_back(0);
    if (p.leaves.empty()) p.leaves.push_back(0);
    if (p.runEnd.empty()) p.runEnd.push_back(0);
    if (p.runMat.empty()) p.runMat.push_back(0);
    if (p.regions.empty()) p.regions.push_back({});
    if (p.heightMip.empty()) p.heightMip.push_back(0);
    {
        auto next = std::make_shared<const PackedScene>(std::move(p));
        std::lock_guard<std::mutex> snap(snapMu_);
        packed_ = std::move(next);
    }
    std::lock_guard<std::mutex> lock(mu_);
    packedShapes_ = shapesSnap->revision;
    packedCards_ = cardsSnap->revision;
    for (int lod = 0; lod < lodCount; lod++) {
        lastCentre_[lod][0] = centreRegion[lod][0];
        lastCentre_[lod][1] = centreRegion[lod][1];
    }
    havePacked_ = true;
}

void World::packer() {
#ifdef _WIN32
    SetThreadPriority(GetCurrentThread(), -1);
#endif
    std::unique_lock<std::mutex> lock(packMu_);
    for (;;) {
        packCv_.wait(lock, [&] { return stopBuilders_ || packWanted_; });
        if (stopBuilders_) return;
        packWanted_ = false;
        int c[MAX_LODS][2];
        std::memcpy(c, packCentre_, sizeof c);
        lock.unlock();
        try {
            repack(c);
        } catch (...) {
            // (out of memory: the scene stays as it was; the next update asks again)
        }
        lock.lock();
    }
}

uint32_t nowMs() {
    static const auto t0 = std::chrono::steady_clock::now();
    return uint32_t(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count());
}

uint64_t World::nodeBytes() const { return packed()->nodes.size() * 4; }
uint64_t World::leafBytes() const { return packed()->leaves.size() * 8; }
uint64_t World::materialBytes() const {
    auto p = packed();
    return p->runEnd.size() * 4 + p->runMat.size() * 4;
}

}  // namespace atlas
