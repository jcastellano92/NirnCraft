"""Generates the magic module's pixel art and per-spell tome assets.

Writes into mod/src/main/resources:
  assets/nirncraft/textures/item/spell_tome_<school>.png      one cover per school of magic
  assets/nirncraft/textures/item/bound_sword.png, bound_battleaxe.png, bound_bow.png, bound_bow_pulling_0..2.png
  assets/nirncraft/textures/block/word_wall_front.png, word_wall_side.png, word_wall_top.png
  assets/nirncraft/textures/mob_effect/<effect>.png           18x18 status effect icons
  assets/nirncraft/models/item/spell_tome_<spell>.json        every tome points at its school's texture
  data/nirncraft/tags/items/spell_tomes.json
  data/nirncraft/loot_tables/chests/spell_tomes.json          weighted by spell level
  data/nirncraft/nirncraft_values/magic.json                   gold values of tomes (economy module)

The spell list is read from mod/src/main/java/com/nirncraft/magic/spell/Spells.java so it can never drift.
Run from anywhere: python3 tools/textures/magic.py
"""
import json
import os
import random
import re

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
TEX = os.path.join(RES, "assets", "nirncraft", "textures")
MODELS = os.path.join(RES, "assets", "nirncraft", "models")
DATA = os.path.join(RES, "data", "nirncraft")
SPELLS_JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "com", "nirncraft", "magic", "spell", "Spells.java")


def rgba(hex_color, a=255):
    hex_color = hex_color.lstrip("#")
    return (int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16), a)


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


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


# --------------------------------------------------------------------------- spell tomes

SCHOOLS = {
    # school: (cover, emblem, emblem highlight)
    "destruction": ("#7A1A12", "#FF8A2A", "#FFE070"),
    "restoration": ("#9A7420", "#FFF4C0", "#FFFFFF"),
    "alteration": ("#24604A", "#9FE8C8", "#E8FFF4"),
    "conjuration": ("#4E2470", "#C890FF", "#F0D8FF"),
    "illusion": ("#22407E", "#FF9AD8", "#FFE0F4"),
    "mysticism": ("#1E5A5A", "#7FF0E0", "#E8FFFC"),
}

EMBLEMS = {
    # 5x5 masks, '#' = emblem, '+' = highlight
    "destruction": ["..+..", ".+#..", ".##+.", "####.", ".###."],   # a flame
    "restoration": ["+.#.+", ".###.", "##+##", ".###.", "+.#.+"],   # a sun
    "alteration": ["..#..", ".#+#.", "#+.+#", ".#+#.", "..#.."],    # a crystal
    "conjuration": [".###.", "#...#", "#.+.#", "#...#", ".###."],   # a portal
    "illusion": [".....", ".###.", "#+#+#", ".###.", "....."],      # an eye
    "mysticism": ["#...#", ".#+#.", "..#..", ".#+#.", "#...#"],     # crossed paths of the atlas
}


def spell_tome(school):
    cover_hex, emblem_hex, hi_hex = SCHOOLS[school]
    cover = rgba(cover_hex)
    emblem = rgba(emblem_hex)
    hi = rgba(hi_hex)
    gold = rgba("#D8B060")
    page = rgba("#EDE3C8")
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    rnd = random.Random(school)
    # pages (right and bottom edge)
    for y in range(2, 15):
        px[13, y] = page if y % 2 else shade(page, 0.85)
    for x in range(3, 14):
        px[x, 14] = shade(page, 0.8)
    # cover
    for y in range(1, 14):
        for x in range(3, 13):
            c = shade(cover, 0.92 + rnd.random() * 0.16)
            px[x, y] = c
    # spine
    for y in range(1, 15):
        px[2, y] = shade(cover, 0.6)
        px[3, y] = shade(cover, 0.75)
    # bands on the spine
    for y in (3, 11):
        px[2, y] = gold
        px[3, y] = shade(gold, 0.8)
    # border & corners
    for x in range(3, 13):
        px[x, 1] = shade(cover, 1.25)
        px[x, 13] = shade(cover, 0.7)
    for y in range(1, 14):
        px[12, y] = shade(cover, 0.8)
    for (cx, cy) in ((4, 2), (11, 2), (4, 12), (11, 12)):
        px[cx, cy] = gold
    # emblem
    mask = EMBLEMS[school]
    for j, row in enumerate(mask):
        for i, ch in enumerate(row):
            if ch == "#":
                px[5 + i, 5 + j] = emblem
            elif ch == "+":
                px[5 + i, 5 + j] = hi
    return img


# --------------------------------------------------------------------------- bound weapons

VIOLET = rgba("#B080FF", 200)
VIOLET_HI = rgba("#F0E0FF", 230)
VIOLET_DARK = rgba("#5A2AA0", 210)
GLOW = rgba("#C8A0FF", 90)


def glow_outline(img):
    """Adds a faint glow around every opaque pixel."""
    px = img.load()
    w, h = img.size
    solid = [(x, y) for y in range(h) for x in range(w) if px[x, y][3] > 120]
    for (x, y) in solid:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and px[nx, ny][3] == 0:
                px[nx, ny] = GLOW


def bound_sword():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # blade: diagonal from (5,10) up to (14,1)
    for i in range(10):
        x, y = 5 + i, 10 - i
        px[x, y] = VIOLET_HI if i % 3 == 0 else VIOLET
        if x + 1 < 16:
            px[x + 1, y] = VIOLET
        if y + 1 < 16 and i > 0:
            px[x, y + 1] = VIOLET_DARK
    px[14, 1] = VIOLET_HI
    px[15, 0] = VIOLET_HI
    # crossguard
    for (x, y) in ((2, 9), (3, 10), (4, 11), (6, 13), (7, 12), (5, 12)):
        px[x, y] = VIOLET_DARK
    px[3, 9] = VIOLET
    px[7, 13] = VIOLET
    # grip and pommel
    for (x, y) in ((4, 12), (3, 13), (2, 14)):
        px[x, y] = rgba("#3A1A60", 230)
    px[1, 15] = VIOLET_HI
    glow_outline(img)
    return img


def bound_dagger():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # short blade: diagonal from (6,9) up to (12,3)
    for i in range(7):
        x, y = 6 + i, 9 - i
        px[x, y] = VIOLET_HI if i % 3 == 0 else VIOLET
        if y + 1 < 16 and i > 0:
            px[x, y + 1] = VIOLET_DARK
    px[13, 2] = VIOLET_HI
    # small curved guard
    for (x, y) in ((4, 9), (5, 10), (6, 11), (7, 11)):
        px[x, y] = VIOLET_DARK
    px[3, 8] = VIOLET
    # grip and pommel
    for (x, y) in ((5, 11), (4, 12), (3, 13)):
        px[x, y] = rgba("#3A1A60", 230)
    px[2, 14] = VIOLET_HI
    glow_outline(img)
    return img


def altar_texture(part):
    """Spellmaking Altar: dark stone with inlaid silver runes and a glowing amethyst top."""
    rnd = random.Random("altar_" + part)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    stone = rgba("#4A4458")
    for y in range(16):
        for x in range(16):
            px[x, y] = shade(stone, 0.85 + rnd.random() * 0.25)
    if part == "top":
        glow = rgba("#C8A0FF")
        hi = rgba("#F0E0FF")
        for y in range(16):
            for x in range(16):
                d = abs(x - 7.5) + abs(y - 7.5)
                if d < 5.5:
                    px[x, y] = shade(glow, 0.7 + (5.5 - d) / 10)
                elif abs(d - 6.5) < 0.6:
                    px[x, y] = rgba("#D8D8E8")
        px[7, 7] = hi
        px[8, 8] = hi
    elif part == "side":
        rune = rgba("#C8C8E0")
        for x in range(16):
            px[x, 0] = shade(stone, 1.3)
            px[x, 15] = shade(stone, 0.6)
        for gx in (3, 7, 11):
            for (dx, dy) in ((0, 0), (1, 1), (2, 0), (1, 2), (1, 3)):
                px[gx + dx, 5 + dy] = rune
        for x in range(2, 14):
            px[x, 11] = shade(rgba("#9A7AE0"), 1.0 if x % 3 else 1.3)
    else:  # base
        for x in range(16):
            px[x, 0] = shade(stone, 1.25)
        for y in range(16):
            px[0, y] = shade(stone, 0.7)
            px[15, y] = shade(stone, 0.7)
    return img


def bound_battleaxe():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # haft
    for i in range(12):
        px[2 + i, 14 - i] = rgba("#3A1A60", 230) if i < 4 else VIOLET_DARK
    # axe head: a wide crescent blade bound to the top of the haft
    head = [
        "..##.....",
        ".#++#....",
        "#+++##...",
        "#++++##..",
        "#+++++#..",
        ".#++++##.",
        "..#+++++#",
        "...##+++#",
        ".....###.",
    ]
    for j, row in enumerate(head):
        for i, ch in enumerate(row):
            x, y = 7 + i, 0 + j
            if x < 16 and y < 16:
                if ch == "#":
                    px[x, y] = VIOLET
                elif ch == "+":
                    px[x, y] = VIOLET_HI if (i + j) % 5 == 0 else rgba("#C8A8FF", 200)
    # haft over the head
    for i in range(8, 12):
        px[2 + i, 14 - i] = VIOLET_DARK
    glow_outline(img)
    return img


def bound_bow(pull):
    """pull: -1 idle, 0..2 pulling stages."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # bow limb arc from top-left to bottom-right, bulging to the top-right (like vanilla)
    limb = [(3, 1), (4, 1), (5, 1), (6, 2), (7, 2), (8, 3), (9, 3), (10, 4), (11, 5), (12, 6), (12, 7), (13, 8), (13, 9),
            (14, 10), (14, 11), (14, 12), (14, 13)]
    for i, (x, y) in enumerate(limb):
        px[x, y] = VIOLET_HI if i % 4 == 0 else VIOLET
        if y + 1 < 16 and px[x, y + 1][3] == 0:
            px[x, y + 1] = VIOLET_DARK
    # grip
    for (x, y) in ((9, 5), (10, 6), (10, 5)):
        px[x, y] = rgba("#3A1A60", 230)
    # string from (2,1) to (14,14) pulled toward bottom-left by pull stage
    offset = 0 if pull < 0 else pull + 1
    for t in range(0, 14):
        x = 2 + t * 12 / 13
        y = 1 + t * 13 / 13
        # bend the string at its middle
        mid = 1 - abs(t - 6.5) / 6.5
        x -= offset * mid
        y += offset * mid
        xi, yi = int(round(x)), int(round(y))
        if 0 <= xi < 16 and 0 <= yi < 16 and px[xi, yi][3] == 0:
            px[xi, yi] = rgba("#E8D8FF", 160)
    # conjured arrow when pulling
    if pull >= 0:
        nock_x, nock_y = int(round(8 - offset)), int(round(7 + offset))
        for i in range(7 + pull):
            x, y = nock_x + i, nock_y - i
            if 0 <= x < 16 and 0 <= y < 16:
                px[x, y] = VIOLET_HI if i >= 5 + pull else rgba("#A070F0", 220)
    glow_outline(img)
    # vanilla orientation: the belly faces the top-left (the display transforms of item/bow assume it)
    return img.transpose(0)


# --------------------------------------------------------------------------- word wall

def stone_base(seed, dark=1.0):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    base = rgba("#7C7A72")
    for y in range(16):
        for x in range(16):
            n = 0.82 + rnd.random() * 0.22
            px[x, y] = shade(base, n * dark)
    # weathering: cracks and moss
    for _ in range(3):
        x, y = rnd.randrange(16), rnd.randrange(16)
        for _ in range(rnd.randrange(3, 7)):
            px[x % 16, y % 16] = shade(base, 0.55 * dark)
            x += rnd.choice((-1, 0, 1))
            y += 1
    for _ in range(5):
        x, y = rnd.randrange(16), rnd.randrange(12, 16)
        px[x, y] = rgba("#5E7240")
    return img


def word_wall_front():
    img = stone_base("front", 0.95)
    px = img.load()
    carve = rgba("#3A3A36")
    glow = rgba("#BFF4FF")
    glow2 = rgba("#7FD8F0")
    # Dragon script: rows of claw-mark glyphs (strokes and dots), carved and glowing.
    rnd = random.Random("script")
    strokes = [((0, 0), (1, 1), (2, 2)), ((2, 0), (1, 1), (0, 2)), ((1, 0), (1, 1), (1, 2)), ((0, 0), (0, 1), (1, 2)),
               ((2, 0), (2, 1), (1, 2)), ((0, 1), (1, 1), (2, 1))]
    for row_y in (1, 6, 11):
        x = 1
        while x <= 12:
            cells = set(rnd.choice(strokes))
            if rnd.random() < 0.6:
                cells |= set(rnd.choice(strokes))
            if rnd.random() < 0.5:
                cells.add((rnd.randrange(3), 3))
            for (gx, gy) in cells:
                px[x + gx, row_y + gy] = glow if rnd.random() < 0.75 else glow2
            for (gx, gy) in cells:
                bx, by = x + gx + 1, row_y + gy + 1
                if bx < 16 and by < 16 and (bx - x, by - row_y) not in cells:
                    px[bx, by] = carve
            x += 4
    # carved frame
    for i in range(16):
        px[i, 0] = shade(px[i, 0], 0.7)
        px[i, 15] = shade(px[i, 15], 0.6)
        px[0, i] = shade(px[0, i], 0.7)
        px[15, i] = shade(px[15, i], 0.6)
    return img


def word_wall_side():
    img = stone_base("side", 0.9)
    px = img.load()
    for i in range(16):
        px[i, 0] = shade(px[i, 0], 0.7)
        px[i, 15] = shade(px[i, 15], 0.6)
    return img


def word_wall_top():
    img = stone_base("top", 1.0)
    px = img.load()
    for i in range(16):
        px[i, 0] = shade(px[i, 0], 0.75)
        px[0, i] = shade(px[0, i], 0.75)
        px[i, 15] = shade(px[i, 15], 0.65)
        px[15, i] = shade(px[15, i], 0.65)
    return img


# --------------------------------------------------------------------------- effect icons (18x18)

ICONS = {
    # name: (main color, accent, shape)
    "soul_trap": ("#8A4FD8", "#E8D0FF", "gem"),
    "oakflesh": ("#9A7040", "#D8B080", "shield"),
    "stoneflesh": ("#8A8A86", "#D0D0C8", "shield"),
    "ironflesh": ("#C8C8D0", "#FFFFFF", "shield"),
    "ebonyflesh": ("#3A2A4A", "#8A6AA8", "shield"),
    "ethereal": ("#A8E8FF", "#FFFFFF", "ghost"),
    "muffle": ("#6A7AA0", "#C0CCE8", "feather"),
    "calm": ("#9AD0F0", "#FFFFFF", "wave"),
    "feared": ("#404060", "#A0A0D0", "skull"),
    "frenzied": ("#D02020", "#FF9A60", "burst"),
    "marked_for_death": ("#7A0A0A", "#FF5050", "skull"),
    "dragonhide": ("#6A8A5A", "#D8E8B0", "scales"),
    "reflect_spell": ("#9AB8C8", "#FFFFFF", "mirror"),
    "spell_absorption": ("#3A6AC8", "#B8D8FF", "gem"),
    "levitate": ("#D8E0F0", "#FFFFFF", "feather"),
    "water_walking": ("#3A88C8", "#C8ECFF", "wave"),
    "flame_cloak": ("#D85A1A", "#FFD070", "burst"),
    "frost_cloak": ("#7AB8E0", "#FFFFFF", "burst"),
    "lightning_cloak": ("#8A8AE0", "#F0F0FF", "burst"),
    "stendarrs_aura": ("#D8B040", "#FFF4C0", "sun"),
    "ash_shell": ("#5A5550", "#A8A090", "shield"),
    "time_slowed": ("#6A7AC0", "#D8E0FF", "hourglass"),
    "dragon_aspect": ("#C88A2A", "#FFE0A0", "scales"),
    "ice_form": ("#A8D8F0", "#FFFFFF", "gem"),
}

SHAPES = {
    "gem": [
        "......####......",
        ".....#++++#.....",
        "....#++##++#....",
        "...#+######+#...",
        "..#+########+#..",
        "..############..",
        "...##########...",
        "....########....",
        ".....######.....",
        "......####......",
        ".......##.......",
    ],
    "shield": [
        "..############..",
        "..#++++++++++#..",
        "..#+########+#..",
        "..#+########+#..",
        "..#+###++###+#..",
        "..#+###++###+#..",
        "...#+######+#...",
        "...#+######+#...",
        "....#+####+#....",
        ".....#+##+#.....",
        "......#++#......",
        ".......##.......",
    ],
    "ghost": [
        ".....######.....",
        "....#++++++#....",
        "...#++++++++#...",
        "...#+#++++#+#...",
        "...#+#++++#+#...",
        "...#++++++++#...",
        "...#++++++++#...",
        "...#++++++++#...",
        "...#++#++#++#...",
        "...#.#.##.#.#...",
    ],
    "feather": [
        "...........##...",
        "..........#++#..",
        ".........#+##+..",
        "........#+##+#..",
        ".......#+##+#...",
        "......#+##+#....",
        ".....#+##+#.....",
        "....#+##+#......",
        "...#+++#........",
        "..##............",
        ".#..............",
    ],
    "wave": [
        "................",
        "..###......###..",
        ".#+++#....#+++#.",
        "#+...+#..#+...+#",
        "......#++#......",
        ".......##.......",
        "..###......###..",
        ".#+++#....#+++#.",
        "#+...+#..#+...+#",
        "......#++#......",
        ".......##.......",
    ],
    "skull": [
        "....########....",
        "...#++++++++#...",
        "..#++++++++++#..",
        "..#+##++++##+#..",
        "..#+##++++##+#..",
        "..#++++##++++#..",
        "...#+++##+++#...",
        "....#+#++#+#....",
        "....#+#++#+#....",
        ".....######.....",
    ],
    "scales": [
        "..##..##..##....",
        ".#++##++##++#...",
        "#+##+#+##+#+#...",
        ".##..##..##.##..",
        "..##..##..##..#.",
        ".#++##++##++##..",
        "#+##+#+##+#+##..",
        ".##..##..##.....",
        "..##..##..##....",
        ".#++##++##++#...",
        "..##..##..##....",
    ],
    "mirror": [
        ".....######.....",
        "....#++++++#....",
        "...#+######+#...",
        "...#+#++++#+#...",
        "...#+#+..+#+#...",
        "...#+#+..+#+#...",
        "...#+#++++#+#...",
        "...#+######+#...",
        "....#++++++#....",
        ".....######.....",
        ".......##.......",
        "......####......",
    ],
    "sun": [
        ".......##.......",
        "..#....##....#..",
        "...#..####..#...",
        "....##++++##....",
        "...#++####++#...",
        "####+######+####",
        "...#++####++#...",
        "....##++++##....",
        "...#..####..#...",
        "..#....##....#..",
        ".......##.......",
    ],
    "hourglass": [
        "...##########...",
        "....#++++++#....",
        ".....#++++#.....",
        "......#++#......",
        ".......##.......",
        "......#..#......",
        ".....#.++.#.....",
        "....#.++++.#....",
        "...##########...",
    ],
    "burst": [
        ".......##.......",
        "...#...##...#...",
        "....#..++..#....",
        ".....#+##+#.....",
        "..##+######+##..",
        "##++########++##",
        "..##+######+##..",
        ".....#+##+#.....",
        "....#..++..#....",
        "...#...##...#...",
        ".......##.......",
    ],
}


def effect_icon(name):
    main_hex, accent_hex, shape = ICONS[name]
    main = rgba(main_hex)
    accent = rgba(accent_hex)
    img = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    px = img.load()
    rows = SHAPES[shape]
    oy = (18 - len(rows)) // 2
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            x, y = 1 + i, oy + j
            if ch == "#":
                px[x, y] = shade(main, 0.55)
            elif ch == "+":
                above = rows[j - 1][i] if j > 0 else "."
                left = row[i - 1] if i > 0 else "."
                px[x, y] = accent if (above != "+" or left != "+") else main
    # dark outline
    out = img.copy()
    opx = out.load()
    for y in range(18):
        for x in range(18):
            if px[x, y][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < 18 and 0 <= ny < 18 and px[nx, ny][3] > 0:
                        opx[x, y] = (20, 16, 24, 200)
                        break
    return out


# --------------------------------------------------------------------------- spell list & derived data

TIER_WEIGHT = {"NOV": 20, "APP": 10, "ADE": 5, "EXP": 2, "MAS": 1}
TIER_VALUE = {"NOV": 50, "APP": 150, "ADE": 350, "EXP": 750, "MAS": 1500}
SCHOOL_CODE = {"D": "destruction", "R": "restoration", "A": "alteration", "C": "conjuration", "I": "illusion", "M": "mysticism"}


def altar_models():
    """Block model, blockstate, item model and loot table of the Spellmaking Altar (a pedestal with a slab top)."""
    model = {
        "parent": "minecraft:block/block",
        "textures": {"particle": "nirncraft:block/spellmaking_altar_side", "top": "nirncraft:block/spellmaking_altar_top",
                     "side": "nirncraft:block/spellmaking_altar_side", "base": "nirncraft:block/spellmaking_altar_base"},
        "elements": [
            {"from": [1, 0, 1], "to": [15, 3, 15], "faces": {
                "down": {"texture": "#base"}, "up": {"texture": "#base"}, "north": {"texture": "#base"}, "south": {"texture": "#base"},
                "west": {"texture": "#base"}, "east": {"texture": "#base"}}},
            {"from": [4, 3, 4], "to": [12, 11, 12], "faces": {
                "north": {"texture": "#side"}, "south": {"texture": "#side"}, "west": {"texture": "#side"}, "east": {"texture": "#side"}}},
            {"from": [0, 11, 0], "to": [16, 14, 16], "faces": {
                "down": {"texture": "#base"}, "up": {"texture": "#top"}, "north": {"texture": "#side", "uv": [0, 0, 16, 3]},
                "south": {"texture": "#side", "uv": [0, 0, 16, 3]}, "west": {"texture": "#side", "uv": [0, 0, 16, 3]},
                "east": {"texture": "#side", "uv": [0, 0, 16, 3]}}},
        ],
    }
    write_json(os.path.join(MODELS, "block", "spellmaking_altar.json"), model)
    write_json(os.path.join(MODELS, "item", "spellmaking_altar.json"), {"parent": "nirncraft:block/spellmaking_altar"})
    states = {"variants": {f"facing={f}": ({"model": "nirncraft:block/spellmaking_altar", "y": r} if r else {"model": "nirncraft:block/spellmaking_altar"})
                           for f, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}}
    write_json(os.path.join(RES, "assets", "nirncraft", "blockstates", "spellmaking_altar.json"), states)
    write_json(os.path.join(DATA, "loot_table", "blocks", "spellmaking_altar.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "nirncraft:spellmaking_altar"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
    })


def read_spells():
    with open(SPELLS_JAVA, encoding="utf-8") as f:
        src = f.read()
    out = []
    for m in re.finditer(r'add\(s\("([a-z_]+)",\s*([DRACIM]),\s*(NOV|APP|ADE|EXP|MAS)\)', src):
        out.append((m.group(1), SCHOOL_CODE[m.group(2)], m.group(3)))
    return out


def main():
    written = []
    for school in SCHOOLS:
        written.append(save(spell_tome(school), "item", f"spell_tome_{school}.png"))
    written.append(save(bound_sword(), "item", "bound_sword.png"))
    written.append(save(bound_battleaxe(), "item", "bound_battleaxe.png"))
    written.append(save(bound_dagger(), "item", "bound_dagger.png"))
    write_json(os.path.join(MODELS, "item", "bound_dagger.json"),
               {"parent": "minecraft:item/handheld", "textures": {"layer0": "nirncraft:item/bound_dagger"}})
    for part in ("top", "side", "base"):
        written.append(save(altar_texture(part), "block", f"spellmaking_altar_{part}.png"))
    altar_models()
    written.append(save(bound_bow(-1), "item", "bound_bow.png"))
    for stage in range(3):
        written.append(save(bound_bow(stage), "item", f"bound_bow_pulling_{stage}.png"))
    written.append(save(word_wall_front(), "block", "word_wall_front.png"))
    written.append(save(word_wall_side(), "block", "word_wall_side.png"))
    written.append(save(word_wall_top(), "block", "word_wall_top.png"))
    for name in ICONS:
        written.append(save(effect_icon(name), "mob_effect", f"{name}.png"))

    spells = read_spells()
    for spell_id, school, _tier in spells:
        write_json(os.path.join(MODELS, "item", f"spell_tome_{spell_id}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/spell_tome_{school}"}})
    write_json(os.path.join(DATA, "tags", "item", "spell_tomes.json"),
               {"replace": False, "values": [f"nirncraft:spell_tome_{s}" for s, _, _ in spells]})
    write_json(os.path.join(DATA, "loot_table", "chests", "spell_tomes.json"), {
        "type": "minecraft:chest",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"nirncraft:spell_tome_{s}", "weight": TIER_WEIGHT[t]} for s, _, t in spells],
        }],
    })
    values = {f"nirncraft:spell_tome_{s}": TIER_VALUE[t] for s, _, t in spells}
    values.update({"nirncraft:bound_sword": 0, "nirncraft:bound_battleaxe": 0, "nirncraft:bound_bow": 0, "nirncraft:bound_dagger": 0,
                   "nirncraft:word_wall": 0, "nirncraft:spellmaking_altar": 400})
    write_json(os.path.join(DATA, "nirncraft_values", "magic.json"), {"values": values})
    print(f"wrote {len(written)} textures and assets for {len(spells)} spells")


if __name__ == "__main__":
    main()
