# Skycraft

**Skycraft turns Minecraft into The Elder Scrolls V: Skyrim.** You start as a nobody, unable to do much more than
fight and mine an ore vein. From there you level up skills by using them, look to the heavens to unlock perks,
learn spells from tomes, absorb dragon souls, and shout. Along the way you join factions, take radiant quests and
bounties, trade with merchants, get thrown in jail, and adventure with your friends.

* **Minecraft 1.20.1, Forge 47.4.10, Java 17**: the most complete RPG mod ecosystem.
* **Skycraft Core** (`mod/`) is a custom mod that builds the Skyrim rules into Minecraft.
* About 60 well-known mods (`tools/modlist.txt`) supply towns, dungeons, villagers, vampires, werewolves,
  Distant Horizons, and performance. Skycraft prefers its own systems over third-party feature mods: followers, NPC
  dialogue, tree felling and the Skyrim map are all Skycraft Core.

## Install

Every CI run on GitHub Actions produces downloads (Actions → latest run → *Artifacts*):

| Artifact | Use |
| --- | --- |
| `Skycraft-client-instance` | Zip for **Prism Launcher / MultiMC**: *Add Instance → Import → zip*. Includes Forge, all mods, configs and shaders. Give it 6–8 GB RAM. |
| `Skycraft-server` | Ready Forge server with all server-side mods. Accept the EULA, then run `run.sh` / `run.bat`. |
| `Skycraft-exports` | `Skycraft.mrpack` (Modrinth App) and `Skycraft-curseforge.zip` (CurseForge App). Mods that are only on one platform can be missing from the other platform's export, so the Prism instance is the complete one. |
| `skycraft-core-jar` | Just the Skycraft Core mod. |

Full instructions, including hosting with friends through **Essential** and running a dedicated server, are in
[docs/INSTALL.md](docs/INSTALL.md).

The packwiz pack in `pack/` holds every third-party mod and config, but not Skycraft Core itself (that jar is a build
output). Use the CI downloads above, or see [docs/DEVELOPING.md](docs/DEVELOPING.md) to build them yourself.

## Controls (rebind in Options → Controls → Key Binds → Skycraft)

These are the defaults for new players (shipped through Default Options). Skycraft moves any of its keys that clash
with another mod's to a free key on first launch.

| Key | Action |
| --- | --- |
| **E** | Skyrim inventory |
| **Tab** | Menu hub: Skills ↑, Items ←, Magic →, Map ↓, Journal |
| **K** | Skills: look to the heavens, spend perk points, choose Health/Magicka/Stamina on level up |
| **J** | Journal: quests, factions, party, reputation, stats, bounties |
| **P** | Magic menu: equip spells, shouts and powers |
| **M** | Skyrim map: fast travel to discovered locations and party members |
| **Z** | Shout or use the equipped power (racial and Standing Stone powers too); hold longer for more Words of Power |
| **Q** / mouse wheel | Favorites. **1–8** use your favorite hotkeys |
| **Left mouse** | Attack or cast the right-hand spell. Hold to power attack |
| **Right mouse** (hold) | Block with your weapon or shield, or cast the left-hand spell |
| **F** | Interact: talk, loot, use |
| **Left Alt** | Dodge roll (perk-unlocked) |
| **H** | Call your horse |
| **I** | Wait (beds also open the sleep menu, at any time of day) |
| **Y** | Reputation and faction regalia |
| **`** | Player list (vanilla Tab) |
| Sneak + use on an NPC | Pickpocket |
| Sneak + use on a corpse | Drag the body |

Unbound by default (set them in Controls if you want them): ready/sheathe weapon, the Standing Stone power key, vanilla
drop, swap hands, advancements and hotbar slot 9, and Oculus's shader keys (shaders are in Video Settings →
Shader Packs).

## How Skyrim maps onto Minecraft

See [docs/FEATURES.md](docs/FEATURES.md) for the full feature guide.

## Developing

See [docs/DEVELOPING.md](docs/DEVELOPING.md). `cd mod && ./gradlew build` builds the mod; CI builds everything on push.

## Recent mod list changes

* **Removed:** Physics Mod (60 MB with its features switched off; Skycraft prefers its own physics), HT's TreeChop
  (Skycraft fells trees itself), Easy NPC (unused; Skycraft has its own NPCs), and an invalid Tectonic config
  (Tectonic itself stays, with its defaults).
* **Added for performance:** ImmediatelyFast, Krypton, Clumps, Saturn, Dynamic FPS and Structure Layout Optimizer.
  ModernFix and Entity Culling were updated.
* **MCA Reborn** is pinned to its latest stable release (7.6.26) instead of a 7.7 beta.
