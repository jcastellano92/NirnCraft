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

### R1. The inventory (done: docs/ATLAS-RENDER-INVENTORY.md)

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

- **R0 loop (2026-10-20).** A shader save reaches a scored side by side in about 2.5 s (slangc -O1 1.9 s, the
  reload and frame 0.5 s): `py -3 tools/dev/shader_watch.py` beside `element_lab.py <element> --renderer far-game
  --truth exact --view 128 --watch`. Without watch, one view is 3.6 s cold.
- **R1 inventory (2026-10-20)**: docs/ATLAS-RENDER-INVENTORY.md, 80 items from the 26.3 sources, with each item's
  hooks.
- **E1 near cost (2026-10-20).** The far field's trace at the Falkreath town export, 1920x1080, Vulkan, the camera
  in the street: the groups (block shapes) 21-30 ms a frame, the plain voxels 1.35 ms (whole blocks, no shapes), no
  textures either way. Tracing block-exact detail up close costs 15x what it should: **near terrain is rasterised**,
  far terrain traced, with one material and one light so the change of method does not show. The meshes are
  vanilla's own section meshes (exact models, mod blocks, fluids, smooth light and tint) drawn by us: the mesher can
  be replaced later where it limits (distance, LOD), the look is ours from now on.
- **R2 stage A (2026-10-20, d7c96778).** AtlasNearPass: vanilla draws no terrain (its visible sections are taken at
  RenderFrameEvent.Pre and given back at AfterLevel), we draw its meshes into a G-buffer (albedo with tint and AO,
  block and sky light, reverse-Z depth) through a pipeline modifier and light them in a resolve that also composites
  the far frame and writes the frame's depth. Live A/B with vanilla's own light curve: mean |diff| 2.6 of 255, 6.6 % of
  pixels over 8 (fade-in, particles), 60 fps both. Sodium and Iris leave the dev instance (Sodium replaces the section
  renderer; Iris needs Sodium); the harness's pack variant is off until R4.
- **R3 so far (2026-10-20).** Temporal resolve (d5b84712): our frame resolves into our own target after atlas (which now
  renders right after the G-buffer), 8 Halton jitters shared by the G-buffer's projection and atlas's rays,
  reprojection by depth and camera motion, neighbourhood clamp; mid-strafe captures clean. One camera: vanilla's bobbed,
  hurt-tilted projection for atlas, its near depth and the sky (the far field swam against the near terrain when
  walking). Far materials (merge of d65a3f38: the game's tint sources for far trees and grass, snowy conifers spruce
  green with half snow, coarse voxels by what is seen from above): spruce dE 6.5 -> 2.3 at 64 m, 6.0 -> 1.3 at 256 m.
  The far field starts past the loaded chunks (no predicted trees floating beside the real ones). Near water: the far
  field's water look (Fresnel sky, glint, waves). Lightmap in our look for the hand; atlas's clouds while it lights the
  frame. Harness: stale dev-overlay copies dropped at launch (one crashed the client).
  Milestone review (10 scenes, ours vs vanilla, Haiku): no defects at High Hrothgar and Winterhold; the horizon is
  crisp where vanilla fogs it (LOOK_HAZE x2.5 since); far hill tops read brown in places (medium-low, open); lamp glow a
  little weaker at night (low); far fields missing right after a teleport were capture timing, not the renderer.
  Underground live (after the merge of atlas-underground): a carved test tunnel under Falkreath with sunlit shafts:
  atlas's traced light reaches the pit and fades along the tunnel; the resolve's sky-light cap dropped for traced
  light (it took 81 % in a tunnel's first 48 blocks); chunks vanilla re-meshes are read again, so a shaft cut after
  load lights the floor within 5 s. Arrival frames: our light untraced (mode 2) while atlas streams a new place (they
  had switched to vanilla's look); shadows still come and go during those seconds (open).
  Still open: terrain in water reflections, shadows on forward-lit things, eye exposure from the frame, the aurora over
  our night sky, OIT, caves (agent), far life (R5), scale (R6).
- **R2 stage B live (2026-10-20, 2002d7db, 70f3dae9).** Falkreath, live A/B against vanilla: one light near and far
  (atlas's near light: traced sun shadows under the well, the buildings, the barrels), the look's sky fill (shadeSurfaceAmb
  LOOK_AMBIENT 3, LOOK_BOUNCE 0.25: readable cool shade, warm sun), no line where near meets far at the seam view, torch
  pools and moonlit shade at night; the sky ours (the far field's model; not opened with the eye at night). Fixed on
  the way: near-pass pixels past nearFieldEnd were traced as far and their colour landed in the resolve's inscatter
  (grey leafless spruces, dirt tiles over the near pass's); nearest filtering on the terrain atlas. The dev loop in
  game: shader_watch --game reloads atlas's kernel in the running client (atlas-shaders folder) and stages a fresh core
  (atlas-dev); Java bodies hotswap (client_hotswap). The 01:42 crash: the context destroyed under the far-field worker
  at shutdown (55ced956).
  Frame time (live, Falkreath, 600 frames each, vsync 60): ours median 16.6 ms p95 25.8, vanilla 16.9 / 25.1; render
  thread CPU 3.6 vs 2.4 ms (our second prepareChunkRenders and the resolve). On by default where Sodium is absent.
  Open (R3 list): the hand (drawn after AfterLevel: vanilla light), clouds (NirnCraft's deck, vanilla-lit), the far
  field's snowy spruces read whitish, NirnCraft's aurora weaker over our night sky, shadows on forward-lit things
  (entities, water), water reflections near, eye exposure from the frame (canonical now), OIT path not owned.
- **R2 stage B design (2026-10-20).** One light: atlas lights the near pass's pixels with the far field's own code
  (ABI 0.15 near light: the G-buffer's normal and light levels in, irradiance and aerial perspective out; sun with
  traced shadow, traced sky, far lights), the resolve multiplies the full-resolution albedo by it, adds vanilla's block
  light, caps sun and sky by vanilla's sky light where atlas may lack a cave's sections, and puts near and far through
  one display (the pack's eye exposure, night vision, ACES). What is drawn after the resolve takes the same light
  forward through pipeline modifiers pushed while we own the frame (E4 answered without mixins): translucent terrain
  (near_forward) and every pipeline on vanilla's entity shader (near_entity: mobs, players, block entities, items).
  Not yet: the hand (drawn after AfterLevel), particles, the sky (vanilla's pass, NirnCraft's sky over it), shadows on
  forward-lit things.
- **E2 vanilla terrain off, no mixin (from the inventory).** Wrap every block model (`ModelEvent.ModifyBakingResult`,
  a delegate whose parts are empty when the getter is a `RenderSectionRegion`): sections still compile, so
  entity visibility (it needs the section at the entity's feet compiled), block entities (listed by compiled
  sections), cave culling and the loading screen keep working, and nothing is drawn. Vanilla water and lava cannot
  be replaced that way; clearing `levelRenderer.visibleSections()` at `RenderFrameEvent.Pre` and restoring it at
  `AfterLevel` drops their draws too. Render distance 2 is not a route (it cuts the server's entity tracking to 32
  blocks). Sodium replaces vanilla's section renderer, so it leaves the instance with this step; the harness's
  readiness gate (rendered-section counts) changes with it.
- **E3 handoff (from the inventory).** Depth is reverse-Z (far = 0) and the projection reaches 2048 blocks; our
  terrain writes colour and reverse-Z depth in `AfterOpaqueBlocks` (the stage the far field uses), so entities,
  block entities, particles, translucents, clouds and weather that follow depth-test against it. Today's fallback
  writes no depth (things behind far terrain are not hidden): fixed by the same step.
- **Near light (2026-10-20, atlas_core ABI 0.15).** One light near and far: with `ATLAS_FRAME_NEAR_LIGHT` (0x20000) the
  near pass hands atlas its surface info (`AtlasGlFrameIO.nearInfoTex`, RGBA8: block and sky light, octahedral normal)
  beside its depth, and atlas lights every covered pixel with the far field's own functions (key light x N.L through
  traced shadows and cloud shadow, traced sky plus floor, far lights; block light stays the host's) into
  `dstNearLight` (RGBA16F: irradiance on white, sun visibility), its colour there becoming (aerial inscatter,
  transmittance) so the resolve does albedo x light x T + inscatter. Both interop modes (host copies read the near
  inputs back) and the CPU backend. Checked: far surfaces fed back as the near field give the far field's own colour
  (median relative error 5.6e-4, the normal's 8 bits), CPU and Vulkan agree. Cost on the 3090 at 1920x1080, zero-copy,
  64 % of the screen near (looking down from 60 blocks): +0.5-0.57 ms GPU (3.7 -> 4.3 ms; atlas_gl_test).
- **One material near and far (2026-10-20).** element_lab's `far-lit-game` (atlas's own light on the game's far
  materials, no cards) against `far-lit` (the same light on albedo cards: the textures) is the materials alone. Fixed at
  the source: far materials wear the tint the near field's tint source gives (AtlasMaterials.tint: the biome's grass and
  foliage, spruce and birch constants, none where a block has no source, as NirnCraft's snowy spruce leaves): grass,
  ferns and vines were grey, and the far grown trees' walls (a crown's sides, AtlasTrees / AtlasSettlements) were
  untinted spruce leaves, grey (dE 33 against the near's green: the whitish far spruces); the dressing's snowy conifer
  canopy was pale grey (sRGB 153,168,163, dE 30 against a snowy pine's crown), now spruce green with half snowy leaves
  (74,97,80), plain conifers the spruce leaves' mean x 619961; a coarse voxel took its commonest child, the buried one
  (dirt under grass, a hillside's stone), now the commonest of those seen from above (world.cpp shownKid). atlas_export
  mirrors the game (tint sources from quads.bin; only the grey texels of tinted quads: it had tinted a grass block's
  dirt and spruce by the biome, part of the before). dE cards vs game materials, 64 / 256 / 1024 / 4096 m, CPU:
  cave-mouth-near-tree (spruce) 6.5 / 6.0 / 6.0 / 6.3 -> 2.3 / 1.3 / 2.1 / 3.8; whiterun-day-chunk 7.1 / 5.2 / 5.7 /
  6.6 -> 3.9 / 2.4 / 3.9 / 4.4; falkreath-wide-town 2.5 / 1.4 / 6.0 / 10.3 -> 2.5 / 1.2 / 5.1 / 10.4;
  falkreath-wide-tree 3.5 / 2.7 / 2.5 -> 2.9 / 1.8 / 2.0. Left: from ~4 km a coarse voxel shows one material's face
  tiles (no colour mips; the town's 50 px still 13 dE inside). far-game's growth against `exact` (15 at 256 m, 25 at
  4 km) is not materials: against `intended` (16 spp) it is 12.9 / 15.6, the 1-spp reference's aliasing and the vanilla
  light's emulation.
- **Underground (2026-10-20).** Atlas held only surface sections (the server's from 16 under the lowest ground), and
  none under the near field (held until the camera left), so the near light traced caves as the tiles' stone and the
  far trace past the loaded chunks started in it. Now every chunk the client loads goes up whole except deep solid rock
  (AtlasClientSections; the server records the same: AtlasSections, re-recorded on edits), deduplicated per section,
  never held; FILL_NEAR also underground once the eye's 3 x 3 chunks are up. Under solid cover (solidRoof) the sky rays
  reach 64 voxels, a far hit counting only if it is under cover too, unbiased (the clamped control variate made closed
  cave walls glow), and no aerial inscatter with the eye under cover too (ATLAS_ABLATE 512 = before). Measured
  (test_main caves: a tunnel 30 under ground opening into a sunlit pit; near-light irradiance, the ambient floor
  0.037): deep inside 0.214 -> 0.062 (sun 0), inscatter 6e-4 -> 0, first 24 blocks 0.076, the pit 1.86 (0.29 / 0.05
  without the cave's sections); past a 24-block near field the far trace meets the tunnel at its own depth on 100 % of
  pixels (0 % with surface sections only: tile stone at the hand-off). The resolve's sky-light cap still takes 81 % of
  the light atlas traces in the tunnel's first 48 blocks (vanilla's sky light is 0 from 6 blocks in): what dropping it
  would give back. Near light cost on open terrain (atlas_gl_test, GPU shared with the game): +0.56-0.62 ms ablated,
  +0.70-1.09 ms with the roof rays.

## Parked (off the renderer goal, for later)

- Client-owned world: x-ray from a client that generates the world (the public and secret split decides it), the hash
  tree, the edit log, anti-cheat consequences (ATLAS-STREAMING.md).
- World generation: the seed decision, order dependence, plans for every site, biome layer, weather data
  (WORLDGEN-REVIEW.md).
- Other dimensions (with the world rework, after weather).
- Lootr takes our containers by default; ore regrowth shared by all; mob levels from the nearest player.
- Docs that disagree with the code (TAMRIEL.md, WORLD.md, WORLDGEN-PIPELINE.md, GPU-WORLDGEN-PLAN.md).
