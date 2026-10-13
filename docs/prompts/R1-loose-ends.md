# R1 Loose ends

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R1-loose-ends.md`." Two agents in parallel (R1a, R1b).

## Goal

Close every known defect left by the earlier phases, plus the R0 playtest items, before new systems pile on top.

## Context to read

* `docs/DUNGEON-AUDIT.md` sections "Skyrim identity", "Analyzer", "Open in game".
* `docs/WORLDGEN-PIPELINE.md` sections 6 (P1 numbers and residue), 7b (P3 numbers, known limits), 9 (P6 limits).
* `docs/FIRST-PLAYTEST.md` known gaps, `docs/PLAYTEST-4.md` (R0's triaged notes), `docs/PLAYTEST-1.md` (search
  "Invisible enemies").
* WORLD-PLAN section 8 items tagged `R1`.

## Agent R1a: dungeons and creatures

Write set: `mod/src/main/java/com/skycraft/dungeons/**`, the creature renderer registration in
`mod/src/main/java/com/skycraft/creatures/**` (client package only for renderers), `tools/qa/dungeon_*.py`,
`tools/audit_entity_renderers.py`, the variant/roster fields of the affected entries in
`resources/skycraft/tamriel/places/*.json` and `resources/skycraft/dungeons/**`.

1. **Analyzer blockers.** Reproduce with `skycraft-dungeon-qa place` on the dev server: `frossel` (den: 2 rooms stuck
   in terrain), `shadowgreen_cavern` (80 spots with no way back out), `brucas_leap_redoubt` (boss room unreachable).
   Fix the layout or terrain cause in the builder, not per place. Then run one dungeon per variant (45) on three seeds
   (Nirn, 7777, 2428247) and fix what else shows (DUNGEON-AUDIT names benkongerike, ashfallow_citadel, fahlbtharz,
   ysgramors_tomb, lower_steepfall_burrow on other seeds).
2. **`deepwood_redoubt`.** A Forsworn stronghold in lore but the `barrow` variant builds draugr. Give it a Forsworn
   roster (and Forsworn dressing) while keeping its word wall: a roster override on the place, not a new variant.
3. **`tower_of_mzark`.** Variant `mzark` has no structure and never starts. Build it from the Dwemer kit (a tower
   descending to a lift and an observatory chamber with a sphere mechanism) or map it onto an existing Dwemer layout
   with its own set piece; it must generate and be enterable.
4. **Shipwrecks and cabins.** The identity lint still flags shipwrecks (no dry post for the sign; 11 at 1.5 flags each)
   and a few cabins and dragon lairs (no motif on rough ground). Put the sign on the hull or a beached crate, the note
   in the captain's chest; let motifs fall back to a flat spot within 6 blocks.
5. **Invisible enemies.** Run `py -3 -I tools/audit_entity_renderers.py`; every registered living entity (and every
   vanilla one we replace) needs a renderer and a model layer; spiders first (the playtest's invisible spider). Add the
   check to `qa.ps1`'s static set if it is not there.
6. **Lectern books.** `PlaceLorePlacer` puts `skycraft:book` items on lecterns, which a vanilla lectern does not show;
   use the `SkyNote` written-book path there too.

## Agent R1b: roads, sea and survival

Write set: `mod/src/main/java/com/skycraft/{worldplan,roads}/**`, `sailing/**` route code,
`resources/skycraft/tamriel/ports.json`, `survival/**` (bedroll only), `tools/qa/{roadplan_lint,road_measure,reach_lint,p3_corridors}.py`.

1. **Off-ground mountain vertices.** 1,080 road vertices and 11 highway vertices sit > 8 blocks off the ground in the
   Velothi range and Wrothgar (switchbacks did not fit). Widen the switchback corridor search, allow a tunnel or a
   gap bridge where the cut or fill exceeds the class limit, or reroute; P2's cut/fill must not be the only answer for
   highways.
2. **6 failed trail and lane links; 33 junction pins missed by > 0.6.** Find them in `roadplan_lint.py` output; fix
   the router (start/end inside water or a steep pad are the usual causes).
3. **P3 measure failures left after P5.** Run legacy `road_measure.py` on the 4 corridors; the 11 gaps and 8 drops
   were inside settlement zones (P5's ground). Fix whatever P5 did not.
4. **Riverwood's unserved gate** (`FIRST-PLAYTEST` roads): a gate lane or a street must reach it.
5. **Solitude's sea routes** to Dawnstar, Windhelm and Raven Rock fall back to straight lines. Give the sea router an
   open-water path round Haafingar's headlands (coast distance field) and fail loudly when no path exists.
6. **Bedroll warning.** `Tried to load a block entity for block skycraft:bedroll` at worldgen: the block has no block
   entity type or the structure template carries an NBT tag for one. Fix at the source; `log_summary.py` must not show
   it.

## Verification while working

Dev server (`devserver.ps1 run`, `/skycraft-dev regen`, `quick_corridor.ps1 <a>:<b> -Replan` after planner changes),
`roadplan_lint.py`, the dungeon analyzer per dungeon. Full gates once at the end.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Dungeon blockers | `skycraft-dungeon-qa place`, 45 variants x 3 seeds | 0 |
| Identity flags | `dungeon_identity_lint.py` | total <= 10; shipwrecks and cabins 0 |
| deepwood_redoubt | analyzer roster | Forsworn; word wall present |
| tower_of_mzark | `/skycraft-dungeon-qa view tower_of_mzark` | generates and enters |
| Renderers | `audit_entity_renderers.py` | 0 missing |
| Road plan | `roadplan_lint.py` | 0 errors, 0 failed links, 0 junction pins > 0.6 |
| Off-ground | `roadplan_lint.py` stats | 0 highway vertices, <= 0.1% road vertices > 8 off ground |
| Corridors | `full_corridors.ps1` and legacy `road_measure.py` | all zeros |
| Sea routes | route log / `/skycraft-sail routes` | 0 straight-line fallbacks |
| Bedroll | `log_summary.py` on `qa.ps1 -Server` | 0 warnings |
| Playtest items | `docs/PLAYTEST-4.md` | every R1 item fixed or moved with a reason |

## Merge and report

Merge R1a then R1b (`_COMMON.md` section 7; `full_corridors.ps1` after R1b), one commit each. Update WORLD-PLAN
(R1 `done`, numbers) and START-HERE. Report per `_COMMON.md` section 8.

**Size:** M (two agents, 1-2 days each).
