# Black Marsh (Argonia): province plan

Status: plan, no code changed; all ten sections written (2026-10-14). Feeds roadmap phase **R8**
(`docs/prompts/R8-black-marsh.md`, steps V1-V9; phases BM0-BM8 in section 10.3). Governing rules:
`docs/world/DESIGN-PRINCIPLES.md` (world building is king, the eleven-layer causal sheet, G1-G12, measured Skyrim
floors 4.1-4.9, MS21, MS22). Companion specs: `docs/world/SCALE-AND-GEOGRAPHY.md` 3, 5.9, 6.2, 6.3, 7, 8.9;
`docs/MARQUEE.md` (Black Marsh block); `docs/LORE-WEB.md` (`bm_1`-`bm_4`). Legal line: names, relative positions,
counts and lore facts only; every sentence here is our own.

**Identity in one line.** A drowned green continent within a continent: not a mountain province flattened by mistake,
but a living hydrographic maze of braided channels, oxbow lakes, cypress-and-mangrove domes, Hist groves on low clay
rises, sunken stone step-pyramids (`xanmeer`), stilt towns, and raised causeway roads where boats matter more than
horses and the trees remember what the stones forgot.

**Why Black Marsh scores 40 today.** `parity.py` scores Black Marsh at 40 across its 6 regions (38.0 km2): relief is
almost flat (`p50` 1, `p90` 2, ruggedness 0.4), settlements use generic fallback kits, dungeon identity is low, and
Arena's uniform town grid was placed on dry noise without channels or causeways. Here height is **not** the primary
amplifier—water structure, canopy layers, Hist rises, and raised stone/timber architecture are.

---

## 1. World model sheet

Data home: `nirncraft/tamriel/world/black_marsh.json` (MS22): one province block (`bm`) and six region sub-sheets
(`shadowfen`, `stormhold`, `helstrom`, `gideon`, `lilmoth`, `murkmire`).

| Region (id) | km2 | Seat | Character | Level band |
| --- | ---: | --- | --- | --- |
| Shadowfen (`shadowfen`) | 6.4 | Forsaken Hamlet / Alten Corimont | North-west brackish border mire and former slaver marches below Morrowind | 15-30 |
| Stormhold (`stormhold`) | 6.8 | Stormhold | Northern basalt-and-Ayleid upland rim, Dres border watch, raised stone causeways | 16-32 |
| Gideon (`gideon`) | 6.2 | Gideon | West-bank Nibenay reed plains, old Imperial/Colovian fort-towns, plantation ruins | 14-28 |
| Thornmarsh / Archon (`helstrom`) | 7.9 | Helstrom (interior) & Thorn / Archon (east) | The impenetrable Root-Heart mire around Helstrom and the east-coast slaver ports | 20-38 |
| Lilmoth (`lilmoth`) | 5.6 | Lilmoth | Southern tidal delta on Topal Bay, merchant smugglers, drowned xanmeer | 15-30 |
| Murkmire (`murkmire`) | 5.1 | Soulrest | Deep south-east mangrove shelf, bright-throat tribes, ancient Sithis-and-Hist temples | 18-35 |
| **Province** | **38.0** | **Helstrom** (sacred heart) / **Stormhold** (northern gate) | 6 regions | 14-38 |

### 1.1 Province sheet (`bm`)

**World truth paragraph.** Black Marsh—Argonia to its people—is a vast subtropical basin of slow rivers, tidal deltas,
peat domes, and flooded root-tangles fed by the Niben's spill and the warm rains of the Padomaic Ocean. Only its
north-western rim rises into firm basalt and limestone; everywhere else dry ground consists of natural levees, shell
middens, ancient stone xanmeer platforms, and clay mounds raised around sentient Hist trees. Before the Duskfall,
Saxhleel priest-kings built calendar pyramids and stone canals; after the Duskfall they abandoned stone for reed, mud,
and living root, listening to the Hist sap while empires and Dunmer slavers broke their teeth on the outer border and
never reached Helstrom at the centre.

| Layer | Fields (id: what, cites) |
| --- | --- |
| L1 Geology and landform | `alluvial_basin`: a 38 km2 low-gradient basin of silt, peat, and drowned limestone karst. `nw_border_uplands`: basalt and sandstone ridges (30-80 blocks) along the Cyrodiil and Morrowind frontier. `hist_clay_rises`: natural and artificial mounds (8-20 blocks) that stay above flood stage. `xanmeer_foundations`: pre-Duskfall stepped stone platforms half-sunk in peat. `east_barrier_spits`: sandy barrier islands, coral shoals, and tidal coves on the Padomaic coast. `lilmoth_delta`: a multi-mouth silt fan emptying into Topal Bay |
| L2 Climate | `monsoon_humidity` (L1.alluvial_basin): warm, wet, mist-heavy air year-round with afternoon downpours. `spore_and_marsh_haze` (L1.alluvial_basin, L2.monsoon_humidity): luminous night fog and heavy pollen over stagnant backwaters. `padomaic_squalls` (L1.east_barrier_spits): coastal gales that push salt water up the eastern estuaries. `upland_breeze` (L1.nw_border_uplands): drier air on the Stormhold and Gideon border hills |
| L3 Water | `braided_channels` (L1.alluvial_basin, L2.monsoon_humidity): slow, interconnected river arms, oxbows, and root-tunnels navigable by flatboat. `root_water_network` (L1.hist_clay_rises, L3.braided_channels): submerged hollow root-tubes through which native swimmers move between pools. `hist_sap_springs` (L1.hist_clay_rises): amber sap-fed pools around the roots of elder Hist trees. `brackish_estuaries` (L1.lilmoth_delta, L2.padomaic_squalls): salt-and-fresh mixing zones along the south and east coasts. `blackwood_spill` (L1.nw_border_uplands): Niben overflow entering the western reed beds |
| L4 Ecology and micro-biomes | `hist_grove` (L1.hist_clay_rises, L3.hist_sap_springs): giant buttressed Hist trees, bioluminescent fungi, sacred silence. `mangrove_tangle` (L3.brackish_estuaries): stilt-rooted mangroves, mudskippers, haj mota, swamp jellyfish. `cypress_mire` (L3.braided_channels, L2.spore_and_marsh_haze): knees of bald cypress, hanging moss, wamasu, hackwings, fleshflies. `reed_savanna` (L3.blackwood_spill): tall sawgrass, lily mats, kagouti, bog dogs. `sinkhole_fen` (L1.alluvial_basin): acid peat pools, carnivorous pitcher flora, Voriplasm oozes. `upland_fern_wood` (L1.nw_border_uplands, L2.upland_breeze): drier fern-and-stone wood on the northern rim |
| L5 Resources and economy | `reed_and_mud_craft` (L4.reed_savanna, L4.cypress_mire): woven reed, shell-lime, bone, and cured hide replacing iron that rusts in a season. `alchemical_reagents` (L4.sinkhole_fen, L4.hist_grove): rare marsh toxins, luminous spores, hackwing plumes, wamasu scales. `river_barge_and_punt` (L3.braided_channels): flatboat transport, reed rafts, and guide fees. `border_timber_and_rice` (L4.upland_fern_wood, L3.blackwood_spill): bog-iron, wetland grain, and timber traded at Gideon and Stormhold. `coastal_smuggling` (L1.east_barrier_spits, L1.lilmoth_delta): spice, contraband, and salvaged Imperial cargo at Lilmoth and Archon |
| L6 Settlement pattern | `outer_ring_ports` (L1.nw_border_uplands, L1.lilmoth_delta, L5.coastal_smuggling): stone-and-timber hybrid towns (Stormhold, Gideon, Lilmoth, Thorn, Archon, Soulrest) sit on the outer border and coast where foreigners can reach them. `inner_hist_villages` (L1.hist_clay_rises, L4.hist_grove): inside the mire, every village is a ring of mud-and-reed huts and stilt walkways around a central Hist tree. `helstrom_sanctuary` (L3.braided_channels, L4.cypress_mire): the sacred interior capital reachable only by water and causeway. `raised_causeways` (L1.xanmeer_foundations, L3.braided_channels): ancient stone causeways and log corduroy bridges linking the outer seats |
| L7 Society | `hist_communion` (L4.hist_grove, L6.inner_hist_villages): every tribe takes its speech, craft, and role from its Hist tree; the Tree-Minder interprets the sap. `outer_vs_inner_tribes` (L6.outer_ring_ports, L6.inner_hist_villages): assimilated Lukiul Argonians in the border towns vs traditional Saxhleel tribes (Bright-Throat, Dead-Water, Kota-Vimleel, Naga) in the interior. `shadowscale_order` (L4.hist_grove, L7.hist_communion): those hatched under the Shadow sign, trained in silence and bound to both Sithis and the Dark Brotherhood. `freed_and_scarred` (L1.nw_border_uplands, L6.outer_ring_ports): former captives of House Dres holding the northern marches |
| L8 Culture and belief | `the_hist_and_sithis` (L7.hist_communion, L1.xanmeer_foundations): the Hist is the living memory of the tribe; Sithis is change, honoured by letting stone ruins sink back into the mud rather than rebuilding them after Duskfall. `hist_communion` (L3.hist_sap_springs, L7.hist_communion): sap-communion rites binding each clutch to its tree. `sap_rites_and_naming` (L3.hist_sap_springs): naming day at the tree, sap-licking festivals, and reincarnation through the roots. `xanmeer_taboo` (L1.xanmeer_foundations, L8.the_hist_and_sithis): old stone pyramids are respected as reminders of hubris, entered only when the Tree-Minder bids |
| L9 Politics and conflict | `an_xileel_and_imperial_border` (L6.outer_ring_ports, L7.outer_vs_inner_tribes): traditionalist An-Xileel wardens vs Imperial charter-factors and Lukiul merchants in Gideon and Lilmoth. `dres_slaver_remnants` (L7.freed_and_scarred, L6.outer_ring_ports): rogue slaver rings, smuggling stockades, and border raids in Shadowfen and Thornmarsh. `sickening_hist` (L3.hist_sap_springs): a rogue mining/alchemical syndicate tapping a deep Hist root-vein for black sap. `tribal_water_feuds` (L7.outer_vs_inner_tribes): Dead-Water warriors and Bright-Throat singers contesting a sunken calendar stone |
| L10 The hidden and the underground | `sunken_xanmeer` (L1.xanmeer_foundations, L8.xanmeer_taboo): flooded multi-tier step-pyramids with water-gate puzzles, stone vipers, and pre-Duskfall relics. `root_caverns` (L3.root_water_network, L4.hist_grove): amber-lit caverns beneath elder Hist trees guarded by sap-bound constructs and wamasu. `ayleid_barsaebic_ruins` (L1.nw_border_uplands): Barsaebic Ayleid vaults under Stormhold and Gideon. `slaver_stockades_and_pens` (L9.dres_slaver_remnants): hidden subterranean and mangrove-camouflaged holding pens. `kothringi_ghost_ships` (L1.lilmoth_delta, L3.brackish_estuaries): silver-skinned undead and plague-era hulks in the southern estuaries |
| L11 History layers | `pre_duskfall_empire` (L1.xanmeer_foundations, L10.sunken_xanmeer): the era of stone xanmeer, astronomy, and blood-oracles before the Hist called the Saxhleel back to simplicity. `barsaebic_exile` (L1.nw_border_uplands, L10.ayleid_barsaebic_ruins): Ayleids who fled Cyrodiil into northern Black Marsh and built Stormhold's under-works. `imperial_blackwater_war` (L6.raised_causeways, L9.an_xileel_and_imperial_border): Reman and Septim legions burning roads into the outer swamp and building border forts that sank a foot a decade. `knahaten_flu` (L1.lilmoth_delta, L10.kothringi_ghost_ships): the 2E 560 plague that wiped out the silver-skinned Kothringi and closed the ports to outsiders. `dres_slave_raids` (L7.freed_and_scarred, L9.dres_slaver_remnants): centuries of border raiding across Shadowfen and Thornmarsh |

### 1.2 Shadowfen (`shadowfen`, 6.4 km2, levels 15-30)

**World truth.** Shadowfen is the north-western border mire where Morrowind's ash-runoff meets Argonia's deep peat: a
scarred frontier of broken slaver chains, mud-stilt villages, sunken step-pyramids, and vigilant border-tribes watching
every road down from Deshaan. Here former Dres captives and Tree-Minders guard the Percolating Mire and Alten Corimont
against smuggling rings that still try to spirit hatchlings north across the hills. Beneath the pitcher bogs and
cypress knees lie Barsaebic Ayleid ruins and the twin-peaked xanmeer of Ten-Maur-Wolk, where water-locks still answer
the old calendar.

| Layer | Fields |
| --- | --- |
| L1 | `border_karst`: limestone sinkholes and low basalt spurs along the Morrowind march. `mire_peat_domes`: raised acid peat beds between the channels |
| L2 | `ash_tinged_mist` (bm.L2.spore_and_marsh_haze, L1.border_karst): warm marsh fog carrying fine grey ash from the northern hills |
| L3 | `ash_stained_fen` (bm.L3.braided_channels, L1.border_karst): slow dark channels draining the northern border. `percolating_springs` (bm.L3.hist_sap_springs, L1.mire_peat_domes): warm bubbling mire pools |
| L4 | `pitcher_bog` (bm.L4.sinkhole_fen, L1.mire_peat_domes): carnivorous flora, fleshflies, and bog dogs. `border_cypress` (bm.L4.cypress_mire, L3.ash_stained_fen): moss-hung cypress and wamasu lairs |
| L5 | `toxin_and_reed_harvest` (bm.L5.alchemical_reagents, L4.pitcher_bog): marsh reagents, reed basketry, and bog-iron spears |
| L6 | `percolating_mire_posts` (bm.L6.inner_hist_villages, L3.percolating_springs): mud-and-stilt villages around border Hist trees, led by Alten Corimont and Forsaken Hamlet |
| L7 | `chain_breaker_clans` (bm.L7.freed_and_scarred, L6.percolating_mire_posts): freed slave families and vigilant marsh scouts |
| L8 | `shackle_cairns` (bm.L8.the_hist_and_sithis, L7.chain_breaker_clans): rusted slaver irons hung on boundary trees as a warning and memorial |
| L9 | `broken_chain_watch` (bm.L9.dres_slaver_remnants, L7.chain_breaker_clans): border wardens hunting rogue slaver factors and informants |
| L10 | `ten_maur_wolk_xanmeer` (bm.L10.sunken_xanmeer, L1.border_karst): sunken step-pyramids and the Loriasel Ayleid-Hist vault. `slaver_hideouts` (bm.L10.slaver_stockades_and_pens, L9.broken_chain_watch): concealed caves in the northern karst |
| L11 | `liberation_marches` (bm.L11.dres_slave_raids, L8.shackle_cairns): the border uprisings that burned the northern slave pens |

### 1.3 Stormhold (`stormhold`, 6.8 km2, levels 16-32)

**World truth.** Built atop the only high basalt and limestone shelf on the northern frontier, Stormhold wraps Argonian
mud-and-timber compounds around the half-sunken white-stone spires of a Barsaebic Ayleid citadel. It is the northern
gateway of Black Marsh, where raised stone causeways meet the Morrowind and Cyrodiil roads and where caravans trade
northern iron and wetland grain for marsh reagents, bog-timber and cured hides. Tree-Minders share the market ring with
Dunmer and Imperial factors, while beneath the city's guildhalls and plazas, flooded Ayleid crystal vaults and
Shadowscale training sanctums run deep into the living rock.

| Layer | Fields |
| --- | --- |
| L1 | `stormhold_shelf`: a firm basalt and limestone ridge rising above the northern fens. `sunken_plinths`: Ayleid white-stone foundations subsiding into the clay |
| L2 | `northern_scarp_breeze` (bm.L2.upland_breeze, L1.stormhold_shelf): clearer upland air above the marsh haze |
| L3 | `upper_causeway_falls` (bm.L3.braided_channels, L1.stormhold_shelf): stepped cascades where upland streams drop into the mire |
| L4 | `basalt_fern_wood` (bm.L4.upland_fern_wood, L2.northern_scarp_breeze): tree-ferns, kagouti, and durzogs along the northern rocks |
| L5 | `border_caravan_exchange` (bm.L5.border_timber_and_rice, L3.upper_causeway_falls): iron imports traded for marsh herbs, hides, and bog-timber |
| L6 | `stormhold_ring_over_ruin` (bm.L6.outer_ring_ports, L1.sunken_plinths): Argonian timber-and-mud compounds crowning Ayleid stone walls |
| L7 | `tree_minders_and_factors` (bm.L7.outer_vs_inner_tribes, L5.border_caravan_exchange): Saxhleel elders sharing the market ring with Dunmer and Imperial factors |
| L8 | `shadow_sign_vigil` (bm.L8.the_hist_and_sithis, bm.L7.shadowscale_order): shrines to Sithis and the Hist in the under-plaza |
| L9 | `caravan_toll_dispute` (bm.L9.an_xileel_and_imperial_border, L5.border_caravan_exchange): tension over foreign charter privileges and relic smuggling |
| L10 | `barsaebic_undercity` (bm.L10.ayleid_barsaebic_ruins, L1.sunken_plinths): the Sunken Stormhold Vaults and crystal-lit Ayleid labyrinths |
| L11 | `ayleid_foundation` (bm.L11.barsaebic_exile, L10.barsaebic_undercity): the Barsaebic Ayleid flight from Cyrodiil and the old Imperial prison-keep |

### 1.4 Gideon (`gideon`, 6.2 km2, levels 14-28)

**World truth.** Gideon is the western reed-plain bordering Cyrodiil's Blackwood: a country of wide sawgrass prairies,
Niben overflow marshes, half-drowned Imperial causeways, and fortified stone manors left from the Colovian and Septim
occupations. Its walled seat of Gideon and the ruined penal fortress of Blackrose stand on the last firm silt levees
before the deep interior swamp begins. Here assimilated Lukiul townsfolk, Imperial charter-traders, and An-Xileel
traditionalists contest every bridge, punt-ferry and warehouse, while weathered chapels stand beside living Hist pools
and blood-root cults and bog hounds haunt the sunken estates.

| Layer | Fields |
| --- | --- |
| L1 | `western_levee_plain`: broad silt levees and low clay islands along the Blackwood frontier. `blackrose_basin`: a subsided marsh depression in the south-west |
| L2 | `niben_valley_warmth` (bm.L2.monsoon_humidity, L1.western_levee_plain): warm river-valley rains and summer thunderheads |
| L3 | `niben_overflow_marshes` (bm.L3.blackwood_spill, L1.western_levee_plain): seasonal flood sheets and reed-choked bayous |
| L4 | `sawgrass_prairie` (bm.L4.reed_savanna, L3.niben_overflow_marshes): tall sawgrass, bog dogs, and marsh nix |
| L5 | `levee_rice_and_punt_trade` (bm.L5.border_timber_and_rice, bm.L5.river_barge_and_punt, L3.niben_overflow_marshes): wetland rice, reed rafts, and Blackwood border trade |
| L6 | `gideon_walled_seat` (bm.L6.outer_ring_ports, bm.L6.raised_causeways, L1.western_levee_plain): Imperial stone lower walls topped with Argonian stilt-galleries |
| L7 | `lukiul_townsfolk` (bm.L7.outer_vs_inner_tribes, L6.gideon_walled_seat): Imperialised Argonians, Colovian factor descendants, and marsh guides |
| L8 | `chapel_and_root_shrine` (bm.L8.sap_rites_and_naming, L7.lukiul_townsfolk): weathered Imperial chapels standing beside living Hist sap-pools |
| L9 | `lukiul_and_an_xileel` (bm.L9.an_xileel_and_imperial_border, L7.lukiul_townsfolk): An-Xileel wardens pushing to close the western charter houses |
| L10 | `old_governor_vaults` (bm.L10.ayleid_barsaebic_ruins, L1.blackrose_basin, L9.lukiul_and_an_xileel): Blackrose Prison's flooded dungeons and drowned Colovian estates |
| L11 | `blackwater_forts` (bm.L11.imperial_blackwater_war, L6.gideon_walled_seat): the Second and Third Era legion campaigns that built the western causeway |

### 1.5 Thornmarsh and Helstrom (`helstrom`, 7.9 km2, levels 20-38)

**World truth.** Two worlds share this central and eastern region: along the Padomaic barrier spits stand Thorn and
Archon, hybrid ports of call for coastal traders and former slaver houses; inland lies the impenetrable root-labyrinth
surrounding Helstrom, the sacred capital no outsider army has ever taken. In Helstrom, giant interlocking Hist roots
form living bridges over dark water, and the eldest Tree-Minders commune with the root-heart of Argonia. When a rogue
syndicate taps a deep root-vein in the east, the central trees stir and summon the interior tribes.

| Layer | Fields |
| --- | --- |
| L1 | `helstrom_root_dome`: the raised clay-and-root core of the interior basin. `thorn_coastal_spits`: eastern barrier beaches and limestone bluffs |
| L2 | `interior_twilight_haze` (bm.L2.spore_and_marsh_haze, L1.helstrom_root_dome): perpetual golden spore-mist under the high canopy |
| L3 | `labyrinth_channels` (bm.L3.root_water_network, L1.helstrom_root_dome): shifting root-tunnels and dark rivers that turn back unguided boats |
| L4 | `elder_hist_canopy` (bm.L4.hist_grove, bm.L4.cypress_mire, L2.interior_twilight_haze): 65-block Elder Hist domes, Naga patrols, and wamasu |
| L5 | `sacred_sap_and_east_port_trade` (bm.L5.alchemical_reagents, bm.L5.coastal_smuggling, L1.thorn_coastal_spits): coastal shipping at Thorn and Archon; pure reed-and-bone craft inland |
| L6 | `helstrom_ring_and_thorn_port` (bm.L6.helstrom_sanctuary, bm.L6.outer_ring_ports, L3.labyrinth_channels): Helstrom's concentric mud-dome rings and the eastern harbour towns |
| L7 | `root_heart_tribes` (bm.L7.hist_communion, L6.helstrom_ring_and_thorn_port): elder Tree-Minders, Naga-Kur guardians, and Kota-Vimleel speakers |
| L8 | `sap_naming_rite` (bm.L8.sap_rites_and_naming, L4.elder_hist_canopy): the grand communion around the Elder Hist of Helstrom |
| L9 | `elder_tree_council` (bm.L9.sickening_hist, L7.root_heart_tribes): the interior council mobilising against black-sap poachers and eastern slaver remnants |
| L10 | `elder_root_sanctum` (bm.L10.root_caverns, bm.L10.sunken_xanmeer, L1.helstrom_root_dome): Hissmir's sunken calendar pyramid and the living root-caverns beneath Helstrom |
| L11 | `unconquered_core` (bm.L11.pre_duskfall_empire, bm.L11.imperial_blackwater_war, L3.labyrinth_channels): every foreign legion lost in the root-ways since the First Era |

### 1.6 Lilmoth (`lilmoth`, 5.6 km2, levels 15-30)

**World truth.** Where the southern river channels fan out into Topal Bay stands Lilmoth: a bustling, half-sunken delta
port of rotting Imperial stone warehouses, cypress stilt-quays, smuggling wharves, and a customhouse that taxes
everything that floats. In the surrounding tidal mangrove bayous, silver-hulled Kothringi plague-ships from the Second
Era Knahaten Flu still drift through the warm salt fog, and pre-Duskfall xanmeer steps lie just beneath the brackish
tide. Saxhleel punt-men, Khajiit smugglers, Padomaic spice factors, and An-Xileel dock-wardens share the lantern-lit
boardwalks at night.

| Layer | Fields |
| --- | --- |
| L1 | `topal_silt_delta`: shifting mudbanks, shell islands, and drowned stone quays at the bay mouth |
| L2 | `topal_sea_fog` (bm.L2.padomaic_squalls, L1.topal_silt_delta): warm salt fog and summer squalls off Topal Bay |
| L3 | `tidal_bayous` (bm.L3.brackish_estuaries, L1.topal_silt_delta): twice-daily tides reversing the flow of the lower channels |
| L4 | `delta_mangroves` (bm.L4.mangrove_tangle, L3.tidal_bayous): stilt-root mangroves, haj mota, and swamp jellyfish |
| L5 | `smuggler_and_spice_quays` (bm.L5.coastal_smuggling, bm.L5.river_barge_and_punt, L3.tidal_bayous): Padomaic spice, pearls, shell-lime, and untaxed cargo |
| L6 | `lilmoth_harbour` (bm.L6.outer_ring_ports, L5.smuggler_and_spice_quays): twin harbour-cranes, boardwalk markets, and half-sunken stone warehouses |
| L7 | `dock_syndicates_and_boatmen` (bm.L7.outer_vs_inner_tribes, L6.lilmoth_harbour): Saxhleel punt-men, Khajiit and Imperial captains, and harbour factors |
| L8 | `kothringi_quarantine_shrines` (bm.L8.the_hist_and_sithis, L2.topal_sea_fog): mirror-posts set on the shoals to turn back plague spirits |
| L9 | `customhouse_struggle` (bm.L9.an_xileel_and_imperial_border, L5.smuggler_and_spice_quays): An-Xileel dock-wardens seizing contraband from foreign charter ships |
| L10 | `drowned_customs_and_hulks` (bm.L10.kothringi_ghost_ships, bm.L10.sunken_xanmeer, L1.topal_silt_delta): Crimson Cove's Kothringi silver-ship and flooded wharf vaults |
| L11 | `flu_quarantine_wharves` (bm.L11.knahaten_flu, L10.drowned_customs_and_hulks): the burning of the old port during the Knahaten Flu of 2E 560 |

### 1.7 Murkmire (`murkmire`, 5.1 km2, levels 18-35)

**World truth.** Murkmire is the deep south-eastern coastal shelf stretching from Soulrest's sheltered sound to the
outer mangrove archipelagos: a vertical jungle of stilt-roots, bright-painted tribal villages, acid sinkhole fens, and
the grandest pre-Duskfall xanmeer pyramids rising out of the warm equatorial mist. Here the welcoming Bright-Throat
artisan clans and the fierce Dead-Water hunter-warriors keep the old Saxhleel ways untouched by Imperial law, carving
root-canoes, mixing rare marsh venoms, and reading the stone calendars of Xul-Thuxis and the Teeth of Sithis while
Shadowscale initiates undergo their silent trials in the flooded depths.

| Layer | Fields |
| --- | --- |
| L1 | `mangrove_shelf`: drowned limestone karst and stepped xanmeer foundations along the southern shelf |
| L2 | `equatorial_downpour` (bm.L2.monsoon_humidity, L1.mangrove_shelf): heavy warm rains and luminous night spore-mist |
| L3 | `soulrest_sound` (bm.L3.brackish_estuaries, bm.L3.root_water_network, L1.mangrove_shelf): calm lagoon channels and submerged root-ways |
| L4 | `coastal_mangrove_dome` (bm.L4.mangrove_tangle, bm.L4.hist_grove, L3.soulrest_sound): towering mangrove arches, hackwings, and Voriplasm fens |
| L5 | `reed_canoe_and_reagent_craft` (bm.L5.reed_and_mud_craft, bm.L5.alchemical_reagents, L4.coastal_mangrove_dome): shell-lime pottery, Bright-Throat carvings, and rare venoms |
| L6 | `soulrest_and_stilt_villages` (bm.L6.inner_hist_villages, bm.L6.outer_ring_ports, L3.soulrest_sound): Soulrest's stilt-harbour and painted tribal rings around Hist mounds |
| L7 | `bright_throat_and_dead_water` (bm.L7.hist_communion, bm.L7.shadowscale_order, L6.soulrest_and_stilt_villages): rival hunter and singer tribes plus Shadowscale mentors |
| L8 | `sithis_calendar_stones` (bm.L8.the_hist_and_sithis, bm.L8.xanmeer_taboo, L1.mangrove_shelf): honoring Duskfall and the seasonal turning of change |
| L9 | `calendar_stone_feud` (bm.L9.tribal_water_feuds, L7.bright_throat_and_dead_water): tribal contest over a waking pre-Duskfall blood-oracle |
| L10 | `grand_calendar_xanmeer` (bm.L10.sunken_xanmeer, L1.mangrove_shelf, L9.calendar_stone_feud): the Teeth of Sithis and Xul-Thuxis stepped pyramids descending into subterranean seas |
| L11 | `duskfall_abandonment` (bm.L11.pre_duskfall_empire, L8.sithis_calendar_stones): the day the priest-kings laid down their obsidian knives and walked into the mud |

---

## 2. Micro-biomes

Black Marsh's micro-biomes are governed by **water depth, salinity, clay mound elevation, and Hist proximity** (`MS21`)
rather than mountain altitude. Every region uses at least 6 micro-biomes (>= 10 across the province).

| Micro-biome (key) | Regions | Rule (MS21) | Cause | Flora and ground | Weather | Creatures | Work |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `hist_grove_mound` | all 6 | clay mounds 8-20 above water within 140 blocks of a Hist pin | `bm.L1.hist_clay_rises`, `bm.L3.hist_sap_springs` | giant buttressed Hist, amber sap pools, glowing mushrooms | warm golden mist, calm | peaceful marsh fauna, sap-wisps | Tree-Minder rites, sap tending |
| `braided_cypress_channel` | all 6 | hydrography channel & oxbow corridor cells | `bm.L3.braided_channels` | bald cypress knees, duckweed, submerged logs, dark water | morning fog, rain | river Betty, slaughterfish, bog dogs | swamp-boat punting, reed traps |
| `sunken_xanmeer_fen` | `shadowfen`, `helstrom`, `lilmoth`, `murkmire` | 180-block ring round `xanmeer` dungeons | `bm.L1.xanmeer_foundations`, `bm.L8.xanmeer_taboo` | mossy carved limestone, strangler figs, flooded courtyards | heavy mist | wamasu, stone-viper constructs, skeletons | relic warding, scholar expeditions |
| `brackish_mangrove` | `lilmoth`, `murkmire`, `helstrom` (east) | coastal & estuary cells < 6 above sea level | `bm.L3.brackish_estuaries`, `bm.L2.padomaic_squalls` | arched mangrove roots, tidal mudflats, shell banks | coastal squalls, humid | haj mota, mudcrabs, swamp jelly | shell-lime burning, fishing |
| `sawgrass_reed_plain` | `gideon`, `shadowfen`, `lilmoth` | flat alluvial cells 1-4 above water | `bm.L3.blackwood_spill`, `bm.L4.reed_savanna` | tall golden-green reeds, water lilies, levee paths | sun breaks and warm showers | kagouti, marsh nix, herons | reed harvesting, basketry |
| `acid_pitcher_bog` | `shadowfen`, `helstrom`, `murkmire` | interior basin depressions (`heat`/`wet` high) | `bm.L1.alluvial_basin`, `bm.L4.sinkhole_fen` | peat moss, giant pitcher plants, gas bubbles, spore pods | green spore-haze | Voriplasm, fleshflies, giant snakes | toxin & alchemy gathering |
| `border_basalt_upland` | `stormhold`, `shadowfen`, `gideon` | elevation >= 18 along north/west frontier | `bm.L1.nw_border_uplands`, `bm.L2.upland_breeze` | tree-ferns, dark basalt outcrops, Ayleid white-stone rubble | clearer skies | durzogs, border bandits, nix-hounds | caravan staging, quarrying |
| `deep_root_canopy` | `helstrom`, `murkmire` | interior cells > 600 blocks from coast, canopy closure > 80% | `bm.L4.cypress_mire`, `bm.L3.root_water_network` | interlocking root bridges above dark water, hanging lianas | perpetual twilight mist | hackwing flocks, Naga patrols, crocodiles | root-way guiding, hunting |

---

## 3. Map changes and geography

### 3.1 Relief and hydrography (`SCALE-AND-GEOGRAPHY.md` 5.9, 7, 8.9)

| Measure | Now | Target | How |
| --- | --- | --- | --- |
| Height p50 | 1 | 3 | keep the interior basin close to water (1-5 blocks) so channels flood naturally; raise Hist clay mounds to 10-18 |
| Height p90 | 2 | 10 | north-west border uplands (`stormhold`, `gideon`, `shadowfen`) to 30-75; `xanmeer` terraces to 15-35 |
| Ruggedness | 0.4 | 1.0 | incised river channels, levee banks, stepped `xanmeer` profiles, and northern basalt scarps |
| Signature silhouette | flat grass | **stepped stone pyramids and giant Hist crowns rising out of mist-covered channels** | `xanmeer` XL silhouettes + giant Hist tree canopies + stilt walkways |

### 3.2 Coast and anchor adjustments (`SCALE-AND-GEOGRAPHY.md` 8.9)

* **Topal Bay & Soulrest:** Verify Topal Bay's eastern arm reaches ~`5700, 9900` with `soulrest` on its eastern shore (~`6100, 9700`).
* **East coast bulge & bay:** Bulge the east coast ~500 blocks seaward near `thorn` and `archon` to match the lore map's shoulder; carve the indented bay at `10500, 6300–6900` and the multi-channel delta at `lilmoth`.
* **Dry-or-stilt settlement contract:** Every settlement in Black Marsh must either sit on a natural/Hist clay rise (`>= 4` blocks above water) or use the `argonian_stilt` foundation adapter so no door or street is submerged (`layout_lint` 0 water-door errors).
* **Missing Arena place (`SCALE-AND-GEOGRAPHY.md` 8.10):** Verify/add `alten_meirhall` as a town in eastern `shadowfen` / `helstrom` border.

### 3.3 XL silhouettes

| Region | Target XL | Silhouettes |
| --- | --- | --- |
| `stormhold` | 1 | Stormhold's sunken white Ayleid spires ringed by raised Argonian timber towers |
| `gideon` | 1 | Gideon's high Imperial-stone causeway bridge and governor's citadel above the reed sea |
| `helstrom` | 2 | The Elder Hist of Helstrom (65-block canopy dome); the coastal lighthouse-citadel of Archon |
| `lilmoth` | 1 | Lilmoth's twin harbour-cranes and the half-sunken Kothringi flagship in the estuary |
| `murkmire` | 2 | The Grand Calendar `Xanmeer` of Xul-Thuxis (stepped pyramid 55 blocks high); Soulrest's stilt-harbour |
| `shadowfen` | 1 | Ten-Maur-Wolk's twin-peaked `xanmeer` over the border mire |

---

## 4. Roads, travel network, fast travel

### 4.1 Causeway roads (`named_roads.json`)

In Black Marsh, main roads are **raised stone causeways** (built on pre-Duskfall and Imperial foundations) or **timber
corduroy boardwalks** across channels:

| id | Class | Route | Notes |
| --- | --- | --- | --- |
| `blackwood_to_stormhold_causeway` | highway | `(Leyawiin / Blackwood border)` > `gideon` > `stormhold` | Raised stone embankment along the firmer western and northern rim (`bm.L6.raised_causeways`) |
| `northern_slaver_march` | road | `(Morrowind / Deshaan pass)` > `stormhold` > `thorn` > `archon` | The northern and eastern border road |
| `helstrom_sacred_causeway` | road | `stormhold` > `helstrom` > `archon` > `lilmoth` | Broken stone causeway bridged by living root-arches across the interior mire |
| `topal_coast_way` | road | `gideon` > `soulrest` > `lilmoth` | Southern reed-levee and stilt-bridge road along Topal Bay |

### 4.2 Travel modes (`MS11`, `travel/black_marsh.json`)

| Mode | Nodes | Identity rule | Status |
| --- | --- | --- | --- |
| **Swamp Punt / Flatboat** (primary) | `lilmoth`, `soulrest`, `gideon`, `stormhold`, `helstrom`, `thorn`, `archon`, `alten_meirhall` | Flat-bottomed pole boats guided by Saxhleel boatmen along `bm.L3.braided_channels` | NEW (`MS11` `swamp_boat`) |
| **Sea Ships** | `lilmoth`, `soulrest`, `archon`, `thorn` <-> `leyawiin`, `tear`, `senchal` | Coastal packet ships on Topal Bay and the Padomaic | EXISTS + expand |
| **Root-Way Swim Tunnels** (wilderness nodes) | 12 elder Hist pools across the 6 regions | Interacting with a cleansed Hist sap-font lets an Argonian (or sap-blessed traveller) travel through the submerged root network | NEW (`MS11` wilderness node) |

---

## 5. Settlements

### 5.1 Tier list and architectural kits

Four regional kits replace generic huts (`y_looks` >= 3 per region):
1. **`argonian_hist_mud`**: curved mud-and-shell-lime domes, woven reed roofs, painted bone chimes, arranged in concentric rings around a central Hist tree (`bm.L6.inner_hist_villages`).
2. **`argonian_stilt`**: elevated cypress-log platforms, rope-and-plank walkways 3–5 blocks above water, hanging lantern gourds (`bm.L6.outer_ring_ports`).
3. **`xanmeer_stone`**: stepped dark-limestone plinths, serpent-carved lintels, and brazier towers surviving from pre-Duskfall foundations (`bm.L1.xanmeer_foundations`).
4. **`imperial_marsh_hybrid`**: weathered Colovian/Imperial stone lower walls topped by Argonian thatch and stilt-galleries (`gideon`, `lilmoth`, `stormhold`).

| Region | Seat (tier) | Other Cities / Towns | Villages & Tribal Rings |
| --- | --- | --- | --- |
| `helstrom` | `helstrom` (Capital, 73 named NPCs) | `thorn` (City), `archon` (City), `alten_meirhall` (Town) | `Hissmir`, `Xal-Ithix`, `Root-Whisper Village` |
| `stormhold` | `stormhold` (City, 45 named NPCs) | `ten_maur_wolk` (Town) | `Sunken-Road Hamlet`, `Bog-Iron Camp` |
| `gideon` | `gideon` (City, 42 named NPCs) | `blackrose` (Town) | `Red-Water Levee`, `Twyllbek Ruins Village` |
| `lilmoth` | `lilmoth` (City, 44 named NPCs) | `glenbridge` (Town) | `Whispering-Shoal`, `Kothringi Landing` |
| `murkmire` | `soulrest` (City, 40 named NPCs) | `bright_throat_village` (Town) | `Dead-Water Camp`, `Mangrove-Crown` |
| `shadowfen` | `alten_corimont` (Town/Seat, 28 named NPCs) | `forsaken_hamlet` (Town), `percolating_mire` (Town) | `Still-Rise Village`, `Broken-Tusk Camp` |

---

## 6. Dungeons, underground, landmarks

### 6.1 Signature dungeon themes per region (`d_identity` = 1.0)

| Region | Signature theme | Marquee dungeon (`MARQUEE.md`) | Other major sites |
| --- | --- | --- | --- |
| `shadowfen` | **Freed-slave redoubts & sunken drainage `xanmeer`** | `loriasel` (A, Ayleid-and-Hist sunken ruin) | `chid_moska_ruins`, `gandranen_ruins`, `tsanji_hideout` |
| `stormhold` | **Barsaebic Ayleid under-vaults** | `sunken_stormhold_vaults` (A, Ayleid crystal-and-root labyrinth under the city) | `onko_xanmeer`, `at_tura_estate`, `heimmerh_delve` |
| `gideon` | **Drowned Imperial forts & Blood-Root crypts** | `blackrose_prison_ruins` (S, flooded Imperial penal fortress reclaimed by the marsh) | `undertow_cavern`, `bloodrun_cave`, `false_martyrs_folly` |
| `helstrom` | **Living Hist root-caverns & Naga sanctums** | `hissmir_sunken_pyramid` (S, multi-level calendar pyramid with rising/falling water gates) | `mazzatun_ruins`, `the_vile_manse_marsh`, `scales_of_sithis_sanctum` |
| `lilmoth` | **Kothringi plague-hulks & smuggler sea-caves** | `crimson_cove_kothringi` (A, cavern of the last Kothringi silver-ship) | `abandoned_wharf_vault`, `ixtaxh_xanmeer`, `lady_solace_fen` |
| `murkmire` | **Pre-Duskfall calendar `xanmeer` & Shadowscale trials** | `teeth_of_sithis` (S, stepped pyramid descending into a primordial subterranean sea) | `tsofeercavern`, `xal_vakka`, `bocks_tooth_mire` |

---

## 7. Quests

### 7.1 Province story arc (18 quests): **The Amber Memory (`mq_black_marsh`)**

An outer-ring syndicate and rogue Dres factors are drilling into the deep root-table beneath `shadowfen` and `helstrom`
to harvest calcified "amber sap" from before the Duskfall (`bm.L9.sickening_hist`), causing the outer Hist trees to go
silent and their tribes to lose speech and memory.
* **Act I — The Silent Tree (4):** At `stormhold` and `shadowfen`, investigate a village whose Tree-Minder can no longer hear the Hist; trace the poisoned sap-spring to an Ayleid-era siphon in `loriasel` (`bm_1`).
* **Act II — The Drowned Ports (4):** Travel by swamp-boat to `gideon` and `lilmoth`; uncover the shipping ledger at the smuggler quays and board the Kothringi ghost-hulk (`bm_2`) to recover the pre-Duskfall canal key.
* **Act III — The Calendar of Stone (5):** Enter `murkmire` (`soulrest`), broker peace between the Bright-Throat and Dead-Water tribes (`bm.L9.tribal_water_feuds`), and align the water-gates of `teeth_of_sithis` (`bm_3`).
* **Act IV — The Root-Heart at Helstrom (5):** Journey along the sacred root-ways into `helstrom`; descend into `hissmir_sunken_pyramid` to sever the deep siphon or redirect the elder sap. **Consequence:** Restores the Hist's voice across the 6 regions, changes Tree-Minder dialogue (`F-REACT`), and opens the Root-Way fast-travel network.

### 7.2 Factions and regional chains

* **The Shadowscales (`shadowscales.json`, 8 quests):** Born under the Shadow sign (`bm.L7.shadowscale_order`); training trials in `murkmire`, eliminating slaver captains in `thorn`, and a choice between the ancient Sithis covenant and the modern Dark Brotherhood.
* **The An-Xileel Wardens (`an_xileel.json`, 8 quests):** Defending the border causeways, reclaiming stolen `xanmeer` relics in `gideon`, and deciding whether foreign charters in `lilmoth` stay open or are expelled.
* **The Sap-Scholars & Tree-Minders (6 quests):** Tending young Hist shoots, curing sap-rot, and translating pre-Duskfall stone tablets (`bm_4`).

---

## 8. Components layout

| Component | File or system | Now | To do (phase) |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/black_marsh.json` | in §1 | BM0 (V1) |
| Coast, relief & channels | `tamriel_vectors.json` (east bulge, Lilmoth delta, Hist rises, braided rivers) | p50 1, p90 2 | BM1 (V2) |
| Micro-biomes | `palettes.json` (8 marsh micro-biomes by depth/salinity/Hist) | 2-3 per region | BM1 (V3) |
| Places | `places/black_marsh.json` (+ `alten_meirhall`, Hist groves, unmarked `xanmeer`) | 312 | BM2 (V4) |
| Causeways & boats | `named_roads.json` (4 causeways), `travel/black_marsh.json` (swamp boats, root-ways) | 0 swamp boats | BM2 (V5) |
| Settlements & kits | `styles/argonian_*.json` (4 kits), city plans for Helstrom, Stormhold, Gideon, Lilmoth, Soulrest, Thorn, Archon | generic kits | BM3 (V6) |
| Dungeons | `marquee.json` (`xanmeer` water-gates, Hist root caverns, Kothringi hulks) | low identity | BM4 (V7) |
| Creatures & scenes | `region_spawns/black_marsh.json` (wamasu, haj mota, hackwing, bog dog, Naga, Voriplasm), `scenes.json` | 9 kinds | BM5 (V8) |
| Quests & lore | `mq_black_marsh`, Shadowscales, An-Xileel, 6 region chains, readables | 64 quests | BM6 (V8) |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Low-elevation wetland basin | Braided channels, oxbows, 10-20 block Hist clay rises, 30-75 block northern rim, stepped `xanmeer` silhouettes | Featureless 1-block-above-sea-level flatland |
| Settlements | Arena's canonical city and town names (+ `alten_meirhall`) | Outer stone-hybrid ports vs inner Hist-ring mud/stilt villages | Generic stone/timber templates sinking in water |
| Travel | Coastal ships at Lilmoth and Soulrest | Raised causeway roads, poled swamp-boats, Hist root-way fast travel | Walking across dry grass where swamp should be |
| Dungeons | Named ruins (`loriasel`, `teeth_of_sithis`, `blackrose`) | Stepped `xanmeer` pyramids with flooded lower levels, amber-lit root caverns, Kothringi ghost ships | Generic caves and standard crypts |

---

## 10. Floors, gates, and implementation phases

### 10.1 Current vs target per region

| Region | km2 | Parity now | Settlements (plan) | Dungeons (now -> floor) | Quests (now -> target) | Named NPCs (now -> target) | Unmarked sites | `d_identity` | `y_looks` | `c_total` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| `shadowfen` | 6.4 | 41 | 3 | 38 -> 57 | 12 -> 90 | 18 -> 160 | 4 | 1.0 | 3 | 24 |
| `stormhold` | 6.8 | 42 | 2 | 41 -> 60 | 11 -> 95 | 20 -> 175 | 4 | 1.0 | 3 | 24 |
| `gideon` | 6.2 | 39 | 2 | 36 -> 55 | 10 -> 87 | 16 -> 155 | 4 | 1.0 | 3 | 24 |
| `helstrom` | 7.9 | 38 | 4 | 45 -> 70 | 11 -> 110 | 22 -> 210 | 5 | 1.0 | 4 | 25 |
| `lilmoth` | 5.6 | 41 | 2 | 33 -> 50 | 10 -> 78 | 17 -> 145 | 3 | 1.0 | 3 | 24 |
| `murkmire` | 5.1 | 40 | 2 | 30 -> 45 | 10 -> 72 | 15 -> 135 | 3 | 1.0 | 3 | 25 |
| **Province** | **38.0** | **40** | **15** | **223 -> 337** | **64 -> 532** | **108 -> 980** | **23** | **1.0** | **4 kits** | **25** |

### 10.2 Implementation phases (BM0–BM7, roadmap R8)

| Phase | Step | Scope | Gate |
| --- | --- | --- | --- |
| **BM0** | V1 | Compile `tamriel/world/black_marsh.json` (province + 6 regions) | `worldsheet_lint.py` 0 errors |
| **BM1** | V2, V3 | Coast moves (east bulge, Lilmoth delta, Topal arm), braided channels, Hist rises, 8 micro-biomes | `layout_lint` 0 wet doors; `F-MICRO` >= 6 |
| **BM2** | V4, V5 | Add `alten_meirhall`, Hist grove pins, 23 unmarked sites, 4 causeway roads, swamp-boats & root-ways | `roadplan_lint` 0; `F-TRAVEL` pass |
| **BM3** | V6 | 4 architectural kits (`argonian_hist_mud`, `argonian_stilt`, `xanmeer_stone`, `imperial_marsh_hybrid`), 7 city plans | `SET` >= 85; `y_looks` >= 3 |
| **BM4** | V7 | `xanmeer` step-pyramid generator & marquee dungeons (`teeth_of_sithis`, `hissmir`, `blackrose_prison_ruins`, `loriasel`) | `d_identity` 1.0; `F-LOOP` >= 90% |
| **BM5** | V8 | Marsh creature roster (25 kinds) and province encounters (Hist procession, freed slaver caravan, hackwing swarm, sap bloom) | `CRE` >= 85; `F-ENC` >= 8 |
| **BM6** | V8 | **The Amber Memory** arc, Shadowscales, An-Xileel, 6 regional chains, Tree-Minder reactive dialogue | `QST`, `LOR` floors met |
| **BM7** | V9 | Full DESIGN-PRINCIPLES 5.5 audit across all 6 Black Marsh regions | All parity categories >= 85 |

