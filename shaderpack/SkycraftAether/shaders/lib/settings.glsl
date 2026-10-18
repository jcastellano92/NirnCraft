// Skycraft Aether: pack options. Keep PLANET_RADIUS equal to the mod's aether planet radius (config) so the
// rasterised near field and aether's far field curve the same way. Profiles (shaders.properties) set these per
// hardware tier: LOW keeps the look's backbone (lighting, fog, far field), ULTRA adds every extra.
#define CURVATURE              // bend the world over a planet of PLANET_RADIUS blocks
#define PLANET_RADIUS 6371000.0 // [100000.0 400000.0 1000000.0 6371000.0]
#define AETHER_FAR_FIELD        // composite aether's far field (needs the Skycraft mod's aether renderer)
#define AETHER_HANDOFF_BAND 24.0 // [8.0 16.0 24.0 32.0 48.0] blocks over which near hands over to far
#define EXPOSURE 1.0 // [0.6 0.8 1.0 1.2 1.4 1.7 2.0]

// shadows
#define SHADOWS                 // near-field sun shadows (aether shadows the far field itself)
#define SHADOW_DISTORT 0.85 // [0.7 0.8 0.85 0.9]
#define SHADOW_TAPS 12 // [4 8 12 20] soft-shadow samples
#define SHADOW_SOFTNESS 1.0 // [0.5 1.0 1.5 2.0]

// materials
#define PBR_MAPS                // labPBR normal and specular maps when the resource pack has them
#define SPECULAR                // sun and sky highlights (GGX), widened with distance so far ones do not shimmer
#define FOLIAGE_SSS             // sunlight through leaves and grass
#define SSAO                    // screen-space ambient occlusion
#define SSAO_SAMPLES 6 // [4 6 8 12]

// motion
#define WAVING_FOLIAGE          // leaves and plants sway in the wind, rooted where they grow
#define WIND_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0]

// water
#define WATER_WAVES 2 // [0 1 2 3] 0 flat, 3 most octaves
#define WATER_ALPHA 0.62 // [0.4 0.5 0.62 0.75 0.9]
#define REFRACTION              // water, glass and ice bend what is behind them
#define SSR                     // screen-space reflections (sky and aether's far field beyond the screen)
#define SSR_STEPS 20 // [10 16 20 32]
#define SHORE_FOAM              // foam where the water is shallow, lapping with the waves
#define CAUSTICS                // light patterns under water

// atmosphere
#define FOG_LAYERS              // low valley fog, marsh mist and height fog by weather and time of day
#define VOLUMETRIC_LIGHT        // light shafts through clouds, trees and windows
#define VL_SAMPLES 16 // [8 12 16 24 32]
#define STARS                   // stars and the galaxy, turning with the sky
#define AURORA                  // northern lights in cold northern skies at night
#define BLOOM                   // glow around bright lights, lava, fire and magic
#define EMISSIVE_STRENGTH 4.0 // [2.0 3.0 4.0 6.0] brightness of flames and other added light (the mod's fire meshes)
#define FALLBACK_CLOUDS 2 // [0 1 2] the pack's own cloud deck when aether's far field is absent: 0 none, 1 flat, 2 self-shadowed

const int shadowMapResolution = 2048; // [1024 2048 3072 4096]
const float shadowDistance = 128.0; // [64.0 96.0 128.0 192.0 256.0]
const float sunPathRotation = -25.0; // [-40.0 -30.0 -25.0 -15.0 0.0]
