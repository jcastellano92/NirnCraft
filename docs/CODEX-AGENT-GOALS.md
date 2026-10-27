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

## Current follow-up priorities (2026-10-26, after live memory attribution)

The existing three-worker assignment remains; the seam worker now owns the focused cache-identity packet in
cache-identity (base c5c6e117), while its unmeasured shade CSE variant stays parked in seam-parallel. The new packet
addresses arrival-ordered visual-state aliases and emits ingest component hashes. It has not passed its build,
reload/model, deterministic cache or performance gates; the independent reviewer has identified epoch and reload
issues requiring repair. No ingest fingerprint bypass is allowed.

Root's properly gated memory probe observed Java private commit21.33 GiB/working set12.79 GiB and stopped at the
sampled reserve. Closure required force, then PID disappearance and backend restoration were verified; no ready
or visual pass. Total job peak22.04 GiB, no new .NET OOM event. The existing24 GiB cap is unchanged. Root checkpoint e496435b adds backend/retired-buffer/driver-heap gauges. Windows target build, real replacement/
release accounting in10 frames and strict3-context lit/populated-grid parity pass; opt-in query cost is recorded.
These diagnostics do not fix the memory growth; Linux packaging and game attribution remain pending. Separate allocation owners and clean repeated lifecycle remain open.

Cache identities and native generator code match between the two loaded artifacts, but ingest changed from
b711fd8c05f48313 to888e7b3d2f1153eb, changing the generation set. The three retained cache sets are preserved;
actual zero-regeneration revisit remains unproven. CPU footprint counters do not explain process-private memory.

Frame-pacing checkpoint161c3d8e passed focused CPU tests but FAILED the original full cold-revisit tier/hit gate.
Repair04bf3a9b preserves that oracle and passes expanded CPU tests; full native rerun now passes2 harness items, including6/6 cold reads and0/4096 return hit differences.
Main-base candidate is isolated in frame-main-integration at811750c8 (base588a6aba); it has no Vulkan recovery
code and still needs its own build/package/integration/live-route gates. Main has not merged it. A protected local
tag before-frame-pacing-integration-2026-10-26 preserves588a6aba before that integration.
The missing-budget-executable false pass is fixed: checked command returns2 for absent tool and propagates lint
failure. The standalone configured shader manifest has114 entries; target build budgets separately check83+31.

Seam cube pair preserved614981 exact hits. L0/1 matched-group mean DeltaE13.729->0.0327 with80.98/93.56% coverage,
but maximum group4.055 remains; L1/2 mean5.244 fails, higher levels/jitter/lit game unverified. Shade driver binary
regresses2176 bytes. The cube experiment remains isolated. Main source has no Vulkan/performance/seam integration.

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

## Current checkpoint (2026-10-27)

Main renderer source remains `ca3c6c20`; main HEAD `162d96e5` also contains workflow/evidence fixes. The separate
Vulkan candidate `6e599502` combines the reviewed synchronous S3 lifecycle, repaired off-lock section snapshots,
deterministic visual aliases, exact ingest dumps and opt-in allocation diagnostics. Nothing in this checkpoint
establishes route speed, completed Vulkan migration or stage acceptance. S4 asynchronous submission stays parked.

The combined candidate passes the two native harness items, including the original six cold reads with zero of4096
return-hit differences, plus kernel budgets. Fresh Windows/Linux packages load at ABI0.18 with175 exports and source
hash `441f942240832171784e2a3faf4dc834bc5668005e3d05122ba78dacaa806c5e`. Java build passes363 tests with19 opt-in skips.
Driver-resource limits still fail independently of those SPIR-V budget checks.

The properly gated allocator probe `live-20261026-234612-649339` stopped at the existing sampled memory reserve,
before readiness or captures. Java private commit reached21.39GiB and the whole job peaked22.04GiB; closure required
force, and eventual PID disappearance/backend restoration were independently confirmed. This is not a graceful
shutdown result. Late process growth occurred while Minecraft VMA stayed near423MB live/569MB reserved, renderer
allocations near3.66GB and retired-buffer counters zero in the observed samples. JVM committed heap stayed below
2.75GB. These overlapping ownership views do not explain process-private growth and must never be summed into a
CPU attribution. Root and Sol now trace unmanaged CPU/generator/driver ownership before another live attempt.

The probe saved one byte-validated ingest attempt:1099014 words, FNV `b7372337063bdd00`, matching its final logged
generation key `ce0d929d66b6b20e`. This is one run; deterministic aliases still need a matching repeat/reload and
zero-regeneration revisit. Keep all valid cache data and the three development sets. Renderer-only changes must
preserve generation identity; actual generator changes must invalidate dependent content. Per-stage invalidation
remains a separate open requirement, never an excuse to bypass fingerprints.

Seam CSE preserves all614981 measured hits and every14745600 linear RGBA byte of the cube capture. It reduces the
shade binary by128 bytes but remains2048 bytes above the unchanged935168-byte cap. It stays isolated. Terra now
uses a homogeneous stone fixture to distinguish L1/2 palette and face-mean behavior; no brightness tuning or
performance claim is authorized by the failed resource gate. Higher boundaries, jitter and lit-game review remain
open. The independent Terra reviewer continues to maintain the full stage matrix and inspect every new receipt.

## Frame-pacing integration (2026-10-27)

The frame-only main-base candidate `50756e6a` passed its explicit `588a6aba` check-tier gate: seven items, zero
blocking/known failures or missing items. Its own native run preserves all six cold reads and zero of 4096 return-hit
differences. Fresh Windows/Linux packages load at ABI 0.17 with 166 exports and source
`5d91ba49176f0493835649e4ee7cbbe657086ef7b55f09fc9a04cf507462457c`. Java reports 353 tests, zero failures/errors,
19 opt-in skips; jar SHA256 `3dfb4e518ed804539b003aa8d33e75368803fea9356d1a3ee5c9bb3615d33a22`.
This integrates off-lock versioned section snapshots and isolated build/harness paths, with no route-speed claim.
The original 41.23/90.06 ms route remains the last measured baseline; stage 1 and driver-resource limits remain open.

The separate native allocation control at Vulkan checkpoint `9b5cb214` completed 263 generated/durable tiles,
zero failures or queued writes, matching all eight saved fingerprint components. Its four destruction boundaries
were observed with the child alive; post-context private memory was 175353856 bytes and peak job memory 6.01 GiB.
This own-device, empty-overlay, neutral-material control omits Minecraft and the near workload; live growth remains
unexplained. A later byte-identical ingest UUID has unknown provenance and is not a second verified client run.

Current assignments: Terra implements generator/context/overlay lifetime barriers in `generator-lifecycle`; normal
reload must not block rendering, and shutdown must drain every owner before the shared device is destroyed. The
packet is unbuilt/unverified and must not ship. Sol investigates measured feature-pipeline setup latency in
`feature-pipeline-cache`, starting with bounded independent caches for the 19 attached-feature pipelines. It must
prove same-candidate cold/warm output and fingerprint agreement before any benefit or integration claim. Seam work
is preserved and parked while these confirmed ownership and loading issues advance. The independent reviewer
continues to check all packets and the full stage matrix; the parent objective remains active.
