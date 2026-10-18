# Aether: Skycraft's native far-field renderer

Status date: 2026-10-18. Branch `aether-renderer` (worktree `.claude/worktrees/aether`), code in [`native/`](../native/README.md).
This doc covers what aether is, what is built and measured, how it joins the NeoForge 26.3 mod, and where it
departs from the original master plan and why.

## 1. What it is

Everything past the rasterised near field is drawn by `aether_core.dll`, a native library with a plain C ABI:

* **Distant terrain.** A clipmap of 256³-voxel regions at seven LODs (1 m to 64 m voxels, ±512 m to ±32 km). Each
  region is a geometry SVDAG with 64-bit 4³ leaf bricks and an RLE material stream, after Aokana (2025).
* **Curved planet.** The view frame stays flat. A point at horizontal distance d stands at world altitude
  y + d²/2R, so view rays are walked as parabolic chords. The near field applies the matching vertex drop
  y' = y − d²/2R, and the two meet without a seam.
* **Cloud shell.** A line-for-line port of Skycraft's own cloud model: parcels, buds, anvil profile, overcast floor,
  and the middle and ice layers. It reads the same `WeatherFieldTexture` fields and the baked noise volume, measured
  above the curved datum, so decks wrap over the horizon. Cloud shadows land on terrain kilometres away.
* **One kernel, two builds.** `shaders/aether_far.slang` compiles to SPIR-V (Vulkan) and to C++ (the CPU reference).
  The tests compare the two builds, so they can only differ in floating-point rounding.
* **Zero-copy bridge.** Images allocated in Vulkan are imported into the game's OpenGL context through
  EXT_memory_object_win32, with two shared semaphores. A frame never passes through the CPU.
* **Java 25 boundary.** `com.skycraft.aether.AetherNative` binds the ABI with `java.lang.foreign`. It needs no JNI
  and no Minecraft types.

Authority is unchanged. The Java generator (plans, roads, towns, caves, structures) and loaded chunks stay the truth.
They push column tiles and chunk sections into aether, and aether never invents terrain. The offline harness alone
uses a "preview" source built from the bundled raster; every output that uses it is labelled.

## 2. Built and measured (RTX 3090, driver 616.56, Windows 11)

| Piece | Evidence |
| --- | --- |
| SVDAG builder | Round trips over empty, full, sphere, terrain and random volumes have 0 mismatches. A full solid region is 102 node words. Y-major child/bit order halved terrain material memory (73 → 36 MB for the 250-region clipmap). |
| Far kernel | 1280×720 out to 36.8 km with shadows and aerial perspective: 0.8–2 ms GPU, 5 ms with the cloud shell. The 250-region clipmap builds in 100–150 ms on the CPU. |
| Correctness | Flat plane and four grazing directions (camera on integer coordinates): worst depth error 0.001 blocks. CPU vs Vulkan: 0.000 % hit/miss mismatches, 0.003 % colour outliers. |
| Streaming | Rays step exactly across cells no LOD has loaded and still hit land behind them (streaming-gap and four-zone tests). Finer LODs stand in while coarse ones are missing. |
| Java 25 FFM | ABI handshake plus a 13 ns critical downcall. A tile authored in Java renders correctly (`native/java/run_probe.ps1`). |
| GL bridge | A hidden-window GL 4.6 context imports the shared images. After 30 frames the readback is byte-identical to the host render, clean under the Khronos validation layer. CPU cost per frame is the previous frame's fence wait (single-buffered for now). |

Run everything with `.\native\build.ps1` (builds, then runs `aether_tests` and `aether_gl_test`). Offline views:
`native\build\Release\aether_render.exe` (see the native README); the debug views are `flags=lod` and
`flags=dbgweather`.

## 3. How it joins the mod (after the NeoForge 26.3 conversion lands)

The conversion is in progress in the main tree. Nothing here touches `mod/` until it is committed. Then:

1. **Package `com.skycraft.aether`** (client only). It holds `AetherNative` as is, plus a loader that unpacks
   `aether_core.dll` from the jar into the game directory and checks the ABI. If the library or a suitable GPU is
   missing, it falls back to the current renderer with no hard dependency.
2. **Far-field data provider.** Background workers sample the real height path (TamrielHeight's column function,
   ported to 26.3's `DensitySampler`) at each LOD's spacing. Surface materials come from the biome's material rules
   via a block-to-palette colour table, and canopy height from the scatter plans. Tiles stream in
   nearest-first; `aether_update(budgetMs)` runs off the render thread. Loaded chunks push sections, and block edits
   go through `aether_notify_block`.
3. **Weather.** `WeatherFieldTexture` already builds the four RGBA8 fields; the same arrays go to
   `aether_set_weather`. The noise atlas goes to `aether_set_cloud_noise` once.
4. **Render hook.** On the render thread, after the near-field terrain pass, call `aether_gl_render` with the camera
   and the near-field end distance. This needs 26.3's OpenGL backend. Vanilla 26.2+ can also run on Vulkan
   (`Options#preferredGraphicsBackend`); Iris packs use GL, so GL is the primary path. A Vulkan↔Vulkan path using
   the same exported memory is future work.
5. **Skycraft shader pack (owned, replacing the Complementary patch).** Gbuffer programs apply the curvature drop
   and tilt normals. A deferred composite merges near and far by distance, using aether's colour, normal and
   material class, and the R32F view-distance texture. One tonemapper and fog model covers both. The shared
   textures reach the pack the way Skycraft's weather textures do today: as dynamic textures at fixed resource
   locations, referenced with `customTexture.*` in `shaders.properties`.
6. **Far actors.** The server sends a low-rate packet with players and large creatures beyond tracking range.
   aether draws them as instanced impostors, depth-composited into the far field. Dropped items and small mobs
   stay in the near field; past about 100 m they are under a pixel.

## 4. Departures from the master plan, and why

* **No native terrain generator or chunk "hydrator".** Skycraft's terrain is plan-driven: baked roads, town pads,
  caves, hydrology and seeded noise. A second implementation in Slang would drift from the authoritative Java one
  (docs/GPU-WORLDGEN-PLAN.md, point 3). aether consumes Java's output instead. The bimodal idea (00 air / 01 solid /
  10 boundary) lives on as the SVDAG builder's interval classification.
* **No hardware SER / `ReorderThread`.** Shader execution reordering applies to ray-tracing pipelines; aether is a
  compute kernel. The RTX 3090 (Ampere) also has no SER hardware. Coherence comes from LOD-banded segments and
  empty-space skipping.
* **26.3 worldgen API.** 26.3 split `DensityFunction` into a registered function plus a compiled `DensitySampler`
  (`sampleValue`/`sampleVolume`), and replaced `SurfaceRules` with material rules. That is the conversion's
  job; the provider in step 2 follows it. `minecraft:slice`, `chunk_surface_level` and the ore-vein material rule
  were not found in the primer excerpt checked, so nothing here relies on them.
* **Weather stays CPU-authoritative.** `WeatherPhysics` also serves server logic, so aether renders its fields and
  does not simulate them.
* **Split Radiance Cascades** (arXiv 2607.20384) for underground GI is a later phase. The far field is mostly
  surface; SRC needs the near-field voxel overlay first.

## 5. Next

1. Double-buffer the GL path's frame resources, so the CPU never waits on the previous frame.
2. Device-local scene buffers with incremental region uploads, replacing a full repack on every change.
3. A near-field depth import plus Hi-Z tile culling (skip rays the near field already covers, underground win).
4. Far-actor impostors; then the shader pack composite; then mod integration (section 3) once the conversion is in.
5. Owner checks in game: the near/far seam at the hand-off distance, curvature strength (planet radius), cloud
   decks against the current shader clouds, and frame time on the 3090.
