# Loot: a progression economy

NirnCraft's loot follows Skyrim's leveled lists. Every container is worth opening, nothing breaks the economy, each
province tastes different, and there is always something left to collect. This file is the contract between the
modules that place containers (dungeons, settlements, explorers' sites) and the loot module that fills them.

* Code: `com.nirncraft.loot`.
  * `LootLevels`: the loot level.
  * `LootTiers`: the material ladders.
  * `LootFunctions`: the loot functions.
  * `Containers`: table ids and rules.
  * `Collectibles`, `LegendaryUniques` and `CollectionTracker`: the collecting goals.
  * `Restock`, `LootApi` and `LootCommand`.
  * `arsenal/LeveledGear` and `crafting/RegionalGear` now use the ladders.
* Data: `tools/loot_data.py` writes every table in this file. Never hand-edit a generated table: edit the script and
  re-run it.
* Tuning: `tools/loot_sim.py` simulates a character's progress. In game, `/skyloot curve <table>` does the same, and
  so does `nirncraft-loot-sample.txt` (see "Testing").

## 1. The loot level

Every roll happens at a loot level (`LootLevels.of`):

* **Gear level** = the character level, capped at the region's top level + 15.
  * This is Skyrim's encounter zones: a level-50 hero who clears Whiterun's barrows (band 1–10) finds level-25 gear.
  * It is never above the character's level. A level-5 player who runs into Akavir finds level-5 gear, not
    Daedric.
* **Corpses** roll at the creature's own `nirncraft_level`, and never above the killer's level.
* **Danger**: a region whose bottom level is above the player pays +2% gold per level of gap, up to +40%. It never
  gives better gear.
* **The player** is THIS_ENTITY when it is a player.
  * Chests opened by a player and Lootr's per-player rolls both count.
  * Otherwise it is the killer or last attacker, then the nearest player within 64 blocks.

### Material ladders (`LootTiers`)

Each material has an unlock level. Nothing above the level ever drops. The newest unlocked rung makes up 60% of the
drops, the rung before it 30%, and everything older 10%.

| unlock | weapons | heavy armour | light armour |
| --- | --- | --- | --- |
| 1 | Iron | Iron (Banded Iron 3) | Hide, Fur |
| 6 | Steel, Silver | Steel | Leather, Studded |
| 12 | Orcish | Orcish | Elven |
| 18 | Dwarven | Dwarven | Scaled |
| 22 | Elven | Steel Plate | Elven Gilded |
| 27 | Glass, Nordic | Nordic | Glass |
| 32 | Ebony | Ebony | (Glass) |
| 40 | Stalhrim | Stalhrim | Stalhrim |
| 46 | Daedric | Daedric | (Glass, Stalhrim) |
| 50 | Dragonbone | Dragonplate | Dragonscale |

Provinces' own gear (`#nirncraft:gear/<key>`) keeps the gear catalogue's level: Imperial 3, Chitin 3, Bonemold,
Akaviri 19 and so on. `RegionalGear.minLevel` reads the same table, and the item audit checks it.

### Enchantments and quality

* **Chance.** A gear drop is enchanted with chance = the table's base × `clamp(0.35 + L/32, 0.35, 2.2)`, capped at
  85%.
  * A whole dungeon's gear is about 5% enchanted at level 1, 15% at 18, 25% at 40 and 35% at 60.
  * That is Skyrim's curve. In Skyrim, boss chests and leaders carry most of the enchanted pieces.
* **Strength.** Each enchanted drop gets **one** enchantment, at level `1 + L/15` (±1, rarely). From level 36 there
  is a 15% chance of a second enchantment.
* **Tempering quality.** Some drops are pre-tempered. Quality runs 1..`1 + L/12`, capped at 5.

### Gold (`nirncraft:scaled_gold {min, max, growth}`)

* Amount = `uniform(min, max) × (1 + growth × (L − 1)) × danger`.
* Growth is 0.04 by default and 0.05–0.06 for boss and noble hoards.
* Small amounts drop as loose Septims; anything above 64 drops as a coin purse.

## 2. Container taxonomy: `nirncraft:containers/<kind>/<tier>`

Values are the mean total value (gold plus items at their Skyrim values) from `tools/loot_sim.py`. "Tier curve"
means the gear follows the ladders above at the loot level.

| table | where | L1 | L12 | L27 | L46 | L60 | contents |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `dungeon/minor` | urns, barrels, satchels in dungeons | 13 | 20 | 27 | 30 | 34 | coins (50%), ingredients, potion, lockpicks, arrows, rare gem or soul gem |
| `dungeon/standard` | ordinary dungeon chests | 75 | 169 | 402 | 958 | 1058 | coins; gear 70% (base enchant 8%); 1–2 of soul gems, gems, tome, potion, book, trinket, skill book; treasure 3% (L12+); place collectible |
| `dungeon/boss` | the boss chest | 243 | 735 | 1376 | 2803 | 3128 | coins (growth .06); 1–2 gear (enchant 25%, quality 35%); 1–2 gems; soul gem; potions; one extra: tome, skill book, staff (L10+), trinket, regional treasure (L8+), random unique (L12+), artifact of legend (L18+); lore book 30%; place collectibles ×2 |
| `dungeon/theme/<culture>` | a site people's extras: `nordic dwemer falmer ayleid velothi daedric necromancer vampire forsworn` | | | | | | Dwemer scrap and oil, chaurus parts, Ayleid soul gems and treasures, Daedra hearts... |
| `household/poor` | farmhands, beggars | 25 | 27 | 28 | 30 | 30 | food, a few coins, clutter, a note |
| `household/common` | most homes | 33 | 35 | 43 | 49 | 51 | food, coins, clutter, a potion or book, a rare trinket or skill book |
| `household/wealthy` | merchants' and thanes' homes | 123 | 172 | 261 | 426 | 449 | coins, jewellery, gems, soul gems, potions, a little gear, books |
| `household/noble` | manors, Jarls' quarters | 246 | 359 | 549 | 983 | 1088 | coins (growth .05), 2–3 of jewellery, gems, enchanted gear, tomes; books; treasure 3% |
| `household_stores/<wealth>` | kitchen barrels, sacks, cupboards | ~28 | | | | | food, ingredients, salt |
| `shop/<type>` | back-room chests: `general blacksmith alchemist inn temple farm mill mine barracks jarl court_wizard` | general 142 → 678, jarl 316 → 1573 | | | | | each shop's own goods plus scaled coins |
| `misc/satchel`, `misc/knapsack`, `misc/barrel`, `misc/urn`, `misc/sarcophagus`, `misc/coin_purse` | loose containers anywhere | 11–17 | | | | ~35 | satchel and knapsack: a traveller's things; sarcophagus: Ancient Nord gear, gems, a place collectible |
| `strongbox/<lock>` | `novice … master` | adept 177 → 412, master 349 → 870 | | | | | coins by lock tier, gems, trinkets, soul gems |
| `lock_bonus/<lock>` | rolled once when a lock is picked (`crime/Locks`) | novice 24, master 92 → 246 | | | | | small scaled coins plus lockpicks, gems, soul gems |
| `camp/<kind>` | `bandit bandit_supplies giant hunter forsworn smuggler` | bandit 108 → 1500 | | | | | |
| `site/<kind>` | `shrine daedric_shrine word_wall dragon_lair mine shipwreck flotsam harbor landing_camp alchemist_lair` | shipwreck 225 → 1220 | | | | | the dragon lair is a boss chest plus bones and scales; shrines and word walls hold place collectibles |
| `corpse/<rank>` | what a dead creature carries (see the entity tables) | bandit 20 → 44, leader 148 → 1773 | | | | | `commoner bandit bandit_boss soldier mage mage_boss draugr draugr_boss forsworn falmer vampire dremora giant brute dragon_priest dragon` |
| `reward/<tier>` | quest rewards: `minor standard major legendary` | standard 224 → 1654 | | | | | `LootApi.questReward(player, tier)` |
| `nirncraft:uniques/<id>` | one legendary unique (section 4) | | | | | | each character finds it once |

Building blocks under `nirncraft:parts/*` are shared by the tables above: `gems`, `soul_gems`, `potions`, `food`,
`lore_note`, `lockpicks`, `smithing`, `staff`, `clutter`, `gear_common`, `gear_boss` and `gear_rich`.

* **Gem gates.** Rubies appear from level 6, emeralds from 9, sapphires from 12, diamonds from 18 and flawless
  diamonds from 27.
* **Soul gem gates.** Common soul gems appear from level 6, greater from 18 and grand from 30.

### A whole dungeon against the Skyrim reference

`tools/loot_sim.py` clears 500 dungeons per level. Each dungeon is 1 boss chest, 3 chests, 6 urns, 2 picked locks,
7 enemies and their leader. The reference is a Skyrim estimate:

* coins ≈ 110 + 8.5 L;
* value ≈ 500 + 140 L (+70 L past 46);
* enchanted gear ≈ 5% + 0.5% per level.

| L | coins (ref) | value (ref) | ratio | best tier | enchanted % (ref) | ench. level |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 136 (118) | 816 (640) | 1.28 | 1 | 5.4 (5.5) | 1.2 |
| 6 | 167 (161) | 1210 (1340) | 0.90 | 6 | 9.6 (8.0) | 1.2 |
| 12 | 207 (212) | 1917 (2180) | 0.88 | 12 | 12.0 (11.0) | 1.2 |
| 18 | 264 (263) | 2286 (3020) | 0.76 | 18 | 15.9 (14.0) | 2.0 |
| 27 | 321 (340) | 3697 (4280) | 0.86 | 27 | 18.6 (18.5) | 2.0 |
| 32 | 391 (382) | 4814 (4980) | 0.97 | 32 | 22.2 (21.0) | 3.0 |
| 40 | 439 (450) | 6150 (6100) | 1.01 | 40 | 26.3 (25.0) | 3.0 |
| 46 | 539 (501) | 8208 (6940) | 1.18 | 46 | 29.0 (28.0) | 3.9 |
| 60 | 648 (620) | 9221 (7920) | 1.16 | 50 | 37.0 (35.0) | 4.0 |

* **Coins** follow the reference within 15%.
* **Value** stays between 0.74 and 1.28 of the reference. It rises at every level band, with no drought. The small
  mid-level dip comes from Skyrim's own convex prices: Steel and Orcish pieces are cheap next to Glass and Ebony.
* **No jackpots.** No single container's 99th percentile is worth more than 1.3 dungeons. The boss chest's is 1.26,
  shipwrecks' 1.11 and the Jarl's chest 1.27.

## 3. Regional flavour

| province (gear key) | gear (`#nirncraft:gear/<key>`) | ingredients (`#nirncraft:loot/ingredients/<key>`) | trinkets (`#nirncraft:loot/trinkets/<key>`) | treasures |
| --- | --- | --- | --- | --- |
| skyrim | Nord leveled lists, Ancient Nord, Falmer | Skyrim's | silver and gold rings, Talos amulet, circlets | Dwemer artefacts |
| solstheim | Stalhrim, Bonemold, Chitin | Solstheim's | silver, copper | Morrowind's set, Black Books |
| cyrodiil | Imperial | Cyrodiil's | gold, Mara, silver circlet, ruby necklace | Ayleid welkynd and varla stones |
| morrowind | Chitin, Bonemold, Ordinator | ash yams, trama root... | silver, copper | Velothi relics |
| black_marsh | Argonian | Hist sap, swamp pods | jade, silver | Hist amber, Xanmeer glyphs |
| elsweyr | Khajiit | moon sugar | gold, sapphire | moon-sugar idols |
| valenwood | Bosmer | Valenwood's | jade, silver | Green Pact relics |
| summerset | Thalmor, Elven, Glass | Summerset's | gold circlet, diamond, jade | Psijic relics |
| hammerfell | Ra Gada, Redguard, Yokudan | Alik'r herbs | gold | Yokudan relics |
| high_rock / orsinium | Breton / Orsinium | Wrothgar's | silver, Mara | Direnni, coven and Orc relics |

* **Books.** `nirncraft:regional_book` favours the province's own lore books eight to one.
* **Gear key.** `RegionalGear.gearKey(region)` decides the key: the province, except Wrothgar (`orsinium`) and
  Solstheim (`solstheim`).

## 4. Collections and long-term goals

The Journal's **Collections** tab (`loot/client/CollectionsPage`) shows each collection's progress. For every entry
it shows where to look: the named place, "a Prince's shrine", or the province.

* **Tracking.** `CollectionTracker` notices a collectible when it reaches the inventory, and pays each reward once.
* **Data.** Everything is stored in `module("loot")`:
  * `found`, `rewarded` and `offered`;
  * `black_books_read` and `skill_sets`.

| collection | entries and where | reward |
| --- | --- | --- |
| Dragon Priest masks | the 8 named priests' masks (dropped by the priests) | Konahrik's mask and 1 perk point |
| Daedric artifacts | Azura's Star (L5), Wabbajack (10), Dawnbreaker (12), Spellbreaker, Mehrunes' Razor, Volendrung (15), Ebony Blade, Mace of Molag Bal (20). Each waits at its Prince's shrines (`shrine_<prince>_*`, Statue to Meridia, Shrine of Azura...) | 2,500 gold and 2 perk points |
| Artifacts of legend | Bloodskal Blade (Bloodskal Barrow), Zephyr (Arkngthamz), Dragonbane (Sky Haven Temple), Wuuthrad (Ysgramor's Tomb), Auriel's Bow (Darkfall Cave), Chillrend (Nightingale Hall), Nightingale Bow (Twilight Sepulcher), Staff of Magnus (Labyrinthian). Windshear and the Bow of Shadows are the rarest boss-chest finds | 2,000 gold and 2 perk points |
| Stones of Barenziah | 24 "unusual gems", one each in Pinewatch, Fellglow Keep, Blind Cliff Cave, Darklight Tower, Yngvild, Tolvald's Cave, Forsaken Cave, Rimerock Burrow, Stony Creek Cave, Mzulft, Alftand, Ironbind Barrow, Movarth's Lair, Silverdrift Lair, Folgunthur, Korvanjund, Ansilvund, Snow Veil Sanctum, Volunruud, Bthardamz, Nilheim, Valthume, Shroud Hearth Barrow and Hillgrund's Tomb | the Crown of Barenziah, 1,000 gold and 1 perk point. From then on every boss chest (and 30% of chests) holds an extra gem |
| Black Books | Epistolary Acumen (Tel Mithryn), Filament and Filigree (Nchardak), The Hidden Twilight (White Ridge Barrow), The Sallow Regent (Gyldenhul Barrow), Untold Legends (Bloodskal Barrow), Waking Dreams (Temple of Miraak), The Winds of Change (Fahlbtharz). Reading one grants 1 perk point and 2 levels of its skill | 2 perk points |
| Elder Scrolls | Dragon (Tower of Mzark), Blood (Dimhollow Crypt), Sun (Darkfall Cave). Reading one blinds you for a while | 2 perk points |
| Skill books | 5 volumes per skill. Volume 1 is the merchants' book; volumes 2–5 carry `volume` NBT and their own titles. Each volume teaches 1 level, and all 5 of a skill teach 1 more | 3 perk points |
| Regional treasures | 5 per province, from that province's chests (missing ones three times as likely) | 1,500 gold and 1 perk point per set |
| Legendary arms | 38 uniques (below) | 10,000 gold and 3 perk points |

**Place collectibles** (`nirncraft:place_collectibles`):

* The dungeon's standard and boss chests, sarcophagi, shrines and word walls hand a character the collectibles bound
  to the named place (`places/*.json`) whose radius covers the container.
* Each one is given only if the character lacks it and is at or above its level.
* **Lootr** gives every player their own copy. Without Lootr, the first player to open the container gets it.
* An entry offered and left in a chest is not offered again for 30 days, so two chests of one dungeon never both hold
  the same stone.

### Legendary uniques (`LegendaryUniques`, placed by id)

Each unique is a fixed, named, enchanted and pre-tempered weapon with lore and a value well above its material.

* **Placing one.** Explorers' sites place them with the table `nirncraft:uniques/<id>` or with
  `LegendaryUniques.create(id)`.
* **Placed uniques.** Uniques with a place are also handed out by that place's chests.
* **Province uniques.** Uniques without a place turn up, rarely, in their province's boss chests
  (`nirncraft:random_unique`).
* **Once per character.** Every character finds each unique once.

| province | id (base, level, place) |
| --- | --- |
| skyrim | `red_eagles_fury` (Ancient Nord sword, 5, red_eagle_redoubt), `bloodthorn` (dagger, 6, hag_rock_redoubt), `bolars_oathblade` (steel greatsword, 8, valtheim_towers), `nettlebane` (Orcish dagger, 10, dead_crone_rock), `gauldur_blackblade` (12, geirmunds_hall), `ghostblade` (Nordic greatsword, 18, ironbind_barrow), `the_pale_blade` (18, lost_valkygg), `kahvozeins_fang` (Dwarven dagger, 22, skyborn_altar) |
| cyrodiil | `welkynd_sliver` (14), `goldbrand` (Glass, 30), `umbra` (Ebony, 34) |
| morrowind / solstheim | `hopesfire` (20), `trueflame` (26), `keening` (40), `sunder` (40), `riekling_spear_of_thirsk` (Stalhrim, 40) |
| black_marsh | `hist_fang` (16), `shadowscale_kris` (24), `marsh_lords_mace` (30) |
| elsweyr | `claw_of_alkosh` (16), `moonblade_of_the_mane` (26), `rajhins_shadow` (bow, 30) |
| valenwood | `green_pact_fang` (14), `jaqspur_longbow` (22), `wild_hunt_axe` (30) |
| summerset | `alinor_dawnblade` (22), `psijic_longbow` (30), `sunbird_greatsword` (34) |
| hammerfell | `sword_of_the_ansei` (18), `hews_bane_scimitar` (26), `shehai_blade` (34) |
| high_rock / orsinium | `shornhelm_blade` (14), `direnni_warhammer` (24), `chrysamere` (36), `gortwogs_hammer` (20), `malacaths_cleaver` (28) |
| akavir / atmora | `dragonguard_katana` (28), `atmoran_frostaxe` (24) |

## 5. Ownership, theft, respawns and duplicates

* **Owned containers.** Containers using `containers/household/*`, `containers/shop/*` or the old
  `chests/village/*` belong to the household (`Containers.isOwned`; `crime/Theft` and `crime/Ownership` honour it).
  Taking from them is theft: items are tagged stolen and a witness adds a bounty.
* **Finders keepers.** Dungeon, camp, site and wilderness containers belong to no one.
* **Restocking (Skyrim's respawn).**

  | containers | restock after looting |
  | --- | --- |
  | dungeon and wilderness containers | 30 days |
  | households and shops | 10 days (30 with Lootr, which has a single timer) |
  | boss chests, dragon lairs, word walls, Daedric shrines and placed uniques | never |

  * **With Lootr.** Lootr is in the pack. Each player loots their own copy of every container. The pack's
    `config/lootr-common.toml`, written by `tools/loot_data.py`, refreshes exactly the restocking tables after
    720,000 ticks. Corpses, rewards and lock bonuses are not containers.
  * **Without Lootr.** On a dev server, `loot/Restock` remembers each container's table the first time it is opened
    and rolls it afresh when it is opened after the restock time. Containers players placed are never touched.
* **Duplicate guards.**
  * Collectibles and uniques are handed out once per character, recorded in `found`.
  * The `offered` cooldown stops two containers of one roll, or one dungeon, from doubling a collectible.
  * Lock bonuses are rolled once per lock.
  * Lootr's chests are per player by design, so co-op players each find their own loot.
  * Lootr-converted containers keep their loot table id, so they roll these same tables with the opener as
    THIS_ENTITY.

## 6. For other modules

* **Dungeons (DUNGEON-QA) and explorers (EXPLORE).**
  * Point containers at `Containers.dungeon("minor" | "standard" | "boss")`, `containers/dungeon/theme/<culture>`,
    `containers/misc/*`, `containers/camp/*` or `containers/site/*`.
  * The old `nirncraft:chests/*` ids still work as aliases, so the existing builders already use the new tables.
* **Explorers' uniques.** Place a unique with `nirncraft:uniques/<id>`, or put `LegendaryUniques.create(id)` on a
  rack.
* **Settlements (BUILDKIT).** Use `Containers.household(wealth 0..3)` for chests and `Containers.householdStores(...)`
  for barrels and sacks. Use `containers/shop/<type>` for shop back rooms. Taking from any of these is theft.
* **Quests.**
  * `LootApi.questReward(player, "minor" | "standard" | "major" | "legendary")` returns the reward items.
  * `Collectibles.entry("elder_scrolls/dragon").create()` returns the quest's Elder Scroll.
* **Factions.** For faction rewards, use `containers/reward/*` and `LootApi.roll(level, table, pos, player)`.

## 7. Testing

* **Simulation.** `py -3 tools/loot_sim.py` prints the curves above and exits 1 on any drift, drought, jackpot or
  above-level drop. `--table <id>` prints one table.
* **In game.**
  * `/skyloot sample <table> [level] [rolls]` lists one roll and the averages of many.
  * `/skyloot curve <table>` prints the averages at levels 1, 6, 12, 18, 27, 32, 46 and 60.
  * `/skyloot collections`, `/skyloot unique <id>` and `/skyloot collectible <collection> <entry>` check and hand
    out collectibles.
* **Startup sample.** When `run/nirncraft-loot-sample.request` exists, the server writes every `containers/*`
  table's curve to `run/nirncraft-loot-sample.txt` on start.
