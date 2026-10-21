# Start here

**Platform: Minecraft 26.3, NeoForge 26.3.0.90, Java 25.** The map of every doc, with the build, test and ship
commands, is [README.md](README.md). Build with `py -3 tools/dev/build_263.py --offline build`; test with the harness
([tools/harness/README.md](../tools/harness/README.md)); runtime controls are in [NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md).
Preserve the owner's own instances and worlds. Multiplayer uses normal Minecraft server lists and LAN. **Working on the
renderer? Read [ATLAS-STATUS.md](ATLAS-STATUS.md) first**: its present state, the owner's order of work, the rules,
what is in flight and what is broken.

**If you are a new session, read this first.** It takes five minutes and tells you what NirnCraft is, where everything
lives, where the work stands, what to run next and the rules you must keep.

## What the project is

NirnCraft is a Skyrim total conversion for Minecraft 26.3 NeoForge (Java 25, official Mojang mappings), grown into the
whole of Tamriel and the lands around it: its own terrain, biomes, micro-biomes, roads, towns, NPCs, dungeons, cave
networks, creatures, quests, magic, music, skybox/weather, and UI, with no third-party content mods (performance and
library mods are fine). It ships as a Prism Launcher instance and a Modrinth pack. NirnCraft is powered by
**Infinite ATLAS**: the native renderer `atlas_core` (`native/`; state: [ATLAS-STATUS.md](ATLAS-STATUS.md), design:
[ATLAS.md](ATLAS.md)) and NirnCraft's own Iris shader pack `InfiniteATLAS` (`shaderpack/InfiniteATLAS`). The owner's
goal: every province as full, unique and purposeful as Skyrim, **measured** against the real game, built from the
ground up so that everything in a place is explained by its geography, history and people ("world building is
king").

## Where things are

Every visual and non-visual test runs through one harness: `py -3 tools/harness/harness.py run` (see
[tools/harness/README.md](../tools/harness/README.md): tiers check / lab / live, the catalog, before/after results in
`.work/harness`, the gate). Live tests use only the disposable `NirnCraft-26.3-autotest` Prism instance; the harness
launches, drives (through `tools/dev/live_world.py`, [LIVE-WORLD-PREVIEW.md](LIVE-WORLD-PREVIEW.md)) and closes it, and
plans its cameras offline on the seeded terrain. Regional coverage bookkeeping is [WORLD-TESTING.md](WORLD-TESTING.md).

| What | Where |
| --- | --- |
| The mod (Java, resources, lang) | `mod/` (`mod/src/main/java/com/nirncraft/<module>`, `mod/src/main/resources/nirncraft/`) |
| Generators and QA tools | `tools/` (`tools/harness/` is the test harness; `tools/qa/` its audits, `tools/worldgen/`, `tools/marquee/`, `tools/bake/`, `tools/dev/`) |
| Infinite ATLAS | `native/` (atlas_core, [native/README.md](../native/README.md)), `mod/src/main/java/com/nirncraft/atlas`, `shaderpack/InfiniteATLAS`; **[ATLAS-STATUS.md](ATLAS-STATUS.md)** (where it stands), [ATLAS-RENDERER-PLAN.md](ATLAS-RENDERER-PLAN.md) (decisions), [ATLAS.md](ATLAS.md) |
| **The master plan, audit & roadmap (`T1`–`T12`)** | [`docs/world/WORLD-PLAN.md`](world/WORLD-PLAN.md) (Sections 7, 8, 9, and 10) |
| **The rulebook** | [`docs/world/DESIGN-PRINCIPLES.md`](world/DESIGN-PRINCIPLES.md) (world building first, the 11-layer sheet, the measured Skyrim floor) |
| Per-province designs | `docs/world/provinces/<province>.md` (ten sections each), `docs/world/ILIAC-BAY.md`, `docs/world/SCALE-AND-GEOGRAPHY.md` |
| **Ready-to-run `T1`–`T12` phase prompts** | [`docs/prompts/`](prompts/README.md) (`_COMMON.md` first) |
| Agent rules (build, code, legal, world building, fast loop) | [`docs/AGENT-RULES.md`](AGENT-RULES.md) |
| Generation pipeline (plan-first, P1-P7, fast loop) | [`docs/WORLDGEN-PIPELINE.md`](WORLDGEN-PIPELINE.md) |
| Scorecard against Skyrim | [`docs/PARITY.md`](PARITY.md) (`py -3 -I tools/qa/parity.py`) |
| Places that must say "wow", mysteries | [`docs/MARQUEE.md`](MARQUEE.md), [`docs/LORE-WEB.md`](LORE-WEB.md) |
| Domain specs | `docs/ECOLOGY.md`, `docs/FLORA.md`, `docs/HYDROLOGY.md`, `docs/LOOT.md`, `docs/MUSIC.md`, `docs/MOUNTS.md`, `docs/HOMESTEAD.md`, `docs/QUESTS.md`, `docs/JAIL.md`, `docs/REST.md`, `docs/INVENTORY.md`, `docs/UI-COVERAGE.md` |
| Player docs | `README.md`, `docs/FEATURES.md`, `docs/WORLD.md`, `docs/INSTALL.md`, `docs/CONTROLS.md` |
| Architecture and harnesses | `docs/DEVELOPING.md`, `docs/TAMRIEL.md` (world contracts, binding anchors in section 9) |
| Reference data from the owner's game installs | `.reference/` (gitignored; facts only; never committed) and a local checkout of the MIT DFO reimplementation outside the repository |
| Release output | `dist/` (built by `tools/build_release.ps1`, `tools/build_prism_instance.py`, `tools/build_mrpack.py`; not committed) |

## Current geography policy (2026-10-18)

The owner replaced Endless Lands expansion with distant ocean and opposite-edge overworld travel.
Tamriel and all fixed foreign lands remain. The old tile planner is retained for explicit tools and old
data, but runtime generation does not use it. See [TAMRIEL.md](TAMRIEL.md) section 3.

## Historical foundation state (2026-10-15)

* Branch `tamriel-overhaul` tracks `origin/tamriel-overhaul`. GitHub Actions is disabled; builds and tests run locally.
* **Foundation Complete (`P1`–`P7` & `R0`–`R15a`, commit `7b3d95f8`):**
  * **83 regions** (`75` land regions across all 9 provinces, Solstheim, and 5 Foreign lands + `8` sea regions),
  * **11 province world-model sheets** (`99` blocks × `11` causal layers = `3,019` fields, `100.0%` `F-WHY` across `30,424/30,424` content records),
  * **1,728 micro-biome rules** (`76.43` transitions/km of road),
  * **7,579 named places** connected by **7,768 road/lane/trail links** (`0` failed links, `0` isolated places, `0` extra road networks),
  * **5,788 planned scenes** (`12,096` props, `13.56` scenes/km² including regional border-crossing scenes, road corridor scenes, and deep-forest/island scenes),
  * **Procedural open-ocean micro-islands (`OceanIsles.java`)** (`SEA_STACK`, `ATOLL`, `CAY`, `SANDBAR` + maritime `MinorSites`),
  * **1,869 dungeons** (`4,386` identity entries, `118` bespoke 15–30 room marquee dungeon plans), **5,442 quests** (`60,237` lang keys), **7,510 lore records**, **83 Tamrielic festivals**, **P7 4,096×4,096 endless-lands tile planner**, and **61 item affix definitions**.
* **Comprehensive Tamriel Parity Audit (`docs/world/WORLD-PLAN.md` Section 8):**
  * Identified **12 concrete structural and simulation gaps** (`T1`–`T12` in [`docs/world/WORLD-PLAN.md`](world/WORLD-PLAN.md) Sections 8–10) needed to bring every non-Skyrim province and cross-world system to (and beyond) Skyrim's full in-game quality bar.

## Next

**The renderer first** (the owner, 2026-10-20): the order of work is in [ATLAS-STATUS.md](ATLAS-STATUS.md) (finish the
GPU conversion, a loading phase, vanilla's chunks removed, a tracing profiler, one residency manager); weather, biome
and world work wait until the owner says. T1–T12 are done (WORLD-PLAN section 10). The world programme queued after
the renderer is the **concept-art-driven Visual Targets pipeline**: the plan
[`docs/world/VISUAL-TARGETS-PLAN.md`](world/VISUAL-TARGETS-PLAN.md) (owner decisions final in its section 10) and the
phase prompts `docs/prompts/VT0-foundation.md`, `VT1-fast-verification.md`, `VT2-world-knobs.md`,
`VT3-volume-pass.md`. Its live-client queue, captures and before/after sheets are provided by the test harness
(`tools/harness/`). Start with VT0 once the 12 atlases are in `art/atlases/`.

## The rules (non-negotiable)

1. **Legal line.** Facts only (names, relative positions, counts, which features existed, why a design works).
   Nothing from any game's files (text, meshes, textures, sounds, music, voice, dialogue, rasters) enters the repo, raw
   or converted. Terrain is hand-traced vectors with our own heights. Every line of text is our own. Reference data
   stays in the gitignored `.reference/`.
2. **Push only when the owner explicitly requests it.** Commit locally on the checked-out branch (the owner picks it;
   the renderer's is `atlas-render`, see ATLAS-STATUS.md); worktree agents do not commit unless told to; the
   orchestrating session merges and commits. Never force anything; never touch another worktree; never a bare `git stash`.
3. **World building first.** Every element of a place expresses and agrees with its history, culture, conflict,
   economy, faith and ecology. Every new content record carries `why: [world-model sheet field ids]` that resolve.
4. **The floor is Skyrim, measured.** Per region `max(absolute floor, round(median density x km²))`; per settlement
   the tier medians (DESIGN-PRINCIPLES 4.1-4.9).
5. **Keep / amplify / replace** legacy sources; **source precedence**: Skyrim, Oblivion and Morrowind positions are
   canonical over Daggerfall and Arena.
6. **The fast loop.** Iterate with the harness (`tools/harness/harness.py run --changed`, `watch` for hot reloads)
   and the persistent dev server (`tools\dev\devserver.ps1 hotswap|run`, `/nirncraft-dev reload`,
   `/nirncraft-dev regen`); run `harness.py gate` before every merge. **Live client**: agents may use only the
   disposable `NirnCraft-26.3-autotest` Prism instance, and only through the harness (one live client and one dev
   server at a time; a second run queues behind the first). The owner signs off visuals. One or two agents at a time,
   one heavy job at a time (the resource rules in [AGENT-RULES.md](AGENT-RULES.md)); close the client after each test
   batch, and never run the autotest action queue while the owner plays.
7. **Gates are measurable and never weakened.** A phase is done when every gate in its prompt passes, measured by the
   named tool, with the numbers in the report.
