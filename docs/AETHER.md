# Aether: Skycraft's native far-field renderer

Status date: 2026-10-18. Branch `aether-renderer` (worktree `.claude/worktrees/aether`). Code lives in
[`native/`](../native/README.md); the shader pack in [`shaderpack/`](../shaderpack/SkycraftAether). This doc covers
what aether is, what is built and measured, how it joins the NeoForge 26.3 mod, and where it departs from the
original master plan and why.

## 1. What it is

Everything past the rasterised near field is drawn by `aether_core.dll`, a native library with a plain C ABI
(version 0.3):

* **Distant terrain.** A clipmap of 256³-voxel regions at seven LODs (1 m to 64 m voxels, ±512 m to ±32 km). Each
  region is a geometry SVDAG with 64-bit 4³ leaf bricks, y-major ordering and an RLE material stream, after Aokana
  (2025).
* **Curved planet.** The view frame stays flat. A point at horizontal distance d stands at world altitude
  y + d²/2R, so rays are walked as parabolic chords. The pack's vertex shaders apply y' = y − d²/2R to the near
  field, so the two meet seamlessly.
* **Cloud shell.** A line-for-line port of Skycraft's cloud model: parcels, buds, anvil profile, overcast floor,
  and the middle and ice layers. It reads `WeatherFieldTexture`'s four fields and the baked noise volume, measured
  above the curved datum, so decks wrap over the horizon. Cloud shadows land on terrain kilometres away.
* **Far actors.** Players, creatures, dragons and ships beyond entity tracking are drawn as analytic impostors,
  depth-tested and standing on the curved ground.
* **One kernel, two builds.** `shaders/aether_far.slang` compiles to SPIR-V (Vulkan) and to C++ (the CPU
  reference). The tests compare the two builds.
* **Zero-copy GL bridge.** Images allocated in Vulkan are imported into the game's OpenGL context through
  EXT_memory_object_win32, with two frame slots and shared semaphores. Near-field depth is read straight from the
  bound framebuffer (the shader pack's gbuffer target), so covered pixels skip the far march. Finished frames are
  copied into caller-owned textures that the pack samples through `customTexture`.
* **Java 25 boundary.** `com.skycraft.aether.AetherNative` binds the ABI with `java.lang.foreign`. It needs no JNI
  and no Minecraft types.
* **Skycraft Aether shader pack.** Skycraft's own Iris pack replaces the Complementary patch. The near field is
  forward lit with the same sun, sky and aerial functions as aether (verified equal), with curvature, sun shadows
  and water. The composite merges near and far over a hand-off band, and one tonemapper serves both.

Authority is unchanged. The Java generator and loaded chunks stay the truth and push data in; aether never invents
gameplay terrain. The seed-free raster source (for multiplayer clients, which do not know the seed, and for the
harness) is labelled "preview" everywhere.

## 2. Built and measured (RTX 3090, driver 616.56, Windows 11)

| Piece | Evidence |
| --- | --- |
| SVDAG | Round trips over empty, full, sphere, terrain and random volumes have 0 mismatches. Y-major order halved terrain material memory (73 → 36 MB for the 250-region clipmap). |
| GPU cost (game path) | `aether_bench`, 2560×1440, RTX 3090, interleaved medians: terrain 3.1 ms, with sun shadows 3.6 ms; clouds add about 1.6–2.4 ms at half resolution (temporal: one fresh ray per 2×2 block per frame, the rest reprojected; before that they added 4–6 ms). Absolute numbers swing ±1 ms with the 3090's clocks; the difference between sets is the stable figure. In game, near-field depth also removes the pixels the near field covers. Reached through a per-region max-height mip, device-local scene and outputs (the BAR heap is 214 MiB without Resizable BAR), a separate half/quarter-resolution cloud pass bounded by the last frame's surfaces and amortised over four frames (threads dealt out by quadrant so warps never mix marching and copying), and 6-tap normals. |
| Ray correctness | Flat plane and four grazing directions: 0.001-block depth error. Streaming-gap and four-zone rays step exactly across unloaded cells and pin a finer LOD while coarse data is missing. |
| CPU ≡ Vulkan | Preview Tamriel: 0.000 % hit/miss mismatch, 0.003 % colour outliers. |
| Near-depth skip | Half the screen covered → about half the ray steps (185k → 93k). Covered pixels come back empty, the rest bit-identical. |
| Far actors | A humanoid's capsule front is hit at 199.649 against an analytic 199.65, tagged with actor class 4. |
| GL bridge | Readbacks match the host render (HDR colour to half precision, depth exactly) through shared images, GL-written near depth, a real GL depth texture, the bound-framebuffer path and caller copies. Clean under the Khronos validation layer. |
| Streaming | 240 frames at 300 blocks/s with a worker rebuilding the clipmap about 140 times: render-thread CPU 0.15 ms median, about 1 ms p99. Scenes are staged off the render thread into a GPU copy no frame reads. |
| Java 25 FFM | ABI handshake plus a 13 ns critical downcall. A Java-authored tile renders. The raster cache built in Java is byte-identical to the Python exporter (33.7 MB). |
| Shader pack | All 36 program stages compile (glslang). The pack's GLSL sky matches aether's Slang sky to about 1e-6 relative at three sun angles. |
| Mod glue | `native/java/mod-integration` compiles against the real Minecraft 26.3 client and NeoForge 26.3.0.90 jars. |

Run everything with `.\native\build.ps1` (builds, then runs `aether_tests`, `aether_gl_test` and `aether_pack_test`),
`.\native\java\run_probe.ps1`, `.\native\java\mod-integration\compile_check.ps1` and
`py -3 -I shaderpack\build_pack.py`. For offline views use `native\build\Release\aether_render.exe` (flags `lod`,
`dbgweather`, `actors=demo`, `weather=fair|storm|overcast`).

## 3. How it joins the mod (after the NeoForge 26.3 conversion lands)

The other session's conversion is in progress in the main tree, which also moves the pack to Sodium 0.9.2 and Iris
1.11.7 and drops Distant Horizons. Nothing here touches `mod/` until that conversion is committed. The steps are in
[`native/java/mod-integration/README.md`](../native/java/mod-integration/README.md). In short:

1. Copy `com.skycraft.aether` into the mod and package `aether_core.dll` under `natives/windows-x86_64/`.
2. `AetherClient.init(modBus)` hooks `RenderLevelStageEvent.AfterOpaqueBlocks`. It builds the frame from
   `CameraRenderState` and passes `AETHER_GL_BOUND_DEPTH` for the near field. aether copies the frame into
   `skycraft:aether/color` and `skycraft:aether/depth`, which the pack declares as `customTexture`s. Iris resolves
   those through the TextureManager, so no Iris internals or mixins are needed.
3. Feeds: `WeatherFieldTexture` implements `AetherClient.WeatherFeed`. The far-field worker streams tiles
   nearest-first and calls `aether_update` off the render thread.
4. Column sources: v1 is the seed-free raster source, the same on multiplayer clients. The exact single-player
   source evaluates the server's `tamriel_height` through 26.3's `DensitySampler` once the conversion has ported
   it; it then switches to the block palette (`AetherPalette.blocks`). Multiplayer exactness needs a server tile
   packet (later).
5. Ship `shaderpack/build/SkycraftAether.zip` in the pack and select it in `iris.properties`. Keep `PLANET_RADIUS`
   and `sunPathRotation` equal on both sides (`AetherClient.planetRadius` / `sunPathRotationDeg`).
6. Far actors: a server packet with players and large creatures beyond tracking range feeds
   `aether_set_actors`.

## 4. Departures from the master plan, and why

* **No native terrain generator or chunk "hydrator".** Skycraft's terrain is plan-driven: roads, town pads, caves,
  hydrology and seeded noise. A second implementation would drift from the authoritative Java one
  (docs/GPU-WORLDGEN-PLAN.md, point 3). aether consumes Java's output instead. The bimodal 00/01/10 idea lives on
  as the SVDAG builder's interval classification.
* **No hardware SER / `ReorderThread`.** Shader execution reordering applies to ray-tracing pipelines; aether is a
  compute kernel. The RTX 3090 (Ampere) also has no SER hardware.
* **26.3 worldgen API.** 26.3 split `DensityFunction` into a registered function plus a compiled `DensitySampler`
  (`sampleValue`/`sampleVolume`), and turned `SurfaceRules` into material rules. That is the conversion's job.
  `minecraft:slice`, `chunk_surface_level` and an ore-vein material rule were not found in the primer excerpt
  checked, so nothing here relies on them.
* **Renderer backends.** Vanilla 26.2+ also has a Vulkan backend (`Options#preferredGraphicsBackend`). Iris packs
  run on GL, so the GL bridge is the primary path. A Vulkan↔Vulkan path over the same exported memory is future
  work.
* **Weather stays CPU-authoritative.** `WeatherPhysics` also serves server logic, so aether renders its fields and
  does not simulate them.
* **Split Radiance Cascades** (arXiv 2607.20384) for underground GI is a later phase. It needs the near-field voxel
  overlay first.

## 5. Next

1. After the conversion: move the glue in, run the game once, and watch the hand-off seam, curvature, cloud decks
   and frame time.
2. The exact single-player column source (`tamriel_height` via `DensitySampler`), block palette, server tile packet
   for multiplayer, and the far-actor packet.
3. Far terrain look: snow and forest micro-shading; SRC GI underground.
4. Owner checks in game: the near/far seam at the hand-off distance, curvature strength (planet radius), cloud
   decks against the current shader clouds, and frame time on the 3090.
