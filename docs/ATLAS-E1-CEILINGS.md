# E1 ceilings: closing the far pass to 6-8 ms (planner, 2026-10-26)

A read-only Opus planner's report on the far pass at 2560×1440: today 43.6 / 58.0 ms (valley / horizon), target 6–8 ms.
**M** means measured from the logs or read from the code. **E** means estimated. Ablations 1–3 at the end decide the
next lever.

## The main finding: the cost is in how the kernel is built, not in the number of steps

- **M** — Steps per pixel today (`E:/NirnCraft-scratch/atlas-int2/e2/v45e_pw3_a1.log`): valley 12.7, horizon 13.5,
  slope 7.0. With amortisation off it is about 25–33. The slowest pixel in a warp averages only about 15% above the
  mean, so divergence is not the cost. The sky is 33% of valley pixels and 43% of horizon pixels.
- **E** — The valley trace is about 32 ms (43.6 minus the measured ~11 ms of shade). Over 82 SMs with 12 warps each,
  that is about 450k cycles per warp, or **about 30k cycles per walk step**. A step that does 3 dependent loads hitting
  L2 should cost 1–2k cycles, so the walk runs 15–30× slower than a memory-latency-bound one.
- **M** — Time follows code size and L1 size, not work:
  - the trace-only kernel kept the same 168 registers and 16640 B of shared memory but had a 10.15 MB binary, and ran
    at 616–746 ms (about 10× slower);
  - binary size 1.40 → 1.94 MB at 16640 B cost +6% (e40 → e41);
  - the shared-memory tip from 7936 to 16640 B costs 25–40%;
  - local memory is 3344 B per thread (the low bits of the LOCAL field).
- **E (inference)** — Instruction fetch and local-memory traffic dominate. An ideal lean walk over the same data
  (about 92M steps per frame) would take roughly 1.5–3 ms.

## Waste in the walk that the kernel review missed

- **Per-step chunk lookup.** `vgCellG` does two signed `%` operations on every step, and the chunk word is reloaded
  every step even when the ray stays in the same chunk (`traceVoxGrid`, atlas_far.slang:905). The fix is to cache
  `c`/`cw` and wrap with a compare-and-subtract.
- **Long live ranges.** `TraceIn`/`TraceOut` plus `TraceResult fine` (traceViewBody:1703) keep about 25 registers live
  through the segment loop.
- **Grazing second look.** It re-walks ±3 coarse voxels on about 10% of pixels (`grazingShare`).
- **Smooth-hit loads.** `coarseSmoothHit` does up to 120 + 32 `vgMatG` loads, each with two `%` operations.
- **Constant tables are still local arrays:** `G_FACE_*` and `G_ERR` (atlas_groups.slang:29–31), `MIP_AT`
  (atlas_far.slang:658) and `WAVE_SPREAD` (atlas_far.slang:2284).

## Ceiling table, ranked by win per effort

| # | Lever | Where | Win, valley / horizon | Cheapest test | Risk |
|---|---|---|---|---|---|
| 1 | Deeper amortisation, N = 8 | `FAR_AMORT_N` (atlas_far.slang:3708) | **M**: turning it on saved 21 / 28 ms, so the eligible tiles cost about 28 / 37 ms to trace. **E**: N = 8 saves another eighth, −3.5 / −4.6 | Change one constant, run with amortisation on; check that shared memory did not tip | Far hits up to 8 frames old; light stays per-frame. Fits the rules |
| 2 | Sky exit plus per-column max height (H, as a skip structure) | traceViewBody:1708, using a per-level top built at voxgrid ingest | **M** ceiling: −4.9 / −8.1 | Already done (sky exit at y 360) | None if conservative |
| 3 | Level windows sized from the footprint | `lodWindowR`, host radii | **M** ceiling: −5 to −15 | Already done | VRAM |
| 4 | Start t per warp or tile (coarse first-hit pre-pass) | A pre-pass on 8×4 tiles, before the `traceView` loop (atlas_far.slang:3914) | **E**: −4 to −8 / −6 to −10. About 40% of steps are spent approaching through the levels, and all-sky warps would exit at once | Variant: `ta = max(ta, 0.9*WaveActiveMin(prevSurface[pix]))` on the static views | The cone must cover the smooth back-search (one voxel) and the parabola sag. Deterministic if conservative |
| 5 | Walk micro-fixes (above) | `traceVoxGrid`, `traceViewBody` | **E**: −5 to −15% of the trace | One variant | Can tip shared memory: always read the pipeline stats |
| 6 | **A lean trace stage written from scratch**, plus shade stages on work lists | new `stage_trace.slang`, following the blueprint | **E**: trace ×3–5 faster, so 43.6 → about 10–14 including today's shade | Ablations 1 and 2 below decide it | Days of work. Deriving it by deleting from farMain is what produced the 10 MB build |
| 7 | Occupancy 25% → 50% | Comes with #6 | **E**: ×0.6–0.75 if latency-bound; less if instruction-fetch-bound | (The kernel agent is on this) | — |
| 8 | Internal resolution plus temporal upscaling (TAAU) | Host dimensions, `nearUpsample` / `nearTemporal` | **E**: roughly in proportion to pixel count, a bit better because coarser footprints mean fewer steps. 0.75 per axis → about 26 / 35; 0.5 per axis → about 12 / 17 | Zero-code resolution sweep | Softness. It is the §0.6 controller's knob. Must dispatch fewer threads: farquad masked lanes and got only 11% |
| 9 | Hardware ray query as a conservative skip | Per chord in traceViewBody, over a BLAS of per-chunk AABBs; the grid walk confirms the hit | **E**: −30 to −50% of the trace in today's kernel, little on a lean one | A prototype of a day or more | Not on every GPU (the fallback gives the same image, slower); BLAS memory and rebuilds; straight rays need per-chord queries; adds registers. **Parked.** |

## The three ablations to run first

1. **Clock attribution.** Make a `mksm.py` variant `clk`:
   - put `uint64_t c0 = clockARB();` just before the `traceView` pass loop (atlas_far.slang:3914);
   - write `counters[pix] = uint((clockARB()-c0)>>6)` at the `counters` writes (atlas_far.slang:4015, 4062 and the sky
     path);
   - make a second variant with `c0` at farMain entry.

   Run with amortisation on and `ATLAS_FEAT_CLOCK=1 ATLAS_STEP_STATS=1`. The existing printer in api.cpp:1029 already
   gives mean, p99, the warp maximum and per-eighth rows. Compare against the steps line from a normal run.
   - If a step costs more than about 5k cycles, the kernel's build is the problem, and #6 is the lever.
   - If it costs about 1–2k, the work itself is the problem, and #2, #4 and #9 are the levers.
2. **Code-size isolation.** Make a variant `strip` that sets `sh = 0u` and `br0 = 0u` at compile time in
   `traceVoxGrid` (atlas_far.slang:962, 970). Bricks, shapes and cut-out texels then drop out of the code. Run it against
   the built-in kernel with `ATLAS_ABLATE=805306368`, which does the same work at runtime (bricks aside, which are rare
   in this scene). If `strip` is at least 20% faster at equal shared memory, #6 pays off.
3. **Resolution sweep and start-t ceiling.** Run the built-in kernel at `w=1920 h=1080` and at `w=1280 h=720` (no code
   change), and the `WaveActiveMin` start-t variant at 2560×1440.

## Verdict

The architecture can plausibly reach about 6–8 ms. A grid walk of about 25 steps per pixel has an arithmetic floor of
roughly 2–3 ms at 1440p. The 7–10× gap matches how the current kernel is built (a 2 MB binary, 3.3 KB of local memory
per thread, sensitivity to L1), not the grid design. That rests on the inference above until ablation 1 confirms it.

The combination that gets there:
- a lean trace stage (#6) with shading moved to work lists, about 10–14 ms;
- plus N = 8 amortisation, the column skip and footprint-sized windows (#1–3), together about −8 to −12 ms but
  overlapping;
- that lands at about 6–9 ms, with the continuous resolution knob (#8) as the controller's margin rather than the plan.

If ablation 1 shows each step is honestly about 1–2k cycles (so the work really is the cost), the honest alternative
within the north star is #8 as the default: render at about 0.6 per axis with temporal upscaling, plus #2 and #4. That
reaches about 8–10 ms on this GPU and stays one renderer, footprint-driven, with the same hits on every machine. Hardware
ray query (#9) would come only after the lean kernel exists.

## Critical files

- native/shaders/atlas_far.slang (`traceVoxGrid` 860, `traceViewBody` 1681, `farMain` 3769, `FAR_AMORT_N` 3708)
- native/shaders/atlas_groups.slang (constant tables 29–31)
- native/src/api.cpp (step-stats printer, 1029)
- native/src/vk_backend.cpp (shader clock enable at 1669, `ATLAS_FAR_AMORT` at 2953)
- E:/NirnCraft-scratch/atlas-int2/mksm.py (variant generator for the ablations)
