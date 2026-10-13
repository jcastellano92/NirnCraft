# Hammerfell: province plan

Province plan for Hammerfell under `docs/world/DESIGN-PRINCIPLES.md` (the rulebook) and `docs/world/ILIAC-BAY.md`
(the Daggerfall-based plan for the bay; phases IB1-IB8). The master plan is `docs/world/WORLD-PLAN.md`. High Rock,
the other half of the bay, is `docs/world/provinces/high_rock.md`. The two files cross-reference each other on the
cross-bay ferries, the Betony claim, the Orsinium sieges (the order of Diagna), the knightly orders and the Bjoulsae
bridge.

**Legal line.** Daggerfall, Arena, Redguard and ESO supply facts only: names, relative positions, borders, climate
classes, counts, the building mix of towns and Stros M'kai's site layout. Every description below is our own. No game
text, raster or asset enters the repo. Invented people are ours.

**Era.** Skycraft is set in 4E 201, twenty-odd years after Hammerfell drove the Aldmeri Dominion out on its own. Every
older era is a **history layer** (L11) that must be visible today.

**Scope.** Hammerfell is 25.3 km² in Skycraft today, 0.95 times Skyrim, close to the lore map. Northern Hammerfell (the
bay shore) follows Daggerfall. Southern Hammerfell follows Arena's backbone. Stros M'kai follows Redguard's layout
(`SCALE-AND-GEOGRAPHY.md` 5.5). Stros M'kai sits in this province (region `hammerfell/stros_mkai`). Orsinium is in
`high_rock.md` (region `high_rock/wrothgar`), but its sieges are part of Hammerfell's history too.

**Culture in one glance (what makes Hammerfell not Skyrim and not High Rock).**

| Sense | Hammerfell |
| --- | --- |
| Silhouette | Domes, flat roofs, courtyard houses, watch towers over walled cities with four gates; caravanserais at wells; black-stone terraces in the Dragontail |
| Palette | Sandstone and cobalt tile on the coast, ochre and white lime inland, red clay and green in the jungle valleys, basalt black in the Dragontail |
| Land | Hot and open: palm-and-scrub coast, dune sea, gravel plains, salt flats, wadis, jungle rivers at the bay head, a volcanic mountain wall |
| People | A warrior people whose status comes from the sword as much as from wealth; Crowns and Forebears; nomad tribes; port merchants |
| Sound | Drums and strings, the call of the hour from a tower, wind on sand. Coastal towns are temple-rich and tavern-light (6-12 taverns per city in Daggerfall's data, 2 temples), while jungle river towns are tavern-dense like Breton ones |
| Faith | The Yokudan gods among the Crowns; the Eight with Yokudan faces among the Forebears; the sword as a path; a deep hatred of necromancy |
| Danger | Giant scorpions, sand serpents, harpies on the sea cliffs, giants in the jungle, dragons returning to the Dak'fron dens, Ra Gada war-bands, necromancers |
| Politics | Crowns against Forebears, kings and fief lords, pride after the war, distrust of the Empire, no forgiveness for the Thalmor |

---

## 1. World model sheet

Field ids are `L<n>.<id>`. A cite in brackets names the cause. Province fields are cited from regions as
`hf.L<n>.<id>`. Data home: `tamriel/world/hammerfell.json` (MS22).

### 1.0 Province sheet (`hf`)

**World truth.** Hammerfell is a hot, dry land of desert, rock and sea. The Redguards came to it from sunken Yokuda:
first a wave of warriors who took the land by the sword, then the royal court that followed. Their descendants are the
Forebears and the Crowns, still divided over tradition, trade and the Empire. Water decides everything. Oases and
wells belong to Crown families and desert tribes, the ports belong to Forebear merchant houses, and the caravan roads
pay them both. Swords are a calling and the dead are honoured in great necropolises, so necromancers are hated above
every other criminal. In the last war the Empire gave Hammerfell's land to the Dominion, and Hammerfell won it back
alone.

| Layer | Fields |
| --- | --- |
| L1 Geology | `alikr_basin`: an interior basin of sand sea, gravel plain, red sandstone mesas and closed salt pans. `dragontail_wall`: a mountain wall on the east, black volcanic ridges with obsidian, sulphur and ore in the north (Daggerfall's data: 85 mines, 29 volcanic caves) and red rock cut by slot canyons in the south. `south_shore`: sea cliffs and terraces on the bay's south shore (Sentinel's harbour cliff). `abecean_coast`: cliffs, arches and headlands on the southern sea. `bay_head_valleys`: red-clay river valleys at the bay head between the Dragontail and the Wrothgarian foothills. `craglorn_badlands`: eroded badlands with star-fall craters. `stros_island`: a two-lobed island with a high western plateau |
| L2 Climate | `dry_interior` (cites L1.alikr_basin): heat and very little rain. `coast_shadow` (cites L1.south_shore): coastal hills take the sea rain, so the Alik'r beyond them is dry. `winter_rains` (cites L1.south_shore): the coast gets mild winter rain. `bay_lift` (cites L1.bay_head_valleys, L1.dragontail_wall): moist bay air is forced up against the foothills, so the bay head is the only rainforest in the province. `sandstorms` (cites L1.alikr_basin, L2.dry_interior). `mountain_cold` (cites L1.dragontail_wall). `abecean_storms` (cites L1.abecean_coast) |
| L3 Water | `oases_and_wells` (cites L1.alikr_basin): water exists only at oases, wells and underground channels. `water_is_power` (cites L3.oases_and_wells): every oasis belongs to a Crown family or to the tribe that holds its well, and caravans pay at each one. In the cities the palace owns the cisterns. `wadis` (cites L2.winter_rains, L1.dragontail_wall): dry beds that flood after rain. `jungle_rivers` (cites L2.bay_lift): perennial rivers at the bay head (Lainlyn, Totambu). `snowmelt` (cites L2.mountain_cold): Dragontail streams feed the wadis |
| L4 Ecology | `mosaic` (cites L1-L3): see section 2. `scorpions` (cites L1.alikr_basin; Daggerfall's data: 21 scorpion nests in the Alik'r, 22 in Dak'fron). `sand_serpents` (cites L2.sandstorms). `coast_harpies` (cites L1.abecean_coast; Daggerfall's harpy nests cluster on the Abibon coast). `jungle_giants` (cites L2.bay_lift; 19 giant strongholds in Totambu). `returning_dragons` (cites L11.dragon_dens): dragons back in the hot-desert dens. `vent_daedra` (cites L1.dragontail_wall): fire spirits in the volcanic caves |
| L5 Economy | `caravans_and_salt` (cites L3.water_is_power, L1.alikr_basin). `dyes_and_spice` (cites L2.winter_rains): Sentinel's terraces and port. `dragontail_ore` (cites L1.dragontail_wall): iron, obsidian and sulphur. `blades` (cites L5.dragontail_ore, L8.sword_path): swordsmiths in every city. `timber_and_river_fish` (cites L3.jungle_rivers). `oasis_dates` (cites L3.oases_and_wells). Who gets rich: Crown families (oases and caravan tolls), Forebear merchant houses (ports and shipping) and the ore lords of the Dragontail |
| L6 Settlement | `harbour_and_oasis_cities` (cites L3.oases_and_wells, L1.south_shore). `caravanserais_at_wells` (cites L5.caravans_and_salt). `jungle_river_ports` (cites L3.jungle_rivers). `mining_terraces` (cites L5.dragontail_ore). `busy_centres` (cites L5.dyes_and_spice, L5.caravans_and_salt): Sentinel (capital, port and caravan terminus), Totambu (the river port), Taneth and Rihad. `failing_edges` (cites L4.returning_dragons, L4.scorpions): Dak'fron's wells, Satakalaam's scorpion fields and the Antiphyllos dust |
| L7 Society | `crowns_and_forebears` (cites L11.two_waves): the old royal nobility against the integrated warrior-merchant class. `sword_rank` (cites L8.sword_path): a sword-master (Ansei) is honoured even when poor. `nomads` (cites L3.water_is_power): desert tribes outside the cities, proud and armed. `stained_hands` (cites L5.dyes_and_spice, L5.caravans_and_salt): dyers, salt-cutters and dockers are the poor. `debt_miners` (cites L5.dragontail_ore): miners bound by debt in the Dragontail. `outcasts` (cites L8.hate_necromancy): necromancers are hunted, and foreigners are judged by deeds |
| L8 Culture | `yokudan_gods` (cites L11.two_waves): the Crowns keep the old gods (Satakal, Ruptga, Tu'whacca, Onsi, Zeht, Morwha and the HoonDing). `eight_with_yokudan_faces` (cites L7.crowns_and_forebears): the Forebears worship the Divines with Yokudan names (Sentinel's patron temple is Arkay's, the keeper of the dead, close to Tu'whacca). `sword_path` (cites L11.two_waves): the sword as a spiritual discipline (the sword-singers). `hate_necromancy` (cites L8.yokudan_gods): the dead belong to Tu'whacca. `necropolis_dead` (cites L8.yokudan_gods, L1.alikr_basin): the dead lie in great tiered tombs. `distrust_empire` (cites L11.great_war). `hate_thalmor` (cites L11.great_war) |
| L9 Politics | `crown_forebear_rift` (cites L7.crowns_and_forebears, L8.distrust_empire): how to deal with the Empire, and who speaks for Hammerfell. `kings_and_lords` (cites L6.harbour_and_oasis_cities): city kings over fief lords. `betony_claim` (cites L11.war_of_betony): Sentinel against Daggerfall (`high_rock.md`). `collaborators` (cites L11.great_war): those who opened gates to the Dominion are hunted. `ra_gada_bands` (cites L7.sword_rank): free warrior-bands camp on the roads. `well_feuds` (cites L3.water_is_power) |
| L10 Hidden | `covens` (6 on the Hammerfell shore). `bloodlines` (cites L9.kings_and_lords): Selenu (Sentinel coast), Anthotis (desert), Khulari (mountains and jungle), Montalion (bay head). `necropolises` (cites L8.necropolis_dead). `defiled_temples` (cites L8.yokudan_gods; Daggerfall's data: 26 desecrated temples in the Alik'r, 24 in Totambu): cults in the old gods' temples. `dwemer_halls` (cites L1.alikr_basin, L1.stros_island): Volenfell, Bthzark. `lava_tubes` (cites L1.dragontail_wall). `smugglers_coves` (cites L1.abecean_coast). `necromancers` (cites L8.hate_necromancy): hidden labs (Mournoth 7 in Daggerfall's data) |
| L11 History | `before_the_redguards`: Nedic peoples, Orcs, beastfolk and the Dwemer held the land. `two_waves`: the warrior wave from sunken Yokuda took the land by the sword, and the royal court followed; the name Hammerfell and the sword-saint Hunding belong to this time. `orsinium_siege`: Redguard warriors of the order of Diagna helped besiege the Orc city (`high_rock.md`). `covenant_era`. `first_treaty`: in the late Second Era, after the Crowns' fleet was broken at Hunding Bay, a treaty signed on Stros M'kai made Hammerfell an Imperial province. `war_of_betony`. `warp_in_the_west`. `dragon_dens`: old dens in the hot desert, empty for an age. `great_war`: the Empire ceded southern Hammerfell to the Dominion, Hammerfell fought on alone and won, and a second treaty was signed on Stros M'kai |

### 1.1 `hammerfell/sentinel` (new realm, 1.15 km²)

Fiefs: **Sentinel** (Arkay, Order of the Candle, Selenu), **Tigonus** (Julianos, Witches of the Marsh, Anthotis),
**Ayasofya** (Mara, Selenu), **Antiphyllos** (seat Jalallaara, Mara, the Dust Witches, Anthotis), **Bergama**
(Akatosh Chantry, Anthotis), **Myrkwasa** (Kynareth, Selenu), **Pothago** (Julianos, Selenu), **Kairou** (Z'en,
Selenu), **Abibon-Gora** (Dibella, Knights of the Wheel, Order of the Cup, Selenu). **Cybiades** (0.002 km²) folds
into Sentinel as an islet.

**World truth.** A capital of domes on a harbour cliff, ruling a coast of palm scrub and terraced gardens and a desert
hinterland of salt flats. Sentinel's wealth is dye, spice and the end of the Alik'r caravan road. The king holds the
cisterns, so he holds the city. The court is split between Crown nobles in the palace and Forebear merchants on the
quays, and the Betony claim against Daggerfall is a weapon both sides use. The coast fiefs to the south-west are
harpy country, and the Witches of the Marsh rule the reeds of Tigonus.

| Layer | Fields |
| --- | --- |
| L1 | `harbour_cliff` (hf.L1.south_shore). `garden_terraces`: terraced slopes behind the city. `tigonus_lowland`: a low marshy shore east of the city. `abibon_coast` (hf.L1.abecean_coast): harpy cliffs south-west. `bergama_pans` (hf.L1.alikr_basin): salt flats inland |
| L2 | `winter_rain_coast` (hf.L2.winter_rains). `marsh_heat` (L1.tigonus_lowland). `inland_dry` (hf.L2.coast_shadow) |
| L3 | `palace_cisterns` (hf.L3.water_is_power): the palace stores the winter rain; the city drinks by the king's leave. `wadi_river`: a wadi river through Tigonus to the bay (cites hf.L3.wadis). `terrace_channels` (L1.garden_terraces) |
| L4 | `coastal_maquis`, `terrace_gardens` (L2.winter_rain_coast). `tigonus_marsh` (L1.tigonus_lowland): reeds, harpies and scorpions (Daggerfall's data: 12 and 11). `harpy_cliffs` (L1.abibon_coast). `salt_flat` (L1.bergama_pans) |
| L5 | `dye_works` (L4.terrace_gardens, hf.L5.dyes_and_spice). `spice_port`. `caravan_terminus` (hf.L5.caravans_and_salt). `salt_pans` (L1.bergama_pans). Product: cobalt and saffron dyes, spice, Bergama salt, terrace oil |
| L6 | `dome_capital` (L1.harbour_cliff, L3.palace_cisterns). `coast_fief_keeps` (L1.abibon_coast): six lords' seats down the coast. `marsh_town` (L3.wadi_river): Tigonus. `ferry_head`: ferries to Daggerfall and Stros M'kai |
| L7 | `palace_crowns`, `quay_forebears` (hf.L7.crowns_and_forebears). `dyers_quarter` (L5.dye_works, hf.L7.stained_hands): blue-handed workers in the lower town |
| L8 | `arkay_patron` (hf.L8.eight_with_yokudan_faces). `marsh_witch_fear` (L4.tigonus_marsh) |
| L9 | `court_rift` (hf.L9.crown_forebear_rift). `betony_lever` (hf.L9.betony_claim). `candle_order`: the Order of the Candle guards the king |
| L10 | `cistern_undercroft` (L3.palace_cisterns): the palace undercroft is the realm's largest dungeon in Daggerfall's data. `witches_of_the_marsh`, `dust_witches`. `selenu_coast` (hf.L10.bloodlines). `coven_of_the_tide` |
| L11 | `lysandus_war` (hf.L11.war_of_betony): Sentinel's army killed a king of Daggerfall. `harbour_of_the_first_landing` (hf.L11.two_waves) |

### 1.2 `hammerfell/alikr_desert` (existing; large, about 9 km² after IB2; two halves)

Fiefs in the window: **Alik'r Desert** (seat Berbaaqnia, Stendarr, house Alik'ra, Anthotis), **Dak'fron** (seat
Pheonahator, Stendarr, the Sisters of Kykos, Anthotis). South of the window: Skaven, Hegathe and Gilane (Arena
backbone). Plan as **Alik'r North** (Berbaaqnia and Dak'fron) and **Alik'r South** (Skaven, Hegathe and the Abecean
shore with Gilane).

**World truth.** The heart of Hammerfell: a sea of dunes, gravel plains and red mesas. Wealth runs through wells
along the caravan road, and every well is owned. Crown families hold the great oases, nomad tribes hold the hidden
wells, and caravans pay both. Berbaaqnia is a walled caravanserai city. The hot Dak'fron plain has old dragon dens
that were empty for an age and are not empty now. Under the sand lie Yokudan tombs, a buried royal city and a Dwemer
hall half full of drift.

| Layer | Fields |
| --- | --- |
| L1 | `erg` (hf.L1.alikr_basin): dune sea, crests y 70-130. `mesas`: red mesas to y 200. `reg_plain`: gravel plain. `dakfron_hot_plain`. `buried_valleys`: old valleys under the sand |
| L2 | `sandstorms` (hf.L2.sandstorms). `hot_dakfron` (L1.dakfron_hot_plain). `cold_nights` (hf.L2.dry_interior) |
| L3 | `owned_wells` (hf.L3.water_is_power). `hidden_wells`: tribal wells known only to the tribes. `qanat_lines` (L1.buried_valleys): underground channels from the foothills to the oases |
| L4 | `erg_dunes`, `reg_plain`, `oasis_grove`, `wadi_bed`, `mesa_and_hoodoo` (L1). `scorpion_ground` (hf.L4.scorpions). `serpent_sands` (hf.L4.sand_serpents). `dragon_dens` (hf.L4.returning_dragons) |
| L5 | `caravan_tolls` (L3.owned_wells, hf.L5.caravans_and_salt). `salt_trade`. `oasis_dates` (hf.L5.oasis_dates). Product: rock salt, dates, desert horses' tack, Alik'r blades |
| L6 | `caravanserai_city` (L3.owned_wells): Berbaaqnia. `well_towns` (L3.qanat_lines): Pheonahator, Bubyrydata. `south_coast_cities`: Skaven, Hegathe and Gilane on the Arena road |
| L7 | `crown_oasis_lords` (hf.L7.crowns_and_forebears). `nomad_tribes` (hf.L7.nomads). `caravan_men` |
| L8 | `yokudan_gods` (hf.L8.yokudan_gods). `stendarr_patron`. `well_oaths`: a sworn oath at a well cannot be broken (cites L3.owned_wells) |
| L9 | `well_feud` (hf.L9.well_feuds): a Crown lord and a tribe claim the same well. `dragon_fear` (L4.dragon_dens). `ra_gada_war_camps` (hf.L9.ra_gada_bands) |
| L10 | `na_totambu_city` (`lost_city_of_the_na_totambu`). `volenfell` (hf.L10.dwemer_halls). `web_of_anker` (L4.scorpion_ground). `faughtas_guard` (a defiled temple keep). `kykos_coven`. `defiled_temples` (hf.L10.defiled_temples) |
| L11 | `two_waves` (hf.L11.two_waves). `dragon_dens` (hf.L11.dragon_dens). `caravan_wars` |

### 1.3 `hammerfell/bangkorai` (existing, re-cut to the bay-head valleys; 1.45 km² in the window)

Fiefs: **Lainlyn** (Dibella, Host of the Horn, Montalion), **Kozanset** (Kynareth, Khulari), **Totambu** (Z'en,
Order of the Scarab, Tide Witches, Khulari), **Satakalaam** (Dibella, Montalion), **Mournoth** (Stendarr,
Montalion), **Ephesus** (Mara, Khulari, mountain), **Santaki** (Stendarr, Knights of the Hawk, mountain).

**World truth.** The only green, wet corner of Hammerfell: red-clay river valleys under jungle at the bay head, with
mountain fiefs above them. River ports trade timber, fish and cargo from the Bjoulsae bridge, and their towns are
tavern-crowded and half Breton in manner. Totambu carries the name of the old royal line, which makes it sacred to
the Crowns. Its timber lords and the Order of the Scarab are pushing into groves where giants live. Orc bands left
over from old wars hold strongholds in the hills. The old Bangkorai pass, with its garrison, still guards the land
road between High Rock and Hammerfell.

| Layer | Fields |
| --- | --- |
| L1 | `red_clay_valleys` (hf.L1.bay_head_valleys). `ephesus_heights`, `santaki_ridge` (hf.L1.dragontail_wall): mountain fiefs. `bangkorai_pass` |
| L2 | `bay_rain` (hf.L2.bay_lift). `cloud_line` (L1.ephesus_heights): mist on the heights |
| L3 | `lainlyn_river`, `totambu_river` (hf.L3.jungle_rivers). `floods` (L2.bay_rain): the rivers flood every wet season |
| L4 | `river_jungle` (L2.bay_rain). `red_clay_banks` (L3.floods). `cloud_forest` (L2.cloud_line). `giant_glades` (hf.L4.jungle_giants). `river_mouth_mangrove` |
| L5 | `timber` (L4.river_jungle, hf.L5.timber_and_river_fish). `river_trade` (L3.lainlyn_river). `bridge_traffic` (`high_rock.md` section 4). Product: hardwood, river fish, jungle resin, Lainlyn horn work |
| L6 | `river_port_city` (L3.totambu_river): Totambu. `ford_towns` (L3.lainlyn_river): Lainlyn, Mournoth. `mountain_keeps` (L1.ephesus_heights): Ephesus, Santaki. `garrison_pass` (L1.bangkorai_pass) |
| L7 | `timber_lords` (L5.timber). `mixed_towns` (L5.bridge_traffic): Breton and Redguard families side by side. `river_labour` |
| L8 | `z_en_patron`. `na_totambu_reverence` (hf.L8.yokudan_gods): Crown pilgrims come to Totambu. `giant_taboo`: the old families do not cut the giants' groves |
| L9 | `grove_push` (L5.timber, L8.giant_taboo): timber lords and the Scarab against the taboo. `river_pirates` (L5.river_trade; 10 prisons in Lainlyn in Daggerfall's data). `forebear_ports` (hf.L9.crown_forebear_rift) |
| L10 | `scarab_temple` (hf.L10.defiled_temples): a drowned river temple. `orc_hill_bands` (21 Orc strongholds in Totambu in Daggerfall's data). `mournoth_labs` (hf.L10.necromancers). `tide_witches`. `khulari_heights` |
| L11 | `na_totambu_line` (hf.L11.two_waves). `garrison_pass_wars`: the pass changed hands in every war between the provinces |

### 1.4 `hammerfell/dragontail` (existing; about 5.7 km², two halves)

Fief in the window: **Dragontail Mountains** (seat Dragontail, Arkay, house Dragontail, Khulari, the Witches of
Devilrock, the Mountain Witches, Scourg Barrow). South of the window: Rihad and the road to Kvatch. Plan as
**Dragontail North** (black volcanic ridges and the mining city) and **Dragontail South** (red-rock wall, slot canyons
and Rihad).

**World truth.** The mountain wall between Hammerfell and Cyrodiil: black volcanic ridges in the north, red rock and
slot canyons in the south. Its ore and obsidian arm every swordsmith in the province, and its miners are bound by debt
to the lords of Dragontail city, a terraced town of black stone. Fumaroles steam in the high valleys, and fire
spirits walk in the lava tubes. The Devilrock witches trade fire-pacts. Scourg Barrow, the greatest tomb in the
mountains, is said to be waking.

| Layer | Fields |
| --- | --- |
| L1 | `black_ridges` (hf.L1.dragontail_wall): basalt, obsidian, sulphur, y 280-420. `red_wall`: a red-rock wall and slot canyons in the south, y 300-470. `ore_seams` |
| L2 | `mountain_cold` (hf.L2.mountain_cold). `fume_haze` (L1.black_ridges). `canyon_shade` (L1.red_wall) |
| L3 | `snowmelt` (hf.L3.snowmelt). `canyon_springs` (L2.canyon_shade). `sulphur_springs` (L1.black_ridges) |
| L4 | `volcanic_scree`, `fumarole_field` (L1.black_ridges). `canyon_fern` (L3.canyon_springs). `cedar_ridge` (L2.mountain_cold). `vent_daedra` (hf.L4.vent_daedra) |
| L5 | `mines` (L1.ore_seams; 85 in Daggerfall's data). `obsidian_and_sulphur`. Product: iron, obsidian blades, sulphur, mountain cedar |
| L6 | `terrace_city` (L5.mines): Dragontail. `mine_villages` (L5.mines): Morenanu. `pass_to_kvatch` (L1.red_wall): Rihad's road over the wall |
| L7 | `ore_lords`. `debt_miners` (hf.L7.debt_miners) |
| L8 | `arkay_patron`. `fire_pacts` (L4.vent_daedra): the Devilrock coven trades with fire spirits |
| L9 | `debt_unrest` (L7.debt_miners). `arms_for_both` (hf.L9.crown_forebear_rift): both factions buy Dragontail steel |
| L10 | `scourg_barrow`. `devilrock_coven`, `mountain_witches`. `lava_tubes` (hf.L10.lava_tubes). `khulari_mines` (hf.L10.bloodlines) |
| L11 | `scourg_kings`. `imperial_border` (hf.L11.first_treaty): the wall was the Empire's way in |

### 1.5 `hammerfell/stros_mkai` (existing, 0.7 km², small; Redguard layout)

**World truth.** A small, two-lobed island in the southern sea. A harbour town (Port Hunding) climbs the east side of
a south-facing bay toward a walled palace plateau, and a high western plateau holds a Dwemer ruin. Both treaties that
bound Hammerfell to the Empire were signed here: the first after the Crowns lost at Hunding Bay, the second after
Hammerfell beat the Dominion. That makes the island neutral ground that both Crowns and Forebears covet. Smugglers use
the cave behind the waterfall. A necromancer's tower stands on an islet off the north-west coast, and the islanders do
not row there.

| Layer | Fields |
| --- | --- |
| L1 | `two_lobes` (hf.L1.stros_island): east lobe (town and palace plateau), west lobe (high plateau). `harbour_bay`: a south-facing bay. `waterfall_cliff` north-west of the town. `south_point`. `necromancer_islet`: off the north-west coast |
| L2 | `sea_climate` (hf.L2.abecean_storms): warm, wet, storm-prone. `plateau_wind` (L1.two_lobes) |
| L3 | `waterfall_stream` (L1.waterfall_cliff). `palace_cistern` (hf.L3.water_is_power) |
| L4 | `island_palm_coast` (L2.sea_climate). `jungle_hills`: the east hills (Redguard's jungle camp). `plateau_scrub` (L2.plateau_wind). `reef_shallows` |
| L5 | `harbour_trade` (L1.harbour_bay). `dwemer_salvage` (L10.bthzark). `shipwrights` |
| L6 | `port_hunding` (L1.harbour_bay): town on the bay's east shore, warehouse and harbour tower on the west quay. `palace_plateau`. `lighthouse_point` (L1.south_point) |
| L7 | `harbour_lord_and_merchants`. `sailors_and_smugglers` (L5.harbour_trade) |
| L8 | `hunding_memory` (hf.L11.two_waves): the sword-saint's name on the port. `fear_of_the_islet` (hf.L8.hate_necromancy). `treaty_pride` |
| L9 | `treaty_ground` (hf.L11.first_treaty, hf.L11.great_war): neutral ground both factions want. `smuggling_ring` |
| L10 | `catacombs` under the town centre (entrance by the bell tower). `waterfall_hideout`. `ngastas_isle` (hf.L10.necromancers). `bthzark` (hf.L10.dwemer_halls) on the western plateau |
| L11 | `hunding_bay_battle`. `first_treaty`, `second_treaty`. `garrison_rising`: an uprising against the Imperial garrison in the late Second Era |

### 1.6 Frame regions outside the window

| Region | L1-L3 (land, sky, water) | L4-L6 (life, work, towns) | L7-L9 (people, faith, power) | L10-L11 (hidden, history) |
| --- | --- | --- | --- | --- |
| `hammerfell/craglorn` (2.3 km²) | Eroded badlands and star-fall craters (hf.L1.craglorn_badlands), high and dry | Badland scrub, crater glass; Elinhir (granite tiers, moves to about -3950, 3800), Dragonstar (arena city) | Star-watchers; the arena's patrons and gladiators; the Celestials as old powers | Nedic ruins, the `aetherian_archive`, `skyreach_pinnacle`, `sanctum_ophidia`, `valley_of_scars`; lore-web ham_2 |
| `hammerfell/hews_bane` (3.9 km²) | Abecean headlands and coves (hf.L1.abecean_coast) | Cliff terraces, pool gardens; Taneth (`cliff-palace-terraces`), `abahs_landing` (stilt harbour) | Taneth fell to the Dominion in the war and its collaborators are still hunted (hf.L9.collaborators; the Alik'r hunters in Skyrim's Whiterun are part of this story); thieves at the Landing | `ahaleb_necropolis`, `thrall_cove`, `hubalajad_palace`; lore-web ham_3 |

---

## 2. Micro-biomes (MS21)

Each realm needs at least 6 micro-biomes and at least 2 per km².

| Micro-biome | Where | Rule sketch | Cause | Effect (flora, weather, creatures, work) |
| --- | --- | --- | --- | --- |
| `coastal_maquis` (biome `iliac_scrub`) | Sentinel coast, Abibon | LOWLAND, coast distance < 400, rain shadow < 0.5 | sentinel.L2.winter_rain_coast | Palms, olive-green scrub, ochre soil; winter showers; lizards, goats; herders, oil presses |
| `terrace_gardens` | Behind Sentinel, Myrkwasa | UPLAND, slope 0.2-0.5, near a settlement | sentinel.L1.garden_terraces | Dye plants, fig and pomegranate rows, channels; bees; dyers' gardens |
| `harpy_sea_cliffs` | Abibon, Kairou, Ayasofya | UPLAND, coast distance < 100, slope > 0.6 | sentinel.L1.abibon_coast | Bare red cliff, nest ledges; sea spray; harpies; none |
| `tigonus_marsh` | Tigonus | MARSH, warm | sentinel.L1.tigonus_lowland | Reed beds, mangrove edge, warm channels; humid haze; harpies, scorpions, mudcrabs; reed cutters, witches |
| `erg_dunes` (biome `alikr_dunes`) | Alik'r | LOWLAND inside the erg polygon | alikr.L1.erg | Bare sand, star and crescent dunes; sandstorms; sand serpents; none |
| `reg_plain` | Alik'r, Antiphyllos | LOWLAND, low relief, outside the erg | alikr.L1.reg_plain | Gravel, thorn bushes; heat shimmer; scorpions, jackals; caravan roads |
| `salt_flat` | Bergama, Alik'r basins | LOWLAND, closed basin | sentinel.L1.bergama_pans | White crust, mirages; glare; none; salt cutters |
| `oasis_grove` | At wells and qanat heads | Within 60 of a well place | hf.L3.oases_and_wells | Date palms, reeds, pools; cooler air; birds; gardeners, caravanserais |
| `wadi_bed` | Alik'r edges, Dragontail foothills | RIVER, seasonal | hf.L3.wadis | Tamarisk, acacia, cracked mud; flash floods after rain; foxes; seasonal fields |
| `mesa_and_hoodoo` | Alik'r, Craglorn, Dragontail South | UPLAND, relief > 40 within 64 | alikr.L1.mesas | Red stone pillars, bare caps; wind; vultures; lookouts |
| `cold_mesa_top` | High mesas | PEAK in the Alik'r | hf.L2.dry_interior | Lichen, wind-polished stone; frost at night; none; star-watchers |
| `river_jungle` (biome `iliac_rainforest`) | Totambu, Lainlyn | LOWLAND, river distance < 300, rain index high | bangkorai.L2.bay_rain | Broadleaf canopy, vines; rain, steam; parrots, snakes; loggers |
| `red_clay_banks` | Bangkorai rivers | RIVER banks | bangkorai.L3.floods | Red mud, roots, reeds; mosquito haze; river fish; potters, fishers |
| `cloud_forest` | Ephesus, Santaki heights | UPLAND y 140-220 in Bangkorai | bangkorai.L2.cloud_line | Mossy trees, ferns; constant mist; orchids, birds; herb gatherers |
| `giant_glade` | Totambu interior | Clearing inside `river_jungle` | hf.L4.jungle_giants | Huge old trees, trampled ground; quiet; giants and their herds; none (taboo) |
| `volcanic_scree` | Dragontail North | UPLAND/PEAK in the black ridges | dragontail.L1.black_ridges | Black scree, obsidian glints; ash haze; fire spirits; obsidian pickers |
| `fumarole_field` | Dragontail high valleys | Heat raster > 0.6 | dragontail.L1.black_ridges | Steaming vents, sulphur crust; fumes; none; sulphur workers |
| `canyon_shade` | Dragontail South | RIVER in steep relief | dragontail.L2.canyon_shade | Ferns, seep springs, shade; cool; bats, lizards; hermits |
| `cedar_ridge` | Dragontail heights | UPLAND y > 260 | dragontail.L2.mountain_cold | Cedar and juniper; cold wind; goats; woodcutters |
| `starfall_crater` | Craglorn | Crater polygons | hf.L1.craglorn_badlands | Fused glass, odd crystals; eerie light at night; strange beasts; scavengers |
| `island_palm_coast` | Stros M'kai | BEACH/LOWLAND on the island | stros.L2.sea_climate | Palms, white sand, reefs; squalls; seabirds; fishers |

Counts: Sentinel 7, Alik'r 7 (plus the coastal kinds in the south half), Bangkorai 6 (river jungle, red clay banks,
cloud forest, giant glade, river-mouth mangrove, upland scrub on Santaki), Dragontail 6 (volcanic scree, fumarole
field, canyon shade, cedar ridge, mesa and hoodoo, mine spoil round the terraces), Stros M'kai 6 (palm coast, jungle
hills, plateau scrub, reef shallows, waterfall gorge, cove strand). The large Alik'r (about 9 km²) meets the per-km²
rule by repeating these kinds in both halves. If MS21 counts distinct kinds, IB2 adds dune-slack wet hollows, a
dry-lake bed, a qanat garden line and a black-sand coast in the south half.

---

## 3. Map changes and geography

Follows `ILIAC-BAY.md` sections 2 and 10 for the bay shore, and `SCALE-AND-GEOGRAPHY.md` 5.5 for the south and Stros
M'kai.

**Frame (IB1).**

* Apply the transform, plus the **bay-opening hinge**. Hammerfell land turns rigidly by -23.3 degrees about the land
  bridge east of Wayrest (block -4835, 3613), so every distance on the Hammerfell shore is Daggerfall's own and only
  the distances across the bay grow.
* Sentinel moves 224 blocks east to (-7976, 4900), its binding z unchanged.
* Move the Daggerfall-era towns to their Daggerfall positions. Examples: Totambu from (-8350, 6300) to (-5124, 4211),
  Satakalaam from (-6850, 6250) to (-5405, 3891), Kozanset from (-7150, 4700) to (-6151, 4333), Lainlyn by 197 blocks
  and Abibon-Gora by 513.
* **Elinhir** moves to about (-3950, 3800), into Craglorn proper, clear of Mournoth.
* **Gilane** falls in Daggerfall's open sea. The two planning docs disagree on where it goes. ILIAC 2.4 says the coast
  south of the window at about (-9600, 6500). `SCALE-AND-GEOGRAPHY.md` 5.5 says the Abecean shore near (-8650, 6850),
  about 550 west of Hegathe. **Recommendation: (-8650, 6850)**, because it keeps Arena's Hegathe-Gilane-Taneth coast
  chain. IB1 confirms the point is on land. Update `TAMRIEL.md` section 9 (Sentinel x, Elinhir, Gilane) in the same
  commit.
* Stros M'kai keeps its polygon. Its sites move to the Redguard layout (scaled at 0.021 blocks per unit):

| Site | Move to | Today |
| --- | --- | --- |
| Port Hunding town (east shore of the bay); harbour tower and warehouse on the west quay | -10200..-9980, 7240..7370; quay about -10200, 7310 | `port_hunding` at -10150, 7260 (keep) |
| Palace on a walled plateau north-east of the town | Gate -10060, 7210; grounds -9990, 7070 | None: add a palace landmark on a plateau 15-20 blocks up |
| Catacombs under the town centre, entrance by the bell tower | -10120, 7310 | None: add |
| Hideout cave behind a waterfall | -10240, 7220 | `nuset_hideout` at -10318, 6856 |
| Observatory in the centre-south hills | -10240, 7500 | None: add |
| Lighthouse on the south point | -10160, 7630 | `stros_mkai_lighthouse` at -9950, 7050 |
| Dwemer ruin on the high western plateau | -10580, 7520..7580 | `bthzark` at -10450, 7000 |
| Necromancer's islet off the north-west coast, by boat | New islet about -10850, 6950 | `ngastas_tower` at -10300, 7480 |
| Rope-plank causeway on the north coast | -10490, 6990 | None: add landmark |

Island relief follows Redguard's levels. The western plateau is highest, the palace plateau and town terraces sit in
the middle and the harbour is low.

**Realms and biomes (IB2).**

* Create the new `sentinel` region.
* Re-cut `bangkorai` to the bay-head valleys (Daggerfall's rainforest fiefs plus Ephesus and Santaki). The old pass
  and garrison stay as its north-east edge.
* Shrink `alikr_desert` and `dragontail` where the new realms take their land.
* Add three new biomes: `iliac_scrub`, `iliac_rainforest` and `tigonus_marsh`.
* Add the 19 Hammerfell fiefs to `fiefs.json`.

**Relief (IB6).** The province target is p50 27 -> 35, p90 98 -> 150 and ruggedness 2.5 -> 4.5. Each feature below is
authored as vectors with heights we choose:

| Feature | Vector work | Heights | Why (sheet) |
| --- | --- | --- | --- |
| Sentinel harbour cliff and terraces | Cliff band round a harbour basin, terrace `areas` behind | Cliff y 90-110, terraces y 110-150 | sentinel.L1.harbour_cliff, garden_terraces |
| Iliac south-shore cliffs | Cliffed coast with coves from Sentinel to Kairou; sea arches on the Abecean side | y 80-120 | hf.L1.south_shore, abecean_coast |
| Alik'r dune sea | An `areas` erg polygon with dune ridges perpendicular to the prevailing wind (dune crests 20-60 above a y 70 floor) | y 70-130 | alikr.L1.erg |
| Alik'r mesas | 6-10 mesa `areas` with flat caps and cliff sides, hoodoo fields at the edges | Caps to y 200 | alikr.L1.mesas |
| Salt flats | Closed-basin `lakes` marked dry, with a white surface | y 66-70 | sentinel.L1.bergama_pans |
| Wadis | `rivers` with a seasonal flag, braided beds from the Dragontail foothills | Cut 4-10 deep | hf.L3.wadis |
| Dragontail black ridges | `ranges` spine with jagged crests, fumarole `areas`, 2-3 lava-tube mouths | y 280-420 | dragontail.L1.black_ridges |
| Dragontail red wall | `ranges` spine with slot canyons as narrow `rivers` | y 300-470 | dragontail.L1.red_wall |
| Bay-head valleys | Wide river valleys between hills, flood plains | Floors y 66-80, hills y 120-180 | bangkorai.L1.red_clay_valleys |
| Ephesus heights | A mountain block over the jungle | y 180-260 | bangkorai.L1.ephesus_heights |
| Craglorn badlands | Eroded badland `areas`, star-fall craters as round depressions with rims | y 100-200 | hf.L1.craglorn_badlands |

**Rivers (IB6).** The Lainlyn and Totambu rivers and the Sentinel wadi river through Tigonus reach the bay. Together
with High Rock's four, that gives the bay at least six named rivers (ILIAC gate). Falls come where they leave the
Dragontail foothills and the Ephesus heights. Names other than those already in lore are ours, coined in IB2.

**Seams.** The Dragontail continues south to Rihad and the Kvatch pass. The Alik'r erg continues south toward Skaven.
The Bjoulsae bridge joins the High Rock shore.

---

## 4. Roads, travel and fast travel

**Topology (IB5).** Named roads come first, then the greedy spanner. DFO's `city_graph.json` only breaks ties. South
of the window, Arena's backbone is the topology.

| Named road | Class | Route (place ids) | Note |
| --- | --- | --- | --- |
| South Shore Road | highway | sentinel, tigonus, kozanset, lainlyn, Bjoulsae bridge, wayrest | `bridge` 160; the only land link across the bay head |
| Alik'r Caravan Road | road | sentinel, berbaaqnia, pheonahator, skaven | Caravanserais at wells; the caravan route |
| Abibon Coast Road | road | sentinel, myrkwasa, pothago, kairou, abibon_gora | Harpy cliffs |
| Jungle River Road | road | lainlyn, totambu, satakalaam, mournoth, elinhir | Ford towns, a ferry where the river is wide |
| Dragontail Pass | road | totambu, dragontail, rihad | Passes the `bangkorai_garrison` fort |
| Abecean Coast Road (Arena) | highway | sentinel, hegathe, gilane, taneth, rihad | Southern spine |
| Inland Road (Arena) | road | sentinel, skaven, dragonstar, elinhir | Crosses the Alik'r south half |
| Rihad-Elinhir (Arena) | road | rihad, elinhir | Along the Dragontail's west foot |
| Falkreath-Elinhir (cross-province) | highway | elinhir to falkreath through Craglorn | `SCALE-AND-GEOGRAPHY.md` 6.2 |
| Kvatch-Rihad (cross-province) | highway | rihad over the Dragontail to kvatch | Ditto |

Dragonstar sits at (-4100, 2550) in the north-east, so Arena's Dragonstar-Skaven link is a long desert road. IB5 keeps
it only if `reach_lint` and the corridor gate pass; otherwise Dragonstar links to Elinhir only.

**Ships (IB5).** The Iliac ferry ring runs Sentinel-Daggerfall, Wayrest-Lainlyn and Sentinel-Abibon-Gora. The
Abecean line runs Sentinel-Stros M'kai, Stros M'kai-Gilane and Gilane-Taneth. The `sentinel`, `stros_mkai` and
`taneth` ports already exist in `ports.json`. Stros M'kai is reached only by sea, and arriving by boat into the
harbour bay is the island's first moment.

**Caravans (MS11, Hammerfell's second mode).**

* Caravan masters wait at the caravanserais of Sentinel, Berbaaqnia, Pheonahator, Skaven and Hegathe.
* The trip is a **walked journey at high time speed**, the Daggerfall-bible lesson: fast travel should not skip the
  world. A sandstorm, a well dispute or a raid can interrupt it.
* Pack horses and mules carry the goods.
* The caravan stops at every well on the road. The player sees the wells, and the tolls paid at each one.

**River boats** on the Jungle River Road (Lainlyn-Totambu-Mournoth) are a third mode.

**Wilderness nodes.**

* **Way-tents** at desert wells. They are Yokudan-style shelters, and using one refreshes a minor blessing.
* **Tu'whacca shrines** at necropolis gates.
* **Order waystations** on the coast and river roads.
* Placement keeps the 90th percentile distance to a node at 500 blocks or less, and the desert is no exception.

**Danger on the roads.**

* Ra Gada war-bands camp at the road wells (MS4 world event: war-camps and sand-serpent risings).
* Scorpions on the reg plain.
* Harpies on the cliff roads.
* River pirates below Lainlyn.
* Collaborator hunters on the coast road.

Most of the walk stays quiet, and danger lives at the wells and the camps (F-QUIET).

---

## 5. Settlements

**Rules.** In Daggerfall's data every city is walled with four gates. Every Hammerfell capital, city and town keeps
its N, E, S and W gates (u, v from `townplan_seeds.json`), with the main gate turned toward the caravan road or the
harbour. Fief keeps are walled compounds (a kasbah-like keep with a tower and a courtyard) beside an open village.

**Culture in the data.**

* Coastal and desert seats are temple-rich and tavern-light: Sentinel has 223 buildings, 7 taverns and 1 temple;
  the coast fiefs have 2 temples and 6-11 taverns each.
* The bay-head river towns look like Breton towns: Totambu has 17 taverns and 4 guild halls, Lainlyn 16 and Kozanset
  15. They are the trading, mixed edge of the province.
* Skycraft capitals get 2 inns and a large temple quarter with a necropolis gate. The jungle cities get 2 inns and a
  river market.

### 5.1 Settlements by realm

| Settlement | Tier | Realm / fief | Why here (sheet) | Form and identity |
| --- | --- | --- | --- | --- |
| Sentinel | capital | sentinel / Sentinel | Harbour cliff plus the cisterns plus the end of the caravan road (sentinel.L6.dome_capital) | `domed-oasis-harbour`; 8x8 grid, 223 buildings, its own custom blocks in DF; palace over the cisterns, bazaar, dye quarter, Candle order hall, Fighters Guild chain |
| Tigonus | town | sentinel / Tigonus | The wadi river ford at the marsh edge | Reed-thatched outer town; marsh-witch charms on the doors |
| Abibon-Gora | town | sentinel / Abibon-Gora | South-west coast harbour (`tuwhaccas_throne` is 169 blocks away and is re-homed inland, IB3) | Wheel and Cup order halls; two temples |
| Ayasofya, Jalallaara, Bergama, Myrkwasa, Pothago, Kairou | fief keeps | sentinel | A coastal fortified retreat with two temples; a desert kasbah; a salt-pan fort (`goats_head_oasis` is 117 blocks away and moves to its own oasis, IB3); a terraced garden palace; a cliff-top watch; a harbour keep netted against harpies | Each keep has its own banner and a different form |
| Berbaaqnia | city | alikr_desert / Alik'r Desert | The great well on the caravan road (alikr.L6.caravanserai_city) | `oasis-caravanserai`; courtyard inns, well-house, stables; Stendarr temple |
| Pheonahator | town | alikr_desert / Dak'fron | A qanat head on the hot plain | Fortified well-town under dragon watch towers |
| Bubyrydata | village | alikr_desert | A caravan stop | A well, a way-tent and a smith |
| Skaven, Hegathe, Gilane | cities | alikr_desert (south) | Arena's southern towns: Skaven inland, Hegathe and Gilane on the Abecean shore | Each gets its own sub-kit: Skaven a mesa-foot walled town, Hegathe a temple city, Gilane a sea-wall port |
| Totambu | city | bangkorai / Totambu | The river port at the head of navigation (bangkorai.L6.river_port_city) | `stilted-river-port`; Scarab order hall; timber yards; Crown pilgrims' shrine to the old royal line |
| Lainlyn, Mournoth, Santaki | towns | bangkorai | Lainlyn: the ford and ferry to Wayrest. Mournoth: the eastern ford toward Craglorn. Santaki: a mountain town | Horn order hall in Lainlyn; Hawk order hall in Santaki |
| Kozanset, Satakalaam, Ephesus | fief keeps | bangkorai | A stilted river palace; a walled granary-fort on the scorpion edge; a mountain fort on the heights | |
| Apputhuga | village | bangkorai | A jungle landing | |
| Dragontail | city | dragontail / Dragontail Mountains | The ore market under the mines (dragontail.L6.terrace_city) | `black-stone-terraces`; Arkay temple; ore yards; debt-house |
| Morenanu | village | dragontail | A mining village | |
| Rihad | city | dragontail (south) | The pass city to Cyrodiil | `walled-bazaar-maze` (cityplan exists) |
| Taneth, Abah's Landing | city | hews_bane | The cliff palace and the stilt harbour | `cliff-palace-terraces`, `stilt-pier-harbour` |
| Elinhir, Dragonstar | city | craglorn | Granite tiers; the arena city | `highland-tier-spire`, `arena-ringed-fort` |
| Port Hunding | town | stros_mkai | The harbour bay | `island-lighthouse-citadel`; the Redguard layout in section 3 |

ILIAC's selection gives the window 1 capital, 3 cities, 6 towns, 9 fief keeps, 3 villages, 4 farms and 3 inns in
Hammerfell. ESO-era towns inside the window that collide with a Daggerfall seat or fill no purpose are re-homed or
dropped (ILIAC 5.2). For example, `hallins_stand` at (-5650, 4950) is re-homed south. `bangkorai_garrison` stays
because it guards the Dragontail Pass.

### 5.2 Bustling, steady and failing

| Busy (why) | Steady | Failing (why) |
| --- | --- | --- |
| Sentinel: the port, the dyes and the caravan terminus. Totambu: the river trade and the bridge traffic. Rihad: the pass bazaar to Cyrodiil. Taneth: the sea trade, rebuilding after the occupation | Berbaaqnia (the well never fails), Dragontail (ore), Lainlyn (ferry), Abibon-Gora (harbour), Elinhir | Pheonahator and Dak'fron (dragons back in the dens), Satakalaam (scorpion fields take the farms), Jalallaara (dust, a dry well), Morenanu (debt and a flooded gallery) |

### 5.3 Class disparity: Crowns against Forebears

| Class | Where | What the player sees |
| --- | --- | --- |
| Crown royals and oasis lords | Palace quarters, oasis estates, Totambu's royal shrine | Old Yokudan dress and titles, the old gods' shrines, a refusal to speak to the Empire's envoys |
| Forebear merchant houses | Harbour quarters of Sentinel, Abibon-Gora, Taneth and Stros M'kai | Counting houses, ships' offices, Imperial coin, the Eight's temples with Yokudan faces |
| Sword-masters | Sword schools and war-camps | Honoured even when poor; their word settles disputes |
| Craftsmen and smiths | Inner bazaar streets | Sword-smiths in every city, dyers and potters |
| Caravan men and nomad tribes | Caravanserais, tents at the hidden wells | Proud and armed, loyal to the tribe before any king |
| Labourers | Dye pits, salt pans, docks, mines | Stained hands, debt tallies, the quarter by the harbour or the vats |
| Outcasts | Islets, ruins | Necromancers, collaborators from the war, deserters |

**Per-city allegiance is a sheet field, not a fixed rule.** As a design choice, ports lean Forebear, while the interior
and the old royal seats lean Crown. Sentinel's court is split, with Crown nobles in the palace and Forebear merchants
on the quays. Berbaaqnia and Totambu are Crown. Abibon-Gora, Lainlyn, Taneth and Stros M'kai are Forebear. Dragontail
sells to both. IB8 checks each choice against lore.

---

## 6. Dungeons, underground and landmarks

### 6.1 Daggerfall dungeon types to Hammerfell themes

| Daggerfall type (where it clusters) | Skycraft variant | Hammerfell reason |
| --- | --- | --- |
| Crypt, cemetery (Alik'r 23 crypts; cemeteries everywhere) | `yokudan_tomb` (necropolis), tended or defiled; cemeteries become settlement-edge scenes | hf.L8.necropolis_dead |
| Desecrated temple (Alik'r 26, Totambu 24) | `daedric_ruin` / cult temple in a Yokudan temple shell | hf.L10.defiled_temples |
| Scorpion nest (Dak'fron 22, Alik'r 21, Tigonus 11) | New `scorpion_den` | hf.L4.scorpions |
| Dragon's den (Dak'fron 16) | Dragon lair (dragons returned in this era) | hf.L4.returning_dragons |
| Mine (Dragontail 85), volcanic caves (Dragontail 29) | `mine`; `cave` with a lava palette | dragontail.L5.mines, L1.black_ridges |
| Harpy nest (Tigonus 12, Abibon 11) | `den` on sea cliffs | hf.L4.coast_harpies |
| Giant stronghold (Totambu 19) | Giant camp in a glade | hf.L4.jungle_giants |
| Orc stronghold (Totambu 21) | Orc raider camp (`orc_ruin` reused) | bangkorai.L10.orc_hill_bands |
| Prison, human stronghold (Lainlyn 18) | River-pirate holds; crown forts | bangkorai.L9.river_pirates |
| Laboratory (Mournoth 7) | `necromancer_lair` | hf.L10.necromancers |
| Coven (Sentinel 12) | Witch's coven; the 6 named covens are faction seats | hf.L10.covens |
| Vampire haunt | `vampire_lair` by bloodline | hf.L10.bloodlines |
| Natural cave (coastal) | `smuggler_cave` | hf.L10.smugglers_coves |
| (not in DF) Dwemer | `dwemer_ruin`: Volenfell, Bthzark | hf.L10.dwemer_halls |
| (not in DF) Nedic | Nedic ruin kit in Craglorn | hf.L11.before_the_redguards |

**Elder-race slot.** Hammerfell has no Ayleid ruins. Its elder-race layer is **Dwemer** (Volenfell under the dunes and
Bthzark on Stros M'kai's plateau). Its old-culture tombs are **Yokudan necropolises**, and **Nedic** ruins stand in
Craglorn. Each dungeon's inhabitants come from L10:

* tomb-keepers or tomb-robbers in the necropolises;
* cultists in the defiled temples;
* scorpion broods in the warrens;
* Ra Gada war-bands in the forts;
* necromancers on the islets.

### 6.2 Marquee per realm

| Realm | Marquee dungeon | Second marquee | Notes |
| --- | --- | --- | --- |
| Sentinel | **The cistern undercroft** under the palace. It is Daggerfall's palace dungeon for the realm (18 blocks, the largest here), and it is where the king keeps the water (sentinel.L3.palace_cisterns) | Sentinel city | Tristore Laboratory is in Betony (Daggerfall's realm) in Daggerfall's data, so it is not Sentinel's (correcting ILIAC 8.2) |
| Alik'r North | Web of Anker (the scorpion warren, signature theme) | A dragon den on a mesa (XL silhouette) | Faugta's Guard is a major |
| Alik'r South | `lost_city_of_the_na_totambu` (keeps its dune identity, so it stays in the Alik'r. If its current point at -9650, 5950 falls in the sea after IB1, it moves inland onto the erg) | `goats_head_oasis` (re-homed clear of Bergama) | `volenfell` and `motalion_necropolis` are majors |
| Bangkorai | **The Scarab temple** (working id `scarab_temple_totambu`): a Yokudan river temple swallowed by the jungle and half drowned by the river, from Totambu's cluster of defiled temples | `onsis_breath` | ILIAC 8.2 gave Bangkorai the lost city, but a buried city of the dunes does not belong in a jungle. World-building wins, and the lost city stays in the Alik'r |
| Dragontail North | **Scourg Barrow** (the largest dungeon in Daggerfall's data, 22 blocks). Our `scourg_barrow` sits in Wrothgar as an `orc_ruin`. IB3 moves it to about (-4436, 4567) and IB7 re-themes it as a mountain-king necropolis | A fumarole field with a lava-tube mouth (XL) | Our `shedungent` goes the other way, into Wrothgar (`high_rock.md` 6.2) |
| Dragontail South | `motalion_necropolis` style cliff tomb, or a new canyon tomb | Rihad's minaret | |
| Craglorn | `sanctum_ophidia` | `skyreach_pinnacle`, `aetherian_archive` | |
| Hew's Bane | `ahaleb_necropolis` | Taneth palace | |
| Stros M'kai | **Bthzark** (Dwemer, three gates on the western plateau, Redguard layout) | The lighthouse on the south point, and the palace | N'Gasta's tower is a major. The catacombs are a short dungeon with a loop back to the bell tower |

`tuwhaccas_throne` (169 blocks from Abibon-Gora) is re-homed inland to a necropolis valley in the Sentinel
hinterland. It is a major with a procession set piece.

### 6.3 What lies below

* **Sentinel:** the palace cisterns and the undercroft, smugglers' tunnels to the quays, and the Coven of the Tide's
  sea cave.
* **Alik'r:** necropolises under the dunes, the buried royal city, qanat tunnels that become dungeons when they run
  dry, Volenfell's sand-choked lifts and scorpion warrens.
* **Bangkorai:** the drowned temple, river caves, Orc hill holds and Mournoth's hidden labs.
* **Dragontail:** mine galleries breaking into lava tubes, Scourg Barrow and the Devilrock grotto.
* **Stros M'kai:** catacombs under the town, the waterfall hideout, Bthzark's halls and N'Gasta's undercroft.

### 6.4 Landmarks and identity

* XL weenies:
  * Sentinel's palace dome, seen from the sea;
  * a dragon mesa in Dak'fron;
  * the dune sea's highest star dune;
  * Totambu's drowned temple roof above the canopy;
  * Dragontail's smoking black peak;
  * Skyreach;
  * the Stros M'kai lighthouse.
* L kinds of our own: sea arches, a salt-flat mirage stone, a qanat line of well shafts, mesa hoodoos, slot canyons,
  a fumarole field, star-fall craters, a giant glade and a reef wreck.
* M scenes:
  * a caravan camp at a well;
  * a sword-duel circle;
  * a Tu'whacca shrine;
  * a buried statue's head;
  * a collaborator's gibbet;
  * a dried-out oasis with a story.

### 6.5 Dungeon floor per realm

At the Skyrim median of 8.9/km² (abs. 6.7) the targets are:

| Realm | Target | ILIAC selection (window) |
| --- | --- | --- |
| Sentinel | 10 | 10 |
| Alik'r (window) | 13 | 12 |
| Alik'r (whole, about 9 km²) | 80 | n/a |
| Bangkorai | 13 | 10 |
| Dragontail (window) | 14 | 14 |
| Dragontail (whole) | 51 | n/a |
| Craglorn | 20 | n/a |
| Hew's Bane | 35 | n/a |
| Stros M'kai | 6 | n/a |

Every dungeon needs a hook, a named boss or reason and a note. Hammerfell's `d_identity` is 0.032 today.

---

## 7. Quests

### 7.1 Province arcs

| Arc | Kind | Spine | Choice and consequence |
| --- | --- | --- | --- |
| **The Second Treaty** (province main story, at least 19 quests across every region) | Main | Twenty years after the war, both Crowns and Forebears claim the right to speak for Hammerfell to the Empire. A network of former collaborators plays them against each other. The Alik'r hunters' search in Skyrim's Whiterun is one thread of it. The route runs from Sentinel's court to a Crown council at Berbaaqnia's well, to Totambu's royal shrine, to the Dragontail forges that arm both sides, to Taneth's occupation scars and on to Stros M'kai, where both treaties were signed | **Exclusive choice (P-J1): Crowns or Forebears.** Sentinel's vizier changes. Caravan tolls rise or fall. One fief keep's banner changes (Jalallaara or Kairou). The other side's sword-schools close their doors to the player |
| **The Betony Claim** (Sentinel side) | Province chain, 4 quests | Mirrors `high_rock.md`'s Betony Question. Sentinel's envoy, the old battlefield where a Daggerfall king fell, a smuggled garrison list and the Whitefort keep | The outcome is shared with High Rock: one result sets Betony's banner for both provinces |
| **The Siege Tunnel** (cross-province) | Lore chain | Redguard veterans of the order of Diagna left records of the First-Era siege of Orsinium. The tunnel they dug is old Orsinium's loop path (`high_rock.md` 6.2) | The order's sword is returned to Sentinel or left with the Orcs. Dialogue in both cities changes |

### 7.2 Region stories (hold conflict chain, at least 3 quests each, with consequence)

| Realm | Chain | Ends with |
| --- | --- | --- |
| Sentinel | **The Cobalt Ledger** (marquee) plus **Who Drinks**: the king rations the cisterns in a dry year and the dyers' quarter riots | The cistern is opened to the lower town (a fountain scene appears) or the riot is put down (guards on the dye pits) |
| Alik'r North | **Who Holds the Well**: a Crown lord and a nomad tribe both claim the Bubyrydata well; a raid; an oath sworn at the well | The well is shared (the caravan route stays) or taken (caravans reroute and the road scene changes) |
| Alik'r South | **The Buried King** (lore-web ham_1 finale at the lost city) | The king is freed or bound, and a sand-serpent rising stops or grows |
| Bangkorai | **The Scarab and the Giants**: the timber lords and the Order of the Scarab push into a giant glade; the drowned temple | The glade is logged (a mill appears and the giants turn hostile) or protected (the taboo holds, and a giant herd is seen at dawn as a wonder moment) |
| Dragontail | **Debts in the Deep**: debt miners, a flooded gallery, the Devilrock fire-pact and Scourg Barrow waking | The debts are struck (the debt-house becomes a guild hall) or the gallery is sealed with the dead inside |
| Craglorn | **Seven Fights** (Dragonstar marquee) plus a star-fall chain | The arena's fixer is exposed or paid off |
| Hew's Bane | **A Verse for the Sea** (Taneth marquee) plus **The Open Gate**: hunting Taneth's collaborators | A collaborator is tried or spared, and Taneth's court lines change |
| Stros M'kai | **The Island's Price** (marquee) plus **The Sunken Clause** (lore-web ham_4) | The hidden dock is closed or kept, and the treaty copy goes to the Crowns, the Forebears or the sea |

### 7.3 Factions (P-G1)

* **Knightly orders (5 plus the Cup).** The Candle (Sentinel), the Wheel and the Order of the Cup (Abibon-Gora), the
  Horn (Lainlyn), the Scarab (Totambu) and the Hawk (Santaki). Each has an order hall and a rank line of 5-8 quests.
* **Sword-schools.** The Ra Gada war-camps are the MS4 world event. Their masters run a training line that links to
  the foreign-lands lore-web arc for_4 "The Last Lesson". Which schools open to the player depends on the Crown or
  Forebear choice.
* **Covens (6).** The Witches of the Marsh, the Dust Witches, the Sisters of Kykos, the Tide Witches, the Witches of
  Devilrock and the Mountain Witches. They offer bargains, and the Devilrock coven trades fire-pacts.
* **Guilds.** The Fighters Guild's province chain is in Sentinel (the Mages Guild's chain is in Wayrest). There is a
  hall in every realm seat. A Dark Brotherhood sanctuary is in Sentinel and a thieves' guild at Abah's Landing.
* **Temples and the old gods.** Arkay (Sentinel), Stendarr (Berbaaqnia), Z'en (Totambu) and Arkay (Dragontail). The
  Yokudan line is Tu'whacca's tomb-keepers, who give work at the necropolises.
* **Bloodlines.** Selenu, Anthotis and Khulari (Montalion is shared with High Rock).

### 7.4 Settlement quests and radiant

Tier counts follow 4.4: capitals at least 19 quests, cities 3-6, towns 2, keeps and villages 1. Culture-specific
radiant templates (at least 6) use a named giver and a named place:

* escort a caravan between two named wells;
* settle a well-oath dispute;
* clear a tomb of robbers for Tu'whacca's keepers;
* hunt a scorpion queen;
* return a stolen sword to its school;
* track a collaborator.

The festival (MS16) is the Festival of Blades, a Redguard holiday on Daggerfall's calendar (IB8 confirms the date).
It brings duel circles in every city square, a sword market and a sword-school exhibition in Sentinel. Notice boards
(MS12) go in every town and larger.

---

## 8. Components layout

### 8.1 Spatial layout

```
           (Bjoulsae bridge from Wayrest)
 bay ferries <- sentinel (capital, cisterns) -- South Shore Road -- tigonus -- kozanset -- lainlyn -- bangkorai (Totambu river port) -- Jungle River Road -- mournoth -- craglorn (Elinhir, Dragonstar) -> Falkreath
    |   \ Abibon Coast Road (harpy coast, coast keeps)                    |                          |
    |    Alik'r Caravan Road -> berbaaqnia (well city) -> pheonahator (dragon plain) -> skaven         Dragontail Pass -> dragontail (black terraces) -> rihad -> Kvatch
    |                                     alikr south: hegathe -- gilane -- (Abecean Coast Road) -- taneth / abahs_landing (hews_bane)
 Abecean line -> stros_mkai (Port Hunding, treaty ground)
```

The coast is wet in winter, mercantile and Forebear. The interior is dry, Crown-held and built round its wells. The
bay head is green and mixed. The east is a black and red mountain wall. Every realm has one main road, one seat, one
marquee pair and one order hall or sword-school.

### 8.2 Data and code components

| Component | File / system | Hammerfell content | Phase |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/hammerfell.json` (MS22) | Province block; `sentinel`, `alikr_desert` (two halves), `bangkorai`, `dragontail` (two halves), `stros_mkai`, `craglorn`, `hews_bane` | before IB3 |
| Regions and polygons | `tamriel_vectors.json`, `regions.json` | New `sentinel`; re-cut `bangkorai`; shrunk `alikr_desert` and `dragontail`; hinge applied | IB1, IB2 |
| Fiefs | `skycraft/tamriel/fiefs.json`, `Fiefs` | 19 fiefs with seat, lord title, temple, order, coven, bloodline, banner, Crown or Forebear lean | IB2 |
| Biomes | `biomes.py`, `palettes.json` | `iliac_scrub`, `iliac_rainforest`, `tigonus_marsh`; micro tables | IB2, MS21 |
| Places | `places/hammerfell.json`, `places/provinces.json` | About 75 window places; moves for Sentinel, Elinhir, Gilane, the Stros M'kai sites, `scourg_barrow` (in), `shedungent` (out), `tuwhaccas_throne` and `goats_head_oasis` (re-homed) | IB1, IB3 |
| City plans | `cityplans/*.json` (`sentinel.json` and `rihad.json` exist) | Sentinel polish, Berbaaqnia, Totambu, Dragontail, Tigonus, Abibon-Gora, Port Hunding (Redguard layout); 9 keeps with distinct forms; overlays per realm | IB4 |
| Roads, ports, caravans | `named_roads.json`, `ports.json`, a caravan variant of `Carriages` | Section 4 | IB5 |
| Terrain | vectors, `hydrology*.py` inputs | Section 3 | IB6 |
| Dungeons | `places` variants, `dungeons/marquee/**`, kit `scorpion_den` | Section 6 | IB7 |
| Quests, factions, lore | `quests/**` (`side_hammerfell.json`, `marquee_hammerfell.json`, `loreweb_hammerfell.json` exist), `region_voice.json` (no Hammerfell keys yet), `lore/books/<region>.json` | Section 7 | IB8 |
| Events, scenes | `events/hammerfell.json` (MS4), `creatures/scenes.json` (MS14) | War-camps, serpent risings; caravan at a well, sword duel, Tu'whacca procession, collaborator escort, dragon overflight | IB8 |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep (Daggerfall/Arena/Redguard/ESO fact) | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | The bay's south shore, the Alik'r interior, the Dragontail spine, the island scatter, Stros M'kai's shape and site layout | Real dune seas, mesas, salt flats, wadis, a volcanic and red-rock wall, jungle valleys, sea arches | DF's smooth heights and Arena's flat sand: authored vectors, our heights |
| Politics | 19 fiefs, houses, temples, orders, covens, bloodlines; Crowns and Forebears; the two treaties | An exclusive Crown or Forebear choice; well ownership as politics; Betony shared with High Rock | Ruler names as the only content |
| Settlements | Name, position, size, walls and four gates, the service mix (temple-rich coast, tavern-dense river towns) | Unique seats: domes, caravanserai, stilted river port, black terraces; kasbah keeps | Recombined RMB block towns and one shared Redguard kit (27 towns that look the same, `MARQUEE.md`) |
| Dungeons | Names, premises, size ranking, Daggerfall's main-story sites, Redguard's island sites | Cistern undercroft, drowned Scarab temple, Scourg Barrow re-homed, scorpion warrens | Random-block mazes |
| Travel | Neighbours, ports | Caravans that walk the wells, ferries, river boats, way-tents | Fade-to-black travel |
| Quests | Factions, orders, covens, the war and the treaties as facts | The Second Treaty, Who Holds the Well, the Siege Tunnel | Generator quests as main content |
| Life | Building counts and quality | Industries: dyes, spice, salt, caravans, blades, timber, ore | Nameless crowds |

---

## 10. Floors, gates and phases

### 10.1 Current against target (`py -3 -I tools/qa/parity.py`, 2026-10-13)

| Region | km² | SET | NPC | QST | DUN | LOR | s_npcs (floor 37.5) | q_total /km² (11.2) | d_identity (0.8; target 1.0) | r_notes /km² (44.7) | y_looks (2.1) | l_kinds (10.3) | c_total (25) | d_marquee (>= 1) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| alikr_desert | 10.2 | 39 | 21 | 21 | 68 | 18 | 1.5 | 1.87 | 0.04 | 3.8 | 2 | 18 | 15 | 1 |
| bangkorai | 2.4 | 25 | **1** | **8** | 52 | **13** | 0.0 | 0.42 | 0.00 | 2.5 | 1 | 8 | 15 | 0 |
| craglorn | 2.3 | 30 | 21 | 38 | 56 | 24 | 4.7 | 6.39 | 0.05 | 8.9 | 1 | 8 | 15 | 0 |
| dragontail | 5.7 | 38 | 17 | 33 | 56 | **14** | 2.5 | 1.92 | 0.00 | 3.1 | 2 | 11 | 15 | 0 |
| hews_bane | 3.9 | 28 | 30 | 45 | 58 | 19 | 7.5 | 4.06 | 0.04 | 6.1 | 1 | 11 | 15 | 0 |
| stros_mkai (sliver) | 0.7 | 38 | 17 | 52 | 58 | 33 | 8.0 | 11.84 | 0.13 | 14.8 | 1 | 5 | 15 | 0 |
| **Hammerfell** | 25.3 | 40 | 16 | 28 | 60 | 17 | 2.4 | 2.77 | 0.03 | 4.7 | 1.3 | 10.2 | 15 | 0.17 |

Cells in the 40 lowest of the whole map: bangkorai NPC (1), bangkorai QST (8), bangkorai LOR (13) and dragontail LOR
(14). Bangkorai is the emptiest region in either province, and it is exactly the realm the Daggerfall re-cut fills
(3 towns, 1 city and 3 keeps). The new `sentinel` realm inherits today's `alikr_desert` numbers until IB3. As in High
Rock, settlement and dungeon counts already beat Skyrim per km²; NPCs, quests, lore and identity are at 1-20%.

### 10.2 Targets per region (Skyrim median per km², absolute floor never broken)

| Region | km² | Marked places | Dungeons | Settlements | Named NPCs | Merchants | Trainers | Quests | Readables | Micro-biomes | XL |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sentinel | 1.15 | 17 | 10 | 2 (has 10: fief keeps) | 39 | 6 | 4 | 16 | 224 | 6 | 1 |
| alikr_desert (window) | 1.42 | 21 | 13 | 3 (has 5) | 48 | 7 | 5 | 20 | 277 | 6 | 1 |
| alikr_desert (whole, est.) | ~9 | 131 | 80 | 18 | 302 | 45 | 32 | 126 | 1,755 | 18 | 4 |
| bangkorai | 1.45 | 21 | 13 | 3 (has 10) | 49 | 7 | 5 | 20 | 283 | 6 | 1 |
| dragontail (window) | 1.61 | 24 | 14 | 3 (has 4) | 54 | 8 | 6 | 23 | 314 | 6 | 1 |
| dragontail (whole) | 5.7 | 83 | 51 | 11 | 192 | 28 | 20 | 80 | 1,112 | 12 | 2 |
| craglorn | 2.3 | 34 | 20 | 5 | 77 | 12 | 8 | 32 | 448 | 6 | 1 |
| hews_bane | 3.9 | 57 | 35 | 8 | 131 | 20 | 14 | 55 | 760 | 8 | 2 |
| stros_mkai | 0.7 | 10 | 6 | 1 | 24 | 4 | 2 | 10 | 136 | 6 | 1 |

Settlement depth comes on top (4.4). Sentinel needs at least 73 named NPCs and 23 named buildings. Each city needs at
least 20 and 11, each town at least 9 and 4, and each keep and village at least 3.

Fixed per region:

* a hold conflict chain;
* 2 marquee places;
* at least 8 inhabitant themes with a signature one: Sentinel the harpy coast and the marsh coven, the Alik'r
  scorpion broods, Bangkorai the jungle giants and the Orc hill bands, the Dragontail fire-pact cultists, Stros M'kai
  smugglers and the necromancer's isle;
* at least 8 world-encounter kinds, 3 of them Hammerfell-only;
* 3 wonder moments: a sandstorm wall at dusk, a giant herd at dawn, and a dragon over the mesas;
* 12 rumours, legends and songs;
* 12 deed-keyed lines.

**Headline targets for Hammerfell.**

* A province sheet plus 9 region sheets (the Alik'r and the Dragontail in two halves each) that pass
  `worldsheet_lint.py`.
* Across the whole province: at least 225 dungeons, all with identity; about 850 named NPCs; and at least 354 quests.
* At least 9 marquee dungeons.
* An exclusive Crown or Forebear choice.
* 5 order lines plus the sword-schools, and 6 covens.
* 3 travel modes: ships, caravans and river boats.
* 1 festival.

### 10.3 Phases mapped onto IB1-IB8 (Hammerfell share)

| Phase | Hammerfell work | Gate |
| --- | --- | --- |
| IB1 Frame and vectors | Hinge (-23.3 degrees); trace the south shore and the fiefs; Sentinel 224 east; Elinhir and Gilane moves; Stros M'kai site moves; `TAMRIEL.md` section 9 | `layout_lint.py`; coast agreement >= 0.90; binding anchors on land; Haiku review of `preview_places_hammerfell.png` |
| IB2 Realms, fiefs, biomes | `sentinel`; re-cut `bangkorai`; 19 fiefs; `iliac_scrub`, `iliac_rainforest`, `tigonus_marsh`; micro tables; world sheet `hammerfell.json` drafted | Fief areas within 10%; signature biome first in census; `worldsheet_lint.py` 0 |
| IB3 Places | Selection plus reconciliation; marquee collisions (`tuwhaccas_throne`, `goats_head_oasis`); `scourg_barrow` in and `shedungent` out; `lost_city` check; Stros M'kai sites | Spacing; 100% on land; `validate.js`; no lore-web place dropped; SET does not regress |
| IB4 City plans | Sentinel, Berbaaqnia, Totambu, Dragontail, Tigonus, Abibon-Gora, Port Hunding; 9 keeps; overlays | Unique `sil`; `town_checks.py`; Haiku "not a reskin" (the shared Redguard kit is gone); 0 unstaffed workplaces |
| IB5 Roads | Section 4 roads, ferries, caravans, river boats, way-tents | `roadplan_lint.py` 0; `reach_lint.py` 100%; corridor sentinel:tigonus at 0 gaps; F-TRAVEL >= 2 modes |
| IB6 Terrain relief | Section 3 landforms, wadis, rivers, the erg | >= 3 Hammerfell rivers reach the bay (6 with High Rock); p90 relief >= Skyrim's median hold; >= 1 XL landform per realm; Spearman >= 0.6 |
| IB7 Dungeons | Section 6 variants, `scorpion_den` kit, marquee plans (Sentinel undercroft, Web of Anker, Scarab temple, Scourg Barrow, Bthzark) | `d_identity` = 1.0; >= 5 palettes and >= 8 rosters per realm; F-LOOP |
| IB8 Stories and factions | The Second Treaty, the Betony Claim (shared), the Siege Tunnel, region chains, orders, sword-schools, covens, voice, festival | `novelty_lint.py --strict` 0; F-HOOK <= 800; `story_lint.py`; MS20 verbatim 0 |

The frame regions (Craglorn, Hew's Bane, the southern Alik'r and Dragontail, and Stros M'kai beyond its IB1 site moves)
follow the same gates in the province content wave after IB8. Their sheets come first.
