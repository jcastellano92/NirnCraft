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
  R5 far edits live: a 70-block diamond column built 500 m away (force-loaded chunks) showed in the far field within
  about 15 s (the server's journal pushed 35 tile updates; atlas-edits-why), drawn from the column summaries (sides in
  the column's under material) until the section re-record reaches it. Far plains around Whiterun turn orange-brown
  once the exact tiles arrive (agent investigating).
  Intermittent (open): under the Solitude arch a black patch over lit terrain in 3 captures of 6 (two of them fresh
  launches, one 10 s after a teleport); not the near light (debug views: lit, no NaN), not the water's forward pass;
  gone after a resource reload and in later launches. Next time: the debug views at once.
  Still open: shadows on forward-lit things, eye exposure from the frame, the aurora over
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
- **Real ground a block low (2026-10-20).** Far plains turned orange-brown where the server has chunks (Whiterun once
  the change feed re-put its tiles up, hill caps where the review had been): the feed's columns (AtlasEdits.read) took
  `LevelChunk.getHeight - 1` as the ground, but a chunk's getHeight is the top block already (26.3 bytecode:
  getFirstAvailable - 1; Level.getHeight is the free block), so every real column was the dirt under its grass, a block
  low (harness world, tile 0,0: 48,253 of 51,744 grass columns journalled as dirt at y - 1). Not the materials: the
  tile cache's tops are grass in the biome tints, the sections' tops grass (id 9), and atlas_core draws a shaped grass
  block's top green at 300-800 m on CPU and Vulkan (ATLAS_FRAME_DEBUG_ALBEDO, groups: dirt texels 2-6 %, the sides).
  Fixed at the source; the journal is edits-v3 (a v2 journal keeps its lights, its chunks are journalled again as they
  load), and a patched grass top wears the tile's biome grass (it took grass_block's untinted id: grey), other
  biome-tinted blocks their grid cell's colours.
- **One bounce (2026-10-20).** Under an overhang (an arch's underside, a gorge's walls, eaves) the near light was near
  black: a sky ray that met a surface within reach brought back nothing, and the look's constant bounce (LOOK_BOUNCE)
  scaled by the sky seen is ~0 there. Now in the near light a sky ray that meets a surface brings back that surface's
  light as the look draws it (skyVisible / bounceFrom): albedo (palette) x (key light x N.L through one more secondary
  ray from the hit, cloud shadow, plus its sky x LOOK_AMBIENT where open: always with the point in the open, under
  cover only where the hit is lit or has no roof over it). The constant stays only for the rays that met nothing, so
  nothing counts twice; under cover a far hit sends its own light where it counted as the sky before. Measured
  (test_main overhang: a slab 4 over sunlit ground, a wall in the open; caves; near-light irradiance, before / after):
  the slab's underside 0.16 / 0.41, a shaded wall in the open 1.08 / 1.18, open ground 4.416 / 4.416; deep in the
  cave 0.062 / 0.037 (the ambient floor alone: the constant had leaked in under the floor), the tunnel's first 8
  blocks 0.255 / 0.116 (the pit's walls send their light, not the sky's), its first 24 0.076 / 0.043, the pit 1.86 /
  2.02. The Solitude arch from beneath (atlas_render nearlight=1, the solitude-sea export, noon): the underside's
  luminance x1.85. Cost (3090, 1080p, idle GPU): near light +0.16 ms (4.77 -> 4.93 ms the frame), the far pass
  unchanged; the far field keeps the constant (bouncing its level-0 pixels too cost +1.8 ms, 43 % of the far pass),
  so a shaded wall at the hand-off is ~8 % lighter near than far. ATLAS_ABLATE 524288 = before.
- **R6 CPU path in game (2026-10-20).** Without a usable Vulkan device (or -Dnirncraft.atlas.backend=cpu) atlas_gl_attach
  failed on the CPU backend, so the far field went off and the near pass lost its light. Now the CPU backend takes the
  same GL calls with the same textures: the near pass's depth and info are linearised on the GL side at atlas's
  resolution (gl_near.hpp, shared with the Vulkan path), read back through a persistent pixel-pack buffer and a fence,
  rendered on a worker (all hardware threads but two, below normal priority) while the game draws on, converted to
  half floats there and uploaded when done; the first frame after an attach is rendered in the call. The textures lag
  the camera (2 frames at best, ~7 at 60 fps below), so the resolve takes no haze where atlas's texel is unlit there
  (Look.w; no black or far-coloured fringe), and shadows and the far edge trail in fast turns (open: reproject by the
  shown frame's camera). The sun map is uploaded too (pack path). Atlas's scale on CPU follows the worker's time, not
  the GPU controller: a third of the window to start, the cheap light (FRAME_LIGHT_LOW) then a smaller scale over
  25 ms, at most 0.55 (AtlasQuality.observeCpu). Measured (atlas_gl_test, i9-10900K 20 threads, 1920x1080 window,
  atlas 640x360, 64 % of the screen near and lit): worker 66-72 ms a frame traced, 41 ms with the cheap light (the
  near light's traced sky is ~40 %), convert 1.5 ms; render-thread cost per frame 0.2-0.4 ms (p95 1.9; readback copy
  0.2, upload 0.2); 12 ms of render-thread work beside the worker takes 12.9-13.0 ms. ATLAS_CPU_GL=sync: 70-79 ms
  on the render thread. Sync and async frames match the host render (0 pixels differ; colour, near light within half precision).
- **The near light at half resolution, with its own history (2026-10-20).** Profiled live in Falkreath (1280x800,
  RTX 3090): atlas 7.3 ms with the near light, 2.3 without; hot-reloaded ablations: the bounce 3.5 ms, the sun ray 3,
  far lights ~0.3; a cone (a coarser level every 32 or 8 voxels a ray goes) saved 0.4, so the cost is the ray count,
  not the distance. Now one pixel of each 2 x 2 is traced a frame (rotating with the TAA's phases), the rest filled by
  depth and normal, and a reprojected history of ~8 frames blends it (the host's TAA could not: its clamp throws the
  one-ray noise out). Atlas 2-4.5 ms there; a still view's pixels moving by more than 8 levels frame to frame 6.9 % ->
  0.9 % (the owner: "no flickering"). atlas_gl_test: full trace exact against the host, the split unbiased and 0.0 %
  apart over 8 x 8 blocks. Roof tests are never cones (rock overhead at a coarse level could be open).
- **Black sunlit walls were atlas's stand-ins (2026-10-20).** Probes (inside-solid at the ray origin, solid 0.5 / 1.5
  blocks out and in) showed the walls' voxels a block thick in front of the drawn ones and the houses solid: the tiles'
  stand-in buildings, because the client's real chunks went to atlas in arrival order at ~1 a second (a teleport left
  the camera's chunks behind 3,200 others). Now nearest the camera first, ten times the share until the eye's chunks
  are up, and a near point still inside a solid voxel is lit untraced (the floor alone with the eye under rock). Open:
  the stand-in buildings are a block wider than the built ones (far silhouettes too).
- **The worker idles (2026-10-20).** Every uploaded section rebuilt its regions at levels 0-4 in full (one 40 ms update
  ran 38 s; a third of a core in a still scene). Levels 1-4 now wait for the uploads to pause 2 s; the voxeliser caches
  its section lookups. 4-7 % of a core after load. Still not zero: make a still scene cost nothing.
- **Owner direction (2026-10-20): GPU-first.** Scene building (regions, levels, ingest, groups) on the GPU like the
  tracing; the CPU is the fallback. The direction: a GPU-built structure in the NAADF manner (Ulschmid et al., CGF 2026:
  nested 16^3 chunks / 4^3 blocks, per-cell axis-aligned distance fields; their numbers: 3-5x the SVDAG's rays per
  second from the nesting, ~10x with the distance fields, cheap edits). Step 0: ablations of foliage and the height mip
  per ray (an hour); step 1: a level-0 nested grid + AADF built in compute from the raw sections, used by the near
  light's rays, A/B'd live; then the other levels; then primary rays near, which is what makes near and far one
  spectrum instead of raster near and atlas far (primary near rays measured 21-30 ms at 1080p with the SVDAG).
- **Step 0 and the GPU-built level 0 (2026-10-20, branch atlas-naadf).** Step 0 (Falkreath export, 1280x800, the near
  light's frame, interleaved best-of-40; ATLAS_ABLATE bits 21-26, atlas_render nlframes / nlcount): 5.0 / 3.9 ms, 0.9
  without any rays; no height mip at level 0 -6 / -9 %, foliage free 0 %; per secondary ray 6.4 traceRegion steps, 37
  height-mip probes, 7.2 node loads, 0.44 solid voxels, 0.08 foliage passes: not foliage, the per-ray traversal's loads.
  Built (voxgrid.hpp, atlas_voxgrid.slang, traceVoxGrid): the raw sections in a GPU pool, a 64 x 44 x 64 window of
  chunks (state + 5-bit x6 empty boxes), 64 blocks a chunk (2-bit x6 boxes, 64-bit bitmaps), at most three dependent
  loads a step; compute kernels build a section's blocks and regrow the chunks' boxes over a summed-volume table; the
  CPU backend builds the same words in C++. traceLod's level 0 takes it first, the SVDAG past it. Primary hits match
  the SVDAG level 0 within 0.002 blocks (Vulkan and CPU; above a real column's top the grid has air where the tiles had
  stand-ins). 1080p primary + material to 256 blocks: 2.0-3.1 ms vs 3.6-6.2 (groups off); with shapes and the groups'
  look at level 0: 4.1 vs 11.1 ms (groups on), to the horizon 6.8 vs 15.2. Near light: 3.7 vs 6.3 ms. GPU build: 4173
  sections 5.4 ms in all, a 77-section batch with the boxes regrown 0.22 ms; 147 MB at 16384 slots. Then: the grid may
  own level 0 (ATLAS_VOXGRID_LEVEL0: no CPU level-0 rebuilds for its sections), 16 x 16 textures sampled at level-0
  hits, and ATLAS_FRAME_PRIMARY_NEAR / _LIT: the near field from atlas's own primary rays (atlas.h). Open: levels 1+
  still CPU (World::putSection's generated levels are ~35 us a section, the bulk of a section's CPU cost), the shapes'
  per-hit cost, vanilla block light (far lights stand in), the groups' coarse levels still on the CPU.
- **Shadows are traced but faint at low sun (2026-10-20).** A sun-visibility view at time 11600 shows the trees' and
  houses' long shadows; the final image barely does. The ambient (LOOK_AMBIENT 3 -> 2 -> 1.5) hardly changes it (the
  exposure follows); the key light at a 15-25 degree sun is weak against the sky (the evening reads blue and flat, not
  golden). For the owner: the look's key-to-sky balance near sunset. Far shadows exist (A/B: the far forest band and
  hillsides darken by them) at the hit's own level.
- **The air (2026-10-20): one physical atmosphere** for the sky, the sun's colour and strength, the haze and the sky's
  light, near and far (native/src/air.hpp the reference; atlas_air.slang, include/atlas_air.glsl the ports with its
  constants). Rayleigh, Mie (Cornette-Shanks) and ozone over a spherical planet, single scattering marched (sky 24
  steps, a path to a surface 8), the higher orders from Hillaire's 32 x 32 LUT, the sun's transmittance analytic
  (Chapman, erfcx form). The world is a 1 : 2.5 model of its air (AtlasAir.airScale: the haze strength LOOK_HAZE had).
  The key light is the sun through the air at the point's height; the sky light the air's own sky over the hemisphere;
  LOOK_AMBIENT 3 -> 1. The game's sky: AtlasSky marches the GLSL port into a 192 x 108 sky-view LUT each frame, the sky
  and the water's reflections look it up. Regional variation is what is in the air: AtlasClimate (the eye's column:
  temperature, humidity, aerosol band; ClientWeather's dust, ash, snow, mist; rain; the coast) -> atlas_air_from_climate
  (an external mixture of aerosol kinds, the water-loving ones swelling with humidity). Measured (clean air, the eye 60
  over the ground): the key light at 2 / 6 / 15 / 35 / 70 degrees (0.51, 0.11, 0.01) / (1.56, 0.77, 0.24) / (2.50,
  1.88, 1.21) / (3.03, 2.73, 2.34) / (3.22, 3.09, 2.90); a 10-degree sun's key x sin : sky on a flat 1.52 (lit : shaded
  2.5 : 1; the old look 0.42 with its LOOK_AMBIENT 3: the blue flat evening); zenith at 35 degrees (0.080, 0.154,
  0.354), the horizon at the sun at 2 degrees (3.9, 1.0, 0.12). Humid air (AOD 0.39) and dusty (0.50) dim and redden a
  low sun far more (10 degrees: key x sin : sky 0.28 / 0.13). Checks: atlas_pack_test runs the game's GLSL on GL against
  atlas's sky (worst 2.4e-3 relative); tools/dev/air_check.py the same formulas in numpy against atlas_air_sky (median
  2e-5; through the LUT p99 0.6 %). Cost (3090 shared with the game, 1080p, A/B one run): the far frame 5.05 -> 5.34 ms;
  CPU backend 640 x 360 85.6 -> 104.3 ms, with the cheap light 75.3 -> 84.6 ms. The daytime moon: its lit face added
  over the sky (the silhouette drew a dark disc on the blue). Open: the air is the eye's (per-cell air for far haze
  waits for the weather work), a new air rebuilds the LUT on the render thread (~10 ms, at most every 3 s), dusk is at
  the exposure clamp from a 6-degree sun down (physical, darker than before: a live judgement), the Iris pack keeps
  its old sky (retired at R4).
- **Dev loop: a shared-constant change freezes the client 30-60 s** while the driver rebuilds the six pipelines; the
  live control heartbeat times out meanwhile (actions fail: retry after the reload).
- **Open from the owner's questions (2026-10-20):** entities cast no shadows (they receive them: the shadow map's
  casters are the terrain only); flowers and other plants probably cast none (not solid in atlas's voxels: check);
  light shafts into caves and rooms (the air under cover is unlit: volumetric shafts), through clouds later; GI from
  many sources (torches and emitters bounce nothing yet; the far field has no traced bounce): voxel GI in the NAADF /
  VXGI manner (emissive voxels, spatio-temporal reuse) once the GPU structure exists.

- **Water: the owner's list (2026-10-20), paused until atlas is GPU-built** (every live test waited on the CPU feed):
  flowing water poor from every angle; falls are not water (white water, the flow continuing under the surface at the
  foot, rapids); flow on and under the surface, eventually pushing players and items; depth darkness (drop-offs dark,
  the shallows bright: deep views still read bright); shafts must be volumetric and follow the waves above, caustics
  aligned to the ripples and as strong as the light and the weather allow (they show but faint); remove the dark
  underwater particles; the shore: transparent shallows, a wet edge, foam only where waves lap (no glowing band),
  shadows on the water, reflection falloff (the owner's photo: a dock on clear shallow water); per-biome underwater look
  (green lily pond, teal timber lake, glacial blue, tropical turquoise over white sand, tea-brown jungle, deep ocean
  blue) with long blends at biome borders; looking out of the water from just under the surface.
- **For atlas's near shading (owner, 2026-10-20):** ambient occlusion at contacts (vanilla's baked AO is lost under our
  sky light, and goes away with the raster: atlas must trace it, sharp at contacts, not blurred by the half-resolution
  pass); thin plants (flowers, grass) lit as thin translucent things, from both sides and through their leaves, not flat;
  the shore and water edge; waterfalls from the real fall height and flow (the white-water rule on side faces also hit
  the steps between flowing water's levels: off).
- **Atlas draws everything, GPU-built (2026-10-20, the main track).** The near field is still vanilla's chunk raster
  with atlas past the render distance: the line the owner sees, and after a teleport atlas lacks the real blocks for
  minutes (~1 chunk/s CPU feed): no tree shadows, light that does not match. The fix: sections to a GPU pool, the voxel
  structure built in compute per changed section, atlas tracing all terrain near and far with continuous level of
  detail; vanilla keeps entities and effects, composited by depth. With the client owning the world (it generates;
  the server sends coalesced operations by position and need), atlas builds from the client's own data.

- **The atmosphere and the light: the owner's roadmap (2026-10-20).** One optics everywhere; what the look depends on is
  what is in the air, how it is layered, and whether clouds catch the low light (not biome palettes, not a sunrise colour).
  Have (the air merge): Rayleigh / Mie / ozone, the sun's beam filtered through the path (white at noon, orange at 6
  degrees, red at 2) lighting land and water; twilight glow after sunset from the planet's shadow; climate-driven aerosol
  mixes (dust pale or brown, humid milky, smoke a red sun over a dull sky, clean air saturated bands). The sun's glare
  (a sharp core and a wide halo in the sun's transmitted colour, dimmed by what hides it). Volumetric beams near.
  To build:
  - crepuscular and anticrepuscular rays: the sky's scattering shadowed by the terrain along the view ray (the sun map),
    light shooting up past the mountains as the sun sets behind them;
  - the sun's disc near the horizon: refraction flattening and distortion through the layered air (the size itself
    stays; its glow and haze grow);
  - clouds as the screen: thin and broken clouds lit by the reddened beam, pink undersides after sunset (with weather);
  - layering: night inversions trapping haze low (morning milky inside the layer, clear above), daytime mixing, rain
    washing aerosols out (weather drives the air model's inputs);
  - foliage near: light through leaves (translucency), rim light on leaf edges against the light, shadows softening with
    the foliage depth the light crosses (atlas's rays measure it); water's diffusion likewise;
  - every light: the drawn sky (aurora, stars, moon glow) integrated into the ambient (an aurora night tints the snow),
    the moon as key at night (have), torches and lamps bouncing (voxel GI on the GPU structure).

- **Night: the owner's notes (2026-10-20).** Moonlight is filtered light, the aurora emitted light; contrast decides what
  shows. The moons (Masser, Secunda: each its phase and height) light the land through the air like the sun (orange low,
  whiter high, dimmed by dust and haze; a new moon or one below the horizon lights nothing); moon beams and moonlit
  shadows exist but are faint: any torch, lantern or interior light overpowers them (keep the real ratio: night exposure
  may open up to read moonlit terrain, but never flatten torch against moon). The aurora: its own colours from the
  gases high up (oxygen green and red, nitrogen blue-purple), unrelated to the biome below; strong moonlight washes out
  faint displays, a strong display lights snow when the moon is down (the drawn sky into the ambient). Snow throws
  moonlight back brightly; a canopy keeps the floor dark but for its gaps; ridges lit, valleys in the moon's mountain
  shadow. Low light: rod vision (faint aurora greyish to the eye, colour returning as it brightens): a desaturation in
  very low light, a look choice. Order of influence for a night's look: darkness and cloud, whether a moon is up, its
  phase and height, the aurora's strength, then haze and the land's reflectivity or cover.

- **Physical light with pre-exposure (next lighting foundation, 2026-10-20).** The owner asked why moonlight is
  compressed: it is (the moon ~1/30 of the sun, torches only ~40x a full moon, since 01105c5a ~160x), because a screen
  cannot hold the eye's million-to-one range and our RGBA16F buffers cannot hold physical moonlight (about 1e-6 of
  daylight: below half precision's useful range). The fix: physical values everywhere (sun, moons by phase and height,
  starlight and airglow, torches at about 1/1000 of daylight, atlas's far lights the same) and the eye's exposure
  applied while shading (pre-exposure: the stored light always in a displayable range), the eye's real range and speeds
  (rods over minutes), night vision by luminance. Moonless near black, a full moon dim and grey after adaptation,
  torches dominating by their real ratio, out of a lit town into a moonlit forest blinded for a moment.

- **One field, live (2026-10-20).** `nirncraft.atlas.unified=true` (mode A, ATLAS_FRAME_PRIMARY_NEAR_LIT): atlas traces
  every pixel from the camera; the resolve takes its colour and depth and adds only our air (beams, glare, crepuscular,
  the medium, under water); vanilla's opaque chunk meshes are no longer drawn. Every land hit at any distance takes the
  same light (traced sun, sky, bounce, haze): no second model past nearFieldEnd. Past the host's far plane atlas writes
  minus the distance (the horizon no longer reads as sky). Near water is the host's raster (atlas's primary rays pass it,
  a re-trace with water skipped). Found on the way: AtlasSky capped the eye's exposure by writing into the shared look
  array (white skies), SkyCube cleared inside the water's render pass (a crash). **Stuck at spawn:** the grid's window
  never refilled after a move (fixed, 83b22b54) and the far-field worker's native update ignores its 40 ms budget after
  a jump (18 s + 12 s in one pass while 1,700 read chunks and 13,000 server sections waited): being fixed.
- **The owner's direction for the field (2026-10-20):** no near and far planes at all, one adaptive field; updates by
  where the camera is, how it moves and where it looks: near and in view first, then out of view, occluded, far. Next
  after the budget fix: the update order by frustum, distance and occlusion; levels 1-4 built on the GPU from the grid;
  mode B (the G-buffer, our resolve lighting atlas's surfaces) as the default; vanilla's chunk meshes removed (shadow map
  and translucents from atlas).
- **Inside things (the owner, 2026-10-20).** When the camera clips into something it must not see through it, and it
  must look right: inside dirt or stone close darkness with depth (no x-ray into caves), inside leaves a dense dappled
  volume of particles of leaf, inside water, lava, glass or powder snow their own media at their opacity, by the block's
  real bounding box (a slab, a fence, a pane). Only while clipping: the world is never hidden otherwise. Built on our
  media (atlasMedium) with atlas's primary ray starting inside the voxel: the chord through solid as an absorbing
  medium. The same volume path later carries fog banks, snow fields and rain shafts (WEATHER-WORK.md hooks).
- **Levels 1-6 on the GPU, the continuous level, leaves (2026-10-20, branch atlas-naadf).** The grid has coarse
  levels: the same structure with voxels of 2^k blocks (voxgrid.hpp), one window per level. They are built on the GPU
  from the sections' raw ids: a section's levels 1-4 by the mip vote (vgIngestMain), levels 5-6 from the level below
  (vgDownMain). A chunk is known once every section column it covers has come. The CPU builds those levels' regions
  from the tiles alone, as the fallback, and a section no longer rebuilds them or is digested.
  - Offline jump (groups on, budget 40): settled in 1.3 s at A (it was cut off at 60 s) and 0.53 s after the jump
    (was 41 s). A 768-block walk took 72 update calls, mean 0.8 ms, max 20 ms (CPU levels: 2.7 / 36 ms, and it never
    settled).
  - The grid now takes 242 MB in all (level 0 alone took 147).
  - Every ray's level is continuous, never a ring. It is the larger of log2(footprint) and the residency level,
    which reaches k + 1 at level k's window radius. The footprint is widened by 1 / cos at the hit, at most 2x.
  - Each pixel picks floor(level + xi), xi being IGN rotated by the golden ratio under TAA. Shadow, sky and bounce
    rays use the same rule through the pixel's cone (lodFromFootprint, lodPick, lodResident in atlas_far.slang).
  - Cost (interleaved medians): +27 % at 1080p and +5 % at 640x360. It comes from SIMT divergence: one threshold for
    the whole frame costs nothing, one per 8 x 4 tile +3 %.
  - A still camera keeps 0.0046 MAE of stipple after 4 samples, which TAA has to resolve. Walk and turn frame
    differences are 5 % below the rings'.
  - Leaves: a cut-out cube's entered face is tested against its 16 x 16 texture's coverage, so sky shows through the
    gaps. The unified light adds the key light through the crown.
- **Nirn's sky (docs/NIRN-SKY.md).** Masser and Secunda as custom baked bodies with relief and cast shadows, eclipses
  and the Dark Moon, stars placed as the birthsigns (no lines), the named stars, the Dominion planets on orbits, comets;
  all by place and date, lit physically. Two agents on it.

## Parked (off the renderer goal, for later)

- Client-owned world: x-ray from a client that generates the world (the public and secret split decides it), the hash
  tree, the edit log, anti-cheat consequences (ATLAS-STREAMING.md).
- World generation: the seed decision, order dependence, plans for every site, biome layer, weather data
  (WORLDGEN-REVIEW.md).
- Other dimensions (with the world rework, after weather).
- Lootr takes our containers by default; ore regrowth shared by all; mob levels from the nearest player.
- Docs that disagree with the code (TAMRIEL.md, WORLD.md, WORLDGEN-PIPELINE.md, GPU-WORLDGEN-PLAN.md).
