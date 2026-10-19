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

## Status (branch atlas-render)

* Done: trees at level 0 grown by their real features (AtlasTrees); real chunks' columns in the change feed (ground,
  one span, lights).
* Done (native): generated coarser levels from sections, LOD 1..4 (Section::buildMips with the rules above; sections
  are read up to LOD 4, where a section is one voxel).
* Next: sections from real chunks pushed by the server (the change feed); predicted land's objects as sections
  (generators into a capture level); buildings by their builders
  (AtlasCapture for every piece, not massing); surface attributes for ground cover.
