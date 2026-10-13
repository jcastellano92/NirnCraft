# Skyrim: province plan (the template province)

Status: plan, no code changed; all ten sections written (2026-10-13). The design input of roadmap R4
(`docs/prompts/R4-skyrim-uplift.md`; phases SK1-SK8 in section 10.4). Governing rules: `docs/world/DESIGN-PRINCIPLES.md` (world building is king, the
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

---

## 2. Micro-biomes (MS21)

Skyrim's nine holds are each painted today with 3-9 coarse biomes (`palettes.json`: Falkreath 3, the Rift 4, the Reach
4, Haafingar 5, Whiterun 6, Hjaalmarch 6, the Pale 8, Winterhold 9, Eastmarch 9 distinct ids) and no `micro` rule table
at all. Real Skyrim reads as a mosaic: a hot-spring meadow in the snow, a birch glade in the pines, a salt flat
at a river mouth. This section is that mosaic, one patch kind per cause in the hold's sheet.

**Rules.** Floor per hold: at least 6 kinds and at least 2 per km2, so the count per hold is `max(6, ceil(2 x km2))`.
Each rule runs inside a `TamrielBiomeSource` context (LOWLAND, UPLAND, PEAK, MARSH, BEACH, RIVER, LAKE) and reads the
R3b rasters: rain shadow (ridge field and L2 prevailing wind), heat (springs, vents and the Dwemer cluster), slope
and aspect. No rule may cover under 0.5% or over 60% of its hold's land. A patch changes the ground palette, the flora
set, the fog and weather odds, the ambience and the spawn keys (`why` on every rule). Transitions: at least 3 per km
of road (F-MICRO). The cause column cites the sheet of section 1.

**Signature rule for Skyrim.** The province keeps a cold read everywhere: conifers, birch and tundra grass carry
every patch outside the geothermal basin; no palm, no broadleaf lushness except the Rift's birch and maple and
Eastmarch's warm ground. Heat is the one thing that lifts the snow, and it does so only where `heat` says so.

| Hold | km2 | Floor (kinds) | Planned | Today (palette biomes) |
| --- | --- | --- | --- | --- |
| Whiterun | 2.96 | 6 | 7 | 6 |
| Haafingar | 1.68 | 6 | 7 | 5 |
| Falkreath | 4.64 | 10 | 11 | 3 |
| Hjaalmarch | 1.41 | 6 | 7 | 6 |
| The Pale | 1.75 | 6 | 7 | 8 |
| The Rift | 4.17 | 9 | 10 | 4 |
| Winterhold | 2.33 | 6 | 7 | 9 |
| Eastmarch | 2.91 | 6 | 9 | 9 |
| The Reach | 4.64 | 10 | 11 | 4 |
| **Province** | 26.49 | 65 | 76 | |

### 2.1 Whiterun (dry tundra, river flats, the Throat's skirt)

| Micro-biome | Rule sketch | Cause | Effect (flora, weather, creatures, work) |
| --- | --- | --- | --- |
| `steppe_grass_flat` | LOWLAND, rain shadow < 0.3, slope < 0.15 | whiterun.L4.steppe_grass | Lavender, thistle, tundra cotton, wiry grass; steady wind, clear sky; rabbits, foxes, wolves; herders and herb pickers |
| `giant_pasture` | LOWLAND flat, > 160 blocks from a settlement, next to steppe | whiterun.L4.giant_grounds | Trampled grass, bone heaps, boulders; wind; mammoths, giants, sabre cats; tusk hunters |
| `river_farm_flat` | LOWLAND, river distance < 70, soil high | whiterun.L3.white_river_flats | Wheat, cabbage, hedgerows; dawn mist; hares, crows; farms and the mill |
| `falls_gorge` | RIVER, slope > 0.5 at Valtheim | whiterun.L1.white_river_falls | Spray moss, ferns, wet ledges; river mist; slaughterfish, bandits on the towers; toll keepers |
| `throat_meadow` | UPLAND/PEAK y 150-230, south-east aspect | whiterun.L4.throat_meadow | Alpine flowers, short turf; cloud caps and sudden squalls; goats; pilgrims, goatherds |
| `barrow_ridge_heath` | UPLAND ridge crest, slope 0.2-0.5 | whiterun.L1.ridge_barrows | Stunted grass, cairns, bone litter; clear and cold; draugr at night, perched dragons; none |
| `mill_timber_edge` | LOWLAND by river or lake, tree density high | whiterun.L5.riverwood_timber | Spruce stands, stump fields, sawdust ground; mild; deer; woodcutters |

### 2.2 Haafingar (cliffs, estuary, pine coast)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `sea_cliff_heath` | UPLAND, coast distance < 120, slope > 0.4 | haafingar.L1.sea_cliffs, L2.winter_gales | Salt-bitten heath, wind-bent spruce; gusts; gulls, horkers below; lighthouse keepers |
| `cove_shingle` | BEACH, sheltered aspect | haafingar.L1.sea_caves | Shingle, kelp racks, driftwood; sea fog; mudcrabs, smugglers; fishers' huts |
| `karth_estuary_flat` | MARSH, tidal (coast distance < 80) | haafingar.L3.karth_mouth | Reed, mud, salt grass; dawn fog; slaughterfish, waders; eel traps, ferry landing |
| `east_bank_fen` | LOWLAND, wet > 0.2, east of the Karth | haafingar.L3.estuary_marsh | Cotton grass, sedge, pools; fog; spiders, wisps; herb gatherers |
| `pine_coast_ridge` | UPLAND y 90-170, 30-200 inland | haafingar.L4.pine_coast | Spruce, pine, ferns; cold rain and sleet; elk, wolves; the sawmill's cutters |
| `spriggan_glen` | LOWLAND hollow, dense canopy | haafingar.L4.spriggan_glens | Moss, glow caps, hanging lichen; still damp air; spriggans; none |
| `hagraven_hill_scrub` | UPLAND slope 0.5-0.8 | haafingar.L4.hill_covens | Thorn, bone totems, feathers; haze; hagravens; none (cursed ground) |

### 2.3 Falkreath (the southern forest)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `old_pine_deep` | canopy density > 0.7, rain index high | falkreath.L4.old_pine | Tall pine, thick needle floor, moss; green gloom, drip; deer, bears, wolves; woodcutters |
| `fern_moss_hollow` | LOWLAND hollow, wet | falkreath.L2.mild_wet | Ferns, mushrooms, mossy logs; damp haze; skeevers, spiders; alchemists' foragers |
| `lake_reed_shore` | LAKE margin | falkreath.L4.lake_shore | Reeds, cattails; morning mist; mudcrabs, slaughterfish; fishers, the mill |
| `stream_grotto` | RIVER (small order) inside slope > 0.45 | falkreath.L3.forest_streams | Waterfalls, wet rock, ferns; spray; frogs, bats; hermits |
| `spriggan_grove` | LOWLAND clearing, old trees | falkreath.L4.spriggan_groves | Pale bark, bright moss, soft light; still; spriggans; sacred ground, nobody works it |
| `grave_yew_meadow` | within 150 of the graveyard | falkreath.L4.grave_ground, L5.burial | Yews, black grass, wreaths, crows; dusk mist; wisps, ghosts at night; gravediggers, mourners |
| `bear_berry_slope` | UPLAND, south aspect | falkreath.L4.bear_country | Juniper, berry thickets, game trails; warm clear sun; bears, elk; hunters' camps |
| `pass_snowfield` | UPLAND/PEAK on the pass road | falkreath.L2.pass_snow | Spruce in drifts, scree; snow all year; wolves, bandits; toll posts |
| `foothill_scree` | UPLAND slope > 0.6 under the Jerall wall | falkreath.L1.jerall_foothills | Bare rock, lichen; gusts; goats, trolls; none |
| `valley_fog_basin` | LOWLAND basin at lake level | falkreath.L2.valley_fog | Wet grass, birch; thick dawn fog; wisps; farms |
| `burnt_ground` | place-bound round Helgen (ash overlay, fades over game days) | falkreath.L6.helgen_fort, sk.L9.dragon_crisis | Ash, charred stumps, fireweed; falling ash; crows, scavengers; refugee camps |

### 2.4 Hjaalmarch (the marsh)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `cotton_fen` | MARSH | hjaalmarch.L4.fen | Cotton grass, deathbell, reeds; morning fog; spiders, wisps, mudcrabs; herb gatherers |
| `salt_flat` | LOWLAND/BEACH, coast distance < 250, wet 0.2-0.5 | hjaalmarch.L1.drajkmyr_flats | Salt crust, samphire, pools; glare and wind; horkers, mudcrabs; salt and fish |
| `tidal_creek` | RIVER, tidal | hjaalmarch.L3.hjaal_delta | Mud banks, driftwood, reed; mist on the tide; slaughterfish, waders; boatmen |
| `boardwalk_fringe` | MARSH within 120 of a settlement | hjaalmarch.L3.boardwalks | Rotten posts, lantern poles, rain barrels; fog; none; the town's own ground |
| `pine_hill_rise` | UPLAND hills | hjaalmarch.L4.hill_pine | Sparse pine, heath; cold damp; wolves, bandits; the timber mill |
| `granite_stonehills` | UPLAND, ore flag | hjaalmarch.L1.stonehills_rise | Rust-red outcrops, scrub; wind; none; iron miners |
| `marsh_fog_hollow` | LOWLAND hollow, fog index high | hjaalmarch.L2.marsh_fog, L10.marsh_vampires | Dead reeds, wisps; fog all day; wisps and worse; nobody stays |

### 2.5 The Pale (snow highland and the cove)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `drift_plateau` | UPLAND flat, y > 150 | pale.L2.blizzard_plateau | Deep snow, rock teeth, dwarf pine; whiteouts; frost trolls, ice wraiths; travel peril |
| `pale_heath_south` | LOWLAND, southern edge | pale.L4.pale_heath | Frost grass, heath shrubs; thin snow; giants, mammoths; two farms |
| `frozen_cove_shore` | BEACH | pale.L4.frozen_shore | Ice shingle, driftwood; ice fog; horkers; fishers, ore ships |
| `mine_tailings` | UPLAND within 120 of a mine head | pale.L3.mine_water | Grey spoil, stained snow, dead roots; orange meltwater; none; miners |
| `dwemer_vent_ground` | UPLAND on the Dwemer heat flag | pale.L1.dwemer_cluster | Warm bare rock, steam, no snow; steam plumes; automatons; none |
| `crown_ridge_cairn` | UPLAND crest near the crown barrow | pale.L1.crown_barrow | Cairns, rag banners, wind-stripped rock; cold and clear; draugr; none |
| `aurora_headland` | cliff headland facing north | pale.L2.aurora_coast | Bare turf, sea stacks; aurora nights (wonder); seabirds; watchers |

### 2.6 The Rift (birch, lakes, the border heights)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `gold_birch_wood` | y 150-230, rain moderate | rift.L2.golden_autumn | Gold birch all year, red maple, fern; mild, still, wet; deer, elk, bears; woodcutters, bee-keepers |
| `maple_mushroom_floor` | LOWLAND hollow inside birch | rift.L4.birch_forest | Red maple, mushrooms, moss; damp; skeevers; foragers |
| `lake_reed_shore` | LAKE margin | rift.L4.lake_shore | Reeds, alder; mist; ducks, fish; reed cutters |
| `lake_mist_flat` | LOWLAND within 150 of a lake | rift.L2.lake_mist | Wet meadow, willow; heavy morning mist; ducks, slaughterfish; the fishery |
| `honey_meadow` | LOWLAND near the estate island | rift.L5.honey_estate | Flower meadow, hives; calm; bees, deer; apiarists |
| `treva_gorge_spray` | RIVER, slope > 0.5 | rift.L1.treva_gorge | Ferns, wet rock; spray; salmon runs; ferrymen |
| `rift_tundra_plateau` | UPLAND flat, north | rift.L1.rift_tundra | Grass tundra, scattered pine; wind; sabre cats, elk; hunters |
| `spriggan_glade` | LOWLAND clearing in birch | rift.L4.spriggan_glades | Pale grass, glow caps; still; spriggans; none |
| `border_snow_heights` | PEAK, y > 260, east | rift.L1.border_heights | Snow pine, scree; mountain snow; wolves, smugglers' guards; smuggling trails |
| `ore_slag_ground` | UPLAND within 100 of a mine | rift.L5.redbelly_iron | Rust ground, slag; smoke haze; none; miners |

### 2.7 Winterhold (collapse, ice, aurora)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `broken_cliff_edge` | UPLAND, coast distance < 60, slope > 0.6 | winterhold.L1.collapsed_cliff | Sheer rock, ruined footings, snow; gales; gulls, horkers below; none |
| `ice_floe_shore` | BEACH / ocean edge | winterhold.L1.ice_floes | Ice slabs, shingle; ice fog; horkers, mudcrabs; hunters |
| `glacier_waste` | UPLAND east, glacier flag | winterhold.L1.glacier | Blue ice, crevasses; storms; ice wraiths, frost giants; none |
| `stack_spray` | cliff and stack faces | winterhold.L1.college_stack | Wet rock, spray ice; wind; seabirds; none |
| `snow_pine_pocket` | LOWLAND, sheltered by relief | winterhold.L4.snow_pine_pockets | Small pine stands; still; deer, wolves; trappers |
| `wreck_strand` | BEACH with a wreck | winterhold.L5.wreck_salvage | Driftwood, ribs, rope; sea fog; mudcrabs, scavengers; salvagers |
| `saarthal_ridge_scree` | UPLAND ridge | winterhold.L1.saarthal_ridge | Scree, worked stone; cold; frostbite spiders; none |

### 2.8 Eastmarch (the geothermal basin and the coast)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `hot_spring_meadow` | heat > 0.6, flat | eastmarch.L4.hot_spring_grass | Lush grass, steam, no snow; mild; frogs, mudcrabs, slaughterfish; bathers, herb pickers |
| `sulphur_pool_ground` | heat > 0.8, within 40 of a spring | eastmarch.L3.hot_pools | Yellow crust, bare ground, hot water; steam clouds; none (it burns); sulphur diggers |
| `fumarole_tundra` | UPLAND, heat 0.4-0.7 | eastmarch.L4.fumarole_tundra | Sulphur ground, tough grass; steam jets; giants, mammoths; giant camps |
| `steam_fog_hollow` | LOWLAND hollow beside springs | eastmarch.L2.steam_fog | Dense steam, wet moss; fog; wisps; none |
| `river_mouth_flat` | LOWLAND/MARSH at the White River mouth | eastmarch.L1.white_river_mouth | Reed, mud, edge ice; sea fog; slaughterfish; dock labour |
| `snow_pine_slope` | UPLAND, coastal north | eastmarch.L4.snow_pine | Snow pine; snowstorms; wolves; none |
| `eldergleam_hollow` | single placed patch | eastmarch.L4.eldergleam | Warm pink-white blossom inside a cave mouth, no snow; hush; deer, pilgrims; wonder |
| `warm_farm_terrace` | LOWLAND, heat 0.3-0.5 near a farm | eastmarch.L5.warm_farms | Terraced barley, hot-water ditches; mild; hares; farmers |
| `dwemer_vent_crust` | UPLAND on a Dwemer vent | eastmarch.L1.dwemer_vents | Bare warm rock, bronze stain, steam; steam; automatons; none |

### 2.9 The Reach (gorges and crags)

| Micro-biome | Rule sketch | Cause | Effect |
| --- | --- | --- | --- |
| `gorge_floor_meadow` | LOWLAND flat inside a gorge, river distance < 90 | reach.L4.gorge_meadow | Grass, flowers, herbs; mist; deer, goats; herbalists, small farms |
| `crag_juniper_scrub` | UPLAND slope > 0.6 | reach.L4.crag_scrub | Juniper, thorn, red rock; wind; goats, hagravens, Forsworn lookouts; none |
| `pillar_crag_summit` | PEAK, pillar landform | reach.L1.granite_gorges | Bare rock, snow cap; storms; none; none |
| `cave_mouth_ledge` | cliff within 40 of a cave | reach.L4.cave_mouths | Ferns, bat roosts, cool draught; bats, spiders; none |
| `wet_upland_pine` | UPLAND y 160-230, rain index > 0.5 | reach.L2.wet_highland | Pine, fern; damp; elk; woodcutters |
| `karth_gravel_bank` | RIVER | reach.L3.karth_upper | Gravel, willow; sound of water; salmon; none |
| `mine_runoff_flat` | LOWLAND downslope of a mine | reach.L3.mine_runoff | Bleached, stained grass; silver-grey water; none; miners |
| `gorge_mist_floor` | LOWLAND, deep gorge | reach.L2.gorge_mist | Wet stone, moss; morning mist; bats; none |
| `hagraven_crag` | within 100 of a coven site | reach.L8.old_gods | Bone totems, antlers, feathers; haze; hagravens, briarhearts; none |
| `orc_forge_slope` | UPLAND within 150 of a stronghold | reach.L5.orc_mining | Spoil, slag, tusk palisade; smoke; none; mines and forges |
| `west_storm_ridge` | UPLAND/PEAK, western exposure | reach.L2.west_storms | Bare grass, driving rain squalls; storms; none; none |

**Gate (F-MICRO).** `region_audit.py` census: at least the planned counts above (floor 65), each rule citing a sheet
field, no rule under 0.5% or over 60% of its hold, at least 3 transitions per km of road in every hold, patch
effects visible in `/skybiome census` (flora, weather odds, ambience, spawn keys), and the Haiku blind test (G1) names
the hold from 3 renders. Patches with a place-bound or event-bound trigger (`burnt_ground`, `eldergleam_hollow`) are
exempt from the 0.5% rule.

---

## 3. Map changes and geography

Skyrim is canonical (`SCALE-AND-GEOGRAPHY.md` 5.1, source precedence in WORLD-PLAN). It is the yardstick for the
whole continent: 1.11 blocks per game metre (plus or minus 0.07), relief p50 74, p90 168, ruggedness 5.6, and the
vertical curve of 0.35 blocks per metre up to about 360 m, 0.45 at 580 m. **No relief change, no anchor move, no scale
change.** Measured against the reference markers (`markers.json`), 348 of the 377 non-DLC markers match a Skycraft
place by name and every one lies within 150 blocks of its reference position. What is left is the short list below.

### 3.1 Fixes

| # | Fix | Coordinates (blocks) | Why |
| --- | --- | --- | --- |
| M1 | Hold labels: 11 places whose `region` disagrees with the reference hold and sit nearer the reference hold's seat. Relabel the `region` field (accounting only; the raster is untouched) | see 3.2 | Hold counts (marked places, dungeons, quests) are attributed by the `region` field; today Whiterun carries five places on Hjaalmarch's side of the line |
| M2 | Dawnstar harbour. The port anchor stands on the town's flat pad about 60 blocks from open water: water begins near (120, -1900) while the anchor is (155, -1835) and the town (174, -1801). Cut a cove about 70 x 40 blocks into the pad's north edge so quays and boats have real water inside the town box | anchor (155, -1835); cove x 110-230, z -1890 to -1850 | SKYRIM-SWEEP: "the map has no sea inside the town box"; dock pins were dropped |
| M3 | Amol, a roadside hamlet on the Windhelm-Winterhold road (Arena's Amol; our `fort_amol` is a different place at (1276, 245)). Hamlet tier, two houses, a roadside shrine | about (1690, -1050), on `winterhold_road` | WORLD-PLAN R4 S3, SCALE 5.1; spacing is clear (no named place within 600 blocks) |
| M4 | Old Snowhawk, a ruined hold seat in the northern Reach (Arena's Snowhawk; `fort_snowhawk` at (-1219, -1222) is the later fort). New id `old_snowhawk`, `dungeon_fixed` `nordic_ruin` | about (-2100, -910); nearest `chillwind_depths` 226 blocks | WORLD-PLAN R4 S3; fits `reach.L11.reach_kingdom` |
| M5 | Winterhold coast and stack. Measured: at 32-block resolution the College site is y 87 against the mainland's y 90, so there is no sea channel in the macro raster (the Java stack carves its own), and open water lies 220-250 blocks north of the town. Check both against `.reference/skyrim_ref/water_tamriel.png`; if the real gap is about 100 blocks, pull the headland's north edge in with a coast vector and add a 40-block channel at the stack | town (1417, -1749), College (1529, -1886), water near z -2000 | winterhold.L1.collapsed_cliff, L1.college_stack: the town stands on a cliff over the sea |
| M6 | Eastmarch heat raster inputs for R3b: spring and vent points. The basin lies between Windhelm (1825, -717), Mixwater (1414, -15), Darkwater Crossing (1595, 421) and Narzulbur (2224, -304); seed vents at `eldergleam_sanctuary` (1588, 239), `mzulft` (2346, 93), `kagrenzel` and `witchmist_grove` | basin box x 1400-2300, z -700 to 450 | eastmarch.L1.steam_basin |
| M7 | Aliases only: Falcrenth (Arena's Falkreath), Karthwasten Hall (Arena's Karthwasten); Granitehall, Oakwood, Sunguard and Vernim Wood as a name pool for generic farms, in lore text | none | SCALE 5.1; no new places |

### 3.2 Hold label audit (M1)

Of the 348 matched markers, 33 sit in a different hold than the game's location records give. For 11 of them the
reference hold is also the hold of the nearest seat, which makes our label the wrong one. In 18 more our label is
the nearest and the game's record is the quirk (keep ours), and 4 are far from every seat (keep ours).

| Place (x, z) | Our label | Reference hold | Action |
| --- | --- | --- | --- |
| `brood_cavern` (-1190, -807) | whiterun | hjaalmarch | relabel |
| `orotheim` (-1388, -704) | whiterun | hjaalmarch | relabel |
| `eldersblood_peak` (-971, -688) | whiterun | hjaalmarch | relabel |
| `talking_stone_camp` (-1437, -458) | whiterun | hjaalmarch | relabel |
| `gjukars_monument` (-1302, 32) | whiterun | hjaalmarch | relabel |
| `bleakwind_bluff` (-1987, -348) | whiterun | the_reach | relabel |
| `volskygge` (-2381, -1581) | the_reach | haafingar | relabel |
| `forebears_holdout` (-1666, -1197) | hjaalmarch | haafingar | relabel |
| `nightcaller_temple` (328, -1722) | winterhold | the_pale | relabel |
| `ironbind_barrow` (805, -1024) | the_pale | winterhold | relabel |
| `uttering_hills_cave` (1329, -439) | the_pale | eastmarch | relabel |

Effect on dungeon and fort counts: Whiterun 31 to 26, Hjaalmarch 20 to 23, Haafingar 21 to 23, Eastmarch 26 to 27, the
Pale 28 to 27. Whiterun lands on its median (8.8 per km2 against 8.9) instead of above it, so every "already at the
floor" reading for these holds must be re-run after M1 (`parity.py`). Where the game's own record is the odd one
(for example `fort_sungard`, `dead_mens_respite`, `battle_born_farm`), keep ours and note an alias.

### 3.3 Landmarks the map must carry (XL silhouettes)

Target: 1 per hold plus 1 per 3 km2 (12 for the province), each placed for a sightline from a main road (F-WEENIE).

| Hold | XL silhouettes | Status |
| --- | --- | --- |
| Whiterun | The Throat of the World (`peaks.json`, the graded 7,000 Steps, High Hrothgar's ledge); the palace on its rock | peak exists; add the palace profile |
| Haafingar | Solitude's stone arch and the palace on its tip | plan exists; check the arch silhouette |
| Falkreath | Bleak Falls Barrow's spire above the valley (-291, 535); the Helgen keep ruin | spire exists; ruin from the aftermath plan |
| Hjaalmarch | Labyrinthian's ring wall (-610, -786) | check silhouette |
| The Pale | Mzinchaleft's great-lift tower (-204, -1449) | new |
| The Rift | Forelhost's keep (2609, 1681); Riften's lakeside wall | keep exists |
| Winterhold | The College stack and bridge (1529, -1886) | exists (hand-laid) |
| Eastmarch | Windhelm's palace and wall; Mzulft's steam stack (2346, 93) | palace exists; stack new |
| The Reach | Markarth's Dwemer face; Sky Haven Temple's crag (-2473, -193); Karthspire (-2413, -223) | face exists; check the other two |

---

## 4. Roads, travel network and fast travel

**Today.** `named_roads.json` carries 11 Skyrim highways and nothing else: no lane, no trail. Carriage drivers
(`roads/Carriages`) serve the nine seats, `ports.json` has four Skyrim ports and the Windhelm-Raven Rock ferry. The
measured travel gap (offline model: every land cell of the nine holds against nodes at settlements, standing stones,
Daedric shrines and word walls): p90 distance to a node is **762 blocks and the maximum 1,610**, against the floor of
p90 <= 500 and maximum <= 800 (settlements alone: 825 and 1,651).

### 4.1 Named roads

| Road | Class | Route (place ids) | Status |
| --- | --- | --- | --- |
| `white_river_road` | highway | whiterun, riverwood, helgen | exists |
| `pale_pass_road` | highway | helgen, bruma | exists (cross-province, Cyrodiil end) |
| `falkreath_road` | highway | helgen, falkreath | exists |
| `treva_road` | highway | riverwood, ivarstead, riften | exists |
| `reach_road` | highway | whiterun, rorikstead, old_hroldan, markarth | exists |
| `karth_road` | highway | markarth, karthwasten, dragon_bridge, solitude | exists |
| `hjaal_road` | highway | dragon_bridge, morthal, whiterun | exists |
| `eastmarch_road` | highway | whiterun, kynesgrove, windhelm | exists |
| `rift_road` | highway | windhelm, darkwater_crossing, shors_stone, riften | exists |
| `pale_road` | highway | whiterun, dawnstar | exists; add `nightgate_inn` as a stop |
| `winterhold_road` | highway | windhelm, winterhold | exists; add `amol` (M3) as a stop |

**Missing (new entries; every id below exists today except `amol` and the cross-province far ends).**

| Road | Class | Route | Why (sheet) |
| --- | --- | --- | --- |
| `seven_thousand_steps` | trail | ivarstead (896, 901) to high_hrothgar (511, 457); `peaks.json` already draws the graded stair | sk.L8.way_of_the_voice: the pilgrims' climb |
| `north_coast_lane` | lane | morthal, stonehills, dawnstar | hjaalmarch and pale links; the coast road the game has and we lack |
| `frozen_coast_trail` | trail | dawnstar, winterhold | pale.L5.ore_ships, the College's supplies; footpath only |
| `honningbrew_lane` | lane | whiterun, honningbrew_meadery | whiterun.L5.meadery |
| `half_moon_lane` | lane | falkreath, half_moon_mill | falkreath.L5.timber |
| `lakeview_lane` | lane | falkreath, lakeview, riverwood | falkreath.L3.lake_ilinalta |
| `heartwood_lane` | lane | riften, heartwood_mill, largashbur | rift.L5.heartwood_timber, L6.largashbur |
| `black_briar_lane` | lane | riften, black_briar_lodge | rift.L7.meadery_rule |
| `narzulbur_lane` | lane | windhelm, narzulbur | eastmarch.L6.narzulbur |
| `mixwater_lane` | lane | whiterun, mixwater_mill, kynesgrove | eastmarch.L5.river_mills |
| `solitude_mills_lane` | lane | solitude, solitude_sawmill, thalmor_embassy | haafingar.L5.sawmill, L6.embassy |
| `strongholds_lane` | lane | markarth, dushnikh_yal; karthwasten, mor_khazgur | reach.L6.two_strongholds |
| `sinkhole_lane` | lane | markarth, soljunds_sinkhole | reach.L6.mine_villages |
| `druadach_highway` | highway (cross-province) | markarth, then over the Druadach to Evermor or Jehanna | SCALE 6.2; the far end belongs to R5 |
| `velothi_pass_road` | highway (cross-province) | windhelm, then over the Velothi to Blacklight | SCALE 6.2; the far end belongs to R7 |
| `jerall_south_road` | highway (cross-province) | falkreath, then south towards Elinhir | SCALE 6.2; the far end belongs to R5 |

That is 12 lanes, 2 trails and 3 cross-province highways. Each cross-province highway ends on the Skyrim side at a
border fort or inn, so Skyrim's graph bakes alone (SCALE 6.1).

### 4.2 Travel modes and fast travel (MS11)

| Mode | Nodes | Today | Work |
| --- | --- | --- | --- |
| Carriage | A driver and stable at each of the nine seats | drivers at all nine; stable places exist for five (`whiterun_stables`, `windhelm_stables`, `markarth_stables`, `riften_stables`, `katlas_farm` for Solitude) | stable lots on the four small seats' plans (Dawnstar, Falkreath, Morthal, Winterhold) |
| Ship | Solitude, Dawnstar, Windhelm, Winterhold; Windhelm to Raven Rock | exists in `ports.json` | the Dawnstar cove (M2) so the berth is real water |
| Wayshrine and stone | standing stones (11), Daedric shrines (5), word walls (42), plus hashed shrines | the stones and shrines exist as places | **about 29 wilderness nodes still needed**: Falkreath 6, the Rift 5, Haafingar 5, the Reach 4, Eastmarch 3, Winterhold 3, the Pale 2, Whiterun 1, Hjaalmarch 0. A greedy placement over land cells reaches p90 <= 500 and max <= 800 |
| Lift | The three Dwemer great lifts (Raldbthar, Mzinchaleft, Alftand) | exist as places | each is a short fast-travel pair into the deep cavern; flavour travel |

Wilderness nodes come first from existing places that make sense (a hunters' camp, a shrine, a standing stone, an
inn) and only then from the hashed fill. Greedy lower-bound positions, if nothing nearer exists: Falkreath (-2832,
1840), (-1680, 2000), (432, 2064), (-2160, 1296), (-400, 2000), (-1488, 1072); the Rift (688, 2128), (3152, 2032),
(3024, 560), (688, 1488), (1264, 2320); Haafingar (-976, -3152), (-3184, -2576), (-2352, -3024), (-3248, -1776),
(-2608, -2032); the Reach (-2960, 1776), (-2192, 1328), (-3408, 1040), (-2704, 816); Eastmarch (3120, 176), (2704,
-1328), (2800, -656); Winterhold (1008, -3088), (1936, -2768), (1168, -2320); the Pale (-624, -2480), (240, -688);
Whiterun (-784, -208).

### 4.3 Roads as political map

The civil war is visible on the roads: a hold banner where a road crosses a hold border, an Imperial or Stormcloak
checkpoint on the roads that enter a hold held by that side, and camps (section 5) at the junctions. Danger:
bandits on the Whiterun river bridges (`valtheim_crossing`), Forsworn on the Karth road, wolves and trolls on the Pale
road, smugglers on the Rift's border heights. Waysides, inns and shrines come from the existing scene plan (the
Skyrim sweep found 0 isolated dungeons after the footpath fix).

**What is missing, in order:** (1) wilderness nodes (29), (2) lanes and trails (14), (3) stable lots on four small
seats, (4) the Dawnstar cove, (5) the Skyrim ends of the three cross-province highways. Nothing here needs a Java
change beyond R3e's `travel/skyrim.json` reader.

---

## 5. Settlements

**Where the 53 comes from.** `parity.py` scores Settlements as the mean of five sub-scores (named towns per km2,
villages and farms per km2, bespoke share, named buildings per named town, named NPCs per named town). Our Skyrim is
at 100, 100, 100 on the first three and at **53 and 22** on the last two: 6.2 named buildings (real 11.7) and 10.4 named
NPCs (real 46.9) per named town. The count and the plans are there; the **named people and named buildings are not**.
`parity.py` counts a named NPC when a quest file's `npcs[]` places it at a settlement, so named residents authored
only in a city plan or the population grammar do not count yet (WORLD-PLAN O5, an R3c fix). The measured reference
is 845 named NPCs, 210 named buildings and 371 quests across the nine holds' 18 named towns.

### 5.1 Tier floors (Skyrim-measured, DESIGN-PRINCIPLES 4.4)

| Tier | Skyrim places | Named NPCs | Interiors | Named buildings | Roles | Merchants | Trainers | Quests | Scenes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Capital (5) | Whiterun, Solitude, Windhelm, Riften, Markarth | 73 (abs 63) | 27 (24) | 23 (18) | 45 (41) | 15 | 5 | 19 (12) | 8-12 |
| City (5) | Dawnstar, Falkreath, Morthal, Winterhold (+ the College in our data) | 20 (13) | 10 (7) | 11 (5) | 22 (15) | 4 | 2 | 3 (target 6) | 6 |
| Town (10) | Riverwood, Rorikstead, Ivarstead, Dragon Bridge, Karthwasten, Shor's Stone, Kynesgrove, Darkwater Crossing, Stonehills (+ Helgen in our data) | 9 (4) | 4 (2) | 4 (2) | 8 (3) | 1 | 0-1 | 2 | 3 |
| Village | mills, mines, strongholds, roadside inns | 3 (1) | 2 (1) | 1 | 2 | 0 | 0 | 1 | 2 |
| Hamlet | farms, homesteads, stables | 2 (1) | 1 | 0-1 | 1 | 0 | 0 | 1 | 1 |

Our data types the nine seats `capital`; the four small seats are scored as `city` here. `darkwater_crossing` and
`stonehills` are `village` in `skyrim_settlements.json` but town-class in the game; they take the town floor and stay
`village` so the named-town count stays at 18 (the parity denominator).

### 5.2 Per hold: named towns, people and buildings

"Floor" is the 80% line of the real game (37.5 named NPCs and 9.4 named buildings per named town) or the density
absolute, whichever is higher. "Target" is `max(density median x km2, 46.9 x named towns)`, capped by the large-hold
rule of the header (Falkreath, the Reach: the parity reference, the density figure is a stretch).

| Hold | Named towns | Named NPCs now | Floor | Target | Named bldgs now | Floor | Target | SET now | Pair needed for SET 95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Whiterun | 3 | 36 | 113 | 141 | 6 | 28 | 35 | 54 | 80% of ref (floor) |
| Haafingar | 2 | 20 | 75 | 94 | 6 | 19 | 23 | 62 | 80% |
| Falkreath | 2 | 15 | 77 | 94 | 27 | 19 | 23 | 48 | 130% (or more settlements, below) |
| Hjaalmarch | 1 | 9 | 38 | 47 | 8 | 9 | 12 | 57 | 80% |
| The Pale | 1 | 11 | 38 | 59 | 9 | 9 | 12 | 61 | 80% |
| The Rift | 3 | 33 | 113 | 141 | 11 | 28 | 35 | 50 | 80% |
| Winterhold | 2 | 14 | 75 | 94 | 19 | 19 | 23 | 41 | 114% |
| Eastmarch | 2 | 24 | 75 | 98 | 11 | 19 | 23 | 63 | 80% |
| The Reach | 2 | 25 | 77 | 94 | 15 | 19 | 23 | 55 | 100% |
| **Province** | 18 | **187** | **681** | **862** | **112** | **169** | **209** | 54 | |

"Pair needed" is how far the two sub-scores `s_npcs` and `s_bldgs` must average (each capped at 150) for the hold's
Settlements category to reach 95 once every named town has an authored plan (`s_bespoke` 120). In six holds the 80%
floor is enough. **Falkreath, Winterhold and the Reach are thin in settlements by the real game's own design** (0.43
named towns per km2 is Skyrim's minimum, and Winterhold has no village at all), so they must make up the
difference with depth: or they gain steadings. Falkreath's cheapest route is one more named town and four small
steadings (promote `half_moon_mill`; promote the hunters' cabins `hunters_rest`, `pinewatch`, `angis_camp` and
`anises_cabin` to hamlets with an owner each, sheet `falkreath.L6.hunters_camps`), which drops its pair to 84%.

**Named-NPC budget per hold** (tier median or better: capital 78, city 22, town 10, village 4, hamlet 2, then the
people who live outside any settlement):

| Hold | Seat and towns | Villages and hamlets | Outside settlements (needed to reach target) | Total |
| --- | --- | --- | --- | --- |
| Whiterun | 98 | 12 | 31: the Greybeards (4), camp captains (4), hunters and hermits, giant-camp watchers, the three barrow keepers | 141 |
| Haafingar | 88 | 10 | 6: the Dragon Bridge ferryman, the embassy's exterior staff, camp captains | 104 |
| Falkreath | 32 | 6 | 56: the Brotherhood's sanctuary (8), the coven (4), hunters (8), camp captains, graveyard keepers, hermits, the bandit chiefs | 94 |
| Hjaalmarch | 32 | 2 | 13: the marsh witch, the vampire-nest thralls, camp captains | 47 |
| The Pale | 22 | 8 | 17: the vigilant hall (4), miners' foremen, camp captains, giants' shepherd | 47 |
| The Rift | 98 | 20 | 23: the thieves' den (8), the Dawnguard fort (8), camp captains | 141 |
| Winterhold | 44 | 0 | 50: the College faculty and students (14), the salvagers, the floe scholar, the Azura keeper, camp captains | 94 |
| Eastmarch | 98 | 20 | 6: camp captains, the Eldergleam keepers | 124 |
| The Reach | 88 | 20 | 6: camp captains | 114 |

Eastmarch, Haafingar and the Reach exceed their target by design; the surplus is slack for capital depth.
The budget in the "outside settlements" column is the part parity cannot count today: **S2 must extend what
`parity.py` reads (R3c)** or place the named people through quest-file `npcs[]`.

### 5.3 Tier list, plan status and work (the 24 swept settlements, then the rest)

"Bld / res" is `SKYRIM-SWEEP.md` after content pass 2 (buildings / residents). Residents are generic people (housing
model), not the named count; the named count must be at least the tier floor and the cap on residents rises with it.

| Settlement | Hold | Tier | Plan | Bld / res | Work (S2) |
| --- | --- | --- | --- | --- | --- |
| whiterun | Whiterun | capital | `whiterun.json` | 165 / 44 | 23 named buildings, 5 trainers, court (jarl, steward, court mage, housecarl), Jorrvaskr line, Gildergreen priest; 8 doorsteps on hill steps |
| riverwood | Whiterun | town | `riverwood.json` | 34 / 20 | named people to 12-15 (Skyrim's biggest town), bridge and pier lanes, bard anchor |
| rorikstead | Whiterun | town | **none** (generic) | 16 / 15 | authored plan; farm fields as work anchors |
| honningbrew_meadery | Whiterun | village | meadery variant | n/a | owner, brewers, a rival-meadery line (sheet `whiterun.L5.meadery`) |
| solitude | Haafingar | capital | `solitude.json` | 144 / 43 | Blue Palace interior, Temple of the Divines, the Bards College lot, Castle Dour, the Company warehouse; 16 doorsteps; 9 floating crowns |
| dragon_bridge | Haafingar | town | `dragon_bridge.json` | 36 / 20 | named people to 10; the arch, toll square, Four Shields Tavern; the sawmill hand-over |
| solitude_sawmill, thalmor_embassy, katlas_farm | Haafingar | village / hamlet | variants | n/a | owner and 2 hands each; embassy staff as named people |
| windhelm | Eastmarch | capital | `windhelm.json` | 144 / 43 | Grey Quarter props and people, the Argonian dock quarter outside the walls, Hall of the Dead, the palace court; 7 props on streets |
| kynesgrove | Eastmarch | town | `kynesgrove.json` | 43 / 21 | named people to 10; Braidwood Inn, Tusk Square, Malachite Mine |
| darkwater_crossing | Eastmarch | village (town floor) | `darkwater_crossing.json` | 13 / 12 | named people to 9; ford inn, ferry landing; a secondary gate 25 blocks from road |
| angas_mill, mixwater_mill, narzulbur, farms | Eastmarch | village / hamlet | variants; narzulbur and the mills have **no plan** | n/a | Orc set for Narzulbur (chief, forge, longhouses); mill yards |
| riften | The Rift | capital | `riften.json` | 73 / 47 | thieves' den interior in the tunnels, the canal wards, the orphanage, Temple of Mara, the meadery's guard line; 5 guild anchors |
| ivarstead | The Rift | town | **none** | 23 / 23 | authored plan: the inn by the stair, the mill, the graveside lane |
| shors_stone | The Rift | town | **none** | 17 / 15 | authored plan: mine head, the miners' row, the shrine |
| heartwood_mill, largashbur | The Rift | village | `heartwood_mill.json`, `largashbur.json` | 9 / 9, 13 / 10 | Orc set at Largashbur: chief, forge, shaman, pens |
| markarth | The Reach | capital | `markarth.json` | 102 / 43 | Understone Keep interior, Temple of Dibella, the prison mine, Hall of the Dead; 26 market stalls on the plaza line |
| karthwasten | The Reach | town | `karthwasten.json` | 30 / 21 | named people to 10; Sanuarach Mine, the miners' rest |
| old_hroldan, dushnikh_yal | The Reach | village | `old_hroldan.json`, `dushnikh_yal.json` | 10 / 9, 11 / 9 | Orc set at Dushnikh Yal; the inn's shrine |
| soljunds_sinkhole, mor_khazgur, salvius_farm | The Reach | village / hamlet | **none** for the sinkhole and Mor Khazgur | n/a | mine village plan; Orc set (Mor Khazgur) |
| falkreath | Falkreath | city | `falkreath.json` | 84 / 40 | 11 named buildings, 2 trainers, court of 3, the Hall of the Dead priest cell, Dead Man's Drink; 8 floating crowns |
| helgen | Falkreath | town | `helgen.json` (aftermath module) | 61 / 19 | survivors' camp people (named), the keep's story; 13 floating crowns |
| half_moon_mill, lakeview, hunters' cabins | Falkreath | village / hamlet | `half_moon_mill` **none** | n/a | promotions of 5.2 |
| morthal | Hjaalmarch | city | `morthal.json` | 65 / 41 | Highmoon Hall interior, the Thaumaturgist's Hut, the burnt house, the alchemy yard |
| stonehills, windstad | Hjaalmarch | village / hamlet | `stonehills` **none** | n/a | iron mine head, lumber yard anchors; an inn only if wanted |
| dawnstar | The Pale | city | `dawnstar.json` | 98 / 42 | White Hall, Temple of Mara (placed), barracks, quay (needs M2); 11 named buildings |
| nightgate_inn, heljarchen, loreius_farm | The Pale | village / hamlet | inn variant | n/a | owner and hands |
| winterhold | Winterhold | city | `winterhold.json` | 52 / 40 | the ruined quarter kept, a shrine of Talos with its priest, an inn, 11 named buildings, 2 trainers |
| college_of_winterhold | Winterhold | city-class | `college_of_winterhold.json` | 10 / 11 | faculty and students (about 14 named), an arch-mage's line, the library's lectern set, the bridge-side inn |

Not swept and still on generic plans: `rorikstead`, `ivarstead`, `shors_stone`, `soljunds_sinkhole`, `mor_khazgur`,
`narzulbur`, `angas_mill`, `mixwater_mill`, `half_moon_mill`, `solitude_sawmill`, `honningbrew_meadery`,
`thalmor_embassy` (special embassy variant), `nightgate_inn`, `stonehills` and the farms: **14 authored plans** to write (plus the embassy's
special variant) beside the 20 that exist. With them every named town has a plan (`s_bespoke` 100%).

### 5.4 City plan briefs (cause and weenie)

| Seat | Plan form (cause) | Class gradient (L7) | Weenie | Brief |
| --- | --- | --- | --- | --- |
| Whiterun | Three terraces on a rock, the market at the foot (`whiterun.L6.whiterun_seat`) | Plains, Wind, Cloud: market and commoners, then warriors and temple, then court | The palace on its porch over the plain | Complete in count; its 23 named buildings and the court are the work |
| Solitude | A stone arch carries the city; palace at the tip (`haafingar.L1.solitude_arch`) | Blue Palace and nobles, the market, the docks and the poor at the gate | The arch and the palace | Interior of the palace, the Bards College as a named lot, a harbour quarter |
| Windhelm | Long bridge city of stone quarters over the river mouth (`eastmarch.L6.windhelm_seat`) | Stone Quarter and palace, the Grey Quarter, the Argonian docks outside the walls | The palace of the kings on the wall | The Grey Quarter's people and props, the docks quarter outside |
| Riften | Lake city on canals and piers (`rift.L6.riften_seat`) | Meadery families, the canal poor, the tunnels' guild | The lakeside wall and the meadery | Canals as streets, the den below, the orphanage |
| Markarth | A Dwemer city cut into a cliff, streets up the face (`reach.L6.markarth_seat`) | The silver family and the keep, craftsmen, miners and the prison, Reachfolk outside | The stone face and the waterworks | Stalls on the plaza line; the keep and mine interiors |
| Dawnstar | Mine and port under a snow bluff (`pale.L6.dawnstar_seat`) | Owners, miners in bunkhouses, the fishers | Mine heads on the bluff | A real quay after M2; the temple as a lot |
| Falkreath | Forest town round a graveyard (`falkreath.L6.falkreath_graves`) | Jarl's hall, mourners and priests, hunters outside | The graveyard under pine | Authored streets only (fill off), the hall of the dead |
| Morthal | Boardwalk town on a marsh edge (`hjaalmarch.L6.morthal_seat`) | Jarl, the suspected wizard at the edge, labourers | Lantern posts in fog | Five walkways, the burnt house left ruined |
| Winterhold | A shrunken town above a drowned one (`winterhold.L6.winterhold_remnant`) | Townsfolk and the jarl, the College across the bridge | The College stack | Ruined quarter kept, a shrine, an inn |

### 5.5 Bustling, steady and failing

| Busy (why) | Steady | Failing or hurting (why) |
| --- | --- | --- |
| Whiterun: every road meets here and the caravans come. Solitude: the Company's port, the Legion's headquarters, the court. Windhelm: a war capital with a port. Riften: lake trade, mead and smuggling. Markarth: the silver | Riverwood (mill and bridge), Rorikstead (harvests), Ivarstead (pilgrims), Dragon Bridge (the toll), Kynesgrove and Shor's Stone and Karthwasten (mines), Falkreath (burials) | Winterhold (collapse, poverty, mage blame). Morthal (fear and a burnt house). Dawnstar (nobody sleeps). Helgen (destroyed). Largashbur (a cursed clan). Karthwasten (Forsworn pressure on the mine) |

Failing places show it: shuttered houses and a smaller market, people at the door who will not sleep, a quest that can
reverse it, a rumour at the inn. The reversal is a building swap or an NPC-state change, not a stat.

### 5.6 Class disparity (L7 made visible)

| Class | Where they live | What the player sees |
| --- | --- | --- |
| Jarls and courts | The palace or hall of each seat | Court of 3 or more named roles (jarl, steward, housecarl; a court mage in the capitals), banners, a grudge line in dialogue |
| Old clans and thanes | Manors, the upper terrace | Rival houses in Whiterun, the silver family in Markarth, the meadery family in Riften, Imperial nobles in Solitude |
| Merchants and guild people | Market rows, quays, the Company's warehouse | Stalls, counting rooms, rich shop interiors |
| Craftsmen and freeholders | Inner streets, farms | Smithies, tanners, farm houses |
| Miners, mill hands, dockers | Bunkhouses, the foot of a hill, the quay | Poor houses, soot, the owners' fee posts |
| Outsiders | Outside the walls or a quarter apart | Khajiit caravans at Whiterun, the Grey Quarter and the Argonian docks at Windhelm, Reachfolk outside Markarth |
| Outcasts | Hills, woods, caves | Hermits, hunters' camps, covens, Orc strongholds under their own code |

### 5.7 Lived-in requirements (Skyrim-specific)

* **Named first.** Every named person has a home with a bed, a work anchor and a schedule (night sleepers exist since
  round 4). The crowd cap for capitals rises from about 44 to 80 so the 73-person floor can be met; the extra people
  are named, not extra bodies.
* **Courts.** Ruler, steward, court mage and housecarl in every capital and city (F-COURT 100%); the Dawnstar,
  Morthal and Winterhold courts are the four smallest and need the most work.
* **Trainers.** Five in distinct skills in each capital, two in each city (R3d trainer role; today training goes by
  profession). The College is the second training hub (magic skills).
* **Services.** Inn with rooms, smith, apothecary, general store, temple, priest of the dead, bard, jail, stables,
  carriage. Dawnstar and Winterhold have their priests since content pass 2 (WORLD-PLAN O6 is stale; close it).
* **Jobs.** No unstaffed workplaces (16 before pass 2, 0 after).
* **Reactivity.** 12 deed-keyed lines per hold (guards, innkeepers, the court), every finished side chain changes at
  least one (F-REACT).
* **Festivals.** One per hold on the calendar (section 7.5), hosted by the seat.
* **War state.** Each seat shows who holds it: banners, guard kit and camp ownership swap with the civil war outcome.

---

## 6. Dungeons, underground and landmarks

### 6.1 Counts by theme against the Skyrim floor

Named dungeons and forts per hold, `now / floor` where floor is the real median per km2 times the hold's area.
`!` marks a cell under the real absolute floor (the thinnest hold per km2), `-` under the median. Themes are mapped
from place variants (caves = bandit, spider, Falmer, troll, spriggan, skeever, ice and smuggler caves and dens; old
ruins = barrows, Nordic ruins and unique tombs; forts = type `fort`; camps = bandit, Forsworn and war camps (the war-camp floor alone is in 8.3); towers and shacks = watchtowers, Nordic towers,
Forsworn towers, cabins, lighthouses; lairs = mage, necromancer, vampire and hagraven lairs).

| Hold (km2) | Caves | Old ruins | Forts | Camps (all) | Towers, shacks | Mines | Dwemer | Lairs | Dragon | Giant | Wrecks | Groves | **All** (floor 8.9/km2) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Whiterun (2.96) | 8/8 | 4/4 | 2/3- | 4/3 | 6/6 | 0/1- | 0/1- | 1/3- | 1/1 | 4/1 | 0/1- | 1/1 | 31 / 26 |
| Haafingar (1.68) | 7/4 | **0/2!** | 3/2 | 1/2- | 4/3 | 0/1- | 0/0 | 3/2 | 0/1- | 0/0 | 3/1 | 0/0 | 21 / 15 |
| Falkreath (4.64) | **5/12!** | 8/7 | 5/5 | 3/5- | 8/9- | 2/2 | 0/1- | 3/5- | 1/2- | 1/1 | 0/2- | 3/1 | 39 / 41 |
| Hjaalmarch (1.41) | **1/4!** | 8/2 | 1/2- | 3/1 | 3/3 | 0/1- | 0/0 | 1/1 | 1/0 | 1/0 | 1/0 | 0/0 | 20 / 13 |
| The Pale (1.75) | **3/5!** | 8/3 | 1/2- | 2/2 | 1/3- | 0/1- | 7/0 | 0/2- | 1/1 | 3/0 | 1/1 | 1/0 | 28 / 16 |
| The Rift (4.17) | 8/11- | 4/6- | 3/5- | 3/4- | 8/8 | 2/2 | 1/1 | 3/4- | 4/1 | 0/1- | 0/1- | 0/1- | 36 / 37 |
| Winterhold (2.33) | 5/6- | 5/3 | 4/3 | 2/2 | 2/5- | 1/1 | 1/1 | 1/2- | 1/1 | 0/1- | 4/1 | 0/1- | 26 / 21 |
| Eastmarch (2.91) | **3/8!** | 3/4- | 4/3 | 1/3- | 4/6- | 1/1 | 2/1 | 2/3- | 1/1 | 3/1 | 0/1- | 2/1 | 26 / 26 |
| The Reach (4.64) | **8/12!** | 8/7 | **2/5!** | 8/5 | 4/9- | 2/2 | 2/1 | 1/5- | 1/2- | 0/1- | 0/2- | 0/1- | 36 / 41 |
| **Province** | 48 | 48 | 25 | 27 | 40 | 8 | 13 | 15 | 11 | 12 | 9 | 7 | **263 / 236** |

**Reading it.** The total beats the median in five holds (Whiterun, Haafingar, Hjaalmarch, the Pale, Winterhold) and
ties it in Eastmarch; the three below it are Falkreath (-2), the Reach (-5) and the Rift (-1), the big forest and
crag holds. All nine clear the absolute floor (6.7 per km2). The gap is in **shape**:
caves under the real absolute floor in five holds (Falkreath, Hjaalmarch, the Pale, Eastmarch, the Reach), forts under it
in the Reach and none of the old-ruin kind in Haafingar. The Dwemer cluster in the Pale (7) is a signature
(real Skyrim places its great lifts there too), not a surplus. The relabels of section 3.2 move Whiterun to 26,
Hjaalmarch to 23 and Haafingar to 23 before any new dungeon is added.

**Unmarked and power sites.** Skyrim's unmarked dungeons run 1 per hold at least and 0.57 per km2 at the median;
ours: 0 flagged `hidden` (the `hidden` field is not set on any Skyrim place). Target 16 (Whiterun 2, Haafingar 1,
Falkreath 3, Hjaalmarch 1, Pale 1, Rift 2, Winterhold 1, Eastmarch 2, Reach 3), each with a note or a rumour and a
sheet reason. Word walls (power sites) now run 4, 1, 4, 7, 7, 7, 4, 1, 7 across the holds in the order of the table
above; floor 1.5 per km2 is 4, 3, 7, 2, 3, 6, 3, 4, 7, so **Haafingar (1 of 3), Falkreath (4 of 7) and Eastmarch (1 of
4)** are short. Key relics that open something (the claw analogue, 0.43 per km2, about 12 for the province): none today.
Dragon mounds: 4, 1, 4, 1, 2, 3, 0, 4, 3 (median 0.96 per km2); Winterhold has none.

### 6.2 Inhabitant themes and why (L10)

Skyrim's holds carry 8-13 themes each (real: 8 / 10 / 13). Ours: Falkreath 15, Whiterun, the Rift, Winterhold, the
Pale 12, Eastmarch and the Reach 13, Hjaalmarch 11, **Haafingar 9** (under the target of 10). Each theme names its
cause in the sheet.

| Theme | Where and why | Signature of |
| --- | --- | --- |
| Draugr and dragon-priest dead | Barrows on ridges and under hills (`sk.L10.draugr_barrows`) | Every hold; densest in the Pale, Hjaalmarch and Falkreath |
| Forsworn | Crag redoubts and mine conspiracies (`reach.L10.forsworn_redoubts`) | The Reach (11 camps, caves and towers; found in no other hold) |
| Falmer and chaurus | The deep caves under Dwemer cities (`sk.L10.falmer_below`) | Winterhold pits, the Rift's passes, the Reach's gorges |
| Dwemer automatons and steam | Dwemer cities and great lifts (`pale.L10.great_lifts`, `eastmarch.L10.steam_ruin`) | The Pale, Eastmarch |
| Vampires | A fen nest, a vampire den, an island castle (`sk.L10.volkihar`, `hjaalmarch.L10.marsh_vampires`) | Haafingar, Hjaalmarch, Falkreath |
| Necromancers, mages, hagravens | Old towers and keeps, covens in the hills (`whiterun.L10.fire_mage_keep`, `haafingar.L10.hagraven_covens`) | Haafingar, Whiterun, Falkreath |
| Bandits and toll-takers | Towers and caves on trade roads (`sk.L10.bandits_on_roads`) | Whiterun, Falkreath, the Rift |
| Giants | Camps on the open plain and basin (`whiterun.L10.giant_camps`) | Whiterun, the Pale, Eastmarch |
| Spriggan | Sacred groves in old forest (`falkreath.L4.spriggan_groves`) | Falkreath |
| Wrecks and wreckers | Shipwrecks in the ice and the shallows (`winterhold.L5.wreck_salvage`) | Winterhold (4), Haafingar (3) |
| Smugglers | Sea caves and the Rift's border (`haafingar.L10.smugglers_coves`, `rift.L5.smuggling`) | Haafingar, the Rift |
| Dragon lairs | Peaks and mounds (`sk.L4.dragons`) | All; 4 in the Rift |

### 6.3 Marquee (at least 1 hand-authored dungeon and 1 other per hold)

Seven hand-authored dungeons exist (the unique layouts: Bleak Falls Barrow, Forelhost, Kolskeggr Mine, Labyrinthian,
Ustengrav, Saarthal, Ysgramor's Tomb). Four holds have none.

| Hold | Marquee dungeon | Other marquee | Status |
| --- | --- | --- | --- |
| Whiterun | **Fellglow Keep** (mage fort in the hills; a renegade circle and a hidden vault) | Whiterun and its Skyforge; High Hrothgar (unique exists) | dungeon needed |
| Haafingar | **Castle Volkihar** (island fort; 4 rooms today) re-cut as a court of the dark | Solitude (the arch and palace); the Embassy | dungeon needed |
| Falkreath | Bleak Falls Barrow (unique exists) | Helgen's aftermath | exists |
| Hjaalmarch | Labyrinthian (unique exists), Ustengrav | Morthal's burnt house | exists |
| The Pale | **Mzinchaleft** with its great lift, tied to arc `sk_5` | Dawnstar and the nightmare temple | dungeon needed |
| The Rift | Forelhost (unique exists) | Riften's tunnels | exists |
| Winterhold | Saarthal, Ysgramor's Tomb (uniques exist) | The College | exists |
| Eastmarch | **Mzulft** (Dwemer steam observatory) | Windhelm; the Eldergleam | dungeon needed |
| The Reach | Kolskeggr Mine (unique exists), Sky Haven Temple | Markarth's Understone Keep | exists |

Each marquee dungeon needs the P-C3 set: a mechanism no other dungeon has, a named boss, a lectern note, its lore-web
place, a loop with a shortcut home and a unique.

### 6.4 What lies below, and why

* **Blackreach** (`blackreach.json` exists): the Dwemer's great lit cavern under the north and west; the great lifts of
  the Pale, Winterhold and the Reach reach it (`sk.L1.dwemer_bedrock`). Falmer own it, automatons patrol it.
* **Barrows**: every ridge crowned by a barrow (`sk.L1.barrow_ridges`); the Nords bury their dead there and the dead
  do not rest (`sk.L8.ancestor_barrows`). Word walls stand in the older ones (42 power sites today).
* **Falmer pits and glacier ruins**: Winterhold's Dwemer ruin in the ice and its pits (`winterhold.L10.falmer_pit`).
* **Under the cities**: the thieves' tunnels below Riften, the Dwemer channels and mine below Markarth, the cellars
  under the College, the catacombs of Solitude's dead queen (`haafingar.L10.wolf_queen_catacombs`), Windhelm's old city
  where a killer walks (`eastmarch.L10.old_city_killer`).
* **Unmarked (16)**: a barrow behind a farm, a hunter's cache on a ledge, a coven's altar in a gorge, a Falmer pass
  behind a waterfall, a drowned wreck's strongbox. Each with a note or a rumour.

### 6.5 Dungeon identity and the variety rule

Skyrim's identity pass is done (`DUNGEON-AUDIT.md`): of **292** named dungeons and forts, **268 have no identity flag**
(sign, lectern note, named master, set piece, story loot, a trail); 43 flags remain in all (shipwrecks 11, a few cabins
and dragon lairs without a motif, the Temple of Miraak). `d_identity` 1.0 holds. What is left:

* `deepwood_redoubt` builds draugr under a Forsworn lore (needs a variant decision).
* Dungeon variety per hold (`d_caverns`, `d_rosters`): the Pale 2 and 3 and Hjaalmarch 2 and 2 are under the real
  reference (4.1 and 5.7) and far under the DESIGN-PRINCIPLES 4.5 target of 5 palettes and 8 rosters; the other holds
  hold 3-6 and 4-8.
* The sample audit of 20 named dungeons scored 20.4 out of 35 on average (round 2: 20.7 with a grand cavern in 18
  of 20), with enemy pacing, one room type per lair and invisible story as the open failures.

### 6.6 Landmarks and scenes

* **XL**: section 3.3 (12 for the province).
* **L kinds of our own** (10 or more per hold, `l_kinds`; now Whiterun and the Reach 17, Falkreath 14, the Rift 13, Hjaalmarch 12, the Pale, Winterhold and Haafingar 11,
  Eastmarch 10): hot-spring
  terraces, a fumarole field, a mammoth skeleton field, a glacier crevasse, a sea stack, a Dwemer steam stack, a
  standing-stone ring, an ice arch, a silver-slag heap.
* **M scenes**: a battle cairn, a dead camp, a gallows crossroads, a burnt farm, a hunter's blind, a toll bridge, an
  abandoned wagon, a pilgrim's shrine, a wreck with a note.

---

## 7. Quests

Our Skyrim carries 311 quests attributed to the nine holds today (parity: 334 counted by place) against the real
371: 12.6 per km2 against 14.0, and 18.6 per named town against 20.6. The count is nearly there. What is missing is
the **shape**: no hold has a conflict chain with consequences, the main story is filed under one hold, the lore web has
no Skyrim arc, and the civil war is a switch that changes no hold.

### 7.1 Province arcs

| Arc | Kind | Spine | Choice and consequence |
| --- | --- | --- | --- |
| **The Dragon Crisis** (province main story; 20 quests in `main.json`, all attributed to Whiterun today) | Main | A dragon breaks an execution at Helgen and the old war returns: the first attack on a Whiterun watchtower, the claw under Bleak Falls Barrow, the Greybeards' climb, the Blades' hidden keeper in the Rift, the Thalmor embassy's dossiers, the elder word walls, the World-Eater's peak. Stages are re-attributed to the hold where they run (Falkreath, Whiterun, the Rift, Haafingar, the Pale and Hjaalmarch word walls) so every hold carries part of it | **Exclusive choice (P-J1):** the old dragon on the Throat (spared or slain). Consequence: the Greybeards' hall (a building state), the Blades' standing, a dragon's perch on the Throat |
| **The Civil War** (12 quests in `civil_war.json`; the province-wide conflict that touches every hold) | Faction, exclusive | Two camps in every hold, the jagged crown, the message to Whiterun, the siege of Whiterun, then the fall of the other side's capital | **Exclusive choice:** Legion or Stormcloak. Every hold has a war flag (below). Finishing the war flips the held seats: jarl, banners, guard kit and camp ownership |
| **The Long Night** (7 quests in `dawnguard.json`, filed as side today) | Faction, exclusive | A revived order of hunters at a border fort in the Rift against a vampire court on an island off Haafingar, with the elder prophecy between them | **Exclusive choice:** hunters or the court. Consequence: Fort Dawnguard and Castle Volkihar become each other's ruin, vampire spawns in the marsh and the Rift change |

**War flag per hold (the sheet's L9).** Imperial: Haafingar, the Reach, Falkreath's seat (its young jarl's uncle quietly
favours the rebels). Stormcloak: Eastmarch, the Pale, Winterhold, the Rift (the jarl leans that way). Neutral and
contested: Whiterun (the jarl refuses both until the siege) and Hjaalmarch (the jarl will not commit). Each flag
drives banners, guard kit, the two camps of the hold (8.3), the checkpoint on the roads and one line of every guard's
talk. The three seats that change hands at the end are the player's choice.

### 7.2 Hold conflict chains (at least 3 quests each, with a visible consequence)

Level bands are the regions' own (`regions.json`); the Reach's 8-30 is 22 wide, over the 20 limit of F-LEVEL, and
narrows to 9-29 (see 10.3). Consequences are building or NPC-state swaps, never a number only.

| Hold (levels) | Chain | Spine (sheet cites) | Ends with |
| --- | --- | --- | --- |
| Whiterun (1-10) | **Two Banners, One Gate** (4) | Two old clans' hirelings brawl at the gate; the toll towers at Valtheim; the Khajiit caravan kept outside; both camps press the neutral jarl (`whiterun.L7.clan_feud`, `L3.valtheim_crossing`, `L7.caravans_outside`, `L9.neutral_jarl`) | The caravan is let inside or kept out (stalls appear or vanish), the toll is cut or raised, one clan house is shuttered |
| Haafingar (5-24) | **A Moot Without a Crown** (4) | The widow's moot, the Company's ledger, the embassy's dossier, a drowned queen's rumour (`haafingar.L9.succession`, `L5.company_docks`, `L9.thalmor_embassy`) | A candidate is chosen: the court's faces, the dock hours and the embassy's visible staff change |
| Falkreath (5-22) | **The Uncle's Ledger** (3) | The young jarl and his uncle's quiet favours; Helgen's survivors; bandits on the pass road (`falkreath.L7.young_jarl`, `L9.jarl_uncle`, `L9.helgen_burned`, `L10.pass_bandits`) | Helgen is rebuilt as a camp-town or left as a ruin (a building swap); the seat's war flag may flip |
| Hjaalmarch (5-24) | **The Burnt House** (3) | Who set the fire; the suspected conjurer on the edge of town; the nest in the fen (`hjaalmarch.L7.wizard_suspected`, `L8.fear_of_the_dark`, `L10.marsh_vampires`) | The conjurer is cleared or driven out, the barred-door fear lifts (night lamps return) or deepens, the nest is burned |
| The Pale (6-26) | **The Sleepers' Wage** (4) | Owners against miners; a temple of dreams that will not stay sealed; the crown race (`pale.L7.miners_and_owners`, `L10.nightmare_temple`, `L9.crown_race`) | A mine changes owner or hours; the town's sleeplessness ends or worsens; the crown goes to a side |
| The Rift (6-26) | **The Honey and the Ledger** (4) | The meadery family's hold on the guards; the orphanage's keeper; the guild below; the border smugglers (`rift.L7.meadery_rule`, `L7.cruel_orphanage`, `L10.thieves_below`, `L5.smuggling`) | The guard captain is replaced, the orphanage keeper is removed or protected, the thieves' den's luck returns or fails |
| Winterhold (10-30) | **Blame at the Bridge** (3) | Townsfolk against the College over the collapse; salvage rights on four wrecks; a seat with nothing to rule (`winterhold.L7.town_vs_college`, `L5.college_coin`, `L5.wreck_salvage`) | The College opens a lecture hall and pays a tithe, or the town gets a market charter: an inn, a shop and a priest more |
| Eastmarch (8-28) | **The Grey Quarter's Lamps** (4) | Dunmer refugees in a neglected quarter; Argonians outside the walls; a killer in the old streets (`eastmarch.L7.grey_quarter`, `L7.docks_outside`, `L10.old_city_killer`) | The quarter gets lamps, a watch post and a healer; or the walls close at night; the docks get a gate |
| The Reach (9-29) | **The Silver Ledger** (4) | The silver family's mine and prison; Forsworn raids from the mine's own conspiracy; the dead in the hall (`reach.L7.silver_family`, `L10.conspiracy_in_the_mine`, `L9.native_uprising`) | The family's grip is broken or sealed, convict labour changes, the Forsworn truce holds or the raids climb |

Already present: the Reach's `forsworn.json` (7) and the Pale's `vigilants.json` (5) are lines of this kind but carry no
sheet-keyed consequence; Hjaalmarch has 1 curse quest. The other hold chains are new. Every capital also adds a
court problem and 3 favours; every town 2 problems and 1 secret tied to its industry (Riverwood's bridge, Rorikstead's
bad soil, Ivarstead's pilgrims, Shor's Stone's lost miner, Karthwasten's wages, Dragon Bridge's toll).

### 7.3 Settlement quests: now against the floor

Counts are parity's quests per hold (journal quests placed in the hold). Floor is 11.2 per km2 (80% of the real 14.0),
target 14.0 per km2. Parity also asks 16.5 quests per named town at the floor and 20.6 at the target.

| Hold | Quests now | Per town now | Floor | Target | Gap to target |
| --- | --- | --- | --- | --- | --- |
| Whiterun | 54 | 18.0 | 33 | 41 | 0 |
| Haafingar | 44 | 22.0 | 19 | 24 | 0 |
| Falkreath | 34 | 17.0 | 52 | 65 | **+31** |
| Hjaalmarch | 19 | 19.0 | 16 | 20 | +1 |
| The Pale | 23 | 23.0 | 20 | 24 | +1 |
| The Rift | 46 | 15.3 | 47 | 58 | **+12** (and under the per-town floor) |
| Winterhold | 34 | 17.0 | 26 | 33 | 0 |
| Eastmarch | 38 | 19.0 | 33 | 41 | +3 |
| The Reach | 42 | 21.0 | 52 | 65 | **+23** |
| **Province** | 334 | 18.6 | 298 | 371 | **+71** |

Three holds below their floor (Falkreath, the Rift, the Reach) carry nearly all of the gap. It is filled in this order:
the hold chain (3-4 quests), faction extensions that sit in the hold (7.4), favours from the named residents of the
new plans (2-3 per town, 1 per village), then radiant offers (4 per hold, up to 22 in the largest, 7.5). Each authored quest names a real place and a named
person and leaves a consequence (`q_placed` >= 0.9; now 0.89-0.94). F-STARTS: at least 4 start kinds per hold
(npc, find, auto, rumour, note, board).

### 7.4 Faction lines (P-G1: 2-5 hubs, in different cities)

| Line | Hub | Today | Floor (6-14) | Notes |
| --- | --- | --- | --- | --- |
| Companions (and the Silver Hand) | Whiterun, the warriors' hall | 5 and 4 | 6 | Add the hall's rank line and a radiant "honour" job; the curse arc stays |
| College of Winterhold | Winterhold | 7 | 12 | The library lecterns, the arch-mage's line, the mage-blame conflict (7.2) |
| Thieves Guild | Riften, in the tunnels | 8 | 12 | Ties to the meadery chain; city jobs in all five capitals |
| Dark Brotherhood | Falkreath sanctuary (and the one near Dawnstar) | 6 | 9 | The sanctuary's named people count outside settlements (5.2) |
| Bards College | Solitude | 4 | 6 | Four instrument quests now; add a song-collection line across holds |
| Greybeards | High Hrothgar | 5 | 6 | Pilgrims from Ivarstead (the Steps) |
| Civil War | every hold | 12 | 12 | Hold-flip consequence (7.1) |
| Dawnguard / Volkihar | Fort Dawnguard, Castle Volkihar | 7 | 12 | Both sides (exclusive) |
| Forsworn and the Reach | Markarth, Karthwasten | 7 | 7 | Becomes the Reach's hold chain |
| Vigilants of Stendarr | Dawnstar hall | 5 | 6 | Separate hall from the temple |

**Exclusive choices (P-J1):** the civil war, the Long Night, and the Throat's old dragon. Each closes the other
side's upper ranks and changes at least three NPCs' greetings in three holds. The four guild hubs are not exclusive
(a player may be Companion and Thief, as in the game).

### 7.5 Radiant and festivals

* **Radiant** (templates in `radiant_templates.json`, 33 today; Skyrim wants at least 6 culture-specific ones): a jarl's
  bounty on a named bandit chief at a named camp (`named_beast` and `turncoat` templates), a giant camp cull, a
  Forsworn camp clearing (the Reach only), a draugr relic returned to its barrow (`stolen_heirloom`), a marsh
  vampire purge (`elder_vampire`), a mammoth-tusk delivery, an ore convoy (`salvage_job`), an escort up the Steps, a
  courier between two jarls in wartime. Givers are the jarls' stewards and the town boards (MS12, 3-6 live jobs, aimed
  at real uncleared dungeons 300-1,500 blocks away).
* **Festivals** (MS16, one per hold on the calendar; dates are checked in the calendar step, not here): Whiterun the
  harvest fair at the sacred tree; Haafingar the burning of the old king (the bards' feast); Falkreath the day of
  lamps for the dead; Hjaalmarch a lamp night against the fear; the Pale a midwinter aurora watch; the Rift the
  mead fair on the lake; Winterhold the ice-break (first ship through the floes); Eastmarch the founders' feast;
  the Reach a silver fair at the temple of Dibella. The province festival is the Haafingar one.

### 7.6 The lore web and the ties to the sheet

Skyrim has **0 of the 39 regional arcs** of `LORE-WEB.md`, and its places sit on none of the 8 continent threads
(Skyrim's mysteries live in place lore, `lore/places/skyrim_*.json`, and in the Solstheim arcs). The floor is at least
one arc of 4-8 places per hold; the plan is nine, plus joins to four threads. Names of invented people and every note
are written new (MS20).

| Arc | Hold | Title | Trail (existing ids) | Secret and finale |
| --- | --- | --- | --- | --- |
| `sk_1` | Whiterun | The Plain That Keeps Count | valtheim_towers, hamvirs_rest, rannveigs_fast, dustmans_cairn, eldersblood_peak | The ridge barrows are laid as a tally of the dragon war's dead; the last stone is short. Finale: a tally-warden in the peak's lair |
| `sk_2` | Haafingar | The Wreck-Light Ledger | solitude_lighthouse, the_katariah, orphans_tear, brinewater_grotto, broken_oar_grotto | False lights on the coast and a Company clerk's insurance. Finale: the quartermaster below |
| `sk_3` | Falkreath | The Dead Who Are Not Local | falkreath, fort_neugrad, helgen, halldirs_cairn, sunderstone_gorge | A gravedigger-lord buries other provinces' war dead for pay. Finale: in the cairn |
| `sk_4` | Hjaalmarch | The Maze That Lets Out | stonehills, fort_snowhawk, kjenstag_ruins, lost_valkygg, labyrinthian | The old priests' city turned maze has an exit that only the wrong person finds. Finale: the maze's keeper |
| `sk_5` | The Pale | Seven Lifts, One Missing | great_lift_at_raldbthar, great_lift_at_mzinchaleft, mzinchaleft, irkngthand, great_lift_at_alftand | The lifts count riders and one lift's count is wrong. Finale: the lift-warden |
| `sk_6` | The Rift | The Honey That Does Not Spoil | goldenglow_estate, black_briar_lodge, honeystrand_cave, snow_shod_farm, riften | The meadery's hives sit on something that preserves. Finale: the hive's queen |
| `sk_7` | Winterhold | Salvage Rights | wreck_of_the_winter_war, helas_folly, pilgrims_trench, wreck_of_the_pride_of_tel_vos, snowpoint_beacon | Four wrecks, one salvage claim, one captain who wrecked them. Finale: the beacon |
| `sk_8` | Eastmarch | The Steam Under the Stones | mzulft, kagrenzel, eldergleam_sanctuary, witchmist_grove, steamcrag_camp | The basin's vents are the Dwemer's exhaust and someone has stopped one. Finale: the vent-engine |
| `sk_9` | The Reach | The Channels' Warden | markarth, karthwasten, left_hand_mine, bthardamz, arkngthamz | The city's water is rationed by a Dwemer valve nobody remembers. Finale: the warden |

`tools/marquee` has no Skyrim block today (the 231 web nodes are the other ten provinces'), so the nine arcs add
Skyrim situations to `loreweb_src` and the web builder (`loreweb_build.js`) rebuilds the graph.

Thread joins (existing threads, new Skyrim nodes): `t_bone` takes a dragon mound (`shearpoint`), `t_crown` the old
crown's barrow (`korvanjund`), `t_dial` the Dwemer ruins `mzulft` and `alftand`, `t_falls` a Reach waterfall
(`bards_leap_summit`).

---

## 8. Components layout

### 8.1 Spatial layout (how the province composes, x east, z south, blocks)

```
                      north coast: Sea of Ghosts
   Solitude (-1216,-1740)   Dawnstar (174,-1801)        Winterhold (1417,-1749)
      |  Haafingar               | The Pale                  | Winterhold
   Dragon Bridge      Morthal (-928,-1176)                    |
      |  Hjaalmarch      \        |                           |
   Karthwasten ----- Whiterun (62,-68) ======= Windhelm (1825,-717) Eastmarch
   Markarth (-3102,-191)  | \ Throat (579,652)     |   Kynesgrove / basin
      the Reach     Rorikstead  Riverwood (33,593)  \  Darkwater Crossing
                       Falkreath (-795,1250)  Helgen --- Ivarstead (896,901) --- Riften (2435,1418)
                          Falkreath hold                       the Rift
                     south: Jerall wall, Pale Pass, Cyrodiil
```

Whiterun is the hub (five of the eleven highways start or end there and a sixth leaves from Riverwood), the rim holds
sit on the coast and the mountains, and the Throat of the World stands over the whole thing. Every hold has one seat, one main road, one marquee pair and one
conflict chain.

### 8.2 Data and code components

| Component | File / system | Skyrim content | Phase |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/skyrim.json` (MS22), `worldsheet_lint.py` | Province block plus nine hold blocks (section 1) | SK1 |
| Micro-biome rules | `palettes.json` `micro`, rasters (R3b) | 76 patches (section 2) | SK1 |
| Places | `places/skyrim_landmarks.json`, `skyrim_settlements.json` | M1-M4 edits, 16 unmarked, 12 camps, 14 plans' places, 29 wayshrines, Amol, old Snowhawk | SK2, SK5 |
| City plans | `cityplans/<id>.json` | 14 new, 20 extended | SK3, SK4 |
| Roads | `roads/named_roads.json` | 2 trails, 12 lanes, 3 cross-province highways (section 4) | SK2 |
| Travel | `travel/skyrim.json` (R3e), `ports.json` | carriage stables on four seats, 29 wayshrines, the Dawnstar berth | SK2, SK7 |
| Terrain | `tools/worldgen/tamriel_vectors.json` | the Dawnstar cove, check the Winterhold coast (M2, M5) | SK2 |
| Dungeons | `dungeons/identity/skyrim.json`, `dungeons/marquee/*.json`, `structure/unique/*.json` | 4 marquee plans, unmarked, key relics, power sites, cave and ruin fills | SK5 |
| Creatures and spawns | `data/skycraft/region_spawns/skyrim.json` | hostile mix per hold (6 holds at 0 hostile kinds), signature creatures | SK7 |
| Scenes and encounters | `creatures/scenes.json` (6 kinds today) | at least 8 kinds usable per hold, 3 Skyrim-only, 3 wonder moments per hold | SK7 |
| Quests | `quests/place_skyrim_*.json`, `side_skyrim.json`, `marquee_skyrim.json`, faction files, new `quests/story/skyrim_*.json` | 9 hold chains, main re-attribution, faction extensions, war flags | SK6 |
| Lore | `lore/places/skyrim_*.json`, `tools/marquee/loreweb_src/skyrim.json` (new), threads | 9 arcs, 4 thread joins, readables | SK6 |
| Voice and reactions | `society/region_voice.json`, dialogue keys (R3d) | 12 deed-keyed lines per hold | SK6 |
| Festivals | `calendar/festivals.json` (R3e) | 9 festivals | SK6 |
| Events (optional) | `events/skyrim.json` (MS4) | a dragon attack at a watchtower, a war skirmish at a camp | after SK8 |

### 8.3 Collectibles and services (hold floors at the real median per km2; absolute floor in brackets)

| Item | Per km2 | Whiterun | Haafingar | Falkreath | Hjaalmarch | Pale | Rift | Winterhold | Eastmarch | Reach |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Marked places | 14.6 (11.0) | 43 | 25 | 68 | 21 | 26 | 61 | 34 | 42 | 68 |
| Skill books (copies) | 16.2 (10.8) | 48 | 27 | 75 | 23 | 28 | 68 | 38 | 47 | 75 |
| Readables (copies) | 195 (104) | 577 | 328 | 905 | 275 | 341 | 813 | 454 | 567 | 905 |
| Spell tomes | 1.1 | 3 | 2 | 5 | 2 | 2 | 5 | 3 | 3 | 5 |
| Enchanted gear (placed) | 2.9 (0.68) | 9 | 5 | 13 | 4 | 5 | 12 | 7 | 8 | 13 |
| Loot containers | 131 (83) | 388 | 220 | 608 | 185 | 229 | 546 | 305 | 381 | 608 |
| Power sites | 1.5 (0.34) | 4 | 3 | 7 | 2 | 3 | 6 | 3 | 4 | 7 |
| Key relics | 0.43 | 1 | 1 | 2 | 1 | 1 | 2 | 1 | 1 | 2 |
| Unmarked | 0.57 (0.22) | 2 | 1 | 3 | 1 | 1 | 2 | 1 | 2 | 3 |
| Military and war camps | 1.03 (0.43) | 3 | 2 | 5 | 1 | 2 | 4 | 2 | 3 | 5 |
| Trainers (all) | 3.55 (1.08) | 11 | 6 | 16 | 5 | 6 | 15 | 8 | 10 | 16 |

Today's camps: 2, 1, 2, 2, 2, 2, 2, 1, 2 (16 in all; +11 to the floor, Eastmarch and Haafingar are one short of a
pair). Our readable density (`r_notes`) reads 43-82 per km2 against the real 195 median: **the biggest collectible
gap**, to be confirmed by R3c's F-COLLECT (placed copies against distinct titles). Marked places today (the places files less
22 dragon mounds and 3 word-wall entries, which are not map markers): 45, 29, 46, 26, 33, 52, 32, 39, 50. Above the
median in Whiterun, Haafingar, Hjaalmarch and the Pale; **under the absolute floor in Falkreath (51) and the Reach
(51)**; short of the median in the Rift, Winterhold and Eastmarch.

---

## 9. Modernisation: keep, amplify, replace

Skyrim is the source, not a legacy source: nothing here is replaced for age. The column "thinner than the real game"
says what our Skyrim lacks against the measured one.

| Content | Keep (already good) | Amplify (thinner than real Skyrim) | Replace |
| --- | --- | --- | --- |
| Terrain | The whole map at the game's scale, relief p50 74 / p90 168 / ruggedness 5.6; the Throat, the 7,000 Steps, the nine hold polygons | Eight small fixes (section 3): hold labels, the Dawnstar cove, Amol, old Snowhawk, the Winterhold coast check | Nothing |
| Biomes | About 26 biomes in each hold's palette pool (province plus hold), signature ones per hold (hot springs, birch, marsh, glacier) | 76 micro-biome patches from the sheet; today 0 `micro` rules | Hold palettes of 3-4 biomes (Falkreath, the Rift, the Reach) |
| Settlements | All nine capitals and 11 towns have authored plans, all 24 swept settlements pass the sweep, 0 unstaffed jobs | **Settlements 53/100**: 14 more plans, named people 10.4 to 46.9 per town, named buildings 6.2 to 11.7, courts and trainers, class disparity | Generic town fill where a plan exists (cap off) |
| Dungeons | 263 dungeons and forts at 9.9/km2 (real 8.9), identity on 268 of 292, 7 hand-authored layouts, grand caverns | Marquee in four holds, 16 unmarked, power sites in three holds, caves in five, camps and forts in the thin holds, key relics | Nothing |
| Roads and travel | 11 highways, carriages at nine seats, 4 ports | 14 lanes and trails, 29 wayshrines, stables on four seats, cross-province ends | Nothing |
| Quests | 311 attributed quests: a 20-quest main line, about 70 faction quests, 14 side, 26 hamlet mysteries, 180 place quests | A conflict chain in every hold, main-line re-attribution, hold flips for the war, 71 quests in three thin holds, faction floors | The "switch" civil war (a war with no hold state) |
| Life | Residents with homes, jobs and night sleepers; crowd cap; 0 unstaffed workplaces | Named residents at the tier floors, schedules for them, courts, trainers, boards, deed-keyed lines | Anonymous crowds in capitals |
| Lore | 7 place-lore files (hooks, rumours, notes), the Solstheim arcs, rumours and songs per hold | 9 lore-web arcs and 4 thread joins, readables to the real density, per-hold voice | Nothing |
| Creatures | 42 creature kinds, 19 cave kinds, signature creatures per hold | Hostile mix per hold (0 hostile kinds per biome family in six holds), wildlife keyed to micro-biomes | Region-blind spawns |
| Items | Gear families, 8 uniques, the regional gear list of 302 | Hand-placed enchanted gear at 2.9/km2, spell tomes, skill books | Nothing |

"What the source did vs what we do" is a one-row-per-line copy of this table in the phase report.

---

## 10. Floors, gates and implementation phases

### 10.1 Current against the floor (`parity.py`, 2026-10-13, commit `cf288dd`)

Category scores (real Skyrim = 100; cells capped at 150) and the raw metrics that matter.

| Hold | km2 | SET | NPC | QST | DUN | CRE | FLO | STY | LND | LOR | s_npcs / town (floor 37.5) | s_bldgs / town (9.4) | q /km2 (11.2) | dungeons /km2 (6.7) | hand-authored | unmarked |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Whiterun | 2.96 | 54 | 147 | 97 | **51** | **50** | 93 | 91 | 112 | 110 | 12.0 | 2.0 | 18.2 | 10.5 | 0 | 0 |
| Haafingar | 1.68 | 62 | 113 | 106 | **51** | **50** | 96 | 105 | 107 | 109 | 10.0 | 3.0 | 26.3 | 12.5 | 0 | 0 |
| Falkreath | 4.64 | **48** | **46** | **72** | 90 | **44** | 96 | 91 | **76** | 83 | 7.5 | 13.5 | 7.3 | 8.4 | 1 | 0 |
| Hjaalmarch | 1.41 | 57 | 57 | 97 | 69 | **53** | 93 | 71 | 107 | 107 | 9.0 | 8.0 | 13.5 | 14.2 | 2 | 0 |
| The Pale | 1.75 | 61 | **46** | 89 | **46** | 108 | 99 | 71 | 92 | 106 | 11.0 | 9.0 | 13.2 | 16.0 | 0 | 0 |
| The Rift | 4.17 | **50** | 75 | 82 | 106 | **49** | 107 | 105 | 82 | 92 | 11.0 | 3.7 | 11.0 | 8.6 | 1 | 0 |
| Winterhold | 2.33 | **41** | 65 | 92 | 110 | 98 | 98 | 71 | 88 | 94 | 7.0 | 9.5 | 14.6 | 11.2 | 2 | 0 |
| Eastmarch | 2.91 | 63 | 83 | 96 | **53** | **51** | 107 | 114 | 89 | 106 | 12.0 | 5.5 | 13.1 | 8.9 | 0 | 0 |
| The Reach | 4.64 | 55 | 110 | 82 | 96 | 93 | 96 | 115 | 85 | 86 | 12.5 | 7.5 | 9.1 | 7.8 | 1 | 0 |
| **Skyrim** | 26.49 | **54** | 100 | 93 | 100 | 100 | 100 | 100 | 100 | 100 | 10.4 | 6.2 | 12.6 | 9.9 | 7 | 0 |

MUS and ITM are 98-100 everywhere (province-shared). The province row reads 100 on several categories because the
province values are the reference for those metrics; **the hold rows are the honest ones**. Weak cells (under 75):
SET in all nine; NPC in Falkreath, the Pale, Hjaalmarch and Winterhold; QST in Falkreath; DUN in Whiterun, Haafingar,
the Pale and Eastmarch (the four holds with no hand-authored dungeon) and Hjaalmarch; CRE in six holds (hostile
kinds per biome family are 0 against 1.0); STY (71) in the Pale, Winterhold and Hjaalmarch; LND in Falkreath.

### 10.2 Targets per hold (real medians times area; the absolute floor is never broken)

| Hold | km2 | Marked places | Dungeons | Named towns | Named NPCs | Named bldgs | Quests | Unmarked | Power sites | Camps | Micro-biomes | XL |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Whiterun | 2.96 | 43 | 26 (now 31, 26 after M1) | 3 | 141 | 35 | 41 | 2 | 4 | 3 | 7 | 2 |
| Haafingar | 1.68 | 25 | 15 (21, 23) | 2 | 94 | 23 | 24 | 1 | 3 | 2 | 7 | 1 |
| Falkreath | 4.64 | 68 | 41 (39) | 2 (3 with the promotion) | 94 (stretch 156) | 23 | 65 | 3 | 7 | 5 | 11 | 2 |
| Hjaalmarch | 1.41 | 21 | 13 (20, 23) | 1 | 47 | 12 | 20 | 1 | 2 | 1 | 7 | 1 |
| The Pale | 1.75 | 26 | 16 (28, 27) | 1 | 59 | 12 | 24 | 1 | 3 | 2 | 7 | 1 |
| The Rift | 4.17 | 61 | 37 (36) | 3 | 141 | 35 | 58 | 2 | 6 | 4 | 10 | 2 |
| Winterhold | 2.33 | 34 | 21 (26) | 2 | 94 | 23 | 33 | 1 | 3 | 2 | 7 | 1 |
| Eastmarch | 2.91 | 42 | 26 (26, 27) | 2 | 98 | 23 | 41 | 2 | 4 | 3 | 9 | 1 |
| The Reach | 4.64 | 68 | 41 (36) | 2 | 94 (stretch 156) | 23 | 65 | 3 | 7 | 5 | 11 | 2 |
| **Province** | 26.49 | 388 | 236 (now 263) | 18 | 862 | 209 | 371 | 16 | 39 | 27 | 76 | 13 |

Fixed per hold: a conflict chain (7.2), 2 marquee places, at least 8 inhabitant themes with a signature one, at
least 8 world-encounter kinds (3 Skyrim-only), 3 wonder moments, 12 rumours, legends and songs, 12 deed-keyed lines, a
festival, a notice board in every town and larger, and a lore-web arc. Settlement-tier depth is on top (5.1).

**Headline uplift targets (from now to the floor, then the target).**

| Measure | Now | Floor (80% line or abs) | Target |
| --- | --- | --- | --- |
| Settlements score (province) | 54 | 80+ | >= 95 in every hold |
| Named NPCs (nine holds) | 187 | 681 | 862 (real: 845) |
| Named buildings | 112 | 169 | 209 (real: 210) |
| Authored city plans | 20 | | 34 (every named town and village) |
| Quests | 334 | 298 | 371 (+71 over three thin holds) |
| Hold conflict chains | 0 | 9 | 9 (3-4 quests each, 33 in all) |
| Hand-authored dungeons | 7 | 9 | 11 (Whiterun, Haafingar, Pale, Eastmarch added) |
| Unmarked dungeons | 0 | 9 (1 per hold) | 16 |
| Power sites | 42 (3 holds short) | 39 | 50 (+8 in Haafingar, Falkreath, Eastmarch) |
| Lore-web arcs | 0 | 9 | 9 plus 4 thread joins |
| Wilderness fast-travel nodes | 0 | 29 | 29 (p90 <= 500, max <= 800) |
| Micro-biome kinds | 0 | 65 | 76 |
| Camps | 16 | 27 | 27 |

### 10.3 Gates

R4's gate is the union of these (`WORLD-PLAN.md` R4), per hold, with the numbers recorded in the phase report.

| Gate | Tool | Floor |
| --- | --- | --- |
| Sheet | `worldsheet_lint.py --province skyrim` | 0 errors; 9 holds plus province, 11 layers |
| Micro-biomes | `region_audit.py` census | >= 6 per hold, >= 2 per km2 (65), 3 transitions per km of road |
| Settlements | `parity.py --province skyrim --tiers` | every settlement at its tier floor; **SET >= 95 in every hold**; `s_npcs` >= 37.5 and `s_bldgs` >= 9.4 per named town (hold floor in 5.2) |
| Plans and people | `town_checks.py`, `population_lint.py` | 0 unstaffed workplaces; unreachable doors <= 1%; F-COURT 100%; trainers 5 and 2 |
| Dungeons | `dungeon_identity_lint.py`, `dungeon_audit.py`, `parity.py` | `d_identity` 1.0; F-LOOP >= 90% (100% marquee); d_marquee >= 1 in every hold; unmarked and power sites as 10.2 |
| Quests | `story_lint.py`, `parity.py` | q/km2 >= 11.2 per hold (target 14.0); conflict chain in every hold; F-STARTS >= 4; `q_placed` >= 0.9; F-HOOK <= 800 |
| Reactivity | F-REACT | 12 deed-keyed lines per hold; every finished chain changes at least 1 |
| Flow | `flow_audit.py --province skyrim` | F-VISTA, F-WEENIE >= 70%, F-DETOUR >= 2, F-SIGN >= 95%, F-TRAVEL p90 <= 500 and max <= 800 and >= 2 modes, F-ENC >= 8 kinds, F-WONDER >= 3, F-QUIET <= 5% day and 20% night |
| Level | `Leveling` data | F-LEVEL band <= 20: **the Reach 8-30 narrows to 9-29**; story quests within the band +/- 8 |
| Text | `novelty_lint.py --strict`, `verbatim_check.py` | 0, 0 |
| Reach | `reach_lint.py` | 0 isolated major sites (Skyrim: 0 today) |
| Floors | `parity.py --province skyrim --assert-floor SET,NPC,QST,DUN,CRE,FLO,STY,LND,LOR,MUS,ITM` | exit 0 |
| Identity | Haiku blind test (G1) | >= 80% of holds named correctly from 3 renders |
| QA | `qa.ps1 -Server -Rebake`, `full_corridors.ps1` | PASS, all zeros |

### 10.4 Phases (SK1-SK8, aligned to R4 S1-S5)

R4's steps (`docs/prompts/R4-skyrim-uplift.md`) are S1 sheets and micro-biomes, S2 settlements, S3 dungeons and
secrets, S4 quests and reactivity, S5 flow and gate. The eight phases below split them into mergeable units; each
phase is one worktree and one merge (`_COMMON.md` section 7). "Owns" is the write set.

| Phase | R4 step | Scope | Owns | Depends | Gate |
| --- | --- | --- | --- | --- | --- |
| SK1 Sheet and micro-biomes | S1 (V1, V3) | `skyrim.json` from section 1 (nine holds plus province); the 76 micro rules of section 2; per-hold weather tables from L2; music cue per hold | `tamriel/world/skyrim.json`, `palettes.json` Skyrim micro rules, `region_spawns/skyrim.json` keys, `tools/qa/region_audit.py` checks | R3a, R3b | `worldsheet_lint` 0; F-MICRO; every rule cites a field; chunk generation within +5% |
| SK2 Map additions and roads | S1 data, V5 bake (serial) | M1-M7 (relabels, Dawnstar cove, Amol, old Snowhawk, coast check, heat inputs), 12 lanes, 2 trails, 3 cross-province ends, stables on four seats | `places/skyrim_*.json`, `tamriel_vectors.json` (cove, coast), `roads/named_roads.json`, `cityplans` stable lots | SK1 | `layout_lint` 0; `roadplan_lint` 0; `reach_lint` 0 isolated; `parity.py` re-run recorded as the new baseline |
| SK3 Capitals | S2 | Five capitals to capital depth: 73+ named people, 27+ interiors, 23+ named buildings, courts, 5 trainers, class gradient, the interiors the sweep lists (Blue Palace, Grey Quarter, thieves' den, Understone, Jorrvaskr) | `cityplans/{whiterun,solitude,windhelm,riften,markarth}.json`, `society/**` (named residents), `lang/skycraft/{society,settlements}.json` | SK2, R3d | `parity.py --tiers` capitals >= 73/27/23/45; F-COURT; `population_lint` 0; `town_checks` unreachable <= 1%; Haiku "not a reskin" |
| SK4 Cities, towns, villages | S2 | Four small seats, the College, Helgen and the towns to tier floors; 14 new plans; Falkreath's promotion and steadings; Orc sets | `cityplans/<14 new ids>`, existing small-seat plans, `society/**` | SK2, R3d (parallel with SK3) | tier table met for all 24 swept settlements and the new ones; `s_bespoke` 100%; `s_npcs` >= 37.5, `s_bldgs` >= 9.4 per town in every hold |
| SK5 Dungeons and secrets | S3 (V4, V7) | 4 marquee plans, 16 unmarked (`hidden: true`), power sites to floor, 12 key relics, caves in 5 holds, forts in the Reach, camps to 27, Amol and old Snowhawk places, hostile mix | `places/skyrim_landmarks.json`, `dungeons/marquee/**`, `dungeons/identity/skyrim.json`, `structure/unique/**`, `region_spawns/skyrim.json` | SK2 (parallel with SK3, SK4, SK6) | `d_identity` 1.0; d_marquee >= 1 per hold; unmarked >= 0.57/km2; F-LOOP; `dungeon_audit` reach 0 blockers; `parity` DUN, CRE >= 95 |
| SK6 Quests and reactivity | S4 (V8) | Nine hold chains, main re-attribution, war flags, faction floors, 9 lore-web arcs plus 4 joins, 12 deed-keyed lines per hold, boards, readables, 9 festivals | `quests/*skyrim*`, `quests/story/skyrim_*`, `lore/places/*skyrim*`, `loreweb_src/skyrim.json`, `calendar/festivals.json` (Skyrim rows), `society/region_voice.json` (Skyrim keys) | SK1, R3d, R3e (parallel with SK3-SK5) | q/km2 >= 11.2 per hold, chain per hold, F-STARTS >= 4, `story_lint` 0, `novelty_lint --strict` 0, `verbatim_check` 0, F-HOOK <= 800, F-REACT |
| SK7 World texture and flow | S5 | >= 8 encounter kinds and 3 wonder moments per hold, wayshrines (29), XL silhouettes, vista and detour fixes, level band, ambience per micro-biome | `creatures/scenes.json` (Skyrim entries), `travel/skyrim.json`, `landmarks/**` XL kinds, `palettes.json` ambience | SK1-SK6 merged | F-ENC, F-WONDER, F-VISTA, F-WEENIE >= 70%, F-DETOUR, F-TRAVEL, F-QUIET (`ecology_report.py`), F-LEVEL |
| SK8 Province gate | S5, V9 | Run every gate of 10.3, the modernisation table (section 9), the four questions, Haiku blind test and contact sheets, the owner's in-game checklist; write the results into section 10 of this file and the WORLD-PLAN state table | none beyond docs | SK1-SK7 | Every gate in 10.3 green; SET >= 95 in all nine holds; `--assert-floor` exit 0 |

**Order.** SK1 first; SK2 next (it is the only phase that rebakes roads, globally serial); then SK3, SK4, SK5 and SK6
as at most three rolling agents; SK7 after they merge; SK8 last. SK2's relabels and promotions change what `parity.py`
counts, so SK1 and SK2 each end with a re-run recorded as the new baseline.

**Open items for the first R4 session.**

1. M1 is a data relabel; if the owner prefers the raster to follow (so biomes also match the game's holds), that is a
   V2 vector edit and serial.
2. `parity.py` counts named NPCs only from quest-file `npcs[]`; the named people of plans, sanctuaries and camps need a
   reader (R3c, WORLD-PLAN O5) before SK3 and SK4 can measure.
3. Whether `darkwater_crossing` and `stonehills` become `town` (the game's own list): it raises the denominator from 18
   to 20 and the named-NPC target by 94. Recommended: keep `village`, apply the town floor.
4. Falkreath's three routes (more named towns and steadings, 130% depth, or both) and the Reach's level band.
5. M5: whether the Winterhold coast is wrong by 120 blocks or only the macro raster is coarse.
