# Start here

**Current platform: Minecraft 26.3, NeoForge 26.3.0.90, Java 25.** Follow
[NATIVE-26.3-PREVIEW.md](NATIVE-26.3-PREVIEW.md) for the current build, persistent dev server and isolated client controls.
Historical Forge 1.20.1 / Java 17 descriptions below remain project history. Preserve those older instances and worlds;
the native port's full build, server and visual acceptance gates must pass independently. Multiplayer uses normal
Minecraft server lists and LAN; Essential/e4mc are disabled.

**If you are a new session, read this first.** It takes five minutes and tells you what NirnCraft is, where everything
lives, where the work stands, what to run next and the rules you must keep.

## What the project is

NirnCraft is a Skyrim total conversion for Minecraft 1.20.1 Forge (Java 17, official Mojang mappings), grown into the
whole of Tamriel and the lands around it: its own terrain, biomes, micro-biomes, roads, towns, NPCs, dungeons, cave
networks, creatures, quests, magic, music, skybox/weather, and UI, with no third-party content mods (performance and
library mods are fine). It ships as a Prism Launcher instance. The owner's goal: every province as full, unique and
purposeful as Skyrim, **measured** against the real game, built from the ground up so that everything in a place is
explained by its geography, history and people ("world building is king").

## Where things are

For modular regional testing, evidence, shader comparisons and performance sampling, read
[WORLD-TESTING.md](WORLD-TESTING.md). `tools/qa/world_test.py` supports individual systems or the whole coverage plan.

For live visual/world iteration, first read [LIVE-WORLD-PREVIEW.md](LIVE-WORLD-PREVIEW.md) and run
`py -3 tools/dev/live_world.py status`. A disposable Prism preview may already be running; reuse it and preserve the
owner's normal instance. The guide covers window/process detection, clean launch/close, live updates and bake uploads.
For all visual tests, follow [VISUAL-TEST-CAMERAS.md](VISUAL-TEST-CAMERAS.md): plan and routes, local scouting in the
real loaded world, visual review, then saved cameras. Generation changes invalidate saved camera approval.

| What | Where |
| --- | --- |
| The mod (Java, resources, lang) | `mod/` (`mod/src/main/java/com/nirncraft/<module>`, `mod/src/main/resources/nirncraft/`) |
| Generators and QA tools | `tools/` (`tools/qa/qa.ps1` is the harness; `tools/worldgen/`, `tools/marquee/`, `tools/bake/`, `tools/dev/`) |
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
| Reference data from the owner's game installs | `.reference/` (gitignored; facts only; never committed) & `F:\daggerfall-js-source-main\daggerfall-js-source-main` |
| Release output | `dist/` (built by `tools/build_release.ps1`; not committed) |

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

T1–T12 are done (WORLD-PLAN section 10). The next programme is the **concept-art-driven Visual Targets pipeline**:
the plan [`docs/world/VISUAL-TARGETS-PLAN.md`](world/VISUAL-TARGETS-PLAN.md) (owner decisions final in its section
10), the operating manual [`docs/world/VISUAL-TARGETS-WORKFLOW.md`](world/VISUAL-TARGETS-WORKFLOW.md) (board, claims,
broker, fast loop, saving progress), and the phase prompts `docs/prompts/VT0-foundation.md`,
`VT1-fast-verification.md`, `VT2-world-knobs.md`, `VT3-volume-pass.md`. Start with VT0 once the 12 atlases are in
`art/atlases/`.

## The rules (non-negotiable)

1. **Legal line.** Facts only (names, relative positions, counts, which features existed, why a design works).
   Nothing from any game's files (text, meshes, textures, sounds, music, voice, dialogue, rasters) enters the repo, raw
   or converted. Terrain is hand-traced vectors with our own heights. Every line of text is our own. Reference data
   stays in the gitignored `.reference/`.
2. **Push only when the owner explicitly requests it.** Commit locally on `tamriel-overhaul`; worktree agents do not commit; the orchestrating
   session merges and commits. Never force anything; never touch another worktree; never a bare `git stash`.
3. **World building first.** Every element of a place expresses and agrees with its history, culture, conflict,
   economy, faith and ecology. Every new content record carries `why: [world-model sheet field ids]` that resolve.
4. **The floor is Skyrim, measured.** Per region `max(absolute floor, round(median density x km²))`; per settlement
   the tier medians (DESIGN-PRINCIPLES 4.1-4.9).
5. **Keep / amplify / replace** legacy sources; **source precedence**: Skyrim, Oblivion and Morrowind positions are
   canonical over Daggerfall and Arena.
6. **The fast loop.** Iterate with Tier 0 JUnit tests (`tools\qa\qa.ps1 -Unit`) and the persistent dev server
   (`tools\dev\devserver.ps1 hotswap|run`, `/nirncraft-dev reload`, `/nirncraft-dev regen`); run the full gates once per
   phase (`tools\qa\qa.ps1 -Server -Rebake`). **Live client**: agents may use only the authorised autotest Prism
   instance (`docs/LIVE-WORLD-PREVIEW.md`), and only through the live client broker
   (`docs/world/VISUAL-TARGETS-PLAN.md` 5.1): one live client and one dev server at a time, jobs queued and batched.
   The owner signs off visuals. At most 3 agents at once.
7. **Gates are measurable and never weakened.** A phase is done when every gate in its prompt passes, measured by the
   named tool, with the numbers in the report.
