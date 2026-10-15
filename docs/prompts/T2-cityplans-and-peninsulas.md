# T2 — Fix the 75 Streetless Cyrodiil & Morrowind `cityplans/*.json`, Unshadow Java Cities & Verify Peninsula Settlements

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **`mournhold.json`, `vivec.json`, and `raven_rock.json` have `"base": "none"` instead of `"base": "java"`**, which shadows and skips `Cities.mournhold()`, `Cities.vivec()`, and `Cities.ravenRock()` in `CityPlans.build()` (`CityPlans.java:114-130`).
2. **75 `cityplans/*.json` files (`32` in Cyrodiil including all 8 county capitals `anvil`, `bravil`, `bruma`, `cheydinhal`, `chorrol`, `kvatch`, `leyawiin`, `skingrad` + `24` towns/villages; `41` in Morrowind including `balmora`, `aldruhn`, `sadrith_mora`, `ebonheart`, `caldera`, `suran`, `gnisis`, `pelagiad`, `seyda_neen`, `tel_fyr`, `tel_mora`, etc.) have `"base": "none"`, `0` `"streets"`, `0` `"nets"`, and `0` `"queue"`.** When `x.sk.streets` is empty in `Zoning.adopt()` (`Zoning.java:1744-1910`), `haveStreet` is `false`, so `reserved()`, `lining()`, `ensureTrades()`, and `fillGaps()` place **zero infill houses and zero missing trades**!
3. **`Zoning.harbor()` and `Zoning.outskirts()` are only called in `Zoning.generic()`, never in `Zoning.adopt()`**, so coastal `cityplans` cities without pinned docks lack harbors/warehouses/fisheries.

`T2` brings every settlement across Cyrodiil, Morrowind, and all other provinces to the full street network, district density, harbor, and building count bar of Skyrim's overhauled settlements (`R4a`), and verifies peninsula/coastline settlement coverage against `.reference/` and `F:\daggerfall-js-source-main\daggerfall-js-source-main`.

## 2. Write Set

* `mod/src/main/java/com/skycraft/tamriel/settlement/plan/{Cities,CityPlans}.java`
* `mod/src/main/java/com/skycraft/tamriel/settlement/grammar/{Zoning,Fabric,Nets}.java`
* `mod/src/main/resources/skycraft/tamriel/cityplans/*.json`
* `mod/src/main/resources/skycraft/tamriel/places/*.json`
* `mod/src/main/lang/skycraft/settlements.json`
* `tools/qa/parity.py`

## 3. Implementation Steps

1. **Unshadow All Java-Built Cities (`CityPlans.java`, `mournhold.json`, `vivec.json`, `raven_rock.json`, `falkreath.json`, `morthal.json`, `dawnstar.json`, `winterhold.json`, `helgen.json`, `dragon_bridge.json`):**
   * Set `"base": "java"` on every `cityplans/<id>.json` that has a dedicated builder in `Cities.buildJava()` (`Cities.java:225-248`), and make `CityPlans.build()` automatically invoke `Cities.buildJava()` whenever `Cities.hasJavaBuilder(p.id())` is true unless explicitly overridden.
2. **Author Street Nets, Fabric & Queues for All 32 Cyrodiil & 41 Morrowind Cityplans:**
   * For all 8 Cyrodiil county capitals (`anvil`, `bravil`, `bruma`, `cheydinhal`, `chorrol`, `kvatch`, `leyawiin`, `skingrad`) and 24 Cyrodiil towns/villages, author culturally appropriate `"nets"` (`grid`, `radial`, `spine`, `loops`), `"streets"`, `"fabric"`, and `"queue"` arrays so county capitals generate `35–55` buildings across connected districts (chapel district, castle/keep ward, market spine, guild row, residential quarters, waterfront/harbor) and towns generate `12–22` buildings.
   * For all 41 streetless Morrowind plans (`balmora` along the Odai River with bridges, `aldruhn` around Under-Skar, `sadrith_mora` mushroom lanes around Wolverine Hall, `ebonheart`, `caldera`, `suran`, `gnisis`, `pelagiad`, `seyda_neen`, `dagon_fel`, `gnaar_mok`, `vos`, `molag_mar`, `khuul`, `hla_oad`, `maar_gan`, `ald_velothi`, `tel_aruhn`, `tel_branora`, `tel_fyr`, `tel_mora`, `tel_vos`, etc.), author `"nets"`, `"streets"`, `"fabric"`, and `"queue"` arrays matching their Great House / Imperial / Velothi architecture.
3. **Harden `Zoning.adopt()` (`Zoning.java:1744-1910`):**
   * If `x.sk.streets.size() < 2` after `pins()` and `Nets.apply()`, automatically synthesize a cultural street network (`Nets` spine/cross/loop linking gates, main plaza, keep/hall, and pinned buildings) before running `reserved(x)`, `lining(x)`, `ensureTrades(x)`, and `fillGaps(x)`.
   * Call `Zoning.harbor(x)` (when coastal and no dock is pinned yet) and `Zoning.outskirts(x)` inside `Zoning.adopt()` so `cityplans` settlements receive coastal harbors and cultural agricultural outskirts.
4. **Peninsula & Coastline Settlement Audit (`places/*.json`):**
   * Cross-check peninsulas, capes, and bays across High Rock, Hammerfell, Cyrodiil, Morrowind, Black Marsh, Elsweyr, Valenwood, and Summerset against `.reference/` and `F:\daggerfall-js-source-main\daggerfall-js-source-main`, ensuring key coastal/peninsula travel corridors have well-spaced port towns and villages.

## 4. Verification Gates

* Zero `cityplans/*.json` files produce `0` streets in `streetplan.bin`.
* Every capital across all 11 provinces builds `>= 35` buildings (`>= 12` streets); every city builds `>= 22` buildings (`>= 6` streets); every town builds `>= 12` buildings (`>= 3` streets).
* `py -3 -I tools/qa/reach_lint.py` -> `0 settlement errors`, `0 isolated places`.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
