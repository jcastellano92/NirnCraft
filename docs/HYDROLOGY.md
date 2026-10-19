# Hydrology

Tributaries, springs, lake basins, deltas, waterfalls and river sites for the Tamriel map. Authored rivers and lakes
(`tools/worldgen/tamriel_vectors.json`) are never moved; everything here is derived on top of them and baked into the
same rasters the Java side already reads (`MapRaster` / `TamrielHeight` / `MapWaterFeature`) plus
`nirncraft/tamriel/hydrology.json`.

## Method

* `tools/worldgen/hydrology.py`: priority-flood pit filling with an epsilon gradient (Barnes 2014) gives a drainage
  tree (D8 receivers); flow accumulation is weighted by a runoff index (province climate, orographic boost, dunes dry).
  Cells with accumulation >= 44 become brooks (half width 1, stored as the 2-block-wide channel), widening with
  accumulation up to 3.4 (below the river-biome threshold `TamrielMap.RIVER_BIOME_HALF_WIDTH` = 3.5); trunk flows go up
  to 8 half width. Streams end at an authored river (snapped to its centre line), a lake shore, the sea, or a place's
  exclusion zone. Water levels follow the ground (never above it, never rising downstream).
* Closed depressions: the 110 largest become lakes with spill level - 0.4 (outlets by construction); the rest are
  filled to their spill height (no dry pits). Lake outlets of lakes with no authored river are forced streams.
* Dead-end authored rivers (Hjaal, Bjoulsae, Onkobra) get a continuation to the sea; deltas fan out of the Niben,
  Onkobra, Karth, Strid and Xylo mouths over flat wet coast.
* Waterfalls: a drop >= 6.5 blocks (streams) / 7 (rivers) within 48 blocks is squared into one step; `Hydrology.apply`
  makes the water level a sharp step at the lip (no per-chunk compute: a table lookup), and the existing height
  function carves the cliff and the plunge pool. 1 in 4 big falls (drop >= 9) hide a cave behind the curtain.
* River sites (`explore/RiverSiteFeature`, baked placement): fishing huts, watermills, fords, ferry landings, toll
  posts, fall overlooks, fall caves, sunken caches, bank caches (boulder / hollow log), hidden fishing spots, springs.
* Life: `seas/RiverRun` (autumn salmon run, days 48-71 of a 96-day cycle) + salmon in `SeaLife.freshPicks`.
* Layout safety: place exclusion zones (settlements r*1.35+44, forts r+36, ...) are bumped in the flow routing so
  streams route round them; lakes keep 4 cells away; roads cross streams through the Paver's data-driven bridges.

## Findings (baked rasters, before -> after)

| metric | before | after |
| --- | --- | --- |
| lakes / without a channel touching them | 11 / 2 | 73 / 0 |
| rivers ending inland (dead ends) | 3 (Hjaal, Bjoulsae, Onkobra) | 3 authored ends, each now continued by an extension channel |
| unresolved depressions (>= 5 cells, >= 3 blocks) | 396 | 126 |
| big drainage (>= 800 cells) without any channel | 93 | 36 (plus 1,227 cells inside place zones, on purpose) |
| mountain run-off reaching a channel | 59% | 96% |
| median flow path from a mountain cell to a channel | 1,536 blocks | 128 blocks |
| places standing in a channel or lake | 5 settlements / 18 other | 5 / 19 (the 5 are authored river towns) |

Counts of the derived network: ~2,260 stream segments (677 km of blocks), 8 delta/extension channels, 444 waterfalls
(150 big, 41 with a secret), ~1,360 springs (305 mountain-fed), 527 river sites. Level rises along derived streams: 0.
Small (<= 3 block) level wobbles at some confluences remain where a tributary and a river share a 32-block cell.

## Tools

```
py -3 -I tools/worldgen/tamriel_map.py --no-preview            # bakes everything (TAMRIEL_OUT=dir to experiment)
py -3 -I tools/worldgen/hydrology_report.py analyze --dir DIR  # the table above
py -3 -I tools/worldgen/hydrology_report.py compare --before DIR --after DIR --out tools/qa/out/hydrology
```

Views: `tools/qa/out/hydrology/` (gitignored), small JPGs in `docs/screens/hydrology/`.

## Needs in-game confirmation

Brook channels at 32-block raster resolution (plane-fit distance), the cliff + plunge pool at falls, the fall cave
(rock check can reject sites), bridges over streams by the Paver, river-site builders on real terrain, fish run.
