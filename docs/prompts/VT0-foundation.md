# VT0 Foundation: art in the repo, board, broker, ingest

Read `docs/prompts/_COMMON.md`, `docs/world/VISUAL-TARGETS-PLAN.md` (sections 0-3, 5.1, 10) and
`docs/world/VISUAL-TARGETS-WORKFLOW.md` first. This phase builds the rails every later task runs on; no world
generation changes.

## Tasks (board ids `VT0.00.*`)

1. **Doc drift** (`VT0.00.docs`): START-HERE current state; `CLAUDE.md` (missing `HANDOFF.md`); TAMRIEL/WORLD/
   WORLDGEN docs vs code (raster 880x672 at 32 blocks, carvers off and noise caves on, ~131 overworld biomes, Throat
   of the World height, micro-rule counts); mark T8's unwired router as such. Write set: `docs/**`, `CLAUDE.md`.
2. **Art and LFS** (`VT0.00.art`): `.gitattributes` LFS rules for `art/**/*.pdf`, `*.webp`, `*.png`; `art/LICENSE.md`
   (owner's terms for the art, separate from the code licence); `art/atlases/volumes.json` (volume number to
   province/region ids; 01 Skyrim ... 12 miscellaneous areas). Write set: `art/**`, `.gitattributes`.
3. **Ingest** (`VT0.00.ingest`): `tools/visual/ingest_atlas.py` (PyMuPDF): plates as 1600 px WebP plus 256 px
   thumbnails, `index.json` per volume with kind, title, caption, region and place hints resolved against
   `regions.json` and `places/*.json`, 6-8 k-means swatches. Idempotent (skips unchanged pages by hash). Unit tests.
   Write set: `tools/visual/**`, `art/plates/**`.
4. **Board** (`VT0.00.board`): `tools/visual/board.py` with `status`, `next --role`, `claim`, `heartbeat`,
   `takeover`, `release`, `note`, `ready`, `merged`, `block`; `BOARD.json` schema with tasks, dependencies, roles,
   write sets, resources; generated `BOARD.md`; atomic claim files in `.work/claims/`; `.work/` in `.gitignore`.
   Seed the board with VT1/VT2 tasks and the Volume 01 pass tasks. Unit tests (claim races, expiry, takeover).
5. **Broker** (`VT0.00.broker`): `tools/visual/broker.py` with `submit capture|devserver|bake|qa-server`, `status`,
   `wait`, `run` (drains the queue), `recover`; leases `.work/lease/live.json` and `server.json` with heartbeats; wraps
   `tools/dev/live_world.py`, `preview_world.py`, `devserver.ps1`, `bake.ps1`, `qa.ps1`; batching rules from the plan
   5.1; works for agents of any AI system (plain files, no Claude-specific state). Unit tests with a fake runner.
6. **Merge helper** (`VT0.00.merge`): `tools/dev/merge_worktree.sh` diffs from `git merge-base` with
   `tamriel-overhaul` (so worktree WIP commits merge) and excludes `.work/`; `AGENT-RULES.md` and `_COMMON.md` allow
   WIP commits on worktree branches (never on `tamriel-overhaul`).
7. **Housekeeping decisions** (`VT0.00.cleanup`): delete the unread second micro-rule block in `palettes.json` (or make
   it the only one, via `biomes.py`, under `res:biomes`); decide `TamrielCaves`/`TerrainDensity` (wire as the single
   cave router in VT2.caves, or delete now) and record it.

## Gates

`py -3 -m pytest tools/visual` green; `board.py status` lists the seeded tasks; a dry-run broker batch with a fake
runner; one real ingest of every atlas present (`art/plates/<NN>/index.json` for each); `data_checks.py` 0 errors.
