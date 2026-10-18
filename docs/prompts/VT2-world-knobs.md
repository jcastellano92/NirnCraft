# VT2 World knobs: the general features the plates need

Read `docs/prompts/_COMMON.md`, `docs/world/VISUAL-TARGETS-PLAN.md` section 4 and the workflow first. Each card is
one board task (`VT2.00.knob.<name>`). Build each knob general from the start (data first, code once), prove it on
its Volume 01 pilot targets with L1 probes, then hand it to the volume passes. Worlds are regenerated; no save
compatibility; delete legacy code instead of keeping both paths.

| Card | Resource | Pilots (Volume 01) | Done when |
| --- | --- | --- | --- |
| `caves` (4.3) | `res:noise`, `res:bake` | random cave volume in Skyrim | noise caves and aquifers above sea level gone; `sweep.random_cave_volume == 0`; authored caverns, dungeons and Blackreach intact |
| `cave-grammar` (4.8) | none (dungeon module) + `res:lang:dungeons` | 6 Skyrim caves of different purposes | `docs/world/CAVES.md` + `skycraft/dungeons/caves/*.json`; caves planned per region with entrance stamps, guided route, loop back, placed enemies and loot, story props, lore links; dungeon QA bot walks all |
| `stamps` (4.1) | `res:bake`, `res:map` if vectors change | Whiterun hill and tiers, Solitude arch, Markarth gorge, College sea stack | stamps in data; street planner plans on stamped ground; real stairs between tiers; arch is terrain; block-piece terrain builders deleted |
| `water` (4.2) | `res:map`, `res:bake` | Riverwood rapids and mill, Markarth falls, Windhelm river, Riften canals | `waterfalls` and `town_water` in vectors; hydrology honours them; town water features attach to real water; continuity probe passes |
| `looks` (4.5) | `res:shader`, `res:biomes` | Pale whiteout, Rift gold, Blackreach cyan, Hjaalmarch fog, barrow interior air | `looks.json` + generator; `ClientWeather` data-driven; shader data texture; emissive ids for Skycraft glow blocks; tinted leaves |
| `kits` (4.4) | `res:lang:<module>` | Nordic carved stone, Dwemer set, Falmer chitin, Blackreach fungi, sulphur crusts and vents, glacial ice, Riften piers | full block families, painters, asset audit clean; used by styles and overlays |
| `flora` (4.6) | `res:biomes` | Hjaalmarch bog trees, Rift birches, Ilinalta reeds | species as data; micro-rule rich fields parsed or removed |
| `function` (4.7) | `res:bake` | Whiterun, Riverwood, Morthal, Dawnstar | settlement profiles as data; every key building reachable from every gate; staffed services; generic settlements baked |
| `piers` (4.7, code) | `res:bake` | Riften, Morthal | buildings on piers/stilts over water by depth; boardwalk streets |
| `items` (4.9) | `res:lang:<module>` | Skyrim item families interpreted from the plates | regional item families feed loot, shops and crafting |

Order: `caves` and `stamps` first (they change the bake and the terrain everything else sits on), then `water`, then
`function`/`piers`, while `looks`, `kits`, `flora`, `cave-grammar` and `items` run in parallel (no bake).

Gates per card: the workflow's change-class gates, plus the pilot targets' probes passing at L1.
