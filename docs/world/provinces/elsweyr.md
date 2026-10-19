# Elsweyr: Anequina and Pellitine under two moons

Status: plan (docs only, 2026-10-13). Nothing in the mod has changed. Phase prefix **EW**.

Read first: `docs/world/DESIGN-PRINCIPLES.md` (the first principle, sections 0, 1, 4 and 5 are the rulebook),
`docs/world/SCALE-AND-GEOGRAPHY.md` (5.6, 6, 7), `docs/MARQUEE.md` (Elsweyr), `docs/LORE-WEB.md` (els_1 to els_4,
t_sugar, t_dial, t_bone, t_falls), `docs/PARITY.md`. Places: `tamriel/places/elsweyr.json` (5 regions, 336 places).

**Legal line.** Lore facts are summarised in our own words. Arena gives relative positions only (fit 74.4 blocks per
Arena pixel, rotation -14.9, rms 991 blocks; 31 of 32 settlements matched). Every invented name, line and design here
is ours. Nothing from any game's files enters the repo.

**Parity snapshot** (`py -3 -I tools/qa/parity.py --raw`, 2026-10-13). Province 25.7 km², mean score 44 (Skyrim 95):
SET 37, NPC 21, QST 36, DUN 58, CRE 66, FLO 83, STY 31, LND 67, LOR 17, MUS 54, ITM 17. `parity.py --lowest 40` lists
**Pellitine twice** (NPC 12, Lore 13). The province has ground (landmark density 4.36/km² against Skyrim's 2.27,
village density 3.66/km² against 1.40) and almost no depth (0.94 named buildings and 3.3 named NPCs per named town
against 11.7 and 46.9; dungeon identity 9%). The plan therefore adds depth, not dots.

**Not Skyrim in a new colour.** Each Skyrim pattern has an Elsweyr answer drawn from the sheet below:

| Skyrim has | Elsweyr has instead (sheet cause) |
| --- | --- |
| Jarls in longhouses | Two crowned kings, a Mane, Clan Mothers and moon priests (L7, L9) |
| Nordic barrows and draugr | Moon-temples, layered necropolis cities, masked dead and dro-m'Athra (L8, L10) |
| Forsworn camps in the Reach | Renrijra Krin camps in the canyons (L9) |
| Carriages at the stables | Caravans that leave at moonrise, river barges and the Senchal ships (L5, L8) |
| Mead, pine, snow, aurora | Moon-sugar, savanna and jungle, dust storms, the twin moons as the sky's spectacle (L2, L5) |
| Daytime towns that sleep at dusk | Towns that wake at moonrise: night markets, moon rites, caravans leaving (L8) |
| Civil war camps in every hold | Crown-and-Thalmor posts against Renrijra Krin and sugar smugglers (L9) |

---

## 1. World model sheet

Data target: `tamriel/world/elsweyr.json` (MS22): a province block plus five region blocks. Field ids below are the
data ids; cites in parentheses. `ew.` is the province block. The lint (`worldsheet_lint.py`) must pass on every field.

### 1.1 Province sheet (`elsweyr`)

**World truth.** Elsweyr is two old kingdoms sewn together under two moons and pulled apart again. Northern Anequina is
a high plateau of red mesas, badland gullies and dry steppe, where clans live by wells, herds and caravans. Southern
Pellitine is river valley, sugar-cane floodplain and the Tenmar jungle, rich from plantations and the port trade of
Senchal. The Mane at Torval keeps the faith of the moons' path while two kings rule the halves. Since the Void Nights,
the Thalmor claim credit for the moons' return and sit in both courts. Moon-sugar enriches the south, feeds the skooma
trade, and funds the Renrijra Krin above ancient moon-temples, dragon scours and a necropolis city.

| Layer | Fields `id` (cites): meaning |
| --- | --- |
| L1 Geology | `anequina_plateau`: a red sandstone and mudstone tableland cut into mesas, buttes and badland gullies. `central_escarpment`: a scarp of 60 to 120 blocks between the plateau and the southern lowland. `pellitine_alluvium`: deep river silts on the southern plain. `tenmar_karst`: limestone under the Tenmar forest with sinkholes and caves. `topal_coast`: a low coast of coves and bluffs on Topal Bay. `moonstone_seams`: moonstone in the quartz seams of the plateau. `dwemer_seam`: one deep Dwemer delve in the escarpment rock |
| L2 Climate | `rain_shadow_north` (L1.central_escarpment): the escarpment strips the bay wind of rain, so Anequina is arid. `bay_monsoon` (L1.topal_coast): wet summer winds off Topal Bay soak Pellitine and flood its rivers. `dust_season` (L1.anequina_plateau, L2.rain_shadow_north): spring dust storms on the plateau. `clear_nights` (L1.anequina_plateau, L2.rain_shadow_north): dry air and few clouds make the two moons the brightest thing in the sky. `lunar_lattice` (L1.anequina_plateau, L2.clear_nights): the moons' phases mark the calendar, the tides on the bay and, in Khajiit belief, the form a child is born into |
| L3 Water | `plateau_wells` (L1.anequina_plateau, L2.rain_shadow_north): Anequina drinks from deep wells, oases and cisterns at mesa feet. `scarp_springs` (L1.central_escarpment): a line of springs where the plateau's water seeps out of the escarpment. `pellitine_rivers` (L1.pellitine_alluvium, L2.bay_monsoon): braided rivers that flood each summer across the sugar plain to the bay. `old_aqueduct` (L1.central_escarpment, L3.scarp_springs): an arched aqueduct carries spring water across a gorge toward Torval. `water_power` (L1.anequina_plateau, L3.plateau_wells, L3.pellitine_rivers): in the north whoever holds a well holds a clan; in the south planters own the river frontage and the floodgates |
| L4 Ecology | `steppe_grazers` (L2.rain_shadow_north): nix-oxen, goats and wild herds on the dry grass. `canyon_hunters` (L1.anequina_plateau): big cats, scorpions and carrion birds in the gullies. `jungle_life` (L1.tenmar_karst, L2.bay_monsoon): serpents, giant wasps, apes and jungle cats in the Tenmar. `cane_plain` (L1.pellitine_alluvium, L3.pellitine_rivers): sugar cane needs the flood silts, so it grows only on the southern plain. `moon_sugar_bloom` (L2.lunar_lattice, L4.cane_plain): on the richest cane silts a glowing variety ripens only under full moons |
| L5 Economy | `moon_sugar` (L4.cane_plain, L4.moon_sugar_bloom): cane grown, boiled and crystallised in the south, sold as sweetener and sacrament. `skooma` (L4.cane_plain, L5.moon_sugar): the refined drug, illegal abroad and taxed or hidden at home. `caravans` (L3.plateau_wells, L4.steppe_grazers): Anequina's wealth is carrying goods from well to well. `herding_hides` (L4.steppe_grazers): hides, wool and milk from the steppe. `moonstone` (L1.moonstone_seams): mined and cut on the plateau. `senchal_port` (L1.topal_coast, L5.moon_sugar): sugar out, grain, timber and iron in. `rimmen_crafts` (L1.moonstone_seams, L5.caravans, L5.moonstone): carpets, glass and jewellery of Rimmen's artisans for the Cyrodiil trade |
| L6 Settlement | `towns_on_wells` (L3.plateau_wells): northern towns stand on wells, oases and mesa cisterns, never on rivers. `towns_on_rivers` (L1.topal_coast, L3.pellitine_rivers): southern towns stand on river terraces, fords and the coast. `torval_seat` (L3.old_aqueduct, L5.moon_sugar): the Mane's city sits where the aqueduct water meets the sugar plain. `busy_centres` (L5.senchal_port, L5.caravans, L5.rimmen_crafts): Senchal (the sugar port), Dune (the caravan wheel), Torval (court and pilgrims), Rimmen (the Cyrodiil border market). `failing_places` (L3.plateau_wells, L5.moon_sugar): plateau hamlets whose wells fail, cane villages sunk in debt, Knahaten-emptied villages left as ruins |
| L7 Society | `clan_mothers` (L3.water_power): kin-groups led by Clan Mothers who settle disputes and hold wells. `furstock_roles` (L2.lunar_lattice): the many Khajiit forms take different work, from small quick messengers to large warriors and near-elven envoys. `planters_and_cutters` (L3.water_power, L5.moon_sugar): plantation families own the land; cane-cutters and boilers work off debts. `baandari` (L5.caravans): wandering peddler families, welcome as traders, suspected as thieves. `skooma_poor` (L5.skooma, L5.senchal_port): addicts and dealers in the port slums. `imperial_descendants` (L6.busy_centres): families of Septim-era planters and garrison men, still in the plantation towns |
| L8 Culture | `moons_path` (L2.lunar_lattice): the faith of the Riddle'Thar, the order the moons dance in, kept by the Mane and the moon priests. `khajiit_gods` (L3.plateau_wells, L8.moons_path): Alkosh, Khenarthi, Jone and Jode, Azurah, S'rendarr, Rajhin and the other aspects; shrines at wells and crossroads. `dark_spirits` (L2.lunar_lattice, L8.moons_path): Namiira's darkness and the dro-m'Athra, souls fallen from the path, feared in ruins and on moonless nights. `sugar_sacred` (L5.moon_sugar, L8.moons_path): moon-sugar is the moons' gift in rite and a sin in excess. `night_life` (L2.clear_nights, L8.moons_path): markets, rites and departures happen at moonrise. `masked_dead` (L1.central_escarpment, L8.moons_path): honoured dead are laid in necropolis cities wearing burial masks. `outsiders` (L5.rimmen_crafts, L8.moons_path): polite to buyers, wary of Imperials over Rimmen, bitter at the Thalmor's claim on the moons |
| L9 Politics | `two_crowns` (L6.towns_on_wells, L6.towns_on_rivers, L7.clan_mothers): Anequina and Pellitine are separate client kingdoms of the Aldmeri Dominion. `thalmor_in_court` (L8.moons_path, L9.two_crowns): Thalmor advisers in both courts, credited with bringing back the moons. `renrijra_krin` (L5.skooma, L9.thalmor_in_court): outlaw bands who fight the Thalmor and the crowns that bow to them, paid by smuggled sugar. `sugar_tax` (L5.moon_sugar, L9.two_crowns): the crowns, the Mane and the Thalmor all want a share of the sugar. `rimmen_border` (L5.rimmen_crafts, L6.busy_centres): Rimmen faces Cyrodiil's Nibenay; old claims and new smuggling. `two_moons_procession` (L8.moons_path, L8.night_life): annual festival at the first double full moon from Torval to the moon-temples |
| L10 Hidden | `moon_temples` (L1.anequina_plateau, L8.moons_path): ruined and living temples on outcrops and ridges, aligned to moonrise. `necropolis_cities` (L1.central_escarpment, L8.masked_dead): whole cities of the masked dead under Rimmen and in the escarpment. `dragon_scours` (L1.anequina_plateau): bone fields and lairs on mesa tops. `renrijra_camps` (L1.anequina_plateau, L5.caravans, L9.renrijra_krin): camps in canyons and caves along the caravan roads. `skooma_dens` (L4.cane_plain, L5.skooma): hidden boil-rooms and dens in caves near plantations and under Senchal. `smuggler_coves` (L1.topal_coast, L5.skooma): hidden coves and wrecks on the bay. `dro_mathra_haunts` (L1.anequina_plateau, L8.dark_spirits, L10.moon_temples): fallen spirits in abandoned temples. `colossus_hall` (L1.dwemer_seam): the Dwemer delve and its unfinished giant. `daedric_cults` (L1.tenmar_karst, L8.dark_spirits): hidden cults of the darker Daedra |
| L11 History | `old_kingdoms` (L1.anequina_plateau, L6.towns_on_wells): Anequina and Pellitine as rival realms with a plateau city older than both, Ne Quin-al. `union_by_marriage` (L9.two_crowns): the two crowns joined by a royal marriage in the Second Era to make Elsweyr. `knahaten_flu` (L6.failing_places): a Second Era plague that emptied villages. `dragon_war` (L10.dragon_scours): dragons loosed over Elsweyr late in the Second Era, leaving the scours. `septim_plantations` (L7.imperial_descendants): Imperial planters and garrisons settled the south and the Rimmen road. `void_nights` (L2.lunar_lattice, L9.thalmor_in_court): in the early Fourth Era the moons vanished for two years; the Thalmor claim they brought them back and Elsweyr split into two client kingdoms afterwards |

### 1.2 Anequina (`elsweyr/anequina`, 5.58 km², seat Riverhold, levels 18-42)

**World truth.** Anequina is the high plateau of the north: red sandstone mesas over dry steppe, cut by badland gullies
where the Renrijra Krin hide. Clans gather at deep wells; Dune's market wheel turns on caravan traffic; Orcrest guards
the high mesa road; Riverhold, at the head of the only steady stream, keeps the northern crown's court. Ancient
moon-temples stand on the ridges, and the dragon war left a scour of bleached bones on Prowl's Edge. The Thalmor
resident counts the wells for the crown's tax, and the rebels poison the ones he counts.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `mesa_field`: mesas and buttes up to 150 blocks. `badland_gullies`: banded clay canyons. `small_erg`: a small dune field in the far west. `moonstone_quarries`: quartz and moonstone seams |
| L2 | `dust_storms` (ew.L2.dust_season, L1.mesa_field): spring dust gales across the tableland. `hot_days_cold_nights` (ew.L2.rain_shadow_north, L1.mesa_field): sharp desert temperature swings |
| L3 | `riverhold_stream` (ew.L3.scarp_springs, L1.mesa_field): the one perennial stream, fed by seepage. `oasis_chain` (ew.L3.plateau_wells, L1.mesa_field): Nevoe, Temar and Kojanar oases on the caravan line. `cisterns_of_orcrest` (ew.L3.plateau_wells, L1.mesa_field): rock-cut mesa reservoirs |
| L4 | `steppe` (ew.L4.steppe_grazers, L2.hot_days_cold_nights): short gold grass and scattered acacia. `mesa_top` (L1.mesa_field, L2.dust_storms): bare red rock and lone thorn trees. `gully_shade` (L1.badland_gullies): dry brush in canyon bottoms. `oasis_ring` (L3.oasis_chain): palms and green turf round wells. `dune_edge` (L1.small_erg): rippled western sands. `scorpion_gullies` (ew.L4.canyon_hunters, L1.badland_gullies): scorpions and big cats in the ravines |
| L5 | `caravan_trade` (ew.L5.caravans, L3.oasis_chain): well-to-well pack trains. `herds` (ew.L4.steppe_grazers, L4.steppe): nix-oxen and goat herding. `moonstone_cutting` (ew.L5.moonstone, L1.moonstone_quarries): plateau gem and ore quarries. `well_tolls` (L3.oasis_chain): water fees collected by clan holders |
| L6 | `riverhold_seat` (L3.riverhold_stream): the northern seat on its stream. `dune_wheel` (L3.oasis_chain, L5.caravan_trade): market city where five caravan routes meet. `orcrest_mesa` (L1.mesa_field, L3.cisterns_of_orcrest): fortified city on a mesa with cisterns. `seaplace` (ew.L7.imperial_descendants, L3.oasis_chain): an old Imperial waystation town |
| L7 | `well_clans` (ew.L7.clan_mothers, L5.well_tolls): northern kin-groups holding the oases. `caravan_masters_rich` (L5.caravan_trade): wealthy factors of Dune and Riverhold. `herders_poor` (L5.herds): steppe nomads |
| L8 | `ridge_temples` (ew.L8.moons_path, L1.mesa_field): high moon-shrines watching the sky. `scour_taboo` (ew.L8.dark_spirits, L1.mesa_field): the dragon bone fields are not walked at night |
| L9 | `northern_crown` (ew.L9.two_crowns, L6.riverhold_seat): the royal court at Riverhold. `well_counting` (ew.L9.thalmor_in_court, L5.well_tolls): the resented Thalmor census of wells. `canyon_rebels` (ew.L9.renrijra_krin, L1.badland_gullies): insurgents striking the tax roads |
| L10 | `renrijra_canyons` (ew.L10.renrijra_camps, L1.badland_gullies, L9.canyon_rebels): hidden rebel redoubts. `prowls_edge_scour` (ew.L10.dragon_scours, L1.mesa_field): scorched dragon bone field. `vault_of_the_heavenly_scourge` (ew.L10.moon_temples, L1.mesa_field, L8.ridge_temples): sealed lunar sanctuary. `tomb_of_the_moon_singers` (ew.L10.moon_temples, L1.mesa_field, L8.ridge_temples): cliff-cut cantor crypts. `moonstone_mines` (L1.moonstone_quarries, L5.moonstone_cutting): shafts abandoned when seams thinned |
| L11 | `ne_quin_al` (ew.L11.old_kingdoms, L1.mesa_field): the plateau city older than the kingdoms. `hill_of_swords` (ew.L11.old_kingdoms, L9.northern_crown): a battlefield of the old wars where blades still stand planted |

### 1.3 Rimmen (`elsweyr/rimmen`, 4.28 km², seat Rimmen, levels 18-40)

**World truth.** Rimmen is the eastern march facing Cyrodiil, where the plateau breaks into sandstone cliffs above the
Nibenay border. Rimmen is a city of artisans built on a cliff directly over its own necropolis; Alabaster quarries the
pale gypsum stone that dresses the southern temples. Border trade, Imperial grudges and Thalmor customs men make the
market rich and nervous. The Baandari peddlers camp at the city's edge, Hakoshae keeps the masked tombs of the old
magnates, and every caravan crossing to Cyrodiil pays someone twice.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `rimmen_cliff`: a sandstone cliff with tomb galleries cut in its base. `alabaster_beds`: pale gypsum stone beds. `eastern_canyons`: red border canyons |
| L2 | `border_winds` (ew.L2.rain_shadow_north, L1.rimmen_cliff): drier than Cyrodiil, wetter than central Anequina |
| L3 | `cliff_springs` (ew.L3.scarp_springs, L1.rimmen_cliff): perennial springs under the city cliff. `border_creek` (ew.L3.scarp_springs, L1.eastern_canyons): a creek along the Nibenay line |
| L4 | `savanna_edge` (L2.border_winds): tall grass and acacia. `canyon_floor` (L1.eastern_canyons, L3.border_creek): cottonwood and red sand. `quarry_scar` (L1.alabaster_beds): white stone dust and bare terraces. `cliff_spring_garden` (L1.rimmen_cliff, L3.cliff_springs): hanging vines and palms. `sugar_patch` (L3.border_creek): small wet-pocket cane plots |
| L5 | `artisan_trade` (ew.L5.rimmen_crafts, L3.cliff_springs): carpets, glass and metalwork. `alabaster_quarry` (L1.alabaster_beds): dressed white stone for temples. `border_market` (ew.L5.caravans, L3.border_creek): Nibenay-Elsweyr caravan exchange. `customs` (ew.L5.rimmen_crafts, L2.border_winds): border tariffs and inspections |
| L6 | `rimmen_seat` (L1.rimmen_cliff, L3.cliff_springs): the cliff city over springs and tombs. `alabaster_town` (L1.alabaster_beds, L5.alabaster_quarry): quarry town. `hakoshae_tombs` (ew.L8.masked_dead, L1.rimmen_cliff): village of tomb-keepers. `meir_lynmount` (L5.border_market): border waystation town |
| L7 | `master_artisans` (L5.artisan_trade, L6.rimmen_seat): hereditary guild families. `quarry_labourers` (L5.alabaster_quarry): stonecutters of Alabaster. `baandari_camp` (ew.L7.baandari, L5.border_market): peddler tents outside the gate |
| L8 | `mask_makers` (ew.L8.masked_dead, L7.master_artisans): carvers of funerary masks for the necropolis. `imperial_grudge` (ew.L8.outsiders, L6.rimmen_seat): long memory of Septim and potentate garrisons |
| L9 | `customs_war` (ew.L9.thalmor_in_court, L5.customs, L5.border_market): Thalmor customs officers against Imperial and Baandari smugglers. `rimmen_claim` (ew.L9.rimmen_border, L6.rimmen_seat): old Cyrodiil border claims |
| L10 | `rimmen_necropolis` (ew.L10.necropolis_cities, L1.rimmen_cliff, L8.mask_makers): the vast city of the masked dead beneath Rimmen. `mask_thieves` (L1.rimmen_cliff, L8.mask_makers, L9.customs_war): grave robbers selling ancestral masks abroad. `quarry_undercuts` (L1.alabaster_beds, L5.alabaster_quarry): collapsed galleries under Alabaster |
| L11 | `rimmen_traded_hands` (ew.L11.old_kingdoms, ew.L11.septim_plantations, L6.rimmen_seat): the city passed between crowns, Empire and potentates |

### 1.4 Torval (`elsweyr/torval`, 4.11 km², capital, levels 22-44)

**World truth.** Torval is the heart of the Khajiit faith, where arched aqueduct water from the escarpment springs
meets the first cane fields of the south. Torval is the Mane's city of concentric rings and twin golden domes, where
pilgrims climb the processional road at moonrise. Temples of the moons stand on every sandstone knoll around it, the
oldest abandoned to dro-m'Athra spirits. The southern crown courts the Mane, the Thalmor court both, and the sugar
exchange duty keeps the city fed. It is the place every Khajiit means when they say home.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `scarp_foot`: the central escarpment's southern foot. `temple_outcrops`: sandstone knolls rising out of the plain |
| L2 | `moon_sky` (ew.L2.clear_nights, L1.temple_outcrops): crystalline night skies over the knolls. `wet_season_edge` (ew.L2.bay_monsoon, L1.scarp_foot): the northern reach of the summer rains |
| L3 | `aqueduct_water` (ew.L3.old_aqueduct, L1.scarp_foot): spring water carried on stone arches. `torval_cisterns` (L1.scarp_foot, L3.aqueduct_water): the palace and temple fountains |
| L4 | `aqueduct_green` (L3.aqueduct_water): green strip of palms and grass under the arches. `temple_knoll` (L1.temple_outcrops, L2.moon_sky): incense trees and stone terraces. `first_cane` (ew.L4.cane_plain, L2.wet_season_edge): northernmost moon-sugar rows. `palm_court_gardens` (L3.torval_cisterns): ornamental pools inside the city ring. `savanna_park` (L1.scarp_foot, L2.wet_season_edge): open lowland grass |
| L5 | `pilgrim_trade` (L3.torval_cisterns, L4.temple_knoll): hostels, offerings and guides. `sugar_exchange` (ew.L5.moon_sugar, L4.first_cane): the round exchange that fixes province sugar prices. `temple_crafts` (L4.temple_knoll): brass lamps, masks and incense |
| L6 | `torval_capital` (ew.L6.torval_seat, L3.torval_cisterns): the Mane's city of rings. `pilgrim_villages` (L4.aqueduct_green, L5.pilgrim_trade): waystations along the processional road |
| L7 | `priestly_quarter` (ew.L8.moons_path, L6.torval_capital): moon-priests and clan speakers. `court_ohmes` (ew.L7.furstock_roles, L6.torval_capital): near-elven courtiers and scribes. `pilgrim_poor` (L5.pilgrim_trade): supplicants camped by the outer arches |
| L8 | `mane_seat` (ew.L8.moons_path, L6.torval_capital): the spiritual centre of the Lunar Lattice. `two_moons_procession` (L2.moon_sky, ew.L8.night_life, L8.mane_seat): the double-full-moon pilgrimage |
| L9 | `mane_succession` (L8.mane_seat): an ageing Mane and rival claimants. `southern_crown_pressure` (ew.L9.two_crowns, L5.sugar_exchange): royal leverage over the exchange. `thalmor_moon_claim` (ew.L9.thalmor_in_court, L8.mane_seat): envoys claiming credit for ending the Void Nights |
| L10 | `old_temples` (ew.L10.moon_temples, L1.temple_outcrops): ancient lunar sanctuaries on the knolls. `dro_mathra_in_old_temples` (ew.L10.dro_mathra_haunts, L1.temple_outcrops, L9.mane_succession): bent spirits haunting the sealed shrines. `sugar_slingers` (ew.L5.skooma, L4.first_cane, L5.sugar_exchange): illicit dealers beneath the exchange |
| L11 | `void_nights_memory` (ew.L11.void_nights, L8.mane_seat): the two years without moons, when temple bells were rung at the hour they vanished |

### 1.5 Pellitine (`elsweyr/pellitine`, 8.19 km², seat Corinthe, levels 22-45)

**World truth.** Pellitine is the wet south-west: river terraces and floodplain cane fields running into the Tenmar
jungle and the Valenwood gate. Corinthe is the garden city of canals; the plantation towns with Imperial names are
Septim-era foundations where Khajiit cutters work for old families. The Tenmar's limestone karst hides moon-temples, the
Halls of Colossus and the Temple of Agamanus. Summer floods bring wealth and marsh fever. Smugglers take the sugar
downriver by night, and the Green Pact border means Bosmer buy their grain and timber here.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `river_terraces`: deep alluvial silts along the southern rivers. `tenmar_sinkholes`: limestone karst and caves under the jungle. `colossus_scarp`: the Dwemer delve's rock face |
| L2 | `flood_summers` (ew.L2.bay_monsoon, L1.river_terraces): monsoon deluges that swell the rivers. `jungle_fog` (ew.L2.bay_monsoon, L1.tenmar_sinkholes): steaming morning mists under the canopy |
| L3 | `three_rivers` (ew.L3.pellitine_rivers, L1.river_terraces): the braided sugar rivers. `canal_city_water` (L1.river_terraces, L3.three_rivers): Corinthe's stepped canals. `cenotes` (L1.tenmar_sinkholes): deep limestone sinkhole pools |
| L4 | `cane_floodplain` (ew.L4.cane_plain, L3.three_rivers): rich silt cane fields. `gallery_forest` (L3.three_rivers): riverside trees. `tenmar_canopy` (ew.L4.jungle_life, L2.jungle_fog): dense jungle of apes, wasps and serpents. `cenote_glade` (L3.cenotes): fern-ringed sinkholes. `terraced_gardens` (L3.canal_city_water): Corinthe's flower and spice terraces. `moon_sugar_meadow` (L3.three_rivers, ew.L4.moon_sugar_bloom, L4.cane_floodplain): glowing full-moon cane beds |
| L5 | `plantations` (ew.L5.moon_sugar, L4.cane_floodplain): great sugar estates. `boil_houses` (L4.cane_floodplain, L5.plantations): cane-crushing and crystallising mills. `pact_trade` (ew.L5.senchal_port, L4.gallery_forest): grain and timber sold to Valenwood across the border. `river_fishing` (L3.three_rivers): barge and net fisheries |
| L6 | `corinthe_seat` (L3.canal_city_water, L5.plantations): the southern garden-and-canal seat; Ein Meirvale, Greenhall and Portneu View as plantation towns; Tenmar Forest and Darvulk Haven at the jungle edge |
| L7 | `planter_houses` (ew.L7.planters_and_cutters, ew.L7.imperial_descendants, L5.plantations): old estate dynasties. `cutter_debt` (L5.boil_houses): cane-cutters bound by seasonal tallies. `pact_merchants` (L5.pact_trade): border factors trading with Bosmer |
| L8 | `garden_priests` (ew.L8.khajiit_gods, L6.corinthe_seat): Corinthe's garden temple liturgy. `fever_shrines` (ew.L8.khajiit_gods, L2.flood_summers): riverside shrines to S'rendarr against summer marsh fever |
| L9 | `planter_vs_crown` (ew.L9.sugar_tax, L7.planter_houses): planters resisting the southern crown's sugar duty. `cutter_unrest` (L7.cutter_debt): strikes and cane-burnings in the boil-houses. `valenwood_gate` (L5.pact_trade): border fort and customs tension |
| L10 | `halls_of_colossus` (ew.L10.colossus_hall, L1.colossus_scarp): the Dwemer delve and its giant frame. `temple_of_agamanus` (ew.L10.moon_temples, L1.tenmar_sinkholes): jungle-swallowed lunar temple. `plantation_dens` (ew.L10.skooma_dens, L4.cane_floodplain, L9.cutter_unrest): hidden skooma boil-caves. `river_wrecks` (L1.river_terraces, L3.three_rivers): sunken sugar barges |
| L11 | `septim_towns` (ew.L11.septim_plantations, L6.corinthe_seat): Imperial-named plantation foundations. `flu_villages` (ew.L11.knahaten_flu, L1.tenmar_sinkholes): overgrown Second Era villages emptied by plague |

### 1.6 Senchal (`elsweyr/senchal`, 3.55 km², seat Senchal, levels 24-45)

**World truth.** Senchal is the sugar coast on Topal Bay: a loud, sweet-smelling port stepping down from a hill palace
through tiered warehouses to a wide stone quay where everything Elsweyr grows leaves by ship and everything it lacks
arrives. Customs men, sugar boilers, sailors, skooma lords and Thalmor envoys share the same crowded streets. Coves
along the headlands hide smugglers and wrecks; dragonscours on the bluffs recall the Second Era dragon war. It is rich,
crowded and the least trusted city in Elsweyr.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `harbour_bay`: a deep sheltered bay on Topal Bay south of Torval. `headlands`: limestone and sandstone coastal bluffs |
| L2 | `sea_breeze` (ew.L2.bay_monsoon, L1.harbour_bay): warm salt winds into the port. `storm_season` (ew.L2.bay_monsoon, L1.headlands): late-summer bay typhoons |
| L3 | `harbour_wells` (L1.harbour_bay): brackish lower wells and palace rain cisterns. `river_mouth` (ew.L3.pellitine_rivers, L1.harbour_bay): the sugar river estuary |
| L4 | `cove_beach` (L1.harbour_bay): sandy smuggler coves. `headland_scrub` (L1.headlands, L2.sea_breeze): wind-bent coastal brush. `coastal_cane` (ew.L4.cane_plain, L3.river_mouth): estuary cane flats. `mangrove_mouth` (L3.river_mouth): brackish mangrove shallows. `reef_shallows` (L1.harbour_bay): warm bay reefs |
| L5 | `sugar_export` (ew.L5.senchal_port, L4.coastal_cane): the great sugar warehouses and quays. `customs_house` (L1.harbour_bay, L5.sugar_export): harbour tariffs. `shipwrights` (L1.harbour_bay): cove shipyards and ropewalks |
| L6 | `senchal_seat` (L1.harbour_bay, L5.sugar_export): the tiered port capital. `cove_villages` (L1.headlands, L4.cove_beach): fishing and landing villages. `potentates_retreat` (ew.L7.imperial_descendants, L1.headlands): the old governor's cliffside estate |
| L7 | `palace_hill` (L5.sugar_export, L6.senchal_seat): merchant princes and court factors on the upper terraces. `lower_sweet` (ew.L7.skooma_poor, L5.sugar_export): the boil-district slum by the docks. `sailors_and_boilers` (L5.shipwrights, L6.senchal_seat): dock guilds and stokers |
| L8 | `khenarthi_winds` (ew.L8.khajiit_gods, L2.sea_breeze): sailors' cliff shrines to the wind goddess |
| L9 | `skooma_lords` (ew.L5.skooma, L7.lower_sweet): syndicate bosses running the night docks. `thalmor_embassy` (ew.L9.thalmor_in_court, L7.palace_hill): Dominion naval inspectors. `customs_corruption` (ew.L9.sugar_tax, L5.customs_house): bribed manifests and night lighters |
| L10 | `the_tangle` (L1.harbour_bay, L9.skooma_lords): the undercity of old sugar tunnels beneath Senchal. `smuggler_coves` (ew.L10.smuggler_coves, L1.headlands, L9.customs_corruption): sea-caves and wrecks along the bluffs. `twin_scours` (ew.L10.dragon_scours, L1.headlands): north and south headland dragon bone-fields |
| L11 | `south_guard` (ew.L11.septim_plantations, L1.headlands): a ruined Imperial coastal fort overlooking the bay |

---

## 2. Micro-biomes

MS21 drivers available per region: elevation band, slope and aspect, rain-shadow raster, heat, distance to river,
lake and coast, valley against ridge, soil from L1 and disturbance masks. Each micro-biome below is a sub-biome or
overlay key in the region's `micro` table (`palettes.json`, written by `tools/worldgen/biomes.py`). Target: >= 6 per
region and >= 2 per km², >= 3 transitions per km of road. **Fix first:** Rimmen's palette carries `grazelands`, a
Morrowind biome with no Elsweyr cause; replace it with `rimmen_cliff_garden` and `quarry_scar`.

| Region | Micro-biome (key) | Rule (MS21) | Cause | Flora and ground | Weather | Creatures | Work |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Anequina | `mesa_top` | UPLAND, slope < 0.2, elev > p75 | mesa_field | thin grass, bare red rock, lone thorn trees | wind, dust | vultures, cats' lookouts | watch posts, temples |
| Anequina | `badland_gully` | LOWLAND, ridge-adjacent, rainshadow > 0.7 | badland_gullies | bare banded clay, dead brush | heat haze | scorpions, rebels | Renrijra camps, quarries |
| Anequina | `dry_steppe` | LOWLAND, rainshadow 0.5-0.8 | P.rain_shadow_north | short gold grass, scattered acacia | dust_storms | nix-oxen, herds | herding |
| Anequina | `oasis_ring` | within 48 of a well place or spring | oasis_chain | palms, reeds, green turf | cool mornings | birds, herds at dusk | caravan stops |
| Anequina | `dune_edge` | west strip, sand soil | small_erg | rippled sand, dune grass | sand drift | sand snakes | none (crossed) |
| Anequina | `scour_bone_field` | disturbance mask round scour sites | prowls_edge_scour | bleached ground, bone scatter, scorch | still air | carrion birds | none (taboo) |
| Anequina | `cistern_terraces` | settlement ring on mesa | cisterns_of_orcrest | irrigated terraces of millet and figs | n/a | goats | farming |
| Rimmen | `canyon_floor` | river-adjacent in canyon | eastern_canyons | cottonwood, reeds, red sand | shade fog | cats, scorpions | trails |
| Rimmen | `rimmen_cliff_garden` | cliff foot near springs | cliff_springs | hanging gardens, vines, palms | damp | birds | gardens, shrines |
| Rimmen | `quarry_scar` | disturbance mask round Alabaster | alabaster_quarry | white dust, bare stone | dust | none | quarrying |
| Rimmen | `savanna_edge` | LOWLAND, rainshadow 0.3-0.5 | border_winds | tall grass, acacia, baobab-like trees | summer storms | herds, big cats | grazing |
| Rimmen | `border_creek_meadow` | within 32 of border_creek | border_creek | green meadow grading to Nibenay plants | morning mist | deer, birds | smuggling fords |
| Rimmen | `sugar_patch` | lowest wet pockets | savanna_edge + creek | small cane plots | n/a | none | small farms |
| Torval | `aqueduct_green` | within 40 of the aqueduct line | aqueduct_water | green strip of palms and grass under the arches | dew | birds | gardens |
| Torval | `temple_knoll` | outcrop tops | temple_outcrops | bare stone, incense trees, terraces | clear nights | none | temples |
| Torval | `first_cane` | LOWLAND, heat > 0.5, south of scarp | P.cane_plain | cane rows | humid | rats, snakes | plantations |
| Torval | `palm_court_gardens` | inside city ring | torval_cisterns | ornamental palms, pools | n/a | birds | city gardens |
| Torval | `savanna_park` | open lowland | P.rain_shadow_north (edge) | grass, scattered trees | storms | herds | grazing |
| Torval | `scarp_foot_scree` | slope > 0.5 under scarp | scarp_foot | scree, thorn | rockfall | lizards | none |
| Pellitine | `cane_floodplain` | LOWLAND, within 160 of river, elev < p40 | P.cane_plain | cane, levees, ditches | flood_summers | rats, crocodile-like reptiles | plantations |
| Pellitine | `gallery_forest` | within 24 of river, outside cane | three_rivers | tall riverside trees, vines | fog | birds, monkeys | fishing |
| Pellitine | `tenmar_canopy` | east half, UPLAND/LOWLAND, wet | P.tenmar_karst + P.bay_monsoon | dense jungle, lianas, giant ferns | rain, fog | serpents, wasps, jungle cats | hunting |
| Pellitine | `cenote_glade` | sinkhole cells | cenotes | open pool, hanging roots | still | bats, birds | shrines, hideouts |
| Pellitine | `terraced_gardens` | Corinthe ring | canal_city_water | fruit terraces, beehives | n/a | bees | orchards |
| Pellitine | `moon_sugar_meadow` | richest silt cell, rare (< 2%) | P.moon_sugar_bloom | glowing cane flowers at full moon | n/a | moths | sacred harvest |
| Pellitine | `burnt_cane` | disturbance near unrest sites | cutter_unrest | ash rows, regrowth | smoke | none | ruin, quest site |
| Senchal | `cove_beach` | BEACH in a cove | headlands | pale sand, palms | sea breeze | crabs, gulls | fishing villages |
| Senchal | `headland_scrub` | coastal ridge | headlands | salt-scrub, wind-bent trees | gales | birds of prey | watchtowers |
| Senchal | `coastal_cane` | coastal lowland | P.cane_plain | cane to the dunes | humid | rats | plantations |
| Senchal | `mangrove_mouth` | river mouth, tidal | river_mouth | mangroves, mud | fog | crabs, mudcrabs | smuggling |
| Senchal | `reef_shallows` | OCEAN, depth < 6 | harbour_bay | coral, sea grass | clear | fish, slaughterfish | pearl diving |
| Senchal | `port_slope` | settlement ring | sugar_export | terraced whitewashed yards | n/a | cats, rats | warehouses |

Counts: Anequina 7, Rimmen 6, Torval 6, Pellitine 7, Senchal 6 kinds (all >= 6). The per-km² check (>= 2) counts
patches, so a large region needs repeated patches, not more kinds. Pellitine (8.19 km²) is the thinnest; add
`river_island`, `levee_orchard` and `jungle_ruin_clearing` to break up the long cane and canopy stretches.

---

## 3. Map changes and geography

### 3.1 Relief (SCALE-AND-GEOGRAPHY 7)

| Measure | Now | Target | How (vectors in `tamriel_vectors.json`, never global noise) |
| --- | --- | --- | --- |
| Height p50 | 19 | 30 | raise the Anequina plateau base to 100-130 (regions.json already has `base: 125` for Anequina; the raster does not reflect it) |
| Height p90 | 64 | 130 | mesas 60-150 above the plateau floor; Prowl's Edge and Ne Quin-al as the two highest |
| Ruggedness | 1.5 | 3.5 | badland gullies (an `areas` entry with `crag` 0.85 and a gully channel mask), Rimmen cliff, escarpment |
| Signature landform | none | **the Central Escarpment** | a `ranges` polyline from the Valenwood border near Corinthe's north to the Rimmen cliff, 60-120 blocks high, with three passes (Riverhold-Torval road, the aqueduct gorge, Rimmen) |

### 3.2 Coast and water

* **Senchal harbour.** Arena puts sea where NirnCraft has land south of Torval (SCALE 5.6). Cut a deep bay so Senchal
  sits on a real harbour and Torval is near the water. Verify against the lore map before editing.
* **Pellitine rivers.** Three rivers (named in data, our names) run from the escarpment springs to Topal Bay, braided
  on the floodplain with levees; Corinthe's canals take from the middle one. Add to `hydrology.json`.
* **Escarpment springs** as a line of small waterfalls (10-30 blocks) down the scarp; one becomes the aqueduct head.
* **Oases** on the Anequina caravan line as small `lakes` entries (Nevoe, Temar, Kojanar, Mara, Sakhi already exist as
  places; give the water to the ones on roads).

### 3.3 Place fixes

| Fix | Why |
| --- | --- |
| Move `rimmen_necropolis` from the Torval region to beneath Rimmen's cliff | The city "sits atop its dead" (MARQUEE); the lore-web els_2 finale is at Rimmen |
| Re-tier: Riverhold city (seat), Dune city (free market, council not court), Orcrest town (fort-town) | One seat per region (4.4); Dune's lore weight earns a city plan without a second court |
| Pellitine seat = Corinthe (add `capital: corinthe` in regions.json) | The region has no seat today |
| Convert 16 `daedric_shrine` places to Khajiit aspect shrines (Azurah, Boethra, Mafala, Hermorah, Sheggorath, Rajhin, S'rendarr) and keep 3 as hidden dark cults (Namiira, Merrunz, one Molag Bal) | Khajiit worship these as aspects of the Riddle'Thar; Daedric shrines for Clavicus Vile, Peryite, Vaermina, Hircine, Sanguine have no cause in L8 |
| Add `south_guard_ruins` as the ruined Imperial fort (Arena's one missing village) | Keeps the name as a ruin with a cause [Senchal L11] |

### 3.4 Arena's non-Khajiit names

Arena's generator gave many Elsweyr towns Breton or Nord sounding names (Brukreich Bridge, Ein Meirvale, Darvulk
Haven, Helkarn Land, Heimthor Mount, Markgran Brook, Verkarth Hills, Kings Walk, Seaplace). **Keep them as Imperial
foundations**: plantation towns, garrison towns and waystations founded in the Septim era [P.septim_plantations],
built in a `pellitine_colonial` sub-kit (Imperial stone ground floors under Khajiit awnings and domes) with Khajiit
nicknames in dialogue. Names that are only noise (`helkarn_land`, `markgran_brook`) are renamed from a Khajiit name
pool. This turns a source limit into history.

### 3.5 XL silhouettes (weenies; >= 1 per region + 1 per 3 km²)

| Region | Target XL | Silhouettes |
| --- | --- | --- |
| Anequina | 2 | Prowl's Edge rib ridge; Ne Quin-al plateau ruin with a broken colossus gate |
| Rimmen | 2 | Rimmen cliff palace; the Alabaster quarry face (white wall seen for kilometres) |
| Torval | 2 | the Mane's twin domes (lit blue at night); the aqueduct arches across the gorge |
| Pellitine | 3 | Corinthe's garden cathedral; the Halls of Colossus scarp door; a Tenmar emergent tree over the canopy |
| Senchal | 2 | the hill palace above the quay; the Khenarthi's Breath temple tower on the headland |

---

## 4. Roads, travel, fast travel

### 4.1 Named highways (`roads/named_roads.json`)

| id (ours) | Class | Route (place ids) | Notes |
| --- | --- | --- | --- |
| `well_road` | highway | riverhold > kojanar_oasis > dune > temar_oasis > orcrest | Anequina's caravan line, oasis to oasis |
| `moonrise_way` | highway | orcrest > (escarpment pass) > valley_guard > torval | the pilgrim road; the last 800 blocks is the processional avenue aligned to moonrise |
| `cane_road` | highway | torval > tasha > senchal | sugar to the port; the busiest road in the province |
| `rimmen_road` | highway | dune > meir_lynmount > rimmen > alabaster | the border market road |
| `garden_road` | road | torval > brukreich_bridge > corinthe > greenhall > valenwood_gate | west to the Pact border |
| `tenmar_track` | lane | corinthe > tenmar_forest > darvulk_haven | jungle track with river fords |
| `cyrodiil_rimmen` (cross-province) | highway | rimmen > (border fort) > Nibenay road to Bravil or Leyawiin | SCALE 6.2 |
| `strid_south` (cross-province) | road | valenwood_gate > rawlkha (Reaper's March) | the Pact trade road |

Waysides every 1.5-3 km: the roadhouses already placed (`roadside_twin_moons`, `the_prancing_netch`,
`roadside_sleeping_sand_cat`, `roadside_dusk_caravan`, `roadside_saffron_tent`) get positions on these lines. Every
junction gets a signpost in Ta'agra script with a carved moon-phase symbol (prop variant).

### 4.2 Travel modes (MS11; >= 2 beyond walking and horse)

| Mode | Nodes | Identity rule | Status |
| --- | --- | --- | --- |
| **Caravans** | Riverhold, Dune, Orcrest, Rimmen, Torval, Senchal, Corinthe | Leave at moonrise only (a "wait for moonrise" option at the caravan master); the trip shows the land as a caravan (not a fade); 1 in 6 trips is interrupted by a scripted encounter (Renrijra toll, sandstorm, Baandari trade) | NEW: a `Carriages` variant with a trader NPC and pack animals, data `roads/caravans.json` |
| **Ships** | Senchal (to Leyawiin, Lilmoth, Woodhearth: routes exist) | Customs check scene on boarding | EXISTS (`ports.json`) |
| **River barges** | Corinthe, The Stitches, Senchal river mouth | Down-river only in the flood season; poled back in the dry | NEW: river route in the ports/ferry code (shared with Cyrodiil's Niben boats) |
| **Moon shrines** (wilderness nodes) | the aspect shrines (3.3) and the moon-temples' outer courts | Free to use; a blessing that depends on the moon phase | NEW (MS11 wilderness nodes) |

Fast-travel coverage: p90 distance to a node <= 500, max <= 800 (F-TRAVEL). Anequina's dune edge is the likely gap:
add a shrine at `teal_campsite`.

---

## 5. Settlements

### 5.1 Tier list (target tiers; place ids)

| Region | Seat | Cities | Towns | Villages (selected) | Hamlets/farms | Count now -> target |
| --- | --- | --- | --- | --- | --- | --- |
| Anequina | `riverhold` (city, northern crown) | `dune` (free market city) | `orcrest`, `seaplace` | `kae`, `haseae`, `khazir`, `tehai`, `thaal`, `jeqara`, plus inns | 10 sugar farms are wrong here (no cane on the plateau): convert to herding steads and well-farms | 31 places, keep count; re-type 6 farms |
| Rimmen | `rimmen` (city) | `alabaster` (quarry city) | `meir_lynmount`, `hakoshae` | `chasemoor`, `river_keep`, `nera_cove` | 11 farms: keep 4 sugar (sugar_patch), rest herding | keep |
| Torval | `torval` (capital, Mane and southern crown) | none | none | `valley_guard`, `duncori_walk`, `tasha` as pilgrim villages | 9 sugar farms (correct: first_cane) | add 1 town: a pilgrim town at the foot of the processional road |
| Pellitine (west) | `corinthe` (city seat) | none | `ein_meirvale`, `greenhall`, `portneu_view`, `brukreich_bridge` | docks villages on river mouths | plantations | keep |
| Pellitine (east) | none | none | `tenmar_forest`, `darvulk_haven`, `the_stitches` | `rinkha_wharf`, `nanial`, `sra` | plantations | keep |
| Senchal | `senchal` (city) | none | none -> add `black_heights` as a town | cove villages | 3 farms + `potentates_retreat` | +1 town |

### 5.2 City plan briefs (`tamriel/cityplans/*.json`)

| City | Plan form | Districts and class gradient (L7) | Landmark and silhouette | Why it is here |
| --- | --- | --- | --- | --- |
| **Torval** (exists, replace the Sentinel copy) | RING: three concentric ring streets round the twin domes, cut by the moonrise avenue | Mane's Court and priests' ring > Ohmes courtiers' ring > artisans and pilgrims' ring > the outer camp of pilgrims and cane-cutters' families | The Mane's Court (two linked domes for the two moons), the round Moon-sugar Exchange, the open-roofed observatory | Aqueduct water meets the cane plain [Torval L6] |
| **Rimmen** (exists) | CLIFF: palace on the crown, craft terraces stepped down, necropolis gate at the base | Palace and customs > master artisans' halls > workshops and markets > Baandari camp and quarry labourers outside the wall | Cliff palace; the necropolis gate in the cliff foot | Cliff springs and tombs [Rimmen L6] |
| **Senchal** (new) | HARBOUR with terraces | Palace hill > merchant terraces and customs > boil-yards and warehouses > the Lower Sweet slum on the quay | Stepped white warehouse roofs, sugar-boil chimneys, hill palace | The harbour bay and the sugar export [Senchal L6] |
| **Dune** (new) | WHEEL: a round market with eight radial lanes | Wheel-Master council and caravan houses at the hub > tent streets > camel and nix-ox pens at the rim > squatters | The Great Wheel market roof | Five caravan routes meet at its wells [Anequina L6] |
| **Corinthe** (new) | CANAL | Garden cathedral and planter mansions > canal market > orchard workers' terraces > flood-prone lower bank | Garden-roofed cathedral, arched canal bridges | River water and terraces [Pellitine L6] |
| **Riverhold** (new) | STREAM-TOWN: one long street along the stream, mills and granaries on stilts | Northern crown's court at the stream head (highest, first water) > merchants > herders downstream (last water) | The crown's well-hall over the stream's source | The only perennial stream in Anequina [Anequina L6] |
| **Alabaster** (new) | QUARRY TERRACES | Quarry-owners' white houses > masons > labourers in the pit village | The cut white quarry face | Alabaster beds [Rimmen L6] |
| Orcrest, Hakoshae (MARQUEE briefs) | HILLTOP, RING | as MARQUEE | Claw Gate; moonstone ring | cisterns; tombs |

Kits: `khajiit` (exists) splits into **`khajiit_plateau`** (red mud-brick, flat roofs, wind towers, cisterns),
**`khajiit_pellitine`** (whitewash, domes, awnings, garden courts), **`khajiit_caravan`** (tents, pens, canopies) and
**`pellitine_colonial`** (3.4). Target `y_looks` >= 3 per region (now 1-2).

### 5.3 Bustling centres and why

* **Senchal**: the one deep harbour on the sugar coast; every crate of sugar leaves here, so customs, crime and
  wealth concentrate here (L5 sugar_export).
* **Dune**: the wells where five caravan routes meet; it is busiest at night when caravans arrive (L6 dune_wheel,
  P.night_life).
* **Torval**: the Mane, the southern crown and the pilgrim trade; busiest at full moon and festival (L8).
* **Rimmen**: the only paved road to Cyrodiil; artisans sell to Imperial buyers (L5 border_market).
* Poor or failing: plateau hamlets in a dry year (Anequina L3), cane villages in debt (Pellitine L7 cutter_debt),
  the Knahaten-emptied villages kept as ruins (P.knahaten_flu). Each gets a visible sign: dry troughs, debt notices,
  boarded doors.

### 5.4 Class disparity (visible in plans, NPCs and quests)

| Rank (top to bottom) | Who | Where they live | What the player sees |
| --- | --- | --- | --- |
| Sacred | The Mane, moon priests | Torval's inner ring, temples | Rites at moonrise, petitioners |
| Crowned | Kings of Anequina and Pellitine, their courts | Riverhold well-hall, Corinthe/Torval palaces | Courts with a Thalmor adviser at the king's elbow |
| Foreign power | Thalmor advisers, customs men | Embassies in Senchal and Torval | Inspections, informants, arrests of rebels |
| Wealth | Planters, caravan masters, master artisans, skooma lords | Hill terraces, plantation houses, caravan houses | Silk, guards, debt ledgers |
| Kin-power | Clan Mothers | The well-yards of every village | Disputes settled in public |
| Working | Herders, boilers, cane-cutters, quarrymen, sailors | Lower terraces, plantation rows, pit villages | Burns, debt, songs at work |
| Wandering | Baandari peddlers | Camps outside walls | Bargains and accusations |
| Outcast | Renrijra Krin, skooma addicts, dro-m'Athra-touched | Canyons, the Lower Sweet, abandoned temples | Tolls on the road, dens, haunted ruins |

---

## 6. Dungeons, underground, landmarks

### 6.1 What lies below and why

| Underground | Where | Why (sheet) |
| --- | --- | --- |
| Moon-temple undercrofts | Ridges and outcrops in Anequina and Torval | Temples were cut down into the rock to the moon-wells [P.moon_temples] |
| Necropolis layers | Under Rimmen's cliff and the escarpment | Masked dead were laid in city-like galleries [P.necropolis_cities] |
| Karst caves and cenotes | Tenmar | Limestone under the jungle [P.tenmar_karst] |
| Escarpment seep caves | Along the scarp | Spring water in the rock [P.scarp_springs] |
| Dwemer delve | Halls of Colossus in the Pellitine scarp | The one Dwemer seam [P.colossus_hall] |
| Sugar tunnels | Under Senchal (the Tangle) | Smugglers dug past customs [Senchal L10] |
| Moonstone mines | Anequina mesas | Moonstone seams [P.moonstone_seams] |

### 6.2 Inhabitant themes (>= 8 per region, >= 10 target; signature theme per region)

Province themes: moon-temple guardians and dro-m'Athra; masked dead (necropolis); Renrijra Krin (canyon camps);
skooma gangs (dens, coves); dragon-scour beasts and a wyrm; scorpion and big-cat dens; jungle serpents and wasps;
Dwemer constructs; Thalmor-held forts; Imperial-era ruins with bandits; smugglers (coves, wrecks); dark Daedric cults;
Baandari thieves' caches.

| Region | Signature theme (found mainly here) | Marquee dungeon | Other major |
| --- | --- | --- | --- |
| Anequina | **Renrijra Krin canyon camps** | `vault_of_the_heavenly_scourge` (exists: moon-gate shutters) | `tomb_of_the_moon_singers`, `prowls_edge_dragonscour`, `moon_gate_of_anequina` |
| Rimmen | **The masked dead** | `rimmen_necropolis` (moved here; lunar clock) | `hakoshae_tombs`, `moongrave_fane`, `temple_of_the_full_moons` |
| Torval | **Dro-m'Athra in fallen temples** | `temple_of_the_purifying_moons` (new marquee plan: a temple that "re-falls" each moonless night) | `two_moons_at_tenmar_temple`, `sugar_slingers_den`, `tenarr_zalviit_ossuary` |
| Pellitine | **Jungle karst and the colossus** | `halls_of_colossus` (exists) | `temple_of_agamanus` (Arena's starting dungeon), `hidden_moon_crypts`, `scab_ridge_dragonscour` |
| Senchal | **Skooma lords' tunnels** | `the_tangle` (new marquee plan: the undercity of sugar tunnels, warehouses and drowned boil-rooms) | `khenarthis_breath_temple`, `north_dragonscour`, `south_dragonscour`, `forsaken_citadel` |

Every dungeon gets an identity line in the form "who is inside, because" (`d_identity` 0.09 -> 1.0). Examples:
`sleepy_senche_mine`: a moonstone mine abandoned when the seam thinned, now a skooma boil-room because it is dry and
hidden [Anequina L10]. `wreck_of_the_tipsy_griffon`: a sugar cog wrecked on the river bar; smugglers strip it at low
water [Pellitine L10].

### 6.3 Counts (current vs floor; dungeons with interiors, `d_density` x km²)

| Region | Now | Floor (8.9/km²) | Abs. floor (6.7/km²) | Unmarked target (0.57/km²) | Action |
| --- | --- | --- | --- | --- | --- |
| Anequina | 36 | 50 | 37 | 3 | +14: Renrijra camps (4), moonstone mines (3), scour lairs (2), well-caves (3), 2 unmarked |
| Rimmen | 41 | 38 | 29 | 2 | at floor; give identity, add 2 unmarked tombs |
| Torval | 31 | 37 | 28 | 2 | +6 fallen temples and dens |
| Pellitine | 67 | 73 | 55 | 5 | +6 cenote caves; 5 unmarked |
| Senchal | 33 | 32 | 24 | 2 | at floor; identity; 2 unmarked coves |

### 6.4 Landmarks (L-tier kinds per region >= 10, XL in 3.5)

Region-own L kinds: mesa arch, balanced rock, badland hoodoos (Anequina); cliff tomb doors, white quarry face
(Rimmen); aqueduct arches, temple stairs (Torval); cenote, emergent tree, levee (Pellitine); sea arch, headland
lighthouse, reef (Senchal). Marquee landmarks: `hill_of_shattered_swords` (rings in wind), `ne_quin_al` (dusk vista),
`shrine_of_the_cats_divine`, `anequina_aqueduct` (bridge-top walk).

Wonder moments (F-WONDER >= 3 per region): the double full moon rising over the domes (Torval); a dust wall crossing
the plateau at sunset (Anequina); the moon-sugar meadow glowing (Pellitine); a caravan's lanterns strung across the
dark steppe (Anequina, Rimmen); the sugar fleet leaving Senchal at dawn (Senchal); the Hill of Swords ringing.

---

## 7. Quests

### 7.1 Province arc (>= 19 quests; MS1 schema): **The Borrowed Moons**

The Thalmor say they returned the moons; an old moon priest says the moons were never gone, only hidden, and that
the Thalmor know how. The arc crosses every region: a missing lunar ledger at Torval (els_1 thread), Renrijra Krin
raids in Anequina, the masks of Rimmen (els_2), the sugar glow and the colossus in Pellitine (els_3), and the
Oath-Forge (els_4) for the finale's blade. Acts: I Torval and the ledger (4 quests); II North: wells and rebels (5);
III East: masks and customs (4); IV South: the glow beneath the sugar (4); V the Two-Moons Procession and the choice
(2). Consequence: the Mane's succession and the Thalmor adviser's fate change court dialogue in both crowns.

### 7.2 Exclusive faction choice (P-J1)

**The Renrijra Krin or the Crown Guard.** The player can join the rebels (hub: a canyon fastness in Anequina; 8
quests: tolls, raids on Thalmor caravans, freeing cutters, a final strike on the Senchal embassy) or the crowns'
Lunar Guard (hub: Torval barracks; 8 quests: escorting caravans, breaking skooma rings, hunting a rebel captain).
Joining one closes the other. The Mane stays above both.

### 7.3 Faction hubs (>= 2 per province, different cities)

| Faction | Hub | Quests (have -> target) |
| --- | --- | --- |
| Moon Priests and the Mane | Torval | `moon_priests.json` 6 -> 10 |
| Renrijra Krin (exclusive) | Anequina canyon fastness | 0 -> 8 |
| Lunar Guard (exclusive) | Torval | 0 -> 8 |
| Baandari peddlers (trade and theft guild) | Dune | 0 -> 6 |
| Aldmeri Dominion / Thalmor (shared file) | Senchal embassy | `aldmeri_dominion.json` has `ad_the_manes_blessing`; add 3 Elsweyr quests |

### 7.4 Region stories (hold conflict chains, >= 3 quests each)

| Region | Chain | Places | Consequence |
| --- | --- | --- | --- |
| Anequina | **The Counted Wells**: the Thalmor census, poisoned wells and a Clan Mother's choice | riverhold, kojanar_oasis, a Renrijra camp, dune | A well-clan sides with the crown or the rebels; caravan tolls change |
| Rimmen | **Faces for Sale** (lore-web els_2 extended): mask thieves, Thalmor customs, Imperial buyers | rimmen, hakoshae, baandari_caravan_stop, rimmen_necropolis | The necropolis is resealed or opened to trade |
| Torval | **The Mane's Last Moon**: rival claimants to the next Mane | torval, temple_of_the_purifying_moons, valley_guard | A new Mane chosen; court lines change |
| Pellitine | **Debt in the Cane**: cutters strike, a planter burns his own field | corinthe, tathara_sugar_farm, ein_meirvale, a plantation den | A plantation freed or broken; village state swap |
| Senchal | **The Bitter Batch** (MARQUEE) extended: poisoned sugar to the Tangle's lord | senchal, the_tangle, a smuggler cove | A skooma lord falls; dock dialogue changes |

### 7.5 Settlement quests and radiant

Every seat carries its MARQUEE chain (The Lunar Ledger, A Pattern from Below, The Bitter Batch, The Missing Caravan,
The Garden's Bees, The Dry Season, Moon Alignment) plus 2 favours per town. Culture radiant templates (>= 6 for the
Khajiit): escort a caravan at night, recover a stolen burial mask, clear a skooma den, lay a dro-m'Athra to rest,
deliver sugar duty, settle a well dispute. Notice boards (MS12) in every town. Existing `side_elsweyr.json` (12 quests)
is placed and gets `why` fields. Targets: quests 71 now -> 360 (14/km², floor 11.2/km²).

---

## 8. Components layout

| Component | File or system | Now | To do (phase) |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/elsweyr.json` + `tools/qa/worldsheet_lint.py` | missing | EW0 |
| Regions and seats | `tamriel/regions.json` | 5 regions, Pellitine has no capital | EW0: `capital: corinthe` |
| Terrain vectors | `tamriel_vectors.json` (ranges, areas, rivers, lakes), `tamriel_map.py`, `hydrology.json` | flat | EW1 |
| Biomes and micro-biomes | `tools/worldgen/biomes.py` -> `palettes.json` `micro` tables; `ecotones.py` | 5 palettes, grazelands bug | EW1 |
| Places | `tamriel/places/elsweyr.json` | 336 places | EW0 re-tier, moves, `why`, `hidden` |
| City plans | `tamriel/cityplans/{torval,rimmen}.json` exist; add senchal, dune, corinthe, riverhold, alabaster | 2 | EW3 |
| Styles and kits | `tamriel/styles/khajiit.json` + overlays `torval`, `torval_court`, `rimmen` | 1 kit | EW3: 4 sub-kits |
| Roads | `roads/named_roads.json` | none for Elsweyr | EW2 |
| Caravans, barges | `roads/caravans.json` (new), `tamriel/ports.json` | ships only | EW2 |
| Population and courts | `society/` JSON, population grammar | thin | EW3 |
| Dungeons | `dungeons` identity data, `marquee.json`, `MarqueePlans` | identity 9% | EW4 |
| Creatures and scenes | `region_spawns`, `creatures/scenes.json`, `WildScenes` | 13 kinds | EW5 |
| Quests | `quests/story/elsweyr_*.json`, `quests/town_elsweyr_*.json`, faction files | 70 | EW6 |
| Lore and voice | `lore/books/<region>.json`, `region_voice.json`, rumours | 6.2 flavour | EW6 |
| Calendar | `calendar/festivals.json` (Two-Moons Procession) | missing | EW6 |
| Optional layer | `events/elsweyr.json` (moon-sugar raids at caravan stops, MS4) | precursors | EW8, only after floor |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Dry north, wet south; Topal coast; Arena's relative layout | The central escarpment, mesas and gullies, braided rivers, cenotes | Today's 19-block median plain; straight tan-to-green palette seams (REGION-AUDIT T7) |
| Settlement list | The 8 Arena city-states and their order on the map | Each named town gets a plan form and landmark (5.2) | Arena's random Breton names as noise: kept as Septim foundations or renamed (3.4) |
| Settlement life | Port, caravan city, temple seat roles | Night markets, caravan departures, sugar boil-yards as workplaces | 3.3 named NPCs per town: target 9-73 by tier |
| Dungeons | Halls of Colossus, Temple of Agamanus (Arena's two named dungeons) | Moon-temples with moon-phase mechanics; the layered necropolis | Generic caves with no reason (identity 9%) |
| Wilderness | Big cats, scorpions, the jungle | Caravan encounters, dust storms, moon-sugar meadow | Random spawns between dots |
| Quests | Lore conflicts: Thalmor, the moons, skooma, Rimmen's border | The Borrowed Moons arc, the exclusive rebel-or-guard choice | Generator fetch quests as main content |
| Travel | Senchal's ships | Moonrise caravans, river barges | Fade-to-black travel |
| Lore text | Facts of the Riddle'Thar, the Void Nights, the union | Region voices in Ta'agra-flavoured English, songs of the caravans | Any source text |

**What Arena did vs what we do:** Arena gave Elsweyr eight interchangeable walled city blocks on flat ground, 24
generated towns and two named dungeons. We keep its map order and its two dungeons, and build a plateau, an
escarpment and a sugar coast with seven city plans, a living faith and a rebel war.

---

## 10. Floors and gates, implementation phases

### 10.1 Current vs target per region (parity 2026-10-13; targets from DESIGN-PRINCIPLES 4.3 and PARITY 5)

| Region | km² | Score (SET/NPC/QST/DUN/LOR) | Named towns | Dungeons now -> floor | Quests now -> target | Named NPCs now -> target | Lore places now -> floor | Readables now -> floor | `d_identity` | `y_looks` | `c_total` |
| --- | ---: | --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| Anequina | 5.58 | 45 (32/25/42/52/20) | 4 | 36 -> 50 | 20 -> 78 | 16 -> 188 | 14 -> 64 | 34 -> 249 | 0.11 -> 1.0 | 1 -> 3 | 13 -> 25 |
| Rimmen | 4.28 | 45 (46/27/42/54/19) | 4 | 41 -> 38 | 19 -> 60 | 17 -> 144 | 10 -> 49 | 24 -> 191 | 0.15 -> 1.0 | 2 -> 3 | 13 -> 25 |
| Torval | 4.11 | 50 (57/47/44/68/19) | 1 | 31 -> 37 | 14 -> 58 | 13 -> 138 | 9 -> 47 | 23 -> 184 | 0.19 -> 1.0 | 2 -> 3 | 13 -> 25 |
| Pellitine | 8.19 | 42 (35/12/25/56/13) | 8 | 67 -> 73 | 10 -> 115 | 6 -> 275 | 6 -> 93 | 17 -> 366 | 0.03 -> 1.0 | 1 -> 3 | 13 -> 25 |
| Senchal | 3.55 | 42 (25/22/38/52/18) | 1 | 33 -> 32 | 8 -> 50 | 7 -> 119 | 7 -> 40 | 17 -> 159 | 0.00 -> 1.0 | 1 -> 3 | 13 -> 25 |
| **Province** | 25.7 | 44 | 18 | 208 -> 229 | 71 -> 360 | 59 -> 864 | 46 -> 293 | 115 -> 1,149 | 0.09 -> 1.0 | | |

Province-level: signature gear families 1 -> 8 (stage 1), regional ingredients 12 -> 30, legendary uniques 3 -> 6,
exploration tracks 2 -> 4, a festival, >= 2 travel modes. Named NPCs and readables at this scale come mostly from the
population grammar and the region book sets; authored work goes to the seats and the first town on each highway.

### 10.2 Gates (every phase report)

`worldsheet_lint.py` 0 errors; `parity.py --assert-floor SET,NPC,QST,DUN,LOR` for the five regions; `layout_lint.py`;
`roadplan_lint.py` and the corridor gate; `reach_lint.py` (no isolated major site); `population_lint.py` (0
unstaffed workplaces); `placement_lint.py`; `dungeon_identity_lint.py`; `novelty_lint.py --strict` and the MS20
verbatim check; FLW: F-HOOK <= 800, F-WEENIE >= 70%, F-MICRO >= 6, F-ENC >= 8 (>= 3 Khajiit-only), F-WONDER >= 3,
F-TRAVEL p90 <= 500; region contact sheets to Haiku review ("does this belong to Elsweyr and nowhere else?").

### 10.3 Phases (each a self-contained agent task; order matters where noted)

| Phase | Scope | Gate |
| --- | --- | --- |
| **EW0** World sheet and place fixes | `elsweyr.json` sheet (1.2, 1.3), re-tiers, `rimmen_necropolis` move, Pellitine seat, shrine conversion, farm re-typing | worldsheet_lint 0; layout_lint |
| **EW1** Terrain and micro-biomes | Escarpment, mesas, gullies, rivers, Senchal bay; MS21 `micro` tables; remove grazelands | Relief targets (3.1); F-MICRO; REGION-AUDIT seams T2, T6, T7 >= 3 |
| **EW2** Roads and travel | 8 named roads, waysides, signposts; caravans; barges; moon shrines | roadplan_lint; F-SIGN >= 95%; F-TRAVEL |
| **EW3** Cities and towns | 5 new city plans, Torval rebuilt as a ring, 4 sub-kits, courts, population by tier | SET >= 80 on seats; s_bldgs >= 9.4; population_lint 0; `y_looks` >= 3 |
| **EW4** Dungeons | identity for all 208, +23 dungeons, 13 unmarked, two new marquee plans (Purifying Moons, the Tangle) | d_identity 1.0; F-LOOP; `d_marquee` >= 1 per region |
| **EW5** Wilderness | creature kinds to 25 per region (welwa, nix-ox, senche, jungle serpent, dro-m'Athra, scour-wyrm...), >= 8 scripted encounters incl. caravan toll, sandstorm, skooma deal, procession | CRE >= 80; F-ENC; F-QUIET |
| **EW6** Quests and lore | The Borrowed Moons arc, the exclusive faction pair, 5 region chains, Baandari hub, board offers, book sets, voices, festival | QST and LOR stage 1 then floor; F-STARTS >= 4; F-REACT >= 12 |
| **EW7** Review | Contact sheets, four questions per place, the "what Arena did" table, Haiku identity test | G1 blind test >= 80% |
| **EW8** Optional layer (only after the floor) | Moon-sugar raids at caravan stops (MS4), a scour-wyrm world boss | F-EVENT, F-GROUP if adopted |

EW1 and EW2 can run in parallel (different files). EW3 needs EW0. EW6 needs EW3 and EW4 for its places and people.
