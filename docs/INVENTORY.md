# Inventory: carrying capacity is weight, nothing else

Skyrim's only carrying limit is weight. Vanilla's `Inventory` has 36 storage slots, a byte stack count and cannot be
enlarged without a mixin or an access transformer (both banned), so Skycraft keeps those 36 slots as the "hands and
belt" and adds an overflow **pack** behind them. Code: `com.skycraft.inventory.Pack` (design comment at the top).

One idea runs through everything below: **a slot list is never the limit, an overflow behind it is.** The same
reusable store (`BulkStore`) is the pack of a player, the overflow of every chest/barrel/ender chest, and the
overflow of every corpse; one facade (`Inv`) is how gameplay code reads and spends what a player carries; and nothing
that holds items is ever allowed to lose them for lack of room (it spills, it never deletes).

| Piece | Class | What |
| --- | --- | --- |
| Bulk store | `inventory/BulkStore` | `(item+NBT, int count)` entries: merge, add/take, caps, deterministic order, versioned NBT |
| Player pack | `inventory/Pack` | a `BulkStore` view over the player's SkyData `pack`, plus weight, sync, refill, virtual slots |
| Container overflow | `inventory/ContainerOverflow` | block-entity capability + resolution of the overflow behind an open menu, drain, spill |
| Overflow of non-BE containers | `inventory/OverflowHost` | interface implemented by corpses, ship holds, the mount's saddlebags and a follower's bags |
| Container sync | `inventory/ContainerSync`, `ContainerOverflowSync` packet | opening client's copy of the overflow while a menu is open |
| Facade | `inventory/Inv` | `count/has/take/give/find/forEach/slotOf` over slots + offhand + pack |
| Ammo | `inventory/Ammo` | brings pack arrows/bolts into a slot where vanilla's `getProjectile` can see them |

## The bulk store

`BulkStore` (one class, no gameplay in it): a list of `{s: <item saved with count 1>, n: <int>}` rows.

* **Identity** is the item saved with count 1 (id, NBT, capability data); equal stacks merge. Counts are ints.
* **Order** is insertion order (a new kind at the end, a drained row removed in place), so indexes are stable between
  a server's request handling and one client sync and every iteration is deterministic.
* **Caps** (engineering, not gameplay): `maxEntries` distinct rows and `maxPerEntry` items per row; `add` returns what
  it could not take and the caller decides (pack: drop at the feet with a message; container: it stays where it was;
  corpse: it falls at the body). Pack and container overflow: 2000 rows / 1,000,000 per row (so a whole pack always
  fits in one chest). Corpse: 4096 rows / 50,000,000 (a whole player always fits in one body).
* **NBT** is `{v: 1, e: [rows]}` (`BulkStore.save`); `load` accepts that or a bare `{e: [...]}`, drops rows with no
  item or a non-positive count, merges duplicate rows and enforces the caps, so old or hand-edited data never
  throws. The player's pack keeps its older layout (`{e: [...], w, wv}` in module `pack`): the rows are identical.
* `absorb(other)` moves everything of another store into this one (used when two chest halves become one).

## The pack

* **Storage.** A list of `(item + NBT, int count)` entries in the player's SkyData module `pack`
  (`{e: [{s: <stack saved with count 1>, n: <int>}], w, wv}`; `w`/`wv` cache the weight). Saved with the player,
  copied on respawn with the rest of SkyData (`PlayerData.copyFrom`), never written as a vanilla stack, so counts are
  ints (a 1000-arrow stack stays 1000).
* **Filling.** Everything that picks an item up goes through `ActionHandler.addToBags` (world pick-up, container Take,
  shop purchases, rewards, loot, crafting results; `Pack.give` / `Pack.giveOrDrop` for the rest): merge into existing
  stacks, then free slots (never the held slot, never above the max stack size), then the pack. The old
  `inv.add(..)` / `placeItemBackInInventory` fall-backs that dropped things on the floor now call `Pack.giveOrDrop`.
* **Weight.** `CarryWeight.current` = slots + armor + offhand + `Pack.weight`. Pick-ups are never refused for weight.
  Encumbrance is graded and has no floor: `CarryWeight.speedFactor` is 1.0 at or under capacity, then `max(0, 1 - r)`
  with `r = (current - capacity) / capacity`; it is the movement-speed modifier (never below 2% of normal in the
  attribute itself, `speedModifier`, so swimming/riding maths stay sane). At double capacity you cannot move and must
  drop things; the inventory screen and its drop actions do not depend on movement, so they stay usable. Beyond +10%
  you also cannot run (`RUN_LIMIT`), and below 50% speed jumps shrink to nothing (`CarryWeight.onJump`). The HUD shows
  "Carry 412/300 (slow 37%)".
* **Capacity** (`CarryWeight.parts`, listed in the inventory screen's weight tooltip) = base 300 + 5 per Stamina
  level-up + perks (Athletics: Strong Back +25/rank x3, Pack Mule +50/rank x2, Beast of Burden +100) + race
  (`CarryWeight.raceBonus`: Orsimer +60, Nord/Ka Po' Tun +50, Redguard/Imga/Atmoran +40, Tsaesci +30, Imperial/
  Argonian +10, Dunmer +5, Altmer -20, Bosmer/Lilmothiit/Tang Mo -15, Khajiit/Ohmes-raht -10, the rest 0) + the
  `bonus.carry` of enchantments and potions + the mount you steer.
* **Grouping.** A take or store request names one slot but moves every stack of that exact item (item + NBT) in the
  container (and, storing, the pack part), in one operation with one message; world pick-ups sweep the same split pile.
* **Caps (engineering, not gameplay).** 2000 distinct item+NBT entries and 1,000,000 items per entry
  (`Pack.MAX_ENTRIES`, `Pack.MAX_PER_ENTRY`). Beyond that `Pack.add` returns the rest and `giveOrDrop` drops it at the
  player's feet with a message. Conjured (bound) weapons are never stored: they only exist where they tick.
* **Draining.** Every 10 ticks free storage slots 9-35 are refilled from the pack (the hotbar is never rearranged), so
  the pack is non-empty only while the slots are really full. **Draining is housekeeping, not the visibility
  mechanism**: gameplay code never waits for it, it reads the pack through `Inv` (below), so a pack item counts the
  instant it is there.
* **Sync.** `SyncData` omits the module (`omitted: ["pack"]`, the client keeps its copy); `InventoryPackets.PackSync`
  sends it in chunks of 100 entries on login, respawn, dimension change and (debounced to 5 ticks) after changes. The
  client mirror lives in its own SkyData module, so `CarryWeight.current` and every screen read it the same way.

## Acting on pack entries

Client requests name an entry as the virtual slot `Pack.VIRTUAL_BASE (1000) + index`, with the item id as before (a
stale index is ignored). The server **materialises** it: pulls one stack into a free slot, or, with all 36 taken,
swaps the last unequipped stack into the pack (`Pack.materialize`), then runs the normal slot rules (bound curses,
equip, use, read, enchant, temper, alchemy, staff enchanting). Favorite, drop and sell act on the pack directly; drop
all / favorite / sell all cover the slot stacks and the pack part of the row.

## Who sees the pack

Skyrim inventory screen and favorites (`InvEntry.build` merges pack entries into the same rows), container Take/Store
("My Items" tab, Store All), barter sell list, alchemy / arcane enchanter / smithing tempering lists, crafting and
cooking costs (`Cost.countIn/removeFrom`), quest item counts and consumption (`Quests`, `Story` relics, cottage key),
shipwright, gifts, lockpicks and jail keys, house keys, theft detection and "stolen" marking, jail confiscation,
`Bounty.confiscate`, collections, soul gems, death drops (`DeathHandler.dropPack`: the same keep rule as the
backpack), coins (`InventoryEvents.convertCoins`).

Known gaps (they still only look at slots; the drain above makes them rare): arbitrary third-party mods reading
`Inventory.items`; vanilla crafting-grid shortcuts. The spell `Transmute` (`SpellEffects.transmuteOne`) takes its iron
through `Inv.take` and hands the gold over with `Inv.giveOrDrop`, so pack iron works. Arrows and bolts: see "The
facade" (`Ammo`).

## The facade (`inventory.Inv`)

The one door gameplay code uses. "Carried" is the 36 storage slots, the offhand and the pack; armor is worn gear and is
never counted or consumed.

| Call | Meaning |
| --- | --- |
| `Inv.count(player, Item \| predicate)` / `has(...)` / `has(player, item, n)` | slots + offhand + pack, both sides (the client mirrors the pack) |
| `Inv.take(player, Item \| predicate, n)` / `takeExactly` | consumes from slots, then the pack; returns how many were removed (server) |
| `Inv.give(player, stack)` / `giveOrDrop` | merge, free slots, then the pack; never refuses (only the pack's cap leaves a rest) |
| `Inv.find(player, predicate)` / `all` / `forEach` | slot stacks are live references, pack entries are copies with their full int count |
| `Inv.slotOf(serverPlayer, predicate)` | a real slot holding a match, pulling one stack out of the pack if needed: for handing an item to vanilla |

Rules for new code: never write `getInventory().items`, `inv.countItem(...)`, `getInventory().contains(...)` or a
`getContainerSize()` loop over the player's inventory for a gameplay decision; call `Inv`. Slot-addressed code (the
Skyrim screens name a slot, a pack row is a virtual slot id) stays slot-based and calls `Pack.resolveSlot/resolveAll`
first. `tools/qa/data_checks.py` fails when code outside the whitelist (`inventory/`, `economy/` and the listed files,
each with the reason) reads the player's slots directly.

Routed through it today: quest item counts and consumption (`quest/Quests`, `quest/story/Story` relics, `IntroFlow`
cottage key), house keys (`economy/Houses`), jail key and lockpicks (`crime/Locks`), gifts (`society/Relationships`),
bedroll (`TentKitItem`), fishing bait (`SkyFishingHook`), shipwright materials (`Shipwright`), the realm toll gem
(`RealmTravel`), collections (`CollectionTracker`), every crafting/cooking/homestead material cost
(`crafting/recipe/Cost`), the handbook gift (`GuideEvents`).

**Ammo.** Vanilla's `Player#getProjectile` reads only the slots and cannot be redirected (no mixins), so `Ammo` brings
one stack of a ranged weapon's ammo from the pack into a slot at `RightClickItem` (before the bow counts arrows) and
every 5 ticks while a ranged weapon is in hand. It works for every `ProjectileWeaponItem` (bows, vanilla and Dawnguard
crossbows, whose `getAllSupportedProjectiles` includes bolts). Arrows in the pack are usable at once; the rest stays in
the pack.

**Consumers left on purpose (and why).** Slot-addressed screens and actions: alchemy, enchanting, staff enchanting,
tempering, soul gem filling, barter sell, the stolen-blade handover (all call `Pack.resolveSlot/resolveAll` and so work
on pack rows). Code that mutates stacks in place or transfers whole inventories: theft tagging (`crime/Theft`), jail
confiscation (`crime/Jail`, `crime/Bounty`), death (`combat/DeathHandler`), pick-up (`vitals/ActionHandler.addToBags`).
Conjured weapons (`magic/bound/BoundWeapons`) and the hand management of `magic/Hands` live only in slots by design:
a conjured weapon only exists where it ticks, is never stored (not in the pack, not in a chest) and the hand/hotbar
logic names real slots, so neither goes through `Inv`. The crafting counts agree with `Inv`: `crafting/recipe/Cost`
(stations, cooking, homestead plans, tempering cost) is `Inv.count/take` itself; alchemy, enchanting, staff enchanting,
soul gems and tempering name a slot and resolve pack rows with `Pack.resolveSlot/resolveAll`, so a pack row counts at
once. Vanilla and third-party readers (hoppers, brewing stands, furnaces, other mods) see only vanilla slots.

## Containers: a chest has no slot limit

Skyrim's chest holds anything; the engineering cap is 2000 kinds x 1,000,000 each (`ContainerOverflow.MAX_*`).

* **Where the items are.** The vanilla slots stay (27 / 54): hoppers, comparators, `/data` and every non-Skyrim reader
  see only them. What the slots cannot hold is the **overflow**, a `BulkStore` attached to the block entity as a Forge
  capability (`ContainerOverflow.CAP`, via `AttachCapabilitiesEvent<BlockEntity>`). Forge serialises it into the block
  entity's NBT (`ForgeCaps`), so it is saved with the chunk, survives a restart, travels with `/clone`, and marks the
  chunk dirty when it changes. Eligible: every `RandomizableContainerBlockEntity` (chests, trapped chests, barrels,
  modded chests, every Skycraft container block, which are all vanilla chests/barrels) except hoppers, dispensers/
  droppers and shulker boxes (a shulker keeps its items with the item). The ender chest's overflow is the player's
  (`module ender_pack`, never synced to the client with the rest of the player's data).
* **Double chests.** One `CompoundContainer`, one overflow: the half with the smaller block position (x, then y, then
  z) is the primary. The other half's overflow is absorbed into it the moment the pair is opened, so doubling a chest
  that already had an overflow strands nothing; the pair's menu, the Skyrim screen and `Theft` all see the one store.
* **Screen and requests.** The Skyrim container screen lists the overflow rows with the slot rows, grouped by exact
  item (item + NBT): one row per item, whatever mix of slots and overflow holds it. A take of any row takes every stack
  of that item, slots first, then the overflow; a store goes into matching/empty slots first, then the overflow, so it
  never fails for lack of slots. Take All / Store All do the same for everything. Overflow-only rows are named by the menu
  slot id `ContainerOverflow.SLOT_BASE (10000) + index` in `ContainerTake`. After each operation the overflow refills
  free slots (`ContainerOverflow.drain`), so the overflow is non-empty only while the slots are really full.
* **Sync.** `ContainerSync` keeps one session per player: on open the overflow is normalised and sent
  (`ContainerOverflowSync`, 100 rows a packet, at most 2000 rows), then resent on the first tick after any change,
  including another player's change to a shared chest (the store's own version counter), at most every 2 ticks. The
  client mirror (`inventory/client/ContainerOverflowClient`) is keyed by the menu's container id.
* **Theft.** `Theft.countMenu` counts the overflow as part of the container, so taking from an owned chest's overflow
  is stealing exactly like taking a slot.
* **Destruction.** When a container's block entity is really removed (broken, exploded, replaced, burnt; a chunk unload
  is told apart: `onChunkUnloaded` runs first) the whole overflow is spilled where it stood as never-despawning item
  entities (`ContainerOverflow.spill`; past 2048 entities the rest goes to the nearest player's pack). Whatever stops the
  block from breaking (the protect module in towns) stops the spill with it.
* **Other containers.** Anything that is a `Container` and implements `OverflowHost` (give the store an `onChange` that
  marks the owner dirty, save `BulkStore.save()` next to the slots) gets the same screen behaviour with no other
  change: the corpses, the ships' cargo holds (`sailing/ShipEntity`, spilled when the ship sinks or is dismantled), the
  mount's saddlebags and a follower's bags do. A host with a limit of its own answers `overflowRoom(stack)`: a weight
  rule never lets the overflow bypass it (`Bound.add` hands back what the room refuses).
* **Saddlebags** (`mounts/Saddlebags.Bag`, details in docs/MOUNTS.md). 52 cargo slots plus an overflow in the player's
  server-only module `mount_bags`; the limit is the saddle's weight over both (`overflowRoom`, slot `mayPlace`).
  `Bag.canPlaceItem` is structural only (moving a row from the overflow into a free slot, which the screen does after
  every take, changes no weight). Every change of the slots is written to the record at once (`setChanged`), like the
  overflow, so a crash with the menu open loses nothing. `Saddlebags.fit` (saddle shrinks or goes: overflow rows first,
  last first, then the last slots) and `emptyToPlayer` (`Mounts.retire`, so buying, taming and selling) hand items to
  the player through `Inv.giveOrDrop`, with the open menu's slots written first and reloaded after.
* **Followers' trade bags** (`society/Followers`). 27 slots plus an overflow (`skycraft_follower_ov` in the NPC's
  persistent data), weight-limited at `Followers.CARRY_KG` (300, the player's base) over both; the Skyrim container
  screen lists it like a chest. Gear the follower takes off and the stow of swapped gear ignore the weight (never
  refused, never dropped while the overflow takes it); when the menu closes over the limit (a shift-click can overfill)
  the surplus goes back to the player. Dismissal gives slots and overflow to the owner (`Inv.giveOrDrop`); death drops
  the slots and spills the overflow (`ContainerOverflow.spill`, bounded, never deleted).
* **Jail evidence chests** (`crime/Jail`). The evidence chest is an ordinary chest block entity, so it has the
  `ContainerOverflow` capability: what its 54 slots cannot hold goes into its overflow (`Jail.stash`), the Skyrim
  screen shows it, a cell counts as occupied while the overflow holds anything, and `Jail.release` hands every
  non-stolen row back. `JailData.held` remains only for a missing chest and for saves from before this.
* **Loot restock** (`loot/Restock`). A restock empties the slots and the overflow, and only when nothing but what the
  table rolled is inside: `holdsForeignItems` counts the overflow rows against the recorded roll (an unreadable row
  counts as the player's), so a player's stash in a restocking container is never wiped.
  change: the corpses and the ships' cargo holds (`sailing/ShipEntity`, spilled when the ship sinks or is dismantled)
  do. A mount's saddlebags are weight-limited, not slot-limited: their owner decides whether (and with what rule) to
  expose an overflow. Followers' trade inventories keep their own accounting. The jail's two stores (a prisoner's
  evidence and the shared confiscated goods) are `BulkStore`s in `Jail.JailData`, unbounded and independent of any block;
  the confiscated goods open through an `OverflowHost` container (`crime/JailEvidence.StoreContainer`): see docs/JAIL.md.

## Corpses and death

* **One body.** On death `DeathHandler` takes the pack's non-kept entries (quest items always stay; in VALUABLES mode
  everything but valuables stays) as whole int-count entries and hands them to `PlayerCorpses.stashPackDrops`; the
  LOWEST `LivingDropsEvent` handler puts the slot drops and those entries into **one** `PlayerCorpseEntity`:
  54 slots, then its overflow (`BulkStore`, 4096 rows). No item entity is created for lack of room. A 10,000-item pack
  is one body, not thousands of ground items.
* **Never lost.** The body persists slots and overflow in its NBT (`PlayerItems`, `PlayerBulk`); on load a stack whose
  slot no longer exists goes to the overflow instead of being dropped. It never despawns while it holds anything
  (emptied, it fades after `emptyDespawnSeconds`), is fireproof, is put back on safe ground from lava or the void,
  survives a restart or a chunk unload as an entity, and its "Your Corpse" map marker follows it (unchanged). Taking the
  body away (`/kill`, creative punch) spills its content; if another mod vetoes the body, the items stay as drops.
* **Edge cases.** `keepInventory`: nothing is stashed, the pack stays, vanilla restores the slots. Another mod cancels
  the drops (a grave): the stash goes back to the player's pack and comes back with the respawn copy. A prisoner dying in
  jail: the pack part goes to the jail's stores with the rest (`PlayerCorpses.takePackCargo`). Corpses switched off in
  the config: the pack part falls as item entities, as it always did. Other players loot a body by the existing rule
  (`player_corpses.lootAccess`).
* **Creature and NPC bodies** (`creatures/entity/CorpseEntity`) have the same overflow behind their 27 slots (`Bulk` in
  their NBT), so a body never drops loot for lack of slots.

## Respawn and restart

* The pack is part of the player's data: saved with the player file, copied on respawn with the rest of SkyData
  (`PlayerData.copyFrom`), re-synced on login/respawn/dimension change. What the death rules keep (quest items,
  VALUABLES-mode kit) never leaves the pack or `death_kept`.
* A container's overflow is saved with its chunk (`ForgeCaps`); a corpse's with the entity; the ender chest's with the
  player. Nothing here lives only in memory.
* The selftest (below) saves and loads each of these through real NBT, compressed bytes and a real file.

## Selling

Quest items (quest flag, `skycraft_quest_item`, quest item kinds: letters, relics, keys), coins, conjured weapons and
filled containers are not listed and are refused by the server (`Barter.sell`, `Barter.sellable`, `isQuestKind`).
The sell list marks equipped rows with an "E" badge and the inventory screen's colours and sorts them last;
unequipped stacks of one item (slots and pack) are one row. Selling an equipped item needs the explicit second click
(`confirm` in `BarterAction`, validated on the server), sells one item, and never a bound (cursed) worn item.
**Buy back** (`economy.Buyback`): per player and merchant, the last 30 minutes of sales, at the price paid, taken from
the merchant's stock (if somebody else bought it meanwhile it is gone, so nothing can be duplicated); a buy-back
suppresses Speech XP from that merchant for two minutes (no sell/buy-back farming).

## Tests

`/skycraft-inventory selftest` (also autorun task `inventory`, `tools/qa/qa.ps1 -Server`, log line
`Inventory selftest: n/m checks passed`): 336 distinct items and 1000 arrows into a full inventory, weights, real
`saveWithoutId` / compressed NBT / `load` round trip, packet encode/decode of the mirror, refill, virtual slot
actions, a full container taken into a full inventory, quest item sale refusal (slot and pack), equipped confirmation,
buy back. `/skycraft-inventory pack` shows your own pack.

`inventory/OverflowSelfTest` (part of the same run): the bulk store (caps, order, versioned NBT, repair of bad/duplicate
rows, absorb); a container with an overflow driven through the real `handleContainerTake/Store` (Store All into a full
container, overflow-only take, grouped take of slot + overflow, drain, packet round trip); chest block entities saved
with `saveWithFullMetadata` and loaded with `BlockEntity.loadStatic`, double-chest halves and absorb, a chunk unload that
spills nothing and a destruction that spills everything; a full death (drops event through `DeathHandler` and
`PlayerCorpses`, body NBT round trip, respawn `Clone` event, loot by the owner, counts identical); a player written to a
real temp file with `NbtIo.writeCompressed` and loaded (counts, quest item, favorite, ender overflow); the facade and
the ammo hook (bow and crossbow); the stragglers: Transmute from the pack, a follower's bags (overflow, weight rule,
NBT round trip, death) and restock with an overflow. Static lint: `tools/qa/data_checks.py` (direct slot reads
outside the whitelist).

`/skycraft-mounts selftest` (`mounts/MountsSelfTest`, `bagOverflow`): 80 distinct kinds through the real
`handleContainerStore` into 52 slots plus overflow, the weight rule over both (a too heavy item refused, 1000 light
items stored only up to the last kilogram, the rest stays with the player), the record's list through compressed NBT
and a reopened bag, the module absent from the data sync, a saddle that carries a quarter and then nothing (spills,
counts equal), a grouped take over slot and overflow, an overflow-only row by its slot id, `emptyToPlayer`, and the
retire and sell paths of the one-active-mount rules with overflow rows.
