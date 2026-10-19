# T5 — Causal Inter-Settlement Trade Network (`TradeNetwork.java`), 3D Regional Crops & All-Province Homesteads

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **No trade network connects `WorldSheets` or `TravelNetworks` to `ShopStock` or `PriceEngine`**, and `shop_trades.json` only restricts 11 Skyrim/Cyrodiil/Morrowind drinks/cheeses.
2. **`Zoning.cropVariant()` (`Zoning.java:1284-1298`) and `Works.crop()` (`Works.java:301-312`) collapse all non-Skyrim crops (`saltrice`, `wickwheat`, `marshmerrow`, `comberry`, `hackle_lo`, `scathecraw`, `coda_flower`, `sugar`, `vines`, `orchard`, `honey`, `melon`) to `Blocks.WHEAT`**, and `Works.garden()` (`Works.java:318-329`) plants Skyrim cabbage/leek/garlic/tomatoes in house backyards across every province!
3. **`Outdoors.PADDY`, `SUGAR`, `VINEYARD`, `ORCHARD`, and `APIARY` never run in `Zoning.adopt()` or `Zoning.farmstead()`**, `Outdoors.KWAMA` (`Outdoors.java:439`) is never called in `Zoning.java` at all, and `Outdoors.animalType()` (`Outdoors.java:199-214`) spawns Morrowind `guar`, `netch`, and `kwama_forager` as `EntityType.CHICKEN`!
4. **`Homesteads.PLOTS` (`Homesteads.java:48-51`) hardcodes only the 3 Skyrim plots**, leaving the other 104 `"variant": "homestead"` places across Tamriel unpurchasable, and Hammerfell, Valenwood, Summerset, Elsweyr, and Black Marsh only have 1 pre-built city player home each (`ProvincialHomes.java`).

`T5` builds the causal inter-settlement `TradeNetwork`, wires real regional crops, specialty agricultural camps, and native livestock across all provinces, and unlocks all 107 Hearthfire homesteads and provincial city homes.

## 2. Write Set

* `mod/src/main/java/com/nirncraft/economy/{TradeNetwork,ShopStock,ShopTrades,PriceEngine,BarterSelfTest}.java`
* `mod/src/main/resources/nirncraft/economy/shop_trades.json`
* `mod/src/main/resources/data/nirncraft/tags/items/merchant/**/*.json`
* `mod/src/main/java/com/nirncraft/tamriel/settlement/grammar/{Zoning,Outdoors}.java`
* `mod/src/main/java/com/nirncraft/tamriel/settlement/build/Works.java`
* `mod/src/main/java/com/nirncraft/homestead/{Homesteads,Houses}.java`
* `mod/src/main/java/com/nirncraft/mounts/homes/{HousePlots,ProvincialHomes}.java`

## 3. Implementation Steps

1. **`TradeNetwork.java` & Regional Trade Goods (`com.nirncraft.economy.*`, `shop_trades.json`):**
   * Create `TradeNetwork.java` computing each settlement's **Local Production** (from its `WorldSheets` L3 resources, L5 industries, nearby mines/farms/mills/fisheries, and `cityplans` trade specs) and **Imported Goods** along `RoadPlan` and `TravelNetworks` links (up to 2 settlement hops: hamlet/mine/plantation $\rightarrow$ town $\rightarrow$ regional city $\leftrightarrow$ partner cities/ports).
   * Respect cultural/political trade rules from `WorldSheets` L6–L9 (e.g. Green Pact Bosmer import timber/grain rather than harvesting local Valenwood flora; Great House monopolies; civil war / faction tariffs).
   * Expand `shop_trades.json` `goods_by_province` and `#nirncraft:merchant/**` tags to cover specialty goods, foods, drinks, textiles, ingots, ingredients, and art/books for all 11 provinces.
   * Wire `ShopStock.java` and `PriceEngine.java` to include local exports (at `0.80x` base price) and connected trade-route imports (at `1.0x–1.30x` price based on hop distance) in every shop's inventory.
2. **Regional Crops, Gardens, Outskirts Camps & Native Livestock (`Zoning.java`, `Works.java`, `Outdoors.java`):**
   * Extend `Zoning.cropVariant()`, `Works.crop()`, and `Works.garden()` to map all cultural crops (`saltrice`, `ash_yam`, `wickwheat`, `marshmerrow`, `comberry`, `hackle_lo`, `scathecraw`, `coda_flower`, `sugar`, `vines`, `melon`, `gourd`) to distinct crop blocks instead of `Blocks.WHEAT` or Skyrim cabbage/leeks.
   * Call `Zoning.outskirts()` in `Zoning.adopt()` and `Zoning.farmstead()`, and wire `Outdoors.KWAMA` into `Zoning.outskirts()` for `velothi` settlements and farms.
   * In `Outdoors.animalType()` (`Outdoors.java:199-214`), map `"guar"`, `"netch"`, and `"kwama_forager"` (plus `"horker"`, `"mammoth"`, `"senche"`, `"camel"`) to their real NirnCraft `EntityType`s instead of `EntityType.CHICKEN`!
3. **All 107 Hearthfire Homesteads & Provincial City Homes (`Homesteads.java`, `Houses.java`, `ProvincialHomes.java`):**
   * Populate `Homesteads.PLOTS` dynamically from all `107` `"variant": "homestead"` places in `Places.all()`, mapping each plot's steward to its region's capital/city and building wings with the region's cultural `Palette` (`Houses.build()`).
   * Expand `ProvincialHomes.registerDefaults()` so every major city in Hammerfell, Valenwood, Summerset, Elsweyr, Black Marsh, and Outer Realms has a purchasable city home.

## 4. Verification Gates

* `BarterSelfTest` verifies `TradeNetwork` local exports, multi-hop trade imports, and local vs. import price scaling across all 11 provinces.
* `0` non-Skyrim cultural crop names fall back to `0` (`Blocks.WHEAT`) in `Zoning.cropVariant()`, and `Outdoors.animalType("guar")` / `animalType("netch")` return NirnCraft entity types.
* All `107` `"variant": "homestead"` places are registered in `Homesteads` and pass steward purchase + wing build selftests.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
