# Live Minecraft world preview

`tools/dev/live_world.py` is the low-level bridge to the disposable **NirnCraft-26.3-autotest** Prism instance. The
test harness ([tools/harness/README.md](../tools/harness/README.md)) drives it for every live test: it launches the
client, plans cameras offline on the seeded terrain, captures before/after results and closes the client at the end
of a run. Use the harness for tests; read this page when you need the bridge directly. Preparation and launch
boundaries (Java 25, exact instance, core-only diagnostic) are in [NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md).

`camera X Y Z TARGET_X TARGET_Y TARGET_Z` checks loaded collision geometry and refuses a pose inside terrain, trees or
buildings; captures include JSON receipts. Run everything from the repository root. Preserve the owner's own
**NirnCraft** instance and its saves.

## Check before launching anything

```powershell
py -3 tools/dev/live_world.py status
```

This reports the exact instance path, Java PID, game window title/open/responding state, client heartbeat age,
loaded-world flag, current screen, queued capture actions, last action/error, server heartbeat and pending server jobs.
The process alone does not prove the world is ready. Require `serverReady: true` for world jobs and
`client.fresh: true` for capture/shader controls. A stale heartbeat can mean a loading screen, paused game, blocked
generation or a closed client. Inspect `logs/latest.log` before restarting. Do not print Java command lines: launcher
arguments contain authentication information.

The client may already be running. Reuse it. Never launch a second game or a separate headless server just to edit
the same preview. While a command waits, give the owner progress updates; do not silently wait for minutes.

Mutating `live_world.py` calls and imported `live_actions`/`submit` calls share a nonblocking OS lock at
`<Prism data directory>/.nirncraft-live.lock`, across both allowlisted native instances. The lock covers readiness
and busy checks through action/job acknowledgement, so competing cooperative callers cannot overwrite a live
batch. Contention reports only a sanitized tool label and PID; `status`, `storage` and `profile` remain available.
The file persists, but the OS releases ownership after exit, process death or reboot. Never delete it or force a
PID-based unlock. Existing heartbeat/queue checks still apply after a timeout or crash. Raw file writers and
launchers do not participate; a launch call's lock ends after the request, rather than owning the client lifetime.

## Lifecycle

```powershell
py -3 tools/dev/live_world.py close
py -3 tools/dev/live_world.py enable
py -3 tools/dev/live_world.py launch
py -3 tools/dev/live_world.py status
```

`close` requests the live controller's clean shutdown and waits for that instance's Java process to exit. It does not
kill Java processes. It deliberately cancels that preview's pending capture sequence. On the previous controller it
can bootstrap shutdown only if its log confirms `Autotest live: ready`. Otherwise close the actual game window.
`enable` changes only the disposable instance's launch flags and refuses while it runs. It adds
`-Dnirncraft.dev=world-preview -Dnirncraft.preview.resources=world-preview/resources`. These are development flags;
do not ship them. The instance must already have the existing autotest/live/load-world flags (use
`tools/qa/prism_autotest.ps1 -Live` to prepare a new disposable instance). `launch` clears old capture commands,
requests Prism launch and returns; poll status for readiness. It refuses to create a duplicate Java process.

Prism is launched as a hidden helper; the Minecraft game window remains available to watch. No window focus is
required for file controls. The live controller releases mouse/keyboard input during scripts so desktop activity
does not spoil fixed camera poses. Always log the actual pose and Night Vision state with captures.

Close the client after each test batch; never leave it open idle. Never run the action queue while the owner plays
in the instance: it holds the player (use the passive profiler below). One heavy job at a time: no native build,
Gradle or offline render beside a live client (AGENT-RULES.md, resources).

## Infinite ATLAS controls

Launch flags go in the instance's `.minecraft/nirncraft-harness-flags.properties` (read at start, one
`key=value` system property a line): `nirncraft.atlas.unified=true` (atlas traces every pixel),
`nirncraft.atlas.clientGen=true` (the GPU generator), `nirncraft.atlas.profile=true` (the passive profiler: atlas-info
with the player's position every 2 s into `.minecraft/atlas-profile.log`, safe while someone plays),
`nirncraft.comet=now`. Actions for the queue: `atlas-info` (frame, glRender phases, inside, feeds, clientGen, voxgrid
to the log), `atlas-unified on|off`, `atlas-near on|off`, `far-timeline start` / `far-timeline dump NAME`,
`capture-inside TAG` (clip tests), `camera-travel TAG TICKS X Y Z LX LY LZ` (below). A new core: `install-core JAR`
with the client closed, or a dll dropped in `.minecraft/atlas-dev/` (loaded instead at the next start). The full list
and the state of the renderer: [ATLAS-STATUS.md](ATLAS-STATUS.md).

## Live JSON and world geometry

```powershell
py -3 tools/dev/live_world.py reload palettes
py -3 tools/dev/live_world.py reload cityplans
py -3 tools/dev/live_world.py reload scenes
# other targets: places, quests, all
py -3 tools/dev/live_world.py regen -400 320 -273 447
```

Reload copies the selected source JSON into an immutable snapshot beneath `.minecraft/world-preview/resources/`,
validates JSON and hashes, atomically switches `current.json`, then executes the existing `/nirncraft-dev reload`
through its serialized server inbox. The packaged client reads that snapshot instead of requiring a rebuilt jar.
The server checks every file/hash before activating the complete resource map. Other target groups retain their
last staged snapshot within the same world session. A new boot starts a fresh snapshot so unrelated old overrides
cannot silently activate without their caches being reloaded. A deleted override falls back to the packaged file. JSON syntax/hashes are checked; this is
not a guarantee that every content record is semantically valid. Inspect reload feedback and warnings. The override
map resets when the world closes; staging persists but must be explicitly activated again with reload.

Reloading data does **not** repaint saved chunks. `regen` regenerates the specified block rectangle using existing
`DevRegen`; this intentionally replaces generated content in that disposable save. It parks all preview players
2048 blocks away so their chunk tickets can unload the area, regenerates and awaits the actual unload/generation/paving completion. The wrapper
limits each request to 256 chunks. Stay at least one chunk inside the rectangle when judging results (decoration and
road paving need neighbours). It leaves the player parked: return with a saved camera/dungeon-view command after
the job completes. Use `nirncraft-dev release` when finished to release regeneration tickets. Saved quest/spawn flags
are preserved; regeneration is not a new-game simulation. Do not run this on the owner's save.

City-plan/place changes can require new road/street bakes; those caches are explicitly reported as requiring a
rebake/restart. Raster maps, hydrology/region/peak tables, datapack registries and class schema changes are outside
live JSON reload. Never present a reload as applying those changes.

## Server jobs and actual dungeon views

Create a text file with one server command per line, then run:

```powershell
py -3 tools/dev/live_world.py commands dist/weather-review/dungeons/preview-commands.txt
```

Example file:

```text
execute as @p at @p run nirncraft-dungeon-qa view shimmermist_cave hall
@wait 120
effect clear @p minecraft:night_vision
nirncraft-dev status
```

Existing dungeon selectors include `entrance`, `mouth` (inside looking out), `hall`, `descent`, `boss`, `room N`,
and `feature NAME`. They select real planned dungeon geometry; they do not repaint it. The QA view command adds
60 seconds of Night Vision, so clear it before judging lighting. Feature searches paint in memory and may take
seconds; wait for completion. `nirncraft-dungeon-qa air-survey <ids...>` writes real room/corridor bounds and feature
markers to `dungeon_qa/atmosphere-scenes.json`. A `surface_skylight` marker means an actual surface opening;
`light_shaft` can be a sealed glowing set piece and does not prove real sunlight.

The server reads `.minecraft/world-preview/inbox/*.cmd` sequentially and writes per-job `.out` and `.done` files in
`outbox/`. Supported directives: `@wait TICKS`, `@idle`, `@until REGEX`, `@timeout SECONDS`. Long regeneration is
automatically awaited. Do not overwrite a pending job; inspect status and its output. Every zero-result server
command marks its job failed. Some conditional commands intentionally return zero; use a positive-result assertion
when testing a condition. A successful job is not a visual-quality pass.

## Shaders and screenshots

`camera-travel TAG TICKS END_X END_Y END_Z LOOK_X LOOK_Y LOOK_Z` moves continuously from a settled
starting pose through loaded, collision-checked positions. It captures every ten movement steps and writes
`build/autotest-travel/TAG.json`. It does not stop to settle fog between steps. Failures cancel the route;
partial images are diagnostic. This visual sequence is not a frame-time benchmark because it takes screenshots.
`measure TAG FRAMES` samples 120..3600 real frames without screenshots and writes raw timing/context JSON
under `build/autotest-performance/`. Use `ui map|journal|inventory|magic|skills|hub|close` and `capture-ui TAG`
for actual in-world menu frames without closing the screen before capture.

For code updates, cleanly close, then `install-core PATH_TO_BUILT_JAR`, then launch. The installer backs up
the existing disposable jar. It verifies the installed core against its runtime receipt and verifies all four
bake headers against the bundle manifest before preserving bakes. A stale installed bundle can be replaced
by a verified new bundle. It refuses when neither bundle is valid. It never edits the owner's instance.

To prepare a rebuilt core while the preview is still running, use `stage-core BUILT_JAR RUNTIME_BAKE_FOLDER NEW_JAR`.
The folder is normally the disposable game's `nirncraft-bake` directory after `/nirncraft-bake refresh` completes.
Staging requires identical bake-validator bytecode and packaged bake inputs in the old/new cores, matching runtime
core identity, and a complete matching bundle. It writes a new jar and receipt without changing the running game.
Then cleanly close, install the staged jar, verify the installed SHA256 against its receipt, and launch. If inputs
changed, produce and verify matching bakes first; staging must not bypass that guard. Check every command's exit
status before proceeding: PowerShell can continue after a failed Python command unless explicitly checked.

```powershell
py -3 tools/dev/live_world.py capture torch_hall
py -3 tools/dev/live_world.py actions dist/weather-review/dungeons/capture-actions.txt
```

Shader-pack edits: `py -3 tools/harness/harness.py watch` rebuilds the InfiniteATLAS zip (glslang-checked) and reloads
Iris in the running client, with before|after sheets; atlas_core's kernels: `py -3 tools/dev/shader_watch.py --game`.
The old `live_world.py shader` command (which patched Complementary Reimagined) is retired and only prints that
pointer. Check `latest.log` for shader compile errors and inspect actual frames after each material change.

The existing `.minecraft/nirncraft-live.txt` accepts `reload`, `shaders on|off`, `command COMMAND`,
`visit X Z HEIGHT_ABOVE_SURFACE YAW PITCH WEATHER`, `front X Z RADIUS WEATHER [VX VZ]`, `wait TICKS`, `capture TAG`,
`settle TIMEOUT_TICKS`, `flash cg|c2c|ic`, `fx fire|frost|shock|holy|push`, and `stop`. Editing that file **replaces** its pending queue.
Do not have two writers. Captures are `.minecraft/build/autotest-screenshots/TAG.png`; encoding may finish after the
capture log. `nirncraft-live-status.json` reports acknowledgement, queue and last error. An acknowledged Iris reload
alone does not prove that GLSL compiled successfully. Read the game log and visually inspect.

`fx` triggers existing client FX with test sources; it is not proof that an enemy AI or a staff emitted the expected
network packet. Record staged actors/props separately. Actual enemy/staff integration needs its own in-game check.

`command` now waits for the server's completion result before advancing and cancels the remaining sequence on a
zero result, exception or timeout. Use `settle 1200` after a teleport/view instead of `wait 160` or `wait 400`.
The number is an upper bound: a ready view advances after twenty stable ticks, not after sixty seconds.
Shader reload/toggle also uses readiness instead of a fixed 5–7 second delay. Explicit `wait` remains for deliberately
observing animation, time evolution or comparing exposure adaptation; it is not the default way to detect readiness.
The file inbox is polled every five ticks; the outside controller polls acknowledgements every half-second.
Heartbeat output stays at one second. Those short polling intervals are detection latency, not mandatory test sleeps.

## Storage and performance decisions

```powershell
py -3 tools/dev/live_world.py storage
py -3 tools/dev/live_world.py profile
```

`storage` reports the drives behind the build, cache and preview folders (read-only). The optional
`tools/dev/build_fast.ps1` puts build outputs and caches on a faster scratch drive; pass its jar explicitly to
packaging. Do not run simultaneous Gradles.

`profile` extracts actual launch/world-load durations, loaded/stale bakes and regeneration phase summaries from the
current game log. Preserve it with the run's hardware, settings and screenshots. It does not run a synthetic benchmark.

Read-only drive discovery reports bus type, model, capacity and free space. Windows RAID/virtual/storage-controller
layers can hide the underlying interface, so a non-NVMe bus label is not a universal proof of rotating storage.
Prefer putting active build outputs and preview saves on fast storage when measurements show I/O stalls. Do not move
the owner's saves or install directories automatically.

Separate timings for launch/bootstrap, plan loading, terrain/structures/lighting, chunk mesh upload, atlas far-field
work, shader compilation and frame settling. Report time to first useful view separately from time to fully refined
view. Infinite ATLAS draws the world past the loaded chunks, and every pixel in the unified field
([ATLAS-STATUS.md](ATLAS-STATUS.md)); its readiness (`AtlasClient.farFieldIdle()`, the far timeline) is separate from
the loaded chunks'.

For fast side tools, prefer exporting the real Java planner/painter outputs (already supported by dungeon QA) and
viewing those in Python or another renderer. A second implementation of generation creates a parity burden.
Both intentionally synthetic stress fixtures and real content are useful, but label fixtures clearly and keep
them out of production generation. A good fixture cannot certify that an equivalent real dungeon exists.
Ordinary grand caverns currently cap at about 40–75 blocks across and 15–32 high; Blackreach is a separate tiled
system with a 300-block-wide main footprint and a height cap of 62. Framing and those content limits must be judged
independently of fog/shader quality before claiming the reference scale is achieved.

## Bakes and Java

```powershell
# Produce and verify accepted data with the repository's existing bake/QA pipeline first.
py -3 tools/dev/live_world.py close
py -3 tools/dev/live_world.py upload-bakes mod/src/main/resources/nirncraft/baked
py -3 tools/dev/live_world.py launch
```

Upload requires a complete format-1 bundle (landmass, road, street, scene + manifest). It checks manifest membership,
each compressed payload and each input hash against the last boot's **packaged** input digest, and checks the jar
has not changed since that boot. It refuses mixed/stale/corrupt bundles. It backs up the old core jar and bundle in
`world-preview/bakes/`, builds a replacement jar, then installs it atomically while the client is closed. Bakes take
effect on next launch, and existing terrain still needs bounded regeneration. Runtime receipts are written by the
server at boot, not guessed from source timestamps. This is local preview installation, not a remote publish.

If bake inputs/code changed, rebuild and install a matching core first; the upload must not bypass the hash guard.
New Java classes/fields/method signatures require a restart. Method bodies hot-swap into the running preview client
with `py -3 tools/dev/client_hotswap.py` (its JDWP agent added once with `--install` while the client is closed);
`tools/dev/hotswap.py` does the same for the Gradle dev server. Classes the client has not loaded yet need the rebuild.

When visuals are accepted, bake/verify from the source tree, run required QA/release gates, rebuild the distributable
instance and confirm packaged hashes match what was tested. Never ship preview flags/snapshots or claim an older
`dist` zip contains current edits. Preserve unrelated dirty work; do not commit/push/reset it.

## Verification and current limits

```powershell
py -3 tools/dev/test_live_world.py
```

Use real dungeon frames for entrance adaptation, long torch halls, descending stairs, pools/waterfalls, large
vegetated caverns with genuine ceiling openings, cold floor fog, campfire smoke, glowing enemies, and spell/staff
lighting. Test houses as a clear-interior control, plus shaders on/off. Record actual locations, shader options,
weather/time, Night Vision, frame evidence and failures. File existence or a command completing is not a beauty pass.
Use `settle` after teleporting: it waits for the effective native render disk (currently radius render-distance minus two), empty renderer work, clear camera, current dungeon-air packet/blend and weather grids for twenty stable ticks,
then permits capture. A timeout cancels the sequence and reports an error. It does not certify distant terrain
or a good camera angle; visually reject missing terrain and badly framed captures.
The current atmosphere implementation still has bounded room/light counts, approximate local-light visibility,
and no full fluid/thermal simulation or persistent ice embedding. Do not mark those as completed.

The cavern storm follow-up adds cover to outdoor lightning radiance, atmospheric fog and border fog. Previously
distance fog could blend outdoor sky colour over an enclosed cavern even with valid geometry depth. The live
depth diagnostic confirmed valid near geometry in the second Blackreach dome. Debug shader output was removed
after that check. Near cover uses the loaded roof field; distant/deep rejection uses coarse terrain height, so this
is not a full portal visibility solver. Real vertical openings and exposed ground remain eligible; sideways window
and cave-mouth transmission still need more complete visibility data and dedicated visual checks.

For interior captures inspect the `Autotest interior` line too: a pose can have loaded terrain while its dungeon
atmosphere packet is still catching up. Reject `rooms=0` when the test expects an authored cave medium. Some early
second-dome frames had this issue and are retained as failed cases. The current readiness gate certifies the fresh atmosphere packet and settled room blend. It does not certify
the far field or exposure convergence; horizon acceptance stays explicit. Do not equate an empty near-render queue
with a finished vista.

Measured live checks on this machine: a warm corridor sequence took 4.18 seconds with command acknowledgement and
terrain settling versus 12.26 with the old fixed waits. This is one scene, not a general performance benchmark.
Nine-chunk regeneration reported 0.7 s generation, 0.5 s paving, 0.8 s saving and 22.2 s total (mostly unload), plus
the old wrapper's 12-second parking sleep. That unconditional parking sleep has been removed; the real unload
completion wait remains. Successful bake upload was exercised with a local bundle and all four plans loaded on
restart. Production content, exact cave ambience and full distant readiness have not been signed off.

The unload observer checks visible/updating chunk holders and pending unloads; missing observation refuses
regeneration. It never declares completion from a timer. The extra one-second minimum was removed too. A second nine-chunk run completed
in 3.617 seconds for the whole server job (3.5 seconds regeneration summary), versus the earlier 34.39-second job.
A marker was successfully placed before that run and positively confirmed removed afterward. This is a bounded
preview operation, not a general new-world generation benchmark. See `dist/weather-review/dungeons/regen-observed-live.log`.


Stopped-world imports: use tools/dev/preview_world.py import SOURCE qa_NAME, then launch and run
preview_world.py verify. Selection normally closes Prism's windows before editing instance.cfg: Prism caches
settings and overwrites external edits while open. No process is force-killed. The verifier requires the actual
loaded-save log entry and expected seed, plus a fresh preview identity/PID. Reject frames if these disagree.
A newly imported headless world has a fresh player: for a non-intro visual fixture explicitly execute as that
player `nirncraft-quest intro skip` in the disposable copy. Otherwise IntroFlow can legitimately return them to
the cottage during camera settling. Retain the skipped-intro setup in the test scope; do not pretend it tests
that opening sequence. Existing review_world is retained and selectable for normal cave regressions.
