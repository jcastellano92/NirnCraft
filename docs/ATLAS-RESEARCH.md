# Infinite ATLAS: what other games and recent research do (survey, 2026-10-19)

Commissioned by the owner to ground docs/ATLAS-STREAMING.md ("own all of it: one spectrum, client-owned world, server
checked"). Marks: **[V]** checked against the linked source during the survey, **[K]** well-known published work not
re-fetched, **[I]** inference. Sources the survey could not verify are left out or marked unknown. Research, kept as
the grounding for the design; what was decided from it is in [ATLAS-RENDERER-PLAN.md](ATLAS-RENDERER-PLAN.md) and the
present state in [ATLAS-STATUS.md](ATLAS-STATUS.md).

## Games

- **Minecraft Bedrock**: `client-side-chunk-generation-enabled` (default on) lets clients generate visual chunks beyond
  interaction distance; the server stays authoritative near players [V]
  ([server.properties](https://learn.microsoft.com/en-us/minecraft/creator/documents/bedrockserver/server-properties)).
  The closest shipped precedent for a client-generated world; visuals only.
- **Distant Horizons** 2.3: server-side LOD generation sent to clients, fading between DH and vanilla, a large memory
  cut [V] ([changelog](https://modrinth.com/mod/distanthorizons/version/2.3.0-b-1.21.1)); still a separate renderer.
- **Voxy**: chunks to a 5-level mip hierarchy, GPU traversal with occlusion culling [V]
  ([repo](https://github.com/MCRcortex/voxy)); VoxyServer voxelizes on the server and pushes re-voxelized edited
  sections [V] ([voxyserver](https://github.com/poosmacker/voxyserver)).
- **Nvidium** (mesh-shader Sodium backend, NVIDIA only, off under Iris) [V] ([Modrinth](https://modrinth.com/mod/nvidium));
  Bobby caches server chunks to draw past view distance [K]. Common thread: far field bolted onto vanilla; nobody
  unifies near and far [I].
- **Teardown multiplayer** (March 2026) [V] ([Gustafsson](https://blog.voxagon.se/2026/03/13/teardown-multiplayer.html)):
  syncing voxel deltas choked on big destruction; now destruction is a reliable ordered stream of integer commands (the
  breakage code rewritten in fixed point, so a command's size does not depend on the object's), transforms are
  unreliable state sync with a per-client priority queue (visible first) under ~1 Mbit/s; late join replays commands
  (a snapshot is 30-50 MB) and is refused once the command buffer is full; up to 12 players.
- **No Man's Sky**: procedural from a formula with edits layered on; edit buffers are finite and the oldest are evicted
  (terrain "regenerating") [community sources; patch notes V]: a bounded delta layer loses edits.
- **Space Engineers**: long-standing voxel desyncs (mined asteroids revert on reconnect; players asking for
  checksum-validated caches) [V] ([report](https://support.keenswh.com/spaceengineers/pc/comments/2135/vote/toggle)).
- **Roblox** smooth terrain: seams at LOD transitions, breaking with low streaming radii [V]
  ([DevForum](https://devforum.roblox.com/t/smooth-terrain-lod/26868)).
- **Vintage Story**: no official far LOD (a mod draws heightmaps to ~4 km) [V]. **Hytale**: early access January 2026,
  chunk-based legacy engine (the C++ rewrite dropped, per unofficial sources); no official renderer details.
  **Enshrouded** (voxel, 16 players) and **Dual Universe** (single-shard voxel MMO, shut down August 2025): sync not
  public. Veloren, Luanti, Valheim, 7 Days to Die, Astroneer, Voxel Plugin: no primary technical sources found.

## Rendering research

- **Aokana** (2025) [V] ([arXiv 2505.02017](https://arxiv.org/html/2505.02017v1)): many shallow 256^3 SVDAGs with 4^3
  64-bit bitmap leaves (our micro-voxel grain), separate colour blocks, streaming by distance-scaled error, 8x8-tile
  previous-frame Hi-Z culling re-tested on fast camera moves; ~6 ms at 64K^3 with ~5 % resident; no edits, no
  transparency. The closest academic match to our renderer.
- **DAGs**: SVDAG (Kämpe 2013) [V], HashDAG for edits (Careil et al. 2020) [V], Transform-Aware SVDAGs (Molenaar and
  Eisemann, i3D 2025 best paper, code in the 2025 thesis) [V]
  ([TU Delft](https://research.tudelft.nl/en/publications/transform-aware-sparse-voxel-directed-acyclic-graphs/)):
  Minecraft terrain repeats, dedup at 4^3 / 16^3 is a large cheap win [I].
- **Aggregate appearance**: SGGX [K]; ABSDF closed-form per-voxel aggregate (2024, 46.9 to 5.33 GB) [V]
  ([arXiv 2409.03761](https://arxiv.org/html/2409.03761v1)); neural prefiltering (Weier 2023) [V]; neural voxel LOD
  is research only [I].
- **Nanite in UE 5.7**: Nanite Foliage built on Nanite Voxels; VOXELIZE shape preservation turns distant triangles into
  near-pixel voxels so foliage keeps its volume [V]
  ([docs](https://dev.epicgames.com/documentation/unreal-engine/nanite-foliage)); two-pass previous-frame HZB
  occlusion and a screen-space-error cluster DAG [K] (SIGGRAPH 2021). Epic concluded voxels with preserved coverage
  beat triangles far away.
- **Lumen**: cards captured at a resolution following screen size, a per-frame texel budget, priority re-capture [K]
  (SIGGRAPH 2022). Our 16x16 surface cards are this design.
- **Many lights**: MegaLights (fixed rays a pixel, importance-sampled lights: cost independent of light count) [V]
  ([SIGGRAPH 2025](https://www.advances.realtimerendering.com/s2025/content/MegaLights_Stochastic_Direct_Lighting_2025.pdf));
  Area ReSTIR 2024 [V]; ReSTIR-sampled shadow maps 2025 [V].
- **Radiance cascades**: Split Radiance Cascades (Freeman and Sannikov, July 2026: sparse world-space probes in a
  hashmap, rays split by hit distance) [V, via alphaxiv] ([arXiv 2607.20384](https://www.alphaxiv.org/abs/2607.20384)).
  Licensing flag: Sannikov's bilinear upscaler reportedly cannot be licensed [V]
  ([three-rc](https://github.com/CodyJasonBennett/three-rc)): check before adopting. DDGI [K] as the iGPU fallback.
- **Smooth surfaces**: Transvoxel seams [K]; Occupancy-Based Dual Contouring (SIGGRAPH Asia 2024: sharp meshes from
  binary occupancy, no SDF) [V] ([arXiv 2409.13418](https://arxiv.org/html/2409.13418v1)): our 4^3 micro-voxels are an
  occupancy field, so smooth or stylised terrain needs no new data model [I].

## Networking

- **Factorio**: deterministic lockstep, per-tick CRC, a mismatch is a desync and a full map download [V]
  ([FFF 47](https://factorio.com/blog/post/fff-47), [55](https://factorio.com/blog/post/fff-55)).
- **Teardown**'s hybrid: ordered deterministic ops for world mutation, prioritised state for dynamics [V].
- **Unreal Iris**: float priorities that accumulate until an object is sent, with owner, connection, group and dynamic
  filters [V] ([prioritization](https://dev.epicgames.com/documentation/unreal-engine/iris-prioritization-in-unreal-engine)).
- **Star Citizen server meshing**: a separate replication layer (graph database of entity state); game servers are
  nodes receiving subsets, single authority per entity migrating [V] (CIG Q&A).
- **EVE** time dilation, **PlanetSide 2** ~1000-player fights [K]: server authoritative, no client-generated world.
- Delta encoding with DEFLATE beats naive voxel sync widely [V] (Oulu thesis 2025).
- **Hash trees for world sync: no shipped game found** [I]. The technique is distributed databases' anti-entropy
  (Dynamo, Cassandra) [K]. CRDTs are not needed when the server orders edits [I].

## Visibility-driven scheduling

Previous-frame HZB with re-tests of disoccluded tiles (Nanite [K], Aokana [V]); GPU feedback buffers for page and
brick requests (sparse virtual texturing, GigaVoxels) [K]; foveated and dynamic screen-space error, request culling
while the camera moves (Cesium 3D Tiles) [K].

## Combinations nobody has shipped [I]

1. **The DAG is the Merkle tree**: content-hashed DAG nodes give dedup and a hierarchical world hash in one structure;
   client and server compare region roots and walk only into differing subtrees (cost: differences times depth).
2. **Seed as layer 0, ordered ops as layer 1, both hashed**: per region the generation version's hash and the op log's;
   old ops compacted into hashed sparse overlays (no late-join cap, no eviction loss).
3. **One importance metric** (projected error from the renderer's GPU feedback) feeding generation, card capture and
   network requests; on the server an Iris-like accumulating priority per player and region from that region's
   projected size at the player.
4. **Edit mips**: the server aggregates ops per LOD (changed occupancy and mean colour at 1, 4, 16 m); thousands of
   distant observers get one tiny coarse message per region, not every edit: bandwidth then scales with entities and
   nearby edits, not the world (what makes very large player counts plausible).
5. **Far LOD straight from the seed**: evaluate the generator's density and biome functions at coarse resolution for
   far nodes (no full chunks generated and downsampled), corrected by edit mips: horizon from the seed even on CPUs.
6. **Coverage-preserving aggregate per node** (occupancy, mean albedo, normal lobe): far forests and lattices keep
   their volume.
7. **Hashed sparse world-space probes keyed by the hierarchy** (radiance cascades or DDGI): light streams and evicts
   with the geometry.
8. **Server meshing cheap**: a region's authoritative state is a hash and a short op log, so handing it between server
   nodes is small.
9. **One representation, a back end per machine**: CPU packet ray casting at low resolution with the temporal resolve;
   iGPU Hi-Z culling and raster of the box hierarchy; high-end full ray tracing.

Caveats to design for: **x-ray** (a world generated on the client reveals ores and secrets: a server-secret salted
layer, disclosed by proximity or line of sight; seeds can be cracked from terrain anyway [K]); **bitwise determinism**
(anything hashed must generate bit-identically on every machine: no order-dependent reads, no fast-math native code;
Teardown went fixed point).

## Top recommendations (survey)

| # | Recommendation | Source |
|---|---|---|
| 1 | Shallow per-region DAGs with 4^3 bitmap leaves, dedup at 4^3 and 16^3; try transform-aware matching | Aokana [V]; Molenaar and Eisemann [V] |
| 2 | Content-hash DAG nodes; region roots as the Merkle tree for verification and sync | SVDAG [V] + anti-entropy [K] |
| 3 | Edits as ordered integer deterministic ops on a reliable stream; entities by per-client priority budget | Teardown [V] |
| 4 | Compact op logs into hashed delta overlays per region | Teardown's cap [V]; NMS loss |
| 5 | Region hash checks with desync, then resync of the subtree only | Factorio [V] |
| 6 | One shared screen-space-importance priority for interest, plus edit mips for distant observers | Iris [V]; Cesium [K] |
| 7 | One GPU feedback buffer (previous-frame Hi-Z, disocclusion re-test) feeding generation, cards and network | Nanite [K]; Aokana [V]; SVT [K] |
| 8 | Prefiltered aggregate appearance per node | Nanite Voxels [V]; ABSDF [V]; SGGX [K] |
| 9 | Hashed sparse world probes (or DDGI on iGPU) plus MegaLights-style fixed-budget direct light; check licensing | arXiv 2607.20384; MegaLights [V] |
| 10 | Server-secret layer for ores and loot; deterministic generation for anything hashed | Bedrock [V]; Teardown [V] |

# Survey 2 (2026-10-19): deterministic generation, log compaction, information-leak anti-cheat, hash-tree sync

## Deterministic, order-independent generation

- Counter-based RNGs: Philox and Threefry (Salmon et al. SC11, [Random123](https://www.thesalmons.org/john/random123)),
  Squares (Widynski, [arXiv 2004.06278](https://arxiv.org/abs/2004.06278v6)) [V]: rng(seed, stage, x, z, i) is a pure
  function, so order and threads cannot matter.
- Minecraft's `random_spread` structure placement is one attempt per grid cell ([docs](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/worldgenreference/examples/jigsawstructuresets)) [V]:
  the coarse-cell pattern.
- Minecraft's order dependence: MC-55596 (chunks differ by generation order, unresolved), MC-272481 (one RNG shared,
  Won't Fix), MC-241546 [V]; decoration reads and writes a 3x3 neighbourhood "that may or may not be populated"
  ([LimitedRegion](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/generator/LimitedRegion.html)) [V].
- Java/Bedrock parity (1.18): terrain and biomes match, decoration and structures do not [V]: pure density functions
  port, decoration does not.
- Far terrain from noise without chunks exists for Distant Horizons (Turbo, Tellus fast LOD) [V].
- Floating point: Java strict since 17 ([JEP 306](https://openjdk.org/jeps/306)) [V] but `Math` transcendentals may
  differ by 1 ulp per platform (`StrictMath` is reproducible) [V/K]; GCC contracts FMA by default, Clang 14 too [V];
  SPIR-V `NoContraction` stops fusing, Vulkan allows sin/cos error 2^-11 [V]: integer or fixed point is the only path
  to GPU = CPU bit for bit.
- Design: integer arithmetic for every discrete outcome (Philox-4x32 or Squares keyed by seed, generator version and
  stage; hashed-lattice noise with fixed-point gradients; floats only in cosmetic client-only layers); a stage DAG
  (geography cells, heightfield, carvers, feature anchors per cell with a declared footprint, rasterise in a canonical
  order), features never read rasterised blocks (they ask the heightfield); large sites planned as data; every stage
  `sample(x, z, lod)`; a harness gate: the same region in shuffled orders, 1 and N threads, x86 and ARM, two JVMs,
  hashes compared. Pitfalls: Math transcendentals, HashMap iteration order, parallel reductions, a shared RNG, injected
  vanilla or mod features, a 1-ulp difference crossing a threshold.

## Compacting change logs into state

- Event-sourcing snapshots ([Kurrent](https://kurrentdb.kurrent.io/blog/snapshots-in-event-sourcing/)), LSM compaction
  (RocksDB write amplification 20-30x, space ~1.1x) [V], Git delta chains (depth 50, max 4095) [V], SQLite WAL
  checkpoints (a long reader starves them) [V]; CRDT tombstones are not needed with an authoritative ordered log [I].
- Games: Minecraft region files never compact [V]; Space Engineers saves an edited asteroid whole forever [V]; No Man's
  Sky caps edits and evicts old ones (terrain regrows) [V, community]; Minecraft upgrades chunks with DataVersion and
  DataFixerUpper, and 1.18 blends new terrain to old chunk borders [V].
- Design: per region {generator version, base root, edit layer, log tail, checkpoint sequence, state root}; the edit
  layer holds absolute overrides (the final state, not a delta) with the base value's hash; an edit equal to the base
  is deleted (dig-and-refill churn stays bounded); checkpoint on N entries, B bytes or unload, then truncate once
  every client acknowledged; generator upgrades: untouched regions regenerate, edited ones keep their frozen generator
  or are materialised once, a blending band between; the stored base hashes flag edits that conflict with a new base.
  Pitfalls: cosmetic churn (doors, crops) belongs in block-entity state, not the log; never evict edits.

## Anti-cheat for information leaks

- Paper anti-xray modes 1-3 ([docs](https://docs.papermc.io/paper/anti-xray)) [V]: hides only ores covered by solid
  blocks; seed reversing exposes ores; Orebfuscator rewrites packets with line-of-sight checks [V].
- Valorant Fog of War ([Riot 2020](https://www.riotgames.com/en/news/demolishing-wallhacks-valorants-fog-war)) [V]:
  a precomputed PVS answering optimistically, the box expanded by velocity times a look-ahead over ping, missed events
  replayed; under 2 % of server frame. (CS2 server-side occlusion: no primary Valve source found.)
- Honeypots and decoys: Dota 2 (2023, ~40,000 banned for reading a secret client region), Call of Duty Ricochet's
  "hallucinations" (decoys only flagged players see; "Quicksand" dropped as too jarring), Fall Guys' cheater island
  abandoned (too few cheaters to fill matches, escapes by partying) [V, secondary]; innocent CoD players were banned by
  signature injection [V, secondary].
- Legal: EU DSA Article 17 wants a statement of reasons for visibility restrictions (a shadowban was held to fall under
  it) [V, secondary]; GDPR Article 22 covers solely automated decisions [V, secondary]; no case law on deceptive game
  data found.
- Design: split the seed: the public seed drives geometry, a server-only key drives secrets (ores, loot, hidden rooms,
  quest objects); clients generate placeholder stone there, the server streams the secret overlay per section once in
  radius and in line of sight (look-ahead over ping times speed); separate hash roots (public base vs server overlay);
  persistent decoys from the server key; the far field draws only the public layer; honeypots are a signal for human
  review, never the sole basis of a ban; bans with a stated reason and an appeal path. Pitfalls: secrets derived from
  the public seed; packet size and timing side channels; honeypot data inside a verified hash; small populations.

## Hash-tree sync

- Cassandra Merkle repair (depth 15, overstreaming, validation cost; incremental repair) [V]; rsync, IPFS [K]; Git's
  SHA-1 to SHA-256 move (version fields had to be in the format) [V]; Merkle pitfalls (CVE-2012-2459 duplicated leaf;
  RFC 6962 domain separation 0x00 leaves, 0x01 nodes) [V]; BLAKE3 is itself a Merkle tree [V]; Factorio uses per-tick
  CRCs [V]; no game found using Merkle anti-entropy [V, negative result].
- Design: a fixed spatial tree (world, region, chunk, section: one edit about 4 rehashes, batched per tick); BLAKE3
  with domain separation, the hash and generator versions in every leaf; a canonical section encoding; base hashes as
  pure functions of seed, version and position (lazy, cached); sync top down from region roots, sections as leaves.

## Five decisions first (survey 2)

1. Integer or fixed-point arithmetic and one counter RNG for every discrete generation outcome; no `Math.*` in
   generation; the shuffled-order and cross-platform harness gate.
2. The generation contract: stage DAG, maximum feature footprint, no stage reads rasterised neighbours; vanilla and
   mod features wrapped into the anchor stage or disabled.
3. The public/secret split (which content moves to a server-keyed overlay): the hardest to retrofit.
4. Edit semantics: absolute overrides with auto-cancel, checkpoint cadence and truncation, pin or materialise on
   generator upgrades.
5. Hash-tree format v1: spatial shape, BLAKE3 with domain separation, canonical encoding, versions in every leaf.
