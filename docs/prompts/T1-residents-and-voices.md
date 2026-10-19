# T1 — Wire All 12,709 Named Residents (`*_residents.json`), 75 Regional Voices & Cultural Conversations

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Ten JSON files under `mod/src/main/resources/nirncraft/society/*_residents.json` define **12,709 authored named NPCs across 446 settlements** (`skyrim_residents.json`, `morrowind_residents.json`, `cyrodiil_residents.json`, `high_rock_residents.json`, `hammerfell_residents.json`, `valenwood_residents.json`, `summerset_residents.json`, `elsweyr_residents.json`, `black_marsh_residents.json`, `foreign_residents.json`), complete with `id`, `name`, `place`, `region`, `role`, `home`, `work`, `schedule`, `greet`/`bark`, `talk`, `secret`/`problem`, and `why` (`WorldSheets` citations). Currently, **zero Java classes read `*_residents.json`**, and `SettlementPopulation.java` falls back to procedural names (`NpcNames.generate`) and a 10-name Nord innkeeper list (`FALLBACK_INNKEEPERS`) for all 600+ non-Skyrim inns. Furthermore, `region_voice.json` and `ContextualDialogue.SKYRIM_HOLDS` only resolve region-level voice/deed lines for the 9 Skyrim holds + Solstheim.

`T1` wires all 12,709 authored named residents into live settlement spawning, expands `region_voice.json` and `ContextualDialogue` deed reactivity to all **75 land regions**, and expands `Chatter.java` to **>= 6 multi-turn cultural conversation topics per province and >= 2 per region**.

## 2. Write Set

* `mod/src/main/java/com/nirncraft/society/SocietyResidents.java` (new loader & index)
* `mod/src/main/java/com/nirncraft/tamriel/settlement/SettlementPopulation.java`
* `mod/src/main/java/com/nirncraft/society/Housing.java`
* `mod/src/main/java/com/nirncraft/society/RegionVoice.java`
* `mod/src/main/java/com/nirncraft/society/ContextualDialogue.java`
* `mod/src/main/java/com/nirncraft/society/Chatter.java`
* `mod/src/main/java/com/nirncraft/society/SocietySelftest.java`
* `mod/src/main/resources/nirncraft/society/*_residents.json`
* `mod/src/main/resources/nirncraft/npc/region_voice.json`
* `mod/src/main/lang/nirncraft/society*.json`
* `tools/qa/population_lint.py`

## 3. Implementation Steps

1. **`SocietyResidents.java` Loader:**
   * Load all `nirncraft/society/*_residents.json` files from classpath (and support reload via `/nirncraft-dev reload all`).
   * Index residents by `place` (`settlementId`) and `role` (`NpcRole`), preserving `id`, `name`, `title`, `race`, `female`, `role`, `home`, `work`, `schedule`, `greet`/`bark`, `talk`, `secret`/`problem`, and `why`.
2. **`SettlementPopulation.java` & `Housing.java` Integration:**
   * When populating a settlement (`SettlementPopulation.populate()`), match `Housing.Plan` jobs (`INNKEEPER`, `BLACKSMITH`, `MERCHANT`, `APOTHECARY`, `PRIEST`, `MAGE`, `SCHOLAR`, `BARD`, `FARMER`, `MINER`, `GUARD`, `CITIZEN`, etc.) against `SocietyResidents.forSettlement(place.id())` before falling back to procedural names.
   * Stamp `NirnCraftResidentId`, `NirnCraftWhy`, custom `greet`/`bark`, `talk`, and `secret`/`problem` onto the spawned `NpcEntity`'s persistent data so `NpcDialogue` and `ContextualDialogue` surface their authored lines.
   * Replace the 10-name Nord `FALLBACK_INNKEEPERS` list in `SettlementPopulation.innkeeperName()` with `SocietyResidents` lookup + culture-appropriate fallback names from `NpcNames.generate(Cultures.primaryRace(place.culture()), ...)`.
3. **75-Region `region_voice.json` & `ContextualDialogue.java`:**
   * Populate `mod/src/main/resources/nirncraft/npc/region_voice.json` `"regions"` for all 65 non-Skyrim land regions (and add `"pyandonea"` and `"thras"` to `"provinces"`).
   * In `ContextualDialogue.java` (`lines 72–75`), remove the `SKYRIM_HOLDS`-only check so `react.<region>.<deed>` resolves for all 75 land regions, and add region-keyed deed lines across `mod/src/main/lang/nirncraft/society_*.json`.
4. **Multi-Turn Cultural Conversations (`Chatter.java`):**
   * Expand `Chatter.java` (`lines 78–115`) so every province has `>= 6` multi-turn town conversation topics and every region has `>= 2` regional topics grounded in its `WorldSheets` L5–L11 fields.

## 4. Verification Gates

* `py -3 -I tools/qa/population_lint.py` -> `0 errors`, verifying all `12,709` residents across `10` files resolve valid roles, places, and `why` fields.
* `SocietySelftest` -> verifies `SocietyResidents` loads `>= 12,000` residents across all 10 files, spawns authored residents with their exact names/roles/barks in non-Skyrim settlements (`balmora`, `anvil`, `daggerfall`, `sentinel`, `alinor`, `elden_root`, `rimmen`, `stormhold`), and resolves `region_voice.json` for all `75/75` land regions (`0` `region.unknown`).
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
