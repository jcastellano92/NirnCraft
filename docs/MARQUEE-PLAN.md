# Marquee implementation plan

Turns `docs/MARQUEE.md` (231 entries: 69 settlements, 58 dungeons, 43 landmarks, 61 minor places, spec in
`tools/marquee/marquee.json`) into work for Sonnet agents. Gap analysis: `docs/MARQUEE-GAP.md`. Read
`docs/AGENT-RULES.md` and `docs/FINISH-PLAN.md` section 0 first (budget, QA harness, merge rules, legal rule).
Never launch the Minecraft client; verify with `tools/qa`.

## 0. Why shared infrastructure comes first

Today a unique city costs a Java edit in eight places of `plan/Cities.java` (`reach`, `layoutRadius`, `back`, `style`,
`names`, `innIndex`, `gatesLocal`, `build`, plus a layout method), a new `Landmarks` case if it needs a new silhouette,
and a `Kits` int if it needs a new look. A unique dungeon costs a new `Special` enum value (saved by ordinal) plus
a builder. At 69 + 58 entries that is neither parallel nor safe. Three small pieces of infrastructure make the rest
mostly data:

| # | Infrastructure | Replaces | Owned files (new unless noted) | Size |
| --- | --- | --- | --- | --- |
| I1 | **CityPlan data format** + loader | the 8 per-place switches in `Cities.java` | `settlement/plan/CityPlans.java`, `settlement/plan/CityPlanJson.java`, `resources/skycraft/tamriel/cityplans/*.json`; edits to `Cities.java` (delegate when a plan exists) | L |
| I2 | **Style overlay per place** (+ per-ring/per-quarter tint) | adding a `Kits` int per city | `grammar/Style.java`/`Styles.java` (additive overlay lookup), `resources/skycraft/tamriel/styles/overlays/<placeId>.json` | M |
| I3 | **Landmark builder registry** | the `Landmarks.build` switch (conflict magnet) | `build/LandmarkRegistry.java`, `build/landmarks/<Province>Landmarks.java` (one class per province batch) | M |
| I4 | **Marquee dungeon plans** | the `Special` enum | `dungeons/world/MarqueePlans.java`, `Special.DATA` (append only), `resources/skycraft/dungeons/marquee/<placeId>.json` | L |
| I5 | **Quest chain generator** (expand `marquee.json` `q` into story-engine quest files) + quest-chain templates | hand-writing 125 chains | `tools/marquee/gen_quests.js` (JS), writes `quests/marquee_<province>.json`; runs `node tools/quest_lang.js` | M |
| I6 | **Lore/readable stub generator** (hook, rumour, 2 notes per marquee id) | hand-writing | `tools/marquee/gen_lore.js`, writes `lore/places/marquee_<province>.json`, then `node tools/place_lore.js` | S |
| I7 | **Named inns and shops as data** | `Cities.names` / `innIndex` (16 + 8 Skyrim-only) | `cityplans/*.json` `names` block; small additive change in `survival/inn/Innkeepers` to look up `survival.skycraft.inn.<placeId>` | S |
| I8 | **World-state hooks** (optional, late): quest-stage building swaps (Kvatch rebuilt), moon-phase, tide, seasonal site | none | `settlement/…/StageVariants` | M |

### I1 CityPlan format (sketch)

One JSON per place id, loaded at startup, validated by `tools/marquee/validate_cityplans.js` (new). It drives the
same `Plan` calls `Cities.java` makes by hand today (`Plan.at/put/place/line/street/wallRing`, `Layouts.fillStreets`,
`Zoning`), so existing Skyrim layouts keep working untouched.

```json
{ "id": "aldruhn", "extends": "zoning", "reach": 140, "layoutRadius": 120, "back": "north", "kit": "redoran",
  "overlay": "aldruhn",
  "landmarks": [ {"variant": "shell", "at": [0,-30], "size": [41,17], "h": 28, "flags": ["fixed"], "name": "skar"} ],
  "streets": [ {"from": [0,110], "to": [0,-10], "width": 5, "major": true}, {"ring": 60, "width": 3} ],
  "plazas": [ {"at": [0,40], "size": 17, "stalls": 6} ],
  "wall": {"r": 112, "sides": 16, "h": 10, "gates": [0, 1.57], "flags": ["major"]},
  "queue": [ {"kind": "inn", "size": [13,17], "flags": ["upper"], "name": "rat_walk_inn"},
             {"kind": "house", "count": 30, "flags": ["upper:2/3"]} ],
  "gates": [[0,110]], "names": {"inn": "the_rat_walk", "store": "", "smithy": ""} }
```

* `landmarks[].variant` is a registry id (I3), so a new silhouette never touches a switch.
* `extends: "zoning"` keeps the zoning planner's fill (houses, yards, lamps) and only pins the landmarks, the
  gates and the streets that must exist, which is how the 40+ B-tier towns stay S/M effort.
* Per-ring tint and per-quarter style (Imperial City districts, Cheydinhal's Dunmer bank, Ebonheart's Dunmer
  quarter): `"quarters": [{"rect": [...], "style": "dunmer"}]`, applied to specs by `Plan.district`.
* Tests: `/skysettle place <id>` and `/skysettle render` through `qa.ps1 -Server -ServerCmds`; layout lint
  (`py -3 -I tools/qa/layout_lint.py`) for spacing/roads; a new JSON schema check in `data_checks.py`.

### I4 Marquee dungeon plan (sketch)

`dungeons/marquee/<placeId>.json` selected in `DungeonStructure` when the site's place id has a plan (variant text
unchanged). It composes existing room libraries (`BarrowRooms`, `DwemerRooms`, `CultureRooms`, `FortRooms`,
`CaveRooms`, `NaturalRooms`) and prefabs (`Blueprints`), so most dungeons need data only:

```json
{ "id": "miscarcand", "theme": "ruin", "culture": "ayleid", "size": [96,40,96],
  "entrance": {"kind": "stair_down", "facing": "south"},
  "rooms": [ {"id": "gate", "kind": "culture:ayleid_gate"}, {"id": "cistern", "kind": "culture:cistern", "water": 0.6},
             {"id": "lamp_hall", "kind": "culture:lamp_hall", "blueprint": "lexicon_pedestal", "after": "cistern"},
             {"id": "vault", "kind": "boss", "after": "lamp_hall", "boss": "side_marq_miscarcand_warden"} ],
  "mechanism": {"kind": "lamp_gates", "count": 3}, "beats": ["lore", "ambush", "elite"], "story": "miscarcand" }
```

Mechanism kinds the batches add (each a small Java class in `dungeons/puzzle` or `dungeons/world/Mechanisms`):
`rising_water`, `tide_gate` (time of day), `lamp_gates` (lit/unlit light blocks), `moving_walls` (block swaps),
`ash_storm` (particle + short sight), `rotating_floor`, `pilgrim_trials` (trap + lectern), `name_plates`
(read-aloud lecterns). Tests: `/skycraft-dungeon-qa export <id>`, `tools/qa/dungeon_audit.py`,
`tools/qa/dungeon_reach.py` (reachability), `/skycraft-dungeon-qa run <id>` for mechanisms.

### Quest chains (I5)

`marquee.json` already carries per entry `q: {t, s[], u}` and 2-3 NPCs. The generator maps stages to the objective
types the engine has (`go`, `talk`, `kill`, `clear`, `retrieve`, `deliver`, `escort`, `wait` with `hours`, `cond`, plus `boss_id` spawns, `choices` for the moral forks and `actions` for scenes) using 8 templates: **delve**,
**retrieve-and-return**, **escort**, **investigate** (3 talks + 1 clear), **heist** (retrieve with alarm),
**contest** (timed race/duel), **ritual** (talk + go + effect), **defend** (hold N waves). `q.u` is kept as a
design note, never shipped. Quest sites use real place ids (`place: "<id>"`), not quest-private sites, so each
quest advertises its dungeon on the map. Text is written by the batch agent per quest (original); the generator only
scaffolds JSON and speakers. After generating: `node tools/quest_lang.js` and `/skycraft-quest selftest flow`
(via `qa.ps1 -Server`).

## 1. Batches, in value order

"Data" = JSON/JS only, no Java. "Code" = Java. Every batch ends with: `node tools/marquee/validate.js`,
`node tools/place_lore.js`, `node tools/quest_lang.js` (if quests/lore touched), `py -3 -I tools/qa/layout_lint.py`
(if places or plans), `tools\qa\qa.ps1 -Server -NoBuild -ServerCmds "<commands in the batch>"` (after one full
`qa.ps1` build for code batches). One agent per batch; at most 3 batches in parallel; batches in the same wave
touch disjoint files.

### Wave 1: infrastructure (parallel; nothing depends on anything else)

| Batch | Scope | Files owned | Type | Effort | Proves itself by |
| --- | --- | --- | --- | --- | --- |
| **B1 CityPlans + overlays + landmark registry** (I1, I2, I3, I7) | loader, JSON schema check, delegate from `Cities`, register the 19 existing landmark variants as ids, overlay lookup, named inns/shops as data; convert `wayrest`, `orsinium`, `torval`, `sentinel` to JSON and make them differ (fixes the two copies); do NOT convert Skyrim cities | `settlement/plan/CityPlan*.java`, `build/LandmarkRegistry.java`, `grammar/Styles` (additive), `resources/.../cityplans/*`, `tools/marquee/validate_cityplans.js`, `tools/qa/data_checks.py` (check only) | Code | L | `/skysettle place wayrest|orsinium|torval|sentinel`, `/skysettle compare`, layout lint, `qa.ps1 -Server` |
| **B2 Marquee dungeon loader** (I4) | `MarqueePlans`, `Special.DATA`, 4 mechanisms (`rising_water`, `lamp_gates`, `rotating_floor`, `name_plates`), pilots `miscarcand` and `ruins_of_mazzatun` | `dungeons/world/MarqueePlans.java`, `Special.java` (append), `dungeons/puzzle/*` (new), `resources/.../dungeons/marquee/*` | Code | L | dungeon export + audit sheets + reach |
| **B3 Quest + lore pipeline and the S-tier text** (I5, I6) | generators, 8 templates, then write the quests, NPCs and lore for the 12 S-tier settlements and the 12 S-tier dungeons only (quests reference their ids; builds follow later) | `tools/marquee/gen_*.js`, `quests/marquee_<prov>.json`, `quests/index.json`, `lore/places/marquee_*.json`, `lang` via tools only | Data | M | `quest_lang.js`, `place_lore.js`, `/skycraft-quest selftest flow` |

### Wave 2: settlements by province (each depends on B1; order = value)

Each batch writes the CityPlan JSON + overlay + new landmark builders in its own `build/landmarks/<Province>Landmarks.java`
(never in `Landmarks.java`), the named inn and shop, and lang for the place's new signs. Provinces' settlement
batches do not touch each other's files.

| Batch | Entries (marquee ids) | New builders / mechanisms | Effort | Notes |
| --- | --- | --- | --- | --- |
| **B4 Morrowind cities** | vivec (canton kit overrides, per-canton signs), mournhold (bazaar arcade + mirror hook), balmora, aldruhn, sadrith_mora, necrom, molag_mar, ebonheart | `shell` (crab-shell hull), `mushroom_spire`, `tomb_rows` (MAUSOLEUM block run), `lava_channel` (yard prop), `star_bastion` wall option, `arcade_street` archetype | L | The Great Houses split cleanly into kits that already exist (Hlaalu, Redoran, Telvanni, Velothi); best reference data (`morrowind_ref/markers.json`). Highest visual payoff. |
| **B5 Cyrodiil cities** | imperial_city (district tints), bruma, bravil, skingrad, anvil, kvatch, chorrol, cheydinhal | `great_oak` (scaled GIANT_TREE), `lucky_lady_statue`, `slum_stack` yard props (rope bridges, stairs), `vineyard` yard set, `lighthouse` (reuse Anvil blueprint), damaged-shell variant | L | Cheydinhal/Kvatch want I8 for the finale; ship without it first. |
| **B6 High Rock cities** | wayrest, orsinium (stepped bastions + gate hammer), daggerfall, camlorn, shornhelm, northpoint, evermor, glenpoint | `gate_hammer`, `stepped_bastions`, `needle` roof type, `chain_boom`, `causeway_island` | L | B1 already makes Wayrest and Orsinium differ; this batch makes them great. |
| **B7 Hammerfell + Elsweyr** | sentinel, rihad, taneth, elinhir, dragonstar, abahs_landing, stros_mkai; torval, rimmen, senchal, dune, corinthe, orcrest, hakoshae | `awning_street`, `minaret`, `twin_moon_domes`, `ring_orrery`, `caravan_wheel`, `tent` archetype | L | Share the `awning_street` and `dome` work between provinces; one agent. |
| **B8 Valenwood + Summerset** | elden_root (tiers, inner stair), falinesti (4 sites), woodhearth, silvenar, rawlkha, arenthia, marbruk; alinor (radial), lillandril, cloudrest, skywatch, dusk, firsthold, shimmerene | `canopy_platform` (branches + rope bridges), `walking_city` (4-site plan), `library_terrace`, `ring_orrery`, `radial_avenues`, `glass_balcony` | L | Falinesti needs an I8 seasonal rule; as a first pass show the same plan at all four sites. |
| **B9 Black Marsh + Solstheim + foreign** | lilmoth, stormhold, gideon, helstrom, thorn, soulrest, murkmire; raven_rock, skaal_village, thirsk_mead_hall, tel_mithryn, fort_frostmoth; kjolvik, tiger_plains_village, tang_mo_harbor_village, pyandonean_fleet_anchorage, hundings_dojo | `twin_ziggurat`, `hist_tree`, `whale_rib_arch`, `pagoda_roof`, `raft_fleet` (sailing platform), `yurt_ring` | L | Lower priority: fewer players, larger quality jump needed. |

### Wave 3: dungeons by province (each depends on B2; set pieces parallel with Wave 2)

One batch per province group; each uses the plan format, existing room libraries and 1-3 mechanisms. Order of value:

| Batch | Entries | Mechanisms to add | Effort |
| --- | --- | --- | --- |
| **B10 Morrowind + Solstheim** | kogoruhn (ash storm), ald_sotha (pilgrim trials), arkngthand, halls_of_fabrication (animunculi_forge), old_mournhold (mirror plan), missamsi; temple_of_miraak (shifting walls), nchardak, castle_karstaag_ruins (scale-x2), kagrumez | `ash_storm`, `pilgrim_trials`, `moving_walls`, `mirror_plan` | L |
| **B11 Cyrodiil + High Rock** | miscarcand (B2 pilot), sancre_tor, frostcrag_spire, deepscorn_hollow, dark_brotherhood_sanctuary_cyrodiil; old_orsinium, cath_bedraud, mthartzel, sanguines_demesne, doomcrag, tomb_of_lost_kings | `tide_gate`, `no_ground_entrance` (ferry), `social_dungeon` (choice, no boss), `effigy_vigil` | L |
| **B12 Hammerfell + Elsweyr** | lost_city_of_the_na_totambu (sand shafts), tuwhaccas_throne, motalion_necropolis, volenfell, ahaleb_necropolis, sanctum_ophidia; rimmen_necropolis (lunar clock), halls_of_colossus, tomb_of_the_moon_singers (note puzzle), vault_of_the_heavenly_scourge, temple_of_agamanus, prowls_edge_dragonscour | `sand_shafts`, `lunar_clock`, `note_puzzle`, `skylight_shutters` | L |
| **B13 Valenwood + Summerset + Black Marsh** | city_of_ash, ne_salas, tomb_of_the_apostates, reliquary_of_stars, ossuary_of_telacar, labyrinth; psijic_relic_vaults (moving walls + time-freeze), cathedral_of_webs, the_banished_cells, traitors_vault, tor_hame_khard, mehrunes_spite; ruins_of_mazzatun, shrine_of_the_black_maw, bogmother, temple_of_sul, the_vile_pavilion, teeth_of_sithis | `time_freeze`, `spiral_climb`, `banquet_etiquette` | L |
| **B14 Foreign** | wolf_jarls_howe (ship barrow), frost_throne_of_kamal, sunken_temple_of_the_yokudan_kings (air pockets), coral_tower_of_thras, orgnums_citadel | `air_pockets`, `ship_hull_room` | M |

### Wave 4: moments and minor places (data-heavy; run any time after B3)

| Batch | Scope | Type | Effort |
| --- | --- | --- | --- |
| **B15 Landmarks and moments** | the 43 landmark entries: 16 reuse existing styles (peak, monument, altar, stones, island) with lore + a staged encounter or vista trigger; 12 need small builders (`stalhrim_source`, `ghostfence`, `sword_field`, `giant_ribs`, `colonnade`, `aqueduct`, `eyrie`, `waterfall` sound); 6 need the I8 time/phase gate | Code + Data | M |
| **B16 Minor places** | 61 entries: lore hook + rumour + one readable each (via `gen_lore.js`), 20 of them a 1-stage mini-quest, 10 get a named innkeeper; no new geometry | Data | S |
| **B17 B-tier towns pass** | for the ~100 non-marquee named towns: `zoning` forms chosen from the sheet, one overlay per province-region (Stormhaven vs Rivenspire vs Glenumbra), 1 named inn + 1 NPC + 1 quest from a template (`gen_quests.js --fill-towns`) | Data | M |

## 2. What the first three batches are

1. **B1 CityPlans + landmark registry + overlays** (code, L). It makes every other settlement batch
   data-and-small-builder work, kills the two copy-paste capitals, and gives Skyrim-level named inns as data.
2. **B3 Quest + lore pipeline with the S-tier text** (data, M). Zero Java risk, immediately fixes the "no NPC, no
   quest, no rumour" half of the gap for the 12 biggest settlements and 12 biggest dungeons.
3. **B2 Marquee dungeon loader with 2 pilots** (code, L). Opens the dungeon side for Wave 3.

They touch disjoint files and can run at once. After them, **B4 Morrowind cities**, then **B5 Cyrodiil cities**
and **B10 Morrowind/Solstheim dungeons**.

## 3. Definition of done for a marquee entry

Settlement: unique `sil` tag in its CityPlan (and no other plan shares silhouette + landmark + palette); landmark
visible at 150 blocks; named inn, one shop, one smithy sign; 3 named buildings with signs; 3 named NPCs with greetings
(`quests/marquee_*.json` `npcs`); the quest chain in the story engine and passing `selftest flow`; lore entry with
hook, rumour and two notes; layout lint clean; one contact-sheet render reviewed by Haiku.
Dungeon: plan JSON; at least one mechanism or set piece from the brief; boss from the quest's `bosses`; lore
lectern; passes `dungeon_reach.py`; audit sum >= 26/35.
Landmark: visible from 200 blocks or has a staged encounter; lore hook.
Minor place: hook + rumour + one readable; appears in `Heard of any trouble hereabouts?`.

## 4. Risks and traps

* **Merge traps** (FINISH-PLAN section 0): index files union (`quests/index.json`, `places/index.json`,
  `lore/places/index.json`); lang is generated, never hand-merged; Gradle build only once per merge.
* **Save compatibility.** `Special` and `Archetype` ordinals are saved in pieces: append only. New CityPlan landmarks
  go through the registry by string id, not new ordinals; if a builder must be a new `Archetype`, append at the end.
* **Place geometry.** Marquee settlement radii (150) are already spaced; B-tier plans must not grow `reach`
  beyond the place's radius + gap (layout lint `spacing`). Roads are pre-planned from gate points
  (`Cities.gatesLocal`): a plan that moves gates must keep them within 10 blocks or road generation needs a rerun
  (`py -3 -I tools/…` per FINISH-PLAN).
* **Performance.** Large landmarks (shell, ziggurat, tree) are painted per chunk column; keep each under
  ~40k blocks and test with `/skysettle render`.
* **Legal.** Reference installs give names and positions only. NPC names are invented (a few canonical names in
  marquee.json are place/lore facts); every greeting, quest text and readable is original.
* **Skyrim cities are out of scope**: do not convert them to JSON in B1; they work and are the benchmark.
