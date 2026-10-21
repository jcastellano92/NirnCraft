# Infinite ATLAS: what the engine does, what it doesn't, where it goes

The engine at a glance (2026-10-21, atlas-render 7ac470bc + follow-ups). Live state and work in flight:
[ATLAS-STATUS.md](ATLAS-STATUS.md). Future design: [ATLAS-WORLD-STORE.md](ATLAS-WORLD-STORE.md),
[ATLAS-RENDERER-PLAN.md](ATLAS-RENDERER-PLAN.md). The frame-by-frame checklist of everything Minecraft draws:
[ATLAS-RENDER-INVENTORY.md](ATLAS-RENDER-INVENTORY.md); the per-item gap table: `E:\NirnCraft-scratch\gap\GPU-GAP-LIST.md`.

Status words: **in** (on the GPU path, verified), **partial**, **vanilla** (still drawn by vanilla, composited into
our frame), **missing**, **broken** (seen wrong in the owner's live test, 2026-10-21).

## 1. In the engine

| Area | What | Status |
|---|---|---|
| Renderer | One Vulkan path tracer for every pixel (unified mode); one continuous detail gradient, per-pixel footprint over stored levels; GL zero-copy interop | in |
| Voxel grid | Level 0 plus GPU coarse levels 1-6; uniform sections take no slot; admission and eviction by one GPU priority (distance, view, footprint); coarse terrain behind any gap; far chunks straight from the generator | in |
| Residency | GPU ranking; budgeted RAM tier (GPU-packed sections); per-world disk tier; edits stored as differences (voxel-exact reload) | in |
| Self-baking world | Generated sections and far chunks cached on disk by seed + generator fingerprint; second visit loads instead of generating (spawn radius ~1 s vs ~41 s); horizon cache | in |
| Loading phase | Loading screen (renderer, generator, spawn area, horizon), pipeline cache (cold 91 s -> 0.85 s) | in |
| World generation | Terrain, caves, aquifers, ores, surface, the 2D Tamriel inputs, far tiles and far chunks with snow, ice and far roads: on the GPU, bit-exact with Java | in |
| Features | Trees, ores, cover, rocks, freeze, disks, springs, lakes; temperature, frozen, alchemy; placement mask: GPU stages in a neighbour-aware chunk store, 100% vs Java | in, **broken live** (the GPU decoration failed to start, §2) |
| Settlements | Pieces, Sanity (keeps fluids), Accessibility, street paint, generic settlements, working sites, dungeons: GPU store stages, ~99.9% vs the server | in |
| Blocks | Full cubes; every non-cube block as a 1/16 micro-voxel brick from its model; full near state (crop age, redstone power); dirty tracking behind the camera | in, **broken live**: fences / walls / panes / iron bars don't connect, much vegetation missing (§2) |
| Water | All water by atlas (near and far, above and under), entities and particles under water through the medium | in (live look pending) |
| Light | Sun, moons, sky; light by need (tile schedules); every block emitter on the GPU in its own colour (96 blocks), journal lights beyond; emissive surfaces; animated textures | in |
| Sky | Physical air, baked Masser and Secunda, orbits, phases, eclipses, stars by birthsign, planets, comet, aurora | in |
| Items | Dropped items, item frames, held items lit by our forward light | partial (forward light, not traced) |
| Tooling | Heavy-job lock with memory caps and traces; per-worktree builds; content-addressed shader cache; reviewers | in |

## 2. Not in the engine (or broken)

Seen in the owner's live test (2026-10-21):

1. **GPU decoration failed to start**: `AtlasFeatureProgram.Compiler.finish` reads Minecraft's `Mth.SIN` table by
   reflection and it was null in the real game (a pack mod replaces it), so features fell back to Java on the CPU
   (214 s of CPU, fps 1-11 for the first minute). Causes the CPU load and most stutter.
2. **Far stand-in trees that vanish up close**: the old far-tile path (`farTiles` on by default) still paints its
   placeholder forest canopy where the real features put no trees.
3. **Fences, walls, glass panes, iron bars don't connect**: multipart block models (shape by neighbour) not composed.
4. **Much vegetation missing** (plants, double plants and similar).
5. **Stutter** (frame spikes to 317 ms) and CPU load: measure again after 1.
6. **Small green squares floating in the sky**: unknown.

Second live test (2026-10-21, 42b07edb): still CPU-heavy (the generator churns: 802,809 chunks processed for ~1,000
sections uploaded); the far field is still the old tile path and the GPU far chunks don't show; caches outlive code
changes; **a pop-in line** where grass and small detail appear at the level-0 edge (no coverage in the coarse levels);
plants drawn as full textured cubes; many blocks and foliage still wrong; green squares in the sky (not material
exhaustion); **water missing in places**. Owners: grid agent (churn, far field, cache fingerprint and wipe, pop-in
gradient, CPU re-reads), content agent (block gallery review, plants, foliage, green squares), renderer agent (water).

Fixes after the second live test (atlas-render 751bcbd5, third test pending):
- Generation churn (802,809 chunks for ~1,000 uploads): request and drop radii disagreed, so ~330 chunks were forgotten and
  re-requested every turn; only cache misses now enter the store, which keeps their neighbourhood and never demotes a
  packed chunk. Still-camera stall: the store now drains without a new request. Far field: the GPU far chunks are the
  far field (tile path off by default). Caches are keyed by the build (native source hash, atlas sources, bundled
  natives) and `/nirncraft-atlas-wipecache` / `-Dnirncraft.atlas.wipeCache` wipe them.
- Green squares: stale generated sections above a real chunk's top are cleared, far crowns reach the ground at coarse
  lods, far leaf gaps hashed in world space. Blocks: plants as crossed planes, tint per quad layer, real states from the
  server, animation frame sequences, plant offsets, overlays overwrite, overshoot kept.
- Pop-in line: sparse plants carry their coverage into the coarse levels (world-stable dither), so grass thins with
  distance. **Not yet verified in the game or offline** (the offline volume path does not run the GPU vote).
- Water: waterlogged blocks, flowing heights, far-lake holes fixed (atlas-render b1e0f0b7 and the water branch).
- Still open: far crowns are right only on average (exact for scatter trees and the first tree per chunk); the level-0
  window is a fixed radius; coarse-level evictions run on the CPU; remaining gallery classes (content agent).

From the code review (gap list):
- Entities (mobs, NPCs, players), block entities (chests, signs, banners), particles, the first-person hand: **vanilla**
  raster with our forward light; they cast no shadows into atlas and are not traced.
- Dynamic lights (held torches, spells, fire arrows, burning mobs, lightning): **missing** on the atlas path.
- Spell effects as particles only; spell projectiles have no model.
- Other dimensions: vanilla (Overworld only).
- Frames atlas does not own (in lava, powder snow, blindness): vanilla.
- Weather near the camera (rain, snow): vanilla; wet surfaces: missing (weather work paused by the owner).
- Multiplayer: client generation with a shared seed is designed (ATLAS-CLIENT-GENERATION §9), not built.
- Linux natives are behind the Windows ones.

## 3. Where the engine goes (future state)

1. **Fix the live findings above** (now).
2. **Unified entities** (ATLAS-WORLD-STORE §3): every world-space entity, block entity and item as object-space
   micro-voxel bricks with a per-frame state array; near and mid traced through a GPU BVH with the terrain (shadows,
   light, bounce), far splatted into the coarse levels; GPU animation from compact motion state.
3. **Dynamic light system**: one per-frame light list (entities, projectiles, spells, held items, lightning) into the
   GPU light binning, true colours.
4. **Remove vanilla's chunks** (step 3): replace what depends on them first (entity visibility, block-entity lists,
   the near shadow map).
5. **Profiler with full tracing** (step 4): Tracy CPU/GPU zones, Java zones, Perfetto export, in-game overlay.
6. **Rest of the residency manager** (step 5): coarse-level eviction on the GPU, incremental regrow, budgets.
7. **Self-baking world, complete** (ATLAS-WORLD-STORE §1): region files with deduplicated geometry and a trained
   compressor, idle baker, whole-continent bake (~5 GB est.).
8. **Multiplayer** (ATLAS-WORLD-STORE §2): seed + fingerprint handshake, region hashes, edit deltas by interest.
9. **Baked voxel light**, then weather and biome work (paused until the owner says).
