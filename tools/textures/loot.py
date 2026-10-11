"""Generates the loot module's item textures and models (16x16 pixel art, original):

* unusual_gem: a faceted violet-and-rose stone with a bright core (a Stone of Barenziah);
* crown_of_barenziah: a gold crown set with those stones;
* black_book: a black, green-edged tome with a tentacled sigil;
* elder_scroll: a rolled scroll on dark wooden rods with gold caps;
* regional_treasure: a carved relic in light grey (layer 0, tinted per treasure by the item colour handler) on a gold
  mount (layer 1, untinted).

Run from the repository root:  py -3 tools/textures/loot.py
"""
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures", "item")
MODELS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "models", "item")

T = (0, 0, 0, 0)
OUT = (0x1E, 0x14, 0x10, 255)


def rgba(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


def paint(rows, palette):
    img = Image.new("RGBA", (16, 16), T)
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and ch in palette:
                px[x, y] = palette[ch]
    return img


GEM = [
    "................",
    "................",
    "......oooo......",
    ".....oLlllo.....",
    "....oLlhhllo....",
    "...oLlhWhhlmo...",
    "...olhhWhhlmo...",
    "..oDlhhhhlmmdo..",
    "..oDDlhhlmmddo..",
    "...oDDllmmddo...",
    "....oDDmmddo....",
    ".....oDdddo.....",
    "......oddo......",
    ".......oo.......",
    "................",
    "................",
]
GEM_PAL = {"o": OUT, "L": rgba(0xF0C8FF), "l": rgba(0xC890E8), "h": rgba(0xA060D0), "W": rgba(0xFFFFFF),
           "m": rgba(0x8048B8), "D": rgba(0xD070A0), "d": rgba(0x602C80)}

CROWN = [
    "................",
    "................",
    "..o....o....o...",
    ".oyo..oro..oyo..",
    ".oYo..oYo..oYo..",
    ".oYYo.oYo.oYYo..",
    ".oYYYoYYYoYYYo..",
    ".oYYYYYYYYYYYo..",
    ".oyYrYYvYYrYyo..",
    ".oyyYYYYYYYYyo..",
    ".ogggggggggggo..",
    "..ooooooooooo...",
    "................",
    "................",
    "................",
    "................",
]
CROWN_PAL = {"o": OUT, "Y": rgba(0xF2D060), "y": rgba(0xB89030), "g": rgba(0x8A6420), "r": rgba(0xD03850),
             "v": rgba(0xA060D0)}

BLACK_BOOK = [
    "................",
    "..oooooooooooo..",
    "..obbbbbbbbbbgo.",
    "..obkkkkkkkkbgo.",
    "..obkkkkGkkkbgo.",
    "..obkkkGGGkkbgo.",
    "..obkkGkGkGkbgo.",
    "..obkkkGGGkkbgo.",
    "..obkkGkGkGkbgo.",
    "..obkGkkGkkGbgo.",
    "..obkkkkGkkkbgo.",
    "..obkkkkkkkkbgo.",
    "..obbbbbbbbbbgo.",
    "..oppppppppppgo.",
    "..oooooooooooo..",
    "................",
]
BLACK_PAL = {"o": OUT, "b": rgba(0x1A2A1E), "k": rgba(0x0E1410), "G": rgba(0x58C878), "g": rgba(0x2E5A3A),
             "p": rgba(0xC8C0A0)}

SCROLL = [
    "................",
    "..cRRRRRRRRRRc..",
    "..cwwwwwwwwwwc..",
    "...pPPPPPPPPp...",
    "...pPqPPqqPPp...",
    "...pPPPPPPPPp...",
    "...pPqqPPqPPp...",
    "...pPPPPPPPPp...",
    "...pPPqqPPqPp...",
    "...pPPPPPPPPp...",
    "...pPqPPqqPPp...",
    "...pPPPPPPPPp...",
    "..cwwwwwwwwwwc..",
    "..cRRRRRRRRRRc..",
    "................",
    "................",
]
SCROLL_PAL = {"c": rgba(0xF2C840), "R": rgba(0x4A2C18), "w": rgba(0x6A4426), "p": rgba(0xB8A070), "P": rgba(0xEDE0B8),
              "q": rgba(0x9A8458)}

RELIC = [
    "................",
    "......oooo......",
    ".....oHhhho.....",
    "....oHhhhhho....",
    "...oHhSShhhho...",
    "...ohhShhhhso...",
    "...ohhhhhhsso...",
    "...ohhhhhssso...",
    "....ohhhssso....",
    ".....ohssso.....",
    "......osso......",
    "................",
    "................",
    "................",
    "................",
    "................",
]
RELIC_PAL = {"o": OUT, "H": rgba(0xFFFFFF), "h": rgba(0xD8D8D8), "S": rgba(0xF4F4F4), "s": rgba(0x9A9A9A)}
MOUNT = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    ".....oYyyYo.....",
    "....oYYyyYYo....",
    "...oyYYYYYYyo...",
    "...ooooooooooo..",
    "................",
]
MOUNT_PAL = {"o": OUT, "Y": rgba(0xF2D060), "y": rgba(0xB89030)}


def model(name, layers):
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {f"layer{i}": "skycraft:item/" + l for i, l in enumerate(layers)}},
                  f, indent=2)
        f.write("\n")


def save(img, name):
    img.save(os.path.join(TEX, name + ".png"))


def main():
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(MODELS, exist_ok=True)
    save(paint(GEM, GEM_PAL), "unusual_gem")
    model("unusual_gem", ["unusual_gem"])
    save(paint(CROWN, CROWN_PAL), "crown_of_barenziah")
    model("crown_of_barenziah", ["crown_of_barenziah"])
    save(paint(BLACK_BOOK, BLACK_PAL), "black_book")
    model("black_book", ["black_book"])
    save(paint(SCROLL, SCROLL_PAL), "elder_scroll")
    model("elder_scroll", ["elder_scroll"])
    save(paint(RELIC, RELIC_PAL), "regional_treasure")
    save(paint(MOUNT, MOUNT_PAL), "regional_treasure_mount")
    model("regional_treasure", ["regional_treasure", "regional_treasure_mount"])
    print("loot textures written")


if __name__ == "__main__":
    main()
