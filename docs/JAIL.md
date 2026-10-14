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
| Use | right-click: all *your* things come straight into your inventory (armour to its empty slot, never deleted, spill at your feet only past the pack's cap), message and sound | pick the lock (the normal minigame), then the Skyrim container screen over the store (take/take-all/grouped) |
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
