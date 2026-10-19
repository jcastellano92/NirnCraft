# The world of Nirn

NirnCraft builds its own overworld: terrain, biomes, rivers, settlements, ruins and roads. It uses no third-party
terrain or structure mods. This page is the player's view of that world. The technical plan, with the Java contracts
and data formats, is in [TAMRIEL.md](TAMRIEL.md).

## Scale and orientation

* **One block is about one metre.** Skyrim is about 6,000 blocks wide, roughly the walkable size of the game.
  Tamriel keeps its real proportions, about 18,000 × 16,000 blocks.
* **Whiterun is block 0, 0.** North is −Z and east is +X, as on Minecraft's compass. Skyrim spans about
  x −3180…2830 and z −2480…1880.
* **The world is taller than vanilla:** from y −64 to y 383. The Throat of the World peaks near y 370, and sea level
  is y 63.
* **The layout is the same in every world.** The seed changes only the local detail: hills, trees, caves, and where
  generic sites go.
* **New players arrive on the road just north of Helgen.** Once a race is chosen, the main quest opens with the
  prisoner cart at Helgen.

## Provinces and regions

There are **83 regions** (75 land regions across 11 province groupings plus 8 seas). Each has its own 11-layer causal
world-model sheet (`WorldSheets`), biome palette, 6+ micro-biomes, weather, music, culture, festival calendar, and a
level band (`max - min <= 20`). Danger rises with distance from Whiterun. `/nirncraft-tamriel where` and
`/nirncraft-world sheet <region>` (operators) inspect the region you stand in.

| Province | Regions (level band) |
| --- | --- |
| Skyrim | Whiterun Hold (1–10), Falkreath Hold (5–22), Hjaalmarch (5–24), Haafingar (5–24), The Rift (6–26), The Pale (6–26), Eastmarch (8–28), The Reach (8–28), Winterhold (10–30) |
| Cyrodiil | Heartlands, Colovian Highlands, Great Forest, Jerall Mountains, Nibenay Basin, Nibenay Valley, West Weald, Gold Coast, Blackwood (5–28) |
| Morrowind | Solstheim, Stonefalls, Deshaan, Ascadian Isles, West Gash, Bitter Coast, Grazelands, Ashlands, Molag Amur, Azura's Coast, Telvannis, Sheogorad, Red Mountain (12–42) |
| High Rock | Daggerfall, Glenumbra, Stormhaven, Wayrest, Rivenspire, Kambria, Northpoint, Bjoulsae River, Wrothgar, Bangkorai Pass, Western Reach, Dellese Isles (12–38) |
| Hammerfell | Sentinel, Alik'r Desert, Myrkwasa, Satakalaam, Lainlyn, Bergama, Sunforge, Hew's Bane, Taneth, Rihad, Craglorn, Stros M'Kai, Dragontail Mountains (14–40) |
| Black Marsh | Shadowfen, Stormhold, Gideon, Helstrom, Lilmoth, Murkmire (16–40) |
| Elsweyr | Anequina, Rimmen, Torval, Pellitine, Senchal (16–40) |
| Valenwood | Reaper's March, Grahtwood, Malabal Tor, Greenshade (16–40) |
| Summerset | Auridon, Summerset Isle, Artaeum (20–42) |
| Seas | Sea of Ghosts, Iliac Bay, Topal Bay, Inner Sea, Abecean Sea, Padomaic and Eltheric Oceans, the open sea |
| Beyond Tamriel | Atmora (ice waste, frozen forest), Akavir (Tsaesci, Kamal, Tang Mo, Ka Po' Tun), Yokuda (desert, sunken isles), Pyandonea, Thras (28–50) |

## Biomes and Micro-Biomes

There are **113 biomes** and **533 micro-biomes**, each with its own blocks, trees, grasses, weather, ambience and
spawn tables. A region picks among its palette by terrain (coast, river, lake, marsh, lowland, upland, peak, cave) and
evaluates `6–8` causal micro-biome rules driven by elevation, slope, aspect, rainshadow (`rainshadow.png`), geothermal
heat (`heat.png`), water distance and ridge proximity (`>= 3` micro-biome transitions per km of road).

* **Skyrim:** Whiterun's tundra and plains, pine and snowy pine forests, misty Falkreath, the Rift's autumn aspens,
  the Reach's red crags, Hjaalmarch's marshes, Eastmarch's volcanic tundra and hot springs, the Pale's snowfields,
  Winterhold's glacier, the Throat of the World, the iceberg-strewn Sea of Ghosts, and Solstheim's ash and pines.
* **Morrowind:** ash wastes with ash storms and lava vents, Red Mountain, the Bitter Coast, the Grazelands, the
  Ascadian Isles, Azura's Coast, and the Telvanni forests of giant mushrooms.
* **Cyrodiil:** the Heartlands, the Great Forest, the Colovian highlands, the Gold Coast, the West Weald's vineyards,
  the Nibenay and Blackwood.
* **The rest:**
  * Black Marsh: swamps, mires, mangroves and Hist groves.
  * Elsweyr: badlands, dunes, savanna and moon-sugar fields.
  * Valenwood: rainforest and walkable Graht-oaks.
  * Summerset: crystal coasts and flowering meadows.
  * Hammerfell & High Rock (Iliac Bay): the Alik'r, Sunforge mesas, Dragontail peaks, Glenumbra moors, Bjoulsae riverlands and Wrothgarian peaks.
* **Underground:** Dwemer caverns, Blackreach-like Falmer glow caves, ice caves, lava caves and mushroom grottos,
  alongside vanilla's dripstone, lush and deep dark caves.

`/skybiome` (operators) shows why a biome and micro-biome were chosen at a position.

## Places

There are **7,546 named places**, each at its real position and linked to its region's causal world-model sheet:

| Kind | Count |
| --- | --- |
| Capitals and cities | 18 capitals, 64 cities |
| Towns, villages and farms | 222 towns, 487 villages, 154 farms (`354` bespoke cityplans) |
| Named dungeons (barrows, caves, mines, Dwemer ruins, Ayleid ruins, xanmeer...) | 1,869 (including `118` bespoke marquee dungeon plans) |
| Forts | 231 |
| Camps | 1,303 |
| Shrines and sacred groves | 505 shrines, 172 groves |
| Landmarks, standing stones, dragon mounds and unmarked sites | 2,521 |

* **Skyrim's settlements:**
  * The nine hold capitals are built to their own multi-tiered layouts with real connecting stone stairs, recognizable
    trade shops, multi-room inns, and grand Jarl halls: Whiterun, Solitude, Windhelm, Riften, Markarth, Falkreath,
    Morthal, Dawnstar and Winterhold.
  * Also built by bespoke cityplans: Helgen, the College of Winterhold, Dragon Bridge, Raven Rock, Riverwood,
    Rorikstead, Ivarstead, Karthwasten, Kynesgrove, Shor's Stone, Darkwater Crossing, Old Hroldan, Soljund's Sinkhole,
    Stonehills, Narzulbur, Mor Khazgur, Dushnikh Yal, Largashbur, Half-Moon Mill, Anga's Mill, Mixwater Mill,
    Solitude Sawmill, Honningbrew Meadery, Nightgate Inn, the Thalmor Embassy and Amol.
* **Provincial capitals and cities,** also hand-planned (`354` cityplans across Tamriel): the Imperial City on its
  island in Lake Rumare, Mournhold, Vivec, Alinor, Sentinel, Wayrest, Daggerfall, Orsinium, Elden Root, Lilmoth and
  Torval, plus every major city and town across all 11 province groupings.
* **Culture kits.** Settlements are built from 14 culture kits with 27 palettes. Nord alone has ten styles, from
  Whiterun timber to Markarth stone; there are Dunmer Hlaalu, Redoran, Telvanni and Velothi; and Imperial, Argonian,
  Khajiit, Bosmer, Altmer, Redguard, Breton, Orc, Akaviri, Atmoran, Yokudan and Maormer.
* **Unique sites.** High Hrothgar and the Seven Thousand Steps, the Throat of the World, Bleak Falls Barrow (with its
  claw door), Labyrinthian, Ustengrav, Saarthal, Ysgramor's Tomb, Forelhost, Sky Haven Temple, Kolskeggr Mine, the
  Adamantine Tower (Direnni Tower), Fang Lair, Salas En, Orsinium Old Ruin, Sancre Tor, Miscarcand, Dagoth Ur,
  Sotha Sil's Clockwork City entrance, and **Blackreach**, which runs from Mzinchaleft's Great Lift past the Tower of
  Mzark to the Alftand and Raldbthar lifts.

## Beyond Tamriel

* **Atmora** lies to the north (frozen), **Akavir** to the east (Tsaesci jungles, Kamal snowfields, the Tang Mo
  islands, the Ka Po' Tun steppe), **Yokuda** to the west (desert and a sunken archipelago), and **Pyandonea** and
  **Thras** to the south-west (jungle isles and coral).
* **Outer ocean and world loop.** Beyond Tamriel and the fixed foreign lands, new terrain stays ocean.
  Far-ocean travel wraps to the opposite east/west or north/south edge, including diagonal crossings.
  The default seam is 29,360,128 blocks from the origin; `nirncraft-ocean-server.toml` controls the travel
  distance. Destination chunks are prewarmed asynchronously. Existing saved chunks are preserved; this
  policy does not erase previously explored land. The former Endless Lands planner is dormant.

## Travel

* **Discovery.** Reaching a place shows a "DISCOVERED" banner and adds it to your map. Entering a new region for the
  first time shows its region banner with local rumours, plays a regional music sting, and delivers a narrator arrival
  hook. Undiscovered places within 150 blocks appear as a subtle compass hint.
* **The map** (**M**) is painted parchment with relief, coasts, rivers and borders. It shows province and region
  names, the places you know, quest targets, your party, other players and a custom marker. Unexplored land stays
  under fog of war.
* **Fast travel.** Pick any discovered place. It isn't allowed while you're in combat, with enemies within 16
  blocks, over-encumbered, riding, falling, in jail, or to another dimension. In singleplayer it advances the clock
  one hour per 400 blocks. Survival Mode can block it entirely (`blockFastTravel`).
* **Multi-modal travel network (`TravelNetworks`).** Every province has culturally authentic travel routes (`p90 <= 500`,
  `max <= 800` blocks to a travel node on land):
  * **Carriages & caravans:** connect hold capitals, provincial cities, and towns along highway corridors.
  * **Ships, ferries & river boats:** sail open-water A* sea routes around headlands (Sea of Ghosts, Iliac Bay,
    Abecean, Topal Bay, Inner Sea, Padomaic Ocean) and major rivers (White River, Niben, Bjoulsae).
  * **Silt striders, boats, propylon indices, Mages Guild guides & Divine/Almsivi Intervention:** link Morrowind's
    mainland and Vvardenfell.
  * **Standing stones & wilderness wayshrines:** act as free travel and blessing nodes across every region.
* **Horses.** Six breeds are sold at stables, and wild horses can be tamed. Call yours with **H**. Riding trains the
  Riding skill.
* **Roads.**
  * Roads join neighbouring settlements, with switchbacks, bridges, tunnels, signposts (naming the next settlement,
    roadside inn, and nearby dungeons) and lanterns.
  * Khajiit caravans, travellers and guard patrols walk them, and 125+ regional encounter scenes (including non-hostile
    wonder moments) unfold along them while keeping road hostility quiet (`<= 5%` day, `<= 20%` night).
  * Road building runs on a per-tick budget (`nirncraft-roads.toml`).

## Weather and sky

* **Weather by region.** Each region has its own weather, chosen by the server from its climate:
  * clear, cloudy, overcast and fog;
  * drizzle, rain and storms;
  * snow and blizzards;
  * ashfall and ash storms;
  * dust and sandstorms.
* **The night sky.** Masser and Secunda, a galaxy band, the thirteen birthsign constellations, shooting stars, and
  auroras on clear northern nights.

## Other realms

* **Oblivion** is the Nether, and **Sovngarde** is the End, where Alduin waits at the end of the main quest. Each
  has its own fog, music and discoveries. Quests take you to both.
* **Jail** is a dimension of its own, with a private cell for each prisoner.
