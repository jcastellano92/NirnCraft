# T7 — Distance-Capped Carriages (`Carriages.java`), Multi-Modal Regional Transport, Distance-Scaled Pricing & Universal `Prewarm`

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **`Carriages.destinations()` (`Carriages.java:155-225`) offers every `Place.Type.CAPITAL` and discovered `Place.Type.CITY` on the entire landmass with no maximum distance cap**, allowing a player at Whiterun Stables to ride in one hop all the way to Imperial City, Mournhold, Rimmen, Elden Root, or Stormhold.
2. **Carriage offers do not list exact travel distances (`m` / `km`) or travel durations, and do not scale fares proportionally by road distance.**
3. **`TravelNetworks.PROVINCES` (`TravelNetworks.java:79-82`) only loads the 9 mainland province files**, ignoring the 5 outer-realm travel files (`akavir.json`, `atmora.json`, `pyandonea.json`, `thras.json`, `yokuda.json`).

`T7` enforces a realistic maximum carriage range (`MAX_CARRIAGE_DISTANCE = 2,600` blocks—reaching all of Skyrim from central Whiterun, or adjacent cross-border cities, while requiring multi-hop journeys across Tamriel), displays distances and travel times on every offer, scales gold cost with distance, loads all 14 `travel/*.json` files, and guarantees `Prewarm` destination chunk loading on every travel mode.

## 2. Write Set

* `mod/src/main/java/com/skycraft/roads/Carriages.java`
* `mod/src/main/java/com/skycraft/travel/{TravelNetworks,TravelCommand,TravelSelftest}.java`
* `mod/src/main/java/com/skycraft/creatures/travel/StriderPorts.java`
* `mod/src/main/java/com/skycraft/sailing/FerryRoutes.java`
* `mod/src/main/resources/skycraft/travel/*.json`
* `mod/src/main/lang/skycraft/travel.json`

## 3. Implementation Steps

1. **Distance-Capped Carriages, Distance Display & Proportional Fares (`Carriages.java`):**
   * Set `public static final int MAX_CARRIAGE_DISTANCE = 2600;` (in blocks; from Whiterun `(0, 0)`, every Skyrim hold capital—Solitude, Windhelm, Riften, Markarth, Falkreath, Morthal, Dawnstar, Winterhold—is within `<= 2,500` blocks, while edge-to-edge cross-province trips require staging through intermediate cities).
   * In `Carriages.destinations()`, filter all candidate carriage destinations (both authored `TravelNetworks` carriage routes and same-landmass `CAPITAL`/`CITY`/`TOWN` carriage stops) by `roadOrCrowDistance <= MAX_CARRIAGE_DISTANCE` and `PlanLinks.connected(fromX, fromZ, toX, toZ)`.
   * Compute distance in blocks/km (`String.format("%.1f km", dist / 1000.0)` or `dist + "m"` when `< 1000`) and estimated ride hours (`Math.max(1, Math.round(dist / 350.0f))` hours), and scale fare proportionally: `int fee = Math.max(20, 15 + Math.round(dist * 0.045f));` (with a small discount for already-discovered towns).
   * Format every carriage option to show the destination name, distance, travel time, and gold cost (`"Solitude (1.8 km, 5h) — 95g"`).
   * Ensure every capital and city places its carriage and driver outside the main gate beside the road (`Carriages.spawnPost`).
2. **Load All 14 Travel Files & Enforce `Prewarm` Across All Modes (`TravelNetworks.java`):**
   * Add `"akavir"`, `"atmora"`, `"pyandonea"`, `"thras"`, and `"yokuda"` to `TravelNetworks.PROVINCES` (`TravelNetworks.java:79-82`), and ensure outer-realm expedition ports link to their mainland departure ports (`solitude`/`windhelm` $\leftrightarrow$ `atmora`, `anvil`/`hegathe` $\leftrightarrow$ `yokuda`, `alinor`/`lillandril` $\leftrightarrow$ `pyandonea`/`thras`, `port_telvannis`/`tear` $\leftrightarrow$ `akavir`).
   * Verify that every travel execution path (`Carriages`, `StriderPorts`, `FerryRoutes`, `TravelNetworks.travel()`) calls `Prewarm.to()` to pre-generate the 7×7 destination chunk area with the `"Travelling to <Destination>..."` loading screen before teleporting the player and party.

## 4. Verification Gates

* `TravelSelftest` verifies:
  * All `14` `travel/*.json` files load with `0` missing place endpoints,
  * Whiterun carriage offers all 8 other Skyrim hold capitals within `MAX_CARRIAGE_DISTANCE = 2,600`,
  * `0` carriage offers anywhere in Tamriel exceed `MAX_CARRIAGE_DISTANCE`,
  * Carriage fares strictly increase with distance and include formatted distance labels,
  * `Prewarm` is invoked for all travel modes.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
