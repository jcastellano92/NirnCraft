"""Original text of the exploration module: site names, notes, journals and unique items.

Everything here is written for Skycraft (no game text is copied). gen_explore_data.py turns it into the lang file
(mod/src/main/lang/skycraft/explore.json), the note assets (assets/skycraft/lore/books/explore_*.json) and the
uniques data file.
"""

SITE_NAMES = {
    "abandoned_camp": "Abandoned Camp",
    "hunters_camp": "Hunter's Camp",
    "wayshrine": "Wayshrine",
    "treasure_cache": "Treasure Cache",
    "dead_adventurer": "Fallen Adventurer",
    "herb_garden": "Overgrown Herb Garden",
    "rare_reagent": "Rare Reagents",
    "stone_circle": "Stone Circle",
    "ancient_battlefield": "Ancient Battlefield",
    "mage_ruin": "Ruined Mage Tower",
    "hermit_hut": "Hermit's Hut",
    "bandit_lookout": "Bandit Lookout",
    "overlook": "Overlook",
    "mineral_vein": "Exposed Vein",
    "shipwreck_debris": "Shipwreck Debris",
    "fishing_dock": "Fishing Dock",
    "hot_spring": "Hot Spring",
    "dwemer_vent": "Dwemer Vent",
    "nordic_cairn": "Nordic Cairn",
    "ayleid_well": "Ayleid Well",
    "velothi_shrine": "Velothi Wayshrine",
    "hist_sapling": "Hist Sapling",
    "moon_sugar_field": "Moon Sugar Field",
    "giant_beehive": "Giant Beehive",
    "crystal_outcrop": "Crystal Outcrop",
    "yokudan_obelisk": "Yokudan Obelisk",
    "breton_menhir": "Breton Menhir",
    "akaviri_shrine": "Akaviri Shrine",
    "sea_shrine": "Shrine of the Deep",
    "realm_monolith": "Uncharted Monolith",
}

MISC_LANG = {
    "explore.skycraft.found": "Found: %s",
    "explore.skycraft.lead.revealed": "The note mentions a place. Your map has been updated: %s",
    "explore.skycraft.ayleid_well.restored": "The welkynd stone hums, and your magicka floods back.",
    "explore.skycraft.ayleid_well.spent": "The well is dim. Its power will return tomorrow.",
    "explore.skycraft.treasure_map.marked": "You study the map and mark the spot on your own.",
    "explore.skycraft.treasure_map.found": "You found the cache marked on %s.",
    "explore.skycraft.treasure_map.near": "Somewhere near %s",
    "explore.skycraft.unique.tag": "Unique",
    "item.skycraft.treasure_map": "Treasure Map",
    "item.skycraft.treasure_map.numbered": "Treasure Map %s",
    "item.skycraft.treasure_map.blank": "The ink has run; nothing can be read.",
    "item.skycraft.treasure_map.where": "A sketch of the land in %s",
    "item.skycraft.treasure_map.hint": "Use it to study the drawing. Look for a cairn of stones.",
    "block.skycraft.ayleid_well": "Ayleid Well",
}

# ---------------------------------------------------------------------------------------------------------------
# Notes: id -> (title, author, [pages])

NOTES = {
    # ---- journals left at abandoned camps
    "explore_journal_snowbound": ("Snowbound Journal", "Torva, a traveller", [
        "Third day under the canvas. The snow will not stop and Hjalmund has gone ahead to find the road. He said he "
        "would be back by dark. I have kept the fire low to save the wood.\n\nI counted the dried meat twice. If I "
        "am careful, five more days.",
        "Fifth day. No Hjalmund. Wolves in the night, close enough that I heard them breathing. I have decided to go "
        "after him at first light and leave this book here, in case he comes back another way.\n\nHjalmund, if you "
        "read this: I went down the slope, towards the river. Wait for me.",
    ]),
    "explore_journal_deserter": ("Journal of a Deserter", "Unsigned", [
        "They will call it cowardice. Perhaps it is. I watched my sergeant order a farm burned because the farmer's "
        "son had once carried a rebel's message, and I put down my shield and walked into the trees.\n\nNo one "
        "followed. I do not know whether that makes it better or worse.",
        "I have a little coin, a good knife, and no name I can use. There is said to be work on the coast for men "
        "who ask no questions. I will try to be a man who asks a great many, for once.",
    ]),
    "explore_journal_pilgrim": ("A Pilgrim's Daybook", "Sister Aelfrun", [
        "Seventh shrine of my road. I left a candle and a copper for every traveller who has passed this way and "
        "not come home.\n\nMy cough is worse. The priests said the walking would cure it. The priests say many "
        "things.",
        "I can no longer climb to the next shrine. I will rest here a while and then go down to the village and "
        "find someone to carry my candles the rest of the way. Whoever finds this: the road matters more than the "
        "end of it. Light one for me.",
    ]),
    "explore_journal_cartographer": ("Surveyor's Field Notes", "Odric Vane, cartographer", [
        "Ridge line runs north-east, two bends, then a saddle. Stream at the bottom not on any chart I own. Marked "
        "it.\n\nThere is a dark opening in the hillside across the valley. The locals call it nothing at all, which "
        "in my experience means they are afraid of it.",
        "Went across to look. Old stone around the mouth, cut, not natural. Something has been dragging bones "
        "inside. I am a cartographer, not a hero, so I have drawn it from a respectful distance and moved on.",
    ]),
    "explore_journal_prospector": ("Prospector's Tally", "Gunnvald Stone-Hand", [
        "Day 12. Quartz and a trace of silver in the creek bed. Followed it up the slope. Promising.\n\nDay 14. Found "
        "the vein. Thick as my arm where it shows, then it dives into the rock. Need a proper pick and someone to "
        "hold the lamp.",
        "Day 15. Went back to town for supplies and told no one. Came back and the vein is still there, but so are "
        "footprints that aren't mine. Big ones. I am sleeping with the axe tonight.",
    ]),
    "explore_journal_runaways": ("Two Hands, One Book", "Mira and Tolvald", [
        "M: We are free. It rained all night and the tent leaks and I have never been happier.\n\nT: Her father will "
        "send men after us. Mine will send a letter, which is worse.",
        "M: Tomorrow we cross the river and no one will know us on the other side.\n\nT: I have left my old name "
        "under a stone by the fire. If anyone asks, we were never here.",
    ]),
    # ---- notes that lead to places
    "explore_lead_bandit_orders": ("Orders", "The Chief", [
        "Lookouts change at dusk. Anyone who sleeps on watch walks back to the old place over the ridge and explains "
        "himself to me.\n\nThe haul goes to the hold-up in the hills, not to town. I will know if a septim goes "
        "missing.",
    ]),
    "explore_lead_scholars_letter": ("Letter to a Colleague", "Magister Ilvane", [
        "My dear Corvus,\n\nYou were right and I was wrong, and you may frame this letter. There IS a ruin not a "
        "day's walk from where I sit, and its carvings match the ones from your rubbing.\n\nI have marked it on the "
        "enclosed sketch. Do not tell the College until we have been inside.",
    ]),
    "explore_lead_hunters_tally": ("Hunter's Tally", "A hunter", [
        "Wolves: IIII II\nDeer: IIII\nSkeevers: too many\n\nSomething big denning near here. Tracks lead to that "
        "place everyone avoids. Not going alone. Going to fetch Brand and his dogs.",
    ]),
    "explore_lead_courier_rumours": ("A Courier's Notebook", "Delivered by many hands", [
        "Rumours heard on the road this week:\n\n- the innkeeper swears there are lights at night by the old "
        "stones;\n- a farmer lost three goats near a place he won't name;\n- a Khajiit sold me a map he said was "
        "real. It has a skeever drawn on it. I am not convinced.",
    ]),
    "explore_lead_smugglers_note": ("Folded Note", "No signature", [
        "Usual place, three nights from now. Bring the skooma and the Argonian with the boat. Come alone and come "
        "quiet. If you see a guard on the road, you never received this.",
    ]),
    "explore_lead_last_words": ("Bloodied Scrap", "Unknown", [
        "Can't go on. Leg is bad.\n\nIf you find this, the place I came from is close, very close. There is gold "
        "inside, but there is something else, too. Don't go in at night. Don't go in alone.",
    ]),
    # ---- notes that belong to sites
    "explore_note_hermit_rules": ("Rules of This House", "The one who lives here", [
        "1. Knock.\n2. If no one answers, go away.\n3. If someone answers, also go away.\n4. Do not eat the "
        "mushrooms on the windowsill. They are not for eating.\n5. The goat is not lost. The goat lives here too.",
    ]),
    "explore_note_failed_experiment": ("Notes on a Failed Experiment", "A mage, hand shaking", [
        "Attempt 41. The circle held for nine heartbeats. Something looked back at me through the gap.\n\nAttempt "
        "42. Did not complete the circle. Burned the notes for attempts 1 through 40. Leaving the tower in the "
        "morning. Whoever comes after me: do not finish what I started.",
    ]),
    "explore_note_apprentice_lament": ("An Apprentice's Complaint", "Rolf, apprentice (unpaid)", [
        "Master says apprentices learn by sweeping. I have swept this floor four hundred times. I have learned that "
        "it gets dusty.\n\nMaster also says the tower is perfectly safe. The tower is missing half its roof.",
    ]),
    "explore_note_wayshrine_prayer": ("Prayer Left at a Wayshrine", "A traveller", [
        "Watch over the road and those who walk it. Keep the wolves hungry and the bandits lazy. Bring my brother "
        "home from the war, or if you cannot, bring him somewhere warm.\n\nI have left what coin I can spare.",
    ]),
    "explore_note_velothi_prayer": ("Velothi Prayer Slip", "A pilgrim of the old ways", [
        "Ancestors who walked before me, keep the ash from my eyes and the blight from my blood. I leave this "
        "offering at your stone as my mother did, and her mother.\n\nRemember my name. I will remember yours.",
    ]),
    "explore_note_obelisk_rubbing": ("Rubbing of an Obelisk", "A Redguard scholar", [
        "Charcoal rubbing of the lower band. The script is old Yoku, worn smooth by sand.\n\nAs best I can read it: "
        "'Here the sword-singers stopped, and counted their dead, and went on.' Under it, a list of names too worn "
        "to read. The sand keeps the rest.",
    ]),
    "explore_note_monolith_riddle": ("Words Scratched on Bark", "Someone who was here before you", [
        "The stone is warm at midnight and cold at noon. I have slept beside it three nights. Each night I dream "
        "of a coast I have never seen.\n\nThe marks at its foot are not writing. They are a map. I am going to "
        "find where it leads.",
    ]),
    "explore_note_hist_song": ("A Song for the Hist", "Written down by a traveller", [
        "The Saxhleel sang this at the root while I watched, and let me write it down after:\n\n~The roots drink, the "
        "sap remembers,\n~the egg sleeps, the tree dreams,\n~all of us were water once,\n~all of us will be water again.",
    ]),
    "explore_note_menhir_verse": ("Verse on a Scrap of Vellum", "A Breton shepherd", [
        "~Stone that stood when kings were young,\n~stone that heard the old tongue sung,\n~keep my sheep and keep "
        "my fold,\n~and keep my secret, as of old.\n\n(The secret is under the flat stone. Do not take it all.)",
    ]),
    "explore_note_ayleid_fragment": ("Fragment of Ayleid Script", "Copied by an Imperial antiquarian", [
        "Copied from the rim of the well. The Ayleids carved the same few words on every well I have found, which "
        "I take to mean something like 'light given, light returned'.\n\nThe stone still works, after all these "
        "ages. Touch it and you feel lighter, as if you had slept a whole night.",
    ]),
    "explore_note_akaviri_scroll": ("Lacquered Scroll", "A Tsaesci priest", [
        "To whoever lights the lamp after me: the shrine asks little. Oil for the lamp, a coin for the bowl, and "
        "no blood on the steps.\n\nThe last one who forgot the third rule is still in the garden, if you look.",
    ]),
    "explore_note_sea_hymn": ("Hymn to the Deep", "A Maormer sailor", [
        "~Serpent under the grey water,\n~coil around our hull and keep it,\n~drown our enemies, not our sons,\n~and "
        "bring the tide home gently.\n\nLeft at the shrine before the long voyage, with a pearl.",
    ]),
    "explore_note_last_orders": ("Last Orders", "A captain, long dead", [
        "Hold the line at the stream. Archers on the rise. If they break through, fall back to the stones and make "
        "them pay for every step.\n\nNo retreat past the stones. There is nowhere to retreat to.",
    ]),
    "explore_note_cache_ledger": ("Cache Ledger", "Initials only", [
        "Buried here: what was owed, what was won, and what was not mine but should have been.\n\nTo come back for "
        "it in the spring. If I do not come back, it is yours. Spend it on something foolish.",
    ]),
    "explore_note_fishers_almanac": ("Fisher's Almanac", "A patient person", [
        "Morning: slow. Noon: nothing. Dusk: three fat ones and a boot.\n\nThe big one lives under the dock. I have "
        "seen it twice. One day I will catch it and then I will have nothing left to do with my life, so perhaps "
        "it is better not to.",
    ]),
    "explore_note_prospector_claim": ("Mining Claim", "Witnessed by no one", [
        "Let it be known that this vein, from the split boulder to the crooked pine, is claimed by the undersigned, "
        "who found it first, who will fight anyone who says otherwise, and who has gone to fetch a bigger pick.",
    ]),
    "explore_note_herb_notes": ("Herbalist's Notes", "A careful hand", [
        "Bed 1: blue flowers. Fortify the body.\nBed 2: the black ones. Do not touch without gloves.\nBed 3: "
        "something the goat ate. Goat fine. Goat now slightly glowing.\n\nRemember to water bed 2 less.",
    ]),
    "explore_note_bathers_complaint": ("Complaint, Unsent", "A bather", [
        "To the Jarl's steward. Someone has been bathing in MY spring. I found boot prints and a sock. I want it "
        "known that I was here first, that the spring is warm only on the north side, and that the sock is not "
        "mine.",
    ]),
    "explore_note_honey_harvest": ("Honey Harvest Tally", "A Bosmer gatherer", [
        "Combs taken: twelve. Stings taken: twenty-two.\n\nThe Green Pact allows honey, since the bees make more. It "
        "does not mention how angry the bees are about it. Smoke the hive first, cousin. I did not.",
    ]),
    "explore_note_sugar_ledger": ("Ledger of the Moon-Sugar Field", "Ri'zarra, grower", [
        "Rows planted: nine. Rows watered: nine. Rows eaten by a guar: one.\n\nThis one will take the harvest to "
        "the caravan at the full of both moons, as is proper. What the caravan does with it is not this one's "
        "concern.",
    ]),
    "explore_note_gemcutter": ("Gem-Cutter's Notes", "An Altmer jeweller", [
        "The crystals here sing faintly at dusk. The pitch rises as the stone cools. A flawed crystal sings flat.\n\n"
        "I have kept the three best stones and buried the rest. A jeweller who sells every stone she finds is a "
        "jeweller who has nothing left to sell next year.",
    ]),
    "explore_note_dwemer_survey": ("Survey of a Vent", "A College scholar", [
        "The steam is still warm after uncounted centuries. The pipes go down further than my rope does. I "
        "listened at the grate for an hour and heard, very faintly, something ticking.\n\nI did not open the "
        "hatch. I am writing that down so I will remember I was sensible.",
    ]),
    "explore_note_cairn_epitaph": ("Epitaph", "Carved, then copied", [
        "Here lies Sigrun Frost-Hair, who walked further than any of us and came home to die.\n\nDo not take her "
        "sword. Do not take her shield. Take the gold if you need it; she never cared for gold.",
    ]),
    "explore_note_giant_hunters": ("Hunters' Boast", "Three hunters, two signatures", [
        "We brought down the giant at the stream. It took all three of us and most of Arngrim's teeth.\n\nThe toe "
        "fetches a fine price from the alchemists in town. The rest we left for the crows. Arngrim says we should "
        "have left the toe too. Arngrim is not in charge.",
    ]),
    "explore_note_ships_log": ("Water-Stained Log", "The mate of a lost ship", [
        "Day 9 out of port. Storm from the north. Captain says it will pass.\n\nDay 10. It did not pass. Lost the "
        "mast and two men. Rocks ahead and no way to turn. If anyone finds this on the shore, the cargo was wine "
        "and the debts were the captain's, not mine.",
    ]),
}

# ---------------------------------------------------------------------------------------------------------------
# Unique items: id -> dict(name, lore, item, enchant, kind, region, note=(title, author, pages) or None)

UNIQUES = {
    # Skyrim
    "frostfang": dict(name="Frostfang", item="skycraft:nordic_war_axe", enchant={"minecraft:sharpness": 2, "minecraft:unbreaking": 2},
                      kind="dead_adventurer", region="skyrim/the_pale",
                      lore="Its edge never warms, even beside a hearth.",
                      note=("Frostfang's Last Owner", "Scratched on a strip of hide", [
                          "Took the axe from a frost troll's den in the hills. Its owner before me lay there too. The axe "
                          "does not care who holds it.\n\nI think the cold followed it out of the den. My hands have not "
                          "been warm in a week."])),
    "hearthwarden": dict(name="Hearthwarden", item="skycraft:banded_iron_shield", enchant={"minecraft:protection": 3},
                         kind="hermit_hut", region="skyrim/whiterun",
                         lore="Carried by a housecarl who outlived three jarls and retired to the tundra."),
    "wanderers_circlet": dict(name="The Wanderer's Circlet", item="skycraft:silver_circlet", enchant={"minecraft:protection": 2, "minecraft:feather_falling": 2},
                              kind="overlook", region="skyrim/the_rift",
                              lore="Worn by a woman who walked every road in the Rift and wrote none of it down.",
                              note=("A Wanderer's Farewell", "Hild of the Many Roads", [
                                  "I climbed up here every autumn to watch the birches turn. This is the last year my "
                                  "knees will allow it.\n\nI leave the circlet for whoever loves this view as much as I "
                                  "did. Walk the roads for me."])),
    "stormcrow": dict(name="Stormcrow", item="skycraft:ancient_nord_bow", enchant={"minecraft:power": 3, "minecraft:punch": 1},
                      kind="nordic_cairn", region="skyrim/haafingar",
                      lore="Its string hums before a storm, and its shafts fly true into the wind."),
    "riverwyrm_fang": dict(name="Riverwyrm Fang", item="skycraft:elven_dagger", enchant={"minecraft:sharpness": 3},
                           kind="dead_adventurer", region="skyrim/hjaalmarch",
                           lore="Pulled from the jaw of something that lived in the marsh. The marsh wants it back."),
    "ashfall": dict(name="Ashfall", item="skycraft:steel_greatsword", enchant={"minecraft:fire_aspect": 1, "minecraft:sharpness": 1},
                    kind="ancient_battlefield", region="skyrim/eastmarch",
                    lore="Forged in a hot spring's steam; it still smells of sulphur.",
                    note=("A Sword Left Behind", "A mercenary", [
                        "We held the ford for two days. Ashfall held it for three; I only carried her.\n\nWhen this is over "
                        "I am going home, and she would only rust on a farm. Let her lie with the others who fought "
                        "here."])),
    "last_watch_helm": dict(name="Helm of the Last Watch", item="skycraft:iron_helmet", enchant={"minecraft:protection": 2, "minecraft:respiration": 1},
                            kind="bandit_lookout", region="skyrim/the_reach",
                            lore="Taken from a fort's last guard by the bandits who now use his tower."),
    "kynes_feather": dict(name="Kyne's Feather", item="skycraft:silver_necklace", enchant={"minecraft:feather_falling": 3},
                          kind="wayshrine", region="skyrim/falkreath",
                          lore="A hawk's feather sealed in silver. The wind seems to catch you when you fall."),
    "scribes_mittens": dict(name="Mittens of the Frozen Scribe", item="skycraft:fur_leggings", enchant={"minecraft:protection": 2, "minecraft:unbreaking": 2},
                            kind="mage_ruin", region="skyrim/winterhold",
                            lore="Knitted for a scholar whose ink froze every winter. The ink still froze.",
                            note=("Inventory of the Tower", "Brother Halvard, scribe", [
                                "One desk (cracked). Two candles (one lit). Thirty-one books (damp). One pair of mittens "
                                "(mine, enchanted, the only warm thing in this tower).\n\nThe Archmage says the tower is "
                                "'bracing'. The Archmage has never had to write in it."])),
    # Solstheim
    "ashguard_cuirass": dict(name="Ashguard Cuirass", item="skycraft:bonemold_chestplate", enchant={"minecraft:fire_protection": 4},
                             kind="velothi_shrine", region="morrowind/solstheim",
                             lore="Bonemold hardened in the ashfall of the Red Year."),
    # Cyrodiil
    "lindai_gleam": dict(name="Gleam of Lindai", item="skycraft:elven_sword", enchant={"minecraft:smite": 3, "minecraft:sharpness": 1},
                         kind="ayleid_well", region="cyrodiil/heartlands",
                         lore="Ayleid steel that glows faintly near the restless dead.",
                         note=("On the Gleam of Lindai", "Imperial antiquarian", [
                             "Found in the basin of a well, not in a ruin; the Ayleids hid their best work in plain "
                             "sight.\n\nThe blade glows near the dead. Near a well it glows brighter, as if remembering "
                             "something."])),
    "colovian_greaves": dict(name="Boots of the Colovian Rangers", item="skycraft:imperial_light_boots", enchant={"minecraft:feather_falling": 2, "minecraft:protection": 1},
                             kind="hunters_camp", region="cyrodiil/colovian_highlands",
                             lore="Worn soft by a thousand leagues of highland trails."),
    "vintners_signet": dict(name="Vintner's Signet", item="skycraft:gold_ring", enchant={"minecraft:protection": 2},
                            kind="hermit_hut", region="cyrodiil/west_weald",
                            lore="The seal of a vineyard that burned in the Crisis. Its owner kept the ring, and nothing else."),
    "blackwood_cleaver": dict(name="Blackwood Cleaver", item="skycraft:imperial_war_axe", enchant={"minecraft:bane_of_arthropods": 4},
                              kind="dead_adventurer", region="cyrodiil/blackwood",
                              lore="A legion axe notched by mudcrab shells and worse."),
    # Morrowind
    "ashland_stinger": dict(name="Stinger of the Ashlands", item="skycraft:chitin_dagger", enchant={"minecraft:sharpness": 2, "minecraft:fire_aspect": 1},
                            kind="velothi_shrine", region="morrowind/vvardenfell_ashlands",
                            lore="Cut from a cliff racer's tail and sharpened on volcanic glass."),
    "spore_warden_helm": dict(name="Spore-Warden Helm", item="skycraft:chitin_helmet", enchant={"minecraft:respiration": 3, "minecraft:protection": 1},
                              kind="rare_reagent", region="morrowind/telvannis",
                              lore="Worn by the gardeners of a Telvanni tower to breathe among the spores."),
    "redoran_watch_shield": dict(name="Shield of the Redoran Watch", item="skycraft:bonemold_shield", enchant={"minecraft:protection": 3},
                                 kind="ancient_battlefield", region="morrowind/west_gash",
                                 lore="The house colours are flaked away; the dents are all in front."),
    # High Rock
    "thorn_of_glenumbra": dict(name="Thorn of Glenumbra", item="skycraft:breton_sword", enchant={"minecraft:sharpness": 2, "minecraft:knockback": 1},
                               kind="breton_menhir", region="high_rock/glenumbra",
                               lore="A knight's sword, buried at a standing stone after a vow was broken.",
                               note=("The Broken Vow", "Sir Aubrey of the Moors", [
                                   "I swore at this stone to protect the village. I was away hunting when the raiders "
                                   "came.\n\nI leave my sword here. A man who breaks his word should not carry a "
                                   "knight's blade. Let someone better find it."])),
    "wrothgar_icebreaker": dict(name="Wrothgar Ice-Breaker", item="skycraft:orcish_warhammer", enchant={"minecraft:sharpness": 2, "minecraft:knockback": 2},
                                kind="mineral_vein", region="high_rock/wrothgar",
                                lore="An Orsimer mining hammer that has broken more than rock."),
    "rivenspire_mourning_ring": dict(name="Rivenspire Mourning Ring", item="skycraft:silver_garnet_ring", enchant={"minecraft:protection": 2},
                                     kind="dead_adventurer", region="high_rock/rivenspire",
                                     lore="A widow's ring, engraved with a name that someone tried to scratch out."),
    # Hammerfell
    "sun_sworn_blade": dict(name="Sun-Sworn Blade", item="skycraft:redguard_sword", enchant={"minecraft:sharpness": 3, "minecraft:fire_aspect": 1},
                            kind="yokudan_obelisk", region="hammerfell/alikr_desert",
                            lore="A sword-singer's curved blade, left as an offering where the ancestors stopped."),
    "dune_strider_boots": dict(name="Dune Strider Boots", item="skycraft:hide_boots", enchant={"minecraft:feather_falling": 2, "minecraft:protection": 2},
                               kind="abandoned_camp", region="hammerfell/bangkorai",
                               lore="Stitched for the sands; they never fill with grit."),
    "starstone_amulet": dict(name="Starstone Amulet", item="skycraft:gold_ruby_necklace", enchant={"minecraft:fire_protection": 3},
                             kind="yokudan_obelisk", region="hammerfell/craglorn",
                             lore="A ruby cut in the old Yokudan way, said to have fallen from the sky."),
    # Black Marsh
    "hist_bark_cuirass": dict(name="Hist-Bark Cuirass", item="skycraft:argonian_chestplate", enchant={"minecraft:protection": 3, "minecraft:thorns": 1},
                              kind="hist_sapling", region="black_marsh/shadowfen",
                              lore="Shaped from bark the Hist shed willingly. It is still faintly warm."),
    "swampfang_mace": dict(name="Swampfang Mace", item="skycraft:argonian_mace", enchant={"minecraft:bane_of_arthropods": 3, "minecraft:sharpness": 1},
                           kind="dead_adventurer", region="black_marsh/murkmire",
                           lore="Studded with the teeth of something that is, fortunately, extinct."),
    # Elsweyr
    "twin_moons_claw": dict(name="Claw of the Twin Moons", item="skycraft:khajiit_dagger", enchant={"minecraft:sharpness": 3, "minecraft:looting": 2},
                            kind="moon_sugar_field", region="elsweyr/anequina",
                            lore="Its blade shows two pale reflections, even in daylight.",
                            note=("A Khajiit's Gift", "Dro'mash, caravan guard", [
                                "This one leaves his claw with the sugar, for luck, as his father did. Whoever takes it "
                                "takes the luck too.\n\nThis one hopes you need it more than he did."])),
    "sand_dancer_boots": dict(name="Sand-Dancer's Boots", item="skycraft:khajiit_boots", enchant={"minecraft:feather_falling": 4},
                              kind="overlook", region="elsweyr/pellitine",
                              lore="Light enough to leap from a cliff and land like a cat. Almost."),
    # Valenwood
    "green_pact_bow": dict(name="Bow of the Green Pact", item="skycraft:bosmer_bow", enchant={"minecraft:power": 4},
                           kind="giant_beehive", region="valenwood/grahtwood",
                           lore="Bone and sinew, no wood: as the Pact demands."),
    "thornheart_cuirass": dict(name="Thornheart Cuirass", item="skycraft:bosmer_chestplate", enchant={"minecraft:thorns": 3, "minecraft:protection": 1},
                               kind="hermit_hut", region="valenwood/reapers_march",
                               lore="Grown, not made; it still sprouts a thorn now and then."),
    # Summerset
    "crystal_singers_circlet": dict(name="Crystal-Singer's Circlet", item="skycraft:gold_circlet", enchant={"minecraft:protection": 3, "minecraft:respiration": 1},
                                    kind="crystal_outcrop", region="summerset/auridon",
                                    lore="It rings, very faintly, when the wearer hums."),
    # beyond Tamriel
    "atmoran_rime_axe": dict(name="Atmoran Rime-Axe", item="skycraft:atmoran_war_axe", enchant={"minecraft:sharpness": 3, "minecraft:unbreaking": 3},
                             kind="nordic_cairn", region="atmora", x=-400, z=-11200,
                             lore="From the old country, before the long ships sailed south."),
    "tsaesci_fang": dict(name="Tsaesci Fang", item="skycraft:akaviri_sword", enchant={"minecraft:sharpness": 4},
                         kind="akaviri_shrine", region="akavir", x=17400, z=2600,
                         lore="Folded a thousand times by smiths with no hands, they say."),
    "tide_ring": dict(name="Maormer Tide-Ring", item="skycraft:silver_ring", enchant={"minecraft:respiration": 3, "minecraft:depth_strider": 3},
                      kind="sea_shrine", region="pyandonea", x=-19300, z=20600,
                      lore="Cold and damp to the touch, always, as if just pulled from the sea."),
}
