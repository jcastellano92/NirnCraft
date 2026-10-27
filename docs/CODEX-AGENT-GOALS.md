# Renderer agent goals

The owner expanded the team on 2026-10-26: one coordinator plus three assigned workers. This replaces the former
one-child limit. The full docs/ATLAS-PLAN.md objective remains active; completing a component goal does not complete
the renderer. No pushes are authorized.

| Owner | Model | Goal | Measurable gate | Workspace / ownership |
|---|---|---|---|---|
| Coordinator | Current session | Finish direct Vulkan integration and combine accepted work | Fresh prepared ABI0.18 jar; bounded Minecraft VK_SHARED run with delivered frames, independently reviewed captures and subsequent draws; resize/relaunch; matched route measurements before performance claims | vulkan-recovery; Java/Vulkan integration, common harness, docs, packaging and main integration |
| Renderer worker (`sol_review`) | gpt-6.1-sol, high | Reduce the measured feature-pipeline startup delay using bounded reusable driver caches | Same-candidate cold/warm setup, all eight fingerprints and exact generated output agree; actual 19 cache hits, unchanged SPIR-V/resources and safe corruption fallback; no memory-cap increase | feature-pipeline-cache; attached feature pipelines, cache IO helper and focused offline pair oracle. Earlier frame-pacing and allocation-control packets are frozen. |
| Lifecycle worker (`terra_seam`) | gpt-6.1-terra | Drain generator, bake, overlay, readers and startup owners before native/context/device destruction | Exactly-once release, nonblocking normal retirement, real shutdown barrier before shared-device close; blocked-owner/failure regressions, full build and later actual reload/shutdown gates | generator-lifecycle; Java ownership controller and targeted native-binding callers. Seam experiments stay preserved in seam-parallel; all six boundary/jitter targets remain open. |
| Independent gatekeeper (`renderer_gatekeeper`) | gpt-6.1-terra | Verify evidence against the complete stage0-5 requirements and catch regressions | Maintain requirement/evidence/gap matrix; independent image/diff verdicts; reject insufficient coverage, stale artifacts or narrow checks used as broad acceptance | Read-only tracked source; own reports under E:/NirnCraft-scratch/codex-gatekeeper-evidence |

## Early checkpoint evidence and gaps (before parallel repairs)

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

## Historical follow-up priorities (2026-10-26, after live memory attribution)

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

## Checkpoint before frame-pacing integration (2026-10-27)

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

The lifecycle packet `cb001a1d` passes 385 Java tests (zero failures/errors, 19 opt-in skips) and its explicit-base
three-item offline gate. The isolated Vulkan candidate now includes a compiled unified no-output fallback: retained
opaque meshes use the existing vanilla-light resolve when no far target was delivered. Its fresh build also passes
385 tests; actual terrain/transition images and pending-copy behavior remain unverified. It is not on main.
The combined offline gate has 16 passes and one independently verified existing Crafter-menu coverage failure,
zero new failures or missing items. Do not invent a vanilla UI exemption to hide this existing debt.
Main has the tested frame-pacing fix at `b2ea31dd`; recovered shared Vulkan remains isolated.

Sol's feature-pipeline experiment completed a fresh-miss/warm-hit/fresh-miss sequence with the same compiled
candidate: feature setup 48577.626 / 135.131 / 46997.300 ms. All three runs persisted 263 tiles, had zero failed or
queued writes, observed four live cleanup boundaries, and preserved all eight candidate fingerprint components.
All 78158 encoded records agree with the original baseline. Nineteen fixed cache slots total 4380540 bytes.
This supports an attached-feature setup benefit, not a world-generation, frame-time or live-startup result.
The first pair's misleading `fingerprintMatchesSavedRun` label is preserved in raw receipts and corrected in a
separate hash-linked summary: candidate consistency is true; equality to the old saved build/whole is false.
The packet is frozen at `772db4b1`. Full native/kernel checks report two passes with no native failures/skips;
the standard cache-IO hook ran. Actual recovery from one corrupted copied tree slot rebuilt that slot, reused the
other 18, saved all 19 and preserved the same durable/output/cleanup proofs. Original slots were unchanged.
Packaged Linux, near-feature execution and live startup remain pending; this component is not on main.

Sol next investigates the generator-cache retention race in a separate `cache-lifetime` worktree: delayed detached
deletion can target a set reopened by a live context. Protect active sets and keep retention bounded; never bypass
producer identities. The test instance currently holds 155580920 bytes of cache, not gigabytes of process RAM.
Seam work remains preserved and parked. The independent Terra reviewer checks all packets and the full stage
matrix; the parent objective and all failed renderer targets remain active.

## Same-candidate transport allocation controls (2026-10-27)

The first host-copy control used the lifecycle/fallback jar SHA256
`4d687f0d247a66ab9439902558c2e87dd1f7555922207bcc16f8764c85bc4035` and native source441f9422.
Actual log/core information confirms Minecraft Vulkan `VK_HOST` with a separate native device. Its unique scratch
generator cache preserves every existing world cache. It stopped without readiness or images at172 seconds,
near403/441; maximum Java private13339254784 bytes, maximum job13623369728 bytes. Client closure needed no force.
This is not a renderer/memory pass, and the prior S3 run is not a matched workload/source comparison.

Exact ingest bytes match the earlier selected actual dump (`b7372337063bdd00`), while the cleanup edit to Java
OverlayLive legitimately changes the current conservative producer identity: extra `de033c0000000003`, whole
`2c9b977e73653acb`. It is not a same-key revisit. The host allocation report remains UNVERIFIED because one
profile sample exceeds the unchanged3-second Windows alignment tolerance; unmatched data stay excluded.

The host run's strict cleanup caught Prism's lossy INI rewrite of backslashes in the diagnostic JVM path. The
client and backend were closed/restored, but flag restoration refused the changed marker and retained the lease.
A separate exact-hash/owner-guarded recovery restored the original marker and authoritative properties, verified
no client and released the lease; raw failure receipts are unchanged. The normal path now uses forward slashes
(`45811574`), passes31 CPU tests including the real group writer, and preserves strict conflict refusal. Actual
Prism round-trip behavior still needs live verification. The same-engine-candidate shared arm below used the
original240-second/24GiB policy and85% sampled reserve; no cap or budget was raised.

The shared arm `live-20261027-020352-028632` reached terrain readiness, then triggered the reserve and required
forced process cleanup. Maximum named Java private was23398342656 bytes; job peak24127135744 bytes (22.47GiB).
The scoped report `memory-report-20261027-021608-fde5da1a` is OBSERVED, not a memory/renderer pass. Its ingest bytes
and all final fingerprint parts match the new host arm. Thus the current identity was stable across two actual
starts, but each used a deliberately fresh cache: no cross-launch reuse or zero-regeneration claim follows.

The shared supervisor claimed successful flag restoration, but independent post-job inspection found the probe
marker back in instance.cfg. Properties/backend were original and all processes were absent. The writer is not
proved. A second guarded recovery after launcher exit restored/rechecked the exact original marker and retained
the raw premature-success receipt. Further live runs are parked until launcher ownership/exit is verified before
final restoration. JVM native-memory/thread diagnostics pass34 CPU tests plus an actual owned tiny-JVM jcmd
smoke; actual Prism inheritance and stalled Minecraft diagnostics remain unverified.

Sol's cache lease packet passes11 actual Windows IO/process scenarios and the existing torn/corrupt cache suite.
Its first Linux attempt stopped before compilation because system g++ was missing; an existing project compiler
was located, with a nonroot ext4 rerun pending. No production lease integration is accepted yet. The pipeline-only
main-base candidate has a fresh217-second Windows prebuild; all83 production SPIR-V files are byte-identical to
accepted main. At that checkpoint native correctness, Linux packages, Java and the main-base gate still preceded merge.

## Cache maintenance accepted on main (2026-10-27)

Main `d6913ad2` merges the independent accepted-renderer cache candidate. Protected local tag
`before-cache-integration-2026-10-27` preserves preceding main `68ce5eef`; exact negative fetch protection also
preserves the original and frame-pacing recovery tags. No push occurred. Post-merge comparison differs from the
tested candidate only in this goals document: every engine/build/test source and packaged byte agrees.

The combined main-base source passes native/kernel2 checks, all83 shader bytes unchanged, both standard Windows
cache suites,11 ownership scenarios,6/6 cold reads and0/4096 return-hit differences. Fresh Linux package uses at
most GLIBC2.27 and static C++ runtimes; both platforms load at ABI0.17/166 from source076af4d670e6. Registered Linux
cache CTest3/3 and same-helper root/nonroot22 ownership scenarios pass. Java353/0 failures/errors/19 opt-in skips;
jar `ee62663f41f23cf4b557c11036fbb6b086784551ff9e0035996df45495153e7d`. Explicit main-base gate6/6 passes, no
blocking/known failures or missing items. Current native generator hash `cac47eaf36f7a839` intentionally starts a
new version once; no identity bypass. Legacy/manual deletion and concurrent same-key writers remain excluded;
small lease files remain and arbitrary filesystem cleanup latency is not bounded by this evidence.

Main retains the accepted GL-host/native-Vulkan architecture. Shared-device Java lifecycle and deterministic
alias/diagnostic packets remain isolated. No new route, zero-regeneration relaunch, seam, driver-budget, full
startup or renderer stage pass is claimed. The parent objective stays active.

The shorter shared diagnostic `live-20261027-024309-012661` captured the render thread inside Minecraft's
`vkDestroyDevice` at shutdown; one snapshot does not prove its cause. NMT was explicitly disabled in all three
actual jcmd responses, so the environment request yielded no allocation attribution. Owned launcher retirement
before final restoration now passed actual post-job independent checks twice: original marker/properties/backend,
no lease and no matching Java/Prism process. A fixed raw NMT option inside the reversible owned JVM marker passes
45 CPU tests and its offline gate with the existing Crafter UI debt; actual enabled Minecraft NMT remains pending.
Sol now prepares a populated-generator shutdown witness for a concrete near/far slab ownership gap, without
claiming it explains the live memory growth. All previous failed receipts are preserved.

## Actual allocation and populated-shutdown evidence (2026-10-27)

The raw JVM-marker diagnostic `live-20261027-033859-394369` enabled NMT in all three actual Minecraft responses.
HotSpot-tracked commitments were2388503/3400710/3418486KiB; named Windows-private samples reached18492743680
bytes and the job peak22784147456 bytes. These sequential, differently scoped observations do not identify a
native/driver owner or support residual subtraction. The run required forced cleanup and produced no readiness
or visual acceptance. Independent post-job inspection twice confirmed original JVM marker/properties/backend,
no lease and no matching client/owned launcher. The full allocation report remains UNVERIFIED because one current
profile sample misses the unchanged timestamp-alignment gate; the separate NMT sidecar pins its three raw files.

The populated borrowed-device diagnostic `gen-shutdown-borrowed-both-cached-20261027-0358` completed in30.734s,
child exit1. Its explicit copied pipeline-cache pair matched the actual physical device; original cache files,
saved inputs and source441f9422/core97a7f388 stayed unchanged. It admitted7 non-air near sections and5632 far
cells (413 mixed), completed the real second copy, then stopped producing frames. Generator destruction emitted
the retained-buffer warning; after context/token retirement Vulkan reported two leaked buffers and their two
memory allocations at external device destruction (VUID05137). Four null-buffer descriptor diagnostics occurred
earlier and remain a separate issue. The wrapper conservatively labels the receipt UNVERIFIED because the required
zero-error marker is absent; the captured validation failures are positive evidence, not a clean shutdown.

This reproduces a native child-ownership defect, not the cause of live21GiB growth or Minecraft's device-close
delay. Sol owns a narrow producer-drain/consumer-retirement ownership repair and separate descriptor diagnosis;
Terra independently reviews. No production repair or new stage pass has been accepted. A second focused Terra
inspection evaluates porting deterministic near-state aliases to main without shared-Vulkan dependencies, so
equivalent traversal orders cannot unnecessarily discard valid cached world data. Heavy jobs remain serialized.

## ABI 0.17 deterministic ingest candidate (2026-10-27)

The isolated `codex-main-ingest-stability` candidate selects deterministic visual aliases and retains generator, far, bake, overlay, startup and context-reader owners until their release drains. Its GL interop detach is pumped on the render thread; native legacy detach is void, so internal detach failures are not observable here. Existing complete fingerprint components remain enabled; the overlay lifecycle change changes generator identity and can legitimately invalidate a cache once.

Full Java build passed 372 tests, zero failures/errors, 19 opt-in skips (log `1027-074100-ingest__Java_corrected_full_build.log`); its 19 focused tests all passed. Jar SHA-256 `4d5b12bd0d4865a024348c356a8505739727e6fa19ef180623b60ec7e32f2e7e`; packaged bake inputs match `40d6c4f22688`. Explicit-base `e41568bf` check gate passed all 3 checks with zero new, known or missing results (log `1027-074447-ingest__explicit_base_check_gate.log`). Actual GL startup, reload, retirement and two-launch cache revisit remain unverified; this checkpoint is not a production acceptance or merge.
