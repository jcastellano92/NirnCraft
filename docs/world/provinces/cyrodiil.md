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
times the region's area; "abs." is Skyrim's thinnest hold. Area is Skycraft km² from `parity.py`.

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

| Layer | Fields `id`: text [causes] |
| --- | --- |
| L1 Geology | `jerall_wall`: granite range along the Skyrim border, one deep notch at the Pale Pass. `valus_ridge`: a second range on the Morrowind border, separate from the Velothi. `colovian_plateau`: broken limestone upland in the west with karst caves and deep stream valleys. `rumare_basin`: a shallow lake basin at the centre whose island carries the oldest stonework in Tamriel. `nibenay_alluvium`: deep river silt east and south of the lake. `gold_coast_cliffs`: low sea cliffs and coves on the Abecean. `topal_mudflats`: mud, peat and mangrove ground where the Niben meets Topal Bay. `ore_highlands`: iron and silver seams in the Jerall and Colovian rock, gold traces in the Valus foothills. `ayleid_foundations`: elven stone, glass and charged light-stones under every county |
| L2 Climate | `temperate_core`: mild, four-season heartland. `west_dry`: Colovia lies in the lee of its own uplands; drier, brighter, cooler nights [L1.colovian_plateau]. `east_wet`: the Nibenay is humid and fertile [L1.nibenay_alluvium]. `jerall_cold`: snow above ~150 blocks, blizzards in winter [L1.jerall_wall]. `abecean_storms`: westerly gales and sea fog on the Gold Coast [L1.gold_coast_cliffs]. `topal_heat`: sub-tropical, steamy Blackwood with thunderstorms [L1.topal_mudflats]. `lake_mist`: morning fog on Lake Rumare and the Niben |
| L3 Water | `lake_rumare`: the lake at the heart, one bridge to the capital [L1.rumare_basin]. `niben`: the great river south from the lake through Bravil to Topal Bay at Leyawiin, braided in its lower course [L1.nibenay_alluvium]. `tributaries`: the Corbolo from the Valus through Cheydinhal, the Silverfish west of the lake, the Panther in Blackwood, the Strid on the Valenwood border. `ayleid_wells`: about 35 elven wells along the roads that still restore the weary [L1.ayleid_foundations]. `who_holds_water`: the capital controls the lake crossing; counts toll the river ports; the wells are public and treated as sacred, so no lord may fence one |
| L4 Ecology | `oak_beech_forest` (Great Forest) [L2.temperate_core]. `highland_pasture` with pine and elk [L2.west_dry]. `heath_and_grain` round the lake [L3.lake_rumare]. `vine_cypress` slopes in the West Weald [L2.west_dry]. `alpine_spruce` [L2.jerall_cold]. `mangrove_swamp` [L2.topal_heat]. `riverine_reeds` with nirnroot by running water [L3.niben]. Predators and monsters: wolves, bears, mountain lions, trolls on high ground, minotaurs in deep forest, ogres in the hills, land dreugh and mudcrabs on shores, goblin tribes in caves [L1.colovian_plateau], scorched ground and stray lesser Daedra round every gate scar [L9.gate_scars] |
| L5 Economy | `grain_belt`: wheat, barley and cattle in the Heartlands and Nibenay [L3.niben, L4.heath_and_grain]. `wine`: rival estates on the Weald slopes [L4.vine_cypress]. `timber`: oak from the Great Forest [L4.oak_beech_forest]. `herding`: sheep and horses in Colovia [L4.highland_pasture]. `mining`: iron and silver [L1.ore_highlands]. `river_trade`: barges down the Niben [L3.niben]. `sea_trade`: Anvil's fleet to the west, Leyawiin's to Black Marsh and Elsweyr [L1.gold_coast_cliffs, L3.niben]. `skooma_smuggling`: up the Niben from Elsweyr [L3.niben, L9.border_pressure]. `empire_revenue`: tax, offices and the Arena keep the capital rich |
| L6 Settlement | `nine_cities`: one capital and eight walled cities, each at a crossing, pass, port or river [L3, L1]. `radial_roads`: eight named roads from the lake [L3.lake_rumare]. `inn_chain`: a roadside inn every half-day [P.L6.radial_roads]. `busy_centres`: the Imperial City (every road, the Council, the Arena), Anvil (the western sea), Chorrol (highland market and the Fighters Guild hall), Leyawiin (the river mouth) [L5]. `poor_places`: Bravil (river slum on stilts), Kvatch (burned), closed villages in the hills [L5.skooma_smuggling, L9.gate_scars] |
| L7 Society | `colovian_nibenese_split`: west is martial, plain and clannish; east is ornate, mercantile, tattooed and mystical [L2.west_dry, L2.east_wet]. `class_ladder`: Council families and counts, guild masters and merchants, craftsmen, farm tenants, river labourers, beggars. `rule_of_law`: city watch and Legion, bounties, a fine or a cell for every crime. `guilds_everywhere`: Mages and Fighters halls in every city, the Arcane University in the capital, a thieves' network among the poor, assassins in the dark [L6.nine_cities] |
| L8 Belief | `the_nine`: each city's chapel is dedicated to one Divine (Akatosh at Kvatch, Arkay at Cheydinhal, Dibella at Anvil, Julianos at Skingrad, Mara at Bravil, Stendarr at Chorrol, Talos at Bruma, Zenithar at Leyawiin; the Temple of the One in the capital) [L6.nine_cities]. `wayshrines`: about 30 roadside shrines to the Nine [L6.radial_roads]. `daedric_shrines`: 15 Daedric statues in the wild, tended by small cults [L10]. `doomstones`: about 20 old standing stones that grant a sign's blessing [L11.ayleid_era]. `elf_ruin_fear`: people avoid the white ruins and tell stories about them [L1.ayleid_foundations]. `arena_glory`: blood sport as civic pride [L5.empire_revenue] |
| L9 Politics | `throne_empty`: no emperor, the Dragonfires dark, the Elder Council and its chancellor rule [L11.oblivion_crisis]. `gate_scars`: dozens of closed gates leave burned rings that still leak [L11.oblivion_crisis]. `county_rivalry`: counts test how far the Council can reach. `guild_wars`: the Mages Guild against the necromancers; the Fighters Guild against a rival mercenary company based in Leyawiin [L7.guilds_everywhere]. `border_pressure`: skooma from Elsweyr, a newly confident Black Marsh, Dunmer settlers and their own politics in the east [L6.nine_cities] |
| L10 Hidden | `ayleid_undercity`: 50-odd Ayleid ruins and a pre-Imperial reservoir under the cities, home to undead, necromancers, conjurers and treasure hunters [L1.ayleid_foundations]. `cult_remnants`: the doomsday cult's surviving cells hide in lake caves and old forts [L9.gate_scars]. `abandoned_forts`: about 50 ruined forts from older wars taken by bandits, marauders and vampires [L11.three_banner_war]. `road_bandits`: on the long legs (Gold Road, Yellow Road) far from city watches [L6.radial_roads]. `assassins_and_thieves`: a sanctuary under a quiet house in Cheydinhal, a guild among the Waterfront shacks [L7.guilds_everywhere] |
| L11 History | `ayleid_era`: the elves built White-Gold Tower and ruled the heartland with human slaves. `alessian_revolt`: the slaves rose, took the tower and founded the Empire; the Alessian scroll-temples and gates are its relics. `reman_akaviri`: the Reman emperors and their Akaviri guard left Sancre Tor, the Pale Pass fort and Cloud Ruler. `three_banner_war`: keeps and forts from a Second Era war of three alliances for the Ruby Throne. `septim_empire`: the roads, chapels and Legion. `oblivion_crisis`: the emperor murdered, gates opened, Kvatch destroyed, the last heir's sacrifice ended it |

### 1.1 Region sub-sheets

Region areas are today's (`parity.py`); section 3 moves borders. Regions over 6 km² are planned as two hold-sized
halves (DESIGN-PRINCIPLES 4.1).

#### Jerall Mountains (`jerall_mountains`, 9.5 km², seat Bruma; halves: Bruma and the Pale Pass / the eastern Jerall and Sancre Tor)

**World truth.** The cold northern rim where Cyrodiil meets Skyrim. Bruma is half Nord in blood and building, a
snow-roofed city on the only good pass, its chapel to Talos the tallest spire in the province. Above it the Blades'
cliff fortress watches the pass, and to the west the dead Reman fortress of Sancre Tor broods on its peak. Life is
herding, mining, pass trade and smuggling. The mountains hold more caves and old Imperial family crypts than any
county, and the cold keeps the dead in them restless.

| Layer | Fields |
| --- | --- |
| L1 | `pale_pass_notch` [P.L1.jerall_wall]. `granite_caves` with ice in the high ones. `silver_seams` [P.L1.ore_highlands] |
| L2 | `snow_above_150`, `blizzard_winters` [P.L2.jerall_cold]. `valley_inversion`: clear peaks over fogged valleys |
| L3 | `snowmelt_streams` feeding the Upper Niben and Lake Rumare [P.L3.niben]. `bruma_cisterns`: the city stores meltwater behind its walls [L2.blizzard_winters] |
| L4 | `alpine_spruce`, `scree_heath`, `snowfield`, `hot_spring_hollow` [L1.granite_caves]. Trolls, snow wolves, bears, mountain lions |
| L5 | `pass_trade` with Skyrim [L1.pale_pass_notch]. `silver_mines` [L1.silver_seams]. `goat_and_fur` [L4.alpine_spruce]. `smuggling` round the toll post [L9.pass_tolls] |
| L6 | `bruma_on_the_pass` [L1.pale_pass_notch]. `foothill_villages` on the Silver and Orange roads (Bleaker's Way) [P.L6.radial_roads]. `mine_hamlets` [L5.silver_mines] |
| L7 | `nord_imperial_mix`: Nord families and Imperial officers [L6.bruma_on_the_pass]. `miner_debt` [L5.silver_mines] |
| L8 | `talos_first`: the Great Chapel of Talos [P.L8.the_nine]. `dragon_blood_lore` kept by the Blades [L11.reman_akaviri]. Daedric shrines in high hollows |
| L9 | `pass_tolls`: Bruma's count and the Legion quarrel over who taxes the pass [P.L9.county_rivalry]. `blades_without_emperor` [P.L9.throne_empty] |
| L10 | `family_crypts`: the most Imperial crypts of any county; cold keeps the dead walking [L2.snow_above_150]. `sancre_tor_barrow` [P.L11.reman_akaviri]. `mage_on_the_crag`: a lone tower (Frostcrag) |
| L11 | `reman_fortresses`, `pass_battles`, `gate_at_bruma`: a gate opened at the walls in the Crisis [P.L11.oblivion_crisis] |

#### Colovian Highlands (`colovian_highlands`, 9.3 km², seat Chorrol; halves: Chorrol and the Black Road / the western highland to the High Rock gates)

**World truth.** Proud, dry upland of sheep, horses and broken limestone valleys running west to the High Rock
border. Chorrol grew round a giant oak at the end of the Black Road and is the highland's market and the Fighters
Guild's home. The people are plain-spoken Colovians who distrust the capital's ornate politics. Its caves and karst
hide goblin tribes, bandits and closed hill villages with secrets of their own, and the old keeps of a Second Era war
stand on every ridge.

| Layer | Fields |
| --- | --- |
| L1 | `karst_valleys`, `limestone_caves` [P.L1.colovian_plateau]. `iron_seams` [P.L1.ore_highlands] |
| L2 | `dry_bright_summers`, `cold_clear_nights` [P.L2.west_dry]. `upland_wind` |
| L3 | `sinking_streams`: rivers vanish into karst and return as springs [L1.karst_valleys]. `spring_villages`: villages sit on the springs |
| L4 | `sheep_downs`, `pine_ridges`, `karst_scrub`, `spring_glen` [L3.sinking_streams]. Goblins, wolves, bears, ogres |
| L5 | `wool_and_horses` [L4.sheep_downs]. `iron_mines` [L1.iron_seams]. `chorrol_market` [L6.chorrol_crossroads] |
| L6 | `chorrol_crossroads` at the Black Road's end [P.L6.radial_roads]. `spring_hamlets` [L3.spring_villages]. `closed_villages` in side valleys (Hackdirt; Great Forest after the redraw) [L1.karst_valleys] |
| L7 | `colovian_pride` [P.L7.colovian_nibenese_split]. `guild_town`: the Fighters Guild seat [P.L7.guilds_everywhere] |
| L8 | `stendarr_chapel` [P.L8.the_nine]. `oak_reverence`: the town treats its tree as a living elder [L6.chorrol_crossroads]. Weynon Priory's monks |
| L9 | `guild_rivalry`: the Fighters Guild losing contracts to the Leyawiin company [P.L9.guild_wars]. `herder_logger_feud` with the Great Forest |
| L10 | `goblin_wars`: tribes fight each other for caves [L1.limestone_caves]. `deep_ones_below`: a hill village hides what lives in its tunnels [L6.closed_villages]. `ridge_keeps` held by marauders [P.L10.abandoned_forts] |
| L11 | `high_rock_gates`: border keeps. `alessian_scroll_temples` (Alma Ruma, Ni-Mohk) [P.L11.alessian_revolt]. `battlehorn` |

#### Heartlands (`heartlands`, 7.7 km², capital the Imperial City; halves: the lake and city / the Red Ring farmland)

**World truth.** The lake country round the capital. The Imperial City stands on its island, a ring of walled wards
round the elves' White-Gold Tower, reached by one great bridge. Around the shore lie the Red Ring Road, grain farms,
fishing villages and the first inns of every highway. Wealth and power flow through here, and so does every rumour.
Under the city run the sewers and older vaults; under the farms lie Ayleid ruins the farmers plough round. The
Council's quarrels, the Arena's crowds and the Waterfront's thieves make this the busiest and most watched county.

| Layer | Fields |
| --- | --- |
| L1 | `island_bedrock` [P.L1.rumare_basin]. `lakeshore_clay`. `ayleid_footings` [P.L1.ayleid_foundations] |
| L2 | `mild_lake_climate`, `lake_mist` [P.L2.lake_mist] |
| L3 | `rumare_shore`, `city_sewers_and_cisterns`: the Watch controls the city's water and the ways beneath [P.L3.who_holds_water] |
| L4 | `lake_reedbed`, `heath_and_grain`, `lakeshore_oakwood`, `urban_garden` (Arboretum) [L3.rumare_shore]. Mudcrabs, slaughterfish, deer, rats |
| L5 | `capital_economy`: offices, Arena, market district [P.L5.empire_revenue]. `lakeside_grain_and_fish` [L4.heath_and_grain] |
| L6 | `imperial_city` [P.L6.busy_centres]. `bridgehead_villages` (Weye, the bridge inn) [L3.rumare_shore]. `red_ring_villages` (Pell's Gate, Aleswell, Roxey Inn) [P.L6.inn_chain] |
| L7 | `ward_ladder`: Elven Gardens and Talos Plaza at the top, Market and Temple in the middle, the Waterfront at the bottom [P.L7.class_ladder]. `beggar_eyes`: beggars watch for the thieves [L7.ward_ladder] |
| L8 | `temple_of_the_one` [P.L8.the_nine]. `arena_cult` [P.L8.arena_glory]. `university_rationalism` |
| L9 | `council_factions`: Colovian and Nibenese blocs [P.L9.throne_empty]. `watch_vs_guild`: a hard captain hunts the thieves [L7.beggar_eyes] |
| L10 | `ring_beneath_the_rings`: pre-Imperial reservoir and vaults under every ward (lore web cyr_4) [L1.ayleid_footings]. `prison_tunnels`. `lake_cult_caves` [P.L10.cult_remnants] |
| L11 | `white_gold_tower` [P.L11.ayleid_era]. `alessian_bridge`. `throne_room_murder` [P.L11.oblivion_crisis] |

#### Great Forest (`great_forest`, 3.8 km² today; grows to ~7 km² after the county redraw, section 3)

**World truth.** The old oak and beech forest west and north-west of the lake, the densest ground for ruins in
Cyrodiil: more caves and Ayleid sites per mile than anywhere. Few people live in it, and those who do are loggers,
charcoal-burners, priory monks and hunters who keep to the roads. Minotaurs and wild things roam deep in, necromancers
take the elven ruins, and the forest's shrines draw cultists. The forest is Cyrodiil's wild middle: beautiful by day,
avoided at night, full of things the capital would rather forget.

| Layer | Fields |
| --- | --- |
| L1 | `forest_loam_hills`, `ruin_dense_ridge` [P.L1.ayleid_foundations] |
| L2 | `canopy_shade`, `forest_fog` [P.L2.temperate_core] |
| L3 | `forest_brooks`, `silverfish_headwaters` [P.L3.tributaries] |
| L4 | `old_oakwood`, `beech_hanger`, `fern_glade`, `charcoal_clearing` [L5.timber], `unicorn_grove` (a hidden grove) [L1.forest_loam_hills]. Minotaurs, bears, wisps |
| L5 | `timber` and `charcoal` [P.L5.timber]. `hunting` |
| L6 | `road_hamlets` only (Weye's forest edge, Wawnet Inn) [P.L6.radial_roads]. `priory` (Weynon) on the Black Road |
| L7 | `woodfolk`: tenants of lowland lords [P.L7.class_ladder]. `outlaw_refuge` |
| L8 | `forest_daedric_shrines`: several of the 15 statues stand here [P.L8.daedric_shrines]. `grove_spirits` |
| L9 | `necromancer_war_front`: the Mages Guild loses members to forest ruins [P.L9.guild_wars] |
| L10 | `lich_ruins` (Vindasel, Fanacasecul, Nornalhorst, Silorn, Ceyatatar) [L1.ruin_dense_ridge]. `minotaur_lairs` [L4.old_oakwood] |
| L11 | `ayleid_heartwood`: the elves' sacred woods; `goblin_war_of_old` |

#### Nibenay Basin (`nibenay_basin`, 8.5 km², seat Cheydinhal; halves: Cheydinhal and the Valus / the Blue Road and Lake Arrius)

**World truth.** The fertile eastern basin between the Niben and the Valus range, where Nibenese tattoo-art, spice
markets and mystic orders meet Dunmer settlers from over the mountains. Cheydinhal straddles the Corbolo with a Dunmer
count in its castle and an assassins' sanctuary under one of its quiet houses. Farms and orchards fill the plain, the
Knights of the Thorn play at chivalry, and the old Morrowind border keeps watch from the passes. A doomsday cult hid
in the caves by Lake Arrius during the Crisis, and its last cells still do.

| Layer | Fields |
| --- | --- |
| L1 | `valus_foothills` [P.L1.valus_ridge]. `basin_silt` [P.L1.nibenay_alluvium]. `foothill_gold` [P.L1.ore_highlands] |
| L2 | `humid_east`, `valus_rain` [P.L2.east_wet] |
| L3 | `corbolo` [P.L3.tributaries]. `lake_arrius`. `mill_weirs` |
| L4 | `orchard_plain`, `wet_meadow`, `valus_pine`, `lake_arrius_reeds` [L3.lake_arrius]. Bears, deer, minotaurs on the forest edge |
| L5 | `orchards_and_grain` [L1.basin_silt]. `corbolo_mills` [L3.mill_weirs]. `morrowind_trade` over the Valus [L1.valus_foothills] |
| L6 | `cheydinhal_on_the_corbolo` [L3.corbolo]. `blue_road_villages` (Cropsford, Harlun's Watch) [P.L6.radial_roads]. `border_keeps` [L5.morrowind_trade] |
| L7 | `dunmer_quarter` [L5.morrowind_trade]. `nibenese_gentry` [P.L7.colovian_nibenese_split]. `knightly_dilettantes` (the Thorn lodge) |
| L8 | `arkay_chapel` [P.L8.the_nine]. `dunmer_ancestor_rites` beside it |
| L9 | `dunmer_count`: the castle's Dunmer lord and his Imperial-born courtiers [L7.dunmer_quarter]. `market_quarrel` between old merchants and Dunmer traders |
| L10 | `sanctuary_under_the_house` [P.L10.assassins_and_thieves]. `arrius_cult_cells` [P.L10.cult_remnants]. `valus_keeps` |
| L11 | `alessian_gates_chim_ghartok` [P.L11.alessian_revolt]. `border_wars_with_morrowind` |

#### West Weald (`west_weald`, 4.1 km², seat Skingrad)

**World truth.** Warm, sheltered vineyard country west of the Great Forest, on the Gold Road between the capital and
the coast. Skingrad sits on its hill under a castle on a rock, its estates locked in an old rivalry over who makes the
best wine. The count is almost never seen and the town prefers not to ask why. Elven ruins (Miscarcand among them)
lie under the vines and orchards, and the Priory of the Nine, home of a knightly order of the Divines, stands in the
hills. Mild, golden and wealthy, with cold things in its cellars.

| Layer | Fields |
| --- | --- |
| L1 | `warm_limestone_slopes` [P.L1.colovian_plateau]. `deep_ayleid_vault` (Miscarcand) [P.L1.ayleid_foundations] |
| L2 | `sheltered_warmth`, `harvest_haze` [P.L2.west_dry] |
| L3 | `strid_headwaters`, `estate_cisterns`: estates hold the springs and sell water to tenants in dry years [P.L3.who_holds_water] |
| L4 | `vine_terrace`, `cypress_line`, `oak_savanna`, `orchard` [P.L4.vine_cypress] |
| L5 | `wine_estates` [L4.vine_terrace]. `tomato_and_grain` (Skingrad's famous produce). `gold_road_traffic` [P.L6.radial_roads] |
| L6 | `skingrad_hill` on the Gold Road [L5.gold_road_traffic]. `estate_hamlets` [L5.wine_estates]. `priory_of_the_nine` |
| L7 | `vintner_aristocracy` vs `field_hands` [L5.wine_estates] |
| L8 | `julianos_chapel` [P.L8.the_nine]. `crusader_relic_cult`: pilgrims to the Priory [L6.priory_of_the_nine] |
| L9 | `wine_feud` [L7.vintner_aristocracy]. `hidden_count`: the town shields its count from questions [L10.vampire_count] |
| L10 | `vampire_count` (rumour, never said aloud). `name_vault_under_the_vines` (lore web cyr_1) [L1.deep_ayleid_vault] |
| L11 | `knightly_order_of_the_nine`. `ayleid_name_vaults` |

#### Gold Coast (`gold_coast`, 5.6 km², seats Anvil and Kvatch)

**World truth.** The western shore of the Abecean and the hills behind it. Anvil is a bright harbour of white walls
and blue tiles, its fleet and merchants rich on western trade. Up the Gold Road, Kvatch stood on its hill until a gate
opened beneath it in the Crisis: the city burned and its survivors camp below while the rebuilding starts. Between
them run the longest leg of the Gold Road, smugglers' coves, wreckers, a haunted manor and the vampire lairs that
feed on lonely travellers.

| Layer | Fields |
| --- | --- |
| L1 | `low_sea_cliffs`, `sea_caves` [P.L1.gold_coast_cliffs]. `kvatch_hill` |
| L2 | `sea_fog`, `westerly_gales` [P.L2.abecean_storms] |
| L3 | `harbour_springs`, `kvatch_wells` (poisoned in the burning) [L11.kvatch_burned] |
| L4 | `coastal_scrub`, `cliff_grass`, `cove_beach`, `burned_slope` [L11.kvatch_burned], `strid_riverine`. Land dreugh, mudcrabs, seabirds |
| L5 | `anvil_shipping` [P.L5.sea_trade]. `fishing`. `rebuild_trade`: masons and timber flow to Kvatch [L11.kvatch_burned] |
| L6 | `anvil_harbour` [L5.anvil_shipping]. `kvatch_on_the_hill` [L1.kvatch_hill]. `gold_road_inns` (Brina Cross, Gottshaw) [P.L6.inn_chain]. `refugee_camp` below Kvatch |
| L7 | `merchant_wealth` vs `dock_labour` [L5.anvil_shipping]. `kvatch_refugees` [L6.refugee_camp] |
| L8 | `dibella_chapel` (Anvil), `akatosh_chapel` (Kvatch, a field hospital now) [P.L8.the_nine] |
| L9 | `kvatch_rebuild`: who pays and who owns the new wards [L11.kvatch_burned]. `wreckers` dim the lighthouse [L5.anvil_shipping] |
| L10 | `smuggler_coves` [L1.sea_caves]. `haunted_manor`: a merchant house over a sealed crypt (Benirus) [L6.anvil_harbour]. `gate_remnant_under_kvatch` [P.L9.gate_scars] |
| L11 | `kvatch_burned` [P.L11.oblivion_crisis]. `old_pirate_ages` |

#### Nibenay Valley (`nibenay_valley`, 8.2 km², seat Bravil; halves: Bravil and the Lower Niben / the Green Road and the Elsweyr gates)

**World truth.** The river country of the Lower Niben, flat, wet and poor. Bravil is a city of plank houses on mud
and stilts beside the river, the poorest of the nine and the gateway for skooma coming up from Elsweyr. Its people
trust luck more than law and rub the statue of their Lucky Old Lady for it. The valley's caves and riverside ruins
shelter smugglers, necromancers and the crypt where an assassins' cult keeps its mother. The Green Road runs up to the
capital past inns where nobody asks questions.

| Layer | Fields |
| --- | --- |
| L1 | `river_flats` [P.L1.nibenay_alluvium]. `riverbank_caves` |
| L2 | `humid_heat`, `river_fog` [P.L2.east_wet] |
| L3 | `lower_niben_braids` [P.L3.niben]. `flood_meadows`. `bravil_mud`: the city has no clean well; the poor buy water by the bucket [P.L3.who_holds_water] |
| L4 | `reed_marsh`, `flood_meadow`, `willow_carr`, `river_island` [L3.lower_niben_braids]. Mudcrabs, slaughterfish, rats |
| L5 | `river_labour` [P.L5.river_trade]. `skooma` [P.L5.skooma_smuggling]. `eel_and_reed` |
| L6 | `bravil_ferry_city` [L3.lower_niben_braids]. `green_road_inns` (Faregyl, Ill Omen) [P.L6.inn_chain] |
| L7 | `slum_city` [L5.river_labour]. `addiction` [L5.skooma] |
| L8 | `mara_chapel` [P.L8.the_nine]. `lucky_old_lady`: a statue of a saviour mother rubbed for luck. `night_mother_crypt` |
| L9 | `corrupt_clerk`: the count's office is bought [L5.skooma]. `elsweyr_gates` |
| L10 | `skooma_dens`, `smuggler_caves` [L1.riverbank_caves]. `assassins_mother_crypt` [P.L10.assassins_and_thieves] |
| L11 | `alessian_gates_altadoon_mnem`. `river_wars` |

#### Blackwood (`blackwood`, 4.5 km², seat Leyawiin)

**World truth.** Hot, steaming swamp-forest where the Niben meets Topal Bay, Cyrodiil's border with Black Marsh and
Elsweyr. Leyawiin is an Imperial river-mouth city with Argonian and Khajiit quarters outside its proper streets, and
the home of a mercenary company that has made itself rich doing the Fighters Guild's work for less. Rumour says the
company's men drink something stolen from the marsh. Ayleid ruins sink into the mud, vampire lairs and necromancers
hide in the mangroves, and the border forts face a Black Marsh that is no longer afraid of the Empire.

| Layer | Fields |
| --- | --- |
| L1 | `mangrove_mud`, `peat` [P.L1.topal_mudflats]. `sinking_ruins` [P.L1.ayleid_foundations] |
| L2 | `steam_heat`, `thunderstorms` [P.L2.topal_heat] |
| L3 | `niben_mouth`, `panther_river` [P.L3.niben, P.L3.tributaries]. `brackish_tide` |
| L4 | `mangrove`, `cypress_swamp`, `palm_hammock`, `brackish_flat` [L3.brackish_tide]. Mudcrabs, land dreugh, giant snakes, wisps |
| L5 | `river_mouth_port` [P.L5.sea_trade]. `mercenary_trade` [L9.mercenary_company]. `rice_and_reed` |
| L6 | `leyawiin_at_the_mouth` [L3.niben_mouth]. `border_villages` (Water's Edge, Border Watch, Blankenmarch) [L9.marsh_border] |
| L7 | `imperial_core_vs_beastfolk_shanties` [L6.leyawiin_at_the_mouth]. `khajiit_border_village` (Border Watch) |
| L8 | `zenithar_chapel` [P.L8.the_nine]. `argonian_hist_reverence` in the shanties [X: black_marsh.P.L8.hist] |
| L9 | `mercenary_company`: the rival to the Fighters Guild [P.L9.guild_wars]. `marsh_border`: forts face an assertive Black Marsh [P.L9.border_pressure] |
| L10 | `stolen_sap`: the company's men drink stolen Hist sap [L9.mercenary_company]. `mangrove_vampires` (Deepscorn Hollow, Redwater Slough) [L1.mangrove_mud]. `sunken_ayleid` (Nenalata, Malada) [L1.sinking_ruins] |
| L11 | `blackwood_border_wars`. `ayleid_marsh_retreat` |

Cross-province cites (prefix `X:`) are allowed only to another province sheet's field; the lint resolves them across
files (MS22 extension, section 8).

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
| Orange Road | bleakers_way - bruma | exists | Keep Skycraft's naming (SCALE 6.2) |
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
