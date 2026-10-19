# VT1 Fast verification: L0 lint, L1 headless export, L2 sweeps, L4 sheets

Read `docs/prompts/_COMMON.md`, `docs/world/VISUAL-TARGETS-PLAN.md` (sections 3, 5, 6) and the workflow first. The
goal is that almost every trait is proven in seconds without Minecraft, and the client is used only for light, colour
and mood.

## Tasks (board ids `VT1.00.*`)

1. **Target schema and lint** (`VT1.00.schema`): JSON schema for `targets.json` (traits, axes, probes, rungs,
   weights, `must`, `resolution`, `binding`, `derived`, `why`); `tools/visual/target_lint.py` checks ids resolve
   (regions, places, cityplans, dungeons, cavern profiles), `why` resolve, probe names exist, every disagreement has a
   resolution. Wire into `world_test.py --tier fast`.
2. **SiteExport** (`VT1.00.export`): `com.nirncraft.dev.SiteExport` run by `tools/qa/site_export.gradle` (JavaExec like
   `GeographyAudit`, no Minecraft world): for a list of sites (place, dungeon, cave, or an x/z window) it evaluates the
   real height function, map water, the street plan (planned live: no bake needed), `SettlementPiece` builders,
   landmarks, scatter and dungeon/cave builders into an in-memory block canvas (reuse the canvas `DungeonQa` paints
   into), then writes `.work/cache/<digest>/<site>.npz` (block ids, heights, water, door and entrance list, placed
   enemies and loot, light sources) plus a block palette. One warm JVM serves many sites. Gate: Whiterun, Riverwood and
   Markarth export in < 20 s each.
3. **Renders and probes** (`VT1.00.probes`): `tools/visual/site_render.py` (iso, plan, cross-sections, skyline
   silhouette; reuse `render_dungeon.py`/`render_town.py` drawing) and `site_probes.py` implementing the probe
   vocabulary: tiers and tier links, gate-to-door and door-to-door flood fills, exits and loops, skyline highest
   building, pad flat share, river through and waterfall drop, water continuity, cave air ownership (no unowned cave
   air), counts (services, set pieces, vistas, placed enemies, loot, story props, lore links), palette and material
   shares. Output: compact probe JSON per target. Gate: the L1 flood fill matches `tools/qa/town_audit/bfs_truth.py`
   on the saved Whiterun world within 2%.
4. **Sweeps** (`VT1.00.sweep`): `tools/visual/sweep.py --province <id>` runs SiteExport plus probes over every place
   of a province incrementally (digest cache), plus region censuses (biomes, flora, cave volume, water continuity),
   G1 renders and neighbour-difference metrics. Gate: Skyrim sweep in under 15 minutes cold, under 1 minute warm.
5. **L3 parity sampler** (`VT1.00.parity`): via the broker's dev-server job, regenerate 3 windows and compare block-type
   histograms with SiteExport (feature placement is not bitwise deterministic). Gate: histogram distance under the
   documented threshold.
6. **Local camera fingerprints** (`VT1.00.cameras`): `visual_cameras.py` fingerprints a camera by the generation
   inputs of its window (place data, stamps, region data, class hashes of the builders involved), not the global hash.
7. **Sheets and scoring** (`VT1.00.sheets`): `tools/visual/sheet.py` (plate, shaders-on, shaders-off side by side, with
   advisory metrics: palette and fog colour distance in Lab, luminance histogram, sky share, vegetation share,
   silhouette edge overlap) and `score.py` (vision model scores the sheet against the target's traits with a fixed JSON
   answer; results into `STATUS.json`); `world_test.py coverage` gains the target dimension.
8. **Gaps, derive, bible** (`VT1.00.gaps`): `gaps.py` (classify failing probes into the plan's gap classes),
   `derive.py` (derived targets for every place without a plate), `bible.py` (draft culture design bible from plates,
   world sheets and current kits for the target author to edit).

## Gates

All `tools/visual` unit tests green; the three pilot exports; the flood-fill parity; a full Skyrim L2 sweep with a
report; one L4 sheet produced through the broker for Whiterun.
