# Guards: how many, where, and which

Owner direction (playtest 5): no guards in the wilderness; one guard, wandering, in a minor settlement; two posted in
every major settlement, one on each side of the entrance; nothing in anything smaller; and the right guards for the
region. The rule is `society/GuardWatch.java`, the posts are `society/GatePosts.java`, the look of a guard is the table
`mod/src/main/resources/skycraft/creatures/guard_profiles.json` read by `creatures/GuardProfiles.java`.

## Count by settlement tier

| Tier | Which places (`places/*.json`) | Guards | Behaviour |
|---|---|---|---|
| **Major** | `capital`, `city`, and `town` of radius 90+ (the provincial seats: Raven Rock, Port Hunding, Murkmire, Arenthia, Firsthold) | exactly **2** | posted, one on each side of the **main gate** (the first of `SettlementAnchors.gates`, where the road enters), facing outward, stationary, replaced 30 s after a death |
| **Minor** | every other `town` (Riverwood-class), 99 of them | exactly **1** | patrols a route through the settlement (square, gate, carriage stop, stables, forge; ring points when too few are recorded), stops a few seconds at each, posted at no entrance; replaced 60 s after a death |
| **None** | `village`, `farm`, hamlets, camps, shacks, homesteads, forts, dungeons, landmarks, open country; also Helgen (scripted soldiers), the College of Winterhold (its own wards), the Skaal village | 0 | nobody keeps a watch; people still run from hostiles (`GuardProtection` no longer needs a guard present to do that) |

Counted from the places files: **77 major** (154 posted guards), **99 minor** (99 patrols), everything else none.

Gates: the old rule was two guards at every gate (up to 8 at a capital). The new one keeps the same two flank posts but
only at the main gate. `GuardWatch.posts(Watch)` is the one place that says which posts exist (`{gate index, side}`); to
spread the two over the first two gates instead, change it to `{{0, 0}, {1, 0}}` and nothing else.

What was removed: barracks, gate and palace guards from `GUARD` anchors (the housing plan holds no guard slots, except
Helgen's scripted garrison), `CreatureSpawns.maintainGuards` and the iron-golem-to-guard replacement, road patrols
(`Travelers.PATROL` is never rolled), the random thief-chase encounter (two guards in open country; still available when
forced). Guards an older save left in a settlement that belong to no post, patrol or scene are removed when a player is
near; old road patrols likewise. Kept: dungeon, fort and camp enemies, quest guards (`skycraft_quest_id`,
`skycraft_quest_npc`), guild staff, the jail's guard, soldiers on the roads (Legion and Stormcloak squads and tax
collectors are NPC soldiers, not hold guards) and Helgen's scripted soldiers.

## Crime still works with so few guards

* Bounties are per hold and are written when any civilian sees the crime; they never needed a guard to be near.
* Shout: when a witnessed crime raises a player's bounty, every guard within 80 blocks that is not already fighting
  drops its post or its route and walks to the player; the call is renewed every two seconds for 20 seconds after
  the crime. Inside 24 blocks the existing `crime/Guards` tick takes over (confront, arrest dialogue, attack on resist
  or 1000+). A posted guard goes back to its post when the alarm ends.
* The town alarm (`GuardProtection`, three or more hostiles) and the attack-a-citizen response work with any number of
  guards, one included.

## Which guard: resolution

First row that matches wins: **place id** (`settlements`) > **region id** (`regions`) > the place's **culture**
(`cultures`) > **province** (`provinces`) > `default`. A province with no art of its own wears the closest culture's.
Skyrim's holds each keep their own livery; the civil war's side (`quest/story/CivilWar`: a red Legion or blue
Stormcloak cuirass over the hold colours, by who holds the hold) is worn on top, as before.

| Profile (name) | Where | Skin | Weapon, shield |
|---|---|---|---|
| `whiterun`, `the_rift`, `eastmarch`, `the_pale`, `winterhold`, `haafingar`, `hjaalmarch`, `the_reach`, `falkreath` ("Whiterun Guard"... as before) | the nine holds of Skyrim; Nord and Atmoran places elsewhere wear Whiterun's | the hold's tunic colour, closed helm, cross | iron sword, shield |
| `imperial_watch` ("Imperial Watchman") | Cyrodiil (Imperial City, Bruma, Chorrol, Skingrad, Kvatch, Anvil, Bravil, Leyawiin, Cheydinhal) and Imperial-culture towns | crimson and gold, crested helm | Imperial sword and shield |
| `imperial_legion` ("Imperial Legionary") | the Empire's garrison towns of other provinces: Caldera, Ebonheart, Raven Rock, all of Solstheim; Akaviri places; the fallback | legion red, open helm | Imperial sword, light shield |
| `dunmer_redoran` ("Redoran Guard") | Ald'ruhn, Vvardenfell ashlands, Grazelands, Azura's Coast (Molag Mar, Gnisis) | rust and bone, open chitin helm | chitin sword, bonemold shield |
| `dunmer_hlaalu` ("Hlaalu Guard") | Balmora, West Gash, Bitter Coast, Stonefalls, Suran, Narsis, Tear (Dres have no art: closest is Hlaalu), Dunmer towns | teal and gold, mail coif | chitin sword, bonemold shield |
| `dunmer_telvanni` ("Telvanni Guard") | Telvannis, Sheogorad (Sadrith Mora, Port Telvannis, Gorne, Firewatch) | violet, hood | chitin sword, bonemold shield |
| `dunmer_ordinator` ("Temple Ordinator") | Vivec, Ascadian Isles, Mournhold and Deshaan, Necrom and Molag Amur, Red Mountain | olive and gold, full helm with slit | steel sword, bonemold shield |
| `breton_guard` ("Breton Man-at-Arms") | all High Rock, Breton places (also Jehanna in Wrothgar) | white surcoat, blue trim, mail coif | Breton sword and shield |
| `orc_stronghold` ("Orsinium Warrior") | Orsinium and Orc-culture places | moss and bronze, horned helm, tusks | orcish sword, Orsinium shield |
| `redguard_crown` ("Crown Guard") | Hammerfell interior: Alik'r, Bangkorai, Craglorn, Dragontail (Sentinel, Totambu, Rihad...); Yokuda | cream and gold, turban | Redguard sword, Ra Gada shield |
| `redguard_forebear` ("Forebear Guard") | Hammerfell ports: Hews Bane, Stros M'kai, Abibon-Gora, Lainlyn, Taneth | sea blue and silver, turban | Redguard sword, Ra Gada shield |
| `khajiit_dune` ("Dune Warden") | Anequina, Rimmen, Khajiit places outside Elsweyr | sand and rust, headband, fur | Khajiit sword and shield |
| `khajiit_moon` ("Lunar Guard") | Pellitine, Torval, Senchal (the moon-temple south) | night blue and silver, pale fur | Khajiit sword and shield |
| `altmer_aldmeri` ("Aldmeri Guard") | Summerset, Auridon, Altmer places; Pyandonea (Sea Elves, closest) | pale gold, crested helm | elven sword and shield |
| `bosmer_wood` ("Wood Warden") | Valenwood, Bosmer places | leaf green, hood, leather | Bosmer sword and shield |
| `argonian_hist` ("Marsh Warden") | Black Marsh, Argonian places | marsh olive and bone, open helm | Argonian sword, hide shield |

Guards outside Skyrim say only the lines that are not about Skyrim's dragons, draugr and mead. A province with no art
yet (Atmora, Akavir, Yokuda) uses a neighbour's: see `provinces` in the table.

## Checks

* `py -3 -I tools/qa/data_checks.py`: every profile has its skin (`guard_<id>.png`), its name and its item models;
  every row names a known place, region, culture or profile; no Skyrim hold livery outside Skyrim; every Skyrim
  settlement resolves to its own hold.
* `py -3 -I tools/qa/population_lint.py` (qa.ps1 -Server, `/skypop <id>`): per settlement the watch is 2 + 0, 0 + 1 or
  0 + 0, and no housing resident is a guard (Helgen excepted).
* `SocietySelfTest` (`/skycraft-society selftest`): the rule on the whole places file; the guard of 20+ settlements over
  the provinces; Whiterun has exactly two posted guards at its main gate, nobody else, and a killed one is replaced;
  Riverwood has exactly one patrolling guard and a second visit adds none; Old Hroldan has none and no guard stands
  within 300 blocks of it unmarked.
* Skins are generated: `tools/textures/creatures.py` (guard section) reads the same table.
