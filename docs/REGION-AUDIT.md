# Region variety and transition audit

Data-only audit of every land region's variety and of every border between adjacent regions, with the fixes made in this pass and what is left. Generated numbers come from `py -3 -I tools/qa/region_audit.py` (reads the rasters, `palettes.json`, biome and feature JSON, structure and place lists; `--json PATH` to save). Renders: `py -3 -I tools/qa/region_render.py maps|transects` into `tools/qa/out/regions/` (gitignored).

## 1. What the world does today

* Each region (61 land regions, `regions.png` at 32 blocks per pixel) owns a palette (`palettes.json`, from `tools/worldgen/biomes.py`): terrain context (lowland/upland/peak/summit/beach/river/lake/marsh/caves) to weighted biomes. `TamrielBiomeSource` looks the region up at a domain-warped position and walks the palette with a smooth climate value. There is no cross-region blending: at a border one palette simply switches to the next (the warp only wobbles the line by a few tens of blocks).
* Region borders are hand-authored polygons (`tamriel_vectors.json`). 42 of the 130 borders are more than 60% straight over a 640-block window; but most real jumps are climate, not geometry: palettes were written per region with no shared biome with the neighbour.
* Terrain (elevation, rivers, ridges) is already continuous across borders, so seams are palette seams: tree/flora/surface-block changes along a line, not cliffs.

### Variety score

0-100, mean of min-max normalised parts (weights in brackets): effective biomes with at least 4% of the land (1.5), distinct surface biomes in the palette incl. beach/river/lake/marsh (1.0), tree species (1.0), flora species (1.0), province/region-specific structure kinds (1.0), places (0.7), places per 1000 km2 (0.7), land elevation spread p5-p95 (0.8). Tree species include the custom `flora_tree` species; flora counts block names of plants/flowers/mushrooms/crystals in the biome's resolved feature graph. Structure kinds: all 126 generic dungeon kinds spawn everywhere, so only the culture/province-specific kinds differ per region.

| Region | km2 | biomes (eff/all) | trees | flora | struct kinds | places (/1000 km2) | elev p5-p95 | T / D | variety before | after |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| elsweyr/torval | 4 | 3/6 | 10 | 13 | 1 | 61 (14848.0) | 77-99 | 1.29 / 0.50 | 38 | 30 |
| hammerfell/hews_bane | 3 | 3/6 | 7 | 10 | 2 | 72 (18272.5) | 65-98 | 1.72 / 0.17 | 39 | 31 |
| skyrim/winterhold | 2 | 4/8 | 10 | 5 | 0 | 30 (12860.8) | 63-164 | -0.50 / 0.51 | 26 | 31 |
| black_marsh/helstrom | 7 | 3/6 | 10 | 12 | 1 | 111 (15465.6) | 63-65 | 0.92 / 0.94 | 40 | 32 |
| black_marsh/lilmoth | 4 | 4/6 | 10 | 10 | 1 | 70 (15127.1) | 63-66 | 0.94 / 0.89 | 24 | 32 |
| valenwood/malabal_tor | 3 | 2/5 | 11 | 13 | 3 | 72 (19828.7) | 65-95 | 0.97 / 0.94 | 39 | 32 |
| morrowind/red_mountain | 1 | 1/5 | 8 | 15 | 3 | 31 (19735.0) | 249-474 | 1.40 / 0.00 | 38 | 33 |
| elsweyr/senchal | 3 | 3/7 | 10 | 13 | 1 | 68 (19170.4) | 65-84 | 1.18 / 0.66 | 32 | 33 |
| black_marsh/gideon | 6 | 4/6 | 10 | 10 | 1 | 105 (15277.0) | 63-65 | 0.94 / 0.92 | 26 | 34 |
| black_marsh/murkmire | 7 | 3/7 | 9 | 14 | 1 | 115 (16356.6) | 63-65 | 0.98 / 0.96 | 35 | 35 |
| elsweyr/pellitine | 8 | 3/6 | 10 | 14 | 1 | 130 (15875.1) | 66-82 | 1.17 / 0.69 | 34 | 35 |
| valenwood/grahtwood | 10 | 2/5 | 11 | 13 | 3 | 150 (14910.9) | 67-90 | 0.96 / 0.92 | 43 | 36 |
| valenwood/greenshade | 4 | 3/5 | 10 | 13 | 3 | 82 (18158.3) | 65-80 | 0.99 / 0.96 | 36 | 36 |
| hammerfell/stros_mkai | 0 | 4/6 | 12 | 12 | 2 | 14 (20715.0) | 64-76 | 1.30 / 0.57 | 22 | 36 |
| hammerfell/bangkorai | 2 | 2/7 | 13 | 14 | 2 | 41 (17184.2) | 66-194 | 0.87 / 0.40 | 32 | 38 |
| black_marsh/stormhold | 5 | 4/7 | 11 | 11 | 1 | 94 (18684.5) | 63-68 | 0.94 / 0.89 | 30 | 38 |
| summerset/auridon | 2 | 3/6 | 9 | 17 | 2 | 67 (24019.7) | 64-94 | 0.82 / 0.53 | 38 | 38 |
| hammerfell/dragontail | 5 | 3/6 | 10 | 10 | 2 | 91 (15852.2) | 116-288 | 1.15 / 0.27 | 23 | 40 |
| hammerfell/alikr_desert | 10 | 4/7 | 8 | 7 | 2 | 168 (16495.3) | 65-98 | 1.79 / 0.07 | 29 | 41 |
| hammerfell/craglorn | 2 | 4/8 | 11 | 10 | 2 | 39 (16602.4) | 120-169 | 1.29 / 0.22 | 18 | 41 |
| black_marsh/shadowfen | 7 | 4/7 | 11 | 11 | 1 | 137 (18961.0) | 63-78 | 0.88 / 0.87 | 33 | 42 |
| cyrodiil/great_forest | 3 | 2/8 | 20 | 19 | 1 | 62 (16206.3) | 58-104 | 0.72 / 0.75 | 48 | 42 |
| summerset/summerset_isle | 10 | 3/6 | 9 | 17 | 2 | 148 (14269.0) | 64-114 | 0.81 / 0.57 | 50 | 42 |
| skyrim/hjaalmarch | 1 | 3/10 | 18 | 16 | 0 | 27 (19106.7) | 63-115 | 0.16 / 0.71 | 51 | 43 |
| summerset/artaeum | 0 | 4/7 | 9 | 19 | 2 | 11 (25335.3) | 63-89 | 0.74 / 0.64 | 35 | 44 |
| skyrim/the_reach | 4 | 4/9 | 13 | 11 | 0 | 55 (11846.3) | 106-285 | 0.37 / 0.40 | 55 | 45 |
| high_rock/bjoulsae | 5 | 2/8 | 14 | 15 | 4 | 79 (15476.1) | 67-111 | 0.67 / 0.64 | 52 | 46 |
| high_rock/rivenspire | 4 | 2/7 | 15 | 14 | 4 | 85 (17855.0) | 67-140 | 0.46 / 0.79 | 52 | 46 |
| elsweyr/anequina | 5 | 5/8 | 9 | 10 | 1 | 95 (17022.7) | 99-166 | 1.66 / 0.08 | 46 | 46 |
| elsweyr/rimmen | 4 | 5/8 | 12 | 11 | 1 | 78 (18209.9) | 71-107 | 1.34 / 0.26 | 35 | 46 |
| skyrim/haafingar | 1 | 3/10 | 20 | 17 | 0 | 29 (17310.7) | 63-154 | 0.14 / 0.55 | 55 | 47 |
| skyrim/the_pale | 1 | 5/9 | 14 | 13 | 0 | 35 (20046.7) | 65-136 | -0.18 / 0.50 | 23 | 47 |
| cyrodiil/west_weald | 4 | 3/10 | 16 | 18 | 1 | 86 (20824.3) | 89-112 | 0.93 / 0.41 | 48 | 50 |
| morrowind/telvannis | 8 | 4/8 | 12 | 25 | 3 | 45 (5145.2) | 65-90 | 0.81 / 0.63 | 39 | 50 |
| high_rock/glenumbra | 12 | 2/7 | 19 | 15 | 4 | 208 (16756.7) | 66-90 | 0.62 / 0.72 | 57 | 51 |
| morrowind/bitter_coast | 0 | 3/6 | 12 | 22 | 3 | 22 (42883.0) | 63-140 | 0.88 / 0.80 | 59 | 51 |
| skyrim/falkreath | 4 | 4/9 | 20 | 16 | 0 | 50 (10783.6) | 124-281 | 0.29 / 0.72 | 61 | 52 |
| morrowind/azuras_coast | 1 | 2/7 | 8 | 21 | 3 | 64 (47528.5) | 64-222 | 0.83 / 0.61 | 58 | 52 |
| morrowind/molag_amur | 4 | 4/8 | 11 | 21 | 3 | 42 (8715.6) | 64-136 | 1.04 / 0.16 | 38 | 52 |
| cyrodiil/gold_coast | 5 | 3/11 | 17 | 18 | 1 | 101 (18028.3) | 73-112 | 0.96 / 0.38 | 58 | 54 |
| high_rock/stormhaven | 6 | 2/9 | 21 | 15 | 4 | 104 (16806.6) | 67-92 | 0.71 / 0.76 | 60 | 54 |
| morrowind/sheogorad | 0 | 4/8 | 13 | 23 | 3 | 14 (25086.0) | 63-81 | 0.59 / 0.50 | 43 | 54 |
| morrowind/deshaan | 5 | 4/8 | 15 | 22 | 3 | 53 (9600.8) | 66-93 | 0.97 / 0.41 | 35 | 54 |
| morrowind/grazelands | 2 | 3/7 | 13 | 21 | 3 | 51 (25685.8) | 66-219 | 0.90 / 0.28 | 63 | 55 |
| morrowind/solstheim | 1 | 4/8 | 9 | 12 | 3 | 53 (54310.4) | 64-190 | 0.04 / 0.43 | 65 | 55 |
| cyrodiil/jerall_mountains | 9 | 2/8 | 25 | 20 | 1 | 145 (15230.9) | 146-300 | 0.10 / 0.60 | 62 | 57 |
| high_rock/wayrest | 1 | 3/9 | 24 | 17 | 4 | 24 (15625.0) | 65-109 | 0.86 / 0.54 | 53 | 58 |
| skyrim/eastmarch | 2 | 6/11 | 15 | 16 | 0 | 43 (14780.8) | 66-220 | 0.37 / 0.36 | 52 | 58 |
| cyrodiil/heartlands | 7 | 3/9 | 29 | 22 | 1 | 108 (13958.3) | 58-104 | 0.78 / 0.58 | 64 | 58 |
| cyrodiil/nibenay_valley | 8 | 3/10 | 28 | 18 | 1 | 140 (17143.4) | 67-82 | 0.97 / 0.58 | 56 | 59 |
| skyrim/whiterun | 3 | 5/12 | 17 | 20 | 0 | 49 (16529.0) | 90-171 | 0.34 / 0.38 | 61 | 60 |
| high_rock/wrothgar | 10 | 3/7 | 12 | 14 | 4 | 184 (17057.9) | 96-325 | 0.18 / 0.56 | 62 | 60 |
| morrowind/west_gash | 1 | 4/7 | 13 | 21 | 3 | 43 (35198.8) | 89-260 | 0.83 / 0.29 | 72 | 62 |
| valenwood/reapers_march | 6 | 4/9 | 21 | 18 | 3 | 103 (17223.6) | 85-108 | 1.03 / 0.54 | 50 | 62 |
| cyrodiil/blackwood | 4 | 4/10 | 27 | 20 | 1 | 101 (22596.3) | 63-69 | 0.96 / 0.79 | 53 | 63 |
| morrowind/vvardenfell_ashlands | 2 | 4/7 | 11 | 19 | 3 | 101 (39755.3) | 66-267 | 0.88 / 0.18 | 62 | 66 |
| cyrodiil/colovian_highlands | 9 | 4/11 | 24 | 17 | 1 | 147 (15771.8) | 106-169 | 0.63 / 0.44 | 62 | 66 |
| morrowind/ascadian_isles | 1 | 4/6 | 12 | 22 | 3 | 76 (43581.2) | 65-289 | 0.97 / 0.65 | 76 | 67 |
| skyrim/the_rift | 4 | 5/10 | 26 | 21 | 0 | 55 (13190.3) | 140-311 | 0.42 / 0.52 | 68 | 68 |
| morrowind/stonefalls | 5 | 7/10 | 15 | 24 | 3 | 56 (10780.1) | 65-309 | 0.81 / 0.27 | 67 | 73 |
| cyrodiil/nibenay_basin | 8 | 5/10 | 26 | 22 | 1 | 139 (16405.9) | 83-216 | 0.78 / 0.67 | 76 | 77 |

Biome sets, tree and flora palettes per region are in `tools/qa/out/regions/audit.json` (fields `dist`, `biomes`, `trees`, `plants`, `struct_specific`, `place_types`); `dist` is the share of each biome over the region's land.

## 2. Weakest 15 regions (before this pass)

| # | Region | variety | why | after |
|---|---|---:|---|---:|
| 1 | hammerfell/craglorn | 18 | one effective biome (craglorn 100%); only 4 flora species | 41 |
| 2 | hammerfell/stros_mkai | 22 | two effective biomes (stros_mkai_coast 67%, sunken_isles 33%); flat (12 blocks p5-p95) | 36 |
| 3 | hammerfell/dragontail | 23 | one effective biome (dragontail_mountains 100%); only 3 flora species; only 5 tree species | 40 |
| 4 | skyrim/the_pale | 23 | two effective biomes (pale_snowfield 60%, snowy_pine_forest 39%); 0 culture-specific structure kinds; only 5 flora species | 47 |
| 5 | black_marsh/lilmoth | 24 | two effective biomes (mangrove_delta 60%, black_marsh_swamp 40%); flat (3 blocks p5-p95); 1 culture-specific structure kinds | 32 |
| 6 | black_marsh/gideon | 26 | two effective biomes (black_marsh_swamp 80%, deep_mire 20%); flat (2 blocks p5-p95); 1 culture-specific structure kinds | 34 |
| 7 | skyrim/winterhold | 26 | 0 culture-specific structure kinds; only 5 flora species; only 4 tree species | 31 |
| 8 | hammerfell/alikr_desert | 29 | two effective biomes (alikr_desert 66%, alikr_dunes 33%); only 3 flora species; only 5 tree species | 41 |
| 9 | black_marsh/stormhold | 30 | two effective biomes (black_marsh_swamp 60%, hist_grove 40%); flat (5 blocks p5-p95); 1 culture-specific structure kinds | 38 |
| 10 | hammerfell/bangkorai | 32 | one effective biome (bangkorai 99%) | 38 |
| 11 | elsweyr/senchal | 32 | two effective biomes (sugar_fields 50%, pellitine_jungle 50%); flat (19 blocks p5-p95); 1 culture-specific structure kinds | 33 |
| 12 | black_marsh/shadowfen | 33 | two effective biomes (shadowfen 80%, deep_mire 20%); flat (15 blocks p5-p95); 1 culture-specific structure kinds | 42 |
| 13 | elsweyr/pellitine | 34 | two effective biomes (pellitine_jungle 80%, sugar_fields 20%); flat (16 blocks p5-p95); 1 culture-specific structure kinds | 35 |
| 14 | elsweyr/rimmen | 35 | 1 culture-specific structure kinds | 46 |
| 15 | morrowind/deshaan | 35 | two effective biomes (deshaan_plains 80%, stonefalls 20%); flat (26 blocks p5-p95) | 54 |

Weakest after the fixes: elsweyr/torval (30), hammerfell/hews_bane (31), skyrim/winterhold (31), black_marsh/helstrom (32), black_marsh/lilmoth (32), valenwood/malabal_tor (32), morrowind/red_mountain (33), elsweyr/senchal (33).

## 3. Worst 15 borders (before this pass)

`seam` = jump (palette contrast x4, border mean temperature and humidity difference, height step, roughness and wetness step) scaled up for straight borders, down when the two sides share a biome (ecotone), and for short borders. `gap` = expected climate contrast between two random land points either side, 0..1. Coordinates are block x, z (Whiterun = 0,0) of the middle of the straightest stretch.

| # | Border (A / B) | at (x, z) | length | straight | gap | dT | height step | seam before | seam after | cause |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | morrowind/molag_amur / black_marsh/shadowfen | 8496, 4256 | 2112 | 0.68 | 0.98 | 0.36 | 6 | 9.8 | 8.4 | no shared biome, humidity jump 0.86, 68% straight |
| 2 | cyrodiil/nibenay_valley / elsweyr/anequina | -128, 9200 | 1952 | 0.57 | 0.99 | 0.95 | 5 | 9.4 | 4.4 | no shared biome, temperature jump 0.9, humidity jump 0.72 |
| 3 | hammerfell/bangkorai / hammerfell/dragontail | -5280, 5104 | 1472 | 0.80 | 0.97 | 0.90 | 8 | 9.2 | 1.7 | no shared biome, temperature jump 0.9, humidity jump 0.40, 80% straight |
| 4 | skyrim/eastmarch / morrowind/stonefalls | 3104, -208 | 1760 | 0.82 | 0.81 | 0.94 | 20 | 9.2 | 3.3 | no shared biome, temperature jump 0.9, 82% straight, height step 20 |
| 5 | cyrodiil/jerall_mountains / morrowind/stonefalls | 3816, 2360 | 1536 | 0.50 | 1.00 | 1.71 | 13 | 9.1 | 1.4 | no shared biome, temperature jump 1.7, humidity jump 0.58, height step 13, desert<->snow |
| 6 | cyrodiil/blackwood / elsweyr/rimmen | 2968, 9960 | 1280 | 0.75 | 0.90 | 0.57 | 4 | 9.1 | 4.4 | no shared biome, humidity jump 0.66, 75% straight |
| 7 | elsweyr/anequina / elsweyr/pellitine | -312, 11560 | 2144 | 0.57 | 0.99 | 0.78 | 6 | 8.8 | 3.4 | no shared biome, humidity jump 0.84 |
| 8 | hammerfell/craglorn / high_rock/wrothgar | -3984, 2240 | 1472 | 0.46 | 1.00 | 1.60 | 13 | 8.8 | 3.7 | no shared biome, temperature jump 1.6, humidity jump 0.50, height step 13, desert<->snow |
| 9 | cyrodiil/nibenay_valley / elsweyr/rimmen | 1568, 9488 | 2624 | 0.87 | 0.86 | 0.63 | 4 | 8.6 | 1.6 | no shared biome, humidity jump 0.51, 87% straight |
| 10 | elsweyr/anequina / valenwood/reapers_march | -1712, 10784 | 3488 | 0.58 | 0.99 | 0.90 | 3 | 8.5 | 3.1 | no shared biome, temperature jump 0.9, humidity jump 0.62 |
| 11 | hammerfell/alikr_desert / hammerfell/bangkorai | -5952, 4912 | 1824 | 0.37 | 0.99 | 1.32 | 14 | 8.1 | 3.5 | no shared biome, temperature jump 1.3, humidity jump 0.48, height step 14 |
| 12 | hammerfell/craglorn / high_rock/wayrest | -4576, 2864 | 1472 | 0.46 | 1.00 | 0.96 | 6 | 8.0 | 3.1 | no shared biome, temperature jump 1.0, humidity jump 0.51 |
| 13 | skyrim/whiterun / skyrim/the_pale | 464, -384 | 1632 | 0.80 | 0.83 | 0.88 | 4 | 7.9 | 3.2 | no shared biome, temperature jump 0.9, 80% straight |
| 14 | hammerfell/craglorn / cyrodiil/colovian_highlands | -3680, 3920 | 2560 | 0.54 | 0.98 | 1.17 | 5 | 7.8 | 3.4 | no shared biome, temperature jump 1.2, humidity jump 0.37 |
| 15 | cyrodiil/nibenay_basin / morrowind/stonefalls | 4368, 3200 | 1664 | 0.33 | 0.94 | 1.34 | 23 | 7.7 | 4.0 | no shared biome, temperature jump 1.3, humidity jump 0.47, height step 23, desert<->snow |

### Specific adjacency checks

* **desert<->snow** (desert/hot-dry beside snow or tundra): before cyrodiil/jerall_mountains | morrowind/stonefalls at 3816, 2360 (dT 1.7); hammerfell/craglorn | high_rock/wrothgar at -3984, 2240 (dT 1.6); cyrodiil/nibenay_basin | morrowind/stonefalls at 4368, 3200 (dT 1.3); after none.
* **jungle<->tundra** (jungle beside tundra or snow): before none; after none.
* **swamp<->mountain** (swamp beside mountains with no foothills): before none; after none.

The desert/snow cases are the Jerall/Red Mountain ridge line (snowy peaks against volcanic ash peaks, Nibenay basin and Stonefalls) and Craglorn against Wrothgar (hot badlands against a cold highland). The jungle/tundra and swamp/mountain checks (swamp family against a region with 30% of its land above 150 blocks or a ridge strength of 70+, with a height step over 20) found no direct contacts before or after; Hjaalmarch's marsh and Black Marsh both meet higher ground through their own lowland tundra/foothill palettes.

## 4. Renders and visual score

All in `tools/qa/out/regions/` (gitignored): `biome_map.png` and `region_map.png` (1 px = 32 blocks), `seams.png` (the 15 worst borders of the pre-fix audit: red = top 5, orange = 6-10, yellow = 11-15) and `seams_remaining.png` (worst 15 after), `transect_NN_<a>__<b>_{before,after}.png` (2400x400 blocks, three panels: biome colours, height, vegetation class), `pair_NN.png` (before and after side by side, assignment shuffled, key in `transects_key.json`) and `transects_sheet.png` (contact sheet of all eight pairs). The renders come from a numpy port of the biome source's palette walk (`region_render.py`), not from generated chunks.

One Haiku reviewer scored each strip blind (1-5 for natural-looking transition, not told which side was the fix):

| Transect | Border | before | after | note before | note after |
|---|---|---:|---:|---|---|
| T1 | morrowind/molag_amur / black_marsh/shadowfen | 2 | 3 | hard tan-to-green jump at the border, thin strip | olive patches on tan; a fairly straight edge remains |
| T2 | cyrodiil/nibenay_valley / elsweyr/anequina | 2 | 4 | flat tan block with a hard cut at the border | close olive/tan palette, speckled border |
| T3 | hammerfell/bangkorai / hammerfell/dragontail | 1 | 4 | perfectly straight vertical seam | feathered diagonal green band, some stepping |
| T4 | skyrim/eastmarch / morrowind/stonefalls | 3 | 3 | teal block, hard cut at the line | dark green stripe, straight-ish edge near the line |
| T5 | cyrodiil/jerall_mountains / morrowind/stonefalls | 2 | 3 | brown block cut sharply at the border | flat teal-grey area spans the line, uniform |
| T6 | cyrodiil/blackwood / elsweyr/rimmen | 3 | 3 | large flat tan area on the Elsweyr side | olive/yellow bands near the line, moderately organic |
| T7 | elsweyr/anequina / elsweyr/pellitine | 1 | 3 | straight tan-to-green seam | curved olive/tan bands with a rounded blob |
| T8 | hammerfell/craglorn / high_rock/wrothgar | 2 | 4 | flat olive block, hard cut at the line | organic feathered edge, tan patches cross the line |

Mean: before 2.0, after 3.4.

## 5. Fixes made in this pass

1. **Ecotone palette additions** (`tools/worldgen/ecotones.py` writes `tools/worldgen/ecotones.json`; `apply_ecotones()` in `tools/worldgen/biomes.py` applies it). 37 region palettes get extra surface biomes with a geographic `bias`: `linear` rows grow the extra biomes in the last ~40% of the way to the shared border (the normal of that border, from the raster), `radial` rows grow them on the region's rim, `none` rows add variety everywhere. Border pairs covered: Hammerfell desert/Bangkorai/Dragontail/Craglorn, Craglorn-Wrothgar-Wayrest-Colovian, Molag Amur-Shadowfen-Deshaan-Telvannis, Eastmarch/Rift/Stonefalls/Jerall/Nibenay basin, Nibenay valley/Blackwood/Gold Coast-Rimmen/Anequina/Pellitine-Reaper's March, Whiterun-Pale-Winterhold, Sheogorad-Vvardenfell. Stonefalls/Eastmarch also get peak-context extras so the snowy ridge meets the ash peaks in patches.
2. **Five new ecotone biomes**, composed from existing tree layers, ground cover, rocks and surfaces (no new species or textures): `warm_steppe` (hot desert to temperate highland and Elsweyr to Cyrodiil), `fen_edge` and `reedlands` (wooded and reedy fen grading Black Marsh into its neighbours, also variety for Lilmoth, Gideon, Murkmire), `ash_heath` (Skyrim tundra to Morrowind ash, grass with ash patches), `pale_heath` (snowy heath between Whiterun tundra and the Pale snowfield, also Winterhold). Files: new `biome/*.json`, `configured_feature/biome/flora/trees_*.json`, tag lists, lang, `palettes.json`, `noise_settings/tamriel.json` (all generator output).
3. **Weak-region variety**: Craglorn (+ warm steppe, Bangkorai, badlands), Dragontail, Alik'r, Stros M'Kai (+ Pellitine jungle, savanna), Black Marsh (Lilmoth, Gideon, Murkmire, Stormhold, Shadowfen), Pale, Winterhold, Artaeum, Auridon, Greenshade, Senchal.
4. **Seam feathering in `TamrielBiomeSource.column()`** (the only Java change, 4 lines): the region lookup warp gets a mid-frequency octave (amplitude 24 blocks at 40-block scale) and the per-quart jitter grows from +-7.5 to +-22 blocks, so palettes interleave over roughly 50 blocks instead of meeting on a line. Spawn and structure region lookups are unaffected (they use `TamrielMap.region` directly). `compileJava` passes.

## 6. Ranked fix list (what is left)

Worst borders still standing after the pass:

* morrowind/molag_amur / black_marsh/shadowfen at 8496, 4256: seam 8.4, gap 0.85, dT 0.16, 68% straight.
* cyrodiil/colovian_highlands / cyrodiil/great_forest at -480, 5424: seam 5.7, gap 0.58, dT 0.18, 86% straight.
* hammerfell/craglorn / cyrodiil/jerall_mountains at -3552, 2384: seam 4.5, gap 0.85, dT 1.09, 19% straight.
* hammerfell/dragontail / hammerfell/hews_bane at -5608, 7368: seam 4.4, gap 0.52, dT 0.36, 62% straight.
* cyrodiil/blackwood / elsweyr/rimmen at 2968, 9960: seam 4.4, gap 0.63, dT 0.22, 75% straight.
* cyrodiil/nibenay_valley / elsweyr/anequina at -128, 9200: seam 4.4, gap 0.69, dT 0.60, 57% straight.
* elsweyr/rimmen / elsweyr/torval at 1408, 10880: seam 4.3, gap 0.66, dT 0.40, 84% straight.
* elsweyr/rimmen / elsweyr/senchal at 2976, 11424: seam 4.3, gap 0.80, dT 0.50, 56% straight.
* morrowind/deshaan / morrowind/molag_amur at 7872, 3280: seam 4.2, gap 0.52, dT 0.06, 58% straight.
* skyrim/haafingar / skyrim/the_reach at -2464, -1632: seam 4.0, gap 0.48, dT 0.33, 53% straight.

1. **Real palette blending in the biome source** (Java, `TamrielBiomeSource.column()`): sample the region at four points on a 64-block cross and pick the palette by a smooth weight (`TamrielMap` already has the coast/ridge/river distance fields, so the weight can follow geography: favour the side whose terrain context matches). The feather added here is a cheap stand-in; this gives true graded ecotones and removes the last straight lines.
2. **Borders that follow geography**: move `regions` polygons in `tamriel_vectors.json` so Molag Amur/Shadowfen/Deshaan and Hammerfell/Cyrodiil borders follow a ridge or river (`features.png` ridge/river channels show where). Run `tools/worldgen/tamriel_map.py`, then `tools/worldgen/ecotones.py`, `biomes.py`, `noise_settings.py`.
3. **Per-biome tree layers for the hard ridge borders** (Jerall-Stonefalls, Craglorn-Jerall, Dragontail-Gold Coast): peak/summit contexts of the two sides still differ outright; add a `snowy ash scree` peak biome and put it in both palettes' `peak` lists.
4. **Second ecotone chain for multi-neighbour regions**: a palette has one `linear` bias; Colovian Highlands (Craglorn/Dragontail and Great Forest), Anequina and Jerall border several unlike neighbours. Support a list of biases per palette in `Palettes.java` (`bias` is parsed in `compileOne`).
5. **Structure variety** is province-bound only (`where`): Skyrim holds, Cyrodiil counties and most of Morrowind have 0-1 culture-specific dungeon kinds beyond the 126 generic kinds. Other agents own `dungeons/**`; per-region weighting of existing kinds (not new art) in `structure_set/*.json` would give the weakest regions (Pale, Winterhold, Gideon, Lilmoth) a signature.
6. **Flat regions** (Black Marsh p5-p95 under 5 blocks, Greenshade, Senchal): terrain amplitude in `tamriel_vectors.json`/`tamriel_map.py` region overrides (low hummocks, tidal channels) would add the elevation variety no palette can.

## 7. Files changed in the pass

* New tools: `tools/qa/region_audit.py`, `tools/qa/region_render.py`, `tools/worldgen/ecotones.py` (+ generated `tools/worldgen/ecotones.json`).
* Generator edit: `tools/worldgen/biomes.py` (5 biomes, 5 tree layers, flora entries, `apply_ecotones()`).
* Generator output (do not hand-edit; regenerate): `biome/{warm_steppe,fen_edge,reedlands,ash_heath,pale_heath}.json`, `configured_feature/biome/flora/trees_*` and `placed_feature/biome/flora/trees_*` for the five, biome tag lists under `data/{c,forge,minecraft,skycraft}/tags/worldgen/biome`, `lang/skycraft/tamriel_biomes.json`, `skycraft/tamriel/palettes.json`, `data/skycraft/worldgen/noise_settings/tamriel.json`, `tools/worldgen/generated/surface_rule.json`.
* Java: `TamrielBiomeSource.column()` warp (4 lines).

## 8. How to re-run

```
py -3 -I tools/worldgen/ecotones.py          # geometry -> tools/worldgen/ecotones.json
py -3 -I tools/worldgen/biomes.py            # applies ecotones, writes biomes/palettes/tags/lang
py -3 -I tools/worldgen/noise_settings.py    # surface rule
py -3 -I tools/qa/region_audit.py            # tables + worst borders
py -3 -I tools/qa/region_render.py maps      # biome/region/seam maps
py -3 -I tools/qa/region_render.py transects [--before-root DIR]
```
