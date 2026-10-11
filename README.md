# Skycraft

**Skycraft turns Minecraft into The Elder Scrolls V: Skyrim, and keeps going to the edge of Nirn.** You arrive as a
prisoner on a cart to Helgen. From there you level up skills by using them, look to the heavens to spend perks,
learn spells from tomes, absorb dragon souls and shout. Along the way you join factions, follow the Dragonborn's
story, trade, get thrown in jail, buy a house, and explore a whole continent with your friends.

Skycraft Core (`mod/`) is one custom Forge mod that builds all of this itself: the world, its people, creatures,
music and rules. The pack (`pack/`) adds only performance mods, a few libraries and helpers such as Essential,
Better Combat, Lootr and Controllable.

* **Minecraft 1.20.1, Forge 47.4.10, Java 17.**
* Runs on a Steam Deck-class PC. Every menu fits the Deck's 1280×800 screen.

## Highlights

* **Tamriel at real scale.** The whole continent, generated from a hand-drawn map. Skyrim is about 6,000 blocks
  across, with Whiterun at block 0, 0, and you spawn on the road outside Helgen. Cyrodiil, Morrowind, High Rock,
  Hammerfell, Black Marsh, Elsweyr, Valenwood and the Summerset Isles sit where they belong. Atmora, Akavir, Yokuda,
  Pyandonea and Thras lie beyond the seas, and past them, endless generated lands.
* **113 biomes** in 79 regions, from Whiterun's tundra and the Rift's autumn forests to Vvardenfell's ashlands,
  Valenwood's Graht-oaks and Summerset's crystal coasts. Each region has its own weather, music and danger level.
* **602 named places** at their real positions:
  * the nine hold capitals and ten provincial capitals, each built to its own layout, plus 30 more cities;
  * 266 named dungeons;
  * Word Walls, Standing Stones and dragon mounds;
  * shrines to all sixteen Daedric Princes in every province;
  * High Hrothgar, Blackreach and more.

  Generic towns, farms and culture ruins fill the gaps.
* **23 playable races**: Skyrim's ten, plus the Maormer, Reachmen, Ohmes-raht, Lilmothiit, Imga, the Akaviri
  Tsaesci, Ka Po' Tun and Tang Mo, and the lost Atmorans, Falmer, Dwemer, Ayleids and Kothringi.
* **29 skills and 255 perks** on a starfield of constellations, using Skyrim's leveling formulas.
* **115 spells in six schools** (Mysticism is back), spellmaking, staff enchanting, **26 shouts** and dragon souls.
* **98 story quests**: the full Dragonborn main quest, the Companions, the College, the Thieves Guild, the Dark
  Brotherhood, the Civil War, the Bards College, Dawnguard, fifteen Daedric quests, Solstheim and the provinces. On
  top of these come radiant favors, bounties and faction contracts.
* **A bestiary of about 80 creatures and foes**, rig-animated: dragons that shout, draugr, trolls, giants and
  mammoths, Dwemer automatons, Daedra, and the beasts of every province.
* **Townsfolk of every race**, with daily routines, relationships, followers and marriage, in settlements built by
  culture.
* **Combat and arms**: power attacks, blocking, sneak attacks and kill cams. There are 48 armour sets, 191 weapons,
  polearms, crossbows, staves and artifacts.
* **Crime and stealth**: witnesses, bounties per hold, guards, jail, lockpicking and pickpocketing.
* **Economy and homes**: barter, Speech prices, five houses to buy, and Hearthfire-style homesteads.
* **Skyrim Survival Mode**: hunger, fatigue and cold. Also fishing (52 fish), camping, diseases, and your own corpse
  to recover.
* **Sky and sound**: Masser and Secunda, auroras, regional weather, an original soundtrack for each province, an
  ambience engine, reverb, and a Skyrim-style HUD and menus.
* **Onboarding**: every new player gets *The Traveler's Handbook*, and small one-time hints explain things the
  first time they happen.

In progress, not yet in the pack: Skycraft's own vampirism and lycanthropy (these replace the Vampirism and Werewolves
mods), sailing between ports, deeper economy and alchemy integration, and terrain polish.

## Install

Every CI run on GitHub Actions produces downloads (**Actions** → latest green **Build** run → **Artifacts**):

| Artifact | Use |
| --- | --- |
| `Skycraft-client-instance` | Zip for **Prism Launcher / MultiMC**: *Add Instance → Import → zip*. Includes Forge, all mods, configs and shaders. Uses 6 GB of RAM (8 GB if you host). |
| `Skycraft-server` | A ready Forge dedicated server. Accept the EULA, then run `run.sh` / `run.bat`. |
| `Skycraft-exports` | `Skycraft.mrpack` (Modrinth App) and `Skycraft-curseforge.zip` (CurseForge App). |
| `skycraft-core-jar` | Just the Skycraft Core mod. |

**Playing with friends:** Essential is included, so the host just opens their world and invites friends from the
Essential menu. No port forwarding is needed. Full instructions, including the dedicated server, shaders and
Distant Horizons settings, updating and troubleshooting, are in [docs/INSTALL.md](docs/INSTALL.md).

## Controls

These are the defaults for new players (shipped through Default Options). Rebind them in **Options → Controls → Key
Binds → Skycraft**. If another mod already uses one of these keys, Skycraft moves its own key to a free one on first
launch.

| Key | Action |
| --- | --- |
| **E** | Skyrim inventory |
| **Tab** | Menu hub: Skills, Items, Magic, Map |
| **K** | Skills and perks; level up |
| **J** | Journal: quests, factions, party, reputation, stats |
| **P** | Magic menu: equip spells, shouts and powers |
| **M** | Map of Tamriel and fast travel |
| **Z** | Shout or use a power; hold longer for more Words of Power |
| **Q** | Favorites; **1–8** equip favorites 1–8 |
| **F** | Interact: talk, loot, pick up, use |
| **Left mouse** | Attack or cast the right-hand spell; hold to power attack |
| **Right mouse** (hold) | Block, or cast the left-hand spell |
| **Left Alt** | Dodge roll (perk) |
| **H** | Call your horse |
| **I** | Wait (right-click a bed to sleep) |
| **Y** | Reputation |
| **`** | Player list |
| Sneak + right-click an NPC | Pickpocket |
| Sneak + right-click a body | Drag it |

These are unbound by default: ready/sheathe weapon, the Standing Stone power, vanilla drop, swap hands,
advancements, hotbar slot 9, and Oculus's shader keys. The full list is in [docs/CONTROLS.md](docs/CONTROLS.md).

## Documentation

* [docs/FEATURES.md](docs/FEATURES.md): the full player guide, system by system.
* [docs/WORLD.md](docs/WORLD.md): the world of Nirn: provinces, places and travel.
* [docs/CONTROLS.md](docs/CONTROLS.md): every key, menu shortcut and command.
* [docs/INSTALL.md](docs/INSTALL.md): installing, hosting, performance and troubleshooting.
* [docs/MUSIC.md](docs/MUSIC.md): the soundtrack and sound system, and importing your own Skyrim audio.
* [docs/DEVELOPING.md](docs/DEVELOPING.md): architecture, generators, test harnesses and CI.
* [docs/TAMRIEL.md](docs/TAMRIEL.md): the world plan and the worldgen contracts.

## Credits and legal

Skycraft is a fan project. It is not affiliated with or endorsed by Bethesda Softworks, ZeniMax Media or Mojang.
*The Elder Scrolls* and *Skyrim* are trademarks of ZeniMax Media Inc.

**Every asset in this repository is original.** Textures and models are drawn by Python scripts, music and sound
effects are synthesised by Python scripts, and all text and dialogue is newly written. The geography is a hand-made
vector tracing at fan-map level of detail. No Bethesda meshes, textures, sounds, voices, music, book text or dialogue
are included, raw or converted. Place names and lore facts are used as references.

If you own Skyrim, `tools/music/import_skyrim_audio.py` can build a **private** resource pack from **your own** copy
of the game's music, sounds and Thu'um voices for your personal use (see [docs/MUSIC.md](docs/MUSIC.md)). That pack
is written outside the repository and must never be redistributed.

Third-party mods in the pack belong to their authors and are downloaded from Modrinth or CurseForge at install time.
