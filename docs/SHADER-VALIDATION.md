# Shader validation and texture state

The rendering review uses independent gates: common Java regressions, shared GLSL syntax checks,
whole patched pack source links, actual Oculus driver links, visual scenes and GPU frame measurements.
Passing an earlier gate does not accept a later one. Run build and game tasks through one coordinator;
parallel agents must not launch competing Gradle builds or Minecraft previews.

## Offline source checks

From the repository root:

```powershell
py -3 -I -m unittest discover -s tools/shaders -p test_render_lifecycle.py
py -3 -I -m unittest discover -s tools/shaders -p test_weather_patch.py
py -3 -I -m unittest discover -s tools/shaders -p test_realm_pipeline.py
py -3 -I tools/shaders/compile_shared_helpers.py --compiler dist/.cache/glslang-16.6.0/glslang.exe
py -3 -I tools/shaders/compile_shader_matrix.py --compiler dist/.cache/glslang-16.6.0/glslang.exe --full --out dist/code-review/shader-matrix-with-compute
```

The helper gate checks owned sources as GLSL130 and330 (six cases). The full matrix links504
vertex/fragment pairs: seven dimension folders; cloud quality1/2/3; native and Distant Horizons;
generic and AMD reflection compatibility; deferred1, composite1, terrain, water and shadow, plus
DH terrain/water when enabled. It additionally checks all compute entry points at colored-lighting
0/128/256/1024, in generic/AMD compatibility modes. Currently this is42 active compute links and14
program-disabled policy checks, totaling560 cases. Use `--compute-only --full` for those56 cases.
The compute compiler uses GLSL430 with custom-image host capability macros. Original conditional
program policies are evaluated using glslang preprocessing; disabled modes must keep their original
policy, while enabled modes must compile with actual voxel/flood-fill sampler/image declarations.
Realm folder policies inherit their base folder's shadow, FXAA and colored-lighting conditionals.
This prevents executing compute with absent resources and preserves selected lighting features.
`--shaderpack PATH` checks a specific built archive; otherwise a
fresh fixture is built from the pinned upstream cache using the same weather patch then solar cleanup order as the instance builder. Include expansion and capability macros
emulate the host preprocessor. DH material enum values and vertex `dhMaterialId` declaration match
Oculus1.8 StandardMacros and DHTerrainTransformer. This is a standalone CPU compiler test, **not**
Minecraft driver compilation, runtime uniform binding, a GPU benchmark or visual evidence.

Reports retain the compiler/pack SHA256, every result and failed expanded inputs. The pinned
upstream is ComplementaryReimagined_r5.9.3, SHA256
`fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`.
The patcher rejects unsupported upstream archives and modified prior checked outputs before mutation.
Build/install helpers rebuild from that pinned cache, rather than upgrading unknown legacy packs.

Compiler provenance: official Khronos [glslang16.6.0 release](https://github.com/KhronosGroup/glslang/releases/tag/16.6.0),
asset `glslang-16.6.0-windows-x86_64-release.zip`, verified against its GitHub release asset digest:
`82bf434e69b9bb4829de7e2b4bc2c5e7a7861e53d66cf75e5cc70f5f694a8d9b`.
The ignored cache executable requires no global install. It can be downloaded again from that release;
verify the digest before extraction. The matrix records the executable digest separately.

## Required live checks

Use the disposable preview and approved saved cameras from LIVE-WORLD-PREVIEW.md and
VISUAL-TEST-CAMERAS.md. Confirm installed core and shader archive hashes first. Capture moonlit
clouds, near/far Helgen smoke, moving cave fog and source/receiver wall openings, fresh native boot,
and shader on/off transitions. Inspect the actual images; camera/readiness receipts alone cannot
prove terrain rendered. Test Deadlands, Sovngarde, Shivering Isles, Apocrypha, Soul Cairn and Coldharbour
for authored sky/fog, local emitters and absence of terrestrial storm leakage. Isles retain day/night.
Native and shader paths require separate acceptance.

Texture upload ownership is explicit: TextureUploadState borrows texture unit0, synchronizes cached
and actual binding state, then restores the original unit0 binding and actual active unit. Nested
weather/media calls share one guard, so its outer batch performs two actual GL integer reads.
`CloudRenderer.requestTextureProbe()` logs once after solid terrain: actual/cached active unit,
unit0 binding, block atlas ID, shader slot0, GL program/VAO and depth-write state. Atmosphere diagnostics
also report guard average/max CPU microseconds, batch count and resynchronizations. Measure this on
the running client. A state read is not a GPU elapsed-query wait, but its CPU cost still needs evidence.
The deterministic TextureBindingStateTest covers stale caches, upload exceptions and Iris high units.
Do not mark shader toggles accepted until fresh-native and toggled-native geometry match.

## Receiver visibility prototype boundary

`InteriorAtmosphere.experimentalOcclusion(true)` or JVM property `nirncraft.localLightOcclusion=true`
enables the experimental loaded64-cube collision-shape cache. It is **off by default** pending measured
CPU/GPU costs and live acceptance. Default/native rendering retains the existing camera visibility
fallback; receiver-correct shadows remain a partial finding. The prototype samples four loaded16-cube
sections per tick, never forces chunk loads, publishes only complete windows and reports unsupported
shape/palette overflow as failed readiness. Palette overflow retries once without historical shapes.

The per-ray prototype is deliberately not a default optimization. Sixteen fog samples times four
lights times96 DDA iterations, with up to32 AABB reads per occupied cell, can approach215,000 texture
fetches per pixel in the pathological case. Even empty cells add visibility reads per light/sample.
A future conservative per-light cell visibility classification cache should amortize proven clear or
blocked cells and use exact DDA only for boundary cells. Keep source fidelity, sample counts and
resolution unchanged when evaluating that approach. Do not label this prototype a performance gain.

## Native toggle investigation checkpoint (2026-10-18)

The texture upload guard is a safety correction, not a verified fix for missing terrain after switching
Oculus off. Phase3 showed the failure even with matching active/binding caches. Phase4 showed correct
terrain/fire on its initial probed toggle and three subsequent unprobed toggles in the same session.
No root cause is established. Keep both failing and successful evidence; do not erase a failure with
later successful screenshots. The implementation report under ignored dist/code-review/fixes records
core hashes, image paths, state values and limitations.

The deeper requested probe logs AFTER_SKY and AFTER_SOLID_BLOCKS, optional renderer public API state,
main/framebuffer attachments, native shader IDs/link/format, VBO lifetime/bytes and nine depth samples.
This readback may synchronize GPU work. It must not be included in performance timing, and its timing
influence needs an independent freshboot/no-probe control. Iris also emits terrain-stage events while
rendering shadow maps: the first shader-on probe was consumed by that pass. Recognize orthographic
projection, shadow shader names and shadow-size viewport; those are not primary-camera evidence.
The current probe still needs an explicit public shadow-pass filter before using it for primary-view
shader comparisons.

Before evaluating cave fog/pop-in, load the real world completely with the strengthened readiness gate
(including renderer busy work, pending uploads, completed render frames and lighting). Queue emptiness
and camera clearance alone do not prove a valid final scene.

There is a separate native cloud dispatch check still outstanding. Vanilla LevelRenderer does not call
the dimension renderClouds hook when getCloudsType()==OFF; Distant Horizons reports disabling vanilla
clouds. NirnCraft's native hook has no fallback stage. Record the actual runtime clouds option and
native cloud frame/build counters before claiming its lunar/cloud rendering has been visually tested.
Do not change dispatch/fidelity during the readiness A/B experiment.
