#!/usr/bin/env python3
"""Blocks, items and skies of the realms (workstream Realms): original procedural textures plus every block asset.

Writes:
  * 16x16 block textures into assets/nirncraft/textures/block/ (animated gates with .mcmeta)
  * item textures (Sigil Stone, Black Book) into assets/nirncraft/textures/item/
  * sky textures into assets/nirncraft/textures/environment/realms/ (Dagon's eye, Hermaeus Mora's eye, the Soul
    Cairn's orb, Coldharbour's dark sun, Sovngarde's sun, smoke and cloud layers)
  * blockstates, block and item models, block loot tables
Names live in mod/src/main/lang/nirncraft/realms.json (written by tools/worldgen/realms.py).

Run from anywhere:  py -3 tools/textures/realms.py
"""
import json
import math
import os
import random

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "nirncraft")
DATA = os.path.join(RES, "data")
TEX = os.path.join(ASSETS, "textures", "block")
ITEM_TEX = os.path.join(ASSETS, "textures", "item")
SKY = os.path.join(ASSETS, "textures", "environment", "realms")
SK = "nirncraft"


# ------------------------------------------------------------------------------------------------ helpers
def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def pal(*hexes):
    return [hexc(h) for h in hexes]


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def pick(p, v):
    i = int(max(0.0, min(0.999, v)) * len(p))
    return p[i]


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def block_tex(name, img):
    save(img, os.path.join(TEX, name + ".png"))


class Noise:
    """Tileable value noise on an n x n torus (cell size c)."""

    def __init__(self, seed, cell=4, size=16):
        r = random.Random(seed)
        self.c = cell
        self.n = max(1, size // cell)
        self.g = [[r.random() for _ in range(self.n)] for _ in range(self.n)]

    def at(self, x, y):
        gx, gy = x / self.c, y / self.c
        x0, y0 = int(math.floor(gx)) % self.n, int(math.floor(gy)) % self.n
        x1, y1 = (x0 + 1) % self.n, (y0 + 1) % self.n
        fx, fy = gx - math.floor(gx), gy - math.floor(gy)
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        a = self.g[y0][x0] + (self.g[y0][x1] - self.g[y0][x0]) * fx
        b = self.g[y1][x0] + (self.g[y1][x1] - self.g[y1][x0]) * fx
        return a + (b - a) * fy


def new(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def speckled(seed, p, cell=4, grain=0.35, bias=0.0):
    r = random.Random(seed)
    n1, n2 = Noise(seed, cell), Noise(seed + 1, 2)
    img = new()
    for y in range(16):
        for x in range(16):
            v = n1.at(x, y) * 0.55 + n2.at(x, y) * 0.25 + (r.random() - 0.5) * grain + 0.1 + bias
            img.putpixel((x, y), pick(p, v))
    return img


def cracks(img, seed, color, count=4, length=9, glow=None):
    r = random.Random(seed)
    for _ in range(count):
        x, y = r.randrange(16), r.randrange(16)
        for _ in range(length):
            img.putpixel((x % 16, y % 16), color)
            if glow is not None:
                for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    px, py = (x + ox) % 16, (y + oy) % 16
                    if img.getpixel((px, py)) != color and r.random() < 0.5:
                        img.putpixel((px, py), mix(img.getpixel((px, py)), glow, 0.5))
            d = r.randrange(4)
            x += (1, -1, 0, 0)[d] if r.random() < 0.6 else 0
            y += (0, 0, 1, -1)[d] if r.random() < 0.9 else 1


def bricks(seed, mortar, p, rows=4, cols=2, bevel=True):
    """Ashlar bricks with offset courses, each brick shaded by its own value."""
    r = random.Random(seed)
    img = new()
    rh = 16 // rows
    for y in range(16):
        row = y // rh
        off = (16 // cols // 2) * (row % 2)
        for x in range(16):
            bx = ((x + off) % 16) // (16 // cols)
            edge_y = y % rh == rh - 1
            edge_x = (x + off) % (16 // cols) == 0
            if edge_y or edge_x:
                img.putpixel((x, y), mortar)
                continue
            r.seed(seed * 131 + row * 17 + bx)
            base = r.random() * 0.5 + 0.25
            rr = random.Random(seed + x * 7 + y * 13)
            v = base + (rr.random() - 0.5) * 0.25
            if bevel and y % rh == 0:
                v += 0.15
            img.putpixel((x, y), pick(p, v))
    return img


def plant(seed, stem, leaf, flower=None, tall=12):
    """A cross-model plant: a few stalks with leaves (and optional bloom pixels)."""
    r = random.Random(seed)
    img = new()
    for s in range(4):
        x = 2 + s * 4 + r.randrange(2)
        h = tall - r.randrange(5)
        for dy in range(h):
            y = 15 - dy
            xx = x + (1 if dy > h * 0.6 and s % 2 else 0)
            if 0 <= xx < 16:
                img.putpixel((xx, y), stem if dy < h * 0.4 else leaf)
            if dy % 3 == 1 and dy < h - 2:
                lx = xx + (1 if (dy // 3) % 2 else -1)
                if 0 <= lx < 16:
                    img.putpixel((lx, y), leaf)
        if flower:
            for fx, fy in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1)):
                px, py = x + fx, 15 - h + fy
                if 0 <= px < 16 and 0 <= py < 16:
                    img.putpixel((px, py), flower if (fx, fy) != (0, 0) else mix(flower, hexc("FFFFFF"), 0.5))
    return img


# ------------------------------------------------------------------------------------------------ block textures
DL_ROCK = pal("1A0A0A", "2A0F0D", "3A1512", "4A1C17", "5A241D", "6B2C22")
ASH = pal("2E2626", "3A302E", "463A37", "524540", "5E5049")
DAEDRIC = pal("120C0C", "1C1212", "271817", "331F1C", "3F2622")
SOUL = pal("2A1E3A", "362848", "433258", "523E68", "604A78")
SOUL_ASH = pal("4A3E5E", "56486C", "62527A", "6E5C86", "7A6892")
CH_STONE = pal("10141E", "171C28", "1E2432", "262D3E", "2F384A")
AP_STONE = pal("0A140C", "0F1C12", "152618", "1C3020", "243A28")


def textures():
    # the Deadlands
    img = speckled(301, DL_ROCK, cell=4, grain=0.5)
    cracks(img, 302, hexc("140606"), count=3)
    block_tex("deadlands_rock", img)
    img = speckled(303, DL_ROCK, cell=4, grain=0.45, bias=-0.1)
    cracks(img, 304, hexc("FF8A20"), count=4, length=10, glow=hexc("C83A10"))
    block_tex("deadlands_magma_rock", img)
    block_tex("deadlands_ash", speckled(305, ASH, cell=4, grain=0.65))
    db = bricks(306, hexc("080505"), DAEDRIC, rows=4, cols=2)
    block_tex("daedric_bricks", db)
    g = db.copy()
    rr = random.Random(307)
    for y in (4, 5, 6, 9, 10, 11):
        for x in range(2, 14):
            if rr.random() < 0.45 and g.getpixel((x, y))[0] > 0x10:
                g.putpixel((x, y), hexc("FF5A1E") if rr.random() < 0.6 else hexc("FFB040"))
    block_tex("daedric_glyph_bricks", g)
    side = new()
    for y in range(16):
        for x in range(16):
            v = 0.35 + 0.3 * math.sin(x / 15 * math.pi) + (random.Random(308 + x * 31 + y).random() - 0.5) * 0.2
            c = pick(DAEDRIC, v)
            if x in (0, 15) or y in (0, 15):
                c = hexc("0A0606")
            if x in (3, 12) and y % 4 != 0:
                c = hexc("5A1A10")
            side.putpixel((x, y), c)
    block_tex("daedric_pillar", side)
    top = bricks(309, hexc("0A0606"), DAEDRIC, rows=2, cols=2)
    for i in range(16):
        top.putpixel((i, i), hexc("4A1610"))
        top.putpixel((15 - i, i), hexc("4A1610"))
    block_tex("daedric_pillar_top", top)
    sp = speckled(310, DAEDRIC, cell=2, grain=0.4)
    for y in range(16):
        sp.putpixel((7, y), hexc("5A1A10"))
    block_tex("daedric_spike", sp)
    block_tex("bloodgrass", plant(311, hexc("5A0E0A"), hexc("A3161A"), None, tall=12))
    harrada = new()
    r = random.Random(312)
    for s in range(3):
        x, y = 3 + s * 5, 15
        for _ in range(14):
            harrada.putpixel((x % 16, y), hexc("6A1A14") if r.random() < 0.7 else hexc("8E2A1A"))
            if r.random() < 0.35:
                tx = (x + r.choice((-1, 1))) % 16
                harrada.putpixel((tx, y), hexc("D8C090"))
            y -= 1
            if y < 1:
                break
            x += r.choice((-1, 0, 0, 1))
    block_tex("harrada", harrada)
    # the Sigil Stone: a faceted burning gem
    sig = new()
    for y in range(16):
        for x in range(16):
            dx, dy = abs(x - 7.5), abs(y - 7.5)
            d = dx + dy
            if d < 7.5:
                v = 1 - d / 7.5
                c = mix(hexc("6A1206"), hexc("FFD070"), v)
                if (x + y) % 5 == 0:
                    c = mix(c, hexc("FFFFFF"), 0.25)
                sig.putpixel((x, y), c)
    block_tex("sigil_stone", sig)
    fl = new()
    r = random.Random(313)
    for y in range(16):
        for x in range(16):
            w = 6 - y * 0.25 + math.sin(y * 0.9 + x) * 1.2
            if abs(x - 7.5) < w and r.random() < 0.85:
                t = y / 15
                fl.putpixel((x, y), mix(hexc("FFE080", 220), hexc("C81E10", 160), 1 - t))
    block_tex("sigil_flame", fl)
    # the gates (animated)
    gate_tex("oblivion_portal", [hexc("3A0602", 230), hexc("A01A06", 225), hexc("FF6A10", 215), hexc("FFC850", 210)], 401)
    gate_tex("valor_portal", [hexc("806020", 170), hexc("D8B050", 160), hexc("FFF0B0", 150), hexc("FFFFFF", 140)], 402)
    gate_tex("soul_portal", [hexc("180A30", 220), hexc("4A2290", 210), hexc("8A5AE0", 200), hexc("D0B8FF", 190)], 403)
    gate_tex("coldharbour_portal", [hexc("02040A", 235), hexc("0A1430", 225), hexc("2A4A90", 210), hexc("8AB0FF", 190)], 404)
    # the Shivering Isles
    stem = new()
    for y in range(16):
        for x in range(16):
            v = 0.55 + 0.25 * math.sin(x * 0.8) + (random.Random(320 + x * 17 + y * 3).random() - 0.5) * 0.15
            stem.putpixel((x, y), pick(pal("B8A8C8", "C8BAD6", "D8CCE2", "E6DCEE", "F2ECF8"), v))
    block_tex("mania_stem", stem)
    top = new()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            top.putpixel((x, y), pick(pal("9888A8", "B0A0C0", "C8BAD6", "DCD0E6"), 0.9 - d / 11 + math.sin(d * 2) * 0.1))
    block_tex("mania_stem_top", top)
    cap_tex("mania_cap_violet", pal("4A1A7A", "6A2AA8", "8A40D0", "B070F0", "D8A8FF"), hexc("F8E8FF"), 321)
    cap_tex("mania_cap_teal", pal("0A4A5A", "107A8A", "20A8B0", "50D8D0", "A0FFF0"), hexc("F0FFF8"), 322)
    cap_tex("mania_cap_amber", pal("7A3A06", "A85A0A", "D88A1A", "F8B840", "FFE090"), hexc("FFF8E0"), 323)
    block_tex("mania_bloom", plant(324, hexc("2A8A4A"), hexc("40C870"), hexc("FF60D0"), tall=10))
    bulb = plant(325, hexc("1E3A1E"), hexc("2E5A2A"), None, tall=9)
    for (x, y) in ((4, 5), (5, 5), (4, 6), (5, 6), (11, 7), (12, 7), (11, 8), (12, 8)):
        bulb.putpixel((x, y), hexc("C8FF60"))
    block_tex("dementia_bulb", bulb)
    # Apocrypha
    page_side("page_block", pal("B8A880", "CBBE98", "DCD2B0", "EAE2C8"), hexc("6A5A3A"), 330)
    page_side("black_page_block", pal("0E1410", "18221A", "222E24", "2E3C30"), hexc("4A8A4A"), 331)
    cover = speckled(332, pal("2A1A10", "3A2416", "4A2E1C", "5A3822"), cell=4, grain=0.3)
    for i in range(16):
        cover.putpixel((i, 0), hexc("A88A40"))
        cover.putpixel((i, 15), hexc("A88A40"))
    block_tex("page_block_top", cover)
    bcover = speckled(333, pal("060A08", "0C120E", "121A14", "18221A"), cell=4, grain=0.3)
    for i in range(3, 13):
        bcover.putpixel((i, 7), hexc("3AA050"))
        bcover.putpixel((7, i), hexc("3AA050"))
    block_tex("black_page_block_top", bcover)
    block_tex("apocrypha_stone", speckled(334, AP_STONE, cell=4, grain=0.5))
    gl = speckled(335, AP_STONE, cell=4, grain=0.45)
    rr = random.Random(336)
    for _ in range(3):
        x0, y0 = rr.randrange(2, 12), rr.randrange(2, 12)
        for k in range(rr.randrange(4, 7)):
            gl.putpixel(((x0 + k) % 16, y0), hexc("60F080"))
            if rr.random() < 0.5:
                gl.putpixel(((x0 + k) % 16, (y0 + 1) % 16), hexc("30B050"))
    block_tex("apocrypha_glyph_stone", gl)
    ten = new()
    for y in range(16):
        for x in range(16):
            v = 0.5 + 0.3 * math.sin((x + y * 0.5) * 0.7) + (random.Random(337 + x * 13 + y * 7).random() - 0.5) * 0.2
            c = pick(pal("0A1A0A", "142A14", "1E3C1C", "2A5026", "3A6A32"), v)
            if (x - 3) ** 2 + (y - 4) ** 2 < 3 or (x - 11) ** 2 + (y - 11) ** 2 < 3:
                c = hexc("6A8A5A")
            ten.putpixel((x, y), c)
    block_tex("apocrypha_tentacle", ten)
    # the black book on its pedestal: cover, pages
    bk = new()
    for y in range(16):
        for x in range(16):
            c = hexc("0A0E0B") if x in (0, 15) or y in (0, 15) else hexc("D8CCA8") if (x < 7 or x > 8) else hexc("1A221C")
            if 2 < y < 13 and (x < 7 or x > 8) and y % 2 == 0 and 2 < x < 13:
                c = hexc("2A5A2A")
            bk.putpixel((x, y), c)
    block_tex("black_book", bk)
    # the Soul Cairn
    block_tex("soul_ash", speckled(340, SOUL_ASH, cell=4, grain=0.6))
    st_ = speckled(341, SOUL, cell=4, grain=0.45)
    cracks(st_, 342, hexc("1A1228"), count=3)
    block_tex("soul_stone", st_)
    gem = new()
    for y in range(16):
        for x in range(16):
            v = 0.5 + 0.35 * math.sin((x * 0.9 + y * 0.6)) * math.cos(y * 0.5 - x * 0.2)
            c = pick(pal("3A1A70", "5A2AA0", "7A40D0", "A070F0", "D0B0FF"), v)
            if (x + 2 * y) % 7 == 0:
                c = hexc("F0E0FF")
            gem.putpixel((x, y), c)
    block_tex("soul_gem_block", gem)
    cl = new()
    for k, (cx, h) in enumerate(((4, 9), (8, 14), (12, 8))):
        for dy in range(h):
            w = max(0, int((1 - dy / h) * 2))
            for dx in range(-w, w + 1):
                x, y = cx + dx, 15 - dy
                if 0 <= x < 16:
                    cl.putpixel((x, y), mix(hexc("6A30C0"), hexc("E8D0FF"), dy / h))
    block_tex("soul_gem_cluster", cl)
    # Coldharbour
    cs = speckled(350, CH_STONE, cell=4, grain=0.5)
    cracks(cs, 351, hexc("06080E"), count=4)
    block_tex("coldharbour_stone", cs)
    block_tex("coldharbour_bricks", bricks(352, hexc("05070C"), CH_STONE, rows=4, cols=2))


def cap_tex(name, p, spot, seed):
    r = random.Random(seed)
    img = speckled(seed, p, cell=4, grain=0.3, bias=0.15)
    for _ in range(6):
        x, y = r.randrange(16), r.randrange(16)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            if r.random() < 0.85:
                img.putpixel(((x + dx) % 16, (y + dy) % 16), spot)
    block_tex(name, img)


def page_side(name, p, line, seed):
    r = random.Random(seed)
    img = new()
    for y in range(16):
        stack = (y // 4) % 2
        for x in range(16):
            v = 0.55 + (r.random() - 0.5) * 0.3 - (0.15 if stack else 0)
            c = pick(p, v)
            if y % 4 == 3:
                c = line
            img.putpixel((x, y), c)
    block_tex(name, img)


def gate_tex(name, ramp, seed, frames=16):
    """A swirling animated sheet: frames stacked vertically, plus the .mcmeta."""
    w = 16
    img = Image.new("RGBA", (w, w * frames))
    n1, n2 = Noise(seed, 4), Noise(seed + 1, 8)
    for f in range(frames):
        t = f / frames
        for y in range(w):
            for x in range(w):
                a = math.atan2(y - 7.5, x - 7.5)
                d = math.hypot(x - 7.5, y - 7.5)
                v = n1.at((x + t * 16) % 16, (y + d * 0.5) % 16) * 0.55 + n2.at((x - t * 8) % 16, (y + t * 16) % 16) * 0.35
                v += 0.25 * math.sin(a * 3 + d * 0.8 - t * math.pi * 2)
                v = max(0.0, min(0.999, v))
                img.putpixel((x, f * w + y), pick(ramp, v))
    save(img, os.path.join(TEX, name + ".png"))
    with open(os.path.join(TEX, name + ".png.mcmeta"), "w", encoding="utf-8", newline="\n") as fp:
        json.dump({"animation": {"frametime": 2, "interpolate": True}}, fp, indent=2)
        fp.write("\n")


# ------------------------------------------------------------------------------------------------ items
def items():
    img = new()
    for y in range(16):
        for x in range(16):
            dx, dy = abs(x - 7.5), abs(y - 7.5)
            d = dx * 0.9 + dy
            if d < 6.5:
                v = 1 - d / 6.5
                c = mix(hexc("5A0A04"), hexc("FFD878"), v)
                if (x * 3 + y) % 7 == 0:
                    c = mix(c, hexc("FFFFFF"), 0.3)
                img.putpixel((x, y), c)
            elif d < 7.3:
                img.putpixel((x, y), hexc("2A0602"))
    save(img, os.path.join(ITEM_TEX, "sigil_stone.png"))
    bk = new()
    for y in range(2, 15):
        for x in range(3, 14):
            c = hexc("0C120E")
            if x == 3 or y in (2, 14):
                c = hexc("040604")
            if x == 4:
                c = hexc("1E2A20")
            if 6 <= x <= 11 and 5 <= y <= 11 and ((x - 8.5) ** 2 + (y - 8) ** 2) < 7:
                c = hexc("3AA050") if ((x - 8.5) ** 2 + (y - 8) ** 2) > 2 else hexc("B0FF90")
            bk.putpixel((x, y), c)
    for y in range(3, 14):
        bk.putpixel((13, y), hexc("C8BC98"))
    save(bk, os.path.join(ITEM_TEX, "black_book.png"))


# ------------------------------------------------------------------------------------------------ skies
def sky_save(name, arr):
    arr = np.clip(arr, 0, 255).astype(np.uint8)
    save(Image.fromarray(arr, "RGBA"), os.path.join(SKY, name + ".png"))


def polar(size):
    y, x = np.mgrid[0:size, 0:size].astype(np.float64)
    c = (size - 1) / 2.0
    dx, dy = (x - c) / c, (y - c) / c
    return np.sqrt(dx * dx + dy * dy), np.arctan2(dy, dx)


def fbm(size, seed, octaves=5, base=4):
    """Tileable fractal noise in [0, 1]."""
    rng = np.random.default_rng(seed)
    out = np.zeros((size, size))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        n = base * (2 ** o)
        g = rng.random((n, n))
        # seamless: resample a 3x3 tiling of the lattice and keep the middle tile
        tiled = np.tile(g, (3, 3))
        img = Image.fromarray((tiled * 255).astype(np.uint8), "L").resize((size * 3, size * 3), Image.BICUBIC)
        a = np.asarray(img, dtype=np.float64)[size:2 * size, size:2 * size] / 255.0
        out += a * amp
        total += amp
        amp *= 0.5
    out /= total
    out = (out - out.min()) / max(1e-6, out.max() - out.min())
    return out


def skies():
    s = 256
    r, a = polar(s)
    n = fbm(s, 7, 5, 4)
    # Dagon's eye: a burning vortex with a black pupil
    swirl = np.sin(a * 5 + r * 14 + n * 6) * 0.5 + 0.5
    ring = np.exp(-((r - 0.45) ** 2) / 0.03)
    body = np.clip(1 - r, 0, 1) ** 0.8
    inten = body * (0.45 + 0.55 * swirl) + ring * 0.6
    pupil = np.clip((0.16 - r) / 0.06, 0, 1)
    rgb = np.zeros((s, s, 4))
    rgb[..., 0] = 255 * np.clip(inten * 1.3, 0, 1)
    rgb[..., 1] = 255 * np.clip(inten ** 1.8 * 0.75, 0, 1)
    rgb[..., 2] = 255 * np.clip(inten ** 3 * 0.35, 0, 1)
    alpha = np.clip(inten * 1.4, 0, 1) * (1 - pupil) + pupil
    rgb[..., :3] *= (1 - pupil)[..., None]
    rgb[..., 3] = 255 * alpha * (r < 1)
    sky_save("deadlands_eye", rgb)
    # drifting smoke (tileable, alpha)
    sm = fbm(s, 11, 6, 2)
    arr = np.zeros((s, s, 4))
    arr[..., 0] = 120 + 80 * sm
    arr[..., 1] = 60 + 40 * sm
    arr[..., 2] = 50 + 30 * sm
    arr[..., 3] = 255 * np.clip((sm - 0.35) * 2.2, 0, 1)
    sky_save("deadlands_smoke", arr)
    # Sovngarde's sun: a soft gold disc with a halo
    s2 = 128
    r2, a2 = polar(s2)
    disc = np.clip((0.32 - r2) / 0.05, 0, 1)
    halo = np.exp(-r2 * 4.5) * 0.8
    rays = (np.sin(a2 * 12) * 0.5 + 0.5) * np.exp(-r2 * 3) * 0.25
    i2 = np.clip(disc + halo + rays, 0, 1)
    arr = np.zeros((s2, s2, 4))
    arr[..., 0] = 255
    arr[..., 1] = 230 * np.clip(0.75 + 0.25 * disc, 0, 1)
    arr[..., 2] = 160 + 90 * disc
    arr[..., 3] = 255 * i2 * (r2 < 1)
    sky_save("sovngarde_sun", arr)
    # Hermaeus Mora's eye: green iris, slit pupil, a wreath of tentacles
    iris = np.clip((0.42 - r) / 0.05, 0, 1)
    pup = np.clip((0.07 - np.abs((np.cos(a) * r))) / 0.03, 0, 1) * np.clip((0.36 - r) / 0.05, 0, 1)
    tent = (np.sin(a * 9 + r * 10 + n * 4) * 0.5 + 0.5) ** 3 * np.clip(1 - r, 0, 1) * (r > 0.4)
    veins = (np.sin(a * 23 + n * 9) * 0.5 + 0.5) ** 6 * iris
    arr = np.zeros((s, s, 4))
    g = iris * (0.55 + 0.45 * n) + tent * 0.8
    arr[..., 0] = 255 * np.clip(g * 0.35 + veins * 0.5, 0, 1)
    arr[..., 1] = 255 * np.clip(g * 1.0, 0, 1)
    arr[..., 2] = 255 * np.clip(g * 0.45, 0, 1)
    arr[..., :3] *= (1 - pup)[..., None]
    arr[..., 3] = 255 * np.clip(np.maximum(iris, tent * 1.2) + pup, 0, 1) * (r < 1)
    sky_save("apocrypha_eye", arr)
    # the Soul Cairn's great gem: a faceted violet orb
    facets = (np.floor((a + math.pi) / (math.pi / 6)) % 2) * 0.2 + (np.floor(r * 6) % 2) * 0.1
    orb = np.clip((0.62 - r) / 0.04, 0, 1)
    shine = np.exp(-(((r - 0.25) ** 2) / 0.01)) * (np.cos(a + 2.2) > 0.6) * 0.8
    glow = np.exp(-r * 3.0) * 0.4
    arr = np.zeros((s, s, 4))
    v = orb * (0.45 + facets + 0.3 * n) + glow
    arr[..., 0] = 255 * np.clip(v * 0.75 + shine, 0, 1)
    arr[..., 1] = 255 * np.clip(v * 0.45 + shine, 0, 1)
    arr[..., 2] = 255 * np.clip(v * 1.0 + shine, 0, 1)
    arr[..., 3] = 255 * np.clip(orb + glow * 1.5, 0, 1) * (r < 1)
    sky_save("soul_cairn_orb", arr)
    # Coldharbour's dark sun: black core, cold corona, rays
    core = np.clip((0.3 - r) / 0.03, 0, 1)
    corona = np.exp(-((r - 0.33) ** 2) / 0.004) + np.exp(-r * 3.2) * 0.4 * (r > 0.3)
    rays = (np.sin(a * 16 + n * 5) * 0.5 + 0.5) ** 4 * np.exp(-(r - 0.3) * 3) * (r > 0.3) * 0.6
    c = np.clip(corona + rays, 0, 1)
    arr = np.zeros((s, s, 4))
    arr[..., 0] = 255 * np.clip(c * 0.6, 0, 1)
    arr[..., 1] = 255 * np.clip(c * 0.75, 0, 1)
    arr[..., 2] = 255 * np.clip(c * 1.0, 0, 1)
    arr[..., 3] = 255 * np.clip(c + core, 0, 1) * (r < 1)
    arr[..., :3] *= (1 - core)[..., None]
    sky_save("coldharbour_sun", arr)
    cl = fbm(s, 23, 6, 2)
    arr = np.zeros((s, s, 4))
    arr[..., 0] = 40 + 50 * cl
    arr[..., 1] = 45 + 55 * cl
    arr[..., 2] = 60 + 70 * cl
    arr[..., 3] = 255 * np.clip((cl - 0.3) * 1.8, 0, 1)
    sky_save("coldharbour_clouds", arr)
    s3 = 64
    r3, _ = polar(s3)
    arr = np.zeros((s3, s3, 4))
    arr[..., :3] = 255
    arr[..., 3] = 255 * np.clip(np.exp(-r3 * 3.2) * (1 - r3), 0, 1)
    sky_save("glow", arr)


# ------------------------------------------------------------------------------------------------ assets
def dump(path, obj):
    import sys
    from pathlib import Path
    native_tools263 = str(Path(__file__).resolve().parents[1])
    if native_tools263 not in sys.path: sys.path.insert(0, native_tools263)
    from data_format_263 import prepare
    path, obj = prepare(path, obj)
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


def b(name):
    return SK + ":block/" + name


def loot_self(name):
    dump(os.path.join(DATA, SK, "loot_table", "blocks", name + ".json"),
         {"type": "minecraft:block", "random_sequence": SK + ":blocks/" + name, "pools": [
             {"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:item", "name": SK + ":" + name}],
              "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


def cube(name, render=None):
    m = {"parent": "minecraft:block/cube_all", "textures": {"all": b(name)}}
    if render:
        m["render_type"] = render
    model(name, m)
    blockstate(name, {"variants": {"": {"model": b(name)}}})
    item_model(name, {"parent": b(name)})
    loot_self(name)


def column(name, side, end):
    model(name, {"parent": "minecraft:block/cube_column", "textures": {"end": b(end), "side": b(side)}})
    blockstate(name, {"variants": {"": {"model": b(name)}}})
    item_model(name, {"parent": b(name)})
    loot_self(name)


def pillar(name, side, end):
    model(name, {"parent": "minecraft:block/cube_column", "textures": {"end": b(end), "side": b(side)}})
    model(name + "_horizontal", {"parent": "minecraft:block/cube_column_horizontal", "textures": {"end": b(end), "side": b(side)}})
    blockstate(name, {"variants": {"axis=x": {"model": b(name + "_horizontal"), "x": 90, "y": 90},
                                   "axis=y": {"model": b(name)}, "axis=z": {"model": b(name + "_horizontal"), "x": 90}}})
    item_model(name, {"parent": b(name)})
    loot_self(name)


def cross_plant(name, drop=True):
    model(name, {"parent": "minecraft:block/cross", "render_type": "minecraft:cutout", "textures": {"cross": b(name)}})
    blockstate(name, {"variants": {"": {"model": b(name)}}})
    item_model(name, {"parent": "minecraft:item/generated", "textures": {"layer0": b(name)}})
    if drop:
        loot_self(name)


def facing_variants(m):
    return {"facing=up": {"model": m}, "facing=down": {"model": m, "x": 180},
            "facing=north": {"model": m, "x": 90}, "facing=south": {"model": m, "x": 90, "y": 180},
            "facing=east": {"model": m, "x": 90, "y": 90}, "facing=west": {"model": m, "x": 90, "y": 270}}


def box(fr, to, tex, faces=("north", "south", "east", "west", "up", "down"), uv=None):
    f = {}
    for face in faces:
        e = {"texture": "#" + tex}
        if uv:
            e["uv"] = uv
        f[face] = e
    return {"from": fr, "to": to, "faces": f}


def assets():
    for name in ("deadlands_rock", "deadlands_magma_rock", "deadlands_ash", "daedric_bricks", "daedric_glyph_bricks",
                 "apocrypha_stone", "apocrypha_glyph_stone", "apocrypha_tentacle", "soul_ash", "soul_stone",
                 "coldharbour_stone", "coldharbour_bricks", "mania_cap_violet", "mania_cap_teal", "mania_cap_amber"):
        cube(name)
    cube("soul_gem_block", render="minecraft:translucent")
    column("page_block", "page_block", "page_block_top")
    column("black_page_block", "black_page_block", "black_page_block_top")
    pillar("daedric_pillar", "daedric_pillar", "daedric_pillar_top")
    pillar("mania_stem", "mania_stem", "mania_stem_top")
    for name in ("bloodgrass", "harrada", "mania_bloom", "dementia_bulb"):
        cross_plant(name)
    # soul gem cluster: a cross pointing any way, like amethyst
    model("soul_gem_cluster", {"parent": "minecraft:block/cross", "render_type": "minecraft:cutout",
                               "textures": {"cross": b("soul_gem_cluster")}})
    blockstate("soul_gem_cluster", {"variants": {("facing=%s,waterlogged=%s" % (k.split("=")[1], w)): v
                                                 for k, v in facing_variants(b("soul_gem_cluster")).items() for w in ("true", "false")}})
    item_model("soul_gem_cluster", {"parent": "minecraft:item/generated", "textures": {"layer0": b("soul_gem_cluster")}})
    loot_self("soul_gem_cluster")
    # the spike: a thick base and a needle tip
    model("daedric_spike", {"parent": "minecraft:block/block", "render_type": "minecraft:cutout",
                            "textures": {"particle": b("daedric_spike"), "s": b("daedric_spike")},
                            "elements": [box([4, 0, 4], [12, 8, 12], "s"), box([5.5, 8, 5.5], [10.5, 13, 10.5], "s"),
                                         box([7, 13, 7], [9, 16, 9], "s")]})
    blockstate("daedric_spike", {"variants": facing_variants(b("daedric_spike"))})
    item_model("daedric_spike", {"parent": b("daedric_spike")})
    loot_self("daedric_spike")
    # sigil stone: a floating burning gem; sigil flame: crossed flames
    model("sigil_stone", {"parent": "minecraft:block/block", "render_type": "minecraft:cutout",
                          "textures": {"particle": b("sigil_stone"), "s": b("sigil_stone")},
                          "elements": [box([3, 2, 3], [13, 14, 13], "s")]})
    blockstate("sigil_stone", {"variants": {"": {"model": b("sigil_stone")}}})
    model("sigil_flame", {"parent": "minecraft:block/cross", "render_type": "minecraft:translucent",
                          "textures": {"cross": b("sigil_flame")}})
    blockstate("sigil_flame", {"variants": {"": {"model": b("sigil_flame")}}})
    # the gates: thin glowing sheets
    for name in ("oblivion_portal", "valor_portal", "soul_portal", "coldharbour_portal"):
        model(name, {"parent": "minecraft:block/block", "render_type": "minecraft:translucent",
                     "textures": {"particle": b(name), "p": b(name)},
                     "elements": [{"from": [0, 0, 6], "to": [16, 16, 10], "shade": False,
                                   "faces": {"north": {"texture": "#p"}, "south": {"texture": "#p"},
                                             "east": {"texture": "#p", "cullface": "east"}, "west": {"texture": "#p", "cullface": "west"},
                                             "up": {"texture": "#p", "cullface": "up"}, "down": {"texture": "#p", "cullface": "down"}}}]})
        blockstate(name, {"variants": {("axis_x=%s,return=%s" % (ax, rt)): ({"model": b(name)} if ax == "true" else {"model": b(name), "y": 90})
                                       for ax in ("true", "false") for rt in ("true", "false")}})
    # the madness door: a carved two-block panel
    door_tex()
    for half in ("lower", "upper"):
        model("madness_door_" + half, {"parent": "minecraft:block/block", "render_type": "minecraft:cutout",
                                       "textures": {"particle": b("madness_door_" + half), "d": b("madness_door_" + half),
                                                    "e": b("madness_door_edge")},
                                       "elements": [{"from": [0, 0, 5], "to": [16, 16, 11], "faces": {
                                           "north": {"texture": "#d"}, "south": {"texture": "#d"},
                                           "east": {"texture": "#e"}, "west": {"texture": "#e"},
                                           "up": {"texture": "#e"}, "down": {"texture": "#e"}}}]})
    variants = {}
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    for f, y in rot.items():
        for half in ("lower", "upper"):
            for rt in ("true", "false"):
                v = {"model": b("madness_door_" + half)}
                if y:
                    v["y"] = y
                variants["facing=%s,half=%s,return=%s" % (f, half, rt)] = v
    blockstate("madness_door", {"variants": variants})
    # the black book on its pedestal of pages
    model("black_book", {"parent": "minecraft:block/block", "render_type": "minecraft:cutout",
                         "textures": {"particle": b("black_page_block"), "side": b("black_page_block"),
                                      "top": b("black_page_block_top"), "book": b("black_book")},
                         "elements": [
                             {"from": [3, 0, 3], "to": [13, 11, 13], "faces": {
                                 "north": {"texture": "#side"}, "south": {"texture": "#side"}, "east": {"texture": "#side"},
                                 "west": {"texture": "#side"}, "up": {"texture": "#top"}, "down": {"texture": "#top", "cullface": "down"}}},
                             {"from": [1, 11, 2], "to": [15, 13, 14], "faces": {
                                 "north": {"texture": "#top"}, "south": {"texture": "#top"}, "east": {"texture": "#top"},
                                 "west": {"texture": "#top"}, "up": {"texture": "#book"}, "down": {"texture": "#top"}}}]})
    variants = {}
    for f, y in rot.items():
        for mode in ("enter", "next", "exit"):
            v = {"model": b("black_book")}
            if y:
                v["y"] = y
            variants["facing=%s,mode=%s" % (f, mode)] = v
    blockstate("black_book", {"variants": variants})
    # items
    item_model("sigil_stone", {"parent": "minecraft:item/generated", "textures": {"layer0": SK + ":item/sigil_stone"}})
    item_model("black_book", {"parent": "minecraft:item/generated", "textures": {"layer0": SK + ":item/black_book"}})


def door_tex():
    wood = pal("2A1A3A", "3A2450", "4A3064", "5A3C78")
    for half in ("lower", "upper"):
        img = new()
        r = random.Random(360 + (half == "upper"))
        for y in range(16):
            for x in range(16):
                v = 0.45 + 0.3 * math.sin(x * 1.3) + (r.random() - 0.5) * 0.2
                c = pick(wood, v)
                if x in (0, 15) or (half == "upper" and y == 0) or (half == "lower" and y == 15):
                    c = hexc("1A1026")
                img.putpixel((x, y), c)
        if half == "upper":
            # a grinning face carved in the upper panel: one eye bright (Mania), one dark (Dementia)
            for (x, y) in ((4, 5), (5, 5), (4, 6), (5, 6)):
                img.putpixel((x, y), hexc("60F0E0"))
            for (x, y) in ((10, 5), (11, 5), (10, 6), (11, 6)):
                img.putpixel((x, y), hexc("0A0410"))
            for x in range(4, 12):
                img.putpixel((x, 10 + (1 if x in (5, 10) else 0) - (1 if x in (4, 11) else 0)), hexc("E8D8A0"))
        else:
            for (x, y) in ((12, 3), (12, 4), (13, 3), (13, 4)):
                img.putpixel((x, y), hexc("D8B040"))
        block_tex("madness_door_" + half, img)
    block_tex("madness_door_edge", speckled(362, wood, cell=2, grain=0.3))


if __name__ == "__main__":
    textures()
    items()
    skies()
    assets()
    print("realms: block, item and sky textures, models, blockstates and loot written")
