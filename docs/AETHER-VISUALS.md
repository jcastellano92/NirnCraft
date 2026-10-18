# Skycraft visuals: the full matrix (near field = the Skycraft Aether shader pack, far field = aether)

Every effect is designed for both fields so it carries across the hand-off, and each has a cheaper fallback the
quality controller (`AetherQuality`) or the pack profile can drop to. Status: **done**, **building**, *planned*.

Shared inputs, computed once and used by both fields:

| Input | What | Status |
| --- | --- | --- |
| Sun map | sun-aligned occlusion over ~4 km around the player, traced through the far field, refreshed progressively (~0.35 ms); plus cloud transmittance | **done** |
| Water map | per column near the player: water kind (ocean / lake / pond / river / marsh), flow direction and speed, depth, distance to shore, rapids and waterfalls (Hydrology) | *planned* |
| Weather fields | WeatherFieldTexture's coverage, storms, curtains, aerosol (rain / snow / ash / dust), wind | **done** (far); near *planned* |

| Effect | Near field (pack) | Far field (aether) | Scales down to | Status |
| --- | --- | --- | --- | --- |
| Terrain to the horizon | rasterised | SVDAG clipmap, curved planet | lower internal resolution | **done** |
| Exact world shape | the game | server's own height function, MP stream | stand-in only | **done** |
| Towns, walls, forts | the game | StreetPlan / fort massing | coarser sampling | **done** |
| Landmark silhouettes, wild landmarks | the game | per-landmark profiles; seeded wild landmarks | boxes | *planned* |
| Biome surface blocks | the game | biome tints **done**; per-biome surface blocks | climate dressing | *planned* |
| Sun shadows | shadow map, soft PCF, entities, foliage alpha | traced, same LOD | off far | **done**; softer near *planned* |
| Mountain shadows on the near field | sun map | traced | 512 / 256 map | **done** (in-game look unverified) |
| Cloud shadows | sun map (cloud transmittance) | traced | off | **done** (near in-game unverified) |
| PBR (labPBR normal / specular, default per material) | GGX sun + sky, roughness by material | roughness widened with distance (no shimmer) | Blinn, no maps | *planned* |
| Emissive and coloured light: lava, lamps, fire, magic | emissive maps + block light colour by light source | emissive classes glow through haze; lava light over volcanoes | no bloom | *planned* |
| Long-distance lights (towns at night, lava fields) | - | emissive massing windows / lava, visible for km | off | *planned* |
| Water waves by body | water map: ocean swell, lake chop, calm ponds, wind and storm amplitude | same wave field, filtered by distance | static normals | *planned* |
| Shore lapping, foam | water map shore distance, animated | foam band on far shores | none | *planned* |
| Rivers, rapids, waterfalls | flow-advected normals, white water on steep flow, mist at falls | flow tint, white water, falls as bright sheets | static | *planned* |
| Reflections | sky + screen-space + aether far field | traced (**done**) | sky only | near *planned* |
| Refraction (water, glass, ice) | screen-space offset by normal, tint, IOR | - | tint only | *planned* |
| Underwater | depth absorption fog, caustics from the wave field, light shafts | - | fog only | *planned* |
| Light shafts | sun and moon shafts through clouds, trees, windows (screen space + sun map) | cloud-shaft term in the aerial perspective | off | *planned* |
| Fog | height fog, valley / low-level fog by weather, time and wetness, marsh mist | same in the aerial perspective | linear fog | far **done** (haze), near *planned* |
| Rain, snow, ash, dust | weather pass by aerosol kind, wind slant, splashes, snow sparkle | rain shafts and curtains (**done**), snow / dust haze | fewer layers | *planned* |
| Dust motes, embers | screen-space particles in light shafts, embers near lava | - | off | *planned* |
| Wind on foliage | rooted waving (leaves, grass, crops), gusts from the weather wind | far canopy sway in the normal | off | *planned* |
| Sky: stars, Milky Way, moon phases, aurora | stars rotate with time, twinkle by humidity; aurora in the north and frozen regions at night | the same sky behind the far field | fewer octaves | *planned* |
| Volcanic glow, ash sky | Red Mountain / ash regions: ash aerosol, ember glow on clouds from lava | lava emissive, clouds lit from below | off | *planned* |
| Far creatures (dragons, ships, players) | the game's models within tracking range | impostors beyond, cross-fading at the boundary, wing flaps | fewer | impostors **done**; fades *planned* |
| LOD transitions | - | dithered blend across LOD boundaries | hard switch | *planned* |
| Underground | lightmap, block light | off (near field covers it: near-zero cost) | - | **done** |
| High altitude | - | 7 LODs to ~40 km, curvature; horizon fog | fewer LODs | **done** |
| Hardware scaling | pack profiles LOW / MEDIUM / HIGH / ULTRA | AetherQuality: resolution, clouds, shadows | - | far **done**, pack *planned* |
| World load | - | the whole map at LOD 2-6 baked once per world on the server, shared with every player | - | *planned* |
