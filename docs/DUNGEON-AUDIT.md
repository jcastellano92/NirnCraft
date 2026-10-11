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
