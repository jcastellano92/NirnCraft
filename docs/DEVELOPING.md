# Developing NirnCraft Core

NirnCraft Core (`mod/`) targets **Minecraft 26.3 / NeoForge 26.3.0.90 / Java 25**, with official Mojang mappings.
The doc map is [README.md](README.md); runtime controls are in [NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md) and
server packaging in [SERVER-26.3.md](SERVER-26.3.md). Tests run through the harness
([tools/harness/README.md](../tools/harness/README.md)). Builds and tests run locally; GitHub Actions is disabled in
the repository settings, so pushes do not run CI. The renderer and shader pack (Infinite ATLAS): start at
[ATLAS-STATUS.md](ATLAS-STATUS.md) (where it stands, the order of work, the rules), then [ATLAS.md](ATLAS.md) and
[native/README.md](../native/README.md). Every session keeps the resource rules in [AGENT-RULES.md](AGENT-RULES.md)
(one heavy job at a time, heavy outputs off C:, the dev client closed after tests).

## Architecture

`NirnCraft.java` (`@Mod("nirncraft")`) registers the common config. It then calls each module's `init(modBus)` in
this order:

1. `ModItems`, `ModEffects`, `ModCreativeTab`, `AddTableModifier`
2. magic, creatures, economy, crime, quest, crafting, world, roads, inventory, survival, lore, fauna, dungeons,
   society, arsenal, atmosphere, tamriel, audio, guide

After that it registers the `PlayerData` and `PlacedBlocks.Store` capabilities and calls `SkyNetwork.init()`.

Some modules are started by another module rather than by `NirnCraft.java`:

* `visual` by `AtmosphereModule`;
* `crafting.arcane` by `CraftingModule`;
* `homestead` by `SurvivalModule`;
* `DungeonsRegistry` by `DungeonsModule`;
* `TamrielRegionsSetup` by `TamrielModule`.

### Core packages (no Module class)

| Package | Purpose |
| --- | --- |
| `core` | `PlayerData` / `SkyData` capability, `Skill`, `Race`, `Currency`, `Holds` (a facade over `tamriel.Regions`), `Buffs`, `Notifier`; `/nirncraft`, `/gold`, `/pay`, `/withdraw` |
| `perk` | `Perk` + `Perks`: every constellation (ids are `<skill>.<perk>`) |
| `skills` | `Progression` (skill XP, skill-ups, level-ups, legendary), `LifeSkills`, Acrobatics, Cookery, Polearms, Riding |
| `vitals` | Magicka/Stamina/Health regen and costs, `RacePowers`, `RacePassives`, `ActionHandler` (key actions) |
| `combat` | `CombatHandler` (weapon, armour, block, sneak and archery rules), `Stealth`, `Sheathe`, death and respawn, legendary prefixes |
| `dig` | `DiggingRules` (perk-gated digging), `PlacedBlocks` |
| `dialogue` | The NPC conversation hub that modules add topics to |
| `loot` | The `nirncraft:add_table` global loot modifier |
| `network` | `SkyNetwork` (one channel), `CorePackets`, `NotifyKind` |
| `registry` | `ModItems`, `ModEffects`, `ModCreativeTab` |
| `client` | `ClientSetup` (keys, HUD overlays), `SkyKeys`, `KeyConflictResolver`, `RaceSkins`, `DialogueCamera` |
| `client/hud` | Compass, compass markers, the HUD bars, target bar, sneak eye, crosshair, notifications |
| `client/screen` | Race (character creator), Skills, Hub, Character Status and Dialogue screens; `title/` has the title, create-world, load, loading, options and pause screens |
| `client/autotest` | `ClientAutotest`, `RaceShowcase` (see [In-game test hooks](#in-game-test-hooks)) |

### Feature modules

| Module | Purpose | Notable subpackages |
| --- | --- | --- |
| `magic` | Spells and tomes, casting, Shouts and Words of Power, Word Walls, dragon souls, spellmaking | `spell` (+`spellmaking`), `shout`, `bound`, `wordwall` |
| `creatures` | Bestiary, leveled enemies, dragons, guards, corpses, region spawning | `entity` (+`anim`), `ai`, `spec` (data-driven creature specs), `spawn` (`RegionSpawner`, spawn tables), `dragons` (lairs, named dragons), `travel` (silt strider ports), `client/rig` (plain-Java rig kit) |
| `economy` | Item values, barter, merchants, trainers, skill books, houses for sale, Speech | |
| `crime` | Bounties, guards and arrest, the `nirncraft:jail` dimension, theft, pickpocketing, lockpicking | |
| `quest` | Radiant quests, factions, parties, the journal; data-driven story quests | `story` (reads `resources/nirncraft/quests/*.json`), `party` |
| `crafting` | Ores, ingots, smithing tiers, stations, tempering | `arcane` (enchanting, alchemy, soul gems; its own `ArcaneModule`) |
| `world` | Discovery, compass sources, the map screen, fast travel, sleep and wait, calendar, music controller | |
| `roads` | Road network (A* over noise heights), paver, signposts, travellers and caravans, carriages | |
| `inventory` | The Skyrim inventory screen, item weights, carry weight, favorites, persistent drops | |
| `survival` | Food, cooking, inns, diseases, shrines, ore veins, Survival Mode, fishing, camping, player corpses | `mode`, `fishing`, `camping`, `corpse`, `cooking`, `inn` |
| `homestead` | Hearthfire plots, drafting table, building plans and materials (API: `Homesteads`) | |
| `lore` | Standing Stones and All-Maker stones, readable books and notes (`BookScreen`) | |
| `fauna` | Wildlife, livestock, insects, horses, stables, wild-horse taming | |
| `dungeons` | One `nirncraft:dungeon` structure type themed per JSON: named sites, long sites (the 7,000 Steps, Blackreach), random dungeons, Nordic puzzles, Dwemer automatons | `world` (rooms, kits, cultures, sites, landmarks), `puzzle` |
| `society` | `nirncraft:npc`: roles, cultures, names, looks, schedules, barks, relationships, followers, marriage, reputation, hit squads, encounters, PvP rules | |
| `arsenal` | Leveled loot (`nirncraft:leveled_gear`), artifacts, arrows and bolts, crossbows, staves, staff enchanting, polearms, kill cams | |
| `atmosphere` | Sky renderer (Masser, Secunda, aurora, constellations), regional weather, music events | `weather` |
| `visual` | Ambient particles, HUD and menu styling, loading screen and tips | |
| `audio` | Province soundtracks, ambience engine, reverb and occlusion, sound events (`AudioSounds`) | |
| `tamriel` | World generation and geography (below) | `terrain`, `biome`, `settlement` |
| `guide` | *The Traveler's Handbook* (item, first-join gift, `HandbookScreen`), tutorial hints (`TutorialHints`), the `/handbook` client command | `client` |

### The Tamriel worldgen pipeline

1. **Map sampler.** `TamrielMap` is a static, thread-safe, allocation-free sampler; block 0, 0 is Whiterun. It fills
   a `MapSample` from `terrain/MapRaster`, which reads the PNG rasters in `resources/nirncraft/tamriel/` (regions,
   elevation, features, terrain, water, coast). These are class-path resources, not datapack files, because both
   the map screen and worldgen need them before datapacks load.
2. **Beyond the map.** `terrain/Beyond` answers for everything outside the raster: the fixed foreign lands, then open
   ocean (the former endless super-cell lands are off, `Beyond.ENDLESS_LANDS_ENABLED = false`, kept for tools).
   `MapNoise` is seed-independent noise.
3. **Density functions.**
   * `terrain/TamrielChannel` (`nirncraft:tamriel_channel`) feeds map channels into the noise router: continentalness,
     erosion, ridges, elevation, river and lake.
   * `terrain/TamrielHeight` (`nirncraft:tamriel_height`) is the map shape plus seeded hills, ridges, crags, dunes and
     river/lake carving.
   * `terrain/MapWaterFeature` (`nirncraft:map_water`) fills lakes and rivers above sea level.
   * The world preset `normal` (and `large_biomes`) points at `nirncraft:tamriel` noise settings. The overworld
     dimension type is raised to y −64..639 (height 704; the terrain noise fills to y 575).
4. **Biomes.**
   * `biome/TamrielBiomeSource` (`nirncraft:tamriel`) picks the region from a domain-warped position, then the
     terrain context, then a weighted pick from that region's palette (`biome/Palettes`, `palettes.json`).
   * Giant features (Graht-oaks, Hist trees, Telvanni mushrooms, lava vents, hot springs...) write through `GenBox`,
     which keeps them inside the 3×3-chunk region.
5. **Regions and places.**
   * `Region` / `Regions` load `regions.json`: index 0 is the sea, and negative indices are Beyond.
   * `Place` / `Places` load `places/index.json` and the files it lists.
   * `PlacesPlacement` (`nirncraft:places`) starts a structure in the anchor chunk of a matching place.
6. **Settlements.**
   * `settlement/SettlementStructure` (`nirncraft:settlement`) and `DistrictPlacement` build them.
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
* **Data.** Quests are in `resources/nirncraft/quests/`, the map in `resources/nirncraft/tamriel/`, regional spawns in
  `data/nirncraft/region_spawns/`, item values and weights in `data/<ns>/nirncraft_values|nirncraft_weights/`, and
  biomes, features and structures in `data/nirncraft/worldgen/`.

## Module rules

* Each module lives in `com.nirncraft.<module>` and fills in `<Module>Module.init(IEventBus modBus)` (register its own
  `DeferredRegister`s, mod-bus listeners) and `<Module>Module.registerPackets()` (register packets through
  `SkyNetwork.register(...)` in a fixed order).
* Game-bus events: use `@EventBusSubscriber(modid = NirnCraft.MODID)` classes. Client-only code goes in
  `com.nirncraft.<module>.client` with `value = Dist.CLIENT`. S2C packet handlers must reach client code through
  `Sides.runOn(Dist.CLIENT, () -> () -> SomeClientClass.handle(...))` (`com.nirncraft.platform.Sides`).
* Every NirnCraft item automatically appears in the NirnCraft creative tab — don't add your own tab.
* Language: write **only** `mod/src/main/lang/nirncraft/<module>.json` (and `mod/src/main/lang/minecraft/<module>.json`
  to rename vanilla things). Never edit another module's file or `core.json`.
* Textures: generate pixel art with a Python/PIL script `tools/textures/<module>.py` that writes PNGs into
  `mod/src/main/resources/assets/nirncraft/textures/...`; run it and keep the PNGs.
* No mixins, no access transformers, no third-party compile dependencies. Optional integrations go through tags
  with `{"id": "othermod:thing", "required": false}` entries.
* Global loot modifiers: NeoForge discovers every `data/nirncraft/loot_modifiers/*.json` and orders them by priority;
  the authored order is kept in `data/neoforge/nirncraft_legacy` and converted to priorities by
  `tools/data_format_263.py` (see [WORLDGEN-26.3.txt](WORLDGEN-26.3.txt)). The list is owned by the core. Its entries are
  `nirncraft:gold` (core), `nirncraft:spell_tomes` (magic), `nirncraft:skyrim_gear`, `nirncraft:smithing_materials`
  (crafting), `nirncraft:soul_gems`, `nirncraft:alchemy_ingredients` (crafting/arcane), `nirncraft:lockpicks` (crime), `nirncraft:skill_books` (economy). Each owner writes
  `data/nirncraft/loot_modifiers/<name>.json` (type `nirncraft:add_table`, see `loot/AddTableModifier.java`) and the
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

1. **Soul gems** (crafting owns): `com.nirncraft.crafting.SoulGems.tryCaptureSoul(ServerPlayer player, LivingEntity victim) -> boolean`.
   Magic calls it when a creature under its `nirncraft:soul_trap` effect dies.
2. **Dragons** (creatures owns the entity): every dragon is in entity tag `#nirncraft:dragons`. Magic listens to
   `LivingDeathEvent` for that tag and performs dragon-soul absorption.
3. **Guards** (creatures owns the entity `nirncraft:guard`): all guards are in `#nirncraft:guards` and `#nirncraft:talkers`.
   Crime decides when guards become hostile and provides the arrest dialogue topics.
4. **Item values** (economy owns the loader): data files `data/<ns>/nirncraft_values/<any>.json` with
   `{"values": {"minecraft:diamond": 250, "nirncraft:ebony_ingot": 150}}`. Crafting ships values for its items.
5. **Crime state** (crime owns): `data.module("crime")` keys `murders` (int), `assaults` (int), `items_stolen` (int),
   `pickpockets` (int), `bounty` (compound holdId -> int). Stolen ItemStacks carry boolean NBT `nirncraft_stolen`.
6. **Factions** (quest owns): `data.module("quest").getCompound("factions")` maps faction id -> rank (int, absent = not a
   member). Faction ids: `companions`, `college`, `thieves_guild`, `dark_brotherhood`, `imperial_legion`,
   `stormcloaks`, `bards_college`.
7. **Creature ids** (creatures owns): `nirncraft:bandit`, `nirncraft:bandit_chief`, `nirncraft:draugr`,
   `nirncraft:draugr_deathlord`, `nirncraft:skeever`, `nirncraft:troll`, `nirncraft:giant`, `nirncraft:dragon`,
   `nirncraft:guard`, `nirncraft:corpse`. Quests spawn them by registry id.
8. **Compass sources**: `quest` (quest targets), `locations` (world module).
9. **Well Rested** is applied by the world module's sleep system (`ModEffects.WELL_RESTED`).
10. **Jail** dimension `nirncraft:jail` belongs to crime.
11. **Dragonrend** (magic sets, creatures reads): `entity.getPersistentData().getLong("nirncraft_dragonrend_until")` holds the
    game time until which a dragon is forced to land and can't fly.
12. **Item values API** (economy owns): `com.nirncraft.economy.ItemValues.get(ItemStack) -> int` (base gold value, never
    negative; includes enchantments/quality). Crime uses it for theft bounties.
13. **Creature drops** (crafting owns the items, creatures' loot tables reference them by id): `nirncraft:dragon_bone`,
    `nirncraft:dragon_scale`, `nirncraft:daedra_heart`, `nirncraft:giants_toe`, `nirncraft:troll_fat`, `nirncraft:skeever_tail`,
    `nirncraft:bone_meal_draugr` (Draugr dust), `nirncraft:hide` (animal hide), `nirncraft:dwarven_scrap`.
14. **Known spells & shouts** (magic owns): `data.module("magic").getList("spells", Tag.TAG_STRING)` (spell ids) and
    `data.module("magic").getCompound("words")` (shout id -> number of words learned), `getInt("dragon_souls")`.
15. **Merchant stock tags** (economy owns `#nirncraft:merchant/*`): other modules publish goods in their own item tags
    that economy includes optionally: `#nirncraft:smithing_goods` (crafting), `#nirncraft:alchemy_goods`,
    `#nirncraft:arcane_goods` (crafting/arcane), `#nirncraft:spell_tomes` (magic), `#nirncraft:thief_goods` (crime).
16. **Quest markers** (quest owns): `data.module("quest").getList("markers", Tag.TAG_COMPOUND)`, each
    `{x, y, z, dim, label}`; the world map draws them.
17. **Sub-module**: `com.nirncraft.crafting.arcane.ArcaneModule` (enchanting, soul gems, alchemy) is initialised from
    `CraftingModule`; the `SoulGems` contract class lives at `com.nirncraft.crafting.SoulGems` and is owned by arcane.

## Building

```
py -3 tools/dev/build_263.py --offline build      # drop --offline the first time (needs the NeoForge and Mojang mavens)
```
The jar lands in `build/libs/` of the scratch build directory (`NIRNCRAFT_263_SCRATCH`, machine default
`E:\NirnCraft-scratch\migration-26.3`), not in `mod/build/libs/`.

* **Toolchain.** ModDevGradle 2.0.148 (`net.neoforged.moddev`), the Gradle wrapper, Java 25, official Mojang names,
  NeoForge 26.3.0.90. There are no compile dependencies besides NeoForge. `build_263.py` (or `build_263.ps1`) selects
  Java 25, an isolated Gradle home and the scratch build directory, and allows one build at a time.
* **Run configurations** (game directory `mod/run/26.3`, or `-PnirncraftRunDir`): MDG's `runClient` / `runServer`
  exist, but use the supported loops instead: `tools/dev/devserver.ps1` for the persistent dev server (hot-swap,
  inbox commands) and the harness for the client (the disposable Prism instance).
* **JUnit** runs through MDG's loader-backed `unitTest` setup; logs land under the scratch build's `minecraft-junit/`.
* **API signatures.** Read the patched 26.3 sources: `moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar` under
  the scratch build directory.

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
     the anchors in `resources/nirncraft/tamriel/places/provinces.json`.
   * Writes the map PNGs (regions, elevation, features, terrain, water, coast), `regions.json` and
     `lang/nirncraft/tamriel_regions.json`, plus preview PNGs in `tools/worldgen/`.
   * It exits 1 if a place anchor lands in the wrong region. The bounds must match `TamrielMap`.
2. `python3 tools/worldgen/biomes.py`
   * Generates the biome catalogue from one declarative table: `data/nirncraft/worldgen/biome/*.json`, the biome
     features, biome tags (NirnCraft, vanilla, Forge and `c`), `palettes.json`, `lang/nirncraft/tamriel_biomes.json`
     and `tools/worldgen/generated/surface_rule.json`.
   * It rewrites its own output folders, so never hand-edit a generated biome.
3. `py -3 tools/worldgen/noise_settings.py`
   * Writes the noise settings, density functions, the `normal` and `large_biomes` world presets, the raised
     overworld dimension type and the `map_water` feature.
   * It inlines `surface_rule.json`, so run it after `biomes.py`.

### Other generators

| Script | Generates |
| --- | --- |
| `py -3 tools/landmarks.py <markers.json>` | `places/skyrim_landmarks.json`, `tamriel_shrines.json`, `index.json`, `lang/nirncraft/landmarks.json`, the dungeon structures and structure sets, and their tags. The input is a private marker extraction (names and positions only) that is not in the repository. |
| `node tools/quest_lang.js` | Validates `resources/nirncraft/quests/*.json` and writes `lang/nirncraft/quest_story.json`. Run it after editing any quest. |
| `python3 tools/gen_core_lang.py` | `lang/nirncraft/core.json` |
| `python tools/gen_bettercombat.py` | Better Combat weapon attributes, from the gear catalogue (`gear.py` also runs it) |
| `python3 tools/textures/<module>.py` | Each module's pixel art, models and related data, for example `lore.py`, `magic.py` (reads `Spells.java`), `tamriel.py` (biome blocks), `ui.py` (HUD and loading art), `atmosphere.py` (moons, galaxy, particles) and `guide.py` (the handbook icon) |
| `python3 tools/textures/gear.py [--preview DIR]` | All armour and weapons from `gear_catalogue.py`, including the generated enum constants between the `gear:` markers in `SmithingTier.java` and `SkyArmorMaterial.java` |
| `bash tools/textures/dump_rigs.sh [Group]`, then `py -3 tools/textures/bestiary*.py [name...]` | Creature rigs (`tools/textures/rigs/*.json`) and their painted textures. `rigview.py <name> [--anim walk]` previews a rig without the game. |
| `py -3 tools/music/compose.py [tracks]` | The original procedural soundtrack (`sounds/music/*.ogg`) |
| `py -3 tools/music/synth_ambience.py`, `synth_skyrim_sfx.py` | Ambient beds and one-shots, and the creature, shout, magic, UI and world sound effects |
| `py -3 tools/music/sound_catalog.py --write` / `--check` | Writes `sounds.json` and `lang/nirncraft/audio.json` from the single sound catalogue; `--check` finds missing or unused `.ogg` files |

`tools/check_assets.py` (missing item models or textures) and `tools/audit_entity_renderers.py` (every entity has a
renderer) are checks; they generate nothing. `tools/music/import_skyrim_audio.py` is the players' personal-use
importer (see [MUSIC.md](MUSIC.md)). It refuses to write inside the repository, and nothing it produces may be
committed.

## Testing

**Use the harness**: `py -3 tools/harness/harness.py run | watch | gate` ([tools/harness/README.md](../tools/harness/README.md)).
It runs every test at its own tier (`check`: audits, lints, JUnit, native suites; `lab`: Infinite ATLAS offline
renders; `live`: the disposable `NirnCraft-26.3-autotest` Prism instance through `tools/dev/live_world.py`, see
[LIVE-WORLD-PREVIEW.md](LIVE-WORLD-PREVIEW.md)) and keeps before/after results in `.work/harness`. The dev-server
loop below is for iterating on server-side code and data between harness runs.

| Tier | Command | Speed | What it covers |
| --- | --- | --- | --- |
| **Unit tests** | `py -3 tools/dev/build_263.py --offline test` (the harness's JUnit check) | < 2 min | JUnit 5 suite (`mod/src/test/java/com/nirncraft/**`): `RoadPlan` / `Profile` / `PlanGround`, `ScenePlan`, `StreetPlan`, `TamrielMap` / `Places` / `Regions` / `Beyond`, `Bake` input hashes, and `WorldSheets`. |
| **JDWP HotSwap** | `tools\dev\devserver.ps1 hotswap` | < 3 s | Compiles `mod/src/main/java` and redefines changed method bodies in the running dev server over JDWP (`127.0.0.1:5005`, `tools/dev/hotswap.py`). If a class changes fields, signatures or hierarchy, falls back automatically to `devserver.ps1 restart`. `devserver.ps1 run` prefers `hotswap` over `restart` when `.java` files change. |
| **Data reload** | `tools\dev\devserver.ps1 run "/nirncraft-dev reload <target>" -Wait` | < 1 s | Re-reads caches in-JVM (`DevReload`): `scenes` (`ScatterRules` + live `ScenePlan`), `places` (`Places` + `QuestPlaces`), `cityplans` (`CityPlans` + `Styles` + `Overlays`), `quests` (`StoryData` + `QuestPlaces`), `palettes` (`Palettes` + live `TamrielBiomeSource`), or `all`. Pair with `/nirncraft-dev regen x1 z1 x2 z2` to see worldgen changes in place. Not hot-reloaded (requires restart/rebake): map PNG rasters, `regions.json`, `hydrology.json`, `peaks.json`, baked `RoadPlan`/`StreetPlan`, and datapack registries. |
| **Dev server** | `tools\dev\devserver.ps1 run "cmd" -Wait`, `quick_corridor.ps1` | 1–15 s | In-place chunk regeneration (`/nirncraft-dev regen`), corridor grading measurement, command smoke checks. |
| **Full gates** | `tools\qa\full_corridors.ps1`, `tools\bake\bake.ps1`, `harness.py gate` | minutes | Full 4-corridor road gate, bake verification in a fresh world, and the harness gate (run once at phase end / merge). |

## In-game test hooks

Each hook is an `@EventBusSubscriber` class that does nothing unless triggered. The harness sets these flags on the
disposable Prism instance (`-Dnirncraft.autotest=true` and friends); its `live` scenes, the gallery and `/nirncraft-tour`
replace the old hand-run tours. Relative output paths land in that instance's game directory.

| Harness | Trigger | What it does | Output |
| --- | --- | --- | --- |
| `client/autotest/ClientAutotest` | `-Dnirncraft.autotest=true` | Walks through the race, skills, hub, inventory, magic, journal, map, spellmaking and staff screens, plus summoned wildlife; with the harness it is the live controller. | `build/autotest-screenshots/*.png` in the game directory |
| ″ | `-Dnirncraft.autotest.world=<name>` | Creates that creative, cheats-on world afresh (seed 1234) from the title screen, so no person is needed. | |
| ″ | `-Dnirncraft.autotest.createflow=true` (and optionally `-Dnirncraft.autotest.load=<save>`) | Creates (or loads) the world through the real New Game screens and screenshots any prompt that waits | `00_create_world_*` |
| ″ | `-Dnirncraft.autotest.tour=Name:x:z[:yaw],...` (`-Dnirncraft.autotest.tourWait=400`) | Flies to each spot and shoots it from the ground and the air | `tNN_<Name>_ground/_air` |
| `guide/client/GuideAutotest` | `-Dnirncraft.autotest.guide=true` (with `.autotest` and `.world`) | Steam Deck window (1280×800, GUI scale 3): the handbook's contents and chapters, two tutorial hints, the handbook in a 320×200 GUI. Also logs whether the first-join gift arrived. | `g_*.png` |
| `client/autotest/RaceShowcase` | `-Dnirncraft.raceshowcase=true` | Steam Deck window: character creator, race looks, townsfolk line-ups | `build/race-showcase/` |
| `society/client/NpcShowcase` | `-Dnirncraft.npcshowcase=true` or file `npc-showcase.flag` | Lines up every race and outfit | `build/npc-showcase/` |
| `visual/client/VisualAutotest` | File `nirncraft-visualtest.txt` or env `NIRNCRAFT_VISUALTEST=1` | Night sky, weather kinds, HUD, banners, loading, pause and death screens | `build/visualtest-screenshots/` |
| `survival/client/SurvivalAutotest` | File `nirncraft-survivaltest.txt` or env `NIRNCRAFT_SURVIVALTEST=1` | Survival meters, frost, fishing reel, tent, bedroll, player body, homestead | `build/survivaltest-screenshots/` |
| `crafting/client/ArmsAutotest` | File `nirncraft-armstest.txt` or env `NIRNCRAFT_ARMSTEST=1` | Every armour set on stands and every weapon | `build/armstest-screenshots/` |
| `creatures/client/CreatureGallery` | File `nirncraft-gallery.txt`, one line per shot: `<entity> [variant=N] [anim=<slot>@<ticks>] [nbt={...}]` | Poses creatures in a superflat world | `screenshots/gallery/` |
| `tamriel/settlement/client/SettlementTour` | `-Dnirncraft.tour=<world>;<name>,x,y,z,yaw,pitch;...` (`-Dnirncraft.tour.wait=500`) | Visits each settlement stop at noon | `screenshots/tour_<name>.png` |
| `creatures/ServerSmokeTest` (server) | File `nirncraft-smoketest.txt` in the server folder | Runs each line as a console command, one per tick (`wait <ticks>`, `#` comments) | `Smoke test>` log lines |
| `TamrielBiomeSource` hook | `-Dnirncraft.biomeTest=<palette>` or `grid` (env `NIRNCRAFT_BIOME_TEST`) | Forces one palette everywhere, or lays every biome out on a 48-block grid | |
| `dungeons/world/Cultures` hook | `-Dnirncraft.dungeons.province=<id>` | Makes every position report that province | |

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
configs that NeoForge copies into every new world's `serverconfig/`), and `shaderpacks/InfiniteATLAS.zip` (built by
`py -3 -I shaderpack/build_pack.py`; copy `shaderpack/build/InfiniteATLAS.zip` here, then refresh the index).

* **NirnCraft Core is not in the committed pack.** The jar is a build output: git ignores `pack/mods/nirncraft-core.jar`
  and `index.toml` never lists it. Every build injects it into a throwaway copy:
  `tools/prepare-build-pack.sh <jar> [out]` copies `pack/` to `dist/pack-build`, adds `mods/nirncraft-core.jar` and
  re-hashes the copy's index.
* **Adding a mod:** add a line to `tools/modlist.txt`, then run `tools/build-pack.sh` locally (needs packwiz) to resolve
  new entries into `.pw.toml` files. Review and commit those metadata changes explicitly. Alternatively, write the `.pw.toml` from the Modrinth API
  (same format as the existing ones: `name`, `filename`, `side`, `[download]` url + `hash-format` + `hash`,
  `[update.modrinth]` `mod-id` + `version`) and run `python3 tools/refresh_packwiz.py`.
* **Updating or removing a mod:** edit or delete its `.pw.toml` (and its `modlist.txt` line), then run
  `python3 tools/refresh_packwiz.py`. Entries whose `.pw.toml` exists are never re-resolved.
* **`side`:** `client` for rendering/UI-only mods (Sodium, Iris, Controllable…), `server` for
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
| `py -3 tools/build_prism_instance.py --core-jar PATH` | `dist/NirnCraft-26.3-client-instance.zip` without packwiz (Windows friendly); checks every hash. |
| `py -3 tools/build_mrpack.py --core-jar PATH` | `dist/NirnCraft-26.3.mrpack` for the Modrinth App (same pins, InfiniteATLAS and the core with its natives in `overrides/`). |
| `py -3 tools/build_server_instance.py --core-jar PATH --java JAVA25` | `dist/NirnCraft-26.3-server.zip`, the complete dedicated server ([SERVER-26.3.md](SERVER-26.3.md)). |
| `tools/make-instances.sh <core.jar>` | Client instance and server zips in one call (Linux/macOS wrapper over the two builders above). |
| `tools/test-server.sh` | The installed server smoke gate (`tools/qa/test_server_package_263.py`). |
| `tools\build_release.ps1` | Everything above with a verified bake and the server gate, into a fresh `dist/release-26.3-*` folder. |

### Git and generated files

Commit source, pack metadata/configs, release notes, required world-generation inputs and baked source resources.
Keep runtime logs, crash reports, caches, local saves, generated QA reports, downloaded/patched shader packs and
release archives on disk outside Git. `dist/` contains the full instance zip and test evidence; `releases/notes/`
remains tracked. The Gradle wrapper and packwiz bootstrap jars are required tooling and remain tracked.

Ignore rules do not remove already tracked files. Use `git rm --cached -- <specific generated paths>` to stop
tracking them while keeping local copies; do not use a blanket clean/reset or remove source bakes/assets.
Fetch and fast-forward the current branch before committing when upstream has advanced. Only push when the
owner asks; build/QA scripts must never create commits or push automatically.

### Optional manual workflow (`.github/workflows/build.yml`)

GitHub Actions was disabled repository-wide on 2026-10-17 at the owner's request. The preserved workflow only
supports manual dispatch, has read-only repository permissions and never commits or pushes. Running it again
requires the owner to re-enable Actions in GitHub settings. Normal development uses the local commands above.

If explicitly re-enabled and dispatched, a newer run on the same ref cancels an older one:

The workflow predates the 26.3 port (it still selects Java 17 for the `mod` job) and needs updating before use.

1. **Job `mod`** builds NirnCraft Core (`./gradlew build`), prints a compile-error summary if that fails, and uploads
   `nirncraft-core-jar`.
2. **Job `pack`** then runs these steps:
   1. It installs a pinned packwiz and resolves `tools/modlist.txt` (`tools/build-pack.sh`), which fails if any entry
      can't be resolved. Resolved metadata stays in the build workspace; it is never pushed back to the repository.
   2. It builds the client instance and the server from the build copy with the jar injected
      (`tools/make-instances.sh`). Then it exports `NirnCraft.mrpack` and `NirnCraft-curseforge.zip` from the same
      copy. Export failures are only warnings, because some mods exist on one platform only.
   3. `tools/test-server.sh` boots the server zip. It generates the spawn area, runs `locate` on villages and
      dungeons, then stops. It fails on a crash, client classes on the server, or any `com.nirncraft` stack trace.
   4. It uploads the server test logs, `NirnCraft-client-instance`, `NirnCraft-server` and
      `NirnCraft-exports` (which includes `pack/RESOLVE_REPORT.md`).

## Second-wave contracts

18. **Skill XP modifiers** (core): `Progression.registerXpModifier((player, skill) -> multiplier)` — Standing Stones,
    fortify effects, etc. multiply skill XP without touching core.
19. **Item weights** (inventory owns): data files `data/<ns>/nirncraft_weights/*.json` with
    `{"weights": {"minecraft:iron_sword": 9, "#c:ingots": 1}}`; API `com.nirncraft.inventory.ItemWeights.get(ItemStack) -> float`
    (per item) and `com.nirncraft.inventory.CarryWeight.capacity(Player)`. Other modules add carry capacity with
    `data.module("bonus").putFloat("carry", ...)` (summed by inventory, like `health`/`magicka`/`stamina`).
20. **Leveled loot** (arsenal owns): loot tables `nirncraft:chests/dungeon_common`, `nirncraft:chests/dungeon_boss`,
    `nirncraft:chests/dungeon_minor` (urns/satchels), `nirncraft:leveled/weapon`, `nirncraft:leveled/armor`, and the loot function
    `nirncraft:leveled_gear` (picks Skyrim's material tier from the looting player's level). The dungeons module places chests with
    these table ids. Global loot modifier entry `nirncraft:leveled_loot` is arsenal's.
21. **Dwarven automatons** (dungeons owns): `nirncraft:dwarven_spider`, `nirncraft:dwarven_sphere`, `nirncraft:dwarven_centurion`.
22. **Society NPCs** (society owns): one entity `nirncraft:npc` with a role (hunter, miner, lumberjack, bard, priest, beggar,
    mage, adventurer, thalmor, imperial_soldier, stormcloak_soldier, forsworn, vampire, necromancer, assassin, thug, courier,
    innkeeper, jarl, housecarl). API `com.nirncraft.society.Npcs.spawn(ServerLevel, BlockPos, String role) -> Mob`.
    Overhead speech: `com.nirncraft.society.Barks.say(LivingEntity speaker, Component text)`.
23. **Reputation** (society owns): `data.module("society").getCompound("reputation")` faction id -> int (-100..100).
24. **Diseases** (survival owns): mob effects `nirncraft:<disease>` listed in `com.nirncraft.survival.Diseases`; the arcane
    `nirncraft:cure_disease` effect, shrines and priests cure them.
25. **Standing Stones** (lore owns): `data.module("lore").getString("stone")`.
26. **Global loot modifier entries** added: `nirncraft:leveled_loot` (arsenal), `nirncraft:lore_books` (lore), `nirncraft:skyrim_food` (survival).
27. **Corpse loot**: arrows that hit a mob are recorded in its persistent data `nirncraft_arrows` (ListTag of ItemStack NBT) by
    arsenal; the creatures corpse adds them to the body's loot.

## Performance, bake and pre-generation (`com.nirncraft.perf`, `com.nirncraft.bake`, `tools/bake/`)
**Default seed.** `WorldSeed.NAME = "Nirn"` (`"Nirn".hashCode() = 2428247`). `SkyCreateWorldScreen` pre-fills the seed box
with it and uses it when the box is blank; `tools/test-server.sh` uses it. A dedicated server needs
`level-seed=Nirn`. The Tamriel map, places, road graph inputs and landmass labels do not depend on the seed (nothing in
`TamrielMap` reads one), so every cache keyed on map data is valid for any seed; only the noise fields (terrain detail,
biome noise, structures) are seeded, and those are never baked.

**Benchmark.** `tools/bake/bench.ps1 -Radius 3 -Label x [-JvmOpts "-Dnirncraft.perf.off=true"]` boots the dev server on a
fresh Nirn world, generates five fixed areas and writes `mod/run/nirncraft-perf/bench-*.txt` (chunks/s, per-stage CPU
split, hot frames; JFR file next to it). `-Dnirncraft.perf.off=true` disables the optimisations below for an A/B run.
Known caveat: the per-chunk block hashes (`compare_hashes.ps1`) differ run to run even with identical code (about half of
the interior chunks), so they cannot prove equivalence; the nondeterminism is in the full-chunk result (features), and
is present with the optimisations off. Measured (245 chunks, 5 areas, 20 cores): 11.0 -> 14.0 chunks/s, worker CPU per
chunk 1725 -> 1358 ms (surface stage 565 -> 297 ms/chunk).

**Hotspots fixed.** (1) `perf.ColumnCache` memoises `TamrielHeight.compute` per thread (the biome fill and structure
probes alternate columns, defeating `cache_2d`). (2) `perf.FastSurfaceRule` (`nirncraft:fast_surface`, wrapped around the
Tamriel surface rule by `tools/worldgen/noise_settings.py`) turns the ~100-entry biome-test sequence into a switch; the
profile showed the surface stage at ~30-40% of generation. (3) `Places.overlapping` is bucket-indexed (verified by
`/nirncraft-perf columns`). Left: `Beardifier.compute` (~17% of the noise stage, structure terrain adaptation) and the
aquifer fluid computation (vanilla).

**Bake.** `bake.Bake` loads `nirncraft/baked/<name>.bin` only when its stored input hash equals the live one (map files,
places files, and the bytecode of the classes that compute the data), otherwise computes live. Baked now: the landmass
raster labels and off-map island labels (`roads.Landmass`). Regenerate with `tools/bake/bake.ps1` (boots the dev server,
runs the `bake` autorun task, copies into `mod/src/main/resources/nirncraft/baked/`); `/nirncraft-bake check|write` in
game; `tools\build_release.ps1` fails when the bake is stale. Rebake and commit after changing map data, places,
or `Landmass`/`TamrielMap`/`Places`. Not baked (deferred): the road network plan (depends on world heights and is
persisted per save), the city-plan fabric cache; hydrology rasters already ship as PNG/JSON.

**Pre-generator** (`perf.Pregen`, config `nirncraft-perf-server.toml` `[pregen]`, commands `/nirncraft-pregen start|stop|status`).
A region ticket per chunk, released when the chunk is ready; progress and last positions are saved in the overworld data
`nirncraft_pregen`. Sources: a frontier ahead of each player weighted by the smoothed (about 30 s) movement vector, the
spawn spiral to `autoRadius`, spirals around remembered positions. Paused by tick time above `maxTickMs` and by players
moving faster than 12 blocks/s. Default: on for integrated servers, off for dedicated.

**Fast-travel prewarm** (`perf.Prewarm`, `PerfPackets.PrewarmProgress`, `perf.client.PrewarmOverlay`): `FastTravelHandler`
and `Carriages` load the 7x7 chunks around the destination with tickets first (the client shows "Travelling to ...",
chunks ready/needed), then complete the travel (25 s timeout fallback). `Prewarm.hold(...)` keeps an area loaded
silently. `sailing.Ferries` already requests the destination chunks at departure.

**Distant terrain.** Infinite ATLAS draws the world past the loaded chunks, and every pixel in the unified field
([ATLAS-STATUS.md](ATLAS-STATUS.md)); the server pushes authoritative column tiles, chunk sections and edits to it,
so pre-generated chunks sharpen distant views like explored ones, and with `nirncraft.atlas.clientGen` the client
generates the terrain on the GPU ([ATLAS-CLIENT-GENERATION.md](ATLAS-CLIENT-GENERATION.md)). Distant Horizons is no
longer part of the pack.

**Shipping a starter world (not implemented).** Run `/nirncraft-pregen start <radius> now` on a Nirn server until
`status` reports it finished, stop the server, zip `world/` (without `playerdata/`, `advancements/`, `stats/`) and host
it as a release asset; players unzip it into `saves/` or a dedicated server's world folder. Chunks stay valid while the
worldgen data is unchanged, so regenerate it per release.

## Release build (always includes an up-to-date bake)
`tools\build_release.ps1` runs the isolated Java 25 build/tests, verifies or refreshes bakes in a new native QA
world, builds both `NirnCraft-26.3-client-instance.zip` and `NirnCraft-26.3-server.zip`, and runs the full installed
server gate. Outputs go to a fresh timestamped release directory under `dist` (or `-OutputDir`); existing worlds
and archives are preserved. Client visuals and a real full-pack multiplayer connection remain separate gates.
The bake hash covers declared computing classes and data, so changes to those inputs invalidate older plans.
