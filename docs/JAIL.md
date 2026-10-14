# Jail: sentences, escapes and what the jail keeps

Code: `com.skycraft.crime.Jail` (sites, sentence, release, escape, guard reaction), `crime.JailEvidence` (the two stores and
their blocks), builders `tamriel/settlement/build/Jails` (hold jails) and `Jail.buildCell` (the jail dimension's cell
blocks). Self-test: `/skycraft-crime selftest`.

## The sentence

* 30 s per 100 gold of bounty (30 s .. 10 min). A served sentence does **not** end by itself: the prisoner sleeps in the
  cell bed (allowed once the sentence is served, or after the first minute, which serves the rest at once) and wakes
  outside the jail's front door with their things, the hold's bounty cleared, some skill progress lost. Nobody is carried
  back to where the crime happened (`Jail.outside`: a hold jail's exit; for the jail dimension, the exit of the jail
  that serves the hold, else the nearest city jail, else its settlement, else the world spawn).
* Or they break out: the cell door (a lockpick, or the jail key pickpocketed from a guard), the cell block gate, the
  guard room past the guards, and out of the front door; or the drain grate at the end of the corridor (a culvert with a
  hatch behind the jail). Leaving the bounds is an escape: an escape bounty, and guards who saw it attack.
* A guard who sees a prisoner outside the cell (door opened with a key or a pick) walks over; after 5 s, or at 3 blocks,
  he takes the key, puts the prisoner back, relocks the door and adds 30 s. Sneaking and darkness shorten how far he sees.

## What the jail keeps: two stores per jail, in no cell

Both stores are `BulkStore`s (docs/INVENTORY.md) held in `Jail.JailData` (the world's saved data, key `stores` ->
`<jail key>` -> `{tier, confiscated, personal: {<player uuid>: bulk}}`). The blocks are only access points: breaking a
block or unloading its chunk loses nothing, a rebuild never touches contents. Caps are engineering only (1,000,000 kinds,
1,000,000,000 each). Jail key = the hold jail's place id, or `dim` for every cell block of the jail dimension.

| | Evidence chest | Confiscated goods |
| --- | --- | --- |
| Look | a chest, an "Evidence / Your belongings" sign above it | a barrel, a "Confiscated / Stolen goods" sign |
| Whose | each prisoner's own (per UUID) | shared by every prisoner of that jail |
| Holds | everything taken from that player at arrest (or at death in jail) that is not stolen | the stolen goods (`Bounty.isStolen`) taken from any arrested player |
| Lock | never locked, no screen | locked every time (relocked on open); the tier grows with the biggest bounty taken in; a guard's jail key also opens it |
| Use | Interact (F, `SkyKeys.INTERACT`, a block use like a right-click): all *your* things come straight into your inventory (armour to its empty slot, never deleted, spill at your feet only past the pack's cap), message and sound | pick the lock (the normal minigame), then the Skyrim container screen over the store (take/take-all/grouped) |
| Crime | none (they are yours) | taking from it is stealing like from any owned container (`Theft.onOpen` gives it a session): witnessed -> bounty; stolen items keep their mark |

* Arrest (`Jail.send`): quest items and up to 2 skilled lockpicks stay with the player; everything else, slots, armour,
  offhand and pack, is routed by `JailEvidence.route` (stolen -> shared store, else the player's own). Dying in jail does
  the same with the drops and the pack cargo.
* Release by sleeping returns the player's own things automatically (`returnPersonal`); stolen goods stay in the jail.
  An escape without the things leaves them in the evidence: sneak back in and use the chest.
* Placement. Hold jail (`Jails.guardRoom`): the chest by the west wall and the barrel by the east wall of the guard room,
  at z 1 inside the front door, guards' posts at z 3: reachable by sneaking past the guards, and in no cell. Anchors
  `JAIL_PERSONAL` and `JAIL_CONFISCATED`. Jail dimension: the evidence room (x 1..4, z 1..2) behind a locked iron door of
  each cell block, chest at (1,1,1), barrel at (4,1,1).
* Older worlds: jails built before this had one double chest per cell in the guard room (anchors `JAIL_EVIDENCE`, two
  per cell; the jail dimension's at (2..3,1,1)). On first access (a sentence start, or a click on one of them) their
  contents move into the stores (the owner from `chestOwner`; unknown owner or stolen -> the shared store), the first
  chest becomes the evidence chest and the third the barrel (jails with no new anchors), the others are removed.
  `JailData.held` (the older per-player overflow list) is drained into the stores the same way when the player is next
  released or uses an evidence chest; the `legacy` bucket is read by every jail.

## Multiplayer

Per-UUID stores: two prisoners in the same jail share only the confiscated goods (by design: it is the jail's); nothing of
one's evidence is ever reachable by the other (`JailEvidence.takePersonal` takes exactly one UUID's). Cells are claimed per
player (`JailData.occupied`); the jail dimension gives each prisoner a cell block of their own but one set of stores.
The store's own change counter drives the container sync, so two players opening the goods see each other's takes.

## Interacting

Everything in a jail is used with the **Interact** key (F by default, `SkyKeys.INTERACT`; the signs show the player's own
key through a keybind component, never "right-click"). The client turns the key into `gameMode.useItemOn` on a block or
`interact` on an entity (`InventoryClientEvents.onClientTick`), which the server sees as the normal
`PlayerInteractEvent.RightClickBlock`/`EntityInteract` with the main hand: the evidence chest, the confiscated barrel
(and its lockpick minigame), the cell and evidence-room doors and the bed are all handled in those events and none of
them looks at the held item or at sneaking (the handlers cancel the event before vanilla's "sneaking with an item skips
the block" rule). Pickpocketing is sneak + Interact on the guard.

## The jail key

A guard of a jail carries a jail key in his pockets (`Pickpocket.generate`; refilled daily for a jail's guards). It is
the jail's property (tag `skycraft_jail_property`, the tooltip says so), **not** stolen goods (`Pickpocket.take` does not
mark it, so nothing confiscates or retrieves it) and it is never consumed: it opens the cell door, the cell block gate,
the evidence room's iron door and the confiscated barrel, any number of times (`Locks.blocks`: only inside a jail
area, so it opens nothing in the town). It stays in the inventory until the prisoner leaves the jail for good
(`Jail.recoverKeys`): released by sleeping, escaped out of the exit or the drain, or arrested again. Being dragged back
into the cell does not take it (the guard sees you, he does not search you).

## Rounds (guards on patrol)

A hold jail has two patrolling guards (`JailPatrol`, spawned by the jail when missing, tagged `skycraft_jail_patrol`),
the jail dimension's cell block one. Their stop is a pure function of the game time, so the pattern can be learnt: a slot
is 10 s, the round 8 slots (80 s); guard 2 walks the same round two slots later.

| Slot (10 s) | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Guard 1 | corridor end | by the gate | guard room | outside the front door | outside | guard room | by the gate | corridor end |
| Guard 2 | guard room | outside | outside | guard room | by the gate | corridor end | corridor end | by the gate |
| Cell block corridor watched | yes | yes | **no** | **no** | yes | yes | yes | yes |
| Guard room watched | yes | no | yes | yes | no | yes | no | no |

* **Gap in the corridor: 20 s** (slots 2-3): both guards are in the guard room or outside. Break out of the cell and
  sneak to the gate then. **Gaps in the guard room: 10-20 s** (slots 1, 4 and 6-7). The gate and the front door are
  opened by a guard who has to cross them (and shut again behind him), so a prisoner can slip through behind a guard.
* A guard "watches" a place when he stands in it with line of sight, by the module's rules (`Crimes.findGuardWitness`):
  range 20 m times the target's visibility, **sneaking** shortens it (x0.8 visibility, and half again in the dark), a wall
  or a closed door blocks the line.
* **Hearing:** sprinting outside the cell (within 10 m) and picking any lock in the jail (within 8 m) send the guards
  nearby to the spot (`JailPatrol.noise`); they still have to see you to act.
* A guard who sees a prisoner outside the cell leaves his round, walks up and, after 5 s or at 3 blocks, drags the
  prisoner back: the cell door is locked again, +30 s on the sentence (`Jail.guardReaction`). A prisoner who leaves the
  jail's bounds has escaped (`Jail.escape`: escape bounty, guards who saw it attack).
* **Jail dimension** (one corridor guard per cell block): near the player's cell window (z 6) for 30 s, the middle for 10 s, then
  the far end (z 21) for 30 s. From the cell door (z 4) the far end is 17 m: a sneaking prisoner (visibility x0.8,
  range 16 m) is not seen, a walking one is; the evidence room's door (z 3) is 18 m.

## Which jail, and loading it

* **Which settlement.** A crime in a hold is served by (`JailLoading.placeFor`): the guardhouse of a minor town of that
  hold within 300 blocks, else the hold's capital, else the nearest major jail of the region, else the nearest jail within
  1500 blocks. Every capital, city and **major** town (`GuardWatch.of(p) == MAJOR`: towns of radius 90 or more) keeps a full
  jail; every **minor** town (`MINOR`) keeps a **guardhouse** (`Jails.guardhouse`, 13 x 13, one low building above ground:
  guard room with the evidence chest and the confiscated goods, an iron gate, a one block corridor and one to three barred
  cells in a row, anchors as a hold jail's). `Halls.hasJail` / `Halls.guardhouse` are the rule; the plan needs a re-bake
  (`qa.ps1 -Server -Rebake`) to carry the guardhouses of towns that had no jail before.
* **Not generated yet.** The prisoner is **not** sent to the jail dimension. `JailLoading.begin`: they stay where they are,
  in spectator mode (unseen, nothing collides with or targets them, nothing hurts them), held in place every tick, rides
  and screens dropped; the opening's opaque loading cover ("Taken to the gaol" + the city, `quest.client.LoadingCover`,
  the same painter as "The valley wakes") shows the chunks ready of needed, at least 6 s like the opening's. The server
  prewarms the settlement's start chunk (it records the anchors of the pieces it holds), the start chunk of the district
  that holds the jail piece and the jail's own chunks (`Prewarm.start(player, points, ...)`, from the baked street plan,
  the place's middle without it); when the anchors exist and the cell's chunks are built, the prisoner is put in the cell
  and the sentence runs as ever. After 60 s without it (`JailLoading.TIMEOUT_TICKS`) the jail dimension serves.
* **State and rest.** `crime.jail_pending` is saved with the player: a relog or a restart resumes the load.
  `Jail.isLoadingJail(player)` is the predicate other systems (rest/wait) call: no time skips while it is true.
  A generated jail whose cells are all taken is not loaded again: the dimension serves the overflow as before.

## Visitors

A player who is not under arrest may walk into a jail's front hall (the first 3 blocks behind the front door, with the
evidence chest and the confiscated goods) without penalty, guards do not react. The rest of the guard room, the gate and
the cell block are for guards: a guard who sees a visitor there tells them to leave (`crime.skycraft.jail.visitor_warn`),
and a visitor who is still there 5 s later is trespassing (`Bounty.TRESPASS` through `Crimes.report`: a bounty only if
someone sees it). `JailVisitors.visitorStep` is the rule. A night challenge is not implemented.

## Talking through the bars

Every cell wall has a pane-free one-block grate window by its door, so a line runs both ways; the server lets a player
interact with an entity he has no line of sight to within 3 blocks, so talking and pickpocketing through the bars work
from the corridor. NPC prisoners talk like any NPC and have two extra options (`JailVisitors.addOptions`): a rumour (what
the guards do, where the key hangs) and what they are in for. A player prisoner is talked to in normal chat; items can be
passed through the grate window by tossing them (a dedicated give-through-the-bars interaction is not built).

## Jailbreak

* Opening a cell door as a free player, by lockpick (the door's tier), by a guard's key, or because it is unlocked, frees
  what is in the cell (`JailVisitors.freed`, from `Jail.onRightClickBlock` and `Locks.unlock`). An NPC prisoner slips away.
  A player prisoner is simply out of the cell: guards that see them re-arrest them (`Jail.guardReaction`), and leaving the
  jail's bounds is an escape with its bounty (`Jail.escape`).
* A rescuer a guard saw gets an **aiding-escape bounty** (`JailVisitors.AIDING_ESCAPE`, 150) and the freed prisoner +60 s
  on the sentence; unseen, nothing is reported.
* **Guards can be dealt with silently.** A guard cut down by a blow that kills, with no other guard or civilian seeing, is no
  assault and no murder report (`Crimes.onHurt` / `onDeath` already judge murder by witnesses). Nothing happens until a
  patrolling guard comes within 10 blocks of the body with a clear line: then the jail goes on **alert** for 3 minutes
  (`JailPatrol.findBody`): the patrols converge on the body, the front door is shut and locked (EXPERT), the players in the
  jail are told. A killed guard is replaced after 3 minutes, not at once.
