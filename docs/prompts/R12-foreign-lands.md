# R12 Foreign lands (Atmora, Akavir, Yokuda, Pyandonea, Thras)

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R12-foreign-lands.md`
> step V<n>."

## Goal

The lands beyond Tamriel as **expeditions, not homelands**: the region template at half density (DESIGN-PRINCIPLES
4.1), each land with a reason to go, a way there, one marquee, and a sheet like any province, so even the far
places obey "world building is king".

## Context to read

* `docs/world/provinces/foreign_lands.md` (the design; the name on disk wins if different).
* `docs/world/DESIGN-PRINCIPLES.md` 4.1 (half density). `docs/MARQUEE.md` foreign lands section, `docs/LORE-WEB.md`.
* `docs/FINISH-PLAN.md` T2 notes on foreign settlements in water (`pyandonean_fleet_anchorage`, `kjolvik`,
  `tiger_plains_village`, `tang_mo_harbor_village`, `mistveil_village`) and the `Beyond` noise land check in
  `tools/provinces.py`.

Lands and regions: atmora (ice_waste, frozen_forest), akavir (tsaesci, kamal, tang_mo, ka_po_tun), yokuda
(yokudan_desert, sunken_isles), pyandonea (maormer_isles), thras (coral_kingdom). Sheets go in one file per land
(`tamriel/world/<land>.json`) or one `foreign_lands.json`; follow the province document.

## Specifics per step

| Step | Foreign specifics | Extra gates |
| --- | --- | --- |
| V1 | One sheet per land; L11 heavy (Atmora's exodus, Yokuda's sinking, the Tsaesci and Kamal), L9 explains why outsiders are welcome or not | as V1 |
| V2 | Coasts and relief per land within the `Beyond` noise frame; no anchor moves unless a place is in water | 0 places in water (`layout_lint`) |
| V3 | One signature biome mosaic per land (glacier and dead forest; bamboo, snow and volcano; dune and drowned isles; coral and kelp) | as V3, half density |
| V4 | Places at half the 4.3 densities: a landing camp or port, 1-2 settlements, dungeons by class, unmarked sites | half-density floors |
| V5 | Sea routes from Tamriel ports (exist for Pyandonea, Tang Mo, Mistveil; add the rest), roads on every island with a settlement (today only the Pyandonean pair) | every land reachable by a paid route; `reach_lint` 100% |
| V6 | Each settlement its culture's own plan or kit (no Tamriel reskins) | `y_looks` >= 2 per land |
| V7 | One marquee per land (`MARQUEE.md` foreign entries), signature inhabitants (Tsaesci, Kamal, Maormer, Sload, Atmoran dead) | as V7, half density |
| V8 | An expedition arc per land started from a Tamriel port (a captain, a map, a reason), one faction or patron per land, encounters and one wonder per land | F-HOOK measured from the landing |
| V9 | DESIGN-PRINCIPLES 5.5 at half density for every foreign region | as V9 |

## Merge and report

As `_COMMON.md` section 7, one commit per step.

**Size:** M.
