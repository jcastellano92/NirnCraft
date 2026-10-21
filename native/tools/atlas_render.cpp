// Offline far-field renderer: preview terrain from the Tamriel raster, rendered through atlas_core.
//   atlas_render raster=native/data/tamriel_raster.bin out=shot.png x=0 z=0 above=40 yaw=0 pitch=-4 fov=70
//                 w=1280 h=720 backend=cpu|vulkan sun=35,120 radius=6371000 near=0 far=40000 flags=shadows,atmos,clouds
//                 weather=off|clear|fair|overcast|storm stormx= stormz= time=<seconds>  (off = clear: no cover,
//                 the game's weather-off switch) eclipse=x,y,z,r[,..] | umbra=R,L,off,az eclipsekm=0.1 (an eclipse's
//                 shadow: atlas_set_eclipse)
// yaw: degrees, 0 = north (-Z), 90 = east (+X). sun=elevation,azimuth degrees (azimuth like yaw).
// Also writes <out>_depth.png (log depth) and <out>_lod.png when lodpass=1, and prints JSON stats on stdout.
//
// Real data (the in-game exports, docs/ATLAS-LOD.md "The renderer's groups"): blocks=<dir>/blocks.bin
// volume=<dir>/x.als|.als2|.alv|.alv2 puts the export's sections up as the game does (materials, shapes, sections;
// keep=all keeps every block with a shape, not only what the game's far field keeps) over the preview terrain, the
// column tiles under it derived from the sections. groups=1 traces the content-shaped groups (ATLAS_FRAME_GROUPS).
// camera=mc eye=x,y,z yaw= pitch= fov= takes a live capture's camera (Minecraft conventions: yaw 0 = south, pitch > 0
// looks down), as the harness passes it. shade=mc outputs what Minecraft's vanilla view draws for the surfaces hit:
// the texel's colour times the fixed face shade (down 0.5, up 1, north / south 0.8, west / east 0.6), the sky in
// atlas_truth's colour, for comparisons with atlas_truth. With an export it also writes <out>_mask.png (where the frame
// shows exported chunks) and <out>_depth.png (1/16 blocks in 24 bits, sky all ones: atlas_truth's encodings).
// truth=<atlas_truth.exe> quads= sprites= truthvolume= renders atlas_truth's frame of the same camera and reports
// vsTruth: CIE76 dE (tools/harness/results.py's metric) where the truth shows exported blocks, by distance band, and
// writes <out>_truth.png and <out>_vs_truth.png (truth | this | dE). frames=N renders N times and reports the fastest.
// sky=RRGGBB: the background of shade=mc (atlas_truth's flat sky by default). shade=pack exposure=<log2> nv=<0.7>
// skyhdr=r,g,b: atlas_core's own lit frame (its sun / moon light, shadows, sky ambient, AO, far lights) through the
// shader pack's tonemap (pack_look.hpp: night vision, exposure, ACES, sRGB) over a flat HDR sky, as atlas_truth
// lighting=realistic draws it (the element tier's far-lit). nearlight=1 (with shade=pack): every surface the frame hits
// lit as the near pass's pixels are (ATLAS_FRAME_NEAR_LIGHT on the frame's own depth and normals, sky light 15, block
// light 0: albedo x light x T + inscatter) instead of by the far field's light. lights=1: the export's emitters (light
// 8 and up, inside
// box=) as far lights, as the game registers a real chunk's lights (AtlasEditsClient.lights: fires, intensity
// level / 15).
//
// The export's sections reach the core as the game's server sends them (server=1, the default; AtlasSections.push):
// those of the 256-block tiles within RADIUS_TILES (4) of the camera's tile, each once, sent again after its tile has
// left that window and come back; the core keeps them as it does in game (World::sectionKeep: windowRadius + 3 regions)
// and draws what it dropped from its coarse levels of real content. server=0 uploads them all at once and keeps them
// however far the camera goes (ATLAS_SECTION_KEEP=64: the old element tier, which no game reproduces).
// synth=R (no export) builds sections over the preview terrain instead (stone, dirt, grass, water, some ore pockets),
// sent farthest first (synthorder=far|near|random); seqgrid=1 adds the grid's residency to every seq frame's line.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include "atlas/atlas.h"
#include "atlas_export.hpp"
#include "camera.hpp"
#include "air.hpp"
#include "pack_look.hpp"
#include "png.hpp"

namespace {

constexpr double PI = 3.14159265358979323846;

std::map<std::string, std::string> parse(int argc, char** argv) {
    std::map<std::string, std::string> m;
    for (int i = 1; i < argc; i++) {
        const char* e = std::strchr(argv[i], '=');
        if (e) m[std::string(argv[i], size_t(e - argv[i]))] = e + 1;
    }
    return m;
}

double num(const std::map<std::string, std::string>& m, const char* k, double d) {
    auto it = m.find(k);
    return it == m.end() ? d : std::atof(it->second.c_str());
}

std::string str(const std::map<std::string, std::string>& m, const char* k, const char* d) {
    auto it = m.find(k);
    return it == m.end() ? d : it->second;
}

// Synthetic weather in WeatherFieldTexture's layout (harness only; in game the fields come from WeatherPhysics).
double vnoise(double x, double z, int seed) {
    auto h = [&](int ix, int iz) {
        uint32_t v = uint32_t(ix) * 374761393u + uint32_t(iz) * 668265263u + uint32_t(seed) * 1442695041u;
        v = (v ^ (v >> 13)) * 1274126177u;
        return double((v ^ (v >> 16)) & 0xFFFFFF) / double(0x7FFFFF) - 1.0;
    };
    int ix = int(std::floor(x)), iz = int(std::floor(z));
    double tx = x - ix, tz = z - iz;
    tx = tx * tx * (3 - 2 * tx);
    tz = tz * tz * (3 - 2 * tz);
    double a = h(ix, iz), b = h(ix + 1, iz), c = h(ix, iz + 1), d = h(ix + 1, iz + 1);
    return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
}

uint32_t rgba(double r, double g, double b, double a) {
    auto q = [](double v) { return uint32_t(std::fmax(0.0, std::fmin(1.0, v)) * 255.0 + 0.5); };
    return q(r) | q(g) << 8 | q(b) << 16 | q(a) << 24;
}

void syntheticWeather(AtlasContext* ctx, const std::string& preset, double camX, double camZ, double stormX, double stormZ) {
    std::vector<uint32_t> nf(128 * 128), nd(128 * 128), ff(128 * 128), fd(128 * 128);
    const double windAngle = std::atan2(0.6, 0.8) / (2 * PI);
    for (int layer = 0; layer < 2; layer++) {
        double cell = layer == 0 ? 64.0 : 512.0;
        int camCell = int(std::floor((layer == 0 ? camX : camX) / cell)), camCellZ = int(std::floor(camZ / cell));
        for (int j = 0; j < 128; j++)
            for (int i = 0; i < 128; i++) {
                // the world cell in the window around the camera that maps to texel (i, j) (floorMod addressing)
                int cx = camCell - 64 + (((i - (camCell - 64)) % 128) + 128) % 128;
                int cz = camCellZ - 64 + (((j - (camCellZ - 64)) % 128) + 128) % 128;
                double x = (cx + 0.5) * cell, z = (cz + 0.5) * cell;
                double n = 0.6 * vnoise(x / 5200, z / 5200, 3) + 0.4 * vnoise(x / 1900, z / 1900, 4);
                double cov = 0, tower = 0, curtain = 0;
                if (preset == "fair") { cov = 0.34 + 0.26 * n; tower = 0.04; }
                else if (preset == "overcast") { cov = 0.86 + 0.08 * n; tower = 0.08; curtain = 0.25; }
                else if (preset == "storm") {
                    double dx = x - stormX, dz = z - stormZ, s = std::exp(-(dx * dx + dz * dz) / (3600.0 * 3600.0));
                    cov = std::fmax(0.42 + 0.22 * n, 0.98 * s);
                    tower = 0.92 * s;
                    curtain = 0.85 * s * s;
                }
                double ground = atlas_preview_height(ctx, x, z);
                double base = 232 + 22 * vnoise(x / 3000, z / 3000, 5);
                uint32_t field = rgba(cov, tower, curtain, 0.02);
                uint32_t dyn = rgba(base / 1024.0, std::fmax(ground, 63.0) / 1024.0, windAngle, 0.0);
                (layer == 0 ? nf : ff)[size_t(j) * 128 + i] = field;
                (layer == 0 ? nd : fd)[size_t(j) * 128 + i] = dyn;
            }
    }
    AtlasWeather w{nf.data(), nd.data(), ff.data(), fd.data(), preset == "storm" ? 0.3f : 0.0f};
    atlas_set_weather(ctx, &w);
}

// ATLAS_ABLATE for the frames that follow (read by the core every frame)
void setAblateEnv(uint32_t v) {
    std::string s = std::to_string(v);
#ifdef _WIN32
    _putenv_s("ATLAS_ABLATE", s.c_str());
#else
    setenv("ATLAS_ABLATE", s.c_str(), 1);
#endif
}

// an environment variable for what follows (empty: unset)
void setEnvVar(const std::string& k, const std::string& v) {
#ifdef _WIN32
    _putenv_s(k.c_str(), v.c_str());
#else
    if (v.empty()) unsetenv(k.c_str());
    else setenv(k.c_str(), v.c_str(), 1);
#endif
}

uint32_t envAblate() {
    const char* v = std::getenv("ATLAS_ABLATE");
    return v ? uint32_t(std::strtoul(v, nullptr, 0)) : 0u;
}

// the level-0 grid's sections still waiting for the GPU (atlas_info "voxgrid"), -1 when there is no grid
long voxgridPending(AtlasContext* ctx) {
    std::string info = atlas_info(ctx);
    auto v = info.find("\"voxgrid\":{");
    if (v == std::string::npos) return -1;
    auto p = info.find("\"pending\":", v);
    return p == std::string::npos ? -1 : std::atol(info.c_str() + p + 10);
}

// ATLAS_ABLATE 134217728: level 0 from the SVDAG regions alone (the grid off)
constexpr uint32_t ABLATE_NO_VOXGRID = 134217728u;

uint32_t flagsOf(const std::string& s) {
    uint32_t f = 0;
    if (s.find("clouds") != std::string::npos) f |= ATLAS_FRAME_CLOUDS;
    if (s.find("shadows") != std::string::npos) f |= ATLAS_FRAME_SHADOWS;
    if (s.find("atmos") != std::string::npos) f |= ATLAS_FRAME_ATMOS;
    if (s.find("actors") != std::string::npos) f |= ATLAS_FRAME_ACTORS;
    if (s.find("lod") != std::string::npos) f |= ATLAS_FRAME_DEBUG_LOD;
    if (s.find("sky") != std::string::npos) f |= ATLAS_FRAME_SKY;
    if (s.find("quarter") != std::string::npos) f |= ATLAS_FRAME_CLOUDS_QUARTER;
    if (s.find("flatnormals") != std::string::npos) f |= ATLAS_FRAME_FLAT_NORMALS;
    if (s.find("lightlow") != std::string::npos) f |= ATLAS_FRAME_LIGHT_LOW;
    if (s.find("dbgweather") != std::string::npos) f |= ATLAS_FRAME_DEBUG_WEATHER;
    return f;
}

}  // namespace

int main(int argc, char** argv) {
    auto a = parse(argc, argv);
    std::string raster = str(a, "raster", "native/data/tamriel_raster.bin");
    std::string out = str(a, "out", "atlas.png");
    std::string backendName = str(a, "backend", "cpu");
    uint32_t w = uint32_t(num(a, "w", 1280)), h = uint32_t(num(a, "h", 720));
    double x = num(a, "x", 0), z = num(a, "z", 0);
    int lods = int(num(a, "lods", 7));
    // a live capture's camera (Minecraft conventions) -> ours: yaw + 180, pitch negated
    bool mcCam = str(a, "camera", "") == "mc";
    double eye[3] = {0, 0, 0};
    if (mcCam) {
        std::sscanf(str(a, "eye", "0,0,0").c_str(), "%lf,%lf,%lf", &eye[0], &eye[1], &eye[2]);
        x = eye[0], z = eye[2];
    }
    bool haveExport = a.count("volume") != 0;
    // box=x0,y0,z0,x1,y1,z1 (world blocks, inclusive): one element cut from the export, nothing else in the world (no
    // terrain either), as atlas_truth / atlas_lab take it
    int box[6] = {0, 0, 0, 0, 0, 0};
    bool boxed = haveExport && a.count("box") &&
                 std::sscanf(str(a, "box", "").c_str(), "%d,%d,%d,%d,%d,%d", &box[0], &box[1], &box[2], &box[3], &box[4], &box[5]) == 6;
    if (boxed)
        for (int k = 0; k < 3; k++)
            if (box[k] > box[k + 3]) std::swap(box[k], box[k + 3]);
    // with an export the groups and Minecraft's own look are the defaults (groups=0 / shade=atlas: the old path, our
    // lighting)
    bool groups = num(a, "groups", haveExport ? 1 : 0) > 0;
    bool mcShade = str(a, "shade", haveExport && mcCam ? "mc" : "") == "mc";
    bool packShade = str(a, "shade", "") == "pack";
    bool nearLight = packShade && num(a, "nearlight", 0) > 0;
    packlook::Look look;
    std::string airJson;  // the air's light and sky at the eye (JSON fragment)
    look.exposure = float(num(a, "exposure", 0));
    look.nightVision = float(num(a, "nv", 0.7));
    packlook::C3 skyHdr{0.358f, 0.636f, 1.286f};  // atlas_truth info=1 time=6000's skyHdr
    {
        float k[3];
        if (std::sscanf(str(a, "skyhdr", "").c_str(), "%f,%f,%f", &k[0], &k[1], &k[2]) == 3) skyHdr = {k[0], k[1], k[2]};
    }
    uint32_t skyMc = uint32_t(std::strtoul(str(a, "sky", "9CC4EC").c_str(), nullptr, 16));

    bool server = num(a, "server", 1) > 0;
    // server=0: one element's sections stay however far the camera goes (the world drops sections ~1.2 km out)
    if (boxed && !server && !std::getenv("ATLAS_SECTION_KEEP")) {
#ifdef _WIN32
        _putenv_s("ATLAS_SECTION_KEEP", "64");
#else
        setenv("ATLAS_SECTION_KEEP", "64", 0);
#endif
    }
    AtlasContext* ctx = nullptr;
    AtlasBackend be = backendName == "vulkan" ? ATLAS_BACKEND_VULKAN : ATLAS_BACKEND_CPU;
    if (atlas_create(be, &ctx) != ATLAS_OK) {
        std::fprintf(stderr, "atlas_create(%s) failed\n", backendName.c_str());
        return 2;
    }
    if (atlas_preview_load_raster(ctx, raster.c_str()) != ATLAS_OK) {
        std::fprintf(stderr, "%s\n", atlas_last_error(ctx));
        return 2;
    }
    double ground = atlas_preview_height(ctx, x, z);
    double y = mcCam ? eye[1] : a.count("y") ? num(a, "y", 100) : std::max(ground, 63.0) + num(a, "above", 40);
    double centre[3] = {x, y, z};
    // air=clean|humid|dusty|smoke|snow|salt: the climate's air (air.hpp namedClimate -> atlas_air_from_climate), as
    // the game sets it (AtlasAir.java); default the clean air
    AtlasAir airUsed;
    atlas_air_defaults(&airUsed);
    if (a.count("air")) {
        air::Climate k = air::namedClimate(a["air"].c_str());
        AtlasClimate c{};
        c.temperatureC = k.temperatureC;
        c.relativeHumidity = k.relativeHumidity;
        c.pressure = k.pressure;
        for (int i = 0; i < 4; i++) c.background[i] = k.background[i];
        c.dust = k.dust, c.ash = k.ash, c.snow = k.snow, c.rain = k.rain, c.fog = k.fog, c.coast = k.coast;
        atlas_air_from_climate(&c, &airUsed);
        atlas_set_air(ctx, &airUsed);
    }

    // preview tiles for every LOD window (2 regions of slack either side, matching World::windowRadius); for one element
    // empty ones (no ground: the regions exist, only the element's sections fill them)
    for (int lod = 0; lod < lods; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        if (!boxed) {
            atlas_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
            continue;
        }
    }
    // one element: empty column tiles (no ground) over every level's window around a camera, as it moves
    auto emptyTiles = [&](double cxw, double czw) {
        std::vector<float> surf(256 * 256, -1000.0f), wat(256 * 256, NAN);
        std::vector<uint16_t> zero(256 * 256, 0);
        for (int lod = 0; lod < lods; lod++) {
            int span = 256 << lod;
            int cx = int(std::floor(cxw / span)), cz = int(std::floor(czw / span));
            for (int tz = cz - 2; tz <= cz + 2; tz++)
                for (int tx = cx - 2; tx <= cx + 2; tx++) {
                    if (atlas_has_tile(ctx, lod, tx, tz)) continue;
                    AtlasColumnTile t{lod, tx, tz, surf.data(), wat.data(), zero.data(), zero.data(), nullptr, nullptr};
                    atlas_upload_column_tile(ctx, &t);
                }
        }
    };
    if (boxed) emptyTiles(x, z);
    // the in-game export: its states as far materials, its sections, the ground under them in the column tiles
    exportdata::Loaded ex;
    double loadMs = 0;
    // synth=R (no export; residency tests): sections built over the preview terrain within R sections of the camera's,
    // sent as a server's stream carries them (synthorder=far, the default: farthest first, the arrival order the live
    // pool filled in; near; random): stone under the ground, three of dirt, the top grass (snow above y 200), water to
    // y 62, nothing above; an ore pocket of gravel in a quarter of the sections (so most of a mountain's inside is one id)
    if (!haveExport && num(a, "synth", 0) > 0) {
        int R = int(num(a, "synth", 0));
        int csx = int(std::floor(x / 16)), csz = int(std::floor(z / 16));
        std::vector<int> hgt(256);
        size_t uniform = 0;
        for (int cz = csz - R; cz <= csz + R; cz++)
            for (int cx = csx - R; cx <= csx + R; cx++) {
                int top = 62;
                for (int i = 0; i < 256; i++) {
                    hgt[size_t(i)] = int(std::floor(atlas_preview_height(ctx, cx * 16 + (i & 15) + 0.5, cz * 16 + (i >> 4) + 0.5)));
                    top = std::max(top, hgt[size_t(i)]);
                }
                for (int sy = -4; sy * 16 <= top && sy < 40; sy++) {
                    exportdata::Held hs{cx, sy, cz, std::vector<uint16_t>(4096, 0)};
                    uint32_t hsd = uint32_t(cx) * 73856093u ^ uint32_t(sy) * 19349663u ^ uint32_t(cz) * 83492791u;
                    hsd = (hsd ^ (hsd >> 13)) * 1274126177u;
                    bool ore = (hsd >> 30) == 0u;
                    int ox = int((hsd >> 4) & 15u), oy = int((hsd >> 8) & 15u), oz = int((hsd >> 12) & 15u);
                    bool any = false, one = true;
                    for (int y = 0; y < 16; y++)
                        for (int lz = 0; lz < 16; lz++)
                            for (int lx = 0; lx < 16; lx++) {
                                int wy = sy * 16 + y, g = hgt[size_t(lx + 16 * lz)];
                                uint16_t m = wy < g - 3 ? 1 : wy < g ? 3 : wy == g ? (g > 200 ? 5 : 2) : wy <= 62 ? 6 : 0;
                                if (m == 1 && ore && std::abs(lx - ox) <= 1 && std::abs(y - oy) <= 1 && std::abs(lz - oz) <= 1) m = 7;
                                hs.mats[size_t(lx + 16 * (lz + 16 * y))] = m;
                                any |= m != 0;
                                one &= m == hs.mats[0];
                            }
                    if (!any) continue;
                    uniform += one ? 1 : 0;
                    ex.held.push_back(std::move(hs));
                }
            }
        std::string so = str(a, "synthorder", "far");
        auto d2 = [&](const exportdata::Held& h) { return (h.cx - csx) * (h.cx - csx) + (h.cz - csz) * (h.cz - csz); };
        if (so == "random") {
            uint32_t sd = 777u;
            for (size_t i = ex.held.size(); i > 1; i--) {
                sd = sd * 1664525u + 1013904223u;
                std::swap(ex.held[i - 1], ex.held[size_t(sd >> 8) % i]);
            }
        } else {
            std::stable_sort(ex.held.begin(), ex.held.end(), [&](const exportdata::Held& p, const exportdata::Held& q) {
                return so == "near" ? d2(p) < d2(q) : d2(p) > d2(q);
            });
        }
        std::fprintf(stderr, "atlas_render: synth %zu sections (%zu of one id) in %d x %d columns, sent %s first\n", ex.held.size(),
                     uniform, 2 * R + 1, 2 * R + 1, so.c_str());
    }
    if (haveExport) {
        auto l0 = std::chrono::steady_clock::now();
        std::vector<exportdata::State> lib;
        std::vector<exportdata::Section> secs;
        std::string vol = str(a, "volume", "");
        if (!exportdata::loadBlocks(str(a, "blocks", ""), lib)) {
            std::fprintf(stderr, "no block library at '%s' (blocks=)\n", str(a, "blocks", "").c_str());
            return 2;
        }
        if (!exportdata::loadVolume(vol, secs)) {
            std::fprintf(stderr, "no volume at '%s'\n", vol.c_str());
            return 2;
        }
        std::string side = vol.substr(0, vol.rfind('.')) + ".json";
        uint32_t grass = exportdata::jsonColour(side, "grass", 0x91BD59), foliage = exportdata::jsonColour(side, "foliage", 0x77AB2F),
                 water = exportdata::jsonColour(side, "water", 0x3F76E4);
        std::string why;
        if (!exportdata::upload(ctx, lib, secs, str(a, "keep", "far") == "all", grass, foliage, water, 64, ex, why,
                                boxed ? box : nullptr, num(a, "cutout", 1) > 0, server)) {
            std::fprintf(stderr, "export: %s\n", why.c_str());
            return 2;
        }
        if (boxed && !ex.sections) {
            std::fprintf(stderr, "export: nothing the far field keeps inside box=%s\n", str(a, "box", "").c_str());
            return 2;
        }
        // the column tiles under the export: its ground (no canopy of the dressing's), the preview's elsewhere
        exportdata::Ground gr = exportdata::ground(secs, lib, ex);
        for (int lod = 0; lod < lods && !boxed; lod++) {
            int s = 1 << lod, span = 256 << lod;
            int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
            for (int tz = cz - 2; tz <= cz + 2; tz++)
                for (int tx = cx - 2; tx <= cx + 2; tx++) {
                    if ((tx + 1) * span <= ex.x0 || tx * span >= ex.x1 || (tz + 1) * span <= ex.z0 || tz * span >= ex.z1) continue;
                    std::vector<float> surf(256 * 256), wat(256 * 256, NAN);
                    std::vector<uint16_t> top(256 * 256, 0), fill(256 * 256, 0);
                    std::vector<uint8_t> flags(256 * 256, 0);
                    bool any = false;
                    for (int j = 0; j < 256; j++)
                        for (int i = 0; i < 256; i++) {
                            size_t c = size_t(j) * 256 + size_t(i);
                            int bx = tx * span + i * s + s / 2, bz = tz * span + j * s + s / 2;
                            float t;
                            uint16_t m, u;
                            if (gr.at(bx, bz, t, m, u)) {
                                surf[c] = t, top[c] = m, fill[c] = u, flags[c] = ATLAS_COLUMN_NO_CANOPY;
                                any = true;
                            } else {
                                surf[c] = atlas_preview_height(ctx, bx, bz);
                                if (surf[c] < 63.0f) wat[c] = 63.0f;
                            }
                        }
                    if (any && atlas_upload_column_heights3(ctx, lod, tx, tz, surf.data(), wat.data(), top.data(), fill.data(), nullptr,
                                                            nullptr, flags.data()) != ATLAS_OK) {
                        std::fprintf(stderr, "tile: %s\n", atlas_last_error(ctx));
                        return 2;
                    }
                }
        }
        if (num(a, "textest", 0) > 0 && !ex.shapes.empty()) {
            // textest=1: stand-in 16 x 16 textures (offline exports carry no textures): each face tile's texel spread
            // over 4 x 4, a one-texel checker darkening every other one, so the sampling, its (u, v) and mips show
            size_t n = ex.shapes.size();
            std::vector<uint32_t> tex(n * 6 * 256 + n, 0u);
            for (size_t m = 0; m < n; m++) {
                for (int f = 0; f < 6; f++)
                    for (int v = 0; v < 16; v++)
                        for (int u = 0; u < 16; u++) {
                            uint32_t c = ex.shapes[m].faces[f][(u >> 2) + 4 * (v >> 2)];
                            if (((u ^ v) & 1) && (c >> 24)) {
                                uint32_t r = ((c >> 16) & 255) * 3 / 4, g = ((c >> 8) & 255) * 3 / 4, b = (c & 255) * 3 / 4;
                                c = (c & 0xFF000000u) | r << 16 | g << 8 | b;
                            }
                            tex[(m * 6 + size_t(f)) * 256 + size_t(u + 16 * v)] = c;
                        }
                tex[n * 6 * 256 + m] = 1u;
            }
            if (atlas_set_material_textures(ctx, ex.firstMat, tex.data(), uint32_t(n)) != ATLAS_OK) {
                std::fprintf(stderr, "textures: %s\n", atlas_last_error(ctx));
                return 2;
            }
        }
        if (num(a, "lights", 0) > 0) {  // the emitters as far lights (AtlasEditsClient.lights)
            std::vector<AtlasLight> ls;
            for (const exportdata::Section& sc : secs)
                for (int i = 0; i < 4096; i++) {
                    uint32_t id = sc.at(i);
                    if (id >= lib.size() || !lib[id].present || lib[id].emit < 8) continue;
                    int bx = sc.cx * 16 + (i & 15), by = sc.sy * 16 + (i >> 8), bz = sc.cz * 16 + ((i >> 4) & 15);
                    if (boxed && (bx < box[0] || by < box[1] || bz < box[2] || bx > box[3] || by > box[4] || bz > box[5])) continue;
                    AtlasLight l{};
                    l.pos[0] = bx + 0.5, l.pos[1] = by + 0.5, l.pos[2] = bz + 0.5;
                    l.radius = 1.5f;
                    l.intensity = std::fmin(1.0f, float(lib[id].emit) / 15.0f);
                    l.rgb = 0xFFu | 0x86u << 8 | 0x2Eu << 16;  // AtlasLights.ORANGE
                    l.kind = ATLAS_LIGHT_FIRE;
                    ls.push_back(l);
                }
            if (!ls.empty() && atlas_set_light_group(ctx, 1, ls.data(), uint32_t(ls.size())) != ATLAS_OK) {
                std::fprintf(stderr, "lights: %s\n", atlas_last_error(ctx));
                return 2;
            }
            std::fprintf(stderr, "atlas_render: %zu far lights from the export\n", ls.size());
        }
        if (num(a, "lightsynth", 0) > 0) {
            // lightsynth=N: N far lights in towns of 64 scattered over 24 km around the camera (a world's far towns, as the
            // game registers them: what the far-light binning is measured on), group 2
            int n = int(num(a, "lightsynth", 0));
            std::vector<AtlasLight> ls;
            uint32_t sd = 12345u;
            auto rnd = [&] {
                sd = sd * 1664525u + 1013904223u;
                return double(sd >> 8) / double(1u << 24);
            };
            double tx = 0, tz = 0;
            for (int i = 0; i < n; i++) {
                if (i % 64 == 0) tx = x + (rnd() - 0.5) * 24000.0, tz = z + (rnd() - 0.5) * 24000.0;
                AtlasLight l{};
                l.pos[0] = tx + (rnd() - 0.5) * 120.0;
                l.pos[2] = tz + (rnd() - 0.5) * 120.0;
                l.pos[1] = std::fmax(atlas_preview_height(ctx, l.pos[0], l.pos[2]), 63.0) + 1.0 + rnd() * 6.0;
                l.radius = 1.0f;
                l.intensity = float(0.3 + 0.7 * rnd());
                l.rgb = 0xFFu | 0xC0u << 8 | 0x70u << 16;
                l.kind = i % 5 == 0 ? ATLAS_LIGHT_FIRE : ATLAS_LIGHT_NIGHT;
                ls.push_back(l);
            }
            if (atlas_set_light_group(ctx, 2, ls.data(), uint32_t(ls.size())) != ATLAS_OK) {
                std::fprintf(stderr, "lights: %s\n", atlas_last_error(ctx));
                return 2;
            }
            std::fprintf(stderr, "atlas_render: %zu synthetic far lights\n", ls.size());
        }
        loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - l0).count();
        std::fprintf(stderr, "atlas_render: export: %zu sections, %zu materials (%zu shaped) in %.0f ms, blocks x %d..%d z %d..%d\n", ex.sections, ex.states,
                     ex.shaped, loadMs, ex.x0, ex.x1, ex.z0, ex.z1);
    }
    // surface cards (atlas_truth bake=: the game's own colour and light per block face): surface= or cards=
    size_t cardCount = 0;
    if (a.count("surface") || a.count("cards")) {
        std::vector<AtlasSurfaceCard> cards;
        std::string cp = a.count("surface") ? str(a, "surface", "") : str(a, "cards", "");
        if (!exportdata::readCards(cp, cards)) {
            std::fprintf(stderr, "no surface cards at '%s'\n", cp.c_str());
            return 2;
        }
        if (atlas_set_surface_cards(ctx, cards.data(), uint32_t(cards.size())) != ATLAS_OK) {
            std::fprintf(stderr, "cards: %s\n", atlas_last_error(ctx));
            return 2;
        }
        cardCount = cards.size();
        std::fprintf(stderr, "atlas_render: %zu surface cards\n", cardCount);
    }
    // the server's sections window around a camera (AtlasSections.push / outside): each section of a tile in the window
    // once; a tile that left it is forgotten, so it comes again on return
    auto serverWindow = [&](double cxw, double czw) -> bool {
        auto fd = [](int v, int d) { return v >= 0 ? v / d : -((-v + d - 1) / d); };
        int ptx = fd(int(std::floor(cxw)), 256), ptz = fd(int(std::floor(czw)), 256);
        for (exportdata::Held& hs : ex.held) {
            int tx = fd(hs.cx * 16, 256), tz = fd(hs.cz * 16, 256);
            bool inside = std::abs(tx - ptx) <= 4 && std::abs(tz - ptz) <= 4;
            if (!inside) {
                hs.sent = false;
                continue;
            }
            if (hs.sent) continue;
            if (atlas_upload_section(ctx, hs.cx, hs.sy, hs.cz, hs.mats.data()) != ATLAS_OK) {
                std::fprintf(stderr, "section: %s\n", atlas_last_error(ctx));
                return false;
            }
            hs.sent = true;
        }
        return true;
    };
    if (!serverWindow(x, z)) return 2;
    // stream=N budget=B: a loading simulation (docs/ATLAS-STREAMING.md): no build up front; N frames, each after one
    // update within B ms (B 0: everything at once, as the game asks today), each written as <out>_sNN.png
    int streamFrames = int(num(a, "stream", 0));
    double updateBudget = num(a, "budget", 0);
    if (!streamFrames && atlas_update(ctx, centre, 0) != ATLAS_OK) {
        std::fprintf(stderr, "update: %s\n", atlas_last_error(ctx));
        return 3;
    }
    AtlasStats st{};
    atlas_get_stats(ctx, &st);
    double buildMs = st.lastBuildMs, groupsMs = 0;
    if (groups && !streamFrames) {
        // a frame asking for the groups starts their building; the next update builds every region's tree
        AtlasFrame f1{};
        f1.cameraPos[0] = x, f1.cameraPos[1] = y, f1.cameraPos[2] = z;
        atlas::cameraMatrix(0, 0, 70, 1, f1.invViewProj);
        f1.width = f1.height = 1;
        f1.maxDistance = 100;
        f1.flags = ATLAS_FRAME_GROUPS;
        uint32_t c1 = 0, n1 = 0;
        float d1 = 0;
        AtlasFrameOutput o1{&c1, &n1, &d1};
        atlas_render(ctx, &f1, &o1);
        if (atlas_update(ctx, centre, 0) != ATLAS_OK) {
            std::fprintf(stderr, "update: %s\n", atlas_last_error(ctx));
            return 3;
        }
        atlas_get_stats(ctx, &st);
        groupsMs = st.lastBuildMs;
    }

    if (str(a, "actors", "") == "demo") {
        // a caravan walking away along the view, a mammoth beside it, a dragon circling further out
        double yaw = num(a, "yaw", 0) * PI / 180, fx = std::sin(yaw), fz = -std::cos(yaw), sx = std::cos(yaw), sz = std::sin(yaw);
        std::vector<AtlasActor> act;
        auto put = [&](double along, double side, double lift, float h, float r, uint32_t kind, uint32_t tint, uint32_t flags = 0) {
            AtlasActor q{};
            q.pos[0] = x + fx * along + sx * side;
            q.pos[2] = z + fz * along + sz * side;
            q.pos[1] = std::fmax(atlas_preview_height(ctx, q.pos[0], q.pos[2]), 63.0) + lift;
            q.yaw = float(yaw);
            q.height = h;
            q.radius = r;
            q.kind = kind;
            q.tint = tint;
            q.flags = flags;
            act.push_back(q);
        };
        // actors=demo close=1: villagers 15-25 blocks out (inside a game's near field: the case of a detached camera)
        double first = num(a, "close", 0) > 0 ? 15 : 70;
        for (int i = 0; i < 6; i++) put(first + i * (first < 50 ? 2.0 : 4.5), (i % 2) * 1.6 - 0.8, 0, 1.8f, 0.35f, 0, i == 0 ? 0xFF2030B0u : 0xFF3A5A70u);
        put(85, 9, 0, 4.6f, 2.3f, 1, 0xFF3A4A5Au);
        // dragon=near: one beating its wings 120 blocks out (the impostor's animation; time= picks the beat phase)
        if (str(a, "dragon", "") == "near") put(120, 0, 20, 5.0f, 7.0f, 2, 0xFF283038u, ATLAS_GAIT_FLAP << 8);
        else put(600, -120, 55, 5.0f, 7.0f, 2, 0xFF283038u, ATLAS_GAIT_FLAP << 8);
        atlas_set_actors(ctx, act.data(), uint32_t(act.size()));
    }

    std::string preset = str(a, "weather", "fair");
    {
        std::string noisePath = str(a, "noise", "native/data/cloud_noise.bin");
        std::vector<uint32_t> noise(64 * 64 * 64);
        FILE* nfp = std::fopen(noisePath.c_str(), "rb");
        if (nfp && std::fread(noise.data(), 4, noise.size(), nfp) == noise.size()) atlas_set_cloud_noise(ctx, noise.data());
        else std::fprintf(stderr, "no cloud noise at %s (run native/tools/export_cloud_noise.py); clouds will be empty\n", noisePath.c_str());
        if (nfp) std::fclose(nfp);
        double sx = num(a, "stormx", x + 9000), sz = num(a, "stormz", z - 6000);
        syntheticWeather(ctx, preset, x, z, sx, sz);
    }

    // one frame (the arguments' camera, or each line of cameras=): its outputs in the vectors below
    AtlasFrame f{};
    std::vector<uint32_t> color, normal;
    std::vector<float> depth, linear;
    std::vector<uint8_t> shown;
    std::string base;
    double best = 1e30, median = 0;
    int frames = std::max(1, int(num(a, "frames", 1)));
    // interleave=1: timing frames alternate the groups and the voxel path; interleave=light: the far light traced and
    // cheap (ATLAS_FRAME_LIGHT_LOW), the same clocks for both
    uint32_t interleaveBits = str(a, "interleave", "") == "light" ? ATLAS_FRAME_LIGHT_LOW : ATLAS_FRAME_GROUPS;
    // interleave=vg: the other frames with level 0 from the SVDAG alone; interleave=ablate abbits=N: with ATLAS_ABLATE ^ N
    bool vgAB = str(a, "interleave", "") == "vg" || str(a, "interleave", "") == "ablate";
    const uint32_t abBits = str(a, "interleave", "") == "vg" ? ABLATE_NO_VOXGRID : uint32_t(std::strtoul(str(a, "abbits", "0").c_str(), nullptr, 0));
    bool interleave = !vgAB && (num(a, "interleave", 0) > 0 || str(a, "interleave", "") == "light");
    const uint32_t baseAblate = envAblate();
    std::vector<double> otherTimes;  // (interleave) the other path's timing frames
    std::vector<double> feedMs;      // (feed=) the uploads' times during a warm-up
    size_t feedAt = 0;
    int spp = std::max(1, int(num(a, "spp", 1)));
    auto renderOne = [&](const std::string& out, double x, double y, double z, double yawIn, double pitchIn, double fovIn,
                         uint32_t w, uint32_t h) -> int {
        double c[3] = {x, y, z};
        if (boxed) emptyTiles(x, z);
        if (!serverWindow(x, z)) return 2;
        {
            // updates=N (with budget=B): N calls as the game's worker makes them, each timed (the first after a move is
            // what the worker waits on); otherwise one
            int nUpd = std::max(1, int(num(a, "updates", 1)));
            std::string times;
            for (int u = 0; u < nUpd; u++) {
                auto u0 = std::chrono::steady_clock::now();
                if (atlas_update(ctx, c, updateBudget) != ATLAS_OK) {  // the window follows the camera (nothing to do when it stays)
                    std::fprintf(stderr, "update: %s\n", atlas_last_error(ctx));
                    return 3;
                }
                char tb[32];
                std::snprintf(tb, sizeof tb, "%s%.0f", u ? " " : "",
                              std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - u0).count());
                times += tb;
            }
            AtlasStats su{};
            atlas_get_stats(ctx, &su);
            std::fprintf(stderr, "atlas_render: update ms (budget %.0f): %s; regions %u, dirty %u\n", updateBudget, times.c_str(),
                         su.regions, su.regionsDirty);
        }
        f = AtlasFrame{};
        f.cameraPos[0] = x;
        f.cameraPos[1] = y;
        f.cameraPos[2] = z;
        double camYaw = mcCam ? yawIn + 180.0 : yawIn, camPitch = mcCam ? -pitchIn : pitchIn;
        atlas::cameraMatrix(camYaw, camPitch, fovIn, double(w) / h, f.invViewProj);
        f.width = w;
        f.height = h;
        f.nearFieldEnd = float(num(a, "near", 0));
        f.maxDistance = float(num(a, "far", 40000));
        f.planetRadius = float(num(a, "radius", haveExport ? 0 : 6371000));  // (the exact reference is flat)
        double se = 35, sa = 120;
        if (a.count("sun")) std::sscanf(a["sun"].c_str(), "%lf,%lf", &se, &sa);
        se *= PI / 180;
        sa *= PI / 180;
        f.sunDir[0] = float(std::sin(sa) * std::cos(se));
        f.sunDir[1] = float(std::sin(se));
        f.sunDir[2] = float(-std::cos(sa) * std::cos(se));
        // an eclipse (atlas_set_eclipse): eclipse=x,y,z,r[,x,y,z,r] occulters from the camera (km), or umbra=R,L,off,az: a
        // sphere of radius R km, L km up the sun's direction from a ground point off km from the camera at azimuth az
        // (degrees, as yaw): its umbra's footprint centred there; eclipsekm= km a block (0.1)
        if (a.count("eclipse") || a.count("umbra")) {
            float occ[8] = {0, 0, 0, 0, 0, 0, 0, 0};
            if (a.count("eclipse"))
                std::sscanf(a["eclipse"].c_str(), "%f,%f,%f,%f,%f,%f,%f,%f", &occ[0], &occ[1], &occ[2], &occ[3], &occ[4],
                            &occ[5], &occ[6], &occ[7]);
            else {
                double R = 2633, L = 41340, off = 2300, az = 0;
                std::sscanf(a["umbra"].c_str(), "%lf,%lf,%lf,%lf", &R, &L, &off, &az);
                az *= PI / 180;
                double q[3] = {std::sin(az) * off, 0.0, -std::cos(az) * off};
                for (int i = 0; i < 3; i++) occ[i] = float(q[i] + f.sunDir[i] * L);
                occ[3] = float(R);
            }
            if (atlas_set_eclipse(ctx, occ, float(num(a, "eclipsekm", 0.1))) != ATLAS_OK) {
                std::fprintf(stderr, "atlas_set_eclipse: %s\n", atlas_last_error(ctx));
                return 2;
            }
        }
        f.timeSec = float(num(a, "time", 0));
        f.lodBias = float(num(a, "bias", 1));
        {
            // the air's light at the eye (the game's AtlasLook: key light, sky light) and the sky there; exposure=auto:
            // the game's canonical eye (mid grey under the open sky, lit by them)
            float lt[12];
            atlas_air_light(&airUsed, f.sunDir, nullptr, nullptr, float(y), lt);
            float d[5][3] = {{0, 1, 0}, {f.sunDir[0], 0.02f, f.sunDir[2]}, {-f.sunDir[0], 0.02f, -f.sunDir[2]}};
            for (int i = 1; i < 3; i++) {
                float l = std::sqrt(d[i][0] * d[i][0] + d[i][1] * d[i][1] + d[i][2] * d[i][2]);
                if (l < 0.03f) d[i][0] = 1, l = std::sqrt(1 + d[i][1] * d[i][1]);  // (a sun at the zenith)
                for (float& v : d[i]) v /= l;
            }
            float sky[9];
            atlas_air_sky(&airUsed, f.sunDir, nullptr, nullptr, float(y), &d[0][0], 3, sky);
            auto lum = [](const float* c) { return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]; };
            float L = 0.18f * (lum(lt + 3) * std::max(lt[1], 0.0f) + lum(lt + 6));
            float ev = packlook::exposureTarget(std::log2(std::max(L, 1e-5f)));
            if (str(a, "exposure", "") == "auto") look.exposure = ev;
            char b[640];
            std::snprintf(b, sizeof b,
                          ",\"air\":{\"aod\":%.3f,\"angstrom\":%.2f,\"key\":[%.4f,%.4f,%.4f],\"skyUp\":[%.4f,%.4f,%.4f],"
                          "\"zenith\":[%.4f,%.4f,%.4f],\"horizonSun\":[%.4f,%.4f,%.4f],\"horizonAnti\":[%.4f,%.4f,%.4f],"
                          "\"keySinToSky\":%.3f,\"exposure\":%.2f}",
                          airUsed.aerosolDepth, airUsed.angstrom, lt[3], lt[4], lt[5], lt[6], lt[7], lt[8], sky[0], sky[1],
                          sky[2], sky[3], sky[4], sky[5], sky[6], sky[7], sky[8],
                          lum(lt + 3) * std::max(lt[1], 0.0f) / std::max(lum(lt + 6), 1e-9f), ev);
            airJson = b;
        }
        // shade=mc: the surfaces' albedo and face normals only (the vanilla look is put together below)
        f.flags = flagsOf(str(a, "flags", mcShade ? "flatnormals" : "shadows,atmos,clouds,sky,actors"));
        if (mcShade) f.flags |= ATLAS_FRAME_DEBUG_ALBEDO | ATLAS_FRAME_FLAT_NORMALS;
        if (groups) f.flags |= ATLAS_FRAME_GROUPS;
        // shade=game: the far field as the game draws it without a shader pack (FRAME_VANILLA_LIGHT, as AtlasClient
        // asks): the core's own vanilla light, not the tool's albedo composite (shade=mc)
        if (str(a, "shade", "") == "game") f.flags = (f.flags | ATLAS_FRAME_VANILLA_LIGHT) & ~(ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS);

        color.assign(size_t(w) * h, 0);
        normal.assign(size_t(w) * h, 0);
        depth.assign(size_t(w) * h, 0.0f);
        linear.assign(size_t(w) * h * 4, 0.0f);
        AtlasFrameOutput o{color.data(), normal.data(), depth.data(), linear.data()};
        // primarynear=1|2 near=<blocks>: atlas's primary rays are the near field (ATLAS_FRAME_PRIMARY_NEAR: the G-buffer,
        // composited here as albedo x light; 2: ATLAS_FRAME_PRIMARY_NEAR_LIT, atlas's own composite), the depth output
        // the host's NDC depth
        int primaryNear = int(num(a, "primarynear", 0));
        std::vector<float> pnLight;
        if (primaryNear > 0) {
            f.flags |= ATLAS_FRAME_NEAR_LIGHT | (primaryNear == 2 ? ATLAS_FRAME_PRIMARY_NEAR_LIT : ATLAS_FRAME_PRIMARY_NEAR);
            pnLight.assign(size_t(w) * h * 4, 0.0f);
            o.nearLight = pnLight.data();
        }

        // spp=N: N jittered samples a pixel (atlas_set_jitter, Halton 2/3 as atlas_truth's spp), averaged: what the host's
        // TAA converges to (the groups' dithered threshold moves with the jitter), against a truth of as many samples

        // the level-0 grid built before anything is timed or compared (a few frames: the backend builds a budget a frame)
        {
            AtlasFrameOutput none{color.data(), normal.data(), depth.data(), linear.data()};
            // (the first frame places the grid's window: before it there is nothing pending to see)
            // nowarm=1: no warm-up (the frame as the game's first one after a move sees it)
            int k = 0;
            // (with budget=B the regions are built by the world's builders while frames go on: until none is pending,
            // at most a minute; how long that took is printed)
            auto w0 = std::chrono::steady_clock::now();
            double maxUpd = 0;
            int maxK = updateBudget > 0 ? 100000 : 256;
            for (; k < maxK && num(a, "nowarm", 0) <= 0; k++) {
                if (atlas_render(ctx, &f, &none) != ATLAS_OK) return 4;
                double cw[3] = {f.cameraPos[0], f.cameraPos[1], f.cameraPos[2]};
                // feed=N: N held sections sent again each frame (as the game's feed keeps sending while it builds),
                // each upload timed: what an upload waits behind
                for (int fi = 0, fn = int(num(a, "feed", 0)); fi < fn && !ex.held.empty(); fi++) {
                    const exportdata::Held& hs = ex.held[feedAt++ % ex.held.size()];
                    if (!hs.sent) continue;
                    auto f0 = std::chrono::steady_clock::now();
                    atlas_upload_section(ctx, hs.cx, hs.sy, hs.cz, hs.mats.data());
                    feedMs.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - f0).count());
                }
                auto u0 = std::chrono::steady_clock::now();
                if (atlas_update(ctx, cw, updateBudget) != ATLAS_OK) return 3;  // (as the game's worker keeps updating: what the grid gave back)
                maxUpd = std::max(maxUpd, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - u0).count());
                AtlasStats sw{};
                atlas_get_stats(ctx, &sw);
                if (voxgridPending(ctx) <= 0 && (updateBudget <= 0 || sw.regionsDirty == 0)) break;
                if (std::chrono::duration<double>(std::chrono::steady_clock::now() - w0).count() > 60.0) break;
                if (updateBudget > 0) std::this_thread::sleep_for(std::chrono::milliseconds(16));  // (a frame's time)
            }
            std::fprintf(stderr, "atlas_render: settled in %.0f ms, %d frames, longest update %.1f ms\n",
                         std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - w0).count(), k, maxUpd);
            if (!feedMs.empty()) {
                std::sort(feedMs.begin(), feedMs.end());
                std::fprintf(stderr, "atlas_render: %zu uploads during it: median %.3f ms, p99 %.3f ms, max %.3f ms\n", feedMs.size(),
                             feedMs[feedMs.size() / 2], feedMs[feedMs.size() * 99 / 100], feedMs.back());
                feedMs.clear();
            }
            std::string inf = atlas_info(ctx);
            auto vg = inf.find("\"voxgrid\":{");
            std::fprintf(stderr, "atlas_render: grid warm-up %d frames; %s\n", k,
                         vg == std::string::npos ? "no grid" : inf.substr(vg, inf.find('}', vg) - vg + 1).c_str());
            auto lv = inf.find("\"levels\":[");  // (its coarse levels)
            if (lv != std::string::npos)
                std::fprintf(stderr, "atlas_render: grid levels %s\n", inf.substr(lv, inf.find("}]", lv) - lv + 2).c_str());
        }
        // reupload=1: every section the core holds sent again (as a burst of edits would), then the CPU update that follows
        // timed, and the frames until the GPU grid has them again
        if (num(a, "reupload", 0) > 0 && haveExport) {
            size_t n = 0;
            auto u0 = std::chrono::steady_clock::now();
            for (const exportdata::Held& hs : ex.held)
                if (hs.sent && atlas_upload_section(ctx, hs.cx, hs.sy, hs.cz, hs.mats.data()) == ATLAS_OK) n++;
            double upMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - u0).count();
            u0 = std::chrono::steady_clock::now();
            if (atlas_update(ctx, c, 0) != ATLAS_OK) return 3;
            double updMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - u0).count();
            atlas_get_stats(ctx, &st);
            AtlasFrameOutput none{color.data(), normal.data(), depth.data(), linear.data()};
            int frames2 = 0;
            std::string stageMs;  // the render thread's share of each frame's grid batch (atlas_info voxgrid stageCpuMs)
            for (; frames2 < 256 && voxgridPending(ctx) > 0; frames2++) {
                if (atlas_render(ctx, &f, &none) != ATLAS_OK) return 4;
                std::string inf = atlas_info(ctx);
                auto sp = inf.find("\"stageCpuMs\":");
                if (sp != std::string::npos) stageMs += (stageMs.empty() ? "" : " ") + inf.substr(sp + 13, inf.find(',', sp) - sp - 13);
            }
            std::fprintf(stderr, "reupload: grid staging ms per frame: %s\n", stageMs.c_str());
            std::string info = atlas_info(ctx);
            auto vg = info.find("\"totalGpuMs\":");
            std::printf("{\"reupload\":{\"sections\":%zu,\"uploadMs\":%.1f,\"cpuUpdateMs\":%.1f,\"buildMs\":%.1f,\"gridFrames\":%d,%s}}\n", n,
                        upMs, updMs, st.lastBuildMs, frames2, vg == std::string::npos ? "\"grid\":null" : info.substr(vg, info.find('}', vg) - vg).c_str());
        }
        best = 1e30;
        std::vector<double> times;
        otherTimes.clear();
        std::vector<double> acc(size_t(w) * h * 3, 0.0), share(size_t(w) * h, 0.0);
        std::vector<float> minDepth(size_t(w) * h, 1e30f);
        auto halton = [](int i, int b) {
            float r = 0, fr = 1.0f / float(b);
            for (; i; i /= b, fr /= float(b)) r += fr * float(i % b);
            return r;
        };
        float inv[16];
        std::memcpy(inv, f.invViewProj, sizeof inv);
        for (int smp = 0; smp < spp; smp++) {
            float jx = spp > 1 ? halton(smp + 1, 2) - 0.5f : 0.0f, jy = spp > 1 ? halton(smp + 1, 3) - 0.5f : 0.0f;
            if (spp > 1) atlas_set_jitter(ctx, jx, -jy);  // (atlas_truth's sample row grows downwards)
            // timing frames on Vulkan keep their outputs on the device (as in game: no read back across the bus), then
            // the frame that is written out
            bool timeOnDevice = smp == 0 && frames > 1 && be == ATLAS_BACKEND_VULKAN;
#ifdef _WIN32
            if (timeOnDevice) _putenv_s("ATLAS_TIMING_DEVICE_OUTPUTS", "1");
#else
            if (timeOnDevice) setenv("ATLAS_TIMING_DEVICE_OUTPUTS", "1", 1);
#endif
            for (int i = 0; i < (smp == 0 ? frames + (timeOnDevice ? 1 : 0) : 1); i++) {
                if (timeOnDevice && i == frames) {
#ifdef _WIN32
                    _putenv_s("ATLAS_TIMING_DEVICE_OUTPUTS", "");
#else
                    unsetenv("ATLAS_TIMING_DEVICE_OUTPUTS");
#endif
                }
                // interleave=1: timing frames alternate the groups and the voxel path (the same clocks for both)
                bool other = (interleave || vgAB) && smp == 0 && i < frames && (i & 1);
                uint32_t keepFlags = f.flags;
                if (other && interleave) f.flags ^= interleaveBits;
                // interleave=vg: the other frames trace level 0 from the SVDAG regions (the grid off)
                if (vgAB) setAblateEnv(other ? baseAblate ^ abBits : baseAblate);
                if (atlas_render(ctx, &f, &o) != ATLAS_OK) {
                    std::fprintf(stderr, "render: %s\n", atlas_last_error(ctx));
                    return 4;
                }
                f.flags = keepFlags;
                atlas_get_stats(ctx, &st);
                if (smp == 0 && i < frames) {
                    (other ? otherTimes : times).push_back(st.lastFrameMs);
                    if (!other) best = std::min(best, st.lastFrameMs);
                }
            }
            // vgcheck=1: the same frame with level 0 from the SVDAG regions (the grid off): where either hits, the hit
            // distance and the surface word (normal, class) compared pixel by pixel
            if (smp == 0 && num(a, "vgcheck", 0) > 0) {
                std::vector<uint32_t> n2(normal.size());
                std::vector<float> d2(depth.size()), l2(linear.size());
                AtlasFrameOutput o2{nullptr, n2.data(), d2.data(), l2.data()};
                setAblateEnv(baseAblate | ABLATE_NO_VOXGRID);
                AtlasStatus rs = atlas_render(ctx, &f, &o2);
                setAblateEnv(baseAblate);
                if (rs != ATLAS_OK) return 4;
                size_t both = 0, exact = 0, within1 = 0, far1 = 0, onlyGrid = 0, onlyOld = 0, sameWord = 0;
                double maxDiff = 0;
                for (size_t i = 0; i < depth.size(); i++) {
                    bool h1 = depth[i] < 1e29f, h2 = d2[i] < 1e29f;
                    if (h1 && !h2) onlyGrid++;
                    if (!h1 && h2) onlyOld++;
                    if (!h1 || !h2) continue;
                    both++;
                    double dd = std::fabs(double(depth[i]) - double(d2[i]));
                    maxDiff = std::max(maxDiff, dd);
                    if (dd < 0.01) exact++;
                    else if (dd < 1.0) within1++;
                    else far1++;
                    if (dd >= 0.01 && num(a, "vgcheck", 0) > 2 && within1 + far1 <= 12) {
                        float nx = (float(i % w) + 0.5f) / float(w) * 2.0f - 1.0f, ny = 1.0f - (float(i / w) + 0.5f) / float(h) * 2.0f, q[4];
                        for (int r = 0; r < 4; r++) q[r] = inv[r] * nx + inv[4 + r] * ny + inv[8 + r] * 0.5f + inv[12 + r];
                        float l = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
                        std::fprintf(stderr, "vgcheck: pixel %zu,%zu grid %.3f (%.2f %.2f %.2f) old %.3f (%.2f %.2f %.2f) words %08x %08x\n", i % w,
                                     i / w, depth[i], x + q[0] / l * depth[i], y + q[1] / l * depth[i], z + q[2] / l * depth[i], d2[i],
                                     x + q[0] / l * d2[i], y + q[1] / l * d2[i], z + q[2] / l * d2[i], normal[i], n2[i]);
                        // what the uploaded sections hold just inside each hit (-1: no section there)
                        auto heldAt = [&](double t) -> int {
                            double px = x + q[0] / l * t, py = y + q[1] / l * t, pz = z + q[2] / l * t;
                            int bx = int(std::floor(px)), by = int(std::floor(py)), bz = int(std::floor(pz));
                            int cx = bx >> 4, sy = by >> 4, cz = bz >> 4;
                            for (const exportdata::Held& hs : ex.held)
                                if (hs.cx == cx && hs.sy == sy && hs.cz == cz)
                                    return hs.mats[size_t((bx & 15) + 16 * ((bz & 15) + 16 * (by & 15)))];
                            return -1;
                        };
                        std::fprintf(stderr, "vgcheck:   held at grid hit %d, at old hit %d\n", heldAt(depth[i] + 0.02), heldAt(d2[i] + 0.02));
                    }
                    sameWord += normal[i] == n2[i];
                }
                std::printf("{\"vgcheck\":{\"pixels\":%zu,\"bothHit\":%zu,\"within0.01\":%zu,\"within1\":%zu,\"beyond1\":%zu,"
                            "\"onlyGrid\":%zu,\"onlyOld\":%zu,\"sameSurfaceWord\":%zu,\"maxDiff\":%.3f}}\n",
                            depth.size(), both, exact, within1, far1, onlyGrid, onlyOld, sameWord, maxDiff);
                if (num(a, "vgcheck", 0) > 1) {  // vgcheck=2: <out>_vgdiff.png, red where the hit moved by a block or more
                    std::vector<uint32_t> im(depth.size());
                    for (size_t i = 0; i < depth.size(); i++) {
                        bool h1 = depth[i] < 1e29f, h2 = d2[i] < 1e29f;
                        float dd = h1 && h2 ? std::fabs(depth[i] - d2[i]) : (h1 != h2 ? 1e9f : 0.0f);
                        uint32_t g = uint32_t(std::min(255.0f, 40.0f + 200.0f * std::log2(1.0f + (h1 ? depth[i] : 0.0f)) / 12.0f));
                        im[i] = dd >= 1.0f ? 0xFF0000FFu : dd >= 0.01f ? 0xFF00C0FFu : 0xFF000000u | g << 16 | g << 8 | g;
                    }
                    atlas::writePng(out.substr(0, out.size() - 4) + "_vgdiff.png", im.data(), w, h);
                }
            }
            if (mcShade) {
                // Minecraft's vanilla look of what was hit: the texel's colour (sRGB) times the fixed shade of its face
                const uint32_t sky = skyMc;
                for (size_t i = 0; i < color.size(); i++) {
                    if (depth[i] > 1e29f) {
                        color[i] = 0xFF000000u | (sky & 255) << 16 | (sky & 0xFF00) | (sky >> 16);
                        continue;
                    }
                    float ox = float(normal[i] & 255) / 255.0f * 2.0f - 1.0f, oz = float((normal[i] >> 8) & 255) / 255.0f * 2.0f - 1.0f;
                    float ny = 1.0f - std::fabs(ox) - std::fabs(oz);  // octahedral decode (atlas_shared.slang octEncode)
                    float nx = ox, nz = oz;
                    if (ny < 0) {
                        nx = (1.0f - std::fabs(oz)) * (ox >= 0 ? 1.0f : -1.0f);
                        nz = (1.0f - std::fabs(ox)) * (oz >= 0 ? 1.0f : -1.0f);
                    }
                    float ax = std::fabs(nx), ay = std::fabs(ny), az = std::fabs(nz);
                    float shade = ay >= ax && ay >= az ? (ny > 0 ? 1.0f : 0.5f) : az >= ax ? 0.8f : 0.6f;
                    if (((normal[i] >> 16) & 255) == 6) shade = 1.0f;  // a surface card: the game's own shade and light
                    uint32_t px = 0xFF000000u;
                    for (int k = 0; k < 3; k++) {
                        float v = std::pow(std::fmax(linear[i * 4 + size_t(k)], 0.0f), 1.0f / 2.2f) * shade;
                        px |= uint32_t(std::fmin(255.0f, v * 255.0f + 0.5f)) << (8 * k);
                    }
                    color[i] = px;
                }
            }
            if (primaryNear == 1) {
                // the G-buffer's near pixels (class | 0x80): albedo x light (the air is the host's: left out here)
                for (size_t i = 0; i < depth.size(); i++)
                    if ((normal[i] >> 16) & 0x80u)
                        for (int k = 0; k < 3; k++) linear[i * 4 + size_t(k)] *= pnLight[i * 4 + size_t(k)];
            }
            if (nearLight) {
                // the frame's surfaces as the near field: their albedo (FRAME_DEBUG_ALBEDO), then the near light on
                // this frame's own depth and normals (as the native tests feed it), composed as the game's resolve does
                AtlasFrame fa = f;
                fa.flags |= ATLAS_FRAME_DEBUG_ALBEDO;
                std::vector<float> alb(linear.size()), nl(linear.size()), nd(depth.size(), 1e30f);
                std::vector<uint32_t> ni(depth.size(), 0);
                AtlasFrameOutput oa{nullptr, nullptr, nullptr, alb.data()};
                float farthest = 0.0f;
                for (size_t i = 0; i < depth.size(); i++)
                    if (depth[i] < 1e29f) {
                        nd[i] = depth[i];
                        ni[i] = (255u << 8) | ((normal[i] & 0xFFFFu) << 16);
                        farthest = std::max(farthest, depth[i]);
                    }
                AtlasFrame fn = f;
                fn.nearFieldEnd = farthest + 1.0f;
                fn.flags |= ATLAS_FRAME_NEAR_DEPTH | ATLAS_FRAME_NEAR_LIGHT;
                fn.nearDepth = nd.data();
                fn.nearInfo = ni.data();
                AtlasFrameOutput on{nullptr, nullptr, nullptr, linear.data(), nl.data()};
                if (atlas_render(ctx, &fa, &oa) != ATLAS_OK || atlas_render(ctx, &fn, &on) != ATLAS_OK) {
                    std::fprintf(stderr, "render (near light): %s\n", atlas_last_error(ctx));
                    return 4;
                }
                // vgcheck=1: the near light with level 0 from the SVDAG regions (the grid off), compared
                if (smp == 0 && num(a, "vgcheck", 0) > 0) {
                    std::vector<float> nl2(nl.size()), lin2(linear.size());
                    AtlasFrameOutput o2{nullptr, nullptr, nullptr, lin2.data(), nl2.data()};
                    setAblateEnv(baseAblate | ABLATE_NO_VOXGRID);
                    AtlasStatus rs = atlas_render(ctx, &fn, &o2);
                    setAblateEnv(baseAblate);
                    if (rs != ATLAS_OK) return 4;
                    size_t px = 0, off2 = 0, off10 = 0, visDiff = 0;
                    double sumA = 0, sumD = 0;
                    for (size_t i = 0; i < nd.size(); i++) {
                        if (nd[i] >= 1e29f) continue;
                        px++;
                        double a1 = 0, d1 = 0;
                        for (int k = 0; k < 3; k++) a1 += nl[i * 4 + k], d1 += std::fabs(double(nl[i * 4 + k]) - nl2[i * 4 + k]);
                        sumA += a1;
                        sumD += d1;
                        if (d1 > 0.02 * std::max(a1, 0.05)) off2++;
                        if (d1 > 0.10 * std::max(a1, 0.05)) off10++;
                        visDiff += std::fabs(nl[i * 4 + 3] - nl2[i * 4 + 3]) > 0.01f;
                    }
                    std::printf("{\"vgcheckNearLight\":{\"pixels\":%zu,\"meanRelDiff\":%.5f,\"over2pct\":%zu,\"over10pct\":%zu,"
                                "\"sunVisDiffers\":%zu}}\n", px, sumA > 0 ? sumD / sumA : 0.0, off2, off10, visDiff);
                }
                // nlframes=N nlablate=a,b,..: the near light's frame timed N rounds, the ATLAS_ABLATE variants interleaved
                // round by round (the same clocks for all); nlcount=1 also sums its secondary rays' counters per
                // variant (bits 23-25: iterations, mip skips, mip probes, node loads, solid voxels, passes, rays)
                if (smp == 0 && num(a, "nlframes", 0) > 0) {
                    std::vector<uint32_t> variants;
                    {
                        std::string vs = str(a, "nlablate", "0");
                        for (size_t p = 0; p < vs.size();) {
                            size_t q = vs.find(',', p);
                            if (q == std::string::npos) q = vs.size();
                            variants.push_back(uint32_t(std::strtoul(vs.substr(p, q - p).c_str(), nullptr, 0)));
                            p = q + 1;
                        }
                    }
                    auto setAblate = [](uint32_t v) {
                        std::string s = std::to_string(v);
#ifdef _WIN32
                        _putenv_s("ATLAS_ABLATE", s.c_str());
#else
                        setenv("ATLAS_ABLATE", s.c_str(), 1);
#endif
                    };
                    size_t nearPx = 0;
                    for (float d : nd) nearPx += d < 1e29f ? 1 : 0;
                    int rounds = int(num(a, "nlframes", 0));
                    std::vector<std::vector<double>> ms(variants.size());
                    // (timed with the outputs on the device, as in game: written across the bus they cost more than the rays)
                    auto deviceOutputs = [&](bool on) {
                        if (be != ATLAS_BACKEND_VULKAN) return;
#ifdef _WIN32
                        _putenv_s("ATLAS_TIMING_DEVICE_OUTPUTS", on ? "1" : "");
#else
                        if (on) setenv("ATLAS_TIMING_DEVICE_OUTPUTS", "1", 1); else unsetenv("ATLAS_TIMING_DEVICE_OUTPUTS");
#endif
                    };
                    deviceOutputs(true);
                    for (int r = 0; r < rounds; r++)
                        for (size_t k = 0; k < variants.size(); k++) {
                            setAblate(variants[k]);
                            if (atlas_render(ctx, &fn, &on) != ATLAS_OK) return 4;
                            atlas_get_stats(ctx, &st);
                            ms[k].push_back(st.lastFrameMs);
                        }
                    deviceOutputs(false);
                    for (size_t k = 0; k < variants.size(); k++) {
                        std::vector<double> t = ms[k];
                        std::sort(t.begin(), t.end());
                        std::string counts;
                        if (num(a, "nlcount", 0) > 0) {
                            counts = ",\"counts\":{";
                            const char* names[8] = {"", "iterations", "mipSkips", "mipProbes", "nodeLoads", "solidVoxels", "passes", "rays"};
                            for (uint32_t m = 1; m < 8; m++) {
                                setAblate(variants[k] | (m << 23) | (num(a, "nlcount", 0) > 1 ? 67108864u : 0u));
                                if (atlas_render(ctx, &fn, &on) != ATLAS_OK) return 4;
                                atlas_get_stats(ctx, &st);
                                char cb[96];
                                std::snprintf(cb, sizeof cb, "%s\"%s\":%llu", m > 1 ? "," : "", names[m], (unsigned long long)st.raySteps);
                                counts += cb;
                            }
                            counts += "}";
                        }
                        std::printf("{\"nearLight\":{\"ablate\":%u,\"nearPixels\":%zu,\"medianMs\":%.3f,\"p25Ms\":%.3f,\"bestMs\":%.3f,\"rounds\":%d%s}}\n",
                                    variants[k], nearPx, t[t.size() / 2], t[t.size() / 4], t[0], rounds, counts.c_str());
                    }
                    setAblate(baseAblate);
                    // the timed frames wrote the same buffers: the last variant's light stays in `nl` (re-render plain)
                    if (atlas_render(ctx, &fn, &on) != ATLAS_OK) return 4;
                }
                for (size_t i = 0; i < depth.size(); i++) {
                    if (depth[i] >= 1e29f) continue;
                    for (int k = 0; k < 3; k++)
                        linear[i * 4 + size_t(k)] = alb[i * 4 + size_t(k)] * nl[i * 4 + size_t(k)] * linear[i * 4 + 3] + linear[i * 4 + size_t(k)];
                    linear[i * 4 + 3] = 1.0f;
                }
            }
            if (packShade) {
                // atlas_core's own light (its HDR radiance, premultiplied by coverage) over the flat HDR sky, then the
                // pack's look, as atlas_truth lighting=realistic tonemaps each of its samples
                for (size_t i = 0; i < color.size(); i++) {
                    float cov = std::fmin(1.0f, std::fmax(0.0f, linear[i * 4 + 3]));
                    packlook::C3 c{linear[i * 4] + skyHdr.r * (1 - cov), linear[i * 4 + 1] + skyHdr.g * (1 - cov), linear[i * 4 + 2] + skyHdr.b * (1 - cov)};
                    packlook::C3 dsp = packlook::display(c, look);
                    auto q = [](float v) { return uint32_t(std::fmin(255.0f, std::fmax(0.0f, v * 255.0f + 0.5f))); };
                    color[i] = 0xFF000000u | q(dsp.r) | q(dsp.g) << 8 | q(dsp.b) << 16;
                }
            }
            for (uint32_t py = 0; py < h; py++)
                for (uint32_t px = 0; px < w; px++) {
                    size_t i = size_t(py) * w + px;
                    for (int k = 0; k < 3; k++) acc[i * 3 + size_t(k)] += double((color[i] >> (8 * k)) & 255);
                    float d = depth[i];
                    if (d >= 1e29f || !haveExport) continue;
                    // where this sample shows the export (the element's box, or the exported chunks)
                    float nx = (float(px) + 0.5f + jx) / float(w) * 2.0f - 1.0f, ny = 1.0f - (float(py) + 0.5f + jy) / float(h) * 2.0f, q[4];
                    for (int r = 0; r < 4; r++) q[r] = inv[r] * nx + inv[4 + r] * ny + inv[8 + r] * 0.5f + inv[12 + r];
                    float l = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
                    double hx = x + q[0] / l * d, hy = y + q[1] / l * d, hz = z + q[2] / l * d;
                    // boxed: the world holds nothing but the element (empty tiles, its sections), so every hit is it
                    // (a coarse level's micro-voxel of 8 or 16 blocks reaches past the box: its hits are the element's
                    // coverage at that level, counted as such, not dropped as outside)
                    bool in = boxed || ex.inExport(hx, hz);
                    (void)hy;
                    if (in) share[i] += 1.0 / spp, minDepth[i] = std::min(minDepth[i], d);
                }
        }
        for (size_t i = 0; i < color.size(); i++) {
            uint32_t px = 0xFF000000u;
            for (int k = 0; k < 3; k++) px |= uint32_t(acc[i * 3 + size_t(k)] / spp + 0.5) << (8 * k);
            if (spp > 1 || mcShade || packShade) color[i] = px;
        }
        if (std::getenv("ATLAS_RENDER_TIMES")) {  // every timing frame, in order (measurements)
            for (size_t i = 0; i < times.size(); i++)
                std::fprintf(stderr, "%.2f%s", times[i], i + 1 < times.size() ? " " : "\n");
            for (size_t i = 0; i < otherTimes.size(); i++)
                std::fprintf(stderr, "%.2f%s", otherTimes[i], i + 1 < otherTimes.size() ? " " : "\n");
        }
        std::sort(times.begin(), times.end());
        median = times.empty() ? 0 : times[times.size() / 2];
        atlas::writePng(out, color.data(), w, h);
        base = out.size() > 4 ? out.substr(0, out.size() - 4) : out;
        if (num(a, "classpng", 0) > 0) {  // (debugging) the last sample's surface class per pixel: 6 (white) a surface card
            std::vector<uint32_t> cl(size_t(w) * h);
            for (size_t i = 0; i < cl.size(); i++) {
                uint32_t c = (normal[i] >> 16) & 255, v = depth[i] > 1e29f ? 0 : c == 6 ? 255 : 40 + 20 * std::min(c, 6u);
                cl[i] = 0xFF000000u | v << 16 | v << 8 | v;
            }
            atlas::writePng(base + "_class.png", cl.data(), w, h);
        }
        shown.assign(size_t(w) * h, 0);  // where this frame shows the export (its mask: most samples)
        if (haveExport) {
            // where the frame shows exported content (the share of its samples), and how far (atlas_truth's encodings)
            std::vector<uint32_t> mask(size_t(w) * h), dp(size_t(w) * h);
            for (size_t i = 0; i < mask.size(); i++) {
                float d = minDepth[i];
                uint32_t v = d >= 1e29f ? 0xFFFFFFu : uint32_t(std::min(d * 16.f, 16777214.f));
                dp[i] = 0xFF000000u | (v & 255u) << 16 | ((v >> 8) & 255u) << 8 | (v >> 16);
                uint32_t m = uint32_t(std::lround(share[i] * 255.0));
                mask[i] = 0xFF000000u | m << 16 | m << 8 | m;
                shown[i] = share[i] > 0.5 ? 1 : 0;
            }
            atlas::writePng(base + "_mask.png", mask.data(), w, h);
            atlas::writePng(base + "_depth.png", dp.data(), w, h);
        }
        return 0;
    };
    // cameras=FILE: one frame per line ("out ex,ey,ez yaw pitch fov w h"), everything loaded once (the element tier's
    // ladders and fly-ins in one process); a JSON line per frame
    if (a.count("cameras")) {
        FILE* cf = std::fopen(str(a, "cameras", "").c_str(), "r");
        if (!cf) {
            std::fprintf(stderr, "no cameras file '%s'\n", str(a, "cameras", "").c_str());
            return 2;
        }
        char line[2048];
        int n = 0;
        while (std::fgets(line, sizeof line, cf)) {
            char op[1024];
            double ex2 = 0, ey2 = 0, ez2 = 0, yw = 0, pt = 0, fv = 70;
            unsigned cw = w, ch = h;
            if (std::sscanf(line, "%1023s %lf,%lf,%lf %lf %lf %lf %u %u", op, &ex2, &ey2, &ez2, &yw, &pt, &fv, &cw, &ch) < 4) continue;
            int rc = renderOne(op, ex2, ey2, ez2, yw, pt, fv, cw, ch);
            if (rc) return rc;
            std::string oj = op;
            for (char& ch2 : oj) ch2 = ch2 == '\\' ? '/' : ch2;
            // with the coarse levels' state after it (what the core kept of real content, cubes per level)
            std::string info = atlas_info(ctx), coarse;
            auto cs = info.find("\"coarse\":{");
            if (cs != std::string::npos) coarse = "," + info.substr(cs, info.find('}', cs) - cs + 1);
            std::printf("{\"frame\":%d,\"out\":\"%s\",\"frameMs\":%.2f%s}\n", n++, oj.c_str(), best, coarse.c_str());
        }
        std::fclose(cf);
        std::printf("{\"backend\":\"%s\",\"frames\":%d,\"groupsOn\":%s,\"groupsBuildMs\":%.1f}\n", backendName.c_str(), n,
                    groups ? "true" : "false", groupsMs);
        atlas_destroy(ctx);
        return 0;
    }
    // seq=static|turn|walk[,...] seqframes=N (the near light's adaptivity, docs/ATLAS-RENDERER-PLAN.md "The light by
    // need"): the arguments' camera rendered once as usual (the grid settled), then for each kind seqwarm frames (default
    // 8) of the starting camera, unmeasured, and N frames in a row from one context, as a game's frames come (the
    // history carries over). static: the camera held; turn: seqrate degrees of yaw a frame (default 1.5); walk: seqrate
    // blocks forward a frame (default 0.072: 4.3 blocks a second at 60 fps). The host's TAA jitter: taa=tiny (default:
    // a thousandth of a pixel, so the temporal cycles run without the rays moving) | halton (8 phases, as the game) |
    // off. seqsave=a,b,..: those frames written as <out>_<kind>NNN.png (the pack's look), seqsave=all every one.
    // seqref=K: instead of the sequences, each saved frame's converged reference (K frames of its camera held still,
    // averaged in linear light) as <out>_<kind>NNN_ref.png. Static views report the display's frame-to-frame flicker
    // (pixels whose largest channel moves by more than 8 levels; the mean absolute change). One JSON line a frame, a
    // summary a kind. With ATLAS_TIMING_DEVICE_OUTPUTS=1 nothing is read back (timings as in game; no images).
    if (a.count("seq")) {
        if (int rc = renderOne(out, x, y, z, num(a, "yaw", 0), num(a, "pitch", mcCam ? 0 : -4), num(a, "fov", 70), w, h)) return rc;
        std::vector<std::string> kinds;
        {
            std::string ks = str(a, "seq", "static");
            for (size_t p = 0; p <= ks.size();) {
                size_t q = ks.find(',', p);
                if (q == std::string::npos) q = ks.size();
                if (q > p) kinds.push_back(ks.substr(p, q - p));
                p = q + 1;
            }
        }
        std::string taa = str(a, "taa", "tiny");
        int n = std::max(1, int(num(a, "seqframes", 60))), warm = int(num(a, "seqwarm", 8));
        int refK = int(num(a, "seqref", 0));
        bool readback = std::getenv("ATLAS_TIMING_DEVICE_OUTPUTS") == nullptr;
        std::string saveList = str(a, "seqsave", "");
        auto saved = [&](int i) {
            if (saveList == "all") return true;
            std::string k = "," + saveList + ",";
            return k.find("," + std::to_string(i) + ",") != std::string::npos;
        };
        std::string sb = out.size() > 4 ? out.substr(0, out.size() - 4) : out;
        double yaw0 = num(a, "yaw", 0), pitch0 = num(a, "pitch", mcCam ? 0 : -4), fov0 = num(a, "fov", 70);
        size_t px = size_t(w) * h;
        std::vector<float> lin(px * 4), nl(px * 4), dep(px);
        std::vector<uint32_t> nrm(px), disp(px), prevDisp;
        AtlasFrameOutput so{nullptr, nrm.data(), dep.data(), lin.data(), (f.flags & ATLAS_FRAME_NEAR_LIGHT) ? nl.data() : nullptr};
        auto halton = [](int i, int b) {
            float r = 0, fr = 1.0f / float(b);
            for (; i; i /= b, fr /= float(b)) r += fr * float(i % b);
            return r;
        };
        auto toDisplay = [&](const std::vector<float>& L, std::vector<uint32_t>& d) {
            for (size_t i = 0; i < px; i++) {
                float cov = std::fmin(1.0f, std::fmax(0.0f, L[i * 4 + 3]));
                packlook::C3 c{L[i * 4] + skyHdr.r * (1 - cov), L[i * 4 + 1] + skyHdr.g * (1 - cov), L[i * 4 + 2] + skyHdr.b * (1 - cov)};
                packlook::C3 ds = packlook::display(c, look);
                auto q = [](float v) { return uint32_t(std::fmin(255.0f, std::fmax(0.0f, v * 255.0f + 0.5f))); };
                d[i] = 0xFF000000u | q(ds.r) | q(ds.g) << 8 | q(ds.b) << 16;
            }
        };
        auto camAt = [&](const std::string& kind, int i, double& cx, double& cy, double& cz, double& yw) {
            double rate = num(a, "seqrate", kind == "turn" ? 1.5 : 0.072);
            cx = x, cy = y, cz = z, yw = yaw0;
            double camYaw = (mcCam ? yaw0 + 180.0 : yaw0) * PI / 180;
            if (kind == "turn") yw = yaw0 + rate * i;
            if (kind == "walk") cx = x + std::sin(camYaw) * rate * i, cz = z - std::cos(camYaw) * rate * i;
        };
        int frameNo = 0;
        auto frameAt = [&](double cx, double cy, double cz, double yw) -> int {
            f.cameraPos[0] = cx, f.cameraPos[1] = cy, f.cameraPos[2] = cz;
            double camYaw = mcCam ? yw + 180.0 : yw, camPitch = mcCam ? -pitch0 : pitch0;
            atlas::cameraMatrix(camYaw, camPitch, fov0, double(w) / h, f.invViewProj);
            if (taa == "halton") {
                int k = frameNo % 8 + 1;
                atlas_set_jitter(ctx, halton(k, 2) - 0.5f, -(halton(k, 3) - 0.5f));
            } else if (taa == "tiny") {
                atlas_set_jitter(ctx, 1e-3f, 0.0f);
            } else {
                atlas_set_jitter(ctx, 0.0f, 0.0f);
            }
            frameNo++;
            double c[3] = {cx, cy, cz};
            if (!serverWindow(cx, cz)) return 2;
            if (atlas_update(ctx, c, updateBudget) != ATLAS_OK) return 3;
            if (atlas_render(ctx, &f, &so) != ATLAS_OK) {
                std::fprintf(stderr, "render: %s\n", atlas_last_error(ctx));
                return 4;
            }
            return 0;
        };
        for (const std::string& kind : kinds) {
            if (refK > 0) {
                // the converged reference of each saved frame: K frames of its camera, averaged in linear light
                for (int i = 0; i < n; i++) {
                    if (!saved(i)) continue;
                    double cx, cy, cz, yw;
                    camAt(kind, i, cx, cy, cz, yw);
                    std::vector<double> accL(px * 4, 0.0);
                    for (int r = 0; r < refK; r++) {
                        if (int rc = frameAt(cx, cy, cz, yw)) return rc;
                        for (size_t p = 0; p < px * 4; p++) accL[p] += lin[p];
                    }
                    std::vector<float> avg(px * 4);
                    for (size_t p = 0; p < px * 4; p++) avg[p] = float(accL[p] / refK);
                    toDisplay(avg, disp);
                    char nm[48];
                    std::snprintf(nm, sizeof nm, "_%s%03d_ref.png", kind.c_str(), i);
                    atlas::writePng(sb + nm, disp.data(), w, h);
                    std::printf("{\"seqRef\":\"%s\",\"frame\":%d,\"frames\":%d}\n", kind.c_str(), i, refK);
                }
                continue;
            }
            // seqvariants=A|B|..: each a comma list of NAME=VALUE environment settings (read by the core every frame:
            // ATLAS_LIGHT, ATLAS_LIGHT_RATE, ATLAS_NEAR_FULLRES, ATLAS_TARGET_MS, ATLAS_ABLATE), run in turn seqrounds
            // times (default 1) in this one process, so a GPU shared with others measures every variant alike; images and
            // lines tagged _v<i>
            std::vector<std::string> variants;
            {
                std::string vs = str(a, "seqvariants", "");
                for (size_t p = 0; p <= vs.size();) {
                    size_t q = vs.find('|', p);
                    if (q == std::string::npos) q = vs.size();
                    variants.push_back(vs.substr(p, q - p));
                    p = q + 1;
                }
            }
            int rounds = std::max(1, int(num(a, "seqrounds", 1)));
            std::vector<std::vector<double>> allMs(variants.size());
            std::vector<std::string> setNames;
            for (int round = 0; round < rounds; round++)
                for (size_t v = 0; v < variants.size(); v++) {
                    for (const std::string& nm : setNames) setEnvVar(nm, "");
                    setNames.clear();
                    for (size_t p = 0; p < variants[v].size();) {
                        size_t q = variants[v].find(',', p);
                        if (q == std::string::npos) q = variants[v].size();
                        std::string kv = variants[v].substr(p, q - p);
                        size_t eq = kv.find('=');
                        if (eq != std::string::npos) {
                            setEnvVar(kv.substr(0, eq), kv.substr(eq + 1));
                            setNames.push_back(kv.substr(0, eq));
                        }
                        p = q + 1;
                    }
                    std::string tag = variants.size() > 1 ? "_v" + std::to_string(v) : "";
                    for (int i = 0; i < warm; i++) {
                        double cx, cy, cz, yw;
                        camAt(kind, 0, cx, cy, cz, yw);
                        if (int rc = frameAt(cx, cy, cz, yw)) return rc;
                    }
                    prevDisp.clear();
                    std::vector<double> ms, flick;
                    for (int i = 0; i < n; i++) {
                        double cx, cy, cz, yw;
                        camAt(kind, i, cx, cy, cz, yw);
                        if (int rc = frameAt(cx, cy, cz, yw)) return rc;
                        atlas_get_stats(ctx, &st);
                        ms.push_back(st.lastFrameMs);
                        std::string extra;
                        if (readback) {
                            toDisplay(lin, disp);
                            if (kind == "static" && !prevDisp.empty()) {
                                size_t over = 0, land = 0;
                                double sum = 0;
                                for (size_t p = 0; p < px; p++) {
                                    if (dep[p] < -1e6f) continue;  // (the sky: PRIMARY_NEAR's depth is minus the distance there)
                                    land++;
                                    int m = 0;
                                    for (int k = 0; k < 3; k++) {
                                        int d = std::abs(int((disp[p] >> (8 * k)) & 255) - int((prevDisp[p] >> (8 * k)) & 255));
                                        m = std::max(m, d);
                                        sum += d / 3.0;
                                    }
                                    over += m > 8;
                                }
                                double o8 = land ? double(over) / land : 0.0;
                                flick.push_back(o8);
                                char b[160];
                                std::snprintf(b, sizeof b, ",\"flickerOver8\":%.5f,\"flickerMean\":%.4f", o8, land ? sum / land : 0.0);
                                extra = b;
                            }
                            prevDisp = disp;
                            if (saved(i)) {
                                char nm[48];
                                std::snprintf(nm, sizeof nm, "_%s%s%03d.png", kind.c_str(), tag.c_str(), i);
                                atlas::writePng(sb + nm, disp.data(), w, h);
                            }
                        }
                        if (num(a, "seqgrid", 0) > 0) {
                            // seqgrid=1: the grid's residency each frame (atlas_info voxgrid: resident .. nearestMissing)
                            std::string inf = atlas_info(ctx);
                            size_t r0 = inf.find("\"resident\":"), r1 = inf.find("\"nearestMissing\":");
                            if (r0 != std::string::npos && r1 != std::string::npos) {
                                size_t e = inf.find(',', r1);
                                extra += "," + inf.substr(r0, e - r0);
                            }
                        }
                        std::printf("{\"seq\":\"%s%s\",\"frame\":%d,\"ms\":%.3f,\"steps\":%llu%s}\n", kind.c_str(), tag.c_str(), i, st.lastFrameMs,
                                    (unsigned long long)st.raySteps, extra.c_str());
                    }
                    allMs[v].insert(allMs[v].end(), ms.begin(), ms.end());
                    std::vector<double> t = ms;
                    std::sort(t.begin(), t.end());
                    double mean = 0, fl = 0;
                    for (double v : ms) mean += v / double(ms.size());
                    for (double v : flick) fl += v / double(flick.size());
                    // the render thread's far-light cost in building the frame (atlas_info glRender.lightsMs, eased)
                    std::string inf = atlas_info(ctx), lightsMs = "null";
                    auto lp = inf.find("\"lightsMs\":");
                    if (lp != std::string::npos) lightsMs = inf.substr(lp + 11, inf.find_first_of(",}", lp + 11) - lp - 11);
                    std::printf("{\"seqSummary\":\"%s%s\",\"round\":%d,\"frames\":%d,\"meanMs\":%.3f,\"medianMs\":%.3f,\"p90Ms\":%.3f,\"maxMs\":%.3f,"
                                "\"flickerOver8\":%.5f,\"cpuLightsMs\":%s}\n",
                                kind.c_str(), tag.c_str(), round, n, mean, t[t.size() / 2], t[t.size() * 9 / 10], t.back(), fl, lightsMs.c_str());
                }
            if (variants.size() > 1 || rounds > 1)
                for (size_t v = 0; v < variants.size(); v++) {
                    std::vector<double> t = allMs[v];
                    std::sort(t.begin(), t.end());
                    std::printf("{\"seqVariant\":\"%s\",\"kind\":\"%s\",\"variant\":\"%s\",\"frames\":%zu,\"medianMs\":%.3f,\"p25Ms\":%.3f,\"p75Ms\":%.3f}\n",
                                ("_v" + std::to_string(v)).c_str(), kind.c_str(), variants[v].c_str(), t.size(), t[t.size() / 2],
                                t[t.size() / 4], t[t.size() * 3 / 4]);
                }
            for (const std::string& nm : setNames) setEnvVar(nm, "");
        }
        atlas_destroy(ctx);
        return 0;
    }
    if (streamFrames) {
        std::string sb = out.size() > 4 ? out.substr(0, out.size() - 4) : out;
        double spent = 0;
        for (int i = 0; i < streamFrames; i++) {
            char nm[16];
            std::snprintf(nm, sizeof nm, "_s%02d.png", i);
            auto t0 = std::chrono::steady_clock::now();
            if (int rc = renderOne(sb + nm, x, y, z, num(a, "yaw", 0), num(a, "pitch", mcCam ? 0 : -4), num(a, "fov", 70), w, h))
                return rc;
            spent += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            atlas_get_stats(ctx, &st);
            std::printf("{\"stream\":%d,\"ms\":%.1f,\"updateMs\":%.1f,\"regions\":%u,\"dirty\":%u}\n", i, spent, st.lastBuildMs,
                        st.regions, st.regionsDirty);
        }
        atlas_destroy(ctx);
        return 0;
    }
    if (int rc = renderOne(out, x, y, z, num(a, "yaw", 0), num(a, "pitch", mcCam ? 0 : -4), num(a, "fov", 70), w, h)) return rc;
    // watch=1 (Vulkan, ATLAS_SHADER_DIR set, tools/dev/shader_watch.py running): the scene stays loaded and the same
    // view is rendered again whenever the shaders change, a frame instead of a run (the backend reloads them)
    if (num(a, "watch", 0) > 0) {
        const char* dir = std::getenv("ATLAS_SHADER_DIR");
        if (!dir || !*dir) {
            std::fprintf(stderr, "watch=1 needs ATLAS_SHADER_DIR (tools/dev/shader_watch.py)\n");
            return 2;
        }
        std::filesystem::path far = std::filesystem::path(dir) / "atlas_far.spv";
        std::error_code ec;
        auto seen = std::filesystem::last_write_time(far, ec);
        std::fprintf(stderr, "watching %s: %s re-renders on every shader change (ctrl-c ends)\n", far.string().c_str(), out.c_str());
        for (;;) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            auto t = std::filesystem::last_write_time(far, ec);
            if (ec || t == seen) continue;
            seen = t;
            std::this_thread::sleep_for(std::chrono::milliseconds(300));  // past the backend's 250 ms look
            auto t0 = std::chrono::steady_clock::now();
            if (int rc = renderOne(out, x, y, z, num(a, "yaw", 0), num(a, "pitch", mcCam ? 0 : -4), num(a, "fov", 70), w, h)) return rc;
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::fprintf(stderr, "rendered %s in %.0f ms (frame %.2f ms)\n", out.c_str(), ms, best);
        }
    }
    // atlas_truth's frame of the same camera, and how far this one is from it (the harness's metric, by distance)
    std::string vsTruth;
    if (a.count("truth") && mcCam) {
        std::string tp = base + "_truth.png";
        std::string cmd = "\"\"" + str(a, "truth", "") + "\" quads=\"" + str(a, "quads", "") + "\" sprites=\"" + str(a, "sprites", "") +
                          "\" volume=\"" + str(a, "truthvolume", str(a, "volume", "").c_str()) + "\" eye=" + str(a, "eye", "0,0,0") +
                          " yaw=" + str(a, "yaw", "0") + " pitch=" + str(a, "pitch", "0") + " fov=" + str(a, "fov", "70") +
                          " w=" + std::to_string(w) + " h=" + std::to_string(h) + " spp=" + std::to_string(spp) + (boxed ? " box=" + str(a, "box", "") : "") +
                          " out=\"" + tp + "\"\" > nul";
        if (std::system(cmd.c_str()) != 0) {
            std::fprintf(stderr, "atlas_truth failed: %s\n", cmd.c_str());
            return 5;
        }
        std::vector<uint32_t> tc, tm, td;
        uint32_t tw = 0, th = 0;
        std::string tb = tp.substr(0, tp.size() - 4);
        if (!exportdata::readPng(tp, tc, tw, th) || !exportdata::readPng(tb + "_mask.png", tm, tw, th) ||
            !exportdata::readPng(tb + "_depth.png", td, tw, th) || tw != w || th != h) {
            std::fprintf(stderr, "could not read atlas_truth's frame %s\n", tp.c_str());
            return 5;
        }
        const float bands[6] = {0, 64, 192, 512, 1024, 4096};
        double sum[5] = {}, all = 0;
        size_t cnt[5] = {}, inside = 0, both = 0, either = 0;
        double meanT[3] = {}, meanC[3] = {};
        std::vector<uint32_t> sheet(size_t(w) * 3 * h);
        for (size_t i = 0; i < tc.size(); i++) {
            float la[3], lb[3];
            exportdata::lab(tc[i], la);
            exportdata::lab(color[i], lb);
            float de = std::sqrt((la[0] - lb[0]) * (la[0] - lb[0]) + (la[1] - lb[1]) * (la[1] - lb[1]) + (la[2] - lb[2]) * (la[2] - lb[2]));
            bool in = (tm[i] & 255) > 127;
            both += in && shown[i];
            either += in || shown[i];
            if (in || shown[i])  // the mean colour over both silhouettes (a sub-pixel element: its share of the pixels)
                for (int k = 0; k < 3; k++) meanT[k] += double((tc[i] >> (8 * k)) & 255), meanC[k] += double((color[i] >> (8 * k)) & 255);
            uint32_t dv = (td[i] & 255) << 16 | ((td[i] >> 8) & 255) << 8 | ((td[i] >> 16) & 255);
            float dist = float(dv) / 16.0f;
            if (in) {
                inside++;
                all += de;
                for (int b = 0; b < 5; b++)
                    if (dist >= bands[b] && dist < bands[b + 1]) sum[b] += de, cnt[b]++;
            }
            size_t row = i / w, col = i % w;
            float heat = std::fmin(de / 30.0f, 1.0f);
            uint32_t hm = in ? 0xFF000000u | uint32_t(heat * 255) | uint32_t((1 - std::fabs(heat - 0.5f) * 2) * 180) << 8 |
                                   uint32_t((1 - heat) * 120) << 16
                             : 0xFF3C3C3Cu;
            sheet[row * w * 3 + col] = tc[i];
            sheet[row * w * 3 + w + col] = color[i];
            sheet[row * w * 3 + 2 * w + col] = hm;
        }
        atlas::writePng(base + "_vs_truth.png", sheet.data(), w * 3, h);
        char buf[256];
        vsTruth = ",\"vsTruth\":{";
        std::snprintf(buf, sizeof buf, "\"dE\":%.2f,\"share\":%.4f,\"iou\":%.4f,\"shownShare\":%.4f,",
                      inside ? all / double(inside) : 0.0, double(inside) / double(tc.size()),
                      either ? double(both) / double(either) : 1.0,
                      double(std::count(shown.begin(), shown.end(), uint8_t(1))) / double(tc.size()));
        vsTruth += buf;
        {
            uint32_t pt = 0, pc = 0;
            for (int k = 0; k < 3; k++)
                pt |= uint32_t(meanT[k] / double(std::max<size_t>(either, 1)) + 0.5) << (8 * k),
                    pc |= uint32_t(meanC[k] / double(std::max<size_t>(either, 1)) + 0.5) << (8 * k);
            float la[3], lb[3];
            exportdata::lab(pt, la);
            exportdata::lab(pc, lb);
            std::snprintf(buf, sizeof buf, "\"meanDE\":%.2f,\"bands\":{", std::sqrt((la[0] - lb[0]) * (la[0] - lb[0]) + (la[1] - lb[1]) * (la[1] - lb[1]) +
                                                                     (la[2] - lb[2]) * (la[2] - lb[2])));
        }
        vsTruth += buf;
        bool firstBand = true;
        for (int b = 0; b < 5; b++) {
            if (cnt[b] <= 200) continue;
            std::snprintf(buf, sizeof buf, "%s\"%d-%d\":{\"dE\":%.2f,\"share\":%.3f}", firstBand ? "" : ",", int(bands[b]),
                          int(bands[b + 1]), sum[b] / double(cnt[b]), double(cnt[b]) / double(tc.size()));
            vsTruth += buf;
            firstBand = false;
        }
        vsTruth += "}}";
    }
    if (num(a, "depthraw", 0) > 0) {
        // depthraw=1: <out>_depth.f32, the frame's depth as floats (w x h, rows from the top; 1e30 sky), for metrics
        if (FILE* fp = std::fopen((out.substr(0, out.size() - 4) + "_depth.f32").c_str(), "wb")) {
            std::fwrite(depth.data(), sizeof(float), depth.size(), fp);
            std::fclose(fp);
        }
    }
    if (num(a, "depthpng", 0) > 0 && !haveExport) {
        std::vector<uint32_t> d(size_t(w) * h);
        for (size_t i = 0; i < d.size(); i++) {
            float t = depth[i] > 1e29f ? 0.0f : float(1.0 - std::log2(1.0 + depth[i]) / std::log2(1.0 + f.maxDistance));
            uint32_t v = uint32_t(std::fmax(0.0f, std::fmin(1.0f, t)) * 255);
            d[i] = v | v << 8 | v << 16 | 0xFF000000u;
        }
        atlas::writePng(out.substr(0, out.size() - 4) + "_depth.png", d.data(), w, h);
    }
    size_t hits = 0;
    double far = 0;
    for (float d : depth)
        if (d < 1e29f) {
            hits++;
            far = std::max(far, double(d));
        }
    // the groups' memory and builds (atlas_info's "groups" object), and what the export put up
    std::string extra, outJson = out;
    {
        std::string info = atlas_info(ctx);
        auto g = info.find("\"groups\":{");
        for (char& c : outJson) c = c == '\\' ? '/' : c;  // a JSON string
        if (g != std::string::npos) extra += "," + info.substr(g, info.find('}', g) - g + 1);
        auto cs = info.find("\"coarse\":{");  // the coarse levels of real content (memory, cubes per level)
        if (cs != std::string::npos) extra += "," + info.substr(cs, info.find('}', cs) - cs + 1);
        auto vg = info.find("\"voxgrid\":{");  // the level-0 grid: slots, memory, GPU build times
        if (vg != std::string::npos) extra += "," + info.substr(vg, info.find('}', vg) - vg + 1);
        char buf[512];
        if (haveExport) {
            std::snprintf(buf, sizeof buf, ",\"export\":{\"sections\":%zu,\"materials\":%zu,\"shaped\":%zu,\"loadMs\":%.0f}", ex.sections,
                          ex.states, ex.shaped, loadMs);
            extra += buf;
        }
        if (!otherTimes.empty()) {
            std::sort(otherTimes.begin(), otherTimes.end());
            std::snprintf(buf, sizeof buf, ",\"otherPathBestMs\":%.2f,\"otherPathMedianMs\":%.2f", otherTimes[0],
                          otherTimes[otherTimes.size() / 2]);
            extra += buf;
        }
        std::snprintf(buf, sizeof buf, ",\"groupsOn\":%s,\"groupsBuildMs\":%.1f,\"frameMedianMs\":%.2f,\"frames\":%d", groups ? "true" : "false",
                      groupsMs, median, frames);
        extra += buf;
    }
    extra += airJson;
    std::printf("{\"backend\":\"%s\",\"terrain\":\"%s\",\"camera\":[%.1f,%.1f,%.1f],\"ground\":%.1f,\"w\":%u,\"h\":%u,"
                "\"regions\":%u,\"nodeBytes\":%llu,\"leafBytes\":%llu,\"materialBytes\":%llu,\"buildMs\":%.1f,"
                "\"frameMs\":%.2f,\"raySteps\":%llu,\"hitFraction\":%.3f,\"farthestHit\":%.0f,\"out\":\"%s\"%s%s}\n",
                backendName.c_str(), haveExport ? "export+preview" : "preview", x, y, z, ground, w, h, st.regions,
                (unsigned long long)st.nodeBytes, (unsigned long long)st.leafBytes, (unsigned long long)st.materialBytes, buildMs,
                best, (unsigned long long)st.raySteps, double(hits) / depth.size(), far, outJson.c_str(), extra.c_str(), vsTruth.c_str());
    atlas_destroy(ctx);
    return 0;
}
