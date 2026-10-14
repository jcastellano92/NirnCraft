# Town audit TO0 (2026-10-13): why the towns read badly, measured

Step TO0 of `docs/prompts/R4a-town-overhaul.md`. Read-only: no Java or data file changed, no server or client started.
Companion data: [`town_audit.json`](town_audit.json) (884 settlements, findings, acceptance numbers). Regenerate with
`py -3 -I tools/qa/town_audit/run.py` (reads the baked `streetplan.bin`; with a saved world it also flood-fills
Whiterun and Helgen over the generated blocks). The older offline audit of props and Haiku scores is
`docs/TOWN-AUDIT.md`; this one is about plans, reachability, sizes and people.

Code paths are relative to `mod/src/main/java/com/skycraft/` unless noted. "Plan" = the baked street plan
(`mod/src/main/resources/skycraft/baked/streetplan.bin`, inputs hash in `manifest.json`).

## 0. Result in twelve lines

1. **Whiterun's upper districts cannot be walked to.** In the saved world, floor 127 (Wind District: Jorrvaskr, temple,
   both manors, 47 buildings) has 0 reachable buildings and floor 135 (Dragonsreach, 8) has 0; floor 120 has 89 of 90.
   The main street runs flat into a 6 block wall with one stair block (F01).
2. **The planner's own lint said this was fine** (251 of 254 Whiterun doors "reachable"): it stops at the first street
   cell of any kind, it does not ask whether that street joins a gate (F02), and its heights are not the built ones (F03).
3. **Roads do not reach the gates of Solitude and Windhelm** (nearest road end 212 and 228 blocks from the gate; no GATE
   node; 3 of 197 and 3 of 231 doors reachable from the road ends) and Imperial City's is 71 away (F04).
4. **545 of 3,406 key buildings declare no entrance** (all 72 jails, 97 of 143 Bosmer, 73 of 181 Argonian, 22 of 156 Dunmer
   shops and inns, landmark seats), so the street plan cannot connect them at all (F06).
5. **No leader's seat exists in 32 of 58 cities, 62 of 106 towns and 2 of 14 capitals** (F05). Where one exists the
   steward, court wizard and housecarl have no work routine and the jarl only stands near the throne block (F15).
6. **The altar over a candle is `Outdoors.SHRINE`** (`grammar/Outdoors.java:265-272`): with a 1x1 yard the candle overwrites
   the pedestal and the altar floats one block up. 375 such shrines; 295 stand against a street, 10 on its band (F07).
7. **Crop fences in town** come from three generators with no zone test: `Zoning.backyards`, `Fabric.filler` and the
   "garden" feature, which builds a fenced crop plot while documented as a park. 586 plots in town cores (F08).
8. **"Cracks people cannot leave a home over" were not reproduced as pits.** What traps people in the Whiterun sample is a
   backyard garden fence built on the back door's doorstep (2 doors, F09). Vanilla carvers under towns are the open
   candidate (F10).
9. **Shops are small and bare**: 354 of 1,712 shops have 63 or fewer interior cells; the keeper has one anchor, no desk,
   no seat; the smith's forge, anvil, grindstone and tanning rack are outside in a lean-to; nothing is a trade symbol (F11, F12).
10. **Homes repeat and are tiny**: 60-73% of houses are 7x9 or smaller; Whiterun has 32 identical 9x9 and 28 5x5; three
    interior families exist in total (F13).
11. **Shops are unstaffed by design of the caps**: 8/3/2 merchants per capital/town/village, stalls take the slots first (F14).
12. **Room to grow exists but is fragmented**: a 31x41 leader's hall fits (free or by clearing gardens and pens) in 2 of 14
    capitals; a 13x15 shop in 38 of 58 cities. Reserve lots first, then fill (section 7).

## 1. Method and limits

* **Plan analysis** (`tools/qa/town_audit/plan_audit.py`): the 884 baked settlements are decoded (nodes 60,153, edges
  57,709, doors 12,936, plots 63,807, specs 64,549). A walk raster is built from street/lane/alley/bridge bands, stairs
  edges, doorstep paths, plaza rectangles and gate passages (plaza centre links excluded: they are virtual). Cells join
  by 8-neighbour with a height step of at most 1 (2 on stairs cells). Gates are the plan's GATE nodes (ROAD joins when a
  settlement has none). A key building is `reachable` when a door cell joins a gate.
* **Calibration**: the raster is conservative. Against the generated blocks it undercounts open-ground walks (Whiterun
  lower tier 79/90 raster against 89/90 world; Helgen 39/50 against 49/50). So `street_only` means "suspect, confirm in a
  world", while a level with about 0% reachable is a hard failure.
* **World truth**: `mod/run/world` holds generated Whiterun, Helgen and five farms (the QA world of 2026-10-13). A flood
  fill (`tools/qa/town_audit/bfs_truth.py`) treats a cell as walkable when a solid support block has two passable cells
  above, steps up at most 1, down at most 3, starting at the gate cells. Only those towns can be checked; every other
  town needs the harness (`qa.ps1 -Server` samples) and `bfs_truth.py <id> <radius>` on its world.
* **No Skyrim area medians exist in the repo.** `DESIGN-PRINCIPLES.md` 4.4 (the table `WORLD-PLAN.md` cites) holds counts
  only: interiors, named buildings, merchants, trainers, inns, roles. `.reference/` has no footprint data. Counts are
  compared to the floor in the JSON; footprint targets in section 5 are proposals for the owner to confirm.
* Terrain slope and water are not in the plan, so free-lot fits (section 7) are optimistic.

## 2. (a) How settlements are planned and built today

### Pipeline (named places)

| Stage | Class (file:line) | Decides |
| --- | --- | --- |
| Entry | `tamriel/settlement/SettlementStructure.java:170-198` | named place: baked layout `StreetPlan.layout(id)`; else `planPlace`. Generic towns, inns, sites (`:200-260`) are planned at generation time with **no street plan** |
| Plan | `worldplan/street/StreetPlanBuilder.java:76-85` `plan()` | `Plan` on the seed-free `StreetTerrain`, `keepOut` = planned road beds, then `Zoning.place`, `plan.resolve()` |
| Unique layouts | `plan/Cities.java:224-257` `build/buildJava`, `plan/CityPlans.java:113-132` | `base: java` (7: whiterun, solitude, windhelm, riften, markarth, imperial_city, college) runs the hand layout, pins, then fills; `none` (31) is fully authored JSON; `zoning` pins + `Zoning.generic` |
| Districts and lots | `grammar/Zoning.java:441-490` `run()` | skeleton (streets, gates, squares, canal), zones (centre, noble, craft, common, poor, gate), `seat()` (`:603`), `civic()` (jail, guild halls, player plots via `plan/Halls`, `HousePlots`), `wants()` trades by tier (`:567-601`), `placeWanted`, `frontage` of homes, `infill`, `backyards`, `lining`, `walls`, `outskirts`, `variety` |
| Organic streets and fill | `plan/Nets.java`, `grammar/Fabric.java`, `grammar/Features.java` | spline streets, houses along chains, gap filler (`Fabric.filler`), park/shrine/orchard features |
| Sizes | `grammar/Use.java:15-50` | footprint range per use; `Use.size` biases by wealth only, never by tier |
| Resolve | `StreetPlanBuilder.Resolver.run` (`:308-370`) | replays every builder to record doors and anchors, builds the walk grid, joins doors to streets, omits props on streets, emits plots and pads |
| Build | `SettlementPiece.java:160-190`, `build/Builders.java`, `Works`, `Interiors`, `grammar/Grammar+Programs`, `build/Landmarks` | one piece per spec, replayed in every chunk it touches, clipped to chunk and piece box |
| Paint | `worldplan/street/StreetPaint.java` | only doorstep paths and gate lanes <= 28 blocks at plan height; **streets are `Works.street` pieces on the real ground** |

`Bake.java:48-90` hashes the settlement planner and builder classes (Zoning, Cities, Plan, Spec, Works, Builders,
Grammar, Programs, Landmarks, Canvas, ...), so any edit there stales all four baked files.

### How the unique hand-made layouts are incorporated

They are not special-cased downstream: they are specs like any other. `Cities.whiterun` (`plan/Cities.java:349-440`)
emits terraces (`:281-297`, level 7 and 15), the Gildergreen/Skyforge/Talos landmarks, Dragonsreach, Jorrvaskr, the
temple, streets and named shops, then `fabric`/`zones`/`features` of `cityplans/whiterun.json` fill round them.
Immovable anchors are whatever carries `FIXED`, `SEAT`, `GROUND`, named buildings and LANDMARK specs; `Plan.fits`
(`Plan.java:171-185`) keeps later specs off them. 40 of 884 settlements have a unique layout
(`unique_layout` in the JSON).

### Where a planner rewrite plugs in

1. **Between the unique pins and the fill**, inside `Zoning.place` (called from `StreetPlanBuilder.plan:76-85`):
   a `Districts` pass that classifies the area (gate, market, craft, temple, noble, leader, garrison, poor) from the
   pinned anchors, lays the gate-to-centre spine and the leader's avenue as MAJOR STREET specs with explicit stair/ramp
   runs, and **reserves lots** (specs the existing `placeWanted`/`frontage` consume), before `Fabric`/`backyards` fill.
2. **A height-aware validator in `StreetPlanBuilder.Resolver`** after `graph()`: street components, gate-to-door
   reachability with a step <= 1 rule on the generated heights; failure feeds the `attempt` loop that `Zoning.run`
   already has (`:441`, reseeds lanes and lots).
3. **Tier links as their own pieces**: a stair/ramp street spec with `floor`/`floor2` like `BRIDGE` (`Works.bridge`
   `:497-540`), instead of the terrace-embedded run that the piece box clips.
4. Entrances: every builder must call `Canvas.entrance`, not `Canvas.door` (F06).

## 3. (b) Altars, crop fences and "cracks"

### The altar hovering over a candle (F07)

* Block pair: `skycraft:shrine_of_<divine>` (lectern fallback, `build/Props.java:58-60`) over a lit `minecraft:candle`.
* Generator: yard kind `Outdoors.SHRINE`, `grammar/Outdoors.java:265-272`. `c.set(0, g+1, 0, p.wallLow)` puts a
  pedestal at g+1 and the altar at g+2; then `Props.candles(c, w > 1 ? 1 : 0, g+1, 0, 3)`. For `w == 1` the candle goes
  to x = 0 and `Canvas.set` (`build/Canvas.java:266-271`) overwrites the pedestal. All town callers are 1x1
  (`Zoning.java:1098-1103`, `Features.java:213-216`). Seen in the world: Whiterun (40,-60) and (18,-61), y121 candle,
  y122 shrine, grass below.
* Why it is on or beside streets: `Zoning.lining` (`Zoning.java:1098-1103`) rolls every rank-1 street (including width-1
  footpaths and every segment of a curved chain, `Zoning.java:1649`, `Fabric.java:63`) at 1/3, half shrines, and offsets
  by 2 only when the segment is axis-aligned. `Plan.lineHits` (`Plan.java:214-223`) cannot reject a 1x1 against a
  width-1 or width-3 street (integer half width, strict `<`, samples every 2). `StreetPlanBuilder.omitProps` removes
  unanchored props on STREET bands in named, baked towns only; generic settlements have neither keepOut nor OMIT.
* Numbers (`town_audit.json`): 375 one-cell shrines (60 capital, 147 city, 76 town, 92 village); 295 have a street cell
  within 1 block; 10 centre on a street band; 3 more overlap one. Other altars checked and fine: `Builders.shrine`
  (5x5 SHRINE building), `Civic` memorial, graveyards, `Paver.placeShrine`.

### Crop fences and plots in the core (F08)

| Generator | Where | Rule that lets it into the core |
| --- | --- | --- |
| `Zoning.backyards` | `grammar/Zoning.java:1122-1160` | every home, inn, tannery, butcher: 1/3 (poor, common) or 1/2 (wealthy) a fenced 5x5 garden behind it; no zone, distance or plaza test |
| `Fabric.filler` | `grammar/Fabric.java:483-497` | any bare cell during density fill: 4/12 GARDEN 5x5 or 7x5, 2/12 PEN, facing fixed SOUTH |
| feature `garden` | `grammar/Features.java:240-248` | documented "small park", spawns `Archetype.GARDEN` which `Works.garden` (`:272-288`) builds as fence + farmland + crops + water |

Real FIELDs are ringed outside the built radius (`Zoning.java:1481`) and are not the problem. Counts of GARDEN/PEN/FIELD
specs with at least 4 buildings within 20 blocks and inside 0.4-0.6 of the reach: capital 307, city 219, town 34,
village 26 (Whiterun 15 gardens and 2 pens).

### Cracks and trapped doors (F09, F10)

* **Observed (Whiterun, built blocks)**: house specs 245 (back door at 105,-8) and 295 (door at -26,-71) open onto a
  `spruce_fence` on the doorstep cell with crops behind it; the planner lists both doors reachable. No 1-block pit, no
  missing street cell: of 5,204 plan walk cells, 464 lack a standable block, 408 of them in the unloaded east edge, 40
  are closed fence gates (F20), the rest fences. `Accessibility` (`build/Accessibility.java:80-98`) walks 6 cells, only
  for declared entrances, and a fence within 2 cells only increments `DOORS_BLOCKED`.
* **Candidate for real cracks (not reproduced)**: vanilla `cave`, `cave_extra_underground` and `canyon` carvers are in
  every biome (`tools/worldgen/biomes.py:2929`) and the plan never excludes them (`docs/WORLDGEN-PIPELINE.md:208,268,285`);
  planned floors use pre-carver heights (`Terrain`), streets are painted on `wground`, a 3-deep fill limit
  (`Accessibility.java:116-126`). `StreetPaint.cell` (`:209-263`) cuts at most a 2-deep trench and fills at most 4.
* The lint to add: door-to-street clearance over the painted blocks (BFS, step <= 1) and "no pit inside a footprint or
  within 3 cells of a doorstep" in `tools/qa/town_checks.py` `access_report` (it only looks at the cell in front of a door).

## 4. (c) Reachability and tier connection

Per settlement: `key_buildings[*].status`, `tier_connection`, `doors` in the JSON. Totals over 3,406 key buildings:

| Status | Count | Meaning |
| --- | --- | --- |
| reachable | 2,486 | door joined to a gate over the plan walk raster |
| street_only | 361 | planner found a street for the door, that street is not joined to a gate |
| unreachable | 14 | no walk at all |
| no_door | 545 | the builder declares no entrance (F06) |

| Tier (n) | settlements with an unreachable or street_only key building (jails excluded) | tier-disconnected | no leader hall | no GATE node |
| --- | --- | --- | --- | --- |
| capital (14) | 13 | 3 | 2 | 2 |
| city (58) | 52 | 1 | 32 | 2 |
| town (106) | 86 | 1 | 62 | 1 |
| village (400) | 105 | 3 | 381 | 33 |
| hamlet (306) | 22 | 0 | 306 | 19 |

Tier-disconnected (a level of at least 3 buildings with under 25% reachable): Whiterun, Solitude, Windhelm, College of
Winterhold, Bravil, and three foreign villages (Kjolvik, Tiger Plains, Tang Mo). `street_only` is inflated by the raster's
conservatism; the failures that matter are the ones below.

### The Skyrim set (plan raster; Whiterun and Helgen also by world flood fill)

| id | tier | doors from gate | key bldgs reachable | floor: reachable/buildings | connected | props on street | not reachable |
| --- | --- | --- | --- | --- | --- | --- | --- |
| whiterun | capital | 115/254 (world 146/254) | 7/14 | 120: 79/90, 127: 3/47, 135: 0/8 (world 89/90, 0/47, 0/8) | NO | 0 | Dragonsreach, Jorrvaskr, temple, hall of the dead, both manors, jail (no door) |
| solitude | capital | 3/197 | 0/15 | 68: 2/2, 79: 0/135 | NO | 3 | everything on the plateau; no GATE node, GATE spec 212 from the road ends |
| windhelm | capital | 3/231 | 0/10 | 66-68: 0/8, 70: 0/125 | NO | 1 | everything; no GATE node, GATE spec 228 from the road ends |
| riften | capital | 77/98 | 7/10 | 171: 49/57 | yes | 4 | Mistveil Keep (unreachable), hall of the dead, jail (no door) |
| markarth | capital | 118/138 | 7/11 | 187: 51/56, 191: 21/21, 196: 16/17 | yes | 3 | landmark seats and Cidhna Mine (no door) |
| dawnstar, falkreath, morthal, winterhold | city | 59-121 of 64-135 | 6-8 of 7-9 | one level each | yes | 2 | jails (no door), barracks, temple of Mara |
| college_of_winterhold | town | 0/12 | 0/8 | 113: 0/5, 116: 0/2 | NO | 2 | all (sea stack, no gate) |
| helgen | town | 39/50 (world 49/50) | 4/5 | 128: 22/26 | yes | 1 | one house (plan and world agree), temple (no door) |
| riverwood, rorikstead, ivarstead, kynesgrove, shors_stone, karthwasten, dragon_bridge | town | see JSON | | | yes | 0-4 | Dragon Bridge mill (unreachable), Karthwasten apothecary, Rorikstead store |

### Whiterun, in detail

* The hand layout (`Cities.java:349-440`) puts the Wind District on terraces at level 7 and Dragonsreach at 15
  (`terraces()`, `Cities.java:281-297`: tiles of up to 31x31, margin 0). The main street (street spec 131, (62,-42) to
  (62,-68), floor 120) is planned to ramp 120 to 127 over 26 blocks (edges 845-857). **In the world it is flat 120 until
  z -64, 121 at z -65, then a 6 block wall**: stone at 122-126, one `cobblestone_stairs` at y127, z -66, and a diagonal
  fragment of 2 blocks beside it. The remaining run of the terrace's stair (`Works.terrace`, `build/Works.java:481-494`,
  up to 14 blocks, 3 wide, at the middle of each tile's south edge) is not there: the piece box is footprint plus
  margin (`SettlementPiece.java:96-110`) and the canvas is clipped to it (`:163-168`). Mechanism confirmed as the
  clip by symptom (it stops at a chunk border) but not traced block by block.
* The east alley STAIRS edge in the plan, (138,-76,120) to (130,-76,126) (edge 988), is a 7 block wall in the world.
* The only STAIRS edges between levels in the plan: 120 to 126 (that alley), 127 to 129 and 129 to 135 (Dragonsreach
  steps, themselves steeper than 1:1).
* Wind District streets (spec 135 at z -80, 136, 137) are fine inside their level and join each other; no street spec
  links 120 to 127, and the 12 block gap between (62,-68) and (62,-82) shows even the plan has no link there.
* The plan lies about heights at 19 of 202 street and plaza nodes (up to 7 blocks): `grid()` raises the walk surface
  with `max(surf, floor)` of ground works (`StreetPlanBuilder.java:505-512`) while the settlement's CAMP clearing tiles
  (floor 120, GROUND, `Zoning.clearing:1777-1796`) and the site pad leave the hill cut at 120.

### Why the lint passes (F02)

`StreetPlanBuilder.Resolver.search` (`:965-1013`) returns at the first cell with any target (`T_STREET`, `T_PLAZA`,
`T_GATE`, `T_ROAD`) and allows steps of 2 at extra cost; nothing asks whether the street reached is connected to a
gate. `dReach` therefore means "the door has a street", not "the door is reachable from the gate".

### Gates and roads (F04)

Whiterun, Imperial City, Solitude and Windhelm are walled with one GATE spec each. Whiterun's road ends within 16
blocks of it. Imperial City has a GATE node (its predicted gate) but the built GATE spec is 71 blocks from the nearest
road end. Solitude and Windhelm have no GATE node: their GATE specs are 212 and 228 blocks from the nearest road end
(Solitude's gate and ramp face west, the planned roads arrive from the east and north). Gate lists are
`plan/Cities.java:196-215`; GATE nodes are made only for road ends within 16 blocks of `SettlementAnchors.gates`
(`StreetPlanBuilder.graph`, `:654`).

## 5. (d) Building sizes and interiors

### Sizes by class as baked (median over settlements of the median building; interior = (w-2)(d-2), first floor, before annex/porch)

| Class | capital | city | town | village |
| --- | --- | --- | --- | --- |
| leader hall | 27x28 / 678 | 13x15 / 143 | 13x15 / 143 | 13x21 / 209 |
| inn | 13x17 / 165 | 14x17 / 180 | 15x17 / 195 | 13x17 / 165 |
| temple | 13x19 / 187 | 11x17 / 135 | 13x19 / 198 | 5x5 / 9 |
| guild hall | 13x17 / 165 | 13x17 / 165 | 13x17 / 165 | - |
| garrison | 9x15 / 91 | 9x13 / 90 | 7x9 / 35 | - |
| general store | 7x9 / 35 | 9x11 / 63 | 9x11 / 63 | 9x11 / 63 |
| smithy | 11x9 / 63 | 12x10 / 72 | 11x9 / 63 | 11x9 / 63 |
| apothecary | 7x9 / 35 | 9x9 / 49 | 9x9 / 63 | 9x9 / 49 |
| jeweller, tailor, butcher, fletcher, bookseller | 7x9 / 35 | 7x9 / 35 | 7x9 / 35 | 7x9 / 35 |
| house | 7x7 / 25 | 7x9 / 35 | 7x7 / 25 | 7x7 / 30 |
| manor | 11x13 / 99 | 15x15 / 183 | 14x14 / 145 | 15x15 / 169 |

Capital shops are smaller than city shops, most likely because cityplan queue entries with `size` 0 take the `Archetype`
defaults of 7x9 (`plan/Archetype.java:9-35`): 41 of 41 `house` entries, 23 stores and 22 smithies (11x9) in the plans.
Skyrim: Whiterun inn 13x17, store 9x11, smithy 11x9, 132 houses; Windhelm inn 12x15; Riften store 10x12; Helgen store 7x9.

### How interiors are made

* **Procedural, not templated**: `Blueprint.of(spec)` (`grammar/Blueprint.java:109-216`) picks body, shape, roof, annex,
  storeys from `gseed`; `Grammar.rect` (`:79`) paints it; `Programs.furnish` (`grammar/Programs.java:32-158`) furnishes
  one program per Use; legacy archetypes use `build/Interiors.java`. `use_sizes` in the JSON has every Use by wealth.
* **Tier never changes size** (`Use.size`, `Use.java:94-101`); wealth biases by 0-0.6 of the range; `odd()` pushes even
  results up. Shrink fallbacks go below the Use minimum to 5 (`Zoning.java:879-880,931,1746-1747`; `Fabric.java:337,458`),
  so a cottage can be 5x5 (3x3 room, no bed, `Interiors.java:34`).
* L/T/U shapes put the room, counter and bar in the front bar only (`Blueprint.java:239-261`): a 9x11 shop with a wing has
  a 3x3 closet behind; a T or L inn has no eating tables (`Interiors.java:122` needs hall depth >= 8).
* **Layout families**: hovel, house, manor: three in total (`Programs.java:176`, `Interiors.java:48-66`). `gseed` varies
  only the exterior. `plain_every`/`small_every` exist (`CityPlans.java:321-322`, `Fabric.java:238-241`) and no plan uses
  them. `Zoning.variety` (`:1838-1862`) re-rolls `gseed`, never size.

### Equipment present (full matrix in `equipment_matrix`)

| Use | counter | desk + chair | forge / anvil | tanning rack | alchemy / brewing | drying herbs | bar | throne | advisor desks | rented rooms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| inn | bar (keg stock) | no | no | no | no | no | if hall >= 9 wide | no | no | 3-4 per upper floor |
| general store | yes | no | no | no | no | no | no | no | no | no |
| smithy | none | table + chair | yes, **outside** annex | yes, outside | no | no | no | no | no | no |
| apothecary | yes | no | no | no | stands + lab + cauldron | no (pots) | no | no | no | no |
| tannery | yes | no | no | yes | no | no | no | no | no | no |
| keep (grammar) | no | steward table, no chair | no | no | wizard corner | no | no | yes | 1 steward + 1 wizard | no |

Signs: one hanging sign per `Use.sign` building, text only (`Grammar.java:665-674`); banner colour is random per style,
not per trade. FLETCHER and BUTCHER never get their lean-to (`w <= 9` fails `w-3 >= 7`). The grammar `GUILD_HALL`
emits no anchors (the 77 baked guild halls are the legacy archetype and do).

### Against the Skyrim floor (counts, `tier_floors_4_4`)

| Tier | interiors | named buildings | merchants | inns |
| --- | --- | --- | --- | --- |
| capital (14) | median 126, 13 meet 27 | median 6, 0 meet 23 | median 4 + stalls, 0 meet 15 | 13 meet 1 |
| city (58) | 52 meet 10 | 4 meet 11 | 50 meet 4 | 52 meet 1 |
| town (106) | 90 meet 4.5 | 7 meet 3.5 | 105 meet 1 | 74 meet 0.5 |

"Named" counts spec names only (a lower bound; generic shops are unnamed).

## 6. (e) NPC routines

Roles, anchors and schedules are hard-coded Java (no data files): anchors are recorded at worldgen by interiors
(`c.anchor`, `SettlementAnchors.java:168`), `Housing.plan` (`society/Housing.java:129-250`) turns anchors into jobs
(`JOB_ORDER :311`, caps `:332-345`, a job needs a free bed), `SettlementPopulation.populate` (`:94-183`) spawns once,
`Workplaces.workplaceOf` (`society/Workplaces.java:163-227`) resolves the post (cached post, nearest anchor of the kind
within 128, a vanilla POI, else the bed), `Schedule` (`society/Schedule.java:38-80`) gives the activity by hour.

| Reason a person is not at the post | Verdict | Where |
| --- | --- | --- |
| shop has no keeper: merchant cap 8/3/2 and stalls claim anchors first | confirmed | `Housing.java:42,325-337`; dumps: Riverwood, Dragon Bridge traders and Rorikstead store unstaffed; 27 of 40 merchant anchors staffed in 7 towns |
| court wizard (MAGE): no workplace kind, no WORK branch | confirmed | `Schedule.java:59`, `Workplaces.java:157` |
| steward spawned as COURIER, not townsfolk: no routine by day | confirmed | `SettlementPopulation.java:340-350`, `NpcRole.java:98` |
| housecarl: no workplace; follows the jarl only if linked in the same pass | confirmed | `SettlementPopulation.java:146`, `Housing.java:332-334` |
| jarl only in tier 0; stands within 3 of the throne block, no sit | confirmed / likely | `Housing.java:332-334`, `SettlementPopulation.java:324` |
| keepers never restored after death or a missing bed | confirmed | `SettlementPopulation.java:119-122,183` |
| anchors outside entity-ticking chunks are skipped | confirmed | `SettlementPopulation.java:114`; 7 of 7 Dragonsreach/temple/Skyforge anchors never spawned in the QA world |
| sealed counter pocket; 3 failed moves then teleport watchdog | likely | `Programs.java:291-299`, `NpcGoals.java:1126-1143,1214` |
| Shelter sends civilians home when an enemy is within 36 | likely, transient | `NpcGoals.java:149-196` |
| shopkeepers at the post 08-20 only, innkeeper 06-24 | by design | `Schedule.java:64-69` |
| weather, festival, locks, crowd trimming, despawn, one NPC for several shops | unlikely | `SettlementCrowd.java:96-130`, `NpcEntity.java:766` |

In the QA world at 09:00 all 10 keepers that had a post stood within about 4 blocks of it: the clock works; what fails is
staffing and role wiring. The population lint (`tools/qa/population_lint.py`) covers 7 settlements, 0 errors, and does
not check roles against anchors, reachability or staffing. Job anchors by role, with the building that provides them and
the routine, are in `npc_jobs` per settlement and `routine_causes`; across capitals JARL is "standing only" in 12,
HOUSECARL/STEWARD/WIZARD "no work routine" in 10-11 each, and missing entirely where no seat exists.

## 7. (f) Size budget

Cells inside 0.85 of the reach (circle median 27,145 capital and city, 8,173 town, 2,289 village, 1,653 hamlet):

| Tier | occupied | soft works (gardens, pens, fields, props) | free | street cells |
| --- | --- | --- | --- | --- |
| capital | 19,354 | 2,788 | 6,102 | 6,429 |
| city | 13,440 | 1,525 | 12,029 | 3,107 |
| town | 3,465 | 760 | 3,929 | 888 |
| village | 614 | 213 | 1,248 | 209 |
| hamlet | 317 | 195 | 1,348 | 73 |

Lots that fit (with a 1 block margin, a street within 3 blocks, free as planned / after clearing soft works):

| Tier | leader hall | inn | shop | smithy | rich house |
| --- | --- | --- | --- | --- | --- |
| capital (14) | 31x41: 2 / 2 | 21x25: 4 / 5 | 15x17: 4 / 8 | 17x15: 4 / 8 | 15x17: 4 / 8 |
| city (58) | 25x31: 6 / 11 | 19x23: 10 / 19 | 13x15: 38 / 46 | 15x13: 38 / 46 | 13x15: 38 / 46 |
| town (106) | 19x25: 5 / 11 | 17x21: 8 / 32 | 13x15: 49 / 81 | 15x13: 49 / 81 | 13x13: 59 / 86 |
| village (400) | 15x19: 24 / 25 | 15x17: 24 / 25 | 11x13: 98 / 244 | 13x11: 98 / 244 | 11x11: 128 / 264 |
| hamlet (306) | 11x13: 165 / 184 | 13x15: 109 / 167 | 9x11: 209 / 228 | 11x9: 209 / 228 | 9x11: 209 / 228 |

Reading: free area is not scarce, **contiguous frontage lots are**. Houses are packed in rows round the streets by
`frontage`/`infill`, so larger buildings must be reserved before the fill, and a capital's seat, inn and 15 shops cannot
be added after the fact. The planner needs to reserve per tier (sizes in `size_targets`, to be confirmed):

* a leader's lot with a 7-9 block forecourt and an avenue to the nearest spine (capital 31x41 + 12; city 25x31 + 10;
  town 19x25; village chief 15x19), adjacent to a plaza or the spine end;
* one inn lot on the gate-side spine (21x25 / 19x23 / 17x21 / 15x17);
* 4-8 shop lots per capital (15x17 and smithy 17x15), 3-5 per city (13x15), 2-3 per town, on the spine and market square;
* 4+ house size classes with the larger ones nearest the centre; clear gardens and pens in the core first (soft works are
  9% of a capital's circle, 760 cells of a median town).

Hand layouts: Dragonsreach is 27x45, Windhelm's palace 23x35, Mistveil Keep (Riften) 19x25, the Blue Palace landmark
23x29, Markarth's keep landmark 55x25. Riften's seat is the only one under the capital target; none has a grand
avenue.

## 8. Acceptance tests the JSON provides

Numbers are "today" from this audit; targets are what the step must reach (`town_audit.json` fields in brackets).

| Id | Step | Test | Today | Target |
| --- | --- | --- | --- | --- |
| T1 | TO1 | key buildings with a door reachable from every gate [`key_buildings[].status`, `gates_reaching`] | 2,486 reachable, 361 street_only, 14 unreachable | 100% of buildings with a door |
| T2 | TO1 | no building without a declared entrance [`status == no_door`] | 545 | 0 (plus jails get one) |
| T3 | TO1 | levels connected, every pair of levels more than 1 apart linked by a stairs/ramp edge with slope <= 1 [`tier_connection`] | 8 settlements disconnected, 131 steep stairs | 0, 0 |
| T4 | TO1 | world flood fill from the gate over generated blocks [`world_truth`; `bfs_truth.py`] | Whiterun 146/254 doors, floor 127 0/47 | >= 99% doors, every level >= 99% |
| T5 | TO1 | every road end arrives at a GATE node, GATE spec within 24 of a road end [`gate_nodes`, `gate_spec_to_road_end`] | Solitude 212, Windhelm 228, Imperial City 71; 2+2+1+33+19 settlements with no GATE node | 0 |
| T6 | TO1/TO3 | house footprints per settlement with >= 8 houses [`house_repeats`] | median distinct 10 (capital) / 5 (town) / 3 (village); 29 villages, 18 towns under 4; small <= 7x9 share 60-73% | >= 4 distinct, no footprint above 25% of houses, small share <= 35% |
| T7 | TO2 | no shop under 99 interior cells (9x11 + annex), staff anchor + seat + trade equipment inside [`areas_by_class`, `equipment_matrix`] | 354 of 1,712 shops <= 63; equipment outside | 0 under target, all columns present |
| T8 | TO3 | leader hall present and >= size target, in capital, city, town chief [`key_summary.leader_hall`, `size_targets`] | missing in 2 / 32 / 62 | 0 missing |
| T9 | TO4 | every required role has anchor and routine [`npc_jobs[].status`]; schedule dump: >= 90% of shop owners within 6 blocks of their anchor 08-20 | only MERCHANT/INN/SMITH/PRIEST/ALCHEMIST ok | all ok |
| T10 | TO5 | no shrine over a candle, no prop with centre on a street band except lamp/sign, no garden/pen in core [`shrines`, `props_on_street`, `scatter_in_core`] | 375 hovering shrines, 562 overlapping props (179 signposts, 124 carts), 586 core plots | 0 / only furniture / 0 |
| T11 | TO5 | no fenced or pitted doorstep in the world [`world_truth.*.doorstep_problems`] | Whiterun 2 | 0 |
| T12 | TO6 | the 40 unique layouts keep their anchors: spec positions, names and flags of LANDMARK/SEAT/named buildings unchanged against this JSON | n/a | diff empty |

## 9. Ranked work list

Effort is in agent-days of one focused session each: S 0.5, M 1-2, L 3-5, XL 6-10.

### Before TO1 (cheap, independent, unblocks lints)

1. Declare entrances in every builder (`Canvas.entrance`): Shells 479/625/708, Jails, Landmarks, Marquee, Interiors 148/212
   (S, T2). Without it TO1's reachability cannot be measured for 545 buildings.
2. Shrine fix: place the candle beside, not on, the pedestal for w == 1, and fix `Plan.lineHits` (`ceil(w/2)`, `<=`,
   every block) (S, T10). Run the OMIT pass on generic settlements too.
3. Make the lint honest: street components and gate-to-door reachability in `StreetPlanBuilder`, plus `bfs_truth.py` in
   `qa.ps1` (M, T1/T4).

### TO1 planner: districts, avenues, steps (L-XL)

1. Tier links as pieces: stairs/ramp street spec with floor/floor2 (as `BRIDGE`) emitted by the planner and by
   `Cities.terraces`; drop the terrace-embedded run (M, T3, F01). Fix `grid()` heights (cut/fill as built) (M, F03).
2. `Districts` pass and lot reservations (section 2 plug-in 1): spine from every gate, avenue and forecourt to the
   leader's lot, frontage for every lot, size classes with >= 4 house footprints (L, T1/T6/T8).
3. Gate geometry: choose or add gates where roads arrive (rotate `back` or a second gate), join GATE nodes (M, T5, F04).
   **Risk**: gate positions feed `RoadPlanBuilder` (`SettlementAnchors`, `Cities`, `Zoning` are in the bake hash): roads,
   terrain (P2) and the scene plan re-bake together; existing worlds show seams.
4. Keep anchors: FIXED/SEAT/GROUND/named/LANDMARK are immovable inputs (T12).
5. Performance: the resolver runs 5-15 s on 8 threads for 884 places; the validator and a retry loop must stay under
   2x that. Determinism: derive every new random roll from `(seed, feature id)`, not the shared `x.r` stream, or every
   generic town re-plans (`Use.size` changes already do).

### TO2 recognisable shops, inns, civic (XL)

* Trade kits: exterior silhouette and sign symbol per trade (hanging sign with an item/emblem, not text), region variants;
  interior: counter with a seated clerk spot, customer area, stock, the trade's equipment inside (forge, anvil, quench
  barrel, grindstone, tanning racks, weapon racks, alchemy table, drying herbs), inn: common room, bar, kitchen, hearth,
  4+ rooms with beds and desks (L per trade family; 5-6 families).
* Raise `Use` ranges to the size targets and make shops/inns rectangular (no L/T) (S); fix shrink floors (S); fix sealed
  counters (S, F18); add anchor types COUNTER_SIT, DESK, THRONE_SIT, SERVER (S, shared with TO4).
* **Risks**: every piece replays per chunk, so bigger buildings cost more per chunk (a 31x41 touches 6 chunks and runs
  `Accessibility`, `OrphanSweep`, `RockSweep` each time, `SettlementPiece.java:170-185`): split halls into wings and
  measure structure-gen time. `Interiors.java`, `Props.java`, `Housing` are not in `Bake.CLASSES`; edits there do not stale
  the bake, edits to `Programs`/`Shells`/`Grammar` do.
* Enum ordinals are saved in pieces (`Archetype.java` "appended"): only append.

### TO3 leader's seat and real interiors (L-XL)

* Seat for every city and town chief where none exists (F05): hall sized by tier with throne room, steward office,
  advisor and mage chambers, family and servant quarters, kitchen, guard post, cellar; wired to the lot of TO1 (L).
* House families: 3+ interior layouts per size class with room logic, set `plain_every`/`small_every` or replace them
  with size-class quotas, per-region materials, no identical interiors within a settlement (L).
* Guild hall (grammar) must emit anchors or be replaced by the legacy hall (S).

### TO4 working people (M-L)

* Roles: STEWARD role actually used for the palace steward; WORK branches for HOUSECARL, MAGE (court wizard), STEWARD;
  jarl: throne court hours then dining; chieftain of towns gets a resident (`Housing.cap`) (M, F14-F16).
* Merchants: cap by building count, not 8/3/2; shop owners take anchors before stalls (S, F14).
* Anchors as data (COUNTER_SIT/DESK/THRONE_SIT) and sit behaviour (M).
* Restore: periodic re-populate for unfilled slots when chunk is ticking; stable slot keys (`SLOT_KEY` is
  `<settlement>#<anchor index>`: reordering specs shifts ids, so key by anchor type + building name) (M, F17).
* Verify with a full-day schedule dump: owners at post 08-20, home or inn after, locked-out owners go home; extend
  `population_lint.py` to check role vs anchor vs reachability (S).

### TO5 scatter and ground (S-M)

* Zone test for backyards/filler/garden feature (no GARDEN/PEN in centre/noble or within 6 of a plaza; the "garden"
  feature becomes a park of FLOWER_BED/TREE/BENCH) (S, F08).
* Door clearance: drop a back door when a garden fence would stand on its step, or move the fence; raise
  `Accessibility` to 6 deep and to a street join (S, F09).
* Carvers: exclude `cave`/`canyon` carvers from plot and street masks or refill after carving (M, F10; needs a world
  with a ravine under a town to prove).
* Lints: door-to-street BFS, no pit within a footprint, no prop on a street cell (S, T10/T11).

### TO6 roll-out (L-XL)

Per province in the order Skyrim, Cyrodiil, Morrowind, High Rock, Hammerfell, Elsweyr, Valenwood, Summerset, Black Marsh;
re-bake, run `town_audit/run.py`, compare T1-T12. Culture shells that never declared entrances (Bosmer, Argonian,
Dunmer) need T2 first. New worlds only: already generated chunks keep the old towns.

### Order and dependencies

`F06 entrances -> honest lint -> TO1 tier links + districts -> TO2/TO3 in parallel on the reserved lots -> TO4 (needs the
anchors of TO2/TO3) -> TO5 (independent; do the shrine and candle fix at once) -> TO6`. One re-bake after the TO1 planner
and one after each of TO2 and TO3; every re-bake changes roads and terrain, so QA (`qa.ps1 -Server -Rebake`,
`full_corridors.ps1`) runs once per step, not per edit.

## 10. Files

* `docs/world/town_audit.json`: `definitions`, `tier_floors_4_4`, `size_targets`, `use_sizes`, `equipment_matrix`,
  `findings` (F01-F20 with file:line), `routine_causes`, `aggregate`, `skyrim_priority`, `world_truth`, `settlements[884]`.
* `tools/qa/town_audit/`: `run.py` (entry), `plan_audit.py`, `budget.py`, `gates.py`, `make_json.py`, `final.py`,
  `bfs_truth.py`, `truth_summary.py`, `static_facts.py` (code facts with file:line), `use_sizes.json`. Intermediate
  output goes to `tools/qa/out/town_audit/` (gitignored).
