# Installing and playing Skycraft

Skycraft runs on **Minecraft 1.20.1 with Forge 47.4.10** and **Java 17**. The pack is Skycraft Core plus about thirty
performance, library and helper mods. It needs **about 6 GB of RAM** for the game, and a Steam Deck-class PC is
enough.

## 1. Get the files

On GitHub, open the repository's **Actions** tab, pick the latest green **Build** run, and scroll to **Artifacts**:

* `Skycraft-client-instance`: the ready-made Prism Launcher / MultiMC instance (recommended).
* `Skycraft-server`: a complete dedicated server (Forge plus the server-side mods).
* `Skycraft-exports`: `Skycraft.mrpack` for the Modrinth App and `Skycraft-curseforge.zip` for the CurseForge App.
  A mod that exists on only one platform can be missing from the other platform's export, so the Prism instance is
  the complete one.

GitHub wraps each artifact in a zip. Unzip it once to get the file named above.

## 2. Install on Prism Launcher (recommended)

1. Install [Prism Launcher](https://prismlauncher.org/) and sign in with your Microsoft account.
2. Click **Add Instance → Import**, choose `Skycraft-client-instance.zip`, then **OK**. Prism downloads Minecraft
   1.20.1 and Forge 47.4.10 itself. All the mods, configs and the optional shader pack are already inside.
3. Right-click the instance, then **Edit → Settings → Java**:
   * **Java:** use Java 17. Prism can download it for you (*Auto-detect* or *Download Java*).
   * **Memory:** the instance sets a minimum of 2048 MB and a maximum of 6144 MB. Raise the maximum to 8192 MB if
     you'll host friends.
   * **JVM flags:** the instance also sets G1 garbage-collector flags and `-Dessential.autoUpdate=false`, which keeps
     Essential on the version the pack was tested with.
4. Click **Launch**. The first start takes a few minutes while Forge prepares everything.
5. On the title screen, choose **New Game**. After the world is created, you'll be asked to choose your race. Then
   the prisoner's cart takes you to Helgen, and you'll find *The Traveler's Handbook* in your pack.

## 3. Play with friends using Essential (host on your own PC)

Essential is included, so nobody needs to forward ports or rent a server.

1. Everyone installs the same Skycraft instance (step 2) and adds each other as friends in the Essential menu. The
   Essential button is on the title and pause screens.
2. The host creates or opens a singleplayer world, then opens **Essential → Host World / Invite Friends** and invites
   their friends.
3. Friends accept the invite and join directly.
4. On joining, you're asked whether to fast travel to your party leader. Create a party in the Journal (**J**,
   Party tab), or with `/party create` and `/party invite <name>`. Party members share quests, rewards and map
   markers, always show on the map (**M**) and compass, and can fast travel to each other.

The host's PC runs the server, so give the host 8 GB of RAM in Prism. Guests can keep the instance's 6 GB.

## 4. Dedicated server (always-on world)

1. Unzip `Skycraft-server.zip` onto the server machine. It contains:
   * Forge and all the server-side mods;
   * a tuned `server.properties`: flight is allowed (giants launch players into the air), and there's a two-minute
     watchdog for heavy world generation;
   * JVM flags in `user_jvm_args.txt` (8 GB heap).
2. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA) and set `eula=true` in `eula.txt`.
3. Start it with `./run.sh` (Linux/macOS) or `run.bat` (Windows). Give it 6–10 GB of RAM in `user_jvm_args.txt`.
4. Players join with the client instance from step 2. Essential isn't needed for a dedicated server, but it does no
   harm.
5. Gameplay settings are in `config/skycraft-*.toml`. Per-world settings (regional weather) are in
   `world/serverconfig/`. Useful switches:

   | Setting | Where |
   | --- | --- |
   | Survival Mode | `/gamerule skycraftSurvivalMode false`, or `survival_mode.enabled` in `skycraft-survival.toml` |
   | The Helgen opening | `helgenStart` in `skycraft-quest.toml` |
   | Perk-gated digging | `restrictDigging` in `skycraft-common.toml` |
   | Corpse looting | `player_corpses.lootAccess` in `skycraft-survival.toml` |
   | Friendly fire and party size | `skycraft-quest.toml` |
   | The handbook gift | `giveOnFirstJoin` in `skycraft-guide-common.toml` |

## 5. Controls

New players get Skyrim-style keys on first launch: **E** inventory, **Tab** menu hub, **K** skills, **J** journal,
**P** magic, **M** map, **Z** shout, **F** interact, **Q** favorites. The full table is in
[CONTROLS.md](CONTROLS.md). In-game, the Controls chapter of *The Traveler's Handbook* shows your current bindings.

**Controller / Steam Deck:** Controllable is preconfigured with Steam button icons, the virtual mouse cursor in menus
and all button hints (`config/controllable.client.toml`). Every Skycraft screen fits the Deck's 1280×800 screen at GUI
scale 3.

## 6. Graphics, shaders and Distant Horizons

* **Starting settings.** The pack starts with Steam Deck-friendly defaults: render distance 10, simulation distance
  8, and Distant Horizons at a 128-chunk LOD distance with the **Low Impact** CPU preset (`config/DistantHorizons.toml`;
  its auto-updater is off). On a stronger PC, raise them in **Video Settings → Distant Horizons**.
* **Distant terrain generation.** Distant Horizons builds far terrain with trees and plants but no structures
  (`chunkGeneratorMode = FEATURES`). Skycraft's terrain sampler is thread-safe, so it can generate in parallel.
* **Stutter while exploring.** If you stutter while exploring new land, set Distant Horizons' CPU load to *Minimal
  Impact* so it builds distant terrain more slowly.
* **Shaders** (Complementary Reimagined, included) are optional: **Options → Video Settings → Shader Packs**. While a
  shader is active, Skycraft hands the sky (moons, auroras, stars) over to it.
* **Skycraft's own visual options** are client configs:
  * `skycraft-visual-client.toml`: particles and HUD styling;
  * `skycraft-atmosphere-client.toml`: sky, fog and weather visuals;
  * `skycraft-audio-client.toml`: ambience, reverb and music;
  * `skycraft-arsenal-client.toml`: kill cams;
  * `skycraft-guide-client.toml`: tutorial hints.
* **Background play.** Dynamic FPS lowers the frame rate while the game is in the background. ImmediatelyFast,
  Entity Culling, ModernFix, FerriteCore and Saturn keep the client fast and light on memory.

## 7. Updating

* **Prism.** Import the newest `Skycraft-client-instance.zip` as a new instance, then copy your `saves` folder (and
  `config/skycraft-*-client.toml` if you changed any) from the old instance's `.minecraft` folder.
* **Server.** Unzip the new server over a copy of the old one, keeping `world/`, `eula.txt`, `server.properties` and
  any configs you edited.
* **Packwiz.** Pointing packwiz-installer at `pack/pack.toml` in the repository installs only the third-party mods
  and configs. Skycraft Core isn't in the committed pack, so you'd have to add `skycraft-core.jar` to `mods` yourself.
* **Existing worlds.** Worlds created before a terrain update keep their old chunks. New terrain generates only in
  chunks you haven't visited yet.

## 8. Your own Skyrim audio (optional, personal use)

All of Skycraft's music, sounds and voices are original. If you own Skyrim, you can build a private resource pack
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

* **"Mod requires forge 47.4.10 or above".** The instance must use Forge 47.4.10 (Edit → Version).
* **The game closes on launch.** Give it more memory (at least 6 GB), and make sure it uses Java 17, not Java 21.
* **World creation seems slow.** The first spawn area generates the Helgen region, so give it a minute. A prompt
  about experimental settings is accepted automatically.
* **Kicked for flying on a server.** Set `allow-flight=true` in `server.properties`.
* **Two actions on the same key.** Skycraft moves its own keys away from clashes on launch. Rebind anything else in
  **Options → Controls**. To reset Skycraft's first-launch key pass, delete `config/skycraft-client-keys.done`.
* **The tutorial hints are in the way.** Type `/handbook hints off`. `/handbook hints reset` shows them all again.
* **Logs** are in the instance's `.minecraft/logs/latest.log`, and crash reports in `.minecraft/crash-reports/`.
  When reporting a problem, attach `latest.log`. If the game lags, a Spark profile also helps: `/spark profiler`.
