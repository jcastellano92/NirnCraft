# T6 — Provincial Factions, Hold/Region Race & Religion Attitudes (`RaceAttitudes.java`), & Dynamic World-State Encounters

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **`Reputation.FACTIONS` (`Reputation.java:39-42`) only tracks 12 Skyrim factions**, with no reputation tracking for Morrowind's Great Houses (`Redoran`, `Telvanni`, `Hlaalu`, `Indoril`, `Dres`), `Ashlanders`, `Morag Tong`, `Tribunal Temple`, Hammerfell's `Crowns` & `Forebears`, Black Marsh's `An-Xileel`, `Fighters Guild`, `Mages Guild` / `Synod`, Summerset's `Psijic Order`, Elsweyr's `Mane Clergy`, or Valenwood's `Green Pact`.
2. **`Relationships.disposition()` (`Relationships.java:76-83`) ignores player and NPC race, hold politics, and religion**, so every hold and province treats every race identically (e.g., Windhelm treats Dunmer and Argonians the exact same as Solitude; Telvanni wizards treat Outlanders the same as House kinsmen; non-Orcs walk into Orc strongholds with no Blood-Kin check).
3. **Random road/wilderness encounters (`Encounters.java`, `WildScenes.java`) do not react to player faction reputation, completed regional story quests, or racial/political standing.**

`T6` implements all provincial factions, the `RaceAttitudes` hold/region/faction/race/faith disposition matrix, and reputation-reactive world encounters.

## 2. Write Set

* `mod/src/main/java/com/nirncraft/society/{Reputation,Relationships,RaceAttitudes,ContextualDialogue,Encounters,SocietySelftest}.java`
* `mod/src/main/java/com/nirncraft/economy/PriceEngine.java`
* `mod/src/main/java/com/nirncraft/creatures/spawn/WildScenes.java`
* `mod/src/main/resources/nirncraft/society/{factions,race_attitudes}.json` (new)
* `mod/src/main/resources/nirncraft/creatures/scenes.json`
* `mod/src/main/lang/nirncraft/society*.json`

## 3. Implementation Steps

1. **Provincial Factions (`Reputation.java`, `resources/nirncraft/society/factions.json`):**
   * Register all provincial factions in `Reputation.FACTIONS` and map each of the 75 land regions and 446 settlements to its ruling faction(s), allied factions, rival factions, and dominant faith in `factions.json` (citing `WorldSheets` L6–L9).
2. **`RaceAttitudes.java` & `resources/nirncraft/society/race_attitudes.json`:**
   * Build `RaceAttitudes` evaluating `(regionId, settlementId, npcRole, npcRace, playerRace, playerReputation)` to return:
     * `dispositionDelta` (`-35` to `+30`),
     * `priceMultiplier` (`0.85f` to `1.25f`),
     * `guardScrutiny` (bounty threshold / arrest dialogue tone),
     * `attitudeKey` (`kin_warm`, `cosmopolitan`, `wary_outlander`, `hold_prejudice`, `blood_kin_required`, `heretic_suspect`, `faction_honored`, `faction_hunted`).
   * Differentiate holds within the same province (e.g. `skyrim/eastmarch` vs. `skyrim/haafingar` vs. `skyrim/the_reach`; `morrowind/telvannis` vs. `morrowind/redoran` vs. `morrowind/ascadian_isles`; `hammerfell/alikr_desert` Crowns vs. `hammerfell/sentinel` Forebears; `summerset/summerset_isle` Thalmor vs. `summerset/auridon`).
   * Wire `RaceAttitudes` into `Relationships.disposition()`, `PriceEngine.java`, `ContextualDialogue.java`, and Orc stronghold gatekeepers.
3. **Reputation- & Consequence-Driven Encounters (`Encounters.java`, `WildScenes.java`, `scenes.json`):**
   * Add optional `minFactionRep`, `maxFactionRep`, `requiredDeed`, `playerRaces`, and `rulingFactions` filters on encounter definitions in `scenes.json`, and author 30+ dynamic political/reputation encounters (faction bounty hunters/justiciars/Morag Tong writs when hated, allied escorts/supply gifts when revered, post-story regional patrols and refugee/merchant shifts after MS1 story finales).

## 4. Verification Gates

* `SocietySelftest` verifies `Reputation` tracks all provincial factions, `RaceAttitudes` produces distinct disposition and price modifiers across contrasting holds (e.g. Argonian/Dunmer in `skyrim/eastmarch` vs. `skyrim/haafingar`; Outlander in `morrowind/telvannis`), and `Encounters` filters by faction reputation and deeds.
* `py -3 -I tools/qa/worldsheet_lint.py` -> `0 errors`, `100.0%` `F-WHY`.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
