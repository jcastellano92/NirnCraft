"""Generates the exploration module's data: loot tables, notes, lang, uniques, worldgen JSON and a few assets.

Usage: py -3 tools/explore/gen_explore_data.py

Writes (all under mod/src/main/):
  resources/data/skycraft/loot_tables/explore/*.json        minor-site loot
  resources/data/skycraft/worldgen/{configured,placed}_feature/minor_site.json
  resources/data/skycraft/forge/biome_modifier/explore_minor_sites.json
  resources/data/skycraft/loot_modifiers/explore_skill_books.json (+ entry in forge global_loot_modifiers.json)
  resources/assets/skycraft/lore/books/explore_*.json         note texts
  resources/assets/skycraft/{blockstates,models}/...           Ayleid well and treasure map models
  resources/assets/skycraft/textures/item/treasure_map.png     procedural icon
  resources/skycraft/explore/uniques.json                      unique items and where they lie
  lang/skycraft/explore.json
Unique positions are picked from the Tamriel map assets: dry land in the named region, well away from every named
place and from each other. Rerun after the map or the places change.
"""
import json
import math
import os
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
from explore_text import MISC_LANG, NOTES, SITE_NAMES, UNIQUES  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ROOT, "mod", "src", "main")
RES = os.path.join(MAIN, "resources")
DATA = os.path.join(RES, "data", "skycraft")
ASSETS = os.path.join(RES, "assets", "skycraft")
TAMRIEL = os.path.join(RES, "skycraft", "tamriel")

MIN_X, MIN_Z, CELL = -15360, -5120, 32


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ================================================================================================ loot tables

def uni(lo, hi):
    return {"type": "minecraft:uniform", "min": lo, "max": hi}


def item(name, weight=1, count=None, fns=None):
    e = {"type": "minecraft:item", "name": name, "weight": weight}
    f = list(fns or [])
    if count is not None:
        f.append({"function": "minecraft:set_count", "count": uni(*count) if isinstance(count, tuple) else count})
    if f:
        e["functions"] = f
    return e


def table(name, weight=1):
    return {"type": "minecraft:loot_table", "name": name, "weight": weight}


def empty(weight):
    return {"type": "minecraft:empty", "weight": weight}


def gold(lo, hi, w=10):
    return item("skycraft:septim", w, (lo, hi))


def gear(kind, w=10, enchant=0.1, quality=0.2, leveled=False):
    base = {"weapon": "minecraft:iron_sword", "armor": "minecraft:iron_chestplate", "bow": "minecraft:bow",
            "shield": "minecraft:iron_sword", "any": "minecraft:iron_sword"}[kind]
    fn = {"function": "skycraft:leveled_gear" if leveled else "skycraft:regional_gear", "kind": kind,
          "enchant_chance": enchant, "quality_chance": quality}
    return item(base, w, fns=[fn])


def arrows(w=4):
    return item("minecraft:arrow", w, fns=[{"function": "minecraft:set_count", "count": uni(5, 14)},
                                          {"function": "skycraft:leveled_gear", "kind": "arrow"}])


def ingredient(w=10, lo=1, hi=3):
    return item("minecraft:sugar", w, fns=[{"function": "skycraft:regional_tag", "tag": "skycraft:loot/ingredients"},
                                          {"function": "minecraft:set_count", "count": uni(lo, hi)}])


def potion(w=4, poison=False):
    return item("minecraft:potion", w, fns=[{"function": "skycraft:skyrim_potion", "poison": poison}])


def tome(w=2, schools=None):
    fn = {"function": "skycraft:leveled_tome"}
    if schools:
        fn["schools"] = schools
    return item("minecraft:book", w, fns=[fn])


def note(book_id, w=1):
    return item("skycraft:book", w, fns=[{"function": "minecraft:set_nbt", "tag": '{book:"%s",skycraft_value:2}' % book_id}])


def lead(w=1):
    return item("skycraft:book", w, fns=[{"function": "skycraft:explore_lead"}])


def tmap(w=1):
    return item("skycraft:treasure_map", w, fns=[{"function": "skycraft:explore_treasure_map"}])


def pool(entries, rolls=1):
    return {"rolls": uni(*rolls) if isinstance(rolls, tuple) else rolls, "entries": entries}


def loot(name, *pools):
    write(os.path.join(DATA, "loot_tables", "explore", name + ".json"),
          {"type": "minecraft:chest", "pools": list(pools), "random_sequence": "skycraft:explore/" + name})


def skill_books(chance_pct):
    """A pool that adds one skill book chance_pct percent of the time."""
    return pool([table("skycraft:chests/skill_books", chance_pct), empty(100 - chance_pct)])


def maps_and_leads(map_pct, lead_pct):
    return pool([tmap(map_pct), lead(lead_pct), empty(100 - map_pct - lead_pct)])


def journals():
    return pool([note(j, 1) for j in NOTES if j.startswith("explore_journal_")])


def site_note(book_id, pct):
    return pool([note(book_id, pct), empty(100 - pct)])


def gen_loot():
    food = table("skycraft:chests/skyrim_food", 10)
    loot("camp",
         pool([gold(2, 15, 10), food, item("minecraft:torch", 6, (1, 3)), item("minecraft:leather", 4, (1, 2)),
               item("skycraft:lockpick", 3, (1, 3)), potion(3), empty(6)], (2, 4)),
         journals(), maps_and_leads(18, 30), skill_books(3))
    loot("hunter",
         pool([item("minecraft:leather", 10, (1, 4)), item("skycraft:wolf_pelt", 6, (1, 2)), item("skycraft:raw_venison", 8, (1, 3)),
               item("skycraft:hide", 6, (1, 3)), arrows(8), gear("bow", 3, leveled=True), item("skycraft:bear_claws", 2),
               gold(3, 20, 6), food], (2, 5)),
         pool([item("skycraft:skill_book_archery", 3), item("skycraft:skill_book_hunting", 3), empty(94)]),
         maps_and_leads(8, 12))
    loot("hermit",
         pool([ingredient(12, 1, 4), potion(6), item("minecraft:mushroom_stew", 3), food, gold(5, 40, 8),
               item("skycraft:garnet", 2), tome(2), table("skycraft:chests/soul_gems", 2)], (3, 6)),
         site_note("explore_note_hermit_rules", 35), skill_books(10), maps_and_leads(12, 20))
    loot("hermit_food", pool([food, item("minecraft:bread", 6, (1, 3)), item("minecraft:apple", 4, (1, 4)),
                              item("skycraft:nord_mead", 3)], (2, 4)))
    loot("lookout",
         pool([gold(10, 60, 14), gear("weapon", 6, 0.1, 0.2), gear("armor", 4), arrows(6), item("skycraft:lockpick", 4, (1, 4)),
               item("skycraft:skooma", 2), potion(4), table("skycraft:chests/gold", 4)], (3, 6)),
         pool([note("explore_lead_bandit_orders", 40), tmap(20), empty(40)]), skill_books(5))
    loot("fisher",
         pool([item("minecraft:cod", 10, (1, 4)), item("minecraft:salmon", 8, (1, 3)), item("skycraft:fishing_bait", 8, (2, 8)),
               item("skycraft:fishing_rod", 3), item("minecraft:string", 5, (1, 3)), item("skycraft:salt_pile", 4, (1, 3)),
               gold(1, 12, 5)], (2, 4)),
         site_note("explore_note_fishers_almanac", 25), pool([item("skycraft:skill_book_fishing", 5), empty(95)]))
    loot("offerings",
         pool([gold(3, 30, 20), item("minecraft:candle", 8, (1, 3)), item("skycraft:garnet", 3), item("skycraft:ruby", 2),
               item("skycraft:amulet_of_mara", 1), item("skycraft:amulet_of_talos", 1), item("skycraft:silver_ring", 2),
               potion(4), item("minecraft:poppy", 4, (1, 3))], (1, 3)),
         pool([note("explore_note_wayshrine_prayer", 20), note("explore_note_menhir_verse", 10),
               note("explore_note_sea_hymn", 5), empty(65)]), skill_books(3))
    loot("cache",
         pool([item("skycraft:coin_purse", 20, fns=[{"function": "minecraft:set_nbt", "tag": "{gold:150L}"}]),
               item("skycraft:coin_purse", 10, fns=[{"function": "minecraft:set_nbt", "tag": "{gold:300L}"}]),
               gold(25, 64, 10)]),
         pool([gear("weapon", 10, 0.5, 0.5), gear("armor", 10, 0.5, 0.5), gear("bow", 3, 0.5, 0.5, leveled=True),
               item("skycraft:flawless_ruby", 3), item("skycraft:flawless_sapphire", 2), item("skycraft:flawless_diamond", 1),
               item("skycraft:gold_diamond_ring", 2), item("skycraft:gold_ruby_necklace", 2),
               table("skycraft:chests/soul_gems", 4), potion(6), tome(3)], (2, 4)),
         site_note("explore_note_cache_ledger", 30), skill_books(12),
         pool([table("skycraft:arsenal/staves", 2), table("skycraft:legendary", 1), empty(97)]))
    loot("adventurer",
         pool([gold(5, 50, 12), potion(8), gear("weapon", 5, 0.2, 0.2), gear("armor", 5, 0.2, 0.2), arrows(4),
               item("minecraft:torch", 5, (1, 4)), item("skycraft:lockpick", 4, (1, 5)), food,
               table("skycraft:chests/soul_gems", 2)], (2, 5)),
         maps_and_leads(20, 35), skill_books(8))
    loot("battlefield",
         pool([gear("weapon", 12, 0.05, 0.1, leveled=True), gear("shield", 6, 0.05, 0.1), gear("armor", 8, 0.05, 0.1, leveled=True),
               arrows(8), gold(5, 40, 8), item("minecraft:iron_ingot", 4, (1, 3))], (2, 4)),
         site_note("explore_note_last_orders", 35), skill_books(6))
    loot("cairn",
         pool([gold(8, 40, 14), item("skycraft:ancient_nord_war_axe", 3), item("skycraft:ancient_nord_sword", 3),
               item("skycraft:ancient_nord_helmet", 2), table("skycraft:chests/soul_gems", 4), item("skycraft:garnet", 3),
               item("skycraft:bone_meal_draugr", 4, (1, 3)), item("skycraft:silver_necklace", 2)], (1, 3)),
         site_note("explore_note_cairn_epitaph", 25), skill_books(4))
    loot("ayleid",
         pool([item("skycraft:refined_moonstone", 6, (1, 2)), item("skycraft:raw_moonstone", 6, (1, 3)), potion(8),
               table("skycraft:chests/soul_gems", 5), item("skycraft:sapphire", 3), tome(3, ["restoration", "alteration"]),
               gold(10, 40, 6)], (2, 3)),
         site_note("explore_note_ayleid_fragment", 40), skill_books(6))
    loot("velothi",
         pool([gold(5, 30, 12), ingredient(10), item("minecraft:candle", 6, (1, 3)), potion(4),
               table("skycraft:chests/soul_gems", 3), item("skycraft:garnet", 2)], (2, 3)),
         site_note("explore_note_velothi_prayer", 40), skill_books(4))
    loot("yokudan",
         pool([gold(20, 64, 14), item("skycraft:ruby", 4), item("skycraft:gold_ring", 3), gear("weapon", 6, 0.3, 0.3),
               item("minecraft:gold_ingot", 4, (1, 3)), potion(4)], (2, 4)),
         site_note("explore_note_obelisk_rubbing", 40), skill_books(5))
    loot("akaviri",
         pool([gold(10, 50, 12), item("skycraft:akaviri_dagger", 3), item("skycraft:sapphire", 3),
               item("minecraft:lantern", 4), potion(4), ingredient(6)], (2, 3)),
         site_note("explore_note_akaviri_scroll", 45), skill_books(5))
    loot("monolith",
         pool([gold(10, 60, 10), item("minecraft:amethyst_shard", 8, (1, 4)), item("skycraft:void_salts", 3),
               table("skycraft:chests/soul_gems", 5), tome(4), potion(4), item("skycraft:flawless_sapphire", 1)], (2, 4)),
         site_note("explore_note_monolith_riddle", 40), maps_and_leads(15, 20), skill_books(8))
    loot("hist",
         pool([item("skycraft:hist_sap", 10, (1, 3)), ingredient(10), item("skycraft:marshmerrow", 4, (1, 2)), potion(4),
               gold(5, 25, 6)], (2, 4)),
         site_note("explore_note_hist_song", 40), skill_books(4))
    loot("moon_sugar",
         pool([item("skycraft:moon_sugar", 14, (2, 6)), item("skycraft:skooma", 4, (1, 2)), gold(10, 40, 8),
               item("minecraft:sugar_cane", 6, (2, 6))], (2, 4)),
         site_note("explore_note_sugar_ledger", 35))
    loot("beehive",
         pool([item("minecraft:honey_bottle", 12, (1, 3)), item("minecraft:honeycomb", 10, (1, 4)), ingredient(6),
               item("skycraft:honey_nut_treat", 4, (1, 2)), arrows(4)], (2, 4)),
         site_note("explore_note_honey_harvest", 35), skill_books(3))
    loot("crystal",
         pool([item("minecraft:amethyst_shard", 10, (2, 6)), item("skycraft:refined_moonstone", 6, (1, 2)),
               item("skycraft:refined_malachite", 4), item("skycraft:flawless_sapphire", 2), item("skycraft:flawless_diamond", 1),
               item("skycraft:sapphire", 4), gold(10, 40, 6)], (2, 4)),
         site_note("explore_note_gemcutter", 35), skill_books(4))
    loot("dwemer",
         pool([table("skycraft:chests/dwemer_scrap", 30), item("skycraft:dwemer_gear", 6, (1, 2)), item("skycraft:dwarven_oil", 4),
               item("skycraft:dwarven_metal_ingot", 4, (1, 2)), gear("weapon", 2, 0.2, 0.2)], (2, 4)),
         site_note("explore_note_dwemer_survey", 35), skill_books(6))
    loot("overlook",
         pool([gold(5, 35, 12), potion(8), food, item("minecraft:spyglass", 2), item("skycraft:lockpick", 3, (1, 3)),
               item("minecraft:torch", 4, (1, 3))], (2, 4)),
         maps_and_leads(25, 30), skill_books(5))
    loot("miner",
         pool([table("skycraft:chests/mine_ore", 30), item("minecraft:iron_pickaxe", 3), item("minecraft:torch", 6, (2, 6)),
               item("skycraft:raw_silver", 4, (1, 3)), item("skycraft:raw_corundum", 4, (1, 3))], (2, 4)),
         site_note("explore_note_prospector_claim", 30), pool([item("skycraft:skill_book_mining", 4), item("skycraft:skill_book_smithing", 3), empty(93)]))
    loot("herbalist",
         pool([ingredient(16, 1, 4), table("skycraft:chests/alchemy_ingredients", 10), potion(8, False), potion(3, True),
               item("minecraft:glass_bottle", 6, (1, 4))], (3, 6)),
         site_note("explore_note_herb_notes", 35), pool([item("skycraft:skill_book_alchemy", 6), empty(94)]))
    loot("reagent",
         pool([item("skycraft:nirnroot", 8, (1, 2)), item("skycraft:glowing_mushroom", 8, (1, 3)), ingredient(12, 1, 3),
               item("skycraft:void_salts", 2), potion(4)], (2, 4)),
         pool([item("skycraft:skill_book_alchemy", 5), empty(95)]))
    loot("reagent_giant",
         pool([item("skycraft:giants_toe", 20), item("skycraft:mammoth_tusk", 6), item("skycraft:mammoth_steak", 6, (1, 2)),
               ingredient(8)], (1, 3)),
         site_note("explore_note_giant_hunters", 40))
    loot("reagent_fire",
         pool([item("skycraft:fire_salts", 20, (1, 3)), item("skycraft:ash_salts", 6, (1, 3)), ingredient(8), potion(3)], (2, 3)),
         pool([item("skycraft:skill_book_destruction", 4), empty(96)]))
    loot("bather",
         pool([potion(10), food, gold(3, 20, 8), item("minecraft:leather_boots", 3), item("skycraft:silver_ring", 2),
               item("minecraft:white_wool", 3)], (2, 3)),
         site_note("explore_note_bathers_complaint", 40))
    loot("shipwreck",
         pool([table("skycraft:chests/sea_wreck", 30), gold(10, 50, 10), item("skycraft:alto_wine", 4, (1, 2)),
               item("minecraft:lead", 3), item("minecraft:paper", 4, (1, 4))], (2, 4)),
         site_note("explore_note_ships_log", 35), maps_and_leads(15, 15))
    loot("mage",
         pool([tome(14), table("skycraft:chests/soul_gems", 8), potion(8), item("minecraft:amethyst_shard", 4, (1, 3)),
               table("skycraft:arsenal/staves", 2), gold(10, 50, 6), item("skycraft:void_salts", 2)], (2, 4)),
         pool([note("explore_note_failed_experiment", 30), note("explore_note_apprentice_lament", 25), empty(45)]),
         pool([table("skycraft:chests/skill_books", 22), empty(78)]))


# ================================================================================================ notes & lang

def gen_notes(lang):
    books = os.path.join(ASSETS, "lore", "books")
    for nid, (title, author, pages) in NOTES.items():
        write(os.path.join(books, nid + ".json"), {"author": author, "pages": pages})
        lang["book.skycraft." + nid] = title
    for uid, u in UNIQUES.items():
        lang["explore.skycraft.unique." + uid] = u["name"]
        lang["explore.skycraft.unique." + uid + ".lore"] = u["lore"]
        if u.get("note"):
            title, author, pages = u["note"]
            nid = "explore_unique_" + uid
            write(os.path.join(books, nid + ".json"), {"author": author, "pages": pages})
            lang["book.skycraft." + nid] = title


# ================================================================================================ uniques

def load_map():
    reg = np.array(Image.open(os.path.join(TAMRIEL, "regions.png")))
    coast = np.array(Image.open(os.path.join(TAMRIEL, "coast.png"))).astype(np.int64) - 32768
    feat = np.array(Image.open(os.path.join(TAMRIEL, "features.png")).convert("RGBA")).astype(np.int64)
    regions = json.load(open(os.path.join(TAMRIEL, "regions.json"), encoding="utf-8"))
    return reg, coast, feat, regions


def load_places():
    root = os.path.join(TAMRIEL, "places")
    out = []
    for f in json.load(open(os.path.join(root, "index.json"), encoding="utf-8")):
        p = os.path.join(root, f)
        if os.path.exists(p):
            out += json.load(open(p, encoding="utf-8"))
    return out


def gen_uniques():
    reg, coast, feat, regions = load_map()
    ids = {r["id"]: i for i, r in enumerate(regions)}
    places = load_places()
    pxs = np.array([p["x"] for p in places], dtype=np.float64)
    pzs = np.array([p["z"] for p in places], dtype=np.float64)
    prs = np.array([p.get("radius", 32) for p in places], dtype=np.float64)
    riv_d = (255 - feat[..., 0]) * 0.5
    ridge = feat[..., 1] / 255.0
    lake_sd = (feat[..., 2] - 128) * 0.5
    chosen = []
    out = []
    for uid, u in UNIQUES.items():
        if "x" in u:
            x, z = u["x"], u["z"]
        else:
            idx = ids[u["region"]]
            best = None
            # well clear of named places and water first; relax step by step in crowded regions
            for need, coast_min, apart in ((220, 80, 900), (140, 50, 700), (90, 30, 500)):
                zz, xx = np.nonzero((reg == idx) & (coast > coast_min) & (riv_d > 40) & (lake_sd < -20))
                best_score = -1e18
                for cz, cx in zip(zz, xx):
                    x = MIN_X + cx * CELL + CELL // 2
                    z = MIN_Z + cz * CELL + CELL // 2
                    d = np.sqrt((pxs - x) ** 2 + (pzs - z) ** 2) - prs
                    clearance = d.min() if len(d) else 1e9
                    if clearance < need:
                        continue
                    if any((x - a) ** 2 + (z - b) ** 2 < apart ** 2 for a, b in chosen):
                        continue
                    h = zlib.crc32(f"{uid}:{cx}:{cz}".encode()) / 2 ** 32
                    score = h * 100 + min(clearance, 600) * 0.05
                    if u["kind"] in ("overlook", "bandit_lookout", "mineral_vein", "nordic_cairn"):
                        score += ridge[cz, cx] * 120
                    if score > best_score:
                        best, best_score = (x, z), score
                if best is not None:
                    break
            if best is None:
                print("no spot for unique", uid, "in", u["region"])
                continue
            x, z = best
        chosen.append((x, z))
        entry = {"id": uid, "kind": u["kind"], "x": int(x), "z": int(z), "item": u["item"], "enchant": u["enchant"]}
        if u.get("note"):
            entry["note"] = "explore_unique_" + uid
        out.append(entry)
        print(f"unique {uid:28s} {u['kind']:18s} {x:7d} {z:7d}  {u['region']}")
    write(os.path.join(RES, "skycraft", "explore", "uniques.json"), out)


# ================================================================================================ worldgen & GLM

def gen_worldgen():
    wg = os.path.join(DATA, "worldgen")
    write(os.path.join(wg, "configured_feature", "minor_site.json"), {"type": "skycraft:minor_site", "config": {}})
    # once per chunk: the feature finds its own site (MinorSites), so no count, spread or biome filter
    write(os.path.join(wg, "placed_feature", "minor_site.json"), {"feature": "skycraft:minor_site", "placement": []})
    write(os.path.join(DATA, "forge", "biome_modifier", "explore_minor_sites.json"),
          {"type": "forge:add_features", "biomes": "#minecraft:is_overworld", "features": "skycraft:minor_site",
           "step": "top_layer_modification"})
    write(os.path.join(DATA, "loot_modifiers", "explore_skill_books.json"),
          {"type": "skycraft:explore_skill_books", "conditions": [],
           "tables": ["skycraft:chests/dungeon_common", "skycraft:chests/dungeon_minor", "skycraft:chests/dwemer_scrap",
                      "skycraft:chests/giant_camp", "skycraft:chests/dragon_lair", "skycraft:chests/bandit_camp",
                      "skycraft:chests/mine_ore"],
           "table": "skycraft:chests/skill_books", "chance": 0.06})
    write(os.path.join(DATA, "loot_modifiers", "explore_skill_books_boss.json"),
          {"type": "skycraft:explore_skill_books", "conditions": [], "tables": ["skycraft:chests/dungeon_boss"],
           "table": "skycraft:chests/skill_books", "chance": 0.3})
    glm = os.path.join(RES, "data", "forge", "loot_modifiers", "global_loot_modifiers.json")
    d = json.load(open(glm, encoding="utf-8"))
    for e in ("skycraft:explore_skill_books", "skycraft:explore_skill_books_boss"):
        if e not in d["entries"]:
            d["entries"].append(e)
    write(glm, d)


# ================================================================================================ assets

def gen_assets(lang):
    write(os.path.join(ASSETS, "blockstates", "ayleid_well.json"), {"variants": {"": {"model": "skycraft:block/ayleid_well"}}})
    # a fluted white pedestal holding a glowing welkynd stone (vanilla textures, original model)
    write(os.path.join(ASSETS, "models", "block", "ayleid_well.json"), {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/quartz_pillar", "pillar": "minecraft:block/quartz_pillar",
                     "top": "minecraft:block/quartz_pillar_top", "stone": "minecraft:block/sea_lantern"},
        "elements": [
            {"from": [3, 0, 3], "to": [13, 2, 13], "faces": {d: {"texture": "#top" if d in ("up", "down") else "#pillar"}
                                                             for d in ("north", "south", "east", "west", "up", "down")}},
            {"from": [5, 2, 5], "to": [11, 8, 11], "faces": {d: {"texture": "#top" if d in ("up", "down") else "#pillar"}
                                                             for d in ("north", "south", "east", "west", "up", "down")}},
            {"from": [4, 8, 4], "to": [12, 9, 12], "faces": {d: {"texture": "#top"} for d in ("north", "south", "east", "west", "up", "down")}},
            {"from": [5.5, 9, 5.5], "to": [10.5, 14, 10.5], "shade": False,
             "faces": {d: {"texture": "#stone"} for d in ("north", "south", "east", "west", "up", "down")}},
        ]})
    write(os.path.join(ASSETS, "models", "item", "ayleid_well.json"), {"parent": "skycraft:block/ayleid_well"})
    write(os.path.join(ASSETS, "models", "item", "treasure_map.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": "skycraft:item/treasure_map"}})
    write(os.path.join(DATA, "loot_tables", "blocks", "ayleid_well.json"), {"type": "minecraft:block", "pools": []})
    # the icon: a curled parchment with ink lines and a red X (procedural)
    s = 16
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    px = img.load()
    for y in range(2, 14):
        for x in range(1, 15):
            edge = x in (1, 14) or y in (2, 13)
            n = (zlib.crc32(bytes([x, y])) & 15) - 8
            c = (214 + n, 188 + n, 132 + n, 255) if not edge else (150, 112, 70, 255)
            px[x, y] = c
    for x in range(2, 14):
        px[x, 3] = (176, 140, 92, 255)
    for (x, y) in ((3, 6), (4, 7), (5, 7), (6, 8), (7, 8), (8, 9), (9, 9), (4, 10), (5, 11)):
        px[x, y] = (96, 70, 46, 255)
    for d in range(-2, 3):
        px[11 + d, 9 + d] = (176, 24, 24, 255)
        px[11 + d, 9 - d] = (176, 24, 24, 255)
    out = os.path.join(ASSETS, "textures", "item", "treasure_map.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)


def main():
    lang = {}
    for k, v in SITE_NAMES.items():
        lang["explore.skycraft.site." + k] = v
    lang.update(MISC_LANG)
    gen_loot()
    gen_notes(lang)
    gen_uniques()
    gen_worldgen()
    gen_assets(lang)
    write(os.path.join(MAIN, "lang", "skycraft", "explore.json"), dict(sorted(lang.items())))
    print("lang keys", len(lang))


if __name__ == "__main__":
    main()
