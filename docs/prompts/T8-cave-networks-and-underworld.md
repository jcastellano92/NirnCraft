# T8 — World-Scale Subterranean Cave & Karst Networks, Underground Waterfalls, True Surface-Piercing Skylights/God-Rays & Submerged Sea Caves

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **World-scale caves outside bounded dungeon structures (`tamriel.json` lines 166–274) are 100% vanilla noise caves + vanilla carvers.**
2. **Only 5 underground biomes exist (`dwemer_caverns`, `falmer_glow_caves`, `ice_caves`, `lava_caves`, `mushroom_grottos`), and their biome JSONs still list vanilla mob spawners (`zombie`, `skeleton`, `spider`, `enderman`, `witch`).**
3. **`CavernDress.lightShaft()` (`CavernDress.java:733-761`) does NOT pierce the surface**—it caps at `g.top - 2` and places a fake 5-block disc of `Blocks.SEA_LANTERN` (`// the sun: a disc of light at the top of the shaft`).
4. **No submerged ocean cave networks with breathable air pockets or open-cave subterranean ruins/shrines exist.**

`T8` builds a Tamriel-wide ecological subterranean system with custom worldgen cave density (`TamrielCaves`) that respects dungeon and road/settlement footprints, expands underground biomes from 5 to **14 regional subterranean biomes** with 0 vanilla hostile mob spawners, makes `lightShaft()` truly pierce the terrain surface for natural sunlight, god-rays, and waterfalls, and adds submerged ocean/coastal sea-caves with breathable air-pocket domes and subterranean ruins.

## 2. Write Set

* `mod/src/main/java/com/nirncraft/tamriel/terrain/{TamrielCaves,TerrainDensity}.java`
* `mod/src/main/java/com/nirncraft/tamriel/worldgen/TamrielBiomeSource.java`
* `mod/src/main/java/com/nirncraft/dungeons/world/{CavernGen,CavernDress}.java`
* `mod/src/main/java/com/nirncraft/seas/world/Undersea.java`
* `mod/src/main/resources/data/nirncraft/worldgen/noise_settings/tamriel.json`
* `mod/src/main/resources/data/nirncraft/worldgen/biome/*.json`
* `mod/src/main/resources/data/nirncraft/region_spawns/*.json`
* `tools/worldgen/{biomes,noise_settings}.py`

## 3. Implementation Steps

1. **Custom World-Scale Cave Router (`TamrielCaves.java`, `noise_settings/tamriel.json`):**
   * Register density function `nirncraft:tamriel_caves` combining regional karst galleries, vaulted subterranean chambers, and river-fed gorge tubes while multiplying by a smooth exclusion mask around `Places` dungeon bounding boxes and `PlanGround` road/settlement pads so world caves complement dungeons and never undermine roads or buildings.
2. **14 Regional Subterranean Biomes & Native Spawners (`biomes.py`, `TamrielBiomeSource.java`, `region_spawns/*.json`):**
   * Add 9 new underground biomes (for 14 total) in `tools/worldgen/biomes.py` and `TamrielBiomeSource.java`:
     1. `nordic_karst_caverns` (temperate Skyrim/High Rock limestone & waterfall caves)
     2. `ice_caves` (glacial permafrost & ice-spike caverns in northern Skyrim, Winterhold, Pale, Atmora, Kamal)
     3. `dwemer_caverns` (geothermal steam & Dwemer pipework caverns)
     4. `falmer_glow_caves` (bioluminescent chaurus/fungal warrens)
     5. `lava_caves` (volcanic basalt tubes & magma rivers in Morrowind/Red Mountain, Eastmarch Aalto, Dragontail)
     6. `mushroom_grottos` (giant Velothi fungal caverns in Morrowind)
     7. `ayleid_limestone_caverns` (Cyrodiil travertine pools, stalactite colonnades & starlight shards)
     8. `valenwood_root_warrens` (colossal subterranean Graht-root tunnels, hanging moss & glow-worms)
     9. `black_marsh_hist_caverns` (flooded mud-grottos, glowing Hist sap roots & bog-beacon clusters)
     10. `elsweyr_sandstone_caverns` (wind-and-water carved red sandstone vaults & salt-crystal domes)
     11. `summerset_crystal_grottoes` (prismatic quartz & luminescent coral-limestone halls)
     12. `yokudan_sand_vaults` (Hammerfell/Yokuda sunken sandstone galleries & subterranean oases)
     13. `submerged_sea_caverns` (coastal/ocean underwater caves with trapped breathable air pockets & glowing sea-flora)
     14. `abyssal_trench_caves` (deep ocean bioluminescent dreugh/sload caverns)
   * Strip all vanilla hostile mob entries (`minecraft:zombie`, `skeleton`, `spider`, `creeper`, `enderman`, `witch`, `slime`) from all underground biome JSONs in `biomes.py`, replacing them with native NirnCraft creature spawn tables.
3. **True Surface-Piercing Skylights (`lightShaft`), Underground Waterfalls & Subterranean Ruins (`CavernGen.java`, `CavernDress.java`, `Undersea.java`):**
   * In `CavernDress.lightShaft()` (`lines 733–761`), check that `(x, z)` at `surf = g.p.surfaceAt(x, z)` is not on a road (`!PlanGround.isRoadOrPlot(x, z)`) or underwater (`surf > sea + 2`). When clear, carve the shaft **all the way through `surf + 1`** with weathered rock/moss rims and hanging vines (no `SEA_LANTERN` ceiling disc!), allowing natural sunlight, shader god-rays, and rain to illuminate the mossy oasis and pool on the cavern floor.
   * Enhance `CavernDress.waterfall()` to build multi-block stepped ledges and mist pools fed from upper aquifers or surface rivers, and add subterranean shrine/hermitage/ruined-outpost set-pieces in open caverns.
   * In `Undersea.java`, add `seaCavernWithAirPocket()` generating submerged rock grottos along coastal drop-offs and `OceanIsles` reefs with trapped breathable air domes, glowing coral/kelp, and sunken chests.

## 4. Verification Gates

* All `14` underground biomes generate via `biomes.py` with `0` vanilla hostile mob spawner entries.
* `CavernDress.lightShaft()` carves through to open sky (`level.canSeeSky(floorPos) == true`) on dry non-road terrain.
* `Undersea` sea-caverns generate breathable air pockets below sea level.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
