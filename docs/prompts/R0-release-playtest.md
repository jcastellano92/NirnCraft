# R0 Post-P5 release build and playtest

> Paste into a new Claude Code session in `F:\Projects\Personal\Skycraft\Skycraft`:
> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R0-release-playtest.md`."

## Goal

P5 (streets to every door) is the last pipeline phase before content work. Land it, cut a release build the owner can
play, write the checklist he plays from, and turn his notes into work items for R1. This is the first build in which
roads, crossings, vegetation masks, streets and scenes all come from the baked plan.

## Context to read

* `docs/WORLDGEN-PIPELINE.md` sections 3, 7b, 8 (P5 contract) and the P5 result section once it exists.
* `docs/FIRST-PLAYTEST.md` (the last checklist; rewrite it), `docs/FINISH-PLAN.md` section 2 (QA harness).
* WORLD-PLAN section 7: the P5 row tells you whether P5 is merged.

## Write set

`docs/FIRST-PLAYTEST.md`, new `docs/PLAYTEST-4.md`, `docs/world/WORLD-PLAN.md` (sections 7 and 8),
`docs/START-HERE.md` ("Next" line). `dist/` is built, never committed. No mod code (a release blocker found here is
fixed by the smallest change and listed, or moved to R1).

## Steps

1. **P5 status.** If WORLD-PLAN shows P5 `in progress`, find its worktree (`git worktree list`, newest with
   `worldplan/street` changes), read its report or `git diff --stat`. If its gate is not met, stop and report; R0 waits.
   If it is met but not merged, merge it (`_COMMON.md` section 7) and run its gate yourself:
   unreachable doors <= 1% on the Skyrim sweep sample (`town_checks.py`), every GATE node joins a road vertex.
2. **Full gates, once.** `tools\qa\full_corridors.ps1` (the 4-corridor gate) and `tools\qa\qa.ps1 -Server -Rebake`.
3. **Release build.** `powershell -File tools\build_release.ps1` (bakes explicitly, then `qa.ps1 -Server -Bake`, then
   the Prism instance). Confirm `dist/Skycraft-client-instance.zip` is fresh and built from the current commit.
4. **Checklist.** Rewrite `docs/FIRST-PLAYTEST.md` for this build (keep its structure: boot, what to look at, known
   gaps, commands, how to report). New since the last checklist: plan-painted roads and crossings (bridges, culverts,
   fords, stairs, tunnels), streets to every door, planned scenes (waysides, crossroads signs, cemeteries, lore-web
   clues), vegetation masks. Put the R1 known defects under "known gaps" so the owner does not report them again.
5. **Hand over.** Tell the owner the build is ready and what to look at (15-30 minutes of play). Do not launch the
   client. When the owner returns notes (in this session or a later one), write them into `docs/PLAYTEST-4.md` as a
   triaged table: item, where (coordinates), class (bug, look, feel, idea), the phase that owns it (R1a, R1b, a later
   phase, or "won't fix" with a reason). Append R1 items to WORLD-PLAN section 8 tagged `R1`.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| P5 merged | WORLD-PLAN row, `git log` | merged |
| Unreachable doors on the sweep sample | `town_checks.py` | <= 1% |
| Corridors | `full_corridors.ps1` | 0 gaps, 0 drops > 1 without a step, 0 unbridged, 0 tree-blocked |
| Release QA | `build_release.ps1` (`qa.ps1 -Server -Bake`) | PASS |
| Instance zip | `dist/Skycraft-client-instance.zip` timestamp vs commit | fresh |
| Checklist | `docs/FIRST-PLAYTEST.md` | rewritten for this build |

## Merge and report

Commit the docs (`_COMMON.md` section 7, docs only), set R0 `done` (or `waiting for owner notes`) in WORLD-PLAN, set
START-HERE's "Next" to R1 and R2. Report in the `_COMMON.md` section 8 format.

**Size:** S.
