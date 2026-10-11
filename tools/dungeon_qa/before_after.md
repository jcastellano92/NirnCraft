# Dungeon QA: before vs after

## All dungeons

| metric | before | after |
|---|---:|---:|
| dungeons | 462 | 462 |
| blockers | 140 | 0.00 |
| rooms reached % | 96.4 | 100 |
| critical path (blocks) | 69.9 | 87.1 |
| walk to boss (s) | 20.0 | 24.0 |
| vertical travel on path | 23.4 | 30.9 |
| enemies on path | 8.1 | 10.8 |
| ramp to boss (Spearman) | 0.64 | 0.71 |
| boss threat / strongest room | 3.6 | 3.1 |
| boss present % | 100 | 100 |
| elite guard % | 0.00 | 87.2 |
| ambush beat % | 0.00 | 28.4 |
| traps per dungeon | 1.1 | 4.3 |
| dungeons with a trap % | 26.0 | 97.2 |
| dark walkable % | 13.4 | 3.1 |
| mean light | 6.8 | 7.7 |
| loot value (gold) | 1839 | 2020 |
| boss chest value | 1114 | 1160 |
| side branch with reward % | 85.3 | 93.7 |
| empty dead ends | 0.17 | 0.00 |
| loop-back exit % | 3.2 | 95.9 |
| exit / way-in ratio | 1.3 | 0.61 |
| extra cycles | 0.17 | 0.20 |
| foreshadowing window % | 0.00 | 15.4 |
| journals & notes | 0.00 | 0.82 |
| set-piece rooms | 0.00 | 1.9 |
| layout similarity (nearest) | 0.56 | 0.55 |
| content similarity (nearest) | 0.44 | 0.28 |
| design checklist (0-9) | 4.1 | 8.6 |

## Per theme (before -> after)

| theme | blockers | critical path (blocks) | ramp to boss (Spearman) | dungeons with a trap % | dark walkable % | side branch with reward % | loop-back exit % | journals & notes | set-piece rooms | design checklist (0-9) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| barrow | 0.00 -> 0.00 | 90.0 -> 106 | 0.83 -> 0.76 | 52.5 -> 100 | 3.3 -> 3.1 | 94.9 -> 100 | 0.00 -> 89.8 | 0.03 -> 0.95 | 0.00 -> 3.0 | 4.5 -> 8.8 |
| cave | 10.0 -> 0.00 | 60.3 -> 78.5 | 0.45 -> 0.71 | 0.00 -> 100 | 59.9 -> 4.7 | 85.5 -> 98.8 | 8.4 -> 95.2 | 0.00 -> 0.83 | 0.00 -> 1.9 | 3.9 -> 8.9 |
| dwemer | 0.00 -> 0.00 | 94.4 -> 103 | 0.84 -> 0.76 | 37.0 -> 100 | 0.00 -> 0.00 | 100 -> 100 | 0.00 -> 96.3 | 0.00 -> 0.96 | 0.00 -> 3.0 | 4.4 -> 8.9 |
| fort | 0.00 -> 0.00 | 41.9 -> 57.0 | 0.82 -> 0.73 | 21.4 -> 71.4 | 2.1 -> 6.9 | 54.8 -> 66.7 | 0.00 -> 95.2 | 0.00 -> 0.31 | 0.00 -> 0.36 | 3.8 -> 6.7 |
| hagraven_lair | 0.00 -> 0.00 | 55.5 -> 59.6 | 0.66 -> 0.73 | 0.00 -> 100 | 9.1 -> 12.1 | 73.7 -> 78.9 | 5.3 -> 94.7 | 0.00 -> 0.42 | 0.00 -> 0.95 | 3.8 -> 7.3 |
| mine | 3.0 -> 0.00 | 58.8 -> 78.6 | 0.57 -> 0.80 | 0.00 -> 100 | 1.1 -> 2.8 | 88.0 -> 100 | 12.0 -> 96.0 | 0.00 -> 0.88 | 0.00 -> 2.0 | 4.0 -> 8.9 |
| necromancer_lair | 0.00 -> 0.00 | 52.0 -> 75.9 | 0.54 -> 0.72 | 0.00 -> 95.2 | 2.4 -> 0.82 | 76.2 -> 90.5 | 14.3 -> 95.2 | 0.00 -> 0.67 | 0.00 -> 1.5 | 3.9 -> 8.2 |
| ruin | 127 -> 0.00 | 78.0 -> 97.4 | 0.63 -> 0.65 | 41.7 -> 100 | 2.0 -> 0.75 | 88.7 -> 97.0 | 0.00 -> 98.2 | 0.00 -> 0.96 | 0.00 -> 2.0 | 4.2 -> 8.9 |
| vampire_lair | 0.00 -> 0.00 | 52.9 -> 68.7 | 0.55 -> 0.85 | 0.00 -> 100 | 11.6 -> 5.8 | 88.9 -> 83.3 | 5.6 -> 100 | 0.00 -> 0.61 | 0.00 -> 1.4 | 3.9 -> 7.9 |
