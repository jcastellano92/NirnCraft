# Minecraft 26.3 client acceptance

Target: Minecraft 26.3, NeoForge 26.3.0.90, Java 25. Use the isolated `Skycraft-26.3-autotest` instance. Preserve the old 1.20.1 instances, custom Distant Horizons binaries, shaders and their patchers. Those integrations remain disabled until individually verified against this platform.

## Verified locally during the port

- Pack dependency/index/hash validation and eight validator regression tests.
- Native item definitions: 1,490 definitions, 74 authored variant sets, 112 spawn egg palettes; five regression tests check variant boundary behavior, model references and tint preservation.
- Native shaderc/SPIR-V gate: 106 actual pipeline configurations, 348 shader units, three backend macro profiles; no shader compilation/interface/vertex/binding/target errors. Native pack codecs accept resource97.1/data121.0. GPU rendering still requires in-game checks.
- Pure Java 25 terrain readiness checks against the installed Sodium 0.9.2 counter format, including busy workers, deferred builds, pending uploads and completed main-camera frames.
- Central compilation is recorded separately in `dist/code-review/neoforge-migration/compile-*.log`; focused checks do not certify the complete mod or gameplay.

Preparation without launching the game: [native preview workflow](../../docs/NATIVE-26.3-PREVIEW.md). Five isolation/integrity tests cover the native preparer, including unchanged legacy instances, exact dependency hashes, running-instance refusal and backup retention.

## Runtime attempts, 2026-10-18

The native full build35 passed 231 tests without failures, errors or skipped tests. This is separate from client acceptance.

The first full-pack client reached its actual native graphics startup, then failed in official Sodium0.9.2's `LevelExtractorMixin`: it expects the four-argument `extractVisibleBlockEntities` overload removed by newer NeoForge26.3 patches. The owner approved official Sodium0.9.3-beta.1 (`SS023RVV`), containing [official Sodium PR3948](https://github.com/CaffeineMC/sodium/pull/3948). That artifact is now pinned; the original0.9.2 metadata and cached artifact are preserved. Iris remains1.11.7 (`Ag5S66CF`). Its unchanged native jar declares Sodium`[0.6,)`; the platform lock records the narrow, artifact/hash-bound exception to Iris's older Modrinth dependency ID. Full-pack startup and rendering with this approved pair still require actual acceptance.

A separate `Skycraft-26.3-core-diagnostic` instance with build35's actual core and no third-party mods reached an integrated Tamriel world, loaded the native assets/bakes and initialized EFX reverb with two auxiliary sends. It then exposed a lazy texture upload inside the sky render pass. Source fixes are being verified through the central build and a later actual run; this attempt produced no accepted loaded-world visual comparison. The full-pack instance and dependency pins were preserved. Diagnostic results cannot establish full-pack, custom shader or Distant Horizons acceptance.

Build36 passed233 tests. Its actual core diagnostic rendered the integrated world with native GPU timestamp samples and captured the character creator. That screenshot exposed spectator-state body culling in the menu snapshot; the next source checkpoint overrides only the preview state so the torso, limbs, armor and racial features can render. The real player's spectator state and invisibility effects are preserved. Recheck the full avatar visually after rebuilding.

The actual complete build37 pack then rendered the full character body and produced inventory, magic, journal, map, skills and hub captures. Build38 passed238 tests, reopened the preserved build37 native save, and passed strict world readiness in1.6seconds:317/317 full chunks and light columns, completed FIFO work, idle light engine/Sodium queues and stable completed main frames at a clear camera. Its Helgen overview and Journal captures were saved with receipts; the Journal screenshot confirms all eight tabs fit. The next source checkpoint adjusts the bottom quest-action controls and releases the capture probe's old-level reference on logout. These local checks do not certify every scene, creature, multiplayer interaction or custom shader/distant integration.

## Runtime gates before calling the migration complete

1. Build the whole mod and tests with Java 25, then start a dedicated server. Require the native registries, loot, recipes, tags, dimensions and equipment assets to load without missing IDs, duplicate registration or client class linkage.
2. Start the isolated native client with custom shader packs disabled. Check title, new/load world, options, accessibility, add/edit/direct-connect server list, LAN discovery, LAN publication and address-copy help. Normal Minecraft networking is used; no Essential/e4mc relay is required.
3. Check keyboard SDL scancodes, left/right/middle mouse actions, wheel input, controller input, remapping, every inventory/workstation, long names, tooltip placement, item previews, bow draw/crossbow charge/shield use and small/large GUI layouts. The loading artifact must fade and rotate. Race previews must retain body scales, skins, armor, beast features, animation and sheathed weapons.
4. Compare preserved creatures, NPC dialogue, corpse poses, large dragon/ship culling bounds and authored furniture/sign geometry. Test multiple actors together so deferred rendering cannot reuse another actor's mutable model pose.
5. Use the existing terrain-aware camera plan and cached-pose identity checks. Require all nearby full chunks, received light columns, a drained public FIFO light-queue witness, idle light propagation, strict Sodium queue counters, stable completed main frames, unchanged camera and ready interior/weather media before capture. Do not treat a screenshot of an incomplete cave as evidence of fog quality.
6. Inspect sky and smoke in normal and native improved transparency, daylight/night, close/far and motion. Smoke keeps its native entity lightmap; flames use explicit feathered additive blending. Check foreground terrain occlusion, cloud depth, cave sealing, storm light, and each realm's original sky/fog/light tint. Native realm tints use a 16x16 GPU postmultiply/copy with original >1 colour boosts.
7. Capture motion and performance at fixed loaded poses. Native GPU readbacks are asynchronous: PNGs are written off-thread, published atomically, and live scripts remain pending until the writes finish. Metadata describes the request frame. Screenshot bursts must not overlap performance timing.
8. Reload resources, disconnect/reconnect, switch dimensions and worlds; require no retained old-world effects or GPU resources. Only then package a full client and server release with an exact native core jar; never substitute the previous Forge core.

Custom Iris shader hooks, custom Distant Horizons, live visual fidelity and frame-time claims remain unverified until their corresponding in-game gates pass. No successful final archive is implied by these source changes.
