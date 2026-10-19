#!/usr/bin/env python3
"""Generates the sailing module's textures (PIL), all original procedural art.

Writes PNGs into mod/src/main/resources/assets/nirncraft/textures/:
  entity/sailing/ship_atlas.png   256x256  16 px tiles for the block-built ship models (indices match
                                           sailing.client.ShipModels) plus two 64x64 sails at y = 192:
                                           x = 0 Nord red-and-white stripes, x = 64 Imperial cream sail with a dragon
  item/rowboat.png, longship.png, cog.png           16x16 item icons
  block/shipwright_bench_top.png, _side.png, _leg.png  the shipwright's bench
  gui/sailing_icons.png           64x16  map icons: anchor (port), tent (landing), ship, wreck

Tile layout (index = row * 16 + column, 16 px each):
  0 tarred planks  1 oak planks  2 deck boards  3 red planks  4 blue planks  5 cream planks  6 gilt  7 iron
  8 rope  9 mast (vertical grain)  10-12 round shields  13 dragon scales  14 eye  15 furled canvas
  16 crate face  17 barrel  18 lantern  19 black planks  20 oar blade  21 red pennant cloth  22 yard (horizontal grain)
Planks have their grain along the tile's x axis (the model maps it fore-and-aft).
Run:  python3 tools/textures/sailing.py
"""
import math
import os
import random

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures")


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, f):
    return (clamp(c[0] * f), clamp(c[1] * f), clamp(c[2] * f), c[3] if len(c) > 3 else 255)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def save(img, rel):
    path = os.path.join(TEX, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", rel)


# ---------------------------------------------------------------------------------------------------------- tiles

def planks(base, seed, rows=4, seam=0.55, grain=0.12, nails=True, weather=0.0):
    """16x16 planks running along x: `rows` boards, butt joints staggered, grain streaks, nail heads."""
    rnd = random.Random(seed)
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    h = 16 // rows
    for r in range(rows):
        tone = 1.0 + rnd.uniform(-0.12, 0.12)
        joint = rnd.randrange(3, 13)
        streaks = [rnd.uniform(-1, 1) for _ in range(h)]
        for y in range(r * h, (r + 1) * h):
            for x in range(16):
                f = tone * (1 + grain * streaks[y - r * h] * math.sin(x * 0.7 + r * 3 + streaks[y - r * h] * 2))
                f *= 1 + rnd.uniform(-0.05, 0.05)
                if y == (r + 1) * h - 1:
                    f *= seam  # dark seam under each board
                elif y == r * h:
                    f *= 1.12  # lit top edge
                if x == joint and y != (r + 1) * h - 1:
                    f *= seam + 0.1
                c = shade(base, f)
                if weather > 0:
                    c = mix(c, (150, 150, 140), weather * rnd.uniform(0.2, 1.0))
                px[x, y] = c
        if nails:
            for nx in ((joint - 2) % 16, (joint + 2) % 16):
                ny = r * h + h // 2 - (0 if h > 2 else 1)
                px[nx, ny] = shade(base, 0.35)
    return t


def pole(base, seed, vertical=True):
    rnd = random.Random(seed)
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    lines = [rnd.uniform(-1, 1) for _ in range(16)]
    for y in range(16):
        for x in range(16):
            i = x if vertical else y
            j = y if vertical else x
            f = 1 + 0.14 * lines[i] + 0.05 * math.sin(j * 0.9 + i) + rnd.uniform(-0.04, 0.04)
            if i in (0, 15):
                f *= 0.7
            px[x, y] = shade(base, f)
    return t


def solid(base, seed, noise=0.06, pattern=None):
    rnd = random.Random(seed)
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    for y in range(16):
        for x in range(16):
            f = 1 + rnd.uniform(-noise, noise)
            if pattern:
                f *= pattern(x, y)
            px[x, y] = shade(base, f)
    return t


def shield(c1, c2, rim, seed, style):
    rnd = random.Random(seed)
    t = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = t.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            d = math.hypot(dx, dy)
            if d > 7.9:
                continue
            if d > 6.6:
                c = shade(rim, 1 + rnd.uniform(-0.1, 0.1))
            else:
                a = math.atan2(dy, dx)
                if style == 0:
                    on = int((a + math.pi) / (math.pi / 2)) % 2 == 0   # quartered
                elif style == 1:
                    on = (int((a + math.pi) / (math.pi / 3)) % 2 == 0)  # six spokes
                else:
                    on = abs(dx) < 1.6 or abs(dy) < 1.6                # cross
                c = shade(c1 if on else c2, 1 + rnd.uniform(-0.08, 0.08))
                if d < 1.8:
                    c = shade(hexc("6a6a6a"), 1.2 - d * 0.15)
            px[x, y] = c
    return t


def scales(seed):
    rnd = random.Random(seed)
    base = hexc("4a3420")
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    for y in range(16):
        for x in range(16):
            row = y // 4
            cx = (x + (2 if row % 2 else 0)) % 4
            cy = y % 4
            f = 1.15 - 0.12 * cy - 0.08 * abs(cx - 1.5)
            if cy == 3:
                f *= 0.6
            c = shade(base, f * (1 + rnd.uniform(-0.06, 0.06)))
            if cy == 0 and cx in (1, 2) and rnd.random() < 0.5:
                c = hexc("b08a3a")  # gilt edges on the carving
            px[x, y] = c
    return t


def eye():
    t = Image.new("RGBA", (16, 16), hexc("2a1a10"))
    d = ImageDraw.Draw(t)
    d.ellipse([2, 3, 13, 12], fill=hexc("d8b040"))
    d.ellipse([5, 4, 10, 11], fill=hexc("b02818"))
    d.rectangle([7, 4, 8, 11], fill=hexc("1a0a06"))
    return t


def canvas(seed):
    rnd = random.Random(seed)
    base = hexc("d6c8a8")
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    for y in range(16):
        for x in range(16):
            fold = 0.85 + 0.15 * math.sin(y * 1.3 + math.sin(x * 0.4) * 1.5)
            px[x, y] = shade(base, fold * (1 + rnd.uniform(-0.04, 0.04)))
    return t


def crate(seed):
    t = planks(hexc("8a6a42"), seed, rows=4, nails=False)
    d = ImageDraw.Draw(t)
    frame = hexc("5a4228")
    d.rectangle([0, 0, 15, 15], outline=frame)
    d.rectangle([1, 1, 14, 14], outline=shade(frame, 1.2))
    d.line([2, 2, 13, 13], fill=frame, width=2)
    return t


def barrel(seed):
    rnd = random.Random(seed)
    base = hexc("7a5632")
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    for y in range(16):
        for x in range(16):
            stave = x // 3
            f = 1 + 0.1 * math.sin(stave * 2.1) + rnd.uniform(-0.05, 0.05)
            if x % 3 == 2:
                f *= 0.7
            c = shade(base, f)
            if y in (2, 3, 12, 13):
                c = shade(hexc("4a4a48"), 1 + rnd.uniform(-0.1, 0.1))
            px[x, y] = c
    return t


def lantern():
    t = Image.new("RGBA", (16, 16), hexc("2a2622"))
    d = ImageDraw.Draw(t)
    d.rectangle([2, 2, 13, 13], fill=hexc("f8c860"))
    d.rectangle([5, 4, 10, 11], fill=hexc("fff0b8"))
    d.line([7, 2, 7, 13], fill=hexc("3a3430"))
    d.line([2, 7, 13, 7], fill=hexc("3a3430"))
    return t


def rope(seed):
    rnd = random.Random(seed)
    base = hexc("a8885a")
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    for y in range(16):
        for x in range(16):
            twist = (x + y) % 4
            f = 1.15 - 0.12 * twist + rnd.uniform(-0.05, 0.05)
            px[x, y] = shade(base, f)
    return t


def blade(seed):
    t = planks(hexc("a07848"), seed, rows=2, nails=False)
    d = ImageDraw.Draw(t)
    d.rectangle([9, 0, 15, 15], fill=hexc("a8301e"))
    d.rectangle([12, 0, 13, 15], fill=hexc("e0d6b8"))
    return t


def cloth(base, seed):
    rnd = random.Random(seed)
    t = Image.new("RGBA", (16, 16))
    px = t.load()
    for y in range(16):
        for x in range(16):
            f = 1 + 0.06 * math.sin(x * 1.1 + y * 0.3) + rnd.uniform(-0.05, 0.05)
            px[x, y] = shade(base, f)
    return t


def gilt(seed):
    return solid(hexc("c89a3a"), seed, 0.08, lambda x, y: 1.15 if (x + y) % 5 == 0 else (0.85 if (x * 3 + y) % 7 == 0 else 1.0))


def iron(seed):
    return solid(hexc("4c4a48"), seed, 0.1, lambda x, y: 1.25 if (x % 5 == 2 and y % 5 == 2) else 1.0)


# ---------------------------------------------------------------------------------------------------------- sails

def sail_nord(seed):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (64, 64))
    px = img.load()
    red, white = hexc("a82a1e"), hexc("e6dcc4")
    for y in range(64):
        for x in range(64):
            stripe = (x // 8) % 2 == 0
            c = red if stripe else white
            f = 1 + rnd.uniform(-0.05, 0.05) + 0.05 * math.sin(y * 0.6 + x * 0.1)
            if y % 16 == 15:
                f *= 0.82  # horizontal seams of the cloth bolts
            if x % 8 == 0:
                f *= 0.9
            c = shade(c, f)
            # weathering towards the foot of the sail
            c = mix(c, (120, 100, 80), max(0, (y - 44) / 64.0) * rnd.uniform(0.2, 0.6))
            px[x, y] = c
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 63, 1], fill=hexc("7a6040"))   # head band on the yard
    d.rectangle([0, 62, 63, 63], fill=hexc("6a5236"))  # foot rope
    return img


def sail_imperial(seed):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (64, 64))
    px = img.load()
    linen = hexc("e2d6b8")
    for y in range(64):
        for x in range(64):
            f = 1 + rnd.uniform(-0.04, 0.04) + 0.04 * math.sin(x * 0.5)
            if y % 12 == 11:
                f *= 0.86
            px[x, y] = shade(linen, f)
    d = ImageDraw.Draw(img)
    gold, red = hexc("c89a3a"), hexc("9a2418")
    # a red roundel with a gilt ring
    d.ellipse([12, 12, 52, 52], fill=red)
    d.ellipse([12, 12, 52, 52], outline=gold, width=2)
    # an original stylized dragon: arched body, spread wing and tail curl, in gold
    body = [(22, 40), (26, 30), (32, 25), (38, 24), (43, 27), (41, 30), (36, 29), (32, 33), (30, 40), (34, 44),
            (40, 44), (38, 47), (31, 47), (26, 44)]
    d.polygon(body, fill=gold)
    wing = [(32, 28), (30, 18), (36, 22), (40, 15), (42, 23), (47, 20), (43, 29), (36, 28)]
    d.polygon(wing, fill=gold)
    d.polygon([(43, 27), (48, 26), (44, 30)], fill=gold)  # head and jaw
    d.point((42, 26), fill=red)
    d.line([(22, 40), (18, 44), (20, 48), (24, 47)], fill=gold, width=2)  # tail curl
    # red border band
    d.rectangle([0, 0, 63, 2], fill=red)
    d.rectangle([0, 61, 63, 63], fill=red)
    return img


# ---------------------------------------------------------------------------------------------------------- atlas

def atlas():
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    tiles = {
        0: planks(hexc("3e2c1e"), 1, weather=0.05),
        1: planks(hexc("8a6238"), 2),
        2: planks(hexc("b0905e"), 3, rows=4, weather=0.15),
        3: planks(hexc("9a2c20"), 4, grain=0.08),
        4: planks(hexc("2e5a7a"), 5, grain=0.08),
        5: planks(hexc("d8ccb0"), 6, grain=0.05, nails=False),
        6: gilt(7),
        7: iron(8),
        8: rope(9),
        9: pole(hexc("7a5a36"), 10, vertical=True),
        10: shield(hexc("b02818"), hexc("e0c060"), hexc("4a3a2a"), 11, 0),
        11: shield(hexc("2a4a8a"), hexc("e6dcc4"), hexc("4a3a2a"), 12, 1),
        12: shield(hexc("2e6a3a"), hexc("2a2018"), hexc("6a6a6a"), 13, 2),
        13: scales(14),
        14: eye(),
        15: canvas(15),
        16: crate(16),
        17: barrel(17),
        18: lantern(),
        19: planks(hexc("26201c"), 19, grain=0.06),
        20: blade(20),
        21: cloth(hexc("b02818"), 21),
        22: pole(hexc("7a5a36"), 22, vertical=False),
    }
    for i, t in tiles.items():
        img.paste(t, ((i % 16) * 16, (i // 16) * 16))
    img.paste(sail_nord(30), (0, 192))
    img.paste(sail_imperial(31), (64, 192))
    save(img, "entity/sailing/ship_atlas.png")


# ---------------------------------------------------------------------------------------------------------- items

def item_rowboat():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.line([(1, 4), (14, 13)], fill=hexc("8a6a42"), width=1)  # oar
    d.polygon([(0, 8), (15, 8), (13, 12), (2, 12)], fill=hexc("7a5430"))
    d.line([(0, 8), (15, 8)], fill=hexc("2e5a7a"))
    d.line([(1, 9), (14, 9)], fill=hexc("3a6a8a"))
    d.line([(3, 11), (12, 11)], fill=hexc("5a3c20"))
    d.rectangle([12, 13, 15, 14], fill=hexc("a8301e"))
    return img


def item_longship():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([7, 1, 7, 11], fill=hexc("5a4028"))
    for x in range(3, 13):
        d.line([(x, 2), (x, 8)], fill=hexc("a82a1e") if (x // 2) % 2 == 0 else hexc("e6dcc4"))
    d.polygon([(0, 10), (15, 10), (12, 14), (3, 14)], fill=hexc("3e2c1e"))
    d.line([(15, 10), (15, 7)], fill=hexc("4a3420"))
    d.point((14, 7), fill=hexc("4a3420"))
    d.line([(0, 10), (0, 8)], fill=hexc("4a3420"))
    for x in (2, 5, 8, 11):
        d.point((x, 11), fill=hexc("c8a040"))
    return img


def item_cog():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([7, 0, 7, 10], fill=hexc("5a4028"))
    d.rectangle([3, 1, 12, 8], fill=hexc("e2d6b8"))
    d.ellipse([6, 3, 9, 6], fill=hexc("9a2418"))
    d.polygon([(0, 9), (15, 9), (13, 14), (2, 14)], fill=hexc("8a6238"))
    d.rectangle([0, 7, 3, 9], fill=hexc("d8ccb0"))
    d.rectangle([12, 7, 15, 9], fill=hexc("d8ccb0"))
    d.line([(1, 11), (14, 11)], fill=hexc("c89a3a"))
    return img


# ---------------------------------------------------------------------------------------------------------- bench

def bench():
    top = planks(hexc("9a7448"), 40)
    d = ImageDraw.Draw(top)
    d.rectangle([2, 2, 12, 11], fill=hexc("e6dcc0"))  # a ship plan pinned to the bench
    d.line([(3, 8), (11, 8)], fill=hexc("3a4a7a"))
    d.line([(4, 9), (10, 9)], fill=hexc("3a4a7a"))
    d.line([(7, 3), (7, 8)], fill=hexc("3a4a7a"))
    d.line([(5, 4), (9, 4)], fill=hexc("3a4a7a"))
    d.point((12, 2), fill=hexc("6a6a6a"))
    d.line([(13, 12), (15, 14)], fill=hexc("4a4a48"))  # dividers
    save(top, "block/shipwright_bench_top.png")
    side = planks(hexc("8a6238"), 41)
    ds = ImageDraw.Draw(side)
    ds.rectangle([0, 0, 15, 3], fill=hexc("6a4a2a"))
    ds.line([(3, 6), (12, 6)], fill=hexc("9a9a96"))  # a saw on its hooks
    ds.polygon([(3, 6), (12, 6), (12, 9), (4, 8)], fill=hexc("8a8a86"))
    ds.rectangle([1, 5, 3, 9], fill=hexc("5a3c20"))
    save(side, "block/shipwright_bench_side.png")
    save(pole(hexc("6a4a2a"), 42, vertical=True), "block/shipwright_bench_leg.png")


# ---------------------------------------------------------------------------------------------------------- map icons

def icons():
    img = Image.new("RGBA", (64, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ink, rim = hexc("1e3a50"), hexc("e8dcc0")
    # anchor
    for col, w in ((rim, 3), (ink, 1)):
        d.line([(7, 2), (7, 13)], fill=col, width=w)
        d.line([(4, 5), (10, 5)], fill=col, width=w)
        d.arc([2, 7, 12, 15], 20, 160, fill=col, width=w)
    d.ellipse([5, 0, 9, 4], outline=ink)
    # tent (landing)
    d.polygon([(16 + 1, 14), (16 + 8, 2), (16 + 15, 14)], fill=hexc("7a3a2e"), outline=rim)
    d.polygon([(16 + 6, 14), (16 + 8, 8), (16 + 10, 14)], fill=hexc("2a1a10"))
    # ship
    d.polygon([(32 + 1, 10), (32 + 15, 10), (32 + 12, 14), (32 + 3, 14)], fill=hexc("5a3c20"), outline=rim)
    d.line([(32 + 8, 1), (32 + 8, 10)], fill=ink)
    d.rectangle([32 + 4, 2, 32 + 12, 8], fill=hexc("e2d6b8"))
    # wreck
    d.polygon([(48 + 2, 12), (48 + 14, 9), (48 + 13, 13), (48 + 3, 15)], fill=hexc("4a3420"), outline=rim)
    d.line([(48 + 6, 11), (48 + 11, 3)], fill=ink, width=2)
    save(img, "gui/sailing_icons.png")


def main():
    atlas()
    save(item_rowboat(), "item/rowboat.png")
    save(item_longship(), "item/longship.png")
    save(item_cog(), "item/cog.png")
    bench()
    icons()


if __name__ == "__main__":
    main()
