#!/usr/bin/env python3
"""Generates the 256x256 (16x16 tiles) material atlas for Skycraft 3D regional shop signs."""

import math
import os
from PIL import Image

OUT_PATH = os.path.join(
    os.path.dirname(__file__),
    "..",
    "mod",
    "src",
    "main",
    "resources",
    "assets",
    "skycraft",
    "textures",
    "entity",
    "settlement",
    "shop_signs.png",
)


def clamp(v):
    return max(0, min(255, int(round(v))))


def mix(c1, c2, t):
    return (
        clamp(c1[0] + (c2[0] - c1[0]) * t),
        clamp(c1[1] + (c2[1] - c1[1]) * t),
        clamp(c1[2] + (c2[2] - c1[2]) * t),
        255,
    )


def scale_rgb(c, f):
    return (clamp(c[0] * f), clamp(c[1] * f), clamp(c[2] * f), 255)


def hash2(x, y, seed):
    n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def paint_tile(img, tile_id, base_rgb, mode="wood", accent_rgb=None):
    tx = (tile_id % 16) * 16
    ty = (tile_id // 16) * 16
    if accent_rgb is None:
        accent_rgb = scale_rgb(base_rgb, 1.22)

    for y in range(16):
        for x in range(16):
            n = hash2(x, y, tile_id * 31 + 7)
            # Subtle 1px 3D bevel around each 16x16 tile so every 3D box part has crisp edges
            bevel = 1.0
            if x == 0 or y == 0:
                bevel = 1.14 if (x == 0 and y == 0) else 1.10
            elif x == 15 or y == 15:
                bevel = 0.78 if (x == 15 and y == 15) else 0.84
            elif x == 1 or y == 1:
                bevel = 1.04
            elif x == 14 or y == 14:
                bevel = 0.93

            col = base_rgb
            if mode == "wood":
                grain = math.sin(y * 1.1 + math.sin(x * 0.45 + tile_id) * 0.8) * 0.08
                streak = (hash2(x // 4, y, tile_id + 3) - 0.5) * 0.10
                plank_line = -0.10 if (y in (0, 8, 15)) else 0.0
                f = bevel + grain + streak + plank_line + (n - 0.5) * 0.05
                col = scale_rgb(base_rgb, f)
            elif mode == "metal":
                spec = math.exp(-(((x - 5.5) + (y - 5.5)) ** 2) / 38.0) * 0.26
                hammer = (hash2(x // 2, y // 2, tile_id + 11) - 0.5) * 0.10
                rivet = 0.16 if ((x in (2, 13)) and (y in (2, 13))) else 0.0
                f = bevel + spec + hammer + rivet + (n - 0.5) * 0.04
                col = scale_rgb(base_rgb, f)
            elif mode == "gold":
                spec = math.exp(-(((x - 5.0) + (y - 4.5)) ** 2) / 30.0) * 0.34
                shimmer = math.sin((x + y) * 0.65) * 0.08
                f = bevel + spec + shimmer + (n - 0.5) * 0.05
                col = scale_rgb(base_rgb, f)
            elif mode == "stone":
                vein = abs(math.sin((x * 0.42 + y * 0.31) + n * 1.6))
                c = mix(base_rgb, accent_rgb, 0.35 if vein > 0.82 else (n * 0.18))
                col = scale_rgb(c, bevel + (n - 0.5) * 0.08)
            elif mode == "inlay":
                border = x in (0, 1, 14, 15) or y in (0, 1, 14, 15)
                diag = abs(x - 7.5) + abs(y - 7.5)
                if border or (5.5 <= diag <= 7.0):
                    col = scale_rgb(accent_rgb, bevel + 0.08)
                else:
                    col = scale_rgb(base_rgb, bevel + (n - 0.5) * 0.08)
            elif mode == "gem":
                dx = abs(x - 7.5)
                dy = abs(y - 7.5)
                d = max(dx, dy)
                facet = 1.30 if (x + y < 13 and d < 5) else (0.82 if (x + y > 17) else 1.05)
                spark = 1.45 if (4 <= x <= 6 and 4 <= y <= 6) else 1.0
                col = scale_rgb(base_rgb, bevel * facet * spark)
            elif mode == "potion":
                # Glowing liquid with bright core and meniscus highlight
                dx = (x - 7.5) / 7.5
                dy = (y - 7.5) / 7.5
                r2 = dx * dx + dy * dy
                glow = max(0.0, 1.0 - r2 * 0.65)
                c = mix(base_rgb, accent_rgb, glow * 0.7)
                if (x, y) in ((5, 4), (6, 4), (5, 5), (10, 9), (9, 11)):
                    c = mix(c, (255, 255, 255), 0.55)
                col = scale_rgb(c, bevel)
            elif mode == "foam":
                bubble = math.sin(x * 1.2) * math.cos(y * 1.2) * 0.07
                col = scale_rgb(base_rgb, bevel + bubble + (n - 0.5) * 0.06)
            elif mode == "cloth":
                weave = (0.05 if ((x + y) % 2 == 0) else -0.05) + math.sin(y * 0.9) * 0.04
                col = scale_rgb(base_rgb, bevel + weave + (n - 0.5) * 0.04)
            elif mode == "bread":
                slash = 1.18 if (abs((x - y) - 1) <= 1 and 4 <= x <= 11) else 1.0
                col = scale_rgb(base_rgb, bevel * slash + (n - 0.5) * 0.07)
            else:
                col = scale_rgb(base_rgb, bevel + (n - 0.5) * 0.06)

            img.putpixel((tx + x, ty + y), col)


def main():
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    img = Image.new("RGBA", (256, 256), (60, 45, 32, 255))

    tiles = [
        # 0..7: Regional Woods
        (0,  (68, 46, 30),   "wood",   None),                  # MAT_SPRUCE_DARK
        (1,  (108, 76, 48),  "wood",   None),                  # MAT_SPRUCE_MID
        (2,  (142, 102, 60), "wood",   None),                  # MAT_OAK_RICH
        (3,  (214, 194, 152),"wood",   None),                  # MAT_BIRCH_CREAM
        (4,  (54, 36, 24),   "wood",   None),                  # MAT_WALNUT
        (5,  (156, 86, 46),  "wood",   None),                  # MAT_ACACIA
        (6,  (112, 46, 38),  "wood",   None),                  # MAT_MANGROVE
        (7,  (42, 38, 36),   "wood",   None),                  # MAT_CHARRED

        # 8..15: Metals & Stone
        (8,  (58, 62, 68),   "metal",  None),                  # MAT_IRON_DARK
        (9,  (108, 114, 124),"metal",  None),                  # MAT_IRON_LIGHT
        (10, (192, 202, 214),"metal",  None),                  # MAT_STEEL_BRIGHT
        (11, (242, 198, 62), "gold",   None),                  # MAT_GOLD
        (12, (194, 152, 66), "gold",   None),                  # MAT_BRASS
        (13, (196, 108, 62), "metal",  None),                  # MAT_COPPER
        (14, (158, 112, 64), "metal",  None),                  # MAT_BRONZE
        (15, (148, 154, 164),"stone",  (182, 188, 198)),       # MAT_SILVER_MIST

        # 16..23: Guild, Gem, Potion & Bone Materials
        (16, (176, 36, 38),  "cloth",  None),                  # MAT_CRIMSON
        (17, (38, 74, 156),  "inlay",  (220, 182, 64)),        # MAT_COBALT
        (18, (38, 184, 96),  "potion", (130, 255, 190)),       # MAT_EMERALD
        (19, (242, 168, 44), "gold",   None),                  # MAT_AMBER_GLOW
        (20, (68, 178, 238), "gem",    None),                  # MAT_ARCANE_BLUE
        (21, (164, 58, 214), "gem",    None),                  # MAT_PURPLE_GEM
        (22, (238, 226, 194),"cloth",  None),                  # MAT_PARCHMENT
        (23, (232, 224, 202),"stone",  (200, 190, 166)),       # MAT_BONEMEAL

        # 24..31: Cultural & Trade Materials
        (24, (148, 108, 62), "stone",  (188, 142, 86)),        # MAT_CHITIN
        (25, (216, 192, 142),"stone",  (236, 214, 168)),       # MAT_SANDSTONE
        (26, (72, 92, 104),  "stone",  (98, 122, 136)),        # MAT_SLATE_BLUE
        (27, (242, 240, 234),"stone",  (255, 255, 250)),       # MAT_PEARL_WHITE
        (28, (134, 82, 46),  "cloth",  None),                  # MAT_LEATHER_WARM
        (29, (198, 134, 54), "bread",  None),                  # MAT_BREAD_CRUST
        (30, (248, 244, 230),"foam",   None),                  # MAT_FOAM_CREAM
        (31, (58, 136, 148), "metal",  None),                  # MAT_TEAL_FISH

        # 32..39: Special Accents
        (32, (48, 54, 52),   "metal",  None),                  # MAT_ORC_IRON
        (33, (124, 242, 112),"potion", (210, 255, 195)),       # MAT_LIME_POTION
        (34, (248, 108, 36), "potion", (255, 220, 90)),        # MAT_ORANGE_FIRE
        (35, (176, 146, 96), "cloth",  None),                  # MAT_ROPE_TAN
        (36, (36, 34, 44),   "metal",  None),                  # MAT_EBONY_STEEL
        (37, (84, 226, 248), "gem",    None),                  # MAT_CYAN_GEM
        (38, (94, 72, 58),   "wood",   None),                  # MAT_MUSHROOM
        (39, (168, 128, 42), "gold",   None),                  # MAT_GOLD_DARK
    ]

    for tid, rgb, mode, acc in tiles:
        paint_tile(img, tid, rgb, mode, acc)

    img.save(OUT_PATH)
    print(f"Saved {OUT_PATH} ({img.size[0]}x{img.size[1]})")


if __name__ == "__main__":
    main()
