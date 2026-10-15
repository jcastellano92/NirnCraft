# Skycraft finish plan

> **Superseded as the plan (2026-10-13).** New sessions start at [`docs/START-HERE.md`](START-HERE.md). The roadmap,
> the order of work and the progress table now live in [`docs/world/WORLD-PLAN.md`](world/WORLD-PLAN.md) (sections 5
> and 7), and every phase has a ready-to-run prompt in [`docs/prompts/`](prompts/README.md).
>
> **Still current here:** section 0 (ground rules and merge traps; `docs/prompts/_COMMON.md` summarises them),
> section 2 (the QA harness and the fast loop) and section 3 (the layered verification strategy).
> **Historical:** section 1 (state as of 2026-10-11), section 4 (the T1-T9 task list; status per task below),
> section 5 (old prompts; replaced by `docs/prompts/`) and section 6 (definition of done; replaced by the R15 gates).

This was the execution plan for wrapping up Skycraft on a tight token budget, written so a Sonnet (or Opus) session
could pick it up cold.

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
  * Then `tools\qa\qa.ps1 -Server -Rebake` (one boot: re-bakes inside the same server run when the bake is stale,
    section 2; `merge_worktree.sh` prints this), then commit.
* **Known merge traps (all hit before):** duplicate registry ids across modules (the QA `data` check and the
  server boot catch these); generators that clean whole folders (biomes.py was fixed to only clean its own files);
  `.gitignore` hiding source folders named `build/`; two modules registering the same `DimensionSpecialEffects`.

## 1. Current state (historical: as of 2026-10-11; the live state is WORLD-PLAN section 7)

Since then: LORE WRITERS, QUESTS2, VISUALS2, EXPLORE, DUNGEON-QA and PERF+BAKE were merged (commits 0aa075c, f62d69a,
81cdedb, 4dd6e15, 133d9a6, fd5c0c9). ILIAC BAY became roadmap phase R5 (`docs/world/ILIAC-BAY.md`,
`docs/prompts/R5-iliac-bay.md`) and ITEMS AFFIX became R14 (`docs/prompts/R14-items-affix.md`); their old worktrees
hold nothing usable.

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

* `qa.ps1 -Unit` — Tier 0 pure-Java JUnit 5 unit tests (`RoadPlan`, `Profile`, `ScenePlan`, `StreetPlan`,
  `TamrielMap`/`Places`/`Regions`, `Bake` input hashes; `./gradlew test`, no server boot, < 5 s warm).
* `qa.ps1` — Gradle build + static checks: assets, item audit, quest graph, sound catalog, data lint (all JSON
  parses, no duplicate place ids, quest index valid, no duplicate registry ids). ~4 min with build, 30 s with
  `-NoBuild`.
* `qa.ps1 -Server` — plus a headless dedicated server: generates the spawn region, runs
  `/skycraft-quest selftest flow` and `/skycraft magic selftest`, stops itself (via `-Dskycraft.autorun`), and
  summarises the log (`tools/qa/log_summary.py`: crashes, Skycraft stack traces, registry errors, missing assets,
  selftest results). ~2 min. Add server commands with `-ServerCmds "cmd a|cmd b"`.
* `qa.ps1 -Server -Bake` — strict: also fails when the baked data (`skycraft/baked`) is stale. Release builds
  (`build_release.ps1`, which bakes explicitly first) use this.
* `qa.ps1 -Server -Rebake` — one boot instead of QA + a second `bake.ps1` boot: when the bake is stale the same
  server run re-bakes (`/skycraft-bake refresh`), verifies the written files against a second live computation, the
  script copies them into `resources/skycraft/baked` and rebuilds the jar; report line `PASS bake rebaked (...)`.
  When the bake is current it runs the same full comparison as `-Bake`. **The command after every merge.**
* `qa.ps1 -Server -Client -Tour "Name:x:z;Name:x:z"` — client autotest + one contact sheet
  (`mod\run\qa-contact-sheet.jpg`). **Final verification only.**

**Fast loop (dev server; iterate with this, run the full gates once at the end of a phase):**

* **Tier 0 & Tier 1 first:** run `tools\qa\qa.ps1 -Unit` (< 5 s, no server) for pure-Java worldplan/map/bake math;
  use `tools\dev\devserver.ps1 hotswap` (< 3 s over JDWP `127.0.0.1:5005`, auto-restarts on schema changes) for
  method-body edits; use `/skycraft-dev reload <scenes|places|cityplans|quests|palettes|all>` (< 1 s) after editing
  JSONs under `mod/src/main/resources/skycraft/`.
* `tools\dev\devserver.ps1 start|stop|status|restart|reset|hotswap` keeps ONE headless dev server running (world
  `mod\run\devworld\world`, Nirn seed, own port and working dir, so it never collides with `qa.ps1`/`bake.ps1`).
  `devserver.ps1 run "cmd a|cmd b" -Wait` runs lines through its inbox in about a second and prints their output
  (server commands, `task:roadplan|roadterrain|bake|verify`, `@wait <ticks>`, `@until <regex>`, `@idle`). `run`
  auto-starts the server and hot-swaps changed `.java` method bodies (~2 s, falling back to a ~40 s restart only on
  schema or non-reloadable resource changes).
  Memory: every server JVM started by `devserver.ps1`/`qa.ps1`/`bake.ps1` gets `-Xmx4G` (`-Xmx 6G` or
  `$env:SKYCRAFT_SERVER_XMX`), and `devserver.ps1` refuses to start while another worktree's dev server runs
  (several servers at once ran the PC out of native memory; `-Force` overrides).
* `/skycraft-dev regen x1 z1 x2 z2 [skycraft:scratch]` (dev server only) regenerates that area in place with the
  current code: deletes the chunks, generates them fresh, repaves the roads, saves (~7-10 s for 12x12 chunks).
  `skycraft:scratch` = same generator, no roads (terrain-only checks such as `p2_corridors.py measure`).
* `tools\qa\quick_corridor.ps1 whiterun:riverwood [-Index n] [-Replan]` = regen of one 160-block tile of the
  corridor + `road_measure.py --box` on it: ~15 s with the server up. After changing road PLANNING code use `-Replan`
  (the dev world keeps its planned network) or `devserver.ps1 reset`.
* `tools\qa\full_corridors.ps1` = the 4-corridor gate (whiterun:riverwood, riverwood:helgen, whiterun:rorikstead,
  dragon_bridge:karthwasten): fresh dev world, waits for the planner, one `/skycraft-dev regenchunks` of every chunk
  within 2 of the road points, `road_measure.py` over all four. `-Reuse` keeps the world (replans). Run it once at the
  end of a phase; iterate with `quick_corridor.ps1`.

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

## 4. Task list (historical; superseded by the WORLD-PLAN roadmap)

Status of each task as of 2026-10-13: **T1** done (all five worktrees merged). **T2** done (`tools/qa/layout_lint.py`,
`qa.ps1 -Layout`; commit a1215f7). **T3** headless census not built (WORLD-PLAN open item O3). **T4** partly done
(`render_town.py`, `render_dungeon.py`, `region_render.py`, `contact_sheet.py` exist; snapshot goldens not built: open
item O4). **T5** ILIAC BAY is now R5. **T6** ITEMS AFFIX is now R14. **T7** PERF+BAKE done (fd5c0c9). **T8** authored
content is now the province phases R4-R12. **T9** final release pass is now R15. Do not work from this list; use
`docs/START-HERE.md`.

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

**T4 notes — prefer existing offline renderers and snapshot diffing over custom work:**
* Generation needs only the headless dedicated server (~30 s boot, no graphics/DH/shaders); it saves region files.
  Rendering never needs the Minecraft client: offline renderers read the saved world folder directly.
* Evaluate first (keep whichever works with 1.20.1 + Skycraft blocks; load the mod jar as a resource pack for
  textures): **BlueMap CLI** (open source; 3D/top-down renders from a world folder), **uNmINeD** (fast top-down,
  Windows), **Chunky** (path-traced beauty shots, optional). Use our own `render.py` only for what they can't do
  (e.g. per-floor dungeon cutaways).
* Two image types: **map views** (top-down, 1 px per block column: kilometres of terrain/roads/towns in one image)
  and **contact sheets** (`tools/qa/contact_sheet.py`: many close-ups tiled into one image).
* **Snapshot (golden-image) testing:** a fixed list of sample locations (`tools/qa/snapshots.json`) is rendered after
  each change and compared to the last approved images (pixel-diff threshold); only CHANGED tiles go into the
  review sheet. Unchanged world = zero review cost. Approve by copying renders to `tools/qa/golden/` (gitignored if
  large; keep small JPGs).
* Pure-data views need no Minecraft at all: terrain/biome/region maps from the rasters (`tamriel_map.py` previews,
  `/skycraft-tamriel heightmap` uses noise only), settlement and dungeon PLANS drawn as 2D diagrams from planner
  output, the road network render (`roads/NetworkRender`). Use these for layout questions before generating blocks.
* Forge GameTest (headless, built in) is the right tool for small behavioural checks (dungeon reachability,
  NPC at anchor, loot container present) if the census command grows unwieldy.

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
`tools/build_prism_instance.py`; (OWNER RUNS the visual test themselves; do NOT launch the client, hand over a checklist instead) was: one full Prism run of the real pack (the `Skycraft-QA` instance flow in
`docs/PLAYTEST-FULLPACK.md`: DH + Oculus/Complementary) with a short tour and one contact sheet; fix blockers; tag a
release commit.

## 5. Prompts for the remaining workstreams (historical; replaced by `docs/prompts/`)

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

## 6. Definition of done (historical; the current one is the R15 gates in `docs/prompts/R15-final-polish.md` and DESIGN-PRINCIPLES 5.5 per region)

* `qa.ps1 -Server -Layout -Census` all PASS; layout lint has no errors (warnings documented).
* Haiku visual review of the T4 sheet set returns no blocking issues.
* One full Prism run: no crash, no Skycraft errors in the log, acceptable FPS with Complementary + DH.
* Docs reflect the shipped features and counts; `dist/Skycraft-client-instance.zip` built from the final commit.

## Playtest-1 feedback fixes (intro, death, saves, abilities)
* **Intro**: no cart/teleport. One fixed start (world spawn, SafeSpawn: never a treetop). `main_unbound` is a short road walk to Riverwood; the `intro:` action plays a 3-line scene and ONE distant dragon flyby per player (flag `intro_played`). Config `quest/intro` (default on) skips it; `helgenStart` (legacy teleport) now defaults off. Ralof/Hadvar no longer fight each other; encounter skirmishes break off after 30 s; the dragon wing-beat volume was cut and scenery dragons are capped at 3.
* **Death**: the death screen no longer offers "Title Screen"; respawn is at the bed, else the nearest settlement THIS player has discovered (market square; inns preferred; config `death/respawnMode`), else world spawn, always on solid ground (`core/SafeSpawn`). Config `death/dropMode` = VALUABLES (default: keep armour, weapons, ammo, tools, food; drop gold share + gems, jewellery, soul gems, costly misc), GEAR, EVERYTHING; `goldDropPercent` (25).
* **Saves**: the pause menu "Save Game" saves the world (host / single-player only; hidden on remote servers). "Save and Choose Character" saves, leaves the world and opens the character list (local server only). There is no named in-world snapshot (vanilla has no safe live reload); revert by re-opening the character.
* **Abilities**: greater powers (racial, standing stone, curse) recharge in 5 min at level 1 down to 2 min at level 50 (`powerCooldownMaxSeconds`/`powerCooldownMinSeconds`); the HUD shows the timer. KeyConflictResolver moves a Skycraft key off chat/command.
* **Golden Claw**: only a merchant standing in Riverwood can give it.
