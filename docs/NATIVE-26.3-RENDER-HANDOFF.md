# Native rendering contracts (26.3)

The render-side contracts of NirnCraft Core on Minecraft 26.3 / NeoForge 26.3.0.90 / Java 25: who owns which frame
stage and GPU resource, and the small data textures the mod publishes for the InfiniteATLAS shader pack
(`shaderpack/InfiniteATLAS/shaders/lib/modtex.glsl` decodes them). The far field itself is in [ATLAS.md](ATLAS.md);
the gameplay-side data contracts are in [NATIVE-INTEGRATION-CONTRACTS-26.3.txt](NATIVE-INTEGRATION-CONTRACTS-26.3.txt).

## Platform

- Minecraft 26.3, NeoForge 26.3.0.90, Java 25; no older-core fallback.
- Official Sodium 0.9.3-beta.1 (`SS023RVV`) and Iris 1.11.7 (`Ag5S66CF`); the exact artifact/hash-bound dependency exception is in [platform-lock.json](../pack/migration/platform-lock.json) and `tools/validate_pack.py` rejects broader overrides. Iris runs NirnCraft's own pack, `InfiniteATLAS.zip`, enabled by default; it is the only shader pack the pack may ship. Distant Horizons is not part of the pack.
- Historical pack metadata/configuration is in `pack/disabled/forge-1.20.1`; the replaced Sodium metadata/lock in `pack/disabled/neoforge-26.3`. Disabled content is outside the active index.

Read the real target sources (the patched `minecraft-patched-26.3.0.90-sources.jar` under the scratch build directory's `moddev/artifacts`, see [NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md)) instead of assuming older signatures. Build through `tools/dev/build_263.py`.

## Native frame and resource ownership

| Stage/API | Contract |
|---|---|
| `ExtractLevelRenderStateEvent` | Read the live level/camera and extract per-view immutable state; attach it to `LevelRenderState` with a `ContextKey`. Workers consume immutable snapshots and must reject stale level/epoch completions. |
| `PrepareRenderBuffersEvent` | Upload native mesh buffers, resolve resource textures and reserve shared indices before drawing. `TextureManager.getTexture` can create/upload a previously unseen texture. |
| `CustomCloudsRenderer` | Normal `renderClouds` receives an active native `RenderPass`. Its OIT callback supplies `OitStage` and an `OitRenderPassProvider`; follow that lifecycle and target/depth ownership. See `atmosphere/client/OverworldClouds.java`. |
| `RenderLevelStageEvent` | Stages exposing `getRenderPass()` must borrow that pass. Never open a nested pass or upload a buffer/texture from inside it. `AfterSky`/`AfterLevel` permit separately owned passes; inspect the actual event’s documentation before adding another stage. |
| Native realm environment | `realms/client/RealmEffects.java` implements `CustomSkyboxRenderer`, `CustomCloudsRenderer` and `CustomWeatherEffectRenderer`. `RealmsClient` registers IDs and prepares extracted realm meshes/lightmaps. Keep authored realm atmosphere separate from overworld weather. |
| Resource reload/world change | Discard stale snapshots, worker results and GPU references; resolve the current texture-manager object after reload. Never overwrite another world’s storage or reuse a closed texture from the previous manager. |

Actual build 35 crashed because a first-use `TextureManager.getTexture` call uploaded inside the sky draw pass. Build 36 moved sky/cloud/realm lookups into preparation and rendered successfully. Native uniform writes also require the engine’s legal mapped dynamic storage. These are real lifecycle requirements; shader source compilation alone cannot detect them.

Shared native helpers live in `mod/src/main/java/com/nirncraft/render/client/`:

- `CpuMesh.builder(topology, format)` owns CPU mesh data; `WorldMesh` consumes it on upload and owns persistent GPU buffers. Prepare outside active passes and close superseded meshes.
- `WorldDraw.material(...)` supports `POSITION_COLOR` and `POSITION_TEX_COLOR`, explicit alpha/additive/replace blending, optional fog and far-depth clamping. `Material.register(event)` registers the regular and all three native OIT pipelines.
- `WorldDraw.open(target)` owns its pass; `WorldDraw.borrow(pass)` never closes the caller’s pass. Draw calls carry model-view, tint, texture and optional projection; OIT uses the explicit stage. The native depth comparison is reverse-Z `GREATER_THAN_OR_EQUAL`; translucent helpers do not write depth.
- `DataTexture` updates the existing resource texture in place, preserving Iris custom-sampler identity. It checks dimensions and supports small rectangular clock/header writes. Resolve/upload only outside active draw passes.

Native GLSL uses `#include <minecraft:...>`, native bind-group layouts and explicit pipeline definitions, rather than old `#moj_import`/OpenGL-global render state. Sources are under `mod/src/main/resources/assets/nirncraft/shaders/core/`. Do not add raw OpenGL calls to a normal render/profiling path: the target engine exposes Vulkan as well as OpenGL. Native `GpuQueryPool` availability is nonblocking; timestamp values must be multiplied by `DeviceInfo.timestampPeriod` in nanoseconds. Cloud timings borrow their active pass; whole-frame timings use the encoder outside passes.

## Resource texture transport

Paths below are under `nirncraft:textures/environment/`. Their PNG placeholders, producer layouts and shader consumers form one contract. Keep dimensions, byte/channel packing, world-coordinate addressing, clocks and sampler identity together. The producer source and preserved patcher are authoritative for detailed row/header encoding.

| Resource | Current producer/extent | Meaning |
|---|---|---|
| `weather_field.png`, `weather_dynamics.png` | `WeatherFieldTexture`;128×128,64blocks/cell | World-anchored toroidal near grid, 8,192 block span. Coverage/convection/precipitation and packed aerosol/lightning/smoke regime; dynamics carry altitude/wind/dew fog. |
| `weather_field_far.png`, `weather_dynamics_far.png` | Same producer;128×128,512blocks/cell |65,536 block span. Blend near/far fields in world space; do not turn camera movement into UV drift. |
| `fog_radiance.png` | `FogRadianceTexture`;128×128 | Loaded-chunk propagated block-light/colored-emitter cache and local media/header data. Reads are bounded; it does not request missing chunks. |
| `smoke_sources.png` | `SmokeSourceTexture`;32×4 | Bounded authored volcano/synced fire sources, shared positions/clocks plus atmosphere headers. Up to 24 source slots; retain producer packing and fade semantics. |
| `lightning_sources.png` | `LightningFieldTexture`;32×4 | Four 3D discharge packets; count at texel 31,0. Updated during actual flashes, separate from quantized weather grids. |
| `local_occlusion.png` | `LocalOcclusionTexture`;256×294 | Optional bounded loaded collision/shape transport for point-light occlusion. Disabled unless explicitly enabled; unknown/unloaded data is not proof of an empty room. |
| `sun_horizon.png` | `SunHorizonTexture`;1×1 | Sun-direction terrain horizon, 192..14,000 blocks. R encodes horizon sine/0.5; nearby structures remain shadow-map work. |

Common weather/media producers remain server-authoritative. Read `DimensionMediaPolicy`, `DungeonAir`, `InteriorAtmosphere`, `LocalLightOcclusion`, `ClientWeather`, `NightSkyState` and their packet contracts before changing how an interior or dimension receives exterior weather. Preserve roof/cave sealing and separate room/floor media from open-sky storms. The owner explicitly reported underground storm flashes as a defect; do not reintroduce them through a new global light path.

The old Complementary Reimagined patchers under `tools/shaders/` (`patch_complementary_clouds.py`, `patch_sun_horizon.py`, `realm_pipeline.py`) document how these effects were first authored; Infinite ATLAS reads the same textures directly and is the only shipped consumer.

## Validation

The source/main pack metadata supports native resource 97.1/data 121.0. The embedded `resourcepacks/recipe_rules` pack supports data 121.0 and intentionally filters the enchanting-table recipe and its unlock advancement. Validate source, processed resources and the packaged jar: Gradle expansion once corrupted a regex that was valid only in source.

```powershell
py -3 tools/migration/shaders_263.py --core-jar PATH_TO_ACTUAL_NATIVE_CORE.jar
py -3 tools/validate_pack.py --core-jar PATH_TO_ACTUAL_NATIVE_CORE.jar
py -3 -I shaderpack/build_pack.py --no-zip
```

The strict core shader gate covers the mod's own pipelines (106 pipeline configurations, 348 shader units and three backend macro profiles at the time of the port), plus vertex/binding/interface/pack-codec checks; `build_pack.py` compiles every InfiniteATLAS stage with glslang. Static success is not GPU acceptance: visual and frame checks run through the harness ([tools/harness/README.md](../tools/harness/README.md)), whose readiness rules require full local chunks, received lighting, the public light-FIFO witness, idle propagation/renderer queues, stable completed main-camera frames, unchanged view and settled interior/weather state. Screenshot encoding is asynchronous and can affect pacing; measure performance separately. Compare normal and improved/OIT transparency, reloads, reconnects, dimension transitions, near/far motion, mountains, cave openings and exterior-to-interior exposure; a genuine TCP connection (not a memory connection) is required for codec claims.

Known upstream library issue: official CreativeCore 2.14.21 eagerly reloads its GUI style during client setup before assets are available, logging `Could not load default style`. Its native resource-reload listener retries after assets load and the jar contains the style. Keep the official artifact unchanged; verify its real config screen after reload. This is separate from NirnCraft’s shader/light integration.
