"""Generates the Skyrim dungeon identity layer from the places and the place lore (offline, stdlib only).

  py -3 -I tools/qa/dungeon_identity_gen.py [--check]

Reads   mod/src/main/resources/skycraft/tamriel/places/skyrim_landmarks.json   (dungeon_fixed and fort places)
        mod/src/main/resources/skycraft/lore/places/*.json                      (hook, notes, authors)
        mod/src/main/lang/skycraft/*.json                                       (place display names)
Writes  mod/src/main/resources/skycraft/dungeons/identity/skyrim.json          (per place: family, boss, sign, notes, motif, loot)
        mod/src/main/resources/skycraft/dungeons/blueprints/motif_*.json       (set-piece furnishings by motif)
        mod/src/main/resources/skycraft/dungeons/blueprints/index.json         (motif entries appended, existing kept)
        mod/src/main/resources/data/skycraft/loot_tables/chests/story_*.json   (themed loot of each family)

Everything is original text and data. The identity of a place comes from its lore: the first reader note's author is
"the person" on the entrance sign and lectern; a chief's own notes name the boss of a bandit, forsworn, vampire,
necromancer, hagraven or mage lair; every other boss gets a deterministic original name from per-family pools.
`--check` only reports (and exits 1 when the files on disk differ from what would be written).
"""
import glob
import json
import os
import re
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "skycraft")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft")
BP = os.path.join(SKY, "dungeons", "blueprints")
LOOT = os.path.join(RES, "data", "skycraft", "loot_tables", "chests")
IDENT = os.path.join(SKY, "dungeons", "identity", "skyrim.json")

# ---------------------------------------------------------------------------------------------------- families
# variant -> family (the story kind). Families decide boss names, set-piece motifs and the loot table.
FAMILY = {
    "barrow": "barrow", "bleak_falls_barrow": "barrow", "forelhost": "barrow", "ysgramors_tomb": "barrow",
    "ustengrav": "barrow", "saarthal": "barrow", "labyrinthian": "barrow", "atmoran_tomb": "barrow",
    "dragon_mound": "barrow", "word_wall": "barrow",
    "nordic_ruin": "ruin", "breton_crypt": "ruin", "yokudan_tomb": "ruin", "orc_ruin": "ruin",
    "daedric_ruin": "ruin", "ayleid_ruin": "ruin", "crystal_vault": "ruin", "imperial_crypt": "ruin",
    "velothi_tomb": "ruin", "xanmeer": "ruin", "moon_temple": "ruin", "tree_tomb": "ruin",
    "sea_vault": "ruin", "akaviri_ruin": "ruin", "swamp_ruin": "ruin", "kothringi_barrow": "ruin",
    "sunken_shrine": "ruin", "adeptorium": "ruin", "sandstone_tomb": "ruin", "khajiit_shrine": "ruin",
    "bosmer_ossuary": "ruin", "canopy_shrine": "ruin", "treethane_seat": "ruin", "elven_ruin": "ruin",
    "wayshrine": "ruin", "ash_shrine": "ruin", "ka_po_tun_shrine": "ruin", "orichalc_ruin": "ruin",
    "dwemer_ruin": "dwemer", "great_lift": "dwemer", "mzark": "dwemer",
    "bandit_cave": "bandit", "bandit_camp": "bandit", "ashlander_camp": "bandit", "naga_camp": "bandit",
    "baandari_camp": "bandit", "wood_orc_camp": "bandit", "hunter_camp": "bandit",
    "fort": "fort", "keep": "fort", "akaviri_fort": "fort", "breton_keep_ruin": "fort", "orc_stronghold": "fort",
    "dunmer_stronghold": "fort", "imperial_prison_fort": "fort", "canyon_redoubt": "fort", "tsaesci_fortress": "fort",
    "fort_mage": "mage", "fort_vampire": "vampire", "fort_forsworn": "forsworn", "fort_necromancer": "necromancer",
    "mushroom_tower": "mage", "altmer_sky_spire": "mage", "arcane_sanctum": "mage",
    "vampire_lair": "vampire", "necromancer_lair": "necromancer", "sixth_house_base": "necromancer",
    "cultist_lair": "necromancer", "sload_grotto": "necromancer", "sload_coral_spire": "necromancer",
    "hagraven_lair": "hagraven",
    "falmer_cave": "falmer", "den": "beast", "skeever_cave": "beast", "spider_cave": "beast",
    "troll_cave": "beast", "scorpion_den": "beast", "hound_den": "beast",
    "ice_cave": "ice", "kamal_ice_vault": "ice",
    "mine": "mine", "kolskeggr_mine": "mine", "egg_mine": "mine", "kwama_egg_mine": "mine",
    "ebony_mine": "mine", "glass_mine": "mine", "crystal_quarry": "mine",
    "forsworn_camp": "forsworn", "forsworn_cave": "forsworn", "forsworn_tower": "forsworn",
    "spriggan_cave": "grove", "grove": "grove", "hist_grove": "grove", "desert_oasis": "grove",
    "graht_grove": "grove", "root_hollow": "grove",
    "smuggler_cave": "smuggler", "grotto": "smuggler", "smuggler_cove": "smuggler", "sugar_smuggler_den": "smuggler",
    "coral_cove": "smuggler", "sea_elf_anchorage": "smuggler", "coastal_grotto": "smuggler",
    "maormer_anchorage": "smuggler", "sea_serpent_cove": "smuggler", "sea_cave": "smuggler", "island": "smuggler",
    "atoll": "smuggler",
    "cave": "cave", "coral_pit": "cave", "mud_cavern": "cave", "marsh_cavern": "cave",
    "badlands_cave": "cave", "desert_cave": "cave",
    "giant_camp": "giant", "dragon_lair": "dragon", "dragon_roost": "dragon",
    "watchtower": "tower", "nordic_tower": "tower", "tower": "tower", "velothi_tower": "tower",
    "tang_mo_pagoda": "tower", "lighthouse": "tower", "mage_tower": "mage",
    "imperial_camp": "military", "stormcloak_camp": "military",
    "cabin": "cabin", "shipwreck": "wreck", "wreck": "wreck", "reef": "wreck", "abyssal_reef": "wreck",
}

KW = {
    "forsworn": r"forsworn|hagraven|briarheart",
    "vampire": r"vampire|thrall|volkihar",
    "necromancer": r"necromanc|reanimat|ritualist",
    "bandit": r"bandit|outlaw|thieves|brigand|smuggler",
    "falmer": r"falmer|chaurus",
    "dwemer": r"dwemer|dwarven|centurion|animuncul",
    "draugr": r"draugr|barrow|sarcoph|entomb|crypt|tomb",
    "giant": r"\bgiants?\b|mammoth",
    "spriggan": r"spriggan",
}
FAMKW = {
    "barrow": "draugr", "bandit": "bandit", "vampire": "vampire", "necromancer": "necromancer",
    "hagraven": "forsworn", "forsworn": "forsworn", "falmer": "falmer", "grove": "spriggan", "giant": "giant",
}
KW_TO_FAM = {
    "forsworn": "forsworn", "vampire": "vampire", "necromancer": "necromancer", "bandit": "bandit",
    "falmer": "falmer", "dwemer": "dwemer", "draugr": "barrow", "giant": "giant", "spriggan": "grove",
}

# family -> (loot key, motifs). The motif is picked by a name token first, then by the place id's hash.
FAMILIES = {
    "barrow": ("barrow", ["urn_wall", "king_throne", "ossuary", "weapon_rack", "offering_bowl"]),
    "ruin": ("ruin", ["rune_pillar", "watch_brazier", "offering_bowl"]),
    "dwemer": ("dwemer", ["tonal_console", "centurion_bay", "lens_array"]),
    "bandit": ("bandit", ["bedroll_row", "trophy_wall", "loot_heap", "prisoner_cage"]),
    "fort": ("bandit", ["armory_rack", "war_table", "trophy_wall"]),
    "military": ("bandit", ["armory_rack", "war_table", "bedroll_row"]),
    "mage": ("necromancer", ["bone_table", "reagent_shelf", "lens_array"]),
    "vampire": ("vampire", ["blood_altar", "coffin_row", "thrall_cells"]),
    "necromancer": ("necromancer", ["bone_table", "reagent_shelf", "raising_slab"]),
    "hagraven": ("hagraven", ["feather_totem", "cauldron_fire"]),
    "forsworn": ("forsworn", ["antler_totem", "bone_fire", "feather_totem"]),
    "falmer": ("falmer", ["chaurus_nest", "fungus_garden"]),
    "beast": ("den", ["bone_heap", "hide_rack"]),
    "ice": ("ice", ["frozen_dead", "ice_shrine"]),
    "mine": ("mine", ["ore_cart", "foreman_desk", "collapsed_timber"]),
    "grove": ("grove", ["root_throne", "moss_shrine"]),
    "smuggler": ("smuggler", ["crate_stack", "bedroll_row"]),
    "cave": ("cave", ["hermit_camp", "bone_heap"]),
    "giant": ("giant", ["mammoth_bones", "bone_heap"]),
    "dragon": ("dragon", ["dragon_bones", "offering_bowl"]),
    "tower": ("tower", ["signal_beacon", "scholar_desk"]),
    "cabin": ("cabin", ["hearth_corner", "hunter_cache"]),
    "wreck": ("wreck", ["driftwood_hoard", "crate_stack"]),
}
# name token -> motif (checked in order, only motifs the family offers apply)
TOKENS = [
    ("throne", "king_throne"), ("hall", "king_throne"), ("sepulcher", "ossuary"), ("crypt", "ossuary"), ("tomb", "ossuary"),
    ("cairn", "urn_wall"), ("rest", "urn_wall"), ("respite", "urn_wall"), ("bastion", "weapon_rack"), ("fast", "weapon_rack"),
    ("keep", "war_table"), ("redoubt", "feather_totem"), ("watch", "signal_beacon"), ("beacon", "signal_beacon"),
    ("helm", "trophy_wall"), ("knife", "trophy_wall"), ("retreat", "loot_heap"), ("den", "loot_heap"), ("hideout", "bedroll_row"),
    ("sanctum", "raising_slab"), ("nest", "chaurus_nest"), ("brood", "chaurus_nest"), ("pit", "chaurus_nest"),
    ("gloom", "fungus_garden"), ("glow", "fungus_garden"), ("burrow", "bone_heap"), ("grotto", "crate_stack"),
    ("quarry", "ore_cart"), ("excavation", "ore_cart"), ("prospect", "foreman_desk"),
]

# boss-name pools (original). Humans: "<title> <first> <byname>" style; creatures: single compound names.
POOLS = {
    "barrow": {
        "first": ["Hrolfgar", "Vestri", "Ulfmund", "Thorgeir", "Grimvald", "Aldrun", "Skeggi", "Haldor", "Orlaug", "Brynjolf",
                  "Eirmund", "Svartkel", "Gunnhild", "Ragnhei", "Yrsa", "Valgrim", "Hakon", "Sigtrygg", "Ingrun", "Bergthor",
                  "Alfhild", "Torvald", "Runolf", "Kettil", "Asmund", "Hjalmar", "Solveig", "Ottrik", "Vigfus", "Arnkel"],
        "by": ["the Unburied", "Rime-Crowned", "Grave-Warden", "the Oathbreaker", "the Long Dead", "Cold-Hand", "the Hollow Jarl",
               "Frost-Beard", "the Sleepless", "Stone-Sworn", "Black-Mantle", "Wolf-Slayer", "of the Seven Urns", "the Barrow-Lord",
               "Ash-Shield", "the Bitter", "Iron-Brow", "the Last Thane", "Ember-Eyed", "the Pale Marshal", "Bone-Reaver",
               "the Silent King", "Night-Sworn", "Hall-Keeper"],
        "title": ["", "", "Jarl ", "Thane ", "Warchief "],
    },
    "ruin": {
        "first": ["Valdrek", "Orsolf", "Aethel", "Haldris", "Morthgar", "Ysolt", "Brandrek", "Tormund", "Edrith", "Gallowyn",
                  "Caelan", "Morgant", "Bertrand", "Evrard", "Gaston", "Roderic", "Aelric", "Corwyn", "Varnak", "Dura",
                  "Rashid", "Tarik", "Sulaiman", "Karim", "Zahir", "Harun", "Malik", "Farid", "Azad", "Qasim", "Nadia", "Soraya"],
        "by": ["the Watcher", "Ward-Sworn", "of the Broken Wall", "the Shrine-Warden", "Rune-Keeper", "the Fallen Priest",
               "of the Sealed Vault", "the Sword-Singer", "of the First Ansei", "the Tomb-Warden", "the Sunken Lord",
               "of the Lion Guard", "the Oath-Bound", "of the Direnni Sigil", "the Iron Castellan", "of the Sand-Shrine"],
        "title": ["", "Warden ", "Ansei ", "Castellan ", "Prelate ", "Keeper "],
    },
    "bandit": {
        "first": ["Garrick", "Maren", "Torvik", "Sella", "Bram", "Hesk", "Orla", "Dunstan", "Vexa", "Kolgrim", "Ysmar", "Tilda",
                  "Rurik", "Nessa", "Gorm", "Elsbet", "Drake", "Halvar", "Isra", "Ketil", "Wulfric", "Brenna", "Jarek", "Mavis",
                  "Reynard", "Thibault", "Giselle", "Corinne", "Bastien", "Luc", "Kemal", "Jafar", "Razia", "Tariq", "Farah", "Zayd"],
        "by": ["Two-Knives", "Red-Sash", "the Tithe-Taker", "Coin-Hand", "Split-Lip", "the Gaoler", "Black-Boots", "Toll-Master",
               "Cutpurse", "One-Ear", "Salt-Eye", "the Lamplighter", "Crow-Bait", "Broken-Nose", "the Quartermaster", "Hook-Thumb",
               "the Dune-Raider", "Sand-Cloak", "of the Red Sails", "the Highwayman", "the Cutthroat", "the Freebooter"],
        "title": ["", "", "Captain ", "Boss ", "Corsair ", "Reaver "],
    },
    "vampire": {
        "first": ["Ysmaine", "Valdrec", "Seraphene", "Corvane", "Morgathe", "Isolwen", "Dravik", "Maelis", "Narcissa", "Lucius",
                  "Verenna", "Aurelio", "Selwyn", "Ravenna", "Genevieve", "Montclair", "Vivienne", "Cyprien", "Sabbah", "Khalid"],
        "by": ["of the Pale Table", "the Patient", "Night-Born", "the Courteous", "of the Red Cup", "the Long-Lived",
               "of the Crimson Chalice", "the Thirsting", "of the Crypt", "the Blood-Lord"],
        "title": ["Lady ", "Lord ", "Count ", "Mistress ", "Master ", "Baron "],
    },
    "necromancer": {
        "first": ["Orsin", "Velka", "Thaddeus", "Iselde", "Morwen", "Caldus", "Drusilla", "Hadrik", "Nerissa", "Umbrel", "Skarn",
                  "Lucan", "Malcor", "Severin", "Delphine", "Mordecai", "Nazeer", "Qadir", "Yasmin", "Hakim"],
        "by": ["the Careful", "Grave-Taker", "the Hollow Hand", "Bone-Reader", "of the Ninth Candle", "the Lector",
               "of the Worm Cult", "the Corpse-Speaker", "the Shroud-Weaver", "of the Black Sepulcher"],
        "title": ["Magister ", "Adept ", "Ritualist ", "Brother ", "Sister ", "Arch-Lector "],
    },
    "mage": {
        "first": ["Aldwyn", "Perrin", "Caraline", "Xerxes", "Melisande", "Orrin", "Thessaly", "Ambrose",
                  "Aurelien", "Celeste", "Evangeline", "Balthazar", "Marwan", "Soraya", "Idris", "Zuleika"],
        "by": ["the Exiled", "of the Sealed Door", "Star-Reader", "the Apostate", "the Glass-Maker", "of the High Spire",
               "the Spell-Weaver", "of the Astral Seal"],
        "title": ["Magister ", "Warlock ", "Conjurer ", "Savant "],
    },
    "hagraven": {
        "first": ["Skeldra", "Orgwen", "Mugrash", "Hesthra", "Dunmira", "Vorga", "Thistle-Anne", "Grimsel", "Wrenna", "Oldwyth",
                  "Morgra", "Beldama", "Drusba", "Kragna", "Ulvra", "Ygraine", "fenn-Mother", "mire-Sister"],
        "by": ["of the Briar", "Feather-Mother", "the Heart-Taker", "Old-Bones", "the Hungry", "of the Wyrd",
               "of the Fen", "the Crow-Speaker", "of the Salt-Marsh"],
        "title": ["Gristle-Mother ", "Crone ", "Briar-Mother ", "Mother ", "Sister "],
    },
    "forsworn": {
        "first": ["Madra", "Ketha", "Orgul", "Sanna", "Thrull", "Varka", "Edda", "Kyrn", "Bresa", "Haskel",
                  "Cael", "Donnan", "Faelan", "Morcant", "Riordan", "Taran", "Branwen", "Niamh"],
        "by": ["Antler-Crown", "Spear-Mother", "the Briar-Sworn", "Red-Feather", "Bone-Chanter", "Ash-Walker", "Hollow-Drum",
               "of the Crags", "the Thorn-Chanter", "Stone-Claw"],
        "title": ["", "Briarheart ", "Hunt-Mother ", "Chieftain "],
    },
    "falmer": {
        "first": ["Mhuur", "Ysk", "Oozk", "Grath", "Hesh", "Vyl", "Nuum", "Skree", "Thok", "Ilmuk"],
        "by": ["the Blind Elder", "Spore-Warden", "of the Deep Hive", "the Pale Chanter", "Gloom-Keeper"],
        "title": ["Elder ", "Hive-Warden ", "Gloomwarden "],
    },
    "beast": {
        "first": ["Gnarltooth", "Hollowfang", "Bonecrusher", "Mossback", "Wretchhide", "Cragmaw", "Splitclaw", "Dreadpaw",
                  "Hushfur", "Ironhide", "Brindlemaw", "Scarflank", "Grimsnout", "Bleakhowl", "Ashmane", "Thornback",
                  "Sandstinger", "Duneclaw", "Venomtail", "Chitinback", "Sunscorcher", "Saltfang", "Canyonstalker", "Redpincer"],
        "by": ["", "of the Wastes", "of the Deep Den", "of the Hollow", "the Ravenous", "the Burrower"],
        "title": ["Old ", "Mother ", "Great ", "Black ", "Brood-Queen ", "Dread "],
    },
    "dwemer": {
        "first": ["Kt-7", "Vh-12", "Rn-3", "Dz-9", "Mk-21", "Ph-4", "Sg-16", "Ol-8", "Bt-30", "Nx-5", "Tr-14", "Gl-2",
                  "Rk-11", "Bz-6", "Mz-19", " Vol-3", "Kla-8", "Zel-15", "Tha-22", "Nch-1"],
        "by": ["", "of the Brass Hall", "of the Dynamo", "of the Steam-Vault", "of the Deep Forge"],
        "title": ["Warden-Engine ", "Overseer Unit ", "Foundry-Guard ", "Gate-Engine ", "Centurion "],
    },
    "ice": {
        "first": ["Rimeheart", "Glasswing", "Frostmaw", "Shardbrow", "Coldwhisper", "Hoarvein", "Icegrip", "Blueclaw"],
        "by": ["", "of the Glacier", "the Rime-Stalker"],
        "title": ["the ", "Old ", "The Frozen "],
    },
    "mine": {
        "first": ["Pickback", "Deepdelver", "Slagheart", "Cinderjaw", "Rubblefist", "Lodestone", "Gritmaw", "Seamripper",
                  "Ironvein", "Copperjaw", "Shaftwarden", "Orebreaker", "Pitboss", "Dustlung", "Quarrymaster", "Deeppick"],
        "by": ["", "of the Deep Adit", "of the Lower Drift", "the Pit-Boss", "the Vein-Claimer"],
        "title": ["the ", "Old ", "Foreman ", "Overseer "],
    },
    "grove": {
        "first": ["Moss-Crown", "Thornmother", "Oldwood", "Rootwarden", "Fernhollow", "Barkhide", "Wispweaver",
                  "Wyrd-Bough", "Briar-Heart", "Glen-Singer", "Oasis-Keeper", "Leaf-Warden"],
        "by": ["", "of the Sacred Grove", "of the Old Root", "of the Spring"],
        "title": ["the ", "Mother ", "Elder ", "Matron "],
    },
    "smuggler": {
        "first": ["Saltlip", "Tidehand", "Brinejack", "Knotwork", "Crab-Ear", "Lanternjaw", "Driftwood",
                  "Bilge-Rat", "Reef-Runner", "Gaff-Hook", "Cove-Master", "Fog-Sail", "Tar-Hand", "Rum-Cask"],
        "by": ["", "of the Hidden Cove", "of the Night Tide", "the Contrabandist", "of the Sea-Caves"],
        "title": ["Captain ", "Boss ", "Quartermaster ", "First Mate ", "Skipper "],
    },
    "cave": {
        "first": ["Hollowback", "Gloomfang", "Stonebrow", "Nightcrawler", "Duskmaw", "Cavepaw",
                  "Echo-Stalker", "Dripstone", "Limestone-Lurker", "Sinkhole-Maw", "blind-Claw", "Karst-Runner"],
        "by": ["", "of the Sinkhole", "of the Dark Grotto", "of the Under-Stream", "the Lurker", "of the Caverns"],
        "title": ["Old ", "the ", "Great ", "Deep ", "Blind "],
    },
    "giant": {
        "first": ["Gruvuk", "Maghor", "Thundrak", "Bolgrum", "Haggarn", "Skulgar", "Yurrg", "Orgnok",
                  "Brogul", "Durgash", "Ghorbash", "Kharzug", "Murzol", "Sharn"],
        "by": ["Stone-Elbow", "Mammoth-Walker", "Cairn-Kicker", "Big-Drum", "Tree-Splitter", "Ridge-Strider", "Boulder-Hand"],
        "title": ["Old ", "", "Chieftain "],
    },
    "dragon": {
        "first": ["Vulthrax", "Nirkul", "Sahrotor", "Krindoth", "Alduhn", "Faasnuk", "Thodrak",
                  "Nahagliiv", "Viinturuth", "Odahviing", "Krosulhah", "Sahrotaar", "Relonikiv", "Kruziikrel"],
        "by": ["the Skyborn", "Storm-Wing", "the Ashen", "of the High Perch", "the Sun-Scorcher", "of the Crags"],
        "title": ["", "Elder "],
    },
    "military": {
        "first": ["Aurelian", "Brynjolf", "Cassian", "Dagny", "Eirik", "Fabius", "Gunnar", "Halla", "Iovan", "Jorun",
                  "Gervais", "Amaury", "rashid", "Qasim", "brennan", "Lucian"],
        "by": ["the Steadfast", "Iron-Line", "the Quartermaster", "Hard-March", "the Veteran", "of the Border Watch"],
        "title": ["Captain ", "Sergeant ", "Commander ", "Marshal "],
    },
    "tower": {
        "first": ["Edrun", "Galdis", "Haldane", "Ismara", "Joric", "Kelwin", "Lysa", "Merrick",
                  "Tristan", "Alaric", "Damien", "Genevieve", "Tariq", "Nadir", "Samira", "Khlid"],
        "by": ["the Watcher", "Beacon-Keeper", "the Last Lamp", "of the High Stair", "of the Coast Watch",
               "of the Signal Tower", "the Lookout", "of the Rampart"],
        "title": ["Warden ", "Keeper ", "", "Watch-Captain ", "Sentinel "],
    },
    "cabin": {
        "first": ["Otho", "Brenna", "Hargrim", "Pelle", "Roskva", "Ivar", "Dagmar", "Folke", "Sunniva", "Alrek",
                  "Henri", "Margot", "Gaston", "Bilal", "Amina"],
        "by": ["the Hermit", "Tall-Tale", "the Trapper", "the Outcast", "the Lone Hunter"],
        "title": ["", "Old "],
    },
    "wreck": {
        "first": ["Nautilus", "Barnacle", "Saltgrave", "Wavebreaker", "Driftmaw", "Kelpbeard", "Gullcry", "Tidewarden",
                  "Reef-Ghost", "Bilge-Wraith", "Drowned-Captain", "Hull-Breaker", "Anchor-Shade"],
        "by": ["", "of the Sunken Hull", "of the Shoals", "of the Wreck"],
        "title": ["the ", "Old ", "Captain "],
    },
}

# families whose lore chief (the "boss" note's author) is taken as the boss's name when it reads as a person
CHIEF_FROM_LORE = {"bandit", "fort", "military", "vampire", "necromancer", "hagraven", "forsworn", "mage"}
NOT_A_PERSON = re.compile(r"^(office of|told by|chalked|the |a |an |unsigned|unknown|translated|courier|prisoner)|translation|expedition|miners|guard$|company$", re.I)

# ---------------------------------------------------------------------------------------------------- loot tables
LOOT_RECIPES = {
    # key: (theme table, relic/second table, signature items [(item, weight, min, max)])
    "barrow": ("containers/dungeon/theme/nordic", "chests/relic_barrow", [("skycraft:bone_meal_draugr", 4, 1, 3), ("minecraft:gold_ingot", 2, 1, 2)]),
    "ruin": ("containers/dungeon/theme/nordic", "chests/relic_ruin", [("minecraft:amethyst_shard", 3, 1, 3), ("minecraft:gold_nugget", 3, 3, 8)]),
    "dwemer": ("containers/dungeon/theme/dwemer", "chests/relic_dwemer", [("skycraft:dwarven_metal_ingot", 3, 1, 3), ("skycraft:dwarven_oil", 2, 1, 2)]),
    "bandit": ("containers/camp/bandit", "containers/corpse/bandit_boss", [("skycraft:gold_ring", 1, 1, 1), ("skycraft:steel_ingot", 3, 1, 3)]),
    "necromancer": ("containers/dungeon/theme/necromancer", "containers/corpse/mage_boss", [("skycraft:soul_gem_black", 1, 1, 1), ("skycraft:deathbell", 3, 1, 3)]),
    "vampire": ("containers/dungeon/theme/vampire", "containers/corpse/vampire", [("skycraft:vampire_dust", 4, 1, 4), ("minecraft:book", 2, 1, 1)]),
    "hagraven": ("containers/dungeon/theme/forsworn", "chests/relic_lair", [("skycraft:hagraven_feathers", 4, 1, 3), ("skycraft:briar_heart", 1, 1, 1)]),
    "forsworn": ("containers/camp/forsworn", "containers/dungeon/theme/forsworn", [("skycraft:hagraven_feathers", 3, 1, 2), ("skycraft:hide", 4, 1, 3)]),
    "falmer": ("containers/dungeon/theme/falmer", "chests/relic_cave", [("skycraft:chaurus_eggs", 3, 1, 2), ("skycraft:falmer_ear", 3, 1, 2)]),
    "den": ("containers/camp/hunter", "chests/relic_cave", [("skycraft:hide", 5, 1, 3), ("minecraft:bone", 4, 2, 5)]),
    "ice": ("chests/relic_cave", "containers/dungeon/theme/falmer", [("minecraft:blue_ice", 2, 1, 3), ("minecraft:amethyst_shard", 3, 1, 4)]),
    "mine": ("containers/site/mine", "chests/relic_mine", [("skycraft:raw_malachite", 2, 1, 3), ("skycraft:raw_silver", 3, 1, 4)]),
    "grove": ("containers/camp/hunter", "chests/relic_cave", [("skycraft:glowing_mushroom", 4, 1, 3), ("minecraft:moss_block", 3, 1, 4)]),
    "smuggler": ("containers/camp/smuggler", "chests/relic_cave", [("skycraft:moon_sugar", 3, 1, 3), ("skycraft:skooma", 2, 1, 1)]),
    "cave": ("chests/relic_cave", "containers/dungeon/standard", [("minecraft:bone", 3, 1, 3), ("minecraft:iron_ingot", 3, 1, 3)]),
    "giant": ("containers/camp/giant", "containers/dungeon/standard", [("skycraft:giants_toe", 1, 1, 1), ("minecraft:leather", 4, 2, 5)]),
    "dragon": ("containers/site/dragon_lair", "chests/relic_barrow", [("minecraft:bone", 5, 2, 6), ("minecraft:gold_ingot", 2, 1, 3)]),
    "tower": ("containers/dungeon/standard", "chests/relic_ruin", [("minecraft:book", 3, 1, 2), ("minecraft:spyglass", 1, 1, 1)]),
    "cabin": ("containers/household/common", "containers/camp/hunter", [("minecraft:cooked_beef", 3, 1, 3), ("minecraft:leather", 2, 1, 3)]),
    "wreck": ("containers/site/shipwreck", "chests/relic_cave", [("minecraft:gold_nugget", 4, 3, 9), ("minecraft:compass", 1, 1, 1)]),
}

# ---------------------------------------------------------------------------------------------------- blueprints
# Furnishings against a room's far wall, rows from the wall inwards (see Blueprints.java). '.' = leave alone.
CAND = "minecraft:candle[candles=3,lit=true]"
BONE = "minecraft:bone_block[axis=y]"
SKULL = "minecraft:skeleton_skull[rotation=0]"
BP_DEFS = {
    "urn_wall": dict(layers=[["PPPPP", "c.c.c"], ["pcpcp"]], key={"P": "minecraft:decorated_pot", "p": "minecraft:flower_pot", "c": CAND}),
    "king_throne": dict(layers=[["SBBBS", ".sTs.", "..f.."], [".b.b.", "..B..", "....."], [".....", "..K..", "....."]],
                        key={"S": "minecraft:polished_deepslate", "B": "minecraft:chiseled_stone_bricks", "s": "minecraft:stone_brick_stairs[facing=south,half=bottom]@in",
                             "T": "minecraft:polished_andesite", "b": "minecraft:red_banner[rotation=0]", "K": SKULL,
                             "f": "minecraft:soul_campfire[lit=true]"}),
    "ossuary": dict(layers=[["BBBBBB", "BsBsBs"], ["BsBsBs"], ["bbbbbb"]],
                    key={"B": BONE, "s": SKULL, "b": CAND}),
    "weapon_rack": dict(layers=[["IIIII", "g.g.g"], ["I.I.I"], ["c...c"]],
                        key={"I": "minecraft:iron_bars", "g": "minecraft:grindstone[face=floor,facing=north]", "c": "minecraft:chain[axis=y]"}),
    "offering_bowl": dict(layers=[[".C.", "bFb", "..."]],
                          key={"C": "minecraft:chiseled_stone_bricks", "F": "minecraft:soul_campfire[lit=true]", "b": CAND}),
    "rune_pillar": dict(layers=[["P.P", "...", "..."], ["P.P"], ["L.L"]],
                        key={"P": "minecraft:chiseled_stone_bricks", "L": "minecraft:lantern[hanging=false]"}),
    "watch_brazier": dict(layers=[["ccc", ".F."], [".L."]],
                          key={"c": "minecraft:cobblestone_wall", "F": "minecraft:campfire[lit=true]", "L": "minecraft:lantern[hanging=false]"}),
    "tonal_console": dict(layers=[["CCCCC", "CgGgC"], ["C.e.C"], ["..e.."]],
                          key={"C": "minecraft:waxed_cut_copper", "g": "minecraft:amethyst_block", "G": "minecraft:ochre_froglight[axis=y]",
                               "e": "minecraft:end_rod[facing=up]"}),
    "centurion_bay": dict(layers=[["CCCCC", "C...C"], ["C...C"], ["C...C"]],
                          key={"C": "minecraft:waxed_copper_block"}),
    "lens_array": dict(layers=[["g...g", ".lAl."], ["..e.."], ["..e.."]],
                       key={"g": "minecraft:glass", "l": "minecraft:sea_lantern", "A": "minecraft:amethyst_cluster[facing=up]",
                            "e": "minecraft:end_rod[facing=up]"}),
    "bedroll_row": dict(layers=[["hbhbh", "wwwww"]],
                        key={"h": "minecraft:hay_block[axis=y]", "b": "minecraft:barrel[facing=up]", "w": "minecraft:brown_carpet"}),
    "trophy_wall": dict(layers=[["fffff"], ["s.s.s"], ["..B.."]],
                        key={"f": "minecraft:spruce_fence", "s": SKULL, "B": "minecraft:white_banner[rotation=0]"}),
    "loot_heap": dict(layers=[["bbhbb", "b.b.h"], ["h.b.."]],
                      key={"b": "minecraft:barrel[facing=up]", "h": "minecraft:hay_block[axis=y]"}),
    "prisoner_cage": dict(layers=[["IIIII", "I...I", "IIIII"], ["I...I", "I.s.I", "I...I"], ["IIIII", "ICCCI", "IIIII"]],
                          key={"I": "minecraft:iron_bars", "s": SKULL, "C": "minecraft:chain[axis=y]"}),
    "armory_rack": dict(layers=[["PPPPP", "g.a.g"], ["I.I.I"]],
                        key={"P": "minecraft:spruce_planks", "g": "minecraft:grindstone[face=floor,facing=north]", "a": "minecraft:anvil[facing=north]",
                             "I": "minecraft:iron_bars"}),
    "war_table": dict(layers=[["ttttt", "t.m.t"], ["..L.."]],
                      key={"t": "minecraft:spruce_slab[type=top]", "m": "minecraft:cartography_table", "L": "minecraft:lantern[hanging=false]"}),
    "bone_table": dict(layers=[["BBBBB", "b.t.b"], [".l.s."]],
                       key={"B": BONE, "b": "minecraft:brewing_stand", "t": "minecraft:lectern[facing=north]@in",
                            "l": "minecraft:soul_lantern[hanging=false]", "s": SKULL}),
    "reagent_shelf": dict(layers=[["BBBBB"], ["BBBBB"], ["ccccc"]],
                          key={"B": "minecraft:bookshelf", "c": "minecraft:soul_lantern[hanging=false]"}),
    "raising_slab": dict(layers=[[".SSS.", ".SBS.", ".SSS."], [".fff.", "..s..", "....."]],
                         key={"S": "minecraft:polished_blackstone", "B": BONE, "f": "minecraft:soul_campfire[lit=true]", "s": SKULL}),
    "blood_altar": dict(layers=[["..R..", ".RrR.", "..R.."], [".c.c.", "..C..", "....."]],
                        key={"R": "minecraft:crimson_planks", "r": "minecraft:red_nether_bricks", "c": "minecraft:red_candle[candles=3,lit=true]",
                             "C": "minecraft:cauldron"}),
    "coffin_row": dict(layers=[["bbbbb", "b.b.b"], ["....."]],
                       key={"b": "minecraft:black_concrete"}),
    "thrall_cells": dict(layers=[["I.I.I", "I.I.I"], ["I.I.I", "IsIsI"]],
                         key={"I": "minecraft:iron_bars", "s": SKULL}),
    "feather_totem": dict(layers=[["...", ".B."], [".B."], [".B."], [".s."]],
                          key={"B": "minecraft:dark_oak_fence", "s": SKULL}),
    "cauldron_fire": dict(layers=[[".C.", "fWf"], ["..."]],
                          key={"C": "minecraft:cauldron", "f": "minecraft:campfire[lit=true]", "W": "minecraft:mangrove_roots"}),
    "antler_totem": dict(layers=[["...", ".B."], [".B."], ["fBf"], [".s."]],
                         key={"B": BONE, "f": "minecraft:dark_oak_fence", "s": SKULL}),
    "bone_fire": dict(layers=[["BfB", "...", "..."]],
                      key={"B": BONE, "f": "minecraft:campfire[lit=true]"}),
    "chaurus_nest": dict(layers=[["wwwww", "weEew"], ["w.e.w"]],
                         key={"w": "minecraft:cobweb", "e": "minecraft:turtle_egg[eggs=4]", "E": "minecraft:sculk"}),
    "fungus_garden": dict(layers=[["mmmmm", "mRmRm"], [".R.R."]],
                          key={"m": "minecraft:moss_block", "R": "minecraft:red_mushroom"}),
    "bone_heap": dict(layers=[["BsB..", "sBBs."], ["Bs..."]],
                      key={"B": BONE, "s": SKULL}),
    "hide_rack": dict(layers=[["fffff", "w.w.w"], ["w...w"]],
                      key={"f": "minecraft:oak_fence", "w": "minecraft:white_carpet"}),
    "frozen_dead": dict(layers=[["IIIII", "IiBiI"], ["IisiI"]],
                        key={"I": "minecraft:packed_ice", "i": "minecraft:ice", "B": BONE, "s": SKULL}),
    "ice_shrine": dict(layers=[["BBBBB", "B.A.B"], ["..A.."], ["..A.."]],
                       key={"B": "minecraft:blue_ice", "A": "minecraft:amethyst_cluster[facing=up]"}),
    "ore_cart": dict(layers=[["rrrrr", "..M.."], ["..O.."]],
                     key={"r": "minecraft:rail[shape=east_west]", "M": "minecraft:barrel[facing=up]", "O": "minecraft:raw_iron_block"}),
    "foreman_desk": dict(layers=[["tttt.", "tLCt."], ["B...B"]],
                         key={"t": "minecraft:spruce_planks", "L": "minecraft:lantern[hanging=false]", "C": "minecraft:crafting_table",
                              "B": "minecraft:barrel[facing=up]"}),
    "collapsed_timber": dict(layers=[["gGgGg", "G.g.G"], ["g...g"]],
                             key={"g": "minecraft:gravel", "G": "minecraft:cobblestone", "p": "minecraft:oak_log[axis=x]"}),
    "root_throne": dict(layers=[["RRRRR", ".RsR."], ["R...R"], ["R...R"]],
                        key={"R": "minecraft:mangrove_roots", "s": "minecraft:moss_carpet"}),
    "moss_shrine": dict(layers=[["mmmmm", ".mCm."], ["..F.."]],
                        key={"m": "minecraft:moss_block", "C": "minecraft:chiseled_stone_bricks", "F": "minecraft:azalea"}),
    "crate_stack": dict(layers=[["bbbbb", "bb.bb"], ["b...b"], ["..b.."]],
                        key={"b": "minecraft:barrel[facing=up]"}),
    "hermit_camp": dict(layers=[["wcw..", ".F.b."]],
                        key={"w": "minecraft:white_carpet", "c": "minecraft:barrel[facing=up]", "F": "minecraft:campfire[lit=true]", "b": "minecraft:barrel[facing=up]"}),
    "mammoth_bones": dict(layers=[["B.B.B", "BBBBB"], ["B...B"], ["B...B"]],
                          key={"B": BONE}),
    "dragon_bones": dict(layers=[["BBBBBBB", "B.....B"], ["B.....B"], ["B.....B"], ["BBBBBBB"]],
                         key={"B": "minecraft:bone_block[axis=x]"}),
    "signal_beacon": dict(layers=[[".O.", "OFO"], ["..."], ["..L"]],
                          key={"O": "minecraft:cobblestone", "F": "minecraft:campfire[lit=true]", "L": "minecraft:lantern[hanging=false]"}),
    "scholar_desk": dict(layers=[["BBBBB", "t.l.t"], ["..L.."]],
                         key={"B": "minecraft:bookshelf", "t": "minecraft:spruce_slab[type=top]", "l": "minecraft:lectern[facing=north]@in",
                              "L": "minecraft:lantern[hanging=false]"}),
    "hearth_corner": dict(layers=[["SSS..", "SFS.b"], ["S.S.."]],
                          key={"S": "minecraft:cobblestone", "F": "minecraft:campfire[lit=true]", "b": "minecraft:barrel[facing=up]"}),
    "hunter_cache": dict(layers=[["hbh..", "w.w.."]],
                         key={"h": "minecraft:hay_block[axis=y]", "b": "minecraft:barrel[facing=up]", "w": "minecraft:brown_carpet"}),
    "driftwood_hoard": dict(layers=[["lblbl", "b.b.b"]],
                            key={"l": "minecraft:stripped_oak_log[axis=x]", "b": "minecraft:barrel[facing=up]"}),
}


# ---------------------------------------------------------------------------------------------------- helpers
def load_json(p):
    with open(p, encoding="utf8") as f:
        return json.load(f)


def lang_names():
    out = {}
    for f in glob.glob(os.path.join(LANG, "*.json")):
        for k, v in load_json(f).items():
            if k.startswith("place.skycraft."):
                out[k[len("place.skycraft."):]] = v
    return out


def lore_places():
    out = {}
    for f in load_json(os.path.join(SKY, "lore", "places", "index.json")):
        d = load_json(os.path.join(SKY, "lore", "places", f))
        out.update(d.get("places", {}))
    return out


def h(s):
    return zlib.crc32(s.encode("utf8")) & 0xFFFFFFFF


def clean_person(a):
    """A personal name from a note's author line, or None."""
    if not a:
        return None
    a = re.sub(r"\(.*?\)", "", a).strip()
    a = a.split(",")[0].strip()
    if not a or NOT_A_PERSON.search(a) or len(a) > 34:
        return None
    return a


def wrap(text, width=14, lines=2):
    words = text.replace("'", "'").split()
    out, cur = [], ""
    for w in words:
        if not cur:
            cur = w
        elif len(cur) + 1 + len(w) <= width:
            cur += " " + w
        else:
            out.append(cur)
            cur = w
    if cur:
        out.append(cur)
    return [l[:width] for l in out[:lines]]


def short(name, width=14):
    if len(name) <= width:
        return name
    parts = name.split()
    for n in range(len(parts), 0, -1):
        s = " ".join(parts[:n])
        if len(s) <= width:
            return s
    return name[:width]


SKYRIM_POOLS = {
    "barrow": {
        "first": ["Hrolfgar", "Vestri", "Ulfmund", "Thorgeir", "Grimvald", "Aldrun", "Skeggi", "Haldor", "Orlaug", "Brynjolf",
                  "Eirmund", "Svartkel", "Gunnhild", "Ragnhei", "Yrsa", "Valgrim", "Hakon", "Sigtrygg", "Ingrun", "Bergthor",
                  "Alfhild", "Torvald", "Runolf", "Kettil", "Asmund", "Hjalmar", "Solveig", "Ottrik", "Vigfus", "Arnkel"],
        "by": ["the Unburied", "Rime-Crowned", "Grave-Warden", "the Oathbreaker", "the Long Dead", "Cold-Hand", "the Hollow Jarl",
               "Frost-Beard", "the Sleepless", "Stone-Sworn", "Black-Mantle", "Wolf-Slayer", "of the Seven Urns", "the Barrow-Lord",
               "Ash-Shield", "the Bitter", "Iron-Brow", "the Last Thane", "Ember-Eyed", "the Pale Marshal", "Bone-Reaver",
               "the Silent King", "Night-Sworn", "Hall-Keeper"],
        "title": ["", "", "Jarl ", "Thane ", "Warchief "],
    },
    "ruin": {
        "first": ["Valdrek", "Orsolf", "Aethel", "Haldris", "Morthgar", "Ysolt", "Brandrek", "Tormund", "Edrith", "Gallowyn"],
        "by": ["the Watcher", "Ward-Sworn", "of the Broken Wall", "the Shrine-Warden", "Rune-Keeper", "the Fallen Priest"],
        "title": ["", "Warden "],
    },
    "bandit": {
        "first": ["Garrick", "Maren", "Torvik", "Sella", "Bram", "Hesk", "Orla", "Dunstan", "Vexa", "Kolgrim", "Ysmar", "Tilda",
                  "Rurik", "Nessa", "Gorm", "Elsbet", "Drake", "Halvar", "Isra", "Ketil", "Wulfric", "Brenna", "Jarek", "Mavis"],
        "by": ["Two-Knives", "Red-Sash", "the Tithe-Taker", "Coin-Hand", "Split-Lip", "the Gaoler", "Black-Boots", "Toll-Master",
               "Cutpurse", "One-Ear", "Salt-Eye", "the Lamplighter", "Crow-Bait", "Broken-Nose", "the Quartermaster", "Hook-Thumb"],
        "title": ["", "", "Captain ", "Boss "],
    },
    "vampire": {
        "first": ["Ysmaine", "Valdrec", "Seraphene", "Corvane", "Morgathe", "Isolwen", "Dravik", "Maelis", "Narcissa", "Lucius",
                  "Verenna", "Aurelio", "Selwyn", "Ravenna"],
        "by": ["of the Pale Table", "the Patient", "Night-Born", "the Courteous", "of the Red Cup", "the Long-Lived"],
        "title": ["Lady ", "Lord ", "Count ", "Mistress ", "Master "],
    },
    "necromancer": {
        "first": ["Orsin", "Velka", "Thaddeus", "Iselde", "Morwen", "Caldus", "Drusilla", "Hadrik", "Nerissa", "Umbrel", "Skarn",
                  "Lucan"],
        "by": ["the Careful", "Grave-Taker", "the Hollow Hand", "Bone-Reader", "of the Ninth Candle", "the Lector"],
        "title": ["Magister ", "Adept ", "Ritualist ", "Brother ", "Sister "],
    },
    "mage": {
        "first": ["Aldwyn", "Perrin", "Caraline", "Xerxes", "Melisande", "Orrin", "Thessaly", "Ambrose"],
        "by": ["the Exiled", "of the Sealed Door", "Star-Reader", "the Apostate"],
        "title": ["Magister ", "Warlock ", "Conjurer "],
    },
    "hagraven": {
        "first": ["Skeldra", "Orgwen", "Mugrash", "Hesthra", "Dunmira", "Vorga", "Thistle-Anne", "Grimsel", "Wrenna", "Oldwyth"],
        "by": ["of the Briar", "Feather-Mother", "the Heart-Taker", "Old-Bones", "the Hungry"],
        "title": ["Gristle-Mother ", "Crone ", "Briar-Mother ", "Mother "],
    },
    "forsworn": {
        "first": ["Madra", "Ketha", "Orgul", "Sanna", "Thrull", "Varka", "Edda", "Kyrn", "Bresa", "Haskel"],
        "by": ["Antler-Crown", "Spear-Mother", "the Briar-Sworn", "Red-Feather", "Bone-Chanter", "Ash-Walker", "Hollow-Drum"],
        "title": ["", "Briarheart ", "Hunt-Mother "],
    },
    "falmer": {
        "first": ["Mhuur", "Ysk", "Oozk", "Grath", "Hesh", "Vyl", "Nuum", "Skree", "Thok", "Ilmuk"],
        "by": ["the Blind Elder", "Spore-Warden", "of the Deep Hive", "the Pale Chanter", "Gloom-Keeper"],
        "title": ["Elder ", "Hive-Warden ", "Gloomwarden "],
    },
    "beast": {
        "first": ["Gnarltooth", "Hollowfang", "Bonecrusher", "Mossback", "Wretchhide", "Cragmaw", "Splitclaw", "Dreadpaw",
                  "Hushfur", "Ironhide", "Brindlemaw", "Scarflank", "Grimsnout", "Bleakhowl", "Ashmane", "Thornback"],
        "by": [""],
        "title": ["Old ", "Mother ", "Great ", "Black "],
    },
    "dwemer": {
        "first": ["Kt-7", "Vh-12", "Rn-3", "Dz-9", "Mk-21", "Ph-4", "Sg-16", "Ol-8", "Bt-30", "Nx-5", "Tr-14", "Gl-2"],
        "by": [""],
        "title": ["Warden-Engine ", "Overseer Unit ", "Foundry-Guard ", "Gate-Engine "],
    },
    "ice": {
        "first": ["Rimeheart", "Glasswing", "Frostmaw", "Shardbrow", "Coldwhisper", "Hoarvein", "Icegrip", "Blueclaw"],
        "by": [""],
        "title": ["the ", "Old ", "The Frozen "],
    },
    "mine": {
        "first": ["Pickback", "Deepdelver", "Slagheart", "Cinderjaw", "Rubblefist", "Lodestone", "Gritmaw", "Seamripper"],
        "by": [""],
        "title": ["the ", "Old ", "Foreman "],
    },
    "grove": {
        "first": ["Moss-Crown", "Thornmother", "Oldwood", "Rootwarden", "Fernhollow", "Barkhide", "Wispweaver"],
        "by": [""],
        "title": ["the ", "Mother ", "Elder "],
    },
    "smuggler": {
        "first": ["Saltlip", "Tidehand", "Brinejack", "Knotwork", "Crab-Ear", "Lanternjaw", "Driftwood"],
        "by": [""],
        "title": ["Captain ", "Boss ", "Quartermaster "],
    },
    "cave": {
        "first": ["Hollowback", "Gloomfang", "Stonebrow", "Nightcrawler", "Duskmaw", "Cavepaw"],
        "by": [""],
        "title": ["Old ", "the ", "Great "],
    },
    "giant": {
        "first": ["Gruvuk", "Maghor", "Thundrak", "Bolgrum", "Haggarn", "Skulgar", "Yurrg", "Orgnok"],
        "by": ["Stone-Elbow", "Mammoth-Walker", "Cairn-Kicker", "Big-Drum", "Tree-Splitter"],
        "title": ["Old ", ""],
    },
    "dragon": {
        "first": ["Vulthrax", "Nirkul", "Sahrotor", "Krindoth", "Alduhn", "Faasnuk", "Thodrak"],
        "by": ["the Skyborn", "Storm-Wing", "the Ashen", "of the High Perch"],
        "title": [""],
    },
    "military": {
        "first": ["Aurelian", "Brynjolf", "Cassian", "Dagny", "Eirik", "Fabius", "Gunnar", "Halla", "Iovan", "Jorun"],
        "by": ["the Steadfast", "Iron-Line", "the Quartermaster", "Hard-March", "the Veteran"],
        "title": ["Captain ", "Sergeant ", "Commander "],
    },
    "tower": {
        "first": ["Edrun", "Galdis", "Haldane", "Ismara", "Joric", "Kelwin", "Lysa", "Merrick"],
        "by": ["the Watcher", "Beacon-Keeper", "the Last Lamp", "of the High Stair"],
        "title": ["Warden ", "Keeper ", ""],
    },
    "cabin": {
        "first": ["Otho", "Brenna", "Hargrim", "Pelle", "Roskva", "Ivar", "Dagmar", "Folke", "Sunniva", "Alrek"],
        "by": ["the Hermit", "Tall-Tale", "the Trapper"],
        "title": [""],
    },
    "wreck": {
        "first": ["Nautilus", "Barnacle", "Saltgrave", "Wavebreaker", "Driftmaw", "Kelpbeard", "Gullcry", "Tidewarden"],
        "by": [""],
        "title": ["the ", "Old "],
    },
}


def boss_name(pid, family, used, pools=POOLS):
    pool = pools.get(family) or pools["bandit"]
    seed = h(pid)
    for i in range(600):
        s = seed + i * 2654435761
        first = pool["first"][s % len(pool["first"])]
        by = pool["by"][(s >> 8) % len(pool["by"])]
        title = pool["title"][(s >> 16) % len(pool["title"])]
        name = (title + first + (" " + by if by else "")).strip()
        name = name[0].upper() + name[1:]
        if name not in used:
            used.add(name)
            return name
    # Fallback with place suffix if pool exhausted
    name = f"{pool['title'][0]}{pool['first'][seed % len(pool['first'])]} of {pid.replace('_', ' ').title()}"
    name = name[0].upper() + name[1:]
    used.add(name)
    return name


def pick_motif(pid, name, family):
    motifs = FAMILIES[family][1]
    low = name.lower()
    for tok, m in TOKENS:
        if tok in low and m in motifs:
            return m
    return motifs[h(pid + "m") % len(motifs)]


def pick_notes(notes):
    """(entrance, boss, story) note ids. Entrance: a visitor's note; boss: the 'boss' note; story: what is left."""
    chest = [n for n in notes if n.get("at", "chest") in ("chest", "site")]
    boss = [n for n in notes if n.get("at") == "boss"]
    pref = ("letter", "journal", "diary", "warning", "report", "notes", "orders", "treasure", "list", "ledger", "contract", "inscription", "prayer", "poem", "book")

    def rank(n):
        return (0 if n.get("author") else 1, pref.index(n["kind"]) if n["kind"] in pref else 99)

    chest.sort(key=rank)
    entrance = chest[0] if chest else (boss[0] if boss else None)
    rest = [n for n in notes if n is not entrance]
    bossn = boss[0] if boss and boss[0] is not entrance else (rest[0] if rest else None)
    if bossn is not None:
        rest = [n for n in rest if n is not bossn]
    story = rest[0] if rest else None
    return (entrance["id"] if entrance else "", bossn["id"] if bossn else "", story["id"] if story else "")


def build_province(prov_key, places_file, names, lore, used=None):
    places = load_json(os.path.join(SKY, "tamriel", "places", places_file))
    sheet_path = os.path.join(SKY, "tamriel", "world", f"{prov_key}.json")
    sheet = load_json(sheet_path) if os.path.exists(sheet_path) else {}
    sheet_regions = sheet.get("regions", {}) if isinstance(sheet, dict) else {}
    prov_themes = sheet.get("dungeon_themes", {}) if isinstance(sheet, dict) else {}
    if used is None:
        used = set()
    pools = SKYRIM_POOLS if prov_key == "skyrim" else POOLS
    out = {}
    skipped = []
    for p in places:
        if p["type"] not in ("dungeon_fixed", "fort"):
            continue
        pid, variant = p["id"], p.get("variant") or ("keep" if p["type"] == "fort" else "cave")
        fam = "forsworn" if variant == "barrow" and p.get("culture") == "forsworn" else FAMILY.get(variant)
        if fam is None:
            skipped.append(pid)
            continue
        lore_p = lore.get(pid) or {}
        if prov_key != "skyrim":
            hook = (lore_p.get("hook", "") + " " + lore_p.get("rumour", "")).lower()
            exp = FAMKW.get(fam)
            if exp and hook.strip():
                hits = {k: len(re.findall(v, hook)) for k, v in KW.items()}
                top = max(hits, key=hits.get)
                if hits[top] > 0 and top != exp and hits[top] > hits[exp]:
                    fam = KW_TO_FAM[top]
        name = names.get(pid, pid.replace("_", " ").title())
        notes = lore_p.get("notes", [])
        ent_id, boss_id, story_id = pick_notes(notes)
        by_id = {n["id"]: n for n in notes}
        person = clean_person(by_id[ent_id].get("author")) if ent_id else None
        chief = clean_person(by_id[boss_id].get("author")) if boss_id and fam in CHIEF_FROM_LORE else None
        if chief and chief not in used:
            boss = chief
            used.add(chief)
        else:
            boss = boss_name(pid, fam, used, pools=pools)
        motif = pick_motif(pid, name, fam)
        # sign: two lines of name, then who
        lines = wrap(name, 14, 2)
        while len(lines) < 2:
            lines.append("")
        if fam in ("barrow", "ruin", "dwemer", "dragon"):
            lines += ["Here lies" if fam == "barrow" else "Guarded by", short(boss)]
        elif person:
            lines += ["Last seen:", short(person)]
        else:
            lines += ["Held by:", short(boss)]
        entry = {
            "family": fam, "variant": variant, "boss": boss, "person": person or "", "sign": lines[:4],
            "motif": motif, "loot": FAMILIES[fam][0], "entrance": ent_id,
            "entranceKind": by_id[ent_id]["kind"] if ent_id else "", "bossNote": boss_id, "story": story_id,
        }
        reg_raw = p.get("region", "")
        rkey = reg_raw.split("/", 1)[1] if "/" in reg_raw else reg_raw
        rkey = {"pale": "the_pale", "rift": "the_rift", "reach": "the_reach"}.get(rkey, rkey)
        why = None
        if rkey in sheet_regions and fam in sheet_regions[rkey].get("dungeon_themes", {}):
            why = list(sheet_regions[rkey]["dungeon_themes"][fam])
        elif fam in prov_themes:
            why = list(prov_themes[fam])
        elif p.get("why"):
            why = list(p["why"])
        elif rkey in sheet_regions:
            layers = sheet_regions[rkey].get("layers", {})
            l10 = layers.get("L10", []) or layers.get("L11", []) or layers.get("L8", [])
            if l10:
                why = [f"{rkey}.L10.{l10[0]['id']}"]
        if why:
            entry["why"] = why
        out[pid] = entry
    return out, skipped


def loot_table(key):
    theme, second, items = LOOT_RECIPES[key]
    entries = [{"type": "minecraft:item", "name": n, "weight": w,
                "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]}
               for n, w, lo, hi in items]
    entries.append({"type": "minecraft:empty", "weight": 2})
    return {
        "type": "minecraft:chest",
        "pools": [
            {"rolls": 1, "entries": [{"type": "minecraft:loot_table", "name": "skycraft:" + theme, "weight": 1}]},
            {"rolls": {"type": "minecraft:uniform", "min": 1, "max": 2},
             "entries": [{"type": "minecraft:loot_table", "name": "skycraft:" + second, "weight": 3},
                         {"type": "minecraft:loot_table", "name": "skycraft:chests/gold", "weight": 1}]},
            {"rolls": 1, "entries": entries},
        ],
        "random_sequence": "skycraft:chests/story_" + key,
    }


def blueprint(motif, d):
    layers = [list(l) for l in d["layers"]]
    return {"id": "motif_" + motif, "themes": ["*"], "beats": ["motif:" + motif], "layers": layers, "key": d["key"]}


def dumps(o):
    return json.dumps(o, indent=1, ensure_ascii=False) + "\n"


def main():
    check = "--check" in sys.argv
    names = lang_names()
    lore = lore_places()
    ident_dir = os.path.dirname(IDENT)

    prov_specs = [
        ("skyrim", "skyrim_landmarks.json", "skyrim.json"),
        ("high_rock", "high_rock.json", "high_rock.json"),
        ("hammerfell", "hammerfell.json", "hammerfell.json"),
        ("cyrodiil", "cyrodiil.json", "cyrodiil.json"),
        ("morrowind", "morrowind.json", "morrowind.json"),
        ("black_marsh", "black_marsh.json", "black_marsh.json"),
        ("elsweyr", "elsweyr.json", "elsweyr.json"),
        ("valenwood", "valenwood.json", "valenwood.json"),
        ("summerset", "summerset.json", "summerset.json"),
        ("foreign", "foreign.json", "foreign.json"),
    ]
    all_out = {}
    all_skipped = []
    files = {}
    used_global = set()
    for prov_key, pl_file, out_fn in prov_specs:
        # Skyrim uses its own isolated `used` set so skyrim.json remains byte-for-byte deterministic
        u_set = set() if prov_key == "skyrim" else used_global
        out, skipped = build_province(prov_key, pl_file, names, lore, used=u_set)
        if prov_key == "skyrim":
            used_global |= u_set
        all_out.update(out)
        all_skipped.extend(skipped)
        files[os.path.join(ident_dir, out_fn)] = dumps({
            "note": "Generated by tools/qa/dungeon_identity_gen.py from the places and the place lore. Do not edit.",
            "places": out,
        })

    files[os.path.join(ident_dir, "index.json")] = dumps([s[2] for s in prov_specs])
    used_motifs = {e["motif"] for e in all_out.values()}
    unknown = sorted(m for m in used_motifs if m not in BP_DEFS)
    if unknown:
        raise SystemExit("motifs without a blueprint: %s" % unknown)
    for m in sorted(BP_DEFS):
        files[os.path.join(BP, "motif_%s.json" % m)] = dumps(blueprint(m, BP_DEFS[m]))
    for k in sorted(LOOT_RECIPES):
        files[os.path.join(LOOT, "story_%s.json" % k)] = dumps(loot_table(k))
    idx_path = os.path.join(BP, "index.json")
    idx = load_json(idx_path)
    for m in sorted(BP_DEFS):
        f = "motif_%s.json" % m
        if f not in idx:
            idx.append(f)
    files[idx_path] = dumps(idx)
    changed = 0
    for path, text in files.items():
        old = open(path, encoding="utf8").read() if os.path.exists(path) else None
        if old != text:
            changed += 1
            if not check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf8", newline="\n") as f:
                    f.write(text)
    fam = {}
    for e in all_out.values():
        fam[e["family"]] = fam.get(e["family"], 0) + 1
    print("%d places, %d blueprints, %d loot tables; %d files %s" % (
        len(all_out), len(BP_DEFS), len(LOOT_RECIPES), changed, "differ" if check else "written"))
    print("families:", ", ".join("%s %d" % kv for kv in sorted(fam.items(), key=lambda kv: -kv[1])))
    if all_skipped:
        print("skipped (no family for variant):", ", ".join(all_skipped))
    return 1 if check and changed else 0


if __name__ == "__main__":
    sys.exit(main())
