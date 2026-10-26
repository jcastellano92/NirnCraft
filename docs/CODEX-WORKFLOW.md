# Continuing ATLAS with Codex

The owner requested continued renderer development on 2026-10-26, preserving the Claude workflow that worked:
one orchestrator, focused worktree workers, independent reviewers, a shared machine queue, and measured gates.
The renderer's goals and order remain [ATLAS-PLAN.md](ATLAS-PLAN.md), not a new roadmap.

## Instructions and roles

Codex enters through [AGENTS.md](../AGENTS.md). Project roles live in `.codex/agents/`: `stage_worker`,
`renderer_reviewer`, and `visual_reviewer`. The project config permits one active child alongside the orchestrator.
The owner chose `gpt-6.1-terra` for gatekeeping and focused work, and `gpt-6.1-sol` with high effort for deeper
renderer review. These choices are explicit in the role files; no Claude aliases are translated into model names.
If a client cannot select a custom role, the orchestrator passes the corresponding instructions in the task.

The role/config format and instruction discovery follow the official OpenAI documentation for
[subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents) and
[AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md).
The installed CLI's `codex exec --help` is checked before changing its invocation.

Workers receive stage/gate, branch/worktree, owned files, question, expected evidence and stop conditions. They
commit small tested steps only on their assigned branches. The orchestrator reviews and integrates. Read-only
reviewers never edit, launch the game or consume GPU slots. Review findings name file:line, trigger and consequence.
Use [docs/agents/visual-reviewer.md](agents/visual-reviewer.md) for images; missing evidence stays unverified.

## Shared queue and monitoring

`tools/dev/agent_job.py` forwards arguments to the existing `heavy.py`; it does not replace locks, caps, FIFO,
timing isolation, gate rules, logs or escalation handling. `NIRNCRAFT_HEAVY` can point to a relocated scheduler.
It sets the scheduler's existing judge-command hook to `agent_review.py`, so gate/triage can use Codex while the
Claude account is unavailable. An existing `HEAVY_GATE_CLAUDE` override is preserved. The legacy hook name belongs
to the scheduler; it does not require Claude.

```powershell
py -3 tools/dev/agent_job.py --status
py -3 tools/dev/agent_job.py "worker: harness checks" -- py -3 tools/harness/harness.py run --suite harness --tier check
py -3 tools/dev/agent_job.py "worker: Java build" -- py -3 tools/dev/build_263.py --offline build
py -3 tools/dev/agent_job.py --exclusive --loop outer --why "finished stage; route gate" "worker: game route" -- py -3 tools/harness/harness.py run atlas-route
```

Check free RAM first. GPU jobs are at most five minutes: split views/variants, never one long comparison batch.
Use `agent_job.py --follow <label>` or `--status <label>`. The orchestrator checks `activity.py`,
`process_report.py --brief` and the escalation/feedback stream under `E:/NirnCraft-scratch/locks/` at check-ins.
Run the existing `escalation_watch.py` as a tracked background process while working, inspect its output between
steps, and re-arm after it expires. Codex tool sessions replace Claude's Monitor/Bash background handles; they
do not resume old agents or monitors automatically. Never infer health just from elapsed time; inspect progress.

`py -3 -u tools/dev/agent_watch.py --root <worker-checkout> --minutes 29` follows the actual harness event logs
and shared scheduler/gate/feedback ledgers in one tracked tool session. It prints test failures, run/review
completions and queue decisions as they arrive. The orchestrator reads that session between edits and while
waiting for jobs, investigates failures immediately, and re-arms it on expiry. This is local orchestration;
no messages are sent to other people or external services by the watcher.

## Portable independent review

`tools/dev/agent_review.py` runs a fresh headless CLI session for each judgment. `auto` prefers installed Codex,
then Claude. A failed provider is reported; there is no hidden retry/fallback or manufactured clean verdict.
Choose explicitly when desired:

```powershell
$env:NIRNCRAFT_REVIEW_PROVIDER = 'codex'  # auto | codex | claude
# Optional: use an exact model; otherwise Codex uses gpt-6.1-terra and Claude uses haiku.
# $env:NIRNCRAFT_REVIEW_MODEL = '<owner-selected model>'
py -3 tools/harness/harness.py review --pages <run-agent-folder>
```

Codex receives image attachments, a read-only sandbox, no project instruction chain, no saved session and no user
tool configuration. Its auth remains available. Prompts and final verdicts use the existing harness protocols;
provider/model identity is recorded in review logs and issue confirmations. Scratch is on E: when available,
otherwise outside the repository under the workspace's `.work/agent-review`; override with
`NIRNCRAFT_AGENT_SCRATCH`. The scheduler shim deliberately ignores its legacy Claude `judge_model` when selecting
a Codex model; the gatekeeper uses `gpt-6.1-terra` (override with `NIRNCRAFT_GATEKEEPER_MODEL`). Hard gate
decisions remain the scheduler's. `NIRNCRAFT_REVIEW_MODEL` independently selects the visual-review model.

Run adapter protocol checks through `harness.py run check-agent-review`. A protocol unit test is not an actual
image-review acceptance test: exercise a real headless review after changing the CLI integration.

## Recovery inventory (2026-10-26, initial base a938b173)

The user's handoff ends at `a938b173`. The main checkout was clean. Existing worktrees were inspected without
modification; dirty work is not included by merging its branch tip.
Annotated recovery tag: `before-codex-atlas-render-2026-10-26` points to that original commit.

| Lane | Commits ahead of initial base | Recovery state |
|---|---:|---|
| `atlas-int2` | 1 | `eacae2f5` is occupancy-mask work. Seam/default-lean work remains uncommitted in four files; prior green suite predates it. |
| `atlas-iq` | 3 | Seven dirty native files and untracked `atlas-trace/`; compile evidence alone does not establish native or visual readiness. |
| `atlas-vk` | 6 | Dirty natives/test. Latest parity run fails five checks; live timing run hit paging/allocation failures. Do not integrate as ready. |
| `atlas-loadhang` | 1 | Clean `e733082f`: lighting floors and wider recovery hysteresis; reviewed, requires integration checks. |
| `harness-title` | 0 | Uncommitted `owner_play.py`, `game.py`, `HarnessTitle.java` and test, plus natives; recover selected source changes, not DLLs. |

Confirmed review findings to address first: corrupt BLOCKS cache records are ingested as AIR; unified terrain can
disappear when tier-2 fallback stops atlas; `AtlasQuality.scale()` bypasses the controller's smooth output at tier 1.
Work ownership: orchestrator owns workflow/reviewer portability and Java quality/fallback integration;
`codex-cache-recovery` in `E:/NirnCraft-scratch/codex-worktrees/cache-recovery` owns the cache corruption fix and
regression. Parked branches stay parked. Update this section as each step lands; record test receipts and remaining
stage gaps in the renderer review report under `.work/`.

Workflow validation: the four `suite:harness` check-tier items passed on 2026-10-26 (including ten portable
review/queue/watcher regressions). Real Terra image review accepted a visible blue AFTER panel and rejected a false
red claim (`.work/codex/reviewer-smoke/receipt.json`). The broader offline gate passed 8/10 checks; its blockers
were stale packaged Windows/Linux natives (orchestrator owns rebuild) and an existing data lint failure
(merchant tag paths and the unskinned crafter menu). These are recorded, not waived as renderer stage evidence.
