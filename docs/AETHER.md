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
| Ray correctness | Flat plane and four grazing directions: 0.001-block depth error. Streaming-gap and four-zone rays step exactly across unloaded cells and pin a finer LOD while coarse data is missing. The region walk steps its cell index across the face it leaves (re-flooring a position a hair past the face is below float precision in 256-voxel table units and re-traced a region from outside: a dark seam one voxel under every region floor seen from above); the vertical-seam test checks slopes across region floors and ceilings both ways. |
| CPU ≡ Vulkan | Preview Tamriel: 0.000 % hit/miss mismatch, 0.003 % colour outliers. |
| Near-depth skip | Half the screen covered → about half the ray steps (185k → 93k). Covered pixels come back empty, the rest bit-identical. |
| Far actors | A humanoid's capsule front is hit at 199.649 against an analytic 199.65, tagged with actor class 4. |
| GL bridge | Readbacks match the host render (HDR colour to half precision, depth exactly) through shared images, GL-written near depth, a real GL depth texture, the bound-framebuffer path and caller copies. Clean under the Khronos validation layer. |
| Streaming | 240 frames at 300 blocks/s with a worker rebuilding the clipmap about 140 times: render-thread CPU 0.15 ms median, about 1 ms p99. Scenes are staged off the render thread into a GPU copy no frame reads. |
| Java 25 FFM | ABI handshake plus a 13 ns critical downcall. A Java-authored tile renders. The raster cache built in Java is byte-identical to the Python exporter (33.7 MB). |
| Shader pack | All 36 program stages compile (glslang). The pack's GLSL sky matches aether's Slang sky to about 1e-6 relative at three sun angles. |
| Mod glue | `native/java/mod-integration` compiles against the real Minecraft 26.3 client and NeoForge 26.3.0.90 jars. |

Run everything with `.\native\build.ps1` (builds, then runs `aether_tests`, `aether_height_test`, `aether_gl_test`
zero-copy and forced host-copy, and `aether_pack_test`; `-Validate` under the validation layers; Linux:
`native/build_linux.sh`). `aether_gl_test --context=both` (what both scripts run) repeats the whole GL bridge on a
second context, an OpenGL 3.3 core forward-compatible profile like the one Minecraft creates, and fails unless it
lands on the same interop path as the default context (NVIDIA hands out exactly 3.3 there, so a 4.5-only call or an
extension the driver drops from that profile shows up here, not in game),
`.\native\java\run_probe.ps1`, `.\native\java\mod-integration\compile_check.ps1` and
`py -3 -I shaderpack\build_pack.py`. For offline views use `native\build\Release\aether_render.exe` (flags `lod`,
`dbgweather`, `actors=demo`, `weather=fair|storm|overcast`).

### Portability (Windows / Linux, NVIDIA / AMD / Intel / Mesa)

One code path; the platform differences live in a few shims:

| Concern | Windows | Linux |
| --- | --- | --- |
| Library | `aether_core.dll` | `libaether_core.so` (glibc 2.27+, libstdc++ linked in, only `aether_*` exported via `cmake/aether_core.map`, stripped Release; ~1.6 MB) |
| Vulkan loader | `vulkan-1.dll`, opened at run time | `libvulkan.so.1`, opened at run time (`src/vk_loader.*`): no link-time Vulkan dependency |
| GL entry points | `wglGetProcAddress` | `eglGetProcAddress` or `glXGetProcAddressARB`, whichever owns the current context (GLFW: EGL on Wayland, GLX on X11 / XWayland) |
| Shared memory / semaphores | `VK_KHR_external_{memory,semaphore}_win32` ↔ `GL_EXT_{memory_object,semaphore}_win32` | `..._fd` ↔ `GL_EXT_..._fd` (the fd goes to GL on import) |
| Build | `native\build.ps1` (MSVC) | `native/build_linux.sh` (GCC 11+ / Clang 14+, CMake 3.20+; fetches pinned Slang and, if missing, Vulkan-Headers; `spirv-val` optional). The shipped `.so` is built with conda-forge's `x86_64-conda-linux-gnu-g++` (glibc 2.28 sysroot) and copied to `native/build-linux/` for `move_in.ps1` |

**Interop is chosen per attach**, and `aether_info` (ABI 0.8) says which and why: zero-copy needs the Vulkan handle
extensions, every shared format (RGBA16F, RGBA8, R32F, RG32F, optimal tiling, storage + sampled + transfer) exportable
per `vkGetPhysicalDeviceImageFormatProperties2`, the GL memory-object + semaphore extensions, and the GL context's
device and driver UUIDs equal to Vulkan's. Exported images are always dedicated allocations (AMD needs it for the
tiling metadata; GL is told the same) in GL_OPTIMAL_TILING_EXT. Anything missing → **host copies**: the same publish
pass into Vulkan-local images, a copy to host memory, `glTextureSubImage2D` into plain GL textures (the caller's
unpack state is saved and restored). Host copies match zero-copy bit for bit; the near-depth skip is off there.
`AETHER_INTEROP=hostcopy|zerocopy` forces a mode; `AETHER_HOSTCOPY_LATENCY=1` uploads the previous frame instead of
waiting (no CPU wait, one frame of far-field lag). **Device choice:** `aether_create` on the render thread picks the
Vulkan device whose UUID the current GL context reports (hybrid laptops; a Deck or Fedora box with lavapipe
installed beside RADV), else discrete > integrated > virtual > CPU; `AETHER_VK_DEVICE=<index|name>` overrides.
Devices below the baseline (Vulkan 1.2, `shaderInt64`, 23 storage buffers per stage, 8×8 work groups) are listed
with the reason; `aether_last_error(NULL)` reports a failed create.

**Baseline:** the SPIR-V is 1.5 with only the `Shader` and `Int64` capabilities (spirv-val clean for vulkan1.2; no
subgroup ops), so Vulkan 1.2 + `shaderInt64` covers Pascal (GTX 10xx), Polaris+, RDNA, the Deck, Intel Gen9+.

**Host-copy cost** (RTX 3090, `aether_bench`, back-to-back frames; zero-copy for comparison):

| | GPU per frame | CPU per `aether_gl_render` |
| --- | --- | --- |
| 1920×1080 zero-copy | 1.35–2.5 ms | (GPU-bound bench; in game: recording only, ~0.2–0.7 ms) |
| 1920×1080 host-copy | 5.7–7.0 ms (+~4.3 ms readback over PCIe) | 10.5–11.9 ms (8.0 ms GPU wait + 4.2 ms upload) |
| 2560×1440 host-copy | 9.5–11.6 ms | 17.6–19.8 ms (11.0 ms wait + 7.7 ms upload) |
| 960×540 host-copy (gl test) | 1.5–1.9 ms | 3.6 ms (1.7 wait + 1.1 upload) |

So the fallback works everywhere but costs a 1080p game roughly 10 ms of render-thread time: adaptive quality's
cost model (docs/ADAPTIVE-QUALITY.md) sees it in aether's measured time and drops aether's resolution there. Integrated GPUs share memory, so their copy is cheaper, and they have
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

## 3. How it joins the mod (after the NeoForge 26.3 conversion lands)

The other session's conversion is in progress in the main tree (it also moves the pack to Sodium 0.9.2 and Iris
1.11.7 and drops Distant Horizons). Nothing here touches `mod/` until it is accepted. Everything is staged in
[`native/java/mod-integration`](../native/java/mod-integration/README.md):

1. `move_in.ps1` (dry run by default, `-Apply` to write) copies `com.skycraft.aether` and `aether_core.dll`
   (`natives/windows-x86_64/`) into the mod and makes the hooks: `AetherServer.init()` and
   `AetherClient.init(modBus)` in `Skycraft.java`, `AetherNet.register()` in `SkyNetwork.init`, and
   `WeatherFieldTexture.copyForAether` (the clouds' weather feed). `sandbox_check.ps1` rehearses exactly that on a
   copy of the main tree's sources and compiles the whole mod against the conversion's patched 26.3 jar.
2. Rendering: `AetherClient` hooks `RenderLevelStageEvent.AfterOpaqueBlocks`, builds the frame from
   `CameraRenderState`, reads the near field's depth from the bound target (`AETHER_GL_BOUND_DEPTH`) and copies the
   result into `skycraft:aether/color` / `skycraft:aether/depth`, the pack's `customTexture`s (Iris resolves them
   through the TextureManager: no Iris internals or mixins). Other dimensions blank them.
3. Terrain: single player evaluates the server's own `tamriel_height` (exact); a multiplayer server streams the same
   tiles (bit-identical, cached on both ends); either way the seed-free raster stand-in fills the window first.
   Grass and foliage wear the biome's colours.
4. On top: every planned settlement (StreetPlan) and fort as building massing, and far actors from the server.
5. Ship `shaderpack/build/SkycraftAether.zip` in the pack and select it in `iris.properties`. Keep `PLANET_RADIUS`
   and `sunPathRotation` equal on both sides (`AetherClient.planetRadius` / `sunPathRotationDeg`).

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

1. After the conversion is accepted (server boots, client joins): `move_in.ps1 -Apply` (coordinated with whoever is
   editing `mod/`), build, ship the pack zip, then the owner's checks below.
2. Later: block-exact surface materials (the material rules per biome, not the raster dressing), wild landmarks
   (seeded, not in the plan), per-landmark silhouettes (White-Gold Tower's spire is a rounded tower today), SRC GI
   underground, a Vulkan-to-Vulkan path for the vanilla Vulkan backend.

### Owner checks in game

Start single player in a Tamriel world with the Skycraft Aether pack, render distance 16-24 chunks:

- **Hand-off seam** at the edge of the render distance: no gap, no double terrain, no colour or brightness jump
  between the rasterised near field and the far field (the band is `AetherClient.handoffBand`, 24 blocks).
- **Horizon:** terrain to 30-40 km, curving away (planet radius `AetherClient.planetRadius`); haze and sky like the
  near field's; nothing popping as you fly (the stand-in fills the window at once, exact tiles replace it within a
  few seconds, nearest first).
- **Settlements from afar:** Whiterun's wall ring, towers and Dragonsreach from the plains; Solitude, Markarth,
  the Imperial City from across their valleys; forts as small grey squares with towers.
- **Clouds and weather:** the cloud decks, storms and rain shafts move with the weather you see near you.
- **Night:** moonlight on the far field like the near field's.
- **Other players** (LAN or a server) beyond entity range: impostors where they are, moving smoothly.
- **Multiplayer:** on a dedicated server the far field fills in the same (log line `aether far field on (server
  columns)`); rejoining the same world is instant (tile cache).
- **Frame time** on the 3090 at 1440p: aether costs about 4-6 ms with clouds; F3 shows the rest as before.
- **Fallbacks:** in the Nether and End the far field is off; without the library or a usable Vulkan device the
  game runs as before with one log line; on a GL context aether cannot share memory with, the far field still
  draws through host copies (the `aether: {...}` log line says `"interop":"host-copy"` and why).

### Hardware still to test (portability)

Run `native/build_linux.sh` (Linux) or `native\build.ps1 -Validate` (Windows) on each, then the mod once, and keep the
`aether: {...}` log line. Expected interop in brackets.

- **Steam Deck** (RADV + radeonsi, SteamOS 3.6+): build in a distrobox (`build_linux.sh` header), Desktop Mode:
  `build_linux.sh` then `build_linux.sh --validate` and `--glx`. [zero-copy (opaque fds)]. Check `pickedForGlContext`
  true with lavapipe also installed, `dedicatedOnly`, frame time at 1280×800 and the dynamic scale it settles on.
  Then Game Mode (gamescope): GLFW may run on XWayland (GLX) or Wayland (EGL): the log's `winsys`.
- **Bazzite / Fedora desktop, AMD** (RADV + radeonsi): the same three runs; then the mod under both a Wayland and an
  X11 session. [zero-copy (opaque fds)]
- **Linux, NVIDIA proprietary** (Bazzite-nvidia or similar): the three runs. [zero-copy (opaque fds); GL and Vulkan
  driver UUIDs must match, if not: `interopReason` names both, retry with `AETHER_INTEROP=zerocopy`]
- **Windows, AMD** (Adrenalin): `build.ps1 -Validate`. [zero-copy (win32) if the GL driver exposes
  `GL_EXT_memory_object_win32` + `GL_EXT_semaphore_win32`, else host-copy with the reason]. Watch for a black or
  garbled far field in zero-copy (tiling metadata): if so, report `dedicatedOnly` and force `AETHER_INTEROP=hostcopy`.
- **Windows, GTX 1080 (Pascal)**: `build.ps1 -Validate` and `aether_bench w=1920 h=1080`. [zero-copy (win32)]. Pascal
  runs 64-bit integer ops at low rate: compare the bench to the 3090's per-set ratios.
- **A hybrid laptop** (iGPU + dGPU): run the game on each GPU; the log must show the matching device chosen
  (`pickedForGlContext` true) and zero-copy, not a cross-GPU host-copy.

## Testing in the real game (2026-10-18)

Single player, the fast loop (one client, under the live lease `.work/lease/live.json`):

```powershell
py -3 tools/dev/build_263.py --offline build
py -3 tools/dev/live_world.py install-core E:/Skycraft-scratch/migration-26.3/build/libs/skycraft-0.1.0.jar
py -3 .claude/worktrees/aether/shaderpack/tools/pack_live.py --launch --close --cams helgen-near-smoke `
    --visit "n:-20,1158,90,180,-6" --wait 300 --off --tag mytest
```

`pack_live.py` syncs the pack from its worktree (or `--no-sync`), reloads it in Iris (`shaders off/on`), prints only
shader errors, captures after full readiness and closes cleanly. `live_world.py close` also closes a client that is
stuck on a menu (normal window close, never a kill).

Multiplayer over TCP (full-pack dedicated server + the full-pack client):

```powershell
py -3 -I tools/build_server_instance.py --core-jar <jar> --java <java25> --work-root <dir> --cache <dir> --output-zip <server.zip>
py -3 tools/build_prism_instance.py --core-jar <jar>
py -3 tools/dev/mp_server.py setup --zip <server.zip>; py -3 tools/dev/mp_server.py start
tools\qa\prism_autotest.ps1 -PrepareOnly -Live -SourceZip dist\Skycraft-26.3-client-instance.zip -Server 127.0.0.1:25790 -Java <java25>
py -3 tools/dev/live_world.py launch      # status: remoteConnected
py -3 tools/dev/mp_server.py cmd "op <player>" "tp <player> x y z"   # live controls need op in remote mode
py -3 tools/dev/live_world.py actions <file with: settle 1800 / wait 300 / capture TAG>
py -3 tools/dev/mp_server.py stop
```

Evidence lines: server `aether tile service: first request from <player>` and `N tiles (X MB) sent`, client
`aether: asking the server for far tiles` and `N far tiles received`. First run: 114 tiles / 1.6 MB in a minute; the
client caches them under `.minecraft/aether-cache/<world>` (revisits are instant and ask nothing).
