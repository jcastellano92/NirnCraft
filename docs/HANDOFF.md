# Handoff (written when the weekly usage limit was hit; resumes after it resets)

Branch `tamriel-overhaul`, HEAD `15e46d7` (all QA gates green). Latest test build: `dist/Skycraft-client-instance.zip`
from `15e46d7` (11:14). Nothing is half-merged in the main checkout.

## Unfinished work (partial, UNCOMMITTED in agent worktrees; both agents died on the weekly limit)

| Worktree (`.claude/worktrees/…`) | What | Notes |
| --- | --- | --- |
| `agent-a9d792d954e135d8f` (27 files + 3 new) | Playtest round 5: (A) missing-texture audit and fixes for fire/charred blocks (magenta cubes in Helgen) + a failing QA asset audit; (B) intro flow: opaque screen from first tick, character select first, player placed in cottage before world shows, loading wait, floating rocks sweep (`CottageTerrain.sweepRocks` was being added); (C) cutscene shake/explosion intensity; (D) Helgen polish: consistent lootable bodies, one-liner dialogue only, remove floating lava/bucket chain/grave carpets, purposeful mourning/medical-tent/shrine scenes | Resume with SendMessage to the agent id (hex in the folder name) or a fresh agent in that worktree: review `git status`/`git diff --stat` first and finish. |
| `agent-aa85e53d82406902a` (18 files + 6 new) | (1) Landmark grounding (foundations, support validator, ground check); (2) lecterns open the Skycraft book reader UI for stories (intercept right-click, packet, reuse lore reader) | same |

## Open owner feedback not yet started
* Custom landmarks sometimes off the ground / lecterns don't open: in the second worktree above.
* Everything in `docs/FIRST-PLAYTEST.md` section 1b is unverified visually.
* Province plans that died on the rate limit earlier: partial `docs/world/provinces/{skyrim,cyrodiil,morrowind,elsweyr,valenwood,summerset}.md` (uncommitted); re-run those planning agents (prompts in WORLD-PLAN.md section 2 and the original task text).
* The client-startup smoke test (boot the real client to the main menu and fail on exceptions) is still not built; the
  duplicate-handler lint in `tools/qa/data_checks.py` covers the one crash class seen so far.

## How to resume
1. `git status`, then read `docs/START-HERE.md` and `docs/world/WORLD-PLAN.md` section 7.
2. Finish the two partial worktrees (merge with `bash tools/dev/merge_worktree.sh <worktree> <NAME>`, then
   `qa.ps1 -Server -Rebake`, commit, `tools\build_release.ps1`).
3. Then follow WORLD-PLAN: R1 loose ends, R2 test tiers, R3 systems, R4 Skyrim uplift to measured floors, provinces.
