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
  <command>` (builds, tests and offline renders share 3 slots; `--exclusive` only for the game client and runs whose timings
  matter, since a pending exclusive job holds every new job back;
  every job and its children capped at 16 GB, `--mem-gb N`). Start it with the Bash tool's `run_in_background` and
  keep working. It writes a timestamped log per job (`locks/logs/`) and a ledger (`locks/jobs.jsonl`: wait, run, rc,
  peak memory, phase times); `py -3 E:/NirnCraft-scratch/locks/watch.py --report` shows where time went.
* **Every test and long job is traceable**: it prints `TRACE begin <phase>` / `TRACE end <phase>` around each phase,
  `TRACE progress <phase> <i>/<n>` at least every 30 s inside long loops, and `TRACE done <name> <secs>s` for steps it
  times itself (one per suite / test / feature type, with its result in the line). Gradle tasks and slangc compiles are
  traced automatically. A job silent for 5 minutes is flagged to the orchestrator.
* **GPU kernel rules (learned 2026-10-21):** every kernel builds at `-O3`; `[noinline]` on big helpers called from
  several places (else spirv-opt copies them into every call site: 30 GB / 14 min compiles, multi-minute driver JITs);
  no inout values forwarded through calls; **never keep mutable state (RNG, flags, builders) in a thread-private
  `static` read or written by a `[noinline]` function**: NVIDIA and slangc treat such calls as pure and merge identical
  calls (two `nextInt(3)` returned the same value). Keep such state in a storage buffer or pass it explicitly, and
  `[ForceInline]` the RNG chain. Double literals need the `l` suffix. Time each pipeline's creation with a `pipeline:`
  TRACE line.
* **No static shared across a real call (learned 2026-10-25).** NVIDIA honours `[noinline]` (SPIR-V DontInline), so a
  thread-private `static` that two such functions both touch becomes a Private global in local memory: every access a
  trip to memory, and its value across the call is the compiler's guess. The far kernel ran 10x the Oct 20 one's time at
  the same steps, and some builds doubled the steps (the picture depended on the build). Each `[noinline]` root owns
  its state (a request struct in, a result struct out, its own copies of the statics, set at its entry, with no
  initializers); shared helpers take state as parameters. Check: `spirv-dis <kernel>.spv | grep -c 'OpVariable.*Private'`
  is 0 for every kernel. Offline timings of a new kernel are valid only once its driver compile is cached and the grid
  is settled (atlas_render's warm-up: "settled in ... N frames" with N > 0 and nothing pending).
  A kernel decision rests on the paired ratio, not on ms: gen_bake_tool views with `ATLAS_AB` (side B every other frame
  in one process; `abRatio` and its p10/p90 on the line). The GPU's clocks step between blocks of frames, so absolute ms
  move 20-40 % between identical runs; the ratio holds within ~1 %. Timed frames never read the outputs back.
* **Reviewers:** the orchestrator puts read-only reviewer agents on new GPU code and on any stuck debugging; their
  findings come to the owning agent through the orchestrator.
* **Lessons that cost hours (2026-10-21), don't repeat:**
  - A build or tool setting change gets one end-to-end run of the affected path (e.g. one Vulkan context) before it
    is rolled out (`-O0` shaders looked fine, then broke every GPU test).
  - Slow compiles, multi-minute driver JITs or GPU memory blow-ups mean inlining or bloat in the kernel: measure SPIR-V
    size and per-pipeline JIT time and fix the code; don't tune optimisation levels.
  - Every GPU submission stays far below the Windows 2 s watchdog (one pass / tile per submission; warn above 500 ms);
    every loop in a kernel is bounded (an unbounded trace or `while` is a device-lost crash for players).
  - Exactness ports: read the Java reference line by line (word layouts, RNG call order, `Math` vs `StrictMath`) and
    get a reviewer early; don't iterate blind on match percentages.
  - Gradle test JVMs get an explicit heap (default = 1/4 of RAM = the job cap); never pass `--log` to build_263
    under heavy.py (it hides output from the trace); pass exes or `py -3` / `powershell`, not `bash` (can be WSL).
  - Cache expensive references (Java dumps, baselines) on E: keyed by inputs; never recompute them every run.
  - Stay on the critical path: no performance measurement, baselines or tuning before the feature works on the GPU.
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
* No mixins or access transformers by default: NeoForge events and public API; screens replaced via
  `ScreenEvent.Opening`.
  * **Narrow exception (owner, 2026-10-25).** A mixin (or an access transformer) is allowed only when all of these hold:
    1. no public NeoForge route exists;
    2. the win is large and on the north-star path (e.g. turning vanilla's chunk meshing and drawing off for stage 5,
       or later simulation hooks; see ATLAS-RENDER-INVENTORY.md §4);
    3. it's small and targeted (one stable method), isolated in one package (`com.nirncraft.mixin`);
    4. it has a switch that turns it off, with a fallback to the public path;
    5. it's re-checked on every Minecraft update.
  * Never for convenience. Propose each one in your report with its win and risk; the orchestrator approves it.
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

* **Never copy a core into the instance's `atlas-dev` folder by hand (2026-10-24).** The game loads
  `<game>/atlas-dev/atlas_core.dll` in preference to the jar's core. The autotest instance is shared by every
  worktree, so a hand-copied core makes every other agent's live test run your code. The harness puts the run's own
  shipped core there before each live run (`game.ensure_current`). For a dev run outside the harness, use
  `-Dnirncraft.atlas.devLibrary=<your build>` instead.
* **The same for `<game>/atlas-shaders` (2026-10-25).** The core draws with any `*.spv` there in place of its own
  kernels (shader_watch `--game`). A folder left from 10-20 made every gallery run measure that day's far kernel (no
  bricks). The harness moves it aside before each live run (`game.clear_dev_shaders`); never leave one there.

* **Three loops, fastest first (owner, 2026-10-25).** Most iterations must not launch the game.
  * **Inner (seconds):** check a change offline on a saved real scene: atlas_render with `watch=1` shader reload, a scene
    exported from the game (harness `atlaslab-export` / twins) or made by gen_bake_tool. Kernels, look, coarse levels
    and materials are iterated here.
  * **Middle (~1 min):** one small live probe (`harness.py probe` or a single `--class` gallery batch) on a warm cache,
    only for what offline can't show.
  * **Outer (milestones only):** the route, the full gallery sample and the relaunch test, once per finished step that is
    ready to merge.

  A live run in the inner loop, or a route per change, is a process bug: say why if you need one.

  **Enforced by `heavy.py` (2026-10-25):** a live-labelled job must declare `--loop middle|outer --why "<one line>"`
  before the `--`, or it is refused (exit 3, `[gate] DENIED`); middle runs also face hard checks and a Haiku judge.
  There is no override: if a denial is wrong, run `heavy.py --gate-escalate "<why>" "<label>"`, keep working offline,
  and wait for the orchestrator's `--gate-resolve` (allow = one run of that label). Example live call:
  `py -3 E:/NirnCraft-scratch/locks/heavy.py --exclusive --loop outer --why "finished bake slicing: confirm route" "bake2 game: route" -- py -3 tools/harness/harness.py run atlas-route`

  **Watching your own jobs (no Haiku needed):** `heavy.py --follow <label-substring>` (run it in the background or under
  a Monitor) streams your jobs' events: queued, position, started, phase, counters, `NO PROGRESS`, finished rc.
  `heavy.py --status [label-substring]` is the one-shot snapshot: your place in the queue, each job ahead (phase, last
  output and last progress, process alive and CPU). Progress decides, never time alone: output, a phase or counter
  change, CPU in the job's process tree, or files written all count, so a long job that is moving is never "stuck".
  Only a stall (no progress for 10 min by default, `gate-rules.json`) reaches Haiku. If you think a job is stuck, run
  `heavy.py --stuck "<what I wait on, why it looks stuck>" [label-substring]`: Haiku answers normal (with an ETA and a
  "re-check at HH:MM"), stuck (diagnosis) or unsure; the last two escalate to the orchestrator automatically. At the
  ETA run `heavy.py --recheck` to verify; a second "normal" with no progress since counts as unsure. Haiku only
  reports; it never kills or changes a job.

* **Process feedback (2026-10-25).** When a rule, the gate, the harness or any step of the process slows you down or
  makes no sense, say so: `py -3 E:/NirnCraft-scratch/locks/feedback.py "<what, and your suggestion>"`. The orchestrator
  sees it within about 5 minutes and changes the process. Keep working meanwhile.

* **Own your jobs and your waits (2026-10-25).** The agents and the Haiku gatekeeper handle these, not the orchestrator.
  * Any job that runs more than a few minutes prints its progress at least every 2 minutes ("tiles 120/600",
    "phase 3/5"), so a waiting agent and Haiku see real movement and an ETA, not just CPU use.
  * Follow your own jobs with `heavy.py --follow <label>`.
  * When you wait more than ~15 minutes, run `heavy.py --stuck "<what I wait for>"`: Haiku explains it (normal, with an
    ETA) or escalates it.
  * The orchestrator only sees what Haiku can't resolve.

* **Measure the ceiling before building (owner, 2026-10-25).** Before building any optimisation, switch off the work it
  would remove (an ablation on the saved scene or a bench: minutes) and measure the time saved. That is the most it can
  ever gain.
  * Build only if the ceiling is worth it; order the steps by ceiling.
  * Ideas get a round-1 estimate first (minutes, from existing data); only the most valuable get real measurement.
  * New ideas get one line in the notes, not chased.

* **Park, don't drop (2026-10-25).** Work that's deferred stays safe: commit it on its own branch (or a WIP commit on
  yours), never merged into atlas-render until its stage comes, and add a row to ATLAS-PLAN §4b "Parked work" with
  where it is and what to re-check when it's resumed.

* **No [noinline] call that passes structs by value on the far kernel's path (2026-10-25).** The driver moves such
  structs through shared memory (16640 B a group instead of 7936), which takes L1 from the walk and slows every pixel,
  even with the feature switched off. Check "Shared Memory Size" with `ATLAS_PIPE_STATS=1` on any far-kernel change:
  7936 B is the good value.
  A kernel change that moves its numbers on purpose updates its rows in native/shaders/kernel_budget.json in the same
  commit with `py -3 native/tools/budget_set.py <module>...` (spv) or `--pipe <log> "<pipeline>"` (driver stats),
  and says why in the message: the budget is a ratchet, never edited by hand.
* **A new or rewritten kernel's speed counts only with result agreement (2026-10-26).** Before claiming a time, compare
  its hits against the reference kernel on a dry, fully resident scene: the hit/miss mask and hit t, on the 3 views,
  with the same step caps, dispatch size and amortisation. Report the mismatch % beside the ms. A kernel that misses,
  skips or stops early is fast for the wrong reason (the lean trace stage's first cut: re-floor skips, water stops,
  a lower step cap, gaps as sky). A read-only reviewer checks every new kernel against the reference before its time
  is trusted.

* **Adding content safely (2026-10-25).** Three layers, each with its guard:
  1. **Content** (structures, biomes, trees, settlements, new blocks) is data and generator rules. The render kernel
     sees only voxels and materials, and a new block's bricks come from its model, so content never changes the
     render kernel's cost.
     - Guards: GPU-vs-Java exactness; the generator's job limits (≤ 4-8 ms a dispatch, the pacer, recorder verdicts);
       the gallery for looks; the cache fingerprint per generator stage (only affected data rebakes).
  2. **Generator rules** (a new feature shape, a new biome step) change generator kernels. Their trap is one placement
     on one lane for many ms. Guards: dispatch timing, exactness, and the same build-time pipeline-stats guard.
  3. **Rendering features** (entities, volumes, light types, effects) change the render kernels. Guards: the build-time
     pipeline-stats check (shared memory, local memory, registers), the no-struct-[noinline] and no-statics rules, a
     ceiling test first, and a reviewer.
  **Standards that make the right way the default:**
  - **Content is data:** Minecraft-style features compile to GPU programs automatically (AtlasFeatureProgram), and
    settlements are recorded tapes. Add or change content in data; code changes only for a new feature type.
  - **A new feature type** follows the bounded-work template (resumable phases, a per-lane op budget), as the tree
    phases do, so it can't become one long placement. Write the template when the next new type is needed.
  - **The build runs the pipeline-stats guard (fails) and the shader lint (warns, with a fix hint each).**
  - **`harness.py run --changed`** runs exactness, dispatch timing and the gallery for what changed (impact.json).
  - **docs/COMPONENTS.md** lists, per generator or world component, the fast check, the visual tool and the one command. Run only those for what you touched. Fill a gap there only when you work on that component.

* **Merges into atlas-render keep the native suite green (2026-10-26).** After each merge, the orchestrator runs the
  native suite (`harness.py run check-native`, or native/build.ps1) and the kernel-budget check. A failing check
  blocks further merges until it's fixed or listed below with a reason and an owner.
  - A merge that touches only docs or tools nothing builds or runs in the suite rides on the next code merge's
    suite run (so the suite doesn't hold the GPU from a running bench for no signal).
  - Known failures: none (2026-10-26: coarse:2133 fixed in 96f76680, tiers:2264 in 6fca5506).

