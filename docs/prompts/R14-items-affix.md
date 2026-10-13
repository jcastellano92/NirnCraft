# R14 ITEMS AFFIX

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R14-items-affix.md`." One agent. Free-slot work: may run
> any time after R3a.

## Goal

Skyrim-faithful item variety that also builds the world: an item says where it was made and what it has been
through. Found-item condition and quality, cultural makers from the region of the roll, Skyrim-style enchantment names
by tier, rare storied items with a one-line provenance; all compatible with tempering and enchanting, all inside the
loot value bands.

## Context to read

* `docs/FINISH-PLAN.md` section 5 (the original ITEMS AFFIX brief), `docs/LOOT.md` (tables, value bands),
  `docs/FEATURES.md` (crafting, tempering, enchanting).
* `docs/world/DESIGN-PRINCIPLES.md` first principle (makers cite L5 crafts and L11 history), 4.2 (signature gear).
* Code: `loot/**`, `arsenal/**` (`LeveledGear`), `crafting/**` (tempering), `magic/**` (enchanting), the loot function
  hooks for containers, corpses and merchants. The old worktree `agent-a2563a6b1f0ded80b` holds nothing usable.

## Write set

`mod/src/main/java/com/skycraft/loot/**` (an `affix` sub-package), `arsenal/**` (tooltip and stat hooks, additive),
`crafting/**` and `magic/**` (only the lines that carry affix NBT through tempering and enchanting),
`resources/skycraft/loot/affixes/**` (new data: qualities, makers per culture with sheet cites, name tiers, storied
name parts), `lang/skycraft/items_affix.json` (new keys), `tools/loot_sim.py`, `tools/audit_items.py` (affix checks).

## Steps

1. **Condition and quality.** Worn, Rusted, Notched, Well-made, Masterwork (and culture-fitting synonyms), stored in
   NBT, shown in the tooltip, as stat and value modifiers.
2. **Makers.** Skyforge, Orsinium-forged, Dwemer-wrought, Ayleid-etched, Redguard-folded, Bosmer bone, Akaviri, and
   one per province where lore has a craft; chosen from the region of the roll and its sheet's L5 crafts; each maker
   cites the sheet field (`why`).
3. **Enchanted names by tier.** "of Burning / Scorching / Searing / Blazing / the Inferno" and the same ladder for
   every effect family; consistent with the enchanting UI.
4. **Storied items.** About 5% of boss chests: a culture name part plus a one-line provenance in our own words, citing
   the region's L11; never two identical in one world (hash of place id and slot).
5. **Hooks.** Containers, corpses, merchants (lower rate); tempering and enchanting keep the affix NBT.
6. **Tools.** `/skycraft-items roll <table> [level]`; `loot_sim.py` simulates value curves with affixes;
   `audit_items.py` checks names and data.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Value bands | `py -3 -I tools/loot_sim.py` | every curve inside `docs/LOOT.md` bands |
| Items audit | `audit_items.py` | 0 errors |
| Survives crafting | new `affix` selftest in `qa.ps1 -Server` | temper and enchant keep quality, maker and name |
| Names | `novelty_lint.py --strict` on item names | 0 pairs |
| World cites | `worldsheet_lint.py` | every maker and storied part resolves |
| QA | `qa.ps1 -Server -Rebake` | PASS |

## Merge and report

`_COMMON.md` section 7. Report per section 8.

**Size:** M.
