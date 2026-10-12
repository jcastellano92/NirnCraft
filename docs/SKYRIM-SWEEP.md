# Skyrim sweep: the first-area logic across all nine holds

Offline audit of 24 generated Skyrim settlements (the 9 hold capitals plus 15 towns, villages and strongholds), run
on a headless-server world (seed `Nirn`, `forceload` around each, one boot, roads paved by the in-boot planner). No
client was started. Checks: `tools/qa/town_checks.py` (plan pieces vs real blocks: gates, stubs, props, doors, stair
ends, sealing, floating vegetation), `population_lint.py` + `/skypop` (beds, jobs, workplaces), `reach_lint.py`,
`placement_lint.py`, `layout_lint.py`. Reproduce: boot the server with `-Dskycraft.autorun=skypop <id>|...|forceload add ...|stop`,
then `py -3 -I tools/qa/town_checks.py <world> <samples.json>` (samples: id, type, culture, x, z, radius, half).

Not swept: Fort Dawnguard (a landmark, not a settlement entry) and Raven Rock / Solstheim (Morrowind province data;
covered by the generic planner, no Skyrim plan). Thalmor Embassy is a special `embassy` variant (not sampled).

## 0. What the sweep changed in the checker

The first run of the existing checks was dominated by false positives, so they were corrected before any code was
judged (the corrected rules are in `tools/qa/town_checks.py`; before and after numbers below use the same rules):

* **floating vegetation** counted every builder beam (a stripped log is a porch rafter) and every leaf clump that
  touches a wall (ivy on a ruin): whiterun read 214 components, 207 of them 1-block stripped logs. Now a component with
  a stripped log, or <= 12 blocks touching a built block, is part of a structure (same rule as `world/OrphanSweep`).
  What remains are whole crowns with no trunk. Helgen still counts the leaf-only ivy on its ruined wall (see 4).
* **sealed**: judged the outermost cell row, which for most houses is the porch (open by design). A side is now the
  best of its outer three rows.
* **unreachable doors**: demanded the second cell in front of the door be free; a one-block step up or a free cell
  beside the doorstep now counts as the way on (mobs and players step one block).

## 1. Totals, before and after the fixes (24 settlements)

| check | before | after |
|---|---|---|
| buildings | 1099 | 1104 |
| props (yards + stalls) | 1873 | 1946 |
| residents / beds | 481 / 1427 | 497 / 1438 |
| inn rooms | 72 | 75 |
| exterior doors / unreachable | 1247 / 75 | 1226 / 77 |
| blocked stair ends | 0 | 0 |
| buildings with an open wall side (of which forges, open by design) | 72 (16) | 76 (19) |
| gate points / gates with no road end within 24 | 28 / 2 | 28 / 2 |
| dead road stubs | 0 | 0 |
| props on a road / on a street / inside a building | 24 / 47 / 0 | 22 / 47 / 1 |
| ghost jobs (workplace anchor is not a workplace) | 0 | 0 |
| unstaffed workplaces | 18 | 16 |
| floating vegetation components (whole crowns, no trunk) | 54 | 64 |
| settlements with no inn | 6 | 5 (stonehills, heartwood_mill, college, dushnikh_yal, largashbur: none has one in Skyrim) |
| capitals without a shop counter (merchant) | 2 (morthal, dawnstar) | 0 |
| capitals without a smithy | 2 (morthal, winterhold) | 0 |
| capitals without an apothecary | 2 (falkreath, winterhold) | 0 |
| props per building, the four small capitals + Helgen | 0.31-0.45 | 0.49-0.68 |

The population lint stays at 0 errors. Floating +10 is world-gen variance in forest towns (falkreath 18>17, helgen
13>25 after +2 new fill homes cut more trees): the sweep did not touch tree clearing (see 4).

## 2. Per settlement (`before>after` where it changed)

`unsealed*` excludes smithies and stables (forges are open on one side by design). `doors bad/of` is exterior doors
whose doorstep has no way on. `shops` and `smith` are workplace anchors in the housing model.

| settlement (hold) | bld | floating | doors bad/of | unsealed* | stair ends | gates no road/of | stubs | props on road | props on street | props/bld | beds | resid. | ghost jobs | unstaffed | inn rooms | shops | smith |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whiterun (Whiterun) | 165 | 3>1 | 9>8/167 | 5 | 0 | 0/1 | 0 | 0 | 1 | 1.8>1.86 | 243 | 44 | 0 | 0 | 3 | 12 | 2 |
| riverwood (Whiterun) | 12 | 0 | 0/8 | 0 | 0 | 1/3 | 0 | 0 | 0 | 2.67 | 10 | 10 | 0 | 3 | 4 | 6 | 1 |
| rorikstead (Whiterun) | 16 | 0 | 0/14 | 2 | 0 | 0/2 | 0 | 0 | 2 | 2.94 | 15 | 15 | 0 | 0 | 4 | 6 | 1 |
| solitude (Haafingar) | 144 | 10>9 | 16/160 | 9 | 0 | 0/1 | 0 | 0 | 5 | 2.29>2.3 | 209 | 43 | 0 | 0 | 3 | 12 | 1 |
| dragon_bridge (Haafingar) | 6>7 | 0 | 2/8 | 2>3 | 0 | 0/3 | 0 | 0 | 1 | 3.83>3.29 | 9>11 | 8>10 | 0 | 0 | 0>3 | 5>6 | 0 |
| windhelm (Eastmarch) | 144 | 0>1 | 9/174 | 6 | 0 | 0/1 | 0 | 0 | 7 | 1.94>1.96 | 225 | 43 | 0 | 0 | 4 | 7 | 1 |
| kynesgrove (Eastmarch) | 13 | 0 | 0/12 | 4 | 0 | 0/1 | 0 | 1 | 0 | 2.31 | 24 | 14 | 0 | 2 | 4 | 6 | 1 |
| darkwater_crossing (Eastmarch) | 3>4 | 1 | 0>1/5 | 1>2 | 0 | 1/2 | 0 | 0 | 1>0 | 6.33>3.0 | 5>9 | 5>8 | 0 | 2>0 | 4 | 3>1 | 0 |
| riften (Rift) | 73 | 4 | 4/72 | 3 | 0 | 0/1 | 0 | 1 | 1 | 2.52>2.53 | 92 | 47 | 0 | 0 | 5 | 10 | 1 |
| ivarstead (Rift) | 20>23 | 1 | 1>2/20 | 4>5 | 0 | 0/2 | 0 | 4>3 | 1 | 2.6>2.0 | 25>30 | 21>23 | 0 | 0 | 6 | 7 | 1 |
| shors_stone (Rift) | 17 | 0 | 1/19 | 2 | 0 | 0/2 | 0 | 2 | 0 | 2.76 | 16 | 15 | 0 | 0 | 3 | 6 | 1 |
| heartwood_mill (Rift) | 1 | 1 | 0/1 | 0 | 0 | 0/0 | 0 | 3 | 0 | 5.0 | 3 | 2 | 0 | 0 | 0 | 0 | 0 |
| largashbur (Rift) | 2 | 0 | 0/2 | 1 | 0 | 0/1 | 0 | 0 | 0 | 3.0 | 3 | 1 | 0 | 1 | 0 | 0 | 1 |
| markarth (Reach) | 102 | 1>2 | 9/117 | 4 | 0 | 0/1 | 0 | 6 | 26 | 3.06>3.09 | 134 | 43 | 0 | 0 | 4 | 9 | 1 |
| karthwasten (Reach) | 8 | 0 | 0/9 | 2 | 0 | 0/3 | 0 | 0 | 1 | 4.25 | 9 | 7 | 0 | 8 | 5 | 7 | 1 |
| old_hroldan (Reach) | 2 | 0 | 0/1 | 0 | 0 | 0/0 | 0 | 0 | 0 | 0.0 | 2 | 2 | 0 | 0 | 3 | 0 | 0 |
| dushnikh_yal (Reach) | 2 | 0 | 0/2 | 0 | 0 | 0/1 | 0 | 0 | 0 | 3.0 | 5 | 1 | 0 | 1 | 0 | 0 | 1 |
| falkreath (Falkreath) | 90>91 | 18>17 | 6/98 | 5>3 | 0 | 0/0 | 0 | 1 | 0 | 0.31>0.49 | 87>93 | 32>33 | 0 | 0 | 5 | 1 | 1 |
| helgen (Falkreath) | 60>58 | 13>25 | 5/65 | 1 | 0 | 0/2 | 0 | 0 | 1 | 0.45>0.55 | 61>56 | 20>22 | 0 | 0 | 4 | 2 | 0>1 |
| morthal (Hjaalmarch) | 58>55 | 2 | 5>4/69 | 2 | 0 | 0/0 | 0 | 2 | 0 | 0.4>0.56 | 59>60 | 32>35 | 0 | 0 | 3 | 0>1 | 0>1 |
| stonehills (Hjaalmarch) | 14>11 | 0 | 1/14 | 1>0 | 0 | 0/1 | 0 | 4>3 | 0>1 | 2.64>3.0 | 27>23 | 12 | 0 | 0 | 0 | 4>3 | 1 |
| dawnstar (Pale) | 88>89 | 0 | 6>7/108 | 1 | 0 | 0/0 | 0 | 0 | 0 | 0.33>0.6 | 98>93 | 33>34 | 0 | 0 | 3 | 0>1 | 1 |
| winterhold (Winterhold) | 59>65 | 0 | 1>2/78 | 1>2 | 0 | 0/0 | 0 | 0 | 0 | 0.44>0.68 | 64>71 | 30>32 | 0 | 0 | 5 | 1 | 0>1 |
| college_of_winterhold (Winterhold) | 0 | 0 | 0/3 | 0 | 0 | 0/0 | 0 | 0 | 0 | 0.0 | 2 | 1 | 0 | 1 | 0 | 1 | 0 |

Generic towns (darkwater_crossing, ivarstead, stonehills, dragon_bridge) are re-planned from a seeded random stream, so
any code change that consumes random numbers earlier reshuffles them: darkwater_crossing is 3-4 buildings either way.

Also from the static lints: reach lint (Skyrim) 329 places, 0 settlement errors, 9 isolated, all minor dungeons
300-600 blocks from a road (`lower_steepfall_burrow`, `broken_oar_grotto`, `steepfall_burrow`, `bleakcoast_cave`,
`brinewater_grotto`, `rimerock_burrow`, `frokis_shack`, `knifepoint_ridge`, `pale_imperial_camp`; none tourist-grade,
the trail range is 300); placement lint 0 problems (every scatter/landmark/minor-site kind has a purpose, needs and clue);
layout lint 0 errors, 9 warnings (7 land, 2 roads).

## 3. Failure classes (frequency x severity), and what was fixed

1. **Capitals missing basic trades (high severity, 4 of 9 capitals).** Morthal and Dawnstar had no shop counter,
   Morthal and Winterhold no smithy, Falkreath and Winterhold no apothecary; Dragon Bridge (a Skyrim town with an
   inn in its own name table) came out with no inn at all, because one failed lot search meant the trade was dropped.
   FIXED (generic): `Zoning.ensureTrades` / `placeFree` guarantee an inn, a general store and a smithy for every
   village-size-or-larger named place, an apothecary from town up and a temple for cities, on the free ground nearest
   the square with a footpath to the street. Runs for generated towns after the wants, and for adopted hand-laid
   layouts (all nine capitals, Helgen, Dragon Bridge) before the town fill. Special variants (strongholds, mills,
   meaderies, inns) are skipped. Result: every capital now has an inn, a merchant, a smith and an apothecary;
   dawnstar and winterhold still have no priest (the temple lot did not fit: see 5).
2. **Hand-laid layouts get no yards behind their homes (medium, 400+ buildings).** `Zoning.adopt` ran the town fill
   (+38..+74 homes in the small capitals) but never the backyard pass, so Falkreath had 91 buildings and 28 props.
   FIXED: `adopt` now calls `backyards` for every home after the fill (collision-checked like the generated path).
   Props per building in falkreath / morthal / dawnstar / winterhold / helgen: 0.31 / 0.40 / 0.33 / 0.44 / 0.45 to
   0.49 / 0.56 / 0.60 / 0.68 / 0.55 (gardens and pens are counted apart from props, so it reads lower than it looks).
   Still low: see 5.
3. **Checker false positives** (section 0). 155 "unreachable doors" were 75, 201 "unsealed" were 72, 54 "floating"
   components (was about 2100).
4. **Not fixed, needs an in-game look.**
   * **Whole crowns with no trunk in forest towns** (falkreath 17, helgen 25, solitude 9, riften 4): a spruce or oak of
     100-1000 leaves hanging 15-20 blocks above the floor, some touching a roof. `OrphanSweep` leaves any component that
     touches the edge of its writable box, and a crown that spans a chunk seam always does. Safe fix needs the neighbour
     chunk loaded (sweep on chunk load, or when the second of two adjacent chunks finishes); a box-edge rule change would
     delete the visible half of trees whose trunk is across the seam. Helgen's count also includes leaf-only ivy clumps on
     its ruined wall (checker noise, on purpose).
   * **Gate with no road** (riverwood 1/3, darkwater_crossing 1/2): a secondary gate 25 blocks from the nearest road end,
     just over the 24-block threshold; the gate lane planner leaves a gate with no neighbour in its direction as a plain gate.
   * **Props on a street**: 47, of which markarth 26 are market stalls drawn on the authored plaza's street line; windhelm 7.
   * **Doorsteps (77)**: about half are a slab or a counter at the doorstep, the rest a door onto a one-block-deep nook;
     none seals a building. Open wall sides (76): forges and stables by design, then porch-less back and side walls of
     houses on stepped footprints (the shell is smaller than the footprint).
   * **Stray one-block fragments (1206)**: all stripped logs (porch and awning rafters) and a few barrels/fences; the
     OrphanSweep keeps them on purpose. Cosmetic.
   * **Unstaffed workplaces** (16): karthwasten 8 field anchors for 7 residents, riverwood 3, kynesgrove 2. The housing
     model caps residents per tier on purpose (whiterun 44 of 243 beds); farms outnumber the people of a farm village.

## 4. Per hold: what needs authored attention in the next content pass

| hold | settlement | state | wanted |
|---|---|---|---|
| Whiterun | whiterun | complete (165 bld, 12 shops, 2 smiths, inn, temples, 56 guard posts) | none; 8 doorsteps are on hill steps |
| Whiterun | riverwood | 12 bld, 3 gates, 1 unserved; 3 unstaffed jobs (hunter, labourer, bard) | bard/hunter/labour anchors (lumber mill yard), lane to the west gate |
| Whiterun | rorikstead | fine | farm fields as work anchors |
| Haafingar | solitude | big, 144 bld, 16 doorsteps, 9 floating crowns | crown sweep (4), blue-palace interior |
| Haafingar | dragon_bridge | generic TOWN plan, 7 buildings; inn now guaranteed but the layout is not the real one | authored plan around the bridge (Four Shields Tavern, Dragon Bridge Mill, Dragon Bridge Overlook) |
| Eastmarch | windhelm | 144 bld, 3 quarters, 2 inns | grey quarter props; 7 props on streets |
| Eastmarch | kynesgrove | generic, 14 residents on 24 beds | authored: Braidwood Inn, boar-tusk plaza |
| Eastmarch | darkwater_crossing | 4 buildings, 1 shop, 1 unserved gate | authored: Darkwater Crossing inn + Black-Briar area |
| Rift | riften | 73 bld, 5 guild anchors, 10 shops | fine; 4 floating crowns |
| Rift | ivarstead / shors_stone | generic, complete trades | Vilemyr Inn graveyard-side lanes, Shor's Stone mine |
| Rift | heartwood_mill | one mill building, no homes, no inn (3 beds) | authored: mill + worker houses + lumber yard |
| Rift | largashbur | orc stronghold generic: 2 buildings, 1 resident | needs the full Orc set: longhouse, forge, cave mouth, pens, hides |
| Reach | markarth | 102 bld, 26 stalls on the plaza street line | fine; check the stall/street split in game |
| Reach | karthwasten | 8 bld, 9 beds, 8 unstaffed farmer anchors | authored mine + 4 more homes (Sanuarach Mine) |
| Reach | old_hroldan | inn + 1 house, 0 props | yards, a stable, the Hroldan shrine |
| Reach | dushnikh_yal | orc stronghold generic: 2 buildings, 1 resident | same as Largashbur |
| Falkreath | falkreath | 91 bld, only 17 authored (rest town fill), 17 floating crowns, no wall | authored: Dead Man's Drink + graveyard are placed, needs real streets (the fill invents 74 homes) and a Hall of the Dead priest cell |
| Falkreath | helgen | ruin + ~50 fill homes, 25 floating comps (ivy on wall + 2 big crowns) | decide the destroyed-town story: the fill builds a living town round a burnt keep |
| Hjaalmarch | morthal | 55 bld, now has shop + smith; swamp props | authored: Highmoon Hall interior, Thaumaturgist's Hut, burnt house, alchemy yard |
| Hjaalmarch | stonehills | 11 bld, no inn | add Stonehills inn only if wanted; lumber yard anchors |
| Pale | dawnstar | 89 bld, mines; store + smith + alchemist now, no priest | authored harbour quarter, Temple of Mara lot (the generic temple lot did not fit), barracks |
| Winterhold | winterhold | 65 bld (6 ruined), shop + smith + alchemist now, no priest | authored: collapsed quarter, a small shrine; keep the ruins |
| Winterhold | college_of_winterhold | one landmark: 2 beds, 1 resident, 1 job | the mage residence: dormitories, enchanting/alchemy rooms, ~10 residents, a bridge-side inn |

## 5. Why the lists still carry

* Dawnstar / Winterhold: no priest. `TEMPLE` is 11-15 x 17-21; the lot search found none in the built area. A shrine
  (5x5) fallback in `ensureTrades` would fix it; deliberately not added to avoid a tiny temple in a capital.
* The four small capitals are thin because the authored plan is 12 specs; the rest is `fillGaps`. They read as full
  (density 38-43%) but all their homes are generic. Authored streets are the remaining step.

## 6. Files changed by this sweep

* `mod/src/main/java/com/skycraft/tamriel/settlement/grammar/Zoning.java`: `ensureTrades`, `placeFree`; `run` and
  `adopt` call them; `adopt` runs `backyards` after the fill.
* `tools/qa/town_checks.py`: floating (built-structure rule), sealed (best of three rows), doorstep (one-block step and
  lateral exit).
* `docs/SKYRIM-SWEEP.md` (this file).
* Content pass 2 (section 7): `mod/src/main/resources/skycraft/tamriel/cityplans/{dragon_bridge,falkreath,morthal,dawnstar,
  winterhold,college_of_winterhold,heartwood_mill,largashbur,dushnikh_yal,karthwasten,kynesgrove,darkwater_crossing,
  old_hroldan,riverwood,helgen}.json` and `index.json`; `mod/src/main/lang/skycraft/settlements.json` (113 name keys);
  `plan/CityPlan.java`, `plan/CityPlans.java` (`use` / `wealth` on buildings and queue entries),
  `grammar/Fabric.java` (`bare*` chains, `use` in the queue), `grammar/Zoning.java` (`fill_cap` / `yard_cap`, stamp
  plan grammar pieces), `settlement/SettlementDevCommands.java` (`/skysettle terrain`),
  `tools/marquee/validate_cityplans.js` (uses, reach of a java-base plan).

Verification: `tools\qa\qa.ps1 -Server` PASS (build, assets, items, quests 1072/1072, data, layout, reach, world boots,
magic selftest, population lint 0 errors).

## 7. Authored plans for the thin settlements (content pass 2)

Fifteen of the section 4 settlements now have a city plan (`resources/skycraft/tamriel/cityplans/<id>.json`, `base: none`
except the College, which keeps its hand-laid stack and bridge as `base: java`). Each plan keeps its gates exactly (the
gate list is copied from `SettlementAnchors.gates`, so the road planner's contract is unchanged), pins its named
buildings, draws its streets as nets and lets the fabric pass (`grammar/Fabric`) put frontage on them; the old town
fill that invented 33-74 homes is capped by `fabric.fill_cap`. Quest NPCs of these places get a named lot or an open
square at their quest offset. Numbers are `skysettle plan` + `skypop` on the `Nirn` seed (headless, no client),
before > after:

| settlement | buildings | built ratio | developed | beds | residents | unstaffed jobs |
|---|---|---|---|---|---|---|
| dragon_bridge | 7 > 36 | 0.07 > 0.14 | 0.24 > 0.41 | 11 > 49 | 10 > 20 | 0 > 0 |
| falkreath | 91 > 84 | 0.34 > 0.33 | 0.52 > 0.70 | 93 > 124 | 33 > 40 | 0 > 0 |
| morthal | 55 > 65 | 0.34 > 0.33 | 0.45 > 0.67 | 60 > 96 | 35 > 41 | 0 > 0 |
| dawnstar | 89 > 98 | 0.35 > 0.37 | 0.47 > 0.68 | 93 > 168 | 34 > 42 | 0 > 0 |
| winterhold | 65 > 52 | 0.39 > 0.33 | 0.51 > 0.68 | 71 > 70 | 32 > 40 | 0 > 0 |
| college_of_winterhold | 0 > 10 | 0 > 0 (pieces lie beyond 0.8 r) | 0.38 > 0.38 | 2 > 13 | 1 > 11 | 1 > 0 |
| heartwood_mill | 1 > 9 | 0.05 > 0.23 | 0.10 > 0.43 | 3 > 11 | 2 > 9 | 0 > 0 |
| largashbur | 2 > 13 | 0.13 > 0.35 | 0.23 > 0.69 | 3 > 20 | 1 > 10 | 1 > 0 |
| dushnikh_yal | 2 > 11 | 0.13 > 0.30 | 0.22 > 0.67 | 5 > 12 | 1 > 9 | 1 > 0 |
| karthwasten | 8 > 30 | 0.20 > 0.29 | 0.37 > 0.63 | 9 > 44 | 7 > 21 | 8 > 0 |
| kynesgrove | 13 > 43 | 0.31 > 0.36 | 0.51 > 0.69 | 24 > 66 | 14 > 21 | 2 > 0 |
| darkwater_crossing | 4 > 13 | 0.13 > 0.09 | 0.27 > 0.38 | 9 > 15 | 8 > 12 | 0 > 0 |
| old_hroldan | 2 > 10 | 0.25 > 0.34 | 0.37 > 0.62 | 2 > 16 | 2 > 9 | 0 > 0 |
| riverwood | 12 > 34 | 0.19 > 0.20 | 0.33 > 0.44 | 10 > 39 | 10 > 20 | 3 > 0 |
| helgen | 58 > 61 | 0.32 > 0.37 | 0.56 > 0.67 | 56 > 52 | 22 > 19 | 0 > 0 |

`built ratio` is the sweep's buildings-over-area figure at 0.8 of the plan radius (`Fabric.measure`); `developed`
counts yards, pens, gardens and streets too. Beds are resident beds (`Housing.capacity`). Every job now has a bed.

* **Dragon Bridge**: a stone arch (Dragon Bridge) joins the two flank gates over the river; Toll Square and the
  Four Shields Tavern by the south gate, Dragon Bridge Mill with its log yard on the south-west bank, the Overlook tower
  on the north-west height, a bank street on the north shore. The old generic town had 7 buildings.
* **Falkreath**: Pine Road from the gate to Dengeir's Hall, Market Row across it, west and east lanes; Dead Man's
  Drink on the row, the Hall of the Dead and two graveyards on the east lane, guard quarters, a lumber yard. The town
  fill is off, so every home sits on an authored street (was 17 authored plus 74 invented).
* **Morthal**: Highmoon Hall at the head of the road, the Thaumaturgist's Hut with two herb gardens behind it, the
  Burnt House left ruined, the sawmill, and five plank walkways (width 2) over the marsh.
* **Dawnstar**: White Hall, the Temple of Mara on its own lot (the generic temple lot never fitted), barracks, a harbour
  street with drying racks and a fishmonger, the quicksilver and iron mine heads joined by mine lanes. The map has no
  sea inside the town box, so the harbour quarter is fishing lanes, not quays (dock pins would be dropped).
* **Winterhold**: the collapsed quarter is pinned as seven ruined houses, a ruined inn and a ruined tower with no
  street through it; a small shrine of Talos with a priest; the living town keeps its main street and row. Ruins give
  no beds, so beds track residents (70 / 40).
* **College of Winterhold**: the stack and bridge are untouched; a campus at the mainland end: two dormitory manors,
  masters' lodge, scholars' house, the Bridgeward Inn, alchemy workshop, enchanting workroom, forge and provisioner.
  11 residents on 13 beds (the stack's two tower beds plus the campus).
* **Heartwood Mill**: the main street follows the pre-planned Treva road; mill, sawmill (grammar use `sawmill`), two
  lumber yards, felled ground, log yard, the Sawyer's Rest and worker rows.
* **Largashbur / Dushnikh Yal**: palisade ring with its gate, chief's longhouse, two longhouses, the forge, a cave
  mouth (mine adit), two pens, a hide camp with tanning racks, a fire ring, plus a shaman's tent (Largashbur) or a
  shrine of Malacath (Dushnikh Yal). 10 and 9 residents on 20 and 12 beds.
* **Karthwasten**: Sanuarach Mine and a second adit on the west slope, Karthwasten Hall, a bunkhouse, the Miners'
  Rest and the ore yard; the eight unstaffed jobs are gone (44 beds for 21 residents).
* **Kynesgrove**: Tusk Square, Braidwood Inn, the Malachite Mine and a second adit, a shrine, a farmhouse with fields.
* **Darkwater Crossing**: a timber causeway over the ford between the two gates, Ford's Rest, a ferry landing, the
  mine on the west slope, east-bank and south-east streets (13 buildings).
* **Old Hroldan**: the inn kept, a stable, the Hroldan shrine, gardens and a pen, two named houses.
* **Riverwood**: a stone bridge to the north gate and a pier to the west gate (both gates stand in the lake), the lumber
  mill with a timber store and log yard, a hunting camp, the Sleeping Giant Inn, Alvor's smithy; the hunter, the
  labourer and the bard are now staffed.
* **Helgen**: not a town: the keep is a ruined hall (`ruined` palace) with a ruined watchtower, the wall is rebuilt, ten
  ruined houses lie in the north half on a frontage-less track (`bare_north`), survivors' tents round two campfires,
  stalled carts and crates outside the south gate, the Legion barracks, the Charred Tankard and Mason's Forge.

Checks on this pass (headless server, `Nirn`): `tools/marquee/validate_cityplans.js` 0 errors (38 plans);
`town_checks.py` over the 15 generated towns: 627 exterior doors, 38 unreachable (6 %, the rate of section 1), 0
blocked stair ends, 0 props inside buildings, 0 dead road stubs, floating crowns falkreath 17 > 8 and helgen 25 > 13;
`population_lint.py` 0 errors. Props standing on a pre-planned road polyline rose (falkreath 16 before banners were
cut, heartwood 8 before its street was aligned): the polylines run through the town interior and the paver only paves
outside the gate ring. Largashbur's gate reads "no road" only because the far end of its road lies in a region this
reduced world never generated.

Plan format additions (all optional): building and queue entries take `"use": "<grammar use>"` (sawmill, warehouse,
mage_tower, fishery...) and `"wealth"`; `fabric.fill_cap` / `fabric.yard_cap` bound the town fill that runs behind
authored streets (0 = none); a net tagged `bare*` is a road with no frontage (ruins, tracks); the dev command
`/skysettle terrain <id>` writes the planner's terrain model (height and wet flag every 3 blocks, local frame) to
`skycraft-previews/terrain_<id>.json` so a plan can be authored against real ground.
