#!/usr/bin/env python3
"""World module textures: aged parchment for the world map and the hand-inked location icon sheet.

Writes into mod/src/main/resources/assets/skycraft/textures/gui/:
  map_parchment.png  256x256  tileable aged paper (stains, fibres, darker edges are drawn by the screen)
  map_icons.png      128x64   8x4 grid of 16x16 ink icons, index = LocationKind.icon
Run: python3 tools/textures/world.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures", "gui")

INK = (58, 40, 24, 255)
INK_SOFT = (92, 66, 40, 255)
FILL = (168, 132, 88, 255)
RED = (140, 30, 20, 255)
RED_FILL = (196, 72, 40, 255)
GOLD = (176, 132, 40, 255)
BLUE = (52, 74, 96, 255)


def tileable_noise(size, cell, rng):
    """Value noise that wraps around at the borders so the texture tiles."""
    n = size // cell
    grid = [[rng.random() for _ in range(n)] for _ in range(n)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        gy = y / cell
        y0 = int(gy) % n
        y1 = (y0 + 1) % n
        fy = gy - int(gy)
        fy = fy * fy * (3 - 2 * fy)
        for x in range(size):
            gx = x / cell
            x0 = int(gx) % n
            x1 = (x0 + 1) % n
            fx = gx - int(gx)
            fx = fx * fx * (3 - 2 * fx)
            a = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx
            b = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx
            out[y][x] = a * (1 - fy) + b * fy
    return out


def parchment():
    size = 256
    rng = random.Random(4201)
    octaves = [(64, 0.45), (32, 0.25), (16, 0.15), (8, 0.1), (4, 0.05)]
    layers = [(tileable_noise(size, c, rng), w) for c, w in octaves]
    stains = tileable_noise(size, 128, rng)
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            v = sum(layer[y][x] * w for layer, w in layers)
            s = stains[y][x]
            stain = max(0.0, s - 0.62) * 2.2
            fibre = 0.012 * math.sin(x * 2 * math.pi / 32 + v * 14) * math.sin(y * 2 * math.pi / 64)
            base = 0.80 + (v - 0.5) * 0.22 + fibre - stain * 0.35
            r = int(min(255, max(0, 232 * base)))
            g = int(min(255, max(0, 206 * base - stain * 18)))
            b = int(min(255, max(0, 160 * base - stain * 30)))
            px[x, y] = (r, g, b, 255)
    # speckles of age
    d = ImageDraw.Draw(img)
    for _ in range(260):
        x, y = rng.randrange(size), rng.randrange(size)
        c = rng.randint(120, 170)
        d.point((x, y), fill=(c, int(c * 0.82), int(c * 0.6), 255))
    return img.filter(ImageFilter.SMOOTH)


# ---------------------------------------------------------------- icons
def icon_canvas():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def town(d):
    d.polygon([(1, 9), (5, 5), (9, 9)], fill=FILL, outline=INK)
    d.rectangle([2, 9, 8, 14], fill=FILL, outline=INK)
    d.polygon([(7, 7), (11, 3), (15, 7)], fill=FILL, outline=INK)
    d.rectangle([8, 7, 14, 14], fill=FILL, outline=INK)
    d.rectangle([4, 11, 5, 14], fill=INK)
    d.rectangle([10, 10, 11, 11], fill=INK)


def ruin(d):
    d.rectangle([1, 13, 14, 14], fill=INK)
    d.rectangle([2, 4, 4, 12], fill=FILL, outline=INK)
    d.rectangle([7, 7, 9, 12], fill=FILL, outline=INK)
    d.rectangle([12, 5, 14, 12], fill=FILL, outline=INK)
    d.line([(1, 3), (6, 4)], fill=INK)
    d.point((7, 6), fill=INK)


def cave(d):
    d.polygon([(0, 14), (3, 6), (8, 2), (13, 6), (15, 14)], fill=FILL, outline=INK)
    d.pieslice([4, 6, 11, 18], 180, 360, fill=INK)
    d.rectangle([4, 12, 11, 14], fill=INK)


def mine(d):
    d.line([(2, 14), (10, 6)], fill=(120, 84, 48, 255), width=2)
    d.line([(4, 3), (8, 3), (11, 5), (13, 8), (13, 12)], fill=INK, width=2)
    d.rectangle([0, 15, 15, 15], fill=INK_SOFT)


def fort(d):
    d.rectangle([2, 6, 13, 14], fill=FILL, outline=INK)
    for x in (2, 5, 8, 11):
        d.rectangle([x, 3, x + 2, 6], fill=FILL, outline=INK)
    d.rectangle([6, 10, 9, 14], fill=INK)


def tower(d):
    d.rectangle([5, 4, 10, 14], fill=FILL, outline=INK)
    d.rectangle([4, 1, 11, 4], fill=FILL, outline=INK)
    d.rectangle([4, 1, 5, 2], fill=INK)
    d.rectangle([10, 1, 11, 2], fill=INK)
    d.rectangle([7, 7, 8, 9], fill=INK)
    d.rectangle([3, 14, 12, 15], fill=INK)


def camp(d):
    d.polygon([(1, 14), (8, 2), (15, 14)], fill=FILL, outline=INK)
    d.polygon([(6, 14), (8, 8), (10, 14)], fill=INK)
    d.line([(8, 2), (8, 0)], fill=INK)


def barrow(d):
    d.chord([0, 4, 15, 22], 180, 360, fill=FILL, outline=INK)
    d.rectangle([5, 8, 10, 14], fill=INK)
    d.line([(4, 7), (11, 7)], fill=INK)
    d.rectangle([0, 14, 15, 15], fill=INK)


def dwemer(d):
    d.ellipse([3, 3, 12, 12], fill=GOLD, outline=INK)
    for a in range(0, 360, 45):
        x = 7.5 + 7 * math.cos(math.radians(a))
        y = 7.5 + 7 * math.sin(math.radians(a))
        d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=INK)
    d.ellipse([6, 6, 9, 9], fill=INK)


def shrine(d):
    d.polygon([(2, 6), (8, 2), (14, 6)], fill=FILL, outline=INK)
    d.rectangle([3, 6, 4, 11], fill=INK)
    d.rectangle([11, 6, 12, 11], fill=INK)
    d.rectangle([7, 6, 8, 11], fill=INK)
    for x in range(0, 16, 4):
        d.arc([x, 11, x + 4, 15], 180, 360, fill=BLUE)


def wreck(d):
    d.polygon([(1, 9), (15, 9), (12, 13), (4, 13)], fill=FILL, outline=INK)
    d.line([(8, 9), (6, 1)], fill=INK)
    d.polygon([(6, 2), (11, 6), (7, 7)], fill=(220, 200, 160, 255), outline=INK)
    d.line([(0, 14), (15, 14)], fill=BLUE)


def oblivion_gate(d):
    d.polygon([(2, 15), (3, 5), (8, 0), (13, 5), (14, 15), (11, 15), (11, 7), (8, 4), (5, 7), (5, 15)], fill=RED, outline=INK)
    d.rectangle([6, 8, 10, 15], fill=RED_FILL)


def citadel(d):
    d.rectangle([3, 5, 12, 14], fill=RED_FILL, outline=INK)
    d.polygon([(1, 15), (3, 1), (5, 15)], fill=RED, outline=INK)
    d.polygon([(10, 15), (12, 1), (14, 15)], fill=RED, outline=INK)
    d.rectangle([6, 9, 9, 14], fill=INK)


def hut(d):
    d.polygon([(1, 8), (8, 2), (15, 8)], fill=INK_SOFT, outline=INK)
    d.rectangle([3, 8, 12, 14], fill=FILL, outline=INK)
    d.rectangle([6, 10, 8, 14], fill=INK)


def manor(d):
    d.polygon([(0, 7), (8, 1), (15, 7)], fill=INK_SOFT, outline=INK)
    d.rectangle([1, 7, 14, 14], fill=FILL, outline=INK)
    for x in (3, 11):
        d.rectangle([x, 9, x + 1, 10], fill=INK)
    d.rectangle([7, 10, 8, 14], fill=INK)


def temple(d):
    d.polygon([(1, 5), (8, 1), (14, 5)], fill=FILL, outline=INK)
    d.rectangle([1, 5, 14, 6], fill=INK)
    for x in (2, 6, 9, 12):
        d.rectangle([x, 7, x + 1, 13], fill=FILL, outline=INK)
    d.rectangle([0, 13, 15, 14], fill=INK)


def inn(d):
    d.rectangle([3, 4, 11, 14], fill=FILL, outline=INK)
    d.arc([9, 6, 15, 12], 270, 90, fill=INK, width=2)
    d.rectangle([3, 2, 11, 4], fill=(240, 230, 210, 255), outline=INK)
    d.line([(5, 7), (5, 12)], fill=INK_SOFT)
    d.line([(8, 7), (8, 12)], fill=INK_SOFT)


def landmark(d):
    d.polygon([(5, 15), (6, 2), (10, 1), (11, 15)], fill=(150, 150, 140, 255), outline=INK)
    d.line([(7, 5), (9, 8)], fill=INK)
    d.line([(9, 5), (7, 8)], fill=INK)
    d.rectangle([2, 14, 14, 15], fill=INK_SOFT)


def hall_of_valor(d):
    d.polygon([(8, 0), (10, 6), (16, 6), (11, 10), (13, 16), (8, 12), (3, 16), (5, 10), (0, 6), (6, 6)], fill=GOLD, outline=INK)


def tomb(d):
    d.polygon([(0, 14), (8, 2), (15, 14)], fill=(200, 170, 110, 255), outline=INK)
    d.line([(4, 8), (12, 8)], fill=INK_SOFT)
    d.line([(2, 11), (13, 11)], fill=INK_SOFT)
    d.rectangle([7, 11, 8, 14], fill=INK)


def sanctum(d):
    d.chord([1, 3, 14, 20], 180, 360, fill=INK_SOFT, outline=INK)
    d.rectangle([1, 11, 14, 14], fill=INK_SOFT, outline=INK)
    d.polygon([(5, 14), (5, 8), (8, 5), (11, 8), (11, 14)], fill=INK)
    d.ellipse([7, 1, 9, 3], fill=GOLD)


def bandit_camp(d):
    camp(d)
    d.line([(1, 1), (5, 5)], fill=RED, width=2)
    d.line([(5, 1), (1, 5)], fill=RED, width=2)


def daedric_tower(d):
    d.polygon([(4, 15), (6, 3), (8, 0), (10, 3), (12, 15)], fill=RED, outline=INK)
    d.rectangle([7, 6, 8, 8], fill=(255, 190, 60, 255))
    d.rectangle([2, 14, 14, 15], fill=INK)


def quest(d):
    d.polygon([(2, 2), (13, 2), (8, 13)], fill=GOLD, outline=INK)


def unknown(d):
    d.ellipse([4, 4, 11, 11], fill=FILL, outline=INK)


def capital(d):
    # walled hold capital: keep with a banner behind a crenellated wall
    d.rectangle([5, 3, 10, 9], fill=FILL, outline=INK)
    d.line([(8, 3), (8, 0)], fill=INK)
    d.polygon([(8, 0), (12, 1), (8, 2)], fill=RED_FILL)
    d.rectangle([0, 9, 15, 14], fill=FILL, outline=INK)
    for x in (0, 4, 8, 12):
        d.rectangle([x, 7, x + 2, 9], fill=FILL, outline=INK)
    d.rectangle([6, 11, 9, 14], fill=INK)
    d.rectangle([0, 15, 15, 15], fill=INK_SOFT)


def city(d):
    d.rectangle([1, 7, 14, 14], fill=FILL, outline=INK)
    for x in (1, 5, 9, 13):
        d.rectangle([x, 5, x + 1, 7], fill=FILL, outline=INK)
    d.polygon([(4, 7), (7, 2), (10, 7)], fill=INK_SOFT, outline=INK)
    d.rectangle([6, 10, 9, 14], fill=INK)


def standing_stone(d):
    d.polygon([(5, 15), (4, 4), (8, 0), (12, 4), (11, 15)], fill=(150, 156, 160, 255), outline=INK)
    d.ellipse([6, 4, 10, 8], outline=BLUE)
    d.line([(8, 9), (8, 12)], fill=BLUE)
    d.rectangle([1, 15, 15, 15], fill=INK_SOFT)


def word_wall(d):
    d.chord([0, 2, 15, 26], 180, 360, fill=(170, 160, 140, 255), outline=INK)
    d.rectangle([0, 14, 15, 15], fill=INK)
    for y in (6, 9, 12):
        for x in range(3, 13, 3):
            d.line([(x, y), (x + 1, y - 1)], fill=INK)


def dragon_mound(d):
    d.chord([0, 6, 15, 22], 180, 360, fill=INK_SOFT, outline=INK)
    d.polygon([(4, 9), (8, 3), (12, 9)], fill=RED, outline=INK)
    d.line([(6, 11), (10, 11)], fill=INK)
    d.rectangle([0, 14, 15, 15], fill=INK)


def daedric_shrine(d):
    d.polygon([(7, 0), (9, 0), (12, 13), (4, 13)], fill=RED, outline=INK)
    d.ellipse([6, 3, 10, 7], fill=(255, 190, 60, 255), outline=INK)
    d.rectangle([2, 13, 14, 15], fill=INK)


def custom(d):
    d.polygon([(8, 15), (2, 6), (4, 2), (8, 1), (12, 2), (14, 6)], fill=(56, 176, 222, 255), outline=INK)
    d.ellipse([6, 4, 10, 8], fill=(240, 250, 255, 255))


# order must match LocationKind.icon
ICONS = [town, ruin, cave, mine, fort, tower, camp, barrow,
         dwemer, shrine, wreck, oblivion_gate, citadel, hut, manor, temple,
         inn, landmark, hall_of_valor, tomb, sanctum, bandit_camp, daedric_tower, quest,
         unknown, capital, city, standing_stone, word_wall, dragon_mound, daedric_shrine, custom]


def icon_sheet():
    sheet = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    for i, fn in enumerate(ICONS):
        im = icon_canvas()
        fn(ImageDraw.Draw(im))
        sheet.paste(im, ((i % 8) * 16, (i // 8) * 16), im)
    return sheet


def main():
    os.makedirs(OUT, exist_ok=True)
    parchment().save(os.path.join(OUT, "map_parchment.png"))
    icon_sheet().save(os.path.join(OUT, "map_icons.png"))
    print("wrote", OUT)


if __name__ == "__main__":
    main()
