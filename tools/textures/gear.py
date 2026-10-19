#!/usr/bin/env python3
"""Generates NirnCraft's arms & armour from tools/textures/gear_catalogue.py.

    python tools/textures/gear.py [--preview DIR]

Writes (all deterministic):
  * the generated constants of SmithingTier.java and SkyArmorMaterial.java (between the gear: markers);
  * weapon, bow, armour-icon, shield and material textures and their item models;
  * armour layers (64x32) and the 3D armour geometry nirncraft/gear/armor_models.json;
  * lang (mod/src/main/lang/nirncraft/gear.json), gold values (nirncraft_values/crafting.json), weights
    (nirncraft_weights/gear.json), item tags (classes, light/heavy, forge tool/armour tags, province gear lists),
    Better Combat attributes (via gen_bettercombat.py) and the regional loot table.
With --preview DIR it also writes contact sheets of every weapon, icon and worn set.
"""
import json
import os
import re
import sys
import zlib

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))

import gear_armor as ga  # noqa: E402
import gear_catalogue as cat  # noqa: E402
import gear_geo as geo  # noqa: E402
import gear_icons as gi  # noqa: E402
import gear_shields as gs  # noqa: E402
import gear_weapons as gw  # noqa: E402
from gear_px import hexc, mix, ramp, rnd, shade  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MAIN = os.path.join(ROOT, "mod", "src", "main")
RES = os.path.join(MAIN, "resources")
ASSETS = os.path.join(RES, "assets", "nirncraft")
TEX_ITEM = os.path.join(ASSETS, "textures", "item")
TEX_ARMOR = os.path.join(ASSETS, "textures", "models", "armor")
MODELS = os.path.join(ASSETS, "models", "item")
DATA = os.path.join(RES, "data", "nirncraft")
JAVA = os.path.join(MAIN, "java", "com", "nirncraft", "crafting")
LANG = os.path.join(MAIN, "lang", "nirncraft")

WRITTEN = []


def seed(s):
    return zlib.crc32(s.encode()) % 100000


def save_png(img, folder, name):
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, name + ".png"))
    WRITTEN.append(name)


def write_json(path, obj):
    import sys
    from pathlib import Path
    native_tools263 = str(Path(__file__).resolve().parents[1])
    if native_tools263 not in sys.path: sys.path.insert(0, native_tools263)
    from data_format_263 import prepare
    path, obj = prepare(path, obj)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def read_json(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


# ============================================================================================= Java constants

def jfloat(v):
    s = repr(round(float(v), 3))
    return s + "f"


def jstr(v):
    return "null" if v is None else f'"{v}"'


def extras_spec(extra):
    return ",".join(f"{i}*{n}" for i, n in extra)


FAMILY_LETTER = {"dagger": "d", "sword": "s", "war_axe": "a", "mace": "m", "greatsword": "g", "battleaxe": "b", "warhammer": "h", "bow": "w"}


def java_constants():
    lines = []
    for k, t in enumerate(cat.TIERS):
        fam = "".join(FAMILY_LETTER[w] for w in t.types)
        end = ";" if k == len(cat.TIERS) - 1 else ","
        lines.append(f'    {t.id.upper()}("{t.id}", "{t.cat}", {t.uses}, {jfloat(t.mining_speed())}, {jfloat(t.damage_bonus())}, {t.level}, '
                     f'{t.ench}, {jfloat(t.bow)}, {jstr(t.perk)}, "{t.temper}", Rarity.{t.rarity.upper()}, {str(t.fire).lower()}, '
                     f'"{t.main}", "{extras_spec(t.extra)}", "{fam}", {t.min_level}){end}')
    _replace_block(os.path.join(JAVA, "SmithingTier.java"), "gear:tiers", lines)
    lines = []
    for k, a in enumerate(cat.ARMORS):
        d = a.defense()
        end = ";" if k == len(cat.ARMORS) - 1 else ","
        counts = "null" if a.counts is None else "new int[]{" + ", ".join(map(str, a.counts)) + "}"
        lines.append(f'    {a.id.upper()}("{a.id}", {str(a.heavy).lower()}, new int[]{{{d[0]}, {d[1]}, {d[2]}, {d[3]}}}, {a.durability()}, '
                     f'{a.ench}, {jfloat(a.tough)}, {jfloat(a.kb)}, {a.shield_defense()}, {jstr(a.perk)}, "{a.temper}", "{a.cat}", '
                     f'Rarity.{a.rarity.upper()}, {str(a.fire).lower()}, "{a.main}", "{extras_spec(a.extra)}", "{a.style}", "{a.sound}", '
                     f'{a.min_level}, {counts}){end}')
    _replace_block(os.path.join(JAVA, "SkyArmorMaterial.java"), "gear:armor", lines)


def _replace_block(path, marker, lines):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    nl = "\r\n" if "\r\n" in src else "\n"
    start = src.index(f"// <{marker}>")
    start = src.index(nl, start) + len(nl)
    end = src.index(f"// </{marker}>")
    end = src.rindex(nl, 0, end) + len(nl)
    src = src[:start] + nl.join(lines) + nl + src[end:]
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(src)


# ============================================================================================= weapons

HANDHELD = "minecraft:item/handheld"


def weapon_model(item_id, wtype):
    m = {"parent": HANDHELD, "textures": {"layer0": f"nirncraft:item/{item_id}"}}
    if wtype in cat.TWO_HANDED:
        # two-handers are drawn bigger in the hands than one-handers
        m["display"] = {
            "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 5.5, 0.5], "scale": [1.1, 1.1, 1.1]},
            "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 5.5, 0.5], "scale": [1.1, 1.1, 1.1]},
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.6, 1.13], "scale": [0.8, 0.8, 0.8]},
            "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.6, 1.13], "scale": [0.8, 0.8, 0.8]},
        }
    elif wtype == "dagger":
        m["display"] = {
            "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 3.0, 0.5], "scale": [0.7, 0.7, 0.7]},
            "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 3.0, 0.5], "scale": [0.7, 0.7, 0.7]},
        }
    return m


BOW_DISPLAY = {
    "thirdperson_righthand": {"rotation": [-80, 260, -40], "translation": [-1, -2, 2.5], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [-80, -280, 40], "translation": [-1, -2, 2.5], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}


def weapons():
    for item_id, t, w in cat.weapon_items():
        if w == "bow":
            save_png(gw.bow(t.id, -1), TEX_ITEM, item_id)
            for i in range(3):
                save_png(gw.bow(t.id, i), TEX_ITEM, f"{item_id}_pulling_{i}")
                write_json(os.path.join(MODELS, f"{item_id}_pulling_{i}.json"),
                           {"parent": f"nirncraft:item/{item_id}", "textures": {"layer0": f"nirncraft:item/{item_id}_pulling_{i}"}})
            write_json(os.path.join(MODELS, f"{item_id}.json"), {
                "parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/{item_id}"}, "display": BOW_DISPLAY,
                "overrides": [{"predicate": {"pulling": 1}, "model": f"nirncraft:item/{item_id}_pulling_0"},
                              {"predicate": {"pulling": 1, "pull": 0.65}, "model": f"nirncraft:item/{item_id}_pulling_1"},
                              {"predicate": {"pulling": 1, "pull": 0.9}, "model": f"nirncraft:item/{item_id}_pulling_2"}]})
        else:
            save_png(gw.weapon(t.id, w, seed(item_id)), TEX_ITEM, item_id)
            write_json(os.path.join(MODELS, f"{item_id}.json"), weapon_model(item_id, w))


# ============================================================================================= armour

def armour():
    styles = {}
    for a in cat.ARMORS:
        l1, l2, built = ga.layers(a.id, a.style)
        save_png(l1, TEX_ARMOR, f"{a.id}_layer_1")
        save_png(l2, TEX_ARMOR, f"{a.id}_layer_2")
        styles[a.style] = geo.to_json(built)
        icons = [gi.helmet_icon(a.id, a.style, seed(a.id)), gi.cuirass_icon(a.id, a.style, seed(a.id) + 1),
                 gi.gauntlets_icon(a.id, a.style, seed(a.id) + 2), gi.boots_icon(a.id, a.style, seed(a.id) + 3)]
        for piece, img in zip(cat.PIECES, icons):
            item_id = f"{a.id}_{piece}"
            save_png(img, TEX_ITEM, item_id)
            write_json(os.path.join(MODELS, f"{item_id}.json"),
                       {"parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/{item_id}"}})
        if a.shield:
            tex = gs.texture(a.id)
            save_png(tex, os.path.join(TEX_ITEM, "shield"), a.id)
            main, block = gs.model(a.id, f"nirncraft:item/shield/{a.id}")
            write_json(os.path.join(MODELS, f"{a.id}_shield.json"), main)
            write_json(os.path.join(MODELS, f"{a.id}_shield_blocking.json"), block)
    write_json(os.path.join(RES, "nirncraft", "gear", "armor_models.json"), {"styles": styles})


# ============================================================================================= materials

def material_icon(kind):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    r = rnd(seed(kind))
    if kind == "stalhrim":
        pal = ramp("2c5c76", "5898b6", "98d0e6", "e6faff")
        shards = [(5, 4, 3, 9), (9, 2, 2, 11), (11, 6, 2, 7), (3, 8, 3, 6)]
        for (x0, y0, w, h) in shards:
            for y in range(y0, min(15, y0 + h)):
                for x in range(x0, x0 + w):
                    t = (y - y0) / max(1, h)
                    if abs(x - (x0 + w / 2 - 0.5)) <= w / 2 * min(1.0, t * 2.2 + 0.2):
                        px[x, y] = pal[3] if x == x0 else (pal[2] if (x + y) % 3 else pal[1])
    elif kind in ("chitin_plate", "chaurus_chitin"):
        pal = ramp("2e4842", "48685e", "6a8e80", "98bcaa") if kind == "chitin_plate" else ramp("4a3a22", "776039", "a48a55", "cdb87e")
        for y in range(3, 14):
            for x in range(2, 14):
                if (x - 7.5) ** 2 / 36 + (y - 8.5) ** 2 / 30 <= 1:
                    k = (y - 3) % 4
                    px[x, y] = pal[3] if k == 0 and x < 9 else (pal[2] if k == 1 else (pal[1] if k == 2 else pal[0]))
    elif kind == "netch_leather":
        pal = ramp("3a3028", "564a3e", "74665a", "988a7c")
        for y in range(3, 14):
            for x in range(2, 14):
                if (x - 7.5) ** 2 / 40 + (y - 8.5) ** 2 / 26 <= 1:
                    px[x, y] = pal[2] if r.random() > 0.6 else pal[1]
        for (x, y) in ((5, 6), (9, 8), (7, 11), (11, 5)):
            px[x, y] = hexc("9a8454")
    # outline
    src = img.copy().load()
    for y in range(16):
        for x in range(16):
            if src[x, y][3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and src[nx, ny][3]:
                    c = src[nx, ny]
                    px[x, y] = (int(c[0] * 0.3), int(c[1] * 0.3), int(c[2] * 0.3), 255)
                    break
    return img


def materials():
    for mid in cat.MATERIALS:
        save_png(material_icon(mid), TEX_ITEM, mid)
        write_json(os.path.join(MODELS, f"{mid}.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/{mid}"}})


# ============================================================================================= lang

CATEGORY_NAMES = {"imperial": "Imperial", "advanced": "Advanced", "silver": "Silver", "stalhrim": "Stalhrim", "nordic": "Nordic",
                  "ancient": "Ancient & Falmer", "morrowind": "Morrowind", "factions": "Guilds & Orders",
                  "tamriel": "Tamriel", "foreign": "Beyond Tamriel"}


def all_gear_ids():
    ids = [i for i, _, _ in cat.weapon_items()]
    ids += [i for i, _, _ in cat.armor_items()]
    ids += list(cat.MATERIALS)
    return ids


def lang():
    out = {}
    for item_id, t, w in cat.weapon_items():
        out[f"item.nirncraft.{item_id}"] = t.label(w)
    for item_id, a, i in cat.armor_items():
        out[f"item.nirncraft.{item_id}"] = a.label(i)
    for mid, (name, _, _) in cat.MATERIALS.items():
        out[f"item.nirncraft.{mid}"] = name
    for k, v in CATEGORY_NAMES.items():
        out[f"category.nirncraft.smithing.{k}"] = v
    out["tooltip.nirncraft.silver_weapon"] = "Burns the undead"
    write_json(os.path.join(LANG, "gear.json"), dict(sorted(out.items())))
    # the old per-item names in crafting.json are superseded
    path = os.path.join(LANG, "crafting.json")
    old = read_json(path, {})
    keep = {k: v for k, v in old.items() if k not in out}
    if len(keep) != len(old):
        write_json(path, keep)


# ============================================================================================= values & weights

def values():
    path = os.path.join(DATA, "nirncraft_values", "crafting.json")
    data = read_json(path, {"values": {}})
    vals = data["values"]
    for item_id, t, w in cat.weapon_items():
        key = f"nirncraft:{item_id}"
        if t.id not in cat.EXISTING_TIERS or key not in vals:
            vals[key] = int(round(t.value * cat.TYPE_VALUE[w]))
    for item_id, a, i in cat.armor_items():
        vals[f"nirncraft:{item_id}"] = a.val[i]
    for mid, (_, v, _) in cat.MATERIALS.items():
        vals[f"nirncraft:{mid}"] = v
    data["values"] = dict(sorted(vals.items()))
    write_json(path, data)


def weights():
    default = read_json(os.path.join(DATA, "nirncraft_weights", "default.json"), {"weights": {}})["weights"]
    out = {}
    for item_id, t, w in cat.weapon_items():
        key = f"nirncraft:{item_id}"
        if key in default:
            out[key] = default[key]
        else:
            out[key] = round(cat.TYPE_WEIGHT[w] * t.wfac * 2) / 2
    for item_id, a, i in cat.armor_items():
        out[f"nirncraft:{item_id}"] = a.wt[i]
    for mid, (_, _, wt) in cat.MATERIALS.items():
        out[f"nirncraft:{mid}"] = wt
    write_json(os.path.join(DATA, "nirncraft_weights", "gear.json"), {"weights": out})


# ============================================================================================= tags

def merge_tag(path, items, owned):
    """Writes a tag keeping every entry that isn't one of our own items, then ours."""
    data = read_json(path, {"replace": False, "values": []})
    keep = [v for v in data.get("values", []) if not (isinstance(v, str) and v in owned)]
    vals = keep + [i for i in items if i not in keep]
    # tag references (#...) go last
    plain = [v for v in vals if not (isinstance(v, dict) or (isinstance(v, str) and v.startswith("#")))]
    refs = [v for v in vals if v not in plain]
    write_json(path, {"replace": False, "values": plain + refs})


def tags():
    owned = {f"nirncraft:{i}" for i in all_gear_ids()}
    owned |= {"minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"}
    T = os.path.join(DATA, "tags", "item")
    F = os.path.join(RES, "data", "forge", "tags", "item")
    weapons_by = {}
    for item_id, t, w in cat.weapon_items():
        weapons_by.setdefault(w, []).append(f"nirncraft:{item_id}")
    for w, name in (("dagger", "daggers"), ("mace", "maces"), ("war_axe", "war_axes"), ("greatsword", "greatswords"),
                    ("battleaxe", "battleaxes"), ("warhammer", "warhammers")):
        merge_tag(os.path.join(T, f"{name}.json"), weapons_by[w], owned)
    merge_tag(os.path.join(T, "two_handed.json"), weapons_by["greatsword"] + weapons_by["battleaxe"] + weapons_by["warhammer"], owned)
    heavy, light = [], []
    for item_id, a, i in cat.armor_items():
        if i == 4:
            continue
        (heavy if a.heavy else light).append(f"nirncraft:{item_id}")
    merge_tag(os.path.join(T, "heavy_armor.json"), heavy, owned)
    merge_tag(os.path.join(T, "light_armor.json"), light, owned)
    # forge tags
    by_piece = {p: [] for p in cat.PIECES}
    shields = []
    for item_id, a, i in cat.armor_items():
        if i == 4:
            shields.append(f"nirncraft:{item_id}")
        else:
            by_piece[cat.PIECES[i]].append(f"nirncraft:{item_id}")
    for p, name in (("helmet", "helmets"), ("chestplate", "chestplates"), ("leggings", "leggings"), ("boots", "boots")):
        merge_tag(os.path.join(F, "armors", f"{name}.json"), by_piece[p], owned)
    merge_tag(os.path.join(F, "tools", "shields.json"), shields, owned)
    merge_tag(os.path.join(F, "tools", "bows.json"), weapons_by["bow"], owned)
    merge_tag(os.path.join(F, "tools", "swords.json"), weapons_by["dagger"] + weapons_by["sword"] + weapons_by["greatsword"], owned)
    merge_tag(os.path.join(F, "tools", "axes.json"), weapons_by["war_axe"] + weapons_by["battleaxe"], owned)
    merge_tag(os.path.join(F, "tools", "maces.json"), weapons_by["mace"] + weapons_by["warhammer"], owned)
    # province gear lists (regional loot) and what Skyrim's blacksmiths stock
    prov = {p: [] for p in cat.PROVINCES + ("realm",)}
    smith = []
    for item_id, t, w in cat.weapon_items():
        for p in t.provinces:
            prov[p].append(f"nirncraft:{item_id}")
        if "skyrim" in t.provinces and t.craftable:
            smith.append(f"nirncraft:{item_id}")
    for item_id, a, i in cat.armor_items():
        for p in a.provinces:
            prov[p].append(f"nirncraft:{item_id}")
        if "skyrim" in a.provinces and a.cat not in ("factions",):
            smith.append(f"nirncraft:{item_id}")
    for p, items in prov.items():
        merge_tag(os.path.join(T, "gear", f"{p}.json"), items, owned)
    smith += [f"nirncraft:{m}" for m in cat.MATERIALS]
    merge_tag(os.path.join(T, "smithing_goods.json"), smith, owned)


# ============================================================================================= loot

def loot():
    write_json(os.path.join(DATA, "loot_table", "gear", "regional.json"), {
        "type": "minecraft:chest",
        "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [{
            "type": "minecraft:item", "name": "minecraft:iron_ingot",
            "functions": [{"function": "nirncraft:regional_gear", "kind": "any", "enchant_chance": 0.1, "quality_chance": 0.25}]}]}],
        "random_sequence": "nirncraft:gear/regional"})
    write_json(os.path.join(DATA, "loot_modifiers", "regional_gear.json"), {
        "type": "nirncraft:add_table", "conditions": [], "table": "nirncraft:gear/regional", "prefix": "chests/", "chance": 0.35})
    # raw materials of the new sets in the smithing-materials chests
    path = os.path.join(DATA, "loot_table", "chests", "smithing_materials.json")
    table = read_json(path)
    if table:
        entries = table["pools"][0]["entries"]
        names = {e.get("name") for e in entries}
        for mid, w in (("stalhrim", 2), ("chitin_plate", 3), ("chaurus_chitin", 3), ("netch_leather", 3)):
            if f"nirncraft:{mid}" not in names:
                entries.append({"type": "minecraft:item", "name": f"nirncraft:{mid}", "weight": w, "functions": [
                    {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 2}}]})
        write_json(path, table)


# ============================================================================================= previews

def previews(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    sc = 4
    types = list(cat.ALL)
    tiers = cat.TIERS
    sheet = Image.new("RGBA", (130 + len(types) * 18 * sc, len(tiers) * 18 * sc), (78, 78, 88, 255))
    d = ImageDraw.Draw(sheet)
    for r, t in enumerate(tiers):
        d.text((4, r * 18 * sc + 28), t.id, fill=(255, 255, 255, 255))
        for c, w in enumerate(types):
            if w not in t.types:
                continue
            img = Image.open(os.path.join(TEX_ITEM, f"{t.id}_{w}.png"))
            sheet.alpha_composite(img.resize((16 * sc, 16 * sc), Image.NEAREST), (130 + c * 18 * sc, r * 18 * sc + sc))
    sheet.save(os.path.join(out_dir, "weapons.png"))


def main():
    java_constants()
    weapons()
    armour()
    materials()
    lang()
    values()
    weights()
    tags()
    loot()
    import gen_bettercombat
    gen_bettercombat.main()
    print(f"gear: wrote {len(WRITTEN)} textures, {len(cat.weapon_items())} weapons, {len(cat.armor_items())} armour pieces")
    if "--preview" in sys.argv:
        previews(sys.argv[sys.argv.index("--preview") + 1])


if __name__ == "__main__":
    main()
