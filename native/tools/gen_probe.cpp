// A quick probe of the client generator's far passes without Java (seconds a try, not a Gradle run): loads a program
// and a map the parity test dumped (AtlasGenParityTest: NIRNCRAFT_GEN_DUMP=<dir> writes prog.bin, map.bin, little-endian
// words), runs atlas_gen_tiles (with blocks) and atlas_gen_far on one tile, and prints what came back. Dev tool.
//   gen_probe <dir> <lod> <tx> <tz> [far]
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

#include "atlas/atlas.h"

static std::vector<uint32_t> load(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    std::vector<uint32_t> w;
    if (!f) return w;
    f.seekg(0, std::ios::end);
    size_t n = size_t(f.tellg()) / 4;
    f.seekg(0);
    w.resize(n);
    f.read(reinterpret_cast<char*>(w.data()), std::streamsize(n * 4));
    return w;
}

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: gen_probe <dir> <lod> <tx> <tz> [far]\n");
        return 2;
    }
    std::string dir = argv[1];
    int lod = std::atoi(argv[2]), tx = std::atoi(argv[3]), tz = std::atoi(argv[4]);
    bool far = argc > 5;
    std::vector<uint32_t> prog = load(dir + "/prog.bin"), map = load(dir + "/map.bin");
    if (prog.empty() || map.empty()) {
        std::fprintf(stderr, "no prog.bin / map.bin in %s\n", dir.c_str());
        return 2;
    }
    AtlasGen* g = nullptr;
    if (atlas_gen_create(ATLAS_BACKEND_VULKAN, &g) != ATLAS_OK) {
        std::fprintf(stderr, "no Vulkan generator\n");
        return 1;
    }
    std::printf("device: %s\n", atlas_gen_device(g));
    if (atlas_gen_set_program(g, prog.data(), uint32_t(prog.size())) != ATLAS_OK ||
        atlas_gen_set_map(g, map.data(), uint32_t(map.size())) != ATLAS_OK) {
        std::fprintf(stderr, "program / map refused: %s\n", atlas_gen_last_error(g));
        return 1;
    }
    const uint32_t cols = 65536;
    std::vector<float> surf(cols), water(cols);
    std::vector<uint32_t> blocks(cols), biomes(cols), flags(1);
    int32_t job[3] = {lod, tx, tz};
    AtlasGenTimes t{};
    auto t0 = std::chrono::steady_clock::now();
    AtlasStatus s = atlas_gen_tiles(g, job, 1, surf.data(), water.data(), blocks.data(), biomes.data(), flags.data(), &t);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::printf("tiles: status %d (%s), kernels %.2f ms, wall %.1f ms, flag %u\n", int(s), s == ATLAS_OK ? "ok" : atlas_gen_last_error(g),
                t.kernelMs, ms, flags[0]);
    if (s == ATLAS_OK)
        for (uint32_t c : {0u, 1u, 255u, 32768u + 128u, 65535u, std::getenv("PROBE_COL") ? uint32_t(std::atoi(std::getenv("PROBE_COL"))) : 0u})
            std::printf("  column %5u: surface %.3f water %.3f top %u under %u biome %u\n", c, surf[c], water[c], blocks[c] & 0xFFFF,
                        blocks[c] >> 16, biomes[c]);
    if (far && s == ATLAS_OK) {
        uint32_t rows = (44u + (1u << lod) - 1u) >> lod, cap = 2000, mixed = 0;
        std::vector<uint32_t> kinds(256 * rows * 2), ids(size_t(cap) * 2048);
        int32_t txz[2] = {tx, tz};
        s = atlas_gen_far(g, lod, txz, 1, 0, kinds.data(), ids.data(), cap, &mixed, flags.data(), &t);
        uint32_t air = 0, uni = 0, mix = 0;
        for (uint32_t k = 0; k < 256 * rows; k++) {
            uint32_t kd = kinds[k * 2];
            if (kd == 1) air++;
            else if (kd == 0) mix++;
            else uni++;
        }
        std::printf("far: status %d (%s), kernels %.2f ms, %u chunks: %u air, %u uniform, %u mixed (counter %u)\n", int(s),
                    s == ATLAS_OK ? "ok" : atlas_gen_last_error(g), t.kernelMs, 256 * rows, air, uni, mix, mixed);
    }
    atlas_gen_destroy(g);
    return s == ATLAS_OK ? 0 : 1;
}
