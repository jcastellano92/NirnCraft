#!/usr/bin/env python3
"""Writes Skycraft's loot economy (see docs/LOOT.md): the container taxonomy skycraft:containers/<kind>/<tier>,
the building-block tables skycraft:parts/*, one table per legendary unique (skycraft:uniques/<id>), the corpse
tables of humanoid creatures, the aliases that keep the old ids (skycraft:chests/dungeon_common, chests/village/house
...) working, the regional trinket tags and the pack's Lootr refresh config. Idempotent: re-run after editing.

    py -3 tools/loot_data.py            then   py -3 tools/loot_sim.py   to check the curves

Loot functions used (Java, com.skycraft.loot / economy / arsenal):
  skycraft:scaled_gold {min,max,growth}   skycraft:leveled_gear / regional_gear {kind, enchant_chance, quality_chance}
  skycraft:skyrim_potion, leveled_tome, regional_tag, regional_book, skill_book, regional_treasure,
  place_collectibles, random_unique, missing_collectible, unique;  condition skycraft:loot_level {min,max}
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "mod" / "src" / "main"
DATA = MAIN / "resources" / "data" / "skycraft"
LOOT = DATA / "loot_table"
TAGS = DATA / "tags" / "item"
JAVA = MAIN / "java" / "com" / "skycraft"
SK = "skycraft:"


def write(path, obj):
    import sys
    from pathlib import Path
    native_tools263 = str(Path(__file__).resolve().parents[0])
    if native_tools263 not in sys.path: sys.path.insert(0, native_tools263)
    from data_format_263 import prepare
    path, obj = prepare(path, obj)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


# ===================================================================================== JSON helpers

def uni(lo, hi):
    return lo if lo == hi else {"type": "minecraft:uniform", "min": lo, "max": hi}


def count(lo, hi):
    return {"function": "minecraft:set_count", "count": uni(lo, hi)}


def lvl(lo, hi=999):
    return {"condition": SK + "loot_level", "min": lo, "max": hi}


def chance(c):
    return {"condition": "minecraft:random_chance", "chance": c}


def item(name, weight=1, functions=None, conditions=None):
    e = {"type": "minecraft:item", "name": name if ":" in name else SK + name, "weight": weight}
    if functions:
        e["functions"] = functions
    if conditions:
        e["conditions"] = conditions
    return e


def table_ref(name, weight=1, conditions=None):
    e = {"type": "minecraft:loot_table", "name": name if ":" in name else SK + name, "weight": weight}
    if conditions:
        e["conditions"] = conditions
    return e


def empty(weight):
    return {"type": "minecraft:empty", "weight": weight}


def pool(entries, rolls=1, conditions=None):
    p = {"rolls": rolls if not isinstance(rolls, tuple) else uni(*rolls), "entries": entries}
    if conditions:
        p["conditions"] = conditions
    return p


def table(path, pools, kind="chest"):
    return {"type": "minecraft:" + kind, "pools": pools, "random_sequence": SK + path}


def loot(path, pools, kind="chest"):
    write(LOOT / (path + ".json"), table(path, pools, kind))


# --- entry builders (each returns one weighted entry)

def gold(lo, hi, weight=1, growth=0.04, conditions=None):
    """Septims (a coin purse above 64) scaled by the loot level: x(1 + growth*(L-1)), more in dangerous regions."""
    return item("septim", weight, [{"function": SK + "scaled_gold", "min": lo, "max": hi, "growth": growth}], conditions)


def gear(kind, weight=1, enchant=0.1, quality=0.15, regional=True, conditions=None):
    fn = {"function": SK + ("regional_gear" if regional and kind in ("weapon", "armor", "shield", "any") else "leveled_gear"),
          "kind": kind}
    if enchant:
        fn["enchant_chance"] = enchant
    if quality:
        fn["quality_chance"] = quality
    if kind == "arrow":
        return item("minecraft:arrow", weight, [count(6, 14), fn], conditions)
    return item("minecraft:iron_sword", weight, [fn], conditions)


def potion(weight=1, poison=False, lo=1, hi=1):
    return item("minecraft:potion", weight, [{"function": SK + "skyrim_potion", "poison": poison}, count(lo, hi)])


def tome(weight=1, schools=None, conditions=None):
    fn = {"function": SK + "leveled_tome"}
    if schools:
        fn["schools"] = schools
    return item("minecraft:book", weight, [fn], conditions)


def ingredients(weight=1, lo=1, hi=2):
    return item("minecraft:sugar", weight, [{"function": SK + "regional_tag", "tag": SK + "loot/ingredients"}, count(lo, hi)])


def trinket(weight=1, conditions=None):
    return item("skycraft:gold_ring", weight, [{"function": SK + "regional_tag", "tag": SK + "loot/trinkets"}], conditions)


def fn_item(fn, weight=1, conditions=None, base="minecraft:stick", **args):
    f = {"function": SK + fn}
    f.update(args)
    return item(base, weight, [f], conditions)


def book(weight=1):
    return fn_item("regional_book", weight, base="skycraft:book")


def skill_book(weight=1):
    return fn_item("skill_book", weight, base="minecraft:book")


def treasure(weight=1, conditions=None):
    return fn_item("regional_treasure", weight, conditions, base="skycraft:regional_treasure")


def part(name, weight=1, conditions=None):
    return table_ref("parts/" + name, weight, conditions)


def crown_bonus(chance_=1.0):
    """The Crown of Barenziah's gift: one more gem in the hoards of whoever restored it."""
    conds = [{"condition": SK + "collection_complete", "collection": "stones_of_barenziah"}]
    if chance_ < 1:
        conds.append(chance(chance_))
    return pool([part("gems")], conditions=conds)


def place_pool():
    """A collectible bound to the named place the container stands in (Stones of Barenziah, Black Books...)."""
    return pool([fn_item("place_collectibles")])


# ===================================================================================== parts

def parts():
    loot("parts/gems", [pool([
        item("minecraft:amethyst_shard", 30), item("garnet", 26), item("small_pearl", 10), item("ruby", 14, conditions=[lvl(6)]),
        item("sapphire", 9, conditions=[lvl(12)]), item("minecraft:emerald", 7, conditions=[lvl(9)]),
        item("minecraft:diamond", 4, conditions=[lvl(18)]), item("flawless_garnet", 6, conditions=[lvl(9)]),
        item("flawless_ruby", 4, conditions=[lvl(18)]), item("flawless_sapphire", 3, conditions=[lvl(22)]),
        item("flawless_diamond", 1, conditions=[lvl(27)])])])
    loot("parts/soul_gems", [pool([
        item("soul_gem_petty", 30), item("soul_gem_petty", 10, [{"function": "minecraft:set_nbt", "tag": "{soul:1,skycraft_value:30}"}]),
        item("soul_gem_lesser", 22), item("soul_gem_lesser", 7, [{"function": "minecraft:set_nbt", "tag": "{soul:2,skycraft_value:75}"}]),
        item("soul_gem_common", 14, conditions=[lvl(6)]),
        item("soul_gem_common", 4, [{"function": "minecraft:set_nbt", "tag": "{soul:3,skycraft_value:150}"}], [lvl(6)]),
        item("soul_gem_greater", 7, conditions=[lvl(18)]),
        item("soul_gem_greater", 2, [{"function": "minecraft:set_nbt", "tag": "{soul:4,skycraft_value:300}"}], [lvl(18)]),
        item("soul_gem_grand", 3, conditions=[lvl(30)]),
        item("soul_gem_grand", 1, [{"function": "minecraft:set_nbt", "tag": "{soul:5,skycraft_value:600}"}], [lvl(36)])])])
    loot("parts/potions", [pool([potion(5), potion(1, True)])])
    loot("parts/food", [pool([table_ref("chests/skyrim_food")])])
    loot("parts/lore_note", [pool([table_ref("chests/lore_notes")])])
    loot("parts/lockpicks", [pool([item("lockpick", 1, [count(1, 3)])])])
    loot("parts/smithing", [pool([table_ref("chests/smithing_materials")])])
    loot("parts/staff", [pool([table_ref("arsenal/staves")])])
    loot("parts/clutter", [pool([
        item("minecraft:candle", 10, [count(1, 3)]), item("minecraft:string", 6, [count(1, 3)]), item("minecraft:leather", 6, [count(1, 2)]),
        item("minecraft:paper", 5, [count(1, 4)]), item("minecraft:white_wool", 4, [count(1, 2)]), item("minecraft:bowl", 4, [count(1, 2)]),
        item("minecraft:glass_bottle", 4, [count(1, 2)]), item("minecraft:feather", 3, [count(1, 3)]), item("minecraft:bone", 2)])])
    # gear of a chest: weapons, armour, bows, arrows, shields of the region, leveled
    for name, enchant, quality in (("gear_common", 0.08, 0.15), ("gear_boss", 0.25, 0.35), ("gear_rich", 0.2, 0.25)):
        loot("parts/" + name, [pool([gear("weapon", 34, enchant, quality), gear("armor", 34, enchant, quality),
                                     gear("bow", 10, enchant, quality, regional=False), gear("shield", 8, enchant, quality),
                                     gear("arrow", 14, 0, 0, regional=False)])])


# ===================================================================================== dungeons

def dungeons():
    loot("containers/dungeon/minor", [
        pool([gold(2, 8, 50), empty(50)]),
        pool([ingredients(30, 1, 2), part("potions", 12), part("lockpicks", 7), gear("arrow", 9, 0, 0, regional=False),
              part("gems", 4), part("soul_gems", 4), part("food", 10), part("lore_note", 4), empty(20)])])
    loot("containers/dungeon/standard", [
        pool([gold(5, 16, growth=0.05)]),
        pool([part("gear_common", 70), empty(30)]),
        pool([part("soul_gems", 12), ingredients(18, 1, 3), part("lockpicks", 8), part("gems", 10), tome(5), part("potions", 14),
              book(5), trinket(4), skill_book(2), part("food", 6), empty(16)], (1, 2)),
        pool([treasure(3, [lvl(12)]), empty(97)]),
        crown_bonus(0.3),
        place_pool()])
    loot("containers/dungeon/boss", [
        pool([gold(20, 50, growth=0.06)]),
        pool([part("gear_boss", 1)], (1, 2)),
        pool([part("gems", 1)]),
        pool([part("gems", 1)], conditions=[lvl(6)]),
        pool([part("soul_gems", 1)]),
        pool([part("potions", 1)], (1, 2)),
        pool([tome(12), skill_book(8), part("staff", 5, [lvl(10)]), trinket(10), treasure(12, [lvl(8)]),
              fn_item("random_unique", 3, [lvl(12)]),
              fn_item("missing_collectible", 1, [lvl(18)], collection="artifacts_of_legend", province=True),
              empty(49)]),
        pool([book(30), empty(70)]),
        crown_bonus(),
        place_pool(), place_pool()])
    # culture themes: extra containers of a site's people (the dungeon builders pick them by theme)
    themes = {
        "nordic": [pool([gold(5, 20, 60), empty(40)]), pool([item("bone_meal_draugr", 3, [count(1, 2)]), gear("weapon", 2, 0.08, 0.1),
                                                              part("gems", 2), item("minecraft:bone", 3, [count(1, 3)]), empty(4)])],
        "dwemer": [pool([item("dwarven_scrap", 6, [count(1, 3)]), item("dwarven_metal_ingot", 3, [count(1, 2)]),
                         item("dwarven_oil", 3), item("minecraft:copper_ingot", 2, [count(1, 3)]), item("minecraft:gold_nugget", 2, [count(2, 6)]),
                         item("minecraft:amethyst_shard", 1)], (1, 3)),
                   pool([gear("weapon", 2, 0.1, 0.15, regional=False), gear("bow", 1, 0.1, 0.1, regional=False), empty(12)])],
        "falmer": [pool([item("chaurus_chitin", 3, [count(1, 2)]), item("chaurus_eggs", 3, [count(1, 3)]), item("glowing_mushroom", 3, [count(1, 2)]),
                         item("refined_moonstone", 1), item("falmer_ear", 1)], (1, 3)), pool([part("gems", 1), empty(5)])],
        "ayleid": [pool([gold(8, 25)]), pool([part("gems", 3), part("soul_gems", 3), item("refined_moonstone", 2), tome(1), empty(4)]),
                   pool([treasure(4), empty(96)])],
        "velothi": [pool([gold(6, 20)]), pool([ingredients(4, 1, 3), item("minecraft:bone", 2, [count(1, 2)]), part("soul_gems", 2),
                                               part("lore_note", 1), empty(3)])],
        "daedric": [pool([item("daedra_heart", 1), item("minecraft:crying_obsidian", 2), item("minecraft:ender_pearl", 2),
                          part("soul_gems", 3), item("minecraft:candle", 3, [count(1, 3)]), empty(4)], (1, 2)),
                    pool([tome(2), empty(6)])],
        "necromancer": [pool([gold(5, 25)]), pool([item("soul_gem_black", 1), part("soul_gems", 4), tome(3, ["conjuration"]),
                                                   item("bone_meal_draugr", 3, [count(1, 3)]), item("deathbell", 2), item("nightshade", 2)], (1, 2))],
        "vampire": [pool([gold(8, 30)]), pool([item("vampire_dust", 2), part("potions", 3), part("gems", 2), part("soul_gems", 2), empty(3)], (1, 2))],
        "forsworn": [pool([gold(2, 10, 50), empty(50)]), pool([item("briar_heart", 1), item("hagraven_feathers", 2), item("hide", 3, [count(1, 2)]),
                                                                ingredients(4, 1, 2), empty(3)], (1, 2))],
    }
    for name, pools in themes.items():
        loot("containers/dungeon/theme/" + name, pools)


# ===================================================================================== households, shops

def households():
    loot("containers/household/poor", [
        pool([part("food", 1)], (1, 2)),
        pool([gold(1, 6, 50), empty(50)]),
        pool([part("clutter", 1)], (0, 2)),
        pool([part("lore_note", 15), empty(85)])])
    loot("containers/household/common", [
        pool([part("food", 1)]),
        pool([gold(3, 15, 70), empty(30)]),
        pool([part("clutter", 1)], (0, 2)),
        pool([part("potions", 15), part("lore_note", 18), trinket(4), skill_book(1), book(4), empty(58)])])
    loot("containers/household/wealthy", [
        pool([gold(10, 40)]),
        pool([part("food", 1)], (1, 2)),
        pool([trinket(25), part("gems", 15), part("soul_gems", 10), part("potions", 25), gear("weapon", 6, 0.1, 0.15),
              gear("armor", 6, 0.1, 0.15), skill_book(3), empty(10)], (1, 2)),
        pool([book(40), empty(60)])])
    loot("containers/household/noble", [
        pool([gold(30, 90, growth=0.05)]),
        pool([trinket(30), part("gems", 25), part("soul_gems", 10), part("gear_rich", 15), tome(8), skill_book(4), part("potions", 8)], (2, 3)),
        pool([book(60), empty(40)]),
        pool([treasure(3), empty(97)])])
    stores = {
        "poor": [pool([part("food", 1)], (1, 2)), pool([ingredients(30, 1, 2), empty(70)])],
        "common": [pool([part("food", 1)], (1, 3)), pool([ingredients(35, 1, 2), item("salt_pile", 15, [count(1, 3)]), empty(50)])],
        "wealthy": [pool([part("food", 1)], (2, 3)), pool([ingredients(40, 1, 3), item("salt_pile", 15, [count(1, 3)]),
                                                         part("potions", 10), empty(35)], (1, 2))],
        "noble": [pool([part("food", 1)], (2, 4)), pool([ingredients(40, 1, 3), part("potions", 20), item("salt_pile", 10, [count(1, 3)]),
                                                        empty(30)], (1, 2))],
    }
    for tier, pools in stores.items():
        loot("containers/household_stores/" + tier, pools)

    shops = {
        "general": [pool([gold(20, 60)]), pool([part("smithing", 3), part("food", 3), ingredients(2, 1, 3), part("lockpicks", 2),
                                                 gear("weapon", 1, 0.05, 0.1), gear("armor", 1, 0.05, 0.1), part("clutter", 2)], (2, 3))],
        "blacksmith": [pool([gold(15, 45)]), pool([part("smithing", 1)], (1, 3)), pool([part("gear_common", 70), empty(30)])],
        "alchemist": [pool([gold(15, 45)]), pool([ingredients(1, 1, 4)], (2, 4)), pool([part("potions", 1)], (1, 2))],
        "inn": [pool([gold(10, 40)]), pool([part("food", 1)], (1, 3)), pool([part("lore_note", 30), book(10), empty(60)])],
        "temple": [pool([gold(10, 30)]), pool([part("potions", 4), part("soul_gems", 2), item("minecraft:candle", 3, [count(1, 4)])], (1, 2)),
                   pool([book(30), tome(10, ["restoration"]), empty(60)])],
        "farm": [pool([gold(2, 10, 60), empty(40)]), pool([part("food", 4), item("minecraft:wheat", 2, [count(2, 6)]), item("minecraft:egg", 2, [count(1, 4)]),
                                                          item("minecraft:leather", 1, [count(1, 2)])], (1, 3))],
        "mill": [pool([gold(2, 10, 60), empty(40)]), pool([item("minecraft:spruce_planks", 3, [count(4, 12)]), item("minecraft:stick", 2, [count(2, 8)]),
                                                          item("firewood", 3, [count(1, 4)]), part("food", 3)], (1, 3))],
        "mine": [pool([gold(5, 15)]), pool([table_ref("containers/site/mine")]), pool([part("food", 1)])],
        "barracks": [pool([gold(5, 20)]), pool([part("gear_common", 1)], (1, 2)), pool([gear("arrow", 3, 0, 0, regional=False), part("potions", 3), part("food", 3)])],
        "jarl": [pool([gold(60, 150, growth=0.05)]), pool([trinket(3), part("gems", 3), part("gear_rich", 2), tome(1)], (2, 3)),
                 pool([book(50), empty(50)]), pool([treasure(4), empty(96)])],
        "court_wizard": [pool([gold(20, 60)]), pool([tome(4), part("soul_gems", 4), ingredients(2, 1, 3), part("staff", 1, [lvl(10)])], (2, 3))],
    }
    for name, pools in shops.items():
        loot("containers/shop/" + name, pools)


# ===================================================================================== misc, strongboxes, locks

def misc():
    loot("containers/misc/satchel", [pool([gold(2, 15, 55), empty(45)]),
                                     pool([ingredients(4, 1, 2), part("potions", 3), part("lockpicks", 2), part("lore_note", 2), part("gems", 1), empty(3)])])
    loot("containers/misc/knapsack", [pool([gold(4, 20, 70), empty(30)]), pool([part("food", 1)], (1, 2)),
                                      pool([gear("arrow", 3, 0, 0, regional=False), part("potions", 3), part("lockpicks", 2), gear("weapon", 1, 0.05, 0.1),
                                            part("lore_note", 2), empty(4)])])
    loot("containers/misc/barrel", [pool([part("food", 1)], (1, 2)), pool([ingredients(2, 1, 2), item("salt_pile", 2, [count(1, 3)]), empty(6)])])
    loot("containers/misc/urn", [pool([gold(2, 10, 60), empty(40)]), pool([part("gems", 4), item("bone_meal_draugr", 6), ingredients(4, 1, 1),
                                                                           part("soul_gems", 2), empty(16)])])
    loot("containers/misc/sarcophagus", [pool([gold(5, 25)]), pool([gear("weapon", 3, 0.15, 0.1), gear("armor", 2, 0.15, 0.1), part("gems", 3),
                                                                    item("bone_meal_draugr", 4, [count(1, 2)]), empty(6)]),
                                         place_pool()])
    loot("containers/misc/coin_purse", [pool([gold(10, 30)])])
    for i, (tier, lo, hi) in enumerate((("novice", 10, 30), ("apprentice", 20, 50), ("adept", 40, 90), ("expert", 70, 150), ("master", 120, 240))):
        loot("containers/strongbox/" + tier, [
            pool([gold(lo, hi, growth=0.03)]),
            pool([part("gems", 1)], (0, 1) if i < 2 else (1, i)),
            pool([trinket(2 + i), part("lockpicks", 3), part("soul_gems", 1 + i), empty(8 - i)])])
    # the bonus when a lock is picked: on top of the container's own loot, small and scaled
    bonus = {"novice": (2, 8, [part("lockpicks", 6), item("garnet", 1), empty(5)]),
             "apprentice": (5, 15, [part("lockpicks", 5), item("garnet", 2), part("soul_gems", 2), empty(4)]),
             "adept": (10, 25, [part("lockpicks", 4), part("gems", 3), part("soul_gems", 3), trinket(1), empty(2)]),
             "expert": (18, 40, [part("lockpicks", 3), part("gems", 4), part("soul_gems", 3), trinket(2)]),
             "master": (30, 60, [part("lockpicks", 2), part("gems", 4), part("soul_gems", 3), trinket(2), item("flawless_diamond", 1, conditions=[lvl(27)])])}
    for tier, (lo, hi, extras) in bonus.items():
        loot("containers/lock_bonus/" + tier, [pool([gold(lo, hi, growth=0.02)]), pool(extras)])


# ===================================================================================== camps and sites

def sites():
    loot("containers/camp/bandit", [pool([gold(10, 40)]), pool([part("gear_common", 1)]),
                                    pool([item("minecraft:iron_ingot", 6, [count(1, 3)]), item("steel_ingot", 4, [count(1, 2)]), part("potions", 8),
                                          ingredients(5, 1, 2), part("lockpicks", 4), part("gems", 4), trinket(2), empty(6)], (1, 3))])
    loot("containers/camp/bandit_supplies", [pool([part("food", 1)], (1, 2)),
                                             pool([gear("arrow", 4, 0, 0, regional=False), item("minecraft:torch", 3, [count(2, 6)]), item("minecraft:leather", 2),
                                                   part("potions", 3), gold(2, 10, 3), empty(3)], (1, 2))])
    loot("containers/camp/giant", [pool([item("giants_toe", 1)], conditions=[chance(0.4)]),
                                   pool([item("minecraft:cooked_beef", 3, [count(1, 3)]), item("minecraft:leather", 3, [count(1, 3)]),
                                         item("minecraft:bone", 3, [count(2, 5)]), item("minecraft:milk_bucket", 1)], (1, 3)),
                                   pool([gold(20, 60)]), pool([part("gems", 1)], (0, 2))])
    loot("containers/camp/hunter", [pool([item("hide", 4, [count(1, 3)]), item("minecraft:leather", 3, [count(1, 2)]), item("minecraft:rabbit_hide", 2),
                                          gear("arrow", 4, 0, 0, regional=False), part("food", 3)], (1, 3)), pool([gold(2, 12, 50), empty(50)])])
    loot("containers/camp/forsworn", [pool([gold(2, 10, 50), empty(50)]),
                                      pool([item("briar_heart", 1), item("hagraven_feathers", 2), item("hide", 3, [count(1, 2)]), ingredients(4, 1, 2),
                                            item("minecraft:bone", 2)], (1, 3))])
    loot("containers/camp/smuggler", [pool([gold(15, 50)]), pool([item("moon_sugar", 3, [count(1, 3)]), item("skooma", 2), part("lockpicks", 3),
                                                                  part("gems", 2), trinket(2), part("potions", 2)], (1, 3))])
    loot("containers/site/shrine", [pool([gold(3, 15, 60), empty(40)]), pool([item("minecraft:candle", 3, [count(1, 3)]), part("potions", 3),
                                                                              part("soul_gems", 1), book(1), empty(3)])])
    loot("containers/site/daedric_shrine", [pool([gold(10, 40)]), pool([item("daedra_heart", 1), item("minecraft:crying_obsidian", 2),
                                                                        item("minecraft:ender_pearl", 2), part("soul_gems", 3), part("gems", 2),
                                                                        item("minecraft:candle", 3, [count(1, 3)])], (1, 2)),
                                            place_pool()])
    loot("containers/site/word_wall", [pool([gold(25, 70)]), pool([tome(3), part("soul_gems", 3), part("gems", 3), item("minecraft:bone", 2, [count(1, 3)])], (1, 3)),
                                       pool([part("gear_boss", 1)]), place_pool()])
    loot("containers/site/dragon_lair", [pool([table_ref("containers/dungeon/boss")]),
                                         pool([item("dragon_bone", 2, [count(1, 2)]), item("dragon_scale", 1), item("minecraft:bone", 4, [count(2, 6)])], (1, 2))])
    loot("containers/site/mine", [pool([item("raw_corundum", 4, [count(1, 3)]), item("raw_orichalcum", 3, [count(1, 3)]), item("raw_silver", 3, [count(1, 2)]),
                                        item("raw_quicksilver", 2, [count(1, 2)]), item("raw_moonstone", 2, [count(1, 2)]),
                                        item("raw_malachite", 1, [count(1, 2)], [lvl(12)]), item("raw_ebony", 1, [count(1, 1)], [lvl(27)]),
                                        item("minecraft:raw_iron", 5, [count(1, 4)]), item("minecraft:coal", 4, [count(2, 6)]),
                                        item("minecraft:torch", 2, [count(2, 6)])], (1, 3)),
                                  pool([gold(2, 10, 40), empty(60)])])
    loot("containers/site/shipwreck", [pool([gold(20, 60)]), pool([part("gems", 3), part("gear_common", 4), part("smithing", 3), part("soul_gems", 2),
                                                                   item("minecraft:nautilus_shell", 2), book(2), skill_book(1)], (2, 4)),
                                       pool([treasure(5), empty(95)])])
    loot("containers/site/flotsam", [pool([part("food", 3), ingredients(2, 1, 2), item("minecraft:string", 2, [count(1, 3)]),
                                           item("minecraft:kelp", 2, [count(1, 4)]), item("minecraft:glass_bottle", 1), gold(2, 10, 2)], (1, 3))])
    loot("containers/site/harbor", [pool([item("minecraft:cod", 3, [count(1, 3)]), item("minecraft:salmon", 3, [count(1, 3)]),
                                          item("minecraft:oak_planks", 2, [count(2, 6)]), item("minecraft:string", 2, [count(1, 3)]),
                                          item("minecraft:white_wool", 1, [count(1, 2)]), item("minecraft:paper", 1)], (1, 3)),
                                    pool([gold(3, 15, 50), empty(50)])])
    loot("containers/site/landing_camp", [pool([part("food", 1)], (1, 2)), pool([gear("arrow", 3, 0, 0, regional=False), part("lockpicks", 2),
                                                                                  item("minecraft:torch", 2, [count(2, 6)]), part("gear_common", 1)], (1, 2)),
                                          pool([gold(5, 20)])])
    loot("containers/site/alchemist_lair", [pool([ingredients(6, 1, 3), item("minecraft:glass_bottle", 3, [count(1, 3)]), part("potions", 3),
                                                  item("minecraft:spider_eye", 1), item("minecraft:feather", 1)], (2, 4))])


# ===================================================================================== corpses, rewards

CORPSES = {
    "commoner": [pool([gold(1, 8, 60), empty(40)]), pool([part("food", 3), part("lore_note", 1), trinket(1), empty(10)])],
    "bandit": [pool([gold(1, 10, 70), empty(30)]),
               pool([part("potions", 6), ingredients(4, 1, 2), part("lockpicks", 2), gear("arrow", 3, 0, 0, regional=False), part("gems", 1),
                     trinket(1), empty(8)])],
    "bandit_boss": [pool([gold(10, 35)]), pool([gear("weapon", 2, 0.2, 0.3), gear("armor", 2, 0.2, 0.3), gear("shield", 1, 0.15, 0.2)]),
                    pool([part("potions", 1)], (1, 2)), pool([part("gems", 5), trinket(2), empty(3)]), pool([part("lore_note", 3), empty(7)])],
    "soldier": [pool([gold(3, 15, 60), empty(40)]), pool([part("potions", 3), gear("arrow", 2, 0, 0, regional=False), part("food", 2), empty(5)])],
    "mage": [pool([gold(3, 20, 80), empty(20)]), pool([tome(2), part("soul_gems", 3), part("potions", 4), ingredients(4, 1, 2), empty(6)])],
    "mage_boss": [pool([gold(15, 50)]), pool([tome(1)]), pool([part("soul_gems", 1)]), pool([part("staff", 1, [lvl(10)]), part("gems", 3), empty(6)]),
                  pool([part("potions", 1)], (1, 2))],
    "draugr": [pool([gold(1, 6, 50), empty(50)]), pool([gear("arrow", 3, 0, 0, regional=False), part("gems", 1), ingredients(1, 1, 1), empty(10)])],
    "draugr_boss": [pool([gold(10, 35)]), pool([gear("weapon", 2, 0.25, 0.2, regional=False), gear("armor", 1, 0.2, 0.2, regional=False)]),
                    pool([tome(2), part("potions", 4), empty(6)]), pool([part("gems", 1)])],
    "forsworn": [pool([gold(1, 8, 50), empty(50)]), pool([ingredients(4, 1, 2), part("potions", 1), empty(5)])],
    "falmer": [pool([part("gems", 1), part("soul_gems", 1), empty(18)])],
    "vampire": [pool([gold(5, 30)]), pool([part("potions", 3), tome(1, ["destruction", "conjuration", "illusion"]), part("gems", 2),
                                           part("soul_gems", 1), empty(5)])],
    "dremora": [pool([gold(5, 25, 50), empty(50)]), pool([part("soul_gems", 2), part("gems", 1), empty(7)])],
    "giant": [pool([gold(20, 60, 70), empty(30)]), pool([part("gems", 1), empty(3)])],
    "brute": [pool([gold(3, 15, 40), empty(60)]), pool([part("gems", 1), ingredients(2, 1, 2), empty(8)])],
    "dragon_priest": [pool([gold(80, 200, growth=0.05)]), pool([tome(1)]), pool([item("soul_gem_grand", 1), part("soul_gems", 1)]),
                      pool([part("gems", 1)], (1, 2)), pool([part("staff", 1, [lvl(10)]), empty(3)])],
    "dragon": [pool([gold(60, 180, growth=0.05)]), pool([part("gems", 1)], (1, 2)), pool([part("gear_boss", 2), empty(3)])],
}

# entity table -> corpse rank
ENTITY_RANKS = {"bandit": "bandit", "bandit_chief": "bandit_boss", "draugr": "draugr", "draugr_deathlord": "draugr_boss",
                "dragon_priest": "dragon_priest", "dragon": "dragon", "dremora": "dremora", "forsworn": "forsworn", "falmer": "falmer",
                "guard": "soldier", "necromancer": "mage", "vampire": "vampire", "giant": "giant", "frost_giant": "giant",
                "minotaur": "brute", "ogre": "brute", "riekling": "brute", "tsaesci": "brute"}
# what the corpse rank table now provides: these entries leave the creature's own table
STRIP_ITEMS = {"skycraft:septim", "skycraft:coin_purse", "skycraft:garnet", "skycraft:ruby", "skycraft:sapphire", "minecraft:amethyst_shard",
               "minecraft:emerald", "skycraft:small_pearl", "skycraft:flawless_garnet", "skycraft:flawless_ruby", "skycraft:flawless_sapphire",
               "skycraft:flawless_diamond", "minecraft:diamond", "minecraft:potion", "minecraft:book"}
STRIP_FUNCTIONS = {SK + "regional_gear", SK + "leveled_gear", SK + "skyrim_potion", SK + "leveled_tome"}


def corpses():
    for rank, pools in CORPSES.items():
        loot("containers/corpse/" + rank, pools, kind="entity")
    for ent, rank in ENTITY_RANKS.items():
        p = LOOT / "entities" / (ent + ".json")
        data = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {"type": "minecraft:entity", "pools": []}
        ref = SK + "containers/corpse/" + rank
        kept = []
        for pl in data.get("pools", []):
            if any(e.get("type") == "minecraft:loot_table" and e.get("name", "").startswith(SK + "containers/corpse/") for e in pl.get("entries", [])):
                continue
            entries = []
            for e in pl.get("entries", []):
                fns = {f.get("function") for f in e.get("functions", [])}
                if e.get("name") in STRIP_ITEMS or fns & STRIP_FUNCTIONS:
                    continue
                entries.append(e)
            if any(e.get("type") != "minecraft:empty" for e in entries):
                pl["entries"] = entries
                kept.append(pl)
        data["pools"] = [pool([table_ref(ref)])] + kept
        data["random_sequence"] = SK + "entities/" + ent
        write(p, data)


def rewards():
    loot("containers/reward/minor", [pool([gold(30, 80)]), pool([part("potions", 1)], (1, 2))])
    loot("containers/reward/standard", [pool([gold(80, 200)]), pool([gear("any", 6, 1.0, 0.4), trinket(2), part("gems", 2)])])
    loot("containers/reward/major", [pool([gold(200, 500)]), pool([gear("any", 1, 1.0, 0.6)], 2), pool([part("gems", 1)], (1, 2))])
    loot("containers/reward/legendary", [pool([gold(500, 1200)]),
                                         pool([fn_item("random_unique", 1)]),
                                         pool([gear("any", 1, 1.0, 0.8)]), pool([part("gems", 1)], 2)])


def uniques():
    text = (JAVA / "loot" / "LegendaryUniques.java").read_text(encoding="utf-8")
    ids = re.findall(r'def\("([a-z0-9_]+)"', text)
    for uid in ids:
        loot("uniques/" + uid, [pool([fn_item("unique", 1, id=uid)])])
    return ids


# ===================================================================================== aliases of the old ids

ALIASES = {
    "chests/dungeon_minor": "containers/dungeon/minor", "chests/dungeon_common": "containers/dungeon/standard",
    "chests/dungeon_boss": "containers/dungeon/boss", "chests/dwemer_scrap": "containers/dungeon/theme/dwemer",
    "chests/giant_camp": "containers/camp/giant", "chests/mine_ore": "containers/site/mine",
    "chests/dragon_lair": "containers/site/dragon_lair", "chests/daedric_offerings": "containers/site/daedric_shrine",
    "chests/lair_reagents": "containers/site/alchemist_lair", "chests/word_wall": "containers/site/word_wall",
    "chests/bandit_camp": "containers/camp/bandit", "chests/bandit_camp_supplies": "containers/camp/bandit_supplies",
    "chests/sea_wreck": "containers/site/shipwreck", "chests/flotsam": "containers/site/flotsam",
    "chests/harbor_supplies": "containers/site/harbor", "chests/landing_camp": "containers/site/landing_camp",
    "chests/village/house": "containers/household/common", "chests/village/house_rich": "containers/household/wealthy",
    "chests/village/palace": "containers/shop/jarl", "chests/village/farm": "containers/shop/farm",
    "chests/village/food": "containers/household_stores/common", "chests/village/alchemist": "containers/shop/alchemist",
    "chests/village/barracks": "containers/shop/barracks", "chests/village/inn": "containers/shop/inn",
    "chests/village/mill": "containers/shop/mill", "chests/village/mine": "containers/shop/mine",
    "chests/village/smithy": "containers/shop/blacksmith", "chests/village/store": "containers/shop/general",
    "chests/village/temple": "containers/shop/temple",
}
for _t in ("novice", "apprentice", "adept", "expert", "master"):
    ALIASES["chests/lock_bonus/" + _t] = "containers/lock_bonus/" + _t


def aliases():
    for old, new in ALIASES.items():
        loot(old, [pool([table_ref(new)])])
    # building blocks the global modifiers still add to other mods' chests: scaled gold, no Daedric artifacts
    loot("chests/gold", [pool([gold(5, 40, 85), empty(15)])])
    sg = LOOT / "chests" / "soul_gems.json"
    data = json.loads(sg.read_text(encoding="utf-8"))
    data["pools"] = [p for p in data["pools"] if "azuras_star" not in json.dumps(p)]
    write(sg, data)


# ===================================================================================== regional trinkets

TRINKETS = {
    "skyrim": ["silver_ring", "gold_ring", "silver_necklace", "amulet_of_talos", "copper_circlet", "silver_garnet_ring"],
    "solstheim": ["silver_ring", "silver_necklace", "copper_circlet"],
    "cyrodiil": ["gold_ring", "gold_necklace", "amulet_of_mara", "silver_circlet", "gold_ruby_necklace"],
    "morrowind": ["silver_ring", "silver_necklace", "copper_circlet", "gold_ring"],
    "black_marsh": ["jade_pendant", "silver_ring", "copper_circlet"],
    "elsweyr": ["gold_ring", "gold_necklace", "gold_circlet", "sapphire_pendant"],
    "valenwood": ["jade_pendant", "silver_ring", "copper_circlet"],
    "summerset": ["gold_circlet", "sapphire_pendant", "gold_diamond_ring", "jade_circlet"],
    "hammerfell": ["gold_ring", "gold_necklace", "silver_circlet"],
    "high_rock": ["silver_ring", "silver_circlet", "silver_necklace", "amulet_of_mara"],
    "orsinium": ["silver_ring", "copper_circlet", "silver_necklace"],
    "akavir": ["jade_circlet", "jade_pendant"],
    "atmora": ["silver_ring", "copper_circlet"],
    "yokuda": ["gold_circlet", "gold_ring"],
    "pyandonea": ["sapphire_pendant", "silver_ring"],
    "realm": ["gold_ring", "silver_ring"],
}


def trinket_tags():
    for key, items in TRINKETS.items():
        write(TAGS / "loot" / "trinkets" / (key + ".json"),
              {"replace": False, "values": [{"id": SK + i, "required": False} for i in items]})


# ===================================================================================== Lootr

def lootr_config():
    """Lootr refreshes these tables 30 days after a player looted their copy; boss chests and uniques never."""
    refresh = []
    for f in sorted(LOOT.rglob("*.json")):
        rel = f.relative_to(LOOT).as_posix()[:-5]
        if not (rel.startswith("containers/") or rel in ALIASES):
            continue
        if rel.startswith(("containers/corpse/", "containers/reward/", "containers/lock_bonus/", "chests/lock_bonus/")):
            continue
        if any(k in rel for k in ("boss", "dragon_lair", "word_wall", "daedric_shrine", "daedric_offerings")):
            continue
        refresh.append(SK + rel)
    lines = ["# Skycraft: Skyrim's respawn rules (see docs/LOOT.md). Dungeon, wilderness, household and shop containers",
             "# refill 30 in-game days after a player looted their copy; boss chests and placed uniques never do.",
             "# Generated by tools/loot_data.py.",
             "refresh_value = 720000",
             "refresh_loot_tables = [" + ", ".join('"' + r + '"' for r in refresh) + "]",
             "disable_notifications = true", ""]
    (ROOT / "pack" / "config" / "lootr-common.toml").write_text("\n".join(lines), encoding="utf-8", newline="\n")


def main():
    parts()
    dungeons()
    households()
    misc()
    sites()
    corpses()
    rewards()
    ids = uniques()
    aliases()
    trinket_tags()
    lootr_config()
    print(f"loot data written ({len(ids)} uniques, {len(ALIASES)} aliases)")


if __name__ == "__main__":
    main()
