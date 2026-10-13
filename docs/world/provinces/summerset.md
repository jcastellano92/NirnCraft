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

Data target: `tamriel/world/summerset.json` (MS22). Field ids are data ids; cites in brackets; `P.` is the province.

### 1.1 Province world truth

Summerset is the Altmer homeland and the Thalmor's heart: two bright islands of white cliffs, crystal-strewn coasts,
terraced vineyards and ordered gardens, with Artaeum, the Psijics' island, half in the mist. Lineage decides a life
here: kinlord houses rule cities, Sapiarchs keep learning at the Crystal Tower, the Thalmor govern from Alinor and
their justiciars watch everyone, and outsiders work in harbour quarters on permits. The Altmer honour their ancestors
in crystal vaults and deny that any man became a god. The sea is the danger: Maormer raid Auridon, the Sload's vents
breach the coast, and hidden Daedric cults grow inside the perfect cities. Arena's names survive only on old maps.

### 1.2 Province sheet (P)

| Layer | Fields `id`: meaning [cites] |
| --- | --- |
| L1 Geology | `white_cliffs`: pale limestone cliffs round both islands, 40-80 blocks, broken by harbour coves. `central_upland`: a granite upland in the middle of Summerset Isle, to 120, where the Crystal Tower stands. `auridon_ridge`: Auridon's spine, 60-120. `crystal_seams`: quartz and crystal veins that weather out onto the beaches. `terrace_loams`: well-drained loams on the cliff-top downs. `sea_trench`: deep water close to the south and west shores. `coral_shelf`: reefs on the warmer southern shore |
| L2 Climate | `mild_maritime`: mild, sunny and even all year [white_cliffs]. `bright_air`: clear air and strong light that sets the crystal glittering [mild_maritime, crystal_seams]. `west_fogs`: sea fogs off the western ocean [sea_trench]. `south_gales`: storms arrive from the south over deep water [sea_trench]. `warmer_auridon`: Auridon is warmer and wetter, almost tropical [auridon_ridge]. `artaeum_mist`: a standing mist round Artaeum that does not follow the weather [west_fogs] |
| L3 Water | `short_streams`: short clear streams off the upland, falling over the cliffs [central_upland]. `cisterns_and_fountains`: cities draw from engineered cisterns and enchanted fountains [short_streams]. `water_wardens`: each city's water is held by its kinlord house and released to public fountains on a schedule; inland estates hold their own springs [cisterns_and_fountains]. `tidal_causeway`: Firsthold reached at low tide [white_cliffs] |
| L4 Ecology | `garden_island`: a managed landscape of hedged gardens, orchards and vineyards; true wild land survives only on the upland, the cliffs and the wastes [terrace_loams, mild_maritime]. `vine_terraces` [terrace_loams]. `cliff_gryphons`: gryphons nest on the high cliffs [white_cliffs]. `indrik_downs`: indriks and deer on the meadow downs [garden_island]. `crystal_shores`: sea birds, crabs and glittering sands [crystal_seams]. `silk_spiders`: giant spiders in old upland caves, their silk prized [central_upland]. `salt_wastes`: the Corgrad Wastes, a scoured salt-and-rock tableland where nothing is planted [central_upland, south_gales]. `reef_life` [coral_shelf] |
| L5 Economy | `wine`: the isles' famous wine [vine_terraces]. `crystal_and_lenses`: crystal cut for lenses, focus stones and glass [crystal_seams]. `learning`: books, enchantment and instruction, exported and guarded [crystal_and_lenses]. `silk` [silk_spiders]. `ships`: eagle-prowed ships from cove yards [white_cliffs]. `pearls_and_fish` [reef_life]. `gryphon_eggs`: taken under licence, tithed to the houses [cliff_gryphons]. `restricted_imports`: grain, labour and raw goods come through two open ports only [ships] |
| L6 Settlement | `harbour_cities`: cities stand at the breaks in the cliffs where streams reach the sea [white_cliffs, short_streams]. `upland_colleges`: the Crystal Tower and the colleges on the upland [central_upland]. `vineyard_villas`: estates and villages on the downs [vine_terraces]. `auridon_frontline`: Auridon's towns face the sea lanes and Valenwood; foreign ships land there first [auridon_ridge, restricted_imports]. `busy_centres`: Alinor (capital, Thalmor seat), Shimmerene (the open port), Lillandril (scholars), Vulkhel Guard (Auridon's open port), Skywatch (Auridon's court) [harbour_cities, learning, restricted_imports]. `failing_places`: estates of fallen lines, villages at the Corgrad edge, fishing hamlets after Maormer raids [salt_wastes] |
| L7 Society | `lineage_registry`: rank by recorded lineage; marriages arranged and registered [harbour_cities]. `kinlord_houses`: old houses rule cities and hold the water [water_wardens]. `sapiarchs`: scholar-priests of the Crystal Tower [upland_colleges]. `thalmor_order`: Thalmor officials and justiciars over all [kinlord_houses]. `lesser_lines`: Altmer of doubted lineage as servants, sailors and labourers [lineage_registry]. `permit_outsiders`: Bosmer, Khajiit and other Dominion subjects on work permits in harbour quarters, barred from the interior without a pass [restricted_imports]. `dissidents`: Altmer who reject the Thalmor, watched or vanished [thalmor_order] |
| L8 Culture | `ancestor_faith`: the Aldmeri gods as ancestors (Auri-El, Magnus, Syrabane, Phynaster, Xarxes, Mara and others); family vaults and ancestor shrines [lineage_registry]. `no_man_god`: the denial that any man became a god; Talos worship hunted [ancestor_faith, thalmor_order]. `propriety`: etiquette, beauty and order as virtues; a college teaches them [lineage_registry]. `old_ways`: the Psijics' older philosophy, outside the Thalmor's faith [artaeum_mist, ancestor_faith]. `outsiders_lesser`: other peoples are tolerated, not equal [permit_outsiders]. `magic_heritage`: magic as birthright and duty [learning]. `lamplit_dead`: the dead keep their lamps in the family vault [ancestor_faith] |
| L9 Politics | `thalmor_rule`: the Thalmor govern the isles and the Dominion from Alinor [thalmor_order]. `kinlord_resentment`: old houses resent the Thalmor's grip [kinlord_houses]. `psijic_apart`: Artaeum answers to no one; the Thalmor want its relics [old_ways]. `maormer_war`: Pyandonea raids Auridon and the south coast [south_gales, auridon_frontline]. `sload_shadow`: Sload agents and their vents in the southern sea [sea_trench]. `dominion_clients`: Valenwood and Elsweyr governed through envoys [thalmor_rule]. Festival (MS16): **the Ancestors' Vigil** at midwinter, when every house lights its vault and the Crystal Tower turns its beam to the sea [lamplit_dead, upland_colleges] |
| L10 Hidden | `crystal_vaults`: ancestral vaults of crystal and lamps under family estates [lamplit_dead, crystal_seams]. `direnni_ruins`: ruins of the clan that left for High Rock [harbour_cities]. `daedric_cults`: hidden cults inside the perfect cities, for those the order crushes [dissidents, propriety]. `abyssal_vents`: Sload-made geysers that open from the deep and spill sea creatures [sload_shadow, sea_trench]. `maormer_beachheads` [maormer_war]. `justiciar_cells`: secret prisons for dissidents [thalmor_rule]. `spider_cathedral` [silk_spiders]. `smuggler_coves`: outsider smugglers in sea caves [permit_outsiders]. `psijic_eddies`: places where time does not run straight [old_ways] |
| L11 History | `aldmeri_landing`: the Aldmer settle the isles in the Merethic Era and raise the Crystal Tower. `direnni_departure`: the Direnni clan leaves for High Rock. `sload_plague`: the Sload's plague in the First Era and the allied fleet that sank Thras. `first_dominion`: the Second Era alliance of Altmer, Bosmer and Khajiit. `thalmor_coup`: early in the Fourth Era the Thalmor take power and rename the isles Alinor, erasing foreign names. `great_war`: the war on the Empire and the Concordat. `old_maps`: Imperial-era maps still carry the erased names [thalmor_coup] |

### 1.3 Region sub-sheets

**Summerset Isle** (`summerset/summerset_isle`, 10.37 km², capital Alinor, levels 25-45; large: an east half round
Alinor, Shimmerene and Dusk, and a west half round Lillandril and Cloudrest).
World truth: the larger island, the most ordered land in Tamriel. Alinor's sixteen avenues radiate from the Crystal
Tower's sight-line; Shimmerene is the one port where outsiders crowd; Lillandril's libraries stand open to the sky;
Cloudrest hangs over the sea. Vineyards climb the downs, gryphons nest on the cliffs and the Corgrad Wastes lie salt-
white inland. Under the order, kinlord houses fight the Thalmor by etiquette, Sload vents breach the south shore and a
cult of the Mad God remembers a temple Arena knew.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `tower_upland` [P.central_upland]. `south_shelf` [P.coral_shelf]. `corgrad_tableland` [P.salt_wastes] |
| L2 | `prism_noon` [P.bright_air]. `west_fog_coast` Lillandril and Cloudrest [P.west_fogs] |
| L3 | `alinor_aqueduct` [P.short_streams]. `upland_springs` (Norlor, Calinaan, Faaril) [P.short_streams] |
| L4 | `crystal_coast`, `vine_terraces`, `meadow_downs`, `gryphon_cliffs`, `salt_waste`, `coral_forest`, `ornamental_ring` (section 2) |
| L5 | `alinor_court_economy` [P.learning]. `shimmerene_open_port` [P.restricted_imports]. `lillandril_books` [P.learning]. `wineries` (Oleander Coast, Queiisen, Torancar, Valiel, Calandil) [P.wine] |
| L6 | `alinor_capital` [P.harbour_cities, alinor_aqueduct]. `east_half` Shimmerene, Dusk, Sunhold [P.harbour_cities]. `west_half` Lillandril, Cloudrest, West Guard [west_fog_coast]. `colleges` Sapiarchs, Illumination Academy [P.upland_colleges] |
| L7 | `alinor_houses` [P.kinlord_houses]. `shimmerene_permit_quarter` [P.permit_outsiders]. `vineyard_labour` [P.lesser_lines] |
| L8 | `tower_faith` [P.ancestor_faith]. `sacred_numbers` a garden of geometry as devotion [P.propriety] |
| L9 | `house_vs_thalmor` [P.kinlord_resentment]. `geyser_alarm` [P.sload_shadow] |
| L10 | `abyssal_geysers` (signature theme: the Corgrad, Direnni, Rellenthil, Sil-Var-Woad, Sunhold and Welenkin vents) [P.abyssal_vents]. `cathedral_of_webs` [P.spider_cathedral]. `tower_undervaults` [P.crystal_vaults, tower_upland]. `temple_of_the_mad_god` (Arena's starting dungeon) [P.daedric_cults] |
| L11 | `direnni_acropolis` [P.direnni_departure]. `erased_names` [P.old_maps] |

**Auridon** (`summerset/auridon`, 2.79 km², seat Firsthold, levels 22-44).
World truth: the warmer northern island, Summerset's front door and its front line. Foreign ships must land at
Vulkhel Guard; Firsthold sits on a tidal rock, Skywatch on its hill under a great orrery. Maormer raiders strike the
coves and the Dominion marines answer from Hightide Keep. In the Banished Cells and Mehrunes' Spite, Daedric cults
the Thalmor deny exist are growing. Auridon's people are prouder of their island's history than of its quiet.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `ridge` [P.auridon_ridge]. `tide_rock` Firsthold [P.tidal_causeway] |
| L2 | `warm_wet_auridon` [P.warmer_auridon] |
| L3 | `ridge_falls` Beacon Falls [P.short_streams] |
| L4 | `palm_groves`, `ridge_pines`, `crystal_coast`, `tidal_flats`, `raid_scar`, `dolmen_heath` (section 2) |
| L5 | `vulkhel_open_port` [P.restricted_imports]. `fisheries` [P.pearls_and_fish]. `skywatch_learning` the orrery [P.learning] |
| L6 | `firsthold_seat` [tide_rock]. `skywatch` [ridge]. `vulkhel_guard` [vulkhel_open_port]. `mathiisen` [P.vineyard_villas] |
| L7 | `marines` [P.thalmor_order]. `vulkhel_outsiders` [P.permit_outsiders] |
| L8 | `propriety_college` the College of Aldmeri Propriety [P.propriety] |
| L9 | `maormer_front` [P.maormer_war]. `cult_denial` [P.daedric_cults] |
| L10 | `maormer_beachheads_auridon` (signature theme: `maormer_invasion_camp`, sea caves, wrecks) [maormer_front]. `banished_cells`, `mehrunes_spite` [cult_denial]. `heritance_proving_ground` [P.crystal_vaults] |
| L11 | `first_landing_lore` Firsthold's claim to be the oldest settlement [P.aldmeri_landing] |

**Artaeum** (`summerset/artaeum`, 0.43 km², levels 30-45; a small region).
World truth: the Psijics' island off Summerset, there in the mist and sometimes not there at all. Ceporah Tower, the
Grand Psijic Villa and a ruined older college stand among lotus pools under the Statue of Syrabane. The Order keeps
the Old Ways and relics no one else may touch; time runs strangely in its vaults and in the Dreaming Cave. The
Thalmor want those relics. A boat comes for those the Order invites, and only in the mist.

| Layer | Fields [cites] |
| --- | --- |
| L1 | `isle_rock` [P.white_cliffs] |
| L2 | `standing_mist` [P.artaeum_mist] |
| L3 | `lotus_pools` [P.short_streams] |
| L4 | `mist_forest`, `lotus_meadow`, `crystal_spires`, `shore_of_stones`, `eddy_glade`, `syrabane_heath` (section 2) |
| L5 | `relic_keeping` [P.old_ways]. `craftworks` [P.learning] |
| L6 | `psijic_villa` and `ceporah_tower` [relic_keeping] |
| L7 | `order_ranks` monks, ritemasters, loremasters [P.old_ways] |
| L8 | `syrabane_statue` [P.ancestor_faith]. `old_ways_rite` [P.old_ways] |
| L9 | `thalmor_want_relics` [P.psijic_apart] |
| L10 | `psijic_time_vaults` (signature theme: `psijic_relic_vaults`, `traitors_vault`, `dreaming_cave`) [P.psijic_eddies] |
| L11 | `vanishing_isle` the island has left the world before and come back [standing_mist] |

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
