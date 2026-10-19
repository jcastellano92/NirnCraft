# Rest: Wait, Sleep and time passing

One system for sleeping, waiting, the rest need, multiplayer time skips and the jail sentence. Code:
`world/RestManager` (server sessions, skips, spawn gate), `world/RestLogic` (the pure rules, self-tested),
`world/RestSim` (townsfolk and world catch-up), `world/client/RestClient` (camera, HUD, input). Selftest:
`/nirncraft-crime selftest` ("rest:" lines).

## What the player does

* **Sleep** = use a bed you may use (a rented room, your bedroll, an unowned bed): **right-click or [F]** (the interact key;
  its crosshair prompt reads "[F] Sleep Bed"). Sneak-use is still vanilla sleeping. Sleeping is the **real vanilla sleeping
  state**: every client draws the lying pose, the hitbox is low, damage wakes. The vanilla "Leave Bed" screen and black-out
  are replaced by our panel while a rest session runs.
* **Wait** = the wait key (`key.nirncraft.wait`, default I; every message shows the real binding) sits the player on the
  ground (a rider on an invisible `Marker`; sneak = stand up). Pressing the key again stands up and cancels a running skip.
* Camera goes third person; the view the player had is remembered and restored on every exit (stand up, cancel, logout,
  death, ESC).
* A non-blocking panel above the hotbar (`HudStack.BC_REST`): hours (left/right or mouse wheel, 1-24; up = until morning,
  down = until evening), Enter starts. Movement keys, sneak, Escape or damage stand the player up; Escape does not open
  the pause menu while resting.
* **Rest bar**: only a bed rests you. Lying in a bed slowly refills the survival fatigue bar (a drained bar full in
  `fullRechargeSeconds`, 120; a bedroll at `bedrollPercent`, 75%) and stops it growing. Waiting gives no recovery and does
  not stop the bar growing (a skipped hour of waiting tires like any awake hour).

## The fast-forward

Time passing is never a jump: the world clock (and game time, see below) advances a little every tick over 3-12 real
seconds (`RestLogic.worldTicksAt`, smoothstep), the sky visibly races, weather ticks as normal, the FOV zooms slowly and a
vignette and progress bar show it. During it, per tick and bounded:

* **Meters** hour by hour (`SurvivalMode.restStep`); at the end Well Rested, spawn at an owned bed, Rested/Lover's Comfort.
* **Townsfolk** near the resting players hop along their real paths towards the anchor their schedule gives
  (`RestSim.hop`, 48 per 10 ticks); at the end (or on a cancel) every loaded scheduled NPC near the group is put at its
  scheduled place (`NpcPlacement.snapToSchedule`: standable cell by the anchor, in bed for sleepers).
* **Plants**: extra random ticks for crops, saplings, stems, cane, cactus, bamboo and farmland in the loaded chunks within
  3 chunks of every player (`RestSim.catchUp`): as many samples per section per tick as the skipped ticks would have given
  (`randomTickSpeed`, capped at 384), only growing blocks (no grass spread). Growth honours the light at that moment.
* **Machines**: furnaces, smokers, blast furnaces, brewing stands and lit campfires near the players are ticked for the
  skipped ticks (fuel burns, items finish). Baby animals grow up and breeding cooldowns run down.
* **Game time**: `advanceTime` moves the overworld game time as well as the day time, so every deadline stored as
  "game time + n" (spell/shout/power/mount cooldowns, rented rooms, hostile pacing, scheduled block ticks and commands)
  runs out exactly as if the time had been waited.
* **Status effects** of every player in the dimension are shortened by the skipped ticks at the end; spent ones expire
  (no retroactive poison/regeneration; Well Rested is applied after, fresh).
* **Jail sentences**: every prisoner's remaining seconds count down by the skipped time (`Jail.passTime`).
* **No hostile spawning**: while a skip runs, no new hostile appears in that dimension; while anyone rests, none within 96
  blocks of them. Gated where new spawns are decided: `MobSpawnEvent` (position check and finalize, for natural, patrol,
  event, reinforcement, triggered and jockey spawns only), `SpawnPacing.hostileReady` (region spawner, wild scenes,
  encounters, ambushes, vanilla replacements) and the dragon attack roll in `CreatureSpawns`.

## Nothing that exists is removed

* There is **no** `EntityJoinLevelEvent` filter: a join event cannot tell a fresh spawn from a world-generation
  entity, a dungeon population or a quest actor (world-generation entities arrive with `loadedFromDisk=false`), so
  cancelling there could lose a mob for good. Dungeon spawners (`SPAWNER`), `CHUNK_GENERATION` and `STRUCTURE` spawns
  are never gated. Chunk loading during a rest cannot lose a mob.
* The rest system never discards, despawns or teleports hostiles, bosses, dormant dungeon mobs, summons or pets
  (`RestSim` only moves scheduled townsfolk: not busy, not hunting, not prisoners, not followers).
* **Live time**: skipped ticks are added to the game time, so every cooldown, room and pacing deadline runs out, but
  anything that **removes a creature on a timer** counts the real clock `RestManager.liveTime(level)` (game time minus
  the ticks rest added, persisted in `nirncraft_rest_rule`): encounters and their summons, wild scenes, region-spawned
  creatures, ambushes, wild dragons, conjured summons. Read and write such deadlines with `liveTime`, never the raw
  game time. Vanilla `checkDespawn` uses tick counters and distance, not game time.
* **Hunters**: hostiles already hunting a resting player when a skip starts have their target cleared and the skip does
  not let them re-acquire it (`LivingChangeTargetEvent`); when it ends they get the same target back. A rest cannot
  START with anything awake and hostile within 16 blocks or anything at all hunting the player within 24 (bandit NPCs
  included); guards and fellow prisoners do not hunt a prisoner in the cell, so a jail cell can always be slept in.

## When resting is not allowed (`RestGate`)

`RestGate.blocked(player)`: dead, in the opening (`IntroFlow.inIntro`), a loading cover (`Prewarm.busy`: fast travel,
carriage, jail transfer), in a conversation (`Dialogue.isTalking`), a scripted scene (`Scenes.playing`), or any state a
system registers with `RestGate.register`. Such a player cannot start or join a rest, a running session of theirs ends,
and no world skip starts (or a running one stops, everybody keeps resting) while anyone in the dimension is in one.

## Waking: disturbances

Any damage, a hostile mob targeting the resting player within 16 blocks, combat, fire, water or being pushed more than
1.5 blocks wakes the player at once, stands them up (they can fight immediately) and, during a skip, stops it for
**everybody** and stands everybody up, naming who was disturbed (`RestLogic.disturb`). Without a skip only that player
gets up and other resting players are told.

## Multiplayer

* Per dimension, time may pass only when **every** player there rests (sleeping or waiting). Excluded: spectators, dead
  players, prisoners and players idle for `afkSeconds` (300).
* Players who are not resting see one HUD line: "<Name> is resting. Press [key] to rest and join (n/m)"; the key waits.
* The **initiator** is the longest-resting player; only they start the skip. If they stand up before it starts (or log out,
  die or change dimension at any time) it passes to the next longest-resting player.
* **Cancel**: not skipping = only that player stands up. Initiator cancels a running skip = it stops for all and everybody
  stands up. Another player cancels = they leave and the skip stops (not everyone rests), the others keep resting.
  Logout/death/dimension change do not stop the skip: the member is dropped and the handover applies.
* Works on an Essential-hosted integrated server and a dedicated one (all logic is server side; the client packet is
  `ENTER mode` / `START hours` / `CANCEL`, validated: combat, enemies, water, fire, air, mounted, open menu, prisoner, bed
  range/ownership/occupancy).

## Vanilla sleeping and the sleep percentage

Vanilla wakes everybody and skips to morning once all players have slept 100 ticks. Our system owns time, so while anybody
lies in a bed the world's `playersSleepingPercentage` is raised to 1000 and put back afterwards (`RestManager.guardSleepRule`;
the original value is kept in `nirncraft_rest_rule` world data and restored at server start after a crash). The "you can only
sleep at night" stop is lifted for resting players (`SleepingTimeCheckEvent`).

## Config (`config/nirncraft-world-common.toml`, `[rest]`)

| key | default | effect |
| --- | --- | --- |
| `syncMultiplayer` | true | false: with more than one player online the world clock never skips; resting restores needs and "Start" is a personal fast-forward (needs and effects only, no world time, no NPC simulation) |
| `allowWait` | true | false disables sitting to wait |
| `allowSleepSkip` | true | false: beds only restore the rest need, no time skip |
| `afkSeconds` | 300 | idle time after which a non-resting player no longer blocks the group (0 = never) |
| `fullRechargeSeconds` | 120 | seconds in a bed to refill a drained rest bar |
| `bedrollPercent` | 75 | bedroll recovery speed relative to a bed (waiting has none) |
| `maxSkipSeconds` | 12 | real seconds of the longest (24 h) fast-forward; 1 h takes 3.5 s |

## Jail

Sleeping in the cell bed after the first minute (`crime/Jail`) is a **personal** fast-forward of the same cinematic
(`RestManager.beginJail`): no hours, no other player needed, the world clock is not touched by the cinematic, and the
release (items back, wake outside the door, `passDays` only when alone) runs when it completes. Cancelling leaves the
prisoner in the cell. Prisoners are excluded from the group requirement; the cell bed never becomes a respawn point.

## Notes and limits

* Sitting keeps the full standing hitbox (a rider cannot be made smaller without mixins). Sleeping has the vanilla low one.
* Not accelerated: dropped items (they do not despawn), NirnCraft's own crafting stations that tick on their own counters,
  entity fire, mob AI. Regional difficulty (chunk inhabited time) rises with the game-time jump, as for any elapsed time.
* `doDaylightCycle` off: a skip still moves the clock (as before).
