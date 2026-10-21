# Infinite ATLAS: where the renderer stands (read this first)

The current state of NirnCraft's renderer, what the owner has decided, what is in flight and what is broken. Written
2026-10-20 so work survives a context reset. Older renderer docs are history where they disagree with this one;
[ATLAS-RENDERER-PLAN.md](ATLAS-RENDERER-PLAN.md) keeps the decision log, this file the present. Update it at every
milestone.

## The owner's order of work (2026-10-20)

1. **Finish the GPU conversion.** Everything on the GPU; the CPU is only the CPU backend's fallback. No CPU interim
   stages (no background CPU threads, Java pages or single-threaded features as steps on the way).
2. **A proper loading phase.** A loading screen on world load / server join: GPU and caches initialised, the spawn
   neighbourhood generated and baked to a target radius from disk cache or the GPU generator, edits resynced with the
   server (hash checked), shaders warmed; only then the player gets control. Progress shown, never a half-built world.
3. **Remove vanilla's chunks.** Atlas draws water, the near shadow map and everything vanilla's chunk meshes still give;
   vanilla only simulates in a small radius; its chunk meshing and far chunk sending stop.
4. **A profiler with full tracing.** Tracy (CPU zones on every thread, Vulkan GPU zones per pass, memory and queue
   counters, live), Java zones too, a Chrome/Perfetto trace export, an in-game counter overlay. The passive
   `atlas-profile.log` (below) stays as the cheap baseline.
5. **One residency manager.** One GPU-side priority for every piece of data (screen-space error x visibility x
   proximity x predicted motion) driving admission, generation, baking and eviction; hard memory budgets (GPU and host,
   from atlas_memory_info) and per-frame work budgets; tiers: GPU hot, host RAM warm (compressed), disk cold (on F:/E:,
   compressed, keyed by seed + generator version + edit hash: back somewhere = instant reload); LRU per tier, bounded
   sizes, reference-counted slots, free lists, compaction, leak checks; uniform (all-stone) sections take no slot.

Later: baked light in the voxels (static emitters, AO, sky visibility baked on the GPU by the same light kernel;
real time only where something changes, rebaked when it settles; downsampled with the blocks so towns glow far off).
Weather and biome work stay paused until the owner says.

## Standing rules (also in memory)

- GPU-first, end to end. The client owns the world: it generates and renders everything; the server sends only
  coalesced, position-based operations (edits), hash-checked.
- One renderer, one continuous gradient: no near / far field. Detail per pixel from its footprint (continuous level,
  blended across stored resolutions like trilinear mips); "levels" are storage only.
- Physical light with pre-exposure; the eye adapts (AtlasMeter); no vanilla fog (our media only).
- Visual claims need evidence (captures, side by sides, metrics); agents' sheets are checked before merging.
- **Resources (after the 2026-10-20 machine lock-up):** one heavy job at a time (native builds, Gradle, offline
  renders, the game); check free RAM before heavy jobs; heavy outputs (renders, bakes, traces, exports) go to
  `E:\NirnCraft-scratch` or the repo's `.work`, not C:. Up to three agents in parallel (owner, 2026-10-20) only
  because every heavy job goes through the shared lock: `py -3 E:/NirnCraft-scratch/locks/heavy.py "<label>" --
  <command>` (one at a time, waits for 8 GB free RAM).
- Close the dev client after each test batch; never run the autotest action queue while the owner plays (it holds the
  player: use the passive profiler instead).
- The project's pre-rename name never in the tree (a case-insensitive `git grep` for it stays empty); never Bethesda
  assets; no mixins / access transformers; push only when asked.

## What works (atlas-render, 5ffc5489)

- **Unified mode** (`nirncraft.atlas.unified=true`, ATLAS_FRAME_PRIMARY_NEAR_LIT): atlas traces every pixel; vanilla's
  opaque chunk meshes are not drawn; the resolve adds our air (beams, glare, crepuscular, media, under water). One
  light for every land hit at any distance. Past the host's far plane atlas writes minus the distance. Near water is
  vanilla's raster (atlas's primary rays pass it).
- **Moving**: the grid window follows the camera; atlas_update has a real budget (builds on background threads); the
  worker's update is a 60 ms slice; real blocks around a 600-block teleport in about 6 s with vanilla feeding.
- **GPU world generation** (`nirncraft.atlas.clientGen=true`): atlas_gen terrain sections bit-exact with the Java
  generator (100% on 107 chunks; 99.83% of a real server's chunks with client features; structures missing). The Java
  world generator uses StrictMath on the column path (705a3096 on the generator branch; heights can move by one ulp
  against chunks generated before).
- **Light**: far lights culled and binned on the GPU (lightCullMain / lightAdmitMain / lightBinMain); the light by need
  (tiles traced on schedules, own-sample history, footprint levels): atlas's frame 8.2 -> ~4 ms offline, flicker
  40.9% -> 0.29%.
- **Frame rate**: 30 -> 60 fps (vsync) after the far-light binning left the render thread (it was 25-28 ms of a
  31 ms frame); a weather texture's per-texel atan2 replaced by a fast one.
- **Sky** ([NIRN-SKY.md](NIRN-SKY.md)): physical air (Rayleigh / Mie from the climate), custom baked Masser and Secunda
  (regions, withered skin, relief with cast shadows, pole-safe maps), real orbits with parallax, phases, eclipses by
  place (Masser's alien totality, the horizon ring), lunar eclipses, occultations, Nirnshine, stars as birthsign
  placements (no lines), named stars, planets on orbits, the great comet, aurora volume, meteors; the eye's scotopic
  range (AtlasMeter.MAX 21).
- **Inside a block** (clipping): rock and earth opaque dark with depth (nothing seen past it, the eye's adaptation held);
  leaves and glass a volume by their chord. Vanilla's in-wall overlay off under our light.

## Defaults and shipping (so nothing is assumed)

- The unified field and client generation are **off by default** (flags default false). With no flags the dev instance
  draws vanilla's chunk meshes lit by atlas, atlas beyond them.
- **The shipped pack does not run the new renderer**: `pack/mods` still carries Sodium and Iris, and AtlasNearPass
  turns itself off when Sodium is loaded, so players get the shader-pack path. The near pass and the unified field run
  only in the dev instance (Sodium and Iris removed by hand). Shipping it means removing them from the pack (step 3).
- **Linux natives are behind**: the shipped libatlas_core.so (3440faae) lacks the 0.16 / 0.17 entry points (air, GPU
  generator, sky glow); Windows is 0.17. Rebuild with `py -3 tools/dev/build_linux_wsl.py` before shipping.

## In flight (branches, worktrees, work in progress)

| Who | Branch / worktree | State |
|---|---|---|
| Grid agent | `atlas-naadf`, `.claude/worktrees/agent-ad6de3e70c66603d6` | Committed: 57691c8d GPU coarse levels 1-6, f1579c89 continuous footprint LOD + leaf cut-outs from the texture + light through leaves + `VoxGrid::ingestGpu`, 4b4a59f9 no CPU stage per section. **Mid-merge of atlas-render, unfinished**: MERGE_HEAD d3f2fa85, conflicts unresolved in atlas_far.slang, atlas_shared.slang, api.cpp, gpu_layout.hpp, vk_backend.cpp (the light-by-need lightCtl / farLight and the eclipse eclipseA/B/C fields in FrameGpu must all be kept; static_assert ends `+ 64 + 48 + 32` before its own additions). Markers were resolved; agent resumed 2026-10-20 to verify, commit the merge, then fix bug 1. |
| Generator agent | `worktree-agent-ad125abe1eb89e706` | Committed: 374da61b exact StrictMath on the GPU (atlas_exact.slang), 705a3096 StrictMath in the Java column path, 7bb3267b merge of atlas-naadf, 20470052 GPU -> GPU ingest (atlas_gen_create_for / ingest into VoxGrid::ingestGpu; `-Dnirncraft.atlas.clientGen.direct=false` the old path). WIP committed as d1a4f690 (2D Tamriel inputs on the GPU, unverified). Agent resumed 2026-10-20: finish the 2D inputs, bug 2, far tiles on the GPU. |
| Features agent | `atlas-features`, `.claude/worktrees/atlas-features` (from d1a4f690) | Started 2026-10-20: trees, scatter, ores, dripstone on the GPU after terrain (match % against Java), then overlays and structures. |
| Light agent | `worktree-agent-a9041fbf0158099cd` | Merged (5dc820e7). Was assigned baked voxel light; not started. Deferred until after the five steps. |
| Moons, stars | merged | Done (moons a158cb48, comet b401de28). |

Not merged into atlas-render yet: everything on atlas-naadf after 424841c5 and the generator branch.

## Open bugs (seen live)

1. **Near shows far stand-ins** (owner, live): the grid's level-0 pool full (`slots 16384/16384, evicted 16915,
   overflow 5494`): admitted in arrival order (the server stream sent ~46k mostly far sections), every non-air section
   takes a slot (mountain interiors too), overflow goes to a CPU level 0 that lags: blurry flat-green blobs and a black
   cube beside real blocks. Fix (sent to the grid agent): uniform sections as a state (no slot), strictly nearest /
   in-view first admission and eviction, level-0 extent from the footprint rule, pool sized from the memory plan,
   no CPU fallback near, one slot per section whatever the source; and ingestGpu reporting what it accepted / let go.
2. **Generation starts ~5 s late after a jump** (clientGen chunks flat for 5 s): requests not triggered on the move.
3. **Sky seam at the zenith and dark specks in the day sky** (owner): the sky-view LUT (192x108) maps latitude with a
   sqrt and the azimuth relative to the sun: a pole at the zenith; the specks are undiagnosed (possibly far birds,
   SkyFlocks, or a star/planet/Xero-Lyg term not gated by day).
4. **Bright streaks along a far lake shore at night** (near / far water boundary).
5. **Sun glare washes trees** near a low sun; moonbeams not shown (beams use a fixed scattering 0.004: tie it to the
   air's humidity / aerosol from the climate).
6. **Rock darkness too flat** inside blocks (needs a little of the block's colour near the face).
7. Night lake bed teal (the water's scattering at full night adaptation; with the water redo).

## What is still on the CPU (to finish step 1)

1. The generator's 2D Tamriel inputs (Java pages) - in progress on the generator branch.
2. Features (trees, ores, scatter, dripstone): Java, single-threaded, 81 ms / chunk.
3. Settlement and tree overlays (AtlasCapture.Recorder, Canvas) in the feed thread; structures from the plans.
4. The CPU World build for sections fed through atlas_upload_section (vanilla chunks, the server stream); gone where
   generated sections go GPU -> GPU and the grid owns every level.
5. The coarse levels on atlas-render (GPU on atlas-naadf, not merged).
6. Far tiles from the server's tile service (Java, 5 threads) and the far World build from them: to be generated on the
   GPU from the GPU height engine and the surface rules, client side.
7. Vanilla: chunk loading / sending / meshing (until step 3); the weather simulation (paused work).

## Tools and how to test

- Build: native `.\native\build.ps1 -NoTest` (suites `native/build/Release/atlas_tests.exe`, `atlas_gl_test.exe`);
  Java `py -3 tools/dev/build_263.py --offline jar` (or `compileJava`); shaders `py -3 tools/dev/glsl_check.py`.
- Ship natives: copy `native/build/Release/atlas_core.dll` to `mod/src/main/resources/natives/windows-x86_64/` and
  update `natives/manifest.json` with `tools/dev/native_sources.py`'s `source_hash()`; or drop the dll in the
  instance's `.minecraft/atlas-dev/` (loaded instead at the next start, no jar rebuild).
- Live: `py -3 tools/dev/live_world.py install-core <jar> | launch | actions <file> | close | status`. Flags go in the
  instance's `.minecraft/nirncraft-harness-flags.properties` (unified, clientGen, profile; `nirncraft.comet=now` forces
  the comet).
- Autotest actions (never while the owner plays): `atlas-info` (frame / glRender phases / inside / feeds / clientGen /
  voxgrid to the log), `atlas-unified on|off`, `far-timeline start` / `far-timeline dump NAME`, `capture-inside TAG`
  (clip tests), `camera-travel TAG TICKS X Y Z LX LY LZ` (moving captures every 10 ticks). The eye is 1.62 above the
  feet; conditional poses: `try execute ... if block ~ ~ ~ #minecraft:leaves run tp ...` (the last success wins).
- Passive profiler while someone plays: `nirncraft.atlas.profile=true` appends atlas-info with the player's position
  every 2 s to `.minecraft/atlas-profile.log`.
- Offline: `atlas_render.exe` (primarynear=2, seq=static,turn,walk, interleave, lightsynth=N; see the agents' commits),
  `tools/dev/sky_offline.py` (the game's sky GLSL on GL), `tools/dev/moon_gen.py` / `moon_sheets.py`.

## Pending owner decisions

- Delete old Claude scratch on C: (this project's 22 old sessions, bash-edit-diff, AppData\Local\Temp\claude, the
  old-name `*-aether-*` / `*-glsl-*` temp folders, old zip extracts; Second-Brain left alone unless told); move the
  Prism instance (13 GB) off C:.
- Rewrite the two unpushed commits that contain the old name (c29468eb, ae36b77b) once the agents' branches have
  landed (owner said yes; do it at a quiet point and rebase the active branches). Pushed history keeps the pre-rename
  name unless the owner asks for a full rewrite with force-push.
