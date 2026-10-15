# Skyrim parity: scorecard, deficits and fill backlog

Owner's concern: outside Skyrim everything feels light (cavern types, quests, dialogue, enemies, world-building,
building styles, cities). It should be full, varied, tied to its region and never copy-paste. This document measures
that gap per region and turns it into batches that Sonnet agents can fill in parallel without touching each other's
files. Companions: `docs/MARQUEE-GAP.md` and `docs/MARQUEE-PLAN.md` (places), `docs/REGION-AUDIT.md` (biomes and
borders), `docs/DUNGEON-AUDIT.md`, `docs/TOWN-AUDIT.md`. Rules: `docs/AGENT-RULES.md`, `docs/FINISH-PLAN.md` section 0
(no client launches, at most 3 agents, merge traps, legal rule: facts and inspiration only, all text original).

## 0. Run it

```
py -3 -I tools/qa/parity.py                      # province + sub-region scorecard, 10 lowest cells (~1 s)
py -3 -I tools/qa/parity.py --raw                # plus the raw numbers behind every cell
py -3 -I tools/qa/parity.py --deficits 40        # lowest province-level sub-metrics (score, value, Skyrim ref)
py -3 -I tools/qa/parity.py --floors             # how many items each province is short of the 80% floor
py -3 -I tools/qa/parity.py --assert-metric d_identity,r_places   # exit 1 if any region is under floor ("done" check)
py -3 -I tools/qa/parity.py --assert-floor NPC,LOR                # same for whole categories
py -3 -I tools/qa/parity.py --write-doc docs/PARITY.md            # refresh the generated block below
tools\qa\qa.ps1 -NoBuild -Parity                 # QA report plus the 10 lowest cells (informational, never fails)
```

`parity.py` is standard-library only; it reads `places/*.json`, `quests/*.json`, `lore/places/*.json`,
`tamriel/{regions,palettes,cityplans,styles}`, `region_spawns`, the biome/feature/structure JSON, the item tags, the
audio folder, `lang/skycraft/{society,place_lore}.json` and greps the dungeon, society, explore and loot Java.
`tools/qa/out/parity.json` holds every number. It writes nothing else.

## 1. How a score is made

Skyrim (the nine holds, 26.5 km2 of raster land) is 100. For four metrics (`s_npcs`, `s_bldgs`, `q_total`,
`q_per_town`) the reference is the real game measured from the owner's install where that is higher than our copy (see
section 5), so our own Skyrim scores under 100 there. A sub-metric value is divided by Skyrim's value for the same
thing, times 100, capped at 150. A category score is `0.6 x mean + 0.4 x weakest sub-metric`, so one empty column (no
lore hooks, no hand-built dungeons) cannot hide behind seven healthy ones. Four kinds of sub-metric:

| kind | meaning | Skyrim reference |
| --- | --- | --- |
| density | count per km2 of land (quests, dungeons, landmarks, lore places) | Skyrim total / Skyrim km2 |
| unit | count per named town (capital + city + town): NPCs, quests, dialogue, named buildings | Skyrim total / 18 named towns |
| div | a variety count per region (themes, looks, creature kinds, tree species...) | mean of the nine holds |
| prov | one number shared by every region of a province (music, gear lists, legendary uniques) | Skyrim's own number |

Region id = `regions.json` id (`province/region`). Provinces group them. "GLOBAL" things are listed separately and
not scored per region (see the end of the generated block). Realms beyond the map (Atmora, Akavir, Yokuda, Pyandonea,
Thras) have no raster area: their density cells read 0, and they are left out of the lowest lists unless `--realms`.
Land slivers under 1 km2 (Artaeum, Bitter Coast) are left out of the lowest lists as well.

| category | sub-metrics (id: what) |
| --- | --- |
| SET settlements | `s_towns` named towns/km2, `s_small` villages+farms/km2, `s_bespoke` share with a unique plan, `s_bldgs` named buildings per town, `s_npcs` named NPCs per town |
| NPC | `n_lines` greeting + scene lines per town, `n_scenes` scenes per town, `n_roles` distinct NPC roles, `n_say` region-keyed chat/say lines |
| QST | `q_total` quests/km2, `q_per_town`, `q_kinds` (main/side/faction/hidden present), `q_placed` share of quests that use a registered place |
| DUN | `d_density`, `d_themes`, `d_caverns` carved looks (theme x kit palette), `d_rosters` inhabitants, `d_built` built looks, `d_rooms` room templates, `d_marquee` hand-authored, `d_bosses` named bosses, `d_identity` share of dungeons with a lore hook, quest or unique |
| CRE | `c_hostile`/`c_wild` kinds per biome family, `c_cave`, `c_total` kinds in region, `c_bosses` boss+dragon+shrine/km2 |
| FLO | `f_biomes`, `f_trees`, `f_plants` (palette biomes and the species their features place) |
| STY | `y_looks` distinct settlement looks, `y_sheet` style-sheet richness (leaf count of the culture sheet) |
| LND | `l_density`, `l_kinds` distinct landmark kinds, `l_minor` minor-site kinds (province), `l_unique` explore uniques/km2 |
| LOR | `r_places` places with a lore entry/km2, `r_notes` readables + place books/km2, `r_flavor` rumours + legends + songs |
| MUS | `m_explore` exploration tracks, `m_town` town tracks, `m_amb` ambience beds (derived from the region's biome families) |
| ITM | `i_gear` regional gear list size, `i_sets` signature (non-universal) gear families, `i_ingr`, `i_trink`, `i_unique` legendary uniques |

<!-- PARITY:BEGIN (generated by tools/qa/parity.py --write-doc; do not hand-edit) -->

Symbols: ■ 100+  ▣ 80+  ▤ 50+  ▥ 25+  □ <25

Columns: **SET** Settlements, **NPC** NPCs + dialogue, **QST** Quests, **DUN** Dungeons, **CRE** Creatures, **FLO** Flora + biomes, **STY** Building styles, **LND** Landmarks + sites, **LOR** Lore, **MUS** Music + ambience, **ITM** Items + loot.

### By province (area-weighted for densities, mean of regions for variety)

| province | km2 | SET | NPC | QST | DUN | CRE | FLO | STY | LND | LOR | MUS | ITM | mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **skyrim** | 26.5 | ■ 119 | ■ 143 | ■ 110 | ■ 100 | ■ 100 | ■ 100 | ■ 115 | ■ 100 | ■ 100 | ■ 100 | ■ 100 | ■ 108 |
| **cyrodiil** | 60.9 | ■ 103 | ■ 132 | ■ 113 | ■ 120 | ■ 105 | ■ 129 | ■ 140 | ■ 120 | ■ 128 | ■ 115 | ■ 130 | ■ 121 |
| **morrowind** | 36.7 | ■ 121 | ■ 150 | ■ 115 | ■ 124 | ■ 120 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 132 |
| **high_rock** | 34.9 | ■ 118 | ■ 116 | ■ 106 | ■ 113 | ■ 106 | ▣ 98 | ■ 132 | ■ 122 | ■ 133 | ■ 103 | ■ 114 | ■ 115 |
| **hammerfell** | 22.3 | ■ 122 | ■ 116 | ■ 109 | ■ 101 | ■ 107 | ▣ 95 | ■ 130 | ■ 118 | ■ 134 | ■ 108 | ■ 115 | ■ 114 |
| **elsweyr** | 25.7 | ■ 123 | ■ 150 | ■ 114 | ■ 121 | ■ 115 | ■ 150 | ■ 150 | ■ 120 | ■ 146 | ■ 118 | ■ 117 | ■ 129 |
| **valenwood** | 24.2 | ■ 122 | ■ 146 | ■ 115 | ■ 121 | ■ 113 | ■ 150 | ■ 150 | ■ 122 | ■ 143 | ■ 118 | ■ 117 | ■ 129 |
| **summerset** | 13.6 | ■ 124 | ■ 134 | ■ 112 | ■ 122 | ■ 117 | ■ 150 | ■ 150 | ■ 120 | ■ 150 | ■ 118 | ■ 117 | ■ 129 |
| **black_marsh** | 38.0 | ■ 119 | ■ 150 | ■ 115 | ■ 122 | ■ 115 | ■ 150 | ■ 150 | ■ 120 | ■ 142 | ■ 118 | ■ 116 | ■ 129 |
| **atmora** | 0.0 | ■ 120 | ■ 139 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 122 |
| **akavir** | 0.0 | ■ 120 | ■ 149 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 123 |
| **yokuda** | 0.0 | ■ 120 | ■ 148 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 123 |
| **pyandonea** | 0.0 | ■ 120 | ■ 150 | ■ 112 | ■ 121 | ■ 119 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 117 | ■ 123 |
| **thras** | 0.0 | ■ 120 | ■ 148 | ■ 112 | ■ 121 | ■ 119 | ■ 106 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 124 |

### By sub-region

| region | km2 | SET | NPC | QST | DUN | CRE | FLO | STY | LND | LOR | MUS | ITM | mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| skyrim/eastmarch | 2.9 | ■ 121 | ■ 129 | ■ 108 | ▣ 92 | ■ 100 | ■ 106 | ■ 115 | ■ 107 | ▣ 98 | ■ 102 | ■ 100 | ■ 107 |
| skyrim/falkreath | 4.6 | ■ 103 | ■ 132 | ■ 110 | ▣ 94 | ▣ 91 | ■ 113 | ■ 109 | ▣ 92 | ▣ 97 | ■ 101 | ■ 100 | ■ 104 |
| skyrim/haafingar | 1.7 | ■ 121 | ■ 122 | ■ 105 | ▣ 92 | ■ 105 | ▣ 91 | ■ 109 | ■ 107 | ▣ 99 | ▣ 96 | ■ 100 | ■ 104 |
| skyrim/hjaalmarch | 1.4 | ■ 121 | ■ 132 | ■ 110 | ▣ 95 | ■ 106 | ▣ 89 | ■ 103 | ▣ 91 | ▣ 99 | ▣ 96 | ■ 100 | ■ 104 |
| skyrim/the_pale | 1.7 | ■ 115 | ■ 132 | ■ 110 | ▣ 95 | ■ 106 | ▣ 89 | ■ 103 | ▣ 97 | ■ 100 | ▣ 96 | ■ 100 | ■ 104 |
| skyrim/the_reach | 4.6 | ■ 103 | ■ 150 | ■ 109 | ▣ 91 | ▣ 90 | ■ 108 | ■ 115 | ▣ 90 | ▣ 96 | ■ 101 | ■ 100 | ■ 105 |
| skyrim/the_rift | 4.2 | ■ 120 | ■ 145 | ■ 107 | ▣ 92 | ▣ 90 | ■ 109 | ■ 115 | ▣ 89 | ▣ 94 | ■ 102 | ■ 100 | ■ 106 |
| skyrim/whiterun | 3.0 | ■ 120 | ■ 150 | ■ 108 | ▣ 90 | ▣ 97 | ▣ 90 | ■ 115 | ■ 101 | ▣ 97 | ▣ 96 | ■ 100 | ■ 106 |
| skyrim/winterhold | 2.3 | ■ 120 | ■ 141 | ■ 110 | ▣ 92 | ▣ 99 | ▣ 87 | ■ 103 | ▣ 96 | ▣ 96 | ▣ 96 | ■ 100 | ■ 104 |
| cyrodiil/blackwood | 4.5 | ■ 117 | ■ 131 | ■ 109 | ■ 122 | ■ 115 | ■ 137 | ■ 140 | ■ 120 | ■ 149 | ■ 117 | ■ 130 | ■ 126 |
| cyrodiil/colovian_highlands | 9.2 | ▣ 90 | ■ 132 | ■ 113 | ■ 119 | ■ 103 | ■ 128 | ■ 140 | ■ 120 | ■ 120 | ■ 115 | ■ 130 | ■ 119 |
| cyrodiil/gold_coast | 5.5 | ■ 117 | ■ 132 | ■ 115 | ■ 120 | ■ 106 | ■ 132 | ■ 140 | ■ 120 | ■ 131 | ■ 115 | ■ 130 | ■ 123 |
| cyrodiil/great_forest | 3.8 | ▣ 97 | ■ 129 | ■ 112 | ■ 118 | ■ 104 | ■ 125 | ■ 140 | ■ 120 | ■ 129 | ■ 115 | ■ 130 | ■ 120 |
| cyrodiil/heartlands | 7.7 | ■ 102 | ■ 132 | ■ 115 | ■ 120 | ■ 104 | ■ 125 | ■ 140 | ■ 120 | ■ 116 | ■ 115 | ■ 130 | ■ 120 |
| cyrodiil/jerall_mountains | 9.5 | ▣ 92 | ■ 132 | ■ 112 | ■ 119 | ■ 104 | ■ 132 | ■ 140 | ■ 120 | ■ 117 | ■ 115 | ■ 130 | ■ 119 |
| cyrodiil/nibenay_basin | 8.5 | ■ 106 | ■ 132 | ■ 112 | ■ 120 | ■ 103 | ■ 127 | ■ 140 | ■ 120 | ■ 126 | ■ 117 | ■ 130 | ■ 121 |
| cyrodiil/nibenay_valley | 8.2 | ▣ 96 | ■ 132 | ■ 112 | ■ 120 | ■ 104 | ■ 128 | ■ 140 | ■ 120 | ■ 130 | ■ 117 | ■ 130 | ■ 121 |
| cyrodiil/west_weald | 4.0 | ■ 107 | ■ 132 | ■ 113 | ■ 119 | ■ 107 | ■ 128 | ■ 140 | ■ 120 | ■ 147 | ■ 115 | ■ 130 | ■ 123 |
| morrowind/ascadian_isles | 1.7 | ■ 124 | ■ 150 | ■ 113 | ■ 124 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 132 |
| morrowind/azuras_coast | 1.3 | ■ 122 | ■ 150 | ■ 112 | ■ 124 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 132 |
| morrowind/bitter_coast | 0.5 | ▣ 99 | ■ 130 | ▣ 97 | ■ 122 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 126 |
| morrowind/deshaan | 5.5 | ■ 117 | ■ 150 | ■ 110 | ■ 109 | ▣ 97 | ■ 150 | ■ 150 | ■ 125 | ■ 125 | ■ 118 | ■ 128 | ■ 125 |
| morrowind/grazelands | 2.0 | ■ 123 | ■ 150 | ▣ 94 | ■ 124 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 130 |
| morrowind/molag_amur | 4.8 | ▣ 86 | ■ 150 | ■ 111 | ■ 115 | ■ 102 | ■ 150 | ■ 150 | ■ 125 | ■ 125 | ■ 118 | ■ 128 | ■ 124 |
| morrowind/red_mountain | 1.6 | ■ 116 | ■ 150 | ■ 110 | ■ 123 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 131 |
| morrowind/sheogorad | 0.6 | ■ 119 | ■ 150 | ▣ 100 | ■ 121 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 130 |
| morrowind/solstheim | 1.0 | ■ 124 | ■ 150 | ■ 116 | ■ 124 | ■ 123 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 127 | ■ 133 |
| morrowind/stonefalls | 5.2 | ■ 124 | ■ 150 | ■ 111 | ■ 110 | ▣ 95 | ■ 150 | ■ 150 | ■ 125 | ■ 127 | ■ 118 | ■ 128 | ■ 126 |
| morrowind/telvannis | 8.7 | ■ 109 | ■ 150 | ■ 111 | ■ 111 | ▣ 95 | ■ 150 | ■ 150 | ■ 125 | ■ 111 | ■ 118 | ■ 128 | ■ 123 |
| morrowind/vvardenfell_ashlands | 2.5 | ■ 123 | ■ 150 | ■ 108 | ■ 124 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 131 |
| morrowind/west_gash | 1.2 | ■ 124 | ■ 150 | ■ 109 | ■ 124 | ■ 119 | ■ 150 | ■ 150 | ■ 125 | ■ 150 | ■ 118 | ■ 128 | ■ 132 |
| high_rock/anticlere | 1.7 | ■ 118 | ■ 118 | ■ 103 | ■ 101 | ■ 109 | ▣ 87 | ■ 132 | ■ 113 | ■ 150 | ▣ 98 | ■ 114 | ■ 113 |
| high_rock/bjoulsae | 4.0 | ■ 111 | ■ 115 | ■ 100 | ■ 103 | ■ 101 | ▣ 92 | ■ 132 | ■ 118 | ■ 119 | ▣ 95 | ■ 114 | ■ 109 |
| high_rock/daggerfall | 1.6 | ■ 124 | ■ 117 | ■ 105 | ■ 102 | ■ 111 | ▣ 95 | ■ 132 | ■ 119 | ■ 150 | ■ 102 | ■ 114 | ■ 116 |
| high_rock/dwynnen | 1.0 | ■ 124 | ■ 128 | ■ 107 | ▣ 93 | ■ 112 | ▣ 84 | ■ 132 | ■ 116 | ■ 150 | ▣ 97 | ■ 114 | ■ 114 |
| high_rock/glenumbra | 5.6 | ■ 113 | ■ 114 | ▣ 98 | ■ 104 | ■ 103 | ■ 106 | ■ 132 | ■ 123 | ■ 123 | ■ 108 | ■ 114 | ■ 113 |
| high_rock/rivenspire | 4.8 | ■ 118 | ■ 114 | ▣ 96 | ■ 105 | ■ 104 | ▣ 98 | ■ 132 | ■ 125 | ■ 124 | ■ 104 | ■ 114 | ■ 112 |
| high_rock/stormhaven | 2.8 | ▣ 91 | ▣ 88 | ■ 106 | ■ 105 | ■ 109 | ▣ 87 | ■ 132 | ■ 124 | ■ 142 | ▣ 98 | ■ 114 | ■ 109 |
| high_rock/wayrest | 1.0 | ■ 124 | ■ 115 | ■ 104 | ■ 101 | ■ 112 | ▣ 86 | ■ 132 | ■ 120 | ■ 150 | ▣ 91 | ■ 114 | ■ 113 |
| high_rock/wrothgar | 12.3 | ▣ 89 | ■ 112 | ■ 103 | ■ 118 | ▣ 99 | ■ 142 | ■ 133 | ■ 122 | ■ 113 | ■ 116 | ■ 113 | ■ 115 |
| hammerfell/alikr_desert | 5.8 | ■ 115 | ■ 114 | ▣ 99 | ■ 103 | ■ 100 | ■ 102 | ■ 130 | ■ 122 | ■ 126 | ■ 110 | ■ 115 | ■ 113 |
| hammerfell/bangkorai | 1.3 | ■ 124 | ■ 110 | ■ 105 | ▣ 90 | ■ 111 | ▣ 100 | ■ 130 | ■ 115 | ■ 150 | ■ 110 | ■ 115 | ■ 115 |
| hammerfell/craglorn | 1.7 | ■ 124 | ■ 115 | ■ 103 | ▣ 99 | ■ 111 | ▣ 81 | ■ 130 | ■ 115 | ■ 142 | ■ 100 | ■ 115 | ■ 112 |
| hammerfell/dragontail | 8.2 | ▣ 100 | ■ 119 | ■ 103 | ■ 102 | ▣ 95 | ■ 118 | ■ 130 | ■ 122 | ■ 114 | ■ 112 | ■ 115 | ■ 112 |
| hammerfell/hews_bane | 3.9 | ■ 115 | ■ 115 | ■ 100 | ■ 103 | ■ 106 | ▣ 83 | ■ 130 | ■ 122 | ■ 126 | ■ 102 | ■ 115 | ■ 111 |
| hammerfell/sentinel | 1.1 | ■ 124 | ■ 116 | ■ 103 | ▣ 93 | ■ 112 | ▣ 88 | ■ 130 | ■ 112 | ■ 150 | ■ 106 | ■ 115 | ■ 114 |
| hammerfell/stros_mkai | 0.5 | ■ 124 | ■ 118 | ■ 116 | ▣ 90 | ■ 112 | ▣ 83 | ■ 130 | ■ 108 | ■ 150 | ■ 106 | ■ 115 | ■ 114 |
| elsweyr/anequina | 5.6 | ■ 122 | ■ 150 | ■ 114 | ■ 116 | ■ 112 | ■ 150 | ■ 150 | ■ 120 | ■ 143 | ■ 118 | ■ 117 | ■ 128 |
| elsweyr/pellitine | 8.2 | ■ 124 | ■ 108 | ■ 111 | ■ 117 | ■ 104 | ■ 150 | ■ 150 | ■ 120 | ■ 132 | ■ 118 | ■ 117 | ■ 123 |
| elsweyr/rimmen | 4.3 | ■ 124 | ■ 150 | ■ 114 | ■ 123 | ■ 117 | ■ 150 | ■ 150 | ■ 120 | ■ 150 | ■ 118 | ■ 117 | ■ 130 |
| elsweyr/senchal | 3.5 | ■ 118 | ■ 150 | ■ 115 | ■ 123 | ■ 118 | ■ 150 | ■ 150 | ■ 120 | ■ 150 | ■ 118 | ■ 117 | ■ 130 |
| elsweyr/torval | 4.1 | ■ 112 | ■ 150 | ■ 115 | ■ 122 | ■ 116 | ■ 150 | ■ 150 | ■ 120 | ■ 148 | ■ 118 | ■ 117 | ■ 129 |
| valenwood/grahtwood | 10.1 | ■ 113 | ■ 142 | ■ 113 | ■ 115 | ■ 102 | ■ 150 | ■ 150 | ■ 122 | ■ 127 | ■ 118 | ■ 117 | ■ 124 |
| valenwood/greenshade | 4.5 | ■ 120 | ■ 150 | ■ 116 | ■ 122 | ■ 116 | ■ 150 | ■ 150 | ■ 122 | ■ 150 | ■ 118 | ■ 117 | ■ 130 |
| valenwood/malabal_tor | 3.6 | ■ 124 | ■ 150 | ■ 113 | ■ 122 | ■ 117 | ■ 150 | ■ 150 | ■ 122 | ■ 150 | ■ 118 | ■ 117 | ■ 130 |
| valenwood/reapers_march | 6.0 | ■ 124 | ■ 122 | ■ 113 | ■ 121 | ■ 114 | ■ 150 | ■ 150 | ■ 122 | ■ 143 | ■ 118 | ■ 117 | ■ 127 |
| summerset/artaeum | 0.4 | ■ 124 | ■ 150 | ■ 114 | ■ 122 | ■ 119 | ■ 150 | ■ 150 | ■ 120 | ■ 150 | ■ 118 | ■ 117 | ■ 130 |
| summerset/auridon | 2.8 | ■ 124 | ■ 138 | ■ 114 | ■ 124 | ■ 119 | ■ 150 | ■ 150 | ■ 120 | ■ 150 | ■ 118 | ■ 117 | ■ 129 |
| summerset/summerset_isle | 10.4 | ■ 124 | ■ 108 | ■ 112 | ■ 110 | ■ 103 | ■ 150 | ■ 150 | ■ 120 | ■ 127 | ■ 118 | ■ 117 | ■ 122 |
| black_marsh/gideon | 6.9 | ■ 107 | ■ 150 | ■ 113 | ■ 121 | ■ 108 | ■ 150 | ■ 150 | ■ 120 | ■ 133 | ■ 118 | ■ 116 | ■ 126 |
| black_marsh/helstrom | 7.2 | ■ 124 | ■ 124 | ■ 110 | ■ 122 | ■ 111 | ■ 150 | ■ 150 | ■ 120 | ■ 135 | ■ 118 | ■ 116 | ■ 125 |
| black_marsh/lilmoth | 4.6 | ■ 124 | ■ 132 | ■ 113 | ■ 122 | ■ 115 | ■ 150 | ■ 150 | ■ 120 | ■ 147 | ■ 118 | ■ 116 | ■ 128 |
| black_marsh/murkmire | 7.0 | ■ 102 | ■ 150 | ■ 112 | ■ 122 | ■ 114 | ■ 150 | ■ 150 | ■ 120 | ■ 139 | ■ 118 | ■ 116 | ■ 127 |
| black_marsh/shadowfen | 7.2 | ■ 105 | ■ 150 | ■ 113 | ■ 123 | ■ 117 | ■ 150 | ■ 150 | ■ 120 | ■ 148 | ■ 118 | ■ 116 | ■ 128 |
| black_marsh/stormhold | 5.0 | ■ 104 | ■ 150 | ■ 113 | ■ 124 | ■ 117 | ■ 150 | ■ 150 | ■ 120 | ■ 150 | ■ 118 | ■ 116 | ■ 128 |
| atmora/frozen_forest | 0.0 | ■ 120 | ■ 145 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 122 |
| atmora/ice_waste | 0.0 | ■ 120 | ■ 133 | ■ 111 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 121 |
| akavir/ka_po_tun | 0.0 | ■ 120 | ■ 150 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 123 |
| akavir/kamal | 0.0 | ■ 120 | ■ 145 | ■ 111 | ■ 121 | ■ 116 | ▣ 99 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 122 |
| akavir/tang_mo | 0.0 | ■ 120 | ■ 150 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 123 |
| akavir/tsaesci | 0.0 | ■ 120 | ■ 148 | ■ 111 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 123 |
| yokuda/sunken_isles | 0.0 | ■ 120 | ■ 142 | ■ 111 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 122 |
| yokuda/yokudan_desert | 0.0 | ■ 120 | ■ 150 | ■ 112 | ■ 121 | ■ 116 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 123 |
| pyandonea/maormer_isles | 0.0 | ■ 120 | ■ 150 | ■ 112 | ■ 121 | ■ 119 | ▣ 97 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 117 | ■ 123 |
| thras/coral_kingdom | 0.0 | ■ 120 | ■ 148 | ■ 112 | ■ 121 | ■ 119 | ■ 106 | ■ 150 | ■ 111 | ■ 150 | ■ 108 | ■ 116 | ■ 124 |

### Raw numbers

Province-level raw numbers (density metrics are per km2 of land; unit metrics per named town; `[P]` metrics are shared by every region of a province). The last column is the Skyrim reference used as 100.

**SET: Settlements**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| named towns /km2 | 0.68 | 0.43 | 0.71 | 0.55 | 0.72 | 0.78 | 0.74 | 1.25 | 0.58 | - | - | - | - | - |
| villages+farms /km2 | 1.89 | 3.18 | 4.98 | 4.02 | 3.76 | 5.02 | 3.97 | 4.71 | 3.29 | - | - | - | - | - |
| bespoke share of named towns | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 |
| named buildings per named town | 32 | 15 | 23 | 40 | 42 | 35 | 34 | 32 | 34 | 44 | 40 | 38 | 56 | 38 |
| named NPCs per named town | 95 | 67 | 111 | 80 | 86 | 85 | 86 | 80 | 86 | 124 | 113 | 106 | 158 | 105 |

**NPC: NPCs + dialogue**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dialogue + scene lines per named town | 123 | 147 | 453 | 112 | 116 | 262 | 260 | 239 | 268 | 499 | 472 | 456 | 598 | 453 |
| NPC scenes per named town | 14 | 13 | 44 | 8.58 | 8.50 | 12 | 11 | 9.65 | 13 | 48 | 46 | 48 | 49 | 48 |
| distinct NPC roles among named NPCs | 116 | 54 | 61 | 37 | 38 | 62 | 62 | 62 | 62 | 44 | 50 | 49 | 53 | 49 |
| region-keyed chat/say lines | 25 | 21 | 27 | 21 | 21 | 27 | 27 | 27 | 27 | 28 | 27 | 28 | 27 | 27 |

**QST: Quests**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| quests /km2 | 19 | 18 | 20 | 14 | 18 | 22 | 21 | 31 | 19 | - | - | - | - | - |
| quests per named town | 27 | 41 | 29 | 26 | 25 | 28 | 29 | 25 | 33 | 32 | 30 | 32 | 42 | 31 |
| quest kinds (main/side/faction/hidden/radiant) | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | 4 |
| share of quests on registered places | 0.89 | 0.99 | 0.99 | 0.98 | 0.96 | 1.00 | 1.00 | 1 | 1.00 | 1 | 1 | 1 | 1 | 1 |

**DUN: Dungeons**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dungeons+forts /km2 | 12 | 13 | 23 | 14 | 12 | 13 | 12 | 14 | 14 | - | - | - | - | - |
| distinct dungeon themes | 14 | 19 | 22 | 15 | 14 | 22 | 22 | 23 | 21 | 22 | 22 | 22 | 22 | 22 |
| distinct carved looks (theme x kit palette) | 5.11 | 7 | 8 | 6 | 5.86 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 |
| distinct inhabitant rosters in carved dungeons | 7.44 | 15 | 17 | 14 | 14 | 17 | 18 | 18 | 17 | 17 | 17 | 17 | 17 | 17 |
| distinct built-dungeon looks | 4.44 | 11 | 14 | 8.78 | 7.43 | 12 | 15 | 14 | 13 | 13 | 12 | 12 | 12 | 12 |
| room templates reachable | 53 | 59 | 59 | 54 | 53 | 59 | 59 | 59 | 59 | 59 | 59 | 59 | 59 | 59 |
| hand-authored dungeons | 1.22 | 4.33 | 4 | 1.22 | 1 | 4.20 | 4.25 | 4.33 | 4.17 | 4 | 3.50 | 3.50 | 4 | 3 |
| named quest bosses | 40 | 90 | 67 | 60 | 48 | 80 | 88 | 81 | 102 | 47 | 46 | 48 | 49 | 49 |
| share of dungeons with a lore hook, quest or unique | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 |

**CRE: Creatures**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hostile kinds per biome family | 23 | 26 | 26 | 25 | 25 | 26 | 26 | 26 | 26 | 26 | 26 | 26 | 29 | 29 |
| wildlife kinds per biome family | 13 | 17 | 17 | 16 | 16 | 17 | 17 | 17 | 17 | 17 | 17 | 17 | 17 | 17 |
| cave creature kinds | 17 | 18 | 18 | 17 | 17 | 18 | 18 | 18 | 18 | 18 | 18 | 18 | 18 | 18 |
| distinct creature kinds in region | 40 | 56 | 56 | 47 | 49 | 56 | 56 | 56 | 56 | 56 | 56 | 56 | 59 | 59 |
| bosses+dragons+shrines /km2 | 15 | 14 | 26 | 16 | 16 | 17 | 16 | 20 | 17 | - | - | - | - | - |

**FLO: Flora + biomes**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| biomes in palette | 35 | 40 | 66 | 32 | 33 | 71 | 70 | 69 | 69 | 57 | 59 | 59 | 58 | 58 |
| tree species | 56 | 107 | 138 | 60 | 52 | 131 | 132 | 131 | 135 | 59 | 62 | 59 | 62 | 60 |
| flora species | 61 | 121 | 149 | 66 | 62 | 144 | 140 | 142 | 146 | 46 | 46 | 46 | 45 | 55 |

**STY: Building styles**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| distinct settlement looks | 6.89 | 17 | 14 | 9.22 | 9.14 | 18 | 19 | 18 | 17 | 10 | 10 | 10 | 11 | 10 |
| style-sheet richness | 541 | 737 | 995 | 672 | 657 | 1342 | 1297 | 1335 | 1324 | 1311 | 1306 | 1315 | 1314 | 1270 |

**LND: Landmarks + sites**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| landmarks+shrines+stones /km2 | 3.51 | 9.52 | 13 | 6.14 | 8.15 | 9.22 | 9.76 | 11 | 8.19 | - | - | - | - | - |
| distinct landmark kinds | 16 | 30 | 34 | 20 | 19 | 35 | 37 | 39 | 34 | 33 | 34 | 35 | 33 | 35 |
| minor-site kinds available [P] | 19 | 18 | 20 | 20 | 19 | 18 | 19 | 18 | 18 | 18 | 18 | 18 | 18 | 18 |
| explore uniques /km2 | 1.36 | 3.87 | 4.30 | 3.01 | 3.22 | 2.72 | 2.60 | 3.02 | 2.53 | - | - | - | - | - |

**LOR: Lore**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| places with lore /km2 | 17 | 20 | 37 | 21 | 22 | 25 | 24 | 28 | 24 | - | - | - | - | - |
| readables+books /km2 | 90 | 131 | 412 | 142 | 141 | 257 | 248 | 287 | 252 | - | - | - | - | - |
| rumours+legends+songs | 16 | 37 | 209 | 31 | 32 | 31 | 31 | 33 | 31 | 25 | 25 | 25 | 25 | 25 |

**MUS: Music + ambience**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| exploration tracks [P] | 5 | 7 | 7 | 7 | 7 | 7 | 7 | 7 | 7 | 7 | 7 | 7 | 7 | 7 |
| town tracks [P] | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| ambience beds | 32 | 44 | 60 | 29 | 33 | 60 | 60 | 60 | 60 | 32 | 32 | 32 | 32 | 32 |

**ITM: Items + loot**

| metric | skyrim | cyrodiil | morrowind | high_rock | hammerfell | elsweyr | valenwood | summerset | black_marsh | atmora | akavir | yokuda | pyandonea | thras |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| regional gear list size [P] | 302 | 357 | 379 | 340 | 346 | 363 | 367 | 357 | 357 | 357 | 357 | 357 | 366 | 357 |
| signature gear families (non-universal sets) [P] | 22 | 29 | 33 | 26 | 28 | 29 | 30 | 29 | 29 | 29 | 29 | 29 | 30 | 29 |
| regional ingredients [P] | 65 | 89 | 107 | 87 | 89 | 90 | 89 | 92 | 89 | 89 | 89 | 89 | 89 | 89 |
| regional trinkets [P] | 6 | 11 | 11 | 11 | 10 | 12 | 12 | 13 | 12 | 11 | 13 | 11 | 12 | 11 |
| legendary uniques [P] | 8 | 12 | 9 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 |


### Lowest 25 cells (sub-regions of 5 km2 or more, Skyrim holds excluded)

| score | region | category |
|---:|---|---|
| 81 | hammerfell/craglorn | Flora + biomes |
| 83 | hammerfell/hews_bane | Flora + biomes |
| 86 | morrowind/molag_amur | Settlements |
| 87 | high_rock/anticlere | Flora + biomes |
| 87 | high_rock/stormhaven | Flora + biomes |
| 88 | hammerfell/sentinel | Flora + biomes |
| 88 | high_rock/stormhaven | NPCs + dialogue |
| 89 | high_rock/wrothgar | Settlements |
| 90 | hammerfell/bangkorai | Dungeons |
| 90 | cyrodiil/colovian_highlands | Settlements |
| 91 | high_rock/stormhaven | Settlements |
| 92 | high_rock/bjoulsae | Flora + biomes |
| 92 | cyrodiil/jerall_mountains | Settlements |
| 93 | hammerfell/sentinel | Dungeons |
| 94 | morrowind/grazelands | Quests |
| 95 | high_rock/daggerfall | Flora + biomes |
| 95 | high_rock/bjoulsae | Music + ambience |
| 95 | morrowind/telvannis | Creatures |
| 95 | hammerfell/dragontail | Creatures |
| 95 | morrowind/stonefalls | Creatures |
| 96 | cyrodiil/nibenay_valley | Settlements |
| 96 | high_rock/rivenspire | Quests |
| 97 | cyrodiil/great_forest | Settlements |
| 97 | morrowind/deshaan | Creatures |
| 98 | high_rock/rivenspire | Flora + biomes |

### Global (not per region)

* NPC roles in the engine: 33; radiant templates: 33; creature kinds in spawn tables: 98; recipes: 105.
* Room templates in code (distinct `feature("room:*"|"setpiece:*")` names): 59; cave variant kinds: 10.
* Places: 7546. Bespoke settlement layouts (plan file or Java case): 360. Quests counted: 5739.

<!-- PARITY:END -->

## 2. Reading the scorecard

* Skyrim's holds average 84 to 101 (cells range 45 to 150: The Pale is thin on NPC lines and dungeons, Haafingar,
  Whiterun and Eastmarch on hand-authored dungeons). Every other province averages **37 to 52** (Morrowind best, Black
  Marsh worst). No province outside Skyrim reaches 80 in SET, NPC, QST, DUN, CRE, LOR or ITM (Solstheim is the one
  sub-region that does, because it was written as Skyrim's neighbour).
* The map is not empty: density of towns, villages, dungeons, landmarks and minor sites is at or above Skyrim's per km2
  in most provinces. What is missing is **depth per place**: the thing Skyrim does at every site (a name with a story,
  a person to talk to, a reason to go) is done for ~2% of the non-Skyrim ones.
* The shape of the gap at province level, worst first: NPCs + dialogue (11 to 33), Lore (12 to 19), Items + loot (15
  to 31), Quests (20 to 39), Building styles (30 to 49; Morrowind 80), Settlements (34 to 61), Creatures (32 to 63),
  Dungeons (52 to 60), Landmarks (55 to 69), Music (48 to 54).
* Flora and biomes are the one category at 77 to 92 everywhere: the generator work already paid off.
* Dungeons score 52 to 60 only because of the weakest-column rule: counts, themes and room templates are at Skyrim's
  level, but **no** dungeon outside Skyrim is hand-authored and 98% have no identity.
* Skyrim totals for the bar: 158 named NPCs and 308 quests on 18 named towns (8.8 NPCs and 17.1 quests per town),
  263 dungeons and forts of which **all 263** carry a hook, quest or unique, 377 places with lore, 818 notes and 662
  place books. Outside Skyrim: ~2,650 dungeons and forts, **54** with any hook (2%); 160 named towns with 299 named
  NPCs (1.9 per town) and 357 quests (2.2 per town), against Skyrim's 8.8 and 17.1.

## 3. The 20 biggest deficits

Ranked by size of the hole times how visible it is to a player. Numbers are province values against Skyrim's
(`--deficits 40` prints the raw ranking by sub-metric).

| # | Deficit | Now (outside Skyrim) | Skyrim | 80% floor |
| --- | --- | --- | --- | --- |
| 1 | Dungeons and forts with a hook, rumour, quest or unique (`d_identity`) | 0 to 8% (Morrowind 8%, Cyrodiil 2%, rest under 1%) | 100% | 80% |
| 2 | Places with a lore entry per km2 (`r_places`) | 0.4 to 2.1 | 14.2 | 11.4 |
| 3 | Readables and place books per km2 (`r_notes`) | 1.2 to 7.1 | 55.9 | 44.7 |
| 4 | Named NPCs per named town (`s_npcs`) | 1.2 to 3.9 (Cyrodiil 5.9) | 8.8 | 7.0 |
| 5 | Dialogue + scene lines per named town (`n_lines`) | 2.6 to 8.4 (Cyrodiil 18) | 21.8 | 17.4 |
| 6 | NPC scenes per named town (`n_scenes`) | 0.7 to 1.4 (Morrowind 2.6) | 6.0 | 4.8 |
| 7 | Hand-authored dungeons per region (`d_marquee`) | 0 in every province | 0.78 per hold | 0.62 |
| 8 | Quests per named town (`q_per_town`) | 1.1 to 1.8 (Morrowind 5.0, Cyrodiil 8.8) | 17.1 | 13.7 |
| 9 | Quests per km2 (`q_total`) | 0.6 to 2.9 | 11.6 | 9.3 |
| 10 | Region-keyed chat/say lines per region (`n_say`) | 0.1 to 0.5 | 4.8 | 3.8 |
| 11 | Signature gear families per province (`i_sets`) | 1 to 4 | 22 | 18 |
| 12 | Cave creature kinds per region (`c_cave`; Summerset 0, Valenwood 3, Elsweyr and Black Marsh 5) | 0 to 9 | 15 | 12 |
| 13 | Bespoke settlement layouts, share of named towns (`s_bespoke`) | 5 to 12% (Cyrodiil 33%, Morrowind 29%) | 67% | 53% |
| 14 | Named buildings per named town (`s_bldgs`) | 0.3 to 0.9 (Cyrodiil 1.9, Morrowind 1.8) | 3.9 | 3.1 |
| 15 | Distinct creature kinds per region (`c_total`) | 10 to 19 | 31 | 25 |
| 16 | Legendary uniques per province (`i_unique`); explore uniques per km2 (`l_unique`) | 3 to 4; 0.05 to 0.12 | 8 ; 0.34 | 6.4 ; 0.27 |
| 17 | Style-sheet richness (`y_sheet`) | 102 to 349 leaves | 541 | 433 |
| 18 | Hostile kinds per biome family (`c_hostile`) | 4.5 to 8.7 (Summerset 4.5, Elsweyr 4.5) | 9.9 | 7.9 |
| 19 | Exploration music tracks per province (`m_explore`) and town tracks (`m_town`) | 2 ; 1 | 5 ; 2 | 4 ; 1.6 |
| 20 | Rumours + legends + songs per region (`r_flavor`) | 4.4 to 8 (all province-level lists) | 12.3 | 9.9 |

Three of these (1, 2, 3) are one problem: **place identity**. A generator that writes a hook, a rumour and a short
note per place from the place's own facts closes most of them at once. Batch PA3 below.

## 4. Where Skyrim itself is thin (exceed it, do not copy it)

* **Carved dungeons:** only 4.1 palettes and 5.7 inhabitant rosters per hold; every cave in a hold is the same Nord
  stone with one of five rosters. Target 8 palettes per province (owner's example) and 8 rosters.
* **Hand-built interiors:** 7 of 263 dungeons (2.7%) are hand-authored. Skyrim's feel is the named-place coverage, not
  the set pieces; outside Skyrim we can only afford ~1 per region, but each should be unlike any other.
* **Wildlife:** 2.6 kinds per biome family against 9.9 hostile. Wildlife (and passive fauna with behaviours) is
  where non-Skyrim biomes can read as alive. Target 3 per family.
* **Radiant quests:** Skyrim has none (hand-written place quests only); the 33 templates (17 culture-specific) exist
  only outside Skyrim. Target 40+ templates with region tokens so every province generates its own errands.
* **Music:** 5 exploration tracks for the whole of Skyrim; no hold has its own. Target 4 per province and a distinct
  piece for each Skyrim hold, Solstheim and the great cities.
* **Region voice:** region-keyed "tell me about this place" and gossip lines exist for 10 regions (the nine holds and
  Solstheim); the other 61 regions fall back to one line per province. Give every region, Skyrim's included, 4 to 6.
* **Skyrim's weakest holds** (proof the bar is not even there): The Pale DUN 46 and NPC 45, Haafingar, Whiterun and
  Eastmarch DUN 51 to 53, Falkreath NPC 52, Winterhold SET 54, Hjaalmarch NPC 56. Fill those too; they are cheap and
  keep Skyrim at the top.
* **Style kits:** Skyrim's nine capitals have their own kits; everywhere else a culture sheet plus (Dunmer only) four
  hashed kits. A per-place variant lookup (hash of the id over 2 to 4 sub-kits, region overlays on top) is cheaper than
  new plans and works for every culture, Skyrim's villages included.

## 5. Floors and targets

Floor = 80% of Skyrim per area (or per town / per region as the kind says). **Skyrim means the real game where we
can count it** (changed 2026-10-12): the four bold rows below were measured from the owner's Skyrim SE install (nine
holds, facts and counts only, method in `docs/world/DESIGN-PRINCIPLES.md` 4.1) and replace the weaker numbers our own
Skyrim had (8.8 NPCs and 3.9 named buildings per named town, 11.6 quests/km2, 17.1 per town). `parity.py` takes the
larger of our Skyrim and the measured value (`SKYRIM_MEASURED`), so our Skyrim holds now score under 100 on SET and
QST too: they have to be filled as well. The full per-hold baseline (dungeons by theme, settlements by tier, NPCs,
services, quests and collectibles per km2) is in `DESIGN-PRINCIPLES.md` 4.1; the region template (4.3 to 4.8) uses it. "Stage 1" is what the first batch must
reach per region so the next agent has something to build on; "exceed" is where Skyrim is thin. All checks are
`py -3 -I tools/qa/parity.py --assert-metric <ids>` on the metric ids shown (exit 1 lists regions below floor).

| metric | Skyrim | floor (80%) | stage 1 | exceed |
| --- | --- | --- | --- | --- |
| `d_identity` share of dungeons/forts with hook, quest or unique | 100% | 80% | 50% | 90% |
| `r_places` lore places per km2 | 14.2 | 11.4 | 4 | 14 |
| `r_notes` readables + books per km2 | 55.9 | 44.7 | 8 (1 per lore place) | 56 |
| `s_npcs` named NPCs per named town | **46.9** (real game; our Skyrim 10.4) | 37.5 | 15 | 47 |
| `n_lines` lines per named town | 21.8 | 17.4 | 10 | 22 |
| `n_scenes` scenes per named town | 6.0 | 4.8 | 2 | 6 |
| `n_roles` distinct roles per region | 10.2 | 8.2 | 6 | 10 |
| `n_say` region-keyed lines per region | 4.8 | 3.8 | 4 | 6 |
| `q_per_town` quests per named town | **20.6** (real game; ours 18.6) | 16.5 | 8 | 21 |
| `q_total` quests per km2 | **14.0** (real game; ours 12.6) | 11.2 | 3 | 14 |
| `q_kinds` kinds present per region | 3.9 | 3.1 | 3 | 4 |
| `q_placed` share on registered places | 0.93 | 0.75 | 0.60 | 0.9 |
| `d_caverns` carved palettes per region | 4.1 | 3.3 | 5 | 8 |
| `d_rosters` inhabitant rosters | 5.7 | 4.5 | 8 | 10 |
| `d_marquee` hand-authored per region | 0.78 | 0.62 | 0.5 | 1.0 (every region >= 1) |
| `c_hostile` kinds per family | 9.9 | 7.9 | 7 | 10 |
| `c_wild` wildlife kinds per family | 2.6 | 2.0 | 2 | 3 |
| `c_cave` cave kinds | 15 | 12 | 10 | 14 |
| `c_total` kinds per region | 31 | 25 | 22 | 30 |
| `f_biomes` / `f_trees` / `f_plants` | 26 / 29 / 27 | 21 / 23 / 22 | 19 / 21 / 21 | 24 / 26 / 26 |
| `y_looks` distinct settlement looks per region | 2.7 | 2.1 | 2 | 3 |
| `y_sheet` style-sheet leaves | 541 | 433 | 300 | 500 |
| `s_bespoke` bespoke share of named towns | 67% | 53% | 25% | 60% |
| `s_bldgs` named buildings per named town | **11.7** (real game; ours 5.3) | 9.4 | 4 | 12 |
| `l_kinds` landmark kinds per region | 12.9 | 10.3 | 10 | 14 |
| `l_unique` explore uniques per km2 | 0.34 | 0.27 | 0.15 | 0.34 |
| `r_flavor` rumours + legends + songs | 12.3 | 9.9 | 8 | 12 |
| `m_explore` / `m_town` / `m_amb` | 5 / 2 / 24 | 4 / 1.6 / 19 | 3 / 1 / 18 | 5 / 2 / 22 |
| `i_sets` signature gear families | 22 | 18 | 8 | 12 (per province; the faction sets count) |
| `i_ingr` / `i_trink` / `i_unique` | 65 / 6 / 8 | 52 / 4.8 / 6.4 | 30 / 4 / 4 | 52 / 5 / 6 |

Honest note on the per-area floors: Skyrim packs its content into 26.5 km2, so an 80% per-km2 floor on a 61 km2
province (Cyrodiil) means 570 quests and 2,700 readables. That is only reachable with generators plus a radiant layer;
hand writing alone gets stage 1. The batches below say which is which.

## 6. Backlog, prioritised and batched

Sizes: S = a day of one agent's work or less; M = a few days; L = a week-scale batch. "Data" = JSON/JS/Python, no Java.
Each batch owns files nobody else in its wave touches. At most 3 batches at once (FINISH-PLAN). Every batch ends with
`py -3 -I tools/qa/data_checks.py`, its own domain tool, the parity assert shown, and (for anything the server loads)
`tools\qa\qa.ps1 -Server -NoBuild`.

### Wave 0: enablers (do first, parallel, all S)

| Batch | Scope | Owns | Size | Verified by | Done means |
| --- | --- | --- | --- | --- | --- |
| **PA0 Region voice hook** | Make `NpcDialogue.aboutRegion` and `Chatter` gossip data-driven: a JSON list of region ids that have own lines instead of the two hard-coded sets; fall back region -> province -> unknown. | `society/NpcDialogue.java`, `society/Chatter.java`, new `resources/skycraft/society/region_voice.json` | S (Java) | compile + `qa.ps1 -Server`; `parity.py` `n_say` | Adding `say.region.<id>` + id in the JSON is enough; no Java edits for later fills. |
| **PA1 Per-province loaders** | `explore/Uniques.java` reads `explore/uniques*.json`; confirm `tools/place_lore.js` merges `regions` blocks of several lore files and `lore/places/index.json` unions; otherwise fix. | `explore/Uniques.java`, `tools/place_lore.js` | S | `node tools/place_lore.js`, qa.ps1 -Server | Two lore files naming the same province both load; two uniques files both load. |
| **PA2 Novelty lint** | `tools/qa/novelty_lint.py`: flags near-duplicate text across lore hooks, rumours, greetings, quest pitches and lang lines (4-gram Jaccard above 0.55 within a province, shared sentence skeleton above 3 uses), plus per-template reuse counts. Wire as an optional `qa.ps1 -Novelty` switch. | `tools/qa/novelty_lint.py`, `tools/qa/qa.ps1` (one switch) | S | runs in under 10 s; first run records the baseline (Skyrim's hand-written text is the reference for "acceptable") | Every generator batch below must pass it at 0 pairs. This is the "never copy-paste" gate. |

**Wave 0 status (enablers in place).**
* PA0: `society/RegionVoice.java` reads `resources/skycraft/society/region_voice.json` (`regions`, `provinces`, `gossip`
  variant counts). `NpcDialogue.aboutRegion` and `Chatter` use it (region -> province -> `say.region.unknown`). To add a
  region: lang keys `say.region.<id>` / `chat.gossip_<id>.<v>.<0..2>` plus its id in the JSON; no Java edit. Note
  `parity.py` `n_say` still reads only `society.json`; PA10's `society_<province>.json` needs that counter widened.
* PA1: `explore/Uniques.java` loads `uniques.json` plus every file listed in `explore/uniques_index.json` (a JSON array
  of names; append). `tools/place_lore.js` and `PlaceLore.java` now merge a region written in several lore files (rumours,
  legends and songs append, key indices continue) and the lore index is a union. `parity.py` still counts uniques from
  `uniques.json` only (widen it when the first `uniques_<province>.json` lands).
* PA2: `py -3 -I tools/qa/novelty_lint.py [--top N] [--strict]`, or `qa.ps1 -Novelty`. Baseline on current content
  (9,001 texts, 0.4 s): 4 near-duplicate pairs (quest objective lines such as "Pray at the Chapel of ..." and bark
  repeats), 2 shared skeletons ("pray at the X of X in X", "go to the shrine of X north of X"). Generators must keep
  `--strict` at 0 pairs and 0 skeletons beyond this baseline.

### Wave 1: depth per place (data; the biggest score movers; parallel by province)

| Batch | Scope | Owns | Size | Verified by | Done means (per region unless stated) |
| --- | --- | --- | --- | --- | --- |
| **PA3 Place identity generator** (also closes marquee B16/B3 for non-marquee sites) | `tools/lore/gen_place_lore.js`: for every dungeon, fort, landmark, shrine, village and farm without lore, write a hook, a rumour and (40%) a short readable from the place's own facts: variant, kit, region name, nearest settlement (by coordinates), biome family, who lives there (roster/boss from the structure JSON), what is lost (loot unique, quest), plus region rumour pools. At least 12 phrasings per variant per culture and clause-level combinators, never one template with a swapped noun. Output one file per province: `lore/places/gen_<province>.json`. Then `node tools/place_lore.js`. | `tools/lore/gen_place_lore.js` (+ `tools/lore/pools/<culture>.json`), `lore/places/gen_*.json`, `lore/places/index.json` (append) | L (generator M, then 8 province runs S) | `node tools/place_lore.js`; `novelty_lint.py` = 0 pairs; `parity.py --assert-metric d_identity,r_places,r_notes` | `d_identity` >= 0.50 (stage 1) then 0.80; `r_places` >= 4 per km2 (stage 1) then 11; `r_notes` >= 8 per km2 (stage 1) then 20; `r_flavor` unchanged. |
| **PA4 Town life: High Rock** | For the 29 named towns: named NPCs (role mix: innkeeper, smith, priest, guard captain, 2 citizens with a personal problem, 1 secret), greetings with 2 variants, 2 scenes per town, 6 quests per town (side: town problem x dungeon from PA3 x boss from the region roster; plus 1 hidden `find` quest), named inn and shop sign keys. Region flavour: Breton court politics, orc stronghold border, Direnni ruins. | `quests/town_high_rock_*.json`, `lore/places/town_high_rock.json`, lang is generated by `node tools/quest_lang.js` | L | `node tools/quest_lang.js`; `qa.ps1 -Server` (`/skycraft-quest selftest flow`); `novelty_lint.py`; `parity.py --assert-metric s_npcs,n_lines,n_scenes,q_per_town,q_placed` | Stage 1 per named town: `s_npcs` >= 4, `n_lines` >= 10, `n_scenes` >= 2, quests >= 6, >= 1 hidden quest per region, `q_placed` >= 0.6. High Rock needs +81 NPCs, +31 scenes, +136 quests for stage 1 (to floor: +169, +112, +359). Counts below are the same arithmetic (4 NPCs, 2 scenes, 6 quests per named town, minus what exists). |
| **PA5 Town life: Hammerfell** | As PA4 for 28 named towns (Ra Gada, Crown/Forebear split, Alik'r nomads, Ansei). | `quests/town_hammerfell_*.json`, `lore/places/town_hammerfell.json` | L | as PA4 | As PA4. Needs +78 NPCs, +36 scenes, +138 quests for stage 1 (to floor: +163, +114, +353). |
| **PA6 Town life: Elsweyr** | As PA4 for 18 named towns (moon-sugar trade, caravans, Pellitine/Anequina rivalry, Mane). | `quests/town_elsweyr_*.json`, `lore/places/town_elsweyr.json` | M | as PA4 | +49 NPCs, +17 scenes, +79 quests (to floor: +103, +67, +217). |
| **PA7 Town life: Valenwood + Summerset** | 18 + 16 named towns (Green Pact rites, tree-cities, Maormer raids; Alinor politics, Psijic). | `quests/town_valenwood_*.json`, `quests/town_summerset_*.json`, `lore/places/town_{valenwood,summerset}.json` | L | as PA4 | Valenwood +48 NPCs, +19 scenes, +84 quests; Summerset +39, +10, +67 (to floor: +102/+87, +69/+55, +222/+190). |
| **PA8 Town life: Black Marsh** | 20 named towns (Hist, Shadowscale, Dunmer border, Helstrom). | `quests/town_black_marsh_*.json`, `lore/places/town_black_marsh.json` | M | as PA4 | +57 NPCs, +25 scenes, +97 quests (to floor: +117, +81, +251). |
| **PA9 Town life: Morrowind + Solstheim + Cyrodiil** | Morrowind 21 (houses, Tribunal, Ashlanders; 12 towns have no quest today), Cyrodiil 9 (needs only the quest and named-building top-up), Solstheim stays as is. | `quests/town_morrowind_*.json`, `quests/town_cyrodiil_*.json`, `lore/places/town_{morrowind,cyrodiil}.json` | M | as PA4 | By count both provinces already meet stage 1 (Morrowind +2 NPCs, +21 quests; Cyrodiil none), so the job is distribution and floor: every named town gets >= 4 NPCs and >= 6 quests (`--assert-metric` at region level catches Great Forest, Red Mountain, Bitter Coast, Grazelands, Stonefalls). To floor: Morrowind +65 NPCs, +46 scenes, +182 quests; Cyrodiil +10, +0, +44 (named buildings +11). |
| **PA10 Region voice** | For all 71 land regions (and Skyrim's holds too): 4 keyed say/gossip lines (about, rumour, work, weather), 6 rumours + 3 legends + 3 songs per region (region blocks of the lore files), tied to region biome/culture/landmarks. | `lang/skycraft/society_<province>.json` (new, one per province group), `lore/places/regions_<province>.json`, `resources/skycraft/society/region_voice.json` (append) | M | `parity.py --assert-metric n_say,r_flavor` | `n_say` >= 4 and `r_flavor` >= 12 in every region. Needs PA0, PA1. |

PA4 to PA9 are disjoint by file and province; each agent may also add `side_<province>_places`-style place quests,
but quest ids must be prefixed `tl_<province>_` so merge never collides.

### Wave 2: dungeons and creatures (code or mixed; sequence inside a file, parallel across files)

| Batch | Scope | Owns | Size | Verified by | Done means |
| --- | --- | --- | --- | --- | --- |
| **PA11 Carved looks and rosters** | A data-driven look registry for carved dungeons (`dungeons/looks/*.json`): palette (floor, wall, ceiling, accent), ceiling/floor features (stalactites, roots, ice pillars, lava channels, glow veins, bone drifts, salt crust, coral), ambience bed and roster, keyed by biome family and region. Wire `Kit`/`NaturalBuilder` to it; `structure/*.json` gain `"look"`; update `parity.py` `collect()` to count looks from that field. Needs: ice, ash/obsidian, sandstone/desert, overgrown/root, mangrove/flooded, crystal, fungal, sea-cave/coral, bone, salt, volcanic tube. | `dungeons/world/{Kit,NaturalBuilder,NaturalRooms,CaveVariants,CaveRooms}.java`, `dungeons/looks/*.json`, `structure/**.json` (`look` field), `tools/qa/parity.py` (the look counter only) | L (Java) | `py -3 -I tools/qa/dungeon_cavern_view.py` contact sheet reviewed by Haiku (one sheet per province), `dungeon_stats.py`, `dungeon_reach.py`, `qa.ps1 -Server`; `parity.py --assert-metric d_caverns,d_rosters` | >= 8 carved palettes per major province (Cyrodiil, Morrowind, High Rock, Hammerfell, Black Marsh) and >= 5 per other region; >= 8 rosters; no two palettes in one region share a floor+wall pair. |
| **PA12 Built looks and room templates** | Per-kit room templates in `CultureRooms` and `FortRooms`: >= 6 per kit (Ayleid, Velothi, Xanmeer, Redguard, Breton, Khajiit, Bosmer, Altmer, Orc, Akaviri), each with its own set piece; trap and puzzle set per culture (`Puzzles`, `Traps`). One agent, because the files are shared. | `dungeons/world/{CultureRooms,FortRooms,SetPieces,Traps,Puzzles,BarrowRooms,DwemerRooms}.java` | L (Java) | `dungeon_audit.py` (audit sum >= 26/35 on 5 non-Skyrim dungeons), `dungeon_reach.py`; `parity.py --assert-metric d_built,d_rooms` | `d_built` >= 5 per region, `d_rooms` >= 40 reachable, every kit has >= 6 templates. |
| **PA13 Hand-authored dungeons (marquee)** | Marquee B2 (loader) then B10 to B14 (`docs/MARQUEE-PLAN.md`): the 58 dungeon entries of `tools/marquee/marquee.json` (B10 to B14 list ~50 of them). Add a "mini-marquee" pass for the regions that still have none afterwards (one set piece + one mechanism + named boss + lectern). | `dungeons/world/MarqueePlans.java`, `Special.java`, `resources/.../dungeons/marquee/*` | L | as MARQUEE-PLAN; `parity.py --assert-metric d_marquee` | >= 1 hand-authored dungeon in every land region; each with a mechanism no other dungeon has. |
| **PA14 Creature ecology (data)** | Per province, fill `region_spawns/<province>.json` from the 83 existing kinds: hostile >= 8 per biome family present, wildlife >= 3, cave >= 12, levels banded, `time` and `place` varied, signature groups with named members (e.g. Elsweyr: pack of cats with a Mane). Add `families` blocks so Black Marsh swamp differs from Black Marsh river. One agent per 2 provinces. | `region_spawns/{cyrodiil,morrowind,high_rock,hammerfell,elsweyr,valenwood,summerset,black_marsh,atmora,akavir,yokuda,southern_isles}.json` | M | `parity.py --assert-metric c_hostile,c_wild,c_cave,c_total`; `qa.ps1 -Server` (spawn tables load) | per region: `c_hostile` >= 8, `c_wild` >= 2, `c_cave` >= 12, `c_total` >= 25. |
| **PA15 New creature kinds** | >= 4 new region-signature creatures per major province (hostile, wildlife and one rare/boss variant each) with models, loot and sounds; reuse the existing spec pipeline. Sequential inside `BestiarySpecs`/`ModEntities`; art with `tools/textures`. | `creatures/{BestiarySpecs,ModEntities,CreatureItems}.java`, `creatures/spec/*`, `tools/textures/*` (new), `lang/skycraft/creatures.json` | L (Java + art) | `tools/audit_entity_renderers.py`, `check_assets.py`, `qa.ps1 -Server`; `parity.py --assert-metric c_total` | >= 4 species unique to each major province; each has a spawn rule from PA14. Needs PA14 first. |

### Wave 3: world texture (mixed; mostly parallel by file)

| Batch | Scope | Owns | Size | Verified by | Done means |
| --- | --- | --- | --- | --- | --- |
| **PA16 Style sheets and sub-kits** | Grow each culture's `styles/<culture>.json` to >= 430 leaves (materials per wealth tier, roofs, windows, banners, signs, yard sets, forms) and add 2 to 4 sub-kits per culture selected by a hash of the place id plus region overlays (Stormhaven vs Rivenspire vs Glenumbra). 14 files; split across 3 agents by culture. | `tamriel/styles/<culture>.json`, `tamriel/styles/overlays/<region>.json`, `settlement/grammar/{Style,Styles,Overlays}.java` (additive) | M each | `town_checks.py`, `render_town.py` contact sheets (Haiku), `layout_lint.py`; `parity.py --assert-metric y_looks,y_sheet` | `y_sheet` >= 430 for every culture with a settlement; `y_looks` >= 2 per region; no two cultures share a roof set. |
| **PA17 Settlement identity (marquee B4 to B9, B17)** | Existing plan: unique plans and named buildings for the 69 settlement entries of `marquee.json` (B1 already merged: 18 plans), then B17 for the other named towns. | as MARQUEE-PLAN | L (per wave) | as MARQUEE-PLAN; `parity.py --assert-metric s_bespoke,s_bldgs` | `s_bespoke` >= 0.25 stage 1, 0.53 floor; `s_bldgs` >= 3 (named inn + shop + smithy sign from `cityplans` names, also for generic towns via a name generator). |
| **PA18 Landmarks and uniques** | Marquee B15 (43 landmarks) plus `explore/uniques_<province>.json`: a unique per 4 km2 (dead adventurer, shrine cache, named tree, monument) each with a note and a reward. Landmark kinds >= 10 per region (Morrowind is the low one at 6.7). | `explore/uniques_<province>.json`, `lore/places/landmarks_<province>.json`, `build/landmarks/*Landmarks.java` (marquee I3) | M | `explore/density_audit.py`; `parity.py --assert-metric l_unique,l_kinds` | `l_unique` >= 0.15 per km2 stage 1 then 0.27; `l_kinds` >= 10 per region. |
| **PA19 Flora and biome top-up** | The only places under floor: Hammerfell trees 21, Summerset trees 20, Black Marsh trees 21, Cyrodiil biomes 17.3. Add region-specific tree/flora species and 1 to 3 biomes each via `tools/worldgen/biomes.py` and the flora generator. | `tools/worldgen/biomes.py`, `tools/worldgen/noise_settings.py`, `tamriel/biome/flora/*`, `data/skycraft/worldgen/**` (generated) | M | `region_audit.py`, `region_render.py`; `parity.py --assert-metric f_biomes,f_trees,f_plants` | every region >= 21 biomes, >= 23 trees, >= 22 flora. |
| **PA20 Music and ambience** | 2 more exploration pieces per province (day variants; a night variant for Skyrim's holds), a second town piece for every culture, 6 more ambience beds keyed to biome family (savanna night, tundra, heath, salt flat, cloud forest, sulphur). Skyrim holds get their own pieces to exceed. | `tools/music/*`, `assets/skycraft/sounds/{music,ambient}/*`, `assets/skycraft/sounds.json`, `lang/skycraft/audio.json`, `world/client/MusicController.java` | L (generation) | `sound_catalog.py --check`; `parity.py --assert-metric m_explore,m_town,m_amb` | `m_explore` >= 4 per province, `m_town` >= 2 per culture, `m_amb` >= 19 per region. |
| **PA21 Signature gear, uniques and loot** | 10 signature gear families per province from faction and region (Redoran, Hlaalu, Telvanni, Morag Tong, Ordinator; Knights of the Nine, Blades; Crowns/Forebears; Green Pact; Psijic; Hist; Direnni...), +3 legendary uniques per province, 2 trinkets and 12 ingredients each. Per-province tag files are disjoint; `LegendaryUniques.java` has one owner. | `tags/items/gear/<province>.json`, `tags/items/loot/{ingredients,trinkets}/<province>.json`, `loot/LegendaryUniques.java`, `gear/*`, `tools/textures/*`, `lang/skycraft/gear.json`, `loot.json` | L (art-heavy) | `audit_items.py`, `check_assets.py`, `loot_sim.py`; `parity.py --assert-metric i_sets,i_ingr,i_trink,i_unique` | `i_sets` >= 8 stage 1, 12 target; `i_unique` >= 6; `i_ingr` >= 30. |
| **PA22 Radiant layer** | Grow `radiant_templates.json` from 33 to 60 with region tokens (region, nearest landmark, resident, rival, weather), per-culture voice, and the PA3 dungeons as destinations. Skyrim gets the same layer to exceed. | `quests/radiant_templates.json`, `quest/story/RadiantTemplates.java` (small) | M | `quest_lang.js`, `selftest flow`; `novelty_lint.py` | >= 40 templates valid per culture; `q_per_town` stage 2 through radiants (count them in `parity.py` once the generator has a stable per-town offer count). |

### Order and parallelism

1. Wave 0 first (PA0, PA1, PA2; one day, 3 agents).
2. Then PA3 (generator) alone or with PA10 and PA14 (three at once). PA3 is the single biggest score mover:
   DUN `d_identity`, LOR `r_places` and `r_notes` all jump from the teens to the 60s in one batch.
3. PA4 to PA9 as three-at-a-time rolling province batches (Cyrodiil, Morrowind first because their quests already exist
   and 12 Morrowind towns have no quest; then High Rock and Hammerfell, the worst by size).
4. PA11 (Java, one agent) in parallel with data batches. PA12 after PA11 (different files, but share the room painter).
5. PA13 follows MARQUEE-PLAN B2.
6. PA15 only after PA14. PA16, PA18, PA19, PA20, PA21 are independent of each other and of the data waves.

### Conflict map (who owns what)

| file / folder | owner batch |
| --- | --- |
| `lore/places/gen_<province>.json` | PA3 |
| `lore/places/town_<province>.json`, `quests/town_<province>_*.json` | PA4 to PA9 (one province each) |
| `lore/places/regions_<province>.json`, `lang/skycraft/society_<province>.json` | PA10 |
| `region_spawns/<province>.json` | PA14 |
| `dungeons/looks/*`, `Kit`, `NaturalBuilder`, `NaturalRooms`, `CaveVariants`, `CaveRooms` | PA11 |
| `CultureRooms`, `FortRooms`, `SetPieces`, `Traps`, `Puzzles`, `BarrowRooms`, `DwemerRooms` | PA12 |
| `MarqueePlans`, `Special`, `dungeons/marquee/*` | PA13 |
| `BestiarySpecs`, `ModEntities`, `creatures/spec/*` | PA15 |
| `tamriel/styles/*`, `styles/overlays/*` | PA16 |
| `cityplans/*`, `plan/Cities.java`, `build/landmarks/<Province>Landmarks.java` | PA17, PA18 (landmark builders only) |
| `explore/uniques_<province>.json` | PA18 |
| `tools/worldgen/biomes.py` | PA19 |
| `tools/music/*`, `sounds.json` | PA20 |
| `tags/items/gear/<province>.json`, `LegendaryUniques.java` | PA21 |
| `quests/radiant_templates.json` | PA22 |
| `tools/qa/parity.py` | parity maintainer; PA11 may touch only the look counter, PA22 only the radiant counter |

Shared index files (`quests/index.json`, `lore/places/index.json`, `places/index.json`) are union-merged as in
FINISH-PLAN section 0; append, never rewrite.

## 7. Definition of done for the whole program

`py -3 -I tools/qa/parity.py --assert-floor SET,NPC,QST,DUN,CRE,FLO,STY,LND,LOR,MUS,ITM` exits 0 (no non-Skyrim
region below 80 in any category), `novelty_lint.py` reports 0 near-duplicate pairs, `qa.ps1 -Server` passes, and the
final visual check (contact sheet per province, Haiku review, then one owner playtest) shows no province that reads
as a reskin of another.

## 8. What the numbers do not capture

* Quality: a hook can exist and be dull. `novelty_lint.py` and the Haiku review are the quality gates, not parity.py.
* `d_caverns` counts (theme x kit palette) from the structure JSON, which is the visual identity today; it does not
  see interior variety (rooms, set pieces) beyond `d_rooms`, which is a global count per theme. PA11 replaces it with
  look counts read from the new registry.
* `m_amb` is derived from the region's biome families through a hand table (the real engine picks beds at run time);
  `m_explore`/`m_town` count the files that exist for the region's music set.
* Creature kinds are read from `region_spawns` rules (provinces, regions, families); Java-side spawns (fauna module,
  dragons, quest-private bosses, faction defenders) are not counted, so Skyrim and the others are both slightly low.
* Biome families come from the id keywords and spawn tags, a port of `BiomeFamily.resolve`; elevation bands are not
  modelled, so a region's palette may list a peak biome it rarely shows.
* Quest region assignment: explicit `region`, else the start NPC's settlement, else the first objective place (settlement
  or quest-private place by coordinates), else the file's region. Faction quests are attributed to where their steps
  happen, so provinces with chapter houses (Morrowind, Cyrodiil) gain.
* Named buildings for Java-built cities are estimated from `named(...)` calls in the layout method plus the names
  record; the `cityplans/*.json` ones are counted from landmarks, plaza and building names.
