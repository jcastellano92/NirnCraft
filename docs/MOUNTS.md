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

## Downed and companion combat (`MountCompanion`, `MountCompanionEvents`, `CompanionPackets`, `client/MountDownedClient`)

Applies to every OWNED mount of a `MountKind` (a Skycraft mount entity carrying `skycraft_mount` and a player owner). NPC
and guard horses, wild mounts and stable-master stock have no owner tag and are untouched.

### Downed instead of dead
* A blow past the mount's health (`LivingDamageEvent`, HIGHEST) or any death event (`LivingDeathEvent`) is cancelled and
  the mount is laid down with 1 health. `Mounts.onDeath` (cooldown, "has fallen") now only runs for an explicit kill
  (`/kill`, the `GENERIC_KILL` source); damage never removes a mount.
* **While down**: invulnerable, silent (it whimpers every 4 s), no AI, rider ejected, cannot be mounted
  (`EntityMountEvent`), no blows land (`LivingAttackEvent`/`LivingHurtEvent` cancelled, aimed or area), cannot be a target
  (`LivingChangeTargetEvent` cancelled, mobs already after it are cleared every 0.5 s within 24 blocks). Client: the model
  is rolled onto its side by `RenderLivingEvent` (works on vanilla horses, camels, the guar and every rig), owner gets a
  message, a low note and a compass marker ("Fallen mount").
* **Rises** (`MountCompanion.tryRise`, from the owner's player tick) when it has lain 3 s and the area is calm: owner not
  in combat for 8 s (`Vitals.inCombat`, 160 ticks), no hostile within 24 blocks of the mount or the owner that targets
  either, OR the owner is gone (offline, dead, other dimension, more than 64 blocks away). It stands at 35% health and
  cannot be ridden for 5 s (`skycraft_mount_rest`; the owner is told).
* **Call (H)** while down: if calm it gets up and comes; otherwise "is down and cannot come until the fight is over".
* **Edge cases**: state is entity persistent data (`skycraft_downed`), so it survives saves and restarts. A downed mount
  loading with its owner offline/elsewhere, or not the recorded mount, rises quietly; the owner logging out raises it
  quietly; a downed mount that falls below the world is put beside its owner (or the surface) and stays down until
  calm. Dimension change: it stays downed in the old dimension; calling spawns a fresh mount as before, and the old
  copy is dropped as stale.
* Stable cooldowns: `DEATH_COOLDOWN_TICKS` and Arvak's 10 s respawn remain only for the explicit-kill branch.

### Companion combat
A mount that is not ridden and whose profile `fights` helps while its owner is in combat: it takes up the mob that hit
the owner, the mob the owner hit, or a hostile targeting the owner (monsters, or mobs targeting the owner; never
players, the owner's pets or other owned mounts), stays within 12 blocks of the owner and lets go of a foe more than 20
blocks from the owner. Beasts attack with their own spec attacks (so their attack attribute and animations); horses and
Arvak kick (`attribute or base x factor x power`, cooldown, knockback). `power` reads the persistent floats
`skycraft_mount_power` (multiplier) and `skycraft_mount_level` (+4% per level) that the mount-stat system may write.
Non-fighters keep 8 blocks from hostiles near the owner (moving away, drifting back to the owner when far), and never get
a target. A downed fighter leaves the fight like any downed mount.

**Stance**: sneak + the call key (H) while your mount is within 48 blocks, or `/skycraft-mounts stance [follow|stay]`.
Stay: it does not follow into a fight and only takes foes within 6 blocks. (Sneak-use stays the saddlebag menu.)

| Kind | Role | Kick/attack | Notes |
|---|---|---|---|
| Whiterun bay, Riften chestnut, Cyrodilic bay, Colovian paint | fighter (light) | x0.5, base 4, 1.5 s | cavalry stock |
| Breton destrier | fighter | x0.8, base 5 | warhorse |
| Alik'r steed | fighter (light) | x0.6, base 4 | |
| Shadowmere | fighter | x1.0, base 6 | legend |
| Arvak | fighter (strong) | x1.4, base 8 | legend |
| Senche-tiger | fighter | spec attacks | claws, 1.2 s |
| Akaviri tiger | fighter | spec attacks | stronger |
| Giant wolf | fighter | spec attacks | |
| Woolly mammoth | fighter (stomp) | spec attacks | slow, heavy knockback |
| Realm grazer | fighter (light) | spec attacks | |
| Windhelm pinto, Markarth black, Solitude grey, Nibenese grey, Auridon white | peaceful | none | keep 8 blocks away |
| Camel, pack guar, indrik | peaceful | none | camel's brain may override the keep-away |

### Future work
* A real stance/command key and a radial entry; "attack my target" order; per-mount aggression setting.
* A proper lying/sitting animation per rig (the roll is a render transform) and a lying hitbox.
* Mount stat/level system (the other mounts workstream): write `skycraft_mount_power` / `skycraft_mount_level`; bardings could
  add a kick bonus; the kick for the beasts should scale with it too (set the attack attribute modifier).
* Revive by item (a healing potion or food speeds the rise), and a rise timer shown on the mount's health bar.
* Keep-away does not avoid doors; camels use a brain, so their flight is only partly effective.
* A downed mount in a dimension the owner left rises only when loaded again (a fresh mount is spawned on call).