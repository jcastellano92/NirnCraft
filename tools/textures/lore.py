"""Generates the lore module's pixel art, models and generated data.

Writes into mod/src/main/resources:
  assets/nirncraft/textures/block/standing_stone_side.png, standing_stone_top.png, standing_stone_plinth.png
  assets/nirncraft/textures/block/standing_stone_<sign>_{upper,lower}.png         carved stone face with the sigil
  assets/nirncraft/textures/block/standing_stone_<sign>_{upper,lower}_glow.png    glowing sigil overlay (cutout)
  assets/nirncraft/textures/item/standing_stone_<sign>.png                         inventory icon
  assets/nirncraft/textures/item/book_<cover>.png                                  book covers (+ loose sheet)
  assets/nirncraft/textures/gui/book_spread.png (380x220), book_sheet.png (180x220)
  assets/nirncraft/models/block/standing_stone_{lower,upper}.json                  templates
  assets/nirncraft/models/block/standing_stone_<sign>_{lower,upper}.json
  assets/nirncraft/blockstates/standing_stone_<sign>.json
  assets/nirncraft/models/item/standing_stone_<sign>.json, book.json, book_<cover>.json
  data/nirncraft/loot_tables/chests/lore_books.json, lore_notes.json

Constellations are read from lore/client/Constellations.java and books from lore/LoreBooks.java so they never
drift. Run from anywhere: python3 tools/textures/lore.py
"""
import json
import math
import os
import random
import re

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
TEX = os.path.join(RES, "assets", "nirncraft", "textures")
MODELS = os.path.join(RES, "assets", "nirncraft", "models")
BLOCKSTATES = os.path.join(RES, "assets", "nirncraft", "blockstates")
DATA = os.path.join(RES, "data", "nirncraft")
JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "com", "nirncraft", "lore")

SIGNS = ["warrior", "mage", "thief", "lady", "lord", "lover", "atronach", "apprentice", "ritual", "serpent",
         "shadow", "steed", "tower"]

SIGN_COLORS = {
    "warrior": "#D85A48", "mage": "#5C8CF0", "thief": "#5CCB62", "lady": "#F0B8D8", "lord": "#E8C060",
    "lover": "#FF7FA0", "atronach": "#9070F0", "apprentice": "#70D0F0", "ritual": "#C8F0A0", "serpent": "#70F0B0",
    "shadow": "#A090C0", "steed": "#F0A050", "tower": "#E0E0F8",
}

COVERS = ["brown", "red", "green", "blue", "black", "sheet", "gold"]


def rgba(hex_color, a=255):
    hex_color = hex_color.lstrip("#")
    return (int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16), a)


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))


def save(img, *path):
    full = os.path.join(TEX, *path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full)
    return full


def write_json(path, obj):
    import sys
    from pathlib import Path
    native_tools263 = str(Path(__file__).resolve().parents[1])
    if native_tools263 not in sys.path: sys.path.insert(0, native_tools263)
    from data_format_263 import prepare
    path, obj = prepare(path, obj)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")
    return path


# --------------------------------------------------------------------------- sources

def read_constellations():
    with open(os.path.join(JAVA, "client", "Constellations.java"), encoding="utf-8") as f:
        src = f.read()
    stars_part, lines_part = src.split("public static int[][] lines", 1)
    pat = re.compile(r"case (\w+) -> new int\[\]\[\]\{(.*?)\};")

    def parse(part):
        out = {}
        for name, body in pat.findall(part):
            out[name.lower()] = [tuple(int(v) for v in p.split(",")) for p in re.findall(r"\{([^{}]*)\}", body)]
        return out

    return parse(stars_part), parse(lines_part)


def read_books():
    with open(os.path.join(JAVA, "LoreBooks.java"), encoding="utf-8") as f:
        src = f.read()
    pat = re.compile(r'book\("(\w+)", Category\.(\w+), (\w+), (\d+), (\d+)\);')
    return [(bid, cat.lower(), cover.lower(), int(w), int(v)) for bid, cat, cover, w, v in pat.findall(src)]


# --------------------------------------------------------------------------- stone textures

STONE_LIGHT = rgba("#8E8A80")
STONE_MID = rgba("#7E7A72")
STONE_DARK = rgba("#6A665E")
MOSS = rgba("#5E7240")
MOSS_DARK = rgba("#465A30")


def stone_canvas(seed, w=16, h=32):
    """Weathered grey-brown granite with vertical streaks."""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (w, h))
    px = img.load()
    streak = [rnd.uniform(0.9, 1.08) for _ in range(w)]
    for y in range(h):
        for x in range(w):
            r = rnd.random()
            base = STONE_MID if r < 0.55 else STONE_LIGHT if r < 0.8 else STONE_DARK
            f = streak[x] * (1.0 - 0.06 * ((y * 7 + x * 3) % 5 == 0))
            px[x, y] = shade(base, f)
    # speckles
    for _ in range(w * h // 16):
        x, y = rnd.randrange(w), rnd.randrange(h)
        px[x, y] = shade(px[x, y], rnd.choice([0.85, 1.1]))
    return img, rnd


def grid_to_canvas(gx, gy):
    """Sigil grid (12 x 22) -> pixel on the 16 x 32 face canvas (upper block = rows 0-15, lower = 16-31)."""
    return 3 + round(gx * 9 / 11), 7 + round(gy * 20 / 21)


def bresenham(x0, y0, x1, y1):
    pts = []
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy
    return pts


def face_mask():
    """Which canvas pixels belong to the visible stone face (matches the block model)."""
    mask = set()
    for y in range(32):
        for x in range(16):
            if 1 <= y <= 2 and 5 <= x <= 10:
                mask.add((x, y))
            elif 3 <= y <= 5 and 3 <= x <= 12:
                mask.add((x, y))
            elif 6 <= y <= 28 and 2 <= x <= 13:
                mask.add((x, y))
    return mask


def sigil_pixels(sign, stars, lines):
    pts = [grid_to_canvas(*p) for p in stars[sign]]
    line_px = set()
    for a, b in lines[sign]:
        line_px.update(bresenham(*pts[a], *pts[b]))
    return pts, line_px - set(pts)


def stone_face(sign, stars, lines):
    img, rnd = stone_canvas("face-" + sign)
    px = img.load()
    mask = face_mask()
    # darker rim around the face edges, moss creeping up from the bottom
    for (x, y) in mask:
        if (x - 1, y) not in mask or (x + 1, y) not in mask or (x, y - 1) not in mask:
            px[x, y] = shade(px[x, y], 0.82)
    for x in range(16):
        top = 22 + rnd.randrange(5)
        for y in range(top, 32):
            if rnd.random() < 0.35 + (y - top) * 0.08:
                px[x, y] = MOSS if rnd.random() < 0.6 else MOSS_DARK
    # carve the sigil: grooves are dark with a lit lower edge
    pts, line_px = sigil_pixels(sign, stars, lines)
    carve = rgba("#3A3833")
    for (x, y) in line_px | set(pts):
        px[x, y] = carve
        if (x, y + 1) not in line_px and (x, y + 1) not in pts and y + 1 < 32:
            px[x, y + 1] = shade(px[x, y + 1], 1.15)
    # a ring of small runes around the stars of the sigil
    for (x, y) in pts:
        for (dx, dy) in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx, ny = x + dx, y + dy
            if (nx, ny) in mask and (nx, ny) not in line_px and (nx, ny) not in pts:
                px[nx, ny] = shade(carve, 1.35)
    return img


def stone_glow(sign, stars, lines):
    img = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    px = img.load()
    color = rgba(SIGN_COLORS[sign])
    pts, line_px = sigil_pixels(sign, stars, lines)
    for (x, y) in line_px:
        px[x, y] = shade(color, 0.85)
    for (x, y) in pts:
        px[x, y] = mix(color, rgba("#FFFFFF"), 0.75)
        for (dx, dy) in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < 16 and 0 <= ny < 32 and px[nx, ny][3] == 0:
                px[nx, ny] = color
    return img


def split(img):
    return img.crop((0, 0, 16, 16)), img.crop((0, 16, 16, 32))


def stone_side():
    img, rnd = stone_canvas("side", 16, 16)
    px = img.load()
    for y in range(16):
        px[0, y] = shade(px[0, y], 0.8)
        px[15, y] = shade(px[15, y], 0.75)
    for _ in range(4):
        x, y = rnd.randrange(16), rnd.randrange(10, 16)
        px[x, y] = MOSS
    return img


def stone_top():
    img, rnd = stone_canvas("top", 16, 16)
    px = img.load()
    for i in range(16):
        px[i, 0] = shade(px[i, 0], 0.8)
        px[0, i] = shade(px[0, i], 0.8)
        px[i, 15] = shade(px[i, 15], 0.7)
        px[15, i] = shade(px[15, i], 0.7)
    for _ in range(6):
        px[rnd.randrange(16), rnd.randrange(16)] = MOSS
    return img


def stone_plinth():
    img, rnd = stone_canvas("plinth", 16, 16)
    px = img.load()
    for x in range(16):
        px[x, 0] = shade(px[x, 0], 1.1)
        px[x, 15] = shade(px[x, 15], 0.7)
        if x % 5 == 0:
            for y in range(16):
                px[x, y] = shade(px[x, y], 0.8)
    for _ in range(14):
        x, y = rnd.randrange(16), rnd.randrange(16)
        px[x, y] = MOSS if rnd.random() < 0.6 else MOSS_DARK
    return img


def stone_icon(sign, stars, lines):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    rnd = random.Random("icon-" + sign)
    for y in range(1, 16):
        for x in range(4, 12):
            if y <= 2 and not 6 <= x <= 9:
                continue
            if y >= 14:
                continue
            base = STONE_MID if rnd.random() < 0.6 else STONE_LIGHT
            if x == 4 or (y <= 2 and x == 6):
                base = shade(base, 1.12)
            if x == 11 or (y <= 2 and x == 9):
                base = shade(base, 0.75)
            px[x, y] = base
    for x in range(3, 13):
        px[x, 14] = shade(STONE_DARK, 1.0)
        px[x, 15] = shade(STONE_DARK, 0.8)
    color = rgba(SIGN_COLORS[sign])
    pts = [(5 + round(gx * 5 / 11), 3 + round(gy * 10 / 21)) for (gx, gy) in stars[sign]]
    for a, b in lines[sign]:
        for (x, y) in bresenham(*pts[a], *pts[b]):
            px[x, y] = shade(color, 0.7)
    for (x, y) in pts:
        px[x, y] = mix(color, rgba("#FFFFFF"), 0.45)
    return img


# --------------------------------------------------------------------------- books

COVER_COLORS = {
    # cover, band/emblem
    "brown": ("#6B4426", "#C8A050"),
    "red": ("#7A2018", "#D8B060"),
    "green": ("#2E5A30", "#C8B060"),
    "blue": ("#24406E", "#C8C8D8"),
    "black": ("#2A2428", "#B04040"),
    "gold": ("#9A7A30", "#F0E0A0"),
}


def book_icon(cover):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    if cover == "sheet":
        paper = rgba("#E8DDBE")
        ink = rgba("#6A5A40")
        for y in range(2, 15):
            for x in range(3, 13):
                px[x, y] = paper if (x + y) % 7 else shade(paper, 0.93)
        for x in range(3, 13):
            px[x, 14] = shade(paper, 0.8)
        for y in range(2, 15):
            px[12, y] = shade(paper, 0.85)
        # torn corner and a fold
        px[3, 2] = (0, 0, 0, 0)
        px[12, 2] = (0, 0, 0, 0)
        for y in (4, 6, 8, 10, 12):
            for x in range(4, 11 if y != 12 else 8):
                if (x * 3 + y) % 5:
                    px[x, y] = ink
        px[10, 12] = rgba("#9A2A20")
        px[9, 12] = rgba("#9A2A20")
        return img
    main_hex, band_hex = COVER_COLORS[cover]
    main = rgba(main_hex)
    band = rgba(band_hex)
    pages = rgba("#EDE3C8")
    # a closed book seen at a slight angle: cover, spine on the left, page block at the bottom/right
    for y in range(2, 14):
        for x in range(3, 13):
            px[x, y] = main
    for y in range(2, 14):
        px[3, y] = shade(main, 0.7)
        px[4, y] = shade(main, 0.85)
        px[12, y] = shade(main, 1.15)
    for x in range(3, 13):
        px[x, 2] = shade(main, 1.2)
    for x in range(4, 14):
        px[x, 14] = pages
        px[x, 15] = shade(pages, 0.8)
    for y in range(3, 15):
        px[13, y] = shade(pages, 0.9)
    # bands on the spine and a cover emblem
    for x in range(3, 5):
        px[x, 4] = band
        px[x, 11] = band
    for (dx, dy) in ((0, -2), (-1, -1), (0, -1), (1, -1), (-2, 0), (-1, 0), (1, 0), (2, 0), (-1, 1), (0, 1), (1, 1), (0, 2)):
        if abs(dx) + abs(dy) <= 2:
            px[8 + dx, 8 + dy] = band if (dx + dy) % 2 == 0 else shade(band, 0.8)
    px[8, 8] = shade(band, 1.2)
    return img


def parchment(w, h, seed, torn=False):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    base = rgba("#E9DCB8")
    dark = rgba("#CDB98C")
    # low-frequency blotches
    blobs = [(rnd.uniform(0, w), rnd.uniform(0, h), rnd.uniform(10, 40), rnd.uniform(0.05, 0.25)) for _ in range(14)]
    left_edge = [0] * h
    right_edge = [w] * h
    top_edge = [0] * w
    bottom_edge = [h] * w
    if torn:
        for y in range(h):
            left_edge[y] = rnd.randrange(0, 4)
            right_edge[y] = w - rnd.randrange(0, 4)
        for x in range(w):
            top_edge[x] = rnd.randrange(0, 4)
            bottom_edge[x] = h - rnd.randrange(0, 5)
    for y in range(h):
        for x in range(w):
            if x < left_edge[y] or x >= right_edge[y] or y < top_edge[x] or y >= bottom_edge[x]:
                continue
            t = 0.0
            for (bx, by, r, s) in blobs:
                d = math.hypot(x - bx, y - by)
                if d < r:
                    t += s * (1 - d / r)
            c = mix(base, dark, min(1.0, t))
            n = rnd.uniform(0.96, 1.03)
            px[x, y] = shade(c, n)
    # darken the edges
    for y in range(h):
        for x in range(w):
            if px[x, y][3] == 0:
                continue
            edge = min(x - left_edge[y], right_edge[y] - 1 - x, y - top_edge[x], bottom_edge[x] - 1 - y)
            if edge < 6:
                px[x, y] = shade(px[x, y], 0.78 + 0.035 * edge)
    return img, rnd


def book_spread():
    w, h = 380, 220
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    leather = rgba("#4A2E1A")
    rnd = random.Random("leather")
    # leather cover behind the pages, with rounded corners
    for y in range(h):
        for x in range(w):
            corner = min(x, w - 1 - x) < 4 and min(y, h - 1 - y) < 4 and math.hypot(min(x, w - 1 - x) - 4, min(y, h - 1 - y) - 4) > 4
            if corner:
                continue
            px[x, y] = shade(leather, rnd.uniform(0.85, 1.1))
    for x in range(w):
        for y in (2, h - 3):
            px[x, y] = rgba("#B8904A") if 6 < x < w - 7 else px[x, y]
    for y in range(h):
        for x in (2, w - 3):
            px[x, y] = rgba("#B8904A") if 6 < y < h - 7 else px[x, y]
    # page stacks (slightly offset sheets) and the two pages
    for (ox, label) in ((10, "left"), (194, "right")):
        page, _ = parchment(176, 204, "page-" + label)
        for i in range(3, 0, -1):
            off = i if label == "right" else -i
            for y in range(204):
                for x in range(176):
                    if page.getpixel((x, y))[3]:
                        tx, ty = ox + x + off, 8 + y + i
                        if 0 <= tx < w and 0 <= ty < h:
                            px[tx, ty] = shade(rgba("#D8C8A0"), 0.9 - 0.05 * i)
        img.alpha_composite(page, (ox, 8))
    # gutter shadow in the middle
    for y in range(8, 212):
        for x in range(176, 206):
            c = px[x, y]
            if c[3] == 0:
                continue
            d = abs(x - 190)
            f = 0.6 + 0.4 * min(1.0, d / 14)
            px[x, y] = shade(c, f)
    # a faded red ribbon bookmark hanging from the top
    for y in range(4, 26):
        for x in (188, 189, 190, 191):
            px[x, y] = shade(rgba("#8A2A22"), 0.9 if x in (188, 191) else 1.05)
    px[188, 26] = rgba("#8A2A22")
    px[191, 26] = rgba("#8A2A22")
    return img


def book_sheet():
    img, rnd = parchment(180, 220, "sheet", torn=True)
    px = img.load()
    # an old fold across the middle and a wax drip
    for x in range(180):
        if px[x, 110][3]:
            px[x, 110] = shade(px[x, 110], 0.88)
            px[x, 111] = shade(px[x, 111], 1.03)
    for (dx, dy) in [(0, 0), (1, 0), (0, 1), (1, 1), (2, 1), (-1, 1), (0, 2), (1, 2)]:
        px[158 + dx, 200 + dy] = rgba("#8A2A22")
    return img


# --------------------------------------------------------------------------- models & data

def stone_models(sign):
    written = []
    for half in ("lower", "upper"):
        written.append(write_json(os.path.join(MODELS, "block", f"standing_stone_{sign}_{half}.json"), {
            "parent": f"nirncraft:block/standing_stone_{half}",
            "render_type": "minecraft:cutout",
            "textures": {
                "face": f"nirncraft:block/standing_stone_{sign}_{half}",
                "glow": f"nirncraft:block/standing_stone_{sign}_{half}_glow",
            },
        }))
    variants = {}
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for half in ("lower", "upper"):
            v = {"model": f"nirncraft:block/standing_stone_{sign}_{half}"}
            if rot:
                v["y"] = rot
            variants[f"facing={facing},half={half}"] = v
    written.append(write_json(os.path.join(BLOCKSTATES, f"standing_stone_{sign}.json"), {"variants": variants}))
    written.append(write_json(os.path.join(MODELS, "item", f"standing_stone_{sign}.json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"nirncraft:item/standing_stone_{sign}"},
    }))
    return written


def face(texture, **extra):
    d = {"texture": texture}
    d.update(extra)
    return d


GLOW_FACE = {"forge_data": {"block_light": 15, "sky_light": 15}}


def template_models():
    lower = {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "textures": {"particle": "nirncraft:block/standing_stone_side", "side": "nirncraft:block/standing_stone_side",
                     "top": "nirncraft:block/standing_stone_top", "plinth": "nirncraft:block/standing_stone_plinth"},
        "elements": [
            {"from": [1, 0, 4], "to": [15, 3, 12], "faces": {
                "north": face("#plinth"), "south": face("#plinth"), "east": face("#plinth"), "west": face("#plinth"),
                "up": face("#top"), "down": face("#top", cullface="down")}},
            {"from": [2, 3, 5], "to": [14, 16, 11], "faces": {
                "north": face("#face"), "south": face("#face"), "east": face("#side"), "west": face("#side")}},
            {"from": [2, 3, 4.9], "to": [14, 16, 11.1], "shade": False, "faces": {
                "north": face("#glow", **GLOW_FACE), "south": face("#glow", **GLOW_FACE)}},
        ],
    }
    upper = {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "textures": {"particle": "nirncraft:block/standing_stone_side", "side": "nirncraft:block/standing_stone_side",
                     "top": "nirncraft:block/standing_stone_top"},
        "elements": [
            {"from": [2, 0, 5], "to": [14, 10, 11], "faces": {
                "north": face("#face"), "south": face("#face"), "east": face("#side"), "west": face("#side"),
                "up": face("#top")}},
            {"from": [3, 10, 5], "to": [13, 13, 11], "faces": {
                "north": face("#face"), "south": face("#face"), "east": face("#side"), "west": face("#side"),
                "up": face("#top")}},
            {"from": [5, 13, 5], "to": [11, 15, 11], "faces": {
                "north": face("#face"), "south": face("#face"), "east": face("#side"), "west": face("#side"),
                "up": face("#top")}},
            {"from": [2, 0, 4.9], "to": [14, 10, 11.1], "shade": False, "faces": {
                "north": face("#glow", **GLOW_FACE), "south": face("#glow", **GLOW_FACE)}},
        ],
    }
    return [write_json(os.path.join(MODELS, "block", "standing_stone_lower.json"), lower),
            write_json(os.path.join(MODELS, "block", "standing_stone_upper.json"), upper)]


def book_models():
    written = []
    overrides = []
    for i, cover in enumerate(COVERS):
        written.append(write_json(os.path.join(MODELS, "item", f"book_{cover}.json"), {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"nirncraft:item/book_{cover}"},
        }))
        if i > 0:
            overrides.append({"predicate": {"nirncraft:cover": round(i / 10, 2)}, "model": f"nirncraft:item/book_{cover}"})
    written.append(write_json(os.path.join(MODELS, "item", "book.json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "nirncraft:item/book_brown"},
        "overrides": overrides,
    }))
    return written


def loot_tables(books):
    def entry(bid, w, v):
        return {"type": "minecraft:item", "name": "nirncraft:book", "weight": w,
                "functions": [{"function": "minecraft:set_nbt", "tag": '{book:"%s",nirncraft_value:%d}' % (bid, v)}]}

    all_books = {"type": "minecraft:chest", "pools": [
        {"rolls": 1, "entries": [entry(b, w, v) for (b, _c, _cv, w, v) in books]}]}
    notes = {"type": "minecraft:chest", "pools": [
        {"rolls": 1, "entries": [entry(b, w, v) for (b, c, _cv, w, v) in books if c in ("note", "letter")]}]}
    return [write_json(os.path.join(DATA, "loot_table", "chests", "lore_books.json"), all_books),
            write_json(os.path.join(DATA, "loot_table", "chests", "lore_notes.json"), notes)]


def main():
    stars, lines = read_constellations()
    books = read_books()
    assert set(stars) == set(SIGNS) == set(lines), (sorted(stars), sorted(lines))
    assert len(books) >= 30, len(books)
    written = []
    written.append(save(stone_side(), "block", "standing_stone_side.png"))
    written.append(save(stone_top(), "block", "standing_stone_top.png"))
    written.append(save(stone_plinth(), "block", "standing_stone_plinth.png"))
    for sign in SIGNS:
        upper, lower = split(stone_face(sign, stars, lines))
        gu, gl = split(stone_glow(sign, stars, lines))
        written.append(save(upper, "block", f"standing_stone_{sign}_upper.png"))
        written.append(save(lower, "block", f"standing_stone_{sign}_lower.png"))
        written.append(save(gu, "block", f"standing_stone_{sign}_upper_glow.png"))
        written.append(save(gl, "block", f"standing_stone_{sign}_lower_glow.png"))
        written.append(save(stone_icon(sign, stars, lines), "item", f"standing_stone_{sign}.png"))
        written.extend(stone_models(sign))
    written.extend(template_models())
    for cover in COVERS:
        written.append(save(book_icon(cover), "item", f"book_{cover}.png"))
    written.extend(book_models())
    written.append(save(book_spread(), "gui", "book_spread.png"))
    written.append(save(book_sheet(), "gui", "book_sheet.png"))
    written.extend(loot_tables(books))
    print("wrote %d files (%d books)" % (len(written), len(books)))


if __name__ == "__main__":
    main()
