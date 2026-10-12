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

* **P1 Road plan bake (S3+S4).** Bakeable `roads.bin`: polylines with width, class, per-vertex road height (grade cap
  1:8 main, 1:5 track, steps on footpaths), span list with crossing type. Pathfinding on the macro+hydrology heights
  (not on seeded noise). Crossing rules: water or gap > 3 blocks: bridge; stream narrower than ~6: culvert; shallow
  ford on footpaths; cliff: switchback, else stairs; tunnel only where nothing else fits (rare). Gate: every link has
  a profile within grade caps, every water crossing has a span, reach lint unchanged.
* **P2 Terrain obeys the plan (S7).** The density/height function reads the road index: inside the bed the ground is
  the profile height; shoulders blend with smoothstep; cut slopes get stone, fills get earth; settlement/landmark pads
  likewise. Gate: in the saved corridors, terrain under every road cell is within 0.5 of the profile.
* **P3 Paint and crossings (S8+S9).** Road surface in the surface rule (half steps as slabs from the fractional
  height); bridges, culverts, fords, stairs as per-chunk features cut from the span list. Retire `Paver` for new
  chunks (keep it only to upgrade old saves). Gate: 0 missing road cells, 0 unbridged crossings, 0 drops without a
  step on the corridors.
* **P4 Vegetation masks (S10).** Tree and flora features check the road/street/pad/plot masks before placing; remove the
  clearing code paths that create floating crowns. Gate: floating vegetation within 150 blocks of the sample towns ~0.
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
