# Infinite ATLAS: the client generates the world

The owner's direction (docs/ATLAS-STREAMING.md, "Client-owned world, server-checked"): the client generates and renders
everything from the seed and the baked plans; the server sends only coalesced, position-based operations. Today the
near field's real blocks come from vanilla chunks the server loads (about 6 chunks/s after a teleport) and the server's
section blobs. This document maps the Java generator that produces a chunk's final blocks, says what is portable, what
the GPU already does, and what stays Java; then the GPU generator built from it (stages 2-5).

Sources: the mod (`mod/src/main/java/com/nirncraft`), its data (`data/nirncraft/worldgen`), and Minecraft 26.3's own
generator read from the NeoForm sources (`net.minecraft.world.level.levelgen`: `NoiseBasedChunkGenerator`,
`densityfunction/**`, `synth/**`, `Aquifer`, `material/**`). Line numbers are as of 8e95cfac.

## 1. The pipeline that makes a chunk's blocks

The overworld is vanilla `minecraft:noise` with `settings: nirncraft:tamriel` and biome source `nirncraft:tamriel`
(`data/minecraft/worldgen/world_preset/normal.json`). There is no custom ChunkGenerator. Per chunk, in order:

| step | code | what it does |
|---|---|---|
| BIOMES | `ChunkAccess.fillBiomesFromNoise` -> `TamrielBiomeSource.getNoiseBiome(qx, qy, qz, climate)` | one biome per quart (4^3 blocks), 3D (caves by depth) |
| NOISE `doFill` | `NoiseBasedChunkGenerator.doFill` | `final_density` over the chunk volume (16 x 640 x 16), then `Aquifer.computeSubstance(density)` per block: null = `default_block` (stone), else water / lava / air |
| SURFACE | `MaterialSystem.buildSurface` + `material_rule` | per column, top down: stone depth above / below, water height, then the rule tree replaces stone blocks |
| CARVERS | none | all 163 biomes have `"carvers": []` |
| FEATURES | `applyBiomeDecoration` | per step and biome, seeded by the decoration seed (see section 4) |

### 1.1 Density (`noise_settings/tamriel.json`, `final_density`)

`min(squeeze(0.64 * interpolated(blend_density(... slide(range_choice(below_surface, sloped, caves ...))))),
range_choice(below_surface, -1e6, 0.25, 1, max(noodle, -100 + 500 * road)))`, with cells of 4 x 8 x 4 blocks.

* The Tamriel inputs are 2D Java functions of the column: `nirncraft:tamriel_height` (`TamrielHeight.height`: the map,
  four seeded noises, hydrology, islands, the road plan and pads via `PlanGround`) and `nirncraft:tamriel_channel`
  (`TamrielChannel.value`: elevation, river, road (+ PlanGround influence), overhang, erosion, ...). `below_surface`
  = (height - y) / 28, `sloped` = below_surface + overhang * 0.55 * noise(`tamriel_overhang`).
* The caves are vanilla's overworld stack: `cave_layer`, `cave_cheese` (twice), `spaghetti_2d`, the spaghetti roughness,
  `entrances`, `pillars`, `noodle`, plus a river-gorge band gated by the river and elevation channels.
* **The mod's own cave code is dead.** `nirncraft:tamriel_caves` / `TerrainDensity.computeCaveDensity` (karst
  galleries, vaulted chambers, river gorges, the Places / PlanGround hard mask) is registered but no JSON references
  it. The protection of roads and pads from caves is only the `road` channel terms in the JSON above.
* **The beardifier is not in this router.** 26.3 applies structure terrain adaptation only where `final_density`
  contains `minecraft:beardifier`; `tamriel.json` does not, so the settlements' `beard_box` and the dungeons'
  `beard_thin` change nothing today (noted for the owner; not changed here).
* 26.3's density engine is all float (`DensitySampler.sampleValue` / `sampleVolume` return float buffers); constants
  are floats. Two evaluation paths give different bits and both are used:
  - *volume* (`sampleVolume`, chunk fill and the ore veins' prefill): a noise layer's coordinate is
    `blockX * (xzScale * frequency)`; `min`/`max` keep the left unless the right is strictly smaller / larger;
  - *point* (`sampleValue`, aquifer noises, material-rule noises, the ore gap): `(blockX * xzScale) * frequency`;
    `min`/`max` skip the right when the left is past the right's range; `mul`/`div` return 0 for a zero left.
  - `interpolated` in the volume path lerps z, then x, then steps y by repeated addition (`InterpolatedFunction`);
    its point path (`lerp3`) is different and is not used by the chunk fill.
  - The optimiser inlines references, de-duplicates caches and slices 2D functions (`DfRewriteRule`); min/max between
    disjoint ranges compile to one side (`BinaryFunction`). Values follow the Java objects' `range()`, so a port
    must take the same shortcuts from the same ranges.

### 1.2 Water and lava

* `Aquifer.NoiseBasedAquifer` (26.3: `aquifers` block in the settings): a 16 x 12 x 16 grid of cells, a random centre
  per cell (`positionalRandomFactory("minecraft:aquifer").at(cell)`, Xoroshiro), each centre's fluid level from the
  preliminary surface (`tamriel/height` at quart positions, 13 samples up to 3 chunks west), the `exclusion`
  (erosion and depth), floodedness / spread / lava noises; the nearest four centres and a barrier noise decide each
  block. Global fluid: water below sea level 63, lava below y -54.
* Chunk-scoped: `skipSamplingAboveY` is from the highest preliminary surface over the chunk's aquifer grid; above it
  only the global fluid applies.
* Map water (`MapWaterFeature`, the first `lakes` feature): rivers and lakes above sea level, filled per column from
  `OCEAN_FLOOR_WG` up to `ceil(waterLevel) - 1` where air or replaceable; marsh pools to sea level. A function of the
  map sample and the terrain column: portable.

### 1.3 Surface paint (`material_rule`, 328 KB)

`MaterialSystem.buildSurface`: per column from `WORLD_SURFACE_WG + 1` down, counting stone above (reset by air), the
water height (first fluid), the next non-stone below; every stone block runs the rule tree. The rule is a sequence:
vanilla copper and iron ore veins (`OreVeinRule`: density / richness / gap functions, `ore` random), then the
mod's tree (wrapped by `nirncraft:fast_surface`, an exact biome dispatch of the same tree). Used condition kinds:
`biome` (131 biomes), `noise_threshold` (surface, gravel, calcite, patch, powder_snow, packed_ice, surface_swamp,
ice, netherrack; 2D), `stone_depth`, `water`, `y_above`, `steep` (heightmap gradient, clamped at the chunk edge),
`hole`, `not`, `vertical_gradient` (bedrock, positional random), `above_preliminary_surface`, `bandlands` (clay bands).
53 block states come out (grass, dirt, coarse dirt, podzol, sand, gravel, snow block, powder snow, packed ice, mud,
moss, the stones, terracotta, the mod's volcanic rock, ash, frosted stone, coral stone, ...). Every input is a pure
function of the position, the column and the chunk-local heightmap.

The biome a rule sees is `BiomeManager.getBiome` (the seeded voronoi zoom over quarts, LCG arithmetic) over
`TamrielBiomeSource`. **Order dependence found:** the biome source samples a quart column's climate once per sampler,
at the first quart asked, and derives the surface from the quantised depth there; another first quart can give a
surface 0.01 block different and, rarely, another context. Chunk generation asks bottom-up (`fillBiomesFromNoise`), so
the client must ask in the same order with a fresh sampler per chunk to match.

## 2. What is portable, what the GPU already does

| part | deterministic | portable to the GPU | today on the GPU |
|---|---|---|---|
| perlin / normal noise (26.3 `NoiseStack` of `PerlinNoise`) | yes | yes, bit exact (float + double, no division) | the four height noises (`atlas_height.slang`) |
| the density router | yes | yes: a small float VM over the compiled tree, both paths | no |
| `TamrielHeight` / channels (map rasters, hydrology, road plan, pads, isles) | yes (baked plans) | later: rasters + plans as GPU data | the noise values only; the rest runs in Java (`AtlasHeightGpu` replays height()) |
| biomes (`TamrielBiomeSource`) | yes, with the order caveat | later | no (Java, `AtlasBiomes`) |
| aquifer | yes | yes: int, double and Xoroshiro; two divisions need exact rounding | no |
| material rules | yes | yes: a rule VM; conditions are ints, doubles and noises | no (Java `AtlasSurface` evaluates the real rule for far tiles by reflection) |
| map water | yes | yes (a column flag + level) | no (far tiles carry the water top) |
| trees, cover, rocks, ores, vanilla features | positions partly; shapes use the chunk's decoration RNG and read blocks other features wrote | no: Java into the same section path | trees at LOD 0 run the real `flora_tree` features into `AtlasCapture` |
| settlements (named places) | piece lists from the baked street plan, seed-free; painting reads the ground and runs after trees | Java into the same section path | `AtlasSettlements` rasterises blueprints for the far field |
| dungeons, generic settlements, vanilla structures | seeded by the structure RNG, read heightmaps | Java | `AtlasSites` approximates |

The far field's "stand-ins" (column tiles from heights plus the material rule's top block) are the 2D projection of
the same pipeline: heights exact, no caves, no overhangs, no aquifers.

## 3. The split for client generation

* **GPU (stage 2):** density, caves, aquifers, map water, the surface paint and the ore veins, for whole chunk columns
  (the surface pass walks a column top down, so a section cannot be painted alone; a column costs little more than its
  sections). Output: per section 4096 ids, either generator block ids (parity) or atlas material ids.
* **Java, per column (for now):** the 2D inputs the GPU program reads: `TamrielHeight` (already GPU-assisted), the
  channels, the map water level, and the quart biomes. These are the mod's own code, so they are exact by
  construction; moving them to the GPU means porting the map rasters, hydrology and the plans (a later stage).
* **Java, per chunk (stage 4):** features and structures, run with the real feature code into a capture level over
  the generated blocks, as `AtlasTrees` does, then into the same section path.
* **Server (stage 5):** only differences: player edits, and generator divergences found by comparison.

## 4. Features, for stage 4 (from the audit of 2026-10-20)

* Decoration per biome step; `ctx.random()` = WorldgenRandom (Xoroshiro) seeded from the decoration seed of
  (seed, chunk min x/z) then the feature index and step. Biome lists: `worldgen/biome/*.json` plus NeoForge biome
  modifiers (`data/nirncraft/neoforge/biome_modifier`): mod ores added, vanilla redstone / lapis / emerald / diamond
  and monster rooms removed; top layer: scatter, landmark, minor / river sites, bandit camp, flotsam, road paint,
  street paint.
* Still vanilla: coal / iron / gold / copper ores and stone blobs, disks, amethyst geodes, underground lava lakes,
  springs, glow lichen, `freeze_top_layer` (snow and ice by biome temperature), per-biome seagrass, kelp, cane, vines,
  dripstone, fossils.
* Trees: positions from `ScatterPlacement` (pure, hash priority), species and shapes from the decoration RNG,
  `TreeBuilder.commit` writes into the 3x3 chunks and reads what is there: order dependent across chunks.
* Pure or nearly: map water, flora positions, road and street paint (per column from the baked plans), scene plan
  props, named settlements' piece lists, Places.
* Seeded only on the server: in multiplayer the client has no seed today (surface noise, biomes' climate, trees,
  generic structures). Client generation is single player first (the integrated server's RandomState, as the far
  field's exact source already does); multiplayer needs the public seed decision (ATLAS-STREAMING.md, decision 3).
