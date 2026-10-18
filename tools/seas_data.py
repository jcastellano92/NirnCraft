"""Data files of the seas module (com.skycraft.seas), generated so the many loot tables stay consistent.

    py -3 tools/seas_data.py

Writes under mod/src/main/resources:
  data/skycraft/loot_tables/chests/seas/*.json   site and ship cargo loot
  data/skycraft/loot_tables/entities/<creature>.json
  data/skycraft/loot_tables/blocks/kollop_bed.json
  data/skycraft/worldgen/{configured,placed}_feature/{sea_site,sea_garden,islet,coast_site}.json
  data/skycraft/forge/biome_modifier/seas_*.json
  data/skycraft/tags/entity_types/sea_bosses.json
  assets/skycraft/{blockstates,models}/... for the kollop bed and the lair marker, spawn egg item models
Loot uses the shared chest tables of the base (skycraft:chests/gold, soul_gems, dwemer_scrap, lore_books...); there
is no skycraft:containers/* family in this base yet.
"""
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mod", "src", "main", "resources")
DATA = os.path.join(ROOT, "data", "skycraft")
ASSETS = os.path.join(ROOT, "assets", "skycraft")


def write(path, obj):
    import importlib.util
    from pathlib import Path
    converter = Path(__file__).resolve().parents[0] / "worldgen" / "format_263.py"
    spec = importlib.util.spec_from_file_location("skycraft_worldgen_263", converter)
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    path, obj = native.prepare(path, obj)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


# ------------------------------------------------------------------------------------------------ loot helpers

def item(name, weight=1, lo=1, hi=1, nbt=None):
    e = {"type": "minecraft:item", "name": name, "weight": weight}
    fns = []
    if hi > 1 or lo != 1:
        fns.append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}})
    if nbt:
        fns.append({"function": "minecraft:set_nbt", "tag": nbt})
    if fns:
        e["functions"] = fns
    return e


def table(name, weight=1):
    return {"type": "minecraft:loot_table", "name": name, "weight": weight}


def gold(lo, hi, weight=10):
    return item("skycraft:septim", weight, lo, hi)


def book(book_id, value=10):
    return item("skycraft:book", 1, nbt='{book:"%s",skycraft_value:%d}' % (book_id, value))


def pool(entries, lo=1, hi=None, chance=None):
    p = {"rolls": lo if hi is None else {"type": "minecraft:uniform", "min": lo, "max": hi}, "entries": entries}
    if chance is not None:
        p["conditions"] = [{"condition": "minecraft:random_chance", "chance": chance}]
    return p


def chest(name, pools):
    write(os.path.join(DATA, "loot_table", "chests", "seas", name + ".json"), {"type": "minecraft:chest", "pools": pools})


def entity(name, pools):
    write(os.path.join(DATA, "loot_table", "entities", name + ".json"), {"type": "minecraft:entity", "pools": pools})


GEMS = [item("skycraft:garnet", 6), item("skycraft:ruby", 4), item("skycraft:sapphire", 3), item("skycraft:flawless_ruby", 1),
        item("skycraft:flawless_diamond", 1)]


def loot_tables():
    chest("yokudan_relics", [
        pool([gold(30, 120, 10), item("skycraft:coin_purse", 4, nbt="{gold:250L}"), *GEMS, item("minecraft:gold_ingot", 4, 1, 3)], 2, 4),
        pool([table("skycraft:leveled/weapon", 3), table("skycraft:chests/lore_books", 2)], 1),
        pool([book("seas_drowned_yokuda", 40)], 1, chance=0.7),
        pool([item("skycraft:pearl", 2), item("skycraft:small_pearl", 5, 1, 3)], 1, 2)])
    chest("sload_hoard", [
        pool([table("skycraft:chests/soul_gems", 5), item("skycraft:soul_gem_black", 1), item("skycraft:soul_gem_grand", 1)], 1, 3),
        pool([item("skycraft:thras_coralfish", 3, 1, 3), item("skycraft:coral_claw", 3, 1, 2), item("minecraft:slime_ball", 4, 2, 6),
              item("skycraft:void_salts", 2), item("skycraft:pearl", 2)], 2, 4),
        pool([table("skycraft:chests/spell_tomes", 1), gold(40, 160, 3)], 1),
        pool([book("seas_sload_tablet", 25)], 1, chance=0.8)])
    chest("dreugh_lair", [
        pool([item("skycraft:dreugh_wax", 8, 1, 4), item("skycraft:small_pearl", 6, 1, 3), item("skycraft:pearl", 2),
              item("skycraft:nordic_barnacle", 3, 1, 3), item("skycraft:mudcrab_chitin", 2, 1, 2)], 2, 4),
        pool([table("skycraft:chests/sea_wreck", 3), gold(10, 50, 3)], 1),
        pool([book("seas_dreugh_wax", 20), book("seas_pearl_diver", 5)], 1, chance=0.5)])
    chest("dreugh_queen_hoard", [
        pool([item("skycraft:dreugh_wax", 6, 3, 8), item("skycraft:pearl", 5, 1, 3), item("skycraft:small_pearl", 4, 2, 5), *GEMS], 3, 6),
        pool([table("skycraft:chests/sea_wreck", 2), table("skycraft:leveled/armor", 1), item("skycraft:coin_purse", 2, nbt="{gold:400L}")], 2),
        pool([book("seas_dreugh_wax", 20)], 1, chance=0.6)])
    chest("dwemer_air_chamber", [
        pool([table("skycraft:chests/dwemer_scrap", 5), item("skycraft:dwarven_metal_ingot", 3, 1, 3), item("skycraft:dwarven_oil", 2)], 2, 4),
        pool([table("skycraft:leveled/weapon", 2), table("skycraft:chests/soul_gems", 2), gold(20, 80, 2)], 1, 2),
        pool([book("seas_dwemer_sealed_hall", 30)], 1, chance=0.75)])
    chest("nordic_ship_grave", [
        pool([gold(20, 90, 8), item("minecraft:gold_ingot", 3, 1, 2), *GEMS], 2, 4),
        pool([table("skycraft:leveled/weapon", 3), table("skycraft:leveled/armor", 2)], 1, 2),
        pool([item("skycraft:nordic_barnacle", 4, 1, 4), item("minecraft:bone", 3, 1, 3)], 1),
        pool([book("seas_ship_graves", 30), book("seas_ghost_ship", 15)], 1, chance=0.6)])
    chest("slaughterfish_nest", [
        pool([item("skycraft:slaughterfish_egg", 6, 2, 5), item("skycraft:slaughterfish_scales", 4, 1, 3), item("minecraft:bone", 3, 1, 4)], 2, 3),
        pool([table("skycraft:chests/flotsam", 3), gold(5, 30, 2)], 1)])
    chest("serpent_hoard", [
        pool([item("skycraft:sea_serpent_scale", 6, 2, 5), *GEMS, item("skycraft:coin_purse", 3, nbt="{gold:400L}")], 3, 5),
        pool([table("skycraft:chests/sea_wreck", 3), table("skycraft:leveled/weapon", 2)], 2),
        pool([book("seas_serpent_warning", 5)], 1, chance=0.5)])
    chest("maormer_cargo", [
        pool([item("skycraft:maormer_serpentfish", 4, 1, 4), item("skycraft:sapphire", 3), item("skycraft:pearl", 3, 1, 2),
              item("minecraft:prismarine_shard", 3, 2, 6), gold(20, 80, 4)], 2, 4),
        pool([table("skycraft:leveled/weapon", 2), table("skycraft:chests/spell_tomes", 1)], 1),
        pool([book("seas_maormer_orders", 20)], 1, chance=0.7)])
    chest("lighthouse", [
        pool([item("minecraft:candle", 3, 1, 4), item("minecraft:torch", 4, 2, 6), item("skycraft:salt_pile", 3, 1, 3),
              item("minecraft:cod", 3, 1, 3), item("skycraft:smoked_fish", 3, 1, 2), gold(5, 25, 3)], 2, 4),
        pool([table("skycraft:chests/harbor_supplies", 2)], 1),
        pool([book("seas_keeper_log", 5)], 1, chance=0.6)])
    chest("hermit", [
        pool([table("skycraft:chests/skyrim_food", 3), table("skycraft:chests/alchemy_ingredients", 3), item("skycraft:salt_pile", 2, 1, 3)], 2, 3),
        pool([gold(3, 20, 2), table("skycraft:chests/lore_books", 1)], 1),
        pool([book("seas_hermit_diary", 5)], 1, chance=0.7)])
    chest("pirate_plunder", [
        pool([gold(15, 70, 8), item("skycraft:nord_mead", 3, 1, 2), table("skycraft:chests/skyrim_food", 3),
              table("skycraft:chests/flotsam", 3), item("skycraft:lockpick", 3, 1, 4)], 2, 4),
        pool([table("skycraft:leveled/weapon", 1)], 1, chance=0.5)])
    chest("pirate_treasure", [
        pool([gold(80, 300, 10), item("skycraft:coin_purse", 5, nbt="{gold:400L}"), *GEMS, item("minecraft:gold_ingot", 3, 2, 5)], 4, 7),
        pool([table("skycraft:leveled/weapon", 2), table("skycraft:leveled/armor", 2)], 1, 2),
        pool([book("seas_pirate_will", 10)], 1, chance=0.8)])
    chest("smuggler_cache", [
        pool([gold(10, 60, 6), table("skycraft:chests/alchemy_ingredients", 3), item("skycraft:lockpick", 3, 2, 5),
              item("skycraft:moon_sugar", 2, 1, 3), item("skycraft:skooma", 2, 1, 2), table("skycraft:chests/flotsam", 2)], 2, 4),
        pool([book("seas_smuggler_ledger", 10)], 1, chance=0.6)])
    chest("tidal_cave", [
        pool([table("skycraft:chests/flotsam", 4), table("skycraft:chests/sea_wreck", 1), item("skycraft:small_pearl", 3, 1, 2),
              item("skycraft:nordic_barnacle", 2, 1, 3)], 2, 3)])
    chest("rookery_eggs", [
        pool([item("skycraft:hawks_egg", 4, 1, 3), item("skycraft:pine_thrush_egg", 3, 1, 3), item("skycraft:rock_warbler_egg", 3, 1, 3),
              item("skycraft:hawk_feather", 4, 1, 4), item("minecraft:feather", 3, 2, 6)], 2, 4)])
    # ship holds
    chest("cargo_merchant", [
        pool([table("skycraft:chests/harbor_supplies", 4), table("skycraft:chests/skyrim_food", 3), table("skycraft:chests/smithing_materials", 2),
              gold(20, 80, 2)], 4, 7)])
    chest("cargo_navy", [
        pool([table("skycraft:leveled/weapon", 3), table("skycraft:leveled/arrows", 3), table("skycraft:chests/skyrim_food", 3),
              gold(20, 60, 2)], 3, 5)])
    chest("cargo_nord", [
        pool([item("skycraft:raw_horker_meat", 4, 1, 4), item("skycraft:horker_tusk", 2, 1, 2), table("skycraft:chests/skyrim_food", 3),
              item("skycraft:salt_pile", 2, 1, 3), table("skycraft:chests/smithing_materials", 2)], 3, 5)])
    chest("cargo_pirate", [
        pool([gold(30, 120, 6), *GEMS, table("skycraft:chests/sea_wreck", 3), table("skycraft:chests/flotsam", 3)], 4, 7),
        pool([book("seas_pirate_will", 10)], 1, chance=0.25)])
    chest("cargo_maormer", [
        pool([item("skycraft:maormer_serpentfish", 4, 1, 4), item("skycraft:sapphire", 3), item("skycraft:pearl", 3, 1, 2),
              table("skycraft:leveled/weapon", 2), gold(30, 100, 3)], 3, 6),
        pool([book("seas_maormer_orders", 20)], 1, chance=0.4)])
    chest("cargo_sload", [
        pool([table("skycraft:chests/soul_gems", 4), item("minecraft:slime_ball", 4, 2, 6), item("skycraft:void_salts", 2),
              table("skycraft:chests/spell_tomes", 1), item("minecraft:bone", 3, 2, 5)], 3, 6),
        pool([book("seas_sload_tablet", 25)], 1, chance=0.4)])
    chest("cargo_ghost", [
        pool([gold(20, 90, 6), *GEMS, table("skycraft:leveled/weapon", 2), item("skycraft:nordic_barnacle", 3, 1, 3)], 3, 6),
        pool([book("seas_ghost_ship", 15)], 1, chance=0.6)])

    # creatures
    entity("dreugh", [pool([item("skycraft:dreugh_wax", 1, 1, 2)]), pool([item("skycraft:small_pearl", 1)], chance=0.25)])
    entity("dreugh_queen", [pool([item("skycraft:dreugh_wax", 1, 4, 8)]), pool([item("skycraft:pearl", 1, 1, 3)]),
                            pool(GEMS, chance=0.6)])
    entity("sea_leviathan", [pool([item("skycraft:leviathan_tooth", 1, 2, 4)]), pool([item("skycraft:pearl", 1, 1, 2)]),
                             pool([item("skycraft:netch_jelly", 1, 2, 4)])])
    entity("giant_slaughterfish", [pool([item("skycraft:slaughterfish_scales", 1, 2, 3)]),
                                   pool([item("skycraft:slaughterfish_egg", 1, 1, 2)], chance=0.5), pool([item("minecraft:cod", 1, 1, 2)])])
    entity("sea_netch", [pool([item("skycraft:netch_jelly", 1, 1, 2)])])
    entity("whale", [pool([item("minecraft:bone", 1, 4, 9)]), pool([item("skycraft:hide", 1, 2, 4)]), pool([item("minecraft:cod", 1, 3, 6)])])
    entity("sea_crab", [pool([item("skycraft:mudcrab_chitin", 1, 1, 3)]), pool([item("skycraft:coral_claw", 1)], chance=0.3)])
    entity("seal", [pool([item("skycraft:hide", 1, 1, 2)]), pool([item("skycraft:raw_horker_meat", 1, 1, 2)])])
    entity("hawk", [pool([item("skycraft:hawk_feather", 1, 1, 2)]), pool([item("skycraft:hawk_beak", 1)], chance=0.6),
                    pool([item("skycraft:hawks_egg", 1)], chance=0.1)])
    entity("river_betty", [pool([item("skycraft:river_betty", 1)])])
    entity("abecean_longfin", [pool([item("skycraft:abecean_longfin", 1)])])

    write(os.path.join(DATA, "loot_table", "blocks", "kollop_bed.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:alternatives", "children": [
                {"type": "minecraft:item", "name": "skycraft:kollop_bed",
                 "conditions": [{"condition": "minecraft:match_tool", "predicate": {"enchantments": [{"enchantment": "minecraft:silk_touch", "levels": {"min": 1}}]}}]},
                {"type": "minecraft:item", "name": "skycraft:pearl", "conditions": [{"condition": "minecraft:random_chance", "chance": 0.06}]},
                {"type": "minecraft:item", "name": "skycraft:small_pearl", "conditions": [{"condition": "minecraft:random_chance", "chance": 0.3}]},
                {"type": "minecraft:item", "name": "skycraft:nordic_barnacle", "conditions": [{"condition": "minecraft:random_chance", "chance": 0.15}]},
                {"type": "minecraft:item", "name": "skycraft:kollop"}]}]}],
        "random_sequence": "skycraft:blocks/kollop_bed"})


# ------------------------------------------------------------------------------------------------ worldgen

FEATURES = {
    # id: (rarity, biome tag, step)
    "sea_site": (110, "#skycraft:tamriel/sea", "surface_structures"),
    "sea_garden": (14, "#skycraft:tamriel/sea", "vegetal_decoration"),
    "islet": (170, "#skycraft:tamriel/sea", "surface_structures"),
    "coast_site": (45, "#skycraft:tamriel/coast", "surface_structures"),
}


def worldgen():
    for fid, (rarity, biomes, step) in FEATURES.items():
        write(os.path.join(DATA, "worldgen", "configured_feature", fid + ".json"), {"type": "skycraft:" + fid, "config": {}})
        write(os.path.join(DATA, "worldgen", "placed_feature", fid + ".json"), {
            "feature": "skycraft:" + fid,
            "placement": [{"type": "minecraft:rarity_filter", "chance": rarity}, {"type": "minecraft:in_square"},
                          {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR_WG"}, {"type": "minecraft:biome"}]})
        write(os.path.join(DATA, "forge", "biome_modifier", "seas_" + fid + "s.json"),
              {"type": "forge:add_features", "biomes": biomes, "features": "skycraft:" + fid, "step": step})


# ------------------------------------------------------------------------------------------------ assets

EGGS = ["dreugh", "dreugh_queen", "sea_leviathan", "giant_slaughterfish", "sea_netch", "whale", "sea_crab", "seal", "hawk",
        "river_betty", "abecean_longfin"]


def assets():
    for e in EGGS:
        write(os.path.join(ASSETS, "models", "item", e + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    # kollop: a ribbed clam shell, two halves slightly open
    write(os.path.join(ASSETS, "models", "block", "kollop_bed.json"), {
        "parent": "minecraft:block/block",
        "textures": {"shell": "skycraft:block/kollop_bed", "particle": "skycraft:block/kollop_bed"},
        "elements": [
            {"from": [3, 0, 3], "to": [13, 2, 13], "faces": {d: {"uv": [0, 8, 10, 10] if d not in ("up", "down") else [3, 3, 13, 13], "texture": "#shell"}
                                                         for d in ("north", "south", "east", "west", "up", "down")}},
            {"from": [3.5, 2, 4], "to": [12.5, 4, 12], "rotation": {"angle": -22.5, "axis": "x", "origin": [8, 2, 12]},
             "faces": {d: {"uv": [0, 0, 9, 2] if d not in ("up", "down") else [3, 2, 12, 10], "texture": "#shell"}
                       for d in ("north", "south", "east", "west", "up", "down")}},
        ]})
    write(os.path.join(ASSETS, "blockstates", "kollop_bed.json"), {"variants": {
        "facing=%s,waterlogged=%s" % (f, w): {"model": "skycraft:block/kollop_bed", "y": rot}
        for f, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)) for w in ("true", "false")}})
    write(os.path.join(ASSETS, "models", "item", "kollop_bed.json"), {"parent": "skycraft:block/kollop_bed"})
    write(os.path.join(ASSETS, "models", "block", "lair_marker.json"), {"textures": {"particle": "minecraft:block/water_still"}})
    write(os.path.join(ASSETS, "blockstates", "lair_marker.json"), {"variants": {
        "waterlogged=true": {"model": "skycraft:block/lair_marker"}, "waterlogged=false": {"model": "skycraft:block/lair_marker"}}})


def tags():
    write(os.path.join(DATA, "tags", "entity_type", "sea_bosses.json"), {"replace": False, "values": [
        "skycraft:dreugh_queen", "skycraft:sea_leviathan", {"id": "skycraft:sea_serpent", "required": False}]})


if __name__ == "__main__":
    loot_tables()
    worldgen()
    assets()
    tags()
    print("seas data written")
