#!/usr/bin/env python3
"""Blocks of Nirn's biomes (workstream B): original procedural 16x16 textures plus every block asset.

Writes:
  * PNGs into mod/src/main/resources/assets/skycraft/textures/block/
  * blockstates, block and item models into assets/skycraft/{blockstates,models}/
  * loot tables into data/skycraft/loot_tables/blocks/
  * recipes into data/skycraft/recipes/ (planks, wood, ash)
  * block/item tag entries (logs, leaves, planks, mineable, base stone, digging tags), merged into existing files
Block names live in mod/src/main/lang/skycraft/tamriel_biomes.json (written by tools/worldgen/biomes.py).

Run from anywhere:  python3 tools/textures/tamriel.py
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "skycraft")
DATA = os.path.join(RES, "data")
TEX = os.path.join(ASSETS, "textures", "block")
SK = "skycraft"


# ------------------------------------------------------------------------------------------------ helpers
def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


def new(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def save(name, img):
    os.makedirs(TEX, exist_ok=True)
    img.save(os.path.join(TEX, name + ".png"))


class Noise:
    """Tileable value noise on a 16x16 torus."""

    def __init__(self, seed, cell=4):
        r = random.Random(seed)
        self.cell = cell
        self.n = 16 // cell
        self.g = [[r.random() for _ in range(self.n)] for _ in range(self.n)]

    def at(self, x, y):
        c = self.cell
        gx, gy = x / c, y / c
        x0, y0 = int(math.floor(gx)) % self.n, int(math.floor(gy)) % self.n
        x1, y1 = (x0 + 1) % self.n, (y0 + 1) % self.n
        fx, fy = gx - math.floor(gx), gy - math.floor(gy)
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        a = self.g[y0][x0] + (self.g[y0][x1] - self.g[y0][x0]) * fx
        b = self.g[y1][x0] + (self.g[y1][x1] - self.g[y1][x0]) * fx
        return a + (b - a) * fy


def palette_pick(pal, v):
    """pal: list of colours dark -> light; v in [0,1]."""
    i = int(max(0.0, min(0.999, v)) * len(pal))
    return pal[i]


def speckled(seed, pal, cell=4, grain=0.35, bias=0.0):
    """Stone/sand-like: smooth clumps plus per-pixel grain, quantised to a small palette (vanilla style)."""
    r = random.Random(seed)
    n1, n2 = Noise(seed, cell), Noise(seed + 1, 2)
    img = new()
    for y in range(16):
        for x in range(16):
            v = n1.at(x, y) * 0.55 + n2.at(x, y) * 0.25 + (r.random() - 0.5) * grain + 0.1 + bias
            img.putpixel((x, y), palette_pick(pal, v))
    return img


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), c)


# ---------------------------------------------------------------------------------------------- textures
def ash_block():
    pal = [hexc("4E4945"), hexc("5C5652"), hexc("6B6560"), hexc("78726C"), hexc("858079")]
    img = speckled(11, pal, cell=4, grain=0.6)
    r = random.Random(12)
    for _ in range(7):  # cinders and pale pumice grains
        put(img, r.randrange(16), r.randrange(16), hexc("2E2A28") if r.random() < 0.6 else hexc("9A948C"))
    return img


def volcanic_rock(seed=21):
    pal = [hexc("1F1B1A"), hexc("2B2624"), hexc("36302D"), hexc("413A36"), hexc("4D4540")]
    img = speckled(seed, pal, cell=4, grain=0.3)
    r = random.Random(seed + 1)
    for _ in range(9):  # vesicles: dark pits with a lit lower rim
        x, y = r.randrange(16), r.randrange(16)
        put(img, x, y, hexc("141110"))
        put(img, x, (y + 1) % 16, hexc("5A504A"))
    return img


def cracks(img, seed, color, count=4, length=9, glow=None):
    """Meandering cracks: mostly sideways, stepping up or down now and then (tileable)."""
    r = random.Random(seed)
    for _ in range(count):
        x, y = r.randrange(16), r.randrange(16)
        horizontal = r.random() < 0.5
        for _ in range(length):
            put(img, x % 16, y % 16, color)
            if glow:
                for gx, gy in ((0, 1), (0, -1)) if horizontal else ((1, 0), (-1, 0)):
                    if r.random() < 0.45:
                        p = img.getpixel(((x + gx) % 16, (y + gy) % 16))
                        if p != color:
                            put(img, (x + gx) % 16, (y + gy) % 16, glow)
            if horizontal:
                x += 1
                if r.random() < 0.3:
                    y += r.choice((-1, 1))
            else:
                y += 1
                if r.random() < 0.3:
                    x += r.choice((-1, 1))
    return img


def cracked_volcanic_rock():
    img = volcanic_rock(31)
    return cracks(img, 32, hexc("110E0D"), count=5, length=10)


def magma_volcanic_rock():
    frames = []
    for f, (hot, warm) in enumerate([(hexc("FFB43C"), hexc("C8501E")), (hexc("FFD25A"), hexc("E0642A")),
                                     (hexc("F09A30"), hexc("B44418"))]):
        img = volcanic_rock(41)
        cracks(img, 42, hot, count=5, length=10, glow=warm)
        frames.append(img)
    sheet = new(16, 48)
    for i, fr in enumerate(frames):
        sheet.paste(fr, (0, 16 * i))
    return sheet


def leaves_texture(seed, pal, holes=0.18, snow=None):
    """Vanilla-style leaves: clumps of 2-3 shades with irregular see-through gaps; snow dusting optional."""
    r = random.Random(seed)
    n = Noise(seed, 4)
    gaps = Noise(seed + 5, 2)
    img = new()
    for y in range(16):
        for x in range(16):
            v = n.at(x, y)
            if gaps.at(x, y) < holes + 0.12 and r.random() < 0.85:
                continue
            c = palette_pick(pal, v * 0.75 + r.random() * 0.4)
            img.putpixel((x, y), c)
    # leaf outlines: darken pixels whose upper-left neighbour is a gap
    out = img.copy()
    for y in range(16):
        for x in range(16):
            p = img.getpixel((x, y))
            if p[3] and img.getpixel(((x - 1) % 16, (y - 1) % 16))[3] == 0:
                out.putpixel((x, y), shade(p, 0.8))
    if snow:
        for y in range(16):
            for x in range(16):
                p = out.getpixel((x, y))
                above = out.getpixel((x, (y - 1) % 16))[3] == 0
                if p[3] and ((above and r.random() < 0.8) or r.random() < 0.04):
                    out.putpixel((x, y), snow[r.randrange(len(snow))])
    return out


def bark(seed, pal, moss=None, moss_amount=0.18):
    """Vertical ridged bark: each column a ridge or a groove, with breaks; a little moss or sap optional."""
    r = random.Random(seed)
    img = new()
    x = 0
    ridge = []
    while len(ridge) < 16:
        w = r.randint(2, 4)
        level = r.uniform(0.45, 0.9)
        ridge += [level] * w + [0.05]
    ridge = ridge[:16]
    for x in range(16):
        for y in range(16):
            v = ridge[x] + (r.random() - 0.5) * 0.3
            img.putpixel((x, y), palette_pick(pal, v))
    for _ in range(6):  # breaks across the ridges
        x, y = r.randrange(16), r.randrange(16)
        for i in range(r.randint(1, 2)):
            put(img, (x + i) % 16, y, pal[0])
    if moss:
        n = Noise(seed + 7, 4)
        for y in range(16):
            for x in range(16):
                if n.at(x, y) > 1.0 - moss_amount and r.random() < 0.8:
                    img.putpixel((x, y), moss[r.randrange(len(moss))])
    return img


def log_top(seed, bark_pal, ring_pal):
    img = new()
    r = random.Random(seed)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 7.0:
                img.putpixel((x, y), palette_pick(bark_pal, r.random() * 0.7 + 0.15))
            else:
                ring = (d + r.random() * 0.6) % 2.0
                img.putpixel((x, y), ring_pal[0] if ring < 0.7 else palette_pick(ring_pal[1:], 0.5 + (r.random() - 0.5) * 0.6))
    return img


def planks(seed, pal):
    r = random.Random(seed)
    img = new()
    for board in range(4):
        y0 = board * 4
        offset = r.randrange(16)
        for y in range(y0, y0 + 4):
            for x in range(16):
                grain = math.sin((x + offset) * 0.9 + board) * 0.15
                v = 0.55 + grain + (r.random() - 0.5) * 0.2
                if y == y0 + 3:
                    v = 0.0
                img.putpixel((x, y), palette_pick(pal, v))
        seam = (offset + 8) % 16
        for y in range(y0, y0 + 3):
            put(img, seam, y, pal[1])
    return img


def stem_side(seed):
    r = random.Random(seed)
    pal = [hexc("A69A84"), hexc("BDB199"), hexc("CFC4AC"), hexc("DED5BF")]
    img = new()
    for x in range(16):
        b = r.random()
        for y in range(16):
            v = 0.35 + b * 0.4 + (r.random() - 0.5) * 0.25 - (0.3 if x % 5 == 0 else 0)
            img.putpixel((x, y), palette_pick(pal, v))
    return img


def stem_top(seed):
    img = new()
    r = random.Random(seed)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            c = hexc("A69A84") if d > 6.8 else mix(hexc("E4DCC8"), hexc("C9BEA6"), d / 7 + r.random() * 0.15)
            img.putpixel((x, y), c)
    return img


def cap(seed, pal, spot, spots=7):
    img = speckled(seed, pal, cell=4, grain=0.3)
    r = random.Random(seed + 3)
    for _ in range(spots):
        x, y = r.randrange(16), r.randrange(16)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            if r.random() < 0.85:
                put(img, (x + dx) % 16, (y + dy) % 16, spot)
    return img


def palm_side():
    pal = [hexc("6E5636"), hexc("86693F"), hexc("9C7D4C"), hexc("B0915A")]
    img = new()
    r = random.Random(61)
    for y in range(16):
        for x in range(16):
            # diamond-scale pattern of old frond bases
            v = 0.5 + 0.35 * math.sin((x + (y // 4) * 2) * math.pi / 4) + (r.random() - 0.5) * 0.2
            if y % 4 == 3:
                v -= 0.45
            img.putpixel((x, y), palette_pick(pal, v))
    return img


def fronds():
    pal = [hexc("2F6B1E"), hexc("3D8526"), hexc("4F9C2F"), hexc("63B23A")]
    img = new()
    r = random.Random(71)
    for y in range(16):
        for x in range(16):
            # long diagonal leaflets with gaps between them
            band = (x + y) % 5
            if band == 4 or (band == 3 and r.random() < 0.5):
                continue
            img.putpixel((x, y), palette_pick(pal, band / 4 + (r.random() - 0.5) * 0.3))
    return img


def crystal_block():
    """Translucent crystal: facets (nearest of a few seed points on the torus), lit edges between them."""
    r = random.Random(81)
    seeds = [(r.uniform(0, 16), r.uniform(0, 16)) for _ in range(6)]
    base = [hexc("6FB8DE", 165), hexc("8ACCEA", 172), hexc("A6DCF2", 180), hexc("C4EAFA", 190), hexc("7CC2E4", 168),
            hexc("B4E2F6", 185)]

    def nearest(x, y):
        best, second, bi = 99.0, 99.0, 0
        for i, (sx, sy) in enumerate(seeds):
            dx = min(abs(x - sx), 16 - abs(x - sx))
            dy = min(abs(y - sy), 16 - abs(y - sy))
            d = math.hypot(dx, dy)
            if d < best:
                best, second, bi = d, best, i
            elif d < second:
                second = d
        return bi, second - best

    img = new()
    for y in range(16):
        for x in range(16):
            i, edge = nearest(x + 0.5, y + 0.5)
            c = base[i]
            if edge < 0.9:
                c = hexc("E6F8FF", 215)
            elif r.random() < 0.04:
                c = hexc("FFFFFF", 225)
            img.putpixel((x, y), c)
    return img


def crystal_cluster():
    img = new()
    shards = [(7, 15, 3, 2), (4, 15, 10, 1), (11, 15, 8, 1), (9, 15, 12, 1)]
    for sx, sy, h, w in shards:
        for dy in range(h):
            y = sy - dy
            half = max(0, w - dy // 4)
            for dx in range(-half, half + 1):
                c = hexc("9AD8F0") if dx < 0 else hexc("CDEFFA")
                if dy == h - 1:
                    c = hexc("FFFFFF")
                put(img, sx + dx, y, c)
    # the main shard, tall
    for dy in range(13):
        y = 15 - dy
        half = 1 if dy < 9 else 0
        for dx in range(-half, half + 1):
            put(img, 7 + dx, y, hexc("7FC8E8") if dx < 0 else hexc("B8E8F8") if dx == 0 else hexc("E4F8FF"))
    put(img, 7, 2, hexc("FFFFFF"))
    return img


def coral_stone():
    pal = [hexc("A9706E"), hexc("C08883"), hexc("D29C94"), hexc("E2B2A8"), hexc("EEC8BC")]
    img = speckled(91, pal, cell=4, grain=0.4)
    r = random.Random(92)
    for _ in range(14):  # pores
        put(img, r.randrange(16), r.randrange(16), hexc("8A5654"))
    return img


def dune_sand():
    pal = [hexc("C9A86A"), hexc("D6B87C"), hexc("E0C48A"), hexc("EAD29C")]
    img = speckled(101, pal, cell=4, grain=0.45)
    for y in (3, 8, 13):  # wind ripples
        for x in range(16):
            yy = (y + (1 if (x // 4) % 2 else 0)) % 16
            img.putpixel((x, yy), hexc("BF9C5E"))
    return img


def frosted_stone():
    pal = [hexc("6F7378"), hexc("7E8287"), hexc("8C9095"), hexc("9A9EA3")]
    img = speckled(111, pal, cell=4, grain=0.35)
    n = Noise(112, 4)
    r = random.Random(113)
    for y in range(16):
        for x in range(16):
            if n.at(x, y) + (0.25 if y < 5 else 0) > 0.7 or r.random() < 0.05:
                img.putpixel((x, y), hexc("E8F0F8") if r.random() < 0.7 else hexc("C8D8EA"))
    return img


def hist_sap():
    img = new()
    r = random.Random(121)
    for y in range(16):
        for x in range(16):
            v = 0.5 + 0.3 * math.sin(x * 0.7 + y * 0.4) + (r.random() - 0.5) * 0.2
            c = mix(hexc("A85A10", 210), hexc("F0B040", 200), v)
            if x in (0, 15) or y in (0, 15):
                c = hexc("8A4808", 230)
            img.putpixel((x, y), c)
    return img


def reeds(top):
    img = new()
    r = random.Random(131 + top)
    stems = [2, 5, 8, 11, 13]
    if not top:
        for x in range(2, 14):
            put(img, x, 15, hexc("3E5624") if x % 2 == 0 else hexc("4E6A2E"))
            if 3 <= x <= 12:
                put(img, x, 14, hexc("4E6A2E"))
    for i, sx in enumerate(stems):
        h = 16 if not top else r.randint(8, 14)
        for dy in range(h):
            y = 15 - dy
            x = sx + (1 if dy > 10 and i % 2 else 0)
            put(img, x, y, hexc("5E7A3A") if (dy + i) % 3 else hexc("4E6A2E"))
            if dy < 10 and x + 1 < 15:
                put(img, x + 1, y, hexc("3E5624"))
        if top and i % 2 == 0:  # cattail heads
            for dy in range(4):
                put(img, sx, 15 - h + dy + 1, hexc("6A4426"))
                put(img, sx + 1, 15 - h + dy + 1, hexc("7E5430"))
    if not top:
        for x in (3, 7, 9, 12):  # lower leaves
            for dy in range(7):
                put(img, x + (dy // 3), 15 - dy, hexc("6E8A44"))
    return img


def scathecraw():
    img = new()
    r = random.Random(141)
    # Grounded basal root crown at y=14..15
    for x in range(3, 13):
        put(img, x, 15, hexc("542418") if x % 2 == 0 else hexc("6E3020"))
        if 4 <= x <= 11:
            put(img, x, 14, hexc("7A3A28"))
    for _ in range(11):
        x = r.randint(2, 13)
        h = r.randint(6, 13)
        lean = r.choice([-1, 0, 1])
        for dy in range(h):
            xx = max(1, min(14, x + (lean * dy) // 4))
            put(img, xx, 15 - dy, hexc("7A3A28") if dy < h - 3 else hexc("B8643A"))
            if dy < 5 and xx + 1 < 15:
                put(img, xx + 1, 15 - dy, hexc("5E2A1C"))
        tx = max(1, min(14, x + (lean * h) // 4))
        put(img, tx, 15 - h, hexc("D88A4A"))
        if 15 - h + 1 < 16:
            put(img, max(1, tx - 1), 15 - h + 1, hexc("F0AC64"))
    return img


def ash_grass():
    img = new()
    r = random.Random(151)
    # Grounded basal clump at y=14..15
    for x in range(2, 14):
        put(img, x, 15, hexc("4E4E3A") if x % 2 == 0 else hexc("5E5E46"))
        if 3 <= x <= 12:
            put(img, x, 14, hexc("6A6A50"))
    for _ in range(14):
        x = r.randint(1, 14)
        h = r.randint(4, 11)
        lean = r.choice([-1, 0, 0, 1])
        for dy in range(h):
            xx = max(1, min(14, x + (lean * dy) // 3))
            put(img, xx, 15 - dy, mix(hexc("6A6A50"), hexc("B8B894"), dy / h + r.random() * 0.2))
    return img


def write_textures():
    save("ash_block", ash_block())
    save("volcanic_rock", volcanic_rock())
    save("cracked_volcanic_rock", cracked_volcanic_rock())
    save("magma_volcanic_rock", magma_volcanic_rock())
    with open(os.path.join(TEX, "magma_volcanic_rock.png.mcmeta"), "w", newline="\n") as f:
        json.dump({"animation": {"frametime": 12, "interpolate": True}}, f, indent=2)
        f.write("\n")
    save("golden_aspen_leaves", leaves_texture(161, [hexc("A87A12"), hexc("C9981E"), hexc("E2B42C"), hexc("F2CE4A")]))
    save("orange_aspen_leaves", leaves_texture(162, [hexc("9C3E12"), hexc("C25A1C"), hexc("DE7A26"), hexc("EE9A3A")]))
    save("snowy_spruce_leaves", leaves_texture(163, [hexc("22402A"), hexc("2C5034"), hexc("38603E")], holes=0.12,
                                               snow=[hexc("F4F8FC"), hexc("DDE8F2")]))
    save("graht_oak_leaves", leaves_texture(164, [hexc("5A5A5A"), hexc("7A7A7A"), hexc("9A9A9A"), hexc("B4B4B4")],
                                            holes=0.14))
    save("hist_leaves", leaves_texture(165, [hexc("626262"), hexc("808080"), hexc("9C9C9C"), hexc("B0B0B0")],
                                       holes=0.22))
    save("palm_fronds", fronds())
    gb = [hexc("3A2414"), hexc("4E3220"), hexc("62412A"), hexc("744F34")]
    save("graht_oak_log", bark(171, gb, moss=[hexc("4E6A2A"), hexc("5E7A30")], moss_amount=0.12))
    save("graht_oak_log_top", log_top(172, gb, [hexc("6A4A2C"), hexc("9C7448"), hexc("B08452"), hexc("C49662")]))
    save("graht_oak_planks", planks(173, [hexc("4A2E1A"), hexc("5E3C24"), hexc("74502F"), hexc("86603A"),
                                          hexc("966C42")]))
    save("palm_log", palm_side())
    save("palm_log_top", log_top(181, [hexc("6E5636"), hexc("86693F")],
                                 [hexc("A88C5A"), hexc("CCB078"), hexc("D8BE88"), hexc("E2CA96")]))
    save("palm_planks", planks(182, [hexc("8E7046"), hexc("A88A58"), hexc("BC9E68"), hexc("CCAE76"), hexc("D8BC84")]))
    hb = [hexc("2E3428"), hexc("3E4636"), hexc("505A44"), hexc("626C52")]
    save("hist_log", bark(191, hb, moss=[hexc("8A5A1A"), hexc("B87A28")], moss_amount=0.07))
    save("hist_log_top", log_top(192, hb, [hexc("4A3A22"), hexc("7A6440"), hexc("8C744C"), hexc("9C8458")]))
    save("hist_sap_block", hist_sap())
    save("parasol_stem", stem_side(201))
    save("parasol_stem_top", stem_top(202))
    save("parasol_cap", cap(203, [hexc("7A4A24"), hexc("8E5A2C"), hexc("A26A34"), hexc("B47A3E")], hexc("D8B07A")))
    save("telvanni_cap", cap(204, [hexc("4E2E5A"), hexc("62386E"), hexc("764484"), hexc("8A5296")], hexc("C8A0D8")))
    save("glowing_mushroom_cap", cap(205, [hexc("1E7A8A"), hexc("28A0B0"), hexc("3CC4D0"), hexc("64DCE4")],
                                     hexc("D8FFFF"), spots=10))
    save("crystal_block", crystal_block())
    save("crystal_cluster", crystal_cluster())
    save("coral_stone", coral_stone())
    save("dune_sand", dune_sand())
    save("frosted_stone", frosted_stone())
    save("swamp_reeds_bottom", reeds(0))
    save("swamp_reeds_top", reeds(1))
    save("scathecraw", scathecraw())
    save("ash_grass", ash_grass())


# ------------------------------------------------------------------------------------------ assets/data
def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def blockstate(name, obj):
    dump(os.path.join(ASSETS, "blockstates", name + ".json"), obj)


def model(name, obj):
    dump(os.path.join(ASSETS, "models", "block", name + ".json"), obj)


def item_model(name, obj):
    dump(os.path.join(ASSETS, "models", "item", name + ".json"), obj)


def loot(name, pools):
    dump(os.path.join(DATA, SK, "loot_tables", "blocks", name + ".json"),
         {"type": "minecraft:block", "pools": pools, "random_sequence": "skycraft:blocks/" + name})


def b(name):
    return "skycraft:block/" + name


def self_drop(name, conditions=None):
    pool = {"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:item", "name": "skycraft:" + name}],
            "conditions": conditions or [{"condition": "minecraft:survives_explosion"}]}
    loot(name, [pool])


SHEARS_OR_SILK = {"condition": "minecraft:any_of", "terms": [
    {"condition": "minecraft:match_tool", "predicate": {"items": ["minecraft:shears"]}},
    {"condition": "minecraft:match_tool", "predicate": {"enchantments": [
        {"enchantment": "minecraft:silk_touch", "levels": {"min": 1}}]}}]}


def cube(name, texture=None, render=None):
    m = {"parent": "minecraft:block/cube_all", "textures": {"all": b(texture or name)}}
    if render:
        m["render_type"] = render
    model(name, m)
    blockstate(name, {"variants": {"": {"model": b(name)}}})
    item_model(name, {"parent": b(name)})


def pillar(name, side, end):
    model(name, {"parent": "minecraft:block/cube_column", "textures": {"end": b(end), "side": b(side)}})
    model(name + "_horizontal", {"parent": "minecraft:block/cube_column_horizontal",
                                 "textures": {"end": b(end), "side": b(side)}})
    blockstate(name, {"variants": {
        "axis=x": {"model": b(name + "_horizontal"), "x": 90, "y": 90},
        "axis=y": {"model": b(name)},
        "axis=z": {"model": b(name + "_horizontal"), "x": 90}}})
    item_model(name, {"parent": b(name)})


def leaves_block(name):
    model(name, {"parent": "minecraft:block/leaves", "render_type": "minecraft:cutout_mipped",
                 "textures": {"all": b(name)}})
    blockstate(name, {"variants": {"": {"model": b(name)}}})
    item_model(name, {"parent": b(name)})
    loot(name, [{"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:alternatives", "children": [
        {"type": "minecraft:item", "name": "skycraft:" + name, "conditions": [SHEARS_OR_SILK]},
        {"type": "minecraft:item", "name": "minecraft:stick",
         "conditions": [{"condition": "minecraft:survives_explosion"},
                        {"condition": "minecraft:table_bonus", "enchantment": "minecraft:fortune",
                         "chances": [0.02, 0.022222223, 0.025, 0.033333335, 0.1]}]}]}]}])


def cross(name, texture=None, tinted=False):
    model(name, {"parent": "minecraft:block/" + ("tinted_cross" if tinted else "cross"),
                 "render_type": "minecraft:cutout", "textures": {"cross": b(texture or name)}})


def botanical_cross(name, texture=None):
    tex = b(texture or name)
    model(name, {
        "ambientocclusion": False,
        "render_type": "minecraft:cutout",
        "textures": {"cross": tex, "particle": tex},
        "elements": [
            {
                "from": [0.8, 0, 8],
                "to": [15.2, 16, 8],
                "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True},
                "shade": False,
                "faces": {
                    "north": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                    "south": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                },
            },
            {
                "from": [8, 0, 0.8],
                "to": [8, 16, 15.2],
                "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True},
                "shade": False,
                "faces": {
                    "west": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                    "east": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                },
            },
            {
                "from": [1.5, 0, 6.8],
                "to": [14.5, 14, 6.8],
                "rotation": {"origin": [8, 0, 6.8], "axis": "x", "angle": 22.5},
                "shade": False,
                "faces": {
                    "north": {"uv": [0, 2, 16, 16], "texture": "#cross"},
                    "south": {"uv": [0, 2, 16, 16], "texture": "#cross"},
                },
            },
            {
                "from": [9.2, 0, 1.5],
                "to": [9.2, 14, 14.5],
                "rotation": {"origin": [9.2, 0, 8], "axis": "z", "angle": 22.5},
                "shade": False,
                "faces": {
                    "west": {"uv": [0, 2, 16, 16], "texture": "#cross"},
                    "east": {"uv": [0, 2, 16, 16], "texture": "#cross"},
                },
            },
        ],
    })


def write_assets():
    # simple cubes
    for name in ("volcanic_rock", "cracked_volcanic_rock", "magma_volcanic_rock", "coral_stone", "dune_sand",
                 "frosted_stone", "graht_oak_planks", "palm_planks", "parasol_cap", "telvanni_cap",
                 "glowing_mushroom_cap", "ash_block"):
        cube(name)
        self_drop(name)
    cube("crystal_block", render="minecraft:translucent")
    self_drop("crystal_block")
    cube("hist_sap_block", render="minecraft:translucent")
    self_drop("hist_sap_block")
    # logs and wood
    pillar("graht_oak_log", "graht_oak_log", "graht_oak_log_top")
    pillar("graht_oak_wood", "graht_oak_log", "graht_oak_log")
    pillar("palm_log", "palm_log", "palm_log_top")
    pillar("hist_log", "hist_log", "hist_log_top")
    pillar("parasol_stem", "parasol_stem", "parasol_stem_top")
    for name in ("graht_oak_log", "graht_oak_wood", "palm_log", "hist_log", "parasol_stem"):
        self_drop(name)
    # leaves
    for name in ("golden_aspen_leaves", "orange_aspen_leaves", "snowy_spruce_leaves", "graht_oak_leaves",
                 "palm_fronds", "hist_leaves"):
        leaves_block(name)
    # ash layers (like snow)
    variants = {}
    pools = []
    for layers in range(1, 9):
        if layers < 8:
            model("ash_layer_height%d" % (layers * 2), {"parent": "minecraft:block/snow_height%d" % (layers * 2),
                                                        "textures": {"particle": b("ash_block"), "texture": b("ash_block")}})
            variants["layers=%d" % layers] = {"model": b("ash_layer_height%d" % (layers * 2))}
        else:
            variants["layers=8"] = {"model": b("ash_block")}
    blockstate("ash_layer", {"variants": variants})
    item_model("ash_layer", {"parent": b("ash_layer_height2")})
    loot("ash_layer", [{"rolls": 1, "bonus_rolls": 0, "entries": [{
        "type": "minecraft:item", "name": "skycraft:ash_layer",
        "functions": [{"function": "minecraft:set_count", "count": n, "conditions": [
            {"condition": "minecraft:block_state_property", "block": "skycraft:ash_layer",
             "properties": {"layers": str(n)}}]} for n in range(1, 9)]}],
        "conditions": [{"condition": "minecraft:survives_explosion"}]}])
    # crystal cluster (faces any direction, like amethyst)
    cross("crystal_cluster")
    blockstate("crystal_cluster", {"variants": {
        "facing=up": {"model": b("crystal_cluster")},
        "facing=down": {"model": b("crystal_cluster"), "x": 180},
        "facing=north": {"model": b("crystal_cluster"), "x": 90},
        "facing=south": {"model": b("crystal_cluster"), "x": 90, "y": 180},
        "facing=east": {"model": b("crystal_cluster"), "x": 90, "y": 90},
        "facing=west": {"model": b("crystal_cluster"), "x": 90, "y": 270}}})
    item_model("crystal_cluster", {"parent": "minecraft:item/generated", "textures": {"layer0": b("crystal_cluster")}})
    self_drop("crystal_cluster")
    # plants
    for name in ("scathecraw", "ash_grass"):
        botanical_cross(name)
        blockstate(name, {"variants": {"": [
            {"model": b(name)},
            {"model": b(name), "y": 90},
            {"model": b(name), "y": 180},
            {"model": b(name), "y": 270},
        ]}})
        item_model(name, {"parent": "minecraft:item/generated", "textures": {"layer0": b(name)}})
    self_drop("scathecraw")
    self_drop("ash_grass", [SHEARS_OR_SILK])
    cross("swamp_reeds_bottom")
    cross("swamp_reeds_top")
    blockstate("swamp_reeds", {"variants": {"half=lower": {"model": b("swamp_reeds_bottom")},
                                            "half=upper": {"model": b("swamp_reeds_top")}}})
    item_model("swamp_reeds", {"parent": "minecraft:item/generated", "textures": {"layer0": b("swamp_reeds_top")}})
    self_drop("swamp_reeds", [{"condition": "minecraft:survives_explosion"},
                              {"condition": "minecraft:block_state_property", "block": "skycraft:swamp_reeds",
                               "properties": {"half": "lower"}}])


def recipe(name, obj):
    dump(os.path.join(DATA, SK, "recipes", name + ".json"), obj)


def write_recipes():
    for planks_, logs in (("graht_oak_planks", ["skycraft:graht_oak_log", "skycraft:graht_oak_wood"]),
                          ("palm_planks", ["skycraft:palm_log"])):
        recipe(planks_, {"type": "minecraft:crafting_shapeless", "category": "building", "group": "planks",
                         "ingredients": [[{"item": i} for i in logs]],
                         "result": {"item": "skycraft:" + planks_, "count": 4}})
    recipe("mangrove_planks_from_hist_log", {"type": "minecraft:crafting_shapeless", "category": "building",
                                             "group": "planks", "ingredients": [{"item": "skycraft:hist_log"}],
                                             "result": {"item": "minecraft:mangrove_planks", "count": 4}})
    recipe("graht_oak_wood", {"type": "minecraft:crafting_shaped", "category": "building", "group": "bark",
                              "pattern": ["##", "##"], "key": {"#": {"item": "skycraft:graht_oak_log"}},
                              "result": {"item": "skycraft:graht_oak_wood", "count": 3}})
    recipe("ash_layer", {"type": "minecraft:crafting_shaped", "category": "building", "pattern": ["###"],
                         "key": {"#": {"item": "skycraft:ash_block"}},
                         "result": {"item": "skycraft:ash_layer", "count": 6}})
    recipe("ash_block", {"type": "minecraft:crafting_shaped", "category": "building", "pattern": ["##", "##"],
                         "key": {"#": {"item": "skycraft:ash_layer"}}, "result": {"item": "skycraft:ash_block"}})
    recipe("mushroom_planks_from_parasol_stem", {"type": "minecraft:crafting_shapeless", "category": "building",
                                                 "group": "planks", "ingredients": [{"item": "skycraft:parasol_stem"}],
                                                 "result": {"item": "minecraft:birch_planks", "count": 4}})
    recipe("glass_from_crystal", {"type": "minecraft:smelting", "category": "blocks",
                                  "ingredient": {"item": "skycraft:crystal_cluster"},
                                  "result": "minecraft:glass", "experience": 0.1, "cookingtime": 200})
    recipe("sandstone_from_dune_sand", {"type": "minecraft:crafting_shaped", "category": "building",
                                        "pattern": ["##", "##"], "key": {"#": {"item": "skycraft:dune_sand"}},
                                        "result": {"item": "minecraft:sandstone"}})


def merge_tag(kind, tag, values):
    ns, path = tag.split(":")
    p = os.path.join(DATA, ns, "tags", kind, *path.split("/")) + ".json"
    existing = {"replace": False, "values": []}
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            existing = json.load(f)
    vals = existing.get("values", [])
    for v in values:
        if v not in vals:
            vals.append(v)
    existing["values"] = vals
    existing.setdefault("replace", False)
    dump(p, {"replace": existing["replace"], "values": vals})


def write_tags():
    s = lambda *n: ["skycraft:" + x for x in n]
    logs = s("graht_oak_log", "graht_oak_wood", "palm_log", "hist_log")
    leaves = s("golden_aspen_leaves", "orange_aspen_leaves", "snowy_spruce_leaves", "graht_oak_leaves", "palm_fronds",
               "hist_leaves")
    plank = s("graht_oak_planks", "palm_planks")
    for kind in ("blocks", "items"):
        merge_tag(kind, "minecraft:logs", logs)
        merge_tag(kind, "minecraft:logs_that_burn", logs)
        merge_tag(kind, "minecraft:leaves", leaves)
        merge_tag(kind, "minecraft:planks", plank)
        merge_tag(kind, "minecraft:sand", s("dune_sand"))
    merge_tag("items", "minecraft:smelts_to_glass", s("dune_sand"))
    merge_tag("blocks", "minecraft:mineable/axe", logs + plank + s("parasol_stem", "parasol_cap", "telvanni_cap",
                                                                   "glowing_mushroom_cap"))
    merge_tag("blocks", "minecraft:mineable/hoe", leaves + s("hist_sap_block"))
    merge_tag("blocks", "minecraft:mineable/pickaxe", s("volcanic_rock", "cracked_volcanic_rock", "magma_volcanic_rock",
                                                        "coral_stone", "frosted_stone", "crystal_block",
                                                        "crystal_cluster"))
    merge_tag("blocks", "minecraft:mineable/shovel", s("ash_block", "ash_layer", "dune_sand"))
    # natural stone: carvers cut caves through it, digging needs Stonebreaker
    merge_tag("blocks", "minecraft:base_stone_overworld", s("volcanic_rock", "cracked_volcanic_rock", "coral_stone",
                                                            "frosted_stone"))
    merge_tag("blocks", "minecraft:infiniburn_overworld", s("magma_volcanic_rock"))
    merge_tag("blocks", "minecraft:replaceable_by_trees", s("scathecraw", "ash_grass", "swamp_reeds", "ash_layer"))
    merge_tag("blocks", "minecraft:sword_efficient", s("scathecraw", "ash_grass", "swamp_reeds"))
    merge_tag("blocks", "minecraft:crystal_sound_blocks", s("crystal_block"))
    # digging rules (dig/DiggingRules): ash is soft ground, volcanic rock and crystal hard ground
    merge_tag("blocks", "skycraft:soft_terrain", s("ash_block", "dune_sand"))
    merge_tag("blocks", "skycraft:hard_terrain", s("magma_volcanic_rock", "crystal_block"))


if __name__ == "__main__":
    write_textures()
    write_assets()
    write_recipes()
    write_tags()
    print("tamriel blocks: textures, models, loot tables, recipes and tags written")
