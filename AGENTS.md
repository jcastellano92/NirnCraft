# NirnCraft: instructions for Codex and other coding agents

The Git repository is this directory (`Skycraft/` inside the workspace). Work on the owner's checked-out branch;
the renderer branch is `atlas-render`. Never push unless the owner explicitly asks. Commit small green steps locally.

## Read before working

Read `CLAUDE.md`, `docs/START-HERE.md`, and `docs/AGENT-RULES.md`: their project, legal, server/client, testing and
resource rules apply to every agent, regardless of provider. For renderer work, read `docs/ATLAS-PLAN.md` first;
it supersedes the old order in `docs/ATLAS-STATUS.md`. Use `docs/README.md` as the map and `docs/COMPONENTS.md` to
choose checks. Weather, world expansion and later renderer stages stay parked until their stage is authorised.

## Coordination

The owner requests the existing orchestrator/worker/reviewer workflow. Use focused subagents when useful, with
at most one active child alongside the orchestrator. Parallel reading is fine; heavy jobs share the machine queue.
Give each task its stage, owned files, a concrete question, gate and expected evidence. A reviewer is read-only.
Use `.codex/agents/` roles when supported; otherwise include their instructions in the delegated task explicitly.
The owner chose Terra for gatekeeping and Sol for higher reasoning: `gpt-6.1-terra` for the gatekeeper, visual
reviewer and focused workers; `gpt-6.1-sol` with high effort for renderer review and stuck reasoning. Use these
explicit models when spawning (with a focused task rather than full-history inheritance when required by the client).
Claude tier names are not Codex model IDs.

Workers edit only their own assigned files in a newly assigned worktree. Inspect `git worktree list` and each
candidate's status first. Existing `.claude/worktrees/` contain unfinished work: do not reset, clean, stash, stage,
commit or otherwise change someone else's checkout. Read it with `git show`/`git diff` and recover it deliberately.
Only the orchestrator integrates; no branch is ready merely because it has commits. Preserve dirty work separately
and require the branch's checks and evidence before merging. Keep current ownership and pending work in
`docs/CODEX-WORKFLOW.md`; update the renderer plan at milestones and WORLD-PLAN section 7 on merges.

## Execution and evidence

* Use `py -3 tools/dev/agent_job.py "<agent>: <job>" -- <command>` for heavy work. This delegates to the existing
  `E:/NirnCraft-scratch/locks/heavy.py` queue; never create a second scheduler. Check free RAM first.
* GPU timing/game jobs use `--exclusive`. Live jobs also need `--loop middle|outer --why "<reason>"` before the label.
  Keep GPU jobs under five minutes; split by view/variant. Follow owned jobs, print progress and inspect stalls.
  Never kill a process you did not start. Do not bypass denials; use the existing escalation procedure.
* Three loops: saved-scene/offline checks first, one small live probe only for a question offline cannot answer,
  route/gallery/relaunch at a finished milestone. Launch the disposable client only through the harness.
* Java: `py -3 tools/dev/build_263.py --offline build`. Native changes: `harness.py run check-native` plus
  `check-kernel-budget`. Queue these commands through `agent_job.py`. Re-bake worldgen changes before installing.
* Run `harness.py gate --base <explicit base>` before integrating. An offline gate is not a live stage gate.
  Use `--dry` to see its scope. Do not weaken or conceal failures, missing checks or unreviewed pictures.
* Give visual evidence to an independent reviewer following `docs/agents/visual-reviewer.md`. Name exact compared
  images and metrics. The fixer cannot confirm its own visual fix. Codex/Claude availability is not a visual verdict.
* Preserve GPU kernel budgets, bounded dispatches, no-private-static rules, and result agreement before speed claims.

See `docs/CODEX-WORKFLOW.md` for portable review settings, monitoring, branch recovery and the current handoff.
