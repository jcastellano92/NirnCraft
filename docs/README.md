# NirnCraft documentation map

**NirnCraft** turns Minecraft into The Elder Scrolls V: Skyrim and keeps going to the edge of Nirn: one custom mod,
NirnCraft Core (`mod/`, mod id `nirncraft`, Java package `com.nirncraft`), builds the whole of Tamriel with its own
terrain, towns, people, quests, magic, music and UI. The pack (`pack/`) adds only performance and library mods.
NirnCraft is powered by **Infinite ATLAS**: the native renderer `atlas_core` (`native/`), one renderer from the
player's feet to the horizon, and NirnCraft's own Iris shader pack `InfiniteATLAS` (`shaderpack/InfiniteATLAS`, shipped
as `pack/shaderpacks/InfiniteATLAS.zip` until our own pipeline replaces Iris). Where the renderer stands:
[ATLAS-STATUS.md](ATLAS-STATUS.md).

Platform: **Minecraft 26.3, NeoForge 26.3.0.90, Java 25.** All commands run from the repository root.

## Build

```powershell
py -3 tools/dev/build_263.py --offline build      # the mod: full build + JUnit (Java 25, isolated Gradle home)
.\native\build.ps1                                 # atlas_core + its tests (Linux: native/build_linux.sh)
py -3 -I shaderpack/build_pack.py                  # InfiniteATLAS: glslang check of every stage, then the zip
```

`build_263.py` uses the Java 25 install named by `NIRNCRAFT_JAVA25_HOME` (or the toolchains location file) and keeps
Gradle outputs out of the checkout, in `NIRNCRAFT_263_SCRATCH` (machine default `E:\NirnCraft-scratch\migration-26.3`):
the jar lands in `build/libs` there. Native toolchains (Slang, CMake, Vulkan SDK, JDK) live in the `toolchains` folder
beside the checkout; see [native/README.md](../native/README.md). The fast server loop is `tools/dev/devserver.ps1`
([NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md)); architecture and generators are in [DEVELOPING.md](DEVELOPING.md).

## Test

One entry point for every visual and non-visual test: **[tools/harness/README.md](../tools/harness/README.md)**.

```powershell
py -3 tools/harness/harness.py run              # the smoke suite (checks, offline renders, a short live tour)
py -3 tools/harness/harness.py run --changed    # only what your changes can affect
py -3 tools/harness/harness.py watch            # hot reload shaders / textures / data with before|after sheets
py -3 tools/harness/harness.py gate             # the merge / push gate
py -3 tools/harness/harness.py audit --budget 30m   # a random, well-spread sample (5m / 30m / 1h / 2h; --theme dungeons)
py -3 tools/harness/harness.py issues           # the running issue tally and its fix workflow
py -3 tools/harness/harness.py wait             # block until the current run ends (agents: run in the background)
```

Tiers: `check` (audits, lints, JUnit, native suites), `lab` (Infinite ATLAS offline renders, no Minecraft) and `live`
(the disposable `NirnCraft-26.3-autotest` Prism instance, driven through `tools/dev/live_world.py`). Results and
reports go to `.work/harness` (never committed). In game, hosts can run the same camera planner with
`/nirncraft-tour`. Background on the live preview instance: [LIVE-WORLD-PREVIEW.md](LIVE-WORLD-PREVIEW.md);
regional coverage bookkeeping: [WORLD-TESTING.md](WORLD-TESTING.md).

## Ship

| Output | Command |
| --- | --- |
| Shader pack | `py -3 -I shaderpack/build_pack.py`, copy `shaderpack/build/InfiniteATLAS.zip` to `pack/shaderpacks/`, then `py -3 tools/refresh_packwiz.py` |
| Pack index | `py -3 tools/refresh_packwiz.py` after any change under `pack/`; `py -3 tools/validate_pack.py` checks it |
| Prism instance | `py -3 tools/build_prism_instance.py --core-jar <jar>` (`dist/NirnCraft-26.3-client-instance.zip`) |
| Modrinth pack | `py -3 tools/build_mrpack.py --core-jar <jar>` (`dist/NirnCraft-26.3.mrpack`) |
| Dedicated server | `py -3 tools/build_server_instance.py --core-jar <jar> --java <java25>` (`dist/NirnCraft-26.3-server.zip`); see [SERVER-26.3.md](SERVER-26.3.md) |
| Full release | `tools/build_release.ps1` (build, verified bake, client + server, server smoke gate) |

Players install with [INSTALL.md](INSTALL.md). Release notes are in `releases/notes/`.

## Infinite ATLAS (rendering)

* **[ATLAS-STATUS.md](ATLAS-STATUS.md) first**: the renderer's present state, the owner's order of work, the standing
  and resource rules, branches in flight, open bugs, what is still on the CPU, tools and how to test. Older renderer
  docs are history where they disagree with it.
* [ATLAS-RENDERER-PLAN.md](ATLAS-RENDERER-PLAN.md): the decision log (phases R0-R7, experiments, the owner's
  directions); [ATLAS-RENDER-INVENTORY.md](ATLAS-RENDER-INVENTORY.md): every item the 26.3 frame draws and its hooks.
* [ATLAS.md](ATLAS.md): what atlas is, measurements, how it joins the mod, portability;
  [native/README.md](../native/README.md): building `atlas_core`, the C ABI, failure handling, offline renders.
* The world it draws: [ATLAS-LOD.md](ATLAS-LOD.md) (one representation, generated levels),
  [ATLAS-CLIENT-GENERATION.md](ATLAS-CLIENT-GENERATION.md) (the GPU generator), [ATLAS-DELTAS.md](ATLAS-DELTAS.md)
  (the change feed), [ATLAS-STREAMING.md](ATLAS-STREAMING.md) (streaming and the client-owned world design),
  [ATLAS-RESEARCH.md](ATLAS-RESEARCH.md) (the surveys behind it).
* The sky and the hooks weather will drive: [NIRN-SKY.md](NIRN-SKY.md), [WEATHER-WORK.md](WEATHER-WORK.md).
* Machines and budgets: [PLATFORMS.md](PLATFORMS.md), [ADAPTIVE-QUALITY.md](ADAPTIVE-QUALITY.md) (the GPU budget
  controller of the shader-pack path).
* The shader-pack path: [NATIVE-26.3-RENDER-HANDOFF.md](NATIVE-26.3-RENDER-HANDOFF.md) (26.3 render-pass ownership and
  the mod's data textures the pack reads), [ATLAS-VISUALS.md](ATLAS-VISUALS.md) (historical: its effect matrix).
* [NATIVE-INTEGRATION-CONTRACTS-26.3.txt](NATIVE-INTEGRATION-CONTRACTS-26.3.txt): weather, media, terrain and clock data the renderer may consume.
* [native/java/mod-integration/README.md](../native/java/mod-integration/README.md): the Java side of atlas.

## Design and world

* Start of any session: [START-HERE.md](START-HERE.md), then [AGENT-RULES.md](AGENT-RULES.md) (legal line, world-building rules).
* The world: [world/WORLD-PLAN.md](world/WORLD-PLAN.md) (master plan), [world/DESIGN-PRINCIPLES.md](world/DESIGN-PRINCIPLES.md)
  (the rulebook), [world/SCALE-AND-GEOGRAPHY.md](world/SCALE-AND-GEOGRAPHY.md), [world/ILIAC-BAY.md](world/ILIAC-BAY.md),
  `world/provinces/*.md`, [TAMRIEL.md](TAMRIEL.md) (Java contracts), [WORLDGEN-PIPELINE.md](WORLDGEN-PIPELINE.md),
  [WORLDGEN-26.3.txt](WORLDGEN-26.3.txt) (26.3 worldgen and data boundaries), [PARITY.md](PARITY.md).
* Content: [MARQUEE.md](MARQUEE.md), [LORE.md](LORE.md), [LORE-WEB.md](LORE-WEB.md), [QUESTS.md](QUESTS.md),
  [EXPLORATION.md](EXPLORATION.md), [ECOLOGY.md](ECOLOGY.md), [FLORA.md](FLORA.md), [HYDROLOGY.md](HYDROLOGY.md).
* Systems: [LOOT.md](LOOT.md), [INVENTORY.md](INVENTORY.md), [MOUNTS.md](MOUNTS.md), [HOMESTEAD.md](HOMESTEAD.md),
  [JAIL.md](JAIL.md), [REST.md](REST.md), [MUSIC.md](MUSIC.md), [UI-COVERAGE.md](UI-COVERAGE.md),
  [NETWORK-PROTOCOL.txt](NETWORK-PROTOCOL.txt).
* World generation reviews: [WORLDGEN-REVIEW.md](WORLDGEN-REVIEW.md) (the choices for the world rework),
  [GPU-WORLDGEN-PLAN.md](GPU-WORLDGEN-PLAN.md) (historical audit).
* Players: [FEATURES.md](FEATURES.md), [WORLD.md](WORLD.md), [CONTROLS.md](CONTROLS.md), [INSTALL.md](INSTALL.md).
* Plans and phase prompts: [world/VISUAL-TARGETS-PLAN.md](world/VISUAL-TARGETS-PLAN.md), [prompts/](prompts/README.md).
* Old saves: [NATIVE-DATA-MIGRATION-26.3.txt](NATIVE-DATA-MIGRATION-26.3.txt) converts a pre-26.3 world copy.
