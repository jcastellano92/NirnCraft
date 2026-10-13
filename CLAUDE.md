# Skycraft: instructions for any Claude session

**Start by reading `docs/START-HERE.md`** (what the project is, the current state, the next phase and its prompt file),
then `docs/AGENT-RULES.md`. The master plan and its state table are `docs/world/WORLD-PLAN.md` (section 7: update it
on every merge); ready-to-run phase prompts are in `docs/prompts/`; the previous session's loose ends are in
`docs/HANDOFF.md`.

## Non-negotiables
* Branch `tamriel-overhaul`. **Never push.** Commit locally after each green step.
* Legal: the owner's game installs (Skyrim, Oblivion, Morrowind, Daggerfall, Arena, Redguard) and the MIT DFO
  reimplementation are reference for FACTS only (names, positions, distances, heights, layouts, road topology). Raw
  extractions stay in the gitignored `.reference/`. Never ship or commit Bethesda text, textures, models or sounds.
* World building is king: everything must build the world and cite the region's causal world-model sheet
  (`docs/world/DESIGN-PRINCIPLES.md`). The quality bar is Skyrim, measured (per-hold densities), ESO features optional.
  Legacy-game data is a starting point to modernise, never the target. Skyrim/Oblivion/Morrowind positions are
  canonical over Daggerfall/Arena.
* Plan-first generation (`docs/WORLDGEN-PIPELINE.md`): road/street/scene plans are baked, seed-independent data;
  chunks only evaluate them. Re-bake after changing world-generation classes (`qa.ps1 -Server -Rebake`).
* Verify cheaply first: `tools/dev/devserver.ps1` + `tools/qa/quick_corridor.ps1` (fast loop), then
  `tools\qa\qa.ps1 -Server -Rebake` before every commit; release builds with `tools\build_release.ps1`.
  One server at a time (`-Xmx4G`). Nested Forge event-handler classes need unique simple names per package.
* The owner playtests visuals; never launch the Minecraft client yourself. State what is unverified visually.
* Tool and merge helpers: `tools/dev/merge_worktree.sh <worktree> <NAME>`, `tools/dev/resolve_generated.py`.
