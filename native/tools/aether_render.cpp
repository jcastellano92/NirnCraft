// Offline far-field renderer: preview terrain from the Tamriel raster, rendered through aether_core.
//   aether_render raster=native/data/tamriel_raster.bin out=shot.png x=0 z=0 above=40 yaw=0 pitch=-4 fov=70
//                 w=1280 h=720 backend=cpu|vulkan sun=35,120 radius=6371000 near=0 far=40000 flags=shadows,atmos
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

uint32_t flagsOf(const std::string& s) {
    uint32_t f = 0;
    if (s.find("clouds") != std::string::npos) f |= AETHER_FRAME_CLOUDS;
    if (s.find("shadows") != std::string::npos) f |= AETHER_FRAME_SHADOWS;
    if (s.find("atmos") != std::string::npos) f |= AETHER_FRAME_ATMOS;
    if (s.find("actors") != std::string::npos) f |= AETHER_FRAME_ACTORS;
    if (s.find("lod") != std::string::npos) f |= AETHER_FRAME_DEBUG_LOD;
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
    f.flags = flagsOf(str(a, "flags", "shadows,atmos"));

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
