# Developing Skycraft Core

Skycraft Core (`mod/`) is a **Forge 1.20.1 (47.3.x)** mod, **official Mojang mappings**, **Java 17**.
GitHub Actions builds it on every push (`.github/workflows/build.yml`).

## Layout

```
mod/src/main/java/com/skycraft/
  Skycraft.java            mod entry point; calls every module's init()
  SkyConfig.java           common config (config/skycraft-common.toml)
  core/                    PlayerData (+capability SkyData), Skill, Race, Currency, Holds, Buffs, Notifier, commands
  perk/                    Perk + Perks (every constellation; ids are "<skill>.<perk>")
  skills/                  Progression (skill XP, skill-ups, level-ups), LifeSkills (mining/woodcutting/fishing/hunting)
  vitals/                  Vitals (magicka/stamina/health regen & costs), RacePowers, ActionHandler (key actions)
  combat/                  CombatHandler (weapon/armor/block/sneak/archery rules), WeaponClass, ArmorClass
  dig/                     DiggingRules (perk-gated terrain digging), PlacedBlocks (player-placed tracking)
  dialogue/                Dialogue hub: NPC conversation menu that modules add topics to
  loot/                    AddTableModifier: the skycraft:add_table global loot modifier
  network/                 SkyNetwork (single channel), CorePackets, NotifyKind
  registry/                ModItems (septim, coin purse), ModEffects, ModCreativeTab
  client/                  SkyKeys, HUD (bars, compass, notifications), core screens
  magic/ creatures/ economy/ crime/ quest/ crafting/ world/   feature modules
mod/src/main/lang/<namespace>/<part>.json   language fragments, merged into assets/<ns>/lang/en_us.json at build
tools/                     generators (core lang, textures)
```

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

1. Builds Skycraft Core.
2. Resolves `tools/modlist.txt` (fails if any entry can't be resolved) and commits `pack/` only if that changed it.
3. Builds the client instance and server from the build copy with the jar injected, then exports `Skycraft.mrpack`
   and `Skycraft-curseforge.zip` from the same copy (export failures are warnings: some mods exist on one platform
   only).
4. Runs the server and client smoke tests; either failing fails the build.

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
