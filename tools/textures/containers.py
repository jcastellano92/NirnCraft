#!/usr/bin/env python3
"""Original procedural art and the block models of NirnCraft's loot containers (docs/CONTAINERS.md section 3).

Every container is a plain block model: the lid (or flap, or door) is the blockstate property `lid`
(closed / ajar / open), one model per value; the styled kinds have a `style` property (nordic, imperial, velothi,
dwemer, elven, desert, rustic) and shelves a `books` count. No block-entity renderer anywhere, so the far-field
renderer that reads block models sees every container and every lid position.

Writes, under mod/src/main/resources:
  assets/nirncraft/textures/block/container/*.png      pixel art (Pillow, seeded, idempotent)
  assets/nirncraft/models/block/container/*.json       templates and per-style children
  assets/nirncraft/blockstates/<id>.json               every property combination
  assets/nirncraft/models/item/<id>.json, items/<id>.json
  data/nirncraft/loot_table/blocks/<id>.json           a player's own container drops itself (style kept)
and the block and container names into mod/src/main/lang/nirncraft/loot.json.

    py -3 tools/textures/containers.py
"""
import json
import os
import random

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "nirncraft")
TEX = os.path.join(ASSETS, "textures", "block", "container")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft", "loot.json")
NS = "nirncraft"

STYLES = ["nordic", "imperial", "velothi", "dwemer", "elven", "desert", "rustic"]
LIDS = ["closed", "ajar", "open"]
FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}

# (wood, wood dark, trim, trim light, inner) per style: a few representative colours, our own choice
PALETTE = {
    "nordic": ("#5a3e2b", "#3a281c", "#45484e", "#7a8088", "#1e1611"),
    "imperial": ("#7a3a24", "#52261a", "#a8823a", "#d9b25e", "#24120c"),
    "velothi": ("#8a6a3a", "#5c4424", "#cbbf9c", "#ece3c8", "#22190e"),
    "dwemer": ("#9a6a2e", "#6a4519", "#c8924a", "#e8bd78", "#2a1a0a"),
    "elven": ("#cbb88e", "#9a875e", "#c4a23c", "#eed67e", "#3a3020"),
    "desert": ("#b39066", "#856941", "#9a5a34", "#cf8452", "#2e2214"),
    "rustic": ("#6e5a44", "#4c3d2e", "#5c5a52", "#8a877c", "#1c1712"),
}
STYLE_NAMES = {"nordic": "Nordic", "imperial": "Imperial", "velothi": "Velothi", "dwemer": "Dwemer",
               "elven": "Elven", "desert": "Alik'r", "rustic": "Rough"}


# ============================================================================ pixel helpers

def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, f):
    return tuple(max(0, min(255, int(c[i] * f))) for i in range(3)) + (c[3],)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(4))


def new(size=16):
    return Image.new("RGBA", (size, size), (0, 0, 0, 255))


def save(name, img):
    os.makedirs(TEX, exist_ok=True)
    img.save(os.path.join(TEX, name + ".png"))


def rng(name):
    return random.Random(sum((i + 1) * ord(ch) for i, ch in enumerate(name)))


def planks(name, wood, dark, horizontal=True, board=4):
    """Boards with grain and seams; dwemer 'wood' is riveted bronze plate instead."""
    r = rng(name)
    img = new()
    for y in range(16):
        for x in range(16):
            u, v = (x, y) if horizontal else (y, x)
            f = 0.88 + 0.16 * r.random()
            if (u * 7 + v * 3 + r.randint(0, 2)) % 9 == 0:
                f *= 0.86  # grain streak
            c = shade(wood, f)
            if v % board == 0:
                c = shade(dark, 0.95)  # seam between boards
            img.putpixel((x, y), c)
    return img


def plate(name, metal, dark):
    r = rng(name)
    img = new()
    for y in range(16):
        for x in range(16):
            c = shade(metal, 0.9 + 0.15 * r.random())
            if x % 8 == 0 or y % 8 == 0:
                c = shade(dark, 1.0)
            img.putpixel((x, y), c)
    for x, y in ((2, 2), (5, 2), (10, 2), (13, 2), (2, 13), (5, 13), (10, 13), (13, 13)):
        img.putpixel((x, y), shade(metal, 1.35))
    return img


def bands(img, trim, light, xs=(1, 2, 13, 14), border=True):
    for y in range(16):
        for x in xs:
            img.putpixel((x, y), light if x in (xs[0], xs[2]) else trim)
    if border:
        for x in range(16):
            img.putpixel((x, 0), trim)
            img.putpixel((x, 15), shade(trim, 0.8))
    for x in (xs[0], xs[2] + 1):
        for y in (3, 12):
            img.putpixel((x, y), shade(light, 1.2))  # rivet heads
    return img


def body(style, name, horizontal=True):
    wood, dark, trim, light, inner = (hexc(c) for c in PALETTE[style])
    if style == "dwemer":
        return plate(name, wood, dark)
    return planks(name, wood, dark, horizontal)


def inner_tex(style, name):
    inner = hexc(PALETTE[style][4])
    r = rng(name)
    img = new()
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), shade(inner, 0.85 + 0.3 * r.random()))
    return img


def lock_plate(img, trim, light, x0=6, y0=3, x1=10, y1=8, gem=None):
    for y in range(y0, y1):
        for x in range(x0, x1):
            img.putpixel((x, y), light if (x in (x0, x1 - 1) or y in (y0, y1 - 1)) else trim)
    img.putpixel(((x0 + x1) // 2, (y0 + y1) // 2), (16, 12, 10, 255))  # keyhole
    if gem:
        img.putpixel(((x0 + x1) // 2 - 1, y0 + 1), gem)
    return img


# ============================================================================ textures

def chest_textures():
    for s in STYLES:
        wood, dark, trim, light, inner = (hexc(c) for c in PALETTE[s])
        save(f"chest_{s}_side", bands(body(s, f"chest_{s}_side"), trim, light))
        save(f"chest_{s}_top", bands(body(s, f"chest_{s}_top", horizontal=False), trim, light))
        front = bands(body(s, f"chest_{s}_front"), trim, light)
        save(f"chest_{s}_front", lock_plate(front, trim, light))
        save(f"chest_{s}_inner", inner_tex(s, f"chest_{s}_inner"))
        # the hoard: broad gilt bands, corner caps and a jewelled lock
        gold, gold_l = hexc("#b8922e"), hexc("#f0d070")
        g_side = bands(body(s, f"grand_{s}_side"), gold, gold_l, xs=(0, 1, 14, 15))
        for x in range(16):
            g_side.putpixel((x, 1), gold)
            g_side.putpixel((x, 14), gold)
        save(f"grand_{s}_side", bands(g_side, trim, light, xs=(6, 7, 8, 9), border=False))
        save(f"grand_{s}_top", bands(body(s, f"grand_{s}_top", horizontal=False), gold, gold_l, xs=(1, 2, 13, 14)))
        g_front = bands(body(s, f"grand_{s}_front"), gold, gold_l, xs=(0, 1, 14, 15))
        save(f"grand_{s}_front", lock_plate(g_front, gold, gold_l, 5, 2, 11, 9, gem=hexc("#a01a2a")))


def strongbox_textures():
    iron, dark, light = hexc("#55585e"), hexc("#34363a"), hexc("#8c9198")
    side = plate("strongbox_side", iron, dark)
    save("strongbox_side", bands(side, dark, light))
    save("strongbox_top", bands(plate("strongbox_top", iron, dark), dark, light))
    save("strongbox_front", lock_plate(bands(plate("strongbox_front", iron, dark), dark, light), hexc("#8a6a2a"), hexc("#c9a04a"), 6, 3, 10, 9))
    save("strongbox_inner", inner_tex("nordic", "strongbox_inner"))


def barrel_textures():
    wood, dark = hexc("#6b4c30"), hexc("#3f2c1b")
    hoop, hoop_l = hexc("#3b3d41"), hexc("#6c7077")
    side = planks("barrel_side", wood, dark, horizontal=False, board=3)
    for x in range(16):
        for y in (2, 3, 12, 13):
            side.putpixel((x, y), hoop_l if y in (2, 12) else hoop)
    save("barrel_side", side)
    r = rng("barrel_top")

    def lid(gap):
        img = new()
        for y in range(16):
            for x in range(16):
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
                if d > 7.2:
                    c = hoop
                elif d > 6.2:
                    c = shade(dark, 1.1)
                else:
                    c = shade(wood, 0.92 + 0.12 * r.random())
                    if x % 4 == 0:
                        c = shade(dark, 1.0)
                    if gap and y <= gap:
                        c = shade(hexc("#140e09"), 1.0 + 0.4 * r.random())  # the dark inside showing
                img.putpixel((x, y), c)
        return img
    save("barrel_top", lid(0))
    save("barrel_top_ajar", lid(5))
    save("barrel_top_open", lid(13))
    save("barrel_bottom", lid(0))


def sack_textures():
    r = rng("sack")
    burlap, dark = hexc("#a08a62"), hexc("#6e5c3e")
    img = new()
    for y in range(16):
        for x in range(16):
            c = shade(burlap, 0.86 + 0.18 * r.random())
            if (x + y) % 2 == 0:
                c = shade(c, 0.93)  # weave
            if y in (0, 15):
                c = dark
            img.putpixel((x, y), c)
    save("sack", img)
    rope = new()
    for y in range(16):
        for x in range(16):
            rope.putpixel((x, y), shade(hexc("#c2a46a"), 0.75 + 0.3 * (((x + y * 2) % 4) / 3)))
    save("sack_rope", rope)
    grain = new()
    for y in range(16):
        for x in range(16):
            v = r.random()
            grain.putpixel((x, y), shade(hexc("#d9c38a"), 0.7 + 0.4 * v) if v > 0.08 else hexc("#c8a040"))
    save("sack_inner", grain)


URN = {  # body, band, band light
    "nordic": ("#7d7a72", "#4c4a46", "#9b978d"),
    "imperial": ("#a5583a", "#5e2c1c", "#c97a52"),
    "velothi": ("#b08a4e", "#5b3d1e", "#d2ad6e"),
    "dwemer": ("#9a6a2e", "#5c3d16", "#d8a65e"),
    "elven": ("#d8d4c8", "#6c8aa4", "#f2efe6"),
    "desert": ("#c9a878", "#3e6a8a", "#e3c89a"),
    "rustic": ("#7a5a3e", "#4a3424", "#946f4e"),
}


def urn_textures():
    for s in STYLES:
        b, band, light = (hexc(c) for c in URN[s])
        r = rng("urn_" + s)
        img = new()
        for y in range(16):
            for x in range(16):
                c = shade(b, 0.88 + 0.16 * r.random())
                if y in (3, 4, 11):
                    c = band
                if y == 7 and (x // 2) % 2 == 0:
                    c = light  # a running pattern round the belly
                img.putpixel((x, y), c)
        save(f"urn_{s}", img)
        top = new()
        for y in range(16):
            for x in range(16):
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
                top.putpixel((x, y), band if d > 6 else shade(b, 0.95 + 0.1 * r.random()))
        save(f"urn_{s}_top", top)
    save("urn_inner", inner_tex("rustic", "urn_inner"))


BOOK_COLOURS = ["#6a2a22", "#2e4a2a", "#2a3a5c", "#5c4a22", "#3a2a3a", "#7a6a4a", "#1e1e22", "#6a4a2a"]


def shelf_textures():
    for s in STYLES:
        wood, dark, trim, light, inner = (hexc(c) for c in PALETTE[s])
        frame = planks(f"shelf_{s}_side", wood, dark, horizontal=False)
        save(f"shelf_{s}_side", frame)
        save(f"shelf_{s}_top", planks(f"shelf_{s}_top", wood, dark))
        for n in range(4):
            r = rng(f"shelf_{s}_{n}")
            img = new()
            for y in range(16):
                for x in range(16):
                    edge = x in (0, 15) or y in (0, 7, 8, 15)
                    img.putpixel((x, y), shade(wood, 0.8 + 0.2 * r.random()) if edge else shade(inner, 1.0 + 0.3 * r.random()))
            fill = [0.0, 0.35, 0.7, 1.0][n]
            for row_top, row_bottom in ((1, 6), (9, 14)):
                x = 1
                while x < 15:
                    w = 1 + (r.random() < 0.4)
                    if r.random() < fill:
                        col = hexc(r.choice(BOOK_COLOURS))
                        h = row_top + (1 if r.random() < 0.4 else 0)
                        for xx in range(x, min(15, x + w)):
                            for yy in range(h, row_bottom + 1):
                                c = shade(col, 0.9 + 0.2 * r.random())
                                if yy == h + 1:
                                    c = shade(hexc("#c9a640"), 0.9)  # a gilt line on the spine
                                img.putpixel((xx, yy), c)
                    x += w
            save(f"shelf_{s}_books{n}", img)


def remains_textures():
    r = rng("remains")
    bone = new()
    for y in range(16):
        for x in range(16):
            bone.putpixel((x, y), shade(hexc("#d8ceb4"), 0.82 + 0.2 * r.random()))
    save("remains_bone", bone)
    cloth = new()
    for y in range(16):
        for x in range(16):
            c = shade(hexc("#4e4234"), 0.8 + 0.3 * r.random())
            if r.random() < 0.06:
                c = shade(hexc("#2a1e16"), 1.0)  # rot and holes
            cloth.putpixel((x, y), c)
    save("remains_cloth", cloth)


# ============================================================================ models

def tex(name):
    return f"{NS}:block/container/{name}"


def write_json(rel, obj):
    path = os.path.join(RES, rel)
    if rel.startswith("data/"):
        # data files in the 26.3 format (conditions and functions as the native codec wants them)
        import sys
        tools = os.path.join(ROOT, "tools")
        if tools not in sys.path:
            sys.path.insert(0, tools)
        from data_format_263 import prepare
        path, obj = prepare(path, obj)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def box(fr, to, faces, rotation=None):
    e = {"from": fr, "to": to, "faces": faces}
    if rotation:
        e["rotation"] = rotation
    return e


def faces(side, front, top, bottom, uv_side, uv_front=None, uv_top=None, cull=None):
    d = {
        "north": {"texture": front, "uv": uv_front or uv_side},
        "south": {"texture": side, "uv": uv_side},
        "east": {"texture": side, "uv": uv_side},
        "west": {"texture": side, "uv": uv_side},
        "up": {"texture": top, "uv": uv_top or [1, 1, 15, 15]},
        "down": {"texture": bottom, "uv": uv_top or [1, 1, 15, 15]},
    }
    if cull:
        d["down"]["cullface"] = cull
    return d


def chest_template(name, x0, x1, base_h, lid_h, z0=1, z1=15, latch=(7, 9)):
    """A lidded box facing north: base, lid hinged at the back top edge, a latch that moves with the lid. Ajar is the
    lid rotated 45 degrees about the hinge; open is the lid standing upright behind the box, built straight (element
    rotations stay within the loader's 22.5-degree steps), its inside facing the opener."""
    lx0, lx1 = latch
    hasp = {f: {"texture": "#front", "uv": [6, 3, 8, 7]} for f in ("north", "south", "east", "west", "up", "down")}
    for lid in LIDS:
        base = box([x0, 0, z0], [x1, base_h, z1],
                   faces("#side", "#front", "#inner", "#side", [x0, 16 - base_h, x1, 16], cull="down"))
        if lid == "open":
            top = base_h + (z1 - z0)
            lid_box = box([x0, base_h, z1 - lid_h], [x1, top, z1], {
                "north": {"texture": "#inner", "uv": [x0, 1, x1, 15]},
                "south": {"texture": "#top", "uv": [x0, 1, x1, 15]},
                "east": {"texture": "#side", "uv": [16 - z1, 16 - base_h - lid_h, 16 - z1 + lid_h, 16 - base_h]},
                "west": {"texture": "#side", "uv": [z1 - lid_h, 16 - base_h - lid_h, z1, 16 - base_h]},
                "up": {"texture": "#front", "uv": [x0, 16 - base_h - lid_h, x1, 16 - base_h]},
                "down": {"texture": "#side", "uv": [x0, 0, x1, lid_h]}})
            latch_box = box([lx0, top - 2, z1 - lid_h - 1], [lx1, top + 1, z1 - lid_h], hasp)
        else:
            rot = None if lid == "closed" else {"origin": [8, base_h, z1], "axis": "x", "angle": 45}
            lid_faces = faces("#side", "#front", "#top", "#inner", [x0, 16 - base_h - lid_h, x1, 16 - base_h])
            lid_box = box([x0, base_h, z0], [x1, base_h + lid_h, z1], lid_faces, rot)
            latch_box = box([lx0, base_h - 2, z0 - 1], [lx1, base_h + 2, z0], hasp, rot)
        write_json(f"assets/{NS}/models/block/container/{name}_{lid}.json", {
            "parent": "minecraft:block/block",
            "textures": {"particle": "#side"},
            "elements": [base, lid_box, latch_box],
        })


def styled_children(template, kind_prefix, tex_prefix):
    for s in STYLES:
        for lid in LIDS:
            write_json(f"assets/{NS}/models/block/container/{kind_prefix}_{s}_{lid}.json", {
                "parent": f"{NS}:block/container/{template}_{lid}",
                "textures": {"side": tex(f"{tex_prefix}_{s}_side"), "front": tex(f"{tex_prefix}_{s}_front"),
                             "top": tex(f"{tex_prefix}_{s}_top"), "inner": tex(f"{tex_prefix}_{s}_inner" if tex_prefix == "chest" else f"chest_{s}_inner")},
            })


def models():
    chest_template("chest_template", 1, 15, 10, 4)
    styled_children("chest_template", "chest", "chest")
    chest_template("grand_template", 0, 16, 11, 4, latch=(7, 9))
    styled_children("grand_template", "grand", "grand")
    chest_template("strongbox_template", 3, 13, 5, 2, z0=4, z1=12, latch=(7, 9))
    for lid in LIDS:
        write_json(f"assets/{NS}/models/block/container/strongbox_{lid}.json", {
            "parent": f"{NS}:block/container/strongbox_template_{lid}",
            "textures": {"side": tex("strongbox_side"), "front": tex("strongbox_front"), "top": tex("strongbox_top"), "inner": tex("strongbox_inner")},
        })
    # barrel: a full cube whose top shows the lid shut, lifted, or off
    for lid, top in (("closed", "barrel_top"), ("ajar", "barrel_top_ajar"), ("open", "barrel_top_open")):
        write_json(f"assets/{NS}/models/block/container/barrel_{lid}.json", {
            "parent": "minecraft:block/cube_bottom_top",
            "textures": {"top": tex(top), "bottom": tex("barrel_bottom"), "side": tex("barrel_side")},
        })
    # sack: tied, loosened, open (the grain or coins inside showing)
    sack_body = box([3, 0, 3], [13, 9, 13], faces("#sack", "#sack", "#sack", "#sack", [3, 7, 13, 16], uv_top=[3, 3, 13, 13], cull="down"))
    sack_models = {
        "closed": [sack_body, box([5, 9, 5], [11, 11, 11], faces("#sack", "#sack", "#sack", "#sack", [5, 5, 11, 7], uv_top=[5, 5, 11, 11])),
                   box([6, 11, 6], [10, 12, 10], faces("#rope", "#rope", "#rope", "#rope", [6, 4, 10, 5], uv_top=[6, 6, 10, 10]))],
        "ajar": [sack_body, box([4, 9, 4], [12, 10.5, 12], faces("#sack", "#sack", "#inner", "#sack", [4, 5, 12, 7], uv_top=[4, 4, 12, 12]))],
        "open": [box([3, 0, 3], [13, 9, 13], faces("#sack", "#sack", "#inner", "#sack", [3, 7, 13, 16], uv_top=[3, 3, 13, 13], cull="down")),
                 box([2, 8, 2], [14, 9, 14], faces("#sack", "#sack", "#inner", "#sack", [2, 7, 14, 8], uv_top=[2, 2, 14, 14]))],
    }
    for lid, els in sack_models.items():
        write_json(f"assets/{NS}/models/block/container/sack_{lid}.json", {
            "parent": "minecraft:block/block",
            "textures": {"particle": tex("sack"), "sack": tex("sack"), "rope": tex("sack_rope"), "inner": tex("sack_inner")},
            "elements": els,
        })
    # urn: body, neck, lip; the lid on, tilted, or off
    for lid in LIDS:
        els = [box([4, 0, 4], [12, 10, 12], faces("#body", "#body", "#top", "#top", [4, 4, 12, 14], uv_top=[4, 4, 12, 12], cull="down")),
               box([5, 10, 5], [11, 12, 11], faces("#body", "#body", "#inner" if lid == "open" else "#top", "#top", [5, 2, 11, 4], uv_top=[5, 5, 11, 11])),
               box([4.5, 12, 4.5], [11.5, 13, 11.5], faces("#body", "#body", "#inner" if lid == "open" else "#top", "#top", [4, 3, 12, 4], uv_top=[4, 4, 12, 12]))]
        if lid != "open":
            rot = None if lid == "closed" else {"origin": [11, 13, 8], "axis": "z", "angle": -22.5}
            els.append(box([5, 13, 5], [11, 14, 11], faces("#top", "#top", "#top", "#top", [5, 3, 11, 4], uv_top=[5, 5, 11, 11]), rot))
            els.append(box([7, 14, 7], [9, 15, 9], faces("#body", "#body", "#top", "#top", [7, 3, 9, 4], uv_top=[7, 7, 9, 9]), rot))
        write_json(f"assets/{NS}/models/block/container/urn_template_{lid}.json", {
            "parent": "minecraft:block/block", "textures": {"particle": "#body"}, "elements": els})
        for s in STYLES:
            write_json(f"assets/{NS}/models/block/container/urn_{s}_{lid}.json", {
                "parent": f"{NS}:block/container/urn_template_{lid}",
                "textures": {"body": tex(f"urn_{s}"), "top": tex(f"urn_{s}_top"), "inner": tex("urn_inner")}})
    # bookshelf: a full cube, the books on the front
    for s in STYLES:
        for n in range(4):
            write_json(f"assets/{NS}/models/block/container/shelf_{s}_{n}.json", {
                "parent": "minecraft:block/orientable",
                "textures": {"front": tex(f"shelf_{s}_books{n}"), "side": tex(f"shelf_{s}_side"), "top": tex(f"shelf_{s}_top")}})
    # remains: an old skeleton on a rotted cloak (the corpse tables' things are on it)
    bone = lambda fr, to: box(fr, to, faces("#bone", "#bone", "#bone", "#bone", [0, 0, to[0] - fr[0], to[1] - fr[1] + 1],
                                             uv_top=[fr[0], fr[2], to[0], to[2]]))
    els = [box([2, 0, 3], [15, 0.25, 13], {"up": {"texture": "#cloth", "uv": [2, 3, 15, 13]}, "down": {"texture": "#cloth", "uv": [2, 3, 15, 13], "cullface": "down"},
                                           "north": {"texture": "#cloth", "uv": [2, 0, 15, 1]}, "south": {"texture": "#cloth", "uv": [2, 0, 15, 1]},
                                           "east": {"texture": "#cloth", "uv": [3, 0, 13, 1]}, "west": {"texture": "#cloth", "uv": [3, 0, 13, 1]}}),
           bone([2.5, 0.25, 6.5], [5.5, 3.25, 9.5]),       # skull
           bone([5.5, 0.25, 7.5], [6.5, 1.25, 8.5]),       # neck
           bone([6.5, 0.25, 5.5], [10.5, 2.25, 10.5]),     # ribs
           bone([10.5, 0.25, 7.5], [11.5, 1.25, 8.5]),     # spine
           bone([11.5, 0.25, 6], [13, 1.5, 10]),           # pelvis
           bone([13, 0.25, 6.5], [16, 1, 7.5]),            # legs
           bone([13, 0.25, 8.5], [16, 1, 9.5]),
           bone([7, 0.25, 3.5], [11, 1, 4.5]),             # arms
           bone([7, 0.25, 11.5], [11, 1, 12.5])]
    write_json(f"assets/{NS}/models/block/container/remains.json", {
        "parent": "minecraft:block/block",
        "textures": {"particle": tex("remains_bone"), "bone": tex("remains_bone"), "cloth": tex("remains_cloth")},
        "elements": els})


# ============================================================================ blockstates, items, drops, names

KINDS = {  # block id -> (kind, styled, model for (style, lid) or for books)
    "loot_chest": "chest", "grand_chest": "grand", "strongbox": "strongbox", "loot_barrel": "barrel",
    "loot_sack": "sack", "loot_urn": "urn", "loot_bookshelf": "shelf", "remains": "remains",
}
NAMES = {"loot_chest": "Chest", "grand_chest": "Hoard Chest", "strongbox": "Strongbox", "loot_barrel": "Barrel",
         "loot_sack": "Sack", "loot_urn": "Urn", "loot_bookshelf": "Bookshelf", "remains": "Remains"}
STYLED = {"loot_chest", "grand_chest", "loot_urn", "loot_bookshelf"}


def model_of(block_id, style, lid):
    k = KINDS[block_id]
    if k in ("chest", "grand", "urn"):
        return f"{NS}:block/container/{k}_{style}_{lid}"
    if k in ("strongbox", "barrel", "sack"):
        return f"{NS}:block/container/{k}_{lid}"
    return f"{NS}:block/container/remains"


def blockstates():
    for bid in KINDS:
        variants = {}
        if bid == "loot_barrel":
            rot = {"up": {}, "down": {"x": 180}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "east": {"x": 90, "y": 90}, "west": {"x": 90, "y": 270}}
            for f, r in rot.items():
                for lid in LIDS:
                    variants[f"facing={f},lid={lid}"] = dict({"model": model_of(bid, None, lid)}, **r)
        elif bid == "loot_bookshelf":
            for f, y in FACINGS.items():
                for s in STYLES:
                    for n in range(4):
                        v = {"model": f"{NS}:block/container/shelf_{s}_{n}"}
                        if y:
                            v["y"] = y
                        variants[f"books={n},facing={f},style={s}"] = v
        else:
            for f, y in FACINGS.items():
                for lid in LIDS:
                    for s in (STYLES if bid in STYLED else [None]):
                        v = {"model": model_of(bid, s, lid)}
                        if y:
                            v["y"] = y
                        key = f"facing={f},lid={lid}" + (f",style={s}" if s else "")
                        variants[key] = v
        write_json(f"assets/{NS}/blockstates/{bid}.json", {"variants": variants})
        # the item looks like the closed default (nordic) block
        item_model = {"loot_bookshelf": f"{NS}:block/container/shelf_nordic_3", "remains": f"{NS}:block/container/remains"}.get(bid, model_of(bid, "nordic", "closed"))
        write_json(f"assets/{NS}/models/item/{bid}.json", {"parent": item_model})
        write_json(f"assets/{NS}/items/{bid}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{bid}"}})
        entry = {"type": "minecraft:item", "name": f"{NS}:{bid}"}
        if bid in STYLED:
            entry["functions"] = [{"function": "minecraft:copy_state", "block": f"{NS}:{bid}", "properties": ["style"]}]
        write_json(f"data/{NS}/loot_table/blocks/{bid}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}],
            "random_sequence": f"{NS}:blocks/{bid}"})


def lang():
    with open(LANG, encoding="utf-8") as f:
        data = json.load(f)
    for bid, name in NAMES.items():
        data[f"block.{NS}.{bid}"] = name
        data[f"container.{NS}.{bid}"] = name
        if bid in STYLED:
            for s in STYLES:
                data[f"container.{NS}.{bid}.{s}"] = f"{STYLE_NAMES[s]} {name}"
    with open(LANG, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    chest_textures()
    strongbox_textures()
    barrel_textures()
    sack_textures()
    urn_textures()
    shelf_textures()
    remains_textures()
    models()
    blockstates()
    lang()
    print("container art, models and blockstates written")


if __name__ == "__main__":
    main()
