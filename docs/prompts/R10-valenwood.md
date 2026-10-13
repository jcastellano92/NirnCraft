# R10 Valenwood

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R10-valenwood.md` step
> V<n>."

## Goal

Valenwood at the Skyrim floor with its height coming from trees, not hills: Graht-oak giants (100+ blocks) as
landmarks and cities, canopy layers, river gorges cut below the forest floor, mangrove deltas and sinkhole lakes; the
Green Pact explaining what people eat, build and fear.

## Context to read

* `docs/world/provinces/valenwood.md` (the design).
* `docs/world/SCALE-AND-GEOGRAPHY.md` 2.4 (Arena drew it oversized: 40 blocks/px), 3, 5.7, 6.2, 6.3, 7.
  `docs/MARQUEE.md` Valenwood, `docs/LORE-WEB.md`. `docs/FLORA.md` (tree generator).

Province `<P>` = `valenwood`; regions: malabal_tor, reapers_march, grahtwood, greenshade. Source: Arena on the lore map.

## Specifics per step

| Step | Valenwood specifics | Extra gates |
| --- | --- | --- |
| V1 | Sheet: the Green Pact (L8, L5: no plant harvest, meat and bone economy), the Wild Hunt (L10), walking cities (L6), the Camoran dynasty (L9, L11), Ayleid and Bosmer ruins | as V1 |
| V2 | Relief 18→22 / 35→60 / 1.2→2.5: river gorges (Xylo, Strid; 20-40 below the floor), Reaper's March knolls (60-120), mangrove deltas, sinkhole lakes; giant trees are V7 landmarks, not terrain | as V2 |
| V3 | Canopy forest, understorey glades, mangrove, river gorge, Reaper's March dry savanna edge; micro-biomes by canopy gap and water | parity FLO stays >= 80 |
| V4 | Thormar Keep, Vulkwasten Wood, Karthdar Square are a name pool for Grahtwood generic villages; spread the eastern village cluster so no two are closer than ~600 blocks | spacing rule holds |
| V5 | Canopy paths and river fords rather than paved roads (Arenthia-Silvenar-Falinesti-Woodhearth, Eldenroot-Silvenar, Eldenroot-Greenheart-Southpoint-Haven) as lanes and trails with raised walkways; **river boats** on the Xylo and Strid and **Graht-oak lifts** (MS11); Skingrad-Arenthia along the Strid to Cyrodiil | >= 2 travel modes |
| V6 | Elden Root and Falinesti as tree cities (vertical plans), Woodhearth's port, Silvenar's court; homes in and on trees | unique vertical plans for tree cities |
| V7 | Giant-tree XL landmarks for sightlines (F-WEENIE through the canopy: plan clearings and river views), Ayleid ruins, Wild Hunt groves, spider-silk caves | F-WEENIE >= 70% |
| V8 | The Green Pact's rules as quests (a hunter who broke it, a forbidden garden), the Silvenar and Green Lady, Thalmor presence; encounters (a Wild Hunt rumble as wonder, a bone-carver's caravan, a forest spirit); festival | F-ENC >= 3 province-specific |
| V9 | DESIGN-PRINCIPLES 5.5 for all 4 regions | as V9 |

## Merge and report

As `_COMMON.md` section 7, one commit per step.

**Size:** L.
