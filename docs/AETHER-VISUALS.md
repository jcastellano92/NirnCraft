# Skycraft visuals: the full matrix (near field = the Skycraft Aether shader pack, far field = aether)

Every effect is designed for both fields so it carries across the hand-off, and each has a cheaper fallback the
quality controller (adaptive quality v2, docs/ADAPTIVE-QUALITY.md) or the pack profile can drop to. Status: **done**, **building**, *planned*.

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
| Landmark silhouettes, wild landmarks | the game | settlement landmarks captured from their own builders (AetherCapture: exact spans, floating where they do); named wild sites (forts, towers, shrines, wonders, entrances) from the builders' dimensions (AetherSites); seeded natural landmarks sent by the server (AetherNatural) | profile boxes | **done** (in-game unverified) |
| Biome surface blocks | the game | biome tints **done**; the world's own material rules per far column, snow and ice (AetherSurface, tile codec v3) | raster climate dressing | **done** (in-game unverified: the `aether surface check` log line) |
| Sun shadows | shadow map, soft PCF, entities, foliage alpha | traced, same LOD | off far | **done**; softer near *planned* |
| Mountain shadows on the near field | sun map | traced | 512 / 256 map | **done** (in-game look unverified) |
| Cloud shadows | sun map (cloud transmittance) | traced | off | **done** (near in-game unverified) |
| PBR (labPBR normal / specular, default per material) | GGX sun + sky, roughness by material | roughness widened with distance (no shimmer) | Blinn, no maps | *planned* |
| Emissive and coloured light: lava, lamps, fire, magic | emissive maps; block light coloured by its emitter (the mod's emitter cache) and flickering for flames; the mod's point lights (spells, atronachs); additive HDR flames; bloom | far lights (aether_set_light_group): a pixel-wide core and a haze halo; lava light over Red Mountain's caldera | white block light, no bloom | near **done**, far **done** |
| Long-distance lights (towns at night, lava fields) | - | lit windows per facade, street lamps, braziers, wall lanterns, gate torches, camp fires, landmark light blocks, lava; dusk to dawn; budget per frame (adaptive: aether's LOD knob), clusters beyond 6 km | fewer lights / off | **done** (in-game unverified) |
| Water waves by body | water map: ocean swell, lake chop, calm ponds, wind and storm amplitude | same wave field, filtered by distance | static normals | *planned* |
| Shore lapping, foam | water map shore distance, animated | foam band on far shores | none | *planned* |
| Rivers, rapids, waterfalls | flow from the flowing sprite's basis + face slope: advected waves, rapids foam on steep / fast runs, white water streaming down falls, spray and mist where a fall meets the pool | rivers flow (8 headings x calm / fast / rapids from the channel and the water levels): waves advected downstream, white water on fast runs and rapids; Hydrology's falls as white streaming sheets with sunlit spray | static | near **done** (in-game unverified), far **done** (in-game unverified) |
| Reflections | sky + screen-space + aether far field | traced (**done**) | sky only | near *planned* |
| Refraction (water, glass, ice) | screen-space offset by normal, tint, IOR | - | tint only | *planned* |
| Underwater | depth absorption fog, caustics from the wave field, light shafts | - | fog only | *planned* |
| Light shafts | sun and moon shafts through clouds, trees, windows (screen space + sun map) | cloud-shaft term in the aerial perspective | off | *planned* |
| Fog | sealed local air (lib/media.glsl): the mod's ground-fog drive + dawn / night / wet mists only where the sky reaches (roof cache, terrain), never in houses or caves; dungeon air with a low creeping floor layer; lamp and point-light halos; closed form beyond 128 blocks | same in the aerial perspective | 4-step march, no halos | far **done** (haze), near **done** |
| Rain, snow, ash, dust | rain mist under rain bands, blowing snow, ash haze by the weather's aerosol band (outdoors only); rain and particles shaded and fogged forward | rain shafts and curtains (**done**), snow / dust haze | fewer layers | near **done** (air), weather pass *planned* |
| Dust motes, embers | screen-space particles in light shafts, embers near lava | - | off | *planned* |
| Wind on foliage | rooted waving (leaves, grass, crops), gusts from the weather wind | far canopy sway in the normal | off | *planned* |
| Sky: stars, Milky Way, moon phases, aurora | Masser and Secunda on their orbits with phase atlases (the mod's sky state), stars veiled by moon glare and light pollution, the mod's live aurora and its light on the ground; nights keyed and shadowed by the brighter moon; the pack's own stars / aurora without the mod | the same sky behind the far field | pack sky, vanilla moon | near **done**, far *planned* |
| Volcanic glow, ash sky | the Red Mountain column from the mod's smoke source 0 (bounded volume, wind-leaning, vent glow at night), ash haze in volcanic air | the citadel's lava moat and the caldera's glow above the rim (far lights); clouds lit from below *planned* | off | near **done**, far **done** (glow) |
| Storm light (lightning) | the mod's discharge packets light facing ground and walls, the rain and mist round the channel and the sky over it; outdoors only (no flashes underground) | - | off | near **done** |
| Smoke columns (fires) | the mod's sprite smoke, shaded as a lit volume and fogged; volumes from smoke sources opt-in (SMOKE_FIRE_VOLUMES) | - | sprites | **done** |
| Clouds without aether | a weather-driven fallback deck with its own ground shadow, only when aether's frame is absent | - | flat deck | **done** |
| Mountain sunsets without aether | terrain transmission over the mod's surface heights from each receiver; sun disc and shafts set behind sun_horizon | sun map | off | **done** |
| Realms | shaders/realm/ (dimension.properties): the realm's own sky dome, its authored fog and hue, local media only | - | - | **done** (in-game unverified) |
| Far creatures (dragons, ships, players) | the game's models within tracking range | impostors beyond, dissolving out while the client also tracks the real entity (entity id in the packet), dragon wings beating / gliding / folded | fewer | **done** (in-game unverified) |
| LOD transitions | - | per-pixel LOD segment phase (a stipple band, no pop line); refined tiles, new buildings and finer LODs dissolve in over 0.6 s (aether_set_transitions) | hard switch | **done** |
| Underground | lightmap, block light | off (near field covers it: near-zero cost) | - | **done** |
| High altitude | - | 7 LODs to ~40 km, curvature; horizon fog | fewer LODs | **done** |
| Hardware scaling | adaptive quality v2: continuous knobs (taps, samples, steps, waves, far detail) in quality.png; profiles are ceilings | the same controller: resolution, LOD bias, clouds, shadows, fallback tiers | far field off | **done** (docs/ADAPTIVE-QUALITY.md) |
| World load | - | the whole map at LOD 2-6 baked once per world on the server, shared with every player | - | *planned* |
