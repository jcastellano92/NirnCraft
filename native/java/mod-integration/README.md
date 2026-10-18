# Mod integration (staged; NeoForge 26.3)

These classes join aether_core to the Skycraft mod. They are written against the NeoForge 26.3 / Minecraft 26.3
signatures read from the real jars (`javap` on `neoforge-26.3.0.90-universal.jar` and Mojang's 26.3 client jar).
They are kept here, not in `mod/`, until the Forge 1.20.1 → NeoForge 26.3 conversion is committed: the mod does not
compile against 26.3 before that. Then:

1. Copy `src/com/skycraft/aether/` and `../src/com/skycraft/aether/AetherNative.java` into
   `mod/src/main/java/com/skycraft/aether/` (drop `AetherProbe.java`).
2. Package the DLL: copy `native/build/Release/aether_core.dll` to
   `mod/src/main/resources/natives/windows-x86_64/aether_core.dll` (a release build step, like the shader pack zip).
3. Call `AetherClient.init(modBus)` from the client setup of `Skycraft.java`.
4. Wire the weather feed: `WeatherFieldTexture` implements `AetherClient.WeatherFeed` (it already builds the four
   RGBA8 fields). The column source needs no wiring: `AetherClient` picks `AetherExactSource` when an integrated
   server runs a Tamriel world, else the seed-free raster source (multiplayer clients).
5. Ship `shaderpack/build/SkycraftAether.zip` in the pack's `shaderpacks/` and select it in `iris.properties`.

## Exact columns (single player)

`AetherExactSource` evaluates the server's own `skycraft:tamriel_height` (the four seeded noises taken from the
server's `RandomState`, so they are the instances its chunk generator samples) at every far column on a small
worker pool (`skycraft.aether.columnThreads`, default a quarter of the cores), and uploads surface + water heights
with `aether_upload_column_heights`; aether dresses them (materials, forest canopy) from the bundled raster. Caves and
the 3D overhang term are not in the far field. Water: sea level below y 63 (sea and near-surface aquifers), the map's
river/lake level above it, filled to `ceil(level)` like `MapWaterFeature`.

It is exercised before the mod compiles on 26.3:

* `height-subset/build_subset.ps1` compiles the mod's height path (`files.txt`, snapshotted read-only from the main
  tree's `mod/`) against the real 26.3 jars, with `stubs/` for the few edges out of it (each stub says why it is
  safe: endless-lands tile planning is off, town street plots are below voxel size, the baked road plan is read
  without its staleness check).
* `compile_check.ps1` compiles all the glue against 26.3 + NeoForge + that subset.
* `harness/run_exact_harness.ps1 [-Seed n -X x -Z z -Yaw deg -Threads n]` runs vanilla's bootstrap, builds
  `tamriel_height` for a seed exactly as `RandomState`/`Noises.instantiate` does, streams the full clipmap window
  through `AetherExactSource` into aether_core.dll, and renders exact vs the raster stand-in
  (`native/out/exact_source.png`). Measured (seed 12345, origin): the 7-LOD window (175 tiles, 11.5 M columns)
  in 4.8 s on 8 threads, 3.3 us per column per thread; already-resident tiles cost nothing. The stand-in's
  surface is a median 28 blocks off the real one (its synthetic relief, not misregistration).

Verified against the jars:

| Need | 26.3 API |
| --- | --- |
| render hook | `RenderLevelStageEvent.AfterOpaqueBlocks` (NeoForge), `getLevelRenderState().cameraRenderState` |
| camera | `CameraRenderState.pos`, `.projectionMatrix`, `.viewRotationMatrix`, `.depthFar` |
| sun | `LevelRenderState.skyRenderState.sunAngle` |
| textures Iris samples | `RenderSystem.getDevice().createTexture(String, int usage, GpuFormat, w, h, 1, 1)`, `GlTexture.glId()`, `TextureManager.register(Identifier, AbstractTexture)` (Iris resolves `customTexture` through `TextureManager.getTexture` → `GpuTexture.iris$getGlId`) |
| exact columns | `ServerChunkCache.randomState().getOrCreateNoise(ResourceKey<NormalNoise>)`, `NoiseBasedChunkGenerator.stable(ResourceKey<NoiseGeneratorSettings>)`, `Minecraft.getSingleplayerServer()` |
| other dimensions | `CommandEncoder.clearColorTexture` on the two pack textures (coverage 0: near field only) |
| near-field depth | `AETHER_GL_BOUND_DEPTH`: aether reads the depth attachment of the framebuffer bound at that stage (Iris's gbuffer target) |
