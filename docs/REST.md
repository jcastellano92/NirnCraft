# Rest: Wait, Sleep and time passing

One system for sleeping, waiting, the rest need, multiplayer time skips and the jail sentence. Code:
`world/RestManager` (server sessions and skips), `world/RestLogic` (the pure rules, self-tested), `world/RestSim` (townsfolk),
`world/client/RestClient` + `RestScreen` (camera, pose, HUD, input). Selftest: `/skycraft-crime selftest` ("rest:" lines).

## What the player does

* **The wait key** (`key.skycraft.wait`, "Wait / Sleep", default I; every message shows the player's real binding) opens a
  small non-pausing prompt: **Sleep** (enabled only with a bed or bedroll block within 3.5 blocks; the server picks the
  nearest free one) or **Wait** (sit on the ground). Right-clicking a bed lies down in it directly (sneak = vanilla).
  Pressing the key again while resting opens the same prompt to switch or stand up.
* Sleeping lies the player along the bed (a client-side sleeping pose from a `RestPose` packet, centred on the mattress);
  waiting seats them as a rider on an invisible `Marker` (sneak = vanilla dismount = stand up). The camera goes to third
  person; the view the player had is remembered and restored on every exit (stand up, cancel, logout, death).
* A non-blocking panel above the hotbar (`HudStack.BC_REST`): hours (left/right or mouse wheel, 1-24; up = until morning,
  down = until evening), Enter starts. Movement keys, sneak, Escape or damage stand the player up. Escape does not open
  the pause menu while resting (it cancels).
* Time passing is a **fast-forward**, never a jump: the world clock is advanced a little every tick over 3-12 real
  seconds (`RestLogic.worldTicksAt`, smoothstep), the sky visibly races, weather ticks as normal, the FOV slowly zooms and a
  vignette closes in with a progress bar; meters are fed hour by hour (`SurvivalMode.restStep`), the townsfolk near the
  resting players hop along their real paths towards the anchor their schedule gives (`RestSim.hop`, throttled to 48 per
  10 ticks), and at the end (or on a cancel) every loaded scheduled NPC near the group not at its scheduled place is put
  there (`NpcPlacement.snapToSchedule`: standable cell by the anchor, in bed for sleepers). The normal wake-up then plays
  (fade "N hours later", Well Rested, spawn at an owned bed, Rested in a bedroll, Lover's Comfort).
* **Rest need**: lying or sitting slowly refills the survival fatigue ("rest") bar and stops it growing, with no skip:
  a drained bar is full in `fullRechargeSeconds` (120) in a bed, `bedrollPercent` (75) of that speed on a bedroll,
  `waitPercent` (25) sitting. Combat, damage, fire, water or a hostile hunting the player within 16 blocks end it.

## Multiplayer

* Per dimension, time may pass only when **every** player there rests (SLEEP or WAIT). Excluded from the requirement:
  spectators, dead players, prisoners and players idle (no movement or look change) for `afkSeconds` (300).
* Players who are not resting see one HUD line (`HudStack`, no chat, no screen): "<Name> is resting. Press [key] to rest
  and join (n/m)". Pressing the key gives the same Sleep/Wait prompt (join sleeping if a bed is near, else waiting).
* The **initiator** is the longest-resting player; only they start the skip. If they stand up before it starts (or log
  out, die or change dimension at any time) it passes to the next longest-resting player.
* **Cancel**: not skipping = only that player stands up. Initiator cancels a running skip = it stops for all and everybody
  stands up (the time that passed stays, with its effects). Another player cancels = they leave and the skip stops (not
  everyone rests), the others keep resting and may start again. Logout/death/dimension change do not stop the skip: the
  member is dropped and the handover applies.
* Works the same on an Essential-hosted integrated server and a dedicated one (all logic is server side; client packets
  are `ENTER mode` / `START hours` / `CANCEL` and are validated: combat, enemies, water, fire, air, mounted, open menu,
  prisoner, bed range/ownership/occupancy).

## Config (`config/skycraft-world-common.toml`, `[rest]`)

| key | default | effect |
| --- | --- | --- |
| `syncMultiplayer` | true | false: with more than one player online the world clock never skips; resting restores needs and a "Start" is a personal fast-forward (needs and effects only, no world time, no NPC simulation) |
| `allowWait` | true | false disables sitting to wait |
| `allowSleepSkip` | true | false: beds only restore the rest need, no time skip |
| `afkSeconds` | 300 | idle time after which a non-resting player no longer blocks the group (0 = never) |
| `fullRechargeSeconds` | 120 | seconds in a bed to refill a drained rest bar |
| `bedrollPercent` / `waitPercent` | 75 / 25 | recovery speed relative to a bed |
| `maxSkipSeconds` | 12 | real seconds of the longest (24 h) fast-forward; 1 h takes 3.5 s |

## Jail

Sleeping in the cell bed after the first minute (`crime/Jail`) is a **personal** fast-forward of the same cinematic
(`RestManager.beginJail`): no hours, no other player needed, the world clock is not touched by the cinematic, and the
release (items back, wake outside the door, `passDays` only when alone) runs when it completes. Cancelling leaves the
prisoner in the cell. Prisoners are excluded from the group requirement and never block anybody.

## Notes and limits

* Crops, effect durations and jail sentence timers run on real ticks (as before); the survival meters, hunger/fatigue/cold,
  rest effects and townsfolk follow the skipped hours. `doDaylightCycle` off: the clock still moves for a skip (as before).
* The lying pose is a client override (the server keeps the player standing): other players need the mod to see it; the
  hitbox stays standing height.
* Vanilla sneak-use on a bed still starts vanilla sleeping (night only, vanilla skip).
