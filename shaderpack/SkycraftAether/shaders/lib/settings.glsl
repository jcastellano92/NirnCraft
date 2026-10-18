// Skycraft Aether: pack options. Keep PLANET_RADIUS equal to the mod's aether planet radius (config) so the
// rasterised near field and aether's far field curve the same way.
#define CURVATURE              // bend the world over a planet of PLANET_RADIUS blocks
#define PLANET_RADIUS 6371000.0 // [100000.0 400000.0 1000000.0 6371000.0]
#define AETHER_FAR_FIELD        // composite aether's far field (needs the Skycraft mod's aether renderer)
#define AETHER_HANDOFF_BAND 24.0 // [8.0 16.0 24.0 32.0 48.0] blocks over which near hands over to far
#define SHADOWS                 // near-field sun shadows (aether shadows the far field itself)
#define SHADOW_DISTORT 0.85 // [0.7 0.8 0.85 0.9]
#define EXPOSURE 1.0 // [0.6 0.8 1.0 1.2 1.4 1.7 2.0]
#define WATER_ALPHA 0.62 // [0.4 0.5 0.62 0.75 0.9]

const int shadowMapResolution = 2048; // [1024 2048 3072 4096]
const float shadowDistance = 128.0; // [64.0 96.0 128.0 192.0 256.0]
const float sunPathRotation = -25.0; // [-40.0 -30.0 -25.0 -15.0 0.0]
