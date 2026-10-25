# Infinite ATLAS: the plan to finish the renderer

Written 2026-10-24 after the fourth live test and a review of Project Aether. **Read this first.** It replaces the
order-of-work sections of ATLAS-STATUS.md, ATLAS-ENGINE.md §3 and ATLAS-RENDERER-PLAN.md's phases. Those stay as
history and reference: the design (ATLAS-LOD, -STREAMING, -WORLD-STORE), the checklist of everything Minecraft draws
(ATLAS-RENDER-INVENTORY) and the decision log.

## 0. Principles (owner, 2026-10-24)

1. **One system for everything.** Our renderer draws every pixel of the world, near and far: blocks, fluids,
   entities, block entities, items, particles and effects. There is no Minecraft-style near renderer with a far
   field behind it. Only the UI and the first-person hand are laid on top.
2. **One continuous gradient.** Detail falls off smoothly with distance, matched to what the eye can resolve at
   that distance (the pixel footprint). There's never a line where one system hands over to another.
3. **The same look near and far.** A block, a tree or a town reads the same at 2 m and 2 km, only less detailed.
   Every vanilla block and every NirnCraft block matches vanilla up close.
4. **One world, used for everything.** The same voxel world the renderer draws also answers collisions and
   physical interactions, so what you see is what you hit. It lives on the client.
5. **The client owns the world.** It generates, stores and renders everything. The server sends only the key pieces:
   edits, entity state and interactions, coalesced and by position, hash-checked.
6. **Scale without switches.** Quality is continuous everywhere, like dynamic resolution: one controller trades the
   least important detail first (distant entities, far light bounces, coarse levels) to hold the frame, on low-end
   and high-end machines alike. Nothing pops on or off. The same holds for entities, players, light and physical
   interactions: they scale by importance and distance, not by hard limits.
7. **Realistic light at every distance**, from the same light system near and far.
9. **Never freeze, and say truly what is loading (owner, 2026-10-25).** No pipeline compile or bake work on the render
   thread. While atlas isn't ready, vanilla keeps drawing, reduced (short distance, no costly passes) so the work gets
   the machine. The loading screen shows each real phase with true progress from real counters (kernel compiles,
   bake per level, cache load, spawn), never a fake bar; background work after entry shows a small indicator.
   **The player is held until loading is truly complete**, in single player and on servers. Until the client reports
   ready, the player isn't placed in play: invisible to others, untargetable, invulnerable, frozen, not ticking
   anything that affects gameplay. Only then does the character select (first join) or the return to the last
   position happen. Loading is real loading.
8. **One generator (owner, 2026-10-24).** Far is the real world downsampled, never a second guess. The coarse levels
   are made on the GPU from level-0 chunks of the one exact generator. The coarse "far path" generator is only a
   placeholder: it fills places the level-0 bake hasn't reached, is marked as a stand-in, and fades out (refinement
   dissolve) when true downsampled data arrives. The background baker bakes the whole map at level 0, nearest first;
   once the map is complete the far path isn't needed.

The stages below reach this end state in order. Early stages may keep vanilla drawing some things (composited
into our frame) only as a step; stage 4 and stage 5 remove every one of those steps.

## 1. The bar (what "done" means, measurable)

The owner's target (2026-10-20): one renderer from the player's feet to the horizon, everything Minecraft draws
(with our custom blocks), smooth and never jittery, beautiful at rest and in motion, scaling past any vanilla view
distance. As numbers:

| Bar | Measure | Pass |
|---|---|---|
| Looks like Minecraft | Every block state (vanilla + NirnCraft), atlas vs vanilla, same view at 2 m and 16 m | 100% of states within the diff threshold, failures shown on one sheet |
| Same block near and far | The same states, atlas at 2 m vs its coarse levels at 64 m-2 km (colour, coverage) | Mean colour within threshold at every level |
| No line, no holes | Scripted route (walk, sprint, fly, turn) in the real game | 0 holes in view over the whole route; no ring in the captures |
| Smooth | Same route, 1440p, RTX 3090 | p99 frame ≤ 20 ms, worst ≤ 50 ms, no frame over 50 ms caused by atlas |
| Instant revisit | Relaunch, same route | 0 chunks generated in visited places; full radius in under 2 s |
| Everything drawn | ATLAS-RENDER-INVENTORY's checklist | Every item ticked in route captures (ours, or vanilla composited and lit by ours) |
| One system | Route captures | Nothing composited from vanilla but the hand and the UI (stage 4 on) |
| What you see is what you hit | Scripted walk against the drawn world | No walking through or floating on anything (stage 5) |
| Scale | Stress scenes: ocean, mountains, dense forest, caves, night with many lights, Imperial City, fast flight | Each within the frame bar; frame time ≤ vanilla at its default distance |

A stage is done when its bar passes in the real game, not offline.

## 2. Why we keep not getting there

1. **Too many fronts at once.** Generation on the GPU, features, settlements, content, water and residency all moved
   together, so every live test found five new regressions and none got finished. The renderer never stood still
   long enough to be right.
2. **Offline checks didn't follow the game's path.** The cache round trip passed in a bench and never wrote in the game.
   Benches held the camera still, so the ring of holes only showed when the owner moved. The block gallery compared
   against references, not against vanilla in the game, so grass pits shipped.
3. **No profiler.** Stutter was diagnosed from one log line a second; causes were guessed (the 944 ms submission, the
   36 ms staging spike).
4. **No guaranteed floor.** When data is missing, the ray sees sky, so any gap shows as a stark line.
5. **Agents stopped mid-work** (usage limits) with large uncommitted diffs (632 lines on atlas-naadf right now).

## 3. What Project Aether teaches

`E:\Projects\Personal\Project Aether` (Rust, Vulkan, Slang, about 15k lines) was designed from our own docs. Its world
is a fixed 32 m-tall box in dense arrays (levels 0-2) plus a baked 512×512 height map for everything beyond. It never
streams, so it never has holes. It doesn't solve our problem (a real block world, streamed to the horizon), and its
roadmap of 12 slices is mostly unbuilt. Not a code source. Four ideas are worth taking:

| Idea | In Aether | For us |
|---|---|---|
| A guaranteed floor | Rays that leave the arena hit the baked horizon map | A complete coarse floor (our horizon cache / the generator's 2D height and colour inputs) under every ray: missing data shows as coarse terrain, never sky. Stage 2 |
| Refinement dissolve | New data fades in over 600 ms (per slot arrival time vs the pixel's dither) | Each slot records its arrival frame; the per-pixel level dither eases from the coarser level over ~0.5 s. Ends pop-in. Stage 2 |
| One quality controller | Frame time steers lod bias, ray steps, budgets | One controller from GPU frame time steering the generator budget, staging budget, lod bias and ray budget (our pacer, generalised). Stage 1 |
| Assertions as tests | Telemetry server with `assert_fps`, `assert_zero_holes` | The scripted route reports holes and frame times and fails on the bar. Stage 0 |

Already ours (Aether copied them from us): footprint level per pixel, `[noinline]` with value structs, step budgets
against the watchdog, uniform cells taking no slot, the 16³ micro-brick for non-cube blocks.

## 3b. Architecture check: every requirement against the design (2026-10-25)

The renderer direction is decided by this table, not by building everything to find out. **Known** means it's built
and measured, or standard practice we already rely on. **Risk** means only an experiment answers it. Only the
experiments in the last column get run, in this order: E1, then E2 to E5 as their stage comes up. Everything else
follows the design without new tests.

| Requirement | How the design meets it | State | Vital experiment (only if risk) |
|---|---|---|---|
| Terrain, near to horizon | NAADF grid per level (clipmap, levels 0-6), footprint picks the level per pixel, baked coarse floor | Built; in game the grid is the far path (~17 km reach) | **E1** grid far cost on 3 views (valley, 10 km horizon, grazing): fits ~6 ms at 1440p? Else hybrid or height pyramid |
| Vanilla and custom blocks, materials | Level-0 blocks plus 16³ micro-bricks from baked quads; the gallery against vanilla | 113/300 pass; causes now lighting, not shape | No new experiment: the gallery is the measure |
| Water and fluids | Medium in the trace (in and under), waterlogged bricks | Built; look gap (tint, waves) | None: a look decision, done in the lighting pass |
| Sky light, sun, moon, night | Physical sky, light by need, tile schedules | Built; night not rechecked since the kernel rework | None: route captures at night |
| Static light sources, near and far | Emitters in GPU light binning, far lights | Built | None |
| Dynamic lights (held torches, spells, burning mobs) | One per-frame light list into the same binning | Not built | **E3** binning cost with 1,000 moving lights at 1440p |
| Shadows | Traced (shadow rays against the grid and the entity BVH) | Terrain yes; entities not yet | Covered by E2 |
| Entities, players, mobs near to mid | Object-space micro-voxel bricks, posed per frame, GPU two-level BVH traced with the terrain | Not built (vanilla raster with our light now) | **E2** BVH build and trace with 50 / 500 / 5,000 animated entities: frame ms at 1440p |
| Entities far | Splatted into the coarse levels as transient voxels; impostors with dissolve exist in the old far view | Partly (old view) | Covered by E2 (splat cost) |
| Smoke, clouds, particles, volumes | GPU particles and density volumes in the same trace (clouds already), lit by ours | Clouds built; particles vanilla | **E4** a smoke volume plus 10k particles: ms near and far |
| Low-end and high-end scaling | One continuous quality controller (resolution, lod bias, ray and light budgets, entity detail) | Pacer only | **E5** the route on the integrated GPU with the controller holding 30 fps: does the picture stay acceptable? |
| Fast loading and far changes | GPU-native region files, baked whole-map coarse levels, per-section edits re-downsampled, per-stage fingerprints | Coarse bake built (7-19 MB per level); format and fingerprints designed | None now: measured by the route's relaunch and ready time |
| Server communicates elements | Coalesced position-based edits plus entity state by interest; bake shared by region hashes (designed) | Edits stored as deltas; sharing not built | None until multiplayer; design in ATLAS-WORLD-STORE §2 |
| Same world for collisions | The server fed from the GPU generator; level-0 ids against the client level | Built on atlas-server; 0 mismatches over 7,500 blocks | None (server speed is the generator's) |

**Two candidate techniques to test when their stage comes (from an outside review, 2026-10-25):**
- **(C) Directional coarse voxels:** six-direction colour and opacity per coarse voxel, so distant forests and meadows
  don't average to mud and read right from the side. Test with the far-look work, on the gallery sweep (grass
  coverage at 1 km, tree shimmer).
- **(E) Cone tracing through the coarse levels** for bounce and AO. Test in the lighting pass against the dark side
  faces and corners.

Rejected: raster meshlets near with SDF morphing in a middle zone, because it reintroduces a near/middle/far split
(§0.1-0.2).

**Supported, not built (owner, 2026-10-25).** E2-E5 aren't features to build now. They're checks that the core leaves
room for them, so none forces a redesign later. Each is a design check against the core's interfaces:
- the trace loop has a slot for a second structure (the entity BVH) and for density volumes;
- the light binning takes a per-frame list;
- the frame has the controller's knobs (resolution, lod bias, ray and light budgets, per-entity detail);
- the grid's levels accept transient splats.

A micro-benchmark runs only if the design check finds a likely blocker. Only E1 is a measurement, because it decides
the core itself.

Rules for tests from here: the route, gallery and relaunch are the standing gates; E1 now; E2-E5 as design checks,
done when the core settles; no other new tests unless a gate can't answer a question.

## 4. The stages

One stage at a time. Nothing from a later stage starts early. **Frozen until stage 3 passes:** far settlements and
overlays, new generator features, entities as bricks, multiplayer, baked light, weather.

### The GPU-driven loop and the baked world (2026-10-24)

[ATLAS-GPU-LOOP.md](ATLAS-GPU-LOOP.md) holds the design: the CPU only kicks submissions and does file I/O, every request,
schedule, ingest and codec runs on the GPU, the baked world is the main path, and live generation is the fallback. Its
twelve steps feed the stages:

- **Stage 1** (steps 1-3): wide, resumable trees; no `submitWait`.
- **Stage 2** (steps 4-10): the GPU region format and codec, the whole-map coarse bake as the floor, per-stage
  fingerprints with a verifier, the GPU request ring and idle baker, GPU scheduling and ingest.
- **Stage 5, started now on separate files** (steps 11-12): the integrated server fed from the GPU world, and
  collisions agreeing.


### Stage 0: tools that tell the truth (small, first)

1. **Salvage the grid work** on atlas-naadf (632 uncommitted lines: window-driven far scheduling, 0 uncovered within
   1.5 km in the walking bench, the staging timers, a game-path persistence test). Finish, test, commit, or park it
   on a branch. Nothing lost.
2. **Flight recorder:** CPU zones (render thread, generator thread, Java tick) and GPU timestamps per pass, kept in
   a ring buffer at almost no cost. Any frame over 50 ms dumps the surrounding 30 s as a Perfetto trace plus a
   one-page text summary naming the pass, step and thread. Always on in dev builds.
3. **The scripted route:** a fixed walk, sprint, fly and turn path in the autotest instance (the harness action
   queue). It reports, per second: holes in view per level, frame p50, p99 and max, chunks generated, cache hits and
   writes, and staging ms. Ten captures at fixed points. This is every stage's gate, run by me before the owner plays.
4. **The vanilla gallery:** every block state (vanilla + NirnCraft registry), rendered in the game, vanilla's raster
   vs atlas, same camera at 2 m and 16 m. Pixel diff, with one sheet of the failures only. Plus the distance sweep:
   each state's atlas colour and coverage at levels 0-6.

Gate: all four run in the game, and their first reports are the baseline (expected to fail the bar).

### Stage 1: smooth

The generator and staging never take frame time.

- The pacer fed with the real frame cadence in the game; one quality controller from GPU frame time.
- Every generator submission sliced to ≤ 4 ms of GPU time; generation on a lower-priority or async compute queue
  where the device has one.
- Render-thread staging under a per-frame budget (≤ 2 ms); the rest waits for the next frame.
- Every spike the flight recorder names, fixed at its cause.

Gate: route p99 ≤ 20 ms, worst ≤ 50 ms, while generating; recorder dumps empty.

### Stage 2: complete and continuous

- **Persistence on the game's path:** a relaunch generates 0 chunks where you've been (test drives AtlasClientGen in
  store mode, two launches, one cache folder).
- **The floor:** a complete coarse layer under all data; holes can't show as sky.
- **Fill order:** each level fills outward from the next finer level's edge, nearest first, with overlap (the
  window-driven scheduling from stage 0.1).
- **Refinement dissolve:** arrivals fade in.

Gate: route holes 0; second launch 0 generated and full radius under 2 s; the ten captures show no ring.

### Stage 3: looks like Minecraft

Worked through the gallery's failures by class, worst first. Each fix is verified by the gallery, not by eye.

| Class | Covers |
|---|---|
| Full cubes | Grass top pits (texel holes), overlays (grass side), tint layers, random rotations and variants |
| Cut-out | Leaves (tint, cut-out), glass, ice, iron bars, cobwebs |
| Shape by neighbour | Fences, walls, panes, bars, redstone wire, stairs' corner shapes, chests |
| Non-cube | Slabs, stairs, doors, trapdoors, buttons, lanterns, chains, beds, carpets, snow layers (micro-bricks from baked quads) |
| Plants | Short and tall grass, flowers, double plants, saplings, crops by age, mushrooms, ferns, vines, lichen, dripleaf, kelp and seagrass: crossed planes, offsets, tints, two-block plants joined, flower beds and petals |
| Fluids | Water: still and flowing heights, falls, sides against blocks, waterlogged blocks, surface animation, underwater. Lava: emissive, animated, flow |
| Light and animation | Fire and soul fire (animated, emissive), torches, lanterns, campfires, furnaces lit, redstone lamps, glowstone, sea lanterns, magma, nether portal, every animated texture |
| NirnCraft blocks | Every custom block in the registry, through the same gallery |
| Distance | Every class's coarse levels match its near look (the sweep) |

Gate: gallery 100% within threshold, sweep within threshold; owner test.

### Stage 4: everything that moves, in our renderer

Vanilla draws these today, composited into our frame. This stage moves them into our renderer (ATLAS-WORLD-STORE
§3), so they're lit, shadowed and scaled like the terrain:

- **Entities, block entities and items as object-space micro-voxel bricks**, built from their models (the same
  baked-quad method as non-cube blocks), posed per frame on the GPU from a compact state array. Traced near and
  mid-range through a GPU BVH with the terrain (shadows, light, bounce); splatted into the coarse levels far off.
  Covers mobs, NPCs, players, armour and held items, chests, signs (text), banners, shelves, pots, dropped items
  and item frames. Detail and update rate scale by importance and distance, so crowds and far players cost little.
- **Dynamic light list:** fire blocks (flicker), burning entities, held torches, fire arrows, spells and lightning
  into the GPU light binning in their own colour.
- **Particles and effects** (smoke, flame, drips, splashes, portal, spells) as our own GPU particles, lit by ours and
  scaling with distance. The green-squares source is checked here.
- Breaking cracks and the block outline drawn by us; only the hand and the UI stay on top.
- Every item in the inventory checklist ticked.

Gate: the checklist from route captures, with nothing composited from vanilla but the hand and the UI; a crowd
scene (hundreds of entities) within the frame bar; an owner test at night with fire and torches.

### Stage 5: one world: vanilla chunks off, collisions ours, and scale

- Vanilla's chunk meshing and its chunk sending off; vanilla simulates only a small radius.
- **Collisions and physical interactions from our voxel world** on the client: the player's and entities'
  collision shapes, raycasts (block targeting) and fluids read the same store the renderer draws, so they always
  agree. The server checks the key pieces only.
- The near shadow map, entity visibility and block-entity lists from our world.
- **One quality controller across everything** (detail, entities, light, effects, interactions), continuous, no
  switches; tested on the integrated GPU and the CPU path.
- Stress scenes and the frame bar.

Gate: frame time ≤ vanilla at its default distance; collisions match the drawn world in a scripted walk (no
walking through or floating on anything); the owner flies every stress scene.

**Then, in the owner's order:** far settlements (the overlays agent's far stage), far life (R5: far players
building in real time), multiplayer at scale, baked light, weather.

## 5. How the work runs (cost)

- **A few agents, each on the model its work needs** (owner, 2026-10-24): Haiku for image checks, Sonnet for coding,
  Opus for deep planning and stuck debugging. Poor work from a lower tier: stop it and hand over to the next tier up.
  Parallel agents only on separate files and worktrees; a read-only reviewer on every new GPU kernel.
- **Commit small and often**, with WIP commits allowed on the stage branch, so a usage limit never strands work.
- **Every agent prompt names the stage gate**, and the agent reports the gate's numbers, not a narrative.
- **Gates run by me**, in the game, through the route and the gallery. The owner tests at the gates of stages 2, 3, 4
  and 5, not between them.
- **Branch:** stage work lands on atlas-render. Old worktrees (atlas-features, -overlays, -content, -water, -step1)
  are parked; nothing merges from them until its stage.
