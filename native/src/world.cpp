#include "world.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <thread>

namespace aether {
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
        if (k.lod != 0 || !sections_ || sections_->empty()) sections_ = nullptr;
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
            int wx = bx_ + x, wy = by_ + y, wz = bz_ + z;
            auto it = sections_->find({0, floorDiv(wx, 16), floorDiv(wy, 16), floorDiv(wz, 16)});
            if (it != sections_->end()) {
                int lx = wx & 15, ly = wy & 15, lz = wz & 15;
                return it->second->mats[size_t(lx + 16 * (lz + 16 * ly))];
            }
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
            if (depth <= s) return t.top[c];
            if (depth <= s + FILL_DEPTH) return t.fill[c];
            return stone;
        }
        // canopy: crowns over trunk space (the lowest 35 % stays open, so forest edges are not solid walls)
        if (!t.canopy.empty() && t.canopy[c] > 0.0f && yc < surf + t.canopy[c] && yc >= surf + 0.35f * t.canopy[c])
            return t.canopyMat[c];
        float w = t.water[c];
        if (!std::isnan(w) && yc < w) return water;
        return 0;
    }

private:
    bool anySection(int x, int y, int z, int n) const {
        int sx0 = floorDiv(bx_ + x, 16), sx1 = floorDiv(bx_ + x + n - 1, 16);
        int sy0 = floorDiv(by_ + y, 16), sy1 = floorDiv(by_ + y + n - 1, 16);
        int sz0 = floorDiv(bz_ + z, 16), sz1 = floorDiv(bz_ + z + n - 1, 16);
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sz = sz0; sz <= sz1; sz++)
                for (int sx = sx0; sx <= sx1; sx++)
                    if (sections_->count({0, sx, sy, sz})) return true;
        return false;
    }

    const Tile* t_;
    uint16_t stone_, water_;
    const SectionMap* sections_;
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
    t->buildPyramids();
    std::lock_guard<std::mutex> lock(mu_);
    Key3 k{t->lod, t->tx, 0, t->tz};
    for (int ry = 0; ry < regionRows(t->lod); ry++) dirty_.insert({t->lod, t->tx, ry, t->tz});
    tiles_[k] = std::shared_ptr<const Tile>(std::move(t));
}

const Tile* World::tile(int lod, int tx, int tz) const {
    auto it = tiles_.find({lod, tx, 0, tz});
    return it == tiles_.end() ? nullptr : it->second.get();
}

void World::putSection(int sx, int sy, int sz, const uint16_t* mats) {
    auto s = std::make_unique<Section>();
    std::copy(mats, mats + 4096, s->mats.begin());
    std::lock_guard<std::mutex> lock(mu_);
    sections_[{0, sx, sy, sz}] = std::move(s);
    int rx = floorDiv(sx * 16, 256), ry = floorDiv(sy * 16 - WORLD_MIN_Y, 256), rz = floorDiv(sz * 16, 256);
    dirty_.insert({0, rx, ry, rz});
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
    sections_[sk] = std::move(s);
    dirty_.insert({0, floorDiv(x, 256), floorDiv(y - WORLD_MIN_Y, 256), floorDiv(z, 256)});
}

World::Job World::snapshot(const Key3& k) const {
    Job j;
    j.key = k;
    auto it = tiles_.find({k.lod, k.x, 0, k.z});
    if (it != tiles_.end()) j.tile = it->second;
    if (k.lod == 0 && !sections_.empty()) {
        int sx0 = k.x * 16, sy0 = (WORLD_MIN_Y + k.y * 256) / 16, sz0 = k.z * 16;
        if (sections_.size() < 4096) {
            for (auto& [sk, sec] : sections_)
                if (sk.x >= sx0 && sk.x < sx0 + 16 && sk.y >= sy0 && sk.y < sy0 + 16 && sk.z >= sz0 && sk.z < sz0 + 16)
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
    unsigned n = std::max(1u, std::thread::hardware_concurrency());
    std::vector<std::thread> pool;
    for (unsigned i = 0; i + 1 < n; i++) pool.emplace_back(worker);
    worker();
    for (auto& th : pool) th.join();

    bool moved = !havePacked_;
    {
        std::lock_guard<std::mutex> lock(mu_);
        for (auto& [k, r] : results) {
            if (!r) continue;
            regions_[k] = r;
            dirty_.erase(k);
        }
        // evict regions far outside the window (keep one ring of slack against thrashing)
        for (auto it = regions_.begin(); it != regions_.end();) {
            const Key3& k = it->first;
            int dx = std::abs(k.x - centreRegion[k.lod][0]), dz = std::abs(k.z - centreRegion[k.lod][1]);
            if (k.lod >= lodCount || dx > windowRadius + 1 || dz > windowRadius + 1) it = regions_.erase(it);
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
    p.revision = packed_.revision + 1;
    std::unordered_map<const Region*, int32_t> slot;
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
                                RegionGpu g{};
                                g.origin[0] = k.x * (256 << lod);
                                g.origin[1] = WORLD_MIN_Y + k.y * (256 << lod);
                                g.origin[2] = k.z * (256 << lod);
                                g.origin[3] = lod;
                                g.bases[0] = uint32_t(p.nodes.size());
                                g.bases[1] = uint32_t(p.leaves.size());
                                g.bases[2] = r->dag.root;
                                g.runs[0] = uint32_t(p.runEnd.size());
                                g.runs[1] = uint32_t(r->dag.runEnd.size());
                                p.nodes.insert(p.nodes.end(), r->dag.nodes.begin(), r->dag.nodes.end());
                                p.leaves.insert(p.leaves.end(), r->dag.leaves.begin(), r->dag.leaves.end());
                                p.runEnd.insert(p.runEnd.end(), r->dag.runEnd.begin(), r->dag.runEnd.end());
                                for (uint16_t m : r->dag.runMat) p.runMat.push_back(m);
                                v = int32_t(p.regions.size());
                                p.regions.push_back(g);
                                slot.emplace(r, v);
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
    packed_ = std::move(p);
    for (int lod = 0; lod < lodCount; lod++) {
        lastCentre_[lod][0] = centreRegion[lod][0];
        lastCentre_[lod][1] = centreRegion[lod][1];
    }
    havePacked_ = true;
}

uint64_t World::nodeBytes() const { return packed_.nodes.size() * 4; }
uint64_t World::leafBytes() const { return packed_.leaves.size() * 8; }
uint64_t World::materialBytes() const { return packed_.runEnd.size() * 4 + packed_.runMat.size() * 4; }

}  // namespace aether
