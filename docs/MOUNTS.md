# Mounts: saddles, tack, saddlebags and stamina

Code: `mod/src/main/java/com/skycraft/mounts/` (`SaddleType`, `MountGear`, `Saddlebags`, `SaddlebagMenu`, `MountStamina`,
`Mounts`, `MountKind`), taming in `fauna/WildHorseTaming`, selftest `MountsSelfTest` (`/skycraft-mounts selftest`, run by
`qa.ps1 -Server`). Kinds, breeds and their base data (`MountKind`) are unchanged; this layer sits on top.

## The loop
1. **Get a saddle** (an item): stablemasters sell them, or craft them (a pack saddle is a saddle, leather and a chest; a war
   saddle steel and leather; an Imperial saddle gold, red wool and leather).
2. **Hold it**: Skyrim inventory, Equip (right hand), like a weapon.
3. **Sneak up to a wild mount and use it.** Not sneaking, sprinting, or having hurt it in the last 5 s spooks it. Only wild
   mounts are saddled this way; one saddle is consumed. The breed must take that saddle type (matrix below). Then the
   existing bucking minigame; a failed or interrupted breaking returns the saddle. Success: it wears that saddle.
4. **Your own mount**: use = ride; **sneak-use = the mount menu**, the Skyrim container screen of chests and corpses (no
   vanilla horse screen exists: it is closed on the server and cancelled on the client; the inventory key while mounted opens
   the normal Skyrim inventory).
   - Slot entries "Worn: saddle" and "Worn: barding": Take = Unequip. From My Items, Store = Equip, and it swaps with what is
     worn (the old piece goes back to your bags). Take All and Store All never touch gear.
   - Everything else is cargo, limited by **weight** (kg, the `ItemWeights` unit), not by slots. A smaller or removed saddle
     spills the surplus, last stacks first, split to fit, into your bags (floor if full). Nothing is ever deleted.
   - An unsaddled own mount takes a saddle straight from the hand; a saddled one only through the menu. Barding in hand on
     an own mount puts it on.

## Saddles
| Saddle | Carry (kg) | Bags (kg, 2-row beast) | Stamina | Speed | Mounted blow | Price |
|---|---|---|---|---|---|---|
| Plain (`minecraft:saddle`) | +0 | 20 | x1.00 | 0 | 0 | 60 |
| Pack / travel (`pack_saddle`) | +60 | 80 | x0.95 | -4% | 0 | 220 |
| War (`war_saddle`) | +10 | 30 | x1.10 | +2% | +15% | 400 |
| Imperial / noble (`noble_saddle`) | +20 | 50 | x1.15 | +5% | +5% | 900, only where there is a court |

Bag capacity scales with the beast (`bagRows / 2`: 1 row half, 3 rows one and a half). **Carry** is added to your carry
capacity while you steer the mount, on top of the breed's own (`MountGear.baseCarry`: ponies 60, riding horses 80, Alik'r 70,
destrier 110, Colovian draught 140, camel 200, guar 260, mammoth 320 ...). Stamina is the kind's stamina times the saddle.

## Breeds by saddle (cultural reasoning in the right column)
| Breed (region) | Plain | Pack | War | Imperial | Why |
|---|---|---|---|---|---|
| Windhelm pinto, Markarth black (Nord and Reach hill ponies) | y | y | - | - | small hardy ponies, work stock, no cavalry |
| Whiterun bay, Riften chestnut (Skyrim holds) | y | y | y | - | the holds' riding and cart horses; Legion and Stormcloak cavalry |
| Solitude grey (Haafingar, the Imperial seat) | y | y | - | y | court and coach horses |
| Cyrodilic bay (Cyrodiil, also Skyrim imports) | y | y | y | y | the Imperial all-rounder |
| Colovian paint (Colovian Highlands) | y | y | y | - | hardy draught stock, most carry |
| Nibenese grey (Nibenay) | y | - | - | y | light, fast, bred for show and post |
| Breton destrier (High Rock) | y | y | y | y | knightly warhorse, comes barded |
| Alik'r steed (Hammerfell, desert) | y | y | y | - | Redguard cavalry; the highest stamina of the horses |
| Auridon white (Summerset) | y | - | - | y | Altmer riding horse, too fine for panniers |
| Camel (Hammerfell) | y | y | - | - | caravan beast, 2 seats |
| Guar (Morrowind) | y | y | - | - | pack lizard, the biggest bags and carry |
| Senche-tiger (Elsweyr), Akaviri tiger (Akavir) | y | - | y | - | war cats, no panniers |
| Giant wolf (Valenwood), Woolly mammoth (Atmora) | y | y | y | - | Bosmer hunt-riders, Nord haulers |
| Indrik (Summerset) | y | - | - | y | Altmer ceremonial beast |
| Tamed grazer (the realm) | y | y | - | - | pack animal |
| Shadowmere, Arvak | - | - | - | - | legends: their own tack, not changed |

## Barding (armour tiers; adds armour value and health = 2 x armour)
Leather 3, iron 5, steel 6, Nordic 7, Redguard 8, Imperial 9, Elven 10, Breton 11, dwarven 12, orcish 13, ebony 15 (horses);
beast barding 8 (beasts). Crafted at a table from the ingot of the tier (7 ingots); the culture bardings are still sold by
stablemasters. Vanilla horse armour works too.

## Stamina bar
While you steer one of your mounts the **HUD stamina bar is the mount's** (the same fraction of your bar): your own value is
parked in `module("mounts").parked_stamina` and restored the moment you leave the saddle (dismount, death, logout, teleport,
the mount dying), and the mount keeps what the bar held (`skycraft_mount_stamina` on the entity, `stamina_pts` in the record
for a mount brought back from afar). Gallop (sprint key) drains 1 point per tick, a jump 6% of the max, resting regains
slowly; an exhausted mount cannot gallop until a quarter is back and you cannot sprint either. The vitals module neither
drains nor restores the bar while swapped. The only mount HUD left is a thin health bar when the mount is hurt.
