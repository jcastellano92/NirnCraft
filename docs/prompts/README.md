# Prompt library

One ready-to-run prompt per roadmap phase of `docs/world/WORLD-PLAN.md` section 5. Each is written so a brand-new
Claude Code session can execute it cold: what to read, the legal rule, the write set, how to verify cheaply, the
measurable gates, the merge procedure and the report format.

## Files

| File | Phase | Runs after |
| --- | --- | --- |
| `_COMMON.md` | Rules every phase shares: roles, reading order, legal line, world building first, fast loop, gates, merge procedure, report format, resuming | (read first, always) |
| `_province-steps.md` | The nine province steps V1-V9 (goal, write set, verification, gates) used by R4-R12 | (read with any province phase) |
| `R0-release-playtest.md` | R0 post-P5 release build and owner playtest | P5 merged |
| `R1-loose-ends.md` | R1a dungeons and creatures, R1b roads, sea and survival | R0 |
| `R2-testing-tiers.md` | Tier 0 JUnit plan-layer tests, Tier 1 hot-swap dev server | R0 |
| `R3a-world-sheets.md` | World-model sheets as data, loader, `why` fields, lint (MS22) | R0, `provinces/skyrim.md` |
| `R3b-micro-biomes.md` | Micro-biome layer (MS21) | R3a |
| `R3c-measurement.md` | `flow_audit.py`, floors per region, MS7, MS1 schema, MS20 | R3a |
| `R3d-town-services.md` | Trainers, notice boards, signposting, courts, reactive lines, arrival moment | R0 |
| `R3e-world-systems.md` | Travel networks, encounter library, festival calendar, level policy | R0 |
| `R4-skyrim-uplift.md` | Skyrim to the measured floors (steps S1-S5) | R3a-R3e |
| `R5-iliac-bay.md` | High Rock and Hammerfell: IB-S, IB1-IB8, IB9, IB-G | IB1-IB3 after R1; the rest after R4 |
| `R6-cyrodiil.md` | Cyrodiil V1-V9 | R5 (V2/V3/V5 slots) |
| `R7-morrowind.md` | Morrowind V1-V9 | R6 |
| `R8-black-marsh.md` | Black Marsh V1-V9 | R7 |
| `R9-elsweyr.md` | Elsweyr V1-V9 | R8 |
| `R10-valenwood.md` | Valenwood V1-V9 | R9 |
| `R11-summerset.md` | Summerset V1-V9 | R10 |
| `R12-foreign-lands.md` | Atmora, Akavir, Yokuda, Pyandonea, Thras V1-V9 at half density | R11 |
| `R13-endless-lands-p7.md` | P7: the plan-first pipeline per 4 km tile beyond the map | R2 (free slot) |
| `R14-items-affix.md` | Item condition, makers, enchantment names, storied items | R3a (free slot) |
| `R15-final-polish.md` | Docs, realms, performance, release build, owner sign-off | everything else |

## How to run a phase

1. Open Claude Code in `F:\Projects\Personal\Skycraft\Skycraft` (the main checkout, branch `tamriel-overhaul`).
2. Check `docs/START-HERE.md` "Next" and `docs/world/WORLD-PLAN.md` section 7 for what is runnable now (dependencies
   met, no conflicting globally serial step in progress, fewer than 3 agents busy).
3. Say: **"Read `docs/START-HERE.md` and `docs/prompts/_COMMON.md`, then run `docs/prompts/<file>` [step <id>]."**
   For province phases name the step (`R6-cyrodiil.md step V2`, `R5-iliac-bay.md step IB3`, `R4-skyrim-uplift.md step
   S2`); one step per session is the normal size.
4. The session orchestrates: it starts worktree agents (at most 3) with the write sets in the prompt, verifies with
   the fast loop, runs the gates once, merges (`bash tools/dev/merge_worktree.sh`, `tools\qa\qa.ps1 -Server -Rebake`),
   commits on `tamriel-overhaul` (never pushes), and **updates the WORLD-PLAN state table and START-HERE's "Next"
   line** before it reports.
5. Read the report (format in `_COMMON.md` section 8). If a gate failed, the phase stays `in progress`; run the same
   prompt again and it resumes.

Running two phases at once: only if their write sets are disjoint and neither is a globally serial step while the
other one also is (V2, V3, V5, R3b, R13). Say so in both sessions.

## How to resume

* **Same session, agent interrupted:** SendMessage to the agent (its id is the hex in the worktree folder name):
  "continue where you left off; finish and report".
* **New session:** run the same prompt; it reads WORLD-PLAN section 7, finds the phase `in progress`, lists the
  worktrees (`git worktree list`, `git -C <path> status --short`, `diff --stat`) and restarts an agent **in the
  existing worktree** with "Uncommitted work from a previous attempt is already in this worktree; review it first and
  finish it rather than starting over" (`_COMMON.md` section 9).
* **Merged but not recorded:** if `git log` shows the phase's commit but section 7 does not, update section 7 first.

## Writing or changing a prompt

Keep the shape: header line, Goal, Context to read, Write set, Steps (or Specifics per step), Verification, Gates
(table: gate, tool, floor), Merge and report, Size. Gates must be measurable by a named tool. Put shared rules in
`_COMMON.md` or `_province-steps.md`, not in every file. When a phase's plan changes, change WORLD-PLAN section 5 and
the prompt in the same commit.
