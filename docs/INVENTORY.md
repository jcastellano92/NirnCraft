# Inventory: carrying capacity is weight, nothing else

Skyrim's only carrying limit is weight. Vanilla's `Inventory` has 36 storage slots, a byte stack count and cannot be
enlarged without a mixin or an access transformer (both banned), so Skycraft keeps those 36 slots as the "hands and
belt" and adds an overflow **pack** behind them. Code: `com.skycraft.inventory.Pack` (design comment at the top).

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
  the pack is non-empty only while the slots are really full.
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

Known gaps (they still only look at slots; the drain above makes them rare): bait is found in the pack only when no
slot has any; arbitrary third-party mods reading `Inventory.items`; vanilla crafting-grid shortcuts.

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
