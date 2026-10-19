# The lore web: a connected web of mysteries

Every marquee settlement, dungeon, landmark and minor site of `tools/marquee/marquee.json` (all 231) has a
**situation**: a gimmick of its landscape, a mystery you learn slowly, three environmental clues a builder can place,
two follow-up links to other places and a payoff. The situations are organised into **39 regional arcs**
(4-8 places each, a trail of clues with a finale) and **8 continent threads** (7-9 places across several
provinces). Together they form one graph (462 links, 42 of them across provinces) in which no place is an orphan.

Legal: facts and themes only; every name of an invented person, every note and every line is original text.

## The idea

The best places have a reason to be where they are (a cliff over the sea, a caldera, a sunken court) and something
strange that makes you stop on arrival: a cult, a curse, a spreading blight, a strange trade, a sealed vault, a lost
expedition. Each place here states that strangeness once, then leaves **three clues** in the environment (sigils,
failing crops, mourning flags), a **readable note** and a **rumour** that point at the next place. Following the trail
is the reward; the finale of each arc is a named foe and a real payoff.

## What the player sees

| What | How it is delivered |
| --- | --- |
| rumour | the place's lore `rumour` (townsfolk "Any news?", innkeepers) |
| hook | the place's lore `hook` (map tooltip, discovery line): the site gimmick |
| note | a readable in the place's chests, boss chest or lectern (`web` = the arc's note, `thread` = the thread's note). Reading it marks the next place on the map (`reveals`) |
| hidden quest | the first place of every arc and thread holds a clue ("Something here catches your eye"): a stone, lectern, urn, grave, skeleton, lever or totem that starts quest `lw_<arc>_a` |
| follow-on | quest `lw_<arc>_b` starts by itself when part a is done and ends with a named boss at the finale |

The last place of an arc links to the first place of the next arc of the same province (and its note reveals it), so
the arcs of a province also chain into a ring.

## Data and files

| File | Role |
| --- | --- |
| `tools/marquee/loreweb_src/<province>.json`, `threads.json`, `extra.json` | authored source |
| `tools/marquee/loreweb_build.js` | builds everything below from the source |
| `tools/marquee/loreweb_doc.js` | rewrites the tables of this file |
| `tools/marquee/loreweb.json` | machine-readable web for builders (city plans, landmarks, scatter) |
| `mod/src/main/resources/nirncraft/lore/places/loreweb.json` | hooks, rumours and notes (listed in that folder's `index.json`) |
| `mod/src/main/resources/nirncraft/quests/loreweb_<province>.json`, `loreweb_threads.json` | quests, ids `lw_*` (listed in `quests/index.json`) |
| `tools/marquee/loreweb_check.js` | verifies links, orphans, starts and ends |

Workflow: edit `loreweb_src`, then `node tools/marquee/loreweb_build.js`, `node tools/place_lore.js`,
`node tools/quest_lang.js`, `node tools/marquee/loreweb_check.js`, `node tools/marquee/loreweb_doc.js`.

A place's lore lives in exactly one file. 62 of the 231 marquee places already had lore in another file
(capitals, the Skyrim-adjacent writers' work, Solstheim); for those the web adds no note or rumour (`lore_elsewhere`
is set), but their situation, clues and quests are still part of the web, and builders may place their clues as well.
The other 169 places get a hook, a rumour and one or two notes from the web.

## Schema of `tools/marquee/loreweb.json`

```json
{
  "version": 1,
  "arcs": [{"id": "cyr_1", "title": "...", "province": "cyrodiil", "tier": "S|A|B", "secret": "the truth of the arc",
            "places": ["ordered place ids"],
            "entry": {"place", "clue": "stone|lectern|urn|grave|skeleton|lever|totem", "offset": [dx, dz], "text": "the clue as the player reads it"},
            "finale": {"place", "boss": "name", "reward": {"gold", "loot", "unique"}},
            "quests": ["lw_cyr_1_a", "lw_cyr_1_b"]}],
  "threads": [{"id": "t_welkynd", "title": "...", "tier": "...", "secret": "...", "places": ["..."], "entry": {}, "finale": {},
               "provinces": ["..."], "quests": ["lw_t_welkynd_a", "lw_t_welkynd_b"]}],
  "situations": {
    "<place id>": {
      "id": "...", "name": "...", "province": "...", "kind": "settlement|dungeon|landmark|minor", "tier": "S|A|B",
      "place_type": "...", "region": "...",
      "arc": "cyr_1", "arc_step": 1, "thread": "t_welkynd (optional)", "thread_step": 1,
      "site":    "(a) the gimmick: the landscape or placement that defines it (also the lore hook)",
      "mystery": "(b) what you slowly learn",
      "clues":   ["(c) three environmental clues a builder can place"],
      "follow":  ["(d) two place ids: the next step, and a thread or neighbour link"],
      "payoff":  "(e) unique loot, spell, relic or location",
      "rumour":  "the rumour line (absent when the place's lore is written elsewhere)",
      "thread_clue": "the thread's extra clue, if the place is on a thread",
      "lore_note": ["place.<id>.web", "place.<id>.thread"], "lore_elsewhere": "file holding its lore, if any",
      "quests": ["lw_..._a"]
    }
  }
}
```

**For builders.** `clues` and `thread_clue` are short, placeable descriptions ("a scaffold post tallied with a thousand
chalk strokes", "ash seats facing an empty stage"). A city plan or landmark builder looks the place id up in
`loreweb.json` and sets the clues as signs, props, banners or decals near the described spot; `site` is the
landscape gimmick the layout should honour (cliff, caldera, causeway, hanging gardens). `arcs[].entry.offset` is where
the hidden clue of the arc waits, relative to the place centre.

## Regional arcs (39: 31 tier A, 8 tier S)

| id | Province | Arc | Tier | Trail (first > ... > finale) | Finale foe |
| --- | --- | --- | --- | --- | --- |
| cyr_1 | Cyrodiil | The Debt of Names | A | `shardiuss_excavation` > `gate_of_alma_ruma` > `thalaras_winery` > `mist_mirror_refuge` > `skingrad` > `miscarcand` | The Warden of Names |
| cyr_2 | Cyrodiil | The Gate That Was Opened | S | `lunar_fang_docks` > `dark_brotherhood_sanctuary_cyrodiil` > `anvil` > `deepscorn_hollow` > `kvatch` > `kvatch_gate` | Maelis the Candle-Bearer |
| cyr_3 | Cyrodiil | The Dragon-Hunters' Vigil | A | `weynon_priory` > `the_sentinel` > `bruma` > `cloud_ruler_temple` > `frostcrag_spire` > `sancre_tor` | Hatsu the Unrung |
| cyr_4 | Cyrodiil | The Ring Beneath the Rings | A | `imperial_bridge_inn` > `chorrol` > `cheydinhal` > `bravil` > `imperial_city` | Auditor Vell Corvus |
| mor_1 | Morrowind | The Sleepers of the Ashlands | S | `khuul` > `ald_velothi` > `urshilaku_camp` > `ghostfence_morrowind` > `missamsi` > `kogoruhn` > `red_mountain` | The Ash-Dreamer |
| mor_2 | Morrowind | The Seventh Canton | S | `seyda_neen` > `pelagiad` > `fields_of_kummu` > `ald_sotha` > `vivecs_antlers` > `ebonheart` > `vivec` | Canon-Clerk Ravel Maryon |
| mor_3 | Morrowind | The Guest of Brass | A | `hla_oad` > `gnaar_mok` > `balmora` > `rkuldumz_spire` > `halls_of_fabrication` > `arkngthand` | Collector Mavos Hlaren |
| mor_4 | Morrowind | The Council of the Quiet | A | `tel_branora` > `molag_mar` > `necrom` > `sadrith_mora` > `aldruhn` > `mournhold` > `old_mournhold` | Speaker of the Quiet Vote |
| sol_1 | Solstheim | The Last Seed-Bank | A | `old_attius_farm` > `raven_rock` > `brodir_grove` > `fort_frostmoth` > `ashfallow_citadel` > `tel_mithryn` > `temple_of_miraak` | Warden of the Greenhouse |
| sol_2 | Solstheim | Six Stones, Five Songs | A | `abandoned_lodge` > `hvitkald_peak` > `stalhrim_source` > `allmaker_water_stone` > `skaal_village` > `altar_of_thrond` > `gyldenhul_barrow` | Hjorda Ice-Voice |
| sol_3 | Solstheim | The Frozen Engine | A | `ramshackle_trading_post` > `bujolds_retreat` > `haknirs_shoal` > `thirsk_mead_hall` > `kagrumez` > `nchardak` > `castle_karstaag_ruins` | Karstaag's Last Thrall |
| hr_1 | High Rock | The Uncounted King | A | `aldhaven_harbor` > `glenpoint` > `wayrest` > `daggerfall` > `camlorn` > `cath_bedraud` > `tomb_of_lost_kings` | Master-Mason Orvane |
| hr_2 | High Rock | Cream-Coloured Invitations | A | `the_lonely_lantern` > `moiras_hope` > `northpoint` > `shornhelm` > `doomcrag` > `sanguines_demesne` | The Host of Plums |
| hr_3 | High Rock | Names Before Death | A | `morkul_stronghold_high_rock` > `paragons_remembrance` > `wrothgarian_mountains` > `orsinium` > `old_orsinium` | Hargruk the Unwritten |
| hr_4 | High Rock | The Giant That Drinks | A | `koeglin_village` > `weeping_giant` > `evermor` > `isle_of_balfiera` > `mthartzel` > `direnni_tower` | The Keeper of the Pump |
| ham_1 | Hammerfell | The Thirst of the Buried City | S | `the_silver_pilgrim` > `goats_head_oasis` > `sentinel` > `volenfell` > `motalion_necropolis` > `tuwhaccas_throne` > `lost_city_of_the_na_totambu` | Herald of the Dune-King |
| ham_2 | Hammerfell | Reliefs That Remember Forward | A | `valley_of_scars` > `elinhir` > `dragonstar` > `aetherian_archive` > `sanctum_ophidia` > `skyreach_pinnacle` | The Last Panel |
| ham_3 | Hammerfell | The Gardener of Ahaleb | A | `thrall_cove` > `abahs_landing` > `taneth` > `hubalajad_palace` > `rihad` > `ahaleb_necropolis` | Lord Hubalajad's Steward |
| ham_4 | Hammerfell | The Sunken Clause | A | `port_hunding` > `saintsport` > `draggin_tale_inn` > `stros_mkai` > `onsis_breath` | The Prow-Keeper |
| els_1 | Elsweyr | The Late Moon | S | `star_haven_adeptorium` > `shrine_of_the_cats_divine` > `torval` > `temple_of_agamanus` > `tomb_of_the_moon_singers` > `vault_of_the_heavenly_scourge` | The Blade That Woke Early |
| els_2 | Elsweyr | The Faceless of Rimmen | A | `roadside_twin_moons` > `baandari_caravan_stop` > `hakoshae` > `rimmen` > `anequina_aqueduct` > `rimmen_necropolis` | The Unmasked Warden |
| els_3 | Elsweyr | The Glow Beneath the Sugar | A | `tathara_sugar_farm` > `moon_sugar_meadow` > `the_prancing_netch` > `corinthe` > `senchal` > `halls_of_colossus` | The Unfinished Colossus |
| els_4 | Elsweyr | The Oath-Forge | A | `orcrest` > `dune` > `ne_quin_al` > `hill_of_shattered_swords` > `prowls_edge_dragonscour` | Khasra the Oathless |
| val_1 | Valenwood | The Crown Lamp | S | `elden_tree` > `ne_salas` > `reliquary_of_stars` > `elden_root` > `ossuary_of_telacar` | Keeper of the Crown |
| val_2 | Valenwood | Where the City Walks | A | `roadside_green_bough` > `supplication_house` > `drabul` > `jathsogur` > `silvenar` > `falinesti` > `tomb_of_the_apostates` | The Chanting Apostate |
| val_3 | Valenwood | The Citizens' Orchard | A | `marbruk` > `woodhearth` > `labyrinth` > `city_of_ash` | Ashen Archon |
| val_4 | Valenwood | The Truce of Bones | A | `waterdancer_falls` > `sren_ja` > `dokrin_monastery` > `rawlkha` > `arenthia` > `thizzrini_arena` > `reapers_henge` | Gorrim of the Broken Bone |
| sum_1 | Summerset | The Skipped Hour | S | `isle_of_contemplation` > `colosseum_of_the_old_ways` > `alinor` > `traitors_vault` > `crystal_tower_summerset` > `psijic_relic_vaults` | The Hour's Keeper |
| sum_2 | Summerset | The Glass Fever | A | `dawnbreak` > `maras_kiss_public_house` > `shattered_grove` > `firsthold` > `skywatch` > `the_banished_cells` > `mehrunes_spite` | The Splinter-Prophet |
| sum_3 | Summerset | The Egg-Tithe | A | `kings_haven` > `the_golden_netch` > `shimmerene` > `gryphon_aerie` > `cloudrest` > `tor_hame_khard` | Steward Aelindor |
| sum_4 | Summerset | The Silk Library | A | `trebbite_monastery` > `dusk` > `lillandril` > `direnni_acropolis` > `cathedral_of_webs` | The Loom-Mother |
| bm_1 | Black Marsh | The Tapped Root | A | `stay_moist_mansion` > `xal_haj_ei_shrine` > `weeping_wamasu_falls` > `stormhold` > `temple_of_sul` > `bogmother` > `shrine_of_the_black_maw` | Factor Delvyn Sull |
| bm_2 | Black Marsh | The Warm Count | A | `hatchlings_crown` > `hatching_pools` > `lilmoth` > `the_vile_pavilion` | The Lord of the Long Supper |
| bm_3 | Black Marsh | The Extra One | A | `jeevu_rice_paddy` > `root_whisper_village` > `thorn` > `helstrom` > `ruins_of_mazzatun` | The Counted Shade |
| bm_4 | Black Marsh | The Rerouted Dirge | A | `hutan_tzel` > `grave_singers_lodge` > `plateau_of_the_traveler` > `gideon` > `soulrest` > `murkmire` > `teeth_of_sithis` | Lord Heel of the Dry Fields |
| for_1 | Foreign lands | The Last Voyage Home | A | `saarvik_landing` > `skjaldborg` > `kjolvik` > `the_frozen_dragon_mound` > `wolf_jarls_howe` | Hjalti the Ice-Sworn |
| for_2 | Foreign lands | The Four-Court Seal | S | `tang_mo_watch` > `tang_mo_harbor_village` > `tiger_plains_village` > `temple_of_the_serpent_emperor` > `dragon_graveyard_of_akavir` > `frost_throne_of_kamal` | Kamal's Frost-Warden |
| for_3 | Foreign lands | The Walking Reef | A | `serpent_pens` > `pyandonean_fleet_anchorage` > `coral_tower_of_thras` > `orgnums_citadel` | Orgnum's Reef-Warden |
| for_4 | Foreign lands | The Last Lesson | A | `ra_gada_encampment` > `hundings_dojo` > `temple_of_satakal` > `sunken_temple_of_the_yokudan_kings` | The Barge-Master |

### The secrets of the arcs

* **The Debt of Names** (cyr_1): Ayleid captives' names were never freed; a Colovian winemaking family has kept a name-vault alive under their vines for generations, and someone in Skingrad is quietly buying the vintages.
* **The Gate That Was Opened** (cyr_2): The Oblivion gate at Kvatch was not opened by a stranger: the Ashen Candle, a lay brotherhood of Gold Coast fishermen who prayed for the drowned, was tricked into leaving a door unlatched. The survivors still hide among the rebuilders.
* **The Dragon-Hunters' Vigil** (cyr_3): The Akaviri who built Sancre Tor did not come to conquer; they came to bury something. A Blades archivist has been quietly relocating the vigil-bells of the Jerall passes to keep a seal from being rung open.
* **The Ring Beneath the Rings** (cyr_4): A pre-Alessian reservoir runs under every great Cyrodiil city. The Imperial Treasury has been quietly draining it into private vaults, and the drowned Ayleid water-magic is going sour.
* **The Sleepers of the Ashlands** (mor_1): Since the blight faded, the ash itself dreams. The north-coast sleepers who walk uphill by night are not corprus victims but the ash's chosen audience, and a dwindling dream-cult keeps them from reaching the crater.
* **The Seventh Canton** (mor_2): Vivec's six cantons are a cover: a seventh sits below the lagoon, a quiet administrative afterlife where the Temple files every pilgrim who never came home. Imperial Ebonheart has been paying for it.
* **The Guest of Brass** (mor_3): A Hlaalu collector is reassembling a single Dwemer attendant from fisherman-salvaged fragments. The machine, when whole, asks only for one thing: to be told whether its makers are coming back.
* **The Council of the Quiet** (mor_4): The Great Houses' ancestor ghosts have begun to vote: a shadow council in Old Mournhold's lower mirror-hall that steers the living by bequest. The Temple clerks of Necrom keep its minutes.
* **The Last Seed-Bank** (sol_1): Before the first eruption an Imperial botanist sealed a seed-bank of unblighted crops in the cellar of an old Nordic temple. The yam farmers, the Telvanni gardener and the Redoran bulwark each know a third of the way in.
* **Six Stones, Five Songs** (sol_2): One of the Skaal's six All-Maker stones has gone mute. The elder hid it. The lost hunter at the lodge went to wake it, and is held in the barrow's mural, still singing the missing verse.
* **The Frozen Engine** (sol_3): Karstaag's throne of ice is a Dwemer cold-engine. Haknir's crew sailed for it, split its charts among four hands, and lost the ship. The pieces are in the trader's crates, the lodge, the mead-hall's saga and the wreck.
* **The Uncounted King** (hr_1): The royal tomb carves seven kings of the old line. There were eight. A stonemasons' guild paid by three Iliac crowns is quietly recarving the family tree to erase the eighth, whose heirs would void three thrones.
* **Cream-Coloured Invitations** (hr_2): The Rivenspire fog is the carpet of a party that never ended. Invitations on cream paper appear in lanterns and doorframes, and those who say yes are collected by the fog. The Doomcrag necromancer sold the guest list.
* **Names Before Death** (hr_3): A new name is carved on the memorial column every dusk, of Orcs not yet dead. A smith-spirit in the old city's collapsed forge keeps an account of those who will fall in the rebuilding, and cannot stop.
* **The Giant That Drinks** (hr_4): The toppled giant beneath the inn is a Direnni construct kept alive by a Dwemer pump at Mthartzel; its tears are seawater from a drowned cavern. When the pump is stopped, the island of Balfiera rises.
* **The Thirst of the Buried City** (ham_1): The buried city of the Na-Totambu draws the oases down its glass-roofed shaft, each season an inch. A herald of the dead dune-king collects the water-tithe from caravan masters and sells their debts on.
* **Reliefs That Remember Forward** (ham_2): The battle-reliefs of the Valley of Scars carve themselves a little each season, showing a battle that has not happened. The arena-master in Dragonstar stages fights to fit the stone, and a serpent cult believes the last panel shows the world's end.
* **The Gardener of Ahaleb** (ham_3): A Hews Bane lord buys freed slaves' debts and sends them, not to work, but to tend a hidden garden of palms in a necropolis. It blooms only for descendants of the dead, and he is slowly planting his entire line in it.
* **The Sunken Clause** (ham_4): The treaty that freed Stros M'Kai carried a secret clause: every fifth year a ship must sail past the horizon with a cargo no one names. The inn's dragon tail is the prow of the first such ship, and the cargo is a torn page of the Tide-Ledger.
* **The Late Moon** (els_1): Secunda rises a few minutes later each month. The Moon-Priests know and hide it, because the holy calendar sets the sugar harvest and the temple fees. The vault of lunar blades is waking to a moonrise that no longer comes on time.
* **The Faceless of Rimmen** (els_2): Burial masks of Rimmen's honoured dead are being stolen and sold abroad by the Baandari. A dead Khajiit without a mask wanders as a faceless guardian, and the moonstone-ring village at Hakoshae has run out of ways to bury them.
* **The Glow Beneath the Sugar** (els_3): Moon-sugar's glow comes from a coolant that leaked from a Dwemer colossus. The meadow blooms over the leak, the boil-houses of Senchal cut the refined with colossus dust, and the sleepwalkers of Corinthe are a symptom, not an addiction.
* **The Oath-Forge** (els_4): Every sword broken in Anequina's wars is carried by caravan to the hill. They re-form each year into a single blade of a hundred oaths, quenched in the bone trough of the dragon-scour. A faction of clan-chiefs wants to claim it; the blade wants none of them.
* **The Crown Lamp** (val_1): The Elden Tree's glow is not the tree's own. An Ayleid star-lamp lodged in its crown by the first Bosmer settlers holds the Ayleid dead at bay, and its keepers have run out of names to feed it.
* **Where the City Walks** (val_2): Falinesti is not walking by season: it is being led, by the chant of the apostates in a hollow tree-tomb, toward a hollow that will drown it in roots. Hermits and Orc outposts are secretly cutting guide-trees to break the chant, which the Green Pact forbids.
* **The Citizens' Orchard** (val_3): The ring of living trees at Woodhearth are the Ayleid citizens of the burned city, turned by a Bosmer 'mercy' ritual rather than let die. Their roots remember, and they are leaning toward the ash-city, to go home.
* **The Truce of Bones** (val_4): Each generation the Bosmer and Khajiit elders of Reaper's March place a bone on the henge altar to renew the Truce. This time one bone is missing, and each stop on the trail holds a piece of the Truce's rite: a song, an arena's sand, a falls' name.
* **The Skipped Hour** (sum_1): Alinor's bells chime twenty-three hours, not twenty-four. The Psijic Order excised one hour from the capital's record to bury a single act. The hour survives as a room that rearranges itself in the relic vaults.
* **The Glass Fever** (sum_2): Crystal splinters from a Daedric seed in Shattered Grove are rooting in the villagers. A Dagon cult in a red fortress coaxes the fever, and the victims hide under the cells of the prison under the spire.
* **The Egg-Tithe** (sum_3): The cantilevered balconies of Cloudrest are held up by stone tuned to gryphon-song. Eggs are not hatching because the palace steward has been selling them to pay a debt, and the song is fading.
* **The Silk Library** (sum_4): Lillandril's books are vanishing because giant spiders are weaving their words into silk. A vanished librarian lives in the web, reading the silk aloud to keep it from being lost.
* **The Tapped Root** (bm_1): A merchant's stilt mansion has been tapping a Hist root for sap sold abroad. The falls that weep, the hag that spawns and the black pool are all the marsh's own ways of crying out.
* **The Warm Count** (bm_2): The hatching pools are cooling and the eggs hatching pale. A banquet-ghost in the rotting pavilion draws heat for the plates of a supper that never ends.
* **The Extra One** (bm_3): Every census in the Shadowfen counts one more than the roll. The extra is the village's first settler, who was never named, and whose seat has been kept for generations at a rice-paddy table, a village fire and a Hist pool.
* **The Rerouted Dirge** (bm_4): The grave-singers carry the dead down the delta to the island necropolis by song. A Gideon plantation lord has quietly rerouted the dirge's path through his dry fields, in the belief that the dead's blessing will bring rain.
* **The Last Voyage Home** (for_1): The last Atmoran hold stands on an ice shelf that is about to calve. A frozen dragon in the mound keeps the shelf bound; the whole ship of the dead captain in the howe is the only vessel left to carry the living out.
* **The Four-Court Seal** (for_2): Each of Akavir's four courts holds one quarter of a seal-name that keeps the dragon-graveyard shut. The Tsaesci serpent-court wishes to gather all four and open the valley. The ice throne of Kamal holds the last quarter.
* **The Walking Reef** (for_3): A reef of plague-coral from the Thras sea is spreading across the Maormer isles, slowly walking toward the fleet raft. The serpent pens feed on it and grow monstrous, and the citadel's window on the deep shows the reef's head.
* **The Last Lesson** (for_4): The Ra Gada sword-masters' final lesson is a voyage: each school teaches one form, and the last student rows the funeral barge of the Yokudan kings home. No student has finished in three hundred years.

## Continent threads (8)

| id | Thread | Provinces | Trail |
| --- | --- | --- | --- |
| t_welkynd | The Welkynd Census | Cyrodiil, Valenwood, Summerset | `gate_of_alma_ruma` > `shardiuss_excavation` > `miscarcand` > `ne_salas` > `reliquary_of_stars` > `city_of_ash` > `crystal_tower_summerset` > `psijic_relic_vaults` |
| t_ash | The Ash Remembers | Morrowind, Cyrodiil, Solstheim, Black Marsh | `ghostfence_morrowind` > `red_mountain` > `cheydinhal` > `raven_rock` > `brodir_grove` > `helstrom` > `thorn` > `root_whisper_village` |
| t_dial | The Dwemer Dial | Morrowind, Solstheim, High Rock, Hammerfell, Elsweyr | `halls_of_fabrication` > `arkngthand` > `nchardak` > `kagrumez` > `mthartzel` > `volenfell` > `halls_of_colossus` > `rkuldumz_spire` |
| t_sugar | The Sugar Road | Elsweyr, Valenwood, Cyrodiil, Morrowind | `moon_sugar_meadow` > `tathara_sugar_farm` > `senchal` > `baandari_caravan_stop` > `rawlkha` > `bravil` > `lunar_fang_docks` > `balmora` > `gnaar_mok` |
| t_bone | The Bone Census | Elsweyr, Cyrodiil, Solstheim, Hammerfell, Foreign lands | `prowls_edge_dragonscour` > `sancre_tor` > `hvitkald_peak` > `onsis_breath` > `the_frozen_dragon_mound` > `dragon_graveyard_of_akavir` > `temple_of_the_serpent_emperor` |
| t_tide | The Tide-Ledger | Foreign lands, Hammerfell, Solstheim, Summerset | `temple_of_satakal` > `port_hunding` > `stros_mkai` > `haknirs_shoal` > `firsthold` > `serpent_pens` > `orgnums_citadel` > `ra_gada_encampment` > `sunken_temple_of_the_yokudan_kings` |
| t_crown | The Sundered Crown | High Rock, Hammerfell, Valenwood, Black Marsh, Solstheim, Foreign lands | `tomb_of_lost_kings` > `tuwhaccas_throne` > `tomb_of_the_apostates` > `soulrest` > `old_orsinium` > `gyldenhul_barrow` > `wolf_jarls_howe` > `frost_throne_of_kamal` |
| t_falls | The Silent Falls | Cyrodiil, Valenwood, Summerset, Elsweyr, Morrowind, High Rock, Solstheim, Black Marsh | `mist_mirror_refuge` > `waterdancer_falls` > `tor_hame_khard` > `anequina_aqueduct` > `molag_mar` > `weeping_giant` > `stalhrim_source` > `weeping_wamasu_falls` |

* **The Welkynd Census** (t_welkynd): Ayleid lamp-wardens tuned their light-stones into one continental instrument. A scholars' cabal from the Summerset libraries, calling itself the Lampwrights, is relighting the dead stones in order. When the last is lit, the Crystal Tower's lens will show the sky as it was before the fall, and wake the name-vaults.
* **The Ash Remembers** (t_ash): Ash from Red Mountain carries a faint memory of the people it burned. A Telvanni theory holds that the memory seeds where it falls, in groves, in marshes and in the dreams of exiles, and that a Hist-tree in the south is the only thing that can read it all.
* **The Dwemer Dial** (t_dial): Each Dwemer ruin keeps a bronze dial in its deepest hall, all set to the same figure and all turning forward since the Heart was silenced. At the end of its sweep, the spire at Rkuldumz will answer. The dials were a countdown to a return, or a recall.
* **The Sugar Road** (t_sugar): A hidden counting-house buys the debts of every sugar farm, caravan and dockside inn from the Elsweyr coast to the Bitter Coast. Its patron is a pale lender who collects years of labour rather than gold, and keeps his strongbox on the stilts of a swamp market.
* **The Bone Census** (t_bone): Every ancient dragon grave on Tamriel and Akavir is missing one vertebra. The Tsaesci serpent-court has been collecting them to build a spine, a ladder to somewhere high, and its agents have visited every barrow and mound in the last ten years.
* **The Tide-Ledger** (t_tide): The last navigator of the Yokudan fleet tore his tide-ledger in nine and hid the pieces in nine ports across the seas, so no single people could chart the road home. The Maormer want the road; the Redguards want the route to a hidden isle of kings; the Altmer want to bury it again.
* **The Sundered Crown** (t_crown): A conqueror known by a dozen names, remembered by every people as a foreign king, was buried in eight pieces under eight realms' royal tombs so he could not rise. Someone is gathering the regalia, a finger-ring in a barrow, a crown in a ship, a throne-arm in the ice, to make him whole.
* **The Silent Falls** (t_falls): The continent's great waterfalls and cascades are falling silent one night at a time, each hush a verse of a binding lullaby. A coven of water-witches is singing the Weeping One, a buried wamasu fossil under the Black Marsh falls, into a sleep it will never wake from, and the world's tears with it.

## The graph

* **Nodes**: the 231 marquee places. **Edges**: `follow` (two per place, 462 in all).
* **Arc edge**: every place points to the next place in its arc. The finale of an arc points to the first place of the
  next arc of the same province and, if it belongs to a thread, on along the thread.
* **Thread edge**: a place on a continent thread points to the next place on the thread, which may lie in another
  province. Threads cross each other and the arcs: the Welkynd Census runs from Cyrodiil through Valenwood to Summerset, the
  Ash Remembers from Red Mountain through Cyrodiil and Solstheim to the Shadowfen, and the Tide-Ledger from Yokuda through
  Hammerfell, Solstheim, Summerset and the Maormer isles.
* **Neighbour edge**: when a place has no thread, its second link is its nearest marquee neighbour in another arc
  (preferring the same province), so following one trail keeps surfacing the next.
* The web is one connected component; `loreweb_check.js` fails on any orphan, dead end, missing target or unstarted arc.

## Writing guidelines (for later additions)

1. A place's mystery is about **this** place and its region (Dunmer house politics and ash blight, Skyrim barrows and
   dragon cults, Cyrodiil's Ayleid and Mythic Dawn echoes, Hammerfell's Forebears and Na-Totambu, Elsweyr's moons and sugar,
   Valenwood's Green Pact, Summerset's Psijic time, Black Marsh's Hist).
2. Do not repeat a mystery; vary the shape (a cult, a debt, a counting, a missing hour, a forged tomb, a tapped root...).
3. The three clues are things a builder can place without scripting. The note holds a concrete lead to the next place.
4. Notes are 150+ characters in the voice of whoever wrote them; the hook is 60-320 characters in narrator voice.
5. Only existing place, creature, item and unique ids; the validators tell.
