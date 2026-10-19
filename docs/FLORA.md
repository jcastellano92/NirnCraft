# Flora: the trees and plants of Nirn

NirnCraft grows its own vegetation. Every tree is built procedurally from a species definition, so no two trees are
the same. Trees, shrubs, ground cover and rocks are spaced by world-wide noise fields, not per-chunk random patches.
The result is groves, clearings, forest edges, meadows and rock fields that run across chunk borders without
repeating.

The work answers the owner's playtest feedback:

* "Tree variations are strange, weird shapes." Vanilla tree placers are gone from the Nirn biomes. Each tree now
  has a natural silhouette for its region.
* "Plants have repeating patterns." The uniform `random_patch` sprinkles are gone. Ground cover follows noise.

## How it works

### Java: `com.nirncraft.tamriel.biome.flora`

| Class | Registered as | What it does |
| --- | --- | --- |
| `FloraTreeFeature` | feature `nirncraft:flora_tree` | Grows one tree. The species comes from a weighted list. `stands` makes the weights follow a slow noise per species, so species gather in stands (a knot of aspens in a pine wood) instead of a salt-and-pepper mix. |
| `TreeSpecies` | (data in code) | About 70 species. Each has a growth form, its wood, foliage and accent foliage, and its proportions. |
| `TreeShapes` | | The growth forms (see below). Every proportion is sampled per tree. |
| `TreeBuilder` | | Collects one tree's blocks and writes them in one go, through `GenBox`. Logs are oriented along their branch. Thin branches are face-connected chains. Leaves get their real decay distance (breadth-first from the logs), so felled trees drop their crowns like vanilla ones; leaves too far for vanilla's rule become persistent. It also handles hanging vines, willow curtains and waterlogging. |
| `ScatterPlacement` | placement `nirncraft:flora_scatter` | Natural spacing for trees. It replaces `count + in_square`. The points are a world-wide jittered grid of cell size `spacing`, thinned by priority so no two kept points are closer than `spacing × min_distance` (blue noise). Each point lives with probability `floor + (density − floor) × field`. Points depend only on the seed and position, so neighbouring chunks agree. |
| `DensityFilter` | placement `nirncraft:flora_density` | Keeps a position with probability `floor + (density − floor) × field`. Used after `count + in_square` for ground cover and rocks. |
| `FloraField` | (codec) | A density field. `fbm` is rotated fractal value noise: groves and clearings. `worley` is cellular: tufts, rings and rock fields. It has `scale`, `coverage` and `softness`. `mode` is `inside` (forest), `outside` (clearing meadows), `edge` (forest-edge shrubs) or `any`. Layers that share a salt and shape describe the same forest. |
| `GroundCoverFeature` | feature `nirncraft:ground_cover` | One tuft of `cluster` plants within `spread` blocks. The plant is picked by a slow noise per entry, sharpened by `patchiness`, so each plant keeps to its own drifts. It handles double plants. `near_water` keeps reeds to the shore. |
| `RockFeature` | feature `nirncraft:flora_rock` | Rocks that each look different. Each is an ellipsoid with its own three radii, yaw and tilt, warped by noise and half sunk into the ground, with its columns filled down so nothing floats. Moss and lichen sit on the upper faces. Styles: `boulder`, `slab`, `standing`, `rubble`, `mixed`. |
| `FloraNoise` | | Allocation-free hash, value, fbm and Worley noise. |

**Chunk safety.**

* Every write goes through `GenBox`, so nothing leaves the 3×3-chunk decoration area.
* Tree origins always lie in the decorated chunk, and crowns stay within 16 blocks of it.
* During decoration only the decorated chunk's heightmaps are primed. Ground lookups in the neighbouring chunks
  (fallen logs, knees, bamboo, rubble) scan the blocks instead.

**Determinism.** Positions are pure functions of the world seed. Shapes come from the decoration random, which
vanilla seeds per chunk and feature.

### Growth forms

| Form | Species | Shape |
| --- | --- | --- |
| `CONIFER` | Skyrim pine and spruce, giant pine, snowy variants, saplings, swamp pine, highland pine, West Gash umbrella pine, Solstheim pine | A gently leaning trunk. Below the crown, a bare trunk with dead stubs. The crown is in tiers of drooping boughs, each lobed by four to six boughs with a ragged rim that droops. Tiers narrow to a spire, and some are missing (gaps). Snowy species frost the top surfaces of each tier. Swamp pines stand on exposed root arches. Umbrella pines keep their few, wide tiers at the top. |
| `BROADLEAF` | oak, great oak, dark oak, elm, beech, maple, olive, fruit tree, hawthorn, swamp oak, Ascadian bloom and oak, swamp Hist, young Graht-oak, cherry, plum, Summerset bloom and gold | A trunk to the fork. Then three to six main limbs whose pitch comes from `spread` (steep elms, wide oaks), each with side shoots ending in leaf clumps. A leader clump sits on top, with a fill clump so the crown isn't hollow. Some limbs are dead and bare. Big trunks get flared feet and roots. Windswept species (hawthorn) fold their limbs downwind; the wind direction is a slow world noise, so a moor's trees all lean the same way. |
| `ASPEN` | Rift gold and orange aspens (young and mature), birch | A slender white trunk, sometimes twinned, with a narrow crown of small clumps stacked up its top half. Clumps mix gold and orange. |
| `WILLOW` | willow | A stout trunk, arching limbs, a broad dome and curtains of hanging leaves. |
| `COLUMNAR` | Gold Coast cypress | A slim dark flame. |
| `BALD_CYPRESS` | Black Marsh bald cypress | A buttressed foot in the water ringed by knees, a tall trunk, two or three flat layered pads, and hanging moss (vines). |
| `MANGROVE` | mangrove, Bitter Coast swamp tree | The trunk stands above the water on arching prop roots (mangrove roots, waterlogged in water), under a low, dense crown. |
| `PALM` | palm, date palm | A trunk curving away from upright. Seven to ten fronds (more on date palms), each at its own angle, arching out and drooping, with leaflets either side. |
| `ACACIA` | acacia, Grazelands tree | A short trunk forking into long angled limbs, each holding a wide, flat, thin canopy. |
| `BAOBAB` | baobab | A bottle trunk with a sparse crown of stubby branches and small tufts. |
| `JUNGLE` | jungle tree, jungle giant | Buttress roots, a tall trunk with side boughs, and an emergent umbrella of big flat clumps. Vines hang from the crown and climb the trunk, and cocoa grows on the bark. |
| `TWISTED` | juniper, Reach crag tree, tundra tree, ash tree, Akaviri pine | A short, gnarled trunk that bends hard and corrects (an S-curve), with near-horizontal limbs ending in flat pads, like a windswept bonsai on rock. |
| `SHRUB` | tundra shrub, berry, forest, conifer and autumn bushes, desert shrub, jungle bush | A stub of wood under one to three ragged blobs. |
| `DEAD` | dead pine, frozen pine (Atmora), dead oak and birch, charred tree (ashlands), bleached tree (deserts), marsh snag | Leafless trunks with crooked bare limbs and weathered patches. Dead conifers keep irregular whorls of short stubs. Frozen pines keep a few clumps of frosted needles. A quarter have snapped, with the top lying beside them. |
| `FALLEN_LOG` | spruce, oak, birch, dark oak, jungle, acacia, mangrove, Graht-oak (double width), Hist, charred | A trunk lying in any direction, following the ground. Moss carpet and mushrooms grow on its back. The foot has either a root plate torn out of the ground or a stump. |
| `STUMP` | the same woods | A short trunk with root nubs, and moss or a mushroom on top. |
| `BAMBOO` | bamboo grove (Akavir, Pellitine) | A clump of stalks, tallest in the middle, with proper leafy tops. |
| `CRYSTAL` | Summerset crystal tree | A calcite trunk branching into limbs of glowing crystal tipped with clusters. |

The giant features keep their own classes and now vary per tree:

* Graht-oak (`GrahtOakFeature`): trunk spiral, branch rise, crown sizes.
* Hist (`HistTreeFeature`): coil width and pitch, bough count, canopy size.
* Emperor parasol (`GiantMushroomFeature`): an S-bent stem. The cap is flat with a drooping rim, domed, funnelled,
  or has a second, smaller cap lower on the stem.

**Endless lands.** The realms beyond Tamriel reuse Tamriel's biomes. Each generated realm (`realm/...` regions)
gets one foliage cast for the whole realm: gold, flame, blossom, pale, or unchanged. A far continent's oak woods
therefore look foreign.

### Data: `tools/worldgen/biomes.py`

The flora section sits after the old vegetation section.

* `field(...)` defines the shared fields:
  * `F_FOREST_DENSE`, `F_FOREST`, `F_WOODLAND` and `F_SCATTER` for forest cover from closed to lone trees;
  * `F_MARSH` and `F_OASIS` for wetlands and oases;
  * `F_TUFT`, `F_MEADOW`, `F_DRIFT`, `F_RING` and `F_ROCKS` for ground cover and rocks.
* `forest(name, [(species, weight)], spacing, density, field, floor, stands)` is a tree layer. Biomes stack a
  canopy layer, an understory layer (saplings and bushes in `edge` mode, so they gather at forest edges and in
  glades) and a deadwood layer (fallen logs, stumps, snags).
* `cover(name, entries, n, field, ...)` is ground cover. `flowers(...)` is a meadow drift with high patchiness.
* `rocks(name, stone, size, n, moss, moss_chance, style, field)` places rocks in the `local_modifications` step.
* `FLORA[biome]` lists each land biome's vegetation. `apply_flora()` (called first in `main()`) removes the
  biome's older vegetation entries (everything defined in the old vegetation section except cave flora, the old
  boulders, and a few vanilla patches) and adds the table's. The biome catalogue keeps its terrain features
  untouched.
* The old vegetation features stay defined, so anything else that names them still works.

**Feature order.** Every feature still joins `MINE[step]` when it is defined, and biome lists are sorted by the one
canonical list per step, so feature-order cycles stay impossible. Tree layers are defined before ground cover, so
trees grow first and ground cover fills in under them (it uses the `MOTION_BLOCKING_NO_LEAVES` heightmap).

Regenerate with `py tools/worldgen/biomes.py` (or `python3`).

## Region by region

| Region | Canopy | Understory and deadwood | Ground |
| --- | --- | --- | --- |
| Whiterun tundra and plains | Lone tundra shrubs, junipers, wind-bent tundra trees, a few pines and oaks (scatter field) | | Grass tufts and bare patches (Worley), drifts of tundra cotton, blue mountain flower and daisies, snowberries, lichen-spotted tundra rocks |
| Pine forest, Jerall foothills | Skyrim pines, spruces and the odd giant pine, with glades | Saplings and bushes at the edges; fallen spruce, stumps, dead pines | Fern banks, moss drifts, mushroom rings, mossy boulders |
| Falkreath | Dense mixed conifer (pine, giant pine, spruce) with dark oaks and birches | Saplings, bushes, many fallen logs and stumps | Ferns, dense moss, mushrooms, nightshade, lily of the valley in the glades |
| The Rift | Gold and orange aspens in stands among pines and maples | Autumn bushes; fallen birch | Ferns and grass, clearing meadows of poppy, tulip and lavender |
| The Reach | Twisted crag trees and junipers on the rock, highland pines | | Granite boulder fields, leaning standing stones, dry grass |
| Hjaalmarch | Swamp pines on exposed roots, snags, dead oaks | Fallen spruce | Reeds on the shores, marsh grass, cold marsh flowers, lily pads |
| The Pale, Winterhold, peaks | Snowy pines in copses, frozen dead pines | | Ice rocks, snowberries |
| Eastmarch | Junipers, tundra shrubs, dead pines | | Tundra grass, calcite boulders at the hot springs, reeds |
| Solstheim | Charred trees on the ash; snow-dusted pines in the north | Deadwood | Ash grass, ash drifts |
| Cyrodiil: Heartlands, Nibenay | Groves of oak and elm with fruit trees, birches, cypresses; willows by the water | | Lush grass, wildflower meadows with sunflowers, sugar cane, pumpkins |
| Great Forest | Great oaks, oaks, dark oaks, beeches, elms | Saplings and bushes at the glades; fallen oaks, stumps | Ferns, moss, mushrooms, nightshade |
| Gold Coast, West Weald | Olives and cypresses in stands; orchards of blossoming fruit trees | | Grape vines in rows of drifts, lavender, golden flowers |
| Blackwood | Swamp oaks, bald cypresses, mangroves, swamp Hist, the odd giant Hist | Snags, fallen mangroves | Reeds, blue orchids, dense lily pads |
| Morrowind: ashlands, Molag Amur, Stonefalls | Charred trees and twisted ash trees, far apart | | Ash grass, scathecraw, ash drifts, volcanic boulders |
| Bitter Coast | Twisted swamp trees on prop roots, hung with moss; emperor parasols | Snags, fallen logs | Reeds, mushrooms, orchids, lily pads |
| Ascadian Isles | Flowering umbrella trees (cherry blossom and blossoming oaks) | | Heather, tulips and peonies, lavender, reeds |
| West Gash, Grazelands, Deshaan | Umbrella pines; acacia-like graze trees; autumn maples | | Tall grass steppe |
| Telvanni | Telvanni towers, emperor parasols (now varied) | | Moss, mushrooms |
| Black Marsh | Bald cypresses, mangroves, swamp Hist, the giant Hist (varied); jungle trees in Murkmire | Snags, fallen mangroves | Reeds, orchids, lily pads, vines |
| Elsweyr | Savanna: acacias, baobabs and shrubs far apart; jungle and palms in Pellitine; bleached trees in the badlands | Jungle bushes, bamboo | Tall savanna grass, moon sugar |
| Valenwood | Graht-oaks (varied), young Graht-oaks, jungle trees and giants with vines and cocoa | Jungle bushes, bamboo; fallen Graht-oak | Ferns, moss, mushrooms |
| Summerset | Elegant blossom and golden trees, cherries, crystal trees on the coast | | Roses, lavender, white flowers, crystal clusters |
| Hammerfell | Date-palm oases (Worley field) in the Alik'r; junipers and baobabs in Craglorn; palms on Stros M'Kai; oaks and pines in Bangkorai | | Desert scrub, sandstone and red rocks |
| High Rock | Wind-bent hawthorns on the moors; oak, beech and elm woods in Stormhaven; dark oaks and snags in Rivenspire; snowy spruce in Wrothgar | Deadwood | Heather, mossy moor boulders |
| Atmora | Frozen dead pines and snowy giants | | Ice rocks |
| Akavir | Bamboo groves in jungle (Tsaesci); cherry, plum and Akaviri pines (Tang Mo); steppe pines (Ka Po' Tun) | | Blossom meadows, steppe grass |
| Endless lands | The biome's own trees, with each realm's foliage cast | | |

## Performance

Measurement: the `features` chunk-status time from Minecraft's JFR profiling. The method:

1. `/jfr start`.
2. Force-load 8 vegetation-heavy areas of 9×9 chunks each (Whiterun, Falkreath, the Rift, Hjaalmarch, the Great
   Forest, Valenwood, Black Marsh and the Ascadian Isles; 1,800 decorated chunks).
3. `/jfr stop`.
4. Read `chunkGen.status[features]` in the summary JSON.

Both runs used the same jar on a fresh world, seed 20261010. The old run swapped in the original biome JSONs.

| Run | `features` average | p50 | p90 | `surface` average (load reference) |
| --- | --- | --- | --- | --- |
| Old vegetation | 11.5 ms | 7.9 ms | 23.0 ms | 26.3 ms |
| New flora | 7.3 ms | 4.9 ms | 14.4 ms | 17.8 ms |

The PC was shared with other agents' servers and clients, so absolute times drift between runs. The vanilla
`surface` and `noise` steps don't involve vegetation, so they serve as a load reference:

* The new run had about 30% less contention (`surface` −32%, `noise` −30%).
* Corrected for that, decoration costs about 6–10% **less** than before.
* An earlier pair, measured before the last shape tweaks, came out the same: `features` 12.2 ms (old) against
  12.4 ms (new) while `noise` was 34% slower in the new run.

Decoration is therefore well inside the budget of "no more than 20% above the old cost". Ground cover got
cheaper: one tuft per try, against `random_patch`'s 32–48 tries per patch. That pays for the heavier trees.

## Screenshots (`docs/screens/flora_*.jpg`)

The client autotest tour (`ClientAutotest`, flags through `JAVA_TOOL_OPTIONS`) took these shots on seed
20261010. It visited 15 spots, each found with `/skybiome` well inside its biome (see "Re-running the tour"). The
composites show two spots side by side.

| File | What it shows |
| --- | --- |
| `flora_01_whiterun_tundra.jpg` | Whiterun tundra: lone pines, junipers, wind-bent tundra trees and shrubs, lichen rocks, flower drifts |
| `flora_02_falkreath.jpg` | Inside Falkreath's forest: tall pines with bare lower trunks and dead stubs, spruces, saplings, fallen logs |
| `flora_03_rift.jpg` | The Rift: stands of gold and orange aspens among pines, meadow drifts |
| `flora_04_reach.jpg` | The Reach: twisted crag trees with flat pads, junipers, highland pines on granite |
| `flora_05_hjaalmarch.jpg` | Hjaalmarch: swamp pines on root arches, broken snags, marsh grass and pools |
| `flora_06_pale.jpg` | The Pale: snow-dusted pines in tiers, snowy saplings, frozen snags |
| `flora_07_great_forest.jpg` | Cyrodiil's Great Forest from above: great oaks, beeches and elms with glades |
| `flora_08_ascadian_bitter_coast.jpg` | Left: the Ascadian Isles' blossom trees and meadows. Right: Bitter Coast swamp trees on prop roots, with emperor parasols |
| `flora_09_black_marsh.jpg` | Black Marsh: bald cypresses with buttressed feet, knees, flat crowns and hanging moss |
| `flora_10_valenwood_savanna.jpg` | Left: Valenwood's Graht-oaks over the jungle. Right: the Elsweyr savanna's acacias and baobabs |
| `flora_11_summerset.jpg` | Summerset: elegant blossom and golden trees |
| `flora_12_alikr_glenumbra.jpg` | Left: palm oases in the Alik'r. Right: Glenumbra's hawthorn moor with heather |

### Re-running the tour

1. Generate a world on a dedicated server (seed 20261010). Copy `run/world` to `run/saves/flora` without its
   `region`, `entities` and `poi` folders, so the chunks generate afresh.
2. Set `pauseOnLostFocus:false` in `run/options.txt`.
3. Run:

   ```
   $env:JAVA_TOOL_OPTIONS="-Xmx4G -Dnirncraft.autotest=true -Dnirncraft.autotest.createflow=true -Dnirncraft.autotest.load=flora -Dnirncraft.autotest.tourWait=500 -Dnirncraft.autotest.tourUp=18 -Dnirncraft.autotest.tourPitch=14 -Dnirncraft.autotest.tour=WhiterunTundra:-256:-192,Falkreath:-731:1058,Rift:2435:1802,ReachCrags:-2910:-255,Hjaalmarch:-1120:-1240,PaleSnowForest:110:-1609,GreatForest:64:5092,Ascadian:7336:1108,BitterCoast:5792:736,BlackMarsh:5608:8364,ElsweyrSavanna:-408:11312,Valenwood:-4192:11236,Summerset:-11392:12936,Alikr:-8392:4964,Glenumbra:-9192:-264"
   ./gradlew runClient
   ```

`tourUp` and `tourPitch` are new. They set the height above the ground and the pitch of each spot's second shot
(the defaults, 70 and 28, keep the old aerial view).

## Known limits and ideas

* Leaf colours come from vanilla and NirnCraft leaf blocks. A few dedicated textures would widen the palette: pine
  needles with a blue cast, red Reach foliage, Ascadian purple blossom, hanging Spanish moss.
* Species are defined in code (`TreeSpecies`). Moving them to a data file would allow tuning without a rebuild.
* Ground cover stays inside the decorated chunk, because only its heightmaps are ready. Clusters near a chunk edge
  are cut by the edge, but drifts continue in the neighbouring chunk from the same noise.
