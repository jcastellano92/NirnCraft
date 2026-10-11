# Playtest 2: integration QA of the Tamriel world

This is the QA pass over the merged Phase 2 foundation. It covers:

* Phase 1 fixes.
* Regions and the map screen.
* Audio.
* Visuals: weather, sky, HUD and loading screens.
* Landmarks and dungeons (S2).
* Magic and skills.
* NPC townsfolk.
* Biomes (B).
* Terrain (T).

The work being done in other worktrees (S1 settlements, creatures, quests, arms, survival, races and sailing) was
not part of this test. Base commit: `8d5c1e9`.

## How it was tested

### Dedicated server

* Command: `./gradlew runServer` with `-Xmx4G`, a fresh `run/world`, seed 20261010, and RCON on port 32561.
* Driving scripts: small Python RCON scripts. They are not in the repo.
* Startup log: the world was created with the Tamriel preset, showing `Loaded 543 places of Tamriel` and
  `Loaded 79 regions of Nirn`.
* Spawn: `Tamriel world spawn set near Helgen at (-24, 112, 1007)`.
* Errors: none. `latest.log` had no `ERROR`, `Exception`, `Feature order`, `Couldn't`, `Unknown` or `missing`
  lines, apart from Forge's harmless "missing mods.toml" notices for its own language jars.

### Visiting areas

* Each area was force-loaded as 9×9 chunks around its centre, timed until every chunk was loaded, then checked
  with `/forge tps`. See Performance.
* Areas visited:
  * Skyrim: Whiterun, Riverwood, the Throat of the World, Riften, Markarth, Solitude, Winterhold, Falkreath and
    the Pale.
  * Elsewhere: Solstheim, the Imperial City, Vvardenfell, the Alik'r, Black Marsh, Elsweyr, Summerset, Atmora,
    Akavir and a far realm at (60000, 60000).

### Structures

* Vanilla `/locate` can't find `skycraft:places` placements (see Known issues). Instead, a throw-away datapack in
  `run/world/datapacks/qa` defines a `location_check` predicate per structure.
* Every named place within 1000 blocks of (0, 300) was force-loaded and tested with
  `execute positioned … if predicate`.
* **All 40 places checked had their structure start.** These included:
  * Bleak Falls Barrow, High Hrothgar and the Throat summit.
  * The Ritual, Lady and Guardian stones.
  * Forts, camps, caves and a dragon mound.

### Places, palettes and lang

* Each of the 543 places was compared with the generator's surface, floor and map water, and with its region.
* Palette coverage:
  * Every region resolves all 12 contexts.
  * Every palette biome exists.
  * Every catalogue biome is used.
* Lang: every region, place, biome, shout name and shout subtitle has a key.

### Heightmaps

* Rendered with `/skycraft-tamriel heightmap` and inspected:
  * Skyrim (r 3300).
  * All of Tamriel (r 10400).
  * Whiterun and the Throat.
  * Riverwood.
  * Solstheim.
  * Hjaalmarch.
  * Black Marsh.

### Client

* The autotest harness gained three modes (`ClientAutotest`):
  * `-Dskycraft.autotest.createflow=true`: makes the world through the title screen and `SkyCreateWorldScreen`,
    the way a player does. It logs and screenshots any screen that stays up.
  * `-Dskycraft.autotest.load=<save>`: continues an existing save the same way.
  * `-Dskycraft.autotest.tour=Name:x:z[:yaw],…`: captures the HUD and map screen, then visits each spot in
    spectator mode through the integrated server, so cheats don't need to be on. It takes a ground shot and an
    aerial shot, and clears the regional weather at each spot.
* Example:

  ```
  $env:JAVA_TOOL_OPTIONS="-Xmx4G -Dskycraft.autotest=true -Dskycraft.autotest.createflow=true -Dskycraft.autotest.tour=Whiterun:0:0:-90,Throat:579:652:180"
  ./gradlew runClient
  ```

  Screenshots go to `mod/run/build/autotest-screenshots/`.
* Two full tours were run: 17 spots before the fixes and 17 after. A load test was also run. Every screenshot was
  inspected.

## Bugs found and fixed

1. **A new world stopped at "Warning! These settings are using experimental features".**
   * Vanilla flags every worldgen entry that comes from a mod data pack as experimental, so the warning appeared
     on New Game and again when a save was continued.
   * Fix: `ClientEvents.onScreenOpen` now recognises the vanilla experimental-settings confirm screen (or the
     backup prompt for experimental settings). It cancels the screen and answers "proceed" on the next tick.
   * Verified: creating and continuing a world both go straight to the loading screen. The log says "Answered the
     experimental settings warning".
2. **Rivers were broken into chains of puddles.**
   * The raster stores exact river distances at the cell centres. Blending those distances bilinearly can't reach
     zero between centres: the minimum stays up to 16 blocks off the centre line.
   * As a result, every river with a half width under about 8 blocks was missing over most of its course. That
     included the White River at Riverwood, the Karth and the rivers of the Rift.
   * Fix: `MapRaster.riverDistance` fits a planar signed distance through the four corner cells near a river. It
     tries each sign pattern and uses the fit when it explains the corners well. It is allocation-free and only
     runs near rivers.
   * Result: rivers are now continuous channels with water (see screenshots 05 and 11). `TamrielMap.sample` costs
     about 165 ns instead of 150 ns.
3. **Marshes were full of dry pits below sea level.**
   * Hjaalmarch and Black Marsh sit at sea level, and the terrain keeps "hollows that hold pools" there.
   * The inland aquifers leave those hollows as air, so Hjaalmarch and Stormhold were pocked with dry holes 1–5
     blocks deep.
   * Fix: `MapWaterFeature.fillMarshPool` fills open-sky dips up to sea level where the map's wetland is 0.5 or
     more and the dip is at most 8 blocks deep. Deeper holes are cave mouths and stay dry.
   * Verified: (−520, −1524) now has water at y 62.
4. **Daedric shrines were placed at sea.**
   * About 20 of the 128 province shrines in `tamriel_shrines.json` were in the sea, in lakes or in rivers: Summerset, Auridon,
     the Inner Sea, the Abecean coast, Topal Bay and Lake Rumare.
   * Cause: `tools/landmarks.py` placed them before the terrain existed.
   * Fix: the script now reads the map rasters. It only accepts dry inland cells of the right province (inland
     more than 64 blocks, at y 68 or higher, away from lakes and rivers), falls back to a ring search for small
     islands, and takes the region from the map.
   * Result: the regenerated file has 0 shrines on water. Only `tamriel_shrines.json` changed; every other output
     was identical.
5. **Named sites in lakes and in cave mouths.**
   * Before, a site whose anchor was in one of the map's lakes or rivers was built on the lake bed, then flooded
     by the water feature. Examples: the Lady Stone (64 blocks into Lake Ilinalta) and shrines in Lake Rumare.
   * Sites whose anchor was at the bottom of a cave mouth were built in the hole. Example: Glenmoril Coven, where
     the map is at y 123 but the floor is at y 61.
   * Fix: `DungeonStructure.surface` now treats the map's water, and a floor more than 24 blocks below the map
     surface, as unusable. The existing nudge (up to 56 blocks) then moves the site onto dry ground. Random sites
     avoid these spots too.
6. **Spawn could land in a cave mouth.**
   * The first client world spawned at the foot of a stone wall.
   * Fix: `TamrielSpawn.findDry` now requires the spot to be within 12 blocks of the map surface and level to
     within 3 blocks over 6 blocks around.
   * Verified: the next world spawned on open forest floor near Helgen (screenshot 02).
7. **Eleven shouts were silent.**
   * The magic workstream added Ice Form, Throw Voice, Dismay, Call Dragon, Call of Valor, Cyclone, Drain
     Vitality, Soul Tear, Bend Will, Dragon Aspect and Battle Fury. Audio's catalogue didn't have them, so the
     client logged 33 "Missing sound for event: skycraft:shout.*".
   * Fix: added them to `tools/music/sound_catalog.py` and `synth_skyrim_sfx.py` (with an elemental flavour each),
     synthesised the 33 chants (original, procedural), and regenerated `sounds.json` and the subtitles.
   * The SFX total is now 3.8 MB, over the script's soft budget of about 3 MB.
8. **Dev tooling.** `/skycraft-tamriel sample` now also prints `floor y` (OCEAN_FLOOR_WG). Without it, a column
   at sea level couldn't be told apart from a dry pit or open water.

## Known issues (not fixed)

### Design issues

These need design work, so they were not changed.

1. **The biome at Whiterun (0, 0) is a pine forest.**
   * Three seeds out of three gave `skycraft:pine_forest` exactly at the city (screenshot 04).
   * The 64-block census around it is mostly `whiterun_tundra` (216 of 289), so the pick noise must have a
     seed-independent extreme at the origin.
   * Settlements will cover the city, but the tundra should reach the walls.
   * Suggestion: drop pine from Whiterun's lowland palette near the capital, or offset `BiomeNoise` from the origin.
2. **The Throat of the World is a broad, rounded dome.**
   * It is about 700 blocks across with a flat-ish top at y 330–375, and its surface is mostly blue and packed
     ice in rectangular patches (screenshots 06 and 11).
   * Suggestion: a sharper summit (ridge noise on top), and more snow and stone with less blue ice in the
     `throat_peak` surface.
3. **Most of Skyrim's interior is an even y 90–130 plain.**
   * Real Skyrim has many more hills between the holds: Whiterun–Falkreath, the Pale highlands and the Rift's
     hills.
   * Only the Reach, the Throat and the border ranges have relief. Hjaalmarch is a dead-flat sea-level sheet
     (heightmap screenshot 11).
4. **The Rift shows no autumn birch near Riften (2435, 1414).** It is pine forest and flower meadow. Check the
   weights in the Rift palette.
5. **Vanilla clouds at y 192 cut through mountains.** This happens in the Reach (Markarth's ground is at y 180),
   the Velothi range and the Jerall range (screenshot 07). Raising the cloud height needs custom
   `DimensionSpecialEffects` for the overworld type, which is visual's call.

### Data and lore

6. **Some places sit on sea-level flats or slightly off their region.**
   * Solstheim's coast is a wide ring at y 62–63. Raven Rock, Fort Frostmoth, Kolbjorn Barrow, Castle Karstaag,
     Glacial Cave and Frossel stand on it.
   * 46 Skyrim marker places sample a neighbouring hold, because the hold polygons are approximate. Examples:
     Volskygge, Lost Echo Cave, Nightcaller Temple, Mzinchaleft and Robber's Gorge.
   * Shipwrecks in the Sea of Ghosts (the Katariah, the Dainty Sload, Pilgrim's Trench and Hela's Folly) are on
     water on purpose.
7. **The Stormhold anchor (6400, 6300) is in a dry pit.** The map is at y 69.5 but the floor is at y 59, and the
   wetland value there is 0. The S1 settlement should level it.
8. **Lore islands are missing.** The Lady Stone (Lake Ilinalta) and Geirmund's Hall (Lake Honrich) are on islands
   in the game, but the lakes have none. The sites are now nudged to the shore. To fix it, add `islands` to those
   lakes in `tamriel_vectors.json`.

### Tooling and engine

9. **Vanilla `/locate structure` can't find any `skycraft:places` or `skycraft:sites` structure.**
   * Vanilla only searches the random-spread and concentric-ring placements. `quest/Locate` already works around
     this for quests.
   * Operators need a `/skycraft-place locate <id>` command (or use `/skycraft-tamriel` with the coordinates in
     `places/*.json`).
10. **"Tried to load a DUMMY block entity … minecraft:water" at (851, −46, 5857)** under the Imperial City. A
    feature's block entity in a cave was overwritten by aquifer water. It is a one-off warning; vanilla shows it
    occasionally too.
11. **`/forceload add` of 81 fresh chunks stalls the server thread for 3–7 s** ("Can't keep up"). This is vanilla
    behaviour: the command loads its chunks synchronously. Normal exploration is unaffected; TPS stayed at 20.

## Performance

These numbers were measured on a PC shared with 4–6 other agents' servers and clients, so they include contention.

| Measure | Result |
| --- | --- |
| Fresh world to "Done" (spawn prep) | 21–28 s |
| `TamrielMap.sample` (bench) | 150–165 ns in Tamriel, 170–195 ns beyond |
| `getBaseHeight`, single thread, cold random columns | 0.68–0.73 ms per column |
| `getBaseHeight`, parallel heightmap renders | 56–62 µs per column |
| Chunk generation to full, 81 chunks per area (wall time) | 80–122 ms per chunk |

Chunk generation per area, in ms per chunk:

| Area | ms per chunk |
| --- | --- |
| Whiterun | 97 |
| Riverwood | 80 |
| The Throat | 118 |
| Riften | 100 |
| Markarth | 122 |
| Solitude | 83 |
| Winterhold | 102 |
| Solstheim | 120 |
| Imperial City | 81 |
| Vvardenfell | 96 |
| Alik'r | 103 |
| Black Marsh | 111 |
| Elsweyr | 102 |
| Summerset | 104 |
| Atmora | 115 |
| Akavir | 105 |
| Realm (60000, 60000) | 120 |

* There is no performance cliff anywhere: the foreign lands and endless realms cost the same as Skyrim.
* Mean tick time after each load was 3–11 ms at 20 TPS.
* Client: in spectator mode, about 10 render distance takes 20–30 s to fill around a fresh spot.
* Note that `getBaseHeight` is expensive: each random dungeon start samples 5–10 columns.

## Screenshots (`docs/screens/`)

| File | What it shows |
| --- | --- |
| `pt2_01_create_world.jpg` | The New Journey screen. The experimental warning no longer follows it. |
| `pt2_02_spawn_hud.jpg` | Spawn near Helgen, with the HUD, compass and race banner. |
| `pt2_03_map_screen.jpg` | The map screen at spawn (Falkreath Hold), with fog of war and a quest marker. |
| `pt2_04_whiterun_pine_forest.jpg` | Known issue 1: (0, 0) is a pine forest. |
| `pt2_05_riverwood_river.jpg` | The river at Riverwood after the river fix. |
| `pt2_06_high_hrothgar.jpg` | High Hrothgar's steps and the ice-blue Throat massif (known issue 2). |
| `pt2_07_markarth_clouds.jpg` | Reach crags, with a vanilla cloud slab through the terrain (known issue 5). |
| `pt2_08_hjaalmarch_marsh.jpg` | The Hjaalmarch marsh with its sea-level pools filled. |
| `pt2_09_red_mountain.jpg` | Red Mountain: ash, lava and the ash-red sky. |
| `pt2_10_summerset.jpg` | Summerset meadow with blossom trees. |
| `pt2_11_skyrim_heightmap.jpg` | Skyrim heightmap after the river fix. Region borders are drawn darker. |
| `pt2_12_tamriel_heightmap.jpg` | All of Tamriel. Rendered before the river fix, so rivers show dashed. |

## Recommended next fixes

1. Terrain (T):
   * Add hill relief to the Skyrim interior and Hjaalmarch.
   * Give the Throat a summit.
   * Add islands to Lake Ilinalta and Lake Honrich.
   * Consider raising the cloud height together with visuals.
2. Biomes (B):
   * Make Whiterun's surroundings tundra.
   * Bring back the Rift's autumn birches.
   * Use less blue ice on the Throat.
3. S2 or tools: an operator command to locate a named place, because `/locate` can't.
4. Audio: re-check the SFX budget now that there are 26 shouts. Shorter chants or a lower Vorbis quality for the
   new ones would do.
5. QA: re-run this tour after S1, creatures and races merge, with the settlement anchors and spawn checked again.
