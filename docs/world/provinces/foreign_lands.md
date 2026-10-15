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

Data home: `skycraft/tamriel/world/foreign_lands.json` (MS22): five land blocks (`atmora`, `akavir`, `yokuda`,
`pyandonea`, `thras`) covering all 10 foreign regions in `regions.json`.

| Land (id) | Regions (`regions.json`) | Charter Port in Tamriel | Signature Culture / Kit | Level band |
| --- | --- | --- | --- | --- |
| **Atmora** (`atmora`) | `atmora/ice_waste`, `atmora/frozen_forest` | Windhelm / Solitude | Ancient Atmoran cyclopean stone & rime-timber (`atmoran_frost`) | 28-45 |
| **Akavir** (`akavir`) | `akavir/tsaesci`, `akavir/kamal`, `akavir/tang_mo`, `akavir/ka_po_tun` | Ebonheart / Tear / Wayrest | Serpentine lacquer & bronze (`tsaesci`), ice-bastion (`kamal`), bamboo pavilion (`tang_mo`), tiger-stepped temple (`ka_po_tun`) | 30-50 |
| **Yokuda** (`yokuda`) | `yokuda/yokudan_desert`, `yokuda/sunken_isles` | Sentinel / Stros M'Kai | Old Na-Totambu fluted sandstone & star-observatory domes (`yokudan_archaic`) | 25-42 |
| **Pyandonea** (`pyandonea`) | `pyandonea/maormer_isles` | Alinor / Woodhearth | Sea-serpent bone, coral-stone, and translucent kelpsail (`maormer_kelp`) | 25-42 |
| **Thras** (`thras`) | `thras/coral_kingdom` | Anvil / Lillandril | Living calcified coral spires and abyssal brine pools (`thrassian_coral`) | 28-45 |

### 1.1 Atmora (`atmora`: `ice_waste`, `frozen_forest`)

**World truth paragraph.** Atmora—Altmora, the "Elder Wood" of myth—is a continent locked in terminal glaciation north
of the Sea of Ghosts. No living hearth burns inland; what remains are petrified frost-forests, cyclopean dragon-totem
halls half-swallowed by blue glacier tongues, and the frozen dead of the last ships that failed to sail south with
Ysgramor. Only seasonal salvage crews and dragon-cult scholars camp on its southern shingle.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `continental_ice_dome`: a mile-high glacial shield descending to basalt sea-cliffs. `petrified_wood_valleys` (L1.continental_ice_dome): sheltered fjords where whole forests froze standing |
| L2 Climate | `Great_Freeze` (L1.continental_ice_dome): permanent sub-zero whiteout, green-and-violet auroras, catabatic gales off the ice cap |
| L3 Water | `pack_ice_leads` (L2.Great_Freeze): shifting sea-ice channels that open only in mid-summer. `subglacial_brine` (L1.continental_ice_dome): unfrozen hypersaline rivers beneath the glacier |
| L4 Ecology | `rime_timber_stand` (L1.petrified_wood_valleys, L2.Great_Freeze): frost-petrified pines, ice wraiths, ancient frost trolls. `glacier_barrens` (L1.continental_ice_dome): snow whales offshore, frost giants, white sabre cats |
| L5 Economy | `relic_and_stalhrim_expeditions` (L3.pack_ice_leads, L1.continental_ice_dome): no native trade; Windhelm charter camps salvage Atmoran bronze, dragon-masks, and pure stalhrim |
| L6 Settlement | `kjolvik_anchorage` (L3.pack_ice_leads, L5.relic_and_stalhrim_expeditions): a fortified seasonal expedition camp on dry basalt above the fjord (`kjolvik`). `jylkurfyk_ruins` (L1.petrified_wood_valleys): the ruined shipyard-city of the exodus |
| L7 Society | `charter_crews_and_scholars` (L6.kjolvik_anchorage): Nord captains, College chroniclers, and grave-robbers bound by winter-ration law |
| L8 Belief | `totem_ancestors` (L6.jylkurfyk_ruins): the nine animal totems (Hawk, Wolf, Snake, Moth, Owl, Whale, Bear, Fox, Dragon) in their pre-Imperial form |
| L9 Conflict | `frozen_king_awakening` (L8.totem_ancestors): an unthawed dragon-priest in `jylkurfyk` calling the frost-dead against the salvage camp |
| L10 Underground | `ice_entombed_meadhalls` (L1.continental_ice_dome, L8.totem_ancestors): giant-scale stone halls under the ice sheet. `subglacial_ice_caverns` (L3.subglacial_brine) |
| L11 History | `the_return_exodus`: Ysgramor's departure; the last fleet of 1E 68; the continent's final freeze |

### 1.2 Akavir (`akavir`: `tsaesci`, `kamal`, `tang_mo`, `ka_po_tun`)

**World truth paragraph.** Akavir, the "Dragon Land" across the Padomaic, is a continent of four warring realms where
men were long ago absorbed or ruled by serpent-folk, snow-demons, monkey-islanders, and tiger-dragons. Outsiders are
permitted only in a single treaty-harbour in `tang_mo` and a guarded factor's compound in `tsaesci`; beyond those gates
lie bamboo cloud-mountains, red-scale fortresses, and the thawing passes of Kamal.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `bamboo_karst_pillars` (`tang_mo`): vertical limestone towers and mist valleys. `serpent_river_plains` (`tsaesci`): terraced red-earth basins. `kamal_hell_glaciers` (`kamal`): jagged volcanic-and-ice peaks in the north. `golden_mesa_steppe` (`ka_po_tun`): high sun-baked plateaus |
| L2 Climate | `monsoon_and_thaw`: warm monsoon mists in `tang_mo`, dry heat in `ka_po_tun`, and the annual spring thaw that unleashes `kamal` armies |
| L3 Water | `lacquer_canals` (`tsaesci`, L1.serpent_river_plains): stepped irrigation and barge canals. `island_straits` (`tang_mo`, L1.bamboo_karst_pillars) |
| L4 Ecology | `cloud_bamboo_forest` (L1.bamboo_karst_pillars): giant bamboo, blossoms, Tang Mo rangers. `crimson_reed_plain` (L1.serpent_river_plains): serpent-constructs, scale-hounds. `thaw_waste` (L1.kamal_hell_glaciers): frost-demons, ice-beetles |
| L5 Economy | `treaty_silk_and_steel` (L3.lacquer_canals, L3.island_straits): Akaviri folded-steel blades, lacquerware, and spice traded under strict port charter |
| L6 Settlement | `tang_mo_harbor_village` (L3.island_straits): open treaty port on dry coastal terrace. `tsaesci_treaty_post` (L3.lacquer_canals): walled factor's enclave. `tiger_plains_village` (`ka_po_tun`): high steppe shrine-town. `kamal_watch_camp` (`kamal`) |
| L7 Society | `four_court_protocol` (L6.tsaesci_treaty_post, L6.tang_mo_harbor_village): outsiders carry bronze tally-seals; breaking quarantine means immediate expulsion |
| L8 Belief | `dragon_tiger_ascension` (`ka_po_tun`): Tosh Raka's apotheosis. `immortal_coil` (`tsaesci`): blood-and-memory lineage |
| L9 Conflict | `spring_thaw_front` (`kamal`, `tang_mo`): Kamal raiders testing the Tang Mo island passes while Tsaesci envoys play both sides |
| L10 Underground | `serpent_scale_citadels` (`tsaesci`): subterranean blood-temples and blade-forges. `imperial_disaster_tombs` (L11.ionith_campaign): the lost vaults of Uriel V's Tenth Legion |
| L11 History | `reman_invasion` (1E 2703): the Pale Pass march. `potentate_years` (2E 1-430). `ionith_campaign` (3E 288): Uriel V's doomed expedition to Septimia and Ionith |

### 1.3 Yokuda (`yokuda`: `yokudan_desert`, `sunken_isles`)

**World truth paragraph.** Yokuda is the shattered archipelago of a sunken continent west of Hammerfell: flat-topped
red mesas, drowned marble causeways visible beneath clear turquoise shoals, and star-observatories left from before the
Pankratosword split the land in 1E 792. Redguard pilgrims, sword-singers, and Sentinel chart-captains sail here to walk
the Ancestor Steps.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `riven_archipelago`: sheared sandstone mesas and basalt faults dropping straight into submerged city shelves (`sunken_isles`) |
| L2 Climate | `trade_wind_arid` (L1.riven_archipelago): hot dry trade winds, crystalline night skies for star-reading |
| L3 Water | `drowned_causeway_shoals` (L1.riven_archipelago): shallow turquoise lagoons over sunken streets; oasis cisterns in the mesa rock |
| L4 Ecology | `mesa_scrub_and_oasis` (L2.trade_wind_arid): date palms, desert lynx, dunerippers, bone-walkers in the salt flats |
| L5 Economy | `pilgrim_and_chart_trade` (L3.drowned_causeway_shoals): memory-stones, orichalcum salvage, and star-charts |
| L6 Settlement | `new_tava_anchorage` (`yokudan_desert`): Redguard pilgrim port and caravanserai on a high dry mesa shelf. `riven_shoal_camp` (`sunken_isles`) |
| L7 Society | `ankh_keepers_and_singers` (L6.new_tava_anchorage): order of Redguard custodians who guard the sunken tombs from plunderers |
| L8 Belief | `way_of_the_sword_and_stars` (L2.trade_wind_arid): the Shehai (spirit sword) and the constellations of the Warrior, Thief, and Mage |
| L9 Conflict | `forbidden_stroke` (L8.way_of_the_sword_and_stars, L11.sinking_of_yokuda): a rogue Ansei seeker trying to unseal the vault where the Pankratosword was struck |
| L10 Underground | `na_totambu_star_tombs` (L1.riven_archipelago): half-submerged sandstone-and-brass sword-singer vaults |
| L11 History | `sinking_of_yokuda` (1E 792): the Hiradirge, the Pankratosword, and the Ra Gada warrior-wave to Hammerfell |

### 1.4 Pyandonea (`pyandonea`: `maormer_isles`)

**World truth paragraph.** South of Summerset beyond a wall of perpetual warm fog lies Pyandonea, the veiled jungle-and-reef
archipelago of the Maormer (Sea Elves) and their Immortal King Orgnum. Giant kelp forests, bioluminescent cliffs, and
harbours lashed from sea-serpent bone shelter the fleets that raid Auridon every generation.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `veil_reef_archipelago`: volcanic jungle peaks ringed by barrier reefs and deep sea-trenches |
| L2 Climate | `eternal_sea_mist` (L1.veil_reef_archipelago): warm tropical rain and a permanent mist-curtain along the northern approaches |
| L3 Water | `serpent_lagoon` (L1.veil_reef_archipelago): deep sheltered fleet anchorages where sea serpents coil |
| L4 Ecology | `kelp_and_mist_jungle` (L2.eternal_sea_mist, L3.serpent_lagoon): giant canopy ferns, strangler vines, sea serpents, storm-ateliers |
| L5 Economy | `privateer_and_pearl_trade` (L3.serpent_lagoon): black pearls, serpent-scale mail, and neutral privateer safe-conducts |
| L6 Settlement | `pyandonean_fleet_anchorage` (`4280, 14520` moved onto firm coastal terrace): outer truce-harbour (`mistveil_village` on the inner ridge) |
| L7 Society | `storm_caste_and_truce_captains` (L6.pyandonean_fleet_anchorage): Maormer storm-callers and foreign privateers holding temporary truce-tokens |
| L8 Belief | `orgnum_and_the_coiled_king` (L3.serpent_lagoon): worship of the undying serpent-monarch and the lost Aldmeris claim |
| L9 Conflict | `mutiny_of_the_truce_fleet` (L7.storm_caste_and_truce_captains): a rogue fleet-lord preparing a storm-ritual against Summerset's open ports |
| L10 Underground | `serpent_rib_grottoes` (L1.veil_reef_archipelago, L3.serpent_lagoon): tidal sea-caves and lightning-spire sanctums |
| L11 History | `aldmeris_schism`: Orgnum's banishment; `war_of_the_isle` (3E 110): the Potema/Antiochus storm that broke the Maormer armada |

### 1.5 Thras (`thras`: `coral_kingdom`)

**World truth paragraph.** West of Summerset and south of the Iliac Bay rises the Coral Kingdom of Thras: an atoll of
living, calcified pink-and-bone coral spires, brine sinkholes, and sacrificial lagoons ruled by the amphibious Sload.
Only licensed alchemy-ships and Bend'r-mahk wreck-hunters dare anchor on its outer sandbar.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology | `living_coral_atoll`: calcified coral towers (30-70 blocks) growing over a drowned volcanic caldera (`agonir_lagoon`) |
| L2 Climate | `brine_miasma` (L1.living_coral_atoll): heavy salt-haze, eerie dead-calm air, phosphorescent night tides |
| L3 Water | `abyssal_sinkholes` (L1.living_coral_atoll): bottomless blue holes connecting the central lagoon to Summerset's abyssal vents |
| L4 Ecology | `bleached_and_living_reef` (L1.living_coral_atoll, L3.abyssal_sinkholes): giant anemones, reef-vipers, zombie thralls, Sload |
| L5 Economy | `sload_soap_and_alchemical_contraband` (L4.bleached_and_living_reef): forbidden necromantic reagents and pearl-glass traded at the outer sandbar |
| L6 Settlement | `thras_sandbar_camp`: a quarantined trader/expedition anchorage on the outer reef; Sload coral-spires around the central lagoon |
| L7 Society | `calculating_contractors` (L5.sload_soap_and_alchemical_contraband): Sload speak with outsiders purely through slow, literal contracts |
| L8 Belief | `efficiency_and_necromancy`: unsentimental mastery of flesh, memory, and deep-sea currents |
| L9 Conflict | `the_second_plague_vats` (L3.abyssal_sinkholes): a Sloadcabal cultivating a new strain of Thrassian miasma in the deep vents |
| L10 Underground | `drowned_coral_ziggurats` (L1.living_coral_atoll, L3.abyssal_sinkholes): flooded bio-luminescent chambers beneath the atoll |
| L11 History | `thrassian_plague` (1E 2200) and the `all_flags_navy` that sank Thras the first time |

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

