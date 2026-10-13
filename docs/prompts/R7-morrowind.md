# R7 Morrowind (Vvardenfell, the mainland, Solstheim)

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R7-morrowind.md` step
> V<n>."

## Goal

The alien province at Skyrim quality: Vvardenfell game-faithful and amplified (caldera, foyadas, Azura's Coast
islets), the mainland's empty middle replaced with a real Velothi wall, ash plains and mushroom coasts, Solstheim kept
at its Skyrim-neighbour standard, and the richest travel web in Tamriel (striders, boats, guild guides, propylons,
interventions).

## Context to read

* `docs/world/provinces/morrowind.md` (the design).
* `docs/world/SCALE-AND-GEOGRAPHY.md` 2.3, 5.1 (Solstheim list), 5.3 (Vvardenfell moves, mainland, missing places,
  modernisation), 6.2, 6.3 (Vvardenfell travel table), 7, 8.3, 8.8, 8.10. `docs/MARQUEE.md` Morrowind and Solstheim.
* `.reference/morrowind_ref` (markers, regions, coast, roads, signposts, shrines, travel; authoring only).

Province `<P>` = `morrowind`; regions: stonefalls, deshaan, molag_amur, telvannis, sheogorad, vvardenfell_ashlands,
grazelands, azuras_coast, west_gash, bitter_coast, ascadian_isles, red_mountain, solstheim. **Morrowind's positions are
canonical** for Vvardenfell and Solstheim; the mainland follows Arena's backbone onto the lore map.

## Specifics per step

| Step | Morrowind specifics | Extra gates |
| --- | --- | --- |
| V1 | Sheet: the Tribunal and the Temple, the Great Houses, the Ashlanders, the Blight and Red Mountain, the Dwemer (L11), the Sixth House (L10) | as V1 |
| V2 | Vvardenfell uniform similarity at 0.95 blocks/m with the SCALE 5.3 moves (Vivec 7140, 1590 with the south coast ~250 south; Sadrith Mora; Molag Mar; Tel Branora; West Gash towns; Dagon Fel); replace the 17-vertex polygon with the extracted coastline through the same similarity, simplified to 48 blocks (Bitter Coast inlets, Sheogorad isles, Azura's Coast archipelago, the Odai mouth); fix the `sea_of_ghosts` label south of Vvardenfell to `inner_sea`; Red Mountain caldera and radiating foyadas; the mainland Velothi wall (300-450) with passes, Stonefalls volcanic shields, Deshaan ash-and-fungus plains, the Telvanni coast cliffs; relief 21→30 / 150→170 / 4→5 | Vvardenfell game-distance ratios uniform (one similarity); `layout_lint` all Vvardenfell places on land |
| V3 | Ash wastes, fungal forests, Grazelands slopes, Bitter Coast swamp, Azura's Coast islets, Solstheim's ash-and-snow split; heat raster drives volcanic micro-biomes | as V3 |
| V4 | Missing places: Holamayan, the Ministry of Truth (landmark pin over Vivec), Tel Fyr's tower landmark, Mawia, Castle Ebonheart near Vivec (~7000, 1650); Solstheim: Thirsk, Castle Karstaag, Lake Fjalding, Mortrag Glacier, Hrothmund's Bane, Thormoor's Watch, Aidanat; the propylon strongholds (10 `fort` places exist); Arena's mainland towns as a name pool for generic villages only | every SCALE 8.10 Morrowind and Solstheim name present |
| V5 | Named roads from the 174 signpost junctions and 291 road polylines (West Gash road, Ald-ruhn-Maar Gan, Ald-ruhn-Caldera-Balmora, Balmora-Pelagiad-Vivec, Seyda Neen-Pelagiad, Vivec-Suran-Molag Mar, Vos-Tel Vos); ash wastes and Red Mountain trails only; mainland Arena backbone highways; Windhelm-Blacklight over the Velothi (the vectors' `morrowind_road`) and Cheydinhal-Kragenmoor; travel: strider offers restricted to the 16 game links, the 25-link boat network, Mages Guild guides all-to-all (Ald-ruhn, Balmora, Caldera, Vivec, Sadrith Mora), the Propylon ring (index items as quest rewards or loot), Almsivi and Divine interventions (19 targets), Mark and Recall | >= 4 travel modes; F-TRAVEL; a corridor `balmora:pelagiad` in `full_corridors.ps1` |
| V6 | Vivec's cantons, Balmora (Hlaalu), Ald-ruhn (Redoran, under-skar), Sadrith Mora (Telvanni towers), Mournhold as the mainland capital, Ashlander camps as villages with yurt rings; each a unique plan (`MARQUEE.md` silhouettes) | Morrowind STY stays >= 80 |
| V7 | Velothi tombs, Dwemer ruins, Daedric shrines, Sixth House bases, Blight caves; the propylon strongholds as forts with the ring | as V7 |
| V8 | **Exclusive Great House choice** (Hlaalu, Redoran, Telvanni; P-J1), the Temple and Imperial Cult lines, Morag Tong, Ashlander clan favours, the province story across Vvardenfell and the mainland; encounters (Ashlander herd crossing, Ordinator patrol, a silt strider down, a Blight storm as wonder) | >= 1 exclusive faction choice; F-HUBS >= 2 |
| V9 | DESIGN-PRINCIPLES 5.5 for all 13 regions | as V9 |

## Merge and report

One merge and commit per step (`_COMMON.md` section 7). V2 anchor moves update `docs/TAMRIEL.md` section 9 and every
reference in the same commit.

**Size:** XL.
