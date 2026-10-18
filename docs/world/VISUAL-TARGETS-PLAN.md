# Visual Targets: a concept-art-driven world pipeline

Status: **plan** (2026-10-18). Nothing in this document is built yet. It extends what exists; it replaces only what
is a mismatch or a blocker (listed in section 9). Read `docs/START-HERE.md`, `docs/AGENT-RULES.md`,
`docs/WORLDGEN-PIPELINE.md`, `docs/WORLD-TESTING.md` and `docs/VISUAL-TEST-CAMERAS.md` first.

## 0. The idea in one paragraph

The owner supplies one concept-art atlas per region (12 volumes; Volume 01 Skyrim: 30 plates: 12 biomes and
sub-biomes, 10 cities and towns, 4 underground, 4 dungeons). Each plate becomes a **visual target**: a written,
checkable list of traits (landform, water, vegetation, palette, materials, silhouette, signature features, light and
mood, clutter, fauna, scale) attached to the ids the world already uses (region, micro-biome, place, cityplan,
dungeon, cavern profile). Targets drive a **gap report** (what data, blocks, terrain, light or code is missing), the
fixes are made through the existing generators, and every fix is verified on a **ladder of tests from seconds to
minutes**: data lints, a headless block exporter with offline renders and graph checks, mass sweeps, and only at the
end a real client camera shot set beside the plate. Places without a plate get **derived targets** from their region
target plus their world sheet, so the spaces between the art stay coherent but unique.

## 1. What exists today (surveyed 2026-10-18)

Strong foundations to keep:

| Area | What we have | Where |
| --- | --- | --- |
| Ids to attach to | 83 regions, 1,518 live micro rules, 7,640 places, 361 cityplans, 18 culture styles, 129 overlays, 4,386 dungeon identities, 123 marquee dungeons, 14 cave biomes, 8 cavern profiles, 133 landmark kinds | `skycraft/tamriel/*`, `dungeons/*`, `landmarks/*` |
| Causal model | 11 world sheets, `why` ids, F-WHY 100% | `skycraft/tamriel/world/*.json`, `tools/world/SCHEMA.md` |
| Terrain | vectors, then 32-block rasters, then Java height plus detail noise; baked plans (road, street, scene) | `tools/worldgen/*`, `tamriel/terrain/*`, `bake/*` |
| Fast checks | Tier 0 JUnit (~17 s), data/layout/reach/flow/ecology/worldsheet lints (seconds), `world_test.py --tier fast` with resumable digests | `tools/qa/*` |
| Headless Java audits | `GeographyAudit` (128-block survey), weather coverage | `tools/qa/*.java`, `*.gradle` |
| Offline renders | flat-colour iso and plan renders of dungeons, towns, caverns, landmarks; region and seam maps | `render_dungeon.py`, `render_town.py`, `dungeon_cavern_view.py`, `region_render.py`, `mca.py` |
| In-memory painting | dungeons are painted in memory and walked by a bot (0 blockers in 462) | `dungeons/qa/DungeonQa` |
| Real screenshots | live Prism autotest client, approved camera poses with generation fingerprints, readiness waits, shaders on/off, receipts | `tools/dev/live_world.py`, `visual_cameras.py`, `world_test.py capture/review/coverage` |
| Iteration | dev server hot-swap (~2 s), data reload (<1 s), bounded regen (9 chunks in 3.6 s), shader hot reload | `devserver.ps1`, `live_world.py` |
| Look | per-biome colours (sky, fog, water, grass, foliage, particle), ~40 weather profiles, moving fronts, patched Complementary shader with live data textures | `tools/worldgen/biomes.py`, `atmosphere/*`, `tools/shaders/*` |
| Blocks | ~228 custom blocks, procedural PIL painters per module, asset audit against a registry dump | `tools/textures/*`, `asset_audit.py` |

Gaps and blockers found (each is answered in sections 3-9):

* **No reference store, no targets, no image comparison.** Every image is judged by eye; only 5 approved cameras
  exist, all in Skyrim; any generation change makes every camera stale.
* **Settlement terrain is one flat pad** (`PlanTerrain.padHeight`). Whiterun's hill, Solitude's rock and arch,
  Markarth's cliff and the College's sea stack are block pieces on that pad, opaque to the street bake (the arch
  skips recording, parameters packed into `Spec.name`).
* **Water cannot be designed through towns.** Derived streams route around settlements; only authored river vectors
  plus the `ON_RIVER` allow-list (one entry: Dragon Bridge) cross a town; waterfalls exist only where a drop happens
  (no authored list); in-town water is source blocks (Markarth's falls are two source blocks).
* **Relief fidelity outside Skyrim is low** (median height 18-34 against 74; ruggedness 1.2-2.5 against 5.6); the
  planned reference-geometry tracer (`refgeo.py`) was never written.
* **Random caves are still on.** Vanilla carvers are off, but noise caves (cheese, spaghetti, noodle) and aquifers are
  active; the Java cave router (`TamrielCaves`, `TerrainDensity`) is not wired although the plan marks T8 done.
* **Data the generator ignores.** `palettes.json` carries a second micro-rule block (1,728) that Java never reads;
  micro rules' rich fields (`surface`, `trees`, `props`, `weather`, `ambience`, `why`) are not parsed; terrain and
  biome code never read the world sheets.
* **Look is not data.** No per-region grade, exposure, ambient or minimum light; region fog overrides are Java `if`s;
  under shaders, biome fog colour has little effect; custom glowing blocks get no emissive material id; interior air
  has four hard-coded tints; only two leaf blocks take a biome tint.
* **Block kits are vanilla.** Culture styles are nearly all vanilla blocks: no Nordic carved stone, Dwemer bronze,
  gears and pipes, Imperial marble, Redguard sandstone, Ayleid glass, Daedric set, Falmer chitin; Blackreach is warped
  wart and sea lanterns although Skycraft glowing-mushroom blocks exist; hot springs have no sulphur crusts or vents.
* **Variants are code.** ~70 tree species and fauna skin variants are hard-coded.
* **Doc drift.** START-HERE "Next" points at T1/T2 (done); `CLAUDE.md` links a missing `HANDOFF.md`; the town audit
  doc and design model (TO0, TOWN-DESIGN) never landed in this tree; raster size, carver and biome counts in
  TAMRIEL/WORLD/WORLDGEN docs disagree with code; START-HERE says agents never launch the client while
  LIVE-WORLD-PREVIEW authorises the autotest instance.

## 2. Principles

1. **Concept art is a target, not the truth.** The world sheet (lore, causes) wins where they disagree; scale and
   playability win over composition. A plate is mined for traits; we never copy a picture.
2. **Data first, code once.** Every fix should become a data knob that the next region reuses. When a fix needs code,
   build the general knob (for example "terrain stamps"), not a one-off for Whiterun.
3. **Fail fast, cheap first.** A trait is checked at the lowest ladder rung that can prove it (section 5). The client
   is the last rung, not the loop.
4. **Every target trait has an acceptance probe** (data, plan, block export, sweep, or client), with a recorded
   result. "Looks right" is never a gate by itself; a rubric score with reasons is.
5. **Unique between the plates.** Derived targets must pass neighbour-difference and blind-identity tests (G1).
6. **Legal line unchanged.** The atlases stay outside the repo unless the owner says they are his own work and may be
   committed (section 10); the repo holds our written trait lists, sampled colour swatches (a few per plate, as for
   palettes) and ids.

## 3. The pipeline (one volume at a time)

```
 PDF atlas ──► V0 ingest ──► V1 targets ──► V2 gap report ──► V3 fix (data / terrain / blocks / look / code)
                                   ▲                                   │
                                   └──── V5 derived targets ◄──────────┤
                                                                       ▼
                     V4 verify ladder: L0 lint ► L1 headless export ► L2 sweeps ► L3 dev-server regen ► L4 client
                                                                       │
                                                         V6 sign-off ledger (per target, per trait)
```

### V0 Ingest (tool: `tools/visual/ingest_atlas.py`, new)

* Input: `.reference/concept_art/<NN>_<volume>.pdf` (gitignored).
* Output (gitignored): `.reference/concept_art/<NN>/plate_<pp>.png` (page crops via PyMuPDF or pdfplumber; page text
  gives the plate title and caption).
* Output (committed): `tools/visual/atlases/<NN>.json`: per plate `{plate, kind (biome|sub_biome|capital|city|town|
  underground|dungeon), title, caption, region_hint, palette: [6-8 sampled hex swatches with coverage %], image_sha}`.
  Colour swatches are k-means over the plate (a few colours, like palette sampling today); nothing else from the image
  enters the repo.

### V1 Targets (`docs/world/targets/<province>.json`, written by an agent, reviewed by the owner)

One record per plate, plus derived records (V5). Kept OUT of `mod/src/main/resources` so editing targets never changes
the generation fingerprint (approved cameras stay valid).

```json
{
  "id": "vt.skyrim.whiterun.city",
  "plate": "01/13", "kind": "capital",
  "attach": {"place": "whiterun", "cityplan": "whiterun", "region": "skyrim/whiterun"},
  "why": ["whiterun.L6.hill_citadel", "whiterun.L9.civil_war_keystone"],
  "traits": [
    {"id": "tiers", "axis": "silhouette", "text": "three walled tiers climbing a rocky hill above the tundra", "probe": "plan.tiers>=3 & export.tier_links==all", "rung": "L1", "weight": 3},
    {"id": "keep_crown", "axis": "silhouette", "text": "great hall on the summit, visible from the plain", "probe": "export.skyline.highest==dragonsreach", "rung": "L1", "weight": 3},
    {"id": "hill_terrain", "axis": "landform", "text": "the hill is real ground with rock outcrops, not blocks on a pad", "probe": "export.terrain_stamp==hill & export.pad_flat_share<0.4", "rung": "L1", "weight": 2},
    {"id": "palette", "axis": "palette", "text": "golden tundra, grey stone, dark timber", "probe": "client.palette_de<=18", "rung": "L4", "weight": 1},
    {"id": "approach", "axis": "flow", "text": "a winding road climbs from the plain to the gate", "probe": "export.gate_road_switchbacks>=1", "rung": "L1", "weight": 1}
  ],
  "accept": {"min_score": 0.8, "must": ["tiers", "keep_crown", "hill_terrain"]}
}
```

Axes: `landform`, `water`, `vegetation`, `palette`, `materials`, `silhouette`, `signature`, `light`, `weather`,
`clutter`, `fauna`, `scale`, `flow` (traversal), `function` (services, people). Probe vocabulary is a fixed list
implemented by the ladder tools (section 5), so targets stay declarative and machine-checkable.

### V2 Gap report (tool: `tools/visual/gaps.py`, new; seconds)

For each target, resolve every probe it can at L0 and list what fails, classified:

| Class | Fix path | Example (Volume 01) |
| --- | --- | --- |
| `data` | edit generator inputs (`biomes.py`, places, cityplans, styles, overlays, cavern profiles) | Rift foliage tint, Falkreath fog |
| `terrain` | vectors, peaks, terrain stamps (section 4.1) | Whiterun hill, Solitude arch, Markarth gorge, College stack |
| `water` | authored river/lake/waterfall records (4.2) | Riverwood rapids, Markarth falls, Windhelm frozen river |
| `blocks` | new block kit (4.4) | Dwemer bronze, Nordic carved stone, sulphur crust, glow fungi |
| `look` | per-region Look record (4.5) | Pale whiteout, Blackreach cyan, Hjaalmarch fog |
| `code` | a new general knob | piers over water, stilts on depth, sea caves |
| `content` | dungeon/landmark/scatter records | Eldergleam grotto, Forgotten Vale |

Output: `docs/world/targets/<province>-gaps.md` plus JSON. This is the work list for V3; nothing is regenerated that
already meets its target.

### V3 Fix

Ordered so that each general knob is built once (Skyrim pilot) and then reused by every later volume. Section 4 lists
the knobs.

### V4 Verify (section 5)

### V5 Derived targets (the spaces between the art)

* Every region without its own plate inherits its region target; every settlement, dungeon, cave biome and landmark in
  the region gets a derived target generated from: the closest plate of the same kind in the province, the place's
  world-sheet fields (L1-L11), its cityplan profile, and the neighbour-difference rule.
* Generated by `tools/visual/derive.py` (rule-based first; an agent may write the trait text when rules leave gaps)
  and marked `derived: true` with the source plate and `why` ids.
* Ecotones: a border target between two region targets defines a blend band (palette, vegetation mix, weather) so
  transitions read naturally; checked by `region_render.py` seam maps and transects.
* Uniqueness gates: G1 blind identity (a model names the region from 3 renders, >= 80%), neighbour-difference over
  palette, landform, architecture, weather, music and fauna (no reskins), `novelty_lint` for text.

### V6 Sign-off ledger

Extend `world_test.py coverage` with a `target` dimension: per (target, trait) the latest result, the rung that proved
it, the input digests, and the reviewer. A target is `met` when `must` traits pass and the weighted score is above
`min_score`; `stale` when its inputs change. The owner signs off volumes, not images.

## 4. The general knobs to build (V3 work, built once)

### 4.1 Terrain stamps for places (replaces "blocks on a flat pad" for landform)

* New per-place data `skycraft/tamriel/stamps/<place>.json` (or a `terrain` block in the cityplan): a list of shaped
  operations evaluated by `TamrielHeight` in place of the single pad: `plateau` (levels with heights and edges),
  `tiers` (concentric or stepped terraces with ramp positions), `mound/hill`, `cliff` (face line, height, overhang),
  `arch` (span, clearance, deck width: real terrain with a void under it via the density function, not blocks),
  `sea_stack`, `gorge` (for Markarth: canyon walls the city is carved into), `basin`, `shelf`.
* The street planner reads the stamped height field (`StreetTerrain` already plans on a seed-free terrain), so tiers
  get real stairs and ramps as street edges, and doors are found on the real levels (fixes the opaque arch/rock
  pieces and the Whiterun tier-link failure class).
* Baked like the pad today (input hash covers stamps). Silhouette landmarks that remain block pieces (towers,
  aqueducts, Dwemer facades) must record into the street bake.
* Probe support: `export.terrain_stamp`, `export.pad_flat_share`, `export.tier_links`.

### 4.2 Authored water through places

* `tools/worldgen/tamriel_vectors.json` gains `waterfalls` (position, drop, width, plunge pool, cave behind) and
  `town_water` (a river or canal that MUST pass through a place footprint), generalising `ON_RIVER`.
* `hydrology.py` honours them: carves the channel through the stamp, guarantees the drop, keeps levels monotonic
  through confluences; street plan gets bridges and quays automatically (BRIDGE edges exist).
* In-town water features (mill races, Markarth falls, Riften canals) become real hydrology water, not source blocks;
  `Works.canal`, `cliffFalls` and waterwheels attach to it.
* Probes: `export.river_through(place)`, `export.waterfall(place, min_drop)`, `export.water_continuity` (flow graph
  from source to sea passes the town with no gaps).

### 4.3 Underground policy: named caves only

* Turn noise caves off (cheese/spaghetti/noodle and entrances) in `noise_settings.py`; keep aquifers only where a
  target needs a water table (decide per province; default off above sea level).
* Underground space is authored: dungeons, grand caverns (`CavernGen`), Blackreach-type regions, sea caves and the
  waterfall caves; each cave is a named place with an identity, guided paths (the dungeon layout graph) and an
  entrance placed by the planner. Either wire `TamrielCaves` as the single router or delete it and record why.
* New cave kinds from the plates (ice cavern, Falmer grotto, Ratway cisterns) become cavern profiles plus kits.
* Probe: `sweep.random_cave_volume==0` (count of cave air not owned by any structure or authored cavern), cave
  entrances per region vs target.

### 4.4 Block kits per culture and feature

* A kit request is generated from gap reports (`blocks` class) and implemented by the existing painter pattern
  (`tools/textures/<module>.py` + registry + lang + asset audit), always as a full family (block, stairs, slab, wall,
  pillar where relevant) because `Materials.family()` resolves by suffix.
* First kits for Volume 01: Nordic carved stone and dragon reliefs; Dwemer bronze, brass, pipes, gears, Dwemer brick
  and lamps; Falmer chitin; Blackreach fungi (stems, caps, gills, glow) using the existing glowing-mushroom blocks;
  hot-spring sulphur and mineral crusts plus steam vents; glacial ice variants; Riften dock planks and piers.
* Kits plug in through style sheets and overlays (no Java), plus the few hard-coded landmark builders (which move to
  data as part of 4.1).
* Rule: no block without a target trait that needs it; every block passes `asset_audit` and the shader material map
  (4.5).

### 4.5 Region Look as data (light, fog, grade)

* New `skycraft/tamriel/looks.json` (generated by `tools/worldgen/looks.py` from targets and world sheets): per region
  and per micro/cave biome: fog colour and density by weather, grade (saturation, contrast, warmth), ambient and
  minimum light, night ambient, particle set, interior-air style (replacing the four hard-coded tints), leaf tint
  family.
* Client: `ClientWeather` reads it instead of `if` branches (no-shader path). Shader path: one new data texture
  (region index to look parameters, like the weather field textures) consumed by the patched Complementary code; the
  patcher's sky/fog/ambient functions read it. Keep the patch rule "one key light colour" (no per-region sun tint).
* Emissive and subsurface material ids for Skycraft blocks in the patched `block.properties` (glow fungi, crystals,
  nirnroot, magma rock).
* Tinted leaves: more leaf blocks use the biome foliage colour so the Rift/Reach/Ascadian palettes come from biome
  data instead of pre-coloured PNGs.
* Probes: `L0 look.complete(region)`, `L4 palette_de`, `L4 fog_colour_de`, `L4 luminance histogram`.

### 4.6 Flora and fauna as data

* Tree species move from `TreeSpecies` (Java) to `skycraft/tamriel/flora/species.json` (shape, wood, leaves, size,
  tint family); `biomes.py` keeps placing them.
* Fauna variants: `region_spawns/*.json` gains `variants` per region or micro-biome (snow, cave, marsh skins), read by
  `chooseVariant()`.
* Micro rules: Java parses the rich fields (`surface`, `trees`, `groundcover`, `props`, `weather`, `ambience`) or the
  dead fields are removed; the second, unread micro block in `palettes.json` is deleted (or becomes the only one).
* Probes: `L2 flora census per region vs target vegetation`, `L2 fauna census`.

### 4.7 Settlement function model (traversal and sense)

The concept art shows the face of a town; the function model makes it work. Re-land the TO0 audit and the
TOWN-DESIGN model that did not reach this tree, as data:

* Settlement profile (`skycraft/settlements/profiles.json`): purpose, population band, staffed services, districts
  with personalities, guard watch, jail tier, leader's seat, iconic feature, outer farms or trade-fed, and the
  defining constraint (Riverwood: the river and the mill; Whiterun: the hill).
* Planner rules: every key building reachable from every gate, tiers linked by real stairs/ramps, exits and entrances
  declared (fixes F01-F06 from `tools/qa/town_audit/static_facts.py`), lots sized for staffed service buildings.
* Generic settlements get a baked street plan too (today only named ones do).

## 5. The verification ladder (fast first, client last)

| Rung | What runs | Needs | Time | Proves |
| --- | --- | --- | --- | --- |
| **L0 data lint** | `target_lint.py`, `gaps.py`, existing data/layout/reach/worldsheet lints | Python only | seconds | probes on data: regions, micro rules, places, cityplan fields, stamps, water records, kits exist, looks complete, `why` resolve |
| **L1 headless export** (new: `SiteExport`) | real Java generators paint a site window in memory: `TamrielHeight` with stamps, hydrology water, `SettlementPiece`, landmarks, scatter; writes `.npz/.json.gz` (blocks, heights, water, doors, entities). Python renders iso, plan, cross-sections, skyline silhouette; graph checks: gate-to-door flood fill, tier links, exits, water continuity, cave air ownership | JDK, no Minecraft world (Gradle JavaExec like `GeographyAudit`, reusing the in-memory canvas `DungeonQa` already uses) | 2-20 s per site | most `silhouette`, `landform`, `water`, `flow`, `function`, `clutter` traits; regression without the client |
| **L2 mass sweeps** | `SiteExport --all` over every place of a province plus `GeographyAudit` extensions: biome, flora, fauna census per region; cave volume; water continuity; G1 renders; neighbour difference | same | minutes per province, parallel threads | every place meets its derived target's L1 probes; region distributions match targets |
| **L3 dev server** | bounded regen of a window, saved-world audits (`town_checks`, `town_audit`, `road_measure`) | headless server | ~1 min | parity between L1 export and real chunks (sampled), feature nondeterminism |
| **L4 client** | approved cameras per target (shaders on and off); side-by-side sheet: plate, shaders-on, shaders-off; automatic advisory metrics (palette and fog colour distance in Lab, luminance histogram, sky share, vegetation share, silhouette edge overlap); a vision model scores the sheet against the trait list with reasons | live autotest Prism client | ~5 s per warm capture | `light`, `palette`, `weather`, `mood` traits; final human sign-off |

Efficiency rules (tokens and time):

* **No model in the loop below L4.** L0-L3 produce compact JSON (pass/fail per probe with numbers). An agent reads only
  failures. Contact sheets go to a cheap vision model only at L4, one sheet per target, with the trait list as the
  rubric and a fixed JSON answer format.
* **Digest caching.** Reuse `world_test.py` input digests: a probe re-runs only when its inputs changed (target, data
  files, class hashes, bake). Sweeps are incremental per place.
* **One JVM, many sites.** `SiteExport` keeps the generators warm and exports a list of sites per run (like
  `GeographyAudit`), so a province sweep is one process, not N boots.
* **Exporter parity guard.** L1 uses the real Java builders (no Python re-implementation; the docs already warn about
  parity drift). L3 samples a few windows per sweep and diffs the export against real chunks (block-type histograms,
  not hashes, because feature placement is not bitwise deterministic).
* **Camera stability.** Fingerprint cameras by the *local* generation inputs of their window (place, stamps, region
  data) instead of the global generation hash, so a change in Morrowind does not stale Whiterun's cameras.
* **Bake only when the planner changes.** Stamps and water are bake inputs; looks, kits, flora and fauna are not.

## 6. Mapping Volume 01 (Skyrim) to today

Status is from code reading; `?` means verify in the V2 gap pass.

| Plate | Target | Today | Main gap class |
| --- | --- | --- | --- |
| 01 Whiterun tundra | golden steppe, ponds, distant peaks | biome colours exist | look, vegetation mix |
| 02 Falkreath pine | tall pines, mist, mossy boulders | forest biome exists | look (valley mist), flora variants |
| 03 Hjaalmarch marsh | icy delta, cotton grass, dead bog trees | marsh biome exists | flora (dead bog trees), look |
| 04 Haafingar cliffs | coastal crags into the Sea of Ghosts | coast exists | terrain (crag relief), water foam |
| 05 Rift gold birch | gold/orange birch, mountain lake | colours and aspen blocks exist | tinted leaves, bark, ecotones |
| 06 Pale whiteout | wind-carved snow, blizzard | snow biome exists | look (whiteout), snow drift landform |
| 07 Eastmarch springs | turquoise pools, sulphur crusts, steam | `HotSpringFeature` (calcite, tuff) | blocks (crusts, vents), water colour |
| 08 Reach Karth gorge | granite crags, juniper, rapids | Reach relief exists | terrain (gorge), water (rapids) |
| 09 Winterhold glacier | ice cliffs, sea ice | coast fix M5 pending | terrain, blocks (glacial ice) |
| 10 Forgotten Vale | glacial valley, frozen river, pale flora | ? | content, look |
| 11 Eldergleam | sunlit grotto, ancient tree | ? | content (grotto + tree landmark) |
| 12 Lake Ilinalta shore | reeds, misty pines | lake exists | flora (reeds), look |
| 13 Whiterun | three tiers on a hill | terraces as blocks on a pad; tier-link issues (F01) | terrain stamp, flow |
| 14 Solitude | city on a natural arch over the river mouth | rock grid + sine-deck arch blocks, not in street bake | terrain stamp (arch), water |
| 15 Windhelm | stone bridge over a frozen river | city exists; gate faces away from road (F04) | water, flow |
| 16 Riften | timber canal city on piers over the lake | canal + docks; houses on land | code (piers), water |
| 17 Markarth | city carved into a canyon with falls | slab cliffs, two source-block falls | terrain stamp (gorge), water (falls), Dwemer kit |
| 18 Falkreath | forest seat, cemetery, footbridges | Java builder exists | clutter, water (stream) |
| 19 Morthal | stilts and boardwalks over marsh water | stilts flag not tied to water depth | code (stilts on depth), water |
| 20 Dawnstar | mining port on a horseshoe bay, headframes | cove fix M2 pending | terrain (bay), landmark (headframes) |
| 21 College | citadel on a sea stack, arched bridge | sea-stack rock pieces | terrain stamp (sea stack) |
| 22 Riverwood | sawmill on whitewater rapids | depends on the map river | water (rapids through town), mill on real water |
| 23 Blackreach | giant glowing mushrooms, crystals, falls | vanilla blocks | blocks (fungi), look, water |
| 24 Glacial ice cavern | blue ice, frozen falls | ? | cavern profile, blocks |
| 25 Falmer grotto | chitin bridges, green-cyan fungi | Falmer kit exists | blocks (chitin), look |
| 26 Ratway | vaulted sewers under Riften | ? | content (city underground), function model |
| 27 Nordic barrow | carved arches, braziers, blue mist | barrow kit exists | blocks (carved stone), look (interior air) |
| 28 Dwemer ruin | bronze halls, gears, magma, aetherium | blackstone + copper | blocks (Dwemer set), look |
| 29 Bleak Falls | buttressed barrow on a ridge | `Special` exists | silhouette check |
| 30 Castle Volkihar | gothic fortress on an ice island | ? | content, terrain (island) |

## 7. Phases (sizes in agent-days; one server at a time)

| Phase | Work | Size | Exit |
| --- | --- | --- | --- |
| **VT0 Housekeeping** | fix doc drift (START-HERE, CLAUDE.md, TAMRIEL/WORLD counts), decide the client-launch rule, remove the unread micro block, decide `TamrielCaves` fate | S | docs agree with code |
| **VT1 Ingest + schema** | `ingest_atlas.py`, target schema + lint, Volume 01 targets (30) written and owner-reviewed | M | 30 targets, lint clean |
| **VT2 Headless export (L1)** | `SiteExport` (JavaExec, warm, multi-site), Python renders and graph probes, parity sampler | L | Whiterun, Riverwood, Markarth exported in < 20 s each; flood fill matches `bfs_truth.py` |
| **VT3 Gap report** | `gaps.py`, Volume 01 gap list | S | ranked work list |
| **VT4 Knobs** | 4.1 stamps, 4.2 authored water, 4.3 caves off, 4.5 looks + shader channel + emissive, 4.4 first kits, 4.6 data flora/fauna, 4.7 function model | XL (split per knob, 2-3 agents, serial for bake inputs) | knobs proven on Skyrim pilots |
| **VT5 Skyrim pass** | apply to the 30 plates, then derived targets for all Skyrim places | L | every Skyrim target `met`; G1 >= 80% |
| **VT6 L4 cameras** | camera plans per target, local fingerprints, side-by-side sheets, scored review, owner sign-off | M | Volume 01 signed off |
| **VT7 Roll-out** | volumes 02-12 in WORLD-PLAN order; each: ingest, targets, gaps, fixes, sweep, cameras | L per volume | each volume signed off |

Order inside VT4: caves off and stamps first (they change the bake), then water, then looks/kits/flora (no bake), then
the function model on the new terrain.

## 8. Tools to build (summary)

| Tool | Language | Rung |
| --- | --- | --- |
| `tools/visual/ingest_atlas.py` | Python (PyMuPDF/pdfplumber, PIL, numpy) | V0 |
| `tools/visual/target_lint.py` | Python | L0 |
| `tools/visual/gaps.py` | Python | V2 |
| `tools/visual/derive.py` | Python | V5 |
| `com.skycraft.dev.SiteExport` + `tools/qa/site_export.gradle` | Java (JavaExec, no world) | L1/L2 |
| `tools/visual/site_render.py`, `site_probes.py` | Python (reuse `render_dungeon`/`render_town` drawing) | L1/L2 |
| `tools/visual/sheet.py` | Python (plate + shots side by side, metrics) | L4 |
| `tools/visual/score.py` | Python + vision model call with fixed rubric | L4 |
| `world_test.py` target dimension and local camera fingerprints | Python | V6 |
| `tools/worldgen/looks.py`, shader patch data texture | Python + GLSL | 4.5 |
| stamps and waterfall/town-water support | Python (map) + Java (`TamrielHeight`, `Hydrology`, street planner) | 4.1/4.2 |

## 9. Replace or remove (with reasons)

* **Noise caves and random entrances**: replaced by authored caves (4.3). They are unguided, unnamed and fight the
  "named caves with paths" rule.
* **Terrain made of block pieces** (`Landmarks.rock/arch/cliff`, sea stack): replaced by stamps (4.1). They are flat-pad
  workarounds, opaque to the street bake, and use hard-coded vanilla stone.
* **Source-block falls and canals as the only town water**: replaced by authored hydrology (4.2).
* **Hard-coded look branches and interior tints**: replaced by `looks.json` (4.5).
* **The unread second micro-rule block**: delete or make it the only one (4.6).
* **Unwired `TamrielCaves`/`TerrainDensity` router**: wire as the single cave router or delete (VT0).
* **Pre-coloured leaf textures where a tint would do**: replaced by tinted leaves (4.5).

Kept as is: the vector map and rasters, bake architecture, road plan, street planner core, dungeon generator and its
QA bot, marquee dungeons, world sheets, scatter and scene plans, weather system, shader patcher, camera workflow.

## 10. Decisions for the owner

1. **The atlases**: are they the owner's own work that may be committed (then `docs/world/targets/plates/` may hold
   reduced copies), or reference only (gitignored `.reference/concept_art/`, the default here)?
2. **The 12 volumes**: 11 province documents exist (Skyrim, High Rock, Hammerfell, Cyrodiil, Morrowind, Solstheim,
   Black Marsh, Elsweyr, Valenwood, Summerset, foreign lands). Is the 12th the seas and outer realms, the Iliac Bay,
   or another split?
3. **Agents and the client**: may agents drive the autotest Prism instance for L4 captures (LIVE-WORLD-PREVIEW
   allows it; START-HERE forbids it)? L1-L3 need no client.
4. **Caves**: remove all noise caves and aquifers above sea level (default here), or keep a reduced natural cave
   layer in some regions?
5. **World regeneration**: terrain stamps, water and caves change generation: these apply to new worlds only. Is
   that acceptable for the playtest world?
6. **Plate vs lore conflicts**: the default is "world sheet wins, plate adapts"; the owner may override per plate.

## 11. Next steps

1. Owner answers section 10.
2. VT0 housekeeping (one agent, half a day).
3. VT1: ingest Volume 01, write the 30 targets, owner reviews them.
4. VT2: build `SiteExport` and the L1 probes on Whiterun, Riverwood and Markarth (proves the fast loop before any
   world change).
5. VT3: Volume 01 gap report; then VT4 knob by knob, Skyrim first.
