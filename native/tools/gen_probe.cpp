// A quick probe of the client generator's far passes without Java (seconds a try, not a Gradle run): loads a program
// and a map the parity test dumped (AtlasGenParityTest: NIRNCRAFT_GEN_DUMP=<dir> writes prog.bin, map.bin, little-endian
// words), runs atlas_gen_tiles (with blocks) and atlas_gen_far on one tile, and prints what came back. Dev tool.
//   gen_probe <dir> <lod> <tx> <tz> [far]
//   gen_probe <dir> stage      (a stage, atlas_gen_add_stage, fills a layer of chunk 0, 0: the batch must carry it)
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

#include "atlas/atlas.h"

#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#undef far
#undef near
#endif

// (stage check) the two Vulkan calls the test stage needs, without the SDK's headers
typedef void (*FillBufferFn)(void* cmd, uint64_t buffer, uint64_t offset, uint64_t size, uint32_t data);
typedef void* (*GetDeviceProcAddrFn)(void* device, const char* name);
static FillBufferFn g_fill = nullptr;
static int32_t testStage(void*, const AtlasGenStageBatch* b) {
    // chunk 0's layer y 200 (words x + 16 * (z + 16 * (y + 64))) to generator id 1 (the default block)
    uint64_t at = (uint64_t(200 + 64) * 256) * 4;
    g_fill(b->vkCommandBuffer, b->blocks, at, 256 * 4, 1u);
    return 0;
}

static int stageCheck(AtlasGen* g) {
#ifdef _WIN32
    HMODULE vk = LoadLibraryA("vulkan-1.dll");
    auto gdpa = vk ? reinterpret_cast<GetDeviceProcAddrFn>(GetProcAddress(vk, "vkGetDeviceProcAddr")) : nullptr;
#else
    GetDeviceProcAddrFn gdpa = nullptr;
#endif
    AtlasGenDevice d{};
    if (!gdpa || atlas_gen_device_handles(g, &d) != ATLAS_OK) {
        std::fprintf(stderr, "no device handles\n");
        return 1;
    }
    g_fill = reinterpret_cast<FillBufferFn>(gdpa(d.vkDevice, "vkCmdFillBuffer"));
    uint32_t id = 0;
    if (atlas_gen_add_stage(g, ATLAS_GEN_STAGE_DECORATION + 4, testStage, nullptr, &id) != ATLAS_OK) {
        std::fprintf(stderr, "add stage: %s\n", atlas_gen_last_error(g));
        return 1;
    }
    int32_t xz[2] = {0, 0};
    std::vector<uint32_t> ids(40 * 2048), flags(1);
    AtlasGenTimes t{};
    AtlasStatus s = atlas_gen_chunks(g, xz, 1, 0, ids.data(), flags.data(), &t);
    // section of y 200: (200 + 64) / 16 = 16, its layer y 200 & 15 = 8
    uint32_t sec = 16, ly = 8, ones = 0;
    for (uint32_t b = ly * 256; b < ly * 256 + 256; b++) {
        uint32_t w = ids[sec * 2048 + b / 2];
        if (((b & 1) ? w >> 16 : w & 0xFFFF) == 1u) ones++;
    }
    std::printf("stage check: status %d, the layer's blocks from the stage %u of 256 (kernels %.1f ms)\n", int(s), ones, t.kernelMs);
    atlas_gen_remove_stage(g, id);
    s = atlas_gen_chunks(g, xz, 1, 0, ids.data(), flags.data(), &t);
    ones = 0;
    for (uint32_t b = ly * 256; b < ly * 256 + 256; b++) {
        uint32_t w = ids[sec * 2048 + b / 2];
        if (((b & 1) ? w >> 16 : w & 0xFFFF) == 1u) ones++;
    }
    std::printf("stage removed: status %d, %u of 256 (the terrain's own)\n", int(s), ones);
    return 0;
}

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
    if (argc < 3) {
        std::fprintf(stderr, "usage: gen_probe <dir> <lod> <tx> <tz> [far] | gen_probe <dir> stage\n");
        return 2;
    }
    std::string dir = argv[1];
    bool stage = std::string(argv[2]) == "stage";
    if (!stage && argc < 5) return 2;
    int lod = stage ? 0 : std::atoi(argv[2]), tx = stage ? 0 : std::atoi(argv[3]), tz = stage ? 0 : std::atoi(argv[4]);
    bool wantFar = argc > 5;
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
    if (stage) {
        int r = stageCheck(g);
        atlas_gen_destroy(g);
        return r;
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
    if (s == ATLAS_OK) {
        // a checksum of the whole tile (FNV-1a over its words): before / after a change to the passes
        uint64_t h = 1469598103934665603ull;
        auto mix = [&](const void* p, size_t n) {
            const uint8_t* b = static_cast<const uint8_t*>(p);
            for (size_t i = 0; i < n; i++) h = (h ^ b[i]) * 1099511628211ull;
        };
        mix(surf.data(), cols * 4);
        mix(water.data(), cols * 4);
        mix(blocks.data(), cols * 4);
        mix(biomes.data(), cols * 4);
        std::printf("  tile checksum %016llx\n", (unsigned long long)h);
    }
    if (s == ATLAS_OK)
        for (uint32_t c : {0u, 1u, 255u, 32768u + 128u, 65535u, std::getenv("PROBE_COL") ? uint32_t(std::atoi(std::getenv("PROBE_COL"))) : 0u})
            std::printf("  column %5u: surface %.3f water %.3f top %u under %u biome %u\n", c, surf[c], water[c], blocks[c] & 0xFFFF,
                        blocks[c] >> 16, biomes[c]);
    if (wantFar && s == ATLAS_OK) {
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
        uint64_t h = 1469598103934665603ull;
        for (uint32_t k = 0; k < 256 * rows * 2; k += 2) {
            h = (h ^ kinds[k]) * 1099511628211ull;
            if (kinds[k] == 0 && kinds[k + 1] < cap)  // a mixed chunk: its voxels (slots come in any order)
                for (size_t v = 0; v < 2048; v++) h = (h ^ ids[size_t(kinds[k + 1]) * 2048 + v]) * 1099511628211ull;
        }
        std::printf("  far checksum %016llx (kinds and the mixed chunks' voxels in chunk order)\n", (unsigned long long)h);
        std::printf("far: status %d (%s), kernels %.2f ms, %u chunks: %u air, %u uniform, %u mixed (counter %u)\n", int(s),
                    s == ATLAS_OK ? "ok" : atlas_gen_last_error(g), t.kernelMs, 256 * rows, air, uni, mix, mixed);
    }
    atlas_gen_destroy(g);
    return s == ATLAS_OK ? 0 : 1;
}
