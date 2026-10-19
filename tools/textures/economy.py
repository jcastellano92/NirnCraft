"""Generates the economy module's item textures and models (16x16 pixel art).

Skill books: a leather-bound tome in the colour of the skill's constellation group (warrior red, mage blue,
thief green, life gold) with a small gilded emblem of the skill on the cover.

Run from the repository root:  python3 tools/textures/economy.py
"""
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures", "item")
MODELS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "models", "item")

GROUPS = {
    "warrior": (0xC8, 0x46, 0x3C),
    "mage": (0x4A, 0x7B, 0xD8),
    "thief": (0x4C, 0xB0, 0x50),
    "life": (0xD8, 0xB0, 0x4A),
}

# skill -> (group, 5x5 emblem; '#' = gilded pixel)
SKILLS = {
    "one_handed": ("warrior", ["....#", "...#.", "#.#..", ".#...", "#.#.."]),
    "two_handed": ("warrior", ["##.##", "#####", "##.##", "..#..", "..#.."]),
    "archery": ("warrior", ["#...#", ".#.#.", "..#..", ".#.#.", "#...#"]),
    "block": ("warrior", ["#####", "#.#.#", "#####", ".###.", "..#.."]),
    "heavy_armor": ("warrior", [".###.", "#####", "#.#.#", "#####", "#...#"]),
    "smithing": ("warrior", ["###..", "####.", "###..", "..#..", "..#.."]),
    "destruction": ("mage", ["..#..", ".##..", ".###.", "#####", ".###."]),
    "restoration": ("mage", ["..#..", "..#..", "#####", "..#..", "..#.."]),
    "alteration": ("mage", [".###.", "#...#", "#.#.#", "#...#", ".###."]),
    "conjuration": ("mage", [".###.", "#.#.#", "#####", ".#.#.", ".###."]),
    "illusion": ("mage", [".....", ".###.", "#.#.#", ".###.", "....."]),
    "enchanting": ("mage", ["..#..", ".###.", "#####", ".###.", "..#.."]),
    "light_armor": ("thief", ["#...#", "#####", ".###.", ".###.", ".#.#."]),
    "sneak": ("thief", [".....", "#####", "#.#.#", ".....", "....."]),
    "lockpicking": ("thief", [".##..", "#..#.", ".##..", "..###", "..#.#"]),
    "pickpocket": ("thief", ["..#..", ".#.#.", "#####", "#####", ".###."]),
    "speech": ("thief", ["####.", "#..#.", "####.", "#....", "....."]),
    "alchemy": ("thief", [".#.#.", ".#.#.", "#...#", "#####", ".###."]),
    "mining": ("life", ["####.", "#..#.", "..#..", ".#...", "#...."]),
    "woodcutting": ("life", ["..##.", "..###", "..#..", ".#...", "#...."]),
    "fishing": ("life", [".....", "#.##.", "#####", "#.##.", "....."]),
    "hunting": ("life", ["#...#", "#.#.#", ".###.", ".###.", "..#.."]),
    "unarmed": ("warrior", [".###.", "#####", "#####", ".###.", "..#.."]),
    "athletics": ("warrior", ["#....", "#.##.", "####.", "..#..", ".###."]),
    # skills added by the magic & skills workstream (keep appended: the index tints the cover)
    "polearm": ("warrior", ["....#", "...##", "..#..", ".#...", "#...."]),
    "mysticism": ("mage", ["#...#", ".#.#.", "..#..", ".#.#.", "#...#"]),
    "acrobatics": ("thief", ["..#..", ".#.#.", "#...#", ".....", "#####"]),
    "riding": ("life", ["..##.", ".####", "####.", "#..#.", "#..#."]),
    "cooking": ("life", [".#.#.", "#####", "#...#", "#...#", ".###."]),
}

GOLD = (0xF0, 0xD0, 0x70, 255)
GOLD_DARK = (0xA8, 0x80, 0x30, 255)
PAGE = (0xF2, 0xEC, 0xD8, 255)
PAGE_SHADE = (0xC8, 0xBC, 0x9A, 255)
OUTLINE = (0x24, 0x18, 0x10, 255)
SILVER = (0xF6, 0xF2, 0xEA, 255)  # emblem on gold (life) covers, where gilding would not show


def shade(rgb, f):
    return tuple(max(0, min(255, int(c * f))) for c in rgb) + (255,)


def book(group_rgb, emblem, variant, emblem_color=GOLD):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # tilt the hue a little per skill so books of one group are distinguishable
    base = tuple(max(0, min(255, c + (variant % 3 - 1) * 10)) for c in group_rgb)
    cover = shade(base, 0.85)
    cover_hi = shade(base, 1.05)
    cover_lo = shade(base, 0.6)
    spine = shade(base, 0.45)
    x0, x1, y0, y1 = 2, 13, 1, 14
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if x in (x0, x1) or y in (y0, y1):
                px[x, y] = OUTLINE
            elif x <= x0 + 2:
                px[x, y] = spine
            elif x == x1 - 1:
                px[x, y] = PAGE if y % 2 else PAGE_SHADE
            elif y == y1 - 1:
                px[x, y] = PAGE_SHADE if x < x1 - 1 else PAGE
            else:
                # soft vertical gradient for a leather look
                px[x, y] = cover_hi if y < 5 else cover if y < 11 else cover_lo
    # spine bands
    for y in (3, 12):
        for x in range(x0 + 1, x0 + 3):
            px[x, y] = GOLD_DARK
    # gilded frame on the cover
    for x in range(5, 12):
        px[x, 2] = GOLD_DARK
        px[x, 12] = GOLD_DARK if px[x, 12] != PAGE_SHADE else px[x, 12]
    # emblem (5x5) centred on the cover
    ex, ey = 6, 5
    lit = {(ex + i, ey + j) for j, row in enumerate(emblem) for i, ch in enumerate(row) if ch == "#"}
    for (x, y) in lit:
        shadow = (x + 1, y + 1)
        if shadow not in lit and shadow[0] <= 11 and shadow[1] <= 11:
            px[shadow] = cover_lo
    for (x, y) in lit:
        px[x, y] = emblem_color
    return img


def model(name):
    return {"parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/{name}"}}


def main():
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(MODELS, exist_ok=True)
    for i, (skill, (group, emblem)) in enumerate(SKILLS.items()):
        name = f"skill_book_{skill}"
        book(GROUPS[group], emblem, i, SILVER if group == "life" else GOLD).save(os.path.join(TEX, name + ".png"))
        with open(os.path.join(MODELS, name + ".json"), "w") as f:
            json.dump(model(name), f, indent=2)
            f.write("\n")
    print(f"wrote {len(SKILLS)} skill book textures and models")


if __name__ == "__main__":
    main()
