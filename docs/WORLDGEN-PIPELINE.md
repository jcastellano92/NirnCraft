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
* **P3 Paint and crossings (S8+S9). DONE** (section 7b). Road surface in the surface rule (half steps as slabs from the fractional
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
* **P5 Streets to every door (S5). DONE** (section 7c). Settlement plans emit a street graph that reaches every door (and gate lanes that
  meet S4 road ends); plot pads go into S7. Gate: unreachable doors <= 1% on the Skyrim sweep sample.
* **P6 Planned scenes (S6+S11). DONE** (section 9). Replace per-chunk human-prop rolls with the planned scene list (scene = anchor,
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

## 7b. P3 result: roads, crossings and the network paint from the plan (S8 + S9)

Code (package `com.skycraft.roads`): `RoadPaint` (the world generation feature `skycraft:road_paint`, added to every
overworld biome at `top_layer_modification` by the biome modifier `z_road_paint`, so it runs after the vegetation and
the landmarks), `RoadMask` (the plan as a `PlacementMask.Source`), `PlanRoute` (the network's polylines come from the plan);
changed: `RoadCells`, `Paver`, `RoadNetwork`, `RoadsData`, `Road`, `RoadsConfig`, `RoadsModule`, `RoadLayout`,
`PlacementMask` (+`structureGrid`, `useMapRoadChannel`), `scatter/RoadProbe`. Nothing the bake hashes changed.

**Painting.** Per column of the chunk being decorated, no neighbour read, no order dependence, idempotent, written only
inside the chunk (so Distant Horizons, which runs the same generator, shows the roads): `RoadPlan.at(x+.5, z+.5)` gives
distance, class half width, span; the surface height is `floor(profile at the column corner + 0.002)` (the height the
terrain was shaped to in P2, centre height on steep stretches). Inside the half width: refill below (dirt then stone, until it
rests on something solid: carver pits, caves, water), clear 4 blocks above (leaves, logs, plants, snow, props), the culture's
`RoadStyle` surface mix (highways get edge stones on the outer column), a slab on top where the fraction is >= .5 on
highways and roads, half-block curbs where a town pad is near; 1.5 blocks of worn verge beyond (coarse dirt, vegetation
cleared). Columns inside a structure piece (`PlacementMask.structureGrid`, pad 0) are only blended: a plain ground top with
air above becomes road, so a road runs through a gate; nothing is cleared there.
**Crossings, all from span data:** bridge (also over dry gaps): deck at the profile height; highways and roads a stone arch
(bays between the planned piers, rise from the clearance, full-width piers down to the bed, solid abutments in the first
and last 3.5 blocks), lanes and trails a plank deck on beams and log piers; parapet wall or fence connected from the plan,
a taller post with a lantern every 8 blocks. Culvert: the channel columns (map water) stay open under the deck, lintel and
stone-brick lining on the columns beside the channel. Ford: gravel and cobble flush with the water. Stairs (and any
steep stretch, grade > 1:4): stair blocks facing uphill where a row starts a step, full blocks on the landings, a lantern
post beside every 8th row. Tunnel: a bore of 4 (3) blocks between the mouths, stone-brick lined, a hanging lantern every 8
blocks, a taller portal frame in the first and last 2.5 blocks.

**Masks, cells, network.** `RoadMask` registers with `PlacementMask` (distance to the centre line minus half width minus
2) and switches the map's road-channel stand-in off, so trees keep off the real roads only. `RoadCells.isRoad/kind` read
`RoadPlan.at` (any thread; the legacy network is consulted only for roads of old saves, server thread), and `scatter/RoadProbe`
now uses it on worldgen threads too. `RoadNetwork` takes each link's polyline from the plan (`PlanRoute`: graph links by
place pair, gate lanes by `placeId|@gateN`, trails by `placeId|@road`; resampled to 2 blocks, chords across junction
cuts, bridge vertices flagged WATER): 4,688 roads (1,443 km) in ~0.0 s of planner time instead of A* on noise heights,
12 links applied per planning call. Travelers, carriages, gates, signposts, inns, wayshrines and dressing therefore stand on
the roads that are painted (posts stand one block further out on plan roads). A link the plan does not have (a generic
structure-found settlement) is dropped unless the legacy paver is on.
**Config** `roads.legacyPaver` = `auto` (default) | `true` | `false`: `false` never paves at runtime (the `Paver` only places
signposts, lanterns, milestones, shrines, dressing); `auto` = only in saves whose `RoadsData` predates the plan
(`planEra` flag, set for every new world); `true` = always and plans its own roads where the plan has none.
`roads.paintAtGeneration` switches the painter off (A/B timing). Settlement join: the plan's last vertex is the gate cell
(`GateEnds`, the settlement grammar's `SettlementAnchors.gates`), the painter blends onto the gate's ground and the grammar's
street starts there.

**Numbers** (fresh world seed 7777, roads module on, 45 tiles around the 4 corridors, the plan-based check
`tools/qa/p3_corridors.py measure`, 1,820 centre-line points every 2 blocks outside 8 blocks of any site): gaps 0, drops > 1
0, tree-blocked 0, unbridged 0, obstacles 0; 5 bridges and 2 culverts, all clean. On span examples regenerated with
`/skycraft-dev regen` (7 bridges incl. 2 over dry gaps, 5 culverts, 8 stairs runs, 3 fords, 2 tunnels, 205 switchback
points; 1,406 points): gaps 1 (a farm fence on a plan road), drops > 1 5 (2 farm/plan seams, 1 ford where two plan segments
disagree by 2-3 blocks, 2 covered-bridge seams), big drops 2, unbridged 0; 176 points are covered by ground (the 173 tunnel
points and 3 bridge points under a natural rise the macro ground did not predict). The legacy `road_measure.py` on the
same 4 corridors (1,898 points): gaps 11, drops > 1 8, tree 0, obstacle 0, unbridged 1: every one is inside a settlement
structure the road ends at (Whiterun's walls and gate, Helgen, Rorikstead's farm fields, a farm fence, a stray plant) —
inside its settlement zone, where the grammar owns the ground (P5). `roadplan_lint` 0 errors and `reach_lint` unchanged.
Cost: `RoadPaint` 0.19-0.22 ms per painted chunk (1,289 of ~4,500 corridor chunks); `PlacementMask` plan source ~70 us per
chunk grid; chunk generation 24 ms per chunk with and without the painter in `/skycraft-dev regen` (difference inside noise).
Screens: `docs/screens/worldplan/p3_bridge.jpg`, `p3_culvert.jpg`, `p3_switchback.jpg` (`render_town.py <world> --box x z half out`).
Known limits: farm plots and walls (structure pieces) keep their ground where a plan road crosses them (P5); a bridge
over a rise the macro plan did not know becomes a short covered passage; a steep tunnel is stairs.

## 7c. P5 result: streets to every door (S5)

Code: package `com.skycraft.worldplan.street`: `StreetPlan` (the baked data, bake name `streetplan`, format version 1,
queries), `StreetPlanBuilder` (the bake), `StreetTerrain` (the seed-free plan terrain), `StreetPaint` (the world
generation feature `skycraft:street_paint`, biome modifier `zz_street_paint`, after the road painter), `StreetMask` (the
`PlacementMask` source), `StreetPlanModule` (registration, loader thread, `/skycraft-streetplan stats|at|lint [id]|debug
<id> <x> <z>|trace|repaint|verify`, autorun task `streetplan`). Changed: `SettlementStructure` (named places are built
from the baked layout; `planPlace` plans on the plan terrain), `plan/Plan` (`keepOut`, terrace seating in `resolve`, wall
towers face the town), `plan/Terrain` (height-model constructor), `plan/Spec` (`OMIT`, `NO_BACK_DOOR`), `plan/Nets` and
`grammar/Zoning` (wall towers face the town), `grammar/Grammar` (no back door when flagged), `build/Builders` (skips
`OMIT`), `build/Canvas` + `SettlementPiece.recordDoors` (declared entrances are recorded on a replay), `build/Accessibility`
(a lone stair no longer clears what stands over it: it deleted balcony decks), `worldplan/PlanGround` (the edge index is
built once, under a lock: it was built by every worldgen thread at start; plot pads), `terrain/TamrielHeight` (plot pads),
`worldplan/RoadPlanBuilder` (a road keeps outside the gate ring of the settlement it ends at and off the buildings and
landmarks its city plan pins), `worldplan/RoadPlan` (`atSegment`, `Hit.set`), `roads/RoadPaint` (at a junction the road a
branch joins keeps its own surface; a settlement's open works yield to a road), `roads/PlanRoute` (a link found by its
places keeps the plan's gate; a stretch merged into another road is joined by a chord), `grammar/Fabric` (a static field
shared by every planner thread made layouts depend on thread timing: the bake was not reproducible),
`creatures/world/BanditCampFeature` (keeps off the placement mask: a camp stood on the Rorikstead highway), `bake/Bake`
(the third bakeable, and the settlement planner, builders, city plans and style sheets in the input hash). Tools:
`tools/qa/town_checks.py` (a door onto a railed balcony deck is counted apart, `balcony_doors`), `tools/qa/road_measure.py`
(a point under a gatehouse arch or deck is judged on the surface walked under it), `tools/qa/skyrim_sweep.json` (the
24-settlement sample of `docs/SKYRIM-SWEEP.md`).

**How it is made** (`StreetPlanBuilder`, 5-15 s for the 884 named settlements, 8 threads):
* **Layout on the plan terrain.** Every named settlement (capital, city, town, village, farm) is planned by the settlement
  grammar exactly as before (its city plan is the authored input), but on `StreetTerrain`: `TamrielHeight` without the
  seeded detail (macro elevation, the seed-free hydrology, the site pads and the road beds of P1/P2). Inside a site pad
  that is exactly the generated terrain; outside it the plot pads below pull the terrain to it. The planned roads' beds
  plus 2.5 blocks are kept free (`Plan.keepOut`): fields, yards, fences and houses never cut a road. A piece standing on
  a terrace tile is seated on it (`Plan.resolve`; the town fill used to sink houses into Whiterun's terraces). Wall
  towers face the town (their door opened onto the field outside).
* **Doors.** Every building's builder is replayed on a recording canvas; every declared entrance (`Canvas.entrance`) and
  every player-home front door is a door. On a 1-block walk grid of the settlement (buildings, walls, towers, mines
  blocked; a gatehouse only through its 3-wide passage; eaves, props, yards and fields dearer; water dearest, it gets a
  plank walk; a step of more than two blocks impossible, of two dear) the cheapest 4-connected walk runs from the
  doorstep (through the building's own porch and its steps, which its builder lays) to a street, a plaza, a road's gate
  or a planned road's bed. Heights: from the door's floor along the ground, at most one block per cell, then back from
  the end so the walk meets what it reached. A square building whose front door has no walk is turned to the first side
  that has one (324 in Tamriel, mostly wall towers); a back door with no walk is not built (`NO_BACK_DOOR`, 92); a prop
  without anchors on a walk, a street or a planned road is left out (`OMIT`: it keeps its index, so no other piece
  changes).
* **Graph.** Street and bridge lines give the street nodes (ends merged within 2 blocks, T-junctions split); a plaza is a
  node joined to its nearest street node; the last vertex of every planned road that ends at the settlement (a segment
  end with `JOIN_SITE` on its pad) is a GATE node (ROAD when no predicted gate is within 16 blocks), joined to the
  nearest street node by the gate lane; each walk is a chain of PATH edges (a node at every corner or change of grade)
  from the door node to a node split into the street it reaches. Edges: MAIN (major street, gate lane), LANE (3+ wide),
  ALLEY, PATH, STAIRS (2+ blocks at more than 0.35 per block, and the builder's own steps), BRIDGE; surfaces PAVED,
  BEATEN, PLANKS (on water), STONE (the building's own porch and steps: left as built).
* **Plots.** Building footprints (axis-aligned rectangles) with their floor as the pad (not on water, stilts or tents),
  plazas with theirs, walls, gatehouses and towers, and every other work (no pad). The layout itself (every spec, 20
  ints and a name) is stored too: `SettlementStructure` builds named places from it, so the generated blocks are what
  the graph describes.

**Consumers.** (a) The settlement grammar: city plans are the input, the bake their resolved output; named places are
no longer planned at runtime (`SettlementStructure.plan` reads `StreetPlan.layout`). (b) Terrain: `TamrielHeight` applies
`StreetPlan.pad` after the site pad and before the road bed (never on water): weight 1 on a plot and one block round it,
or on a street band (half width + 1), a smoothstep over 4 blocks beyond, height pad + 0.5 (the top solid block is the
pad); `PlanGround.influence` includes it (caves and overhangs calm under plots). (c) `PlacementMask`: `StreetMask`, every
plot plus one block and every street, lane and walk plus one block. (d) `StreetPaint`, per column of the chunk being
decorated, after the settlement's pieces (same step, structures first) and the road painter: each walk cell at its
planned height: a hole filled (dirt under a path block), grass and dirt worn to a path, a plank walk on water, the two
cells above cleared of clutter, plants, low walls, slabs and stairs of yard props, tree trunks and natural ground (a
cut; a full block of a building stays), a yard or pen fence turned into a gate; gate lanes (up to 28 blocks) the same
over their width; inside a building, wall or gatehouse footprint and on the building's own porch and steps only plants
and snow go. Streets, their steps on slopes and the stairs off terraces stay the settlement pieces' own (built on the
real ground). Painting is deterministic and idempotent, reads and writes only its chunk.

**Joins with the road plan.** A road ending at a settlement is penalised inside the ring of its gates plus 16 blocks
(`OWN_MARGIN`) and on the buildings and landmarks its city plan pins, so it reaches the gate radially, never along or
through a wall, a field or the Dragon Bridge mill; its last vertex is the gate (P1) and the street plan's GATE node is that
vertex exactly (lint: 0 GATE/ROAD nodes off a road plan vertex); the gate lane joins it to the main street; fields,
yards and houses keep 2.5 blocks off every planned road bed.

**Numbers** (bake of this phase; the 24-town Skyrim sweep sample and the 4 corridors regenerated in the Nirn dev world
with the final code, `/skycraft-dev regen`):
* Bake: 884 settlements, 64,564 specs (5,387 props left out, 324 buildings turned to a free side, 292 back doors not
  built), 60,263 nodes, 57,820 edges (37,707 doorstep path edges, ~150 km), 12,962 doors, 58 with no walk in the plan
  (0.45%: buildings walled in by another piece, a ruin inside a wall tower, a door two blocks under its street), 1,295
  GATE nodes + 1,281 ROAD joins, 56,926 plots. `streetplan.bin` 1.7 MB deflated (9.1 MB raw); 5 s warm / 15 s cold
  (8 threads) to compute live, 0.3 s to load. Reproducible (`qa.ps1 -Bake`: a live recomputation equals the bake).
* `town_checks.py`, 24 settlements: **1,324 exterior doors, 5 unreachable (0.38%)** (before: 69 of 1,401, 4.9%; 39 of
  them, 2.8%, without the 30 balcony doors the checker now counts apart), 0 blocked stair ends, props on a street 64 > 1,
  props in buildings 0, gates without a road within 24 blocks 0. Left: two Windhelm harbour doors (a drop past the
  doorstep, a missing floor), a Markarth back door onto a slab, a Karthwasten door two blocks under its street, a Helgen
  ruin built into a wall tower.
* `road_measure.py`, the 4 corridors (1,993 points): **gaps 0, drops 0, tree-blocked 0, obstacles 0, unbridged 0**
  (P3: 11 / 8 / 0 / 0 / 1, every one at a settlement join: Whiterun's and Helgen's walls, Rorikstead's fields, a farm
  fence). `p3_corridors.py` (plan centre lines, 1,941 points): gaps 2, drops 1, unbridged 0: one dry-gap bridge between
  Whiterun and Riverwood where the Nirn detail terrain rises to the deck (the P3 limit, not a join).
* `StreetPaint`: 24 us per painted chunk (1,302 chunks). The road plan's terrain index is built once (it was built by
  the loader, the server thread and every worldgen worker at once, 16+ times at start).
* `qa.ps1 -Server -Bake`: PASS (build, data, layout, reach, world boots, selftests, population lint 0 errors, bake up to date).

**Known limits.** Generic (unnamed) settlements and sites are still planned at runtime on the noise terrain (no street
plan). Doors of landmarks, jails and towers built with `Canvas.door` (not `entrance`) are not in the plan. A path is one
block wide and is not stepped with stair blocks (a one-block rise is a step). Street lines are still painted by the
settlement's STREET pieces on the real ground.

**API** (`com.skycraft.worldplan.street.StreetPlan`, immutable, thread-safe):
```java
StreetPlan plan = StreetPlan.get();               // baked (or planned live once)
StreetPlan.Hit hit = new StreetPlan.Hit();        // reuse one per thread
if (plan.at(x, z, hit)) {                         // nearest street edge within StreetPlan.REACH (16) blocks
    hit.cls;      // MAIN LANE ALLEY PATH STAIRS BRIDGE     hit.surface; hit.width; hit.distance; hit.height (walk top y)
    hit.settlement; hit.edge; hit.t;
}
List<StreetPlan.Door> doors = plan.doorsOf("whiterun");   // {spec, x, y, z, facing (outward), node, reachable}
List<Spec> layout = plan.layout("whiterun");              // the resolved layout (OMIT / NO_BACK_DOOR flags set)
plan.pad(x, z, bed);                                      // plot / street pad: bed.plotWeight, bed.plotHeight
```
Raw arrays: settlements `sId sX sZ sReach` with ranges `sNodeOff/sEdgeOff/sDoorOff/sPlotOff/sSpecOff`; nodes `nx nz nh
nClass nJoinSeg nJoinVert` (a GATE / ROAD node's road plan segment and global vertex); edges `ea eb ew eClass eSurf`; doors
`dSpec dx dy dz dFacing dNode dReach`; plots `pSpec pMinX pMinZ pMaxX pMaxZ pPad pKind` (`NO_PAD`; building, wall, work).

## 8. Contracts for P5 and P6 (so they can run in parallel with P3)

Both are baked like `roadplan` (seed-independent, versioned, input-hashed, loaded at start, queried allocation-free).
P3 only needs to know that these exist; P5/P6 must not change `RoadPlan`'s format.

**P5 `streetplan` (bake name `streetplan`, package `com.skycraft.worldplan.street`)**
* Per settlement id: a street graph `nodes[] {id, x, z, h}` (h = pad/street height, fractional) and
  `edges[] {a, b, width, class: MAIN|LANE|ALLEY|PATH|STAIRS|BRIDGE, surface}`; every node of class GATE carries the
  `RoadPlan` segment id + vertex it joins (`join.segment`, `join.vertex`), so streets and roads meet at one cell.
* `doors[] {buildingId, x, y, z, facing, node}`: every building door is linked to a street node by an edge (doorstep
  path); a door with no reachable node is a lint error.
* `plots[] {buildingId, polygon, padHeight}` go into the terrain like site pads (P2 `PlanGround`), and into
  `PlacementMask` as plot masks.
* Query API: `StreetPlan.at(x, z)` -> nearest street edge, distance, height, class (same contract as `RoadPlan.at`),
  `StreetPlan.doorsOf(settlementId)`; the settlement grammar consumes the plan instead of inventing streets (city
  plans become the authored input, the bake their resolved output).
* Gate (P5): unreachable doors <= 1% on the Skyrim sweep sample; every GATE node joins a road plan vertex.

**P6 `sceneplan` (bake name `sceneplan`, package `com.skycraft.worldplan.scene`)**
* A list of scenes `{id, kind, x, z, h, facing, anchor: {type: ROAD_BEND|CROSSROADS|FORD|BRIDGE_END|FARM|CAMP_SITE|
  SHRINE_SITE|BATTLE_SITE|RUIN_EDGE|DUNGEON_MOUTH|LOREWEB_CLUE, ref}, reason, props[], clue?}` produced from the road
  plan (bends, crossroads, fords, bridge ends: one wayside every 1.5-3 km of road, rarer on trails), sites (farms,
  camps), the lore web (`tools/marquee/loreweb.json` clues) and landmarks; kinds come from `scatter/kinds.json`
  entries that have `purpose`/`needs` (human kinds only; natural micro-scatter stays per-chunk random).
* Chunk features only realise scenes whose footprint intersects the chunk (deterministic, no neighbour reads).
* Gate (P6): every human prop placed belongs to a scene (placement lint); scene density per km of road and per km²
  within targets; zero human-prop placements from per-chunk rolls.
* As built (section 9): one anchor type was added, `SETTLEMENT_EDGE` (ref = place id, or `<id>@shore` for a fishing
  shore); road anchor refs are `seg:<segment>:v<vertex in segment>`, lore refs `<place>#<clue index>`; props are
  `{kind, dx, dz}` with kind a scatter kind, `lm:<landmark kind>`, `clue_sign` or `clue_note`; scenes also carry a
  `note` (place-lore book id) and an optional worn `path` end on the road's edge.

## 9. P6 result: planned scenes (S6 + S11)

Code: package `com.skycraft.worldplan.scene` (`ScenePlan` data + chunk index, `ScenePlanBuilder`, `SceneKinds`,
`ScenePlanCommands`), `com.skycraft.scatter.SceneRealiser` (new); changed `scatter/Scatter`, `ScatterRules`,
`ScatterFeature`, `ScatterStats`, `ScatterCommands`, `scatter/rules.json` (zones natural only; new `scenes` section);
`scatter/Waysides` deleted (the server-thread per-chunk wayside pass). Outside the package, two one-line guards:
`landmarks/Landmarks.compute` leaves cells of human vignettes empty, `roads/Dressing.layout` returns in plan-era
worlds. Bake: name `sceneplan` (`skycraft/baked/sceneplan.bin`, format version 1, 140 KB deflated / 636 KB raw), its
classes and data files (`scatter/kinds.json`, `rules.json`, `loreweb_clues.json`, `landmarks/kinds.json`) are in the
`Bake` input hash; loaded on a background thread at server start (17 ms; ~0.3 s live). New data:
`scatter/loreweb_clues.json` from `tools/marquee/loreweb.json` (`py -3 -I tools/marquee/loreweb_clues.py`).

**How it is made** (`ScenePlanBuilder`, ~0.2 s after the road plan; only map, places, road plan and data tables, every
choice a hash of stable ids; byte-identical when recomputed under another seed, `qa.ps1 -Server -Bake`):
1. lore-web clues: each of the 758 clues of the 231 lore-web places is set 10-50 blocks outside its place, kind from the
   clue's words (`scenes.clueWords`, else a cairn), with a sign carrying the clue text (front and back, 15-character
   lines) and, for the first clue (and the thread clue), the place's `web`/`thread` note in a barrel beside it;
2. places: daedric shrines, standing stones, word walls (SHRINE_SITE, 70%), forts and Oblivion gates (BATTLE_SITE,
   50%), ruin/altar/monument/stones landmarks (RUIN_EDGE, 50%), campsite landmarks (CAMP_SITE), dungeon mouths (35%);
3. settlement edges (capital 4, city 3, town 2, village 1; a cemetery takes the highest spot of its ring), fishing
   shores (water 3-24 blocks off the edge), one farm yard per farm;
4. waysides: per road link a hashed gap of 1.5-3 km (lanes 1.4-2.8, trails 3.5-6), at the best anchor within ±240
   blocks of the drawn spot: crossroads (sign naming the places the roads lead to), ford bank, bridge end, else the
   sharpest bend; never within 60 blocks of a settlement's edge; 7 blocks beyond the bed plus the scene's radius, with
   a worn dirt path to the road's edge.
Validity everywhere: dry map ground, macro slope <= 0.9, outside every place's radius (+4/+6), every prop off every
road bed, scenes 6 blocks apart. Kinds per anchor: `rules.json` `scenes.anchors` (scatter human kinds, clusters
expanded into member props at bake time, `lm:<builder>` = a landmark S/M vignette of that family).

**Realising** (`SceneRealiser`, called first by the `skycraft:scatter` feature): for the chunk being decorated,
`ScenePlan.inChunk` (a chunk -> scenes index of footprints and paths); each prop whose centre is in the chunk is built
(scatter kinds through `Scatter.planned`: ground, slope, water, structure/road mask checks, up to 3 retries within 2
blocks inside the chunk; vignettes through `LandmarkBuilder`; sign, note barrel), and the path columns inside the
chunk. Variants are seeded by scene, prop and world seed; positions come from the plan. Natural scatter keeps off scene
footprints (`ScenePlan.covers`). Per-chunk rolls: `Scatter.Run.allowed` refuses human kinds and `ScatterRules` refuses
them in zones, so `rules.json` cannot bring them back.

**Numbers** (bake of this phase): 3,962 scenes, 8,539 props (591 landmark vignettes, 1,655 signs, 140 notes), 522
worn paths. Per anchor: ROAD_BEND 113, CROSSROADS 389, FORD 5, BRIDGE_END 26, FARM 306, CAMP_SITE 58, SHRINE_SITE 105,
BATTLE_SITE 171, RUIN_EDGE 152, DUNGEON_MOUTH 958, LOREWEB_CLUE 746 (12 of 758 found no dry spot), SETTLEMENT_EDGE
933. Density (targets in `placement_lint.py`): waysides per km of highway+road 0.46 (364 / 793 km; target 0.33-0.67),
lane 0.34 (32 / 95 km; 0.25-0.71), trail 0.22 (137 / 620 km; 0.17-0.29); scenes per km² of land 9.1 (3,962 / 436 km²;
target 5-15, i.e. one scene every ~330 blocks). Few fords and bridge ends: crossroads win the anchor choice in the
dense network. Realisation on the dev world (6 regenerated areas, 654 chunks): 27 scene props built, 4 refused by
the ground (2 placement mask, 1 blocked, 1 sign), 0 human props from per-chunk rolls (`/skycraft-scatter stats` line
`scene props built ...`). Feature time 0.25 ms per chunk on average. `placement_lint.py`: 0 problems (0 human kinds
rolled, 5/5 code guards, 0 scene errors, densities in target).
Screens: `docs/screens/worldplan/p6_crossroads.jpg` (cairn and road sign at the Whiterun-Rorikstead crossroads),
`p6_loreclue.jpg` (Ald Velothi clue: cairn, sign with the clue, barrel with the place's note), `p6_cemetery.jpg`
(Whiterun's cemetery on the rise).
**Tools:** `/skycraft-sceneplan stats|near <x> <z>|list <anchor|kind> [n]|verify`, autorun task `sceneplan`.
**Known limits / next:** minor sites (`explore/MinorSites`) and hidden stashes stay on their seeded cell grids (sites
with a purpose each, not clutter; making them seed-independent is a separate step); props near a structure or in a
dense forest can still be refused at realisation (the plan cannot see seeded trees: registering scene footprints
with `PlacementMask` would need a site-pad slot there); the slope probes of a prop read up to 2 blocks into a
neighbour chunk (its ground height, which is generated before any feature runs).

## 10. Dormant P7 prototype: endless lands per 4 km tile (S1–S6 beyond the authored map)

**Superseded by the owner on 2026-10-18:** runtime geography beyond the fixed lands is now ocean,
with far-edge opposite-side travel. Runtime tile fallback is disabled, including saved cached plans.
The following describes retained tools/prototype architecture; it is not active terrain or a performance
guarantee. Old first-consumer/settlement-realization gaps remain dormant rather than production work.

Code: package `com.skycraft.worldplan.tile` (`TilePlan`, `TilePlanner`, `TileSheetGenerator`, `TilePlanData`,
`TilePlanCommands`); composite fallback hooks in `RoadPlan.at` / `RoadPlan.bedHeight`, `StreetPlan.at` / `StreetPlan.pad`,
and `ScenePlan.covers` / `ScenePlan.planFor` when `!TamrielMap.inTamriel(x, z)`; Tier 0 tests in `TilePlanTest`.

**How it is made** (`TilePlanner.buildTilePlan`, ~15–60 ms per 4,096 x 4,096 block tile):
1. **Procedural 11-layer world sheet (`TileSheetGenerator`):** samples an 8x8 macro grid across `[tileX << 12, (tileX + 1) << 12)`
   via `TamrielMap.sample` (`Beyond`) to measure dry land fraction, mean/peak elevation, relief, wetland, and dune cover;
   classifies latitude climate band (`polar`, `temperate`, `arid`, `tropical`) and nearest border/foreign culture (`skyrim`,
   `high_rock`, `hammerfell`, `morrowind`, `cyrodiil`, `summerset`, `valenwood`, `elsweyr`, `black_marsh`, `akavir`,
   `atmora`, `yokuda`, `pyandonea`); populates all 11 layers (`L1`..`L11`, 22 causal fields) with strict upstream citations
   and `L1`/`L4`/`L9` sensory links on `L10`.
2. **Symmetric boundary crossings (`TilePlanner.computeEdgePoints`):** crossing points on any shared tile edge are a pure
   function of `(worldSeed, isHorizontal, borderLineCoord, spanIndex)`, so `EAST` of `(tx, tz)` and `WEST` of `(tx + 1, tz)`
   (and `SOUTH`/`NORTH` vertically) produce identical `EdgePoint(x, z, height, kind, river)` lists without reading each
   other or depending on tile generation order. Tiles touching the authored Tamriel border (`MIN_X`, `MAX_X`, `MIN_Z`, `MAX_Z`)
   also discover authored `RoadPlan` border vertices (`computeAuthoredSeamPoints`) so Tamriel's roads continue across the
   border at the exact vertex coordinate and height.
3. **Tile-scale hydrology, places, roads, streets, and scenes:** traces watershed river segments from upland basins to
   coast/edge crossings; places settlements, delves/ruins, camps, and shrines at half Tamriel density on dry benches with
   resolving `why` citations into the tile's `TileSheet`; routes grade-capped road segments (`<= 1:8` road, `<= 1:5` lane,
   `<= 1` block/step trail) with `BRIDGE` spans over all water crossings; builds a `StreetPlan` with reachable doors and
   plot pads for each settlement; and builds a `ScenePlan` with causal `reason` citations into the tile sheet.
4. **Off-thread execution & persistence (`TilePlanData`):** runtime queries call `TilePlanner.getIfReady(x, z)` which
   never stalls the server thread (`0 ms` stall) and schedules the tile plus its 4 cardinal neighbours on a daemon pool;
   `TilePlan.write()`/`read()` persists compressed tile plans in `data/skycraft_tile_plans.dat` (`SavedData`) so reloaded
   tiles match recomputed bytes bit-for-bit.
5. **Tools:** `/skycraft-tile plan <tileX> <tileZ> | sheet <tileX> <tileZ> | at <x> <z> | selftest`, autorun task `tileplan`.

