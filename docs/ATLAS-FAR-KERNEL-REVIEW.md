# Far kernel review: what taxes every pixel, and the fix order (2026-10-25)

A read-only Opus review of `native/shaders/atlas_far.slang` (split farMain) at atlas-int2. It read the built SPIR-V and
the driver's pipeline stats from today's logs. Line numbers are approximate as of that review.

**State:**
- **Code:** farMain is one 144,891-instruction SPIR-V function (2.79 MB driver binary) at 168 registers: 12 warps/SM,
  25% occupancy.
- **Local memory:** 3392 B/thread. The local-memory field carries a flag at bit 36, so read only the low 32 bits.
- **Shared memory:** appears only in kernels with real calls (cloud 6912 B, far and near 12544 B); the voxgrid kernels
  have none.
- **L1:** at 6 groups/SM, 7936 B of shared leaves ~64 KB of L1, and 16640 B leaves ~28 KB. Shared memory at 0 would give
  ~128 KB.

## Patterns that cost every pixel, ranked

1. **Duplicated walk bodies.**
   - The SPIR-V holds 24× `traceRegion`, 12× `traceGroups` and 8× `traceVoxGrid`.
   - Their source: `traceLod` is reached 4 times (traceView ×2, waterReflection, traceSecondary in waterFromAbove);
     `traceLodBody` has 2 `traceVoxGrid` and 3 `traceLodRegions` sites; `traceLodRegions` has 2 `traceRegion` and 1
     `traceGroups` site.
   - Why it costs: the instruction cache works over megabytes, and compiles take 50 s and 11 GB.
   - Fix: one call site per structure, one `traceView` site (2 passes for the water re-trace), and the water rays moved
     out of farMain. About 6× less code.
2. **[noinline] calls passing structs** (the 7936 B of shared memory): `farHitTake` (being replaced by scalar
   `farHitSource`), `coarseSmoothHit` (VgGrid in, SmoothHit out), `brickHit`/`brickWalk` (BrickHit, 7 sites, one inside
   the walk loop), `cumulusDensity` (CloudColumn by value), and `brickMisses` (37 sites). Fix: inline them, and run them
   once after the walk.
3. **Local arrays indexed at run time.**
   - Tables: `G_FACE_LOW/HIGH` (100 copies), `MIP_AT` (32), `G_ERR` and `WAVE_SPREAD` (40× f32[16]).
   - Per-thread arrays: `gLodR[8]`, filled per pixel; `pathPtr/pathRank`.
   - Fixes:
     - bit tricks: `MIP_AT[l] = 341 - (341 >> 2l)`, `G_FACE_LOW[a] = (0x204 >> 4a) & 15`, `HIGH = (0x315 >> 4a) & 15`;
     - `G_ERR` and the level radii into FrameGpu, computed on the host once a frame;
     - `[unroll]` the wave loop;
     - keep only the groups stack, as one copy.
4. **Rarely-run paths reached by every pixel** (they cost registers and code):
   - `waterFromAbove`: a secondary walk, a reflection walk, 8 cumulusDensity calls, 12 waves;
   - `eyeWaterDepth`: up to 48 lookups per pixel, though the answer is the same for every pixel;
   - `traceActors` (up to 512 per pixel), farLights, clouds and sky;
   - measurement code in the hot loops (`scm`/`cSecCount`, `dbgPath`, `rings`, `cAblate`).
   - Fix: water and actors in nearLightMain or their own compacted kernels; the eye's water depth on the host;
     measurement behind `#if ATLAS_MEASURE`.
5. **Heavy work inside the divergent walk loop:** `brickHit` (a 48-step micro-walk), `vgShapeHit`,
   `texSample`/`vgTexel`, and the secondary rays' `voxelStops`/`secondarySigma`. Fix: the walk returns a candidate
   (voxel, t, axis, material, kind); refinement runs once after the loop, and on a miss the walk resumes, bounded.
   Ceiling first: `ATLAS_ABLATE` 268435456 (shapes as cubes) and 536870912 (no cut-outs).
6. **Long live ranges across the walk:**
   - `TraceResult fine = gTr` (10 registers live for 768 segments, only for the grazing look);
   - graze, rings, pinned and gapEnd state;
   - the full TraceResult, albedo included, carried from the start;
   - `gHead`, the air globals and `gLodR`.
   - Fix: the walk carries {t, steps, axis, dither}; albedo after the hit; the grazing look as a post-walk pass;
     `airBegin` after the walk.
7. **Statics are still live in the non-split kernels:** atlas_far, nearlight, neartemporal, nearup and sunmap each have
   28 Private globals, because the walk is `[noinline]` there. nearLightMain reports 5328 B of local memory, which hits
   the lighting slice too. Fix: after item 1, inline everything under `traceLodCore`.
8. **groupshared:** none in farMain.

## Verdict: keep splitting, wavefront-style, incrementally

The split frames are already half wavefront: farMain writes a G-buffer of ~40 B/px that the near kernels shade. One
inlined farMain doesn't hold up as features grow.

**Target:**
- a lean trace kernel: ray generation, the amortisation source, one `traceView` site, store the hit;
- separate kernels: water optics, actors (BVH), lights, volumes, sky and clouds composite.

**Expected occupancy:**

| Registers | Warps/SM | Occupancy |
|---|---|---|
| 168 (today) | 12 | 25% |
| ≤128 | 16 | 33% |
| ≤96 | 20 | 42% |
| ≤80 | 24 | 50% |

At ≤96 registers, with shared memory at 0, L1 returns to ~128 KB.

**Trade-off:** a hit buffer of ~28 B/px is ~100 MB read and written per frame at 1440p, ≈0.25 ms at 936 GB/s, against
a 48-62 ms far pass.

**Decide with one experiment:** current vs trace-only (it built at 128 registers and a 372 KB binary; fix its bench
script) vs "duplicates removed only", on the saved scene.

## Build-time guard (ratchet)

- **Without a GPU, on every build:** `tools/spv_budget.cpp` runs after each slangc (like `spv_exact`), with the limits
  in `native/shaders/kernel_budget.json`:
  - 0 Private globals;
  - the expected Workgroup variables;
  - no farsplit calls with struct or pointer parameters (except a whitelist);
  - farsplit function-scope arrays ≤ 3;
  - an instruction-count cap (155k now, lowered as the duplicates go).
- **With the driver:** `pipelineStats()` in vk_backend.cpp is checked against a budget keyed by pipeline and vendor,
  printing `TRACE FAIL pipebudget` (and failing hard with `ATLAS_PIPE_BUDGET=strict`). `spv_jit_time` grows into an
  `atlas_pipe_gate` target when a GPU is present.
- **NVIDIA thresholds:**
  - farMain: registers ≤168 → 128 → 96; shared memory ≤7936 → 0; local memory ≤ today's value → ~0 apart from the
    groups stack; stack 0; binary ≤2.9 MB → 0.6 MB.
  - nearLightMain: registers ≤128; local memory ≤5328 → ~0.
- **Any increase fails.** The budget file changes only in a deliberate commit.
