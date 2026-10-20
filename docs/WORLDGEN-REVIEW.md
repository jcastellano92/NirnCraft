# World generation review (2026-10-19)

Asked by the owner before the next world work (per-region biomes with weather patterns and sub-biomes, special
dungeons, towns, home and building types), with the client-owned world (docs/ATLAS-STREAMING.md) in mind. Based on a
read of the code; paths are under `mod/src/main/java/com/nirncraft/` unless noted.

## What exists

| layer | today | source |
|---|---|---|
| map | hand-authored vectors (provinces, holds, coasts, ranges, rivers, lakes, peaks) baked offline to rasters: 880x672 cells of 32 blocks (28 x 21 km), 83 regions; fixed foreign lands outside | `tools/worldgen/tamriel_map.py`, `hydrology.py`, `resources/nirncraft/tamriel/*.png` |
| height | one surface function: the map (seed-free) plus four vanilla seeded noises scaled by map channels, rivers, lakes, road beds, site pads | `terrain/TamrielHeight.java:56` |
| 3D | vanilla caves, aquifers, ore veins; an overhang channel; carvers empty | `data/nirncraft/worldgen/density_function/tamriel/*` |
| biomes | 163 vanilla biome JSONs (134 overworld); per-region palettes (109) with weighted picks for 12 contexts; 1,518 micro rules (sub-biomes on elevation, slope, rainshadow, heat, water, coast, ridge, aspect); no blending | `tamriel/TamrielBiomeSource.java:138`, `biome/Palettes.java` |
| weather | 15 kinds, per-region and per-hold weighted profiles, moving fronts, server RNG, synced; no seasons | `atmosphere/weather/` |
| towns | 7 named sets from places JSON, 326 city plans through a zoning grammar, baked seed-free; generic towns seeded; all built by code (no NBT, no jigsaw); 14 cultures, sub-styles (Nord 10, Dunmer 4) | `tamriel/settlement/`, `worldplan/street/` |
| dungeons | procedural room grid, 123 authored marquee plans, special and unique sites; layouts seeded by world and chunk | `dungeons/` |
| loot | table id stored at generation, rolled on first open per player at their level, per-player restock | `loot/` |
| ores | regrow on a timer, world state shared by all | `survival/OreVeins.java` |
| mobs | region spawner per player; level fixed at first join from the region and the nearest player | `creatures/` |

Strong points: the map and every named plan are seed-free and baked; the data model for sub-biomes is already rich;
building is code from plans (no templates to keep in sync); loot is already server-rolled per player on open.

## What stands in the way

1. **The world seed leaks into content that the owner considers fixed**: height detail, the biome inside a palette
   (humidity and temperature noise), named dungeon layouts and facings, generic towns, minor sites, landmarks. In
   multiplayer the seed never leaves the server, so the far field draws a separate approximation
   (`native/src/preview_source.cpp`, a hand-port with no parity test): that is the dirt-to-grass the owner saw.
2. **Order dependence in decoration.** Settlement and dungeon pieces sweep up to 14 blocks into neighbouring chunks
   (`SettlementPiece.java:184-190`, `DungeonPiece.java:157`, flood fills up to 20k blocks); features read
   neighbouring heightmaps and blocks (`GenBox.java:75-81`, `TreeShapes.java:808`, `GroundCoverFeature.java:112`,
   `landmarks/Grounding`). The result depends on which chunk was decorated first: no client can reproduce it.
3. **Blocks placed after generation**: road signposts, lanterns, milestones, shrines (`roads/RoadNetwork`, `Paver`),
   provincial homes built when a player comes near, NPC and ownership state at chunk load.
4. **The far field predicts content with copies, not the builders**: landmarks are sent before the builder's
   refusals run (`atlas/AtlasNatural.java:95` vs `landmarks/LandmarkBuilder.java:38-58`: nearby structures, plan
   masks, slope, water): the phantom structures the owner saw. Wild sites' shapes are transcribed by hand
   (`atlas/AtlasSites.java:62`, facing guessed). Reserved home plots are drawn as houses (`AtlasSettlements.java:571`).
5. **Floats at thresholds**: the height is cast to float for vanilla (`TamrielHeight.java:213`) but used in double by
   the far field; `Math.pow`, `atan2` aspect thresholds (`Palettes.java:185`), and a C++ port of the same.
6. **Authored data the runtime ignores**: micro rules carry `surface`, `trees`, `groundcover`, `props`, `weather`,
   `spawns`, `ambience`, but only `biome` is read (`Palettes.compileOne`); 11-layer region sheets (`WorldSheets`)
   unused; `TamrielCaves`, `TerrainDensity` (karst, vaults, gorges) never registered; region blending computed but
   unused. The sub-biome system the owner wants is half authored already.
7. **Biome and terrain disagree at borders**: the biome lookup warps the position again on top of the raster's warp
   (`TamrielBiomeSource.java:162-170`); no blending, only dithering.
8. **Gaps**: hundreds of named places with variants no structure set builds (sea caves 34, kwama mines 26, ebony 25,
   glass 24, grottos 20); Lootr still takes containers by default (`containers.system=AUTO`), which bypasses our
   per-player loot; ore regrowth is shared (one player farms a vein for everyone); one player's level sets mob
   levels for everyone near; a raster load failure turns the world into open sea silently. Docs disagree with the
   code (TAMRIEL.md, WORLD.md, WORLDGEN-PIPELINE.md, GPU-WORLDGEN-PLAN.md).

## Choices, with alternatives

### A. The seed

- **A1 (today)**: the fixed map plus seeded detail. Every world differs in detail; the client cannot be told the
  seed (it would reveal ores), so multiplayer far fields are guesses.
- **A2: one canonical Tamriel**: a fixed public seed for all public content (terrain, biomes, flora, towns,
  dungeon layouts); a per-server secret key only for secrets (ores, loot, hidden rooms, traps). Every client can
  generate the exact world; the stand-in disappears; caches can even ship with the pack.
- **A3: the public seed per server but shared with clients** (A2 with variety): the same, but each server's details
  differ; secrets still on the private key.

Recommendation: **A2, built so A3 is a switch** (the public seed is an input, constant by default). A different map
later is a different set of authored vectors and a new bake; the pipeline does not care.

### B. Biomes and sub-biomes

- **B1: more vanilla biomes**: every sub-biome a biome JSON. Vanilla and mods understand them (colours, sounds,
  spawns), but the registry bloats, a biome is per 4x4x4 cell, one weather setting each, and no blending.
- **B2: our own ecology layer**: a hierarchy region, biome, sub-biome (micro), patch, each a set of parameters
  (surface, flora, props, ground cover, weather allowed and typical, spawns, ambience, palette tints), resolved per
  column at any resolution with blend weights. The far field samples the same function at its distance.
- **B3 (combo, recommended)**: B2 is the truth; the vanilla biome is derived from it per cell for compatibility (the
  biome source asks the ecology layer). This is mostly wiring what is authored: the micro-rule fields that are
  ignored today become live, and blending uses the weights that are already computed.

### C. Weather

- **C1 (today)**: region profiles rolled on the server, synced.
- **C2: weather as a pure function of time and place**: fronts and spells from a counter RNG keyed by day and area,
  shaped by each biome's allowed and typical weather and a season from the calendar; every client computes the same
  sky without sync, and distant storms show correctly in the far field.
- **C3 (combo, recommended)**: C2 as the base, the server's ordered operations for scripted weather (spells, quests,
  events). The per-biome weather data (B3) can be authored now; the runtime belongs to the weather branch, last on
  the roadmap.

### D. Towns, dungeons, homes, buildings

- **D1 (today)**: code builders from plans, named places baked, generic ones planned at runtime, pieces sweep
  their neighbours.
- **D2: hand-built templates** (NBT, jigsaw): most art control, heavy data, every style variant a file, vanilla
  jigsaw is order-sensitive and hard to draw far.
- **D3: plan as data for everything**: every site, named or generic, is a plan record (footprint, pieces,
  culture and sub-style, archetype, seed from its site id, not the world), a pure function or baked; builders read
  only their plan and the heightfield and write only inside the chunk being built; the far field draws the same plan.
- **D4 (combo, recommended)**: D3, with three sources feeding the same plan format: the grammar (generic towns and
  dungeons), authored plans (marquee dungeons, unique cities, special sites), and optional hand-built rooms or
  buildings as pieces inside a plan (D2 where art needs it). New home and building types are archetypes x culture
  kits, as data. Special dungeons: authored plans with a guaranteed site.

### E. How generation runs

- **E1 (today)**: vanilla's chunk pipeline: structures, then features with a 3x3 neighbourhood.
- **E2: our own generator**: a section is a pure function (terrain, then the plans that cover it rasterised); vanilla
  only receives finished chunks. Fully deterministic, the client runs the same code, LOD comes free; vanilla and
  mod feature injection is lost (mod ores and trees), a large refactor.
- **E3 (combo, recommended path to E2)**: keep vanilla's pipeline as the carrier, but make every builder plan-driven
  and chunk-clipped (no sweeps, no neighbour reads), bake what is placed later (road dressing) into plans, and
  generate sections through one function that the client and far field call too. When nothing reads neighbours any
  more, E2 is a small step.

### F. Per-player state (for the owner's graduated consequences)

Loot already rolls per player on open (`LootApi.roll`), so lower yields hook there; Lootr must go (or AUTO
default to ours). Ores need per-player depletion like loot (it also fixes one player farming for all). Mob level
needs the target player's level, not the nearest player at join; per-player difficulty hooks into `Leveling`.

## Order (proposed)

1. Quick fixes to what the owner saw: run the landmark builder's refusals before the far field sends them (or send
   only built sites); do not draw reserved home plots as houses; facing from the same seed as the builder.
2. Decide A (seed). With A2, the multiplayer stand-in is the real generator and `preview_source.cpp` goes.
3. Integer arithmetic and the counter RNG in the height, biome and micro-rule thresholds; one function for Java,
   native and GPU; the shuffled-order gate (ATLAS-STREAMING decision 1).
4. Plans for every site (D4) and chunk-clipped builders (E3): remove the sweeps and neighbour reads.
5. The ecology layer (B3) with blending; author per-biome weather allowances.
6. New content on top: sub-biomes, special dungeons, towns, home and building types, as data.
7. Weather runtime (C3) on its own branch.
