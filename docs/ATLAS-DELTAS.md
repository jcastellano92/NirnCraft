# Infinite ATLAS: the change feed (far deltas)

What atlas draws past the client's own chunks starts as a prediction: exact heights, the world's material rules, town
plans and tree placement, all from the seed, built into tiles once per world (and cached). What the prediction cannot
know arrives as **changes**: small, sparse, pushed by the server only to the players who can see them, before they
see them. (The far field, below, is the code's name for that part of atlas: AtlasFarField, its worker and tiles.)
The client-owned world replaces this with coalesced, position-based operations, hash-checked
([ATLAS-STREAMING.md](ATLAS-STREAMING.md), "Client-owned world"; [ATLAS-STATUS.md](ATLAS-STATUS.md) standing rules).

## What changes

| source | how it is noticed | what reaches the far field |
|---|---|---|
| players building, digging | the block change (any `setBlock` that notifies neighbours) | the column's new top and the block under it |
| explosions, fire, the volcano, landslides | the same block changes | the same (many columns at once, coalesced per tick) |
| flowing water, lava | the same | the column's water top |
| light emitters placed / removed / lit | the block change, by its light emission | a far light (fire, lamp) added or removed |
| chunk generation (what the prediction missed) | a newly generated or loaded chunk | every column as built (terrain, buildings, props, the real trees), once per chunk content (a hash per chunk), and its sections (AtlasSections: deep solid rock left out), re-recorded when a kept block changes (AtlasEdits.RESECTION) |
| entities | already streamed (FarActors, every 10 ticks) | impostors; same windows |
| weather (later, once positional and deterministic) | the weather field's own versions | field patches in the same windows |

## The journal (server)

Per overworld tile of 256 x 256 blocks (LOD 0's tile), a journal of changed columns: `(x, z) -> top y, top block, under
block, water top, version` and changed lights `(x, y, z) -> level, colour, version`. A tile's version counts its
changes. Changes are coalesced per tick (an explosion is one batch). The journal is saved with the world
(`data/nirncraft_atlas/edits-v3.dat`, the sections beside it in `sections/`), so a server restart, a client reconnect
or a new client gets the same far field.

## Who gets what (interest)

Each player has a window of 21 x 21 LOD-0 tiles around them (`AtlasEdits.RADIUS_TILES` 10: the LOD 2 window, about
5 km square). Twice a second the server compares each player's window with what it has already sent them
(`tile -> version sent`) and sends only newer changes of tiles in the window. A player arriving somewhere (joining, teleporting, flying) gets the changes of every
tile entering the window at once, ahead of the terrain they will see; a change happening anywhere in the window
reaches them within half a second. LAN hosts and single player run the same path (the host is a player too).

Far tiles coarser than a block show a change when it is big enough for their column size (a house is invisible at a
64-block column, a crater is not): the client patches the columns that cover a change at each resident level.

## Applying (client)

Changes are kept per tile; a tile arriving later (from the cache, the network or the pre-build) is patched before it
goes up; a tile already resident is patched and put up again on the far-field worker (its refinement fade blends it
in). Lights go into a light group per tile.

## Cost

A change is a few bytes per column; a packet carries a tile's new changes since what that player has; nothing is sent
for tiles nobody can see; whole tiles are never re-sent for an edit. The far field's own tile cache stays the
seed's prediction; the journal is the truth on top.

## Status

1. Journal, interest, push, client patch of resident tiles and lights: done (atlas-render; live: a 70-block column
   built 500 m away showed within about 15 s, ATLAS-RENDERER-PLAN.md "R5 far edits live").
2. Chunk-generation corrections: the server's chunks as built, columns and sections (done).
3. Entities' windows unified with tiles' (FarActors keeps its own 10-tick sender for now).
4. Weather patches, after the weather rework.
5. Edits as operations with the hash tree, the client-owned world's form: parked world work (ATLAS-RENDERER-PLAN.md).
