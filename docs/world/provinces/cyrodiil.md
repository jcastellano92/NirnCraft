# Cyrodiil: the Imperial heartland (province plan)

Status: plan, no mod code changed. Governing rules: `docs/world/DESIGN-PRINCIPLES.md` (world building is king, the
eleven-layer sheet, the Skyrim-measured floor). Geometry and scale: `docs/world/SCALE-AND-GEOGRAPHY.md` 5.2, 6 and 7.
Scorecard: `docs/PARITY.md`. Marquee briefs: `docs/MARQUEE.md` (Cyrodiil). Mysteries: `docs/LORE-WEB.md` (cyr_1-4,
threads t_welkynd, t_ash, t_sugar, t_bone, t_falls). Facts came from `.reference/oblivion_ref` (459 markers, 85 POIs,
road graph) and `.reference/arena_ref`; Oblivion's positions win over Arena's.

**Legal line.** Names and relative positions of places, which features existed and how many, and why a design works
are facts. Every description, quest, invented person and line below is ours. No game text, names of game characters,
rasters or assets.

**Notation.** Sheet fields are `layer.id`; province fields carry the prefix `P.` (for example `P.L3.niben`). A field's
causes follow it in square brackets. Region ids drop the `cyrodiil/` prefix. "Floor" is the Skyrim median per km²
times the region's area; "abs." is Skyrim's thinnest hold. Area is NirnCraft km² from `parity.py`.

**Assumed era (owner decision C1).** Cyrodiil is shown in the year after the Oblivion Crisis (end of the Third Era):
the gates are closed but scarred, the Dragonfires are out, there is no emperor and the Elder Council governs. This
is the moment that makes Cyrodiil's sheet strongest (an empty throne, a wounded Kvatch, guild wars) and it sits a few
years after the Vvardenfell and Black Marsh moments those plans assume. If `WORLD-PLAN.md` fixes one present for the
whole continent, only the L9 and L11 rows change; the land, water, economy and settlement layers stand.

**Identity in one line.** Order laid over older magic: paved roads, chapels, law courts and wine estates on top of a
land honeycombed by the white ruins of the elves who once enslaved its people. Never Skyrim: counts, not jarls; the
Nine in marble chapels, not Nordic halls; Ayleid glass-light underground, not draugr barrows; vineyards, oak and heath,
not pine and snow (except the Jerall rim).

---

## 1. World model sheet

### 1.0 Province sheet (`tamriel/world/cyrodiil.json`, block `province`)

**World truth.** Cyrodiil is the Empire's heart: a broad basin of farmland round a great lake, ringed by mountains on
three sides and opening to the sea and swamp in the south. Its people descend from slaves who overthrew the Ayleid
elves, and the elves' white ruins still riddle the ground under every county. Roads radiate from the Imperial City on
its lake island to nine walled cities, each with a chapel to one of the Nine and halls of the great guilds. The
Oblivion Crisis has just ended: Kvatch lies burned, gate scars smoke in the hills, the throne is empty and the Elder
Council, the guilds and the counts all reach for the power that is left.

| Layer | Fields `id` (causes): text |
| --- | --- |
| L1 Geology | `jerall_wall`: granite range along the Skyrim border, one deep notch at the Pale Pass. `valus_ridge`: a second range on the Morrowind border, separate from the Velothi. `colovian_plateau`: broken limestone upland in the west with karst caves and deep stream valleys. `rumare_basin`: a shallow lake basin at the centre whose island carries the oldest stonework in Tamriel. `nibenay_alluvium`: deep river silt east and south of the lake. `gold_coast_cliffs`: low sea cliffs and coves on the Abecean. `topal_mudflats`: mud, peat and mangrove ground where the Niben meets Topal Bay. `ore_highlands`: iron and silver seams in the Jerall and Colovian rock, gold traces in the Valus foothills. `ayleid_foundations`: elven stone, glass and charged light-stones under every county |
| L2 Climate | `temperate_core` (L1.rumare_basin): mild, four-season heartland. `west_dry` (L1.colovian_plateau): Colovia lies in the lee of its own uplands with drier, brighter days and cooler nights. `east_wet` (L1.nibenay_alluvium): the Nibenay is humid and fertile. `jerall_cold` (L1.jerall_wall): snow above 150 blocks and blizzards in winter. `abecean_storms` (L1.gold_coast_cliffs): westerly gales and sea fog on the Gold Coast. `topal_heat` (L1.topal_mudflats): sub-tropical, steamy Blackwood with thunderstorms. `lake_mist` (L1.rumare_basin): morning fog on Lake Rumare and the Niben |
| L3 Water | `lake_rumare` (L1.rumare_basin, L2.temperate_core): the lake at the heart, with one bridge to the capital. `niben` (L1.nibenay_alluvium, L2.east_wet): the great river south from the lake through Bravil to Topal Bay at Leyawiin, braided in its lower course. `tributaries` (L1.valus_ridge, L1.colovian_plateau): the Corbolo from the Valus through Cheydinhal, the Silverfish west of the lake, the Panther in Blackwood, the Strid on the Valenwood border. `ayleid_wells` (L1.ayleid_foundations): about 35 elven wells along the roads that still restore the weary. `who_holds_water` (L1.rumare_basin, L3.lake_rumare, L3.niben): the capital controls the lake crossing; counts toll the river ports; the wells are public and treated as sacred, so no lord may fence one |
| L4 Ecology | `oak_beech_forest` (L2.temperate_core): the Great Forest canopy of oak and beech. `highland_pasture` (L1.colovian_plateau, L2.west_dry): upland pastures with pine and elk. `heath_and_grain` (L3.lake_rumare): open fields round the lake. `vine_cypress` (L1.colovian_plateau, L2.west_dry): warm slopes in the West Weald. `alpine_spruce` (L1.jerall_wall, L2.jerall_cold): northern mountain conifers. `mangrove_swamp` (L1.topal_mudflats, L2.topal_heat): southern coastal swamp. `riverine_reeds` (L3.niben): reedbanks with nirnroot by running water, while wolves, bears, mountain lions, minotaurs, ogres and cave goblins haunt the wilds |
| L5 Economy | `grain_belt` (L3.niben, L4.heath_and_grain): wheat, barley and cattle in the Heartlands and Nibenay. `wine` (L4.vine_cypress): rival estates on the Weald slopes. `timber` (L4.oak_beech_forest): oak from the Great Forest. `herding` (L4.highland_pasture): sheep and horses in Colovia. `mining` (L1.ore_highlands): iron and silver shafts. `river_trade` (L3.niben): barges down the Niben. `sea_trade` (L1.gold_coast_cliffs, L3.niben): Anvil's fleet to the west, Leyawiin's to Black Marsh and Elsweyr. `skooma_smuggling` (L3.niben): contraband carried up the Niben from Elsweyr. `empire_revenue` (L3.lake_rumare, L5.grain_belt): tax, offices and the Arena keep the capital rich |
| L6 Settlement | `nine_cities` (L1.rumare_basin, L3.niben): one capital and eight walled cities, each at a crossing, pass, port or river. `radial_roads` (L3.lake_rumare, L5.empire_revenue): eight named roads from the lake. `inn_chain` (L5.river_trade, L6.radial_roads): a roadside inn every half-day. `busy_centres` (L5.empire_revenue, L5.sea_trade, L6.nine_cities): the Imperial City, Anvil, Chorrol and Leyawiin. `poor_places` (L3.niben, L5.skooma_smuggling): Bravil on stilts, burned Kvatch, and closed villages in the hills |
| L7 Society | `colovian_nibenese_split` (L2.west_dry, L2.east_wet): west is martial, plain and clannish; east is ornate, mercantile, tattooed and mystical. `class_ladder` (L5.empire_revenue, L6.nine_cities): Council families and counts, guild masters and merchants, craftsmen, farm tenants, river labourers, beggars. `rule_of_law` (L6.nine_cities, L6.radial_roads): city watch and Legion, bounties, a fine or a cell for every crime. `guilds_everywhere` (L6.nine_cities): Mages and Fighters halls in every city, the Arcane University in the capital, a thieves' network among the poor, assassins in the dark |
| L8 Belief | `the_nine` (L6.nine_cities): each city's chapel is dedicated to one Divine (Akatosh at Kvatch, Arkay at Cheydinhal, Dibella at Anvil, Julianos at Skingrad, Mara at Bravil, Stendarr at Chorrol, Talos at Bruma, Zenithar at Leyawiin; the Temple of the One in the capital). `wayshrines` (L6.radial_roads): about 30 roadside shrines to the Nine. `daedric_shrines` (L4.oak_beech_forest, L7.colovian_nibenese_split): 15 Daedric statues in the wild, tended by small cults. `doomstones` (L1.ayleid_foundations): about 20 old standing stones that grant a sign's blessing. `elf_ruin_fear` (L1.ayleid_foundations): people avoid the white ruins and tell stories about them. `arena_glory` (L5.empire_revenue): blood sport as civic pride |
| L9 Politics | `throne_empty` (L6.nine_cities, L7.class_ladder): no emperor, the Dragonfires dark, the Elder Council and its chancellor rule. `gate_scars` (L6.poor_places, L8.daedric_shrines): dozens of closed gates leave burned rings that still leak. `county_rivalry` (L7.colovian_nibenese_split, L7.class_ladder): counts test how far the Council can reach. `guild_wars` (L7.guilds_everywhere): the Mages Guild against the necromancers; the Fighters Guild against a rival mercenary company based in Leyawiin. `border_pressure` (L5.skooma_smuggling, L6.nine_cities): skooma from Elsweyr, a newly confident Black Marsh, Dunmer settlers and their own politics in the east |
| L10 Hidden | `ayleid_undercity` (L1.ayleid_foundations, L8.elf_ruin_fear): 50-odd Ayleid ruins and a pre-Imperial reservoir under the cities, home to undead, necromancers, conjurers and treasure hunters. `cult_remnants` (L1.rumare_basin, L9.gate_scars): the doomsday cult's surviving cells hide in lake caves and old forts. `abandoned_forts` (L1.colovian_plateau, L9.county_rivalry): about 50 ruined forts from older wars taken by bandits, marauders and vampires. `road_bandits` (L4.oak_beech_forest, L6.radial_roads): highwaymen on the long legs far from city watches. `assassins_and_thieves` (L7.guilds_everywhere, L9.county_rivalry): a sanctuary under a quiet house in Cheydinhal, a guild among the Waterfront shacks |
| L11 History | `ayleid_era` (L1.ayleid_foundations, L10.ayleid_undercity): the elves built White-Gold Tower and ruled the heartland with human slaves. `alessian_revolt` (L1.rumare_basin, L8.the_nine): the slaves rose, took the tower and founded the Empire; the Alessian scroll-temples and gates are its relics. `reman_akaviri` (L1.jerall_wall, L6.nine_cities): the Reman emperors and their Akaviri guard left Sancre Tor, the Pale Pass fort and Cloud Ruler. `three_banner_war` (L10.abandoned_forts): keeps and forts from a Second Era war of three alliances for the Ruby Throne. `septim_empire` (L6.radial_roads, L8.the_nine): the roads, chapels and Legion. `oblivion_crisis` (L9.throne_empty, L9.gate_scars): the emperor murdered, gates opened, Kvatch destroyed, and the last heir's sacrifice ended it |

### 1.2 Jerall Mountains (`jerall_mountains`, 9.5 km², seat Bruma)

**World truth.** The Jerall Mountains form the cold northern rim where Cyrodiil meets Skyrim. Bruma is half Nord in
blood and timber-and-stone building, a snow-roofed city guarding the only good pass, its Great Chapel of Talos raising
the tallest spire in the county. Above it the Blades' cliff fortress of Cloud Ruler Temple watches the road, and to the
west the dead Reman citadel of Sancre Tor broods on its peak. Life turns on herding, silver mining, pass trade and
smuggling. The mountains hold more caves and old Imperial family crypts than any county, and the cold keeps the dead in
them restless.

| Layer | Fields |
| --- | --- |
| L1 | `pale_pass_notch`: the deep northern pass through the granite wall. `granite_caves`: high ice-rimmed caverns. `silver_seams`: rich mountain silver veins |
| L2 | `snow_above_150` (cy.L2.jerall_cold, L1.pale_pass_notch): permanent snowpack on the high ridges. `blizzard_winters` (cy.L2.jerall_cold, L1.pale_pass_notch): winter whiteouts closing the pass. `valley_inversion` (L1.granite_caves): clear peaks over fogged valleys |
| L3 | `snowmelt_streams` (cy.L3.niben, L2.snow_above_150): cold torrents feeding the Upper Niben and Lake Rumare. `bruma_cisterns` (L1.pale_pass_notch, L2.blizzard_winters): heated meltwater cisterns behind the city walls |
| L4 | `alpine_spruce` (cy.L4.alpine_spruce, L2.snow_above_150): snow-laden spruce. `scree_heath` (L1.pale_pass_notch, L2.snow_above_150): wind-scoured rock. `snowfield` (L2.blizzard_winters): high snow wastes with frost trolls and snow wolves. `hot_spring_hollow` (L1.granite_caves): steamed fault hollows |
| L5 | `pass_trade` (L1.pale_pass_notch): wagon caravans to and from Skyrim. `silver_mines` (L1.silver_seams): mountain shafts and stamp mills. `goat_and_fur` (L4.alpine_spruce): pelts and mountain cheeses. `smuggling` (L1.pale_pass_notch, L2.valley_inversion): untaxed goods carried round the toll post |
| L6 | `bruma_on_the_pass` (L1.pale_pass_notch, L5.pass_trade): the Nord-Imperial seat overlooking the highway. `foothill_villages` (cy.L6.radial_roads, L3.snowmelt_streams): waystations on the Silver and Orange roads. `mine_hamlets` (L5.silver_mines): bunkhouse camps at the shaft heads |
| L7 | `nord_imperial_mix` (L6.bruma_on_the_pass): Nord clans and Imperial magistrates sharing the watch. `miner_debt` (L5.silver_mines): company tallymen at the silver shafts |
| L8 | `talos_first` (cy.L8.the_nine, L6.bruma_on_the_pass): the Great Chapel of Talos and Nord hearth-shrines. `dragon_blood_lore` (L6.bruma_on_the_pass): Akaviri oaths kept by the Blades at Cloud Ruler |
| L9 | `pass_tolls` (cy.L9.county_rivalry, L5.pass_trade): Bruma's count and the Legion quarrelling over pass customs. `blades_without_emperor` (cy.L9.throne_empty, L8.dragon_blood_lore): the ancient guard waiting on its cliff without a Dragonborn |
| L10 | `family_crypts` (L1.granite_caves, L2.snow_above_150): ancestral Imperial crypts where the cold keeps the dead walking. `sancre_tor_barrow` (L1.pale_pass_notch, L8.dragon_blood_lore): the haunted tomb-fortress of Reman. `mage_on_the_crag` (L1.pale_pass_notch, L4.snowfield): Frostcrag Spire on its icy peak |
| L11 | `reman_fortresses` (cy.L11.reman_akaviri, L10.sancre_tor_barrow): the Pale Pass fort and Sancre Tor. `pass_battles` (cy.L11.reman_akaviri, L1.pale_pass_notch): the Akaviri surrender at the pass. `gate_at_bruma` (cy.L11.oblivion_crisis, L6.bruma_on_the_pass): the Great Gate fought before Bruma's walls |

### 1.3 Colovian Highlands (`colovian_highlands`, 9.3 km², seat Chorrol)

**World truth.** The Colovian Highlands are a proud, dry upland of sheep downs, horse paddocks and broken limestone
valleys running west to the High Rock and Hammerfell borders. Chorrol grew round a giant oak at the head of the Black
Road and serves as the highland's market and the Fighters Guild's provincial seat. The people are plain-spoken
Colovians who distrust the capital's ornate politics. Its karst caves hide warring goblin tribes, bandits and closed
hill villages with secrets of their own, while the stone keeps of a Second Era war stand on every ridge.

| Layer | Fields |
| --- | --- |
| L1 | `karst_valleys`: incised limestone valleys and sinkholes. `limestone_caves`: deep cavern systems. `iron_seams`: upland iron ore veins |
| L2 | `dry_bright_summers` (cy.L2.west_dry, L1.karst_valleys): sunny upland summers. `cold_clear_nights` (cy.L2.west_dry, L1.karst_valleys): crisp night air. `upland_wind` (L1.karst_valleys): steady westerly breezes |
| L3 | `sinking_streams` (L1.karst_valleys): rivers that vanish into karst and return as cold springs. `spring_villages` (L1.karst_valleys, L3.sinking_streams): upland water-points |
| L4 | `sheep_downs` (cy.L4.highland_pasture, L2.dry_bright_summers): open limestone turf. `pine_ridges` (L1.karst_valleys, L2.upland_wind): dry pine crests. `karst_scrub` (L1.karst_valleys): rocky brush with wolves, bears and ogres. `spring_glen` (L3.sinking_streams): green watered hollows |
| L5 | `wool_and_horses` (L4.sheep_downs): Colovian wool, mutton and cavalry mounts. `iron_mines` (L1.iron_seams): upland iron shafts and smithies. `chorrol_market` (L4.sheep_downs, L5.iron_mines): the western guild and livestock exchange |
| L6 | `chorrol_crossroads` (cy.L6.radial_roads, L5.chorrol_market): the walled seat at the Black Road's end. `spring_hamlets` (L3.spring_villages): stone pastoral villages. `closed_villages` (L1.karst_valleys): isolated side-valley hamlets such as Hackdirt |
| L7 | `colovian_pride` (cy.L7.colovian_nibenese_split, L6.chorrol_crossroads): martial, plain-spoken highland families. `guild_town` (cy.L7.guilds_everywhere, L6.chorrol_crossroads): the Fighters Guild headquarters |
| L8 | `stendarr_chapel` (cy.L8.the_nine, L6.chorrol_crossroads): the Great Chapel of Stendarr. `oak_reverence` (L6.chorrol_crossroads): civic pride in the Great Oak and Weynon Priory's monks |
| L9 | `guild_rivalry` (cy.L9.guild_wars, L7.guild_town): the Fighters Guild losing contracts to the Blackwood Company. `herder_logger_feud` (L5.wool_and_horses, L7.colovian_pride): boundary disputes with Great Forest timber camps |
| L10 | `goblin_wars` (L1.limestone_caves, L4.karst_scrub): totem-tribes fighting each other for caves. `deep_ones_below` (L1.limestone_caves, L6.closed_villages): a hill village hiding what lives in its tunnels. `ridge_keeps` (cy.L10.abandoned_forts, L1.karst_valleys): ruined forts held by marauders |
| L11 | `high_rock_gates` (cy.L11.three_banner_war, L10.ridge_keeps): border keeps on the northern road. `alessian_scroll_temples` (cy.L11.alessian_revolt, L1.karst_valleys): the ancient temples of Alma Ruma and Ni-Mohk. `battlehorn` (cy.L11.septim_empire, L1.karst_valleys): the knightly castle on the western crag |

### 1.4 Heartlands (`heartlands`, 7.7 km², capital the Imperial City)

**World truth.** The Heartlands are the lake country round the capital. The Imperial City stands on its central island,
a wheel of six walled wards around the Ayleids' White-Gold Tower, reached by one monumental bridge. Around the shore
run the Red Ring Road, rich grain farms, fishing villages and the first coaching inns of every highway. Wealth and power
flow through here, and so does every rumour in Tamriel. Under the city run the sewers and pre-Imperial vaults; under
the ploughed fields lie Ayleid ruins. The Elder Council's quarrels, the Arena's crowds and the Waterfront's thieves make
this the busiest and most watched county.

| Layer | Fields |
| --- | --- |
| L1 | `island_bedrock`: the central lake-island stone platform. `lakeshore_clay`: fertile basin clays. `ayleid_footings`: ancient white-stone foundations beneath city and shore |
| L2 | `mild_lake_climate` (cy.L2.temperate_core, L1.lakeshore_clay): even, mild seasons moderated by the water. `lake_mist` (cy.L2.lake_mist, L1.lakeshore_clay): dawn mists over the bridges and reeds |
| L3 | `rumare_shore` (cy.L3.lake_rumare, L1.lakeshore_clay): the ring-lake waters. `city_sewers_and_cisterns` (cy.L3.who_holds_water, L1.island_bedrock): the Imperial Watch controls the city's water and the brick ways beneath |
| L4 | `lake_reedbed` (L3.rumare_shore): reed beds with mudcrabs and slaughterfish. `heath_and_grain` (cy.L4.heath_and_grain, L1.lakeshore_clay): ploughed wheat fields. `lakeshore_oakwood` (L2.mild_lake_climate): parklands and deer groves. `urban_garden` (L3.city_sewers_and_cisterns): the Arboretum and palace gardens |
| L5 | `capital_economy` (cy.L5.empire_revenue, L3.rumare_shore): Imperial offices, the Arena, and the Market District. `lakeside_grain_and_fish` (L3.rumare_shore, L4.heath_and_grain): bread, ale and lake eels feeding the wards |
| L6 | `imperial_city` (cy.L6.busy_centres, L1.island_bedrock): the wheel-city around White-Gold Tower. `bridgehead_villages` (L3.rumare_shore): Weye and the bridgehead docks. `red_ring_villages` (cy.L6.inn_chain, L4.heath_and_grain): Pell's Gate, Aleswell and Roxey Inn |
| L7 | `ward_ladder` (cy.L7.class_ladder, L6.imperial_city): Elven Gardens and Talos Plaza at the top, Market and Temple in the middle, the Waterfront shacks at the bottom. `beggar_eyes` (L6.imperial_city, L7.ward_ladder): beggars watching every street for the Gray Fox |
| L8 | `temple_of_the_one` (cy.L8.the_nine, L6.imperial_city): the Dragonfires shrine where emperors were crowned. `arena_cult` (cy.L8.arena_glory, L5.capital_economy): blood-sport celebrity. `university_rationalism` (cy.L7.guilds_everywhere, L6.imperial_city): the Arcane University's orthodoxy |
| L9 | `council_factions` (cy.L9.throne_empty, L7.ward_ladder): Colovian and Nibenese blocs vying in the Elder Council. `watch_vs_guild` (L7.beggar_eyes): Captain Hieronymus Lex hunting the Thieves Guild on the Waterfront |
| L10 | `ring_beneath_the_rings` (L1.ayleid_footings, L3.city_sewers_and_cisterns): pre-Imperial reservoir and Ayleid vaults under every ward. `prison_tunnels` (L1.island_bedrock, L3.city_sewers_and_cisterns): the escape ways beneath the Bastion. `lake_cult_caves` (cy.L10.cult_remnants, L1.lakeshore_clay, L9.council_factions): Mythic Dawn cells in the shore caverns |
| L11 | `white_gold_tower` (cy.L11.ayleid_era, L1.ayleid_footings): the Merethic tower at the centre of the wheel. `alessian_bridge` (cy.L11.alessian_revolt, L6.imperial_city): the bridge of the slave-revolt triumph. `throne_room_murder` (cy.L11.oblivion_crisis, L8.temple_of_the_one): the lighting of the last Dragonfire |

### 1.5 Great Forest (`great_forest`, 3.8 km², Red Ring to Chorrol)

**World truth.** The Great Forest is the old oak and beech woodland west and north-west of Lake Rumare, the densest
ground for ancient ruins in Cyrodiil with more caves and Ayleid sites per mile than any other county. Few people live
under its boughs, and those who do are loggers, charcoal-burners, priory monks and hunters who keep close to the roads.
Minotaurs and wild beasts roam the deep glades, necromancers fortify the elven vaults, and roadside Daedric statues draw
secret cultists. It is Cyrodiil's wild middle: golden-green by day, avoided at night, full of things the capital would
rather forget.

| Layer | Fields |
| --- | --- |
| L1 | `forest_loam_hills`: rolling hills of deep forest loam. `ruin_dense_ridge`: limestone ridges riddled with Ayleid vaults |
| L2 | `canopy_shade` (cy.L2.temperate_core, L1.forest_loam_hills): cool, dappled light under the high boughs. `forest_fog` (cy.L2.lake_mist, L1.forest_loam_hills): thick autumn and morning mists |
| L3 | `forest_brooks` (cy.L3.tributaries, L1.forest_loam_hills): clear pebble streams under alder banks. `silverfish_headwaters` (cy.L3.tributaries, L1.ruin_dense_ridge): springs feeding the lake |
| L4 | `old_oakwood` (cy.L4.oak_beech_forest, L2.canopy_shade): ancient oaks, minotaurs and bears. `beech_hanger` (L1.ruin_dense_ridge, L2.canopy_shade): steep beech slopes. `fern_glade` (L3.forest_brooks): deer and wisps. `charcoal_clearing` (L1.forest_loam_hills): smoky coppiced rings. `unicorn_grove` (L1.forest_loam_hills, L3.forest_brooks): a warded glade where a last unicorn walks |
| L5 | `timber` (cy.L5.timber, L4.old_oakwood): oak felling for the capital. `charcoal` (L4.charcoal_clearing): kiln-burning for city smithies. `hunting` (L4.fern_glade): venison and boar pelts |
| L6 | `road_hamlets` (cy.L6.radial_roads, L5.timber): Wawnet Inn and logging hamlets on the forest edge. `priory` (cy.L6.radial_roads, L3.forest_brooks): Weynon Priory on the Black Road |
| L7 | `woodfolk` (cy.L7.class_ladder, L5.charcoal): tenants, woodcutters and charcoal-burners. `outlaw_refuge` (L4.old_oakwood, L6.road_hamlets): highwaymen hiding two hundred paces off the road |
| L8 | `forest_daedric_shrines` (cy.L8.daedric_shrines, L4.old_oakwood): hidden woodland statues tended by hedge-cults. `grove_spirits` (L4.unicorn_grove): old forester taboos against hunting the warded glades |
| L9 | `necromancer_war_front` (cy.L9.guild_wars, L8.forest_daedric_shrines): Mages Guild battlemages clashing with worm-cults in the forest ruins |
| L10 | `lich_ruins` (cy.L10.ayleid_undercity, L1.ruin_dense_ridge, L9.necromancer_war_front): Vindasel, Fanacasecul, Nornalhorst, Silorn and Ceyatatar. `minotaur_lairs` (L1.ruin_dense_ridge, L4.old_oakwood): horn-carved grove dens |
| L11 | `ayleid_heartwood` (cy.L11.ayleid_era, L10.lich_ruins): the rival Ayleid city-states of the woodland. `goblin_war_of_old` (cy.L11.three_banner_war, L1.ruin_dense_ridge): abandoned siege-forts under the oaks |

### 1.6 Nibenay Basin (`nibenay_basin`, 8.5 km², seat Cheydinhal)

**World truth.** The Nibenay Basin is the fertile eastern valley between the Niben and the Valus Mountains, where
Nibenese tattoo-art, silk and spice markets, and mystic orders meet Dunmer settlers from across the border passes.
Cheydinhal straddles the Corbolo River under willow trees, with a Dunmer count in its castle and the Dark Brotherhood's
sanctuary hidden beneath an abandoned house. Farms and orchards fill the plain, the Knights of the Thorn play at
chivalry, and border keeps watch the Morrowind road while surviving Mythic Dawn cells lurk in the Lake Arrius caverns.

| Layer | Fields |
| --- | --- |
| L1 | `valus_foothills`: steep eastern ridges rising to the Morrowind border. `basin_silt`: rich alluvial soils. `foothill_gold`: gold-bearing quartz seams in the eastern hills |
| L2 | `humid_east` (cy.L2.east_wet, L1.basin_silt): warm, humid growing seasons. `valus_rain` (cy.L2.east_wet, L1.valus_foothills): heavy orographic rains off the Valus |
| L3 | `corbolo` (cy.L3.tributaries, L2.valus_rain): the willow-lined river through Cheydinhal. `lake_arrius` (L1.valus_foothills, L2.valus_rain): a crater lake in the northern hills. `mill_weirs` (L1.basin_silt, L3.corbolo): stone weirs powering the basin's mills |
| L4 | `orchard_plain` (L1.basin_silt, L2.humid_east): apple and pear orchards and dye-flax fields. `wet_meadow` (L3.corbolo): lush river meadows. `valus_pine` (L1.valus_foothills): mountain pine with bears and trolls. `lake_arrius_reeds` (L3.lake_arrius): reed-ringed caldera shore |
| L5 | `orchards_and_grain` (L1.basin_silt, L4.orchard_plain): fruit, grain and Nibenese dye-flax. `corbolo_mills` (L3.mill_weirs): watermills and cloth fulling. `morrowind_trade` (L1.valus_foothills): pass caravans trading Ebonheart goods |
| L6 | `cheydinhal_on_the_corbolo` (L3.corbolo, L5.morrowind_trade): the half-timbered willow city on two banks. `blue_road_villages` (cy.L6.radial_roads, L5.orchards_and_grain): Cropsford and Harlun's Watch. `border_keeps` (L1.valus_foothills, L5.morrowind_trade): watch-forts on the eastern passes |
| L7 | `dunmer_quarter` (L5.morrowind_trade, L6.cheydinhal_on_the_corbolo): Hlaalu and Redoran emigres in Cheydinhal. `nibenese_gentry` (cy.L7.colovian_nibenese_split, L5.orchards_and_grain): silk-robed landowners. `knightly_dilettantes` (L6.cheydinhal_on_the_corbolo, L7.nibenese_gentry): the Count's son and his Knights of the Thorn lodge |
| L8 | `arkay_chapel` (cy.L8.the_nine, L6.cheydinhal_on_the_corbolo): the Great Chapel of Arkay. `dunmer_ancestor_rites` (L7.dunmer_quarter): household ancestor ash-shrines kept quietly beside the chapel |
| L9 | `dunmer_count` (L7.dunmer_quarter, L7.nibenese_gentry): Count Indarys balancing Imperial courtiers and Dunmer kin. `market_quarrel` (L5.morrowind_trade, L7.nibenese_gentry): guild friction over eastern caravan tariffs |
| L10 | `sanctuary_under_the_house` (cy.L10.assassins_and_thieves, L1.basin_silt, L6.cheydinhal_on_the_corbolo): the Black Door beneath the abandoned eastern house. `arrius_cult_cells` (cy.L10.cult_remnants, L1.valus_foothills, L3.lake_arrius): the Mythic Dawn shrine in Lake Arrius Caverns. `valus_keeps` (cy.L10.abandoned_forts, L1.valus_foothills): bandit-held mountain forts |
| L11 | `alessian_gates_chim_ghartok` (cy.L11.alessian_revolt, L1.basin_silt): ancient Alessian triumphal arches. `border_wars_with_morrowind` (cy.L11.reman_akaviri, L6.border_keeps): the Reman and Septim campaigns over the Valus passes |

### 1.7 West Weald (`west_weald`, 4.1 km², seat Skingrad)

**World truth.** The West Weald is warm, sheltered vineyard country south-west of the Great Forest, straddling the Gold
Road between the capital and the Abecean coast. Skingrad sits on its limestone hill between two sheer castle bridges,
its vintner houses locked in a generational feud over whose cellar presses the finest vintage. Count Hassildor is
almost never seen by daylight, and the townsfolk prefer not to ask why. Ayleid vaults—Miscarcand chief among them—lie
under the vines, and the Priory of the Nine stands in the southern hills. It is golden and wealthy, with cold things in
its cellars.

| Layer | Fields |
| --- | --- |
| L1 | `warm_limestone_slopes`: south-facing limestone hills and dry gullies. `deep_ayleid_vault`: the subterranean white-stone halls of Miscarcand |
| L2 | `sheltered_warmth` (cy.L2.west_dry, L1.warm_limestone_slopes): long, sunny ripening seasons. `harvest_haze` (cy.L2.west_dry, L1.warm_limestone_slopes): golden autumn mist in the valleys |
| L3 | `strid_headwaters` (cy.L3.tributaries, L1.warm_limestone_slopes): upland streams running south toward Valenwood. `estate_cisterns` (cy.L3.who_holds_water, L1.warm_limestone_slopes): vintner estates hold the hill springs and ration water in dry years |
| L4 | `vine_terrace` (cy.L4.vine_cypress, L2.sheltered_warmth): terraced grape vines on stone walls. `cypress_line` (L1.warm_limestone_slopes, L2.sheltered_warmth): cypress avenues along estate roads. `oak_savanna` (L2.sheltered_warmth): open sunny pasture. `orchard` (L3.estate_cisterns): tomato gardens and fruit trees |
| L5 | `wine_estates` (cy.L5.wine, L4.vine_terrace): the Surilie and Tamika vineyards. `tomato_and_grain` (L4.orchard): Skingrad's prized produce and sheep cheeses. `gold_road_traffic` (cy.L6.radial_roads, L1.warm_limestone_slopes): merchant wagons between the capital and Anvil |
| L6 | `skingrad_hill` (L1.warm_limestone_slopes, L5.gold_road_traffic): the twin-walled city bridged by its castle ravine. `estate_hamlets` (L5.wine_estates): vintner villas and press-houses. `priory_of_the_nine` (L3.strid_headwaters): the knightly priory in the southern woods |
| L7 | `vintner_aristocracy` (cy.L7.class_ladder, L5.wine_estates): rival wine dynasties. `field_hands` (L5.wine_estates): seasonal pickers and cellar coopers |
| L8 | `julianos_chapel` (cy.L8.the_nine, L6.skingrad_hill): the Great Chapel of Julianos. `crusader_relic_cult` (cy.L8.the_nine, L6.priory_of_the_nine): pilgrims seeking the Knights of the Nine |
| L9 | `wine_feud` (L5.wine_estates, L7.vintner_aristocracy): sabotage and price wars between the two great cellars. `hidden_count` (L6.skingrad_hill, L7.vintner_aristocracy): the town shielding its nocturnal count from outsiders' questions |
| L10 | `vampire_count` (L1.warm_limestone_slopes, L9.hidden_count): the secret pact between Castle Skingrad and the county's bloodlines. `name_vault_under_the_vines` (cy.L10.ayleid_undercity, L1.deep_ayleid_vault): Miscarcand and its Great Welkynd Stone |
| L11 | `knightly_order_of_the_nine` (cy.L11.septim_empire, L8.crusader_relic_cult): the founding and fall of the Knights of the Nine. `ayleid_name_vaults` (cy.L11.ayleid_era, L10.name_vault_under_the_vines): the Ayleid kings buried under the vineyards |

### 1.8 Gold Coast (`gold_coast`, 5.6 km², seats Anvil and Kvatch)

**World truth.** The Gold Coast is Cyrodiil's western shore on the Abecean Sea and the dry hills behind it. Anvil is a
bright harbour of white stone walls and blue roofs, its fleet and merchant houses rich on western sea trade. Up the
Gold Road, Kvatch stood on its commanding hill until a Great Gate opened beneath it in the Oblivion Crisis: the city
burned and its survivors camp below the walls while rebuilding begins. Between them run the longest leg of the Gold
Road, smugglers' sea-caves, wreckers, a haunted manor and vampire lairs that prey on lonely travellers.

| Layer | Fields |
| --- | --- |
| L1 | `low_sea_cliffs`: pale coastal bluffs and sandy coves. `sea_caves`: tidal caverns under the headlands. `kvatch_hill`: a steep 60-block plateau commanding the Gold Road |
| L2 | `sea_fog` (cy.L2.abecean_storms, L1.low_sea_cliffs): salt mists rolling into the harbour. `westerly_gales` (cy.L2.abecean_storms, L1.low_sea_cliffs): Abecean squalls driving ships onto the rocks |
| L3 | `harbour_springs` (L1.low_sea_cliffs): fresh springs feeding Anvil's docks and fountain. `kvatch_wells` (L1.kvatch_hill): rock-cut hilltop wells fouled by ash during the burning |
| L4 | `coastal_scrub` (L1.low_sea_cliffs, L2.westerly_gales): wind-bent olive and scrub. `cliff_grass` (L1.low_sea_cliffs): golden coastal downs with mountain lions. `cove_beach` (L1.low_sea_cliffs): land dreugh and mudcrabs on the sand. `burned_slope` (L1.kvatch_hill): scorched earth round Kvatch. `strid_riverine` (cy.L3.tributaries, L1.low_sea_cliffs): the southern border gorge |
| L5 | `anvil_shipping` (cy.L5.sea_trade, L3.harbour_springs): ocean trade with Hammerfell, Summerset and Yokuda. `fishing` (L4.cove_beach): coastal trawlers and wharves. `rebuild_trade` (L1.kvatch_hill, L4.burned_slope): masons, timber and stone hauled up to Kvatch |
| L6 | `anvil_harbour` (L3.harbour_springs, L5.anvil_shipping): the white-walled port city and its great lighthouse. `kvatch_on_the_hill` (L1.kvatch_hill, L4.burned_slope): the ruined hilltop seat under scaffolding. `gold_road_inns` (cy.L6.inn_chain, L5.anvil_shipping): Brina Cross and Gottshaw. `refugee_camp` (L1.kvatch_hill, L5.rebuild_trade): the tent town at the foot of Kvatch's road |
| L7 | `merchant_wealth` (L5.anvil_shipping, L6.anvil_harbour): shipping magnates and guild factors. `dock_labour` (L5.fishing, L6.anvil_harbour): sailors and stevedores on the quay. `kvatch_refugees` (L6.refugee_camp): displaced citizens led by Savlian Matius |
| L8 | `dibella_chapel` (cy.L8.the_nine, L6.anvil_harbour): the Great Chapel of Dibella in Anvil. `akatosh_chapel` (cy.L8.the_nine, L6.kvatch_on_the_hill): the surviving dome of Akatosh serving as Kvatch's field hospital and shrine |
| L9 | `kvatch_rebuild` (cy.L9.throne_empty, L7.kvatch_refugees): disputes over who funds the rebuilding and who holds the county signet. `wreckers` (L2.westerly_gales, L5.anvil_shipping): coastal gangs trying to douse Anvil's lighthouse |
| L10 | `smuggler_coves` (L1.sea_caves, L9.wreckers): Dunbarrow Cove and pirate grottos under the cliffs. `haunted_manor` (L1.low_sea_cliffs, L6.anvil_harbour): Benirus Manor over its sealed lich-crypt. `gate_remnant_under_kvatch` (cy.L9.gate_scars, L1.kvatch_hill, L4.burned_slope): smoking Daedric fissures beneath the burned plaza |
| L11 | `kvatch_burned` (cy.L11.oblivion_crisis, L10.gate_remnant_under_kvatch): the night the Great Gate broke the city. `old_pirate_ages` (cy.L11.three_banner_war, L10.smuggler_coves): the Gold Coast pirate kings and Red Sabre fleets |

### 1.9 Nibenay Valley (`nibenay_valley`, 8.2 km², seat Bravil)

**World truth.** The Nibenay Valley is the flat, wet and poor river country of the Lower Niben. Bravil is a crowded
city of weathered plank houses built over mud canals and timber stilts beside the river, the poorest of the nine seats
and the northern funnel for skooma smuggled up from Elsweyr. Its people trust luck more than the Watch and rub the
statue of their Lucky Old Lady in the square for coin. The valley's riverbank caves and drowned Ayleid ruins shelter
smugglers, necromancers and the sealed crypt where the Dark Brotherhood keeps its Night Mother.

| Layer | Fields |
| --- | --- |
| L1 | `river_flats`: broad, low-gradient silt floodplains. `riverbank_caves`: wet mudstone and limestone caves cut by the river |
| L2 | `humid_heat` (cy.L2.east_wet, L1.river_flats): oppressive summer humidity. `river_fog` (cy.L2.lake_mist, L1.river_flats): dense fog blanketing the braided channels |
| L3 | `lower_niben_braids` (cy.L3.niben, L1.river_flats): wide river channels and shifting islands. `flood_meadows` (L1.river_flats, L3.lower_niben_braids): seasonally inundated banks. `bravil_mud` (L1.river_flats, cy.L3.who_holds_water, L3.lower_niben_braids): the city has no clean well, so the poor buy drinking water by the bucket |
| L4 | `reed_marsh` (cy.L4.riverine_reeds, L3.lower_niben_braids): tall reeds, nirnroot, mudcrabs and slaughterfish. `flood_meadow` (L3.flood_meadows): wet sedge and willows. `willow_carr` (L2.river_fog, L3.lower_niben_braids): tangled river woods. `river_island` (L3.lower_niben_braids): wooded islets in the Niben |
| L5 | `river_labour` (cy.L5.river_trade, L3.lower_niben_braids): barge hauling, timber rafting and ferrying. `skooma` (cy.L5.skooma_smuggling, L3.lower_niben_braids): illicit moon-sugar and skooma traffic. `eel_and_reed` (L4.reed_marsh): eel traps and reed thatch |
| L6 | `bravil_ferry_city` (L3.lower_niben_braids, L5.river_labour): plank-and-stilt tenements crowded along dirty canals. `green_road_inns` (cy.L6.inn_chain, L1.river_flats): Faregyl and the Inn of Ill Omen along the highway |
| L7 | `slum_city` (cy.L7.class_ladder, L5.river_labour, L6.bravil_ferry_city): impoverished dockhands, Khajiit and Argonian barge-crews. `addiction` (L5.skooma, L7.slum_city): skooma dens emptying pockets in the lower wards |
| L8 | `mara_chapel` (cy.L8.the_nine, L6.bravil_ferry_city): the Great Chapel of Mara. `lucky_old_lady` (L6.bravil_ferry_city, L7.slum_city): the plaza statue rubbed for luck. `night_mother_crypt` (L6.bravil_ferry_city, L8.lucky_old_lady): the secret vault beneath the statue |
| L9 | `corrupt_clerk` (L5.skooma, L7.addiction): harbour inspectors bribed to look away from night barges. `elsweyr_gates` (cy.L9.border_pressure, L5.skooma): smuggling trails across the Rimmen and Torval hills |
| L10 | `skooma_dens` (L1.river_flats, L7.addiction, L9.corrupt_clerk): hidden boil-cellars and canal caches. `smuggler_caves` (L1.riverbank_caves, L9.corrupt_clerk): river-mouth caves with boat slips. `assassins_mother_crypt` (cy.L10.assassins_and_thieves, L1.river_flats, L8.night_mother_crypt): the Listener's sanctum under the Lucky Old Lady |
| L11 | `alessian_gates_altadoon_mnem` (cy.L11.alessian_revolt, L1.river_flats): weathered Alessian stone gates in the reeds. `river_wars` (cy.L11.septim_empire, L3.lower_niben_braids): toll-chain wars between Bravil and Leyawiin |

### 1.10 Blackwood (`blackwood`, 4.5 km², seat Leyawiin)

**World truth.** Blackwood is hot, steaming swamp-forest where the Lower Niben meets Topal Bay on Cyrodiil's southern
border with Black Marsh and Elsweyr. Leyawiin is a walled Imperial river-mouth city with Argonian and Khajiit shanties
crowded outside its painted streets, and the home of the Blackwood Company, a mercenary outfit that has grown rich
undercutting the Fighters Guild. Rumour says the company's men drink sap stolen from a marsh tree. Ayleid ruins sink
into the peat, vampire covens and necromancers hide in the mangroves, and the border forts face an assertive Black
Marsh.

| Layer | Fields |
| --- | --- |
| L1 | `mangrove_mud`: coastal mudflats and brackish silt. `peat`: deep waterlogged peat bogs. `sinking_ruins`: Ayleid stone foundations subsiding into the mire |
| L2 | `steam_heat` (cy.L2.topal_heat, L1.mangrove_mud): year-round sub-tropical heat and mist. `thunderstorms` (cy.L2.topal_heat, L1.peat): daily afternoon downpours off Topal Bay |
| L3 | `niben_mouth` (cy.L3.niben, L1.mangrove_mud): the wide estuary at Leyawiin. `panther_river` (cy.L3.tributaries, L1.peat): dark eastern swamp river full of wrecks. `brackish_tide` (L1.mangrove_mud, L3.niben_mouth): salt tides pushing into the bayous |
| L4 | `mangrove` (cy.L4.mangrove_swamp, L3.brackish_tide): stilt-root mangroves and land dreugh. `cypress_swamp` (L1.peat, L2.steam_heat): moss-hung cypress, giant snakes and wisps. `palm_hammock` (L1.mangrove_mud, L2.steam_heat): dry hummocks in the bog. `brackish_flat` (L3.brackish_tide): mudcrabs and marsh reeds |
| L5 | `river_mouth_port` (cy.L5.sea_trade, L3.niben_mouth): Leyawiin's customs quay and coastal shipping. `mercenary_trade` (L3.niben_mouth, L5.river_mouth_port): contract arms and bounty gold. `rice_and_reed` (L1.peat, L4.brackish_flat): wet-field rice and marsh herbs |
| L6 | `leyawiin_at_the_mouth` (L3.niben_mouth, L5.river_mouth_port): the brightly plastered river-fortress seat. `border_villages` (L1.peat, L4.palm_hammock): Water's Edge, Border Watch and Blankenmarch |
| L7 | `imperial_core_vs_beastfolk_shanties` (cy.L7.class_ladder, L6.leyawiin_at_the_mouth): noble manors inside the walls, Argonian and Khajiit quarters in the damp lanes. `khajiit_border_village` (L6.border_villages): Border Watch on the western ridge |
| L8 | `zenithar_chapel` (cy.L8.the_nine, L6.leyawiin_at_the_mouth): the Great Chapel of Zenithar. `argonian_hist_reverence` (L4.cypress_swamp, L7.imperial_core_vs_beastfolk_shanties): quiet Hist sap-veneration in the marsh villages |
| L9 | `mercenary_company` (cy.L9.guild_wars, L5.mercenary_trade): the Blackwood Company rivalling the Fighters Guild. `marsh_border` (cy.L9.border_pressure, L6.border_villages): frontier forts facing An-Xileel scouts from Gideon |
| L10 | `stolen_sap` (L4.cypress_swamp, L9.mercenary_company): the captured Hist tree in the Blackwood Company basement. `mangrove_vampires` (L1.mangrove_mud, L4.mangrove): Deepscorn Hollow and Redwater Slough. `sunken_ayleid` (cy.L10.ayleid_undercity, L1.sinking_ruins): Nenalata and Malada half-drowned in peat |
| L11 | `blackwood_border_wars` (cy.L11.septim_empire, L9.marsh_border): centuries of legion marches into the outer swamp. `ayleid_marsh_retreat` (cy.L11.ayleid_era, L10.sunken_ayleid): the last Ayleid king crowned in Nenalata |

---

## 2. Micro-biomes (MS21)

Drivers: elevation and snowline, aspect, rain shadow (Colovian lee), distance to water, heat (only gate scars),
soil/rock from L1, disturbance (farm rings, burned ground, charcoal clearings). Floor: >= 6 per region and >= 2/km².

| Region | Micro-biomes (cause) | Count / floor |
| --- | --- | --- |
| jerall_mountains | snowfield, alpine spruce, scree heath, frozen tarn, hot-spring hollow (granite fault), pass meadow, mine spoil, gate scorch | 8 / 19 |
| colovian_highlands | sheep down, pine ridge, karst scrub, spring glen, limestone pavement, horse paddock ring, keep-ridge heath, cave-mouth fern | 8 / 19 |
| heartlands | lake reedbed, lakeshore oakwood, grain belt, heath common, urban garden, ruin-plough field, fishing strand | 7 / 15 |
| great_forest | old oakwood, beech hanger, fern glade, charcoal clearing, unicorn grove, brook alder, ruin moss-ground | 7 / 8 (14 after redraw) |
| nibenay_basin | orchard plain, wet meadow, Valus pine, Lake Arrius reeds, mill race, foothill gold-scrub, tattoo-dye flax fields | 7 / 17 |
| west_weald | vine terrace, cypress line, oak savanna, orchard, dry gully, estate garden | 6 / 8 |
| gold_coast | coastal scrub, cliff grass, cove beach, burned slope (Kvatch), Strid riverine, sea-fog heath | 6 / 11 |
| nibenay_valley | reed marsh, flood meadow, willow carr, river island, nirnroot bank, skooma-poppy plot (illegal) | 6 / 16 |
| blackwood | mangrove, cypress swamp, palm hammock, brackish flat, peat bog, sinking-ruin mire | 6 / 9 |

Counts below the per-km² floor are met by splitting large rules by aspect and altitude in `biomes.py` (for example
spruce north-facing vs pine south-facing), not by inventing biomes with no cause. Signature rule for Cyrodiil: **no
two counties share a dominant tree** (spruce, pine, oak, beech, cypress, mangrove, willow, orchard fruit).

---

## 3. Map changes and geography

1. **Uniform scale** (SCALE-AND-GEOGRAPHY 5.2): replace the thin-plate warp with the similarity at 1.754 blocks per
   game metre, rotation -3.24°. Move the nine anchors to the fitted points (IC 1270, 5540; Bruma 1020, 3400; Chorrol
   -1110, 4850; Cheydinhal 3440, 4620; Skingrad -1080, 7130; Kvatch -3020, 8050; Anvil -4100, 8220; Bravil 2350, 7930;
   Leyawiin 3250, 10150 after the Topal Bay snap). Lake Rumare and the island move with the capital; the Niben's middle
   course moves with Bravil. Re-ingest all ~900 Cyrodiil places through the same similarity.
2. **Topal Bay** north-west lobe to the Niben mouth at about (3200, 10250); move the Blackwood/Rimmen border ~300 west
   there (shared with the Elsweyr plan).
3. **Redraw the counties from Oblivion's own region map** (`oblivion_ref/regions.json`) through the same similarity.
   The game's Great Forest is its densest region (89 markers: 25 caves, 14 Ayleid ruins, 10 settlements) and today's
   3.8 km² sliver takes almost none of it. After the redraw, Aleswell, Bleaker's Way, Brindle Home, Hackdirt, Pell's
   Gate and Weynon Priory lie in the Great Forest as in the game. Expected areas: Great Forest ~7, Heartlands ~5.5,
   Colovian Highlands ~8.5 km². Add the **Valus Mountains** as a named range and sub-area of the Nibenay Basin (11
   game markers); it is not a separate region.
4. **Relief** to the province targets: p50 34 to 40, p90 114 to 150, ruggedness 2.2 to 4. Author with vectors
   (method of SCALE-AND-GEOGRAPHY 4): the Jerall wall with the Pale Pass notch and foothill gorges, the Valus ridge,
   the Colovian plateau broken by valleys cut 20-40 blocks deep, rolling Great Forest hills, a flat Nibenay floor, the
   Gold Coast cliffs (10-25 blocks) with coves, Kvatch's hill as a 60-block rise over the Gold Road.
5. **Rivers** cut 10-30 blocks below the plains: the Upper and Lower Niben (braided with islands below Bravil), the
   Corbolo through Cheydinhal (two banks for the canal plan), the Silverfish, the Panther, the Strid gorge on the
   Valenwood border. Lake Arrius as a lake polygon. Waterfalls where streams leave the Colovian plateau and the Jerall.
6. **Fixes**: Deepscorn Hollow, the Brotherhood sanctuary and other DLC sites re-ingested with the rest; `gold_coast`
   holds `dark_brotherhood_sanctuary_cyrodiil` today, but the sanctuary belongs under Cheydinhal (lore): move it.
7. **Silhouettes (XL)**: White-Gold Tower (exists, 210 blocks), the Jerall wall seen from the whole north, Kvatch's
   burned hill with the gate scar, Anvil's lighthouse, Chorrol's oak, Bruma's Talos spire, Sancre Tor on its peak,
   Cloud Ruler on its cliff, the Valus statue (`the_sentinel`), Lake Arrius' broken tower. Target >= 1 per region plus
   1 per 3 km² (F-WEENIE): 29 for the province, 9 exist in some form.

---

## 4. Roads, travel network and fast travel

**Named highways** (`roads/named_roads.json`; nine exist):

| Road | Route (place ids) | Status | Notes |
| --- | --- | --- | --- |
| Talos Bridge | imperial_city - imperial_bridge_inn | exists | The single bridge; the city's weenie from every shore |
| Red Ring Road | bridge inn - weye - aleswell - pells_gate - roxey_inn - bridge inn | exists | Lake ring; every highway leaves from it |
| Gold Road | weye - inn_of_ill_omen - skingrad - brina_cross_inn - kvatch - gottshaw_inn - anvil | exists | Longest leg (~9,650 road blocks); add two road towns (section 5) and a carriage |
| Black Road | weye - chorrol | exists | Add Weynon Priory as a named stop |
| Silver Road | aleswell - bleakers_way | exists | |
| Orange Road | bleakers_way - bruma | exists | Keep NirnCraft's naming (SCALE 6.2) |
| Blue Road | pells_gate - cheydinhal | exists | Add Cropsford, Harlun's Watch as stops |
| Green Road | roxey_inn - faregyl_inn - bravil | exists | |
| Yellow Road | bravil - drunken_dragon_inn - leyawiin | exists | |
| Colovian Road (new) | chorrol - brindle_home - skingrad | add | Oblivion's west link; highland-to-vineyard descent |
| Jerall Foothill Road (new) | bruma - (foothill village) - cheydinhal | add | Under the Jerall and over the Valus spurs |
| Bruma-Chorrol trail (new) | bruma - chorrol | add as trail | Mountain path past Sancre Tor's approach |

**Cross-province passes** (each ends at a border fort or inn on each side): Pale Pass (exists, to Helgen);
Cheydinhal - Kragenmoor over the Valus (`morrowind.md`); Leyawiin - Stormhold through Blackwood (`black_marsh.md`);
Bravil or Leyawiin - Rimmen (Elsweyr); Skingrad - Arenthia along the Strid (Valenwood); Kvatch - Rihad over the
Dragontail (Hammerfell); Chorrol's western High Rock gates (High Rock).

**Travel modes** (P-J2: >= 2 beyond walking and horse):

* **Carriages** from a stable outside each city and the capital's waterfront. Oblivion had stables, not carriages;
  the stables become the carriage yards (Chorrol, Bruma, Cheydinhal, Skingrad, Anvil, Bravil, Leyawiin, the capital;
  Kvatch's is rebuilt as part of its recovery chain). Extend `roads/Carriages`.
* **Niben river boats**: capital Waterfront - Bravil - Leyawiin, with a halt at the bridge inn. Reuse the ferry code
  with a river route; the boat passes the braided islands and Bravil's stilts (P-F3: paid travel shows the land).
* **Wayshrines as free nodes**: the ~30 wayshrines of the Nine (places exist) become fast-travel nodes once found;
  using one grants that Divine's small blessing (MS11). Ayleid wells stay blessing sites, not nodes.
* **Coastal ship** Anvil - (Hammerfell, Valenwood, Summerset) via `ports.json` (Anvil exists).
* Gap rule (F-TRAVEL): p90 <= 500 and max <= 800 blocks from any land point to a node. Today's wayshrine spread
  leaves gaps in the western Colovian highland and the eastern Valus; add 4-6 hashed roadside shrines there.

**Road texture** (P-F2): waysides every 1.5-3 km with a reason (a well at every Ayleid well site, a toll post at the
Pale Pass and the Valus, a Legion patrol post on the Gold and Yellow roads, a gate-scar warning sign on scorched
stretches). Every junction signed with distances to the next city, inn and wayshrine.

---

## 5. Settlements

### 5.1 Tier list

Cyrodiil has 0 town-tier places today (`s_towns` 0.15 named towns/km² against Skyrim's 0.68), because the
Oblivion villages were all ingested as `village`. Total settlements are already at density (134, 2.2/km²). The fix is
**promotion, not addition**: 24 villages that have a road, an industry and lore become towns.

| Tier | Count now -> target | Places |
| --- | --- | --- |
| Capital | 1 | Imperial City |
| City (county seat) | 8 | Bruma, Chorrol, Cheydinhal, Skingrad, Kvatch, Anvil, Bravil, Leyawiin |
| Town (Riverwood-class) | 0 -> 24 | Heartlands: Weye, Pell's Gate, Aleswell. Great Forest: Hackdirt, Brindle Home, Bleaker's Way. Jerall: Ember Hollow, Wrenstone, Fern Mouth. Colovian: one new spring town on the Colovian Road, the High Rock gate town. Nibenay Basin: Cropsford, Harlun's Watch, Larkmarsh. West Weald: Ivy Shade, a vintners' town at Shardrock. Gold Coast: Brina Cross, Gottshaw (Gold Road road towns grown round their inns). Nibenay Valley: Cold Dale, the Lunar Fang docks village. Blackwood: Water's Edge, Border Watch (Khajiit), Blankenmarch. Plus the Kvatch refugee camp (temporary town; becomes a ward when the chain ends) |
| Village | 45 -> 40 | Inns, mills, the Shurgak Orc estate, priories, mine and fishing villages |
| Hamlet | 80 | Farms and estates |

Result: 33 named towns (0.54/km², the 80% floor), with size paid for by stops and travel, not denser towns
(SCALE-AND-GEOGRAPHY 3).

### 5.2 City plan briefs

Marquee briefs for eight cities are in `MARQUEE.md`; this table fixes what each plan must express from the sheet and
adds Leyawiin (missing from the marquee list).

| City | Plan form (cause) | Districts and class gradient (L7) | Landmark weenie | Plan file |
| --- | --- | --- | --- | --- |
| Imperial City | Ring-island: concentric walled wards on the lake island, one bridge [Heartlands L6] | Elven Gardens / Talos Plaza (Council families) > Market, Arena, Temple, University (merchants, clergy, mages) > Waterfront shacks and docks (labourers, beggars, thieves) | White-Gold Tower | exists (`cityplans/imperial_city.json`, overlays); add per-ward tint, roof and street-width |
| Bruma | Switchback terraces up a stream valley under the pass [Jerall L1] | Castle and chapel heights > merchant terrace > miners' lower town | Great Chapel of Talos spire | exists (`bruma.json`); add `imperial_alpine` kit |
| Chorrol | Radial lanes round the Great Oak's green [Colovian L6] | Castle and guild row by the oak > craftsmen > herders' outer lanes | The Great Oak | new |
| Cheydinhal | Canal city split by the Corbolo, two bridges [Nibenay Basin L3] | Castle and Nibenese bank > Dunmer bank (flat-roofed) > mill race poor | Chapel and bridge | new; needs per-bank style (`quarter_style`) |
| Skingrad | Hill town with a castle on a rock crown, vineyards round [West Weald L1] | Estate houses on the crest > craftsmen > field hands' cottages in the vines | Castle Skingrad on its rock | new |
| Kvatch | Hilltop ruin mid-rebuild, scaffolds in pale timber [Gold Coast L11] | The camp below (refugees) > rebuilt wards > the half-restored castle | Burned castle and gate crater | new; stage-swapped buildings |
| Anvil | Harbour crescent with a mole and lighthouse [Gold Coast L1] | Castle point and merchant terraces > chandlers > dockside lodging | Lighthouse | new |
| Bravil | Stilt-and-plank city stacked on a river bank, rope bridges [Valley L3] | A thin top (the castle and the count's clerk) > everyone else poor; the statue square is the commons | Lucky Old Lady statue | exists (`bravil.json`); add `imperial_slum` kit |
| Leyawiin | Walled river-mouth city on a low island, the mercenary hall dominating the docks, shanty quarters outside the walls [Blackwood L6] | Castle and Imperial streets > the company hall and its men > Argonian stilt quarter and Khajiit quarter outside | Mercenary company hall tower and the river gate | new; marquee entry to add (`leyawiin`, tier A) |

### 5.3 Busy centres and why

* **The Imperial City**: every road ends there, the Council sits there, the Arena and Market draw the province
  [P.L6.busy_centres]. Target: 73+ named NPCs, 8-12 scenes, every faction present.
* **Anvil**: the western sea trade [Gold Coast L5]. Market days when the fleet is in.
* **Chorrol**: the highland market and the Fighters Guild hall [Colovian L5, L7].
* **Leyawiin**: the river mouth, the mercenary company's money and the border trade [Blackwood L5].
* **Skingrad at harvest**: a seasonal centre (the festival, section 7).
* **Poor and failing**: Bravil (poverty and skooma), Kvatch (rebuilding), Hackdirt (closed and strange), border
  villages in Blackwood (raids). Each poor place gets a problem quest, not just a palette.

### 5.4 Lived-in rules (Cyrodiil-specific)

* Every city has the Mages and Fighters guild halls, a chapel with its Divine, a castle court (count, steward,
  captain, court mage), an inn, a smith, an alchemist, a general store and a stable-carriage yard.
* Daily rhythm: chapel bells at dawn and dusk; the watch changes at the gate; market stalls open mid-morning; the
  Arena (capital) and the Kvatch arena ruin hold fight days.
* Each town's industry is visible: Weye's nets, Pell's Gate's fields, Hackdirt's closed doors and old mine, Cropsford's
  ford and mill, Border Watch's Khajiit moon rituals, Brina Cross's coaching yard, Water's Edge's raided fishing huts.
* Problems at the tier counts of DESIGN-PRINCIPLES 4.4 (capital 3+ problems and a secret across the class gradient;
  city 2+1; town 2+1).

---

## 6. Dungeons, underground and landmarks

### 6.1 Counts by theme vs the Skyrim floor (now / floor = median x area)

Old-culture ruins = Ayleid ruins plus Imperial and Alessian crypts. Elder-race = deep Ayleid city-vaults with a
working light-stone mechanism (a subset to designate, 0 today). Lairs = mage, necromancer and vampire lairs.

| Region (km²) | Caves | Old-culture ruins | Forts | Elder-race | Mines | Camps | Minor (towers, shacks, wrecks) | Lairs | Unmarked | All named dungeons |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| blackwood (4.5) | 11 / 12 | 11 / 6 | 12 / 5 | 0 / 1 | 4 / 2 | 7 / 5 | 3 / 9 | 8 / 3 | 0 / 3 | 57 / 40 |
| colovian_highlands (9.3) | 35 / 24 | 12 / 13 | 15 / 11 | 0 / 2 | 7 / 4 | 5 / 10 | 11 / 18 | 7 / 7 | 0 / 5 | 92 / 83 |
| gold_coast (5.6) | 17 / 15 | 11 / 8 | 7 / 6 | 0 / 1 | 4 / 2 | 8 / 6 | 6 / 11 | 3 / 4 | 0 / 3 | 56 / 50 |
| great_forest (3.8) | 11 / 10 | 6 / 5 | 7 / 4 | 0 / 1 | 1 / 2 | 3 / 4 | 1 / 8 | 2 / 3 | 0 / 2 | 31 / 34 |
| heartlands (7.7) | 17 / 20 | 12 / 11 | 15 / 9 | 0 / 2 | 6 / 3 | 6 / 8 | 4 / 15 | 5 / 6 | 0 / 4 | 65 / 69 |
| jerall_mountains (9.5) | 37 / 25 | 15 / 14 | 9 / 11 | 0 / 2 | 5 / 4 | 6 / 10 | 9 / 19 | 10 / 7 | 0 / 5 | 92 / 85 |
| nibenay_basin (8.5) | 20 / 22 | 10 / 12 | 16 / 10 | 0 / 2 | 9 / 4 | 9 / 9 | 6 / 17 | 7 / 6 | 0 / 5 | 77 / 76 |
| nibenay_valley (8.2) | 31 / 21 | 10 / 12 | 11 / 9 | 0 / 2 | 6 / 4 | 4 / 8 | 1 / 16 | 8 / 6 | 0 / 5 | 73 / 73 |
| west_weald (4.1) | 8 / 11 | 9 / 6 | 2 / 5 | 0 / 1 | 5 / 2 | 8 / 4 | 1 / 8 | 1 / 3 | 0 / 2 | 35 / 36 |

**Reading it.** Cyrodiil's dungeon count is at the floor (578 against 545) because Oblivion's map is dense. The gaps
are kinds and depth: **unmarked secrets 0 / 35**, **minor sites 42 / 121** (shacks, watchtowers, wrecks with a
story), **elder-race vaults 0 / 15**, camps short in the highlands and the Jerall, and `d_identity` at 1-14% per
region (floor 80%). Theme counts move with the county redraw; re-run after section 3 step 3.

### 6.2 Inhabitant themes and why (L10)

| Theme | Where and why | Signature of |
| --- | --- | --- |
| Ayleid undead and liches | Every county; the ruins are the old masters' tombs and vaults [P.L10.ayleid_undercity] | Province |
| Necromancers | Great Forest and Nibenay ruins and caves; the guild war drove them out of the cities [P.L9.guild_wars] | Great Forest |
| Conjurers and Daedra-cultists | Daedric shrines and gate scars [P.L8.daedric_shrines, P.L9.gate_scars] | Gold Coast, Jerall |
| Doomsday-cult remnants | Lake caves (Arrius), old forts [P.L10.cult_remnants] | Nibenay Basin |
| Marauders and bandits | Ruined forts on long road legs [P.L10.abandoned_forts, P.L10.road_bandits] | Gold Coast, Nibenay Valley |
| Vampires | Forts and coastal caves far from chapels | West Weald, Blackwood |
| Goblin tribes | Karst caves, at war with each other [Colovian L10] | Colovian Highlands |
| Minotaurs and ogres | Deep forest and hills [P.L4] | Great Forest |
| Smugglers and skooma dens | River caves [Valley L10] | Nibenay Valley |
| Trolls | High caves | Jerall |
| Mercenary hideouts | The Leyawiin company's camps in Blackwood [Blackwood L9] | Blackwood |
| Imperial family dead | Crypts under estates and in the cold north [Jerall L10] | Jerall |
| Gate remnants | Burned rings with a sealed door that wakes on a timer (MS4, optional) | Gold Coast (Kvatch) and every county |

Every region reaches >= 10 themes with this list; each region's **signature theme** is the one in the last column.

### 6.3 Marquee (>= 1 hand-authored dungeon and 1 other per region)

| Region | Marquee dungeon | Other marquee | Status |
| --- | --- | --- | --- |
| jerall_mountains | Sancre Tor (Akaviri fortress over a dragon-hunters' barrow) | Cloud Ruler Temple; Frostcrag Spire | in MARQUEE.md |
| colovian_highlands | Battlehorn Castle (a cursed keep with its own household) | Chorrol's Great Oak; Alma Ruma gate | new dungeon brief |
| heartlands | The Ring Beneath the Rings (urban delve under the wards) | Imperial City; Arena | in MARQUEE.md |
| great_forest | Vindasel (an Ayleid lich-vault under the oaks, new brief) | Weynon Priory | new dungeon brief |
| nibenay_basin | Lake Arrius caverns (the doomsday cult's last hall) | Cheydinhal's sanctuary (`dark_brotherhood_sanctuary_cyrodiil`) | new + MARQUEE.md |
| west_weald | Miscarcand (the light-and-water name-vault) | Skingrad | in MARQUEE.md |
| gold_coast | Benirus Manor (a haunted merchant house over a sealed crypt; new brief) | Kvatch gate crater; Anvil lighthouse | new + MARQUEE.md |
| nibenay_valley | Crypt of the Night Mother under Bravil | Bravil; Lucky Old Lady | new dungeon brief |
| blackwood | Deepscorn Hollow (vampire court, tide-gated) | Leyawiin; the mercenary hall | in MARQUEE.md |

### 6.4 What lies below, and why

* **The Ayleid undercity**: the elves' cities were never fully buried, only built over. Every great Cyrodiil city
  stands on an Ayleid footing, and an old reservoir links them (lore web cyr_4). Under the capital: sewers, the prison
  tunnels and the vaults. Light-stones (lit and dark states) are the dungeon mechanic of the province.
* **The forts**: three eras of war (Reman, three-banner war, Septim) left ~50 forts; each fort's inhabitants follow
  its road and its era (marauders on the Gold Road, vampires in the far south, cultists on gate scars).
* **The karst**: Colovian and Jerall caves are long, wet, cold and goblin-held.
* **Unmarked (35)**: a sealed Ayleid side-vault behind a well, an Alessian slave tunnel, a Reman courier cache in the
  Jerall, a smuggler's drowned cellar on the Niben, a gate-scar pocket that did not close. Each with a note or rumour.

---

## 7. Quests

### 7.1 Province arc: "The Council of Ash" (>= 19 quests, one or two chapters per county)

With no emperor and the Dragonfires out, the Elder Council splits between a Colovian bloc and a Nibenese bloc, and
each county's count will back one of them. A forger is selling proofs of a "surviving Septim line" to both sides;
gate scars still leak and the cult's last cells want them reopened. The player carries the Council's errands county
by county (each chapter is that county's hold conflict seen from the capital), learns who is buying the forgeries,
and ends in the Council chamber under White-Gold Tower, where the vote they shaped decides which bloc governs.
Consequences: the counts who backed the losing bloc change their courts; one county's guard livery changes; the
capital's ward with the winning bloc gains a market.

| County | Chapter (hold conflict) | Start (P-D4 kinds) | World change |
| --- | --- | --- | --- |
| Jerall | **The Pass Toll**: count vs Legion over the Pale Pass tax; smugglers use the quarrel | npc (Bruma gate), rumour | Toll post staffed by one side; smugglers' cave sealed or thriving |
| Colovian | **Wool and Steel**: the Fighters Guild hall is losing its contracts to the Leyawiin company | npc (guild hall), board | Guild hall reopens a wing or closes it |
| Heartlands | **The Ring Beneath the Rings** (marquee) | find (sewer grate), note | A sealed vault returned to the Palace or the Arboretum |
| Great Forest | **The Forest Lectures**: Mages Guild members vanish into the ruins | npc (guild), note | A ruin cleared; a necromancer cell's tower burns |
| Nibenay Basin | **Two Banks**: Dunmer and old Cheydinhal merchants over the market | npc, rumour | Market charter rewritten; one bank's shopfronts change |
| West Weald | **Two Vintages, One Cask** (marquee) | npc | One cellar sealed or merged |
| Gold Coast | **What the Ash Remembers** (Kvatch rebuild) and **The Dark Beacon** (Anvil) | auto (on entering Kvatch), npc | Kvatch wards rebuilt in stages; lighthouse relit |
| Nibenay Valley | **The Lady's Lucky Day** (Bravil) | rumour | The clerk falls or keeps his office |
| Blackwood | **The Sap Ledger**: the mercenary company's men drink stolen Hist sap; Black Marsh wants it stopped | npc (Leyawiin), courier | Company hall raided or protected; Argonian quarter's mood changes; links `black_marsh.md` 7.1 |

### 7.2 Faction lines (hubs in different cities, P-G1)

| Faction | Hub | Ladder and line (6-14 quests) | Cyrodiil-specific twist (our design) | Exists today |
| --- | --- | --- | --- | --- |
| Mages Guild | Arcane University (capital) + a hall in every city | A recommendation from each city hall (8 short quests), then the University and the war with the necromancers (4-6) | Each hall's test is that city's problem (Bruma's cold-crypt, Bravil's skooma alchemist, Anvil's sea-glass) | `mages_guild.json` (6) |
| Fighters Guild | Chorrol | Contracts (radiant) then the company war (8) | The rival company's sap secret ends in Blackwood, joined to the province arc | `fighters_guild.json` (6) |
| Thieves Guild | Imperial City Waterfront | Fence ladder, then a heist chain against the hard watch captain (8) | The guild's beggar-eyes give rumours as map reveals (MS13) | `thieves_cyrodiil.json` (6) |
| Dark Brotherhood | Cheydinhal sanctuary | Contracts across all nine counties (10) | A traitor in the family; the mother's crypt under Bravil | `brotherhood_cyrodiil.json` (6) |
| Knights of the Nine | Priory of the Nine (West Weald) | Relic pilgrimage to wayshrines and crypts (8) | Each relic lies in a different county's marquee or crypt; pilgrims follow the wayshrine network | `knights_nine.json` (6) |
| Arena | Imperial City | Bout ladder (8) | Kvatch's ruined arena reopens as a second stage after its rebuild | `arena.json` (8) |
| Imperial Legion | Capital and border forts | Patrol and border chain (6) | Gate-scar watch and the marsh border | `legion_cyrodiil.json` (6) |

Exclusive choice: none forced; the province arc's bloc vote is the choice with consequences.

### 7.3 Region stories and settlement quests

* **Per county**: the chapter above plus >= 3 authored side quests with consequence and 2 Daedric-shrine quests
  (15 shrines exist; each county's shrine cult has a reason in L8 or L10).
* **Per city**: the MARQUEE chain plus a court problem (F-COURT) and 3 favours. **Per town**: 2 problems and 1
  secret (for example: Weye's nets pull up Ayleid glass; Hackdirt's sealed chapel; Water's Edge raided by men in
  company colours; Border Watch's ritual needs a moon-sugar delivery; Brina Cross's coachman sells road news).
* **Daedric**: shrines in the Great Forest, Jerall and Blackwood carry the most (L8 `forest_daedric_shrines`).

### 7.4 Radiant (culture-specific templates, >= 6)

Light a gate-scar warding stone; clear a ruin a farmer ploughs into; escort a wine wagon on the Gold Road; deliver a
Council summons to a hostile count; retrieve a stolen wayshrine offering; hunt a minotaur that took a logging camp;
recover Ayleid glass for the University; collect river tolls in Bravil (and decide whom to excuse). Each names a real
giver and a real uncleared site 300-1,500 blocks away (MS12 boards in every town and city).

### 7.5 Festival (MS16)

**Skingrad harvest** (the wine feud's set piece) is the province festival; the capital's Arena championship day is the
second.

---

## 8. Components layout

| Component | File / system | Owner phase | Status |
| --- | --- | --- | --- |
| World sheet (province + 9 regions) | `tamriel/world/cyrodiil.json`; `tools/qa/worldsheet_lint.py` (MS22; add `X:` cross-province cites) | CY0 | new |
| Geometry | `tools/provinces.py` uniform similarity; `tamriel_vectors.json` ranges, rivers, lakes, areas; `regions.png` redraw from `oblivion_ref/regions.json` | CY1 | change |
| Places | `tamriel/places/cyrodiil.json` + `provinces.json` (anchors), tier promotions, unmarked `hidden: true`, minor sites | CY1, CY3 | change |
| Micro-biomes | `palettes.json` `micro` tables via `tools/worldgen/biomes.py`; rain-shadow and heat rasters | CY2 | new (MS21) |
| Roads | `roads/named_roads.json` (+3 roads, +6 passes), rebake `roadplan.bin` | CY2 | change |
| Travel | `roads/Carriages` (Cyrodiil yards), river route in `ports.json`, wayshrine nodes (MS11) | CY2 | change |
| City plans | `tamriel/cityplans/{chorrol,cheydinhal,skingrad,kvatch,anvil,leyawiin}.json` new; IC, Bruma, Bravil extended; kits `imperial_alpine`, `imperial_warm`, `imperial_coast`, `imperial_slum`, `imperial_oak` in `styles/imperial.json` | CY3 | new |
| Population | society grammar per tier; courts; guild staff | CY3 | change |
| Dungeons | `class` tags (MS7), identity (`dungeon_identity_gen.py`), marquee plans (`MarqueePlans`), Ayleid light-stone room kit | CY4 | change |
| Creatures | `region_spawns` keys per micro-biome; goblin tribes, minotaurs, land dreugh; gate-scar spawns | CY4 | change |
| Scenes and encounters | sceneplan kinds + `creatures/scenes.json` (>= 8 per region, >= 3 Cyrodiil-only: Council courier, Legion gate-scar patrol, wine wagon, pilgrims of the Nine) | CY5 | change |
| Quests | `quests/story/cyrodiil_*.json` (arc + 9 chapters), faction files, `side_cyrodiil.json`, `radiant_templates.json` | CY6 | change |
| Lore and readables | `lore/books/cyrodiil_*.json`, region voice, rumours, `loreweb_cyrodiil.json` | CY7 | change |
| Events (optional layer) | `events/cyrodiil.json` (timed gate remnants, MS4) | CY8 | optional |

Conflicts: Topal Bay is shared with `black_marsh.md` and the Elsweyr plan; the Valus road with `morrowind.md`.

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Oblivion's county layout, the lake, the Niben, the Jerall and Valus walls | Ruggedness 2.2 to 4; river valleys cut below the plains; Colovian gorges; Gold Coast cliffs; Kvatch's hill | Oblivion's **generic green forest everywhere**: each county gets its own dominant tree, ground cover and light (section 2) |
| Settlements | The nine cities, their roles and chapels, the inn chain | Each city's lore role becomes its plan form and its industry (5.2) | Same-plan Imperial towns; 24 villages promoted with their own sub-kits |
| Dungeons | 51 Ayleid ruins, 50+ forts, ~90 caves, their names and spots | Ayleid light-stone mechanics, the shared undercity, gate scars as events | **Oblivion's copy-paste caves and same-room Ayleid halls**: culture room kits per era (Ayleid, Alessian, Reman, three-banner war), a loop and a shortcut, a boss with a reason |
| Life | Guild halls everywhere, schedules | Daily rhythms, courts, class gradient by ward | Oblivion's global levelling: county bands (Jerall and Blackwood fiercer, Heartlands gentle) |
| Quests | The guild lines' structure, the city problems | Consequences visible in the city; the Council arc | Oblivion's own quest content: all ours |
| Travel | Roads, inns, wayshrines | Carriages at the stables, Niben boats, wayshrine nodes | Fade-out fast travel with no world in between |

---

## 10. Floors, gates and implementation phases

### 10.1 Current vs target per region (`parity.py`, 2026-10-13)

"Now" values for quests, NPCs and readables are parity densities times area (estimates). Floor = Skyrim median x
area; stage 1 = PARITY.md 5 stage-1 densities.

| Region | km² | Mean score | Weakest | Dungeons now / floor | Quests now / stage 1 / floor | Named NPCs now / floor | Readables now / stage 1 / floor | Named towns now / target |
| --- | ---: | ---: | --- | --- | --- | --- | --- | --- |
| jerall_mountains | 9.5 | 50 | LOR 15, ITM 30, QST 35 | 92 / 85 | 12 / 29 / 133 | 11 / 320 | 20 / 76 / 1,856 | 1 / 4 |
| colovian_highlands | 9.3 | 46 | LOR 13, SET 23, ITM 30 | 92 / 83 | 18 / 28 / 130 | 13 / 313 | 14 / 74 / 1,817 | 1 / 3 |
| heartlands | 7.7 | 52 | LOR 13, ITM 30, SET 44 | 65 / 69 | 35 / 23 / 108 | 22 / 260 | 11 / 62 / 1,509 | 1 / 4 |
| great_forest | 3.8 | 34 | NPC 1, LOR 3, QST 11 | 31 / 34 | 1 / 11 / 54 | 0 / 129 | 2 / 30 / 746 | 0 / 3 |
| nibenay_basin | 8.5 | 43 | LOR 12, SET 23, QST 29 | 77 / 76 | 10 / 26 / 119 | 11 / 285 | 10 / 68 / 1,652 | 1 / 4 |
| west_weald | 4.1 | 46 | LOR 18, SET 21, NPC 23 | 35 / 36 | 5 / 12 / 58 | 8 / 139 | 16 / 33 / 805 | 1 / 3 |
| gold_coast | 5.6 | 47 | LOR 16, SET 27, ITM 30 | 56 / 50 | 22 / 17 / 78 | 20 / 188 | 19 / 45 / 1,092 | 2 / 4 |
| nibenay_valley | 8.2 | 48 | LOR 15, ITM 30, NPC 30 | 73 / 73 | 15 / 25 / 114 | 10 / 274 | 23 / 66 / 1,592 | 1 / 3 |
| blackwood | 4.5 | 49 | LOR 15, SET 23, NPC 26 | 57 / 40 | 8 / 14 / 63 | 4 / 150 | 14 / 36 / 872 | 1 / 4 |
| **Province** | **61.2** | **48** | LOR 13, ITM 30, SET 37 | **578 / 545** | **126 / 184 / 857** | **~100 / 2,056** | **~130 / 490 / 11,934** | **9 / 33** |

**Headline targets (Cyrodiil):** 33 named towns (from 9); `d_identity` from ~4% to 80% (stage 1: 50%); 35 unmarked
secrets (from 0); 79 more minor sites; quests 126 to 184 at stage 1 and 857 at the floor (generators and radiant carry
most); readables 130 to 490 at stage 1; named NPCs to 37.5 per named town (1,240 over 33 towns); >= 6 micro-biomes per
region; 29 XL silhouettes; carriages, Niben boats and wayshrine nodes live (F-TRAVEL p90 <= 500).

### 10.2 Gates (a phase is done only when its gate passes)

* `worldsheet_lint.py`: 0 errors; every content record added in the phase has a resolving `why`.
* `parity.py --assert-floor SET,DUN,LND` per region after CY3/CY4; `--assert-metric q_total,r_notes,s_npcs` at the
  stage-1 values after CY6/CY7; floor values at the end.
* `layout_lint.py` (geometry traced, no raster), `roadplan_lint.py` and the corridor gate (CY1-CY2).
* `reach_lint.py`: no isolated major site; F-SIGN >= 95%; F-TRAVEL p90 <= 500, max <= 800.
* `population_lint.py` 0 unstaffed workplaces; `town_checks.py`; F-COURT 100% of the nine seats.
* `dungeon_audit.py` F-LOOP >= 90% major, 100% marquee; `dungeon_identity_lint.py`.
* `ecology_report.py` road300 <= 5% day; F-ENC >= 8 per region; F-WONDER >= 3 per region (for example dawn over the
  Rumare from Cloud Ruler, the Kvatch plume at dusk, the Niben fog lifting at Bravil, moonrise on Lake Arrius).
* `novelty_lint.py --strict` 0; MS20 verbatim 0.
* Haiku review per county: blind identity test >= 80% on 3 screenshots (G1).

### 10.3 Phases (each a self-contained agent task)

| Phase | Scope | Depends on | Gate |
| --- | --- | --- | --- |
| CY0 | Write `tamriel/world/cyrodiil.json` from section 1; lint | MS22 lint | worldsheet_lint 0 |
| CY1 | Uniform similarity re-ingest, anchor moves, county redraw, Topal Bay lobe, relief and river vectors | CY0 | layout_lint, relief stats (p50 >= 40, rugged >= 3.5), corridor gate |
| CY2 | Named roads (+3, passes), carriages, Niben boats, wayshrine nodes, micro-biome tables | CY1 | roadplan_lint, F-TRAVEL, F-MICRO |
| CY3 | Tier promotions (24 towns), six new city plans, kits, courts, population | CY1 | SET, F-COURT, population_lint, Haiku contact sheet |
| CY4 | Dungeon class tags, identity back-fill, 35 unmarked, 79 minor sites, Ayleid light-stone kit, 4 new marquee dungeons | CY0 | DUN floors, F-LOOP, d_identity >= 50% |
| CY5 | Scenes and encounters (>= 8 per region), wonder moments, signs | CY2 | F-ENC, F-WONDER, F-SIGN, F-QUIET |
| CY6 | Province arc (19+), nine chapters, faction extensions, side and radiant, boards | CY3, CY4 | QST stage 1, q_placed >= 0.9, F-HOOK <= 800 |
| CY7 | Readables, region voice, rumours, lore-web extension, festival | CY6 | LOR stage 1, novelty, MS20 |
| CY8 | Optional layer (timed gate remnants, MS4) and floor push with generators | CY7 | Floors per 10.1 |
