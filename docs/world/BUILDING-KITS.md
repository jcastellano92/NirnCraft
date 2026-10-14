# Building kits: trade requirements from other systems

Notes that other workstreams hand to the settlement building kit (TO2). The kit owns how a building looks; this file
only records what a trade needs from it. Add a section per trade.

## builder (homestead services)

Owner requirement: every major settlement (capital, city, large town) has a house person who sells the founding item
(drafting table), deeds, upgrades and expansions, and the builder's shop should look unique and special like the other
planned shops. Logic is in `com.skycraft.homestead` (`HomesteadDialogue`, `HomesteadBuilders`); see `docs/HOMESTEAD.md`.

| Need | Value |
| --- | --- |
| Trade id | `builder` |
| Where | every capital, every city, every town with `SettlementPopulation.tier <= 1`; not villages or farms |
| Anchor | a new `AnchorType.BUILDER` (**append** to the enum: ordinals are saved), one per such settlement, behind the shop counter or at the drafting desk |
| Person | spawn from the anchor a merchant of the mason trade (`VillagerProfession.MASON`, `Shop.KEY.pools = ["mason"]`, level 3) and set persistent data `skycraft_builder = true` and `skycraft_trade = "builder"`; name `homestead.skycraft.builder.title` ("%s, Builder"). `SettlementPopulation.merchant(...)` already does the rest; the tag is what makes the dialogue offer homestead services |
| Interim | `HomesteadBuilders.ensureBuilder` spawns that same person at the market bell when none stands in a recorded major settlement. When the kit spawns one from the anchor, nothing else needs to change (the spawner finds the tag and stays quiet) |
| Equipment inside (trade symbol) | an actual drafting table (`skycraft:drafting_table`) at the counter, a stack of sawn logs and quarried stone (`homestead` items) on display, a rack of rolled plans (lectern + paper/banner), a chest of nails and fittings, a scale model of a house on a table (stairs, slabs, spruce); forge/anvil are NOT needed |
| Exterior | a timber yard: stacked log piles, a crane or scaffold of fences and beams, a lean-to saw pit, stone blocks under a tarp (wool); a sign with a hammer and set-square (banner pattern). Must read as "builders" from the street, unlike the smith's chimney or the apothecary's herbs |
| Size | like other shops: at least the 99 interior cells target of T7 in `docs/world/TOWN-AUDIT.md` (a counter, a drafting area, storage) |
| Palette | the settlement culture's timber and stone; Nord/Imperial heavy timber, Dunmer ash-stone and bone scaffolds, Redguard sandstone and awnings (culture rules of the kit apply) |
| Stock | the mason's pool (`#skycraft:merchant/mason`) plus the homestead materials the dialogue sells (sawn logs, stone, clay, nails, from `Hearthfire.OFFERS`) |

Also wanted from TO3 (leader's seat): a steward desk in every seat; `skycraft_steward` people in capitals, cities and
large towns are treated as builders as well (`HomesteadDialogue.isBuilder`).
