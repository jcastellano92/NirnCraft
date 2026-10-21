# Infinite ATLAS: the 26.3 client frame, item by item (R1 checklist)

The definition of done for owning the renderer (docs/ATLAS-RENDERER-PLAN.md): every item below is ours, vanilla composited into our frame, or deferred with a reason. Derived from the 26.3.0.90 sources on 2026-10-20 (paths are inside the patched Minecraft and NeoForge sources jars named below). Dispositions are added to the plan as each phase lands; what is owned today is in [ATLAS-STATUS.md](ATLAS-STATUS.md) and section 5 below (updated 2026-10-20).


Read from source, not memory. Every path below is inside one of these two jars (unzipped copies under
a scratch unzip):

- **MC** = `E:\NirnCraft-scratch\migration-26.3\build\moddev\artifacts\minecraft-patched-26.3.0.90-sources.jar`
  (Mojang-mapped, NeoForge-patched; the `net.neoforged...` calls inlined below are NeoForge patches)
- **NF** = `E:\NirnCraft-scratch\migration-26.3\gradle-home\caches\modules-2\files-2.1\net.neoforged\neoforge\26.3.0.90\...\neoforge-26.3.0.90-sources.jar`

Abbreviations: `r/` = `net/minecraft/client/renderer/`, `nf/` = `net/neoforged/neoforge/client/`.
"LRS" = `LevelRenderState` (r/state/level/LevelRenderState.java), "GRS" = `GameRenderState`.
"Ex-event" = `ExtractLevelRenderStateEvent` (fires at the very end of `LevelExtractor.extract`).
"RF.Pre" = `RenderFrameEvent.Pre` (fires after `GameRenderer.extract`, before `GameRenderer.render`).

---

## 0. Facts that frame everything else

| Fact | Where | Consequence for us |
|---|---|---|
| **Reverse-Z.** `Projection.getMatrix` swaps near/far (`near = zFar; far = zNear`); every clear is depth `0.0`; always-on-top passes clear to 0. | r/Projection.java:69-75, r/GameRenderer.java:484, r/LevelRenderer.java:260 | Far plane = depth 0. Our depth must be written reverse-Z with `isZZeroToOne()` (glClipControl) or it will not interleave. |
| **Far plane is not the chunk edge.** `Camera.update`: `depthFar = max(RD*16*4, cloudRange*16)`; cloudRange default 128 chunks, so 2048 blocks. | net/minecraft/client/Camera.java:94-110 | Vanilla projection reaches 2048+ blocks; our far field can share the projection. |
| **Extract / render split.** `Minecraft.runTick`: `gameRenderer.update` -> `gameRenderer.extract` (lightmap state, camera, fog, `LevelExtractor.extract`, GUI extract, post-effect list) -> **RF.Pre** -> `gameRenderer.render` -> RF.Post -> blit/present -> `levelRenderer.endFrame` -> `FlipFrameEvent`. | net/minecraft/client/Minecraft.java:1318-1371 | **RF.Pre sees the whole frame's render state, fully extracted, before any GPU work.** Almost every vanilla item can be suppressed there by mutating public fields. |
| **All render state is public and mutable.** LRS fields (`entityRenderStates`, `blockEntityRenderStates`, `blockBreakingRenderStates`, `blockOutlineRenderState`, `particlesRenderState`, `weatherRenderState`, `worldBorderRenderState`, `skyRenderState.skybox`, `cloudColor`, `shouldShowEntityOutlines`, `sectionUpdateRenderStates`, `playerRenderState.*`, `cameraRenderState.fogData`), `GRS.lightmapRenderState`, `GRS.requestedPostEffects`. | r/state/level/LevelRenderState.java, r/state/GameRenderState.java | The main no-mixin suppression tool. |
| **Feature (submit) system.** Everything that is not terrain/sky/clouds/weather/border is a `SubmitNode` collected into `SubmitNodeStorage` (per-order `SubmitNodeCollection`, 16 phases) and drawn by 12 `FeatureRenderer`s. | r/SubmitNodeStorage.java, r/SubmitNodeCollection.java:55-70, r/feature/FeatureRenderDispatcher.java:47-59 | Mods add submits (`SubmitCustomGeometryEvent`, `submitSpecial(RenderPhaseKey)`), add feature renderers (`RegisterFeatureRenderersEvent`) but **cannot replace a vanilla feature renderer** (duplicate registration throws). |
| **Frame graph.** `LevelRenderer.render` builds a `FrameGraphBuilder`: passes `clear` -> `sky` -> `main` -> entity-outline post chain. Passes execute in insertion/dependency order. NeoForge's `FrameGraphSetupEvent` fires before the vanilla passes are added. | r/LevelRenderer.java:209-296, com/mojang/blaze3d/framegraph/FrameGraphBuilder.java:53-100 | A pass added from `FrameGraphSetupEvent` that writes `targets.main` runs **before** `clear` (so it can only prepare off-screen work, not draw into main). |
| **One "Main" render pass.** Solid+cutout terrain, AfterOpaqueBlocks, solid features, AfterOpaqueFeatures, and (classic mode) all translucents, clouds, weather, border run inside one `RenderPass` on main color+depth. | r/LevelRenderer.java:434-484 | Stage events hand us that live pass (`event.getRenderPass()`); never open/close one inside it; buffer uploads must happen in `PrepareRenderBuffersEvent`. |
| **No mixins, no access transformers in NirnCraft today.** | mod/src/main/resources (no *mixin*.json; neoforge.mods.toml has no [[mixins]]) | Everything below marked "no mixin" fits the current mod. |

---

## 1. Frame timeline (exact order, one frame, level loaded)

| # | Step | Method (file) | Mod hook at this point |
|---|---|---|---|
| T1 | Camera update, post-effect request list rebuilt (`end_of_frame` + player's server-set effects + spectator effect) | `GameRenderer.update` (r/GameRenderer.java:430) | `gameRenderer.getRequestedPostEffects()` is a public mutable list until T2 copies it |
| T2 | Window/options extract, lightmap state extract, camera + **fog** setup | `GameRenderer.extract` -> `LightmapRenderStateExtractor.extract`, `extractCamera` -> `FogRenderer.setupFog` (r/GameRenderer.java:446-471, r/fog/FogRenderer.java:162) | `ViewportEvent.RenderFog`, `ViewportEvent.ComputeFogColor` (fired inside setupFog / computeFogColor) |
| T3 | Level extract (see section 2) | `LevelExtractor.extract` (r/extract/LevelExtractor.java:109-248) | `ExtractBlockOutlineRenderStateEvent`, `ExtractBlockScreenEffectEvent`, `AddSectionGeometryEvent` (per dirty section), `RenderNameTagEvent.CanRender`, **Ex-event** (last) |
| T4 | GUI extract | `Gui.extractRenderState` | `RenderGuiEvent`, `RenderGuiLayerEvent` |
| T5 | Copy post effects into GRS | r/GameRenderer.java:461-468 | -- |
| T6 | **RF.Pre** | `ClientHooks.fireRenderFramePre` (Minecraft.java:1330) | `RenderFrameEvent.Pre` |
| T7 | Clear main color (GUI clear override) + depth 0; global settings UBO | `GameRenderer.render` (r/GameRenderer.java:481-496) | -- |
| T8 | Prepare post chains; **lightmap draw** (16x16 GPU pass) | `preparePostEffects`, `Lightmap.render` (r/Lightmap.java:63) | Pipeline modifier on `RenderPipelines.LIGHTMAP`; overwrite texture afterwards (our `RealmLightmap`) |
| T9 | Projection: hurt-tilt, view bob, nausea/portal warp | `GameRenderer.renderLevel` (r/GameRenderer.java:639-669) | `ViewportEvent.ComputeCameraAngles/ComputeFov` (camera), none for the warp |
| T10 | Fog UBO upload | `FogRenderer.updateBuffer` | -- |
| T11 | Submit all features (entities, BEs, breaking, transient blocks, particles, block outline, **SubmitCustomGeometryEvent**, gizmos) | `LevelRenderer.submitFeatures` (r/LevelRenderer.java:331-357) | `SubmitCustomGeometryEvent`, `RenderLivingEvent`, `RenderPlayerEvent`, `RenderNameTagEvent.DoRender`, `RenderItemInFrameEvent` |
| T12 | Prepare feature frame (sort, upload staged vertex buffer) | `FeatureRenderDispatcher.prepareFrame` | -- |
| T13 | Frame graph targets (OIT targets if improved transparency) + **FrameGraphSetupEvent** | r/LevelRenderer.java:209-249 | `FrameGraphSetupEvent` |
| T14 | Pass `clear`: main color = fog color (alpha 0), depth 0 | r/LevelRenderer.java:252-263 | -- |
| T15 | Pass `sky` (only if fog type not powder snow/lava, no blindness/darkness, `skybox != NONE`) | `addSkyPass` -> `SkyRenderer.render` (r/LevelRenderer.java:369-394) | `CustomSkyboxRenderer.renderSky` (return true = skip vanilla), then **`RenderLevelStageEvent.AfterSky`** (no render pass given) |
| T16 | Build terrain draw lists from `visibleSections` | `prepareChunkRenders` / `prepareChunkRendersIndirect` (r/LevelRenderer.java:849, 937) | -- (runs at graph setup, i.e. before passes execute) |
| T17 | Pass `main`: `prepareTranslucents` (cloud/border/weather prepare) + **PrepareRenderBuffersEvent** | r/LevelRenderer.java:451, 544-581 | `PrepareRenderBuffersEvent`, `CustomCloudsRenderer.prepare`, `CustomWeatherEffectRenderer.prepare` |
| T18 | Solid + cutout terrain | `executeSolid` -> `renderGroup(OPAQUE)` | **`AfterOpaqueBlocks`** (render pass given) |
| T19 | Solid features | `featureFrame.executeSolid` | **`AfterOpaqueFeatures`** |
| T20a | *Classic transparency*: translucent features (shadows, translucent models, name tags, texts, custom geometry, shape outlines, translucent gizmos, translucent blocks/items, breaking overlay, water mask) | `executeClassicTransparency` (r/LevelRenderer.java:713) | `AfterTranslucentFeatures` |
| T20b | translucent terrain | `renderGroup(TRANSLUCENT)` | `AfterTranslucentBlocks` |
| T20c | after-terrain features (translucent particles, outlines on translucent blocks) | `executeTranslucentAfterTerrain` | `AfterTranslucentParticles` |
| T20d | clouds -> weather -> world border | `CloudRenderer.render`, `WeatherEffectRenderer.render`, `WorldBorderRenderer.render` | `CustomCloudsRenderer.renderClouds`, `CustomWeatherEffectRenderer.renderSnowAndRain`, `AfterWeather` (before border) |
| T20' | *Improved transparency (OIT)* instead of T20a-d: water mask, then for each `OitStage` (DEPTH_BOUNDS, TRANSMITTANCE, ACCUMULATE): OIT terrain, OIT features + border + weather, depth-bounds cull, OIT clouds; then OIT composite | `executeOit` (r/LevelRenderer.java:583-673) | `RenderLevelStageEvent.OitTranslucent` x3, `renderCloudsOit`, `renderSnowAndRainOit` |
| T21 | Entity outline target pass (glowing) | `executeOutline` | `LRS.shouldShowEntityOutlines` |
| T22 | See-through features (non-discrete name tags) on color only | `executeSeeThrough` | -- |
| T23 | Always-on-top gizmos (+ integrate depth if post effects) | `executeAlwaysOnTop` | -- |
| T24 | `entity_outline` post chain (frame graph) | r/LevelRenderer.java:278-283 | -- |
| T25 | Compile/schedule dirty sections; upload terrain buffers; occlusion graph update; player-section callback (loading screen) | `compileSections`, `uploadTerrainBuffersToGpu`, `SectionOcclusionGraph.update` (r/LevelRenderer.java:301-326) | (`AddSectionGeometryEvent` renderers run in the compile task) |
| T26 | **`RenderLevelStageEvent.AfterLevel`** (no pass) | r/GameRenderer.java:681 | `AfterLevel` |
| T27 | 3D HUD: hud projection (near 0.05), clear depth (main or `hud_3d_depth`), **hand + held items** | `render3dHud` -> `renderItemInHand` (r/GameRenderer.java:685-696, 379-423) | `RenderHandEvent`, `RenderArmEvent` (cancellable), `IClientItemExtensions.applyForgeHandTransform` |
| T28 | Screen effects: block-in-wall, water, fire, item activation (totem) | `ScreenEffectRenderer.submit` (r/ScreenEffectRenderer.java:33) | `ExtractBlockScreenEffectEvent`, custom fluid/fire overlay renderers on `PlayerRenderState` |
| T29 | Debug 3D crosshair; integrate hud depth | r/GameRenderer.java:722-731 | -- |
| T30 | Auto world screenshot (singleplayer icon) | `tryTakeScreenshotIfNeeded` | -- |
| T31 | Blit entity outline over main | `LevelRenderer.blitEntityOutline` | -- |
| T32 | **Post effects** (spectator creeper/spider/invert, server-set, `end_of_frame`) | `applyPostEffects` | Add/remove ids in `GRS.requestedPostEffects` at RF.Pre; `RegisterEntitySpectatorShadersEvent` |
| T33 | Fog end-frame; clear depth; **GUI** (HUD layers, screens, PiP) | `guiRenderer.render` | `RegisterGuiLayersEvent`, `RenderGuiLayerEvent`, `CustomizeGuiOverlayEvent`, `RegisterPictureInPictureRenderersEvent` |
| T34 | RF.Post; swapchain blit; present; `levelRenderer.endFrame` (clouds) ; `FlipFrameEvent` | Minecraft.java:1332-1370 | `RenderFrameEvent.Post`, `FlipFrameEvent` |

Stage order per NeoForge javadoc (nf/event/RenderLevelStageEvent.java): AfterSky, PrepareRenderBuffersEvent, AfterOpaqueBlocks,
AfterOpaqueFeatures, then **either** OitTranslucent (x3) **or** AfterTranslucentFeatures, AfterTranslucentBlocks,
AfterTranslucentParticles, AfterWeather; then AfterLevel. None are cancellable. AfterSky does not fire when the sky pass is skipped.

---

## 2. Master checklist (everything the frame draws or computes)

Columns: **Reads** = depth / lightmap / fog / section visibility. **No-mixin control** = public NeoForge or vanilla API to
suppress (S), replace (R) or composite (C). **Mixin** = only if no public route exists.

### 2a. Frame-wide state

| ID | Item | Class / method (path) | Draws / produces | Reads | No-mixin control | Mixin needed? |
|---|---|---|---|---|---|---|
| F1 | Main target + clears | `GameRenderer.render` clear (r/GameRenderer.java:481); `ClientHooks.instantiateMainTarget` | Main color RGBA8 + D32F (stencil optional) | -- | `ConfigureMainRenderTargetEvent.enableStencil` | Changing format/clears: `GameRenderer#render`, `LevelRenderer#render` (clear pass lambda) |
| F2 | Global settings UBO | `GlobalSettingsUniform.update` | screen size, glint, game time, camera pos, RGSS flag | -- | read-only | -- |
| F3 | Lightmap | `Lightmap.render(LightmapRenderState)` (r/Lightmap.java:63), state from `LightmapRenderStateExtractor.extract` (r/LightmapRenderStateExtractor.java:46) via `EnvironmentAttributes` BLOCK_LIGHT_TINT, SKY_LIGHT_FACTOR/COLOR, AMBIENT_LIGHT_COLOR, NIGHT_VISION_COLOR | 16x16 light texture (`levelLightmap()`), UI lightmap separate | -- | S/R: mutate `GRS.lightmapRenderState` at RF.Pre; data-driven env attributes; pipeline modifier on `RenderPipelines.LIGHTMAP`; overwrite texture after (as `RealmLightmap` does) | -- |
| F4 | Fog | `FogRenderer.setupFog` (r/fog/FogRenderer.java:162) + 7 environments (Atmospheric, Water, Lava, PowderedSnow, Blindness, Darkness, MobEffect) in r/fog/environment; `updateBuffer`, modes WORLD/NONE | Fog UBO (color, env start/end, render-distance start/end = RD*16 minus clamp(RD*16/10,4,64)), sky/cloud end | camera fluid, effects, env attributes | `ViewportEvent.RenderFog` (distances), `ViewportEvent.ComputeFogColor`, `IClientFluidTypeExtensions.modifyFogRender/modifyFogColor`, `IClientBlockExtensions.getFogColor`; or edit `cameraRenderState.fogData` at RF.Pre | -- |
| F5 | Camera, projection, frustum | `Camera.update` (Camera.java:94), `GameRenderer.renderLevel` bob/hurt/warp (r/GameRenderer.java:639-669) | projection, cull frustum, depthFar | -- | `ViewportEvent.ComputeCameraAngles`, `ComputeFov`, `ComputeFovModifierEvent`, `CalculateDetachedCameraDistanceEvent` | nausea/portal projection warp: `GameRenderer#renderLevel` |
| F6 | Frame graph + targets | `LevelRenderer.render` (r/LevelRenderer.java:186-329), `LevelTargetBundle` (main, entity_outline, OIT targets) | -- | -- | `FrameGraphSetupEvent` (add passes/targets, they precede `clear`) | Removing/reordering vanilla passes: `LevelRenderer#render`, `#addSkyPass`, `#addMainPass` |
| F7 | Clear pass | r/LevelRenderer.java:252-263 | main color = fog color, depth 0 | fog color | -- (draw over it) | `LevelRenderer#render` lambda |
| F8 | Render pipelines (all shaders) | `RenderPipelines`, `RenderSystem.getCompiledPipeline` (com/mojang/blaze3d/systems/RenderSystem.java:106) | -- | -- | `RegisterRenderPipelinesEvent` (add), **`RegisterPipelineModifiersEvent` + `RenderSystem.push/popPipelineModifier`** (replace any pipeline for a span of rendering) | -- |

### 2b. Level extraction (main thread, `LevelExtractor.extract`, r/extract/LevelExtractor.java)

| ID | Item | Method | Produces | Depends on sections? | No-mixin control | Mixin |
|---|---|---|---|---|---|---|
| X1 | Dispatchers prepare | `blockEntityRenderDispatcher().prepare`, `entityRenderDispatcher().prepare` | camera pos, crosshair entity | -- | -- | -- |
| X2 | Frustum -> visible sections | `applyFrustum` -> `SectionOcclusionGraph.addSectionsInFrustum` (r/SectionOcclusionGraph.java:115) when camera rotates 2 deg or graph changed | `LevelRenderer.visibleSections()`, `nearbyVisibleSections()` (octree in frustum, offset 8) | **is** the section visibility | read via `RenderLevelStageEvent.getRenderableSections()` / `SubmitCustomGeometryEvent`; the list itself is public mutable (`LevelRenderer.visibleSections()`) | -- |
| X3 | Dirty-section scan | loop over `visibleSections` with `SectionUpdateTracker` (lines 152-187) | `LRS.sectionUpdateRenderStates` (+ `RenderRegionCache` copy, + `AddSectionGeometryEvent` renderers) | only **visible** dirty sections rebuild | `AddSectionGeometryEvent` (add), filter `sectionUpdateRenderStates` at Ex-event/RF.Pre (stops compiles; see 3) | -- |
| X4 | Entities | `extractVisibleEntities` (line 268) -> `isEntityVisible` (298) | `LRS.entityRenderStates` | **yes: compiled + faded >= 0.3** (`isSectionCompiledAndVisible`), not occlusion | filter/append `entityRenderStates` (append via public `entityRenderDispatcher().extractEntity`); `RenderLivingEvent.Pre` cancel | -- |
| X5 | Block entities | `extractVisibleBlockEntities` (314): BEs listed in each **visible** section's compiled mesh + `level.getGloballyRenderedBlockEntities()` | `LRS.blockEntityRenderStates` | **yes: occlusion-visible and compiled** (global ones: beacon, structure block, test instance) | filter/append (`tryExtractRenderState` is public); renderer `getRenderBoundingBox` (`IBlockEntityRendererExtension`) | -- |
| X6 | Block outline | `extractBlockOutline` (385) | `LRS.blockOutlineRenderState` | -- | `ExtractBlockOutlineRenderStateEvent` (cancel or `addCustomRenderer`; `CustomBlockOutlineRenderer` returning true cancels vanilla) | -- |
| X7 | Block breaking | `extractBlockDestroyAnimation` (366), <= 32 blocks | `LRS.blockBreakingRenderStates` | -- | clear list at Ex-event | -- |
| X8 | Transient blocks (server `ClientboundAddTransientBlockPacket`) | `drainTransientBlockQueue` (250) -> `LevelRenderer.addTransientBlock` | moving-block submits until the section recompiles or TTL | removed when the section compile lands | -- | `LevelRenderer#submitTransientBlocks` |
| X9 | Weather | `WeatherEffectRenderer.extractRenderState` (r/WeatherEffectRenderer.java:71) | rain/snow columns, intensity, radius | -- | `weatherRenderState.reset()`; `CustomWeatherEffectRenderer` | -- |
| X10 | Sky | `SkyRenderer.extractRenderState` (r/SkyRenderer.java:107): SUN/MOON/STAR_ANGLE, STAR_BRIGHTNESS, SUNRISE_SUNSET_COLOR, MOON_PHASE, SKY_COLOR | `LRS.skyRenderState` | -- | `skyRenderState.skybox = NONE` (skips pass) or `reset()`; `CustomSkyboxRenderer` | -- |
| X11 | World border | `WorldBorderRenderer.extract` | `LRS.worldBorderRenderState` | -- | `worldBorderRenderState.reset()` (alpha 0 = not drawn) | -- |
| X12 | Particles | `ParticleEngine.extract(..., frustum offset -3)` (net/minecraft/client/particle/ParticleEngine.java:131) | `LRS.particlesRenderState` | -- (frustum only) | `particlesRenderState.reset()`; `RegisterParticleGroupsEvent`; spawn side `IClientBlockExtensions.addHitEffects/addDestroyEffects` | -- |
| X13 | Clouds | env `CLOUD_COLOR`, `CLOUD_HEIGHT` (LevelExtractor.java:219-224) | `LRS.cloudColor/cloudHeight` | -- | `cloudColor = 0` (alpha 0 skips clouds); `CustomCloudsRenderer` | -- |
| X14 | Debug + gametest gizmos | `DebugRenderer.emitGizmos`, `GameTestBlockHighlightRenderer.emitGizmos`, per-tick gizmos | gizmo primitives (standard / always-on-top) | -- | `RegisterDebugRenderersEvent` (add) | removing vanilla gizmos: `LevelRenderer#finalizeGizmoCollection` |
| X15 | Player state (overlays, hands) | `extractPlayerState` (426) | `PlayerRenderState` (avatar, hands/items, portal/nausea, block overlay, water overlay, fire, item activation) | -- | `ExtractBlockScreenEffectEvent`, `IClientFluidTypeExtensions.extractOverlay`; fields mutable | -- |
| X16 | Entity outline flag | `shouldShowEntityOutlines` | `LRS.shouldShowEntityOutlines` | -- | set false at Ex-event | -- |
| X17 | NeoForge custom renderers lookup | `CustomEnvironmentEffectsRendererManager.get*` (from env attributes `neoforge:custom_skybox/clouds/weather_effects`) | `LRS.customSkyboxRenderer/customCloudsRenderer/customWeatherEffectRenderer` | -- | data-driven per dimension/biome; or set the fields at Ex-event | -- |

### 2c. Terrain (sections)

| ID | Item | Class / method | What | Reads | No-mixin control | Mixin |
|---|---|---|---|---|---|---|
| C1 | Section storage | `ViewArea` (r/ViewArea.java), `SectionRenderDispatcher.RenderSection` (r/chunk/SectionRenderDispatcher.java:206) | ring of render sections sized by effective RD | -- | -- | -- |
| C2 | Occlusion graph (cave culling) | `SectionOcclusionGraph.runUpdates` (r/SectionOcclusionGraph.java:256) BFS through `facesCanSeeEachother` (VisGraph) + ray test beyond 60 blocks when `smartCull`; async full update, partial updates on compile | visible-section octree | per-section VisibilitySet (from `blockState.isSolidRender`, not from models) | -- | `SectionOcclusionGraph#runUpdates` |
| C3 | Compile scheduling | `LevelRenderer.compileSections` (1004): sync if nearby/player-changed per `prioritizeChunkUpdates`, else async | compile tasks | -- | filter `LRS.sectionUpdateRenderStates`; `LevelExtractor.setSectionDirty*` (public) to force rebuilds | -- |
| C4 | Mesher | `SectionCompiler.compile` (r/chunk/SectionCompiler.java): per block: VisGraph opaque, BE list, fluid (`FluidRenderer.tesselate` unless `CustomFluidRenderer`), block model (`ModelBlockRenderer.tesselateBlock`), then `ClientHooks.addAdditionalGeometry` (AddSectionGeometryEvent), translucent quad sort | `MeshData` per `ChunkSectionLayer` | region copy (`RenderSectionRegion`) | add: `AddSectionGeometryEvent`; replace block models: `ModelEvent.ModifyBakingResult` (map is the live `blockStateModels`), `RegisterBlockStateModels`, model loaders; modded fluids: `RegisterFluidModelsEvent` + `CustomFluidRenderer` | **vanilla water/lava cannot be replaced** (`RegisterFluidModelsEvent.register` uses `putIfAbsent` and throws; `FluidStateModelSet` map is `Map.copyOf`): `FluidRenderer#tesselate(BlockAndTintGetter, BlockPos, FluidRenderer.Output, BlockState, FluidState)` |
| C5 | Layers | `ChunkSectionLayer` {SOLID, CUTOUT, TRANSLUCENT} (r/chunk/ChunkSectionLayer.java); groups OPAQUE={SOLID,CUTOUT}, TRANSLUCENT | pipelines SOLID/CUTOUT/TRANSLUCENT_TERRAIN (+ _MULTIDRAW, + OIT set) | -- | `quad.materialInfo().layer()` per quad (models); `ModelBlockRenderer.forceOpaque` for leaves (cutoutLeaves option) | -- |
| C6 | Block model lighting (AO / flat, shade) | `ModelBlockRenderer.tesselateBlock` -> `tesselateAmbientOcclusion`/`tesselateFlat` (r/block/ModelBlockRenderer.java:52-77), `BlockModelLighter` | per-vertex light+AO baked into mesh | light engine, neighbours | NeoForge config `handleAmbientOcclusionPerPart`; Neo AO helpers (nf/model/ao); per-part `ambientOcclusion()` | -- |
| C7 | Tint / biome colors | `BlockColors.getTintSources`, `BiomeColors`, `ColorResolverManager` (nf) | baked vertex colors | biome blend | `RegisterColorHandlersEvent.BlockTintSources/ColorResolvers/ItemTintSources`, `IClientBlockExtensions.collectDynamicTintValues` | -- |
| C8 | Emissive | `BakedQuad.MaterialInfo.lightEmission` per quad (net/minecraft/client/resources/model/geometry/BakedQuad.java:79); block `getLightEmission` | full-bright quads | -- | model data (`neoforge` emissivity in model JSON / ExtraFaceData) | -- |
| C9 | Animated textures | `TextureManager.tick` (each client tick, Minecraft.java:1207) -> `TextureAtlas.tick` -> `cycleAnimationFrames/uploadAnimationFrames` (r/texture/TextureAtlas.java:251-271); quads flagged `FLAG_ANIMATED` | block atlas texels change in place | -- | sprite metadata; `RegisterSpriteSourcesEvent`, `TextureAtlasStitchedEvent` | -- |
| C10 | Upload | `SectionRenderDispatcher.uploadTerrainBuffersToGpu` (145) | GPU vertex/index slices | -- | -- (empty meshes upload nothing) | -- |
| C11 | Draw lists | `prepareChunkRenders`/`Indirect` (r/LevelRenderer.java:763-1002): iterates **`visibleSections`**, per-section `ChunkSectionInfo` (offset + fade visibility), translucent order reversed | draws per layer | section fade (`chunkSectionFadeInTime`) | stash-and-clear `visibleSections()` (see 3), empty meshes, pipeline modifier | -- |
| C12 | Solid/cutout draw | `ChunkSectionsToRender.renderGroup(OPAQUE)` (r/chunk/ChunkSectionsToRender.java:120,176) | terrain into main | lightmap, fog, block atlas, chunk sampler | as C11 | -- |
| C13 | Translucent draw (classic) | `renderGroup(TRANSLUCENT)` | water, glass, ice | depth | as C11 | -- |
| C14 | Translucent draw (OIT) | `ChunkSectionsToRender.renderOit` per `OitStage` | into OIT targets | main depth / water-patched depth | as C11 | -- |
| C15 | Translucency sorting | per-quad sort at compile (`mesh.sortQuads`), resort tasks `scheduleTranslucentSectionResort` (1262) only when **not** improved transparency; section order reversed in draw list | -- | camera pos | -- | `LevelRenderer#scheduleTranslucentSectionResort` |
| C16 | Wireframe / MDI toggles | `renderWireframeTerrain`, `shouldUseMultiDrawIndirectForTerrain` | -- | -- | LRS fields | -- |

### 2d. Sky, clouds, weather, border

| ID | Item | Class / method | Draws | Reads | No-mixin control | Mixin |
|---|---|---|---|---|---|---|
| S1 | Sky disc (SKY pipeline) | `SkyRenderer.renderSkyDisc` (r/SkyRenderer.java:163) | upper sky color dome | sky fog | `CustomSkyboxRenderer.renderSky` -> true; `skybox = NONE`; data `DimensionType.Skybox` | -- |
| S2 | Sunrise/sunset fan | `renderSunriseAndSunset` (284) | SUNRISE_SUNSET pipeline | -- | same | -- |
| S3 | Sun, moon (phases) | `renderSun`, `renderMoon`, `drawCelestialBody` (219-256), CELESTIAL pipeline, atlas sprites | -- | rain brightness | same; resource pack sprites | -- |
| S4 | Stars | `renderStars` (266) STARS pipeline | -- | star brightness | same | -- |
| S5 | Dark disc (below horizon) | `renderDarkDisc` (174) | -- | camera height | same | -- |
| S6 | End sky / end flash | `renderEndSky` (309), `renderEndFlash` (232) | END_SKY pipeline | -- | same | -- |
| S7 | Clouds | `CloudRenderer.prepare/render/renderOit` (r/CloudRenderer.java:129,197,207), CLOUDS / FLAT_CLOUDS / OIT_* | cloud mesh, OIT cloud depth | depth, fog | `CustomCloudsRenderer` (prepare + renderClouds/renderCloudsOit return true), `cloudColor = 0`, option OFF | -- |
| S8 | Rain/snow | `WeatherEffectRenderer.prepare/render/renderOit` (125,147,152), WEATHER / OIT_WEATHER | instanced columns | lightmap per column, depth | `CustomWeatherEffectRenderer` (also `tickRain` -> true stops splash particles/sounds), `weatherRenderState.reset()` | -- |
| S9 | World border | `WorldBorderRenderer.prepare/render/renderOit` (136,152,164) | forcefield | depth | `worldBorderRenderState.reset()` | -- |

### 2e. Features (submits) and their renderers

12 vanilla `FeatureRenderer`s (r/feature/FeatureRenderDispatcher.java:47-58): Shadow, Flame, Model, Text, Leash, Item, Custom,
BlockModel, MovingBlock, QuadParticle, ShapeOutline, Gizmo. 16 phases per order (r/SubmitNodeCollection.java:55-70): solid,
waterMask, outline, alwaysOnTopGizmos, seeThrough, oitTranslucent, shadows, nameTags, texts, shapeOutlines,
translucentBlocksAndItems, translucentModels, translucentCustomGeometry, breakingOverlay, afterTerrain, translucentGizmos
(NeoForge exposes them as `RenderPhaseKeys` for `submitSpecial`).

| ID | Item | Producer -> submit -> feature renderer | Phase | Reads | No-mixin control | Mixin |
|---|---|---|---|---|---|---|
| E1 | Entity models (150 registered renderers, 136 renderer classes in r/entity) | `EntityRenderDispatcher.submit` (r/entity/EntityRenderDispatcher.java:143) -> `EntityRenderer.submit` -> `submitModel` -> `ModelFeatureRenderer` | solid / translucentModels / outline | lightmap, overlay tex, fog, depth | R: `EntityRenderersEvent.RegisterRenderers.registerEntityRenderer` (overwrites vanilla provider map); S: `RenderLivingEvent.Pre`, `RenderPlayerEvent.Pre`, remove state from `entityRenderStates` | -- |
| E2 | Render layers (51 layer classes: armor, held items, eyes/emissive, cape, elytra, saddle/equipment, wool, collars, stuck arrows, ...) | `LivingEntityRenderer` layers (r/entity/layers) | as E1 | as E1 | `EntityRenderersEvent.AddLayers`; `IClientItemExtensions` armor models; `ModelPart` visibility (`ClientHooks.updateModelVisibility`) | removing a vanilla layer: no API (renderer's private layer list) -> replace the renderer (E1) or mixin `LivingEntityRenderer#submit` |
| E3 | Entity culling | `EntityRenderer.shouldRender` (r/entity/EntityRenderer.java:64): `entity.shouldRender` (distance x `Entity.viewScale` = clamp(RD/8,1,2.5) x entityDistanceScaling), `affectedByCulling`, AABB+0.5 vs frustum, leash holder box; then `isSectionCompiledAndVisible` | -- | section compiled state | override per renderer (`shouldRender`, `affectedByCulling`, `getBoundingBoxForCulling`) by replacing renderer; or append states (X4) | -- |
| E4 | Entity shadows | `EntityRenderer.extractShadow` (285) reads blocks under entity; `submitShadow` -> `ShadowFeatureRenderer` | shadows | depth, block data | clear `state.shadowPieces` (public list) at Ex-event; renderer `getShadowRadius` | replacing ShadowFeatureRenderer: duplicate throws -> `FeatureRenderDispatcher#<init>` |
| E5 | Name tags / score text | `submitNameDisplay` -> `submitNameTag` -> `TextFeatureRenderer` | nameTags + seeThrough | depth (see-through ignores) | `RenderNameTagEvent.CanRender` (TriState) / `DoRender` (cancel) | -- |
| E6 | Leashes | `EntityRenderer.submit` -> `submitLeash` -> `LeashFeatureRenderer` | solid | lightmap | clear `state.leashStates` / replace renderer | -- |
| E7 | Entity fire | `EntityRenderDispatcher.submit` -> `submitFlame` -> `FlameFeatureRenderer` | -- | -- | `state.displayFireAnimation = false` | -- |
| E8 | Fishing line, guardian beam, lightning, end-crystal beams, dragon rays | `submitCustomGeometry` in FishingHookRenderer:42-53, GuardianRenderer:113, LightningBoltRenderer:45, EndCrystalRenderer:43 -> `CustomFeatureRenderer` | per render type | -- | replace renderer (E1) / remove state | -- |
| E9 | Item entities, item frames, held items, thrown items | `ItemStackRenderState.submit` -> `ItemFeatureRenderer` (+ glint) ; `ItemFrameRenderer` (r/entity/ItemFrameRenderer.java:47) | solid / translucentBlocksAndItems | lightmap | `RenderItemInFrameEvent` (cancel), item models (`RegisterItemModelsEvent`, `RegisterSpecialModelRendererEvent`, 17 special renderers in r/special) | -- |
| E10 | Maps (frames, hand) | `MapRenderer.render` (r/MapRenderer.java:35), `MapTextureManager` | map texture quad + decorations | lightmap | `RenderItemInFrameEvent`, `RenderHandEvent`, `MapDecorationRenderStateModifier` (nf/renderstate) | -- |
| E11 | Falling blocks, pistons (moving), transient blocks | `submitMovingBlock` -> `MovingBlockFeatureRenderer` | -- | lightmap | replace renderer | -- |
| E12 | Hitboxes, debug shapes | `EntityHitboxDebugRenderer` -> gizmos -> `GizmoFeatureRenderer` | translucentGizmos / alwaysOnTop | -- | only when F3+B | -- |
| E13 | Entity outline (glowing, spectator highlight) | `outlineColor` on submits -> `outline` phase -> `entity_outline` target -> `entity_outline` post chain -> `blitEntityOutline` | outline | -- | `LRS.shouldShowEntityOutlines = false` | -- |
| B1 | Block entity renderers (26) | `BlockEntityRenderDispatcher.submit` / `tryExtractRenderState` (r/blockentity/BlockEntityRenderDispatcher.java:79): frustum on `getRenderBoundingBox`, `shouldRenderOffScreen`, `shouldRender` (view distance, default 64) | see list below | lightmap, crumbling overlay | R: `registerBlockEntityRenderer` (overwrites); S: filter `blockEntityRenderStates`; bounds: `IBlockEntityRendererExtension.getRenderBoundingBox` | -- |
| B2 | Block breaking crumble | `submitBreakingBlockModel` (r/LevelRenderer.java:1085); BE crumble via `CrumblingOverlay` | breakingOverlay / solid | depth | clear `blockBreakingRenderStates`; BE: null crumble | -- |
| B3 | Block outline (selection box) | `submitBlockOutline` (r/LevelRenderer.java:1117) -> `ShapeOutlineFeatureRenderer`; render types linesTranslucent / linesTranslucentNoDepthWrite / linesDepthBias / secondaryBlockOutline | shapeOutlines or afterTerrain | depth | `ExtractBlockOutlineRenderStateEvent` cancel / `CustomBlockOutlineRenderer`; `GameRenderer.setRenderBlockOutline(false)` | -- |
| P1 | Particles | `ParticlesRenderState.submit` -> `QuadParticleFeatureRenderer`; groups by `ParticleRenderType` {SINGLE_QUADS, ITEM_PICKUP, ELDER_GUARDIANS, NO_RENDER}; `SingleQuadParticle.Layer` {OPAQUE_TERRAIN, TRANSLUCENT_TERRAIN, OPAQUE_ITEMS, TRANSLUCENT_ITEMS, OPAQUE, TRANSLUCENT} | solid / afterTerrain / oit | lightmap, depth | `particlesRenderState.reset()`; `RegisterParticleProvidersEvent`, `RegisterParticleGroupsEvent` | -- |
| G1 | Gizmos (debug renderers: ChunkBorder, Octree, Pathfinding, Water, HeightMap, CollisionBox, SupportBlock, NeighborsUpdate, RedstoneWireOrientations, Structure, Light, SolidFace, VillageSections, Brain, Poi, Bee, Raid, GoalSelector, ChunkDebug, GameEventListener, LightSection, Breeze, EntityBlockIntersection, EntityHitbox, ChunkCulling) | r/debug/DebugRenderer.java:36-139 -> gizmo primitives -> `GizmoFeatureRenderer` | translucentGizmos / alwaysOnTop | depth | `RegisterDebugRenderersEvent` | -- |
| M1 | Custom geometry (mods) | `SubmitCustomGeometryEvent` (after particles + outline, before gizmos) | any phase via `submitSpecial(RenderPhaseKey)` | -- | (that is the hook) | -- |

Block entity renderers registered (r/blockentity/BlockEntityRenderers.java:38-63): SIGN, HANGING_SIGN, MOB_SPAWNER, PISTON,
CHEST, ENDER_CHEST, TRAPPED_CHEST, ENCHANTING_TABLE, LECTERN, END_PORTAL, END_GATEWAY (view 256), BEACON (beam, off-screen,
view = RD), SKULL, BANNER, STRUCTURE_BLOCK (off-screen), TEST_INSTANCE_BLOCK (off-screen), SHULKER_BOX, CONDUIT, BELL,
CAMPFIRE, BRUSHABLE_BLOCK, DECORATED_POT, TRIAL_SPAWNER, VAULT, COPPER_GOLEM_STATUE, SHELF. (26 types, 23 renderer classes.)

### 2f. 3D HUD, screen overlays, post, GUI

| ID | Item | Class / method | Draws | Reads | No-mixin control | Mixin |
|---|---|---|---|---|---|---|
| H1 | Hands + held items + map in hand | `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` (r/FirstPersonHandsAndItemsRenderer.java:326), `renderMapHand` (132); own render pass "Item in hand" | arms, items | hud projection (fov `hudFov`, near 0.05), cleared depth, lightmap | `RenderHandEvent` / `RenderArmEvent` (cancel + draw own), `IClientItemExtensions.applyForgeHandTransform` | changing hud projection/depth: `GameRenderer#render3dHud` |
| H2 | Block-in-wall overlay | `ScreenEffectRenderer.submitBlockSprite` (r/ScreenEffectRenderer.java:100) | dark block sprite | -- | `ExtractBlockScreenEffectEvent` (cancel / `setCustomRenderer`, `CustomBlockScreenEffectRenderer.NO_OP`) | -- |
| H3 | Underwater / fluid overlay | `submitWater` / `submitFluid` (110-114) | underwater texture | brightness | `ClientHooks.extractWaterScreenEffect` path, `IClientFluidTypeExtensions.extractOverlay` | -- |
| H4 | Fire overlay | `submitFire` (138) | fire sprite | -- | custom fire overlay renderer on `PlayerRenderState` (`extractFireScreenEffect`) | -- |
| H5 | Totem / item activation | `renderItemActivationAnimation` (72) | item | -- | `playerRenderState.itemActivation = null` | -- |
| H6 | 3D debug crosshair | `DebugCrosshairRenderer.render` | axes | -- | debug toggle | -- |
| H7 | Post effects | `GameRenderer.applyPostEffects` (555); chains: blur, creeper, entity_outline, invert, spider (assets/minecraft/post_effect); `end_of_frame` id requested every frame (resource-pack hook, absent in vanilla); server-set via `LocalPlayer.setActivePostEffects` | full-screen | main target only (`MAIN_TARGETS`) | add/remove ids in `GRS.requestedPostEffects` at RF.Pre; `RegisterEntitySpectatorShadersEvent`; `clearSpectatedEntityPostEffect()` | -- |
| U1 | HUD layers (VanillaGuiLayers: CAMERA_OVERLAYS, CROSSHAIR, AFTER_CAMERA_DECORATIONS, HOTBAR, PLAYER_HEALTH, ARMOR_LEVEL, FOOD_LEVEL, VEHICLE_HEALTH, AIR_LEVEL, CONTEXTUAL_INFO_BAR(_BACKGROUND), EXPERIENCE_LEVEL, SELECTED_ITEM_NAME, SPECTATOR_TOOLTIP, EFFECTS, BOSS_OVERLAY, SLEEP_OVERLAY, DEMO_OVERLAY, SCOREBOARD_SIDEBAR, OVERLAY_MESSAGE, TITLE, CHAT, TAB_LIST, SUBTITLE_OVERLAY) | net/minecraft/client/gui/Hud.java | 2D | UI lightmap for items | `RegisterGuiLayersEvent` (replaceLayer/wrapLayer/registerBelowAll...), `RenderGuiLayerEvent.Pre` cancel, `RenderGuiEvent.Pre` | -- |
| U2 | Camera overlays inside CAMERA_OVERLAYS: vignette, spyglass scope, equippable camera overlay (carved pumpkin), powder-snow outline (freezing), portal swirl, nausea | `Hud.extractCameraOverlays` (Hud.java:303-345), `extractVignette` (1190), `extractSpyglassOverlay` (1164), `extractPortalOverlay` (1230), `extractConfusionOverlay` (1246) | 2D textures | -- | cancel/replace CAMERA_OVERLAYS layer; `IClientItemExtensions.renderFirstPersonOverlay/getScopeOverlayTexture`; options (vignette) | splitting one overlay out of the layer: replace whole layer (no mixin) |
| U3 | Picture-in-picture (entity in inventory, skins, book, banner, profiler) | `GuiRenderer` PiP renderers (r/GameRenderer.java:165-175) | offscreen | -- | `RegisterPictureInPictureRenderersEvent` | -- |
| U4 | Menu blur | `GameRenderer.processBlurEffect` | blur chain | -- | options | -- |

---

## 3. Can a mod stop vanilla terrain without mixins?

**Drawing: yes. Building: almost (vanilla fluids are the exception). Not compiling at all: possible, but it breaks other things.**

Ranked options:

1. **Empty models for meshing only (best).** In `ModelEvent.ModifyBakingResult` wrap every entry of
   `getBakingResult().blockStateModels()` in a `DelegateBlockStateModel` (nf/model) whose
   `collectParts(BlockAndTintGetter level, ...)` adds nothing when `level instanceof RenderSectionRegion` and delegates
   otherwise (`ModelBlockRenderer.tesselateBlock` skips a block whose part list is empty, r/block/ModelBlockRenderer.java:56-57).
   Sections still compile: VisGraph (from `isSolidRender`, not models), block-entity lists and the compiled flag stay correct, so
   occlusion culling, block entities, entity visibility and the loading screen keep working, and empty layers upload and draw
   nothing. Breaking overlays (`BlockAndTintGetter.EMPTY`), falling/moving/transient blocks and items keep their real models.
   Leftovers: vanilla **water/lava** still meshes (no public override, see mixin list), other mods'
   `AddSectionGeometryEvent` geometry, and the main-thread region copy plus a 4096-block scan per dirty section.
2. **Drop the draws, keep the compile.** At RF.Pre, copy and clear `Minecraft.getInstance().levelRenderer.visibleSections()`
   (and `nearbyVisibleSections()`), then restore both in `RenderLevelStageEvent.AfterLevel`. `prepareChunkRenders` runs inside
   `render()` and finds no sections; `compileSections` uses `LRS.sectionUpdateRenderStates` (already extracted);
   `SectionOcclusionGraph.update` never touches the list (r/SectionOcclusionGraph.java:148-210). Stage events would then pass an
   empty `getRenderableSections()`, so keep our own copy. Use it together with option 1 to remove the fluid draws. The list is a
   vanilla public method, not a NeoForge contract.
3. **Pipeline modifier.** `RegisterPipelineModifiersEvent` + `RenderSystem.pushPipelineModifier` around the main pass (push in a
   `FrameGraphSetupEvent` pass or AfterSky, pop in AfterLevel; it must be empty by RF.Post, `ClientHooks.java:863`) can swap
   SOLID/CUTOUT/TRANSLUCENT_TERRAIN (+_MULTIDRAW, OIT) for a no-output pipeline. The draws still run; this only helps for shader-level replacement.
4. **Stopping compiles** (filter `LRS.sectionUpdateRenderStates` at RF.Pre). Public, but it breaks things:
   - **entities** never render: `isEntityVisible` requires `isSectionCompiledAndVisible` (mesh != UNCOMPILED and fade >= 0.3),
     r/extract/LevelExtractor.java:298-308 and r/LevelRenderer.java:1322-1331;
   - **block entities** vanish except beacon/structure/test-instance (they come from compiled meshes of visible sections);
   - **occlusion BFS** dead-ends: `UNCOMPILED.facesCanSeeEachother` returns false (r/chunk/CompiledSectionMesh.java:17-22);
   - **loading screen** waits for the player's section and only gives up after the 30 s timeout (`LevelLoadTracker.WaitingForPlayerChunk`, net/minecraft/client/multiplayer/LevelLoadTracker.java:118-136);
   - transient blocks linger until TTL; F3 "C:" reads 0; the world-icon auto screenshot never fires.

**Smallest render distance:** 2 chunks (`Options.renderDistance` `IntRange(2, 16|32)`, Options.java:1518-1522). The effective
value is `min(option, server ClientboundSetChunkCacheRadius)`. Lowering it is **not** a way to shrink vanilla terrain. It sets:
- what the server streams: `ChunkMap.getPlayerViewDistance = clamp(requested, 2, serverViewDistance)`;
- **entity tracking**: `visibleRange = min(trackingRange, viewDistance*16)` (ChunkMap.java:1414-1423), so at RD 2 the server stops sending entities beyond 32 blocks;
- the client chunk cache radius `max(2, RD) + 3` (ClientChunkCache.java:154);
- `Entity.viewScale`, the render-distance fog, the BE/beacon view and `depthFar`.

**What depends on sections being drawn:** nothing. Entity culling is frustum + distance + "section compiled" and does not use
the occlusion graph. Block entities and section rebuilds depend on the occlusion-visible set (`visibleSections`). Client chunk
loading does not depend on rendering. The things that depend on sections being compiled are listed in option 4.

---

## 4. Mixin-only list (no public route found), exact targets

| # | Need | Target |
|---|---|---|
| 1 | Skip **vanilla water/lava meshing** (RegisterFluidModelsEvent cannot replace them) | `net.minecraft.client.renderer.block.FluidRenderer#tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V` HEAD cancel when the getter is a `RenderSectionRegion` (or redirect the call in `SectionCompiler#compile`) |
| 2 | Skip section compile **work** entirely while keeping "compiled" semantics | `SectionRenderDispatcher$RenderSection$CompileTask#doTask` or `SectionCompiler#compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack, List)` (return Results with visibility + BE list only) |
| 3 | Replace a vanilla **FeatureRenderer** (shadow, leash, text, model, item, particles, outline, gizmo...) | `FeatureRenderDispatcher#<init>` (or `FeatureRendererMap#put`); `RegisterFeatureRenderersEvent.register` throws on duplicates |
| 4 | Remove, reorder or replace vanilla **frame-graph passes** (clear, sky, main, outline chain) | `LevelRenderer#render`, `#addSkyPass`, `#addMainPass` |
| 5 | Draw **after the hand pass, before the GUI**, in 3D with depth (no stage event there) | `GameRenderer#render3dHud` TAIL / `GameRenderer#renderLevel` TAIL. (Full-screen 2D work is possible without one through a post chain in `requestedPostEffects`, which runs after the hand, or a `registerBelowAll` GUI layer.) |
| 6 | Change the **hud projection** / hand depth target | `GameRenderer#render3dHud`, `Projection#setupPerspective` |
| 7 | Remove the **nausea/portal projection warp** without the accessibility option | `GameRenderer#renderLevel` |
| 8 | Replace the **occlusion graph** / smart culling policy | `SectionOcclusionGraph#runUpdates`, `#addSectionsInFrustum` |
| 9 | Change the **translucent resort** policy (classic mode) | `LevelRenderer#scheduleTranslucentSectionResort` |
| 10 | Remove a single vanilla **entity render layer** without replacing the renderer | `LivingEntityRenderer#submit` (layer loop); a replacement renderer through RegisterRenderers is the no-mixin route |
| 11 | Suppress vanilla **gizmos** (debug/gametest) | `LevelRenderer#finalizeGizmoCollection` |
| 12 | Change the **clear-pass colour** or the main target format | `LevelRenderer#render` (clear lambda), `ClientHooks#instantiateMainTarget` (only stencil is configurable) |
| 13 | Suppress **transient block** previews | `LevelRenderer#submitTransientBlocks` |

Everything else in section 2 can be suppressed, replaced or composited through public API.

---

## 5. What NirnCraft hooks today (mod/src/main/java/com/nirncraft)

| Area | File | Hooks | Notes |
|---|---|---|---|
| **Infinite ATLAS frame** | atlas/AtlasClient.java | `RenderLevelStageEvent.AfterOpaqueBlocks` | Runs `ctx.glRender` inside the live Main pass, right after solid+cutout terrain and before all features. `io.nearDepthTex = ATLAS_GL_BOUND_DEPTH`: reads the depth of the currently bound framebuffer (vanilla main, our G-buffer, or the Iris gbuffer). Uses inverse projection, `depthZeroToOne`, `reversedZ` (true without a pack; with a pack, Iris's `isReverseZ` via reflection). Writes nirncraft:atlas/color, /depth and the sun map (glCopyImageSubData). `nearFieldEnd`: RD*16 where the near pass owns the frame, else RD*16 - 16 - 48 (the pack's hand-off band); the unified field sets `FRAME_PRIMARY_NEAR_LIT` (atlas traces every pixel). FILL_NEAR when the eye sees sky. Overworld only. Off on non-GL backends. |
| **Near pass (owning terrain)** | atlas/AtlasNearPass.java | `RegisterRenderPipelinesEvent` (resolve, composite, TAA, lightmap, AtlasMeter), `RegisterPipelineModifiersEvent` (near_gbuffer, near_forward, near_entity, near_shadow), `RenderFrameEvent.Pre` (takes `visibleSections()`), `AfterSky` (G-buffer from vanilla's opaque meshes; not in the unified field; the sky copied for reflections at LOWEST), `AfterTranslucentFeatures` (translucents forward), `AfterLevel` (sections given back), `ExtractBlockScreenEffectEvent` (in-wall overlay off under our light) | On where Sodium is absent (`-Dnirncraft.atlas.near=false` or the action `atlas-near off` turns it off). The resolve writes the frame's colour and reverse-Z depth, so everything later depth-tests against our terrain (E3 answered). Not owned: a shader pack in use, OIT, frames without a sky pass. |
| Fallback compositor (no pack) | atlas/AtlasFallback.java | `RegisterRenderPipelinesEvent` (pipeline nirncraft:pipeline/atlas_fallback), draws in the borrowed AfterOpaqueBlocks pass | Full-screen triangle at clip z 0 (far plane), `GREATER_THAN_OR_EQUAL`, **no depth write**, premultiplied blend: lands only where depth is still 0 (sky). Everything later (entities, translucents, clouds, weather, particles) composites on top. Skipped in dense fog. It does **not** write depth, so later features do not depth-test against far terrain. |
| Shader-pack path | AtlasTargets, render/client/IrisPipelines, atmosphere/client/ShaderCompat | Iris API via reflection (`isShaderPackInUse`, `assignPipeline`, reverse-Z) | The pack composites the atlas custom textures. Iris replaces vanilla's level pipeline, so stage timing under Iris is Iris's. |
| Fog | atlas/AtlasTruthView.java:22, atmosphere/client/ClientWeather.java:468,491, realms/client/RealmsClient.java:108, world/client/WorldClientEvents.java:131 | `ViewportEvent.RenderFog`, `ComputeFogColor` | The far field removes render-distance fog on the vanilla path. |
| Sky | atmosphere/client/SkyRenderer.java:100,181 | Ex-event + `AfterSky` | Draws over the vanilla sky; does not suppress it. |
| Clouds | atmosphere/client/CloudRenderer.java, OverworldClouds.java | `CustomCloudsRenderer`, Ex-event, `PrepareRenderBuffersEvent`, `AfterSky`/`AfterOpaqueBlocks` probes, `AfterWeather`, `OitTranslucent` | Replaces vanilla clouds through the NeoForge API. |
| Realms | realms/client/RealmEffects.java, RealmLightmap.java | Custom skybox + clouds + weather; lightmap post-tint (GPU copy over `levelLightmap()`) | `GRS.lightmapRenderState.needsUpdate` read in Ex-event. |
| Pinned truth / snapshots | client/pinned/PinnedTruth.java:399-483 | Ex-event, `AfterOpaqueBlocks`, `AfterTranslucentBlocks`, `OitTranslucent`, `SubmitCustomGeometryEvent` | Already draws its own chunk-layer geometry in the vanilla pass: the model for owning terrain draws. |
| Custom geometry | fire/client/FarFireRenderer, seas/client/SkyFlocks, quest/client/IntroClient | Ex-event + `SubmitCustomGeometryEvent` | -- |
| Frame timing / quality | render/quality/QualityClient.java | `RenderFrameEvent.Pre/Post`, `AfterOpaqueBlocks` flag | -- |
| Harness readiness | client/autotest/WorldReadiness.java, TerrainMeshReadiness.java | `AfterLevel`, `hasRenderedAllSections`, parses the section statistics | **Will misread once vanilla terrain is suppressed** (rendered-section counts drop to 0): needs a harness fix and self-test in the same change. |
| Camera | client/DialogueCamera, arsenal/client/KillCamClient, fauna/client/FaunaClientEvents, world/client/RestClient, quest/client/IntroClient | `ViewportEvent.ComputeCameraAngles/ComputeFov` | -- |

Not used yet: `FrameGraphSetupEvent`, `AddSectionGeometryEvent`, `ModelEvent.ModifyBakingResult`, `RenderHandEvent`,
`ExtractBlockOutlineRenderStateEvent`, `RegisterFeatureRenderersEvent`. (Pipeline modifiers, `RenderFrameEvent.Pre`
state edits and `ExtractBlockScreenEffectEvent` are used by the near pass since 2026-10-20.)

---

## 6. Surprises worth acting on

1. RF.Pre fires **after** the whole extract and before any GPU work, and LRS/GRS are public and mutable. Most suppression needs neither a mixin nor an event of its own.
2. Entity visibility is "the section at the entity's feet is compiled and faded in", not occlusion. Keep compiles alive (empty models) and entities keep working with vanilla terrain gone.
3. Vanilla block entities come **from compiled section meshes of occlusion-visible sections**. Owning BEs means either keeping vanilla's section compile or walking chunks ourselves.
4. Vanilla water/lava are the one terrain piece with no public override (fluid model registration is put-if-absent and throws).
5. Vanilla feature renderers cannot be replaced (duplicate throws), but each feature's input state can be emptied.
6. NeoForge 26.3 has **render pipeline modifiers**: a pushed modifier swaps any `RenderPipeline` until it is popped.
7. `GameRenderer` requests `minecraft:end_of_frame` as a post effect every frame. Vanilla has no such chain, so it is a resource-pack slot for a full-screen pass after the hand.
8. The projection far plane is `max(RD*64, cloudRange*16)` = 2048 blocks by default, and everything is reverse-Z.
9. Render distance 2 is a trap: it cuts the server's entity tracking to 32 blocks and the chunks it streams.
10. `FrameGraphSetupEvent` passes run before `clear`. Main-pass compositing has to happen in the stage events.
11. The fallback compositor writes no depth, so entities and particles behind far terrain are not occluded. Owning near terrain means writing depth in AfterOpaqueBlocks, reverse-Z, with vanilla's projection (the near pass's resolve does, 2026-10-20).
