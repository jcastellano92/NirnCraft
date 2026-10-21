# NirnCraft agent rules

**Platform: Minecraft 26.3 / NeoForge 26.3.0.90 / Java 25, official Mojang mappings.** Before building or controlling
a runtime, read [NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md); the doc map is [README.md](README.md). Preserve the
owner's own instances and worlds. Normal Minecraft multiplayer/LAN is the only hosting path. A green build does not
replace the dedicated-server and loaded-world visual gates.

Rules every agent/workstream follows. Reference data from the owner's game installs lives in `.reference/` (gitignored, facts only).
New sessions start at `docs/START-HERE.md`; the roadmap and its state table are in `docs/world/WORLD-PLAN.md`, the
phase prompts in `docs/prompts/`. Renderer work starts at `docs/ATLAS-STATUS.md` (present state, the owner's order of
work, standing rules). The world-building rules at the end of this file apply to every workstream; the resource rules
below apply to every session and agent.

## Resources (every session and agent; ATLAS-STATUS.md, after the 2026-10-20 machine lock-up)

* **One heavy job at a time**: native builds, Gradle, offline renders and the game never run together. Agents run
  one or two at a time, not four.
* **Every heavy job goes through the shared lock**: `py -3 E:/NirnCraft-scratch/locks/heavy.py "<agent>: <what>" --
  <command>` (builds share 3 slots; `--exclusive` only for the GPU-heavy part: the game, offline renders, GPU tests;
  every job and its children capped at 16 GB, `--mem-gb N`). Start it with the Bash tool's `run_in_background` and
  keep working. It writes a timestamped log per job (`locks/logs/`) and a ledger (`locks/jobs.jsonl`: wait, run, rc,
  peak memory, phase times); `py -3 E:/NirnCraft-scratch/locks/watch.py --report` shows where time went.
* **Every test and long job is traceable**: it prints `TRACE begin <phase>` / `TRACE end <phase>` around each phase,
  `TRACE progress <phase> <i>/<n>` at least every 30 s inside long loops, and `TRACE done <name> <secs>s` for steps it
  times itself (one per suite / test / feature type, with its result in the line). Gradle tasks and slangc compiles are
  traced automatically. A job silent for 5 minutes is flagged to the orchestrator.
* **Check free RAM before a heavy job** (`Get-CimInstance Win32_OperatingSystem` FreePhysicalMemory, or Task Manager);
  wait or close something rather than start one on a full machine.
* **Heavy outputs go to `E:\NirnCraft-scratch` or the repository's `.work`**, never C: (renders, bakes, traces,
  exports, unzipped jars).
* **Never run the autotest action queue while the owner plays**: it holds the player. Use the passive profiler
  (`nirncraft.atlas.profile=true`: atlas-info every 2 s to `.minecraft/atlas-profile.log`) instead.
* **GPU-first, no CPU interim stages**: scene building, generation and ingest go on the GPU; the CPU path is only the
  CPU backend's fallback. No background CPU threads, Java pages or single-threaded features as steps on the way.
* **Close the dev client after each test batch**; never leave it open idle.

## Phase 1 rules (still apply)

You work on NirnCraft, a Skyrim total conversion for Minecraft 26.3 NeoForge (mod id `nirncraft`, Java package
`com.nirncraft`), usually in your OWN git worktree (your current working directory). Paths below are relative to the
repository root.

## Build
* `py -3 tools/dev/build_263.py --offline compileJava` (or `build`; `tools/dev/build_263.ps1 -Tasks build -Offline` in
  PowerShell). It selects Java 25, an isolated Gradle home and a build directory outside the checkout
  (`NIRNCRAFT_263_SCRATCH`, machine default `E:\NirnCraft-scratch\migration-26.3`), and holds a lock so only one build
  runs at a time. Trust the `BUILD SUCCESSFUL` / `BUILD FAILED` line. Compile after a chunk of work, not after each
  edit. You MUST finish with `build` passing.
* Real 26.3 signatures: the patched sources and classes jars under that build directory
  (`moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar`); read them instead of assuming older signatures.
  Third-party mod jars are cached in `dist\.cache\` (read-only).

## Code rules
* No mixins, no access transformers. NeoForge events and public API; screens replaced via `ScreenEvent.Opening`.
* NEVER reference client classes (`Minecraft`, `Screen` and subclasses, `KeyMapping`, renderers, `LocalPlayer`)
  from common code, not even behind an `isClientSide` check: the bytecode verifier loads them and the dedicated
  server crashes. Hand off via `Sides.runOn(Dist.CLIENT, () -> () -> SomeClientClass.method(...))`
  (`com.nirncraft.platform.Sides`) where `SomeClientClass` lives in a `client` package.
* Worldgen code never reads/writes outside the region being generated, never touches `ServerLevel`-only services
  (POI manager, entity lookups) off the server thread.
* Server is authoritative. Validate every client packet (distance, menu type, ownership, value bounds).
* Multiplayer must work both on an integrated (LAN) server and on a dedicated server.
* Player data: `SkyData.get(player).module("<name>")`.
* Lang: only your module's file `mod/src/main/lang/nirncraft/<module>.json` (merged at build time).
* Match the surrounding code style, comment density and naming. Don't reformat files. Don't rewrite files
  wholesale; make targeted edits.
* Stay inside your OWNED packages. If a fix truly needs a small change in another package, make it minimal and
  additive and list it in your report.
* Do NOT touch region/holds layout code (`world/Holds*`, carriage destination positions in `roads/Carriages`,
  house locations in `economy/Houses`): a later phase replaces the whole region system with a Tamriel province
  map. Note related bugs in your report instead.

## Git
* Unless the owner explicitly requests it, do NOT commit, push, merge, rebase, reset or switch branches. Leave all changes uncommitted in your worktree.
  New files are fine (leave them untracked or `git add -N`).
* GitHub Actions is disabled; builds/tests run locally. No automatic commits or pushes. Keep runtime logs, generated
  QA reports, caches and release packages ignored. Required source bakes/assets and bootstrap jars remain tracked.

## Final report (your last message)
* Each finding: FIXED / PARTIAL / SKIPPED (+why), with file:line of the fix.
* Every file you created; every file outside your owned packages you edited.
* Anything risky, needing playtest, or left for later.
* Confirm the final `build_263.py build` result and the harness result you ran.
# NirnCraft Phase 2+ rules (every workstream reads this)

Read these first:
* `the Phase 1 rules section above`.
  The build, code, git and report rules there all still apply. One exception: the "don't touch Holds, Carriages
  or Houses" rule is lifted for the workstreams that now own them.
* `docs/TAMRIEL.md` in your worktree: the world plan and the Java contracts. The contract signatures in
  `com.nirncraft.tamriel` (`TamrielMap`, `MapSample`, `Region`, `Regions`, `Place`, `PlaceType`, `Places`) are
  fixed. You may add methods, but don't change or remove existing ones.

## The owner's direction
* NirnCraft replaces ALL third-party content mods with its own systems: terrain, biomes, structures, villages,
  NPCs and ambience. Performance and library mods stay. Build the real thing, not placeholders. Aim for
  complete, polished, Skyrim-faithful content.
* Don't stop at the minimum. When something in your scope can clearly be made more complete or more
  Skyrim-like within your time, do it.

## Skyrim reference data (the owner's own copy of Skyrim SE)
* Extracted reference data is in
  `.reference/skyrim_ref\`:
  * `markers.json`: 436 map markers with `name`, `type`, hold, game coords, and `mcX`/`mcZ` in Minecraft
    blocks (Whiterun = 0,0; scale 0.0158561 blocks per game unit; north = −Z). It also lists word walls,
    standing stones and dragon mounds.
  * `holds.json`: holds per marker, plus approximate hold regions.
  * `summary.json`: bounds, scale, water bodies.
  * Previews and heightmaps: `preview_markers.png`, `preview_holds_tamriel.png`, `heightmap_tamriel_ref.png`
    (16-bit; height units = pixel − 37032; 256 game units per pixel), and `water_tamriel.png`, plus the
    Solstheim versions.
  * The game install itself is the owner's own Skyrim Special Edition (read-only; its path is machine-specific).
* **Legal rule (strict).** Bethesda's assets are copyrighted, and this mod is redistributed.
  * NEVER copy game meshes, textures, sounds, voice, music, book text or dialogue into the repo, raw or
    converted.
  * NEVER commit the raw heightmap or anything sampled pixel-for-pixel from it.
  * Allowed: facts and inspiration.
    * Names and relative positions of places.
    * Hand-authored vector geography traced from the reference: coastline polylines, mountain range
      polylines with your own chosen heights, river polylines, lake polygons. Simplify them; fan-map level of
      detail.
    * Colour palettes sampled as a few representative colours.
    * Architectural ideas.
  * Everything shipped must be your own original work: procedural Python/PIL textures and original Java
    builders.

## Coordinates
* Skyrim's map spans about mcX −3180..2830 and mcZ −2480..1880, with Whiterun at 0,0.
* Vertical: the world runs from y −64 to y 639 (the terrain noise to y 575); the Throat of the World peaks near y 560,
  High Hrothgar's ledge at about y 434; sea level 63 (docs/TAMRIEL.md section 4).

## Test

* **Use the harness** for every test, visual or not: `py -3 tools/harness/harness.py run` (smoke), `run --changed`,
  `watch`, `gate` (before a merge). Read [tools/harness/README.md](../tools/harness/README.md) first. Live tests run
  only in the disposable `NirnCraft-26.3-autotest` Prism instance, which the harness launches and closes; never touch
  the owner's own instance. Only one live client and one dev server per machine.
* **Fast loop with the persistent dev server**: `tools\dev\devserver.ps1 start|status|hotswap|run "cmd|cmd" -Wait|stop`
  (JDWP method-body hot-swap in seconds; restarts only for schema or non-reloadable resource changes; its own world in
  `mod/run/devworld-26.3`), `/nirncraft-dev reload <scenes|places|cityplans|quests|palettes|all>` for in-JVM JSON
  reload, `tools\qa\quick_corridor.ps1 <a>:<b>` (one tile) and `/nirncraft-dev regen` for a bounded area. Servers get
  `-Xmx4G`; stop the dev server when you finish.
* **Full gates only at a checkpoint:** `tools\qa\full_corridors.ps1` (the 4-corridor road gate) when the road work of a
  phase is done, re-bake after world-generation changes (`tools\bake\bake.ps1`), and `harness.py gate` before merging.
  Do not weaken a gate; a gate that only the slow path can run is run once, not skipped.
* Release builds stay strict: `tools\build_release.ps1` builds, verifies the bake in a fresh world and runs the server
  smoke gate.

## World-building rules (every workstream; docs/world/DESIGN-PRINCIPLES.md)

* **World building first.** Everything (terrain, architecture, palette, a prop in a scene, an NPC's job and line, a
  quest, a book, a dungeon's enemies, music, weather) exists to build the world. Each element must express its
  place's history, culture, conflict, economy, faith and ecology, and agree with every other element there. When two
  rules conflict, the one that makes the place more believable wins. A desert town with a pine-log mill, a bandit camp
  with no road to rob or a mine with no ore in the rock is a bug.
* **Cite the world-model sheet.** Every region and province has an 11-layer causal sheet (L1 geology, L2 climate, L3
  water, L4 ecology and micro-biomes, L5 economy, L6 settlement pattern, L7 society, L8 culture and belief, L9 politics,
  L10 the hidden and underground, L11 history), as data in `mod/src/main/resources/nirncraft/tamriel/world/<province>.json`
  (designed in `docs/world/provinces/<province>.md` section 1). Every sheet field in L2-L11 cites a field above it.
  Every new content record (place, NPC, quest, scene kind, dungeon identity, book, creature table, palette or
  micro-biome rule, music cue, weather rule) carries `why: [sheet field ids]`, e.g.
  `"why": ["the_reach.L5.silver_mining", "the_reach.L7.prison_labour"]`. Ids must resolve
  (`tools/qa/worldsheet_lint.py`); content with no cause is rejected. Until a province's JSON sheet exists, cite the
  ids of its province document's section 1. Content reports quote the region's world truth paragraph and answer the
  four questions (why does this place exist here, what do its people do, what were the ruins, why are the dangers on
  this road) with sheet ids.
* **Skyrim-measured floors.** The quality bar is Skyrim, measured from the real game (DESIGN-PRINCIPLES 4.1-4.9).
  A region's count of each kind is `max(absolute floor, round(Skyrim's median per km² x the region's km²))`; each
  settlement meets its tier's measured medians (capital: 73 named NPCs, 27 interiors, 23 named buildings, 15 merchants,
  5 trainers, a court; city 20 NPCs; town 9; village 3; hamlet 2), with the tier minimum as the absolute floor. ESO
  ideas are an optional layer on top of a met floor (4.12), never the bar and never a replacement.
* **Modernise legacy sources: keep / amplify / replace** (DESIGN-PRINCIPLES 1). Keep the facts that define a place in
  lore (name, rough position, culture, role, the famous thing). Amplify what the source had in seed form until it
  drives a quest, a scene, a mechanic or a view. Replace the source's limits (bland terrain, repeated town blocks,
  generic mazes, nameless NPCs, random encounters, consequence-free generator quests, empty space) so no trace remains.
  Never import legacy quantity: curate to the Skyrim floor. Province phases report a "source did vs we do" table.
* **Source precedence.** Positions from Skyrim, Oblivion (Cyrodiil) and Morrowind (Vvardenfell, Solstheim) are
  canonical and are never overridden by Daggerfall or Arena data. Daggerfall and Arena may move or add places only
  where those three games have no say (High Rock, Hammerfell, the Iliac Bay towns, the Arena-only provinces). Between
  provinces the lore map wins. Fan projects (Tamriel Rebuilt, Beyond Skyrim, Project Tamriel) are never traced and
  their invented names never reused.
* **Gates and the plan.** Every phase prompt lists measurable gates; never weaken one to pass it. The orchestrating
  session (not a worktree agent) merges, commits and updates the state table in `docs/world/WORLD-PLAN.md` section 7
  on every merge.
