# UI coverage: every menu a player can open, and the Skyrim screen it shows

Owner rule: *"Hoppers and non-Skyrim UI, they should ultimately have a Skyrim UI version of them that makes sense."*
So no menu may show the vanilla look by accident. Where a menu has no Skyrim equivalent on purpose, the reason is
written down here and `tools/qa/menu_coverage.py` (run by `tools/qa/data_checks.py`) accepts only that.

Code: `inventory/client/MenuSkins` (which menu gets which screen), `SkyrimContainerScreen` (lists with Take / Store),
`SkyrimMachineScreen` + `MachineProfile` (workstations), `SkyrimLecternScreen` (lectern), server side
`inventory/MachineActions` + `InventoryPackets.MachineAction`.

## How a vanilla menu gets a Skyrim screen

1. The server opens the **unchanged vanilla menu** (recipes, fuel, xp costs, result slots, ownership, theft: all as before).
2. On the client `ScreenEvent.Opening` (in `InventoryClientEvents`) asks `MenuSkins.replace`, which swaps the vanilla
   screen for the Skyrim one, keeping the same menu object. Creative and spectator players keep the vanilla screens.
3. The Skyrim screens read the client's copy of the menu (slots, data slots) every frame. Moves are either vanilla packets
   (button click, trade select, beacon, rename) or `MachineAction` (take, shift-click, place), which the server runs as
   real vanilla menu clicks after validating the menu id, `stillValid`, the menu type and every slot index.
4. Every such screen shows the carry weight (`SkyUi.carryLine`, the client's live sum of slots and pack against the
   server's capacity), lists the player's pack rows next to the slots (`Pack.entries`), keeps the player sneaking
   (`client/SneakKeeper` works for any screen) and uses the container screen's keys: list on the left, details on the
   right, Up/Down (W/S), F or E acts, R all, Space/1/2 tabs, Tab or Esc closes. No slot grids except the crafting
   table's 3x3 (shaped recipes need the shape).

## Menu type map (checked by `tools/qa/menu_coverage.py`)

Status `skyrim` names the screen class; `vanilla-allowed` needs a reason.

<!-- menu-map:begin -->
| menu type | status | screen / reason |
| --- | --- | --- |
| `minecraft:generic_9x1` | skyrim | `SkyrimContainerScreen` |
| `minecraft:generic_9x2` | skyrim | `SkyrimContainerScreen` |
| `minecraft:generic_9x3` | skyrim | `SkyrimContainerScreen` |
| `minecraft:generic_9x4` | skyrim | `SkyrimContainerScreen` |
| `minecraft:generic_9x5` | skyrim | `SkyrimContainerScreen` |
| `minecraft:generic_9x6` | skyrim | `SkyrimContainerScreen` |
| `minecraft:generic_3x3` | skyrim | `SkyrimContainerScreen` |
| `minecraft:hopper` | skyrim | `SkyrimContainerScreen` |
| `minecraft:shulker_box` | skyrim | `SkyrimContainerScreen` |
| `minecraft:furnace` | skyrim | `SkyrimMachineScreen` |
| `minecraft:blast_furnace` | skyrim | `SkyrimMachineScreen` |
| `minecraft:smoker` | skyrim | `SkyrimMachineScreen` |
| `minecraft:brewing_stand` | skyrim | `SkyrimMachineScreen` |
| `minecraft:anvil` | skyrim | `SkyrimMachineScreen` |
| `minecraft:grindstone` | skyrim | `SkyrimMachineScreen` |
| `minecraft:smithing` | skyrim | `SkyrimMachineScreen` |
| `minecraft:cartography_table` | skyrim | `SkyrimMachineScreen` |
| `minecraft:stonecutter` | skyrim | `SkyrimMachineScreen` |
| `minecraft:loom` | skyrim | `SkyrimMachineScreen` |
| `minecraft:enchantment` | skyrim | `SkyrimMachineScreen` |
| `minecraft:beacon` | skyrim | `SkyrimMachineScreen` |
| `minecraft:merchant` | skyrim | `SkyrimMachineScreen` |
| `minecraft:crafting` | skyrim | `SkyrimMachineScreen` |
| `minecraft:lectern` | skyrim | `SkyrimLecternScreen` |
| `nirncraft:smithing_station` | skyrim | `StationScreen` |
| `nirncraft:homestead_table` | skyrim | `HomesteadScreen` |
| `nirncraft:cooking_pot` | skyrim | `CookingScreen` |
<!-- menu-map:end -->

Adding a menu type (a new `MENUS.register`, or a Minecraft upgrade that adds one) without a row fails the check. A new
vanilla type needs a `skin(MenuType.X, Kind.Y)` line in `MenuSkins` too.

## Audit: every menu and screen a player can open

Triggers say what the server does first: the vanilla blocks are intercepted in `crafting/CraftingEvents` (not sneaking,
or sneaking with nothing in either hand, opens the NirnCraft station; sneaking with an item in hand places it as in
vanilla and opens nothing).

### Containers (menus with items inside)

| Menu | Trigger | Current screen | Target Skyrim screen | Status |
| --- | --- | --- | --- | --- |
| Chest, trapped chest, large chest | use the block | `SkyrimContainerScreen` (overflow rows behind the 27/54 slots) | same | DONE (before) |
| Barrel | use the block | `SkyrimContainerScreen` (overflow rows) | same | DONE (before) |
| Ender chest | use the block | `SkyrimContainerScreen` (the player's ender overflow rows) | same | DONE (before) |
| Shulker box | use the block, or placed | `SkyrimContainerScreen` (its slots only: the items travel with the item) | same | DONE (this change; was vanilla `ShulkerBoxScreen`) |
| Hopper | use the block | `SkyrimContainerScreen` (its 5 slots only) | same | DONE (this change; was vanilla `HopperScreen`) |
| Dispenser, dropper | use the block | `SkyrimContainerScreen` (its 9 slots only) | same | DONE (this change; was vanilla `DispenserScreen`) |
| Corpses (creature, NPC, player) | [F] / use the body | `SkyrimContainerScreen` (overflow rows) | same | DONE (before) |
| Ship cargo hold | use the ship | `SkyrimContainerScreen` (overflow rows) | same | DONE (before) |
| Saddlebags (your mount) | sneak-use own mount, key 3 = stat sheet | `SkyrimContainerScreen` (gear rows, weight) | same | DONE (before; owned by the mounts stream) |
| Followers' trade inventory | follower menu | `SkyrimContainerScreen` | same | DONE (before; owned by the followers stream) |
| Jail evidence chest, Lootr / lockboxes | use the block | `SkyrimContainerScreen` (chest menus) | same | DONE (before) |
| Vanilla horse inventory | opened by vanilla on mount-use | cancelled on the client (`MountHudOverlay`) and closed on the server (`MountEvents`) | the saddlebag container screen above | DONE (before) |

### Workstations (vanilla menus re-skinned by `SkyrimMachineScreen`)

The Skyrim stations still come first: the right-click goes to them (see the trigger column); the vanilla menu is
reached by sneaking with empty hands, and that menu now looks like the other Skyrim menus.

| Menu | Trigger | Current screen | Target Skyrim screen | Status |
| --- | --- | --- | --- | --- |
| Furnace, blast furnace | use: NirnCraft smelter station (`StationScreen`); sneak + empty hands: vanilla menu | vanilla `FurnaceScreen` / `BlastFurnaceScreen` | `SkyrimMachineScreen`: Ingredient / Fuel / Output rows, fuel and progress meters, "My Items" tab shows what the furnace accepts first | DONE (needs playtest) |
| Smoker | use: NirnCraft cooking pot (`CookingScreen`); sneak + empty hands: vanilla menu | vanilla `SmokerScreen` | `SkyrimMachineScreen` (same as furnace, smoking recipes) | DONE (needs playtest) |
| Campfire, soul campfire | empty hand: NirnCraft cooking pot; item in hand: vanilla (cooking on the fire) | `CookingScreen` (no menu) | n/a: no container menu | DONE (before) |
| Brewing stand | use (also sneaking): NirnCraft Alchemy Lab (`AlchemyScreen`); the vanilla menu is unreachable by right-click | vanilla `BrewingStandScreen` | `SkyrimMachineScreen`: three Bottle rows, Ingredient, Blaze Powder, brewing and blaze meters | DONE (only reachable by other mods or commands) |
| Anvil (all three damages) | use: NirnCraft forge (`StationScreen`); sneak + empty hands: vanilla menu | vanilla `AnvilScreen` | `SkyrimMachineScreen`: Item / Material / Result, level cost line (red when you cannot pay or "Too expensive!"), N renames | DONE (needs playtest) |
| Grindstone | use: NirnCraft grindstone tempering (`StationScreen`); sneak + empty hands: vanilla menu | vanilla `GrindstoneScreen` | `SkyrimMachineScreen`: Item / Second Item / Result | DONE (needs playtest) |
| Smithing table | use: NirnCraft armor workbench (`StationScreen`); sneak + empty hands: vanilla menu | vanilla `SmithingScreen` | `SkyrimMachineScreen`: Template / Base Item / Addition / Result | DONE (needs playtest) |
| Crafting table | use: NirnCraft armor workbench (`StationScreen`); sneak + empty hands: vanilla 3x3 | vanilla `CraftingScreen` (recipe book) | `SkyrimMachineScreen`: a 3x3 grid plus result (arrow keys move, F fills a cell from My Items one item at a time, F on a filled cell takes it out). No recipe book | DONE (recipe book intentionally absent: recipes are in the Skyrim stations) |
| Stonecutter | use the block | vanilla `StonecutterScreen` | `SkyrimMachineScreen`: Stone / Result rows and a "Cuts" list of the recipes for the stone in the input | DONE (needs playtest) |
| Loom | use the block | vanilla `LoomScreen` | `SkyrimMachineScreen`: Banner / Dye / Pattern Item / Result rows and a "Patterns" list | DONE (needs playtest) |
| Cartography table | use: opens the world map; sneak: vanilla menu | vanilla `CartographyTableScreen` | `SkyrimMachineScreen`: Map / Paper, Compass or Glass / Result | DONE (needs playtest) |
| Enchanting table | use (also sneaking): NirnCraft Arcane Enchanter (`ArcaneEnchanterScreen`); the vanilla menu is unreachable by right-click | vanilla `EnchantmentScreen` | `SkyrimMachineScreen`: Item / Lapis rows and the three offers (clue, level, lapis) | DONE (only reachable by other mods or commands) |
| Beacon | use the block | vanilla `BeaconScreen` | `SkyrimMachineScreen`: Payment row and a "Powers" list (primary, secondary, Confirm sends the vanilla beacon packet) | DONE (needs playtest) |
| Villager / wandering trader trade | [F] / use: NirnCraft dialogue then barter (`BarterScreen`); vanilla trade only for an NPC tagged `no_dialogue` | vanilla `MerchantScreen` | `SkyrimMachineScreen`: Payment rows, Result, a "Trades" list with cost and stock (the barter screen is the Skyrim one) | DONE (fallback only) |
| Lectern with a book | use: the lore module's reading screen for the world's story lecterns; placed lecterns open the vanilla menu | vanilla `LecternScreen` | `SkyrimLecternScreen` (`BookScreen` parchment, [T] takes the book) | DONE (needs playtest) |
| Jukebox, composter, bell, respawn anchor, bed | use the block | no menu | n/a | DONE (no menu) |

### NirnCraft's own menus and screens

| Menu / screen | Trigger | Current screen | Target Skyrim screen | Status |
| --- | --- | --- | --- | --- |
| Inventory | Tab > Items, inventory key | `SkyrimInventoryScreen` (vanilla `InventoryScreen` replaced by `HubScreen`; reachable by the "Crafting" button) | same | DONE (before) |
| Hub (four-way menu) | Tab | `HubScreen` | same | DONE (before) |
| Favorites quick menu | favorites key | `FavoritesScreen` | same | DONE (before) |
| Character / status | hub | `CharacterStatusScreen` | same | DONE (before) |
| Skills and perks | K, level-up | `SkillsScreen` (constellations) | same | DONE (before) |
| Magic menu | P | `MagicMenuScreen` | same | DONE (before) |
| Spellmaking altar | use the altar | `SpellmakingScreen` | same | DONE (before) |
| Staff enchanter | use the block | `StaffEnchanterScreen` | same | DONE (before) |
| Journal (quests, factions, stats, party, collections) | Q | `JournalScreen` | same | DONE (before) |
| Reputation | journal / factions | `ReputationScreen` | same | DONE (before) |
| Party, party travel | party key, invitation | `PartyScreen`, `PartyTravelScreen` | same | DONE (before) |
| Map | M, cartography table | `MapScreen` | same | DONE (before) |
| Wait / sleep | I, bed | `RestScreen` | same | DONE (before) |
| Dialogue | [F] / use an NPC | `DialogueScreen` | same | DONE (before) |
| Barter | dialogue "Let's trade" | `BarterScreen` | same | DONE (before; carry weight added) |
| Pickpocket | sneak-use an NPC | `PickpocketScreen` | same | DONE (before) |
| Lockpicking | use a locked door or chest | `LockpickScreen` | same | DONE (before) |
| Forge, smelter, tanning rack, grindstone, armor workbench | use the vanilla block (`smithing_station` menu) | `StationScreen` | same | DONE (before; carry weight added) |
| Cooking pot | use a smoker / campfire (`cooking_pot` menu) | `CookingScreen` | same | DONE (before; carry weight added) |
| Alchemy Lab | use a brewing stand / Alchemy Lab block | `AlchemyScreen` | same | DONE (before; carry weight added) |
| Arcane Enchanter | use an enchanting table / Enchanter block | `ArcaneEnchanterScreen` | same | DONE (before; carry weight added) |
| Homestead drafting table | use the table (`homestead_table` menu) | `HomesteadScreen` | same | DONE (before; carry weight added) |
| Shipwright | use the shipwright bench | `ShipwrightScreen` | same | DONE (before) |
| Stable master | dialogue "Show me your horses" | `StableScreen` | same | DONE (before) |
| Werewolf / Vampire Lord perks and forms | skills, form key | `CursePerkScreen`, `CurseFormScreen` | same | DONE (before) |
| Lore books, letters, notes | read from inventory, world lecterns and signs | `BookScreen` | same | DONE (before) |
| Traveler's handbook | handbook item | `HandbookScreen` | same | DONE (before) |
| Treasure map | use the map item | `TreasureMapScreen` | same | DONE (before) |
| Race / character creation | new game | `RaceScreen` | same | DONE (before) |
| Title, load, new game, options, pause, loading, death, confirm | game flow | `SkyTitleScreen`, `SkyLoadWorldScreen`, `SkyCreateWorldScreen`, `SkyOptionsScreen`, `SkyPauseScreen`, `SkyLoadingScreen`, death and confirm restyled by `ScreenSkins` | same | DONE (before) |

### Vanilla screens that are not menus

| Screen | Trigger | Current screen | Status |
| --- | --- | --- | --- |
| Creative inventory, creative-mode chests and machines | creative players | vanilla (`MenuSkins` leaves creative and spectator alone) | vanilla-allowed: operator / testing tool, not part of the survival game |
| Book and Quill editor | use a writable book | vanilla `BookEditScreen` | vanilla-allowed: player-written text; the lectern and every NirnCraft book already read on the parchment |
| Sign editing | place or use a sign | vanilla `SignEditScreen` | vanilla-allowed: a text field, nothing to list |
| Command block, structure block, jigsaw | operator use | vanilla editors | vanilla-allowed: operator tools |
| Chat, advancements, stats | keys | vanilla | vanilla-allowed: not part of the Skyrim game (advancements and stats are replaced by the journal and the hub) |
| Options (video, controls, resource packs) | pause > Settings | vanilla `OptionsScreen` below `SkyOptionsScreen` | vanilla-allowed: keeps shader and mod settings reachable |

## Not covered by a skin (limits to know)

* **Crafting table recipe book**, furnace recipe book: not shown. The Skyrim stations (non-sneak use) list every recipe.
* **Trades**: the trade auto-fill (`tryMoveItems`) of vanilla looks only at the 36 slots, not the pack; use the My Items
  tab (Add) to put pack items into the payment rows.
* **Results with all 36 slots full** go to the pack (`MachineActions` takes them with `addToBags`), not onto the floor.
* **Third-party menus** (other mods' machines and containers): a chest-shaped menu gets the container screen; anything
  else keeps its own look. `MenuSkins` decides by menu type.
