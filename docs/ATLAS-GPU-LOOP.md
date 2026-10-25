# Infinite ATLAS: the GPU-driven world loop and the baked world

Design written 2026-10-24 by an Opus planning agent, a read-only review of atlas-int bd80405f. Its order of work is folded
into [ATLAS-PLAN.md](ATLAS-PLAN.md) (stages 1, 2 and 5). Line numbers are approximate as of that commit.

**Root cause of the spikes.** Decoration runs one feature-placement interpreter per origin, on one lane
(`featBody` → `runPlaced` → `placeTreeAt` → `tCommit`, atlas_features.slang ~1892-3360).
- A tree keeps its leaves, logs and trimmings in fastutil-exact hash tables in global scratch. Each `fmFind` is a chain
  of dependent 64-bit global loads.
- The commit runs three breadth-first searches (rooted logs, reachable leaves, leaf distance), probing 26 or 6
  neighbours per step.

That is about 10⁵ dependent loads on one lane, which matches the measured 20-60 ms a tree. The tree round's budget is
one placement (`featBudget_`, features_engine.cpp ~912), so a round is never shorter than its slowest tree and can't be
preempted.

## 1. Every CPU touch from deciding to drawing

RT = a GPU→CPU→GPU round trip. "Frame?" = whether it blocks or delays a frame.

| Step | File / function | What the CPU does | RT | Frame? |
|---|---|---|---|---|
| Decide (near) | `AtlasClientGen.run / plan / next` | Orders the near window in Java | no | no |
| Decide (far) | `AtlasClientGen.farStep`, `atlas_gen_coarse_window / coarse_room` | Scans every level's window, picks one tile a turn, runs retry timers | control | no |
| Decide (residency) | vk_backend ~2226 `vgRankBack` → `VoxGrid::rankResults` → `takeGpuWanted/Dropped` → `gridReports` | Reads back the GPU ranking every frame, builds host lists, Java resends | **yes** | **yes** |
| Cache lookup | `fromCache` → `GenCache::chunk` | CPU index | no | no |
| Cache load | `atlas_gen_cache_ingest` (~3519), far cache ingest | Disk, then a **CPU range decode** on 8 threads (sectioncodec.hpp), `putPacked`, staged by the render thread | disk→CPU→GPU | **yes** (staging) |
| Generate | `VulkanEngine::run` (~1219) | Host memcpys; **one `submitWait` per pass or slice**, plus pacer sleeps | control | queue |
| Generate (readback) | `run` ~1352-1392 | Flags and 164 info words per chunk; non-store ids | **yes** | no |
| Store schedule | `storeInsert/Advance/Ready/Take` (~2440-2620) | CPU hash-map scans of the store's DAG | control | no |
| Store stages | `storeStage` (~1140) | One `submitWait` per part | control | queue |
| Decoration rounds | features_engine `decorate` ~842-937 | **Reads back `left_` every round**; origins built on the CPU | **yes** | **yes: 85% of spikes** |
| Overlays | `AtlasOverlayLive`, `AtlasOverlayStarts` | Tapes recorded once per settlement | no | no |
| Far | `farPasses` (~822) / `farIngest` (~995) | 4 passes × tiles × `submitWait`; **kinds read back**, host lists, then `ingestCoarseGpu` | **yes** | no |
| Claim | `directBatch`: `AtlasEditsClient.claim` | Java hash checks against edits | **yes** | no |
| Ingest | `VulkanEngine::ingest` (~1425) | Host slab free lists (sleeps up to 2 s), copy, `submitWait`, host lists | control | indirectly |
| Grid bookkeeping | `stageVoxGrid`, `take0Gpu`, VgBatch | Host per-frame grid jobs, **13-19 ms peak** | — | **yes** |
| Bake / warm tier | `bakeGpu` → `vgPackMain` → readback → `packedDone` → `putSection` | **CPU range encode**; warm tier in host RAM | needed for disk | lock fixed (75ca2547) |

## 2. Target: a GPU-driven loop

**The CPU's whole job:**
1. Every frame (or every 4 ms), submit a pre-recorded "gen tick" on the low-priority compute queue, chained by a
   timeline semaphore and never fence-waited mid-work.
2. Run one I/O thread.
3. Handle server messages: edits become delta uploads; server chunk requests go into the request ring.

**GPU structures:**
- **ResidencyTable**, per level and window cell: state, priority, edit epoch.
- **CacheIndex**: a bit per chunk or tile, from the region directories.
- **StoreState**: the store's slots, plus a GPU spatial hash.
- **EditSet**: section key to its delta list.
- **PartBudget**: units per ms for each kernel, fed by `vkCmdCopyQueryPoolResults`.

**Requests.** `vgRankMain / vgRankCutMain / vgRankListMain` extended over every level's window:
- Each absent cell gets a priority: footprint × visibility (last frame's per-cell hits, from `farMain`) × motion
  (camera velocity, 0.5-3 s lookahead).
- They're compacted into a **RequestRing** by priority bucket and split against CacheIndex: on disk → **LoadList**,
  not on disk → **GenList**, served from disk → the **IORing** (the only control readback, asynchronous).
- This replaces `plan`, `next`, `farStep`, `gridReports` and `fromCache`.

**Bounded work units.** A scheduler kernel computes stage readiness (the store's neighbourhood rule on the device) and
writes `VkDispatchIndirectCommand`s. Each kernel takes `min(listLen, PartBudget × 3 ms)` units, so no dispatch runs much
past ~3 ms. The decoration's "left" count feeds the next round's indirect arguments directly. Groups in flight are
capped (~SMs × 2) so the frame always finds free SMs.

**Results straight into the grid.** `secsMain` appends **Arrivals** {key, kind, hash, source offset}. `vgIngestMain`
then:
1. checks the EditSet and applies deltas (replacing Java's `claim`);
2. admits against the rank cut;
3. pops a slot from a GPU free stack;
4. writes the pool and the cell table;
5. chains `vgDownMain`.

Far chunks use the same path, so the kinds readback goes. Host `ingestGpu` lists, slabs and `gpuPendingOffsets` are
retired.

**Trees wide and still bit-exact.**
1. **Per-origin independence stays.** A chunk is its terrain plus its 9 origins' writes in z-major order; that order is
   the deterministic conflict rule. Within an origin, Java's order is kept exactly. Origins per dispatch go from 64 to
   every ready origin (512 or more).
2. **Trees as resumable phases in a workgroup** (64-256 lanes per tree):
   - **(a) The RNG spine stays serial, on lane 0**, with its maps in LDS: a dense occupancy bitmap of the tree's box
     (32×64×32 at 1 bit = 8 KB per set). The fastutil table is kept only for iteration order, holding positions only,
     with values in a parallel LDS array, and spilling to global past its capacity.
   - **(b) Order-free phases go wide**: rooted-log flood, leaf reachability, leaf-distance relaxation (≤ 7 sweeps) and
     the final writes run as frontier sweeps, one lane per cell.
   - **(c) Draws whose count is fixed by geometry use prefix sums** (`tClump` d > 0.45, `tTint`) plus Xoroshiro128++
     jump-ahead (a precomputed table of T^(2^i), 32 KB). Walks with value-dependent counts (`tHangVines`) stay on the
     spine.
   - **(d) Phase boundaries are checkpoints**: LDS to scratch, with an operation budget per lane.
3. **Parity:** the features_test dumps and AtlasGenFeaturesGpuTest stay unchanged (225/225).

**Persistence without CPU coding.**
- **Encoder:** `vgPackMain` becomes a GPU encoder into a host-visible **Outbox** ring. The I/O thread polls the timeline
  value and appends to the files.
- **Loading:** the I/O thread reads files into a mapped **Upload ring** (ReBAR where available), and the GPU decodes
  them into the pools.
- **Warm tier:** compressed region pages in pinned host memory, read by the GPU over PCIe.

## 3. The baked world as the primary path

Stage-1 slicing comes first, since the bake and the idle baker run the same generator.

**Whole-map coarse bake.** Levels 2-6 of all of Tamriel (28,000 × 21,500 × 704) through the far path (`tileMain`,
`tileBiomeMain`, `tileSurfMain`, `farChunkMain`, far trees), into region files loaded at boot:

| Level | Tiles | Mixed chunks | Stored |
|---|---|---|---|
| 2 | 616 | ~290k | ~150 MB with brick dedup |
| 3 | 154 | ~74k | ~45 MB |
| 4-6 | ~58 | ~25k | ~20 MB |

- **Size:** ~215 MB, ~120 MB after entropy coding.
- **Time on a 3090:** levels 3-6 in 7-15 s inside the first launch's loading phase; level 2 in 20-45 s after, spawn
  tiles first, while the player plays.
- Shippable only with a fixed seed.
- This is also stage 2's **floor**: where level-0 data exists, `vgDownMain` replaces the stand-ins with true
  downsampled voxels.

**Near.** The self-baking cache plus an **idle baker**: the RequestRing's tail along the predicted path, then a
spiral, run only from the pacer's idle tokens in bake-only mode. A full level-0 Tamriel is ~5.3 GB and ~40 GPU-min once
trees are fixed: optional.

**Region format "abr".** One file per 32×32 chunks.
- **Layout:** a header; a directory of 1,024 entries {offset, bytes, buildId, 40 × 2-bit kinds, 40 content hashes}; a
  brick dictionary; then section records.
- **Sections:** a palette (≤ 256) plus 64 bricks of 4³, each uniform (a palette index) or a deduplicated bit-packed
  brick (64-bit hash).
- **Entropy layer (optional):** 64 KB pages of 32-way rANS, or GDeflate via `VK_NV_memory_decompression`.
- **Decoder:** `abrPageMain` (a warp per page), then `abrSectionMain` (64 lanes per section, a lane per brick: expand
  through the palette, write the pool slot, apply deltas).
- **Encoder:** a group per section, palette by ballots in LDS, dedup by `atomicCAS` into a GPU region hash. Correctness
  is checked on decoded content hashes.

**Invalidation.**
- **Per-stage, per-region fingerprints:** F(stage, region) hashes the stage's code plus its data restricted to the
  region. For example, one settlement's tape counts only inside its box ± the stage radius.
- Each directory entry stores a **buildId**, an index into the world's table of fingerprint vectors. A mismatch at
  load marks only those chunks stale, for the idle baker. Today one global `gen-<fp>/` set wipes everything.
- **Edits** stay deltas, applied by the decoder; coarse levels are re-downsampled per edit epoch.
- **Verifier:** on idle GPU, regenerate random baked chunks (weighted to recently changed stages) into scratch and
  compare content hashes. A mismatch means a rebake request and a counter. `AtlasCacheWipe` remains the last resort.

## 4. Ending the duplicate server generation

**Quick win:** in unified + clientGen mode, cap the integrated server's view distance to the simulation radius
(`server.getPlayerList().setViewDistance(n)`, public API). Java then generates about 13×13 chunks, not the whole
render distance.

**AtlasChunkGenerator** (new worlds): `DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID)` with a `MapCodec`;
the world preset gets `"type": "nirncraft:atlas"`. It wraps `NoiseBasedChunkGenerator`, with no mixins or access
transformers.
- `fillFromNoise` queues a server-priority request and fills the ProtoChunk from the store's decorated chunk, then
  primes the heightmaps.
- `buildSurface` and `applyCarvers` are no-ops.
- `applyBiomeDecoration` runs Java placement only for structures the GPU doesn't cover (an allow-list), and replays the
  tapes' block-entity and spawn ops. The tapes must record them; `Painter.RECORDING` drops them today.
- Structures, base height, original mobs and biomes are delegated.

The server's world is then the GPU's world. The only readback is the simulation-radius chunks, once each.

**Risks:**
- Existing worlds store `minecraft:noise` in level.dat (check the 26.3 sources; fallback: new worlds only).
- Server workers must never block on the GPU during loading (server requests get the highest priority).

**Dedicated servers:**
- **(a)** A headless Vulkan generator.
- **(b)** The C++ build of the same kernels, bit-exact.
- **(c)** Java as a last resort, flagged by the GenHello fingerprint.
- Client-sent chunks aren't authoritative: only a LAN cache warmer, spot-checked.

**Collisions (stage 5):** with server = GPU world, the client's vanilla chunks in the simulation radius hold exactly the
drawn blocks. Gate: a scripted walk comparing level-0 ids against `ClientLevel` gives 0 mismatches.

### 4.1 Status (2026-10-25, branch atlas-server)

**Built.** `com.nirncraft.atlas.server`: `AtlasServerCap` (the integrated server's chunk maps use the simulation distance, at most 8:
`ServerChunkCache.setViewDistance` per level plus the chunk-cache radius packet. `PlayerList.setViewDistance` is undone on the next tick,
the integrated server sets it back to the render distance), `AtlasServerStats` (server ticks, worker CPU, chunks, in the route report),
`AtlasChunkGenerator` (`nirncraft:atlas`, a `NoiseBasedChunkGenerator` subclass; the world presets use it; 26.3 has `buildTerrain` for
fillFromNoise + surface + carvers), `AtlasServerFeed` / `AtlasServerGen` (the seam to the client generator), `AtlasServerAgreement`.
Existing worlds keep `minecraft:noise` in their saved dimensions; a datapack `data/minecraft/dimension/overworld.json` would replace it
(`WorldDimensions.bake` prefers the datapack's stems), the preset only gives it to new worlds. Native: `atlas_gen_store_prioritize`
(the server's chunks and their neighbourhoods first in the store), `atlas_debug_read_blocks` (test readback of level 0).

**How a chunk goes.** `buildTerrain` asks the feed; the generator's worker puts the chunk through the store ahead of the camera's window,
the decorated ids come back with `atlas_gen_run_store` and fill the proto chunk (heightmaps primed, block entities and mobs replayed from
the structures' side files, `AtlasSideOps`). Java makes the chunk instead (counted) when: no GPU yet, a structure the overlays do not carry
reaches it, its settlement or dungeon is not recorded yet, more than 24 requests are outstanding, the kernels flagged it, or 6 s passed.

**Measured** (route, fresh world and cache each, RTX 3090 shared with the client). Step 1, cap off vs on, Java generation both:
server worker CPU 1430 -> 1200 ms/s, new server chunks 2225 -> 1789, `buildTerrain` calls 2942 -> 2479, tick p99 20.1 -> 17.6 ms, view 12 -> 8.
Step 2 (GPU chunks, before the store priority): worker CPU 150 ms/s, tick p99 8.2 ms; 211 chunks from the GPU, 312 in Java, 156 timeouts.
**Gate not met**: Java `buildTerrain` calls are not 0. The store makes about 7 decorated chunks/s while the camera's window is also
filling (trees: stage 1 of the table above), so bursts (the first ring, flight) are Java and the walk is the GPU's. The share of GPU-made
server chunks matching the GPU world (all 8 neighbours GPU-made too): 71 of 77; the misses were deliveries with all-air ids, now refused
(`ids` against the store's own kinds). A Java neighbour's decoration writes into a GPU chunk (ore blobs: the 0.2 % parity gap), so the
hash gate is only exact once every chunk is GPU-made. Step 3: the collision walk (walk + sprint, 7,500 level-0 blocks read back from the
GPU against the client level) has 0 mismatches (`harness.py run atlas-collide`).

**Next:** after the trees' stage-1 work the burst fits; then raise `AtlasServerGen.MAX_OUTSTANDING`, drop the timeout, and make the
structure chunks the overlays do not carry (the site lists' sets) part of the tapes or an allow-list.

## 5. Order of work

First, measure: recorder `rt:` counters (fence waits, readbacks, host map reads per second by site), a per-tree ms
histogram (`ATLAS_FEAT_PART_TRACE`), per-dispatch GPU ms by phase, and queue-latency verdicts.

| # | Stage | Step | Gate |
|---|---|---|---|
| 1 | 1 | Trees as resumable phases with operation budgets; cap groups in flight | max generator dispatch ≤ 4 ms; route p99 ≤ 20 ms while generating; 0 dumps blamed on the generator; parity unchanged |
| 2 | 1 | Trees in LDS with wide order-free phases; 64 → 512 origins | tree median and max ÷10; decorated chunks/s ×5 |
| 3 | 1 | Timeline-semaphore ring instead of `submitWait`; budgets from query copies | generator fence waits/s ≈ 0; GPU bubbles < 10% |
| 4 | 2 | abr format, GPU decoder, I/O thread, upload ring | second launch full radius < 2 s; CPU decodeMs 0; staging ≤ 2 ms |
| 5 | 2 | GPU encoder and Outbox | CPU encodeMs 0; MB/km² vs 8.9 |
| 6 | 2 | Whole-map coarse bake (3-6 in loading, then 2) as the floor | first launch: complete view at spawn ≤ 20 s; route holes 0 |
| 7 | 2 | Per-stage, per-region fingerprints; verifier | a changed tape rebakes only its box; mismatches 0 after the rebake |
| 8 | 2 | GPU RequestRing replaces plan / farStep / gridReports; idle baker | second-visit generation 0; holes 0 at sprint and flight speed |
| 9 | 2 | GPU store scheduler and indirect stages | CPU round trips/s ≈ 0; generator thread CPU < 5% |
| 10 | 2 | GPU claim and ingest (EditSet, Arrivals, free stack) | no info or kinds readbacks; render-thread grid work ≤ 1 ms |
| 11 | 5 (can start now) | `setViewDistance` cap; AtlasChunkGenerator; dedicated C++ / Vulkan | Java `fillFromNoise` 0; server hash = client hash 100%; server tick p99 unchanged |
| 12 | 5 | Collision agreement test | 0 mismatches on the scripted walk |

**Risks:** large trees overflowing LDS (spill path); drivers ignoring queue priority (dispatch sizing is the real fix);
encoder dedup order (compare decoded hashes); far stand-ins against true downsamples (dissolve); the existing-world
generator swap; integrated server and client sharing the GPU during loading.

**Critical files:** atlas_features.slang and features_engine.cpp (trees); gen_engine.cpp (run, submitWait, pacer, store,
ingest, farIngest, cache ingest); voxgrid.cpp/.hpp and atlas_voxgrid.slang (RequestRing, Arrivals, free stack);
gencache.cpp/.hpp, sectionpack.hpp and sectioncodec.hpp (abr, fingerprints, I/O); AtlasClientGen.java (retire plan,
farStep, directBatch and claim); a new AtlasChunkGenerator.java.
