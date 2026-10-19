// Far lights (aether_set_light_group): kept per group, indexed in 1 km cells with pixel-sized clusters for the far
// distance, and each frame culled, ranked and binned into 32 px screen tiles for the far kernel (farLights).
#pragma once
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

#include "aether/aether.h"
#include "gpu_layout.hpp"

namespace aether {

struct LightIndex;

// What the frame needs to place lights on screen.
struct LightView {
    double cam[3];
    int32_t camInt[3];
    float viewProj[16];  // camera-relative (translation removed), column-major
    uint32_t width, height;
    float pixelAngle;    // radians per pixel
    float nearEnd, maxDist, planetRadius;
    float sunY;
    float hazeDensity;   // aerial extinction per block at the camera's altitude (as the kernel's aerialTransmittance)
};

constexpr uint32_t LIGHT_TILE = 32;     // screen tile (pixels) of the light lists
constexpr uint32_t LIGHTS_PER_TILE = 64;

class Lights {
public:
    void setGroup(uint32_t group, const AetherLight* l, uint32_t n);
    void setBudget(uint32_t maxPerFrame, float clusterDistance);
    // Appends this frame's lights, then the tile table (6 tiles per record: start, count) and the index lists (12 per
    // record), to `out` after whatever it holds; returns the number of lights (0: nothing appended).
    uint32_t frame(const LightView& v, std::vector<ActorGpu>& out);
    size_t total() const;

private:
    mutable std::mutex mu_;
    std::map<uint32_t, std::vector<int64_t>> groupCells_;  // the index cells each group has lights in
    std::shared_ptr<const LightIndex> index_;
    uint32_t maxPerFrame_ = 4096;
    float clusterDistance_ = 6000.0f;
};

}  // namespace aether
