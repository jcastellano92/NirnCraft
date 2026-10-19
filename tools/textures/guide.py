"""Generates the guide module's pixel art and models.

Writes into mod/src/main/resources:
  assets/nirncraft/textures/item/travelers_handbook.png   a worn leather field guide with a compass rose and a strap
  assets/nirncraft/models/item/travelers_handbook.json

The reading screen reuses the lore module's book_spread.png. Run from anywhere: python3 tools/textures/guide.py
"""
import json
import os

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
TEX = os.path.join(RES, "assets", "nirncraft", "textures")
MODELS = os.path.join(RES, "assets", "nirncraft", "models")


def rgba(hex_color, a=255):
    h = hex_color.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


def handbook_icon():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    leather = rgba("#6B4426")
    pages = rgba("#EDE3C8")
    brass = rgba("#D8B460")
    strap = rgba("#3E2614")
    # cover (slightly worn: a few lighter scuffs), spine on the left, page block below and to the right
    for y in range(1, 14):
        for x in range(2, 13):
            px[x, y] = shade(leather, 1.08 if (x * 7 + y * 3) % 11 == 0 else 1.0)
    for y in range(1, 14):
        px[2, y] = shade(leather, 0.62)
        px[3, y] = shade(leather, 0.8)
        px[12, y] = shade(leather, 1.18)
    for x in range(2, 13):
        px[x, 1] = shade(leather, 1.22)
    for x in range(3, 14):
        px[x, 14] = pages
        px[x, 15] = shade(pages, 0.78)
    for y in range(2, 15):
        px[13, y] = shade(pages, 0.88 if y % 2 else 0.95)
    # stitched spine bands
    for y in (3, 7, 11):
        px[2, y] = brass
        px[3, y] = shade(brass, 0.8)
    # compass rose emblem in brass
    cx, cy = 8, 7
    for d in range(1, 4):
        px[cx, cy - d] = brass if d < 3 else shade(brass, 1.15)
        px[cx, cy + d] = shade(brass, 0.85)
        px[cx - d, cy] = shade(brass, 0.9)
        px[cx + d, cy] = shade(brass, 0.9)
    for (dx, dy) in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        px[cx + dx, cy + dy] = shade(brass, 0.7)
    px[cx, cy] = rgba("#8A2A22")
    # a leather strap with a buckle across the right edge
    for y in range(1, 14):
        px[11, y] = strap
    px[11, 9] = brass
    px[11, 10] = shade(brass, 0.8)
    px[12, 9] = shade(brass, 0.9)
    return img


def main():
    out = os.path.join(TEX, "item", "travelers_handbook.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    handbook_icon().save(out)
    model = os.path.join(MODELS, "item", "travelers_handbook.json")
    with open(model, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "nirncraft:item/travelers_handbook"}}, f, indent=2)
        f.write("\n")
    print("wrote", out)
    print("wrote", model)


if __name__ == "__main__":
    main()
