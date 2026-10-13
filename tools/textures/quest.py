#!/usr/bin/env python3
"""Generates the quest module's 16x16 item textures (pixel art from character maps).

Writes PNGs into mod/src/main/resources/assets/skycraft/textures/item/:
ancient_tome, monster_trophy, black_hand_letter, courier_letter.

Usage: python3 tools/textures/quest.py
"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures", "item")


def hexc(rgb, a=255):
    return ((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, a)


TEXTURES = {
    "ancient_tome": (
        {
            "K": 0x24160C, "B": 0x6B3A1E, "b": 0x4A2612, "H": 0x8C5430, "G": 0xD8AC3C, "g": 0x9A7420,
            "P": 0xEADFC4, "p": 0xC4B494,
        },
        [
            "................",
            "..KKKKKKKKKKK...",
            ".KGbBBBBBBBGK...",
            ".KbBHHHHHHHBKP..",
            ".KbBHBBBBBBBKPp.",
            ".KbBBBBGBBBBKPp.",
            ".KbBBGBGBGBBKPp.",
            ".KbBBBGGGBBBKPp.",
            ".KbBBBBGBBBBKPp.",
            ".KbBBBGgGBBBKPp.",
            ".KbBBGBBBGBBKPp.",
            ".KbBBBBBBBBBKPp.",
            ".KbBBBBBBBBBKPp.",
            ".KGbBBBBBBBGKPp.",
            "..KKKKKKKKKKKpp.",
            "...ppppppppppp..",
        ],
    ),
    "monster_trophy": (
        {
            "W": 0x8A5A2C, "w": 0x5E3A1A, "F": 0x9A9A8C, "f": 0x66665A, "E": 0xD82020, "T": 0xEAE2C8,
            "t": 0xB8AE90, "N": 0x3E3E36,
        },
        [
            "................",
            "...T........T...",
            "...TT......TT...",
            "....TfFFFFfT....",
            "....fFFFFFFf....",
            "...fFFFFFFFFf...",
            "...fFEfFFfEFf...",
            "...fFFFNNFFFf...",
            "...fFFNNNNFFf...",
            "...fFTFFFFTFf...",
            "..wWfFTffTFfWw..",
            ".wWWWfFFFFfWWWw.",
            ".wWWWWffffWWWWw.",
            ".wWWWWWWWWWWWWw.",
            "..wwwwwwwwwwww..",
            "................",
        ],
    ),
    "black_hand_letter": (
        {
            "K": 0x3A2A1A, "P": 0xE2D4AA, "p": 0xB8A678, "H": 0x141010, "R": 0xA81818, "r": 0x6E0E0E,
        },
        [
            "................",
            "................",
            ".KKKKKKKKKKKKKK.",
            ".KPPPPPPPPPPPPK.",
            ".KPPPPHPHPHPPPK.",
            ".KPPPPHPHPHPHPK.",
            ".KPPPPHHHHHPHPK.",
            ".KPPPPHHHHHHHPK.",
            ".KPPHHHHHHHHPPK.",
            ".KPPPHHHHHHHPPK.",
            ".KPPPPHHHHHPPPK.",
            ".KpPPPPHHHPPPpK.",
            ".KppPPPPPPPPppK.",
            ".KKKKKKRRKKKKKK.",
            "......RrrR......",
            ".......RR.......",
        ],
    ),
    "cottage_key": (
        {
            "K": 0x2A2118, "G": 0xB8AA8A, "g": 0x7E7360, "H": 0xE6DCC0,
        },
        [
            "................",
            "................",
            "...KKKK.........",
            "..KHHHHK........",
            ".KHKKKKGK.......",
            ".KGK..KGKKKKKKK.",
            ".KGK..KGGGGGGGGK",
            ".KGKKKKGGggggggK",
            "..KGGGGKKKKKGKKK",
            "...KKKK...KGKKGK",
            "..........KGKKGK",
            "..........KKK.KK",
            "................",
            "................",
            "................",
            "................",
        ],
    ),
    "courier_letter": (
        {
            "K": 0x5A4A32, "E": 0xE8DCC0, "e": 0xC0B090, "R": 0xB02020, "r": 0x701010, "S": 0x8A6A3A,
        },
        [
            "................",
            "................",
            "................",
            ".KKKKKKKKKKKKKK.",
            ".KeEEEEEEEEEEeK.",
            ".KEeEEEEEEEEeEK.",
            ".KEEeEEEEEEeEEK.",
            ".KEEEeEEEEeEEEK.",
            ".KEEEEeRReEEEEK.",
            "SSSSSSRrrRSSSSSS",
            ".KEEEEEERREEEEK.",
            ".KEEEEEEEEEEEEK.",
            ".KeEEEEEEEEEEeK.",
            ".KKKKKKKKKKKKKK.",
            "................",
            "................",
        ],
    ),
}


def render(palette, rows):
    assert len(rows) == 16, rows
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row)
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            px[x, y] = hexc(palette[ch])
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (palette, rows) in TEXTURES.items():
        path = os.path.join(OUT, name + ".png")
        render(palette, rows).save(path)
        print("wrote", os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()
