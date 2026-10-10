# Installing and playing Skycraft

Skycraft runs on **Minecraft 1.20.1 with Forge 47.4.10** and **Java 17**. It needs **about 6 GB of RAM** for the
game (Distant Horizons and about 60 mods); a Steam Deck-class PC is enough.

## 1. Get the files

Open the repository on GitHub → **Actions** → the latest green **Build** run → **Artifacts**:

* `Skycraft-client-instance`: the ready-made Prism Launcher / MultiMC instance (recommended).
* `Skycraft-server`: a complete dedicated server (Forge plus server-side mods).
* `Skycraft-exports`: `Skycraft.mrpack` for the Modrinth App and `Skycraft-curseforge.zip` for the CurseForge App.

GitHub wraps each artifact in a zip. Unzip it once to get the file named above.

## 2. Install on Prism Launcher (recommended)

1. Install [Prism Launcher](https://prismlauncher.org/) and sign in with your Microsoft account.
2. Click **Add Instance → Import**, choose `Skycraft-client-instance.zip`, then **OK**.
   Prism downloads Minecraft 1.20.1 and Forge 47.4.10 itself; all mods, configs and shaders are already inside.
3. Right-click the instance → **Edit → Settings → Java**:
   * Java: 17 (Prism can download it for you: *Auto-detect* or *Download Java*).
   * Memory: minimum 2048 MB, maximum 6144 MB (already set by the instance). Raise the maximum to 8192 MB if you host
     for friends.
   * The instance also sets G1 garbage-collector flags and `-Dessential.autoUpdate=false`, which keeps Essential on
     the version the pack was tested with.
4. Click **Launch**. The first start takes a few minutes while Forge prepares everything.

To update, import the newest `Skycraft-client-instance.zip` as a new instance and copy your `saves` folder across.
(Pointing packwiz-installer at `pack/pack.toml` in the repository only installs the third-party mods and configs:
Skycraft Core isn't in the committed pack, so you would have to drop `skycraft-core.jar` into `mods` yourself.)

## 3. Play with friends using Essential (host on your own PC)

Essential is included. Nobody needs to port-forward or rent a server.

1. Everyone installs the same Skycraft instance (step 2) and adds each other as friends in the Essential menu
   (the Essential button on the title and pause screens).
2. The host creates or opens a singleplayer world, then opens **Essential → Host World / Invite Friends**
   and invites their friends.
3. Friends accept the invite and join directly.
4. On joining, everyone is asked whether to **fast travel to the party leader** or stay where they are. Create a
   party in the Journal (**J**) (or `/party create`, `/party invite <name>`) so you share quests, rewards and map
   markers. Party members always show on the Skyrim map (**M**) and compass. Click a member on the map to fast travel
   to them.

The host's PC runs the server, so give the host 8 GB of RAM in Prism. Guests can keep the instance's 6 GB.

## 4. Dedicated server (always-on world)

1. Unzip `Skycraft-server.zip` onto the server machine. It contains Forge, all server-side mods, a tuned
   `server.properties` (flight allowed, because giants launch players into the air; a 2-minute watchdog for heavy
   world generation) and JVM flags in `user_jvm_args.txt`. New worlds get Skycraft's server configs from
   `defaultconfigs/` (for example, corpses never despawn while they still hold items, and other players may loot them).
2. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA) and set `eula=true` in `eula.txt`.
3. Start it with `./run.sh` (Linux/macOS) or `run.bat` (Windows). Give it 6–10 GB of RAM in `user_jvm_args.txt`.
4. Players join with the client instance from step 2. Essential isn't needed for a dedicated server, but is harmless.

## 5. Controls

New players get these keys on first launch (Default Options ships them in `config/defaultoptions/keybindings.txt`;
it never overrides keys you've changed). If another mod already uses one of them, Skycraft moves its own key to a
free one. Everything can be changed in **Options → Controls → Key Binds → Skycraft**.

| Key | Action |
| --- | --- |
| E | Skyrim inventory |
| Tab | Menu hub: Skills ↑, Items ←, Magic →, Map ↓, Journal |
| K | Skills: look to the heavens, spend perk points, level up |
| J | Journal: quests, factions, party, reputation, stats |
| P | Magic menu: equip spells, shouts and powers |
| M | Skyrim map and fast travel |
| Z | Shout or use the equipped power (hold for more words) |
| Q / mouse wheel | Favorites; 1–8 are favorite hotkeys |
| Left mouse | Attack or cast the right-hand spell. Hold to power attack. |
| Right mouse (hold) | Block with your weapon or shield, or cast the left-hand spell |
| F | Interact: talk, loot, use |
| Left Alt | Dodge roll (perk-unlocked) |
| H | Call your horse |
| I | Wait. Right-click any bed to sleep. |
| Y | Reputation and faction regalia |
| ` | Player list |
| Sneak + use NPC | Pickpocket |
| Sneak + use corpse | Drag a body |

Left unbound: ready/sheathe weapon, the Standing Stone power key (powers are on Z), vanilla drop, swap hands,
advancements, hotbar slot 9, and Oculus's reload/toggle/select-shader keys.

**Controller / Steam Deck:** Controllable is preconfigured with Steam button icons, the virtual mouse cursor in menus
and all button hints (`config/controllable.client.toml`).

## 6. Performance tips

* The pack starts with Steam Deck-friendly defaults: render distance 10, simulation distance 8, and Distant Horizons
  at a 128-chunk LOD distance with the **Low Impact** CPU preset (`config/DistantHorizons.toml`; its auto-updater is
  off). Raise them in Video Settings → Distant Horizons on a stronger PC.
* Shaders (Complementary Reimagined) are optional: **Options → Video Settings → Shader Packs**. Skycraft's sky
  hands the sky over to the shader when one is active.
* If you stutter while exploring new land, lower Distant Horizons' CPU load further (Distant Horizons settings →
  CPU load → Minimal Impact) so it builds distant terrain more slowly.
* Dynamic FPS lowers the frame rate while the game is in the background, and ImmediatelyFast, Entity Culling,
  ModernFix, FerriteCore and Saturn keep the client fast and light on memory.

## 7. Troubleshooting

* **"Mod requires forge 47.4.10 or above"**: the instance must use Forge 47.4.10 (Edit → Version).
* **Game closes on launch**: give it more memory (at least 6 GB) and make sure it uses Java 17, not Java 21.
* **Kicked for flying on a server**: set `allow-flight=true` in `server.properties`.
* Logs are in the instance's `.minecraft/logs/latest.log`. Crash reports are in `.minecraft/crash-reports/`.
