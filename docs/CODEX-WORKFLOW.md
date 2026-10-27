# Continuing ATLAS with Codex

The owner requested continued renderer development on 2026-10-26, preserving the Claude workflow that worked:
one orchestrator, focused worktree workers, independent reviewers, a shared machine queue, and measured gates.
The renderer's goals and order remain [ATLAS-PLAN.md](ATLAS-PLAN.md), not a new roadmap.

## Instructions and roles

Codex enters through [AGENTS.md](../AGENTS.md). Project roles live in `.codex/agents/`: `stage_worker`,
`renderer_reviewer`, and `visual_reviewer`. The owner expanded the project on 2026-10-26 to three assigned workers alongside the orchestrator.
The current goals, ownership and gates are in [CODEX-AGENT-GOALS.md](CODEX-AGENT-GOALS.md).
The owner chose `gpt-6.1-terra` for gatekeeping and focused work, and `gpt-6.1-sol` with high effort for deeper
renderer review. These choices are explicit in the role files; no Claude aliases are translated into model names.
If a client cannot select a custom role, the orchestrator passes the corresponding instructions in the task.

The role/config format and instruction discovery follow the official OpenAI documentation for
[subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents) and
[AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md).
The installed CLI's `codex exec --help` is checked before changing its invocation.
The installed CLI also accepted the project setup under `codex exec --strict-config` and returned `CONFIG_OK`
in a real read-only Terra session (`.work/codex/config-check.txt`).

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
The local tag is protected by an exact negative `remote.origin.fetch` refspec. VS Code's installed Git Tag Release
Tracker explicitly fetches with `--prune-tags`, which otherwise deletes tags absent from the remote. A disposable
Git fixture reproduced deletion and protection; the protected tag then survived a real automatic fetch. A separate
local `recovery/before-codex-atlas-render-2026-10-26` branch also retains the baseline. Neither reference was pushed.

| Lane | Commits ahead of initial base | Recovery state |
|---|---:|---|
| `atlas-int2` | 1 | `eacae2f5` is occupancy-mask work. Seam/default-lean work remains uncommitted in four files; prior green suite predates it. |
| `atlas-iq` | 3 | Seven dirty native files and untracked `atlas-trace/`; compile evidence alone does not establish native or visual readiness. |
| `atlas-vk` | 6 | Dirty natives/test. Latest parity run fails five checks; live timing run hit paging/allocation failures. Do not integrate as ready. |
| `atlas-loadhang` | 1 | `e733082f` integrated with quality consumer fixes: Java build green (353 tests, 19 opt-in skips), 3 new regressions pass. Live gate still pending. |
| `harness-title` | 0 | Uncommitted `owner_play.py`, `game.py`, `HarnessTitle.java` and test, plus natives; recover selected source changes, not DLLs. |

Confirmed review findings to address first: corrupt BLOCKS cache records are ingested as AIR; unified terrain can
disappear when tier-2 fallback stops atlas; `AtlasQuality.scale()` bypasses the controller's smooth output at tier 1.
Work ownership: orchestrator owns workflow/reviewer portability and Java quality/fallback integration;
`codex-cache-recovery` in `E:/NirnCraft-scratch/codex-worktrees/cache-recovery` supplied the cache corruption fix and
regression; integrated native suite and kernel-budget checks are green. The orchestrator corrected the ingest
header-batching compile error before accepting the suite. `codex-seam-recovery` in
`E:/NirnCraft-scratch/codex-worktrees/seam` owns recovered seam source and controlled evidence; it remains unverified.
Parked branches stay parked. Update this section as each step lands; record test receipts and remaining
stage gaps in the renderer review report under `.work/`.

Workflow validation: the four `suite:harness` check-tier items passed on 2026-10-26 (including ten portable
review/queue/watcher regressions). Real Terra image review accepted a visible blue AFTER panel and rejected a false
red claim (`.work/codex/reviewer-smoke/receipt.json`). The broader offline gate passed 8/10 checks; its blockers
were stale packaged Windows/Linux natives (orchestrator owns rebuild) and an existing data lint failure
(merchant tag paths and the unskinned crafter menu). These are recorded, not waived as renderer stage evidence.

Follow-up tool checks: worktrees outside the old `.claude/worktrees` naming convention now get isolated Java
outputs and locks instead of sharing main's Gradle output. The focused `check-build-wrapper` runs five affected
build/output/lock regressions, all passing. The broader pre-existing process-guard module timed out in live-action
coverage during exploration; it is not claimed green. The merchant lint now reads the platform's singular
`tags/item` path; fourteen false failures are resolved and the genuine crafter menu coverage failure remains.

Native portability follow-up: both packaged platforms now pass `check-natives` at ABI 0.17 with 166 exports and
matching source hashes. The rebuilt Linux library also loads in WSL. Linux build repairs add loader/thread
libraries to the offline tools and replace Windows-only environment calls in `gen_bake_tool`; its numeric compiler
job limit is passed explicitly by the shell script. The Windows build also passes with these portable changes.

Check execution now writes complete output directly to each test log and streams TRACE progress while running.
The runner regression verifies logs over 20 KB, fragmented TRACE lines, exit-time output, partial timeout logs,
owned grandchild termination and survival of an unrelated process. All five check-tier harness items pass.
These are tooling/packaging results; the seam branch still needs driver-resource, paired-hit/albedo and jitter proof.

The integrated check-tier gate against immutable base `a938b173` ran exactly 14 items: 13 passed and the existing
crafter-screen coverage failure remained known, with no missing or newly failing items. Receipt:
`.work/harness/gate/latest.json` (2026-10-26 18:16). Git command errors now fail the gate instead of looking like
zero changes. Gate execution pins the planned IDs so additive tier filters cannot expand the run. Both cases have
runner regressions. An earlier expanded run was stopped; its extra audits are not claimed as passing evidence.

Live continuation evidence: the loading watchdog reached readiness with 441/441 spawn chunks and 263/263 coarse
tiles after 66.97 seconds in-world on a partially populated cache. This is startup completion, not the <2-second
revisit gate. The stationary probe now waits for fresh `ready{ok}` and active metrics before its measurement window,
keeps shutdown outside that window, and reports later asynchronous staging separately from missing renderer data.
The first 180-second post-ready trace has 91 active samples, one transient grid-pending sample. Independent Sol
review and explicit replay preserve the unchanged flatness failure: post-settle median 9.11 ms, range 5.22–33.69 ms,
zero tier changes, reach 168–201. The original stricter unverified receipt is retained alongside the reanalysis in
`E:/NirnCraft-scratch/codex-main-evidence`. It is not a route/frame-budget acceptance result.

Seam lane: a controlled general-path pair preserves all hit geometry fields and improves sampled L0/1 matched
linear-albedo delta-E 4.660→0.172, but lower-side matching covers only 26.65%; higher levels remain unobserved.
The original feature adds 16 bytes/thread local memory and 16,640 bytes driver binary versus its reference. A
smaller helper interface preserved exact colors but increased those costs further and was rejected. Both variants
remain isolated. The next paired experiment ports face means into the lean stage, whose smooth blend still used
palette colors; no shader feature from these experiments is integrated into main.


## Owner-approved parallel expansion (2026-10-26)

The owner requested additional concurrent agents. The project cap is now three child threads plus the coordinator;
this supersedes the previous one-child policy. See [CODEX-AGENT-GOALS.md](CODEX-AGENT-GOALS.md) for assigned goals,
measurable gates and isolated ownership. One-heavy-job scheduling remains enforced by the coordinator. The config
counts child threads excluding the primary, as documented in the official [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).


## Live job classification, memory and cache accounting (2026-10-26)

Passing loop/why alone did not classify labels containing only `client` as live. This omitted live gating and
left the16 GiB build cap in force, producing the21:55 PowerShell OOM near world readiness. agent_job now adds
an explicit game label to declared middle/outer jobs and rejects an undeclared Vulkan probe. Live jobs retain
the existing24 GiB scheduler allowance; it is diagnostic headroom, not a renderer memory target.

A py/launcher inner Windows job hid the actual cap from QueryInformationJobObject(NULL). The shared scheduler's
existing cap job now has a unique random name and exports NIRNCRAFT_HEAVY_JOB_NAME only after successful create/
set/assign. The read-only sampler opens that exact job with QUERY access and closes every query/process handle.
The sampler rejects discovered client PIDs outside the named capped group. No second scheduler/job or higher cap was added. The pre-patch scheduler is preserved in the gatekeeper evidence
folder as heavy-before-named-job-20261026-222227.py; the current machine helper lives in the existing locks folder.
A real CPU test through nested py verifies16 GiB cap, complete membership including the child and25 sampling
rounds without handle growth. Machines without this handoff fail the probe preflight; do not relax the guard.

Memory evidence distinguishes private committed CPU bytes, resident working set, Java heap and Vulkan device
allocations. Sample every5 seconds, before launch and after cleanup; reject unavailable accounting and begin
cleanup at a sampled85% cap threshold. This cannot guarantee catching allocation bursts before the Windows cap.
Cache metadata records retained sets, bytes and packaged identities before/after, never deleting valid data to
make a benchmark pass. The autotest harness keeps three recent generation versions. Renderer-only native edits
exclude themselves from ATLAS_GEN_HASH; per-stage invalidation and actual warm-reload acceptance remain pending.

The 23:46 allocator probe on October 26 reached the sampled reserve again. It did not reach readiness or create
comparison images. The exact client eventually exited after forced cleanup and the backend option was restored.
The scoped, timestamp-aligned report is
`E:/NirnCraft-scratch/codex-vulkan-evidence/live-20261026-234612-649339/memory-report-20261027-000426-201efcb1/summary.json`.
It separates private commit, heap, Minecraft VMA, raw renderer resources and overlapping driver heap estimates.
Stable late VMA/renderer observations do not attribute the unexplained private-memory growth. The report excludes
historical time-of-day-only cache keys from current-run fingerprint observations; its 14 CPU regressions pass.
An earlier combined reporting shell lost its exit-code check; the direct checked rerun above succeeded independently.

The completed generator control and main frame-pacing merge are recorded in
[CODEX-AGENT-GOALS.md](CODEX-AGENT-GOALS.md). The native control did not reproduce the live memory growth;
Minecraft, shared transport, near generation and real overlay payloads remain outside its scope. Current workers
prepare temporary probe-flag restoration, feature pipeline caching and active-cache deletion protection. The
coordinator prepares the lifecycle candidate for a bounded host-copy allocation control before another shared run.
Source edits and independent review continue in parallel, with one heavy job.

Cache identities remain conservative: renderer shaders are excluded from the native generator hash, but the
entire feature engine and Java overlay producer files still participate. Infrastructure-only edits in those files
can therefore invalidate a bake. Preserve that safe invalidation until a finer dependency scheme is proved; identical
sampled output is evidence, not permission to remove an entire producer file from its identity. Development retention
keeps three versions, while actual same-version relaunch and zero-regeneration revisit still require live evidence.

## Accepted-base cache maintenance checkpoint (2026-10-27)

The source and packages at `c298d1c4` combine bounded attached-feature pipeline reuse and active generator-cache
protection on the accepted GL-host/Vulkan-core source. Fresh Windows/Linux ABI0.17/166 packages share source
`076af4d670e6973fd055245e9f3a8b361c437126e87d7c54c03179d90573f0e9`. All83 shader binaries match the accepted
baseline. Windows native/kernel checks pass, including both cache suites and the original cold-return oracle;
Linux registered cache CTest3/3 and Java353 tests pass (0 failures/errors,19 opt-in skips). The explicit b41724ca
check-tier gate passes6/6 with no known/blocking failures or missing items (`1027-032247`,91s).

The pruner skips active or uncertain sets and retains small lock files; it does not protect against legacy or manual
deletion or serialize same-key writers. Joining cleanup does not bound arbitrary filesystem latency. Current
whole-producer hashing intentionally invalidates the previous generator set once; the new native generator hash
is `cac47eaf36f7a839`. This checkpoint does not integrate the shared-Vulkan Java lifecycle/diagnostic packets or
establish live cache revisit, full startup, frame-time, driver-resource or renderer stage acceptance.
