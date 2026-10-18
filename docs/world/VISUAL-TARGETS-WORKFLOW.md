# Visual Targets: the operating workflow

How any agent or AI system picks up Visual Targets work, does it fast, saves progress and hands it on. The *what* is
in [`VISUAL-TARGETS-PLAN.md`](VISUAL-TARGETS-PLAN.md); phase prompts are `docs/prompts/VT*.md`. Tools named here are
built in VT0/VT1; until a tool exists, follow the manual fallback given next to it.

## 1. Start of every session (2 minutes, few tokens)

1. Read this file, then `docs/START-HERE.md` rules, then the phase prompt for the task you take.
2. `py -3 tools/visual/board.py status` (fallback: read `docs/world/targets/BOARD.md`). It prints open tasks, active
   claims, leases and the queue. Do not read code to find out "where things are": the board and the task notes say it.
3. Take one task: `board.py next --role <role>` then `board.py claim <task>`. Never work on an unclaimed task.
4. Read the task's notes file (`docs/world/targets/<vol>/notes/<task>.md`) and resume from its last "Next" line.

## 2. Where everything lives

| What | Path | In git |
| --- | --- | --- |
| Atlases (owner's art) | `art/atlases/<NN>_<name>.pdf`, `art/atlases/volumes.json` | yes (LFS) |
| Plates and index | `art/plates/<NN>/plate_<pp>.webp`, `thumb_<pp>.webp`, `index.json` | yes (LFS for images) |
| Targets | `docs/world/targets/<NN>-<name>/targets.json` (plate and derived targets) | yes |
| Culture design bible | `docs/world/targets/<NN>-<name>/bible.md` | yes |
| Plan notes (how a plate was read) | `docs/world/targets/<NN>-<name>/plans/<target>.md` | yes |
| Gap report | `docs/world/targets/<NN>-<name>/gaps.json` + `gaps.md` | yes |
| Progress per target and trait | `docs/world/targets/<NN>-<name>/STATUS.json` (written only by tools) | yes |
| Task notes (progress log, handoff) | `docs/world/targets/<NN>-<name>/notes/<task>.md` | yes |
| Board (task list and dependencies) | `docs/world/targets/BOARD.json` (+ generated `BOARD.md`) | yes |
| Claims, leases, queue, results, caches | `.work/` in the MAIN checkout (`F:\Projects\Personal\Skycraft\Skycraft\.work`) | no (gitignored) |
| L1/L2 outputs (exports, renders, probe JSON) | `.work/cache/<digest>/` | no |
| L4 captures and sheets | `.work/results/<job>/` (promoted reviews go to `dist/world-tests/` as today) | no |

Agents in worktrees use the absolute `.work` path of the main checkout, so every agent and every AI system on this
machine sees the same claims, leases and queue.

## 3. Tasks, claims and resources

**Task ids**: `VT<phase>.<vol>.<kind>.<id>`, for example `VT3.01.city.whiterun`, `VT2.00.knob.stamps`,
`VT3.01.cave.bleak_falls_sidecave`. Volume `00` is cross-volume work (knobs, tools).

**States**: `todo` → `claimed` → `in_progress` → `ready_to_merge` → `merged` → `needs_capture` (waiting for an L4
batch) → `done`; or `blocked` (with the blocking task id). Dependencies are listed per task (`needs: [task ids]`); a
task is offered by `next` only when its dependencies are `merged` or better.

**Claims** (`.work/claims/<task>.json`: `{task, owner, system, worktree, started, heartbeat, expires}`):

* `board.py claim <task>` creates it (atomic create; fails if it exists and is not expired).
* `board.py heartbeat <task>` at least every 20 minutes of work; a claim without a heartbeat for 60 minutes expires
  and may be taken over (`board.py takeover <task>` records the takeover in the notes).
* `board.py release <task>` when stopping without finishing (notes must say where you stopped).

**Serial resources** (only one holder at a time, claimed like tasks: `board.py claim res:<name>`):

| Resource | Why serial |
| --- | --- |
| `res:map` (`tools/worldgen/tamriel_vectors.json`, `tamriel_map.py`, rasters, hydrology) | one generated raster set |
| `res:biomes` (`tools/worldgen/biomes.py`, `palettes.json`, biome JSONs) | one generated catalogue |
| `res:noise` (`tools/worldgen/noise_settings.py`) | one density/cave graph |
| `res:bake` (anything in `Bake.CLASSES` or the bake inputs; running a bake) | one baked plan set |
| `res:shader` (`tools/shaders/*` patch) | one patched pack |
| `res:lang:<module>` | one lang file per module |

Everything else (cityplans, stamps of one place, cave plans, kits in new files, notes) is free to edit in parallel
under the task's claim, inside the write set the task declares.

## 4. Git and saving progress

* Work in your own worktree (`isolation: worktree` for Claude agents; `git worktree add` for other systems) on a
  branch named after the task. **WIP commits on your own worktree branch are allowed and encouraged** (they make work
  survive crashes and usage limits); never commit to `tamriel-overhaul` from a worktree.
* Never `git reset --hard`, `git checkout -- .` or a bare `git stash` with uncommitted work: commit WIP first.
* The merge helper diffs from the merge base with `tamriel-overhaul`, so WIP commits merge cleanly
  (`tools/dev/merge_worktree.sh` is updated for this in VT0).
* **Save progress after every meaningful step** (a tool works, a plan is drafted, a gap is fixed): WIP commit,
  `board.py note <task> "<one line>"` (appends to the notes file), heartbeat. The notes file always ends with
  `Next: <the very next step>` so anyone can resume.
* Finish: `board.py ready <task> --summary "..." --files "..." --verify "<rung: numbers>"`. The merging session
  (whoever holds `res:merge`, normally the orchestrator) merges, runs the gates for that change class (section 6),
  commits, and sets `merged`.

## 5. The fast loop: what to run for each kind of change

Full builds, bakes and client installs are rare events, not the loop.

| You changed | Run | Time |
| --- | --- | --- |
| targets, plan notes, bible, board | `target_lint.py` (L0) | seconds |
| reloadable data (cityplans, places, scenes, quests, palettes) | L0, then L1 `SiteExport` on the affected sites; on a running dev server `/skycraft-dev reload <group>`; on the live client `live_world.py reload <group>` | seconds |
| generated data (`biomes.py`, `caverns.py`, `looks.py`, painters) | the generator, L0, L1 on pilot sites; `reload palettes` where reloadable | seconds to 1 min |
| generator Java (planner, builders, height, hydrology, caves) | `gradlew classes` (incremental), then L1 `SiteExport` (warm JVM, plans live: no bake needed); dev server: `devserver.ps1 hotswap` (~2 s) | seconds |
| map vectors and rasters (`res:map`) | `tamriel_map.py`, L0, L1 on affected sites, L2 on the province | 1-5 min |
| textures, models, kits | painter, `asset_audit.py` (static), L1 renders use block colours; client sees them at the next L4 batch | seconds |
| shader patch (`res:shader`) | patcher, then queue an L4 job (`live_world.py shader` hot reload inside the batch) | queued |
| anything in the bake (`res:bake`) | L1 first (live planning); bake only before an L3/L4 batch or a merge of bake inputs | minutes, rare |
| ready to show the owner | queue an L4 capture job (section 7) | batched |

L1 output is cached by input digest (`.work/cache/`), so unchanged sites are free; L2 sweeps are incremental.

## 6. Gates per change class (run by the merging session)

| Change class | Gate before commit |
| --- | --- |
| docs, targets, notes | `target_lint.py` |
| data, kits, looks | L0 + `data_checks.py` + `asset_audit.py` + L1 on the touched targets |
| generator code | `qa.ps1 -Unit` + L1 on pilot sites + L2 on the touched province |
| bake inputs | the above + `qa.ps1 -Server -Rebake` (under `res:bake`) |
| a finished volume | L2 on all its targets, L4 batch, owner sign-off |

Gates are recorded in the task's `ready` line with numbers.

## 7. The live client and the dev server (one each, shared by every AI system)

* **Never start the live client, the dev server, a bake or `qa.ps1 -Server` directly.** Use the broker:
  * `broker.py submit capture --targets vt.skyrim.whiterun.city ... --shaders both` (L4)
  * `broker.py submit devserver --cmds file.cmd` (L3 windows, saved-world audits)
  * `broker.py submit bake` / `broker.py submit qa-server`
  * `broker.py wait <job>` or poll `broker.py status <job>`; results land in `.work/results/<job>/`.
* The broker holds `.work/lease/live.json` and `.work/lease/server.json` (owner, system, job, heartbeat). If another
  AI system or agent holds a lease, jobs wait in `.work/queue/`; the broker drains the queue in batches (one client
  start per build, cameras sorted by area, shaders on then off).
* **Other AI systems on this machine** follow the same protocol: lease files plus queue. If a lease is held and its
  heartbeat is fresh, wait your turn. A lease with no heartbeat for 10 minutes is stale: `broker.py recover` records it
  and releases it; never kill another system's process.
* Fallback before the broker exists: check `.work/lease/*.json` by hand, write your own lease file with a heartbeat
  timestamp, delete it when done; if a lease is held, do L0-L2 work meanwhile.

## 8. Roles

| Role | Does | Typical tasks |
| --- | --- | --- |
| Ingestor | runs the atlas ingest, fixes plate ids | `VT3.<vol>.ingest` |
| Target author | writes plate and derived targets, the culture bible, resolutions (plate vs world) | `VT3.<vol>.targets`, `.bible`, `.derive` |
| Planner | designs site plans (cityplans, dungeon and cave plans, stamps, water) from plates and our building types | `VT3.<vol>.city.<id>`, `.cave.<id>`, `.dungeon.<id>` |
| Knob builder | builds the general generator features | `VT2.00.knob.<name>` |
| Content | lore, books, quests, NPCs, rumours that tie caves and places into the region | `VT3.<vol>.lore.<id>` |
| Verifier | runs L2 sweeps, triages failures into tasks | `VT3.<vol>.sweep` |
| Capture runner | operates the broker batches, assembles review sheets | `VT3.<vol>.capture` |

One session may take several roles, one claimed task at a time per agent.

## 9. Token discipline

* Read the board, the task notes and compact JSON results; never read whole logs (use `log_summary.py` or the probe
  JSON's failure list).
* Probe outputs are numbers and short reasons; renders are looked at only when a probe fails or for the final sheet.
* L4 scoring uses one side-by-side sheet per target and a fixed rubric answer format; a cheap vision model scores,
  the owner signs off.
* Notes are short: what was done, numbers, the next step. No narrative.
* Resume from notes and STATUS, not by re-exploring the code.

## 10. Definition of done

* **Target**: all `must` traits pass at their rung, the weighted score >= `min_score`, every disagreement has a
  recorded resolution, L4 sheet scored.
* **Volume**: every target `done`, derived targets for every place in the volume `met` at L1/L2, G1 >= 80%, the
  owner signs off the volume sheet; WORLD-PLAN state table and START-HERE updated.
