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

### Hidden things and small things (owner, 2026-10-19)

- **Occlusion.** Drawing already pays nothing for what is hidden: the far field is ray traced, each pixel's ray walks
  the hierarchy front to back, skips empty space in big steps and stops at the first surface (Lumen's software ray
  tracing works the same way; Nanite's two-pass occlusion culling exists because it rasterises). What is not culled
  is the building and streaming: regions behind a mountain are built and uploaded as eagerly as those in view. Two
  steps: a horizon test from the heightfield around the camera (regions wholly below the horizon line wait or stay
  coarse; deep underground the surface waits), then the trace's own feedback (which regions and groups rays reached,
  and how far off they were) as the build priority. Hidden regions keep a coarse version: a turn, a shadow ray or a
  reflection can need them.
- **Small details.** Far away a fence post, flowers or a lantern cannot be drawn as themselves: they merge into their
  group's surface as colour and coverage (the surface cache does the same). The element tier shows where that is
  wrong today: coverage counted along a face's axis, not the view (far groups 30-90 % too solid, or prefiltered 15-35
  % too faint), and thin blocks lost (visual hull: approximate). The aim: per group, coverage seen from a few
  directions and colour mips, both baked from the real geometry; rays pass a partly covered group with that
  probability and the temporal resolve averages it; the few details that carry beyond their size (lights at night,
  landmark silhouettes, water glints) stay explicit, as far lights already do.

## What the owner saw flying the build (2026-10-19), and the architecture it led to

- A stark line where vanilla's near field meets the far field (two renderers, two lights); colours that change when
  the real chunks arrive; the far field more accurate where the player has been (real sections exist) than where not
  (stand-ins: the surface painter's guesses, planned settlements: dirt that turns to grass, structures that appear and
  then are not there); a circle around the player rather than detail where they look; a white glow as regions load
  (suspected: the traced sky light's 16-frame accumulation starting from open sky on new surfaces, plus the fade from
  sky; to be measured); deep caves end in haze past render distance (the server records only surface sections).
- Decided with the owner: rendering and simulation part. Vanilla simulates on real chunks in the small simulation
  radius (collision, interaction, mobs: the server is the authority); the client's renderer draws everything, near
  included, from the client's own copy of the world.

### Client-owned world, server-checked

1. **The client generates the world** from the seed with the same generation code as the server (heights, surface,
   settlements, trees), at the detail each distance needs. Far-field sections and tiles are no longer sent: the
   client knows the untouched world.
2. **Hash tree.** The server keeps a hash per section, rolled up to chunk, region and area; the client the same over
   its copy (generated plus every change applied). Checking is top down: equal area hashes cost one hash, a change
   costs a few hashes down to it. The server spot-checks what the client looks at; a mismatch means the client is
   wrong and gets a correction. The server can also hash the untouched generation: generated == real means nothing was
   ever changed there; a client's baseline that differs from the server's is a generator bug or version mismatch,
   logged by kind (surface, structure, tree) and corrected with data until fixed (worldgen must be deterministic:
   "unsafe terrain read during worldgen" is the kind of order dependence that breaks it).
3. **Changes are edits, not chunks**: blocks in a section that became these states (a few bytes a block, batched,
   compressed). They replace AtlasSections' whole-chunk blobs and AtlasEdits' column summaries.
4. **An ordered log**: each change has the server's sequence number; a client applies in order from its own position
   and asks again from a gap; every interested client gets each change as it happens; old history is compacted into
   generated plus net changes (a joining player gets that).
5. **Interest, not radius**: the client reports its view and heading; the server sends changes and live state there
   (players and creatures far off, lights on and off, doors, fires), ordered by how visible they are at that distance
   and at a rate that falls with it. Underground the interest is the cave volume and the exits, not the surface.
6. **Up close, a free check**: vanilla's real chunks in the simulation radius are compared with the client's own
   generation as they arrive; every chunk walked through tests the generator.
7. **One renderer**: the client's renderer takes over terrain from vanilla, from just beyond the player inward, until
   vanilla only simulates; no line, nothing changes renderer as you approach.

8. **Operations, not results, where they are deterministic**: a change that is the output of known code (a spell
   carving terrain, a settlement placing a building plan) goes out as the operation, its parameters and seed, a few
   bytes; every client applies it with the same code. Where the result is not deterministic (vanilla explosions,
   fluids), the edited blocks go out instead; the hash check catches either way.

9. **The log folds into the region; it never grows for years** (owner): a region's state is the generated base plus
   a compacted layer of differences; operations up to a point every client has passed are merged into the layer and
   dropped (storage follows how much a region differs, not its history); a heavily built region graduates (the layer
   is its content, the base no longer matters there); a joining player gets the layer and the few recent operations.
   Each region records its base's generator version: an untouched region regenerates with a new generator, a changed
   one keeps its version or is materialised at upgrade (base and layer merged into stored content, the new base).
10. **Only what is needed is generated**: the far field asks the generator for the detail a region needs at its
    distance (heights and surface at 16 m spacing for a mountain 10 km off), never whole columns or chunks.
11. **Secrets and cheaters**: ores, loot, hidden rooms and traps are not in what the client can generate. When
    something first becomes visible the client asks for it; the server checks against its own world whether that
    position could see it and only then sends it (a cheater asking for what is behind rock flags itself). The host's
    policy decides: log, alert the host quietly, warn, kick, ban, or deceive (fake ores, fake safe ground, false
    directions: Paper's anti-xray engine modes and honeypots are precedents), all without touching the real world. The
    server keeps simulating the real world (deceptions that should hurt need the server to act, the host's explicit
    choice); no punishment on one event, evidence accumulates, a log the host can review.
12. **Order-independent generation**: every feature a pure function of the seed and its position (stateless hashing,
    counter-based random numbers, bounded footprints), nothing depending on which chunk generated first ("unsafe
    terrain read during worldgen" at Leyawiin is that kind of dependence). A second survey (deterministic generation,
    log compaction and generator upgrades, anti-cheat for information leaks, hash-tree sync) grounds the design
    (docs/ATLAS-RESEARCH.md, survey 2).

### Five decisions before building (from survey 2; proposed, the owner's to confirm)

1. **Arithmetic.** Every discrete generation outcome (a block, a height, a feature placed or not) in integer or fixed
   point, with one counter RNG (Philox-4x32 keyed by seed, generator version and stage, indexed by position and draw);
   no `Math.*` or float thresholds in generation, floats only in client-only cosmetics. Only this makes CPU, GPU, Java
   and C++ agree bit for bit. Gate: one region generated in shuffled orders, 1 and N threads, Java and native, hashes
   equal.
2. **Generation contract.** A stage DAG: geography cells, heightfield, carvers, feature anchors (one attempt per cell,
   with a declared maximum footprint), rasterise in a canonical order. A feature reads the heightfield and anchors,
   never blocks another feature wrote; large sites are planned as data. Vanilla and mod features that break this are
   wrapped into the anchor stage or turned off. Every stage answers `sample(x, z, lod)`, so the far field asks only for
   the detail it needs.
3. **Public and secret.** Two keys: the public seed drives geometry (what every client generates and the far field
   draws), a server-only key drives secrets (ores, loot, hidden rooms, traps, quest objects). Clients generate
   placeholder stone there; the server sends a section's secret overlay once it is in radius and in line of sight,
   with a look-ahead of ping times speed (Valorant's fog of war). Separate hash roots for the public base and the
   overlay. Decoys come from the server key and persist; a honeypot hit is evidence for the host's review, never a ban
   by itself; any action gives a reason and an appeal (EU DSA Article 17, GDPR Article 22). The hardest to retrofit,
   so decided first.
4. **Edit semantics.** The edit layer holds absolute overrides (the block's final state with the base value's hash),
   not deltas; an edit back to the base deletes itself, so dig-and-refill stays bounded. Checkpoint after N entries, B
   bytes or unload; the log is truncated once every client has acknowledged past it. Doors, crops and similar churn
   are block-entity state, not log entries. Generator upgrade: untouched regions regenerate; edited ones keep their
   generator version or are materialised once; a blended band between; stored base hashes flag edits that now
   conflict with the new base.
5. **Hash tree v1.** A fixed spatial shape (world, region, chunk, section; one edit is about four rehashes, batched per
   tick), BLAKE3 with domain separation (leaf and node prefixes, RFC 6962), hash and generator versions in every
   leaf, a canonical section encoding (palette order and padding fixed). Untouched base hashes are pure functions of
   seed, version and position, computed lazily. Sync top down from region roots.

### Own all of it: one spectrum, no vanilla terrain (owner, 2026-10-19)

No near and far at all: one representation whose detail follows each part's importance on screen and its visibility,
so distance stops mattering. Vanilla's terrain rendering is removed, not turned down (it gets no terrain geometry; it
keeps entities until those are ours too; if owning it all needs mixins, that rule is the owner's to revisit). Drawing
picks its method per piece, not per ring: the groups' rectangles raster as meshes where that is cheapest (close, on a
weak GPU) and are traced where that wins (far, many blocks a pixel), with the same cards and light, so nothing shows
where the method changes. Owning the drawing means owning the look: what vanilla would draw does not bind us (smooth or
stylised terrain is a rendering choice; smooth collision would change the server's world and is a later experiment).
A research survey of games and papers grounds the plan before the build.

Order: deterministic generation with a test (client-generated vs server-real per chunk), then the client-generated far
field with the hash tree and the change log, then interest-driven live state, then the near field.

Targets: first far image under 100 ms after arrival (stand-ins), within dE 3 of settled in under 2 s on a GPU, no jump
between seconds over dE 6; a return to a visited place loads from cache with no visible refinement.
