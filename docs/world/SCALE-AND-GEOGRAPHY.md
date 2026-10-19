# Scale and geography of Tamriel: one model for every source

Status: research and plan, no code changed. Read `docs/TAMRIEL.md` (contract), `docs/WORLDGEN-PIPELINE.md` (P1 road
plan) and `docs/AGENT-RULES.md` (legal rule) first. All numbers come from the owner's game installs, extracted into
`.reference/<game>_ref/` (gitignored; facts only) and measured by `.reference/scale_analysis/{analyze,proposals}.py`.
This file holds only facts, numbers, coordinates and our own designs.

Owner direction that shapes everything below: the old games (Arena above all, Daggerfall too) are a **starting point,
not the target**. We take geography, relative positions, names and road topology from them, then build each province
to modern quality: amplified relief, distinct silhouettes, river valleys, coasts, unique cities, with **Skyrim's
density and quality, not Arena's or Daggerfall's quantity**. Each province section ends with a *modernisation* block:
what we keep, what we amplify, what we replace.

## 0. Summary

* **One block = one metre of Skyrim as built.** NirnCraft's Skyrim is the game map at **1.11 blocks per game metre**
  (0.01586 blocks/unit; 70 units = 1 m in Morrowind, Oblivion and Skyrim). Every other source is expressed as a
  multiple `k` of that: **Cyrodiil k = 1.58** (1.754 blocks per Oblivion metre), **Vvardenfell k = 0.86** (0.95 blocks
  per Morrowind metre), **Solstheim k = 1.0**, **Arena: 70 blocks per world-map pixel inside a province** (40 for
  Valenwood and Summerset, which Arena drew oversized), **Stros M'kai: 0.021 blocks per Redguard unit**.
* **Inside a province, geometry is a single uniform similarity** (scale + rotation + shift) of its best source. No
  thin-plate warps inside a province: they are why Cyrodiil's city-to-city distances today vary from 1.4x to 2.4x the
  game's (Bruma is 2.4x further from the Imperial City than in Oblivion, Skingrad-Kvatch only 1.47x).
* **Between provinces, the lore map wins** (NirnCraft's current continent outline), not Arena's continent, which puts
  the Imperial City 42 px (about 3 km at our scale) from Whiterun and inflates Summerset and Valenwood.
* **Relief is the biggest quality gap**: outside Skyrim the macro terrain is flat. Median height above sea is 74 blocks
  in Skyrim but 18-34 in the other provinces (Valenwood max 65, Summerset max 75), and local ruggedness is 1.2-2.5
  against Skyrim's 5.6. Section 7 gives per-province relief targets and a legal method (warp, extract vectors, blend).
* **Density is already high enough**: named settlements per km2 are 1.0 (Cyrodiil) to 3.0 (Summerset) against
  Skyrim's 1.28. Add no Arena villages wholesale; use Arena names to rename or upgrade existing generic places, and
  add only the curated settlements in section 5.
* Top 10 changes: section 8.

## 1. What was extracted (all in `.reference/`, gitignored)

| Source | Already there | Added now | Counts |
| --- | --- | --- | --- |
| Skyrim SE (`skyrim_ref`) | markers, holds, heightmaps, water | README | 436 markers; heightmap 1905x1505 @256 u/px |
| Oblivion (`oblivion_ref`) | markers + POIs, heightmap, road graph, named routes, transform | README | 459 markers (34 settlements, 16 taverns, 53 forts, 51 Ayleid ruins, 91 caves, 25 mines, 15 Daedric shrines, 99 gates) + 85 POIs; heightmap 4288x4128 @128 u/px; 1,168 road polylines, 11 named routes |
| Morrowind + Tribunal + Bloodmoon (`morrowind_ref`) | markers, regions, coast, travel, heightmap, transform | `roads.json`, `signposts.json`, `shrines.json`, `preview_roads.png`, README (`scripts/mw_roads.py`) | 474 markers; 291 road polylines (3.5 km main, 20.4 km dirt, 0.3 km cobble game distance); 174 signpost junctions (593 arms); travel: 16 strider, 25 boat, 10 guild guide, 10 propylon links; 19 intervention targets |
| Arena (`arena_ref`, new) | | `citydata.json`, `locations.json`, `roads.json`, `city_backbone.json`, decoded maps (reference only), preview (`scripts/citydata.py`, `arena_img.py`, `lzhuf.py`, `arena_build.py`) | 9 provinces; 273 named locations (8 x 32 settlements + Imperial City + 16 named dungeons; 14 dungeon slots per province are generated in game, not listed); 281 road links read off the 8 province maps |
| Redguard (`redguard_ref`, new) | | terrain levels, objects, sites, preview (`scripts/redguard.py`, `sites.py`) | Stros M'kai 256x256 terrain grid; 2,663 placed objects; 36 named sites |
| Daggerfall (`daggerfall_ref`) | another agent's (15,251 locations, Iliac Bay warp) | untouched | hooks only (section 5.4) |

Formats decoded with our own scripts: TES3 ESM (LAND VTEX texture grid in its 4x4-block order, LTEX, refs), Arena
`CITYDATA` (9 x 1228-byte province blocks), Arena `GLOBAL.BSA` (index at the end, 18-byte entries) and IMG
compression types 4 (LZSS) and 8 (LZHUF), Redguard `WLD` (four 128x128 sections, 2x2) and `RGM` (tagged sections;
MPOB/MPSO records of 66 bytes). Gap: Skyrim road geometry was not extracted (roads are landscape texture and
RoadChunk statics); `roads/named_roads.json` already encodes Skyrim's road topology from game knowledge.

## 2. Measured scales

### 2.1 Skyrim (reference province)

Game metres between map markers vs NirnCraft blocks today (`skyrim_settlements.json`):

| Pair | Game m | NirnCraft blocks | Blocks per game m |
| --- | ---: | ---: | ---: |
| Whiterun - Solitude | 2,008 | 2,104 | 1.05 |
| Whiterun - Windhelm | 1,756 | 1,879 | 1.07 |
| Whiterun - Riften | 2,537 | 2,800 | 1.10 |
| Whiterun - Markarth | 2,776 | 3,166 | 1.14 |
| Whiterun - Falkreath | 1,335 | 1,572 | 1.18 |
| Riverwood - Helgen | 498 | 553 | 1.11 |
| Solitude - Windhelm | 3,022 | 3,208 | 1.06 |
| Markarth - Riften (diagonal) | 5,172 | 5,766 | 1.11 |
| Dawnstar - Falkreath | 2,884 | 3,201 | 1.11 |

Uniform at 1.11 (+-0.07; Whiterun's own pin and Riverwood's are slightly off-scale). Skyrim is the yardstick.

### 2.2 Cyrodiil (Oblivion)

The least-squares similarity of the 9 city centroids is **0.02506 blocks/unit = 1.754 blocks per game m, rotated
-3.24 deg** (`oblivion_ref/transform.json`), with residuals of 324-947 blocks: the TAMRIEL.md anchors are not a
uniform image of the game. Pair ratios today:

| Pair | Game m | NirnCraft | Ratio | | Pair | Game m | NirnCraft | Ratio |
| --- | ---: | ---: | ---: | --- | --- | ---: | ---: | ---: |
| IC - Bruma | 1,224 | 2,961 | **2.42** | | Skingrad - Kvatch | 1,119 | 1,640 | **1.47** |
| IC - Cheydinhal | 1,344 | 2,941 | 2.19 | | Bravil - Leyawiin | 1,567 | 2,163 | 1.38 |
| Chorrol - Bruma | 1,470 | 3,130 | 2.13 | | IC - Kvatch | 2,697 | 4,168 | 1.55 |
| IC - Bravil | 1,498 | 2,941 | 1.96 | | IC - Leyawiin | 3,058 | 4,826 | 1.58 |
| Kvatch - Anvil | 723 | 1,389 | 1.92 | | IC - Anvil | 3,420 | 5,557 | 1.62 |

Cause: `tools/provinces.py --ingest` places all ~900 Cyrodiil places through a thin-plate warp pinned exactly on the
TAMRIEL.md anchors, which stretches the north (Bruma, Cheydinhal pulled toward Skyrim) and squeezes the west.

### 2.3 Vvardenfell (Morrowind)

Similarity fit 0.01359 blocks/unit = **0.95 blocks per game m** (rms 315). The thin-plate warp through 5 anchors
(Vivec, Balmora, Ald-ruhn, Sadrith Mora, Red Mountain) is exact at the anchors but distorts the rest:

| Pair | Game m | NirnCraft | Ratio |
| --- | ---: | ---: | ---: |
| Balmora - Vivec | 1,350 | 1,360 | 1.01 |
| Balmora - Ald-ruhn | 971 | 1,020 | 1.05 |
| Vivec - Sadrith Mora | 2,444 | 1,921 | 0.79 |
| Ald-ruhn - Maar Gan | 707 | 491 | 0.69 |
| Suran - Vivec | 628 | 446 | 0.71 |
| Gnisis - Ald-ruhn | 1,187 | 919 | 0.77 |
| Khuul - Vivec | 3,612 | 3,556 | 0.98 |
| Ebonheart - Vivec | 296 | 2,915 | (moved to the mainland on purpose) |

The south (Ascadian Isles, Molag Amur) and the north-west are compressed by 20-30 %. Vvardenfell's polygon in
`tamriel_vectors.json` has only 17 vertices.

### 2.4 Arena (the whole continent)

Arena's world map is 320x200 px; each province has a rect on it and its own 320x200 province map (local coords).

* **Inside a province, Arena is a usable layout.** Per-province similarity fits of Arena settlements onto NirnCraft's
  places with the same name:

| Province | n | Blocks per Arena px | Rotation | rms (blocks) |
| --- | ---: | ---: | ---: | ---: |
| Skyrim | 9 | **69.5** | +4.8 | **230** |
| High Rock | 11 | 72.7 | -12.4 | 1,330 |
| Hammerfell | 9 | 68.1 | -0.7 | 1,588 |
| Elsweyr | 31 | 74.4 | -14.9 | 991 |
| Morrowind (mainland + Vvardenfell) | 9 | 80.1 | +16.2 | 1,652 |
| Black Marsh | 31 | 85.6 | -7.3 | 974 |
| Valenwood | 23 | 41.4 | +11.8 | 1,797 |
| Summerset | 24 | 34.3 | +7.5 | 1,428 |

  Skyrim, the province we know at game scale, fits Arena at 69.5 blocks/px with only 230 blocks rms: Arena's
  intra-province relative placement is honest. Six provinces cluster at 68-86; Valenwood and Summerset at 34-41 mean
  Arena drew them about 1.8x too large (the lore map agrees).
* **The continent layout is not usable.** One similarity for all 63 shared city-states: 100 blocks/px, rms 2,370.
  The Imperial City sits 42.5 Arena px from Whiterun (about 2,950 blocks at 69.5) where NirnCraft has 6,030, because
  Arena's Imperial Province is a small blob squeezed between the others (its land, measured by nearest-settlement
  areas on the world map, is under 2x Skyrim; it holds a single location).
* Relative land areas (Skyrim = 1.0; Arena from its world-map land mask assigned to the nearest settlement; lore map
  estimated by eye, +-20 %):

| Province | Arena | Lore map (est.) | NirnCraft today (km2) | NirnCraft relative |
| --- | ---: | ---: | ---: | ---: |
| Skyrim | 1.00 | 1.0 | 26.5 | 1.00 |
| Cyrodiil | ~1.7 | ~2.0 | 61.2 | 2.31 |
| Morrowind (incl. Vvardenfell) | 1.29 | ~1.3 | 36.7 | 1.38 |
| Black Marsh | 0.90 | ~1.3 | 38.0 | 1.43 |
| High Rock | 0.67 | ~0.9 | 40.8 | **1.54** |
| Hammerfell | 0.96 | ~1.1 | 25.3 | 0.95 |
| Elsweyr | 0.80 | ~1.0 | 25.7 | 0.97 |
| Valenwood | 0.87 | ~0.8 | 24.2 | 0.91 |
| Summerset | 0.81 | ~0.5 | 13.6 | 0.51 |

  High Rock is the outlier (larger than both Arena and the lore map); it is Daggerfall-driven, so it is the Iliac Bay
  agent's call (section 5.4). Do not grow it further; fill its interior with Wrothgarian highland, not towns.

### 2.5 Stros M'kai (Redguard)

The island's land spans 170 x 185 terrain px of 256 units (about 43,500 x 47,400 units). Redguard's unit has no
metric calibration, so we size it by function: at **0.021 blocks per unit** (5.4 blocks per terrain px) the town core
is about 250 blocks across (Riften-like), the island is about 0.6 km2, and it matches the current `stros_mkai` polygon
(0.68 km2, 950 x 950 blocks).

### 2.6 Relief (heights above sea)

| | p25 | p50 | p75 | p90 | p99 | max | ruggedness* |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Skyrim game (m) | 111 | 214 | 357 | 449 | 578 | 607 | |
| NirnCraft Skyrim (blocks) | 38 | 74 | 116 | 168 | 258 | 344 (+ peaks.json) | **5.6** |
| Cyrodiil game, inside the border (m) | 21 | 72 | 168 | 422 (p95) | 535 | 665 | |
| NirnCraft Cyrodiil | 15 | 34 | 65 | 114 | 234 | 354 | 2.2 |
| Vvardenfell + Solstheim game (m) | 10 | 19 | 35 | 63 | 202 | 271 | |
| NirnCraft Morrowind | 9 | 21 | 55 | 150 | 374 | 414 | 4.0 |
| NirnCraft High Rock | 15 | 22 | 69 | 125 | 272 | 357 | 3.4 |
| NirnCraft Hammerfell | 14 | 27 | 76 | 98 | 229 | 327 | 2.5 |
| NirnCraft Elsweyr | 10 | 19 | 38 | 64 | 104 | 138 | 1.5 |
| NirnCraft Valenwood | 10 | 18 | 28 | 35 | 47 | 65 | 1.2 |
| NirnCraft Summerset | 10 | 23 | 30 | 40 | 61 | 75 | 1.9 |
| NirnCraft Black Marsh | 0 | 1 | 1 | 2 | 16 | 137 | 0.4 |

\* mean absolute height step between neighbouring 32-block raster cells.

Skyrim's game-to-block vertical transfer is about **0.35 blocks per game metre up to ~360 m, steepening to 0.45 at
580 m**, with the signature peaks drawn by `peaks.json`. Cyrodiil's macro heights already follow that curve (game
median 72 m -> ~25 blocks; we have 34); what Cyrodiil lacks is ruggedness. TES3 heights are compressed in the game
itself (Red Mountain 271 m), so Morrowind needs its own, steeper curve (about 1.1 blocks per m low, 1.55 at the peak).

## 3. The unified scale model (recommendation)

1. **Reference.** Skyrim as built: 1.11 blocks per game metre; Whiterun = (0, 0); vertical transfer as in 2.6.
2. **Game-built provinces keep their game geometry as one uniform similarity per province** (per landmass for
   islands), scale `k x 1.11` blocks per game metre, least-squares fitted on the main settlements, never a
   thin-plate warp. Coastal and lake-shore cities get a *snap* of at most 400 blocks to the authored coast instead
   of bending the whole province.
   * Cyrodiil `k = 1.58` (1.754 blocks/m): the lore map makes Cyrodiil about twice Skyrim, Oblivion's playable map
     is about the same size as Skyrim's, so the inflation is deliberate and is already today's footprint.
   * Vvardenfell `k = 0.86` (0.95 blocks/m): today's footprint; it is an island beside a mainland, so a slightly
     denser world reads right, and it keeps Vvardenfell a little over half of Skyrim's width, as on the lore map.
   * Solstheim `k = 1.0` (Skyrim's own Dragonborn geometry; already so).
3. **Arena-only provinces** (mainland Morrowind, Black Marsh, Elsweyr, Valenwood, Summerset, southern Hammerfell; High
   Rock and northern Hammerfell are Daggerfall's): relative positions inside the province from Arena at 70 blocks/px
   (Valenwood, Summerset 40), rotated and shifted onto the province's lore-map outline; then curated by hand
   (Arena's town names are largely generated and many were later retconned).
4. **The continent frame** (where each province sits, its outline and its seas) follows the lore map, i.e.
   NirnCraft's current `tamriel_vectors.json` outline. Arena and Daggerfall only adjust coasts locally.
5. **Fun constraint on travel.** Minecraft walking is 4.3 blocks/s, sprinting 5.6, a good horse 9-12. Keep
   (a) a named stop (village, inn, fort, landmark) every 600-1,000 blocks of highway, (b) adjacent major settlements
   at most ~3,000 blocks of road apart (12 min walk, 5 min horse), and (c) fast travel for anything longer. Measured:

| Leg | Road blocks (est.) | Walk | Sprint | Horse (~10 b/s) |
| --- | ---: | ---: | ---: | ---: |
| Whiterun - Riverwood | ~800 | 3 min | 2.4 min | 1.3 min |
| Whiterun - Solitude | ~2,600 | 10 min | 8 min | 4.3 min |
| Balmora - Vivec | ~1,700 | 6.6 min | 5 min | 2.8 min |
| IC - Skingrad (uniform) | ~3,300 | 13 min | 10 min | 5.5 min |
| IC - Anvil, Gold Road (5,504 game m) | ~9,650 | 37 min | 29 min | 16 min |
| Whiterun - IC via Helgen, Pale Pass, Bruma | ~6,500 | 25 min | 19 min | 11 min |
| Alinor - Sadrith Mora (straight, by sea) | ~24,000 | | | ship |

   The Gold Road is the only leg that breaks rule (b); it already has inns (Inn of Ill Omen, Brina Cross, Gottshaw);
   add carriages at every Cyrodiil city (section 6).
6. **Vertical.** One transfer curve per source family (Gamebryo/Creation games: Skyrim's; TES3: its steeper one).
   Arena and Daggerfall heights are not used as heights, only as hints (mountain areas on Arena's province art).

### Lore map vs game maps: the compromise in one line

Game maps are compressed for walking (Skyrim and Oblivion are about the same size in-game), lore maps are not
(Cyrodiil is twice Skyrim). We take **lore-map proportions between provinces** and **game geometry, uniformly scaled,
inside provinces**, and pay for the bigger provinces with **more stops and fast travel**, not with denser towns.

## 4. Height and biome guidance: method (legal)

The legal rule forbids committing the game heightmaps or anything sampled pixel for pixel from them. So the source
grids are used **offline, to author vectors**, and only the vectors (and rasters made from them) are committed:

1. **Warp.** Map each 32-block raster cell centre of the province through the inverse province similarity (section 3)
   into game coordinates; read the reference heightmap there with an area average over the cell footprint (Cyrodiil:
   18 game m per cell, about 9 px; Vvardenfell: 34 m). Convert with the vertical transfer curve. This is a private
   working grid in `.reference/` only.
2. **Extract features from the working grid** (scripts in a new `tools/worldgen/refgeo.py`, outputs reviewed by
   hand):
   * ridgelines: skeleton of the terrain above the province's p80, simplified (Douglas-Peucker, 64 blocks), written as
     `ranges` with our own rounded height (10-block steps) and width;
   * valleys and rivers: D8 flow accumulation, channels above a threshold, simplified to `rivers` with monotone
     heights (hydrology.py then owns them);
   * basins and lakes: cells under the game's water level, as `lakes` polygons;
   * coasts: the zero contour, simplified to 48-64 blocks, merged into `land` polygons;
   * terrain characters: `areas` polygons (base height, rough, crag, hills) where the grid's local relief is
     distinctive (Colovian highlands, Great Forest, Nibenay basin, West Gash, Molag Amur, Grazelands).
3. **Blend.** `tamriel_map.py` already rasterises vectors and blends them with region weights. New vector features
   carry a weight that is 1 inside the province, fades to 0 over 300-500 blocks towards province borders and coasts
   (distance transform of `regions.png`), and is overridden by the plan (road beds and settlement pads come from
   `roadplan.bin` in P2). Result: the game's geography at fan-map detail, our own heights and shapes, no raster copy.
4. **Arena-only provinces** have no height data. Use the province maps (reference only) to note where Arena paints
   mountains, then author ranges, areas and rivers by hand to the relief targets in section 5.
5. **Check** with `tools/qa/parity.py` (FLO, LND) and `REGION-AUDIT.md`'s variety and border checks, plus the relief
   stats of 2.6 (re-run `analyze.py`) against each province's target.

Biome guidance per province is in the modernisation blocks; palettes go to `nirncraft/tamriel/palettes.json` as usual.

## 5. Province by province

Coordinates are block x, z (east, south). "Fit" = position predicted by the source fit; "move" = distance.

### 5.1 Skyrim (and Solstheim)

* **Scale:** reference. Arena's Skyrim fits NirnCraft at 69.5 blocks/px with all 9 city-states within 374 blocks.
* **Moves:** none. Whiterun's capital place sits at (62, -68) while its marker is (0, 0): intended (the city centre
  vs the map marker), leave it.
* **Additions (curated, from Arena and later lore):**

| Name | Arena type | Arena fit | Proposal |
| --- | --- | --- | --- |
| Falcrenth | city-state | (-1710, 1200) | Arena's spelling of Falkreath: add as an alias in lore text only. |
| Karthwasten Hall | town | (-3120, -510) | Arena's Karthwasten: alias. |
| Amol | town | (1510, -1010) | an Eastmarch/Winterhold-border village (later lore keeps it in Eastmarch): add as a small Nord village on the Windhelm-Winterhold road near (1650, -1050), if spacing allows; else a ruined hamlet. |
| Snowhawk | city-state | (-2100, -910) | not in later lore: a ruined hold seat in the northern Reach, as a `dungeon_fixed` ruin ("old Snowhawk"). |
| Granitehall, Oakwood, Sunguard, Vernim Wood | towns | Reach / Falkreath / Rift / Eastmarch | name pool for existing generic farms/hamlets in those holds; no new settlements. |

* **Solstheim:** add Bloodmoon/Dragonborn places that are missing: Thirsk (mead hall, near Lake Fjalding), Castle
  Karstaag (ruin, north-west), Lake Fjalding (lake), Mortrag Glacier (the north-west ice), Hrothmund's Bane (barrow),
  Thormoor's Watch, Aidanat camp. Use Skyrim's Dragonborn markers where they exist (Skyrim's geometry wins on
  Solstheim).
* **Modernisation.** Keep: everything (Skyrim is the benchmark). Amplify: nothing structural. Replace: nothing. Use
  Skyrim as the template the other provinces are measured against.

### 5.2 Cyrodiil

* **Scale:** uniform similarity of Oblivion at 1.754 blocks/m (k = 1.58), least-squares on the 9 cities.
* **Proposed anchor moves** (TAMRIEL.md section 9 table update; then re-ingest every Cyrodiil place through the same
  similarity instead of `ref_warp`'s thin-plate warp):

| City | Current | Fit (uniform) | Move | Note |
| --- | --- | --- | ---: | --- |
| Imperial City | 900, 5900 | 1270, 5540 | 517 | move Lake Rumare and the island with it |
| Bruma | 1500, 3000 | 1020, 3400 | 627 | stays in jerall_mountains; Pale Pass road shortens nothing (Helgen is fixed) |
| Chorrol | -1300, 4400 | -1110, 4850 | 489 | |
| Cheydinhal | 3700, 5000 | 3440, 4620 | 457 | |
| Skingrad | -1700, 6700 | -1080, 7130 | 751 | |
| Kvatch | -2700, 8000 | -3020, 8050 | 324 | keep kvatch_hill peak on it |
| Anvil | -3900, 8700 | -4100, 8220 | 519 | coastal snap to the Gold Coast |
| Bravil | 1800, 8700 | 2350, 7930 | 947 | on the Niben, move the river's middle course |
| Leyawiin | 3600, 9900 | 3250, 10150 (snap) | 420 | the fit (3140, 10560) is Elsweyr land: extend Topal Bay's north-west lobe to the Niben mouth at about (3200, 10250) and move the Blackwood/Rimmen border ~300 west there |

  Alternative with less churn: pin the Imperial City at (900, 5900) and keep the fitted scale and rotation; the
  other cities then move 450-1,320 blocks (Bruma to (650, 3770), Leyawiin to (2770, 10930)). Not recommended:
  bigger moves and Bruma leaves the Jeralls.
* **Coast and relief features to add** (from the Oblivion grid, method of section 4): the Jerall wall with the Pale
  Pass notch; the Valus range along the eastern border (Cheydinhal side) as a separate ridge from the Velothi; the
  Colovian highlands as broken plateau with deep stream valleys west of Chorrol; the Great Forest as rolling hills;
  the Nibenay basin flat; the lower Niben as a broad, braided river with marsh banks to Topal Bay; Blackwood as a
  coastal swamp; the Gold Coast as low cliffs with coves; the Strid river valley on the Valenwood border. Inland lakes
  from the grid (cells under their own water level) as `lakes`.
* **Missing places:** coverage is complete for the Oblivion settlements and landmarks we checked (165 of 183 marker
  names matched; the rest are the 15 Daedric shrines, which `tamriel_shrines.json` holds under its own ids, the Castle
  Skingrad and Waterfront district pins). Note: "Elven Gardens" is an Imperial City district, not a road.
* **Modernisation.** Keep: Oblivion's city sites, road topology and county layout. Amplify: ruggedness from 2.2 to
  about 4 (Colovian highlands and Great Forest valleys, Jerall foothill gorges, Gold Coast cliffs), river valleys
  (Niben, Silverfish, Corbolo, Strid) cut 10-30 blocks below the plains, the Imperial City's lake island silhouette.
  Replace: Oblivion's uniform green hills with distinct county characters (vineyard terraces in the West Weald, open
  heath in the Heartlands, oak-and-beech Great Forest, hot misty Blackwood mangroves).

### 5.3 Morrowind (Vvardenfell, Solstheim, mainland)

* **Scale:** Vvardenfell uniform similarity at 0.95 blocks/m (k = 0.86), least-squares; Solstheim as is.
* **Moves (Vvardenfell; from the least-squares similarity):**

| Place | Current | Fit | Move | Note |
| --- | --- | --- | ---: | --- |
| Vivec | 7400, 1300 | 7140, 1590 | 392 | south coast must come south ~250 (fit is sea today) |
| Sadrith Mora | 8900, 100 | 8700, -130 | 306 | on Tel Fyr's channel: redraw Azura's Coast islands |
| Ald-ruhn | 6100, -500 | 6500, -370 | 422 | |
| Balmora | 6300, 500 | 6390, 550 | 104 | |
| Molag Mar | ~8100, 600 | 8200, 1190 | ~590 | south-east coast |
| Tel Branora | ~8090, 950 | 8430, 1730 | ~850 | southern tip of Azura's Coast |
| Gnisis, Khuul, Ald Velothi, Gnaar Mok | 5330-5850, -1900..-230 | 5490-5850, -1540..10 | 210-580 | the warp over-stretched the west coast (game markers land at x 4,600-4,900); the ingest's land check already pulled the places in |
| Dagon Fel | 7600, -2580 | 7480, -2130 | 450 | Sheogorad |

  Then **replace the 17-vertex Vvardenfell polygon** with `coastline.json` (2,128 mainland points, 206 islands)
  pushed through the same similarity and simplified to 48 blocks; this adds the Bitter Coast's ragged shore, the
  Sheogorad isles, Azura's Coast archipelago and the Odai mouth. Also: the sea south of Vvardenfell is labelled
  `sea/sea_of_ghosts` in `regions.png` at (7140, 1590): it should be `sea/inner_sea` (sea seed fix).
* **Mainland (Arena-only).** Arena backbone: Ebonheart-Mournhold, Mournhold-Narsis, Mournhold-Necrom, Mournhold-Tear,
  Narsis-Kragenmoor, Narsis-Tear, Blacklight-Kragenmoor, Firewatch-Necrom, Firewatch-Mournhold. NirnCraft has all ten
  Arena city-states. Arena's seven towns (Stoneforest, Karththor Dale, Oaktown, Eagle Moor, Silnim Dale, Dragon Glade,
  Glen Haven) are generated names: use them as a name pool for existing generic villages in Stonefalls and Deshaan; do
  not add. Keep Ebonheart on the mainland (Stonefalls) as designed, and add the Vvardenfell-side Imperial fortress
  next to Vivec as its own landmark ("Castle Ebonheart", about (7000, 1650) after the move).
* **Missing named places:** Holamayan monastery (east of Vivec on Azura's Coast), the Ministry of Truth (a landmark
  pin over Vivec), Tel Fyr's tower (exists as the `tel_fyr` village; add the tower landmark), Mawia (Velothi tower),
  plus the Solstheim list in 5.1.
* **Modernisation.** Keep: Vvardenfell's geography (game-faithful), the Propylon ring, the travel networks.
  Amplify: Red Mountain's caldera and the Foyada lava channels radiating from it (TES3 drew them shallow), Molag Amur's
  broken lava fields, the West Gash's ridges and gorges, the Bitter Coast's tangled inlets, the Grazelands' long open
  slopes, Azura's Coast as hundreds of rocky islets. Replace: mainland Morrowind's empty middle with a proper Velothi
  wall (300-450 blocks) and passes, Deshaan's ash-and-fungus plains around Mournhold, Stonefalls' volcanic
  shields and lava fields, and the Telvanni peninsula's coastal mushroom forests and cliffs.

### 5.4 High Rock and northern Hammerfell (Iliac Bay): hooks for the Daggerfall agent

Daggerfall is the authoritative source for the Iliac Bay regions (another agent; `daggerfall_ref/iliac_coast_mc.json`,
`regions_mc.json`). This doc only records what Arena says, so the two can be reconciled:

* Arena's High Rock backbone: North Point-Shornhelm, Daggerfall-Camlorn, Camlorn-Wayrest, Shornhelm-Wayrest,
  Shornhelm-Evermore, Wayrest-Evermore, Evermore-Farrun, Evermore-Jehanna, Farrun-Jehanna.
* Arena puts Wayrest at about (-7510, 930) (central High Rock) where NirnCraft follows Daggerfall's Iliac Bay
  shore (-5300, 3300): follow Daggerfall.
* Arena's Iliac Bay is a small inlet; NirnCraft's (Daggerfall-scale) bay is much bigger: Arena shows land where we
  have sea around (-9300..-6300, 3850..4500). Follow Daggerfall.
* High Rock is already 1.54x Skyrim (section 2.4): add no towns; Arena's 7 missing High Rock towns (Old Gate, Meir
  Darguard, Kings Guard, Portdun Creek, Vermeir Wastes, Raven Spring, Cloud Spring) are a name pool only.
* **Modernisation (for the Daggerfall agent).** Keep: Daggerfall's region map and city sites. Amplify: the
  Wrothgarian Mountains as High Rock's spine (300-480, Orsinium on its plateau), Rivenspire's dark crags (80-160),
  Glenumbra's moors and sea cliffs, the Bjoulsae as a deep river valley to the bay. Replace: Daggerfall's thousands of
  generic hamlets with Skyrim-density curated towns.

### 5.5 Southern Hammerfell and Stros M'kai

* **Arena backbone:** Sentinel-Hegathe, Sentinel-Gilane, Sentinel-Skaven, Hegathe-Gilane, Gilane-Taneth,
  Gilane-Skaven, Taneth-Rihad, Taneth-Elinhir, Rihad-Elinhir, Rihad-Skaven, Dragonstar-Elinhir, Dragonstar-Skaven,
  Elinhir-Skaven.
* **Moves:** Gilane is Arena's neighbour of Hegathe and Taneth on the south coast (69 and 67 px of road) but
  NirnCraft has it alone in the far west at (-9900, 6050). Move Gilane to the Abecean shore near (-8650, 6850)
  (Alik'r desert region, ~550 west of Hegathe). The other Hammerfell residuals (Taneth, Elinhir, Dragonstar, Skaven:
  1,400-1,900) are within the Daggerfall agent's area or follow the lore map: no change.
* **Arena towns missing** (8): Sunkeep, Lainebon Place, Vulnim Gate (Alik'r), Heldorn Mount (Bangkorai), Riverpoint,
  Roseguard, North Hall (Dragontail), Belkarth Guard (Craglorn). Hammerfell has 2.3 settlements/km2 already: name
  pool only. Exception: Belkarth (Craglorn's town in later lore) may replace a generic Craglorn village.
* **Stros M'kai (Redguard layout, scaled at 0.021 blocks/unit onto the current island):**

| Site (our label) | Redguard position -> NirnCraft | Current NirnCraft place | Proposal |
| --- | --- | --- | --- |
| town and harbour (east side of a south-facing bay) | (-10200..-9980, 7240..7370) | `port_hunding` (-10150, 7260) | keep; lay the town on the bay's east shore, harbour tower and warehouse on the west quay (-10180..-10230, 7310) |
| palace (walled plateau NE of town) | gate (-10060, 7210), grounds (-9990, 7070) | none | add a palace landmark on a 15-20 block plateau |
| catacombs (under the town centre) | (-10120, 7310) | none | dungeon entrance by the bell tower |
| hideout cave behind a waterfall | (-10240, 7220) | `nuset_hideout` (-10318, 6856) | move to the waterfall north-west of the town |
| observatory (centre-south hills) | (-10240, 7500) | none | add landmark |
| lighthouse (south point) | (-10160, 7630) | `stros_mkai_lighthouse` (-9950, 7050) | move to the south point |
| Dwemer ruin (high western plateau, the island's highest ground) | (-10580, 7520..7580) | `bthzark` (-10450, 7000) | move to the western plateau, ~550 south |
| necromancer's isle (off the north-west coast, by boat) | boat (-10660, 7060) | `ngastas_tower` (-10300, 7480) | move to a new islet ~250 off the north-west coast, about (-10850, 6950) |
| rope-plank causeway along the north coast | (-10490, 6990) | none | add as a landmark |

  Island relief: Redguard's levels put the western plateau at the top (level ~105-108 of 127), the palace plateau
  and town terraces in the middle (56-72), the harbour low (22). Shape: two lobes (east: town and palace; west:
  plateau and Dwemer ruin) round the harbour bay.
* **Modernisation.** Keep: city sites, the Alik'r as Hammerfell's heart, Stros M'kai's Redguard layout. Amplify: the
  Alik'r into real dune seas with wind-cut mesas (dunes 20-80, mesas to 150), Craglorn's eroded badlands (100-200),
  the Dragontail Mountains as a red-rock wall (300-470) with slot canyons, Bangkorai's gorge passes. Replace: flat sand
  plains with oases, wadis and salt flats; the coast with sea arches and harbours cut into cliffs.

### 5.6 Elsweyr

* **Arena backbone:** Corinth-Alabaster, Corinth-Rimmen, Corinth-Torval, Corinth-Dune, Alabaster-Senchal,
  Alabaster-Rimmen, Alabaster-Dune, Senchal-Torval, Torval-Dune, Dune-Riverhold.
* **Moves:** Arena's largest residuals are Torval (1,666), Alabaster (1,675), Portneu View (1,689) and two villages;
  Torval is a binding anchor that follows the lore map, so no change. Arena shows sea where we have land in the south
  (about 1,500-2,700, 12,900-14,100): consider a bay or deep inlet south of Torval so Senchal sits on a real harbour
  coast and Torval near it (lore: both are southern coastal cities).
* **Missing:** Elsweyr matched 31 of 32 Arena settlements; only "South Guard" (village) is missing. Nothing to add.
* **Modernisation.** Keep: north (Anequina) arid, south (Pellitine) lush. Amplify: Anequina's mesas, canyons and
  natural arches (60-200 blocks; today max 138), the Pellitine river valleys and the sugar-cane floodplain, the
  Tenmar forest. Replace: the flat interior with a central escarpment between the two halves, and moon-temple sites on
  outcrops.

### 5.7 Valenwood

* **Arena backbone:** Eldenroot-Silvenar, Eldenroot-Greenheart, Eldenroot-Arenthia, Eldenroot-Haven,
  Eldenroot-Southpoint, Silvenar-Falinesti, Silvenar-Greenheart, Silvenar-Arenthia, Woodhearth-Falinesti,
  Woodhearth-Greenheart, Falinesti-Greenheart, Greenheart-Southpoint, Haven-Southpoint.
* **Moves:** Arena spreads the Reaper's March town cluster (Eagle Vale, Emperors Run, Wasten Brukbrook, Lynpar March)
  across Grahtwood, 2,100-3,200 blocks from where NirnCraft has them; these are Arena-generated names, so no move:
  instead spread NirnCraft's eastern cluster so no two villages are closer than ~600 blocks.
* **Missing towns:** Thormar Keep, Vulkwasten Wood, Karthdar Square: name pool for Grahtwood generic villages.
* **Modernisation.** Keep: Valenwood is lowland forest. Amplify: vertical interest by trees, not hills: Graht-oak
  giants (100+ blocks) as landmarks and walking cities (Falinesti), deep river gorges (Xylo, Strid; 20-40 blocks
  below the forest floor), a few highland knolls in Reaper's March (60-120). Replace: today's uniform 18-block plain
  (max 65) with canopy layers, mangrove deltas on the coast and sinkhole lakes.

### 5.8 Summerset

* **Arena backbone:** Dusk-Sunhold, Dusk-Shimmerene, Sunhold-Alinor, Sunhold-Lillandril, Sunhold-Cloudrest,
  Alinor-Lillandril, Alinor-Cloudrest, Lillandril-Cloudrest, Firsthold-Skywatch (Auridon).
* **Moves:** none to anchors. Arena's shape is not usable (it draws Summerset far too big, fit 34 blocks/px with
  1,000+ block residuals everywhere): follow the lore map. Silsailen Point (Arena village) is the only missing name.
* **Modernisation.** Keep: two islands (Summerset Isle, Auridon) and Artaeum. Amplify: white sea cliffs (40-80),
  a central upland for the Crystal Tower (to 120), Auridon's ridge (60-120), terraced vineyards. Replace: today's
  20-30 block plain (max 75) with a high, bright island silhouette visible from the sea.

### 5.9 Black Marsh

* **Arena backbone:** Stormhold-Thorn, Stormhold-Helstrom, Stormhold-Gideon, Stormhold-Soulrest, Thorn-Lilmoth,
  Thorn-Archon, Helstrom-Blackrose, Helstrom-Lilmoth, Helstrom-Archon, Gideon-Soulrest, Gideon-Blackrose,
  Soulrest-Blackrose, Soulrest-Lilmoth, Blackrose-Lilmoth, Lilmoth-Archon.
* **Moves:** Soulrest (4955, 9568) is 1,567 blocks west of Arena's fit (6520, 9690); Chasepoint likewise. Arena shows
  sea where we have land at about (5,680, 9,900): Topal Bay's eastern arm should reach in there. Proposal: extend
  Topal Bay north-east to about (5,700, 9,900) and move Soulrest to its eastern shore, about (6,100, 9,700).
  Coast: Arena has land where we have sea on the east coast near Thorn and Archon (about 9,900-10,500, 4,500-5,100)
  and in the south-east round Lilmoth (8,700-9,300, 9,300-10,500): bulge the east coast out by ~500 there and give
  Lilmoth a delta; Arena has sea where we have land at (10,500, 6,300-6,900): cut a bay there. Verify each against
  the lore map before editing.
* **Missing:** Alten Meirhall (village) only.
* **Modernisation.** Keep: flat, wet, near sea level (median 1 block is right). Amplify: not height but structure:
  braided channels, oxbows, floating islands of reed, Hist groves on low rises (10-20), xanmeer step-pyramids as
  silhouettes, a few north-western uplands toward Cyrodiil (30-80). Replace: the uniform marsh with distinct
  sub-regions (Shadowfen brackish flats, Murkmire mangroves, Helstrom deep mire).

## 6. Road and travel network plan

### 6.1 How it feeds the pipeline

`mod/src/main/resources/nirncraft/roads/named_roads.json` is the P1 input: `{id, class, route: [place ids], bridge?}`.
`RoadGraph` builds highways from it first, then roads and lanes, `RoadPlanBuilder` routes and profiles them and bakes
`roadplan.bin`; `linkName` carries the road's name to signposts and the map. Adding a province's roads = adding
entries (place ids must exist), re-running `tools/bake/bake.ps1`, then `roadplan_lint.py` and the corridor gate.
Today it has 20 entries (9 Cyrodiil, 11 Skyrim). Rules for new entries:

* class `highway` for the backbones below; inter-province links are highways through a named pass;
* the route lists the real intermediate stops (inns, villages, forts) so the planner keeps them on the road;
* cross-province routes end at a border fort or inn on each side, so each province's graph bakes alone too.

### 6.2 Per province

| Province | Source of topology | Named highways (route) |
| --- | --- | --- |
| Skyrim | game (authored) | as today: White River, Pale Pass, Falkreath, Treva, Reach, Karth, Hjaal, Eastmarch, Rift, Pale, Winterhold roads |
| Cyrodiil | Oblivion ROAD graph (11 routes extracted) | as today (Red Ring, Gold, Black, Silver, Orange, Blue, Green, Yellow, Talos Bridge) + **Chorrol-Skingrad** (Colovian road, 181k units in game) + **Bruma-Cheydinhal** (Jerall foothill road, 185k) + a mountain trail Bruma-Chorrol (the extractor labelled it "Orange Road"; NirnCraft's `orange_road` is Bleaker's Way-Bruma: keep ours, treat this one as a trail) |
| Vvardenfell | 174 signpost junctions + 291 road polylines | West Gash road (Gnisis-Ald Velothi-Khuul), Ald-ruhn-Maar Gan, Ald-ruhn-Caldera-Balmora (the main road, cobbled near Balmora), Balmora-Pelagiad-Vivec (main), Seyda Neen-Pelagiad, Vivec-Suran-Molag Mar (Molag Amur foyada road), Vos-Tel Vos road (Grazelands). The ash wastes and Red Mountain get trails only. |
| Mainland Morrowind | Arena backbone (5.3) | Ebonheart-Mournhold, Mournhold-Narsis-Kragenmoor, Narsis-Tear, Mournhold-Necrom, Firewatch-Necrom, Blacklight-Kragenmoor |
| High Rock, N. Hammerfell | Daggerfall agent | Arena backbones in 5.4/5.5 as a cross-check |
| S. Hammerfell | Arena | Sentinel-Hegathe-Gilane-Taneth-Rihad (coast road), Sentinel-Skaven-Dragonstar-Elinhir (inland), Rihad-Elinhir |
| Elsweyr | Arena | Riverhold-Dune-Torval-Senchal (north-south spine), Rimmen-Alabaster, Corinth-Torval, Corinth-Rimmen |
| Valenwood | Arena | Arenthia-Silvenar-Falinesti-Woodhearth, Eldenroot-Silvenar, Eldenroot-Greenheart-Southpoint-Haven (canopy paths and river fords rather than paved roads) |
| Summerset | Arena | Alinor-Sunhold-Dusk-Shimmerene, Alinor-Cloudrest-Lillandril; Auridon: Firsthold-Skywatch |
| Black Marsh | Arena | Stormhold-Helstrom-Archon-Lilmoth (causeways), Stormhold-Gideon-Soulrest, Thorn-Archon; most travel by water (6.3) |

**Cross-province highways** (our own names; each through a pass or crossing): Skyrim-Cyrodiil = Pale Pass (exists);
Skyrim-High Rock (Markarth-Jehanna/Evermor over the Druadach); Skyrim-Hammerfell (Falkreath-Elinhir into Craglorn);
Skyrim-Morrowind (Windhelm-Blacklight over the Velothi; the vectors' `morrowind_road`); Cyrodiil-Morrowind
(Cheydinhal-Kragenmoor over the Valus); Cyrodiil-Black Marsh (Leyawiin-Stormhold through Blackwood); Cyrodiil-Elsweyr
(Bravil or Leyawiin-Rimmen); Cyrodiil-Valenwood (Skingrad-Arenthia along the Strid); Cyrodiil-Hammerfell
(Kvatch-Rihad over the Dragontail). These replace the unnamed `*_road` corridors in `tamriel_vectors.json`.

### 6.3 Fast travel per province (gameplay)

| Province | System | Nodes | Status / proposal |
| --- | --- | --- | --- |
| Skyrim | carriages | hold capitals' stables | exists (`roads/Carriages`) |
| Skyrim | ships | Solitude, Dawnstar, Winterhold, Windhelm; Windhelm-Raven Rock ferry | exists (`ports.json`) |
| Cyrodiil | carriages | a stable outside each of the 8 cities + the Imperial City waterfront | extend `Carriages` to Cyrodiil (Oblivion had stables, not carriages: our feature) |
| Cyrodiil | river boats | IC waterfront - Bravil - Leyawiin down the Niben | new; reuses the ports/ferry code with a river route |
| Vvardenfell | silt striders | game: Ald-ruhn, Balmora, Gnisis, Khuul, Maar Gan, Molag Mar, Seyda Neen, Suran, Vivec (16 links) | exists (`creatures/travel/StriderPorts`, 10 ports incl. Ebonheart); restrict each port's offers to its game links |
| Vvardenfell | boats | 25 links: Hla Oad, Gnaar Mok, Khuul, Dagon Fel, Tel Mora, Vos, Tel Aruhn, Sadrith Mora, Tel Branora, Molag Mar, Vivec, Ebonheart, Holamayan; Khuul - Fort Frostmoth - Raven Rock | add the coastal boat network (`ports.json` routes) |
| Vvardenfell | Mages Guild guides | Ald-ruhn, Balmora, Caldera, Vivec, Sadrith Mora (Wolverine Hall): all-to-all | new: a guild service (teleport, fee, guild membership discount) |
| Vvardenfell | Propylon chambers | ring of 10 strongholds: Andasreth-Berandas-Falasmaryon-Valenvaryon-Rotheran-Indoranyon-Falensarano-Telasero-Marandus-Hlormaren-Andasreth (all 10 exist as `fort` places) | new: each needs its index item (quest reward / loot), a ring, not a hub |
| Vvardenfell | interventions | Almsivi -> nearest Tribunal temple; Divine -> nearest Imperial cult shrine / Legion fort (19 targets in `shrines.json`) | new spells/scrolls |
| Vvardenfell | Mark/Recall | any | new spell pair |
| Morrowind | Mournhold | Ebonheart <-> Mournhold teleport (Tribunal) | road exists on our mainland; keep a temple teleport as flavour |
| Hammerfell, High Rock | ships along the Iliac Bay and Abecean coast | Sentinel, Daggerfall, Wayrest, Taneth, Stros M'kai | ports exist; add the Iliac Bay ferry ring |
| Elsweyr | caravans (Khajiit) | Riverhold, Dune, Rimmen, Torval, Senchal | new: a carriage variant with traders; desert legs |
| Valenwood | river boats / canopy lifts | Woodhearth, Falinesti, Elden Root, Arenthia | new; boats on the Xylo and Strid |
| Summerset | ships | Alinor, Firsthold, Lillandril, Shimmerene | ports for Alinor and Firsthold exist; add the inter-island ferry |
| Black Marsh | swamp boats | Lilmoth, Gideon, Stormhold, Helstrom, Soulrest | new; Black Marsh's roads are causeways, its main travel is by water |

## 7. Relief and biome targets per province (blocks above sea, macro raster)

| Province | p50 now -> target | p90 now -> target | ruggedness now -> target | Signature features to author |
| --- | --- | --- | --- | --- |
| Skyrim | 74 | 168 | 5.6 | (reference) |
| Cyrodiil | 34 -> 40 | 114 -> 150 | 2.2 -> 4 | Jerall wall and foothill gorges, Colovian plateau valleys, Niben braids, Gold Coast cliffs |
| Morrowind | 21 -> 30 | 150 -> 170 | 4.0 -> 5 | Red Mountain caldera and foyadas, Velothi wall on the mainland, Molag Amur lava fields |
| High Rock | 22 -> 35 | 125 -> 160 | 3.4 -> 4.5 | Wrothgarian spine, Rivenspire crags (Daggerfall agent) |
| Hammerfell | 27 -> 35 | 98 -> 150 | 2.5 -> 4.5 | dune seas, mesas, Dragontail wall, Craglorn badlands |
| Elsweyr | 19 -> 30 | 64 -> 130 | 1.5 -> 3.5 | Anequina mesas and canyons, central escarpment |
| Valenwood | 18 -> 22 | 35 -> 60 | 1.2 -> 2.5 | river gorges, knolls, giant trees (vertical by vegetation) |
| Summerset | 23 -> 40 | 40 -> 100 | 1.9 -> 3.5 | white cliffs, central upland, Auridon ridge |
| Black Marsh | 1 -> 3 | 2 -> 10 | 0.4 -> 1 | channels, Hist rises, xanmeer, north-west uplands |

Targets keep each province distinct from Skyrim (no province gets Skyrim's alpine average) while removing the flat,
noise-only look. They are reached with `ranges`, `areas`, `rivers` and `lakes` in `tamriel_vectors.json` (section 4),
never by raising the global noise.

## 8. Top 10 map changes (in order)

1. **Cyrodiil uniform scale:** switch the Cyrodiil ingest from the thin-plate warp to the least-squares similarity
   (1.754 blocks/m, -3.24 deg); move the 9 anchors to the fitted positions (5.2; moves 324-947 blocks), Lake Rumare and
   the Imperial isle with the Imperial City (to 1270, 5540), Bravil's Niben course with Bravil.
2. **Topal Bay:** extend its north-west lobe to Leyawiin (snap to about 3250, 10150, the Niben mouth) and its eastern
   arm into Black Marsh to about (5700, 9900), moving Soulrest onto that shore (6100, 9700).
3. **Vvardenfell coast and scale:** replace the 17-vertex polygon with the extracted coastline through the uniform
   similarity (0.95 blocks/m): south coast ~250 further south (Vivec 7140, 1590; Molag Mar; Tel Branora), West Gash
   towns 210-580 blocks east, Sheogorad and Azura's Coast islands; fix the `sea_of_ghosts` label south of Vvardenfell.
4. **Relief pass for the flat provinces** (Summerset, Valenwood, Elsweyr, Hammerfell, Cyrodiil) to the section 7
   targets, using the vector method of section 4.
5. **Stros M'kai to Redguard's layout:** lighthouse to the south point, Bthzark to the western plateau, N'Gasta's tower
   to a new islet off the north-west, add palace, observatory, catacombs, causeway (5.5).
6. **Gilane** to the Abecean shore near Hegathe and Taneth (about -8650, 6850).
7. **Named roads for every province** (6.2): about 40 new `named_roads.json` highways from the Arena backbones,
   Morrowind's signpost topology and Oblivion's two extra links, plus 9 named cross-province passes.
8. **Morrowind travel networks** (6.3): restrict strider offers to the game links, add the boat network, guild guides,
   the Propylon ring and interventions.
9. **Black Marsh coast:** bulge the east coast by ~500 near Thorn and Archon, give Lilmoth a delta, cut a bay at
   (10500, 6300-6900) (verify each against the lore map).
10. **Missing named places:** Solstheim (Thirsk, Castle Karstaag, Lake Fjalding, Mortrag Glacier, Hrothmund's Bane,
    Thormoor's Watch, Aidanat), Vvardenfell (Holamayan, Ministry of Truth, Tel Fyr's tower, Mawia, Castle Ebonheart),
    Skyrim (Amol village, ruined Snowhawk). Everything else from Arena is a name pool, not new places.

## 9. Gaps and caveats

* Skyrim road geometry not extracted (topology exists in `named_roads.json`).
* Arena's road links are read off map art (a link = two BFS fronts meeting on road pixels); a few long links may hide a
  junction village. Arena's 14 random-dungeon slots per province have no names or positions.
* Redguard's unit is calibrated by function (town size), not by data.
* The coast-disagreement checks against Arena (5.6, 5.9) depend on the per-province fit (rms ~1 km): treat them as
  pointers to look at, not as coordinates.
* Lore-map area ratios in 2.4 are estimates by eye.
* Daggerfall (High Rock, northern Hammerfell) belongs to another agent; this doc leaves hooks only.

## 10. Re-running

```
py -3 -I .reference/arena_ref/scripts/citydata.py "<Arena>/ARENA" .reference/arena_ref
py -3 -I .reference/arena_ref/scripts/arena_img.py "<Arena>/ARENA" .reference/arena_ref/raw TAMRIEL.IMG ...
py -3 -I .reference/arena_ref/scripts/arena_build.py .reference/arena_ref
py -3 -I .reference/redguard_ref/scripts/redguard.py "<Redguard>/Redguard" .reference/redguard_ref
py -3 -I .reference/redguard_ref/scripts/sites.py .reference/redguard_ref
py -3 -I .reference/morrowind_ref/scripts/mw_roads.py
py -3 -I .reference/scale_analysis/analyze.py .
py -3 -I .reference/scale_analysis/proposals.py .
```
