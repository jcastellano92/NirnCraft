# Infinite ATLAS: what other games and recent research do (survey, 2026-10-19)

Commissioned by the owner to ground docs/ATLAS-STREAMING.md ("own all of it: one spectrum, client-owned world, server
checked"). Marks: **[V]** checked against the linked source during the survey, **[K]** well-known published work not
re-fetched, **[I]** inference. Sources the survey could not verify are left out or marked unknown.

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
