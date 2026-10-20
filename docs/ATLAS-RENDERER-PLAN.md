# Infinite ATLAS: owning the renderer

The owner (2026-10-20): own the renderer, all of it, now, before any world, seed, biome or weather work. One
renderer from the player's feet to the horizon, no near and far; everything a frame draws, derived from vanilla's
own source so nothing is missed; beautiful at rest and in motion (the Minecraft movie's look, a Nanite-like system
under our name), smooth, never jittery; entities and effects at distances no game shows, real-time far changes
(watching a player build a home kilometres away). We own the shader pack (Iris packs replaced by ours) and replace
mods that conflict. Other dimensions wait for the world rework after weather. Decisions are made by quick
experiments; work continues until complete, then pauses before weather or biome work.

## How we work

- **The loop is seconds.** One element (a chunk, a tree, a town) already exported, rendered from a distance by
  atlas_render or the bench: a change shows in seconds. Shaders reload while a renderer runs (atlas_render watch,
  and in game); Java hotswaps into a running client. A relaunch is for structural changes only.
- **Verification is targeted.** The check that matters for the change, once (one element, one metric, or a side by
  side); a milestone gets a small fixed set of scenes; the end gets the full pass. No tests for tests, no suite that
  grows with every change; a new check replaces a slower one or is not added.
- **Agents in parallel** where work splits cleanly (separate files or worktrees), merged by me.
- **Off-goal findings are parked** below, not chased.

## Phases

Each phase ends with its exit check; the next phase starts when it passes.

### R0. The loop and what is in flight

- Shader hot reload: atlas_core rebuilds a pipeline when its Slang source changes (atlas_render `watch=1`
  re-renders on change; the game client polls in dev builds).
- One-view command: an element at one distance, one image and its score, under 2 s.
- Java hotswap into the live client (JDWP agent on the dev launch; tools/dev/hotswap.py's client mode).
- In flight: the vanilla light without a pack (done, 7b23ca08), coarse levels from real blocks (agent), the white
  glow on load (measure the sky-visibility accumulation on new surfaces, start it from the coarser level's light),
  the far field's phantom landmarks and reserved home plots (data the renderer is given wrongly).

Exit: a shader edit is on screen in under 2 s, offline and in game.

### R1. The inventory

Every item the 26.3 client frame draws, from vanilla's source (GameRenderer, LevelRenderer and its passes, feature
and entity renderers, every block entity renderer, particles, sky, weather, overlays, post chains, light texture,
fog), each with what it reads (depth, light, fog, section visibility) and its disposition: ours, vanilla composited
into our frame, or later. Also what depends on vanilla's terrain without drawing (entity culling, map colours,
render distance). The checklist lives in this document and is the definition of done.

### R2. One terrain renderer

Experiments first (each a measurement, then a decision recorded here):

- **E1 near cost**: block-exact near terrain traced (the far field's trace at LOD 0 with the game's textures)
  against rasterised meshes, at 1080p and 1440p on the 3090 and on the CPU backend; also vanilla's and Sodium's
  frame time for the same view. Decides trace, raster, or both by distance and hardware.
- **E2 vanilla off**: stopping vanilla (and Sodium) terrain building and drawing: public API only, or the smallest
  mixin set; what breaks (entity culling, client chunk tracking).
- **E3 handoff**: our colour and depth into the game's framebuffer so vanilla's entities, block entities, particles
  and hand draw into our frame, hidden by our depth.
- **E4 entity light**: vanilla's core shaders overridden by our resource pack (no mixin) to light entities,
  particles and block entities with our light and shadows; or our own entity pass.

Then the build: the game's own block textures and animated sprites as the far field's materials (one material
near and far: the colour gap goes), biome tints, cutout and translucent (water, glass, ice) near, block and sky
light from the world's light data, AO, emissive, breaking cracks and outline, caves (sections underground recorded
and drawn: no haze past render distance), vanilla and Sodium terrain removed. Detail chosen per piece by screen
error, not by ring.

Exit: no line anywhere; a block looks the same at 2 m and 2 km (side by side and the element tier); frame time at
or under vanilla's at its default distance.

### R3. Light and look

One light for terrain, entities, particles and block entities: sun and moon shadows at every distance, traced sky
light and bounce, far point lights, held lights, exposure and tonemap. The look: art direction toward the movie
(soft saturated light, atmospheric depth, rich water, bloom), not a pixel match of anything. In motion: temporal
resolve with motion vectors (no ghosting, no shimmer), level changes invisible while moving, no white glow on load.

Exit: still and moving captures of the stress scenes (below), judged side by side; no visible pop, shimmer or glow
in a fly-through.

### R4. Our shader pipeline

The post chain, water, sky, clouds, fog, underwater and in-fluid effects, all ours; Iris and the pack removed from
the instance; Sodium removed if R2 replaced it. Mods that conflict are replaced.

Exit: the instance runs without Iris and Sodium, at least as fast and better looking than with them.

### R5. Far life

Far players, creatures, ships and carts as impostors that become the real model as they near; far lights and
far particles (chimney smoke, fires, spells, explosions); far changes in real time: the server streams edits and
live state to clients by interest (view and heading), so a player kilometres away building a home is seen doing
it. The renderer's side and the minimal server interest stream; the full client-owned world is world work.

Exit: a second client building far away is visible in real time; far creatures and lights at night.

### R6. Scale and hardware

Adaptive quality per machine (GPU, integrated GPU, CPU-only), budgets for memory and build time, GPU builds in game,
the stress scenes: long ocean, high mountains, dense forest, deep caves, night with many lights, the Imperial City,
a fast flight across a province.

Exit: every stress scene within its frame budget on the 3090 and on the integrated GPU, and usable on the CPU path.

### R7. The final pass

The full checklist from R1, every item checked live; the owner flies it.

## Experiments and decisions

(Recorded as they are made: what was measured, the numbers, what was chosen.)

## Parked (off the renderer goal, for later)

- Client-owned world: x-ray from a client that generates the world (the public and secret split decides it), the hash
  tree, the edit log, anti-cheat consequences (ATLAS-STREAMING.md).
- World generation: the seed decision, order dependence, plans for every site, biome layer, weather data
  (WORLDGEN-REVIEW.md).
- Other dimensions (with the world rework, after weather).
- Lootr takes our containers by default; ore regrowth shared by all; mob levels from the nearest player.
- Docs that disagree with the code (TAMRIEL.md, WORLD.md, WORLDGEN-PIPELINE.md, GPU-WORLDGEN-PLAN.md).
