# Place lore: a story for every named place

Every named place of Tamriel (`places/*.json`, see TAMRIEL.md section 6) has hand-written lore:

* a **hook**: one or two sentences on why anyone would go there;
* a **rumour**: what a townsperson says about it;
* **one to three readables**: a dead adventurer's journal, bandit orders, a necromancer's notes, a scholar's letter, a
  guard report, a love letter, a ledger, a treasure note pointing at a cache, a prayer, a warning;
* often a **mini-quest** of one to four steps, started by reading the note or by asking townsfolk about trouble.

Every region (and province) also has **rumours**, **local legends** and **folk songs** that townsfolk share.

All of it is original writing. Lore facts and names come from the Elder Scrolls; no game text is copied.

* Code: `com.skycraft.lore.place`.
  * `PlaceLore`: the data and the API.
  * `PlaceLoreEvents`: reading hooks (reveal, mini-quest) and the discovery line.
  * `StoryReader`: right-click / [F] on a story lectern, clue sign or note barrel opens the Skycraft reading screen.
  * `PlaceLoreLoot`: the notes in containers.
  * `PlaceLorePlacer`: lecterns.
  * `PlaceLoreDialogue`: townsfolk topics.
  * `PlaceLoreCommands`: `/skylore`.
  * `client/PlaceLoreClient`: map tooltips.
  * `client/PlaceLoreAutotest`: screenshots.
* Data:
  * Lore: `mod/src/main/resources/skycraft/lore/places/*.json`, listed in that folder's `index.json`.
  * Mini-quests: `mod/src/main/resources/skycraft/quests/place_*.json`, listed in `quests/index.json`.
* Tooling: `node tools/place_lore.js` validates the lore, prints the coverage and writes
  `lang/skycraft/place_lore.json`. Then run `node tools/quest_lang.js`, which writes the quest texts into
  `quest_story.json`. Run both after every edit.

## 1. Where players meet it

| what | where | code |
| --- | --- | --- |
| hook | the map pin's tooltip (wrapped, grey italics) | `world/client/MapScreen.pinTooltip` → `PlaceLoreClient.hookLines` |
| hook | a chat line under the "DISCOVERED" banner | `world/Discovery.announce` → `PlaceLoreEvents.onDiscovered` |
| rumour | "Any news?" when the townsperson's rumoured place has lore | `society/NpcDialogue.news` |
| regional rumour | innkeepers' "Heard any rumours?" (half the time), and "Heard of any trouble hereabouts?" when nothing is left to offer | `survival/inn/Innkeepers.rumor`, `PlaceLoreDialogue` |
| legend or folk song | "Know any local tales?" (every civilian townsperson) | `PlaceLoreDialogue` |
| mini-quest | reading its note, or asking any civilian townsperson "Heard of any trouble hereabouts?" (the nearest quest the player can still take, within 2,400 blocks: its pitch, the place goes on the map, the quest starts) | `PlaceLoreEvents.onRead`, `PlaceLoreDialogue` |
| named quest giver | some quests are offered by a named NPC in a nearby settlement (`start: "npc:<id>"`), the usual story-engine way | `quest/story` |
| readables | the place's containers, or a lectern at the place | `PlaceLoreLoot`, `PlaceLorePlacer` |
| treasure note | reading it marks the cache's place on the map (`Discovery.revealPlace`), and its quest's last step digs the cache up | `PlaceLoreEvents.onRead` |

The readables are ordinary lore books (`skycraft:book`) whose id is `place.<place>.<note>`:

* `LoreBooks.get` resolves the id through `PlaceLore.book`. These books have loot weight 0, so they never show up as
  random shelf books.
* `BookTexts` gets their text from `PlaceLore.bookText`.
* `LoreBookItem.read` calls `PlaceLoreEvents.onRead`.

Letters and notes open as a loose sheet. A `book` or `poem` opens as a bound book.

## 2. Placement: the default placer and the API for builders

**The default placer** puts every note where players will find it, with no builder involved.

* **Containers** (`PlaceLoreLoot`).
  * Every Skycraft container table that named places' chests roll gets one more pool with the function
    `skycraft:place_lore_note`. This covers `containers/dungeon/boss|standard`, `camp/*`, `site/*`, `household/*`,
    `shop/*`, `strongbox/*`, and `misc/satchel|knapsack|sarcophagus`. The pool is added on `LootTableLoadEvent`, so
    no data file changes.
  * The pool hands the opening player one note they haven't found yet from the place whose radius covers the
    container.
  * The boss chest prefers `"at": "boss"` notes. Other containers prefer `chest` notes, then `site` notes.
  * `boss` notes also turn up elsewhere now and then (1 in 3), for places that have no boss chest.
  * With Lootr every player rolls their own copy. Without Lootr the first opener gets the note.
* **Lecterns** (`PlaceLorePlacer`).
  * The first time anyone stands inside a place whose lore has `"at": "site"` notes, a lectern is set on open ground
    4–12 blocks from the centre, facing it. Shrines, standing stones, word walls, dragon mounds and landmarks have no
    chests, so all of their notes count as site notes.
  * The lectern holds the first site note. Right-clicking it reads the note and gives the reader copies of every
    lectern note of the place they haven't found.
  * This happens once per world and place (world data `skycraft_place_lore`). If no loaded free spot exists yet, it
    retries on the next visit.
* **Per player** (`module("place_lore").found`). A note that has been handed over or read is never handed over again.

**The API for other modules** (DUNGEON-QA, EXPLORE, settlement builders):

```java
PlaceLore.contentFor(placeId)        // Optional<Content>: hook, rumour, quest id, notes (id, kind, title, at, reveals, starts)
PlaceLore.createNotes(placeId)       // List<ItemStack>: every note of the place, ready for a chest, rack or lectern
PlaceLore.createNote(placeId, noteId)
PlaceLore.claimPlacement(placeId)    // call once at setup if your builder places this place's notes itself:
                                     // the default placer (containers and lectern) then leaves the place alone
PlaceLore.hook(placeId) / rumour(placeId) / region(regionId) / regionalRumour(regionId, random)
```

To place a note, put the stack in any container, or on a vanilla lectern with
`LecternBlockEntity.setBook(stack)` and `HAS_BOOK` set. `StoryReader` opens the Skycraft reading screen (the one lore
books use) for a lectern that holds a `skycraft:book`, a written book tagged `SkyNote` (the place note id) or `SkyStory`,
or any written book on a lectern no player placed. The vanilla lectern menu is never reached, so the book stays (set
`lore.storyLecternTakeable` and sneak to take it). A sign or barrel is readable the same way when its block entity has
the `SkyRead` marker (`StoryReader.mark` / `markNoteContainer`); the scene realiser marks its clue signs and note
barrels. `/skylore selftest lecterns` simulates the clicks headless (run by `qa.ps1 -Server`).

## 3. Data format

### Lore files (`lore/places/<file>.json`)

```json
{
  "regions": {
    "skyrim/whiterun": {
      "rumours": ["5-8 spoken lines: news, gossip, worries; mention real places"],
      "legends": ["2-4 short local legends, 2-5 sentences each"],
      "songs":   [{"title": "...", "lines": ["4-8 lines"]}]
    },
    "cyrodiil": { "...": "a province entry is the fallback for its regions without their own" }
  },
  "places": {
    "<placeId>": {
      "hook": "1-2 sentences, narrator voice (60-320 characters)",
      "rumour": "what a townsperson says about it",
      "quest": "pl_<placeId>",
      "notes": [
        {"id": "journal", "kind": "journal", "title": "...", "author": "...", "at": "chest",
         "reveals": "<placeId>", "starts": "pl_<placeId>",
         "pages": ["#17th of Second Seed\nFirst entry...", "Second entry..."]}
      ]
    }
  }
}
```

* `quest` is optional. It names the place's mini-quest, which townsfolk nearby can then offer.
* Notes:
  * `id` is lowercase and unique within the place.
  * `kind` is one of `journal letter orders notes report ledger treasure poem inscription book diary list contract
    prayer warning`. `letter` opens as a letter, `book` and `poem` as bound books, and everything else as a note
    sheet.
  * `author` is optional.
  * `at` is `chest` (the default), `boss` or `site`.
  * `reveals` is optional. Reading the note marks that place on the reader's map.
  * `starts` is optional. Reading the note starts that mini-quest if the reader can take it.
  * `pages` are sections. Each one starts a new page and long ones flow on. Inside a page, `\n` breaks a line,
    `#` starts a centred heading, `^` a centred line and `~` a verse line.
* Language keys, written by `tools/place_lore.js`. A translation can override any of them.

  | text | key |
  | --- | --- |
  | hook | `place_lore.skycraft.<place>.hook` |
  | rumour | `place_lore.skycraft.<place>.rumour` |
  | note title | `book.skycraft.place.<place>.<note>` |
  | note author | `place_lore.skycraft.<place>.<note>.author` |
  | note pages | `place_lore.skycraft.<place>.<note>.p<i>` |
  | regional rumours | `place_lore.skycraft.region.<region with / as .>.rumour.<i>` |
  | regional legends | `place_lore.skycraft.region.<region with / as .>.legend.<i>` |
  | song title | `place_lore.skycraft.region.<region with / as .>.song.<i>.title` |
  | song lines | `place_lore.skycraft.region.<region with / as .>.song.<i>.line.<j>` |

* A place id the world doesn't list yet needs `"future": true`. It is kept, and shows up once the place exists.

### Mini-quests (`quests/place_<file>.json`)

Mini-quests use the story engine format (`quest/story/StoryData`) with these conventions:

* **Ids.**
  * Quest ids are `pl_<placeId>`, or `pl_<placeId>_2` for a second quest at the same place.
  * Named NPC ids are `pln_<file>_<name>`.
  * Relic ids are `plr_<placeId>_<thing>`.
* **Required fields.** Every quest has `region`, `title`, `desc`, `start` and `pitch`.
  * `"start": "manual"` means it is started by a note's `starts` or a place's `quest`. The validator rejects a manual
    quest that nothing starts.
  * `"start": "npc:<id>"` means a named giver offers it. Givers live in settlements (`place` is a settlement id,
    within ±8 blocks of `offset`).
* **Objectives** (1–4).
  * `go`, `retrieve` (a `relic` or an `item`), `kill` (needs a spawn with `"target": true`), `clear`, `deliver`,
    `talk` (`choices` are allowed), `discover`, `wait` and `escort`.
  * Inside a dungeon, use `"placement": "structure"`.
* **Treasure caches.** A cache is a `retrieve` with an `item` at a place, plus an `offset` of up to about 40 blocks.
  Its note `reveals` that place.
* **Rewards.**
  * Skyrim quests pay 60–400 gold, scaled by the region's level band, and province quests 150–600.
  * A quest can add one or two items from the list in `tools/place_lore.js`. The list is all items that the other
    questlines already give, so it passes `/skycraft-quest selftest`.
  * A lost heirloom can be a legendary unique: `"unique:<id>"` in `rewards.items` or as a retrieve `item`.
    `Story.stack` builds it with `LegendaryUniques.create`, so the collection tracker counts it.

## 4. Adding lore for new places (the second pass)

PROVINCES adds hundreds of province places. To write lore for them:

1. Create a new file, `lore/places/<province>_<n>.json`. Never grow someone else's file. Add it to
   `lore/places/index.json`. If the file comes with quests, add `quests/place_<province>_<n>.json` to
   `quests/index.json`.
2. Write an entry for each new place id from `tamriel/places/*.json`. A place's lore lives in exactly one file, and
   the validator rejects duplicates.
3. Weave the new places into the existing ones: reference real neighbours by name, and use `reveals` to point
   treasure notes at nearby ruins.
4. Run `node tools/place_lore.js`. It must report no problems, and its coverage line lists every place still
   without lore. Then run `node tools/quest_lang.js`.
5. In game, run `/skycraft-quest selftest` and `/skycraft-quest selftest flow`. Both must report 0 problems.
   `/skylore stats`, `/skylore show <place>` and `/skylore read <place> <note>` check the result.

The writers' brief used for the first pass covers voice, lengths and the allowed creatures and items. It is
summarised in section 3 and in the checks of `tools/place_lore.js`.

## 5. Testing

* `node tools/place_lore.js` validates the data and prints the coverage:
  * place ids, kinds, lengths, `reveals`, `starts` and region ids;
  * each quest's objective types, creatures, items, uniques, NPC roles, relics and starters.
* In game, `/skycraft-quest selftest` builds every quest, and `/skycraft-quest selftest flow` plays every quest to
  completion. Both include the place quests.
* `/skylore stats|show|give|read|lectern` checks the lore directly.
* Client screenshots: set `JAVA_TOOL_OPTIONS=-Dskycraft.autotest.placelore=<place>`, then run
  `tools/autotest.ps1 -World <name>`.
  * It reads each note of the place through the server and screenshots the reading screen, the journal and the map.
  * The shots are saved as `pl_*.png` in `mod/run/build/autotest-screenshots`.

## 6. Coverage (first pass)

See the final report of the LORE WRITERS workstream, or run `node tools/place_lore.js` for the current numbers.
