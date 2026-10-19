# Mounts: saddles, tack, saddlebags and stamina

Code: `mod/src/main/java/com/nirncraft/mounts/` (`SaddleType`, `MountGear`, `Saddlebags`, `SaddlebagMenu`, `MountStamina`,
`Mounts`, `MountKind`; individuals: `MountProfile`, `MountStats`, `MountToken`, `MountGrowth`, `MountInspect`, `MountView`,
`MountStock`, `MountStable`,
`StableUi`, `StablePackets`; client `mounts/client`), breaking in `fauna/WildHorseTaming` + `fauna/TameBalance`, the stable
dialogue `fauna/Stables`, selftest `MountsSelfTest` (`/nirncraft-mounts selftest`, run by `qa.ps1 -Server`).

## The loop
1. **Get a saddle** (an item): stablemasters sell them, or craft them (a pack saddle is a saddle, leather and a chest; a war
   saddle steel and leather; an Imperial saddle gold, red wool and leather).
2. **Hold it**: Skyrim inventory, Equip (right hand), like a weapon.
3. **Sneak up to a wild mount and use it.** Not sneaking (unless you have fed it), sprinting, or having hurt it in the last
   5 s spooks it. Only wild mounts are saddled this way; one saddle is consumed. The breed must take that saddle type
   (matrix below). Then the balance minigame (below); thrown or interrupted returns the saddle. Success: it wears that saddle.
4. **Your own mount**: use = ride; **sneak-use = the mount menu**, the Skyrim container screen of chests and corpses (no
   vanilla horse screen exists: it is closed on the server and cancelled on the client; the inventory key while mounted opens
   the normal Skyrim inventory). Empty-handed it opens on the **stat sheet** (key 3 shows it from the bags too).
   - Slot entries "Worn: saddle" and "Worn: barding": Take = Unequip. From My Items, Store = Equip, and it swaps with what is
     worn (the old piece goes back to your bags). Take All and Store All never touch gear.
   - Everything else is cargo, limited by **weight** (kg, the `ItemWeights` unit), not by slots. The 52 cargo slots of the
     screen are only the first rows: what they cannot hold goes to the bags' **overflow** (`BulkStore`, behind the slots like
     a chest's, `inventory.OverflowHost`), listed in the same screen grouped by exact item (Take / Store / Take All / Store
     All work on the rows), so 80 different items fit as long as they weigh less than the saddle carries. The limit counts
     slots and overflow together (the header shows the sum). A smaller or removed saddle spills the surplus, the overflow's
     last rows first, then the last slots, split to fit, into your bags (floor if full). Nothing is ever deleted.
   - Persistence: the slot stacks are `bags` in the mount record, the overflow is the player's server-only module
     `mount_bags` (list `e`, the `BulkStore` row format; never in the data sync). Both stay with the player through
     calling, falling, switching and relogging; see "One active mount".
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
the mount dying), and the mount keeps what the bar held (`nirncraft_mount_stamina` on the entity, `stamina_pts` in the record
for a mount brought back from afar). Gallop (sprint key) drains 1 point per tick, a jump 6% of the max, resting regains
slowly; an exhausted mount cannot gallop until a quarter is back and you cannot sprint either. The vitals module neither
drains nor restores the bar while swapped. The only mount HUD left is a thin health bar when the mount is hurt.

## Downed and companion combat (`MountCompanion`, `MountCompanionEvents`, `CompanionPackets`, `client/MountDownedClient`)

Applies to every OWNED mount of a `MountKind` (a NirnCraft mount entity carrying `nirncraft_mount` and a player owner). NPC
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
  cannot be ridden for 5 s (`nirncraft_mount_rest`; the owner is told).
* **Call (H)** while down: if calm it gets up and comes; otherwise "is down and cannot come until the fight is over".
* **Edge cases**: state is entity persistent data (`nirncraft_downed`), so it survives saves and restarts. A downed mount
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
`nirncraft_mount_power` (multiplier) and `nirncraft_mount_level` (+4% per level) that the mount-stat system may write.
Non-fighters keep 8 blocks from hostiles near the owner (moving away, drifting back to the owner when far), and never get
a target. A downed fighter leaves the fight like any downed mount.

**Stance**: sneak + the call key (H) while your mount is within 48 blocks, or `/nirncraft-mounts stance [follow|stay]`.
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
* Mount stat/level system (the other mounts workstream): write `nirncraft_mount_power` / `nirncraft_mount_level`; bardings could
  add a kick bonus; the kick for the beasts should scale with it too (set the attack attribute modifier).
* Revive by item (a healing potion or food speeds the rise), and a rise timer shown on the mount's health bar.
* Keep-away does not avoid doors; camels use a brain, so their flight is only partly effective.
* A downed mount in a dimension the owner left rises only when loaded again (a fresh mount is spawned on call).
for a mount brought back from afar). Gallop (sprint key) drains 1 point per tick (a runner has about 14 s on a 100 bar, every
mount 30 s or more, see the table), a jump 6% of the max, resting regains the bar in 12 s standing or 24 s moving (a trusting
mount sooner); an exhausted mount cannot gallop until a quarter is back and you cannot sprint either. The vitals module
neither drains nor restores the bar while swapped. The only mount HUD left is a thin health bar when the mount is hurt.

## Individuals: stats, grades, growth, trust (`MountStats`, `MountGrowth`)
Every mount of every kind is an individual: **Speed, Stamina, Health, Jump, Carry** and **Temperament** are rolled once
(bell-shaped, from the wild animal's UUID, or the stable's key, week and slot; the legends are fixed Fine to Superb), shown as
a grade (Poor, Fair, Good, Fine, Superb; a Superb is about 1 in 22 per stat). Variance at the extremes: speed +-6%, stamina,
health, carry +-20%, jump +-10%. **Trust** (0..100) is earned and moves speed (-3%..+3%) and wind recovery (-15%..+15%).
- **Growth** (cap level 20): mount XP per second of riding is distance (walk 1 per 48 blocks, gallop 1 per 16), jumps (1.5),
  hauling a load of 20%+ of its bags (0.1/s), blows struck and hits taken. Each level is one growth point in the stat that
  use trained most (gallop: Speed and Stamina, jumps: Jump, hauling: Carry, taking hits: Health), up to a soft cap of
  3 + grade + what the kind trains well (table). Point worth: speed +0.4%, jump +1%, the others +1.5%. A level-up shows a
  message, a sound and particles.
- **Trust** rises with feeding (apple, carrot, sugar +4, hay +3, wheat +2, golden +8; one feeding per 30 s; use the item on
  it, a wild one takes +50% and will let you walk up at 50), a day left in a stable (+6), riding (a point in 6 min); it falls
  with a swing at your own mount (-3), and while it is left hurt (under 35%) and far from you (-0.5 per 10 s).
- Applied through attribute modifiers (speed, health, jump) and the stamina, carry and bag multipliers.
- **The stats belong to the animal, not the player** (`MountToken`, `MountStable`): while it is an entity its persistent data
  (`nirncraft_mstats`, `nirncraft_mid` its animal id, custom name, coat, health) is the single truth; whenever it is not an
  entity it is a **token**, a self-contained CompoundTag (`id`, `kind`, `stats` incl. rolls, growth, level, XP, trust and
  training counters, `name`, `coat`, `hp`, `origin`; never tack or bag items). The player's record (`mounts.tokens.<kind>`)
  holds only the token of the ACTIVE mount and of the legends, refreshed on every change, on unload (`EntityLeaveLevelEvent`),
  on logout and before a body is discarded. Training an animal that stands in an unloaded chunk changes its token and bumps
  `rev`; the newer revision wins when it loads. Old `mstats.<kind>` records become tokens when first read.

## Inspecting (`MountInspect`, `MountView`; the server decides what the viewer may know)
- **Target card**: look at any horse, camel, guar or beast within 6 blocks (16 with Wild Eye): top-right HUD card with name or
  breed, wild or owner, level, the six grades, saddle and armour, health. The client asks when the target changes and every
  2 s (the server rate-limits to one per 5 ticks and validates distance and kind).
- **Sheet**: sneak-use your own mount empty-handed, or key 3 in the mount menu: level and XP bar, trust, grades and numbers
  with growth pips against their cap, carry and bags, saddle, barding, health.
- **Perks** (Riding): *Horse Sense* grades and numbers (without it only words: swift, sturdy, placid; no numbers);
  *Wild Eye* tells what a wild mount is, sizes it up at range and marks wild mounts within 48 blocks on the compass (without it:
  "a wild animal"); a stranger's grades are a
  two-grade estimate below Riding 40.

## The stable (`StableUi`, `MountStock`, `StableScreen`)
The stablemaster's "Show me your horses" opens one screen: **Horses** lists this week's stock (a stable's own quality and the
week's luck move the bell; each stable keeps its own, per region flavour from `MountKind.forSale`) beside **your own mount**,
from its entity or token wherever it is parked: grades, numbers (Horse Sense), arrows up and down, level, trust, whether
your saddle and barding fit, price, and what yours is worth. Price = list price x (0.75 + 0.45 x quality + 5% a level),
clamped 0.5..2.6; trade-in is 60% of the same formula for yours. Buying and selling ask for a second click and are
recomputed on the server. **Train** pays a stable hand one growth point in a stat (fee 50 + 40/point + 20/level + grade;
Trust +10), at most twice an in-game week and never past the stat's soft cap.
- **Selling puts the animal in that stable's stock.** Its token (same stats, level, trust, training, name, coat; id kept) moves
  into the stable's SavedData (`nirncraft_mount_stock`, keyed by the stable's key, at most 12, the oldest overflow to a
  bounded "sold on" pool of 64, of which one animal that suits the region is shown per week). It is listed first in the
  stock, priced by its grades and level, indistinguishable from generated animals except that it has a history. Buying it
  restores the token onto a fresh entity: that very animal. Generated stock keeps filling the rest, deterministic per week.
- **No duplicates**: selling removes the token from the seller's record and discards the entity in the same server call; a
  purchase takes the token out of the stable's storage before anything is granted (a second click or a second buyer finds
  nothing, and a failed grant puts it back). An animal standing in an unloaded chunk when it is sold gets a fate
  (`MountStable.fate`: delete, or release) that is applied when it loads, by its animal id and its entity UUID.
- Tack and saddlebag items are not sold with the animal: they are returned to the seller first (the saddle and barding to
  your bags, bag contents to your inventory). A released tamed mount keeps its stats, name and id on the entity, and anyone
  can break it in again to get that individual.

## One active mount
Buying or breaking in a mount retires the previous regular mount: its saddlebag items (slots and overflow rows, with their
whole counts) go to your inventory through `Inv.giveOrDrop` (slots, then your pack), its saddle and
barding move to the new mount if the breed takes them (else to your bags), and the animal is released if you tamed it
(it wanders free, token on it) or its token goes to the stable (a bought one pays its trade-in when you tame instead:
to the sold-on pool; at a stable master: into that stable's stock). Selling does the same and pays by grade and level.
Never deleted. Legends (Shadowmere, Arvak) are quest rewards: they stay yours and are
brought out at any stablemaster instead; getting one does not retire your horse, it steps aside to the stable.

## Breaking in (`WildHorseTaming`, `TameBalance`)
A bar at the bottom centre with a safe zone and a marker. The bucking pushes the marker with growing strength and speed;
hold A / D to push back (the camera rolls with it); progress fills in the zone and drains outside; touching an edge too long
throws you. 20 to 35 s by the kind's difficulty (shorter for a trusting, placid animal); the AI is off meanwhile and the
vanilla saddle goes on only at the end. The server simulates it and the client sends only steer. *Steady Seat*: slower
growth, wider zone, longer grace. Other perks: *Pack Master* +10% carry and bags a rank, *Endurance* -12% gallop burn a
rank, *Trick Rider* +6% mounted blows and -15% jump cost a rank.

## Per kind (average animal; individuals vary by the grades above)
| Kind | Gallop | Speed (m/s) | Health | Jump (blocks) | Carry | Bags (plain) | Trains well (cap +) |
|---|---|---|---|---|---|---|---|
| Whiterun bay | 35 s | 12.0 | 26 | 3.1 | 80 kg | 10 kg | speed 1, jump 1 |
| Solitude grey | 36 s | 12.9 | 28 | 4.0 | 80 | 10 | speed 2, jump 1 |
| Windhelm pinto | 40 s | 11.2 | 32 | 2.7 | 60 | 20 | health 2, stamina 1, carry 1 |
| Riften chestnut | 34 s | 14.2 | 24 | 4.4 | 80 | 10 | speed 2, jump 1 |
| Markarth black | 42 s | 11.6 | 36 | 5.4 | 60 | 20 | health 2, stamina/jump/carry 1 |
| Shadowmere | 50 s | 15.5 | 50 | 5.9 | 120 | 20 | all 2 (fixed Fine+) |
| Cyrodilic bay | 36 s | 12.9 | 30 | 3.5 | 80 | 20 | all 1 |
| Colovian paint | 38 s | 13.3 | 28 | 4.0 | 140 | 10 | carry 2, stamina/health 1 |
| Nibenese grey | 32 s | 14.2 | 26 | 4.0 | 80 | 10 | speed 2, jump 2 |
| Breton destrier | 30 s | 11.2 | 44 | 3.1 | 110 | 20 | health 2, carry 1 |
| Alik'r steed | 50 s | 15.0 | 24 | 4.9 | 70 | 10 | stamina 2, speed/jump 1 |
| Auridon white | 35 s | 14.6 | 30 | 5.4 | 80 | 10 | speed 2, jump 2 |
| Arvak | 70 s | 15.0 | 40 | 5.4 | 120 | 10 | all 2 (fixed Fine+) |
| Camel | 65 s | 11.2 | 40 | 1.7 | 200 | 30 | stamina 2, carry 2, health 1 |
| Pack guar | 50 s | 9.9 | 45 | 1.4 | 260 | 30 | health 2, carry 2, stamina 1 |
| Senche-tiger | 32 s | 13.3 | 60 | 5.4 | 120 | 20 | speed 2, health/jump 1, carry -1 |
| Giant wolf | 45 s | 14.2 | 50 | 4.9 | 80 | 10 | stamina 2, speed/jump 1 |
| Indrik | 40 s | 14.6 | 55 | 6.5 | 90 | 10 | speed 2, jump 2 |
| Woolly mammoth | 50 s | 9.5 | 180 | 1.7 | 320 | 30 | health 3, carry 3, stamina 1, speed -1 |
| Akaviri tiger | 35 s | 13.8 | 70 | 5.4 | 110 | 20 | speed 2, health/jump 1, carry -1 |
| Tamed grazer | 45 s | 12.5 | 45 | 3.5 | 150 | 20 | stamina/health/carry 1 |

Stat names follow the kind on cards (camel: Pace, Endurance, Burden; guar: Trot, Hide; cats: Sprint, Pounce, Haul; indrik:
Grace, Bound; mammoth: Stride, Heave; see `mounts.nirncraft.stat.*`). Overweight slowdown is not implemented here.
