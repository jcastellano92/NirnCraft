# T4 — World-Scale Mountain Peaks (`peaks.json`), Calderas, Graded Ascents & Monumental World Wonders

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

In Skyrim, `throat_of_the_world` in `peaks.json` (`MapPeaks.java`) is a monumental analytical peak (`height: 560, reach: 450`) with a carved `High Hrothgar` shelf (`y=362, r=52`), two graded switchback paths (the 7,000 Steps), and dedicated multi-piece world structures (`THROAT_SUMMIT`, `HIGH_HROTHGAR`, `STEPS`). Outside Skyrim, High Rock, and Hammerfell, **`peaks.json` has ZERO (`0`) peaks**, and standalone `"type": "landmark"` places like `red_mountain` (`variant: ""`), `crystal_tower_summerset` (`variant: "monument"`), and `falinesti_*_site` (`variant: "clearing"|"source"`) dispatch to `com.skycraft.dungeons.world.Landmarks.landmark()`, which builds a **7×7×10 cobblestone pillar or 9×9 log ring**! Meanwhile, `balfiera` (Isle of Balfiera) is a generic radial town lacking the **Adamantine / Direnni Tower**.

`T4` expands `peaks.json` and `MapPeaks.java` from 19 to **60+ analytical peaks, volcanic calderas, mesas, and cliff shelves across all 11 provinces**, and replaces the 7×7 landmark stubs with monumental multi-chunk world-wonder structures.

## 2. Write Set

* `mod/src/main/java/com/skycraft/tamriel/terrain/MapPeaks.java`
* `mod/src/main/resources/skycraft/tamriel/peaks.json`
* `mod/src/main/java/com/skycraft/dungeons/world/{Landmarks,DungeonStructure,SiteParams}.java`
* `mod/src/main/java/com/skycraft/tamriel/settlement/build/{Landmarks,MarqueeLandmarks,LandmarkRegistry}.java`
* `mod/src/main/resources/skycraft/tamriel/cityplans/{balfiera,vivec,aldruhn,sadrith_mora,mournhold,imperial_city,alinor,elden_root,sunspire}.json`
* `mod/src/main/resources/skycraft/tamriel/places/*.json`
* `mod/src/test/java/com/skycraft/tamriel/TamrielPlanTest.java`

## 3. Implementation Steps

1. **Caldera & Mesa Support in `MapPeaks.java` + 60+ Peaks in `peaks.json`:**
   * Extend `MapPeaks.Peak` to support optional `calderaRadius` and `calderaDepth` (depressing the summit cone inside `d < calderaRadius` into a dramatic volcanic crater rim and inner basin).
   * Add `45+` new analytical peaks across all provinces (total `>= 60` peaks):
     * **Morrowind:** `red_mountain` (`x=7100, z=-400, height=540, reach=520, calderaRadius=75, calderaDepth=65`, `ghostgate` shelf at `y=245`, `dagoth_ur` crater-rim shelf at `y=465`, and Foyada Mamaca / Foyada Bani-Dad graded ascent paths), `mount_assarnibibi`, `mount_kand`, `velothi_central_horn`, `telvanni_tooth`, `solstheim_mortrag_peak`, `hvitkald_peak`.
     * **Cyrodiil:** `dive_rock_peak` (Jerall overlook shelf at `y=410`), `frostcrag_peak` (`y=435` shelf + graded path), `cloud_ruler_peak` (`y=315` shelf + stair path from Bruma), `sancre_tor_crag`, `valus_high_horn`, `colovian_table_peak`.
     * **Summerset:** `eton_nir` (`height=510, reach=440`, `cloudrest` aerie shelf at `y=420` + graded crystal switchback path), `crystal_law_plateau`, `auridon_beacon_peak`.
     * **Valenwood:** `falinesti_canopy_mesa`, `green_sap_rise`, `tarlain_peak`, `strid_gorge_bluff`.
     * **Elsweyr:** `sunspire_peak` (`height=470, reach=400`, Dragon-Temple shelf at `y=415` + switchback pilgrim trail), `anequina_grand_mesa`, `two_moons_spire`, `quin_rawl_crag`.
     * **Black Marsh:** `helstrom_hist_dome` (broad root-mound rise), `murkmire_xanmeer_bluff`, `shadowfen_karst_needle`.
     * **Outer Realms:** `jylkurfyk_frost_horn` (Atmora), `ysgramor_watch_peak` (Atmora), `ka_po_tun_heaven_peak` (Akavir), `kamal_glacier_horn` (Akavir), `tang_mo_jade_peak` (Akavir), `orichalc_mesa` (Yokuda), `coral_kingdom_pinnacle` (Thras).
2. **Monumental World-Wonder Structures (`com.skycraft.dungeons.world.Landmarks`, `MarqueeLandmarks.java`):**
   * **Adamantine / Direnni Tower (`balfiera` / `dirennis_tower`):** Add `adamantine_tower` (25×25×180 fluted metallic-stone cylinder spire with Aedric vault entrance and Direnni courtyard) to `MarqueeLandmarks.java` and pin it in `balfiera.json`.
   * **Red Mountain Summit, Dagoth Ur Citadel & Ghostfence (`red_mountain`, `dagoth_ur`, `ghostgate`):** Build a massive volcanic crater citadel with Dwemer brass piping, magma bridges, and Ghostfence bone-and-brass energy pylons instead of the 7×7 `Style.MONUMENT` pillar.
   * **Crystal-Like-Law (`crystal_tower_summerset`):** Build a 23×23×175 multi-tiered translucent crystal spire and terraced arcane gardens.
   * **Baar Dau / Ministry of Truth (`ministry_of_truth` / `vivec`):** Build the giant hollowed meteor floating above Vivec's Temple Canton with suspended scaffolding and Ordinator watch-posts.
   * **Falinesti Seasonal Sites (`falinesti_autumn_site`, `falinesti_winter_site`, `falinesti_spring_site`, `falinesti_summer_site`):** Build 55×55 colossal Graht-oak root basins, canopy platforms, and Bosmer shrine rings.

## 4. Verification Gates

* `TamrielPlanTest` verifies `peaks.json` loads `>= 60` peaks across all 11 provinces, `red_mountain` summit exceeds `y = 480` with an inner caldera basin, `eton_nir` exceeds `y = 480`, and `sunspire_peak` exceeds `y = 440`.
* `0` major world landmarks (`red_mountain`, `crystal_tower_summerset`, `falinesti_*_site`, `ministry_of_truth`) use the 7×7 `Style.MONUMENT` or 9×9 `Style.CLEARING` fallback.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
