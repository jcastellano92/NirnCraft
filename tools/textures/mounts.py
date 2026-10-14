"""Textures of the mount system (all original, procedural):

* Ridden beasts (rig models of the bestiary, layouts in tools/textures/rigs/<rig>.json): saddled and barded skins
  painted over the beasts' own textures, plus the giant wolf's timber coat, the Akaviri tiger and the tamed grazer.
  Saddle: a cloth blanket and leather seat on the torso's back, girth straps and saddlebags on the flanks. Barding:
  plate lames or quilted cloth over torso, chest and neck, a chanfron on the face.
* Horse barding (vanilla horse model, 64x64 armour layer): Nordic, Imperial, Breton, Redguard and Elven, painted
  over the horse model's box layout.
* Items (16x16): the bardings, beast barding, marsh-shoes, Shadowmere's bridle, Arvak's skull; the marsh-shoes
  armour layer.

Run from tools/textures:  py -3 mounts.py
"""
import colorsys
import json
import math
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft")
CREATURES = os.path.join(ASSETS, "textures", "entity", "creatures")
MOUNTS = os.path.join(ASSETS, "textures", "entity", "mounts")
ITEMS = os.path.join(ASSETS, "textures", "item")
ARMOR = os.path.join(ASSETS, "textures", "models", "armor")
MODELS = os.path.join(ASSETS, "models", "item")
RIGS = os.path.join(HERE, "rigs")


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, mul):
    return tuple(clamp(x * mul) for x in c[:3])


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def noise(x, y, s):
    n = (x * 374761393 + y * 668265263 + s * 2246822519) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def faces(b):
    u, v, w, h, d = b["u"], b["v"], b["w"], b["h"], b["d"]
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def load_rig(name):
    with open(os.path.join(RIGS, name + ".json"), encoding="utf-8") as f:
        return json.load(f)


def boxes(lay, tags):
    return [b for b in lay["boxes"] if b.get("tag") in tags]


# ------------------------------------------------------------------------------------------------ materials


def leather(x, y, s, base):
    n = noise(x, y, s)
    return shade(base, 0.86 + 0.22 * n)


def cloth(x, y, s, base, weave=True):
    n = noise(x // 1, y // 1, s)
    m = 0.9 + 0.14 * n
    if weave and (x + y) % 2 == 0:
        m -= 0.05
    return shade(base, m)


def plate(x, y, s, base, rows=3):
    """Overlapping lames: a bright upper edge, a shadow at the lower edge, rivets."""
    r = y % rows
    m = 1.15 if r == 0 else (0.72 if r == rows - 1 else 0.95)
    m += (noise(x, y, s) - 0.5) * 0.08
    c = shade(base, m)
    if r == 1 and x % 4 == 1:
        c = shade(base, 1.35)
    return c


def scales(x, y, s, base):
    row = y // 2
    off = (row % 2) * 1
    edge = (x + off) % 3 == 0 or y % 2 == 1
    return shade(base, (0.78 if edge else 1.08) + (noise(x, y, s) - 0.5) * 0.1)


# ------------------------------------------------------------------------------------------------ beast skins


class Skin:
    def __init__(self, img):
        self.img = img
        self.px = img.load()

    def put(self, x, y, c, a=255):
        if 0 <= x < self.img.width and 0 <= y < self.img.height:
            self.px[x, y] = (c[0], c[1], c[2], a)

    def get(self, x, y):
        return self.px[x, y]

    def face_fill(self, b, face, fn):
        u, v, w, h = faces(b)[face]
        for y in range(h):
            for x in range(w):
                c = fn(x, y, w, h)
                if c is not None:
                    self.put(u + x, v + y, c)


def saddle_on(skin, lay, style, seed):
    """A saddle blanket and leather seat on the torso, girth straps, saddlebags on the rear flanks."""
    blanket, trim, seat, bag = style
    for b in boxes(lay, ("torso",)):
        u, v, w, d = faces(b)["top"]
        # top: blanket (wide) with a leather seat on it, from 30% to 70% of the length
        z0, z1 = int(d * 0.28), int(d * 0.72)
        for z in range(z0, z1):
            for x in range(w):
                edge = z in (z0, z1 - 1) or x in (0, w - 1)
                c = trim if edge else cloth(x, z, seed, blanket)
                inner = 1 < x < w - 2 and z0 + 1 < z < z1 - 2
                if inner:
                    c = leather(x, z, seed + 1, seat)
                    if z == z0 + 2 or z == z1 - 3:
                        c = shade(seat, 1.25)  # pommel and cantle
                skin.put(u + x, v + z, c)
        for face in ("right", "left"):
            u, v, fw, fh = faces(b)[face]
            # the face runs along the body (fw = length); the blanket hangs down the upper flank
            drop = max(2, int(fh * 0.55))
            for x in range(fw):
                zz = x if face == "left" else fw - 1 - x
                if not (z0 <= zz < z1):
                    continue
                for y in range(drop):
                    c = trim if (y == drop - 1 or zz in (z0, z1 - 1)) else cloth(x, y, seed, blanket)
                    skin.put(u + x, v + y, c)
            # girth strap round the belly
            gz = (z0 + z1) // 2
            gx = gz if face == "left" else fw - 1 - gz
            for y in range(fh):
                skin.put(u + gx, v + y, shade(seat, 0.75))
            # a saddlebag behind the seat
            bz0, bz1 = z1, min(fw, z1 + max(3, fw // 6))
            for zz in range(bz0, bz1):
                x = zz if face == "left" else fw - 1 - zz
                for y in range(1, min(fh, drop + 2)):
                    c = leather(x, y, seed + 7, bag)
                    if y in (1,) or zz in (bz0, bz1 - 1):
                        c = shade(bag, 0.7)
                    skin.put(u + x, v + y, c)
        # under the belly: the girth
        u, v, w, d = faces(b)["bottom"]
        gz = (z0 + z1) // 2
        for x in range(w):
            skin.put(u + x, v + gz, shade(seat, 0.75))
    # a bridle on the head
    for b in boxes(lay, ("head", "snout")):
        for face in ("right", "left"):
            u, v, fw, fh = faces(b)[face]
            y = fh // 2
            for x in range(fw):
                skin.put(u + x, v + y, shade(seat, 0.6))


def barding_on(skin, lay, style, seed, kind):
    """Plate (or quilted cloth) over torso, chest and neck, a chanfron on the head."""
    metal, cloth_c, trim = style
    saddle_on(skin, lay, (cloth_c, trim, shade(metal, 0.55), shade(cloth_c, 0.7)), seed)
    for b in boxes(lay, ("torso", "chest", "neck", "belly", "hump", "throat")):
        for face in ("right", "left", "front", "back", "top"):
            u, v, w, h = faces(b)[face]
            for y in range(h):
                for x in range(w):
                    if face == "top" and b.get("tag") == "torso":
                        continue  # the saddle stays visible
                    if face in ("right", "left") and y >= h - 1:
                        c = trim
                    elif kind == "scale":
                        c = scales(x, y, seed, metal)
                    elif kind == "cloth":
                        c = cloth(x, y, seed, cloth_c)
                        if (x // 3 + y // 3) % 2 == 0:
                            c = shade(c, 0.88)
                    else:
                        c = plate(x, y, seed, metal)
                    skin.put(u + x, v + y, c)
    for b in boxes(lay, ("head",)):
        u, v, w, h = faces(b)["front"]
        # chanfron: the middle of the face, eyes left clear
        for y in range(h):
            for x in range(w):
                if x in (0, w - 1) and y < h - 1:
                    continue
                if y == h // 2 and x in (1, w - 2):
                    continue
                skin.put(u + x, v + y, plate(x, y, seed + 3, metal, rows=2))
        u, v, w, d = faces(b)["top"]
        for y in range(d):
            for x in range(1, w - 1):
                skin.put(u + x, v + y, plate(x, y, seed + 4, metal, rows=2))
        mid = w // 2
        u2, v2, w2, h2 = faces(b)["front"]
        skin.put(u2 + mid, v2, shade(trim, 1.2))


def recolor(img, fn):
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            nr, ng, nb = fn(r, g, b, x, y)
            px[x, y] = (clamp(nr), clamp(ng), clamp(nb), a)
    return out


def hue(img, dh, ds=1.0, dv=1.0, only=None):
    def f(r, g, b, x, y):
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if only is not None and not only(h, s, v):
            return r, g, b
        h = (h + dh) % 1.0
        s = max(0, min(1, s * ds))
        v = max(0, min(1, v * dv))
        rr, gg, bb = colorsys.hsv_to_rgb(h, s, v)
        return rr * 255, gg * 255, bb * 255
    return recolor(img, f)


def tint(img, color, amount):
    def f(r, g, b, x, y):
        lum = (r * 0.3 + g * 0.59 + b * 0.11) / 255
        tc = shade(color, 0.55 + lum * 0.9)
        return mix((r, g, b), tc, amount)
    return recolor(img, f)


def beast(rig, base_png, out, saddle_style, barding_style, barding_kind, seed, base_fn=None, base_out=None):
    lay = load_rig(rig)
    base = Image.open(os.path.join(CREATURES, base_png)).convert("RGBA")
    if base_fn is not None:
        base = base_fn(base)
    if base_out:
        base.save(os.path.join(CREATURES, base_out + ".png"))
    s = Skin(base.copy())
    saddle_on(s, lay, saddle_style, seed)
    s.img.save(os.path.join(CREATURES, out + "_saddled.png"))
    s = Skin(base.copy())
    barding_on(s, lay, barding_style, seed, barding_kind)
    s.img.save(os.path.join(CREATURES, out + "_barded.png"))
    print("painted", out)


SADDLE_BROWN = hexc("#6a4426")
DARK_LEATHER = hexc("#3e2716")


def beasts():
    # Elsweyr: orange-and-gold Khajiit blanket, bronze scale barding
    beast("senche_tiger", "senche_tiger.png", "senche_mount",
          (hexc("#b8541c"), hexc("#e8c050"), SADDLE_BROWN, DARK_LEATHER),
          (hexc("#a8783a"), hexc("#a83c1c"), hexc("#e8c050")), "scale", 11)
    # Valenwood: a timber wolf grown huge, a green Bosmer blanket, bone-and-hide barding
    beast("wolf", "wolf.png", "giant_wolf",
          (hexc("#3c6a2a"), hexc("#c8b080"), SADDLE_BROWN, DARK_LEATHER),
          (hexc("#d8ccb0"), hexc("#4a6a30"), hexc("#7a5a30")), "plate", 12,
          base_fn=lambda im: tint(im, hexc("#6a5038"), 0.35), base_out="giant_wolf")
    # Summerset: white and gold, glassy elven plate
    beast("indrik", "indrik.png", "indrik",
          (hexc("#e8e0c8"), hexc("#d8b040"), hexc("#8a6a3a"), hexc("#6a4a2a")),
          (hexc("#c8d870"), hexc("#f0ece0"), hexc("#d8b040")), "plate", 13)
    # Atmora: a broad Nord riding platform, iron-and-fur barding
    beast("mammoth", "mammoth.png", "mammoth",
          (hexc("#4a5a7a"), hexc("#d8d0c0"), SADDLE_BROWN, DARK_LEATHER),
          (hexc("#8a9098"), hexc("#3a4a6a"), hexc("#c8c0b0")), "plate", 14)
    # Akavir: a darker, redder tiger with heavy stripes, lacquered red-and-black Akaviri barding
    beast("senche_tiger", "senche_tiger.png", "akaviri_tiger",
          (hexc("#202020"), hexc("#c02a20"), hexc("#5a2a18"), hexc("#2a1810")),
          (hexc("#7a1a14"), hexc("#1a1a1a"), hexc("#d8a838")), "plate", 15,
          base_fn=lambda im: hue(im, -0.025, 1.15, 0.92), base_out="akaviri_tiger")
    # the endless lands: a dun grazer, a patterned nomad blanket, hide barding
    beast("realm_grazer", "realm_grazer.png", "realm_grazer_mount",
          (hexc("#8a3a5a"), hexc("#e0c070"), SADDLE_BROWN, DARK_LEATHER),
          (hexc("#9a8060"), hexc("#6a3a5a"), hexc("#e0c070")), "cloth", 16,
          base_fn=lambda im: tint(im, hexc("#9a7a52"), 0.75), base_out="realm_grazer_mount")


# ------------------------------------------------------------------------------------------------ horse barding

# the vanilla horse model's boxes (texture offsets and sizes), for the 64x64 armour layer
HORSE_BOXES = {
    "body": dict(u=0, v=32, w=10, h=10, d=22),
    "neck": dict(u=0, v=35, w=4, h=12, d=7),
    "head": dict(u=0, v=13, w=6, h=5, d=7),
    "mouth": dict(u=0, v=25, w=4, h=5, d=5),
    "leg": dict(u=48, v=21, w=4, h=11, d=4),
    "tail": dict(u=42, v=36, w=3, h=14, d=4),
}

HORSE_STYLES = {
    # metal, cloth, trim, kind
    "nordic_barding": (hexc("#7a8088"), hexc("#3e5068"), hexc("#8a6a48"), "plate"),
    "imperial_barding": (hexc("#a8acb4"), hexc("#8a1c1c"), hexc("#d8b048"), "plate"),
    "breton_barding": (hexc("#b8bcc4"), hexc("#2a4a9a"), hexc("#e8d8a8"), "caparison"),
    "redguard_barding": (hexc("#b08848"), hexc("#a82a1c"), hexc("#e8c868"), "scale"),
    "elven_barding": (hexc("#c8b048"), hexc("#e8e4d4"), hexc("#7aa848"), "plate"),
    # the smithing ladder (leather to ebony), plain cloth under the plate
    "leather_barding": (hexc("#7a5230"), hexc("#4a3420"), hexc("#c8a060"), "plate"),
    "iron_barding": (hexc("#8e9298"), hexc("#3a3c40"), hexc("#6a6e74"), "plate"),
    "steel_barding": (hexc("#b4bac4"), hexc("#2c3440"), hexc("#d8dce4"), "plate"),
    "dwarven_barding": (hexc("#b89850"), hexc("#4a3a20"), hexc("#e8c868"), "scale"),
    "orcish_barding": (hexc("#5a7a48"), hexc("#2a3a22"), hexc("#a8b890"), "plate"),
    "ebony_barding": (hexc("#2a2c34"), hexc("#16161c"), hexc("#7a6aa8"), "plate"),
}


def horse_barding(name, style, seed):
    metal, cloth_c, trim, kind = style
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    s = Skin(img)

    def body_px(face, x, y, w, h):
        if face in ("right", "left"):
            if y >= h - 2:
                return trim if y == h - 2 else shade(cloth_c, 0.8)
            if kind == "caparison":
                # quartered heraldic cloth with a white cross band
                q = ((x * 2) // w + (y * 2) // max(1, h - 2)) % 2
                c = cloth(x, y, seed, cloth_c if q == 0 else hexc("#e8e4dc"))
                return c
            if kind == "scale":
                return scales(x, y, seed, metal) if y < h // 2 else cloth(x, y, seed, cloth_c)
            return plate(x, y, seed, metal) if y < h // 2 + 1 else cloth(x, y, seed, cloth_c)
        if face == "top":
            mid = w // 2
            if abs(x - mid) <= 1 and 7 <= y <= 14:
                return leather(x, y, seed, hexc("#5a3a20"))
            return cloth(x, y, seed, cloth_c) if kind == "caparison" else plate(y, x, seed, metal)
        if face == "front":
            return plate(x, y, seed + 2, metal, rows=2) if kind != "caparison" else cloth(x, y, seed, cloth_c)
        if face == "back":
            return cloth(x, y, seed, cloth_c)
        return None

    s.face_fill(HORSE_BOXES["body"], "right", lambda x, y, w, h: body_px("right", x, y, w, h))
    s.face_fill(HORSE_BOXES["body"], "left", lambda x, y, w, h: body_px("left", x, y, w, h))
    s.face_fill(HORSE_BOXES["body"], "top", lambda x, y, w, h: body_px("top", x, y, w, h))
    s.face_fill(HORSE_BOXES["body"], "front", lambda x, y, w, h: body_px("front", x, y, w, h))
    s.face_fill(HORSE_BOXES["body"], "back", lambda x, y, w, h: body_px("back", x, y, w, h))
    s.face_fill(HORSE_BOXES["body"], "bottom", lambda x, y, w, h: cloth(x, y, seed, shade(cloth_c, 0.8)))
    # crinet: plates down the neck
    for f in ("right", "left", "front", "top", "back"):
        s.face_fill(HORSE_BOXES["neck"], f, lambda x, y, w, h: plate(x, y, seed + 5, metal, rows=2)
                    if kind != "caparison" else cloth(x, y, seed, cloth_c))
    # chanfron on the head and nose
    for f in ("top", "front"):
        s.face_fill(HORSE_BOXES["head"], f, lambda x, y, w, h: plate(x, y, seed + 6, metal, rows=2))
    for f in ("right", "left"):
        s.face_fill(HORSE_BOXES["head"], f, lambda x, y, w, h: plate(x, y, seed + 6, metal, rows=2) if y < 2 else None)
    for f in ("top", "front"):
        s.face_fill(HORSE_BOXES["mouth"], f, lambda x, y, w, h: plate(x, y, seed + 7, metal, rows=2) if y < h - 1 else None)
    # the caparison hem over the upper legs
    for f in ("right", "front", "left", "back"):
        s.face_fill(HORSE_BOXES["leg"], f, lambda x, y, w, h: (trim if y == 2 else cloth(x, y, seed, cloth_c)) if y < 3 else None)
    os.makedirs(MOUNTS, exist_ok=True)
    img.save(os.path.join(MOUNTS, "horse_armor_" + name + ".png"))
    print("painted horse barding", name)


# ------------------------------------------------------------------------------------------------ items


def icon(name, rows, palette):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in palette:
                px[x, y] = palette[ch] + (255,)
    os.makedirs(ITEMS, exist_ok=True)
    img.save(os.path.join(ITEMS, name + ".png"))
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "skycraft:item/" + name}}, f, indent=2)
        f.write("\n")


BARDING_ICON = [
    "................",
    "....mmmmmmmm....",
    "...mMMMMMMMMm...",
    "..mMmmmmmmmmMm..",
    "..mmMMMMMMMMmm..",
    "..cccccccccccc..",
    ".cCCCCCCCCCCCCc.",
    ".cCcccccccccCCc.",
    ".cCCCCCCCCCCCCc.",
    ".cCCCCtCCCCCCCc.",
    ".cCCCtttCCCCCCc.",
    ".cCCCCtCCCCCCCc.",
    ".cCCCCCCCCCCCCc.",
    ".tttttttttttttt.",
    ".t.t.t.t.t.t.t..",
    "................",
]

SHOES_ICON = [
    "................",
    "................",
    "..aa............",
    ".aAAa...........",
    ".aAAAa....aa....",
    ".aAaAAa..aAAa...",
    "..aAAaAa.aAAAa..",
    "...aAAAAaaAaAAa.",
    "....aAaAAaaAAaAa",
    ".....aAAAAaaAAAa",
    "......aaaaa.aaaa",
    "...........aAAa.",
    "............aa..",
    "................",
    "................",
    "................",
]

BRIDLE_ICON = [
    "................",
    "......kkkk......",
    ".....k....k.....",
    "....k......k....",
    "...k..kkkk..k...",
    "...k.k....k.k...",
    "...kk......kk...",
    "...k........k...",
    "...kmm....mmk...",
    "...k.rr..rr.k...",
    "...k..rRRr..k...",
    "....k..rr..k....",
    ".....k....k.....",
    "......kmmk......",
    ".......kk.......",
    "................",
]

SKULL_ICON = [
    "................",
    "....bbbbb.......",
    "...bBBBBBbb.....",
    "..bBBBBBBBBb....",
    "..bBeBBBBBBBb...",
    "..bBEeBBBBBBBb..",
    "..bBBBBBBBBBBBb.",
    "...bBBBBBBBBBBBb",
    "....bbBBBBBBBBBb",
    "......bBBBBnBBb.",
    ".......bBBBBBb..",
    "........bbtbtb..",
    ".........t.t.t..",
    "................",
    "................",
    "................",
]


SADDLE_ICON = [
    "................",
    "................",
    "......ssss......",
    ".....sSSSSs.....",
    "....sSSSSSSs....",
    "..bbsSSSSSSsbb..",
    ".bBBsSSttSSsBBb.",
    ".bBBssSSSSssBBb.",
    ".bBBb.ssss.bBBb.",
    ".bBBb.t..t.bBBb.",
    "..bb..t..t..bb..",
    "......t..t......",
    ".....tt..tt.....",
    "................",
    "................",
    "................",
]


def saddles():
    # pack: brown with big panniers; war: dark leather, steel studs; noble: crimson with gold
    pal = {
        "pack_saddle": {"s": hexc("#5a3a1e"), "S": hexc("#8a5e34"), "b": hexc("#3a2a14"), "B": hexc("#a07a48"), "t": hexc("#2a1c0e")},
        "war_saddle": {"s": hexc("#22201e"), "S": hexc("#46423c"), "b": hexc("#8a8e96"), "B": hexc("#4a4e56"), "t": hexc("#b0b4bc")},
        "noble_saddle": {"s": hexc("#5a1018"), "S": hexc("#9a1c2a"), "b": hexc("#b8962e"), "B": hexc("#8a1824"), "t": hexc("#e8c860")},
    }
    for name, p in pal.items():
        icon(name, SADDLE_ICON, p)
    print("painted saddles")


def items():
    saddles()
    for name, (metal, cloth_c, trim, kind) in HORSE_STYLES.items():
        icon(name, BARDING_ICON, {"m": shade(metal, 0.7), "M": metal, "c": shade(cloth_c, 0.7), "C": cloth_c, "t": trim})
    icon("beast_barding", BARDING_ICON, {"m": hexc("#5a4a3a"), "M": hexc("#9a8060"), "c": hexc("#4a2a18"),
                                         "C": hexc("#7a4a28"), "t": hexc("#c8a868")})
    icon("marsh_shoes", SHOES_ICON, {"a": hexc("#4a5a2a"), "A": hexc("#8a9a4a")})
    icon("shadowmere_bridle", BRIDLE_ICON, {"k": hexc("#1a1414"), "m": hexc("#8a8a90"), "r": hexc("#7a0e0e"), "R": hexc("#e83030")})
    icon("arvaks_skull", SKULL_ICON, {"b": hexc("#8a8270"), "B": hexc("#d8d0b8"), "e": hexc("#2a6ad8"), "E": hexc("#9adcff"),
                                      "n": hexc("#3a3428"), "t": hexc("#ece4cc")})
    # marsh-shoes worn: woven reed over the lower leg (the armour layer's leg boxes)
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    s = Skin(img)
    leg = dict(u=0, v=16, w=4, h=12, d=4)
    for f in ("right", "front", "left", "back"):
        s.face_fill(leg, f, lambda x, y, w, h: (shade(hexc("#6a7a3a"), 0.8 + 0.3 * ((x + y) % 2)) if y >= h - 4 else None))
    s.face_fill(leg, "bottom", lambda x, y, w, h: hexc("#3a4a1a"))
    os.makedirs(ARMOR, exist_ok=True)
    img.save(os.path.join(ARMOR, "marsh_shoes_layer_1.png"))
    Image.new("RGBA", (64, 32), (0, 0, 0, 0)).save(os.path.join(ARMOR, "marsh_shoes_layer_2.png"))
    print("painted items")


def main():
    random.seed(7)
    beasts()
    for i, (name, style) in enumerate(HORSE_STYLES.items()):
        horse_barding(name, style, 30 + i)
    items()


if __name__ == "__main__":
    main()
