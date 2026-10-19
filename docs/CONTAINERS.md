# Loot containers: NirnCraft's own, replacing Lootr

NirnCraft's loot containers give every player their own loot, look like the people who built them, open with model
animations the far-field renderer can see, use our inventory screen, roll our tables at the player's level, restock
like Skyrim, and store unlimited things for a player at home while staying balanced in the world. They replace the
third-party Lootr mod; until the owner removes Lootr from the pack, both coexist behind one config flag.

* Code: `com.nirncraft.loot.container`.
  * `ContainerKind`, `ContainerStyle`, `LidState`: what a container is, who built it, where its lid is.
  * `LootContainerBlock` (and `Styled`, `Barrel`, `Bookshelf`), `LootContainerBlockEntity`: the blocks.
  * `LootInstance`: one player's copy. `LootWindow`: that copy open on screen.
  * `LootContainers`: placement, conversion and the questions other modules ask. `LootrCompat`: Lootr's ids.
  * `ContainersConfig`, `ContainerRegistry`, `ContainerCommands`, `ContainerSelfTest`.
* Art and models: `tools/textures/containers.py` (procedural, original). Shelf tables: `tools/loot_data.py`
  (`bookshelves()`).
* The loot itself (tables, levels, tiers, gold, collectibles) is `docs/LOOT.md`; this file is the container around it.

## 1. What a container must do

| need | how |
| --- | --- |
| Multiplayer: every player finds their own loot | one copy per player in the block entity (section 4) |
| Skyrim looks per region and culture | a `style` blockstate property, chosen at placement (section 3) |
| Animations without a block-entity renderer | a `lid` blockstate property, one block model per value (section 3) |
| Our UI | an ordinary chest menu over the copy; the Skyrim container screen skins it (section 6) |
| Bodies, shelves, urns, sacks, hoards | one block per kind (section 2) |
| Loot that matches the world | the `nirncraft:containers/*` tables at the opener's loot level (section 13) |
| Unlimited storage, balanced | bulk store behind every copy; a stash allowance in world containers only (section 7) |
| Restock like Skyrim | per player, per table, never over the player's things (section 5) |
| Locks and crime | the existing `crime.Locks` and `crime.Theft` see our containers (section 8) |
| Keep old worlds | registry aliases and on-load conversion (section 11) |

## 2. Kinds

Each kind is one block (`nirncraft:<id>`) with its own models and block entity type `nirncraft:loot_container`.

| kind | id | styled | where | default table (`/nirncraft-containers place`) | stash |
| --- | --- | --- | --- | --- | --- |
| chest | `loot_chest` | yes | dungeon chests, homes, shops, camps | `dungeon/standard` | 150 kg |
| hoard chest | `grand_chest` | yes | boss chests, dragon lairs, uniques, Jarls' chests | `dungeon/boss` | 250 kg |
| strongbox | `strongbox` | no | `strongbox/<lock>` | `strongbox/adept` | 20 kg |
| barrel | `loot_barrel` | no | stores, dungeons, wrecks; six-way facing | `misc/barrel` | 100 kg |
| sack | `loot_sack` | no | satchels, knapsacks, coin purses | `misc/satchel` | 40 kg |
| urn | `loot_urn` | yes | small dungeon containers in tombs built by Nords, Velothi, Dwemer, Ayleids | `misc/urn` | 25 kg |
| bookshelf | `loot_bookshelf` | yes | homes (`household_books/*`), ruins, scholars, mages (`bookshelf/*`) | `bookshelf/scholar` | 30 kg of books |
| remains | `remains` | no | an old skeleton on a rotted cloak; carries a `corpse/*` table | `corpse/bandit` | 15 kg |

* **Kind at placement** (`ContainerKind.forPlacement`): the table decides first (boss, dragon lair, unique or Jarl:
  hoard; `strongbox/`; satchel, knapsack, coin purse: sack; `misc/urn`; shelves; `corpse/`: remains). Otherwise the
  block the builder placed: a barrel stays a barrel, except that `dungeon/minor` barrels become urns in a tomb whose
  builders made urns. Everything else is a chest.
* **Bodies.** Creatures killed in play keep becoming `creatures.entity.CorpseEntity` bodies (an entity, with its pose
  and the death fall of `loot.CorpseEvents`): they are per kill, not per place. `remains` is the static body a
  builder places in a ruin or on a battlefield: a block model, so it shows at any distance, and per player like
  every other world container.
* **Merchant stock.** A merchant's barter inventory stays in the economy module (it is the merchant's, not a
  container). Shops' back rooms are chests with `shop/*` tables: owned, so taking from them is theft (section 8).
  Phase 2 ties a shop chest to its merchant (the merchant restocks it, a robbed merchant notices).
* **Sarcophagi, wardrobes, dressers** are not kinds yet: sarcophagi use chests in the Nordic style, the settlement
  furniture containers (`tamriel.settlement.furniture`) still use their block-entity renderer. Both are phase 2.

## 3. Look: models, styles, lids

* **Only block models.** Every container renders with `RenderShape.MODEL` from JSON models
  (`assets/nirncraft/models/block/container/`); there is no block-entity renderer anywhere. The far-field renderer,
  which reads block models, sees every container exactly as the near renderer does.
* **Lids are model swaps.** The `lid` property (`closed`, `ajar`, `open`) has one model per value: the chest lid,
  latch and all, hinged at the back top edge, tilted 45 degrees when ajar and standing upright behind the box when
  open (element rotations stay within the loader's 22.5-degree steps, `tools/qa/asset_audit.py`); the urn's lid
  tilts and then is gone; the sack's neck is tied, loosened, open with its contents showing; the barrel's top shows
  the lid shut, lifted, off. Opening steps closed, ajar, open two ticks apart; the last viewer leaving steps back
  (`LootContainerBlockEntity#tickLid`, block scheduled ticks). The lid is shared: a player sees it open when anyone
  has the container open, as with vanilla chests.
* **Shelves** have `books` (0 to 3) instead of a lid. A world shelf shows how full its table makes it (a poor home's
  sparse, a scholar's full); a player's own shelf shows how many books are on it.
* **Styles** (`style` on chests, hoards, urns, shelves): `nordic` (dark pine, black iron), `imperial` (red oak, brass),
  `velothi` (resin and chitin, bone trim), `dwemer` (bronze plate, rivets), `elven` (pale wood, gilt), `desert`
  (sun-bleached wood, copper), `rustic` (rough planks). Chosen at placement: the table's theme first (a Dwemer ruin's
  chest is Dwemer in any province: `ContainerStyle.ofTable`), else the culture of the region at the position
  (`tamriel.Regions`, `ContainerStyle.ofCulture`): Nord, Atmoran, Skaal, giant: nordic; Imperial, Breton, Akaviri:
  imperial; Dunmer, Ashlander, Daedric: velothi; Dwemer; Altmer, Maormer, Ayleid: elven; Redguard, Yokudan, Khajiit:
  desert; everyone else rustic.
* **Art.** `tools/textures/containers.py` paints every texture procedurally (seeded, idempotent) and writes the models,
  blockstates, item models, block drops and names. Original work: no game textures or meshes. The art is placeholder
  quality; the model structure (one model per style and lid) is final, so better art is a texture swap.

## 4. Per-player copies

* **Where.** In the block entity (`LootContainerBlockEntity`), saved with the chunk: `LootTable` (the vanilla and Lootr
  key, so the QA tools and a Lootr migration read it), `WorldLoot`, and `Instances`, a list of copies keyed by the
  player's UUID. A copy (`LootInstance`) is a `BulkStore` (no slots, no stack limit) plus `rolled` (loot clock of the
  last roll), `kg` (weight of that roll) and `gen` (item id and count of that roll).
* **When a copy exists.** Only once its player opens the container: the table is rolled then, at that player's loot
  level (`LootApi.roll` with the player as `THIS_ENTITY`, so `LootLevels`, the region's band, the place collectibles
  and the once-per-character uniques all apply per player). A container nobody opened costs a table id.
* **Who shares.** World loot (`WorldLoot`: placed by world generation with a table) is per player. Player-placed
  containers, and every world container with `perPlayer = false`, have one shared copy (`SHARED`, the nil UUID);
  two players looking into a shared copy see one window, as with a vanilla chest.
* **Sync.** A copy never leaves the server: the block entity's update tag is empty, and the client only knows the
  block state (kind, style, lid). While a player has their copy open, the existing `inventory.ContainerSync` sends
  them that copy's rows (chunked, versioned); nothing else of any copy is ever sent to anyone. This works the same on
  an integrated server and a dedicated one.
* **Size.** A copy stores only rows (item with count 1 and an int count). A typical rolled dungeon chest is a few
  hundred bytes per player; the chunk's block entity grows with the players who opened it. Phase 2 can move copies to
  per-region saved data if a big server needs it; the format is the same.

## 5. Restock

* **Per player, per table** (`LootInstance#dueForRoll`, `Containers.restockDays`): dungeon and wilderness containers
  30 days, households and shops 10, boss hoards, dragon lairs, word walls, Daedric shrines and placed uniques never.
  `restockScale` multiplies the times.
* **The clock** is the loot clock (`LootContainerBlockEntity.clock`): game time, but sleeping and waiting, which move
  the day, count too (the same rule as ore regrowth).
* **Never over the player's things.** A copy that holds anything its last roll did not generate (another kind, or more
  of a kind) is not restocked. This is the same rule `loot.Restock` applies to vanilla chests.
* **Lootr parity.** Lootr refreshed the same tables after 720,000 ticks (`pack/config/lootr-common.toml`); ours is
  per player and counts waiting.

## 6. The UI path

* Using the block (or picking its lock, or sneaking with something in hand: `crime.Locks` opens any `MenuProvider`)
  opens an ordinary `GENERIC_9x3` chest menu over a `LootWindow`: the copy's first rows in 27 slots, the rest as the
  window's overflow (`inventory.OverflowHost`).
* `inventory.client.MenuSkins` replaces every chest menu's screen with `SkyrimContainerScreen`: one list of everything
  inside with Take / Store, sorted and categorised like the inventory. Nothing in the screen is container-specific.
  Creative players keep the vanilla screen and see the slots.
* The title is the kind in its style ("Dwemer Chest", "Velothi Urn": `container.nirncraft.<id>.<style>` in
  `lang/nirncraft/loot.json`).
* **Storing** never puts an item in a slot: the menu is a `LootMenu` whose container slots are take-only (a menu slot
  never asks the container's `canPlaceItem`), and its shift-click stores through the overflow; every store goes
  through the window's room rule
  (section 7), so the balance rule and the shelf's books-only rule hold exactly. On close the slots fold back into the
  copy; a save while the window is open writes the slots as rows, so a crash loses nothing.
* **HUD.** `crime.client.CrimeHud` shows "[F] Open" / "[F] Steal" over our containers and asks the server whether they
  are locked or owned (`crime.client.CrimeClientEvents`).

## 7. Storage and balance

* **Player storage is unlimited.** A container a player placed has no limit beyond the bulk store's engineering caps
  (2,000 kinds, a million of each), like every NirnCraft container, the pack and the ender chest.
* **World containers take a stash allowance** on top of what their roll generated (`ContainerKind.stashKg`, scaled by
  `stashScale`; `limitWorldStash = false` turns it off): chest 150 kg, hoard 250, barrel 100, sack 40, urn 25,
  shelf 30 (books only), strongbox 20, remains 15. Weightless things are limited to 32 kinds beyond the roll's.
  Taking something out and putting it back always works: the allowance counts from the roll's weight.
* **Why.** With per-player copies every barrel in the world would otherwise be a private, infinite, restock-proof
  bank next to every dungeon. Skyrim's answer was that non-owned containers respawn and eat what you left; ours never
  deletes a player's things (section 5), so it limits how much a world container takes instead. Home storage stays
  unlimited, which keeps houses (economy.Houses), homesteads and their chests the place to keep a hoard, and carry
  weight (inventory.CarryWeight) the limit on what a player hauls.

## 8. Locks and crime

* **Locks** (`crime.Locks`): a world loot container of ours is a lock candidate (`isCandidate` asks
  `LootContainers.isWorldLoot`), decided once per position like any loot chest; each player picks it once, and the
  lock bonus (`containers/lock_bonus/*`) is rolled into that player's bags, so locks were already per player.
* **Ownership and theft** (`crime.Theft`, `crime.Ownership`): `Theft.lootTable` asks `LootContainers.lootTableOf`, which
  answers for ours always (a vanilla chest only until it is opened). Household and shop tables are owned
  (`Containers.isOwned`), so taking from a home's chest is theft with witnesses and bounty, every time, not only on
  the first open. Dungeon, camp and site tables are finders keepers. Player-placed containers belong to their builder.
* **Shelves**: `household_books/*` contain `containers/household`, so a home's books are owned; `bookshelf/*` (ruins,
  scholars, mages) are free.

## 9. Placement: world generation and structures

* **Builders do not change.** They place a vanilla chest or barrel and give it a table through
  `worldgen.WorldgenData.setLootTable` or `LootContainers.setLootTable` (settlements' `Canvas.container`, wrecks,
  flotsam, docks, word walls, sea sites, explorers' sites, dungeons, camps and landmarks all route there). When
  NirnCraft runs containers (section 10) the block is replaced on the spot by ours: kind and style as in sections 2
  and 3, facing kept, the table set, written only at that position (safe in world generation: a proto chunk's old
  block entity is removed first). Otherwise the vanilla table is set as before.
* **A builder that wants a kind** calls `LootContainers.place(level, pos, kind, facing, table, style)`; style null
  picks it from the table and region.
* **Everything else** (a vanilla structure, a builder not routed) is caught when its chunk first loads
  (`convertNewChunks`): unopened vanilla chests, trapped chests and barrels with a table (only NirnCraft's tables unless
  `convertVanillaTables`) are queued and converted on the server thread, 64 a tick. Converting drops the old
  container's table first and skips its removal side effects, so nothing is generated, spilled or hooked.
* **Fixed-content chests** (a dungeon's claw in its owner's pack, the jail's evidence chest, quest props) have no table
  and stay vanilla and shared. Chest minecarts (`Painter`, `Trailside`) are entities and stay vanilla: phase 2.

## 10. Config and coexistence with Lootr

`config/nirncraft-containers-common.toml`:

| key | default | meaning |
| --- | --- | --- |
| `containers.system` | `AUTO` | `AUTO`: Lootr while it is installed, ours once it is gone. `NIRNCRAFT`: ours even with Lootr installed. `LOOTR` / `VANILLA`: vanilla chests with tables (Lootr or `loot.Restock` handles them) |
| `containers.perPlayer` | `true` | one copy per player in world containers |
| `containers.convertNewChunks` | `true` | catch vanilla loot chests in new chunks |
| `containers.convertExisting` | `false` | convert unopened vanilla loot chests and every Lootr container in old chunks on load |
| `containers.convertVanillaTables` | `true` | also convert `minecraft:chests/*` containers |
| `balance.limitWorldStash` | `true` | the stash allowance of section 7 |
| `balance.stashScale` | `1.0` | its multiplier |
| `balance.restockScale` | `1.0` | the restock times' multiplier |

* With Lootr installed and `NIRNCRAFT`, both run: new chunks get ours; Lootr keeps the containers it already made
  until they are converted (`convertExisting`, or `/nirncraft-containers convert`). Our blocks are not Lootr's
  vanilla blocks, so Lootr never touches them. Containers of ours keep working in every mode.
* `loot.Restock` (vanilla chests without Lootr) is unchanged and does not touch ours.

## 11. Migration from Lootr

* **Before removing Lootr from a world** (recommended): set `system = NIRNCRAFT` and `convertExisting = true`; every
  Lootr container and unopened vanilla loot chest converts as its chunk loads, or run
  `/nirncraft-containers convert <radius>` where players have been. Lootr's per-player copies live in Lootr's own
  files and are not read: after the switch each player rolls each converted container afresh, once, at their level.
  Items a player stored in a Lootr chest stay in Lootr's files; empty those chests first.
* **After removing Lootr** (`LootrCompat`): Lootr's ids are registry aliases of ours, so nothing turns into air.
  `lootr:chest`, `trapped_chest`, `inventory`, `shulker_box` and the copper chests load as `loot_chest`,
  `lootr:barrel` as `loot_barrel`, `lootr:decorated_pot` as `loot_urn`, all with block entity
  `nirncraft:loot_container`, which reads Lootr's `LootTable` and fixes kind and style on the next tick (a boss table
  becomes a hoard). Lootr's brushable blocks go back to vanilla suspicious sand and gravel, its chest minecart and item
  frame entities to vanilla ones, its block items to ours. The aliases are registered only when Lootr is absent.
* **Removing Lootr from the pack** (owner, not done here): delete `pack/mods/lootr.pw.toml`, its two lines in
  `pack/index.toml` and `pack/config/lootr-common.toml` (and `lootr_config()` in `tools/loot_data.py`), the `lootr`
  entries in `pack/migration/platform-lock.json` and `tools/modlist.txt`, and the mentions in `README.md` and
  `docs/FEATURES.md`. `society.client.SocietyClientEvents` filters Lootr's chat lines and can lose that filter.

## 12. Testing and commands

* **Unit tests** (`mod/src/test/java/com/nirncraft/loot/container/LootInstanceTest.java`, `tools\qa\qa.ps1 -Unit`): roll and
  restock timing, never restocking over a player's things, the stash allowance against unlimited storage, weightless
  entries, the save format, two players' copies in one block entity, an open window saved and folded back, a Lootr
  chest's data loaded through the alias, the kind, style and lid rules.
* **Server selftest** (`ContainerSelfTest`; `/nirncraft-containers selftest`, the autorun task `containers`, and
  `tools\qa\qa.ps1 -Server`, which reports `containers`): two real players with real menus on a live level: per-player
  copies, Take All in one copy leaving the other's alone, the lid models stepping open and shut, restock after its time
  and never over the player's things, a boss hoard that never restocks, the stash allowance (nothing lost or
  duplicated when it refuses), unlimited player storage of 129 kinds and 5,000 arrows, one shared window for two
  players, a shelf of lore books refusing a sword, a chunk-style save and load with a window open, a vanilla loot chest
  converted without spilling, and Lootr's chest data normalised into a hoard.
* **Commands** (`/nirncraft-containers`, operators): `info` (the container you look at: kind, style, table, copies, your
  copy's roll, restock and room), `place <kind> [style] [table]`, `restock` (backdates your copy so the next open
  restocks), `convert [radius]`, `selftest`.

## 13. Loot tables tied to place, tier and level

* The containers roll the `nirncraft:containers/<kind>/<tier>` taxonomy of `docs/LOOT.md` section 2 unchanged: the
  builder picks the table for the place (a household of its wealth, a shop of its trade, a dungeon's minor, standard
  or boss tier, a culture's theme), and the roll happens at the opener's loot level with the region's danger.
* New for shelves: `containers/household_books/{poor,common,wealthy,noble}` (owned; one to four books, notes, skill
  books, tomes by wealth) and `containers/bookshelf/{ruin,scholar,arcane}` (free; a ruin's few, a scholar's many with
  skill books, a mage's tomes, soul gems and a staff from level 10). Books are `nirncraft:regional_book`: the lore
  system's books (`lore.LoreBooks`), the province's own eight to one, read like any lore book.
* Phase 2: builders choose shelves, urns and remains where they fit (Ayleid ruins' urns, a college's shelves, a
  battlefield's dead); today shelves, urns and remains come from `LootContainers.place` and the kind rules above.

## 14. Phase 2

* Builders placing the new kinds directly (shelves in homes and libraries, remains in ruins, urns in tombs).
* Sarcophagus and wardrobe/dresser kinds; the settlement furniture containers moved off their block-entity renderer.
* Per-player loot in chest minecarts (entities).
* A shop chest tied to its merchant's restock and theft memory.
* Better art (a texture swap: the model structure is final), and a per-player "searched" look (needs a client-side
  model choice, without a block-entity renderer).
* Copies in per-region saved data for large servers (section 4).
