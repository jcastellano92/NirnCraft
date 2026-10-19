"""The realms' structures, spawn tables, loot, places in Nirn and lore books (see realms.py)."""
from realms import SK, st

S = SK + ":"


# ============================================================================================ structures
def grid(spacing=0, fixed=(), salt=0):
    p = {"type": "nirncraft:realm_grid", "salt": salt, "spacing": spacing}
    if fixed:
        p["fixed"] = [list(f) for f in fixed]
    return p


def spread(spacing, separation, salt):
    return {"type": "minecraft:random_spread", "spacing": spacing, "separation": separation, "salt": salt}


def places(variant, salt):
    return {"type": "nirncraft:places", "salt": salt, "types": ["landmark"], "variants": [variant]}


def chunk_of(x, z):
    return (x >> 4, z >> 4)


# site id -> (biomes, placement). Fixed landmarks name their start chunk (RealmLayout's coordinates).
STRUCTURES = {
    # the Deadlands
    "deadlands/sigil_keep": ("#nirncraft:realm/deadlands", grid(40, salt=11)),
    "deadlands/citadel": ("#nirncraft:realm/deadlands", spread(24, 9, 2041501)),
    "deadlands/spire": ("#nirncraft:realm/deadlands", spread(11, 4, 2041502)),
    "deadlands/bridge": ("#nirncraft:realm/deadlands", spread(9, 3, 2041503)),
    # Sovngarde
    "sovngarde/arrival": ("#nirncraft:realm/sovngarde", grid(fixed=[chunk_of(0, 300)], salt=21)),
    "sovngarde/arena": ("#nirncraft:realm/sovngarde", grid(fixed=[chunk_of(0, 0)], salt=22)),
    "sovngarde/whalebone_bridge": ("#nirncraft:realm/sovngarde", grid(fixed=[chunk_of(0, -126)], salt=23)),
    "sovngarde/hall_of_valor": ("#nirncraft:realm/sovngarde", grid(fixed=[chunk_of(0, -275)], salt=24)),
    "sovngarde/hero_cairn": ("#nirncraft:realm/sovngarde", spread(14, 5, 2041504)),
    # the Shivering Isles
    "isles/fringe": ("#nirncraft:realm/shivering_isles", grid(fixed=[chunk_of(760, 0)], salt=31)),
    "isles/new_sheoth": ("#nirncraft:realm/shivering_isles", grid(fixed=[chunk_of(0, 0)], salt=32)),
    "isles/ruin": ("#nirncraft:realm/shivering_isles", spread(14, 5, 2041505)),
    # Apocrypha
    "apocrypha/chapter": ("#nirncraft:realm/apocrypha", grid(32, salt=41)),
    # the Soul Cairn
    "soul_cairn/arrival": ("#nirncraft:realm/soul_cairn", grid(fixed=[chunk_of(0, 220)], salt=51)),
    "soul_cairn/valerica_keep": ("#nirncraft:realm/soul_cairn", grid(fixed=[chunk_of(0, -180)], salt=52)),
    "soul_cairn/boneyard": ("#nirncraft:realm/soul_cairn", grid(fixed=[chunk_of(480, -60)], salt=53)),
    "soul_cairn/mausoleum": ("#nirncraft:realm/soul_cairn", spread(12, 4, 2041506)),
    # Coldharbour
    "coldharbour/arrival": ("#nirncraft:realm/coldharbour", grid(fixed=[chunk_of(0, 150)], salt=61)),
    "coldharbour/hollow_city": ("#nirncraft:realm/coldharbour", grid(fixed=[chunk_of(0, -200)], salt=62)),
    "coldharbour/ruined_tower": ("#nirncraft:realm/coldharbour", spread(10, 4, 2041507)),
    # the ways in, in Nirn
    "nirn/oblivion_gate": ("#nirncraft:dungeons/anywhere", spread(56, 20, 2041508)),
    "nirn/named_gate": ("#nirncraft:dungeons/anywhere", places("oblivion_gate", 2041509)),
    "nirn/madness_door": ("#nirncraft:dungeons/anywhere", places("madness_door", 2041510)),
    "nirn/black_book_altar": ("#nirncraft:dungeons/anywhere", places("black_book_altar", 2041511)),
    "nirn/soul_cairn_portal": ("#nirncraft:dungeons/anywhere", places("soul_cairn_portal", 2041512)),
    "nirn/dark_anchor": ("#nirncraft:dungeons/anywhere", places("dark_anchor", 2041513)),
}

# discovery names (LocationNames gets them through the realms' hook); generic sites are named by kind
SITE_NAMES = {
    "deadlands/sigil_keep": "Great Sigil Tower", "deadlands/citadel": "Daedric Citadel", "deadlands/spire": "Burning Spire",
    "deadlands/bridge": "Bridge of Thorns", "sovngarde/arrival": "The Misty Vale", "sovngarde/arena": "Alduin's Arena",
    "sovngarde/whalebone_bridge": "The Whalebone Bridge", "sovngarde/hall_of_valor": "The Hall of Valor",
    "sovngarde/hero_cairn": "Hero's Cairn", "isles/fringe": "The Fringe", "isles/new_sheoth": "New Sheoth",
    "isles/ruin": "Mad Ruin", "apocrypha/chapter": "Chapter of Apocrypha", "soul_cairn/arrival": "The Soul Cairn Gate",
    "soul_cairn/valerica_keep": "Valerica's Refuge", "soul_cairn/boneyard": "The Boneyard",
    "soul_cairn/mausoleum": "Soul Cairn Mausoleum", "coldharbour/arrival": "The Dark Anchor",
    "coldharbour/hollow_city": "The Hollow City", "coldharbour/ruined_tower": "Broken Tower",
    "nirn/oblivion_gate": "Oblivion Gate", "nirn/named_gate": "Oblivion Gate", "nirn/madness_door": "Door to the Shivering Isles",
    "nirn/black_book_altar": "Altar of the Black Book", "nirn/soul_cairn_portal": "Soul Cairn Portal",
    "nirn/dark_anchor": "Dark Anchor",
}


def structure_json(site, biomes):
    return {"type": "nirncraft:realm_site", "site": site, "biomes": biomes, "step": "surface_structures",
            "spawn_overrides": {}, "terrain_adaptation": "none"}


def structure_set_json(site, placement):
    return {"structures": [{"structure": S + "realm/" + site, "weight": 1}], "placement": placement}


# ============================================================================================ spawns
def group(entity, weight, count, kind="hostile", time="any", place="surface", levels=(1, 999)):
    return {"kind": kind, "weight": weight, "levels": list(levels), "time": time, "place": place, "entity": entity,
            "count": list(count)}


def members(weight, kind="hostile", levels=(1, 999), *ms):
    return {"kind": kind, "weight": weight, "levels": list(levels), "time": "any", "place": "surface",
            "members": [{"entity": e, "count": list(c)} for e, c in ms]}


SPAWNS = [
    # the Deadlands (the Nether dimension)
    {"regions": ["minecraft:the_nether"], "why": ["cyrodiil.L11.oblivion_crisis", "gold_coast.L10.gate_remnant_under_kvatch"], "groups": [
        group(S + "scamp", 10, (1, 3)), group(S + "clannfear", 8, (1, 2)), group(S + "dremora", 7, (1, 2), levels=(10, 999)),
        group(S + "flame_atronach", 5, (1, 1)), group(S + "daedroth", 4, (1, 1), levels=(15, 999)),
        group(S + "spider_daedra", 3, (1, 1), levels=(18, 999)),
        members(3, "hostile", (20, 999), (S + "dremora", (2, 3)), (S + "scamp", (1, 2)))]},
    {"regions": ["minecraft:the_nether"], "biomes": [S + "deadlands_harrada_thicket", S + "deadlands_blood_fields"],
     "why": ["cyrodiil.L11.oblivion_crisis", "cyrodiil.L8.daedric_shrines"], "groups": [
        group(S + "spider_daedra", 4, (1, 1)), group(S + "clannfear", 4, (2, 3))]},
    # Sovngarde (the End dimension): hero spirits wander; the living are guests
    {"regions": ["minecraft:the_end"], "why": ["whiterun.L8.honour_and_sovngarde", "skyrim.L8.ancestor_barrows"], "groups": [
        group(S + "sovngarde_hero", 10, (1, 2), kind="wildlife"), group(S + "wandering_soul", 6, (1, 3), kind="wildlife")]},
    # the Shivering Isles
    {"regions": ["nirncraft:shivering_isles"], "biomes": [S + "shivering_isles_dementia_swamp", S + "shivering_isles_dementia_forest"],
     "why": ["cyrodiil.L8.daedric_shrines", "nibenay_basin.L10.arrius_cult_cells"], "groups": [
        group(S + "grummite", 10, (2, 4)), group(S + "baliwog", 6, (1, 3)), group(S + "gnarl", 5, (1, 1)),
        group(S + "elytra", 4, (1, 2)), group(S + "flesh_atronach", 2, (1, 1), levels=(15, 999)),
        group(S + "baliwog", 6, (2, 3), place="water")]},
    {"regions": ["nirncraft:shivering_isles"], "biomes": [S + "shivering_isles_mania_fields", S + "shivering_isles_mania_mushroom_forest",
                                                       S + "shivering_isles_mania_crystal_grove", S + "shivering_isles_fringe"],
     "why": ["cyrodiil.L8.daedric_shrines", "cyrodiil.L10.cult_remnants"], "groups": [
        group(S + "elytra", 8, (1, 3)), group(S + "gnarl", 5, (1, 1)), group(S + "grummite", 4, (1, 2)),
        group(S + "flesh_atronach", 2, (1, 1), levels=(15, 999))]},
    # Apocrypha
    {"regions": ["nirncraft:apocrypha"], "why": ["solstheim.L10.black_book_altars", "solstheim.L11.black_book_return"], "groups": [
        group(S + "seeker", 10, (1, 3)), group(S + "lurker", 3, (1, 1), levels=(15, 999)),
        group(S + "lurker", 4, (1, 1), place="water")]},
    # the Soul Cairn
    {"regions": ["nirncraft:soul_cairn"], "why": ["haafingar.L10.soul_cairn_gate", "skyrim.L10.volkihar"], "groups": [
        group(S + "boneman", 9, (1, 3)), group(S + "mistman", 7, (1, 2)), group(S + "wrathman", 3, (1, 1), levels=(18, 999)),
        group(S + "keeper", 2, (1, 1), levels=(25, 999)),
        group(S + "wandering_soul", 10, (2, 4), kind="wildlife")]},
    # Coldharbour
    {"regions": ["nirncraft:coldharbour"], "why": ["cyrodiil.L11.three_banner_war", "cyrodiil.L10.cult_remnants"], "groups": [
        group(S + "dremora", 9, (1, 2)), group(S + "harvester", 4, (1, 1)), group(S + "flame_atronach", 4, (1, 1)),
        group(S + "scamp", 6, (2, 3)), group(S + "daedroth", 3, (1, 1)), group(S + "wandering_soul", 6, (1, 3), kind="wildlife")]},
]


# ============================================================================================ loot
def item(name, weight=1, count=None, nbt=None, enchant=False):
    e = {"type": "minecraft:item", "name": name, "weight": weight}
    fn = []
    if count:
        fn.append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": count[0], "max": count[1]}})
    if nbt:
        fn.append({"function": "minecraft:set_nbt", "tag": nbt})
    if enchant:
        fn.append({"function": "minecraft:enchant_randomly"})
    if fn:
        e["functions"] = fn
    return e


def table(name, weight=1):
    return {"type": "minecraft:loot_table", "name": name, "weight": weight}


def empty(weight):
    return {"type": "minecraft:empty", "weight": weight}


def pool(rolls, *entries):
    r = rolls if isinstance(rolls, (int, float)) else {"type": "minecraft:uniform", "min": rolls[0], "max": rolls[1]}
    return {"rolls": r, "entries": list(entries)}


def book(bid, value):
    return item(S + "book", 1, nbt="{book:\"%s\",nirncraft_value:%d}" % (bid, value))


def black_book(chapter):
    return item(S + "black_book", 1, nbt="{chapter:%d,nirncraft_value:500}" % chapter)


def sigil(weight):
    return {"type": "minecraft:item", "name": S + "sigil_stone", "weight": weight,
            "functions": [{"function": "minecraft:set_nbt", "tag": "{sigil:{ench:\"minecraft:fire_aspect\",lvl:2},nirncraft_value:270}"}]}


DAEDRIC = [S + "daedric_" + p for p in ("sword", "dagger", "war_axe", "mace", "greatsword", "battleaxe", "warhammer", "bow",
                                         "helmet", "chestplate", "leggings", "boots", "shield")]
EBONY = [S + "ebony_" + p for p in ("sword", "dagger", "war_axe", "mace", "greatsword", "bow", "helmet", "chestplate", "leggings", "boots")]

LOOT = {
    "deadlands_sigil": [
        pool(1, table(S + "chests/gold", 1)),
        pool((2, 4), item(S + "daedra_heart", 6), table(S + "chests/soul_gems", 8), item("minecraft:blaze_powder", 4, (2, 5)),
             item(S + "flawless_diamond", 2), table(S + "chests/spell_tomes", 3)),
        pool(1, *[item(n, 1, enchant=True) for n in DAEDRIC], *[item(n, 2, enchant=True) for n in EBONY], empty(14)),
        pool(1, book("on_the_deadlands", 40), book("binding_a_sigil_stone", 40), empty(40)),
    ],
    "deadlands_citadel": [
        pool(1, table(S + "chests/gold", 1)),
        pool((2, 5), item(S + "daedra_heart", 4), table(S + "chests/soul_gems", 6), item("minecraft:blaze_rod", 3, (1, 3)),
             table(S + "chests/smithing_materials", 6), item("minecraft:magma_cream", 3, (1, 4))),
        pool(1, *[item(n, 1) for n in DAEDRIC], *[item(n, 2) for n in EBONY], empty(20)),
        pool(1, book("on_the_deadlands", 30), sigil(6), empty(60)),
    ],
    "deadlands_spire": [
        pool((1, 3), item(S + "daedra_heart", 2), table(S + "chests/soul_gems", 5), item("minecraft:blaze_powder", 5, (1, 4)),
             table(S + "chests/gold", 4), table(S + "chests/alchemy_ingredients", 4)),
        pool(1, book("on_the_deadlands", 20), book("binding_a_sigil_stone", 15), empty(60)),
    ],
    "sovngarde_hall": [
        pool(1, table(S + "chests/gold", 1)),
        pool((2, 4), item("minecraft:honey_bottle", 6, (1, 3)), item("minecraft:golden_apple", 2), item(S + "flawless_diamond", 2),
             table(S + "chests/skill_books", 3), table(S + "chests/spell_tomes", 2), item("minecraft:cooked_beef", 5, (2, 5))),
        pool(1, *[item(S + "dragonbone_" + p, 1, enchant=True) for p in ("sword", "war_axe", "battleaxe", "greatsword", "bow")], empty(10)),
        pool(1, book("the_whalebone_bridge", 50), book("mist_of_sovngarde", 30), empty(30)),
    ],
    "sovngarde_cairn": [
        pool((1, 3), table(S + "chests/gold", 4), item("minecraft:honey_bottle", 4), item("minecraft:iron_ingot", 3, (1, 3)),
             table(S + "chests/skill_books", 1)),
        pool(1, book("the_whalebone_bridge", 30), empty(70)),
    ],
    "isles_palace": [
        pool(1, table(S + "chests/gold", 1)),
        pool((2, 5), item("minecraft:cake", 2), item("minecraft:amethyst_shard", 6, (2, 6)), table(S + "chests/soul_gems", 4),
             item(S + "flawless_diamond", 2), table(S + "chests/spell_tomes", 3), item("minecraft:enchanted_golden_apple", 1)),
        pool(1, book("letter_from_new_sheoth", 40), book("mania_and_dementia", 40), empty(20)),
    ],
    "isles_ruin": [
        pool((1, 3), table(S + "chests/gold", 4), item("minecraft:amethyst_shard", 5, (1, 4)), table(S + "chests/alchemy_ingredients", 5),
             item("minecraft:glow_berries", 3, (2, 5))),
        pool(1, book("mania_and_dementia", 25), book("letter_from_new_sheoth", 20), empty(55)),
    ],
    "apocrypha_chapter": [
        pool((1, 3), table(S + "chests/spell_tomes", 6), table(S + "chests/skill_books", 6), table(S + "chests/gold", 3),
             item("minecraft:ink_sac", 3, (2, 6)), item("minecraft:experience_bottle", 4, (2, 5))),
        pool(1, book("apocrypha_marginalia", 35), black_book(1), black_book(2), black_book(3), black_book(4), black_book(5), empty(50)),
    ],
    "apocrypha_summit": [
        pool(1, table(S + "chests/gold", 1)),
        pool((3, 5), table(S + "chests/spell_tomes", 6), table(S + "chests/skill_books", 6), item("minecraft:experience_bottle", 5, (4, 10)),
             item(S + "flawless_diamond", 2)),
        pool(1, black_book(0), empty(1)),
    ],
    "soul_cairn": [
        pool((1, 3), table(S + "chests/soul_gems", 10), item(S + "ectoplasm", 6, (1, 3)), item("minecraft:bone", 5, (2, 6)),
             table(S + "chests/gold", 3)),
        pool(1, book("the_ideal_masters", 30), empty(70)),
    ],
    "soul_cairn_keep": [
        pool(1, table(S + "chests/gold", 1)),
        pool((2, 4), table(S + "chests/soul_gems", 10), item(S + "ectoplasm", 6, (2, 4)), table(S + "chests/spell_tomes", 3),
             item(S + "flawless_diamond", 2)),
        pool(1, *[item(n, 1, enchant=True) for n in EBONY], empty(8)),
        pool(1, book("the_ideal_masters", 60), empty(40)),
    ],
    "coldharbour": [
        pool((1, 4), table(S + "chests/gold", 4), item(S + "daedra_heart", 3), table(S + "chests/soul_gems", 5),
             item("minecraft:chain", 4, (2, 6)), item("minecraft:netherite_scrap", 1)),
        pool(1, *[item(n, 1) for n in DAEDRIC], empty(30)),
        pool(1, book("chains_of_coldharbour", 35), empty(65)),
    ],
}


# ============================================================================================ places in Nirn
PLACES = [
    {"id": "kvatch_gate", "name": "place.nirncraft.kvatch_gate", "type": "landmark", "class": "marquee", "culture": "imperial",
     "region": "cyrodiil/gold_coast", "x": -2480, "z": 8190, "radius": 18, "variant": "oblivion_gate",
     "why": ["cyrodiil.L11.oblivion_crisis", "gold_coast.L11.kvatch_burned", "gold_coast.L10.gate_remnant_under_kvatch"]},
    {"id": "shivering_isles_door", "name": "place.nirncraft.shivering_isles_door", "type": "landmark", "class": "marquee", "culture": "imperial",
     "region": "sea/topal_bay", "x": 3600, "z": 10330, "radius": 12, "variant": "madness_door",
     "why": ["cyrodiil.L8.daedric_shrines", "nibenay_basin.L10.arrius_cult_cells", "cyrodiil.L11.oblivion_crisis"]},
    {"id": "black_book_epistolary", "name": "place.nirncraft.black_book_epistolary", "type": "landmark", "class": "marquee", "culture": "dunmer",
     "region": "morrowind/solstheim", "x": 4990, "z": -3000, "radius": 8, "variant": "black_book_altar",
     "why": ["solstheim.L10.black_book_altars", "solstheim.L11.black_book_return", "morrowind.L10.daedric_ruins"]},
    {"id": "black_book_sallow_regent", "name": "place.nirncraft.black_book_sallow_regent", "type": "landmark", "class": "marquee", "culture": "nord",
     "region": "morrowind/solstheim", "x": 4290, "z": -3600, "radius": 8, "variant": "black_book_altar",
     "why": ["solstheim.L10.black_book_altars", "solstheim.L11.black_book_return", "morrowind.L10.daedric_ruins"]},
    {"id": "black_book_filament", "name": "place.nirncraft.black_book_filament", "type": "landmark", "class": "marquee", "culture": "nord",
     "region": "morrowind/solstheim", "x": 4230, "z": -3150, "radius": 8, "variant": "black_book_altar",
     "why": ["solstheim.L10.black_book_altars", "solstheim.L11.black_book_return", "morrowind.L10.daedric_ruins"]},
    {"id": "soul_cairn_portal", "name": "place.nirncraft.soul_cairn_portal", "type": "landmark", "class": "marquee", "culture": "imperial",
     "region": "skyrim/haafingar", "x": -3148, "z": -2402, "radius": 10, "variant": "soul_cairn_portal",
     "why": ["skyrim.L10.volkihar", "haafingar.L10.soul_cairn_gate", "haafingar.L10.volkihar_castle"]},
    {"id": "dark_anchor", "name": "place.nirncraft.dark_anchor", "type": "landmark", "class": "marquee", "culture": "imperial",
     "region": "cyrodiil/jerall_mountains", "x": 1010, "z": 2530, "radius": 12, "variant": "dark_anchor",
     "why": ["cyrodiil.L10.cult_remnants", "jerall_mountains.L10.sancre_tor_barrow", "cyrodiil.L11.three_banner_war"]},
]

BOOK_WHY = {
    "on_the_deadlands": ["cyrodiil.L11.oblivion_crisis", "gold_coast.L10.gate_remnant_under_kvatch"],
    "binding_a_sigil_stone": ["cyrodiil.L11.oblivion_crisis", "nibenay_basin.L5.morrowind_trade"],
    "the_whalebone_bridge": ["skyrim.L8.ancestor_barrows", "whiterun.L8.honour_and_sovngarde"],
    "letter_from_new_sheoth": ["cyrodiil.L8.daedric_shrines", "nibenay_basin.L10.arrius_cult_cells"],
    "mania_and_dementia": ["cyrodiil.L8.daedric_shrines", "cyrodiil.L10.cult_remnants"],
    "apocrypha_marginalia": ["solstheim.L10.black_book_altars", "solstheim.L8.ink_dread"],
    "the_ideal_masters": ["haafingar.L10.soul_cairn_gate", "skyrim.L10.volkihar"],
    "chains_of_coldharbour": ["cyrodiil.L11.three_banner_war", "cyrodiil.L10.cult_remnants"],
}

# Lore books: id -> (category, cover, weight, value, title, author, pages). Original text.
BOOKS = {
    "on_the_deadlands": ("DAEDRA", "RED", 2, 60, "On the Deadlands", "Quintus Ravel, Imperial Battlemage (retired)", [
        "#What Lies Behind the Gate\n\nThe first thing you notice is the heat. The second is the sky: red as a cut, and low, as if it were pressing down on the island you stand on.\n\nThe Deadlands are not one land but a scatter of black rock islands in a sea of fire. You cross from one to the next on bridges the Daedra built, and you do not look down.",
        "#The Towers\n\nOn every island that matters there is a tower, taller than anything in the Imperial City. The Dremora call them sigil towers. At the top, past a hundred turns of a ramp, burns the stone that holds the gate open. A column of blood-red fire climbs the shaft at the tower's heart; brave fools ride it.\n\nTake the stone, and the gate dies. The island throws you out like a bad tooth.",
        "#Advice\n\nThe thorny vines they call harrada will open your legs to the bone. Walk around them.\n\nScamps are cowards; clannfear are not. A daedroth will breathe fire at you from further away than you think.\n\nThe red grass hides lava under its roots. Watch where the ground glows."]),
    "binding_a_sigil_stone": ("GUIDE", "BLACK", 2, 45, "Binding a Sigil Stone", "Heddvild, smith of Cheydinhal", [
        "#On Sigil Stones\n\nEvery stone torn from a sigil tower carries one enchantment, burned in by the Daedra who fed it. You cannot choose it. You can only choose what you give it to.",
        "#The Binding\n\nHold the stone in your sword hand. Hold the thing you want enchanted in the other. Then will it, the way you would will a door to open.\n\nThe stone crumbles to warm grit. The enchantment stays.\n\nIt won't bind to something that can't take it: a fire-ward won't sit in a sword, and a blade's edge won't sit in a boot."]),
    "the_whalebone_bridge": ("POEM", "BLUE", 2, 30, "The Whalebone Bridge", "A skald of Windhelm", [
        "~Over the chasm the old bones lie,\n~white as the frost on the river reed,\n~and the mist below is a sea of sighs\n~where the cowards fall and the faithful lead.",
        "~Tsun stands at the near end, broad as a door,\n~and asks every soul what it did with its days.\n~Some answer with deeds, and he lets them pass;\n~some answer with boasts, and he shows them the ways.",
        "~Beyond is the Hall where the long fires burn,\n~where Ysgramor drinks with his five hundred kin.\n~Live so the old giant steps aside,\n~and the doors of the Hall will let you in."]),
    "letter_from_new_sheoth": ("LETTER", "SHEET", 2, 15, "Letter from New Sheoth", "Your loving cousin, Tervyn", [
        "Dear cousin,\n\nThe Duke has been very kind. I have a house in Bliss with a garden that sings, mostly in the mornings. The cheese here is extraordinary. The neighbours are mostly fine.\n\nI am told the other half of the city is called Crucible and that I must never go there after dark. Or before dark. I am told many things.",
        "The weather changes when He is in a mood. Last week it rained fish, which the cooks were pleased about, and then it rained forks, which the cooks were less pleased about.\n\nPlease do not come to visit. Not because I don't miss you, but because the door only opens one way for some people, and I have not yet worked out which way it opens for me.\n\nP.S. Bring cheese."]),
    "mania_and_dementia": ("DAEDRA", "GREEN", 2, 50, "Mania and Dementia", "Halcyon Mytal, collector of curiosities", [
        "#Two Halves of a Mind\n\nThe Shivering Isles are divided as their master is divided. North lies Mania: mushrooms tall as towers, glowing caps, flowers in colours that have no names. It is beautiful, and everyone in it is a little too happy.",
        "South lies Dementia: mire, fog, twisted black trees, eyes in the reeds. It is dreadful, and everyone in it is a little too watchful.\n\nThe city of New Sheoth sits on the hill between them, the Palace sits in the middle of the city, and the Prince sits in the middle of the Palace. Nobody knows which half of him is listening.",
        "#Of the Inhabitants\n\nGrummites are the fish-faced folk of the swamps. Their young, the baliwogs, live in the water and bite. Gnarls are walking trees, ancient and cross. The elytra are insects the size of hounds.\n\nThe flesh atronachs were made, not born. Ask no one by whom.\n\nTo leave, find the Fringe in the east, where the Door stands. It opens both ways. Usually."]),
    "apocrypha_marginalia": ("NOTE", "SHEET", 1, 20, "Marginalia from Apocrypha", "Unknown hand", [
        "it doesn't END. every shelf has a shelf behind it. every book has a book inside it. the pages turn when I'm not looking and they are always on the page I need. that is the worst part.",
        "I found my own name in a book. it was spelled correctly. it listed everything I have ever forgotten.\n\nthe Seekers drift between the stacks and they WATCH. the big ones, the Lurkers, wade through the ink like it's a shallow stream.\n\nthere is a book at the end of every chapter. read it to go on. read the other one to go HOME. I have read the wrong one four times.",
        "if you are reading this, I got out. or I didn't, and this is a book now. either way: do not bargain with the eyes in the sky."]),
    "the_ideal_masters": ("DAEDRA", "BLACK", 1, 55, "The Ideal Masters", "Fragment, author unknown", [
        "#The Cairn\n\nThe Ideal Masters were once mortal. They made themselves into something else, and the something else is hungry.\n\nEvery soul trapped in a black soul gem goes to them. Their realm is a waste of violet ash under a sky like a bruise, crowded with the souls they have collected.",
        "#Its Keepers\n\nThe Keepers guard what the Masters prize. The Bonemen are the remains of soldiers. The Mistmen are what is left when even the bones are gone. The Wrathmen are angry about it.\n\nThe portal asks a toll of the living who enter the first time: a soul, in a gem. Pay it. The alternative is that it takes yours."]),
    "chains_of_coldharbour": ("DAEDRA", "BLACK", 1, 55, "Chains of Coldharbour", "A survivor's account, set down at the Imperial City", [
        "#The Mirror\n\nColdharbour looks like home, if home were burned and hollowed and hung on chains. I saw the Imperial City there, or a broken copy of it, with fires in its windows that gave no warmth.",
        "The Prince of Domination drops his anchors from the sky: chains as thick as a ship's hull, ending in black spikes buried in the earth. Where an anchor lands, the realms touch.\n\nI escaped through one of them, at the place where I had arrived. I still hear the chains when it's quiet."]),
}
