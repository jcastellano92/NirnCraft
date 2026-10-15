# World design principles: a full, unique, purposeful Nirn

This is the design rulebook for the whole world: every province, region, city, town, dungeon and roadside spot. The
master plan and every province phase follow it. It says what "as full and special as Skyrim" means in numbers
Skycraft can build and measure. The bar is Skyrim itself, measured from the real game (section 4.1). ESO is only a
pool of optional ideas that may sit on top of that bar (section 4.12).

* **The first principle** (world building is king) governs everything below it. It includes the causal world model
  sheet that every region and province fills in, and the coherence check every province phase must pass.
* **Section 0** holds the non-negotiables. Read it even if you read nothing else.
* **Section 1** covers modernising legacy-source content (Arena, Daggerfall, old maps): what we keep, what we
  amplify and what we replace.
* **Section 2** covers the research inputs: the owner's asset-research PDF, its resource inventory and what each
  source may be used for, plus what the Daggerfall bible teaches about how a whole TES world's systems fit together.
* **Section 3** lists the open-world principles from Skyrim, ESO, Morrowind, Oblivion and Daggerfall, each one turned
  into a Skycraft rule with a measurable target.
* **Section 4** is the region content template. It opens with what made Skyrim great, turned into rules with
  checks (4.0), and the Skyrim baseline measured per hold from the real game (4.1). Then it says what every province
  and region must contain, counted by tier and scaled by area, mapped to existing systems. It lists the missing
  systems as short specs (4.11) and ends with the ESO-style extras, kept apart as an optional layer (4.12).
* **Section 5** describes the gameplay flow: the moment-to-moment loop and the session loop, with the checks the QA
  tools can measure.

Companions: `docs/PARITY.md` (the scorecard and backlog this plan feeds), `docs/MARQUEE.md` and `docs/LORE-WEB.md`
(the places that must say "wow" and the mysteries that link them), `docs/WORLDGEN-PIPELINE.md` (plan-first
generation and planned scenes), `docs/ECOLOGY.md` (peace and danger), `docs/EXPLORATION.md` (density targets),
`docs/QUESTS.md`, `docs/SKYRIM-SWEEP.md` (the town audit) and `docs/FIRST-PLAYTEST.md`.

**Legal line (unchanged, strict).** We use facts and design principles only. That covers names and relative
positions of places, which features a game had and how many, and why a design works. Every description, line of
text, name of an invented person, model, texture and sound is our own. Nothing from any game's files (text, meshes,
textures, audio, rasters) enters the repo, raw or converted. Section 2.2 says what each listed source may be used
for.

**Units.** 1 block is about 0.9 m, close to Skyrim's own scale (Skycraft's Skyrim is 26.5 km² of land). 1 km² is
1,000 x 1,000 blocks. Walking speed is about 4.3 blocks/s, so 250 blocks is about one minute on foot. "Region" means
a `regions.json` region (71 land regions; Skyrim's nine holds are regions). "Province" means a group of regions.
"Named town" means a capital, city or town, as in `parity.py`.

---

## The first principle: world building is king

**Everything builds the world.** The lore, the visuals, the people, the layouts and the cities all exist to build
the world. This rule sits above every other rule in this document. When two rules conflict, the one that makes the
place more believable wins.

Every element must express and reinforce its place's history, culture, conflicts and ecology. That covers terrain,
architecture, palette, a prop in a scene, an NPC's job and line, a quest, a book, a dungeon's enemies, a road's
waystones, music and weather. Each element must also agree with every other element in the same place. No element may
contradict its place or ignore it. A desert town with a pine-log mill is wrong. So is a bandit camp with no road to
rob, a mine with no ore in the rock, or a cheerful lute in a town under siege.

Skyrim's geography explains most of what is in it. The underground follows the rock. The cults hide where the law is
weak. Water decides where people can live. Trade routes decide which towns are busy and which are poor. The people's
beliefs follow from their history and their neighbours. Skycraft builds every region the same way, starting from
the ground and working up, using the sheet below.

### The causal world model sheet

Every region, and every province as a whole, gets a **world model sheet**. It is data: `tamriel/world/<province>.json`
holds a province block plus one block per region (MS22). Every content batch takes its content from the sheet. The
generators read it too: city plans, quests, scenes, dungeon identity, ecology, dialogue, music and weather.

The eleven layers are listed in **causal order**. Each layer must cite at least one field from the layers above it as
its cause. For example, `L5.silver_mining` cites `L1.silver_veins`, and `L7.miner_poverty` cites `L5.silver_mining`
and `L9.family_rule`. A layer with no cause is invented, so it is rejected.

| # | Layer | Fields (each a short id plus a sentence or a list) |
| --- | --- | --- |
| L1 | **Geology and landform** | Rock types; ranges and passes; volcanism and heat; caves and karst; ore and gem veins; soils; coast form (cliff, beach, fjord, delta) |
| L2 | **Climate** | Prevailing wind; rain shadow; storms (kind, season, where they come from); snowline; fog; seasons; how the weather changes with height and aspect |
| L3 | **Water** | Rivers and where they run; springs, wells, aqueducts and cisterns; lakes, marsh and floods; **who controls the water**, and how ordinary people get theirs |
| L4 | **Ecology and micro-biomes** | The mosaic of micro-biomes (MS21) with their causes; flora; game animals; predators and monsters, and why they live where they do |
| L5 | **Resources and economy** | Farming, mining, timber, fishing and herding; crafts; trade routes, ports and markets; regional products; who gets rich |
| L6 | **Settlement pattern** | Why each town is where it is (a crossing, harbour, mine, temple, pass or spring); which towns are busy centres and why (lore plus geography); which are poor or failing; the road and trade flow |
| L7 | **Society** | Classes and the gaps between them (nobles, merchants, craftsmen, the poor quarter, slums, outcasts); laws and their enforcement; guilds; what is expected of people; daily life |
| L8 | **Culture and belief** | Faiths and shrines; superstitions; festivals; burial customs; what people think of outsiders, the Empire, the Thalmor, magic and the dead |
| L9 | **Politics and conflict** | Rulers and courts; rival families; wars and unrest; outside powers; what is at stake right now |
| L10 | **The hidden and the underground** | Cults; criminal networks; ancient ruins, who built them and why; what lies below (caves, barrows, Dwemer halls, sewers); **why each dungeon is inhabited, and by whom** |
| L11 | **History layers** | What happened here in each era, and what is still visible: ruins, monuments, place names, grudges, songs |

**Worked example: the Reach (facts as lore states them, summarised in our own words; the depth every region needs).**

| Layer | Example fields |
| --- | --- |
| L1 | `granite_gorges`: deep river gorges and pillar-like crags. `silver_veins`: silver in the rock under Markarth. `karst_caves`: many caves in the cliffs. `dwemer_city_rock`: a Dwemer city carved into the cliff at the head of a gorge |
| L2 | `wet_highland`: mild and damp next to the snowy north. `valley_mist`: morning mist in the river gorges. `storm_west`: storms come over the western ranges |
| L3 | `karth_river`: the Karth runs north-east through the hold to the sea. `dwemer_channels`: Markarth draws its water through Dwemer channels in the rock, so whoever holds the city holds the water. `mine_runoff`: mine streams below the city |
| L4 | `gorge_meadow` (river floor: grass, flowers and herbs, cites L3). `crag_scrub` (dry ledges: juniper and thorn, hagraven nests, cites L1). `cave_mouth` (bats and spiders, cites L1). `pine_upland` (above the gorges, cites L2) |
| L5 | `silver_mining` (cites L1): Cidhna Mine is the city's wealth. `small_mines` (cites L1): small mining camps (Karthwasten, Left Hand, Kolskeggr, Soljund's Sinkhole) for silver, iron, gold and moonstone. `orc_mining` (cites L1): Orc strongholds working their own mines. `river_farms` (cites L3): a few farms on the Karth flats. `border_trade` (cites L6): roads to High Rock and Hammerfell |
| L6 | `markarth_fortress` (cites L1, L3, L5): the city sits on the Dwemer rock, on its water, over its silver, at the head of the gorge road. `mine_hamlets` (cites L5): villages stand on the mines and nowhere else. `karth_road` (cites L3): the road follows the river down to Dragon Bridge. `old_hroldan_waystation` (cites `karth_road`): an inn on the road |
| L7 | `family_rule` (cites L5): one mining family owns the silver, the prison mine and most of the city. `prison_labour` (cites `silver_mining`): convicts dig the silver. `miner_poverty`: the miners are poor and cannot leave. `natives_outcast` (cites L9): the Reachfolk live outside the law in the hills |
| L8 | `old_gods` (cites L11): the Reachfolk worship the old gods through hagravens and briarhearts. `talos_ban_split` (cites L9): the city follows the Empire's ban while some citizens worship in secret. `death_cult_under_dead` (cites L10). `fear_of_outsiders` (cites L9) |
| L9 | `native_uprising` (cites L11, L7): the Forsworn fight to take the Reach back. `imperial_jarl` (cites L9 province): the jarl owes his seat to the Empire. `silver_power` (cites `family_rule`): the family's money is the real power |
| L10 | `conspiracy_in_the_mine` (cites L7, L9): the rebellion is run from inside the prison mine. `dwemer_depths` (cites L1): the Dwemer halls go on under the city. `hagraven_covens` (cites L4, L8). `forsworn_camps` (cites L9, L1): the rebels camp in the crags and caves along the roads, which is why the roads are dangerous |
| L11 | `dwemer_era`: the Dwemer city and its machines. `reach_kingdom`: Reachfolk rule in the past. `nord_conquest`. `the_incident`: the recent seizure of the city by the natives, and its bloody end, the grudge behind L9 |

**The province sheet** holds the same eleven layers at a larger scale: ranges and climate belts, the great rivers,
the main trade arteries, the capital's role, the province's faiths and its war or politics. Each region sheet cites
the province sheet where it inherits from it.

**The world truth paragraph.** The top of every region block is a single paragraph (about 80-120 words) that
summarises the sheet. It covers history, people, conflict, economy, faith and ecology. Agents read it first. Every
content batch quotes it in its report.

### The coherence check (every province phase must pass)

1. **The sheet exists and is causal.** Every region and the province have all eleven layers filled. Every field in
   L2-L11 cites at least one field above it. `tools/qa/worldsheet_lint.py` (MS22) fails on a missing layer, a field
   with no cause or a dangling cite.
2. **Every content element traces back to the sheet.** Each place, NPC, quest, scene kind, dungeon identity, book,
   creature table, palette and micro-biome choice, music cue and weather rule carries a `why` field. The `why` holds
   one or more sheet field ids, for example `"why": ["reach.L5.silver_mining", "reach.L7.prison_labour"]`. The lint
   resolves every id and fails on content with no `why` (100% for new content; legacy content is back-filled per
   phase).
3. **No contradiction.** The lint flags pairs the sheet rules out. Examples: a crop on a micro-biome whose climate
   forbids it, an industry with no resource field, a faith shrine the culture layer does not list, or a dungeon
   inhabitant group with no L10 reason.
4. **The four questions.** For every settlement and every named site, the phase report answers in one line each:
   why does this place exist here, what do its people do, what were the ruins, and why are the dangers (bandits,
   cultists, beasts) on this road? Each answer cites sheet ids.
5. **A human read.** One page per region (the world truth paragraph plus a contact sheet of towns, dungeons and
   landmarks) goes to Haiku review with one question: does everything here look like it belongs to this place and
   to nowhere else?

---

## 0. The non-negotiables (owner's direction)

These apply to every region, city, town and location. A place that breaks one is not done, however much data it
has.

1. **Unique.** No place may read as a reskin of another. Each settlement has its own silhouette, landmark and
   palette (`marquee.json` already enforces this for the 231 marquee places). Each dungeon has its own hook, and
   each region has its own story, world event, set, collectibles and voice. "Different text on the same thing" does
   not count; the place itself has to be different.
2. **Purposeful placement.** Everything is somewhere for a reason: a mine on the ore, a mill on the river, a shrine
   at the crossroads, a camp at the ford, a grave at the battle site. Random clutter is not allowed (the P6 scene
   plan and `placement_lint.py` already enforce this for human props).
3. **Lived-in towns.** People sleep, work, eat at the inn, gossip, have problems and remember you. Every job has a
   bed and a workplace, every trade a counter, and every town an industry that explains why it exists.
4. **Roads to everything.** Every settlement and every named dungeon, fort, shrine and landmark is reached by a road,
   lane or signposted trail (`reach_lint.py`). Roads are the spine of discovery, not just transport.
5. **Memorable marquee places.** Every region has at least one place the player will describe to a friend: a city,
   a hand-authored dungeon or an XL landmark with a mechanism or view no other place has.
6. **Quests that mean something.** A quest changes a place or a person (a feud ends, a mine reopens, a shrine is
   relit), names a real person and a real place, and leaves a trace in the world or in dialogue. Fetch-counts with
   no consequence belong only in the radiant layer, and even there they say why.
7. **Peace and danger, paced.** Most of a walk is calm. Danger comes from places (camps, lairs, ruins, night) and
   from scripted encounters with a story, not from random spawns at your heels (`ECOLOGY.md`: 3% day and 15% night
   chance of a hostile group on a 300-block road walk).
8. **Signposting.** The player always knows where the next interesting thing probably is, through sightlines, signs,
   rumours, smoke, lights, notes and map reveals, without a quest arrow being the only guide.
9. **Discovery is rewarded.** Every detour pays off with at least one of: a story (note, scene), a thing (loot,
   collectible, unique), knowledge (map reveal, lore, recipe) or a view.
10. **Skyrim quality everywhere, not only in Skyrim.** Every region meets the Skyrim floor measured from the real
    game (section 4.1): the same density per km², the same depth per settlement tier and the same mix of dungeons,
    people, quests and secrets as a Skyrim hold. Skyrim's own holds in Skycraft must meet it too. ESO ideas are an
    optional layer on top (4.12) and never replace any part of the floor.

---

## 1. Modernising legacy-source content (keep / amplify / replace)

Much of what we know about the provinces outside Skyrim comes from old games and data: Arena's sketch of the whole
continent, Daggerfall's Iliac Bay (High Rock and Hammerfell), Morrowind's and Oblivion's maps, and ESO's zone
layouts. **Old sources give geography and structure. They do not give quality.** Daggerfall had about 15,000
locations on a 160,000 km² map, but most of them were the same few town blocks and procedural dungeon mazes on
smooth, featureless heightmaps. Those are exactly the faults this project exists to fix.

### 1.1 The three verbs

| Verb | Meaning | Test |
| --- | --- | --- |
| **Keep** | Facts that define the place in lore: its name, its rough position relative to its neighbours, its culture and faction, its role (port, capital, pilgrimage site), the famous thing about it | A fan recognises it |
| **Amplify** | Something the source had in seed form that a modern open world can make great: a region's politics, a guild network, a travel network, a festival, a local legend, a dungeon's premise | It now drives a quest, a scene, a mechanic or a view |
| **Replace** | The source's limitations: bland terrain, repeated town blocks, generic mazes, nameless NPCs, random encounters, quest generators with no consequence, empty space between dots | No trace of the old limitation remains; the replacement meets the template in section 4 |

### 1.2 Concrete rules by content type

| Content | Keep | Amplify | Replace |
| --- | --- | --- | --- |
| **Terrain** | Coastlines, major ranges, rivers and lakes as hand-traced vector geography (AGENT-RULES legal rule), the province's broad character (moor, desert, jungle) | Every region gets a signature landform (`l_kinds`, XL landmarks): Bangkorai's passes, the Alik'r dune sea, Wrothgar's peaks. Rivers get falls and gorges where the relief allows (hydrology) | Smooth or blocky source heightmaps: our own eroded, plan-first terrain (`WORLDGEN-PIPELINE.md`). Never sample a game raster pixel-for-pixel |
| **Settlement list** | Names, relative positions and size class (capital, city, town, village) | Each named town gets its marquee identity: silhouette, landmark, palette and 3+ named buildings (`marquee.json`, `cityplans/`) | Daggerfall's repeated RMB block towns: unique city plans for every capital and city, and style sub-kits plus region overlays for the rest (PA16, PA17). No two towns in a region share a plan |
| **Settlement life** | The town's role and economy as lore states it (port, mine town, temple seat) | The role becomes the industry: workplaces, jobs, a regional product in the shops, a problem tied to the industry (quests) | Nameless crowds and the "wander, vanish at dusk" walkers: named residents with homes, jobs, schedules and relationships, at Skyrim's measured counts per tier (section 4.4: about 3 in a working village, 9 in a town, 20 in a minor seat, 73 in a capital) |
| **Dungeons** | Names, positions and the premise (an Ayleid ruin, a Direnni tower, a Yokudan tomb) | The premise becomes a hook, a boss with a name and a reason, a lore note and a link to the lore web (PA3, `LORE-WEB.md`) | Daggerfall's random-block mazes: authored room templates per culture (PA12), a loop with a return shortcut, sleepers vs awake, set pieces, one hand-authored marquee per region (PA13) |
| **Wilderness** | The province's fauna and flora as lore states them | Signature creatures per province with their own behaviour (PA14, PA15), ambient life, herds and birdsong | Empty space between dots, and random hostile spawns: planned scenes (P6), minor sites with a reason, scripted world encounters (MS14) and quiet stretches |
| **Quests** | Lore events, factions and conflicts of the era (houses, guilds, wars, cults) | Each conflict becomes a region storyline (MS1) or a faction chain with choices | Daggerfall-style generator quests as the main content: radiant work stays as filler, always with a named giver, a named place and a reason (PA22) |
| **Travel** | Historic roads, ports, ferries, silt striders, guild guides, wayshrines | Each province's travel network becomes part of its identity (MS11) | Daggerfall's fade-to-black travel across empty map pixels: roads with scenes, signposts and encounters, plus fast travel to discovered places |
| **Lore text** | Facts: who ruled, what happened, what a people believe | Rumours, legends, songs and notes in each region's voice (PA10) | Any source text: never copied, always rewritten in our own words (`novelty_lint.py` keeps our own text from repeating; MS20 checks it against a local corpus) |
| **ESO zones** | Zone names, the regional split (Glenumbra, Stormhaven, Bangkorai...), landmark positions as facts | Optional ideas only, on top of the Skyrim floor (zone story, open dungeon, world bosses, collectibles; 3.2, 4.12) | ESO's level-gated zones and instanced phasing: one seamless world with regional level bands (P-J3) |

### 1.3 The modernisation checklist (every province phase runs it)

A province phase is not done until every line holds. Each line names its check.

1. **Geography kept and traced, not sampled.** `layout_lint.py` passes, and no game raster is in the repo.
2. **Every region has a signature landform and at least one XL silhouette** (`l_kinds` >= 10; F-WEENIE in section
   5.4).
3. **No repeated town plans in a region.** `y_looks` >= 2 per region, every named town has a unique plan or its own
   sub-kit, and the `render_town.py` contact sheet passes Haiku review as "not a reskin".
4. **Every settlement has an industry with jobs and a regional product.** `population_lint.py` reports 0 unstaffed
   workplaces, and a regional ingredient or ore is sold there.
5. **Every dungeon has a hook, a named boss or reason, and a note** (`d_identity` = 1.0). Major dungeons have a loop
   with a return path (F-LOOP).
6. **The region story exists and is reachable from the first town on the main road** (F-HOOK).
7. **The wilderness is paced.** `ecology_report.py` road300 is <= 5% by day, and >= 8 scripted encounter kinds exist
   per region (F-ENC).
8. **All text is original and varied.** `novelty_lint.py --strict` reports 0 pairs, and the MS20 corpus check
   reports 0 hits.
9. **The source's limits are gone.** The phase report has a short "what the source did vs what we do" table for the
   province, one row per row of 1.2.

---

## 2. Research inputs

### 2.1 The owner's PDF: "The Elder Scrolls: A Comprehensive Reverse-Engineering and Data-Extraction Analysis"

**What it is.** It is a survey of the fan and community toolchain around the series, not a design document. It
covers:

* open engine re-implementations: OpenTESArena, Daggerfall Unity, OpenMW and its multiplayer fork TES3MP;
* runtime plugin frameworks for the Creation Engine: Ghidra-based symbol recovery, CommonLibSSE-NG, the Address
  Library and SKSE;
* data editors: xEdit;
* terrain tools: TESAnnwyn heightmap import/export, World Machine, L3DT, GeoControl, and how Project Tamriel and
  Beyond Skyrim build unrepresented provinces;
* the Havok animation and behaviour toolchain: hkxcmd, HKX2, blender-hkx, HKXPoserNG and creature retargeting;
* ESO's Granny/Oodle mesh format and batch converters;
* archive extraction: BSArch;
* the UESP ESO map;
* lore corpora: the Imperial Library's books dataset, dialogue tables, ESO string dumps and mobile spin-off text.

It ends with a reference list of 72 sources.

**Main insights (paraphrased):**

1. **Content was always data over geometry.** Daggerfall's town blocks carry no names or textures of their own. The
   region data supplies the name seeds and the climate swaps the materials, so one block serves a desert or a snowy
   town. Dungeons are assembled from blocks with "inner" pieces that open on all sides and "border" pieces that cap
   the edges, so any enclosed arrangement is navigable by construction.
2. **The lore corpus is huge and structured:** over a million words and about 4,800 books with metadata, dialogue
   as tables, and ESO strings per update.
3. **The unrepresented provinces (Hammerfell, High Rock, Cyrodiil, mainland Morrowind) are where fans have spent
   decades of work.** That work uses professional terrain generators (erosion, tectonics), then imports. The demand
   is for exactly the regions Skycraft's `PARITY.md` shows are thinnest.
4. **Big worlds hit engine limits.** Skyrim's physics drifts beyond about 64 cells from the origin, so fan projects
   split land into separate worldspaces. Multiplayer (TES3MP) must put rest, fast travel and the clock under the
   server, and must serialise journal and NPC state.
5. **Region-specific fauna with its own movement is what makes a new province feel real.** Beyond Skyrim retargets
   creature skeletons for Hammerfell scorpions and the like.
6. **ESO's geography is the most complete public record of every province's zones** (the UESP map).
7. **Licensing is the fault line.** Engine code is often open (MIT for Daggerfall Unity, GPL for OpenMW), but game
   data never is, and AI-assembled code drawn from mixed sources has raised IP and security worries in the
   community.

**What the PDF does not give us.** It gives no open-world design principles. Sections 3 to 5 of this document are
our own synthesis from the games themselves and from the existing Skycraft docs and playtests.

### 2.2 Resource inventory, licensing posture and mapping to Skycraft

Posture key: **REF** means facts or inspiration only (read it, never ship from it). **LOCAL** means the owner may run
it on his own game install, with output kept in the gitignored `.reference/`. **NO** means not used. No row permits
copying game assets or text.

| Source / tool | What it is | Good for | Posture | Skycraft use |
| --- | --- | --- | --- | --- |
| OpenTESArena | Open C++ re-implementation of Arena (needs the original data) | Arena's continent sketch, its province and city list, dungeon archetypes | REF | Province and city names and rough positions (already in `places/`) |
| Arena text dumps (dungeon and city strings, via the Imperial Library) | Raw strings | Which city and dungeon types existed | REF | Nothing beyond facts |
| Daggerfall Unity (MIT code; data not redistributable) | C# port reading BLOCKS.BSA and MAPS.BSA | Iliac Bay geography, region list, location types, guild and temple network, the dungeon assembly rule | REF, LOCAL (`.reference/daggerfall_ref`) | ILIAC BAY geography (FIRST-PLAYTEST known gap); assembly principle maps to `dungeon_reach.py` |
| DFU mods (RMB Resource Pack, World of Daggerfall) | New blocks; wilderness instance lists (shrines, ruins, bandit camps by region) | Proof that the wilderness needs purposeful roadside content at volume | REF | Same idea as our scene plan (P6) and minor sites; nothing copied |
| Daggerfall Online / daggerfall-js bible (owner's local copy) | A browser port's design bible: world bosses on a shared clock, bounty boards, living-world NPC travel, travel options, a quest-guide layer | How systems fit together (section 2.3) | REF | Specs MS4, MS5, MS12, MS13 |
| DFUVR, Android ports | Platform ports | Not relevant | NO | None |
| OpenMW (GPLv3; needs Morrowind data) | Morrowind engine | Morrowind's world and faction structure as facts | REF, LOCAL (`.reference/morrowind_ref`) | Vvardenfell places (done), Great House structure (P-J1) |
| TES3MP (GPLv3) | Morrowind multiplayer | Server-owned clock, rest and fast-travel sync, shared journal | REF | Already our model (server-authoritative, sleep vote); MS4 and MS5 run on the server clock |
| Ghidra, Steamless, .pdb symbol recovery | Executable reverse engineering, DRM unwrapping | Nothing we need; legal risk | NO | None |
| CommonLibSSE-NG, Address Library, SKSE | Skyrim runtime plugin frameworks | Not relevant to a Forge mod | NO | None |
| xEdit (SSEEdit, TES4Edit, TES3 editors) | Record browser for .esm/.esp | Extracting facts from the owner's own install: marker positions, counts of quests per hold, encounter-zone levels, faction lists | LOCAL | Fact tables in `.reference/` (as `markers.json` was); counts that calibrate section 4 |
| TESAnnwyn | Heightmap import/export (16-bit) | Reading the owner's Skyrim heights to `.reference/` | LOCAL | Already done for Skyrim (`heightmap_tamriel_ref.png`, never committed or sampled into shipped data) |
| World Machine, L3DT, GeoControl | Commercial terrain generators | Not needed; we have `hydrology.py`, erosion and the plan-first bake | NO | The principle only: erode, then place |
| Project Tamriel, Tamriel Rebuilt, Beyond Skyrim, the All Tamriel Heightmap mod | Fan builds of the missing provinces | Their invented places, maps and assets are the fan authors' own work | REF for canon facts only; never trace their maps or reuse their invented names | Nothing; use canonical game facts and our own invention |
| Havok tools (hkxcmd, HKX2, blender-hkx, HKXPoserNG, creature retargeting) | Skyrim animation pipeline | Not applicable (our creature rigs and animations are our own work) | NO | The principle: province creatures move differently (PA15) |
| Granny .gr2, Oodle, Transmogrifier, BSArch | Mesh and archive extraction | Nothing shippable | NO (BSArch LOCAL only to inspect the owner's own files) | None |
| UESP ESO gamemap | Stitched map of all ESO zones | Zone names, borders and relative positions of towns, delves, public dungeons, world bosses, wayshrines; counts per zone | REF | Region borders and place relative positions for non-Skyrim regions (most regions are ESO zones); counts for the optional layer (4.12). The floor itself is calibrated from Skyrim (4.1) |
| Imperial Library (books dataset, game text) | About 4,800 books with metadata; Arena to ESO text | Lore facts, who and what and when; checking we contradict nothing | REF; LOCAL corpus | MS20: a local no-verbatim check; never copy text |
| Skyrim dialogue tables and sentiment lexicon | Dialogue as CSV | Nothing to ship | LOCAL | MS20 corpus only |
| ESO string dumps | Text per update | Names and facts per zone | REF, LOCAL | Facts; MS20 corpus |
| Castles and Blades text | Mobile spin-off logic and strings | Little | NO | None |
| Kirkbride texts, Aldudagga | Metaphysical lore | Themes for mysteries | REF | Lore-web themes in our own words |

### 2.3 What the Daggerfall bible teaches (how a whole TES world's systems fit together)

The bible (`F:\daggerfall-js-source-main\...\bible`) is a port's engineering record. Its world and systems chapters
show which systems a whole-province world needs and how they hand off to each other:

* **Location instances by region at volume.** The World of Daggerfall mod data lists about 228,000 wilderness
  instances: 7,000 bandit camps, 5,000 shrines, 4,000 ruins, plus rocks. Wilderness density is a data list per
  region, built where the location belongs. Our P6 scene plan already works this way, with a reason per scene.
* **Bounty boards.** Each city has notice boards whose hunts name a nearby dungeon, sit 4 to 10 map cells out,
  scale reward with the size of the job, and are shared by everyone in the party. This gives a steady way to send
  players to the many dungeons, which our quests reach only 2% of today (MS12).
* **A living world.** Townsfolk with tasks, sleep, work, caravans, travel between towns and conversations. Our
  society module does most of this, but daily routines are fixed-anchor (FIRST-PLAYTEST known limit).
* **World bosses and gates on a shared clock.** A large area is announced on the map and in chat, a gate opens to
  an arena with a telegraphed boss, and per-player loot drops. This is the ESO world-event idea for a multiplayer
  Minecraft server (MS4, MS5).
* **Travel options.** Fast travel can be a fade, or a walked journey at high time speed that encounters, locations,
  wounds and the sea can interrupt. Lesson: fast travel should not skip the world entirely. Carriages and ferries
  show the route (our ferries already do).
* **A quest-guide layer.** A read-only lens over the quest machine: the journal links an entry to a place, notifies
  when the journal changes, and shows deadlines. Lesson: signposting is a presentation layer over quests and must
  be built on purpose (MS13).
* **Guilds and temples in every town, reputation per region, banks and houses, holidays on the calendar.** These are
  Daggerfall's strengths, and principles P-J4 and P-J5 take them over.

---

## 3. Open-world design principles

Each principle has a source idea, a Skycraft rule, a target and its check. Ids (P-A1...) are referenced from
sections 4 and 5.

### 3.1 Skyrim

**A. Space and density**

| Id | Principle (why it works) | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-A1 | **Something every minute.** Skyrim packs over 400 map markers (`markers.json`: 436), plus many unmarked sites, into the 26.5 km² Skycraft gives it, so you rarely walk two minutes without a find | A notable point of interest close to almost every point of land | POI within 150-250 blocks of >= 90% of land; major site within 600 blocks of >= 90% | `explore/density_audit.py` (met in every region today) |
| P-A2 | **Density is depth, not dots.** A Skyrim place has a name, a person or story, and a reason to go | Every place carries a hook, a rumour and (for 40% or more) a note | `d_identity` = 1.0, `r_places` >= 11.4/km², `r_notes` >= 44.7/km² (floors) | `parity.py` DUN, LOR |
| P-A3 | **Spacing by tier.** Big things are far apart, small things close | Grid spacing per landmark tier (`landmarks/Tier.java`) and minor-site cells of 160 blocks | S about 17/km², M about 9/km², L about 4.5/km², XL >= 0.33/km² (today 0.075: raise it, see P-B2) | `/skycraft-landmarks`, F-WEENIE |
| P-A4 | **Hold identity.** Each hold has its own colour, land, people, threat and industry | Each region differs in palette, landform, culture mix, creature mix, industry and voice | Category floors in `PARITY.md` 5; `y_looks` >= 2, `l_kinds` >= 10, `c_total` >= 25 per region | `parity.py --assert-floor` |

**B. Discovery, sightlines and weenies**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-B1 | **The rule of three (discovery loop).** You set out for something you can see, and on the way two more things catch your eye, so a trip becomes a chain | Design the land so any goal has two or more detours on the way. From a vantage point you can see or hear about at least three things | On a road walk, >= 2 secondary POIs within 120 blocks of the route per 600 blocks; from a hilltop vantage, >= 3 distinct POIs with line of sight within 250 blocks (vanilla render distance) and >= 5 within 1,000 (Distant Horizons) | F-VISTA, F-DETOUR (new, 5.4) |
| P-B2 | **Weenies.** A tall, unique silhouette pulls you across the land (a mountain peak, a palace on a rock, a tower, a giant's fire) | Every region has a visible-from-afar silhouette: an XL landmark, a marquee tower or palace, a lit beacon or a named peak. Dungeon exteriors on heights (barrows on hills) | >= 70% of land points see a weenie within 1,500 blocks (DH); every region has >= 1 XL landmark plus 1 per 3 km² | F-WEENIE (new) |
| P-B3 | **Environmental storytelling.** Skeletons in a pose, a note in a pack, a ruined camp tell a story without dialogue | Every scene, minor site and dungeon room set piece tells one small story; the lore web places 3 clues per place | Every minor-site kind and scene kind has `purpose`, `needs` and `clue` (already in `placement_lint`); >= 1 story set piece per dungeon | `placement_lint.py`, `dungeon_identity_lint.py` |
| P-B4 | **Soft guidance.** Smoke, light, paths, banners and sounds lead the eye | Camps have smoke, towers burn lights at night, worn paths lead from the road to a site, shrines stand at forks | Every named site within 300 blocks of a road has a worn trail or sign (P6 trails and signs) | `reach_lint.py`, sceneplan stats |

**C. Dungeons**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-C1 | **Loop, then shortcut home.** A Skyrim dungeon is a one-way loop that ends near the entrance with a lever, door or drop | Major and marquee dungeons are loops: the boss room opens a shortcut back | >= 90% of major, 100% of marquee | `dungeon_audit.py` (F-LOOP) |
| P-C2 | **Kit-built, hand-composed.** Rooms come from kits, but each dungeon's order, story and set piece are authored | Room templates per culture kit (>= 6), set pieces, traps and puzzles per culture | `d_rooms` >= 40, `d_built` >= 5 per region | PA12 |
| P-C3 | **A reason and a reward.** A named boss, a chest, a unique, a word wall or a lore payoff | Each dungeon has a boss or named threat, a boss chest, a hook, and for major ones a unique, a power or a story payoff | 100% hook; >= 30% unique, power or quest payoff | `d_identity`, `l_unique` |
| P-C4 | **Pacing inside.** Quiet rooms, sleepers vs awake, traps, a set-piece hall, light and dark | Existing room grammar (sleepers, grand caverns, back doors) | Every major dungeon has >= 1 set piece and >= 1 quiet room | `dungeon_identity_lint.py` |
| P-C5 | **Length tiers.** Small caves take 5 to 10 minutes; great ruins take an hour | Short (5-12 min), major (15-40 min), marquee (40-90 min) | Room-count bands per class (4.5) | `dungeon_stats.py` |

**D. Quests and story**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-D1 | **Authored stories carry the world; radiant work fills the gaps.** | Every region story and side quest is authored; radiant templates are filler with named givers and places | Authored >= 70% of the quests a player meets in a region | `parity.py` QST + F-RADIANT share |
| P-D2 | **Quests point at places.** In Skyrim nearly every quest uses a real location (93%) | Quests use registered places | `q_placed` >= 0.9 | `parity.py` |
| P-D3 | **Consequence.** The world remembers: a jarl changes, a shop opens, a ghost is gone | Each side chain ends with a world change: an NPC state, a building, a dialogue line or a scene | >= 1 visible consequence per side chain | New field `consequence` in quest JSON (MS1 schema); `quest_lang.js` validator |
| P-D4 | **Many ways in.** Overheard talk, a note, a courier, a board, finding the place first | Quest starts: npc, find (hidden clue), auto (chain), rumour, note-in-dungeon, board (MS12) | Each region uses >= 4 start kinds | F-STARTS (new) |

**E. Pacing: peace and danger**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-E1 | **Quiet by default.** Long walks with music, wind and wildlife | Quiet weights, quiet roads (`ECOLOGY.md`) | road300 <= 5% by day and <= 20% by night | `ecology_report.py` |
| P-E2 | **World encounters, not random attacks.** Skyrim's roads stage small scenes: a courier, a prisoner escort, hunters, a fight in progress | A library of scripted encounters per region, anchored to roads and wild spots | >= 40 kinds in total, >= 8 per region, >= 3 of them unique to the province | `scenes.json` count (F-ENC) |
| P-E3 | **Danger lives in places.** Camps, lairs, ruins, the night, the high peaks | Hostile humanoids come from camps and lairs, the dead from ruins at night, trolls above a height | Day hostile-humanoid share <= 7% of attempts | `ecology_report.py` |
| P-E4 | **Wonder moments.** Auroras, a dragon on a peak, a herd at dawn, a waterfall cave, a hot spring | Each region scripts >= 3 "wonder" moments: weather or sky events, vistas, creature spectacles | >= 3 per region (data in region scene table) | F-WONDER (new) |

**F. Travel**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-F1 | **Walk first, fast travel second.** Fast travel only to places you have found, so the first trip is on foot | Already the rule (map to discovered places) | Unchanged | none |
| P-F2 | **Roads as content.** Signs, inns, shrines, waysides, patrols, caravans | Waysides every 1.5-3 km, signposts at every junction, roadside inns | Waysides per km in `placement_lint` bands; every junction signed | sceneplan stats, F-SIGN (new) |
| P-F3 | **Paid travel shows the land.** Carriages between hold capitals | Carriages, striders and ferries per province (MS11) | Every city on a paid route | F-TRAVEL |

**G. Factions and towns**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-G1 | **Guild homes are places.** Each Skyrim faction owns a building in a different city and gives that city meaning | Each province has 2-4 joinable faction hubs, spread over different cities | >= 2 per province | Faction data, F-HUBS |
| P-G2 | **Towns run on an industry.** Mines, mills, farms, docks | Every village and larger has a work site with jobs and sells its product (`Zoning.ensureTrades` plus industry) | 0 unstaffed workplaces; 1 industry per settlement | `population_lint.py`, `town_checks.py` |
| P-G3 | **Courts and politics.** A jarl, a steward, a court wizard, rivals | Every capital and city has a court of 3 or more named roles with a local political problem | 100% of capitals and cities | F-COURT (new, from NPC data) |

**H. Economy**

| Id | Principle | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-H1 | **Regional goods.** What a place sells says where you are | Regional ingredients, ore, fish, gear | `i_ingr` >= 30 per province (stage 1); each town sells >= 2 regional goods | `parity.py` ITM |
| P-H2 | **Reasons to come back to town.** Sell, craft, train, rest, turn in quests | Every town has the services of its tier (4.4) | 100% | `town_checks.py`, `population_lint.py` |

### 3.2 ESO: zone structure, and what it improved in the flow

**Status: an idea pool, not the bar.** Nothing in this subsection is part of the floor a region must meet. The floor
is Skyrim, measured (4.1). An ESO idea may be added to a region only on top of that floor, through the region's world
model sheet (it must cite sheet fields like any other content). It is then tracked in the optional layer (4.12). The
targets in the table below apply only to a region that adopts the idea.

**What every ESO zone contains** (a design fact, paraphrased): a zone storyline; a set of solo delves (about six),
each with a boss and a skill-point shard; one or two public dungeons with several named bosses and a group event;
about six open-world bosses; about 16 shards (three make a skill point); about 15 to 20 wayshrines; a dozen or more
points of interest, each with a small story; lorebooks in collections; three crafting set stations; six treasure
maps; one survey per gathering craft; a repeatable world event (Dark Anchors, then a new kind per chapter zone: rifts,
storms, dragons, geysers); a zone guide and achievements that track all of it; and a guild or faction hub in the
zone's main city.

**What ESO improved in the flow:**

| Id | Improvement | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-I1 | **Every zone tells its own story.** You learn a zone's politics and people by playing its chain | A region storyline per region (MS1) | 5-8 quests per region | F-STORY |
| P-I2 | **Clear signposting.** Quest givers are marked, points of interest show on the compass when near, and a zone guide lists what is left | A compass hint for undiscovered places within 150 blocks, a quest-giver marker in towns and a Region Guide (MS2, MS13) | Every region has a complete guide | F-GUIDE |
| P-I3 | **Collectibles that pay.** Shards give skill points, lorebooks give mage-guild progress, and set stations give gear builds, so exploring builds the character | Star-shards to perk points, lorebook collections, regional set stations (MS3, MS8, MS10), optional layer | Counts in 4.12 if adopted | F-COLLECT |
| P-I4 | **Repeatable world events draw players together.** | A region-flavoured world event at 3-5 sites per region on the server clock (MS4) | 1 kind per region, 3-5 sites | F-EVENT |
| P-I5 | **Group content in the open world.** World bosses and public dungeons scale for groups and can be soloed carefully | World bosses and an open dungeon per region (MS5, MS6), scaled by party size (the party system exists) | 1-4 world bosses and 1 open dungeon per region | F-GROUP |
| P-I6 | **Overland content scaling.** Go anywhere in any order without hitting a wall | Hybrid: story and side quests scale to the player within a region band, and the wilderness keeps its region band for identity (P-J3) | No region story quest is more than 8 levels above or below the player | F-LEVEL |
| P-I7 | **Points of interest each hold a micro-story.** | Every M or L landmark and every minor site with a note has a small optional objective (MS18) | >= 1 micro-objective per km² | F-POI |

### 3.3 Morrowind, Oblivion and Daggerfall

| Id | Strength | Skycraft rule | Target | Check |
| --- | --- | --- | --- | --- |
| P-J1 | **Morrowind: exclusive factions and alien uniqueness.** Great Houses you can join only one of, cities you could never mistake (a city of cantons, mushroom towers, a giant crab shell) | Mutually exclusive faction choices per province where lore has them (houses, crowns and forebears, clans); each city's silhouette tag is unique (marquee) | >= 1 exclusive faction choice in Morrowind, Hammerfell and High Rock | Faction data |
| P-J2 | **Morrowind: travel networks as world-building.** Striders, boats, guild guides, propylons, intervention spells | Province travel networks (MS11) | Each province has >= 2 travel modes beyond walking and horse | F-TRAVEL |
| P-J3 | **Oblivion: the region band.** Oblivion's levelling everywhere made the world flat. Skyrim's encounter zones per location gave places character | Keep `Leveling.regionLevel` bands (gentle Whiterun, fierce Winterhold), and scale authored quests within the band (P-I6) | Band width <= 20 levels per region | F-LEVEL |
| P-J4 | **Oblivion: gates as world events, cities with guild halls, roadside wayshrines and Ayleid wells.** | Oblivion gates in Cyrodiil become MS4 world events; wayshrines become fast-travel nodes and blessings (MS11) | Cyrodiil: gates as an event at >= 3 sites per region | F-EVENT |
| P-J5 | **Daggerfall: scale, guilds and temples everywhere, regional politics and reputation, bounty boards, banks, holidays.** | Per-region reputation (exists per hold for crime); notice boards (MS12); a festival calendar (MS16) | 1 festival per province, a board in every town and larger settlement | F-BOARD |

---

## 4. Region content template

### 4.0 What made Skyrim great (rules every province must meet)

Skyrim's quality is not its dot count. It comes from a handful of things it did everywhere. Each one below is a rule
for every Skycraft province, with the check that proves it. The first principle (world building is king) comes before
all of them: each rule is met through the region's world model sheet, never as decoration.

| # | What Skyrim did | Rule for every Skycraft region | Check |
| --- | --- | --- | --- |
| G1 | **Each hold has an identity you can feel within seconds.** The Reach's red crags and stone city, the Pale's snow and mining town, the Rift's autumn birches and canals | Each region differs from its neighbours in palette, landform, architecture, weather, music cue, politics and creature mix, all drawn from its sheet (L1-L9) | Blind test: Haiku names the region from 3 random screenshots at >= 80%; `y_looks` >= 3, `c_total` >= 25, a region music cue (PA20) |
| G2 | **A skyline pulls you off the road.** A tower on a ridge, a barrow on a hill, smoke from a camp, a peak, a ruin's arch, about every few hundred blocks | Weenies and detours are planned from sightlines, not scattered | F-WEENIE >= 70%; F-VISTA >= 3 within 250 blocks; F-DETOUR >= 2 per 600 blocks of road |
| G3 | **Every location is hand-made and tells a story without words.** A note, a body, a camp left in a hurry, a trap that already caught someone | Every named site and every scene has an environmental story that says what happened, with its cause in L9 or L10 | `d_identity` 100%; every dungeon has >= 1 story set piece; `placement_lint` clue on every scene kind |
| G4 | **Dungeons are loops with a reward and a shortcut home.** | Major and marquee dungeons loop back; each has a boss or named threat, a reward and a reason (L10) | F-LOOP >= 90% / 100%; P-C3 |
| G5 | **Towns are people.** Named residents with homes, jobs, routines, local politics and problems; a court in every seat | Skyrim's measured counts per tier (4.4): named NPCs, roles, services, quests | Tier floors in 4.4; F-COURT 100%; `population_lint` 0 unstaffed workplaces |
| G6 | **Quests are tied to places and factions, and change things.** A jarl falls, a mine reopens, a family is ruined | Every authored quest names a real place and person and leaves a consequence | `q_placed` >= 0.9; >= 1 consequence per side chain (P-D3) |
| G7 | **The road network reveals the land.** Following the main road passes most of the hold's kinds of place | The main roads pass within sight of >= 60% of the region's settlements and site kinds; every junction has a sign | `reach_lint` no isolated major site; F-SIGN >= 95%; F-DETOUR |
| G8 | **Peace and wonder between dangers.** Long quiet walks, an aurora, a herd at dawn, then a camp you chose to attack | Danger lives in places and at night; wonder moments are scripted per region | F-QUIET (<= 5% day, <= 20% night); F-WONDER >= 3 |
| G9 | **Secrets everywhere.** Unmarked ruins, a chest under a waterfall, a hidden room, a skeleton with a note | Unmarked sites and hidden caches at the measured density (4.3), each with a reason from the sheet | Unmarked dungeons >= 0.57/km²; hidden caches and treasure maps per 4.3; F-REWARD-RATE >= 1 per 10 min |
| G10 | **Music and weather set the mood.** Storms in the north, mist in the marsh, a different track in a barrow | Weather and fog tables come from L2 and the micro-biomes (MS21); music cues per region and per place kind | Every region has an L2-driven weather table and a music cue; `m_amb` floor |
| G11 | **The world reacts.** Guards comment on what you did, people remember your deeds, a jarl thanks you | Reactive lines keyed to player deeds per region (guards, innkeepers, courts) and to quest outcomes | F-REACT (new): >= 12 deed-keyed lines per region, every finished side chain changes >= 1 line |
| G12 | **Mini biomes inside biomes.** Hot springs inside the tundra, a birch glade in a pine forest, salt marsh at a river mouth, each matching the land, the weather and the people | Each region is a mosaic of micro-biomes caused by its geography (MS21), each changing flora, ground, weather, creatures and work | MS21 checks: >= 6 micro-biomes per region, >= 2 per km², each tied to a sheet cause |

**Expand to the whole of Tamriel.** These rules apply to every province, but the answers are its own. Skyrim is the
measure of depth, not a stencil. A Hammerfell region meets G1 with its own palette, sandstone domes, sandstorms,
Yokudan music and Crown-and-Forebear politics. It meets G5 with its own trades and courts, and G12 with its own
mosaic (oases, salt flats, wadis, canyon shade). The same eleven-layer sheet drives it, so Black Marsh's dungeons
follow its drowned ruins and Hist politics, and Summerset's towns follow its castes and its ports. A region that only
copies Skyrim's shapes in a new colour fails G1 and the coherence check.

### 4.1 The Skyrim baseline (measured) and how to read the counts

**Source and method.** All numbers come from the owner's Skyrim Special Edition install (base game plus the three
official add-ons), read locally with the `.reference/skyrim_ref` scripts. Only counts and facts are recorded; no
text, names of invented people or assets enter the repo (legal line). Each count was attributed to a hold through
Skyrim's own location records (location parent chains). Where that failed, positions were used (the nearest map
marker of a known hold). Areas are **Skycraft km²** (1,000 x 1,000 blocks) of the same hold on our map, which is
what `parity.py` divides by. One Skycraft km² is about 0.8 real km². Solstheim is shown for comparison but is not in
the median.

What each column means:

* **Map markers**: every place on the in-game map.
* **Dungeons**: named location records of a dungeon kind, counted once at the top level (an interior sub-location
  is not counted again).
* **Unmarked**: a dungeon location with no map marker.
* **Named NPCs**: unique, named actors placed in the hold.
* **Merchants**: members of a vendor faction.
* **Trainers**: people whose class offers training.
* **Quests**: journal quests with objectives, attributed to the hold through their quest giver's settlement or the
  places they use. Another 97 journal quests use places across all of Skyrim and belong to no single hold.

The figures are lower bounds wherever attribution was uncertain.

**Per hold (nine holds): totals and density per km².**

| Measure | Min / median / max per hold | Per km² min / **median** / max | Solstheim (0.98 km²) |
| --- | --- | --- | --- |
| Land area (Skycraft km²) | 1.41 / 2.91 / 4.64 | n/a | 0.98 |
| Map markers (all kinds) | 29 / 41 / 56 | 11.0 / **14.6** / 22.0 | 52 |
| Dungeon locations (named, all themes) | 18 / 26 / 37 | 6.7 / **8.9** / 14.9 | 35 |
| Distinct dungeon inhabitant themes | 8 / 10 / 13 | n/a | 13 |
| Unmarked dungeon locations | 1 / 1 / 3 | 0.22 / **0.57** / 1.42 | 2 |
| Settlements (all tiers) | 2 / 4 / 9 | 0.43 / **2.0** / 3.1 | 4 |
| Named NPCs | 41 / 105 / 140 | 16.6 / **33.6** / 68.5 | 80 |
| ... of whom live in settlements | 14 / 92 / 124 | n/a | 42 |
| Merchants | 3 / 17 / 26 | 0.86 / **5.0** / 10.1 | 16 |
| Trainers | 5 / 9 / 14 | 1.08 / **3.55** / 5.36 | 7 |
| Journal quests attributed to the hold | 8 / 33 / 77 | 2.6 / **9.7** / 22.0 | 48 |
| All journal quests, Skyrim-wide (incl. the 97 shared) | 371 in nine holds | **14.0** (whole of Skyrim) | n/a |
| Skill books placed (copies) | 27 / 41 / 62 | 10.8 / **16.2** / 20.8 | 1 |
| Books, notes and letters placed (copies) | 263 / 568 / 1,054 | 104 / **195** / 627 | 480 |
| Distinct readable titles found in the hold | 143 / 182 / 252 | n/a | n/a |
| Spell tomes placed | 0 / 3 / 5 | 0 / **1.1** / 2.1 | 2 |
| Hand-placed enchanted gear (not levelled) | 2 / 5 / 14 | 0.68 / **2.9** / 6.9 | 17 |
| Loot containers (chests, satchels, urns, strongboxes) | 245 / 406 / 561 | 83 / **131** / 320 | 554 |
| Word walls (power sites) | 1 / 6 / 8 | 0.34 / **1.5** / 4.6 | 7 |
| Dragon mounds (outdoors) | 0 / 2 / 4 | 0 / **0.96** / 1.4 | 2 |
| Standing stones | 0 / 1 / 4 (13 in all) | 0 / **0.34** / 1.4 | 6 |
| Key relics (dragon claws) | 0 / 1 / 3 | 0 / **0.43** / 1.0 | 0 |

**Marked places by physical kind (per hold; per km²).**

| Kind | Min / median / max | Per km² min / **median** / max |
| --- | --- | --- |
| Caves | 4 / 8 / 13 | 2.0 / **2.6** / 6.0 |
| Ancient tombs and ruins (Nordic ruins) | 1 / 4 / 7 | 0.34 / **1.44** / 3.4 |
| Forts | 1 / 3 / 4 | 0.65 / **1.14** / 1.8 |
| Military camps (both sides of the war) | 2 / 3 / 4 | 0.43 / **1.03** / 1.8 |
| Watchtowers (Imperial and Nordic) | 0 / 2 / 4 | 0 / **0.69** / 1.8 |
| Shacks, dwellings and small camps | 1 / 3 / 9 | 0.6 / **1.29** / 2.8 |
| Landmarks (vistas, passes, clearings, lighthouses) | 1 / 2 / 5 | 0.22 / **0.86** / 1.2 |
| Farms and mills (marked separately) | 0 / 1 / 4 | 0 / **0.68** / 1.1 |
| Mines | 0 / 1 / 2 | 0 / **0.43** / 0.71 |
| Elder-race ruins (Dwemer) | 0 / 1 / 4 | 0 / **0.24** / 2.3 |
| Dragon lairs | 0 / 1 / 3 | 0 / **0.34** / 1.4 |
| Shipwrecks (coastal holds) | 0 / 1 / 4 | 0 / **0.34** / 1.8 |
| Giant camps | 0 / 1 / 3 | 0 / **0.22** / 1.7 |
| Groves | 0 / 1 / 2 | 0 / **0.24** / 0.57 |
| Daedric shrines | 0 / 1 / 1 | 0 / **0.22** / 0.60 |
| Orc strongholds | 0 / 0 / 2 (4 in all) | lore-placed |

**Dungeons by inhabitant theme (per hold).** Draugr barrows 1 / 4 / 8. Bandits (camps, towers and caves) 0 / 2 / 6.
Mage, hagraven and vampire lairs 0 / 3 / 8. Forts 1 / 2 / 5. Beast caves 1 / 1 / 5. Falmer hives 0 / 1 / 4. Dwemer
ruins 0 / 1 / 2. Dragon lairs 0 / 1 / 3. Giant camps 0 / 1 / 3. Other or mixed: 2 / 4 / 6. Some themes appear in one
hold only and become that hold's signature: Forsworn camps (7, all in the Reach), shipwrecks (4 in Winterhold),
spriggan groves (4 in Falkreath) and Riekling camps (6 on Solstheim).

**Quest kinds per hold (attributed).** Faction 1 / 6 / 14. Side 0 / 3 / 7. Daedric 0 / 2 / 4. Miscellaneous 2 / 3 /
41. Radiant 1 / 4 / 22. Main 0 / 0 / 11: the main story moves across the whole province and sits mostly in its
heart.

**How to read the counts.**

* **The floor is Skyrim's median per km², scaled by area**, for every Skycraft region whatever its province. A
  region's count is `max(absolute floor, round(median density x km²))`.
* **The absolute floor is Skyrim's minimum per km²** (the thinnest hold). No region goes below it, even where the
  terrain is hostile (deserts, swamps, ice).
* **Counts per settlement are Skyrim's medians per tier** (4.4), with the tier minimum as the absolute floor.
* **Fixed** counts apply to every region whatever its size: a seat with a court, a marquee place and a hold
  conflict chain.
* Size classes: **small** under 2 km² (Bitter Coast, Artaeum, Stros M'Kai, Tang Mo...), **medium** 2-6 km² (most
  Skyrim holds), **large** over 6 km² (Glenumbra 12.4, Summerset Isle 10.4, Wrothgar 10.8...). A large region should
  be planned as two hold-sized halves, each with its own seat or town cluster.
* The foreign lands (Atmora, Akavir, Yokuda, Pyandonea, Thras) use the template at half density. They are
  expeditions, not homelands.
* Status: **EXISTS** means the system is built and only content is missing. **PARTIAL** means part of the system
  exists. **MISSING** means a new system is needed (specs in 4.11).
* Honest note: at Skyrim density, a 4 km² region needs about 130 named NPCs, 36 dungeons and 55 quests, and Skycraft
  has 71 regions. Hand authoring cannot reach that everywhere. Generators that read the world model sheet (named
  residents from the population grammar, dungeon identity, quest templates bound to real places) carry most of it,
  and authored work goes where the player meets it first. Generated content must still pass the coherence check.

### 4.2 Province layer (every province)

| Item | Count | Maps to | Status |
| --- | --- | --- | --- |
| Province world model sheet (11 layers) and a world truth paragraph | 1 | MS22 | MISSING |
| Capital with court, palace and seat-of-power landmark | 1 | `places` capital, `cityplans/`, marquee | EXISTS (plans vary) |
| Province main story moving across its regions (as Skyrim's main quest crosses every hold) | >= 19 quests (Skyrim: 19 main quests, plus 23 in the add-ons) | Quest chain data | PARTIAL |
| Joinable faction hubs, in different cities | 2-5, each with 6-14 quests and ranks (Skyrim: 59 faction quests over four guild lines and the civil war) | `quest/faction`, `Factions` | PARTIAL (Skyrim 7; others few) |
| A province-wide conflict that touches every region (Skyrim: the civil war's camps and forts in every hold) | 1 | Faction data, military camps | PARTIAL |
| Exclusive faction choice where lore has one | >= 1 (Morrowind, Hammerfell, High Rock, Elsweyr) | Faction data | MISSING outside Skyrim's civil war |
| Continent lore-web thread passing through | >= 1 | `LORE-WEB.md` threads | EXISTS |
| Travel network (carriage, strider, ferry, guild guide, wayshrine) | >= 2 modes | `roads/Carriages`, `StriderPorts`, `sailing` | PARTIAL (MS11) |
| Signature creatures | >= 4 (hostile, wildlife, rare, boss variant) | PA15 | PARTIAL |
| Signature gear families, including faction sets | >= 8 (stage 1), 12 target | PA21 | PARTIAL |
| Legendary uniques | >= 6 | `LegendaryUniques` | PARTIAL |
| Music | >= 4 exploration tracks, 2 town tracks per culture | PA20 | PARTIAL |
| Festival on the calendar | >= 1 | MS16 | MISSING |

### 4.3 Region layer: the Skyrim floor (every region)

"Median" and "abs." are per Skycraft km²; the example is a medium region of 4 km². Inhabitants, building styles and
names always come from the region's own sheet. A "Nordic ruin" becomes an Ayleid well-ruin in Cyrodiil, a Velothi
tomb in Morrowind or a Yokudan necropolis in Hammerfell.

| Item | Fixed | Median / km² (abs. floor) | 4 km² example | Maps to | Status |
| --- | --- | --- | --- | --- | --- |
| **World model sheet and world truth paragraph** | 1 | n/a | 1 | MS22 | MISSING |
| **Hold conflict** (local politics as a quest chain that changes the region; Skyrim: one or more per hold) | >= 1 chain of 3+ quests | n/a | 1 | Quest chains, MS1 schema reused | PARTIAL |
| **Marquee place** (hand-authored dungeon, city or XL landmark with a mechanism) | >= 1 dungeon + 1 other | n/a | 2 | `marquee.json`, PA13 | PARTIAL |
| Map-marked places (all kinds) | n/a | 14.6 (11.0) | 58 | `places`, landmarks, structures | EXISTS (count) |
| **Dungeons** (named, with an interior) | n/a | 8.9 (6.7) | 36 | `places` dungeon_fixed and fort, structures | EXISTS |
| ... caves | n/a | 2.6 (2.0) | 10 | Carved dungeons | EXISTS |
| ... ancient tombs and ruins of the region's old culture | n/a | 1.44 (0.34) | 6 | Built dungeons, culture kits | PARTIAL |
| ... forts and keeps | n/a | 1.14 (0.65) | 5 | Forts | EXISTS |
| ... elder-race ruins (Dwemer, Ayleid, Yokudan...) | n/a | 0.24 (0) | 1 | Built dungeons | PARTIAL |
| ... mines (worked or abandoned) | n/a | 0.43 (0) | 2 | Mines on L1 ore | PARTIAL |
| Distinct inhabitant themes among the dungeons | >= 8 (target 10) | n/a | 10 | `d_rosters`, dungeon identity | PARTIAL |
| A signature inhabitant theme found mainly in this region (Forsworn-style, from L9 or L10) | >= 1 | n/a | 1 | Rosters, sheet L10 | MISSING |
| Unmarked dungeons and secret sites | n/a | 0.57 (0.22) | 2 | `places` with `hidden: true` | MISSING |
| Military or faction camps tied to the province conflict | n/a | 1.03 (0.43) | 4 | Camps, MS4 precursors | PARTIAL |
| Watchtowers | n/a | 0.69 (0) | 3 | Structures | EXISTS |
| Shacks, dwellings and small camps (someone lives here, with a story) | n/a | 1.29 (0.6) | 5 | Minor sites, PA3 | EXISTS |
| Marked landmarks (vista, pass, clearing, lighthouse) | n/a | 0.86 (0.22) | 3 | Landmarks M/L | EXISTS |
| Great-beast lairs (dragon lairs, giant camps, region analogues) | n/a | 0.72 (0) | 3 | Lairs, `c_bosses` | PARTIAL |
| Shrines of the region's faiths, and power stones | n/a | 0.57 (0.24) | 2 | Shrines, stones | EXISTS |
| Power sites (word-wall analogue: a lore reward at a site) | n/a | 1.5 (0.34) | 6 | Word walls (Skyrim), region analogues | PARTIAL |
| **Settlements** (all tiers, 4.4) | 1 seat | 2.0 (0.43) | 8 | `places` | EXISTS (count; depth missing) |
| **Named NPCs** in the region | n/a | 33.6 (16.6) | 134 | Population grammar, society JSON | PARTIAL |
| Merchants | n/a | 5.0 (0.86) | 20 | Shops, `Zoning.ensureTrades` | PARTIAL |
| Trainers | n/a | 3.55 (1.08) | 14 | Trainer role (missing engine role, 4.4) | MISSING |
| **Quests** available in the region (all kinds; 4.8) | n/a | 14.0 (2.6 attributed) | 56 | `quests/*.json`, radiant | PARTIAL |
| Skill books placed (copies) | n/a | 16.2 (10.8) | 65 | Explore loot | PARTIAL |
| Readables placed (books, notes, letters; copies) | n/a | 195 (104) | 780 | Lore notes, books, loot | PARTIAL |
| Distinct readable titles reachable in the region (province and continent pools count) | >= 143 | n/a | 182 | `lore/books`, notes | PARTIAL |
| Spell tomes placed | n/a | 1.1 (0) | 5 | Loot | PARTIAL |
| Hand-placed named or enchanted gear | n/a | 2.9 (0.68) | 12 | Explore uniques, `LegendaryUniques` | PARTIAL |
| Loot containers (with owners and reasons) | n/a | 131 (83) | 525 | Loot tables, Lootr | EXISTS |
| Key relics that open something (claw-key analogue) | n/a | 0.43 (0) | 2 | Puzzle doors | MISSING |
| Treasure maps leading to caches | 4 | 1 | 4-6 | `explore` treasure maps | EXISTS |
| Lore-web arc (a mystery trail with a finale) | >= 1 (4-8 places) | n/a | 1 | `loreweb_src` | EXISTS for 39 arcs; extend |
| Hidden "find" quests | >= 1 | 0.5 | 2 | `start: find` | EXISTS |
| Regional products (ore, ingredient, wood, fish) sold locally, from L5 | >= 3 | n/a | 3 | Loot tags, economy | PARTIAL |
| Fast-travel nodes (settlements, plus wilderness shrines or stones) | every settlement | +0.7 wilderness nodes | ~4 wild + settlements | MS11 | PARTIAL |
| Scripted world encounters (kinds usable here) | >= 8, >= 3 province-specific | n/a | 8 | `creatures/scenes.json`, `WildScenes`, MS14 | PARTIAL (6 kinds) |
| Wonder moments (sky, weather, vista, creature spectacle) | >= 3 | n/a | 3 | Atmosphere, fauna, MS14 | PARTIAL |
| Micro-biomes (MS21) | >= 6 | >= 2 | 8 | MS21 | MISSING |
| Region voice (about, rumour, work, weather lines) | 4-6 lines | n/a | 6 | PA10 `region_voice.json` | PARTIAL |
| Deed-keyed reactive lines (guards, innkeepers, court) | >= 12 | n/a | 12 | Dialogue keys, F-REACT | MISSING |
| Rumours + legends + songs | 12 | n/a | 12 | Lore region blocks | PARTIAL |
| Planned scenes | n/a | 5-15 (one per ~330 blocks) | ~36 | Sceneplan (P6) | EXISTS |
| Waysides per km of road | 0.33-0.67 highway, 0.25-0.71 lane | n/a | by road | Sceneplan | EXISTS |

### 4.4 Settlements by tier (Skyrim-measured)

**Tiers.** Each tier is mapped to the Skyrim places it was measured on:

* **Capital**: the five walled hold capitals (Solitude, Windhelm, Whiterun, Riften, Markarth).
* **City**: the minor hold seats (Dawnstar, Falkreath, Morthal, Winterhold, plus Raven Rock).
* **Town**: Riverwood-class villages (Riverwood, Rorikstead, Ivarstead, Dragon Bridge, Karthwasten, Shor's Stone,
  Kynesgrove, Darkwater Crossing, Stonehills, Skaal Village).
* **Village**: working settlements such as mills, mines, Orc strongholds and roadside inns.
* **Hamlet**: farms and estates.

Skycraft's `places` types map onto these tiers: capital to capital or city, town to town, village to village, and
farm to hamlet.

**Density per region.** Skyrim's holds have 2.0 settlements per km² at the median, and 0.43 in the thinnest hold.
Each hold has exactly one seat (a capital or a city). Towns run at 0.48 per km², villages at 0.48 and hamlets at
0.57. The farms inside town limits (Rorikstead's, Riverwood's) are not counted separately. Most provinces already
meet these counts (`PARITY.md` SET raw). **The gap is depth, not count.**

**Measured per settlement: min / median / max.** In the table:

* **Interiors** are the enterable buildings.
* **Named buildings** are named locations such as houses, shops, inns, temples and halls.
* **Roles** are the distinct classes and jobs among the named residents.
* **Quests** are the journal quests attributed to the settlement. They are a lower bound.

| Tier (n) | Named NPCs | Interiors | Named buildings | Merchants | Trainers | Inns | Roles | Quests |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Capital** (5) | 63 / **73** / 91 | 24 / **27** / 33 | 18 / **23** / 31 | 14 / **15** / 22 | 4 / **5** / 9 | 1 / **1** / 2 | 41 / **45** / 54 | 12 / **19** / 48 |
| **City** (5) | 13 / **20** / 35 | 7 / **10** / 14 | 5 / **11** / 12 | 3 / **4** / 5 | 0 / **2** / 5 | 1 / **1** / 1 | 15 / **22** / 23 | 3 / **3** / 5 |
| **Town** (10) | 4 / **9** / 15 | 2 / **4.5** / 8 | 2 / **3.5** / 8 | 0 / **1** / 4 | 0 / **0** / 5 | 0 / **0.5** / 1 | 3 / **7.5** / 17 | 0 / **2** / 10 |
| **Village** (18) | 0 / **3** / 12 | 1 / **2** / 4 | 0 / **1** / 4 | 0 / **0** / 3 | 0 / **0.5** / 4 | 0 / **0** / 1 | 0 / **2** / 13 | 0 / **0.5** / 13 |
| **Hamlet** (13) | 1 / **2** / 5 | 1 / **1** / 4 | 0 / **0** / 1 | 0 / **0** / 1 | 0 | 0 | 1 / **1** / 3 | 0 / **0** / 2 |

**The Skycraft floor per tier.** The target is the bold median and the absolute floor is the minimum. Skycraft's
larger footprint means more buildings than Skyrim, but never fewer enterable, named or staffed ones.

| Tier | Named NPCs | Enterable interiors | Named buildings | Distinct roles | Services required (Skyrim-measured) | Quests (authored + radiant offers) | Scenes | Other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Capital** | >= 73 (abs. 63) | >= 27 (24) | >= 23 (18) | >= 45 (41) | 15 merchants: 2-4 smiths, 2-3 apothecaries, street vendors, specialists (jeweller, tailor, fletcher). 1-2 inns with rooms. 5 trainers (abs. 4) in different skills. Court: ruler, steward, court mage, 2-3 housecarls or a captain. Temple and priest, a hall of the dead, a bard, a fence, stables with a carriage, a jail | >= 19 (abs. 12) attributed, plus faction lines | 8-12 | Unique plan (100%); palace weenie; 3+ districts with a class gradient (L7: noble quarter to slum); a guild hall; festival host |
| **City** (minor seat) | >= 20 (13) | >= 10 (7) | >= 11 (5) | >= 22 (15) | 4 merchants (3), 1 inn, 0-2 smiths, an apothecary, 2 trainers, a court (ruler, steward, housecarl), a bard, and the industry's workforce (Skyrim: 7 miners in Dawnstar, 2-3 woodcutters in Falkreath and Morthal) | >= 3 attributed + town problems (target 6) | 6 | Unique plan; a landmark seen from the roads; its industry explains why it exists (L5, L6) |
| **Town** | >= 9 (4) | >= 4 (2) | >= 4 (2) | >= 8 (3) | 1 merchant (target 2 where on a road); an inn with rooms in half of them (the road ones); a smith in 1 of 5; 2-5 workers of the industry (farmers, woodcutters, miners) | >= 2 attributed (Riverwood-class leader: 7-10) | 3 | Own sub-kit or plan; industry with 3+ jobs; a local problem |
| **Village** | >= 3 (abs. 1, except a ruin) | >= 2 (1) | >= 1 | >= 2 | As its kind: a mill has its miller, a mine its foreman and miners, a stronghold its chief, smith and wise woman, a roadside inn its keeper and rooms | >= 1 favour or radiant (Orc strongholds and wizard towers carry chains) | 2 | The work site is the reason it exists (L5) |
| **Hamlet** | >= 2 (1) | >= 1 | 0-1 | >= 1 | As its kind (a farm sells produce) | 1 favour or radiant | 1 | Fields or pens as work anchors |

The `parity.py` floors these must clear (80% of the measured reference, `PARITY.md` 5): `s_npcs` >= 37.5 named NPCs
per named town, counted over the region's named towns as in Skyrim. `s_bldgs` >= 9.4 named buildings per named town.
`q_per_town` >= 16.5. `n_lines`, `n_scenes` and `s_bespoke` are as listed in `PARITY.md` 5.

**NPC roles by tier (minimum set; the counts above set how many).**

* **Capital:**
  * Ruler, steward, court mage, housecarls and a guard captain.
  * Priests and a keeper of the dead.
  * Innkeepers, smiths, general merchants, apothecaries, street vendors and 2-4 specialty merchants.
  * Trainers in at least 5 skills, a bard, guild leaders, a fence, a stablemaster and a carriage driver.
  * A courier, beggars, children, labourers and household servants.
  * At least 3 people with a problem and 1 with a secret, spread across the class gradient (L7).
* **City:** a court of 3, a priest, an innkeeper, a smith, a merchant, an apothecary, 2 trainers, a bard and the
  industry's workers. Also 2 citizens with a problem and 1 with a secret.
* **Town:** a leader or reeve, an innkeeper (on a road), a merchant, a smith or shrine keeper where the town's role
  needs one, the industry's workers, 2 citizens with a problem and 1 with a secret.
* **Village:** its trade's people (miller, foreman, chief, keeper), 2 workers and 1 with a problem.
* **Hamlet:** an owner and 1-2 hands.
* **Missing engine roles** (FIRST-PLAYTEST limits): a true trainer role (today training goes by profession) and
  daily routines that move between anchors. Both are needed for "lived-in" at the capital and city tiers.

### 4.5 Dungeons by class

| Class | Per region | Length (on foot) | Must have | Maps to | Status |
| --- | --- | --- | --- | --- | --- |
| **Marquee** (hand-authored) | >= 1 | 40-90 min | A mechanism no other dungeon has, a named boss, a lectern note, its lore-web place, a shortcut home, a unique | `MarqueePlans`, `Special`, PA13 | PARTIAL (0 outside Skyrim) |
| **Major** (named lore ruin, tomb, fort, elder-race ruin) | 2.4/km² (abs. 1.9; Skyrim's old-culture ruins, forts and elder-race ruins together) | 15-40 min | A loop with a shortcut, a boss, a hook, a note, >= 1 set piece, a culture kit, a quest or unique payoff in >= 30% | Dungeon grammar, PA11, PA12, PA3 | PARTIAL (identity 2%) |
| **Short** (small cave, mine, lair, den) | 3.0/km² (abs. 2.0; Skyrim's caves and mines together) | 5-12 min | A boss or leader, a hook, a note, a reward | Class tag (MS7) | PARTIAL |
| **Minor** (camp, shack, wreck, tower) | 3.9/km² (abs. 2.6; Skyrim's shacks, camps, towers, wrecks and military camps together) | 2-8 min | A reason (who lives there, from L9 or L10), loot, a hook | Structures, PA3 | PARTIAL |
| **Unmarked** (secret) | >= 0.57/km² | any | Found only by exploring, a note or a rumour; a better-than-average reward | `hidden: true` | MISSING |

All dungeons together: >= 8.9/km² (absolute floor 6.7), Skyrim's measured density. Inhabitant themes: >= 10 distinct
per region (absolute 8), at least one found mainly in this region. Variety rule per region: >= 5 carved palettes
(`d_caverns`), >= 8 inhabitant rosters (`d_rosters`), and no two palettes sharing a floor and wall pair (PA11).
**Why each dungeon is inhabited, and by whom, comes from the sheet** (L10, with its cause in L1, L4 or L9).

### 4.6 Landmarks (S / M / L / XL)

| Tier | Meaning (`landmarks/Tier.java`) | Per km² today | Target | Rule |
| --- | --- | --- | --- | --- |
| **S** | Tiny vignette: a grave, a cairn, a cold camp | ~17 | Keep | Every kind has a story clue; graves carry names |
| **M** | A scene with a story, often with someone in it | ~9 | Keep | Feeds G3 |
| **L** | A natural formation: an arch, a sinkhole, hoodoos | ~4.5 | Keep; each region has >= 3 L kinds of its own | `l_kinds` >= 10 |
| **XL** | Visible from afar: a colossal tree, a spire stack, a crater lake | ~0.075 (one per 13 km²) | >= 1 per region plus 1 per 3 km², placed for sightlines from roads | P-B2, F-WEENIE |

Skyrim's marked landmarks run at 0.86 per km² (vistas, passes, clearings, lighthouses) on top of its unmarked
detail. The M and L tiers above already exceed that in count. What they need is a sheet cause for each one.

### 4.7 Scenes and encounters

* **Planned scenes** (P6, EXISTS): 5-15 per km². They include:
  * waysides at crossroads, fords, bridge ends and bends;
  * settlement edges (cemeteries, fishing shores);
  * farm yards, battle sites, shrine sites and dungeon mouths;
  * lore-web clues.

  Each scene tells a story that has a cause in the sheet.
* **Scripted world encounters** (`WildScenes`, PARTIAL; MS14): >= 8 kinds usable per region, >= 3 unique to the
  province. One roll per player every 15-25 minutes, daylight-biased.
* **Wonder moments** (MS14 data): >= 3 per region (an aurora night, a migration at dawn, a sky event at a shrine, a
  storm over the sea cliffs, a lantern festival seen from the road).

### 4.8 Quests per region (Skyrim-measured)

Skyrim has 371 journal quests in its nine holds: 14.0 per Skycraft km² over the whole province. A median hold carries
33 of them itself (9.7 per km², 8 in the thinnest hold) and shares the rest with the province. Every region needs
the following:

| Type | Floor per region (median per hold, abs. min) | Format | Status |
| --- | --- | --- | --- |
| All quests available in the region | 14.0/km² x km² (abs. 2.6/km² and >= 8) | All formats below | PARTIAL |
| Hold conflict chain (local politics) | >= 1 chain of 3+ quests | Quest chain with consequences | PARTIAL |
| Side quests (authored, with consequence) | 3 (abs. 0, but every seat has one) | `quests/town_<province>_*.json` | PARTIAL |
| Daedric or great-power quests (region's faiths, L8) | 2 (abs. 0); >= 1 per province per 3 regions | Authored | PARTIAL |
| Miscellaneous favours and errands from named residents | 3 (abs. 2) | Favour quests per settlement | PARTIAL |
| Faction quests (where the region hosts a hub) | 6 (abs. 1) | `quest/faction` | PARTIAL |
| Radiant offers (jarl bounties, guild jobs, delivery) aimed at real places | 4 (abs. 1) | `radiant_templates.json`; givers per settlement | PARTIAL (33 templates) |
| Main-story chapters passing through | 0-2 (the province story crosses most regions) | Province story | PARTIAL |
| Hidden "find" quests | >= 1, plus 0.5/km² | `start: find` | EXISTS |
| Lore-web arc | >= 1 (two parts) | `loreweb_<province>.json` | EXISTS (extend) |
| Radiant templates usable in the culture | >= 40 in total, >= 6 culture-specific | `radiant_templates.json` (PA22) | PARTIAL (33) |
| Jarl-style bounties (named target in a named place) | >= 2 | Existing bounty quests | EXISTS (Skyrim); extend |
| Notice-board offers (town and larger) | 3-6 live offers | MS12 | MISSING |

### 4.9 Economy, crafting and fast travel

* **Stations per tier:** a smithy, tanning rack and grindstone in every town and larger settlement; an enchanter
  and alchemy lab in every city and larger; a cooking pot at every inn (`Zoning.ensureTrades` guarantees inn, store
  and smith, plus an apothecary from town up).
* **Merchants and trainers per region** at the measured density (4.3: 5.0 and 3.55 per km²), placed by tier (4.4).
* **Regional industry** per settlement with its product in local shops (P-G2, P-H1), caused by L1 and L5.
* **Fast travel** (MS11): every settlement node is discovered on arrival; wilderness nodes (wayshrines, standing
  stones, Velothi shrines, Hist stones, Yokudan way-tents) cost nothing to use and refresh a blessing. Max distance
  from any land point to a node is <= 800 blocks (90th percentile <= 500).

### 4.10 Mapping to existing systems (summary)

| Template part | Existing system | Doc |
| --- | --- | --- |
| World model sheets and coherence | New: `tamriel/world/<province>.json`, `worldsheet_lint.py` (MS22) | This document, first principle |
| Micro-biomes | `TamrielBiomeSource` contexts, `Palettes.bias`, `tools/worldgen/ecotones.py` and `biomes.py`; new MS21 layer | `REGION-AUDIT.md`, MS21 |
| Skyrim baseline numbers | `.reference/skyrim_ref` (local, gitignored), `SKYRIM_MEASURED` in `parity.py` | 4.1, `PARITY.md` 5 |
| Marquee places and identity | `tools/marquee/marquee.json`, city plans, PA13 and PA17 | `MARQUEE.md`, `MARQUEE-PLAN.md` |
| Unique towns | `tamriel/settlement/grammar` (Zoning, Fabric), `cityplans/*.json`, styles | `SKYRIM-SWEEP.md` |
| Dungeon identity | Dungeon grammar, `dungeon_identity_gen.py` and lint, PA3, PA11, PA12 | `DUNGEON-AUDIT.md`, `PARITY.md` |
| Landmarks S-XL | `com.skycraft.landmarks`, `landmarks/kinds.json` | `EXPLORATION.md` |
| Scenes | `worldplan/scene`, `SceneRealiser`, `scatter/rules.json` | `WORLDGEN-PIPELINE.md` 9 |
| Lore web | `tools/marquee/loreweb*`, `lore/places/loreweb.json` | `LORE-WEB.md` |
| Quests | `quests/*.json`, `quest/story`, `RadiantTemplates`, `quest_lang.js` | `QUESTS.md` |
| Ecology and pacing | `region_spawns`, `WildScenes`, `SpawnTables.Where` | `ECOLOGY.md` |
| Collectibles today | Treasure maps, caches, uniques, skill books, word walls, standing stones | `EXPLORATION.md`, `FEATURES.md` |
| Roads and signs | Road plan bake, scene plan crossroads signs | `WORLDGEN-PIPELINE.md` |
| Measurement | `parity.py`, `density_audit.py`, `placement_lint.py`, `reach_lint.py`, `ecology_report.py`, `town_checks.py`, `population_lint.py`, `dungeon_audit.py`, `novelty_lint.py` | `PARITY.md` |

### 4.11 Missing systems (short specs)

Each spec gives what it is, its data, where it lives, how it is placed and how it is checked. All placement is baked
or hashed from stable ids (seed-independent, like the scene plan). Everything is server-authoritative and works in
multiplayer. Specs tagged **(optional layer)** belong to 4.12: they are built only on top of a region that already
meets the Skyrim floor, and never count toward it. MS21 and MS22 are part of the floor.

* **MS1 Region story framework.** The schema is part of the floor, because the hold conflict chain (4.3) uses it. An
  ESO-style 5-8 quest zone arc on top of that is **(optional layer)**. A `story` block per region
  (`quests/story/<region>.json`): `hook` (start NPC in the first town on the main road, plus a rumour and a courier
  letter on first entry to the region), 5-8 ordered quests with `requires`, a `consequence` per quest (NPC state,
  building swap, dialogue key, scene on or off) and a finale at the region's marquee. The journal gets a "Region" tab
  heading. Validator: `quest_lang.js` plus a new `tools/qa/story_lint.py` (counts, places registered, the hook within
  800 road blocks of a region entry, consequences declared).
* **MS2 Region Guide and achievements.** **(optional layer)** A journal page per region listing story progress,
  delves, open dungeon, world bosses, shards, lorebooks, set station, surveys, treasure maps, fast-travel nodes and
  world events, as found / total. Completion awards a title and a small trinket. Achievements are data
  (`achievements.json`: id, region, condition = count of a tracked kind). Player data lives in `SkyData` module
  `guide`. It reads the same baked lists as MS3-MS10, so the guide is never out of sync.
* **MS3 Star-shards.** **(optional layer)** A glowing meteoric shard (original block and item): three absorbed give
  one perk point. Each region has 6 + 1.5/km² (cap 16): 1/3 in delves, 1/3 on vistas reached by climbing, 1/3 behind
  small puzzles or hidden paths. A cryptic hint line shows in the guide. Baked list `shards.bin` from places plus a
  vista finder (high points with line of sight). Per player, never respawning. Check: count per region; every shard
  reachable (reach from road ≤ 300 blocks, or inside a delve).
* **MS4 World events** (the dolmen analogue). **(optional layer)** A region-flavoured event at 3-5 fixed sites per
  region on the server clock (every 20-40 min per site, at most one active per region). It is announced on the map,
  compass and chat within 600 blocks, scales with the players present, gives per-player rewards and has a cooldown.
  Kinds:
  * Cyrodiil: Oblivion gates. These exist, but are rare and permanent; make them timed and repeatable.
  * Skyrim: a dragon descending on a mound. This exists as a random attack; anchor it to mounds and watchtowers.
  * Morrowind: ash-vent eruptions with fire daedra.
  * Black Marsh: Hist-sap blooms with feral spawn.
  * Summerset: Maormer landing parties.
  * Hammerfell: sand-serpent risings and Ra Gada war-camps.
  * Elsweyr: moon-sugar raids at a caravan stop.
  * Valenwood: a Green Pact wild hunt.
  * High Rock: knightly-order tourneys and witch-coven circles.

  Data: `events/<province>.json` (sites from places, waves, boss, reward table). Java: one `WorldEvents` scheduler.
* **MS5 World bosses.** **(optional layer)** A named outdoor foe at an L or XL landmark, with telegraphed attacks
  (wind-ups), party scaling, per-player loot and a respawn of 10-20 minutes. 1 + 0.25/km² per region (cap 4). Reuses
  the existing bosses block (named creature as a character) plus a `BossArena` helper. Check: count, and each one on
  a road or signposted trail.
* **MS6 Open dungeons.** **(optional layer)** A larger dungeon (marquee scale) whose population respawns, with 3-5
  named bosses and a group event room. It is shared: every player present sees the same state, while loot stays per
  player (Lootr). Data flag `open: true` on a dungeon structure. Respawn timer per room cluster.
* **MS7 Dungeon class tag.** Data only: `class: short | major | marquee | minor | unmarked` (plus `open` in the
  optional layer) on dungeon places. A short dungeon gets a boss slot, and a star-shard slot only if the region
  adopts MS3. `dungeon_stats.py` reports room counts per class against the length bands (P-C5).
* **MS8 Crafting sets and set stations.** **(optional layer)** Each region has an attuned station at a landmark that
  crafts that region's set (armour, weapon, jewellery slots) with 2, 3 and 4-piece bonuses. Using it needs N "known
  traits", learned by breaking down or researching gear (a light research timer). Each province also has a regional
  motif (style) the smiths can learn from motif books found in its dungeons. Data: `crafting/sets/<region>.json`.
  This raises `i_sets` (PA21).
* **MS9 Surveys.** **(optional layer)** A survey report (sold by merchants and guilds, and found in camps) marks a
  rich cluster of 4-6 gathering nodes (ore veins, herb patches, timber stand, fish shoal, game trail) somewhere in
  its region. Clusters are baked from biome and map context. One survey per gathering skill per region. The rich
  nodes regrow on the existing vein timers.
* **MS10 Lorebook collections.** The books themselves are floor content (4.3 readables); the set reward is
  **(optional layer)**. Each region has a set of 6-12 original region books (history, local legends, bestiary, a
  diary) placed in its towns, dungeons and scenes. Reading the whole set grants a small reward (skill XP in a
  lore-tied skill, a map reveal, a recipe). Data: `lore/books/<region>.json`. The guide tracks it. This counts toward
  `r_notes`.
* **MS11 Province travel networks and wilderness nodes.** Per province:
  * Skyrim: carriages (exist).
  * Morrowind: striders (exist), boats and guild guides (teleport between guild halls).
  * Cyrodiil: wayshrines (places exist) as free-travel nodes, plus Imperial carriages.
  * Hammerfell and Summerset: ships between ports (sailing exists).
  * Black Marsh: river barges.
  * Valenwood: walking-tree or Graht-oak lifts.
  * Elsweyr: caravans.

  Wilderness nodes come from places (shrines, stones) plus a hashed fill so the 800-block gap rule holds. Discovery
  marks the node on the map. Using a node refreshes a minor blessing.
* **MS12 Notice boards.** A board in every town and larger settlement offers 3-6 live jobs: radiant templates aimed
  at real, uncleared dungeons 300-1,500 blocks away, with reward scaled by job size. Jobs are shared by the whole
  party, refresh every two in-game days and are visible to every player at that board. This turns the 98% of dungeons
  without a quest into destinations (`d_identity` "quest" leg).
* **MS13 Signposting layer.**
  * Undiscovered places show on the compass within 150 blocks, Skyrim-style.
  * Quest-giver markers appear in towns, and the region-entry banner gets a "rumours here" line.
  * Hearing a rumour reveals the place on the map (this exists).
  * Light beacons appear at night on towers and camps, and smoke rises from inhabited camps.
  * The road signs (exist) name the next settlement, the next inn and the nearest dungeon.
  * The journal links each entry to its place on the map.
* **MS14 World encounter library.** Grow `scenes.json` from 6 kinds to >= 40, region-keyed. Examples:
  * a courier with a letter for you;
  * a prisoner escort;
  * pilgrims who ask for an escort;
  * a duel;
  * a broken cart;
  * a lost child;
  * a skooma deal (Elsweyr);
  * an Ashlander herd crossing (Morrowind);
  * a Hist procession (Black Marsh);
  * a knightly challenge on a bridge (High Rock).

  Some are one-shot story encounters that start a side quest. Wonder moments use the same table with a `wonder`
  tag.
* **MS15 Party scaling for open-world group content.** One helper scales health, damage and rewards by players
  present within 48 blocks. MS4, MS5 and MS6 use it.
* **MS16 Festival calendar.** One festival per province (and per Skyrim hold) on Tamriel's calendar. On the day, town
  decorations go up (a scene-plan overlay), a market adds stalls, festival quests appear and some towns run events.
  Data: `calendar/festivals.json`.
* **MS17 Level policy.** `Leveling` keeps region bands. Story quests read the player's level clamped to the band plus
  or minus 8. Delves follow the band. World bosses scale by party (MS15).
* **MS18 Point-of-interest micro-objectives.** M-tier landmarks and minor sites with notes may carry one small
  optional objective (find the cache the note mentions, lay the ghost to rest, free the captive, return the
  heirloom), shown as a small discovery-banner line. Data from PA3 generator output, as a `micro` field.
* **MS19 Region arrival moment.** The first entry to a region shows its banner (exists) plus a music sting, a short
  narrator line in the region's voice, and a courier or rumour pointing at the MS1 hook.
* **MS20 No-verbatim corpus check.** `tools/qa/verbatim_check.py`: on the owner's machine only, it compares our text
  (lang, lore, quests) against a local corpus in `.reference/` (book and dialogue text he extracted from his own
  games) by 8-word shingles. It reports any hit and exits 1. The corpus is never committed. This turns the legal line
  into a gate.

* **MS21 Micro-biome layer.** Skyrim is not one big biome per hold. Hot springs steam inside Eastmarch's tundra,
  a birch glade sits in a pine forest, salt marsh lies at a river mouth and rock fields cover the high moors, and each
  one matches the land, the weather and the people. Each Skycraft region becomes a mosaic of micro-biomes driven by
  its geography and listed in its sheet (L4, citing L1-L3).
  * **Drivers** (map variables, all baked or derivable from the existing rasters):
    * elevation bands and the snowline;
    * slope and aspect (sunny or shaded side);
    * wind exposure, and a **rain-shadow raster** derived from the ridge field and the prevailing wind (L2);
    * distance to rivers, lakes and the coast, giving riparian strips, fens, salt marsh, sea cliffs and beaches;
    * valley floor against ridge (the ridge and coast distance fields already exist in `TamrielMap`);
    * a **heat raster** for hot springs and volcanic ground (Eastmarch, Vvardenfell, the Velothi foothills);
    * soil and rock from L1, giving tundra rock fields, glacial moraines and karst;
    * disturbance masks: burnt or blighted patches, forest glades and clearings, and farmland rings round
      settlements (from `places` and L5).
  * **Effects** of each micro-biome:
    * flora and ground cover, through the palette's sub-biome or a surface-rule overlay;
    * the odds of fog, storm and snow (feeding the weather table, G10);
    * ambience;
    * the creature table, through `region_spawns` keys;
    * what people do there (farms in valleys, mines in crags, fishers on coasts, herders on the moors), which feeds
      the settlement grammar and minor sites.
  * **How Skycraft does it.**
    * `TamrielBiomeSource` already picks from a region palette by context (OCEAN, BEACH, RIVER, LAKE, MARSH,
      LOWLAND, UPLAND, PEAK, SUMMIT) with a climate and geography bias. `ecotones.py` already grades palettes
      toward their neighbours.
    * MS21 adds a finer selection inside each context. A per-region `micro` table (in `palettes.json`, written by
      `tools/worldgen/biomes.py`) maps rules to sub-biomes or overlay keys, for example
      `UPLAND & aspect=north & rainshadow<0.3 -> wet_pine_glade` or `LOWLAND & heat>0.6 -> hot_spring_moor`.
    * Two new baked rasters at 32-block cells (rain shadow, heat) sit next to `features.png`. Aspect and slope are
      computed from the macro height already sampled per column.
    * The cost is a few table lookups per biome query, with no new noise.
  * **Checks** (in `region_audit.py`):
    * distinct micro-biomes per region >= 6 and per km² >= 2;
    * transitions per km of road >= 3 (the walk keeps changing);
    * no micro-biome without a sheet cause;
    * no micro-biome rule that fires on less than 0.5% or more than 60% of the region's land.
* **MS22 World model sheets and coherence lint.**
  * **Data:** `tamriel/world/<province>.json` holds a province block plus one block per region. Each block has the
    world truth paragraph and the eleven layers (first principle). Each field is `{id, text, cites: [field ids]}`.
  * **Consumers:** city plans (district and class gradient from L6-L7), the population grammar (jobs from L5,
    beliefs from L8), quests (conflicts from L9-L10), scenes, dungeon identity (inhabitants from L10), region spawns
    (L4), weather (L2), music cues and dialogue.
  * **Content links:** every content record gains a `why: [field ids]` list.
  * **Lint:** `tools/qa/worldsheet_lint.py` checks that:
    * every layer is present;
    * every L2-L11 field cites a field above it;
    * every cite resolves;
    * every content record's `why` resolves (100% for new content, a back-fill counter for legacy content);
    * the forbidden pairs listed in the sheet are absent (a crop on a micro-biome whose climate forbids it, an
      industry with no resource, a shrine to a faith the region does not hold).
  * **Format as built (`tools/world/SCHEMA.md`).** `tools/world/sheet_from_md.py <provinces/x.md> --out <json>` parses
    section 1 of a province markdown plan into `mod/src/main/resources/skycraft/tamriel/world/<province>.json` with
    `province`, `truth`, `layers` (`L1`..`L11` of `{id, text, cites}`), `regions` (`<region>` -> `{truth, layers, jobs,
    dungeon_themes}`), and `forbidden` (`{kind, a, b, reason}`). Field IDs are global as `<scope>.L<n>.<id>` (aliases
    `sk`/`skyrim`, `reach`/`the_reach`, `rift`/`the_rift`, `pale`/`the_pale` normalized). `com.skycraft.tamriel.world.WorldSheets`
    loads all sheets asynchronously at server start and exposes `/skycraft-world sheet <scope> [layer]`;
    `SettlementPopulation` (`SkycraftWhy` NBT tag from `jobs`) and `dungeons/identity/skyrim.json` (`why` from `L10`
    `dungeon_themes`) are the first two consumers.

  The lint runs in `qa.ps1` and in every province phase report.

### 4.12 Optional additions (ESO-inspired idea pool, on top of the Skyrim floor)

These ideas come from ESO's zone design. They are **never a replacement for any part of the floor** (4.3 to 4.9).
A region may adopt one only after it meets the floor, and the idea must be expressed through the region's world
model sheet. Star-shards, for example, need an L10 or L11 reason to fall there, and a world event needs an L9 or L10
cause. An adopted idea is then held to its own count below. None of them is required for a region to be done.

| Idea | Count if adopted | Spec | Status |
| --- | --- | --- | --- |
| ESO-style zone storyline (a 5-8 quest arc with a finale at the marquee) | 5-8 (large: two arcs) | MS1 | MISSING |
| Star-shards (3 give a perk point) | 6 + 1.5/km² (cap 16) | MS3 | MISSING |
| World events (dolmen analogue: dragon mounds, Oblivion gates, ash vents...) | 1 kind, 3-5 sites | MS4 | PARTIAL (precursors) |
| World bosses with party scaling | 1 + 0.25/km² (cap 4) | MS5, MS15 | MISSING |
| Open (public) dungeon with respawn and a group event | 1 | MS6 | MISSING |
| Crafting set station and regional motif | 1 station, 1 motif per province | MS8 | MISSING |
| Surveys (rich gathering clusters) | 1 per gathering skill | MS9 | MISSING |
| Lorebook set reward | 1 set of 6-12 | MS10 | PARTIAL |
| Region Guide and achievements | 1 guide, 4-6 achievements | MS2 | MISSING |
| Point-of-interest micro-objectives | >= 1/km² | MS18 | MISSING |


---

## 5. Gameplay flow

### 5.1 The moment-to-moment loop (what the world must supply)

| Time scale | The player should... | Supplied by | Check |
| --- | --- | --- | --- |
| Every 30-60 s | See or hear something worth a look: a scene, landmark, smoke, a herd, a sign | POI density, scenes, ambient life | `density_audit` (POI within 250 blocks), F-VISTA |
| Every 2-3 min | Choose: detour or stay on the road | Rule of three (P-B1), signposts, trails | F-DETOUR |
| Every 5-10 min | Get a small reward or story: a note, cache, unmarked site, encounter, view (a shard if adopted) | Minor sites, secrets (G9), MS14, scenes | F-REWARD-RATE |
| Every 20-40 min | Finish something: a short dungeon, a side quest, a favour, a town problem (a world event if adopted) | Dungeons (MS7), quests | F-QUEST-RADIUS, F-LOOP |
| Every session (1-3 h) | Advance the hold conflict or a faction line, change a town, gain a level and perks, find a marquee place | MS1 schema, factions, marquee | F-STORY, G6 |

### 5.2 The session loop: arriving in a new region

1. **Approach.** The road crosses the border. The region banner, music sting and narrator line play (MS19). Within
   the first 600 blocks of road the player passes a wayside, a sign naming the first town and at least one detour
   (P-B1).
2. **First town.** It sits on the main road, within 800 road blocks of the region entry (F-HOOK). It has an inn
   (rest, rumours, bard), a notice board (MS12), a shop with regional goods and people with problems at the Skyrim
   tier count (4.4). Overheard talk points at the hold conflict.
3. **Hold conflict hook.** The first quest of the hold's conflict sends the player to 2-3 places in the region
   within 600 blocks, passing other content on the way.
4. **Discovery.** Between objectives the player meets dungeons, landmarks, unmarked secrets, scenes and changing
   micro-biomes, plus any adopted optional content (shards, world events, world bosses). The compass hints at
   undiscovered places; rumours reveal more.
5. **Dungeon.** A short dungeon takes 5-12 minutes; a major dungeon has a loop and a shortcut, a boss, a note
   linking to the lore web and a reward that fits the region.
6. **Return.** Back in town the player sells, crafts, trains, turns in quests and sees a consequence (the town
   changed, the guards mention it). Fast travel to discovered nodes makes the return short.
7. **Onward.** The next quest, a lore-web clue, a faction errand or a carriage route points to the next town or
   region.

### 5.3 Checks by existing parity.py category

| Flow step | parity.py categories and metrics |
| --- | --- |
| Approach and first town | SET (`s_towns`, `s_bespoke`, `s_bldgs`), STY (`y_looks`), MUS (`m_town`) |
| Town life | NPC (`n_lines`, `n_scenes`, `n_roles`, `n_say`), SET (`s_npcs`) |
| Hooks and quests | QST (`q_total`, `q_per_town`, `q_kinds`, `q_placed`) |
| Discovery | LND (`l_density`, `l_kinds`, `l_unique`), LOR (`r_places`, `r_notes`, `r_flavor`) |
| Danger and wildlife | CRE (`c_hostile`, `c_wild`, `c_cave`, `c_total`, `c_bosses`) |
| Dungeons | DUN (`d_identity`, `d_marquee`, `d_rooms`, `d_caverns`, `d_rosters`) |
| Rewards | ITM (`i_sets`, `i_unique`, `i_ingr`) |
| Atmosphere | FLO, MUS (`m_explore`, `m_amb`) |

### 5.4 New flow metrics

These are proposed as a new parity.py category **FLW** (or a sibling `tools/qa/flow_audit.py` that writes into
`parity.json`). They are computed offline from baked data (road plan, scene plan, places, macro heights) plus
quest, event and collectible data. Floors are given per region.

| Id | Metric | How it is computed | Floor |
| --- | --- | --- | --- |
| F-HOOK | Road distance from each region entry point (where a road crosses into the region) to the town holding the region story hook | Road plan graph shortest path | <= 800 blocks |
| F-STORY | Hold conflict chain present (floor); zone arc count and finale if the optional arc is adopted | MS1 data | >= 1 chain of 3+; arc 5-8, finale = marquee (optional) |
| F-VISTA | Distinct POIs with line of sight from sampled vantage points (local height maxima within 150 blocks of a road) | Baked macro heights, a ray test every 4 blocks | >= 3 within 250, >= 5 within 1,000 |
| F-WEENIE | Share of land sample points that see an XL silhouette (XL landmark, marquee tower, peak) within 1,500 blocks | Same ray test, from a 1.7-block eye height | >= 70% |
| F-DETOUR | Secondary POIs within 120 blocks of a road route, per 600 blocks of road | Road plan plus places, minor-site cells and scenes | >= 2 |
| F-SIGN | Share of road junctions with a signpost; share of settlement exits with a sign | Scene plan crossroads vs road-graph junctions | >= 95% |
| F-TRAVEL | 90th percentile and maximum distance from land to a fast-travel node; travel modes per province | Places plus MS11 nodes | p90 <= 500, max <= 800; >= 2 modes |
| F-ENC | Scripted encounter kinds usable in the region; province-specific share | `scenes.json` filters | >= 8; >= 3 specific |
| F-WONDER | Wonder moments per region | Scene table `wonder` tag | >= 3 |
| F-QUIET | Hostile chance on a 300-block road walk, day and night | `ecology_report.py` road300 | <= 5% day, <= 20% night |
| F-LOOP | Share of major and marquee dungeons with a return shortcut | `dungeon_audit.py` reach report | >= 90% / 100% |
| F-QUEST-RADIUS | Median distance from side-quest giver to objective; share of a region's quests whose objectives stay in the region | Quest JSON and places | 150-900 blocks; >= 70% |
| F-STARTS | Distinct quest start kinds used in the region (npc, find, auto, rumour, note, board) | Quest JSON | >= 4 |
| F-REWARD-RATE | Expected rewards per 10 minutes of walking (caches, shards, notes, uniques, encounters along a road-and-detour walk) | Densities x walk model at 4.3 blocks/s | >= 1 |
| F-COLLECT | Per-region collectibles against the Skyrim floor (skill books, readables, spell tomes, placed uniques, containers, treasure maps, power sites; 4.3), plus any adopted optional collectibles (4.12) | Places, loot and MS2-MS10 data | 4.3 medians (abs. 4.3 minimums); 4.12 counts if adopted |
| F-GROUP | World bosses and open dungeon per region (optional layer only) | MS5, MS6 data | 1-4 and 1 if adopted |
| F-EVENT | World-event kind and site count per region (optional layer only) | MS4 data | 1 kind, 3-5 sites if adopted |
| F-GUIDE | Region Guide entries resolve (every tracked id exists and is reachable) | MS2 data plus `reach_lint` | 100% |
| F-LEVEL | Level band width per region; story quest level vs band | `Leveling` data, MS17 | Band <= 20; story within band +/- 8 |
| F-HUBS / F-COURT / F-BOARD | Faction hubs per province; capitals and cities with a court of 3+; towns and larger with a notice board | Faction, NPC and settlement data | >= 2 / 100% / 100% |
| F-ORPHAN | Named dungeons, forts and landmarks that nothing points to (no quest, board job, lore-web link, rumour or trail) | Union of references | 0 for major and marquee; <= 10% overall |
| F-POI | Micro-objectives per km² (optional layer only) | MS18 data | >= 1 if adopted |
| F-REACT | Deed-keyed reactive lines per region (guards, innkeepers, courts) and lines changed by finished side chains | Dialogue keys | >= 12; every finished chain changes >= 1 |
| F-MICRO | Distinct micro-biomes per region and per km²; transitions per km of road | MS21 data, biome census along the road plan | >= 6 and >= 2/km²; >= 3 per km |
| F-WHY | Share of content records whose `why` resolves to sheet fields | MS22 lint | 100% new, back-fill tracked |

### 5.5 Definition of done for a region

A region is done when all of the following hold:

1. Its world model sheet is complete and causal, and the coherence check (first principle) passes:
   `worldsheet_lint.py` reports 0, and the four questions are answered in the phase report.
2. It meets the Skyrim floor: the 4.3 densities (median target, absolute minimum never broken), the 4.4 tier counts
   for every settlement, the 4.5 dungeon mix and the 4.8 quest counts.
3. The `parity.py --assert-floor` categories pass for the region (references include the real-game values, see
   `PARITY.md` 5).
4. The FLW floors above pass. The optional-layer checks (F-GROUP, F-EVENT, and the optional parts of F-STORY and
   F-COLLECT) apply only to ideas the region adopted.
5. `novelty_lint.py --strict` and the MS20 verbatim check report 0.
6. `reach_lint.py` reports no isolated major site.
7. The modernisation checklist (1.3) is ticked in the phase report.
8. The G1-G12 checks (4.0) pass, including the blind identity test and the MS21 micro-biome counts.
9. One contact sheet per region (towns, dungeons, landmarks) passes Haiku review as unique, purposeful and belonging
   to its place.

Owner playtests then judge feel.
