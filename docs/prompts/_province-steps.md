# Province steps V1-V9 (shared by every province phase)

The province phase files (`R5` to `R12`) name their province, its document, its specifics and any extra gates, then
say "run step Vn as defined in `_province-steps.md`". This file defines each step: goal, inputs, write set, how to
verify while working, and the gates. Read `_COMMON.md` first.

Placeholders: `<P>` = province id (`cyrodiil`), `<doc>` = `docs/world/provinces/<P>.md`, `<regions>` = the province's
region ids. Resource paths are under `mod/src/main/resources/skycraft/` unless they start with `tools/` or `docs/`.

**Global serial rule.** V2, V3 and V5 of any province (and R3b, R13) never run at the same time as each other: they
share `tools/worldgen/tamriel_vectors.json`, the rasters, `biomes.py`/`palettes.json` and the bake. Check WORLD-PLAN
section 7 for an `in progress` V2/V3/V5 before starting one.

**Order inside a province.** V1 → V2 → V3 → V4 → V5 → (V6, V7, V8 in parallel, three agents) → V9. V4 may start once
V2's vectors are accepted. V6-V8 need R3a-R3e merged.

---

## V1 World model sheet

* **Goal.** `<doc>` section 1 becomes `tamriel/world/<P>.json`: the province block and one block per region, each with
  its world truth paragraph (80-120 words) and the eleven layers in causal order; every L2-L11 field cites a field
  above it; the `forbidden` pairs listed.
* **Inputs.** `<doc>` section 1, DESIGN-PRINCIPLES first principle, `tools/world/sheet_from_md.py` (R3a).
* **Owns.** `tamriel/world/<P>.json`; fixes to `<doc>` section 1 when the extraction exposes a missing cite (doc first,
  then regenerate).
* **Verify.** `py -3 -I tools/world/sheet_from_md.py <doc> --out tamriel/world/<P>.json`,
  `py -3 -I tools/qa/worldsheet_lint.py --province <P>`.
* **Gates.** `worldsheet_lint.py` 0 errors for the province and every region; every region has all 11 layers and a
  truth paragraph; every L10 dungeon-inhabitant field cites L1, L4 or L9; Haiku read of the truth paragraphs: "does
  each region read as its own place?" with no blocking issue.
* **Size.** S.

## V2 Geography (globally serial)

* **Goal.** `<doc>` section 3 on the map: coast, islands, ranges with heights we choose, `areas`, rivers and lakes in
  `tools/worldgen/tamriel_vectors.json`; scale and anchor moves under the source precedence rule (Skyrim, Oblivion and
  Morrowind positions are canonical); relief to the SCALE-AND-GEOGRAPHY section 7 targets; every region's signature
  landform and at least one XL silhouette; hydrology rerun; rasters regenerated; `docs/TAMRIEL.md` section 9 anchors
  updated in the same commit.
* **Inputs.** `<doc>` section 3, `docs/world/SCALE-AND-GEOGRAPHY.md` sections 3, 4, 5.x, 7, 8; reference data in
  `.reference/` read at authoring time only (tracing helpers write vectors, never rasters).
* **Owns.** The `<P>` part of `tamriel_vectors.json`, tracing helpers under `tools/worldgen/` (new files),
  regenerated `tamriel/*.png` and `hydrology.json`, moved anchors in `places/*.json` and `places/provinces.json`,
  `docs/TAMRIEL.md` section 9.
* **Verify.** `py -3 -I tools/worldgen/tamriel_map.py` (previews), `py -3 -I tools/worldgen/hydrology_report.py`,
  `qa.ps1 -Layout`, `/skycraft-dev regen` of 2-3 sample areas, `region_render.py` contact sheet to Haiku.
* **Gates.** `layout_lint.py` passes; relief per region p50, p90 and ruggedness within 10% of target (or above);
  `hydrology_report.py`: every lake has an outlet, named rivers reach the sea; every region >= 1 XL landform
  (F-WEENIE >= 70% once `flow_audit.py` exists); no file under `mod/` or `tools/` holds raster data from a game (size
  and grep check); `roadplan_lint.py` 0 errors after the rebake; corridors of neighbouring provinces still all zeros.
* **Size.** M.

## V3 Regions and micro-biomes (globally serial)

* **Goal.** `<doc>` section 2 on the map: region polygons and new region ids (ids are never renamed), palettes per
  region covering ocean, beach and river, new biomes through `tools/worldgen/biomes.py` (generated, never hand-edited),
  the MS21 `micro` rules per region with their sheet cites, the L2 weather table, a region music cue, level bands.
* **Owns.** `tamriel/regions.json` (via `tamriel_map.py`), the `<P>` entries of `palettes.json` and `biomes.py`,
  generated `data/skycraft/worldgen/**`, `lang/skycraft/tamriel_regions.json` (own keys), `atmosphere` weather data for
  `<regions>`.
* **Verify.** `py -3 -I tools/worldgen/biomes.py`, `py -3 -I tools/worldgen/noise_settings.py`,
  `py -3 -I tools/qa/region_audit.py --province <P>`, `/skybiome census` on the dev server per region.
* **Gates.** F-MICRO per region >= 6 micro-biomes, >= 2 per km², >= 3 transitions per km of road; no rule on < 0.5% or
  > 60% of a region's land; every rule cites a sheet field; each region's signature biome is the top lowland pick in
  its census; parity FLO >= 80 for every region; G1 palette distinct from every neighbour (region_render sheet, Haiku).
* **Size.** M.

## V4 Places

* **Goal.** `<doc>` sections 5 and 6 as the place list: settlements per tier and dungeons per class at the 4.3 and 4.5
  densities (`max(abs, median x km²)`), unmarked sites (`hidden: true`, 0.57/km²), MS7 `class` on every dungeon,
  `why` on every new or changed place; legacy places reconciled (renamed, upgraded, moved or removed, never silently
  dropped); marquee and lore-web places kept.
* **Owns.** `tamriel/places/<P>*.json`, `places/index.json` (append), `lang/skycraft/places*.json` (own keys), any
  curation tool `tools/worldgen/<P>_places.py` (new).
* **Verify.** `qa.ps1 -Layout`, `node tools/marquee/validate.js`, `node tools/marquee/loreweb_check.js`,
  `py -3 -I tools/qa/parity.py --province <P>`, `flow_audit.py --province <P>`.
* **Gates.** Per region: settlements, dungeons by class, unmarked, power sites, shrines, camps and watchtowers at or
  above their floors (`parity.py --province <P>` and `flow_audit.py`); exactly one seat per hold-sized half; spacing
  rules hold; 100% of places on land (or at their water anchor); `validate.js` 231 ids; no lore-web place dropped;
  `why` on 100% of new places (`worldsheet_lint` back-fill counter for legacy).
* **Size.** M.

## V5 Roads and travel (globally serial)

* **Goal.** `<doc>` section 4: named roads in `roads/named_roads.json` (highways first, real intermediate stops in the
  route, cross-province routes ending at a border fort or inn), gate lanes and trails to every place, the province's
  travel network content in the MS11 format (`travel/<P>.json`, `tamriel/ports.json`), wilderness fast-travel nodes;
  rebake `roadplan` and `sceneplan`.
* **Owns.** The `<P>` entries of `roads/named_roads.json`, `travel/<P>.json`, `tamriel/ports.json` (own routes), baked
  files (regenerated), one or two new corridors in `tools/qa/full_corridors.ps1`.
* **Verify.** `tools/bake/bake.ps1` or `qa.ps1 -Server -Rebake`, `py -3 -I tools/qa/roadplan_lint.py`,
  `py -3 -I tools/qa/reach_lint.py`, `quick_corridor.ps1 <a>:<b>` while iterating, `placement_lint.py`.
* **Gates.** `roadplan_lint.py` 0 errors and 0 failed links; `reach_lint.py` 100% of the province's places reached;
  the new corridors and the 4 existing ones in `full_corridors.ps1`: 0 gaps, 0 drops > 1 without a step, 0 unbridged,
  0 tree-blocked; F-TRAVEL p90 <= 500 and max <= 800 blocks, >= 2 travel modes beyond walking and horse; F-SIGN >= 95%;
  waysides per km inside the `placement_lint` bands; a named stop every 600-1,000 blocks of highway; adjacent major
  settlements <= ~3,000 road blocks apart or linked by paid travel.
* **Size.** M.

## V6 Settlements

* **Goal.** `<doc>` sections 5 and 8: every capital and city a unique city plan (`cityplans/<id>.json`, unique `sil`)
  with districts and a class gradient from L7, a palace or seat weenie, the industry from L5; towns their own sub-kit
  or plan; populations at the 4.4 tier counts (named NPCs, interiors, named buildings, roles, merchants, trainers,
  inns, court); regional products in the shops; every job with a bed and a workplace; people with problems and
  secrets. PARITY batches PA4-PA9, PA16, PA17 for this province fold in here.
* **Owns.** `tamriel/cityplans/<P ids>.json` and `index.json` (append), `tamriel/styles/<culture>.json` and
  `styles/overlays/<region>.json` for `<P>`, `society` data and lang for `<P>`, `quests/town_<P>_*.json`,
  `lore/places/town_<P>.json`.
* **Verify.** `node tools/marquee/validate_cityplans.js`, `/skysettle plan <id>` and `/skypop <id>` on the dev server,
  `py -3 -I tools/qa/population_lint.py`, `town_checks.py`, `render_town.py` contact sheet to Haiku ("not a reskin",
  "streets furnished", "gates meet roads").
* **Gates.** `parity.py --province <P> --tiers`: every settlement at or above its tier's absolute floor and the
  province median at or above the tier median; F-COURT 100%; 0 unstaffed workplaces; `town_checks.py` 0 unreachable
  doors above 1%, 0 props on roads; unique `sil` per capital and city; `y_looks` >= 3 per region; Haiku review with no
  "reskin" finding.
* **Size.** L.

## V7 Dungeons, underground and landmarks

* **Goal.** `<doc>` section 6: every dungeon an identity from L10 (hook, named boss or reason, note, motif, set piece),
  classes with their length bands, >= 1 hand-authored marquee dungeon per region (plan JSON), a signature inhabitant
  theme per region, >= 8 rosters and >= 5 carved palettes per region, culture room kits, loops with a shortcut home,
  XL landmarks placed for sightlines, power sites, key relics. PARITY batches PA3 (identity), PA11-PA13, PA14-PA15
  (creatures) and PA18 for this province fold in here.
* **Owns.** Dungeon variant fields in `places/<P>*.json`, `dungeons/marquee/<P>_*`, `dungeons/looks/<P>*`,
  `lore/places/gen_<P>.json`, `region_spawns/<P>.json`, `explore/uniques_<P>.json`, `landmarks/kinds.json` (`<P>`
  entries), Java room kits only when the phase file says so (one agent owns `dungeons/world/*Rooms.java` at a time).
* **Verify.** `py -3 -I tools/qa/dungeon_identity_lint.py --province <P>`, `dungeon_stats.py`, `dungeon_audit.py`,
  `/skycraft-dungeon-qa place <id>` (analyzer), `render_dungeon.py` cutaways to Haiku.
* **Gates.** `d_identity` = 1.0; F-LOOP >= 90% of major, 100% of marquee; analyzer 0 blockers on one dungeon per
  variant used; >= 1 marquee dungeon plan per region validates; >= 10 inhabitant themes per region (abs. 8) with one
  signature; `d_caverns` >= 5, `d_rosters` >= 8; F-WEENIE >= 70%; parity DUN, CRE, LND >= 80 for every region.
* **Size.** L.

## V8 Quests and world texture

* **Goal.** `<doc>` sections 7 and 8: the province story (>= 19 quests across most regions), a hold conflict chain of
  3+ per region with consequences, faction hubs (2-5, in different cities, ranks and 6-14 quests), the exclusive choice
  where lore has one, side quests, Daedric quests from L8, favours, radiant and board offers aimed at real places,
  hidden `find` quests, the lore-web arc, >= 8 encounter kinds per region (>= 3 province-specific), >= 3 wonder
  moments, the region voice (about, rumour, work, weather; rumours, legends, songs), >= 12 deed-keyed reactive lines
  per region, readables and collectibles at the 4.3 densities, the province festival. PARITY PA3 (notes), PA10, PA21,
  PA22 for this province fold in here.
* **Owns.** `quests/<P>_*.json`, `quests/story/<region>.json`, `quests/index.json` (append),
  `lore/places/regions_<P>.json`, `lore/books/<region>.json`, `lang/skycraft/society_<P>.json`, the `<P>` entries of
  `creatures/scenes.json`, `calendar/festivals.json` and `society/region_voice.json`, `tags/items/gear/<P>.json`.
* **Verify.** `node tools/quest_lang.js`, `py -3 -I tools/qa/story_lint.py --province <P>`,
  `/skycraft-quest selftest flow` on the dev server, `novelty_lint.py --strict`, `verbatim_check.py`.
* **Gates.** Quests per region at the 4.8 floors (all kinds 14.0/km², abs. 2.6/km² and >= 8); F-STORY (>= 1 chain of 3+
  per region); F-HOOK <= 800 road blocks; F-STARTS >= 4; F-QUEST-RADIUS median 150-900 and >= 70% in-region;
  `q_placed` >= 0.9; >= 1 consequence per side chain; F-ENC >= 8 (>= 3 specific); F-WONDER >= 3; F-REACT >= 12;
  F-COLLECT at 4.3 medians; F-ORPHAN 0 for major and marquee; `novelty_lint --strict` 0 pairs; `verbatim_check` 0;
  `selftest flow` all quests complete; parity QST, NPC, LOR, MUS, ITM >= 80 for every region.
* **Size.** L.

## V9 Province gate

* **Goal.** Prove DESIGN-PRINCIPLES 5.5 for every region of `<P>` and write the province report.
* **Do.** Run every gate tool once over the province: `worldsheet_lint`, `parity.py --province <P> --assert-floor
  SET,NPC,QST,DUN,CRE,FLO,STY,LND,LOR,MUS,ITM`, `flow_audit.py --province <P>`, `novelty_lint --strict`,
  `verbatim_check`, `reach_lint`, `qa.ps1 -Server -Rebake`, `full_corridors.ps1`. Produce one contact sheet per region
  (towns, dungeons, landmarks) and run two Haiku reviews: the coherence question ("does everything here belong to
  this place and nowhere else?") and the G1 blind test (name the region from 3 random renders; >= 80%).
* **Write.** `docs/world/provinces/<P>.md` section 10 "Results" (the gate table with numbers), the modernisation
  "source did vs we do" table (section 9), the four questions per settlement and named site (or a link to a generated
  file under `docs/world/reports/<P>.md`), the owner's in-game checklist, WORLD-PLAN state table and open items.
* **Gates.** All of the above pass for every region; the 1.3 modernisation checklist ticked; G1-G12 checks pass.
* **Size.** S-M.
