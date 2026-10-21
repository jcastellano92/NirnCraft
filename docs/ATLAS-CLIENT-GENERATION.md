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
* **GPU too (2026-10-21, section 7.2):** the 2D inputs the program reads: `TamrielMap.sample`, `TamrielHeight`, the
  channels, the map water and the quart biomes, from the map's data on the device. Java's `AtlasGenPages` is only the
  fallback now (a library or program without the page passes).
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

## 5. The GPU generator (stage 2, 2026-10-20)

`native/shaders/atlas_gen.slang` + `native/src/gen_engine.cpp` (`atlas_gen_*` in atlas.h), its own Vulkan compute
device like the height engine, and the same source compiled to C++ (the CPU path and the reference). Whole chunk
columns, y -64..575 (40 sections; above is air). Under the exact float contract of `atlas_height.slang` (`-fp-mode
precise`, `spv_exact`: RTE, signed zero / inf / NaN preserved, every arithmetic op NoContraction, no transcendental);
divisions go through `divF` (float, the residual picks the correctly rounded quotient) and `divI` (integers, long
division with sticky bit); signed integer division only on non-negative operands (a driver disagreed on
`floorDiv(y, 40)` for negative y).

**The program** (`AtlasGenProgram`, Java): the world's own objects compiled, nothing re-authored.

* The density functions as Java's `DensityFunctionCompiler` sees them (references inlined, caches and slices
  transparent, min/max range shortcuts from the same `range()`), into a register VM: 16 registers suffice. Volume and
  point paths are separate opcodes (`MIN_V` / `MIN_P`, `MUL_V` / `MUL_P`, the noise's coordinate product order).
  `interpolated` inputs (8 for this router: the terrain, four noodle parts, three ore-vein parts) are evaluated at the
  5 x 81 x 5 cell corners, then lerped as `InterpolatedFunction.sampleWithBlockStep` does.
* Noise tables read from the RandomState's seeded `NoiseStack`s (as `AtlasHeightGpu`); the aquifer, ore, surface and
  vertical-gradient random factories' seeds by reflection; the clay bands from `MaterialSystem`.
* The material rule tree as rule code (test / jump, block, ore vein, bands) and a condition table; biome sets as
  bit sets over the 134 possible biomes.
* The mod's 2D functions (`tamriel_height`, the channels: road, erosion, overhang, river, elevation) become column
  fields read from pages.

**Pages** (made on the device since 2026-10-21, section 7.2; `AtlasGenPages`, Java, per chunk, is the fallback): the fields from the generator's own compiled samplers (exact by
construction), the map water of each column (`MapWaterFeature`'s test), and for the chunk's 3 x 3 the quart biomes,
filled as `ChunkGenerator.createBiomes` fills them (fresh climate sampler, sections bottom up). A chunk reads heights
from chunks x-4..x+2, z-2..z+2 (the aquifer looks up to three chunks west) and biomes from its 3 x 3. Pages stay
resident on the device (4096, least recently used out).

**Passes** per batch of up to 64 chunks: corners, aquifer cells (3 x 57 x 3 per chunk, plus the chunk's
`skipSamplingAboveY`), fill (final density, then the aquifer), tops (`WORLD_SURFACE_WG`), surface (the rule walk per
column, then map water), pack (16-bit ids: generator ids or atlas materials).

**Ingest**: `atlas_gen_into(gen, ctx, ...)` hands each non-air section to the context through `atlas_upload_section`
(World::putSection and the level-0 grid's `put`): one small function, no grid internals touched. GPU-to-GPU (the
generator writing the grid's pool directly) needs both on one device: the next step, with the grid's owner.

## 6. Parity (stage 3) and features (stage 4)

`mod/src/test/java/com/nirncraft/atlas/AtlasGenParityTest.java`, a dev tool off by default (the JUnit server, no
client):

    NIRNCRAFT_GEN_PARITY=1 NIRNCRAFT_ATLAS_LIB=<native/build/Release/atlas_core.dll> NIRNCRAFT_GEN_PARITY_N=<per kind>
    NIRNCRAFT_GEN_PARITY_OUT=<report> py -3 tools/dev/build_263.py --offline -- test --tests "com.nirncraft.atlas.AtlasGenParityTest"
    (add NIRNCRAFT_TEST_WORLD=tamriel for realWorld; NIRNCRAFT_GEN_DEBUG=1 dumps where Vulkan and C++ part)

* `gpuChunksMatchJava`: chunks over 10 kinds of terrain (mountain, high peaks, coast, ocean, river, lake, desert,
  swamp, plains, road) from a scan of the map, made by Minecraft's own `NoiseBasedChunkGenerator` (createBiomes,
  buildTerrain) plus the mod's `MapWaterFeature`, against the kernel on Vulkan and in C++. **107 chunks,
  17,530,880 blocks: 100.00000 % on both** (RTX 3090). A control (each GPU chunk against another chunk's Java
  blocks) gives 90.9 %, so the comparison sees differences.
* `realWorld` (stage 4): the JUnit server's overworld made Tamriel (seed 1234, structures on); its fully generated
  chunks (the server's whole pipeline, features and structures, its own order) as the reference. **30 chunks,
  5,406,720 blocks: GPU terrain alone 95.40 %; GPU terrain plus the client's decoration 99.83 %.** What remains:
  structures' pieces (stone and mud bricks, cobblestone, fences: not placed by the client yet) and decoration-order
  overlaps (ore blobs, gravel, dripstone where two chunks' features meet: the server's order is its own).
* Features (`AtlasGenFeatures`): the decoration driver of `applyBiomeDecoration` replicated over proto chunks filled
  with the generated blocks; 0 feature failures; 81 ms a chunk in Java, one thread.

## 7. The near field from atlas's own generation (stage 5)

`-Dnirncraft.atlas.clientGen=true` (single player): `AtlasClientGen` generates the window around the camera
(`.radius`, default 24 chunks), nearest first and in view before behind, pages on all cores, and hands chunks to the
far-field worker's section path (`AtlasEditsClient.applySections`) as the client feed carries a chunk (far ids, deep
solid rock left out, the zoomed biome's tints). Features follow on a second thread within `.featureRadius` (16);
a decorated chunk goes up again and only its changed sections re-upload. Vanilla's chunks and the server's sections
still come through that path: a section equal to what is up is skipped (its hash), so they only correct what
differs (structures for now, edits, ore order); a loaded chunk already up is never covered by generated blocks. The
check offline: every generated section equals the client feed's reading of Java's chunk.

**Measured** (`windowAfterJump`, RTX 3090 shared with a live client): the camera 600 blocks from the last window,
32 x 32 chunks: 1332 pages in 196 ms (Java, 20 threads), 1024 chunks in 399 ms (kernels 196 ms: corners 43, aquifer
1.5, fill 43, tops 2.7, surface 104, pack 1.2; readback 61), 12,192 non-air sections, **0.016 ms of kernel a
section**; all blocks ready **0.64 s** after the jump (vanilla: about 6 chunks a second, three minutes for the
same window). Through `atlas_gen_into` into a context: 956 ms (ingest through `atlas_upload_section` 599 ms: the
CPU world build, the grid owner's to move to the GPU). Features for the 16-chunk radius: about 800 chunks at 81 ms
on one thread, a minute behind the terrain.

### 7.1 GPU to GPU (2026-10-20)

`atlas_gen_create_for(ctx)` puts the generator on the context's own device: on a queue of its own (async compute when
the device has a compute-only family, else a second queue of the frames' family, else the frames' queue under their
lock), 64-bit floats enabled on the device when it has them. `atlas_gen_run` adds three passes after the terrain's:
`colsMain` (a column's top and ground, `decode`'s), `secsMain` (the chunk's lowest ground and highest top, each
section's kind: air, blocks, deep rock; its FNV-1a hash over the far ids seeded with the tints, `AtlasEditsClient
.claim`'s; the tints' biome by the seeded zoom at the centre and the highest top) and `matsMain` (the atlas materials
by biome, the table `AtlasClientGen.ingestTable` builds once: putGen's `mats.section` with each block's tint). Only
the 164 words of infos come back a chunk, plus the generator ids for the chunks the features will decorate. Java
claims what the core lacks and `atlas_gen_ingest` copies those sections device to device into slots of 16 MiB
buffers and hands them to `VoxGrid::ingestGpu` (the grid agent's entry point, f1579c89); a slot is free again once
the grid no longer waits for it and a frame submitted after that has completed (`framesSubmitted`,
`framesCompleted`, `VoxGrid::gpuPendingOffsets`). The grid takes GPU sections only inside its level-0 window and its
CPU World never sees them, so: before the first frame `atlas_gen_ingest` says busy, sections outside the window come
back unsent, and `AtlasClientGen` forgets chunks (and their claims) once they leave its window + 2 chunks, so they
are generated and sent again when the camera returns. On a CPU-backend context the same calls run the C++ kernels
and the sections go up as `atlas_upload_section` takes them. `.direct=false` keeps the old path.

**Checked** (`gpuIngest`, 256 chunks at Whiterun): lowest ground, highest top, all 10,240 sections' kinds and hashes
and the tints' biome agree with the Java decode 100 %; the C++ build's infos equal Vulkan's word for word; one
context fed device to device and another fed the same materials through `atlas_upload_section` render the same
frame identically (depth and colour, 129,600 of 129,600 px). **Measured** (`clientGenJump`, a render thread drawing
320 x 180 frames beside it, features on 4 threads, the GPU shared): the first chunk in the grid 103 ms after a
1,600-block jump (pages 23, generated 100, sent 103), the radius-24 window (1,793 chunks) 2.0 s; the info and
material passes add about a third to the terrain kernels.

### 7.2 The pages on the device (2026-10-21)

`atlas_gen_set_map(gen, AtlasGenMap.words(prog, biomeSource))` puts the map of Nirn on the generator's device once; with
a program that carries the page passes' inputs (`AtlasGenProgram.pageGen`: the climate router's six entries as point
code, every column field's kind, the four TamrielHeight noises' stacks) the engine then makes every page a batch needs
itself, before the batch's terrain, in the same submission: `pageColsMain` (a thread per column: fields and map water)
and `pageBiomesMain` (a thread per quart column: its 176 quart biomes). `atlas_gen_missing_pages` answers 0 and Java
computes nothing; a page with a value the kernels could not prove flags every chunk that reads it.

The kernels (`atlas_page.slang`, `atlas_map.slang`) are the mod's Java ported term for term (Java's float / double
promotions, StrictMath through `atlas_exact.slang`, exact division and square root):

| Java | on the device from |
|---|---|
| `TamrielMap.sample` (MapRaster, Hydrology, MapPeaks, Beyond) | the raster's channels and regions, the falls' grid, the peaks' records, the lands |
| `TamrielHeight.height`, `OceanIsles.surfaceHeight` | the program's noise stacks; every 512-block cell's isle (`OceanIsles.cellIsle` itself, at map build) |
| `PlanGround.bed / pad`, `StreetPlan.pad`, `PlanGround.influence` | the authored road plan, PlanGround's edge and site indices, the street plan (sorted key tables) |
| `TamrielChannel.value` (+ influence on road / overhang) | the sample |
| the map water word (`AtlasGenPages`) | the sample |
| `TamrielBiomeSource.getNoiseBiome` (+ Palettes, BiomeNoise) | the palettes in the program's biome ids, the regions' palettes and cave classes, the climate entries |

Not on the device: `TilePlanner`'s tile plans beyond Tamriel (Java uses one only where its planner finished that
tile), and the biome source's test modes (`nirncraft.biomeTest`: the pages stay in Java then).

**Checked** (`gpuPagesMatchJava`, NIRNCRAFT_GEN_PARITY_N=11: 100 chunks over the 10 kinds): 16,384,000 of 16,384,000
blocks equal on Vulkan (RTX 3090) and in C++, no chunk flagged; the 3,497 pages those chunks read equal to
AtlasGenPages' word for word in every field, the map water and all 1,267,200 quart-biome words (the overhang channel
after the double-influence fix: re-checked on 30 chunks, 1,050 pages, every word equal). **Measured**: 0.066 ms a page on the device (3,497 in
229 ms of passes; Java: 373 ms on all 20 cores), a batch with its pages resident 0.42 ms a chunk. The map is 19.6 M
words (78 MB, the raster most of it), packed in about 0.1 s once the plans are loaded (a fresh dev tree whose road
and street plan bakes are stale plans them first: 35 s, which the world's own generation needs anyway); it goes up
from the generator's worker thread, Java's pages serving until then.

### 7.3 Far tiles on the device (2026-10-21)

`atlas_gen_tiles(gen, (lod, tx, tz)..., surface, water, flags)` (`tileMain`): AtlasExactSource.fill's 256 x 256
columns (TamrielMap.sample, TamrielHeight.height at each 2^lod square's centre block, waterAt) from the same kernels.
`AtlasExactSource.enableGpuTiles` (single player, `-Dnirncraft.atlas.gpuTiles=false` off) makes the client's far
tiles there; the first tile of each LOD is also made on the CPU and compared bit for bit (a difference turns the path
off, logged). **Checked** (`gpuTilesMatchJava`): 12 tiles over LODs 0-6 (Whiterun, sea, the raster's edge, Atmora,
Yokuda, Hammerfell): 786,432 of 786,432 columns equal on Vulkan and C++. **Measured**: 1.32 ms a tile on the device,
23.2 ms with all CPU cores. Still Java per tile: the surface blocks (`AtlasSurface`, the material rule at the top) and
the tints (`AtlasBiomes`), then the tile's encoding and the far World build (`atlas_upload_tile`). Multiplayer clients
have no seed: their tiles still come from the server's tile service.

## 8. Open

* **Structures from the plans**: starts per chunk (`ChunkGenerator.createStructures` with the server's
  `ChunkGeneratorStructureState`, named settlements from the street plan) into the proto chunks, their pieces placed
  in decoration as `applyBiomeDecoration` does; the remaining 0.17 %.
* **Features are slow and single threaded** (81 ms a chunk): decorate disjoint 3 x 3s in parallel; trees on the GPU
  later (their placements are pure; shapes need the decoration random).
* **The surface pass** is the kernel's largest (a thread per column walks it): split the per-block rule work.
* **The grid and GPU sections**: `VoxGrid::ingestGpu` drops what lies outside its windows and cannot refill it (the
  CPU World never had it); a list of the sections it let go would let the generator resend exactly those.
* **Tile plans beyond Tamriel** (`TilePlanner`): Java shapes roads there only where its planner finished a tile, so the
  kernels (authored plan only) differ where one did. The plans would go up as device data like the authored one.
* **The far tiles' surface blocks and tints** (`AtlasSurface`, `AtlasBiomes`): still Java per tile; the heights and water
  come from the device (section 7.3). The rule VM and the biome kernels are on the device already.
* **Multiplayer**: the client has no seed (the public / secret decision, ATLAS-STREAMING.md decision 3).
* Found on the way, for the owner: the router has no `minecraft:beardifier` (structures' `beard_box` /
  `beard_thin` do nothing), `nirncraft:tamriel_caves` is dead code, and `TamrielBiomeSource` samples a quart
  column's climate at the first quart asked (an order dependence; chunk generation asks bottom up).
