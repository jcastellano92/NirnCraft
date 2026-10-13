# World design principles: a full, unique, purposeful Nirn

This is the design rulebook for the whole world: every province, region, city, town, dungeon and roadside spot. The
master plan and every province phase follow it. It says what "as full and special as Skyrim, with ESO's flow" means
in numbers Skycraft can build and measure.

* **Section 0** holds the non-negotiables. Read it even if you read nothing else.
* **Section 1** covers modernising legacy-source content (Arena, Daggerfall, old maps): what we keep, what we
  amplify and what we replace.
* **Section 2** covers the research inputs: the owner's asset-research PDF, its resource inventory and what each
  source may be used for, plus what the Daggerfall bible teaches about how a whole TES world's systems fit together.
* **Section 3** lists the open-world principles from Skyrim, ESO, Morrowind, Oblivion and Daggerfall, each one turned
  into a Skycraft rule with a measurable target.
* **Section 4** is the region content template. It says what every province and region must contain, counted by
  tier, mapped to existing systems, and lists the missing systems with short specs.
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
10. **Skyrim + ESO quality everywhere, not only in Skyrim.** Each province gets the same depth per place as Skyrim's
    holds (`PARITY.md` floors) and ESO's zone structure (section 4) on top.

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
| **Settlement life** | The town's role and economy as lore states it (port, mine town, temple seat) | The role becomes the industry: workplaces, jobs, a regional product in the shops, a problem tied to the industry (quests) | Nameless crowds and the "wander, vanish at dusk" walkers: named residents with homes, jobs, schedules, relationships, 4-8 named NPCs per village and more in larger towns (section 4.4) |
| **Dungeons** | Names, positions and the premise (an Ayleid ruin, a Direnni tower, a Yokudan tomb) | The premise becomes a hook, a boss with a name and a reason, a lore note and a link to the lore web (PA3, `LORE-WEB.md`) | Daggerfall's random-block mazes: authored room templates per culture (PA12), a loop with a return shortcut, sleepers vs awake, set pieces, one hand-authored marquee per region (PA13) |
| **Wilderness** | The province's fauna and flora as lore states them | Signature creatures per province with their own behaviour (PA14, PA15), ambient life, herds and birdsong | Empty space between dots, and random hostile spawns: planned scenes (P6), minor sites with a reason, scripted world encounters (MS14) and quiet stretches |
| **Quests** | Lore events, factions and conflicts of the era (houses, guilds, wars, cults) | Each conflict becomes a region storyline (MS1) or a faction chain with choices | Daggerfall-style generator quests as the main content: radiant work stays as filler, always with a named giver, a named place and a reason (PA22) |
| **Travel** | Historic roads, ports, ferries, silt striders, guild guides, wayshrines | Each province's travel network becomes part of its identity (MS11) | Daggerfall's fade-to-black travel across empty map pixels: roads with scenes, signposts and encounters, plus fast travel to discovered places |
| **Lore text** | Facts: who ruled, what happened, what a people believe | Rumours, legends, songs and notes in each region's voice (PA10) | Any source text: never copied, always rewritten in our own words (`novelty_lint.py` keeps our own text from repeating; MS20 checks it against a local corpus) |
| **ESO zones** | Zone names, the regional split (Glenumbra, Stormhaven, Bangkorai...), landmark positions as facts | ESO's structure per region (story, delves, open dungeon, world bosses, collectibles; section 3.2) | ESO's level-gated zones and instanced phasing: one seamless world with regional level bands (P-J3) |

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
| UESP ESO gamemap | Stitched map of all ESO zones | Zone names, borders and relative positions of towns, delves, public dungeons, world bosses, wayshrines; counts per zone | REF | Region borders and place relative positions for non-Skyrim regions (most regions are ESO zones); calibrating section 4 counts |
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
| P-C5 | **Length tiers.** Small caves take 5 to 10 minutes; great ruins take an hour | Delve (5-12 min), major (15-40 min), marquee (40-90 min) | Room-count bands per class (4.5) | `dungeon_stats.py` |

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
| P-I3 | **Collectibles that pay.** Shards give skill points, lorebooks give mage-guild progress, and set stations give gear builds, so exploring builds the character | Star-shards to perk points, lorebook collections, regional set stations (MS3, MS8, MS10) | Counts in 4.3 | F-COLLECT |
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

### 4.1 How to read the counts

* **Fixed** counts apply to every region, whatever its size: a story, a marquee, an open dungeon and a world event.
* **Per km²** counts scale with land area (`parity.py` km²). A region's count is
  `max(floor, round(density x km²))`.
* Size classes: **small** under 2 km² (Bitter Coast, Artaeum, Stros M'Kai, Tang Mo...), **medium** 2-6 km², **large**
  over 6 km² (Glenumbra 12.4, Summerset Isle 10.4, Wrothgar 10.8...). Large regions should consider splitting their
  story into two arcs.
* The foreign lands (Atmora, Akavir, Yokuda, Pyandonea, Thras) use the template at half density. They are
  expeditions, not homelands.
* Status: **EXISTS** means the system is built and only content is missing. **PARTIAL** means part of the system
  exists. **MISSING** means a new system is needed (specs in 4.11).

### 4.2 Province layer (every province)

| Item | Count | Maps to | Status |
| --- | --- | --- | --- |
| Capital with court, palace and seat-of-power landmark | 1 | `places` capital, `cityplans/`, marquee | EXISTS (plans vary) |
| Province main arc linking its regions (an alliance-story analogue) | 8-12 quests, one chapter per region | MS1 schema at province level | MISSING |
| Joinable faction hubs, in different cities | 2-4, each with 5-8 quests and ranks | `quest/faction`, `Factions` | PARTIAL (Skyrim 7; others few) |
| Exclusive faction choice where lore has one | >= 1 (Morrowind, Hammerfell, High Rock, Elsweyr) | Faction data | MISSING outside Skyrim's civil war |
| Continent lore-web thread passing through | >= 1 | `LORE-WEB.md` threads | EXISTS |
| Travel network (carriage, strider, ferry, guild guide, wayshrine) | >= 2 modes | `roads/Carriages`, `StriderPorts`, `sailing` | PARTIAL (MS11) |
| Signature creatures | >= 4 (hostile, wildlife, rare, boss variant) | PA15 | PARTIAL |
| Signature gear families, including faction sets | >= 8 (stage 1), 12 target | PA21, MS8 | PARTIAL |
| Legendary uniques | >= 6 | `LegendaryUniques` | PARTIAL |
| Music | >= 4 exploration tracks, 2 town tracks per culture | PA20 | PARTIAL |
| Festival on the calendar | >= 1 | MS16 | MISSING |
| Regional crafting style (a motif) for smithed gear | 1 | MS8 | MISSING |

### 4.3 Region layer (every region)

| Item | Fixed | Per km² | Medium example (4 km²) | Maps to | Status |
| --- | --- | --- | --- | --- | --- |
| **Region storyline** | 5-8 quests (large: two arcs, 8-12) | none | 6 | MS1; quests format (`start`, `requires`, bosses) | MISSING (framework); quests EXIST |
| Region story hook in the first town on the main road | 1 | none | 1 | MS1 + F-HOOK | MISSING |
| Lore-web arc (a mystery trail with a finale) | >= 1 (4-8 places) | none | 1 | `loreweb_src` | EXISTS for 39 arcs; extend to every region |
| **Marquee place** (hand-authored dungeon, city or XL landmark with a mechanism) | >= 1 dungeon + 1 other | none | 2 | `marquee.json`, PA13 | PARTIAL |
| **Open dungeon** (public-dungeon analogue) | 1 | none | 1 | MS6 | MISSING |
| **Delves** (short dungeons with boss and shard) | >= 4 | 1.5 (cap 8) | 6 | Tag on existing dungeons (MS7) | PARTIAL |
| **World bosses** | 1 | 0.25 (cap 4) | 2 | MS5 | MISSING (Jarl bounties and legendary hunts EXIST as quests) |
| **World event** (region flavour) | 1 kind, 3-5 sites | none | 4 sites | MS4 (Oblivion gates and dragon attacks EXIST as precursors) | PARTIAL |
| Dungeons and forts in total | none | 8-11 (Skyrim 9.9) | 40 | `places` dungeon_fixed and fort, structures | EXISTS |
| Hidden "find" quests | >= 1 | 0.5 | 2 | `start: find` | EXISTS |
| Points of interest with a micro-objective | none | >= 1 | 4 | MS18 + PA3 | MISSING |
| **Star-shards** (skyshard analogue: 3 make 1 perk point) | 6 | 1.5 (cap 16) | 12 | MS3 | MISSING |
| Lorebook collection (region books) | 1 set of 6-12 | none | 8 | MS10, lore notes | PARTIAL |
| Skill books placed in the world | >= 3 | none | 3 | Explore loot | EXISTS |
| Treasure maps leading to caches | 4 | 1 | 4-6 | `explore` treasure maps | EXISTS (point to the nearest cache; add per-region map sets) |
| Surveys (rich gathering clusters) | 1 per gathering skill (mining, woodcutting, herbs, fishing, hunting) | none | 5 | MS9 | MISSING |
| Explore uniques (dead adventurer, shrine cache...) | >= 1 | 0.27 (floor) | 1-2 | `explore/uniques_<province>.json`, PA18 | PARTIAL |
| Crafting set station (attuned site) | 1 | none | 1 | MS8 | MISSING |
| Regional products (ore, ingredient, wood, fish) sold locally | >= 3 | none | 3 | Loot tags, economy | PARTIAL |
| Fast-travel nodes (settlements, plus wilderness shrines or stones) | every settlement | +0.7 wilderness nodes | ~4 wild + settlements | MS11; places of type shrine and wayshrine | PARTIAL |
| Scripted world encounters (kinds usable here) | >= 8, >= 3 province-specific | none | 8 | `creatures/scenes.json`, `WildScenes`, MS14 | PARTIAL (6 kinds) |
| Wonder moments (sky, weather, vista, creature spectacle) | >= 3 | none | 3 | Atmosphere, fauna, MS14 | PARTIAL |
| Region voice (about, rumour, work, weather lines) | 4-6 lines | none | 6 | PA10 `region_voice.json` | PARTIAL |
| Rumours + legends + songs | 12 | none | 12 | Lore region blocks | PARTIAL |
| Planned scenes | none | 5-15 (one per ~330 blocks) | ~36 | Sceneplan (P6) | EXISTS |
| Waysides per km of road | 0.33-0.67 highway, 0.25-0.71 lane | none | by road | Sceneplan | EXISTS |
| Region Guide entry tracking all of the above | 1 | none | 1 | MS2 | MISSING |
| Achievements (region complete, all shards, all delves, story done) | 4-6 | none | 5 | MS2 | MISSING |

### 4.4 Settlements by tier

Density per region: city 1 (the region seat; the province capital replaces it in its own region); towns 0.35-0.5/km²;
villages 0.6-1.0/km²; hamlets (farms, estates, inns, mills, camps) 0.8-1.5/km². Most provinces already meet these
(`PARITY.md` SET raw: villages and farms are 2-3.7/km² outside Skyrim). The gap is depth, not count.

| Tier | Buildings | Named NPCs | Distinct roles | Named buildings | Services required | Quests (authored side) | Scenes | Other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Capital** | 100-170 | 25-45 | >= 14 | >= 8 | 2 inns, general store, 2 smiths, apothecary, temple, court (ruler, steward, court mage, captain), guild hubs, trainers (>= 6 skills), stable and carriage, fence (hidden), notice board, bank or moneylender | 15-25, plus faction lines | 8-12 | Unique plan (100%); palace landmark (weenie); 3+ districts; festival host |
| **City** | 60-150 | 15-25 | >= 10 | >= 6 | Inn, store, smith, apothecary, temple, court of 3, stable, notice board, trainers (>= 4), 1-2 specialty shops | 10-15 | 6 | Unique plan (100%); a landmark seen from the roads |
| **Town** | 25-50 | 8-12 | >= 7 | >= 3 | Inn, store, smith, shrine or priest, notice board, 1 trainer | 6-8 | 3 | Unique plan or own sub-kit; industry with 3+ jobs |
| **Village** | 8-25 | 4-6 | >= 4 | >= 2 (inn + one) | Inn or tavern (when lore allows), trader or smith | 2-4 | 2 | Industry (mill, mine, fishery, vineyard); named inn |
| **Hamlet** (farm, estate, inn, mill, camp) | 1-6 | 1-3 | 1-2 | 1 | As its kind (an inn has rooms) | 1 favour or radiant | 1 | Fields or pens as work anchors |

The `parity.py` floors these must clear: `s_npcs` >= 7.0 per named town, `n_lines` >= 17.4, `n_scenes` >= 4.8,
`q_per_town` >= 13.7 (authored + radiant offers), `s_bldgs` >= 3.1, `s_bespoke` >= 0.53.

**NPC roles by tier (minimum set):**

* **Capital:** ruler, steward, court mage, housecarl or captain of the guard, guard captain, priest(s), innkeepers,
  smiths, general merchant, apothecary, 2-4 specialty merchants, trainers, bard, guild leaders, courier, beggar,
  children, labourers. It also has 3 "people with a problem" and 1 "person with a secret".
* **City:** court of 3, priest, innkeeper, smith, merchant, apothecary, trainer, bard, guard captain, 2 citizens
  with a problem, 1 with a secret.
* **Town:** headman or reeve, innkeeper, smith, merchant, priest or shrine keeper, trainer, workers of the industry,
  2 citizens with a problem, 1 secret.
* **Village:** elder, innkeeper or trader, 2 workers, 1 with a problem.
* **Hamlet:** owner and 1-2 hands.
* **Missing engine roles** (FIRST-PLAYTEST limits): a true trainer role (today training goes by profession) and
  daily routines that move between anchors. Both are needed for "lived-in" at the capital and city tiers.

### 4.5 Dungeons by class

| Class | Per region | Length (on foot) | Must have | Maps to | Status |
| --- | --- | --- | --- | --- | --- |
| **Marquee** (hand-authored) | >= 1 | 40-90 min | A mechanism no other dungeon has, a named boss, a lectern note, its lore-web place, a shortcut home, a unique | `MarqueePlans`, `Special`, PA13 | PARTIAL (0 outside Skyrim) |
| **Open dungeon** | 1 (may be the marquee if it fits) | Open, 30-60 min to clear | 3-5 named bosses, a group event, respawning population, a shard, shared by everyone present | MS6 | MISSING |
| **Major** (named lore ruin, fort, tomb) | 2-3/km² | 15-40 min | A loop with a shortcut, a boss, a hook, a note, >= 1 set piece, a culture kit, a quest or unique payoff in >= 30% | Dungeon grammar, PA11, PA12, PA3 | PARTIAL (identity 2%) |
| **Delve** (tag on short caves and small ruins) | >= 4, plus 1.5/km² (cap 8) | 5-12 min | A boss, a star-shard, a hook, a note | MS7 | PARTIAL |
| **Minor** (camp, small cave, mine, wreck) | 4-6/km² | 2-8 min | A reason (who lives there), loot, a hook | Structures, PA3 | PARTIAL |
| **Fort** (garrisoned or ruined) | 0.5-1/km² | 10-25 min | A faction (bandits, Legion, Forsworn...), changes hands in quests or events | Forts, MS4 | EXISTS |

Variety rule per region: >= 5 carved palettes (`d_caverns`), >= 8 inhabitant rosters (`d_rosters`), no two
palettes sharing a floor and wall pair (PA11).

### 4.6 Landmarks (S / M / L / XL)

| Tier | Meaning (`landmarks/Tier.java`) | Per km² today | Target | Rule |
| --- | --- | --- | --- | --- |
| **S** | Tiny vignette: a grave, a cairn, a cold camp | ~17 | Keep | Every kind has a story clue; graves carry names |
| **M** | A scene with a story, often with someone in it | ~9 | Keep; 30% offer a micro-objective (MS18) | Feeds P-I7 |
| **L** | A natural formation: an arch, a sinkhole, hoodoos | ~4.5 | Keep; each region has >= 3 L kinds of its own | `l_kinds` >= 10 |
| **XL** | Visible from afar: a colossal tree, a spire stack, a crater lake | ~0.075 (one per 13 km²) | >= 1 per region plus 1 per 3 km², placed for sightlines from roads | P-B2, F-WEENIE |

### 4.7 Scenes and encounters

* **Planned scenes** (P6, EXISTS): 5-15 per km²; waysides at crossroads, fords, bridge ends and bends; settlement
  edges (cemeteries, fishing shores), farm yards, battle sites, shrine sites, dungeon mouths, lore-web clues.
* **Scripted world encounters** (`WildScenes`, PARTIAL; MS14): >= 8 kinds usable per region, >= 3 unique to the
  province. One roll per player every 15-25 minutes, daylight-biased.
* **Wonder moments** (MS14 data): >= 3 per region (an aurora night, a migration at dawn, a sky event at a shrine, a
  storm over the sea cliffs, a lantern festival seen from the road).

### 4.8 Quests per region

| Type | Count | Format | Status |
| --- | --- | --- | --- |
| Region storyline | 5-8 (fixed) | `quests/story_<region>.json`, MS1 schema | MISSING framework |
| Side quests per settlement | Per tier (4.4) | `quests/town_<province>_*.json` (PA4-PA9) | PARTIAL |
| Hidden "find" quests | >= 1, plus 0.5/km² | `start: find` | EXISTS |
| Lore-web arc | >= 1 (two parts) | `loreweb_<province>.json` | EXISTS (extend) |
| Faction chains | Per province (4.2) | `quest/faction` | PARTIAL |
| Radiant templates usable in the culture | >= 40 in total, >= 6 culture-specific | `radiant_templates.json` (PA22) | PARTIAL (33) |
| Notice-board offers in town and larger | 3-6 live offers | MS12 | MISSING |
| World events | 1 kind, 3-5 sites | MS4 | PARTIAL |
| Jarl-style bounties (named target in a named place) | >= 2 | Existing bounty quests | EXISTS (Skyrim); extend |

### 4.9 Economy, crafting and fast travel

* **Stations per tier:** a smithy, tanning rack and grindstone in every town and larger settlement; an enchanter
  and alchemy lab in every city and larger; a cooking pot at every inn (`Zoning.ensureTrades` guarantees inn, store
  and smith, plus an apothecary from town up).
* **A regional set station** per region (MS8).
* **Regional industry** per settlement with its product in local shops (P-G2, P-H1).
* **Fast travel** (MS11): every settlement node is discovered on arrival; wilderness nodes (wayshrines, standing
  stones, Velothi shrines, Hist stones, Yokudan way-tents) cost nothing to use and refresh a blessing. Max distance
  from any land point to a node is <= 800 blocks (90th percentile <= 500).

### 4.10 Mapping to existing systems (summary)

| Template part | Existing system | Doc |
| --- | --- | --- |
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
multiplayer.

* **MS1 Region story framework.** A `story` block per region (`quests/story/<region>.json`): `hook` (start NPC in
  the first town on the main road, plus a rumour and a courier letter on first entry to the region), 5-8 ordered
  quests with `requires`, a `consequence` per quest (NPC state, building swap, dialogue key, scene on or off) and a
  finale at the region's marquee. The journal gets a "Region" tab heading. Validator: `quest_lang.js` plus a new
  `tools/qa/story_lint.py` (counts, places registered, the hook within 800 road blocks of a region entry,
  consequences declared).
* **MS2 Region Guide and achievements.** A journal page per region listing story progress, delves, open dungeon,
  world bosses, shards, lorebooks, set station, surveys, treasure maps, fast-travel nodes and world events, as found
  / total. Completion awards a title and a small trinket. Achievements are data (`achievements.json`: id, region,
  condition = count of a tracked kind). Player data lives in `SkyData` module `guide`. It reads the same baked lists
  as MS3-MS10, so the guide is never out of sync.
* **MS3 Star-shards.** A glowing meteoric shard (original block and item): three absorbed give one perk point. Each
  region has 6 + 1.5/km² (cap 16): 1/3 in delves, 1/3 on vistas reached by climbing, 1/3 behind small puzzles or
  hidden paths. A cryptic hint line shows in the guide. Baked list `shards.bin` from places plus a vista finder
  (high points with line of sight). Per player, never respawning. Check: count per region; every shard reachable
  (reach from road ≤ 300 blocks, or inside a delve).
* **MS4 World events** (the dolmen analogue). A region-flavoured event at 3-5 fixed sites per region on the server
  clock (every 20-40 min per site, at most one active per region). It is announced on the map, compass and chat
  within 600 blocks, scales with the players present, gives per-player rewards and has a cooldown. Kinds:
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
* **MS5 World bosses.** A named outdoor foe at an L or XL landmark, with telegraphed attacks (wind-ups), party
  scaling, per-player loot and a respawn of 10-20 minutes. 1 + 0.25/km² per region (cap 4). Reuses the existing
  bosses block (named creature as a character) plus a `BossArena` helper. Check: count, and each one on a road or
  signposted trail.
* **MS6 Open dungeons.** A larger dungeon (marquee scale) whose population respawns, with 3-5 named bosses and a
  group event room. It is shared: every player present sees the same state, while loot stays per player (Lootr).
  Data flag `open: true` on a dungeon structure. Respawn timer per room cluster.
* **MS7 Delve tag.** Data only: `class: delve | major | marquee | open | minor` on dungeon places. A delve gets a
  boss slot and a star-shard slot. `dungeon_stats.py` reports room counts per class against the length bands
  (P-C5).
* **MS8 Crafting sets and set stations.** Each region has an attuned station at a landmark that crafts that
  region's set (armour, weapon, jewellery slots) with 2, 3 and 4-piece bonuses. Using it needs N "known traits",
  learned by breaking down or researching gear (a light research timer). Each province also has a regional motif
  (style) the smiths can learn from motif books found in its dungeons. Data: `crafting/sets/<region>.json`. This
  raises `i_sets` (PA21).
* **MS9 Surveys.** A survey report (sold by merchants and guilds, and found in camps) marks a rich cluster of 4-6
  gathering nodes (ore veins, herb patches, timber stand, fish shoal, game trail) somewhere in its region. Clusters
  are baked from biome and map context. One survey per gathering skill per region. The rich nodes regrow on the
  existing vein timers.
* **MS10 Lorebook collections.** Each region has a set of 6-12 original region books (history, local legends,
  bestiary, a diary) placed in its towns, dungeons and scenes. Reading the whole set grants a small reward (skill XP
  in a lore-tied skill, a map reveal, a recipe). Data: `lore/books/<region>.json`. The guide tracks it. This counts
  toward `r_notes`.
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
  party, refresh every two in-game days and are visible to every player at that board. This turns the 98% of
  dungeons without a quest into destinations (`d_identity` "quest" leg).
* **MS13 Signposting layer.**
  * Undiscovered places show on the compass within 150 blocks, Skyrim-style.
  * Quest-giver markers appear in towns, and the region-entry banner gets a "rumours here" line.
  * Hearing a rumour reveals the place on the map (this exists).
  * Light beacons appear at night on towers and camps, and smoke rises from inhabited camps.
  * The road signs (exist) name the next settlement, the next inn and the nearest delve.
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
* **MS16 Festival calendar.** One festival per province (and per Skyrim hold) on Tamriel's calendar. On the day,
  town decorations go up (a scene-plan overlay), a market adds stalls, festival quests appear and some towns run
  events. Data: `calendar/festivals.json`.
* **MS17 Level policy.** `Leveling` keeps region bands. Story quests read the player's level clamped to the band
  plus or minus 8. Delves follow the band. World bosses scale by party (MS15).
* **MS18 Point-of-interest micro-objectives.** M-tier landmarks and minor sites with notes may carry one small
  optional objective (find the cache the note mentions, lay the ghost to rest, free the captive, return the
  heirloom), shown as a small discovery-banner line. Data from PA3 generator output, as a `micro` field.
* **MS19 Region arrival moment.** The first entry to a region shows its banner (exists) plus a music sting, a short
  narrator line in the region's voice, and a courier or rumour pointing at the MS1 hook.
* **MS20 No-verbatim corpus check.** `tools/qa/verbatim_check.py`: on the owner's machine only, it compares our text
  (lang, lore, quests) against a local corpus in `.reference/` (book and dialogue text he extracted from his own
  games) by 8-word shingles. It reports any hit and exits 1. The corpus is never committed. This turns the legal line
  into a gate.

---

## 5. Gameplay flow

### 5.1 The moment-to-moment loop (what the world must supply)

| Time scale | The player should... | Supplied by | Check |
| --- | --- | --- | --- |
| Every 30-60 s | See or hear something worth a look: a scene, landmark, smoke, a herd, a sign | POI density, scenes, ambient life | `density_audit` (POI within 250 blocks), F-VISTA |
| Every 2-3 min | Choose: detour or stay on the road | Rule of three (P-B1), signposts, trails | F-DETOUR |
| Every 5-10 min | Get a small reward or story: a note, cache, shard, encounter, view | Minor sites, MS3, MS14, scenes | F-REWARD-RATE |
| Every 20-40 min | Finish something: a delve, a side quest, a world event, a town problem | Dungeons (MS7), quests, MS4 | F-QUEST-RADIUS, F-LOOP |
| Every session (1-3 h) | Advance a region story chapter, change a town, gain a level and perks, find a marquee place | MS1, marquee, MS2 | F-STORY, F-GUIDE |

### 5.2 The session loop: arriving in a new region

1. **Approach.** The road crosses the border. The region banner, music sting and narrator line play (MS19). Within
   the first 600 blocks of road the player passes a wayside, a sign naming the first town and at least one detour
   (P-B1).
2. **First town.** It sits on the main road, within 800 road blocks of the region entry (F-HOOK). It has an inn
   (rest, rumours, bard), a notice board (MS12), a shop with regional goods and a person with a problem. The region
   story hook is here.
3. **Region story hook.** The first story quest sends the player to 2-3 places in the region within 600 blocks,
   passing other content on the way.
4. **Discovery.** Between objectives the player meets delves, landmarks, shards, scenes, a world event announced
   nearby and a world boss on a ridge. The compass hints at undiscovered places; rumours reveal more.
5. **Dungeon.** A delve takes 5-12 minutes; a major dungeon has a loop and a shortcut, a boss, a note linking to the
   lore web and a reward that fits the region.
6. **Return.** Back in town the player sells, crafts at the set station, turns in quests and sees a consequence
   (the town changed). Fast travel to discovered nodes makes the return short.
7. **Onward.** The guide shows what is left. The story's next chapter, a lore-web clue or a carriage route points to
   the next town or region.

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
| F-STORY | Region story quest count; finale at the marquee | MS1 data | 5-8; finale = marquee |
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
| F-COLLECT | Per-region counts of shards, lorebook set, treasure maps, surveys, set station, uniques | MS2-MS10 data | Section 4.3 counts |
| F-GROUP | World bosses and open dungeon per region | MS5, MS6 data | 1-4 and 1 |
| F-EVENT | World-event kind and site count per region | MS4 data | 1 kind, 3-5 sites |
| F-GUIDE | Region Guide entries resolve (every tracked id exists and is reachable) | MS2 data plus `reach_lint` | 100% |
| F-LEVEL | Level band width per region; story quest level vs band | `Leveling` data, MS17 | Band <= 20; story within band +/- 8 |
| F-HUBS / F-COURT / F-BOARD | Faction hubs per province; capitals and cities with a court of 3+; towns and larger with a notice board | Faction, NPC and settlement data | >= 2 / 100% / 100% |
| F-ORPHAN | Named dungeons, forts and landmarks that nothing points to (no quest, board job, lore-web link, rumour or trail) | Union of references | 0 for major and marquee; <= 10% overall |
| F-POI | Micro-objectives per km² | MS18 data | >= 1 |

### 5.5 Definition of done for a region

A region is done when all of the following hold:

1. The `parity.py --assert-floor` categories pass for the region.
2. The FLW floors above pass.
3. `novelty_lint.py --strict` and the MS20 verbatim check report 0.
4. `reach_lint.py` reports no isolated major site.
5. The modernisation checklist (1.3) is ticked in the phase report.
6. One contact sheet per region (towns, dungeons, landmarks) passes Haiku review as unique and purposeful.

Owner playtests then judge feel.
