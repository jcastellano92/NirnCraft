# T3 — Replace All 1,718 Synthetic Grid-Stamped `_site_`/`_marker_` Places & Populate Outer/Uncharted Islands

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Across 6 provincial place files (`morrowind.json`: 562, `foreign.json`: 485, `black_marsh.json`: 228, `elsweyr.json`: 173, `valenwood.json`: 146, `summerset.json`: 124), **1,718 places (`22.7%` of all places) are synthetic `<region>_site_1..29_<variant>` and `<region>_marker_1..18_<variant>` entries stamped in uniform 96-block grid rows** (`x = 6736, 6832, 6928...`) that assign Skyrim-only variants (`bleak_falls_barrow`, `word_wall`, `dragon_mound`, `great_lift`, `kolskeggr_mine`, `nordic_ruin`, `barrow`, `giant_camp`, `ice_cave`) into Morrowind, Black Marsh, Summerset, Valenwood, Elsweyr, and Outer Realms.

`T3` replaces all 1,718 synthetic grid-stamped entries with organically positioned, lore-authentic regional sites fitted to the terrain, and populates minor islands across uncharted and outer-realm seas (`Beyond.java` + `OceanIsles.java`).

## 2. Write Set

* `mod/src/main/resources/skycraft/tamriel/places/{morrowind,foreign,black_marsh,elsweyr,valenwood,summerset,hammerfell,high_rock,cyrodiil}.json`
* `mod/src/main/resources/skycraft/dungeons/identity/*.json`
* `mod/src/main/resources/skycraft/quests/**/*.json` (if any quest references a renamed `_site_` ID)
* `mod/src/main/resources/skycraft/lore/places/*.json`
* `mod/src/main/lang/skycraft/places.json`
* `mod/src/main/java/com/skycraft/tamriel/terrain/{OceanIsles,Beyond,TamrielHeight}.java`
* `mod/src/main/java/com/skycraft/explore/MinorSites.java`
* `tools/qa/{layout_lint,dungeon_identity_gen,dungeon_identity_lint}.py`

## 3. Implementation Steps

1. **De-Grid & Culturally Re-Theme All 1,718 Synthetic `_site_`/`_marker_` Places:**
   * Replace every `<region>_site_*` and `<region>_marker_*` ID and name with lore-grounded regional names and IDs (drawing from `.reference/morrowind_ref`, `.reference/oblivion_ref`, `.reference/daggerfall_ref`, `docs/world/provinces/*.md`, and regional cultural lexicons).
   * Replace Skyrim-exclusive variants in non-Skyrim provinces with province-authentic variants:
     * **Morrowind:** `velothi_tomb`, `sixth_house_base`, `daedric_shrine`, `dwemer_ruin`, `dunmer_stronghold`, `kwama_egg_mine`, `ebony_mine`, `glass_mine`, `smuggler_cave`, `grotto`, `ashlander_camp`, `silt_strider_post`, `velothi_tower`, `Red_mountain_fumarole`.
     * **Black Marsh:** `xanmeer`, `hist_grove`, `swamp_ruin`, `kothringi_barrow`, `mud_cavern`, `smuggler_cove`, `imperial_prison_fort`, `naga_camp`.
     * **Elsweyr:** `moon_temple`, `adeptorium`, `sandstone_tomb`, `sugar_smuggler_den`, `canyon_redoubt`, `baandari_camp`, `desert_oasis`, `dragon_roost`.
     * **Valenwood:** `bosmer_ossuary`, `root_hollow`, `graht_grove`, `ayleid_ruin`, `wood_orc_camp`, `hound_den`, `canopy_shrine`.
     * **Summerset:** `altmer_sky_spire`, `elven_ruin`, `coral_cove`, `crystal_quarry`, `sload_grotto`, `sea_elf_anchorage`, `wayshrine`.
     * **Foreign Lands (`Atmora`, `Akavir`, `Yokuda`, `Pyandonea`, `Thras`):** keep `nordic_ruin`/`barrow`/`word_wall`/`dragon_mound`/`ice_cave` only in `atmora/*`; use `tsaesci_fortress`, `kamal_ice_vault`, `tang_mo_pagoda`, `ka_po_tun_shrine` in `akavir/*`; `yokudan_tomb`, `orichalc_ruin`, `desert_cave` in `yokuda/*`; `maormer_anchorage`, `sea_serpent_cove` in `pyandonea/*`; `sload_coral_spire`, `abyssal_reef` in `thras/*`.
   * Scatter coordinates organically across each region's dry terrain (sampling `elevation.png`, `coast.png`, `water.png`, `regions.png` with minimum Poisson-disc spacing and zero grid alignment).
   * Update any referencing files in `quests/`, `lore/places/`, and regenerate `dungeons/identity/*.json` via `dungeon_identity_gen.py`.
2. **Minor Islands & Archipelagos in Uncharted & Outer-Realm Seas (`Beyond.java`, `OceanIsles.java`, `places/*.json`):**
   * Add authored minor island places (atolls, sea-caves, lighthouses, castaway camps, shipwrecks, coral shrines) around the southern/western coasts (Valenwood, Elsweyr, Black Marsh, Summerset, Hammerfell) and around `yokuda`, `pyandonea`, `thras`, `atmora`, and `akavir`.
   * Extend `OceanIsles.cellIsle()` and `surfaceHeight()` so procedural micro-islands (`SEA_STACK`, `ATOLL`, `CAY`, `SANDBAR`) also form in `Beyond.java` outer-realm oceans where `coastDistance < -180`.

## 4. Verification Gates

* `0` places in `places/*.json` match `_site_\d+` or `_marker_\d+`.
* `0` places in non-Skyrim/non-Atmora provinces have variant `bleak_falls_barrow`, `word_wall`, `dragon_mound`, or `kolskeggr_mine`.
* `py -3 -I tools/qa/layout_lint.py` -> `0 errors`.
* `py -3 -I tools/qa/reach_lint.py` -> `0 isolated places`, `0 extra road networks`.
* `py -3 -I tools/qa/dungeon_identity_lint.py` -> `0 errors`.
* `py -3 -I tools/qa/worldsheet_lint.py` -> `0 errors`, `100.0%` `F-WHY`.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
