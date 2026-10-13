# First full playtest: what to check

Build: `dist/Skycraft-client-instance.zip` (Prism Launcher: Add Instance -> Import). Branch `tamriel-overhaul`.
Everything below was verified only headless (`tools\qa\qa.ps1 -Server`: build, data, layout lint, world boot, 1,072
quests played to completion, magic selftest) and with offline renders. **Nothing in this list has been seen in game.**

**Use a NEW world.** Worldgen changed (terrain rasters, hydrology, caverns, dungeon layouts, cities), so old saves
will not match new chunks. For an existing save, `/skycraft-roads replan` picks up the new road ends only.

## 1. Boot and spawn (5 min)
- World creates without a crash; you spawn at Whiterun (0,0). Check the log for `ERROR`.
- Flying/teleport around Whiterun: terrain, rivers (White River), clouds at y 330, shader/DH if installed.

## 1b. The opening: cottage, loading screen, cutscene, key (intro v3; check all of it by eye)
Verified headless only (`/skycraft-quest intro selftest`: site, support, windows, door, path, viewshed, state machine
with a stand-in player). **Nothing below has been seen in the game.** Use a new world (or `/skycraft-quest intro restart`
on an old character; config `quest/introExistingSaves`).

1. **First join**: you appear inside the cottage (no gate spawn, no second teleport). The character screen opens there.
2. **After the character**: a dark "The valley wakes" screen with a progress bar until the chunks around you and the
   town are in (45 s at most, `introLoadingSeconds`), then it fades; no terrain popping in behind it.
3. **The cottage from outside** (go out with `/tp` as an op, or after the key): sits on real ground (no platform on air,
   retaining wall where the hill falls away), timber walls, stepped gable roof, chimney with a smoking campfire, porch
   with posts and a lantern, woodpile, well, fenced garden with crops, shutters, lanterns. Check the roof and chimney join.
4. **Inside**: windows are solid sheets of glass in their openings (no thin cross), the big window faces Helgen,
   bed, table, hearth, shelves; nothing blocks the door.
5. **The view**: from the big window the whole town (four walls, keep, roofs) is in sight over a cleared meadow; forest
   stays on both sides. Trees in the cone are topped, not bare (check it does not look like a lawnmower strip; tell me if
   the cut line is ugly and I will thin by species instead).
6. **Walk to the window** (about 2 blocks from it): controls freeze, black bars slide in, the view zooms a little toward
   Helgen, the camera shakes. The dragon (Alduin) rises over the ridge, circles, breathes fire six times (a streak of flame
   with debris and explosions where it lands), the town burns with tall smoke columns, embers and ash; bells, roars with
   a pitch shift as it passes, crackle, timber collapsing, screams; sub-bass thumps on impacts; narration cards between
   the bars; it flies off; the bars retract; the title "Helgen is burning" and the journal entry appear.
   No block is damaged.
7. **Per player**: with two players, neither sees the other (no body, no name tag) until they leave; neither sees the
   other's dragon; the second can still be in the cottage while the first leaves.
8. **The key**: a "Cottage Key" is in the pack after the scene; it cannot be thrown away. Right-click the door with it:
   the key is consumed and you stand at the start of the path below the porch. The door never lets you back in (and
   others inside are unaffected). A relog before that resumes the state (cutscene restarts from the window).
9. **The path**: winds down the hillside (spruce stairs on steep runs, posts and lanterns, edge stones, no trees in it)
   and meets the Helgen road at its north side; follow it to the gate with no gaps or floating cells.
10. **Locked in until then**: the door will not open without the key; `/tp` as a non-op, ender pearls and the like are
    refused; a player pushed out of the cottage is put back.

Quick checks: `/skycraft-quest intro status|selftest|dump|map x1 z1 x2 z2|column x z`, `restart [player]`, `skip`,
`rebuild`. Config `skycraft-quest.toml`: `intro`, `introLockExit`, `introHidePlayers`, `introLoadingSeconds`,
`introCutsceneSeconds`, `introExistingSaves`. Not done: the sight cone keeps stray boulders (about 1% of the town's
sample points), and the Helgen town wall hides the ground just behind it from a low window (by physics, the roofs and the
keep are seen).

## 2. Look at (each: does it look good, is it broken?)
| Area | Where / how | What to judge |
| --- | --- | --- |
| Organic cities | `/skysettle plan <id>` for a diagram; visit Markarth, Riften, Solitude, Windhelm, Whiterun, then Balmora, Aldruhn, Bravil, Lilmoth, Alinor, Elden Root, Rimmen | curved streets, density, civic features (trees, fountains, shrines), no big empty gaps, gates meet roads |
| Roads | walk Whiterun -> Riverwood, Chorrol -> Skingrad | roads reach gates, roadside carts/barrels/lanterns, bridges at streams |
| Rivers and waterfalls | `/skycraft-explore near`, any big river in the Rift/Cyrodiil | streams from mountains, lakes with outlets, waterfalls (some with a cave behind), salmon run in autumn |
| Dungeons | Bleak Falls Barrow, Alftand, Miscarcand, Ruins of Mazzatun, any cave/mine | length, grand caverns (glow, bridges, lakes), sleepers vs awake, lectern journals, boss room, back-door shortcut |
| Landmarks and scatter | wander 500+ blocks from a town | S/M/L/XL landmarks, graves with names, camps, debris thinning with distance |
| Region borders | walk Jerall -> Stonefalls, Craglorn -> Wrothgar | transitions look natural, no straight seams |
| Quests and NPCs | pick up a chain in a marquee town (Vivec, Mournhold, Balmora, Imperial City, Alinor, Torval) | text, choices, consequences, NPC placement |
| Lore web | find a clue note, follow it | hidden quest starts, points to next place |
| Sea routes | any port; Tang Mo, Pyandonean anchorage | ships follow routes; Solitude routes use straight lines (no open-water path) |

## 3. Known gaps (not bugs to report; not done yet)
- Not built: ILIAC BAY (Daggerfall geography for High Rock/Hammerfell), ITEMS AFFIX. World generation is still
  the slowest thing on first visits (see section 4 for what was done).
- Roads: 1 unserved gate (Riverwood); foreign islands have roads only on the Pyandonean pair; sea routes
  solitude-dawnstar/windhelm/raven_rock fall back to straight lines.
- Not verified visually: cloud lighting changes, distant lightning, waterfall caves, ford beds, shaft ramps,
  honeycomb/city-wall cavern shapes, floating props flagged by the town audit (pond, themed statue, bell tower).
- Engine limits: NPC daily routines (fixed anchor only), no trainer role, no moon phases for quest gates.
- Content depth outside Skyrim is still below Skyrim (see `docs/PARITY.md` for the scorecard and backlog).
- `Tried to load a block entity for block skycraft:bedroll` warnings at worldgen (harmless, to be fixed).

## 4. Useful commands
`/skycraft-dungeon-qa view <place> [boss|start]` teleports into a dungeon room; `/skycraft-landmarks showcase`;
`/skycraft-scatter stats <x> <z> <r>`; `/skysettle plan <city>`; `/skycraft-explore near`; `/skycraft-quest selftest`.

## 5. When reporting
Screenshot + coordinates (F3) + what you expected. Server log: `logs/latest.log`. The audits to re-run after fixes:
`tools\qa\qa.ps1 -Server`, `py -3 -I tools/qa/parity.py --lowest 10`, `py -3 -I tools/qa/dungeon_audit.py`.

## 4. Performance, seed and pre-generation (new)
- New worlds default to the seed text **Nirn** (the create-world screen shows it; type another seed to change it, blank
  means Nirn). On a dedicated server set `level-seed=Nirn` in `server.properties` before the first start.
- The idle pre-generator is on for single-player/LAN worlds (throttled: one chunk at a time while you play, paused
  when the server is slow or you move fast) and off on dedicated servers (`skycraft-perf-server.toml`, `[pregen]`).
  `/skycraft-pregen start <blocks> [now]`, `stop`, `status`. Look for: no "Can't keep up" while it runs, and the
  area ahead of where you ride/walk being ready.
- Fast travel (map, carriage) now shows "Travelling to ..." with a progress bar while the destination loads, then
  arrives with no hitch. Ferries already load the destination during the voyage.
- Baked data (landmass labels) loads at startup; `/skycraft-bake check` (op 4) says "up to date".
