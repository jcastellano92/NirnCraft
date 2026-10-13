#!/usr/bin/env python3
"""Fire, ruin and aftermath assets (module com.skycraft.fire): original procedural pixel art plus every block asset.

Writes
  * animated 16x16 flame textures (vertical strips with .mcmeta and a labPBR emission map "_s" for shader packs):
    big_fire, flame_column, flame_tip, rubble_fire, fire_coals, rubble_floor
  * the charred / scorched block set: charred_planks (+ stairs, slab), charred_log, charred_beam, scorched_stone,
    scorched_stone_bricks, plus the decals soot_stain, blood_stain and the timber_debris model
  * the sprite sheets of the client-side far fire renderer: textures/fire/far_flame.png (8 frames of 32x64),
    far_smoke.png (4 puffs of 64x64), far_glow.png
  * blockstates, block / item models, block loot tables and the module's lang file (src/main/lang/skycraft/fire.json)

Run from anywhere:  py -3 tools/textures/fire.py
"""
import json
import math
import os
import random

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "skycraft")
DATA = os.path.join(RES, "data", "skycraft")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft", "fire.json")
TEX = os.path.join(ASSETS, "textures", "block")
FIRE_TEX = os.path.join(ASSETS, "textures", "fire")
SK = "skycraft"
FRAMES = 8


# ------------------------------------------------------------------------------------------------ helpers
def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def tile_noise(w, h, cw, ch, seed):
    """Value noise on a w x h torus (cells of cw x ch pixels): wraps in both axes, so shifting it loops seamlessly."""
    rng = np.random.default_rng(seed)
    gw, gh = max(1, w // cw), max(1, h // ch)
    g = rng.random((gh, gw))
    x = np.arange(w) / cw
    y = np.arange(h) / ch
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    fx = x - x0
    fy = y - y0
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    a = g[np.ix_(y0 % gh, x0 % gw)]
    b = g[np.ix_(y0 % gh, (x0 + 1) % gw)]
    c = g[np.ix_((y0 + 1) % gh, x0 % gw)]
    d = g[np.ix_((y0 + 1) % gh, (x0 + 1) % gw)]
    top = a + (b - a) * fx[None, :]
    bot = c + (d - c) * fx[None, :]
    return top + (bot - top) * fy[:, None]


FLAME_PAL = [hexc(h) for h in ("#2a0a06", "#7a1608", "#c93a0c", "#ee7a1a", "#ffb42c", "#ffe27a", "#fff6c8")]
FAR_PAL = [hexc(h) for h in ("#5a1206", "#a82208", "#e2540e", "#ff8f1f", "#ffc23a", "#ffe98a", "#fffbe0")]


def flame(w, hgt, frame, frames, seed, shape, pal, floor_glow=0.0):
    """One frame of flame pixel art (RGBA array hgt x w) and its emission strength (hgt x w, 0..1)."""
    n1 = tile_noise(w, hgt, max(2, w // 4), max(2, hgt // 4), seed)
    n2 = tile_noise(w, hgt, max(2, w // 8), max(2, hgt // 8), seed + 7)
    shift = int(round(hgt * frame / frames))
    n = 0.62 * np.roll(n1, -shift, axis=0) + 0.38 * np.roll(n2, -2 * shift, axis=0)
    yy = (1.0 - (np.arange(hgt) + 0.5) / hgt)[:, None]           # 0 at the bottom, 1 at the top
    xx = ((np.arange(w) + 0.5) / w * 2.0 - 1.0)[None, :]
    ph = 2 * math.pi * frame / frames
    sway = 0.10 * np.sin(ph + yy * 4.0 + seed) * (0.4 + yy)
    if shape == "flame":
        width = 0.95 - 0.78 * yy ** 1.1
        fall = 0.30 * yy
    elif shape == "column":
        width = 0.70 + 0.12 * np.sin(2 * math.pi * 2 * yy + seed)
        fall = 0.0 * yy
    elif shape == "tip":
        width = 0.78 * (1.0 - yy ** 1.5) + 0.04
        fall = 0.10 * yy
    elif shape == "rubble":
        low = np.clip(yy / 0.62, 0, 1)
        width = (0.95 - 0.7 * low ** 1.2) * (yy < 0.62)
        fall = 0.18 * low
    elif shape == "far":
        width = None
        fall = 0.08 * yy
    else:
        raise ValueError(shape)
    if width is None:
        # three tongues: a tall one in the middle and two shorter ones that lean away from it
        base = np.full((hgt, w), -1.0)
        for c, wk, hk, ps in ((0.0, 0.52, 1.0, 0.0), (-0.52, 0.36, 0.66, 2.1), (0.5, 0.38, 0.74, 4.2)):
            wl = wk * np.clip(1.0 - (yy / hk) ** 1.25, 0, 1) + 0.03
            sw = 0.12 * np.sin(ph + yy * 3.5 + ps) * (0.3 + yy)
            tongue = (1.0 - np.abs(xx - c - sw) / wl) * (yy < hk)
            base = np.maximum(base, tongue)
    else:
        base = 1.0 - np.abs(xx - sway) / np.maximum(width, 0.05)
    v = base * (0.58 + 0.80 * n) - fall + 0.22 * (1 - yy) ** 2 * (1 - np.abs(xx) * 0.8)
    if shape == "rubble":
        v = v * (yy < 0.62)
    mask = v > 0.14
    t = np.clip((v - 0.14) / 0.72, 0, 0.999)
    idx = (t * len(pal)).astype(int)
    img = np.zeros((hgt, w, 4), np.uint8)
    for k, c in enumerate(pal):
        sel = mask & (idx == k)
        img[sel] = c
    # a few flying sparks above flames
    rng = random.Random(seed * 31 + frame * 7)
    if shape in ("flame", "tip"):
        for _ in range(max(1, w // 10)):
            sx = rng.randrange(w)
            sy = rng.randrange(0, max(1, int(hgt * 0.35)))
            if not mask[sy, sx]:
                img[sy, sx] = pal[-2]
                mask[sy, sx] = True
    emis = np.where(mask, 0.55 + 0.45 * np.clip(t, 0, 1), 0.0)
    return img, emis


def strip(name, shape, seed, pal=FLAME_PAL, size=16, frames=FRAMES, frametime=2):
    """An animated block texture: vertical strip, .mcmeta, and the labPBR emission map."""
    col = np.zeros((size * frames, size, 4), np.uint8)
    spec = np.zeros((size * frames, size, 4), np.uint8)
    spec[..., 3] = 0
    for f in range(frames):
        img, emis = flame(size, size, f, frames, seed, shape, pal)
        col[f * size:(f + 1) * size] = img
        block = spec[f * size:(f + 1) * size]
        on = emis > 0
        # labPBR: emission is the alpha channel, 0 (none) .. 254 (strongest); 255 also means none
        block[..., 3] = np.where(on, np.clip((emis * 254).astype(int), 1, 254), 0).astype(np.uint8)
    save(Image.fromarray(col, "RGBA"), os.path.join(TEX, name + ".png"))
    save(Image.fromarray(spec, "RGBA"), os.path.join(TEX, name + "_s.png"))
    meta = {"animation": {"frametime": frametime, "interpolate": True}}
    for suffix in ("", "_s"):
        write_json(os.path.join(TEX, name + suffix + ".png.mcmeta"), meta)


def floor_strip(name, seed, kind):
    """Embers / coals on the ground, animated glow pulse (8 frames)."""
    col = np.zeros((16 * FRAMES, 16, 4), np.uint8)
    spec = np.zeros((16 * FRAMES, 16, 4), np.uint8)
    spec[..., 3] = 0
    n = tile_noise(16, 16, 4, 4, seed)
    n2 = tile_noise(16, 16, 2, 2, seed + 3)
    rng = random.Random(seed)
    dark = [hexc(h) for h in ("#0c0907", "#171210", "#221a15", "#2d231c")]
    hot = [hexc(h) for h in ("#5c1608", "#a32a0c", "#e0541a", "#ff8a2c", "#ffc860")]
    for f in range(FRAMES):
        pulse = 0.5 + 0.5 * math.sin(2 * math.pi * f / FRAMES + 0.0)
        for y in range(16):
            for x in range(16):
                v = 0.6 * n[y, x] + 0.4 * n2[y, x]
                if kind == "coals":
                    glow = max(0.0, v - 0.30 + 0.25 * pulse * (n2[(y + 5) % 16, (x + 3) % 16] - 0.4))
                    if glow > 0.12:
                        c = hot[min(len(hot) - 1, int(glow * 7.0))]
                    else:
                        c = dark[int(v * 3.99)]
                else:  # rubble: dark charred pieces with a few live embers
                    ember = n2[y, x] > 0.80 and (x * 3 + y * 5 + f) % 4 != 0
                    c = hot[1 + int(pulse * 2.5)] if ember else dark[int(v * 3.99)]
                    if rng.random() < 0.04:
                        c = hexc("#3a2c20")
                col[f * 16 + y, x] = c
                hot_px = c[0] > 90 and c[1] < 120 and c[2] < 60
                spec[f * 16 + y, x, 3] = 140 if hot_px else 0
    save(Image.fromarray(col, "RGBA"), os.path.join(TEX, name + ".png"))
    save(Image.fromarray(spec, "RGBA"), os.path.join(TEX, name + "_s.png"))
    for suffix in ("", "_s"):
        write_json(os.path.join(TEX, name + suffix + ".png.mcmeta"), {"animation": {"frametime": 4, "interpolate": True}})


# ------------------------------------------------------------------------------------------------ static blocks
def pick(p, v):
    return p[int(max(0.0, min(0.999, v)) * len(p))]


def charred_planks(seed=401):
    r = random.Random(seed)
    pal = [hexc(h) for h in ("#0f0b09", "#17110d", "#201813", "#2a1f18", "#36291f")]
    img = Image.new("RGBA", (16, 16))
    n = tile_noise(16, 16, 4, 2, seed)
    for y in range(16):
        row = y // 4
        off = (row * 5) % 16
        for x in range(16):
            seam = y % 4 == 3 or (x + off) % 16 == 0 and y % 4 == 0
            v = 0.55 * n[y, x] + 0.3 * r.random() + 0.1 * row / 4
            c = pal[0] if seam else pick(pal, v)
            img.putpixel((x, y), c)
    for _ in range(5):  # ember cracks
        x, y = r.randrange(16), r.randrange(16)
        for _ in range(r.randrange(2, 5)):
            img.putpixel((x % 16, y % 16), hexc("#5a2210"))
            x += r.choice((1, 0, 1, -1))
            y += r.choice((0, 1))
    return img


def charred_log(side=True, seed=411):
    r = random.Random(seed + (0 if side else 9))
    img = Image.new("RGBA", (16, 16))
    pal = [hexc(h) for h in ("#0b0807", "#130e0b", "#1c1511", "#271c15", "#33271d")]
    if side:
        n = tile_noise(16, 16, 2, 8, seed)
        for y in range(16):
            for x in range(16):
                v = 0.7 * n[y, x] + 0.3 * r.random()
                if x % 5 == 0:
                    v *= 0.5
                img.putpixel((x, y), pick(pal, v))
        for _ in range(6):
            x, y = r.randrange(16), r.randrange(16)
            for k in range(r.randrange(2, 6)):
                img.putpixel((x, (y + k) % 16), hexc("#4e1d0e"))
    else:
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                ring = int(d * 1.3) % 2
                edge = d > 7.2
                c = pal[1] if edge else (pal[3] if ring else pal[2])
                if d < 1.6:
                    c = pal[0]
                if r.random() < 0.08:
                    c = pal[4]
                img.putpixel((x, y), c)
    return img


def charred_beam(side=True, seed=421):
    r = random.Random(seed + (0 if side else 5))
    img = Image.new("RGBA", (16, 16))
    pal = [hexc(h) for h in ("#0d0a08", "#16110d", "#1f1812", "#2b2018", "#3a2c21")]
    for y in range(16):
        for x in range(16):
            if side:
                edge = x in (0, 15)
                band = y % 8 in (0, 7)
                v = 0.4 + 0.3 * r.random() + (0.0 if band else 0.15)
                c = pal[0] if edge else (pal[1] if band else pick(pal, v))
            else:
                edge = x in (0, 15) or y in (0, 15)
                c = pal[1] if edge else pick(pal, 0.35 + 0.35 * r.random())
            img.putpixel((x, y), c)
    return img


def scorched_stone(bricks=False, seed=431):
    r = random.Random(seed + (7 if bricks else 0))
    pal = [hexc(h) for h in ("#1c1b1a", "#292826", "#363532", "#46443f", "#585650", "#6a6760")]
    n = tile_noise(16, 16, 4, 4, seed)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            v = 0.5 * n[y, x] + 0.25 * r.random() + 0.1
            soot = 0.45 * (1 - y / 15.0) ** 1.4  # blackened towards the top (where the smoke went)
            v = max(0.0, v - soot - (0.12 if (x * 7 + y * 3) % 11 == 0 else 0))
            c = pick(pal, v)
            if bricks:
                if y % 4 == 3 or (x + (y // 4 % 2) * 4) % 8 == 0:
                    c = pal[0]
            img.putpixel((x, y), c)
    return img


def arrows_tex():
    """Three arrows seen from the side on one 16x16 sheet: shaft, iron head, feather vanes."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    shaft, head, vane, vane2 = hexc("#7a5a3a"), hexc("#8d8f94"), hexc("#d9d2c0"), hexc("#a02828")
    for x0 in (2, 7, 12):
        for y in range(2, 16):
            img.putpixel((x0, y), shaft)
        img.putpixel((x0, 1), head)
        img.putpixel((x0, 0), head)
        img.putpixel((x0 - 1, 2), head)
        img.putpixel((x0 + 1, 2), head)
        for y in (13, 14, 15):
            img.putpixel((x0 - 1, y), vane)
            img.putpixel((x0 + 1, y), vane2 if x0 == 7 else vane)
    return img


def decal(color_hex, seed, density=0.55, alpha_lo=120, alpha_hi=230):
    """A ragged splotch decal with alpha (soot, blood)."""
    r = random.Random(seed)
    n = tile_noise(16, 16, 4, 4, seed)
    n2 = tile_noise(16, 16, 2, 2, seed + 1)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    base = hexc(color_hex)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) / 8.0
            v = 0.65 * n[y, x] + 0.35 * n2[y, x] - 0.55 * d ** 1.6
            if v > 1.0 - density - 0.25:
                a = int(alpha_lo + (alpha_hi - alpha_lo) * min(1.0, (v - 0.2) * 1.4))
                shade = r.random() * 0.25
                img.putpixel((x, y), (int(base[0] * (1 + shade)), int(base[1] * (1 + shade)), int(base[2] * (1 + shade)), a))
    return img


# ------------------------------------------------------------------------------------------------ far sheets
def far_flame_sheet():
    w, h, frames = 32, 64, 8
    sheet = np.zeros((h, w * frames, 4), np.uint8)
    for f in range(frames):
        img, _ = flame(w, h, f, frames, 77, "far", FAR_PAL)
        sheet[:, f * w:(f + 1) * w] = img
    save(Image.fromarray(sheet, "RGBA"), os.path.join(FIRE_TEX, "far_flame.png"))


def far_smoke_sheet():
    size, count = 64, 4
    sheet = np.zeros((size, size * count, 4), np.uint8)
    for k in range(count):
        n = tile_noise(size, size, 16, 16, 300 + k)
        n2 = tile_noise(size, size, 8, 8, 320 + k)
        yy, xx = np.mgrid[0:size, 0:size]
        d = np.hypot((xx - size / 2 + 0.5) / (size / 2), (yy - size / 2 + 0.5) / (size / 2))
        v = 1.0 - d ** 1.7 + 0.55 * (0.6 * n + 0.4 * n2 - 0.5)
        a = np.clip(v, 0, 1) ** 1.4
        shade = 150 + 80 * (0.5 * n + 0.5 * n2)
        tile = np.zeros((size, size, 4), np.uint8)
        tile[..., 0] = shade
        tile[..., 1] = shade
        tile[..., 2] = shade
        tile[..., 3] = (a * 255).astype(np.uint8)
        sheet[:, k * size:(k + 1) * size] = tile
    save(Image.fromarray(sheet, "RGBA"), os.path.join(FIRE_TEX, "far_smoke.png"))


def far_glow():
    size = 64
    yy, xx = np.mgrid[0:size, 0:size]
    d = np.hypot((xx - size / 2 + 0.5) / (size / 2), (yy - size / 2 + 0.5) / (size / 2))
    a = np.clip(1.0 - d, 0, 1) ** 2.3
    img = np.zeros((size, size, 4), np.uint8)
    img[..., 0] = 255
    img[..., 1] = (200 + 40 * (1 - d).clip(0, 1)).astype(np.uint8)
    img[..., 2] = (110 + 60 * (1 - d).clip(0, 1)).astype(np.uint8)
    img[..., 3] = (a * 255).astype(np.uint8)
    save(Image.fromarray(img, "RGBA"), os.path.join(FIRE_TEX, "far_glow.png"))


# ------------------------------------------------------------------------------------------------ models
def plane(x0, z0, x1, z1, h, tex, angle=0):
    el = {"from": [x0, 0, z0], "to": [x1, h, z1], "shade": False,
          "faces": {"north": {"uv": [0, 0, 16, 16], "texture": tex}, "south": {"uv": [0, 0, 16, 16], "texture": tex}}}
    if angle:
        el["rotation"] = {"origin": [8, 8, 8], "axis": "y", "angle": angle, "rescale": True}
    return el


def plane_x(x, tex):
    return {"from": [x, 0, 0], "to": [x, 16, 16], "shade": False,
            "faces": {"east": {"uv": [0, 0, 16, 16], "texture": tex}, "west": {"uv": [0, 0, 16, 16], "texture": tex}}}


def fire_model(tex_name, coals=None, wide=True):
    elements = [plane(0.8, 8, 15.2, 8, 16, "#fire", 45), plane(0.8, 8, 15.2, 8, 16, "#fire", -45)]
    if wide:
        elements.append(plane(0, 3.5, 16, 3.5, 16, "#fire"))
        elements.append(plane(0, 12.5, 16, 12.5, 16, "#fire"))
        elements.append(plane_x(3.5, "#fire"))
        elements.append(plane_x(12.5, "#fire"))
    textures = {"particle": f"{SK}:block/{tex_name}", "fire": f"{SK}:block/{tex_name}"}
    if coals:
        textures["coals"] = f"{SK}:block/{coals}"
        elements.insert(0, {"from": [0, 0, 0], "to": [16, 1.2, 16], "shade": False,
                            "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#coals"},
                                      "north": {"uv": [0, 14.8, 16, 16], "texture": "#coals"},
                                      "south": {"uv": [0, 14.8, 16, 16], "texture": "#coals"},
                                      "east": {"uv": [0, 14.8, 16, 16], "texture": "#coals"},
                                      "west": {"uv": [0, 14.8, 16, 16], "texture": "#coals"}}})
    return {"ambientocclusion": False, "textures": textures, "elements": elements}


def flat_decal_model(tex):
    return {"ambientocclusion": False, "textures": {"particle": f"{SK}:block/{tex}", "decal": f"{SK}:block/{tex}"},
            "elements": [{"from": [0, 0.04, 0], "to": [16, 0.04, 16], "shade": False,
                          "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#decal"}}}]}


def debris_model(tex, pieces):
    els = []
    for (x0, z0, x1, z1, y0, h, rot) in pieces:
        el = {"from": [x0, y0, z0], "to": [x1, y0 + h, z1],
              "faces": {f: {"uv": [0, 0, 16, 2] if f in ("north", "south", "east", "west") else [0, 0, 16, 4], "texture": "#t"}
                        for f in ("north", "south", "east", "west", "up", "down")}}
        if rot:
            el["rotation"] = {"origin": [(x0 + x1) / 2, y0, (z0 + z1) / 2], "axis": "y", "angle": rot, "rescale": False}
        els.append(el)
    return {"textures": {"particle": f"{SK}:block/{tex}", "t": f"{SK}:block/{tex}"}, "elements": els}


def cube_all(tex):
    return {"parent": "minecraft:block/cube_all", "textures": {"all": f"{SK}:block/{tex}"}}


def stairs_state(name):
    table = json.loads('{"facing=east,half=bottom,shape=inner_left":["i",0,270,true],"facing=east,half=bottom,shape=inner_right":["i",0,0,false],"facing=east,half=bottom,shape=outer_left":["o",0,270,true],"facing=east,half=bottom,shape=outer_right":["o",0,0,false],"facing=east,half=bottom,shape=straight":["s",0,0,false],"facing=east,half=top,shape=inner_left":["i",180,0,true],"facing=east,half=top,shape=inner_right":["i",180,90,true],"facing=east,half=top,shape=outer_left":["o",180,0,true],"facing=east,half=top,shape=outer_right":["o",180,90,true],"facing=east,half=top,shape=straight":["s",180,0,true],"facing=north,half=bottom,shape=inner_left":["i",0,180,true],"facing=north,half=bottom,shape=inner_right":["i",0,270,true],"facing=north,half=bottom,shape=outer_left":["o",0,180,true],"facing=north,half=bottom,shape=outer_right":["o",0,270,true],"facing=north,half=bottom,shape=straight":["s",0,270,true],"facing=north,half=top,shape=inner_left":["i",180,270,true],"facing=north,half=top,shape=inner_right":["i",180,0,true],"facing=north,half=top,shape=outer_left":["o",180,270,true],"facing=north,half=top,shape=outer_right":["o",180,0,true],"facing=north,half=top,shape=straight":["s",180,270,true],"facing=south,half=bottom,shape=inner_left":["i",0,0,false],"facing=south,half=bottom,shape=inner_right":["i",0,90,true],"facing=south,half=bottom,shape=outer_left":["o",0,0,false],"facing=south,half=bottom,shape=outer_right":["o",0,90,true],"facing=south,half=bottom,shape=straight":["s",0,90,true],"facing=south,half=top,shape=inner_left":["i",180,90,true],"facing=south,half=top,shape=inner_right":["i",180,180,true],"facing=south,half=top,shape=outer_left":["o",180,90,true],"facing=south,half=top,shape=outer_right":["o",180,180,true],"facing=south,half=top,shape=straight":["s",180,90,true],"facing=west,half=bottom,shape=inner_left":["i",0,90,true],"facing=west,half=bottom,shape=inner_right":["i",0,180,true],"facing=west,half=bottom,shape=outer_left":["o",0,90,true],"facing=west,half=bottom,shape=outer_right":["o",0,180,true],"facing=west,half=bottom,shape=straight":["s",0,180,true],"facing=west,half=top,shape=inner_left":["i",180,180,true],"facing=west,half=top,shape=inner_right":["i",180,270,true],"facing=west,half=top,shape=outer_left":["o",180,180,true],"facing=west,half=top,shape=outer_right":["o",180,270,true],"facing=west,half=top,shape=straight":["s",180,180,true]}')
    suffix = {"s": "", "i": "_inner", "o": "_outer"}
    variants = {}
    for k, (m, x, y, uv) in table.items():
        v = {"model": f"{SK}:block/{name}{suffix[m]}"}
        if x:
            v["x"] = x
        if y:
            v["y"] = y
        if uv:
            v["uvlock"] = True
        variants[k] = v
    return {"variants": variants}


# ------------------------------------------------------------------------------------------------ main
FIRE_BLOCKS = ["big_fire", "flame_column", "flame_tip", "rubble_fire", "smouldering_embers"]
SIMPLE = ["charred_planks", "scorched_stone", "scorched_stone_bricks"]

LANG_ENTRIES = {
    "block.skycraft.big_fire": "Big Fire",
    "block.skycraft.flame_column": "Flame Column",
    "block.skycraft.flame_tip": "Flame Tip",
    "block.skycraft.rubble_fire": "Rubble Fire",
    "block.skycraft.smouldering_embers": "Smouldering Embers",
    "block.skycraft.charred_planks": "Charred Planks",
    "block.skycraft.charred_planks_stairs": "Charred Plank Stairs",
    "block.skycraft.charred_planks_slab": "Charred Plank Slab",
    "block.skycraft.charred_log": "Charred Log",
    "block.skycraft.charred_beam": "Charred Beam",
    "block.skycraft.scorched_stone": "Scorched Stone",
    "block.skycraft.scorched_stone_bricks": "Scorched Stone Bricks",
    "block.skycraft.soot_stain": "Soot Stain",
    "block.skycraft.blood_stain": "Blood Stain",
    "block.skycraft.timber_debris": "Timber Debris",
    "block.skycraft.splintered_wreckage": "Splintered Wreckage",
    "block.skycraft.stuck_arrows": "Stuck Arrows",
    "config.skycraft.fire": "Fire",
    "fire.skycraft.line.grief.1": "It came out of the sky. Just like that.",
    "fire.skycraft.line.grief.2": "They were at the well. I saw them go.",
    "fire.skycraft.line.grief.3": "Gods, the smell of it. I can't stop smelling it.",
    "fire.skycraft.line.grief.4": "I should have taken the road yesterday.",
    "fire.skycraft.line.grief.5": "He said he'd be back before the noon bell.",
    "fire.skycraft.line.grief.6": "Leave me be. Please.",
    "fire.skycraft.line.survivor.1": "Is the road clear? Tell me the road is clear.",
    "fire.skycraft.line.survivor.2": "We have bread, if you have a fire to share.",
    "fire.skycraft.line.survivor.3": "The wall held. Everything else... no.",
    "fire.skycraft.line.survivor.4": "Don't go near the keep. The beams are still coming down.",
    "fire.skycraft.line.survivor.5": "Whoever you are, the soldiers at the gate will want your name.",
    "fire.skycraft.line.survivor.6": "I keep counting the roofs. There were more of them.",
    "fire.skycraft.line.child.1": "Mama won't wake up.",
    "fire.skycraft.line.child.2": "Is the big thing coming back?",
    "fire.skycraft.line.child.3": "I want to go home.",
    "fire.skycraft.line.child.4": "My shoes got burned.",
    "fire.skycraft.line.mourn.1": "Rest easy. The stones will remember you.",
    "fire.skycraft.line.mourn.2": "We'll give them to the fire by dusk.",
    "fire.skycraft.line.mourn.3": "May Arkay find them quickly.",
    "fire.skycraft.line.mourn.4": "Eight we know. There may be more under the walls.",
    "fire.skycraft.line.healer.1": "Stay still. Let me see that.",
    "fire.skycraft.line.healer.2": "Water, and clean linen. Anyone with linen?",
    "fire.skycraft.line.healer.3": "You'll live. Many won't. Sit down.",
    "fire.skycraft.line.healer.4": "If it turns black, come straight back to me.",
    "fire.skycraft.line.healer.5": "Stendarr's mercy, there are so many.",
    "fire.skycraft.line.cook.1": "Soup's thin, but it's hot.",
    "fire.skycraft.line.cook.2": "Bowls back when you're done. We have so few.",
    "fire.skycraft.line.cook.3": "Eat. You can't dig on an empty belly.",
    "fire.skycraft.line.worker.1": "Mind the beam. Lift on three.",
    "fire.skycraft.line.worker.2": "Anyone under there? Call out!",
    "fire.skycraft.line.worker.3": "Another one for the cart.",
    "fire.skycraft.line.worker.4": "We'll have this cleared by winter. Maybe.",
    "fire.skycraft.line.worker.5": "Keep moving. The fire's not done with us.",
    "fire.skycraft.line.bucket.1": "Pass it along! Don't spill it!",
    "fire.skycraft.line.bucket.2": "Faster, the wind is turning!",
    "fire.skycraft.line.bucket.3": "Next! Next!",
    "fire.skycraft.line.bucket.4": "My arms are gone, don't stop!",
    "fire.skycraft.line.guard.1": "Move along. Keep to the road.",
    "fire.skycraft.line.guard.2": "Nobody leaves without a word to the captain.",
    "fire.skycraft.line.guard.3": "The Legion is holding Helgen. What's left of it.",
    "fire.skycraft.line.guard.4": "Watch the sky. Always the sky.",
    "fire.skycraft.line.guard.5": "Halt. State your business.",
    "fire.skycraft.line.captain.1": "Barricade the north street, now!",
    "fire.skycraft.line.captain.2": "Healers to the square, bearers to the keep!",
    "fire.skycraft.line.captain.3": "Eyes up. If it returns, scatter and take cover.",
    "fire.skycraft.line.captain.4": "Count the living first. Then the rest.",
    "fire.skycraft.line.captain.5": "Don't loot the dead. Not while I'm watching.",
    "fire.skycraft.line.patient.1": "Water... please.",
    "fire.skycraft.line.patient.2": "My leg. I can't feel my leg.",
    "fire.skycraft.line.patient.3": "Is it over?",
    "fire.skycraft.line.patient.4": "Tell my sister I...",
    "fire.skycraft.line.looter.1": "What? I'm... helping. Looking for family.",
    "fire.skycraft.line.looter.2": "Nothing here anyway. Burnt through.",
    "fire.skycraft.line.looter.3": "Mind your own business.",
    "fire.skycraft.body.nothing": "Nothing here but ash and cloth.",
}


def loot_self(name):
    return {"type": "minecraft:block", "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [{"type": "minecraft:item", "name": f"{SK}:{name}"}],
                                                  "conditions": [{"condition": "minecraft:survives_explosion"}]}],
            "random_sequence": f"{SK}:blocks/{name}"}


def loot_none(name):
    return {"type": "minecraft:block", "pools": [], "random_sequence": f"{SK}:blocks/{name}"}


def item_model(name):
    return {"parent": f"{SK}:block/{name}"}


def main():
    # ---- animated flames
    strip("big_fire", "flame", 11)
    strip("flame_column", "column", 23)
    strip("flame_tip", "tip", 37)
    strip("rubble_fire", "rubble", 53)
    floor_strip("fire_coals", 61, "coals")
    floor_strip("rubble_floor", 71, "rubble")

    # ---- static blocks
    save(charred_planks(), os.path.join(TEX, "charred_planks.png"))
    save(charred_log(True), os.path.join(TEX, "charred_log.png"))
    save(charred_log(False), os.path.join(TEX, "charred_log_top.png"))
    save(charred_beam(True), os.path.join(TEX, "charred_beam.png"))
    save(charred_beam(False), os.path.join(TEX, "charred_beam_top.png"))
    save(scorched_stone(False), os.path.join(TEX, "scorched_stone.png"))
    save(scorched_stone(True), os.path.join(TEX, "scorched_stone_bricks.png"))
    save(arrows_tex(), os.path.join(TEX, "stuck_arrows.png"))
    save(decal("#0d0b0a", 501, 0.55, 90, 190), os.path.join(TEX, "soot_stain.png"))
    save(decal("#4d0c0c", 511, 0.40, 150, 235), os.path.join(TEX, "blood_stain.png"))
    save(decal("#0d0b0a", 521, 0.50, 80, 170), os.path.join(TEX, "soot_stain_b.png"))
    save(decal("#3f0a0a", 531, 0.30, 140, 230), os.path.join(TEX, "blood_stain_b.png"))

    # ---- far renderer sheets
    far_flame_sheet()
    far_smoke_sheet()
    far_glow()

    models = os.path.join(ASSETS, "models", "block")
    items = os.path.join(ASSETS, "models", "item")
    states = os.path.join(ASSETS, "blockstates")
    loot = os.path.join(DATA, "loot_tables", "blocks")

    # fire family
    write_json(os.path.join(models, "big_fire.json"), fire_model("big_fire", "fire_coals", True))
    write_json(os.path.join(models, "flame_column.json"), fire_model("flame_column", None, True))
    write_json(os.path.join(models, "flame_tip.json"), fire_model("flame_tip", None, False))
    write_json(os.path.join(models, "rubble_fire.json"), fire_model("rubble_fire", "rubble_floor", False))
    write_json(os.path.join(models, "smouldering_embers.json"), {
        "ambientocclusion": False, "textures": {"particle": f"{SK}:block/fire_coals", "coals": f"{SK}:block/fire_coals"},
        "elements": [{"from": [0, 0, 0], "to": [16, 2, 16], "faces": {f: {"uv": [0, 0, 16, 16] if f == "up" else [0, 14, 16, 16],
                                                                          "texture": "#coals"} for f in ("up", "north", "south", "east", "west")}}]})
    for name in FIRE_BLOCKS:
        # 4 quarter turns picked per position: no two neighbouring fires look alike
        write_json(os.path.join(states, name + ".json"),
                   {"variants": {"": [{"model": f"{SK}:block/{name}", "y": y} for y in (0, 90, 180, 270)]}})
        write_json(os.path.join(items, name + ".json"), item_model(name))
        write_json(os.path.join(loot, name + ".json"), loot_none(name))

    # charred planks, stairs, slab
    write_json(os.path.join(models, "charred_planks.json"), cube_all("charred_planks"))
    write_json(os.path.join(states, "charred_planks.json"), {"variants": {"": {"model": f"{SK}:block/charred_planks"}}})
    tex = {"bottom": f"{SK}:block/charred_planks", "top": f"{SK}:block/charred_planks", "side": f"{SK}:block/charred_planks"}
    write_json(os.path.join(models, "charred_planks_stairs.json"), {"parent": "minecraft:block/stairs", "textures": tex})
    write_json(os.path.join(models, "charred_planks_stairs_inner.json"), {"parent": "minecraft:block/inner_stairs", "textures": tex})
    write_json(os.path.join(models, "charred_planks_stairs_outer.json"), {"parent": "minecraft:block/outer_stairs", "textures": tex})
    write_json(os.path.join(states, "charred_planks_stairs.json"), stairs_state("charred_planks_stairs"))
    write_json(os.path.join(models, "charred_planks_slab.json"), {"parent": "minecraft:block/slab", "textures": tex})
    write_json(os.path.join(models, "charred_planks_slab_top.json"), {"parent": "minecraft:block/slab_top", "textures": tex})
    write_json(os.path.join(states, "charred_planks_slab.json"), {"variants": {
        "type=bottom": {"model": f"{SK}:block/charred_planks_slab"},
        "type=double": {"model": f"{SK}:block/charred_planks"},
        "type=top": {"model": f"{SK}:block/charred_planks_slab_top"}}})

    # logs and beams (axis blocks)
    for name, side, end in (("charred_log", "charred_log", "charred_log_top"), ("charred_beam", "charred_beam", "charred_beam_top")):
        write_json(os.path.join(models, name + ".json"), {"parent": "minecraft:block/cube_column",
                                                           "textures": {"end": f"{SK}:block/{end}", "side": f"{SK}:block/{side}"}})
        write_json(os.path.join(models, name + "_horizontal.json"), {"parent": "minecraft:block/cube_column_horizontal",
                                                                      "textures": {"end": f"{SK}:block/{end}", "side": f"{SK}:block/{side}"}})
        write_json(os.path.join(states, name + ".json"), {"variants": {
            "axis=y": {"model": f"{SK}:block/{name}"},
            "axis=z": {"model": f"{SK}:block/{name}_horizontal", "x": 90},
            "axis=x": {"model": f"{SK}:block/{name}_horizontal", "x": 90, "y": 90}}})

    # stone
    for name in ("scorched_stone", "scorched_stone_bricks"):
        write_json(os.path.join(models, name + ".json"), cube_all(name))
        write_json(os.path.join(states, name + ".json"), {"variants": {"": [{"model": f"{SK}:block/{name}", "y": y} for y in (0, 90, 180, 270)]}})

    # decals: two textures x four turns
    write_json(os.path.join(models, "soot_stain.json"), flat_decal_model("soot_stain"))
    write_json(os.path.join(models, "soot_stain_b.json"), flat_decal_model("soot_stain_b"))
    write_json(os.path.join(models, "blood_stain.json"), flat_decal_model("blood_stain"))
    write_json(os.path.join(models, "blood_stain_b.json"), flat_decal_model("blood_stain_b"))
    for name in ("soot_stain", "blood_stain"):
        write_json(os.path.join(states, name + ".json"), {"variants": {"": [
            {"model": f"{SK}:block/{name}{sfx}", "y": y} for sfx in ("", "_b") for y in (0, 90, 180, 270)]}})

    # timber debris: loose planks and a beam lying on the ground, two arrangements
    a = [(1, 2, 15, 5, 0, 2, 0), (3, 7, 14, 10, 0, 2, 20), (2, 11, 9, 14, 0, 2, -15), (5, 4, 11, 7, 2, 2, 35)]
    b = [(0, 5, 16, 8, 0, 3, 8), (6, 9, 15, 12, 0, 2, -25), (2, 1, 8, 4, 0, 2, 12), (7, 13, 13, 15, 0, 2, 0)]
    write_json(os.path.join(models, "timber_debris_a.json"), debris_model("charred_planks", a))
    write_json(os.path.join(models, "timber_debris_b.json"), debris_model("charred_planks", b))
    write_json(os.path.join(states, "timber_debris.json"), {"variants": {"": [
        {"model": f"{SK}:block/timber_debris_{s}", "y": y} for s in ("a", "b") for y in (0, 90, 180, 270)]}})
    # splintered wreckage: a taller heap
    c = [(1, 1, 15, 6, 0, 3, 5), (2, 8, 15, 13, 0, 3, -8), (4, 3, 12, 8, 3, 3, 25), (3, 9, 13, 12, 3, 2, -20), (6, 5, 10, 11, 5, 2, 40)]
    write_json(os.path.join(models, "splintered_wreckage.json"), debris_model("charred_planks", c))
    write_json(os.path.join(states, "splintered_wreckage.json"), {"variants": {"": [
        {"model": f"{SK}:block/splintered_wreckage", "y": y} for y in (0, 90, 180, 270)]}})

    # stuck arrows: three shafts leaning out of the ground at different angles (crossed planes)
    arrows = []
    for (x, z, ax, ang) in ((4, 5, "x", 18), (10, 9, "z", -22), (7, 12, "x", -12)):
        arrows.append({"from": [x - 1, 0, z], "to": [x + 1, 11, z], "shade": False,
                       "rotation": {"origin": [x, 0, z], "axis": ax, "angle": ang, "rescale": False},
                       "faces": {"north": {"uv": [x - 1, 0, x + 1, 16], "texture": "#a"}, "south": {"uv": [x - 1, 0, x + 1, 16], "texture": "#a"}}})
        arrows.append({"from": [x, 0, z - 1], "to": [x, 11, z + 1], "shade": False,
                       "rotation": {"origin": [x, 0, z], "axis": ax, "angle": ang, "rescale": False},
                       "faces": {"east": {"uv": [x - 1, 0, x + 1, 16], "texture": "#a"}, "west": {"uv": [x - 1, 0, x + 1, 16], "texture": "#a"}}})
    write_json(os.path.join(models, "stuck_arrows.json"), {"textures": {"particle": f"{SK}:block/stuck_arrows", "a": f"{SK}:block/stuck_arrows"},
                                                           "elements": arrows})
    write_json(os.path.join(states, "stuck_arrows.json"), {"variants": {"": [
        {"model": f"{SK}:block/stuck_arrows", "y": y} for y in (0, 90, 180, 270)]}})

    # items and loot (everything but fire drops itself)
    for name in ["charred_planks", "charred_planks_stairs", "charred_planks_slab", "charred_log", "charred_beam", "scorched_stone",
                 "scorched_stone_bricks", "soot_stain", "blood_stain", "timber_debris", "splintered_wreckage", "stuck_arrows"]:
        write_json(os.path.join(items, name + ".json"), item_model(name))
        if name == "charred_planks_slab":
            slab = loot_self(name)
            slab["pools"][0]["entries"][0]["functions"] = [{"function": "minecraft:set_count", "conditions": [
                {"condition": "minecraft:block_state_property", "block": f"{SK}:{name}", "properties": {"type": "double"}}], "count": 2.0,
                "add": False}, {"function": "minecraft:explosion_decay"}]
            write_json(os.path.join(loot, name + ".json"), slab)
        else:
            write_json(os.path.join(loot, name + ".json"), loot_self(name))

    # item model of the decals must not use a flat 3D plane at y 0.04: show the texture as a flat icon
    for name in ("soot_stain", "blood_stain"):
        write_json(os.path.join(items, name + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{SK}:block/{name}"}})

    write_json(LANG, LANG_ENTRIES)
    print("fire: textures, models, blockstates, loot tables and lang written")


if __name__ == "__main__":
    main()
