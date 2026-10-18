# VT3 Volume pass (repeat for volumes 01-12)

Read `docs/prompts/_COMMON.md`, `docs/world/VISUAL-TARGETS-PLAN.md` (sections 2, 3, 4.7-4.9, 10) and the workflow
first. One volume at a time per region set; several agents may work inside one volume, each on its own claimed task.
`<vol>` is the two-digit volume number.

## Steps (board tasks `VT3.<vol>.*`, in dependency order)

1. **Ingest** (`.ingest`): `ingest_atlas.py --volume <vol>`; confirm every plate's region and place ids.
2. **Targets and bible** (`.targets`, `.bible`): one target per plate (traits on every axis, probes, rungs, weights,
   `must`, `why`); the culture design bible for the volume (materials, shapes, roofs, motifs, colours, clutter, what
   is never used, interiors by building type); plate-vs-world resolutions (`builder`, `plate_off`, `blend`) with
   reasons. Owner reviews the bible and the `must` traits.
3. **Gap report** (`.gaps`): `gaps.py`; open one board task per gap (or attach it to an existing VT2 knob).
4. **Site plans** (`.city.<id>`, `.dungeon.<id>`, `.cave.<id>`): design the working layout from the plate and our
   building types (plan V1b): districts, streets, gates, tiers and stairs, water, all services the place needs,
   interiors from the exterior and function; grow the place when gameplay needs it. Write the cityplan/dungeon/cave
   plan, stamps and water records, and a plan note. Prove with L1 probes; compare the L1 render with the plate.
5. **Caves and contents** (`.cave.<id>`, `.lore.<id>`): caves and underground areas per the cave grammar, each with a
   purpose and story, placed enemies and loot, and its ties into the region (rumours, notes, books, quests, NPCs).
   New lore is ours; canon facts stay; nothing copied from other fan projects.
6. **Biomes and sub-biomes** (`.biome.<id>`): palettes, micro rules, flora, looks, landform via vectors; L2 censuses.
7. **Derived targets** (`.derive`): `derive.py` for every place without a plate (settlement ladder, roadside, cave
   kinds, landmarks, item families, moods interpreted from the plates); uniqueness gates (G1, neighbour difference).
8. **Sweep** (`.sweep`): `sweep.py --province`; failures become tasks.
9. **Capture and sign-off** (`.capture`): one broker batch for the volume's plate targets (shaders on and off);
   `score.py`; the owner signs off the volume sheet; WORLD-PLAN state table and START-HERE updated.

## Exit

Every target `done`; every derived target `met` at L1/L2; G1 >= 80%; owner sign-off recorded in the volume's
`STATUS.json`.
