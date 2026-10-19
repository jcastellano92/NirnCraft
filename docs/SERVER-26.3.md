# Native dedicated server

The server uses **Minecraft 26.3, NeoForge 26.3.0.90 and Java 25**. Normal Minecraft
Multiplayer connects to its address. Local network discovery is for integrated LAN
games. All clients must use the matching full pack (the InfiniteATLAS shader pack
and the atlas far field are client-side only).

`tools/build_server_instance.py` installs the exact official NeoForge server,
checks its installer SHA256, and selects only the platform lock's server/both mods.
The current set contains 13 dependencies plus NirnCraft core, including Better
Combat, Player Animation Library and Cloth Config. It checks hashes even when a
file comes from cache, checks required dependency side closure and the actual
bundled mod/JarJar descriptors, and requires all four consistent embedded bakes.
It does not start Minecraft or accept the EULA. A package receipt records
`runtimeVerified: false`; packaging is separate from runtime acceptance.
For later core builds on the same platform, `--base-archive` and its recorded
`--base-sha256` reuse only the verified native libraries from an earlier server ZIP.
Previous core jars, mods, worlds and EULA files are never copied by that shortcut.

After the full native build, create a candidate without replacing an older archive.
Use the actual tested core jar explicitly. Below, `<scratch>` is the build scratch
folder (`NIRNCRAFT_263_SCRATCH`, machine default `E:\NirnCraft-scratch\migration-26.3`)
and `<java25>` the Java 25 executable from the toolchains folder:

```powershell
py -3 -I tools/build_server_instance.py --core-jar <scratch>/build/libs/nirncraft-0.1.0.jar --java <java25> --work-root <scratch>/server-build --cache <scratch>/server-cache --output-zip <scratch>/candidates/NirnCraft-26.3-server-<build>.zip
```

The output contains a complete `server/` directory and native libraries. Extract
it to a new location, read the Minecraft EULA and set `eula=true` in `eula.txt` if
you agree. On Windows use `run.bat` or `run.ps1`; on Linux/macOS use `./run.sh nogui`.
The launchers select `NIRNCRAFT_JAVA`, then `JAVA_HOME`, then PATH and check that the
selected executable reports Java 25 before Minecraft starts. Adjust RAM in
`user_jvm_args.txt`. Fresh servers use the authored Nirn seed; keep an existing
world's seed unchanged. Legacy worlds require offline conversion on a backup as
described in [NATIVE-DATA-MIGRATION-26.3.txt](NATIVE-DATA-MIGRATION-26.3.txt).

Use the console `stop` command, and wait for all dimensions to save. Do not replace
an active world's files. The builder preserves prior archives, and it never uses
the previous Forge server, old client instance or `mod/run/world`.

For an actual full installed-server smoke test, coordinate exclusive runtime
access first. This command starts Minecraft in a fresh directory; it sets test
EULA acceptance, offline loopback networking and disabled empty-server pausing
only in that disposable copy:

```powershell
py -3 -I tools/qa/test_server_package_263.py --archive <scratch>/candidates/NirnCraft-26.3-server-<build>.zip --java <java25> --work-root <scratch>/server-qa
```

It waits for actual startup, command results, the live bake check, saved-game
feedback and clean shutdown. It stops waiting immediately on completion or a
concrete failure. Timeouts first send `stop`; any final termination addresses only
its own retained child-process handle. No process-name matching or broad `pkill`
is used. Every run retains its world, console, exact PID/launch identity and result.
`--commands` can add a JSON array of `{ "command": "...", "expect": "regex",
"timeout": 180 }` checks. A real full-pack client connection and visuals remain
separate gates; the smoke result records `multiplayerVerified: false`.
`--dev-control` enables the existing NirnCraft development diagnostics only in that
isolated QA copy, while retaining normal spawn ownership. Native clock queries use
`time query time` or a registered timeline ID; the old `time query daytime` syntax
is gone. Shutdown validation requires a save line for every packaged dimension,
the native FML close/clear markers and exit code zero; 26.3 has no old generic
"All dimensions are saved" log line.

`tools/make-instances.sh` is a thin wrapper over the native server and Prism
builders. `tools/test-server.sh` wraps the native smoke gate and uses the correct
`NirnCraft-26.3-server.zip` name. Both accept `NIRNCRAFT_JAVA`; set
`NIRNCRAFT_PACKAGE_DIR` to preserve previous packages in a fresh output directory.

The normal Windows release entry point is `tools/build_release.ps1`. It runs the
isolated exact-platform build/tests, `tools/bake/bake.ps1` in a fresh native world,
repackages the verified bakes, builds both full packages and runs the installed
server smoke gate. It never launches a client automatically. Outputs go to a new
timestamped directory under `dist`, or explicit `-OutputDir`. `-SkipQa` creates
candidates and reports that dedicated-server acceptance was skipped.

The bake script uses the native controller's `nirncraft-bake refresh` and records
the actual runtime input hash. A stale bundle is independently computed twice;
an already-current bundle is compared against live computation. It saves/stops
the server before publication. `tools/bake/publish_263.py` requires the successful
native job receipt, full manifest, matching headers and no retained mismatch.
It backs up the previous bundle and publishes only the four plans and manifest,
rolling back a partial file replacement if necessary. All test worlds and logs
remain intact.

Fast tooling checks, without Minecraft:

```powershell
py -3 -m unittest discover -s tools -p test_server_package_263.py -v
py -3 -m unittest discover -s tools -p test_bake_publish_263.py -v
```
