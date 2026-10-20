#include "preview_source.hpp"
#include "parallel.hpp"

#include <atomic>
#include <thread>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace atlas {
namespace {

enum Ch { ELEV, COAST, RIV_D, RIV_HW, LAKE_SD, WATER, RIDGE, ROUGH, CRAG, DUNES, WET, ROAD, RAIN, HEAT };
enum Climate { MARITIME, SK_TEMPERATE, SK_COLD, FROZEN, DESERT, TROPICAL, TEMPERATE, ASH, SWAMP, JUNGLE, SAVANNA };
constexpr float NO_WATER = -1000.0f;
constexpr int SEA = 63;

// MapNoise (Java int arithmetic wraps; do the same with uint32).
int32_t hashI(int32_t x, int32_t z, int32_t seed) {
    uint32_t h = uint32_t(x) * 374761393u + uint32_t(z) * 668265263u + uint32_t(seed) * 1442695041u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return int32_t(h ^ (h >> 16));
}
double lattice(int x, int z, int seed) { return double(hashI(x, z, seed) & 0xFFFFFF) / double(0x7FFFFF) - 1.0; }
double value(double x, double z, double scale, int seed) {
    double fx = x / scale, fz = z / scale;
    int ix = int(std::floor(fx)), iz = int(std::floor(fz));
    double tx = fx - ix, tz = fz - iz;
    tx = tx * tx * (3 - 2 * tx);
    tz = tz * tz * (3 - 2 * tz);
    double a = lattice(ix, iz, seed), b = lattice(ix + 1, iz, seed);
    double c = lattice(ix, iz + 1, seed), d = lattice(ix + 1, iz + 1, seed);
    double ab = a + (b - a) * tx;
    return ab + (c + (d - c) * tx - ab) * tz;
}
double fbm(double x, double z, double scale, int oct, int seed) {
    double tot = 0, amp = 1, norm = 0;
    for (int o = 0; o < oct; o++) {
        tot += value(x, z, scale, seed + o * 31) * amp;
        norm += amp;
        amp *= 0.5;
        scale *= 0.5;
    }
    return tot / norm;
}
double smoothstep(double e0, double e1, double x) {
    double t = std::clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3 - 2 * t);
}

AtlasMaterial mat(float r, float g, float b, float rough, uint32_t cls, float emis = 0) {
    // authored in sRGB-ish block colours as seen from afar; stored linear
    auto lin = [](float c) { return std::pow(c, 2.2f); };
    return AtlasMaterial{{lin(r), lin(g), lin(b)}, rough, emis, cls};
}

}  // namespace

std::vector<AtlasMaterial> previewPalette() {
    std::vector<AtlasMaterial> p(M_COUNT - 1);
    auto set = [&](PreviewMat id, AtlasMaterial m) { p[id - 1] = m; };
    set(M_STONE, mat(0.47f, 0.47f, 0.47f, 0.9f, ATLAS_CLASS_TERRAIN));
    set(M_GRASS, mat(0.36f, 0.52f, 0.24f, 0.9f, ATLAS_CLASS_TERRAIN));
    set(M_DIRT, mat(0.45f, 0.33f, 0.23f, 0.9f, ATLAS_CLASS_TERRAIN));
    set(M_SAND, mat(0.84f, 0.79f, 0.6f, 0.8f, ATLAS_CLASS_TERRAIN));
    set(M_SNOW, mat(0.93f, 0.95f, 0.98f, 0.6f, ATLAS_CLASS_TERRAIN));
    set(M_WATER, mat(0.16f, 0.27f, 0.42f, 0.05f, ATLAS_CLASS_WATER));
    set(M_GRAVEL, mat(0.55f, 0.52f, 0.5f, 0.9f, ATLAS_CLASS_TERRAIN));
    set(M_FOLIAGE, mat(0.22f, 0.36f, 0.14f, 0.95f, ATLAS_CLASS_FOLIAGE));
    set(M_ASH, mat(0.33f, 0.28f, 0.25f, 0.95f, ATLAS_CLASS_TERRAIN));
    set(M_SANDSTONE, mat(0.78f, 0.66f, 0.45f, 0.85f, ATLAS_CLASS_TERRAIN));
    set(M_MUD, mat(0.33f, 0.3f, 0.2f, 0.7f, ATLAS_CLASS_TERRAIN));
    set(M_DRYGRASS, mat(0.62f, 0.58f, 0.32f, 0.9f, ATLAS_CLASS_TERRAIN));
    // what the near field's spruce crowns average to: the leaves' grey texture (0x838383) x vanilla's fixed spruce tint
    // (0x619961)
    set(M_CONIFER, mat(0.195f, 0.308f, 0.195f, 0.95f, ATLAS_CLASS_FOLIAGE));
    set(M_JUNGLE, mat(0.15f, 0.36f, 0.1f, 0.95f, ATLAS_CLASS_FOLIAGE));
    set(M_REDSAND, mat(0.72f, 0.45f, 0.28f, 0.85f, ATLAS_CLASS_TERRAIN));
    set(M_ICE, mat(0.66f, 0.78f, 0.9f, 0.2f, ATLAS_CLASS_TERRAIN));
    // grass by climate, roughly how the holds and provinces read from a distance
    set(M_TUNDRA, mat(0.51f, 0.52f, 0.34f, 0.9f, ATLAS_CLASS_TERRAIN));      // Whiterun's golden tundra
    set(M_COLDGRASS, mat(0.43f, 0.48f, 0.37f, 0.9f, ATLAS_CLASS_TERRAIN));   // the Pale, Eastmarch, Haafingar
    set(M_LUSHGRASS, mat(0.33f, 0.47f, 0.22f, 0.9f, ATLAS_CLASS_TERRAIN));   // Cyrodiil, High Rock, the jungles
    set(M_MARSHGRASS, mat(0.36f, 0.40f, 0.24f, 0.85f, ATLAS_CLASS_TERRAIN)); // Black Marsh, Hjaalmarch
    // flowing water: the water colour (the kernel adds the white water of fast runs and rapids from the flow)
    for (int k = 0; k < FLOW_DIRS * FLOW_SPEEDS; k++) set(PreviewMat(M_FLOW0 + k), mat(0.16f, 0.27f, 0.42f, 0.05f, ATLAS_CLASS_WATER));
    // snowy conifer crowns as the near field shows them: dark green spruce with the snowy species' accent leaves on top
    // of each layer (NirnCraft's snowy_spruce_leaves, their own mean 0x5C7064), half the crown's exposed leaves in an
    // exported snowy pine, mixed in linear light; not a white crown (it read whitish where the near spruces are green)
    set(M_SNOWCANOPY, mat(0.292f, 0.381f, 0.313f, 0.9f, ATLAS_CLASS_FOLIAGE));
    return p;
}

void applyWaterFlow(std::vector<MaterialGpu>& palette) {
    for (int k = 0; k < FLOW_DIRS * FLOW_SPEEDS; k++) {
        size_t id = size_t(M_FLOW0 + k);
        if (id >= palette.size() || palette[id].emisClass[1] != float(ATLAS_CLASS_WATER)) continue;
        palette[id].emisClass[2] = float(k / FLOW_SPEEDS) * (6.2831853f / FLOW_DIRS);  // heading: atan2(z, x)
        palette[id].emisClass[3] = float(k % FLOW_SPEEDS + 1);                        // 1 calm, 2 fast, 3 rapids
    }
}

bool PreviewSource::load(const std::string& path, std::string& err) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) {
        err = "cannot open " + path;
        return false;
    }
    char magic[4];
    uint32_t hdr[7];
    bool ok = std::fread(magic, 1, 4, f) == 4 && std::memcmp(magic, "AETR", 4) == 0 && std::fread(hdr, 4, 7, f) == 7;
    if (ok && hdr[0] != 2) ok = false;
    if (ok) {
        w_ = int(hdr[1]);
        h_ = int(hdr[2]);
        ch_ = int(hdr[3]);
        minX_ = int32_t(hdr[4]);
        minZ_ = int32_t(hdr[5]);
        cellSize_ = int(hdr[6]);
        data_.resize(size_t(w_) * h_ * ch_);
        regions_.resize(size_t(w_) * h_);
        ok = std::fread(data_.data(), 4, data_.size(), f) == data_.size() &&
             std::fread(regions_.data(), 1, regions_.size(), f) == regions_.size();
        uint32_t n = 0;
        ok = ok && std::fread(&n, 4, 1, f) == 1 && n < 4096;
        if (ok) {
            climate_.resize(n);
            ok = std::fread(climate_.data(), 1, n, f) == n;
        }
    }
    std::fclose(f);
    if (!ok || ch_ < 14) {
        err = "bad raster file " + path + " (rerun native/tools/export_raster.py)";
        return false;
    }
    return true;
}

float PreviewSource::cell(int cx, int cz, int ch) const {
    cx = std::clamp(cx, 0, w_ - 1);
    cz = std::clamp(cz, 0, h_ - 1);
    return data_[(size_t(cz) * w_ + cx) * ch_ + ch];
}

void PreviewSource::sample(double x, double z, Sample& s) const {
    double fx = (x - minX_) / cellSize_ - 0.5, fz = (z - minZ_) / cellSize_ - 0.5;
    int ix = int(std::floor(fx)), iz = int(std::floor(fz));
    double tx = fx - ix, tz = fz - iz;
    bool outside = fx < 0 || fz < 0 || fx > w_ - 1 || fz > h_ - 1;
    // Catmull-Rom elevation and slope (MapRaster.elevation)
    double tx2 = tx * tx, tx3 = tx2 * tx, tz2 = tz * tz, tz3 = tz2 * tz;
    double ax[4] = {-0.5 * tx3 + tx2 - 0.5 * tx, 1.5 * tx3 - 2.5 * tx2 + 1, -1.5 * tx3 + 2 * tx2 + 0.5 * tx, 0.5 * tx3 - 0.5 * tx2};
    double bx[4] = {-1.5 * tx2 + 2 * tx - 0.5, 4.5 * tx2 - 5 * tx, -4.5 * tx2 + 4 * tx + 0.5, 1.5 * tx2 - tx};
    double az[4] = {-0.5 * tz3 + tz2 - 0.5 * tz, 1.5 * tz3 - 2.5 * tz2 + 1, -1.5 * tz3 + 2 * tz2 + 0.5 * tz, 0.5 * tz3 - 0.5 * tz2};
    double bz[4] = {-1.5 * tz2 + 2 * tz - 0.5, 4.5 * tz2 - 5 * tz, -4.5 * tz2 + 4 * tz + 0.5, 1.5 * tz2 - tz};
    double e = 0, ex = 0, ez = 0;
    for (int j = 0; j < 4; j++) {
        double r = 0, rx = 0;
        for (int i = 0; i < 4; i++) {
            double v = cell(ix - 1 + i, iz - 1 + j, ELEV);
            r += ax[i] * v;
            rx += bx[i] * v;
        }
        e += az[j] * r;
        ex += az[j] * rx;
        ez += bz[j] * r;
    }
    s.elev = float(e);
    s.slope = float(std::sqrt(ex * ex + ez * ez) / cellSize_);
    float wx = float(std::clamp(tx, 0.0, 1.0)), wz = float(std::clamp(tz, 0.0, 1.0));
    float w00 = (1 - wx) * (1 - wz), w10 = wx * (1 - wz), w01 = (1 - wx) * wz, w11 = wx * wz;
    auto lerp = [&](int ch) {
        return cell(ix, iz, ch) * w00 + cell(ix + 1, iz, ch) * w10 + cell(ix, iz + 1, ch) * w01 + cell(ix + 1, iz + 1, ch) * w11;
    };
    s.coast = lerp(COAST);
    s.rivD = lerp(RIV_D);
    s.lakeSD = lerp(LAKE_SD);
    s.ridge = lerp(RIDGE);
    s.rough = lerp(ROUGH);
    s.crag = lerp(CRAG);
    s.dunes = lerp(DUNES);
    s.wet = lerp(WET);
    s.road = lerp(ROAD);
    s.rain = lerp(RAIN);
    s.heat = lerp(HEAT);
    float ws = 0, wl = 0, hw = 0;
    int cxs[4] = {ix, ix + 1, ix, ix + 1}, czs[4] = {iz, iz, iz + 1, iz + 1};
    float ww[4] = {w00, w10, w01, w11};
    for (int k = 0; k < 4; k++) {
        float wv = cell(cxs[k], czs[k], WATER);
        if (wv > NO_WATER) {
            ws += ww[k];
            wl += ww[k] * wv;
            hw += ww[k] * cell(cxs[k], czs[k], RIV_HW);
        }
    }
    s.water = ws > 1e-4f ? wl / ws : NAN;
    s.rivHW = ws > 1e-4f ? hw / ws : 0.0f;
    int cx = std::clamp(int(std::floor((x - minX_) / cellSize_)), 0, w_ - 1);
    int cz = std::clamp(int(std::floor((z - minZ_) / cellSize_)), 0, h_ - 1);
    s.region = regions_[size_t(cz) * w_ + cx];
    s.climate = s.region < int(climate_.size()) ? climate_[s.region] : TEMPERATE;
    if (outside) {
        s.elev = 20.0f;
        s.coast = -2000.0f;
        s.water = NAN;
        s.region = 0;
        s.climate = MARITIME;
    }
}

float PreviewSource::column(double x, double z, const Sample& s) const {
    double h = s.elev;
    double hills = fbm(x, z, 220, 4, 7), detail = fbm(x, z, 46, 3, 9);
    if (s.coast < 0) return float(std::min(h + (hills * 5 + detail * 1.5) * (0.4 + 0.6 * smoothstep(-600, 0, s.coast)), double(SEA - 1)));
    double amp = smoothstep(0, 56, s.coast) * (1 - 0.75 * s.road);
    bool water = !std::isnan(s.water);
    if (water) {
        double reach = 30 + 40 * std::clamp((s.rivHW - 1) / 3.0, 0.0, 1.0);
        amp *= std::min(smoothstep(s.rivHW + 4, s.rivHW + reach, s.rivD), smoothstep(0, 70, -s.lakeSD));
    }
    double smooth = 1 / (1 + 6 * s.slope * s.slope);
    h += amp * s.rough * (hills * 22 + detail * 6.5 * smooth);
    if (s.ridge > 0.02f) {
        double r1 = 1 - std::abs(value(x, z, 260, 11)), r2 = 1 - std::abs(value(x * 2.1, z * 2.1, 90, 12));
        double high = 1 + 0.6 * smoothstep(150, 300, h);
        h += amp * std::pow(s.ridge, 1.3) * high * (r1 * r1 * 36 + r1 * r2 * r2 * 12 * (0.4 + 0.6 * smooth) - 15);
    }
    if (s.crag > 0.05f) {
        double steep = smoothstep(0.25, 0.85, s.slope);
        double w = s.crag * 0.85 * amp * (0.15 + 0.85 * steep);
        double step = 8 + 4 * hills, t = h / step, fl = std::floor(t);
        h += ((fl + smoothstep(0.5, 0.9, t - fl)) * step - h) * w;
    }
    if (s.dunes > 0.02f) {
        double crest = 1 - std::abs(value(x * 0.8 + z * 0.35, z * 1.7 - x * 0.2, 70, 13));
        h += amp * s.dunes * (crest * crest * 11 - 2);
    }
    if (s.wet > 0.05f && h < SEA + 10) h += (SEA + 0.4 + detail * 1.8 + hills * 0.8 - h) * s.wet * 0.6;
    if (water) {
        double wl = s.water;
        if (s.lakeSD > -40) {
            if (s.lakeSD > 0) h = std::min(h, wl - std::min(12.0, 1.2 + s.lakeSD * 0.25));
            else h = std::max(h, wl + 1 - s.lakeSD * 0.04);
        }
        double hw = s.rivHW;
        if (hw > 0 && s.rivD < hw) {
            double depth = std::clamp(hw * 0.3, hw < 4 ? 1.0 + 0.25 * hw : 1.5, 5.0);
            double u = s.rivD / hw;
            h = std::min(h, wl - 0.6 - depth * (1 - u * u));
        }
    }
    return float(std::min(h, 568.0));
}

float PreviewSource::height(double x, double z) const {
    Sample s;
    sample(x, z, s);
    return column(x, z, s);
}

static std::unique_ptr<Tile> blankTile(int lod, int tx, int tz) {
    auto t = std::make_unique<Tile>();
    t->lod = lod;
    t->tx = tx;
    t->tz = tz;
    size_t n = size_t(TILE) * TILE;
    t->surface.resize(n);
    t->water.resize(n);
    t->canopy.assign(n, 0.0f);
    t->top.resize(n);
    t->fill.resize(n);
    t->canopyMat.assign(n, 0);
    return t;
}

std::unique_ptr<Tile> PreviewSource::makeTile(int lod, int tx, int tz) const {
    auto t = blankTile(lod, tx, tz);
    double s = double(1 << lod);
    double x0 = double(tx) * TILE * s, z0 = double(tz) * TILE * s;
    Sample sm;
    for (int j = 0; j < TILE; j++) {
        for (int i = 0; i < TILE; i++) {
            size_t c = size_t(j) * TILE + i;
            double x = x0 + (i + 0.5) * s, z = z0 + (j + 0.5) * s;
            sample(x, z, sm);
            float h = column(x, z, sm);
            float wl = NAN;
            if (sm.coast < 0 || h < SEA - 0.5f) wl = float(SEA);  // the sea fills blocks up to y 62, surface at 63
            if (!std::isnan(sm.water) && (sm.lakeSD > 0 || sm.rivD < sm.rivHW)) wl = std::max(std::isnan(wl) ? -1e9f : wl, sm.water);
            t->surface[c] = h;
            t->water[c] = wl;
        }
    }
    dress(*t, Overrides{});
    return t;
}

std::unique_ptr<Tile> PreviewSource::dressTile(int lod, int tx, int tz, const float* surface, const float* water,
                                              const uint16_t* grassMat, const uint16_t* foliageMat) const {
    auto t = blankTile(lod, tx, tz);
    std::copy(surface, surface + t->surface.size(), t->surface.begin());
    std::copy(water, water + t->water.size(), t->water.begin());
    Overrides o;
    o.grassMat = grassMat;
    o.foliageMat = foliageMat;
    dress(*t, o);
    return t;
}

std::unique_ptr<Tile> PreviewSource::dressTile(int lod, int tx, int tz, const float* surface, const float* water,
                                              const Overrides& o) const {
    auto t = blankTile(lod, tx, tz);
    std::copy(surface, surface + t->surface.size(), t->surface.begin());
    std::copy(water, water + t->water.size(), t->water.begin());
    dress(*t, o);
    return t;
}

// The flowing water over a river column: along the channel (perpendicular to the raster's distance-to-river field),
// downhill by the raster's water level 24 blocks either way, its speed class by that slope (calm, fast, rapids);
// 0 for still water (the sea, lakes, marsh pools).
uint16_t PreviewSource::flowMaterial(double x, double z, const Sample& s, float level, const Tile& t, int i, int j) const {
    if (s.coast < 0 || s.lakeSD > 0 || !(s.rivHW > 0) || s.rivD > s.rivHW + 3) return 0;
    Sample a, b;
    sample(x + 6, z, a);
    sample(x - 6, z, b);
    double gx = a.rivD - b.rivD;
    sample(x, z + 6, a);
    sample(x, z - 6, b);
    double gz = a.rivD - b.rivD;
    double gl = std::sqrt(gx * gx + gz * gz);
    if (gl < 1e-3) return 0;
    double tx = -gz / gl, tz = gx / gl;  // along the channel
    sample(x + tx * 24, z + tz * 24, a);
    sample(x - tx * 24, z - tz * 24, b);
    double ahead = std::isnan(a.water) ? level : a.water, behind = std::isnan(b.water) ? level : b.water;
    double baseline = 48.0;  // blocks between the two samples
    // the tile's own levels (exact, falls included) where the raster is flat
    if (std::abs(ahead - behind) < 0.25) {
        int s2 = 3, ia = std::clamp(i + int(std::lround(tx * s2)), 0, TILE - 1), ja = std::clamp(j + int(std::lround(tz * s2)), 0, TILE - 1);
        int ib = std::clamp(i - int(std::lround(tx * s2)), 0, TILE - 1), jb = std::clamp(j - int(std::lround(tz * s2)), 0, TILE - 1);
        float wa = t.water[size_t(ja) * TILE + ia], wb = t.water[size_t(jb) * TILE + ib];
        if (!std::isnan(wa) && !std::isnan(wb)) {
            ahead = wa;
            behind = wb;
            baseline = 2.0 * s2 * double(1 << t.lod);
        }
    }
    if (ahead > behind) {
        tx = -tx;
        tz = -tz;
    }
    double slope = std::abs(ahead - behind) / baseline;
    int speed = slope < 0.015 && s.rivHW >= 2.0f ? 0 : slope < 0.05 ? 1 : 2;
    int dir = int(std::lround(std::atan2(tz, tx) / (6.283185307 / FLOW_DIRS))) & (FLOW_DIRS - 1);
    return uint16_t(M_FLOW0 + dir * FLOW_SPEEDS + speed);
}

// Surface materials and forest canopy for a tile whose geometry is filled in: by climate, altitude, slope and the
// shore (a stand-in for the biome surface rules). Slope is the steeper of the map's macro gradient and the tile's own
// (central differences between neighbouring columns), so cliffs, terraces and ridged peaks in the geometry turn to
// rock and shed their trees even where the macro raster is gentle.
void PreviewSource::dress(Tile& tile, const Overrides& o) const {
    bool wet = false;
    for (float w : tile.water) wet |= !std::isnan(w);
    if (wet) tile.waterMat.assign(tile.water.size(), 0);
    // rows in parallel (a tile is ~20 ms of raster sampling on one core); each row writes only its own columns
    unsigned n = std::max(1u, std::min(8u, std::thread::hardware_concurrency() / 2));
    std::atomic<int> nextRow{0};
    auto rows = [&] {
        for (int j; (j = nextRow.fetch_add(4)) < TILE;) dressRows(tile, j, std::min(j + 4, int(TILE)), o);
    };
    parallelRun(rows, n);
    if (wet && std::all_of(tile.waterMat.begin(), tile.waterMat.end(), [](uint16_t m) { return m == 0; })) tile.waterMat.clear();
}

void PreviewSource::dressRows(Tile& tile, int jBegin, int jEnd, const Overrides& o) const {
    const uint16_t* grassMat = o.grassMat;
    const uint16_t* foliageMat = o.foliageMat;
    Tile* t = &tile;
    double s = double(1 << t->lod);
    double x0 = double(t->tx) * TILE * s, z0 = double(t->tz) * TILE * s;
    const float* hs = t->surface.data();
    Sample sm;
    for (int j = jBegin; j < jEnd; j++) {
        for (int i = 0; i < TILE; i++) {
            size_t c = size_t(j) * TILE + i;
            double x = x0 + (i + 0.5) * s, z = z0 + (j + 0.5) * s;
            sample(x, z, sm);
            float h = hs[c], wl = t->water[c];
            if (!std::isnan(wl) && wl <= h) wl = NAN;
            t->water[c] = wl;
            int i0 = std::max(i - 1, 0), i1 = std::min(i + 1, TILE - 1), j0 = std::max(j - 1, 0), j1 = std::min(j + 1, TILE - 1);
            float gx = (hs[size_t(j) * TILE + i1] - hs[size_t(j) * TILE + i0]) / float((i1 - i0) * s);
            float gz = (hs[size_t(j1) * TILE + i] - hs[size_t(j0) * TILE + i]) / float((j1 - j0) * s);
            float slope = std::max(sm.slope, std::sqrt(gx * gx + gz * gz));

            int cl = sm.climate;
            float snowline = cl == FROZEN ? -100.0f : cl == SK_COLD ? 190.0f : cl == SK_TEMPERATE ? 260.0f
                           : (cl == TEMPERATE || cl == MARITIME) ? 330.0f : cl == ASH ? 400.0f : 440.0f;
            // snow lies lower on shaded faces (downhill towards -Z, north) and higher on sunny ones, not along a
            // level line; the edge itself is ragged
            float g = std::sqrt(gx * gx + gz * gz);
            float northness = g > 1e-3f ? gz / g * float(smoothstep(0.08, 0.5, g)) : 0.0f;
            snowline += float(fbm(x, z, 120, 2, 21) * 30.0) - 45.0f * northness;
            float snowEdge = snowline + float(fbm(x, z, 37, 2, 23) * 12.0);
            uint16_t top = M_GRASS, fill = M_DIRT;
            switch (cl) {
                case SK_TEMPERATE: top = M_TUNDRA; break;
                case SK_COLD: top = M_COLDGRASS; break;
                case TEMPERATE: case MARITIME: case TROPICAL: case JUNGLE: top = M_LUSHGRASS; break;
                case DESERT: top = sm.dunes > 0.2f ? M_SAND : M_SANDSTONE; fill = M_SANDSTONE; break;
                case SAVANNA: top = M_DRYGRASS; break;
                case ASH: top = M_ASH; fill = M_ASH; break;
                case SWAMP: top = sm.wet > 0.3f ? M_MUD : M_MARSHGRASS; break;
                case FROZEN: top = M_SNOW; fill = M_STONE; break;
                default: break;
            }
            if (sm.heat > 0.6f) top = M_ASH;
            if (h < SEA + 2.5f && sm.coast < 40 && cl != SWAMP) top = fill = M_SAND;
            if (slope > 0.9f) top = M_STONE;
            if (h > snowEdge && slope < 1.4f && sm.heat < 0.45f) top = M_SNOW;  // (no snow on hot ground: Red Mountain)
            if (cl == FROZEN && sm.coast < 0) top = M_ICE;
            if (!std::isnan(wl) && wl - h > 1.5f) top = sm.coast < 0 ? M_SAND : M_GRAVEL;
            // the biome's own grass colour where the game would show a tinted grass block (the near field's tint)
            if (grassMat && grassMat[c] && (top == M_GRASS || top == M_LUSHGRASS || top == M_COLDGRASS || top == M_MARSHGRASS ||
                                            top == M_DRYGRASS || top == M_TUNDRA))
                top = grassMat[c];
            uint8_t flags = o.flags ? o.flags[c] : 0;
            // the world's own surface blocks where the caller knows them (its material rules, snow and ice)
            if (o.topMat && o.topMat[c]) top = o.topMat[c];
            if (o.fillMat && o.fillMat[c]) fill = o.fillMat[c];
            t->top[c] = top;
            t->fill[c] = fill;
            if (!std::isnan(wl)) {
                uint16_t wm = (flags & ATLAS_COLUMN_FROZEN) ? uint16_t(M_ICE) : flowMaterial(x, z, sm, wl, *t, i, j);
                if (wm) t->waterMat[c] = wm;  // (allocated by dress before the rows run)
            }

            // forests: canopy lumps on gentle ground below the tree line, by climate
            bool dry = cl == DESERT || cl == FROZEN || cl == ASH;
            float treeLine = snowline - 40.0f;
            if (!(flags & ATLAS_COLUMN_NO_CANOPY) && !dry && std::isnan(wl) && h < treeLine && slope < 0.8f
                && sm.road < 0.3f && top != M_SAND) {
                double density = cl == JUNGLE ? 0.75 : cl == SAVANNA ? 0.12 : cl == SWAMP ? 0.45 : 0.42;
                density += 0.35 * sm.rain;
                double f = fbm(x, z, 300, 3, 31) * 0.5 + 0.5;
                double clump = value(x, z, 9, 33) * 0.5 + 0.5;
                double inside = f + 0.25 * clump - (1.15 - density);  // > 0 in the forest
                if (inside > 0) {
                    bool conifer = cl == SK_COLD || cl == SK_TEMPERATE || h > treeLine - 80;
                    float base = cl == JUNGLE ? 16.0f : conifer ? 12.0f : 10.0f;
                    // the canopy thins out towards the forest's edge instead of ending in a wall
                    float edge = float(smoothstep(0.0, 0.12, inside));
                    float ht = 0.0f;
                    if (s <= 2.0) {
                        // fine LODs: single crowns on a jittered 7-block grid (domes, or cones for conifers), ground
                        // showing between them
                        int gx = int(std::floor(x / 7.0)), gz = int(std::floor(z / 7.0));
                        float best = 1e9f, treeH = 0;
                        for (int dz = -1; dz <= 1; dz++)
                            for (int dx = -1; dx <= 1; dx++) {
                                uint32_t hh = uint32_t(hashI(gx + dx, gz + dz, 71));
                                double tcx = (gx + dx + 0.15 + 0.7 * double(hh & 1023) / 1023.0) * 7.0;
                                double tcz = (gz + dz + 0.15 + 0.7 * double((hh >> 10) & 1023) / 1023.0) * 7.0;
                                float r = 2.2f + 1.6f * float((hh >> 20) & 255) / 255.0f;
                                float dd = float(std::hypot(x - tcx, z - tcz)) / r;
                                if (dd < best) {
                                    best = dd;

                                    treeH = base + 5.0f * float((hh >> 28) & 15) / 15.0f;
                                }
                            }
                        if (best < 1.0f) ht = treeH * (conifer ? 0.45f + 0.55f * (1.0f - best)
                                                              : 0.55f + 0.45f * std::sqrt(1.0f - best * best));
                    } else {
                        ht = base + 5.0f * float(clump);
                    }
                    ht *= edge;
                    if (ht >= 1.0f) {
                        t->canopy[c] = ht;
                        t->canopyMat[c] = conifer ? M_CONIFER : cl == JUNGLE || cl == TROPICAL ? M_JUNGLE : M_FOLIAGE;
                        if (!conifer && foliageMat && foliageMat[c]) t->canopyMat[c] = foliageMat[c];  // biome foliage
                        if (flags & ATLAS_COLUMN_SNOW) t->canopyMat[c] = M_SNOWCANOPY;
                    }
                }
            }
        }
    }
}

}  // namespace atlas
