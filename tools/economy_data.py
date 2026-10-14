#!/usr/bin/env python3
"""Writes the economy's data: merchant stock tags (by shop type and by province), regional ingredient and forage
tags, contraband/trophy/jewellery tags, creature soul tiers, Skyrim-like leveled loot tables, foraging loot
modifiers, and the values and weights of the alchemy ingredients. Idempotent: re-run after editing the tables below.

    py -3 tools/economy_data.py
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "mod" / "src" / "main"
DATA = MAIN / "resources" / "data" / "skycraft"
JAVA = MAIN / "java" / "com" / "skycraft"
TAGS = DATA / "tags" / "items"
LOOT = DATA / "loot_tables"
SK = "skycraft:"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def ids(*names):
    return [n if ":" in n or n.startswith("#") else SK + n for n in names]


def tag(path, values, optional=True):
    """Item tag; skycraft entries from other workstreams' modules are optional so a missing item never breaks it."""
    vals = []
    for v in values:
        if optional and (v.startswith("#") or v.startswith(SK)):
            vals.append({"id": v, "required": False})
        else:
            vals.append(v)
    write(TAGS / (path + ".json"), {"replace": False, "values": vals})


# ===================================================================================== alchemy ingredients

def ingredient_ids():
    text = (JAVA / "crafting" / "arcane" / "alchemy" / "Ingredients.java").read_text(encoding="utf-8")
    return re.findall(r'add\("([a-z]+:[a-z0-9_]+)"', text)


SKYRIM_ING = ids("salt_pile", "nightshade", "deathbell", "nirnroot", "frost_mirriam", "bear_claws", "hawk_feather",
                 "canis_root", "creep_cluster", "elves_ear", "imp_stool", "juniper_berries", "namiras_rot", "white_cap",
                 "glowing_mushroom", "bleeding_crown", "hanging_moss", "swamp_fungal_pod", "thistle_branch",
                 "giant_lichen", "mora_tapinella", "poison_bloom", "rock_warbler_egg", "pine_thrush_egg", "hawks_egg",
                 "hawk_beak", "small_antlers", "large_antlers", "sabre_cat_tooth", "eye_of_sabre_cat", "mudcrab_chitin",
                 "slaughterfish_scales", "troll_fat", "giants_toe", "skeever_tail", "spider_egg", "chaurus_eggs",
                 "falmer_ear", "briar_heart", "hagraven_feathers", "hagraven_claw", "spriggan_sap", "ice_wraith_teeth",
                 "powdered_mammoth_tusk", "pearl", "small_pearl", "nordic_barnacle", "jazbay_grapes", "wisp_wrappings",
                 "torchbug_thorax", "luna_moth_wing", "monarch_wing", "blue_butterfly_wing", "blue_dartwing", "garlic",
                 "vampire_dust", "fire_salts", "frost_salts", "void_salts", "glow_dust", "ectoplasm", "bone_meal_draugr",
                 "chaurus_hunter_antennae", "dwarven_oil", "frostbite_venom")

REGIONAL_ING = {
    "skyrim": SKYRIM_ING,
    "solstheim": ids("trama_root", "ashen_grass_pod", "ash_creep_cluster", "ash_hopper_jelly", "boar_tusk", "spawn_ash",
                     "scathecraw", "netch_jelly", "emperor_parasol_moss", "burnt_spriggan_wood", "frost_mirriam",
                     "nordic_barnacle", "salt_pile", "white_cap", "bone_meal_draugr"),
    "morrowind": ids("comberry", "marshmerrow", "saltrice", "hypha_facia", "bittergreen_petals", "gold_kanet",
                     "scrib_jelly", "kwama_egg", "ash_yam", "ash_salts", "trama_root", "scathecraw", "netch_jelly",
                     "guar_hide", "alit_hide", "kagouti_hide", "cliff_racer_plumes", "nix_hound_meat",
                     "emperor_parasol_moss", "fire_salts", "daedra_heart", "glowing_mushroom", "ashen_grass_pod",
                     "ash_creep_cluster", "scamp_skin"),
    "cyrodiil": ids("nirnroot", "elves_ear", "canis_root", "imp_stool", "nightshade", "jazbay_grapes", "monarch_wing",
                    "minotaur_horn", "ogre_tooth", "spriggan_sap", "wisp_wrappings", "dreugh_wax", "bleeding_crown",
                    "white_cap", "ectoplasm", "scamp_skin", "bear_claws", "salt_pile", "hawk_feather", "garlic",
                    "minecraft:lilac", "minecraft:red_tulip", "minecraft:white_tulip"),
    "elsweyr": ids("moon_sugar", "minecraft:sugar", "minecraft:cocoa_beans", "scorpion_stinger", "harpy_feather",
                   "sabre_cat_tooth", "jazbay_grapes", "mudcrab_chitin", "juniper_berries", "salt_pile", "hawk_feather",
                   "minecraft:orange_tulip"),
    "black_marsh": ids("hist_sap", "swamp_fungal_pod", "wamasu_scale", "leviathan_tooth", "hanging_moss", "nightshade",
                       "deathbell", "chaurus_eggs", "bittergreen_petals", "hypha_facia", "mudcrab_chitin",
                       "slaughterfish_scales", "marshmerrow", "minecraft:blue_orchid", "poison_bloom"),
    "valenwood": ids("spriggan_sap", "jazbay_grapes", "elves_ear", "canis_root", "hanging_moss", "monarch_wing",
                     "luna_moth_wing", "blue_butterfly_wing", "imp_stool", "poison_bloom", "wisp_wrappings",
                     "glowing_mushroom", "hawk_feather", "small_antlers"),
    "summerset": ids("elves_ear", "pearl", "small_pearl", "void_salts", "frost_salts", "fire_salts", "dreugh_wax",
                     "indrik_feather", "mora_tapinella", "gold_kanet", "minecraft:white_tulip",
                     "minecraft:lily_of_the_valley"),
    "hammerfell": ids("scorpion_stinger", "harpy_feather", "ogre_tooth", "juniper_berries", "salt_pile", "dreugh_wax",
                      "pearl", "sea_serpent_scale", "gargoyle_shard", "minecraft:cocoa_beans", "mudcrab_chitin"),
    "high_rock": ids("ogre_tooth", "gargoyle_shard", "harpy_feather", "nightshade", "deathbell", "white_cap",
                     "bleeding_crown", "imp_stool", "hagraven_feathers", "hagraven_claw", "spriggan_sap",
                     "juniper_berries", "nirnroot", "canis_root", "hawk_feather"),
    "orsinium": ids("troll_fat", "ogre_tooth", "juniper_berries", "frost_mirriam", "thistle_branch", "bear_claws",
                    "sabre_cat_tooth", "large_antlers", "white_cap", "garlic"),
    "akavir": ids("kamal_horn", "tsaesci_scale", "frost_salts", "small_pearl", "pearl", "ectoplasm", "glowing_mushroom",
                  "minecraft:pink_tulip", "minecraft:white_tulip"),
    "atmora": ids("frost_salts", "frost_mirriam", "ice_wraith_teeth", "white_cap", "thistle_branch", "sabre_cat_tooth",
                  "large_antlers", "nordic_barnacle"),
    "yokuda": ids("salt_pile", "scorpion_stinger", "sea_serpent_scale", "pearl", "small_pearl", "harpy_feather",
                  "juniper_berries", "fire_salts"),
    "pyandonea": ids("pearl", "small_pearl", "sea_serpent_scale", "leviathan_tooth", "luna_moth_wing", "dreugh_wax",
                     "hist_sap", "slaughterfish_scales"),
    "realm": ids("salt_pile", "nightshade", "glowing_mushroom", "white_cap", "imp_stool", "spider_egg", "glow_dust",
                 "ectoplasm", "bear_claws", "hawk_feather", "small_antlers", "pearl"),
}

FORAGE = {
    # grass, ferns and bushes: wild herbs
    "plants": {
        "skyrim": ids("canis_root", "elves_ear", "thistle_branch", "juniper_berries", "creep_cluster", "jazbay_grapes",
                      "giant_lichen"),
        "solstheim": ids("ashen_grass_pod", "trama_root", "thistle_branch"),
        "morrowind": ids("comberry", "bittergreen_petals", "gold_kanet", "ashen_grass_pod", "trama_root"),
        "cyrodiil": ids("elves_ear", "canis_root", "jazbay_grapes"),
        "valenwood": ids("jazbay_grapes", "elves_ear", "canis_root"),
        "elsweyr": ids("juniper_berries", "jazbay_grapes"),
        "black_marsh": ids("bittergreen_petals", "marshmerrow"),
        "hammerfell": ids("juniper_berries", "elves_ear"),
        "high_rock": ids("canis_root", "juniper_berries", "thistle_branch", "elves_ear"),
        "orsinium": ids("thistle_branch", "juniper_berries"),
        "summerset": ids("elves_ear", "gold_kanet"),
        "atmora": ids("thistle_branch"),
    },
    # mushrooms broken in the wild and in caves
    "fungi": {
        "skyrim": ids("imp_stool", "namiras_rot", "white_cap", "bleeding_crown", "mora_tapinella", "glowing_mushroom",
                      "swamp_fungal_pod", "creep_cluster"),
        "solstheim": ids("emperor_parasol_moss", "ash_creep_cluster", "white_cap"),
        "morrowind": ids("hypha_facia", "emperor_parasol_moss", "ash_creep_cluster", "glowing_mushroom"),
        "black_marsh": ids("swamp_fungal_pod", "hypha_facia", "imp_stool"),
        "valenwood": ids("imp_stool", "glowing_mushroom", "mora_tapinella"),
        "high_rock": ids("bleeding_crown", "white_cap", "namiras_rot", "imp_stool"),
    },
    # birds' nests in tree crowns
    "nests": {
        "skyrim": ids("rock_warbler_egg", "pine_thrush_egg", "hawks_egg"),
        "cyrodiil": ids("rock_warbler_egg", "hawks_egg"),
        "high_rock": ids("pine_thrush_egg", "hawks_egg"),
    },
}

TROPHIES = ids("wolf_pelt", "echatere_hide", "durzog_hide", "dwemer_gear", "horker_tusk", "mammoth_tusk", "netch_leather")
CONTRABAND = ids("moon_sugar", "skooma")

# Skyrim gold values (game facts) of the new ingredients; Skyrim's 0-value ones are 1 here
NEW_VALUES = {
    "canis_root": 5, "creep_cluster": 1, "elves_ear": 10, "imp_stool": 1, "jazbay_grapes": 1, "juniper_berries": 1,
    "namiras_rot": 1, "white_cap": 1, "glowing_mushroom": 5, "bleeding_crown": 10, "hanging_moss": 1,
    "swamp_fungal_pod": 5, "thistle_branch": 1, "giant_lichen": 5, "mora_tapinella": 4, "poison_bloom": 5,
    "emperor_parasol_moss": 1, "burnt_spriggan_wood": 20, "rock_warbler_egg": 2, "pine_thrush_egg": 2, "hawks_egg": 5,
    "hawk_beak": 15, "pearl": 2, "small_pearl": 2, "nordic_barnacle": 5, "trama_root": 1, "ashen_grass_pod": 1,
    "ash_creep_cluster": 20, "ash_hopper_jelly": 20, "boar_tusk": 20, "spawn_ash": 20, "comberry": 2,
    "marshmerrow": 2, "saltrice": 1, "hypha_facia": 1, "bittergreen_petals": 5, "gold_kanet": 5, "scrib_jelly": 10,
    "kwama_egg": 2, "ash_yam": 1, "moon_sugar": 50, "hist_sap": 25, "briar_heart": 20, "falmer_ear": 10,
    "spider_egg": 5, "eye_of_sabre_cat": 2, "powdered_mammoth_tusk": 2, "wisp_wrappings": 2, "dwarven_oil": 15,
    "chaurus_hunter_antennae": 2, "scathecraw": 1,
}
NEW_WEIGHTS = {k: 0.1 for k in NEW_VALUES}
NEW_WEIGHTS.update({"boar_tusk": 0.5, "burnt_spriggan_wood": 0.5, "dwarven_oil": 0.25, "briar_heart": 0.2,
                    "scathecraw": 0.1, "hist_sap": 0.25, "kwama_egg": 0.5})


# ===================================================================================== merchant stock tags

GEMS = ids("garnet", "ruby", "sapphire", "flawless_garnet", "flawless_ruby", "flawless_sapphire", "flawless_diamond",
           "minecraft:diamond", "minecraft:emerald", "minecraft:amethyst_shard", "pearl", "small_pearl")
JEWELRY = ids("silver_ring", "gold_ring", "silver_necklace", "gold_necklace", "silver_garnet_ring", "gold_ruby_necklace",
              "gold_diamond_ring", "copper_circlet", "silver_circlet", "gold_circlet", "jade_circlet", "amulet_of_talos",
              "amulet_of_mara", "jade_pendant", "sapphire_pendant")
SMITHING_MATS = ids("minecraft:iron_ingot", "steel_ingot", "corundum_ingot", "orichalcum_ingot", "silver_ingot",
                    "quicksilver_ingot", "refined_moonstone", "refined_malachite", "ebony_ingot", "dwarven_metal_ingot",
                    "minecraft:gold_ingot", "minecraft:leather", "leather_strips", "hide", "firewood", "raw_corundum",
                    "raw_orichalcum", "raw_silver", "raw_quicksilver", "raw_moonstone", "raw_malachite", "raw_ebony",
                    "minecraft:coal", "minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel")

MERCHANT = {
    "blacksmith": SMITHING_MATS,
    "apothecary": ids("minecraft:potion", "minecraft:potion", "minecraft:splash_potion", "minecraft:glass_bottle",
                      "salt_pile", "nightshade", "deathbell", "frost_mirriam", "bear_claws", "hawk_feather", "garlic",
                      "minecraft:nether_wart", "minecraft:spider_eye", "minecraft:glowstone_dust",
                      "minecraft:red_mushroom", "minecraft:brown_mushroom", "minecraft:honeycomb", "fire_salts",
                      "frost_salts", "vampire_dust"),
    "arcane": ids("minecraft:book", "minecraft:paper", "minecraft:amethyst_shard", "minecraft:glowstone_dust",
                  "minecraft:potion", "void_salts", "fire_salts", "frost_salts", "glow_dust", "ectoplasm",
                  "soul_gem_petty", "soul_gem_lesser"),
    "general": ids("minecraft:bread", "minecraft:apple", "minecraft:torch", "minecraft:lantern", "minecraft:candle",
                   "minecraft:bucket", "minecraft:string", "minecraft:leather", "minecraft:lead", "minecraft:paper",
                   "minecraft:book", "minecraft:glass_bottle", "minecraft:shears", "minecraft:fishing_rod",
                   "minecraft:flint_and_steel", "minecraft:saddle", "minecraft:compass", "minecraft:white_wool",
                   "minecraft:bowl", "minecraft:potion", "minecraft:iron_pickaxe", "minecraft:iron_axe",
                   "minecraft:iron_shovel", "lockpick", "leather_strips", "firewood", "hide", "minecraft:iron_ingot",
                   "steel_ingot", "iron_arrow", "steel_arrow", "soul_gem_petty", "salt_pile", "garlic", "cabbage",
                   "leek", "tomato", "sweetroll", "nord_mead", "ale", "goat_cheese_wedge", "raw_venison",
                   "minecraft:wheat"),
    "fence": ids("lockpick", "minecraft:gold_ingot", "silver_ingot", "minecraft:gold_nugget", "minecraft:splash_potion",
                 "minecraft:splash_potion", "skooma", "moon_sugar", "#skycraft:thief_goods", "#skycraft:merchant/gems",
                 "#skycraft:jewelry", "minecraft:name_tag", "minecraft:spyglass", "minecraft:clock"),
    "fletcher": ids("iron_arrow", "steel_arrow", "orcish_arrow", "dwarven_arrow", "elven_arrow", "steel_bolt",
                    "dwarven_bolt", "minecraft:feather", "minecraft:flint", "minecraft:string", "firewood",
                    "leather_strips", "imperial_crossbow", "hunting_bow"),
    "exotic": ids("moon_sugar", "skooma", "minecraft:sugar", "minecraft:cocoa_beans", "jazbay_grapes", "elsweyr_fondue",
                  "pearl", "small_pearl", "garnet", "ruby", "sapphire", "silver_ring", "gold_ring", "hawk_feather",
                  "sabre_cat_tooth", "minecraft:honey_bottle", "minecraft:rabbit_foot", "salt_pile", "alto_wine",
                  "spiced_wine", "khajiit_dagger", "khajiit_helmet", "khajiit_boots", "lockpick"),
    "hunter": ids("hide", "minecraft:leather", "leather_strips", "wolf_pelt", "minecraft:rabbit_hide",
                  "minecraft:rabbit_foot", "minecraft:feather", "minecraft:bone", "small_antlers", "large_antlers",
                  "sabre_cat_tooth", "bear_claws", "hawk_feather", "horker_tusk", "raw_venison", "raw_horker_meat",
                  "raw_goat_meat", "iron_arrow", "steel_arrow"),
    "food": ids("minecraft:bread", "minecraft:apple", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
                "minecraft:wheat", "minecraft:egg", "minecraft:milk_bucket", "minecraft:honey_bottle",
                "minecraft:sugar", "minecraft:cooked_beef", "minecraft:cooked_mutton", "minecraft:cooked_chicken",
                "minecraft:cooked_salmon", "minecraft:pumpkin", "minecraft:sweet_berries", "#skycraft:food_goods"),
    "library": ids("minecraft:book", "minecraft:paper", "minecraft:writable_book", "minecraft:ink_sac",
                   "minecraft:feather", "minecraft:candle", "minecraft:lantern", "minecraft:bookshelf",
                   "minecraft:glow_ink_sac", "minecraft:map"),
    "jeweler": GEMS + JEWELRY + ids("silver_ingot", "minecraft:gold_ingot", "minecraft:gold_nugget", "raw_silver"),
    # a tailor's counter (settlement TAILOR anchor, economy/shop_trades.json): cloth, leather, hide and light clothing
    "tailor": ids("minecraft:white_wool", "minecraft:black_wool", "minecraft:brown_wool", "minecraft:gray_wool",
                  "minecraft:red_wool", "minecraft:blue_wool", "minecraft:string", "minecraft:shears", "minecraft:leather",
                  "minecraft:white_carpet", "minecraft:red_carpet", "minecraft:leather_helmet",
                  "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots",
                  "leather_strips", "hide", "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
                  "hide_helmet", "hide_chestplate", "hide_leggings", "hide_boots", "fur_helmet", "fur_chestplate",
                  "fur_leggings", "fur_boots"),
}

# province goods: #skycraft:merchant/<type>/<gear key>
PROVINCE_GOODS = {
    "tailor": {
        "morrowind": ids("netch_leather", "guar_hide", "kagouti_hide", "alit_hide"),
        "skyrim": ids("wolf_pelt", "fur_helmet", "fur_chestplate", "fur_leggings", "fur_boots"),
    },
    "general": {
        "morrowind": ids("kwama_egg", "saltrice", "ash_yam", "comberry", "marshmerrow", "netch_leather", "chitin_plate",
                         "guar_hide", "scrib_jelly", "netch_jelly"),
        "solstheim": ids("netch_leather", "chitin_plate", "boar_tusk", "ashen_grass_pod", "trama_root", "hide"),
        "cyrodiil": ids("alto_wine", "spiced_wine", "goat_cheese_wedge", "eidar_cheese_wedge", "jazbay_grapes",
                        "minecraft:apple", "elves_ear"),
        "elsweyr": ids("moon_sugar", "minecraft:sugar", "elsweyr_fondue", "jazbay_grapes", "minecraft:cocoa_beans"),
        "black_marsh": ids("hist_sap", "marshmerrow", "bittergreen_petals", "chitin_plate", "minecraft:cod"),
        "valenwood": ids("raw_venison", "minecraft:cooked_mutton", "hide", "minecraft:bone", "jazbay_grapes"),
        "summerset": ids("alto_wine", "pearl", "elves_ear", "refined_moonstone"),
        "hammerfell": ids("minecraft:cocoa_beans", "salt_pile", "juniper_berries", "minecraft:sugar"),
        "high_rock": ids("spiced_wine", "goat_cheese_wedge", "juniper_berries", "silver_ingot"),
        "orsinium": ids("orichalcum_ingot", "raw_orichalcum", "troll_fat", "minecraft:cooked_beef"),
        "akavir": ids("pearl", "small_pearl", "minecraft:sugar", "steel_ingot"),
    },
    "blacksmith": {
        "morrowind": ids("chitin_plate", "netch_leather", "minecraft:bone", "ebony_ingot", "raw_ebony"),
        "solstheim": ids("stalhrim", "chitin_plate", "netch_leather", "ebony_ingot"),
        "summerset": ids("refined_moonstone", "refined_malachite", "quicksilver_ingot"),
        "hammerfell": ids("steel_ingot", "corundum_ingot"),
        "orsinium": ids("orichalcum_ingot", "raw_orichalcum"),
        "black_marsh": ids("chitin_plate", "minecraft:bone"),
        "elsweyr": ids("minecraft:gold_ingot", "steel_ingot"),
        "valenwood": ids("minecraft:bone", "hide"),
        "high_rock": ids("silver_ingot", "steel_ingot"),
        "akavir": ids("steel_ingot", "refined_moonstone"),
        "cyrodiil": ids("steel_ingot", "corundum_ingot", "minecraft:iron_ingot"),
    },
    "exotic": {
        "elsweyr": ids("moon_sugar", "skooma", "elsweyr_fondue", "khajiit_sword", "khajiit_chestplate"),
    },
    "food": {
        "morrowind": ids("kwama_egg", "saltrice", "ash_yam", "comberry", "marshmerrow", "scrib_jelly"),
        "solstheim": ids("ash_yam", "kwama_egg", "boar_tusk"),
        "elsweyr": ids("moon_sugar", "minecraft:sugar", "elsweyr_fondue"),
        "black_marsh": ids("marshmerrow", "minecraft:cod", "minecraft:salmon"),
        "cyrodiil": ids("alto_wine", "spiced_wine", "jazbay_grapes"),
    },
    "fisher": {
        "skyrim": ids("nordic_barnacle", "small_pearl", "slaughterfish_scales", "mudcrab_chitin"),
        "summerset": ids("pearl", "small_pearl"),
        "hammerfell": ids("pearl", "small_pearl", "sea_serpent_scale"),
        "black_marsh": ids("slaughterfish_scales", "mudcrab_chitin", "small_pearl"),
        "morrowind": ids("mudcrab_chitin", "slaughterfish_scales", "small_pearl"),
    },
    "fence": {
        "skyrim": ids("skooma", "moon_sugar"),
        "cyrodiil": ids("skooma", "moon_sugar"),
    },
    "hunter": {
        "morrowind": ids("guar_hide", "alit_hide", "kagouti_hide", "netch_leather", "cliff_racer_plumes"),
        "solstheim": ids("boar_tusk", "netch_leather", "hide"),
        "orsinium": ids("echatere_hide", "durzog_hide", "troll_fat"),
        "black_marsh": ids("wamasu_scale", "leviathan_tooth"),
        "hammerfell": ids("scorpion_stinger", "harpy_feather"),
        "elsweyr": ids("sabre_cat_tooth", "scorpion_stinger"),
    },
}


def merchant_tags():
    for t, values in MERCHANT.items():
        tag(f"merchant/{t}", values)
    for t, by_key in PROVINCE_GOODS.items():
        for key, values in by_key.items():
            tag(f"merchant/{t}/{key}", values)
    # apothecaries everywhere carry their province's ingredients
    for key in REGIONAL_ING:
        tag(f"merchant/apothecary/{key}", ["#skycraft:loot/ingredients/" + key])
    tag("merchant/gems", GEMS)
    tag("jewelry", JEWELRY)
    tag("contraband", CONTRABAND)
    tag("trophies", TROPHIES)
    ing = [i for i in ingredient_ids()]
    tag("merchant/ingredients", ing)
    # every skill's book (the skills expansion added Acrobatics, Athletics, Mysticism, Polearms, Riding...)
    books = skill_books()
    tag("merchant/skill_books", books, optional=False)
    loot("chests/skill_books", table("chest", [pool([item(b) for b in books])], "chests/skill_books"))


def skill_books():
    out = set()
    for f in (MAIN / "lang" / "skycraft").glob("*.json"):
        for k in json.loads(f.read_text(encoding="utf-8")):
            m = re.fullmatch(r"item\.skycraft\.(skill_book_[a-z_]+)", k)
            if m:
                out.add(SK + m.group(1))
    return sorted(out)


def regional_tags():
    for key, values in REGIONAL_ING.items():
        tag(f"loot/ingredients/{key}", values)
    for kind, by_key in FORAGE.items():
        for key, values in by_key.items():
            tag(f"forage/{kind}/{key}", values)


# ===================================================================================== soul tiers

SOULS = {
    "petty": ["skeever", "mudcrab", "deer", "slaughterfish", "cliff_racer", "hackwing", "alfiq", "wisp", "imp",
              "torchbug", "butterfly", "moth", "dragonfly", "riekling", "elk", "nix_hound", "dwarven_spider",
              "wolf", "minecraft:chicken", "minecraft:rabbit", "minecraft:fox", "minecraft:cod", "minecraft:salmon"],
    "lesser": ["frostbite_spider", "bear", "horker", "sabre_cat", "chaurus", "ice_wraith", "spriggan", "guar",
               "kagouti", "alit", "harpy", "durzog", "death_hound", "scamp", "will_o_wisp", "senche_tiger",
               "realm_stalker", "realm_grazer", "minecraft:wolf", "minecraft:polar_bear", "minecraft:spider",
               "minecraft:zombie", "minecraft:skeleton"],
    "common": ["troll", "chaurus_hunter", "flame_atronach", "draugr", "dwarven_sphere", "gargoyle", "ogre",
               "land_dreugh", "netch", "echatere", "kamal", "ash_spawn", "giant_scorpion", "dwarven_ballista",
               "realm_wyrm", "hagraven"],
    "greater": ["frost_atronach", "storm_atronach", "minotaur", "wamasu", "sand_serpent", "indrik", "dremora",
                "wispmother", "mammoth", "draugr_deathlord", "silt_strider", "tsaesci"],
    "grand": ["dragon_priest", "dragon", "giant", "frost_giant", "sea_serpent", "swamp_leviathan", "dwarven_centurion",
              "minecraft:ender_dragon", "minecraft:wither", "minecraft:warden"],
}


def soul_tags():
    for size, mobs in SOULS.items():
        vals = []
        for m in mobs:
            vals.append(m if m.startswith("minecraft:") else {"id": SK + m, "required": False})
        write(DATA / "tags" / "entity_types" / "souls" / f"{size}.json", {"replace": False, "values": vals})


# ===================================================================================== loot tables

def item(name, weight=1, functions=None, conditions=None):
    e = {"type": "minecraft:item", "name": name, "weight": weight}
    if functions:
        e["functions"] = functions
    if conditions:
        e["conditions"] = conditions
    return e


def count(lo, hi):
    return {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}


def chance(c):
    return {"condition": "minecraft:random_chance", "chance": c}


def chance_looting(c, mult=0.02):
    return {"condition": "minecraft:random_chance_with_looting", "chance": c, "looting_multiplier": mult}


def pool(entries, rolls=1, conditions=None):
    p = {"rolls": rolls, "entries": entries}
    if conditions:
        p["conditions"] = conditions
    return p


def regional(base, lo=1, hi=1, weight=1):
    return item("minecraft:sugar", weight, [{"function": SK + "regional_tag", "tag": SK + base}, count(lo, hi)])


def gear(kind, weight=1, enchant=0.1, quality=0.15):
    f = {"function": SK + "regional_gear", "kind": kind}
    if enchant:
        f["enchant_chance"] = enchant
    if quality:
        f["quality_chance"] = quality
    return item("minecraft:iron_sword", weight, [f])


def leveled(kind, weight=1, enchant=0.1, quality=0.15):
    f = {"function": SK + "leveled_gear", "kind": kind}
    if enchant:
        f["enchant_chance"] = enchant
    if quality:
        f["quality_chance"] = quality
    return item("minecraft:iron_sword" if kind != "arrow" else "minecraft:arrow", weight,
                [f] if kind != "arrow" else [count(6, 14), f])


def tome(weight=1, schools=None):
    f = {"function": SK + "leveled_tome"}
    if schools:
        f["schools"] = schools
    return item("minecraft:book", weight, [f])


def potion(weight=1, poison=False, lo=1, hi=1):
    return item("minecraft:potion", weight, [{"function": SK + "skyrim_potion", "poison": poison}, count(lo, hi)])


def gold(lo, hi):
    return item(SK + "septim", 1, [count(lo, hi)])


def table(kind, pools, seq):
    return {"type": "minecraft:" + kind, "pools": pools, "random_sequence": SK + seq}


def loot(path, obj):
    write(LOOT / (path + ".json"), obj)


def edit_loot(path, fn):
    p = LOOT / (path + ".json")
    data = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {"type": "minecraft:entity", "pools": []}
    fn(data)
    write(p, data)


def has_item(data, name):
    return name in json.dumps(data)


def add_pool_once(path, marker, new_pool):
    def fn(data):
        if not has_item(data, marker):
            data.setdefault("pools", []).append(new_pool)
    edit_loot(path, fn)


def drop_pools_with(data, *names):
    data["pools"] = [p for p in data.get("pools", []) if not any(n in json.dumps(p) for n in names)]


GEMS_SMALL = [item(SK + "garnet", 4), item(SK + "ruby", 3), item(SK + "sapphire", 2), item("minecraft:amethyst_shard", 3),
              item(SK + "small_pearl", 2), item(SK + "flawless_garnet", 1)]


def chest_tables():
    loot("chests/alchemy_ingredients", table("chest", [
        pool([regional("loot/ingredients", 1, 3, 85), item(SK + "nirnroot", 3), item("minecraft:glass_bottle", 8, [count(1, 3)]),
              item(SK + "salt_pile", 10, [count(1, 4)])], {"type": "minecraft:uniform", "min": 1, "max": 3})],
        "chests/alchemy_ingredients"))
    loot("chests/spell_tomes", table("chest", [pool([tome()])], "chests/spell_tomes"))
    loot("chests/skyrim_gear", table("chest", [
        pool([leveled("weapon", 40), leveled("armor", 35), leveled("bow", 10), leveled("shield", 10), leveled("arrow", 5)])],
        "chests/skyrim_gear"))

    # the dungeon chests (boss, common, minor), the bandit camp and every other container now come from
    # tools/loot_data.py (skycraft:containers/*; the old ids are aliases)


def entity_tables():
    # people (bandits, draugr, mages, vampires...) roll their corpse rank table from tools/loot_data.py
    # (skycraft:containers/corpse/*) plus their own parts, which that script keeps

    # creature parts that are Skyrim ingredients
    add_pool_once("entities/frostbite_spider", SK + "spider_egg", pool([item(SK + "spider_egg")], conditions=[chance_looting(0.35, 0.05)]))
    add_pool_once("entities/sabre_cat", SK + "eye_of_sabre_cat", pool([item(SK + "eye_of_sabre_cat")], conditions=[chance_looting(0.5, 0.05)]))
    add_pool_once("entities/wispmother", SK + "wisp_wrappings", pool([item(SK + "wisp_wrappings", 1, [count(1, 2)])]))
    add_pool_once("entities/ash_spawn", SK + "spawn_ash", pool([item(SK + "spawn_ash")], conditions=[chance_looting(0.6, 0.05)]))
    add_pool_once("entities/spriggan", SK + "burnt_spriggan_wood", pool([item(SK + "burnt_spriggan_wood")], conditions=[chance_looting(0.1, 0.02)]))
    add_pool_once("entities/hackwing", SK + "hawk_beak", pool([item(SK + "hawk_beak")], conditions=[chance_looting(0.4, 0.05)]))
    add_pool_once("entities/chaurus_hunter", SK + "chaurus_hunter_antennae", pool([item(SK + "chaurus_hunter_antennae")], conditions=[chance_looting(0.6, 0.05)]))
    add_pool_once("entities/riekling", SK + "boar_tusk", pool([item(SK + "boar_tusk")], conditions=[chance_looting(0.25, 0.05)]))
    for d in ("dwarven_sphere", "dwarven_spider", "dwarven_centurion", "dwarven_ballista"):
        add_pool_once(f"entities/{d}", SK + "dwarven_oil", pool([item(SK + "dwarven_oil", 1, [count(1, 2)])], conditions=[chance_looting(0.5, 0.05)]))
    add_pool_once("entities/mudcrab", SK + "small_pearl", pool([item(SK + "small_pearl")], conditions=[chance_looting(0.03, 0.01)]))
    add_pool_once("entities/slaughterfish", SK + "nordic_barnacle", pool([item(SK + "nordic_barnacle")], conditions=[chance_looting(0.1, 0.02)]))


def flora_tables():
    def no_shears():
        return {"condition": "minecraft:inverted", "term": {"condition": "minecraft:match_tool",
                                                           "predicate": {"items": ["minecraft:shears"]}}}

    def set_extra(block, entries, c):
        def fn(data):
            data["pools"] = [p for p in data["pools"] if "skycraft:forage" not in json.dumps(p.get("conditions", []))
                             and not any(e.get("name") in [x["name"] for x in entries] for e in p.get("entries", []))]
            data["pools"].append(pool(entries, conditions=[chance(c), no_shears()]))
        edit_loot("blocks/" + block, fn)

    set_extra("ash_grass", [item(SK + "ashen_grass_pod", 3), item(SK + "ash_yam", 1)], 0.25)
    set_extra("swamp_reeds", [item(SK + "marshmerrow", 3), item(SK + "swamp_fungal_pod", 1)], 0.3)
    set_extra("hist_leaves", [item(SK + "hist_sap")], 0.04)
    set_extra("parasol_cap", [item(SK + "emperor_parasol_moss")], 0.35)
    set_extra("telvanni_cap", [item(SK + "hypha_facia")], 0.35)
    set_extra("glowing_mushroom_cap", [item(SK + "glowing_mushroom", 1, [count(1, 2)])], 0.5)


def forage_modifiers():
    """Global loot modifiers: wild herbs from grass and ferns, fungi from mushrooms, eggs from tree crowns,
    moon sugar from Elsweyr's cane fields, trama root from the ashlands' dead shrubs, saltrice from Morrowind's
    fields, hanging moss from moss carpets."""
    gl = MAIN / "resources" / "data" / "skycraft" / "loot_modifiers"
    entries = []

    def modifier(name, tables, table_id, chance_, extra_conditions=()):
        conds = [{"condition": "minecraft:any_of", "terms": [{"condition": "forge:loot_table_id", "loot_table_id": t}
                                                            for t in tables]}]
        conds += list(extra_conditions)
        write(gl / f"{name}.json", {"type": SK + "add_table", "conditions": conds, "table": SK + table_id,
                                    "prefix": "blocks/", "chance": chance_})
        entries.append(SK + name)

    not_shears = {"condition": "minecraft:inverted", "term": {"condition": "minecraft:match_tool",
                                                              "predicate": {"items": ["minecraft:shears"]}}}
    modifier("forage_plants", ["minecraft:blocks/grass", "minecraft:blocks/tall_grass", "minecraft:blocks/fern",
                               "minecraft:blocks/large_fern", "minecraft:blocks/sweet_berry_bush"],
             "forage/plants", 0.04, [not_shears])
    modifier("forage_fungi", ["minecraft:blocks/brown_mushroom", "minecraft:blocks/red_mushroom"], "forage/fungi", 0.3)
    modifier("forage_nests", ["minecraft:blocks/spruce_leaves", "minecraft:blocks/oak_leaves",
                              "minecraft:blocks/birch_leaves", "minecraft:blocks/dark_oak_leaves",
                              "skycraft:blocks/snowy_spruce_leaves", "skycraft:blocks/golden_aspen_leaves",
                              "skycraft:blocks/orange_aspen_leaves"], "forage/nests", 0.012, [not_shears])
    modifier("forage_moss", ["minecraft:blocks/moss_carpet", "minecraft:blocks/hanging_roots"], "forage/hanging_moss", 0.2)
    modifier("forage_moon_sugar", ["minecraft:blocks/sugar_cane"], "forage/moon_sugar", 0.35,
             [{"condition": SK + "region", "provinces": ["elsweyr"]}])
    modifier("forage_trama_root", ["minecraft:blocks/dead_bush"], "forage/trama_root", 0.4,
             [{"condition": SK + "region", "provinces": ["morrowind"]}])
    modifier("forage_saltrice", ["minecraft:blocks/wheat"], "forage/saltrice", 1.0,
             [{"condition": SK + "region", "provinces": ["morrowind"]},
              {"condition": "minecraft:block_state_property", "block": "minecraft:wheat", "properties": {"age": "7"}}])

    def regional_pool(base):
        return table("block", [pool([regional(base)])], base)

    loot("forage/plants", regional_pool("forage/plants"))
    loot("forage/fungi", regional_pool("forage/fungi"))
    loot("forage/nests", regional_pool("forage/nests"))
    loot("forage/hanging_moss", table("block", [pool([item(SK + "hanging_moss")])], "forage/hanging_moss"))
    loot("forage/moon_sugar", table("block", [pool([item(SK + "moon_sugar", 1, [count(1, 2)])])], "forage/moon_sugar"))
    loot("forage/trama_root", table("block", [pool([item(SK + "trama_root", 1, [count(1, 2)])])], "forage/trama_root"))
    loot("forage/saltrice", table("block", [pool([item(SK + "saltrice", 1, [count(1, 3)])])], "forage/saltrice"))

    glm = MAIN / "resources" / "data" / "forge" / "loot_modifiers" / "global_loot_modifiers.json"
    data = json.loads(glm.read_text(encoding="utf-8"))
    for e in entries:
        if e not in data["entries"]:
            data["entries"].append(e)
    write(glm, data)


# ===================================================================================== values & weights

# the other workstreams' items that had no price or weight yet (Skyrim's figures where the item exists there)
CREATURE_VALUES = {
    "chaurus_eggs": 10, "frostbite_venom": 10, "spriggan_sap": 15, "hagraven_feathers": 20, "hagraven_claw": 20,
    "ice_wraith_teeth": 30, "ectoplasm": 25, "glow_dust": 20, "netch_jelly": 20, "guar_hide": 6, "scamp_skin": 10,
    "cliff_racer_plumes": 4, "kagouti_hide": 8, "alit_hide": 8, "ash_salts": 25, "harpy_feather": 6,
    "scorpion_stinger": 12, "sea_serpent_scale": 40, "kamal_horn": 25, "tsaesci_scale": 30, "indrik_feather": 50,
    "minotaur_horn": 30, "ogre_tooth": 18, "dreugh_wax": 25, "leviathan_tooth": 45, "wamasu_scale": 30,
    "gargoyle_shard": 20, "nix_hound_meat": 3, "wolf_pelt": 15, "echatere_hide": 25, "durzog_hide": 12,
    "dwemer_gear": 25,
}
CREATURE_WEIGHTS = {"wolf_pelt": 2, "echatere_hide": 4, "durzog_hide": 3, "dwemer_gear": 4, "guar_hide": 2,
                    "kagouti_hide": 2, "alit_hide": 2}
MASKS = ["morokei", "rahgot", "vokun", "volsung", "nahkriin", "otar", "krosis", "hevnoraak", "konahrik"]
CLAWS = {"iron": 75, "ivory": 100, "coral": 100, "golden": 100, "glass": 125, "ruby": 150, "sapphire": 150,
         "emerald": 175, "diamond": 200, "ebony": 200}
ORES = {"corundum": 5, "orichalcum": 8, "malachite": 10, "moonstone": 10, "quicksilver": 10, "silver": 12, "ebony": 25}
STANDING_STONES = ["allmaker_beast", "allmaker_earth", "allmaker_sun", "allmaker_tree", "allmaker_water",
                   "allmaker_wind", "apprentice", "atronach", "lady", "lord", "lover", "mage", "ritual", "serpent",
                   "shadow", "steed", "thief", "tower", "warrior"]
# Nirn's biome blocks: building stuff is worth next to nothing, crystal a little
BIOME_BLOCKS = {
    "ash_block": (0, 1), "ash_layer": (0, 0.2), "ash_grass": (0, 0.1), "volcanic_rock": (0, 2),
    "cracked_volcanic_rock": (0, 2), "magma_volcanic_rock": (1, 2), "coral_stone": (1, 2), "crystal_block": (8, 2),
    "crystal_cluster": (5, 0.5), "dune_sand": (0, 1), "frosted_stone": (0, 2), "swamp_reeds": (0, 0.1),
    "glowing_mushroom_cap": (1, 1), "parasol_cap": (0, 1), "parasol_stem": (0, 1.5), "telvanni_cap": (0, 1),
    "golden_aspen_leaves": (0, 0.2), "orange_aspen_leaves": (0, 0.2), "snowy_spruce_leaves": (0, 0.2),
    "graht_oak_leaves": (0, 0.2), "palm_fronds": (0, 0.2), "hist_leaves": (0, 0.2), "hist_sap_block": (5, 2),
}
STATION_WEIGHTS = {"alchemy_lab": 30, "arcane_enchanter": 40, "armor_workbench": 40, "blacksmith_forge": 60,
                   "drafting_table": 20, "grindstone_wheel": 30, "smelter": 60, "spellmaking_altar": 50,
                   "tanning_rack": 20, "word_wall": 100}
JEWELRY_WEIGHTS = {"copper_circlet": 2, "silver_circlet": 2, "gold_circlet": 2, "jade_circlet": 2,
                   "amulet_of_mara": 1, "amulet_of_talos": 1, "jade_pendant": 0.5, "sapphire_pendant": 0.5}


def values_weights():
    write(DATA / "skycraft_values" / "alchemy.json", {"values": {SK + k: v for k, v in sorted(NEW_VALUES.items())}})
    write(DATA / "skycraft_weights" / "alchemy.json", {"weights": {SK + k: v for k, v in sorted(NEW_WEIGHTS.items())}})
    values, weights = {}, {}
    values.update({SK + k: v for k, v in CREATURE_VALUES.items()})
    weights.update({SK + k: v for k, v in CREATURE_WEIGHTS.items()})
    for m in MASKS:
        values[SK + "mask_" + m] = 3000 if m == "konahrik" else 1500
        weights[SK + "mask_" + m] = 5
    for c, v in CLAWS.items():
        values[f"{SK}{c}_claw"] = v
        weights[f"{SK}{c}_claw"] = 1
    for o, v in ORES.items():
        values[f"{SK}{o}_ore"] = v
        values[f"{SK}deepslate_{o}_ore"] = v
    for s in STANDING_STONES:
        values[f"{SK}standing_stone_{s}"] = 0
        weights[f"{SK}standing_stone_{s}"] = 100
    for b, (v, w) in BIOME_BLOCKS.items():
        values[SK + b] = v
        weights[SK + b] = w
    weights.update({SK + k: v for k, v in STATION_WEIGHTS.items()})
    weights.update({SK + k: v for k, v in JEWELRY_WEIGHTS.items()})
    values.update({"#skycraft:merchant/skill_books": 50, SK + "septim": 1, SK + "coin_purse": 0, SK + "jail_key": 0,
                   SK + "quest_relic": 50})
    # other modules' items the item audit found without data: keys, boats, deposits
    values.update({SK + "house_key": 0, SK + "rowboat": 60, SK + "longship": 1500, SK + "cog": 2500, SK + "shipwright_bench": 150,
                   SK + "clay_deposit": 0, SK + "stone_deposit": 0})
    weights.update({SK + "house_key": 0, SK + "rowboat": 30, SK + "longship": 80, SK + "cog": 100, SK + "shipwright_bench": 30,
                    SK + "clay_deposit": 5, SK + "stone_deposit": 5})
    weights.update({"#skycraft:merchant/skill_books": 1, SK + "jail_key": 0, SK + "quest_relic": 1,
                    SK + "bound_sword": 0, SK + "bound_dagger": 0, SK + "bound_battleaxe": 0, SK + "bound_bow": 0})
    write(DATA / "skycraft_values" / "economy.json", {"values": dict(sorted(values.items()))})
    write(DATA / "skycraft_weights" / "economy.json", {"weights": dict(sorted(weights.items()))})


def main():
    merchant_tags()
    regional_tags()
    soul_tags()
    chest_tables()
    entity_tables()
    flora_tables()
    forage_modifiers()
    values_weights()
    print("economy data written")


if __name__ == "__main__":
    main()
