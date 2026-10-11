# The world of Nirn

Skycraft builds its own overworld: terrain, biomes, rivers, settlements, ruins and roads. It uses no third-party
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

There are **79 regions**. Each has its own biome palette, weather, music, culture, and a level band. Danger rises
with distance from Whiterun. `/skycraft-tamriel where` (operators) names the region you stand in.

| Province | Regions (level band) |
| --- | --- |
| Skyrim | Whiterun Hold (1–10), Falkreath Hold (5–22), Hjaalmarch (5–24), Haafingar (5–24), The Rift (6–26), The Pale (6–26), Eastmarch (8–28), The Reach (8–30), Winterhold (10–30) |
| Cyrodiil | Heartlands, Colovian Highlands, Great Forest, Jerall Mountains, Nibenay Basin, Nibenay Valley, West Weald, Gold Coast, Blackwood (5–28) |
| Morrowind | Solstheim, Stonefalls, Deshaan, Ascadian Isles, West Gash, Bitter Coast, Grazelands, Ashlands, Molag Amur, Azura's Coast, Telvannis, Sheogorad, Red Mountain (15–45) |
| High Rock | Wayrest, Bjoulsae River, Stormhaven, Glenumbra, Wrothgar, Rivenspire (15–42) |
| Hammerfell | Bangkorai, Craglorn, Alik'r Desert, Hew's Bane, Dragontail Mountains, Stros M'Kai (18–45) |
| Black Marsh | Shadowfen, Stormhold, Gideon, Helstrom, Lilmoth, Murkmire (18–45) |
| Elsweyr | Anequina, Rimmen, Torval, Pellitine, Senchal (18–45) |
| Valenwood | Reaper's March, Grahtwood, Malabal Tor, Greenshade (18–45) |
| Summerset | Auridon, Summerset Isle, Artaeum (22–45) |
| Seas | Sea of Ghosts, Iliac Bay, Topal Bay, Inner Sea, Abecean Sea, Padomaic and Eltheric Oceans, the open sea |
| Beyond Tamriel | Atmora (ice waste, frozen forest), Akavir (Tsaesci, Kamal, Tang Mo, Ka Po' Tun), Yokuda (desert, sunken isles), Pyandonea, Thras (30–60) |

## Biomes

There are **113 biomes**, each with its own blocks, trees, grasses and sky. A region picks among its palette by
terrain (coast, river, lake, marsh, lowland, upland, peak, cave) and by local climate, and borders blend.

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
  * Hammerfell: the Alik'r and the Dragontail Mountains.
  * High Rock: moors, farmland and the Wrothgarian peaks.
* **Underground:** Dwemer caverns, Blackreach-like Falmer glow caves, ice caves, lava caves and mushroom grottos,
  alongside vanilla's dripstone, lush and deep dark caves.

`/skybiome` (operators) shows why a biome was chosen at a position.

## Places

There are **602 named places**, each at its real position:

| Kind | Count |
| --- | --- |
| Capitals and cities | 18 capitals, 30 cities |
| Towns, villages and farms | 14 towns, 19 villages, 22 farms |
| Named dungeons (barrows, caves, mines, Dwemer ruins, forts...) | 266 |
| Forts | 27 |
| Landmarks | 29 |
| Daedric shrines (all 16 Princes in each of 8 provinces, plus Skyrim's own) | 133 |
| Standing Stones | 17 (the 13 guardian stones, with the Warrior, Mage and Thief together, and Solstheim's 6 All-Maker stones) |
| Dragon mounds | 24 |
| Word Walls with a fixed place | 3, plus 46 landmarks that hold a wall |

* **Skyrim's settlements:**
  * The nine hold capitals are built to their own layouts: Whiterun, Solitude, Windhelm, Riften, Markarth, Falkreath,
    Morthal, Dawnstar and Winterhold.
  * Also built by hand: Helgen, the College of Winterhold, Dragon Bridge and Raven Rock.
  * Then the towns, villages and farms: Riverwood, Rorikstead, Ivarstead, Karthwasten, Kynesgrove, Shor's Stone,
    Darkwater Crossing, Old Hroldan, Soljund's Sinkhole, Stonehills and more.
* **Provincial capitals,** also hand-planned: the Imperial City on its island in Lake Rumare, Mournhold, Vivec,
  Alinor, Sentinel, Wayrest, Orsinium, Elden Root, Lilmoth and Torval. Each province's other cities (Bruma, Chorrol,
  Anvil, Balmora, Daggerfall, Rimmen and others) stand at their lore positions.
* **Culture kits.** Settlements are built from 14 culture kits with 27 palettes. Nord alone has ten styles, from
  Whiterun timber to Markarth stone; there are Dunmer Hlaalu, Redoran, Telvanni and Velothi; and Imperial, Argonian,
  Khajiit, Bosmer, Altmer, Redguard, Breton, Orc, Akaviri, Atmoran, Yokudan and Maormer.
* **Unique sites.** High Hrothgar and the Seven Thousand Steps, the Throat of the World, Bleak Falls Barrow (with its
  claw door), Labyrinthian, Ustengrav, Saarthal, Ysgramor's Tomb, Forelhost, Sky Haven Temple, Kolskeggr Mine, the
  shrines of Azura and Meridia, and **Blackreach**, which runs from Mzinchaleft's Great Lift past the Tower of Mzark to
  the Alftand and Raldbthar lifts.
* **Generic sites** fill the gaps by culture:
  * Skyrim: bandit camps, caves and dens, forts, watchtowers, lighthouses, shipwrecks, giant camps, hagraven lairs,
    vampire lairs, mines, groves, Forsworn camps and the Legion's and Stormcloaks' camps.
  * The provinces' ruins: Ayleid ruins, Velothi tombs and Daedric ruins, xanmeer, moon temples, tree tombs, crystal
    vaults, sea vaults, coral pits, Yokudan and Atmoran tombs, and Akaviri ruins.
  * Generic towns, villages, hamlets, farmsteads and roadside taverns.

## Beyond Tamriel

* **Atmora** lies to the north (frozen), **Akavir** to the east (Tsaesci jungles, Kamal snowfields, the Tang Mo
  islands, the Ka Po' Tun steppe), **Yokuda** to the west (desert and a sunken archipelago), and **Pyandonea** and
  **Thras** to the south-west (jungle isles and coral).
* **Endless lands.** Further out, the world never ends. Every 12,288-block cell holds an ocean, an archipelago or a
  continent. Each landmass has a generated "Uncharted" name, its own regions and one of ten realm types (frozen,
  temperate, ash, swamp, desert, jungle, crystal, savanna, mushroom, alpine). These lands reuse every biome, and have
  their own caves, forts, camps and expeditions.

## Travel

* **Discovery.** Reaching a place shows a "DISCOVERED" banner and adds it to your map. Entering a new region or
  province shows its name.
* **The map** (**M**) is painted parchment with relief, coasts, rivers and borders. It shows province and region
  names, the places you know, quest targets, your party, other players and a custom marker. Unexplored land stays
  under fog of war.
* **Fast travel.** Pick any discovered place. It isn't allowed while you're in combat, with enemies within 16
  blocks, over-encumbered, riding, falling, in jail, or to another dimension. In singleplayer it advances the clock
  one hour per 400 blocks. Survival Mode can block it entirely (`blockFastTravel`).
* **Carriages.**
  * Drivers wait outside capitals and cities. They go to every capital and city of their own province, plus any
    you've discovered in other provinces, up to 14 destinations.
  * Fares inside Skyrim are 20–50 Septims and grow with distance elsewhere.
  * Routes across open sea aren't offered. Ships are planned.
* **Silt striders** link Vvardenfell's ports.
* **Horses.** Six breeds are sold at stables, and wild horses can be tamed. Call yours with **H**. Riding trains the
  Riding skill.
* **Roads.**
  * Roads join neighbouring settlements, with bridges, signposts and lanterns.
  * Khajiit caravans, travellers and guard patrols walk them, and bandits lie in wait.
  * Road building runs on a per-tick budget (`skycraft-roads.toml`).

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
