# R8 Black Marsh

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R8-black-marsh.md` step
> V<n>."

## Goal

The weakest province today (parity 40) built to the floor without losing what makes it itself: flat, wet and close to
sea level. Height is not the amplifier here; structure is: braided channels, oxbows, reed islands, Hist groves on low
rises, xanmeer step-pyramids as silhouettes, causeway roads and water travel.

## Context to read

* `docs/world/provinces/black_marsh.md` (the design).
* `docs/world/SCALE-AND-GEOGRAPHY.md` 3 (Arena-only rule), 5.9 (backbone, coast moves, modernisation), 6.2, 6.3, 7,
  8.9. `docs/MARQUEE.md` Black Marsh, `docs/LORE-WEB.md` Black Marsh arcs.
* `.reference/arena_ref` (backbone and positions; authoring only).

Province `<P>` = `black_marsh`; regions: shadowfen, stormhold, helstrom, gideon, lilmoth, murkmire. Source: Arena on
the lore map (no canonical modern game).

## Specifics per step

| Step | Black Marsh specifics | Extra gates |
| --- | --- | --- |
| V1 | Sheet: the Hist (L8, L9), the Argonian tribes and An-Xileel, the Dunmer slavers' border (L11, L9), drowned Saxhleel ruins (L10), the Knahaten flu (L11) | as V1 |
| V2 | Coast (SCALE 8.9): Topal Bay's eastern arm to about 5700, 9900 with Soulrest on its eastern shore (~6100, 9700) is drawn in R6 V2; check it, and do it here if R6 did not; bulge the east coast ~500 near Thorn and Archon; Lilmoth's delta; a bay at about 10500, 6300-6900; verify each against the lore map first; relief 1→3 / 2→10 / 0.4→1 (Hist rises 10-20, north-west uplands toward Cyrodiil 30-80); braided channels and oxbows in hydrology | `layout_lint`; every settlement dry or on stilts by design (documented) |
| V3 | Distinct sub-regions: Shadowfen brackish flats, Murkmire mangroves, Helstrom deep mire, Gideon reed plains, Stormhold uplands; micro-biomes by water depth and salinity | as V3 |
| V4 | Alten Meirhall the only missing Arena name; xanmeer and Hist groves as places; unmarked drowned ruins | as V4 |
| V5 | Causeway roads (Stormhold-Helstrom-Archon-Lilmoth, Stormhold-Gideon-Soulrest, Thorn-Archon) as raised beds; **swamp boats** as the main travel (Lilmoth, Gideon, Stormhold, Helstrom, Soulrest); river barges (MS11); Leyawiin-Stormhold through Blackwood to Cyrodiil | >= 2 travel modes; F-TRAVEL with boat nodes counted |
| V6 | Stilt towns and Hist-tree villages, Lilmoth as the bustling port, Gideon's Dunmer-era plantation past, Stormhold's walls | as V6 |
| V7 | Xanmeer dungeons (step-pyramids, flooded lower levels), Hist sap caves, slaver forts, Shadowscale haunts; a signature theme per region | as V7 |
| V8 | Hist politics, the tribes, the Shadowscales (Dark Brotherhood), slavery's legacy; encounters (Hist procession, a slaver caravan freed, a hackwing swarm, a sap bloom as wonder) | F-ENC >= 3 province-specific |
| V9 | DESIGN-PRINCIPLES 5.5 for all 6 regions | as V9 |

## Merge and report

As `_COMMON.md` section 7, one commit per step.

**Size:** L.
