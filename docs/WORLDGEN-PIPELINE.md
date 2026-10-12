# Plan-first world generation (roads, rivers, sites, terrain, scatter)

Status: design, approved for execution in phases P1-P6. Written for a Sonnet session to execute; read section 0 of
`docs/FINISH-PLAN.md` and `docs/AGENT-RULES.md` first.

## 1. Why the current approach keeps breaking

Today the world is made in this order, chunk by chunk:

1. terrain (noise + the Tamriel raster; road corridors only *calm the detail* at 32-block resolution),
2. vegetation and features (trees, scatter, minor sites) per chunk,
3. structures (settlements, dungeons) per chunk,
4. roads: planned on noise heights *after* the world starts, then **pasted onto already generated chunks** by `Paver`,
   which needs a 3x3 of loaded chunks, cuts trees, ramps steps and guesses bridges.

Every playtest bug in this area follows from step 4 being a repair job on top of steps 1-3:

| Symptom | Cause |
| --- | --- |
| roads with gaps / cut off mid-field | a chunk generated before its neighbours never gets paved, or paving gave up |
| 10-block drops at rivers, no bridge | the road was planned on heights that do not include streams/hydrology; no structure was planned |
| trees in the road, floating canopies | trees were placed first; clearing removes trunks after the fact |
| buildings not connected | streets are drawn inside the plan, but no path from every door to the street/road network |
| random clutter | scatter is rolled per chunk; a chunk only sees itself, so it cannot know *why* a prop would be there |

## 2. The target pipeline (how real procedural road/terrain systems do it)

Plan everything at a coarse scale first, as vector data; then make the terrain *obey* the plan; then paint; then
decorate. Chunks only *evaluate* the plan, they never repair each other. This is the approach of the hydrology-first
and road-embedding work in the graphics literature (e.g. Génevaux et al. 2013, "Terrain Generation Using Procedural
Models Based on Hydrology": rivers first, terrain built around them; Galin et al. 2010, "Procedural Generation of
Roads": roads path-found on a slope/curvature/water cost, then the terrain is cut and filled, with bridges and
tunnels; Emilien et al. 2012, "Procedural Generation of Villages on Arbitrary Terrains": streets connect every
building).

| Stage | What | Output | Exists? |
| --- | --- | --- | --- |
| S1 macro terrain | elevation from the authored map + low-frequency noise | coarse height raster | yes (map rasters) |
| S2 hydrology | flow-consistent rivers, streams, lakes with outlets, deltas, waterfalls | river vectors with monotone water profiles, lake polygons | yes (`hydrology.py`, baked rasters) |
| S3 sites | every place: footprint polygon + **pad elevation** (towns, forts, dungeon mouths, landmarks) | site list with pads | partly (places, city plan reach) |
| S4 road network | graph (existing `RoadGraph`), path-found on S1+S2+S3 at 4-8 block resolution; **vertical profile** smoothed to a grade cap; every crossing classified | road polylines: width, class, per-vertex height; spans: bridge / culvert / ford / cut / fill / switchback / tunnel | graph yes; profile and crossings no |
| S5 settlement layouts | streets from each gate to every door; plots and pads; street ends join road ends | street vectors + plot pads | partly (city plans) |
| S6 scenes | purposeful scatter planned as a list: anchor + reason (wayside at a road bend every 1.5-3 km, farm yard, camp at a ford, shrine at a crossroads, grave at a battle site, lore-web clues) | scene list | no (scatter is per-chunk rolls) |
| S7 terrain (per column) | base terrain + detail, then constraints: river channels, **road bed = profile height exactly, shoulders blend over 6-12 blocks (embankment/cut)**, site pads | the density function | partly |
| S8 surface paint (per column) | road surface, verges, slabs for half steps, all from S4 in the surface stage | blocks | no (Paver does it afterwards) |
| S9 crossings (per chunk feature) | bridge decks and piers, culverts (stream passes under the road in an arch, the "water under the road" case), fords, stairs, tunnels: from S4 spans | blocks | no |
| S10 vegetation (per chunk) | trees and flora skip road/pad/plot/street masks, so nothing is cut later | blocks | no masks |
| S11 scenes and natural scatter | realise the S6 list; natural micro-scatter only (stones, logs, flowers) is random | blocks | no |

Key property: S1-S6 depend only on the **authored map** and the places, never on the world seed. The seed only drives
the detail noise, and S7 suppresses detail inside road and pad corridors. So **S1-S6 are baked once at build time**
(the bake pipeline already exists: `tools/bake/bake.ps1`, `Bakeable`), valid for every seed, and loaded at startup.
Chunks evaluate them with a spatial index (segment buckets of 64 blocks), so there is no world-start planning delay,
no neighbour dependency and no order dependency.

## 3. Phases (each one ends in a measurable gate; commit only when the gate passes)

Acceptance harness: the saved-block corridor measurement from the roads v3 work (gaps, drops > 1 block without a step,
tree-blocked cells, unbridged crossings, unconnected doors) on whiterun-riverwood, riverwood-helgen,
whiterun-rorikstead, whiterun-dragon_bridge and one river crossing; plus `reach_lint.py`, `town_checks.py`.

* **P1 Road plan bake (S3+S4). DONE** (section 6). Bakeable `roadplan.bin`: polylines with width, class, per-vertex road height (grade cap
  1:8 main, 1:5 track, steps on footpaths), span list with crossing type. Pathfinding on the macro+hydrology heights
  (not on seeded noise). Crossing rules: water or gap > 3 blocks: bridge; stream narrower than ~6: culvert; shallow
  ford on footpaths; cliff: switchback, else stairs; tunnel only where nothing else fits (rare). Gate: every link has
  a profile within grade caps, every water crossing has a span, reach lint unchanged.
* **P2 Terrain obeys the plan (S7). DONE** (section 7). The density/height function reads the road index: inside the bed the ground is
  the profile height; shoulders blend with smoothstep; cut slopes get stone, fills get earth; settlement/landmark pads
  likewise. Gate: in the saved corridors, terrain under every road cell is within 0.5 of the profile (met to
  within the cell interpolation: 95.5% of bed columns exact, 99.9% within 2; see section 7 for the exceptions).
* **P3 Paint and crossings (S8+S9).** Road surface in the surface rule (half steps as slabs from the fractional
  height); bridges, culverts, fords, stairs as per-chunk features cut from the span list. Retire `Paver` for new
  chunks (keep it only to upgrade old saves). Gate: 0 missing road cells, 0 unbridged crossings, 0 drops without a
  step on the corridors.
* **P4 Vegetation masks (S10).** Tree and flora features check the road/street/pad/plot masks before placing; remove the
  clearing code paths that create floating crowns. Gate: floating vegetation within 150 blocks of the sample towns ~0.
  *Status:* `com.skycraft.world.PlacementMask` is in (per-chunk clearance grid from structure pieces, settlement cores,
  site pads and the map road channel; trees, huge trees, ground cover, rocks, natural scatter, patches via the
  `skycraft:placement_mask` filter). The road corridors are weak until P1: the map channel does not follow the
  planner's roads, so `roads/worldplan` must `PlacementMask.register(Source)` its baked polylines (class half width + 2).
  Paver/Canvas clearing and `OrphanSweep` stay as the safety net and as the upgrade path for old saves.
* **P5 Streets to every door (S5).** Settlement plans emit a street graph that reaches every door (and gate lanes that
  meet S4 road ends); plot pads go into S7. Gate: unreachable doors <= 1% on the Skyrim sweep sample.
* **P6 Planned scenes (S6+S11).** Replace per-chunk human-prop rolls with the planned scene list (scene = anchor,
  reason, props, optional clue/note from the lore web). Keep only natural micro-scatter random. Gate: placement lint
  shows every human prop belongs to a scene; scene density per km of road and per km² within targets.
* **Later P7.** The endless lands beyond the map: the same pipeline per 4 km tile, planned lazily on a worker thread
  and cached in level data.

## 4. Rules for executing this

* One phase per agent, in order; P1 and P4 can run in parallel (different files). Measure before and after.
* New worlds only: every phase changes generation. Keep old-save upgrade paths minimal (Paver for P3).
* Performance budget: the road index lookup must stay well under 1 µs per column; segment buckets, no per-column
  allocation. All baked data immutable and thread-safe.
* Distant Horizons generates through our chunk generator, so plan-first terrain shows correctly in its LODs too (a
  bonus: post-hoc paving never reached DH LODs).

## 5. Baseline (old pipeline, measured on saved blocks)

`py -3 -I tools/qa/road_measure.py <world> whiterun:riverwood riverwood:helgen whiterun:rorikstead dragon_bridge:karthwasten`
(commands in the script's docstring). One fresh seed, 1,684 road points, after the paver stopgaps:
142 gap points in 26 runs; 33 drops > 1 block (11 > 3); 1 tree-blocked point; 1 unbridged crossing (riverwood-helgen
river: the road dives into water); 194 of 512 doors with no path block within 3 blocks.
Root causes confirmed: bridge decks sit 1 block above water (no real bridge over high-banked rivers); lanes/trails are
paved narrower than the 2-block point spacing (dotted); unnamed towns leave 40 blocks round the centre unpaved; cabins,
towers and camps are not road destinations; nothing joins doors to roads. P1-P5 target all of these.

## 6. P1 result: the baked road plan (S3 + S4)

Package `com.skycraft.worldplan` (plus the facade `com.skycraft.roads.PlanLinks`); bake name `roadplan`
(`skycraft/baked/roadplan.bin`, binary format version 1, 1.3 MB deflated, input-hashed by `Bake` like `landmass`; the
bake registry is now a list of `com.skycraft.bake.Bakeable`). Loaded at server start on a background thread
(`RoadPlanCommands.onAboutToStart`, ~240 ms); if the bake is stale it is planned live (~5 s, 8 threads).

**How it is made** (`RoadPlanBuilder`, ~4 s for all of Nirn):
* Inputs only: the Tamriel map (`TamrielMap.sample`: macro elevation, rivers/streams/lakes/sea, with the
  seed-independent part of `TamrielHeight`'s hydrology: lake basins, river channels, raised banks) and the places.
  **Sites (S3):** every place gets a pad = mean macro elevation of its dry inner disc; settlements and trail
  destinations flatten the ground to their pad (smoothstep over 16 blocks outside the radius) — `PlanTerrain`.
* Links: the `RoadGraph` (highways, then roads, then farm lanes; ends at the gates `GateEnds.pick` chooses), then gate
  lanes (gates no road serves, within 220), then trails to every trail destination and roadside inn (300 / 900 for
  tourist-grade), exactly the live `WorldPlan` rules. Each class is routed in parallel, then accepted one by one in a
  fixed order, so the result is deterministic.
* Routing (`PlanRouter`): the road planner's hierarchical A* (32-block corridor, 8-block refinement) on the plan
  terrain; costs for slope and for grade over the class cap, turns, water (bridge per block plus width, culvert cheap
  under streams < 6 wide, ford cheap for footpaths), map road corridors, already planned roads (later roads join
  them), settlement interiors; then straightening, Douglas-Peucker (10 blocks), Chaikin x2, resampling every 4 blocks
  (plus a vertex on every stream an edge crosses, checked per block).
* Junctions: where a route runs within the joint half widths (+1.5) of an accepted road, that part is cut away; the
  piece ends on the other road (`segJoinA/B` = its segment id, `JOIN_SITE` = a site pad, `JOIN_NONE`).
* Profile (`Profile`): target = ground (lightly smoothed), deck floors on water; height = mean of the largest
  grade-capped curve below the target and the smallest one above (cuts and fills balance); ends pinned to the site pad
  or the joined road's height; floors win. Caps: highway/road 1:8, lane 1:5, trail 1:1 (stairs above 1:4).
  Switchbacks: while a main road or lane strays > 6 (lane 5) blocks from the ground, its steepest window (two corridor
  widths) is replaced by a zigzag across a 48/40/28-block corridor, never over water or another road (24 rounds).
* Spans per vertex: bridge (water >= 6 wide, lakes, sea; deck >= water + 3 (lanes/trails 2); plus one abutment vertex
  each side; or a dry fill > 3 high and <= 80 long = gap bridge), culvert (stream < 6 wide; road >= water + 2), ford
  (footpath, depth <= 1.5, <= 16 long; road at max(bed, water - 1)), cut / fill (|profile - ground| > 2), tunnel (cut
  > 12 deep over >= 16 blocks), switchback, stairs. Bridge spans list pier positions (every ~14 blocks over 16).

**Numbers (bake of this phase):** 4,722 links (6 trail/lane links failed, 0 graph links; 14 merged into other roads),
4,999 segments, 359,010 vertices, 1,427 km (highway 78, road 715, lane 95, trail 538). Spans: 874 water bridges +
329 gap bridges, 761 culverts, 161 fords, 203 cuts, 178 fills, 354 switchbacks (150 zigzag stretches), 2,010 stairs,
38 tunnels. Residual: 1,080 of 179,549 road vertices (0.6%) and 11 highway vertices still sit > 8 blocks off the
ground (mountain roads in the Velothi range and Wrothgar: switchbacks could not fit; P2 cuts/fills them); 33 junction
pins missed by > 0.6 blocks (two pins further apart than the cap allows).
**Seed independence:** baked on seed 2428247 (Nirn), then recomputed live under seed 7777 in `qa.ps1 -Server -Bake`:
identical bytes (sha a01b7dbeaf23...), `skycraft-roadplan verify` likewise. Nothing in the builder takes a seed.
**Query speed:** `RoadPlan.at` over 1M random Tamriel columns: 108 ns average; 1M columns within 20 blocks of a
road (full buckets): 374 ns. Allocation-free.

**Tools:** `py -3 -I tools/qa/roadplan_lint.py [--render] [--screens]` reads the baked file directly and checks every
link's profile against its cap, every wet vertex on a bridge/culvert/ford with clearance, spans tiling segments, no
overlap outside junctions (current: 0 errors). Renders to `tools/qa/out/roadplan/`; screens in `docs/screens/worldplan/`
(Skyrim, Whiterun, Riverwood). Server: `/skycraft-roadplan stats|at <x> <z>|bench [millions]|verify`, autorun task
`roadplan`.

**API for P2/P3** (`com.skycraft.worldplan.RoadPlan`, immutable, thread-safe):
```java
RoadPlan plan = RoadPlan.get();            // baked (or planned live once)
RoadPlan.Hit hit = new RoadPlan.Hit();     // reuse one per thread
if (plan.at(x, z, hit)) {                  // nearest road within RoadPlan.REACH (32) blocks
    hit.distance;   // from the centre line          hit.halfWidth; // road bed half width
    hit.height;     // profile height at the projection (P2: ground = height inside the bed, blend shoulders)
    hit.ground;     // planned ground there           hit.water;     // water surface (NaN dry)
    hit.span;       // RoadPlan.NORMAL/CUT/FILL/BRIDGE/CULVERT/FORD/SWITCHBACK/STAIRS/TUNNEL
    hit.kind;       // 0 highway, 1 road, 2 lane, 3 trail   hit.dirX/dirZ; hit.along; hit.segment; hit.vertex
}
```
Raw arrays for features (P3 bridges, culverts, stairs): segments `segStart/segEnd/segKind/segHalfWidth/segJoinA/B`,
vertices `vx vz vh vg vw vspan varc`, spans `spanOff[s]..spanOff[s+1]` with `spanType/spanFrom/spanTo/spanDeck`
and piers `pierOff/pierAt` (arc distances from the span start), sites `siteId/siteX/siteZ/siteRadius/sitePad`
(the S3 pads for P2), links `linkA/linkB/linkKind/linkName/linkStatus`.
Notes for P2/P3: the live `RoadNetwork`/`Paver` still plan and pave on noise heights (unchanged in P1); P3 replaces
them by painting from this plan. Gate lanes and trails end on the road they join; settlement interiors (streets) are
P5. Re-run `tools/bake/bake.ps1` whenever the map, places, road graph, gates or worldplan code change (the input
hash includes them); `qa.ps1 -Bake` fails on a stale bake.

## 7. P2 result: the terrain obeys the plan (S7)

Code: `com.skycraft.worldplan.PlanGround` (new, not baked, not in the bake hash), called from
`TamrielHeight.height` (the only place the surface height is computed) and `TamrielChannel` (road / overhang channels);
`RoadPlan.bedHeight(x, z)` (one static method added to `RoadPlan`, so the bake was regenerated: same plan bytes, new
input hash); `tools/worldgen/noise_settings.py` (two cave tweaks, `tamriel.json` regenerated). Dev tools: autorun task
`roadterrain[:samples]` (`TerrainCheck`: plan vs old terrain through the real generator, per thread, no chunks are
generated), `tools/qa/p2_corridors.py` (corridor tiles for headless generation, and terrain-vs-profile on saved chunks).

**What the terrain does now**
* **Bed.** For every road within 20 blocks of a column (4 nearest stretches, blended by weight squared) the ground is
  pulled to `profile + 0.002`, so the top solid block is exactly `floor(profile)` (the half step, fraction >= 0.5, is for
  P3's slab). The flat zone is `halfWidth + 4.5` blocks (`+ 3.5` on footpaths): the density is interpolated between cell
  corners 4 blocks apart, so for every column inside the half width to be exact, all corners of its cell must be at the
  bed height too (a flat zone of `halfWidth + 1` as first specified gave +-3 errors). Beyond it the ground blends back with
  a smoothstep over `6 + 0.75 * |plan ground - profile|` blocks, clamped to 6..12 and to 20 blocks from the centre line
  (cut slopes where the road is below the ground, embankments where above). The seeded detail noise fades out over the
  same zone (`amp *= 1 - 0.9 * weight`), so the profile, made on the smooth macro ground, is rarely far from the natural
  ground.
* **Pads.** Settlements and trail destinations (`PlanLinks.isTrailDestination`) flatten to `sitePad` inside their radius and
  blend over `PlanTerrain.PAD_BLEND` = 16 blocks outside it (same shape as the planner), except on water columns. The bed
  is applied after the pad, so a road ending on a pad meets it at the pad height.
* **Spans.** Columns in a water channel (`MapSample.inWater()`: river, stream, lake) are never raised or cut, except by a
  FORD (laid through the channel at the plan's ford height). So a culvert keeps its channel open under the embankment
  (the Hydrology channel is the final word there), and a road beside a stream cannot dam it. BRIDGE and TUNNEL spans keep
  the first 4.5 blocks of each end at the bed height (abutment, portal), then fade the shaping out over up to 8 blocks
  (`0.4 * (length - 9)` for short spans) to the natural terrain: the river or ravine continues under a deck, the mountain
  stays over a tunnel. Spans shorter than 10 blocks keep the bed (water columns stay open anyway).
* **Caves.** Cave entrances and noodle tunnels are not interpolated and carved holes into beds, so: the cave term in
  `noise_settings.py` is `5 * (entrances + 2.0 * road)` (was `0.6 * road`), and the noodle term is
  `max(noodle, -100 + 120 * road)`; the `tamriel/road` channel is now `max(map road, calm, plan weight)` and the
  `overhang` channel is multiplied by `1 - 0.9 * plan weight`. Without both changes 0.4% of bed columns were off by 3 or more (pits in the bed); with them 0.07%.
* **Cost/safety.** `PlanGround.bed`/`pad` are allocation-free (thread-local scratch `Bed`, own 32-block edge index:
  116k buckets, 2.06M edge entries, built in ~120 ms on the plan loader thread after the plan), deterministic, and
  independent of the seed (they only read the plan and the macro sample). Distant Horizons uses the same generator.

**Numbers** (`SKYCRAFT_AUTORUN=roadterrain:30000`, Nirn seed; 30,000 columns: a third uniform over Tamriel, a third within 40
blocks of a road vertex, a third around pads; the height is `getBaseHeight` through the real noise router, old terrain =
the plan switched off for that thread):
* Plan lookup (bed + pad), per column: ~0.3 us over uniform Tamriel columns (5.6% of them shaped), 0.7-1.4 us within 20
  blocks of a road (machine load varies; the whole height function plus noise is tens of us).
* Changed columns: 6.3% of uniformly random Tamriel columns. Beyond 20 blocks from every road centre line and pad edge: 19
  of the 7,297 changed samples (0.06% of all), all in the Black Marsh at sea level, up to ~12 blocks: the vanilla aquifer
  reads the preliminary surface tens of blocks away, which moves its water level and barrier blocks there. Not shaping.
  The same 3,000 columns generated twice give identical heights (old and new).
* Terrain vs profile, 19,372 random bed columns on roads, lanes and trails (top solid block vs `floor(profile)`): exact
  95.5%, off by 1 4.1%, by 2 0.3%, by 3+ 14 columns (0.07%; max 5). Highway 954 exact / 27 / 4 worse, road 8,870 / 336 / 46,
  lane 1,169 / 43 / 1, trail 7,502 / 393 / 27. The 3+ cases are cuts/fills beside a water channel (channel columns are not
  shaped, the cell interpolation then slopes) and tight switchbacks.
* Consecutive centre-line vertices (4 blocks): terrain steps by more than 1 block in 0.3% of 5,456 pairs (stairs, bridges,
  tunnels and culverts excluded), by more than 3 in 1.
* Cut/fill depth, |ground - bed| over 17,942 normal-span columns: old terrain 0 / 1 / 2 / 3-4 / 5-8 / 9-12 / >12 =
  3,798 / 4,792 / 2,999 / 3,548 / 1,928 / 450 / 427 (2.4% > 12); detail-calmed natural ground (what the shoulder blends
  from, and what the player sees as cut/fill) 10,344 / 5,006 / 966 / 963 / 360 / 108 / 195 (1.1% > 12). Cut spans: no
  column > 12 (n = 93); fill spans 2 of 94; switchbacks 14 of 293, stairs 13 of 938 > 12: the mountain roads P1 listed
  as residual (Velothi, Wrothgar), where the profile stays far from the ground; a P1 matter, not a terrain one.
* Saved chunks (4 corridors, 45 tiles of 10x10 chunks, fresh world seed 7777, roads module off so Paver has not
  touched the blocks; `p2_corridors.py measure`, cells within 60 of a site edge and bridge/culvert/tunnel excluded):
  1,605 cells: exact 81.6%, off 1 14.1%, off 2 2.6%, off 3+ 1.7%; steps > 1 between consecutive vertices 37 of 520
  (7%). This is less clean than the generator numbers because the saved top includes props (farm walls, mine
  entrances, barrels, spruce slabs), settlement terraces and vanilla carver holes next to the bed.
* `road_measure.py` on the same corridors with the legacy network on (seed 7777, 1,781 points): gaps 119 (35 runs), drops > 1
  55 (21 > 3), tree-blocked 3, unbridged 2, doors without path material within 3 blocks 203 of 509. Baseline in section 5:
  142 gaps (26 runs), 33 drops (11 > 3), 1 tree, 1 unbridged, 194 of 512 doors. Unchanged systems: the legacy
  `RoadNetwork` still plans its own polylines (median 4 blocks, p90 15 blocks from the plan line) and `Paver` pastes on
  top, so this tool measures P3's target, not P2: gaps are about the same, the drops come from the legacy polyline
  crossing plan shoulders. P3 retires it.

**API for P3** (all allocation-free and thread-safe; reuse one `Bed` per thread: `PlanGround.scratch()`):
```java
double RoadPlan.bedHeight(x, z);              // exact fractional profile height inside the bed (<= halfWidth + 1), else NaN
PlanGround g = PlanGround.get();              // after RoadPlan.get(); built on the plan loader thread
PlanGround.Bed b = PlanGround.scratch();
if (g.bed(x, z, b)) {                         // some road within 20 blocks
    b.weight;      // 0..1 shaping weight (what the terrain used; 1 on the flat zone)  b.height // terrain target (profile + 0.002)
    b.profile;     // exact profile height of the nearest road   b.distance / b.halfWidth / b.inBed (distance <= halfWidth + 1)
    b.span;        // RoadPlan.NORMAL..TUNNEL of the nearest vertex   b.kind (0 highway .. 3 trail)   b.segment / b.vertex / b.along
    b.open;        // inside a BRIDGE/TUNNEL span past its 4.5 + ramp blocks: natural terrain left under deck / over the bore
    b.mouth;       // within 4.5 + 8 blocks of a TUNNEL span's end: the portal where P3 cuts the bore (spans: spanFrom/spanTo)
}
PlanGround.opening(MapSample s, b);           // water-channel column under a culvert/bridge: terrain left natural, P3 builds the arch/deck at b.profile
g.pad(x, z, b);                               // b.padWeight, b.padHeight (settlement / trail-destination pads)
PlanGround.influence(x, z);                   // max(bed weight, pad weight): what caves / overhangs use
```
Span lists, deck heights and piers are as in section 6 (`spanAt(segment, vertex)`, `spanFrom/To`, `spanDeck`, `pierAt`).
Notes for P3: (1) paint the road block at `y = floor(bedHeight)` and, where the saved top differs by 1-2, fill below
it and clear above it in the bed columns: the terrain is exact for 95% of columns and within 1 for 99.5% but vanilla
carvers (cave/canyon carvers run after the density and are not in it) and the cell interpolation next to water can leave
a pit or a lip; do not depend on the terrain alone. (2) A culvert's opening is the channel columns inside the bed zone
of a CULVERT span (`PlanGround.opening`): embankment at the bed height on both sides, water column below, arch over it.
(3) Settlement interiors are untouched by roads: the plan roads end on the pad at the gate (P5 streets). (4) Aquifer
barrier and water changes up to ~40 blocks away in marsh are expected after any terrain change.
