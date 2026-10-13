# Skycraft agent rules

Rules every agent/workstream follows. Reference data from the owner's game installs lives in `.reference/` (gitignored, facts only).

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
* Do NOT commit, push, merge, rebase, reset or switch branches. Leave all changes uncommitted in your worktree.
  New files are fine (leave them untracked or `git add -N`).

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

* **Iterate with quick checks; run the full gate once at the end of a phase.** While developing, use the
  persistent dev server, never a fresh boot per check: `tools\dev\devserver.ps1 run "cmd|cmd" -Wait`,
  `tools\qa\quick_corridor.ps1 <a>:<b>` (one tile, ~15 s) and `/skycraft-dev regen` for a bounded area. Compile
  (`compileJava`) after a chunk of work, as above.
* **Full gates only at a checkpoint:** `tools\qa\full_corridors.ps1` (the 4-corridor road gate) when the road work of
  the phase is done, and `tools\qa\qa.ps1 -Server -Rebake` before merging (one boot, re-bakes in the same run). Do not
  repeat a full gate after each small edit and do not weaken one: a gate that only the slow path can run is run once,
  not skipped.
* The dev server restarts itself when `mod\src\main` changed (about 40 s); it uses its own world (`mod\run\devworld`),
  port and working directory, so it never collides with `qa.ps1`/`bake.ps1`. Only ONE dev server per machine
  (`devserver.ps1` refuses a second; servers get `-Xmx4G`): stop it (`devserver.ps1 stop`) when you finish and before
  another agent needs one; do not leave it running between sessions.
* Release builds stay strict: `tools\build_release.ps1` bakes explicitly and runs `qa.ps1 -Server -Bake`.
