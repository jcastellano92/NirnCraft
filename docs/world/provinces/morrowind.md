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

### 1.0 Province sheet (`tamriel/world/morrowind.json`, block `province`)

**World truth.** Morrowind is the Dunmer homeland: the island of Vvardenfell, with the volcano Red Mountain at its
heart, and the mainland beyond the Inner Sea, walled from Skyrim and Cyrodiil by the Velothi and Valus ranges. Its
people worship the Tribunal, three living gods who rule from Vivec, Mournhold and a hidden city, and honour their own
ancestors whose bones guard every family. Five Great Houses split the land: the Hlaalu traders, the Redoran warriors
and the Telvanni wizards on Vvardenfell; the Indoril priest-nobles and the Dres slave-planters on the mainland. The
Empire keeps forts and a trading charter it calls an alliance. Now ash storms carry a plague out of the mountain, men
dream of a sleeping god, and the Tribunal's walls of ghost-light are the only thing holding the mountain in.

| Layer | Fields `id`: text [causes] |
| --- | --- |
| L1 Geology | `red_mountain`: an active volcano at Vvardenfell's centre, with lava channels (foyada) radiating from its crater. `ash_mantle`: wind-blown ash over the north and east. `velothi_wall`: the mainland's western range, the border with Skyrim. `valus_ridge`: the south-western range, the border with Cyrodiil. `inner_sea`: the shallow sea between the island and the mainland. `volcanic_shields`: lava plains and old cones on the mainland's west (Stonefalls). `fungal_soils`: ash-and-loam soils that grow giant mushrooms. `ebony_and_glass`: ebony and raw glass in volcanic rock. `kwama_hills`: egg-mine colonies dug into hills everywhere. `dwemer_deeps`: the vanished Dwemer's cities cut into rock |
| L2 Climate | `ash_storms`: the mountain's ash rides north and east winds over the Ashlands, Molag Amur and the mainland coast [L1.red_mountain]. `blight_storms`: red storms that carry the Blight [L10.sixth_house]. `wet_south`: the Ascadian Isles and Bitter Coast are mild and rainy [L1.inner_sea]. `cold_north`: Sheogorad and the Velothi foot are cool and stormy. `fumarole_heat`: hot ground round vents and lava [L1.red_mountain] |
| L3 Water | `odai_and_rivers`: few rivers; the Odai through Balmora is the largest [L1]. `ascadian_lakes`: lakes of the fertile south. `inner_sea_coasts`: most settlements are coastal [L1.inner_sea]. `cisterns_and_wells`: ash country lives on cisterns and deep wells. `who_holds_water`: Great House lords hold the wells in their towns; plantations hold the irrigation; Ashlanders know the hidden springs |
| L4 Ecology | `mushroom_forest`: emperor-parasol and Telvanni giant fungi [L1.fungal_soils]. `ash_waste`: scrub, scathecraw and trama root [L1.ash_mantle]. `grazeland_steppe`: wickwheat and hackle-lo grass [L2.wet_south]. `bitter_swamp` with marshmerrow [L3.inner_sea_coasts]. `lava_field` with fire fern [L2.fumarole_heat]. Fauna: guar (herded), netch (floating), silt striders (tamed for travel), kagouti, alit, nix-hounds, cliff racers (everywhere), kwama and scribs, mudcrabs, slaughterfish, dreugh; blighted beasts and ash creatures inside the Ghostfence [L10.sixth_house]; lesser Daedra at the old shrines [L8.daedra_veneration] |
| L5 Economy | `kwama_eggs`: the staple, from egg mines [L1.kwama_hills]. `saltrice_plantations`: slave-worked fields in the south and round Tear [L3.ascadian_lakes, L7.slavery]. `ebony_mining`: the Empire's chartered mines [L1.ebony_and_glass]. `guar_and_netch`: leather and meat [L4]. `east_empire_charter`: an Imperial trading company with its hall at Ebonheart [L9.imperial_presence]. `house_trade`: Hlaalu merchant networks, Telvanni enchanting, Redoran arms [L7.house_order]. `slave_trade`: Tear's markets and slaver raids into Black Marsh [L7.slavery] |
| L6 Settlement | `coastal_and_river_towns` [L3.inner_sea_coasts]. `house_seats`: Balmora (Hlaalu), Ald'ruhn (Redoran), Sadrith Mora (Telvanni), Mournhold (Indoril, capital), Tear (Dres) [L7.house_order]. `temple_cities`: Vivec, Mournhold, Necrom [L8.tribunal]. `imperial_forts_and_ports`: Ebonheart, Pelagiad, the Legion forts, Seyda Neen's customs house [L9.imperial_presence]. `egg_mine_villages` [L5.kwama_eggs]. `ashlander_camps`: nomad yurts away from the towns [L7.ashlanders]. `busy_centres`: Vivec (pilgrims and every house), Balmora (trade, guilds), Mournhold (the capital), Ebonheart (Council and Empire), Sadrith Mora (wizards' trade) [L5.house_trade] |
| L7 Society | `house_order`: everyone belongs to a house, the Temple, the Empire or nothing. `caste_ladder`: house nobles and Temple canons > house retainers, merchants and craftsmen > commoners > outlanders (tolerated) > Ashlanders (outside) > slaves (property). `slavery`: Argonians and Khajiit owned by law [L9.house_politics]. `ashlanders`: nomad tribes who reject the Temple and the towns. `outlander_scorn`: foreigners are second-class. `legal_murder`: a licensed guild of assassins settles disputes by writ (the Morag Tong) [L9.house_politics]. `criminal_syndicate`: a native crime family that hates outlanders (the Camonna Tong) [L7.outlander_scorn] |
| L8 Belief | `tribunal`: Almalexia, Sotha Sil and Vivec, living gods, with the Ordinators as their armed priesthood [L11.tribunal_apotheosis]. `ancestor_worship`: family tombs, household shrines and guardian ghosts; bonemeal and ash are sacred [L11.chimer_era]. `daedra_veneration`: the "good" Daedra (Azura, Boethiah, Mephala) honoured as old teachers; the others feared [L11.chimer_era]. `ashlander_old_ways`: the Ashlanders keep the old Daedric faith and a prophecy of a returning hero [L7.ashlanders]. `imperial_cult` in the forts [L9.imperial_presence]. `pilgrimage`: shrines of the Seven Graces and Temple pilgrim routes [L8.tribunal] |
| L9 Politics | `tribunal_failing`: the gods' power ebbs; the Temple hides it [L10.sixth_house]. `house_politics`: Hlaalu court the Empire, Redoran hold to honour, Telvanni to power, Indoril to the Temple, Dres to slavery. `imperial_presence`: Legion forts, a governor and a trade charter [L11.armistice]. `ghostfence_holds`: the Temple's wall round the mountain [L10.sixth_house]. `slavery_question`: an abolitionist underground (the Twin Lamps) against the Dres and Telvanni [L7.slavery]. `argonian_grudge`: raids into Black Marsh breed hatred that will one day come back [X: black_marsh.P.L9.slaver_raids] |
| L10 Hidden | `sixth_house`: a house of sleepers serving a god under the mountain, spreading through dreams and corprus [L1.red_mountain, L11.red_mountain_battle]. `dwemer_citadels`: empty halls with working machines [L1.dwemer_deeps]. `daedric_ruins`: Chimer-era shrines held by cultists [L8.daedra_veneration]. `ancestral_tombs`: 87 family tombs with their guardians [L8.ancestor_worship]. `smugglers`: coast caves for ebony, skooma and moon sugar [L5.ebony_mining]. `blighted_mines`: egg mines where the queen sickens [L2.blight_storms] |
| L11 History | `chimer_era`: the Chimer's exodus from Summerset under Veloth; Velothi towers and Daedric shrines. `dwemer_war`: Chimer and Dwemer united against the Nords, then fought at Red Mountain, where the Dwemer vanished. `red_mountain_battle`: Nerevar's death there. `tribunal_apotheosis`: his counsellors became gods. `velothi_strongholds`: the propylon-linked fortresses of the old wars. `armistice`: Morrowind joined the Empire by treaty, not conquest. `pact_era`: in the Second Era Dunmer, Nords and Argonians fought side by side and Argonian slaves in Bal Foyen were briefly freed |

### 1.1 Region list and the two map fixes it needs

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

### 1.2 Region sub-sheets

#### Ashlands (`vvardenfell_ashlands`, seat Ald'ruhn)

**World truth.** Grey ash country north and west of the mountain, swept by storms that bury roads in hours. Ald'ruhn,
the Redoran seat, is built inside and around the shell of a giant dead crab, and its warrior-nobles hold the north
by duty and stubbornness. Maar Gan's pilgrims, Gnisis' egg mine, Khuul and Ald Velothi's harbours, the Urshilaku
nomads and a Legion fort all live off the ash. The Sixth House's sleepers have taken old strongholds here (Kogoruhn),
and the Blight comes out of the storms.

| Layer | Fields |
| --- | --- |
| L1 | `ash_plain`, `scoria_ridges` [P.L1.ash_mantle]. `crab_shell_rock`: the Skar [P.L1]. `velothi_strongholds` (Falasmaryon, Valenvaryon, Andasreth, Berandas) [P.L11.velothi_strongholds] |
| L2 | `ash_storm_track` [P.L2.ash_storms]. `blight_season` [P.L2.blight_storms] |
| L3 | `deep_wells`, `ald_ruhn_cisterns` under the shell [P.L3.cisterns_and_wells] |
| L4 | `ash_dune`, `scoria_scrub` (scathecraw, trama), `fumarole_flat` (fire fern), `kwama_hill`, `storm_hollow`. Cliff racers, kagouti, alit |
| L5 | `egg_mines` (Gnisis) [P.L5.kwama_eggs]. `redoran_arms` [P.L5.house_trade]. `shell_crafts` (chitin) |
| L6 | `aldruhn_under_the_skar` [L1.crab_shell_rock]. `pilgrim_and_harbour_villages` (Maar Gan, Khuul, Ald Velothi) [P.L6.coastal_and_river_towns]. `urshilaku_camp` [P.L6.ashlander_camps]. `buckmoth_fort` [P.L6.imperial_forts_and_ports] |
| L7 | `redoran_duty`: honour, duels, service [P.L7.house_order]. `ashlander_traders_at_the_gate` |
| L8 | `redoran_temple_piety` [P.L8.tribunal]. `urshilaku_prophecy` [P.L8.ashlander_old_ways] |
| L9 | `council_of_ald_ruhn`: councillors split over the Empire [P.L9.house_politics]. `sleepers_in_the_north` [P.L10.sixth_house] |
| L10 | `kogoruhn`: a Sixth House citadel in an old stronghold. `missamsi`: a blighted sanctum. `daedric_ruins` (Ashalmawia, Ebernanit and others) [P.L10.daedric_ruins] |
| L11 | `shell_founding`, `stronghold_wars` [P.L11.velothi_strongholds] |

#### West Gash (`west_gash`, seat Balmora)

**World truth.** Rocky ridges and green valleys on the west of the island, where the Odai runs through Balmora, the
Hlaalu city of yellow plaster, guild halls and trade. Upriver the Empire's charter town of Caldera digs ebony. The
Hlaalu do business with the Empire and anyone else; the native crime family runs the docks and hates the outlanders
who now own the mines. Dwemer ruins (Arkngthand) sit in the hills, and a lava channel scars the ridges.

| Layer | Fields |
| --- | --- |
| L1 | `gash_ridges`, `odai_gorge`. `ebony_seam` at Caldera [P.L1.ebony_and_glass]. `foyada_mamaea` [P.L1.red_mountain] |
| L2 | `sheltered_west`: less ash, more rain than the Ashlands [P.L2.wet_south] |
| L3 | `odai_river` [P.L3.odai_and_rivers]. `balmora_banks`: the river splits the city's classes |
| L4 | `ridge_heath` (stoneflower, bittergreen), `odai_terrace`, `gorge_fern`, `mine_spoil` [L5.ebony]. Kagouti, cliff racers, nix-hounds |
| L5 | `hlaalu_trade` [P.L5.house_trade]. `ebony` (Caldera, Imperial charter) [L1.ebony_seam]. `guild_services` |
| L6 | `balmora_on_the_odai` [L3.odai_river]. `caldera_company_town` [L5.ebony]. `moonmoth_fort` |
| L7 | `merchant_city`: guild row and council manor [L5.hlaalu_trade]. `dockside_syndicate` [P.L7.criminal_syndicate]. `outlander_miners` |
| L8 | `pragmatic_piety`: Temple attended, Imperial cult tolerated [P.L8] |
| L9 | `hlaalu_collaboration`: the house sells the island to the Empire, say its rivals [P.L9.house_politics]. `mine_ownership_fight` [L5.ebony] |
| L10 | `arkngthand` (Dwemer) [P.L10.dwemer_citadels]. `smuggled_ebony` [P.L10.smugglers]. Sixth House shrines in the hills (Sanit, Abinabi) |
| L11 | `odai_settlement`, `charter_granted` [P.L11.armistice] |

#### Bitter Coast (`bitter_coast`)

**World truth.** A warm, rotting swamp coast on the south-west, all twisted trees, mud and fog. Seyda Neen's customs
house and lighthouse greet ships from the Empire; Hla Oad and Gnaar Mok are fishing villages that do better from
smuggling than from fish. Caves along the shore hold contraband and those who guard it, and Daedric ruins rot in the
mangrove. The first impression of Morrowind for most outlanders, and a misleading one.

| Layer | Fields |
| --- | --- |
| L1 | `mud_coast`, `sea_caves` [P.L1.inner_sea] |
| L2 | `fog_and_heat` [P.L2.wet_south] |
| L3 | `brackish_inlets` [P.L3.inner_sea_coasts] |
| L4 | `bitter_swamp` (marshmerrow, twisted trees), `mudflat`, `smuggler_cove`, `kelp_shallows`. Mudcrabs, slaughterfish, dreugh |
| L5 | `fishing`, `contraband` [P.L10.smugglers] |
| L6 | `customs_port` (Seyda Neen) [P.L6.imperial_forts_and_ports]. `fishing_villages` (Hla Oad, Gnaar Mok) |
| L7 | `smugglers_and_tax_men` [L5.contraband] |
| L8 | `bitter_superstitions`: drowned men's lights in the fog |
| L9 | `customs_corruption` [L6.customs_port] |
| L10 | `smuggler_caves` (most of the region's 21 sites) [L1.sea_caves]. `daedric_mire_ruins` (Ashurnibibi and others) [P.L10.daedric_ruins] |
| L11 | `prisoner_landings`: the Empire ships its pardoned prisoners here |

#### Ascadian Isles (`ascadian_isles`, seat Vivec)

**World truth.** The green, fertile south: lakes, rice paddies and the great water city of Vivec, a ring of stepped
cantons standing in the sea, home of the living god and his Temple. Around it lie slave plantations of saltrice,
Pelagiad's Imperial garrison, Suran's pleasure houses and the pilgrim shrines of the Seven Graces. The wealth here is
old and the cruelty is quiet. A Daedric ruin of the old faith (Ald Sotha) and Dwemer halls lie within a day's walk of
the god's palace.

| Layer | Fields |
| --- | --- |
| L1 | `fertile_lowland`, `lake_basins` [P.L1.fungal_soils] |
| L2 | `mild_rain` [P.L2.wet_south] |
| L3 | `ascadian_lakes`, `paddy_irrigation`: plantation owners hold the water [P.L3.who_holds_water] |
| L4 | `saltrice_paddy`, `heather_lake_shore`, `parasol_grove` (emperor parasol), `lagoon_reed`, `plantation_ring`. Netch, mudcrabs, scribs |
| L5 | `saltrice_plantations` [P.L5.saltrice_plantations]. `pilgrim_economy` [P.L8.pilgrimage] |
| L6 | `vivec_cantons` [P.L6.temple_cities]. `pelagiad_garrison`, `suran_on_the_road` [P.L6.imperial_forts_and_ports] |
| L7 | `plantation_slaves` [P.L7.slavery]. `canton_ranks`: Temple and noble cantons above the Foreign Quarter and the waterfront |
| L8 | `vivec_the_living_god` [P.L8.tribunal]. `seven_graces` |
| L9 | `twin_lamps_underground` [P.L9.slavery_question]. `temple_secrets` [P.L9.tribunal_failing] |
| L10 | `under_vivec`: flooded canton foundations and sewers. `ald_sotha` [P.L10.daedric_ruins]. `escaped_slave_caves` [L7.plantation_slaves] |
| L11 | `vivec_founding`, `fields_of_kummu` |

#### Azura's Coast (`azuras_coast`)

**World truth.** The broken eastern coast: hundreds of rocky islets, kelp coves and Telvanni towers. Tel Branora on
the southern tip, Tel Aruhn and the towers of lesser wizards rise from the islands; the old Telvanni master at Tel Fyr
keeps a hospital for the incurable; the Dreamers' monastery (Holamayan) hides in a cove. Daedric shrines to Azura and
others stand on the headlands, and Dwemer ruins break the sea cliffs.

| Layer | Fields |
| --- | --- |
| L1 | `islet_maze`, `sea_cliffs` [P.L1.inner_sea] |
| L2 | `sea_storms`, `dawn_light` (Azura's coast faces the sunrise) |
| L3 | `kelp_coves`, `rain_cisterns` |
| L4 | `rocky_islet`, `kelp_cove`, `comberry_heath`, `cliff_racer_rookery`, `telvanni_spore_garden` |
| L5 | `telvanni_enchanting` [P.L5.house_trade]. `fishing`. `slave_market_at_tel_aruhn` [P.L5.slave_trade] |
| L6 | `wizard_towers` (Tel Branora, Tel Aruhn, Tel Fyr) [P.L6.house_seats] |
| L7 | `wizard_and_servant` [P.L7.caste_ladder] |
| L8 | `azura_shrine` [P.L8.daedra_veneration]. `dreamer_monastery` |
| L9 | `telvanni_rivalries` [P.L9.house_politics]. `corprus_hospital` [P.L10.sixth_house] |
| L10 | `daedric_headland_ruins` (Bal Fell, Kushtashpi) [P.L10.daedric_ruins]. `sea_cliff_dwemer` |
| L11 | `azura_dawn_legends` |

#### Molag Amur, Vvardenfell (proposed `molag_amur`, carved from `azuras_coast`, `ascadian_isles`, `red_mountain`)

**World truth.** Black lava fields and cinder cones south-east of the mountain, the harshest country on the island.
Molag Mar, a Temple and Redoran pilgrim town cut into a cliff, is the only town; Erabenimsun nomads, the most warlike
of the Ashlanders, roam the rest. Pilgrims cross it to the holy mountains of Assarnibibi and Kand. Fire ferns grow at
the vents, and Daedra haunt the old shrines in the lava.

| Layer | Fields |
| --- | --- |
| L1 | `lava_field`, `cinder_cones`, `obsidian_flats` [P.L1.red_mountain] |
| L2 | `ash_and_heat` [P.L2.ash_storms, P.L2.fumarole_heat] |
| L3 | `no_rivers`; `pilgrim_cisterns` along the route |
| L4 | `lava_field`, `fumarole_fern`, `obsidian_flat`, `scoria_cone`, `sulphur_pool`. Kagouti, scamps, cliff racers |
| L5 | `pilgrim_trade` [P.L8.pilgrimage]. `obsidian_and_fire_fern` |
| L6 | `molag_mar_cliff_town` [L5.pilgrim_trade]. `erabenimsun_camp` |
| L7 | `warlike_nomads` [P.L7.ashlanders] |
| L8 | `holy_mountains` (Assarnibibi, Kand) [P.L8.pilgrimage] |
| L9 | `erabenimsun_raids` on pilgrims [L7.warlike_nomads] |
| L10 | `lava_daedra_shrines` [P.L10.daedric_ruins] |
| L11 | `burned_land` [P.L11.red_mountain_battle] |

#### Grazelands (`grazelands`, seat Vos)

**World truth.** Rolling grass steppe in the north-east, the only open herding land on the island. Ashlander herds
graze it, and the Telvanni have pushed their farming village of Vos and the tower of their one reasonable councillor
into it. Wickwheat and hackle-lo grow wild, guar herds move with the seasons, and the nomads and the wizards argue
over who owns grass nobody planted. Egg mines here are going quiet as the Blight reaches them.

| Layer | Fields |
| --- | --- |
| L1 | `grass_slopes`, `kwama_hills` [P.L1.kwama_hills] |
| L2 | `lee_of_the_storms`: less ash [P.L2.ash_storms] |
| L3 | `seasonal_springs` known to the nomads [P.L3.who_holds_water] |
| L4 | `wickwheat_steppe`, `hackle_lo_meadow`, `guar_commons`, `ash_fringe`, `vos_mushroom_farm` |
| L5 | `herding` [P.L5.guar_and_netch]. `vos_farms`. `egg_mines` (10, many blighted) |
| L6 | `vos_and_tel_vos` [P.L6.house_seats]. `zainab_and_ahemmusa_camps` [P.L6.ashlander_camps] |
| L7 | `nomads_vs_farmers` [P.L7.ashlanders] |
| L8 | `ashlander_herd_rites` |
| L9 | `grass_rights`: Telvanni charters against nomad custom [L7.nomads_vs_farmers] |
| L10 | `blighted_egg_mines` [P.L10.blighted_mines]. `dwemer_ruins` (Nchuleft and others), Sixth House bases (Ainab, Sharapli) |
| L11 | `old_herd_paths` |

#### Sheogorad (`sheogorad`, seat Dagon Fel)

**World truth.** A cold scatter of islands off the north coast, wrecks on every reef. Dagon Fel, a harbour run by
Nord settlers, trades with Skyrim and with whoever survives the reefs. Daedric shrine-fortresses (Ald Daedroth among
them) and Velothi towers stand on the islets, and the Temple keeps a pilgrim shrine at the northern edge of the world.

| Layer | Fields |
| --- | --- |
| L1 | `wave_cut_islets`, `reefs` |
| L2 | `cold_sea_storms` [P.L2.cold_north] |
| L3 | `rain_barrels_and_springs` |
| L4 | `islet_heath`, `wreck_reef`, `kelp_bed`, `gull_cliff`, `driftwood_strand`. Dreugh, slaughterfish |
| L5 | `salvage` [L1.reefs]. `nord_trade` with Skyrim |
| L6 | `dagon_fel_harbour` [L5.nord_trade] |
| L7 | `nord_dunmer_mix` |
| L8 | `sanctus_shrine` [P.L8.pilgrimage]. `mad_god_shrine` (Ald Daedroth) [P.L8.daedra_veneration] |
| L9 | `wreckers_suspected` [L5.salvage] |
| L10 | `shipwrecks`, `velothi_towers`, `daedric_island_fortress` |
| L11 | `nord_landings` |

#### Red Mountain (`red_mountain`, seat Ghostgate)

**World truth.** The volcano itself, walled by the Tribunal's Ghostfence, a ring of shimmering wards anchored by
pylons of the dead. Inside, Dwemer citadels taken by the Sixth House, blighted beasts, ash creatures and red storms.
Ghostgate, the Temple's fortress on the one pass through the fence, is the only settlement; Buoyant Armigers and
Ordinators hold it. The mountain's plume is the island's weenie, visible from everywhere.

| Layer | Fields |
| --- | --- |
| L1 | `caldera`, `cinder_slopes`, `foyadas` (Esannudan, Ashur-Dan, Ilibaal) [P.L1.red_mountain] |
| L2 | `blight_storms`, `plume_shadow` [P.L2.blight_storms] |
| L3 | `none_inside`: no water inside the fence; Ghostgate's cisterns |
| L4 | `caldera_rim`, `cinder_waste`, `blight_scar`, `foyada_lava`, `ghostfence_zone`. Ash creatures, corprus beasts, blighted cliff racers |
| L5 | `no_economy`: Ghostgate is supplied from outside |
| L6 | `ghostgate` [P.L9.ghostfence_holds] |
| L7 | `garrison_life`: Temple soldiers on rotation |
| L8 | `fence_of_the_dead`: the wall's power comes from ancestors' bones [P.L8.ancestor_worship] |
| L9 | `fence_weakening` [P.L9.tribunal_failing] |
| L10 | `sixth_house_citadels` in Dwemer halls (Vemynal, Odrosal, Tureynulal, Endusal) [P.L10.sixth_house, P.L10.dwemer_citadels] |
| L11 | `red_mountain_battle`, `ghostfence_raised` |

#### Zafirbel Bay (proposed `zafirbel_bay`, Vvardenfell half of `telvannis`, seat Sadrith Mora)

**World truth.** The eastern bay and its islands, the Telvanni wizards' Vvardenfell heartland. Sadrith Mora is a
cluster of giant fungal towers with no streets, only stems and footbridges, where the house council meets. Outside it
the Empire's Wolverine Hall holds the guilds and a fort, and the Gateway Inn is where outlanders must sleep. Towers of
ambitious wizards (Tel Naga) and Dwemer pipes on the reefs complete a bay where might is the only law.

| Layer | Fields |
| --- | --- |
| L1 | `bay_islands`, `dwemer_reef_vents` [P.L1.dwemer_deeps] |
| L2 | `mild_bay` |
| L3 | `rain_and_spores`: towers drink from fungal catchments |
| L4 | `telvanni_mushroom_forest`, `spore_glade`, `tidal_flat`, `coral_reef` |
| L5 | `enchanting_and_spells` [P.L5.house_trade]. `slave_labour` [P.L7.slavery] |
| L6 | `sadrith_mora_towers` [P.L6.house_seats]. `wolverine_hall` [P.L6.imperial_forts_and_ports] |
| L7 | `power_is_rank` [P.L7.house_order] |
| L8 | `indifferent_piety`: the wizards tolerate the Temple, no more |
| L9 | `council_of_masters` [P.L9.house_politics]. `imperial_enclave_tension` |
| L10 | `wizard_tower_dungeons`, `rkuldumz_spire` [P.L10.dwemer_citadels] |
| L11 | `telvanni_exile_to_the_east` |

#### Telvanni Peninsula (`telvannis`, mainland half; halves: Port Telvannis / Firewatch)

**World truth.** The mainland's wild east: cliffs, coastal mushroom forest and wizard-lords who answer to no one.
Port Telvannis is the house's old mainland seat and harbour; Firewatch, a walled city at the north, keeps a fire on
its tower for ships rounding the cape. Between them lie spore forests, Dwemer workings and towers that hire
adventurers and rarely pay. The peninsula trades slaves and enchanted goods by sea.

| Layer | Fields |
| --- | --- |
| L1 | `peninsula_cliffs`, `fungal_forest_soils` [P.L1.fungal_soils] |
| L2 | `sea_rain`, `ash_shadow` on the south |
| L3 | `cliff_springs` |
| L4 | `coastal_mushroom_forest`, `spore_glade`, `black_sand_beach`, `cliff_heath`, `dwemer_vent_field` |
| L5 | `sea_trade` [P.L5.house_trade], `slave_shipping` [P.L5.slave_trade] |
| L6 | `port_telvannis`, `firewatch_beacon_city` [L1.peninsula_cliffs] |
| L7 | `lord_and_thrall` [P.L7.caste_ladder] |
| L8 | `mephala_shrines` [P.L8.daedra_veneration] |
| L9 | `wizard_feuds` [P.L9.house_politics] |
| L10 | `towers_gone_wrong`, `dwemer_workings` (Arkzak, Bthungdahrk) |
| L11 | `old_telvanni_seat` |

#### Stonefalls (`stonefalls`, seat Ebonheart; halves: Blacklight and the Velothi foot / Ebonheart and the Inner Sea shore)

**World truth.** The mainland's volcanic west, from the Velothi foothills at Blacklight down to Ebonheart on the
Inner Sea and Kragenmoor by the Cyrodiil border. Ebonheart is the Empire's city in Morrowind: Legion garrison, the
trading company's hall and the chamber where the Great Houses' council meets. Lava shields, ash fields and twin
volcanic peaks fill the interior; Nord homesteads cling to the north, and Dhalmora remembers the old pact that once
freed Argonian slaves here. Redoran blades guard the border; Hlaalu ships fill the ports.

| Layer | Fields |
| --- | --- |
| L1 | `lava_shields`, `twin_peaks` (Brothers of Strife), `ash_mountain`, `velothi_foothills` [P.L1.volcanic_shields, P.L1.velothi_wall] |
| L2 | `ash_falls` [P.L2.ash_storms]. `snow_on_the_foothills` [P.L2.cold_north] |
| L3 | `inner_sea_shore` [P.L3.inner_sea_coasts]. `lava_tube_springs` |
| L4 | `basalt_field`, `ash_scrub`, `lava_tube`, `inner_sea_strand`, `foothill_pine` (the only conifers in the province, at the Skyrim edge), `nord_farmstead` |
| L5 | `ports` (Davon's Watch, Abasipal wharf) [P.L5.house_trade]. `east_empire_hall` [P.L5.east_empire_charter]. `obsidian_and_basalt` |
| L6 | `ebonheart_council_city` [P.L6.imperial_forts_and_ports]. `blacklight_border_city` [L1.velothi_foothills]. `kragenmoor_on_the_valus_road`. `silgrad_tower` |
| L7 | `imperial_district_vs_dunmer_quarter` [L6.ebonheart_council_city]. `nord_settlers` (Hrogar's Hold) |
| L8 | `imperial_chapels` beside Temple shrines |
| L9 | `grand_council` of the houses at Ebonheart [P.L9.house_politics]. `border_watch` on two frontiers |
| L10 | `inner_sea_armature` (Dwemer) [P.L10.dwemer_citadels]. `pact_era_forts` (Virak, Arand, Heimlyn Keep) |
| L11 | `pact_era` [P.L11.pact_era]. `bal_foyen_treaty` |

#### Deshaan (`deshaan`, capital Mournhold)

**World truth.** The mainland heart: wide fungal plains glowing at night, lava canyons and lagoons, and Mournhold, the
white-and-gold capital of the goddess Almalexia and House Indoril. Below the city lies its ruined older self and Dwemer
halls. To the east, Tear is the Dres slave-planters' city with the province's great slave market; Narsis is the Hlaalu
foothold on the mainland. Pilgrims, priests and plantation wealth make Deshaan rich, and its slaves make it uneasy.

| Layer | Fields |
| --- | --- |
| L1 | `fungal_plains` [P.L1.fungal_soils]. `obsidian_gorge` (lava canyon) [P.L1.volcanic_shields] |
| L2 | `mild_inland` [P.L2.wet_south] |
| L3 | `lagoon_fens` (Silent Mire), `mournhold_aqueducts`: the Temple controls the capital's water [P.L3.who_holds_water] |
| L4 | `glowing_fungal_plain`, `lagoon_fen`, `lava_canyon`, `paddy_round_tear`, `mournhold_gardens`, `ghost_snake_vale` |
| L5 | `temple_economy` [P.L8.tribunal]. `dres_plantations` [P.L5.saltrice_plantations]. `slave_market` (Tear) [P.L5.slave_trade]. `hlaalu_narsis_trade` |
| L6 | `mournhold_capital` [P.L6.temple_cities]. `tear_plantation_city` [L5.dres_plantations]. `narsis` [P.L6.house_seats] |
| L7 | `priest_nobility` (Indoril) [P.L7.house_order]. `slaves_of_tear` [P.L7.slavery] |
| L8 | `almalexia_mother` [P.L8.tribunal]. `quarantine_rites` (Quarantine Serk) |
| L9 | `indoril_vs_dres` over slavery's place in the Temple [P.L9.slavery_question]. `goddess_unseen`: Almalexia rarely appears [P.L9.tribunal_failing] |
| L10 | `old_mournhold` and `bamz_amschend` (Dwemer under the capital) [P.L10.dwemer_citadels]. `runaway_caves` |
| L11 | `mournhold_rebuilt` on its own ruins |

#### Necrom Coast (proposed `necrom`, today's mainland `molag_amur`)

**World truth.** The south-eastern coast, given to the dead. Necrom is Morrowind's great necropolis city, where families
from across the province carry their dead for burial in terraced tombs, and pilgrims walk processional avenues under
lanterns. Its harbours (Zaappi, Elamasipal) live off the funeral trade. Some say the city's oldest libraries hold
knowledge no mortal should, and that a Daedric prince of forbidden lore watches the scholars who stay too long.

| Layer | Fields |
| --- | --- |
| L1 | `tomb_hills`, `spire_rocks` [P.L1] |
| L2 | `grey_sea_mist` |
| L3 | `harbour_springs`, `funeral_wells` |
| L4 | `tomb_terraces`, `pale_salt_shore`, `glowing_fungus_grove`, `spire_rock_heath`, `cliff_coast` |
| L5 | `funeral_trade` [P.L8.ancestor_worship]. `pilgrim_hostels` |
| L6 | `necrom` [P.L6.temple_cities]. `funeral_harbours` [L5.funeral_trade] |
| L7 | `tomb_keepers`, `mourners` |
| L8 | `city_of_the_dead` [P.L8.ancestor_worship]. `forbidden_lore` (Hermaeus Mora) [P.L8.daedra_veneration] |
| L9 | `burial_rights`: who may be buried here and at what price |
| L10 | `deep_catacombs`, `necromancer_thieves` of bones (Uhaammu and others) |
| L11 | `oldest_burials` |

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
