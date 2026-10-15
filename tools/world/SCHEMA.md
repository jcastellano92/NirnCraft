# World Model Sheet Schema (MS22)

Each province sheet lives at `mod/src/main/resources/skycraft/tamriel/world/<province>.json` and is generated from Section 1 of `docs/world/provinces/<province>.md` by `tools/world/sheet_from_md.py`.

## Top-Level Structure

```json
{
  "province": "skyrim",
  "truth": "Skyrim is a cold, high country of nine holds walled in by mountain ranges and the Sea of Ghosts...",
  "layers": {
    "L1": [
      { "id": "spine_ranges", "text": "the Throat of the World massif in the centre...", "cites": [] }
    ],
    "L2": [
      { "id": "rain_shadow_tundra", "text": "the central plains lie in the lee of the ranges...", "cites": ["skyrim.L1.spine_ranges"] }
    ],
    "L3": [],
    "L4": [],
    "L5": [],
    "L6": [],
    "L7": [],
    "L8": [],
    "L9": [],
    "L10": [],
    "L11": []
  },
  "regions": {
    "the_reach": {
      "truth": "The Reach is a land of granite gorges and red crags at Skyrim's western edge...",
      "layers": {
        "L1": [
          { "id": "silver_veins", "text": "silver under Markarth...", "cites": ["skyrim.L1.ore_belts"] }
        ],
        "L5": [
          { "id": "silver_mining", "text": "the prison mine is the city's wealth.", "cites": ["the_reach.L1.silver_veins"] }
        ]
      },
      "jobs": {
        "miner": { "weight": 3, "why": ["the_reach.L5.silver_mining", "the_reach.L5.small_mines"] }
      },
      "dungeon_themes": {
        "forsworn": ["the_reach.L10.forsworn_redoubts", "the_reach.L9.native_uprising"]
      }
    }
  },
  "forbidden": [
    { "kind": "crop_on_micro", "a": "warm_crops", "b": "glacier_waste", "reason": "Sub-freezing ice sheet forbids warm-season crops" },
    { "kind": "industry_without_resource", "a": "silver_smelter", "b": "no_silver_veins", "reason": "Smelting requires an L1/L5 ore source" },
    { "kind": "shrine_without_faith", "a": "open_talos_shrine", "b": "haafingar", "reason": "Haafingar enforces the White-Gold Concordat ban on open Talos altars" }
  ]
}
```

## Causal Layers (`L1`–`L11`)

1. **`L1` Geology and landform** — ranges, bedrock, volcanism, karst/caves, ore belts, coastlines.
2. **`L2` Climate** — winds, rain shadow, snowline, fog, storms.
3. **`L3` Water** — rivers, lakes, marshes, channels/wells, who controls water.
4. **`L4` Ecology and micro-biomes** — flora, fauna, micro-biome patches, predators.
5. **`L5` Resources and economy** — mining, farming, timber, fishing, crafts, trade arteries.
6. **`L6` Settlement pattern** — seats, towns, working villages, why each sits where it does.
7. **`L7` Society** — classes, rulers/thanes, labour, outsiders, strongholds.
8. **`L8` Culture and belief** — temples, ancestor barrows, Daedric cults, local customs.
9. **`L9` Politics and conflict** — civil war, succession, native uprisings, factional tension.
10. **`L10` Hidden and underground** — dungeon inhabitants, ruins, covens, criminal networks.
11. **`L11` History layers** — eras, battles, collapses, grudges visible in the landscape.

## Field IDs and Citation Rules

* **Global IDs:** `<scope>.L<n>.<id>`, where `<scope>` is either the province key (`skyrim`) or a canonical region key (`whiterun`, `haafingar`, `falkreath`, `hjaalmarch`, `the_pale`, `the_rift`, `winterhold`, `eastmarch`, `the_reach`).
* **Aliases:** Markdown shorthand (`sk` for `skyrim`, `reach` for `the_reach`, `rift` for `the_rift`, `pale` for `the_pale`) and prefixed region IDs (`skyrim/the_reach`) normalize to canonical `<scope>` keys during extraction and lookup.
* **Causal Ordering:**
  * Every block (province + each region) must contain all eleven layers `L1`..`L11`, each non-empty.
  * Every field in `L2`–`L11` must have explicit `cites` in the source markdown (`sheet_from_md.py` fails with the field name if omitted) and must cite **at least one field in a strictly higher layer** (`L1`..`L<n-1>`) in the same region or the province sheet.
  * Every cited field ID must resolve in the sheet (or loaded province sheets), and the citation graph must be acyclic.
* **Content `why` Links:** Content records (`places`, `quests`, `scatter/kinds.json`, `dungeons/identity/*.json`, `lore`, `region_spawns`, `palettes.json` micro rules) carry `"why": ["<scope>.L<n>.<id>", ...]`. `tools/qa/worldsheet_lint.py` validates every `"why"` entry and reports `F-WHY` coverage plus legacy back-fill counters per content type.
