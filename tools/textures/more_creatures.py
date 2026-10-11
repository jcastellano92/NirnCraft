#!/usr/bin/env python3
"""Generates the Falmer's humanoid skin (64x64). The other creatures that used to be painted here (frostbite spiders,
ice wraiths, spriggans, hagravens, wispmothers) are rig models now, painted by bestiary_skyrim.py.
"""
import os
import random
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures", "entity", "creatures")
os.makedirs(OUT, exist_ok=True)

def clamp(v):
    return max(0, min(255, int(round(v))))

def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3]) + ((c[3],) if len(c) > 3 else (255,))

def jitter(c, rnd, amt):
    d = rnd.uniform(-amt, amt)
    return tuple(clamp(x + d) for x in c[:3]) + ((c[3],) if len(c) > 3 else (255,))

def generate_humanoid(base_rgb, accent_rgb, eye_rgb, cloth_rgb, path, rnd_seed):
    rnd = random.Random(rnd_seed)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for y in range(64):
        for x in range(64):
            # Head (0..31, 0..15)
            if 0 <= y < 16 and 0 <= x < 32:
                c = jitter(base_rgb, rnd, 8)
                # Face area: (8..15, 8..15)
                if 8 <= x < 16 and 8 <= y < 16:
                    if (x in (9, 10, 13, 14)) and y == 12:
                        c = eye_rgb # eyes
                    elif 10 <= x <= 13 and y == 14:
                        c = shade(base_rgb, 0.75) # mouth
                img.putpixel((x, y), c)
            # Torso (16..39, 16..31)
            elif 16 <= y < 32 and 16 <= x < 40:
                c = jitter(cloth_rgb if y > 24 else base_rgb, rnd, 10)
                img.putpixel((x, y), c)
            # Right arm (40..55, 16..31)
            elif 16 <= y < 32 and 40 <= x < 56:
                c = jitter(base_rgb, rnd, 8)
                img.putpixel((x, y), c)
            # Right leg (0..15, 16..31)
            elif 16 <= y < 32 and 0 <= x < 16:
                c = jitter(shade(base_rgb, 0.9), rnd, 8)
                img.putpixel((x, y), c)
            # Left leg (16..31, 48..63)
            elif 48 <= y < 64 and 16 <= x < 32:
                c = jitter(shade(base_rgb, 0.9), rnd, 8)
                img.putpixel((x, y), c)
            # Left arm (32..47, 48..63)
            elif 48 <= y < 64 and 32 <= x < 48:
                c = jitter(base_rgb, rnd, 8)
                img.putpixel((x, y), c)
            # Torso outer / overlay (16..39, 32..47)
            elif 32 <= y < 48 and 16 <= x < 40:
                if y >= 36:
                    c = jitter(cloth_rgb, rnd, 12)
                    img.putpixel((x, y), c)

    img.save(path)
    print("Saved", path)

def main():
    # Falmer: pale sickly subterranean skin, chitin loincloth, milky eyes. (Spriggans, hagravens, wispmothers,
    # frostbite spiders and ice wraiths are rig models now, painted by bestiary_skyrim.py.)
    generate_humanoid(
        base_rgb=(185, 175, 155),
        accent_rgb=(140, 130, 115),
        eye_rgb=(240, 240, 235),
        cloth_rgb=(85, 55, 40),
        path=os.path.join(OUT, "falmer.png"),
        rnd_seed=101
    )


if __name__ == "__main__":
    main()
