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

**Top priority (owner, 2026-10-21 late):** the world renders completely (every block, state and item in the world:
content agent on `atlas-content`), storing and loading (the self-baking cache, the coarse horizon cache, the loading
phase: grid agent), all ready for one owner test. Then entities and the rest per
[ATLAS-WORLD-STORE.md](ATLAS-WORLD-STORE.md). Multiplayer: the seed is shared for now (owner).

**Order change (owner, 2026-10-21):** step 5 (the residency manager) moves up and runs now beside the rest of step 1,
starting with the unbounded host RAM (the world stores every streamed section). The grid agent owns it on atlas-naadf.

Later: baked light in the voxels (static emitters, AO, sky visibility baked on the GPU by the same light kernel;
real time only where something changes, rebaked when it settles; downsampled with the blocks so towns glow far off).
Weather and biome work stay paused until the owner says.

## Standing rules (also in memory)

- **GPU only for now (owner, 2026-10-21):** test on Vulkan only; no CPU-backend versions of new GPU work and no
  CPU-backend runs as gates (the CPU fallback is a later, separate job). Java stays the exactness reference, computed
  once per test set and cached on E:.
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
  <command>` (3 build slots, `--exclusive` for GPU-heavy jobs, 16 GB cap per job, a timestamped log and a ledger of
  wait / run / phases per job; the orchestrator runs `locks/watch.py` under a Monitor for stalls). Every test prints
  `TRACE` phase lines (AGENT-RULES.md).
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
| Grid agent (done 2026-10-21) | `atlas-naadf` | 5ef5d630 merge of atlas-render; 55299718 first-frame skip fix; 113c57cd farMain inlining fix (14 min / 30 GB -> 9 s, driver JIT 190 -> 40 s); **30ca2dd9 bug 1 fixed** (uniform sections no slot, admission by priority, no CPU/tile stand-in near, pool 10% of the GPU budget = 180224 slots); 108b0ac9 `ingestCoarseGpu`. All Vulkan suites pass. Open from it: the second batch of noinline raised the walk/turn median frame time ~6 ms (measure before shipping); the world stores every streamed section in host RAM (step 5); light-green triangles on near grass (old; grazing level-1 faces suspected); grid bookkeeping 13-19 ms peak on the render thread. |
| Grid agent (history) | `atlas-naadf`, `.claude/worktrees/agent-ad6de3e70c66603d6` | Committed: 57691c8d GPU coarse levels 1-6, f1579c89 continuous footprint LOD + leaf cut-outs from the texture + light through leaves + `VoxGrid::ingestGpu`, 4b4a59f9 no CPU stage per section. **Mid-merge of atlas-render, unfinished**: MERGE_HEAD d3f2fa85, conflicts unresolved in atlas_far.slang, atlas_shared.slang, api.cpp, gpu_layout.hpp, vk_backend.cpp (the light-by-need lightCtl / farLight and the eclipse eclipseA/B/C fields in FrameGpu must all be kept; static_assert ends `+ 64 + 48 + 32` before its own additions). Markers were resolved; agent resumed 2026-10-20 to verify, commit the merge, then fix bug 1. |
| Generator agent (2026-10-21) | `worktree-agent-ad125abe1eb89e706` | **d7a293fc far terrain GPU→GPU into the grid's coarse levels** (atlas_gen_far_ingest → ingestCoarseGpu; dropped/wanted reports drive resends): a 1,600-block jump puts the first chunk in the grid 202 ms after the move, the radius-24 window in 1.8 s, far terrain settled ~5 s later; terrain to 9,418 blocks (horizon-far-on.png). 75c96297 far surface and far chunks on the device. Test flags: `nirncraft.atlas.clientGen=true` (direct path, device pages, far terrain, GPU far tiles on under it); `-Dnirncraft.atlas.farTiles=false` drops the tile far field and its CPU World build, off by default until the overlays are live. |
| Generator agent (history) | `worktree-agent-ad125abe1eb89e706` | Committed: 374da61b exact StrictMath on the GPU (atlas_exact.slang), 705a3096 StrictMath in the Java column path, 7bb3267b merge of atlas-naadf, 20470052 GPU -> GPU ingest (atlas_gen_create_for / ingest into VoxGrid::ingestGpu; `-Dnirncraft.atlas.clientGen.direct=false` the old path). WIP committed as d1a4f690 (2D Tamriel inputs on the GPU, unverified). Agent resumed 2026-10-20: finish the 2D inputs, bug 2, far tiles on the GPU. |
| Features agent | `atlas-features`, `.claude/worktrees/atlas-features` (from d1a4f690) | Started 2026-10-20: trees, scatter, ores, dripstone on the GPU after terrain (match % against Java), then overlays and structures. |
| Features agent (2026-10-21 update) | `atlas-features` | d992cf37: the feature engine; cover, freeze_top_layer, disks, springs, lakes 100% on Vulkan at -O0. Open: ores 84.5%, rocks 17%, trees 0%; **results change at -O3** (UB suspected: must be fixed before merge, the game ships -O3); tree dispatch made resumable (watchdog). |
| Content agent (2026-10-21) | `atlas-content` | 3fca5ee3 + dc0a909b (hooks, to the renderer agent): every non-cube block as a 1/16 micro-voxel brick from its model (doors, rails, redstone, ladders, lecterns, crops, flowers, NirnCraft props), full near state (crops age, redstone power), dirty tracking behind the camera, every emitter glows, GPU grid lights in true colour within 96 blocks, animated textures, lava textured with flow height, items lit, biome tint per column. Next: GPU-generated sections through the same materials and bricks; falling blocks / pistons shader. |
| Renderer agent (done 2026-10-21) | `atlas-water` | e55cb188 (on e7fb4f81): atlas draws all water (near and far, above and under; vanilla water raster off in unified mode), trace-state fix (explicit args and result structs), reviewer findings 1-8, 10, 12-16, step budgets (the 5 s timeout bounded); 9e163468 under-water ray cap (32 -> 18.5 ms). Open: entities under water hidden (next), the brick hooks (next), biome water tint, reflections without stars/moons. |
| Renderer agent (brief) | `atlas-water`, `.claude/worktrees/atlas-water` (from atlas-naadf f69a5668) | Owns atlas_far.slang and the near pass. **Owner: water on screen into atlas now** (no vanilla water raster in unified mode; near/far seam, bugs 4 and 7), plus the trace reviewer's findings (far water banding from primaryPassesWater's chord-relative t, water albedo carried into the bed, no clouds / far lights over land in PRIMARY_NEAR_LIT, roof-test footprint, stale split flags) and the [noinline] merge sites. |
| Storing and loading (grid agent, done 2026-10-21) | `atlas-naadf` | 3de6bf33 GPU ranking; fbac1070 + 103c20cb edit deltas (voxel-exact reload); 167a8d81 self-baking cache (spawn radius 0.97 s from cache vs 41-45 s generating; ~8.9 MB/km², Tamriel ~5.3 GB; fingerprint-keyed); 76568435 coarse horizon cache (0.81 s vs 8.6 s); 5ea75655 loading phase (AtlasLoadingScreen: renderer, generator, spawn area, horizon, then control; Esc skips; 180 s limit) and pipeline cache (cold 91.4 s -> warm 0.85 s). Rest of step 5 after the owner's test. |
| Step 5 (grid agent, earlier) | `atlas-naadf` | f69a5668: host tiers (sections packed on the GPU into a budgeted warm tier, a per-world cold tier on disk). Next: the grid reviewer's residency bugs, one GPU priority. |
| Generator agent (scope done 2026-10-21) | `worktree-agent-ad125abe1eb89e706` | f20676fa stage hook API; 519626ce, 8e5e54b3, 4c713d80 reviewer fixes (watchdog-safe submissions); f84b5368 snow, ice, far roads on the GPU (99.91% of columns vs the painter; roads and ice exact; far chunks 0/67M). **Multiplayer client generation designed (ATLAS-CLIENT-GENERATION §9), blocked on an owner decision: the seed decides ores (public seed / ores on a server-only key / keep the tile service for remote players).** |
| **Owner decision 2026-10-21** | | Sanity must not remove fluids (the overlays agent changes the server rule and the GPU sweep). |
| Overlays agent (2026-10-21) | `atlas-overlays` | 8f6aa629: pieces + Sanity + Accessibility + street paint as generator stages (PIECES, SWEEPS, PAINT+50), GPU→GPU, 99.917% of changed blocks vs the server (Riverwood 99.97%); tapes recorded once per world and cached; Sanity keeps fluids (owner). Next: batch apron, near road paint, structures, Orphan/Rock sweeps. |
| Overlays agent (history) | `atlas-overlays`, `.claude/worktrees/atlas-overlays` (from e746a869) | 48c51580: settlement pieces (recorded tapes), Sanity sweep, fence connections and street paint on the GPU, 99.13% of changed blocks alike vs the server's pass (Riverwood 89.76%: Sanity per chunk vs per piece), 0.1 ms a chunk; own Vulkan device, not wired live yet. Next: live wiring on the generator's device, Riverwood, the other sweeps, road paint, seeded structures, then retire the far-field Java overlays. **Owner decision pending:** Sanity's last rule deletes unsupported water (holes in rivers at Riverwood's bridge); the GPU copies it to stay exact. |
| Light agent | `worktree-agent-a9041fbf0158099cd` | Merged (5dc820e7). Was assigned baked voxel light; not started. Deferred until after the five steps. |
| Moons, stars | merged | Done (moons a158cb48, comet b401de28). |

**Before the owner's live test (owner, 2026-10-21):** step 1 complete and merged, including the grid's bug-1 fix
(verified on Vulkan), `VoxGrid::ingestCoarseGpu` (far data from the GPU generator straight into the coarse levels),
farMain without forwarded inout chains (fast compile and driver JIT), and all of world generation on the GPU: terrain
and the 2D inputs (done), far tiles (done), far chunks into the grid and no tile service / CPU World build (generator
agent), features, settlement and tree overlays and structures from the plans (features agent). After the merges the orchestrator refreshes the bakes on atlas-render (`tools/bake/bake.ps1`; the road and street
plan bakes are stale, so the game plans them live at load, ~35 s), then installs the build and boots the client with
the passive profiler; the owner plays.

Not merged into atlas-render yet: everything on atlas-naadf after 424841c5 and the generator branch.

## Merge notes (for the step-1 integration into atlas-render)

- With atlas-content a2f5169d, in the generator's AtlasClientGen.java (GPU->GPU path): constructor
  `farIds[i] = AtlasClientSections.nearIdOf(s);`; ingestTable `far[i] = AtlasClientSections.nearIdOf(s);` and
  `mats.section(Block.stateById(far[i]), AtlasMaterials.tint(...))` instead of `mats.section(st, ...)`.
- Far binding numbers: 39 waterAdd (atlas-water), 40 brickData (atlas-content), BINDINGS 41, +1 descriptor pool slot
  for the publish binding (atlas-water).
- Feature kernels at -O0 (atlas-features CMake); every other kernel -O3 (the wrapper's default).
- Rebuild and ship the natives (Windows DLL; Linux .so via build_linux_wsl.py) after the merge; refresh the bakes
  (stale road / street plans); `atlas-cache/` and `ATLAS_PIPELINE_CACHE` in the instance.

## Risks to fix after the owner's test

- **atlas_exact.slang's `[noinline]` helpers write the static `g_bad`:** NVIDIA's compiler mixed up repeated calls
  in the feature kernels (floorD x6, l2f, nextInt), so features use their own copy (atlas_feat_math.slang). The
  generator is exact on today's driver, but a driver update could break it, and two copies drift. Fix: pure helpers
  returning a result struct with the flag (as atlas_far's TraceIn/SecIn fix), one shared copy, gen and feature parity
  rerun.

- **Feature kernels ship at -O0** (30bea9c8): at -O3 NVIDIA driver 616.56 evaluates `holes == 1` false with holes = 1
  in the spring kernel (the -O3 SPIR-V checks correct op for op; -O0 is exact). After the test: try -O1/-O2, rewrite
  the failing comparison, retest on newer drivers, minimal repro for NVIDIA.

- **Cache x chunk store** (integration, 2026-10-21): in store mode every chunk also goes through the store (cache hits
  go up at once, the store re-makes them in the background, identical hashes skipped), so the cache saves loading time
  but not GPU work. After the test: send only a cache miss's radius-2 neighbourhood through the store.

## Owner's live test of step 1 (2026-10-21)

Findings and their owners are in [ATLAS-ENGINE.md](ATLAS-ENGINE.md) §2: the GPU decoration failed to start (Mth.SIN
null in the pack, Java fallback on the CPU: stutter and CPU load; features agent); far placeholder trees vanish up
close (features agent: far trees from the real placement); fences / walls / panes / bars don't connect and vegetation
is missing (content agent: multipart and cross models); small green squares in the sky (content agent); stutter to
be re-measured after the first fix.

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

- Shader compile speed: `-O3` on atlas_far's entries takes ~14 min each (the SPIR-V optimiser), `-O0` ~2 s. Dev build
  directories point CMake's `SLANGC` at `E:/NirnCraft-scratch/tools/slangc-dev.cmd`. **Fixed on atlas-naadf
  113c57cd:** spirv-opt inlined traceLod and everything under it 46 times (farMain was one function of 1.88M
  instructions: ~14 min and ~30 GB at `-O3`); `[noinline]` on the shared trace functions makes it 17 s, 0.76 GB, 2.7 MB.
  Rule for every kernel: `[noinline]` on big helpers called from several places, no inout values forwarded through
  calls (thread-private statics instead); the gen and feature kernels got the same fix (all compile at `-O3` in
  1-3 s, < 1 GB). **Every kernel builds at `-O3`** (the wrapper's default again; `-O0` kernels run so slowly the
  Windows GPU watchdog resets the device). Only farMain in a tree without 113c57cd falls back to `-O1`. **The first Vulkan context after a SPIR-V change takes ~6 min once** (NVIDIA's JIT; its disk
  cache then makes it seconds): for players, ship or prewarm a pipeline cache (`ATLAS_PIPELINE_CACHE`; step 2's loading
  phase). atlas_far.slang's other entries at `-O3`, cached by content (source + includes + args) in `E:/NirnCraft-scratch/slangcache` so each source
  state compiles once for every worktree; every other kernel at `-O0` (seconds). After switching branches or pulling,
  `touch native/shaders/atlas_far.slang` so the build picks the cached `-O3` result. **farMain must be `-O3`**: at
  `-O0` / `-O1` the NVIDIA driver's JIT takes ~5 min per Vulkan context and runs out of memory (Vulkan tests SKIP, the
  game would stall at load); spirv-opt cannot process the `-O1` output ("ID overflow").
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
