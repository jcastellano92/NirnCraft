# The player hold

Owner requirement (docs/ATLAS-PLAN.md section 0.9): in single player and on servers the player is not placed in play until
the client's loading is truly complete. Until then they are invisible to others, untargetable, invulnerable, frozen and
unable to interact. Only then does the game continue: the character select on a first join, or the return to the last
position. If loading fails or times out the player stays held, with a message and a Retry or an explicit fallback, never
silently released.

## Flow

1. **Login** (`PlayerDataEvents.login`): `PlayerHold.beginOnLogin` holds the player where vanilla put them (their saved
   position; a new character is already in the cottage, placed before the connection by `IntroFlow.onLoadPlayer`) and sends
   `HoldPackets.Held(true, token)`. The login's second half (`PlayerDataEvents.afterLoading`: first-join placement and the
   opening's state, which is what opens the character screen on the client, or the resume of an unfinished opening) is the
   hold's release action and does not run yet.
2. **The client** (`AtlasLoadingScreen`, opened as soon as `HoldClient.held()`): shows the real loading phases and, every
   quarter second, asks `AtlasReady.pending()` (pipelines built, spawn area in the grid, coarse bake loaded, grid settled).
   When it is empty it sends `HoldPackets.Ready(token, false)` (again every 2 s until the release arrives).
3. **The server** (`PlayerHold.onReady`): checks the token and releases: real game mode, abilities, gravity and visibility
   back, tracked and listed again, `Held(false)` to the client, then the release action runs (character select / last
   position). The client closes its screen on `Held(false)`.
4. **Failure or timeout**: atlas off by a failure (`AtlasReady.offForSession()`; a lost device, no memory, a crash guard)
   or 180 s without ready: the screen shows the reason and offers **Retry** (atlas starts again after a transient failure,
   the timer restarts) and **Enter anyway** (enabled once vanilla's terrain around the player is in; sends `Ready(token,
   true)`; logged as a WARN on both sides). Esc opens the game menu (leave). Atlas off by the player's own choice
   (`-Dnirncraft.atlas=false`, no shader pack and no compositor) is ready at once: there is nothing to wait for.
5. **A client that never reports** stays held (the server logs "still held" every 30 s). An operator can release
   knowingly: `/nirncraft-hold release <player>`; `/nirncraft-hold status`, `/nirncraft-hold hold <player>`.
6. **Leaving while held** restores the real state before the player data is saved; the real mode, gravity and invisibility
   are also kept in the player's persistent data (`nirncraft_hold`) so a crash cannot leave a spectator on disk.

Single player is the same path through the integrated server. While held the loading screen does not pause the integrated
server (the hold protects the player, and the same code runs in multiplayer).

## What holds, and by which public API

| behaviour | mechanism |
|---|---|
| not sent to other players, not in the world | spectator game mode: vanilla's `ServerPlayer.broadcastToPlayer` is false for non-spectator viewers; the tracker is refreshed with `ServerChunkCache.move(player)`; `setInvisible(true)` besides |
| not in the tab list | `ClientboundPlayerInfoRemovePacket` to every other player and to anyone who joins meanwhile; `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing` on release |
| untargetable | `LivingChangeTargetEvent` cancelled (any mod's mobs); vanilla `Mob.asValidTarget` and `canBeSeenAsEnemy` already refuse spectators |
| invulnerable | `Abilities.invulnerable`; `LivingIncomingDamageEvent` cancelled for the held player as victim and as attacker |
| frozen: no movement, gravity, falling, drowning | `flying`/`mayfly` off, `setNoGravity`; position pinned every tick in `PlayerTickEvent.Pre` (teleport back), velocity and fall distance zeroed, air supply refilled, fire cleared; `EntityTeleportEvent` and `EntityTravelToDimensionEvent` cancelled |
| cannot interact | `Abilities.mayBuild` off; `PlayerInteractEvent.RightClickBlock/LeftClickBlock/RightClickItem/EntityInteract`, `AttackEntityEvent`, `BreakBlockEvent`, `BlockEvent.EntityPlaceEvent` and `CommandEvent` cancelled |
| triggers nothing | spectators are skipped by pressure plates (`EntitySelector.NO_SPECTATORS`), by mob spawning (`ChunkMap.playerIsCloseEnoughForSpawning`) and by aggro; `noPhysics` (no collisions) |

## Not possible without mixins (documented, not hacked)

* **The "player joined the game" chat line** and the server-list player count are produced before any event we can cancel.
* **Another spectator** still sees a held player (`broadcastToPlayer` is true for spectator viewers).
* **A tripwire** scans entities without `NO_SPECTATORS`; a spectator is `noPhysics` and pinned, so it cannot walk into
  one, but a player who logged out standing inside a tripwire is still inside it.
* **The player's chunk ticket stays** (vanilla gives spectators chunk loading unless the `spectatorsGenerateChunks`
  gamerule is false, which we do not change): the held player is pinned at the login position, so nothing beyond the
  spawn area loads on their behalf, but the area itself is kept as the loading needs it.
* **Mod systems that tick every player** (about 60 `PlayerTickEvent` subscribers: vitals, survival, crime ...) are not
  paused one by one; they see a spectator who is invulnerable, still and invisible. `PlayerHold.isHeld(entity)` is the
  query for any system that must skip a held player explicitly.
* A modified client that skips the hold screen can still send `Ready` at once: the client is the authority on its own
  loading, the server on what a held player may do.

## Tests

* `nirncraft-hold selftest` (headless, stand-in players; in tools/qa/qa.ps1's server run): hidden from a viewer, mob cannot
  target, no damage, attacks / breaking / teleports cancelled, pinned after being moved, a hold with no ready stays, a stale
  token is ignored, the right token releases once and restores everything, a crash record restores the real mode.
  A control for each check (before the hold the zombie can target, the damage lands, the viewer is sent the player).
* Live, on the real client (`tools/harness/hold_tools.py`; suite `suite:hold`): `hold-returning` (held at login, released at
  the same place, return to last position only after; a mid-session hold likewise), `hold-never-ready` (a client that never
  reports stays held; a husk beside the real player: no target, no damage, not sent to another player, not moved; leaving
  restores the real state), `hold-first-join` (the opening kept: the character select only after the release). The
  harness' own autotest skips the character screen (`ClientAutotest.automated`), which is why the first-join test uses
  `-Dnirncraft.autotest.intro=true`; the harness waits behind `AtlasLoadingScreen` like any loading screen and its setup
  (spectator, opening skip) runs after the release.
