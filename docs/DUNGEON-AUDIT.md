# Dungeon audit (20 named dungeons, offline)

What dungeons really look like, measured without generating world chunks. Method: `/skycraft-dungeon-qa export <ids>`
paints each dungeon into the in-memory level and writes blocks + markers (`mod/run/qa-exports/<id>.json.gz`);
`tools/qa/dungeon_audit.py` renders them (`render_dungeon.py`, plan view with enemy/boss/chest/lectern/trap markers),
builds one contact sheet each (`docs/screens/dungeon-audit/<id>.jpg`) and runs `tools/qa/dungeon_reach.py`.
Sample: `tools/qa/dungeon_sample.json` (every theme, Skyrim holds, Cyrodiil, Morrowind/Solstheim, High Rock, Hammerfell,
Atmora). Sheet review was done by Haiku against the rubric below; the numeric columns are measured from the exports.

Rerun: `tools\qa\qa.ps1 -Server -NoBuild -ServerCmds "skycraft-dungeon-qa export <ids>"` then
`py -3 -I tools/qa/dungeon_audit.py`.

## Reachability (measured)

0 of 20 dungeons fail: every room, chest, spawner/mob and boss can be walked to from the entrance tunnel with
Minecraft walking rules once the mechanisms are operated. 9 dungeons are gated behind a mechanism (iron door or claw-door
ring: the boss room and 1-4 chests are unreachable until it is operated): gyldenhul_barrow, alftand, nchardak,
mthartzel, amelion_tomb, alas_ancestral_tomb, ahaleb_necropolis, aphrens_tomb, wolf_jarls_howe. That matches the design
(puzzle room before the boss) but the new checker cannot prove the puzzle is solvable; `/skycraft-dungeon-qa run` does.
Full table: `tools/qa/out/dungeon_audit/reach.md` (regenerate; large outputs are gitignored).

## Per-dungeon scores (1-5, Haiku over the contact sheets)

RSV room shape variety, MAT materials, DRS dressing/props, ENM enemy placement, BOS boss set piece, LTS loot and
story, FLW layout flow. Sum is out of 35. Rooms/mobs/asleep/chests are measured.

| dungeon | theme / region | rooms | mobs (asleep) | chests | RSV | MAT | DRS | ENM | BOS | LTS | FLW | sum |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ahaleb_necropolis | ruin, Hammerfell | 10 | 17 (4) | 15 | 4 | 4 | 3 | 4 | 4 | 4 | 4 | 27 |
| angarvunde | barrow, Rift | 12 | 21 (10) | 21 | 3 | 4 | 3 | 3 | 4 | 3 | 4 | 24 |
| alftand | dwemer, Winterhold | 9 | 23 (3) | 13 | 3 | 5 | 3 | 3 | 4 | 3 | 3 | 24 |
| alas_ancestral_tomb | ruin, Morrowind | 7 | 15 (6) | 17 | 4 | 3 | 2 | 4 | 3 | 4 | 4 | 24 |
| bleak_falls_barrow | barrow, Falkreath | 11 | 25 (6) | 16 | 3 | 4 | 3 | 3 | 4 | 3 | 3 | 23 |
| wolf_jarls_howe | ruin, Atmora | 10 | 20 (5) | 21 | 3 | 2 | 2 | 4 | 4 | 4 | 4 | 23 |
| fort_fellhammer | fort, Winterhold | 6 | 17 (0) | 33 | 3 | 3 | 3 | 3 | 4 | 4 | 3 | 23 |
| castle_volkihar | fort, Haafingar | 4 | 12 (0) | 20 | 3 | 3 | 3 | 3 | 4 | 3 | 3 | 22 |
| mthartzel | dwemer, High Rock | 7 | 17 (0) | 17 | 3 | 4 | 2 | 3 | 3 | 3 | 3 | 21 |
| gyldenhul_barrow | barrow, Solstheim | 9 | 17 (5) | 14 | 3 | 3 | 2 | 3 | 4 | 2 | 3 | 20 |
| nchardak | dwemer, Solstheim | 7 | 14 (0) | 12 | 3 | 4 | 2 | 2 | 3 | 3 | 3 | 20 |
| amelion_tomb | ruin, Cyrodiil | 7 | 15 (0) | 8 | 3 | 3 | 2 | 3 | 3 | 3 | 3 | 20 |
| aphrens_tomb | ruin, High Rock | 7 | 10 (0) | 13 | 3 | 3 | 2 | 3 | 3 | 3 | 2 | 19 |
| bonechill_passage | cave, Falkreath | 6 | 12 (0) | 8 | 3 | 2 | 2 | 3 | 3 | 2 | 3 | 18 |
| bilegulch_mine | mine, Falkreath | 5 | 10 (0) | 18 | 2 | 2 | 3 | 3 | 3 | 3 | 2 | 18 |
| badgerrun_quarry | mine, Cyrodiil | 8 | 12 (0) | 22 | 2 | 2 | 3 | 3 | 3 | 3 | 2 | 18 |
| ravenscar_hollow | hagraven lair, Haafingar | 4 | 7 (0) | 7 | 2 | 2 | 3 | 3 | 3 | 2 | 2 | 17 |
| bramcliff_crypt | necromancer lair, High Rock | 6 | 13 (0) | 8 | 2 | 2 | 3 | 3 | 3 | 2 | 2 | 17 |
| ahabah_hollow | cave, Hammerfell | 6 | 9 (0) | 10 | 2 | 2 | 2 | 2 | 3 | 2 | 2 | 15 |
| haemars_shame | vampire lair, Falkreath | 4 | 9 (0) | 4 | 2 | 2 | 2 | 3 | 3 | 2 | 1 | 15 |

"chests" counts every container (chest, barrel, chest minecart; dispensers excluded). Largest by rooms: angarvunde 12
(bleak_falls_barrow 11, the old run said 13); smallest: castle_volkihar, ravenscar_hollow, haemars_shame at 4.

## The five worst

1. **haemars_shame** (vampire lair, 15/35): 4 rooms (start, 2 normal, boss), 4 chests, no sleepers, no story beat
   (features list is only `room:vampire`, a tripwire and `loopback`). Haiku saw the plan as small islands with pits
   between and an unattached stair; reachability says everything connects, so treat the "islands" as unverified until
   someone looks at the plan tile.
2. **ahabah_hollow** (cave, 15/35): two levels, one long one-wide corridor and a dead-end stub, a lone 3x3 patch on
   level 2; troll den only (`room:troll` x6); 9 mobs all awake; interior shows loose cobblestone and ladders.
3. **ravenscar_hollow** (hagraven lair, 17/35): 4 rooms, 7 mobs, 7 containers; a T-corridor with a dead-end tail,
   level 2 reads as a strip plus a pit.
4. **bramcliff_crypt** (necromancer lair, 17/35): 6 rooms over three levels with a single room type
   (`room:necromancer` x6), soul-soil only; every enemy awake; vertical links not visible in the plans.
5. **bonechill_passage** (ice cave, 18/35): 10 ice wraiths all awake and identical, a long dead-end corridor, one
   material family; (bilegulch_mine and badgerrun_quarry tie at 18: 50% cobblestone, uniform rooms).

## Common failures (what the numbers show)

* **No sleepers outside undead themes.** Dormant mobs exist only in barrows and the tomb ruins that use draugr
  (angarvunde 10, bleak_falls 6, alas 6, gyldenhul 5, wolf_jarls 5, ahaleb 4, alftand 3). In 13 of 20 dungeons every
  enemy is awake at spawn: no ambush staging in caves, mines, forts, dwemer ruins (apart from alftand's one ambush
  beat), lairs or the bandit/guardian tombs.
* **Even, per-room enemy dosing.** Per-room counts are 1-5 and 16 of 20 dungeons have no enemy-free room (e.g. mthartzel
  3,3,3,2,2,2,1; amelion 4,3,2,2,2,1,1). There is no empty or quiet room for pacing, and the boss room is rarely
  distinguished by count.
* **One room type per cave/lair/mine.** `room:troll`, `room:necromancer`, `room:vampire`, `room:hagraven`, `room:mine`
  (x5-8), `room:ice` fill every room of the dungeon; barrows, ruins and dwemer halls get 5-9 different types. Cave and
  lair plans read as a corridor with a boss cell; rooms are 4-6 in lairs and castle_volkihar.
* **Story is mostly invisible.** `story:journal` is announced in 16 of 20 dungeons but only one dungeon has a lectern
  with a book (bleak_falls_barrow) and four have a word wall; the journal must be a book in a barrel, which a player
  cannot see from the plan. Unique loot tables appear only in the named uniques; cache/boss/minor tables elsewhere are
  generic (`dungeon_common`, `dungeon_minor`, `mine_ore`).
* **Dead-end corridors and a tiny second level.** One-wide corridors that end in rock (aphrens_tomb, alas, ahabah,
  bonechill, ravenscar) and level 2 pockets of 70-90 cells hanging off level 1 (gyldenhul, mthartzel, bleak_falls,
  bramcliff). Several are the entrance tunnel, which is intended; Haiku could not tell the difference.
* **Surface strips look unfinished.** structure-only views show lone ladder columns, tall one-block towers, a rail
  track with no supports (mine, quarry) and a stair ending on the ground (bleak_falls). Some are the entrance shaft and
  mine rails; the supportless rails/walkways and the freestanding tower in alas_ancestral_tomb are worth a look in game.
* **Material palette is narrow in caves and mines** (cobblestone 50% in the quarry; soul soil only in the necromancer
  crypt; ice/stone in the ice cave). Dwemer and Redguard tombs are the best (copper/blackstone, red sandstone).

## Fix list (ranked, what code to change)

1. **Mobs / DormantMobs / Beats (all non-undead themes):** add a sleeper/ambush beat to caves, mines, forts, lairs
   and dwemer halls: bears and trolls asleep in dens, bandits asleep in bedrolls, automatons dormant until a plate;
   today only the draugr rosters call `spawnDormant`. Vary count per room (0 in one room in three, a patrol of 2
   between rooms, a 4-5 pack only where the beat says so) instead of 2-3 everywhere. Highest payoff: affects 13 of 20.
2. **Layout (lair and cave sizes) + NaturalRooms / CaveRooms:** raise the minimum room count for lairs and forts
   above 4, and make sure a cave/lair has a loop and a side chamber, not a T. Currently `Theme` depth/size for
   hagraven, vampire and necromancer lairs yields 4 rooms.
3. **CaveRoster / room variants:** give lairs, mines and ice caves 3-4 room templates (den, collapsed gallery, flooded
   pool, shrine/camp, ore vein, bone nest) rather than one type repeated; the dispatch is by theme in `NaturalRooms`,
   `CaveRooms`, `FortRooms`.
4. **Deco / Painter palettes:** broaden mine/quarry (add timber, rubble, lanterns, minecart sets, andesite/tuff mixes),
   lairs (bones, hides, fungi, candles, cages) and ice caves; dressing density 2/5 in 8 of 20.
5. **Story / SetPieces:** put the journal on a lectern or a reading desk (visible set piece) in every dungeon that
   announces `story:journal`, with a unique name for the dead adventurer; add one unique item/loot table per named
   dungeon beyond the uniques. Word walls only in barrows and a few ruins.
6. **Traps / Shafts / Stairs:** audit ending-in-rock corridors and the level-2 pockets: either end them in a
   room/niche/cache or remove; check the surface mine rails (supports) and the free-standing tower in
   alas_ancestral_tomb.
7. **Tooling:** the renderer now has export mode; to judge the surface props properly, export also records which cells
   were painted (so cave floors need not be inferred from piece boxes), and the chest minecart is drawn as a chest.

## Rubric used for the sheets

Room-shape variety; material variety; dressing/props density; enemy placement (sleepers, patrols, ambush, not
clustered or evenly gridded); boss set piece; loot and story presence (chests, journals, uniques); layout flow/loops;
anything broken or odd (floating blocks, disconnected stairs, empty boxes). Scores come from Haiku looking at a
five/six-tile sheet per dungeon and are coarse; the measured columns and the reachability report are exact.
Known reviewer noise: the structure-only tile hides natural rock, so cave floors look like "floating" cubes there;
Haiku's "chest markers vs chest block entities" mismatch complaints are wrong (markers include barrels and minecarts).

## Round 2: grand caverns, longer dungeons, pacing (after)

Code: `GrandCavern` (new), `Layout` (multi-cell caverns, loops, deeper descents), `Theme` (room counts, depths),
`Pacing` (moods, patrols), `CaveVariants` (room templates), `Story` (lecterns, names, relic caches),
`NaturalBuilder`/`NaturalRooms` (rock palettes, light), `DormantMobs` (pressure plates). Tools: `tools/qa/dungeon_cavern_view.py`
(cutaways), `dungeon_stats.py` (the measured columns), `dungeon_audit.py --extra` (more ids, adds the cavern tiles).

What was added:

* **Grand caverns** (`FLAG_GRAND`, one piece, bounded volume): 43x43 or 73x43 (and 43x73) blocks, 15-32 tall, dome roof.
  A walkway ring at the doors' level with galleries raised 4-6 blocks on the wall side; a terraced bowl with a lake
  (never more than one block per step) or a chasm with ladders back up; five-wide natural-rock bridges over it to a central
  platform; giant columns, stalactites, stalagmite mounds; a waterfall where there is water; posts of light along every
  walkway. Five looks: fungal grotto, crystal cavern, frozen cavern, ember cavern (lava lake, bowl only), flooded cavern;
  chosen by theme and roster. Fluids sit in basins whose rim is never lower than the surface, so they are sealed.
  Caves/mines/lairs/forts-undercroft get one from 8 rooms and two from 12; the entrance of a natural dungeon may open onto
  the first from a ledge (the vista). Barrows, ruins and Dwemer delves use it as a deep level.
* **Length**: named dungeons +3 rooms, random sites -2; theme counts raised (cave 7-11, mine 7-10, lairs 6-9, barrow 9-12,
  dwemer 9-12, ruin 8-12, fort 4-6); descents up to 5 blocks a step, depths 16-32; up to three loops in a big dungeon.
* **Variety**: ten room templates for carved dungeons (collapsed gallery, flooded pool, shrine, ore outcrop, bone nest,
  fungal garden, ritual circle, prison, armoury, camp), wider rock palettes per lair/mine/cave, ceiling glow stones.
* **Enemies**: `Pacing` gives each ordinary room a mood: a fifth empty, three in ten sleeping (dormant: bears, trolls,
  bandits in bedrolls, Dwemer automatons until a pressure plate is stepped on), one in seven a pack; one corridor in four
  has a two-foe patrol. Beats (ambush, elite, boss) are untouched.
* **Story**: the adventurer's journal lies on a lectern in front of the bones, titled with a name of their own; caches use
  a relic loot table of the dungeon's kind (`chests/relic_*`); grand caverns hold a cache on a gallery and a platform chest.

Measured (24 dungeons, one world seed, `skycraft-dungeon-qa place` + `export`; analyzer: 0 blockers; 2 offline-checker
"chest unreachable" cases, both ordinary cache rooms, no room or boss unreachable):

| | before (20) | after (same 20) |
|---|---|---|
| rooms, mean (range) | 7.5 (4-12) | 12.2 (7-18) |
| mobs, mean / asleep share | 14.8 / 13% | 29.3 / 34% |
| containers, mean | 14.8 | 21.4 |
| dungeons with a grand cavern | 0 | 18 of 20 (forts excepted) |

Haiku sheet scores after (1-5 per column, sum out of 35, WOW = "epic / vista / glow cavern" 1-5, new column):

| dungeon | before sum | after sum | WOW | note |
|---|---|---|---|---|
| bleak_falls_barrow | 23 | 19 | 3 | blank zones on level plans |
| angarvunde | 24 | 19 | 4 | |
| gyldenhul_barrow | 20 | 17 | 3 | level 2 nearly empty |
| alftand | 24 | 22 | 2 | |
| nchardak | 20 | 22 | 3 | |
| mthartzel | 21 | 20 | 3 | |
| bonechill_passage | 18 | 19 | 4 | ice chasm reads well |
| ahabah_hollow | 15 | 20 | 3 | |
| bilegulch_mine | 18 | 24 | 3 | |
| badgerrun_quarry | 18 | 22 | 3 | |
| amelion_tomb | 20 | 19 | 2 | |
| alas_ancestral_tomb | 24 | 24 | 3 | |
| ahaleb_necropolis | 27 | 21 | 3 | |
| aphrens_tomb | 19 | 20 | 2 | |
| wolf_jarls_howe | 23 | 23 | 3 | |
| fort_fellhammer | 23 | 16 | 1 | no cavern, linear fort |
| castle_volkihar | 22 | 19 | 2 | |
| ravenscar_hollow | 17 | 22 | 3 | |
| haemars_shame | 15 | 24 | 4 | lava-lake cavern strong |
| bramcliff_crypt | 17 | 21 | 3 | |
| lost_echo_cave (new) | - | 22 | 3 | |
| whistling_mine (new) | - | 23 | 4 | |
| movarths_lair (new) | - | 21 | 4 | |
| crystaldrift_cave (new) | - | 24 | 4 | |

The mean of the 20 is 20.4 before and 20.7 after: the weakest ones rose by 5-9 points (haemars_shame, ahabah_hollow,
ravenscar_hollow, bilegulch_mine, bramcliff_crypt) and the strongest barrows and forts fell 3-8, which is mostly the
reviewer (Haiku scored different seeds' sheets: this round's worlds differ from the last, and it marks big blank level
plans down). WOW averages 3.0 over the 24. The scores are coarse; the measured table above is the reliable part.

Views of the new caverns: `docs/screens/dungeon-audit/<id>_cavern0_half.png` (cut along the middle), `_dome.png` (roof off)
and `_plan.png`, for bonechill_passage (frozen chasm 73x43), whistling_mine, haemars_shame (lava bowl), alftand,
angarvunde and ravenscar_hollow. The contact sheets `<id>.jpg` in the same folder now include the cavern tiles.

Open in game: the look of the glow (froglights, sea lanterns, shroomlights), the waterfalls (fluid ticks start on first
chunk tick), pressure plates waking the automatons, the 2 offline "chest unreachable" cases, and worldgen time of a
73x43x32 cavern (analyser paints it with the rest in under a second of CPU; not measured in the real chunk pipeline).

## Skyrim identity (named dungeons: story hook, set piece, loot, master)

Goal: each of Skyrim's 292 named dungeons and forts (dungeon_fixed + fort of `skyrim_landmarks.json`; the Tower of Mzark
does not start, see below) reads as its own place, not a generic one. Everything below is data written by
`tools/qa/dungeon_identity_gen.py` from the places and the place lore (`skycraft/dungeons/identity/skyrim.json`,
`blueprints/motif_*.json`, `loot_tables/chests/story_*.json`), plus a small Java layer (`dungeons/world/Identity`).

What a player meets:

* **Entrance**: a sign naming the place and the person its story is about (the author of the lore's first reader note, or
  who lies in the barrow), and a lectern with that note (a named person's letter, journal or orders). Surface sites (forts,
  camps, towers, ruins) get the same at the front edge of the footprint, plus a stash chest of the place's own loot.
* **Master**: a named boss. Chiefs of bandit, Forsworn, vampire, necromancer, hagraven and mage places carry the name the lore
  gives them (e.g. Ingvild the Breaker, Mistress Hlevala); all others get a deterministic original name from per-family pools
  (draugr jarls, Falmer elders, Dwemer warden-engines, beasts), unique across the 292. Surface ruins and camps now have a master too.
* **Boss chamber**: the master's own note on a lectern and a hoard chest of the place's story loot.
* **Set piece**: one of 46 motif blueprints chosen by the place's name (throne, urn wall, ossuary, trophy wall, prisoner cage,
  blood altar, bone table, chaurus nest, ice shrine, ore cart...) or by family and the id's hash; it stands against the far
  wall of the set-piece room and of the boss chamber (on open ground at surface sites).
* **Loot**: half of the ordinary chests (common/minor tables) and 2 of 3 caches use `chests/story_<family>` (20 families:
  barrow, ruin, dwemer, bandit, necromancer, vampire, hagraven, forsworn, falmer, den, ice, mine, grove, smuggler, cave, giant,
  dragon, tower, cabin, wreck), built from the existing theme/camp/relic/corpse tables plus a signature item.
* **The adventurer's room** uses the lore's person for the journal title when the entrance note is a journal or diary.
* Lectern notes are written books tagged `SkyNote` (a vanilla lectern only shows written books; `PlaceLoreEvents` turns
  the right-click into the place note's reading screen, so quests and discovery hooks still fire).
* **Discoverable**: every Skyrim dungeon now has a road or trail: lairs, dens, camps and cabins joined the long-footpath
  list (`RoadGraph.TOURIST_VARIANTS`, mirrored in `reach_lint.py`): isolated Skyrim places 9 to 0. The map already marks
  discovered dungeons.

### The lint

`py -3 -I tools/qa/dungeon_identity_lint.py [--exports mod/run/qa-exports] [--compare old.json] [--static]` reads the exports
(`/skycraft-dungeon-qa export <ids>`, at most 12 ids per command: one command is one tick and more than ~12 trip the 60 s
watchdog; chain the commands with `|` in `qa.ps1 -ServerCmds`), the place data, the lore and the reach lint, and flags per
dungeon: `no_sign`, `no_lectern`, `no_lore_note`, `no_boss`, `unnamed_boss`, `roster` (enemies do not match the family),
`no_motif`, `generic_loot` (no story table and under a quarter themed), `no_trail`, `lore_mismatch` (hook vs structure).
It lists the worst offenders and writes `tools/qa/out/dungeon_identity.json`. The exporter now also records signs and the
lore note a lectern holds.

| flag (292 Skyrim dungeons) | before | after |
|---|---|---|
| no_sign | 292 | 11 |
| no_lectern (no book anywhere) | 139 | 7 |
| no_lore_note (no named person's words on a lectern) | 292 | 8 |
| no_boss | 70 | 2 |
| unnamed_boss | 42 | 0 |
| roster | 3 | 1 |
| no_motif | 292 | 9 |
| generic_loot | 210 | 4 |
| no_trail | 9 | 0 |
| lore_mismatch | 1 | 1 |
| **total flags** | **1350** | **43** |
| dungeons with no flag | 0 | 268 |

Worst before: the 12 Nordic ruins, 16 Imperial/Stormcloak camps, the Forsworn towers and camps, shipwrecks, vampire lairs
and the bandit camps (5 to 7 flags each: no sign, no book, no master, no motif, generic loot). Worst after: the shipwrecks
(11, 1.5 flags each: they sit in the shallows and no dry post fits), a few cabins and dragon lairs without a motif
(rough ground), Temple of Miraak (a unique layout).

Known leftovers: `deepwood_redoubt` is a Forsworn stronghold in the lore but the `barrow` variant builds draugr (a variant
change would lose its word wall; needs a decision); `tower_of_mzark` (variant `mzark`) has no structure and does not start;
`castle_karstaag_ruins` and a few Solstheim barrows mix in ash spawn and ice wraiths from their cavern look.

### Analyzer

`skycraft-dungeon-qa place` on one dungeon per variant (45), fixed world seed: 3 dungeons report blockers (`frossel` den:
2 rooms stuck in terrain; `shadowgreen_cavern`: 80 spots with no way back out; `brucas_leap_redoubt`: boss room unreachable).
All three reproduce identically with the identity layer switched off (index.json removed), so they are existing layout and
terrain faults of those seeds, not caused by this work; other seeds show other places (benkongerike, ashfallow_citadel,
fahlbtharz, ysgramors_tomb, lower_steepfall_burrow). The earlier "0 blockers" held for the 24-dungeon sample only. Server
selftests, population and data checks pass.

### Open in game

Sign text width (14 characters per line is tuned for the standard font), the lectern note reading screen (right-click on the
written book), the look of the motifs on rough cave floors, hoard chests next to bosses, and whether the stash/boss
spawn at surface sites stand clear of the walls. `PlaceLorePlacer` puts `skycraft:book` items on its site lecterns, which a
vanilla lectern does not show (`hasBook` only accepts written books); the same `SkyNote` trick would fix those.
