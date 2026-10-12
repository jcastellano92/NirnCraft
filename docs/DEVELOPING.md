# Developing Skycraft Core

Skycraft Core (`mod/`) is a **Forge 1.20.1 (47.3.x)** mod, **official Mojang mappings**, **Java 17**.
GitHub Actions builds it on every push (`.github/workflows/build.yml`).

## Architecture

`Skycraft.java` (`@Mod("skycraft")`) registers the common config. It then calls each module's `init(modBus)` in
this order:

1. `ModItems`, `ModEffects`, `ModCreativeTab`, `AddTableModifier`
2. magic, creatures, economy, crime, quest, crafting, world, roads, inventory, survival, lore, fauna, dungeons,
   society, arsenal, atmosphere, tamriel, audio, guide

After that it registers the `PlayerData` and `PlacedBlocks.Store` capabilities and calls `SkyNetwork.init()`.

Some modules are started by another module rather than by `Skycraft.java`:

* `visual` by `AtmosphereModule`;
* `crafting.arcane` by `CraftingModule`;
* `homestead` by `SurvivalModule`;
* `DungeonsRegistry` by `DungeonsModule`;
* `TamrielRegionsSetup` by `TamrielModule`.

### Core packages (no Module class)

| Package | Purpose |
| --- | --- |
| `core` | `PlayerData` / `SkyData` capability, `Skill`, `Race`, `Currency`, `Holds` (a facade over `tamriel.Regions`), `Buffs`, `Notifier`; `/skycraft`, `/gold`, `/pay`, `/withdraw` |
| `perk` | `Perk` + `Perks`: every constellation (ids are `<skill>.<perk>`) |
| `skills` | `Progression` (skill XP, skill-ups, level-ups, legendary), `LifeSkills`, Acrobatics, Cookery, Polearms, Riding |
| `vitals` | Magicka/Stamina/Health regen and costs, `RacePowers`, `RacePassives`, `ActionHandler` (key actions) |
| `combat` | `CombatHandler` (weapon, armour, block, sneak and archery rules), `Stealth`, `Sheathe`, death and respawn, legendary prefixes |
| `dig` | `DiggingRules` (perk-gated digging), `PlacedBlocks` |
| `dialogue` | The NPC conversation hub that modules add topics to |
| `loot` | The `skycraft:add_table` global loot modifier |
| `network` | `SkyNetwork` (one channel), `CorePackets`, `NotifyKind` |
| `registry` | `ModItems`, `ModEffects`, `ModCreativeTab` |
| `client` | `ClientSetup` (keys, HUD overlays), `SkyKeys`, `KeyConflictResolver`, `RaceSkins`, `DialogueCamera` |
| `client/hud` | Compass, compass markers, the HUD bars, target bar, sneak eye, crosshair, notifications |
| `client/screen` | Race (character creator), Skills, Hub, Character Status and Dialogue screens; `title/` has the title, create-world, load, loading, options and pause screens |
| `client/autotest` | `ClientAutotest`, `RaceShowcase` (see [Test harnesses](#test-harnesses)) |

### Feature modules

| Module | Purpose | Notable subpackages |
| --- | --- | --- |
| `magic` | Spells and tomes, casting, Shouts and Words of Power, Word Walls, dragon souls, spellmaking | `spell` (+`spellmaking`), `shout`, `bound`, `wordwall` |
| `creatures` | Bestiary, leveled enemies, dragons, guards, corpses, region spawning | `entity` (+`anim`), `ai`, `spec` (data-driven creature specs), `spawn` (`RegionSpawner`, spawn tables), `dragons` (lairs, named dragons), `travel` (silt strider ports), `client/rig` (plain-Java rig kit) |
| `economy` | Item values, barter, merchants, trainers, skill books, houses for sale, Speech | |
| `crime` | Bounties, guards and arrest, the `skycraft:jail` dimension, theft, pickpocketing, lockpicking | |
| `quest` | Radiant quests, factions, parties, the journal; data-driven story quests | `story` (reads `resources/skycraft/quests/*.json`), `party` |
| `crafting` | Ores, ingots, smithing tiers, stations, tempering | `arcane` (enchanting, alchemy, soul gems; its own `ArcaneModule`) |
| `world` | Discovery, compass sources, the map screen, fast travel, sleep and wait, calendar, music controller | |
| `roads` | Road network (A* over noise heights), paver, signposts, travellers and caravans, carriages | |
| `inventory` | The Skyrim inventory screen, item weights, carry weight, favorites, persistent drops | |
| `survival` | Food, cooking, inns, diseases, shrines, ore veins, Survival Mode, fishing, camping, player corpses | `mode`, `fishing`, `camping`, `corpse`, `cooking`, `inn` |
| `homestead` | Hearthfire plots, drafting table, building plans and materials (API: `Homesteads`) | |
| `lore` | Standing Stones and All-Maker stones, readable books and notes (`BookScreen`) | |
| `fauna` | Wildlife, livestock, insects, horses, stables, wild-horse taming | |
| `dungeons` | One `skycraft:dungeon` structure type themed per JSON: named sites, long sites (the 7,000 Steps, Blackreach), random dungeons, Nordic puzzles, Dwemer automatons | `world` (rooms, kits, cultures, sites, landmarks), `puzzle` |
| `society` | `skycraft:npc`: roles, cultures, names, looks, schedules, barks, relationships, followers, marriage, reputation, hit squads, encounters, PvP rules | |
| `arsenal` | Leveled loot (`skycraft:leveled_gear`), artifacts, arrows and bolts, crossbows, staves, staff enchanting, polearms, kill cams | |
| `atmosphere` | Sky renderer (Masser, Secunda, aurora, constellations), regional weather, music events | `weather` |
| `visual` | Ambient particles, HUD and menu styling, loading screen and tips | |
| `audio` | Province soundtracks, ambience engine, reverb and occlusion, sound events (`AudioSounds`) | |
| `tamriel` | World generation and geography (below) | `terrain`, `biome`, `settlement` |
| `guide` | *The Traveler's Handbook* (item, first-join gift, `HandbookScreen`), tutorial hints (`TutorialHints`), the `/handbook` client command | `client` |

### The Tamriel worldgen pipeline

1. **Map sampler.** `TamrielMap` is a static, thread-safe, allocation-free sampler; block 0, 0 is Whiterun. It fills
   a `MapSample` from `terrain/MapRaster`, which reads the PNG rasters in `resources/skycraft/tamriel/` (regions,
   elevation, features, terrain, water, coast). These are class-path resources, not datapack files, because both
   the map screen and worldgen need them before datapacks load.
2. **Beyond the map.** `terrain/Beyond` answers for everything outside the raster: the fixed foreign lands and the
   endless super-cell realms. `MapNoise` is seed-independent noise.
3. **Density functions.**
   * `terrain/TamrielChannel` (`skycraft:tamriel_channel`) feeds map channels into the noise router: continentalness,
     erosion, ridges, elevation, river and lake.
   * `terrain/TamrielHeight` (`skycraft:tamriel_height`) is the map shape plus seeded hills, ridges, crags, dunes and
     river/lake carving.
   * `terrain/MapWaterFeature` (`skycraft:map_water`) fills lakes and rivers above sea level.
   * The world preset `normal` (and `large_biomes`) points at `skycraft:tamriel` noise settings. The overworld
     dimension type is raised to y −64..383.
4. **Biomes.**
   * `biome/TamrielBiomeSource` (`skycraft:tamriel`) picks the region from a domain-warped position, then the
     terrain context, then a weighted pick from that region's palette (`biome/Palettes`, `palettes.json`).
   * Giant features (Graht-oaks, Hist trees, Telvanni mushrooms, lava vents, hot springs...) write through `GenBox`,
     which keeps them inside the 3×3-chunk region.
5. **Regions and places.**
   * `Region` / `Regions` load `regions.json`: index 0 is the sea, and negative indices are Beyond.
   * `Place` / `Places` load `places/index.json` and the files it lists.
   * `PlacesPlacement` (`skycraft:places`) starts a structure in the anchor chunk of a matching place.
6. **Settlements.**
   * `settlement/SettlementStructure` (`skycraft:settlement`) and `DistrictPlacement` build them.
   * `plan/` lays them out: `Cities` holds the hand-planned capitals, and `Terrain` uses noise heights only.
   * `build/` builds the shells, interiors, works, landmarks and props. `kit/` holds the culture palettes.
   * `SettlementAnchors` and `Population` tell `society` where townsfolk live.
7. **Spawn.** `TamrielSpawn` puts new worlds on dry, level ground just north of Helgen.

All worldgen code samples noise only, never reads or writes outside the region it is generating, and never touches
`ServerLevel`-only services off the server thread.

### Files outside Java

* **Language fragments.** `mod/src/main/lang/<namespace>/<module>.json`. The `mergeLang` Gradle task merges them into
  `assets/<ns>/lang/en_us.json` at build time. A duplicate key with a different value only logs a warning, so keep
  keys module-prefixed.
* **Data.** Quests are in `resources/skycraft/quests/`, the map in `resources/skycraft/tamriel/`, regional spawns in
  `data/skycraft/region_spawns/`, item values and weights in `data/<ns>/skycraft_values|skycraft_weights/`, and
  biomes, features and structures in `data/skycraft/worldgen/`.

## Module rules

* Each module lives in `com.skycraft.<module>` and fills in `<Module>Module.init(IEventBus modBus)` (register its own
  `DeferredRegister`s, mod-bus listeners) and `<Module>Module.registerPackets()` (register packets through
  `SkyNetwork.register(...)` in a fixed order).
* Forge-bus events: use `@Mod.EventBusSubscriber(modid = Skycraft.MODID)` classes. Client-only code goes in
  `com.skycraft.<module>.client` with `value = Dist.CLIENT`. S2C packet handlers must reach client code through
  `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SomeClientClass.handle(...))`.
* Every Skycraft item automatically appears in the Skycraft creative tab — don't add your own tab.
* Language: write **only** `mod/src/main/lang/skycraft/<module>.json` (and `mod/src/main/lang/minecraft/<module>.json`
  to rename vanilla things). Never edit another module's file or `core.json`.
* Textures: generate pixel art with a Python/PIL script `tools/textures/<module>.py` that writes PNGs into
  `mod/src/main/resources/assets/skycraft/textures/...`; run it and keep the PNGs.
* No mixins, no access transformers, no third-party compile dependencies. Optional integrations go through tags
  with `{"id": "othermod:thing", "required": false}` entries.
* `data/forge/loot_modifiers/global_loot_modifiers.json` is owned by the core. Its entries are
  `skycraft:gold` (core), `skycraft:spell_tomes` (magic), `skycraft:skyrim_gear`, `skycraft:smithing_materials`
  (crafting), `skycraft:soul_gems`, `skycraft:alchemy_ingredients` (crafting/arcane), `skycraft:lockpicks` (crime), `skycraft:skill_books` (economy). Each owner writes
  `data/skycraft/loot_modifiers/<name>.json` (type `skycraft:add_table`, see `loot/AddTableModifier.java`) and the
  referenced loot table.

## Core API cheat sheet

| Need | Call |
| --- | --- |
| Player RPG data | `PlayerData data = SkyData.get(player);` |
| Per-module saved data | `CompoundTag t = data.module("magic"); ... data.markDirty();` (synced to the owning client automatically) |
| Award skill XP | `Progression.addSkillXp(serverPlayer, Skill.DESTRUCTION, useValue)` |
| Raise skill directly | `Progression.increaseSkill(serverPlayer, skill, levels)` |
| Perk checks | `Perks.has(player, "destruction.augmented_flames")`, `Perks.rank(player, id)` |
| Spend magicka/stamina | `Vitals.consumeMagicka(player, amount)`, `Vitals.consumeStamina(player, amount, partial)` |
| Combat state | `Vitals.markInCombat(player)`, `Vitals.inCombat(player)` |
| HUD popups | `Notifier.send(sp, NotifyKind.QUEST_STARTED, title, subtitle)`, `Notifier.message(sp, text)`, `Notifier.title(sp, title, sub)` |
| Gold | `Currency.balance(p)`, `Currency.take(p, n)`, `Currency.give(p, n)`, `Currency.coins(n)` |
| Stagger / paralysis | `CombatHandler.stagger(src, target, ticks)`, `ModEffects.PARALYSIS`, `ModEffects.STAGGER` |
| Timed flags | `Buffs.apply(player, "name", ticks)`, `Buffs.active(player, "name")` |
| Holds | `Holds.holdAt(level, pos)` -> `"whiterun"`, `Holds.displayName(id)` |
| NPC conversation topics | `Dialogue.registerProvider((player, npc, out) -> out.add(new DialogueOption(id, label, order, action)))`; reopen with `Dialogue.open(player, npc, greeting)` |
| Compass markers (client) | `CompassMarkers.set("quest", List.of(new CompassMarkers.Marker(pos, Shape.QUEST, 0xE8C060, "Bleak Falls", 0)))` |
| Keys (client) | `SkyKeys.CAST`, `SkyKeys.SHOUT`, ... each key has exactly one owning module (see `SkyKeys`) |

## Cross-module contracts

These names are fixed so modules can be written independently.

1. **Soul gems** (crafting owns): `com.skycraft.crafting.SoulGems.tryCaptureSoul(ServerPlayer player, LivingEntity victim) -> boolean`.
   Magic calls it when a creature under its `skycraft:soul_trap` effect dies.
2. **Dragons** (creatures owns the entity): every dragon is in entity tag `#skycraft:dragons`. Magic listens to
   `LivingDeathEvent` for that tag and performs dragon-soul absorption.
3. **Guards** (creatures owns the entity `skycraft:guard`): all guards are in `#skycraft:guards` and `#skycraft:talkers`.
   Crime decides when guards become hostile and provides the arrest dialogue topics.
4. **Item values** (economy owns the loader): data files `data/<ns>/skycraft_values/<any>.json` with
   `{"values": {"minecraft:diamond": 250, "skycraft:ebony_ingot": 150}}`. Crafting ships values for its items.
5. **Crime state** (crime owns): `data.module("crime")` keys `murders` (int), `assaults` (int), `items_stolen` (int),
   `pickpockets` (int), `bounty` (compound holdId -> int). Stolen ItemStacks carry boolean NBT `skycraft_stolen`.
6. **Factions** (quest owns): `data.module("quest").getCompound("factions")` maps faction id -> rank (int, absent = not a
   member). Faction ids: `companions`, `college`, `thieves_guild`, `dark_brotherhood`, `imperial_legion`,
   `stormcloaks`, `bards_college`.
7. **Creature ids** (creatures owns): `skycraft:bandit`, `skycraft:bandit_chief`, `skycraft:draugr`,
   `skycraft:draugr_deathlord`, `skycraft:skeever`, `skycraft:troll`, `skycraft:giant`, `skycraft:dragon`,
   `skycraft:guard`, `skycraft:corpse`. Quests spawn them by registry id.
8. **Compass sources**: `quest` (quest targets), `locations` (world module).
9. **Well Rested** is applied by the world module's sleep system (`ModEffects.WELL_RESTED`).
10. **Jail** dimension `skycraft:jail` belongs to crime.
11. **Dragonrend** (magic sets, creatures reads): `entity.getPersistentData().getLong("skycraft_dragonrend_until")` holds the
    game time until which a dragon is forced to land and can't fly.
12. **Item values API** (economy owns): `com.skycraft.economy.ItemValues.get(ItemStack) -> int` (base gold value, never
    negative; includes enchantments/quality). Crime uses it for theft bounties.
13. **Creature drops** (crafting owns the items, creatures' loot tables reference them by id): `skycraft:dragon_bone`,
    `skycraft:dragon_scale`, `skycraft:daedra_heart`, `skycraft:giants_toe`, `skycraft:troll_fat`, `skycraft:skeever_tail`,
    `skycraft:bone_meal_draugr` (Draugr dust), `skycraft:hide` (animal hide), `skycraft:dwarven_scrap`.
14. **Known spells & shouts** (magic owns): `data.module("magic").getList("spells", Tag.TAG_STRING)` (spell ids) and
    `data.module("magic").getCompound("words")` (shout id -> number of words learned), `getInt("dragon_souls")`.
15. **Merchant stock tags** (economy owns `#skycraft:merchant/*`): other modules publish goods in their own item tags
    that economy includes optionally: `#skycraft:smithing_goods` (crafting), `#skycraft:alchemy_goods`,
    `#skycraft:arcane_goods` (crafting/arcane), `#skycraft:spell_tomes` (magic), `#skycraft:thief_goods` (crime).
16. **Quest markers** (quest owns): `data.module("quest").getList("markers", Tag.TAG_COMPOUND)`, each
    `{x, y, z, dim, label}`; the world map draws them.
17. **Sub-module**: `com.skycraft.crafting.arcane.ArcaneModule` (enchanting, soul gems, alchemy) is initialised from
    `CraftingModule`; the `SoulGems` contract class lives at `com.skycraft.crafting.SoulGems` and is owned by arcane.

## Building

```
cd mod && ./gradlew build      # needs network access to Forge & Mojang maven
```
The jar lands in `mod/build/libs/`.

* **Toolchain.** ForgeGradle 6, Gradle 8.8 (wrapper), Java 17, official 1.20.1 mappings, compiled against Forge
  `1.20.1-47.3.0` (the pack runs 47.4.10). There are no dependencies besides Forge.
* **Run configurations** (working directory `mod/run`):
  * `./gradlew runClient` starts a dev client with Forge and Skycraft only.
  * `./gradlew runServer` starts a `--nogui` server. Accept the EULA in `mod/run/eula.txt`, delete `mod/run/world`
    for a fresh world, and type `stop` to end it.
* **Gradle flags don't reach the game.** Gradle's own `-D` flags never reach the forked game JVM. Pass game system
  properties through `JAVA_TOOL_OPTIONS` (see [Test harnesses](#test-harnesses)).
* **API signatures.** Check real 1.20.1 signatures with `javap -cp <the mapped forge jar> <class>`. The jar is in the
  ForgeGradle cache, under `minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.3.0_mapped_official_1.20.1/`.

## Generators (`tools/`)

Everything in the repository is generated by these scripts or written by hand: textures, models, sounds and music,
the Tamriel map, biomes and lang. Most scripts find their paths from their own location, so you can run them from
anywhere.

**Dependencies:**

* **Python 3 and Pillow** for every texture script.
* **numpy** for `tamriel_map.py`, `atmosphere.py`, `ui.py`, `rigview.py` and the music scripts.
* **scipy** for `synth_ambience.py` and `synth_skyrim_sfx.py`. It's optional for `tamriel_map.py`.
* **ffmpeg with libvorbis** for audio, found through the `FFMPEG` environment variable or `PATH`.
* **Node** for `quest_lang.js`.
* **Python 3.11 or later** for `build_prism_instance.py`.

### The world (`tools/worldgen/`); run in this order

1. `py -3 tools/worldgen/tamriel_map.py [--no-preview] [-v]`
   * Reads the hand-drawn vectors in `tamriel_vectors.json` (coasts, ranges, rivers, lakes, provinces and holds) and
     the anchors in `resources/skycraft/tamriel/places/provinces.json`.
   * Writes the map PNGs (regions, elevation, features, terrain, water, coast), `regions.json` and
     `lang/skycraft/tamriel_regions.json`, plus preview PNGs in `tools/worldgen/`.
   * It exits 1 if a place anchor lands in the wrong region. The bounds must match `TamrielMap`.
2. `python3 tools/worldgen/biomes.py`
   * Generates the biome catalogue from one declarative table: `data/skycraft/worldgen/biome/*.json`, the biome
     features, biome tags (Skycraft, vanilla, Forge and `c`), `palettes.json`, `lang/skycraft/tamriel_biomes.json`
     and `tools/worldgen/generated/surface_rule.json`.
   * It rewrites its own output folders, so never hand-edit a generated biome.
3. `py -3 tools/worldgen/noise_settings.py`
   * Writes the noise settings, density functions, the `normal` and `large_biomes` world presets, the raised
     overworld dimension type and the `map_water` feature.
   * It inlines `surface_rule.json`, so run it after `biomes.py`.

### Other generators

| Script | Generates |
| --- | --- |
| `py -3 tools/landmarks.py <markers.json>` | `places/skyrim_landmarks.json`, `tamriel_shrines.json`, `index.json`, `lang/skycraft/landmarks.json`, the dungeon structures and structure sets, and their tags. The input is a private marker extraction (names and positions only) that is not in the repository. |
| `node tools/quest_lang.js` | Validates `resources/skycraft/quests/*.json` and writes `lang/skycraft/quest_story.json`. Run it after editing any quest. |
| `python3 tools/gen_core_lang.py` | `lang/skycraft/core.json` |
| `python tools/gen_bettercombat.py` | Better Combat weapon attributes, from the gear catalogue (`gear.py` also runs it) |
| `python3 tools/textures/<module>.py` | Each module's pixel art, models and related data, for example `lore.py`, `magic.py` (reads `Spells.java`), `tamriel.py` (biome blocks), `ui.py` (HUD and loading art), `atmosphere.py` (moons, galaxy, particles) and `guide.py` (the handbook icon) |
| `python3 tools/textures/gear.py [--preview DIR]` | All armour and weapons from `gear_catalogue.py`, including the generated enum constants between the `gear:` markers in `SmithingTier.java` and `SkyArmorMaterial.java` |
| `bash tools/textures/dump_rigs.sh [Group]`, then `py -3 tools/textures/bestiary*.py [name...]` | Creature rigs (`tools/textures/rigs/*.json`) and their painted textures. `rigview.py <name> [--anim walk]` previews a rig without the game. |
| `py -3 tools/music/compose.py [tracks]` | The original procedural soundtrack (`sounds/music/*.ogg`) |
| `py -3 tools/music/synth_ambience.py`, `synth_skyrim_sfx.py` | Ambient beds and one-shots, and the creature, shout, magic, UI and world sound effects |
| `py -3 tools/music/sound_catalog.py --write` / `--check` | Writes `sounds.json` and `lang/skycraft/audio.json` from the single sound catalogue; `--check` finds missing or unused `.ogg` files |

`tools/check_assets.py` (missing item models or textures) and `tools/audit_entity_renderers.py` (every entity has a
renderer) are checks; they generate nothing. `tools/music/import_skyrim_audio.py` is the players' personal-use
importer (see [MUSIC.md](MUSIC.md)). It refuses to write inside the repository, and nothing it produces may be
committed.

## Test harnesses

Each harness is an `@Mod.EventBusSubscriber` class that does nothing unless triggered. Client harnesses run under
`./gradlew runClient`, whose game directory is `mod/run/`, so relative output paths land there. Pass system
properties through `JAVA_TOOL_OPTIONS`, for example:

```
$env:JAVA_TOOL_OPTIONS="-Dskycraft.autotest=true -Dskycraft.autotest.world=autotest"; cd mod; ./gradlew runClient
```

`tools/autotest.ps1 [-World <name>]` does the same for the plain screen walk-through.

| Harness | Trigger | What it does | Output |
| --- | --- | --- | --- |
| `client/autotest/ClientAutotest` | `-Dskycraft.autotest=true` | Walks through the race, skills, hub, inventory, magic, journal, map, spellmaking and staff screens, plus summoned wildlife. Ends with `mc.stop()`. | `mod/run/build/autotest-screenshots/*.png`, plus base64 JPEG thumbnails in stdout between `=== SCREENSHOT_START:<name> ===` markers |
| ″ | `-Dskycraft.autotest.world=<name>` | Creates that creative, cheats-on world afresh (seed 1234) from the title screen, so no person is needed. | |
| ″ | `-Dskycraft.autotest.createflow=true` (and optionally `-Dskycraft.autotest.load=<save>`) | Creates (or loads) the world through the real New Game screens and screenshots any prompt that waits | `00_create_world_*` |
| ″ | `-Dskycraft.autotest.tour=Name:x:z[:yaw],...` (`-Dskycraft.autotest.tourWait=400`) | Flies to each spot and shoots it from the ground and the air | `tNN_<Name>_ground/_air` |
| `guide/client/GuideAutotest` | `-Dskycraft.autotest.guide=true` (with `.autotest` and `.world`) | Steam Deck window (1280×800, GUI scale 3): the handbook's contents and chapters, two tutorial hints, the handbook in a 320×200 GUI. Also logs whether the first-join gift arrived. | `g_*.png` |
| `client/autotest/RaceShowcase` | `-Dskycraft.raceshowcase=true` | Steam Deck window: character creator, race looks, townsfolk line-ups | `build/race-showcase/` |
| `society/client/NpcShowcase` | `-Dskycraft.npcshowcase=true` or file `npc-showcase.flag` | Lines up every race and outfit | `build/npc-showcase/` |
| `visual/client/VisualAutotest` | File `skycraft-visualtest.txt` or env `SKYCRAFT_VISUALTEST=1` | Night sky, weather kinds, HUD, banners, loading, pause and death screens | `build/visualtest-screenshots/` |
| `survival/client/SurvivalAutotest` | File `skycraft-survivaltest.txt` or env `SKYCRAFT_SURVIVALTEST=1` | Survival meters, frost, fishing reel, tent, bedroll, player body, homestead | `build/survivaltest-screenshots/` |
| `crafting/client/ArmsAutotest` | File `skycraft-armstest.txt` or env `SKYCRAFT_ARMSTEST=1` | Every armour set on stands and every weapon | `build/armstest-screenshots/` |
| `creatures/client/CreatureGallery` | File `skycraft-gallery.txt`, one line per shot: `<entity> [variant=N] [anim=<slot>@<ticks>] [nbt={...}]` | Poses creatures in a superflat world | `screenshots/gallery/` |
| `tamriel/settlement/client/SettlementTour` | `-Dskycraft.tour=<world>;<name>,x,y,z,yaw,pitch;...` (`-Dskycraft.tour.wait=500`) | Visits each settlement stop at noon | `screenshots/tour_<name>.png` |
| `creatures/ServerSmokeTest` (server) | File `skycraft-smoketest.txt` in the server folder | Runs each line as a console command, one per tick (`wait <ticks>`, `#` comments) | `Smoke test>` log lines |
| `TamrielBiomeSource` hook | `-Dskycraft.biomeTest=<palette>` or `grid` (env `SKYCRAFT_BIOME_TEST`) | Forces one palette everywhere, or lays every biome out on a 48-block grid | |
| `dungeons/world/Cultures` hook | `-Dskycraft.dungeons.province=<id>` | Makes every position report that province | |

**Adding a screenshot to `ClientAutotest`.**

1. Add two cases to `runStep`: one opens the screen (`mc.setScreen(new MyScreen())`), and the next captures it
   (`captureAndSave(mc, "13_my_screen")`) and closes it.
2. Renumber the final "Completed successfully" case.

Steps run 40 ticks apart. Screens that need preset state expose a static `preselect(...)`, as `SkillsScreen`,
`MagicMenuScreen` and `HandbookScreen` do. For a longer scenario, write a step list as `GuideAutotest` and
`VisualAutotest` do. `ClientAutotest.captureAndSave` is public for that purpose.

## The modpack (`pack/`, `tools/`)

`pack/` is a [packwiz](https://packwiz.infra.link/) pack: `pack.toml`, `index.toml`, one `mods/*.pw.toml` per
third-party mod, `config/` (client and common configs, copied into `.minecraft/config`) and `defaultconfigs/` (server
configs that Forge copies into every new world's `serverconfig/`).

* **Skycraft Core is not in the committed pack.** The jar is a build output: git ignores `pack/mods/skycraft-core.jar`
  and `index.toml` never lists it. Every build injects it into a throwaway copy:
  `tools/prepare-build-pack.sh <jar> [out]` copies `pack/` to `dist/pack-build`, adds `mods/skycraft-core.jar` and
  re-hashes the copy's index.
* **Adding a mod:** add a line to `tools/modlist.txt`. CI (`tools/build-pack.sh`, which needs packwiz) resolves new
  entries into `.pw.toml` files and commits them. To skip the wait, write the `.pw.toml` yourself from the Modrinth API
  (same format as the existing ones: `name`, `filename`, `side`, `[download]` url + `hash-format` + `hash`,
  `[update.modrinth]` `mod-id` + `version`) and run `python3 tools/refresh_packwiz.py`.
* **Updating or removing a mod:** edit or delete its `.pw.toml` (and its `modlist.txt` line), then run
  `python3 tools/refresh_packwiz.py`. Entries whose `.pw.toml` exists are never re-resolved.
* **`side`:** `client` for rendering/UI-only mods (Embeddium, Oculus, shaders, Controllable…), `server` for
  server-only ones, `both` otherwise. The dedicated server zip skips client mods.
* **After any change under `pack/`** run `python3 tools/refresh_packwiz.py` (works from any directory, hashes raw
  bytes, indexes only `config/`, `defaultconfigs/`, `mods/`, `resourcepacks/`, `shaderpacks/`). `.gitattributes` marks
  `pack/**` as `-text`, so git never rewrites line endings there; keep those files LF so the hashes are the same on
  every OS.
* **Configs:** only ship keys you verified against the mod's jar (javap its config class, or read the config file it
  generates). A wrong key is silently ignored, which is worse than none.

### Building downloads locally

| Command | Result |
| --- | --- |
| `python3 tools/build_prism_instance.py [--core-jar PATH]` | `dist/Skycraft-client-instance.zip` without packwiz (Windows friendly). Uses `mod/build/libs`' newest jar unless `--core-jar` is given; checks every hash. |
| `tools/make-instances.sh <core.jar>` | Client instance and dedicated server zips through a pinned packwiz-installer (Linux/macOS, needs Java and python3). |
| `tools/test-server.sh` | Boots `dist/Skycraft-server.zip`, generates the spawn area, fails on crashes, client classes on the server or Skycraft stack traces. |
| `tools/test-client.sh` | Boots the Skycraft dev client (`./gradlew runClient`, Forge + Skycraft only) under Xvfb, fails on crashes, Skycraft stack traces or missing Skycraft models/textures. It does not test the full pack's client. |

### CI (`.github/workflows/build.yml`)

It runs on every push and on manual dispatch. A newer run on the same ref cancels an older one.

1. **Job `mod`** builds Skycraft Core (`./gradlew build`), prints a compile-error summary if that fails, and uploads
   `skycraft-core-jar`.
2. **Job `pack`** then runs these steps:
   1. It installs a pinned packwiz and resolves `tools/modlist.txt` (`tools/build-pack.sh`), which fails if any entry
      can't be resolved. On a push, it commits `pack/` as `[ci] Resolve modpack`, but only if that changed it.
   2. It builds the client instance and the server from the build copy with the jar injected
      (`tools/make-instances.sh`). Then it exports `Skycraft.mrpack` and `Skycraft-curseforge.zip` from the same
      copy. Export failures are only warnings, because some mods exist on one platform only.
   3. `tools/test-server.sh` boots the server zip. It generates the spawn area, runs `locate` on villages and
      dungeons, then stops. It fails on a crash, client classes on the server, or any `com.skycraft` stack trace.
   4. `tools/test-client.sh` boots the dev client under Xvfb (1280×800, software GL) for 60 seconds after resource
      loading. It fails on crashes, `com.skycraft` traces, or missing Skycraft models and textures. It doesn't load
      a world, so the screen walk-through runs only locally.
   5. It uploads the server and client test logs, `Skycraft-client-instance`, `Skycraft-server` and
      `Skycraft-exports` (which includes `pack/RESOLVE_REPORT.md`).

## Second-wave contracts

18. **Skill XP modifiers** (core): `Progression.registerXpModifier((player, skill) -> multiplier)` — Standing Stones,
    fortify effects, etc. multiply skill XP without touching core.
19. **Item weights** (inventory owns): data files `data/<ns>/skycraft_weights/*.json` with
    `{"weights": {"minecraft:iron_sword": 9, "#forge:ingots": 1}}`; API `com.skycraft.inventory.ItemWeights.get(ItemStack) -> float`
    (per item) and `com.skycraft.inventory.CarryWeight.capacity(Player)`. Other modules add carry capacity with
    `data.module("bonus").putFloat("carry", ...)` (summed by inventory, like `health`/`magicka`/`stamina`).
20. **Leveled loot** (arsenal owns): loot tables `skycraft:chests/dungeon_common`, `skycraft:chests/dungeon_boss`,
    `skycraft:chests/dungeon_minor` (urns/satchels), `skycraft:leveled/weapon`, `skycraft:leveled/armor`, and the loot function
    `skycraft:leveled_gear` (picks Skyrim's material tier from the looting player's level). The dungeons module places chests with
    these table ids. Global loot modifier entry `skycraft:leveled_loot` is arsenal's.
21. **Dwarven automatons** (dungeons owns): `skycraft:dwarven_spider`, `skycraft:dwarven_sphere`, `skycraft:dwarven_centurion`.
22. **Society NPCs** (society owns): one entity `skycraft:npc` with a role (hunter, miner, lumberjack, bard, priest, beggar,
    mage, adventurer, thalmor, imperial_soldier, stormcloak_soldier, forsworn, vampire, necromancer, assassin, thug, courier,
    innkeeper, jarl, housecarl). API `com.skycraft.society.Npcs.spawn(ServerLevel, BlockPos, String role) -> Mob`.
    Overhead speech: `com.skycraft.society.Barks.say(LivingEntity speaker, Component text)`.
23. **Reputation** (society owns): `data.module("society").getCompound("reputation")` faction id -> int (-100..100).
24. **Diseases** (survival owns): mob effects `skycraft:<disease>` listed in `com.skycraft.survival.Diseases`; the arcane
    `skycraft:cure_disease` effect, shrines and priests cure them.
25. **Standing Stones** (lore owns): `data.module("lore").getString("stone")`.
26. **Global loot modifier entries** added: `skycraft:leveled_loot` (arsenal), `skycraft:lore_books` (lore), `skycraft:skyrim_food` (survival).
27. **Corpse loot**: arrows that hit a mob are recorded in its persistent data `skycraft_arrows` (ListTag of ItemStack NBT) by
    arsenal; the creatures corpse adds them to the body's loot.

## Performance, bake and pre-generation (`com.skycraft.perf`, `com.skycraft.bake`, `tools/bake/`)
**Default seed.** `WorldSeed.NAME = "Nirn"` (`"Nirn".hashCode() = 2428247`). `SkyCreateWorldScreen` pre-fills the seed box
with it and uses it when the box is blank; `tools/test-server.sh` uses it. A dedicated server needs
`level-seed=Nirn`. The Tamriel map, places, road graph inputs and landmass labels do not depend on the seed (nothing in
`TamrielMap` reads one), so every cache keyed on map data is valid for any seed; only the noise fields (terrain detail,
biome noise, structures) are seeded, and those are never baked.

**Benchmark.** `tools/bake/bench.ps1 -Radius 3 -Label x [-JvmOpts "-Dskycraft.perf.off=true"]` boots the dev server on a
fresh Nirn world, generates five fixed areas and writes `mod/run/skycraft-perf/bench-*.txt` (chunks/s, per-stage CPU
split, hot frames; JFR file next to it). `-Dskycraft.perf.off=true` disables the optimisations below for an A/B run.
Known caveat: the per-chunk block hashes (`compare_hashes.ps1`) differ run to run even with identical code (about half of
the interior chunks), so they cannot prove equivalence; the nondeterminism is in the full-chunk result (features), and
is present with the optimisations off. Measured (245 chunks, 5 areas, 20 cores): 11.0 -> 14.0 chunks/s, worker CPU per
chunk 1725 -> 1358 ms (surface stage 565 -> 297 ms/chunk).

**Hotspots fixed.** (1) `perf.ColumnCache` memoises `TamrielHeight.compute` per thread (the biome fill and structure
probes alternate columns, defeating `cache_2d`). (2) `perf.FastSurfaceRule` (`skycraft:fast_surface`, wrapped around the
Tamriel surface rule by `tools/worldgen/noise_settings.py`) turns the ~100-entry biome-test sequence into a switch; the
profile showed the surface stage at ~30-40% of generation. (3) `Places.overlapping` is bucket-indexed (verified by
`/skycraft-perf columns`). Left: `Beardifier.compute` (~17% of the noise stage, structure terrain adaptation) and the
aquifer fluid computation (vanilla).

**Bake.** `bake.Bake` loads `skycraft/baked/<name>.bin` only when its stored input hash equals the live one (map files,
places files, and the bytecode of the classes that compute the data), otherwise computes live. Baked now: the landmass
raster labels and off-map island labels (`roads.Landmass`). Regenerate with `tools/bake/bake.ps1` (boots the dev server,
runs the `bake` autorun task, copies into `mod/src/main/resources/skycraft/baked/`); `/skycraft-bake check|write` in
game; `tools\qa\qa.ps1 -Server -Bake` fails when the bake is stale. Rebake and commit after changing map data, places,
or `Landmass`/`TamrielMap`/`Places`. Not baked (deferred): the road network plan (depends on world heights and is
persisted per save), the city-plan fabric cache; hydrology rasters already ship as PNG/JSON.

**Pre-generator** (`perf.Pregen`, config `skycraft-perf-server.toml` `[pregen]`, commands `/skycraft-pregen start|stop|status`).
A region ticket per chunk, released when the chunk is ready; progress and last positions are saved in the overworld data
`skycraft_pregen`. Sources: a frontier ahead of each player weighted by the smoothed (about 30 s) movement vector, the
spawn spiral to `autoRadius`, spirals around remembered positions. Paused by tick time above `maxTickMs` and by players
moving faster than 12 blocks/s. Default: on for integrated servers, off for dedicated.

**Fast-travel prewarm** (`perf.Prewarm`, `PerfPackets.PrewarmProgress`, `perf.client.PrewarmOverlay`): `FastTravelHandler`
and `Carriages` load the 7x7 chunks around the destination with tickets first (the client shows "Travelling to ...",
chunks ready/needed), then complete the travel (25 s timeout fallback). `Prewarm.hold(...)` keeps an area loaded
silently. `sailing.Ferries` already requests the destination chunks at departure.

**Distant Horizons.** DH builds LODs from chunks that are loaded or saved, so pre-generated chunks are picked up like
explored ones; no DH API is called. Its server-side generation runs through the normal chunk path of our noise-based
generator; the custom biome source is read through `getNoiseBiome`. Not verified in game. Suggested: DH distant
generation on the internal server with a modest radius and let the pre-generator do the loading (it halves its
in-flight cap when DH is installed).

**Shipping a starter world (not implemented).** Run `/skycraft-pregen start <radius> now` on a Nirn server until
`status` reports it finished, stop the server, zip `world/` (without `playerdata/`, `advancements/`, `stats/`) and host
it as a release asset; players unzip it into `saves/` or a dedicated server's world folder. Chunks stay valid while the
worldgen data is unchanged, so regenerate it per release.
