# T9 — 3D Regional Alchemy Flora (All 54 Plants/Fungi), Regrowing Forage System, Predator AI Fix & Living Monster/Animal Dens

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **CRITICAL BUG in `Harvesting.java` (`lines 187–292, 417–432`):** Harvesting (`[F]`) or breaking any block in `#skycraft:harvestable_flora` cancels the vanilla drop pipeline and manually gives `new ItemStack(block.asItem(), count)`—**completely bypassing Forge Global Loot Modifiers (`forage_fungi.json` and `forage_plants.json` NEVER fire!)**—and calls `removeFlora()`, permanently deleting wild flowers/mushrooms from the world instead of leaving a regrowing state.
2. **CRITICAL BUG in `FaunaBehaviors.java` (`lines 46–64`):** `applyWildFleeGoal(Animal animal)` attaches `AvoidEntityGoal<>(animal, Player.class, ...)` at priority 2 to **every `Animal` subclass** without checking if it is a predator—causing `BearEntity`, `SabreCatEntity`, and `SkyBeast` predators (`wolf`, `senche_tiger`, `kagouti`, `alit`, `nix_hound`, `durzog`, `realm_stalker`) to flee from players!
3. **50 of the 54 plant/fungi alchemy ingredients in `ArcaneRegistry.MORE_INGREDIENTS` (`ArcaneRegistry.java:41-49, 85-95`) are registered only as 2D inventory `Item`s with zero 3D worldgen plant blocks.**
4. **`Builders.animalDen()` (`scatter/Builders.java:338-365`) builds an empty 7×7×3 dirt mound with zero spawned creatures, no block entity, and no territorial/sleeping den AI.**

`T9` fixes both bugs, registers 3D worldgen blocks for all 50 regional alchemy plants/fungi scattered by macro/micro ecology, and turns wilderness animal/monster dens into living creature lairs.

## 2. Write Set

* `mod/src/main/java/com/skycraft/survival/Harvesting.java`
* `mod/src/main/java/com/skycraft/fauna/FaunaBehaviors.java`
* `mod/src/main/java/com/skycraft/crafting/arcane/{ArcaneRegistry,IngredientPlantBlock}.java`
* `mod/src/main/java/com/skycraft/tamriel/biome/blocks/BiomeBlocks.java`
* `mod/src/main/java/com/skycraft/tamriel/biome/flora/GroundCoverFeature.java`
* `mod/src/main/java/com/skycraft/scatter/{Builders,DenBlock,DenBlockEntity}.java`
* `mod/src/main/resources/assets/skycraft/{blockstates,models,textures}/**`
* `mod/src/main/resources/data/skycraft/tags/blocks/harvestable_flora.json`

## 3. Implementation Steps

1. **Fix `Harvesting.java` Loot Modifiers & Regrowing Flora (`Harvesting.java`):**
   * When harvesting a block in `#skycraft:harvestable_flora` on a `ServerLevel`, evaluate `Block.getDrops(state, level, pos, be, player, tool)` (which invokes `forage_fungi.json` and `forage_plants.json` Global Loot Modifiers!) in addition to the plant's primary ingredient drop.
   * Instead of calling `removeFlora(level, pos)` to set `Blocks.AIR`, transition `IngredientPlantBlock` (and harvested wild flowers/mushrooms) to `HARVESTED = true` (rendering a picked stem/base that regrows via `randomTick` after ~2–3 in-game days).
2. **Fix Predator Flee Bug in `FaunaBehaviors.java` (`lines 46–64`):**
   * In `FaunaBehaviors.applyWildFleeGoal(Animal animal)`, return immediately if `animal instanceof BearEntity || animal instanceof SabreCatEntity || animal instanceof com.skycraft.creatures.entity.SkyBeast || animal instanceof net.minecraft.world.entity.NeutralMob` so predators never flee from players.
3. **Register 50 3D Regional Alchemy Plant/Fungus Blocks & Micro-Biome Scatter (`ArcaneRegistry.java`, `BiomeBlocks.java`, `GroundCoverFeature.java`):**
   * Register `IngredientPlantBlock` (supporting surface soil, marsh mud, cave stone, tree-trunk/hanging, and coastal substrates, plus optional bioluminescent light level `4..9` for glowing fungi/nirnroot) for all 50 plant/fungi ingredients in `ArcaneRegistry.MORE_INGREDIENTS` (including `crimson_nirnroot` in Blackreach/deep Dwemer caverns, `imp_stool`, `namiras_rot`, `white_cap`, `bleeding_crown`, `mora_tapinella`, `glowing_mushroom`, `blisterwort`, `fly_amanita`, `scaly_pholiota`, `canis_root`, `creep_cluster`, `elves_ear`, `jazbay_grapes`, `juniper_berries`, `hanging_moss`, `swamp_fungal_pod`, `thistle_branch`, `giant_lichen`, `poison_bloom`, `trama_root`, `ashen_grass_pod`, `ash_creep_cluster`, `comberry`, `marshmerrow`, `saltrice`, `hypha_facia`, `bittergreen_petals`, `gold_kanet`, `ash_yam`, `moon_sugar`, `rot_scale_fungus`, `kresh_fiber`, etc.).
   * Generate blockstates, cross/bush/mushroom/hanging models, and textures for all 50 blocks, and wire them into `GroundCoverFeature.java` (surface micro-biomes by province, moisture, heat, elevation, and road distance) and `CavernDress.java` (subterranean caves by cave theme).
4. **Living Monster & Animal Dens (`DenBlock.java`, `DenBlockEntity.java`, `Builders.animalDen()`):**
   * Register `skycraft:creature_den` (`DenBlock` + `DenBlockEntity`) and place it inside `Builders.animalDen()` and wilderness den/nest scenes.
   * When a player approaches within 36 blocks of an unpopulated den, `DenBlockEntity` spawns 1–3 region-appropriate den residents (bears, wolves, sabre cats, trolls, giant spiders, chaurus, spriggans, kagouti, nix-hounds, senche-tigers, durzogs) bound to `homePos` (`radius = 24`), with nocturnal/diurnal sleep routines inside the den and a bone/web/nest loot cache.

## 4. Verification Gates

* `py -3 -I tools/qa/asset_audit.py` and `py -3 -I tools/audit_items.py` -> `0 problems`, `0 gaps`.
* Server selftest verifies:
  * Harvesting `brown_mushroom` / `red_mushroom` / `IngredientPlantBlock` triggers `forage_fungi` / `forage_plants` drops and leaves a regrowing block state (never `Blocks.AIR`),
  * `BearEntity`, `SabreCatEntity`, and `SkyBeast` have `0` `AvoidEntityGoal` goals targeting `Player`,
  * `DenBlockEntity` spawns resident fauna tethered to the den coordinate.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
