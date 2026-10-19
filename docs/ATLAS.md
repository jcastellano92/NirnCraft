# Infinite ATLAS: NirnCraft's native far-field renderer

NirnCraft is powered by Infinite ATLAS: the native library `atlas_core` and the `InfiniteATLAS` Iris shader pack.
Status date: 2026-10-18. Native code lives in [`native/`](../native/README.md), the Java side in
`mod/src/main/java/com/nirncraft/atlas`, the shader pack in [`shaderpack/InfiniteATLAS`](../shaderpack/InfiniteATLAS).
Tests (native suites, offline `lab` renders, live scenes) run through the harness
([tools/harness/README.md](../tools/harness/README.md)). This doc covers
what atlas is, what is built and measured, how it joins the NeoForge 26.3 mod, and where it departs from the
original master plan and why.

## 1. What it is

Everything past the rasterised near field is drawn by `atlas_core.dll`, a native library with a plain C ABI
(version 0.3):

* **Distant terrain.** A clipmap of 256³-voxel regions at seven LODs (1 m to 64 m voxels, ±512 m to ±32 km). Each
  region is a geometry SVDAG with 64-bit 4³ leaf bricks, y-major ordering and an RLE material stream, after Aokana
  (2025).
* **Curved planet.** The view frame stays flat. A point at horizontal distance d stands at world altitude
  y + d²/2R, so rays are walked as parabolic chords. The pack's vertex shaders apply y' = y − d²/2R to the near
  field, so the two meet seamlessly.
* **Cloud shell.** A line-for-line port of NirnCraft's cloud model: parcels, buds, anvil profile, overcast floor,
  and the middle and ice layers. It reads `WeatherFieldTexture`'s four fields and the baked noise volume, measured
  above the curved datum, so decks wrap over the horizon. Cloud shadows land on terrain kilometres away.
* **Far actors.** Players, creatures, dragons and ships beyond entity tracking are drawn as analytic impostors,
  depth-tested and standing on the curved ground.
* **One kernel, two builds.** `shaders/atlas_far.slang` compiles to SPIR-V (Vulkan) and to C++ (the CPU
  reference). The tests compare the two builds.
* **Zero-copy GL bridge.** Images allocated in Vulkan are imported into the game's OpenGL context through
  EXT_memory_object_win32, with two frame slots and shared semaphores. Near-field depth is read straight from the
  bound framebuffer (the shader pack's gbuffer target), so covered pixels skip the far march. Finished frames are
  copied into caller-owned textures that the pack samples through `customTexture`.
* **Java 25 boundary.** `com.nirncraft.atlas.AtlasNative` binds the ABI with `java.lang.foreign`. It needs no JNI
  and no Minecraft types.
* **InfiniteATLAS shader pack.** NirnCraft's own Iris pack (it replaced the old patched third-party pack). The near field is
  forward lit with the same sun, sky and aerial functions as atlas (verified equal), with curvature, sun shadows
  and water. The composite merges near and far over a hand-off band, and one tonemapper serves both.

Authority is unchanged. The Java generator and loaded chunks stay the truth and push data in; atlas never invents
gameplay terrain. The seed-free raster source (for multiplayer clients, which do not know the seed, and for the
harness) is labelled "preview" everywhere.

## 2. Built and measured (RTX 3090, driver 616.56, Windows 11)

| Piece | Evidence |
| --- | --- |
| SVDAG | Round trips over empty, full, sphere, terrain and random volumes have 0 mismatches. Y-major order halved terrain material memory (73 → 36 MB for the 250-region clipmap). |
| GPU cost (game path) | `atlas_bench`, 2560×1440, RTX 3090, interleaved medians: terrain 3.1 ms, with sun shadows 3.6 ms; clouds add about 1.6–2.4 ms at half resolution (temporal: one fresh ray per 2×2 block per frame, the rest reprojected; before that they added 4–6 ms). Absolute numbers swing ±1 ms with the 3090's clocks; the difference between sets is the stable figure. In game, near-field depth also removes the pixels the near field covers. Reached through a per-region max-height mip, device-local scene and outputs (the BAR heap is 214 MiB without Resizable BAR), a separate half/quarter-resolution cloud pass bounded by the last frame's surfaces and amortised over four frames (threads dealt out by quadrant so warps never mix marching and copying), and 6-tap normals. |
| Ray correctness | Flat plane and four grazing directions: 0.001-block depth error. Streaming-gap and four-zone rays step exactly across unloaded cells and pin a finer LOD while coarse data is missing. The region walk steps its cell index across the face it leaves (re-flooring a position a hair past the face is below float precision in 256-voxel table units and re-traced a region from outside: a dark seam one voxel under every region floor seen from above); the vertical-seam test checks slopes across region floors and ceilings both ways. |
| CPU ≡ Vulkan | Preview Tamriel: 0.000 % hit/miss mismatch, 0.003 % colour outliers. |
| Near-depth skip | Half the screen covered → about half the ray steps (185k → 93k). Covered pixels come back empty, the rest bit-identical. |
| Far actors | A humanoid's capsule front is hit at 199.649 against an analytic 199.65, tagged with actor class 4. |
| GL bridge | Readbacks match the host render (HDR colour to half precision, depth exactly) through shared images, GL-written near depth, a real GL depth texture, the bound-framebuffer path and caller copies. Clean under the Khronos validation layer. |
| Streaming | 240 frames at 300 blocks/s with a worker rebuilding the clipmap about 140 times: render-thread CPU 0.15 ms median, about 1 ms p99. Scenes are staged off the render thread into a GPU copy no frame reads. |
| Java 25 FFM | ABI handshake plus a 13 ns critical downcall. A Java-authored tile renders. The raster cache built in Java is byte-identical to the Python exporter (33.7 MB). |
| Shader pack | All 36 program stages compile (glslang). The pack's GLSL sky matches atlas's Slang sky to about 1e-6 relative at three sun angles. |
| Mod glue | `native/java/mod-integration` compiles against the real Minecraft 26.3 client and NeoForge 26.3.0.90 jars. |

Run everything with `.\native\build.ps1` (builds, then runs `atlas_tests`, `atlas_height_test`, `atlas_gl_test`
zero-copy and forced host-copy, and `atlas_pack_test`; `-Validate` under the validation layers; Linux:
`native/build_linux.sh`). `atlas_gl_test --context=both` (what both scripts run) repeats the whole GL bridge on a
second context, an OpenGL 3.3 core forward-compatible profile like the one Minecraft creates, and fails unless it
lands on the same interop path as the default context (NVIDIA hands out exactly 3.3 there, so a 4.5-only call or an
extension the driver drops from that profile shows up here, not in game),
`.\native\java\run_probe.ps1`, `.\native\java\mod-integration\compile_check.ps1` and
`py -3 -I shaderpack\build_pack.py`. For offline views use `native\build\Release\atlas_render.exe` (flags `lod`,
`dbgweather`, `actors=demo`, `weather=fair|storm|overcast`).

### Portability (Windows / Linux, NVIDIA / AMD / Intel / Mesa)

One code path; the platform differences live in a few shims:

| Concern | Windows | Linux |
| --- | --- | --- |
| Library | `atlas_core.dll` | `libatlas_core.so` (glibc 2.27+, libstdc++ linked in, only `atlas_*` exported via `cmake/atlas_core.map`, stripped Release; ~1.6 MB) |
| Vulkan loader | `vulkan-1.dll`, opened at run time | `libvulkan.so.1`, opened at run time (`src/vk_loader.*`): no link-time Vulkan dependency |
| GL entry points | `wglGetProcAddress` | `eglGetProcAddress` or `glXGetProcAddressARB`, whichever owns the current context (GLFW: EGL on Wayland, GLX on X11 / XWayland) |
| Shared memory / semaphores | `VK_KHR_external_{memory,semaphore}_win32` ↔ `GL_EXT_{memory_object,semaphore}_win32` | `..._fd` ↔ `GL_EXT_..._fd` (the fd goes to GL on import) |
| Build | `native\build.ps1` (MSVC) | `native/build_linux.sh` (GCC 11+ / Clang 14+, CMake 3.20+; fetches pinned Slang and, if missing, Vulkan-Headers; `spirv-val` optional). The shipped `.so` is built with conda-forge's `x86_64-conda-linux-gnu-g++` (glibc 2.28 sysroot) and copied to `native/build-linux/` for `move_in.ps1` |

**Interop is chosen per attach**, and `atlas_info` (ABI 0.8) says which and why: zero-copy needs the Vulkan handle
extensions, every shared format (RGBA16F, RGBA8, R32F, RG32F, optimal tiling, storage + sampled + transfer) exportable
per `vkGetPhysicalDeviceImageFormatProperties2`, the GL memory-object + semaphore extensions, and the GL context's
device and driver UUIDs equal to Vulkan's. Exported images are always dedicated allocations (AMD needs it for the
tiling metadata; GL is told the same) in GL_OPTIMAL_TILING_EXT. Anything missing → **host copies**: the same publish
pass into Vulkan-local images, a copy to host memory, `glTextureSubImage2D` into plain GL textures (the caller's
unpack state is saved and restored). Host copies match zero-copy bit for bit; the near-depth skip is off there.
`ATLAS_INTEROP=hostcopy|zerocopy` forces a mode; `ATLAS_HOSTCOPY_LATENCY=1` uploads the previous frame instead of
waiting (no CPU wait, one frame of far-field lag). **Device choice:** `atlas_create` on the render thread picks the
Vulkan device whose UUID the current GL context reports (hybrid laptops; a Deck or Fedora box with lavapipe
installed beside RADV), else discrete > integrated > virtual > CPU; `ATLAS_VK_DEVICE=<index|name>` overrides.
Devices below the baseline (Vulkan 1.2, `shaderInt64`, 24 storage buffers per stage (23 before ABI 0.14 added the groups), 8×8 work groups) are listed
with the reason; `atlas_last_error(NULL)` reports a failed create.

**Baseline:** the SPIR-V is 1.5 with only the `Shader` and `Int64` capabilities (spirv-val clean for vulkan1.2; no
subgroup ops), so Vulkan 1.2 + `shaderInt64` covers Pascal (GTX 10xx), Polaris+, RDNA, the Deck, Intel Gen9+.

**Host-copy cost** (RTX 3090, `atlas_bench`, back-to-back frames; zero-copy for comparison):

| | GPU per frame | CPU per `atlas_gl_render` |
| --- | --- | --- |
| 1920×1080 zero-copy | 1.35–2.5 ms | (GPU-bound bench; in game: recording only, ~0.2–0.7 ms) |
| 1920×1080 host-copy | 5.7–7.0 ms (+~4.3 ms readback over PCIe) | 10.5–11.9 ms (8.0 ms GPU wait + 4.2 ms upload) |
| 2560×1440 host-copy | 9.5–11.6 ms | 17.6–19.8 ms (11.0 ms wait + 7.7 ms upload) |
| 960×540 host-copy (gl test) | 1.5–1.9 ms | 3.6 ms (1.7 wait + 1.1 upload) |

So the fallback works everywhere but costs a 1080p game roughly 10 ms of render-thread time: adaptive quality's
cost model (docs/ADAPTIVE-QUALITY.md) sees it in atlas's measured time and drops atlas's resolution there. Integrated GPUs share memory, so their copy is cheaper, and they have
zero-copy anyway (Mesa RADV/radeonsi, ANV/iris, NVIDIA all ship the fd extensions).

**Conformance runs** (2026-10-18): NVIDIA RTX 3090 (zero-copy and forced host-copy) and Intel UHD 630 (Vulkan; GL
stayed on the 3090 → cross-GPU host-copy, chosen by the UUID mismatch) under Khronos validation + synchronization
validation: clean, every suite passes. Mesa 26.2.4 lavapipe + llvmpipe on Windows (validation on) and Mesa 25.0.7
lavapipe + llvmpipe in WSL Ubuntu 24.04 (GCC 13 and Clang 18 builds; EGL surfaceless and GLX contexts): every suite
passes on the host-copy path (lavapipe lacks `VK_KHR_external_semaphore_fd` / `_win32` memory export, llvmpipe has no
memory objects, so zero-copy over fds still needs real hardware: the checklist in section 5). Found and fixed on the
way: the sun map readback buffer leaked (validation), the GL-ready semaphore wait and the acquire barrier lacked the
transfer stage the sun map copy runs in (a real race, synchronization validation), two sun map copies wrote the same
image without a barrier, and the sun map's parameter row was read uninitialised (garbage on lavapipe; NVIDIA happened
to zero it).

Re-run after the exact-height merge (ABI 0.9, 2026-10-18): Windows RTX 3090 every suite, the GL bridge zero-copy on
both the default context (4.6 compatibility) and a 3.3 core forward-compatible one (NVIDIA reports `3.3.0`), and
both host-copy when forced. WSL Ubuntu 24.04, Mesa 25.0.7 lavapipe + llvmpipe, GCC 13.4 (glibc 2.28 sysroot) and
Clang 18: every suite, EGL and GLX, both contexts on host-copy (llvmpipe hands out 4.5 core for the 3.3 request);
lavapipe has shaderFloat64 + the RTE / SignedZeroInfNanPreserve float controls, so the exact-height kernel runs on it
and matches its C++ reference bit for bit; with no Vulkan driver at all the height test reports the CPU path and
passes. Linux build fixes from the merge: `spirv-val` was looked up only in the Windows SDK's `Bin` (now the SDK or
PATH, optional on Linux), and the height reference's MSVC-only `/fp:strict` got its GCC / Clang equivalent.

## 3. How it joins the mod

The NeoForge 26.3 mod (Sodium 0.9.3, Iris 1.11.7, no Distant Horizons) carries atlas in `com.nirncraft.atlas` with
the natives under `natives/<platform>/`. The classes were staged in
[`native/java/mod-integration`](../native/java/mod-integration/README.md) and moved in as follows:

1. `move_in.ps1` (dry run by default, `-Apply` to write) copies `com.nirncraft.atlas` and `atlas_core.dll`
   (`natives/windows-x86_64/`) into the mod and makes the hooks: `AtlasServer.init()` and
   `AtlasClient.init(modBus)` in `NirnCraft.java`, `AtlasNet.register()` in `SkyNetwork.init`, and
   `WeatherFieldTexture.copyForAtlas` (the clouds' weather feed). `sandbox_check.ps1` rehearses exactly that on a
   copy of the main tree's sources and compiles the whole mod against the conversion's patched 26.3 jar.
2. Rendering: `AtlasClient` hooks `RenderLevelStageEvent.AfterOpaqueBlocks`, builds the frame from
   `CameraRenderState`, reads the near field's depth from the bound target (`ATLAS_GL_BOUND_DEPTH`) and copies the
   result into `nirncraft:atlas/color` / `nirncraft:atlas/depth`, the pack's `customTexture`s (Iris resolves them
   through the TextureManager: no Iris internals or mixins). Other dimensions blank them.
3. Terrain: single player evaluates the server's own `tamriel_height` (exact); a multiplayer server streams the same
   tiles (bit-identical, cached on both ends); either way the seed-free raster stand-in fills the window first.
   Grass and foliage wear the biome's colours.
4. On top: every planned settlement (StreetPlan) and fort as building massing, and far actors from the server.
5. Ship `shaderpack/build/InfiniteATLAS.zip` in the pack and select it in `iris.properties`. Keep `PLANET_RADIUS`
   and `sunPathRotation` equal on both sides (`AtlasClient.planetRadius` / `sunPathRotationDeg`).

## 4. Departures from the master plan, and why

* **No native terrain generator or chunk "hydrator".** NirnCraft's terrain is plan-driven: roads, town pads, caves,
  hydrology and seeded noise. A second implementation would drift from the authoritative Java one
  (docs/GPU-WORLDGEN-PLAN.md, point 3). atlas consumes Java's output instead. The bimodal 00/01/10 idea lives on
  as the SVDAG builder's interval classification.
* **No hardware SER / `ReorderThread`.** Shader execution reordering applies to ray-tracing pipelines; atlas is a
  compute kernel. The RTX 3090 (Ampere) also has no SER hardware.
* **26.3 worldgen API.** 26.3 split `DensityFunction` into a registered function plus a compiled `DensitySampler`
  (`sampleValue`/`sampleVolume`), and turned `SurfaceRules` into material rules. That is the conversion's job.
  `minecraft:slice`, `chunk_surface_level` and an ore-vein material rule were not found in the primer excerpt
  checked, so nothing here relies on them.
* **Renderer backends.** Vanilla 26.2+ also has a Vulkan backend (`Options#preferredGraphicsBackend`). Iris packs
  run on GL, so the GL bridge is the primary path. A Vulkan↔Vulkan path over the same exported memory is future
  work.
* **Weather stays CPU-authoritative.** `WeatherPhysics` also serves server logic, so atlas renders its fields and
  does not simulate them.
* **Split Radiance Cascades** (arXiv 2607.20384) for underground GI is a later phase. It needs the near-field voxel
  overlay first.

## 5. World content in the far field (ABI 0.10, tile codec 3; branch atlas-far2, 2026-10-18)

What the far field now shows is what the near field will build, from the mod's own data and builders:

* **Landmarks.** A settlement landmark is captured from its real builder: `AtlasCapture` runs `Landmarks.build` on a
  `Canvas` (framed and seeded exactly as `SettlementPiece` frames it) over a `WorldGenLevel` proxy whose ground is the
  far field's, and keeps per world column the span the builder made above the ground: its top, the bottom of the run
  reaching it (gaps under 10 blocks merged, so hollow towers stay solid while an arch's deck, a tree's crown or a
  floating rock keeps the air under it, `atlas_upload_column_structures2`), the top block and the column's wall
  block. The White-Gold Tower's tapering shaft and gold needle, the College on its sea stack, Solitude's arch, Elden
  Root's tree: 325 of 325 landmarks around the Imperial City capture (about 9 ms each, once, on the far-field
  worker); one that cannot run falls back to the old profile box, one that builds nothing above the ground (a sea
  stack the terrain's pad already is) adds nothing. Silhouettes are max-pooled into far columns, so a needle keeps
  its column out to the horizon. The named wild sites (`AtlasSites`: forts, watchtowers, lighthouses, mage towers,
  great lifts, dragon lairs, groves, camps, prince shrines and the two great statues, standing stones, word walls,
  High Hrothgar, the Throat of the World, Sky Haven, Labyrinthian, every built world wonder, dungeon entrances) are
  drawn from their builders' dimensions, placed like DungeonStructure (nudged off water) and seed-free: 3868 in a
  40 km window. The seeded natural landmarks (`AtlasNatural`: colossal trees, spires, ribcages, stone arches,
  mesas, menhirs, hoodoos ...) need the world seed, so the server enumerates them around each player (XL to 16 km,
  the tall L kinds to 6 km, again after 1.5 km of travel; packet `FarSites`, the integrated server too) and the
  client draws them from their builders' dimensions. Block colours: `AtlasBlockColors`, the linear-light mean of
  each block's top texture (`native/tools/export_block_colors.py`).
* **Surface blocks.** `AtlasSurface` evaluates the overworld's own material rule per far column (the per-biome
  presets, the seeded surface noises and depth, the biomes through the seeded BiomeManager zoom, vanilla's
  chunk-clamped steepness) at the exact top block, and freeze_top_layer's snow and ice where the biome is cold
  enough there. 26.3 builds its rule context only per chunk, so it is driven reflectively (falls back to the raster
  dressing with one log line if that fails). Coarse LODs share one evaluation per 2 x 2 group (shores re-evaluated).
  The blocks travel in the tile (`AtlasTileCodec` v3: a block table and top / under / flags planes), so server-fed
  far fields stay bit-identical to single player; `atlas_upload_column_heights3` takes them (snow makes forest
  crowns snowy, frozen water renders as ice). Cost on vanilla's multi-noise biomes (`SurfaceHarness`): about
  45 us per column per thread at LOD 0 and 22 at coarser LODs, once per tile (cached on disk).
* **Far lights** (`atlas_set_light_group`, `atlas_set_light_budget`): each settlement's lit windows (one light per
  facade, as bright as its windows), street lamps, braziers, wall lanterns, gate torches, camp fires and its
  landmarks' light blocks; each site's (lighthouse lamps, shrine braziers, camp fires); the Red Mountain citadel's
  lava moat and the caldera's glow above the rim. Per frame the lights beyond the near field are culled, merged into
  16-block clusters beyond 6 km, ranked with a cap of 64 per 32 px screen tile (a near town is never crowded out by
  far ones) and binned; the far kernel adds a core at least a pixel wide and a halo that widens with the haze, unless
  something nearer covers the light. Towns light up from dusk to dawn, fires flicker, lava pulses, spray is sunlit.
  Budget 4096 a frame, falling with atlas's LOD knob to 1024 (512 at fallback tier 1). Cost (atlas_bench,
  2560x1440, 8000 town lights): +0.08 ms GPU, about 0.1 ms CPU.
* **Water.** River columns get a flowing-water material (8 headings x calm / fast / rapids from the raster's channel
  direction and the water levels downstream); the kernel carries the wave field downstream and breaks fast runs and
  rapids into white-water streaks. Hydrology's 511 falls stand as falling-water sheets across the channel below the
  lip (`ATLAS_CLASS_FALLS`: white, streaming down) with sunlit spray at the pool (`ATLAS_LIGHT_MIST`).
* **Transitions.** `atlas_set_transitions(0.6 s)`: a region rebuilt over a resident one (an exact tile replacing
  the stand-in, buildings arriving, a finer LOD entering the window over the coarser one) keeps the old one packed
  and dissolves into the new per pixel; `ATLAS_FRAME_LOD_DITHER` gives each pixel its own LOD segment phase, so a
  level change is a stipple band instead of a line. Both screen-stable without TAA; with the pack's TAA (ABI 0.13)
  they move per frame and the resolve averages them into a smooth fade.
* **Far actors.** The dragon impostor has a neck, head, tail and hinged two-part wings beating at 0.8 Hz (gliding
  when descending, folded on the ground: `AtlasActor.flags`); the packet carries the entity id, and an impostor the
  client also tracks dissolves out over 0.6 s (the server now sends actors from 32 blocks inside the tracking range).

ABI 0.10 (minor bump; everything 0.9 still works): `atlas_set_transitions`, `atlas_set_light_group`,
`atlas_set_light_budget`, `atlas_upload_column_heights3`, `atlas_upload_column_structures2`; `AtlasActor.flags`
(in the old padding), `AtlasLight`, `ATLAS_FRAME_LOD_DITHER`, `ATLAS_CLASS_FALLS`, `ATLAS_COLUMN_*`. Network:
`FarActors` gains id/flags, new `FarSites`; tile codec 3 (caches re-key: `Hello` carries the codec version).

ABI 0.12 (2026-10-19): `atlas_set_moon` (the pack's lunar key lights the far night too), `ATLAS_FRAME_FILL_NEAR`
(where the near field drew nothing in the open, the far field fills from 2 m). ABI 0.13: `atlas_set_jitter` (primary
rays offset by the pack's TAA sequence; `AtlasClient` sets it only while Infinite ATLAS runs with its TAA option on,
read from Iris' option file; the cloud history reprojects unjittered, and the screen-fixed dithers then move per
frame for the resolve). Both optional: a 0.11 core still loads. The mod's data texture `smoke_sources.png` row 0
texel 17 = the hand-off band / 64 (the pack's crossfade), texel 18 = the interior (LocationKind.icon + 1, eased
strength) for the pack's interior grade.

Real far trees (`AtlasTrees`, single player, LOD 0): each chunk's tree features run as chunk decoration runs them
(the generator's global feature indices and decoration seeds, `FeaturePlacer`), into `AtlasCapture`'s level over the
exact ground with an `Env` (the material rules' surface block for the soil check, the map's water, the generator's
biomes, the real level for the road / site masks). The crowns and trunks are stamped with the buildings (leaves in
the biome's foliage colour, birch and spruce their fixed ones); those tiles carry `ATLAS_COLUMN_NO_CANOPY` (ABI 0.13)
so the dressing grows no noise canopy under them. Coarser LODs keep the dressing's canopy.

Offline checks: `native\build.ps1` (new tests: fades, LOD dither, dragon motion and fades, far lights day/night and
occlusion, surface overrides and frozen water, lights in the CPU/Vulkan parity scene);
`run_settlement_harness.ps1 -Sandbox <dir> -Mod <worktree>\mod [-Rebuild] -Place imperial_city | at:x,z | xl:n,x,z`
(day, mid, mid without buildings, night; capture / site / light counts and costs; `-Dharness.overhead=true`);
`run_settlement_harness.ps1 -Main SurfaceHarness` (the surface painter on vanilla's overworld); `run_exact_harness.ps1`
(now built against the sandbox build of the worktree's mod: exact terrain, flowing rivers, server-fed = single
player); `atlas_bench lights=8000`; `atlas_render actors=demo dragon=near time=<s>`.

## 6. Next

1. Done: atlas is moved into `mod/` and the pack ships as `pack/shaderpacks/InfiniteATLAS.zip`; the owner's checks
   below remain the visual sign-off.
2. Later: clouds lit from below over Red Mountain; dungeon surfaces captured from their own builders like the
   settlement landmarks (they need a DungeonPiece, so the parametric silhouettes stand in); lit windows on landmarks
   (only their light blocks glow now); SRC GI underground, a Vulkan-to-Vulkan path for the vanilla Vulkan backend.

### Owner checks in game

Start single player in a Tamriel world with the InfiniteATLAS pack, render distance 16-24 chunks:

- **Hand-off seam** at the edge of the render distance: no gap, no double terrain, no colour or brightness jump
  between the rasterised near field and the far field (the band is `AtlasClient.handoffBand`, 24 blocks).
- **Horizon:** terrain to 30-40 km, curving away (planet radius `AtlasClient.planetRadius`); haze and sky like the
  near field's; nothing popping as you fly (the stand-in fills the window at once, exact tiles replace it within a
  few seconds, nearest first).
- **Settlements from afar:** Whiterun's wall ring, towers and Dragonsreach from the plains; Solitude, Markarth,
  the Imperial City from across their valleys; forts as small grey squares with towers.
- **Clouds and weather:** the cloud decks, storms and rain shafts move with the weather you see near you.
- **Night:** moonlight on the far field like the near field's.
- **Other players** (LAN or a server) beyond entity range: impostors where they are, moving smoothly.
- **Multiplayer:** on a dedicated server the far field fills in the same (log line `atlas far field on (server
  columns)`); rejoining the same world is instant (tile cache).
- **Frame time** on the 3090 at 1440p: atlas costs about 4-6 ms with clouds; F3 shows the rest as before.
- **Imperial City / White-Gold Tower from far** (e.g. from the Rumare shore 2-3 km out, and flying in): the tower's
  tapering shaft and gold needle, not a rounded stand-in; no pop when it enters the near field. Then Solitude's arch
  over the water, the College on its stack, a fort / watchtower / lighthouse along a road: each the shape it becomes.
- **A town at night from a hill** (1-3 km): lit windows, lamps along the streets, wall lanterns; towns behind the
  hills glow over them; nothing lit by day (fires barely).
- **Red Mountain at night** from Balmora / the Ascadian Isles: a red glow over the caldera rim, the citadel's moat.
- **A river valley with falls** (e.g. near Markarth): rivers continuous, white water on rapids moving downstream, the
  falls a white sheet with spray at the pool; near/far water joins at the hand-off.
- **Flying a dragon in** (or watching one): wings beating at a distance; no double dragon and no gap as it crosses the
  tracking range (the impostor dissolves out as the real one appears).
- **Surface blocks:** snow, rock bands, sand, podzol and ash in the far field matching the near field at the
  hand-off; the log line `atlas surface check: N columns, top block matches P%` two minutes in (expect 85 %+; it
  only compares columns where the ground is where the far field puts it). If instead the log says
  `far surface blocks from the raster dressing (...)`, the reflective rule driver failed: report the reason.
- **No pops:** flying fast, refined tiles, new buildings and finer LODs dissolve in (0.6 s); LOD changes far out are
  a fine stipple band, not a line.
- **Fallbacks:** in the Nether and End the far field is off; without the library or a usable Vulkan device the
  game runs as before with one log line; on a GL context atlas cannot share memory with, the far field still
  draws through host copies (the `atlas: {...}` log line says `"interop":"host-copy"` and why).

### Hardware still to test (portability)

Run `native/build_linux.sh` (Linux) or `native\build.ps1 -Validate` (Windows) on each, then the mod once, and keep the
`atlas: {...}` log line. Expected interop in brackets.

- **Steam Deck** (RADV + radeonsi, SteamOS 3.6+): build in a distrobox (`build_linux.sh` header), Desktop Mode:
  `build_linux.sh` then `build_linux.sh --validate` and `--glx`. [zero-copy (opaque fds)]. Check `pickedForGlContext`
  true with lavapipe also installed, `dedicatedOnly`, frame time at 1280×800 and the dynamic scale it settles on.
  Then Game Mode (gamescope): GLFW may run on XWayland (GLX) or Wayland (EGL): the log's `winsys`.
- **Bazzite / Fedora desktop, AMD** (RADV + radeonsi): the same three runs; then the mod under both a Wayland and an
  X11 session. [zero-copy (opaque fds)]
- **Linux, NVIDIA proprietary** (Bazzite-nvidia or similar): the three runs. [zero-copy (opaque fds); GL and Vulkan
  driver UUIDs must match, if not: `interopReason` names both, retry with `ATLAS_INTEROP=zerocopy`]
- **Windows, AMD** (Adrenalin): `build.ps1 -Validate`. [zero-copy (win32) if the GL driver exposes
  `GL_EXT_memory_object_win32` + `GL_EXT_semaphore_win32`, else host-copy with the reason]. Watch for a black or
  garbled far field in zero-copy (tiling metadata): if so, report `dedicatedOnly` and force `ATLAS_INTEROP=hostcopy`.
- **Windows, GTX 1080 (Pascal)**: `build.ps1 -Validate` and `atlas_bench w=1920 h=1080`. [zero-copy (win32)]. Pascal
  runs 64-bit integer ops at low rate: compare the bench to the 3090's per-set ratios.
- **A hybrid laptop** (iGPU + dGPU): run the game on each GPU; the log must show the matching device chosen
  (`pickedForGlContext` true) and zero-copy, not a cross-GPU host-copy.

## Testing in the real game (2026-10-18)

Single player: use the harness ([tools/harness/README.md](../tools/harness/README.md)). `run --suite seam-core`
covers the near/far hand-off, `run --tag suite:distance` the distance ladders, pop sweeps and fidelity pairs, `watch`
rebuilds and reloads the pack on every edit with before|after sheets, and `probe NAME --visit x,z,height,yaw,pitch`
looks at one spot. It launches the disposable preview on a kept test world, installs a newer core with `--rebuild` and
closes the client at the end. Captures wait for the render to settle: the chunks in the camera's view built and lit,
Infinite ATLAS idle for the tiles in the view cone (`AtlasClient.farFieldIdle()`), and a steady picture.
`shaderpack/tools/pack_live.py` (the pack-only loop the harness grew from) is kept for low-level debugging only.

Multiplayer over TCP (full-pack dedicated server + the full-pack client):

```powershell
py -3 -I tools/build_server_instance.py --core-jar <jar> --java <java25> --work-root <dir> --cache <dir> --output-zip <server.zip>
py -3 tools/build_prism_instance.py --core-jar <jar>
py -3 tools/dev/mp_server.py setup --zip <server.zip>; py -3 tools/dev/mp_server.py start
tools\qa\prism_autotest.ps1 -PrepareOnly -Live -SourceZip dist\NirnCraft-26.3-client-instance.zip -Server 127.0.0.1:25790 -Java <java25>
py -3 tools/dev/live_world.py launch      # status: remoteConnected
py -3 tools/dev/mp_server.py cmd "op <player>" "tp <player> x y z"   # live controls need op in remote mode
py -3 tools/dev/live_world.py actions <file with: settle 1800 / wait 300 / capture TAG>
py -3 tools/dev/mp_server.py stop
```

Evidence lines: server `atlas tile service: first request from <player>` and `N tiles (X MB) sent`, client
`atlas: asking the server for far tiles` and `N far tiles received`. First run: 114 tiles / 1.6 MB in a minute; the
client caches them under `.minecraft/atlas-cache/<world>` (revisits are instant and ask nothing).
