# R13 P7 Endless lands

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R13-endless-lands-p7.md`." One agent. Globally serial with
> V2/V3/V5 steps (it touches `worldplan` and `TamrielMap`). Free-slot work: may run any time after R2.

## Goal

WORLDGEN-PIPELINE "Later P7": beyond the authored map, the world goes on with the same plan-first pipeline, per 4 km
tile, planned lazily on a worker thread and cached in level data, so roads, crossings, sites, scenes and terrain
behave exactly as on the authored map. Each tile gets a small **procedural world sheet** (L1-L11 generated from the
tile's climate band, coast and neighbours) so the coherence rule holds in the endless lands too.

## Context to read

* `docs/WORLDGEN-PIPELINE.md` (all; sections 2, 4, 6-9 are the contracts this phase reuses).
* `docs/world/DESIGN-PRINCIPLES.md` first principle (the sheet), 4.1 (half density for lands beyond Tamriel).
* Code: `worldplan/{RoadPlan,RoadPlanBuilder,PlanRouter,PlanTerrain,PlanGround,Profile}.java`, `worldplan/scene/**`,
  `worldplan/street/**` (P5), `tamriel/TamrielMap.java` (what lies beyond the map today: the `Beyond` noise),
  `bake/**`, `perf/**` (pregenerator).

## Write set

A new `mod/src/main/java/com/skycraft/worldplan/tile/**` package; `worldplan/**` (additive: a tile-scoped builder
entry point, no change to the baked format); `tamriel/TamrielMap.java` (beyond-map sampling only); level data saved
data class (new); `mod/src/test/**/worldplan/tile/**`; `docs/WORLDGEN-PIPELINE.md` (a P7 result section).

## Steps

1. **Tile frame.** 4,096-block tiles outside the authored map's land mask; a tile's inputs are the beyond-map macro
   terrain, hydrology run at tile scale with fixed boundary conditions shared with neighbours (rivers and roads cross
   tile edges at hashed edge points, so neighbours agree without reading each other), and a procedural place list
   (settlements, dungeons, landmarks at half density, from a tile hash).
2. **Procedural sheet.** Generate L1-L11 per tile from the climate band, coast, relief and the nearest authored
   province (culture spill-over); places, scenes and dungeons carry `why` from it; `worldsheet_lint` accepts tile
   sheets.
3. **Plan per tile.** Run the existing builders (road plan, scene plan, street plan for its settlements) on the tile,
   off-thread, on first request of any chunk in the tile or its neighbour ring; cache in level data; chunks evaluate
   the tile plan with the same query APIs (`RoadPlan.at` contract) through a composite that consults the baked plan
   first, then the tile plans.
4. **Seams.** At the authored map's edge, authored roads that point outward end at an edge point the tile planner
   honours; nothing in the baked plan changes.
5. **Performance.** No main-thread stall: a chunk whose tile is not planned yet waits on the worker with the pregen
   prewarm logic, never plans on the server thread.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Plan time | tile planner timing | < 2 s per tile on the worker |
| Stalls | `/skycraft-perf` (or JFR) | 0 main-thread stalls > 50 ms caused by tiles |
| Roads | `road_measure.py` / `p3_corridors.py` on 2 generated tiles | 0 gaps, 0 drops > 1 without a step, 0 unbridged |
| Seams | a corridor crossing the authored edge | 0 gaps |
| Determinism | Tier 0 test | same tile plan bytes under two seeds for the plan layer; reload from level data equals recompute |
| Coherence | `worldsheet_lint` on 3 tile sheets | 0 errors |
| Bake | `qa.ps1 -Server -Bake` | authored bake byte-identical (unchanged) |

## Merge and report

`_COMMON.md` section 7 plus `full_corridors.ps1`. Write the P7 result section in WORLDGEN-PIPELINE. Report per
section 8.

**Size:** L.
