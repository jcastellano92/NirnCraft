# World testing workflow

Read `docs/START-HERE.md` and `docs/LIVE-WORLD-PREVIEW.md`. Operate only the disposable
Skycraft-autotest instance; check status and reuse its world. Preserve the owner's normal instance.
Do not run a headless server alongside its integrated server or two drivers against the live queue.

`tools/qa/world_test.py` composes existing audits and real preview controls. Its manifest,
`tools/qa/world-tests.json`, defines fifteen systems and their required regional conditions. `plan`
expands those obligations over all 83 regions. This is a backlog, not completed regional coverage.

```powershell
py -3 tools/qa/world_test.py list
py -3 tools/qa/world_test.py plan
py -3 tools/qa/world_test.py --systems caves,dungeons,fx --regions skyrim/falkreath plan
py -3 tools/qa/world_test.py run --tier fast
py -3 tools/qa/world_test.py --systems magic,ui run --tier runtime
py -3 tools/qa/world_test.py --systems caves,dungeons --regions skyrim/falkreath capture mine-crystal-chamber
py -3 tools/qa/world_test.py --regions skyrim/falkreath measure mine-crystal-chamber --shaders on --frames 600
```

Fast audits run outside Minecraft: data/assets, layout/reach, ecology, world-sheet causality, menus,
shader contracts and controller/camera tests. Each records commands, time, input digest and full logs.
Nonzero exits, emitted errors and missing required summaries fail. `run --resume RUN_DIRECTORY`
reuses only passed results with unchanged input and checker digests. A global audit does not prove
individual regional behavior or appearance because a region was selected.

Runtime checks use authoritative commands in the running game, require their success summaries and
reject failure output. They are contracts, not complete playthroughs. Asynchronous checks need an
observed completion signal; do not guess a sleep. Add new checks to the manifest with their actual
success/failure signals, timeout, systems and scope.

Visual tests follow `docs/VISUAL-TEST-CAMERAS.md`: actual plan/routes, loaded-world scouting, frame
inspection, then approval. Never accept a buried camera. Capture receipts require clear geometry,
successful readiness at that pose, actual shader state and no Night Vision. Both shader modes start
as `awaiting_review`. Open each image before recording concrete observations:

```powershell
py -3 tools/qa/world_test.py review RUN_DIRECTORY CASE_ID --status passed --notes "Observed behavior and limits"
py -3 tools/qa/world_test.py report RUN_DIRECTORY
```

Use `--status rejected` for bad composition/appearance. Failed captures require reruns. Static frames
cannot prove smooth motion, NPC routes, spell damage, fog displacement or exposure adaptation; retain
sequences and gameplay observations. Synthetic `fx` actions test rendering, not enemy/staff packets.

Camera identity combines seed/dimension, runtime bake inputs and a generated geometry source/data
digest. Dungeon/terrain/settlement/road/scatter/landmark changes invalidate old poses; client lighting
changes preserve them. Regenerate affected disposable chunks, revisit routes, scout and review again.
The policy is conservative and global. Extend `generationSources` / `generationData` in
`mod/build.gradle` whenever adding a generator package or data directory.

Performance samples contain 120..3600 real frames without PNG encoding: raw intervals, render CPU
work, server tick average, median/p95/p99/max and >50ms stalls. Record GPU, resolution, view distance,
cap/VSync, focus, pose, shader state and time/weather. `measured` is separate from acceptance. Compare
matching settings/conditions and warm runs; cap/VSync/background pacing limits throughput conclusions.
Atmosphere GPU diagnostics time the whole frame, not isolated clouds. Keep screenshots and compilation
outside measurement windows.

At milestones run the complete build, affected full road/corridor gates and verified bakes when inputs
change. Retain failures and missing content; never lower a floor to get green. Each archive must state
its tested scope and include evidence and remaining gaps. Continue test, inspect, plan, enhance, retest.

Real gameplay scenarios live in `tools/qa/scenarios/`. Run one or all variants:

```powershell
py -3 tools/qa/world_test.py scenario tools/qa/scenarios/cave-spells.json --variant flames --shaders both
```

A scenario names one actual region, an approved generation-aware camera, plan/route, conditions,
setup commands, action steps, observed completion signals, assertions, captures and cleanup.
`waitFor` polls an authoritative JSON signal and stops immediately when it is satisfied. Timing a
charge/projectile flight is legitimate; an unconditional sleep after readiness is not.
Runtime assertions and visual acceptance remain separate. Open every retained image before review.
The named disposable actors use real server entities; stationary targets explicitly have AI disabled.
`damageFromObserver` prevents environmental damage being mistaken for the player's spell hit.
`cast start/stop` sends the normal Cast packet; `fx` remains a synthetic renderer-only exercise.
`use down/up` uses the ordinary client game-mode use-item packet and holds/releases the use key for staffs and items. Both have a200-tick safety release.
Staff streaming range is8 blocks; the staff fixture target is6 blocks away, while the hand-spell fixture is9 blocks away.
Use `skycraft:npc` for tracked NPC fixtures: vanilla villagers are intentionally converted by the mod.
The scenario runner releases inputs and runs named cleanup even on failure. Crashed sessions may
retain entities tagged `skycraft_qa_fixture`; inspect that disposable save before resuming fixtures.

`skycraft-dev route TAG X Y Z TARGET_X TARGET_Y TARGET_Z` uses the engine's real villager navigation,
with loaded search bounds and no spawned probe entity. It records nodes, reachability, seed, dimension,
bake inputs and duration in `world-preview/routes/`. This proves bounded path existence with opening
doors allowed; it does not prove a live NPC completed a schedule or door interaction. Test both.
`skycraft-dev save` provides an observed save completion in integrated previews, where vanilla
`save-all` is not registered.

Foreign-region flow audits consume `geography.py refresh`, a headless Java survey of the actual map
at128-block spacing. Its input/data digest invalidates on map changes. Raster geography stays exact;
foreign area is explicitly sampled. Procedural Endless Lands are dormant under the ocean-loop policy.
The active QA registry has 117 buckets:83 authored regions, one outer-ocean travel bucket,32 realm roles
and jail. The outer-ocean bucket requires actual distant open-sea coordinates; a coast screenshot cannot
stand in for it. Validate X/Z seams, diagonal crossings, boats/passengers and destination readiness separately.
Missing/stale geography produces unavailable metrics, never an invented0.01km2 land area.


`skycraft-dev scenario move TAG X Y Z` starts actual mob navigation for a named actor (loaded goal within64m).
`cave-navigation.json` waits for observed arrival; this supplements the read-only path probe without pretending
that a short walkway covers schedules, doors or whole dungeons. Explicit command rejection aborts a scenario
immediately. An assertion deadline is only for an active operation with no completion signal yet.

```powershell
py -3 tools/qa/world_test.py scenario tools/qa/scenarios/cave-staves.json --shaders both
py -3 tools/qa/world_test.py scenario tools/qa/scenarios/cave-navigation.json --shaders both
py -3 tools/qa/world_test.py review RUN CASE --status passed --conditions vast_cavern --notes "Actual visible evidence"
py -3 tools/qa/world_test.py coverage
py -3 tools/qa/world_test.py --systems caves,fx --regions skyrim coverage
```

Coverage joins immutable run evidence by actual region/system/explicit condition. Current visual samples require
matching core, world generation, shader contents/options and reviewed image hash; old images remain stale evidence.
Unlabelled captures and global contracts never spread to selected regions. Shader-on/off samples are not automatic
condition acceptance, especially where different variants were tested. All acceptance remains `not_signed_off`
until a reviewer verifies the complete behavior/performance/route obligations. The active identity cannot prove old
chunks were regenerated; retain regeneration receipts for affected areas.

Storage experiments are opt-in. `tools/dev/build_fast.ps1` puts build outputs/caches on the measured NVMe;
`py -3 tools/dev/benchmark_world.py --runs 2` creates stopped-world copies on F and E, alternates order,
measures full corridor regeneration and preserves failed road gates. Close the disposable preview first. No user
installation/save is moved. Runtime roots are restricted to owned QA folders and only one server runs at a time.


For exact headless-world visual review, first stop the server and disposable client, then:

```powershell
py -3 tools/dev/preview_world.py import E:/Skycraft-scratch/world-benchmark/RUN/world qa_road_review
py -3 tools/dev/live_world.py launch
# Inspect real plan/route, scout, approve camera for this actual seed and generation, then capture.
py -3 tools/dev/live_world.py close
py -3 tools/dev/preview_world.py select review_world
py -3 tools/dev/live_world.py launch
```

Import creates a new copy, refuses overwriting, checks owned source roots and a stopped server, and records its
snapshot digest. It only changes the disposable instance's load target, with config backup. Existing cave cameras
from another seed are correctly stale in this road world. Never change their seed fingerprint to bypass it.
The same core's remapped/runtime bake bytecode hashes can differ from mapped headless bakes; retain the original
world snapshot and runtime identity and verify actual geometry. Do not regenerate away a bug before reproducing it.

Navigation fixtures use native Skycraft NPCs with active physics, wait for actual `onGround`, and repeatedly
request the goal only while arrival remains unobserved. This is controlled locomotion, not autonomous schedules.
MINE room3's actual walking height is88; the camera at89 is clear and aims at body height89. Target actors must
stand at88, not float one block above the floor. The staff target is6m away; the hand-spell target9m away.
