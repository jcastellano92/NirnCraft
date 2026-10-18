# Skycraft agent rules

Rules every agent/workstream follows. Reference data from the owner's game installs lives in `.reference/` (gitignored, facts only).
New sessions start at `docs/START-HERE.md`; the roadmap and its state table are in `docs/world/WORLD-PLAN.md`, the
phase prompts in `docs/prompts/`. The world-building rules at the end of this file apply to every workstream.

## Phase 1 rules (still apply)

You are fixing review findings in Skycraft, a Skyrim total-conversion for Minecraft 1.20.1 Forge
(compiled against 47.3.0, the pack runs 47.4.10), official Mojang mappings, Java 17.
You work in your OWN git worktree (your current working directory). Paths below are relative to the repo root.

## Build
* From `<your worktree>/mod` run `./gradlew.bat compileJava --console=plain "-Dorg.gradle.jvmargs=-Xmx2G"`
  (PowerShell) or `./gradlew compileJava --console=plain -Dorg.gradle.jvmargs=-Xmx2G` (bash). Trust the
  `BUILD SUCCESSFUL` / `BUILD FAILED` line. Six workstreams share one PC: compile after a chunk of work, not
  after each edit. You MUST finish with `build` passing.
* Real 1.20.1 signatures: `javap -cp <jar> <class>` with javap at `C:\Program Files\Java\jdk-17\bin\javap.exe`
  and the jar
  `C:\Users\Jeremy\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.3.0_mapped_official_1.20.1\forge-1.20.1-47.3.0_mapped_official_1.20.1.jar`.
  Third-party mod jars are cached in `F:\Projects\Personal\Skycraft\Skycraft\dist\.cache\` (read-only).

## Code rules
* No mixins, no access transformers. Forge events and public API; screens replaced via `ScreenEvent.Opening`.
* NEVER reference client classes (`Minecraft`, `Screen` and subclasses, `KeyMapping`, renderers, `LocalPlayer`)
  from common code, not even behind an `isClientSide` check: the bytecode verifier loads them and the dedicated
  server crashes. Hand off via `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SomeClientClass.method(...))`
  where `SomeClientClass` lives in a `client` package.
* Worldgen code never reads/writes outside the region being generated, never touches `ServerLevel`-only services
  (POI manager, entity lookups) off the server thread.
* Server is authoritative. Validate every client packet (distance, menu type, ownership, value bounds).
* Multiplayer must work both on an Essential-hosted integrated server and on a dedicated server.
* Player data: `SkyData.get(player).module("<name>")`.
* Lang: only your module's file `mod/src/main/lang/skycraft/<module>.json` (merged at build time).
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
* Confirm the final `./gradlew build` result.
# Skycraft Phase 2+ rules (every workstream reads this)

Read these first:
* `the Phase 1 rules section above`.
  The build, code, git and report rules there all still apply. One exception: the "don't touch Holds, Carriages
  or Houses" rule is lifted for the workstreams that now own them.
* `docs/TAMRIEL.md` in your worktree: the world plan and the Java contracts. The contract signatures in
  `com.skycraft.tamriel` (`TamrielMap`, `MapSample`, `Region`, `Regions`, `Place`, `PlaceType`, `Places`) are
  fixed. You may add methods, but don't change or remove existing ones.

## The owner's direction
* Skycraft replaces ALL third-party content mods with its own systems: terrain, biomes, structures, villages,
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
  * The game install itself is `E:\Steam\steamapps\common\Skyrim Special Edition` (read-only).
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
* Vertical: compress heights so the Throat of the World peaks near y 370 (world top is y 383; sea level 63).

## Test in game where possible
* `./gradlew runServer` (with `--nogui`, from `mod/`) generates a world with only Skycraft loaded. Use it to
  smoke-test worldgen: no crashes, and sensible timing.
* Accept the EULA in `mod/run/eula.txt`. Stop the server cleanly with the `stop` command on stdin, or kill it
  after a timeout.
* World creation can be slow. Delete `mod/run/world` before each test.
* `tools/autotest.ps1` can launch the client and capture screenshots. Read it before using it.
* Only one Gradle run per worktree at a time.

## Fast loop (every workstream; docs/FINISH-PLAN.md section 2)

* **Iterate with quick checks; run the full gate once at the end of a phase.** Start with Tier 0 pure-Java JUnit 5
  tests (`tools\qa\qa.ps1 -Unit`, < 5 s, no server) for worldplan/map/bake logic. While developing against the
  persistent dev server, use Tier 1 (`tools\dev\devserver.ps1 hotswap` for < 3 s JDWP method-body hot-swap, and
  `/skycraft-dev reload <scenes|places|cityplans|quests|palettes|all>` for < 1 s in-JVM JSON reload),
  `tools\dev\devserver.ps1 run "cmd|cmd" -Wait`, `tools\qa\quick_corridor.ps1 <a>:<b>` (one tile, ~15 s) and
  `/skycraft-dev regen` for a bounded area. Compile (`compileJava`) after a chunk of work, as above.
* **Full gates only at a checkpoint:** `tools\qa\full_corridors.ps1` (the 4-corridor road gate) when the road work of
  the phase is done, and `tools\qa\qa.ps1 -Server -Rebake` before merging (one boot, re-bakes in the same run). Do not
  repeat a full gate after each small edit and do not weaken one: a gate that only the slow path can run is run once,
  not skipped.
* The dev server hot-swaps method-body edits (`devserver.ps1 hotswap`, ~2 s) and only restarts (~40 s) when class
  schemas or non-reloadable resources changed; it uses its own world (`mod\run\devworld`), port and working directory,
  so it never collides with `qa.ps1`/`bake.ps1`. Only ONE dev server per machine (`devserver.ps1` refuses a second;
  servers get `-Xmx4G`): stop it (`devserver.ps1 stop`) when you finish and before another agent needs one; do not
  leave it running between sessions.
* Release builds stay strict: `tools\build_release.ps1` bakes explicitly and runs `qa.ps1 -Server -Bake`.

## World-building rules (every workstream; docs/world/DESIGN-PRINCIPLES.md)

* **World building first.** Everything (terrain, architecture, palette, a prop in a scene, an NPC's job and line, a
  quest, a book, a dungeon's enemies, music, weather) exists to build the world. Each element must express its
  place's history, culture, conflict, economy, faith and ecology, and agree with every other element there. When two
  rules conflict, the one that makes the place more believable wins. A desert town with a pine-log mill, a bandit camp
  with no road to rob or a mine with no ore in the rock is a bug.
* **Cite the world-model sheet.** Every region and province has an 11-layer causal sheet (L1 geology, L2 climate, L3
  water, L4 ecology and micro-biomes, L5 economy, L6 settlement pattern, L7 society, L8 culture and belief, L9 politics,
  L10 the hidden and underground, L11 history), as data in `mod/src/main/resources/skycraft/tamriel/world/<province>.json`
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
