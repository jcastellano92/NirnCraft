# Marquee gap analysis: how far each province is from Skyrim's level

Measured from the data on `tamriel-overhaul` (503dffe), no chunks generated. Method: `places/*.json` (4,914 places),
`settlement/plan/Cities.java` + `kit/Kits.java` + `grammar/Zoning.java` (what gets a unique layout), `lore/places/*.json`
and `lang place_lore` (what has written content), `quests/*.json` (675 quests; NPC `place` and objective `place`
resolved to settlement ids), `dungeons/world/{Sites,Special,Theme,Landmarks,Uniques}.java` and
`dungeons/blueprints/*.json` (what is hand-built). Skyrim is the bar. The list of what to build about it is in
`docs/MARQUEE.md`; the work order is in `docs/MARQUEE-PLAN.md`.

## 1. The headline

* **12 settlements have a unique layout and kit; 11 of them are in or tied to Skyrim**: the nine hold capitals,
  Helgen, the College, Dragon Bridge (Raven Rock is only a wall ring). Outside Skyrim there are 10 bespoke layouts for
  ~170 towns and cities (`imperial_city, mournhold, vivec, alinor, sentinel, torval, wayrest, orsinium, elden_root,
  lilmoth`), and two of those are literal copies (`torval` = `sentinel`'s `domedCapital`, `orsinium` = `wayrest`'s
  `castleCapital`, differing in one size argument).
* **Every other non-Skyrim city is `Zoning.generic` output**, styled only by its culture's style sheet
  (14 sheets; Dunmer towns pick one of four kits by a hash of the id). Two Redguard towns, two Altmer towns or two
  Imperial cities differ in street form and seed, not in identity: no signature landmark, no named inn or shop
  (`Cities.names` and `innIndex` cover 8 and 16 Skyrim/Solstheim places only), no unique skyline.
* **Written content stops at the capitals.** Place lore exists for 377 of 378 Skyrim places; outside Skyrim it exists
  only for the 16 Daedric shrines per province and for 3 to 10 cities each (Morrowind's 60 are mostly Solstheim).
  **Zero** of the 2,465 non-Skyrim dungeons and ~1,000 forts and landmarks has a hook, rumour, note or mini-quest, except
  Solstheim.
* **No dungeon outside Skyrim is hand-built.** `Special` (11 values) and `Uniques` (High Hrothgar, Throat summit, Sky
  Haven, Labyrinthian, Blackreach) are Skyrim only. Non-Skyrim dungeons are theme output (`Theme`: barrow, cave,
  dwemer, ruin, fort, lair...) with the site's `variant` choosing the culture dressing. The only data-driven hookup,
  `dungeons/blueprints`, holds 9 prefabs of 5x5 blocks (small room props), not set-piece rooms.
* **Quests are rich but unattached to places.** 675 quests, 390 outside Skyrim, but the province side arcs
  (`side_<province>.json`, 12 to 15 each) spawn their bosses at **quest-private sites** defined inline
  (`side_cyr_narindel`, 17 to 27 per file), not at the 2,465 registered dungeons; and 112 of 160 non-Skyrim
  settlements have no quest and no named NPC at all.

## 2. Settlements

Counts from `places/*.json` (capital + city + town = "named towns"; village + farm = "small"). Bespoke = has its own
case in `Cities.build`. NPCs/quests = named NPC placed by a quest (`npcs[].place`), quests = distinct quests whose
start NPC or an objective is at a settlement of that province.

| Province | capital+city | town | small | bespoke | named towns with a named NPC | with a quest | named NPCs | Named inn / shop records |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Skyrim | 9 | 9 | 37 | 12 | 18 / 18 | 18 / 18 | 87 | 16 inns, 8 shops |
| Cyrodiil | 9 | 0 | 133 | 1 | 9 / 9 | 9 / 9 | 49 | 0 |
| Morrowind (+Solstheim) | 10 | 11 | 81 | 2 (+Raven Rock wall) | 9 / 21 | 9 / 21 | 54 | 3 (Raven Rock) |
| High Rock | 9 | 20 | 113 | 2 | 6 / 29 | 7 / 29 | 27 | 0 |
| Hammerfell | 9 | 19 | 52 | 1 | 6 / 28 | 6 / 28 | 30 | 0 |
| Elsweyr | 8 | 10 | 94 | 1 | 4 / 18 | 4 / 18 | 22 | 0 |
| Valenwood | 5 | 13 | 67 | 1 | 4 / 18 | 4 / 18 | 19 | 0 |
| Summerset | 5 | 11 | 43 | 1 | 2 / 16 | 4 / 16 | 14 | 0 |
| Black Marsh | 8 | 12 | 81 | 1 | 5 / 20 | 5 / 20 | 22 | 0 |
| Foreign lands | 0 | 1 | 5 | 0 | 0 / 1 | 0 / 1 | 0 | 0 |

Quests tied to a settlement (best and worst): Imperial City 31, Balmora 20, Raven Rock 15, Alinor 13, Daggerfall 12,
Sentinel 11, Vivec 11, Elden Root 11, Torval 11, Stormhold 10, Wayrest 10 ... but Mournhold 4, Lilmoth 4, Orsinium 5,
Necrom 3, Camlorn 1, Shornhelm 2, and **0** for every one of: Firewatch, Blacklight, Tear, Gnisis, Caldera, Molag Mar,
Dune, Orcrest, Alabaster, Corinthe, Silvenar, Rawlkha, Skywatch, Dusk, Cloudrest, Lillandril, Thorn, Soulrest, Archon,
Blackrose, Northpoint, Farrun, Jehanna, Gilane, Hegathe, Dragonstar, Abah's Landing and every town in High Rock,
Hammerfell, Elsweyr, Valenwood. Skyrim's bar: 7.5 quests and 4.8 named NPCs per named town (Riften 14, Solitude 13,
Falkreath 13, Whiterun 11).

### Per-province gaps in the towns

| Province | Biggest settlement gap | Skyrim-level equivalent |
| --- | --- | --- |
| Cyrodiil | 8 cities (Bruma, Chorrol, Cheydinhal, Skingrad, Kvatch, Anvil, Bravil, Leyawiin) all `generic` with one Imperial kit; only the Imperial City is unique. All have NPCs and quests, but nothing you could name from a screenshot. | Whiterun/Solitude/Windhelm/Riften each have their own kit, landmark and named inn. |
| Morrowind | Vivec and Mournhold are unique; Balmora, Ald'ruhn, Sadrith Mora, Necrom, Ebonheart, Molag Mar are 4 hashed kits on a street form. 12 of 21 named towns have no quest. | Skyrim's nine capitals. |
| Solstheim | Raven Rock has a wall ring only; Skaal Village, Thirsk and Tel Mithryn are villages with variants (`skaal`, `mead_hall`, `telvanni`) but no layouts. Lore is complete (44 entries). | Dawnstar-level. |
| High Rock | Wayrest and Orsinium share one code path; Daggerfall, Camlorn, Shornhelm, Northpoint, Evermor generic with the single Breton kit; 22 of 29 towns have no NPC or quest. | Wayrest/Daggerfall are the two biggest Daggerfall-game cities (reference: 851 and 1,331 locations). |
| Hammerfell | Sentinel only; Rihad, Taneth, Elinhir, Dragonstar generic; 22 of 28 towns empty. | Markarth-level identity. |
| Elsweyr | Torval is a Sentinel copy; Rimmen (palace + necropolis), Senchal (port of sugar), Dune generic. | Moon, sugar, dune identity absent. |
| Valenwood | Elden Root unique and good; Falinesti exists only as four place ids (`falinesti_*_site`), not as a walking city; Woodhearth, Silvenar, Rawlkha generic. | The walking city is the Whiterun-equivalent set piece. |
| Summerset | Alinor unique; Lillandril, Cloudrest, Skywatch, Dusk generic; only 2 of 16 towns have an NPC. | Solitude's rock-arch skyline level. |
| Black Marsh | Lilmoth unique; 5 other cities and 12 towns generic; 54% of the province's place ids are mechanical (`warm_tail_spring`, `reed_mist_ruins`). | Morthal's marsh identity, 6x. |
| Foreign lands | 6 settlements in 5 lands, none with a layout or NPC. | Raven Rock level. |

## 3. Dungeons and set pieces

"Generic" = `variant` in the generic list (cave, den, camp, mine, ruin, keep, fort, tower, grove, spring, rock...).
Hand-built = a `Special`, `Uniques` or `Blueprints` entry for that place id.

| Province | dungeon_fixed | landmarks + forts | generic variant share | hand-built set pieces | lore/quest-wired named dungeons | Quest-private sites in the province arc file |
| --- | --- | --- | --- | --- | --- | --- |
| Skyrim | 238 | 19 + 25 | 44% | 11 Specials + 5 Uniques | all 238 have lore; 156 mini-quests | 3 |
| Cyrodiil | 484 | 293 + 94 | 66% | 0 | 0 (25 lore: 16 shrines + 9 cities) | 21 |
| Morrowind | 436 | 66 + 25 | 56% | 0 (Solstheim: 40 lore'd places, no builds) | Solstheim 22 mini-quests; mainland 0 | 27 (+1 Solstheim) |
| High Rock | 415 | 76 + 35 | 64% | 0 | 0 | 21 |
| Hammerfell | 228 | 81 + 20 | 70% | 0 | 0 | 18 |
| Elsweyr | 193 | 96 + 15 | 74% | 0 | 0 | 20 |
| Valenwood | 198 | 103 + 5 | 75% | 0 | 0 | 23 |
| Summerset | 89 | 54 + 7 | 66% | 0 | 0 | 25 |
| Black Marsh | 393 | 110 + 12 | 70% | 0 | 0 | 17 |
| Foreign | 29 | 7 + 6 | 27 to 50% | 0 | 0 | 0 (10 quests use named dungeon ids) |

What that means in play: a player in Cyrodiil meets 66 Ayleid ruins (`ayleid_ruin` = one theme output at different
seeds), 35 numbered `ayleid_well_N`, 28 `wayshrine_of_<god>_N`, 84 `oblivion_gate_N`; in Black Marsh 97 xanmeers;
in Morrowind 100 `*_ancestral_tomb`, 31 egg mines; in Elsweyr 42 `*_moon_temple`. The audit (`DUNGEON-AUDIT.md`)
already scores the named ones at 15 to 27 / 35 with the weakest points being dressing, boss set piece and layout flow,
and the dungeon theme tooling has no per-place override for anything but Skyrim's `Special` enum (saved by ordinal in
`DungeonPiece`).

## 4. Lore, quests and moments by province

| Province | Place-lore entries (non-shrine) | Place mini-quests | Province quests (side arcs + faction + main by region) | Side-arc quests with a named boss | Skyrim equivalent |
| --- | --- | --- | --- | --- | --- |
| Skyrim | 372 | 156 | 285 | 16 | the bar |
| Cyrodiil | 9 | 3 | 78 | 16 | 1 mini-quest per 343 places (Skyrim: 1 per 2.4) |
| Morrowind (+Solstheim) | 60 | 22 | 105 | 14 (+10) | best covered; mostly Solstheim |
| High Rock | 6 | 7 | 44 | 14 | |
| Hammerfell | 6 | 6 | 30 | 14 | |
| Elsweyr | 4 | 3 | 28 | 14 | |
| Valenwood | 4 | 3 | 23 | 13 | |
| Summerset | 3 | 3 | 34 | 14 | |
| Black Marsh | 5 | 4 | 23 | 12 | |
| Foreign | 0 | 0 | 10 | 13 | |

Only Skyrim has a "journey" quest fabric: the pitch/rumour/readable loop reaches 99.7% of places. Outside Skyrim a
player can walk 500 blocks through a province without a single readable, rumour or hook.

## 5. Moments and landmarks

* Unique landmarks that exist as a built set piece (`Landmarks` variants, 19): Gildergreen, Skyforge, Talos statue,
  Solitude arch, College, sea stack, White-Gold Tower, giant tree, canton, dome temple/palace, xanmeer, crystal
  tower, castle, Markarth cliff, ratway, Blue Palace, rock, arena. Used in **10 cities**. The rest of the 4,900 places
  render as generic `landmark` styles (monument, stones, peak, island, pass, clearing...).
* Skyrim's moments: 7,000 Steps, High Hrothgar, Throat summit, Sky Haven, Labyrinthian, Blackreach, Word Walls
  (3), dragon mounds (22), standing stones (11). Outside Skyrim there are ~170 standing stones, 95 Oblivion Gates,
  and no moments that have a staged encounter, a vista or a unique structure: Red Mountain, the Ghostfence, the Crystal
  Tower of Summerset, the Elden Tree (exists as a city landmark), Falinesti, the Sentinel statue, the Hill of Shattered
  Swords, the Dragon Graveyard of Akavir exist only as ids.

## 6. Gap summary by province (what to do first)

| Province | Settlement identity | Dungeons | Lore/quests | Priority |
| --- | --- | --- | --- | --- |
| Morrowind | 6 big towns to differentiate; the Great Houses are the natural split | Kogoruhn, Ald Sotha, Arkngthand, Old Mournhold | 12 towns with no quest | **1** (reference data richest, kits exist) |
| Cyrodiil | 8 cities, Imperial City district tint | Miscarcand, Sancre Tor, Frostcrag | 25 lore, 3 mini | **2** (largest province, 8 cities) |
| High Rock | split Wayrest/Orsinium; 5 castle towns | Old Orsinium, Cath Bedraud | 22 towns empty | **3** |
| Valenwood | Falinesti, Woodhearth canopy | City of Ash, Ne Salas | 14 towns empty | 4 |
| Hammerfell | Rihad, Taneth, Elinhir, Dragonstar | Na-Totambu, Tuwhacca | 22 towns empty | 5 |
| Elsweyr | rework Torval, Rimmen, Senchal | Rimmen Necropolis | 14 towns empty | 6 |
| Summerset | Lillandril, Cloudrest, Skywatch | Psijic vaults | 12 towns empty | 7 |
| Black Marsh | Stormhold, Gideon, Helstrom | Mazzatun | 15 towns empty | 8 |
| Solstheim | 4 layouts | Miraak | already well covered | 9 |
| Foreign | one showpiece each | Wolf Jarl's Howe, Frost Throne | none | 10 |
