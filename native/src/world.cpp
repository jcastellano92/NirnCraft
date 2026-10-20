#include "world.hpp"
#include "parallel.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <unordered_set>
#include <cstring>
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
            // the voxel's first block in world blocks, its section, its coordinates in that section's level
            int s = 1 << lod_;
            int wx = (bx_ + x) * s, wy = WORLD_MIN_Y + (by_ - WORLD_MIN_Y + y) * s, wz = (bz_ + z) * s;
            auto it = sections_->find({0, floorDiv(wx, 16), floorDiv(wy, 16), floorDiv(wz, 16)});
            if (it != sections_->end() && (lod_ == 0 || !it->second->mip[0].empty()))
                return it->second->at(lod_, (wx & 15) >> lod_, (wy & 15) >> lod_, (wz & 15) >> lod_);
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
    int lod_ = 0;
    float s_, y0_;
    int bx_, by_, bz_;
};

}  // namespace

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

void World::putSection(int sx, int sy, int sz, const uint16_t* mats) {
    auto s = std::make_unique<Section>();
    std::copy(mats, mats + 4096, s->mats.begin());
    std::lock_guard<std::mutex> lock(mu_);
    s->buildMips(classes_);
    sections_[{0, sx, sy, sz}] = std::move(s);
    markSectionDirty(sx, sy, sz);
}

void World::markSectionDirty(int sx, int sy, int sz) {
    // the section's region at every level that reads sections (0..4); with groups also the region across a border it
    // touches (whose border voxels' exposure it decides)
    bool nbrs = groupsWanted.load(std::memory_order_relaxed);
    for (int lod = 0; lod <= 4; lod++) {
        int span = 256 << lod, per = span / 16;
        int rx = floorDiv(sx * 16, span), ry = floorDiv(sy * 16 - WORLD_MIN_Y, span), rz = floorDiv(sz * 16, span);
        dirty_.insert({lod, rx, ry, rz});
        if (!nbrs) continue;
        int ix = sx - rx * per, iz = sz - rz * per, iy = sy - floorDiv(WORLD_MIN_Y, 16) - ry * per;
        if (ix == 0) dirty_.insert({lod, rx - 1, ry, rz});
        if (ix == per - 1) dirty_.insert({lod, rx + 1, ry, rz});
        if (iz == 0) dirty_.insert({lod, rx, ry, rz - 1});
        if (iz == per - 1) dirty_.insert({lod, rx, ry, rz + 1});
        if (iy == 0 && ry > 0) dirty_.insert({lod, rx, ry - 1, rz});
        if (iy == per - 1 && ry + 1 < regionRows(lod)) dirty_.insert({lod, rx, ry + 1, rz});
    }
}

// docs/ATLAS-LOD.md: a coarse voxel from its 2 x 2 x 2 children. Solid (terrain, walls, trunks, emitters' blocks) when
// 3 or more children are solid; else foliage when 2 or more are leaves; else water when 4 or more are water; else air.
// Its material: the most common among the children of the winning kind, ties to the higher child.
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
                    uint16_t best = 0;
                    int bestN = 0;
                    for (int a = 7; a >= 0; a--) {  // the upper children first: ties go up
                        if (kind(ch[a]) != want) continue;
                        int c = 0;
                        for (int b = 0; b < 8; b++) c += ch[b] == ch[a] ? 1 : 0;
                        if (c > bestN) {
                            bestN = c;
                            best = ch[a];
                        }
                    }
                    out[size_t(x + m * (z + m * y))] = best;
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
    if (it != sections_.end()) {
        s = std::make_shared<Section>(*it->second);  // copy on write: builders may hold the old one
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
    s->buildMips(classes_);
    sections_[sk] = std::move(s);
    markSectionDirty(sx, sy, sz);
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
    if (groups && k.lod <= 4 && !sections_.empty()) {
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
    } else if (k.lod <= 4 && !sections_.empty()) {
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

// The voxels a region's groups are built from (GroupInput): every column of the region and its border ring, each
// evaluated over the rows where it may hold exposed content or decide a neighbour's exposure, exactly as TileVoxels
// evaluates them (real blocks where sections are, LOD 0 voxels marked VOXEL_SHAPED, the column tiles elsewhere).
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
    // canopy or water, and all its sections
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
            if (secs) {
                int wx = (k.x * 256 + x) * s, wz = (k.z * 256 + z) * s;
                auto it = secRows.find(ckey(floorDiv(wx, 16), floorDiv(wz, 16)));
                if (it != secRows.end()) {
                    lo = std::min(lo, floorDiv(it->second.first * 16 - WORLD_MIN_Y, s));
                    hi = std::max(hi, ((it->second.second + 1) * 16 - WORLD_MIN_Y + s - 1) / s);
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
    // A coarser level's voxel from real blocks: the mip's material (or, where the mip's vote left it air, its blocks'
    // commonest), and its own micro-voxels by coverage: each micro-voxel is there with the probability that a ray across
    // it meets what its blocks hold (a shaped block by its shape, cut-out leaves by their texels' alpha), hashed in world
    // space. Solid parts count by the share they fill; cut-out parts as a ray crossing 2^lod of the finest micro-voxels
    // sees them (1 - (1 - share)^(2^lod): a crown keeps its opacity as it coarsens instead of thinning or filling its
    // voxels), so a thin wall or a trunk keeps its share (coverage, docs/ATLAS-LOD.md's plan of record step 1, in the
    // groups' micro-voxels).
    struct Cover {
        float solid = -1, cut = 0;  // shares of a block: what is solid, what is cut-out (by alpha)
    };
    std::vector<Cover> covOf;  // per material (solid < 0: not known yet)
    auto blockCover = [&](uint16_t m) -> Cover {
        if (!m) return {0, 0};
        if (m >= covOf.size()) covOf.resize(size_t(m) + 1);
        Cover& c = covOf[m];
        if (c.solid < 0) {
            const MaterialShape* s = shapes.at(m);
            c = {1.0f, 0.0f};
            if (s) {
                float sum = 0;
                for (int b = 0; b < 64; b++)
                    if ((s->mask >> b) & 1) sum += float(s->alpha[b]) / 255.0f;
                c = (s->flags & SHAPE_CUTOUT) ? Cover{0.0f, sum / 64.0f} : Cover{sum / 64.0f, 0.0f};
            }
        }
        return c;
    };
    auto effective = [&](float solid, float cut) {
        solid = std::min(solid, 1.0f);
        if (solid >= 0.999f) return 1.0f;
        float rest = std::min(cut / (1.0f - solid), 1.0f);
        return solid + (1.0f - solid) * (1.0f - std::pow(1.0f - rest, float(1 << lod)));
    };
    auto coarseVoxel = [&](const Section& sec, int wx, int wy, int wz, uint16_t mip, GroupInput& gi, uint32_t idx) -> uint32_t {
        int bx = wx & 15, by = wy & 15, bz = wz & 15;  // its first block in the section
        int n = 1 << lod;
        uint64_t mask = 0;
        uint16_t common = 0;
        {
            // the commonest kept block, where the mip voted air
            uint16_t seen[8] = {};
            int counts[8] = {};
            if (!mip)
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
        for (int b = 0; b < 64; b++) {
            int mx = b & 3, my = b >> 4, mz = (b >> 2) & 3;
            float c = 0;
            if (lod == 1) {
                // a micro-voxel is half a block: the 2 x 2 x 2 of that block's own micro-voxels under it
                uint16_t m = sec.mats[size_t((bx + (mx >> 1)) + 16 * ((bz + (mz >> 1)) + 16 * (by + (my >> 1))))];
                if (m) {
                    const MaterialShape* sh = shapes.at(m);
                    bool cutout = sh && (sh->flags & SHAPE_CUTOUT);
                    float sum = 0;
                    for (int q = 0; q < 8; q++) {
                        int ux = (mx & 1) * 2 + (q & 1), uy = (my & 1) * 2 + (q >> 2), uz = (mz & 1) * 2 + ((q >> 1) & 1);
                        int ub = ux + 4 * (uz + 4 * uy);
                        sum += !sh ? 1.0f : ((sh->mask >> ub) & 1) ? float(sh->alpha[ub]) / 255.0f : 0.0f;
                    }
                    c = cutout ? effective(0.0f, sum / 8.0f) : sum / 8.0f;
                }
            } else {
                int kb = n / 4;  // blocks a side of a micro-voxel
                float solid = 0, cut = 0;
                for (int y = 0; y < kb; y++)
                    for (int z = 0; z < kb; z++)
                        for (int x = 0; x < kb; x++) {
                            Cover cv = blockCover(sec.mats[size_t((bx + mx * kb + x) + 16 * ((bz + mz * kb + z) + 16 * (by + my * kb + y)))]);
                            solid += cv.solid, cut += cv.cut;
                        }
                float nb = float(kb * kb * kb);
                c = effective(solid / nb, cut / nb);
            }
            if (c <= 0.0f) continue;
            if (c < 0.999f) {
                // hashed at its world micro-voxel of this level (stable across rebuilds and regions)
                uint32_t h = cutHash((wx >> lod) * 4 + mx + lod * 1000003, (wy >> lod) * 4 + my, (wz >> lod) * 4 + mz);
                if ((h >> 24) >= uint32_t(c * 255.0f + 0.5f)) continue;
            }
            mask |= 1ull << b;
        }
        if (!mask) return 0;
        if (mask == SHAPE_FULL) return mat;
        gi.masks[idx] = mask;
        return uint32_t(mat) | VOXEL_MASKED;
    };
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
                        if (lod > 0) v = coarseVoxel(*sec, wx, wy, wz, m, in, uint32_t(out - in.vox.data()) +
                                                     uint32_t(row - in.lo[size_t(ci)]));
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
    std::vector<Job> jobs;
    bool groups = groupsWanted.load();
    std::shared_ptr<const ShapeSet> shapes;
    {
        std::lock_guard<std::mutex> lock(mu_);
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
                        if (r == regions_.end() || dirty_.count(k) ||
                            (groups && lod < groupLods && !r->second->groups && (!r->second->empty() || (lod <= 4 && !sections_.empty()))))
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
        auto order = [&](const Key3& k) {
            int d = std::abs(k.x - centreRegion[k.lod][0]) + std::abs(k.z - centreRegion[k.lod][1]);
            bool missing = regions_.find(k) == regions_.end();
            int coarseFirst = (MAX_LODS - k.lod) * 64 + d;
            if (hidden(k)) {
                hiddenCount++;
                return std::make_pair(2, coarseFirst);
            }
            return missing ? std::make_pair(0, coarseFirst) : std::make_pair(1, d + k.lod);
        };
        std::vector<std::pair<std::pair<int, int>, Key3>> keyed;
        keyed.reserve(want.size());
        for (const Key3& k : want) keyed.push_back({order(k), k});
        std::stable_sort(keyed.begin(), keyed.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
        for (size_t i = 0; i < want.size(); i++) want[i] = keyed[i].second;
        lastHidden_ = hiddenCount;
        // ATLAS_SKIP_HIDDEN (a measurement): hidden regions are never built, so the settled picture shows what the
        // horizon test hid wrongly
        static const bool skipHidden = std::getenv("ATLAS_SKIP_HIDDEN") != nullptr;
        if (skipHidden)
            while (!keyed.empty() && keyed.back().first.first == 2) keyed.pop_back(), want.pop_back();
        jobs.resize(want.size());
        shapes = shapes_;
    }

    // each job's inputs are snapshot when a worker takes it, not all up front: with a budget, snapshotting all 250
    // wanted regions took longer than the budget itself and nothing was built (ATLAS-STREAMING.md); the budget counts
    // from here
    auto workStart = std::chrono::steady_clock::now();
    std::atomic<size_t> next{0};
    std::atomic<int> built{0}, treesBuilt{0};
    std::atomic<int64_t> treeUs{0};
    std::vector<std::pair<Key3, std::shared_ptr<const Region>>> results(want.size());
    auto worker = [&] {
        for (;;) {
            size_t i = next.fetch_add(1);
            if (i >= want.size()) return;
            if (budgetMs > 0) {
                double el = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - workStart).count();
                if (el > budgetMs) return;
            }
            {
                std::lock_guard<std::mutex> lock(mu_);
                const Key3& k = want[i];
                jobs[i] = snapshot(k, groups && k.lod < groupLods);
                auto r = regions_.find(k);
                jobs[i].wasDirty = dirty_.erase(k) != 0;  // an edit while it builds marks it again (built next time)
                if (r != regions_.end() && !jobs[i].wasDirty) jobs[i].keep = r->second;  // only its tree is missing
            }
            const Job& j = jobs[i];
            Region r;
            if (j.keep) {
                r.key = j.key;
                r.dag = j.keep->dag;
            } else {
                r = buildRegion(j);
            }
            if (j.groups && (!r.empty() || !j.sections.empty())) {
                r.groups = buildGroups(j, *shapes);
                treesBuilt++;
                treeUs += int64_t(r.groups->buildMs * 1000.0);
            }
            results[i] = {want[i], std::make_shared<const Region>(std::move(r))};
            built++;
        }
    };
    parallelRun(worker);  // a worker's exception (bad_alloc) comes back here, after all have stopped

    bool moved = !havePacked_;
    {
        std::lock_guard<std::mutex> lock(mu_);
        lastGroupsBuilt_ = uint32_t(treesBuilt.load());
        lastGroupsMs_ = double(treeUs.load()) / 1000.0;
        uint32_t now = nowMs();
        for (size_t i = 0; i < results.size(); i++) {
            auto& [k, r] = results[i];
            if (!r) {
                if (jobs[i].wasDirty) dirty_.insert(want[i]);  // not built within the budget: still to do
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
                it = sections_.erase(it);
            else ++it;
        }
        for (int lod = 0; lod < lodCount; lod++)
            if (centreRegion[lod][0] != lastCentre_[lod][0] || centreRegion[lod][1] != lastCentre_[lod][1]) moved = true;
    }
    bool reshaped;
    {
        std::lock_guard<std::mutex> lock(mu_);
        reshaped = shapes_->revision != packedShapes_ || cards_->revision != packedCards_;
    }
    lastPending_ = want.size() - size_t(built.load());
    auto tBuilt = std::chrono::steady_clock::now();
    bool repacked = built > 0 || moved || reshaped;
    if (repacked) repack(centreRegion);
    auto tEnd = std::chrono::steady_clock::now();
    if (buildMs) *buildMs = std::chrono::duration<double, std::milli>(tEnd - start).count();
    static const bool trace = std::getenv("ATLAS_TRACE_UPDATE") != nullptr;  // streaming measurements (ATLAS-STREAMING.md)
    if (trace && (built > 0 || repacked)) {
        auto p = packed();
        std::fprintf(stderr, "[atlas] update: %d of %zu wanted (%d behind the horizon) built in %.1f ms, repack %.1f ms (%zu regions, %.1f MB)\n",
                     built.load(), want.size(), lastHidden_.load(), std::chrono::duration<double, std::milli>(tBuilt - start).count(),
                     std::chrono::duration<double, std::milli>(tEnd - tBuilt).count(), p->regions.size(),
                     double(p->nodes.size() * 4 + p->leaves.size() * 8 + p->groups.size() * 4 + p->heightMip.size() * 4) / 1e6);
    }
    return built;
}

void World::setShapes(uint32_t first, const std::vector<std::shared_ptr<const MaterialShape>>& shapes) {
    std::lock_guard<std::mutex> lock(mu_);
    auto next = std::make_shared<ShapeSet>(*shapes_);  // copy on write: builders hold the old snapshot
    if (next->byMaterial.size() < first + shapes.size()) next->byMaterial.resize(first + shapes.size());
    for (size_t i = 0; i < shapes.size(); i++) next->byMaterial[first + i] = shapes[i];
    next->revision = shapes_->revision + 1;
    shapes_ = std::move(next);
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
    std::lock_guard<std::mutex> lock(mu_);
    PackedScene p;
    p.lodCount = lodCount;
    p.revision = packed()->revision + 1;
    std::unordered_map<const Region*, int32_t> slot;
    {
        // the groups' header, the material shapes and the material -> shape table (shaders/atlas_groups.slang)
        const ShapeSet& sh = *shapes_;
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
        packedShapes_ = sh.revision;
        // the surface cards: a header (table entries, the table's first uint4, the cards' first word, count), an
        // open-addressing table of (x, y, z, face + 1 | card << 3), then each card's mip chain
        const CardSet& cs = *cards_;
        packedCards_ = cs.revision;
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
        for (auto& [k, r] : regions_) count(r.get());
        for (auto& [k, f] : fading_) count(f.old.get());
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
                    auto it = regions_.find(k);
                    int32_t v = -2;
                    if (it != regions_.end()) {
                        const Region* r = it->second.get();
                        if (r->empty()) {
                            v = -1;
                        } else {
                            auto s = slot.find(r);
                            if (s != slot.end()) {
                                v = s->second;
                            } else {
                                v = pack(r, k);
                                auto f = fading_.find(k);
                                if (f != fading_.end()) {
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
    for (int lod = 0; lod < lodCount; lod++) {
        lastCentre_[lod][0] = centreRegion[lod][0];
        lastCentre_[lod][1] = centreRegion[lod][1];
    }
    havePacked_ = true;
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
