#include "lights.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>

namespace atlas {

namespace {
constexpr int CELL = 1024;    // index cell (blocks)
constexpr int CLUSTER = 16;   // far cluster (blocks): about a pixel at the distance clusters are used from

struct Rec {
    double x, y, z;
    float radius, intensity;
    float r, g, b;  // linear
    uint32_t kind;
    float seed;     // flicker phase
};

float lin(uint32_t c) { return std::pow(float(c & 255u) / 255.0f, 2.2f); }

// How a light's brightness on screen falls with distance: slower than the inverse square of a true point source (a
// lit window a few km off still reads as a point to the eye), steep enough that a far town is a smudge, not a beacon.
float falloff(float d) { return 1.0f / std::pow(1.0f + d / 1500.0f, 1.5f); }
}  // namespace

struct LightIndex {
    // a 1 km cell: each group's lights in it, all of them (fine) and their far clusters (coarse); immutable once
    // published (an update copies the cells it touches)
    struct Cell {
        int cx, cz;
        std::map<uint32_t, std::vector<Rec>> groups;
        std::vector<Rec> fine, coarse;
    };
    std::vector<std::shared_ptr<const Cell>> cells;
    std::unordered_map<int64_t, size_t> at;  // cell key -> index in cells
    size_t total = 0;
};

namespace {
int64_t cellKey(int cx, int cz) { return (int64_t(cx) << 32) ^ int64_t(uint32_t(cz)); }

// A cell's flat list and its far clusters: lights of one kind within a CLUSTER square merged (intensity summed,
// position and colour weighted by it).
void finish(LightIndex::Cell& c) {
    c.fine.clear();
    c.coarse.clear();
    for (auto& [g, list] : c.groups) c.fine.insert(c.fine.end(), list.begin(), list.end());
    std::unordered_map<int64_t, size_t> at;
    for (const Rec& r : c.fine) {
        int64_t k = (cellKey(int(std::floor(r.x / CLUSTER)), int(std::floor(r.z / CLUSTER))) << 2) ^ int64_t(r.kind);
        auto it = at.find(k);
        if (it == at.end()) {
            at.emplace(k, c.coarse.size());
            Rec q = r;
            q.x *= r.intensity;
            q.y *= r.intensity;
            q.z *= r.intensity;
            q.r *= r.intensity;
            q.g *= r.intensity;
            q.b *= r.intensity;
            c.coarse.push_back(q);
        } else {
            Rec& q = c.coarse[it->second];
            q.x += r.x * r.intensity;
            q.y += r.y * r.intensity;
            q.z += r.z * r.intensity;
            q.r += r.r * r.intensity;
            q.g += r.g * r.intensity;
            q.b += r.b * r.intensity;
            q.intensity += r.intensity;
            q.radius = std::max(q.radius, r.radius);
        }
    }
    for (Rec& q : c.coarse) {
        float w = 1.0f / q.intensity;
        q.x *= w;
        q.y *= w;
        q.z *= w;
        q.r *= w;
        q.g *= w;
        q.b *= w;
        q.radius = std::max(q.radius, CLUSTER * 0.5f);
    }
}
}  // namespace

// Only the cells the group had or now has are rebuilt (a settlement's lights touch one or two): uploading hundreds of
// groups as a far field fills costs each one its own size, not the whole index.
void Lights::setGroup(uint32_t group, const AtlasLight* l, uint32_t n) {
    std::lock_guard<std::mutex> lock(mu_);
    std::unordered_map<int64_t, std::vector<Rec>> fresh;
    for (uint32_t i = 0; l && i < n; i++) {
        const AtlasLight& a = l[i];
        if (!std::isfinite(a.pos[0]) || !std::isfinite(a.pos[1]) || !std::isfinite(a.pos[2]) || !(a.intensity > 0)) continue;
        int cx = int(std::floor(a.pos[0] / CELL)), cz = int(std::floor(a.pos[2] / CELL));
        fresh[cellKey(cx, cz)].push_back(Rec{a.pos[0], a.pos[1], a.pos[2], std::max(0.25f, a.radius), a.intensity, lin(a.rgb),
                                             lin(a.rgb >> 8), lin(a.rgb >> 16), std::min(a.kind, 3u),
                                             float((uint32_t(int32_t(std::floor(a.pos[0]))) * 2654435761u ^
                                                    uint32_t(int32_t(std::floor(a.pos[2])))) % 6283u) * 0.001f});
    }
    std::vector<int64_t> touched = groupCells_[group];
    for (auto& [k, v] : fresh)
        if (std::find(touched.begin(), touched.end(), k) == touched.end()) touched.push_back(k);
    auto idx = index_ ? std::make_shared<LightIndex>(*index_) : std::make_shared<LightIndex>();
    for (int64_t k : touched) {
        auto it = idx->at.find(k);
        auto cell = it != idx->at.end() ? std::make_shared<LightIndex::Cell>(*idx->cells[it->second])
                                        : std::make_shared<LightIndex::Cell>();
        if (it == idx->at.end()) {
            cell->cx = int(k >> 32);
            cell->cz = int(int32_t(uint32_t(k)));
        }
        auto old = cell->groups.find(group);
        if (old != cell->groups.end()) {
            idx->total -= old->second.size();
            cell->groups.erase(old);
        }
        auto f = fresh.find(k);
        if (f != fresh.end()) {
            idx->total += f->second.size();
            cell->groups[group] = std::move(f->second);
        }
        finish(*cell);
        if (it != idx->at.end()) {
            idx->cells[it->second] = std::move(cell);
        } else if (!cell->fine.empty()) {
            idx->at.emplace(k, idx->cells.size());
            idx->cells.push_back(std::move(cell));
        }
    }
    // drop emptied cells (swap with the last, fixing its index)
    for (size_t i = 0; i < idx->cells.size();) {
        if (!idx->cells[i]->fine.empty()) {
            i++;
            continue;
        }
        idx->at.erase(cellKey(idx->cells[i]->cx, idx->cells[i]->cz));
        if (i + 1 != idx->cells.size()) {
            idx->cells[i] = std::move(idx->cells.back());
            idx->at[cellKey(idx->cells[i]->cx, idx->cells[i]->cz)] = i;
        }
        idx->cells.pop_back();
    }
    std::vector<int64_t> now;
    for (auto& [k, v] : fresh) now.push_back(k);
    if (now.empty()) groupCells_.erase(group);
    else groupCells_[group] = std::move(now);
    index_ = std::move(idx);
}

void Lights::setBudget(uint32_t maxPerFrame, float clusterDistance) {
    std::lock_guard<std::mutex> lock(mu_);
    maxPerFrame_ = std::min(maxPerFrame, 65536u);
    clusterDistance_ = std::max(0.0f, clusterDistance);
}

size_t Lights::total() const {
    std::lock_guard<std::mutex> lock(mu_);
    return index_ ? index_->total : 0;
}

uint32_t Lights::frame(const LightView& v, std::vector<ActorGpu>& out) {
    std::shared_ptr<const LightIndex> idx;
    uint32_t budget;
    float clusterDist;
    {
        std::lock_guard<std::mutex> lock(mu_);
        idx = index_;
        budget = maxPerFrame_;
        clusterDist = clusterDistance_;
    }
    if (!idx || idx->cells.empty() || budget == 0 || v.width == 0 || v.height == 0) return 0;
    // what each kind shows now: town lights from dusk to dawn, spray only by day; fires and lava always
    bool nightLights = v.sunY < 0.14f, mist = v.sunY > -0.12f;
    struct Cand {
        const Rec* r;
        float score, dist, px, py, sigma, halo, reach;
        double rx, ry, rz;
    };
    std::vector<Cand> cands;
    cands.reserve(1024);
    const float* m = v.viewProj;
    float pa = std::max(v.pixelAngle, 1e-5f);
    for (const auto& cp : idx->cells) {
        const LightIndex::Cell& c = *cp;
        double x0 = double(c.cx) * CELL, z0 = double(c.cz) * CELL;
        double dx = std::max({x0 - v.cam[0], v.cam[0] - (x0 + CELL), 0.0}), dz = std::max({z0 - v.cam[2], v.cam[2] - (z0 + CELL), 0.0});
        double near = std::sqrt(dx * dx + dz * dz);
        if (near > v.maxDist) continue;
        double far = std::hypot(std::max(std::abs(x0 - v.cam[0]), std::abs(x0 + CELL - v.cam[0])),
                                std::max(std::abs(z0 - v.cam[2]), std::abs(z0 + CELL - v.cam[2])));
        if (far < v.nearEnd) continue;
        const std::vector<Rec>& list = near > clusterDist ? c.coarse : c.fine;
        for (const Rec& r : list) {
            if (r.kind == ATLAS_LIGHT_NIGHT && !nightLights) continue;
            if (r.kind == ATLAS_LIGHT_MIST && !mist) continue;
            double rx = r.x - v.cam[0], ry = r.y - v.cam[1], rz = r.z - v.cam[2];
            double h2 = rx * rx + rz * rz;
            if (v.planetRadius > 0) ry -= h2 / (2.0 * v.planetRadius);
            double dist = std::sqrt(h2 + ry * ry);
            if (dist < v.nearEnd + r.radius || dist > v.maxDist) continue;
            float X = float(rx), Y = float(ry), Z = float(rz);
            float cw = m[3] * X + m[7] * Y + m[11] * Z + m[15];
            if (cw <= 1e-4f) continue;
            float cxp = (m[0] * X + m[4] * Y + m[8] * Z + m[12]) / cw, cyp = (m[1] * X + m[5] * Y + m[9] * Z + m[13]) / cw;
            float px = (cxp * 0.5f + 0.5f) * float(v.width), py = (0.5f - cyp * 0.5f) * float(v.height);
            float d = float(dist);
            // the flame or lamp itself, not the whole light: about a third of its radius (at least half a pixel)
            float sigma = std::max(0.5f, 0.3f * r.radius / (d * pa));
            float T = std::exp(-d * v.hazeDensity);
            float halo = 1.5f + 8.0f * (1.0f - T);
            // its glow, and the ground and walls it lights (block light reaches 15 x intensity blocks)
            float lit = r.kind == ATLAS_LIGHT_MIST ? 0.0f
                      : (15.0f * std::min(r.intensity, 1.0f) + (r.kind == ATLAS_LIGHT_LAVA ? r.radius : 0.0f)) / (d * pa);
            float reach = std::min(96.0f, std::max(3.0f * sigma + 3.0f * halo, lit));
            if (px + reach < 0 || py + reach < 0 || px - reach > float(v.width) || py - reach > float(v.height)) continue;
            cands.push_back({&r, r.intensity * falloff(d), d, px, py, sigma, halo, reach, rx, ry, rz});
        }
    }
    if (cands.empty()) return 0;
    // brightest first; each screen tile takes at most LIGHTS_PER_TILE, so a near town's own lights are never crowded out
    // by the summed clusters of far ones elsewhere on screen; the budget bounds the lights kept in all
    std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.score > b.score; });
    uint32_t tilesX = (v.width + LIGHT_TILE - 1) / LIGHT_TILE, tilesY = (v.height + LIGHT_TILE - 1) / LIGHT_TILE;
    std::vector<std::vector<uint32_t>> lists(size_t(tilesX) * tilesY);
    std::vector<const Cand*> kept;
    kept.reserve(std::min<size_t>(cands.size(), budget));
    for (const Cand& c : cands) {
        if (kept.size() >= budget) break;
        int tx0 = std::max(0, int(std::floor((c.px - c.reach) / LIGHT_TILE))), tx1 = std::min(int(tilesX) - 1, int(std::floor((c.px + c.reach) / LIGHT_TILE)));
        int ty0 = std::max(0, int(std::floor((c.py - c.reach) / LIGHT_TILE))), ty1 = std::min(int(tilesY) - 1, int(std::floor((c.py + c.reach) / LIGHT_TILE)));
        // its own tile must have room (the halo's neighbours take it while they do)
        int ox = std::clamp(int(std::floor(c.px / LIGHT_TILE)), 0, int(tilesX) - 1), oy = std::clamp(int(std::floor(c.py / LIGHT_TILE)), 0, int(tilesY) - 1);
        if (lists[size_t(oy) * tilesX + ox].size() >= LIGHTS_PER_TILE) continue;
        uint32_t i = uint32_t(kept.size());
        kept.push_back(&c);
        for (int ty = ty0; ty <= ty1; ty++)
            for (int tx = tx0; tx <= tx1; tx++) {
                auto& l = lists[size_t(ty) * tilesX + tx];
                if (l.size() < LIGHTS_PER_TILE) l.push_back(i);
            }
    }
    size_t base = out.size();
    uint32_t n = uint32_t(kept.size());
    out.resize(base + n);
    for (uint32_t i = 0; i < n; i++) {
        const Cand& c = *kept[i];
        const Rec& r = *c.r;
        ActorGpu& g = out[base + i];
        // camera-relative to the integer camera block, as every position in the kernel
        g.posYaw[0] = float(c.rx + (v.cam[0] - double(v.camInt[0])));
        g.posYaw[1] = float(c.ry + (v.cam[1] - double(v.camInt[1])));
        g.posYaw[2] = float(c.rz + (v.cam[2] - double(v.camInt[2])));
        g.posYaw[3] = c.sigma;
        float k = 6.0f * r.intensity * falloff(c.dist);
        g.shape[0] = r.r * k;
        g.shape[1] = r.g * k;
        g.shape[2] = r.b * k;
        g.shape[3] = float(r.kind);
        g.motion[0] = c.dist;
        g.motion[1] = r.seed;
        g.motion[2] = c.halo;
        g.motion[3] = r.radius;
    }
    // tile table: 6 tiles per record, (start, count) each; then the index lists, 12 per record
    uint32_t tiles = tilesX * tilesY;
    std::vector<uint32_t> words(size_t((tiles + 5) / 6) * 12, 0), indices;
    for (uint32_t t = 0; t < tiles; t++) {
        words[size_t(t / 6) * 12 + (t % 6) * 2] = uint32_t(indices.size());
        words[size_t(t / 6) * 12 + (t % 6) * 2 + 1] = uint32_t(lists[t].size());
        indices.insert(indices.end(), lists[t].begin(), lists[t].end());
    }
    indices.resize((indices.size() + 11) / 12 * 12, 0);
    words.insert(words.end(), indices.begin(), indices.end());
    size_t recs = words.size() / 12;
    size_t at = out.size();
    out.resize(at + recs);
    std::memcpy(&out[at], words.data(), words.size() * 4);
    return n;
}

}  // namespace atlas
