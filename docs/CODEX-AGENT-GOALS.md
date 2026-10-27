# Renderer agent goals

The owner expanded the team on 2026-10-26: one coordinator plus three assigned workers. This replaces the former
one-child limit. The full docs/ATLAS-PLAN.md objective remains active; completing a component goal does not complete
the renderer. No pushes are authorized.

| Owner | Model | Goal | Measurable gate | Workspace / ownership |
|---|---|---|---|---|
| Coordinator | Current session | Finish direct Vulkan integration and combine accepted work | Fresh prepared ABI0.18 jar; bounded Minecraft VK_SHARED run with delivered frames, independently reviewed captures and subsequent draws; resize/relaunch; matched route measurements before performance claims | vulkan-recovery; Java/Vulkan integration, common harness, docs, packaging and main integration |
| Frame pacing worker (`sol_review`) | gpt-6.1-sol, high | Remove the terrain-lock convoy behind the measured 68.7 ms render stall | Cold/background world/store reads cannot block render-facing setView/frame/staging; edit/reset/unload/stale-read regressions; native/output agreement and independent review; live route p99 target20ms/worst50ms remains the final performance gate | frame-pacing; voxgrid, world and necessary sectionstore snapshots/tickets, focused native regressions |
| Seam worker (`terra_seam`) | gpt-6.1-terra | Make detail continuous across LOD boundaries | Cube candidate/reference paired geometry agreement and adequate boundary coverage; target DeltaE<2 at every observed boundary and stationary jitter flips<1%; all six boundaries eventually covered, no cap increases or hidden missing views | seam-parallel; shade-pass cube albedo and focused offline rig/analysis only |
| Independent gatekeeper (`renderer_gatekeeper`) | gpt-6.1-terra | Verify evidence against the complete stage0-5 requirements and catch regressions | Maintain requirement/evidence/gap matrix; independent image/diff verdicts; reject insufficient coverage, stale artifacts or narrow checks used as broad acceptance | Read-only tracked source; own reports under E:/NirnCraft-scratch/codex-gatekeeper-evidence |

## Current evidence and gaps

Main `atlas-render` remains at `ca3c6c20`. Shared-device Vulkan recovery checkpoint `9366b010` is separate, with
reviewed startup/status fixes awaiting the next fresh-jar probe. Native, parity, cancellation, delayed retirement,
populated-grid pillar, package ABI/source and Java checks pass. First live run reached Minecraft Vulkan plus
VK_SHARED and 30 completed copies, but readiness was pending and no captures were made. Its structured ABI false
was a parser bug: the raw log explicitly records ABI0.18. This is not a completed migration or a speed result.

Measured main route remains p99 41.2348 ms / worst90.0575 ms at1440p RTX3090, above20/50 targets. The 68.7 ms
render-thread stall overlaps a66.3 ms background world/store hold. GPU generation queue delays are another known
cost. Historical driver-resource limits fail on the identical main shader artifacts; the recovery artifact needs its
own exact-artifact audit. SPIR-V budget passes do not establish driver-budget acceptance.

Seam references preserve inputs and geometry. The north view has useful LOD0/1 and1/2 coverage; the cube candidate
still needs its capture. The former DeltaE0.172 result covered only26.65% of one side and is not an acceptance result.
Higher boundaries, temporal jitter, visible pixel holes, sub2-second zero-regeneration revisits and full content/
lighting/inventory stages remain unproven. See the gatekeeper matrix for the stage-by-stage inventory.

## World-generation latency investigation

The owner requested a parallel investigation of generation/loading latency. While waiting for its seam GPU window,
the seam worker performs a bounded source/evidence-only audit and prepares separate implementation packets. It
separates generator execution time, submission queue delay, CPU storage/lock waits and cache readiness. It also
locates the existing instancing S0 ceiling experiments before proposing implementation. No generator source is
changed in this audit; World/SectionStore/VoxGrid remain the frame-pacing worker's ownership.

Priorities are the measured lock convoy, bounded generation submissions (target <=4 ms), staging <=2 ms and a
cached revisit under2 seconds with zero regenerated visited chunks. Existing instancing speedups are estimates
pending their ceiling tests. Vulkan correctness remains the integration priority; generation algorithm/instance
changes need separate parity, fingerprint/rebake and world-consistency gates. The runtime has four total slots,
so the audit uses waiting time rather than launching an additional worker or displacing independent review.

## Scheduling and reporting

Source editing, static analysis and independent review run concurrently in separate owned worktrees. Only ONE
heavy build, test or GPU job runs at a time. Workers send exact ready commands/resource estimates; the coordinator
assigns queue windows. GPU-only exclusivity permits CPU work in the shared scheduler, so it is not a substitute for
this coordination. Every heavy job uses agent_job.py, checks free RAM, records complete output and stays within the
five-minute GPU limit. Only the coordinator launches the disposable client through the harness and integrates.

Each worker records its assigned objective, current evidence, pending command and blocker in its own .work/codex
report and sends results as they become reviewable. Waiting for a GPU slot is explicit, not a passing result. The
coordinator advances the live integration lane while other lanes prepare code and oracles, then rotates queue windows.
The active parent goal and all stage targets remain unchanged.
