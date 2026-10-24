# Infinite ATLAS: the plan to finish the renderer

Written 2026-10-24 after the fourth live test and a review of Project Aether. **Read this first.** It replaces the
order-of-work sections of ATLAS-STATUS.md, ATLAS-ENGINE.md §3 and ATLAS-RENDERER-PLAN.md's phases. Those stay as
history and reference: the design (ATLAS-LOD, -STREAMING, -WORLD-STORE), the checklist of everything Minecraft draws
(ATLAS-RENDER-INVENTORY) and the decision log.

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

## 4. The stages

One stage at a time. Nothing from a later stage starts early. **Frozen until stage 3 passes:** far settlements and
overlays, new generator features, entities as bricks, multiplayer, baked light, weather.

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

### Stage 4: effects and things that move

Vanilla still draws these, composited into our frame with our depth and lit by our light. This stage makes them
correct and lit, and adds what's missing:

- **Dynamic light list:** fire blocks (flicker), burning entities, held torches, fire arrows, spells and lightning
  into the GPU light binning in their own colour.
- **Particles:** fire smoke, campfire smoke, flame, drips, splashes, portal and spell effects: depth-correct and lit by
  ours. Check the green-squares source here.
- Entities, block entities (chests, signs, banners, shelves, pots), dropped items, item frames, the hand, breaking
  cracks, the block outline: each checked in route captures; entities cast shadows into atlas through a proxy.
- Every item in the inventory checklist ticked.

Gate: the checklist, from route captures, plus an owner test at night with fire and torches.

### Stage 5: vanilla chunks off, and scale

- Vanilla's chunk meshing and far sending off. The near shadow map, entity visibility and block-entity lists come
  from atlas.
- Stress scenes and the frame bar; adaptive quality on the integrated GPU.

Gate: frame time ≤ vanilla at its default distance; the owner flies every stress scene.

**Then, in the owner's order:** far settlements (the overlays agent's far stage), far life (R5), entities as bricks
(ATLAS-WORLD-STORE §3), multiplayer, baked light, weather.

## 5. How the work runs (cost)

- **One worker agent on the current stage**, plus a read-only reviewer on every new GPU kernel. No parallel fronts.
- **Commit small and often**, with WIP commits allowed on the stage branch, so a usage limit never strands work.
- **Every agent prompt names the stage gate**, and the agent reports the gate's numbers, not a narrative.
- **Gates run by me**, in the game, through the route and the gallery. The owner tests at the gates of stages 2, 3, 4
  and 5, not between them.
- **Branch:** stage work lands on atlas-render. Old worktrees (atlas-features, -overlays, -content, -water, -step1)
  are parked; nothing merges from them until its stage.
