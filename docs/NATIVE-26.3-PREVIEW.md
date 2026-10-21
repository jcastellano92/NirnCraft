# Native 26.3 preview

Use Minecraft **26.3**, NeoForge **26.3.0.90**, and Java **25**. Normal Minecraft multiplayer and LAN are the only hosting paths. Preserve the owner's own instances and saves. This page covers the build helper, the dev server, the disposable preview instance and the core-only diagnostic; for tests use the harness ([tools/harness/README.md](../tools/harness/README.md)), which drives the same controls. The doc map is [README.md](README.md).

For the full installed server ZIP, Java 25 launchers, fresh-world bake/release path
and actual server-package gate, see [SERVER-26.3.md](SERVER-26.3.md). A DevLaunch
core-only server does not establish full-pack multiplayer compatibility.

The owner approved official Sodium **0.9.3-beta.1** (`SS023RVV`) after the original 0.9.2 artifact failed against NeoForge's removed render overload. Iris is **1.11.7** (`Ag5S66CF`) and runs NirnCraft's own shader pack, Infinite ATLAS (`pack/shaderpacks/InfiniteATLAS.zip`, enabled by default in `pack/config/iris.properties`); it is the only shader pack the pack may ship, and Distant Horizons is not part of the pack (Infinite ATLAS replaces it). The exact artifact/hash-bound Modrinth dependency exception is recorded in `pack/migration/platform-lock.json` and checked by `validate_pack.py`; it permits only this pair.

Infinite ATLAS owns the frame (`AtlasNearPass`, and the unified field with `nirncraft.atlas.unified=true`) only where Sodium is absent, so the renderer's dev and autotest instance runs without Sodium and Iris, while the shipped pack keeps both and the InfiniteATLAS pack until our own pipeline replaces them (ATLAS-RENDERER-PLAN.md, R4). The renderer's state, flags and test actions: [ATLAS-STATUS.md](ATLAS-STATUS.md).

Run from the repository root. Source checks and packaging preparation do not establish that the game works: complete the full build, tests, dedicated server boot ([SERVER-26.3.md](SERVER-26.3.md)) and the harness gate (`py -3 tools/harness/harness.py gate`).

## Native test storage

Use `py -3 tools/dev/build_263.py <tasks>` or `tools/dev/build_263.ps1 -Tasks <tasks>` for cooperative native builds.
The PowerShell wrapper delegates to the same Python helper, including `-Offline` / `--offline`, and preserves its
exit code without changing the caller's environment. Both hold the same nonblocking OS lock at
`<scratch>/.native-build.lock`, where `<scratch>` is `NIRNCRAFT_263_SCRATCH` (machine default `E:\NirnCraft-scratch\migration-26.3`; below, `<java25>` is the Java 25 executable from the toolchains folder) before opening the build
log and through the Gradle call. A competing call fails immediately with a tool label and PID, without truncating
its log. The persistent lock file is diagnostic; never delete it or force a PID-based unlock. The OS releases the
lock on exit, process death or reboot. Only direct raw Gradle commands bypass these supported build wrappers. A free lock
does not establish that a previously started Gradle child exited; inspect its exact process and logs before retrying.

`-PnirncraftBuildDir=<scratch>/build` places Gradle outputs and the native JUnit working directory in the scratch folder. MDG 2.0.148's `UnitTest` DSL has no `gameDirectory` option: its default `build/minecraft-junit` is project relative even when the build directory changes. NirnCraft overrides the preparation task's public directory property and sets the test working directory after MDG's own assignment.

Read native FML test logs under `<scratch>/build/minecraft-junit/logs/latest.log` and `debug.log`; crash reports/configs are beside that logs directory. JUnit XML and HTML reports remain under `build/test-results/test` and `build/reports/tests/test` in the selected E: build directory. These overrides apply to Gradle tests; IDE-only JUnit run configurations still use the plugin's project-relative default unless configured separately.

Native `EphemeralTestServerProvider` creates its world with `Files.createTempDirectory("test-mc-server-")`. NirnCraft separately sets the native Gradle test JVM's `java.io.tmpdir` to the selected `build/minecraft-junit/tmp`, which also places those ephemeral worlds in the scratch folder with this build property. Relocating directories alone does not establish an NVMe performance gain. Require bounded startup and actual server failure diagnostics; a running Gradle worker is not evidence that its test server started.

## Dedicated-server control

Keep the dedicated QA server console attached to a writable terminal, and set `pause-when-empty-seconds=-1` in its isolated `server.properties`. The native server otherwise pauses after 60 empty seconds; a command inbox handled by server ticks also stops while paused. Prefer the console `stop` command for orderly shutdown.

For the persistent fast loop, use the native controller. Its default runtime is now `mod/run/devworld-26.3`; the previous `mod/run/devworld` is preserved. `-RuntimeRoot` accepts owned QA/benchmark/native scratch runtime directories, and the corridor tools follow the native default.

```powershell
tools/dev/devserver.ps1 start
tools/dev/devserver.ps1 status
tools/dev/devserver.ps1 run "list|save-all flush" -Wait
tools/dev/devserver.ps1 hotswap
tools/dev/devserver.ps1 stop
```

Startup runs the exact platform's MDG `prepareServerRun` and `createServerLaunchScript` tasks through `build_263.py`, retaining independent classpath/VM/program argfiles in that runtime before starting the checked Java 25 executable. It checks the native Minecraft version, NeoForge artifact, current Java 25 development classes and exact command-inbox directory. Only the server gets the loopback JDWP agent and requested heap. Empty-server pausing is disabled. No old Forge core or cache supplies missing native classes. The first native controller launch and hotswap still require the coordinated runtime acceptance check.

`run` reuses the server, hot-swaps compatible Java changes, and restarts for resource/schema changes. Jobs remain FIFO and stop waiting when their actual completion marker appears. `status` and `stop` verify the exact native executable, runtime argument file and inbox property before using a PID. `stop` first uses the ticking inbox, then the public attach fallback if needed; it preserves the running world and reports failure if orderly shutdown cannot finish.

If the console was lost and that inbox is paused, this development-only fallback requests the public native `MinecraftServer.halt(true)` method through Java 25's attach API:

```powershell
py -3 tools/dev/stop_native_server.py --pid <exact-server-pid> --java <java25> --runtime <scratch>/runtime/server --scratch <scratch>/runtime/orderly-stop-<exact-server-pid>
```

Use a fresh scratch directory for each request. The tool validates that PID's executable, Java version/home, runtime working directory, dedicated NeoForge development launcher and expected control directory twice, including inside the target JVM. The default control directory is the runtime's sibling `server-control`; pass `--control` for another explicit development inbox such as the persistent controller's runtime itself. It uses public methods and instrumentation only to find the already loaded lifecycle class; it does not transform bytecode, open private members or terminate the process. Preserve the stop receipt, verify the log saved every world, and confirm the exact PID exited. A dispatched stop alone is not proof that saving completed. Do not use it for the normal client or another server.

## Prepare without starting Minecraft

After the native build and server checks pass, supply the actual new jar explicitly. Scratch builds do not appear under `mod/build/libs`.

```powershell
py -3 tools/validate_pack.py --core-jar <scratch>\build\libs\nirncraft-0.1.0.jar
py -3 tools/build_prism_instance.py --core-jar <scratch>\build\libs\nirncraft-0.1.0.jar
if ($LASTEXITCODE -ne 0) { throw 'Native full-pack packaging failed' }
tools\qa\prism_autotest.ps1 -PrepareOnly -Live -Java <java25>
```

Replace the example jar filename with the actual build artifact. The full archive is `dist/NirnCraft-26.3-client-instance.zip`. Preparation selects only `%APPDATA%/PrismLauncher/instances/NirnCraft-26.3-autotest`; the old `NirnCraft-autotest` and normal `NirnCraft` are preserved. An earlier native preview is renamed into `%APPDATA%/PrismLauncher/nirncraft-263-backups/<timestamp>` with its saves, screenshots and logs intact. Preparation refuses to replace a running native preview.

`tools/qa/prepare_prism_263.py` performs the preparation without any launch capability. It checks the archive's exact component versions, complete pinned mod list and hashes, native Java 25 core, embedded platform lock and shader settings (off, or only InfiniteATLAS.zip) before publishing the instance. It pins the checked Java executable in `instance.cfg`, writes live startup flags, and writes an installation receipt under `.minecraft/migration/preview-receipt.json`. The receipt explicitly records `runtimeVerified: false`.

Prism must support NeoForge and the exact game release, and have an authenticated Minecraft account. It resolves the actual native game libraries/assets on launch; packaging does not silently substitute old game files. No copied legacy client jars are installed. Optional `-SaveSource PATH` copies a save into the disposable preview; use a backup and treat a legacy world's data conversion as a separate acceptance test.

For a separate Prism data directory, pass `-PrismRoot PATH`. Launch uses Prism's documented [`--dir` option](https://prismlauncher.org/wiki/getting-started/command-line-interface/) so it selects that directory and its own authenticated account. Set `$env:NIRNCRAFT_PRISM_ROOT` to the same path before using `live_world.py`; its process matching, files and launch then address that exact directory. Preparation does not copy account credentials or move existing saves.

## Launch and operate the prepared preview

When the owner has authorized the runtime launch:

```powershell
py -3 tools/dev/live_world.py status
py -3 tools/dev/live_world.py launch
py -3 tools/dev/live_world.py status
```

The controller addresses only the exact native instance. It reports PID and window state without exposing launcher authentication arguments. Reuse a running preview. A process or title screen does not establish world readiness: require a fresh client heartbeat and `serverReady: true` for world jobs. `launch` validates exact platform/core and returns immediately; poll status and inspect `.minecraft/logs/latest.log`. `close` requests a clean controller shutdown and never kills arbitrary Java processes.

For scene tours, captures and before/after comparisons use the harness (`harness.py run`, `probe`, `watch`), which launches this instance itself and closes it at the end of every run. `tools/qa/prism_autotest.ps1 -PrepareOnly -Live` remains the way to (re)prepare the instance.

The harness plans cameras offline on the seeded terrain (`tools/harness/cameras.py`); the low-level [live controls](LIVE-WORLD-PREVIEW.md) remain available. Commands such as `settle 1800` are readiness deadlines, not mandatory sleeps. They require full nearby terrain, received lighting, idle Sodium queues, completed stable main-camera frames, camera clearance and settled atmosphere. Successful screenshot writes remain pending until their atomic PNG and receipt publication completes. Use `measure` separately from screenshot bursts.

Shader-pack edits go through `py -3 tools/harness/harness.py watch` (rebuilds the InfiniteATLAS zip and reloads Iris); the old `live_world.py shader` patch command is retired. atlas_core's own kernels reload in the running client with `py -3 tools/dev/shader_watch.py --game` (it also stages a fresh core in `.minecraft/atlas-dev` for the next start); Java method bodies hot-swap with `tools/dev/client_hotswap.py`.

## Separate core diagnostics

When a third-party startup failure blocks the full pack, an explicitly authorized core-only diagnostic can isolate native NirnCraft issues. This is a separate instance, not full-pack acceptance. Keep the active dependency lock and full preview intact. It uses the same Prism data directory and existing account without copying credentials.

```powershell
py -3 tools/qa/build_core_diagnostic_263.py --core-jar <scratch>/build/libs/nirncraft-0.1.0.jar --output <scratch>/runtime/core-diagnostic-build35.zip --java <java25>
py -3 tools/qa/prepare_core_diagnostic_263.py --archive <scratch>/runtime/core-diagnostic-build35.zip --java <java25>
$env:NIRNCRAFT_NATIVE_INSTANCE = 'NirnCraft-26.3-core-diagnostic'
py -3 tools/dev/live_world.py status
# Only after the authorized server/client runtime gate permits another Minecraft process:
py -3 tools/dev/live_world.py launch
```

Choose a fresh archive filename to preserve previous evidence. Preparation has no launch capability and backs up only the previous diagnostic instance. The diagnostic has exactly one native core jar, no third-party mods or shader packs, and its own `qa_core_diagnostic_263` world. Its marker and preparation receipt explicitly record `fullPackAcceptance: false`; launching checks the exact platform, core hash and contents again. The controller refuses arbitrary instance names and simultaneous full/diagnostic clients. Use its existing commands, camera/readiness, capture and clean-close controls. Rebuild and prepare a new diagnostic archive to change its core or bakes.

After closing it cleanly, restore the default full preview selector with `Remove-Item Env:NIRNCRAFT_NATIVE_INSTANCE`. Full-pack validation still requires all pinned dependencies and their hashes. A successful diagnostic screenshot cannot resolve a third-party compatibility failure or prove the shader pack's integration.

For a genuine network-codec check, the diagnostic preparer also accepts `--server 127.0.0.1:PORT`. It accepts only that explicit loopback literal and a port in 1..65535. The prepared client uses native `ConnectScreen` before any automatic world creation and never falls back to a local world if connecting fails. Run this mode only when the dedicated loopback QA server and client are explicitly coordinated; it intentionally requires two Minecraft processes. Grant the test player op through the dedicated server's controlled inbox after login. Live initialization waits for the actual native gamemaster permission.

Use the complete pinned pack for final TCP acceptance: `tools/qa/prism_autotest.ps1 -PrepareOnly -Live -SourceZip PATH_TO_VERIFIED_NATIVE_CANDIDATE.zip -Server 127.0.0.1:PORT -Java PATH_TO_JAVA25.exe`. The full Python preparer exposes the same option as `--server`. It retains full dependency/hash validation, backs up the previous integrated preview and refuses to replace a running client. Remote mode cannot be combined with integrated save copying or automatic terrain tours. Preparation records the intended transport without claiming a connection; launch and verify the actual TCP receipt afterward.

Client status, camera identity and screenshot receipts report the actual connection's `isMemoryConnection` value, `transport`, `connected`, `remote` and `integratedServer`. A TCP test requires `isMemoryConnection: false`, `transport: tcp`, `connected: true` and `integratedServer: false`. The `transport` live action writes those observations to `world-preview/transport-receipt.json`; it explicitly does not certify payload codecs. Require real server/client payload and state observations as well.

Remote live `command` actions use native multiplayer command submission and explicitly record that no server-result acknowledgement exists. Complete commands through the dedicated server's authoritative inbox receipts and verify the resulting client state. `serverReady` continues to describe only an integrated inbox; a connected remote client reports `remoteConnected` separately. Remote clients do not know the world seed, so identity records `seedKnown: false`. Position remote cameras through the server inbox and then require the same local terrain, lighting, clearance and stable-frame readiness checks. Integrated camera scouting, prewarming and weather-front helpers retain their existing behavior and require local server access.
