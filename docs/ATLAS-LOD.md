# Infinite ATLAS: one representation, generated levels

Owner's requirement (2026-10-19): going from near to far nothing may change what it is. A tree stays a tree (not a
stone pillar), a house keeps its shape and its charred beams, props, fire blocks and scatter stay, and the further out
the rougher the stand-in, never a different thing. It must hold for everything (terrain, ground cover, all flora, town
scatter, paths, water, dungeons, above and underground) and must not depend on someone hand-making a far version of
each object.

## The rule

**Every object enters the far field as its real blocks; every coarser level is generated from the level below by one
set of rules.** No per-type stand-ins (boxes for houses, cones for trees, columns for chunks) except where a type has
no blocks to give (a far-away place nobody has generated, see Sources).

### Level 0: real blocks

The far field's finest level is block voxels, exactly what the near field draws. Where the server has a chunk, its
sections are the truth. Where nobody has generated the land yet, the same generators the server would run produce the
blocks into a capture level (trees: done, AtlasTrees; buildings, scatter, dungeon entrances: AtlasCapture with each
piece's builder; terrain: exact heights and the material rules).

### Coarser levels: generated

A voxel of level k+1 is made from its 2 x 2 x 2 children:

| children | the coarse voxel |
|---|---|
| solid (terrain, walls, roofs, props, trunks) | solid when at least 3 of 8 children are solid (keeps thin walls and trunks as silhouettes, closes doorways only when they are under half a voxel) |
| foliage (leaves) | foliage with a density = the share of leaf children; the far shader passes rays through it in proportion (gaps, dappled light), so a crown thins as it coarsens instead of going solid |
| water | water when at least 4 of 8 |
| emissive (fire, lamps, lava, crystals) | an emitter registered as a far light (its glow and the light it casts), and its voxel takes its non-emissive neighbours' material |
| material | the most common material among the solid children, ties to the topmost (what is seen from above) |

Sub-block things (grass, flowers, ferns, snow layers, carpets, torches, small props under a block) are not voxels: they
become **surface attributes** of the voxel under them (cover share and colour, snow, a light), averaged the same way,
so a meadow keeps its colour and its drifts at every distance.

### Underground

Real sections carry caves, dungeons, mines; their levels are generated the same way, so a cave mouth stays open as far
as it is bigger than a voxel. Predicted land has no caves except the openings its structures declare.

## Sources, by distance

| where | level-0 source |
|---|---|
| chunks the server has (around players, where anyone has been) | their sections, pushed by the change feed (docs/ATLAS-DELTAS.md) |
| land not generated yet, within the fine windows | the generators into a capture level (same code, same seed) |
| beyond (coarse levels only) | exact heights + material rules + generated tree / building / scatter silhouettes from the same placements |

## Specifications every object type must meet

1. Its level 0 is its real blocks (or what its generator produces into a capture level).
2. Its coarser levels come from the rules above: no hand-made far version unless the rules cannot express it, and then
   it is a documented exception in this file.
3. Lights it emits are far lights (emission, colour, flicker kind).
4. A harness check exists for it: near view vs far view of the same object at 60 m, 200 m, 600 m (fidelity pairs), the
   shape metric within the limits of the level.

## What other renderers teach (owner asked, 2026-10-19)

| renderer | idea | here |
|---|---|---|
| Nanite (UE5) | every mesh's LOD hierarchy generated automatically with an error bound; levels chosen by screen-space error, swaps below a pixel | section mips (this file); choose the level by projected error, cross-fade levels (dither + TAA) |
| Lumen (UE5) | every object gets an automatic simplified proxy (distance field + "cards" capturing its real materials) for far tracing; a surface cache updated incrementally, changed and most visible first, within a budget | proxies captured from the real object, never hand-made; the change feed + view-prioritised, budgeted rebuilds |
| octahedral impostors (Fortnite, Horizon, Far Cry) | trees and props past mid distance as sprites pre-rendered from the real model from many angles | flora: impostors baked automatically from each species' real blocks (exact leaves, gaps, trunk) from ~200 m to a few km; voxel mips beyond |
| Distant Horizons (Minecraft) | columns with several vertical segments, not one | coarse columns with several spans (overhangs, arches, canopies over ground, cave mouths) |
| Voxy (Minecraft) | voxel LOD from real chunk sections, mip by mip, GPU-driven, stored on disk | the architecture of this file |
| Lumen radiance cache | world-space probes shared near and far | a shared probe grid (sky visibility, bounce light) so both fields are lit by the same light |

## Plan of record (research 2026-10-19: Distant Horizons, Voxy, Aokana, Teardown, GigaVoxels, HashDAG,
## transform-aware SVDAGs, Heitz & Neyret coverage, UE 5.7 Nanite foliage voxels)

What the others do: Distant Horizons keeps columns of vertical spans and gets real trees and structures only by running
the real generators on the CPU (slow, the "features" / "internal server" modes); Voxy mips real sections with "any
non-air wins" (crowns go solid and fat); Aokana's 2-of-8 vote (ours 3/8) loses thin things (a 1-block trunk); Epic moved
far foliage from impostors to Nanite voxels. The principled mip is **coverage**.

1. **One mip kernel with per-axis coverage** (GPU, shared by real sections and templates): from level 1 each voxel keeps
   its majority material and three 4-bit coverages (how much of it blocks a ray along x, y, z); the ray passes the rest
   by hash (as leaves already do). Nothing thin vanishes, nothing fattens, levels blend without pop. Level chosen so a
   voxel is under a pixel, cross-faded by the existing dither + TAA. (~1 week)
2. **Object templates, not column stamps**: every object variant captured once with AtlasCapture into a small voxel
   volume (building pieces, landmarks, dungeon entrances, N variants per tree species), kept in a GPU template pool with
   its mip chain from the same kernel, deduplicated as DAG subtrees. (~1-1.5 weeks)
3. **Placement the GPU evaluates**: per region an instance list (template, position, rotation / mirror, tint); trees from
   the existing pure seed-and-position placement on the GPU heights; towns, sites, dungeons from their deterministic plans
   (a few kB); a stamp pass writes template level k into the region's bricks at level k. Speed of terrain. (~2 + 1 weeks)
4. **Openings**: template voxels are don't-care, solid or carve (forced air); stamped terrain -> carve -> solid; gates,
   doors, tunnels, cave mouths, dungeon shafts are carve volumes and carve coverage mips like everything else. (~1 week)
5. **Exact trees**: port the tree builders that cover most biomes to Slang with the RNG reproduced, checked against
   AtlasTrees in the harness; templates for the rest. (~2-3 weeks)
6. **Near = far where chunks exist**: real sections are authoritative per 16^3 (the stamp pass skips them); both go through
   the one kernel, so level k of the real object equals level k of its template. Harness: near vs far IoU per object
   type at 60 / 200 / 600 m. (~1 week)

Steps 1 and 3 remove the pillars, the filled holes and the cut-off tree line; step 5 gives exact one-to-one trees.

### Level -1: block-state micro-voxels (owner, 2026-10-19: "break objects into smaller voxels, detail where needed")

Minecraft's content is already voxels: what makes a near object look as it does (a slab's half height, a fence's post,
a leaf's cutout, its texture) is a property of its **block state**, not of the object. So:

1. A library built once at load: per block state a 4 x 4 x 4 occupancy mask (one uint64, exactly the SVDAG's leaf
   brick) from its real shape (collision / model shape; leaves and other cutouts from the texture's alpha) and a 4 x 4
   colour tile per face sampled from its real texture (plus roughness, emission, transparency class). A few hundred
   bytes a state, every state of every mod, automatically.
2. The world (real sections, templates, edits) stays **block ids**: objects are stamped as the block ids their
   generators produce; memory is a block world's; an edit is one id and the bricks above it.
3. The ray descends into a block's mask and samples its face tile only where the block covers more than a few pixels
   (the hand-off ring and nearer): true shapes and texture colours where the screen can show them, one averaged voxel
   where a block is about a pixel, the coverage levels beyond. No per-object micro storage, nothing spent where unseen.
4. The G-buffer the pack lights gets real face normals, colours and classes: shadows, far lights, TAA and grading work
   unchanged.

Order: the block-state library and block-id sections first (everything else builds on them), then the coverage
kernel, templates and GPU stamping, carve volumes, exact tree ports.

### Continuous levels, one representation near and far (owner, 2026-10-19)

The goal beyond the plan: not 4 levels but a continuous one, the same for every kind of thing (no per-type far
versions), chosen per pixel from the player's position, motion and view, so that the far field is the near field seen
from further away. If it is exact enough up close it can draw the near field too, with Minecraft's meshes kept only
where they are needed (interaction, block entities, entities, animated and translucent layers), and then there is no
seam to hide. It also gives sub-block detail up close (level -1) that block meshes cannot.

How it is continuous: the storage has levels (powers of two of the micro-voxel), but the level a ray uses is the
pixel's footprint as a real number, and a ray picks the finer or the coarser of the two nearest levels in proportion
(hashed per pixel and frame), so TAA blends them: level 2.3 is 70 % level 2 and 30 % level 3, and moving changes the
mix smoothly. It only works if neighbouring levels agree (same silhouette, same colour seen from each side), which is
what the coverage kernel is for, and the experiments below measure.

## Experiments: the test bed (fast iterations)

Inputs exported in game, once: `/nirncraft-atlaslab blocks` (the block-state library: every state's 4x4x4 shape, its
6 face tiles from its real textures, class, tint, emission) and `/nirncraft-atlaslab here <name> <radius> <down> <up>`
(a box of real block state ids around the player); the autotest verb `atlaslab ...` is the same; the harness scene
`atlaslab-export` makes them at Falkreath. Then `native/build/Release/atlas_lab.exe blocks=... volume=... out=sheet.png`
renders the object at 10 m .. 1.5 km at the game's real pixel size (1080 rows, fov 70) with each representation, one
column each, against `ref` (micro-voxels, 64 samples a pixel), in a second or two on the CPU; it prints iou (soft
silhouette), dE (converged colour, CIE76), dE1 (one frame: shimmer in motion), steps (voxels visited per ray: the GPU
cost proxy). Harness: `atlaslab-falkreath`, `atlaslab-falkreath-wide` (lab tier; they come `after` the export, which
joins the run while its files are missing; a new export is a new fingerprint).

First result (a synthetic tree on a grass slab, before real exports):

| at 1500 m | iou | dE | steps |
|---|---|---|---|
| current (kept blocks, 3-of-8 vote) | 0.83 | 12.4 | 4.0 |
| micro-voxels, no mips | 0.97 | 2.0 | 8.4 |
| cov (per-axis coverage + side colours) | 0.84 | 13.6 | 4.2 |
| covbox (cov + the content's box per voxel) | 0.94 | 3.6 | 4.4 |

| boxes (adaptive tight boxes with masked face textures, below) | 0.95 | 3.6 | 1.1 |

**Adaptive boxes** (owner, 2026-10-19: "smaller boxes where detail is needed, large ones where it is the same,
rectangles not only cubes, textures for the sub-parts"): an octree where each node keeps the tight box of its content
(a rectangle: a wall's node is a thin slab, a ground plane one flat box) and its flattening error (how deep the
content lies under its box's faces). A ray draws a node as one box whose faces are masked textures (the first
content under each texel, holes where there is none) when that error is under the pixel there, else descends. Same
quality as covbox at a quarter of its steps (at 200 m: 1.4 steps a ray against micro-voxels' 9.8). The cost moves to
memory: each drawn box needs its face textures at its footprint's resolution (a mip chain of surfaces, like Lumen's
cards); measuring that is next. Fog: an error of e shows as e times the transmittance, so the threshold may grow as
it falls; 1/transmittance is too strong (covbox at 1500 m in fog 0.0012: silhouette 0.52, though dE stays 3.3), a
gentler curve is to be tuned.

**Content-shaped groups** (owner, 2026-10-19: "like Lumen, objects are many boxes whose grouping changes; trees and
structures group when it makes sense; chunks do not matter, only groups that scale with distance"): the hierarchy
follows the content, not a grid: each group splits in two at the plane whose children's tight boxes have the least
surface, so a wall comes away whole from the tree beside it and groups cross objects and chunks (far away one group
is a stand of trees and the ground under it). Synthetic tree:

| | hierarchy | boxes drawn at 200 / 1500 m | 1500 m iou / dE / steps |
|---|---|---|---|
| boxes (octree) | 74 k nodes | 911 / 44 | 0.948 / 3.6 / 1.1 |
| groups | 9.6 k nodes | 764 / 27 | 0.970 / 2.1 / 1.5 |

Groups are smaller, draw fewer boxes and are the most faithful far representation so far (micro-voxels: 0.971 /
2.0 / 8.4). Up close (10 - 50 m) the binary tree costs more steps (31 at 10 m against 10.5): there the last levels
should be dense bricks of micro-voxels (a hybrid), which is the next variant. A GPU builds this kind of hierarchy
in milliseconds (LBVH from Morton codes), so groups can be rebuilt as the world changes.

**Real data (Falkreath, exported 2026-10-19: 36,954 block states, a 65 x 51 x 65 core and a 129 x 61 x 129 area).**
The synthetic tree misled once: groups split by surface alone peeled thin slices off a real town (114 k nodes, 25 -
237 steps a ray); weighting each side by its content (the real SAH) fixed it (77 k nodes, 3.4 - 25 steps, built in
2.2 s). Wide area:

| | 200 m iou / dE / steps | 1500 m | 4000 m | hierarchy |
|---|---|---|---|---|
| current | 0.993 / 4.8 / 104 | 0.967 / 6.8 / 11 | 0.935 / 8.2 / 6.1 | - |
| micro-voxels | 1.000 / 0.4 / 105 | 0.994 / 1.3 / 22 | 0.984 / 1.8 / 22 | - |
| boxes (octree) | 1.000 / 0.4 / 10 | 0.989 / 2.1 / 1.6 | 0.974 / 3.3 / 1.5 | 12.9 M nodes |
| groups (SAH) | 1.000 / 0.4 / 18 | 0.990 / 2.0 / 2.6 | 0.971 / 3.2 / 2.3 | 260 k nodes |

Fly-in from 2.5 km to 60 m over the core: no representation pops beyond its noise; dE over the flight current 4.6,
boxes 1.3, groups 1.3 (micro 0.9). Finalists: boxes and groups (same quality; groups 50x smaller and draw about a third
fewer boxes, boxes take about 60 % of the steps). Next: both in Slang (one source, CPU for exact comparisons, GPU for
real timings), and ref against Minecraft's own view of the same blocks (the export scene's capture) at 3 - 10 m.

**Smooth between groupings** (owner, 2026-10-19: "all we see is more detail resolving: a pixel becomes a group of
pixels, then a window"): measured by a fly-in (`sweep=24 dist=40,2000`: the camera from 2 km to 40 m through one
window; pop = how much more a representation's picture changes per step than ref's, beyond its own frame noise).

| | pop (mean / largest) | dE vs ref over the flight | steps |
|---|---|---|---|
| current | 0.07 / 1.55 | 5.8 | 10.1 |
| micro-voxels | 0 / 0 | 0.9 | 10.1 |
| covbox | 0 / 0 | 1.4 | 10.5 |
| boxes | 0 / 0 | 1.4 | 2.3 |
| groups | 0 / 0 | 1.1 | 4.6 |
| groups, prefiltered faces | 0 / 0 | 2.2 | 4.6 |

The dithered switch (each pixel and frame draws a group whole or splits it around the threshold) plus TAA is enough
for no visible pop; groups stay closest to ref. Prefiltering the face textures over the pixel's footprint blurs
twice (TAA's jitter already averages the pixel), so it is off; it may still earn its place for single frames in fast
motion (dE1), to be measured with a moving-camera TAA.

**Moving camera with a real TAA** (`motion=1`: short moves at game speed, 0.5 blocks a frame forward and 0.3
sideways, history reprojected by hit distance, clamped to the 3 x 3 neighbourhood, 10 % blend; against ref converged
at every frame; synthetic tree):

| | 60 m dE / flicker | 200 m | 600 m |
|---|---|---|---|
| ref through the same TAA (TAA's own floor) | 2.0 / 2.1 | 4.2 / 2.9 | 7.5 / 3.5 |
| current | 2.9 / 3.0 | 6.3 / 3.5 | 8.5 / 3.3 |
| micro-voxels | 2.0 / 2.1 | 3.6 / 2.8 | 6.7 / 3.2 |
| boxes | 2.0 / 2.1 | 3.6 / 2.8 | 6.5 / 3.2 |
| groups | 2.0 / 2.1 | 3.6 / 2.9 | 6.5 / 3.2 |
| groups, prefiltered faces | 2.0 / 2.1 | 3.7 / 2.9 | 7.4 / 3.5 |

In motion boxes and groups shimmer no more than exact micro-voxels; most of what remains is TAA's own lag (ref
scores the same), a TAA question, not a representation one. Prefiltered faces are worse in motion too: dropped.

**Ground truth against Minecraft itself** (2026-10-19; harness atlaslab-falkreath-near: the vanilla capture's own
camera, the offline reference of the same blocks with vanilla face shading and smooth-lighting AO). Geometry matches
(houses, path, bench, trees, torches in place). Colour dE 6.2 over the 87 % of the frame the exported blocks cover;
blurred 4x 4.8, blurred 16x 3.1, lightness within 0.8: the average colour and brightness already match, and most of
what is left is texture detail (the 4 x 4 face tiles against Minecraft's 16 x 16 texels). Block-entity visuals
(campfire flames, chests) are absent from the reference: they are drawn by code, not models. The 1 km view
(atlaslab-falkreath-view) measures today's far field in the game's own view; its first run compared foggy, wrongly
tinted pictures, so the export scene now captures with fog-off (no vanilla fog, no atlas haze) and writes the view's
tints.

**What each comparison means** (owner, 2026-10-19). Ground truth exists only where Minecraft itself renders: the
truth suite (tools/harness/truth.py) judges colour and outlines against the exact reference (atlas_truth: Minecraft's
own mesh, offline) only within the capture's render distance (minus a chunk), with entities and particles removed from
the capture (truth-quiet: the reference draws neither) and the world frozen through it. Beyond the render distance
the live picture is today's far field, the thing being replaced: its error against the real blocks is information
(liveFarDE), never a verdict. Holes and fog walls (sky or a flat fog colour over real blocks) are failures anywhere.
The new far field is judged against the reference out to the exported view (1 km): does it represent the real blocks
(dE by distance band), and how does it resolve as the camera approaches (the fly-in: no change beyond the reference's
own, no pop, no shimmer). Further out, where nothing can be ground truth, it is judged on stability, smoothness,
absence of artefacts, and updates when blocks change.

**Reference modes and vanilla quirks** (owner, 2026-10-19: "some ground truth is worse too... careful"). The exact
reference has two modes. *Exact* is Minecraft as it renders, quirks included; it is used only to check the reference
against the live game (the near comparisons, 1.24 dE at Falkreath). *Intended* is what our renderer is scored against:
16 samples a pixel (true coverage: an 11 x 20-block pine at 8 km covers a fifth of a pixel; exact mode shows nothing,
so Minecraft flickers it in and out while moving, while the grouped representation fades it out smoothly, IoU 0.94 -
0.996 against intended from 8 m to 32 km), plus fixes of vanilla quirks, each written here, one at a time, with a
before / after, never silently:
* sub-pixel coverage (adopted): intended = supersampled.
* light through stairs and slabs (candidate, not adopted): vanilla lets sky light leak through stair and slab ceilings
  (a dungeon hall's stair ceiling shows bright stripes; the exact reference reproduces it, dE 0.44 against the game).

A third mode, *realistic* (`--truth realistic --time T`), scores our renderers under the shader pack's light rather
than Minecraft's: below, "A realistic reference".

Verification tiers (owner, 2026-10-19): 1. elements offline (tools/harness/element_lab.py, suite:elements: one chunk or
tree cut from an export by `box=`, the intended reference vs our renderer from 8 m to 64 km and two fly-ins, ~40 s an
element, now ~12 s: one process per renderer renders the whole ladder and both fly-ins); 2. the same elements against
Minecraft's own rendering, live, pinned (suite:pinned, final); 3. full scenes offline (suite:truth's lab scenes); 4.
full scenes live, last. Live captures only when a tier needs new inputs, and every new camera position is vetted by a
cheap visual review before it is relied on. An element fails below IoU 0.9 (where it covers 16 px or more), with a
dot's coverage off the reference's by more than 25 % or 0.25 px, or above 0.1 fly-in pop.

**Texture resolution up close** (ground truth, 2026-10-19): the 4 x 4 face tiles are right far away but flat up close
(the cobble path, planks); owning the near field needs faces at the texture's own resolution (16 x 16), stored once
per texture (an atlas), not per block state.

**Surface cards: colour and light from the real faces** (2026-10-19). `atlas_truth bake=x.alc` writes a card for
every face of every block (inside `box=`): the face seen straight on, 16 x 16 texels of what the game draws there
(texture, tint, shade, AO, the lightmap at its light; sky and block light kept per texel for relighting). `atlas_lab
surface=x.alc` takes every hit's colour from them through a mip chain picked by the pixel's footprint (trilinear).
This is the surface cache in its simplest form: captured once from the real models with the real light, then the
grouped voxels only decide shape and coverage.

Correction (2026-10-19, later the same day): until 694bde66 atlas_lab's camera frame put its ref variant (plain
micro-voxels) at out= whatever was asked, so the first table written here measured micro-voxels with cards, not the
groups. The camera frame now renders the variants named, and element_lab checks the log that the scored frame is the
one asked for. With the groups themselves, cards sampled as the game samples its atlas (nearest when magnified,
bilinear and trilinear when minified: nearest texels at every level had been twice as sharp as the game from 30 to
300 m, which read as fly-in pop), translucent water composited over what lies under it, and thin blocks shaped by
the visual hull of their face tiles, over the 54 elements of today's exports (suite:elements, renderer:lab-groups):

| | median | worst |
|---|---|---|
| near dE (32 m or 1.5 radii) | 0.84 | 5.3 (a stepped chunk) |
| worst dE where the element covers 16 px or more | 4.1 | 11.8 (falkreath-wide-tree, 2-4 km) |
| lowest IoU (16 px or more) | 0.95 | 0.73 (trees at 2-4 km) |
| slow fly-in pop | 0.008 | 0.375 (riften-day-water) |

What fails: coverage at 8-16 km (the groups' flattened faces cover a column whenever anything lies along the face's
axis: 30-90 % too much for a dot of a pixel or two; the prefiltered faces, groupsf, cover 15-35 % too little and lose
colour), trees in the middle distance, and fly-in pop on small odd models (a campfire as a glowing cube, a chain).
Before the correction the bench's own 4 x 4 tiles under a fixed sun gave a near dE of 29 on the Whiterun chunk.

Leaves (`leaves=cards`, the default): whole voxels whose cards' alpha is the cutout (per texel up close, the mip's
coverage further away). The bench passes through a transparent texel along the face's own axis (the next block in
the column), not along the ray, so a crown's ragged fringe against the sky is solid: the far pass's tracer continues
the ray itself. The far pass takes its colour the same way (cards from the real quads, light kept apart so day,
night and carried lights relight them).

**Elements by kind, and the far field itself in the tier** (2026-10-19). element_lab finds per export its centre chunk,
its biggest tree and featured chunks: the chunk with the most water, lights (emissive), built materials and custom
(non-vanilla) blocks, each when it has enough. Every element has two scenes in suite:elements: the bench
(`element-<slug>`, lab-groups: the representation alone) and the far field (`element-<slug>-far`, far-groups:
atlas_core through atlas_render, what the game draws). An underground element (sky light near 0 on its exposed faces,
from its cards) is skipped with its reason (judge it from inside, in the scene tier): black against black agrees
and says nothing. A fly-in whose frames stop changing while the reference's change fails (a renderer that stopped
following the camera reads as perfect otherwise). New subjects' inputs come from `element-export-<subject>` live
scenes (suite:element-exports): the library and the box around a truth subject's pose, without the 1 km view.

What scoring the far field found, each fixed in the game, its native copy and the bench:

| found | effect in game | fix |
|---|---|---|
| snow layers not kept (AtlasSections.kept) | a far snowfield was the bare stone under it | snow layers and carpets kept |
| the thin-block floor layer took chains and rods | floating slabs under hanging lanterns | only blocks that cover the floor (Java: the outline's floor cover; native: the top tile) |
| sections sent 6 tiles out, kept 4 regions, never resent | the ring past ~1 km and everything after a trip away and back lost its real blocks | server window 4 tiles, forgotten when a tile leaves it (a return resends it); the core keeps one ring more |
| a ripe crop's outline is a full cube | a field of crops (wheat, garlic, cabbage) a solid block of green | crops and bushes with cut-out quads are plants |
| library exports keyed on world and packs only | a changed rule never reached blocks.bin | AtlasLabExport.LIBRARY_VERSION in the key |

Open in the far field: real elements vanish from ~8 km (real sections feed LOD 0-4 only), and coarse levels show
interior faces through walls at some transitions (High Hrothgar: fly-in pop 1.05): the coarse-levels work.

**An exact reference: atlas_truth** (2026-10-19). atlas_lab's ref is an approximation (4 x 4 x 4 shapes, 4 x 4 tiles,
hashed cutouts, no light), so it cannot be ground truth itself. `native/tools/atlas_truth.cpp` traces Minecraft's own
chunk mesh offline instead: `/nirncraft-atlaslab quads` exports every state's model quads as the mesher gets them and
every sprite at native resolution; `here` / `view` with a trailing `light` write ALV2 / ALS2 (sky and block light per
block, the model variant at each position, biome tints per column); atlas_truth renders a capture's camera with
culling, cutout and front-to-back translucency, fluids, NeoForge's smooth lighting and the lightmap (formats in
AtlasLabExport's Javadoc, what it does and does not reproduce in the tool's header). Synthetic checks:
`native/tools/atlas_truth_test.py`. Harness: `atlastruth-falkreath-near` / `-view` (scenes/truth.json). On the old
exports alone (its fallback) it already lowers the near view's liveDE from 6.3 to 5.6.

**A realistic reference: atlas_truth lighting=realistic** (2026-10-19). The exact reference lights like Minecraft
(smooth lighting, AO, the light map, no sun shadows), so it cannot judge our own lighting. `lighting=realistic` traces
the same mesh (the same quads, sprites, culling, cutout and translucency) and lights it the way the shader pack means
it, each term placed physically instead of read from light levels (`native/tools/pack_look.hpp` mirrors the pack:
`lib/atmosphere.glsl`, `lib/keylight.glsl`, `lib/exposure.glsl`, `lib/grade.glsl` nightVision, `final.fsh`):

| term | the pack (deferred.fsh) | the realistic reference |
|---|---|---|
| key light | sunRadiance (3.2 at noon, warm at the horizon); by night the vanilla moon opposite the sun, (0.035, 0.05, 0.09) | the same radiance; direction from `time=` by Minecraft's sky angle turned as Iris turns it (sunPathRotation -25): noon high in the south, sunset west |
| its shadows | shadow map (PCSS) x atlas's sun map x the light map's openness | a ray per sample through the real quads to a point of the sun's disc (0.27 degrees): hard at contact, soft further; cutout texels pass (dappled shade under leaves); occlusion is two-sided (the ground inside a leaf bush is shaded) |
| sky | skyAmbient(n) x skyLight^2 + floor, x SSAO | skyAmbient decomposed exactly: radiance skyUp over the horizon, ground under it; cosine-weighted rays (4 a sample, 64 a pixel) see either or are blocked: the visible hemisphere, no light levels |
| block light | the light map's block level through pow(l, 3.2) 1.8 + pow(l, 12) 2, warm (or the mod's coloured cache) | every emitter in the box a light: a point of its model's box (soft shadows), I cos / (d^2 + 0.5^2) x (1 - (d / R)^4)^2, I = 9.4 (level / 15)^2, R = level + 1 (Minecraft's reach): the pack's curve at 3 - 8 blocks from a level-15 light; colours from FogRadianceTexture.emitterColour at the warm light's luminance |
| emission | albedo x emission x strength (fire 5, lava 6, lights 4 x smoothstep of the texel's brightness) | the same, per texel |
| foliage | FOLIAGE_SSS: key x through x 0.6 (leaves) / 0.5 x (pow(v.l, 4) 0.8 + 0.25) x 0.5 | the same, through = what reaches past the crown's leaves (exp(-0.45 gap)) |
| bounce | none | optional (`bounce=1`): a sky ray that meets a surface takes its sun (shadowed), sky and block light |
| look | the eye's auto exposure, night vision, ACES, regional grade, bloom, shafts | the eye adapted to mid grey (0.18) under the open sky at that time (the pack's exposure curve: noon x0.85, dusk x4.0, night at its x4.9 limit), night vision (NIGHT_VISION 2), ACES, sRGB; per sample, as the bench's cards are; no grade, bloom or shafts |

Misses show a flat background: the pack's sky 20 degrees over the horizon, a quarter turn from the sun (`info=1`
prints it with the rest of the time's light, for the harness to give the renderers the same). Checked on synthetic
scenes with known answers (`native/tools/atlas_truth_realistic_test.py`, 16 checks, check-atlas-truth-realistic):
a 3^3 box's hard shadow on a floor against an analytic slab test (279 points, 0 wrong); the soft edge (0.05 rad sun)
lit / half / dark at -0.5 / 0 / +0.5 blocks; sky visibility at five floor points and a wall's face against a
numerical integral (within 0.004); leaves in the air and on the floor shade the floor under their opaque texels and
let the sun through their holes; a lantern in a closed room: no sky, no sun, the inverse square to 0.3 % at three
points, a pillar's shadow, the lantern glowing; one bounce reddening the floor at a sunlit red wall; the tonemap
against a numpy port of final.fsh; the sun's path at four times. The vanilla suite is unchanged. The reference's own
noise (16 against 64 samples a pixel) is 0.3 - 0.9 dE.

The element tier takes it as `--truth realistic --time T` (6000 day, 12500 dusk, 18000 night), the same
`camera=mc ... box= cameras=` interface and outputs. The renderers are lit the same way: lab-groups and far-groups
sample cards baked in that light (`bake= lighting=realistic`: 16 samples a texel), so they measure the representation
under realistic light; `far-lit` is atlas_core's own lighting (atlas_render `shade=pack`: its sun / moon, its shadows,
sky ambient, AO and far lights, on albedo cards (`bake= lighting=albedo`), the export's emitters registered as far
lights as the game registers a real chunk's (`lights=1`), through the same exposure and tonemap over the same sky).
Harness: suite:elements-lit (every element, three times, the three renderers; opt-in).

Element tier under realistic light (2026-10-19, after the bench fix 694bde66; 256 px at 1080p's pixel size, 16
samples a pixel; dE at 32 m / range 64 m - 2 km; pop fast / slow; the far field's losses from 8 km on are its window,
not light: real sections feed LOD 0-4 only):

| element | time | bench (lab-groups, realistic cards) | far field (far-groups, realistic cards) | far field's own light (far-lit) |
|---|---|---|---|---|
| whiterun-day-chunk | day | 3.5 / 1.7 - 2.3, pop 0 / 0 | 4.0 / 2.4 - 7.3, pop 0.03 / 0.16 | 6.2 / 4.9 - 8.7, pop 0.09 / 0.20 |
| | dusk | 2.6 / 1.5 - 1.7, 0 / 0 | 3.0 / 1.9 - 6.1, 0.03 / 0.08 | 9.0 / 8.0 - 10.9, 0.21 / 0.17 |
| | night | 2.4 / 1.0 - 1.4, 0 / 0 | 2.8 / 1.6 - 9.7, 0.06 / 0.11 | 7.7 / 6.6 - 7.5, 0.08 / 0.04 |
| falkreath-wide-tree | day | 2.4 / 2.5 - 6.3, 0.03 / 0.01 | 3.3 / 0.9 - 6.4, 0.05 / 0.01 | 4.1 / 3.2 - 8.2, 0.06 / 0.04 |
| | dusk | 5.6 / 4.0 - 7.4, 0.18 / 0.32 | 7.3 / 2.2 - 8.0, 0.25 / 0.41 | 7.1 / 5.5 - 9.9, 0.05 / 0.03 |
| | night | 1.2 / 0.8 - 3.4, 0.04 / 0.03 | 1.4 / 0.5 - 6.8, 0.05 / 0.06 | 1.5 / 1.1 - 3.2, 0.03 / 0.00 |
| whiterun-night-lights | day | 1.1 / 1.8 - 3.6, 0 / 0 | 2.1 / 3.1 - 6.7, 0.06 / 0.14 | 4.8 / 5.8 - 9.2, 0.26 / 0.53 |
| | dusk | 1.5 / 1.8 - 3.8, 0 / 0 | 2.6 / 3.2 - 6.2, 0.07 / 0.14 | 9.3 / 8.7 - 14.9, 0.28 / 0.40 |
| | night | 1.0 / 1.6 - 3.2, 0.05 / 0.04 | 2.2 / 2.8 - 6.8, 0.29 / 0.47 | 12.9 / 14.8 - 28.3, 2.3 / 2.5 |

IoU is the representation's and the same as under Minecraft's light: bench 0.985 / 0.729 / 0.937, far field 0.926 /
0.930 / 0.860 (worst where the element covers 16 px or more; the tree at 4 km on the bench, the lights' chunk at 4 km
in the far field fail the 0.9 line under any light). Under realistic light the representation holds up: the bench is
within 1 - 4 dE of the reference by day and night (the tree at dusk up to 7) and the far field's groups within
2 dE of the bench up close.
One new failure is the representation's, shown by the light: the tree at dusk pops 0.18 - 0.41 in both card renderers
(0.01 - 0.05 by day): cards baked under a low sun carry dappled, high-contrast texels, and the mip levels of a cutout
crown disagree more when its texels differ that much (the cards' mips average covered texels' colour, the reference's
16 samples average the light the gaps let through).

**Where atlas_core's lighting falls short** (far-lit against far-groups is the lighting alone: same groups, same
cards, the far field's light instead of the reference's). Each by an experiment, not by eye:

1. **The sky ambient is a constant 0.78** (`FAR_AMBIENT_OCCLUSION`, calibrated against near / far pairs as the near
   field's average sky occlusion), times a 4-neighbour AO. The reference's sky visibility is 1 on open faces and much
   less in crevices; the constant darkens every open surface (the day chunk's shaded sides L* 9.4 against 13.3). The
   reference with its sky and ground radiance scaled by 0.78 scores far-lit at 4.6 / 2.8 / 4.6 dE (32 / 128 / 512 m,
   day; from 6.2 / 4.9 / 6.4) and 5.5 / 3.7 / 5.6 at dusk (from 9.0 / 8.0 / 9.2), down to the representation's own
   error: the constant is most of far-lit's error on open land, worst at dusk when the sky is most of the light. The
   far field needs a sky visibility of its own (per group face, in the surface cache), not a constant.
2. **Foliage is opaque to light.** Shadow rays (the voxel path) and the AO taps see a crown as solid, nothing passes
   its gaps, and there is no light through leaves: the tree's crown is near black at dusk (L* 2.7 against 9.8 seen
   from the sun's side, 2.4 against 6.9 in the tier's view) and darker by day. The reference with foliage opaque to its
   secondary rays and no light through it (`foliagelight=opaque sss=0`, a diagnostic) and the sky at 0.78 gives the
   crown far-lit's L* (2.4 / 2.4 dusk, 1.1 / 1.0 day) and scores far-lit at 4.1 / 2.6 / 5.2 dE at dusk (from 7.1 / 6.2 /
   6.8). Shadows through a crown must pass its coverage (the groups' cutout cards already know it), and foliage needs
   the pack's light-through term.
3. **Block light is the light map's curve, not a light.** `farLightsOn` lights by the euclidean distance through the
   pack's block-light curve, with no facing term, keeping a quarter behind an occluder, so a lantern lights whole walls
   that face away from it, around corners and through them; and every emitter is also drawn as a far light's glow, a
   1.5-block sphere, also up close. At night the lights' chunk scores 12.9 / 15.8 / 14.8 dE (32 / 128 / 512 m); without
   their light on surfaces (ATLAS_ABLATE 8) 9.7 / 9.7 / 9.0, without far lights at all 6.4 / 7.8 / 7.3, and the fly-ins
   pop 2.3 / 2.5 as lights cluster and drop in and out of the budget. A real chunk's emitters need shadowed,
   cosine-weighted point lights near the camera, and the glow only where the light itself is under a few pixels.
   (The reference's own block-light intensity and reach are matched to the pack's curve at 3 - 8 blocks from a
   level-15 light; the live calibration below checks them.)
4. **Smooth normals from 96 m** brighten slopes the blocks do not have: removing them (ATLAS_ABLATE 2) lowers far-lit's
   dE at 512 m by 0.6 on the chunk (6.4 to 5.7) and 1.2 on the tree at dusk (6.8 to 5.6).
5. **Sun shadows add little where they could:** removing them changes far-lit's dE by 0.0 - 0.3 on these elements
   (the chunk casts little; the tree's crown shades itself solid either way, point 2). By night (the moon) and at
   dusk, the voxel shadows of the coarse levels are the open question for larger elements (a town, a ridge).

**Far light: traced** (2026-10-19, `atlas_far.slang`; ATLAS_FRAME_LIGHT_LOW keeps the light of before). Each shortfall
above replaced, measured with the element tier (far-lit, realistic reference, 16 samples a pixel; inputs frozen in a
snapshot of the exports, the "before" built from the commit before):

* *Sky visibility per surface* (1): the hemisphere a face sees, traced: one cosine-weighted secondary ray a pixel and
  frame through the voxel path, 16 voxels of the hit's level long (a cone widening with the level: eaves and crevices
  near, valleys and ridges far), its sample stratified over a 16-frame cycle (R2 per pixel), so a still pixel under the
  host's TAA converges to one fixed set. It is a control variate on the exact open-sky integral (skyAmbient: the
  cosine-weighted split of sky and ground radiance about the face): the rays estimate only what is taken from it, so
  open faces (most of the far field) are exact and noise-free. The pack's ambient floor (deferred.fsh) is added under
  every far land surface. One ray scores within 0.1 dE of two; half the reach (8) the same as 16 on these elements.
* *Secondary rays see the real shapes* (needed by every term): sun / moon shadows, sky and block-light rays pass foliage
  and cut-out plants by their coverage (the share of the shape's micro-voxels filled times their cut-out alpha; a
  crossing meets two faces of a block: an extinction of -2 ln(1 - c) a block; leaves without a shape FOLIAGE_COVER
  0.65, Minecraft's oak and spruce tiles), and walk a partial shape's 4 x 4 x 4 micro-voxels at level 0: a snow layer,
  a slab or a stair was a full cube to the light (a full block's shadow in every pit beside a snow layer, black faces
  where layers of two heights meet). The rays start at the hit nudged off its face (inside a shaped voxel's empty part
  when it must), out of a coarse level's solid along the face, and out of the column upwards where a drawn group's face
  lies inside the ground.
* *Light through foliage* (2): the sun's ray from a leaf carries the pack's light-through-leaves estimate
  (E[exp(-0.45 x)] of the first leaf texel along it, integrated over the crown's coverage, 0 behind a solid), and
  leaves take FOLIAGE_SSS (0.6 x (pow(v.l, 4) 0.8 + 0.25) x 0.5); the ground under a crown gets its dappled light as
  the transmittance.
* *Block lights as lights* (3): point lights, E = I cos / (d^2 + 0.5^2) x (1 - (d / R)^4)^2, I = 9.4 (level / 15)^2,
  R = level + 1 (the reference's), the cosine both ways on foliage, shadowed by one secondary ray a pixel and light to
  a point of the emitter that moves with the TAA's frames (soft), in the pack's block-light hue (real chunks' emitters
  arrive as orange fires; the orange stays their glow's); fires and lava light by day too. Screen tiles take a light
  wherever its sphere of light covers the frame (the 96 px cap cut a near lamp's light into a square). The glow is drawn
  only where the far field does not hold the emitter's block (a far town's windows, a block lost in a coarse level):
  the far field's own emissive surface draws the rest, which now glows texel by texel as the pack's light blocks do
  (4 x smoothstep(0.35, 0.8, luminance), never under the flat glow before).
* *Normals* (4): a groups hit keeps its true face normal at every distance (a slope is a staircase of tops and sides,
  and so are its blocks); the voxel path still smooths its coarse cubes.

| element | time | before (far-lit) | traced | the representation's floor (far-groups) |
|---|---|---|---|---|
| whiterun-night-chunk (the day chunk's blocks) | day | 6.2 / 4.9 / 6.4, worst 8.7, pop 0.09 / 0.20 | 4.1 / 2.4 / 3.5, worst 6.5, pop 0.11 / 0.30 | 4.0 / 2.4 / 4.1, worst 7.3, pop 0.03 / 0.16 |
| | dusk | 9.0 / 8.0 / 9.2, 10.9, 0.21 / 0.17 | 3.8 / 2.2 / 3.1, 6.0, 0.14 / 0.23 | 3.0 / 1.9 / 2.6, 6.1, 0.03 / 0.08 |
| | night | 7.7 / 6.8 / 6.7, 7.5, 0.08 / 0.04 | 2.6 / 1.4 / 1.6, 4.3, 0.03 / 0.05 | 2.8 / 1.6 / 4.3, 9.7, 0.06 / 0.11 |
| falkreath-wide-tree | day | 4.1 / 4.5 / 4.6, 8.2, 0.06 / 0.04 | 3.4 / 2.8 / 2.7, 6.5, 0.04 / 0.01 | 3.3 / 3.0 / 2.9, 6.4, 0.05 / 0.01 |
| | dusk | 7.1 / 6.2 / 6.8, 9.9, 0.05 / 0.03 | 5.5 / 2.6 / 2.4, 6.4, 0.05 / 0.02 | 7.3 / 3.7 / 3.2, 8.0, 0.25 / 0.41 |
| | night | 1.5 / 1.4 / 1.3, 3.2, 0.03 / 0.00 | 1.4 / 0.6 / 0.7, 2.7, 0.03 / 0.00 | 1.4 / 1.0 / 1.4, 6.8, 0.05 / 0.06 |
| whiterun-night-lights | day | 3.3 / 7.0 / 8.9, 8.9, 0.11 / 0.15 | 1.4 / 4.2 / 4.0, 5.4, 0.10 / 0.14 | 1.6 / 4.1 / 4.0, 6.6, 0.25 / 0.36 |
| | dusk | 6.8 / 11.8 / 11.5, 14.3, 0.48 / 0.37 | 2.4 / 5.9 / 5.5, 7.8, 0.17 / 0.28 | 1.6 / 4.6 / 4.5, 6.7, 0.13 / 0.23 |
| | night | 12.3 / 23.6 / 22.0, 26.5, 1.63 / 1.55 | 1.1 / 4.4 / 4.1, 7.8, 0.18 / 0.18 | 1.6 / 4.0 / 3.9, 7.1, 0.25 / 0.41 |

(dE at 32 / 128 / 512 m, the worst from 64 m to 2 km, fly-in pop fast / slow. The far-groups floor is the same far
field coloured from cards baked in the reference's own light: what is left once lighting is perfect. Where the traced
light beats it, the floor's cards carry baked dusk contrast that its mip levels average badly.) The vanilla look is
untouched: far-groups under Minecraft's light (shade=mc) renders bit-identical before and after.

Cost (RTX 3090, 1920 x 1080, the Falkreath view from 200 blocks up, near field's 192 blocks left out, Vulkan, the
traced and the cheap light in alternate frames of one process, `atlas_render interleave=light`; the GPU shared with a
game client, so ratios over 45 frame pairs): two sky rays at reach 24 1.82x the cheap frame (+4.3 ms on 3.4), one ray
1.48x, one ray at reach 12 1.41x; of that the sun's secondary ray is ~1 ms over the old binary one. The default is one
ray at reach 16; ATLAS_FRAME_LIGHT_LOW is the quality step for the host's controller.

Left: the chunk's fly-in pop (0.11 / 0.30 against the floor's 0.03 / 0.16, near the end of the slow fly-in: shadow
and sky detail resolving as groups open, of which shadows are about a third); the lights' chunk at dusk 1.3 dE over
the floor at 128 m; secondary rays still trace the voxel path (coarse levels are mip-voted cubes; groups for secondary
rays, or a surface cache per group face, are next); light through foliage uses one coverage per material, not per
texel; town and relief elements (element_lab kinds `town`, `relief`) exist for larger casters, not yet scored.

**Calibration against the pack itself** (to run, live): suite:truth-realistic. `truth-<subject>-pack` captures the
subject (whiterun-day, falkreath-dusk, whiterun-night, the lights-night stage) with the pack on, from the subject's
pose, time and world without its exports; `truth-<subject>-realistic` renders the reference in that capture's camera
from the subject's export and reports dE against it. What they disagree on is the reference's calibration (the
canonical exposure against the eye's, the block light's intensity and reach, the moon) or what the pack adds that the
reference leaves out (grade, bloom, shafts, SSAO), each to be written here before the reference changes.

**Pinned ground truth: Minecraft's own rendering at any distance** (2026-10-19; mod `client/pinned/PinnedTruth`,
harness `tools/harness/pinned.py`, `harness.py run --suite pinned`, the final live validation, run deliberately).
Minecraft unloads chunks past its render distance and clips at its far plane, so the truth suite ends there. A pin
keeps one element (a box: blocks, light with a one-block margin, blended tints, block entities, optionally its
entities) and Minecraft draws it wherever the harness puts it: its own SectionCompiler meshes the box from a
RenderSectionRegion that answers from the snapshot (models, NeoForge's smooth lighting and AO, tints, fluids, the
three layers), its terrain pipelines draw it (ChunkSection uniform per section, the block atlas with the chunk
layer sampler's settings, the lightmap; translucent through the OIT stages or the classic pass), and the entity and
block entity renderers draw the render states they extracted at the pin. Beyond 32 m the copy is drawn smaller and
nearer (scale s, the same picture: every direction from the camera is the same, every distance s times shorter, so
the same pixels, texture derivatives and mip levels, and the same baked per-vertex light); the capture receipt
carries the equivalent eye and atlas_truth renders the export from there (box= cuts the element out, the light kept).
What depends on position and is therefore not invariant: fog (off for these captures), the lightmap's inputs (the
environment attributes are probed at the camera: on the sky stage, the stage's; pin-view writes them as
name.view.json for the offline render), chunk fade-in (forced visible), and with a shader pack anything the pack
derives from world position (the suite runs vanilla). What is not reproduced: particles, anything a block entity or
entity does after the pin (states are frozen), mods' additional section geometry (AddSectionGeometryEvent), and
NeoForge model data other than block entities'. Per rung the suite also measures Minecraft's own stability
(pin-shimmer: the same frame twice, 6 ticks apart) and records what Minecraft itself would draw at the true distance
(entity view distance from Entity.shouldRenderAtSqrDistance, block entity renderers' view distance): where Minecraft
culls or the element is sub-pixel, disagreement with it is information, not error.

First live run (2026-10-19, whiterun-day-chunk, three rungs, 6 scenes in 353 s with the jar build): the offline
reference (atlas_truth, exact mode, from the receipt's equivalent eye) against Minecraft's pinned rendering: liveDE
0.32 at 32 m, 0.42 at 1 km (edges 0.84 / 1.0, nothing missing); at 16 km the chunk is under half a pixel and neither
draws it. So the offline reference is Minecraft's rendering for this element at those distances, and the element
tier's comparisons against it stand for comparisons against the game.

**Lighting and surfaces beyond Minecraft's path** (owner): once offline and live match, the groups are the place for
Lumen's ideas: a surface cache per group face (sun visibility, bounce, block light, sky) updated where it changed and
where it is seen, so far light, shadows and bounce resolve like geometry; and the face textures become a virtual,
resolution-on-demand atlas (each group face asks for the texel density its footprint needs), better than block meshes
with fixed textures.

**Plan of record: accumulate on surfaces, not the screen** (owner, 2026-10-19). Every group face carries its full
surface state in a surface cache: albedo, normal, roughness, emission and lighting (sun visibility, sky, bounce,
block light), at the texel density its footprint asks for. Stochastic choices (level dithering, coverage, shadow
and light samples) converge there, in object space, so camera motion throws nothing away and nothing is reprojected;
the screen only reads the cache (and a light TAA for edges). Normals and every other attribute follow the same rule
as colour: kept per face, averaged up the groups, refined where seen and where changed. Screen-space TAA tricks
(surface-id history, adaptive blend, variance clip) are interim measures for the current renderer only.

**Dynamic content** (owner, 2026-10-19: "animations at different distances, visual and lighting effects, player
interactions, you name it"). One system, sorted by how a thing changes:

| kind | examples | how |
|---|---|---|
| state changes | a chest lid, a door, a lever, fire's age, crops | an edit (the change feed): the groups over that block rebuild, nearest and most visible first; which properties matter is distance-dependent (a lid's open state near, nothing far: AtlasFarState is the far end of that rule) |
| continuous motion | water and lava flow, leaves and grass swaying, flags, fire flicker, animated textures | material properties (frames, wave, sway) evaluated in the surface cache at the rate the distance needs: every frame near, averaged over time far (a flickering torch is a steady glow at 2 km) |
| entities | mobs, NPCs, players, mounts, dropped items | their models near (Minecraft's path, sharing our G-buffer and light), stand-ins voxelised from the same models beyond, posed from a few key poses, animation rate falling with distance |
| light | carried torches, spells, fire spreading, lightning, day and night | light sources injected into the surface cache; only the groups they reach update |
| interaction | breaking, placing, opening, aiming | always near: exact blocks and the vanilla interaction layers on top; the change feed carries the result outward |

Every row keeps the rule: nothing changes what it is with distance, only how much detail and how often it updates.

**The same structure for everything** (owner): clouds, rain curtains and fog are density instead of surfaces in the
same groups (a group keeps its mean density and the spread inside, averaged up the hierarchy like colour, traced with
the same descend-or-draw rule, so a cloud resolves from a soft blob to its billows as it nears); light is kept per
group face (Lumen's surface cache: sun visibility, bounce, block light, updated where it changed and where it is
seen), so lighting and shadows also coarsen and resolve smoothly. Each is an experiment for this bed once surfaces
are settled on real data.

What this asks of generation: the content (blocks, from real sections or from the generators) must be producible
deterministically and fast near the GPU; the grouping is built from it, never authored, so a tree, a town or a cave
mouth needs no far version of its own.

Coverage alone thickens thin sheets at grazing angles (it does not know where in the voxel the content is); the
content box (6 numbers a voxel) fixes most of it at about half the micro-voxels' steps. Next: real objects (towns,
forests, gates), the near-view capture of the same blocks against `ref` (validates the library), a hierarchical
traversal for the cost numbers, then the winner ported to Slang.

## Slang port

Both finalists run through the renderer's own Slang paths: `native/shaders/atlas_lod.slang` (entries `boxesMain`,
`groupsMain`) compiles to C++ for the CPU backend and to SPIR-V for Vulkan, like atlas_far. `atlas_lab_gpu` (target of
the same name) loads atlas_lab's inputs, builds the hierarchies with atlas_lab's own builders (atlas_lab_scene.hpp, the
code atlas_lab runs, moved out of it unchanged: same output bit for bit), flattens them (atlas_lod_build.hpp) and checks
the kernels against atlas_lab's tracers:

    atlas_lab_gpu synth=<dir>                                   a small synthetic scene of our own (no game assets)
    atlas_lab_gpu blocks=<dir>/blocks.bin volume=<dir>/x.alv [dist=10,50,200,600,1500] [backend=vulkan] [w=1920 h=1080]

**Layout.** One 16-byte node for both hierarchies (`uint4`): the content's tight box in micro-voxels (12 bits a
coordinate: volumes up to 1023 blocks a side, so the renderer keeps one tree per region in region coordinates), the
flattening error (12 bits, an integer number of micro-voxels, saturating: drawn later, never earlier), an 8-bit child
mask and the first child (children contiguous: the octree's occupied octants in octant order, the groups' left and
right side by side). The octree's single micro-voxels are not stored: a 2^3 node's mask says which of its octants are
filled and the kernel rebuilds their boxes from its own (seven in eight of atlas_lab's octree entries are such leaves
or empty octants). Face texels are **not** stored per node: a drawn box finds each texel by walking the column under it in a
micro-voxel brick pool (per block a brick index; per brick the block's resolved 4 x 4 x 4 occupancy, leaves' cutout
hashed in, and its state; per state its 6 x 4 x 4 texels, mean colour and tint), exactly as atlas_lab's boxFace walks
microAt. Storing every node's faces at micro-voxel resolution instead would cost far more than the nodes (the "faces MB"
column below: every level of the hierarchy repeats the surface), and the pool is what a block world costs anyway; the
price is the walk (one fetch where the content is at the face, the box's depth through a hole). Traversal is iterative
with a fixed stack a thread: the octree one entry per level (8 bytes: the first child and the rest of that level's
children sorted by entry, 3 bits each; 16 levels, 128 bytes), the groups one per level (the farther child; 64 levels,
256 bytes; the builder refuses deeper trees).

| scene | boxes: nodes / MB (atlas_lab's tree; faces MB) | groups: nodes / MB (atlas_lab's; faces MB) | brick pool |
|---|---|---|---|
| synthetic tree, hut, pond (40 x 20 x 40) | 33 k / 0.54 (5.4; 12) | 10 k / 0.17 (0.25; 4.6) | 0.14 MB |
| Falkreath core (65 x 51 x 65) | 365 k / 5.8 (58; 131) | 78 k / 1.25 (1.9; 54) | 0.96 MB: 1,744 bricks, 172 states |
| Falkreath area (129 x 61 x 129) | 1.61 M / 25.7 (257; 569) | 266 k / 4.25 (6.4; 184) | 4.26 MB: 4 MB of it the dense block grid |

Builds on the CPU (atlas_lab's builders, one thread): the area's octree 4.3 s, its groups 12.8 s; flattening 0.1 s.

**Parity** (CPU backend against atlas_lab, same jitter, seeds and dithering): the images are identical at every
distance on all three scenes, 10 m to 4 km: iou 1.0000, dE 0.000, largest channel difference 0, the same steps per ray
(the C++ is compiled with precise floats; the kernel mirrors atlas_lab's operation order). For scale, atlas_lab against
itself with other seeds (what the dithering alone moves): dE 0.06 at 10 m, 0.6 at 200 m, 2.2 at 1500 m, 3.3 at 4 km.
Against ref (micro-voxels, 64 samples) the kernels reproduce the table above (Falkreath area):

| | 10 m | 50 m | 200 m | 600 m | 1500 m | 4000 m |
|---|---|---|---|---|---|---|
| boxes iou / dE / steps | 1.000 / 0.04 / 16 | 1.000 / 0.12 / 18 | 1.000 / 0.40 / 11 | 0.996 / 1.4 / 2.3 | 0.990 / 2.3 / 1.8 | 0.978 / 3.4 / 1.6 |
| groups | 1.000 / 0.04 / 31 | 1.000 / 0.15 / 35 | 0.999 / 0.43 / 19 | 0.997 / 1.4 / 3.9 | 0.991 / 2.2 / 3.0 | 0.974 / 3.4 / 2.6 |

**Cost on the CPU backend** (10 threads, a 1920 x 1080 frame through the object; Falkreath area): at 10 m (87 % of the
frame on the town) boxes 621 ms, groups 574 ms (atlas_lab's hand-written tracer on the same threads: 384, 368); from
200 m the town is a few percent of the frame and a frame is 20 - 50 ms, mostly the sky's rays. The generated C++ is
about 1.6x slower than atlas_lab's: it is a reference, not the fast path.

**Vulkan** (`backend=vulkan`; RTX 3090, the dispatch alone by GPU timestamps, median of 32 frames after a warm-up, no
game running). The images agree with the CPU backend's: the same silhouette and steps per ray at every distance, mean
dE at most 0.002, under 0.02 % of pixels beyond dE 1 (worst 2.4: the SPIR-V is not built with precise floats). A 1920 x
1080 frame through the object, ms:

| | 10 m | 50 m | 200 m | 600 m - 4 km |
|---|---|---|---|---|
| boxes, core | 1.62 | 0.90 | 0.16 | 0.14 - 0.16 |
| groups, core | 0.97 | 0.53 | 0.12 | 0.12 |
| boxes, area | 2.00 | 1.47 | 0.26 | 0.19 |
| groups, area | 1.20 | 0.93 | 0.17 | 0.14 - 0.16 |

At 10 m over the area (87 % of the frame on the town; 15 and 27 steps a ray) that is 1.0 G rays/s for boxes and 1.7 G
for groups: **groups are the faster on the GPU despite nearly twice the steps**, because an octree descent fetches and
sorts up to 8 children in local memory where a group compares 2. 0.12 - 0.19 ms is what the sky's rays cost (ray setup,
the root box).

Before the renderer can use it: a tree per region in region coordinates under a top-level structure over regions (the
12-bit boxes), and the brick pool from the world's sections instead of a dense grid per volume; a bound on the face walk
through holes (a hole costs the box's depth in micro-voxel fetches: a per-face first-hit depth or a coarse occupancy
mip); building and updating groups on the GPU (done, "GPU group build" below) with edits from the change feed; 16 x 16
face texels from a per-texture atlas (texture resolution up close); the G-buffer the pack lights (normal, material class,
depth against the near field) and the far pass's jitter, fog and hand-off; then timings over real far-field scenes
(terrain to the horizon, not one town in the sky).

## GPU group build

The groups are built on the GPU from the brick pool (`native/shaders/atlas_lod_build.slang`, host side
`native/tools/atlas_lod_gpubuild.hpp`), compiled like atlas_lod: Slang -> C++ for the CPU backend, Slang -> SPIR-V for
Vulkan. `atlas_lab_gpu ... build=gpu` builds them on the CPU backend and (`backend=vulkan`) on Vulkan, compares the
result with atlas_lab's tree word for word, and the kernels then trace the GPU-built tree (`cpubuild=0` skips atlas_lab's
10 s build; `profile=1|2` times every kernel, `buildreps=` the builds; `update=carve,fill,block edit=16` the updates
below).

**The choice.** Two GPU designs were weighed. LBVH (Morton codes of occupied micro-voxels or blocks, radix sort, Karras'
radix tree, bottom-up refit, then treelet SAH) is the usual fast GPU BVH, but it would build a different and worse tree
than atlas_lab's: Morton order cuts at power-of-two planes (the grid lines content-shaped groups exist to ignore), its
leaves are primitives, so flat boxes would have to be found again by collapsing, and every node still needs its
flattening error, the expensive part, measured from the bricks. The other way is atlas_lab's own top-down SAH, level by
level, every node of a level at once. Its work is a set of independent scans (per node, axis and micro-voxel slice; per
box face and column), which is what a GPU wants once the scans are cheap: they read the bricks' 4 x 4 x 4 masks a layer at
a time and skip empty blocks by their runs. That one was built, so **the GPU tree is atlas_lab's tree, word for word, on
the synthetic scene, the Falkreath core and the Falkreath area (CPU backend and Vulkan): the quality cost against the CPU
SAH is nil.** It also means the parity tables above hold unchanged for GPU-built trees (checked again: iou 1.0000, dE
0.000, the same steps per ray, 10 m to 4 km, all three scenes; against ref on the area: iou / dE / steps 1.000 / 0.04 / 30.4
at 10 m, 0.999 / 0.43 / 19.3 at 200 m, 0.991 / 2.37 / 3.05 at 1500 m, 0.975 / 3.58 / 2.64 at 4 km).

**How.** Each node is a work item; a level is a range of them (breadth first), and every count comes from an exclusive scan
(work-group scans on Vulkan, one thread on the CPU backend, which has no group memory or atomics), so each dispatch is
sized by the GPU itself (indirect dispatch) and a build is three submissions (two batches of 16 levels, then the layout).
Per level:

1. *Flattening error*: one task per box face row (axis, side, a line of 32 columns); the column's first content is found
   by walking the brick pool from the face, four micro-voxel columns of a block at once, empty blocks skipped by their
   empty runs (per block and direction how many empty blocks follow, 5 bits each: built once, three dispatches over the
   volume's lines). A node with error 0 (or a single micro-voxel) is a leaf, as atlas_lab's.
2. *Slices*: per inner node, axis and micro-voxel slice, the content's count and its bounds on the other two axes; one task
   per block layer and block row computes four slices from the masks' layers (popcounts and nibble ORs), a reduction per
   slice combines the rows.
3. *SAH sweep*: suffix boxes, then prefix boxes and each plane's cost, `area(L) * float(nL) + area(R) * float(nR)` in
   atlas_lab's float operations and order (precise floats on both targets: no fused multiply-add; 64-bit counts converted
   to float rounding to nearest even as the C++ cast does), the least cost and the first plane on ties: atlas_lab's plane.
   Nodes with 64 slices or more get a work group (256 slices a step by group scans, an arg-min reduction) on Vulkan.
4. The two children (each side's tight box) appended to the next level.

At the end, subtree sizes up the levels and slots down them lay the nodes out depth first exactly as lodFlattenGroup does
(children side by side, the left subtree right after them), then one write a node in atlas_lod.slang's 16-byte format.
Buffers that overflow (nodes, slices, partial records) stop the build, grow, and it runs again.

| scene | nodes / depth | atlas_lab (CPU, 1 thread) | CPU backend (10 threads) | Vulkan, wall (GPU) | identical |
|---|---|---|---|---|---|
| synthetic (40 x 20 x 40) | 10,331 / 20 | 183 ms | 17 ms | 3.7 - 4.3 ms (2.9) | yes |
| Falkreath core (65 x 51 x 65) | 50,401 / 22 | 2.2 s | 71 ms | 5.7 ms (4.6) | yes |
| Falkreath area (129 x 61 x 129) | 158,775 / 25 | 10.2 s | 213 - 230 ms | 8.4 ms (7.4) | yes |

(RTX 3090, no game running, median of 5 - 9 builds, from nothing but the pool: the empty runs included; the export of
2026-10-19 15:00 read with the block library as it is now: cutouts for leaves and plants only, thin layers kept, so smaller
trees than the tables above; the same build gave 257,155 area nodes in 7.7 ms before that change.) Where Vulkan's time goes,
area: error tasks ~1.7 ms, slice tasks ~1.7, scans ~0.9, sweeps ~1.1, the rest small; by level 1.0 ms for the first (the root's region and its slices, the
runs), 0.2 - 0.4 ms for the next twelve, under 0.2 below; a level costs at least ~0.04 ms of dispatch and barrier latency
(15 dispatches), so a small build (the synthetic scene, an update) is bound by that floor, not by work. Memory: the
scratch is 7 / 12 / 47 MB (work nodes, scans, slice records and partials, sized to the widest level), plus the empty runs
(4 B a block) beside the pool; the output is the tree itself.

**Updates** (`LodGpuBuilder::editGrid` + `update`). Every node owns a region of space: the root the volume, a child its
parent's region on its side of the split plane (the plane is read back from the two children's boxes, which lie apart on
it). An edit uploads the changed grid rows, re-measures the empty runs of the lines through it, and then:

* rebuilds the subtree of every topmost node whose region meets the edit and is at most 8 times its volume (or is a leaf),
  from the content of that region (the build above, started from regions instead of the volume); each subtree is appended
  after the array's end and its root written into the old root's slot (the old subtree becomes garbage until the next
  full build, which compacts);
* refits the nodes above them, deepest first: each box the union of its children's (an emptied child dropped from the
  mask), then every refitted node's flattening error measured again, all of them in one error pass.

The planes above the rebuilt subtrees stay where the old SAH put them. Edits at the middle of the area (16-block edits,
`update=carve,fill,block edit=16`, each repeated 7 times; times are the median, the first update of a session is ~1.5 ms
more while the output buffer grows):

| edit | regions / refitted | Vulkan, wall (GPU) | CPU backend | full build, Vulkan / CPU backend |
|---|---|---|---|---|
| carve a 16^3 hole through the town | 4 / 20 | 5.0 ms (3.6) | 30 ms | 7.5 ms / 202 ms |
| fill a 16 x 8 x 16 stone slab in the air above it | 4 / 28 | 3.9 ms (3.2) | 21 ms | 7.7 ms / 216 ms |
| place one block on a roof | 1 / 10 | 1.6 ms (1.2) | 4.4 ms | 7.7 ms / 213 ms |

(The core: carve 4.1 ms, fill 3.9, one block 1.3, full 5.1 - 5.4 ms.) Against ref after each edit, the updated tree and a
full build of the edited volume: the silhouette and colour agree at every distance (iou within 0.002, dE within 0.1;
identical after the carve on the area), and so do the steps after a carve (30.35 / 19.32 / 3.05 at 10 / 200 / 1500 m, the
full build's exactly; core 24.9 / 11.0 / 3.6 against 23.8 / 11.0 / 3.5). New content outside the old boxes is the case refitting serves worst: after the slab in the air the nodes
above it keep the old planes and grow up past the camera, 36.3 / 23.4 / 3.6 steps against 23.9 / 20.5 / 3.2 (core: 31.9 /
13.8 / 4.5 against 24.9 / 11.7 / 3.9); rebuilding a node whose box would grow by more than a quarter (`regrow=1.25`)
barely helped (measured before the library change: 34.5 against 35.9 at 10 m), since the root must grow anyway, nor does
rebuilding the smallest region holding the whole edit (`contain=64`: the slab straddles the root's plane). So: update where the edit is (milliseconds), and run a
full build (8 ms for the area) when a refit grew the root's box or the garbage passes the live nodes.

Before the far pass can use it: the builder takes one dense volume (a region in region coordinates under 1023 blocks a
side, the 12-bit boxes); the world's regions need the pool from their sections (per-section grid rows instead of a dense
grid), one tree each under the top-level structure, and the builder sharing the renderer's Vulkan device and queue (it
makes its own today; `vkCmdDispatchIndirect` is resolved through `vkGetDeviceProcAddr`, the loader in `src/` does not
list it); the change feed's edits as `editGrid` boxes (new block states append bricks); a policy for full rebuilds and
compaction (above).

**The octree (boxes)** is built by the same kernels' file (`lbOct*`, `LodGpuBuilder::buildBoxes`), bottom up: a block's
cell and its eight 2^3 sub-cubes straight from the brick (box, mask of occupied octants, flattening error from the 64-bit
mask), every larger cell the union of its eight children, its flattening error through the groups' error passes (a level's
occupied cells listed as a level of work nodes); then slots down the levels in octant order and one write a node, a
sub-cube an F_MICRO node with its micro-voxels unstored, as lodFlattenBoxes. Word for word atlas_lab's octree on all three
scenes; one level per power of two, so nine levels and ~100 dispatches for the area:

| scene | nodes / depth | atlas_lab (build + flatten, 1 thread) | CPU backend | Vulkan, wall (GPU) |
|---|---|---|---|---|
| synthetic | 33,467 / 8 | 85 + 2 ms | 9 ms | 1.0 ms (0.7) |
| Falkreath core | 369,741 / 9 | 678 + 20 ms | 35 ms | 1.8 ms (1.4) |
| Falkreath area | 1,627,279 / 10 | 3.5 s + 90 ms | 139 ms | 3.6 ms (3.2) |

Scratch 6 / 22 / 84 MB (the cells' records). Both builders run clean under the Khronos validation layer with
synchronization validation (builds, updates, profiling).

## The renderer's groups (ATLAS_FRAME_GROUPS, ABI 0.14)

The groups draw the real far field inside atlas_core, fed by what the game already sends, behind a frame flag (the
default stays the voxel path; `-Dnirncraft.atlas.groups=true` turns it on in game with a 0.14 core).

**Shapes.** `atlas_set_material_shapes`: per far material its 4 x 4 x 4 occupancy and six 4 x 4 face tiles, computed in
game by `AtlasShapes` (the lab library's entry, moved there and shared: the shape from the outline, each tile texel
the outermost quad over it, the biome tint on the quads the game tints; cut-out coverage only for leaves and plants,
blocks thinner than a micro-voxel keep a floor layer). With groups on, `AtlasMaterials` sends a shape with every id
and real sections get one id per far block state and tint (4096 up), so a top slab, a stair's facing and a log's axis
survive. Voxels of real sections at LOD 0 take their material's shape; everything else is a full cube wearing its
material's face tiles; a material without a shape is a cube in its palette colour.

**Regions.** Per clipmap region (`World`'s keys, every level) a tree is built on update's worker threads, nearest
first within its budget, once a frame has asked for groups; a dirty section rebuilds its regions (and the one across
a border it touches); a region whose voxels are clean only builds its tree. Content is the region's exposed voxels
(an open 6-neighbour: air, a shaped block's empty part, cut-out), read column by column from sections and tiles with
a ring of the neighbours' columns. At LOD 1-4 the voxels made from real blocks carry their own micro-voxels by
coverage (solid parts by share, cut-out parts by the opacity a ray across them meets: 1 - (1 - share)^(2^lod)), so a
crown neither fattens nor thins as it coarsens; such a region exists even where the voxels' mip vote left it empty.
The tree: binary SAH over voxel planes (exact sweep over presorted lists), per face a flattening error (4-bit codes,
rounded up), leaves flat solid boxes (every face covered at depth 0) or boxes of at most 4 x 4 x 4 voxels. 16-byte
nodes (`src/groups.hpp`), a brick pool per region (64 x 64 columns -> slots -> bricks of the occupied voxels' 16-bit
cells, deduplicated, with masks for the coverage voxels), all in one buffer (binding 25: the baseline is now 24 storage
buffers a stage). A tree over 2^20 nodes is refused (the region keeps the voxel path).

**Trace.** `shaders/atlas_groups.slang`, called from `traceLod` for primary rays: a node is drawn when the error of the
face the ray enters by is under the pixel's footprint there (threshold dithered per pixel, moving with the TAA's jitter
phase), a flat leaf always, a voxel leaf bigger than the pixel is stepped voxel by voxel (micro-voxel by micro-voxel in
shaped ones); a drawn face walks the column under the texel at most error + 1 micro-voxels (holes cost that, not the
box's depth). The chord's first cell is tested against the voxel path's solid (a ray starting inside a coarser level's
ground hits it at once). Same outputs; shading, shadows (voxel path), sun map, jitter and hand-off unchanged. Surface
cards (`atlas_set_surface_cards`, atlas_truth bake=) colour a hit from the game's own rendering of that face, filtered
by footprint; the game does not send them yet.

**Measured** (offline, `atlas_render` with the Falkreath exports; harness scenes `tools/harness/scenes/atlasgroups.json`):

| Falkreath view, the live camera, 4 spp, vs atlas_truth (dE) | 0-64 | 64-192 | 192-512 | 512-1024 |
|---|---|---|---|---|
| live (vanilla near, today's far field) | 1.2 | 4.8 | 20.5 | 23.8 |
| voxel path (same materials, vanilla face shade) | 7.6 | 12.7 | 13.0 | 13.2 |
| groups (shapes and face tiles) | 6.7 | 11.3 | 12.8 | 11.3 |

What is left there is mostly what face tiles under a fixed shade cannot carry (the lightmap under roofs and crowns,
smooth light, 4 x 4 against 16 x 16 texels); the element tier with surface cards (`atlas_render camera=mc box=
cameras= surface=`, 16 spp against the intended reference) measures the representation itself: falkreath-wide-chunk
dE 2.6-5.2 and iou 0.97-0.999 from 32 m to 2 km, 0.90-0.94 at 4-8 km; the trees 0.7-5.8 and 0.93-0.999 to 2 km;
fly-ins pop 0.0. Elements beyond the LOD 4 window (~10 km) are gone: real sections only feed LOD 0-4, and the world
keeps them ~1.2 km out (the server sends them to ~1.5 km); in game far trees and buildings come from the tiles.

Vulkan (RTX 3090, 1920 x 1080, GPU timestamps, outputs on the device, the voxel path in alternate frames of the same
process): the Falkreath view from 200 blocks up with the near field's 192 blocks left out, groups 13-19 ms against the
voxel path's 12-14 ms; to the 40 km horizon 22 against 16. Both are 5-8x the preview terrain's cost over the same steps
(13k real sections in the window). Without the texel walks the tree traversal alone is 9 ms: the face walks and leaf
steps (a chain of brick-pool lookups a voxel) are what to cut next. CPU backend (10 threads): ~0.96 s a frame either
way. Builds: every tree of the clipmap 24-31 s of CPU (2.9-4.9 s on 10 threads), the slowest region 0.5-0.7 s, 59 MB
for 191 regions (largest 1.9 MB).

## Status (branch atlas-render)

* Done: trees at level 0 grown by their real features (AtlasTrees); real chunks' columns in the change feed (ground,
  one span, lights).
* Done (native): generated coarser levels from sections, LOD 1..4 (Section::buildMips with the rules above; sections
  are read up to LOD 4, where a section is one voxel).
* Next: sections from real chunks pushed by the server (the change feed); predicted land's objects as sections
  (generators into a capture level); buildings by their builders
  (AtlasCapture for every piece, not massing); surface attributes for ground cover.
