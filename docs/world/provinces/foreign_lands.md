# Foreign Lands (Atmora, Akavir, Yokuda, Pyandonea, Thras): expedition plan

Status: plan, no code changed; all ten sections written (2026-10-14). Feeds roadmap phase **R12**
(`docs/prompts/R12-foreign-lands.md`, steps V1-V9; phases FL0-FL6 in section 10.3). Governing rules:
`docs/world/DESIGN-PRINCIPLES.md` (world building is king, the eleven-layer causal sheet, G1-G12, and the
**half-density expedition template of section 4.1**). Companion specs: `docs/MARQUEE.md` (foreign lands block),
`docs/LORE-WEB.md`, `docs/FINISH-PLAN.md` T2 (land placement audit for foreign settlements). Legal line: names,
relative positions, counts and lore facts only; every sentence here is our own.

**Identity in one line.** The lands beyond Tamriel are **expeditions, not homelands**: reached only by chartered voyage
from a Tamriel port, built at half the regional density floor, each with an unmistakable alien biome mosaic, its own
architectural kit, a landing anchorage, one marquee wonder dungeon, and an expedition story that explains why outsiders
come and why few stay.

---

## 1. World model sheet

Data home: `nirncraft/tamriel/world/foreign.json` (MS22): one umbrella expedition block (`foreign`), five continent/land
blocks (`atmora`, `akavir`, `yokuda`, `pyandonea`, `thras`), and ten regional sub-sheets covering all 10 foreign regions
in `regions.json`.

| Land (id) | Regions (`regions.json`) | Charter Port in Tamriel | Signature Culture / Kit | Level band |
| --- | --- | --- | --- | --- |
| **Atmora** (`atmora`) | `atmora/ice_waste`, `atmora/frozen_forest` | Windhelm / Solitude | Ancient Atmoran cyclopean stone & rime-timber (`atmoran_frost`) | 28-45 |
| **Akavir** (`akavir`) | `akavir/tsaesci`, `akavir/kamal`, `akavir/tang_mo`, `akavir/ka_po_tun` | Ebonheart / Tear / Wayrest | Serpentine lacquer & bronze (`tsaesci`), ice-bastion (`kamal`), bamboo pavilion (`tang_mo`), tiger-stepped temple (`ka_po_tun`) | 30-50 |
| **Yokuda** (`yokuda`) | `yokuda/yokudan_desert`, `yokuda/sunken_isles` | Sentinel / Stros M'Kai | Old Na-Totambu fluted sandstone & star-observatory domes (`yokudan_archaic`) | 25-42 |
| **Pyandonea** (`pyandonea`) | `pyandonea/maormer_isles` | Alinor / Woodhearth | Sea-serpent bone, coral-stone, and translucent kelpsail (`maormer_kelp`) | 25-42 |
| **Thras** (`thras`) | `thras/coral_kingdom` | Anvil / Lillandril | Living calcified coral spires and abyssal brine pools (`thrassian_coral`) | 28-45 |

### 1.1 Province sheet (`foreign`)

**World truth paragraph.** Beyond Tamriel's coast lie five distant lands reached only by chartered expedition ships from
the great ports of the Empire and the Dominion: frozen Atmora in the polar north, four-crowned Akavir across the
Padomaic Ocean, the shattered desert mesas and drowned shoals of Yokuda in the west, mist-veiled Pyandonea in the
southern sea, and the living coral atoll of Thras. None of these lands are ordinary provinces; each is an expeditionary
frontier where outsiders cling to a single anchorage or treaty post, trading for relics, star-charts, serpent steel, or
forbidden reagents while ancient cataclysms and hostile courts press against the perimeter.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `expedition_archipelagos`: five distant continental margins and island chains separated from Tamriel by deep ocean basins. `cataclysm_scarred_crust`: glacial domes, sunken continental shelves, karst pillars, and calcified coral calderas |
| L2 Climate | `extreme_oceanic_regimes` (L1.expedition_archipelagos): terminal polar freeze in Atmora, monsoon and spring thaw in Akavir, arid trade winds in Yokuda, perpetual sea-fog in Pyandonea, and salt miasma over Thras |
| L3 Water | `charter_sea_lanes` (L1.expedition_archipelagos, L2.extreme_oceanic_regimes): long-passage ocean currents, seasonal pack-ice leads, and reef channels navigable only by chartered pilots |
| L4 Ecology | `alien_endemics` (L1.cataclysm_scarred_crust, L2.extreme_oceanic_regimes): frost-petrified forests, cloud bamboo, desert dunerippers, giant kelp serpents, and living coral polyps |
| L5 Economy | `charter_salvage_and_treaty_trade` (L3.charter_sea_lanes, L4.alien_endemics): high-risk expeditions returning stalhrim, Akaviri folded steel, orichalcum star-charts, black pearls, and alchemical contraband |
| L6 Settlement | `beachhead_anchorages` (L3.charter_sea_lanes, L5.charter_salvage_and_treaty_trade): fortified seasonal camps, pilgrim caravanserais, and quarantined treaty harbours pinned to dry coastal shelves |
| L7 Society | `captains_factors_and_custodians` (L6.beachhead_anchorages): charter captains, guild chroniclers, Ankh-Keepers, and treaty factors living under strict port law |
| L8 Belief | `elder_homeland_myths` (L1.cataclysm_scarred_crust, L7.captains_factors_and_custodians): ancestor totems of the Atmoran exodus, the Way of the Sword, the Coiled King, and Akaviri dragon-tiger apotheosis |
| L9 Conflict | `quarantine_and_awakening` (L6.beachhead_anchorages, L8.elder_homeland_myths): each anchorage faces an imminent local crisis—waking dragon-priests, Kamal thaws, rogue Ansei seekers, Maormer mutinies, or Sload plague vats |
| L10 Underground | `wonder_sanctums` (L1.cataclysm_scarred_crust, L9.quarantine_and_awakening): ice-entombed meadhalls, Tenth Legion siege-citadels, sunken Pankratosword vaults, serpent-rib grottoes, and drowned coral ziggurats |
| L11 History | `sundered_origins` (L1.cataclysm_scarred_crust, L8.elder_homeland_myths): the departures, sinkings, schisms, and doomed invasions that severed these five lands from Tamriel |

### 1.2 Atmora (`atmora`)

**World truth paragraph.** Atmora—Altmora, the Elder Wood of myth—is a continent locked in terminal glaciation north of
the Sea of Ghosts. No living hearth burns inland; what remains are petrified frost-forests, cyclopean dragon-totem
halls half-swallowed by blue glacier tongues, and the frozen dead of the last ships that failed to sail south with
Ysgramor. Only seasonal salvage crews and dragon-cult scholars from Windhelm and Solitude camp on its southern basalt
shingle, watching the pack-ice leads before winter locks the sea and the unthawed king in Jylkurfyk calls the frost-dead
against the anchorage.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `continental_ice_dome`: a mile-high glacial shield descending to basalt sea-cliffs. `petrified_wood_valleys`: sheltered fjords where whole forests froze standing |
| L2 Climate | `Great_Freeze` (L1.continental_ice_dome): permanent sub-zero whiteout, green-and-violet auroras, catabatic gales off the ice cap |
| L3 Water | `pack_ice_leads` (L1.continental_ice_dome, L2.Great_Freeze): shifting sea-ice channels that open only in mid-summer. `subglacial_brine` (L1.continental_ice_dome): unfrozen hypersaline rivers beneath the glacier |
| L4 Ecology | `rime_timber_stand` (L1.petrified_wood_valleys, L2.Great_Freeze): frost-petrified pines, ice wraiths, ancient frost trolls. `glacier_barrens` (L1.continental_ice_dome, L2.Great_Freeze): snow whales offshore, frost giants, white sabre cats |
| L5 Economy | `relic_and_stalhrim_expeditions` (L1.continental_ice_dome, L3.pack_ice_leads): no native trade; Windhelm charter camps salvage Atmoran bronze, dragon-masks, and pure stalhrim |
| L6 Settlement | `kjolvik_anchorage` (L3.pack_ice_leads, L5.relic_and_stalhrim_expeditions): a fortified seasonal expedition camp on dry basalt above the fjord. `jylkurfyk_ruins` (L1.petrified_wood_valleys): the ruined shipyard-city of the exodus |
| L7 Society | `charter_crews_and_scholars` (L6.kjolvik_anchorage): Nord captains, College chroniclers, and grave-robbers bound by winter-ration law |
| L8 Belief | `totem_ancestors` (L6.jylkurfyk_ruins): the nine animal totems (Hawk, Wolf, Snake, Moth, Owl, Whale, Bear, Fox, Dragon) in their pre-Imperial form |
| L9 Conflict | `frozen_king_awakening` (L6.kjolvik_anchorage, L8.totem_ancestors): an unthawed dragon-priest in Jylkurfyk calling the frost-dead against the salvage camp |
| L10 Underground | `ice_entombed_meadhalls` (L1.continental_ice_dome, L8.totem_ancestors, L9.frozen_king_awakening): giant-scale stone halls under the ice sheet. `subglacial_ice_caverns` (L1.continental_ice_dome, L3.subglacial_brine): deep brine tunnels beneath the glacier |
| L11 History | `the_return_exodus` (L6.jylkurfyk_ruins, L8.totem_ancestors): Ysgramor's departure, the last fleet of 1E 68, and the continent's final freeze |

### 1.3 Akavir (`akavir`)

**World truth paragraph.** Akavir, the Dragon Land across the Padomaic Ocean, is a mysterious eastern continent of four
warring realms where human populations were long ago absorbed or ruled by immortal serpent-folk, thawing snow-demons,
hospitable monkey-islanders, and tiger-dragon monks. Outsiders from Tamriel are permitted only in a single open
treaty-harbour in Tang Mo and a strictly guarded factor's compound in Tsaesci; beyond those treaty gates lie mist-hung
bamboo cloud-mountains, red-scale river fortresses, stepped golden plateaus, and the thawing glacier passes of Kamal
where Emperor Uriel V's Tenth Legion lost its eagle standard at Ionith.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `bamboo_karst_pillars`: vertical limestone towers and mist valleys in Tang Mo. `serpent_river_plains`: terraced red-earth basins in Tsaesci. `kamal_hell_glaciers`: jagged volcanic-and-ice peaks in the north. `golden_mesa_steppe`: high sun-baked plateaus in Ka Po' Tun |
| L2 Climate | `monsoon_and_thaw` (L1.bamboo_karst_pillars, L1.kamal_hell_glaciers): warm monsoon mists in Tang Mo, dry heat in Ka Po' Tun, and the annual spring thaw that unleashes Kamal armies |
| L3 Water | `lacquer_canals` (L1.serpent_river_plains, L2.monsoon_and_thaw): stepped irrigation and barge canals. `island_straits` (L1.bamboo_karst_pillars, L2.monsoon_and_thaw): tidal channels between the monkey-isles |
| L4 Ecology | `cloud_bamboo_forest` (L1.bamboo_karst_pillars, L2.monsoon_and_thaw): giant bamboo, blossoms, Tang Mo rangers. `crimson_reed_plain` (L1.serpent_river_plains): serpent-constructs, scale-hounds. `thaw_waste` (L1.kamal_hell_glaciers, L2.monsoon_and_thaw): frost-demons, ice-beetles |
| L5 Economy | `treaty_silk_and_steel` (L3.lacquer_canals, L3.island_straits): Akaviri folded-steel blades, lacquerware, and spice traded under strict port charter |
| L6 Settlement | `tang_mo_harbor_village` (L3.island_straits, L5.treaty_silk_and_steel): open treaty port on dry coastal terrace. `tsaesci_treaty_post` (L3.lacquer_canals, L5.treaty_silk_and_steel): walled factor's enclave. `tiger_plains_village` (L1.golden_mesa_steppe): high steppe shrine-town. `kamal_watch_camp` (L1.kamal_hell_glaciers): northern ice-pass watch |
| L7 Society | `four_court_protocol` (L6.tsaesci_treaty_post, L6.tang_mo_harbor_village): outsiders carry bronze tally-seals; breaking quarantine means immediate expulsion |
| L8 Belief | `dragon_tiger_ascension` (L1.golden_mesa_steppe, L6.tiger_plains_village): Tosh Raka's apotheosis. `immortal_coil` (L6.tsaesci_treaty_post, L7.four_court_protocol): blood-and-memory lineage |
| L9 Conflict | `spring_thaw_front` (L2.monsoon_and_thaw, L6.kamal_watch_camp, L6.tang_mo_harbor_village): Kamal raiders testing the Tang Mo island passes while Tsaesci envoys play both sides |
| L10 Underground | `serpent_scale_citadels` (L1.serpent_river_plains, L8.immortal_coil): subterranean blood-temples and blade-forges. `imperial_disaster_tombs` (L1.serpent_river_plains, L9.spring_thaw_front): the lost vaults of Uriel V's Tenth Legion at Ionith |
| L11 History | `reman_invasion` (L8.immortal_coil): the 1E 2703 Pale Pass march. `potentate_years` (L7.four_court_protocol): Tsaesci rule in Cyrodiil from 2E 1 to 430. `ionith_campaign` (L6.tsaesci_treaty_post, L10.imperial_disaster_tombs): Uriel V's doomed 3E 288 expedition to Septimia and Ionith |

### 1.4 Yokuda (`yokuda`)

**World truth paragraph.** Yokuda is the shattered western archipelago of a sunken continent across the sea from
Hammerfell: flat-topped red sandstone mesas, drowned white-marble causeways visible beneath clear turquoise shoals, and
brass star-observatories left from the high age before the forbidden Pankratosword split the land in 1E 792. Redguard
pilgrims, sword-singers, and Sentinel chart-captains sail here to walk the Ancestor Steps at New Tava Anchorage, while
sworn Ankh-Keepers guard the half-submerged Na-Totambu tombs against plunderers and a rogue Ansei seeker trying to
unseal the fault where the continent broke.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `riven_archipelago`: sheared sandstone mesas and basalt faults dropping straight into submerged city shelves |
| L2 Climate | `trade_wind_arid` (L1.riven_archipelago): hot dry trade winds and crystalline night skies for star-reading |
| L3 Water | `drowned_causeway_shoals` (L1.riven_archipelago): shallow turquoise lagoons over sunken streets and oasis cisterns in the mesa rock |
| L4 Ecology | `mesa_scrub_and_oasis` (L1.riven_archipelago, L2.trade_wind_arid): date palms, desert lynx, dunerippers, and bone-walkers in the salt flats |
| L5 Economy | `pilgrim_and_chart_trade` (L3.drowned_causeway_shoals): memory-stones, orichalcum salvage, and star-charts |
| L6 Settlement | `new_tava_anchorage` (L1.riven_archipelago, L5.pilgrim_and_chart_trade): Redguard pilgrim port and caravanserai on a high dry mesa shelf. `riven_shoal_camp` (L3.drowned_causeway_shoals): salvage camp on the outer shoals |
| L7 Society | `ankh_keepers_and_singers` (L6.new_tava_anchorage): order of Redguard custodians who guard the sunken tombs from plunderers |
| L8 Belief | `way_of_the_sword_and_stars` (L2.trade_wind_arid, L7.ankh_keepers_and_singers): the Shehai spirit sword and the constellations of the Warrior, Thief, and Mage |
| L9 Conflict | `forbidden_stroke` (L7.ankh_keepers_and_singers, L8.way_of_the_sword_and_stars): a rogue Ansei seeker trying to unseal the vault where the Pankratosword was struck |
| L10 Underground | `na_totambu_star_tombs` (L1.riven_archipelago, L9.forbidden_stroke): half-submerged sandstone-and-brass sword-singer vaults |
| L11 History | `sinking_of_yokuda` (L1.riven_archipelago, L8.way_of_the_sword_and_stars): the 1E 792 Hiradirge, the Pankratosword, and the Ra Gada warrior-wave to Hammerfell |

### 1.5 Pyandonea (`pyandonea`)

**World truth paragraph.** Far south of Summerset beyond a wall of perpetual warm sea-fog lies Pyandonea, the veiled
tropical jungle-and-reef archipelago of the Maormer Sea Elves and their deathless wizard-monarch King Orgnum. Giant
kelp forests, bioluminescent basalt cliffs, and fleet harbours lashed from sea-serpent bone and translucent kelpsail
shelter the armadas that raid Auridon every generation. At the outer truce-anchorage, foreign privateers holding
temporary ivory tokens trade for black pearls and serpent-scale mail while a mutinous fleet-lord prepares a lightning
storm-ritual to break the truce and strike Summerset's open ports.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `veil_reef_archipelago`: volcanic jungle peaks ringed by barrier reefs and deep sea-trenches |
| L2 Climate | `eternal_sea_mist` (L1.veil_reef_archipelago): warm tropical rain and a permanent mist-curtain along the northern approaches |
| L3 Water | `serpent_lagoon` (L1.veil_reef_archipelago, L2.eternal_sea_mist): deep sheltered fleet anchorages where sea serpents coil |
| L4 Ecology | `kelp_and_mist_jungle` (L2.eternal_sea_mist, L3.serpent_lagoon): giant canopy ferns, strangler vines, sea serpents, storm-ateliers |
| L5 Economy | `privateer_and_pearl_trade` (L3.serpent_lagoon, L4.kelp_and_mist_jungle): black pearls, serpent-scale mail, and neutral privateer safe-conducts |
| L6 Settlement | `pyandonean_fleet_anchorage` (L3.serpent_lagoon, L5.privateer_and_pearl_trade): outer truce-harbour on a firm coastal terrace with Mistveil Village on the inner ridge |
| L7 Society | `storm_caste_and_truce_captains` (L6.pyandonean_fleet_anchorage): Maormer storm-callers and foreign privateers holding temporary truce-tokens |
| L8 Belief | `orgnum_and_the_coiled_king` (L3.serpent_lagoon, L7.storm_caste_and_truce_captains): worship of the undying serpent-monarch and the lost Aldmeris claim |
| L9 Conflict | `mutiny_of_the_truce_fleet` (L7.storm_caste_and_truce_captains, L8.orgnum_and_the_coiled_king): a rogue fleet-lord preparing a storm-ritual against Summerset's open ports |
| L10 Underground | `serpent_rib_grottoes` (L1.veil_reef_archipelago, L3.serpent_lagoon, L9.mutiny_of_the_truce_fleet): tidal sea-caves and lightning-spire sanctums |
| L11 History | `aldmeris_schism` (L8.orgnum_and_the_coiled_king): Orgnum's ancient banishment. `war_of_the_isle` (L6.pyandonean_fleet_anchorage, L9.mutiny_of_the_truce_fleet): the 3E 110 storm that broke the Maormer armada |

### 1.6 Thras (`thras`)

**World truth paragraph.** West of Summerset and south-west of the Iliac Bay rises the Coral Kingdom of Thras: a ring
atoll of living, calcified pink-and-bone coral spires, bottomless brine sinkholes, and sacrificial lagoons ruled by the
amphibious, calculating Sload. Only licensed alchemy-ships and Bend'r-mahk wreck-hunters dare anchor on its quarantined
outer sandbar, where Sload factors deal in literal blood-and-pearl bargains for Sload soap and abyssal pearl-glass while
a deep-vent cabal cultivates a second strain of the Thrassian plague in flooded bioluminescent ziggurats beneath Agonir
Lagoon.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `living_coral_atoll`: calcified coral towers 30 to 70 blocks high growing over a drowned volcanic caldera around Agonir Lagoon |
| L2 Climate | `brine_miasma` (L1.living_coral_atoll): heavy salt-haze, eerie dead-calm air, phosphorescent night tides |
| L3 Water | `abyssal_sinkholes` (L1.living_coral_atoll): bottomless blue holes connecting the central lagoon to Summerset's abyssal vents |
| L4 Ecology | `bleached_and_living_reef` (L1.living_coral_atoll, L3.abyssal_sinkholes): giant anemones, reef-vipers, zombie thralls, Sload |
| L5 Economy | `sload_soap_and_alchemical_contraband` (L4.bleached_and_living_reef): forbidden necromantic reagents and pearl-glass traded at the outer sandbar |
| L6 Settlement | `thras_sandbar_camp` (L1.living_coral_atoll, L5.sload_soap_and_alchemical_contraband): a quarantined trader and expedition anchorage on the outer reef facing Sload coral-spires |
| L7 Society | `calculating_contractors` (L5.sload_soap_and_alchemical_contraband, L6.thras_sandbar_camp): Sload speak with outsiders purely through slow, literal contracts |
| L8 Belief | `efficiency_and_necromancy` (L4.bleached_and_living_reef, L7.calculating_contractors): unsentimental mastery of flesh, memory, and deep-sea currents |
| L9 Conflict | `the_second_plague_vats` (L3.abyssal_sinkholes, L8.efficiency_and_necromancy): a Sload cabal cultivating a new strain of Thrassian miasma in the deep vents |
| L10 Underground | `drowned_coral_ziggurats` (L1.living_coral_atoll, L3.abyssal_sinkholes, L9.the_second_plague_vats): flooded bio-luminescent chambers beneath the atoll |
| L11 History | `thrassian_plague` (L8.efficiency_and_necromancy, L9.the_second_plague_vats): the 1E 2200 plague and the All-Flags Navy that sank Thras the first time |

### 1.7 Atmora — Ice Waste (`atmora/ice_waste`, levels 28-45)

**World truth.** The Ice Waste is the northernmost glacial shield of Atmora: a mile-high dome of blue continental ice
descending to black basalt shingle beaches where Windhelm charter vessels moor in brief mid-summer pack-ice leads. Frost
giants, snow whales, ice wraiths and white sabre cats roam the crevasses around fortified Kjolvik Anchorage, while
Nord salvage crews dig for pure stalhrim and ancient bronze beneath polar auroras, and subglacial brine rivers flow
past cyclopean stone meadhalls entombed when the Great Freeze overtook the continent after the last ships sailed south.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `glacial_shield`: blue continental ice sheet and basalt sea-cliffs |
| L2 | `catabatic_whiteout` (fl.L2.extreme_oceanic_regimes, L1.glacial_shield): permanent sub-zero gales and polar auroras |
| L3 | `summer_ice_leads` (fl.L3.charter_sea_lanes, L2.catabatic_whiteout): brief navigable channels in the pack ice |
| L4 | `glacier_fauna` (fl.L4.alien_endemics, L2.catabatic_whiteout): frost giants, snow whales, and ice wraiths |
| L5 | `stalhrim_salvage` (fl.L5.charter_salvage_and_treaty_trade, L3.summer_ice_leads): pure burial ice and bronze recovered by charter crews |
| L6 | `kjolvik_camp` (fl.L6.beachhead_anchorages, L3.summer_ice_leads): fortified basalt-shingle expedition anchorage |
| L7 | `windhelm_charter_crews` (fl.L7.captains_factors_and_custodians, L6.kjolvik_camp): Nord mariners and winter-ration wardens |
| L8 | `whale_and_bear_totems` (fl.L8.elder_homeland_myths, L6.kjolvik_camp): weathered coastal totem pillars of the old faith |
| L9 | `pack_ice_lock` (fl.L9.quarantine_and_awakening, L3.summer_ice_leads): early freeze threatening to trap the salvage ship |
| L10 | `entombed_great_halls` (fl.L10.wonder_sanctums, L1.glacial_shield, L9.pack_ice_lock): giant-scale Atmoran halls beneath the blue ice |
| L11 | `last_fleet_shingle` (fl.L11.sundered_origins, L6.kjolvik_camp): hulls of the ships that missed the 1E 68 departure |

### 1.8 Atmora — Frozen Forest (`atmora/frozen_forest`, levels 28-45)

**World truth.** In the sheltered southern basalt fjords of Atmora stands the Frozen Forest: whole silent valleys of
ancient pines and elder trees flash-frozen in crystalline rime ice around the cyclopean ruined shipyard-city of
Jylkurfyk. Here Ysgramor's Five Hundred Companions hewed the keels of the Return before the Great Freeze closed the
harbour slips forever. Today Winterhold chroniclers and chartered relic-hunters trace the old totem road through the
petrified trunks while an unthawed dragon-priest in Jylkurfyk Great Barrow stirs the frost-dead from their ice-sealed
tier-tombs.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `fjord_valleys`: sheltered basalt valleys holding standing petrified forests |
| L2 | `rime_hoarfrost` (fl.L2.extreme_oceanic_regimes, L1.fjord_valleys): still, crystalline freezing air that preserves timber as stone |
| L3 | `frozen_cascades` (fl.L3.charter_sea_lanes, L1.fjord_valleys): waterfalls locked in blue ice columns above subglacial brine |
| L4 | `petrified_rime_woods` (fl.L4.alien_endemics, L2.rime_hoarfrost): rime-encrusted pines, ancient frost trolls, and wisps |
| L5 | `dragon_cult_relic_digs` (fl.L5.charter_salvage_and_treaty_trade, L4.petrified_rime_woods): inscribed bronze masks and elder-wood cores |
| L6 | `jylkurfyk_port_ruins` (fl.L6.beachhead_anchorages, L1.fjord_valleys): cyclopean stone slips of the ancient shipyard-city |
| L7 | `chroniclers_and_delvers` (fl.L7.captains_factors_and_custodians, L5.dragon_cult_relic_digs): scholars reading the pre-Return inscriptions |
| L8 | `dragon_totem_orthodoxy` (fl.L8.elder_homeland_myths, L6.jylkurfyk_port_ruins): the primal Dragon and Owl totems in cyclopean relief |
| L9 | `barrow_king_stirring` (fl.L9.quarantine_and_awakening, L8.dragon_totem_orthodoxy): the Frozen King calling draugr patrols down the fjord |
| L10 | `jylkurfyk_great_barrow` (fl.L10.wonder_sanctums, L1.fjord_valleys, L9.barrow_king_stirring): hearth-brazier vaults sealed by glacial ice |
| L11 | `ysgramor_shipyards` (fl.L11.sundered_origins, L6.jylkurfyk_port_ruins): the slips where the Five Hundred Companions built their fleet |

### 1.9 Akavir — Tsaesci (`akavir/tsaesci`, levels 30-50)

**World truth.** Tsaesci is the south-western realm of Akavir: a country of terraced red-earth river basins, stepped
lacquer irrigation canals, and crimson reed plains ruled by the immortal vampire serpent-courts. Foreigners from Tamriel
may not pass beyond the walled coastal factor's compound without a bronze tally-seal, where chartered Ebonheart factors
trade for folded-steel blades and red lacquerware. Just inland stand the dual-layer ruins of Ionith, where Emperor
Uriel V's Tenth Legion died under arrow-rain in 3E 288 above a subterranean Tsaesci blood-forge and scale-citadel.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `red_earth_basin`: terraced red-clay river plains and obsidian outcrops |
| L2 | `warm_monsoon_haze` (fl.L2.extreme_oceanic_regimes, L1.red_earth_basin): humid summer rains and dry autumn winds |
| L3 | `stepped_lacquer_canals` (fl.L3.charter_sea_lanes, L1.red_earth_basin): engineered irrigation and barge waterways |
| L4 | `crimson_reeds` (fl.L4.alien_endemics, L3.stepped_lacquer_canals): red river-reeds, scale-hounds, and serpent constructs |
| L5 | `folded_steel_and_lacquer` (fl.L5.charter_salvage_and_treaty_trade, L3.stepped_lacquer_canals): Akaviri katanas, red lacquerware, and silk |
| L6 | `tsaesci_factor_compound` (fl.L6.beachhead_anchorages, L3.stepped_lacquer_canals): walled treaty post beside the ruins of Ionith |
| L7 | `tally_seal_custodians` (fl.L7.captains_factors_and_custodians, L6.tsaesci_factor_compound): serpent-court magistrates and chartered Ebonheart factors |
| L8 | `immortal_blood_lineage` (fl.L8.elder_homeland_myths, L7.tally_seal_custodians): reverence for the undying scale-lines |
| L9 | `eagle_standard_intrigue` (fl.L9.quarantine_and_awakening, L6.tsaesci_factor_compound): rival Tsaesci houses using the lost Imperial standard as leverage |
| L10 | `citadel_of_ionith` (fl.L10.wonder_sanctums, L1.red_earth_basin, L9.eagle_standard_intrigue): 3E 288 Imperial siege-works over a Tsaesci blood-forge |
| L11 | `uriels_doomed_march` (fl.L11.sundered_origins, L10.citadel_of_ionith): the siege and fall of Ionith in 3E 288 |

### 1.10 Akavir — Kamal (`akavir/kamal`, levels 30-50)

**World truth.** Kamal is the northern ice-hell of Akavir: jagged black volcanic crags and hanging glaciers locked in
nine months of howling blizzard until the violent annual spring thaw melts the high passes and looses the snow-demon
hosts southward toward Tang Mo and Tsaesci. A single fortified stone watch-camp above the thaw line monitors the roaring
meltwater torrents and glacier tongues, where armoured ice-beetles, snow predators, and Kamal vanguard warbands gather
in rime-tempered iron around volcanic-heated subterranean steam-bastions in memory of Ada'Soom Dir-Kamal's Second Era
march.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `volcanic_ice_peaks`: black volcanic crags sheathed in hanging glaciers |
| L2 | `annual_thaw_gale` (fl.L2.extreme_oceanic_regimes, L1.volcanic_ice_peaks): nine months of blizzard broken by a violent spring melt |
| L3 | `meltwater_torrents` (fl.L3.charter_sea_lanes, L2.annual_thaw_gale): roaring glacial floods that open the mountain passes |
| L4 | `ice_beetle_tundra` (fl.L4.alien_endemics, L2.annual_thaw_gale): armoured ice-beetles and snow-predators |
| L5 | `glacial_iron_and_trophies` (fl.L5.charter_salvage_and_treaty_trade, L1.volcanic_ice_peaks): dark meteoric iron and rime-tempered armour |
| L6 | `kamal_pass_watch` (fl.L6.beachhead_anchorages, L1.volcanic_ice_peaks): fortified stone watch-camp above the thaw line |
| L7 | `winter_host_warbands` (fl.L7.captains_factors_and_custodians, L6.kamal_pass_watch): garrison scouts watching the northern ice-clans |
| L8 | `rite_of_the_spring_march` (fl.L8.elder_homeland_myths, L2.annual_thaw_gale): martial cult of the thawing war-season |
| L9 | `thaw_vanguard_incursion` (fl.L9.quarantine_and_awakening, L3.meltwater_torrents): Kamal raiders massing at the southern pass |
| L10 | `steam_ice_bastions` (fl.L10.wonder_sanctums, L1.volcanic_ice_peaks, L9.thaw_vanguard_incursion): volcanic-heated ice fortresses beneath the peaks |
| L11 | `dir_kamal_memory` (fl.L11.sundered_origins, L8.rite_of_the_spring_march): Ada'Soom Dir-Kamal's Second Era invasion of Tamriel |

### 1.11 Akavir — Tang Mo (`akavir/tang_mo`, levels 30-50)

**World truth.** Tang Mo is the Land of the Thousand Monkey Isles: vertical limestone karst pillars wrapped in warm
monsoon cloud-mist, giant green bamboo forests, wind-bell pinnacle shrines, and curved tile-and-bamboo pavilions perched
high above calm turquoise straits. Its coastal treaty harbour is the one port in all Akavir where Tamrielic charter
ships from Ebonheart are welcomed openly, exchanging spice, tea, and silk while brave Tang Mo ranger clans and allied
Ka Po' Tun envoys defend the high suspension bridges against Kamal thaw-raiders and Tsaesci privateers.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `karst_spire_isles`: vertical limestone pillars rising from coastal straits |
| L2 | `monsoon_cloud_mist` (fl.L2.extreme_oceanic_regimes, L1.karst_spire_isles): warm mist and seasonal rains clinging to the spires |
| L3 | `strait_anchorages` (fl.L3.charter_sea_lanes, L1.karst_spire_isles): deep calm channels between the limestone islands |
| L4 | `cloud_bamboo_groves` (fl.L4.alien_endemics, L2.monsoon_cloud_mist): towering green bamboo and blossom canopies |
| L5 | `open_charter_spice_trade` (fl.L5.charter_salvage_and_treaty_trade, L3.strait_anchorages): spice, tea, bamboo craft, and silk exchanged with Ebonheart ships |
| L6 | `tang_mo_treaty_harbor` (fl.L6.beachhead_anchorages, L3.strait_anchorages): curved bamboo-and-tile port on a dry coastal terrace |
| L7 | `island_ranger_clans` (fl.L7.captains_factors_and_custodians, L6.tang_mo_treaty_harbor): hospitable monkey-folk clans and foreign charter captains |
| L8 | `ancestral_spire_shrines` (fl.L8.elder_homeland_myths, L1.karst_spire_isles): wind-bell shrines on the highest karst pinnacles |
| L9 | `strait_bridge_defense` (fl.L9.quarantine_and_awakening, L6.tang_mo_treaty_harbor): defending the suspension bridges against northern raiders |
| L10 | `mist_pillar_sanctums` (fl.L10.wonder_sanctums, L1.karst_spire_isles, L9.strait_bridge_defense): hollow limestone spire-caverns and rope-ascents |
| L11 | `four_realm_truces` (fl.L11.sundered_origins, L7.island_ranger_clans): the alliances that kept the Thousand Isles unconquered |

### 1.12 Akavir — Ka Po' Tun (`akavir/ka_po_tun`, levels 30-50)

**World truth.** Ka Po' Tun is the high sun-baked sandstone plateau of the Tiger-Dragon Empire in central and eastern
Akavir: wide golden grass steppes, deep rock-cut stepwells, stepped golden-stone temples, and bronze-gong courts ruled
by the divine tiger-dragon Tosh Raka, who transformed himself into the largest dragon in the world. Connected to the
Tang Mo treaty harbour by the inland treaty road, Tiger Plains Village hosts allied pilgrims and envoys who witness the
strict martial discipline of the tiger-monk retainers and their eternal border vigilance against the Tsaesci
serpent-courts.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `golden_steppe_mesa`: high sun-baked sandstone plateaus and dry canyons |
| L2 | `continental_sun_heat` (fl.L2.extreme_oceanic_regimes, L1.golden_steppe_mesa): bright, dry skies and crisp highland winds |
| L3 | `stepped_cistern_wells` (fl.L3.charter_sea_lanes, L1.golden_steppe_mesa): deep rock-cut stepwells feeding the plateau towns |
| L4 | `golden_grass_steppe` (fl.L4.alien_endemics, L2.continental_sun_heat): tall golden grass and great striped steppe-cats |
| L5 | `bronze_and_jade_tribute` (fl.L5.charter_salvage_and_treaty_trade, L3.stepped_cistern_wells): cast bronze bells, sun-jade, and martial manuals |
| L6 | `tiger_plains_shrine_town` (fl.L6.beachhead_anchorages, L3.stepped_cistern_wells): stepped golden-stone town on the inland treaty road |
| L7 | `tiger_monk_retainers` (fl.L7.captains_factors_and_custodians, L6.tiger_plains_shrine_town): disciplined temple guards and allied Tang Mo envoys |
| L8 | `tosh_raka_apotheosis` (fl.L8.elder_homeland_myths, L6.tiger_plains_shrine_town): veneration of the ruler who became the largest dragon in the world |
| L9 | `serpent_border_standoff` (fl.L9.quarantine_and_awakening, L7.tiger_monk_retainers): armed vigilance along the Tsaesci frontier |
| L10 | `dragon_step_sanctuary` (fl.L10.wonder_sanctums, L1.golden_steppe_mesa, L9.serpent_border_standoff): subterranean flame-lit trial halls beneath the stepped temple |
| L11 | `black_dragon_ascension` (fl.L11.sundered_origins, L8.tosh_raka_apotheosis): the transformation of Tosh Raka and the renaming of the empire |

### 1.13 Yokuda — Yokudan Desert (`yokuda/yokudan_desert`, levels 25-42)

**World truth.** The Yokudan Desert is the high, dry mesa remnant of old Yokuda: sheared red sandstone tablelands
cut by the 1E 792 cataclysm fault and crowned by fluted Na-Totambu columns, brass star-observatories, and rock-cut
date-palm oasis cisterns. At New Tava Anchorage, charter ships from Sentinel and Stros M'Kai land Redguard pilgrims,
sword-scholars, and sword-singers to walk the Ancestor Steps under cloudless starry skies, while sworn Ankh-Keepers
guard the inland star-mirror fault-vaults against a rogue seeker who would wake the forbidden Pankratosword stroke.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `sheared_red_mesas`: flat-topped sandstone mesas cut by the 1E 792 cataclysm fault |
| L2 | `star_clear_arid` (fl.L2.extreme_oceanic_regimes, L1.sheared_red_mesas): dry trade winds and cloudless night skies |
| L3 | `mesa_oasis_cisterns` (fl.L3.charter_sea_lanes, L1.sheared_red_mesas): rock-cut spring reservoirs feeding the pilgrim road |
| L4 | `date_palm_and_lynx_scrub` (fl.L4.alien_endemics, L2.star_clear_arid): oasis palms, desert lynx, and dunerippers |
| L5 | `orichalcum_and_star_charts` (fl.L5.charter_salvage_and_treaty_trade, L3.mesa_oasis_cisterns): astronomical tables and ancient metalwork |
| L6 | `new_tava_pilgrim_port` (fl.L6.beachhead_anchorages, L1.sheared_red_mesas): fluted sandstone caravanserai and harbour on a dry shelf |
| L7 | `ansei_pilgrims_and_keepers` (fl.L7.captains_factors_and_custodians, L6.new_tava_pilgrim_port): Ankh-Keepers and Hammerfell sword-scholars |
| L8 | `constellation_liturgy` (fl.L8.elder_homeland_myths, L2.star_clear_arid): rites of the Warrior, Thief, and Mage under Yokudan stars |
| L9 | `fault_vault_seeker` (fl.L9.quarantine_and_awakening, L7.ansei_pilgrims_and_keepers): a rogue seeker attempting to unseal the Pankratosword chamber |
| L10 | `vault_of_the_pankratosword` (fl.L10.wonder_sanctums, L1.sheared_red_mesas, L9.fault_vault_seeker): star-mirror sanctum spanning the riven fault |
| L11 | `na_totambu_high_age` (fl.L11.sundered_origins, L6.new_tava_pilgrim_port): the emperors and sword-schools before the sinking |

### 1.14 Yokuda — Sunken Isles (`yokuda/sunken_isles`, levels 25-42)

**World truth.** Surrounding the high sandstone mesas of Yokuda lie the Sunken Isles: a warm turquoise archipelago of
low coral-sand cays, half-submerged white-marble causeways, and drowned city plazas where fluted domes rise out of the
clear tide. Sentinel salvage divers and chart-captains camp at Riven Shoal Anchorage on the outer limestone shoals to
recover sealed Yokudan memory-stones, orichalcum ingots, and brass astrolabes from the flooded streets, watched closely
by sworn Ankh-Keepers and wary of restless Ansei wardens and brass star-automata that still patrol the tide-washed
colonnades.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `submerged_city_shelves`: drowned marble terraces and low coral-sand cays |
| L2 | `trade_wind_swell` (fl.L2.extreme_oceanic_regimes, L1.submerged_city_shelves): warm ocean swells and salt spray over the shoals |
| L3 | `turquoise_ruin_lagoons` (fl.L3.charter_sea_lanes, L1.submerged_city_shelves): clear shallow water over intact stone avenues |
| L4 | `shoal_reef_and_salt_scrub` (fl.L4.alien_endemics, L3.turquoise_ruin_lagoons): reef life and bone-walkers in the tidal flats |
| L5 | `memory_stone_salvage` (fl.L5.charter_salvage_and_treaty_trade, L3.turquoise_ruin_lagoons): diving for sealed Yokudan memory-stones and brass |
| L6 | `riven_shoal_anchorage` (fl.L6.beachhead_anchorages, L3.turquoise_ruin_lagoons): diver and chart-captain camp on a dry limestone cay |
| L7 | `salvage_divers_and_wardens` (fl.L7.captains_factors_and_custodians, L5.memory_stone_salvage): Sentinel wreck-divers watched by Ankh-Keepers |
| L8 | `drowned_ancestor_vigil` (fl.L8.elder_homeland_myths, L3.turquoise_ruin_lagoons): lanterns floated over the sunken streets at night |
| L9 | `plunderers_in_the_shoals` (fl.L9.quarantine_and_awakening, L7.salvage_divers_and_wardens): treasure-hunters breaking into warded drowned tombs |
| L10 | `drowned_orichalc_vaults` (fl.L10.wonder_sanctums, L1.submerged_city_shelves, L9.plunderers_in_the_shoals): tidal star-tombs guarded by brass automata |
| L11 | `the_wave_of_1e_792` (fl.L11.sundered_origins, L1.submerged_city_shelves): the night the western cities slid beneath the sea |

### 1.15 Pyandonea — Maormer Isles (`pyandonea/maormer_isles`, levels 25-42)

**World truth.** The Maormer Isles of Pyandonea rise out of a perpetual warm sea-fog south of Summerset: steep volcanic
jungle peaks, giant kelp lagoons, and fleet harbours built from sea-serpent bone and translucent kelpsail. Foreign
ships from Tamriel may moor only at the outer Pyandonean Fleet Anchorage under a privateer truce-token to trade for
black pearls and serpent-scale mail; inland past Mistveil Village stands the Sanctum of the Coiled King, where Maormer
storm-callers harness lightning spires and breed sea serpents for the next armada against Auridon.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `volcanic_reef_isles`: steep jungle peaks ringed by barrier reefs and serpent trenches |
| L2 | `warm_mist_curtain` (fl.L2.extreme_oceanic_regimes, L1.volcanic_reef_isles): eternal tropical fog and sudden lightning squalls |
| L3 | `serpent_coil_lagoons` (fl.L3.charter_sea_lanes, L1.volcanic_reef_isles): deep sheltered fleet basins |
| L4 | `kelp_and_strangler_jungle` (fl.L4.alien_endemics, L2.warm_mist_curtain): giant kelp fronds, bioluminescent ferns, and sea serpents |
| L5 | `black_pearl_and_scale_trade` (fl.L5.charter_salvage_and_treaty_trade, L3.serpent_coil_lagoons): serpent-scale mail, pearls, and truce safe-conducts |
| L6 | `mistveil_and_fleet_anchorage` (fl.L6.beachhead_anchorages, L3.serpent_coil_lagoons): kelpsail docks on the coastal terrace and Mistveil Village on the ridge |
| L7 | `truce_captains_and_storm_caste` (fl.L7.captains_factors_and_custodians, L6.mistveil_and_fleet_anchorage): Maormer storm-mages and foreign privateers |
| L8 | `cult_of_immortal_orgnum` (fl.L8.elder_homeland_myths, L7.truce_captains_and_storm_caste): worship of the undying wizard-king and his Coffer |
| L9 | `storm_spire_sabotage` (fl.L9.quarantine_and_awakening, L7.truce_captains_and_storm_caste): a mutinous fleet-lord charging a storm-spire to strike Vulkhel Guard |
| L10 | `sanctum_of_the_coiled_king` (fl.L10.wonder_sanctums, L1.volcanic_reef_isles, L9.storm_spire_sabotage): rising tidal chambers and lightning-rod sea grottoes |
| L11 | `armada_of_3e_110` (fl.L11.sundered_origins, L8.cult_of_immortal_orgnum): the War of the Isle and the storm that shattered Orgnum's fleet |

### 1.16 Thras — Coral Kingdom (`thras/coral_kingdom`, levels 28-45)

**World truth.** The Coral Kingdom of Thras is a ring of living, calcified pink-and-ivory coral spires rising seventy
blocks above a drowned volcanic caldera and Agonir Lagoon. Heavy phosphorescent salt-miasma hangs over bottomless blue
sinkholes that connect the lagoon to Summerset's abyssal vents. On the quarantined outer sandbar, licensed alchemy-ships
from Anvil and Lillandril trade with calculating Sload contractors for Sload soap, abyssal pearl-glass, and forbidden
necromantic reagents, while inside the flooded ziggurat of the Pillar of Thras a Sload cabal tends deep-vent plague
vats.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `agonir_coral_caldera`: living calcified coral towers around a drowned volcanic lagoon |
| L2 | `phosphorescent_brine_haze` (fl.L2.extreme_oceanic_regimes, L1.agonir_coral_caldera): dead-calm salt miasma and glowing night tides |
| L3 | `blue_hole_vents` (fl.L3.charter_sea_lanes, L1.agonir_coral_caldera): abyssal sinkholes linked to deep ocean currents |
| L4 | `anemone_and_thrall_reef` (fl.L4.alien_endemics, L3.blue_hole_vents): giant anemones, reef-vipers, and amphibious Sload |
| L5 | `alchemical_sandbar_exchange` (fl.L5.charter_salvage_and_treaty_trade, L4.anemone_and_thrall_reef): Sload soap, necromantic reagents, and pearl-glass |
| L6 | `quarantined_sandbar_camp` (fl.L6.beachhead_anchorages, L1.agonir_coral_caldera): outer expedition sandbar facing the living coral spires |
| L7 | `literal_contract_factors` (fl.L7.captains_factors_and_custodians, L5.alchemical_sandbar_exchange): Sload contractors and Anvil/Lillandril alchemists |
| L8 | `unsentimental_mastery` (fl.L8.elder_homeland_myths, L7.literal_contract_factors): cold calculus of memory, necromancy, and buoyancy |
| L9 | `plague_vat_cultivation` (fl.L9.quarantine_and_awakening, L3.blue_hole_vents): a Sload cabal brewing a new miasma strain in the deep vents |
| L10 | `the_pillar_of_thras` (fl.L10.wonder_sanctums, L1.agonir_coral_caldera, L9.plague_vat_cultivation): flooded coral ziggurat with buoyancy shafts and living coral doors |
| L11 | `sinking_by_all_flags` (fl.L11.sundered_origins, L9.plague_vat_cultivation): the 1E 2200 Thrassian Plague and the All-Flags Navy reprisal |

---

## 2. Micro-biomes

Each foreign land has a distinct 3–4 micro-biome mosaic (`MS21` at half-density):

| Land | Micro-biomes (keys) | Causes | Visual signature |
| --- | --- | --- | --- |
| **Atmora** | `glacier_barrens`, `rime_timber_stand`, `basalt_ice_shingle` | `atmora.L1.continental_ice_dome`, `atmora.L2.Great_Freeze` | Blue glacier walls, white rime-encrusted dead pines, black shingle |
| **Akavir** | `cloud_bamboo_forest`, `crimson_reed_plain`, `kamal_thaw_waste`, `golden_tiger_steppe` | `akavir.L1.*`, `akavir.L2.monsoon_and_thaw` | Towering green bamboo, red-earth terraces, volcanic ice, golden grass |
| **Yokuda** | `red_mesa_scarp`, `drowned_causeway_shoal`, `star_oasis` | `yokuda.L1.riven_archipelago`, `yokuda.L3.drowned_causeway_shoals` | Sheared red sandstone, shallow turquoise ruins, date-palm springs |
| **Pyandonea** | `mist_canopy_jungle`, `kelp_lagoon`, `storm_reef` | `pyandonea.L2.eternal_sea_mist`, `pyandonea.L3.serpent_lagoon` | Perpetual sea-fog, giant kelp fronds, serpent-bone arches |
| **Thras** | `living_coral_spires`, `bleached_bone_reef`, `abyssal_brine_pool` | `thras.L1.living_coral_atoll`, `thras.L3.abyssal_sinkholes` | Pink/ivory coral pillars, phosphorescent turquoise-black pools |

---

## 3. Map changes and geography

* **Zero settlements in water (`FINISH-PLAN.md` T2):** Verify and keep all five previously flagged foreign settlements
  (`pyandonean_fleet_anchorage`, `kjolvik`, `tiger_plains_village`, `tang_mo_harbor_village`, `mistveil_village`) pinned
  strictly onto dry land cells (`height >= sea + 4`) within the `Beyond` noise land mask (`layout_lint` 0 errors).
* **Island road connectivity:** Every foreign island that holds both a landing anchorage and an interior settlement or
  marquee dungeon gets an explicit trail or road in `named_roads.json` (`reach_lint` 100%).

---

## 4. Roads, travel network, fast travel

| Land | Charter Port (Tamriel) | Landing Node (Foreign) | Island Route (`named_roads.json`) |
| --- | --- | --- | --- |
| **Atmora** | `windhelm`, `solitude` | `kjolvik` | `atmoran_exodus_trail`: `kjolvik` > `jylkurfyk` > `foreground_totem_barrow` |
| **Akavir** | `ebonheart`, `tear`, `wayrest` | `tang_mo_harbor_village` | `akavir_treaty_road`: `tang_mo_harbor_village` > `ionith_ruins` > `tiger_plains_village` |
| **Yokuda** | `sentinel`, `stros_mkai` | `new_tava_anchorage` | `ansei_pilgrim_way`: `new_tava_anchorage` > `orichalc_tower_ruins` |
| **Pyandonea** | `alinor`, `woodhearth`, `vulkhel_guard` | `pyandonean_fleet_anchorage` | `mistveil_serpent_path`: `pyandonean_fleet_anchorage` > `mistveil_village` > `orgnums_coffer_sanctum` |
| **Thras** | `anvil`, `lillandril` | `thras_sandbar_camp` | `coral_atoll_causeway`: `thras_sandbar_camp` > `pillar_of_thras` |

---

## 5. Settlements

Each foreign land has **1–2 settlements** using its own bespoke cultural kit (`y_looks` >= 2 per land, no generic Tamriel
reskins):
* **Atmora:** `kjolvik` (expedition longhouse-and-palisade camp) + `jylkurfyk` (ruined cyclopean stone port).
* **Akavir:** `tang_mo_harbor_village` (curved bamboo-and-tile treaty port) + `tiger_plains_village` (stepped golden stone).
* **Yokuda:** `new_tava_anchorage` (fluted sandstone pilgrim port and star-court).
* **Pyandonea:** `pyandonean_fleet_anchorage` (kelpsail-and-serpent-bone docks) + `mistveil_village`.
* **Thras:** `thras_sandbar_camp` (quarantined expedition camp facing the living coral spires).

---

## 6. Dungeons, underground, landmarks

One **S/A-tier marquee dungeon** per land (`MARQUEE.md` foreign entries) plus half-density regional delves:

| Land | Marquee Dungeon | Signature Inhabitants | Mechanic / Wonder |
| --- | --- | --- | --- |
| **Atmora** | `jylkurfyk_great_barrow` | Frozen Atmoran draugr-kings, frost giants, ice wraiths | Unfreezing ancient hearth-braziers to thaw sealed cyclopean doors |
| **Akavir** | `citadel_of_ionith` | Tsaesci scale-guard, Kamal snow-raiders, Tenth Legion ghosts | Dual-layer ruin: Imperial 3E 288 siege works over a Tsaesci blood-forge |
| **Yokuda** | `vault_of_the_pankratosword` | Ansei spirit-guardians, brass star-automata | Aligning star-mirrors to part the flooded lagoon above the sunken sanctum |
| **Pyandonea** | `sanctum_of_the_coiled_king` | Maormer storm-callers, sea-serpent brood | Lightning-rod spires and rising tidal chambers |
| **Thras** | `the_pillar_of_thras` | Sload necromancers, abyssal thralls | Living coral doors and deep-vent buoyancy shafts |

---

## 7. Quests

Each land features a **5-quest Expedition Arc** launched from its Tamriel charter port (`F-HOOK` at the dock):
1. **Atmora — *The Last Ship of Jylkurfyk*:** Chartered from Windhelm (`atmora.L5.relic_and_stalhrim_expeditions`); rescue a trapped College expedition and lay the Frozen King to rest.
2. **Akavir — *The Eagle of Ionith*:** Chartered from Ebonheart (`akavir.L11.ionith_campaign`); recover the Tenth Legion's eagle standard and broker a truce between `tang_mo_harbor_village` and the Tsaesci factor.
3. **Yokuda — *The Unsheathed Star*:** Chartered from Sentinel (`yokuda.L9.forbidden_stroke`); walk the Ancestor Steps with an Ankh-Keeper and seal the Pankratosword fault.
4. **Pyandonea — *Beyond the Mist Veil*:** Chartered from Vulkhel Guard / Woodhearth (`pyandonea.L9.mutiny_of_the_truce_fleet`); infiltrate `pyandonean_fleet_anchorage` under a privateer token and sabotage the storm-armada's ritual spire.
5. **Thras — *The Coral Contract*:** Chartered from Anvil / Lillandril (`thras.L9.the_second_plague_vats`); descend into `the_pillar_of_thras` to destroy the deep-vent plague vats.

---

## 8. Components layout

| Component | File or system | Now | To do (phase) |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/foreign_lands.json` (5 land blocks, 10 regions) | in §1 | FL0 (V1) |
| Land placement & relief | `tamriel_vectors.json` / `provinces.py` (`Beyond` land mask check) | 5 pins checked | FL1 (V2) |
| Micro-biomes | `palettes.json` (3-4 micro-biomes per land) | 1-2 per region | FL1 (V3) |
| Places & roads | `places/foreign.json`, `named_roads.json` (5 island routes), `ports.json` | 1 road | FL2 (V4, V5) |
| Cultural kits & settlements | `styles/{atmoran,akaviri,yokudan,maormer,thrassian}.json` | fallback kits | FL3 (V6) |
| Marquee dungeons & rosters | `marquee.json` (5 foreign marquees), `region_spawns/foreign.json` | basic delves | FL4 (V7) |
| Expedition quests | `quests/expeditions.json` (5 x 5-quest arcs + port hooks) | stub quests | FL5 (V8) |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Scope | Half-density expedition template (`DESIGN-PRINCIPLES.md` 4.1) | Distinct cultural kits, port-to-landing sea charters, 1 marquee wonder per land | Treating foreign lands as empty procedural islands or full Tamriel provinces |
| Settlements | Existing anchor pins (`kjolvik`, `tang_mo_harbor_village`, `pyandonean_fleet_anchorage`) | Dry coastal terraces, island roads connecting landing to interior | Settlements spawning in water or using generic Nord/Imperial houses |

---

## 10. Floors, gates, and implementation phases

### 10.1 Half-density expedition targets (per foreign land)

| Land | Regions | Settlements | Dungeons (target, incl. 1 marquee) | Quests (target) | Named NPCs | Unmarked sites | `d_identity` | `y_looks` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| **Atmora** | 2 | 2 | 8 (1 S-marquee) | 10 | 16 | 2 | 1.0 | 2 |
| **Akavir** | 4 | 4 | 16 (1 S-marquee) | 18 | 36 | 4 | 1.0 | 3 |
| **Yokuda** | 2 | 2 | 8 (1 S-marquee) | 10 | 20 | 2 | 1.0 | 2 |
| **Pyandonea** | 1 | 2 | 6 (1 A-marquee) | 8 | 18 | 2 | 1.0 | 2 |
| **Thras** | 1 | 1 | 5 (1 A-marquee) | 8 | 12 | 2 | 1.0 | 2 |
| **Total** | **10** | **11** | **43** | **54** | **102** | **12** | **1.0** | **5 kits** |

### 10.2 Implementation phases (FL0–FL6, roadmap R12)

| Phase | Step | Scope | Gate |
| --- | --- | --- | --- |
| **FL0** | V1 | Compile `tamriel/world/foreign_lands.json` (5 lands, 10 regions) | `worldsheet_lint.py` 0 errors |
| **FL1** | V2, V3 | Verify all foreign pins on dry land (`>= sea + 4`); wire 16 micro-biomes across the 5 lands | `layout_lint` 0 water errors; `F-MICRO` pass |
| **FL2** | V4, V5 | Add 5 island roads (`named_roads.json`) and complete charter sea routes in `ports.json` | `reach_lint` 100%; every land reachable by ship |
| **FL3** | V6 | Build 5 bespoke cultural style kits and settlement plans (`y_looks` >= 2 per land) | `SET` half-density floor met |
| **FL4** | V7 | Implement the 5 foreign marquee dungeons and signature creature rosters | `d_identity` 1.0; 0 analyzer blockers |
| **FL5** | V8 | Implement the 5 Expedition Arcs (`quests/expeditions.json`) with Tamriel port hooks | `F-HOOK` 100% at charter ports |
| **FL6** | V9 | Full DESIGN-PRINCIPLES 5.5 audit at half-density across all 10 foreign regions | All foreign parity gates pass |

