# Valenwood: the uncut forest of the Green Pact

Status: plan (docs only, 2026-10-13). Nothing in the mod has changed. Phase prefix **VW**.

Read first: `docs/world/DESIGN-PRINCIPLES.md` (first principle, sections 0, 1, 4, 5), `docs/world/SCALE-AND-GEOGRAPHY.md`
(5.7, 6, 7), `docs/MARQUEE.md` (Valenwood), `docs/LORE-WEB.md` (val_1 to val_4, t_welkynd, t_crown, t_falls,
t_sugar), `docs/PARITY.md`. Places: `tamriel/places/valenwood.json` (4 regions, 351 places).

**Legal line.** Lore facts in our own words; Arena gives relative positions only (fit 41.4 blocks per Arena pixel:
Arena drew Valenwood about 1.8 times too large, so the scale is 40, not 70; rms 1,797 blocks). Invented names, lines
and designs are ours.

**Parity snapshot** (2026-10-13). Province 24.2 km², mean 44: SET 35, NPC 22, QST 33, DUN 61, CRE 58, FLO 82, STY 33,
LND 69, LOR 18, MUS 54, ITM 16. Valenwood has the most landmark kinds in Tamriel after Summerset (15.5 per region) and
the best settlement looks outside Skyrim and Morrowind (2.25), and almost no people, quests or lore behind them (0.56
named buildings and 3.2 named NPCs per named town; dungeon identity 5%).

**Not Skyrim in a new colour.**

| Skyrim has | Valenwood has instead (sheet cause) |
| --- | --- |
| Towns in clearings ringed by farms and lumber mills | Cities grown into living giant trees, no fields, no mills: the forest is thicker near people, not thinner (L4 `pact_forest`) |
| Status by wealth and blood | Status by height: crown, trunk, bough, root, ground (L7 `vertical_rank`) |
| Roads cut through forests | Root-paths that bend round every trunk, canopy ways between city-trees, river boats (L5, L6) |
| Bread, mead, timber, iron | Meat, jagga, bone, hide and sap; grain, wood and iron bought from Khajiit and Imperials (L5 `pact_imports`) |
| Draugr in barrows | The dead returned to burial trees and root tombs; Ayleid ruins beneath; spriggans and strangling green (L10) |
| Jarls and housecarls | Treethanes, the Silvenar and the Green Lady, Spinners who keep the people's story (L7, L8) |
| A city that stays put | A city that walks between four seasonal sites (L6 `walking_city`) |

---

## 1. World model sheet

Data target: `tamriel/world/valenwood.json` (MS22). Field ids are data ids; cites in brackets; `P.` is the province.

### 1.1 Province world truth

Valenwood is the forest that its people have sworn never to harm. The Bosmer keep the Green Pact: no living plant of
Valenwood may be cut or eaten by them, so they live on meat, bone, hide and sap, and buy their grain and wood from
Khajiit and Imperial traders. Their cities grow inside giant graht-oaks, and status rises with height, from the poor
on the ground to the Treethane in the crown; one city walks between four seasonal sites. Ayleid ruins lie under the
roots. Elden Root is the Dominion's forest capital, where the Thalmor stand beside a Camoran king, while the Silvenar
and the Green Lady speak for the people and the Pact. Colovian poachers press on the Strid, Maormer raid the coast,
and the March keeps an old truce with the Khajiit.

### 1.2 Province sheet (P)

| Layer | Fields `id`: meaning [cites] |
| --- | --- |
| L1 Geology | `forest_basin`: a low basin of deep humus over old sediments, little exposed rock. `river_gorges`: the Xylo and the Strid and their feeders cut 20-40 blocks below the forest floor. `march_knolls`: sandstone knolls and low ridges in Reaper's March, to 60-120 blocks. `karst_sinks`: sinkhole lakes where limestone lies under the humus. `delta_coast`: mangrove deltas and drowned river mouths on the south and west coast. `poor_ore`: little metal near the surface, some bog iron only |
| L2 Climate | `warm_wet`: warm and wet all year, wettest in the south-west [forest_basin, delta_coast]. `under_canopy`: below the crowns the floor is dim, still and humid [warm_wet]. `drier_march`: Reaper's March is drier and seasonal, grading into Elsweyr's savanna [march_knolls]. `south_storms`: sea storms and sea mists from the south-west [delta_coast]. `four_seasons_felt`: the forest's seasons show as leaf colour, fruiting and migration rather than snow [warm_wet] |
| L3 Water | `river_net`: the Xylo in the north-west, the Strid on the Cyrodiil border, and many forest rivers [river_gorges, warm_wet]. `crown_cisterns`: rain caught in the hollows and bromeliad pools of the giant trees; tree-cities drink from their own trees [under_canopy]. `sink_lakes` [karst_sinks]. `who_holds_water`: water is everywhere; what is held is the trees: Treethanes control the city-trees and so the cisterns, the shelter and the height [crown_cisterns] |
| L4 Ecology | `canopy_layers`: four vertical layers (floor, understorey, canopy, emergent crowns), each with its own life [under_canopy]. `graht_oaks`: giant trees in groves on the deepest soils, the only places large enough for cities [forest_basin]. `pact_forest`: the forest grows uncut wherever Bosmer live, because their covenant forbids harming its plants; old growth reaches to every doorstep [graht_oaks, canopy_layers]. `game_abundance`: deer, boar, wild pigs, birds and timber mammoths in glades [pact_forest]. `predators`: big cats in the March, giant spiders and wasps in the understorey, hoarvors in wet hollows, imga in the canopy [canopy_layers, river_net]. `green_hostiles`: spriggans, stranglers and walking plant-beasts guard old groves [pact_forest] |
| L5 Economy | `meat_and_jagga`: hunting, pig-keeping and smokehouses; jagga is fermented from pig's milk [game_abundance]. `bone_hide_craft`: bows of horn and bone, armour of hide and bone, because local wood may not be cut and metal is scarce [pact_forest, poor_ore]. `pact_imports`: grain, bread, timber, paper, wine and iron bought from Elsweyr and Cyrodiil [pact_forest]. `sap_given`: graht-oaks yield sap through openings sung into the bark; tapping is a priestly craft, not cutting [graht_oaks, pact_forest]. `ports`: Woodhearth, Haven, Southpoint and Velyn Harbor import what the Pact forbids [delta_coast, pact_imports]. `pelts_and_feathers`: the export trade [game_abundance] |
| L6 Settlement | `tree_cities`: cities stand where graht-oaks stand, not where rivers cross [graht_oaks, crown_cisterns]. `walking_city`: Falinesti, a giant tree that migrates among four seasonal sites [graht_oaks, four_seasons_felt]. `delta_ports` [ports]. `march_border_towns`: the March's towns sit on knolls and fords on the Elsweyr and Cyrodiil roads, mixed Bosmer and Khajiit [march_knolls, pact_imports]. `busy_centres`: Elden Root (capital and Dominion seat), Woodhearth (port), Falinesti (wherever it stands), Arenthia and Rawl'kha (border trade) [tree_cities, ports, pact_imports]. `poor_places`: ground camps under the city-trees, hunting villages whose game has thinned, coastal villages after a raid [tree_cities, game_abundance] |
| L7 Society | `vertical_rank`: the higher you live the higher you stand: Treethane and court in the crown, craftsmen on the trunk tiers, hunters on the boughs, the poor and outsiders at the roots and on the ground [tree_cities]. `treethanes`: each city's ruler [tree_cities]. `hunters_honoured`: the best hunters are the city's heroes [meat_and_jagga]. `spinners`: priest-storytellers who keep and shape the people's story [tree_cities]. `outsider_traders`: Khajiit and Imperial merchants handle plant goods the Bosmer cannot, so they are needed and kept low [pact_imports]. `pact_breakers`: Bosmer who break the Pact are cast out to the ground or beyond [pact_forest] |
| L8 Culture | `yffre_faith`: Y'ffre the Storyteller, giver of the Pact; shrines are story-stones and living groves [pact_forest, spinners]. `meat_mandate`: the slain are to be eaten, within three days, and nothing is wasted [pact_forest, meat_and_jagga]. `wild_hunt`: a rite of transformation into a ravening host, called in extremity and feared by all [pact_forest]. `silvenar_and_lady`: the Silvenar embodies the people's will, the Green Lady is its champion; their bond is the Pact made flesh [treethanes, yffre_faith]. `wilderking`: a forest-spirit protector bound to Greenshade [yffre_faith]. `burial_trees`: the dead are given back to the forest in burial trees and root tombs [pact_forest]. `songs_not_stone`: history is sung and spun, not carved [spinners]. `outsiders`: wary of Colovians, uneasy partners of the Thalmor, rivals and friends of the Khajiit [pact_imports] |
| L9 Politics | `camoran_court`: a Camoran king in Elden Root, seated with Thalmor support; Valenwood is a Dominion province [treethanes]. `thalmor_in_the_crown`: Thalmor envoys live in the crown of the Elden Tree [camoran_court, vertical_rank]. `silvenar_vs_thalmor`: the Silvenar guards the Pact against Dominion "improvements" [silvenar_and_lady, camoran_court]. `strid_border`: Colovian loggers and poachers on the Strid; old border wars [river_net, pact_imports]. `march_truce`: a renewed truce between Bosmer and Khajiit elders in the March [march_border_towns]. `maormer_raids`: Pyandonean raiders on the south-west coast [south_storms, ports]. Festival (MS16): **the Arrival** (when Falinesti reaches its winter site: a great hunt and a feast with no plant on the table) [walking_city, meat_mandate] |
| L10 Hidden | `ayleid_vaults`: Ayleid ruins under the roots; stone survives only where the floor is firm [forest_basin]. `root_tombs`: burial trees and root tombs [burial_trees]. `spriggan_groves` [green_hostiles]. `wild_hunt_grounds`: sites where hunts were called; still wrong [wild_hunt]. `poacher_camps` on the Strid [strid_border]. `maormer_beachheads` [maormer_raids]. `feast_lodges`: hunters' lodges where the Mandate is abused into cannibal cults [meat_mandate]. `hircine_packs`: werewolf packs of Hircine's hunters [hunters_honoured]. `septim_forts`: Imperial border forts, now held by bandits, Khajiit or Thalmor [strid_border]. `thalmor_cells`: dissidents held in hollow trees [thalmor_in_the_crown] |
| L11 History | `dawn_pact`: the covenant with Y'ffre in the forest's first age. `ayleid_age`: Ayleid settlement in the north and east. `camoran_line`: an ancient royal line, including a Third Era usurper who overran the province. `imperial_province`: the Septim era, its forts and trading posts. `dominion_union`: early Fourth Era union with Summerset under the Thalmor. `great_war`: Bosmer archers in the Dominion's war against the Empire. `walking_legend`: Falinesti's walk, sung by every Spinner |

### 1.3 Region sub-sheets

**Grahtwood** (`valenwood/grahtwood`, 10.06 km², capital Elden Root, levels 20-42; large: two halves, the north
round Elden Root and the south coast round Haven).
World truth: the heart of the forest and of the Dominion's hold on it. The Elden Tree rises over everything, a city in
one trunk with the Camoran court and the Thalmor in its crown and the poor among its roots. Around it stand old
Ayleid vaults the Thalmor's scholars are reopening, burial trees, timber-mammoth glades and the winter site of
Falinesti. The south coast is a separate world: Haven and Southpoint, delta ports on mangrove roots that buy the grain
and iron the Pact forbids, watching the sea for Maormer sails.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `elden_grove_soil` the deepest soils [P.forest_basin]. `grahtwood_sinks` [P.karst_sinks]. `south_deltas` [P.delta_coast] |
| L2 | `deep_canopy` [P.under_canopy]. `coastal_wet` [P.south_storms] |
| L3 | `elden_cisterns` [P.crown_cisterns]. `lady_solace_fen` [grahtwood_sinks] |
| L4 | `graht_grove`, `old_growth_floor`, `mammoth_glade`, `sink_lake`, `mangrove_delta`, `burial_grove` (section 2) |
| L5 | `sap_tappers` [P.sap_given]. `capital_market` [P.pact_imports]. `delta_trade` Haven and Southpoint [P.ports]. `kwama_tenders` at Burroot (eggs are not plants) [P.meat_and_jagga] |
| L6 | `elden_root_capital` [P.tree_cities, elden_grove_soil]. `haven_port` south seat [south_deltas]. `redfur_post` a trading post for outsiders at the forest edge [capital_market]. `falinesti_winter_site` [P.walking_city] |
| L7 | `crown_court_thalmor` [P.vertical_rank]. `root_poor` [P.vertical_rank]. `delta_merchants` [delta_trade] |
| L8 | `elden_temple` [P.yffre_faith]. `crown_lamp` a light in the Elden Tree's crown, said to show the Pact holds (lore-web val_1) [elden_cisterns] |
| L9 | `court_vs_voice` the Camoran court and the Silvenar's Voice in the same tree [P.silvenar_vs_thalmor]. `ayleid_digs` Thalmor scholars reopening vaults [P.thalmor_in_the_crown] |
| L10 | `ayleid_vaults_grahtwood` (signature theme: Ne Salas, Laeloria, Reliquary of Stars, Ossuary of Telacar) [P.ayleid_vaults, ayleid_digs]. `maarselok_lair` a dragon's lair from the Second Era [P.green_hostiles]. `elden_grove` (Arena's staff dungeon) [P.root_tombs] |
| L11 | `cormount_battle` a border battle remembered by a memorial [P.camoran_line]. `ayleid_north` [P.ayleid_age] |

**Greenshade** (`valenwood/greenshade`, 4.52 km², seat Woodhearth, levels 24-45).
World truth: the wet, misty west, where marsh, mangrove and graht-oak meet the sea. Woodhearth is a city of rope
bridges strung among tall trees, a port the Pact allows to trade with Summerset and Senchal, with the poor camped on
the ground. The Wilderking, the forest's spirit-guardian, holds court in a grove the living rarely find. The burned
Ayleid City of Ash and the shifting Labyrinth lie inland. Maormer raiders camp on the drowned coast, and the
Spinners say the Wilderking has gone quiet.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `drowned_coast` [P.delta_coast]. `mire_basins` [P.forest_basin] |
| L2 | `sea_mists` [P.south_storms]. `wettest_west` [P.warm_wet] |
| L3 | `marsh_water` Gathongor's Mire, Verrant Morass [mire_basins]. `woodhearth_bay` [drowned_coast] |
| L4 | `mire_fen`, `mangrove_delta`, `bridge_canopy`, `wisp_hollow`, `spriggan_grove`, `drowned_beach` (section 2) |
| L5 | `bridge_port` [P.ports]. `net_fishing` [woodhearth_bay]. `reed_weaving` from dead reed only (the Pact allows the fallen) [P.pact_forest] |
| L6 | `woodhearth_seat` [P.tree_cities, woodhearth_bay]. `greenheart` a marsh town inside a hollow tree [mire_basins]. `marbruk` a glade fort-town of outsiders and Bosmer on the road east [P.pact_imports] |
| L7 | `bridge_district_rank` [P.vertical_rank]. `ground_camp` the poorest of Valenwood [P.poor_places] |
| L8 | `wilderking_court` [P.wilderking]. `spinners_of_greenshade` [P.spinners] |
| L9 | `wilderking_silent` the spirit no longer answers [wilderking_court]. `maormer_shore` [P.maormer_raids] |
| L10 | `wilderking_spirits` (signature theme: groves of the Wilderking's corrupted servants) [wilderking_silent]. `city_of_ash` [P.ayleid_vaults]. `labyrinth` [P.ayleid_vaults]. `maormer_camp` [maormer_shore] |
| L11 | `ash_fire` the Ayleid city burned [P.ayleid_age]. `wilderking_line` each Wilderking chosen from the living [P.dawn_pact] |

**Malabal Tor** (`valenwood/malabal_tor`, 3.63 km², seat Silvenar, levels 22-44).
World truth: the deep jungle of the north-west on the Xylo, the Bosmer heartland that the Dominion reaches least.
The city of Silvenar is a hidden court under hollowed root arches, where the Silvenar holds audience and the Green
Lady trains. Falinesti stands here in summer. Orc enclaves keep an uneasy truce with their hunting neighbours, Velyn
Harbor trades on the coast, and the oldest Wild Hunt grounds lie in the jungle where hoarvors breed. Here the Pact is
kept most strictly and the Thalmor are least welcome.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `xylo_gorge` [P.river_gorges]. `jungle_floor` [P.forest_basin] |
| L2 | `hothouse` [P.warm_wet, P.under_canopy] |
| L3 | `xylo_basin` [P.river_net]. `velyn_bay` [P.delta_coast] |
| L4 | `jungle_understorey`, `xylo_gorge_walls`, `hoarvor_hollow`, `root_amphitheatre`, `canopy_high`, `orc_clearing` (section 2) |
| L5 | `strict_hunters` [P.meat_and_jagga]. `velyn_trade` [P.ports]. `orc_smiths` iron work traded to Bosmer [P.poor_ore] |
| L6 | `silvenar_seat` [P.tree_cities]. `falinesti_summer_site` [P.walking_city]. `velyn_harbor` [velyn_bay]. `orc_enclaves` Drabul, Jathsogur [orc_smiths] |
| L7 | `silvenar_court` [P.silvenar_and_lady]. `orc_outsiders` [orc_enclaves] |
| L8 | `strictest_pact` [P.pact_forest]. `green_lady_trials` [P.silvenar_and_lady] |
| L9 | `lady_succession` the Green Lady's trial for a successor [green_lady_trials]. `orc_truce` [orc_enclaves] |
| L10 | `wild_hunt_grounds_xylo` (signature theme) [P.wild_hunt_grounds]. `tomb_of_the_apostates` [P.root_tombs]. `hoarvor_pit` [hoarvor_hollow] |
| L11 | `apostates` Bosmer who once broke the Pact and were sealed in a tree [P.dawn_pact] |

**Reaper's March** (`valenwood/reapers_march`, 5.98 km², seat Arenthia, levels 18-40).
World truth: the borderland where Valenwood meets Elsweyr and Cyrodiil, drier knolls and savanna between groves.
Bosmer and Khajiit share it under a truce renewed with a bone on a stone altar each generation. Arenthia hangs its
gardens over a waterfall; Rawl'kha is a Khajiit city in the trees; old Imperial forts watch the roads. Werewolf
packs of Hircine's hunters run the knolls, the Maw of Lorkhaj glowers on the Khajiit side, and this year one bone of
the truce is missing.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `knolls` [P.march_knolls]. `strid_valley` [P.river_gorges] |
| L2 | `dry_season` [P.drier_march] |
| L3 | `waterdancer_falls` [P.river_net]. `strid` [P.river_net] |
| L4 | `savanna_grove_mosaic`, `knoll_scrub`, `riverine_gallery`, `khajiit_fields`, `henge_heath`, `poacher_scar` (section 2) |
| L5 | `grain_road` the Pact trade from Elsweyr [P.pact_imports]. `khajiit_farming` Khajiit farm here; they are not bound by the Pact [P.pact_imports]. `border_tolls` [P.march_border_towns] |
| L6 | `arenthia_seat` over the falls [waterdancer_falls, knolls]. `rawlkha` Khajiit city [khajiit_farming]. `border_cluster` Eagle Vale, Emperor's Run, Longvale, Wasten Brukbrook (spread to >= 600 apart) [border_tolls] |
| L7 | `mixed_town_rank` Bosmer high, Khajiit low in Arenthia, reversed in Rawl'kha [P.vertical_rank]. `truce_elders` [P.march_truce] |
| L8 | `bone_truce_rite` [P.march_truce]. `moonmont` Khajiit temple [P.outsiders] |
| L9 | `missing_bone` (lore-web val_4) [bone_truce_rite]. `colovian_pressure` [P.strid_border] |
| L10 | `hircine_packs_march` (signature theme) [P.hircine_packs]. `maw_of_lorkhaj` a sealed Khajiit dark site [moonmont]. `septim_forts_march` Grimwatch, Sphinxmoth [P.septim_forts]. `selenes_web` (Arena's starting dungeon) [P.green_hostiles] |
| L11 | `march_wars` [P.imperial_province]. `thizzrini` an arena of the old wars [march_wars] |

---

## 2. Micro-biomes

Valenwood's mosaic is vertical as much as horizontal. MS21 handles the ground mosaic; the **canopy layers** are a
second axis carried by feature layers and the spawn table (`region_spawns` keys by height band above ground).

### 2.1 The canopy layers (every forest micro-biome)

| Layer | Height above floor | Light and ground | Flora (features) | Creatures | People |
| --- | --- | --- | --- | --- | --- |
| Floor | 0-6 | dim, leaf litter, fungus, roots, standing water | ferns, fungi, root mats | hoarvors, spiders, boar, snakes | ground camps, root tombs, trails |
| Understorey | 6-20 | green gloom, vines | palms, saplings, lianas | giant wasps, imga young, stranglers | hunting hides |
| Canopy | 20-45 | bright, windy | crown leaves, epiphytes, fruit | imga, birds, monkeys | rope bridges, bough platforms |
| Emergent | 45-110+ | sun, storms | graht-oak crowns only | eagles, wasp nests | crowns of city-trees, lookouts, Thalmor quarters |

### 2.2 Ground micro-biomes (>= 6 per region)

**Green Pact rule (lint).** Bosmer settlements have **no farmland ring, no lumber site and no felled clearing**. The
MS21 disturbance mask is inverted for culture `bosmer`: old growth is densest round their towns. Fields, orchards and
lumber are allowed only in Khajiit and Imperial settlements, and each must cite `P.pact_imports` or
`reapers_march.khajiit_farming`. Forbidden pairs: `bosmer` + `industry:lumber`; `bosmer` + `crop_field`;
`bosmer` + `wood_fence`.

| Region | Micro-biome (key) | Rule (MS21) | Cause | Flora and ground | Weather | Creatures | Work |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Grahtwood | `graht_grove` | deepest soil cells, rare | elden_grove_soil | giant oaks 60-110, moss, root arches | still | imga, eagles | city-trees, sap tapping |
| Grahtwood | `old_growth_floor` | LOWLAND forest within 400 of a Bosmer settlement | P.pact_forest | dense uncut forest to the door | dim | deer, boar | hunting |
| Grahtwood | `mammoth_glade` | natural glades (treefall), slope < 0.2 | P.game_abundance | open grass, fallen giant trunk | sun shafts | timber mammoths, deer | hunting grounds |
| Grahtwood | `sink_lake` | karst sink cells | grahtwood_sinks | clear pool, ferns | mist | fish, birds | shrines |
| Grahtwood | `mangrove_delta` | coast and river mouth, south half | south_deltas | mangrove roots, mud | sea fog | crabs, crocodile-like reptiles | ports on roots |
| Grahtwood | `burial_grove` | near burial-tree places | P.burial_trees | old trees hung with tokens | quiet | crows | funerals |
| Grahtwood | `fen_of_solace` | marsh context near the fen | lady_solace_fen | reeds, drowned trees | fog | hoarvors | none |
| Greenshade | `mire_fen` | MARSH context | marsh_water | reeds, cypress-like trees | fog | stranglers, hoarvors | reed gathering |
| Greenshade | `mangrove_delta` | coast | drowned_coast | mangrove | sea mist | crabs | fishing |
| Greenshade | `bridge_canopy` | within 300 of Woodhearth | woodhearth_seat | tall straight trees, bridges | breeze | birds | the city |
| Greenshade | `wisp_hollow` | low shaded hollows | sea_mists | pale fungus, will-o-lights | mist at night | wisps | none |
| Greenshade | `spriggan_grove` | old grove cells | P.green_hostiles | flowering vines over ruins | still | spriggans, lurching plant-beasts | none |
| Greenshade | `drowned_beach` | BEACH near drowned coast | drowned_coast | dead trees in surf | storms | crabs, Maormer | wreck salvage |
| Malabal Tor | `jungle_understorey` | LOWLAND default | hothouse | lianas, giant ferns | rain | wasps, spiders | hunting |
| Malabal Tor | `xylo_gorge_walls` | river gorge slope > 0.6 | xylo_gorge | hanging roots, falls | spray | birds | river boats |
| Malabal Tor | `hoarvor_hollow` | wet shaded floor | P.predators | standing water, rot | still | hoarvors | none |
| Malabal Tor | `root_amphitheatre` | Silvenar's site | silvenar_seat | arching roots, fireflies | dusk mist | fireflies | the court |
| Malabal Tor | `canopy_high` | UPLAND forest | P.canopy_layers | tallest crowns | wind | imga | lookouts |
| Malabal Tor | `orc_clearing` | Orc enclaves only (outsiders, cite orc_enclaves) | orc_smiths | stumps, forge smoke | smoke | none | smithing (a Pact tension) |
| Reaper's March | `savanna_grove_mosaic` | LOWLAND, rainshadow 0.3-0.6 | dry_season | grass with tree islands | dry wind | big cats, deer | hunting |
| Reaper's March | `knoll_scrub` | knoll tops | knolls | thorn, red rock | heat | lizards, wolves | watch posts |
| Reaper's March | `riverine_gallery` | within 32 of the Strid | strid | tall riverside trees | mist | birds, otters | ferries |
| Reaper's March | `khajiit_fields` | ring round Khajiit settlements only | khajiit_farming | grain, cane, fences | n/a | none | farming |
| Reaper's March | `henge_heath` | round Reaper's Henge | bone_truce_rite | short grass, bone-white stones | moonrise | moths | rites |
| Reaper's March | `poacher_scar` | disturbance on the Strid border | colovian_pressure | stumps, sawdust: a scandal | n/a | none | quest sites |

---

## 3. Map changes and geography

### 3.1 Relief (SCALE-AND-GEOGRAPHY 7): vertical by trees, not hills

| Measure | Now | Target | How |
| --- | --- | --- | --- |
| Height p50 | 18 | 22 | slight basin rise; keep it lowland |
| Height p90 | 35 | 60 | the March knolls (60-120), gorge rims |
| Ruggedness | 1.2 | 2.5 | the Xylo and Strid gorges cut 20-40 below the floor; sinkholes |
| Vertical interest | giant trees only at Elden Root | **graht-oak groves** 60-110 blocks at every city and 1 per 2 km² in the wild | `GIANT_TREE` builder variants; groves as `areas` with a tree-height field |

### 3.2 Coast and water

* Mangrove deltas at Haven, Southpoint, Woodhearth and Velyn Harbor (delta `rivers` with channel braiding, the same
  technique as Black Marsh but with tree cover).
* The Xylo (Malabal Tor) and the Strid (the Cyrodiil border) as named gorges with falls; Waterdancer Falls on a Strid
  feeder below Arenthia.
* Sinkhole lakes in Grahtwood (8-12 small `lakes` entries).

### 3.3 Place fixes

| Fix | Why |
| --- | --- |
| `falinesti_summer_site` is typed `dungeon_fixed`; make all four seasonal sites the same type (`landmark` + a seasonal-city flag) | The walking city must be one plan at four sites (MARQUEE) |
| Spread the March cluster (Eagle Vale, Emperor's Run, Longvale, Wasten Brukbrook) so no two are closer than ~600 blocks (SCALE 5.7) | Arena's generated cluster sits on top of itself |
| Seat of Malabal Tor = Silvenar (regions.json has Falinesti, which moves) | A seat must not walk away |
| Grahtwood south half seat = Haven (city) | Large region rule: two halves |
| 16 Daedric shrines: keep Hircine (hunters), Namira (feast lodges), one Molag Bal (Thalmor cells nearby, hidden); convert the rest to Y'ffre story-stones and Jephre groves | Bosmer worship Y'ffre; Daedric shrines need an L10 cause |
| Rename stone-and-city Arena names that cannot be Bosmer (Stonesquare, Stone Fell, Heimdar City, Karthdar, Black Park) **unless** kept as Septim trading posts (stone buildings at the forest edge, cite `P.imperial_province`). Proposal: keep Stonesquare and Heimdar City as old trading posts, rename the rest from a Bosmer name pool | Arena's generator, not lore |

### 3.4 XL silhouettes (weenies)

| Region | Target XL | Silhouettes |
| --- | --- | --- |
| Grahtwood | 4 | the Elden Tree (110, glowing crown); Falinesti at its winter site; a wild graht grove; Haven's delta lighthouse-tree |
| Greenshade | 2 | Woodhearth's bridge-web; the Wilderking's grove crown seen over the mist |
| Malabal Tor | 2 | Falinesti in summer; the Xylo falls gorge |
| Reaper's March | 2 | Arenthia over the falls; Reaper's Henge on its knoll; Falinesti in autumn |

---

## 4. Roads, travel, fast travel

### 4.1 Roads that do not cut the forest

**Rule.** Valenwood roads are never cleared corridors. The road planner routes round every trunk; the
`PlacementMask` for Valenwood road classes keeps trees and only clears the walking line (2-3 blocks). Surfaces are
root mats, packed earth, bark chips and bone markers, not stone. Three road classes:

| Class | Where | Form |
| --- | --- | --- |
| `root_path` (highway rank) | between cities | a winding ground path round trunks, river fords, bone-cairn waymarks every 300 blocks |
| `canopy_way` | within 300-600 blocks of a city-tree | raised walkways on living boughs and rope bridges at canopy height (20-45), joining city-trees and lookouts |
| `hunt_trail` | to dungeons and glades | a narrow game trail; antler signposts at forks |

### 4.2 Named routes (`roads/named_roads.json`; Arena backbone, SCALE 6.2)

| id (ours) | Route | Notes |
| --- | --- | --- |
| `heartwood_path` | arenthia > cormount > elden_root > redfur_trading_post > woodhearth | east-west spine |
| `silvenar_path` | elden_root > baandari_trading_post > silvenar > falinesti (current site) | the route re-targets with the season |
| `delta_path` | elden_root > haven > southpoint | to the south coast |
| `mire_path` | woodhearth > marbruk > greenheart | Greenshade |
| `grain_road` (cross-province) | rawlkha > arenthia > strid ford > Skingrad | SCALE 6.2 Cyrodiil-Valenwood |
| `pact_gate_road` (cross-province) | rawlkha > valenwood_gate (Pellitine) | Elsweyr trade |

### 4.3 Travel modes (MS11)

| Mode | Nodes | Identity rule | Status |
| --- | --- | --- | --- |
| **River boats** | Xylo (Velyn Harbor, Silvenar landing), Strid (Arenthia, border ford), coast (Haven, Southpoint) | Bosmer boats are hide over bone frames | NEW (river route in ferry code) |
| **Graht-oak lifts** | inside every city-tree (Elden Root, Woodhearth, Falinesti, Silvenar) | a sap-weighted lift between tiers; vertical travel as a city service | NEW (block-based lift, MS11) |
| **Riding the walking city** | Falinesti's four sites | at a season change, a player aboard travels with the city to its next site | NEW (special case of the seasonal flag) |
| **Ships** | Woodhearth (routes to Senchal, Anvil, Alinor, Firsthold, Pyandonea expedition exist); add Haven | as ports.json | EXISTS + add Haven |
| **Story-stones** (wilderness nodes) | converted shrines and the 12 dolmen places (`*_dolmen`) re-read as Y'ffre story-stones | a Spinner's line at each; blessing | NEW |

---

## 5. Settlements

### 5.1 Tier list

| Region | Seat | Cities | Towns | Villages (selected) | Hamlets | Change |
| --- | --- | --- | --- | --- | --- | --- |
| Grahtwood north | `elden_root` (capital) | none | `cormount`, `redfur_trading_post` (outsiders' post) | `tarlain_heights`, `green_hall`, `cori_silmoor`, `cormeir_spring`, `ebon_ro`, `nindell` | lodges, `sweetbreeze_cottage` | rename 3 (3.3) |
| Grahtwood south | `haven` (city, south half seat) | none | `southpoint` | `corarata`, `laearata_cove`, `athenwe_cove`, `telenwe_landing` | | Haven re-tiered to city |
| Greenshade | `woodhearth` (city) | none | `greenheart`, `marbruk` | `longhaven`, `bramblebreach`, `malathil_cove`, `camp_gushnukbur` (Orc) | | |
| Malabal Tor | `silvenar` (city) | `falinesti` (walking city, counted at its current site) | `velyn_harbor`, `baandari_trading_post` | `deepwoods`, `vulkwasten`, `tanglehaven`, `drabul`, `jathsogur` (Orc) | | seat changes |
| Reaper's March | `arenthia` (city) | `rawlkha` (Khajiit city) | `eagle_vale`, `emperors_run`, `longvale`, `wasten_brukbrook` | `vinedusk_village`, `sren_ja`, `greenhill`, `willowgrove`, `lynpar_march` | `dokrin_monastery` etc. | spread the cluster |

### 5.2 City plan briefs

| City | Plan form | Vertical districts (L7, crown to ground) | Landmark | Why here |
| --- | --- | --- | --- | --- |
| **Elden Root** (exists, extend) | GIANT_TREE with three canopy tiers and an inner trunk stair | Crown: Camoran court and Thalmor quarters > upper canopy: Silvenar's Voice, temple, Spinners > trunk tiers: crafts, sap-tappers, jagga halls > root market: outsiders' stalls > ground: root camps | The Elden Tree and its crown lamp | Deepest soil, its own cisterns [Grahtwood L6] |
| **Falinesti** (new) | WALKING TREE: one plan, four sites, seasonal foliage | Wandering Court in the crown > Root-Tenders on the trunk > pilgrims' bridge-camp trailing below | the tree itself, which is gone the day the season turns | The walk [P.walking_city] |
| **Woodhearth** (new) | TREE_GRID: platforms at nodes of a rope-bridge web | Weave hub and Treethane > net and bridge guilds > fishers' hangs > the ground camp of the poorest | The Great Weave | Tall straight trees on the bay [Greenshade L6] |
| **Silvenar** (new) | ROOT AMPHITHEATRE | Audience Hall > Root Archive and Green Lady's yard > hunters' hollows > pilgrims | Root arches, firefly gate | The strict heartland [Malabal Tor L6] |
| **Arenthia** (new) | CLIFF over the falls with rope terraces | Treethane's hall at the top > gardens and temple > Khajiit traders' terrace at the bottom | Hanging gardens and the Waterfall Temple | The falls and the border fords [March L6] |
| **Rawl'kha** (new) | Khajiit sandstone town among trees, rope bazaar | Clan Mother and moon priest > traders > field workers | Rope market, Moon-sugar den | Khajiit farmland and the grain road [March L6] |
| **Haven** (new) | DELTA: houses on mangrove roots, plank streets | Treethane's tree > merchants' landings > fisher roots > raid-scarred outskirts | Delta lighthouse-tree | The delta port [Grahtwood L6] |
| Marbruk, Greenheart, Velyn Harbor | RING thorn wall; HOLLOW TREE; HARBOUR | as MARQUEE | | |

Kits: `bosmer` splits into **`bosmer_crown`** (platforms, hide canopies, lanterns), **`bosmer_trunk`** (hollow
rooms, living stairs), **`bosmer_root`** (root arches, ground huts of hide and bone), **`bosmer_bone`** (hunting
lodges, bone fences, antler gates); plus `imperial_trading_post` and `khajiit_marchland`. No sawn plank in a Bosmer
kit except imported crates and boats. Target `y_looks` >= 3 per region.

### 5.3 Bustling centres and why

* **Elden Root**: the capital, the Dominion's forest seat and the market where imports are sold [Grahtwood L5-L6].
* **Woodhearth**: the western port; every foreign plank and sack lands here [Greenshade L5].
* **Falinesti**: wherever it stands it draws pilgrims and hunters for the season [P.walking_city].
* **Arenthia and Rawl'kha**: the grain road; Khajiit grain feeds Bosmer cities that cannot farm [March L5].
* Poor: Woodhearth's ground camp, root camps under Elden Root, March villages after a werewolf season, coastal
  villages after a Maormer raid. Visible as bare hide shelters, ash of a raid, empty smoke racks.

### 5.4 Class disparity (the vertical city)

| Height | Who | What the player sees |
| --- | --- | --- |
| Crown | Treethanes, the Camoran court, Thalmor envoys | Lifts guarded; outsiders turned back; Thalmor robes above the leaves |
| Upper canopy | Silvenar's Voice, priests, Spinners, master hunters | Story circles, trophy halls |
| Trunk | Craftsmen: bowyers of horn, tanners, sap-tappers, jagga brewers | Workshops on spiral stairs |
| Boughs | Hunters and their families | Smoke racks, hide drying |
| Roots | Outsider traders (Khajiit, Imperial, Breton) | Stalls selling bread, wood, iron: necessary and despised |
| Ground | The poor, pact-breakers, the unlucky | Camps in the dim, no cistern access, debts to traders |

---

## 6. Dungeons, underground, landmarks

### 6.1 What lies below and why

| Underground | Where | Why |
| --- | --- | --- |
| Ayleid vaults | Grahtwood (north), Greenshade inland | Ayleid settlement; stone survives where the floor is firm [P.ayleid_vaults] |
| Root tombs and burial trees | Everywhere near old towns | The dead given back to the forest [P.root_tombs] |
| Hollow trees | Trunk dungeons inside dead giants | Giant trees rot hollow from within [P.graht_oaks] |
| Karst caves | Grahtwood sinks | Limestone under the humus [P.karst_sinks] |
| Kwama mine | Burroot (Grahtwood) | Kwama eggs are meat under the Pact [Grahtwood L5] |
| Sea caves | Deltas | Maormer and smugglers [P.maormer_beachheads] |

### 6.2 Inhabitant themes (>= 10 per region; signature per region)

Province themes: Ayleid vault guardians and Thalmor dig crews; restless dead in root tombs; spriggan groves; Wild Hunt
remnants; werewolf packs of Hircine; feast-lodge cannibals; Colovian poachers; Maormer raiders; Imperial-fort
bandits; giant spiders and hoarvors; Orc enclaves; Thalmor cells.

| Region | Signature theme | Marquee dungeon | Other major |
| --- | --- | --- | --- |
| Grahtwood | **Ayleid vaults reopened by Thalmor scholars** | `ne_salas` (spiral gallery round a crystal pillar) | `reliquary_of_stars`, `ossuary_of_telacar`, `laeloria`, `elden_grove` (Arena's staff dungeon), `lair_of_maarselok` |
| Greenshade | **The Wilderking's corrupted groves** | `city_of_ash` (S: ash terraces, lamp puzzle) | `labyrinth`, `hectahame`, `wilderking_court`, `rulanyils_fall` |
| Malabal Tor | **Wild Hunt grounds** | `tomb_of_the_apostates` (tree-tomb with an inner spiral) | `roots_of_silvenar`, `hoarvor_pit`, `dead_mans_drop`, `ilayas_ruins` |
| Reaper's March | **Hircine's werewolf packs** | `maw_of_lorkhaj` (new marquee plan: a Khajiit dark-moon cavern sealed by the truce) | `selenes_web` (Arena's starting dungeon), `fort_grimwatch`, `moonmont`, `deathsong_cleft` |

### 6.3 Counts (current vs floor)

| Region | Now | Floor (8.9/km²) | Abs. (6.7/km²) | Unmarked (0.57/km²) | Action |
| --- | --- | --- | --- | --- | --- |
| Grahtwood | 83 | 90 | 67 | 6 | +7 hollow-tree dungeons; 6 unmarked root tombs |
| Greenshade | 39 | 40 | 30 | 3 | +1; 3 unmarked wisp hollows |
| Malabal Tor | 29 | 32 | 24 | 2 | +3 Wild Hunt grounds; 2 unmarked |
| Reaper's March | 52 | 53 | 40 | 3 | +1; 3 unmarked dens |

### 6.4 Landmarks

Region-own L kinds: graht grove, burial tree, sink lake (Grahtwood); drowned forest, wisp ring (Greenshade); gorge
falls, root arch, hoarvor mound (Malabal Tor); henge, knoll arch, arena ruin (March). Marquee landmarks: `elden_tree`,
`reapers_henge`, `thizzrini_arena`, `waterdancer_falls`. Wonder moments: the Elden Tree's crown lamp seen at dusk;
Falinesti gone on the morning the season turns; a timber-mammoth herd crossing a glade; fireflies rising at
Silvenar; a canopy storm seen from a crown platform; the March henge at moonrise.

---

## 7. Quests

### 7.1 Province arc (>= 19): **The City That Stopped Walking**

Falinesti fails to reach its winter site. The Spinners say the year's story will not spin; the Thalmor say the tree
is ill and propose to root it at Elden Root as a Dominion bastion. Acts: I Grahtwood: the crown lamp dims (val_1),
the Thalmor's Ayleid digs (4); II Malabal Tor: the Silvenar and the Green Lady estranged, the Lady's trial (4); III
Greenshade: the Wilderking's silence and the Maormer on the coast (4); IV Reaper's March: the missing truce bone, the
Khajiit's price for grain (4, val_4); V Falinesti: walk with the city or root it (3, val_2). Consequence: Falinesti
walks again or stands rooted at Elden Root (two different seasonal states; dialogue in every city changes).

### 7.2 Factions and hubs

| Faction | Hub | Quests (have -> target) | Notes |
| --- | --- | --- | --- |
| The Silvenar and the Green Lady (Green Pact) | Silvenar | `green_pact.json` 6 -> 10 | Pact law, the Lady's trial, Wild Hunt warnings |
| Thalmor / Aldmeri Dominion | Elden Root crown | `aldmeri_dominion.json` (`ad_treaty_of_the_green`) + 4 Valenwood | Ayleid digs, Camoran court politics |
| Spinners (story guild) | Woodhearth | 0 -> 6 | Collect and spin stories; books as quests (MS10 link) |
| Maormer raids (coast watch) | Haven | 0 -> 5 | Defence chain: raid warnings, a beachhead, a captured serpent-rider |
| Hunters' lodges (Hircine and the hunt) | Arenthia | 0 -> 5 | Great hunts, werewolf packs, the Meat Mandate's limits |

An arc choice (not an exclusive faction): at the finale the player stands with the Silvenar (Falinesti walks) or the
Camoran court (Falinesti is rooted).

### 7.3 Region chains (hold conflicts)

| Region | Chain | Places | Consequence |
| --- | --- | --- | --- |
| Grahtwood | **Sap and Crown**: the Thalmor tap the Elden Tree beyond what it gives | elden_root, ne_salas, a sap-tappers' tier, haven | Tapping stops or a crown tier closes; the crown lamp state |
| Greenshade | **The Quiet King**: the Wilderking's servants turn; Maormer take a cove | woodhearth, hectahame, maormer_camp, wilderking_court | A new Wilderking chosen (could be an NPC the player knows) |
| Malabal Tor | **The Lady's Trial**: candidates for the Green Lady; an Orc enclave accused | silvenar, drabul, jathsogur, wild hunt ground | The trial's winner; the Orc truce kept or broken |
| Reaper's March | **The Truce of Bones** (val_4 extended) | waterdancer_falls, sren_ja, dokrin_monastery, rawlkha, arenthia, thizzrini_arena, reapers_henge | Truce renewed or broken: border scenes and prices change |

### 7.4 Settlement quests and radiant

MARQUEE chains (The Tree's Fever, Chasing the Walk, The Fraying, The Silent Court, A Wagon Through the Green Pact,
A Garden Lost, The Thorn Wall) plus 2 favours per town. Culture radiant (>= 6): a hunt for a named beast, return the
fallen to a burial tree, catch a pact-breaker (a choice), escort a grain wagon without cutting, bring a story to a
Spinner, drive poachers off the Strid. `side_valenwood.json` (13) placed with `why`. Quests 59 -> 339 (14/km²).

---

## 8. Components layout

| Component | File or system | Now | To do (phase) |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/valenwood.json`, `worldsheet_lint.py` (+ Pact forbidden pairs) | missing | VW0 |
| Regions | `regions.json` (Malabal Tor seat to Silvenar) | | VW0 |
| Terrain | `tamriel_vectors.json` gorges, deltas, knolls, sinks; `hydrology.json` | flat | VW1 |
| Biomes | `biomes.py` `micro` tables; inverted disturbance for `bosmer`; canopy-layer spawn bands | 4 palettes | VW1 |
| Giant trees | `GIANT_TREE` builder variants (city-tree, wild grove, hollow dungeon tree, walking tree) | Elden Root only | VW1, VW3 |
| Places | `tamriel/places/valenwood.json` | 351 | VW0 fixes, `why`, `hidden` |
| Roads | `roads/named_roads.json`; Valenwood road classes in the road plan and `PlacementMask` | none | VW2 |
| Travel | river routes, lifts, seasonal Falinesti flag, story-stones | ships only | VW2 |
| City plans | `cityplans/elden_root.json` exists; add falinesti, woodhearth, silvenar, arenthia, rawlkha, haven | 1 | VW3 |
| Styles | `styles/bosmer.json` + 4 sub-kits; overlay `elden_root` | 1 kit | VW3 |
| Dungeons and marquee | identity data, `marquee.json`, `MarqueePlans` (`maw_of_lorkhaj` new) | identity 5% | VW4 |
| Creatures and scenes | `region_spawns` by canopy band, `scenes.json` (Wild Hunt omen, grain wagon, poachers, Maormer sail sighted) | 13 kinds | VW5 |
| Quests and lore | `quests/story/valenwood_*.json`, faction files, `lore/books/*`, `region_voice.json`, `calendar/festivals.json` (the Arrival) | 59 quests | VW6 |
| Optional | `events/valenwood.json` (a Green Pact wild hunt, MS4) | | VW8 |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Lowland forest, rivers, coast | Giant trees as the vertical, gorges, deltas, knolls | The 18-block uniform plain (max 65) |
| Settlement list | Arena's eight cities and their order | Each city a different tree-form (5.2) | Arena's Breton and Nord village names (3.3) |
| Settlement life | Bosmer roles: hunters, Treethanes | The vertical class city; imports as a daily need | 3.2 named NPCs per town |
| Dungeons | Elden Grove and Selene's Web (Arena's two) | Ayleid vaults, hollow trees, burial trees | Generic caves (identity 5%) |
| Wilderness | Forest fauna | Canopy layers, mammoth glades, hunts | Random spawns |
| Quests | The Pact, the Thalmor, the walking city | The City That Stopped Walking; the Lady's trial | Generator quests |
| Travel | Rivers, Woodhearth ships | Root paths, canopy ways, lifts, riding Falinesti | Straight cleared roads |
| Lore text | Pact, Mandate, Wild Hunt, Silvenar facts | Spinners' stories as the region voice | Any source text |

**What Arena did vs what we do:** Arena drew Valenwood as eight walled towns in a flat forest with generated village
names. We build a forest that is never cut, cities that are trees, a city that walks, and a society ranked by height.

---

## 10. Floors and gates, implementation phases

### 10.1 Current vs target per region (parity 2026-10-13)

| Region | km² | Score (SET/NPC/QST/DUN/LOR) | Named towns | Dungeons now -> floor | Quests now -> target | Named NPCs now -> target | Lore places | Readables | `d_identity` | `y_looks` | `c_total` |
| --- | ---: | --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| Grahtwood | 10.06 | 43 (34/20/31/57/17) | 5 | 83 -> 90 | 14 -> 141 | 11 -> 338 | 14 -> 115 | 36 -> 450 | 0.04 -> 1.0 | 3 -> 3 | 13 -> 25 |
| Greenshade | 4.52 | 44 (28/27/36/69/19) | 3 | 39 -> 40 | 14 -> 63 | 14 -> 152 | 10 -> 51 | 26 -> 202 | 0.08 -> 1.0 | 2 -> 3 | 13 -> 25 |
| Malabal Tor | 3.63 | 42 (37/23/34/54/21) | 4 | 29 -> 32 | 13 -> 51 | 16 -> 122 | 10 -> 41 | 26 -> 162 | 0.10 -> 1.0 | 2 -> 3 | 13 -> 25 |
| Reaper's March | 5.98 | 45 (36/20/34/55/17) | 6 | 52 -> 53 | 18 -> 84 | 16 -> 201 | 12 -> 68 | 27 -> 267 | 0.02 -> 1.0 | 2 -> 3 | 13 -> 25 |
| **Province** | 24.2 | 44 | 18 | 203 -> 215 | 59 -> 339 | 57 -> 813 | 46 -> 275 | 115 -> 1,081 | 0.05 -> 1.0 | | |

Province: signature gear families 1 -> 8 (bone-and-hide sets, Silvenar's guard, Wild Hunt relics), regional
ingredients 14 -> 30, uniques 3 -> 6, music 2 -> 4 exploration tracks, the Arrival festival, >= 2 travel modes.

### 10.2 Gates

`worldsheet_lint.py` (including the Pact forbidden pairs) 0; `parity.py --assert-floor` per region; `layout_lint`;
`roadplan_lint` plus a new check that Valenwood road corridors fell no tree (count of tree features removed by a road
in Valenwood = 0 outside Khajiit and Imperial settlements); `reach_lint`; `population_lint`; `placement_lint`;
`dungeon_identity_lint`; `novelty_lint --strict` and MS20; F-HOOK, F-WEENIE (giant trees count as XL), F-MICRO,
F-ENC (>= 3 Valenwood-only kinds: grain wagon, Wild Hunt omen, poacher crew), F-WONDER, F-TRAVEL; Haiku contact sheet
review.

### 10.3 Phases

| Phase | Scope | Gate |
| --- | --- | --- |
| **VW0** Sheet and fixes | `valenwood.json`, seats, seasonal-site typing, the March spread, shrines, renames | worldsheet_lint 0; layout_lint |
| **VW1** Terrain, trees, micro-biomes | gorges, deltas, knolls, sinks; graht groves; canopy bands; inverted disturbance | relief targets; F-MICRO; F-WEENIE |
| **VW2** Roads and travel | root paths, canopy ways, hunt trails; river boats; lifts; Falinesti riding; story-stones | roadplan_lint; no-fell check; F-TRAVEL |
| **VW3** Cities | 6 new plans, Elden Root tiers, 4 Bosmer sub-kits, courts and population | SET >= 80 at seats; s_bldgs >= 9.4; `y_looks` >= 3 |
| **VW4** Dungeons | identity for 203; +12; 14 unmarked; `maw_of_lorkhaj` plan | d_identity 1.0; F-LOOP |
| **VW5** Wilderness | imga, timber mammoth, hoarvor, strangler, lurcher, senche, werewolf, spriggan variants to 25 kinds; encounters | CRE >= 80; F-ENC; F-QUIET |
| **VW6** Quests and lore | the arc, faction lines, 4 chains, Spinner book sets, voices, the Arrival | QST, LOR stage 1 then floor; F-REACT |
| **VW7** Review | contact sheets, four questions per place | G1 blind test >= 80% |
| **VW8** Optional | wild hunt world event, Maormer landing at Haven | if adopted |

VW1 and VW2 run in parallel; VW3 needs VW1's giant trees.
