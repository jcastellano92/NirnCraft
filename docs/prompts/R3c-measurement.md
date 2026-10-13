# R3c Measurement: flow audit and floors per region

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R3c-measurement.md`." One agent (Python and data).

## Goal

Make every gate in the plan computable with one command per gate, offline, from baked data. After R3c a province
phase can prove DESIGN-PRINCIPLES 5.5 for a region without a human counting anything.

## Context to read

* `docs/world/DESIGN-PRINCIPLES.md` 4.1-4.9 (the floors), 4.11 MS1, MS7, MS20, 5.3-5.5 (FLW metrics, definition of
  done).
* `docs/PARITY.md` sections 0, 1, 5 and the wave 0 status notes (known counter gaps).
* `tools/qa/parity.py`, `tools/qa/dungeon_audit.py`, `tools/qa/reach_lint.py`, `tools/qa/placement_lint.py`,
  `tools/qa/ecology_report.py`, `tools/qa/roadplan_lint.py` (readers of the baked files to reuse).

## Write set

`tools/qa/flow_audit.py` (new), `tools/qa/floors.json` (new), `tools/qa/story_lint.py` (new),
`tools/qa/verbatim_check.py` (new), `tools/qa/dungeon_stats.py`, `tools/qa/parity.py` (filters, tier report, the two
counter widenings only), `tools/qa/qa.ps1` (`-Flow` switch), `mod/src/main/resources/skycraft/quests/story/README.md`
(the MS1 schema), the `class` field on dungeon places (data only; a script adds it from variant and size).

## Steps

1. **`floors.json`.** One machine-readable copy of DESIGN-PRINCIPLES 4.3 (per-km² medians and absolute floors), 4.4
   (tier table), 4.5 (dungeon classes and length bands), 4.8 (quests) and the FLW floors (5.4). Every tool reads it.
2. **`flow_audit.py [--province P] [--region R] [--json out]`.** Computes from `roadplan.bin`, `sceneplan.bin`, the
   places, the macro height raster, quest JSON, scenes and travel data: F-HOOK, F-VISTA (ray test every 4 blocks from
   local maxima within 150 blocks of a road), F-WEENIE (from 1.7-block eye height, 1,500 blocks), F-DETOUR, F-SIGN,
   F-TRAVEL, F-ENC, F-WONDER, F-QUIET (from `ecology_report.py`), F-LOOP (from `dungeon_audit.py`), F-QUEST-RADIUS,
   F-STARTS, F-REWARD-RATE, F-COLLECT, F-LEVEL, F-HUBS, F-COURT, F-BOARD, F-ORPHAN, F-REACT, F-MICRO (from
   `region_audit.py`), F-WHY (from `worldsheet_lint.py`). A metric whose data does not exist yet prints `n/a (needs
   <phase>)`, never 0 or PASS. Under 60 s for all regions.
3. **`parity.py`.** `--province` and `--region` filters on every mode including `--assert-floor`; `--tiers` prints
   each settlement against the 4.4 tier table (named NPCs, interiors, named buildings, roles, merchants, trainers,
   inns, quests); widen the uniques counter to `uniques_*.json` and `n_say` to `society_<province>.json` (PARITY wave 0
   notes).
4. **MS7 class tag.** `class: short | major | marquee | minor | unmarked` on dungeon places, assigned by a script from
   variant, size and marquee status (reviewable data, not code); `dungeon_stats.py` reports room counts per class
   against the length bands; `hidden: true` places counted as unmarked.
5. **MS1 schema and `story_lint.py`.** Document `quests/story/<region>.json` (hook, 3+ ordered quests with `requires`,
   a `consequence` per quest, finale); the lint checks counts, registered places, the hook within 800 road blocks of a
   region entry, consequences declared. Write one sample (a stub for Whiterun) to prove it.
6. **MS20 `verbatim_check.py`.** On the owner's machine only: compares our text (lang, lore, quests, books) with a local
   corpus in `.reference/corpus/` by 8-word shingles, prints hits, exit 1 on any. Skips with a clear message when the
   corpus is absent (other machines). Never commits the corpus.
7. **QA wiring.** `qa.ps1 -Flow` prints the per-province FLW summary (informational) and runs `story_lint` and
   `verbatim_check` when their inputs exist.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Flow audit | `flow_audit.py` | every FLW metric printed for all 71 regions, < 60 s; unbuilt inputs print `n/a` |
| Filters | `parity.py --region whiterun --tiers` | 4.4 table per Whiterun settlement |
| Province assert | `parity.py --province skyrim --assert-floor SET` | runs and exits with the right code |
| Classes | `dungeon_stats.py` | every dungeon place has a `class`; bands reported |
| Story lint | `story_lint.py` on the sample | passes; a broken sample fails |
| Verbatim | `verbatim_check.py` | exit 0 on current text, or the real hits listed for fixing |
| Baseline | WORLD-PLAN section 7 | Skyrim and one other province's FLW numbers recorded |

## Merge and report

`_COMMON.md` section 7 (data-only `class` changes touch places files: union, not overwrite). Report per section 8.

**Size:** M.
