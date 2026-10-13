# High Rock: province plan

Province plan for High Rock under `docs/world/DESIGN-PRINCIPLES.md` (the rulebook) and `docs/world/ILIAC-BAY.md`
(the Daggerfall-based plan for the bay; phases IB1-IB8). The master plan is `docs/world/WORLD-PLAN.md`. Hammerfell,
the other half of the bay, is `docs/world/provinces/hammerfell.md`, and the two files cross-reference each other where
the bay joins them (ferries, the Betony claim, the Orsinium sieges, the knightly orders, the Bjoulsae head).

**Legal line.** Daggerfall, Arena and ESO supply facts only: names, relative positions, borders, climate classes,
counts and the building mix of towns. Every description below is our own. No game text, raster or asset enters the
repo. Invented people are ours. Lore facts are summarised in our own words.

**Era.** Skycraft is set in 4E 201. Every older era is a **history layer** (L11) that must be visible in the world
today: a ruin, a monument, a place name, a grudge, a song.

**Scope.** High Rock is 40.8 km² in Skycraft today, 1.54 times Skyrim (`SCALE-AND-GEOGRAPHY.md` 2.4). We add no more
towns beyond the Iliac selection. Its interior is filled with highland, moor and crag, not new dots. Orsinium and
Wrothgar sit in this province (region `high_rock/wrothgar`, binding anchor Orsinium at -4300, 300).

**Culture in one glance (what makes High Rock not Skyrim and not Hammerfell).**

| Sense | High Rock |
| --- | --- |
| Silhouette | Castles on rock and river islands, slate spires, a keep every few kilometres, walled towns with four gates |
| Palette | Honey sandstone and red tile in Daggerfall, grey granite and blue slate in Wayrest, black slate on the moors, Orc iron in Wrothgar |
| Land | Green, wet and broken: sea bluffs, river meadows, heather moor, haunted oak wood, then the Wrothgarian wall |
| People | A feudal ladder from king to tenant; knights of an order in every seat; witches at the edge of every village |
| Sound | Bells, lutes in crowded taverns (Breton towns hold 14-20 taverns in Daggerfall's data), rain on slate |
| Faith | The Eight, each fief with a patron temple; covens and Daedric bargains in the woods; Malacath in the mountains |
| Danger | Vampire bloodlines tied to fiefs, coven circles, deserter bands from the old petty wars, harpies on the cliffs, Orc raiders |
| Politics | Crowns that were once dozens of kingdoms; every baron remembers being a king |

---

## 1. World model sheet

Field ids are `L<n>.<id>`. A cite in brackets names the cause. Province fields are cited from regions as
`hr.L<n>.<id>`. Data home: `tamriel/world/high_rock.json` (MS22).

### 1.0 Province sheet (`hr`)

**World truth.** High Rock is a wet, broken country of sea bluffs, river meadows and moors, walled on the east by the
Wrothgarian Mountains. The Bretons are a people of mixed Nedic and elven blood, shaped by the Direnni elves who ruled
them first. They split the land into dozens of petty kingdoms. The Warp in the West folded most of the bay's
kingdoms into a few crowns, but every former king is now a baron with a keep, a banner and a grudge. Knights, guilds
and covens fill the gaps that the law leaves. The Orcs hold the mountains and have lost their city more than once.
Wool, hides, grain, ships and ore make the money, and the money flows to the crowns and the merchant guilds.

| Layer | Fields |
| --- | --- |
| L1 Geology | `wrothgar_spine`: a granite and slate range on the east side, peaks y 300-460, passes y 200-240, with iron, silver and orichalcum veins. `bay_plain`: clay and alluvial shore plains from Daggerfall to Wayrest. `sea_bluffs`: sandstone bluffs at Daggerfall and Glenpoint (y 100-120) cut by sea caves. `north_crags`: dark crags and acid moors over granite in Rivenspire and Glenumbra, with peat. `bjoulsae_trench`: the Bjoulsae valley cut from the north-east highlands to the bay head. `balfiera_hill`: a hill island (about y 170) in the eastern bay. `ocean_cliffs`: a cliffed west coast on the open ocean, with sheltered coves inside the bay |
| L2 Climate | `westerlies` (cites L1.ocean_cliffs): ocean winds bring rain to Glenumbra and the bluffs. `bay_fog` (cites L1.bjoulsae_trench, L1.bay_plain): sea fog fills the bay on calm mornings, thickest at the Bjoulsae mouth. `spine_snow` (cites L1.wrothgar_spine): snow above about y 260, and a drier lee on the east side. `mild_shore` (cites L1.bay_plain): the bay shore is mild with a long growing season. `still_woods` (cites L1.north_crags): cold damp air pools in the low northern woods |
| L3 Water | `many_rivers` (cites L2.westerlies): at least six rivers reach the bay. `bjoulsae_artery` (cites L1.bjoulsae_trench): the Bjoulsae is navigable from its mouth to Evermor. `moor_tarns` (cites L1.north_crags, L2.westerlies): bogs and tarns on the moors. `water_is_crossings`: water is plentiful, so power over water means mills, fords and bridges; the lord owns them and the peasants pay to grind and to cross (cites L3.many_rivers) |
| L4 Ecology | `mosaic` (cites L1-L3): see section 2. `cliff_harpies` (cites L1.sea_bluffs): harpies nest on the bluffs. `wood_spiders` (cites L2.still_woods): giant spiders in the haunted woods. `wereboar_woods` (cites L4.mosaic): wereboars and wolves in the oak forests. `spine_beasts` (cites L1.wrothgar_spine): trolls, bears and giants in the high valleys. `restless_dead` (cites L11.many_wars): ghosts and wraiths gather at old battlefields |
| L5 Economy | `wool_and_hides` (cites L4.mosaic): sheep on the moors, hides and tanning in Daggerfall. `grain_orchards` (cites L2.mild_shore): grain, orchards and vines on the shore plains. `fish_salt` (cites L1.sea_bluffs): fishing and salt pans on the coast. `bay_shipping` (cites L3.bjoulsae_artery): Wayrest's ships and the Bjoulsae barges carry the province's trade. `orc_ore` (cites L1.wrothgar_spine): Orc and Breton mines in Wrothgar, orichalcum forged in Orsinium. `tolls` (cites L3.water_is_crossings): bridge and ford tolls fund the keeps. Who gets rich: the crowns (tolls and taxes), Wayrest's merchant guilds (shipping) and the orders (land grants) |
| L6 Settlement | `fief_patchwork` (cites L9.crowns_and_barons, L3.water_is_crossings): a lord's keep with a village every few kilometres, each at a crossing, mill or harbour. `capitals_at_mouths` (cites L3.many_rivers, L5.bay_shipping): Daggerfall and Wayrest sit where rivers meet the bay. `mountain_city` (cites L1.wrothgar_spine): Orsinium on its plateau. `busy_centres` (cites L5.bay_shipping, L5.tolls): Wayrest (the Bjoulsae trade) and Daggerfall (the crown, the road and the ferry) are the crowded hearts. `failing_edges` (cites L2.still_woods, L4.wood_spiders): the haunted northern fiefs and the Kambria march lose people every year |
| L7 Society | `feudal_ladder` (cites L6.fief_patchwork): king, baron, knight, burgher, freeholder, tenant. `burghers` (cites L5.bay_shipping): Wayrest's guild merchants are richer than many barons. `tenants_bound` (cites L3.water_is_crossings): tenants owe labour and milling fees and cannot leave without the lord's leave. `orders_as_police` (cites L9.crowns_and_barons): the knightly orders keep the roads in place of a royal guard. `outcasts` (cites L8.witch_ambivalence): witches, Orcs outside Wrothgar and the Reachfolk of the eastern hills live outside the ladder |
| L8 Culture | `eight_and_patrons` (cites L9.crowns_and_barons): the Eight Divines, with each fief's patron temple a point of pride (Kynareth, Mara, Arkay, Stendarr, Dibella, Julianos, Z'en and the Akatosh Chantry all appear). `direnni_nostalgia` (cites L11.direnni_hegemony): nobles boast of elven blood. `witch_ambivalence` (cites L4.mosaic): villagers fear the covens and still pay them for cures. `crypt_dead` (cites L11.many_wars): the dead lie in crypts under chapels and keeps; ghosts are expected, not shocking. `imperial_loyal` (cites L5.bay_shipping): trade makes the Bretons loyal to the Empire and wary of the Thalmor. `malacath_code` (cites L6.mountain_city): Orcs follow Malacath's code of the stronghold |
| L9 Politics | `crowns_and_barons` (cites L11.warp_in_the_west): Daggerfall and Wayrest rule the bay shore through barons who were kings a few generations ago. `betony_claim` (cites L11.war_of_betony): Daggerfall and Sentinel both claim Betony (see `hammerfell.md`). `orsinium_question` (cites L11.orsinium_sieges): the Orcs want recognition and their old valley; Breton lords want the ore. `north_houses` (cites L1.north_crags): Camlorn, Shornhelm, Northpoint and Evermor are older crowns outside the bay with their own feuds. `empire_and_thalmor` (cites L8.imperial_loyal): the Empire's overlordship is accepted; Thalmor agents watch the mages |
| L10 Hidden | `covens` (cites L8.witch_ambivalence): fourteen covens on the bay, eight of them in High Rock. `bloodlines` (cites L9.crowns_and_barons): vampire bloodlines tied to fief groups (Vraseth, Haarvenu, Thrafey, Garlythi, Lyrezi, Montalion). `undercrofts` (cites L8.crypt_dead): crypts under every keep and chapel. `direnni_vaults` (cites L11.direnni_hegemony). `dwemer_under_spine` (cites L1.wrothgar_spine). `sea_caves` (cites L1.sea_bluffs): privateers and smugglers. `city_shadows` (cites L6.busy_centres): a thieves' den and a Dark Brotherhood sanctuary in Daggerfall |
| L11 History | `nedic_barrows`: the first people's barrows on the moors. `direnni_hegemony`: an elven clan ruled High Rock from Balfiera's tower; the Breton-Direnni victory over the Alessians on the Glenumbra Moors. `orsinium_sieges`: the Orc city fell to a long Breton-Redguard siege in the First Era and has been rebuilt and lost again since. `covenant_era`: the Second Era alliance of Bretons, Redguards and Orcs. `war_of_betony`: Daggerfall fought Sentinel over Betony and its king fell. `warp_in_the_west`: a break in time that left the bay's many kingdoms folded into a few, with every outcome partly true. `many_wars` (cites the above): battlefields everywhere. `great_war`: the recent war with the Dominion |

### 1.1 `high_rock/daggerfall` (new realm, 1.64 km² in the window)

Fiefs (Daggerfall political regions): **Daggerfall** (seat Daggerfall, Kynareth, Knights of the Dragon, Sisters of
the Bluff, Daggerfall Witches, Vraseth), **Glenpoint** (Glenpoint, Z'en, Knights of the Owl, Glenmoril Witches),
**Tulune** (Tulune, Dibella), **Glenumbra Moors** (Grimley, Mara), **Betony** (Whitefort, Mara, island fief),
**Ilessan Hills** (Ilessan Hills, Arkay), **Northmoor** (Northmoor, Mara, the Beldama, Garlythi).

**World truth.** The crown's own country: sea bluffs, a river through the capital, sheep on the moors and fog in the
coves. Daggerfall grew rich on hides, wool and the river toll, and it rules seven fiefs whose lords were kings before
the Warp. The Glenpoint headland watches for smugglers and Sentinel's ships, because the old war over Betony was
never settled. The Vraseth bloodline feeds quietly in the moor keeps. Witches live on the bluffs, and the court uses
them while pretending not to.

| Layer | Fields |
| --- | --- |
| L1 | `dagger_bluffs` (hr.L1.sea_bluffs): bluffs with sea caves either side of the river mouth. `glenpoint_headland`. `ilessan_hills`: hills to y 180 behind the shore. `moor_plateau` (hr.L1.north_crags): the Glenumbra Moors and Northmoor on peat and granite |
| L2 | `wet_west` (hr.L2.westerlies). `cove_fog` (L1.dagger_bluffs). `moor_wind` (L1.moor_plateau): treeless, wind-scoured tops |
| L3 | `daggerfall_river` (hr.L3.many_rivers): the river splits round the castle island. `bridge_toll` (L3.daggerfall_river, hr.L3.water_is_crossings): two bridges, two toll-houses. `moor_tarns` |
| L4 | `tannery_reeds` (L3.daggerfall_river). `heather_moor`, `sheep_pasture` (L1.moor_plateau). `bluff_harpies` (L1.dagger_bluffs). `ilessan_spider_dells` (L1.ilessan_hills; Daggerfall's data has 18 spider nests here) |
| L5 | `hides_and_wool` (L4.sheep_pasture, hr.L5.wool_and_hides): Tannery Row and the wool market. `river_toll` (L3.bridge_toll). `salt_pans` (L1.glenpoint_headland). Product: tanned leather, moor wool, Glenpoint salt |
| L6 | `capital_on_island` (L3.daggerfall_river). `glenpoint_watch` (L1.glenpoint_headland, hr.L9.betony_claim). `moor_keeps` (L1.moor_plateau): Grimley and Northmoor. `ferry_head` (hr.L5.bay_shipping): Daggerfall to Sentinel and Betony |
| L7 | `tanners_quarter` (L5.hides_and_wool): the poor live downwind by the vats. `toll_families` (L5.river_toll): the bridge clerks are a hated, rich caste. `shepherds_tenants` |
| L8 | `kynareth_patron`. `bluff_sisters` (hr.L8.witch_ambivalence): the court consults the Sisters of the Bluff. `lysandus_memory` (hr.L11.war_of_betony): the fallen king is mourned in song and feared as a ghost |
| L9 | `court_vs_barons` (hr.L9.crowns_and_barons): Tulune and Grimley resent the toll. `betony_garrison` (hr.L9.betony_claim). `owl_vs_dragon`: the two orders compete for the king's favour |
| L10 | `vraseth_keeps` (hr.L10.bloodlines). `privateers_hold` (L1.dagger_bluffs, hr.L10.sea_caves). `tristore_lab` (Betony): a sealed laboratory. `castle_necromoghan`: a vampire seat. `under_daggerfall` (hr.L10.city_shadows): thieves' den and sanctuary in the cellars |
| L11 | `glenumbra_moors_battle` (hr.L11.direnni_hegemony): the moor battlefield with its cairns. `betony_war` (hr.L11.war_of_betony). `warp_scars` (hr.L11.warp_in_the_west) |

### 1.2 `high_rock/anticlere` (new realm, 1.48 km²)

Fiefs: **Anticlere** (Mara, Knights of the Flame, Haarvenu), **Shalgora** (Arkay, Haarvenu), **Daenia** (Dibella,
Thrafey), **Urvaius** (Stendarr, Thrafey), **Ykalon** (Mara, Skeffington Witches, Castle Llugwych, haunted
woodlands), **Phrygias** (Stendarr, Garlythi, haunted woodlands).

**World truth.** River meadows under a long cliff line, the breadbasket of the western bay, with a dark wood to the
north. Anticlere is pious and rich in grain, and its Knights of the Flame want to clear the haunted woods of Ykalon
and Phrygias and settle them. Every settler party that goes north loses someone. Castle Llugwych sits in that wood,
and the Skeffington coven sells safe passage. Orc bands from the old wars still hold strongholds in the Phrygias
hills.

| Layer | Fields |
| --- | --- |
| L1 | `anticlere_scarp`: a long cliff line north of the meadows. `river_meadows` (hr.L1.bay_plain). `northern_hollows` (hr.L1.north_crags): wet hollows behind the scarp |
| L2 | `sheltered_meadows` (L1.anticlere_scarp, hr.L2.mild_shore). `hollow_fog` (L1.northern_hollows, hr.L2.still_woods): fog sits in the woods all day |
| L3 | `anticlere_river` (hr.L3.many_rivers). `water_meadows` (L3.anticlere_river): flooded in spring. `mill_chain` (hr.L3.water_is_crossings): a mill at every weir, each a fief's |
| L4 | `water_meadow`, `wheat_fields` (L3.water_meadows). `haunted_wood` (L2.hollow_fog): dead oaks, crows, spider nests (16 in Ykalon in Daggerfall's data). `scarp_scrub` (L1.anticlere_scarp) |
| L5 | `grain_and_mills` (L4.wheat_fields, L3.mill_chain). `charcoal_burners` (L4.haunted_wood): the only trade in the wood. Product: flour, Anticlere ale, haunted-wood nightshade |
| L6 | `anticlere_meadow_city` (L3.anticlere_river). `settler_villages` (L9.flame_crusade): new, half-built villages on the wood edge. `scarp_keeps` (L1.anticlere_scarp): Shalgora and Daenia |
| L7 | `pious_burghers` (L8.mara_patron). `settler_poor` (L6.settler_villages): landless families promised land in the wood |
| L8 | `mara_patron`. `fear_of_the_wood` (L4.haunted_wood). `coven_toll` (hr.L8.witch_ambivalence): settlers pay the Skeffington coven for charms |
| L9 | `flame_crusade` (L5.grain_and_mills, hr.L9.crowns_and_barons): the Knights of the Flame push north to win new fiefs. `ykalon_resists`: the lords of Ykalon and Phrygias prefer the wood empty |
| L10 | `castle_llugwych` (L4.haunted_wood). `skeffington_coven`. `phrygias_orc_bands` (hr.L11.orsinium_sieges; 7 Orc strongholds in Daggerfall's data). `haarvenu_shalgora` (hr.L10.bloodlines) |
| L11 | `petty_kingdoms` (hr.L11.warp_in_the_west). `failed_clearances`: two earlier attempts to settle the wood, their ruins still standing |

### 1.3 `high_rock/dwynnen` (new realm, 0.87 km²)

Fiefs: **Dwynnen** (Dibella, Order of the Raven, Thrafey), **Kambria** (Z'en, Vraseth), **Bhoriane** (Stendarr,
Montalion), **Koegria** (Julianos, Lyrezi), **Alcaire** (Kynareth, Witches of Alcaire, Lyrezi).

**World truth.** A gorge-cut march between the two crowns, the smallest and hardest realm. Dwynnen city spans a
limestone gorge with a bridge. Its Order of the Raven fought on the losing side of the old petty wars and the realm
still owes Wayrest's bankers for it. Disbanded soldiers turned bandit fill the Kambria crossroads, which is why
Daggerfall's data gives Kambria nine prisons and eight bandit holds. Giants and harpies come down from the hills
toward Alcaire and Koegria.

| Layer | Fields |
| --- | --- |
| L1 | `dwynnen_gorge`: a limestone gorge with caves (karst). `march_hills`: hills to the east toward the Wrothgarian foothills. `bhoriane_ore` (hr.L1.wrothgar_spine): small iron and lead workings |
| L2 | `gorge_shade` (L1.dwynnen_gorge): cool, shaded, misty floor. `hill_storms` (L1.march_hills) |
| L3 | `gorge_river` (hr.L3.many_rivers). `gorge_bridge` (L3.gorge_river, hr.L3.water_is_crossings): the one crossing for the north shore road |
| L4 | `gorge_fern_wood` (L2.gorge_shade). `karst_bat_caves` (L1.dwynnen_gorge). `giant_meadows` (L1.march_hills). `harpy_tors` |
| L5 | `bridge_toll` (L3.gorge_bridge). `bhoriane_mines` (L1.bhoriane_ore). `mercenary_trade` (L9.raven_debt). Product: lead, Dwynnen lime, hired swords |
| L6 | `gorge_bridge_city` (L3.gorge_bridge). `crossroads_jail` (L9.deserter_bands): Kambria's prison keep. `frontier_keeps` (L4.giant_meadows): Alcaire and Koegria |
| L7 | `debt_poverty` (L9.raven_debt): high taxes, poor tenants. `ex_soldiers` (L9.deserter_bands) |
| L8 | `dibella_patron`. `raven_omens`: crows read as the order's luck. `alcaire_coven` (hr.L8.witch_ambivalence) |
| L9 | `raven_debt` (hr.L11.warp_in_the_west): the order backed a losing king. `deserter_bands` (L9.raven_debt). `two_crowns_squeeze` (hr.L9.crowns_and_barons): both crowns court Dwynnen |
| L10 | `deserter_holds` (L9.deserter_bands). `hearthsly_citadel` (the realm's largest dungeon in Daggerfall's data; IB7 confirms the name). `thrafey_gorge` (hr.L10.bloodlines). `karst_vaults` (L1.dwynnen_gorge) |
| L11 | `petty_wars` (hr.L11.warp_in_the_west). `raven_defeat` |

### 1.4 `high_rock/wayrest` (existing, 0.80 km² in the window)

Fiefs: **Wayrest** (Akatosh Chantry, Knights of the Rose, Montalion), **Menevia** (Julianos, Tamarilyn Witches,
Lysandus' Tomb, Lyrezi), **Gavaudon** (Z'en, Montalion), **Isle of Balfiera** (Blackhead, Akatosh Chantry, Direnni
Tower, Lyrezi), **Orsinium Area** (Reytry, Arkay, house Orsinium, Lyrezi; Daggerfall's Orsinium site).

**World truth.** The richest city on the bay, at the mouth of the Bjoulsae. Wayrest's merchant guilds own the ships,
the quays and half the barons' debts; the crown owns the harbour chain and wants the rest. Out in the bay the
Direnni Tower stands on Balfiera, older than any Breton crown. Inland, the Orsinium Area is a Breton fief round the
ruins of a fallen Orc city, and Orsinium wants it back. A dead king of Daggerfall lies in Menevia, which is either an
honour or a theft depending on who tells it.

| Layer | Fields |
| --- | --- |
| L1 | `bjoulsae_mouth` (hr.L1.bjoulsae_trench): estuary, channel islands and mud flats. `castle_spur`: a rock spur at one horn of the harbour. `orsinium_valley` (hr.L1.wrothgar_spine): a mountain-wood valley north-east of the city. `balfiera` (hr.L1.balfiera_hill) |
| L2 | `estuary_fog` (hr.L2.bay_fog). `mild_harbour` |
| L3 | `bjoulsae_artery` (hr.L3.bjoulsae_artery). `harbour_chain` (L1.castle_spur): the crown can close the harbour. `tidal_flats` (L1.bjoulsae_mouth) |
| L4 | `salt_marsh`, `mud_flats` (L3.tidal_flats). `channel_isles` (L1.bjoulsae_mouth). `orc_valley_wood` (L1.orsinium_valley) |
| L5 | `guild_shipping` (L3.bjoulsae_artery, hr.L5.bay_shipping). `shipyards`. `chain_dues` (L3.harbour_chain). Product: ships' stores, Bjoulsae fish, Balfiera pilgrim tokens |
| L6 | `harbour_capital` (L1.castle_spur, L3.bjoulsae_artery): the busiest place in High Rock. `pilgrim_isle` (L1.balfiera): Blackhead serves the tower's pilgrims. `border_keep` (L1.orsinium_valley, hr.L9.orsinium_question): Reytry faces the Orc valley |
| L7 | `guild_lords` (L5.guild_shipping, hr.L7.burghers). `dockers` (L5.shipyards): the poor quarter on the flats. `chancery_clerks` |
| L8 | `akatosh_chantry`. `direnni_awe` (hr.L8.direnni_nostalgia). `lysandus_dispute` (hr.L8.crypt_dead) |
| L9 | `crown_vs_guild` (L5.guild_shipping, L5.chain_dues). `orsinium_area_claim` (hr.L9.orsinium_question). `menevia_tomb_quarrel` (hr.L11.war_of_betony) |
| L10 | `woodborne_hall`: a manor house with a hidden purpose. `lysandus_tomb`. `direnni_tower` (hr.L10.direnni_vaults). `tamarilyn_coven`. `montalion_harbour` (hr.L10.bloodlines). `old_orsinium` (hr.L11.orsinium_sieges; see section 6) |
| L11 | `direnni_seat`. `orsinium_strata` (hr.L11.orsinium_sieges). `covenant_capital` (hr.L11.covenant_era): Wayrest led the Second Era alliance |

### 1.5 `high_rock/wrothgar` (existing, extended south; large: about 11-12 km² after IB2, two halves)

Fiefs: **Wrothgarian Mountains** (Newing, Kynareth, house Wrothgaria, Daughters of Wroth, Lyrezi, Shedungent) in the
window. North of the window: the Orc homeland round Orsinium (no Daggerfall fief). Plan as two hold-sized halves:
**Wrothgar South** (Newing's Breton mining baronies) and **Wrothgar North** (Orsinium and the clans).

**World truth.** The mountain wall of High Rock: granite peaks, high cold valleys and the richest ore in the
province. In the north the Orcs keep Orsinium on its plateau and live by Malacath's code in strongholds that each
work a mine. In the south, Breton barons in Newing work the same ore with hired miners and call the valleys theirs.
Orsinium has been sacked and rebuilt more than once, and every fall left a ruin and a grudge. The Daughters of Wroth
keep a coven in the high woods. Dwemer halls run under the range.

| Layer | Fields |
| --- | --- |
| L1 | `granite_peaks` (hr.L1.wrothgar_spine). `orsinium_plateau`. `high_passes`: passes at y 200-240 (Wayrest, Newing, Orsinium). `ore_veins` (hr.L1.wrothgar_spine): iron, silver, orichalcum. `natural_arches`: wind-cut arches on the ridges |
| L2 | `alpine_cold` (hr.L2.spine_snow). `lee_dry` (hr.L2.spine_snow): the east side is drier. `pass_storms` (L1.high_passes) |
| L3 | `snowmelt_streams` (L2.alpine_cold): feed the Bjoulsae. `stronghold_springs` (L1.orsinium_plateau): each stronghold sits on its own spring |
| L4 | `pine_larch_slopes` (L2.alpine_cold). `alpine_meadow`, `scree_fields` (L1.granite_peaks). `snowfields` above y 300. `troll_ledges`, `bear_valleys` (hr.L4.spine_beasts) |
| L5 | `stronghold_mines` (L1.ore_veins): Daggerfall's data gives the range 175 mines and 66 Orc strongholds. `orichalcum_forges` (L5.stronghold_mines). `newing_barons` (L1.ore_veins). Product: orichalcum, iron, Orc steel, goat hides |
| L6 | `orsinium_city` (L1.orsinium_plateau, hr.L6.mountain_city). `stronghold_per_mine` (L3.stronghold_springs, L5.stronghold_mines). `newing_shelf_city` (L5.newing_barons). `pass_inns` (L1.high_passes) |
| L7 | `chief_and_wives` (hr.L8.malacath_code): chief, forge-wife, wise-woman. `clanless` (L7.chief_and_wives): Orcs cast out by the code become raiders. `hired_miners` (L5.newing_barons): poor Breton and Orc labour in the south |
| L8 | `malacath` (hr.L8.malacath_code). `trinimac_memory`: the Orcs' account of their own origin. `kynareth_newing`. `names_matter` (L11.three_sieges): an Orc's name and deeds are his wealth |
| L9 | `ore_rights` (L5.stronghold_mines, L5.newing_barons): Newing's barons against the clans. `orsinium_succession`: chiefs compete for the city's throne by trial. `recognition` (hr.L9.orsinium_question) |
| L10 | `clanless_raiders` (L7.clanless). `daughters_of_wroth`. `shedungent` (a laboratory in Daggerfall's data). `dwemer_halls` (hr.L10.dwemer_under_spine): Mthartzel-style bastions, Rkindaleft, Zthenganaz. `lyrezi_heights` (hr.L10.bloodlines) |
| L11 | `three_sieges` (hr.L11.orsinium_sieges). `paragons_memorial` (marquee `paragons_remembrance`). `covenant_orcs` (hr.L11.covenant_era) |

### 1.6 Frame regions outside the window (kept ESO-era regions, polygons shrunk by IB1)

These keep their ids and get the same eleven layers, condensed here; full sheets are written with their content
phase.

| Region | L1-L3 (land, sky, water) | L4-L6 (life, work, towns) | L7-L9 (people, faith, power) | L10-L11 (hidden, history) |
| --- | --- | --- | --- | --- |
| `high_rock/glenumbra` (about 9.5 km² after IB2, large: halves Camlorn and the western moors) | Ocean cliffs, moors, peat (hr.L1.ocean_cliffs, north_crags); the wettest country in High Rock (hr.L2.westerlies) | Heather and bog, sheep, cliff villages; Camlorn the cathedral city on its hill (`spire-hill-gothic`) | The Glenmoril Wyrd's sacred grove; Camlorn's bells and court; Mara's cathedral | `cath_bedraud`, `tomb_of_lost_kings`, `mthartzel`, `castle_llugwych` moves south into Anticlere (IB3); the Glenumbra Moors battle cairns (hr.L11.direnni_hegemony) |
| `high_rock/rivenspire` (4.8 km²) | Dark crags, gorges, cold (hr.L1.north_crags) | Crag pine, lichen, ravens; Shornhelm the grid town under its red keep; Northpoint the cliff port | Rival noble houses of Shornhelm; a military town's discipline | `doomcrag`, Northpoint's second lamp, crag barrows |
| `high_rock/stormhaven` (about 4.6 km² after IB2) | Rolling woodland north of the bay (hr.L2.mild_shore) | Oak and beech, pilgrim roads; `weeping_giant`, `koeglin_village`, `moiras_hope` | Pilgrim shrines, a Mara heartland | `sanguines_demesne` (Daedric pocket), wayside miracles |
| `high_rock/bjoulsae` (5.1 km²) | The Bjoulsae trench (hr.L1.bjoulsae_trench), a lake with islands | River barges, reed beds, fishers; Evermor's lake-causeway cathedral | Border with Skyrim's Reach across the Druadach: Reachfolk raids, a border fort | Island crypts, drowned undercroft (`evermor` marquee) |

---

## 2. Micro-biomes (MS21)

Each realm needs at least 6 micro-biomes and at least 2 per km². Rules run inside the `TamrielBiomeSource` contexts.
The cause column cites the sheet.

| Micro-biome | Where | Rule sketch | Cause | Effect (flora, weather, creatures, work) |
| --- | --- | --- | --- | --- |
| `sea_bluff_heath` | Daggerfall, Glenpoint, west coast | UPLAND, coast distance < 120, slope > 0.4 | hr.L1.sea_bluffs | Thrift, gorse, short turf; gusts; harpies, gulls; lookout posts |
| `cove_strand` | Bay coves | BEACH, sheltered aspect | hr.L1.ocean_cliffs | Shingle, kelp racks; fog; crabs, smugglers; fishers' huts |
| `salt_marsh` | River mouths, Bjoulsae flats | MARSH, tidal (coast distance < 80) | wayrest.L3.tidal_flats | Samphire, mud channels; mist; wading birds, mudcrabs; salt pans, eel traps |
| `water_meadow` | Anticlere, Daggerfall river | LOWLAND, river distance < 60, flat | anticlere.L3.water_meadows | Willow, rush, buttercups; spring floods; herons; hay and mills |
| `wheat_and_orchard` | Shore plains round keeps | LOWLAND, farmland ring of a settlement | hr.L2.mild_shore | Wheat, apple and pear rows, hedges; mild; crows, hares; tenant farms |
| `oak_beech_wood` | Stormhaven, Dwynnen | LOWLAND/UPLAND, rain shadow < 0.4 | hr.L2.westerlies | Oak, beech, bluebells; dappled light; deer, boar, wereboars at night; charcoal |
| `haunted_wood` | Ykalon, Phrygias, Northmoor edges | LOWLAND hollow, fog index high, north aspect | hr.L2.still_woods | Dead oaks, ivy, crows, fog all day; spiders, ghosts; nightshade gatherers only |
| `heather_moor` | Glenumbra Moors, Northmoor | UPLAND, rain shadow < 0.3, exposed | daggerfall.L1.moor_plateau | Heather, bracken, standing stones; wind and squalls; sheep, ravens; shepherds |
| `peat_bog` | Moor hollows | UPLAND flat, wet | hr.L3.moor_tarns | Sphagnum, cotton grass, black pools; fog; will-o'-lights, bog bodies; peat cutters |
| `limestone_gorge` | Dwynnen | RIVER inside steep relief | dwynnen.L1.dwynnen_gorge | Ferns, moss walls, caves; deep shade; bats; lime kilns |
| `spider_dell` | Ilessan Hills, Ykalon | UPLAND hollow, dense canopy | daggerfall.L4.ilessan_spider_dells | Webbed thickets; still air; giant spiders; none |
| `crag_scrub` | Rivenspire, Wrothgar foothills | UPLAND, slope > 0.6 | hr.L1.north_crags | Juniper, thorn, bare rock; ravens; bandit lookouts |
| `pine_larch_slope` | Wrothgar | UPLAND y 160-280 | wrothgar.L2.alpine_cold | Pine and larch, needle floor; snow flurries; bears, wolves; woodcutters, Orc hunters |
| `alpine_meadow` | Wrothgar passes | PEAK y 220-300, flat | wrothgar.L1.high_passes | Short grass, flowers; clear and cold; goats; herders' summer huts |
| `scree_and_snowfield` | Wrothgar summits | SUMMIT, y > 300 | wrothgar.L1.granite_peaks | Bare scree, snow; blizzards; trolls; none |
| `forge_valley` | Round Orc strongholds | UPLAND within 150 of a stronghold | wrothgar.L5.stronghold_mines | Soot-dark ground, felled slopes, slag heaps; smoke haze; none; mines and forges |
| `island_grass` | Balfiera, Betony | LOWLAND on an island | hr.L1.balfiera_hill | Salt-tolerant grass, wind-bent trees; sea mist; seabirds; pilgrims |
| `reed_lake` | Bjoulsae lakes, Evermor | LAKE margin | hr.L3.bjoulsae_artery | Reeds, alder; morning mist; ducks, fish; reed cutters |

Counts: Daggerfall 9 (bluff heath, cove strand, salt marsh, water meadow, wheat and orchard, heather moor, peat bog,
spider dell, island grass on Betony), Anticlere 7, Dwynnen 6, Wayrest 7, Wrothgar 8. Every realm meets the floor of
6. Wrothgar (about 11 km²) needs 22 by the per-km² rule. If MS21 counts distinct kinds rather than patches, IB2 adds
Wrothgar-only kinds with their own causes: lee-side dry pine (wrothgar.L2.lee_dry), avalanche chutes and cirque tarns
(L1.granite_peaks), steam-vent ground over Dwemer halls (L10.dwemer_halls) and goat terraces round the pass inns.

---

## 3. Map changes and geography

Follows `ILIAC-BAY.md` sections 2 and 10. Phases in brackets.

**Frame (IB1).**

* Apply the similarity transform (scale 6.221, rotation +7.2 degrees) with Daggerfall and Wayrest exact. High Rock
  land has weight 0 in the 23.3 degree bay-opening hinge, so every distance on the High Rock shore is Daggerfall's
  own.
* Trace the bay coast, the islands and the realm and fief polygons with `iliac_trace.py` (coast simplified at 48
  blocks, borders at 64).
* Move Betony to about (-9880, 2840), south-west of Daggerfall. Make Balfiera the large island east of Menevia (about
  -6400, 2900).
* Shrink `glenumbra`, `stormhaven` and `bjoulsae` to the land north of the window.
* The Daggerfall-era towns move to their Daggerfall positions with their ids, for example Glenpoint from (-11050,
  1650) to (-9465, 1927) and Northmoor from (-9750, -1600) to (-9098, 1597).

**Realms and biomes (IB2).** Create three new regions (`daggerfall`, `anticlere` and `dwynnen`), re-cut `wayrest`
and extend `wrothgar` south. Add the new `haunted_wood` biome. Add `fiefs.json` with the 24 High Rock fiefs.

**Relief (IB6).** The province target is p50 22 -> 35, p90 125 -> 160 and ruggedness 3.4 -> 4.5. Daggerfall's
heights are only a macro guide; every landform below is authored as vectors with heights we choose.

| Feature | Vector work | Heights | Why (sheet) |
| --- | --- | --- | --- |
| Daggerfall bluffs and the river island | `areas` bluff band, a river split round a 60 x 40 island | Bluffs y 100-120, sheer to the sea; river at y 64 | daggerfall.L1.dagger_bluffs, L3.daggerfall_river |
| Glenpoint headland | Headland polygon with a cliff edge and sea stacks | y 90-115 | daggerfall.L1.glenpoint_headland |
| Glenumbra Moors and Northmoor | Upland `areas` with low tors, tarns as `lakes` | y 110-150, low relief and hummocky | hr.L1.north_crags |
| Ilessan Hills | `ranges` of rounded hills | y 120-180 | daggerfall.L1.ilessan_hills |
| Anticlere scarp | A 3 km `ranges` spine with a cliff face toward the meadows | y 140-170 over meadows at y 70 | anticlere.L1.anticlere_scarp |
| Dwynnen gorge | `rivers` entry with a 30-40 block deep gorge and a single bridge site | Rim y 120, floor y 80 | dwynnen.L1.dwynnen_gorge |
| Bjoulsae trench and mouth | River as a wide `rivers` channel, estuary islands, mud flats | Valley walls to y 160 upstream; flats at sea level | hr.L1.bjoulsae_trench |
| Balfiera hill island | Island with a central hill | y 170 summit | hr.L1.balfiera_hill |
| Wrothgarian spine | `ranges` spine joined to the existing range north to Orsinium; passes as saddles; 2-3 natural arches as XL landmarks | Peaks y 300-460, passes y 200-240 | wrothgar.L1.granite_peaks, high_passes, natural_arches |
| Orsinium Area valley | Mountain-wood valley NE of Wayrest | Floor y 110, walls y 220 | wayrest.L1.orsinium_valley |

**Rivers (IB6).** At least four High Rock rivers enter the bay: the Daggerfall river (through the castle island), the
Anticlere river (water meadows), the Dwynnen gorge river and the Bjoulsae. Each gets falls where it leaves the
highland (Ilessan Hills, the scarp, the gorge head, the Wrothgar foothills). `hydrology_report.py` lists them. River
names other than the Bjoulsae are ours to coin in IB2 (lang file), never borrowed from fan works.

**Seams.** The Wrothgarian spine continues north to Orsinium and the Bjoulsae north to Evermor. The moors join
Glenumbra's existing moorland. `iliac_trace.py` reports any vector gap over 200 blocks at the window edge.

**Orsinium on the map (owner decision 3).** Daggerfall's Orsinium site lies in the Orsinium Area fief at about
(-5017, 2719), a mountain-wood valley north-east of Wayrest. The fief holds 24 Orc strongholds in Daggerfall's data.
It fits both the geography (a defensible valley under the spine) and the lore (the city of the Daggerfall era stood
there, and the city of our era stands elsewhere). **Recommendation: move `old_orsinium` there.** It keeps its id and
its marquee identity, and is re-cut as a stratified ruin (section 6). The living Orsinium stays on its binding anchor
(-4300, 300). The old site's present position (-3797, -1434) becomes a smaller First-Era siege ruin (a new id in
IB3).

---

## 4. Roads, travel and fast travel

**Topology (IB5).** P1 takes named roads first (`named_roads.json`), then the greedy spanner (stretch 1.45). DFO's
`city_graph.json` only breaks ties. No DFO or Basic Roads pixels are imported.

| Named road | Class | Route (place ids) | Note |
| --- | --- | --- | --- |
| North Shore Road | highway | daggerfall, shalgora, anticlere, urvaius, dwynnen (gorge bridge), kambria, koegria, menevia, wayrest | The spine of the realm; crosses the Dwynnen gorge on one bridge |
| Glenumbra Road | highway | daggerfall, glenpoint, ilessan_hills, northmoor, then north to camlorn | Leaves the window north |
| Wrothgar Pass | road (switchbacks) | wayrest, gavaudon, kirkwall, newing, then north to orsinium | Pass inns at the saddles |
| Orsinium Valley Road | lane | wayrest, reytry, old_orsinium | The contested fief |
| Moor Road | lane | glenpoint, grimley, tulune | Moor keeps |
| Druadach Highway (cross-province) | highway | evermor (or jehanna) to markarth over the Druadach pass | From `SCALE-AND-GEOGRAPHY.md` 6.2 |
| Bjoulsae Bridge link | highway | wayrest to the South Shore Road (see `hammerfell.md` section 4) | `bridge` 160; the only land link across the bay head |

Arena's northern backbone (Northpoint-Shornhelm, Camlorn-Wayrest, Shornhelm-Evermor, Evermor-Jehanna, Farrun-Jehanna)
is the cross-check for the frame regions.

**Ferries and boats (IB5).** Daggerfall to Sentinel (the cross-bay ferry), Daggerfall to Betony, Wayrest to Lainlyn,
and a Wayrest pilgrim boat to Blackhead on Balfiera. Balfiera is reached only by boat (the Direnni Tower moment). The
existing `daggerfall` and `wayrest` ports in `ports.json` gain these routes.

**Fast travel (MS11).** High Rock needs two modes beyond walking. They are **post-coaches** between the capitals and
realm seats (stables outside the gate of Daggerfall, Anticlere, Dwynnen, Wayrest and Newing, plus Camlorn, Shornhelm
and Evermor) and the **bay ferries**. Wilderness nodes are wayside shrines of the fief's patron deity at road forks,
and standing stones on the moors (the Nedic layer). They are placed so that the 90th percentile distance to a node is
500 blocks or less.

**Roads as political map.** Boundary stones with the fief's banner stand where a road crosses a fief border ("Entering
the Barony of Kambria"). Toll-houses stand at the bridges (daggerfall.L3.bridge_toll). An order waystation (Dragon,
Owl, Flame, Raven or Rose) stands on each realm's main road. Danger: deserter bands on the Kambria crossroads, Vraseth
night hunters on the moor roads, Orc raiders on the Wrothgar pass, harpies on the cliff roads.

---

## 5. Settlements

**Rules.** The Daggerfall data says every city is walled with four gates (N, E, S, W) at known grid positions, and no
town or village is walled. In Skycraft every capital, city and town keeps its four gates (`townplan_seeds.json`
gives the u, v of each), with the main gate turned to the nearest road end. Fief keeps are walled at the keep only
(a bailey); their villages are open. The Daggerfall block grid is a zoning hint, never a footprint (ILIAC 6).

**Culture.** Breton towns are tavern-dense: 14-20 taverns per city in Daggerfall's data against 6-12 in coastal
Hammerfell. Each Skycraft capital has 3 inns and each city 2. Guild halls, banks and libraries mark the burgher
class. Every seat has a knightly-order hall where the fief has an order.

### 5.1 Settlements by realm (window)

| Settlement | Tier | Realm / fief | Why here (sheet) | Form and identity |
| --- | --- | --- | --- | --- |
| Daggerfall | capital | daggerfall / Daggerfall | River island at the mouth, the Glenumbra road meets the ferry (daggerfall.L3.daggerfall_river, L6.ferry_head) | `river-castle-twin-bank`; 8x8 grid, 316 buildings in DF; 20 taverns, 3 banks, library, temple of Kynareth, Mages, Fighters, two Dragon halls, thieves' den, sanctuary |
| Glenpoint | town | daggerfall / Glenpoint | Headland over the smugglers' coast and Sentinel's sea lane | `headland-fort-lighthouse`; Owl order hall; salt pans |
| Ilessan Hills | town | daggerfall / Ilessan Hills | Hill market on the Glenumbra road | Arkay patron; hill-terrace town |
| Northmoor | town | daggerfall / Northmoor | Moor crossing, wool market | Mara patron; peat smoke; the Beldama nearby |
| Tulune, Grimley, Whitefort | fief keeps | daggerfall | A coastal tower-house; a moorland shell-keep; Betony's white island fort | Whitefort: Daggerfall's garrison facing Sentinel's claim |
| Anticlere | city | anticlere / Anticlere | Meadow river under the scarp, the grain mart (anticlere.L3.anticlere_river) | `river-meadow`; Flame order hall; Mara cathedral |
| Urvaius, Ykalon | towns | anticlere | Urvaius: a road town; Ykalon: the last town before the haunted wood | Ykalon walled against the wood; settlers' camp outside |
| Shalgora, Daenia, Phrygias | fief keeps | anticlere | A motte by the vampire-haunted wood; a moated manor in the meadows; a fortified priory keep in the haunted hills | |
| Dwynnen | city | dwynnen / Dwynnen | The only bridge over the gorge on the shore road (dwynnen.L3.gorge_bridge) | `gorge-bridge`; Raven order hall; two temples in DF |
| Kambria, Bhoriane, Koegria, Alcaire | fief keeps | dwynnen | A prison keep on the crossroads; a mining keep; a hill keep facing giants; a palisaded frontier keep | |
| Wayrest | capital | wayrest / Wayrest | Harbour at the Bjoulsae mouth (wayrest.L6.harbour_capital) | `bay-castle-wheel`; Rose order hall; Akatosh Chantry; harbour chain; replaces `castleCapital` (IB4) |
| Menevia, Gavaudon, Blackhead, Reytry | fief keeps | wayrest | A scholar's keep by Lysandus' Tomb; a bridge keep on the Wrothgar road; a harbour keep under the Direnni Tower; a border keep facing the Orc valley | |
| Newing | city | wrothgar / Wrothgarian Mountains | Ore market on a shelf under the pass (wrothgar.L6.newing_shelf_city) | `mountain-shelf`; Kynareth patron; ore yards |
| Kirkwall | village | wrothgar | A pass village with an inn | |
| Orsinium | capital | wrothgar (north) | The plateau with springs and ore (wrothgar.L6.orsinium_city) | `stepped-orc-bastions`; the Great Forge; trial ground |
| Orc strongholds | village (stronghold) | wrothgar, Orsinium Area | One per worked mine and spring (wrothgar.L6.stronghold_per_mine) | Chief, forge-wife, wise-woman; palisade; each with its own trial (`morkul_stronghold_high_rock` is the model) |

ILIAC's selection gives the window 2 capitals, 3 cities, 5 towns, 14 fief keeps, 3 villages, 6 farms and 3 inns in
High Rock. Owner decision 1 holds: keep density is built as proposed and judged in the playtest. If it feels crowded,
keeps are thinned as a data change.

### 5.2 Bustling, steady and failing

| Busy (why) | Steady | Failing (why) |
| --- | --- | --- |
| Wayrest: the Bjoulsae trade and the guild fleets. Daggerfall: the crown, the ferry, the Glenumbra road and the hides market. Orsinium: the forges and the trial games draw every clan | Anticlere (grain), Newing (ore), Glenpoint (salt and the watch), Camlorn and Evermor (pilgrims) | Ykalon and Phrygias (the haunted wood takes settlers), Kambria (deserters on the crossroads), Alcaire (giants), Grimley (Vraseth nights on the moor) |

Failing places show it: shuttered houses, a half-built settler village, a gallows at the crossroads, a smaller market,
a quest that can reverse it.

### 5.3 Class disparity (L7 made visible)

| Class | Where they live | What the player sees |
| --- | --- | --- |
| Crown and court | Castle on the island or the spur | Palace weenie, court of 3+ named roles (P-G3), heralds |
| Barons (former kings) | Fief keeps | A lord, steward and knight; an old crown carved over the gate; a grudge line in dialogue |
| Knights of an order | Order halls and waystations | Yards, banners, a rank line (section 7) |
| Burghers and guild merchants | Wayrest quays, Daggerfall bridge-houses | Townhouses (`Wealth` rich, DF quality 14-20), counting houses (`Use.BANK`), libraries |
| Freeholders and craftsmen | Inner streets | Shops and workshops (DF quality 7-13) |
| Tenants and labourers | Outside the walls, tannery and dock quarters | Poor houses (quality 1-6), the lord's mill they must pay, the vats downwind |
| Outcasts | Bluffs, woods, hills | Witches' huts, clanless Orcs, Reachfolk camps in the Bjoulsae hills |

Orc society runs on a different ladder (chief, wives, warriors, the clanless), set inside Wrothgar and never mixed
into the Breton grammar.

---

## 6. Dungeons, underground and landmarks

### 6.1 Daggerfall dungeon types to High Rock themes

| Daggerfall type (where it clusters in DF data) | Skycraft variant | High Rock reason |
| --- | --- | --- |
| Ruined castle (Daggerfall 24, Dwynnen 23, Ilessan 18) | New `breton_keep_ruin` kit | Each one is a former petty kingdom's seat (hr.L11.warp_in_the_west); High Rock's barrow equivalent |
| Crypt, cemetery (Daggerfall 72 cemeteries) | `breton_crypt`; cemeteries become settlement-edge scenes | hr.L8.crypt_dead |
| Vampire haunt (Glenpoint 29, Daggerfall 21, Dwynnen 16) | `vampire_lair`, each tied to its fief's bloodline | hr.L10.bloodlines |
| Coven (Wayrest 19, Ilessan 18, Anticlere 16) | Witch's coven (renamed `hagraven_lair`); the 8 named covens are faction seats | hr.L10.covens |
| Laboratory (Glenpoint 16, Phrygias 6) | `necromancer_lair`, `tower` | Mages and necromancers on the edges of the law |
| Orc stronghold (Wrothgar 66, Orsinium Area 24) | Live strongholds in Wrothgar; `orc_ruin` in the Orsinium Area | wrothgar.L6.stronghold_per_mine, hr.L11.orsinium_sieges |
| Mine (Wrothgar 175) | `mine` | wrothgar.L5.stronghold_mines |
| Natural cave (Wrothgar 127) | `cave`, `troll_cave`; coastal ones `smuggler_cave` | hr.L1.sea_bluffs |
| Human stronghold, prison (Kambria 17) | `bandit_camp` (deserters), garrisoned crown forts | dwynnen.L9.deserter_bands |
| Harpy nest (Northmoor 12) | `den` on cliffs | hr.L4.cliff_harpies |
| Spider nest (Ilessan 18, Ykalon 16) | `spider_cave` | daggerfall.L4.ilessan_spider_dells |
| Giant stronghold (Alcaire 5) | Giant camp | dwynnen.L4.giant_meadows |
| Desecrated temple | `daedric_ruin` | Daedric bargains in the woods |
| (not in DF) Dwemer | `dwemer_ruin` | hr.L10.dwemer_under_spine |
| (not in DF) Direnni | Elven tower ruin (crystal tower builder) | hr.L11.direnni_hegemony |

**Elder-race slot.** High Rock has no Ayleid ruins. Its elder-race layer is **Direnni** (the Altmeri clan's towers
and vaults: the Direnni Tower, ruined towers on Balfiera, vaults under old keeps) and **Dwemer** (Wrothgar's bastions:
`mthartzel`, Rkindaleft, Zthenganaz). Nedic barrows on the moors are the old-culture tombs.

### 6.2 Marquee per realm (each needs at least 1 marquee dungeon and 1 other marquee)

| Realm | Marquee dungeon | Second marquee | Notes |
| --- | --- | --- | --- |
| Daggerfall | Privateer's Hold (sea-cave fortress under the bluffs; exists as `privateers_hold`, moves to about -9859, 2183) | Daggerfall city (river island castle) | Castle Necromoghan and Tristore Laboratory are majors. Tristore lies in Betony in Daggerfall's data, so it belongs to this realm, not to Sentinel (correcting ILIAC 8.2) |
| Anticlere | Castle Llugwych (Ykalon; exists in `glenumbra` at -9900, -50, moves to -8330, 1548) | The haunted wood edge with the half-built settler village (XL: a dead giant oak) | |
| Dwynnen | The gorge citadel (Hearthsly per ILIAC 8.2) | The Dwynnen gorge bridge (XL landmark) | Deserter holds as the signature theme |
| Wayrest | Lysandus' Tomb (Menevia) | Direnni Tower by boat (`direnni_tower`, moves to about -6136, 2941) plus Wayrest's harbour chain | Woodborne Hall is a major |
| Wrothgar South | Shedungent (Daggerfall puts it in the Wrothgarian Mountains at about -4485, 2184; our `shedungent` is a fort in `hammerfell/dragontail`, so IB3 moves it here) | A natural arch over the Orc road (`wrothgarian_mountains`) | |
| Wrothgar North | `old_orsinium` re-cut (below), or `mthartzel` if the owner keeps `old_orsinium` north | Orsinium city | |

**Scourg Barrow.** Daggerfall puts it in the Dragontail Mountains (Hammerfell), where it is that realm's marquee. Our
`scourg_barrow` currently sits in Wrothgar (-5300, 50) as an `orc_ruin`. IB3 moves it to the Dragontail (see
`hammerfell.md` section 6). Ids never change.

**`old_orsinium` as a stratified ruin.** The site holds three cities on top of each other, read as you descend.

1. **The newest layer** is the Daggerfall-era city: broken bastions open to the sky.
2. **Under it** lies the Second-Era rebuilding: forge halls.
3. **At the bottom** is the First-Era city: sloping halls blackened by the long siege.

Each layer has its own enemies, according to who uses it now:

* clanless raiders at the top;
* the restless dead of the siege in the middle;
* a buried chief (`Old Chief Uzgash the Unburied`, MARQUEE.md) at the bottom.

The loop returns through a siege tunnel dug by the besiegers, which is the Hammerfell link: the Redguard order of
Diagna fought here. The dungeon carries the marquee quest "The Name Beneath the City" and the lore-web arc hr_3.

### 6.3 What lies below (L10 per realm)

* **Daggerfall:** sea caves under the bluffs (privateers), cellars under the city (the thieves and the sanctuary),
  Vraseth crypts under the moor keeps, and Mantellan Crux as a pocket realm reached from a bluff cave (not overworld
  ground).
* **Anticlere:** the haunted wood's sunken chapels from the failed clearances, Llugwych's dungeons and spider dells.
* **Dwynnen:** karst caves in the gorge walls, deserter holds in old quarries and Thrafey vaults.
* **Wayrest:** the drowned undercroft of the harbour, Direnni vaults under Balfiera and Lysandus' crypt.
* **Wrothgar:** mines that break into Dwemer halls, the strata of old Orsinium, troll caves and the Daughters of
  Wroth's grotto.

### 6.4 Landmarks and identity

* XL weenies (at least 1 per realm, plus 1 per 3 km²):
  * the Daggerfall castle island;
  * the dead giant oak of the haunted wood;
  * the Dwynnen gorge bridge;
  * the Direnni Tower (seen from the whole eastern bay);
  * the Wrothgar arch;
  * the Orsinium bastions.
* L kinds of our own: sea stacks, a bog causeway, a ring of standing stones, a hollow-oak shrine, gorge falls and a
  harbour chain tower.
* M scenes: battle cairns on the moor, a gallows crossroads, a coven circle, a toll-house, a settler grave and a
  boundary stone.

### 6.5 Dungeon floor per realm

Skyrim median 8.9/km² (abs. 6.7): Daggerfall 15, Anticlere 13, Dwynnen 8, Wayrest 7, Wrothgar (window) 17, Wrothgar
whole about 96. ILIAC's selection supplies 16, 14, 7, 11 and 16 for the window. **Every one needs a hook, a named boss
or reason and a note (`d_identity` 1.0)**. Today the High Rock figure is 0.033.

---

## 7. Quests

### 7.1 Province arcs

| Arc | Kind | Spine | Choice and consequence |
| --- | --- | --- | --- |
| **The Betony Question** (province main story, at least 19 quests, crossing every realm) | Main | An unsigned betrothal letter could restart the old war over Betony. The player, an outsider no court owns, carries it between Daggerfall, Wayrest and Sentinel. On the way the player visits fief keeps whose barons remember being kings, finds the lore-web thread "the golem's key" at Privateer's Hold, Castle Llugwych, Woodborne Hall, Lysandus' Tomb, the Direnni Tower, Shedungent and Castle Necromoghan, and ends at Lysandus' Tomb | **Exclusive choice (P-J1):** swear to Daggerfall or to Wayrest. One border fief (Kambria or Menevia) changes banner on its keep and its road signs. The losing court's knights become cold to the player |
| **The Many Endings** (Warp in the West era layers) | Lore-web mystery, 6 places | Places that remember different outcomes of the Warp: two memorials to one battle naming different victors, a keep with two founding dates, a chapel to a king who both died and lived | The player decides which account the Daggerfall library records. The plaque text changes, and so do one bard's song and a scholar's line |
| **Three Sieges** (Orsinium; spans Wrothgar and Wayrest) | Region chain plus marquee | Prove yourself at the Great Forge, then descend through old Orsinium's strata, then find the First-Era siege camp. The Redguard order of Diagna's camp links to `hammerfell.md` | Back Orsinium's claim to the Orsinium Area valley or the Baron of Reytry's. The Reytry keep's banner changes, Orc traders appear (or vanish) in Wayrest, and one stronghold opens or closes its gate |

### 7.2 Region stories (hold conflict chain, at least 3 quests each, with consequence)

| Realm | Chain | Ends with |
| --- | --- | --- |
| Daggerfall | **The River Toll**: Tulune and Grimley refuse the bridge toll and the crown sends the Dragon knights; the toll-house cheat (`A Toll on the Bridges`) | Tolls raised or cut. The tannery quarter's fortunes and the bridge-house traders change |
| Anticlere | **The Flame and the Fog**: the Knights of the Flame lead settlers into Ykalon; a missing family; the Skeffington coven's price; Castle Llugwych | The settler village is finished or abandoned (a building swap) |
| Dwynnen | **The Raven's Debt**: unpaid Raven soldiers turned bandits at Kambria; the Wayrest bankers; the gorge citadel | The debt is paid (deserters pardoned, Kambria's gallows comes down) or called in (Wayrest's banner over the bridge) |
| Wayrest | **The Boom Chain** (marquee) plus **Crown and Guild**: who controls the chain | The guild hall or the crown gains the chain. Dues, harbour traffic and dialogue change |
| Wrothgar South | **Ore Rights**: Newing's barons against a stronghold over a vein that breaks into a Dwemer hall | A joint mine or a burnt one |
| Wrothgar North | **Hammer on the Gate** (marquee) | The player takes a clan name |

Frame regions keep their marquee chains (Camlorn's bell, Shornhelm's night muster, Northpoint's second lamp,
Evermor's water relic). Each adds one hold conflict: the Glenmoril Wyrd against Camlorn's priests, Shornhelm's houses,
Stormhaven's pilgrim road and the Reachfolk raids on the Bjoulsae.

### 7.3 Factions (P-G1: 2-5 hubs in different cities)

* **Knightly orders (5 in High Rock).** Dragon (Daggerfall), Owl (Glenpoint), Flame (Anticlere), Raven (Dwynnen) and
  Rose (Wayrest). Each has an order hall with a yard and a rank line of 5-8 quests. The orders compete: the player
  can hold rank in only one order per crown.
* **Covens (8).** The Sisters of the Bluff, the Daggerfall Witches, the Glenmoril Witches, the Beldama, the
  Skeffington Witches, the Tamarilyn Witches, the Witches of Alcaire and the Daughters of Wroth. They are seats with a
  friendly face. They offer bargains and summonings on the daedra's days. The MS4 world event is a coven circle
  (High Rock's world-event kind, together with order tourneys).
* **Guilds.** The Mages Guild's chain is in Wayrest and the Fighters Guild's in Sentinel (`hammerfell.md`). There is a
  hall in every realm seat. The thieves' den and a Dark Brotherhood sanctuary are in Daggerfall.
* **Temples.** One temple seat per realm carries a temple line: Kynareth (Daggerfall), Mara (Anticlere), Dibella
  (Dwynnen), the Akatosh Chantry (Wayrest) and Kynareth (Newing). Malacath's line is in Orsinium.
* **Bloodlines.** Six bloodlines (Vraseth, Haarvenu, Thrafey, Garlythi, Lyrezi, Montalion). An afflicted player gets
  the bloodline's chain in that fief group.

### 7.4 Settlement quests and radiant

Each capital needs at least 19 quests, each city at least 3 (target 6), each town at least 2, and each fief keep and
village at least 1 favour. Keep-specific radiant work uses a named lord and a named place:

* a boundary-stone dispute;
* a tenant who fled the mill fee;
* a knight's challenge on a bridge (MS14);
* a witch's cure that went wrong.

Notice boards (MS12) go in every town and larger. The festival (MS16) is the Witches' Festival, with coven circles lit
and an order tourney at Daggerfall. Its date is checked in IB8.

---

## 8. Components layout

### 8.1 Spatial layout (how the province composes)

```
 north (frame)      glenumbra (Camlorn, moors) | rivenspire (Shornhelm, Northpoint) | bjoulsae (Evermor) -> Druadach -> Skyrim
                                   |                      |                              |
 window       daggerfall --North Shore Road-- anticlere -- dwynnen (gorge) -- (stormhaven pilgrim belt) -- wayrest -- Wrothgar Pass -- wrothgar (Newing) -- Orsinium (north)
   (bay shore)   |  \ferry Betony                                                          |   \boat Balfiera          |
                 ferry to Sentinel (hammerfell)                                       Bjoulsae bridge -> South Shore Road (hammerfell)
```

The west is wet, old and crowned. The middle is meadow and haunted wood. The east is mercantile at the harbour, then
rises into Orc mountains. Every realm has one main road, one seat, one marquee pair and one order hall.

### 8.2 Data and code components

| Component | File / system | High Rock content | Phase |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/high_rock.json` (MS22) | Province block, 5 realm blocks (Wrothgar with two halves), 4 frame blocks | before IB3 |
| Regions and polygons | `tools/worldgen/tamriel_vectors.json`, `regions.json` | 3 new realms, re-cut `wayrest`, extended `wrothgar`, shrunk frames | IB1, IB2 |
| Fiefs | `skycraft/tamriel/fiefs.json`, `com.skycraft.tamriel.Fiefs` | 24 fiefs with seat, lord title, temple, order, coven, bloodline, banner | IB2 |
| Biomes | `tools/worldgen/biomes.py`, `palettes.json` | `haunted_wood` plus micro tables (section 2) | IB2, MS21 |
| Places | `places/high_rock.json`, `places/provinces.json` | About 100 window places, the moves, `variant: fief_keep` | IB3 |
| City plans | `skycraft/tamriel/cityplans/*.json` | Daggerfall, Wayrest (replacing `castleCapital`), Anticlere, Dwynnen, Newing, Glenpoint; Orsinium's `stepped-orc-bastions`; style overlays per realm | IB4 |
| Roads, ports | `roads/named_roads.json`, `tamriel/ports.json` | Section 4 | IB5 |
| Terrain | vectors, `hydrology*.py` inputs | Section 3 | IB6 |
| Dungeons | `places` variants, `dungeons/marquee/**`, kits `breton_keep_ruin` | Section 6 | IB7 |
| Quests, factions, lore | `quests/**` (`side_high_rock.json`, `marquee_high_rock.json`, `loreweb_high_rock.json` exist), orders, covens, `region_voice.json` (no High Rock keys yet), `lore/books/<region>.json` | Section 7 | IB8 |
| Events, scenes | `events/high_rock.json` (MS4), `creatures/scenes.json` (MS14) | Coven circles, order tourneys; knight's bridge challenge, deserter ambush, tithe cart, pilgrim band | IB8 |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep (Daggerfall/Arena/ESO fact) | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Bay coast, Betony and Balfiera, the Wrothgarian highlands, the moors | Bluffs, the Anticlere scarp, the Dwynnen gorge, the Bjoulsae trench, Wrothgar's arches, standing-stone moors | DF's smooth 800 m pixels: authored vectors, our heights, eroded relief |
| Politics | 24 fiefs with houses, temples, orders, covens and bloodlines | Barons who were kings: a keep, a court, a banner, a grudge; one fief's banner moves by player choice | Ruler names as the only content |
| Settlements | Name, position, size class, walls and four gates, the service mix | Unique plans per seat; class gradient; tavern-dense Breton towns | 3,444 recombined block towns: 23 curated settlements plus 14 keeps |
| Dungeons | Names, premises, size ranking, main-story sites | Stratified old Orsinium; deserter holds; bloodline lairs per fief | Random-block mazes: culture kits, loops, set pieces |
| Roads | Which towns are neighbours | Named roads with toll-houses, boundary stones and order waystations | Fade-to-black travel |
| Quests | Orders, covens, bloodlines, the three crowns, the golem story as facts | The Betony Question, the Many Endings, Three Sieges, an order line per seat | Generator quests as main content |
| Life | Building counts and quality as a measure of wealth | Industries: tanning, wool, grain, salt, shipping, ore | Nameless crowds |

"What the source did vs what we do" goes into the phase report as a one-row-per-line copy of this table.

---

## 10. Floors, gates and phases

### 10.1 Current against target (`py -3 -I tools/qa/parity.py`, 2026-10-13)

Category scores (Skyrim = 100) and the raw metrics that matter most. The floor is 80% of the Skyrim reference
(`PARITY.md` 5).

| Region | km² | SET | NPC | QST | DUN | LOR | s_npcs (floor 37.5) | q_total /km² (11.2) | d_identity (0.8; target 1.0) | r_notes /km² (44.7) | y_looks (2.1) | l_kinds (10.3) | c_total (25) | d_marquee (>= 1) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| glenumbra | 12.4 | 32 | 26 | 36 | 62 | 16 | 3.2 | 2.42 | 0.05 | 3.1 | 1 | 17 | 14 | 0 |
| rivenspire | 4.8 | 30 | 21 | 31 | 62 | 17 | 4.0 | 2.52 | 0.08 | 4.6 | 1 | 14 | 14 | 0 |
| stormhaven | 6.2 | 36 | **4** | 20 | 61 | **14** | 0.1 | 0.81 | 0.02 | 2.7 | 1 | 11 | 14 | 0 |
| bjoulsae | 5.1 | 36 | 17 | **14** | 58 | **11** | 1.8 | 1.18 | 0.00 | 0.8 | 1 | 4 | 14 | 0 |
| wayrest | 1.5 | 63 | 39 | 80 | 56 | 32 | 16.0 | 11.07 | 0.00 | 13.7 | 2 | 8 | 14 | 0 |
| wrothgar | 10.8 | 23 | 19 | 32 | 72 | **14** | 3.0 | 1.21 | 0.02 | 2.1 | 3 | 12 | 17 | 1 |
| **High Rock** | 40.8 | 35 | 18 | 31 | 64 | 15 | 2.6 | 2.03 | 0.03 | 3.1 | 1.5 | 11 | 14.5 | 0.17 |

Cells in the 40 lowest of the whole map: stormhaven NPC (4), bjoulsae LOR (11), bjoulsae QST (14), stormhaven LOR (14)
and wrothgar LOR (14). The new realms (daggerfall, anticlere and dwynnen) inherit today's glenumbra and stormhaven
numbers until IB3. The gap is depth, not count: dungeon density (11.0/km²) and settlement count already beat Skyrim,
while NPCs, quests, lore and dungeon identity are at 3-20%.

### 10.2 Targets per region (Skyrim median per km², absolute floor never broken)

| Region | km² | Marked places | Dungeons | Settlements | Named NPCs | Merchants | Trainers | Quests | Readables | Micro-biomes | XL |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| daggerfall | 1.64 | 24 | 15 | 3 (has 10: deliberate, fief keeps) | 55 | 8 | 6 | 23 | 320 | 6 | 1 |
| anticlere | 1.48 | 22 | 13 | 3 (has 9) | 50 | 7 | 5 | 21 | 289 | 6 | 1 |
| dwynnen | 0.87 | 13 | 8 | 2 (has 6) | 29 | 4 | 3 | 12 | 170 | 6 | 1 |
| wayrest (window) | 0.80 | 12 | 7 | 2 (has 6) | 27 | 4 | 3 | 11 | 156 | 6 | 1 |
| wrothgar (whole, est.) | ~11 | 158 | 96 | 22 | 363 | 54 | 38 | 151 | 2,106 | 22 | 4 |
| glenumbra (est. after IB2) | ~9.5 | 139 | 85 | 19 | 319 | 48 | 34 | 133 | 1,852 | 19 | 4 |
| rivenspire | 4.8 | 70 | 43 | 10 | 161 | 24 | 17 | 67 | 936 | 10 | 2 |
| stormhaven (est.) | ~4.6 | 67 | 41 | 9 | 155 | 23 | 16 | 64 | 897 | 10 | 2 |
| bjoulsae | 5.1 | 74 | 45 | 10 | 171 | 26 | 18 | 71 | 994 | 11 | 2 |

Settlement-tier depth comes on top (4.4): Daggerfall, Wayrest and Orsinium at least 73 named NPCs and 23 named
buildings each; the cities at least 20 and 11; towns at least 9 and 4; keeps and villages at least 3. Fixed per
region: a hold conflict chain, 2 marquee places, at least 8 inhabitant themes with a signature one (Daggerfall:
privateers; Anticlere: haunted-wood spiders and the coven; Dwynnen: deserter companies; Wayrest: Bjoulsae river
pirates; Wrothgar: clanless Orc raiders), at least 8 world-encounter kinds (3 of them High Rock-only), 3 wonder
moments, 12 rumours, legends and songs, and 12 deed-keyed lines. Areas marked "est." are recomputed with `parity.py`
after IB2.

**Headline targets for High Rock.** A province sheet plus 9 region sheets that pass `worldsheet_lint.py`. Across the
whole province: at least 363 dungeons, all with identity; about 1,370 named NPCs; at least 571 quests; at least 10
marquee dungeons (one per realm and half); an exclusive crown choice; 5 order lines; 8 covens; 2 travel modes; and 1
festival.

### 10.3 Phases mapped onto IB1-IB8 (High Rock share)

| Phase | High Rock work | Gate |
| --- | --- | --- |
| IB1 Frame and vectors | Transform; trace coast, Betony, Balfiera and fief polygons; move the Daggerfall-era towns; shrink the frame regions | `layout_lint.py`; coast agreement >= 0.90; no DF raster in the repo; Haiku review of `preview_places_high_rock.png` |
| IB2 Realms, fiefs, biomes | `daggerfall`, `anticlere`, `dwynnen`; `fiefs.json` (24); `haunted_wood`; MS21 micro tables (section 2); world sheet `high_rock.json` drafted | Fief areas within 10%; `/skybiome census` signature biome first; `worldsheet_lint.py` 0 errors |
| IB3 Places | Selection plus reconciliation; moves for `old_orsinium`, `shedungent` (in), `scourg_barrow` (out), `castle_llugwych`, `privateers_hold`, `direnni_tower`; fief-keep variant. Marquee collision: `weeping_giant` sits 78 blocks from the Phrygias keep, so the inn is re-homed onto the road outside the keep (ILIAC 5.2) | Spacing; 100% on land; marquee `validate.js` passes; no lore-web place dropped; SET does not regress |
| IB4 City plans | Daggerfall, Wayrest, Anticlere, Dwynnen, Newing, Glenpoint; 14 keeps with distinct forms; realm style overlays | Unique `sil`; `town_checks.py`; Haiku review "not a reskin"; 0 unstaffed workplaces |
| IB5 Roads | Section 4 roads, ferries, coaches, boundary stones and toll-houses | `roadplan_lint.py` 0; `reach_lint.py` 100%; corridor daggerfall:anticlere at 0 gaps; F-SIGN >= 95% |
| IB6 Terrain relief | Section 3 landforms and rivers | >= 4 High Rock rivers reach the bay; p90 relief >= Skyrim's median hold; >= 1 XL landform per realm; Spearman >= 0.6 (`.reference` check) |
| IB7 Dungeons | Section 6 variants and kits; 6 marquee plans (Wrothgar two) | `d_identity` = 1.0; >= 5 palettes and >= 8 rosters per realm; F-LOOP |
| IB8 Stories and factions | Section 7 arcs, 5 order lines, 8 covens, 6 bloodlines, region voice, festival | `novelty_lint.py --strict` 0; F-HOOK <= 800; `story_lint.py`; MS20 verbatim 0 |

Frame regions (glenumbra, rivenspire, stormhaven, bjoulsae) follow the same gates in the province content wave after
IB8. Their sheets come first.
