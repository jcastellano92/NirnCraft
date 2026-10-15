# Solstheim: island plan (ash from the south, ice from the north)

Status: plan, no code changed; all ten sections written (2026-10-13). The design input of roadmap R7's Solstheim slice
(`docs/prompts/R7-morrowind.md`, steps V1-V9; phases SO1-SO8 in section 10.4). Governing rules:
`docs/world/DESIGN-PRINCIPLES.md` (world building is king, the eleven-layer causal sheet, G1-G12, the measured floors
of 4.1-4.9, MS21, MS22). Template: `docs/world/provinces/skyrim.md` (same section structure). Inputs:
`docs/world/SCALE-AND-GEOGRAPHY.md` 5.1 and 5.3, `docs/world/provinces/morrowind.md`, `docs/PARITY.md`,
`docs/MARQUEE.md` and `tools/marquee/marquee.json` (Solstheim block), `docs/LORE-WEB.md` (sol_1-sol_3 and the six
threads), `docs/DUNGEON-AUDIT.md`, `docs/PLAYTEST-3.md` 7, and the facts in `.reference/skyrim_ref` (Dragonborn
markers, `markers.json`, 52 on the island) and `.reference/morrowind_ref`. Legal line: names, positions, counts and lore
facts only; every sentence here is our own and every invented person is new.

**Identity in one line.** Two weathers on one island: ash from Red Mountain falls on the south, snow from the Sea of
Ghosts falls on the north, and a mountain between them keeps the two apart. Never Skyrim's hold (it has no jarl, no
hold, no mead-and-banner politics) and never Vvardenfell (it has no Great House seat, no strider, no canton): a refugee
mine town behind a wall, a people who keep a covenant with the land, a hall that is a whole village, a lone wizard's
tower, and under the mountain a temple that is still listening.

**Why a file of its own.** `parity.py` (2026-10-13, `598858e`) scores the island at a mean of 86, the highest
non-Skyrim region, because it was written as Skyrim's neighbour: SET 103, NPC 80, QST 102, DUN 86, CRE 69, FLO 104,
STY 71, LND 125, LOR 127, MUS 50, ITM 27. Its count is not the problem. The real island is a dense case (52 map
markers, 35 dungeons, 80 named NPCs on 0.98 km2, 3 to 5 times a Skyrim hold) and ours already matches it (50 marked
places, 30 dungeons, 63 quests, 7 power sites). What is missing is **identity and depth**: no sheet, no micro-biome rule,
no named road, two of four settlements without a plan, one music key on a two-culture island, a level band 25 wide, and
a mix of eras in the data (below). `morrowind.md` deliberately holds the island "to the Skyrim plan" and keeps only
its L9 links; this file is that plan.

**Era (owner decision S1, recommended).** The island is shown in **4E 201, the present of `skyrim.md`**: the Red Year
(4E 5) emptied the Company's colony and drove Dunmer refugees here, and the first Dragonborn is stirring again. The
Bloodmoon events (3E 427: the Hunt, the Company's colony and Fort Frostmoth's garrison) are L11 history. Our data mixes
both: `east_empire.json` (4 quests) and the Fort Frostmoth marquee set read as the colony years; `solstheim.json`, the
Raven Rock marquee set and the rumours read as 4E 201. `morrowind.md` assumes the late Third Era for Vvardenfell and
the mainland. The two meet only on the route Raven Rock to Sadrith Mora and on the Company's chain; if WORLD-PLAN fixes
one continental present, only the L9 and L11 rows below and the Company quests change (7.4).

**How to read the numbers.** "Now" is `parity.py` raw data (`tools/qa/out/parity.json`, region
`morrowind/solstheim`, 0.976 km2) or a direct count of `places/*.json` and `quests/*.json`. "Scaled floor" is
`max(absolute floor, round(median density x km2))` of DESIGN-PRINCIPLES 4.3. "Real island" is the measured Solstheim
column of 4.1. "Target" is the larger of the scaled floor and the settlement-tier sums, with the real island as a
cross-check (never below it where the island's systems can carry it).

---

## 1. World model sheet

Data home: `skycraft/tamriel/world/solstheim.json` (MS22): one province block (`so`) and seven area blocks. The areas
are **sheet areas, not `regions.json` regions**: Solstheim stays one region (`morrowind/solstheim`, 0.976 km2; splitting
would churn `regions.png` indices and save data for no gain). Each area is a rule on the macro raster (below), so the
generators can resolve `why` ids to the area under a block. Each field is `{id, text, cites}`; a cite in the same block
is written `L1.x`, a cite to the island block `so.L9.x`, a cite into another sheet `X:sk.L2.x` (Skyrim) or
`X:mw.L9.x` (Morrowind). Every L2-L11 field cites at least one field above it.

| Area (id) | km2 | Rule on the macro raster (blocks) | Seat or anchor | Levels |
| --- | ---: | --- | --- | --- |
| Skaal territory (`skaal`) | 0.19 | x >= 4720, z < -3130 | Skaal Village (4975, -3467) | 18-32 |
| Raven Rock / Redoran colony (`raven_rock`) | 0.13 | x < 4560, z >= -3130 (less the grove and Bloodskal) | Raven Rock (4300, -2900) | 15-25 |
| The Moesring mountains (`moesring`) | 0.17 | -3560 <= z < -3130, x < 4720 (less Bloodskal) | Temple of Miraak (4703, -3290) | 22-35 |
| Isinfier Plains (`isinfier`) | 0.18 | z >= -3130, x >= 4560 | Tel Mithryn (5007, -2722) | 15-30 |
| Felsaad Coast (`felsaad`) | 0.17 | z < -3560, x < 4720 | Castle Karstaag (4339, -3770) | 24-35 |
| The Brodir Grove (`brodir`) | 0.04 | 4440 <= x < 4660, -3130 <= z < -2930 | Brodir Grove (4504, -3042) | 15-20 |
| Bloodskal (`bloodskal`) | 0.10 | x < 4440, -3360 <= z < -3000 | Bloodskal Barrow (4225, -3089) | 18-28 |
| **Island** | **0.976** | 953 land cells of 32 blocks | Raven Rock (capital of the region) | 15-35 |

### 1.1 Island sheet (`so`)

**World truth paragraph.** Solstheim is a cold island off Skyrim's north-east coast that Red Mountain's ash has half
buried. A mountain massif runs through its middle: south of it the ground is a low plain of ash under a falling grey
sky, north of it spruce, glacier and sea ice. The Skaal, hunters and tellers of the north-east, keep a covenant with the
All-Maker and six stones; the south belongs to Dunmer refugees of the Red Year, who took over an East Empire ebony mine
at Raven Rock and wall it against ash storms and the ash spawn that walk out of them. Between them rise a dragon-cult
temple, Dwemer ruins, thane-kings' barrows and a Telvanni tower. The seam is failing, the stones are sickening and
something in the temple has begun to dream again.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology and landform | `moesring_massif`: a central mountain block with one low pass, the island's spine and its only true peak. `north_cliffs`: a ring of sea cliffs, scree and ice-scoured shore along the north and north-west. `glacier_nw` (L1.north_cliffs): a glacier tongue and ice sheet in the north-west. `ash_plain`: the south and south-east are a low plain buried in ash fall. `ebony_seam`: an ebony vein under the south-west coast, nearly worked out. `old_ash_hollows` (L1.ebony_seam): voids in the south-west rock filled with an older ash bed. `stalhrim_basins` (L1.glacier_nw): shallow basins at the glacier's foot where a spring freezes over a vein of humming ice. `dwemer_roots` (L1.moesring_massif): Dwemer works under the massif's flanks and on the east shore, one city sunk in the ice, one observatory on a ridge. `barrow_ridges`: ridges crowned by barrows, and the dragon cult's temple under the massif's south face. `all_maker_stones`: six standing stones of sun, wind, water, earth, beast and tree set round the island |
| L2 Climate | `ash_fall` (L1.ash_plain; X:mw.L2.ash_storms): ash carried from Red Mountain on the south-east wind falls most days on the south, with a heavy storm several times a season. `polar_north` (L1.north_cliffs; X:sk.L2.polar_coast): blizzards, ice fog and a snowline at sea level in the north. `ash_snow_seam` (L1.moesring_massif, L2.ash_fall, L2.polar_north): the massif keeps the two weathers apart; at its southern foot ash and snow meet as grey sleet. `long_dark` (L2.polar_north; X:sk.L2.aurora): short winter days and auroras over the ice. `wind_funnel` (L1.moesring_massif): the pass draws the north wind down onto the plain in gusts |
| L3 Water | `harstrad_river` (L1.moesring_massif): rises on the massif's north face and runs to the north-east shore, the island's one real river. `lake_fjalding` (L1.moesring_massif, L2.polar_north): a lake between Thirsk and the Skaal, frozen half the year. `frozen_north_sea` (L2.polar_north): winter closes the north coast to ships and opens it to sledges. `raven_rock_bay` (L1.north_cliffs): the south-west bay, the one sheltered harbour. `ash_fouled_wells` (L2.ash_fall): wells in the south silt with ash; whoever keeps the cistern keeps the town. `grove_spring` (L1.ash_plain): a clean spring under one stand of trees that ash does not foul. `skaal_meltwater` (L1.glacier_nw): meltwater channels in the north-east that the Skaal hold as sacred |
| L4 Ecology and micro-biomes | `ash_waste` (L2.ash_fall): ash grass, charred stumps, drifts; cliff racers, netch overhead, ash spawn after dark. `pine_north` (L2.polar_north): spruce and pine with snow on the boughs; elk, bears, wolves. `ice_waste` (L1.glacier_nw): frost spiders, trolls, ice wraiths. `riekling_country` (L1.north_cliffs, L2.polar_north): rieklings and their boars in the northern caves and shore camps in numbers found nowhere else. `spore_garden` (L1.ash_plain, L3.grove_spring): fungi on ash-and-loam, cultivated round one tower. `ash_grove` (L3.grove_spring): living ash-trees green in the fall. `sea_herds` (L3.frozen_north_sea): horker herds on the north and east shingle. `old_wood_hollows` (L2.ash_snow_seam): stunted wet wood at the seam where sleet feeds the roots |
| L5 Resources and economy | `ebony_mining` (L1.ebony_seam): the colony's only export, failing. `stalhrim_craft` (L1.stalhrim_basins): harvested and worked only by the Skaal. `hunting_and_hide` (L4.pine_north, L4.sea_herds): pelts, bone, horker oil and meat. `ash_farming` (L4.ash_waste, L3.grove_spring): thin crops on ash, seed stock from the grove. `spore_trade` (L4.spore_garden): fungi, reagents and potions from the tower. `ferry_and_salvage` (L3.raven_rock_bay, L3.frozen_north_sea): the ferry landing, wreck salvage on the shoals. `dwemer_scrap` (L1.dwemer_roots): brass and gears hauled out of the ruins for the Telvanni. `ale_and_trophy` (L5.hunting_and_hide): a hall that brews and keeps tally of kills |
| L6 Settlement pattern | `raven_rock_bulwark` (L5.ebony_mining, L3.raven_rock_bay, L2.ash_fall): a mine town and harbour behind a wall. `skaal_ring` (L5.hunting_and_hide, L5.stalhrim_craft, L3.lake_fjalding): the Skaal's ring of hide houses on the east coast. `thirsk_hall` (L5.ale_and_trophy, L4.pine_north): one great hall under the north-east heights, a whole settlement in one building. `tel_mithryn_tower` (L5.spore_trade, L4.spore_garden): a lone tower on the south-east ash flat. `frostmoth_post` (L3.frozen_north_sea, L5.ferry_and_salvage): a fort on the south shore watching the sea road. `attius_farm` (L5.ash_farming): one farm in the ash. `island_roads` (L1.moesring_massif, L1.ash_plain): a ring road on the plain and one trail over the pass. `busy_and_failing`: the bay and the mine are the busy centre; the mine, the fort and the hall are what is failing |
| L7 Society | `refugee_colony` (L6.raven_rock_bulwark): families who fled the Red Year, ranked by who brought whom, living in lean-tos along the wall. `redoran_duty` (L7.refugee_colony; X:mw.L7.house_order): a council and a guard captain who ration, assign watches and judge. `mine_and_wall` (L5.ebony_mining): miners and guards share bunkhouses and the same debts. `skaal_commons` (L6.skaal_ring): no chief, no coin; an elder, a wind-listener and a smith; knowledge lives in the telling. `hall_ranks` (L6.thirsk_hall): rank is the count of trophies at the long table. `tower_and_servants` (L6.tel_mithryn_tower; X:mw.L7.caste_ladder): a master, apprentices and servants. `outsider_gate` (L6.raven_rock_bulwark): the Skaal and the Ashlander traders do business at the gate, never inside |
| L8 Culture and belief | `all_maker` (L6.skaal_ring, L1.all_maker_stones): the Skaal's faith in a maker whose six stones hold the island's order; the Telling keeps the verse. `ash_graves` (L7.refugee_colony; X:mw.L8.ancestor_worship): the refugees bury in the ash because the family tombs lie across the water. `temple_hall` (L6.raven_rock_bulwark; X:mw.L8.tribunal): a hall of the Temple, the old Tribunal chapel shrunk to one room. `stalhrim_law` (L5.stalhrim_craft): unwrought stalhrim is the All-Maker's memory; to sell it is to sell the dead. `red_moon_hunt` (L4.pine_north, L11.bloodmoon_hunt): an old hunting cult and a fear of a red moon. `ink_dread` (L10.temple_cult): people sleep badly near stones and will not read from a black cover |
| L9 Politics and conflict | `colony_and_skaal` (L5.ebony_mining, L5.stalhrim_craft, L7.redoran_duty, L7.skaal_commons): a failing mine wants timber, game and a new vein; the Skaal want the stones left alone. `company_claim` (L11.company_charter, L5.stalhrim_craft): the Company's lapsed concession over the stalhrim basin is paper nobody has burned. `two_houses` (X:mw.L9.house_politics, L6.tel_mithryn_tower): Redoran duty and Telvanni appetite share a small island. `legion_remnant` (L6.frostmoth_post; X:sk.L9.civil_war): an Imperial outpost cut off by a war across the sea. `first_dragonborn_returns` (L10.temple_cult): the temple's master is awake. `riekling_war` (L4.riekling_country, L6.thirsk_hall): the northern tribes are united under one chief. `reaver_menace` (L5.ferry_and_salvage): pirate crews hold a lodge on the east shore |
| L10 The hidden and the underground | `temple_cult` (L1.barrow_ridges, L11.dragon_cult_age): masked servants and a sunken sanctum under the temple; its walls move. `ash_spawn` (L1.ash_plain, L10.temple_cult): the ash given will and a march. `black_book_altars` (L8.ink_dread, L1.moesring_massif): three altars of a Daedric prince of forbidden knowledge. `frozen_dwemer` (L1.dwemer_roots, L2.polar_north): works held in the ice, an observatory on a ridge, a spire that hums. `riekling_dens` (L4.riekling_country): caves and a mine claim. `reavers` (L9.reaver_menace): a crew in a lodge, another at a shoal. `moon_hunt_dens` (L8.red_moon_hunt): a crag where the pack gathers. `thane_dead` (L1.barrow_ridges, L11.thane_kings): the barrow kings. `karstaag_hall` (L1.glacier_nw, L11.giant_age): a giant's frozen castle. `deep_gallery_sleepers` (L1.old_ash_hollows, L5.ebony_mining): what the miners woke |
| L11 History layers | `dwemer_age`: the Dwemer sank works here before they vanished; Nchardak, Kagrumez and the spire remain. `dragon_cult_age`: the dragon cult raised a temple under the massif; its first Dragonborn took a Daedric prince's book and was cast out. `giant_age`: a frost giant king held the north from a castle of ice. `thane_kings`: Nord thanes settled the south and were buried on the ridges. `skaal_pact`: the Skaal made their covenant and took the east. `bloodmoon_hunt` (3E 427): a red moon, the Hunt and a werewolf plague; Fort Frostmoth garrisoned. `company_charter`: the East Empire Company opened the ebony mine and the stalhrim concession under an Imperial charter. `red_year` (4E 5): Red Mountain erupted; the Company left, the refugees came, the Bulwark was raised. `black_book_return` (4E 201): the first Dragonborn's books surface again |

### 1.2 Skaal territory (`skaal`, 0.19 km2, levels 18-32)

**World truth.** The north-east and east of the island belong to the Skaal, hunters and tellers who keep an old
covenant with the All-Maker: they take what the land lends, work stalhrim and bone and read the six stones as a
living calendar. Their village is a ring of hide-and-bone houses round a fire and a stone circle, with no streets and
no coin. Beside them Nord hunting bands keep Thirsk's single great hall against riekling raids, and reavers have
taken a lodge on the shore. The Skaal are losing: the stones sicken, the young leave for Raven Rock's wages, and the
colony wants their timber.

| Layer | Fields |
| --- | --- |
| L1 | `east_shore` (so.L1.north_cliffs): a low rock shore with shingle coves and a horker island. `forest_uplands` (so.L1.moesring_massif): wooded slopes that shelter the village from the north wind. `king_ridge` (so.L1.barrow_ridges): a ridge with a thane's barrow and a seer's tomb |
| L2 | `snow_forest` (so.L2.polar_north, L1.forest_uplands): deep snow under the pines most of the year. `lake_ice` (so.L2.polar_north): the lake and the shore freeze from the first frost |
| L3 | `lake_fjalding_shore` (so.L3.lake_fjalding): the lake between the hall and the village. `skaal_springs` (so.L3.skaal_meltwater): meltwater channels held as sacred. `ice_shingle` (so.L3.frozen_north_sea, L1.east_shore): a shore the winter turns into a road |
| L4 | `skaal_pine` (so.L4.pine_north, L2.snow_forest): spruce and pine, elk and bears, wolves at the edge. `horker_shingle` (so.L4.sea_herds, L1.east_shore): horkers on the shingle. `riekling_edge` (so.L4.riekling_country): riekling raiders coming from the north |
| L5 | `hide_and_bone` (so.L5.hunting_and_hide, L4.skaal_pine): racks, bone-craft, horker oil. `stalhrim_hands` (so.L5.stalhrim_craft): the smith's work is the village's only trade good. `hall_brewing` (so.L5.ale_and_trophy): Thirsk brews and sells to anyone |
| L6 | `skaal_village` (so.L6.skaal_ring, L3.skaal_springs): a ring on the east shore by the springs. `thirsk_mead_hall` (so.L6.thirsk_hall, L3.lake_fjalding_shore): the hall under the heights. `reaver_lodge` (so.L5.ferry_and_salvage, L1.east_shore): a lodge on the shore. `stone_walk` (so.L1.all_maker_stones): the beast and wind stones along the village's paths |
| L7 | `elder_and_listener` (so.L7.skaal_commons): the elder keeps the stories, the listener reads the wind. `leaving_young` (so.L7.skaal_commons, so.L6.raven_rock_bulwark): the young go south for wages. `hall_tally` (so.L7.hall_ranks): the table's order |
| L8 | `telling_at_the_fire` (so.L8.all_maker, L7.elder_and_listener): a verse is told at the fire or it is lost. `stone_calendar` (so.L1.all_maker_stones, so.L8.all_maker): each stone marks a duty. `trophy_oath` (so.L8.red_moon_hunt, L7.hall_tally): the hall swears on its kills |
| L9 | `stones_poisoned` (so.L9.first_dragonborn_returns, so.L1.all_maker_stones): the temple's master is bending the stones. `timber_demand` (so.L9.colony_and_skaal): Raven Rock's council wants a felling ground. `riekling_raids` (so.L9.riekling_war, L4.riekling_edge): spears at Thirsk's gate. `reaver_toll` (so.L9.reaver_menace, L6.reaver_lodge): sledges taken on the shore |
| L10 | `king_barrow` (so.L10.thane_dead, L1.king_ridge): a king's feast-barrow. `seer_tomb` (so.L10.thane_dead): a seer's tomb with a ritual of proof. `riekling_den` (so.L10.riekling_dens): a den at the north edge. `stone_blight` (so.L10.temple_cult, so.L1.all_maker_stones): sleepers in the stone circles |
| L11 | `skaal_pact` (so.L11.skaal_pact). `old_king_feast` (so.L11.thane_kings). `hunt_wars` (so.L11.bloodmoon_hunt): the Skaal's wars against the Hunt |

### 1.3 Raven Rock and the Redoran colony (`raven_rock`, 0.13 km2, levels 15-25)

**World truth.** Raven Rock is a refugee town behind a bulwark. Dunmer who fled Red Mountain's eruption took over the
East Empire Company's failing ebony mine on the south-west bay and walled it against ash storms and the ash spawn
that walk out of them. A Redoran council and a guard captain ration the grain, the shaft is nearly worked out, a hall of
the Temple keeps the faith, and the dead go into the ash because the family tombs are across the sea. The harbour is
the island's only ferry landing and the colony's only link to the world.

| Layer | Fields |
| --- | --- |
| L1 | `bay_headland` (so.L1.north_cliffs): a rock headland that shelters the bay. `mine_shaft` (so.L1.ebony_seam, so.L1.old_ash_hollows): a headframe over a shaft whose deep gallery meets old ash. `ash_shelf` (so.L1.ash_plain): the ground east of the wall is a low ash shelf |
| L2 | `windward_wall` (so.L2.ash_fall, L1.ash_shelf): the wall's east face takes the ash and is swept at dawn. `storm_shutters` (so.L2.ash_fall): shutters and sealed doors in the storm season |
| L3 | `bay_harbour` (so.L3.raven_rock_bay, L1.bay_headland): the wharf and the ferry. `cistern` (so.L3.ash_fouled_wells): the one covered cistern under the council's seal. `wharf_dues` (L3.bay_harbour): the council charges the landing |
| L4 | `spoil_barrens` (so.L4.ash_waste, L1.mine_shaft): spoil heaps where nothing grows. `ash_shingle` (so.L4.ash_waste, L3.bay_harbour): a black shore. `farm_ash` (so.L4.ash_waste, so.L3.grove_spring): fields with seed stock from the grove |
| L5 | `ebony_shaft` (so.L5.ebony_mining, L1.mine_shaft): the mine, the sorting shed and the pay line. `wharf_trade` (so.L5.ferry_and_salvage, L3.bay_harbour): the ferry, customs and the only market. `attius_farm` (so.L5.ash_farming, L4.farm_ash): a farm of ash-fed fields |
| L6 | `raven_rock_town` (so.L6.raven_rock_bulwark, L5.ebony_shaft, L3.bay_harbour): the walled town and its wharf. `earth_stone_hill` (so.L1.all_maker_stones): the earth stone a short walk to the north-west. `kolbjorn_ridge` (so.L1.barrow_ridges): a barrow the miners dig for stone |
| L7 | `council_and_captain` (so.L7.redoran_duty): rations, watches, judgement. `lean_to_row` (so.L7.refugee_colony, L6.raven_rock_town): the newest arrivals under the wall. `miners_debt` (so.L7.mine_and_wall, L5.ebony_shaft): wages in ration chits. `gate_trade` (so.L7.outsider_gate): the Skaal and an Ashlander trader at the gate |
| L8 | `temple_hall` (so.L8.temple_hall): a single room of the Temple. `ash_graves` (so.L8.ash_graves, L7.lean_to_row): rows of cairns on the shelf. `ancestor_shrines` (so.L8.ash_graves): a shrine in every house |
| L9 | `mine_or_farm` (so.L9.colony_and_skaal, L5.ebony_shaft): seal the failing shaft or dig on. `company_ledger` (so.L9.company_claim, L5.wharf_trade): the Company's last clerk and its papers. `ash_siege` (so.L9.first_dragonborn_returns, so.L10.ash_spawn): nightly marches on the wall |
| L10 | `deep_gallery` (so.L10.deep_gallery_sleepers, L1.mine_shaft): the gallery that broke into old ash. `ash_spawn_wall` (so.L10.ash_spawn, L2.windward_wall): the march on the windward face. `sleepwalkers` (so.L10.temple_cult, L6.earth_stone_hill): townsfolk walking to the earth stone in the night. `smuggled_ebony` (so.L5.ebony_mining, so.L5.ferry_and_salvage): ore sold north |
| L11 | `company_mine` (so.L11.company_charter). `red_year_landing` (so.L11.red_year): the first boats and the wall raised that winter. `colony_graves` (so.L11.red_year, L8.ash_graves) |

### 1.4 The Moesring mountains (`moesring`, 0.17 km2, levels 22-35)

**World truth.** The Moesring massif is the island's spine and its dividing line: ash falls on its southern foot, snow
on its north, and a belt of grey sleet marks where they meet. It carries the island's oldest layers: a dragon-cult
temple whose stair climbs from an altar of the old kings to a sunken sanctum, barrows of thane-kings, Dwemer works and
an observatory on a ridge. Its pass is the only low way between north and south, and everything the colony and the Skaal
want from each other must cross it.

| Layer | Fields |
| --- | --- |
| L1 | `moesring_crown` (so.L1.moesring_massif): the island's one true peak. `pass_notch` (so.L1.moesring_massif): the low pass, the only through way. `temple_face` (so.L1.barrow_ridges): the south face where the temple is cut. `star_ridge` (so.L1.dwemer_roots): an observatory ridge in the east of the massif |
| L2 | `crown_blizzard` (so.L2.polar_north, L1.moesring_crown): whiteout above the snowline. `seam_sleet` (so.L2.ash_snow_seam): grey sleet on the south foot. `pass_gale` (so.L2.wind_funnel, L1.pass_notch): the pass blows hard. `clear_night` (so.L2.long_dark, L1.star_ridge): the ridge's sky is the clearest on the island |
| L3 | `harstrad_head` (so.L3.harstrad_river, L1.moesring_crown): the river's source at the north face. `snow_melt_burns` (so.L2.ash_snow_seam, L1.temple_face): brown meltwater with ash in it. `lake_source` (so.L3.lake_fjalding): the lake's feeder streams |
| L4 | `snowfield` (so.L4.ice_waste, L2.crown_blizzard): bears, wolves, frost spiders. `pass_scree` (L1.pass_notch, L2.pass_gale): bare rock. `temple_moss` (so.L4.old_wood_hollows, L2.seam_sleet): dark moss on the temple's black stone |
| L5 | `quarry_and_slab` (so.L1.barrow_ridges, so.L5.ebony_mining): stone for walls dug from barrow ridges. `scrap_runs` (so.L5.dwemer_scrap, L1.star_ridge): brass hauled to the Telvanni. `pilgrim_trade` (so.L8.all_maker): a few Skaal and colony pilgrims cross at the altar |
| L6 | `pass_road` (so.L6.island_roads, L1.pass_notch): the one trail and the only lodge. `trader_shack` (so.L5.scrap_runs, L1.pass_notch): a shack at the pass. `altar_of_the_kings` (so.L1.barrow_ridges): a ring of carved posts at the foot of the temple's stair |
| L7 | `pass_toll_custom` (so.L7.outsider_gate, L6.pass_road): whoever holds the pass taxes it. `trader_who_knows` (L6.trader_shack): a shack-keeper who sells news |
| L8 | `king_altar_dawn` (L6.altar_of_the_kings, so.L11.thane_kings): dawn through the posts marks the day. `fear_of_the_stair` (so.L8.ink_dread): the stair is not climbed at night |
| L9 | `who_holds_the_pass` (so.L9.colony_and_skaal, L6.pass_road). `temple_awake` (so.L9.first_dragonborn_returns). `scholar_race` (so.L9.two_houses, L5.scrap_runs): Telvanni diggers and the colony's guard |
| L10 | `temple_of_miraak` (so.L10.temple_cult, L1.temple_face): the sunken sanctum, walls like ink. `black_stone_ribs` (so.L10.temple_cult): the grand chambers. `observatory` (so.L10.frozen_dwemer, L1.star_ridge): star-maps and a long gantry. `hrothmund_barrow` (so.L10.thane_dead): a barrow with a key. `den_of_the_pack` (so.L10.moon_hunt_dens): a crag where the pack gathers on Loredas |
| L11 | `dragon_cult_age` (so.L11.dragon_cult_age): the temple. `first_dragonborn` (so.L11.black_book_return). `thane_hrothmund` (so.L11.thane_kings). `star_makers` (so.L11.dwemer_age) |

### 1.5 Isinfier Plains (`isinfier`, 0.18 km2, levels 15-30)

**World truth.** South and east of the mountain lies a plain of ash: drifts, charred stumps, cliff racers and netch
overhead and ash spawn after dark. A Telvanni master keeps a lone mushroom tower and a spore garden on its south-east
point; an Imperial fort and a ruined mage-citadel watch the sea road; a Dwemer city half sunk in ice lies on the east
shore and a trader's shack sells to anyone. The farms that survive here are measured in fields, and grow in ash that
nothing should grow in.

| Layer | Fields |
| --- | --- |
| L1 | `ash_shelf_plain` (so.L1.ash_plain): flat ash. `dune_ridges` (so.L1.ash_plain, so.L2.wind_funnel): wind-built ash ridges. `east_shore_heights` (so.L1.dwemer_roots): the heights over the frozen city. `sea_shore` (so.L1.north_cliffs): a low south shore with a wrecked strand |
| L2 | `ash_storms` (so.L2.ash_fall, L1.ash_shelf_plain): the heaviest falls on the island. `dry_clear` (so.L2.ash_fall): ash-days are dry and bright between storms. `east_fog` (L1.east_shore_heights, so.L3.frozen_north_sea): ice fog on the east shore |
| L3 | `plain_wells` (so.L3.ash_fouled_wells): wells silted by ash. `cistern_at_the_tower` (so.L3.ash_fouled_wells): a tower drinks from deep cisterns. `spire_cistern` (so.L1.dwemer_roots): the spire's old pipes carry clean water to a vent |
| L4 | `ash_dune_scrub` (so.L4.ash_waste, L1.dune_ridges): scathecraw-like scrub and ash grass. `spore_flat` (so.L4.spore_garden, L3.cistern_at_the_tower): the tower's garden. `racer_hollows` (so.L4.ash_waste, L2.ash_storms): cliff racers nest in the hollows |
| L5 | `telvanni_garden` (so.L5.spore_trade, L4.spore_flat). `scrap_hauling` (so.L5.dwemer_scrap, L1.east_shore_heights). `ash_farms` (so.L5.ash_farming, L4.ash_dune_scrub). `trader_post` (so.L5.ferry_and_salvage): a shack where roads cross |
| L6 | `tel_mithryn` (so.L6.tel_mithryn_tower, L5.telvanni_garden): the tower on the south-east point. `fort_frostmoth` (so.L6.frostmoth_post, L1.sea_shore): a fort on the south shore. `ashfallow_citadel` (so.L1.ash_plain, so.L11.company_charter): a ruined mage-fort on the plain. `aidanat_camp` (so.L7.outsider_gate): a trader camp that moves with the season |
| L7 | `tower_house` (so.L7.tower_and_servants, L6.tel_mithryn): a master, his apprentices, his servants. `garrison` (so.L7.mine_and_wall, L6.fort_frostmoth): too few soldiers for the wall. `farm_family` (so.L7.refugee_colony, L5.ash_farms): a refugee family on a farm with a strange field |
| L8 | `ward_ring` (so.L8.temple_hall, L6.ashfallow_citadel): the citadel's ward-ring still hums. `unquiet_garrison` (so.L8.red_moon_hunt, L6.fort_frostmoth): the grave of a tribune |
| L9 | `telvanni_appetite` (so.L9.two_houses, L6.tel_mithryn). `cut_off_fort` (so.L9.legion_remnant, L6.fort_frostmoth): winter ice closes the supply road. `deserters` (so.L9.legion_remnant, L7.garrison): men leaving the citadel's watch |
| L10 | `nchardak_ice_city` (so.L10.frozen_dwemer, L1.east_shore_heights): a Dwemer city half in the ice. `spire_that_hums` (so.L10.frozen_dwemer, L3.spire_cistern): a resonance spire south of the tower. `ash_spawn_march` (so.L10.ash_spawn, L2.ash_storms). `black_book_east` (so.L10.black_book_altars, L1.east_shore_heights): an altar on the east heights. `scarecrow_field` (so.L10.ash_spawn, L5.ash_farms): a field that moves |
| L11 | `company_fort` (so.L11.company_charter, so.L11.bloodmoon_hunt): the fort's garrison years. `mage_citadel_age` (so.L11.company_charter): the citadel was an Imperial mage-fort of the charter years. `frozen_city` (so.L11.dwemer_age) |

### 1.6 Felsaad Coast (`felsaad`, 0.17 km2, levels 24-35)

**World truth.** The north and north-west coast is cliffs, ice fog and a glacier tongue, the cold end of the island.
Rieklings hold its caves and shore camps in numbers found nowhere else, riding boars and throwing spears, and a mine
is theirs by raid. Stalhrim forms at the glacier's foot where a spring freezes over its vein, and a giant's frozen
castle stands above the sea. Ships rarely land and the winter closes the coast entirely; the only landing is a
half-wrecked jetty.

| Layer | Fields |
| --- | --- |
| L1 | `mortrag_glacier` (so.L1.glacier_nw): the north-west ice. `cliff_wall` (so.L1.north_cliffs): sea cliffs with caves at the foot. `stalhrim_basin` (so.L1.stalhrim_basins): the humming basin. `karstaag_stack` (so.L1.glacier_nw): a rock stack carved into a castle. `north_peaks` (so.L1.moesring_massif): two high shoulders above the coast |
| L2 | `ice_fog` (so.L2.polar_north, L1.cliff_wall). `blizzard_coast` (so.L2.polar_north). `aurora_ice` (so.L2.long_dark): auroras over the glacier |
| L3 | `harstrad_mouth` (so.L3.harstrad_river): the river reaches the coast here. `frozen_landing` (so.L3.frozen_north_sea, L1.cliff_wall): winter closes the jetty. `glacier_melt` (L1.mortrag_glacier, so.L3.skaal_meltwater) |
| L4 | `ice_field` (so.L4.ice_waste, L1.mortrag_glacier). `shore_cave_range` (so.L4.riekling_country, L1.cliff_wall): caves at the cliff foot. `sea_cliff_birds` (L1.cliff_wall). `horker_shore` (so.L4.sea_herds). `mortrag_snow_wood` (so.L4.pine_north, L2.blizzard_coast) |
| L5 | `stalhrim_harvest` (so.L5.stalhrim_craft, L1.stalhrim_basin): the Skaal's, and only theirs. `riekling_claim` (so.L10.riekling_dens): a mine they took by raid. `landing_trade` (so.L5.ferry_and_salvage, L3.frozen_landing): reavers and smugglers. `horker_oil` (so.L5.hunting_and_hide, L4.horker_shore) |
| L6 | `northshore_landing` (so.L5.ferry_and_salvage, L3.frozen_landing): a jetty and one hut. `harstrad_ford` (so.L3.harstrad_river): the ford on the shore trail. `karstaag_castle` (so.L10.karstaag_hall, L1.karstaag_stack): the giant's castle. `watcher_rock` (so.L1.north_cliffs, L2.ice_fog): a rock where a dragon perches |
| L7 | `landing_outlaws` (so.L7.outsider_gate, L6.northshore_landing): nobody's law. `riekling_tribes` (so.L4.riekling_country): bands under chiefs. `prospectors` (so.L5.stalhrim_craft): lone men who come for the ice |
| L8 | `stalhrim_shrine` (so.L8.stalhrim_law, L1.stalhrim_basin): the basin is a shrine. `giant_dead` (so.L11.giant_age, L1.karstaag_stack): the king's feast-hall under ice |
| L9 | `stalhrim_claim` (so.L9.company_claim, so.L9.colony_and_skaal, L5.stalhrim_harvest). `riekling_chief` (so.L9.riekling_war). `reaver_landings` (so.L9.reaver_menace, L6.northshore_landing) |
| L10 | `karstaag_hall` (so.L10.karstaag_hall): frozen warriors and a throne of ice. `glacier_cave` (L4.ice_field): a cave of trolls and a prospector's last camp. `dragon_watch` (L6.watcher_rock, so.L10.thane_dead): a dragon's perch and a word. `barrow_in_the_ice` (so.L10.thane_dead): a barrow with a word wall. `hunt_totems` (so.L8.red_moon_hunt, L4.mortrag_snow_wood): a totem hung upside down |
| L11 | `giant_age` (so.L11.giant_age). `company_stalhrim_survey` (so.L11.company_charter). `red_moon_hunts` (so.L11.bloodmoon_hunt) |

### 1.7 The Brodir Grove (`brodir`, 0.04 km2, levels 15-20)

**World truth.** On the ash plain's western edge a ring of ash-trees stands green while the ground round it is grey: a
spring beneath them the fall does not foul. It is the island's last stand of living broadleaf, the source of the seed
stock the colony's farms depend on, and a place the Dunmer refugees hold as ground where the dead rest. A keeper who
answers every question with a question tends it; a mage's tower on the ridge above watches without help.

| Layer | Fields |
| --- | --- |
| L1 | `spring_hollow` (so.L1.ash_plain): a shallow hollow over a spring. `watch_ridge` (so.L1.barrow_ridges): a ridge above the hollow |
| L2 | `sheltered_hollow` (so.L2.ash_fall, L1.spring_hollow): the hollow keeps the fall off. `still_air` (L1.spring_hollow) |
| L3 | `grove_spring` (so.L3.grove_spring): the clean spring |
| L4 | `ash_trees_green` (so.L4.ash_grove, L3.grove_spring): living broadleaf, moss. `seed_beds` (so.L4.ash_grove): beds of grain and herb |
| L5 | `seed_stock` (so.L5.ash_farming, L4.seed_beds): the island's seed bank. `shade_herbs` (L4.ash_trees_green): reagents the colony cannot grow |
| L6 | `keeper_hut` (L5.seed_stock): one hut. `colony_path` (so.L6.island_roads): a path from the Raven Rock road. `highpoint_tower` (L1.watch_ridge): a tower above |
| L7 | `keeper` (L6.keeper_hut): answers in questions. `seed_rights` (so.L7.redoran_duty, L5.seed_stock): the council claims the stock |
| L8 | `resting_ground` (so.L8.ash_graves, L4.ash_trees_green): the dead are said to lie easier here |
| L9 | `who_owns_the_seed` (so.L9.colony_and_skaal, L5.seed_stock). `tower_interest` (so.L9.two_houses, L6.highpoint_tower) |
| L10 | `tower_experiments` (L6.highpoint_tower, so.L10.ash_spawn): a mage who tried to make the ash grow and lost the argument. `seed_theft` (L5.seed_stock, L9.who_owns_the_seed): the grove's one hidden thing is a locked bed of seed |
| L11 | `first_planting` (so.L11.red_year): the refugees planted it the first spring |

### 1.8 Bloodskal (`bloodskal`, 0.10 km2, levels 18-28)

**World truth.** The western ridge between the colony and the mountain is barrow country: Bloodskal Barrow, the grave of
a hero whose blade is the island's best-known relic, a hunter's lodge where footprints go in and none come out, a
flooded and ash-choked mine, and a peak with a bear that does not belong. It is where the colony's quarrymen go to
dig and where the old Nord settlers lie. Everything here is a question of who owns a grave: the colony wants the
barrow's stone, the Skaal want the dead undisturbed, and the dead have views of their own.

| Layer | Fields |
| --- | --- |
| L1 | `barrow_ridge` (so.L1.barrow_ridges): the ridge. `hvitkald_cone` (so.L1.moesring_massif): a snow-crowned peak. `old_mine_cut` (so.L1.ebony_seam): a mine cut into the west flank |
| L2 | `seam_edge` (so.L2.ash_snow_seam): the sleet belt crosses the ridge. `peak_storms` (so.L2.polar_north, L1.hvitkald_cone) |
| L3 | `west_runoff` (so.L3.ash_fouled_wells, L1.barrow_ridge): the barrow floods in thaw. `mine_sump` (L1.old_mine_cut): a flooded gallery |
| L4 | `cairn_heath` (so.L4.old_wood_hollows, L2.seam_edge): heath and cairns. `peak_snow` (so.L4.ice_waste, L1.hvitkald_cone). `white_bear_range` (so.L4.pine_north) |
| L5 | `quarry_claim` (so.L5.ebony_mining, L1.barrow_ridge): barrow stone for the wall. `mine_remnant` (L1.old_mine_cut): a played-out cut. `lodge_hunting` (so.L5.hunting_and_hide) |
| L6 | `bloodskal_barrow` (so.L1.barrow_ridges, L5.quarry_claim). `abandoned_lodge` (L5.lodge_hunting): a hunter's lodge. `damphall_mine` (L1.old_mine_cut). `west_water_stone` (so.L1.all_maker_stones): the water stone's low hill |
| L7 | `quarrymen` (so.L7.mine_and_wall, L5.quarry_claim). `hunters_who_vanish` (L6.abandoned_lodge) |
| L8 | `hero_grave` (so.L11.thane_kings, L6.bloodskal_barrow): the champion's blade. `ash_urns` (so.L8.ash_graves, L5.quarry_claim): urns carried from Raven Rock |
| L9 | `dig_or_leave` (so.L9.colony_and_skaal, L5.quarry_claim). `blade_claim` (so.L9.company_claim): a relic with three claimants |
| L10 | `barrow_dead` (so.L10.thane_dead). `ash_in_the_mine` (so.L10.deep_gallery_sleepers, L1.old_mine_cut). `black_book_filament` (so.L10.black_book_altars, L1.barrow_ridge). `white_bear` (L4.white_bear_range): a bear that walks in snow |
| L11 | `bloodskal_hero` (so.L11.thane_kings). `old_settlers` (so.L11.thane_kings): the first Nords on the south |

---

## 2. Micro-biomes

Solstheim's 0.976 km2 island is split along the Moesring massif (`so.L1.moesring_massif`, `so.L2.ash_snow_seam`):
south of the ridge, ash carried on the south-east wind from Red Mountain governs soil and flora; north of it, polar
gales off the Sea of Ghosts govern ice and pine. Each of the 9 micro-biomes cites an L1-L3 cause from section 1.

| Area | Micro-biome (key) | Rule (MS21) | Cause | Flora and ground | Weather | Creatures | Work |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `raven_rock`, `isinfier` | `ash_waste` | z >= -3130, elevation < 85, away from springs | `so.L2.ash_fall`, `so.L1.ash_plain` | grey ash turf, charred trunks, scathecraw, ash yam | falling ash, periodic ash storm | ash hopper, netch, ash spawn (night) | ash-farming, salvage |
| `isinfier` | `spore_garden` | 220-block ring round Tel Mithryn (5007, -2722) | `so.L3.grove_spring`, `isinfier.L3.cistern_at_the_tower` | giant mushroom caps, fungal loam, Emperor parasol moss | dry ash-haze | netch calves, blight moths | Telvanni alchemy and spore trade |
| `brodir` | `ash_grove` | 4440 <= x < 4660, -3130 <= z < -2930 | `so.L3.grove_spring`, `brodir.L2.sheltered_hollow` | living green broadleaf ash-trees, moss, clear spring | calm, sheltered from ash fall | deer, songbirds, wisps at dusk | colony seed bank and resting cairns |
| `moesring`, `bloodskal` | `sleet_seam` | -3320 <= z < -3100 along southern foot of Moesring | `so.L2.ash_snow_seam`, `moesring.L3.snow_melt_burns` | wet dark heath, stunted birch, grey sleet-crust | sleet and gusting wind | wolves, draugr patrols near barrows | barrow stone quarrying |
| `skaal` | `skaal_pine` | x >= 4680, -3600 <= z < -3130, elevation 65-110 | `so.L2.polar_north`, `skaal.L1.forest_uplands` | snow-laden spruce and pine, lichen, deep drift | snow flurries, crisp clear nights | elk, snow bears, wolves | Skaal hunting, bone and hide craft |
| `skaal`, `felsaad` | `frozen_lake_and_shingle` | shore cells and Lake Fjalding basin | `so.L3.lake_fjalding`, `so.L3.frozen_north_sea` | shore ice, shingle, horker haul-outs | ice fog, coastal gale | horkers, mudcrabs, rieklings | horker oil, shore sledging |
| `moesring`, `felsaad` | `alpine_crown` | elevation >= 110 on Moesring and north peaks | `so.L1.moesring_massif`, `moesring.L2.crown_blizzard` | bare rock, rime ice, wind-scoured scree | whiteout blizzard, pass gale | frost trolls, ice wraiths, dragons | Dwemer scrap runs, pass watch |
| `felsaad` | `glacier_and_stalhrim` | x < 4560, z < -3560 (Mortrag Glacier and basins) | `so.L1.glacier_nw`, `so.L1.stalhrim_basins` | blue glacial ice, crevasses, humming stalhrim veins | aurora at night, freezing fog | rieklings on boar-back, frost giants | sacred stalhrim harvest |
| `raven_rock` | `spoil_and_bulwark` | 180-block arc east of Raven Rock wall | `raven_rock.L1.mine_shaft`, `raven_rock.L2.windward_wall` | black mine spoil, timber palisades, ash cairns | windward ash drift | ash spawn marches | wall watch, ebony sorting |

---

## 3. Map changes and geography

### 3.1 Relief and hydrography (`SCALE-AND-GEOGRAPHY.md` 5.1, 7)

| Measure | Now | Target | How |
| --- | --- | --- | --- |
| Height p50 | 24 | 32 | raise the Moesring apron and Bloodskal barrow ridges above the southern ash shelf |
| Height p90 | 78 | 125 | Moesring crown (`moesring_crown`) to 155, Mortrag Glacier (`mortrag_glacier`) to 130, Hvitkald Peak to 120 |
| Ruggedness | 2.6 | 4.2 | north-west sea cliffs (`cliff_wall`), Harstrad gorge, barrow ridge scarps |
| Signature silhouette | low ridge | **central white peak dividing grey ash south from blue-white glacier north** | Moesring massif + Temple of Miraak stair + Tel Mithryn mushroom silhouette on the south-east point |

* **Hydrology:** `harstrad_river` from the Moesring north face to the north-east shore (`so.L3.harstrad_river`); `lake_fjalding` (`4830, -3520`) frozen along its northern reed-bed with a clear meltwater outlet (`skaal_springs`) past Skaal Village; the clean spring at `brodir_grove` (`4504, -3042`).

### 3.2 Missing canonical places to add or verify (`SCALE-AND-GEOGRAPHY.md` 5.1, 8.10)

All 52 Dragonborn markers and key Bloodmoon landmarks are cross-checked against `places/morrowind.json`:
* Verify/add: `thirsk` (`4885, -3490`, hall settlement), `castle_karstaag` (`4339, -3770`, marquee ice fortress), `lake_fjalding` (`4830, -3520`, landmark), `mortrag_glacier` (`4410, -3710`, landmark), `hrothmunds_bane` (`4610, -3380`, barrow), `thormoors_watch` (`4520, -3590`, tower/camp), `aidanat` (`4890, -2960`, seasonal Ashlander/hunter camp).
* Standing stones (the six All-Maker Stones, `so.L1.all_maker_stones`): `earth_stone` (`4260, -2860`), `water_stone` (`4195, -3290`), `wind_stone` (`4910, -3360`), `beast_stone` (`4890, -3395`), `sun_stone` (`4820, -2990`), `tree_stone` (`4715, -3275`, inside the Temple precinct).

### 3.3 XL silhouettes

| Area | Target XL | Silhouettes |
| --- | --- | --- |
| `raven_rock` | 1 | The Bulwark wall and the Coldcinder headframe over the bay |
| `moesring` | 2 | Temple of Miraak's rib-spires and black stair; Fahlbtharz/Kagrumez Dwemer observatory |
| `isinfier` | 2 | Tel Mithryn's giant fungal tower; Nchardak's half-submerged brass domes on the east coast |
| `felsaad` | 1 | Castle Karstaag's jagged glacial spires above the northern ice |

---

## 4. Roads, travel network, fast travel

### 4.1 Named roads (`named_roads.json`)

| id | Class | Route | Notes |
| --- | --- | --- | --- |
| `bulwark_to_mithryn_road` | road | `raven_rock` > `attius_farm` > `fort_frostmoth` > `ashfallow_citadel` > `tel_mithryn` | The southern ash-plain road (`so.L6.island_roads`), marked by Redoran ash-cairns and lantern posts |
| `moesring_pass_trail` | trail | `raven_rock` > `brodir_grove` > `altar_of_thrond` > `temple_of_miraak` > `thirsk` > `skaal_village` | The only low pass across the massif (`moesring.L6.pass_road`) |
| `east_shore_sledge_way` | trail | `tel_mithryn` > `nchardak` > `skaal_village` > `northshore_landing` | Shingle and winter-ice track along the east and north coast (`skaal.L3.ice_shingle`) |

### 4.2 Sea routes and travel modes (`MS11`)

| Mode | Nodes | Identity rule | Status |
| --- | --- | --- | --- |
| **Ferry (`Northern Maiden`)** | `raven_rock` <-> `windhelm`, `solitude`, `sadrith_mora`, `blacklight` | Paid passage at Raven Rock wharf (`raven_rock.L3.bay_harbour`); non-straight sea corridors around the north capes | EXISTS (fix straight-line fallback in R1b) |
| **Smuggler / Reaver skiff** | `northshore_landing` <-> `dawnstar`, `dagon_fel` | High-risk charter when winter ice or bounty closes Raven Rock | NEW (`travel/morrowind.json`) |
| **All-Maker Stones (wilderness nodes)** | The 6 All-Maker Stones (`earth`, `water`, `wind`, `beast`, `sun`, `tree`) | Cleansed stones act as free wilderness fast-travel anchors; corrupted stones do not | NEW (`MS11` rule) |

---

## 5. Settlements

### 5.1 Tier list

| Settlement | Area | Tier | Kit / Look | Named NPCs (now -> target) | Interiors (now -> target) | Role in `so.L6` |
| --- | --- | --- | --- | ---: | ---: | --- |
| `raven_rock` | `raven_rock` | City (seat) | `redoran_shell` + `bulwark_ash` | 19 -> 28 | 11 -> 15 | Walled Redoran refugee port and ebony mine |
| `skaal_village` | `skaal` | Town | `skaal_hide_bone` | 9 -> 14 | 6 -> 9 | Ring of hide-and-bone longhouses around the Greathall |
| `tel_mithryn` | `isinfier` | Village (wizard seat) | `telvanni_fungal` | 5 -> 8 | 4 -> 5 | Grown mushroom tower, steward's hall, apothecary, spore sheds |
| `thirsk` | `skaal` | Hamlet (mead hall) | `nord_timber_hall` | 4 -> 7 | 1 -> 2 | Single great hunting hall by Lake Fjalding |

### 5.2 Settlement plan briefs

* **Raven Rock (`raven_rock`):** Walled crescent facing the bay (`raven_rock.L2.windward_wall`). Districts: (1) Wharf and customs pier; (2) Market square and the Retching Netch inn; (3) Morvayn Manor, Redoran guardhouse, and the Temple hall (`raven_rock.L8.temple_hall`); (4) Coldcinder Mine headframe, sorting shed, and the lean-to row where late refugees live (`raven_rock.L7.lean_to_row`).
* **Skaal Village (`skaal_village`):** Unwalled ring of curved timber-and-horker-hide houses around a central fire and the Greathall (`skaal.L6.skaal_village`). No shops with coin counters—trade is by barter with the smith (`stalhrim_hands`) and healer.
* **Tel Mithryn (`tel_mithryn`):** One central grown Telvanni tower with a float-shaft, surrounded by three smaller root-pods (steward's kitchen, apprentice laboratory, silt-ash staff quarters) and the spore garden (`isinfier.L4.spore_flat`).
* **Thirsk Mead Hall (`thirsk`):** One long Nord hall with trophy racks (`skaal.L7.hall_tally`), a mead-brewing annex, and a boar-stave palisade scarred by riekling spears.

---

## 6. Dungeons, underground, landmarks

### 6.1 Underground layers (`so.L10`)

| Class | Count | Signature places | Sheet cause |
| --- | ---: | --- | --- |
| Dragon-cult & Thane Barrows | 9 | `temple_of_miraak` (S-marquee), `bloodskal_barrow` (A-marquee), `kolbjorn_barrow`, `gyldenhul_barrow`, `vahloks_tomb`, `hrothmunds_barrow` | `so.L10.temple_cult`, `so.L10.thane_dead` |
| Frozen & Sunken Dwemer | 4 | `nchardak` (S-marquee), `kagrumez`, `fahlbtharz`, `white_ridge_barrow` (hybrid) | `so.L10.frozen_dwemer` |
| Colony Mines & Forts | 4 | `raven_rock_mine` (Coldcinder deep gallery), `damphall_mine`, `fort_frostmoth`, `ashfallow_citadel` | `so.L10.deep_gallery_sleepers`, `isinfier.L11.company_fort` |
| Riekling & Glacial Dens | 7 | `castle_karstaag` (A-marquee), `benkongerike`, `frossel`, `Bristleback Cave`, `Glacier Cave`, `giant_tooth_hollow` | `so.L10.karstaag_hall`, `so.L10.riekling_dens` |
| Reaver & Werebear Holds | 6 | `wreck_of_the_strident_squall`, `horker_island_camp`, `frostmoon_crag`, `highpoint_tower`, `brodir_hollow` | `so.L10.reavers`, `so.L10.moon_hunt_dens` |

* **Unmarked sites (>= 2):** The prospector's frozen camp under Mortrag (`felsaad.L10.glacier_cave`) and the drowned smuggler cache in the Coldcinder sea-adit (`raven_rock.L10.smuggled_ebony`).

---

## 7. Quests

### 7.1 Island story arc (9 quests): **The First Voice (`mq_solstheim`)**

Sleepers in Raven Rock and the Skaal walk by night to the six All-Maker Stones (`so.L9.first_dragonborn_returns`).
* **Act I — Ash and Ink (3):** Arrival at Raven Rock wharf; investigating the night-walkers at the Earth Stone; entering the Temple of Miraak's outer sanctum and reading the first Black Book (`so.L10.black_book_altars`).
* **Act II — The Six Stones and the Sunken City (3):** The Skaal Elder's covenant (`skaal.L8.telling_at_the_fire`); cleansing the Wind, Beast, Water, and Sun Stones; descending into `nchardak` with Tel Mithryn's master to recover the submerged lexicon.
* **Act III — The Summit of Apocrypha (3):** Returning to the deep sanctum of the Temple; breaking the stone-blight; confronting the First Dragonborn's remnant. **Consequence:** Night-walking ends, the cleansed stones grant their six powers, and Skaal/Redoran envoys open a timber-and-grain compact (`so.L9.colony_and_skaal`).

### 7.2 Area and faction chains

| Chain | Area | Quests | Summary & consequence |
| --- | --- | ---: | --- |
| **The Coldcinder Seam** | `raven_rock`, `bloodskal` | 4 | Descend through `raven_rock_mine` into `bloodskal_barrow` (`raven_rock.L10.deep_gallery`), recover the Bloodskal blade and the true charter ledger; re-opens the deep ebony vein or shifts the council to ash-farming (`raven_rock.L9.mine_or_farm`). |
| **The Hall of Trophies** | `skaal` (`thirsk`) | 3 | Reclaim Thirsk Mead Hall from the riekling chief (`skaal.L9.riekling_raids`) or back the tribe's boar-riders; sets who holds the hall and who trades at Lake Fjalding. |
| **Ghosts of Frostmoth** | `isinfier` | 3 | Clear the ash-spawn commander in `fort_frostmoth` (`isinfier.L8.unquiet_garrison`) and decide the disposition of the East Empire Company's old stalhrim survey (`so.L9.company_claim`). |
| **The Spore-Master's Apprentices** | `isinfier` (`tel_mithryn`) | 3 | Heart-stone experiments, a rogue ash-guardian, and the dying root of the mushroom tower (`isinfier.L7.tower_house`). |
| **The Secret of Stalhrim** | `skaal`, `felsaad` | 2 | Rescue the Skaal smith from reavers/Thalmor prospectors (`felsaad.L9.stalhrim_claim`); unlocks stalhrim smithing (`so.L8.stalhrim_law`). |

---

## 8. Components layout

| Component | File or system | Now | To do (phase) |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/solstheim.json` (or `morrowind.json` `so` block) | in `solstheim.md` §1 | SO1 (V1) |
| Relief & hydrology | `tamriel_vectors.json` (Moesring peak, Mortrag, Harstrad, Lake Fjalding) | p90 78 | SO2 (V2) |
| Micro-biomes | `palettes.json` (`morrowind/solstheim` 9 micro rules) | 2 biomes | SO2 (V3) |
| Places | `places/morrowind.json` (50 -> 52 + 2 unmarked) | 50 | SO3 (V4) |
| Roads & travel | `named_roads.json` (3 island routes), `ports.json`, Stone nodes | 1 ferry | SO3 (V5) |
| Settlements | `cityplans/raven_rock.json`, `skaal_village.json`, `tel_mithryn.json`, `thirsk.json` | 2 plans | SO4 (V6) |
| Dungeons | `marquee.json` (Miraak, Bloodskal, Nchardak, Karstaag), `frossel` fix (R1a) | 30 | SO5 (V7) |
| Quests & voice | `quests/solstheim.json`, `east_empire.json` (4E 201 era alignment), two music cues (ash south / Skaal north) | 63 quests, 1 music cue | SO6 (V8) |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Geography | Exact 52 canonical Dragonborn/Bloodmoon positions | Moesring 155-block divide, Mortrag glacier wall, the sleet seam | Flat ash-to-snow transition without a ridge |
| Era & lore | Both 3E 427 Bloodmoon history and 4E 201 Redoran/Miraak present | Bloodmoon as L11 history (Frostmoth ruins, Hircine totems, Karstaag's skull); 4E 201 as live L6-L9 state | Mixing active 3E Company colony quests with 4E Redoran Raven Rock |
| Settlements | Raven Rock, Skaal Village, Tel Mithryn, Thirsk | Distinct kits (`redoran_shell`, `skaal_hide_bone`, `telvanni_fungal`, `nord_timber_hall`) | Generic Dunmer/Nord fallback huts |
| Dungeons | All 30 dungeons and 7 power sites | Loop shortcuts, Black Book altars, submerged Dwemer water-level rooms in Nchardak | Stuck rooms in `frossel` (fixed in R1a) |

---

## 10. Floors, gates, and implementation phases

### 10.1 Measured floor check (`morrowind/solstheim`, 0.976 km2)

| Metric | Now | Scaled floor (`0.976 km2`) | Real island | Target |
| --- | ---: | ---: | ---: | ---: |
| Marked places | 50 | 18 | 52 | **52** (+ 2 unmarked) |
| Settlements | 4 | 2 | 4 | **4** (all 4 with bespoke plans) |
| Named NPCs | 37 | 45 | 80 | **64** (Raven Rock 28, Skaal 14, Tel Mithryn 8, Thirsk 7, camps/keeps 7) |
| Dungeons | 30 | 9 | 35 | **32** (`d_identity` 1.0) |
| Quests | 63 | 14 | 58 | **65** (era-aligned to 4E 201) |
| Creature kinds (`c_total`) | 18 | 22 | 24 | **25** (ash spawn, ash hopper, netch, riekling + boar, burnt spriggan, werebear, lurker, seeker) |
| Micro-biomes (`F-MICRO`) | 2 | 6 | — | **9** |
| Music cues | 1 | 2 | — | **2** (`solstheim_ash`, `solstheim_skaal`) |
| Level band | 10-35 (25) | <= 20 | — | **15-35 (20)** |

### 10.2 Implementation phases (SO1–SO7, run inside R7 Morrowind)

| Phase | Step | Scope | Gate |
| --- | --- | --- | --- |
| **SO1** | V1 | Compile `solstheim` block into `tamriel/world/morrowind.json`; align `east_empire.json` to 4E 201 | `worldsheet_lint.py` 0 errors |
| **SO2** | V2, V3 | Raise Moesring & Mortrag relief; wire 9 micro-biomes and 2 music cues (`solstheim_ash`, `solstheim_skaal`) | `F-MICRO` >= 6; p90 >= 120 |
| **SO3** | V4, V5 | Add missing canonical pins (`thirsk`, `castle_karstaag`, `lake_fjalding`, `mortrag_glacier`, `aidanat`), 3 named roads, Stone travel nodes | `roadplan_lint` 0; `reach_lint` 100% |
| **SO4** | V6 | Build bespoke plans for `skaal_village`, `tel_mithryn`, `thirsk` and expand `raven_rock` to 28 named NPCs | `SET` >= 95; `y_looks` = 4 |
| **SO5** | V7 | Marquee upgrades for `temple_of_miraak`, `nchardak`, `bloodskal_barrow`, `castle_karstaag`; 2 unmarked sites | `d_identity` = 1.0; 0 analyzer blockers |
| **SO6** | V8 | Wire **The First Voice** 9-quest arc, 5 area chains, reactive lines for Stone cleansing, clamp level band to 15-35 | `F-LEVEL` <= 20; `F-REACT` >= 12 |
| **SO7** | V9 | Full DESIGN-PRINCIPLES 5.5 audit on `morrowind/solstheim` | All parity categories >= 90 |
