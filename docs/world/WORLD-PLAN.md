# World plan: the whole of Nirn, from here to done

This is the master plan for Skycraft's world. It says what the world must become, how the plan is laid out on disk,
in which order the provinces are built, and the execution roadmap from today to the final release, phase by phase.
Every phase has a ready-to-run prompt in `docs/prompts/`. A new session starts at `docs/START-HERE.md`.

**The state table (section 7) is the single source of truth for progress. Update it on every merge.**

---

## 1. Vision

A Skyrim total conversion for Minecraft that covers the whole of Tamriel and the lands around it, built so that
**every province is as full, as unique and as purposeful as Skyrim, measured**. The player can cross from Whiterun to
the Imperial City, sail the Iliac Bay, ride a silt strider over the Ashlands or follow a caravan into Elsweyr, and in
every region find what made Skyrim great: a hold you recognise in seconds, a skyline that pulls you off the road, towns
of named people with jobs and problems, dungeons with a story and a shortcut home, quests that change a place, and long
quiet walks between dangers.

The world is built **from the ground up**, in causal order: rock, climate, water, ecology, economy, settlement,
society, belief, politics, the hidden underground and history. Everything a player meets (a town's plan, a miner's
complaint, the bandits on a road, the draugr in a barrow, the music in a marsh) traces back to its place's world model
sheet. The owner sees that depth as the heart of the project.

Three things this plan will not do:

* Import legacy quantity. Daggerfall's 15,000 dots and Arena's generated towns are a map of where things are, not a
  list of what to build (keep / amplify / replace).
* Treat ESO as the bar. The bar is Skyrim, measured from the real game. ESO ideas are an optional layer on top.
* Ship anything from any game. Facts and design principles only (the legal line, section 2).

## 2. Governing principles (read, do not duplicate)

The rulebook is `docs/world/DESIGN-PRINCIPLES.md`. This plan only points into it:

| Rule | Where | One-line summary |
| --- | --- | --- |
| World building is king | first principle | Every element expresses its place's history, culture, conflict, economy, faith and ecology, and agrees with every other element there |
| The causal world model sheet (11 layers) | first principle | L1 geology, L2 climate, L3 water, L4 ecology and micro-biomes, L5 economy, L6 settlement pattern, L7 society, L8 culture and belief, L9 politics, L10 the hidden and underground, L11 history; every field cites a cause above it; every content record carries `why: [sheet ids]` |
| The coherence check | first principle | Sheet complete and causal, every element traces back, no contradiction, the four questions, a human (Haiku) read |
| Non-negotiables | section 0 | Unique, purposeful placement, lived-in towns, roads to everything, marquee places, meaningful quests, paced peace and danger, signposting, rewarded discovery, Skyrim quality everywhere |
| Keep / amplify / replace | section 1 | Legacy sources give geography and structure, never quality; the 1.3 checklist runs in every province phase |
| What made Skyrim great | 4.0 (G1-G12) | Identity in seconds, skylines, environmental stories, loop dungeons, towns are people, consequences, roads reveal the land, wonder, secrets, mood, reactivity, micro-biomes |
| The measured Skyrim floor | 4.1-4.9 | Per region `max(absolute floor, round(median density x km²))`; per settlement the tier medians (capital 73 named NPCs, 27 interiors, 23 named buildings, 15 merchants, 5 trainers...) |
| Micro-biomes | MS21 | Every region is a mosaic of >= 6 micro-biomes (>= 2 per km²) caused by elevation, aspect, rain shadow, water, heat, soil and disturbance |
| Missing systems | MS1-MS20, MS22 (4.11) | Short specs; part floor, part optional layer |
| Optional ESO-style layer | 4.12 | Only on top of a region that meets the floor, only through its sheet |
| Gameplay flow and FLW metrics | section 5 | F-HOOK, F-VISTA, F-WEENIE, F-DETOUR, F-SIGN, F-TRAVEL, F-ENC, F-WONDER, F-QUIET, F-LOOP, F-REACT, F-MICRO, F-WHY... |
| Definition of done for a region | 5.5 | Sheet passes lint, floors met, parity floor, FLW floors, novelty and verbatim 0, reach, modernisation ticked, G1-G12, Haiku review |

Companions: `docs/world/SCALE-AND-GEOGRAPHY.md` (one scale model for every source, per-province relief targets, roads
and travel), `docs/world/ILIAC-BAY.md` (the High Rock and Hammerfell plan, phases IB1-IB8, owner decisions),
`docs/WORLDGEN-PIPELINE.md` (plan-first generation P1-P7 and the fast loop), `docs/PARITY.md` (scorecard and the
PA0-PA22 backlog), `docs/MARQUEE.md` and `docs/LORE-WEB.md` (the 231 places that must say "wow", 39 arcs, 8 threads),
`docs/ECOLOGY.md`, `docs/SKYRIM-SWEEP.md`, `docs/DUNGEON-AUDIT.md`, `docs/FIRST-PLAYTEST.md`, `docs/AGENT-RULES.md`.

**Source precedence (owner decision, 2026-10-12).** Skyrim, Oblivion (Cyrodiil) and Morrowind (Vvardenfell,
Solstheim) positions are canonical. Daggerfall and Arena may move or add places only where those three games have no
say (High Rock, Hammerfell, the Iliac Bay towns, the Arena-only provinces). Between provinces the lore map wins.

**Legal line.** Facts only: names, relative positions, counts, which features existed, why a design works. Nothing from
any game's files (text, meshes, textures, audio, rasters) enters the repo, raw or converted; terrain is hand-traced
vectors with heights we choose; every line of text is our own. Reference extractions live in the gitignored
`.reference/`.

**Corrections to the design docs since they were written.** NPC daily routines are no longer a gap: housing, jobs,
schedules and `society/NpcPlacement` exist (commits 3b17eb0, 781287a). **Trainer roles are still missing** (R3d).

## 3. The province set and the file layout

### 3.1 Provinces and regions (71 land regions in `regions.json` today, plus the Iliac Bay realms)

| Province | km² | Regions today | Best source (precedence) | Parity mean now |
| --- | ---: | --- | --- | ---: |
| Skyrim | 26.5 | 9 holds | Skyrim (canonical) | 95 (SET 53) |
| High Rock | 40.8 | glenumbra, rivenspire, stormhaven, bjoulsae, wayrest, wrothgar (+ new realms `daggerfall`, `anticlere`, `dwynnen`, IB2) | Daggerfall inside the Iliac window, Arena outside | 42 |
| Hammerfell | 25.3 | alikr_desert, bangkorai, craglorn, dragontail, hews_bane, stros_mkai (+ new realm `sentinel`, IB2) | Daggerfall inside the window, Redguard for Stros M'Kai, Arena outside | 43 |
| Cyrodiil | 61.2 | 9 | Oblivion (canonical) | 48 |
| Morrowind | 36.7 | 13 incl. Solstheim | Morrowind (canonical) for Vvardenfell and Solstheim, Arena for the mainland | 53 |
| Black Marsh | 38.0 | 6 | Arena | 40 |
| Elsweyr | 25.7 | 5 | Arena | 44 |
| Valenwood | 24.2 | 4 | Arena (drawn oversized: 40 blocks/px) | 44 |
| Summerset | 13.6 | 3 | Arena (40 blocks/px) | 45 |
| Foreign lands | small | Atmora 2, Akavir 4, Yokuda 2, Pyandonea 1, Thras 1 | lore; half-density template (expeditions) | 31-32 |

The realms (Deadlands, Sovngarde, Shivering Isles, Apocrypha, Soul Cairn, Coldharbour) and the seas are outside the
province template; they are touched only by final polish (R15) and by the travel systems.

### 3.2 The province documents: `docs/world/provinces/<province>.md`

One document per province, named by the province id of `regions.json` (`skyrim.md`, `high_rock.md`,
`hammerfell.md`, `cyrodiil.md`, `morrowind.md`, `black_marsh.md`, `elsweyr.md`, `valenwood.md`, `summerset.md`,
`foreign_lands.md`). If a document on disk uses a different name, the name on disk wins; fix this list and the prompt
that reads it in the same commit. High Rock and Hammerfell also share `docs/world/ILIAC-BAY.md` for the bay.

Every province document has this fixed section structure. Phase prompts address sections by number, so do not renumber:

| § | Section | Feeds roadmap step |
| --- | --- | --- |
| 1 | **World model sheet** (the 11 layers for the province) **+ region sub-sheets** (11 layers and a world truth paragraph per region) | V1 (sheet JSON), and every other step through `why` cites |
| 2 | **Micro-biomes** (the mosaic per region, each with its L1-L3 cause) | V3 |
| 3 | **Map changes and geography** (coast, ranges, rivers, lakes, relief targets, anchor moves under the precedence rule) | V2 |
| 4 | **Roads, travel network, fast travel** (named roads, cross-province passes, the province's travel modes, wilderness nodes) | V5 |
| 5 | **Settlements** (tier list, city plan briefs, the bustling centres and why, poor and failing places) | V4, V6 |
| 6 | **Dungeons, underground, landmarks** (marquee and identity, signature inhabitants, unmarked sites, XL weenies) | V4, V7 |
| 7 | **Quests** (province story, region stories and hold conflicts, settlement quests, factions, radiant) | V8 |
| 8 | **Components layout** (scenes, encounters, collectibles, services) | V6, V8 |
| 9 | **Modernisation: keep / amplify / replace** (one row per content type of DESIGN-PRINCIPLES 1.2) | V9 report |
| 10 | **Floors and gates** (measured Skyrim floors per region, scaled by area) **and implementation phases** | every gate |

The province document is the design. The sheet JSON (`mod/src/main/resources/skycraft/tamriel/world/<province>.json`,
MS22) is the data the generators read. Step V1 turns section 1 into that JSON; when the two disagree, fix the document
first, then regenerate.

### 3.3 The province steps (V1-V9)

Every province phase runs the same nine steps (full definitions, owned files and gates: `docs/prompts/_province-steps.md`).
The Iliac Bay's IB1-IB8 map onto them.

| Step | What | Province doc § | Iliac Bay | Runs |
| --- | --- | --- | --- | --- |
| V1 Sheet | Section 1 to `tamriel/world/<province>.json`; `worldsheet_lint.py` 0 | 1 | IB-S | first |
| V2 Geography | Vectors, scale and anchor moves, relief to target, hydrology, rasters | 3 | IB1 + IB6 | **globally serial** (shared vectors and rasters) |
| V3 Regions and micro-biomes | Region polygons, palettes, new biomes, MS21 tables, L2 weather, music cue | 2 | IB2 | **globally serial** (`biomes.py`, `palettes.json`) |
| V4 Places | Curated place list per tier and class at the 4.3 densities, `hidden`, `class`, `why` | 5, 6 | IB3 | after V2 |
| V5 Roads and travel | Named roads, passes, travel network content, nodes; rebake road and scene plans | 4 | IB5 | **globally serial** (bake) |
| V6 Settlements | City plans, sub-kits, populations at the tier floors, trainers, courts, industries | 5, 8 | IB4 | parallel with V7, V8 |
| V7 Dungeons and landmarks | Identity, classes, marquee plans, rosters, looks, XL landmarks, power sites | 6 | IB7 | parallel with V6, V8 |
| V8 Quests and world texture | Province story, hold conflicts, factions, radiant, boards, encounters, wonder, voice, reactive lines, readables | 7, 8 | IB8 | parallel with V6, V7 |
| V9 Province gate | DESIGN-PRINCIPLES 5.5 for every region; modernisation table; four questions; Haiku blind test; owner checklist | 9, 10 | IB-G | last |

## 4. Order of provinces (and why)

1. **Skyrim uplift (R4).** Skyrim is the measure, yet our own Skyrim scores 53 on Settlements against the real game. It
   is where the player starts and where every new system (sheets, micro-biomes, trainers, boards, encounters) is proven
   before it is rolled out. Nothing else is "Skyrim quality" until Skyrim is.
2. **High Rock and Hammerfell, the Iliac Bay first (R5).** Fully planned (`ILIAC-BAY.md`, extraction done), among the
   weakest provinces (42, 43), and the most visible legacy-modernisation showcase. The bay window is built first
   (IB1-IB8), then the rest of both provinces (IB9).
3. **Cyrodiil (R6).** The largest province (61 km²), the heart of the empire, Oblivion data in hand; the top map change
   on the list (uniform scale) is here.
4. **Morrowind (R7).** The best non-Skyrim province already (53) with the richest canonical data (roads, signposts,
   the whole travel web); the alien-uniqueness showcase.
5. **Black Marsh (R8).** The weakest province (40) and the most different landscape (flat, wet, water travel).
6. **Elsweyr (R9), Valenwood (R10), Summerset (R11).** Arena-only provinces; relief is the main map job.
7. **Foreign lands (R12).** Half-density expeditions.

Geography steps (V1-V3) of the next province may run while the current province runs V6-V8, inside the 3-agent cap
and the global serial rule for V2, V3 and V5.

## 5. The execution roadmap

Sizes: **S** about one agent-day, **M** a few agent-days, **L** a week of agent work, **XL** several weeks (split into
steps). "Owns" is the write set; anything else is touched only minimally and listed in the report. Every phase ends
with the merge procedure (`docs/prompts/_COMMON.md`: `bash tools/dev/merge_worktree.sh`, `tools\qa\qa.ps1 -Server
-Rebake`, commit, update section 7).

```
P5 merged
 └─ R0 release build + playtest
     ├─ R1 loose ends (R1a dungeons/creatures ∥ R1b roads/sea/survival)
     ├─ R2 testing tiers 0/1
     ├─ R3a world sheets ─┬─ R3b micro-biomes
     │                    └─ R3c measurement
     └─ R3d town services ∥ R3e world systems
R3a + R3b + R3c + R3d + R3e ──> R4 Skyrim uplift (S1-S5)
R1 ──> R5 IB1-IB3 (geography may run early)   R3a ──> IB-S   R4 ──> R5 IB4-IB8, IB9, IB-G
R5 ──> R6 Cyrodiil ──> R7 Morrowind ──> R8 Black Marsh ──> R9 Elsweyr ──> R10 Valenwood ──> R11 Summerset ──> R12 Foreign
Free-slot work: R13 P7 endless lands (after R2), R14 ITEMS AFFIX (after R3a).   Optional: R-OPT (after R4, owner's call).
Last: R15 final polish and release.
```

### R0 Post-P5 release build and playtest

* **Goal.** Land P5 (streets to every door), then cut a release build the owner can play, and turn the owner's notes
  into the R1 list. The first build in which roads, crossings, vegetation masks, streets and scenes all come from the
  plan.
* **Owns.** `docs/FIRST-PLAYTEST.md` (rewrite for this build), new `docs/PLAYTEST-4.md` (the owner's notes, triaged),
  `dist/` (built, not committed).
* **Depends on.** P5 merged with its gate passed (another agent's worktree).
* **Gate.** `tools\build_release.ps1` PASS (explicit bake, then `qa.ps1 -Server -Bake`); `full_corridors.ps1` 0 gaps,
  0 drops > 1 without a step, 0 unbridged, 0 tree-blocked; P5 gate unreachable doors <= 1% on the Skyrim sweep sample
  and every GATE node joins a road vertex; `dist/Skycraft-client-instance.zip` built from the commit; the owner's
  checklist written. The owner plays (no agent launches the client).
* **Size.** S.

### R1 Loose ends

* **Goal.** Close every known defect left by the earlier phases before new systems pile on top. Two agents in parallel,
  disjoint files:
  * **R1a dungeons and creatures:** analyzer blockers `frossel` (2 rooms stuck in terrain), `shadowgreen_cavern` (80
    spots with no way back out), `brucas_leap_redoubt` (boss room unreachable), plus whatever the 3-seed run shows;
    `deepwood_redoubt` builds draugr but is a Forsworn stronghold in lore (Forsworn roster, word wall kept);
    `tower_of_mzark` has no structure and never starts; the shipwreck and cabin identity gaps (no dry post for the
    sign, no motif on rough ground); the invisible spider audit (every registered entity renders, PLAYTEST-1's
    invisible-enemy class); lecterns showing `skycraft:book` items.
  * **R1b roads, sea and survival:** the P1 residue (1,080 road vertices and 11 highway vertices > 8 blocks off the
    ground on mountain roads, 6 failed trail and lane links, 33 junction pins missed); unmet P3 `road_measure` failures
    that remain after P5 (settlement-zone gaps and drops); Solitude's sea routes to Dawnstar, Windhelm and Raven Rock
    falling back to straight lines; the Riverwood unserved gate; the `skycraft:bedroll` block-entity warning at
    worldgen; anything from the R0 playtest in these areas.
* **Owns.** R1a: `dungeons/**`, `creatures/**` renderer registration, `tools/qa/dungeon_*`, the affected `places`
  entries' variant fields, `tools/audit_entity_renderers.py`. R1b: `worldplan/**`, `roads/**`, `sailing/**` route code,
  `resources/skycraft/tamriel/ports.json`, `survival/**` (bedroll), road QA tools.
* **Depends on.** R0 (build in the owner's hands; playtest items appended).
* **Gate.** `skycraft-dungeon-qa place` on one dungeon per variant (45) on 3 fixed seeds (Nirn, 7777, 2428247): 0
  blockers; `dungeon_identity_lint.py` total flags <= 10 and 0 for shipwrecks and cabins; `deepwood_redoubt` roster
  Forsworn with its word wall; `tower_of_mzark` generates and `/skycraft-dungeon-qa view tower_of_mzark` enters it;
  `audit_entity_renderers.py` 0 missing; `roadplan_lint.py` 0 errors, 0 failed links, 0 highway and <= 0.1% road
  vertices > 8 blocks off the ground, 0 junction pins > 0.6; `full_corridors.ps1` all zeros; 0 straight-line sea-route
  fallbacks (`/skycraft-sail routes` or the route log); `log_summary.py` 0 bedroll block-entity warnings; every R0
  playtest item fixed or moved to section 8 with a reason.
* **Size.** M (two agents, 1-2 days each).

### R2 Testing tiers 0 and 1

* **Goal.** Make the fast loop faster and safer before the big content phases. **Tier 0:** JUnit 5 plan-layer tests
  that run without booting Minecraft (`gradlew test` under 90 s): `RoadPlan` (format round-trip, `at()` contract,
  grade caps, spans tile segments), `ScenePlan` and `StreetPlan` (determinism, byte-identical under two seeds), `Places`
  and `Regions` lookups, `TamrielMap.sample` determinism, the bake input hash, and (once R3a lands) the world-sheet
  loader. **Tier 1:** a hot-swap dev server: method-body changes are pushed into the running dev server over JDWP
  (`devserver.ps1 hotswap`, about 5-10 s instead of a 40 s restart), data files reload with `/skycraft-dev reload
  <kind>`, and anything structural still restarts.
* **Owns.** `mod/src/test/**`, `mod/build.gradle` (test dependencies and the test task only), `tools/dev/devserver.ps1`,
  `tools/qa/qa.ps1` (a `-Unit` switch), `dev/**` (reload command), `docs/DEVELOPING.md` (testing section).
* **Depends on.** R0 (stable base). Parallel with R1.
* **Gate.** `gradlew test` >= 40 tests, all pass, < 90 s wall; `qa.ps1 -Unit` wired; a deliberate break of `Profile`
  fails a test; `devserver.ps1 hotswap` applies a method-body change without a restart in <= 15 s (measured), and
  falls back to restart on a schema change; `/skycraft-dev reload scenes|places|cityplans` works; FINISH-PLAN section 2
  and AGENT-RULES fast-loop text updated.
* **Size.** M. (Tiers 2 and 3, the headless census and snapshot golden images of FINISH-PLAN T3 and T4, are open items
  O3 and O4 in section 8; they become part of R3c if budget allows.)

### R3 The system builds the plan needs

Five parts, at most three at once. R3a goes first; the others may start alongside it.

#### R3a World-model sheets (MS22)

* **Goal.** The sheet becomes data the generators read. JSON schema (`{province, truth, layers: {L1..L11: [{id, text,
  cites}]}, regions: {<id>: {...}}, forbidden: [...]}`), a Java loader (`WorldSheets.get(regionId).field("L5.silver_mining")`),
  `why` fields on every content record type (places, quests, scenes kinds, dungeon identity, lore books, region spawns,
  palettes and micro-biome rules), `tools/world/sheet_from_md.py` (province doc section 1 to JSON), and
  `tools/qa/worldsheet_lint.py` (layers present, every L2-L11 field cites above, cites resolve, every `why` resolves,
  forbidden pairs absent; a back-fill counter for legacy content). Pilot: `skyrim.json` from `provinces/skyrim.md`.
  First consumers: the population grammar takes jobs from L5 and beliefs from L8, dungeon identity takes inhabitants
  from L10.
* **Owns.** `resources/skycraft/tamriel/world/**`, `com.skycraft.tamriel.world` (new), `tools/world/**`,
  `tools/qa/worldsheet_lint.py`, `qa.ps1` (wire the lint), the `why` field readers (additive).
* **Depends on.** R0; `docs/world/provinces/skyrim.md` written.
* **Gate.** `worldsheet_lint.py` 0 errors on `skyrim.json` (9 holds + province, 11 layers each); a Tier 0 test of the
  loader; >= 2 generators read the sheet (population jobs, dungeon inhabitants) with `why` on their output; F-WHY
  reported by the lint; legacy back-fill counter printed.
* **Size.** M.

#### R3b Micro-biomes (MS21)

* **Goal.** Two new baked rasters at 32-block cells (rain shadow from the ridge field and L2 prevailing wind; heat for
  springs and volcanic ground), slope and aspect from the macro height, a per-region `micro` rule table in
  `palettes.json` written by `biomes.py`, selection inside each `TamrielBiomeSource` context, effects on flora, weather
  odds, ambience and spawn keys. Pilot: Skyrim's nine holds (Eastmarch hot springs, Reach gorge meadows, Rift birch
  glades, Haafingar sea cliffs, Hjaalmarch salt marsh...).
* **Owns.** `tamriel/TamrielBiomeSource.java`, `tamriel/biome/**`, `tools/worldgen/{biomes,ecotones,tamriel_map}.py`
  (micro parts), `resources/skycraft/tamriel/palettes.json`, new rasters, `tools/qa/region_audit.py` (MS21 checks).
* **Depends on.** R3a schema (micro rules cite L1-L3 ids).
* **Gate.** F-MICRO in Skyrim: >= 6 micro-biomes per hold, >= 2 per km², >= 3 transitions per km of road; no rule on
  < 0.5% or > 60% of a region's land; every rule cites a sheet field; chunk generation time within +5% (`/skycraft-dev
  regen` timing); bake byte-identical across seeds.
* **Size.** M-L.

#### R3c Measurement: flow audit and floors per region

* **Goal.** Make every gate in this plan computable in one command. `tools/qa/flow_audit.py` (the FLW metrics of
  DESIGN-PRINCIPLES 5.4, offline from baked data); `parity.py --province <id> --region <id>` filters and a
  `--tiers` per-settlement report against the 4.4 tier table; MS7 dungeon `class` tag plus `dungeon_stats.py` length
  bands; `hidden: true` (unmarked) counted; MS1 story schema and `story_lint.py`; MS20 `verbatim_check.py`
  (owner's machine only, corpus in `.reference/`); `qa.ps1 -Flow` switch; a `floors.json` with the 4.3-4.8 medians and
  absolute floors so every tool reads one copy.
* **Owns.** `tools/qa/{flow_audit,story_lint,verbatim_check,dungeon_stats}.py`, `tools/qa/parity.py` (filters and tier
  report only), `tools/qa/floors.json` (new), `qa.ps1` switch, `quests/story/` schema doc.
* **Depends on.** R3a (F-WHY reads the lint output). Parallel with R3b.
* **Gate.** `flow_audit.py` prints every FLW metric for all 71 regions in < 60 s; `parity.py --region whiterun
  --tiers` prints the 4.4 table for Whiterun's settlements; `verbatim_check.py` exits 0 on current text (or lists real
  hits to fix); `story_lint.py` validates a sample story file; numbers recorded as the Skyrim baseline in section 7.
* **Size.** M.

#### R3d Town services: trainers, boards, signposting, courts, reactivity

* **Goal.** The engine roles the tier table needs. **Trainer role** (skill, cap, fee, five sessions per level,
  placed by tier: capital 5, city 2, town 0-1; data-driven from the population grammar and the sheet's L5/L7);
  **MS12 notice boards** (town and larger, 3-6 live jobs aimed at real uncleared dungeons 300-1,500 blocks away, party
  shared, two-day refresh); **MS13 signposting** (compass hints within 150 blocks, quest-giver markers, journal map
  links, night beacons, signs naming the next settlement, inn and dungeon); **F-COURT** (ruler, steward, court mage,
  housecarl roles in every capital and city); **F-REACT** deed-keyed reactive lines (guards, innkeepers, courts) as
  data keys; **MS19** region arrival moment (banner, sting, narrator line, courier to the hold-conflict hook).
* **Owns.** `society/**` (roles, placement), `quest/**` (boards, journal links), `guide/**` or a new `signpost`
  package, `dialogue/**` (reactive keys), `lang/skycraft/{society,quests}.json` (own keys), `population_lint.py`.
* **Depends on.** R0. R3a for the `why` cites of placed trainers (soft).
* **Gate.** `population_lint.py` reports trainers and court roles per tier, 0 errors; in Skyrim every capital has >= 5
  trainers in distinct skills and a court of >= 3, every city >= 2 trainers; boards in 100% of towns and larger with
  >= 3 live jobs at real places (`/skycraft-board list <town>`); F-SIGN >= 95% on Skyrim; F-REACT keys loadable (lines
  come in R4); `qa.ps1 -Server` selftests pass including a new `board` and `trainer` selftest.
* **Size.** L.

#### R3e World systems: travel networks, encounters, festivals, levels

* **Goal.** The world-level frameworks every province fills. **MS11** generic travel networks (`travel/<province>.json`:
  modes carriage, strider, boat, river barge, caravan, guild guide, lift, wayshrine; routes restricted to real links;
  wilderness nodes from shrines and stones plus a hashed fill for the 800-block rule); **MS14** encounter library
  (`scenes.json` from 6 to >= 40 kinds, region keyed, `wonder` tag, one roll per player every 15-25 minutes,
  daylight-biased); **MS16** festival calendar (`calendar/festivals.json`, decoration overlay, stalls, festival quests);
  **MS17** level policy (story quests clamped to region band +/- 8).
* **Owns.** `roads/Carriages`, `creatures/travel/**`, `sailing/**` (route offers), new `travel` and `calendar`
  packages, `resources/skycraft/creatures/scenes.json`, `society/Encounters`, `creatures/spawn/WildScenes`,
  `creatures/Leveling` (story clamp only).
* **Depends on.** R0. Parallel with R3d (disjoint files).
* **Gate.** F-TRAVEL in Skyrim p90 <= 500 and max <= 800 blocks; Skyrim >= 2 travel modes; F-ENC >= 8 kinds per hold
  (>= 40 total), F-WONDER >= 3 per hold; ecology road300 still <= 5% day and <= 20% night (`ecology_report.py`); one
  festival per Skyrim hold on the calendar; F-LEVEL band <= 20 and story quests within +/- 8.
* **Size.** L.

### R4 Skyrim uplift to the measured floors

* **Goal.** Our Skyrim meets the floor it was measured from: DESIGN-PRINCIPLES 5.5 for all nine holds, Settlements
  from 53 to >= 95. Steps (prompt `R4-skyrim-uplift.md`): **S1** sheet and micro-biome data for every hold (V1, V3);
  **S2** settlements to the 4.4 tier counts (named NPCs, interiors, named buildings, roles, trainers, courts; the
  authored attention list of `SKYRIM-SWEEP.md` section 4: Solitude's Blue Palace, Windhelm's Grey Quarter, Helgen's
  story, the College); **S3** dungeons and secrets (unmarked 0.57/km², power sites, signature themes per hold, F-LOOP,
  missing places Amol and ruined Snowhawk); **S4** quests and reactivity (a hold conflict chain of 3+ per hold, 12
  deed-keyed lines per hold, F-STARTS >= 4, board offers); **S5** flow (F-VISTA, F-WEENIE, F-DETOUR, F-ENC, F-WONDER),
  then the gate.
* **Owns.** Skyrim's data: `tamriel/world/skyrim.json`, `places/skyrim*.json`, `cityplans/<skyrim ids>`,
  `quests/*skyrim*`, `lore/places/*skyrim*`, `region_spawns/skyrim.json`, Skyrim entries of `scenes.json`,
  `travel/skyrim.json`, `palettes.json` Skyrim micro rules.
* **Depends on.** R3a, R3b, R3c, R3d; R3e for S5.
* **Gate.** For each of the 9 holds: `worldsheet_lint` 0; 4.3 densities at or above `max(abs, median x km²)`;
  4.4 tier table met for all 24 swept settlements (`parity.py --tiers`); `parity.py --province skyrim
  --assert-floor SET,NPC,QST,DUN,CRE,FLO,STY,LND,LOR,MUS,ITM` with SET >= 95; FLW floors (`flow_audit.py`);
  `novelty_lint --strict` 0; `verbatim_check` 0; `reach_lint` 0 isolated; G1 blind test (Haiku names the hold from 3
  renders) >= 80%.
* **Size.** XL (about 3 weeks of agent work, 3 agents rolling by step).

### R4a Town overhaul: planned cities, recognisable shops, working people, real interiors

* **Goal.** Execute `docs/prompts/R4a-town-overhaul.md` (steps `TO0`–`TO6`) as the structural settlement foundation of R4
  and R3d: **TO0** full settlement audit (`docs/world/TOWN-AUDIT.md`); **TO1** district/avenue/stair planner with 100%
  gate-to-door reachability and stairs between terraces; **TO2** recognisable trade shops and multi-room inns with exterior
  signs, counters, and working trade stations; **TO3** grand leader/jarl halls and multi-room house interior families by
  wealth class; **TO4** verified daily work routines anchored to shop counters, forges, thrones, desks, and beds; **TO5**
  street scatter and ground cleanup; **TO6** rollout across Skyrim and the provinces.
* **Owns.** `settlements/**`, `worldplan/**` (street/lot planner), `society/**` (work anchors & schedules),
  `resources/skycraft/tamriel/cityplans/**`, `resources/skycraft/tamriel/styles/**`, `tools/qa/*town*`.
* **Depends on.** R0; integrates with R3d and R4 S2.
* **Gate.** 100% key-building reachability from every gate; stairs on all tier transitions; 0 street-blocking scatter or
  pits; shop/inn/jarl-hall interior size floors met; schedule audit pass across a 24h cycle (`qa.ps1 -Server -Rebake`).
* **Size.** L-XL (steps `TO0`–`TO6`).

### R5 High Rock and Hammerfell: the Iliac Bay (IB1-IB8), then the rest (IB9)

* **Goal.** `docs/world/ILIAC-BAY.md` executed, then both provinces brought to the floor outside the window.
  Order (ILIAC-BAY 11, plus the sheet and the gate): **IB1** frame and vectors (V2) →
  **IB2** realms, fiefs, biomes (V3) and **IB3** places (V4) in parallel → **IB-S** realm sheets (V1, once R3a
  is merged) → **IB4** city plans (V6), **IB5** roads and
  ferries (V5), **IB6** terrain relief (V2) in parallel → **IB7** dungeons (V7) → **IB8** stories and factions (V8) →
  **IB9** the rest of High Rock and Hammerfell (Glenumbra north, Rivenspire, Bjoulsae, northern Wrothgar with
  Orsinium, Craglorn, Hews Bane, Stros M'Kai to Redguard's layout, Gilane to the Abecean shore; all V1-V8 for those
  regions) → **IB-G** province gate (V9).
* **Owns.** As ILIAC-BAY section 11 per step; IB9 the High Rock and Hammerfell data outside the window.
* **Depends on.** R1 for IB1-IB3 (may run alongside R3/R4 within the agent cap); R3a-R3e and R4's proven pipeline for
  IB4, IB7, IB8.
* **Gate.** Each IB step's gate in ILIAC-BAY section 11 (coast agreement >= 0.90, fiefs within 10% of area, 4.4/4.5
  bands per realm, unique `sil`, `reach_lint` 100%, >= 6 named rivers, relief p90 >= Skyrim's median hold, Spearman
  >= 0.6, `d_identity` 1.0, 9 marquee plans, F-HOOK); then DESIGN-PRINCIPLES 5.5 for every region of both provinces;
  owner decision 1 (keep-village density) judged in a playtest.
* **Size.** XL (IB steps S-M each; IB9 L).

### R6 Cyrodiil

* **Goal.** Province steps V1-V9 from `provinces/cyrodiil.md`. Specifics: the uniform-scale switch (SCALE 8.1: least
  squares similarity 1.754 blocks/m, -3.24 degrees; move the 9 anchors 324-947 blocks; Lake Rumare and the Imperial
  isle to about 1270, 5540; Bravil's Niben course), Topal Bay (8.2), relief 34→40 / 114→150 / ruggedness 2.2→4
  (Jerall wall, Colovian plateau valleys, Niben braids, Gold Coast cliffs), named roads Chorrol-Skingrad and
  Bruma-Cheydinhal, carriages at all 8 cities plus the IC waterfront, Niben river boats, wayshrines as free nodes,
  Ayleid ruins as the elder-race class, the Gold Road inns rule (a stop every 600-1,000 blocks).
* **Owns / depends / gate / size.** Per `_province-steps.md`; depends on R4 (pipeline proven) and R5's V2/V3/V5 slots
  being free; gate = V9; XL.

### R7 Morrowind (Vvardenfell, the mainland, Solstheim)

* **Goal.** V1-V9 from `provinces/morrowind.md`. Specifics: the Vvardenfell coast at 0.95 blocks/m (SCALE 8.3:
  south coast 250 further south, West Gash towns 210-580 east, Sheogorad and Azura's Coast islands, the `sea_of_ghosts`
  label); missing places (Holamayan, Ministry of Truth, Tel Fyr, Mawia, Castle Ebonheart; Solstheim's Thirsk, Castle
  Karstaag, Lake Fjalding, Mortrag Glacier, Hrothmund's Bane, Thormoor's Watch, Aidanat); travel networks (strider
  offers restricted to the 16 game links, 25 boat links, Mages Guild guides, the Propylon ring of 10, Almsivi and
  Divine intervention, Mark and Recall); named roads from the signpost topology plus the mainland Arena backbone;
  relief 21→30 / 150→170 / 4→5; an exclusive Great House choice (P-J1).
* **Gate / size.** V9; XL.

### R8 Black Marsh

* **Goal.** V1-V9 from `provinces/black_marsh.md`. Specifics: the coast (SCALE 8.9: east coast bulge near Thorn and
  Archon, Lilmoth's delta, a bay at 10500, 6300-6900, each checked against the lore map); relief 1→3 / 2→10 / 0.4→1
  (channels, Hist rises, xanmeer, north-west uplands); causeway roads and swamp boats as the main travel; Hist politics
  and drowned ruins as the underground.
* **Gate / size.** V9; L.

### R9 Elsweyr, R10 Valenwood, R11 Summerset

* **Goal.** V1-V9 from each province document. Elsweyr: relief 19→30 / 64→130 / 1.5→3.5 (Anequina mesas and canyons,
  the central escarpment), caravans, the Riverhold-Dune-Torval-Senchal spine, moon-sugar economy. Valenwood: relief
  18→22 / 35→60 / 1.2→2.5 with height from giant trees, canopy paths and river fords instead of paved roads, river
  boats and lifts, the Green Pact. Summerset: relief 23→40 / 40→100 / 1.9→3.5 (white cliffs, central upland, Auridon
  ridge), the inter-island ferry, castes and ports.
* **Gate / size.** V9 each; L, L, M.

### R12 Foreign lands

* **Goal.** Atmora, Akavir, Yokuda, Pyandonea and Thras at the half-density template (DESIGN-PRINCIPLES 4.1):
  expeditions with a reason to go, ports and sea routes, one marquee per land, sheets like any province.
* **Gate / size.** V9 at half density; M.

### R13 P7 endless lands

* **Goal.** WORLDGEN-PIPELINE "Later P7": beyond the authored map, the same plan-first pipeline per 4 km tile, planned
  lazily on a worker thread, cached in level data, with a procedural sheet per tile (a generated L1-L11 so the
  coherence rule holds even in the endless lands).
* **Owns.** `worldplan/**` (tile planner), a new `worldplan/tile` package, `tamriel/TamrielMap` (beyond-map sampling
  only), level data.
* **Depends on.** R2 (Tier 0 tests for the tile planner); P1-P6 stable. Free-slot work; may run any time after R2.
* **Gate.** Tiles plan in < 2 s each off-thread, 0 main-thread stalls > 50 ms (`/skycraft-perf`); the corridor
  measure on 2 generated tiles all zeros; seams with the authored map 0 gaps; Tier 0 determinism test per tile.
* **Size.** L.

### R14 ITEMS AFFIX

* **Goal.** FINISH-PLAN section 5's ITEMS AFFIX: found-item condition and quality, cultural makers by region of the
  roll, Skyrim-style enchantment names by tier, storied items in ~5% of boss chests, hooks in containers, corpses and
  merchants, `tools/loot_sim.py` bands, `/skycraft-items roll`. Makers and storied provenance cite the sheet (L5, L11).
* **Owns.** `loot/**` (affix code), `arsenal/**` (tooltip and stat hooks), `lang/skycraft/items*.json` (own keys),
  `tools/loot_sim.py`.
* **Depends on.** R3a (sheet cites). Free-slot work.
* **Gate.** `loot_sim.py` value curves inside `docs/LOOT.md` bands; `audit_items.py` 0 errors; tempering and
  enchanting keep affixes (a selftest); `novelty_lint --strict` 0 on item names.
* **Size.** M.

### R15 Final polish and release

* **Goal.** FINISH-PLAN T9 updated: README, FEATURES, INSTALL and WORLD counts refreshed; the realms touched up to
  the template where they fall short; performance pass on the full world; `tools/build_release.ps1`; the owner's Prism
  run with a checklist (no agent launches the client); a tagged release commit (local; never pushed by an agent).
* **Gate.** `qa.ps1 -Server -Bake -Layout` PASS; `parity.py --assert-floor` all categories for every region; FLW
  floors for every region; Haiku review of the per-province contact sheets with no blocking issue; the owner signs off.
* **Size.** M.

### R-OPT The optional ESO-style layer (owner's call)

Star-shards, world events, world bosses, open dungeons, set stations, surveys, lorebook rewards, the Region Guide and
micro-objectives (DESIGN-PRINCIPLES 4.12; MS2-MS6, MS8-MS10, MS15, MS18). Only on regions that already meet the floor,
only through their sheets. No prompt is written until the owner opts in.

## 6. Parallelism and ownership rules

* At most **3 agents at once** (owner's budget rule); prefer 1-2.
* **Globally serial:** V2 (vectors and rasters), V3 (`biomes.py`, `palettes.json`), V5 (bake of road and scene
  plans) of any province, R3b and R13. Two of these never run at the same time.
* Index files (`places/index.json`, `quests/index.json`, `lore/places/index.json`, tag lists) are union-merged, never
  rewritten. Generated files are regenerated, never hand-merged (FINISH-PLAN section 0).
* A step that needs a file outside its write set makes the smallest additive change and lists it in its report.

## 7. State table (update on every merge)

Status values: `done`, `in progress`, `next`, `blocked (why)`, `not started`. "Gate" holds the key numbers.

| Phase | Status | Where (worktree / commit) | Gate result | Updated |
| --- | --- | --- | --- | --- |
| P1 road plan | done | 464c430 | roadplan_lint 0; 6 failed trail links (R1b) | 2026-10-13 |
| P2 terrain obeys plan | done | 7b0816c | 95.5% bed columns exact | 2026-10-13 |
| P3 paint and crossings | done | 5d0ffe7 | p3_corridors all zeros; legacy measure 11 gaps in settlement zones (P5/R1b) | 2026-10-13 |
| P4 vegetation masks | done | 233629b | | 2026-10-13 |
| P5 streets to every door | done | e9a5ebe | unreachable doors 0.38% (gate <= 1%) | 2026-10-13 |
| P6 planned scenes | done | f8e3a68 | 3,962 scenes; placement_lint 0 | 2026-10-13 |
| Fire system and destroyed-Helgen aftermath | done | 4ba41bb | big-fire blocks, far fire renderer, `FireSites` API, aftermath scene, mournful music | 2026-10-13 |
| Playtest round 4 fixes | done | 15e46d7 | settlement crowd cap, night sleepers, gate posts, respawn at nearest town, key remap, longbow pose | 2026-10-13 |
| Playtest round 5 fixes | done | b738492 | block-model rotations, strict asset audit, intro cover, Helgen polish; rebake | 2026-10-13 |
| Landmark grounding and story lecterns | done | 7af4469 | whole-footprint sampling, foundations, support validator, lectern reader UI; rebake | 2026-10-13 |
| Playtest round 6 & multiplayer overhaul (14 items + 2 refinements) | done | 2bcf594b | 2-block direct-aim companion healing, Skyrim-style free jail visits & 1-day escapee tracking, jail/party fast-travel, Sanies Lupinus & level-gated werewolves, crouch trap disarm & filled dispensers, vanilla spawner strip, NPC busy cleanup, L/R hand dual-wield/spell/bound/favorite equip, Skyrim XP curve & party XP/hud bar, Helgen aftermath & contextual dialogue, weapon poisons & HUD meter, party inn booking & bed ownership, book-to-inventory state return & readable Journey book, 5-min per-item evidence decay, 48h merchant rotation & buyback decay; `qa.ps1 -Server` OVERALL PASS | 2026-10-15 |
| Design docs (principles, scale, Iliac Bay) | done | cf288dd | | 2026-10-13 |
| Province documents `docs/world/provinces/*.md` (all 11 files, §1-§10) | done | `skyrim.md`, `high_rock.md`, `hammerfell.md`, `cyrodiil.md`, `morrowind.md`, `solstheim.md`, `black_marsh.md`, `elsweyr.md`, `valenwood.md`, `summerset.md`, `foreign_lands.md` | All 11 province/island/expedition design docs complete with §§1-10 | 2026-10-14 |
| World plan, prompt library, START-HERE | done | updated with R4a & all province docs | | 2026-10-14 |
| IB0 Iliac extraction | done | `.reference/daggerfall_ref/v2` | | 2026-10-12 |
| **R0** release build and playtest | **done** (release build deployed to Prism & `dist/Skycraft-client-instance.zip`; owner playtest items resolved) | 2bcf594b | `qa.ps1` OVERALL PASS | 2026-10-15 |
| **R1** loose ends (R1a, R1b) | **done** | `tamriel-overhaul` | `tower_of_mzark` structure & Great Lift/mage bosses, `deepwood_redoubt` Forsworn roster (`dungeon_identity_lint` 293/293, 0 flags), shipwreck/cabin/lair dry sign & motif pads, `skycraft:book` lecterns, `frossel`/`shadowgreen_cavern`/`brucas_leap_redoubt` depth & headroom & bridge fixes, Solitude 2x2 sub-cell sea routing (0 straight-line warnings), landmass-aware site trail snapping, `BedrollBlock.newBlockEntity` null; `qa.ps1 -Server -Rebake` OVERALL PASS | 2026-10-15 |
| **R2** testing tiers 0/1 | **done** | `tamriel-overhaul` | 60 JUnit 5 Tier 0 tests across 7 test classes (`qa.ps1 -Unit` passes in 17.4s), JDWP hot-swap (`tools/dev/hotswap.py` + `devserver.ps1 hotswap`), `/skycraft-dev reload <scenes\|places\|cityplans\|quests\|palettes\|all>` | 2026-10-15 |
| **R3a** world sheets | **done** | `tamriel-overhaul` | `tools/world/SCHEMA.md`, `sheet_from_md.py`, `worldsheet_lint.py` (10 blocks x 11 layers, 449 fields, 0 errors, F-WHY 662/1815 = 36.5%, 2 consumers: `SettlementPopulation`/`Housing` 100% + `dungeons/identity/skyrim.json` 100%), `WorldSheets.java` + `/skycraft-world sheet` | 2026-10-15 |
| **R3b** micro-biomes | **done** | `tamriel-overhaul` | `rainshadow.png` & `heat.png` rasters, `MapSample` aspect/rainshadow/heat drivers, 76 micro-biome rules across 9 Skyrim holds in `palettes.json` (`TamrielBiomeSource.microAt`), `region_spawns/skyrim.json` (100% `why`), `region_audit.py --province skyrim` 0 errors (19.16 transitions/km, 9/9 `F-MICRO` PASS) | 2026-10-15 |
| **R3c** measurement | **done** | `tamriel-overhaul` | `floors.json`, `flow_audit.py` (all 27 FLW metrics across 71 regions in 6.4s), `story_lint.py` (0 errors on `whiterun.json`), `verbatim_check.py` (0 matches across 101,401 strings), `dungeon_stats.py` (2,947/2,947 class-tagged dungeons), `parity.py --province/--region/--tiers`, `qa.ps1 -Flow` | 2026-10-15 |
| **R3d** town services | **done** | `tamriel-overhaul` | `NpcRole.TRAINER` + `SocietyTrainers` + `trainers.json` (5/capital, 2+/city, 5-per-level & cap selftests), `Courts` + `courts.json` (9/9 `F-COURT` PASS), 120 reactive dialogue lines (`F-REACT` 9/9 PASS), `population_lint.py` 0 errors, `NoticeBoards` (3-6 live jobs, 48h refresh, `F-BOARD` 9/9 PASS), `Signposts` (`F-SIGN` 9/9 PASS), MS19 region arrival moment | 2026-10-15 |
| **R3e** world systems | **done** | `tamriel-overhaul` | `TravelNetworks` + `travel/skyrim.json` (116 nodes, 142 routes, 5 active modes, `p50=226.3m`, `p90=357.8m <= 500m`, `max=518.2m <= 800m`, 6/6 selftests, `F-TRAVEL` 9/9 PASS), 66 encounter kinds & 26 wonders in `scenes.json` (`F-ENC` & `F-WONDER` 9/9 PASS, `ecology_report.py` 0 errors), `FestivalCalendar` + 17 festivals in `festivals.json`, `Leveling.storyQuestLevel` + clamped bands (`F-LEVEL` 9/9 PASS); `qa.ps1 -Server -Rebake` OVERALL PASS | 2026-10-15 |
| **R4** Skyrim uplift (S1-S5) | **in progress** (`skyrim.md` SK1-SK8; SK1 & much of SK7 done in R3a-R3e) | | Skyrim SET 54 -> target >= 95 | 2026-10-15 |
| **R4a** Town overhaul (TO0-TO6) | **done** | `4144f49a` | TO0 audit, TO1 stepped terrace/tier stairs & 100% leader seats & >=4 house footprints (>=7x9), TO2 recognisable trade shops with unsealed counters/chairs & multi-room inns (4-6 private rooms with desks), TO3 partitioned Jarl/leader halls & 3 house interior layout families, TO4 stationed counter/throne/desk routines, TO5 shrine candle pedestal fix & urban park gardens & 24-block carver foundations; rebaked, `qa.ps1 -Server -Rebake` OVERALL PASS | 2026-10-15 |
| R5 Iliac Bay (IB-S, IB1-IB9, IB-G) | not started (`high_rock.md`, `hammerfell.md`, `ILIAC-BAY.md` done) | | High Rock 42, Hammerfell 43 | |
| R6 Cyrodiil | not started (`cyrodiil.md` done) | | 48 | |
| R7 Morrowind | not started (`morrowind.md`, `solstheim.md` done) | | 53 | |
| R8 Black Marsh | not started (`black_marsh.md` done) | | 40 | |
| R9 Elsweyr | not started (`elsweyr.md` done) | | 44 | |
| R10 Valenwood | not started (`valenwood.md` done) | | 44 | |
| R11 Summerset | not started (`summerset.md` done) | | 45 | |
| R12 Foreign lands | not started (`foreign_lands.md` done) | | 31-32 | |
| R13 P7 endless lands | not started | | | |
| R14 ITEMS AFFIX | not started | worktree `agent-a2563a6b1f0ded80b` holds nothing usable | | |
| R15 final polish | not started | | | |

## 8. Open items (append on every merge; remove when closed)

| Id | Item | Owner phase |
| --- | --- | --- |
| O1 | Owner decision on `old_orsinium` (move to Daggerfall's Orsinium area if geography and lore fit) | IB3 |
| O2 | Owner decision 1 of ILIAC-BAY (keep-village density): judge in the first playtest after IB5 | IB-G |
| O3 | Headless world census (`/skycraft-qa census`, FINISH-PLAN T3) not built | R15 |
| O4 | Snapshot golden-image renders (`tools/qa/snapshots.json`, FINISH-PLAN T4) not built; `render_town.py` and `render_dungeon.py` exist | R15 |
| O7 | Minor sites and hidden stashes are on seeded cell grids, not in the plan (WORLDGEN-PIPELINE 9) | R4 S3 or R13 |
| O8 | PARITY PA3-PA22 backlog: fold each batch into the province step that owns its files (PA3 into V7/V8, PA4-PA9 into V6/V8, PA11-PA13 into V7, PA14-PA15 into V3/V7, PA16-PA17 into V6, PA18 into V7, PA19 into V3, PA20 into V3, PA21 into V8, PA22 into R3c/V8) | per province |
| O9 | Skyrim hold labels: 11 places carry a `region` that disagrees with the game's hold and the nearest seat; decide data relabel (recommended) or raster repaint (`skyrim.md` 3.2) | R4 SK2 |
| O10 | `parity.py` counts named NPCs only from quest-file `npcs[]`; named residents of plans, sanctuaries and camps need a reader before R4 S2 can measure (extends O5) | R4 S2 |

