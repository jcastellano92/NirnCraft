// Offline far-field renderer: preview terrain from the Tamriel raster, rendered through atlas_core.
//   atlas_render raster=native/data/tamriel_raster.bin out=shot.png x=0 z=0 above=40 yaw=0 pitch=-4 fov=70
//                 w=1280 h=720 backend=cpu|vulkan sun=35,120 radius=6371000 near=0 far=40000 flags=shadows,atmos,clouds
//                 weather=off|clear|fair|overcast|storm stormx= stormz= time=<seconds>  (off = clear: no cover,
//                 the game's weather-off switch)
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
// lighting=realistic draws it (the element tier's far-lit). lights=1: the export's emitters (light 8 and up, inside
// box=) as far lights, as the game registers a real chunk's lights (AtlasEditsClient.lights: fires, intensity
// level / 15).
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "atlas_export.hpp"
#include "camera.hpp"
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
    packlook::Look look;
    look.exposure = float(num(a, "exposure", 0));
    look.nightVision = float(num(a, "nv", 0.7));
    packlook::C3 skyHdr{0.358f, 0.636f, 1.286f};  // atlas_truth info=1 time=6000's skyHdr
    {
        float k[3];
        if (std::sscanf(str(a, "skyhdr", "").c_str(), "%f,%f,%f", &k[0], &k[1], &k[2]) == 3) skyHdr = {k[0], k[1], k[2]};
    }
    uint32_t skyMc = uint32_t(std::strtoul(str(a, "sky", "9CC4EC").c_str(), nullptr, 16));

    // one element: its sections stay however far the camera goes (the world drops sections ~1.2 km out)
    if (boxed && !std::getenv("ATLAS_SECTION_KEEP")) {
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
                                boxed ? box : nullptr, num(a, "cutout", 1) > 0)) {
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
        loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - l0).count();
        std::fprintf(stderr, "atlas_render: export: %zu sections, %zu materials (%zu shaped) in %.0f ms\n", ex.sections, ex.states,
                     ex.shaped, loadMs);
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
    if (atlas_update(ctx, centre, 0) != ATLAS_OK) {
        std::fprintf(stderr, "update: %s\n", atlas_last_error(ctx));
        return 3;
    }
    AtlasStats st{};
    atlas_get_stats(ctx, &st);
    double buildMs = st.lastBuildMs, groupsMs = 0;
    if (groups) {
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
    bool interleave = num(a, "interleave", 0) > 0;
    std::vector<double> otherTimes;  // (interleave) the other path's timing frames
    int spp = std::max(1, int(num(a, "spp", 1)));
    auto renderOne = [&](const std::string& out, double x, double y, double z, double yawIn, double pitchIn, double fovIn,
                         uint32_t w, uint32_t h) -> int {
        double c[3] = {x, y, z};
        if (boxed) emptyTiles(x, z);
        if (atlas_update(ctx, c, 0) != ATLAS_OK) {  // the window follows the camera (nothing to do when it stays)
            std::fprintf(stderr, "update: %s\n", atlas_last_error(ctx));
            return 3;
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
        f.timeSec = float(num(a, "time", 0));
        f.lodBias = float(num(a, "bias", 1));
        // shade=mc: the surfaces' albedo and face normals only (the vanilla look is put together below)
        f.flags = flagsOf(str(a, "flags", mcShade ? "flatnormals" : "shadows,atmos,clouds,sky,actors"));
        if (mcShade) f.flags |= ATLAS_FRAME_DEBUG_ALBEDO | ATLAS_FRAME_FLAT_NORMALS;
        if (groups) f.flags |= ATLAS_FRAME_GROUPS;

        color.assign(size_t(w) * h, 0);
        normal.assign(size_t(w) * h, 0);
        depth.assign(size_t(w) * h, 0.0f);
        linear.assign(size_t(w) * h * 4, 0.0f);
        AtlasFrameOutput o{color.data(), normal.data(), depth.data(), linear.data()};

        // spp=N: N jittered samples a pixel (atlas_set_jitter, Halton 2/3 as atlas_truth's spp), averaged: what the host's
        // TAA converges to (the groups' dithered threshold moves with the jitter), against a truth of as many samples

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
                bool other = interleave && smp == 0 && i < frames && (i & 1);
                uint32_t keepFlags = f.flags;
                if (other) f.flags ^= ATLAS_FRAME_GROUPS;
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
                    bool in = boxed ? hx >= box[0] - 0.5 && hx <= box[3] + 1.5 && hy >= box[1] - 0.5 && hy <= box[4] + 1.5 &&
                                          hz >= box[2] - 0.5 && hz <= box[5] + 1.5
                                    : ex.inExport(hx, hz);
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
            std::printf("{\"frame\":%d,\"out\":\"%s\",\"frameMs\":%.2f}\n", n++, oj.c_str(), best);
        }
        std::fclose(cf);
        std::printf("{\"backend\":\"%s\",\"frames\":%d,\"groupsOn\":%s,\"groupsBuildMs\":%.1f}\n", backendName.c_str(), n,
                    groups ? "true" : "false", groupsMs);
        atlas_destroy(ctx);
        return 0;
    }
    if (int rc = renderOne(out, x, y, z, num(a, "yaw", 0), num(a, "pitch", mcCam ? 0 : -4), num(a, "fov", 70), w, h)) return rc;
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
    std::printf("{\"backend\":\"%s\",\"terrain\":\"%s\",\"camera\":[%.1f,%.1f,%.1f],\"ground\":%.1f,\"w\":%u,\"h\":%u,"
                "\"regions\":%u,\"nodeBytes\":%llu,\"leafBytes\":%llu,\"materialBytes\":%llu,\"buildMs\":%.1f,"
                "\"frameMs\":%.2f,\"raySteps\":%llu,\"hitFraction\":%.3f,\"farthestHit\":%.0f,\"out\":\"%s\"%s%s}\n",
                backendName.c_str(), haveExport ? "export+preview" : "preview", x, y, z, ground, w, h, st.regions,
                (unsigned long long)st.nodeBytes, (unsigned long long)st.leafBytes, (unsigned long long)st.materialBytes, buildMs,
                best, (unsigned long long)st.raySteps, double(hits) / depth.size(), far, outJson.c_str(), extra.c_str(), vsTruth.c_str());
    atlas_destroy(ctx);
    return 0;
}
