# T12 — Complete Regional Arsenal (`LegendaryUniques`), Combat Perks, Spellcasting NPCs & Third-Person Player/Humanoid Animations

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **CRITICAL BUG in `LegendaryUniques.java` (`lines 128–192`):** Because `ArsenalItems.POLEARMS` (`line 113`) skips all smithing tiers after `DRAGONBONE` and the 9 regional tiers in `SmithingTier.java` (`lines 48–56`) omit several weapon shapes, **11 of the 94 `LegendaryUniques` (`xanmeer_sun_cleaver`, `blackrose_warden_spear`, `silvenar_thorn_spear`, `alikr_dune_cleaver`, `tang_mo_jade_staff`, `ysgramors_first_oar_spear`, `hiradirge_stone_maul`, `morwha_oasis_spear`, `orgnums_coffer_trident`, `pyandonean_storm_glaive`, `tigers_reef_warhammer`) look up non-existent item IDs and return `ItemStack.EMPTY`!**
2. **Polearms and crossbows are excluded from `LeveledGear.java` (`lines 169–191`) and `RegionalGear.java` (`line 139`)**, and foreign continents (`akavir`, `atmora`, `yokuda`, `pyandonea`, `thras`, `realm`) have no regional treasure sets in `Collectibles.java` (`lines 133–134`).
3. **5 combat perks in `Perks.java` (`one_handed.dual_flurry`, `one_handed.dual_savagery`, `archery.steady_hand`, `archery.ranger`, `block.block_runner`) have zero gameplay implementation.**
4. **NPC mages, court wizards, necromancers, and Thalmor (`NpcGoals.Caster`, `NpcEntity.java:610`) shoot vanilla `SmallFireball`s instead of casting spells from `Spells.java`.**
5. **Wall climbing, dodge rolling, and third-person weapon sheathing (`ClientEvents.java`, `Sheathe.java`) have no third-person limb/body animations or hip/back scabbard rendering, and `CorpseEvents` despawns killed enemies on `LivingDeathEvent` before `KillCamClient` finishes.**

`T12` closes all 5 gaps across the arsenal, perk, magic, and animation systems.

## 2. Write Set

* `mod/src/main/java/com/skycraft/crafting/{SmithingTier,RegionalGear}.java`
* `mod/src/main/java/com/skycraft/arsenal/{ArsenalItems,LeveledGear,Sheathe}.java`
* `mod/src/main/java/com/skycraft/arsenal/client/{KillCamClient,SheathedWeaponLayer}.java`
* `mod/src/main/java/com/skycraft/loot/{LegendaryUniques,Collectibles,CorpseEvents}.java`
* `mod/src/main/java/com/skycraft/combat/CombatHandler.java`
* `mod/src/main/java/com/skycraft/vitals/ActionHandler.java`
* `mod/src/main/java/com/skycraft/client/ClientEvents.java`
* `mod/src/main/java/com/skycraft/society/entity/{NpcEntity,NpcGoals}.java`
* `mod/src/main/java/com/skycraft/society/client/NpcModel.java`
* `mod/src/main/java/com/skycraft/creatures/client/SkyHumanoidModel.java`
* `mod/src/main/resources/assets/skycraft/{models,textures}/item/**`
* `mod/src/main/resources/data/skycraft/weapon_attributes/*.json`
* `mod/src/main/lang/skycraft/items*.json`

## 3. Implementation Steps

1. **Register Missing Regional Weapons & Fix All 11 `LegendaryUniques` (`SmithingTier.java`, `ArsenalItems.java`, `LeveledGear.java`, `RegionalGear.java`, `Collectibles.java`):**
   * Enable full weapon coverage (`"dsamgwbh"` + polearms `spear`, `halberd`, `glaive`) across all regional/faction tiers in `SmithingTier.java` and `ArsenalItems.POLEARMS`, generating their item models, textures, lang keys, and `weapon_attributes/*.json` files.
   * Add a unit/server selftest asserting that `LegendaryUniques.create(id)` returns a non-empty `ItemStack` for all **94/94** unique IDs.
   * Include `PolearmItem` and `SkyCrossbowItem` in `LeveledGear` and `RegionalGear`, and add regional treasure sets for `akavir`, `atmora`, `yokuda`, `pyandonea`, `thras`, and `realm` in `Collectibles.java`.
2. **Wire All 5 Combat Perks (`CombatHandler.java`, `ActionHandler.java`, `ClientEvents.java`):**
   * `one_handed.dual_flurry`: increase dual-wield attack speed by `+20%` (rank 1) / `+35%` (rank 2).
   * `one_handed.dual_savagery`: multiply dual-wield power-attack damage by `1.50x`.
   * `archery.steady_hand`: when aiming a drawn bow while sneaking/zooming, apply extra FOV zoom and slow target movement / projectile drop (`25%` / `50%`).
   * `archery.ranger`: cancel or halve the movement speed slowness penalty while drawing a bow.
   * `block.block_runner`: cancel the movement speed slowness penalty while blocking with a shield or weapon.
3. **NPC & Humanoid Spellcasting from `Spells.java` (`NpcGoals.Caster`, `NpcEntity.java`, `SkyHumanoid.java`):**
   * Replace the vanilla `SmallFireball` attack in `NpcGoals.Caster` with real spell casts from `Spells.java` (`FIREBOLT`, `ICE_SPIKE`, `LIGHTNING_BOLT`, `CHAIN_LIGHTNING`, `FAST_HEALING`, `OAKFLESH`, `CONJURE_FAMILIAR`, `RAISE_ZOMBIE`) using school-appropriate cast sounds, particles, and two-handed casting arm poses.
4. **Third-Person Player/Humanoid Animations, Hip/Back Sheathed Weapons & Kill-Cam Sync (`ClientEvents.java`, `SheathedWeaponLayer.java`, `NpcModel.java`, `SkyHumanoidModel.java`, `CorpseEvents.java`):**
   * Add `SheathedWeaponLayer` rendering sheathed melee weapons on the hip/back and bows/staves/polearms on the back when `Sheathe.isSheathed()` is true, and relax the third-person right arm to `ArmPose.EMPTY` when sheathed.
   * Animate player and humanoid limbs in third person during **wall climbing** (alternating arm reach and knee lift), **dodge rolling** (forward/lateral body tuck pitch), **power-attack windups**, **shield bash**, and **spell casting** (`NpcModel.java`, `SkyHumanoidModel.java`, `ClientEvents.java`).
   * In `CorpseEvents.java`, when `KillCamClient` triggers on a fatal blow, delay despawning the dying entity into a flat `CorpseEntity` by 20 ticks so the slow-motion kill camera captures the death animation cleanly.

## 4. Verification Gates

* `94/94` `LegendaryUniques.create(id)` return `!stack.isEmpty()` (0 `ItemStack.EMPTY`).
* `py -3 -I tools/qa/asset_audit.py` and `py -3 -I tools/audit_items.py` -> `0 problems`, `0 gaps`.
* Server selftest verifies all 5 combat perks (`dual_flurry`, `dual_savagery`, `steady_hand`, `ranger`, `block_runner`) and NPC `Spells.java` casting.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
