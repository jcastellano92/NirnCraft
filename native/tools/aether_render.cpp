// Offline far-field renderer: preview terrain from the Tamriel raster, rendered through aether_core.
//   aether_render raster=native/data/tamriel_raster.bin out=shot.png x=0 z=0 above=40 yaw=0 pitch=-4 fov=70
//                 w=1280 h=720 backend=cpu|vulkan sun=35,120 radius=6371000 near=0 far=40000 flags=shadows,atmos,clouds
//                 weather=clear|fair|overcast|storm stormx= stormz= time=<seconds>
// yaw: degrees, 0 = north (-Z), 90 = east (+X). sun=elevation,azimuth degrees (azimuth like yaw).
// Also writes <out>_depth.png (log depth) and <out>_lod.png when lodpass=1, and prints JSON stats on stdout.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "camera.hpp"
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

void syntheticWeather(AetherContext* ctx, const std::string& preset, double camX, double camZ, double stormX, double stormZ) {
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
                double ground = aether_preview_height(ctx, x, z);
                double base = 232 + 22 * vnoise(x / 3000, z / 3000, 5);
                uint32_t field = rgba(cov, tower, curtain, 0.02);
                uint32_t dyn = rgba(base / 1024.0, std::fmax(ground, 63.0) / 1024.0, windAngle, 0.0);
                (layer == 0 ? nf : ff)[size_t(j) * 128 + i] = field;
                (layer == 0 ? nd : fd)[size_t(j) * 128 + i] = dyn;
            }
    }
    AetherWeather w{nf.data(), nd.data(), ff.data(), fd.data(), preset == "storm" ? 0.3f : 0.0f};
    aether_set_weather(ctx, &w);
}

uint32_t flagsOf(const std::string& s) {
    uint32_t f = 0;
    if (s.find("clouds") != std::string::npos) f |= AETHER_FRAME_CLOUDS;
    if (s.find("shadows") != std::string::npos) f |= AETHER_FRAME_SHADOWS;
    if (s.find("atmos") != std::string::npos) f |= AETHER_FRAME_ATMOS;
    if (s.find("actors") != std::string::npos) f |= AETHER_FRAME_ACTORS;
    if (s.find("lod") != std::string::npos) f |= AETHER_FRAME_DEBUG_LOD;
    if (s.find("sky") != std::string::npos) f |= AETHER_FRAME_SKY;
    if (s.find("quarter") != std::string::npos) f |= AETHER_FRAME_CLOUDS_QUARTER;
    if (s.find("flatnormals") != std::string::npos) f |= AETHER_FRAME_FLAT_NORMALS;
    if (s.find("dbgweather") != std::string::npos) f |= AETHER_FRAME_DEBUG_WEATHER;
    return f;
}

}  // namespace

int main(int argc, char** argv) {
    auto a = parse(argc, argv);
    std::string raster = str(a, "raster", "native/data/tamriel_raster.bin");
    std::string out = str(a, "out", "aether.png");
    std::string backendName = str(a, "backend", "cpu");
    uint32_t w = uint32_t(num(a, "w", 1280)), h = uint32_t(num(a, "h", 720));
    double x = num(a, "x", 0), z = num(a, "z", 0);
    int lods = int(num(a, "lods", 7));

    AetherContext* ctx = nullptr;
    AetherBackend be = backendName == "vulkan" ? AETHER_BACKEND_VULKAN : AETHER_BACKEND_CPU;
    if (aether_create(be, &ctx) != AETHER_OK) {
        std::fprintf(stderr, "aether_create(%s) failed\n", backendName.c_str());
        return 2;
    }
    if (aether_preview_load_raster(ctx, raster.c_str()) != AETHER_OK) {
        std::fprintf(stderr, "%s\n", aether_last_error(ctx));
        return 2;
    }
    double ground = aether_preview_height(ctx, x, z);
    double y = a.count("y") ? num(a, "y", 100) : std::max(ground, 63.0) + num(a, "above", 40);
    double centre[3] = {x, y, z};

    // preview tiles for every LOD window (2 regions of slack either side, matching World::windowRadius)
    for (int lod = 0; lod < lods; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        aether_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span);
    }
    if (aether_update(ctx, centre, 0) != AETHER_OK) {
        std::fprintf(stderr, "update: %s\n", aether_last_error(ctx));
        return 3;
    }
    AetherStats st{};
    aether_get_stats(ctx, &st);
    double buildMs = st.lastBuildMs;

    if (str(a, "actors", "") == "demo") {
        // a caravan walking away along the view, a mammoth beside it, a dragon circling further out
        double yaw = num(a, "yaw", 0) * PI / 180, fx = std::sin(yaw), fz = -std::cos(yaw), sx = std::cos(yaw), sz = std::sin(yaw);
        std::vector<AetherActor> act;
        auto put = [&](double along, double side, double lift, float h, float r, uint32_t kind, uint32_t tint, uint32_t flags = 0) {
            AetherActor q{};
            q.pos[0] = x + fx * along + sx * side;
            q.pos[2] = z + fz * along + sz * side;
            q.pos[1] = std::fmax(aether_preview_height(ctx, q.pos[0], q.pos[2]), 63.0) + lift;
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
        if (str(a, "dragon", "") == "near") put(120, 0, 20, 5.0f, 7.0f, 2, 0xFF283038u, AETHER_GAIT_FLAP << 8);
        else put(600, -120, 55, 5.0f, 7.0f, 2, 0xFF283038u, AETHER_GAIT_FLAP << 8);
        aether_set_actors(ctx, act.data(), uint32_t(act.size()));
    }

    std::string preset = str(a, "weather", "fair");
    {
        std::string noisePath = str(a, "noise", "native/data/cloud_noise.bin");
        std::vector<uint32_t> noise(64 * 64 * 64);
        FILE* nfp = std::fopen(noisePath.c_str(), "rb");
        if (nfp && std::fread(noise.data(), 4, noise.size(), nfp) == noise.size()) aether_set_cloud_noise(ctx, noise.data());
        else std::fprintf(stderr, "no cloud noise at %s (run native/tools/export_cloud_noise.py); clouds will be empty\n", noisePath.c_str());
        if (nfp) std::fclose(nfp);
        double sx = num(a, "stormx", x + 9000), sz = num(a, "stormz", z - 6000);
        syntheticWeather(ctx, preset, x, z, sx, sz);
    }

    AetherFrame f{};
    f.cameraPos[0] = x;
    f.cameraPos[1] = y;
    f.cameraPos[2] = z;
    aether::cameraMatrix(num(a, "yaw", 0), num(a, "pitch", -4), num(a, "fov", 70), double(w) / h, f.invViewProj);
    f.width = w;
    f.height = h;
    f.nearFieldEnd = float(num(a, "near", 0));
    f.maxDistance = float(num(a, "far", 40000));
    f.planetRadius = float(num(a, "radius", 6371000));
    double se = 35, sa = 120;
    if (a.count("sun")) std::sscanf(a["sun"].c_str(), "%lf,%lf", &se, &sa);
    se *= PI / 180;
    sa *= PI / 180;
    f.sunDir[0] = float(std::sin(sa) * std::cos(se));
    f.sunDir[1] = float(std::sin(se));
    f.sunDir[2] = float(-std::cos(sa) * std::cos(se));
    f.timeSec = float(num(a, "time", 0));
    f.lodBias = float(num(a, "bias", 1));
    f.flags = flagsOf(str(a, "flags", "shadows,atmos,clouds,sky,actors"));

    std::vector<uint32_t> color(size_t(w) * h), normal(size_t(w) * h);
    std::vector<float> depth(size_t(w) * h);
    AetherFrameOutput o{color.data(), normal.data(), depth.data()};
    int frames = int(num(a, "frames", 1));
    double best = 1e30;
    for (int i = 0; i < frames; i++) {
        if (aether_render(ctx, &f, &o) != AETHER_OK) {
            std::fprintf(stderr, "render: %s\n", aether_last_error(ctx));
            return 4;
        }
        aether_get_stats(ctx, &st);
        best = std::min(best, st.lastFrameMs);
    }
    aether::writePng(out, color.data(), w, h);
    if (num(a, "depthpng", 0) > 0) {
        std::vector<uint32_t> d(size_t(w) * h);
        for (size_t i = 0; i < d.size(); i++) {
            float t = depth[i] > 1e29f ? 0.0f : float(1.0 - std::log2(1.0 + depth[i]) / std::log2(1.0 + f.maxDistance));
            uint32_t v = uint32_t(std::fmax(0.0f, std::fmin(1.0f, t)) * 255);
            d[i] = v | v << 8 | v << 16 | 0xFF000000u;
        }
        aether::writePng(out.substr(0, out.size() - 4) + "_depth.png", d.data(), w, h);
    }
    size_t hits = 0;
    double far = 0;
    for (float d : depth)
        if (d < 1e29f) {
            hits++;
            far = std::max(far, double(d));
        }
    std::printf("{\"backend\":\"%s\",\"terrain\":\"preview\",\"camera\":[%.1f,%.1f,%.1f],\"ground\":%.1f,\"w\":%u,\"h\":%u,"
                "\"regions\":%u,\"nodeBytes\":%llu,\"leafBytes\":%llu,\"materialBytes\":%llu,\"buildMs\":%.1f,"
                "\"frameMs\":%.2f,\"raySteps\":%llu,\"hitFraction\":%.3f,\"farthestHit\":%.0f,\"out\":\"%s\"}\n",
                backendName.c_str(), x, y, z, ground, w, h, st.regions, (unsigned long long)st.nodeBytes,
                (unsigned long long)st.leafBytes, (unsigned long long)st.materialBytes, buildMs, best,
                (unsigned long long)st.raySteps, double(hits) / depth.size(), far, out.c_str());
    aether_destroy(ctx);
    return 0;
}
