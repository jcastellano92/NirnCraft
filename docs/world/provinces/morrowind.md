# Morrowind: Vvardenfell and the mainland (province plan)

Status: plan, no mod code changed. Governing rules: `docs/world/DESIGN-PRINCIPLES.md`. Geometry, scale and travel:
`docs/world/SCALE-AND-GEOGRAPHY.md` 5.3, 6 and 7. Scorecard: `docs/PARITY.md`. Marquee briefs: `docs/MARQUEE.md`
(Morrowind). Mysteries: `docs/LORE-WEB.md` (mor_1-4; threads t_ash, t_dial, t_sugar, t_falls). Facts came from
`.reference/morrowind_ref` (474 markers, 291 road polylines, 174 signpost junctions, travel links) and
`.reference/arena_ref` (mainland backbone). Morrowind's own positions win over Arena's.

**Legal line.** Place names, positions, which features existed and how many are facts. Every description, quest,
invented person and line below is ours. No game text, names of game characters, rasters or assets.

**Notation.** As in `cyrodiil.md`: `layer.id`, province fields prefixed `P.`, causes in square brackets, `X:` for a
cite into another province's sheet. Region ids drop the `morrowind/` prefix.

**Assumed era (owner decision M1).** Vvardenfell and the mainland are shown in the last years of the Third Era, before
the Red Mountain disaster: the Tribunal still rules as living gods but their power is failing, the Sixth House is
waking under Red Mountain, the Blight rides the ash storms, the Empire holds forts and a trade charter, the Great Houses
scheme, and slavery is still legal. This is a few years before the moment `cyrodiil.md` assumes; the later catastrophe
(the mountain's eruption and the Argonian invasion) is foreshadowing in L9 and L10, never present fact. If
`WORLD-PLAN.md` fixes one continental present, only L9 and L11 rows change.

**Identity in one line.** Alien and proud: a volcanic land of ash, giant mushrooms and insect-shell architecture,
ruled by houses of sorcerers, merchants and warrior-nobles under three living gods, where the dead are kept at home,
outlanders are tolerated at best and slaves till the rice. Never Skyrim: no pine, no barrow, no jarl, no mead hall;
travel by giant flea-insect and boat; the underground belongs to the Dwemer, the ancestors and the Daedra.

---

## 1. World model sheet

#### Region list and the two map fixes it needs

Today's regions mix the island and the mainland in two places (section 3): `molag_amur` is painted on the **mainland
round Necrom**, while Vvardenfell's real Molag Amur is split between `azuras_coast`, `ascadian_isles` and
`red_mountain`; and `telvannis` joins Sadrith Mora's islands to the mainland peninsula. The sub-sheets below are
written for the fixed map: ids marked "proposed" need the owner decision M2.

| Region (id) | km² now | Land | Seat | Size class |
| --- | ---: | --- | --- | --- |
| Ashlands (`vvardenfell_ashlands`) | 2.5 | Vvardenfell | Ald'ruhn | medium |
| West Gash (`west_gash`) | 1.2 | Vvardenfell | Balmora | small |
| Bitter Coast (`bitter_coast`) | 0.5 | Vvardenfell | (Seyda Neen) | small |
| Ascadian Isles (`ascadian_isles`) | 1.7 | Vvardenfell | Vivec | small |
| Azura's Coast (`azuras_coast`) | 1.3 | Vvardenfell | (Tel Branora) | small |
| Molag Amur, Vvardenfell (proposed `molag_amur`, carved out) | ~0.8 | Vvardenfell | Molag Mar | small |
| Grazelands (`grazelands`) | 2.0 | Vvardenfell | Vos | medium |
| Sheogorad (`sheogorad`) | 0.6 | Vvardenfell isles | Dagon Fel | small |
| Red Mountain (`red_mountain`) | 1.6 | Vvardenfell | Ghostgate | small |
| Zafirbel Bay (proposed `zafirbel_bay`, Vvardenfell half of `telvannis`) | ~2.5 | Vvardenfell east isles | Sadrith Mora | medium |
| Telvanni Peninsula (`telvannis`, mainland half) | ~6.2 | mainland | Port Telvannis | large (halves: Port Telvannis / Firewatch) |
| Stonefalls (`stonefalls`) | 5.2 | mainland | Ebonheart | medium (halves: Blacklight north / Ebonheart south) |
| Deshaan (`deshaan`) | 5.5 | mainland | Mournhold (capital) | medium |
| Necrom Coast (proposed `necrom`, today's mainland `molag_amur`) | 4.8 | mainland | Necrom | medium |
| Solstheim (`solstheim`) | 1.0 | island | Raven Rock | held to the Skyrim plan (SCALE 5.1); here only for L9 links (the Empire's ebony colony) |

### 1.1 Province sheet (`tamriel/world/morrowind.json`, block `morrowind`)

**World truth.** Morrowind is the Dunmer homeland: the island of Vvardenfell, with the volcano Red Mountain at its
heart, and the mainland beyond the Inner Sea, walled from Skyrim and Cyrodiil by the Velothi and Valus ranges. Its
people worship the Tribunal, three living gods who rule from Vivec, Mournhold and a hidden city, and honour their own
ancestors whose bones guard every family. Five Great Houses split the land: the Hlaalu traders, the Redoran warriors
and the Telvanni wizards on Vvardenfell; the Indoril priest-nobles and the Dres slave-planters on the mainland. The
Empire keeps forts and a trading charter it calls an alliance, while ash storms carry a plague out of the mountain.

| Layer | Fields `id` (causes): text |
| --- | --- |
| L1 Geology | `red_mountain`: an active volcano at Vvardenfell's centre, with lava channels (foyada) radiating from its crater. `ash_mantle`: wind-blown ash over the north and east. `velothi_wall`: the mainland's western range, the border with Skyrim. `valus_ridge`: the south-western range, the border with Cyrodiil. `inner_sea`: the shallow sea between the island and the mainland. `volcanic_shields`: lava plains and old cones on the mainland's west (Stonefalls). `fungal_soils`: ash-and-loam soils that grow giant mushrooms. `ebony_and_glass`: ebony and raw glass in volcanic rock. `kwama_hills`: egg-mine colonies dug into hills everywhere. `dwemer_deeps`: the vanished Dwemer's cities cut into rock |
| L2 Climate | `ash_storms` (L1.red_mountain, L1.ash_mantle): the mountain's ash rides north and east winds over the Ashlands, Molag Amur and the mainland coast. `blight_storms` (L1.red_mountain, L2.ash_storms): red storms that carry the Blight out of the crater. `wet_south` (L1.inner_sea): the Ascadian Isles and Bitter Coast are mild and rainy. `cold_north` (L1.velothi_wall, L1.inner_sea): Sheogorad and the Velothi foot are cool and stormy. `fumarole_heat` (L1.red_mountain, L1.volcanic_shields): hot ground round vents and lava |
| L3 Water | `odai_and_rivers` (L1.ash_mantle, L2.wet_south): few rivers; the Odai through Balmora is the largest. `ascadian_lakes` (L1.fungal_soils, L2.wet_south): lakes of the fertile south. `inner_sea_coasts` (L1.inner_sea): most settlements are coastal. `cisterns_and_wells` (L1.ash_mantle, L2.ash_storms): ash country lives on cisterns and deep wells. `who_holds_water` (L2.ash_storms, L3.cisterns_and_wells, L3.ascadian_lakes): Great House lords hold the wells in their towns; plantations hold the irrigation; Ashlanders know the hidden springs |
| L4 Ecology | `mushroom_forest` (L1.fungal_soils, L2.wet_south): emperor-parasol and Telvanni giant fungi. `ash_waste` (L1.ash_mantle, L2.ash_storms): scrub, scathecraw and trama root. `grazeland_steppe` (L1.fungal_soils, L2.wet_south): wickwheat and hackle-lo grass. `bitter_swamp` (L2.wet_south, L3.inner_sea_coasts): coastal mire with marshmerrow. `lava_field` (L1.red_mountain, L2.fumarole_heat): black basalt with fire fern. `native_fauna` (L1.fungal_soils, L4.mushroom_forest, L4.ash_waste): guar (herded), netch (floating), silt striders (tamed for travel), kagouti, alit, nix-hounds, cliff racers, kwama, scribs, mudcrabs, slaughterfish and dreugh |
| L5 Economy | `kwama_eggs` (L1.kwama_hills): the staple food harvested from underground egg mines. `saltrice_plantations` (L3.ascadian_lakes, L4.mushroom_forest): slave-worked fields in the south and round Tear. `ebony_mining` (L1.ebony_and_glass): the Empire's chartered ebony and glass mines. `guar_and_netch` (L4.native_fauna): leather, meat and silt-strider caravan haulage. `east_empire_charter` (L3.inner_sea_coasts, L5.ebony_mining): an Imperial trading company with its hall at Ebonheart. `house_trade` (L4.native_fauna, L5.kwama_eggs, L5.guar_and_netch): Hlaalu merchant networks, Telvanni enchanting, Redoran arms. `slave_trade` (L3.ascadian_lakes, L5.saltrice_plantations): Tear's markets and slaver raids into Black Marsh |
| L6 Settlement | `coastal_and_river_towns` (L3.inner_sea_coasts, L3.odai_and_rivers): ports and river towns built in House styles. `house_seats` (L5.house_trade): Balmora (Hlaalu), Ald'ruhn (Redoran), Sadrith Mora (Telvanni), Mournhold (Indoril, capital), Tear (Dres). `temple_cities` (L3.inner_sea_coasts, L5.house_trade): Vivec, Mournhold and Necrom. `imperial_forts_and_ports` (L5.east_empire_charter): Ebonheart, Pelagiad, the Legion forts and Seyda Neen's customs house. `egg_mine_villages` (L5.kwama_eggs): miner hamlets clustered at kwama hills. `ashlander_camps` (L3.who_holds_water, L4.ash_waste): nomad yurts away from the towns. `busy_centres` (L5.house_trade, L6.house_seats): Vivec, Balmora, Mournhold, Ebonheart and Sadrith Mora |
| L7 Society | `house_order` (L5.house_trade, L6.house_seats): everyone belongs to a Great House, the Temple, the Empire or nothing. `caste_ladder` (L6.house_seats, L7.house_order): house nobles and Temple canons above retainers, merchants, commoners, outlanders, Ashlanders and slaves. `slavery` (L5.slave_trade, L7.caste_ladder): Argonians and Khajiit owned by law. `ashlanders` (L6.ashlander_camps): nomad tribes who reject the Temple and the towns. `outlander_scorn` (L6.imperial_forts_and_ports, L7.house_order): foreigners are treated as second-class. `legal_murder` (L6.house_seats, L7.house_order): a licensed guild of assassins (the Morag Tong) settles House disputes by writ. `criminal_syndicate` (L5.house_trade, L7.outlander_scorn): a native crime family (the Camonna Tong) that hates outlanders |
| L8 Belief | `tribunal` (L6.temple_cities, L7.house_order): Almalexia, Sotha Sil and Vivec, living gods, with the Ordinators as their armed priesthood. `ancestor_worship` (L7.house_order, L8.tribunal): family tombs, household shrines and guardian ghosts; bonemeal and ash are sacred. `daedra_veneration` (L7.ashlanders, L8.tribunal): the "good" Daedra (Azura, Boethiah, Mephala) honoured as old teachers while the Four Corners of the House of Troubles are feared. `ashlander_old_ways` (L7.ashlanders): the Ashlanders keep the old Daedric faith and a prophecy of the Nerevarine. `imperial_cult` (L6.imperial_forts_and_ports): Nine Divines chapels in the forts. `pilgrimage` (L6.temple_cities, L8.tribunal): shrines of the Seven Graces and Temple pilgrim routes |
| L9 Politics | `tribunal_failing` (L2.blight_storms, L8.tribunal): the living gods' power ebbs while the Temple hides it. `house_politics` (L7.house_order, L7.legal_murder): Hlaalu court the Empire, Redoran hold to honour, Telvanni to power, Indoril to the Temple, Dres to slavery. `imperial_presence` (L5.east_empire_charter, L6.imperial_forts_and_ports): Legion forts, a figurehead king and a trade charter. `ghostfence_holds` (L1.red_mountain, L8.ancestor_worship, L9.tribunal_failing): the Temple's wall of ancestor-powered ghost-light round the mountain. `slavery_question` (L7.slavery, L9.house_politics): an abolitionist underground (the Twin Lamps) against the Dres and Telvanni. `argonian_grudge` (L5.slave_trade, L7.slavery): slaver raids into Black Marsh breed hatred that will one day return |
| L10 Hidden | `sixth_house` (L1.red_mountain, L2.blight_storms, L9.tribunal_failing): a house of sleepers serving Dagoth Ur under the mountain, spreading through dreams and corprus. `dwemer_citadels` (L1.dwemer_deeps): empty subterranean halls with working steam centurions. `daedric_ruins` (L1.volcanic_shields, L8.daedra_veneration): Chimer-era basalt shrines held by cultists and lesser Daedra. `ancestral_tombs` (L1.ash_mantle, L8.ancestor_worship): eighty-seven family tombs guarded by bonewalkers and ancestor ghosts. `smugglers` (L3.inner_sea_coasts, L5.ebony_mining, L9.imperial_presence): coast caves for contraband ebony, skooma and Dwemer artifacts. `blighted_mines` (L1.kwama_hills, L2.blight_storms, L9.ghostfence_holds): egg mines where the kwama queen sickens |
| L11 History | `chimer_era` (L1.velothi_wall, L8.daedra_veneration): the Chimer's exodus from Summerset under the prophet Veloth. `dwemer_war` (L1.dwemer_deeps, L10.dwemer_citadels): Chimer and Dwemer united against the Nords, then fought at Red Mountain, where the Dwemer vanished. `red_mountain_battle` (L1.red_mountain, L11.dwemer_war): Indoril Nerevar's death at the mountain. `tribunal_apotheosis` (L8.tribunal, L11.red_mountain_battle): Nerevar's counsellors became living gods. `velothi_strongholds` (L1.ash_mantle, L11.chimer_era): the propylon-linked Dunmer fortresses of the old wars. `armistice` (L9.imperial_presence, L11.tribunal_apotheosis): Morrowind joined the Empire by treaty, keeping its gods, Houses and slave laws. `pact_era` (L9.argonian_grudge, L11.chimer_era): in the Second Era Dunmer, Nords and Argonians briefly fought side by side in the Ebonheart Pact |

### 1.2 Ashlands (`vvardenfell_ashlands`)

**World truth.** The Ashlands are grey volcanic country north and west of Red Mountain, swept by choking storms that
bury roads and cairns in hours. Ald'ruhn, the Vvardenfell seat of House Redoran, is built inside and around Skar, the
hollowed shell of a giant ancient emperor crab, and its warrior-nobles hold the northern frontier by duty and austere
pride. Maar Gan's pilgrim shrine, Gnisis' rich egg mine, Khuul and Ald Velothi's fishing harbours, the Urshilaku
Ashlander nomads and Fort Buckmoth all scrape a living from the ash while Sixth House sleepers gather in ruined
Kogoruhn.

| Layer | Fields |
| --- | --- |
| L1 | `ash_plain`: grey volcanic ash flats north and west of the crater. `scoria_ridges`: jagged basalt and cinder ridges. `crab_shell_rock`: the colossal fossilized emperor-crab carapace (Skar) at Ald'ruhn. `velothi_strongholds`: ancient stepped stone fortresses (Falasmaryon, Valenvaryon, Andasreth, Berandas) |
| L2 | `ash_storm_track` (mw.L2.ash_storms, L1.ash_plain): howling ash winds scouring the northern tracks. `blight_season` (mw.L2.blight_storms, L1.scoria_ridges): red Blight gusts spilling over the Ghostfence |
| L3 | `deep_wells` (mw.L3.cisterns_and_wells, L1.ash_plain): covered wells sunk through dry ash. `ald_ruhn_cisterns` (mw.L3.cisterns_and_wells, L1.crab_shell_rock): stone water cisterns sheltered beneath the Skar shell |
| L4 | `ash_dune` (mw.L4.ash_waste, L2.ash_storm_track): drifting grey dunes. `scoria_scrub` (L1.scoria_ridges, L2.ash_storm_track): scathecraw, trama root and ash yams. `fumarole_flat` (mw.L2.fumarole_heat, L1.scoria_ridges): hot vents with fire fern. `kwama_hill` (mw.L1.kwama_hills, L1.ash_plain): warm clay hills housing kwama colonies, cliff racers, kagouti and alit |
| L5 | `egg_mines` (mw.L5.kwama_eggs, L4.kwama_hill): the great egg mine at Gnisis and smaller burrows. `redoran_arms` (mw.L5.house_trade, L4.kwama_hill): bonemold and chitin armour smithing. `shell_crafts` (L4.kwama_hill): chitin tools and ash-glass goggles |
| L6 | `aldruhn_under_the_skar` (L1.crab_shell_rock, L3.ald_ruhn_cisterns, L5.redoran_arms): Redoran manor halls inside the giant crab shell and mudbrick houses around it. `pilgrim_and_harbour_villages` (mw.L6.coastal_and_river_towns, L5.egg_mines): Maar Gan, Gnisis, Khuul and Ald Velothi. `urshilaku_camp` (mw.L6.ashlander_camps, L4.ash_dune): yurt camp of the Urshilaku tribe. `buckmoth_fort` (mw.L6.imperial_forts_and_ports, L1.ash_plain): Legion garrison south of Ald'ruhn |
| L7 | `redoran_duty` (mw.L7.house_order, L6.aldruhn_under_the_skar): martial honour, duels and Temple service. `ashlander_traders_at_the_gate` (mw.L7.ashlanders, L6.urshilaku_camp): guarded trade between Redoran patrols and Urshilaku scouts |
| L8 | `redoran_temple_piety` (mw.L8.tribunal, L7.redoran_duty): strict devotion to the Tribunal and the Maar Gan rock shrine. `urshilaku_prophecy` (mw.L8.ashlander_old_ways, L6.urshilaku_camp): Wise Woman visions of the Nerevarine and the burial caverns |
| L9 | `council_of_ald_ruhn` (mw.L9.house_politics, L6.aldruhn_under_the_skar, L7.redoran_duty): Redoran councillors divided between honourable tradition and corrupt nobles. `sleepers_in_the_north` (mw.L9.ghostfence_holds, L2.blight_season): Sixth House cultists infiltrating towns and mines |
| L10 | `kogoruhn` (mw.L10.sixth_house, L1.velothi_strongholds, L9.sleepers_in_the_north): the fallen House Dagoth stronghold bridging the Ashlands and Red Mountain. `missamsi` (mw.L10.sixth_house, L1.scoria_ridges, L9.sleepers_in_the_north): a blighted Sixth House cavern sanctum. `daedric_ruins` (mw.L10.daedric_ruins, L1.scoria_ridges, L9.council_of_ald_ruhn): Ashalmawia, Ebernanit and Yasammidan |
| L11 | `shell_founding` (L1.crab_shell_rock, L6.aldruhn_under_the_skar): House Redoran's raising of the ancient emperor-crab shell. `stronghold_wars` (mw.L11.velothi_strongholds, L1.velothi_strongholds): First Era wars documented in the Velothi keeps |

### 1.3 West Gash (`west_gash`)

**World truth.** The West Gash is a belt of rocky limestone ridges and green river valleys along Vvardenfell's western
flank, where the Odai River runs through Balmora, the bustling Hlaalu seat of yellow plaster terraces, guild halls and
merchant manors. Upriver, the Empire's chartered company town of Caldera digs rich ebony in stone towers built in
Cyrodilic style. House Hlaalu does business with the Empire and anyone else, while the Camonna Tong crime syndicate runs
the docks and hates the outlanders who own the mines. Above the valley, Dwemer Arkngthand and Foyada Mamaea scar the
hills.

| Layer | Fields |
| --- | --- |
| L1 | `gash_ridges`: weathered grey-brown rock ridges dividing the Ashlands from the coast. `odai_gorge`: the fertile canyon cut by the Odai River. `ebony_seam` (mw.L1.ebony_and_glass): rich volcanic ebony veins at Caldera. `foyada_mamaea` (mw.L1.red_mountain): a deep lava ravine running down from Red Mountain |
| L2 | `sheltered_west` (mw.L2.wet_south, L1.gash_ridges): ridge shelter blocks most ash storms and brings mild coastal rain |
| L3 | `odai_river` (mw.L3.odai_and_rivers, L1.odai_gorge, L2.sheltered_west): Vvardenfell's largest river flowing south to the sea. `balmora_banks` (L1.odai_gorge, L3.odai_river): stone-walled river embankments dividing Balmora's noble hill from its working district |
| L4 | `ridge_heath` (L1.gash_ridges, L2.sheltered_west): stoneflower, bittergreen and heather scrub. `odai_terrace` (L3.odai_river): grassy river benches with guar and nix-hounds. `mine_spoil` (L1.ebony_seam): dark slag heaps around the Caldera pits |
| L5 | `hlaalu_trade` (mw.L5.house_trade, L3.odai_river): silt-strider caravans, guild contracts and Hlaalu commerce. `ebony` (mw.L5.ebony_mining, L1.ebony_seam): Imperial-chartered ebony mining at Caldera. `guild_services` (L3.odai_river, L5.hlaalu_trade): Fighters, Mages and Thieves Guild chapters in Balmora |
| L6 | `balmora_on_the_odai` (mw.L6.house_seats, L3.balmora_banks, L5.hlaalu_trade): flat-roofed Hlaalu stone houses, bridges and council manor along the Odai. `caldera_company_town` (mw.L6.imperial_forts_and_ports, L5.ebony): timber-framed Imperial town and castle keep at the mines. `moonmoth_fort` (mw.L6.imperial_forts_and_ports, L1.foyada_mamaea): Legion fort guarding the road junction |
| L7 | `merchant_city` (mw.L7.house_order, L5.hlaalu_trade, L6.balmora_on_the_odai): Hlaalu factors, guild retainers and cosmopolitan traders. `dockside_syndicate` (mw.L7.criminal_syndicate, L6.balmora_on_the_odai): the Camonna Tong running the Council Club and smuggling rings. `outlander_miners` (mw.L7.slavery, L6.caldera_company_town): hired miners and slaves working Caldera's shafts |
| L8 | `pragmatic_piety` (mw.L8.tribunal, mw.L8.imperial_cult, L6.balmora_on_the_odai): Balmora's hilltop Temple and Moonmoth's Imperial shrine coexist for business |
| L9 | `hlaalu_collaboration` (mw.L9.house_politics, L6.balmora_on_the_odai, L7.merchant_city): House Hlaalu enriches itself through Imperial charters while rivals call them traitors. `mine_ownership_fight` (L5.ebony, L7.dockside_syndicate, L9.hlaalu_collaboration): corrupt Hlaalu councillors and the Camonna Tong scheming to sabotage Caldera |
| L10 | `arkngthand` (mw.L10.dwemer_citadels, L1.foyada_mamaea, L9.hlaalu_collaboration): the Dwemer citadel overlooking Foyada Mamaea. `smuggled_ebony` (mw.L10.smugglers, L1.ebony_seam, L9.mine_ownership_fight): illicit raw ebony caches in ridge caves. `sixth_house_hill_shrines` (mw.L10.sixth_house, L1.gash_ridges, L9.hlaalu_collaboration): hidden Dreamer caves at Sanit and Abinabi |
| L11 | `odai_settlement` (L3.odai_river, L6.balmora_on_the_odai): House Hlaalu's growth along the Odai valley. `charter_granted` (mw.L11.armistice, L6.caldera_company_town): the Imperial mining charter granted after the Armistice |

### 1.4 Bitter Coast (`bitter_coast`)

**World truth.** The Bitter Coast is a warm, rotting swamp shore along Vvardenfell's south-west, a maze of twisted bog
trees, brackish mudflats, glowing mushroom caps and thick sea fog. At its southern gate, Seyda Neen's Imperial Census
and Excise warehouse and wooden lighthouse greet prisoners and merchants arriving from Cyrodiil, while the stilted
shacks of Hla Oad and Gnaar Mok make more coin from smuggling than from netch or fish. Sea caves along the inlets hide
skooma, moon sugar and escaped slaves, and sunken Daedric ruins rot in the mangrove mire.

| Layer | Fields |
| --- | --- |
| L1 | `mud_coast`: low-lying coastal silt, peat and mudflats. `sea_caves` (mw.L1.inner_sea): tidal limestone and basalt caves hollowed by the Inner Sea |
| L2 | `fog_and_heat` (mw.L2.wet_south, L1.mud_coast): warm, humid rains and dense coastal sea fog |
| L3 | `brackish_inlets` (mw.L3.inner_sea_coasts, L1.mud_coast, L2.fog_and_heat): stagnant swamp sloughs and tidal creeks |
| L4 | `bitter_swamp` (mw.L4.bitter_swamp, L2.fog_and_heat, L3.brackish_inlets): twisted swamp trees, luminous russula, violet coprinus and marshmerrow. `kelp_shallows` (L3.brackish_inlets): shallow bays teeming with mudcrabs, slaughterfish and dreugh |
| L5 | `fishing` (L4.kelp_shallows): small-boat fishing and netch herding. `contraband` (mw.L5.ebony_mining, L1.sea_caves): moon-sugar, skooma and unchartered ebony smuggling |
| L6 | `customs_port` (mw.L6.imperial_forts_and_ports, L3.brackish_inlets): Seyda Neen's Imperial Census office, dock and lighthouse. `fishing_villages` (mw.L6.coastal_and_river_towns, L5.fishing): plank-walk shacks on stilts at Hla Oad and Gnaar Mok |
| L7 | `smugglers_and_tax_men` (mw.L7.criminal_syndicate, L5.contraband, L6.customs_port): Camonna Tong smugglers bribing or dodging Imperial excise clerks |
| L8 | `bitter_superstitions` (mw.L8.ancestor_worship, L2.fog_and_heat): fishermen's tales of drowned ghosts and swamp lights |
| L9 | `customs_corruption` (mw.L9.imperial_presence, L6.customs_port, L7.smugglers_and_tax_men): bribed excise guards looking the other way along the coast |
| L10 | `smuggler_caves` (mw.L10.smugglers, L1.sea_caves, L9.customs_corruption): Addamasartus and twenty other coastal contraband grottoes. `daedric_mire_ruins` (mw.L10.daedric_ruins, L4.bitter_swamp, L9.customs_corruption): half-sunken Daedric shrines at Ashurnibibi and Assarnatamat |
| L11 | `prisoner_landings` (mw.L11.armistice, L6.customs_port): Seyda Neen's history as the port of entry for Imperial ships and prisoners |

### 1.5 Ascadian Isles (`ascadian_isles`)

**World truth.** The Ascadian Isles are Vvardenfell's green, fertile southern garden: placid lakes, emperor-parasol
groves, bridged channels and the great water city of Vivec, where nine stepped cantons stand in the bay below the
floating Ministry of Truth and the palace of the living god. Around Lake Amaya and Lake Masobi lie walled Hlaalu and
Dres saltrice plantations worked by Argonian and Khajiit slaves, alongside Pelagiad's Imperial fort, Suran's hillside
pleasure houses and the pilgrim shrines of the Seven Graces. The wealth here is old, the cruelty is polite, and
Twin Lamps abolitionists move in secret.

| Layer | Fields |
| --- | --- |
| L1 | `fertile_lowland` (mw.L1.fungal_soils): rich volcanic-loam valleys and islands. `lake_basins`: sheltered freshwater basins and coastal lagoons |
| L2 | `mild_rain` (mw.L2.wet_south, L1.lake_basins): warm, gentle rains shielded from the worst ash storms |
| L3 | `ascadian_lakes` (mw.L3.ascadian_lakes, L1.lake_basins, L2.mild_rain): Lake Amaya, Lake Hairan and Lake Masobi. `paddy_irrigation` (L1.fertile_lowland, mw.L3.who_holds_water, L3.ascadian_lakes): ditched saltrice terraces controlled by the great manors |
| L4 | `saltrice_paddy` (L3.paddy_irrigation): cultivated saltrice, marshmerrow and heather. `parasol_grove` (mw.L4.mushroom_forest, L1.fertile_lowland): towering emperor-parasol mushrooms, netch, guar and scribs |
| L5 | `saltrice_plantations` (mw.L5.saltrice_plantations, L3.paddy_irrigation, L4.saltrice_paddy): the Dren, Arvel and Sterdecan slave estates. `pilgrim_economy` (mw.L5.house_trade, L3.ascadian_lakes): gondola ferries, offerings and hostelries serving Vivec's pilgrims |
| L6 | `vivec_cantons` (mw.L6.temple_cities, L1.lake_basins, L5.pilgrim_economy): the stepped pyramid cantons of Vivec City standing in the bay under the floating moonlet Baar Dau. `pelagiad_garrison` (mw.L6.imperial_forts_and_ports, L1.fertile_lowland): the stone Imperial fort and village of Pelagiad. `suran_on_the_road` (mw.L6.coastal_and_river_towns, L5.saltrice_plantations): Hlaalu trade town on the eastern shore |
| L7 | `plantation_slaves` (mw.L7.slavery, L5.saltrice_plantations): shackled Khajiit and Argonian field hands watched by Hlaalu and Camonna Tong overseers. `canton_ranks` (mw.L7.caste_ladder, L6.vivec_cantons): Ordinators and priests in the High Fane above the Foreign Quarter and sewer underworks |
| L8 | `vivec_the_living_god` (mw.L8.tribunal, L6.vivec_cantons): Warrior-Poet Vivec keeping vigil in his palace while Ordinators patrol the plazas. `seven_graces` (mw.L8.pilgrimage, L3.ascadian_lakes): the Stop the Moon shrine and the Fields of Kummu |
| L9 | `twin_lamps_underground` (mw.L9.slavery_question, L7.plantation_slaves): abolitionists smuggling freed slaves from the plantations to Ebonheart. `temple_secrets` (mw.L9.tribunal_failing, L6.vivec_cantons, L8.vivec_the_living_god): the Ordinators' Ministry of Truth prison silencing dissidents |
| L10 | `under_vivec` (L1.lake_basins, L6.vivec_cantons, L9.temple_secrets): flooded canton underworks housing cult shrines, rats and Morag Tong headquarters. `ald_sotha` (mw.L10.daedric_ruins, L1.fertile_lowland, L9.temple_secrets): the ruined Chimer seat of House Sotha dedicated to Mehrunes Dagon. `escaped_slave_caves` (L1.fertile_lowland, L7.plantation_slaves, L9.twin_lamps_underground): hidden caverns sheltering runaways |
| L11 | `vivec_founding` (mw.L11.tribunal_apotheosis, L6.vivec_cantons): the building of the cantons and the halting of the meteor Baar Dau. `fields_of_kummu` (L8.seven_graces, L11.vivec_founding): the lakeside shrine where Lord Vivec once worked as a humble field hand |

### 1.6 Azura's Coast (`azuras_coast`)

**World truth.** Azura's Coast is Vvardenfell's jagged eastern archipelago: hundreds of steep basalt islets, kelp-choked
channels and giant fungal towers facing the sunrise over the Padomaic sea. Tel Branora at the southern point, Tel Aruhn
with its underground slave market, and Tel Fyr—where the four-thousand-year-old wizard Divayth Fyr keeps his Corprusarium
for the incurably blighted—rise from the islands. Hidden behind a magical rock door on a southern island, the Dissident
Priests of Holamayan preserve the history the Tribunal suppresses, while Azura's great twilight statue watches from the
headland cliffs.

| Layer | Fields |
| --- | --- |
| L1 | `islet_maze` (mw.L1.inner_sea): hundreds of jagged basalt islands and sea stacks. `sea_cliffs`: sheer volcanic headlands facing the eastern sea |
| L2 | `sea_storms` (mw.L2.wet_south, L1.sea_cliffs): squalls and salt spray off the eastern water. `dawn_light` (L1.sea_cliffs): clear sunrise and twilight skies along the eastern headlands |
| L3 | `kelp_coves` (mw.L3.inner_sea_coasts, L1.islet_maze): deep tidal channels between the islets. `rain_cisterns` (mw.L3.cisterns_and_wells, L2.sea_storms): rainwater catchments in the island towers |
| L4 | `rocky_islet` (L1.islet_maze, L2.sea_storms): bare basalt stacks and comberry scrub. `kelp_cove` (L3.kelp_coves): giant kelp, mudcrabs and dreugh. `cliff_racer_rookery` (L1.sea_cliffs): nesting crags of cliff racers. `telvanni_spore_garden` (mw.L4.mushroom_forest, L3.rain_cisterns): magically grown fungal stalks |
| L5 | `telvanni_enchanting` (mw.L5.house_trade, L4.telvanni_spore_garden): spellmaking, alchemy and enchanting in the wizard towers. `fishing` (L4.kelp_cove): small skiffs working the coves. `slave_market_at_tel_aruhn` (mw.L5.slave_trade, L3.kelp_coves): Vvardenfell's largest open slave market beneath Tel Aruhn |
| L6 | `wizard_towers` (mw.L6.house_seats, L4.telvanni_spore_garden, L5.telvanni_enchanting): grown fungal towers at Tel Branora, Tel Aruhn and Tel Fyr |
| L7 | `wizard_and_servant` (mw.L7.caste_ladder, L5.slave_market_at_tel_aruhn, L6.wizard_towers): aloof Telvanni magister-lords served by retainers, mercenaries and slaves |
| L8 | `azura_shrine` (mw.L8.daedra_veneration, L1.sea_cliffs, L2.dawn_light): the monumental headland shrine of Azura of the Crimson Gate. `dreamer_monastery` (mw.L8.tribunal, L1.islet_maze): the secret Dissident Priests' monastery at Holamayan |
| L9 | `telvanni_rivalries` (mw.L9.house_politics, L6.wizard_towers, L7.wizard_and_servant): wizard-lords settling disputes by spell and assassination. `corprus_hospital` (mw.L9.tribunal_failing, L6.wizard_towers): Divayth Fyr's Corprusarium sheltering victims of the divine disease including the last living Dwarf |
| L10 | `daedric_headland_ruins` (mw.L10.daedric_ruins, L1.sea_cliffs, L9.telvanni_rivalries): Bal Fell, Kushtashpi and Onimushili among the islets. `sea_cliff_dwemer` (mw.L10.dwemer_citadels, L1.islet_maze, L9.corprus_hospital): Nchardahrk and Mudan Grotto beneath the waves |
| L11 | `azura_dawn_legends` (mw.L11.chimer_era, L8.azura_shrine): ancient Chimer myths of Azura's guidance and her prophecy after Red Mountain |

### 1.7 Molag Amur, Vvardenfell (`molag_amur`)

**World truth.** Molag Amur is a black wasteland of lava fields, cinder cones and boiling fumaroles south-east of Red
Mountain, the harshest country on Vvardenfell outside the Ghostfence. Molag Mar, a fortified adobe canton town shared by
the Temple and House Redoran, is the sole settlement; the rest belongs to the Erabenimsun, the fiercest and most
warlike of the four Ashlander tribes. Pilgrims cross its burning passes to reach the holy peaks of Mount Assarnibibi and
Mount Kand. Fire ferns glow beside the lava vents, kagouti stalk the ravines, and Daedra haunt the basalt shrines.

| Layer | Fields |
| --- | --- |
| L1 | `lava_field` (mw.L1.red_mountain): crusted black basalt and active lava channels. `cinder_cones`: steep volcanic cones and ash craters. `obsidian_flats` (mw.L1.ebony_and_glass): glassy volcanic talus slopes |
| L2 | `ash_and_heat` (mw.L2.ash_storms, mw.L2.fumarole_heat, L1.lava_field): scorching geothermal heat and choking ash gusts off the mountain |
| L3 | `no_rivers` (L1.lava_field, L2.ash_and_heat): bone-dry interior with no surface streams. `pilgrim_cisterns` (L1.cinder_cones, mw.L3.cisterns_and_wells, L3.no_rivers): covered stone cisterns at Molag Mar and along the southern coast |
| L4 | `fumarole_fern` (mw.L4.lava_field, L1.lava_field, L2.ash_and_heat): fire fern, trama root and scathecraw around hot vents. `sulphur_pool` (L1.cinder_cones, L2.ash_and_heat): yellow mineral vents prowled by kagouti, nix-hounds, cliff racers and feral scamps |
| L5 | `pilgrim_trade` (mw.L5.guar_and_netch, L3.pilgrim_cisterns): silt-strider and boat transport, guides and hostelries at Molag Mar. `obsidian_and_fire_fern` (L1.obsidian_flats, L4.fumarole_fern): alchemical harvesting of fire salts and fire petals |
| L6 | `molag_mar_cliff_town` (mw.L6.temple_cities, L3.pilgrim_cisterns, L5.pilgrim_trade): the fortified cantons of Molag Mar above the southern bay. `erabenimsun_camp` (mw.L6.ashlander_camps, L1.cinder_cones): the high ash-basin yurt camp of the Erabenimsun tribe |
| L7 | `warlike_nomads` (mw.L7.ashlanders, L6.erabenimsun_camp): militant Ashlander khan-chieftains and gulakhans who despise town Dunmer and outlanders |
| L8 | `holy_mountains` (mw.L8.pilgrimage, L1.cinder_cones, L6.molag_mar_cliff_town): the Temple pilgrimage shrines of Mount Assarnibibi (Vivec's birth) and Mount Kand |
| L9 | `erabenimsun_raids` (mw.L9.house_politics, L6.molag_mar_cliff_town, L7.warlike_nomads): Erabenimsun warbands ambushing pilgrims and Redoran patrols in the lava passes |
| L10 | `lava_daedra_shrines` (mw.L10.daedric_ruins, L1.lava_field, L9.erabenimsun_raids): Punabi, Sulipund, Marandus and Daedric ruins surrounded by lava rivers |
| L11 | `burned_land` (mw.L11.red_mountain_battle, L1.lava_field): "the Fire-Glitter," scarred by First Era eruptions and Lord Vivec's trials |

### 1.8 Grazelands (`grazelands`)

**World truth.** The Grazelands are a rolling green steppe across Vvardenfell's north-east, sheltered in the lee of the
northern hills to form the only open herding country on the island. Wild wickwheat and hackle-lo leaf carpet the plains,
where the Zainab and Ahemmusa Ashlander tribes drive their guar herds from spring to spring. Into this nomad pasture the
Telvanni have pushed the farming village of Vos and the hybrid stone-and-fungus fortress of Tel Vos, ruled by Master
Aryon, the youngest and most reform-minded Telvanni councillor. Beneath the grass, ten kwama mines are falling silent as
Blight creeps down from the mountain.

| Layer | Fields |
| --- | --- |
| L1 | `grass_slopes` (mw.L1.fungal_soils): rolling volcanic-loam downs. `kwama_hills` (mw.L1.kwama_hills): low clay hills honeycombed with kwama tunnels |
| L2 | `lee_of_the_storms` (mw.L2.ash_storms, L1.grass_slopes): northern hills deflect the worst ash, leaving clear skies and sea breezes |
| L3 | `seasonal_springs` (mw.L3.who_holds_water, L1.grass_slopes, L2.lee_of_the_storms): grassland waterholes known to the Ashlander herders |
| L4 | `wickwheat_steppe` (mw.L4.grazeland_steppe, L1.grass_slopes, L2.lee_of_the_storms): wild wickwheat, hackle-lo leaf and golden kanet. `guar_commons` (L3.seasonal_springs, L4.wickwheat_steppe): open grazing range of domestic and wild guar, alit and kagouti |
| L5 | `herding` (mw.L5.guar_and_netch, L4.guar_commons): Ashlander guar hides, meat and trade goods. `vos_farms` (L4.wickwheat_steppe): Telvanni wickwheat and hackle-lo farms around Vos. `egg_mines` (mw.L5.kwama_eggs, L1.kwama_hills): ten grassland egg mines |
| L6 | `vos_and_tel_vos` (mw.L6.house_seats, L5.vos_farms): the Velothi farming village of Vos and Master Aryon's Imperial-fort-and-mushroom tower at Tel Vos. `zainab_and_ahemmusa_camps` (mw.L6.ashlander_camps, L3.seasonal_springs, L5.herding): the trading Zainab camp and coastal Ahemmusa camp |
| L7 | `nomads_vs_farmers` (mw.L7.ashlanders, L6.vos_and_tel_vos, L6.zainab_and_ahemmusa_camps): uneasy truce between Zainab traders, Ahemmusa herders and Telvanni settlers |
| L8 | `ashlander_herd_rites` (mw.L8.ashlander_old_ways, L6.zainab_and_ahemmusa_camps): Wise Women blessing the herds and seeking safe haven at Ald Daedroth |
| L9 | `grass_rights` (mw.L9.house_politics, L6.vos_and_tel_vos, L7.nomads_vs_farmers): Master Aryon negotiating trade with the Zainab while older Telvanni ignore nomad custom |
| L10 | `blighted_egg_mines` (mw.L10.blighted_mines, L1.kwama_hills, L9.grass_rights): grass-country mines infected by the Blight. `dwemer_and_sixth_house` (mw.L10.dwemer_citadels, L1.grass_slopes, L9.grass_rights): Dwemer Nchuleft and Sixth House bases at Ainab and Sharapli |
| L11 | `old_herd_paths` (mw.L11.chimer_era, L4.guar_commons): ancestral Ashlander migration tracks predating the Great House charters |

### 1.9 Sheogorad (`sheogorad`)

**World truth.** Sheogorad is a cold, storm-battered archipelago of jagged rock islands and treacherous reefs off
Vvardenfell's northern coast, named for the Mad God. Its sole town is Dagon Fel, a bleak fishing and salvage harbour
settled by hardy Nords who trade across the Sea of Ghosts with Skyrim and strip the shipwrecks that litter every shoal.
Across the rocky channels stand solitary Velothi wizard towers, the remote Sanctus Shrine where Temple pilgrims keep a
vow of silence, and the sprawling island fortress-shrine of Ald Daedroth, where Sheogorath's cultists and Ordinators war
amid the surf.

| Layer | Fields |
| --- | --- |
| L1 | `wave_cut_islets` (mw.L1.inner_sea): craggy grey basalt islands and arches. `reefs`: jagged submerged rock shoals along the Sea of Ghosts |
| L2 | `cold_sea_storms` (mw.L2.cold_north, L1.wave_cut_islets): freezing gales, sleet and heavy surf from the northern sea |
| L3 | `rain_barrels_and_springs` (mw.L3.cisterns_and_wells, L2.cold_sea_storms): small island rock springs and rainwater casks |
| L4 | `islet_heath` (L1.wave_cut_islets, L2.cold_sea_storms): stunted heather, kreshweed and gulls. `kelp_bed` (L1.reefs): cold kelp forests with dreugh, slaughterfish and horkers |
| L5 | `salvage` (L1.reefs, L4.kelp_bed): stripping cargo from ships wrecked on the shoals. `nord_trade` (mw.L5.east_empire_charter, L4.kelp_bed): fishing and northern sea trade out of Dagon Fel |
| L6 | `dagon_fel_harbour` (mw.L6.coastal_and_river_towns, L3.rain_barrels_and_springs, L5.nord_trade): Nord timber-and-stone houses and tavern ("The End of the World") around a sheltered bay |
| L7 | `nord_dunmer_mix` (mw.L7.outlander_scorn, L6.dagon_fel_harbour): rough-spoken Nord fishermen, eccentric hermits and outcast Telvanni mages |
| L8 | `sanctus_shrine` (mw.L8.pilgrimage, L1.wave_cut_islets): the remote Shrine of the Vow of Silence. `mad_god_shrine` (mw.L8.daedra_veneration, L1.wave_cut_islets): the great island temple of Sheogorath at Ald Daedroth |
| L9 | `wreckers_suspected` (mw.L9.imperial_presence, L5.salvage, L6.dagon_fel_harbour): whispered accusations that islanders lure merchant ships onto the reefs |
| L10 | `shipwrecks` (mw.L10.smugglers, L1.reefs, L9.wreckers_suspected): dozens of sunken hulls and drowned vaults. `daedric_island_fortress` (mw.L10.daedric_ruins, L1.wave_cut_islets, L8.mad_god_shrine, L9.wreckers_suspected): Ald Daedroth and the Velothi towers of Mawia and Vas |
| L11 | `nord_landings` (mw.L11.dwemer_war, L6.dagon_fel_harbour): centuries of Nordic raids and settlements along Morrowind's northern islands |

### 1.10 Red Mountain (`red_mountain`)

**World truth.** Red Mountain is Vvardenfell's smoking volcanic heart, encircled by the Tribunal's Ghostfence—a
shimmering blue wall of ancestor-bone wards raised to quarantine the Blight and the awakened Sixth House. Inside the
fence, red ash storms howl without cease over black cinder slopes, foyada lava chutes and Dwemer citadels held by Dagoth
Ur and his ash-vampire brothers. Nothing grows here but blight-twisted scrub, corprus beasts and eyeless ash creatures.
Ghostgate, the twin-towered fortress spanning the only pass through the fence, is the sole lawful outpost, garrisoned by
Ordinators and Buoyant Armigers whose wards grow weaker every month.

| Layer | Fields |
| --- | --- |
| L1 | `caldera` (mw.L1.red_mountain): the smoking summit crater and lava lake of Dagoth Ur. `cinder_slopes`: steep black ash and basalt talus slopes. `foyadas` (mw.L1.red_mountain): ancient lava channels (Esannudan, Ashur-Dan, Ilibaal) cutting down the mountain |
| L2 | `blight_storms` (mw.L2.blight_storms, L1.caldera): perpetual red ash gales carrying the Corprus Blight. `plume_shadow` (L1.caldera, L2.blight_storms): dark volcanic smoke blotting out the sun |
| L3 | `none_inside` (L1.caldera, L2.blight_storms): poisoned sulfur vents inside the fence; only Ghostgate's sealed cisterns hold drinkable water |
| L4 | `cinder_waste` (mw.L4.lava_field, L1.cinder_slopes, L2.blight_storms): scorched ash, corprus weepings and blight-warped scrub. `ghostfence_zone` (L1.foyadas, L4.cinder_waste): ash slaves, ash ghouls, ascended sleepers, corprus stalkers and blighted cliff racers |
| L5 | `no_economy` (L3.none_inside, L4.cinder_waste): zero civilian trade; Ghostgate is supplied entirely by Temple caravans from Ald'ruhn and Vivec |
| L6 | `ghostgate` (mw.L6.temple_cities, L1.foyadas, L5.no_economy): the twin-towered Temple fortress (Tower of Dawn and Tower of Dusk) guarding the pass through the Ghostfence |
| L7 | `garrison_life` (mw.L7.house_order, L6.ghostgate): Buoyant Armigers, Ordinators and Redoran volunteers serving grim tours on the wall |
| L8 | `fence_of_the_dead` (mw.L8.ancestor_worship, mw.L8.tribunal, L6.ghostgate): the Ghostfence pylons powered by the consecrated bones of Dunmer ancestors |
| L9 | `fence_weakening` (mw.L9.tribunal_failing, mw.L9.ghostfence_holds, L7.garrison_life, L8.fence_of_the_dead): the Tribunal's annual pilgrimage to the Heart of Lorkhan has failed, and the Ghostfence is faltering |
| L10 | `sixth_house_citadels` (mw.L10.sixth_house, mw.L10.dwemer_citadels, L1.caldera, L9.fence_weakening): the Dwemer fortresses of Dagoth Ur, Vemynal, Odrosal, Tureynulal and Endusal held by the Ash Vampires |
| L11 | `red_mountain_battle` (mw.L11.red_mountain_battle, L1.caldera): the First Era battle where the Dwemer vanished and Nerevar fell. `ghostfence_raised` (mw.L11.tribunal_apotheosis, L8.fence_of_the_dead): the raising of the Ghostfence after Dagoth Ur retook the crater in 3E 417 |

### 1.11 Zafirbel Bay (`zafirbel_bay`)

**World truth.** Zafirbel Bay is the island-studded eastern bay of Vvardenfell, the insular heartland of House Telvanni
on the island. Its seat, Sadrith Mora, is a forest of giant grown emperor-mushroom towers linked by spiraling root
ramps and vertical levitation shafts where the Telvanni Council meets. Outsiders are confined to the Gateway Inn or to
Wolverine Hall, an Imperial Legion fortress bolted directly onto the side of the bay to house the Empire's guilds and
chapel. Across the surrounding coral reefs and islets rise rival wizard spires like Tel Naga and steaming Dwemer sea
pipes, in a bay where might is law.

| Layer | Fields |
| --- | --- |
| L1 | `bay_islands` (mw.L1.inner_sea): sheltered volcanic islands enclosing Zafirbel Bay. `dwemer_reef_vents` (mw.L1.dwemer_deeps): submerged geothermal vents and ancient brass pipes |
| L2 | `mild_bay` (mw.L2.wet_south, L1.bay_islands): warm, humid bay air sheltered from Red Mountain's ash plume |
| L3 | `rain_and_spores` (mw.L3.cisterns_and_wells, L2.mild_bay): fungal root catchments filtering rainwater for the towers |
| L4 | `telvanni_mushroom_forest` (mw.L4.mushroom_forest, L1.bay_islands, L2.mild_bay): towering emperor-parasol canopies and spore glades. `coral_reef` (L1.bay_islands): shallow bay waters with dreugh and slaughterfish |
| L5 | `enchanting_and_spells` (mw.L5.house_trade, L4.telvanni_mushroom_forest): Telvanni alchemy, soul gems and enchanted gear. `slave_labour` (mw.L5.slave_trade, L4.telvanni_mushroom_forest): slave markets and fungal-tower servants |
| L6 | `sadrith_mora_towers` (mw.L6.house_seats, L4.telvanni_mushroom_forest, L5.enchanting_and_spells): Tel Naga, the Telvanni Council House and fungal shops of Sadrith Mora. `wolverine_hall` (mw.L6.imperial_forts_and_ports, L1.bay_islands): the towering stone Imperial fort and guild complex beside the town |
| L7 | `power_is_rank` (mw.L7.house_order, L6.sadrith_mora_towers): Telvanni law where winning an argument by assassination or sorcery makes it legal |
| L8 | `indifferent_piety` (mw.L8.tribunal, mw.L8.imperial_cult, L7.power_is_rank): Telvanni wizards ignore both the Temple and Wolverine Hall's Imperial cult |
| L9 | `council_of_masters` (mw.L9.house_politics, L6.sadrith_mora_towers, L7.power_is_rank): Mouths speaking for reclusive Magister-Masters in the Council House. `imperial_enclave_tension` (mw.L9.imperial_presence, L6.wolverine_hall, L9.council_of_masters): cold friction between the Legion garrison and Master Neloth |
| L10 | `wizard_tower_dungeons` (L4.telvanni_mushroom_forest, L6.sadrith_mora_towers, L9.council_of_masters): root-cellar prisons, Daedra summoning vaults and corprus pens under the towers. `rkuldumz_spire` (mw.L10.dwemer_citadels, L1.dwemer_reef_vents, L9.council_of_masters): flooded Dwemer ruins on the bay floor |
| L11 | `telvanni_exile_to_the_east` (mw.L11.armistice, L6.sadrith_mora_towers): House Telvanni's withdrawal to the eastern islands to avoid Imperial oversight |

### 1.12 Telvanni Peninsula (`telvannis`)

**World truth.** The Telvanni Peninsula is the mainland's wild, cliff-walled north-eastern arm, a realm of giant
coastal mushroom forests, black sand beaches and archmagisters who answer neither to the Grand Council nor to the
Empire. At its northern tip stands Port Telvannis, the ancient mainland seat of House Telvanni and a harbour of immense
fungal spires; to the west rises Firewatch, an Imperial-chartered walled beacon city keeping a lighthouse flame over the
stormy cape. Between them lie mist-wrapped spore glades, rogue wizard experiments and deep Dwemer steam-works, while
Telvanni galleys ship slaves and enchanted wares down the coast.

| Layer | Fields |
| --- | --- |
| L1 | `peninsula_cliffs`: sheer grey basalt sea cliffs along the Padomaic Ocean. `fungal_forest_soils` (mw.L1.fungal_soils): deep ash-loam basins supporting giant mainland fungi |
| L2 | `sea_rain` (mw.L2.cold_north, L1.peninsula_cliffs): cool ocean rains and mist. `ash_shadow` (mw.L2.ash_storms, L1.fungal_forest_soils): light ash drift over the southern neck |
| L3 | `cliff_springs` (mw.L3.inner_sea_coasts, L1.peninsula_cliffs, L2.sea_rain): waterfall streams plunging off the coastal bluffs |
| L4 | `coastal_mushroom_forest` (mw.L4.mushroom_forest, L1.fungal_forest_soils, L2.sea_rain): giant mainland fungal canopies, spore glades and netch herds. `black_sand_beach` (L1.peninsula_cliffs, L3.cliff_springs): volcanic shingle coves and cliff heaths |
| L5 | `sea_trade` (mw.L5.house_trade, L4.coastal_mushroom_forest): maritime export of potions, telvanni bug musk and enchantments. `slave_shipping` (mw.L5.slave_trade, L4.black_sand_beach): slave galleys supplying the mainland towers |
| L6 | `port_telvannis` (mw.L6.house_seats, L4.coastal_mushroom_forest, L5.sea_trade): the colossal fungal capital of mainland House Telvanni. `firewatch_beacon_city` (mw.L6.imperial_forts_and_ports, L1.peninsula_cliffs, L5.sea_trade): the walled Imperial port and lighthouse city on the north-west cape |
| L7 | `lord_and_thrall` (mw.L7.caste_ladder, mw.L7.slavery, L6.port_telvannis): unchecked magister rule over indentured retainers, mercenaries and slaves |
| L8 | `mephala_shrines` (mw.L8.daedra_veneration, L7.lord_and_thrall): quiet household and cave shrines to Mephala and Boethiah alongside nominal Temple chapels |
| L9 | `wizard_feuds` (mw.L9.house_politics, L6.port_telvannis, L6.firewatch_beacon_city, L7.lord_and_thrall): open magical warfare between mainland archmagisters and containment of the Imperial charter at Firewatch |
| L10 | `towers_gone_wrong` (L4.coastal_mushroom_forest, L6.port_telvannis, L9.wizard_feuds): fungal towers abandoned to rogue Daedra or failed experiments. `dwemer_workings` (mw.L10.dwemer_citadels, L1.peninsula_cliffs, L9.wizard_feuds): the Dwemer halls of Arkzak and Bthungdahrk |
| L11 | `old_telvanni_seat` (mw.L11.chimer_era, L6.port_telvannis): Port Telvannis's unbroken rule since before Vvardenfell was opened to settlement |

### 1.13 Stonefalls (`stonefalls`)

**World truth.** Stonefalls is the mainland's volcanic western march, stretching from the snow-dusted Velothi foothills
at Blacklight down to Old Ebonheart on the Inner Sea and Kragenmoor by the Valus passes into Cyrodiil. Ebonheart is the
hinge between Empire and province: a fortress-city holding the Imperial garrison, the East Empire Company's main hall
and the Grand Council chamber where the five Great Houses meet. Smoking lava shields, basalt columns and the twin peaks
of the Brothers of Strife dominate the interior, while Redoran border keeps watch Skyrim, Hlaalu ships crowd Davon's
Watch, and Dhalmora remembers the Second Era Pact.

| Layer | Fields |
| --- | --- |
| L1 | `lava_shields` (mw.L1.volcanic_shields): smoking mainland lava plains and basalt columns. `twin_peaks` (mw.L1.volcanic_shields): the twin volcanic cones of the Brothers of Strife and Ash Mountain. `velothi_foothills` (mw.L1.velothi_wall): steep western mountain passes bordering Skyrim |
| L2 | `ash_falls` (mw.L2.ash_storms, L1.lava_shields): warm ash haze and sulfuric steam across the central basin. `snow_on_the_foothills` (mw.L2.cold_north, L1.velothi_foothills): cold mountain winds and snow in the high Velothi passes |
| L3 | `inner_sea_shore` (mw.L3.inner_sea_coasts, L1.lava_shields): deep-water harbours facing Vvardenfell. `lava_tube_springs` (L1.lava_shields, L2.snow_on_the_foothills): warm mineral streams fed by mountain snowmelt |
| L4 | `basalt_field` (mw.L4.lava_field, L1.lava_shields, L2.ash_falls): ash scrub, fire fern and giant mushrooms amid lava flows. `foothill_pine` (L1.velothi_foothills, L2.snow_on_the_foothills): hardy pines along the Skyrim border crags |
| L5 | `ports` (mw.L5.house_trade, L3.inner_sea_shore): maritime shipping through Davon's Watch and Ebonheart. `east_empire_hall` (mw.L5.east_empire_charter, L3.inner_sea_shore): the East Empire Company's mainland warehouse and customs hub. `obsidian_and_basalt` (mw.L5.ebony_mining, L1.lava_shields): quarrying of volcanic stone and ebony |
| L6 | `ebonheart_council_city` (mw.L6.imperial_forts_and_ports, L3.inner_sea_shore, L5.east_empire_hall): the Imperial fortress-city and Grand Council seat at Ebonheart. `blacklight_border_city` (mw.L6.house_seats, L1.velothi_foothills): the great Redoran mainland capital guarding the northern pass. `kragenmoor_on_the_valus_road` (mw.L6.house_seats, L5.ports): Hlaalu walled city near the Cyrodiil frontier |
| L7 | `imperial_district_vs_dunmer_quarter` (mw.L7.outlander_scorn, L6.ebonheart_council_city): Imperial legates and factors rubbing shoulders with proud House delegates. `nord_settlers` (L4.foothill_pine, L6.blacklight_border_city): frontier Nord homesteaders at Hrogar's Hold |
| L8 | `imperial_chapels` (mw.L8.imperial_cult, mw.L8.tribunal, L6.ebonheart_council_city): Nine Divines chapels in Ebonheart standing across the plaza from Tribunal temples |
| L9 | `grand_council` (mw.L9.house_politics, mw.L9.imperial_presence, L6.ebonheart_council_city, L7.imperial_district_vs_dunmer_quarter): delegates of all five Great Houses debating tariffs, charters and border defence. `border_watch` (L6.blacklight_border_city, L9.grand_council): Redoran and Indoril garrisons watching the Skyrim and Cyrodiil passes |
| L10 | `inner_sea_armature` (mw.L10.dwemer_citadels, L1.lava_shields, L9.grand_council): Dwemer ruins along the Inner Sea coast. `pact_era_forts` (mw.L10.daedric_ruins, L1.twin_peaks, L9.border_watch): sealed Second Era fortresses at Fort Virak, Fort Arand and Heimlyn Keep |
| L11 | `pact_era` (mw.L11.pact_era, L6.ebonheart_council_city): the forging of the Ebonheart Pact against the Akaviri invasion. `bal_foyen_treaty` (L3.inner_sea_shore, L11.pact_era): the brief liberation and settlement of former Argonian slaves at Dhalmora |

### 1.14 Deshaan (`deshaan`)

**World truth.** Deshaan is the rich southern heartland of mainland Morrowind: wide bioluminescent fungal plains,
obsidian gorges and misty lagoons surrounding Mournhold, the white-and-gold sacred capital of the goddess Almalexia, the
nominal Imperial King of Morrowind, and House Indoril. Beneath Mournhold's concentric plazas lie the buried ruins of Old
Mournhold and the Dwemer weather-foundry of Bamz-Amschend. To the east, Tear sits in the feverish border marshes as the
capital of House Dres and the province's largest slave market; to the west, Narsis anchors Hlaalu trade on the river,
making Deshaan wealthy and uneasy.

| Layer | Fields |
| --- | --- |
| L1 | `fungal_plains` (mw.L1.fungal_soils): broad fertile mainland basins of rich ash-loam. `obsidian_gorge` (mw.L1.volcanic_shields): deep volcanic-glass canyons cutting the central hills |
| L2 | `mild_inland` (mw.L2.wet_south, L1.fungal_plains): warm, humid inland climate with summer rains on the eastern marsh edge |
| L3 | `lagoon_fens` (mw.L3.ascadian_lakes, L1.fungal_plains, L2.mild_inland): the Silent Mire and southern river flats toward Black Marsh. `mournhold_aqueducts` (L1.fungal_plains, mw.L3.who_holds_water, L3.lagoon_fens): Temple-controlled canals and fountains supplying the capital |
| L4 | `glowing_fungal_plain` (mw.L4.mushroom_forest, L1.fungal_plains, L2.mild_inland): towering bioluminescent mushrooms, kagouti and netch herds. `paddy_round_tear` (mw.L4.bitter_swamp, L3.lagoon_fens): flooded saltrice marshes and reed fens around Tear |
| L5 | `temple_economy` (mw.L5.house_trade, L3.mournhold_aqueducts): tithes, crafts and court commerce in Mournhold. `dres_plantations` (mw.L5.saltrice_plantations, L4.paddy_round_tear): sprawling slave-worked saltrice and muck estates. `slave_market` (L4.paddy_round_tear, mw.L5.slave_trade, L5.dres_plantations): Tear's great slave pens fed by raids into Black Marsh. `hlaalu_narsis_trade` (mw.L5.house_trade, L1.fungal_plains): river and road caravans through Narsis |
| L6 | `mournhold_capital` (mw.L6.temple_cities, mw.L6.house_seats, L3.mournhold_aqueducts, L5.temple_economy): the ringed white-and-gold city of Mournhold (Almalexia), seat of the Temple, House Indoril and the Royal Palace. `tear_plantation_city` (mw.L6.house_seats, L5.dres_plantations, L5.slave_market): the canal-cut Dres seat of Tear. `narsis` (mw.L6.house_seats, L5.hlaalu_narsis_trade): the mainland capital of House Hlaalu |
| L7 | `priest_nobility` (mw.L7.house_order, mw.L7.caste_ladder, L6.mournhold_capital): orthodox Indoril nobles and Ordinators guarding the capital's strict etiquette. `slaves_of_tear` (mw.L7.slavery, L6.tear_plantation_city): thousands of Argonian captives toiling under Dres whip-masters |
| L8 | `almalexia_mother` (mw.L8.tribunal, L6.mournhold_capital): devotion to Mother Morrowind and her elite Hands of Almalexia in the High Chapel. `quarantine_rites` (mw.L8.pilgrimage, L3.lagoon_fens): Temple healers tending plague history at the Quarantine Serk |
| L9 | `indoril_vs_dres` (mw.L9.house_politics, mw.L9.slavery_question, L6.mournhold_capital, L6.tear_plantation_city): rivalry between King Hlaalu Helseth's Royal Court, orthodox Indoril canons and Dres slave-lords. `goddess_unseen` (mw.L9.tribunal_failing, L8.almalexia_mother): Almalexia's growing isolation and paranoia as the Heart's power wanes |
| L10 | `old_mournhold` (mw.L10.dwemer_citadels, L1.fungal_plains, L6.mournhold_capital, L9.goddess_unseen): the buried ruins of First Mournhold and Dwemer Bamz-Amschend beneath the plazas. `runaway_caves` (L1.obsidian_gorge, L7.slaves_of_tear, L9.indoril_vs_dres): marsh and gorge caverns where escaped Argonians and Twin Lamps agents hide |
| L11 | `mournhold_rebuilt` (mw.L11.armistice, L6.mournhold_capital, L10.old_mournhold): the sacking of First Mournhold by Mehrunes Dagon at the end of the First Era and its rebuilding above the ruins |

### 1.15 Necrom Coast (`necrom`)

**World truth.** The Necrom Coast is mainland Morrowind's solemn south-eastern seaboard, a pale landscape of limestone
tomb-terraces, basalt sea spires and lantern-lit processional roads given wholly to the dead. At its centre stands
Necrom, the white-walled City of the Dead, where Dunmer families of every Great House bring their ancestors' ashes and
bones by barge and bier to be entombed by the Keepers of the Dead. Funeral harbours at Zaappi and Elamasipal live off
mourners and pilgrims, while deep beneath the catacombs, bone-thieving necromancers and seekers of Hermaeus Mora's
forbidden libraries pry into vaults meant never to open.

| Layer | Fields |
| --- | --- |
| L1 | `tomb_hills`: pale coastal limestone bluffs honeycombed with burial vaults. `spire_rocks` (mw.L1.volcanic_shields): tall basalt sea stacks and crags along the eastern shore |
| L2 | `grey_sea_mist` (mw.L2.wet_south, L1.spire_rocks): cool salt fog rolling in off the Padomaic Ocean |
| L3 | `harbour_springs` (mw.L3.inner_sea_coasts, L1.tomb_hills, L2.grey_sea_mist): coastal wells and sheltered coves. `funeral_wells` (mw.L3.cisterns_and_wells, L1.tomb_hills): ritual washing basins along the pilgrim avenues |
| L4 | `tomb_terraces` (L1.tomb_hills, L2.grey_sea_mist): manicured grave terraces, ash-salt shores and white lanterns. `glowing_fungus_grove` (mw.L4.mushroom_forest, L1.tomb_hills, L3.harbour_springs): pale bioluminescent fungi and cliff heaths |
| L5 | `funeral_trade` (mw.L5.house_trade, L3.harbour_springs): embalmers, urn-makers, funeral barges and bonemeal consecration. `pilgrim_hostels` (L3.funeral_wells, L5.funeral_trade): quiet inns and scriptoria serving mourners from across Morrowind |
| L6 | `necrom` (mw.L6.temple_cities, L1.tomb_hills, L5.funeral_trade): the white-walled necropolis city of Necrom with its towering catacomb tiers. `funeral_harbours` (mw.L6.coastal_and_river_towns, L3.harbour_springs, L5.funeral_trade): the coastal ports of Zaappi and Elamasipal |
| L7 | `tomb_keepers` (mw.L7.house_order, L6.necrom): the solemn order of Keepers of the Dead and Ordinator guards who enforce the truce of the necropolis. `mourners` (L5.pilgrim_hostels, L6.necrom): noble corteges and common pilgrims carrying ancestral remains |
| L8 | `city_of_the_dead` (mw.L8.ancestor_worship, mw.L8.tribunal, L6.necrom, L7.tomb_keepers): the sacred law that all Dunmer dead find peace and counsel their kin within Necrom's walls. `forbidden_lore` (mw.L8.daedra_veneration, L1.spire_rocks): whispered cults of Hermaeus Mora drawn to the city's ancient memories |
| L9 | `burial_rights` (mw.L9.house_politics, L7.tomb_keepers, L8.city_of_the_dead): Great House competition for prestigious vault tiers and the Temple's strict ban on necromancy |
| L10 | `deep_catacombs` (mw.L10.ancestral_tombs, L1.tomb_hills, L8.city_of_the_dead, L9.burial_rights): miles of lower ossuaries guarded by bonewalkers and ancient ghosts. `necromancer_thieves` (L1.spire_rocks, L8.forbidden_lore, L9.burial_rights): bone-robbers and Daedric cultists in coastal caves like Uhaammu |
| L11 | `oldest_burials` (mw.L11.chimer_era, L6.necrom, L10.deep_catacombs): Necrom's foundation in the First Era around the giant bones of the primordial mythic past |

---

## 2. Micro-biomes (MS21)

Drivers: heat raster (vents, lava, foyadas), ash fall (downwind of the mountain), distance to sea, elevation, fungal
soil. Floor: >= 6 per region, >= 2/km² (small regions: >= 6).

| Region | Micro-biomes (cause) | Count / floor |
| --- | --- | --- |
| Ashlands | ash dune, scoria scrub, fumarole flat, kwama hill, storm hollow, stronghold ruin-field, Skar shell-yard | 7 / 6 |
| West Gash | ridge heath, Odai terrace, gorge fern, mine spoil, foyada scar, parasol hollow | 6 / 6 |
| Bitter Coast | bitter swamp, mudflat, smuggler cove, kelp shallows, marshmerrow bed, fog lagoon | 6 / 6 |
| Ascadian Isles | saltrice paddy, heather lake shore, parasol grove, lagoon reed, plantation ring, canton waterfront | 6 / 6 |
| Azura's Coast | rocky islet, kelp cove, comberry heath, racer rookery, spore garden, dawn headland | 6 / 6 |
| Molag Amur (VV) | lava field, fumarole fern, obsidian flat, scoria cone, sulphur pool, pilgrim cairn path | 6 / 6 |
| Grazelands | wickwheat steppe, hackle-lo meadow, guar commons, ash fringe, Vos mushroom farm, nomad spring | 6 / 6 |
| Sheogorad | islet heath, wreck reef, kelp bed, gull cliff, driftwood strand, sea-ice shelf (winter) | 6 / 6 |
| Red Mountain | caldera rim, cinder waste, blight scar, foyada lava, Ghostfence zone, steam vent | 6 / 6 |
| Zafirbel Bay | mushroom forest, spore glade, tidal flat, coral reef, Dwemer vent, tower garden | 6 / 6 |
| Telvanni Peninsula | coastal mushroom forest, spore glade, black-sand beach, cliff heath, vent field, slave-farm terrace, beacon headland | 7 / 12 |
| Stonefalls | basalt field, ash scrub, lava tube, Inner Sea strand, foothill pine, Nord farmstead, twin-peak scree | 7 / 10 |
| Deshaan | glowing fungal plain, lagoon fen, lava canyon, paddy round Tear, Mournhold gardens, snake vale | 6 / 11 |
| Necrom Coast | tomb terraces, pale salt shore, glowing fungus grove, spire heath, cliff coast, funeral harbour | 6 / 10 |

Signature rule for Morrowind: **no conifer and no broadleaf canopy anywhere except Stonefalls' Velothi foot**; every
tree-height plant is a fungus, a twisted swamp tree or a parasol. This is the strongest single anti-Skyrim rule.

---

## 3. Map changes and geography

1. **Vvardenfell uniform scale and coast** (SCALE-AND-GEOGRAPHY 5.3): similarity at 0.95 blocks per game metre;
   moves Vivec (7140, 1590), Sadrith Mora (8700, -130), Ald'ruhn (6500, -370), Balmora (6390, 550), Molag Mar (8200,
   1190), Tel Branora (8430, 1730), the west coast towns 210-580 blocks east, Dagon Fel (7480, -2130). Replace the
   17-vertex island polygon with the extracted coastline (2,128 points, 206 islands) simplified to 48 blocks: the Bitter
   Coast's inlets, Sheogorad's isles, Azura's Coast archipelago, the Odai mouth. Fix the `sea_of_ghosts` label south of
   the island to `inner_sea`.
2. **Repaint Vvardenfell's regions from the game's own region grid** (`morrowind_ref/regions.json`,
   `regions_grid.png`) through the same similarity. Today's places are scrambled by the old warp (Seyda Neen in the
   Ascadian Isles, Urshilaku in Sheogorad, Massahanud in the Ascadian Isles, Ald Daedroth in the Grazelands).
3. **Owner decision M2: two region fixes.** (a) Free the name Molag Amur for Vvardenfell: carve the lava fields
   between the mountain and Molag Mar into `morrowind/molag_amur`, and give the mainland region round Necrom the new id
   `morrowind/necrom`. (b) Split `telvannis` into `morrowind/zafirbel_bay` (Sadrith Mora and the bay islands) and
   `telvannis` (the mainland peninsula). Both change `regions.png` indices and save data; do them in the same repaint
   as step 2. Low-churn alternative: keep ids, rename displays ("Necrom Coast"), and treat Molag Amur and Zafirbel as
   sheet sub-areas only. Recommended: the real split (identity G1 depends on it).
4. **Relief**: Morrowind p50 21 to 30, p90 150 to 170, ruggedness 4 to 5, with the TES3 vertical curve (about 1.1
   blocks per game metre low, 1.55 at the peak). Amplify Red Mountain's caldera and the foyadas radiating from it as
   lava channels 8-20 blocks deep, Molag Amur's broken lava fields, the West Gash's ridges and the Odai gorge, the
   Grazelands' long open slopes. **Replace Morrowind's blocky, low-relief terrain**: no flat ash plains; ash dunes,
   scoria ridges and cones.
5. **Mainland**: a proper Velothi wall (300-450 blocks) with two named passes (Blacklight to Windhelm; a southern pass
   to Kragenmoor and the Valus), Deshaan's fungal plains and Obsidian Gorge, Stonefalls' lava shields and the Brothers of
   Strife twin peaks, the Telvanni Peninsula's cliffs and coastal fungus forest, Necrom's tomb hills on the coast.
6. **Missing places** (SCALE 5.3): Holamayan (Azura's Coast), the Ministry of Truth (a floating rock over Vivec, XL
   weenie), Tel Fyr's tower, Mawia, Castle Ebonheart beside Vivec (about 7000, 1650 after the move), and Vvardenfell's
   four plantations (Arano, Arvel, Dren, Gro-Bagrat) and five house manors as places if absent.
7. **XL silhouettes**: Red Mountain and its plume, the Ministry of Truth, Vivec's palace, the Skar, Sadrith Mora's
   tower cluster, Mournhold's dome, Necrom's tomb hill, Ghostgate and the Ghostfence line, Molag Mar's cliff glow,
   Vivec's Antlers, the Brothers of Strife, Firewatch's beacon. Target 1 per region + 1 per 3 km²: about 26; 6 exist.

---

## 4. Roads, travel network and fast travel

**Named roads** (none exist for Morrowind today; from the game's signpost topology and Arena's mainland backbone):

| Road | Route | Class | Notes |
| --- | --- | --- | --- |
| West Gash road | gnisis - ald_velothi - khuul | road | Ridge road |
| Ald'ruhn road | aldruhn - maar_gan | road | Through the ash; storm shelters every 400 blocks |
| Odai road | aldruhn - caldera - balmora | highway | The island's main road, paved near Balmora |
| Pelagiad road | balmora - pelagiad - vivec | highway | Main road south |
| Seyda Neen road | seyda_neen - pelagiad | road | The outlander's first road |
| Foyada road | vivec - suran - molag_mar | road | Along the Molag Amur foyadas |
| Grazelands road | vos - tel_vos | road | |
| Ebonheart road | ebonheart - mournhold | highway | Mainland spine |
| Narsis road | mournhold - narsis - kragenmoor | highway | Continues as the Valus pass to Cheydinhal |
| Tear road | narsis - tear | road | Plantation road |
| Necrom road | mournhold - necrom | highway | Funeral and pilgrim road |
| Firewatch road | firewatch - necrom | road | Along the peninsula |
| Blacklight road | blacklight - kragenmoor | road | Under the Velothi |

Ash wastes and Red Mountain get trails only (the Ghostgate pass is a trail with the fence's one gate).
**Cross-province**: Windhelm - Blacklight over the Velothi (Skyrim); Kragenmoor - Cheydinhal over the Valus
(Cyrodiil); Tear - Thorn, the **slavers' causeway** into Black Marsh (our name; `black_marsh.md` 4).

**Travel modes** (Morrowind is the province where travel is identity; P-J2):

| Mode | Nodes and links | Status / work |
| --- | --- | --- |
| Silt striders | Ald'ruhn, Balmora, Gnisis, Khuul, Maar Gan, Molag Mar, Seyda Neen, Suran, Vivec (16 game links); mainland ring Ebonheart - Mournhold - Narsis - Tear - Necrom | exists (`StriderPorts`, 10 ports); restrict offers to game links; add the mainland ring |
| Boats | 25 game links: Hla Oad, Gnaar Mok, Khuul, Dagon Fel, Tel Mora, Vos, Tel Aruhn, Sadrith Mora, Tel Branora, Molag Mar, Vivec, Ebonheart, Holamayan; Khuul - Fort Frostmoth; Inner Sea ferries Vivec - Ebonheart - Necrom - Sadrith Mora | add (`ports.json` routes) |
| Mages Guild guides | Ald'ruhn, Balmora, Caldera, Vivec, Wolverine Hall (all-to-all), plus Mournhold | new guild service (fee, member discount) |
| Propylon chambers | Ring of 10 strongholds (Andasreth, Berandas, Falasmaryon, Valenvaryon, Rotheran, Indoranyon, Falensarano, Telasero, Marandus, Hlormaren); each needs its index | new; a ring, not a hub |
| Interventions | Almsivi to the nearest Temple; Divine to the nearest Imperial cult shrine or Legion fort (19 targets) | new spells and scrolls |
| Mark and Recall | anywhere | new spell pair |
| Temple teleport | Ebonheart - Mournhold | flavour |

Wilderness nodes: Temple pilgrim shrines (the Seven Graces), Velothi wayshrines and the propylons; hashed roadside
shrines fill the 800-block gap rule on the mainland. Road texture: ash-storm shelters (stone huts with a guar post),
egg-mine junctions, pilgrim cairns, Ordinator checkpoints, slaver patrols near Tear.

---

## 5. Settlements

### 5.1 Tier list

| Tier | Vvardenfell | Mainland |
| --- | --- | --- |
| Capital | (Vivec built and staffed at capital depth) | Mournhold |
| City | Vivec, Balmora, Ald'ruhn, Sadrith Mora | Ebonheart, Necrom, Tear, Blacklight, Firewatch |
| Town (Riverwood-class) | Gnisis, Caldera, Suran, Molag Mar; promote Maar Gan, Pelagiad, Seyda Neen, Tel Branora, Tel Aruhn, Tel Mora, Vos, Dagon Fel | Narsis, Kragenmoor, Davon's Watch, Silgrad Tower, Port Telvannis, Gorne; promote Muth Gnaar, Selfora, Hrogar's Hold, Dhalmora |
| Village | Khuul, Ald Velothi, Hla Oad, Gnaar Mok, Tel Fyr, Tel Vos, Ghostgate (garrison), Ashlander camps (Urshilaku, Ahemmusa, Erabenimsun, Zainab and the smaller camps: each with an ashkhan and a wise woman), Wolverine Hall, egg-mine villages | Mainland wharf and landing villages, Quarantine Serk, Senie, the Necrom harbours |
| Hamlet | Plantations (Arano, Arvel, Dren, Gro-Bagrat), manors, farms | Dres plantations round Tear, Nord farmsteads |

Named towns: 32 (Vvardenfell 16, mainland 16 incl. promotions), about 1.15/km² on Vvardenfell with Zafirbel Bay (its
game density, an intentional exception) and 0.74/km² on the mainland (above the 0.54 floor).

### 5.2 City plan briefs

`MARQUEE.md` has briefs for Vivec, Mournhold, Balmora, Ald'ruhn, Sadrith Mora, Necrom, Molag Mar and Ebonheart. This
table fixes the sheet causes and adds four mainland cities.

| City | Plan form (cause) | Class gradient (L7) | Weenie | Plan file |
| --- | --- | --- | --- | --- |
| Vivec | Cantons in water joined by bridges [Ascadian L6] | Palace and Temple canton > house cantons > Foreign Quarter > waterfront and under-canton | Palace ziggurat; Ministry of Truth above | bespoke exists; per-canton kits |
| Mournhold | Walled citadel round a domed temple and a great plaza; a ruined twin below [Deshaan L11] | Royal palace and Godsreach > bazaar > craft lanes > Old Mournhold's squatters | Almalexia's dome | bespoke exists; bazaar arcade, mirror undercity |
| Balmora | Twin-bank river city, bridge spine [West Gash L3] | High town (council manor, guilds) > Labourers' bank > dockside syndicate | Strider port tower | `balmora.json` exists; warm Hlaalu palette |
| Ald'ruhn | Town in and along a giant shell [Ashlands L1] | Councillors' halls inside the shell > Redoran retainers on the ridge > outlanders' lodgings outside | The Skar | `aldruhn.json` exists; shell landmark builder |
| Sadrith Mora | Mushroom-tower cluster, stems and footbridges [Zafirbel L6] | Council spire > masters' towers > servants and slaves at the stems > outlanders at the inn outside | Council spire | `sadrith_mora.json` exists |
| Necrom | Tomb terraces with processional avenues [Necrom L6] | Great families' mausoleums > priests and keepers > pilgrims' hostels > gravediggers | Temple tomb | new |
| Molag Mar | Cliff town over a lava-lit grotto [Molag Amur L6] | Temple canton > Redoran ledge houses > pilgrims' camp | Cliff glow | new |
| Ebonheart | Imperial star-fort grid, Dunmer quarter outside the wall [Stonefalls L6] | Council chamber and governor > Company hall and officers > Dunmer quarter | Castle bastion | new |
| Tear | Plantation city: compound walls, a sunken slave-market square, Dres manor-towers [Deshaan L5] | Dres lords' manors > overseers and traders > freemen > slave pens (the starkest gradient in Tamriel) | Market ziggurat | new (marquee entry to add) |
| Blacklight | Redoran border fortress-city at the Velothi foot [Stonefalls L6] | Councillor's hall > barracks > herders and smiths | Gate towers in the pass | new |
| Narsis | Hlaalu trade city of warehouse terraces on a lagoon [Deshaan L6] | Trade houses > warehousemen > lagoon boatmen | Counting-house dome | new |
| Firewatch | Walled cape city with a beacon tower [Telvanni L6] | Tower lord > harbour guild > fishers and thralls | Beacon | new |

### 5.3 Busy centres and why

Vivec (pilgrims, every house, the Temple; 73+ named NPCs at capital depth), Balmora (trade crossroads and every
guild), Mournhold (the capital and the goddess), Ebonheart (the Council and the Empire), Sadrith Mora (enchanted goods
and the house council). **Poor and failing**: Gnisis and the Grazelands egg villages (blighted mines), Seyda Neen
(dead-end customs town), Molag Mar's pilgrim camp, Tear's slave quarter, Old Mournhold's squatters.

### 5.4 Lived-in rules (Morrowind-specific)

* Every home has an ancestor shrine; every town a Temple shrine; Ordinators patrol Vivec and Mournhold.
* Ash-storm behaviour: townsfolk shelter, guars are led in, strider ports close (weather-linked schedules).
* Egg-mine shifts, guar caravans, netch herders, strider keepers and boatmen are visible jobs with beds and posts.
* Outlanders hear scorn lines from commoners and courtesy from Hlaalu traders (deed-keyed lines, F-REACT).
* Slaves exist as people with names, quarters and a Twin Lamps path to freedom, never as props.

---

## 6. Dungeons, underground and landmarks

### 6.1 Counts by theme vs the Skyrim floor (now / floor = median x area; today's region map)

Old-culture ruins = Velothi ancestral tombs. Elder-race = Dwemer ruins. Lairs = mage, necromancer and vampire lairs
plus Daedric ruins.

| Region (km²) | Caves | Old-culture ruins | Forts | Elder-race | Mines | Camps | Minor (towers, shacks, wrecks) | Lairs | Unmarked | All named dungeons |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ascadian_isles (1.7) | 18 / 4 | 15 / 2 | 3 / 2 | 3 / 0 | 9 / 1 | 0 / 2 | 4 / 3 | 5 / 1 | 0 / 1 | 59 / 15 |
| azuras_coast (1.3) | 12 / 3 | 15 / 2 | 2 / 1 | 3 / 0 | 5 / 1 | 0 / 1 | 4 / 3 | 9 / 1 | 0 / 1 | 51 / 12 |
| bitter_coast (0.5) | 7 / 1 | 7 / 1 | 0 / 1 | 1 / 0 | 0 / 0 | 0 / 1 | 3 / 1 | 3 / 0 | 0 / 0 | 21 / 4 |
| deshaan (5.5) | 14 / 14 | 4 / 8 | 1 / 6 | 2 / 1 | 2 / 2 | 0 / 6 | 0 / 11 | 2 / 4 | 0 / 3 | 25 / 49 |
| grazelands (2.0) | 9 / 5 | 10 / 3 | 1 / 2 | 4 / 0 | 11 / 1 | 0 / 2 | 3 / 4 | 3 / 1 | 0 / 1 | 43 / 18 |
| molag_amur, mainland (4.8) | 8 / 12 | 6 / 7 | 1 / 5 | 3 / 1 | 2 / 2 | 0 / 5 | 3 / 10 | 5 / 3 | 0 / 3 | 28 / 43 |
| red_mountain (1.6) | 6 / 4 | 2 / 2 | 2 / 2 | 7 / 0 | 2 / 1 | 0 / 2 | 0 / 3 | 1 / 1 | 0 / 1 | 24 / 14 |
| sheogorad (0.6) | 2 / 2 | 1 / 1 | 1 / 1 | 0 / 0 | 0 / 0 | 0 / 1 | 6 / 1 | 1 / 0 | 0 / 0 | 11 / 5 |
| stonefalls (5.2) | 11 / 14 | 4 / 7 | 3 / 6 | 1 / 1 | 2 / 2 | 1 / 5 | 1 / 10 | 4 / 4 | 0 / 3 | 28 / 46 |
| telvannis (8.7) | 13 / 23 | 2 / 13 | 1 / 10 | 2 / 2 | 1 / 4 | 1 / 9 | 4 / 17 | 3 / 6 | 0 / 5 | 27 / 77 |
| vvardenfell_ashlands (2.5) | 21 / 6 | 24 / 4 | 6 / 3 | 2 / 1 | 11 / 1 | 0 / 3 | 7 / 5 | 10 / 2 | 0 / 1 | 83 / 22 |
| west_gash (1.2) | 8 / 3 | 10 / 2 | 2 / 1 | 2 / 0 | 6 / 1 | 0 / 1 | 0 / 2 | 1 / 1 | 0 / 1 | 31 / 11 |

**Reading it.** Morrowind is two provinces in one table. **Vvardenfell is far above the floor** in count (323 named
sites in ~11.4 km² against a floor of ~101) because it keeps Morrowind's game density; its work is identity
(`d_identity` 0-8%), loops and marquee depth, not more dots. **The mainland is far below**: 108 sites against a
floor of ~215 (Deshaan 25/49, Stonefalls 28/46, Necrom coast 28/43, the Telvanni Peninsula 27/77), with almost no
Velothi tombs, forts, camps or minor sites. The mainland needs **~110 new sites**, built from its own sheet.
Military "camps" in Morrowind are Legion forts, house garrisons and Ordinator posts, not tents.

### 6.2 Inhabitant themes and why (L10)

| Theme | Where and why | Signature of |
| --- | --- | --- |
| Sixth House sleepers and corprus | Red Mountain citadels and Ashlands sanctums [P.L10.sixth_house] | Red Mountain, Ashlands |
| Blighted kwama and beasts | Egg mines downwind of the mountain [P.L10.blighted_mines] | Grazelands |
| Ancestral guardians (ghosts, skeletons) | Velothi family tombs [P.L10.ancestral_tombs] | every region; Necrom's catacombs |
| Daedra worshippers and Daedra | Daedric ruins [P.L10.daedric_ruins] | Azura's Coast, Molag Amur, Sheogorad |
| Dwemer constructs | Dwemer citadels [P.L10.dwemer_citadels] | Red Mountain, Stonefalls |
| Smugglers | Coast caves [P.L10.smugglers] | Bitter Coast |
| Slavers and runaway slaves | Plantation hinterland caves [P.L7.slavery] | Ascadian Isles, Deshaan |
| Telvanni towers gone wrong | Abandoned or mad wizards' towers [Telvanni L10] | Telvanni Peninsula, Zafirbel Bay |
| Bone thieves and necromancers | Raid the city of the dead [Necrom L10] | Necrom Coast |
| Camonna Tong hideouts | Mine and dock cellars [P.L7.criminal_syndicate] | West Gash |
| Wreckers and drowned crews | Reefs [Sheogorad L9] | Sheogorad |
| Pact-era war dead | Old border forts [Stonefalls L10] | Stonefalls |

### 6.3 Marquee (>= 1 hand-authored dungeon and 1 other per region)

| Region | Marquee dungeon | Other marquee |
| --- | --- | --- |
| Ashlands | Kogoruhn (the burned court) | Ald'ruhn; Urshilaku camp |
| West Gash | Arkngthand (the centrifuge chorus) | Balmora |
| Bitter Coast | A smugglers' sea-cave warren under Hla Oad (new brief) | Seyda Neen |
| Ascadian Isles | Under-canton of Vivec ("Water Under the Cantons") | Vivec; Fields of Kummu |
| Azura's Coast | Bal Fell, a headland Daedric fortress (new brief) | Tel Branora; Holamayan |
| Molag Amur (VV) | Ald Sotha (the pilgrim's tolls; moves here with the repaint) | Molag Mar |
| Grazelands | A blighted egg mine whose queen dreams (new brief) | Vos and Tel Vos |
| Sheogorad | Ald Daedroth, the island shrine-fortress (new brief) | Dagon Fel |
| Red Mountain | Odrosal or Vemynal, a Sixth House-held Dwemer citadel (new brief; the god's own hall stays off-limits) | Ghostgate and the Ghostfence |
| Zafirbel Bay | A tower gone wrong (choose among `tel_dreloth`, `tatasipal_spire`; new brief) | Sadrith Mora; `rkuldumz_spire` |
| Telvanni Peninsula | Arkzak Dwemer workings (new brief) | Port Telvannis; Firewatch beacon |
| Stonefalls | Inner Sea Armature (Dwemer, new brief) | Ebonheart; Vivec's Antlers |
| Deshaan | Old Mournhold (the lamplighter's round) | Mournhold; Tear |
| Necrom Coast | The deep catacombs of Necrom (new brief) | Necrom |

### 6.4 What lies below, and why

* **Dwemer**: the elder race's cities run under Red Mountain and through the island's flanks; on the mainland under
  Mournhold (Bamz-Amschend) and the Inner Sea shore. Machines still work; the Sixth House has moved into the
  mountain's. Dwemer lever-and-steam puzzles are the province's dungeon mechanic, with tomb wards second.
* **Velothi tombs**: every family's dead guard their own; robbing them is a crime and a curse. 87 on the island; the
  mainland needs its share (about 37 more) so that Necrom is the climax of a pattern, not an exception.
* **Daedric ruins**: shrines from the Chimer's first faith, now cult dens.
* **Unmarked (20)**: a sealed family tomb behind a home's cellar, a Twin Lamps hiding hole, a Dwemer bolt-hole under
  a strider port, a forgotten propylon index cache, a Sixth House dream-shrine in a Balmora basement, a drowned
  canton store-room. Each with a note or rumour.

---

## 7. Quests

### 7.1 Province arc: "The Dreamers' Census" (>= 19 quests across both halves)

The Grand Council at Ebonheart, where the houses meet under Imperial eyes, hires the player to count the dreamers:
people in every town are sleepwalking toward the mountain and carving the same mask. Each house blames another, the
Temple denies there is a problem, and the Ashlanders say it is the sign of a prophecy that is none of the player's
business (the returning hero is a rumour in this world, never the player). The trail runs the strider and boat
network from Seyda Neen to Balmora, Ald'ruhn, Sadrith Mora and Vivec, then over the Inner Sea to Mournhold, Necrom and
Tear, where the dream has started in the slave pens. It ends at Kogoruhn and the Ghostfence, where the player breaks
the dreaming's hold on the census towns (not the mountain's god). Consequences: towns lose or keep their sleepers (NPC
states), the Council changes its Imperial liaison, a Temple canon falls, and the Ghostfence's weakest pylon is
re-consecrated or abandoned (a visible wall change).

### 7.2 Region stories (hold conflicts) and settlement quests

| Region | Hold conflict (3+ quests) | World change |
| --- | --- | --- |
| Ashlands | **The Honour of the Shell** (MARQUEE): a poisoned duel blade in the Redoran council | Councillor replaced; a duelling ground opens at the shell gate |
| West Gash | **Three Ledgers, One River**: Hlaalu against the dockside syndicate over Caldera's ebony | Syndicate's dock hall closed or legitimised |
| Bitter Coast | **The Customs Ledger**: Seyda Neen's tax man and the Hla Oad smugglers | Customs house staffed by a new officer; a cave sealed |
| Ascadian Isles | **The Rice and the Lamp**: a plantation's slaves, the Twin Lamps and a planter's debts | Plantation freed, sold or fortified |
| Azura's Coast | **The Incurable**: Tel Fyr's patients escape toward the towns | Hospital reinforced; a village quarantined or spared |
| Molag Amur | **The Pilgrims' Toll**: nomad raids on the holy-mountain route | Pilgrim cairns guarded; a nomad truce |
| Grazelands | **Whose Grass**: Telvanni farm charters against nomad herding | A boundary of stones appears or is pulled down |
| Sheogorad | **Lights on the Reef**: wreckers at Dagon Fel | Lighthouse built on the reef |
| Red Mountain | **The Pylon Watch**: Ghostgate's patrols lose a fence pylon | Pylon restored or lost (the arc's finale ties in) |
| Zafirbel Bay | **A Bet Between Towers** (MARQUEE) | Tower allegiance changes |
| Telvanni Peninsula | **The Beacon Cape**: Firewatch's beacon dims and the slave ships run blind | Beacon rekindled; one slaver crew wrecked |
| Stonefalls | **The Bastion and the Quarter** (MARQUEE) at Ebonheart; Blacklight's pass dispute with Skyrim traders | Gate keys reissued; the pass toll changes |
| Deshaan | **Beneath the Twin City** (MARQUEE); **The Market at Tear**: an Indoril priest moves against the Dres slave market | Market hours cut or a Temple ban lifted |
| Necrom Coast | **A Grave Not Dug** (MARQUEE); the forbidden-library scholar who will not come home | A tomb row sealed; a scholar returned changed |

Every city adds a court problem and 3 favours; every town 2 problems and 1 secret, each tied to its industry (Gnisis'
sick queen, Caldera's miners' debts, Suran's house of pleasure, Vos' failed harvest, Maar Gan's pilgrims' missing
relic).

### 7.3 Faction lines

| Faction | Hub | Line | Exists today |
| --- | --- | --- | --- |
| House Hlaalu | Balmora | Trade, influence, a council seat (exclusive) | `house_hlaalu.json` (6) |
| House Redoran | Ald'ruhn | Duty, duels, defence of the north (exclusive) | `house_redoran.json` (6) |
| House Telvanni | Sadrith Mora | Service, theft, a tower of your own (exclusive) | `house_telvanni.json` (6) |
| Tribunal Temple | Vivec (and Mournhold) | Pilgrimages of the Seven Graces, Ordinator inquiries, the failing-gods secret | `tribunal_temple.json` (5) |
| Imperial Cult | Ebonheart | Alms and Divine shrines, Legion chaplaincy | `imperial_cult.json` (6) |
| Ashlanders | Urshilaku camp | Herd, hunt and witness rites; trust earned camp by camp | `ashlanders.json` (6) |
| Morag Tong | Vivec | Legal writs: assassination by licence, honour rules, no innocents | `morag_tong.json` (6) |
| Camonna Tong | Balmora docks | The native syndicate's jobs against outlanders | `camonna_tong.json` (6) |
| East Empire Company | Ebonheart | Charter work, ebony convoys, the Solstheim colony | `east_empire.json` (6) |
| Mages, Fighters, Thieves guilds | Balmora (Fighters, Thieves), Vivec and Balmora (Mages) | Local chapters with Morrowind jobs; the guild guides | shared files |
| Twin Lamps (new) | hidden, Ascadian Isles | Abolitionist underground: escapes, forged papers | new |
| House Indoril and House Dres (new, non-joinable) | Mournhold, Tear | Political chains on the mainland, opposed over slavery | new |

**Exclusive choice** (P-J1): the three Vvardenfell houses. Joining one closes the other two's upper ranks and changes
three towns' greetings.

### 7.4 Radiant (culture-specific templates, >= 6)

Morag Tong writ on a named target; cleanse a blighted egg-mine queen; escort pilgrims to a Grace shrine; return a
looted ancestor urn to its family tomb; guide an Ashlander herd past cliff racers; carry an Ebonheart Council summons;
smuggle a slave to the Twin Lamps (or claim the Dres bounty, with consequence); clear a strider route of kagouti.
Boards (MS12) are Temple notice stones in towns.

### 7.5 Festival (MS16)

**The Tribunal's day in Vivec** (pilgrims crowd the cantons, lanterns on the water). Second: the Ashlander herd
gathering in the Grazelands.

---

## 8. Components layout

| Component | File / system | Phase | Status |
| --- | --- | --- | --- |
| World sheet (province + 15 regions) | `tamriel/world/morrowind.json`, `worldsheet_lint.py` | MW0 | new |
| Geometry | island similarity, coastline vectors, `regions.png` repaint (game grid, region splits), mainland ranges and passes | MW1 | change |
| Places | re-ingest Vvardenfell; promote towns; ~110 mainland sites; missing places; unmarked | MW1, MW4 | change |
| Micro-biomes | `palettes.json` `micro`; heat raster from vents and foyadas | MW2 | new |
| Roads | `named_roads.json` (+13 roads, 3 passes) | MW2 | new |
| Travel | `StriderPorts` link restriction + mainland ring; boat routes in `ports.json`; guild guides; propylon ring and indices; interventions; Mark/Recall | MW2 | change |
| City plans | new: Necrom, Molag Mar, Ebonheart, Tear, Blacklight, Narsis, Firewatch, Port Telvannis; extend Vivec, Mournhold, Balmora, Ald'ruhn, Sadrith Mora; house kits in `styles/dunmer.json` | MW3 | change |
| Population | house retainers, slaves with quarters, Ashlander camps, Temple staff | MW3 | change |
| Dungeons | identity back-fill (Vvardenfell), mainland tomb and fort kits, Dwemer puzzle rooms, marquee plans | MW4 | change |
| Creatures | ash-storm and Blight spawns, cliff racer rookeries, netch herds, kwama by mine | MW4 | change |
| Weather | ash storm and blight storm tables (L2), Ghostfence fog | MW5 | change |
| Scenes | encounters: Ashlander herd crossing, pilgrim party, slave escape, ash-storm refuge, Ordinator stop, strider breakdown | MW5 | change |
| Quests | `quests/story/morrowind_*.json` (arc + 14 conflicts), faction files, Twin Lamps, Indoril, Dres | MW6 | change |
| Lore | `lore/books/morrowind_*.json`, voice, rumours, lore web | MW7 | change |
| Events (optional) | `events/morrowind.json`: ash-vent eruptions with fire Daedra (MS4) | MW8 | optional |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Vvardenfell's game geography, the Inner Sea, the Ghostfence ring | Red Mountain's caldera and foyadas, Molag Amur's lava fields, West Gash ridges, Grazelands slopes, Azura's islets | **Morrowind's blocky, low-relief terrain** (TES3 heights are compressed: the mountain was 271 m): our steeper curve and real ash dunes, cones and gorges; the mainland's empty middle with the Velothi wall and plains |
| Settlements | Every Vvardenfell town and its house; Arena's mainland city-states | House kits that never mix by accident (Hlaalu clay, Redoran shell, Telvanni fungus, Indoril marble, Dres compound) | Random kit choice per Dunmer town today (MARQUEE note) |
| Dungeons | 87 tombs, 20 Dwemer ruins, 33 Daedric ruins, 31 egg mines, 17 Sixth House bases | Puzzles and set pieces per culture, the Blight as a mechanic | TES3's same-room tomb interiors and the mainland's empty count |
| Life | Houses, Temple, Ashlanders, striders | Schedules, slaves as people, storms changing routines | Static, scheduleless NPCs |
| Quests | Factions and their conflicts | Exclusive house choice with consequences | Morrowind's own quests: all ours |
| Travel | Striders, boats, guides, propylons, interventions | A mainland strider ring and Inner Sea ferries | Nothing: Morrowind's network is the model |

---

## 10. Floors, gates and implementation phases

### 10.1 Current vs target per region (`parity.py`, 2026-10-13; today's region map)

"Now" for quests, NPCs and readables are parity densities times area (estimates).

| Region | km² | Mean | Weakest | Dungeons now / floor | Quests now / stage 1 / floor | Named NPCs now / floor | Readables now / stage 1 / floor |
| --- | ---: | ---: | --- | --- | --- | --- | --- |
| stonefalls | 5.2 | 44 | LOR 11, NPC 12, QST 29 | 28 / 46 | 6 / 16 / 73 | 8 / 175 | 12 / 42 / 1,013 |
| deshaan | 5.5 | 48 | LOR 15, NPC 16, ITM 31 | 25 / 49 | 9 / 17 / 77 | 11 / 185 | 18 / 44 / 1,076 |
| molag_amur (mainland) | 4.8 | 44 | LOR 13, SET 16, NPC 22 | 28 / 43 | 7 / 14 / 67 | 8 / 162 | 18 / 38 / 940 |
| telvannis | 8.7 | 45 | LOR 10, NPC 15, SET 25 | 27 / 78 | 10 / 26 / 122 | 13 / 294 | 14 / 70 / 1,705 |
| sheogorad | 0.6 | 46 | NPC 7, LOR 8, SET 22 | 11 / 5 | 5 / 8 / 8 | 0 / 19 | 2 / 8 / 109 |
| vvardenfell_ashlands | 2.5 | 57 | LOR 22, NPC 25, ITM 31 | 83 / 23 | 16 / 8 / 36 | 17 / 85 | 22 / 20 / 495 |
| grazelands | 2.0 | 38 | LOR 1, QST 6, NPC 7 | 43 / 18 | 1 / 8 / 28 | 0 / 67 | 0 / 16 / 387 |
| azuras_coast | 1.3 | 51 | LOR 10, NPC 29, ITM 31 | 51 / 12 | 8 / 8 / 19 | 9 / 45 | 9 / 10 / 263 |
| west_gash | 1.2 | 60 | ITM 31, LOR 36, NPC 40 | 31 / 11 | 22 / 8 / 17 | 21 / 41 | 22 / 10 / 238 |
| bitter_coast | 0.5 | 36 | NPC 0, QST 8, LOR 10 | 21 / 5 | 1 / 8 / 8 | 0 / 17 | 4 / 8 / 100 |
| ascadian_isles | 1.7 | 57 | NPC 29, ITM 31, LOR 33 | 59 / 16 | 21 / 8 / 24 | 15 / 59 | 26 / 14 / 340 |
| red_mountain | 1.6 | 39 | SET 0, NPC 0, STY 19 | 24 / 14 | 6 / 8 / 22 | 0 / 53 | 12 / 13 / 306 |
| **Province (excl. Solstheim)** | **35.6** | **53** | LOR 22, NPC 26, ITM 31 | **431 / 317** | **112 / 137 / 498** | **~100 / 1,196** | **~160 / 293 / 6,942** |

Solstheim (1.0 km², mean 86) is already near parity and follows the Skyrim plan.

**Headline targets (Morrowind):** the region repaint and the two region splits; ~110 new mainland sites (Deshaan,
Stonefalls, Necrom coast, Telvanni Peninsula to the 8.9/km² floor); Vvardenfell `d_identity` from ~4% to 80%
without adding dots; 20 unmarked secrets; Red Mountain's SET/NPC from 0 (Ghostgate as a staffed garrison village);
quests 112 to 137 at stage 1 and ~500 at the floor; named NPCs to 37.5 per named town (~1,200 over 32 towns); 13
named roads and all six travel modes live; exclusive house choice working.

### 10.2 Gates

As `cyrodiil.md` 10.2, plus: strider and boat offers match the game's link lists (data check); the propylon ring is
a closed ring of 10 with an index each; F-TRAVEL p90 <= 500 on the mainland; the anti-Skyrim tree rule (section 2)
holds in a biome census; Haiku blind test names the region from 3 screenshots (G1) and never answers "Skyrim".

### 10.3 Phases

| Phase | Scope | Depends on | Gate |
| --- | --- | --- | --- |
| MW0 | `tamriel/world/morrowind.json` from section 1 | MS22 lint | worldsheet_lint 0 |
| MW1 | Island similarity, coastline, region repaint and splits (M2), mainland ranges and passes, relief | MW0, owner M2 | layout_lint, relief (p50 >= 30, rugged >= 4.5), corridor gate |
| MW2 | Named roads, strider restriction and mainland ring, boats, guild guides, propylons, interventions, micro-biomes | MW1 | roadplan_lint, F-TRAVEL, F-MICRO |
| MW3 | Town promotions, eight new city plans, house kits, population (slaves, Ashlanders, Temple) | MW1 | SET, F-COURT, population_lint, Haiku |
| MW4 | Vvardenfell identity back-fill and loops; ~110 mainland sites; 20 unmarked; marquee plans | MW0 | DUN floors, F-LOOP, d_identity >= 50% |
| MW5 | Ash and blight weather, scenes and encounters, wonder moments (the plume at night, a strider crossing at dawn, the Ghostfence glow, a netch herd over the Grazelands) | MW2 | F-ENC, F-WONDER, F-QUIET |
| MW6 | Province arc, 14 hold conflicts, faction extensions, Twin Lamps, Indoril/Dres | MW3, MW4 | QST stage 1, q_placed, F-HOOK |
| MW7 | Readables, voice, rumours, lore web, festival | MW6 | LOR stage 1, novelty, MS20 |
| MW8 | Optional ash-vent events; floor push with generators | MW7 | Floors per 10.1 |
