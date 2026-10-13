# R3a World-model sheets (MS22)

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R3a-world-sheets.md`." One agent.

## Goal

The causal world model sheet becomes data that generators read and a lint enforces. This is the spine of "world
building is king": after R3a, every content record can say **why** it exists, and the lint can prove it.

## Context to read

* `docs/world/DESIGN-PRINCIPLES.md`: the first principle (sheet, layers, worked Reach example, coherence check), 4.10,
  4.11 MS22, 5.4 F-WHY.
* `docs/world/provinces/skyrim.md` section 1 (the pilot sheet; if it does not exist yet, R3a is blocked: report it).
* `docs/world/provinces/*.md` section 1 of one other province, to make sure the format fits more than Skyrim.

## Write set

`mod/src/main/resources/skycraft/tamriel/world/**` (new), `mod/src/main/java/com/skycraft/tamriel/world/**` (new
package), `tools/world/**` (new), `tools/qa/worldsheet_lint.py` (new), `tools/qa/qa.ps1` (wire the lint into the
static checks), `mod/src/test/**/tamriel/world/**` (if R2 has landed), additive `why` readers in the consumers named
below, `docs/world/DESIGN-PRINCIPLES.md` 4.11 MS22 (a "Format as built" paragraph only).

## Steps

1. **Schema.** `tamriel/world/<province>.json`:
   ```
   { "province": "skyrim", "truth": "<80-120 words>",
     "layers": { "L1": [ {"id": "silver_veins", "text": "...", "cites": []} ], ..., "L11": [...] },
     "regions": { "the_reach": { "truth": "...", "layers": { ... } }, ... },
     "forbidden": [ {"kind": "crop_on_micro", "a": "...", "b": "..."} ] }
   ```
   Field ids are global as `<scope>.L<n>.<id>` (`skyrim.L9.civil_war`, `the_reach.L5.silver_mining`). A region field
   may cite its own layers above it and the province's. Write the schema down as `tools/world/SCHEMA.md` (short).
2. **Extractor.** `tools/world/sheet_from_md.py <provinces/x.md> --out <json>`: parses section 1 of a province document
   (tables or lists by layer, region sub-sheets) into the schema. Where the document has no explicit cites, it must
   fail with the field name, not guess. Fix the document (it is the design), not the extractor's output.
3. **Lint.** `tools/qa/worldsheet_lint.py [--province <id>]`: every layer present for the province and every region;
   every L2-L11 field cites at least one field in a layer above; every cite resolves; no cycles; every content record
   `why` resolves (scan places, quests, `scatter/kinds.json` scene kinds, dungeon identity, lore books,
   `region_spawns`, palettes micro rules once R3b lands); forbidden pairs absent; prints F-WHY (share of records with a
   resolving `why`) and a legacy back-fill counter per content type. Exit 1 on errors; `qa.ps1` runs it.
4. **Java loader.** `com.skycraft.tamriel.world.WorldSheets`: loads every sheet at server start (background thread,
   like the bake), `WorldSheets.region(regionId)` and `.field("the_reach.L5.silver_mining")`, `fieldsIn(layer)`,
   `truth(regionId)`; immutable and thread-safe; a `/skycraft-world sheet <region> [layer]` debug command.
5. **First consumers.** (a) The population grammar takes a settlement's job weights from its region's L5 fields
   (a simple id-to-job table in the sheet or a side table; record `why` on the residents it makes). (b) Dungeon identity
   (`tools/qa/dungeon_identity_gen.py` or its Java side) takes inhabitant themes from L10 and writes `why`. Keep both
   changes additive and behind a fallback for regions without a sheet.
6. **Pilot.** Generate `skyrim.json` (province + 9 holds) from `provinces/skyrim.md`; lint 0.

## Verification while working

`py -3 -I tools/world/sheet_from_md.py docs/world/provinces/skyrim.md --out ...`,
`py -3 -I tools/qa/worldsheet_lint.py --province skyrim`, `compileJava`, the debug command on the dev server.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Pilot sheet | `worldsheet_lint.py --province skyrim` | 0 errors; 10 blocks (province + 9 holds) x 11 layers |
| Loader | Tier 0 test (or a `/skycraft-world sheet the_reach L5` output if R2 is not merged) | loads, resolves cites |
| Consumers | population and dungeon identity outputs | >= 2 generators write `why` that resolves |
| F-WHY | lint report | printed for every content type; back-fill counter printed |
| QA wiring | `qa.ps1 -NoBuild` | lint runs in the static set |

## Merge and report

`_COMMON.md` section 7. Record the F-WHY baseline in WORLD-PLAN section 7. Report per section 8.

**Size:** M.
