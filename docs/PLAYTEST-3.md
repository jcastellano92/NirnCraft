# Playtest 3: making Nirn look right (TERRAIN2)

This pass fixes the design issues that Playtest 2 found in the terrain and biomes, and adds the owner's later
requests: a taller world with Skyrim-scale mountains, and natural, eroded landscapes instead of repeating patterns.
Base commit: `d51e72c`.

## How it was tested

* **Server.** `./gradlew runServer` on a fresh world (seed 20261010), driven over RCON by a scratch script (not in
  the repo). Checks used `/skycraft-tamriel sample`, `/skybiome`, `/skybiome census` and
  `/skycraft-tamriel heightmap`. Before/after heightmaps use the same seed, area and step.
* **Client.** The `ClientAutotest` tour mode (world made through the title screen, spectator flights, a ground
  and an aerial shot per spot) at render distance 20. The tour spec gained optional `:up:pitch` fields for the
  aerial shot (`Name:x:z:yaw[:up[:pitch]]`).
* **Map tool.** `tools/worldgen/tamriel_map.py` previews and statistics, compared by eye with the reference relief
  (inspiration only; nothing is sampled from it).
* **Timing.** `/skycraft-tamriel bench` and a new `/skycraft-tamriel chunkbench <x> <z> <n>`, which generates the
  (2n+1)² chunks round a point to full status and reports the wall time per chunk. Base and new builds were timed
  side by side (see Performance).

## Heights (owner request: "push the heights")

The overworld is now `min_y` −64, `height` 704: **the world top is y 639** (dimension type, `noise_settings.py`,
the channel ranges, the biome census scan, the heightmap colours and the surface rules' snow and bare-rock lines all
follow). The terrain noise fills up to y 575 (`noise.height` 640, `TamrielHeight.MAX_SURFACE` 568): nothing stands
higher, and every block the generator fills costs a density interpolation and an aquifer lookup even when it is air.
Above y 575 there is open sky up to the build limit.

| Feature | Height (measured with `/skycraft-tamriel sample` and the map tool) |
| --- | --- |
| Sea level | 63 |
| Whiterun plain, Whiterun city | 100–120 (city 115–121) |
| Hjaalmarch marsh, Eastmarch lowland | 60–80 |
| Falkreath and the Rift highlands | 150–190 (Riften 172, Lake Honrich 170, Lake Ilinalta 132) |
| The Reach plateau | 165–230 (Markarth 189) |
| Jerall, Velothi, Druadach, Wrothgarian, Dragontail ranges | crest lines 370–430 on the raster, summits to about 470 with the seeded crags |
| Passes where roads cross ranges | 180–230 (the Pale Pass 214, Solitude–Markarth 229, the Rift roads 194–196) |
| Mount Anthor | about 313 |
| Red Mountain | rim about 480, caldera floor about 385 |
| **Throat of the World** | **summit 556–560** |
| High Hrothgar's ledge | 432 |
| The 7,000 Steps | from 160 at Ivarstead to 432 at High Hrothgar (y 234 and 304 on the way), then 432 → 552 to the summit |
| **Clouds** | **y 330** (`TamrielSkyEffects.CLOUD_HEIGHT`) |

The clouds sit above every hill, pass and town, so they no longer slice through the Reach (Playtest 2, known issue
5), while the great ranges and the Throat rise through them as in Skyrim.

## Fixes

### 1. Whiterun is golden tundra again

* **Cause.** The climate value that walks a palette's choices mixes the seeded temperature and humidity with a
  fixed low-frequency noise. At the origin that noise sits on a lattice value near its maximum, so the whole
  neighbourhood of Whiterun picked the *last* lowland choice, which was the pine forest (census r96: 410 pine
  forest of 625). That is why three seeds out of three gave pines at (0, 0).
* **Fix.** Whiterun's lowland is now tundra 4 and plains 3, with pines only on the foothills (the upland context,
  y 130+). A new palette `bias` (radial, round the city) keeps the choice on the tundra side near the walls.
* **Result.** Census r160 round (0, 0): 1658 `whiterun_tundra`, 20 highlands, 3 pine (on the hill edge).

### 2. The Throat of the World

* **Cause of the blue rectangles.** The `throat_of_the_world` summit surface laid packed ice over a wide noise band,
  and the `snowy_slopes` biome next to it had none. The border between the two biomes runs along 4×4-block biome
  cells, so the ice showed up as rectangles. The surfaces now share one look (wind-packed snow, powder drifts,
  bare and frosted rock, snow clinging to about half the steep rock) with no ice at all, and ice boulders became
  frost-and-stone boulders.
* **Shape.** A 32-block raster can only hold a dome. The Throat (and Mount Anthor) are now drawn analytically at
  full resolution by `terrain/MapPeaks` from `skycraft/tamriel/peaks.json`: a massif profile
  `(1 − u^1.5)^1.35` whose reach changes with the bearing (six aretes), a summit horn, seeded crags down the
  flanks (fewer at the very top, so the summit is one point), and High Hrothgar's levelled ledge at (511, 457).
* **The 7,000 Steps and the summit path** are levelled ledges: the map tool walks each path every 8 blocks and keeps
  it as close to the ground as it can while it never descends and never climbs more than 0.6 blocks per block.
  Where that means a cut or a fill, the banks widen with its depth (about 1.25:1 talus), so there are no sheer
  walls. The crag detail and cave mouths are suppressed along them. (A first version graded the path evenly and
  cut a slot canyon into the mountain; the tour's aerial shot caught it.)
* The road corridors and town pads (Ivarstead) damp the massif, so the foot stays passable.

### 3. Relief all over Skyrim

* The south was raised as in the game: Falkreath and the Rift are highlands at y 150–190, with the lakes and rivers
  re-levelled to match (Ilinalta 132, Honrich 170, Geir 165, the White River stepping down to the sea, the
  Darkwater falling 165 → 72 into Eastmarch).
* Every hold gets a rolling hill field sized to its character (`hills` and `cliff` terrain keys), plus authored
  ridges between the holds (the Bleak Falls ridge, the Pale highlands, Solstheim's Moesring chain).
* **The Reach:** a high, eroded plateau, the Karth in a narrow canyon, cliff bands on the steep rock.
* **Hjaalmarch:** a maze of channels and pools at sea level between hummocks, filled by the map water feature.
* **Coasts:** cliff coasts with a narrow strand for Haafingar, the Pale, Winterhold and Solstheim.
* **Ranges:** taller, ridged-multifractal crests, and **passes wherever a map road crosses a range**.
* **Buildable towns:** every capital, city, town, village and farm stands on ground levelled to its local mean
  (strength by type), with the seeded detail calmed and cave mouths kept shut there (the road channel now covers
  settlement pads). This also fixed **Stormhold's dry pit**: it was a cave mouth under the anchor (map y 69.5,
  floor 59); the floor is now y 70.

### 4. Biomes per hold

* **The Rift:** the palette's upland threshold was the global y 115, so the whole Rift (y 130+) used its upland mix
  of pines and meadow. Thresholds now follow each hold's ground, and a radial bias round Riften and Lake Honrich
  keeps the golden and orange aspens there. Census r160 round Riften: 1681 of 1681 `rift_autumn_forest`.
* **Solstheim:** a linear bias puts the Skaal pine forests and snow in the north and the ash wastes in the south
  (census: north 1138 pine forest + Moesring's snowy slopes; south 1704 of 1704 ash wastes).
* **Other provinces (census r400):** Vvardenfell ashlands 4949 ashlands + Red Mountain's slopes; Cyrodiil
  heartlands, Nibenay valley and Rumare shore; Stormhold murky river, deep mire and swamp; Anequina canyons,
  badlands and savanna; Grahtwood 4009 Graht-oak forest and rainforest; Summerset meadow and crystal coast; the
  Alik'r desert and dunes; Glenumbra moors. Each province's signature biome dominates.

### 5. Lake islands

* Lake Ilinalta has the Lady Stone's island, Lake Honrich Goldenglow's, and Lake Geir (enlarged southwards) Geirmund
  Hall's (`islands` in `tamriel_vectors.json`). All three places now sample dry land: the Lady Stone at y 144
  (lake 132), Goldenglow at 184 (lake 170), Geirmund's Hall at 177 (lake 165). `tools/landmarks.py` was re-run;
  only `tamriel_shrines.json` changed (shrines re-sited on the new terrain).

### 6. Clouds

`terrain/client/TamrielSkyEffects` registers the overworld's special effects as vanilla's `OverworldEffects` with
the cloud height at **y 330**. The sky type stays NORMAL, so the atmosphere module's Skyrim sky still draws.

### 7. Solstheim, Stormhold and the DUMMY block entity

* **Solstheim** has proper terrain now: cliffs round the north, the Moesring chain and peak (y 265) in the middle,
  undulating ash wastes in the south, Raven Rock on its bay (y 71). The wide sea-level ring is gone. Fort Frostmoth
  and Glacial Cave are still right on the shore (their markers are); they now stand at y 63–73 on a narrow strand
  below the cliffs.
* **Stormhold:** see 3.
* **The DUMMY warning at (851, −46, 5857)** is a vanilla **ancient city** under the Imperial City. Its jigsaw
  pieces were placed into a flooded aquifer; the block entity block was replaced by the aquifer's water, which
  left the block entity's placeholder behind. The chunk dump shows deepslate tiles, candles and sculk sensors
  surrounded by water. This is vanilla behaviour (it happens in vanilla worlds too), so it was not changed. If it
  matters, the fix is to keep `deep_dark` out of the deep-cave palettes under lakes, or to drop ancient cities from
  Tamriel.

### 8. `/skycraft-locate`

`/skycraft-locate <place id or name> [tp]` (permission 2) tab-completes the place ids, accepts an id, an English
name or part of one, and prints the place, its type, region, coordinates and distance. With several matches it
lists up to ten, nearest first. `tp` teleports the player to the ground there, generating the chunk first. For
example, `skycraft-locate lady stone` prints
`The Lady Stone [the_lady_stone, standing_stone, skyrim/falkreath] at (-829, 761), 861 blocks away`.

## Natural shapes and materials (owner feedback: "weird repeating patterns")

Sources found and replaced:

| Source of the pattern | Replacement |
| --- | --- |
| The map tool's value noise, with every octave on the same axis-aligned lattice (blobby squares, aligned ridges) | Gradient noise with quintic fades, each octave rotated and offset (`gnoise`, `gfbm`); a **ridged multifractal** for crests (`gridged`), where fine octaves ride on the crests and valleys stay smooth |
| Bilinear interpolation of the 32-block raster creased every slope along the cell grid | **Catmull-Rom** elevation over 4×4 cells in `MapRaster` (smooth, and it gives the macro slope) |
| The borders' domain warp (96- and 29-block value noise) was also applied to the elevation, rippling steep slopes in a regular lattice | Elevation and slope are sampled unwarped; the warp stays for borders and coasts |
| Cliff terraces repeated as contour lines over every hillside with crag | Cliff bands only where the macro slope is steep, uneven step heights, bands broken and offset along their length |
| Surface-rule noise *bands* (`calcite −0.02..0.02`, `powder_snow 0.45..0.58`, packed-ice strips) drew rings and worm-like stripes | All thin bands become one-sided thresholds (blobs and drifts); wide organic mazes (mud, moss) stay |
| Featureless smooth macro slopes | **Erosion pass** on the raster: D8 run-off routing, stream-power incision (dendritic valleys feeding the rivers, gullies down the flanks), thermal slumping to talus slopes; coasts, lakes and marshes protected |
| Fine detail spread evenly over steep flanks | Derivative-damped ("eroded") detail: high-frequency noise fades with the macro slope, stays on crests, flats and valley floors |
| Uniform grey cliffs | Steep rock now carries scree (gravel), andesite and tuff patches; high peaks keep patchy snow on steep rock |

The seeded detail noises also got another octave each. Trees and plants were not touched (the FLORA workstream).

## Performance

Measured on a PC shared with several other agents' servers and clients, so single numbers swing by ±30%. The
base build (a `git archive` of `d51e72c` with only the chunkbench command added) and the new build were run at the
same time, so both saw the same load.

Chunk generation, `chunkbench` with n = 3 (49 fresh chunks per spot, 18 spots across Skyrim, Cyrodiil, Morrowind,
Valenwood and two generated realms), base vs new, final round:

| Measure | Base (`d51e72c`) | New | Ratio |
| --- | --- | --- | --- |
| Process CPU per chunk, sum over 18 spots | 3452 ms | 3438 ms | **1.00** |
| Wall time per chunk, sum over 18 spots | 2186 ms | 2455 ms | 1.12 |
| Spots (CPU ratio) | | | 0.79–1.47; Whiterun 0.86, the Throat 0.89, Riften 1.07, Hjaalmarch 1.47 |

* A first round, before the two optimisations below, measured **+39% CPU** (and +16% even in the generated realms,
  where the terrain is unchanged), almost all from the taller world:
  * The biome source sampled the whole noise router once per 4×4×4 quart, and a 704-block column has 176 quarts
    instead of 112. The router's climate doesn't vary with y here, and depth is linear in y, so the biome source
    now samples once per quart column and per chunk, and derives each quart's depth.
  * Every block the noise fills, air included, gets a density interpolation and an aquifer lookup. The noise now
    stops at y 575 instead of 639.
* Hjaalmarch is the slowest spot: its channel maze is mostly water (map water and aquifers).
* `TamrielMap.sample`: 154–158 ns before and 171–182 ns after on a quiet machine, about +10% (the Catmull-Rom
  elevation reads 16 cells instead of 4). The two sharp peaks cost one bounding-box test elsewhere.
* `getBaseHeight` (structure placement, Distant Horizons) walks down from the noise top, so it costs about 10–20%
  more than before.

## Screenshots (`docs/screens/`)

| File | What it shows |
| --- | --- |
| `pt3_01_skyrim_heightmap.jpg` | Skyrim before and after: raised southern highlands, the ranges and their passes, the Throat, the Hjaalmarch maze, the eroded drainage. |
| `pt3_02_throat_heightmap.jpg` | The Throat before (a dome) and after (a horn with aretes and gullies, High Hrothgar's ledge, the Steps). |
| `pt3_03_solstheim_heightmap.jpg` | Solstheim before (sea-level ring) and after (cliffs, Moesring, ash wastes). |
| `pt3_04_marsh_reach_heightmap.jpg` | Hjaalmarch's channels and pools, and a 1:1 crop of the Reach: eroded, no lattice or contour-line patterns. |
| `pt3_05_throat.jpg` | The Throat before (blue-ice rectangles) and after, from the north-west. |
| `pt3_06_throat_clouds.jpg` | The south face rising through the clouds at y 330. |
| `pt3_07_high_hrothgar.jpg` | High Hrothgar on its ledge at y 432, the summit massif above. |
| `pt3_08_whiterun.jpg` | Whiterun before (pine forest) and after (golden tundra). |
| `pt3_09_riften.jpg` | Golden and orange aspens round Riften and Lake Honrich. |
| `pt3_10_reach.jpg` | The Reach before (a cloud slab through the crags) and after (the Karth in its canyon). |
| `pt3_11_hjaalmarch.jpg` | Hjaalmarch before (a flat sheet) and after (channels, pools and hummocks). |
| `pt3_12_islands_solstheim.jpg` | The Lady Stone's island in Lake Ilinalta, and Solstheim's ash wastes below the Moesring. |

## Known issues and notes

* The 7,000 Steps waypoints are S2's (`dungeons/world/Sites`); `peaks.json` carries a copy for the graded ledge.
  If S2 moves the path, update `tamriel_vectors.json` too.
* The vanilla ancient city under the Imperial City (the DUMMY warning) is left as vanilla.
* Fort Frostmoth and Glacial Cave stand on the shore by design of their markers.
* Big vanilla cave entrances can still open on open mountainsides (they are kept shut only under roads, towns,
  ledges and paths).
* The map tool now takes about two minutes (the erosion pass).
