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
4. Wire the two feeds: `WeatherFieldTexture` implements `AetherClient.WeatherFeed` (it already builds the four
   RGBA8 fields), and the far-field column source: `IntegratedColumnSource` in single player (the server's own
   `tamriel_height` density function, exact), or the seed-free raster preview on a multiplayer client.
5. Ship `shaderpack/build/SkycraftAether.zip` in the pack's `shaderpacks/` and select it in `iris.properties`.

Verified against the jars:

| Need | 26.3 API |
| --- | --- |
| render hook | `RenderLevelStageEvent.AfterOpaqueBlocks` (NeoForge), `getLevelRenderState().cameraRenderState` |
| camera | `CameraRenderState.pos`, `.projectionMatrix`, `.viewRotationMatrix`, `.depthFar` |
| sun | `LevelRenderState.skyRenderState.sunAngle` |
| textures Iris samples | `RenderSystem.getDevice().createTexture(String, int usage, GpuFormat, w, h, 1, 1)`, `GlTexture.glId()`, `TextureManager.register(Identifier, AbstractTexture)` (Iris resolves `customTexture` through `TextureManager.getTexture` → `GpuTexture.iris$getGlId`) |
| near-field depth | `AETHER_GL_BOUND_DEPTH`: aether reads the depth attachment of the framebuffer bound at that stage (Iris's gbuffer target) |
