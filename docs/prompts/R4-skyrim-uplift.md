# R4 Skyrim uplift to the measured floors

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R4-skyrim-uplift.md`
> step S<n>." Steps S1-S5 in order; S2, S3, S4 may run as three parallel agents once S1 is merged.

## Goal

Skyrim is the measure, and our own Skyrim must meet it. Today it scores 95 on average but **53 on Settlements**
against the real game: the gap is depth per settlement (named people, interiors, named buildings, trainers, courts),
not count. After R4 all nine holds pass DESIGN-PRINCIPLES 5.5, and every system built in R3 has been proven on real
content before it rolls out to the other provinces.

## Context to read

* `docs/world/provinces/skyrim.md` (all ten sections; the design for this phase).
* `docs/world/DESIGN-PRINCIPLES.md` first principle, 0, 4.0-4.9, 5.5.
* `docs/SKYRIM-SWEEP.md` sections 4, 5, 7 (authored attention per hold), `docs/ECOLOGY.md`, `docs/DUNGEON-AUDIT.md`
  (Skyrim identity), `docs/PARITY.md` (Skyrim rows), `docs/MARQUEE.md` (Skyrim entries are under the other provinces'
  neighbours; check `tools/marquee/marquee.json` for Skyrim ids), `docs/LORE-WEB.md`.
* `docs/world/SCALE-AND-GEOGRAPHY.md` 5.1 (Skyrim and Solstheim; Solstheim belongs to Morrowind's province data) and
  8.10 (missing Skyrim places: Amol village, ruined Snowhawk).

Province `<P>` = `skyrim`; regions = whiterun, falkreath, the_rift, eastmarch, the_pale, winterhold, hjaalmarch,
haafingar, the_reach. Skyrim's geography is canonical: **no V2 step** (no vector or anchor moves), only additions.

## Steps

| Step | = province step | What (Skyrim specifics) | Agents |
| --- | --- | --- | --- |
| **S1 Sheets and micro-biomes** | V1 + V3 (data) | `tamriel/world/skyrim.json` from `skyrim.md` section 1 (R3a's pilot may already be it: complete and lint); every hold's MS21 micro rules from section 2 (R3b's pilot: complete them); L2 weather tables and music cues checked against the sheet | 1 |
| **S2 Settlements** | V6 | All 24 swept settlements plus every other Skyrim settlement at the 4.4 tier counts: named NPCs (capital 73, city 20, town 9, village 3, hamlet 2), interiors, named buildings, roles, merchants, trainers (R3d), courts, people with problems and secrets; the SKYRIM-SWEEP section 4 list (Solitude's Blue Palace interior, Windhelm's Grey Quarter, Riverwood's lumber anchors, Karthwasten's Sanuarach Mine, the Orc strongholds' full set, the College's residence, Helgen's story, Dawnstar and Winterhold priests: open item O6); regional products from L5 in every shop | 1-2 |
| **S3 Dungeons and secrets** | V4 + V7 | Unmarked sites at 0.57/km² (`hidden: true`) with sheet reasons; power sites (word walls) at 1.5/km²; key relics (claw analogue) 0.43/km²; signature theme per hold (Forsworn in the Reach, shipwrecks in Winterhold, spriggan groves in Falkreath...) checked; F-LOOP for majors; missing places Amol and ruined Snowhawk; minor sites and stashes moved into the plan if cheap (open item O7) | 1 |
| **S4 Quests and reactivity** | V8 | A hold conflict chain of 3+ quests per hold (MS1 schema, consequences); 12 deed-keyed reactive lines per hold (R3d keys); F-STARTS >= 4 per hold; board offers live (R3d); readables and skill books at the 4.3 densities; region voice complete | 1-2 |
| **S5 Flow and gate** | V8 (encounters) + V9 | Encounters >= 8 per hold and wonder >= 3 (R3e data); F-VISTA, F-WEENIE, F-DETOUR fixes where a hold fails (an XL landmark or a ridge-top ruin placed for a sightline); then the V9 gate for all nine holds | 1 |

Each step: owned files, verification and gates as in `_province-steps.md` for the matching V step, restricted to
Skyrim's data. Java changes are out of scope here (they belong to R3); if a step needs one, make it minimal and list it.

## Gates (R4 as a whole)

| Gate | Tool | Floor |
| --- | --- | --- |
| Sheets | `worldsheet_lint.py --province skyrim` | 0 errors |
| Densities | `flow_audit.py --province skyrim`, `parity.py --province skyrim` | every 4.3 row at `max(abs, median x km²)` per hold |
| Tier table | `parity.py --province skyrim --tiers` | every settlement >= its tier's absolute floor; tier medians met |
| Parity | `parity.py --province skyrim --assert-floor SET,NPC,QST,DUN,CRE,FLO,STY,LND,LOR,MUS,ITM` | exit 0; **SET >= 95** |
| Flow | `flow_audit.py --province skyrim` | every FLW floor of 5.4 (optional-layer metrics excluded) |
| Text | `novelty_lint.py --strict`, `verbatim_check.py` | 0, 0 |
| Reach | `reach_lint.py` | 0 isolated major sites |
| Identity | Haiku blind test (G1) | >= 80% of holds named correctly from 3 renders |
| QA | `qa.ps1 -Server -Rebake`, `full_corridors.ps1` | PASS, all zeros |

## Merge and report

One merge and commit per step (`_COMMON.md` section 7). At S5, write the results into `docs/world/provinces/skyrim.md`
section 10 and the WORLD-PLAN state table. Report per section 8, plus the owner's in-game checklist (five places to
visit and what to judge).

**Size:** XL (about three weeks of agent work, three agents rolling).
