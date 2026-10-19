# NirnCraft: instructions for any Claude session

NirnCraft is an Elder Scrolls world for Minecraft **26.3, NeoForge 26.3.0.90, Java 25**, powered by **Infinite
ATLAS** (the far-field renderer `atlas_core` + the shader pack `shaderpack/InfiniteATLAS`). Start with
[`docs/README.md`](docs/README.md) (the doc map), then [`docs/START-HERE.md`](docs/START-HERE.md) and
[`docs/AGENT-RULES.md`](docs/AGENT-RULES.md). The master plan and its state table are `docs/world/WORLD-PLAN.md`
(update section 7 on every merge); phase prompts are in `docs/prompts/`.

## Build, test, ship
* Build: `py -3 tools/dev/build_263.py --offline build` (Gradle + every JUnit suite; output on the scratch drive).
  Native renderer: `native/build.ps1` (see `native/README.md`, `docs/ATLAS.md`).
* Re-bake after changing world-generation classes or data: `tools/bake/bake.ps1` (a jar with stale bakes is refused by
  `tools/dev/live_world.py install-core`; never test with the old jar).
* **Test through the harness only: `py -3 tools/harness/harness.py`** ([README](tools/harness/README.md)). It owns the one
  disposable test client (`NirnCraft-26.3-autotest`), closes it after every run, and never touches a normal instance.
  Do not launch Minecraft any other way. Cheap first: `run --changed` / `run --tier check`, then `run --suite smoke`;
  `watch` for live reload; `gate` before merging or pushing (it runs exactly the tests your changes can affect).
  Start runs as a tracked background task (or follow them with `harness.py wait`) so you are woken when they end;
  `harness.py stop` ends one at once. Long checks: `audit --budget 30m` / `--theme dungeons`.
* Issues: `harness.py issues` is the running tally (P1..P4, grouped by cause). Working one: `issues claim <test>
  --by NAME` -> fix -> `issues fixed <test> --note ...` -> `issues verify` (re-runs it); visual ones then need the
  reviewer's `issues confirm <test>`. A failed verify reopens it.
* Visual results: **hand them to the `visual-reviewer` subagent** (`.claude/agents/visual-reviewer.md`, Haiku) with
  the agent folder the run prints; never read stacks of screenshots in the main conversation. Ask for `zoom` crops when
  a detail matters. State plainly what is still unverified visually.
* New registry entries (items, weapons, armour, blocks, entities) must satisfy `tools/harness/audits/contracts.py`
  (wired into the gate); the gallery shows them by itself (`run --tag gallery:entity:<id>`, `gallery:material:<m>`).
* Ship: `shaderpack/build_pack.py` + `tools/refresh_packwiz.py`, `tools/build_prism_instance.py`, `tools/build_mrpack.py`.

## Non-negotiables
* Work on the current branch (`infiniteATLAS`); commit locally after each green step. **Push only when the owner
  explicitly asks.** GitHub Actions is disabled. Keep logs, reports, caches and archives out of Git (`.work/`,
  `dist/`, the scratch drive); the project's pre-rename name must not reappear anywhere (a case-insensitive `git grep` for it must stay empty).
* Legal: the owner's game installs (Skyrim, Oblivion, Morrowind, Daggerfall, Arena, Redguard) and the MIT DFO
  reimplementation are reference for FACTS only (names, positions, distances, heights, layouts, road topology). Raw
  extractions stay in the gitignored `.reference/`. Never ship or commit Bethesda text, textures, models or sounds.
* World building is king: everything must build the world and cite the region's causal world-model sheet
  (`docs/world/DESIGN-PRINCIPLES.md`). The quality bar is Skyrim, measured (per-hold densities), ESO features optional.
  Legacy-game data is a starting point to modernise, never the target. Skyrim/Oblivion/Morrowind positions are
  canonical over Daggerfall/Arena.
* Plan-first generation (`docs/WORLDGEN-PIPELINE.md`): road/street/scene plans are baked, seed-independent data;
  chunks only evaluate them. The test world is seed 1234; absolute camera poses belong to the seed they were made in.
* Machine safety: one game client and one dev server at a time (`-Xmx4G`), GPU work only when the GPU is mostly idle,
  never kill processes you did not start.
* Merge helpers: `tools/dev/merge_worktree.sh <worktree> <NAME>`, `tools/dev/resolve_generated.py`.
