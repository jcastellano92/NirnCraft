# Homesteads: audit, rules and backlog

Module `com.skycraft.homestead` (claims, drafting table, plans, builder, build mode, access, map entries), with
`com.skycraft.protect` (break/place/griefing), `com.skycraft.dig` (perk rules) and `world.Discovery`/`FastTravelHandler`
(the map, compass and fast travel it feeds). Paths below are under `mod/src/main/java/com/skycraft/`.
Status key: **yes** = existed before this pass, **NEW** = added in this pass, **partial** = works with a stated gap.

## Owner requirements to code

| # | Requirement | Exists? | Where | Gap and what was done |
| --- | --- | --- | --- | --- |
| a1 | A house person in every major city/large town | partial | `homestead/HomesteadBuilders.java:57` (NEW), `homestead/HomesteadDialogue.java:49` (NEW), steward `tamriel/settlement/SettlementPopulation.java:340` | The steward (palace) exists only where a leader's seat was built: the town audit finds **no seat in 32 of 58 cities, 62 of 106 towns, 2 of 14 capitals** (`docs/world/TOWN-AUDIT.md` F05). NEW: a builder (mason trade, tag `skycraft_builder`) is spawned at the market square of every recorded capital, city and large town (tier <= 1) the first time a player comes near and none exists; the steward of such a settlement also counts. The shop *building* and a `BUILDER` anchor belong to the settlement kit: `docs/world/BUILDING-KITS.md`. |
| a2 | Sells the founding item, deeds, upgrades, expansions with a UI | partial | founding item: `homestead/HomesteadDialogue.java:85` (NEW); deeds/houses: `economy/Houses.java` + `economy/EconomyDialogue.java:76` (steward and Jarl); upgrades, materials, expansion: drafting table UI `homestead/client/HomesteadScreen.java` | The builder sells the drafting table (gold, `draftingTablePrice`), points out open plots (marks them on the map as heard-of) and sells sawn logs, stone, clay and nails. Building plans, upgrades and the grounds' expansion are made at the table itself (they need the site). Deeds for the Hearthfire plots and the city houses stay with steward/Jarl. |
| a3 | A specialty block to claim land | yes | `homestead/DraftingTableBlock.java`, recipe `data/skycraft/recipes/drafting_table.json` | Placing the table founds the claim (`HomesteadEvents.java:31`). |
| b1 | No claim near another homestead | yes, config NEW | `homestead/HomesteadData.java:68` (`checkCanClaim`, spacing `claimSpacing` 64, grows with an expanded neighbour) | Now configurable. |
| b2 | No claim near a settlement or dungeon | yes, config NEW | `HomesteadData.java:68-110` (`claimSettlementDistance` 96, `claimDungeonDistance` 64) | Now configurable. |
| b3 | No claim within the radius of a named place of the map | **NEW** | `HomesteadData.java:146` `checkLand` | Every non-homestead `Place` (standing stones, forts, shrines, ruins, farms, dungeons...) within its own radius + `claimPlaceClearance` (48) refuses, message names the place. About 79 % of Skyrim's area stays free (measured on the places data). The open `homestead`-variant plots are exempt. |
| b4 | Terrain/zone rules | **NEW** | `HomesteadData.java:146` | Not on the sea or in a dimension region; nine sample columns under the grounds must not be a settlement, structure or site footprint (`protect/Footprints`), the table not on a road. Overworld only (was). |
| c1 | Upgrade tree functions, spawns real things | yes | `homestead/HomesteadPlans.java`, `homestead/HomesteadBuilder.java` | House, hall, 3 wings x 3 rooms, cellar, garden, pen, workshop: all place real stations (cooking pot, alchemy lab, grindstone, smelter, anvil, enchanter), beds (registered on the claim), crops and animals. Costs are Hearthfire materials with the Woodcutting perk discount. |
| c2 | Expansions | **NEW** | `Hearthfire.java:146` `expand`, `HomesteadData.java:185` `checkExpansion` | Grounds widen by 8 blocks (28 -> 36 -> 44, `claimMaxRadius`); gold doubles each step (`expandGold`) plus sawn logs, stone and nails; refused into a neighbour's grounds or the world's places. |
| c3 | Crafting the home's items | yes | `Hearthfire.java` `MATERIALS`; recipes for drafting table, forge, smelter, grindstone, workbench, alchemy lab, enchanter, cooking pot in `data/skycraft/recipes/` | Nails, hinges, locks, fittings, sawn logs, stone, glass at the table. |
| c4 | Place every crafting block | **NEW** | `homestead/HomesteadBlocks.java` | The old whitelist refused blast furnaces, smokers, looms, stonecutters, brewing stands, enchanting tables, lecterns and **all gardening** (dirt, farmland, seeds, saplings). Now a deny-list: TNT, spawners, command/structure/jigsaw blocks, bedrock, barriers, portals and frames, obsidian, respawn anchors, light blocks, fire, lava. |
| d1 | Build mode toggle | **NEW** | `homestead/HomesteadBuildMode.java:69`; button on the Homestead tab; sneak + use the table with empty hands (`HomesteadEvents.java:148`) | Owner only, inside the grounds. HUD action-bar reminder while on. |
| d2 | Pick up and re-place stations, containers | **NEW** | `HomesteadBuildMode.java:170` `pickUp`, `HomesteadEvents.java:135` | Left click in build mode takes up every Skycraft station, vanilla crafting block, furnace, brewing stand, chest, barrel, shulker box, any container block entity and lectern into the bags. Container contents travel inside the item (`BlockEntityTag`, restored by ordinary placement); nothing spills. A forge could not be broken by hand before (requires a tool, no drop): fixed by this. Outside build mode these blocks cannot be broken at all (hint). Beds and doors come apart as usual. |
| d3 | Build mode off on leaving / logout / death / dimension | **NEW** | `HomesteadBuildMode.java:99-135` | Tick check each second, logout, death, respawn, dimension change, server stop. Not saved. |
| e1 | Access levels owner / party / everyone for doors, containers, stations, beds | partial -> **NEW** | `homestead/HomesteadClaim.java:225` `mayUse`, `HomesteadEvents.java:148`, `homestead/HomesteadAccess.java` | Doors had 3 levels, containers 2, stations and beds none, and only chests, barrels, shulkers, hoppers and dispensers counted (furnaces, brewing stands, workbenches, cooking pot, enchanter, alchemy lab, beds were open). Now four kinds with three levels each plus building (owner/party), enforced server-side on right click. |
| e2 | Per door/container/station/bed control | **NEW** | `HomesteadClaim.java:237` `cycleOverride`, `HomesteadEvents.java:170` | In build mode, sneak + use with empty hands cycles that block: claim default, owner, party, everyone. Double chests, two-block doors and beds count as one. Persisted in the claim. |
| e3 | UI | **NEW** | `HomesteadScreen.java` Permissions and Homestead tabs | Five rows (doors, containers, workstations, beds, building) and the hint. |
| f1 | Owner-only break/place, perks and mobs, TNT, fire | yes | `protect/Protect.java:170,210`, `dig/DiggingRules.java:58`, `protect/ProtectEvents.java` (explosions, fire, mob griefing, pistons, buckets, decor) | A perk holder who is not the builder is refused by both `Protect` and `DiggingRules` (a stranger can never `canBuild`). Explosions change no block, fire goes out, mobs do no block work. |
| f2 | Pistons across the border | partial -> **NEW** | `ProtectEvents.java:290` | Only pistons inside a claim were limited; a piston outside could push or pull a claim's blocks. Now any piston whose move touches a claim it is not in is cancelled. |
| f3 | Guests use, not break | yes | `HomesteadClaim.canBuild` vs `mayUse` | Use levels and build permission are separate. |
| g1 | Hidden at first; a compass point only | **NEW** | `homestead/HomesteadDiscovery.java:64`, `world/client/MapScreen.java` (skips `hidden`) | A stranger within `homesteadHintRange` (220) gets a nameless `known`+`hidden` entry in the world module's discovered list: compass icon only (the same as undiscovered places), nothing on the map, not travellable. |
| g2 | Discover by approach with the owner's name | **NEW** | `HomesteadDiscovery.java:64,152` | Inside the grounds + 6 blocks: the usual "DISCOVERED" banner with the name, sting and Athletics XP; from then on a normal map and compass entry. |
| g3 | Owner sees it, can rename; renames propagate | **NEW** | `Hearthfire.java:190` `rename`, `HomesteadClaim.sanitizeName`, Homestead tab | Name box (32 characters, letters, digits and a little punctuation). Entries of everyone who knows it are updated at their next check, removed when the claim is dismantled. |
| h | Fast travel to it | **NEW** | entry carries `ax/ay/az` = in front of the house door (or behind the table): `HomesteadDiscovery.java:160` | The map's click-to-travel and `FastTravelHandler` need no change: the owner always, others once discovered. Normal travel rules apply (combat, encumbrance, jail). |
| i1 | Persistence | yes | `HomesteadData` (overworld `SavedData` `skycraft_homesteads`) | New claim fields saved with defaults for old saves (`HomesteadClaim.save/load`). Build mode is deliberately not saved. |
| i2 | Multiplayer / dedicated server | yes | all packets validated (`HomesteadPackets.ownedClaim`: open menu, reach, owner; values clamped; name sanitised server side) | No client class reaches common code. |

## Rules in one page

* **Found:** place a drafting table on open overworld land: not in or near a settlement (96), dungeon (64), another
  homestead (64, more if it was expanded), named place of the map (radius + 48), settlement/structure/site footprint, the
  sea. One homestead per player. The three Hearthfire plots and generated open plots are the exception for footprints.
* **Build and break:** only the owner (and the party when building is set to "party"). Depth 10 below and 24 above the
  table. Nothing destroys blocks (explosions, fire, mobs, pistons from outside).
* **Use:** per kind (door, container, workstation, bed) owner / party / everyone, with per-block overrides.
* **Build mode:** carry stations and containers; ends on leaving.
* **Map:** the owner always; strangers get a compass point, then discover it by approaching.

## Config (`config/skycraft-survival.toml`, section `homesteads`)

`plotPrice`, `stewardPrice`, `claimSettlementDistance` 96, `claimDungeonDistance` 64, `claimPlaceClearance` 48,
`claimSpacing` 64, `draftingTablePrice` 250, `expandGold` 1500, `claimMaxRadius` 44, `homesteadHintRange` 220,
`homesteadDiscoverMargin` 6.

## Verification

* `./gradlew build -x test` and `py -3 -I tools/qa/data_checks.py`: green.
* `/skycraft-homestead selftest` (also autorun task `homestead`): land rules, spacing, every access kind and the
  override cycle, a stranger's break and place refused, the deny-list, build mode carrying a chest with its contents (and
  nothing spilling, refusing outside build mode and for a stranger), build mode ending when the owner leaves, hidden
  point / discovery / rename / removal on a second player's list, expansion refused into a neighbour. **Written but not
  run: no server was booted in this pass.**
* `/skycraft-homestead list`; `/skycraft-protect check` for any block.

## Playtest checklist (owner)

1. In Whiterun, find the builder at the market bell: buy a drafting table, ask where open land is (compass points).
2. Try to place the table next to a standing stone, a fort, a road, a town gate: refused with the place named.
3. Found a homestead, build the small house and the workshop. Open the Homestead tab: name it, expand the grounds.
4. Build mode: left click the forge, a chest with items, the alchemy lab; set them down elsewhere; contents intact.
   Walk out of the grounds: build mode ends. Break a chest outside build mode: refused.
5. Permissions: set doors to "Anyone" and containers to "Only you"; second player uses the door, not the chest. In build
   mode sneak-use one door to give it its own level.
6. Second player with Excavator/Stonebreaker perks tries to dig and place: refused everywhere in the claim; TNT and a
   creeper change nothing; a piston next to the border does not push in.
7. Second player walks by at 200 blocks: a nameless point on the compass, no map pin. Walks up: "Discovered: <name>", on
   the map, click to fast travel. Rename it: the name changes for them. Dismantle: it disappears.

## Backlog

* Builder's shop building and a `BUILDER` anchor/trade: `docs/world/BUILDING-KITS.md` (settlement kit, TO2). The runtime
  spawner is the interim; remove it once the anchor spawns a builder everywhere.
* Animals in the pen: other players can still hit them (decor entities are protected, livestock is not).
* Hoppers/droppers outside a claim can still pull from a chest inside it (only pistons were closed).
* Party members cannot toggle build mode (owner only); the table menu is owner only.
* Fluids flowing into a claim from outside are not blocked (only lava/water mixing is).
* Overrides for stale blocks are never pruned automatically (`HomesteadClaim.pruneOverrides` exists, unused).
* Several homesteads per player (the API `Homesteads.residencesOf` returns a list; the data holds one).
* Not verified in game: UI layout of the new tab at small GUI scales, the lectern pick-up (book pops out), double chest
  pick-up, the builder's spawn spot in odd city layouts.
