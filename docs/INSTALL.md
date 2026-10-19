# Installing and playing NirnCraft

NirnCraft targets **Minecraft 26.3, NeoForge 26.3.0.90 and Java 25**. The full
client contains NirnCraft Core and 22 pinned performance/library/helper artifacts.
Start with 6 GB of game memory; actual performance depends on hardware and settings.
The native port's runtime evidence and remaining gates are recorded in
[NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md).

## 1. Get the files

Get the tested locally built package from the project owner. Developers use
`tools/build_release.ps1`; it preserves previous archives and writes both packages
to a new release directory under `dist` (or explicit `-OutputDir`). GitHub Actions
is disabled, so a push does not publish a package.

* `NirnCraft-26.3-client-instance.zip`: the full Prism Launcher / MultiMC instance.
* `NirnCraft-26.3-server.zip`: the installed native dedicated server and its 13
  server-side dependencies, plus NirnCraft Core.

Use the matching full client/server packages. A core-only diagnostic instance is
for isolating failures and is not full-pack acceptance. `NirnCraft-26.3.mrpack`
(built by `tools/build_mrpack.py`) installs the same pinned pack in the Modrinth App.

## 2. Install on Prism Launcher

1. Install [Prism Launcher](https://prismlauncher.org/) and sign in with your Microsoft account.
2. Use **Add Instance → Import** and choose `NirnCraft-26.3-client-instance.zip`.
   Prism resolves the exact Minecraft 26.3 and NeoForge 26.3.0.90 libraries/assets.
3. In **Edit → Settings → Java**, select Java 25. The instance starts with 2048 MB
   initial and 6144 MB maximum memory; allow more when hosting if your PC has room.
4. Launch the instance. Keep any older NirnCraft instance and its worlds separate.
5. Choose **New Game** to create the authored Nirn world. Character creation and
   the Helgen opening follow; *The Traveler's Handbook* lists current controls.

The current Sodium pin is the explicitly approved official 0.9.3-beta.1 build. Iris
runs NirnCraft's own shader pack, Infinite ATLAS, which is enabled by default.

## 3. Play with friends

For the same local network, the host opens a world with **Open to LAN**. Other
players use **Multiplayer**, or **Direct Connect** with the host address and the
port shown by Minecraft if discovery does not find it. Allow Java through the
host's firewall for the intended local network.

For a dedicated server, use **Multiplayer ? Add Server** with its address. Outside
the local network, normal Minecraft requires a reachable server address and port;
LAN discovery does not create an internet invitation. Essential/e4mc are not
installed. Everyone must use the same native full pack.

The host runs the world simulation, so reserve enough RAM for the game and OS.
Create a party through the Journal (**J**, Party tab), or `/party create` and
`/party invite <name>`, to use NirnCraft's shared quest/travel features.

## 4. Dedicated server

1. Extract the complete `server/` directory from `NirnCraft-26.3-server.zip` to a new
   location. Keep existing worlds and configurations backed up.
2. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA) and create `eula.txt`
   containing `eula=true` if you agree.
3. Use `run.bat`/`run.ps1` on Windows or `./run.sh nogui` on Linux/macOS. The launchers
   require Java 25 and select `NIRNCRAFT_JAVA`, then `JAVA_HOME`, then PATH.
4. Adjust RAM in `user_jvm_args.txt` (default 2 GB initial, 6 GB maximum). Fresh
   servers use Nirn. The defaults allow flight for shouts/giants and a two-minute
   watchdog for generation; public servers retain `online-mode=true`.
5. Join using the matching client pack. Use console `stop` and wait for every
   dimension to save before closing the server or upgrading files.

Local settings are in `config/nirncraft-*.toml`. Native synced world settings use
`world/syncedconfig/`; do not copy old `serverconfig` files without the documented
migration. Survival Mode uses `/gamerule nirncraft:survival_mode false` or its
`survival_mode.enabled` configuration. Other gameplay settings retain their
explicit filenames and authored values.

See [SERVER-26.3.md](SERVER-26.3.md) for exact package checks, launch controls,
automated tests and the native bake/release pipeline.

## 5. Controls

New players get Skyrim-style keys on first launch: **E** inventory, **Tab** menu hub, **K** skills, **J** journal,
**P** magic, **M** map, **Z** shout, **F** interact, **Q** favorites. The full table is in
[CONTROLS.md](CONTROLS.md). In-game, the Controls chapter of *The Traveler's Handbook* shows your current bindings.

**Controller / Steam Deck:** Controllable is preconfigured with Steam button icons, the virtual mouse cursor in menus
and all button hints (`config/controllable.client.toml`). Every NirnCraft screen fits the Deck's 1280×800 screen at GUI
scale 3.

## 6. Graphics, shaders and the far field

* The native client uses the approved pinned Sodium build. Set render and
  simulation distances for your hardware, and compare performance in a loaded
  world before assuming a setting is inexpensive.
* Iris ships with `InfiniteATLAS.zip` selected (`config/iris.properties`); turn it
  off or on in **Video Settings → Shader Packs**. It is the only supported pack: it
  composites the atlas far field, weather, smoke, lightning and realm skies.
* Distant terrain comes from Infinite ATLAS's native renderer (`atlas_core`), which
  needs a Vulkan 1.2 GPU with `shaderInt64` (GTX 10xx, Polaris, RDNA, the Steam
  Deck, Intel Gen9 or newer) and `--enable-native-access=ALL-UNNAMED` (Minecraft's
  launcher profile passes it; add it under JVM arguments in a launcher that does
  not). Without a usable device the far field stays off and the game runs as before.
* NirnCraft's visual, atmosphere, audio, arsenal and guide client settings keep
  their module filenames in `config`. Review the current generated configs in
  the native instance rather than copying an entire old config directory.
* Dynamic FPS, ImmediatelyFast, Entity Culling, FerriteCore and Lithium are among
  the current performance helpers. ModernFix, Saturn and Memory Leak Fix are not
  part of the active native set. The exact list is in `migration/platform-lock.json`.

## 7. Updating

* Import the new client as a separate instance and keep the previous one intact.
  Copy only intentional personal settings and a backed-up, supported save.
* Saves from the old Minecraft 1.20.1 version require the offline conversion procedure in
  [NATIVE-DATA-MIGRATION-26.3.txt](NATIVE-DATA-MIGRATION-26.3.txt). Never let the new
  game be the first process to modify the only copy of an old world.
* Extract the new server to a separate directory, then restore its backed-up,
  supported world, EULA, properties and selected compatible settings. Do not leave
  old mod jars beside the new pinned dependencies.
* Packwiz source state does not include NirnCraft Core; the complete builders inject
  the actual tested native core. Installing only `pack/pack.toml` is not the full game.
* Existing chunks keep their generated terrain; generation changes affect new
  chunks. Visual-test camera poses must be revalidated when map/generation changes.

## 8. Your own Skyrim audio (optional, personal use)

All of NirnCraft's music, sounds and voices are original. If you own Skyrim, you can build a private resource pack
from your own copy of the game's music, sound effects and Thu'um voices:

1. Install Python 3 and ffmpeg (built with Vorbis).
2. Preview what it will do: `py -3 tools/music/import_skyrim_audio.py --dry-run`. It finds Steam and GOG installs by
   itself; `--skyrim <path>` points it at another one.
3. Build the pack: `py -3 tools/music/import_skyrim_audio.py --out "%APPDATA%/.minecraft/resourcepacks"`. For Prism,
   point `--out` at the instance's `resourcepacks` folder.
4. In game, enable it under **Options → Resource Packs**.

The pack is for your own use only and must never be shared. The script refuses to write it inside the repository.
See [MUSIC.md](MUSIC.md) for all the options.

## 9. Troubleshooting

* **Wrong game/loader:** require exactly Minecraft 26.3 / NeoForge 26.3.0.90. An older
  NirnCraft instance is a separate install, not a fallback.
* **Java error:** select Java 25 in Prism or the server launcher environment.
* **Startup crash:** inspect the actual native `logs/latest.log` and crash report;
  a core-only diagnostic succeeding does not clear a third-party full-pack failure.
* **Slow world creation:** wait for actual chunk/lighting/readiness completion.
  Do not use an arbitrary long sleep as proof that the world loaded correctly.
* **Kicked for flying:** keep `allow-flight=true` in the dedicated server properties.
* **LAN discovery misses the host:** use Direct Connect with the actual local IP and
  LAN port, and check the host's local firewall permissions.
* **Two actions on one key:** review **Options → Controls**. The first-launch key
  pass marker is `config/nirncraft-client-keys.done`.
* **Tutorial hints:** `/handbook hints off` disables them; `/handbook hints reset`
  enables them again.
* **Logs:** use the selected native instance's `.minecraft/logs/latest.log`, or the
  server's `logs/latest.log`. Spark profiles can help investigate observed stalls.
