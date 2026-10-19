// NirnCraft Atlas: pack options. Keep PLANET_RADIUS equal to the mod's atlas planet radius (config) so the
// rasterised near field and atlas's far field curve the same way. Profiles (shaders.properties) set these per
// hardware tier: LOW keeps the look's backbone (lighting, fog, far field), ULTRA adds every extra.
#define CURVATURE              // bend the world over a planet of PLANET_RADIUS blocks
#define PLANET_RADIUS 6371000.0 // [100000.0 400000.0 1000000.0 6371000.0]
#define ATLAS_FAR_FIELD        // composite atlas's far field (needs the NirnCraft mod's atlas renderer)
#define ATLAS_HANDOFF_BAND 48.0 // [8.0 16.0 24.0 32.0 48.0 64.0] blocks over which near hands over to far (without the mod: with it the mod's value)
#define EXPOSURE 1.0 // [0.6 0.8 1.0 1.2 1.4 1.7 2.0]
#define AUTO_EXPOSURE           // the eye adapts: dark caves and nights opened up, the bright outside of a cave blown out
#define NIGHT_VISION 2 // [0 1 2 3] dim light loses its colour to the eye's rods and leans blue (Purkinje)
#define REGION_GRADE            // a film grade per land (baked LUTs): Skyrim's cold pines, Hammerfell's gold, Morrowind's ash ...
#define GRADE_STRENGTH 1.0 // [0.0 0.25 0.5 0.75 1.0 1.25]

// adaptive quality (lib/quality.glsl, docs/ADAPTIVE-QUALITY.md): the mod measures this machine and scales the counts
// below at runtime (they are ceilings). The ADAPTIVE profile (these defaults) carries ULTRA's ceilings and, without the
// mod's controller, runs at QUALITY_FALLBACK of them (0.65: about HIGH); the fixed profiles run their own counts (1.0).
#define ADAPTIVE_QUALITY        // follow the mod's quality controller (off: the fixed counts, QUALITY_FALLBACK of them)
#define QUALITY_FALLBACK 0.65 // [0.65 1.0] share of the counts used without the controller

// shadows
#define SHADOWS                 // near-field sun shadows (atlas shadows the far field itself)
#define SHADOW_DISTORT 0.85 // [0.7 0.8 0.85 0.9]
#define SHADOW_TAPS 20 // [4 8 12 20] soft-shadow samples (the most; adaptive quality uses fewer when it must)
#define SHADOW_SOFTNESS 1.0 // [0.5 1.0 1.5 2.0]

// materials
#define PBR_MAPS                // labPBR normal and specular maps when the resource pack has them
#define POM                     // parallax occlusion from the labPBR height map, up close (needs PBR_MAPS)
#define POM_STEPS 24 // [8 12 16 24 32] height-map steps (the most; fewer with the SSAO knob and towards POM_DISTANCE)
#define POM_DEPTH 0.12 // [0.06 0.09 0.12 0.18 0.25] how deep the height map reaches, in blocks
#define POM_DISTANCE 24.0 // [8.0 12.0 16.0 24.0 32.0] blocks from the eye where the parallax has faded out
#define SPECULAR                // sun and sky highlights (GGX), widened with distance so far ones do not shimmer
#define FOLIAGE_SSS             // sunlight through leaves and grass
#define SSAO                    // screen-space ambient occlusion
#define SSAO_SAMPLES 12 // [4 6 8 12]
#define COLORED_LIGHT           // block light takes the colour of what emits it (the mod's emitter cache, ~26 blocks)
#define BLOCKLIGHT_FLICKER 1 // [0 1 2] torch and fire light flickers (0 steady, 2 strong)
#define SUN_HORIZON_TAPS 6 // [0 4 6] without atlas's sun map: low sun blocked by distant mountains (terrain taps)

// motion
#define WAVING_FOLIAGE          // leaves and plants sway in the wind, rooted where they grow
#define WIND_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0]

// water
#define WATER_WAVES 3 // [0 1 2 3] 0 flat, 3 most octaves
#define WATER_ALPHA 0.62 // [0.4 0.5 0.62 0.75 0.9]
#define REFRACTION              // water, glass and ice bend what is behind them
#define SSR                     // screen-space reflections (sky and atlas's far field beyond the screen)
#define SSR_STEPS 32 // [10 16 20 32]
#define SHORE_FOAM              // foam where the water is shallow, lapping with the waves
#define CAUSTICS                // light patterns under water
#define WATER_FLOW              // flowing water: waves ride the flow, rapids break white, falls stream white water
#define FALLS_MIST              // spray and mist where a fall meets the water below

// atmosphere
#define FOG_LAYERS              // low valley fog, marsh mist and height fog by weather and time of day
#define MEDIA_STEPS 16 // [0 4 6 10 14 16] the near air march: fog sealed out of houses and caves, dungeon air, halos
#define HALO_STEPS 12 // [0 6 8 12 16] lamps, torches, fires and spells glow in the fog around them (0: off)
#define PLUME_STEPS 16 // [0 6 10 16] smoke columns: the Red Mountain volcano (and fires with the option below)
#define SMOKE_FIRE_VOLUMES 12 // [0 2 4 8 12 23] fires' smoke columns as lit volumes, the strongest first (0: the mod's sprites only)
#define LIGHTNING_LIGHT 1 // [0 1] storm channels light the ground, the rain and the clouds (outdoors only)
//#define LIGHT_OCCLUSION        // the mod's point lights are shadowed by walls (needs -Dnirncraft.localLightOcclusion)
#define VOLUMETRIC_LIGHT        // light shafts through clouds, trees, windows, doors, cave mouths and ceiling holes
#define SHAFT_MARCH             // shafts from real occlusion (shadow map + atlas's sun map); off: screen-space only
#define VL_SAMPLES 20 // [6 8 12 16 20 24 32] shaft steps (the most; adaptive quality uses fewer when it must)
#define STARS                   // stars and the galaxy, turning with the sky
#define AURORA                  // northern lights in cold northern skies at night
#define WEATHER_SKY             // the sky greys and the sky light dims under the mod's storm cells and overcast
#define MOD_SKY                 // Nirn's sky from the mod: Masser and Secunda, the live aurora, the mod's sky options
#define LUNAR_KEY               // nights lit (and shadowed) by the brighter moon, Masser or Secunda
#define BLOOM                   // glow around bright lights, lava, fire and magic
#define EMISSIVE_STRENGTH 4.0 // [2.0 3.0 4.0 6.0] brightness of flames and other added light (the mod's fire meshes)
#define FALLBACK_CLOUDS 2 // [0 1 2] the pack's own cloud deck when atlas's far field is absent: 0 none, 1 flat, 2 self-shadowed

const int shadowMapResolution = 2048; // [1024 2048 3072 4096]
const float shadowDistance = 128.0; // [64.0 96.0 128.0 192.0 256.0]
const float sunPathRotation = -25.0; // [-40.0 -30.0 -25.0 -15.0 0.0]

// Iris lists a #define as a toggle in the settings menu only when some #ifdef / #ifndef tests it; these are otherwise
// tested with `#if defined X && ...` only
#ifdef VOLUMETRIC_LIGHT
#endif
#ifdef POM
#endif
#ifdef LUNAR_KEY
#endif
