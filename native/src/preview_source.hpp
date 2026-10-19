// Offline-harness terrain: the bundled Tamriel raster with a seed-free stand-in for the seeded detail.
// NOT the game's generator: no seeded noises, hydrology graph, peaks, roads or town pads. In game, column tiles come
// from the Java TamrielHeight path instead. Every harness output that uses this says "preview terrain".
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "world.hpp"

namespace atlas {

// Default palette ids used by the preview source (the game sends its own palette).
enum PreviewMat : uint16_t {
    M_STONE = 1, M_GRASS, M_DIRT, M_SAND, M_SNOW, M_WATER, M_GRAVEL, M_FOLIAGE, M_ASH, M_SANDSTONE, M_MUD,
    M_DRYGRASS, M_CONIFER, M_JUNGLE, M_REDSAND, M_ICE, M_TUNDRA, M_COLDGRASS, M_LUSHGRASS, M_MARSHGRASS,
    // flowing water: 8 directions x 3 speeds (calm, fast, rapids), id = M_FLOW0 + dir * 3 + speed; the kernel reads the
    // flow from the palette entry (applyWaterFlow)
    M_FLOW0, M_SNOWCANOPY = M_FLOW0 + 24, M_COUNT
};
constexpr int FLOW_DIRS = 8, FLOW_SPEEDS = 3;
std::vector<AtlasMaterial> previewPalette();
// Writes the flow (heading radians, speed class) of the flowing-water ids into their palette entries (emisClass.zw).
void applyWaterFlow(std::vector<MaterialGpu>& palette);

class PreviewSource {
public:
    bool load(const std::string& path, std::string& err);
    float height(double x, double z) const;
    std::unique_ptr<Tile> makeTile(int lod, int tx, int tz) const;
    // a tile of the caller's exact geometry (surface, water: TILE^2 each), dressed with the preview materials and
    // forest canopy for its climate, slope and shore
    // grassMat / foliageMat (TILE^2 each, may be null, 0 = keep): the caller's biome-tinted materials for grass-family
    // tops and broadleaf canopy (conifers, snow, sand, rock keep the dressing's)
    std::unique_ptr<Tile> dressTile(int lod, int tx, int tz, const float* surface, const float* water,
                                    const uint16_t* grassMat = nullptr, const uint16_t* foliageMat = nullptr) const;
    // As dressTile, with the caller's surface blocks where it knows them (topMat / fillMat, 0 = the dressing's) and
    // column flags (bit 0 snow lies on the ground: crowns are snowy too; bit 1 the water is frozen over)
    struct Overrides {
        const uint16_t* grassMat = nullptr;
        const uint16_t* foliageMat = nullptr;
        const uint16_t* topMat = nullptr;
        const uint16_t* fillMat = nullptr;
        const uint8_t* flags = nullptr;
    };
    std::unique_ptr<Tile> dressTile(int lod, int tx, int tz, const float* surface, const float* water, const Overrides& o) const;

private:
    struct Sample {
        float elev, coast, rivD, rivHW, lakeSD, water, ridge, rough, crag, dunes, wet, road, rain, heat, slope;
        int region, climate;
    };
    void sample(double x, double z, Sample& s) const;
    float column(double x, double z, const Sample& s) const;
    float cell(int cx, int cz, int ch) const;
    void dress(Tile& t, const Overrides& o) const;
    void dressRows(Tile& t, int jBegin, int jEnd, const Overrides& o) const;
    uint16_t flowMaterial(double x, double z, const Sample& s, float level, const Tile& t, int i, int j) const;

    int w_ = 0, h_ = 0, ch_ = 0, minX_ = 0, minZ_ = 0, cellSize_ = 32;
    std::vector<float> data_;
    std::vector<uint8_t> regions_, climate_;
};

}  // namespace atlas
