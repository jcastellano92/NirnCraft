# Image quality: causes of shimmer, banding and dither (analyst, 2026-10-26)

A read-only Opus analysis of the owner's first play-test on the lean renderer (screenshots 47-54, build 02954217),
from reading the code. Nothing was built or measured.

## Lean versus farMain inputs (parity)

- **No missing temporal input.** In lean, the shade pass is farMain itself, built with ATLAS_FAR_PASS 2. It writes the
  same per-pixel buffers the temporal passes read: depth, surface distance, normal and level, NDC depth
  (atlas_far.slang:4032-4048).
- **Jitter** is the same (stage_trace.slang:283).
- **Stale stamps:** records are stamped stale only when the eye is under water (stage_trace.slang:796). Neither
  nearTemporal nor the TAA reads that buffer.
- **What lean changes:** its surfaces are plain cubes in flat colours, with no textures, cut-out leaves, shapes or
  bricks. Nothing hides the noise, so the problems below are easier to see. The pipeline problems themselves are
  shared with farMain.

## Ranked causes

1. **Dither in shade (53/54): the existing lighting pipeline.**
   - **Too few samples:** one sun ray, one sky ray (SKY_RAYS=1, atlas_far.slang:2206) and one bounce per traced pixel.
     Only one pixel in each 2×2 is traced (3254), and a tile is traced at `lightCtl.y`, 0.25 when still. So a pixel
     gets one sample of its own every 4-16 frames.
   - **Structured grain:** the sky ray's offsets are screen-space interleaved gradient noise (2211-2212) on that
     stride-2 grid.
   - **Short history:** it holds at most 8 samples (3420).
   - **Resets on motion:** the history is bilinearly resampled (3218-3236), which softens sun-visibility edges. Every
     disagreement on sun visibility then resets the count to 1 (3472-3475). Dappled shade is all edges, so moving fast
     in it leaves about one sample per pixel.
   - **Sky and bounce:** the sky estimate under cover is noisy (2256-2260), and the bounce is all-or-nothing.
2. **Structure shimmer (51) and far-field crawl: shared with farMain.**
   - **Level threshold rotates every frame:** the per-pixel level threshold `xi` changes each frame
     (stage_trace.slang:288, 324).
   - **Level 1 near the camera:** the residency rule leans to level 1 from r0/2 outwards (lodRes, 49-51). r0 is the
     distance to the *nearest* edge of the level-0 grid (api.cpp:1251-1261), so it shrinks while the grid fills.
   - **Cubes and blobs swap:** a level-1 voxel near the camera is over 1.5 px, so it's drawn as a smooth surface (649),
     and pixels flip between cubes and blobs every frame. The blobby trees in 54 are this.
   - **Reused far hits:** they're fetched from the nearest previous pixel (210-227), and 8×8 tiles refresh one frame in
     four, so the distance crawls.
3. **Edge shimmer: weaknesses in the existing TAA** (near_taa.fsh).
   - **Low-precision history:** it's RGBA8 (AtlasNearPass.java:559), blended at 0.12 (569) and fetched bilinearly
     (581), which smears under motion.
   - **Clamp throws away edges:** an RGB min/max clamp (near_taa.fsh:25-32, 46) discards converged edge coverage.
   - **No far depth:** far pixels get depth 0 (near_resolve.fsh:323), so they're reprojected as directions
     (near_taa.fsh:40). That gives sub-pixel parallax error when the camera moves.
4. **Shadow banding while moving: the existing pipeline.**
   - **Tile bands:** untraced 16 px tiles keep a resampled history (3463-3464), and `lightRate` eases only 0.2 per
     frame towards its target (api.cpp:1211-1214).
   - **Upsample ignores shadows:** the 2×2 upsample weighs depth and normal but not sun visibility (3389-3393).
   - **Shadow origin:** coarse-level hits push the shadow-ray origin out by up to 1.5 voxels (3059-3061).

## Fixes

| Fix | Where | Cost |
|---|---|---|
| Keep sun visibility separate and sharp; denoise only sky + bounce; replace the stale reset with a variance clamp; add a normal test to the history lookup | nearLightAt, nearTemporal (3227, 3472) | ~0.3-0.5 ms |
| Keep `xi` fixed per pixel inside the level-0 window (or anchor it to the world); no smooth surface for level 1 near the camera | stage_trace.slang:288/649, farMain 3806 | ~0 |
| RGBA16F history, Catmull-Rom fetch, YCoCg variance clipping, true far distance for reprojection | near_taa, AtlasNearPass | ~0.1-0.2 ms |
| Trace the sun ray every frame; schedule only sky and bounce by tile | nearLightMain | ~0.3 ms |
| Textures, cut-out leaves and shapes at level 0 in lean | stage_trace (lean 6.4 and textures) | register pressure |

**Denoiser.** 53/54 need a real denoiser. An SVGF-lite / ReLAX-diffuse style filter fits:
- luminance moments in nearTemporal for variance;
- 3 à-trous iterations on the half-resolution traced grid before the upsample, stopping at depth and normal edges;
- sky + bounce only, with sun visibility kept separate.

It's ~0.2-0.3 ms at ~0.9 Mpx, inside the 3 ms lighting budget. ReBLUR (~1-2 ms) targets specular, so it isn't worth it
for diffuse light.

**Repro gap.** gen_bake_tool `views` can't reproduce any of this. It uses a fixed camera with no jitter, the tile
schedule is always on, the 2×2 phase is fixed and `xi` never rotates, and the TAA lives in Java. It needs:
- a `path=` option with one pose per frame;
- Halton jitter (`atlas_set_jitter`);
- per-frame dumps of the near light and the level byte;
- the TAA in native code.

Metrics: the temporal standard deviation of reprojected luminance over a 60-frame dolly on a forest export, and how
often the level byte flips per pixel.
