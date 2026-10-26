# Infinite ATLAS render blueprint

The renderer is built from **stages**: small kernels with fixed interfaces, each with its own pipeline-stats budget, timestamp and off switch. A feature is a stage, or a branch inside one stage that is kept within that stage's budget. A feature never touches a shared kernel.

This doc grew out of the far-kernel review of 2026-10-25. In that review, one inlined `farMain` (split frames) reached 145k SPIR-V instructions, a 2.79 MB driver binary and 168 registers (25 % occupancy). It held 24 copies of `traceRegion` and 12 of `traceGroups`. Every feature added to it slowed every pixel.

## 1. The stage graph

```
amortSource → trace → [hit buffer] → classify → shade stages → temporal → upsample → composite → publish
              (+ voxgrid, lights, clouds, sunMap: per-frame producers of shared resources)
```

| Stage | What it does | Today (split frames) |
|---|---|---|
| `voxgrid` | world grid levels on the GPU | the 14 `atlas_voxgrid` kernels (mark `voxgrid`) |
| `lights` | cull, admit and bin the dynamic lights | `lightCullMain`, `gridLightMain`, `lightAdmitMain`, `lightBinMain` (mark `lights`) |
| `clouds` | the half-resolution volume | `cloudMain` (mark `clouds`) |
| `amortSource` | per-tile decision: reuse last frame's hit or trace | inside `farMain` (`farHitSource`, the FRAME_FAR_AMORT 8×8 tile hash) |
| `trace` | primary rays and the water re-trace; writes the hit buffer only | `farMain` built with ATLAS_FAR_SPLIT (mark `far`), which also does water optics, actors, sky and far lights |
| `classify` | builds the work lists from the hit buffer | none (`nearLightMain`'s `gsSplitFresh` early-out, `splitTraces`) |
| `shade.light` | sun, sky, bounce, block and dynamic lights | `nearLightMain` (`nearLightAt`), half resolution, tiles scheduled by `lightCtl.y` |
| `shade.water` | water optics: waves, reflection, Snell, glint | inside `farMain` (`waterFromAbove`, `waterFromBelow`) |
| `shade.actors` | entities | inside `farMain` (`traceActors`: brute force over up to 512) |
| `shade.volumes` | clouds and smoke over the surface | `sampleClouds` inside `farMain` / `nearTemporalMain` |
| `shade.sky` | sky, atmosphere, far-light glow on miss pixels | inside `farMain` (`skyColor`, `farLights`) |
| `upsample` | fills the untraced pixels of each 2×2 | `nearUpsampleMain` |
| `temporal` | history and the composite: albedo × light × T + inscatter | `nearTemporalMain` |
| `publish` | hand-off to the host | `publishMain` |

Other producers: `sunMap` (`sunMapMain`) and `nearIngest`. The offline tools use the same stages, run through `atlas_render`.

## 2. Standard interfaces

### Hit buffer `HIT_V1` (8 words a pixel, 32 B)

This promotes today's `farHit`/`prevFarHit` (bindings 41 and 42) to be the one hit record. The trace stage writes it on every frame, not only under FRAME_FAR_AMORT.

| Word | Content |
|---|---|
| w0 | `tHit` (f32; < 0 means a miss) |
| w1 | `tW`: distance to the water surface (−1 none) |
| w2 | `mat` (16 bits) \| flags (16 bits) |
| w3 | `hasAlbedo` word: bit 0 own albedo, bit 1 `HIT_SMOOTH_N`, bits 16–31 octahedral normal |
| w4 | `axis + 1` (2 bits) \| `lod` (4) \| water `lod` (4) \| `steps` (16) |
| w5 | water `mat` |
| w6 | albedo r, g (half floats) |
| w7 | albedo b (half) \| stamp (frame.temporal.x low 16 bits) |

Flags in w2: `HB_TRACED`, `HB_REUSED`, `HB_CAPPED`, `HB_EYE_WATER`, `HB_ROOFED`.

Rules for the layout:
- Readers use `hbLoad(pix)` / `hbStore` from `atlas_hit.slang`. No stage decodes the words itself.
- `HIT_VERSION` is in FrameGpu, and the host refuses a kernel whose version differs.
- A new field means a new version, with the layout table updated here.

Overloaded buffers this replaces:
- `nearDepth` (distance, or the hit for the light passes);
- `nearInfo` (sky byte, `255 − lod`, normal, and `SPLIT_TRACED` in the low byte);
- `outNearLight.a` (visibility, −1 roofed, −2 `SPLIT_SKIPPED`);
- `waterAdd.a`;
- the `outColor` that holds albedo × waterA until the composite.

### Work lists

- A `WorkLists` buffer holds per-stage lists of **8×8 tiles** plus an indirect-dispatch header. Tiles keep warps spatially coherent; a list of single pixels is only for sparse stages such as water.
- Lists: `WL_TRACE` (tiles not reused), `WL_LIGHT` (tiles scheduled or young), `WL_WATER`, `WL_ACTOR` (tiles whose actor bin is non-empty), `WL_SKY`.
- `classify` writes them; each stage runs `vkCmdDispatchIndirect` on its own list.
- A stage never decides with a per-pixel branch that it has nothing to do.

### Shared resources

| Class | Bindings today | Rule |
|---|---|---|
| WORLD (read-only) | `regionTable`, `regions`, `nodes`, `leaves`, `runEnd`, `runMat`, `palette`, `heightMip`, `groupData`, `vgChunks`, `vgBlocks`, `vgBits`, `vgPool`, `texData`, `brickData` | one copy; any stage may read |
| LIGHTS | `lightSrc`, `lightCand`, `lightList`, `actors` | written only by `lights` (later also the actor BVH) |
| MEDIA | `weather`, `cloudNoise`/`cloudNoiseTex`, `sunMap`, `cloudBuf`, `cloudDepth` | written by their own producer |
| FRAME products | hit buffer, `outNearLight`, `waterAdd`, `outColor`, `outNormal`, `outDepth`, `outSurface` | one writer each |
| HISTORY | `prevSurface`, `prevFarHit`, `prevNearLight`, `prevNearAge`, `prevCloudBuf`, `prevCloudDepth` | swapped per slot; read-only |

Today all of these share one 43-binding set (`farLayout_`). The target is three sets:
- set 0: frame and WORLD (stable);
- set 1: the stage's products;
- set 2: HISTORY.

With that, adding a stage doesn't renumber the existing bindings or the CPU build's `FarGlobals` order.

### Frame constants (`FrameGpu`)

Anything that is the same for every pixel is computed once, on the host or in a one-thread pre-pass, and read from FrameGpu. It is never recomputed per thread. This covers:
- the level radii `R[8]` (today `lodFillR()` runs in every thread of 5 kernels);
- the `G_ERR` table;
- `eyeRoofed` (today it traces a ray upward per pixel in `nearUpsample`/`nearTemporal`);
- the eye's water depth and material (`eyeWaterDepth`: up to 48 lookups per pixel);
- `gHead` (`groupData[0]`).

Also in FrameGpu:
- stage-enable bits, in their own word: `dims.z` is nearly out of bits;
- quality knobs, in a `float4 knobs[2]` block;
- the measurement word (today ATLAS_ABLATE in `jitter.w`), read only by builds with `ATLAS_MEASURE`.

## 3. Building-block template

### Adding a stage

1. **Files.** One file `native/shaders/stage_<name>.slang` with entry `<name>Main`. It includes `atlas_shared.slang`, `atlas_hit.slang` and the world headers it needs. Inputs and outputs are listed at the top: the hit-buffer fields, the work list and the bindings it reads and writes.
2. **Host record.** One `Stage` record in `vk_backend.cpp`:
   ```
   { name, spv, set layout, list WL_x, gt.mark("<name>"), env ATLAS_STAGE_<NAME>=0, FrameGpu enable bit, knob index }
   ```
   When the stage is off, its outputs hold a documented neutral value, and downstream stages must accept it.
3. **Budget.** A `kernel_budget.json` entry: registers, shared, local (low 32 bits), stack, binary, SPIR-V instructions, Private globals = 0, struct calls = 0, function arrays. Both the build lint and the pipeline gate read it (section 7).
4. **Ceiling test.** `atlas_render --stage-ceiling <name>` runs the stage on a saved scene three ways: full, as a hollow variant (`-DSTAGE_CEILING`: read the inputs, write neutral), and off. The timestamp deltas go to the ledger.
5. **Quality knob.** One continuous 0..1 knob, registered with the adaptive-quality controller (`AtlasQuality`, ADAPTIVE-QUALITY.md), with a floor and an "on" threshold.

### Adding a feature inside a stage

- Compile the stage with and without the feature (`#if`), and check its stats against its own budget. If registers rise, it is not a branch: it becomes its own stage on its own work list.
- Uniform decisions (whether the feature is on this frame, its tables) go in FrameGpu.
- Heavy work found in a loop (a brick, a micro-shape, a texel) is recorded as a candidate and done once after the loop; on a miss the loop resumes from where it was. This is the pattern `smoothPrimaryHit` / `traceView` already use.

## 4. Rules

Each rule comes from a failure already measured.

1. **No mutable `static` read or written across a real call.** Private globals = 0 for every render kernel. State is passed explicitly, and RNG state goes in a storage buffer.
2. **No `[noinline]` function that takes or returns a struct, or has `inout`/`out` parameters, in a render kernel.** The driver spills around the call into shared memory and that shared memory comes out of L1. Going from 7936 to 16640 B per group took L1 from 64 KB to about 28 KB and made the pass 40 % slower.
3. **No heavy call inside a divergent loop.** Find the candidate in the loop and refine once after it.
4. **One call site per traversal.** Walks are inlined once. A second caller loops back to the same site instead of making a new copy.
5. **No dynamically indexed local arrays except a real traversal stack, one per stage.** Constant tables become closed forms or FrameGpu entries; for example `MIP_AT[l] = 341 − (341 >> 2l)` and `G_FACE_LOW[a] = (0x204 >> 4a) & 15`.
6. **Nothing reached by every pixel that only some pixels need.** It moves to a stage with a work list.
7. **Short live ranges across the walk.** The walk carries only {t, steps, axis, dither}. Albedo, texel and normal are worked out after the hit. Air and key-light setup run after the walk.
8. **Measurement code** (counters, `dbgPath`, ablation switches, rings) **compiles only with `ATLAS_MEASURE`.**
9. **Every loop has a bound, and every submission stays far below the 2 s watchdog** (AGENT-RULES).
10. **Pixels drive everything.** Every level, reuse and quality decision is a footprint test, `dist × frame.params.w × frame.params.z`:
    - `lodFromFootprint`/`lodPick` (the stochastic level);
    - the amortisation test (footprint ≥ 1 block);
    - `SMOOTH_COARSE_PX` (a voxel ≥ 1.5 footprints is drawn smooth);
    - `gSecFoot0`/`gSecSpread` (secondary-ray cones);
    - `waterFootprint` (waves fade).

    Never a fixed distance ring.

## 5. How the later features plug in

- **Entities (BVH).**
  - `actorBuild` builds an LBVH over `actors` (by Morton codes).
  - `actorBin` bins actors per tile, as `lightBinMain` bins lights.
  - `shade.actors` runs over `WL_ACTOR`, depth-tests against `tHit`, and writes an actor record to the hit buffer (a flag plus normal and albedo).
  - This replaces `traceActors` in `farMain`.
- **Volumes.** `clouds` stays a half-resolution stage with its own history. Smoke is a second volume stage over tiles holding smoke columns. `composite` blends both by depth.
- **Dynamic lights.** Already a producer chain (`lightCull` → `gridLight` → `lightAdmit` → `lightBin`). `shade.light` consumes `lightList`; a light's shadow rays live in `shade.light`, never in `trace`. GI (north star) belongs in `shade.light` and its history.
- **Weather.** Data only: the weather fields and FrameGpu feed `clouds`, `shade.water` (wind) and later `shade.volumes`. No trace code.
- **Far movers.** Voxel changes go through the world data (voxgrid ingest), so the trace is unchanged. Non-voxel movers are actors.
- **Amortisation.** `amortSource` (`farHitSource`) copies reused hits from `prevFarHit` into the hit buffer and emits `WL_TRACE`. Light reuse stays in `lightCtl.y` scheduling, which becomes `WL_LIGHT`.

## 6. Migration order

Each step must keep the image identical: `atlas_render` images match on saved scenes, either bit-for-bit or within the ceiling test's stated tolerance. Each step is measured: stage timestamps plus pipeline stats go to the ledger.

0. **Guards first** (section 7), seeded with today's numbers as the ratchet.
1. **Uniform values into FrameGpu**: `R[8]`, `G_ERR`, `eyeRoofed`, the eye's water depth and material, `gHead`. Constant tables become closed forms.
2. **One call site per traversal**:
   - `traceLodBody`: one `traceVoxGrid` and one `traceLodRegions` site;
   - `traceLodRegions`: one `traceRegion` site;
   - `farMain`: one `traceView` site.

   Then inline everything under `traceLodCore` in the non-split kernels, which takes Private globals to 0.
3. **Remove struct calls**: finish `farHitSource`; inline `coarseSmoothHit`, `brickHit` and `cumulusDensity` once, outside loops. Target shared memory: 0.
4. **Hit buffer.** `farMain` always writes `HIT_V1`. `nearLight`, `nearUpsample` and `nearTemporal` read it through `hbLoad` beside the old buffers, then the old buffers are retired one at a time.
5. **`shade.water` out of `trace`** (`waterFromAbove`/`waterFromBelow`, `waterReflection`, `cloudShadow`), running over `WL_WATER`.
6. **`shade.actors` out of `trace`.** Same brute force first, then the BVH.
7. **`shade.sky` and the composite for miss pixels out of `trace`.** `trace` is now lean; its target is ≤ 128 registers, then ≤ 96.
8. **Deferred refinement** for bricks, shapes and texels.
9. **`ATLAS_MEASURE` builds** for measurement code; `shader_watch` builds both variants.
10. **`classify` and indirect dispatch.** `WL_LIGHT` replaces `gsSplitFresh` and `WL_TRACE` replaces the per-pixel amortisation branch.

## 7. Conformance audit

The "local" and "shared" figures are per-thread and per-group bytes from the driver; local is the low 32 bits.

| Kernel / stage | Interfaces | Statics | Struct calls | Local / shared | Duplicated bodies | Rare paths for all | Measurement code | Off switch | Timestamp | Budget entry |
|---|---|---|---|---|---|---|---|---|---|---|
| farMain, split | overloaded buffers ✗ | 0 ✓ | `farHitTake` (being fixed), `coarseSmoothHit`, `brickHit`/`brickWalk`, `cumulusDensity` ✗ | 3392 B (17:40) / 7936 ✗ | 24 `traceRegion`, 12 `traceGroups`, 8 `traceVoxGrid` ✗ | water, actors, eye water, sky ✗ | ABLATE, counters, rings ✗ | `ATLAS_FAR_SPLIT_KERNEL` ✓ | `far` ✓ (shared name) | ✗ |
| farMain | ✗ | 28 Private ✗ | TraceIn/TraceOut, SecIn/SecOut by value (45 calls) ✗ | 12336 / 12544 ✗ | called, not copied ~ | everything ✗ | ✗ | (fallback kernel) | `far` | ✗ |
| nearLightMain | ✗ | 28 ✗ | TraceIn/Out ✗ | 5328 / 12560 ✗ | ~ | caustics, eyeRoofed ✗ | ✗ | FRAME_NEAR_SPLIT, `ATLAS_NEAR_FULLRES` ✓ | ✓ | ✗ |
| nearTemporalMain | ✗ | 28 ✗ | ✗ | 5504 / 12544 ✗ | links the whole trace (490 KB SPIR-V) ✗ | eyeRoofed, fallback light ✗ | ✗ | with split ✓ | ✓ | ✗ |
| nearUpsampleMain | ✗ | 28 ✗ | ✗ | 2528 / 12544 ✗ | links the whole trace for eyeRoofed (282 KB) ✗ | eyeRoofed ✗ | ✗ | with split ✓ | ✓ | ✗ |
| sunMapMain | ~ | 28 ✗ | ✗ | 2416 / 12544 ✗ | ~ | ~ | ✗ | `sunDispatchRows_` ✓ | ✓ | ✗ |
| cloudMain | ✓ | 0 ✓ | `cumulusDensity(CloudColumn)` ✗ | 128 / 6912 ✗ | ✓ | ✓ | ~ | FRAME_CLOUDS; FRAME_CLOUDS_QUARTER as knob ✓ | ✓ | ✗ |
| publishMain | ✓ | ✓ | ✓ | 0 / 0 ✓ | ✓ | ✓ | ✓ | n/a | ✓ | ✗ |
| lights (4) | ✓ | ✓ | `gridLightEmit` (scalars only) ✓ | groupshared by design ✓ | ✓ | ✓ | ✓ | `ATLAS_CPU_LIGHTS` ✓ | one mark for 4 ~ | ✗ |
| voxgrid (14) | ✓ | ✓ | no calls ✓ | 0 local; groupshared by design ✓ | ✓ | ✓ | ✓ | `ATLAS_VOXGRID_BUDGET` ~ | one mark for 14 ~ | ✗ |
| generators: `atlas_gen*`, page, tile | n/a (off-frame) | `g_bad`, `g_colX`/`g_colZ`/`g_colOwn`/`g_colVals` ✗ (`g_bad` written in `[noinline]`: correctness risk) | many, -O3 | 44–69 function arrays ~ | ~ | n/a | ✓ | gen queue | per-phase marks ✓ | ✗ |
| `atlas_features` (-O0) | n/a | `sG`, `sB`, `sRA` ✗ (RNG already in scratch ✓) | 164–281 functions ✗ (JIT time) | ~ | ~ | n/a | `TREE_TRACE` constant ✓ | ✓ | per-phase ✓ | ✗ |
| `atlas_overlays*` | n/a | `g_bad` ✗ | ~ | groupshared ✓ | ~ | n/a | ✓ | ✓ | per-phase ✓ | ✗ |
| far stages: `atlas_lod`, `atlas_lod_build` (tools) | ✓ | ✓ | no calls ✓ | traversal stacks ✓ | ✓ | n/a | ✓ | tools only | ✗ | ✗ |
| bake (`gen_bake_tool`) | drives the gen kernels; inherits their rows | | | | | | | | TRACE progress ✓ | ✗ |

### Ranked fix list

1. **farMain split** (migration steps 1–3 and 5–7). It is the game's frame.
2. **Non-split far, nearLight, nearTemporal, nearUpsample, sunMap**:
   - get Private globals to 0 (step 2's inlining under `traceLodCore`);
   - `eyeRoofed` into FrameGpu, which unlinks the trace from `nearTemporal`/`nearUpsample`;
   - `TraceIn`/`TraceOut` by value become an inlined core.
3. **`g_bad` in the generators.** Replace it with a returned flag, or a scratch-buffer word as `sRA` already does. Then remove `g_col*`, `sG` and `sB` the same way. Verify with the parity tests.
4. **cloudMain.** Inline `cumulusDensity` once into the march; target shared memory 0.
5. **Budget entries for every kernel**, and per-kernel timestamps for the lights and voxgrid groups.
6. **Measurement code behind `ATLAS_MEASURE`** in all far-module kernels.

### What is enforced automatically

- **Build lint, no GPU** (`tools/spv_budget.cpp`, run as an extra `COMMAND` after each slangc in `native/CMakeLists.txt`, like `spv_exact`):
  - Private globals = 0 (generators allow-listed until fix 3 lands);
  - the Workgroup variables match the budget's list;
  - no calls to struct, pointer or `out` signatures in render kernels;
  - function-array count and bytes;
  - SPIR-V instruction count and bytes (this catches duplicated bodies);
  - every built entry point has a `kernel_budget.json` row;
  - in non-`ATLAS_MEASURE` builds, no load of the FrameGpu measurement word.
- **Source lint** (a Python check in the same command): `[noinline]` signatures that take structs, `inout` or `out`; any `static` that isn't `const`; `groupshared` outside allow-listed entries; every `[shader("compute")]` entry has a `Stage` record and a `gt.mark` name.
- **Pipeline gate, on the GPU.** `pipelineStats()` in `vk_backend.cpp` (:1767) compares registers, shared, local (low 32 bits: bit 36 is set in every NVIDIA report), stack and binary against the budget as a ratchet. It prints `TRACE FAIL pipebudget …`, and with `ATLAS_PIPE_BUDGET=strict` the pipeline creation fails. It runs:
  - in an `atlas_pipe_gate` target, using `spv_jit_time` extended to read each kernel's bindings from its SPIR-V, after the build when a GPU is present;
  - in every `atlas_render` run.
- **Ceiling and off-switch tests** run through the harness: each stage off must give the documented neutral output, and its time goes to the ledger.
- **Still a reviewer's job:** whether a path is rare but reached by every pixel, and what an interface means. A reviewer agent checks each new stage against this blueprint.
