# Summerset: crystal, lineage and the sea

Status: plan (docs only, 2026-10-13). Nothing in the mod has changed. Phase prefix **SU**.

Read first: `docs/world/DESIGN-PRINCIPLES.md` (first principle, sections 0, 1, 4, 5), `docs/world/SCALE-AND-GEOGRAPHY.md`
(5.8, 6, 7), `docs/MARQUEE.md` (Summerset Isles), `docs/LORE-WEB.md` (sum_1 to sum_4, t_welkynd, t_tide, t_falls),
`docs/PARITY.md`. Places: `tamriel/places/summerset.json` (3 regions, 281 places).

**Legal line.** Lore facts in our own words. Arena's Summerset shape is unusable (fit 34.3 blocks per pixel, residuals
over 1,000 blocks everywhere): the lore map rules, Arena gives names and order only. Invented names, lines and designs
are ours.

**Parity snapshot** (2026-10-13). Province 13.6 km², mean 45: SET 43, NPC 26, QST 38, DUN 62, CRE 57, FLO 77, STY 30,
LND 67, LOR 27, MUS 48, ITM 18. Summerset is the densest province (1.18 named towns per km², landmark kinds 16 per
region, 3.38 bosses and shrines per km²) with the fewest creature kinds (10) and the shallowest towns for that density
(0.94 named buildings per named town). Artaeum scores well per km² (QST 76, DUN 87, LOR 68) because it is tiny.

**Not Skyrim in a new colour.**

| Skyrim has | Summerset has instead (sheet cause) |
| --- | --- |
| A rough frontier of holds | A cultivated island: gardens, vineyards, white roads, almost no wild land (L4) |
| Jarls and thanes | Thalmor governors, kinlord houses, Sapiarchs and a lineage registry (L7, L9) |
| Ruins of a fallen people | Ancestral crystal vaults kept by living families; Direnni ruins; Psijic time-places (L10) |
| Bandits on the road | Maormer landings, Sload abyssal vents, hidden Daedric cults among the perfect (L9, L10) |
| Carriages | Ships between ports, the inter-island ferry, a Psijic boat that comes only in the mist, lens-stones (L5, L8) |
| Pine, snow and grey stone | White quartz, gold leaf, terraced vines, prism light at noon (L1, L2) |
| Anyone may enter a city | Outsiders live on permits in harbour quarters and may not go inland without a pass (L7) |

---

## 1. World model sheet

Data target: `tamriel/world/summerset.json` (MS22). Field ids are data ids; cites in parentheses; `su.` is the province.

### 1.1 Province sheet (`summerset`)

**World truth.** Summerset is the Altmer homeland and the Thalmor's heart: two bright islands of white cliffs,
crystal-strewn coasts, terraced vineyards and ordered gardens, with Artaeum, the Psijics' island, half in the mist.
Lineage decides a life here: kinlord houses rule cities, Sapiarchs keep learning at the Crystal Tower, the Thalmor
govern from Alinor and their justiciars watch everyone, and outsiders work in harbour quarters on permits. The Altmer
honour their ancestors in crystal vaults and deny that any man became a god. The sea is the danger: Maormer raid
Auridon, the Sload's vents breach the coast, and hidden Daedric cults grow inside the perfect cities while Imperial-era
names survive only on old maps.

| Layer | Fields `id` (cites): meaning |
| --- | --- |
| L1 Geology | `white_cliffs`: pale limestone cliffs round both islands, 40-80 blocks, broken by harbour coves. `central_upland`: a granite upland in the middle of Summerset Isle, to 120, where the Crystal Tower stands. `auridon_ridge`: Auridon's spine, 60-120. `crystal_seams`: quartz and crystal veins that weather out onto the beaches. `terrace_loams`: well-drained loams on the cliff-top downs. `sea_trench`: deep water close to the south and west shores. `coral_shelf`: reefs on the warmer southern shore |
| L2 Climate | `mild_maritime` (L1.white_cliffs): mild, sunny and even all year. `bright_air` (L1.crystal_seams, L2.mild_maritime): clear air and strong light that sets the crystal glittering. `west_fogs` (L1.sea_trench): sea fogs off the western ocean. `south_gales` (L1.sea_trench): storms arrive from the south over deep water. `warmer_auridon` (L1.auridon_ridge): Auridon is warmer and wetter, almost tropical. `artaeum_mist` (L1.sea_trench, L2.west_fogs): a standing mist round Artaeum that does not follow the weather |
| L3 Water | `short_streams` (L1.central_upland, L2.mild_maritime): short clear streams off the upland, falling over the cliffs. `cisterns_and_fountains` (L1.central_upland, L3.short_streams): cities draw from engineered cisterns and enchanted fountains. `water_wardens` (L1.white_cliffs, L3.cisterns_and_fountains): each city's water is held by its kinlord house and released to public fountains on a schedule; inland estates hold their own springs. `tidal_causeway` (L1.white_cliffs): Firsthold reached at low tide |
| L4 Ecology | `garden_island` (L1.terrace_loams, L2.mild_maritime): a managed landscape of hedged gardens, orchards and vineyards; true wild land survives only on the upland, the cliffs and the wastes. `vine_terraces` (L1.terrace_loams, L2.bright_air): south-facing vineyard slopes. `cliff_gryphons` (L1.white_cliffs): gryphons nest on the high cliffs. `indrik_downs` (L1.terrace_loams, L4.garden_island): indriks and deer on the meadow downs. `crystal_shores` (L1.crystal_seams): sea birds, crabs and glittering sands. `silk_spiders` (L1.central_upland): giant spiders in old upland caves, their silk prized. `salt_wastes` (L1.central_upland, L2.south_gales): the Corgrad Wastes, a scoured salt-and-rock tableland where nothing is planted. `reef_life` (L1.coral_shelf): warm southern coral reefs |
| L5 Economy | `wine` (L4.vine_terraces): the isles' famous wine. `crystal_and_lenses` (L1.crystal_seams): crystal cut for lenses, focus stones and glass. `learning` (L1.central_upland, L5.crystal_and_lenses): books, enchantment and instruction, exported and guarded. `silk` (L4.silk_spiders): spider-silk weaving. `ships` (L1.white_cliffs): eagle-prowed ships from cove yards. `pearls_and_fish` (L4.reef_life): coastal fisheries and pearl diving. `gryphon_eggs` (L4.cliff_gryphons): taken under licence, tithed to the houses. `restricted_imports` (L1.white_cliffs, L5.ships): grain, labour and raw goods come through two open ports only |
| L6 Settlement | `harbour_cities` (L1.white_cliffs, L3.short_streams): cities stand at the breaks in the cliffs where streams reach the sea. `upland_colleges` (L1.central_upland, L5.learning): the Crystal Tower and the colleges on the upland. `vineyard_villas` (L4.vine_terraces, L5.wine): estates and villages on the downs. `auridon_frontline` (L1.auridon_ridge, L5.restricted_imports): Auridon's towns face the sea lanes and Valenwood; foreign ships land there first. `busy_centres` (L5.learning, L5.restricted_imports, L6.harbour_cities): Alinor (capital, Thalmor seat), Shimmerene (the open port), Lillandril (scholars), Vulkhel Guard (Auridon's open port), Skywatch (Auridon's court). `failing_places` (L4.salt_wastes): estates of fallen lines, villages at the Corgrad edge, fishing hamlets after Maormer raids |
| L7 Society | `lineage_registry` (L6.harbour_cities): rank by recorded lineage; marriages arranged and registered. `kinlord_houses` (L3.water_wardens, L6.harbour_cities): old houses rule cities and hold the water. `sapiarchs` (L6.upland_colleges): scholar-priests of the Crystal Tower. `thalmor_order` (L6.harbour_cities, L7.kinlord_houses): Thalmor officials and justiciars over all. `lesser_lines` (L6.vineyard_villas, L7.lineage_registry): Altmer of doubted lineage as servants, sailors and labourers. `permit_outsiders` (L5.restricted_imports, L6.harbour_cities): Bosmer, Khajiit and other Dominion subjects on work permits in harbour quarters, barred from the interior without a pass. `dissidents` (L6.upland_colleges, L7.thalmor_order): Altmer who reject the Thalmor, watched or vanished |
| L8 Culture | `ancestor_faith` (L7.lineage_registry): the Aldmeri gods as ancestors (Auri-El, Magnus, Syrabane, Phynaster, Xarxes, Mara and others); family vaults and ancestor shrines. `no_man_god` (L7.thalmor_order, L8.ancestor_faith): the denial that any man became a god; Talos worship hunted. `propriety` (L7.lineage_registry): etiquette, beauty and order as virtues; a college teaches them. `old_ways` (L2.artaeum_mist, L7.sapiarchs): the Psijics' older philosophy, outside the Thalmor's faith. `outsiders_lesser` (L7.permit_outsiders): other peoples are tolerated, not equal. `magic_heritage` (L5.learning, L7.sapiarchs): magic as birthright and duty. `lamplit_dead` (L7.lineage_registry, L8.ancestor_faith): the dead keep their lamps in the family vault |
| L9 Politics | `thalmor_rule` (L7.thalmor_order, L8.no_man_god): the Thalmor govern the isles and the Dominion from Alinor. `kinlord_resentment` (L7.kinlord_houses, L8.propriety): old houses resent the Thalmor's grip. `psijic_apart` (L8.old_ways): Artaeum answers to no one; the Thalmor want its relics. `maormer_war` (L2.south_gales, L6.auridon_frontline): Pyandonea raids Auridon and the south coast. `sload_shadow` (L1.sea_trench): Sload agents and their vents in the southern sea. `dominion_clients` (L7.permit_outsiders, L9.thalmor_rule): Valenwood and Elsweyr governed through envoys. `ancestors_vigil` (L6.upland_colleges, L8.lamplit_dead): midwinter festival when every house lights its vault and the Crystal Tower turns its beam to the sea |
| L10 Hidden | `crystal_vaults` (L1.crystal_seams, L8.lamplit_dead): ancestral vaults of crystal and lamps under family estates. `direnni_ruins` (L1.white_cliffs, L6.harbour_cities): ruins of the clan that left for High Rock. `daedric_cults` (L7.dissidents, L8.propriety, L9.thalmor_rule): hidden cults inside the perfect cities, for those the order crushes. `abyssal_vents` (L1.sea_trench, L9.sload_shadow): Sload-made geysers that open from the deep and spill sea creatures. `maormer_beachheads` (L1.white_cliffs, L9.maormer_war): hidden sea-elf landing coves. `justiciar_cells` (L9.thalmor_rule): secret prisons for dissidents. `spider_cathedral` (L4.silk_spiders): webbed caverns in the upland rock. `smuggler_coves` (L1.white_cliffs, L7.permit_outsiders): outsider smugglers in sea caves. `psijic_eddies` (L1.crystal_seams, L8.old_ways, L9.psijic_apart): places where time does not run straight |
| L11 History | `aldmeri_landing` (L1.white_cliffs, L6.upland_colleges): the Aldmer settle the isles in the Merethic Era and raise the Crystal Tower. `direnni_departure` (L10.direnni_ruins): the Direnni clan leaves for High Rock. `sload_plague` (L9.sload_shadow, L10.abyssal_vents): the Sload's plague in the First Era and the allied fleet that sank Thras. `first_dominion` (L9.dominion_clients): the Second Era alliance of Altmer, Bosmer and Khajiit. `thalmor_coup` (L9.thalmor_rule): early in the Fourth Era the Thalmor take power and rename the isles Alinor, erasing foreign names. `great_war` (L8.no_man_god, L9.thalmor_rule): the war on the Empire and the Concordat. `old_maps` (L6.harbour_cities, L9.thalmor_rule): Imperial-era maps still carry the erased names |

### 1.2 Summerset Isle (`summerset/summerset_isle`, 10.37 km², capital Alinor, levels 25-45)

**World truth.** The larger island is the most ordered land in Tamriel. Alinor's sixteen avenues radiate from the
Crystal Tower's sight-line; Shimmerene is the one port where outsiders crowd into a permit quarter; Lillandril's
libraries stand open to the sky; Cloudrest hangs over the sea on its mountain eyrie. Vineyards climb the downs,
gryphons nest on the cliffs and the Corgrad Wastes lie salt-white inland. Under the order, kinlord houses fight the
Thalmor by etiquette, Sload vents breach the south shore and a cult of the Mad God remembers a temple Arena knew.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `tower_upland`: the central granite upland around the Crystal Tower. `south_shelf`: warm southern coral shelf. `corgrad_tableland`: scoured salt-and-rock tableland |
| L2 | `prism_noon` (su.L2.bright_air, L1.tower_upland): noon light refracting off crystal spires. `west_fog_coast` (su.L2.west_fogs, L1.tower_upland): sea fogs around Lillandril and Cloudrest |
| L3 | `alinor_aqueduct` (su.L3.short_streams, L1.tower_upland): white-stone aqueducts feeding the capital. `upland_springs` (su.L3.short_streams, L1.tower_upland): the clear springs of Norlor, Calinaan and Faaril |
| L4 | `crystal_coast` (su.L4.crystal_shores, L2.prism_noon): glittering white beaches. `vine_terraces` (su.L4.vine_terraces, L2.prism_noon): terraced hills of grape and olive. `meadow_downs` (su.L4.indrik_downs, L3.upland_springs): hedged flower downs. `gryphon_cliffs` (su.L4.cliff_gryphons, L1.tower_upland): high coastal eyries. `salt_waste` (su.L4.salt_wastes, L1.corgrad_tableland): the barren Corgrad flats. `coral_forest` (su.L4.reef_life, L1.south_shelf): southern reef shallows. `ornamental_ring` (su.L4.garden_island, L3.alinor_aqueduct): clipped city gardens |
| L5 | `alinor_court_economy` (su.L5.learning, L3.alinor_aqueduct): glass, crystal lenses and court patronage. `shimmerene_open_port` (su.L5.restricted_imports, L1.south_shelf): the southern open customs port. `lillandril_books` (su.L5.learning, L2.west_fog_coast): scholarly presses and enchantment. `wineries` (su.L5.wine, L4.vine_terraces): the estates of Oleander Coast, Queiisen, Torancar, Valiel and Calandil |
| L6 | `alinor_capital` (su.L6.harbour_cities, L3.alinor_aqueduct): the sixteen-avenue capital. `east_half` (su.L6.harbour_cities, L5.shimmerene_open_port): Shimmerene, Dusk and Sunhold. `west_half` (L2.west_fog_coast, L5.lillandril_books): Lillandril, Cloudrest and West Guard. `colleges` (su.L6.upland_colleges, L1.tower_upland): the College of Sapiarchs and Illumination Academy |
| L7 | `alinor_houses` (su.L7.kinlord_houses, L6.alinor_capital): ancient noble lineages of the capital. `shimmerene_permit_quarter` (su.L7.permit_outsiders, L5.shimmerene_open_port): walled dock quarter for foreign factors. `vineyard_labour` (su.L7.lesser_lines, L5.wineries): estate vintners and stewards |
| L8 | `tower_faith` (su.L8.ancestor_faith, L6.colleges): Sapiarch ancestor liturgy at the Crystal Tower. `sacred_numbers` (su.L8.propriety, L4.ornamental_ring): gardens of geometry as devotion |
| L9 | `house_vs_thalmor` (su.L9.kinlord_resentment, L7.alinor_houses): quiet lineage resistance to Justiciar oversight. `geyser_alarm` (su.L9.sload_shadow, L1.south_shelf): coastal panic over rising abyssal vents |
| L10 | `abyssal_geysers` (su.L10.abyssal_vents, L1.south_shelf, L9.geyser_alarm): the Corgrad, Direnni, Rellenthil, Sil-Var-Woad, Sunhold and Welenkin vents. `cathedral_of_webs` (su.L10.spider_cathedral, L1.tower_upland): upland spider-cult caverns. `tower_undervaults` (su.L10.crystal_vaults, L1.tower_upland): ancestral crystal crypts. `temple_of_the_mad_god` (su.L10.daedric_cults, L1.corgrad_tableland, L9.house_vs_thalmor): Arena's starting dungeon hidden in the wastes |
| L11 | `direnni_acropolis` (su.L11.direnni_departure, L1.tower_upland): the empty white acropolis of the departed clan. `erased_names` (su.L11.old_maps, L9.house_vs_thalmor): villages whose Imperial-era names survive only on contraband charts |

### 1.3 Auridon (`summerset/auridon`, 2.79 km², seat Firsthold, levels 22-44)

**World truth.** Auridon is the warmer northern island, Summerset's front door and its military front line facing the
continent. Foreign ships must land at Vulkhel Guard's open quays; Firsthold sits on a tidal rock reached by a low
causeway, and Skywatch crowns its hill under a great brass orrery. Maormer raiders strike the coves in every southern
gale, and the Dominion marines answer from Hightide Keep. In the Banished Cells and Mehrunes' Spite, Daedric cults that
the Thalmor officially deny exist are growing among disgraced scions. Auridon's people are prouder of their island's
ancient guard duty than of its quiet.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `ridge`: Auridon's central limestone and granite spine. `tide_rock`: Firsthold's tidal headland and causeway shelf |
| L2 | `warm_wet_auridon` (su.L2.warmer_auridon, L1.ridge): warm, humid breezes off the Abecean |
| L3 | `ridge_falls` (su.L3.short_streams, L1.ridge): Beacon Falls and coastal cascades |
| L4 | `palm_groves` (L2.warm_wet_auridon, L3.ridge_falls): coastal palms and flowering broadleaf. `ridge_pines` (L1.ridge, L2.warm_wet_auridon): umbrella pines along the high spine. `crystal_coast` (su.L4.crystal_shores, L1.tide_rock): quartz-sand coves. `tidal_flats` (su.L3.tidal_causeway, L1.tide_rock): mudflats and pools around Firsthold. `raid_scar` (su.L2.south_gales, L1.tide_rock): burned landing beaches. `dolmen_heath` (su.L4.garden_island, L1.ridge): turf and standing stones |
| L5 | `vulkhel_open_port` (su.L5.restricted_imports, L1.tide_rock): customs, ship repair and foreign factor trade. `fisheries` (su.L5.pearls_and_fish, L4.tidal_flats): coastal fishing fleets. `skywatch_learning` (su.L5.learning, L1.ridge): the high orrery and naval academy |
| L6 | `firsthold_seat` (L1.tide_rock, su.L3.tidal_causeway): the northern court city on its tidal rock. `skywatch` (L1.ridge, L5.skywatch_learning): the stepped hill-city under the orrery. `vulkhel_guard` (L5.vulkhel_open_port): the southern port town. `mathiisen` (su.L6.vineyard_villas, L3.ridge_falls): inland smithing and vineyard town |
| L7 | `marines` (su.L7.thalmor_order, L6.vulkhel_guard): Dominion naval officers and coastal watches. `vulkhel_outsiders` (su.L7.permit_outsiders, L5.vulkhel_open_port): foreign traders and sailors under harbour curfew |
| L8 | `propriety_college` (su.L8.propriety, L6.skywatch): the College of Aldmeri Propriety where suspect scions are re-educated |
| L9 | `maormer_front` (su.L9.maormer_war, L7.marines): sea-elf raids and Hightide Keep counter-strikes. `cult_denial` (su.L9.thalmor_rule, L8.propriety_college): Veiled Heritance and Daedric cells suppressed by Justiciars |
| L10 | `maormer_beachheads_auridon` (su.L10.maormer_beachheads, L4.raid_scar, L9.maormer_front): sea-elf invasion camps, sea caves and wrecks. `banished_cells` (su.L10.daedric_cults, L1.ridge, L9.cult_denial): the high-security prison vault. `mehrunes_spite` (su.L10.daedric_cults, L1.ridge, L9.cult_denial): sunken Dagonite shrine. `heritance_proving_ground` (su.L10.crystal_vaults, L1.ridge, L9.cult_denial): rebel training crypts |
| L11 | `first_landing_lore` (su.L11.aldmeri_landing, L6.firsthold_seat): Firsthold's claim to be the oldest Aldmeri landing in the isles |

### 1.4 Artaeum (`summerset/artaeum`, 0.43 km², levels 30-45)

**World truth.** Artaeum is the Psijic Order's island off Summerset, there in the mist and sometimes not in the mortal
world at all. Ceporah Tower, the Grand Psijic Villa and a ruined older college stand among quiet lotus pools under the
Statue of Syrabane. The Order keeps the Old Ways and dangerous relics no one else may touch; time runs strangely in its
under-vaults and in the Dreaming Cave. The Thalmor covet those relics and resent an Altmer authority they cannot
command. A silent boat comes for those the Ritemaster invites, and only through the standing mist.

| Layer | Fields (cites) |
| --- | --- |
| L1 | `isle_rock`: white limestone cliffs and pre-Aldmeri crystal outcrops rising from the western sea |
| L2 | `standing_mist` (su.L2.artaeum_mist, L1.isle_rock): a timeless silver mist volume that hides the island from uninvited ships |
| L3 | `lotus_pools` (su.L3.short_streams, L1.isle_rock): stepped meditation pools and clear springs |
| L4 | `mist_forest` (L1.isle_rock, L2.standing_mist): pale broadleaf trees, moss and wisps. `lotus_meadow` (L3.lotus_pools): quiet flowering meadows. `crystal_spires` (su.L1.crystal_seams, L1.isle_rock): humming natural crystal pillars. `shore_of_stones` (L1.isle_rock): grey pebble beaches and carved markers. `eddy_glade` (L1.isle_rock, L2.standing_mist): glades where falling leaves hang suspended in air. `syrabane_heath` (L1.isle_rock, L3.lotus_pools): high flowered turf round the monument |
| L5 | `relic_keeping` (su.L5.learning, L1.isle_rock): containment and study of dangerous magical artifacts. `craftworks` (su.L5.crystal_and_lenses, L4.crystal_spires): focus-lenses and scrying spheres |
| L6 | `psijic_villa` (L3.lotus_pools, L5.relic_keeping): the Grand Psijic Villa and lecture terraces. `ceporah_tower` (L1.isle_rock, L5.relic_keeping): the pre-Aldmeri tower older than the Crystal Tower |
| L7 | `order_ranks` (su.L7.sapiarchs, L6.psijic_villa): monks, initiates, loremasters and the Ritemaster chosen by merit, not lineage |
| L8 | `syrabane_statue` (su.L8.ancestor_faith, L4.syrabane_heath): monument to the Warlock's Ring and the Ancestor-Mage. `old_ways_rite` (su.L8.old_ways, L7.order_ranks): veneration of the Eleven Forces and ancestral spirits without Thalmor dogma |
| L9 | `thalmor_want_relics` (su.L9.psijic_apart, L5.relic_keeping): Justiciar agents seeking entry to seize the Order's sealed vaults |
| L10 | `psijic_time_vaults` (su.L10.psijic_eddies, L1.isle_rock, L9.thalmor_want_relics): the Psijic Relic Vaults, the Traitor's Vault and the Dreaming Cave |
| L11 | `vanishing_isle` (su.L11.aldmeri_landing, L2.standing_mist, L6.ceporah_tower): the centuries when Artaeum left the mortal sea and returned |

---

## 2. Micro-biomes

Summerset's land is mostly cultivated; its micro-biomes are as much made by people (terraces, gardens) as by nature,
and each cites a cause either way.

| Region | Micro-biome (key) | Rule (MS21) | Cause | Flora and ground | Weather | Creatures | Work |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Summerset Isle | `crystal_coast` | BEACH where crystal seams outcrop | P.crystal_seams | white sand, crystal clusters | glitter at noon | crabs, gulls | crystal gathering |
| Summerset Isle | `vine_terraces` | UPLAND/LOWLAND, aspect south, slope 0.15-0.5 | P.vine_terraces | vines on stone terraces | dry, sunny | none | wineries |
| Summerset Isle | `meadow_downs` | LOWLAND open cliff-tops | P.garden_island | flower meadow, hedges | breeze | indriks, deer | grazing, estates |
| Summerset Isle | `gryphon_cliffs` | cliff cells > 40 high | P.cliff_gryphons | bare white rock, nests | gales | gryphons | egg licence |
| Summerset Isle | `salt_waste` | Corgrad tableland | corgrad_tableland | salt crust, scoured rock | dust | vent creatures | none |
| Summerset Isle | `coral_forest` | south shelf shallows | south_shelf | reef, sea grass | clear | reef fish, sea creatures | pearl diving |
| Summerset Isle | `ornamental_ring` | ring round cities | P.propriety | clipped gardens, pools | n/a | birds | gardeners |
| Summerset Isle | `upland_heath` | tower upland above p75 | tower_upland | heath, standing crystal | mist at dawn | silk spiders (caves) | colleges |
| Summerset Isle | `fog_coast` | west coast | west_fog_coast | sea-grass, dark rock | fog | seals | lighthouses |
| Auridon | `palm_groves` | LOWLAND, warm | warm_wet_auridon | palms, broadleaf | rain showers | birds, monkeys | orchards |
| Auridon | `ridge_pines` | UPLAND on the ridge | ridge | umbrella pines | wind | eagles | watch posts |
| Auridon | `crystal_coast` | as above | P.crystal_seams | crystal sand | glitter | crabs | gathering |
| Auridon | `tidal_flats` | round Firsthold | tide_rock | mud and pools | tides | wading birds | fishing |
| Auridon | `raid_scar` | disturbance on raided coves | maormer_front | burnt boats, ash | smoke | none | quest sites |
| Auridon | `dolmen_heath` | round the three dolmens | P.ancestor_faith | short turf, standing stones | still | moths | shrines |
| Artaeum | `mist_forest` | default land | standing_mist | pale trees, moss, ferns | constant mist | wisps | none |
| Artaeum | `lotus_meadow` | near pools | lotus_pools | lotus, reeds | soft rain | frogs | meditation |
| Artaeum | `crystal_spires` | rock outcrops | isle_rock | crystal pillars | glow at night | none | relic sites |
| Artaeum | `shore_of_stones` | BEACH | isle_rock | grey pebbles, carved stones | mist | gulls | the mist boat |
| Artaeum | `eddy_glade` | rare cells | P.psijic_eddies | leaves that hang in the air | no wind | none | puzzles |
| Artaeum | `syrabane_heath` | round the statue | syrabane_statue | heath, flowers | clear | birds | rites |

Kinds: Summerset Isle 9, Auridon 6, Artaeum 6. **Fix:** the Summerset Isle and Auridon palettes today hold only three
biomes each (`crystal_coast`, `auridon_vineyards`, `summerset_meadow`), so `vine_terraces` and `meadow_downs` cover
almost everything; add `gryphon_cliffs`, `salt_waste`, `coral_forest`, `upland_heath`, `palm_groves`, `ridge_pines`.

---

## 3. Map changes and geography

### 3.1 Relief (SCALE-AND-GEOGRAPHY 7)

| Measure | Now | Target | How |
| --- | --- | --- | --- |
| Height p50 | 23 | 40 | raise the cliff-top downs to 40-60 |
| Height p90 | 40 | 100 | the central upland to 120, Auridon's ridge to 60-120 |
| Ruggedness | 1.9 | 3.5 | white cliffs 40-80 round both islands, harbour coves cut into them |
| Signature silhouette | flat | **a high white island seen from the sea** | cliffs as a `coast` profile, the upland as a `ranges` entry, the Crystal Tower at 180 (MARQUEE) |

### 3.2 Coast and islands

* Cliffs everywhere except at harbour coves (Alinor, Shimmerene, Dusk, Sunhold, Lillandril, Cloudrest, Vulkhel Guard,
  Firsthold). Coves get beaches and quays; elsewhere the sea meets the cliff.
* Firsthold's causeway as a long low bridge with a visible "flooded" state (MARQUEE: time-of-day water level is not
  available).
* Artaeum: a mist volume round the island; the island is present by default (it never really vanishes in data), but
  the Psijic boat and the island's interiors are gated by quest state (section 4).
* The south shelf's coral forest and the six abyssal-geyser landmarks on the south and west coasts.

### 3.3 Place fixes

| Fix | Why |
| --- | --- |
| Re-tier: Alinor capital; Lillandril city (west half seat); Cloudrest, Dusk cities; Shimmerene and Sunhold towns; Skywatch city; Vulkhel Guard town to city-class port without a court; Firsthold seat | One seat per half, ports need plans |
| **Arena's names on old maps.** Arena gave Summerset Nord and Breton names (Thorheim Guard, Marbruk Brook, Karnwasten Moor, Holly Falls, Silver Wood, Rosefield, Glenview, Old Falls, White Guard, Graddun Spring). Rename these villages from an Aldmeri name pool; keep the Arena name as an `old_name` field shown on Imperial-era treasure maps and in books [P.old_maps]. Keep Sunhold, Dusk, Riverfield, West Guard, Sea Keep, Marnor Keep (they read as Imperial-era garrison names the Thalmor kept for their forts) | The Thalmor erased foreign names; old maps are a quest and loot hook |
| 16 Daedric shrines: keep 4 as hidden cult sites with L10 causes (Banished Cells' Molag Bal, Mehrunes' Spite, the Mad God's temple, one of Nocturnal); convert the rest to ancestor shrines (Auri-El, Syrabane, Magnus, Phynaster, Xarxes, Mara, Stendarr) | Open Daedric shrines in the Thalmor homeland contradict L8 and L9 |
| Add `temple_of_the_mad_god` (Arena's starting dungeon) and Arena's missing `silsailen` point as a landmark | Keep Arena's named places |

### 3.4 XL silhouettes

| Region | Target XL | Silhouettes |
| --- | --- | --- |
| Summerset Isle | 4 | the Crystal Tower (180, a beam across the sea at night); Cloudrest's balconies over the sea; Lillandril's library rock; the Direnni Acropolis |
| Auridon | 1 | Skywatch's ring-orrery on its hill (plus the Crystal Tower's beam on the horizon) |
| Artaeum | 1 | the Statue of Syrabane rising out of the mist |

---

## 4. Roads, travel, fast travel

### 4.1 Roads

Summerset's roads are paved white, lined with lamp posts and hedges, with ancestor shrines at junctions and a
milestone in Aldmeri script every 300 blocks. Inland roads past the harbour ring have a **pass gate** where
outsiders without a pass are turned back (a scene and a quest hook, not a wall).

| id (ours) | Class | Route | Notes |
| --- | --- | --- | --- |
| `kings_avenue` | highway | alinor > shimmerene > dusk > sunhold | the east coast (Arena: Alinor-Sunhold-Dusk-Shimmerene) |
| `scholars_road` | highway | alinor > (tower upland: crystal_tower_summerset) > cloudrest > lillandril | climbs past the Crystal Tower |
| `west_road` | road | lillandril > west_guard > sunhold | the fog coast |
| `ridge_road` (Auridon) | highway | vulkhel_guard > mathiisen > skywatch > firsthold | Arena: Firsthold-Skywatch |

### 4.2 Sea routes and travel modes (MS11)

| Mode | Nodes | Identity rule | Status |
| --- | --- | --- | --- |
| **Ships** | Alinor and Firsthold exist (to Woodhearth, Anvil); add Shimmerene, Lillandril, Dusk, Vulkhel Guard | Outsiders may book only from the open ports (Shimmerene, Vulkhel Guard) until they hold a pass | EXISTS + 4 ports |
| **Inter-island ferry** | Alinor - Vulkhel Guard - Firsthold | an eagle-prowed packet; a Thalmor inspection scene | NEW route |
| **The mist boat** | Artaeum's shore and three coves on Summerset Isle | appears only in fog weather and only for those the Order has invited (quest flag) | NEW |
| **Lens-stones** (wilderness nodes) | crystal waystones tuned to the Crystal Tower (converted shrines, obelisks such as `galorne_obelisk`) | the stone flashes when the Tower's beam crosses it at night | NEW |
| **Expedition ships** | Alinor to Thras; Woodhearth/Alinor to Pyandonea (exist with `expedition`) | foreign_lands.md | EXISTS |

---

## 5. Settlements

### 5.1 Tier list

| Region | Seat | Cities | Towns | Villages (selected) | Estates and farms | Change |
| --- | --- | --- | --- | --- | --- | --- |
| Summerset Isle east | `alinor` (capital) | `dusk` | `shimmerene` (open port), `sunhold`, `rellenthil`, `belport_run`, `riverfield` | `kings_haven`, `silyon_landing`, `valdril_docks`, `renandil`, `lanethil`, `galiisen`, `the_golden_netch` | vineyards, villas, `russafeld` | rename 6 Arena villages |
| Summerset Isle west | `lillandril` (city, west seat) | `cloudrest` | `archen_grangrove`, `west_guard` | `berancar_cove`, `mirimor_landing`, `the_winking_carafe` | `college_of_sapiarchs`, `illumination_academy`, `monastery_of_serene_harmony` | rename 4 |
| Auridon | `firsthold` (seat) | `skywatch` | `vulkhel_guard` (open port), `mathiisen`, `greenwater_cove` | `dawnbreak`, `phaer`, `silsailen`, `shattered_grove` | `college_of_aldmeri_propriety`, `trebbite_monastery` | |
| Artaeum | the Order (no seat; a monastery hamlet) | none | none | add `psijic_hamlet` (monks' cells, villa household) | `grand_psijic_villa`, `artaeum_craftworks` | +1 hamlet |

### 5.2 City plan briefs

| City | Plan form | Districts and class gradient (L7) | Landmark | Why here |
| --- | --- | --- | --- | --- |
| **Alinor** (exists, extend) | RADIAL: sixteen avenues on the Crystal Tower's sight-line | Palace and Thalmor ministries > kinlord terraces > scholars and artisans > service streets of lesser lines > the harbour permit quarter | Royal Palace, Crystal Library, Hall of Aldmeri Law | The aqueduct cove [Isle L6] |
| **Lillandril** (exists) | TERRACED ROCK with open-air stacks | Sapiarch libraries > orrery court > scholars' hostels > copyists' lanes | The Great Library Terraces | Learning [Isle L5] |
| **Cloudrest** (new) | CLIFF cantilever, glass floors, no walls | Palace > balcony houses > sea-lift workers at the cliff foot | Glass Balcony Walk | The cliff [Isle L6] |
| **Shimmerene** (new) | HARBOUR with a walled permit quarter | Harbourmaster and customs > Altmer merchants > the permit quarter (Bosmer, Khajiit, Bretons) > dockside barracks | Lantern Docks | The one open port [Isle L5] |
| **Dusk** (new) | crescent HARBOUR under pale dunes | Lantern tower and harbour court > pastel houses > fishers | The Dusk Lantern Tower | A sheltered crescent bay [Isle L6] |
| **Skywatch** (new) | HILLTOP rings | Orrery and court > ring observatories > ring houses > outer market | The great orrery | The ridge [Auridon L6] |
| **Vulkhel Guard** (new) | HARBOUR port-city of the Dominion | Thalmor harbour command > Altmer town > the outsiders' quay | Dominion harbour gate | Auridon's open port [Auridon L5] |
| **Firsthold** (new) | TIDAL ROCK and causeway | Rock chapel and seat > fisher platforms | The Tide Gate | The tide rock [Auridon L6] |

Kits: `altmer` splits into **`altmer_court`** (white quartz, gold, prism glass), **`altmer_scholar`** (bronze,
open stacks, orreries), **`altmer_port`** (white wood, teal sail, lantern poles), **`altmer_villa`** (vineyard
estates, pergolas) and **`psijic`** (older Aldmeri forms: low stone, ivy, round towers). Permit quarters use a
`dominion_quarter` overlay (crowded, mixed, plain). Target `y_looks` >= 3 per region (now 3, 1 and 0).

### 5.3 Bustling centres and why

* **Alinor**: the Dominion's capital; every embassy, petition and ship of state passes here [P.thalmor_rule].
* **Shimmerene** and **Vulkhel Guard**: the only ports outsiders may use, so all foreign trade and labour crowd into
  them [P.restricted_imports].
* **Lillandril**: scholars from across the Dominion come to its libraries [P.learning].
* **Skywatch**: Auridon's court and its orrery [Auridon L6].
* Poor or failing: the permit quarters, Corgrad-edge villages (salt in the wells), raided Auridon coves, estates of
  lines struck from the registry (shuttered villas, a quest hook).

### 5.4 Class disparity

| Rank | Who | Where | What the player sees |
| --- | --- | --- | --- |
| Rulers | Thalmor ministers and justiciars | Alinor's ministries, harbour commands | Patrols, interrogations, notices of the disappeared |
| Old blood | Kinlord houses | City terraces, island estates | Formal courts, rivalries fought with invitations |
| Learning | Sapiarchs, scholars, Psijics apart | Crystal Tower, colleges, Artaeum | Debates, forbidden books |
| Citizens | Altmer of good lineage | Garden streets | Propriety lessons, registry certificates on the wall |
| Lesser lines | Altmer of doubted lineage | Service streets, ships, vineyards | Servants who are also elves, and resent it |
| Permit holders | Bosmer, Khajiit, others | Harbour quarters | Passes checked; a curfew bell |
| Outcasts | Dissidents, cultists, smugglers | Cells, cult chambers, sea caves | Scratched symbols, hidden doors |

---

## 6. Dungeons, underground, landmarks

### 6.1 What lies below and why

| Underground | Where | Why |
| --- | --- | --- |
| Ancestral crystal vaults | under estates and the upland | families keep their dead and their lamps [P.crystal_vaults] |
| Tower undervaults | beneath the Crystal Tower | sealed lenses and an older foundation (Arena's final dungeon was the Tower itself) [Isle L10] |
| Direnni ruins | the Acropolis, Ald Mora | the clan that left [P.direnni_ruins] |
| Sea caves and vents | south and west coasts | the deep water and the Sload's vents [P.abyssal_vents] |
| Spider caves | upland | silk spiders [P.silk_spiders] |
| Cult chambers | inside cities and on Auridon | hidden Daedric worship [P.daedric_cults] |
| Time vaults | Artaeum | Psijic eddies [P.psijic_eddies] |

### 6.2 Themes and marquee

Province themes: ancestral vault guardians and lamp-wraiths; Direnni ruins; Daedric cultists; Maormer raiders;
abyssal vent creatures and Sload agents; silk spiders; gryphon aeries; smugglers; justiciar cells; Psijic time
guardians; Dominion marines in forts; sea-cave beasts.

| Region | Signature theme | Marquee dungeon | Other major |
| --- | --- | --- | --- |
| Summerset Isle | **Abyssal vents and the Sload's deep** | `cathedral_of_webs` (A, silk and light) and a new S plan: **the Crystal Tower's undervaults** (sealed lenses; honours Arena's last dungeon) | `tor_hame_khard`, `the_vaults_of_heinarwe`, `red_temple_catacombs`, `temple_of_the_mad_god` (Arena's first), `direnni_acropolis` |
| Auridon | **Maormer beachheads** | `the_banished_cells` (A, a Daedric prison) | `mehrunes_spite`, `toothmaul_gully`, `wansalen`, `entilas_folly`, `maormer_invasion_camp` |
| Artaeum | **Psijic time vaults** | `psijic_relic_vaults` (S, sliding walls and frozen rooms) | `traitors_vault`, `ceporah_tower`, `dreaming_cave` |

### 6.3 Counts

| Region | Now | Floor (8.9/km²) | Abs. (6.7/km²) | Unmarked (0.57/km²) | Action |
| --- | --- | --- | --- | --- | --- |
| Summerset Isle | 61 | 92 | 69 | 6 | +31: crystal vaults under estates (12), sea caves and vents (8), spider caves (4), cult chambers (3), Direnni (2), the undervaults; 6 unmarked family vaults |
| Auridon | 30 | 25 | 19 | 2 | at floor; identity; 2 unmarked coves |
| Artaeum | 5 | 4 | 3 | 1 | at floor; 1 unmarked eddy |

### 6.4 Landmarks

Region-own L kinds: crystal outcrop, gryphon stack, sea arch, abyssal vent, garden of sacred numbers (Isle); tidal
rock, dolmen, beacon falls (Auridon); eddy glade, Syrabane statue (Artaeum). Marquee landmarks:
`crystal_tower_summerset`, `direnni_acropolis`, `colosseum_of_the_old_ways`, `gryphon_aerie`, `isle_of_contemplation`.
Wonder moments: the Crystal Tower's beam sweeping the sea at night; prism light filling Alinor's avenues at noon; a
gryphon flight along the cliffs; an abyssal geyser erupting offshore; the mist lifting to show Artaeum; the Ancestors'
Vigil lamps across the isle.

---

## 7. Quests

### 7.1 Province arc (>= 19): **The Corrected Line**

The Thalmor have begun "correcting" the lineage registry with a Psijic relic that can alter records of the past, and
families are waking to find their ancestors changed. Acts: I Alinor: a kinlord house erased overnight (4); II Auridon:
the Glass Fever and a Daedric cult using the confusion (sum_2) while the Maormer land (4); III Artaeum: the Psijics,
the Skipped Hour (sum_1) and the relic's theft (4); IV Summerset Isle west: the Silk Library (sum_4) and the
Lampwrights' relit stones (t_welkynd) (4); V the Crystal Tower's undervaults and the choice (3). Choice: return the
relic to the Order, give it to the Thalmor, or let the kinlords break it. Consequence: registry state (houses restored
or erased) changes court dialogue, permits and which estates are open.

### 7.2 Factions and hubs

| Faction | Hub | Quests (have -> target) | Notes |
| --- | --- | --- | --- |
| Psijic Order | Artaeum (Ceporah Tower) | `psijic_order.json` 6 -> 10 | Time breaches, relics, the Old Ways |
| Thalmor | Alinor ministries | `thalmor.json` 5 + `aldmeri_dominion.json` -> 12 | An agent's line; justiciar work; a choice to turn |
| Sapiarchs' College (scholars' guild) | Lillandril / the Crystal Tower | 0 -> 6 | Books, lenses, the Welkynd thread |
| Auridon Sea-Watch (Maormer raids) | Vulkhel Guard / Hightide Keep | 0 -> 6 | Landings, a captured storm-caller, a raid on a beachhead |
| Kinlord houses (politics) | Alinor terraces | 0 -> 5 | Invitations, duels of etiquette, a house's fall |

Exclusive choice (P-J1 style, lore-led): at rank 3 the Psijic Order and the Thalmor ask for the same relic; serving
one closes the other's line.

### 7.3 Region chains

| Region | Chain | Places | Consequence |
| --- | --- | --- | --- |
| Summerset Isle | **The Vents Below**: abyssal geysers multiply; a Sload agent in a kinlord's household | alinor, sunhold, corgrad_abyssal_geyser, a sea cave | Vents sealed or one kept open by a house for profit |
| Auridon | **Landfall**: a Maormer storm-caller raises a storm and a fleet; the Sea-Watch is short of ships | vulkhel_guard, hightide_keep, maormer_invasion_camp, firsthold | Beachhead destroyed; raid scars heal in data |
| Artaeum | **The Hour's Keeper** (sum_1 extended) | isle_of_contemplation, colosseum_of_the_old_ways, psijic_relic_vaults | The Order's trust; the mist boat's schedule |

### 7.4 Settlement quests and radiant

MARQUEE chains (The Sixteenth Avenue, The Missing Folio, The Glass Floor, The Slipped Ring, Lamps at Dusk, The Tide
Gate, Lantern Poles) plus 2 favours per town. Culture radiant (>= 6): deliver a sealed letter between houses, check a
permit, light an ancestor's lamp in a vault, recover a book from a sea cave, take a licensed gryphon egg, report a
smuggler. `side_summerset.json` (13) placed with `why`. Quests 66 -> 192.

---

## 8. Components layout

| Component | File or system | Now | To do (phase) |
| --- | --- | --- | --- |
| World sheet | `tamriel/world/summerset.json`, lint | missing | SU0 |
| Terrain | `tamriel_vectors.json` cliffs, upland, ridge, coves, causeway, coral shelf | flat | SU1 |
| Biomes | `biomes.py` `micro` tables; 6 new sub-biomes | 3 per island | SU1 |
| Places | `tamriel/places/summerset.json` (+ `old_name` field for erased names) | 281 | SU0 |
| Roads and travel | `named_roads.json`, `ports.json` (4 ports, ferry, mist boat), pass gates, lens-stones | 2 ports | SU2 |
| City plans | `cityplans/alinor.json`, `lillandril.json` exist; add cloudrest, shimmerene, dusk, skywatch, vulkhel_guard, firsthold | 2 | SU3 |
| Styles | `styles/altmer.json` + 5 sub-kits, `dominion_quarter` overlay | 1 kit | SU3 |
| Dungeons | identity data, `marquee.json`, undervaults plan | identity 11% | SU4 |
| Creatures and scenes | `region_spawns` (gryphon, indrik, welwa, vent creatures, spiders, Maormer), `scenes.json` | 10 kinds | SU5 |
| Quests and lore | arc, factions, chains, `lore/books/*` (lineage records, old maps), `region_voice.json`, Ancestors' Vigil | 66 | SU6 |
| Optional | `events/summerset.json`: Maormer landings (Auridon), abyssal geysers (Isle) | | SU8 |

---

## 9. Modernisation: keep, amplify, replace

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| Terrain | Two islands and Artaeum; the lore-map outline | White cliffs, upland, ridge, coves, the Tower at 180 | The 20-30 block plain and Arena's oversized shape |
| Settlement list | Arena's eight city-states | Distinct plan forms (5.2) | Arena's Nord and Breton village names (kept only as old-map names) |
| Settlement life | Port and scholar roles | Lineage, permits, the pass gate | 3.9 named NPCs per town |
| Dungeons | Crystal Tower and the Mad God's temple (Arena's two) | Ancestral vaults, vents, time vaults | Generic caves (identity 11%) |
| Wilderness | Gryphons, indriks | A cultivated island with wild cliffs and wastes | Random spawns; only 10 creature kinds |
| Quests | Thalmor, Psijics, Maormer | The Corrected Line; the relic choice | Generator quests |
| Travel | Alinor and Firsthold ships | Open ports vs inland passes, the mist boat, lens-stones | Fade travel |
| Lore text | Aldmeri faith, the coup, the Sload plague | Region voices of propriety and fear | Any source text |

**What Arena did vs what we do:** Arena drew Summerset far too large with generic walled towns and Nordic village
names. We make it small, high and white, ruled by lineage, with Arena's names surviving only on the maps the Thalmor
tried to burn.

---

## 10. Floors and gates, implementation phases

### 10.1 Current vs target per region (parity 2026-10-13)

| Region | km² | Score (SET/NPC/QST/DUN/LOR) | Named towns | Dungeons now -> floor | Quests now -> target | Named NPCs now -> target | Lore places | Readables | `d_identity` | `y_looks` | `c_total` |
| --- | ---: | --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| Summerset Isle | 10.37 | 44 (44/27/35/54/25) | 11 | 61 -> 92 | 35 -> 145 | 35 -> 349 | 25 -> 118 | 59 -> 464 | 0.08 -> 1.0 | 3 -> 3 | 10 -> 25 |
| Auridon | 2.79 | 49 (37/27/45/57/34) | 5 | 30 -> 25 | 23 -> 39 | 19 -> 94 | 16 -> 32 | 36 -> 125 | 0.10 -> 1.0 | 1 -> 3 | 10 -> 25 |
| Artaeum (small) | 0.43 | 48 (22/18/76/87/68) | 0 | 5 -> 4 | 8 -> 8 (abs. >= 8) | 0 -> 15 | 6 -> 5 | 16 -> 19 | 0.60 -> 1.0 | 0 -> 1 | 10 -> 15 |
| **Province** | 13.6 | 45 | 16 | 96 -> 121 | 66 -> 192 | 54 -> 458 | 47 -> 155 | 111 -> 608 | 0.11 -> 1.0 | | |

Province: signature gear families 1 -> 8 (Thalmor, marine, Psijic, kinlord house sets), regional ingredients 12 ->
30, uniques 3 -> 6, music 2 -> 4, the Ancestors' Vigil, >= 2 travel modes. Artaeum is held to the small-region
rule: absolute floors, one marquee, one chain.

### 10.2 Gates

`worldsheet_lint.py` 0; `parity.py --assert-floor` per region; `layout_lint`; `roadplan_lint`; `reach_lint`;
`population_lint` (permit quarters staffed); `placement_lint`; `dungeon_identity_lint`; `novelty_lint --strict` and
MS20; F-HOOK, F-WEENIE (the Tower's beam counts across the sea), F-MICRO, F-ENC (>= 3 Summerset-only: pass check,
Maormer sail, gryphon egg licensing), F-WONDER, F-TRAVEL; Haiku contact sheet review.

### 10.3 Phases

| Phase | Scope | Gate |
| --- | --- | --- |
| **SU0** Sheet and fixes | `summerset.json`, re-tiers, renames with `old_name`, shrine conversion, Arena's two dungeons | worldsheet_lint 0 |
| **SU1** Terrain and micro-biomes | cliffs, upland, ridge, coves, coral shelf; 6 new sub-biomes | relief targets; F-MICRO |
| **SU2** Roads and travel | 4 named roads, pass gates, 4 ports, ferry, mist boat, lens-stones | roadplan_lint; F-TRAVEL |
| **SU3** Cities | 6 new plans, Alinor's 16 avenues, 5 sub-kits, permit quarters, courts | SET >= 80 at seats; `y_looks` >= 3 |
| **SU4** Dungeons | identity for 96; +31 on the Isle; 9 unmarked; undervaults plan | d_identity 1.0; F-LOOP |
| **SU5** Wilderness | creature kinds 10 -> 25; scripted encounters | CRE >= 80; F-ENC |
| **SU6** Quests and lore | arc, factions, chains, books, voices, the Vigil | QST, LOR floors; F-REACT |
| **SU7** Review | contact sheets and the four questions | G1 >= 80% |
| **SU8** Optional | Maormer landings, abyssal geysers (MS4) | if adopted |
