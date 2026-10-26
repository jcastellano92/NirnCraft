# Instanced world: design verdict (spike, 2026-10-26)

**Owner's idea:** instance everything placed (trees, rocks, scatter, houses, settlements), give each instance
location-seeded variation (including structural variation larger than a block, such as room layouts and tree damage),
and store player edits as deltas.

This was a read-only Opus spike. **M** marks a measured number and **E** an estimate.

## Verdict: do it partly

Instances become the **generation and storage format**: instance records, a library of parts, and compositions resolved
once when things are placed. Every chunk is **flattened** into ordinary level-0 block ids when it is generated or decoded.

Grid cells don't point at instances, so the trace walk is unchanged and pays nothing extra (blueprint rules 3 and 7).

**Why not instances inside the walk:**
- **Cost:** two more dependent loads per overlapping instance per step, plus transform state. E: +40-100% walk time in
  forests, and a likely register spill. That is the old farMain trap.
- **Precedent:** material micro-bricks are already shared and resolved after the walk, and they still cost +19-36% of
  far work (M, ebcbbbbc).
- **Coarse consistency:** the coarse levels come from a non-linear 2×2×2 vote (world.cpp:390). Mixing part mips can't
  equal "the real world, downsampled". Flattening and then downsampling is consistent near and far by construction.

## Data model

- **Prefab library.** Level-0 ids in boxes, with per-voxel class bits (overwrite, soft-only, carve).
  - Trees are pre-built variants or parts (trunk, limb, canopy); houses are modules. Under 10 MB (E).
  - Java builds it once at load and ships it like `fprog`.
- **Instance record, 8-12 B.** A prefab or composition id, a local position, a 3-bit rotation/mirror and a 16-bit seed.
- **Composition, once, at placement.** Houses use a grammar or WFC-style rules over a kit of parts. Tree parameters
  (height, damaged branches, foliage high or low) pick parts or a variant. Everything is seeded by location, so it is the
  same every time on every machine.
- **Flattening, a deterministic GPU pass.**
  - Order rules: logs beat leaves, a later instance wins, and leaf distance takes the minimum.
  - Terrain fit (trunk sink, roots) runs as small per-instance steps.
  - Buried parts are dropped by the soft-only rule.
- **Variation.**
  - **Shape changes** (missing or added blocks, broken branches) are resolved at flatten time, so the coarse levels
    already contain them.
  - **Material swaps** (mossy stone) become block ids at flatten time, which keeps them exact for collisions.
  - **Tint** is a zero-mean jitter applied at shade, after the walk, so near and far agree.

## Wins and costs

**Trace:** zero cost.

**Generator.**
- **Today:** decoration takes ~7.9 ms a chunk (M): ores 58%, trees 26%, rocks 5%, cover 5%.
- **Instancing trees and rocks:** about 1.3× faster (E).
- **Ores off the visibility path:** about 1.9× faster (E), which instancing alone doesn't touch.
- **Both together:** about 3.5× faster (E).

**Storage.**
- **Today:** 8.9 MB/km², about 5.3 GB for all of Tamriel (M).
- **Savings:** 1.4-2× smaller in forests, about 1.05× in open land (E).
- **VRAM:** unchanged. A palette-compressed pool would be the bigger VRAM win, about 2×.

**Lighting.** Flattened blocks get world-space cache entries like any block, so the lighting cache (option A) works
unchanged. Per-prefab baked light is wrong in tree stands and against terrain, so it can only serve as a prior for far
levels, or be skipped.

**Server and gameplay.**
- **Same blocks everywhere:** the server, `AtlasServerGen` and collisions all read flattened ids, so nothing changes there.
- **Edits:** they stay positional id deltas.
- **Determinism improves:** the GPU copies library integers instead of re-running tree maths.
- **Java side:** it must place from the same library. That changes the world's trees, so the world fingerprint changes:
  new worlds, or a rebake.

**Risks.** Fewer distinct trees, overlapping stands, leaf distance across neighbouring trees, the Java port, and a
changed world.

## Staged path (a ceiling or gate at each stage)

| Stage | Work | Gate |
|---|---|---|
| S0, ~1 h, no code | Bake the same Falkreath region with `feat=0` and `feat=1`, and split tree planning from commit (`ATLAS_FEAT_PART_TRACE`) | Upper bound on bytes and on generator ms from any instancing |
| S1, ≤ 1 day, trees only, GPU, behind a flag | N = 16/64 variants per species built with the existing builders; `placeTreeAt` picks a variant and rotation by hash and feeds `tCommit` | Tree ms from 1,668 to ≤ 1/3; falkreath tree dE no worse; owner judges variety |
| S2 | Java `FloraTreeFeature` uses the same library | Parity test and atlas-collide at 0 mismatches |
| S3 | Houses, ruins and settlements as kit-of-parts compositions written into the overlay tapes | Overlay parity; edits on composed buildings come back voxel-exact |
| S4 | Region format stores instance lists plus terrain-only sections; the GPU decoder flattens, then applies deltas | Region bytes against the S0 bound |
| Separate | Ores off the visibility path (generator work in progress) | Decoration ms |

**Files:** native/shaders/atlas_features.slang, mod/.../atlas/AtlasFeatureProgram.java, native/src/features_engine.cpp,
native/tools/gen_bake_tool.cpp.
