// Offline-harness terrain: the bundled Tamriel raster with a seed-free stand-in for the seeded detail.
// NOT the game's generator: no seeded noises, hydrology graph, peaks, roads or town pads. In game, column tiles come
// from the Java TamrielHeight path instead. Every harness output that uses this says "preview terrain".
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "aether/aether.h"
#include "world.hpp"

namespace aether {

// Default palette ids used by the preview source (the game sends its own palette).
enum PreviewMat : uint16_t {
    M_STONE = 1, M_GRASS, M_DIRT, M_SAND, M_SNOW, M_WATER, M_GRAVEL, M_FOLIAGE, M_ASH, M_SANDSTONE, M_MUD,
    M_DRYGRASS, M_CONIFER, M_JUNGLE, M_REDSAND, M_ICE, M_TUNDRA, M_COLDGRASS, M_LUSHGRASS, M_MARSHGRASS, M_COUNT
};
std::vector<AetherMaterial> previewPalette();

class PreviewSource {
public:
    bool load(const std::string& path, std::string& err);
    float height(double x, double z) const;
    std::unique_ptr<Tile> makeTile(int lod, int tx, int tz) const;

private:
    struct Sample {
        float elev, coast, rivD, rivHW, lakeSD, water, ridge, rough, crag, dunes, wet, road, rain, heat, slope;
        int region, climate;
    };
    void sample(double x, double z, Sample& s) const;
    float column(double x, double z, const Sample& s) const;
    float cell(int cx, int cz, int ch) const;

    int w_ = 0, h_ = 0, ch_ = 0, minX_ = 0, minZ_ = 0, cellSize_ = 32;
    std::vector<float> data_;
    std::vector<uint8_t> regions_, climate_;
};

}  // namespace aether
