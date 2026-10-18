# Mod integration (staged; NeoForge 26.3)

These classes join aether_core to the Skycraft mod.

**Moving in:** `move_in.ps1` does steps 1-4 below (dry run by default, `-Apply` to write; every patch is anchored,
idempotent and refuses to guess). `sandbox_check.ps1` rehearses it without touching the main tree: it snapshots the
main checkout's `mod/` sources, applies the move-in to the copy and compiles the whole mod with javac against the
conversion's patched 26.3 jar and Gradle dependencies. As of 2026-10-18 the converted mod with aether moved in
compiles with 0 errors. They are written against the NeoForge 26.3 / Minecraft 26.3
signatures read from the real jars (`javap` on `neoforge-26.3.0.90-universal.jar` and Mojang's 26.3 client jar).
They are kept here, not in `mod/`, until the Forge 1.20.1 → NeoForge 26.3 conversion is committed: the mod does not
compile against 26.3 before that. Then:

1. Copy `src/com/skycraft/aether/` and `../src/com/skycraft/aether/AetherNative.java` into
   `mod/src/main/java/com/skycraft/aether/` (drop `AetherProbe.java`; `compile-stubs/` and `harness/` stay here).
2. Package the DLL: copy `native/build/Release/aether_core.dll` to
   `mod/src/main/resources/natives/windows-x86_64/aether_core.dll` (a release build step, like the shader pack zip).
3. Call `AetherClient.init(modBus)` from the client setup of `Skycraft.java`, `AetherServer.init()` from common
   setup, and add `com.skycraft.aether.AetherNet.register();` to the module list in `SkyNetwork.init`.
4. Wire the weather feed: `WeatherFieldTexture` implements `AetherClient.WeatherFeed` (it already builds the four
   RGBA8 fields). The column source needs no wiring: `AetherClient` picks `AetherExactSource` in single player,
   `AetherServerSource` when the server says Hello (it streams tiles), else the seed-free raster source.
5. Ship `shaderpack/build/SkycraftAether.zip` in the pack's `shaderpacks/` and select it in `iris.properties`.

## Exact columns (single player and multiplayer)

`AetherExactSource` evaluates the server's own `skycraft:tamriel_height` (the four seeded noises taken from the
server's `RandomState`, so they are the instances its chunk generator samples) at every far column on a small
worker pool (`skycraft.aether.columnThreads`, default a quarter of the cores), and uploads surface + water heights
with `aether_upload_column_heights`; aether dresses them (materials, forest canopy) from the bundled raster. Caves and
the 3D overhang term are not in the far field. Water: sea level below y 63 (sea and near-surface aquifers), the map's
river/lake level above it, filled to `ceil(level)` like `MapWaterFeature`.

Exact heights on the GPU (`AetherHeightGpu`, `aether_height_*`, `native/shaders/aether_height.slang`): in single
player the seeded noise calls of `TamrielHeight.height` (half of its CPU time) run on a Vulkan compute kernel that is
bit-identical to Minecraft's `NoiseStack`/`PerlinNoise` (no contraction, RTE, signed zeros kept, no division or
transcendentals, every possible denormal flagged back to the CPU); `TamrielHeight.height` itself still runs on the
CPU with those noise values replayed into its `WorldNoise` objects. The first tile of each LOD is computed both ways
and compared bit for bit, later tiles on 64 random columns; any difference, a device without the float controls, or
a GPU path that is not faster than the CPU on the first LOD 1-2 tiles, and it is the CPU path for the rest of the
session (logged). `-Dskycraft.aether.gpuHeight=false` keeps the CPU. Dedicated servers and the tile server always
use the CPU path; the tile bytes are identical either way. The noise tables are read by reflection from the live
`NoiseStack`/`GradientNoise` objects: if the game's module setup refuses that, the CPU path is used (an access
transformer for `NoiseStack.layers`, `NoiseStack$Layer` and `GradientNoise.perms/offsetX/Y/Z` would enable it).

Every source shows the raster stand-in for the whole window at once and refines it tile by tile, nearest first
(`AetherRefiningSource`), so the horizon is never empty.

Multiplayer: clients have no seed, so the server computes the same tiles (`AetherTileServer`: one tile at a time over
a quarter of the cores, cached under `<world>/data/skycraft_aether/`) and sends them on request (`AetherNet`:
`Hello` on login, `TileRequest` up to 32 tiles, `TileData` one tile; a request is honoured only inside the player's
clipmap window, 24 in flight per player; the client re-asks after 20 s and caches tiles under
`aether-cache/<world id>/`, the id a salted hash, never the seed). `AetherTileCodec` quantises heights to 1/8 of the
tile's voxel by interval (decoded to the interval's middle, so no height crosses a voxel centre in transit) and
single player quantises identically: a server-fed far field is bit-identical to the single-player one.

Biome tints (`AetherBiomes`, `AetherTints`): far grass and broadleaf canopy wear the world's own biome grass and
foliage colours (the generator's biome source and climate, sampled on a 64 x 64 grid per tile), as tinted palette
entries (`aether_upload_column_heights2`), so the hand-off to the near field shows no hue jump. Single player samples
them itself; the server sends them with each tile (codec v2: two small rgb planes); both register the same colours.

Far actors (`AetherFarActors`, server): twice a second, per overworld player, the players, dragons, ships and large
creatures past that entity type's tracking range and within 4 km (nearest 96), with velocities; the client moves them
on between packets and draws them as impostors.

Settlements (`AetherSettlements`, client, single and multiplayer alike): the StreetPlan (every named capital, city,
town, village and farm; baked, seed-free) becomes per-tile structure layers (`aether_upload_column_structures`):
grammar buildings as their blueprint parts with wall top, roof rise and roof shape (gable along the right axis, hip,
flat/crenel, dome, spire), city walls as lines one column thick at least at every LOD, gates and towers as boxes,
landmarks as towers. Colours are the kit blocks' map colours (`aether_set_materials`, ids from 64). The structure
layer survives the ground tile being refined and is evicted with it.

It is exercised before the mod compiles on 26.3:

* `height-subset/build_subset.ps1` compiles the mod's height path (`files.txt`, snapshotted read-only from the main
  tree's `mod/`) against the real 26.3 jars, with `stubs/` for the few edges out of it (each stub says why it is
  safe: endless-lands tile planning is off, town street plots are below voxel size, the baked road plan is read
  without its staleness check).
* `compile_check.ps1` compiles all the glue against 26.3 + NeoForge + that subset.
* `harness/run_exact_harness.ps1 [-Seed n -X x -Z z -Yaw deg -Threads n]` runs vanilla's bootstrap, builds
  `tamriel_height` for a seed exactly as `RandomState`/`Noises.instantiate` does, streams the full clipmap window
  through `AetherExactSource` into aether_core.dll, and renders exact vs the raster stand-in
  (`native/out/exact_source.png`). It also plays the multiplayer flow back in process (the harness's `SkyNetwork`
  hands the client's requests to an in-process "server") and checks the result. Measured (seed 12345, origin):
  * the 7-LOD window (175 tiles, 11.5 M columns) in 4.8 s on 8 threads, 3.3 us per column per thread;
  * tiles average 27 KB on the wire (LOD 0: 19 KB, LOD 6: 15 KB), the whole window 4 MB in 14 requests; a second
    client on the same cache needs none;
  * server-fed vs single-player render: 0 of 1,017,500 terrain pixels differ;
  * the stand-in's surface is a median 28 blocks off the real one (its synthetic relief, not misregistration).
* `harness/run_settlement_harness.ps1 [-Place whiterun]` runs the real mod classes (sandbox_check's build) on vanilla
  26.3 + NeoForge + FML jars: exact terrain plus the StreetPlan's buildings around a city from 2.4 km and 800 m
  (`native/out/settlements.png`); Whiterun's wall ring, towers, houses and Dragonsreach read from 800 m.
  The shipped bakes are read without their inputs check (the converted code invalidates them; planning live needs
  the game's registered structure pieces).

Verified against the jars:

| Need | 26.3 API |
| --- | --- |
| render hook | `RenderLevelStageEvent.AfterOpaqueBlocks` (NeoForge), `getLevelRenderState().cameraRenderState` |
| camera | `CameraRenderState.pos`, `.projectionMatrix`, `.viewRotationMatrix`, `.depthFar` |
| sun | `LevelRenderState.skyRenderState.sunAngle` |
| textures Iris samples | `RenderSystem.getDevice().createTexture(String, int usage, GpuFormat, w, h, 1, 1)`, `GlTexture.glId()`, `TextureManager.register(Identifier, AbstractTexture)` (Iris resolves `customTexture` through `TextureManager.getTexture` → `GpuTexture.iris$getGlId`) |
| exact columns | `ServerChunkCache.randomState().getOrCreateNoise(ResourceKey<NormalNoise>)`, `NoiseBasedChunkGenerator.stable(ResourceKey<NoiseGeneratorSettings>)`, `Minecraft.getSingleplayerServer()` |
| packets | `SkyNetwork.register(Class, PacketFlow, encoder, decoder, handler)` (the mod's payload layer), `FriendlyByteBuf.readByteArray(int max)` |
| server events | `ServerStartedEvent`, `ServerStoppingEvent`, `PlayerEvent.PlayerLoggedInEvent/LoggedOutEvent`, `ServerTickEvent.Post`, client `ClientPlayerNetworkEvent.LoggingOut` |
| far actors | `ServerLevel.getAllEntities()`, `EntityType.clientTrackingRange()`, `PlayerList.getViewDistance()`, `EnderDragon`, `AbstractBoat` |
| other dimensions | `CommandEncoder.clearColorTexture` on the two pack textures (coverage 0: near field only) |
| near-field depth | `AETHER_GL_BOUND_DEPTH`: aether reads the depth attachment of the framebuffer bound at that stage (Iris's gbuffer target) |
