# NirnCraft visuals: the full matrix (near field = the InfiniteATLAS shader pack, far field = atlas_core)

Every effect is designed for both fields so it carries across the hand-off, and each has a cheaper fallback the
quality controller (adaptive quality v2, docs/ADAPTIVE-QUALITY.md) or the pack profile can drop to. Status: **done**, **building**, *planned*.

Shared inputs, computed once and used by both fields:

| Input | What | Status |
| --- | --- | --- |
| Sun map | sun-aligned occlusion over ~4 km around the player, traced through the far field, refreshed progressively (~0.35 ms); plus cloud transmittance | **done** |
| Water map | per column near the player: water kind (ocean / lake / pond / river / marsh), flow direction and speed, depth, distance to shore, rapids and waterfalls (Hydrology) | *planned* |
| Weather fields | WeatherFieldTexture's coverage, storms, curtains, aerosol (rain / snow / ash / dust), wind | **done** (far); near *planned* |

| Effect | Near field (pack) | Far field (atlas) | Scales down to | Status |
| --- | --- | --- | --- | --- |
| Terrain to the horizon | rasterised | SVDAG clipmap, curved planet | lower internal resolution | **done** |
| Exact world shape | the game | server's own height function, MP stream | stand-in only | **done** |
| Towns, walls, forts | the game | StreetPlan / fort massing | coarser sampling | **done** |
| Landmark silhouettes, wild landmarks | the game | settlement landmarks captured from their own builders (AtlasCapture: exact spans, floating where they do); named wild sites (forts, towers, shrines, wonders, entrances) from the builders' dimensions (AtlasSites); seeded natural landmarks sent by the server (AtlasNatural) | profile boxes | **done** (in-game unverified) |
| Biome surface blocks | the game | biome tints **done**; the world's own material rules per far column, snow and ice (AtlasSurface, tile codec v3) | raster climate dressing | **done** (in-game unverified: the `atlas surface check` log line) |
| Sun shadows | shadow map, soft PCF, entities, foliage alpha | traced, same LOD | off far | **done**; softer near *planned* |
| Mountain shadows on the near field | sun map | traced | 512 / 256 map | **done** (in-game look unverified) |
| Cloud shadows | sun map (cloud transmittance) | traced | off | **done** (near in-game unverified) |
| PBR (labPBR normal / specular / height, default per material) | GGX sun + sky, roughness by material; parallax occlusion from the labPBR height map up close (POM: MEDIUM 8 steps / 12 blocks .. ULTRA 24 / 24, steps with the SSAO knob); rain wets what the sky reaches (porous darkening, a water film on up-facing surfaces); polished opaque ice, bare metal (F0 = albedo) | roughness widened with distance (no shimmer) | Blinn, no maps, no parallax (LOW) | near **done** (in-game unverified: needs a labPBR resource pack) |
| Emissive and coloured light: lava, lamps, fire, magic | emissive maps; block light coloured by its emitter (the mod's emitter cache) and flickering for flames; the mod's point lights (spells, atronachs); additive HDR flames; bloom | emissive classes glow through haze; lava light over volcanoes | white block light, no bloom | near **done**, far *planned* |
| Long-distance lights (towns at night, lava fields) | - | lit windows per facade, street lamps, braziers, wall lanterns, gate torches, camp fires, landmark light blocks, lava; dusk to dawn; budget per frame (adaptive: atlas's LOD knob), clusters beyond 6 km | fewer lights / off | **done** (in-game unverified) |
| Water waves by body | water map: ocean swell, lake chop, calm ponds, wind and storm amplitude | same wave field, filtered by distance | static normals | *planned* |
| Shore lapping, foam | water map shore distance, animated | foam band on far shores | none | *planned* |
| Rivers, rapids, waterfalls | flow from the flowing sprite's basis + face slope: advected waves, rapids foam on steep / fast runs, white water streaming down falls, spray and mist where a fall meets the pool | rivers flow (8 headings x calm / fast / rapids from the channel and the water levels): waves advected downstream, white water on fast runs and rapids; Hydrology's falls as white streaming sheets with sunlit spray | static | near **done** (in-game unverified), far **done** (in-game unverified) |
| Reflections | sky + screen-space + atlas far field | traced (**done**) | sky only | near *planned* |
| Refraction (water, glass, ice) | screen-space offset by normal, tint, IOR | - | tint only | *planned* |
| Underwater | depth absorption fog, caustics from the wave field, light shafts | - | fog only | *planned* |
| Light shafts | real occlusion (SHAFT_MARCH, lib/shafts.glsl): the key light (sun, or the night's moon) marched along each view ray through the shadow map and the sun map: beams through cave mouths, ceiling holes, windows and doors in the dust of still indoor / underground air; outdoors the shadowed fog, mist and haze give their sun back (forest shafts in mist, cloud gaps, mountains at sunset), never brightening open daylight; 4x4 Bayer steps smoothed in composite1 | cloud and mountain shadows in the haze, from atlas's sun map to 3 km | screen-space (LOW, 6 samples), off | near **done** (in-game unverified) |
| Fog | sealed local air (lib/media.glsl): the mod's ground-fog drive + dawn / night / wet mists only where the sky reaches (roof cache, terrain), never in houses or caves; dungeon air with a low creeping floor layer; lamp and point-light halos; closed form beyond 128 blocks | same in the aerial perspective | 4-step march, no halos | far **done** (haze), near **done** |
| Rain, snow, ash, dust | rain mist under rain bands, blowing snow, ash haze by the weather's aerosol band (outdoors only); rain and particles shaded and fogged forward | rain shafts and curtains (**done**), snow / dust haze | fewer layers | near **done** (air), weather pass *planned* |
| Dust motes, embers | screen-space particles in light shafts, embers near lava | - | off | *planned* |
| Wind on foliage | rooted waving (leaves, grass, crops), gusts from the weather wind | far canopy sway in the normal | off | *planned* |
| Sky: stars, Milky Way, moon phases, aurora | Masser and Secunda on their orbits with phase atlases (the mod's sky state), stars veiled by moon glare and light pollution, the mod's live aurora and its light on the ground; nights keyed and shadowed by the brighter moon; the pack's own stars / aurora without the mod | the same sky behind the far field | pack sky, vanilla moon | near **done**, far *planned* |
| Volcanic glow, ash sky | the Red Mountain column from the mod's smoke source 0 (bounded volume, wind-leaning, vent glow at night), ash haze in volcanic air | the citadel's lava moat and the caldera's glow above the rim (far lights); clouds lit from below *planned* | off | near **done**, far **done** (glow) |
| Storm light (lightning) | the mod's discharge packets light facing ground and walls, the rain and mist round the channel and the sky over it; outdoors only (no flashes underground) | - | off | near **done** |
| Smoke columns (fires) | the mod's sprite smoke, shaded as a lit volume and fogged; the strongest fires' columns as bounded volumes (SMOKE_FIRE_VOLUMES: MEDIUM 4, HIGH 8, ULTRA 23, ADAPTIVE 12, scaled by the smoke-column knob; LOW sprites only) | - | sprites | **done** (fire volumes in-game unverified) |
| Clouds without atlas | a weather-driven fallback deck with its own ground shadow, only when atlas's frame is absent | - | flat deck | **done** |
| Mountain sunsets without atlas | terrain transmission over the mod's surface heights from each receiver; sun disc and shafts set behind sun_horizon | sun map | off | **done** |
| Realms | shaders/realm/ (dimension.properties): the realm's own sky dome, its authored fog and hue, local media only | - | - | **done** (in-game unverified) |
| Far creatures (dragons, ships, players) | the game's models within tracking range | impostors beyond, dissolving out while the client also tracks the real entity (entity id in the packet), dragon wings beating / gliding / folded | fewer | **done** (in-game unverified) |
| LOD transitions | - | per-pixel LOD segment phase (a stipple band, no pop line); refined tiles, new buildings and finer LODs dissolve in over 0.6 s (atlas_set_transitions) | hard switch | **done** |
| Underground | lightmap, block light | off (near field covers it: near-zero cost) | - | **done** |
| High altitude | - | 7 LODs to ~40 km, curvature; horizon fog | fewer LODs | **done** |
| HDR to the tone curve | the scene stays HDR (RGBA16F) from the lighting to final.fsh (it used to pass through the RGBA8 albedo buffer, clipping the daylit sky and clouds to white) | atlas's linear HDR, the same curve | - | **done** |
| Eye adaptation | centre-weighted log-average luminance from colortex4's mips, partial (slope 0.72), clamped x0.33..x4.9, brighter in ~1 s and darker in ~3.5 s (colortex8, lib/exposure.glsl): caves and nights dark but readable, a cave mouth's daylight blown out; the bloom knee follows it | the same exposure (final.fsh) | fixed (AUTO_EXPOSURE off) | **done** (in-game unverified) |
| Night vision | dim light turns scotopic per pixel (Purkinje: colour drains, a blue-green lean), firelight keeps its colour | the same | off | **done** |
| Regional film grade | 19 baked 32^3 LUTs (shaderpack/tools/bake_grades.py) picked by a 256-block grade map of Tamriel from the mod's regions.png, blended by camera position, eased by overcast and night | the same (final.fsh) | off | **done** (in-game unverified) |
| Hardware scaling | adaptive quality v2: continuous knobs (taps, samples, steps, waves, far detail) in quality.png; profiles are ceilings | the same controller: resolution, LOD bias, clouds, shadows, fallback tiers | far field off | **done** (docs/ADAPTIVE-QUALITY.md) |
| World load | - | the whole map at LOD 2-6 baked once per world on the server, shared with every player | - | *planned* |
