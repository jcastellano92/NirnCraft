#include "world.hpp"
#include "parallel.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
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
    // the section's region at every level that reads sections (0..4)
    for (int lod = 0; lod <= 4; lod++) {
        int span = 256 << lod;
        dirty_.insert({lod, floorDiv(sx * 16, span), floorDiv(sy * 16 - WORLD_MIN_Y, span), floorDiv(sz * 16, span)});
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

World::Job World::snapshot(const Key3& k) const {
    Job j;
    j.key = k;
    auto it = tiles_.find({k.lod, k.x, 0, k.z});
    if (it != tiles_.end()) j.tile = it->second;
    if (k.lod <= 4 && !sections_.empty()) {
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
    r.dag = buildSvdag(src);
    return r;
}

int World::update(const double centre[3], double budgetMs, double* buildMs) {
    auto start = std::chrono::steady_clock::now();
    int centreRegion[MAX_LODS][2];
    std::vector<Key3> want;
    std::vector<Job> jobs;
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
                        if (!regions_.count(k) || dirty_.count(k)) want.push_back(k);
                    }
                }
        }
        // nearest and finest first
        std::sort(want.begin(), want.end(), [&](const Key3& a, const Key3& b) {
            auto d = [&](const Key3& k) {
                return std::abs(k.x - centreRegion[k.lod][0]) + std::abs(k.z - centreRegion[k.lod][1]) + k.lod;
            };
            return d(a) < d(b);
        });
        jobs.reserve(want.size());
        for (const Key3& k : want) jobs.push_back(snapshot(k));
    }

    std::atomic<size_t> next{0};
    std::atomic<int> built{0};
    std::vector<std::pair<Key3, std::shared_ptr<const Region>>> results(want.size());
    auto worker = [&] {
        for (;;) {
            size_t i = next.fetch_add(1);
            if (i >= want.size()) return;
            if (budgetMs > 0) {
                double el = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
                if (el > budgetMs) return;
            }
            Region r = buildRegion(jobs[i]);
            results[i] = {want[i], std::make_shared<const Region>(std::move(r))};
            built++;
        }
    };
    parallelRun(worker);  // a worker's exception (bad_alloc) comes back here, after all have stopped

    bool moved = !havePacked_;
    {
        std::lock_guard<std::mutex> lock(mu_);
        uint32_t now = nowMs();
        for (auto& [k, r] : results) {
            if (!r) continue;
            auto old = regions_.find(k);
            if (fadeMs > 0 && !r->empty()) {
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
            dirty_.erase(k);
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
        for (auto it = sections_.begin(); it != sections_.end();) {
            int rx = floorDiv(it->first.x * 16, 256), rz = floorDiv(it->first.z * 16, 256);
            if (std::abs(rx - centreRegion[0][0]) > windowRadius + 2 || std::abs(rz - centreRegion[0][1]) > windowRadius + 2)
                it = sections_.erase(it);
            else ++it;
        }
        for (int lod = 0; lod < lodCount; lod++)
            if (centreRegion[lod][0] != lastCentre_[lod][0] || centreRegion[lod][1] != lastCentre_[lod][1]) moved = true;
    }
    if (built > 0 || moved) repack(centreRegion);
    if (buildMs) *buildMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    return built;
}

void World::repack(const int centreRegion[MAX_LODS][2]) {
    std::lock_guard<std::mutex> lock(mu_);
    PackedScene p;
    p.lodCount = lodCount;
    p.revision = packed()->revision + 1;
    std::unordered_map<const Region*, int32_t> slot;
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
        g.bases[0] = uint32_t(p.nodes.size());
        g.bases[1] = uint32_t(p.leaves.size());
        g.bases[2] = r->dag.root;
        g.bases[3] = uint32_t(p.heightMip.size());
        p.heightMip.insert(p.heightMip.end(), r->dag.heightMip.begin(), r->dag.heightMip.end());
        g.runs[0] = uint32_t(p.runEnd.size());
        g.runs[1] = uint32_t(r->dag.runEnd.size());
        p.nodes.insert(p.nodes.end(), r->dag.nodes.begin(), r->dag.nodes.end());
        p.leaves.insert(p.leaves.end(), r->dag.leaves.begin(), r->dag.leaves.end());
        p.runEnd.insert(p.runEnd.end(), r->dag.runEnd.begin(), r->dag.runEnd.end());
        for (uint16_t m : r->dag.runMat) p.runMat.push_back(m);
        int32_t v = int32_t(p.regions.size());
        p.regions.push_back(g);
        slot.emplace(r, v);
        return v;
    };
    int side = 2 * windowRadius + 1;
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
