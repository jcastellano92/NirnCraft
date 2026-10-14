# Controls, menus and commands

## Keys

New players get these keys from Default Options (`pack/config/defaultoptions/keybindings.txt`). Default Options
never overrides keys you've changed. Rebind anything in **Options → Controls → Key Binds**; Skycraft's keys are under
**Skycraft**. In-game, *The Traveler's Handbook* (Controls chapter) always shows your current bindings.

| Key | Action | Notes |
| --- | --- | --- |
| **E** | Skyrim inventory | Replaces the vanilla inventory. To turn it off, set `replaceInventoryScreen` in `skycraft-inventory-client.toml`. |
| **Tab** | Menu hub | W/↑ Skills, A/← Items, D/→ Magic, S/↓ Map; Q opens the Journal; Tab closes it. |
| **K** | Skills | Look to the heavens: perks, and the level-up choice. Opens the character creator if you haven't chosen a race. |
| **Q** | Journal | Main, Side, Misc, Factions, Reputation, Stats and Party tabs. |
| **P** | Magic menu | Equip spells to either hand; shouts and powers. |
| **M** | Map | Fast travel. WASD/arrows pan, Q/E or scroll zoom, Space centres on you, L shows the legend. |
| **Z** | Shout / power | Tap for one word, hold about 0.35 s for two and 0.7 s for three. Racial powers use the same key. |
| **R** | Favorites | Quick menu. Keys **1–8** equip favorites 1–8 (when that slot has a favorite; otherwise the hotbar as usual). |
| **F** | Interact | Picks up the item you look at, talks to or loots the creature you look at, then uses or harvests the block. |
| **Left mouse** | Attack / cast right hand | Hold for a power attack (sprint-attacking is a power attack too). |
| **Right mouse** | Block / cast left hand | Hold to block with a weapon or shield. |
| **Left Alt** | Dodge roll | Needs the Athletics perk Dodge Roll. Costs 15 Stamina. |
| **H** | Call your horse | |
| **I** | Wait | Right-click a bed to sleep (any time of day). Sneak + empty hand on a bed for vanilla sleeping. |
| **Y** | Reputation | Your standing with twelve factions. |
| **=** / **-** | Set more sail / take in sail | On a ship at the helm. |
| **`** | Player list | Moved from Tab. |
| *(unbound)* | Ready / sheathe weapon | |
| *(unbound)* | Standing Stone power | The once-a-day powers of the Ritual, Serpent, Shadow and Tower stones. Bind it yourself (the code default is O). |

Also unbound by default: vanilla drop, swap hands, advancements, hotbar slot 9, and Oculus's reload, toggle and
select-shader keys (shaders are in **Video Settings → Shader Packs**).

**Clashes:** on the first launch Skycraft unbinds vanilla keys that clash with its own defaults, plus Oculus's keys.
**Skycraft's keys win** (playtest 4: Essential's chat peek sat on Z, its emote wheel on R, friends on H, zoom on C).
On every launch, any other mod's key that shares a key with a Skycraft key is unbound, saved and logged; vanilla's own
keys are only unbound when the Skycraft key is on its documented default. The first launch of layout v2 moves
Favorites Q→R, Journal J→Q, sail trim R/V→=/− (only keys still on the old defaults) and unbinds Essential's Chat Peek,
Emote Wheel, Zoom and Friends keys (`config/skycraft-client-keys.v2.done`). Fresh instances get the same from
`pack/config/defaultoptions`. A Skycraft key on its default that still clashes with a key of the player's own moves to
the first free key in this list: G J K N O P U I Y Z X B V R C , . ; ' [ ] \ - = CapsLock KP0–KP3. To rerun the
first-launch pass, delete `config/skycraft-client-keys.done` (v2: `skycraft-client-keys.v2.done`).

### Sneaking actions

| Action | Effect |
| --- | --- |
| Sneak + right-click a townsperson | Pickpocket |
| Sneak + right-click a body | Drag it |
| Attack an unaware target while sneaking | Sneak attack |

### Staying sneaked

Opening a chest, a body, a pickpocket window, the lockpicking, barter, dialogue, a book, a station or any other screen
does not make you stand up, and you are still sneaking when it closes (code: `client/SneakKeeper`).

* **Hold mode (the default).** Hold Sneak (Left Shift) when the screen opens and the sneak carries through it. Let go
  of the key and you stand, as usual. Pressing Sneak with a screen already open does not start a sneak.
* **Toggle mode** (Options, Controls, Sneak: Toggle). The toggled sneak is kept through every screen, and the
  screens never flip it. Toggle crouch is switched off once on the very first launch (the marker above) and stays
  whatever you set afterwards.
* Sneak is not carried through a screen while riding (it would dismount you), flying or swimming (it would descend or
  sink you). Opening doors, gates, trapdoors, levers and buttons works while sneaking, with or without an item in hand.

### Inside menus

| Menu | Keys |
| --- | --- |
| Inventory | W/S select, A/D change tab, E or Enter use or equip, F favorite, R sort, X inspect in 3D, Q drop (Shift+Q the stack), Tab close. The Crafting button opens the vanilla inventory (crafting grid, armour slots). |
| Lockpicking | Mouse moves the pick, A/D turn the lock |
| Books and the handbook | ←/→, A/D, Page Up/Down or the mouse wheel turn pages. In the handbook, Home or Backspace goes back to the contents. |

### Controller and Steam Deck

Controllable is preconfigured (`config/controllable.client.toml`): Steam button icons, a virtual mouse cursor in
menus, and all button hints. Every Skycraft screen fits the Steam Deck's 1280×800 screen at GUI scale 3, which is
427×267 GUI pixels.

## Player commands

| Command | What it does |
| --- | --- |
| `/handbook` | Opens *The Traveler's Handbook* (a client command; works on any server). |
| `/handbook chapter <id>` | Opens a chapter: `getting_started`, `controls`, `races`, `skills`, `magic`, `combat`, `stealth`, `economy`, `survival`, `factions`, `world`, `multiplayer`. |
| `/handbook hints reset` / `off` / `on` | Shows the tutorial hints again, or turns them off or on. |
| `/gold` | Your balance. |
| `/pay <player> <amount>` | Give Septims to another player. |
| `/withdraw <amount>` | Turn Septims into coin items. |
| `/party`, `/party create`, `invite <player>`, `accept`, `decline`, `leave`, `kick <member>`, `promote <member>`, `list`, `chat <msg>` | Parties: shared quests, rewards and map markers. |
| `/quest`, `/quest list`, `track <id>`, `untrack`, `abandon <id>` | Your quests. |

## Operator commands (permission level 2)

| Command | What it does |
| --- | --- |
| `/skycraft setskill/addskill <player> <skill> <n>`, `perkpoints <player> <n>`, `gold <player> <n>`, `resetrace <player>`, `reset <player>` | Character administration. `reset` wipes all Skycraft data for that player. |
| `/skycraft magic spells [school]`, `shouts`, `skills`, `selftest`, `learnall` | Lists spells, shouts and skills; `learnall` teaches every spell and word. |
| `/quest admin start/complete/faction/mainstage/reset ...` | Radiant and faction quest administration. |
| `/skycraft-quest stage/start/complete/skip <quest> ...`, `list`, `npc <id> summon/goto`, `goto <place>`, `flag`, `control`, `check`, `selftest` | Story quest tools. |
| `/skycraft-survival info`, `set hunger/fatigue/cold <0-300>`, `reset`, `fish` | Survival Mode tools. |
| `/skycraft-weather info`, `reroll`, `set <kind> [minutes]` | Regional weather. |
| `/skycraft-society npc <role>`, `populate <size>`, `encounter`, `squad`, `rep` | NPC and reputation tools. |
| `/skycraft-roads info/replan/repave/nearest/traveler` | Road network tools. |
| `/skycraft-tamriel where`, `sample <x> <z>`, `heightmap <x> <z> <r> [step]`, `bench` | World map tools (`heightmap` writes a PNG to `skycraft-heightmaps/`). |
| `/skybiome [pos]`, `/skybiome census <radius> <palette>` | Shows the region, palette and terrain context behind a biome. |

**Game rule:** `/gamerule skycraftSurvivalMode false` turns Survival Mode off for a world. Survival Mode also needs
`survival_mode.enabled` in `skycraft-survival.toml`.
