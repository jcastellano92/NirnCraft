# Skycraft finish plan

This is the execution plan for wrapping up Skycraft on a tight token budget. It is written so a Sonnet (or Opus)
session can pick it up cold. Read it top to bottom once, then work the task list in order.

## 0. Ground rules for whoever executes this

* **Branch:** `tamriel-overhaul` (local only; never push unless the owner asks). One checkpoint commit per finished
  task, message style like the existing log, ending with the attribution line the harness provides.
* **Agent rules:** `docs/AGENT-RULES.md` (build commands, no mixins, no client classes in common code, worldgen
  safety, legal rule for game reference data, lang/texture conventions). Reference data from the owner's own game
  installs is in `.reference/` (gitignored): `skyrim_ref`, `oblivion_ref`, `morrowind_ref`, `daggerfall_ref`.
  Facts only (names, positions, geography); never ship game assets or text.
* **Budget discipline (owner's priority: spend tokens on development, verify efficiently, check fully at the end):**
  * At most **3 agents at once**; prefer 1–2. Give each a single, bounded task with explicit owned files.
  * **Never** launch the Minecraft client to test during development. Use `tools/qa/qa.ps1` (section 2).
  * Don't read whole logs, whole JSON files or many screenshots. Read the QA report, `git diff --stat`, and one
    contact sheet.
  * Visual review goes to **Haiku** (section 3). Only escalate to a bigger model to fix what Haiku flags.
  * Don't re-verify things the harness already covers. One `qa.ps1 -Server` per merge is enough.
* **Merging a worktree:** `bash tools/dev/merge_worktree.sh <worktree path> <NAME>`. It stages the worktree
  (including ignored `mod/src/**/build|bin|out` source folders), writes a patch and `git apply --3way`s it onto the
  main checkout. Then:
  * Generated files in conflict: regenerate, don't hand-merge. `pack/index.toml` and `pack/pack.toml`: take ours,
    then `py -3 -I tools/refresh_packwiz.py`. Biomes: `py -3 -I tools/worldgen/biomes.py` then
    `py -3 -I tools/worldgen/noise_settings.py`. Quest text: `node tools/quest_lang.js`.
  * `ClientAutotest.java` conflicts: keep ours (the tour code), add the other side's hook lines if any.
  * Index files (`places/index.json`, tag lists): union both sides.
  * Then `tools\qa\qa.ps1 -Server`, then commit.
* **Known merge traps (all hit before):** duplicate registry ids across modules (the QA `data` check and the
  server boot catch these); generators that clean whole folders (biomes.py was fixed to only clean its own files);
  `.gitignore` hiding source folders named `build/`; two modules registering the same `DimensionSpecialEffects`.

## 1. Current state (as of this plan)

Merged and verified on `tamriel-overhaul` (`git log --oneline | head -60` for detail): Phase 1 fixes; the Tamriel
map + terrain (height 704, eroded, peaks); 113 biomes + flora generator; 4,914 places across every province (with
Oblivion/Morrowind data); settlements + building grammar + zoning; landmarks & dungeons; roads (pre-planned, 554
roads); regions/map UI; NPCs; creatures; magic & skills; races; quests (296) incl. factions (31 new) & jails; loot
progression & collectibles; economy; survival/fishing/homesteads; curses; sailing + seas (walkable decks, NPC
ships); realms (Deadlands, Sovngarde, Shivering Isles, Apocrypha, Soul Cairn, Coldharbour); mounts & provincial
homes; audio (2 passes); visuals; docs + handbook; full-pack QA (DH, Oculus/Complementary preset, clouds y 330).
`tools\qa\qa.ps1 -Server` passes: world boots, 296/296 quests complete, magic selftest OK.

**Unfinished work sitting in worktrees** (interrupted by usage limits; their uncommitted changes are preserved):

| Worktree (`.claude/worktrees/…`) | Workstream | State when stopped |
| --- | --- | --- |
| `agent-af776652e182312b5` | LORE WRITERS: per-place hooks, notes, mini-quests | content written; was writing its smoke-test script |
| `agent-a0313f27ae6b75414` | QUESTS2: province side-quest arcs | Cyrodiil + Hammerfell done; High Rock and others in progress |
| `agent-a88090a80100158d7` | VISUALS2: cloud types, distant weather | renderer written; tuning lighting/cumulus |
| `agent-a6c3ffa03d0a26f1c` | EXPLORE: density audit + minor sites | audit + many sites; tuning water/coast placement |
| `agent-a933b1ded344ad837` | DUNGEON-QA: analyzer, bot, redesign | analyzer + redesign done; was mid "restore the design code" after an experiment — check carefully |
| `agent-aaf93a4a24163f11e` | PERF+BAKE | only a baseline benchmark harness |
| `agent-a0a95572633615768` | ILIAC BAY (Daggerfall geography) | nothing usable yet |
| `agent-a2563a6b1f0ded80b` | ITEMS AFFIX | nothing yet |

The original task prompts for each are reproduced in section 5 so a fresh agent can redo them.

**How to continue a stopped worktree:** if you are still in the same Claude Code session, resume the agent with
`SendMessage` (its id is the hex in the folder name) and "continue where you left off; finish and report". In a new
session, start a fresh agent in that worktree path (`cd` into it) with the section 5 prompt plus: "Uncommitted work
from a previous attempt is already in this worktree — review `git status`/`git diff --stat` first and finish it
rather than starting over." First rebase it onto the branch tip: `git stash && git merge --ff-only tamriel-overhaul &&
git stash pop` (resolve conflicts keeping both sides).

## 2. The QA harness (use this instead of the game)

`tools\qa\qa.ps1` prints a ~20–40 line report and writes `mod\run\qa-report.txt`:

* `qa.ps1` — Gradle build + static checks: assets, item audit, quest graph, sound catalog, data lint (all JSON
  parses, no duplicate place ids, quest index valid, no duplicate registry ids). ~4 min with build, 30 s with
  `-NoBuild`.
* `qa.ps1 -Server` — plus a headless dedicated server: generates the spawn region, runs
  `/skycraft-quest selftest flow` and `/skycraft magic selftest`, stops itself (via `-Dskycraft.autorun`), and
  summarises the log (`tools/qa/log_summary.py`: crashes, Skycraft stack traces, registry errors, missing assets,
  selftest results). ~2 min. Add server commands with `-ServerCmds "cmd a|cmd b"`.
* `qa.ps1 -Server -Client -Tour "Name:x:z;Name:x:z"` — client autotest + one contact sheet
  (`mod\run\qa-contact-sheet.jpg`). **Final verification only.**

## 3. Verification strategy (token-efficient, layered)

Cheapest first; stop at the first layer that answers the question.

1. **Static lint (seconds, no Minecraft):** `qa.ps1 -NoBuild`. Extend `tools/qa/data_checks.py` when a new class of
   bug appears (it is the cheapest regression net).
2. **Layout lint (seconds–minutes, no chunk generation)** — task T2 builds it: from Places, the baked road plan,
   settlement plans and the terrain rasters, compute numeric checks and ONE annotated map PNG:
   place footprints overlapping; spacing violations; settlements on steep/flooded ground; places in water; road
   graph connectivity per landmass, max road slope, water crossings without bridges; dungeons too close to towns;
   point-of-interest density gaps (walking time to nearest site); spawn-table coverage per region × biome family ×
   day/night × surface/cave; every creature/NPC role spawnable somewhere; every biome reachable in some palette.
3. **Headless world census (2–4 min, server only)** — task T3: `/skycraft-qa census <area>` generates a sample grid
   of named places and wilderness headless and reports numbers: structures generated per type, NPCs spawned at
   anchors per settlement (expected vs actual roles), containers with loot, dungeon reachability (DUNGEON-QA's
   analyzer/bot), road blocks paved per km, creature counts by type/region after N spawner ticks, flora/feature
   counts per biome. Output: a compact table, not logs.
4. **Offline renders + Haiku visual review (cheap, only when visuals changed)** — task T4: `/skycraft-qa export`
   dumps generated structures/areas to files in one headless boot; `tools/qa/render.py` renders isometric (4
   angles), top-down and per-floor cutaways with real block textures (extracted once from the mod jar + the vanilla
   client jar in the Gradle cache) into contact sheets. Then launch an Agent with `model: "haiku"` and a rubric
   (examples: "no floating or cut-off blocks; walls only where expected; buildings 2–6 blocks apart around streets;
   streets furnished; roads reach gates; dungeon floors connected; no repeated identical buildings side by side;
   trees natural; no water where it shouldn't be") that returns ONLY a numbered issue list with sheet/tile
   references. Fix flagged issues with Sonnet. The owner can eyeball the same sheets.
5. **Full client (rare):** `qa.ps1 -Server -Client` in the dev client, and once at the very end the real Prism pack
   (section 6). Distant Horizons/shader checks only there.

## 4. Task list (do in this order)

**T1. Land the unfinished worktrees** (one at a time; each: finish → merge → `qa.ps1 -Server` → commit):
1. LORE WRITERS (`agent-af776652…`): nearly done. Finish smoke test; merge.
2. QUESTS2 (`agent-a0313f27…`): finish High Rock + remaining provinces (use `.reference/daggerfall_ref/report.md`
   archetypes/region lore for High Rock & Hammerfell); `node tools/quest_lang.js`; selftest flow must pass.
3. VISUALS2 (`agent-a88090a8…`): finish tuning; keep the single cloud-height source
   (`AtmosphereConfig.CLOUD_HEIGHT`, OverworldClouds); skip its client screenshots — use T4 later if needed.
4. EXPLORE (`agent-a6c3ffa0…`): finish coast/water site placement; merge; its density audit feeds T2.
5. DUNGEON-QA (`agent-a933b1de…`): inspect `git diff --stat` first (it was mid-experiment); make sure the redesign
   (cyclic layouts, set pieces, loop-back shortcut) is the active code and the analyzer/bot pass with 0 blockers;
   merge. Its analyzer becomes part of T3.

**T2. Layout lint** (new, small): `tools/qa/layout_lint.py` (+ a tiny server dev command only if a check truly needs
live data). Wire as `qa.ps1 -Layout`. Then fix what it finds. Already known: 5 foreign-land settlements in water
(`pyandonean_fleet_anchorage`, `kjolvik`, `tiger_plains_village`, `tang_mo_harbor_village`, `mistveil_village` —
road planner warnings); nudge them onto land with the provinces.py land check (foreign lands use `Beyond` noise —
provinces.py has a Python port).

**T3. Headless world census:** `/skycraft-qa census` + `qa.ps1 -Census`. Reuse DUNGEON-QA's analyzer, EXPLORE's
audit, roads' network stats, and settlement anchors.

**T4. Offline render pipeline + Haiku review:** `/skycraft-qa export` (batch list of place ids / areas / structure
ids; writes `mod/run/qa-exports/*.bin` with a block palette + run-length data) and `tools/qa/render.py`
(numpy + PIL isometric/top-down/cutaway renderer; texture atlas cached in `mod/run/qa-cache/`). Review by a Haiku
agent with the rubric in section 3. Run it on: all 9 Skyrim capitals, 1 generic town per culture, 1 dungeon per
theme, each realm's keep/landmark, 2 road junctions with bridges.

**T5. ILIAC BAY** (prompt in section 5): redraw High Rock/Hammerfell to Daggerfall's geography, add its 45
political regions, ingest its locations (subsampled). Then T2 lint.

**T6. ITEMS AFFIX** (prompt in section 5).

**T7. PERF + BAKE** (prompt in section 5): fixed default seed ("Nirn"), profiling and hotspot fixes (Places linear
scans are already indexed; check TamrielHeight column cost, structure acceptance order, road planner sampling),
build-time bake of deterministic data, idle pre-generator, optional starter-world download.

**T8. Authored content (only if budget remains):** hand-authored blueprints for ~150 marquee dungeons (requires
DUNGEON-QA's blueprint format), hand-planned districts for the capitals (requires the building-grammar planner's
plan format), more side quests.

**T9. Final release pass:** refresh README/FEATURES/INSTALL counts (they still list curses/sailing as "coming");
`tools/build_prism_instance.py`; one full Prism run of the real pack (the `Skycraft-QA` instance flow in
`docs/PLAYTEST-FULLPACK.md`: DH + Oculus/Complementary) with a short tour and one contact sheet; fix blockers; tag a
release commit.

## 5. Prompts for the remaining workstreams

Each prompt below is self-contained; prepend "Read docs/AGENT-RULES.md and docs/FINISH-PLAN.md section 0 first."
and give the agent `isolation: worktree` (or point it at the existing worktree to finish).

* **ILIAC BAY** — Make High Rock and Hammerfell match Daggerfall's Iliac Bay. Reference `.reference/daggerfall_ref/`
  (locations.json 15,251 sites, regions_mc.json, iliac_coast_mc.json, politic/climate/height previews,
  warp_diagnostics.json, region_lore.json). Redraw those provinces' coast, ranges and rivers in
  `tools/worldgen/tamriel_vectors.json` following Daggerfall's map (anisotropic but smooth transform pinned at
  Daggerfall, Wayrest, Sentinel, Orsinium; Skyrim/Cyrodiil borders unchanged); re-place Camlorn, Shornhelm,
  Evermor, Farrun, Jehanna, Skaven, Rihad, Taneth, Stros M'Kai, Elinhir consistently and update every reference
  (places files, quests/places.json, docs/TAMRIEL.md §9); add the 45 political regions to regions.json with
  palettes/weather/levels (climate map → palette); regenerate rasters (`tools/worldgen/tamriel_map.py`) and biomes;
  ingest locations with `tools/provinces.py --ingest .reference/daggerfall_ref` subsampled to the density target
  (cities, towns, temples, notable dungeons, covens, vampire haunts); land/spacing checks; `qa.ps1 -Server`.
* **ITEMS AFFIX** — Skyrim-faithful item variety: found-item condition/quality (Worn, Rusted, Notched,
  Well-made, Masterwork) and cultural makers (Skyforge, Orsinium-forged, Dwemer-wrought, Ayleid-etched,
  Redguard-folded, Bosmer bone, Akaviri) from the region of the roll, stored in NBT, shown in tooltips, stat/value
  modifiers compatible with tempering and enchanting; Skyrim-style enchanted item names by tier
  ("of Burning/Scorching/Searing/Blazing/the Inferno"); rare "storied" items (culture name parts + one-line
  provenance) in ~5% of boss chests; hook into loot functions (containers, corpses, merchants at a lower rate);
  extend `tools/loot_sim.py` to keep value curves inside docs/LOOT.md bands; `/skycraft-items roll <table> [level]`.
* **PERF + BAKE** — see T7. Profile with JFR on the headless server (`qa.ps1 -Server` style run with
  `-XX:+FlightRecorder`), fix hotspots without changing output (verify with a block-hash of generated chunks
  before/after), fixed default seed in `SkyCreateWorldScreen` + server template, bake pipeline into
  `mod/src/main/resources/skycraft/baked/` with seed/version hash and live fallback, `/skycraft-pregen`, optional
  starter-world zip tool. Report chunks/s before/after.
* **LORE WRITERS / QUESTS2 / VISUALS2 / EXPLORE / DUNGEON-QA** — finish the existing worktrees (section 1). If
  starting over, their goals: per-place hooks + readable notes + 150 mini-quests (lore); 10–20 side-quest arcs per
  province with unique bosses and hidden-element starts (quests2); typed cloud renderer + distant weather + regional
  weather tuning (visuals2); density audit + culture-specific minor sites + hidden unique items (explore);
  dungeon analyzer + bot + cyclic/set-piece redesign + telemetry (dungeon-qa).

## 6. Definition of done

* `qa.ps1 -Server -Layout -Census` all PASS; layout lint has no errors (warnings documented).
* Haiku visual review of the T4 sheet set returns no blocking issues.
* One full Prism run: no crash, no Skycraft errors in the log, acceptable FPS with Complementary + DH.
* Docs reflect the shipped features and counts; `dist/Skycraft-client-instance.zip` built from the final commit.
