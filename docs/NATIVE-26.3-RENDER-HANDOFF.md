# Native rendering integration handoff

This is a read-only contract map for the separately assigned lighting, shader and distant-visual workstreams. It records the migration checkpoint of 2026-10-18; it does not certify those custom integrations. Coordinate ownership with the primary agent before editing shared files. The owner's external agents now own the custom lighting pass, shader and distant-visual port; the migration agents have stopped further integration changes there. UI capture readiness, avatar preview layout and Journal tabs remain the approved native UI checkpoint.

## Exact platform and preserved work

- Minecraft 26.3, NeoForge 26.3.0.90, Java 25; no 26.2 or older-core fallback.
- Official Sodium 0.9.3-beta.1 (`SS023RVV`) is owner-approved after the actual 0.9.2 mixin failure. Iris remains 1.11.7 (`Ag5S66CF`), installed with shader packs disabled. The exact artifact/hash-bound dependency exception is in [platform-lock.json](../pack/migration/platform-lock.json); `tools/validate_pack.py` rejects broader overrides.
- The owner’s customized previous shader/Distant Horizons binaries, sources, settings and patchers are preserved. They have not passed native 26.3 acceptance. Do not replace them with stock versions, delete their custom hooks, or silently enable them.
- Prior source/custom-artifact snapshot: `E:/NirnCraft-scratch/migration-26.3/baseline-20261018`. Historical pack metadata/configuration is in `pack/disabled/forge-1.20.1`; the replaced native Sodium metadata/lock is in `pack/disabled/neoforge-26.3`. Disabled content is outside the active index.
- The repository contains substantial pre-existing and migration edits. Preserve them; do not reset, regenerate unrelated bakes, commit or push without coordination.

Actual target sources/classes are `E:/NirnCraft-scratch/migration-26.3/build/moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar` and the adjacent class jar. Consult those sources instead of assuming 1.20.1/26.1 signatures. Native builds run centrally through `tools/dev/build_263.py`; coordinate all Gradle/game processes. [The native workflow](NATIVE-26.3-PREVIEW.md) covers process identity, isolated saves, control queues, receipts and clean shutdown.

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

Preserved custom-shader integration work includes `tools/shaders/patch_complementary_clouds.py`, `patch_sun_horizon.py`, `realm_pipeline.py`, bake helpers and their tests. Those files document existing authored effects; they are not evidence that the older shader package works on26.3. Preserve custom DH settings/binaries and rebase their actual source/hooks before enabling native distant rendering.

## Validation and current evidence

The source/main pack metadata supports native resource 97.1/data 121.0. The embedded `resourcepacks/recipe_rules` pack supports data 121.0 and intentionally filters the enchanting-table recipe and its unlock advancement. Validate source, processed resources and the packaged jar: Gradle expansion once corrupted a regex that was valid only in source.

```powershell
py -3 tools/migration/shaders_263.py --core-jar PATH_TO_ACTUAL_NATIVE_CORE.jar
py -3 tools/validate_pack.py --core-jar PATH_TO_ACTUAL_NATIVE_CORE.jar
```

The strict shader gate currently covers 106 pipeline configurations, 348 shader units and three backend macro profiles, plus vertex/binding/interface/pack-codec checks. It excludes old NirnCraft core jars from the classpath. Static success is not actual GPU acceptance. Native event subscribers are separately audited against the real target event hierarchy.

Build 37 passed 234 tests. The complete 22 dependency pack with the approved Sodium/Iris pair entered an actual integrated Tamriel world. Native sky/cloud paths, EFX initialization and asynchronous whole-frame GPU samples ran; real UI captures exposed and then proved the spectator-avatar visibility fix. Build 38 passed 238 tests and reopened the preserved native save. Its corrected FIFO readiness gate settled in 1.6 seconds with 317/317 chunks/light columns, idle propagation/Sodium queues, stable completed main frames and a clear camera; a Helgen overview capture and receipt were saved. The Journal screenshot confirms all eight tabs fit. Custom shader packs and customized DH remain disabled. These samples are bounded scene checks, not every-weather/every-region visual acceptance; UI screenshot receipts with `readinessPassed:false` do not prove the surrounding terrain/atmosphere is settled.

For every accepted world comparison, use cached/scouted clear poses and require full local chunks, received lighting, the real public light-FIFO witness, idle propagation/renderer queues, stable completed main-camera frames, unchanged view and settled interior/weather state. Screenshot encoding is asynchronous and can affect pacing; wait for atomic PNG/receipt publication and measure performance separately. Compare normal and improved/OIT transparency, reloads, reconnects, dimension transitions, near/far motion, mountains, cave openings and exterior-to-interior exposure. Complete the [client acceptance checklist](../pack/migration/CLIENT-PORT-ACCEPTANCE.md), including genuine TCP codec observations; memory connections bypass that proof.

Known upstream library issue: official CreativeCore 2.14.21 eagerly reloads its GUI style during client setup before assets are available, logging `Could not load default style`. Its native resource-reload listener retries after assets load and the jar contains the style. Keep the official artifact unchanged; verify its real config screen after reload. This is separate from NirnCraft’s shader/light integration.
