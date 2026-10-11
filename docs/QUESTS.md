# Quests

Skycraft's story quests are data: JSON files in `mod/src/main/resources/skycraft/quests/`, listed in `index.json`
and loaded by `com.skycraft.quest.story.StoryData`. The format is documented at the top of `StoryData.java`. After
editing quest data run `node tools/quest_lang.js`: it validates every file and exports all texts to
`mod/src/main/lang/skycraft/quest_story.json`. In game, `/skycraft-quest selftest` builds every quest and checks what
a playthrough would hit, and `/skycraft-quest selftest flow` plays every quest from its first step to completion.

## Side quests of the provinces

Every province has its own file of multi-stage side quest arcs (`side_<province>.json`, ids prefixed
`side_<code>_`): forgotten temples and their guardians, haunted forts, cursed heirlooms, feuding families, Daedric
cults, smugglers, vampire bloodlines, witch covens and more, each with a named boss or a twist, a real reward and a
journey between places. They are original stories in the spirit of the lore and of the earlier games' quest
archetypes.

| File | Quests | Hidden | Named bosses |
| --- | --- | --- | --- |
| side_skyrim.json | 14 | 4 | 16 |
| side_cyrodiil.json | 15 | 4 | 16 |
| side_morrowind.json | 14 | 5 | 14 |
| side_solstheim.json | 10 | 3 | 10 |
| side_black_marsh.json | 12 | 3 | 12 |
| side_elsweyr.json | 12 | 3 | 14 |
| side_valenwood.json | 13 | 3 | 13 |
| side_summerset.json | 13 | 3 | 14 |
| side_hammerfell.json | 13 | 3 | 14 |
| side_high_rock.json | 13 | 3 | 14 |
| side_foreign.json (Atmora, Akavir, Yokuda, Pyandonea, Thras) | 10 | 0 | 13 |

### How players find them

* **Quest givers.** Most arcs are offered by a named NPC in a city or town (`"start": "npc:<id>"`). Ask around.
* **Hidden quests.** Some arcs are offered by nobody (`"start": "find"`). A small clue waits in the world near a
  ruin, a fort or a lonely spot: a skeleton clutching a note, a carved standing stone, a lever in a mossy wall, a
  candlelit black altar, a grave, a lectern, an urn or a totem. The clue is built once per world, the first time a
  player comes within 40 blocks. When a player gets close, a hint says "Something here catches your eye". Using the
  clue (right-click) or stepping onto it starts the quest. Each player can find each clue once.
* **Chains.** Later parts of an arc start by themselves when the earlier part is done (`"start": "auto"` with
  `"requires": ["quest:<previous>"]`).

### Unique bosses

A quest file can declare named bosses in a `bosses` block. A boss is an existing creature turned into a character:

```json
"bosses": {
  "side_cyr_example": {"name": "Anaraen the Unlit", "type": "skycraft:necromancer", "hp": 4.0, "dmg": 1.6, "level": 24,
    "weapon": "skycraft:elven_dagger", "gear": {"head": "skycraft:elven_gilded_helmet"},
    "drop": "unique:welkynd_sliver,loot:major", "glow": false}
}
```

* `hp` and `dmg` multiply the creature's health and damage.
* `level` is a floor. The boss is at least that level, or two above the strongest nearby player.
* `gear` equips items by slot (`head`, `chest`, `legs`, `feet`, `offhand`, `mainhand`). Gear never drops.
* `drop` is what the boss leaves when it dies: `unique:<id>` (a legendary unique), `loot:<tier>` (rolled at the
  killer's loot level), or an item id. Separate several with commas.
* An objective spawns a boss with `{"boss_id": "<id>"}`. The boss is then the objective's kill target and shows a
  boss bar.
* The action `boss:<id>` raises the boss next to the party, for ambushes and twists.

`com.skycraft.quest.story.Bosses` implements this. `QuestSpawner` calls `Bosses.apply` for every quest entity.

### Rewards

Besides `gold`, `items` and the other story rewards, a quest's `rewards` may have:

* `"loot": "minor|standard|major|legendary"`: a loot-module reward tier (`LootApi.questReward`), rolled for each
  rewarded player at their own loot level.
* `"unique": "<id>"`: a legendary unique (`loot/LegendaryUniques`) for each rewarded player.

The actions `loot:<tier>` and `unique:<id>` hand out the same rewards in the middle of a quest.

### Foreign shores

Atmora, Akavir, Yokuda, Pyandonea and Thras are shaped by the world seed, so their quests cannot name fixed
places. A quest variable finds land at runtime instead:

```json
"vars": {"shore": "land:atmora:0:-11000"}
```

The variable searches outward from the given centre for dry land of that province. It resolves to a place id,
`land_<x>_<z>`, and objectives use it as `"place": "@shore"`, with offsets.

## Radiant quest archetypes

`radiant_templates.json` holds Daggerfall-style radiant archetypes for the provinces beyond Skyrim. Townsfolk draw
from them two times in five when asked for local work (`ProvinceRadiant`); the rest of the time they offer the
built-in culls, relic hunts and deliveries.

The archetypes include:

* pest-ridden storehouses and named-beast hunts;
* livestock raiders and undead masters;
* haunted homes and family curses;
* planar breaches and rumoured artifacts;
* missing persons and timed couriers;
* relics under siege and reagent gathering;
* profane shrines and stolen heirlooms;
* kidnappings, turncoats and night watches;
* escaped experiments, field surveys and rogue archmages;
* salvage jobs, daedra hearts and coven favours;
* elder vampires and orc warbands;
* culture-specific work: hatchling slavers, skooma dens, Green Pact breakers, Maormer landings, Dunmer tomb robbers,
  stronghold trials, smugglers' ledgers and forbidden tomes.

Each template is filtered by culture (or `*` for any culture) and weighted. Its steps are `clear`, `kill`,
`retrieve`, `go`, `wait`, `courier`, `bring` and `return`. A step's foe can depend on the culture. Template texts
take these arguments:

| Argument | Meaning |
| --- | --- |
| `%1$s` | direction |
| `%2$s` | site name |
| `%3$s` | gold |
| `%4$s` | named target |
| `%5$s` | region |
| `%6$s` | destination town |

The class `com.skycraft.quest.story.RadiantTemplates` builds them, and `quest_lang.js` exports their texts.

## Tools

* `node tools/quest_lang.js` validates all quest data and writes the lang file. It checks:
  * places, NPCs, relics and scenes;
  * factions;
  * bosses and their drops;
  * legendary unique ids and loot tiers;
  * hidden clue places and kinds;
  * Skycraft item ids.
* `node tools/quest_lang.js --dry --with side_new.json` checks a file that isn't in `index.json` yet, without
  writing anything.

