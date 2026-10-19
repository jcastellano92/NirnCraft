"""Generates the dungeons module's Dwemer automaton textures (PIL).

Writes PNGs into mod/src/main/resources/assets/nirncraft/textures/entity/dwemer/:
  dwarven_spider.png      64x32   (DwarvenSpiderModel UVs)
  dwarven_sphere.png      64x64   (DwarvenSphereModel UVs)
  dwarven_centurion.png   128x64  (DwarvenCenturionModel UVs)

Every model box is painted face by face from the same UV layout as the Java models: brushed bronze with darker
seams, rivets along the edges, verdigris in the crevices and soul-blue / furnace-orange glows where the eyes and
boilers are. Deterministic (fixed seeds). Run:  python3 tools/textures/dungeons.py
"""
import json
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures", "entity", "dwemer")

BRONZE = (178, 128, 60)
BRONZE_DARK = (112, 72, 32)
BRONZE_LIGHT = (232, 186, 102)
VERDIGRIS = (92, 150, 128)
IRON = (70, 66, 64)
SOUL = (96, 224, 255)
FURNACE = (255, 168, 64)


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3])


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def box_faces(u, v, w, h, d):
    """UV rectangles (x, y, width, height) of a Minecraft model box at texOffs (u, v) with size (w, h, d)."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
    }


def metal(img, rect, rnd, base=BRONZE, rivets=True, patina=0.08, light=1.0):
    """Brushed metal panel: horizontal brushing, bevelled edges, rivets in the corners, a little verdigris."""
    x0, y0, w, h = rect
    px = img.load()
    for y in range(h):
        streak = rnd.uniform(-0.06, 0.06)
        for x in range(w):
            f = light * (1.0 + streak + rnd.uniform(-0.05, 0.05))
            c = shade(base, f)
            if y == 0 or x == 0:
                c = mix(c, BRONZE_LIGHT, 0.45)
            elif y == h - 1 or x == w - 1:
                c = mix(c, BRONZE_DARK, 0.55)
            if rnd.random() < patina and 0 < x < w - 1 and 0 < y < h - 1:
                c = mix(c, VERDIGRIS, rnd.uniform(0.4, 0.8))
            px[x0 + x, y0 + y] = c + (255,)
    if rivets and w >= 4 and h >= 4:
        for rx, ry in ((1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)):
            px[x0 + rx, y0 + ry] = BRONZE_LIGHT + (255,)


def seam(img, rect, horizontal=True, at=0.5, color=BRONZE_DARK):
    x0, y0, w, h = rect
    px = img.load()
    if horizontal:
        y = y0 + int(h * at)
        for x in range(x0, x0 + w):
            px[x, y] = color + (255,)
    else:
        x = x0 + int(w * at)
        for y in range(y0, y0 + h):
            px[x, y] = color + (255,)


def glow(img, rect, color, core=True):
    x0, y0, w, h = rect
    px = img.load()
    for y in range(h):
        for x in range(w):
            edge = x in (0, w - 1) or y in (0, h - 1)
            c = mix(color, (255, 255, 255), 0.45) if core and not edge else color
            px[x0 + x, y0 + y] = c + (255,)


def grate(img, rect, color):
    """Dark vertical slats over a glowing back (boilers, helm grills)."""
    x0, y0, w, h = rect
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x0 + x, y0 + y] = (IRON if x % 2 == 0 else color) + (255,)


def paint_box(img, u, v, w, h, d, rnd, **kw):
    for name, rect in box_faces(u, v, w, h, d).items():
        light = 1.12 if name == "top" else 0.78 if name == "bottom" else 0.95 if name in ("left", "right") else 1.0
        metal(img, rect, rnd, light=light, **kw)


def spider():
    rnd = random.Random(1701)
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    paint_box(img, 0, 0, 6, 5, 7, rnd)                       # body
    f = box_faces(0, 0, 6, 5, 7)
    seam(img, f["top"], horizontal=False)
    seam(img, f["left"], at=0.6)
    seam(img, f["right"], at=0.6)
    paint_box(img, 0, 12, 4, 2, 4, rnd, base=BRONZE_LIGHT)   # dome
    dome = box_faces(0, 12, 4, 2, 4)
    glow(img, (dome["top"][0] + 1, dome["top"][1] + 1, 2, 2), SOUL)
    paint_box(img, 26, 0, 4, 3, 3, rnd)                       # head
    paint_box(img, 26, 6, 2, 1, 1, rnd, rivets=False)        # lens
    glow(img, box_faces(26, 6, 2, 1, 1)["front"], SOUL, core=False)
    head = box_faces(26, 0, 4, 3, 3)["front"]
    img.putpixel((head[0] + 1, head[1] + 1), SOUL + (255,))
    img.putpixel((head[0] + 2, head[1] + 1), SOUL + (255,))
    paint_box(img, 0, 20, 5, 1, 1, rnd, rivets=False, base=BRONZE_DARK)   # upper leg
    paint_box(img, 0, 24, 8, 1, 1, rnd, rivets=False, base=BRONZE)        # lower leg
    return img


def sphere():
    rnd = random.Random(1702)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    paint_box(img, 0, 0, 10, 10, 10, rnd)                     # rolled ball
    for name, rect in box_faces(0, 0, 10, 10, 10).items():
        seam(img, rect, horizontal=True, at=0.5)
        seam(img, rect, horizontal=False, at=0.5)
    front = box_faces(0, 0, 10, 10, 10)["front"]
    glow(img, (front[0] + 4, front[1] + 3, 2, 2), SOUL)
    paint_box(img, 0, 20, 9, 9, 9, rnd, base=BRONZE_DARK)     # wheel
    for name, rect in box_faces(0, 20, 9, 9, 9).items():
        if name in ("left", "right"):
            x0, y0, w, h = rect
            for i in range(1, w - 1):
                img.putpixel((x0 + i, y0 + h // 2), IRON + (255,))
                img.putpixel((x0 + w // 2, y0 + i), IRON + (255,))
    paint_box(img, 0, 38, 8, 9, 5, rnd)                       # torso
    t = box_faces(0, 38, 8, 9, 5)["front"]
    glow(img, (t[0] + 3, t[1] + 2, 2, 2), SOUL)
    seam(img, t, at=0.7)
    paint_box(img, 40, 0, 5, 5, 5, rnd, base=BRONZE_LIGHT)   # head
    hf = box_faces(40, 0, 5, 5, 5)["front"]
    for x in range(hf[0] + 1, hf[0] + 4):
        img.putpixel((x, hf[1] + 2), SOUL + (255,))
    paint_box(img, 40, 10, 3, 8, 3, rnd)                      # arm
    paint_box(img, 40, 22, 1, 10, 2, rnd, rivets=False, base=(200, 200, 196))   # blade (steel)
    paint_box(img, 46, 22, 1, 1, 6, rnd, rivets=False, base=BRONZE_DARK)        # crossbow stock
    paint_box(img, 26, 52, 7, 1, 2, rnd, rivets=False, base=BRONZE_DARK)        # crossbow limbs
    return img


def centurion():
    rnd = random.Random(1703)
    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    paint_box(img, 0, 0, 6, 12, 6, rnd)                       # leg
    for rect in box_faces(0, 0, 6, 12, 6).values():
        seam(img, rect, at=0.45)
    paint_box(img, 0, 18, 12, 4, 8, rnd, base=BRONZE_DARK)    # hips
    paint_box(img, 0, 30, 14, 12, 8, rnd)                     # chest
    chest = box_faces(0, 30, 14, 12, 8)
    seam(img, chest["front"], horizontal=False)
    seam(img, chest["back"], at=0.3)
    paint_box(img, 44, 34, 6, 6, 3, rnd, base=BRONZE_LIGHT)   # boiler
    bf = box_faces(44, 34, 6, 6, 3)["front"]
    grate(img, (bf[0] + 1, bf[1] + 1, bf[2] - 2, bf[3] - 2), FURNACE)
    paint_box(img, 104, 0, 2, 8, 2, rnd, rivets=False, base=IRON)   # exhaust stacks
    st = box_faces(104, 0, 2, 8, 2)["top"]
    glow(img, st, (40, 36, 34), core=False)
    paint_box(img, 48, 0, 8, 7, 8, rnd, base=BRONZE_LIGHT)    # helm
    hf = box_faces(48, 0, 8, 7, 8)["front"]
    for x in range(hf[0] + 1, hf[0] + 7):
        img.putpixel((x, hf[1] + 2), SOUL + (255,))
    grate(img, (hf[0] + 2, hf[1] + 4, 4, 2), FURNACE)
    paint_box(img, 80, 0, 6, 14, 6, rnd)                      # arm
    for rect in box_faces(80, 0, 6, 14, 6).values():
        seam(img, rect, at=0.55)
    paint_box(img, 0, 50, 8, 4, 8, rnd, base=BRONZE_LIGHT)    # pauldron
    paint_box(img, 48, 15, 6, 6, 10, rnd, base=BRONZE_DARK)   # hammer
    paint_box(img, 80, 20, 1, 10, 4, rnd, rivets=False, base=(200, 200, 196))   # blade
    return img


# --------------------------------------------------------------------------- Nordic puzzles

ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft")
SYMBOLS = ["bear", "wolf", "hawk", "snake", "whale", "dragon"]
# 10 x 10 glyph masks ('#' = carved)
GLYPHS = {
    "bear": [".#......#.", "###....###", "##########", "##.####.##", "##########",
             "####..####", ".########.", "..######..", "...####...", "....##...."],
    "wolf": [".......#..", "......##..", ".....###..", "....######", "...#######",
             "..########", ".#######..", "#######...", ".####.....", "..##......"],
    "hawk": ["....##....", "...####...", "#..####..#", "##.####.##", "##########",
             ".########.", "...####...", "...#..#...", "..##..##..", ".........."],
    "snake": ["...####...", "..##..##..", "..##......", "...###....", ".....###..",
              ".......##.", "..##...##.", "...#####..", "..........", ".........."],
    "whale": [".........#", "........##", "..#####.#.", ".########.", "##########",
              "#.########", "##########", ".########.", "...####...", ".........."],
    "dragon": ["#........#", "##..##..##", "###.##.###", "##########", ".########.",
               "...####...", "...####...", "..##..##..", ".##....##.", "##......##"],
}
CLAWS = {"golden": (232, 184, 48), "ivory": (240, 232, 208), "coral": (240, 120, 96), "ruby": (208, 40, 56),
         "sapphire": (48, 96, 224), "emerald": (48, 192, 96), "glass": (176, 240, 200), "iron": (140, 140, 144),
         "ebony": (60, 50, 70), "diamond": (184, 240, 248)}
GRANITE = (120, 116, 108)


def granite(seed, w=16, h=16):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            f = rnd.uniform(0.86, 1.08) * (0.92 if (x * 5 + y * 3) % 7 == 0 else 1.0)
            px[x, y] = shade(GRANITE, f) + (255,)
    for i in range(w):
        px[i, 0] = shade(px[i, 0], 1.12) + (255,)
        px[0, i] = shade(px[0, i], 1.08) + (255,)
        px[i, h - 1] = shade(px[i, h - 1], 0.72) + (255,)
        px[w - 1, i] = shade(px[w - 1, i], 0.78) + (255,)
    return img, rnd


def carve_glyph(img, glyph, ox=3, oy=3):
    """Cuts a glyph: dark grooves with a lit lower lip."""
    px = img.load()
    rows = GLYPHS[glyph]
    cut = set()
    for gy, row in enumerate(rows):
        for gx, ch in enumerate(row):
            if ch == "#":
                cut.add((ox + gx, oy + gy))
    for (x, y) in cut:
        px[x, y] = shade(px[x, y], 0.42) + (255,)
    for (x, y) in cut:
        if (x, y + 1) not in cut and y + 1 < 16:
            px[x, y + 1] = shade(px[x, y + 1], 1.22) + (255,)


def symbol_textures():
    tex = os.path.join(ASSETS, "textures", "block")
    os.makedirs(tex, exist_ok=True)
    side, _ = granite("puzzle-side")
    side.save(os.path.join(tex, "puzzle_stone_side.png"))
    for s in SYMBOLS:
        # the rotating ring: a carved disc border
        img, rnd = granite("pillar-" + s)
        px = img.load()
        for y in range(16):
            for x in range(16):
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
                if 6.6 < d < 7.6:
                    px[x, y] = shade(px[x, y], 0.6) + (255,)
                elif 5.8 < d <= 6.6:
                    px[x, y] = shade(px[x, y], 1.15) + (255,)
        carve_glyph(img, s)
        img.save(os.path.join(tex, f"symbol_pillar_{s}.png"))
        # the hint tablet: a square-framed slab
        img, rnd = granite("tablet-" + s)
        px = img.load()
        for i in range(1, 15):
            for (x, y) in ((i, 1), (i, 14), (1, i), (14, i)):
                px[x, y] = shade(px[x, y], 0.66) + (255,)
        carve_glyph(img, s)
        img.save(os.path.join(tex, f"symbol_tablet_{s}.png"))
    # the keyhole: a claw-shaped socket, three talon slots over a palm
    img, rnd = granite("keyhole")
    px = img.load()
    hole = [(7, 3), (8, 3), (4, 4), (11, 4), (4, 5), (7, 5), (8, 5), (11, 5), (5, 6), (7, 6), (8, 6), (10, 6),
            (5, 7), (6, 7), (7, 7), (8, 7), (9, 7), (10, 7)]
    for y in range(8, 12):
        for x in range(6, 10):
            hole.append((x, y))
    for (x, y) in hole:
        px[x, y] = (34, 30, 28, 255)
    for (x, y) in hole:
        if (x, y + 1) not in hole:
            px[x, y + 1] = shade(px[x, y + 1], 1.25) + (255,)
    for i in range(2, 14):
        px[i, 13] = (196, 160, 72, 255)
    img.save(os.path.join(tex, "claw_keyhole.png"))


def claw_icon(name, color):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    light = shade(color, 1.25)
    dark = shade(color, 0.6)
    # palm
    for y in range(8, 14):
        for x in range(5, 11):
            if (x, y) in ((5, 13), (10, 13)):
                continue
            px[x, y] = color + (255,)
    # three talons curling up and out
    talons = [[(4, 7), (3, 6), (3, 5), (3, 4), (4, 3)], [(7, 7), (7, 6), (7, 5), (7, 4), (7, 3), (8, 2)],
              [(10, 7), (11, 6), (12, 5), (12, 4), (11, 3)]]
    for t in talons:
        for i, (x, y) in enumerate(t):
            px[x, y] = (light if i == len(t) - 1 else color) + (255,)
            if x + 1 < 16 and px[x + 1, y][3] == 0:
                px[x + 1, y] = dark + (255,)
    for x in range(5, 11):
        px[x, 14] = dark + (255,)
    # the three glyph dots on the palm
    for x in (6, 8):
        px[x, 10] = dark + (255,)
    px[7, 12] = dark + (255,)
    for y in range(8, 14):
        px[5, y] = light + (255,)
    return img


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


ROT = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def puzzle_models():
    bs = os.path.join(ASSETS, "blockstates")
    models = os.path.join(ASSETS, "models")
    for block in ("symbol_pillar", "symbol_tablet"):
        variants = {}
        for i, s in enumerate(SYMBOLS):
            write_json(os.path.join(models, "block", f"{block}_{s}.json"), {
                "parent": "minecraft:block/orientable",
                "textures": {"front": f"nirncraft:block/{block}_{s}", "side": "nirncraft:block/puzzle_stone_side",
                             "top": "nirncraft:block/puzzle_stone_side"}})
            for facing, rot in ROT:
                v = {"model": f"nirncraft:block/{block}_{s}"}
                if rot:
                    v["y"] = rot
                variants[f"facing={facing},symbol={i}"] = v
        write_json(os.path.join(bs, f"{block}.json"), {"variants": variants})
    write_json(os.path.join(models, "block", "claw_keyhole.json"), {
        "parent": "minecraft:block/orientable",
        "textures": {"front": "nirncraft:block/claw_keyhole", "side": "nirncraft:block/puzzle_stone_side",
                     "top": "nirncraft:block/puzzle_stone_side"}})
    variants = {}
    for c in range(len(CLAWS)):
        for facing, rot in ROT:
            v = {"model": "nirncraft:block/claw_keyhole"}
            if rot:
                v["y"] = rot
            variants[f"claw={c},facing={facing}"] = v
    write_json(os.path.join(bs, "claw_keyhole.json"), {"variants": variants})
    # the puzzle lever looks like any lever (vanilla models, vanilla rotations)
    variants = {}
    face_rot = {"ceiling": (180, {"east": 270, "north": 180, "south": 0, "west": 90}),
                "floor": (0, {"east": 90, "north": 0, "south": 180, "west": 270}),
                "wall": (90, {"east": 90, "north": 0, "south": 180, "west": 270})}
    for face, (x, ys) in face_rot.items():
        for facing, y in ys.items():
            for powered in ("false", "true"):
                v = {"model": "minecraft:block/lever_on" if powered == "false" else "minecraft:block/lever"}
                if x:
                    v["x"] = x
                if y:
                    v["y"] = y
                variants[f"face={face},facing={facing},powered={powered}"] = v
    write_json(os.path.join(bs, "puzzle_lever.json"), {"variants": variants})
    tex_item = os.path.join(ASSETS, "textures", "item")
    os.makedirs(tex_item, exist_ok=True)
    for name, color in CLAWS.items():
        claw_icon(name, color).save(os.path.join(tex_item, f"{name}_claw.png"))
        write_json(os.path.join(models, "item", f"{name}_claw.json"), {
            "parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/{name}_claw"}})


# --------------------------------------------------------------------------- Solstheim's All-Maker stones

# Skaal runes on the standing-stone sigil grid (12 x 22): star points and the strokes joining them.
RUNES = {
    "beast": ([(3, 3), (6, 7), (9, 3), (6, 7), (6, 14), (3, 19), (9, 19)], [(0, 1), (2, 1), (1, 4), (4, 5), (4, 6)]),
    "earth": ([(6, 3), (1, 18), (11, 18), (4, 12), (8, 12)], [(0, 1), (0, 2), (1, 2), (3, 4)]),
    "sun": ([(6, 10), (6, 2), (6, 18), (1, 10), (11, 10), (2, 5), (10, 5), (2, 15), (10, 15)],
            [(0, 1), (0, 2), (0, 3), (0, 4), (0, 5), (0, 6), (0, 7), (0, 8)]),
    "tree": ([(6, 20), (6, 4), (2, 8), (10, 8), (3, 13), (9, 13), (6, 1)], [(0, 1), (1, 2), (1, 3), (1, 6), (0, 4), (0, 5)]),
    "water": ([(1, 6), (4, 4), (7, 6), (10, 4), (1, 13), (4, 11), (7, 13), (10, 11)], [(0, 1), (1, 2), (2, 3), (4, 5), (5, 6), (6, 7)]),
    "wind": ([(2, 3), (10, 7), (2, 11), (10, 15), (2, 19)], [(0, 1), (1, 2), (2, 3), (3, 4)]),
}
ALLMAKER_COLORS = {"beast": "#C89058", "earth": "#8A7A5A", "sun": "#F8D050", "tree": "#58B048", "water": "#4890E0",
                   "wind": "#D8E8F0"}


def allmaker_stones():
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import lore
    for name, (stars, lines) in RUNES.items():
        sign = "allmaker_" + name
        lore.SIGN_COLORS[sign] = ALLMAKER_COLORS[name]
        st, ln = {sign: stars}, {sign: lines}
        upper, lower = lore.split(lore.stone_face(sign, st, ln))
        gu, gl = lore.split(lore.stone_glow(sign, st, ln))
        lore.save(upper, "block", f"standing_stone_{sign}_upper.png")
        lore.save(lower, "block", f"standing_stone_{sign}_lower.png")
        lore.save(gu, "block", f"standing_stone_{sign}_upper_glow.png")
        lore.save(gl, "block", f"standing_stone_{sign}_lower_glow.png")
        lore.save(lore.stone_icon(sign, st, ln), "item", f"standing_stone_{sign}.png")
        lore.stone_models(sign)


def main():
    os.makedirs(OUT, exist_ok=True)
    spider().save(os.path.join(OUT, "dwarven_spider.png"))
    sphere().save(os.path.join(OUT, "dwarven_sphere.png"))
    centurion().save(os.path.join(OUT, "dwarven_centurion.png"))
    symbol_textures()
    puzzle_models()
    allmaker_stones()
    print("wrote", OUT, "and the puzzle, claw and All-Maker stone assets")


if __name__ == "__main__":
    main()
