# Hammerfell: province plan

Province plan for Hammerfell under `docs/world/DESIGN-PRINCIPLES.md` (the rulebook) and `docs/world/ILIAC-BAY.md`
(the Daggerfall-based plan for the bay; phases IB1-IB8). The master plan is `docs/world/WORLD-PLAN.md`. High Rock,
the other half of the bay, is `docs/world/provinces/high_rock.md`. The two files cross-reference each other on the
cross-bay ferries, the Betony claim, the Orsinium sieges (the order of Diagna), the knightly orders and the Bjoulsae
bridge.

**Legal line.** Daggerfall, Arena, Redguard and ESO supply facts only: names, relative positions, borders, climate
classes, counts, the building mix of towns and Stros M'kai's site layout. Every description below is our own. No game
text, raster or asset enters the repo. Invented people are ours.

**Era.** NirnCraft is set in 4E 201, twenty-odd years after Hammerfell drove the Aldmeri Dominion out on its own. Every
older era is a **history layer** (L11) that must be visible today.

**Scope.** Hammerfell is 25.3 km² in NirnCraft today, 0.95 times Skyrim, close to the lore map. Northern Hammerfell (the
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
| L2 Climate | `dry_interior` (cites L1.alikr_basin): heat and very little rain. `coast_shadow` (cites L1.south_shore): coastal hills take the sea rain, so the Alik'r beyond them is dry. `winter_rains` (cites L1.south_shore): the coast gets mild winter rain. `bay_lift` (cites L1.bay_head_valleys, L1.dragontail_wall): moist bay air is forced up against the foothills, so the bay head is the only rainforest in the province. `sandstorms` (cites L1.alikr_basin, L2.dry_interior): blinding dust and shifting dunes across the basin. `mountain_cold` (cites L1.dragontail_wall): frost and high winds along the eastern peaks. `abecean_storms` (cites L1.abecean_coast): warm squalls off the southern sea |
| L3 Water | `oases_and_wells` (cites L1.alikr_basin): water exists only at oases, wells and underground channels. `water_is_power` (cites L1.alikr_basin, L2.dry_interior, L3.oases_and_wells): every oasis belongs to a Crown family or to the tribe that holds its well, and caravans pay at each one. In the cities the palace owns the cisterns. `wadis` (cites L2.winter_rains, L1.dragontail_wall): dry beds that flood after rain. `jungle_rivers` (cites L2.bay_lift): perennial rivers at the bay head (Lainlyn, Totambu). `snowmelt` (cites L2.mountain_cold): Dragontail streams feed the wadis |
| L4 Ecology | `mosaic` (cites L1.alikr_basin, L2.dry_interior, L3.oases_and_wells): see section 2. `scorpions` (cites L1.alikr_basin, L2.dry_interior): giant scorpions in the basin and hot plains (21 nests in the Alik'r and 22 in Dak'fron in Daggerfall's data). `sand_serpents` (cites L2.sandstorms): burrowing serpents in the dune seas. `coast_harpies` (cites L1.abecean_coast): harpy nests clustering along the Abibon and southern sea cliffs. `jungle_giants` (cites L2.bay_lift): giant clans in the bay-head valleys (19 giant strongholds in Totambu). `returning_dragons` (cites L1.alikr_basin, L2.dry_interior): dragons back in the hot-desert dens. `vent_daedra` (cites L1.dragontail_wall): fire spirits in the volcanic caves |
| L5 Economy | `caravans_and_salt` (cites L3.water_is_power, L1.alikr_basin): desert caravan trains and salt-pan cutting. `dyes_and_spice` (cites L2.winter_rains): Sentinel's terraces and port. `dragontail_ore` (cites L1.dragontail_wall): iron, obsidian and sulphur. `blades` (cites L1.dragontail_wall, L5.dragontail_ore): swordsmiths in every city. `timber_and_river_fish` (cites L3.jungle_rivers): hardwood logging and river fisheries at the bay head. `oasis_dates` (cites L3.oases_and_wells): date palms and irrigated gardens. Who gets rich: Crown families (oases and caravan tolls), Forebear merchant houses (ports and shipping) and the ore lords of the Dragontail |
| L6 Settlement | `harbour_and_oasis_cities` (cites L3.oases_and_wells, L1.south_shore): walled port capitals and oasis seats. `caravanserais_at_wells` (cites L5.caravans_and_salt): fortified stops at every major well. `jungle_river_ports` (cites L3.jungle_rivers): stilted river towns at the bay head. `mining_terraces` (cites L5.dragontail_ore): stepped black-stone towns in the mountains. `busy_centres` (cites L5.dyes_and_spice, L5.caravans_and_salt): Sentinel (capital, port and caravan terminus), Totambu (the river port), Taneth and Rihad. `failing_edges` (cites L4.returning_dragons, L4.scorpions): Dak'fron's wells, Satakalaam's scorpion fields and the Antiphyllos dust |
| L7 Society | `crowns_and_forebears` (cites L5.caravans_and_salt, L6.harbour_and_oasis_cities): the old royal nobility against the integrated warrior-merchant class. `sword_rank` (cites L5.blades, L6.harbour_and_oasis_cities): a sword-master (Ansei) is honoured even when poor. `nomads` (cites L3.water_is_power): desert tribes outside the cities, proud and armed. `stained_hands` (cites L5.dyes_and_spice, L5.caravans_and_salt): dyers, salt-cutters and dockers are the poor. `debt_miners` (cites L5.dragontail_ore): miners bound by debt in the Dragontail. `outcasts` (cites L6.failing_edges, L7.crowns_and_forebears): necromancers are hunted, and foreigners are judged by deeds |
| L8 Culture | `yokudan_gods` (cites L6.harbour_and_oasis_cities, L7.crowns_and_forebears): the Crowns keep the old gods (Satakal, Ruptga, Tu'whacca, Onsi, Zeht, Morwha and the HoonDing). `eight_with_yokudan_faces` (cites L7.crowns_and_forebears): the Forebears worship the Divines with Yokudan names (Sentinel's patron temple is Arkay's, the keeper of the dead, close to Tu'whacca). `sword_path` (cites L5.blades, L7.sword_rank): the sword as a spiritual discipline (the sword-singers). `hate_necromancy` (cites L7.crowns_and_forebears, L8.yokudan_gods): the dead belong to Tu'whacca. `necropolis_dead` (cites L8.yokudan_gods, L1.alikr_basin): the dead lie in great tiered tombs. `distrust_empire` (cites L6.harbour_and_oasis_cities, L7.crowns_and_forebears): resentment over the White-Gold Concordat cession. `hate_thalmor` (cites L6.harbour_and_oasis_cities, L7.crowns_and_forebears): unyielding hostility toward the Dominion after the occupation |
| L9 Politics | `crown_forebear_rift` (cites L7.crowns_and_forebears, L8.distrust_empire): how to deal with the Empire, and who speaks for Hammerfell. `kings_and_lords` (cites L6.harbour_and_oasis_cities): city kings over fief lords. `betony_claim` (cites L1.south_shore, L6.harbour_and_oasis_cities): Sentinel against Daggerfall (see `high_rock.md`). `collaborators` (cites L7.outcasts, L8.hate_thalmor): those who opened gates to the Dominion are hunted. `ra_gada_bands` (cites L7.sword_rank): free warrior-bands camp on the roads. `well_feuds` (cites L3.water_is_power): rival claims over desert wells |
| L10 Hidden | `covens` (cites L4.mosaic, L9.kings_and_lords): six witch covens on the Hammerfell shore. `bloodlines` (cites L9.kings_and_lords): Selenu (Sentinel coast), Anthotis (desert), Khulari (mountains and jungle), Montalion (bay head). `necropolises` (cites L1.alikr_basin, L8.necropolis_dead): tiered royal and family catacombs. `defiled_temples` (cites L4.scorpions, L8.yokudan_gods, L9.kings_and_lords): cults in the old gods' temples (26 desecrated temples in the Alik'r and 24 in Totambu in Daggerfall's data). `dwemer_halls` (cites L1.alikr_basin, L1.stros_island): Volenfell, Bthzark. `lava_tubes` (cites L1.dragontail_wall, L4.vent_daedra): volcanic caverns under the eastern ridges. `smugglers_coves` (cites L1.abecean_coast): sea caves along the southern cliffs. `necromancers` (cites L8.hate_necromancy, L9.collaborators): hidden labs (7 in Mournoth in Daggerfall's data) |
| L11 History | `before_the_redguards` (cites L1.craglorn_badlands, L10.dwemer_halls): Nedic peoples, Orcs, beastfolk and the Dwemer held the land. `two_waves` (cites L7.crowns_and_forebears, L8.yokudan_gods, L8.sword_path): the warrior wave from sunken Yokuda took the land by the sword, and the royal court followed; the name Hammerfell and the sword-saint Hunding belong to this time. `orsinium_siege` (cites L1.dragontail_wall, L8.sword_path): Redguard warriors of the order of Diagna helped besiege the Orc city (`high_rock.md`). `covenant_era` (cites L6.harbour_and_oasis_cities, L9.kings_and_lords): the Second Era compact with High Rock and Wrothgar. `first_treaty` (cites L1.stros_island, L8.distrust_empire, L9.crown_forebear_rift): in the late Second Era, after the Crowns' fleet was broken at Hunding Bay, a treaty signed on Stros M'kai made Hammerfell an Imperial province. `war_of_betony` (cites L1.south_shore, L9.betony_claim): Sentinel's war with Daggerfall over Betony. `warp_in_the_west` (cites L6.harbour_and_oasis_cities, L9.kings_and_lords): the Dragon Break that reshaped the Iliac crowns. `dragon_dens` (cites L1.alikr_basin, L4.returning_dragons): old dens in the hot desert, empty for an age. `great_war` (cites L8.distrust_empire, L8.hate_thalmor, L9.collaborators): the Empire ceded southern Hammerfell to the Dominion, Hammerfell fought on alone and won, and a second treaty was signed on Stros M'kai |

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
| L1 | `harbour_cliff` (hf.L1.south_shore): red stone cliffs enclosing Sentinel's anchorage. `garden_terraces`: terraced slopes behind the city. `tigonus_lowland`: a low marshy shore east of the city. `abibon_coast` (hf.L1.abecean_coast): harpy cliffs south-west. `bergama_pans` (hf.L1.alikr_basin): salt flats inland |
| L2 | `winter_rain_coast` (L1.harbour_cliff, hf.L2.winter_rains): mild winter showers along the shore. `marsh_heat` (L1.tigonus_lowland): humid haze over the eastern reeds. `inland_dry` (L1.bergama_pans, hf.L2.coast_shadow): parched air behind the coastal hills |
| L3 | `palace_cisterns` (L1.harbour_cliff, L2.winter_rain_coast, hf.L3.water_is_power): the palace stores the winter rain; the city drinks by the king's leave. `wadi_river` (L1.tigonus_lowland, L2.winter_rain_coast, hf.L3.wadis): a wadi river through Tigonus to the bay. `terrace_channels` (L1.garden_terraces): stone rills watering the dye slopes |
| L4 | `coastal_maquis` (L1.harbour_cliff, L2.winter_rain_coast): palm and olive-green scrub. `terrace_gardens` (L2.winter_rain_coast, L3.terrace_channels): fig, pomegranate and dye-plant rows. `tigonus_marsh` (L1.tigonus_lowland, L3.wadi_river): reeds, harpies and scorpions (Daggerfall's data: 12 and 11). `harpy_cliffs` (L1.abibon_coast, hf.L4.coast_harpies): sea-stack nests along the Abibon coast. `salt_flat` (L1.bergama_pans): white salt crust in the Bergama basin |
| L5 | `dye_works` (L4.terrace_gardens, hf.L5.dyes_and_spice): dye vats and drying terraces. `spice_port` (L1.harbour_cliff, hf.L5.dyes_and_spice): ocean quays and warehouses. `caravan_terminus` (L1.bergama_pans, L3.palace_cisterns, hf.L5.caravans_and_salt): the western end of the Alik'r trade road. `salt_pans` (L1.bergama_pans, L4.salt_flat): Product: cobalt and saffron dyes, spice, Bergama salt, terrace oil |
| L6 | `dome_capital` (L1.harbour_cliff, L3.palace_cisterns): Sentinel on its harbour bluff. `coast_fief_keeps` (L1.abibon_coast, L5.dye_works): six lords' seats down the coast and hinterland (Ayasofya, Jalallaara in Antiphyllos, Bergama, Myrkwasa, Pothago and Kairou, plus Abibon-Gora). `marsh_town` (L3.wadi_river, L4.tigonus_marsh): Tigonus at the wadi ford. `ferry_head` (L1.harbour_cliff, L5.spice_port): ferries to Daggerfall and Stros M'kai |
| L7 | `palace_crowns` (L3.palace_cisterns, L6.dome_capital, hf.L7.crowns_and_forebears): royal nobility in the upper palace quarter. `quay_forebears` (L5.spice_port, L6.dome_capital, hf.L7.crowns_and_forebears): merchant houses on the harbour quays. `dyers_quarter` (L5.dye_works, hf.L7.stained_hands): blue-handed workers in the lower town |
| L8 | `arkay_patron` (L6.dome_capital, L7.palace_crowns, hf.L8.eight_with_yokudan_faces): Arkay in Sentinel, Julianos in Tigonus and Pothago, Mara in Ayasofya and Antiphyllos, Akatosh Chantry in Bergama, Kynareth in Myrkwasa, Z'en in Kairou and Dibella in Abibon-Gora. `marsh_witch_fear` (L4.tigonus_marsh, L6.marsh_town): reed-charms hung on doors in Tigonus |
| L9 | `court_rift` (L7.palace_crowns, L7.quay_forebears, hf.L9.crown_forebear_rift): Crowns and Forebears struggle over the vizierate and water rations. `betony_lever` (L6.ferry_head, L7.quay_forebears, hf.L9.betony_claim): both factions use the Betony claim against Daggerfall. `candle_order` (L6.dome_capital, L7.palace_crowns): the Order of the Candle guards the king, while the Knights of the Wheel and Order of the Cup hold Abibon-Gora |
| L10 | `cistern_undercroft` (L1.harbour_cliff, L3.palace_cisterns, L9.court_rift): the palace undercroft is the realm's largest dungeon in Daggerfall's data. `witches_of_the_marsh` (L4.tigonus_marsh, L8.marsh_witch_fear, hf.L10.covens): the marsh coven in Tigonus. `dust_witches` (L1.bergama_pans, L9.court_rift, hf.L10.covens): the Dust Witches of Antiphyllos. `selenu_coast` (L6.coast_fief_keeps, L9.court_rift, hf.L10.bloodlines): Selenu and Anthotis vampire bloodlines. `coven_of_the_tide` (L1.abibon_coast, L4.harpy_cliffs, hf.L10.covens): sea-cave coven on the coast |
| L11 | `lysandus_war` (L6.ferry_head, L9.betony_lever, hf.L11.war_of_betony): Sentinel's army killed a king of Daggerfall. `harbour_of_the_first_landing` (L1.harbour_cliff, L6.dome_capital, hf.L11.two_waves): early Ra Gada anchorage on the southern bay |

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
| L1 | `erg` (hf.L1.alikr_basin): dune sea, crests y 70-130. `mesas`: red mesas to y 200. `reg_plain`: gravel plain. `dakfron_hot_plain`: scorched northern basin plain. `buried_valleys`: old valleys under the sand |
| L2 | `sandstorms` (L1.erg, hf.L2.sandstorms): shifting sand walls across the erg. `hot_dakfron` (L1.dakfron_hot_plain): extreme daytime heat in the northern plain. `cold_nights` (L1.erg, L1.mesas, hf.L2.dry_interior): sharp desert frost after dark |
| L3 | `owned_wells` (L1.buried_valleys, L2.hot_dakfron, hf.L3.water_is_power): deep oasis wells controlled by Crown lords. `hidden_wells` (L1.erg, L1.buried_valleys): tribal wells known only to the tribes. `qanat_lines` (L1.buried_valleys): underground channels from the foothills to the oases |
| L4 | `erg_dunes` (L1.erg, L2.sandstorms): star and crescent dunes. `reg_plain` (L1.reg_plain, L2.cold_nights): gravel plain with thorn bushes. `oasis_grove` (L3.owned_wells, L3.qanat_lines): date palms and reed pools at the wells. `wadi_bed` (L1.buried_valleys, hf.L3.wadis): dry tamarisk channels. `mesa_and_hoodoo` (L1.mesas, L2.sandstorms): red stone pillars and vulture ledges. `scorpion_ground` (L1.reg_plain, L2.hot_dakfron, hf.L4.scorpions): scorpion warrens in the gravel and rock. `serpent_sands` (L1.erg, L2.sandstorms, hf.L4.sand_serpents): sand-serpent hunting grounds. `dragon_dens` (L1.dakfron_hot_plain, L1.mesas, hf.L4.returning_dragons): high mesa and hot-plain dragon roosts |
| L5 | `caravan_tolls` (L3.owned_wells, hf.L5.caravans_and_salt): well dues along the desert road. `salt_trade` (L1.erg, L3.owned_wells, hf.L5.caravans_and_salt): pan salt and rock salt trains. `oasis_dates` (L3.owned_wells, L4.oasis_grove, hf.L5.oasis_dates): Product: rock salt, dates, desert horses' tack, Alik'r blades |
| L6 | `caravanserai_city` (L3.owned_wells, L5.caravan_tolls): Berbaaqnia, seat of the Alik'r Desert fief. `well_towns` (L3.qanat_lines, L5.oasis_dates): Pheonahator (seat of the Dak'fron fief) and Bubyrydata. `south_coast_cities` (L3.owned_wells, L5.caravan_tolls, hf.L6.harbour_and_oasis_cities): Skaven, Hegathe and Gilane on the Arena road |
| L7 | `crown_oasis_lords` (L3.owned_wells, L6.caravanserai_city, hf.L7.crowns_and_forebears): House Alik'ra and the well-owning nobility. `nomad_tribes` (L3.hidden_wells, L4.erg_dunes, hf.L7.nomads): free desert clans outside the city walls. `caravan_men` (L5.caravan_tolls, L6.caravanserai_city): camel and pack-horse drivers |
| L8 | `yokudan_gods` (L6.caravanserai_city, L7.crown_oasis_lords, hf.L8.yokudan_gods): strict Crown devotion to Tu'whacca, Ruptga and Zeht. `stendarr_patron` (L6.caravanserai_city, L6.well_towns, hf.L8.eight_with_yokudan_faces): Stendarr in Berbaaqnia and Pheonahator. `well_oaths` (L3.owned_wells, L7.nomad_tribes): a sworn oath at a well cannot be broken |
| L9 | `well_feud` (L3.owned_wells, L7.crown_oasis_lords, L7.nomad_tribes, hf.L9.well_feuds): a Crown lord and a tribe claim the same well. `dragon_fear` (L4.dragon_dens, L6.well_towns): dragons waking over Dak'fron's wells. `ra_gada_war_camps` (L7.nomad_tribes, L8.well_oaths, hf.L9.ra_gada_bands): armed sword-bands camped along the caravan track |
| L10 | `na_totambu_city` (L1.erg, L8.yokudan_gods, L9.well_feud): buried royal city in the dunes (lost_city_of_the_na_totambu). `volenfell` (L1.erg, L9.well_feud, hf.L10.dwemer_halls): the sand-choked Dwemer capital. `web_of_anker` (L4.scorpion_ground, L9.dragon_fear): the great scorpion warren. `faughtas_guard` (L1.dakfron_hot_plain, L8.yokudan_gods, L9.dragon_fear): a defiled temple keep. `kykos_coven` (L1.dakfron_hot_plain, L4.scorpion_ground, hf.L10.covens): the Sisters of Kykos in Dak'fron. `defiled_temples` (L4.scorpion_ground, L8.yokudan_gods, L9.well_feud, hf.L10.defiled_temples): desecrated desert shrines and Anthotis vampire haunts |
| L11 | `two_waves` (L6.caravanserai_city, L7.crown_oasis_lords, L10.na_totambu_city, hf.L11.two_waves): the Yokudan royal migration into the basin. `dragon_dens` (L1.dakfron_hot_plain, L4.dragon_dens, L9.dragon_fear, hf.L11.dragon_dens): ancient desert dragon mounds. `caravan_wars` (L3.owned_wells, L5.caravan_tolls, L9.well_feud): generations of oasis feuds between cities and tribes |

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
| L1 | `red_clay_valleys` (hf.L1.bay_head_valleys): wide alluvial valleys at the bay head. `ephesus_heights` (hf.L1.dragontail_wall): steep mountain block above the jungle. `santaki_ridge` (hf.L1.dragontail_wall): mountain fiefs on the southern flank. `bangkorai_pass` (hf.L1.dragontail_wall): the fortified pass between High Rock and Hammerfell |
| L2 | `bay_rain` (L1.red_clay_valleys, hf.L2.bay_lift): warm, heavy rain all year. `cloud_line` (L1.ephesus_heights): mist on the heights |
| L3 | `lainlyn_river` (L1.red_clay_valleys, L2.bay_rain, hf.L3.jungle_rivers): western bay-head river. `totambu_river` (L1.red_clay_valleys, L2.bay_rain, hf.L3.jungle_rivers): deep navigable river through Totambu. `floods` (L2.bay_rain): the rivers flood every wet season |
| L4 | `river_jungle` (L2.bay_rain, L3.totambu_river): dense broadleaf rainforest canopy. `red_clay_banks` (L3.floods): reed and mud banks along the rivers. `cloud_forest` (L2.cloud_line): mossy highland woods on Ephesus and Santaki. `giant_glades` (L2.bay_rain, L4.river_jungle, hf.L4.jungle_giants): huge old-growth clearings kept by giants. `river_mouth_mangrove` (L1.red_clay_valleys, L3.lainlyn_river): tidal mangrove channels at the bay head |
| L5 | `timber` (L4.river_jungle, hf.L5.timber_and_river_fish): hardwood logging camps and sawmills. `river_trade` (L3.lainlyn_river, L3.totambu_river): river barges and fish markets. `bridge_traffic` (L1.bangkorai_pass, L3.lainlyn_river): Product: hardwood, river fish, jungle resin, Lainlyn horn work |
| L6 | `river_port_city` (L3.totambu_river, L5.timber): Totambu, seat of the Totambu fief. `ford_towns` (L3.lainlyn_river, L5.river_trade): Lainlyn, Mournoth, Kozanset and Satakalaam on the river roads. `mountain_keeps` (L1.ephesus_heights, L1.santaki_ridge): Ephesus and Santaki in the heights. `garrison_pass` (L1.bangkorai_pass, L5.bridge_traffic): the Bangkorai Garrison stone wall across the pass |
| L7 | `timber_lords` (L5.timber, L6.river_port_city): wealthy logging families and guild factors. `mixed_towns` (L5.bridge_traffic, L6.ford_towns): Breton and Redguard families side by side. `river_labour` (L5.timber, L5.river_trade, L6.river_port_city): woodcutters, boatmen and dock hands |
| L8 | `z_en_patron` (L6.river_port_city, L7.timber_lords, hf.L8.eight_with_yokudan_faces): Z'en in Totambu, Dibella in Lainlyn and Satakalaam, Kynareth in Kozanset, Stendarr in Mournoth and Santaki, and Mara in Ephesus. `na_totambu_reverence` (L6.river_port_city, L7.timber_lords, hf.L8.yokudan_gods): Crown pilgrims come to Totambu. `giant_taboo` (L4.giant_glades, L7.timber_lords): the old families do not cut the giants' groves |
| L9 | `grove_push` (L5.timber, L8.giant_taboo): timber lords and the Order of the Scarab against the taboo. `river_pirates` (L3.lainlyn_river, L5.river_trade): river pirates and Host of the Horn patrols (10 prisons in Lainlyn in Daggerfall's data). `forebear_ports` (L6.ford_towns, L7.mixed_towns, hf.L9.crown_forebear_rift): trading river towns against Crown traditionalists |
| L10 | `scarab_temple` (L3.totambu_river, L4.river_jungle, L9.grove_push, hf.L10.defiled_temples): a drowned river temple in Totambu. `orc_hill_bands` (L1.ephesus_heights, L9.grove_push): Orc strongholds in the Totambu hills (21 in Daggerfall's data). `mournoth_labs` (L6.ford_towns, L9.river_pirates, hf.L10.necromancers): hidden necromancer laboratories in Mournoth. `tide_witches` (L4.river_mouth_mangrove, L9.river_pirates, hf.L10.covens): the Tide Witches of Totambu. `khulari_heights` (L1.ephesus_heights, L6.mountain_keeps, L9.forebear_ports, hf.L10.bloodlines): Khulari and Montalion vampire bloodlines |
| L11 | `na_totambu_line` (L6.river_port_city, L8.na_totambu_reverence, hf.L11.two_waves): the royal Yokudan dynasty remembered at Totambu. `garrison_pass_wars` (L1.bangkorai_pass, L6.garrison_pass, L9.forebear_ports): the pass changed hands in every war between the provinces |

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
| L1 | `black_ridges` (hf.L1.dragontail_wall): basalt, obsidian, sulphur, y 280-420. `red_wall` (hf.L1.dragontail_wall): a red-rock wall and slot canyons in the south, y 300-470. `ore_seams` (hf.L1.dragontail_wall): rich iron, silver and obsidian veins |
| L2 | `mountain_cold` (L1.black_ridges, hf.L2.mountain_cold): freezing nights and snow on the high crests. `fume_haze` (L1.black_ridges): volcanic ash and sulphur haze in the northern valleys. `canyon_shade` (L1.red_wall): deep, cool shade in the southern slot canyons |
| L3 | `snowmelt` (L1.black_ridges, L2.mountain_cold, hf.L3.snowmelt): highland torrents feeding the western wadis. `canyon_springs` (L2.canyon_shade): seep springs in the red-rock gorges. `sulphur_springs` (L1.black_ridges): hot mineral pools around the vents |
| L4 | `volcanic_scree` (L1.black_ridges): black basalt talus and obsidian glints. `fumarole_field` (L1.black_ridges, L3.sulphur_springs): steaming vents and sulphur crust. `canyon_fern` (L3.canyon_springs): shaded fern pockets in the red canyons. `cedar_ridge` (L2.mountain_cold): hardy mountain cedar and juniper above y 260. `vent_daedra` (L1.black_ridges, L4.fumarole_field, hf.L4.vent_daedra): fire spirits around the lava vents |
| L5 | `mines` (L1.ore_seams, hf.L5.dragontail_ore): iron and silver galleries (85 in Daggerfall's data). `obsidian_and_sulphur` (L1.black_ridges, L3.sulphur_springs): Product: iron, obsidian blades, sulphur, mountain cedar |
| L6 | `terrace_city` (L1.black_ridges, L5.mines): Dragontail, seat of the Dragontail Mountains fief. `mine_villages` (L5.mines): Morenanu and the ridge mining camps. `pass_to_kvatch` (L1.red_wall, L3.canyon_springs): Rihad's bazaar city and the southern road over the wall |
| L7 | `ore_lords` (L5.mines, L6.terrace_city): House Dragontail and the mine proprietors. `debt_miners` (L5.mines, L6.mine_villages, hf.L7.debt_miners): miners bound by debt tallies to the black-stone terraces |
| L8 | `arkay_patron` (L6.terrace_city, L7.ore_lords, hf.L8.eight_with_yokudan_faces): Arkay's temple watching over the mountain dead. `fire_pacts` (L4.vent_daedra, L7.debt_miners): the Devilrock coven trades with fire spirits |
| L9 | `debt_unrest` (L7.debt_miners, L8.fire_pacts): desperate miners strike bargains or riot against the debt-houses. `arms_for_both` (L5.mines, L7.ore_lords, hf.L9.crown_forebear_rift): both Crowns and Forebears buy Dragontail steel |
| L10 | `scourg_barrow` (L1.black_ridges, L8.arkay_patron, L9.debt_unrest, hf.L10.necropolises): the greatest mountain-king tomb in the range. `devilrock_coven` (L4.vent_daedra, L8.fire_pacts, hf.L10.covens): the Witches of Devilrock. `mountain_witches` (L1.red_wall, L4.cedar_ridge, hf.L10.covens): high ridge witch circle. `lava_tubes` (L1.black_ridges, L4.vent_daedra, hf.L10.lava_tubes): volcanic caverns beneath the mines. `khulari_mines` (L5.mines, L9.debt_unrest, hf.L10.bloodlines): Khulari vampire haunts |
| L11 | `scourg_kings` (L1.black_ridges, L6.terrace_city, L10.scourg_barrow): ancient mountain rulers entombed in Scourg Barrow. `imperial_border` (L1.red_wall, L6.pass_to_kvatch, L9.arms_for_both, hf.L11.first_treaty): the wall was the Empire's way in |

### 1.5 `hammerfell/stros_mkai` (existing, 0.7 km², small; Redguard layout)

**World truth.** A small, two-lobed island in the southern sea. A harbour town (Port Hunding) climbs the east side of
a south-facing bay toward a walled palace plateau, and a high western plateau holds a Dwemer ruin. Both treaties that
bound Hammerfell to the Empire were signed here: the first after the Crowns lost at Hunding Bay, the second after
Hammerfell beat the Dominion. That makes the island neutral ground that both Crowns and Forebears covet. Smugglers use
the cave behind the waterfall. A necromancer's tower stands on an islet off the north-west coast, and the islanders do
not row there.

| Layer | Fields |
| --- | --- |
| L1 | `two_lobes` (hf.L1.stros_island): east lobe (town and palace plateau), west lobe (high plateau). `harbour_bay`: a south-facing bay. `waterfall_cliff`: steep cliff north-west of the town. `south_point`: rocky southern headland. `necromancer_islet`: isolated rock off the north-west coast |
| L2 | `sea_climate` (L1.harbour_bay, hf.L2.abecean_storms): warm, wet, storm-prone. `plateau_wind` (L1.two_lobes): steady salt gusts over the western heights |
| L3 | `waterfall_stream` (L1.waterfall_cliff): freshwater stream dropping into a gorge pool. `palace_cistern` (L1.two_lobes, L2.sea_climate, hf.L3.water_is_power): stone reservoirs under the palace plateau |
| L4 | `island_palm_coast` (L2.sea_climate): white sand and palms around the bay. `jungle_hills` (L2.sea_climate, L3.waterfall_stream): the east hills (Redguard's jungle camp). `plateau_scrub` (L2.plateau_wind): wind-bent scrub on the western lobe. `reef_shallows` (L1.harbour_bay, L1.south_point): coral and wreck-strewn shallows |
| L5 | `harbour_trade` (L1.harbour_bay): Abecean shipping and treaty delegations. `dwemer_salvage` (L1.two_lobes, L4.plateau_scrub): brass scrap and gears from the western plateau. `shipwrights` (L1.harbour_bay, L4.jungle_hills): boatyards on the west quay |
| L6 | `port_hunding` (L1.harbour_bay, L5.harbour_trade): town on the bay's east shore, warehouse and harbour tower on the west quay. `palace_plateau` (L1.two_lobes, L3.palace_cistern): walled governor's palace north-east of the town. `lighthouse_point` (L1.south_point, L5.harbour_trade): the great beacon on the south point |
| L7 | `harbour_lord_and_merchants` (L5.harbour_trade, L6.port_hunding, L6.palace_plateau): Forebear port factors and treaty envoys. `sailors_and_smugglers` (L5.harbour_trade, L6.port_hunding): island crews who know every hidden cove |
| L8 | `hunding_memory` (L6.port_hunding, L7.harbour_lord_and_merchants): the sword-saint's name on the port. `fear_of_the_islet` (L1.necromancer_islet, L7.sailors_and_smugglers, hf.L8.hate_necromancy): no islander rows toward the north-west rock. `treaty_pride` (L6.palace_plateau, L7.harbour_lord_and_merchants): pride in the Second Treaty victory |
| L9 | `treaty_ground` (L6.palace_plateau, L8.treaty_pride, hf.L9.crown_forebear_rift): neutral ground both factions want. `smuggling_ring` (L1.waterfall_cliff, L7.sailors_and_smugglers): contraband moving behind the customs quay |
| L10 | `catacombs` (L1.two_lobes, L6.port_hunding, L9.treaty_ground): catacombs under the town centre with entrance by the bell tower. `waterfall_hideout` (L1.waterfall_cliff, L3.waterfall_stream, L9.smuggling_ring, hf.L10.smugglers_coves): smugglers' grotto behind the falls. `ngastas_isle` (L1.necromancer_islet, L8.fear_of_the_islet, L9.treaty_ground, hf.L10.necromancers): the necromancer's tower on the north-west islet. `bthzark` (L1.two_lobes, L5.dwemer_salvage, hf.L10.dwemer_halls): Dwemer ruin on the western plateau |
| L11 | `hunding_bay_battle` (L1.harbour_bay, L6.port_hunding, L8.hunding_memory, hf.L11.first_treaty): the naval battle that broke the Crown fleet. `first_treaty` (L6.palace_plateau, L9.treaty_ground, hf.L11.first_treaty): the Second Era compact with Tiber Septim. `second_treaty` (L6.palace_plateau, L8.treaty_pride, L9.treaty_ground, hf.L11.great_war): the Fourth Era peace after driving out the Dominion. `garrison_rising` (L6.port_hunding, L9.treaty_ground, L10.catacombs): Cyrus's uprising against the Imperial garrison in the late Second Era |

### 1.6 `hammerfell/craglorn` (2.3 km²)

**World truth.** Craglorn is a high, dry tableland of red badlands, wind-carved spires and star-fall craters wedged
between the Dragontail Mountains, Bangkorai and Skyrim's Falkreath pass. In the east, Elinhir climbs granite tiers as
a city of mages, star-watchers and stone-cutters; in the north-west, Dragonstar rings its famous gladiator arena inside
a border fort. Long before the Redguards landed, Nedic star-cults built observatories and vaulted bastions into the
crags, and fallen sky-shards still fuse the sand into eerie glass. Today arena patrons fix fights in Dragonstar while
serpents and Celestial cultists stir in the ancient pinnacles.

| Layer | Fields |
| --- | --- |
| L1 | `craglorn_badlands` (hf.L1.craglorn_badlands): eroded red badlands, hoodoo canyons and high tablelands. `starfall_craters` (L1.craglorn_badlands): round impact craters lined with fused glass and sky-iron. `elinhir_granite_tiers` (hf.L1.dragontail_wall): stepped granite ridges toward the Falkreath pass |
| L2 | `high_dry_air` (L1.craglorn_badlands, hf.L2.dry_interior): thin, dry air and cloudless nights ideal for watching the stars. `pass_chill` (L1.elinhir_granite_tiers, hf.L2.mountain_cold): cold mountain gusts sweeping down from the northern border |
| L3 | `canyon_seeps` (L1.craglorn_badlands, L2.high_dry_air, hf.L3.wadis): seasonal canyon streams and deep rock cisterns. `crater_basins` (L1.starfall_craters, L2.high_dry_air, L3.canyon_seeps): mineral-tainted pools in the old impact craters |
| L4 | `badland_scrub` (L1.craglorn_badlands, L2.high_dry_air): juniper, thornbush and red-rock lichen. `crater_beasts` (L1.starfall_craters, L3.crater_basins, hf.L4.scorpions): giant scorpions, dune rippers and crystal-warped creatures. `ridge_vultures` (L1.elinhir_granite_tiers): vultures and mountain lions on the crags |
| L5 | `crater_glass_and_ore` (L1.starfall_craters, L1.elinhir_granite_tiers, hf.L5.dragontail_ore): sky-glass, granite quarrying and highland iron mines. `arena_wagering` (L3.canyon_seeps): gladiator bouts, beast-trapping and border caravan trade at Dragonstar. `pass_trade` (L1.elinhir_granite_tiers): the highway between Elinhir, Mournoth and Falkreath |
| L6 | `elinhir_city` (L1.elinhir_granite_tiers, L5.crater_glass_and_ore): the tiered granite city of towers and star-schools (highland-tier-spire). `dragonstar_city` (L3.canyon_seeps, L5.arena_wagering): the arena-ringed border city (arena-ringed-fort). `scavenger_camps` (L1.starfall_craters, L5.crater_glass_and_ore): prospector and star-watcher camps around the craters |
| L7 | `mage_patrons` (L5.crater_glass_and_ore, L6.elinhir_city): Elinhir's scholar-nobles and astrologers. `arena_promoters_and_fighters` (L5.arena_wagering, L6.dragonstar_city, hf.L7.sword_rank): fight-fixers, trainers and debt-bound gladiators in Dragonstar. `border_caravaneers` (L5.pass_trade, L6.dragonstar_city): Nord, Breton and Redguard traders on the pass road |
| L8 | `star_watching` (L2.high_dry_air, L6.elinhir_city, L7.mage_patrons): ancient astronomical traditions reading the constellations (Warrior, Mage, Thief and Serpent). `arena_glory` (L6.dragonstar_city, L7.arena_promoters_and_fighters, hf.L8.sword_path): blood-sport honour in the Dragonstar ring. `celestial_reverence` (L1.starfall_craters, L7.mage_patrons): awe and dread of the Nedic Celestial guardians |
| L9 | `fixed_bouts` (L5.arena_wagering, L7.arena_promoters_and_fighters, L8.arena_glory): corrupt patrons rig the Dragonstar seven-fight circuit. `serpent_cult_rising` (L8.celestial_reverence, hf.L9.kings_and_lords): Scaled Court fanatics awaken the old Nedic star-shrines. `border_friction` (L5.pass_trade, L6.dragonstar_city): northern border tensions with Skyrim and Cyrodiil |
| L10 | `sanctum_ophidia` (L1.craglorn_badlands, L8.celestial_reverence, L9.serpent_cult_rising): the Serpent cult's subterranean bastion and temple. `skyreach_pinnacle` (L1.elinhir_granite_tiers, L8.star_watching, L9.serpent_cult_rising): a high Nedic observatory city spanning the peaks. `aetherian_archive` (L1.starfall_craters, L7.mage_patrons, L9.serpent_cult_rising): a sealed trial-vault of star magic. `valley_of_scars` (L1.craglorn_badlands, L4.crater_beasts, L9.serpent_cult_rising): Nedic catacombs and scorpion-haunted fissures |
| L11 | `nedic_star_empire` (L1.elinhir_granite_tiers, L8.celestial_reverence, L10.skyreach_pinnacle, hf.L11.before_the_redguards): the Nedic star-cities built before the Ra Gada invasion. `fall_of_the_shards` (L1.starfall_craters, L10.aetherian_archive, L10.valley_of_scars): the ancient sky-falls that scarred the badlands (lore-web ham_2) |

### 1.7 `hammerfell/hews_bane` (3.9 km²)

**World truth.** Hew's Bane is a sun-baked peninsula of limestone headlands, sea arches and sheltered coves on the
southern Abecean coast. Taneth rises in cliff-palace terraces above a wealthy harbour, while Abah's Landing crowds a
stilt-pier cove where merchant syndicates and the Thieves Guild share the docks. During the Great War, Taneth fell to
the Aldmeri Dominion after traitors opened its gates; twenty years later, the city is rebuilding through sea trade, but
Alik'r hunters still track the remaining collaborators from Hammerfell to Skyrim. Ancient Yokudan necropolises and
flooded slave-coves line the cliffs beyond the terraced pool gardens.

| Layer | Fields |
| --- | --- |
| L1 | `abecean_headlands` (hf.L1.abecean_coast): steep limestone headlands, sea arches and hidden coves on the southern sea. `taneth_cliff_shelf` (L1.abecean_headlands): stepped coastal cliffs overlooking the eastern anchorage. `abahs_cove` (L1.abecean_headlands): a sinkhole-rimmed harbour basin on the peninsula tip |
| L2 | `warm_abecean_breeze` (L1.abecean_headlands, hf.L2.winter_rains): hot, bright summers and mild winter showers. `southern_squalls` (L1.abecean_headlands, hf.L2.abecean_storms): sudden late-summer gales driving ships into the coves |
| L3 | `cliff_cisterns` (L1.taneth_cliff_shelf, L2.warm_abecean_breeze, hf.L3.water_is_power): stepped aqueducts and pool-garden reservoirs catching winter rain. `tidal_caverns` (L1.abahs_cove, L2.southern_squalls): sea-level grottoes and flooded limestone channels under the docks |
| L4 | `palm_and_cypress_terraces` (L2.warm_abecean_breeze, L3.cliff_cisterns): date palms, cypresses and scented pool gardens on the shelves. `sea_cliff_harpies` (L1.abecean_headlands, hf.L4.coast_harpies): harpies and sea birds nesting on the outer arches. `cove_reefs` (L1.abahs_cove, L3.tidal_caverns): reef shallows and giant crabs along the peninsula |
| L5 | `abecean_shipping` (L1.taneth_cliff_shelf, L1.abahs_cove, hf.L5.dyes_and_spice): sea trade in pearls, spices, wine and textiles along the Arena coast road. `smuggled_cargo` (L3.tidal_caverns, L5.abecean_shipping): untaxed goods moving through Abah's Landing. `terrace_perfume` (L4.palm_and_cypress_terraces): pressed oils, glasswork and dye vats |
| L6 | `taneth_city` (L1.taneth_cliff_shelf, L3.cliff_cisterns, L5.abecean_shipping): the terraced cliff-palace city rebuilding after the war (cliff-palace-terraces). `abahs_landing` (L1.abahs_cove, L5.smuggled_cargo): the stilt-pier merchant port on the peninsula (stilt-pier-harbour). `necropolis_cliffs` (L1.abecean_headlands, L3.cliff_cisterns): tiered tomb gates cut into the inland bluffs |
| L7 | `forebear_merchant_syndicates` (L5.abecean_shipping, L6.taneth_city, L6.abahs_landing, hf.L7.crowns_and_forebears): rich Forebear shipping houses and counting-house lords. `dock_thieves_and_cutpurses` (L5.smuggled_cargo, L6.abahs_landing): the Thieves Guild and harbour gangs who rule the stilt walkways. `hunted_traitors` (L6.taneth_city, hf.L7.outcasts): families accused of opening Taneth's gates to the Dominion |
| L8 | `forebear_temples` (L6.taneth_city, L7.forebear_merchant_syndicates, hf.L8.eight_with_yokudan_faces): coastal temples of the Eight alongside Tu'whacca's tomb-keepers. `no_forgiveness_oath` (L7.hunted_traitors, hf.L8.hate_thalmor): public memorials and bitter verses remembering the occupation. `nocturnal_coin_custom` (L6.abahs_landing, L7.dock_thieves_and_cutpurses): dockside superstitions and thieves' marks at the Landing |
| L9 | `collaborator_hunt` (L7.hunted_traitors, L8.no_forgiveness_oath, hf.L9.collaborators): Alik'r swordsmen and Taneth's court hunt those who betrayed the city in the Great War. `syndicate_vs_thieves` (L5.smuggled_cargo, L7.forebear_merchant_syndicates, L7.dock_thieves_and_cutpurses): merchant-house guards and the Thieves Guild contest Abah's Landing |
| L10 | `ahaleb_necropolis` (L1.abecean_headlands, L6.necropolis_cliffs, L9.collaborator_hunt, hf.L10.necropolises): a vast cliffside Yokudan necropolis disturbed by tomb-robbers. `thrall_cove` (L1.abahs_cove, L3.tidal_caverns, L9.syndicate_vs_thieves, hf.L10.smugglers_coves): a fortified slaver and smuggler grotto. `hubalajad_palace` (L1.taneth_cliff_shelf, L8.forebear_temples, L9.collaborator_hunt): a grand prince's tomb-palace riddled with traps |
| L11 | `betrayal_of_taneth` (L6.taneth_city, L8.no_forgiveness_oath, L9.collaborator_hunt, hf.L11.great_war): the Great War siege when traitors opened Taneth's gate to the Dominion. `prince_hubalajad_folly` (L6.necropolis_cliffs, L10.hubalajad_palace, hf.L11.two_waves): the extravagant Yokudan prince who built a palace for the dead (lore-web ham_3) |

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
* NirnCraft capitals get 2 inns and a large temple quarter with a necropolis gate. The jungle cities get 2 inns and a
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

| Daggerfall type (where it clusters) | NirnCraft variant | Hammerfell reason |
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
| Fiefs | `nirncraft/tamriel/fiefs.json`, `Fiefs` | 19 fiefs with seat, lord title, temple, order, coven, bloodline, banner, Crown or Forebear lean | IB2 |
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
