# R3b Micro-biomes (MS21)

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R3b-micro-biomes.md`." One agent. Globally serial with any
> V2/V3/V5 step (shared rasters and `palettes.json`).

## Goal

Regions stop being one big biome each. Every region becomes a mosaic of micro-biomes driven by geography (elevation
and snowline, slope and aspect, rain shadow, water proximity, heat, soil, disturbance), each shaping flora, ground,
weather odds, ambience, creatures and what people do there, and each citing its cause in the region's sheet.

## Context to read

* `docs/world/DESIGN-PRINCIPLES.md` 4.0 G12, 4.11 MS21, 5.4 F-MICRO; the first principle (L2, L4 citations).
* `docs/REGION-AUDIT.md` (palette blending, ecotones), `docs/FLORA.md`, `docs/HYDROLOGY.md`.
* `docs/world/provinces/skyrim.md` section 2 (Skyrim's micro-biome lists per hold) and `tamriel/world/skyrim.json`
  (R3a).
* Code: `tamriel/TamrielBiomeSource.java`, `tamriel/TamrielMap.java` (ridge, coast and river distance fields),
  `tools/worldgen/{biomes,ecotones,tamriel_map}.py`.

## Write set

`mod/src/main/java/com/skycraft/tamriel/{TamrielBiomeSource,TamrielMap}.java` (micro selection and the two raster
reads only), `mod/src/main/java/com/skycraft/tamriel/biome/**`, `tools/worldgen/{biomes,ecotones,tamriel_map}.py`
(micro parts), `mod/src/main/resources/skycraft/tamriel/palettes.json`, the two new rasters next to `features.png`,
generated `data/skycraft/worldgen/**`, `tools/qa/region_audit.py` (MS21 checks), Skyrim's micro entries in
`region_spawns/skyrim.json` and the weather tables (keys only).

## Steps

1. **Rasters.** In `tamriel_map.py`, bake at 32-block cells: `rainshadow.png` (from the ridge field and each region's
   L2 prevailing wind; one wind direction per region from the sheet, default west) and `heat.png` (authored heat
   areas in `tamriel_vectors.json`: Eastmarch's springs, Vvardenfell, the Velothi foothills, the Dragontail). Our own
   data; nothing from a game raster.
2. **Drivers in Java.** Slope and aspect from the macro height already sampled per column; rain shadow and heat from
   the rasters; distance to water, coast and ridge from `TamrielMap`; settlement farmland rings from places. No new
   noise; a few table lookups per biome query.
3. **Rules.** Per region a `micro` table in `palettes.json`, written by `biomes.py`:
   `{context, when: {aspect, rainshadow, heat, elev, water_dist, ridge, soil}, biome | overlay, cites: [sheet ids]}`,
   e.g. `UPLAND & aspect=north & rainshadow<0.3 -> wet_pine_glade`, `LOWLAND & heat>0.6 -> hot_spring_moor`. Selection
   inside each `TamrielBiomeSource` context after the existing palette and ecotone choice.
4. **Effects.** Each micro-biome sets flora and ground cover (sub-biome or surface overlay), fog, storm and snow odds
   (weather table keys), an ambience bed key, `region_spawns` keys, and a "work" tag the settlement grammar and minor
   sites can read (farms in valleys, mines in crags, fishers on coasts, herders on moors).
5. **Pilot: Skyrim's nine holds** from `provinces/skyrim.md` section 2 (hot springs in Eastmarch's tundra, gorge
   meadows and crag scrub in the Reach, birch glades in the Rift, salt marsh in Hjaalmarch, sea cliffs in Haafingar,
   glacial moraine in Winterhold, tundra rock fields in Whiterun...).
6. **Checks.** `region_audit.py`: distinct micro-biomes per region and per km², transitions per km of road (biome
   census along the baked road plan), rule coverage per region (fail < 0.5% or > 60%), every rule cites a resolving
   sheet field.

## Verification while working

`py -3 -I tools/worldgen/tamriel_map.py`, `py -3 -I tools/worldgen/biomes.py && py -3 -I tools/worldgen/noise_settings.py`,
`region_audit.py --province skyrim`, `/skybiome census` and `/skycraft-dev regen` on the dev server (time per chunk),
`region_render.py` contact sheet to Haiku ("does each patch match its land, weather and people?").

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Micro-biomes per hold | `region_audit.py` | >= 6 per hold, >= 2 per km² |
| Transitions | `region_audit.py` along the road plan | >= 3 per km of road |
| Coverage | `region_audit.py` | no rule < 0.5% or > 60% of a region's land |
| Causes | `worldsheet_lint.py` | every micro rule cites a resolving field |
| Cost | `/skycraft-dev regen` timing before/after | chunk time within +5% |
| Seed independence | `qa.ps1 -Server -Bake` | byte-identical bake |
| Look | Haiku review of the region sheet | no blocking issue |

## Merge and report

`_COMMON.md` section 7 (biomes conflicts: regenerate). Record F-MICRO per hold in WORLD-PLAN. Report per section 8.

**Size:** M-L.
