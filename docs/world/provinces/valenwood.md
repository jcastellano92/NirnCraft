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

Data target: `tamriel/world/valenwood.json` (MS22). Field ids are data ids; cites in parentheses; `vw.` is the province.

### 1.1 Province sheet (`valenwood`)

**World truth.** Valenwood is the forest that its people have sworn never to harm. The Bosmer keep the Green Pact: no
living plant of Valenwood may be cut or eaten by them, so they live on meat, bone, hide and sap, and buy grain and
timber from Khajiit and Imperial traders. Their cities grow inside giant graht-oaks where status rises with height from
root to crown, and Falinesti walks between four seasonal sites while Ayleid ruins lie beneath the roots. Elden Root is
the Dominion's forest capital, where Thalmor envoys stand beside a Camoran king while the Silvenar and Green Lady speak
for the Pact against Colovian poachers and Maormer raiders.

| Layer | Fields `id` (cites): meaning |
| --- | --- |
| L1 Geology | `forest_basin`: a low basin of deep humus over old sediments, little exposed rock. `river_gorges`: the Xylo and the Strid and their feeders cut 20-40 blocks below the forest floor. `march_knolls`: sandstone knolls and low ridges in Reaper's March, to 60-120 blocks. `karst_sinks`: sinkhole lakes where limestone lies under the humus. `delta_coast`: mangrove deltas and drowned river mouths on the south and west coast. `poor_ore`: little metal near the surface, some bog iron only |
| L2 Climate | `warm_wet` (L1.forest_basin, L1.delta_coast): warm and wet all year, wettest in the south-west. `under_canopy` (L1.forest_basin, L2.warm_wet): below the crowns the floor is dim, still and humid. `drier_march` (L1.march_knolls): Reaper's March is drier and seasonal, grading into Elsweyr's savanna. `south_storms` (L1.delta_coast): sea storms and sea mists from the south-west. `four_seasons_felt` (L1.forest_basin, L2.warm_wet): the forest's seasons show as leaf colour, fruiting and migration rather than snow |
| L3 Water | `river_net` (L1.river_gorges, L2.warm_wet): the Xylo in the north-west, the Strid on the Cyrodiil border, and many forest rivers. `crown_cisterns` (L1.forest_basin, L2.under_canopy): rain caught in the hollows and bromeliad pools of the giant trees; tree-cities drink from their own trees. `sink_lakes` (L1.karst_sinks): clear karst sinkhole pools. `who_holds_water` (L1.forest_basin, L3.crown_cisterns): water is everywhere; what is held is the trees: Treethanes control the city-trees and so the cisterns, the shelter and the height |
| L4 Ecology | `canopy_layers` (L2.under_canopy): four vertical layers (floor, understorey, canopy, emergent crowns), each with its own life. `graht_oaks` (L1.forest_basin, L2.warm_wet): giant trees in groves on the deepest soils, the only places large enough for cities. `pact_forest` (L1.forest_basin, L4.graht_oaks, L4.canopy_layers): the forest grows uncut wherever Bosmer live, because their covenant forbids harming its plants; old growth reaches to every doorstep. `game_abundance` (L2.warm_wet, L4.pact_forest): deer, boar, wild pigs, birds and timber mammoths in glades. `predators` (L3.river_net, L4.canopy_layers): big cats in the March, giant spiders and wasps in the understorey, hoarvors in wet hollows, imga in the canopy. `green_hostiles` (L1.forest_basin, L4.pact_forest): spriggans, stranglers and walking plant-beasts guard old groves |
| L5 Economy | `meat_and_jagga` (L4.game_abundance): hunting, pig-keeping and smokehouses; jagga is fermented from pig's milk. `bone_hide_craft` (L1.poor_ore, L4.pact_forest): bows of horn and bone, armour of hide and bone, because local wood may not be cut and metal is scarce. `pact_imports` (L4.pact_forest): grain, bread, timber, paper, wine and iron bought from Elsweyr and Cyrodiil. `sap_given` (L4.graht_oaks, L4.pact_forest): graht-oaks yield sap through openings sung into the bark; tapping is a priestly craft, not cutting. `ports` (L1.delta_coast, L5.pact_imports): Woodhearth, Haven, Southpoint and Velyn Harbor import what the Pact forbids. `pelts_and_feathers` (L4.game_abundance): the export trade |
| L6 Settlement | `tree_cities` (L3.crown_cisterns, L4.graht_oaks): cities stand where graht-oaks stand, not where rivers cross. `walking_city` (L2.four_seasons_felt, L4.graht_oaks): Falinesti, a giant tree that migrates among four seasonal sites. `delta_ports` (L1.delta_coast, L5.ports): coastal stilt and root ports. `march_border_towns` (L1.march_knolls, L5.pact_imports): the March's towns sit on knolls and fords on the Elsweyr and Cyrodiil roads, mixed Bosmer and Khajiit. `busy_centres` (L5.ports, L5.pact_imports, L6.tree_cities): Elden Root (capital and Dominion seat), Woodhearth (port), Falinesti (wherever it stands), Arenthia and Rawl'kha (border trade). `poor_places` (L4.game_abundance, L6.tree_cities): ground camps under the city-trees, hunting villages whose game has thinned, coastal villages after a raid |
| L7 Society | `vertical_rank` (L6.tree_cities): the higher you live the higher you stand: Treethane and court in the crown, craftsmen on the trunk tiers, hunters on the boughs, the poor and outsiders at the roots and on the ground. `treethanes` (L3.who_holds_water, L6.tree_cities): each city's ruler. `hunters_honoured` (L5.meat_and_jagga): the best hunters are the city's heroes. `spinners` (L6.tree_cities): priest-storytellers who keep and shape the people's story. `outsider_traders` (L5.pact_imports): Khajiit and Imperial merchants handle plant goods the Bosmer cannot, so they are needed and kept low. `pact_breakers` (L4.pact_forest): Bosmer who break the Pact are cast out to the ground or beyond |
| L8 Culture | `yffre_faith` (L4.pact_forest, L7.spinners): Y'ffre the Storyteller, giver of the Pact; shrines are story-stones and living groves. `meat_mandate` (L4.pact_forest, L5.meat_and_jagga): the slain are to be eaten, within three days, and nothing is wasted. `wild_hunt` (L4.pact_forest): a rite of transformation into a ravening host, called in extremity and feared by all. `silvenar_and_lady` (L7.treethanes, L8.yffre_faith): the Silvenar embodies the people's will, the Green Lady is its champion; their bond is the Pact made flesh. `wilderking` (L4.pact_forest, L8.yffre_faith): a forest-spirit protector bound to Greenshade. `burial_trees` (L4.pact_forest): the dead are given back to the forest in burial trees and root tombs. `songs_not_stone` (L7.spinners): history is sung and spun, not carved. `outsiders` (L5.pact_imports): wary of Colovians, uneasy partners of the Thalmor, rivals and friends of the Khajiit |
| L9 Politics | `camoran_court` (L7.treethanes): a Camoran king in Elden Root, seated with Thalmor support; Valenwood is a Dominion province. `thalmor_in_the_crown` (L7.vertical_rank, L9.camoran_court): Thalmor envoys live in the crown of the Elden Tree. `silvenar_vs_thalmor` (L8.silvenar_and_lady, L9.camoran_court): the Silvenar guards the Pact against Dominion improvements. `strid_border` (L3.river_net, L5.pact_imports): Colovian loggers and poachers on the Strid; old border wars. `march_truce` (L6.march_border_towns): a renewed truce between Bosmer and Khajiit elders in the March. `maormer_raids` (L2.south_storms, L5.ports): Pyandonean raiders on the south-west coast. `the_arrival` (L6.walking_city, L8.meat_mandate): winter festival when Falinesti reaches its winter site with a great hunt and a plantless feast |
| L10 Hidden | `ayleid_vaults` (L1.forest_basin): Ayleid ruins under the roots; stone survives only where the floor is firm. `root_tombs` (L4.pact_forest, L8.burial_trees): burial trees and root tombs. `spriggan_groves` (L4.green_hostiles): warded ancient glades. `wild_hunt_grounds` (L4.pact_forest, L8.wild_hunt): sites where hunts were called; still wrong. `poacher_camps` (L4.pact_forest, L9.strid_border): illegal Colovian logging camps on the Strid. `maormer_beachheads` (L1.delta_coast, L9.maormer_raids): sea-elf raid coves. `feast_lodges` (L4.game_abundance, L8.meat_mandate): hunters' lodges where the Mandate is abused into cannibal cults. `hircine_packs` (L4.predators, L7.hunters_honoured): werewolf packs of Hircine's hunters. `septim_forts` (L1.march_knolls, L9.strid_border): Imperial border forts, now held by bandits, Khajiit or Thalmor. `thalmor_cells` (L4.graht_oaks, L9.thalmor_in_the_crown): dissidents held in hollow trees |
| L11 History | `dawn_pact` (L4.pact_forest, L8.yffre_faith): the covenant with Y'ffre in the forest's first age. `ayleid_age` (L10.ayleid_vaults): Ayleid settlement in the north and east. `camoran_line` (L9.camoran_court): an ancient royal line, including a Third Era usurper who overran the province. `imperial_province` (L10.septim_forts): the Septim era, its forts and trading posts. `dominion_union` (L9.thalmor_in_the_crown): early Fourth Era union with Summerset under the Thalmor. `great_war` (L9.camoran_court): Bosmer archers in the Dominion's war against the Empire. `walking_legend` (L6.walking_city, L7.spinners): Falinesti's walk, sung by every Spinner |

### 1.2 Grahtwood (`valenwood/grahtwood`, 10.06 km², capital Elden Root, levels 20-42)

**World truth.** Grahtwood is the heart of the forest and of the Dominion's hold on it. The Elden Tree rises over
everything, a city in one trunk with the Camoran court and the Thalmor in its crown and the poor among its roots.
Around it stand old Ayleid vaults the Thalmor's scholars are reopening, burial trees, timber-mammoth glades and the
winter site of Falinesti. The south coast is a separate world: Haven and Southpoint, delta ports on mangrove roots that
buy the grain and iron the Pact forbids, watching the sea for Maormer sails.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `elden_grove_soil`: the deepest humus soils in the central basin. `grahtwood_sinks`: limestone sinkholes under the forest floor. `south_deltas`: mangrove deltas on the southern coast |
| L2 | `deep_canopy` (vw.L2.under_canopy, L1.elden_grove_soil): dim, humid air beneath the giant crowns. `coastal_wet` (vw.L2.south_storms, L1.south_deltas): sea fogs and warm squalls on the delta coast |
| L3 | `elden_cisterns` (vw.L3.crown_cisterns, L2.deep_canopy): high bromeliad and hollow reservoirs in the Elden Tree. `lady_solace_fen` (vw.L3.sink_lakes, L1.grahtwood_sinks): flooded karst fen |
| L4 | `graht_grove` (vw.L4.graht_oaks, L1.elden_grove_soil): giant city-oaks. `old_growth_floor` (vw.L4.pact_forest, L2.deep_canopy): dense uncut forest to the door. `mammoth_glade` (vw.L4.game_abundance, L1.elden_grove_soil): natural treefall glades with timber mammoths. `sink_lake` (L1.grahtwood_sinks, L3.lady_solace_fen): clear fern-ringed sink pools. `mangrove_delta` (L1.south_deltas, L2.coastal_wet): coastal mangrove roots. `burial_grove` (vw.L4.pact_forest, L1.elden_grove_soil): old trees hung with bone tokens |
| L5 | `sap_tappers` (vw.L5.sap_given, L4.graht_grove): priestly sap singers. `capital_market` (vw.L5.pact_imports, L4.graht_grove): imported grain, wood and iron at the roots. `delta_trade` (vw.L5.ports, L1.south_deltas): Haven and Southpoint maritime trade. `kwama_tenders` (vw.L5.meat_and_jagga, L1.elden_grove_soil): Burroot egg-mines |
| L6 | `elden_root_capital` (vw.L6.tree_cities, L1.elden_grove_soil, L4.graht_grove): the capital grown inside the Elden Tree. `haven_port` (vw.L6.delta_ports, L1.south_deltas): southern port seat. `redfur_post` (L5.capital_market): trading post for outsiders at the forest edge. `falinesti_winter_site` (vw.L6.walking_city, L4.graht_grove): the southern seasonal clearing of the walking city |
| L7 | `crown_court_thalmor` (vw.L7.vertical_rank, L6.elden_root_capital): Camoran courtiers and Thalmor envoys in the highest boughs. `root_poor` (vw.L7.vertical_rank, L6.elden_root_capital): ground-dwellers and outsider peddlers among the roots. `delta_merchants` (L5.delta_trade): port factors at Haven and Southpoint |
| L8 | `elden_temple` (vw.L8.yffre_faith, L6.elden_root_capital): story-shrines in the trunk. `crown_lamp` (L3.elden_cisterns, L6.elden_root_capital): a sacred light in the Elden Tree's crown said to show the Pact holds |
| L9 | `court_vs_voice` (vw.L9.silvenar_vs_thalmor, L7.crown_court_thalmor): the Camoran court and the Silvenar's Voice in the same tree. `ayleid_digs` (vw.L9.thalmor_in_the_crown, L7.crown_court_thalmor): Thalmor scholars reopening sealed Ayleid vaults |
| L10 | `ayleid_vaults_grahtwood` (vw.L10.ayleid_vaults, L1.elden_grove_soil, L9.ayleid_digs): Ne Salas, Laeloria, Reliquary of Stars and Ossuary of Telacar. `maarselok_lair` (vw.L4.green_hostiles, L1.elden_grove_soil): a blighted dragon lair from the Second Era. `elden_grove` (vw.L10.root_tombs, L4.burial_grove): Arena's staff dungeon under the ancient roots |
| L11 | `cormount_battle` (vw.L11.camoran_line, L6.elden_root_capital): a dynastic border battle remembered by a memorial grove. `ayleid_north` (vw.L11.ayleid_age, L10.ayleid_vaults_grahtwood): the Ayleid refugee kingdoms under the oaks |

### 1.3 Greenshade (`valenwood/greenshade`, 4.52 km², seat Woodhearth, levels 24-45)

**World truth.** Greenshade is the wet, misty west where marsh, mangrove and graht-oak meet the Abecean Sea. Woodhearth
is a city of rope bridges strung among tall coastal trees, a port the Pact allows to trade with Summerset and Senchal,
with the poorest Bosmer and outsiders camped on the muddy ground below. The Wilderking, the forest's spirit-guardian,
holds court in a hollow grove the living rarely find. The burned Ayleid City of Ash and the shifting Labyrinth lie
inland. Maormer raiders camp on the drowned coast, and the Spinners say the Wilderking has gone quiet.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `drowned_coast`: subsided beaches and mangrove estuaries on the western sea. `mire_basins`: low peat-and-clay hollows inland |
| L2 | `sea_mists` (vw.L2.south_storms, L1.drowned_coast): rolling salt fog off the Abecean. `wettest_west` (vw.L2.warm_wet, L1.mire_basins): heavy year-round rain |
| L3 | `marsh_water` (L1.mire_basins, L2.wettest_west): Gathongor's Mire and Verrant Morass. `woodhearth_bay` (L1.drowned_coast): sheltered harbour sound |
| L4 | `mire_fen` (L3.marsh_water): reeds, stranglers and hoarvors. `mangrove_delta` (L1.drowned_coast, L3.woodhearth_bay): coastal mangrove roots. `bridge_canopy` (vw.L4.canopy_layers, L3.woodhearth_bay): tall straight trees linked by rope bridges. `wisp_hollow` (L2.sea_mists, L3.marsh_water): shaded hollows lit by wisps. `spriggan_grove` (vw.L4.green_hostiles, L1.mire_basins): vine-draped ancient ruins. `drowned_beach` (L1.drowned_coast): dead surf-washed trunks |
| L5 | `bridge_port` (vw.L5.ports, L3.woodhearth_bay): shipping with Summerset and Senchal. `net_fishing` (L3.woodhearth_bay): bay and estuary fisheries. `reed_weaving` (vw.L4.pact_forest, L4.mire_fen): craft from dead fallen reed only |
| L6 | `woodhearth_seat` (vw.L6.tree_cities, L3.woodhearth_bay, L4.bridge_canopy): the rope-bridge port city. `greenheart` (vw.L6.tree_cities, L1.mire_basins): a marsh town inside a hollow tree. `marbruk` (vw.L5.pact_imports, L1.mire_basins): a glade fort-town of outsiders and Bosmer on the road east |
| L7 | `bridge_district_rank` (vw.L7.vertical_rank, L6.woodhearth_seat): high bridge tiers above the docks. `ground_camp` (vw.L6.poor_places, L6.woodhearth_seat): the poorest of Valenwood on the mud floor |
| L8 | `wilderking_court` (vw.L8.wilderking, L4.spriggan_grove): the living spirit-throne of the western woods. `spinners_of_greenshade` (vw.L7.spinners, L6.greenheart): story-priests of the mire |
| L9 | `wilderking_silent` (L8.wilderking_court): the forest guardian no longer answers the Spinners. `maormer_shore` (vw.L9.maormer_raids, L1.drowned_coast): sea-elf landings along the western coves |
| L10 | `wilderking_spirits` (L4.spriggan_grove, L9.wilderking_silent): groves of the Wilderking's corrupted servants. `city_of_ash` (vw.L10.ayleid_vaults, L1.mire_basins): burned Ayleid citadel. `labyrinth` (vw.L10.ayleid_vaults, L4.spriggan_grove): shifting root-and-stone maze. `maormer_camp` (vw.L10.maormer_beachheads, L1.drowned_coast, L9.maormer_shore): fortified sea-elf beachheads |
| L11 | `ash_fire` (vw.L11.ayleid_age, L10.city_of_ash): the burning of the western Ayleid city. `wilderking_line` (vw.L11.dawn_pact, L8.wilderking_court): each Wilderking chosen from a mortal host |

### 1.4 Malabal Tor (`valenwood/malabal_tor`, 3.63 km², seat Silvenar, levels 22-44)

**World truth.** Malabal Tor is the deep jungle of the north-west on the Xylo River, the Bosmer heartland that the
Dominion reaches least. The city of Silvenar is a hidden court under hollowed root arches, where the Silvenar holds
audience and the Green Lady trains. Falinesti stands here in summer. Wood Orc enclaves keep an uneasy truce with their
hunting neighbours, Velyn Harbor trades on the coast, and the oldest Wild Hunt grounds lie in the jungle where hoarvors
breed. Here the Pact is kept most strictly and the Thalmor are least welcome.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `xylo_gorge`: the steep 30-block gorge of the Xylo River. `jungle_floor`: deep wet humus in the north-western basin |
| L2 | `hothouse` (vw.L2.warm_wet, vw.L2.under_canopy, L1.jungle_floor): steaming, rain-drenched understorey air |
| L3 | `xylo_basin` (vw.L3.river_net, L1.xylo_gorge): the Xylo and its waterfalls. `velyn_bay` (vw.L1.delta_coast, L1.xylo_gorge): the north-western harbour mouth |
| L4 | `jungle_understorey` (L1.jungle_floor, L2.hothouse): lianas, giant ferns, wasps and spiders. `xylo_gorge_walls` (L1.xylo_gorge, L3.xylo_basin): hanging roots and spray. `hoarvor_hollow` (vw.L4.predators, L2.hothouse): rot-pools where hoarvors breed. `root_amphitheatre` (vw.L4.graht_oaks, L1.jungle_floor): arching roots lit by fireflies. `canopy_high` (vw.L4.canopy_layers, L2.hothouse): tallest crowns where imga nest. `orc_clearing` (L1.jungle_floor): stump-cleared forge rings of the Wood Orcs |
| L5 | `strict_hunters` (vw.L5.meat_and_jagga, L4.jungle_understorey): bone-bow hunting and smokehouses. `velyn_trade` (vw.L5.ports, L3.velyn_bay): coastal shipping. `orc_smiths` (vw.L1.poor_ore, L4.orc_clearing): iron work traded to Bosmer |
| L6 | `silvenar_seat` (vw.L6.tree_cities, L4.root_amphitheatre): the hidden court city under root arches. `falinesti_summer_site` (vw.L6.walking_city, L4.canopy_high): the northern summer site of the walking tree. `velyn_harbor` (L3.velyn_bay, L5.velyn_trade): western port town. `orc_enclaves` (L4.orc_clearing, L5.orc_smiths): the strongholds of Drabul and Jathsogur |
| L7 | `silvenar_court` (vw.L8.silvenar_and_lady, L6.silvenar_seat): keepers of the people's voice. `orc_outsiders` (L6.orc_enclaves): Wood Orc clans living outside the Pact |
| L8 | `strictest_pact` (vw.L4.pact_forest, L5.strict_hunters): orthodox Meat Mandate observance. `green_lady_trials` (vw.L8.silvenar_and_lady, L6.silvenar_seat): the martial trials of the forest's champion |
| L9 | `lady_succession` (L8.green_lady_trials): the Green Lady's trial for a successor. `orc_truce` (L6.orc_enclaves, L8.strictest_pact): tension over Wood Orc timber cutting |
| L10 | `wild_hunt_grounds_xylo` (vw.L10.wild_hunt_grounds, L4.jungle_understorey, L9.lady_succession): ancient transformation glades. `tomb_of_the_apostates` (vw.L10.root_tombs, L4.root_amphitheatre): Pact-breakers sealed in living wood. `hoarvor_pit` (L1.jungle_floor, L4.hoarvor_hollow): subterranean blood-tick caverns |
| L11 | `apostates` (vw.L11.dawn_pact, L10.tomb_of_the_apostates): Bosmer who once broke the Pact and were entombed in a tree |

### 1.5 Reaper's March (`valenwood/reapers_march`, 5.98 km², seat Arenthia, levels 18-40)

**World truth.** Reaper's March is the borderland where Valenwood meets Elsweyr and Cyrodiil, a country of drier red
knolls and golden savanna between oak groves. Bosmer and Khajiit share it under a truce renewed with a bone laid on a
stone altar each generation. Arenthia hangs its gardens over a waterfall; Rawl'kha is a Khajiit temple-city among the
trees; old Imperial forts watch the roads. Werewolf packs of Hircine's hunters run the knolls, the Maw of Lorkhaj
glowers on the Khajiit side, and this year one bone of the truce is missing.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `knolls`: red sandstone knolls and dry ridges up to 120 blocks. `strid_valley`: the incised border gorge of the Strid River |
| L2 | `dry_season` (vw.L2.drier_march, L1.knolls): warm dry winds off the Elsweyr plateau |
| L3 | `waterdancer_falls` (vw.L3.river_net, L1.knolls): the high cascade at Arenthia. `strid` (vw.L3.river_net, L1.strid_valley): the border river facing Colovia |
| L4 | `savanna_grove_mosaic` (L1.knolls, L2.dry_season): golden grass with oak islands and big cats. `knoll_scrub` (L1.knolls, L2.dry_season): thorn and red rock. `riverine_gallery` (L3.strid): tall riverside trees. `khajiit_fields` (L1.knolls, L3.waterdancer_falls): farmed grain and cane plots round Khajiit towns. `henge_heath` (L1.knolls): short turf around bone-white stones. `poacher_scar` (L1.strid_valley, L3.strid): felled stumps along the Colovian bank |
| L5 | `grain_road` (vw.L5.pact_imports, L2.dry_season): the Pact caravan trade from Elsweyr. `khajiit_farming` (vw.L5.pact_imports, L4.khajiit_fields): Khajiit farm here as they are not bound by the Pact. `border_tolls` (vw.L6.march_border_towns, L3.strid): bridge and road customs |
| L6 | `arenthia_seat` (L1.knolls, L3.waterdancer_falls): the waterfall seat of the March. `rawlkha` (L4.khajiit_fields, L5.khajiit_farming): Khajiit market and temple city. `border_cluster` (L3.strid, L5.border_tolls): Eagle Vale, Emperor's Run, Longvale and Wasten Brukbrook |
| L7 | `mixed_town_rank` (vw.L7.vertical_rank, L6.arenthia_seat, L6.rawlkha): Bosmer high and Khajiit low in Arenthia, reversed in Rawl'kha. `truce_elders` (vw.L9.march_truce, L6.arenthia_seat): joint Bosmer-Khajiit border arbiters |
| L8 | `bone_truce_rite` (vw.L9.march_truce, L4.henge_heath): generational oath at Reaper's Henge. `moonmont` (vw.L8.outsiders, L6.rawlkha): Khajiit lunar shrine on the border |
| L9 | `missing_bone` (L7.truce_elders, L8.bone_truce_rite): a stolen truce-bone threatening border war. `colovian_pressure` (vw.L9.strid_border, L4.poacher_scar): Colovian loggers and poachers crossing the Strid |
| L10 | `hircine_packs_march` (vw.L10.hircine_packs, L1.knolls, L4.savanna_grove_mosaic): werewolf hunting packs on the knolls. `maw_of_lorkhaj` (L1.knolls, L8.moonmont): a sealed Khajiit dro-m'Athra dark site. `septim_forts_march` (vw.L10.septim_forts, L1.strid_valley, L9.colovian_pressure): Grimwatch and Sphinxmoth border forts. `selenes_web` (vw.L4.green_hostiles, L4.savanna_grove_mosaic): Arena's starting dungeon in the March |
| L11 | `march_wars` (vw.L11.imperial_province, L10.septim_forts_march): the Three Banners and Colovian border campaigns. `thizzrini` (L6.rawlkha, L11.march_wars): the grand arena of the old border wars |

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
