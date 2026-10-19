# Common rules for every phase prompt

> **Platform update (2026-10-19).** NirnCraft now targets Minecraft 26.3 / NeoForge 26.3.0.90 / Java 25. Build with
> `py -3 tools/dev/build_263.py --offline build`; test with the harness (`py -3 tools/harness/harness.py run`,
> `run --changed`, `gate`; see `tools/harness/README.md`), which replaces the `qa.ps1` and `gradlew` commands below.
> The doc map is `docs/README.md`.

Every prompt in `docs/prompts/` says "read `_COMMON.md` first". This file holds what all phases share, so the phase
files can stay short. If a phase file and this file disagree, the phase file wins for that phase.

## 1. Who you are and where you work

* You are the **orchestrator** of one roadmap phase (or one step of a phase) of NirnCraft, a Skyrim total conversion
  for Minecraft 26.3 NeoForge. Your session runs in the main checkout (the repository root) on the
  local branch `tamriel-overhaul`.
* You do the work through **worktree agents** (Agent tool, `isolation: "worktree"`), at most **3 at once** (prefer
  1-2), each with one bounded task and an explicit write set from the phase file. Small docs-only or data-only steps
  may be done directly in the main checkout.
* Agents in worktrees **never commit, push, merge, rebase, reset or switch branches**; they leave changes uncommitted
  (`docs/AGENT-RULES.md`, Git). You, the orchestrator, merge and commit on `tamriel-overhaul`.
* **Never push.** Never `git push`, never force anything, never touch another worktree's files. The shared stash stack
  is not yours: never a bare `git stash`/`git stash pop`.

## 2. Read first (in this order; stop reading when you have what your step needs)

1. `docs/START-HERE.md` (the map of the project and the current state).
2. `docs/AGENT-RULES.md` (build, code, legal, world-building and fast-loop rules).
3. `docs/world/WORLD-PLAN.md` sections 2, 5 (your phase) and 7 (state table).
4. `docs/world/DESIGN-PRINCIPLES.md`: the first principle and section 0 always; the sections your phase file names.
5. The phase file's own "Context" list.

Pass the same list (trimmed to what the agent needs) into every agent prompt, plus: "Read `docs/AGENT-RULES.md` first;
you work in your own worktree; do not commit."

## 3. The legal line (strict, unchanged)

Facts only: names, relative positions of places, counts, which features a game had, why a design works. Nothing from
any game's files (text, meshes, textures, sounds, music, voice, dialogue, book text, rasters) enters the repo, raw or
converted. Terrain is hand-traced simplified vectors with heights we choose; never sample a game raster
pixel-for-pixel. Every description, invented name and line is our own. Reference data lives only in the gitignored
`.reference/` (`skyrim_ref`, `oblivion_ref`, `morrowind_ref`, `daggerfall_ref`, `arena_ref`, `redguard_ref`). Fan
projects (Tamriel Rebuilt, Beyond Skyrim, Project Tamriel) are never traced and their invented names never reused.
Once `tools/qa/verbatim_check.py` exists (R3c) it must report 0.

## 4. World building first

* Everything builds the world. Every new content record (place, NPC, quest, scene kind, dungeon identity, book,
  creature table, palette or micro-biome rule, music cue, weather rule) carries `why: [sheet field ids]` that resolve
  in `mod/src/main/resources/nirncraft/tamriel/world/<province>.json` (once R3a has built it; before that, cite the
  field ids of the province document's section 1, e.g. `reach.L5.silver_mining`).
* Content reports quote the region's world truth paragraph and answer the four questions (why here, what do people do,
  what were the ruins, why are the dangers on this road) with sheet ids.
* Floors are Skyrim's, measured (DESIGN-PRINCIPLES 4.1-4.9): per region `max(absolute floor, round(median x km²))`,
  per settlement the 4.4 tier medians. ESO ideas are optional and only on top of a met floor.
* Modernise legacy sources with keep / amplify / replace (DESIGN-PRINCIPLES 1). Source precedence: Skyrim, Oblivion and
  Morrowind positions are canonical over Daggerfall and Arena.

## 5. Build and the fast loop (iterate cheaply, gate once)

* Compile after a chunk of work, not after every edit: `py -3 tools/dev/build_263.py --offline compileJava`.
  Finish with `build` passing.
* Iterate on the **dev server**, never a fresh boot per check: `tools\dev\devserver.ps1 run "cmd a|cmd b" -Wait`
  (auto-starts; restarts itself when `mod\src\main` changed), `/nirncraft-dev regen x1 z1 x2 z2` for a bounded area,
  `tools\qa\quick_corridor.ps1 <a>:<b>` (one road tile, ~15 s; `-Replan` after road planning changes). Once R2 lands:
  `devserver.ps1 hotswap` for method-body changes and `build_263.py --offline test` for plan-layer tests.
* Only ONE dev server per machine; `devserver.ps1 stop` when you finish.
* Cheapest check first: static lint (`harness.py run --tier check`, `data_checks.py`, the domain lint) → layout lint → dev server
  → offline renders + Haiku review → full gate. **Never launch the Minecraft client.** The owner runs visual and Prism
  checks himself; hand him a checklist instead.
* Visual checks: render offline (`tools/qa/render_town.py`, `render_dungeon.py`, `region_render.py`, map previews),
  tile into one contact sheet (`contact_sheet.py`), and send it to an Agent with `model: "haiku"` and a rubric that
  returns only a numbered issue list. Fix flagged issues; do not escalate the review itself.
* Do not read whole logs or whole JSON files; read reports, `git diff --stat` and one contact sheet.
* After any change to the map, places, road graph, gates or worldplan code the bake is stale: `tools\bake\bake.ps1`
  re-bakes and verifies it in a fresh world. Road phases also run `tools\qa\full_corridors.ps1` once at the end.

## 6. Gates

* Every phase file lists measurable gates. A phase is done only when every gate passes, measured by the named tool,
  with the number written in the report and in the WORLD-PLAN state table.
* Never weaken a gate to pass it. A gate only the slow path can run is run once, not skipped. If a gate is wrong,
  stop, explain, and propose a change to the owner; do not edit it yourself.
* If a gate's tool does not exist yet (because its R3 part has not landed), record "not measurable yet (needs R3x)"
  and do not claim the gate.

## 7. Merge procedure (orchestrator, main checkout)

For each finished worktree, one at a time:

1. Read the agent's report and `git -C <worktree> status --short` / `git -C <worktree> diff --stat`. Reject edits
   outside the write set unless the report justifies them.
2. `bash tools/dev/merge_worktree.sh <worktree path> <NAME>` (stages the worktree, writes a patch, `git apply --3way`
   onto the main checkout; prints the next commands).
3. Conflicts: regenerate generated files, never hand-merge them (`pack/index.toml` and `pack/pack.toml`: take ours,
   then `py -3 -I tools/refresh_packwiz.py`; biomes: `py -3 -I tools/worldgen/biomes.py` then
   `py -3 -I tools/worldgen/noise_settings.py`; quest text: `node tools/quest_lang.js`). Index files
   (`places/index.json`, `quests/index.json`, `lore/places/index.json`, tag lists): union both sides.
   `tools/dev/resolve_generated.py` helps.
4. `py -3 tools/harness/harness.py gate` (and `tools\bake\bake.ps1` if the bake is stale; must pass). Add the phase's
   own gate commands (and `tools\qa\full_corridors.ps1` if road or worldplan code changed).
5. Commit on `tamriel-overhaul` with a message in the style of `git log --oneline` (what changed, the key numbers),
   ending with the attribution line the harness gives you. Commit only the paths of this merge (`git add <paths>`, no
   `git add -A` in the main checkout: other sessions may have files there).
6. Update `docs/world/WORLD-PLAN.md` section 7 (status, commit, gate numbers, date) and section 8 (new open items),
   and the "Next" line of `docs/START-HERE.md`. Commit that with the merge or right after it.

## 8. Report format (your last message to the owner; at most 40 lines)

```
PHASE <id> <step> — DONE | PARTIAL | BLOCKED
Gates:   <metric>  <floor>  <value>  PASS|FAIL      (one line each)
Merged:  <commit> <one-line summary>; files: <n> (diff --stat tail)
World:   <region> truth quoted once; four questions answered in <file> (content phases only)
Source:  what the source did vs what we do (province phases only, one line per DESIGN-PRINCIPLES 1.2 row or a file link)
Outside write set: <files and why, or none>
Open items added: <O-ids>
Next:    <the next phase/step and its prompt file>
Owner checklist: <only if something needs eyes in game>
```

## 9. Resuming

If you were interrupted: read WORLD-PLAN section 7 and `git worktree list`; for this phase's worktrees run
`git -C <path> status --short` and `diff --stat`. In the same session, continue an agent with SendMessage ("continue
where you left off; finish and report"). In a new session, start a fresh agent **in that worktree path** with this
phase prompt plus: "Uncommitted work from a previous attempt is already in this worktree; review `git status` and
`git diff --stat` first and finish it rather than starting over." `merge_worktree.sh` applies with `--3way`, so an old
base usually merges; if it does not, update the worktree's base with a tagged stash: `git stash push -u -m <tag>`,
note the SHA from `git stash list --format='%H %gs'`, `git merge --ff-only tamriel-overhaul`, `git stash apply <sha>`,
then drop that entry (found again by its tag).
