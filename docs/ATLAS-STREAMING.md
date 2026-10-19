# Infinite ATLAS: streaming, priorities and caches

The owner (2026-10-19): loading is slow and pops in. The far field should show something at once (a light quick pass),
then resolve near and visible first, outward from the camera, then fill everything else in the background into caches
so a return or the next boot loads instead of computing again; near instant on a GPU, still good on CPUs and every
platform. It should know what the player needs next (underground: no surface vistas; walking to a cave exit: start the
view outside), use what is hidden or blocked to set priorities, re-prioritise at once when the camera turns or moves
fast, talk to the server smartly, preload on first boot, know cheaply what changed (compare, keep what is good), and
weigh every update by how much it changes what is seen at that distance. Not chunk by chunk: a Lumen-like system
breaks the scene into its parts and puts the work exactly where the eye is.

## How it loads today (2026-10-19, measured)

The loading suite (`harness.py run --suite loading`, tools/harness/loading.py) settles at a place, marks the far
field's timeline (`far-timeline start`), teleports, captures every second for 16 s and once settled, and keeps the
worker's pass timeline (`far-timeline dump`). Baseline (whiterun to riften; the poses were bad, the timing holds):

- the teleport lands about 1 s in; the worker's first pass at the new place runs 3.65 s (covering tiles 2.6 s, the
  builds 0.5 + 0.15 s, overlays 0.24 s) and nothing new is published before it ends: at 4.7 s the view jumps by dE 48
  (the whole far field at once: pop-in);
- passes of 1.5-2 s go on refining (exact tiles, sections, overlays) until about 16 s;
- the 3 km hop: a jump of dE 25 at 2 s, 8 s to come within dE 3 of the settled view.

What the pipeline does (the full map with file:line: the "far-field loading pipeline" survey in the session log):

| | today | consequence |
|---|---|---|
| build budget | the Java worker calls `atlas_update` with budget 0: every wanted region in one parallel run | regions appear all at once (pop) |
| first frame | nothing far is drawn until the worker's first full pass | seconds of nothing |
| order | tiles: LOD 0 to 6 (fine to coarse), in the view cone first; regions: nearest and finest first; overlays nearest first, no frustum | the horizon comes last |
| publishing | every revision repacks the whole scene and stages all of it to the GPU, twice a pass | cost grows with the scene, not the change |
| caches | exact tiles on disk (per world, per generation identity); regions, group trees, settlements, trees, materials rebuilt every session and revisit | a return computes again |
| lights | time of day and sun are a re-shade; a new light re-uploaded the covering tiles at all 7 LODs | (fixed: lights only) |
| GPU | the trace, clouds, sun map; builds (regions, groups) on the CPU | the GPU builder (atlas_lod_build.slang) is in a tool only |

## Plan (each step measured by the loading suite and the element tier)

1. **Measure** (done): the loading suite and the far timeline; the element tier's fly-ins on Vulkan (they pop more
   than on the CPU backend: 0.188 vs 0.083 on the same build, i.e. what reaches the GPU between frames).
2. **Progressive, coarse first**:
   - an instant pass on arrival: every level from ready-made tiles and stand-ins, coarsest first, built and shown before
     any exact tile (done: AtlasFarField, coverQuick);
   - regions missing entirely built coarse first (one coarse region covers what dozens of fine ones do), dirty ones
     nearest first; budgeted updates that commit as they go, each new region fading in;
   - publishing only what changed (incremental repack and staging), so many small commits cost little.
3. **Priority by what is seen**: the far trace writes, per region, how many pixels it covered and how far the result
   was from what it should be (feedback buffer); the scheduler orders work by visible screen error per millisecond,
   with the frustum, occlusion (hidden regions wait), camera motion (predicted view, a fast turn re-orders at once) and
   context (underground: the surface waits; nearing an exit or a window: the view outside is prepared).
4. **Caches with content hashes**: every region keyed by a hash of its inputs (tiles, sections, overlays, code
   identity); a return, a teleport back or the next boot loads it from disk; a change is found by comparing hashes,
   only what differs is rebuilt; caches written in the background.
5. **Fast lighting**: lights and time of day only re-shade; per-surface light caches update where light changed.
6. **Server and first boot**: the client sends what it sees and needs (visibility-weighted requests); first boot
   preloads the coarse levels of the whole map (or ships them), the server pre-generates around spawn.
7. **Builds on the GPU in game**: the GPU group builder inside atlas_core, the CPU path kept for no-GPU machines.

Targets: first far image under 100 ms after arrival (stand-ins), within dE 3 of settled in under 2 s on a GPU, no jump
between seconds over dE 6; a return to a visited place loads from cache with no visible refinement.
