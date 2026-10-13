# Skyrim: province plan (the template province)

Status: plan, no code changed. Governing rules: `docs/world/DESIGN-PRINCIPLES.md` (world building is king, the
eleven-layer causal sheet, G1-G12, the measured Skyrim floor in 4.1-4.9, MS21 and MS22). Inputs:
`docs/world/SCALE-AND-GEOGRAPHY.md` 5.1 and 6, `docs/SKYRIM-SWEEP.md`, `docs/PARITY.md`, `docs/ECOLOGY.md`,
`docs/DUNGEON-AUDIT.md`, `docs/LORE-WEB.md`, `docs/TAMRIEL.md` and the facts in `.reference/skyrim_ref` (markers and
hold attribution). Solstheim has its own file (`solstheim.md`). Legal line: names, positions, counts and lore facts
only; every sentence here is our own.

**Why this file is the template.** Skyrim is the bar every province is measured against, and our own Skyrim is below
that bar. `parity.py` (run 2026-10-13 on `cf288dd`) scores the province 95 overall but **53 on Settlements**, because
the bar is now the real game (`SKYRIM_MEASURED`: 46.9 named NPCs and 11.7 named buildings per named town, 14.0
quests per km2, 20.6 per town). Every other province copies this file's section structure, its tables and its way of
turning a parity hole into a numbered, owned, gated task. The other provinces should copy the method, not the
content: what they build must come from their own sheet.

**How to read the numbers.** "Now" is `parity.py` raw data (`tools/qa/out/parity.json`) or a direct count of
`places/*.json` and `quests/*.json`. "Floor" is the absolute minimum (Skyrim's thinnest hold per km2, the tier
minimum, or parity's 80% line, whichever is highest). "Target" is the measured median: `max(median density x km2,
tier sum, parity reference x named towns)`. Where the density median would push a big, thin hold (Falkreath, the
Reach) far past the real game, the parity reference is the target and the density figure is a stretch, marked as such.

---

## 1. World model sheet

Data home: `skycraft/tamriel/world/skyrim.json` (MS22): one province block, nine hold blocks, each field
`{id, text, cites}`. Ids below are the ids the JSON will use. A cite in the same block is written `L1.x`; a cite to the
province is written `sk.L9.x`. Every L2-L11 field cites at least one field above it.

### 1.1 Province sheet (`sk`)

**World truth paragraph.** Skyrim is a cold, high country of nine holds walled in by mountain ranges and the Sea of
Ghosts. Its oldest layer is buried: the Snow Elves and the Dwemer cut cities into the rock, and the Dwemer drove the
Snow Elves underground, where they became the blind Falmer. Atmoran settlers landed at Saarthal and became the Nords,
whose dragon priests ruled from hilltop barrows until the dragons fell. Today the Nords mine silver, iron and ebony,
farm the tundra and trade mead and lumber along roads that meet at Whiterun. The Empire's treaty with the Thalmor
banned the worship of Talos, and that ban split the province into a civil war between the Legion and the Stormcloaks
just as the dragons return.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology and landform | `spine_ranges`: the Throat of the World massif in the centre, the Jerall wall to the south, the Velothi wall to the east, the Druadach to the west. `northern_coast`: a cold coast of cliffs, inlets and ice floes on the Sea of Ghosts. `geothermal_east`: hot springs, fumaroles and sulphur pools on Eastmarch's high basin. `ore_belts`: silver in the Reach, iron in the Pale, the Rift and Hjaalmarch, quicksilver at Dawnstar, corundum, malachite, moonstone and gold in small veins, ebony under Eastmarch, orichalcum in Orc country. `dwemer_bedrock`: Dwemer cities cut into the northern and western rock, linked far below by a great lit cavern (Blackreach). `barrow_ridges`: hills and ridges crowned by Nordic barrows |
| L2 Climate | `rain_shadow_tundra` (L1.spine_ranges): the central plains lie in the lee of the ranges, dry and windswept. `polar_coast` (L1.northern_coast): blizzards, sea fog and ice off the Sea of Ghosts; the north is snowbound most of the year. `sheltered_south` (L1.spine_ranges): the Rift and Falkreath, walled from the polar wind, stay mild and wet. `snowline_north` (L1): the snowline drops from the high peaks in the south to sea level on the north coast. `aurora` (L2.polar_coast): clear northern nights bring auroras |
| L3 Water | `white_river` (L1.spine_ranges): rises on the central heights, runs past Whiterun and reaches the sea at Windhelm. `karth_river` (L1): drains the Reach north to the sea under Solitude. `treva_darkwater` (L1): the Rift's lakes drain north through the Treva and Darkwater rivers into the White River. `hjaal_marsh` (L3.karth_river): the Hjaal and lower Karth spread into salt marsh at the coast. `great_lakes` (L1): Ilinalta in Falkreath, Honrich and Geir in the Rift. `seats_on_water` (L3): every capital holds a river mouth, a lake or a channel; whoever holds the seat holds the crossing |
| L4 Ecology | `tundra_herds` (L2.rain_shadow_tundra): elk, deer, mammoths and the giants who herd them. `pine_and_birch` (L2.sheltered_south): pine forest in the west and south, birch forest in the Rift. `polar_fauna` (L2.polar_coast): horkers, ice wraiths, frost trolls, sabre cats. `marsh_life` (L3.hjaal_marsh): mudcrabs, spiders, wisps. `below_fauna` (L1.dwemer_bedrock): Falmer and chaurus under the rock, automatons in the Dwemer halls. `dragons` (L11.dragon_war): dragons return to their old mounds and high peaks |
| L5 Resources and economy | `mining_wealth` (L1.ore_belts): silver, iron, quicksilver and ebony; mines make families and towns rich. `farm_and_mead` (L3.white_river, L4.tundra_herds): grain and cattle on the river flats; two rival meaderies. `timber` (L4.pine_and_birch): mills in every forest hold. `sea_trade` (L1.northern_coast): Solitude's East Empire Company docks and Windhelm's harbour. `road_hub` (L1.spine_ranges): the passes funnel the roads to the central plain |
| L6 Settlement pattern | `nine_seats` (L3.seats_on_water, L5): one seat per hold; five walled capitals and four minor seats. `busy_centres` (L5.road_hub, L5.sea_trade, L5.mining_wealth): Whiterun (crossroads), Solitude and Windhelm (ports and rival war capitals), Riften (lake, mead and smuggling), Markarth (silver). `failing_seats` (L11): Winterhold (collapse), Morthal (marsh and fear), Dawnstar (bad dreams), Helgen (destroyed). `work_villages` (L5): every village stands on a mine, a mill, a farm, a ford or an inn on a highway |
| L7 Society | `jarls_and_thanes` (L6.nine_seats): each hold has a jarl with a steward, a court mage and a housecarl; thanes and old clans hold land. `clan_politics` (L9.civil_war): old Nord clans pick sides in the war. `outsiders` (L11.red_year): Dunmer refugees and Argonian dock workers live apart in Windhelm; Khajiit caravans camp outside the walls. `orc_strongholds` (L5.mining_wealth): Orc clans work their own mines outside hold law under Malacath's code. `reachfolk_outcasts` (L11.markarth_incident): the Reach's native people live outside the law |
| L8 Culture and belief | `eight_and_one` (L9.talos_ban): the Divines with Talos worshipped openly in the east and in secret in the west. `hold_temples` (L6.nine_seats): Kynareth in Whiterun, Mara in Riften, Dibella in Markarth, the Divines in Solitude, Talos in Windhelm, a Hall of the Dead for Arkay in every capital. `way_of_the_voice` (L1.spine_ranges): the Greybeards' monastery on the Throat; pilgrims climb from Ivarstead. `ancestor_barrows` (L1.barrow_ridges): the Nords bury their dead in barrows, and the dead do not rest. `daedric_wilds` (L10): Daedric shrines stand in remote places, each with its own following. `distrust_of_magic` (L11.great_collapse): many Nords blame mages for the Collapse |
| L9 Politics and conflict | `talos_ban` (L11.great_war): the treaty with the Thalmor forbids Talos worship. `civil_war` (L9.talos_ban, L7.clan_politics): the Legion holds Solitude and the west; the Stormcloaks hold Windhelm and the east; each hold's jarl leans one way. `thalmor_watch` (L9.talos_ban): Thalmor justiciars hunt Talos worshippers from their embassy. `dragon_crisis` (L4.dragons): the World-Eater has returned. `vampire_war` (L10.volkihar): an old vampire court and a revived order of hunters fight in the dark |
| L10 Hidden and underground | `falmer_below` (L1.dwemer_bedrock, L11.dwemer_betrayal): the Falmer hold the deep caves and raid upward. `draugr_barrows` (L8.ancestor_barrows, L11.dragon_war): the dragon cult's dead guard its tombs and word walls. `guilds_in_shadow` (L7): a thieves' guild under Riften, an assassins' brotherhood behind black doors in Falkreath and Dawnstar. `volkihar` (L9.vampire_war): a vampire court in an island castle off Haafingar. `forsworn` (L9, L7.reachfolk_outcasts): Reach rebels in crags and redoubts. `bandits_on_roads` (L5.road_hub): bandits take towers and caves along the trade roads. `hagravens_and_witches` (L8.daedric_wilds): covens in the wild hills |
| L11 History layers | `snow_elf_era`: the Snow Elves' realm and its shrines. `dwemer_betrayal`: the Dwemer enslaved the Snow Elves below the rock. `atmoran_landing`: Ysgramor's people landed at Saarthal and were driven out, then returned. `dragon_war`: dragons and their priests ruled until the Nords rose and the priests were sealed in barrows. `septim_rise`: the general who became Talos rose from Skyrim's wars. `red_year`: Red Mountain's eruption (4E 5) sent refugees and ash west. `great_collapse`: most of Winterhold fell into the sea (4E 122). `great_war`: the Empire's war with the Thalmor ended in the treaty (4E 175). `markarth_incident`: Reachfolk seized Markarth and were driven out (4E 176). `high_king_slain`: the High King died in a duel with the Stormcloak leader (4E 201) |

### 1.2 Whiterun (`whiterun`, 2.96 km2, levels 1-10)

**World truth.** Whiterun is the open heart of Skyrim: a dry tundra plain in the lee of the ranges, cut by the White
River and overlooked by the Throat of the World. The city sits on a rock where every road of the province meets, so
it lives by trade, by its farms on the river flats and by the warriors' hall that has trained Nords since Ysgramor's
time. Its jarl tries to keep the city out of the civil war, but two old clans have already chosen sides. Giants herd
mammoths on the plain, bandits toll the river bridges, and the first dragon of the new age is seen here. The faith is
Kynareth's: a sacred tree stands at the city's heart.

| Layer | Fields |
| --- | --- |
| L1 | `tundra_plain`: rolling grass tundra with rock outcrops. `city_rock`: an outcrop above the plain carrying the city. `throat_skirt`: the lower slopes of the Throat of the World on the south-east edge. `white_river_falls`: the river drops in falls and a short gorge at Valtheim. `ridge_barrows`: barrows on the low ridges round the plain |
| L2 | `dry_wind` (sk.L2.rain_shadow_tundra, L1.tundra_plain): the driest hold, wind most days, clear skies. `throat_storm` (L1.throat_skirt): snowstorms and cloud caps on the Throat while the plain is sunny. `river_mist` (L1.white_river_falls): dawn mist along the White River |
| L3 | `white_river_flats` (sk.L3.white_river): the river's flats are the only rich soil. `valtheim_crossing` (L1.white_river_falls): the one bridge over the gorge is controlled by whoever holds the towers above it. `city_wells` (L1.city_rock): the city draws from wells and the river below its rock |
| L4 | `steppe_grass` (L2.dry_wind): lavender, thistle and tundra cotton. `giant_grounds` (L1.tundra_plain): giants and mammoths on the open plain. `sabre_and_wolf` (L4.giant_grounds): sabre cats and wolves follow the herds. `throat_meadow` (L1.throat_skirt): alpine meadow and goats. `dragon_perch` (sk.L4.dragons, L1.throat_skirt): dragons roost on the Throat and the mounds |
| L5 | `river_farms` (L3.white_river_flats): wheat, cabbage and cattle farms on the flats. `meadery` (L5.river_farms): a meadery competing with the Rift's. `skyforge_steel` (L11.skyforge): the best steel in Skyrim from an ancient forge. `crossroads_trade` (sk.L5.road_hub): every highway passes the city; merchants, stables, caravans. `riverwood_timber` (L3.white_river_flats): a lumber mill on the river below the Throat |
| L6 | `whiterun_seat` (L1.city_rock, L5.crossroads_trade): the capital at the crossroads. `riverwood` (L5.riverwood_timber): a mill village where the White River road enters the hold. `rorikstead` (L5.river_farms): a farm village on the western road, famous for harvests on poor soil. `caravan_ground` (L7.caravans_outside): Khajiit camp and stables outside the walls |
| L7 | `three_districts` (L6.whiterun_seat): Plains (market and commoners), Wind (warriors' hall, temple and family houses), Cloud (the palace and court). `clan_feud` (sk.L7.clan_politics): two old clans, one for the Empire and one for the rebels, feud in the streets. `warrior_hall` (L11.ysgramor_hall): the warriors' hall offers work and honour. `caravans_outside` (sk.L7.outsiders): Khajiit traders are kept outside the gates |
| L8 | `kynareth_tree` (sk.L8.hold_temples): the sacred tree in the Wind district grew from a cutting of the Eldergleam in Eastmarch. `open_preacher` (sk.L8.eight_and_one): a Talos preacher speaks at the tree, tolerated by the guards. `voice_pilgrims` (sk.L8.way_of_the_voice): pilgrims pass on their way to High Hrothgar. `honour_and_sovngarde` (L7.warrior_hall) |
| L9 | `neutral_jarl` (sk.L9.civil_war): the jarl refuses both sides for as long as he can. `siege_point` (L6.whiterun_seat): both armies need the crossroads; a battle for the city is coming. `first_dragon` (sk.L9.dragon_crisis): the first dragon attack falls on a watchtower west of the city |
| L10 | `beast_blood` (L7.warrior_hall): the warriors' inner circle hides a beast-blood curse; its hunters lurk in forts and barrows. `toll_bandits` (L3.valtheim_crossing, L5.crossroads_trade): bandits hold towers and caves along the trade roads. `giant_camps` (L4.giant_grounds). `dragon_mounds` (sk.L10.draugr_barrows, L4.dragon_perch). `fire_mage_keep` (sk.L8.distrust_of_magic): renegade mages in an old keep |
| L11 | `dragon_porch`: a jarl of old built the palace porch to hold a captured dragon. `ysgramor_hall`: the warriors' hall traces itself to Ysgramor's companions. `skyforge`: the forge is older than the city. `valtheim_towers`: twin towers over the river gorge. `watchtower_attack`: the dragon's return, remembered by every guard |

### 1.3 Haafingar (`haafingar`, 1.68 km2, levels 5-24)

**World truth.** Haafingar is the Empire's foothold in Skyrim: a short, steep coast of pine ridges and sea cliffs
where the Karth reaches the sea under Solitude's great stone arch. The capital lives on the harbour, the East Empire
Company and the Legion's headquarters, and its nobles are the most Imperial, the richest and the most watched
people in the province. The High King was killed in a duel by the rebel leader, and his widow holds the throne. A
Thalmor embassy sits in the mountains, a vampire court in a castle off the coast, and the dead Wolf Queen in the
catacombs under the city.

| Layer | Fields |
| --- | --- |
| L1 | `solitude_arch`: a natural stone arch over the Karth estuary carries the city. `sea_cliffs`: cliffs and coves along the coast. `pine_ridges`: steep pine ridges inland. `sea_caves` (L1.sea_cliffs): caves at sea level. `island_rock`: a rock island off the north-west coast |
| L2 | `sea_fog` (sk.L2.polar_coast, L1.sea_cliffs): fog rolls in off the sea and fills the estuary at dawn. `cold_wet` (sk.L2.polar_coast): rain and sleet, rarely deep snow on the coast. `winter_gales` (sk.L2.polar_coast) |
| L3 | `karth_mouth` (sk.L3.karth_river): the river meets the sea under the arch; the harbour is the hold's water and wealth. `estuary_marsh` (sk.L3.hjaal_marsh): the east bank grades into Hjaalmarch's marsh. `lighthouse_point` (L1.sea_cliffs) |
| L4 | `pine_coast` (L1.pine_ridges, L2.cold_wet): spruce and pine with ferns. `rocky_shore` (L1.sea_cliffs): horkers, mudcrabs and slaughterfish. `hill_covens` (L1.pine_ridges): hagravens in the hills. `spriggan_glens` (L2.cold_wet) |
| L5 | `company_docks` (L3.karth_mouth): the East Empire Company's warehouse and ships. `imperial_market` (L6.solitude_seat): tailors, perfumers, fletchers and jewellers for a rich court. `sawmill` (L4.pine_coast). `bards_college` (L11.olaf_burning): a college for poets and musicians. `toll_bridge` (L6.dragon_bridge): the old bridge over the Karth |
| L6 | `solitude_seat` (L1.solitude_arch, L3.karth_mouth, L5.company_docks): capital and port. `dragon_bridge` (sk.L3.karth_river, L11): a village at the ancient dragon-headed bridge on the Karth road, with an Imperial intelligence post. `embassy` (L9.thalmor_embassy): the Thalmor's walled compound in the mountains. `sawmill_hamlet` (L5.sawmill) |
| L7 | `imperial_nobles` (L5.imperial_market, L9.imperial_seat): rich, Imperial-minded families in manors. `public_justice` (L9.imperial_seat): executions in the square. `dockworkers` (L5.company_docks): labourers and sailors at the harbour gate. `few_beggars` (L5): the poor are few and pushed out to the docks |
| L8 | `divines_temple` (sk.L8.hold_temples): a temple of the Divines with no altar for Talos. `wolf_queen_fear` (L11.wolf_queen): parents frighten children with the Wolf Queen. `meridia_light` (sk.L8.daedric_wilds): Meridia's statue on a peak above the coast. `burning_of_king_olaf` (L11.olaf_burning): a winter festival where an old king is burned in effigy |
| L9 | `imperial_seat` (sk.L9.civil_war): the Legion's headquarters; the High King's widow holds the throne. `succession` (L11.high_king_slain): a moot must choose a new High King. `thalmor_embassy` (sk.L9.thalmor_watch): justiciars watch every Talos worshipper. `naval_power` (L5.company_docks) |
| L10 | `wolf_queen_catacombs` (L11.wolf_queen): the dead queen's catacombs under the city. `volkihar_castle` (sk.L10.volkihar, L1.island_rock). `smugglers_coves` (L1.sea_caves, L5.company_docks). `soul_cairn_gate` (L10.volkihar): a hidden portal tied to the vampire court. `embassy_dossiers` (L9.thalmor_embassy) |
| L11 | `wolf_queen`: Potema, a queen of Solitude, waged a long civil war and died besieged in her city. `olaf_burning`: the bards keep an old feud with a king in song. `high_king_slain` (sk.L11.high_king_slain). `dragon_bridge_age`: the bridge predates the Empire |

### 1.4 Falkreath (`falkreath`, 4.64 km2, levels 5-22)

**World truth.** Falkreath is the southern forest hold: pine slopes rising to the Jerall wall, Lake Ilinalta in its
valley and the Pale Pass road to Cyrodiil. Its capital is a quiet timber and hunting town known for its huge
graveyard, where more dead lie than living people walk. The border fort town of Helgen burned when a dragon broke an
Imperial execution. Hunters live well here and so do the things they hunt: werewolves, spriggans and the witches who
serve the Huntsman. The undead stir near the graves, and an assassins' brotherhood keeps a black door in the pines.

| Layer | Fields |
| --- | --- |
| L1 | `jerall_foothills`: pine slopes climbing to the Jerall wall. `ilinalta_valley`: a long lake basin. `pale_pass` (L1.jerall_foothills): the road pass to Cyrodiil. `forest_caves`: limestone caves and grottoes in the slopes |
| L2 | `mild_wet` (sk.L2.sheltered_south): mild, rainy and green. `valley_fog` (L1.ilinalta_valley). `pass_snow` (L1.pale_pass): snow on the pass all year |
| L3 | `lake_ilinalta` (sk.L3.great_lakes): the lake feeds streams north towards the White River. `forest_streams` (L2.mild_wet): many small streams, waterfalls and grottoes. `shared_water` (L3.lake_ilinalta): no one controls the lake; mills and farms share it |
| L4 | `old_pine` (L2.mild_wet): tall pine forest with ferns, mushrooms and moss. `lake_shore` (L3.lake_ilinalta): reeds, fish, mudcrabs. `spriggan_groves` (L4.old_pine): spriggans in sacred groves. `bear_country` (L4.old_pine): bears, wolves and deer. `grave_ground` (L6.falkreath_graves): the undead gather near graves |
| L5 | `timber` (sk.L5.timber, L4.old_pine): a lake mill and the town's sawyers. `hunting` (L4.bear_country): hunters, an archery camp, pelts and meat. `burial` (L6.falkreath_graves): the hold buries the dead of half the province. `border_trade` (L1.pale_pass): the road to Cyrodiil |
| L6 | `falkreath_graves` (L1.jerall_foothills, L5.timber): a forest town round a vast graveyard on the road south. `helgen_fort` (L1.pale_pass): a walled fort town on the pass road, now burned. `half_moon_mill` (L3.lake_ilinalta). `lakeview_steading` (L3.lake_ilinalta): a homestead on the lake. `hunters_camps` (L5.hunting) |
| L7 | `town_of_mourners` (L5.burial): priests, gravediggers and mourners outnumber traders. `young_jarl` (L9.jarl_uncle): a young jarl, advised by his uncle, the former jarl. `hunters_apart` (L5.hunting): hunters living outside the town |
| L8 | `arkay_priests` (sk.L8.hold_temples, L5.burial). `huntsman_cult` (sk.L8.daedric_wilds, L4.bear_country): witches who serve the Huntsman of the hunting grounds. `bargain_shrine` (sk.L8.daedric_wilds): a shrine to a Daedra of bargains in a vampire-held cave. `night_mother` (L10.black_door) |
| L9 | `imperial_border` (sk.L9.civil_war, L1.pale_pass): the Legion's supply road from Cyrodiil. `helgen_burned` (sk.L9.dragon_crisis, L6.helgen_fort): the dragon's attack ended an execution and the town. `jarl_uncle` (L7.young_jarl): the uncle quietly favours the rebels |
| L10 | `black_door` (sk.L10.guilds_in_shadow, L4.old_pine): the assassins' sanctuary behind a black door in the pines. `necromancers_and_vampires` (L4.grave_ground). `coven` (L8.huntsman_cult). `pass_bandits` (L5.border_trade): bandits on the pass road. `barrow_above_riverwood` (sk.L10.draugr_barrows): the barrow above the valley that holds a stone map of dragon graves |
| L11 | `old_graves`: battles of every age are buried here. `helgen_keep`: the fort's keep, built in the Empire's early days. `sunderstone`: a gorge shrine to a Nord hero. `bleak_falls`: a dragon-cult barrow over the falls |

### 1.5 Hjaalmarch (`hjaalmarch`, 1.41 km2, levels 5-24)

**World truth.** Hjaalmarch is the low marsh hold where the Hjaal and the Karth spread into salt flats on the Sea of
Ghosts. Morthal stands on boardwalks at the edge of the Drajkmyr marsh, small, damp and frightened: a house burned
with a family inside, a vampire nest lies in the fens, and the townsfolk suspect their own wizard. Its jarl sees
visions and keeps out of the war. The hold has little wealth (timber, iron at Stonehills, marsh herbs) but its hills
hold two of the oldest tombs in Skyrim: the maze city of the dragon priests and the grave of the first Voice master.

| Layer | Fields |
| --- | --- |
| L1 | `drajkmyr_flats`: a wide, flat marsh and salt flat. `low_coast`: a shallow coast of mud and shingle. `stonehills_rise`: a granite rise with iron. `western_hills`: low hills to the south and west |
| L2 | `marsh_fog` (sk.L2.polar_coast, L1.drajkmyr_flats): dense fog most mornings and evenings. `cold_damp` (sk.L2.polar_coast): cold, wet and grey; snow on the hills |
| L3 | `hjaal_delta` (sk.L3.hjaal_marsh): channels, pools and tidal creeks. `boardwalks` (L3.hjaal_delta): the town is built on piles and walkways over the water. `no_clean_water` (L3.hjaal_delta): drinking water comes from rain barrels and the one spring by the hall |
| L4 | `fen` (L3.hjaal_delta): cotton grass, swamp fungus, deathbell and reeds. `salt_marsh` (L1.low_coast): mudcrabs and horkers. `marsh_hunters` (L2.marsh_fog): spiders and wisps in the fog. `hill_pine` (L1.western_hills) |
| L5 | `timber_mill` (L4.hill_pine): the town's mill and log yard. `marsh_herbs` (L4.fen): an alchemist's trade in marsh ingredients. `stonehills_iron` (L1.stonehills_rise): an iron mine village. `fishing` (L1.low_coast) |
| L6 | `morthal_seat` (L1.drajkmyr_flats, L5.timber_mill): a small seat on the marsh edge where the road crosses the Hjaal. `stonehills` (L5.stonehills_iron). `windstad` (L1.low_coast): a homestead on the coast. `hjaal_road` (sk.L5.road_hub): the road from Dragon Bridge to Whiterun |
| L7 | `poor_seat` (L5): few traders, many labourers. `wizard_suspected` (L8.fear_of_the_dark): townsfolk blame a conjurer who lives at the edge of town. `jarl_seer` (L9.jarl_apart): a jarl known for visions |
| L8 | `fear_of_the_dark` (L2.marsh_fog, L10.marsh_vampires): doors barred at night, charms on the posts. `arkay_dead` (sk.L8.hold_temples). `ruined_shrines` (L11.bromjunaar): shrines in the dragon cult's old city |
| L9 | `jarl_apart` (sk.L9.civil_war): the jarl will not commit to either side; both camps want her removed. `vampire_fear` (L10.marsh_vampires) |
| L10 | `marsh_vampires` (L2.marsh_fog, L4.fen): a vampire nest in a fen cave. `bromjunaar_maze` (L11.bromjunaar): the dragon priests' city turned into a mage's maze. `voice_tomb` (L11.windcaller): a tomb guarded by trials. `wreck_on_the_flats` (L1.low_coast) |
| L11 | `bromjunaar`: the dragon cult's seat in the hills, later a famous archmage's labyrinth. `windcaller`: the master who founded the Way of the Voice after a defeat in Morrowind lies in a tomb here. `burnt_house`: the recent fire that started the town's fear |

### 1.6 The Pale (`pale`, 1.75 km2, levels 6-26)

**World truth.** The Pale is a high, snowbound hold between the central plain and the Sea of Ghosts. Dawnstar is a
mining port where two mines, one for iron and one for quicksilver, feed ships to Solitude, and where everyone has
been sleeping badly since a temple of nightmares on the cliffs stirred again. Its jarl backs the rebels. The Pale's
rock is riddled with Dwemer cities whose great lifts drop to the lit cavern far below, and its barrows hold the old
crown of Skyrim's kings. Trolls, ice wraiths and giants rule the snowfields between the few roads.

| Layer | Fields |
| --- | --- |
| L1 | `snow_highland`: a high snowy plateau sloping north to the sea. `dawnstar_cove` (sk.L1.northern_coast): a sheltered cove with a beach harbour. `iron_quicksilver` (sk.L1.ore_belts): two ore bodies behind the cove. `dwemer_cluster` (sk.L1.dwemer_bedrock): several Dwemer cities and great lifts in the hills. `crown_barrow` (sk.L1.barrow_ridges) |
| L2 | `blizzard_plateau` (sk.L2.polar_coast, L1.snow_highland): whiteouts and deep snow. `long_night` (sk.L2.polar_coast): short winter days. `aurora_coast` (sk.L2.aurora) |
| L3 | `frozen_streams` (L2.blizzard_plateau): small rivers frozen most of the year. `harbour_water` (L1.dawnstar_cove): the cove is the town's water and road to the world. `mine_water` (L1.iron_quicksilver): tainted runoff below the mines |
| L4 | `snowfield` (L2.blizzard_plateau): snow over heath; frost trolls, ice wraiths. `pale_heath` (L1.snow_highland): tundra heath on the southern edge, giants and mammoths. `frozen_shore` (L1.dawnstar_cove): horkers. `dwemer_machines` (L1.dwemer_cluster): automatons round the ruins |
| L5 | `two_mines` (L1.iron_quicksilver): iron and quicksilver mines owned by rival owners. `ore_ships` (L3.harbour_water): ore goes by sea to Solitude. `fishing` (L3.harbour_water). `roadside_inn` (sk.L5.road_hub): an inn on the long road south |
| L6 | `dawnstar_seat` (L1.dawnstar_cove, L5.two_mines): a mining port. `nightgate` (L5.roadside_inn): an inn where the Whiterun road climbs into the snow. `heath_farms` (L4.pale_heath): two farms on the southern heath |
| L7 | `miners_and_owners` (L5.two_mines): miners live in bunkhouses; the owners compete. `sleepless_town` (L10.nightmare_temple): everyone is tired, short-tempered, superstitious |
| L8 | `dream_dread` (L10.nightmare_temple): people fear sleep. `mara_priest` (sk.L8.hold_temples): a priest of Mara who once served the nightmare goddess. `old_crown` (L11.jagged_crown) |
| L9 | `rebel_jarl` (sk.L9.civil_war): the jarl backs the Stormcloaks. `crown_race` (L11.jagged_crown, sk.L9.civil_war): both sides want the old crown. `nightmares` (L10.nightmare_temple) |
| L10 | `nightmare_temple` (sk.L8.daedric_wilds, L11.temple_siege): a sealed temple of the Daedra of dreams on the cliffs. `great_lifts` (L1.dwemer_cluster): lifts down to the great cavern. `sanctuary_north` (sk.L10.guilds_in_shadow): a second assassins' sanctuary near the town. `troll_caves` (L4.snowfield) |
| L11 | `temple_siege`: the temple was sealed during an Orc attack long ago, with its monks still dreaming inside. `jagged_crown`: the crown of the old High Kings lies in a barrow here. `dwemer_lifts` |

### 1.7 The Rift (`rift`, 4.17 km2, levels 6-26)

**World truth.** The Rift is a sheltered valley of golden birch forest between the Velothi and Jerall ranges, with
Lakes Honrich and Geir at its floor. Riften, built on the lake with canals for streets, is rich in mead, fish and
smuggled goods and poor in honesty: one meadery family owns the guards, the orphans' home is cruel and a thieves'
guild drinks under the city. Its jarl leans to the rebels. Pilgrims start the long climb to the Greybeards at
Ivarstead; Orcs cursed by their god starve at Largashbur; vampire hunters reopen an old fort near the border.

| Layer | Fields |
| --- | --- |
| L1 | `birch_basin` (sk.L1.spine_ranges): a basin walled by the Velothi and Jerall ranges. `lake_floor`: Lakes Honrich and Geir on the basin floor. `rift_tundra`: a high grass plateau in the north. `treva_gorge` (L1.lake_floor): the river gorge north from Lake Geir. `border_heights` (sk.L1.spine_ranges): the eastern heights at the Morrowind border |
| L2 | `golden_autumn` (sk.L2.sheltered_south, L1.birch_basin): mild, still and wet; the birches stay gold. `lake_mist` (L1.lake_floor). `mountain_snow` (L1.border_heights) |
| L3 | `honrich_city` (sk.L3.great_lakes): Riften stands on Lake Honrich with canals; the fishery runs on the lake. `treva_river` (sk.L3.treva_darkwater): Lake Geir drains north past Ivarstead. `estate_island` (L1.lake_floor): an island estate in the lake |
| L4 | `birch_forest` (L2.golden_autumn): birch, red maple, ferns and mushrooms; deer, elk, bears. `lake_shore` (L3): reeds, fish. `rift_grassland` (L1.rift_tundra): sabre cats. `spriggan_glades` (L4.birch_forest). `falmer_caves` (sk.L10.falmer_below) |
| L5 | `meadery` (L4.birch_forest, L5.honey_estate): the most powerful meadery in Skyrim. `honey_estate` (L3.estate_island): bees on an island farm. `fishery` (L3.honrich_city). `redbelly_iron` (sk.L1.ore_belts): a mine at Shor's Stone. `heartwood_timber` (L4.birch_forest). `smuggling` (L10.thieves_below, L1.border_heights) |
| L6 | `riften_seat` (L3.honrich_city, L5.meadery, L5.fishery). `ivarstead` (L3.treva_river, L8.voice_pilgrims): a village at the foot of the long stair. `shors_stone` (L5.redbelly_iron). `heartwood_mill` (L5.heartwood_timber). `largashbur` (sk.L7.orc_strongholds). `lake_farms` (L3) |
| L7 | `meadery_rule` (L5.meadery): one family owns the guards and the council. `canal_poor` (L3.honrich_city): beggars, dockhands and the people of the tunnels below. `cruel_orphanage`: the town's orphans live under a cruel keeper. `guild_decline` (L10.thieves_below): the thieves' guild has lost its luck |
| L8 | `mara_temple` (sk.L8.hold_temples). `voice_pilgrims` (sk.L8.way_of_the_voice). `shadow_patron` (L10.thieves_below): thieves pray to a Daedra of the night. `cursed_clan` (sk.L7.orc_strongholds): Largashbur's Orcs suffer their god's anger |
| L9 | `rebel_leaning_jarl` (sk.L9.civil_war). `meadery_power` (L7.meadery_rule): the meadery backs whoever keeps it rich. `hunters_fort` (sk.L9.vampire_war, L1.border_heights): vampire hunters reopen a fort near the border |
| L10 | `thieves_below` (sk.L10.guilds_in_shadow, L3.honrich_city): the guild's tavern in the tunnels under the city. `rahgot_barrow` (L11.forelhost_siege): the last dragon-cult monastery. `dwemer_valley_ruin` (sk.L1.dwemer_bedrock): a Dwemer ruin in the valley. `falmer_pass` (L4.falmer_caves). `dragon_heights` (sk.L4.dragons): dragon lairs on the ridges |
| L11 | `forelhost_siege`: the cult's last monks poisoned their own water rather than surrender to a Nord siege. `geirmund`: a hero's hall barrow on a lake island. `treva_watch`: an old fort on the river. `orphan_cruelty`: the town remembers its orphans badly |

### 1.8 Winterhold (`winterhold`, 2.33 km2, levels 10-30)

**World truth.** Winterhold was once a rival to Solitude; now it is a handful of houses on a cliff above the sea that
swallowed most of the city in the Great Collapse. The survivors blame the mages, whose College stands on a sea stack
across a narrow bridge. The jarl backs the rebels and has little to rule. The hold is the coldest place in Skyrim:
glacier, ice floes, wrecks on the shore and auroras most clear nights. Under the ice lie the Atmorans' first city,
the Falmer's pits and a Dwemer ruin in the glacier; on the floes a scholar listens for a Daedra of knowledge.

| Layer | Fields |
| --- | --- |
| L1 | `collapsed_cliff` (sk.L1.northern_coast): the cliff where the city stood, broken off into the sea. `college_stack`: a sea stack joined to the cliff by a stone bridge. `glacier`: a glacier and ice sheet to the east. `ice_floes` (sk.L1.northern_coast). `saarthal_ridge`: a ridge with an Atmoran ruin |
| L2 | `polar_storm` (sk.L2.polar_coast): the harshest weather in Skyrim. `ice_fog` (L1.ice_floes). `aurora_nights` (sk.L2.aurora) |
| L3 | `frozen_sea` (L1.ice_floes): the sea is ice for half the year. `meltwater` (L1.glacier): short summer streams. `no_river` (L1.collapsed_cliff): the town has no river; it melts snow |
| L4 | `glacier_waste` (L1.glacier): ice wraiths, frost trolls, frost giants. `floe_shore` (L1.ice_floes): horkers, mudcrabs. `snow_pine_pockets` (L2.polar_storm): small sheltered pine stands. `blind_hunters` (sk.L10.falmer_below) |
| L5 | `college_coin` (L6.college): the College's needs keep an inn and a shop alive. `wreck_salvage` (L1.ice_floes, L2.polar_storm): four wrecks on the shore. `whistling_mine` (sk.L1.ore_belts): a working mine in the hills. `horker_hunting` (L4.floe_shore) |
| L6 | `winterhold_remnant` (L1.collapsed_cliff, L11.great_collapse): a shrunken seat. `college` (L1.college_stack). `no_villages` (L5): only the mine and the shore camps lie outside the town |
| L7 | `town_vs_college` (sk.L8.distrust_of_magic, L11.great_collapse): townsfolk resent and fear the mages. `poverty` (L5): the poorest seat in Skyrim. `scholars_apart` (L6.college): mages from every province keep to themselves |
| L8 | `azura_statue` (sk.L8.daedric_wilds): a giant statue of the Daedra of dusk and dawn in the mountains. `knowledge_seeker` (L10.listener_on_ice). `mage_blame` (L7.town_vs_college) |
| L9 | `rebel_jarl` (sk.L9.civil_war). `thalmor_at_college` (sk.L9.thalmor_watch, L6.college): a Thalmor adviser at the College. `eye_found` (L10.saarthal_eye): a power found under Saarthal |
| L10 | `saarthal_eye` (L11.saarthal): something of great power lies under the Atmoran city. `college_midden` (L6.college): cellars under the College. `harbingers_tomb` (L11.saarthal): the tomb of Ysgramor. `listener_on_ice` (sk.L8.daedric_wilds, L1.ice_floes): a scholar's outpost on the ice. `falmer_pit` (L4.blind_hunters). `glacier_dwemer` (L1.glacier) |
| L11 | `great_collapse` (sk.L11.great_collapse): 4E 122. `saarthal` (sk.L11.atmoran_landing): the first Atmoran city, burned by the elves. `old_rival_of_solitude`: Winterhold once rivalled Solitude for the High Kingship |

### 1.9 Eastmarch (`eastmarch`, 2.91 km2, levels 8-28)

**World truth.** Eastmarch is the old Nord heartland of the east: Windhelm, the oldest city in Skyrim, stands where
the White River meets the sea, and inland a high basin steams with hot springs and fumaroles where giants graze
mammoths. Windhelm is the rebel capital; its leader killed the High King. Talos is worshipped openly, the Dunmer
refugees live in a grey slum and the Argonians are kept outside the walls at the docks. The hold mines corundum,
malachite and, at an Orc stronghold, ebony. Dwemer ruins break the basin's crust; a murderer stalks the old city.

| Layer | Fields |
| --- | --- |
| L1 | `steam_basin` (sk.L1.geothermal_east): a high basin of hot springs, fumaroles and sulphur pools. `white_river_mouth` (sk.L3.white_river): the river's mouth with the city on the north bank. `velothi_foothills` (sk.L1.spine_ranges). `small_ores` (sk.L1.ore_belts): corundum, malachite and ebony. `dwemer_vents` (sk.L1.dwemer_bedrock): Dwemer ruins over the hot rock |
| L2 | `snow_coast` (sk.L2.polar_coast): Windhelm is snowbound. `steam_fog` (L1.steam_basin): steam clouds over the springs. `mild_basin` (L1.steam_basin): no snow on the warm ground |
| L3 | `windhelm_bridge` (L1.white_river_mouth): the city's long bridge controls the river crossing. `darkwater_ford` (sk.L3.treva_darkwater): a ford village on the river from the Rift. `hot_pools` (L1.steam_basin) |
| L4 | `hot_spring_grass` (L1.steam_basin, L2.mild_basin): lush grass, mudcrabs, frogs and slaughterfish. `fumarole_tundra` (L1.steam_basin): sulphur ground, giants and mammoths. `snow_pine` (L2.snow_coast). `eldergleam` (L11.eldergleam): an ancient sacred tree in a cave |
| L5 | `harbour` (L3.windhelm_bridge): ships, a Company office, Argonian dockhands. `mines` (L1.small_ores): Kynesgrove, Darkwater Crossing and the Orc mine. `river_mills` (sk.L3.white_river). `warm_farms` (L2.mild_basin): farms on the warm ground |
| L6 | `windhelm_seat` (L1.white_river_mouth, L3.windhelm_bridge, L5.harbour). `kynesgrove` (L5.mines). `darkwater_crossing` (L3.darkwater_ford, L5.mines). `mills_and_farms` (L5). `narzulbur` (sk.L7.orc_strongholds, L5.mines) |
| L7 | `grey_quarter` (sk.L7.outsiders): Dunmer refugees in a neglected quarter. `docks_outside` (sk.L7.outsiders, L5.harbour): Argonians live and work outside the walls. `stone_quarter` (L6.windhelm_seat): old clans and the palace. `nord_first` (L9.rebel_capital) |
| L8 | `talos_open` (sk.L8.eight_and_one): the city's temple is to Talos. `kings_city` (L11.ysgramor_city): pride in the city of the old kings. `boethiah_cult` (sk.L8.daedric_wilds): a cult shrine in the hills. `refugee_faith` (L7.grey_quarter) |
| L9 | `rebel_capital` (sk.L9.civil_war, L11.high_king_slain): the Stormcloaks' seat. `refugee_tension` (L7.grey_quarter, L9.rebel_capital). `mammoth_wars` (L4.fumarole_tundra): farms and giants clash over the springs |
| L10 | `old_city_killer` (L7.stone_quarter): a killer stalks the narrow streets at night. `steam_ruin` (L1.dwemer_vents): a Dwemer ruin with a lens observatory. `bandit_mine_camps` (L5.mines). `vampire_cave` (sk.L10.volkihar): a vampire den in the hills |
| L11 | `ysgramor_city`: Ysgramor's people built the city and its palace. `red_year_refugees` (sk.L11.red_year). `eldergleam`: the mother of Whiterun's sacred tree |

### 1.10 The Reach (`reach`, 4.64 km2, levels 8-30)

**World truth.** The Reach is a land of granite gorges and red crags at Skyrim's western edge, wetter and milder than
the north. Markarth is cut into a Dwemer city at the head of the Karth gorge; it drinks through Dwemer channels and
sits on the richest silver in Skyrim, which one family owns along with the prison mine where convicts dig it. The
native Reachfolk, who seized the city and lost it in a massacre, now fight from crag forts as the Forsworn, led from
inside the prison mine. Hagravens keep the old gods, Orc strongholds work their own mines, and the Blades' lost
temple hides in a crag.

| Layer | Fields |
| --- | --- |
| L1 | `granite_gorges`: deep river gorges and pillar crags. `silver_veins` (sk.L1.ore_belts): silver under Markarth; iron, gold and moonstone in smaller veins. `karst_caves` (L1.granite_gorges): caves in every cliff. `dwemer_city_rock` (sk.L1.dwemer_bedrock): the Dwemer city cut into the cliff at the gorge head. `crag_temple` (L1.granite_gorges): a temple carved into a crag in the south |
| L2 | `wet_highland` (sk.L2.sheltered_south): mild and damp beside the snowy north. `gorge_mist` (L1.granite_gorges): morning mist in the gorges. `west_storms` (sk.L1.spine_ranges): storms come over the western ranges |
| L3 | `karth_upper` (sk.L3.karth_river): the Karth runs north-east through the hold. `dwemer_channels` (L1.dwemer_city_rock): the city draws water through Dwemer channels; whoever holds the city holds the water. `mine_runoff` (L1.silver_veins) |
| L4 | `gorge_meadow` (L3.karth_upper): grass, flowers and herbs on the river floor. `crag_scrub` (L1.granite_gorges): juniper and thorn, goats, hagraven nests. `cave_mouths` (L1.karst_caves): bats and spiders. `upland_pine` (L2.wet_highland) |
| L5 | `silver_mining` (L1.silver_veins): the prison mine is the city's wealth. `small_mines` (L1.silver_veins): mine villages for silver, iron, gold and moonstone. `orc_mining` (sk.L7.orc_strongholds, L1.silver_veins). `river_farms` (L3.karth_upper). `border_trade` (sk.L5.road_hub): roads to High Rock and down the Karth |
| L6 | `markarth_seat` (L1.dwemer_city_rock, L3.dwemer_channels, L5.silver_mining). `mine_villages` (L5.small_mines): Karthwasten, Soljund's Sinkhole, Left Hand Mine, Kolskeggr. `karth_road` (L3.karth_upper): the road follows the river to Dragon Bridge. `hroldan_inn` (L6.karth_road): an old inn on the Whiterun road. `two_strongholds` (L5.orc_mining) |
| L7 | `silver_family` (L5.silver_mining): one family owns the silver, the prison mine and the city. `prison_labour` (L5.silver_mining): convicts dig the silver. `miner_poverty` (L7.silver_family): miners are poor and cannot leave. `reachfolk_outcast` (sk.L7.reachfolk_outcasts) |
| L8 | `old_gods` (L11.reach_kingdom): the Reachfolk keep the old gods through hagravens and briarhearts. `dibella_temple` (sk.L8.hold_temples). `talos_secret` (sk.L8.eight_and_one): Talos worship in secret, punished by the mine. `dead_hall_cult` (L10.dead_eaters): something eats the dead in the Hall of the Dead |
| L9 | `native_uprising` (L11.markarth_incident, L7.reachfolk_outcast): the Forsworn fight to take the Reach back. `imperial_jarl` (sk.L9.civil_war): the jarl owes his seat to the Empire. `silver_power` (L7.silver_family) |
| L10 | `conspiracy_in_the_mine` (L7.prison_labour, L9.native_uprising): the rebellion is led from the prison mine. `dwemer_depths` (L1.dwemer_city_rock): the Dwemer halls go on under the city. `hagraven_covens` (L4.crag_scrub, L8.old_gods). `forsworn_redoubts` (L9.native_uprising, L1.karst_caves). `dead_eaters` (sk.L8.daedric_wilds). `blades_temple` (L1.crag_temple, sk.L9.dragon_crisis) |
| L11 | `dwemer_era`. `reach_kingdom`: Reachfolk kings ruled here before the Nords. `nord_conquest`. `markarth_incident` (sk.L11.markarth_incident): the native seizure of the city and its bloody end. `old_hroldan_battle`: the future Talos fought a battle at the inn's site |
