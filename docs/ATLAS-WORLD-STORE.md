# Infinite ATLAS: the self-baking world and unified entities (plan, owner 2026-10-21)

Direction the owner approved for after the world renders completely. Numbers are estimates to be measured, not
facts. Present state: [ATLAS-STATUS.md](ATLAS-STATUS.md).

## 1. The self-baking world (storage and loading)

Tamriel is bounded (about 28,000 x 21,500 blocks x 704 high: ~2.4 M chunk columns, ~104 M sections, ~850 GB raw).
So the world moves from "generated on demand" to "a streamed database":

1. **First visit:** the GPU generates the region (today: terrain, features, overlays as generator stages) and the
   residency manager keeps it; a background job packs it and writes it to the per-world disk cache, keyed by seed +
   generator version (program and map hashes) + region.
2. **Known region:** the cache key matches, generation is skipped, the packed region streams from disk into VRAM and
   is unpacked on the GPU. Loading and teleports cost an NVMe read, not generation.
3. **Edits:** stored as differences from the generated base (in progress, grid agent), applied on load; background
   compaction folds settled edits into a new base version.

Optional: an idle baker fills unvisited regions in spare GPU time; a coarse horizon cache (levels 3-7 of the whole
map, est. ~100 MB) loads at boot so the full horizon shows from the first frame; the whole continent could be baked once
and shipped.

**Size estimate** (to measure): one-id sections (sky, solid deep rock, open sea) need only a header; mixed sections
pack with a palette and few bits per voxel, skipping air under an occupancy mask; geometry deduplicated across regions
(shared 4x4x4 occupancy masks, a content-addressed DAG); runs along a space-filling curve; then a dictionary-trained
zstd (or GPU decompression, e.g. GDeflate) per region file. Target: single-digit GB for the whole continent, coarse
horizon ~100 MB, edits KB-MB.

**Files** (per world, in the instance, not the repo): `atlas-cache/<world>/horizon.atlas` (coarse levels),
`regions/r.<rx>.<rz>.atlas` (packed, compressed, with the generator fingerprint), `edits/d.<rx>.<rz>.delta`.

## 2. Multiplayer (owner: seed shared for now)

The server sends the seed + generator fingerprint (handshake, docs/ATLAS-CLIENT-GENERATION.md section 9); clients
generate or load from cache; the server never sends base terrain. Per region a hash (base hash ^ edit epoch): equal
means nothing is sent; otherwise only the edit deltas. Far edits (a house built miles away, a signal fire) arrive as
small brick deltas and show the next frame, emissive edits light the land through the light system. Interest by
spatial subscription (what each player can see), not radius. Ores are not secret while the seed is shared (owner's
choice for now; a server-only ore key is the alternative).

## 3. Unified entities (after the world renders completely)

All world-space entities in atlas, the same "immutable asset + mutable state" idea as terrain:
- Assets: entity models, armour, items converted at startup to object-space micro-voxel bricks (1/16 block), from the
  game's own models and textures.
- Per frame: a flat array of entity states (transform, pose parameters, held items, emission) handed to native.
- Near / mid: a GPU two-level BVH of oriented boxes over the limb bricks, traced with the terrain (shadows, light,
  bounce all automatic). Far: entities splatted into the grid's coarse levels as transient voxels (hundreds of
  distant players and creatures at near-zero cost). Animation on the GPU from compact motion state, so distant
  entities at 1-2 Hz updates still move smoothly.
- Block entities (chests, signs, banners) as static micro-bricks, dynamic only while animating.
- Stays vanilla: the GUI paperdoll; first-person hand to be decided.

## Order

1. Now: the world renders completely (every block, state and item in the world) on the GPU path, storage and loading
   (the self-baking cache, the loading phase), ready for the owner's single test.
2. Then: unified entities, the light system for dynamic lights, multiplayer handshake and deltas.
