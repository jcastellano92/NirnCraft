# The Iliac Bay: High Rock and Hammerfell from Daggerfall's map

Plan for building the Iliac Bay (southern High Rock and northern Hammerfell) from Daggerfall's geography and political
map, at Skyrim quality. Read `docs/world/DESIGN-PRINCIPLES.md` first (sections 0, 1 and 4 are the rulebook this plan
applies), then `docs/TAMRIEL.md`, `docs/WORLDGEN-PIPELINE.md` and `docs/MARQUEE.md`.

Status: plan, for execution in phases IB1-IB8 (section 11). Nothing in the mod has changed yet.

**Legal line.** Daggerfall's data is reference for facts only: names, relative positions, political borders, climate
zones, settlement sizes and building-type counts, road topology. The raw extraction lives in
`.reference/daggerfall_ref/v2/` (gitignored; its `README.md` lists every file and how to rebuild it). This document
and everything committed holds only facts, numbers, coordinates, designs and our own words. No raster, model, texture,
sound or text from the game enters the repo, raw or resampled. Terrain is hand-traced vectors with heights we choose
(AGENT-RULES legal rule). The Basic Roads path data shipped inside DFO Online's `vendor/` folder is permission-only for
that project. We do not read it into Skycraft; we only compared aggregate numbers against it (section 7).

---

## 0. Decisions for the owner (short)

1. **Scale: 6.22 blocks per Daggerfall map pixel**, so one block stands for about 132 m of Daggerfall's ground. This
   keeps Daggerfall and Wayrest exactly on their binding anchors. It also preserves every distance between two cities
   on the same shore of the bay (section 2).
2. **Open the bay like a wedge.** The Hammerfell shore turns 23.3 degrees about the land bridge east of Wayrest. That
   puts Sentinel back on its binding z (it moves 224 blocks east). Daggerfall's own bay is too narrow for the lore map.
3. **Move the Daggerfall-era towns to their Daggerfall positions.** Today 37 of them sit in scrambled places (for
   example Totambu is 3,800 blocks from where Daggerfall puts it). Camlorn, Shornhelm, Evermor, Northpoint, Farrun,
   Jehanna, Orsinium, Elinhir, Skaven, Rihad, Taneth, Gilane, Hegathe and Stros M'Kai lie outside Daggerfall's map.
   They stay where they are, with two small fixes (section 2.4).
4. **Two levels of politics.** Nine realms become Skycraft regions (six of them existing ids). Daggerfall's 44
   political regions become **fiefs** inside them, each with a seat, a lord and a banner. The template's
   fixed-per-region content (storyline, marquee, open dungeon) goes to realms, not fiefs (section 4).
5. **Curate, do not import.** Daggerfall has 15,251 locations in this window. We take about 176 (1.2%). That gives
   14.2 places per km², which matches Skyrim's 14.3 (section 5).

---

## 1. What the extraction offers

Sources:

* The owner's install: `ARENA2` (MAPS.BSA, BLOCKS.BSA, WOODS.WLD, CLIMATE.PAK, POLITIC.PAK, FACTION.TXT via v1).
* DFO Online (MIT, `F:\daggerfall-js-source-main\daggerfall-js-source-main`). Its parsers
  (`src/formats/mapsFile.js`, `blocksFile.js`, `woodsFile.js`, `pakFile.js`) are plain ES modules that Node imports
  directly. Its own road generator is `src/world/roadNetwork.js`.

| Data | Count / shape | Use in Skycraft |
| --- | --- | --- |
| Heightmap (WOODS.WLD) | 1000 x 500 px, land values 0-109 (median 30, p95 75); 1 px = 819 m in Daggerfall | Guide for hand-traced ranges, hills and valleys (section 10). Never resampled into the repo |
| Climate raster (CLIMATE.PAK) | 10 classes: woodlands, haunted woodlands, mountain woods, mountain, desert, hot desert, subtropical, rainforest, swamp, ocean | Biome palette per fief (section 4.3) |
| Political raster (POLITIC.PAK) | 44 regions with land, plus water | Realm and fief polygons, traced and simplified |
| Locations (MAPS.BSA) | 15,251: 410 cities, 1,200 towns (DFU "hamlets"), 1,834 villages, 1,841 farms, 1,646 taverns, 1,043 temples, 475 cult shrines, 14 covens, 2,557 manors and hovels, 4,229 dungeons (1,500 labyrinths, 843 keeps, 1,112 ruins, 774 graveyards) with 19 dungeon types | The place list, curated (section 5) |
| Town layouts (MAPS.BSA + BLOCKS.BSA) | Every location's RMB block grid (cities 6x6 to 8x8; towns 2x2 to 4x3; villages 1x2 or 2x2), building lists (type, quality, faction), 491 distinct blocks with building positions | City-plan seeds (section 6) |
| Walls and gates | All 410 cities are walled and no town or village is. A wall is a ring of `WALL` blocks; gate blocks are `WALLAA08/09/10/11` (S/E/N/W), so every city has 4 gates at known grid positions | `walls`/`gates` of the city plans |
| Guilds and orders | Guild-hall factions: Mages Guild 705 halls, Fighters Guild 636, and 10 regional knightly orders (Dragon/Daggerfall, Rose/Wayrest, Candle/Sentinel, Flame/Anticlere, Horn/Lainlyn, Owl/Glenpoint, Raven/Dwynnen, Wheel/Abibon-Gora, Scarab/Totambu, Hawk/Santaki) | Faction hubs and quest givers (section 9) |
| Temples | 8 deity temples by faction (Kynareth 641, Mara 445, Arkay 396, Julianos 357, Z'en 327, Stendarr 320, Akatosh 235, Dibella 196) plus each fief's patron temple | Temple seat per realm |
| Roads | DFO generates its network from MAPS.BSA + WOODS.WLD (14,012 road px, 26,944 track px). Daggerfall's own data has no roads | Topology hints only (section 7) |

Extraction outputs (all in `.reference/daggerfall_ref/v2/`): `locations.json`, `towns.json`, `rmb_blocks.json`,
`roads_dfo*.{bin,json}`, `city_graph.json`, `scale_model.json`, `region_table.json`, `selection.json`,
`townplan_seeds.json`, plus previews (`scale_overlay.png`, `selection_preview.png`) and the four scripts that rebuild them.

A correction to v1: the PAK rasters must be read at column x + 1 for map pixel x (DFU `GetPoliticIndex`). The v1
files in `.reference/daggerfall_ref/` are one column off; v2 is aligned.

---

## 2. Scale and the mapping into Skycraft's frame

### 2.1 Daggerfall is a different scale of world

Daggerfall's map is 819 x 410 km (1 px = 32,768 units = 819 m). Daggerfall city to Wayrest is 653 px, or 535 km.
Skycraft keeps the provinces in lore-map proportion to a 6,000-block Skyrim. On that map Daggerfall and Wayrest are
4,061 blocks apart, so **one Daggerfall pixel becomes about 6.2 blocks**. That is a 1:132 compression of Daggerfall's
ground. Skyrim is roughly 1:1.

This is fixed by the lore frame, not chosen. A larger scale would push Wayrest into Craglorn and Wrothgar. A smaller
one would leave southern High Rock empty.

At this scale the whole Daggerfall window covers **12.4 km² of Skycraft land**: about half of Skyrim's 26.5 km², in
6,200 x 3,100 blocks. Daggerfall's 15,251 locations would be 1,230 per km². Skyrim has 14.3.

### 2.2 How far the current placement is from Daggerfall

For the cities on both maps, the ratio of current Skycraft distance to Daggerfall distance should be one number. Today
it ranges from 3.0 to 54. The Daggerfall-era towns were placed from region names without Daggerfall's geography.
Examples (blocks; "DF" = Daggerfall pixels x 6.22):

| Pair | Daggerfall km | DF-consistent | Current | Current / DF px |
| --- | --- | --- | --- | --- |
| Daggerfall - Wayrest | 535 | 4,061 | 4,061 | 6.2 |
| Daggerfall - Sentinel | 189 | 1,432 | 2,550 | 11.1 (the bay must be wider: 2.3) |
| Daggerfall - Glenpoint | 91 | 694 | 1,991 | 17.9 |
| Daggerfall - Northmoor | 135 | 1,023 | 4,224 | 25.7 |
| Wayrest - Lainlyn | 107 | 809 | 930 | 7.2 |
| Wayrest - Satakalaam | 50 | 381 | 3,332 | 54.4 |
| Wayrest - Totambu | 99 | 748 | 4,278 | 35.6 |
| Sentinel - Anticlere | 147 | 1,117 | 4,856 | 27.1 |
| Sentinel - Totambu | 386 | 2,933 | 1,408 | 3.0 |

The full table for 17 cities is in `scale_model.json` (`pairs`).

### 2.3 The recommended mapping (affine plus one local warp)

1. **Similarity transform** (scale, rotation, translation). Daggerfall pixel `(px, py)` goes to block `(x, z)` with
   scale 6.221, rotation +7.2 degrees, and translation chosen so Daggerfall (207.5, 213.5) lands on (-9300, 2600) and
   Wayrest (859.5, 244.5) lands on (-5300, 3300), both exactly. Every distance on the map is preserved.
2. **Bay-opening hinge.** Daggerfall's bay is a narrow channel, but the lore map (and our binding Sentinel at z 4900)
   has a wide bay that opens to the west.
   * The Hammerfell shore turns rigidly by -23.3 degrees about a hinge on the land bridge east of Wayrest (Daggerfall
     pixel 940, 285; block -4835, 3613).
   * The weight is 1 on Hammerfell land, 0 on High Rock land, blended across the water, and 0.5 within 40 px of the
     other shore's land east of pixel 840, so the land bridge does not tear.
   * Each shore keeps its own distances exactly; only distances across the bay grow.
   * Measured on land: anisotropy p50 and p95 are 1.00, and 2.1% of land is above 1.1 (all of it in the hinge band at
     the bridge).
   * The coast agrees with our current vectors on 81% of the window. We trace the bay coast from Daggerfall's coast
     anyway (IB1), so the 19% that disagrees is replaced.

Two alternatives were measured and rejected:

* Least-squares similarity on the three anchors: misses Daggerfall by 590 and Sentinel by 820.
* A shear that pushes the south shore south: stretches the Hammerfell coast up to 1.75x.

Resulting positions (current to proposed; blocks):

| Place | Daggerfall px | Proposed x, z | Current x, z | Moves |
| --- | --- | --- | --- | --- |
| Daggerfall | 207.5, 213.5 | -9300, 2600 | -9300, 2600 | 0 |
| Wayrest | 859.5, 244.5 | -5323, 3336 | -5300, 3300 | 43 (keep current) |
| Sentinel | 397.5, 343.5 | -7976, 4900 | -8200, 4900 | 224 east |
| Glenpoint | 167.6, 109.4 | -9465, 1927 | -11050, 1650 | 1,609 |
| Northmoor | 219.6, 49.4 | -9098, 1597 | -9750, -1600 | 3,263 |
| Anticlere | 357.6, 168.4 | -8339, 2439 | -7950, 50 | 2,420 |
| Ykalon | 347.6, 29.4 | -8292, 1573 | -7650, -300 | 1,980 |
| Dwynnen | 496.6, 86.4 | -7417, 2041 | -7100, 2600 | 642 |
| Menevia | 807.6, 187.4 | -5576, 2907 | -5947, 1866 | 1,105 |
| Tulune | 73.6, 123.4 | -10056, 1940 | -8200, 2600 | 1,970 |
| Lainlyn | 737.6, 289.4 | -6037, 3989 | -5850, 4050 | 197 |
| Kozanset | 704.6, 337.4 | -6151, 4333 | -7150, 4700 | 1,064 |
| Totambu | 868.6, 364.4 | -5124, 4211 | -8350, 6300 | 3,843 |
| Satakalaam | 839.6, 302.4 | -5405, 3891 | -6850, 6250 | 2,767 |
| Mournoth | 981.6, 324.4 | -4518, 3777 | -5050, 4700 | 1,066 |
| Abibon-Gora | 207.6, 474.4 | -8885, 6011 | -9150, 6450 | 513 |

The window's corners land at about (-10584, 1541), (-4242, 1901), (-4105, 4794) and (-10443, 5742).

### 2.4 What else moves, and the frame around the window

* **North of the window** (z below about 1,500): Camlorn, Shornhelm, Evermor, Northpoint, Farrun, Jehanna and modern
  Orsinium are not on Daggerfall's map. They keep their positions. Northern High Rock keeps its ESO-derived regions
  (`glenumbra`, `rivenspire`, `stormhaven`, `bjoulsae`, `wrothgar`), and their polygons shrink to the part north of
  the window.
* **South of the window:** Skaven, Rihad, Taneth, Hegathe and Stros M'Kai stay. **Gilane** (-9900, 6050) falls in
  Daggerfall's open sea west of Abibon-Gora, so it moves onto the coast south of the window (about -9600, 6500).
* **East:** Mournoth (-4518, 3777) sits 330 blocks from Elinhir (-4200, 3700). Either move Elinhir 300 east into
  Craglorn proper or accept two neighbours across the realm border. Recommended: move Elinhir to about (-3950, 3800).
* **Orsinium:** Daggerfall's "Orsinium Area" lies just north-east of Wayrest. Our binding modern Orsinium sits 2,500
  blocks further north. Keep the binding anchor. The Daggerfall-era site becomes the Orsinium Area fief with a ruin
  (the existing `old_orsinium` marquee dungeon can move here, owner's call).
* **Islands:** Daggerfall's Betony lies just south-west of Daggerfall (about -9880, 2840), while our `betony` island
  is mid-bay at (-7450, 3600). Move it. The Isle of Balfiera with the Direnni Tower becomes the large island east of
  Menevia (about -6400, 2900).
* **Binding anchors** (`TAMRIEL.md` section 9): the Sentinel x and Elinhir rows change. Update the table in the same
  commit as the vectors (IB1).

---

## 3. Modernisation: what we keep, amplify and replace

Daggerfall gives us the most complete political and settlement map of any Elder Scrolls province. At Daggerfall's own
scale it was also its weakness: smooth 800-metre-pixel heights, about 30 town blocks recombined into 3,444 towns,
random-block dungeon mazes, and hours of empty walking between near-identical dots. The rule from
`DESIGN-PRINCIPLES.md` section 1 applies row by row:

| Content | Keep from Daggerfall | Amplify | Replace |
| --- | --- | --- | --- |
| **Geography** | The bay's shape: the Glenumbra peninsula, the Wayrest channel and Bjoulsae mouth, Betony and Balfiera, the Wrothgarian highlands in the north-east, the Dragontail spine and Alik'r interior in the south, the island scatter of the eastern bay | Daggerfall's heights are only a macro guide at our scale (one Daggerfall pixel is 6 blocks), so the detail is ours. Every realm gets a **signature landform**: Daggerfall's sea bluffs, Glenpoint's headlands, Anticlere's river meadows under a cliff line, Dwynnen's haunted forest gorge, Wayrest's estuary and channel islands, Wrothgar's broken peaks and passes, Sentinel's sea-cliff harbour and palm terraces, the Alik'r dune sea and wadis, Bangkorai's jungle-river valleys, the Dragontail's black volcanic ridges | Smooth, low-relief terrain: our eroded, plan-first terrain with rivers from `hydrology.py`, gorges and falls where relief allows, coastal cliffs and coves |
| **Political map** | 44 political regions with their names, borders, seats, ruling houses, patron temples, knightly orders, covens and vampire bloodlines | Realms with a **storyline each** (court intrigue in the three kingdoms, an orc frontier, a desert succession, jungle river trade). Fiefs become places with a lord, a banner on the road signs, a court in the keep and a local problem | Ruler names as the only content: each realm's story names its lords and makes the politics playable |
| **Settlements** | Name, position, size class, walled or not, port or not, the mix of services (which guilds, which temple, how many shops) | Every capital and city gets a unique plan with its own silhouette and landmark (`MARQUEE.md`), every town its industry and named buildings | Daggerfall's recombined RMB block towns. The block grid becomes a **zoning hint** (where the market, temple, guild quarter and gates go). Buildings come from our grammar and culture kits; no two towns share a plan |
| **Density** | Relative density: the Daggerfall heartland is crowded, the Alik'r and Wrothgar empty | Fief seats as keeps with villages make the feudal patchwork visible from the road | 15,251 dots: we take about 176, chosen for purpose and spacing (section 5) |
| **Dungeons** | Names, positions, premises (crypt, orc stronghold, coven, laboratory, harpy nest...) and the size ranking (RDB block count) | The biggest and the main-story sites become majors and marquees with hooks, bosses, notes and lore-web links | Random-block mazes: authored room templates per culture, loops with a shortcut back |
| **Roads** | Which towns are neighbours, and the shore roads and mountain passes they imply | Named historic roads with waysides, signposts and inns at crossroads (P6 scenes) | Daggerfall's fade-to-black travel and its roadless map: our road plan (P1-P3) |
| **Quests** | Factions, orders, temples, covens, bloodlines and the main conflict (three crowns and a golem) as facts | Realm storylines, faction chains with choices, coven bargains as named encounters | Generator quests as the main content: radiant work stays as filler, always with a named giver, place and reason |
| **Life** | Building counts as a measure of town size and wealth (quality 1-20) | Industries per realm: Daggerfall's tanneries and wool, Wayrest's trade and shipyards, Sentinel's dyes and spice port, Alik'r caravans and salt, Wrothgar ore, Bangkorai timber and river fish | Nameless crowds: named residents with homes, jobs and schedules (`DESIGN-PRINCIPLES.md` 4.4) |

---

## 4. Regions: realms and fiefs

### 4.1 Nine realms (Skycraft regions inside the window)

Grouped by geography, kingdom and race. Six keep an existing id (`TAMRIEL.md` section 9: ids are never renamed;
polygons may change), and three are new ids. Areas are Skycraft km² of land.

| Realm (region id) | Fiefs (Daggerfall regions) | km² | Seat | Climate mix | Palette proposal |
| --- | --- | --- | --- | --- | --- |
| `high_rock/daggerfall` (new) | Daggerfall, Glenpoint, Tulune, Glenumbra Moors, Betony, Ilessan Hills, Northmoor | 1.64 | Daggerfall (capital) | woodlands, mountain woods | `stormhaven` lowland, `glenumbra_moors` upland, rocky shore |
| `high_rock/anticlere` (new) | Anticlere, Shalgora, Daenia, Urvaius, Ykalon, Phrygias | 1.48 | Anticlere (city) | woodlands; haunted woodlands in Ykalon and Phrygias | `stormhaven`, `wayrest_farmland`; `rivenspire` in the haunted north |
| `high_rock/dwynnen` (new) | Dwynnen, Kambria, Bhoriane, Koegria, Alcaire | 0.87 | Dwynnen (city) | woodlands, some mountain | `stormhaven`, new `haunted_wood` patches round Dwynnen |
| `high_rock/wayrest` (existing) | Wayrest, Menevia, Gavaudon, Isle of Balfiera, Orsinium Area | 0.80 | Wayrest (capital) | woodlands, mountain woods | `wayrest_farmland`, `bjoulsae_river` |
| `high_rock/wrothgar` (existing, extended south) | Wrothgarian Mountains | 1.95 | Newing (city) | mountain | `wrothgarian_mountains` |
| `hammerfell/sentinel` (new) | Sentinel, Tigonus, Ayasofya, Antiphyllos, Bergama, Myrkwasa, Pothago, Kairou, Abibon-Gora, Cybiades | 1.15 | Sentinel (capital) | subtropical, desert; swamp in Tigonus | new `iliac_scrub` (palm and scrub coast), `alikr_desert` inland, new `tigonus_marsh` |
| `hammerfell/alikr_desert` (existing) | Alik'r Desert, Dak'fron | 1.42 | Berbaaqnia (city) | desert, hot desert | `alikr_desert`, `alikr_dunes` |
| `hammerfell/bangkorai` (existing, re-cut) | Lainlyn, Kozanset, Totambu, Satakalaam, Mournoth, Ephesus, Santaki | 1.45 | Totambu (city) | rainforest; mountain in Ephesus | new `iliac_rainforest` (jungle-river valleys), `bangkorai` uplands |
| `hammerfell/dragontail` (existing) | Dragontail Mountains | 1.61 | Dragontail (city) | mountain | `dragontail_mountains` with volcanic scree |

Every realm is in the template's "small" class (under 2 km², `DESIGN-PRINCIPLES.md` 4.1). Each gets the region layer:

* a storyline (5-8 quests);
* a marquee dungeon and one other marquee;
* an open dungeon;
* a world event;
* delves, shards and lorebooks;
* a voice.

Realms carry the existing fields: `levels` (rising southward and eastward from the Daggerfall heartland, 15-45 as
today), `music` and `weather`. They also gain a `realm_banner` colour. This is one new optional field in
`regions.json`; `Region` gains a getter, and nothing existing changes.

### 4.2 Fiefs (44 Daggerfall regions)

A fief is a named sub-area. It is not a `Region`, so it has no palette or level band of its own. It is stored as
polygons in a new `skycraft/tamriel/fiefs.json` (`id`, `realm`, `name`, `seat`, `lord` title, `temple`, `order`,
`coven`, `bloodline`, `poly`). A static `Fiefs.at(x, z)` lookup serves the map screen (fief names at mid zoom), road
signs ("Entering the Barony of Kambria"), dialogue ("the Baron of Koegria"), and quest givers.

Fief seats become places (section 5.1). The table `region_table.json` lists each fief's:

* area: Wrothgarian Mountains 1.95 km² down to Betony 0.013;
* seat;
* climate shares;
* counts;
* temple, ruling house, bloodline, orders and covens.

Merge rule: fiefs under 0.01 km² fold into their neighbour as a named place (Betony, 0.013 km², stays: it is an island fief with its own court). Today the rule applies to one:

* Cybiades (0.002) folds into Sentinel as an islet.

Two other political regions get no fief:

* "High Rock sea coast" has three locations, including Mantellan Crux, which is a pocket realm, not overworld ground.
* "Yeorth Burrowland" has no land.

### 4.3 Climates to biomes

| Daggerfall climate | Where | Skycraft biome (existing / new) |
| --- | --- | --- |
| woodlands | most of High Rock's bay shore | `stormhaven`, `wayrest_farmland` |
| haunted woodlands | Ykalon, Phrygias, edges of Dwynnen and Northmoor | `rivenspire` (gloomy), new `haunted_wood` (dead oaks, fog, crows) |
| mountain woods | Glenpoint, Ilessan Hills, the Wayrest hills, Orsinium Area | `glenumbra_moors` upland, `bangkorai` |
| mountain | Wrothgarian Mountains, Dragontail, Ephesus, Isle of Balfiera | `wrothgarian_mountains`, `dragontail_mountains` |
| desert / hot desert | Alik'r, Dak'fron, Antiphyllos, Bergama | `alikr_desert`, `alikr_dunes` |
| subtropical | Sentinel's coast, Abibon-Gora, Kairou, Myrkwasa, Pothago, Ayasofya, Tigonus | new `iliac_scrub` (palms from the existing palm blocks, olive-green scrub, ochre soil) |
| rainforest | Totambu, Mournoth, Satakalaam, Lainlyn, Kozanset | new `iliac_rainforest` (broadleaf canopy, vines, red clay riverbanks; reuses `valenwood_rainforest` placers at lower height) |
| swamp | Tigonus | new `tigonus_marsh` (warm reed marsh, reuse the `map_water` channel maze) |

New biomes go through `tools/worldgen/biomes.py` (generated, never hand-edited). The texture palettes are our own: the
existing High Rock greens, plus terracotta, ochre and palm-green for the south shore.

---

## 5. Places: what becomes a Skycraft place

### 5.1 Selection rules (implemented in `df_curate_v2.py`, output `selection.json`)

1. **Capitals:** Daggerfall, Wayrest, Sentinel.
2. **Realm seat (city):** in realms without a capital, the seat of the largest fief: Anticlere, Dwynnen, Newing,
   Berbaaqnia, Totambu, Dragontail.
3. **Towns:** seats of fiefs that have a knightly order or are 0.25 km² or larger: Glenpoint, Ilessan Hills, Northmoor,
   Urvaius, Ykalon, Tigonus, Abibon-Gora, Pheonahator, Lainlyn, Mournoth, Santaki.
4. **Fief keeps** (village tier with the lord's keep): the other 23 fief seats (Tulune, Shalgora, Daenia, Phrygias,
   Kambria, Bhoriane, Koegria, Alcaire, Menevia, Gavaudon, Kozanset, Satakalaam, Ephesus, Ayasofya, Bergama, Myrkwasa,
   Pothago, Kairou, the Balfiera, Betony and Orsinium Area seats, and so on). They keep the political map visible
   without turning it into 44 towns.
5. **Unique sites:** all 14 covens and the main-story sites (Privateer's Hold, Castle Necromoghan, Woodborne Hall,
   Lysandus' Tomb, Castle Llugwych, Direnni Tower, Tristore Laboratory, Scourg Barrow, Shedungent, Orsinium). Mantellan
   Crux becomes a pocket realm reached from the Daggerfall coast, not an overworld site.
6. **Per-realm quotas** (Skycraft km² of land), each kind with a minimum spacing:

   | Kind | Per km² | Min. spacing (blocks) | Picking order |
   | --- | --- | --- | --- |
   | villages | 0.4 | 260 | Daggerfall villages and hamlets on a DFO road or track first, then the largest |
   | farms | 0.9 | 160 | as for villages |
   | inns | 0.4 | 200 | as for villages |
   | temples | 0.15 | 220 | as for villages |
   | shrines | 0.15 | 200 | as for villages |
   | dungeons | 7 | 110 | round-robin over the realm's dungeon types (diversity), largest dungeon first within a type |

   Graveyards are not dungeons: they become the cemetery scenes at settlement edges (P6 `SETTLEMENT_EDGE`). Manors and
   hovels become farm-yard and roadside scenes, not places.
7. **Result:** 176 places in 12.4 km², or 14.2/km² (Skyrim 14.3). Of these, 65 are settlements (3 capitals, 6 cities,
   11 towns, 23 fief keeps, 6 villages, 10 farms, 6 inns) and 111 are dungeons and unique sites (9.0/km², Skyrim 9.0).
   Settlements run at about 2.5 times Skyrim's density. That is deliberate: the bay is the most densely settled,
   feudal corner of Tamriel, and the fief keeps carry the politics. Named towns (capital, city or town) are 1.6/km².

The selection is a starting proposal: IB3 reviews it by hand. Swap in lore-famous names, and drop dungeons that
cluster or sit on a road bend. Every swap keeps the counts and the spacing.

### 5.2 Reconciling the current places

Today 274 High Rock and Hammerfell places lie inside the window. They come from the ESO-era list: 15 towns, 21
villages, 28 farms, 13 forts, 40 landmarks and 157 dungeons. Rules for IB3:

* **Inside the window, Daggerfall is the authority** for settlements and political seats.
* An existing place with a Daggerfall namesake (37 do: Glenpoint, Anticlere, Tulune...) **moves** to its Daggerfall
  position and keeps its id.
* Marquee places (`tools/marquee/marquee.json`: `daggerfall`, `wayrest`, `sentinel`, `glenpoint`, `cath_bedraud`,
  `koeglin_village`, `weeping_giant`, `aldhaven_harbor`, ...) stay. They are re-homed only if they collide (under 250
  blocks) with a selected Daggerfall settlement, and they always keep their id and identity.
* ESO-era villages, farms and dungeons inside the window that are neither marquee nor lore-web places are
  **re-homed** north or south of the window when their culture fits, or dropped. The window's quotas already fill the
  space.
* Forts stay if they guard a pass or a road crossing on the new road plan; otherwise they move.
* Lore-web places (`tools/marquee/loreweb.json`) are never dropped. Their clues move with them.

---

## 6. Town layouts: from Daggerfall's block grid to Skycraft city plans

### 6.1 What a Daggerfall town is

A Daggerfall city is a grid of up to 8 x 8 RMB blocks, each about 100 m square in Daggerfall, ringed by `WALL` blocks
with four gates. Inside, each block is one of about 30 kinds: a tavern block, a mages' guild block, a temple block, a
market, residential blocks, a palace, the custom blocks of the three capitals, and so on. The building list gives
types, quality (1-20) and owning faction.

Examples:

* **Daggerfall:** 316 buildings. 20 taverns, 9 general stores, 6 pawnbrokers, 5 booksellers, 4 armourers, 4 guild halls
  (Mages, Fighters, two Knights of the Dragon), 3 alchemists, 3 banks, 3 jewellers, 1 library, 1 palace, 1 temple
  (Kynareth), 154 houses (23 for sale) and 100 wall segments.
* **Sentinel:** mostly its own custom blocks.

At our scale a whole Daggerfall city would be 6 blocks wide, so the grid is read as **a zoning diagram, not a
footprint**.

### 6.2 Mapping

The city-plan generator (IB4) reads `townplan_seeds.json` and writes a draft `cityplans/<id>.json` with
`"base": "zoning"`. In the draft:

* Pins: gates, landmarks and the quarters by role.
* The zoning planner fills streets and houses.
* Hand polish follows for capitals, cities and marquee towns.

| Daggerfall block or building | Skycraft plan element | `Archetype` / grammar `Use` |
| --- | --- | --- |
| `WALL` ring; gate blocks AA08-11 | `walls` ring (radius from tier) with 4 gates at the grid side positions, main gate toward the nearest road end | `WALL`, `GATE`; `gates` list |
| `PALA`, `CAST`, capital `CUST` blocks | Palace or castle landmark; capitals get the marquee landmark | `PALACE` / grammar `KEEP` |
| `TEMP` (temple faction = deity) | Temple of that deity in its own quarter | `TEMPLE` |
| `TVRN` | Inn(s): capital 3, city 2, town 1, keep village 1 | `INN` |
| `MAGE` (faction 40) | Mages Guild hall or tower | `GUILD_HALL` / `MAGE_TOWER` |
| `FIGH` (faction 41) | Fighters Guild hall | `GUILD_HALL` |
| `K***` knightly order blocks (factions 368, 408-417) | Order hall with barracks and yard | `GUILD_HALL` + `BARRACKS` |
| `THIE` | Hidden fence den (no sign) | `FENCE_DEN` |
| `DARK` | Nothing visible; a sanctuary entrance in a cellar | (quest-owned lot via `SettlementPlan.reserve`) |
| `MARK` | Market plaza with stalls | `PLAZA` (stalls 6-10) |
| `GENR`, `PAWN` | General store; pawnbroker as a store variant | `STORE` / `GENERAL_STORE` |
| `ARMR`, `WEAP` | Smithies (armour / weapons variants) | `SMITHY` |
| `ALCH` | Apothecary | `ALCHEMIST` |
| `BOOK`, `LIBR` | Bookseller; library as a guild-hall-sized reading hall in capitals | `BOOKSELLER`, `GUILD_HALL` (program: library) |
| `CLOT` | Tailor | `TAILOR` |
| `GEMS` | Jeweller | `JEWELLER` |
| `BANK` | Counting house (template: "bank or moneylender" in capitals) | new grammar `Use.BANK`, or `GUILD_HALL` with a moneylender program until it exists |
| `FURN` | Joiner's workshop | `SAWMILL` / `WAREHOUSE` |
| `RESI` and house types 17-22 | Houses; quality 1-20 maps to `Wealth` (1-6 poor, 7-13 common, 14-20 rich) | `HOUSE`, `TOWNHOUSE`, `MANOR` |
| `house_for_sale` | Player-home plots | `plan/HousePlots` |
| `GRVE` | Cemetery inside or just outside the wall | `GRAVEYARD` |
| `FILL` | Gardens, green, orchard | `GARDEN`, open ground |
| `SHIP` | Docks | `DOCK` |
| `FARM` (village blocks) | Farmsteads and fields | `FARMHOUSE`, `FIELD` |

### 6.3 Counts by tier

Daggerfall's counts become Skycraft's tier targets (`DESIGN-PRINCIPLES.md` 4.4):

* **Capitals** keep every service kind present in Daggerfall (at least one of each), with these caps: inns 3,
  smithies 2, stores 3, guild halls as in Daggerfall. Houses fill to 100-170 buildings.
* **Cities:** one of each service kind present, inns 2, then houses to 60-150.
* **Towns:** inn, store, smith, the temple or a shrine, and the fief's order hall if any. Houses to 25-50.
* **Fief keeps:** a keep (the lord's hall, `KEEP` use, walled yard) plus 8-25 village buildings, an inn, and a trader
  or smith.

The ratios of the remaining shop kinds follow Daggerfall's mix, so a pawnbroker-heavy town stays a trading town.

### 6.4 Uniqueness rules

These apply on top of the mapping:

* The block grid's arrangement (which side the temple, palace and market are on) sets the quarters. Two towns with
  the same grid shape still differ, because the generator rotates to the real road ends and shore, and picks the form
  per site (harbour, hilltop, river, cliff).
* Every capital and city has a `sil` tag that is unique across plans (`validate_cityplans.js`). `MARQUEE.md` already
  names the silhouettes:
  * Daggerfall: river-castle-twin-bank.
  * Wayrest: bay-castle-wheel.
  * Sentinel: domed-oasis-harbour.
  * Glenpoint: headland-fort-lighthouse.
* The six realm-seat cities need new ones, chosen in IB4: Anticlere river-meadow, Dwynnen gorge-bridge, Newing
  mountain-shelf, Berbaaqnia oasis-caravanserai, Totambu stilted-river-port and Dragontail black-stone-terraces.
* Each realm gets a style overlay (`styles/overlays`): roof colour, trim and banner. Each fief keep gets the fief's
  banner colours.

---

## 7. Roads

### 7.1 What exists

* **Daggerfall itself has no road data.** Its travel is fast travel across map pixels. DFO's `src/systems/travel.js`
  ports DFU's TravelTimeCalculator, which walks the straight pixel line and charges each pixel by its climate (it
  ignores roads).
* **DFO generates a network** (`src/world/roadNetwork.js`, MIT). Its design:
  * Cities and hamlets are road nodes, linked to their 2 nearest neighbours within 70 px plus a spanning tree.
  * Villages, taverns, temples, manors, cults and covens get tracks to their 2 nearest within 24 px.
  * Routes are found by A* on the WOODS heightmap, with climb cost, turn cost and a discount for existing roads.
  * On the owner's data it gives 14,012 road px in 1,609 chains, 26,944 track px in 5,079 chains, and 32 unrouted
    pairs. 68% of Basic Roads' hand-drawn road pixels lie within 1 px of it.
  * DFO also plans road-following routes (`src/systems/travelRoute.js`).
* **Basic Roads** (Hazelnut's DFU mod) is the hand-drawn network. Its data is permission-only and not used.

### 7.2 Import into the plan-first pipeline

We do not import pixels. The road plan (P1) already routes every link on our own terrain, with profiles, bridges and
switchbacks. What we take is **topology and naming**:

1. **Named roads** (`skycraft/roads/named_roads.json`): these come first in `RoadGraph`.
   * **North Shore Road:** Daggerfall, Anticlere, Dwynnen (or the Shalgora keep), Kambria, Menevia, Wayrest. Highway.
   * **Glenumbra Road:** Daggerfall, Glenpoint, Northmoor, then north out of the window to Camlorn. Highway.
   * **South Shore Road:** Sentinel, Tigonus, Kozanset, Lainlyn, then round the bay head by the Bjoulsae bridge to
     Wayrest. Highway; the bridge is long, `bridge` 160.
   * **Alik'r Caravan Road:** Sentinel, Berbaaqnia, Pheonahator (Dak'fron), then on to Skaven. Road.
   * **Abibon Coast Road:** Sentinel, Myrkwasa, Pothago, Kairou, Abibon-Gora. Road.
   * **Jungle River Road:** Lainlyn, Totambu, Satakalaam, Mournoth, then on to Elinhir. Road.
   * **Wrothgar Pass:** Wayrest, Gavaudon, Newing, then north to Orsinium. Road, switchbacks.
   * **Dragontail Pass:** Totambu, Dragontail, then on to Rihad. Road.
2. **The greedy spanner** (`RoadGraph`, stretch 1.45) then links every other selected settlement. DFO's
   `city_graph.json` is used only to break ties (prefer the neighbour Daggerfall's geography makes adjacent). Its
   components are disconnected across the bay head, so it cannot be used alone.
3. **Gate lanes and trails** reach every fief keep, dungeon, coven, shrine and landmark (P1 rules). `reach_lint.py`
   must show 100% of the new places reached.
4. **Ports and ferries:** Daggerfall-Sentinel, Wayrest-Lainlyn and Daggerfall-Betony ferries (`ports.json`, sailing).
   Balfiera is reached only by boat (the Direnni Tower moment in `MARQUEE.md`).
5. **P2-P6 then apply unchanged.** The terrain obeys the plan, the paint and crossings come from the plan, vegetation
   masks apply, streets reach every door (P5), and scenes are planned (P6). The P6 waysides, crossroads signs naming
   fiefs, and fiefs' boundary stones are the scenes that make the political map felt on the road.

---

## 8. Dungeons

### 8.1 Daggerfall types to Skycraft themes

Uses the dungeon `variant` values in use today:

| Daggerfall type | Skycraft variant | Notes |
| --- | --- | --- |
| crypt, cemetery | `breton_crypt` (High Rock), `yokudan_tomb` (Hammerfell) | Cemeteries are settlement-edge scenes, not dungeons |
| orc stronghold | `orc_ruin`, or a live Orc stronghold settlement in Wrothgar | Some become friendly strongholds (Morkul-style) |
| human stronghold, prison | `bandit_camp`, a fort (garrisoned) | Prisons are the forts of the realm's crown |
| ruined castle | ruined keep (fort, ruined); new `breton_keep_ruin` room kit | Very common in High Rock; the region's "barrow" equivalent |
| desecrated temple | `daedric_ruin` / cult temple | Cult shrines feed the daedric world event |
| mine | `mine` | Wrothgar and Dragontail ore; the realm product |
| natural cave | `cave`, `troll_cave`, `smuggler_cave` (coast) | Coast caves become smuggler caves |
| coven (dungeon) and the 14 covens | `hagraven_lair` (renamed Witch's Coven for the bay), the covens as places with a friendly face | The covens are faction seats (section 9), not just lairs |
| vampire haunt | `vampire_lair`, each tied to the fief's bloodline | Bloodline lore per fief (Vraseth, Haarvenu, Thrafey, Lyrezi, Montalion, Khulari...) |
| laboratory | `necromancer_lair`, `tower` | The Necromancers' and the Worm cult's labs |
| harpy nest | `den`, coastal cliffs | |
| spider nest, scorpion nest | `spider_cave`; new `scorpion_den` (Alik'r) | |
| giant stronghold | `den` (giant camp) | |
| barbarian stronghold | `bandit_camp` (hill clans) | |
| dragon's den | `den` (dragonling lair; a rare marquee in the Dragontail) | |
| volcanic caves | `cave` with a lava palette | Dragontail only |
| Dwemer ruins (not a Daggerfall type) | `dwemer_ruin` | Keep the existing Hammerfell Dwemer sites (Volenfell...) |

### 8.2 Marquee candidates (Daggerfall size and story weight)

The largest dungeons in Daggerfall's data, by RDB block count:

* Scourg Barrow (22, Dragontail);
* Wayrest's, Sentinel's and Daggerfall's palaces (21, 18, 16);
* Lysandus' Tomb (18, Menevia);
* Direnni Tower (16, Isle of Balfiera);
* Shedungent (16, Wrothgar);
* Orsinium (16).

Realm by realm (each needs at least 1 marquee dungeon):

| Realm | Marquee dungeon proposal |
| --- | --- |
| Daggerfall | Privateer's Hold (sea-cave fortress), or Castle Necromoghan |
| Anticlere | Castle Llugwych (Ykalon) |
| Dwynnen | a haunted-forest citadel from Dwynnen's 14-block dungeons (The Citadel of Hearthsly) |
| Wayrest | Lysandus' Tomb, and the Direnni Tower by boat |
| Wrothgar | Shedungent; `old_orsinium` if moved here |
| Sentinel | Tristore Laboratory, or a harbour smugglers' warren |
| Alik'r | The Web of Anker (scorpion nest) or Faugta's Guard |
| Bangkorai | `lost_city_of_the_na_totambu` (already in `MARQUEE.md`) |
| Dragontail | Scourg Barrow |

---

## 9. Quest hooks (facts to build on)

From FACTION.TXT and the region data (`../region_stats.json`, `factions.json`; names are facts, all quest text is
ours):

* **The three crowns.** Daggerfall, Wayrest and Sentinel are rival courts with a shared succession and alliance web.
  The realm storylines of the three capitals interlock: a letter, a betrothal, a border fief changing hands. The
  player's choices move one fief's banner (the keep's banner and the road signs change).
* **Ten knightly orders**, one per fief (Dragon, Rose, Candle, Flame, Horn, Owl, Raven, Wheel, Scarab, Hawk). Each is
  an order hall in its seat with a rank line of 5-8 quests. The orders give the bay its faction texture.
* **Temples:** each fief has a patron temple (Kynareth for Daggerfall and Wrothgar, Arkay for Sentinel and Ilessan
  Hills, the Akatosh Chantry for Wayrest, Mara for Anticlere, Z'en for Totambu and Glenpoint, and so on). One temple
  seat per realm gets a temple line, and shrines elsewhere carry blessings.
* **Witch covens** (14, e.g. the Glenmoril Witches near Glenpoint, the Sisters of the Bluff near Daggerfall, the
  Daughters of Wroth, the Dust Witches of Antiphyllos). These are friendly-but-dangerous faction seats offering coven
  bargains and daedric summonings on the daedra's days.
* **Vampire bloodlines** by fief group (Vraseth in the Daggerfall realm, Montalion round Wayrest and Bangkorai,
  Lyrezi in Wrothgar...). Haunts and a bloodline storyline for afflicted players.
* **Guild networks:** the Mages and Fighters Guilds in every city (705 and 636 halls in Daggerfall). In Skycraft, one
  hall per realm seat, with the guild's province chain in Wayrest (Mages) and Sentinel (Fighters). The thieves' den
  and a Dark Brotherhood sanctuary go in Daggerfall and Sentinel.
* **Main-story places** become the lore-web thread "the golem's key" across the three capitals: Privateer's Hold,
  Lysandus' Tomb, Castle Llugwych, Woodborne Hall, Direnni Tower, Scourg Barrow, Shedungent, Orsinium and Castle
  Necromoghan. It is told in our own words with our own plot.
* **Archetypes** (58 from v1 `quest_archetypes.json`, in our words) seed each realm's radiant board. The rule from
  `DESIGN-PRINCIPLES.md` 0.6 applies: a named giver, a named place, a reason.

---

## 10. Terrain: heights, relief and blending

* **What Daggerfall's heights are at our scale.** One Daggerfall pixel is 6 blocks, so its 1000 x 500 heightmap is
  *finer* than our 32-block raster. At our scale it is a smooth macro surface: rolling, with few sharp features.
  Inside the window it correlates only 0.51 with our current elevation, because our authored ranges were placed from
  the lore map. Its value is the macro pattern: where the land is high (the Wrothgarian highlands, the Dragontail
  spine, the Glenpoint and Ilessan hills, the Balfiera hill-island) and where it is low (the Daggerfall and Anticlere
  shore plains, the Bjoulsae valley, the Tigonus marsh, the eastern jungle valleys).
* **Blending without copying.** IB1 adds a tracing helper, `tools/worldgen/iliac_trace.py`. It reads the gitignored
  data, warps it, and proposes simplified vectors for review:
  * the coastline (Douglas-Peucker at 48 blocks, islands at least 64 blocks across);
  * fief and realm borders (simplified at 64 blocks);
  * range spines (ridge lines from smoothed height maxima, kept where at least 600 blocks long);
  * `areas` polygons for highlands and lowlands (contours at three levels).

  An agent then edits and accepts them into `tamriel_vectors.json`, **with heights we choose**. The helper never
  writes rasters or per-pixel values into the repo, and its outputs are at fan-map level of detail. `tamriel_map.py`
  then builds everything as today.
* **Height scale.** Lowland plains y 66-90. Shore bluffs at Daggerfall and Glenpoint rise to y 100-120 with cliffs
  to the sea. Hills y 110-180 (Ilessan Hills, Glenpoint foothills, the Balfiera hill at about 170). Wrothgarian
  Mountains y 300-460 with passes at y 200-240. Dragontail y 280-420 with volcanic peaks. Alik'r dunes y 70-130 with
  rocky mesas to 200.
* **Amplify.** Every realm gets its signature landform (section 3, Geography row) as authored vectors:
  * the Daggerfall sea bluffs and the river through the city (the river-castle silhouette needs a real river);
  * Glenpoint's headland;
  * the Dwynnen gorge;
  * the Bjoulsae mouth and the Wayrest channel islands;
  * Wrothgar's arches and passes;
  * Sentinel's harbour cliff;
  * the Alik'r dune sea and wadis;
  * the jungle river valleys of Bangkorai;
  * the Dragontail black ridges.

  Rivers come from `hydrology.py` on the new macro surface. At least 6 named rivers enter the bay (Daggerfall's river,
  the Anticlere river, the Bjoulsae, the Lainlyn and Totambu rivers, a Sentinel wadi river), with falls where they
  leave the highlands.
* **Seams.** Outside the window the authored High Rock and Hammerfell vectors stay. At the window edge, ranges and
  rivers are joined by hand (the Wrothgarian spine continues north to Orsinium, the Bjoulsae north to Evermor, the
  Dragontail south to Rihad). The tracing helper reports any vector gap over 200 blocks at the edge.

---

## 11. Phases (each a self-contained agent task)

Order: IB1, then IB2 and IB3 in parallel, then IB4, IB5 and IB6 in parallel, then IB7 and IB8. Every phase follows
`AGENT-RULES.md` (own worktree, no commits unless asked, compile and gates at the end) and the fast loop.

| Phase | What | Owns (files) | Gates (measurable) |
| --- | --- | --- | --- |
| **IB0 Extraction** (done) | v2 extraction, scale study, selection proposal | `.reference/daggerfall_ref/v2/**` (gitignored), this doc | Files listed in the v2 README rebuild from the scripts |
| **IB1 Frame and vectors** | Apply the mapping. Trace the window's coast, islands, realm polygons, ranges, areas and rivers via `iliac_trace.py`. Move Betony; shrink the northern ESO regions. Update `TAMRIEL.md` section 9 (Sentinel x, Elinhir, Gilane) | `tools/worldgen/iliac_trace.py` (new), `tools/worldgen/tamriel_vectors.json` (Iliac part), regenerated `skycraft/tamriel/*.png`, `TAMRIEL.md` section 9 | `layout_lint.py` passes. Coast agreement with Daggerfall's warped coast >= 0.90 inside the window (measured by a `.reference`-only check script, not committed data). All binding anchors on land in their region. No file under `mod/` or `tools/` contains Daggerfall raster data (size and grep check). Haiku visual review of `preview_places_high_rock.png` and `preview_places_hammerfell.png` |
| **IB2 Realms, fiefs, biomes** | 3 new realm regions, re-cut existing ones. `fiefs.json` + `Fiefs` lookup. New biomes (`iliac_scrub`, `iliac_rainforest`, `tigonus_marsh`, `haunted_wood`). Palettes. Lang names | `regions.json` (via `tamriel_map.py`), `skycraft/tamriel/fiefs.json` (new), `com.skycraft.tamriel.Fiefs` (new), `tools/worldgen/biomes.py`, `palettes.json`, `lang/skycraft/tamriel_regions.json` | Every fief >= 0.01 km² present, with area within 10% of `region_table.json`. Every palette covers ocean, beach and river. `/skybiome census` on each realm shows its signature biome as the top lowland pick. `compileJava` passes |
| **IB3 Places** | Generate a place draft from `selection.json` (tool `tools/worldgen/iliac_places.py`, reading `.reference` at authoring time only), hand-review, reconcile the 274 existing in-window places (section 5.2). Fief-keep place type (`village` + `variant: "fief_keep"`) | `places/high_rock.json`, `places/hammerfell.json`, `places/provinces.json` (moved anchors), `tools/worldgen/iliac_places.py` (new), lang place names | Per realm: settlements and dungeons inside the 4.4/4.5 bands of `DESIGN-PRINCIPLES.md` (documented deviation for fief keeps). Spacing rules hold. 100% on land. `tools/marquee/validate.js` passes (231 ids). No lore-web place dropped. `parity.py` SET does not regress |
| **IB4 City plans** | `tools/worldgen/iliac_cityplans.py`: seeds to draft `cityplans/<id>.json` (zoning base, gates, quarters, service counts). Hand polish for Daggerfall, Sentinel, Wayrest (replace `castleCapital`), Glenpoint and the 6 realm seats. `Use.BANK` if taken | `skycraft/tamriel/cityplans/*.json` (new ids), `tools/worldgen/iliac_cityplans.py`, `settlement/grammar/Use.java` (additive) | `validate_cityplans.js` passes (unique `sil`). `/skysettle` metrics per tier in band (buildings, services). `town_checks.py` passes. `render_town.py` contact sheet passes Haiku review as "not a reskin". 0 unstaffed workplaces (`population_lint.py`) |
| **IB5 Roads** | Named Iliac roads, ferries. Rebake `roadplan`, `sceneplan` | `skycraft/roads/named_roads.json`, `skycraft/tamriel/ports.json`, baked files | `roadplan_lint.py` 0 errors. `reach_lint.py` 100% of new places reached. Two new corridors in `full_corridors.ps1` (daggerfall:anticlere, sentinel:tigonus) at 0 gaps, 0 unbridged crossings, 0 drops without a step. `placement_lint.py` densities in target |
| **IB6 Terrain relief** | Signature landforms, heights per section 10, hydrology rerun, seams at the window edge | `tools/worldgen/tamriel_vectors.json` (ranges, areas, rivers, lakes, peaks in the window), `tools/worldgen/hydrology*.py` inputs, regenerated rasters | `hydrology_report.py`: >= 6 named rivers reach the bay, every lake has an outlet. Relief: p90 local relief per realm (1 km windows) >= Skyrim's median hold. Every realm has >= 1 XL landform. Spearman rank correlation between our elevation and Daggerfall's (at 1 km) >= 0.6 inside the window (`.reference`-only check). Road plan still passes after the rebake |
| **IB7 Dungeons** | Variants per section 8, marquee data plans for 9 realms, new room kits (`breton_keep_ruin`, `scorpion_den`) | `places/*.json` variants, `dungeons/marquee/**`, dungeon kit data | `d_identity` = 1.0 for the bay. >= 5 palettes and >= 8 rosters per realm. 9 marquee plans validate |
| **IB8 Stories and factions** | Realm storylines, 10 order lines, coven bargains, bloodline line, lore-web thread, region voice | `quests/**` (bay realms), `tools/marquee/loreweb.json`, region voice data | `novelty_lint.py --strict` 0 pairs. Every realm story hook reachable from its first town (F-HOOK). The quest validators pass |

---

## 12. Numbers to remember

* 1 Daggerfall map pixel = 819 m in Daggerfall = 6.22 Skycraft blocks. Daggerfall and Wayrest are exact; Sentinel
  moves 224 east.
* Window: 12.4 km² of land, 9 realms, 44 fiefs.
* Place counts: 15,251 Daggerfall locations become about 176 places (14.2/km²). Of those, 65 are settlements (3
  capitals, 6 cities, 11 towns, 23 fief keeps, 6 villages, 10 farms, 6 inns) and 111 are dungeons and unique sites.
* Reconciliation: 274 existing places sit inside the window; 37 Daggerfall namesakes move; marquee and lore-web
  places stay.
