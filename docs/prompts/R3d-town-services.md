# R3d Town services: trainers, notice boards, signposting, courts, reactivity, arrival

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R3d-town-services.md`." Up to two agents: R3d-1 (society:
> trainers, courts, reactive lines) and R3d-2 (quest and guide: boards, signposting, arrival moment). Disjoint files.

## Goal

The engine roles and services the Skyrim tier table needs (DESIGN-PRINCIPLES 4.4, 4.9) and that every town in every
province will use. Daily routines already exist (housing, jobs, schedules, `society/NpcPlacement`); the **trainer role
is missing** and is the first item here.

## Context to read

* `docs/world/DESIGN-PRINCIPLES.md` 0 (non-negotiables 3, 6, 8), 4.0 G5, G11, 4.4 (tier table and roles), 4.8, 4.9,
  4.11 MS12, MS13, MS19, 5.4 F-COURT, F-BOARD, F-SIGN, F-REACT.
* `docs/FEATURES.md` (society, quests, guide sections), `docs/QUESTS.md`, `docs/SKYRIM-SWEEP.md` (population lint
  and `/skypop`), `docs/LORE-WEB.md` (rumours reveal places).
* Code: `society/{NpcRole,Workplaces,Housing,Schedule,NpcPlacement,NpcDialogue,Settlers}.java`,
  `quest/{RadiantQuests,Locate}.java`, `quest/story/RadiantTemplates`, `guide/**`, `skills/**` (training hooks).

## Write sets

* **R3d-1:** `mod/src/main/java/com/skycraft/society/**`, `mod/src/main/java/com/skycraft/skills/**` (the training
  service only), `mod/src/main/java/com/skycraft/dialogue/**` (reactive keys), `resources/skycraft/society/**`,
  `lang/skycraft/society.json` (own keys), `tools/qa/population_lint.py`.
* **R3d-2:** `mod/src/main/java/com/skycraft/quest/**` (boards, journal map links), `mod/src/main/java/com/skycraft/guide/**`
  and a new `signpost` package if needed, `roads/Signposts`-style sign text only, `resources/skycraft/quests/boards/**`
  (new), `lang/skycraft/quests.json` and `guide.json` (own keys).

## R3d-1 steps

1. **Trainer role.** `NpcRole.TRAINER` with `skill`, `cap` (journeyman 50, expert 75, master 90 as Skyrim's bands),
   `fee` scaling with level, five sessions per player level, a training screen through the existing dialogue
   (`Dialogue` option), server-validated. Placement by tier from data (`society/trainers.json`): capital 5 in distinct
   skills (abs. 4), city 2, town 0-1; the skill mix comes from the settlement's region sheet (L5 industry, L7 guilds,
   L8 faith) via `WorldSheets` when present, else from the culture. Trainers get a bed, a workplace and a schedule like
   any resident; `why` recorded.
2. **Courts (F-COURT).** Roles ruler, steward, court mage, housecarl or guard captain in every capital and city; seat
   at the palace/keep anchor; data per settlement with a default per culture.
3. **Reactive lines (F-REACT).** Dialogue keys `react.<region>.<deed>` for guards, innkeepers and courts (deeds from
   quest completion, faction rank, crimes, dragon kills, region story steps); a finished side chain can switch a key.
   Engine and key resolution only; R4 and V8 write the lines.
4. **Population lint.** Report per settlement and tier: named residents, roles, trainers by skill, court roles,
   unstaffed workplaces; error on a capital without a court or with < 4 trainers once data exists.

## R3d-2 steps

1. **MS12 notice boards.** A board block or sign in every town and larger (placed at the market or inn anchor) with 3-6
   live jobs from radiant templates aimed at real, uncleared dungeons 300-1,500 blocks away; reward scaled by job size;
   shared by the party; visible to every player at that board; refresh every two in-game days; jobs persist in level
   data. `/skycraft-board list <settlement>`; a `board` selftest.
2. **MS13 signposting.** Compass hints for undiscovered places within 150 blocks; quest-giver markers in towns;
   the region-entry banner gains a "rumours here" line; journal entries link to their place on the map; night beacons
   on towers and smoke from inhabited camps (data flags read by the existing builders); road signs name the next
   settlement, the next inn and the nearest dungeon (sign text from the road plan's `linkName` and places).
3. **MS19 arrival moment.** First entry to a region: banner (exists), a music sting key, one narrator line in the region
   voice (lang key `arrive.<region>`), and a courier letter or rumour pointing at the region's hold-conflict hook when
   an MS1 story file exists.

## Verification while working

Dev server (`devserver.ps1 run "/skypop whiterun|/skycraft-board list whiterun" -Wait`), `population_lint.py`,
`qa.ps1 -Server` selftests, Tier 0 tests if R2 is merged.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Trainers in Skyrim | `population_lint.py` | every capital >= 5 in distinct skills (abs. 4), every city >= 2 |
| Courts | `population_lint.py` / F-COURT | 100% of capitals and cities, >= 3 roles |
| Training works | new `trainer` selftest | pays, raises the skill, respects cap and sessions |
| Boards | `/skycraft-board list` + F-BOARD | 100% of towns and larger; >= 3 live jobs each at real places |
| Board jobs real | `board` selftest | target exists, uncleared, 300-1,500 blocks away |
| Signs | F-SIGN (`flow_audit.py`) on Skyrim | >= 95% of junctions and settlement exits |
| Reactive keys | F-REACT key loader | keys resolve; fallback to generic lines |
| Lint | `population_lint.py` | 0 errors |
| QA | `qa.ps1 -Server -Rebake` | PASS incl. selftests |

## Merge and report

Merge R3d-1 then R3d-2 (`_COMMON.md` section 7). Update WORLD-PLAN. Report per section 8.

**Size:** L.
