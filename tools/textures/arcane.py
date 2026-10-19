#!/usr/bin/env python3
"""Pixel-art textures for the arcane sub-module (soul gems, alchemy ingredients & plants, stations, effect icons).

Writes PNGs into mod/src/main/resources/assets/nirncraft/textures/{item,block,mob_effect}/.
Run from anywhere:  python3 tools/textures/arcane.py
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures")


def out(kind, name, img):
    d = os.path.join(TEX, kind)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name + ".png"))


def new(size=16):
    return Image.new("RGBA", (size, size), (0, 0, 0, 0))


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), c)


def blend(img, x, y, c):
    """Alpha-composite one pixel."""
    if not (0 <= x < img.width and 0 <= y < img.height):
        return
    base = img.getpixel((x, y))
    a = c[3] / 255.0
    if base[3] == 0:
        img.putpixel((x, y), c)
        return
    r = tuple(int(base[i] * (1 - a) + c[i] * a) for i in range(3))
    img.putpixel((x, y), r + (max(base[3], c[3]),))


def outline(img, color):
    """Adds a 1px outline around opaque pixels."""
    w, h = img.size
    src = img.copy()
    for y in range(h):
        for x in range(w):
            if src.getpixel((x, y))[3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and src.getpixel((nx, ny))[3] > 40:
                    img.putpixel((x, y), color)
                    break


def in_poly(x, y, pts):
    inside = False
    n = len(pts)
    j = n - 1
    for i in range(n):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi + 1e-9) + xi:
            inside = not inside
        j = i
    return inside


# ============================================================================ soul gems

GEM_SIZES = {"petty": (6, 8), "lesser": (8, 10), "common": (9, 12), "greater": (11, 13), "grand": (13, 15), "black": (13, 15)}


def gem_palette(kind, filled):
    if kind == "black":
        if filled:
            return dict(rim=hexc("1a0614"), dark=hexc("4a0f3a"), mid=hexc("8a1f6a"), light=hexc("d0409a"), hi=hexc("ffb0e0"), core=hexc("ff5ac0"), alpha=255)
        return dict(rim=hexc("0c0610"), dark=hexc("1e1426"), mid=hexc("33243f"), light=hexc("54426a"), hi=hexc("9c8cb4"), core=None, alpha=245)
    if filled:
        return dict(rim=hexc("3a1a6a"), dark=hexc("6a3ab8"), mid=hexc("a070f0"), light=hexc("d4b4ff"), hi=hexc("ffffff"), core=hexc("f0e0ff"), alpha=255)
    return dict(rim=hexc("4a3a6a", 230), dark=hexc("7a6a9a", 150), mid=hexc("a898c8", 140), light=hexc("d8d0ee", 170), hi=hexc("ffffff", 230), core=None, alpha=150)


def draw_gem(img, cx, cy, w, h, pal, filled):
    left, right = cx - w / 2.0, cx + w / 2.0
    top, bot = cy - h / 2.0, cy + h / 2.0
    shoulder = h * 0.32
    pts = [(cx, top), (right, top + shoulder), (right, bot - shoulder * 0.9), (cx, bot), (left, bot - shoulder * 0.9), (left, top + shoulder)]
    for y in range(img.height):
        for x in range(img.width):
            px, py = x + 0.5, y + 0.5
            if not in_poly(px, py, pts):
                continue
            # facets: left light, centre mid, right dark; upper crown lighter
            rel = (px - cx) / (w / 2.0)
            if rel < -0.35:
                c = pal["light"]
            elif rel > 0.35:
                c = pal["dark"]
            else:
                c = pal["mid"]
            if py < top + shoulder:
                c = mix(c, pal["hi"], 0.35)
            if py > bot - shoulder * 0.9:
                c = shade(c, 0.8)
            a = pal["alpha"] if c[3] == 255 else c[3]
            if filled and pal["core"] is not None:
                d = math.hypot((px - cx) / (w / 2.0), (py - cy) / (h / 2.0))
                c = mix(c, pal["core"], max(0.0, 0.85 - d) * 0.9)
                a = 255
            img.putpixel((x, y), c[:3] + (a,))
    outline(img, pal["rim"])
    # sparkle highlight
    hx, hy = int(cx - w * 0.22), int(top + shoulder * 0.9)
    put(img, hx, hy, pal["hi"])
    if w >= 9:
        put(img, hx, hy + 1, mix(pal["hi"], pal["light"], 0.5))
        put(img, hx + 1, hy, mix(pal["hi"], pal["light"], 0.5))


def sparkles(img, color, spots):
    for (x, y, big) in spots:
        if img.getpixel((x, y))[3] > 0:
            continue
        put(img, x, y, color)
        if big:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and img.getpixel((nx, ny))[3] == 0:
                    put(img, nx, ny, color[:3] + (130,))


def soul_gems():
    for kind, (w, h) in GEM_SIZES.items():
        for filled in (False, True):
            img = new()
            pal = gem_palette(kind, filled)
            draw_gem(img, 8, 8.5, w, h, pal, filled)
            if filled:
                glow = hexc("ff7ad0") if kind == "black" else hexc("e8d8ff")
                sparkles(img, glow, [(2, 3, True), (13, 4, False), (12, 13, True), (3, 12, False)])
            out("item", "soul_gem_" + kind + ("_filled" if filled else ""), img)


def azuras_star():
    for filled in (False, True):
        img = new()
        cx, cy = 7.5, 7.5
        gold_d, gold, gold_l = hexc("7a5414"), hexc("c8952c"), hexc("f4d77a")
        # eight-pointed star frame
        for y in range(16):
            for x in range(16):
                dx, dy = x - cx, y - cy
                r = math.hypot(dx, dy)
                ang = math.atan2(dy, dx)
                spike = 4.2 + 3.4 * max(0.0, math.cos(ang * 4)) ** 6
                if 3.3 < r < spike:
                    c = gold_l if dx + dy < -2 else gold if dx + dy < 3 else gold_d
                    img.putpixel((x, y), c)
        # central gem
        core = new()
        pal = gem_palette("grand", filled)
        pal = dict(pal)
        if filled:
            pal.update(dark=hexc("2a6ab8"), mid=hexc("60b0f8"), light=hexc("b8e8ff"), core=hexc("f0fbff"), rim=hexc("143060"))
        else:
            pal.update(dark=hexc("4a6a8a", 170), mid=hexc("8ab0c8", 160), light=hexc("c8e0ee", 180), rim=hexc("2a3a50", 230))
        draw_gem(core, 8, 8, 6, 7, pal, filled)
        for y in range(16):
            for x in range(16):
                p = core.getpixel((x, y))
                if p[3] > 0:
                    img.putpixel((x - 1 if x > 0 else x, y - 1 if y > 0 else y), p)
        outline(img, hexc("3a2408"))
        if filled:
            sparkles(img, hexc("dff4ff"), [(1, 2, True), (14, 13, True), (14, 2, False)])
        out("item", "azuras_star" + ("_filled" if filled else ""), img)


# ============================================================================ ingredients

def heap(img, base, light, dark, speck, seed, glitter=None):
    rnd = random.Random(seed)
    for y in range(16):
        for x in range(16):
            # mound: ellipse bottom half
            dx = (x + 0.5 - 8) / 6.5
            dy = (y + 0.5 - 13.5) / 7.0
            if dx * dx + dy * dy <= 1.0 and y <= 13:
                t = rnd.random()
                c = base
                if x + (13 - y) * 0.6 < 9:
                    c = light if t < 0.55 else base
                elif x > 10:
                    c = dark if t < 0.6 else base
                elif t < 0.2:
                    c = dark
                img.putpixel((x, y), c)
    for _ in range(9):
        x, y = rnd.randint(3, 12), rnd.randint(8, 13)
        if img.getpixel((x, y))[3] > 0:
            img.putpixel((x, y), speck)
    if glitter:
        for _ in range(4):
            x, y = rnd.randint(4, 11), rnd.randint(7, 12)
            if img.getpixel((x, y))[3] > 0:
                img.putpixel((x, y), glitter)
    # bottom shadow line
    for x in range(3, 13):
        if img.getpixel((x, 13))[3] > 0:
            img.putpixel((x, 13), shade(dark, 0.8))
    outline(img, shade(dark, 0.45)[:3] + (255,))


def ingredients():
    img = new()
    heap(img, hexc("dcdcd4"), hexc("f8f8f2"), hexc("a8a8a0"), hexc("ffffff"), 1, hexc("c8d8ff"))
    out("item", "salt_pile", img)

    img = new()
    heap(img, hexc("3a2050"), hexc("6a3a90"), hexc("1c0c2c"), hexc("b070ff"), 2, hexc("e8c8ff"))
    out("item", "void_salts", img)

    img = new()
    heap(img, hexc("c84818"), hexc("f08a2a"), hexc("7a1c08"), hexc("ffd040"), 3, hexc("fff4a0"))
    out("item", "fire_salts", img)

    img = new()
    heap(img, hexc("9cc8e8"), hexc("dff2ff"), hexc("5a8ab0"), hexc("ffffff"), 4, hexc("e8fbff"))
    out("item", "frost_salts", img)

    img = new()
    heap(img, hexc("6a6466"), hexc("948c90"), hexc("3a3436"), hexc("8a1010"), 5, hexc("c02020"))
    out("item", "vampire_dust", img)

    # bear claws: three curved, tapering ivory claws on a fur tuft
    img = new()
    fur, fur_l = hexc("4a3020"), hexc("6e4c34")
    for y in range(11, 16):
        for x in range(2, 15):
            if (x - 8.0) ** 2 / 36 + (y - 14.0) ** 2 / 5 <= 1:
                img.putpixel((x, y), fur_l if (x * 3 + y) % 4 == 0 else fur)
    ivory, ivory_l, ivory_d, tip = hexc("e8dcb8"), hexc("fff6dc"), hexc("b09c70"), hexc("5a4a38")
    for bx in (4.5, 8.0, 11.5):
        samples = []
        for i in range(41):
            t = i / 40.0
            # curve from the base upwards, hooking to the right
            x = bx - 1.0 + 3.2 * t * t
            y = 12.5 - 10.0 * t + 1.5 * t * t * t
            samples.append((x, y, 1.6 * (1 - t) + 0.35))
        for y in range(16):
            for x in range(16):
                for (cx, cy, r) in samples:
                    d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                    if d <= r:
                        t = (12.5 - cy) / 10.0
                        c = ivory_l if x + 0.5 < cx - 0.3 else ivory_d if x + 0.5 > cx + 0.4 else ivory
                        if t > 0.8:
                            c = tip
                        img.putpixel((x, y), c)
                        break
    outline(img, hexc("1c120a"))
    out("item", "bear_claws", img)

    # hawk feather: curved vane along a diagonal shaft, barred brown with a pale tip
    img = new()
    ax0, ay0, ax1, ay1 = 2.5, 14.0, 13.5, 2.0
    L = math.hypot(ax1 - ax0, ay1 - ay0)
    ux, uy = (ax1 - ax0) / L, (ay1 - ay0) / L
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5 - ax0, y + 0.5 - ay0
            t = (px * ux + py * uy) / L
            d = px * -uy + py * ux
            if t < 0 or t > 1:
                continue
            half = 0 if t < 0.15 else 2.7 * math.sin(math.pi * min(1.0, (t - 0.15) / 0.85)) ** 0.6
            if abs(d) <= 0.55:
                img.putpixel((x, y), hexc("f0e6d0") if t < 0.9 else hexc("d8ccb0"))
            elif abs(d) <= half:
                band = int(t * 9) % 2 == 0
                c = hexc("7a4a22") if band else hexc("c8a070")
                if t > 0.82:
                    c = hexc("f4ece0")
                if d > 0:
                    c = shade(c, 0.82)
                img.putpixel((x, y), c)
    outline(img, hexc("2a1a0a"))
    out("item", "hawk_feather", img)


# ============================================================================ plants (3D botanical models & grounded block textures)

import json

ASSETS_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft")
DATA_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "data")


def ramp_rgba(pal, t):
    t = max(0.0, min(1.0, t))
    if len(pal) == 1:
        return pal[0]
    x = t * (len(pal) - 1)
    i = min(int(x), len(pal) - 2)
    f = x - i
    return mix(pal[i], pal[i + 1], f)


def pal_list(*hexes):
    return [hexc(h) for h in hexes]


def stem(img, x0, y0, x1, y1, c):
    steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    for i in range(steps):
        t = i / max(1, steps - 1)
        put(img, int(round(x0 + (x1 - x0) * t)), int(round(y0 + (y1 - y0) * t)), c)


def stem_grad(img, x0, y0, x1, y1, pal, thick=False):
    steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    for i in range(steps):
        t = i / max(1, steps - 1)
        px = int(round(x0 + (x1 - x0) * t))
        py = int(round(y0 + (y1 - y0) * t))
        put(img, px, py, ramp_rgba(pal, 0.25 + 0.65 * t))
        if thick and px + 1 < 15:
            put(img, px + 1, py, ramp_rgba(pal, 0.1 + 0.45 * t))


def disc(img, cx, cy, rx, ry, pal, rnd=None):
    for y in range(max(0, int(cy - ry - 1)), min(16, int(cy + ry + 2))):
        for x in range(max(0, int(cx - rx - 1)), min(16, int(cx + rx + 2))):
            dx = (x + 0.5 - cx) / max(0.5, rx)
            dy = (y + 0.5 - cy) / max(0.5, ry)
            d = dx * dx + dy * dy
            if d <= 1.0:
                n = (rnd.random() - 0.5) * 0.14 if rnd else 0.0
                t = 0.62 - dx * 0.24 - dy * 0.28 - d * 0.18 + n
                put(img, x, y, ramp_rgba(pal, t))


def leaf_blade(img, x0, y0, x1, y1, width, pal):
    steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    dx_tot, dy_tot = x1 - x0, y1 - y0
    L = max(1.0, math.hypot(dx_tot, dy_tot))
    nx, ny = -dy_tot / L, dx_tot / L
    for i in range(steps + 1):
        t = i / max(1, steps)
        cx = x0 + dx_tot * t
        cy = y0 + dy_tot * t
        w = width * math.sin(math.pi * t)
        for s in range(int(-w * 2) - 1, int(w * 2) + 2):
            off = s * 0.5
            if abs(off) <= w + 0.15:
                px, py = int(round(cx + nx * off)), int(round(cy + ny * off))
                shade_t = 0.3 + 0.55 * t + (0.18 if abs(off) < 0.35 else -0.15 * (abs(off) / max(0.5, w)))
                put(img, px, py, ramp_rgba(pal, shade_t))


def ground_rosette(img, pal, width=5, ceiling=False, seed=1):
    """Anchors rows y=13..15 (and y=0..2 if ceiling=True) with a natural basal root/foliage crown."""
    rnd = random.Random(seed)
    for x in range(max(1, 8 - width), min(15, 8 + width + 1)):
        dist = abs(x - 7.5) / max(1.0, width)
        put(img, x, 15, ramp_rgba(pal, 0.12 + 0.28 * (1.0 - dist)))
        if dist < 0.88:
            put(img, x, 14, ramp_rgba(pal, 0.28 + 0.38 * (1.0 - dist)))
        if dist < 0.65 and rnd.random() < 0.85:
            put(img, x, 13, ramp_rgba(pal, 0.45 + 0.35 * (1.0 - dist)))
    # Basal leaf tips curving outward from the crown
    leaf_blade(img, 7, 15, max(2, 7 - width), 13, 1.1, pal)
    leaf_blade(img, 8, 15, min(13, 8 + width), 13, 1.1, pal)
    if ceiling:
        for x in range(max(1, 8 - width), min(15, 8 + width + 1)):
            dist = abs(x - 7.5) / max(1.0, width)
            put(img, x, 0, ramp_rgba(pal, 0.15 + 0.25 * (1.0 - dist)))
            if dist < 0.85:
                put(img, x, 1, ramp_rgba(pal, 0.30 + 0.35 * (1.0 - dist)))
            if dist < 0.60:
                put(img, x, 2, ramp_rgba(pal, 0.45 + 0.30 * (1.0 - dist)))


def draw_star_flower(img, cx, cy, r, petal_pal, center_pal, petals=5):
    for a_idx in range(petals):
        ang = a_idx * (2 * math.pi / petals) - math.pi / 2
        px = cx + math.cos(ang) * (r * 0.72)
        py = cy + math.sin(ang) * (r * 0.72)
        disc(img, px, py, r * 0.56, r * 0.56, petal_pal)
    disc(img, cx, cy, max(1.0, r * 0.38), max(1.0, r * 0.38), center_pal)


def botanical_star_bush(seed, stem_pal, petal_pal, center_pal, harvested=False):
    img = new()
    rnd = random.Random(seed)
    ground_rosette(img, stem_pal, width=5, seed=seed)
    leaf_blade(img, 7, 15, 3, 11, 1.3, stem_pal)
    leaf_blade(img, 8, 15, 12, 11, 1.3, stem_pal)
    if harvested:
        for sx, sy in ((6, 10), (8, 9), (10, 11)):
            stem_grad(img, 8, 15, sx, sy, stem_pal, thick=True)
            put(img, sx, sy, ramp_rgba(stem_pal, 0.85))
        return img
    branches = [(8, 15, 8, 4), (7, 14, 4, 6), (9, 14, 12, 5), (8, 12, 5, 9), (8, 12, 11, 9)]
    for x0, y0, x1, y1 in branches:
        stem_grad(img, x0, y0, x1, y1, stem_pal, thick=(x0 == 8 and y0 == 15))
    leaf_blade(img, 7, 11, 2, 8, 1.2, stem_pal)
    leaf_blade(img, 9, 11, 13, 8, 1.2, stem_pal)
    for fx, fy, r in ((8, 4, 2.5), (4, 6, 2.2), (12, 5.5, 2.2), (6, 9, 1.8)):
        draw_star_flower(img, fx + (rnd.random() - 0.5) * 0.4, fy, r, petal_pal, center_pal, petals=5)
    return img


def botanical_bell_stalk(seed, stem_pal, bell_pal, rim_pal, harvested=False):
    img = new()
    ground_rosette(img, stem_pal, width=5, seed=seed)
    leaf_blade(img, 7, 15, 2, 11, 1.4, stem_pal)
    leaf_blade(img, 8, 15, 13, 11, 1.4, stem_pal)
    if harvested:
        stem_grad(img, 7, 15, 6, 9, stem_pal, thick=True)
        stem_grad(img, 9, 15, 10, 10, stem_pal, thick=True)
        return img
    # Arching main stalk and secondary stalk
    pts = [(7, 15), (6, 11), (6, 7), (7, 3), (10, 2)]
    for i in range(len(pts) - 1):
        stem_grad(img, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], stem_pal, thick=(i < 2))
    stem_grad(img, 8, 14, 12, 7, stem_pal)
    # Hanging bell blossoms
    for bx, by, side in ((10, 3, 1), (6, 5, -1), (9, 7, 1), (5, 9, -1), (12, 8, 1)):
        put(img, bx, by, ramp_rgba(stem_pal, 0.7))
        cx = bx + side
        disc(img, cx, by + 2.0, 1.7, 2.0, bell_pal)
        for dx in (-1, 0, 1):
            put(img, cx + dx, by + 3, ramp_rgba(rim_pal, 0.6 + 0.2 * abs(dx)))
        put(img, cx, by + 4, ramp_rgba(rim_pal, 0.95))
    return img


def botanical_rosette(seed, frond_pal, glow_pal, root_pal, harvested=False):
    img = new()
    rnd = random.Random(seed)
    # Thick root bulb at y=12..15
    ground_rosette(img, root_pal, width=5, seed=seed)
    disc(img, 7.5, 13.8, 3.8, 2.0, root_pal, rnd)
    if harvested:
        for tx, ty in ((4, 11), (6, 10), (8, 9), (10, 10), (12, 11)):
            leaf_blade(img, 7.5, 14, tx, ty, 1.0, frond_pal)
        return img
    fronds = [
        (7.5, 14, 1.5, 9, 1.3),
        (7.5, 14, 14.0, 9, 1.3),
        (7.5, 14, 2.5, 4, 1.5),
        (7.5, 14, 13.0, 4, 1.5),
        (7.5, 14, 5.5, 2, 1.6),
        (7.5, 14, 10.5, 2, 1.6),
        (7.5, 14, 8.0, 1, 1.7),
    ]
    for x0, y0, x1, y1, w in fronds:
        leaf_blade(img, x0, y0, x1, y1, w, frond_pal)
        stem_grad(img, x0, y0 - 1, x1, y1, glow_pal)
        put(img, int(round(x1)), int(round(y1)), ramp_rgba(glow_pal, 0.95))
    return img


def botanical_spike_herb(seed, stem_pal, bloom_pal, tip_pal, harvested=False):
    img = new()
    rnd = random.Random(seed)
    ground_rosette(img, stem_pal, width=5, seed=seed)
    leaf_blade(img, 6, 15, 2, 11, 1.2, stem_pal)
    leaf_blade(img, 9, 15, 13, 11, 1.2, stem_pal)
    if harvested:
        for sx, sy in ((5, 10), (8, 9), (11, 10)):
            stem_grad(img, sx, 15, sx, sy, stem_pal, thick=True)
        return img
    spikes = [(4.5, 15, 3.5, 4), (8.0, 15, 8.0, 1), (11.5, 15, 12.5, 3)]
    for x0, y0, x1, top in spikes:
        stem_grad(img, x0, y0, x1, top + 2, stem_pal, thick=(x0 == 8.0))
        for y in range(int(top), int(top) + 7):
            prog = (y - top) / 6.0
            rx = 1.2 + 0.8 * math.sin(math.pi * prog)
            cx = x1 + (x0 - x1) * (prog * 0.35)
            for dx in range(int(-rx), int(rx) + 1):
                px = int(round(cx + dx))
                c = ramp_rgba(tip_pal if prog < 0.35 else bloom_pal, 0.45 + 0.45 * (1.0 - abs(dx) / (rx + 0.5)) + (rnd.random() - 0.5) * 0.15)
                put(img, px, y, c)
    return img


def botanical_berry_shrub(seed, leaf_pal, berry_pal, snow_pal=None, harvested=False):
    img = new()
    rnd = random.Random(seed)
    ground_rosette(img, leaf_pal, width=6, seed=seed)
    if harvested:
        for cx, cy, rx, ry in ((5, 12.5, 3.2, 2.2), (10.5, 12.5, 3.2, 2.2), (8, 11.5, 3.8, 2.5)):
            disc(img, cx, cy, rx, ry, leaf_pal, rnd)
        return img
    # Woody stems at base
    stem_grad(img, 7, 15, 5, 7, pal_list("3a2618", "5c3e28", "785438"), thick=True)
    stem_grad(img, 9, 15, 11, 7, pal_list("3a2618", "5c3e28", "785438"), thick=True)
    # Lush overlapping foliage lobes
    lobes = [(4.5, 11.5, 3.4, 2.8), (11.5, 11.5, 3.4, 2.8), (8.0, 10.5, 4.5, 3.4),
             (5.0, 7.0, 3.5, 3.0), (11.0, 7.0, 3.5, 3.0), (8.0, 5.5, 4.0, 3.2)]
    for cx, cy, rx, ry in lobes:
        disc(img, cx, cy, rx, ry, leaf_pal, rnd)
    if snow_pal:
        for y in range(2, 13):
            for x in range(1, 15):
                if img.getpixel((x, y))[3] > 0 and img.getpixel((x, max(0, y - 1)))[3] == 0:
                    put(img, x, y, ramp_rgba(snow_pal, 0.6 + 0.4 * rnd.random()))
    berries_pts = [(5, 6), (9, 5), (11, 7), (4, 9), (8, 8), (12, 10), (6, 11), (10, 11)]
    for bx, by in berries_pts:
        disc(img, bx, by, 1.4, 1.4, berry_pal)
        put(img, bx - 1, by - 1, ramp_rgba(berry_pal, 0.98))
    return img


def botanical_mushroom_cluster(seed, cap_pal, stalk_pal, gill_pal, spot_pal=None, flat=False, harvested=False):
    img = new()
    rnd = random.Random(seed)
    ground_rosette(img, gill_pal, width=6, seed=seed)
    if harvested:
        for sx, sy, r in ((4.5, 13.5, 1.3), (8.0, 13.0, 1.7), (11.5, 13.5, 1.3)):
            disc(img, sx, sy, r, 1.8, stalk_pal, rnd)
            disc(img, sx, sy - 1.2, r * 0.9, 0.8, gill_pal, rnd)
        return img
    # Three mushrooms: left small, center tall, right medium
    specs = [
        (4.2, 15, 4.0, 9.5, 1.1, 3.0, 1.8 if flat else 2.4),
        (11.8, 15, 12.0, 8.5, 1.2, 3.3, 1.9 if flat else 2.6),
        (8.0, 15, 7.8, 5.5, 1.7, 5.2, 2.2 if flat else 3.4),
    ]
    for x0, y0, cx, cy, sw, crx, cry in specs:
        # Stipe from y=15 up to cap underside
        for y in range(int(cy + 1), 16):
            prog = (15 - y) / max(1.0, 15 - cy)
            w = sw * (1.25 - 0.35 * prog)
            st_x = x0 + (cx - x0) * prog
            for dx in range(int(-w - 1), int(w + 2)):
                if abs(dx) <= w:
                    put(img, int(round(st_x + dx)), y, ramp_rgba(stalk_pal, 0.55 - 0.25 * (dx / max(0.5, w))))
        # Gill underside
        disc(img, cx, cy + cry * 0.45, crx * 0.88, max(1.0, cry * 0.42), gill_pal, rnd)
        # Upper cap
        disc(img, cx, cy, crx, cry, cap_pal, rnd)
        if spot_pal:
            for _ in range(4):
                sx = int(round(cx + (rnd.random() - 0.5) * crx * 1.3))
                sy = int(round(cy + (rnd.random() - 0.6) * cry * 1.1))
                if 0 <= sx < 16 and 0 <= sy < 16 and img.getpixel((sx, sy))[3] > 0:
                    put(img, sx, sy, ramp_rgba(spot_pal, 0.75 + 0.25 * rnd.random()))
    return img


def botanical_creeping_roots(seed, root_pal, node_pal, leaf_pal=None, harvested=False):
    img = new()
    rnd = random.Random(seed)
    ground_rosette(img, root_pal, width=6, seed=seed)
    if harvested:
        for x0, x1, y1 in ((3, 6, 11), (8, 8, 10), (13, 10, 11)):
            stem_grad(img, x0, 15, x1, y1, root_pal, thick=True)
        return img
    # Gnarled root arches anchored in y=13..15
    arches = [
        [(3, 15), (4, 10), (7, 6), (8, 3)],
        [(8, 15), (8, 9), (11, 5), (13, 4)],
        [(13, 15), (11, 11), (6, 8), (3, 5)],
        [(5, 15), (9, 12), (12, 8)],
    ]
    for pts in arches:
        for i in range(len(pts) - 1):
            stem_grad(img, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], root_pal, thick=True)
    if leaf_pal:
        for lx, ly, tx, ty in ((4, 10, 2, 7), (11, 11, 14, 8), (8, 6, 5, 3), (11, 5, 14, 2)):
            leaf_blade(img, lx, ly, tx, ty, 1.2, leaf_pal)
    for nx, ny, r in ((7.5, 8.5, 2.2), (5.0, 11.5, 1.7), (10.5, 10.5, 1.7), (8.0, 5.0, 1.6)):
        disc(img, nx, ny, r, r * 0.9, node_pal, rnd)
        put(img, int(nx), int(ny - 0.5), ramp_rgba(node_pal, 0.96))
    return img


def botanical_hanging_curtain(seed, moss_pal, accent_pal=None, harvested=False):
    img = new()
    rnd = random.Random(seed)
    # Grounded at BOTH y=0..2 (ceiling) and y=13..15 (floor)
    ground_rosette(img, moss_pal, width=6, ceiling=True, seed=seed)
    if harvested:
        for sx in (3, 6, 8, 10, 12):
            stem_grad(img, sx, 0, sx, 4, moss_pal, thick=True)
            stem_grad(img, sx, 15, sx, 11, moss_pal, thick=True)
        return img
    strands = [2, 4, 6, 8, 10, 12, 13]
    for i, sx in enumerate(strands):
        for y in range(0, 16):
            wobble = int(round(math.sin(y * 0.45 + i * 1.1) * 0.85))
            px = max(1, min(14, sx + wobble))
            t = 0.25 + 0.55 * (1.0 - abs(y - 7.5) / 8.0) + (rnd.random() - 0.5) * 0.2
            put(img, px, y, ramp_rgba(moss_pal, t))
            if y % 3 != 2 and px + 1 < 15:
                put(img, px + 1, y, ramp_rgba(moss_pal, max(0.1, t - 0.18)))
    if accent_pal:
        for ax, ay in ((4, 5), (8, 4), (11, 6), (6, 9), (9, 11), (5, 12), (11, 10)):
            disc(img, ax, ay, 1.1, 1.1, accent_pal)
    return img


def botanical_forage_node(seed, kind, base_pal, main_pal, accent_pal, harvested=False):
    img = new()
    rnd = random.Random(seed)
    ground_rosette(img, base_pal, width=6, seed=seed)
    # Thick grounded nest / rock / mycelium mound at y=11..15
    disc(img, 7.8, 13.5, 5.8, 2.5, base_pal, rnd)
    if harvested:
        disc(img, 7.8, 12.8, 4.2, 1.6, base_pal, rnd)
        return img
    if kind == "nest":
        # Woven twig nest cup + 2-3 speckled eggs nestled inside
        disc(img, 7.8, 12.0, 5.5, 2.6, base_pal, rnd)
        for ex, ey, erx, ery in ((6.2, 9.2, 2.2, 2.8), (9.6, 9.4, 2.1, 2.7), (7.9, 8.4, 2.3, 3.0)):
            disc(img, ex, ey, erx, ery, main_pal, rnd)
            for _ in range(3):
                sx = int(round(ex + (rnd.random() - 0.5) * 2.0))
                sy = int(round(ey + (rnd.random() - 0.5) * 2.4))
                put(img, sx, sy, ramp_rgba(accent_pal, 0.4 + 0.5 * rnd.random()))
    elif kind == "shell":
        # Open coastal bivalve shell / barnacle reef on stone bed
        disc(img, 7.8, 11.2, 5.4, 3.2, base_pal, rnd)
        disc(img, 7.8, 9.2, 4.6, 2.8, accent_pal, rnd)
        disc(img, 7.8, 9.5, 2.6, 2.4, main_pal, rnd)
        put(img, 7, 8, hexc("ffffff"))
    elif kind == "pod":
        # Grounded biological/fungal/resin pod cluster
        for px, py, rx, ry in ((5.2, 10.5, 2.4, 3.5), (10.5, 10.8, 2.3, 3.2), (7.8, 8.8, 3.0, 4.5)):
            disc(img, px, py, rx, ry, main_pal, rnd)
            stem_grad(img, px, py - ry + 1, px, py + ry - 1, accent_pal)
    else:
        # Grounded crystalline / relic / horn outcrop
        for cx, cy, rx, ry in ((5.0, 10.5, 2.2, 3.8), (10.8, 10.5, 2.2, 3.6), (7.8, 8.5, 2.8, 5.0)):
            disc(img, cx, cy, rx, ry, main_pal, rnd)
            disc(img, cx - 0.5, cy - 1.0, rx * 0.5, ry * 0.5, accent_pal, rnd)
    return img


FLORA_SPECS = {
    # Base 4 wild alchemy plants
    "nightshade": ("star", ("1a3618", "2e5c2a", "48843e", "6ca85c"), ("3e1660", "682898", "9648cc", "c884f4"), ("c89818", "f4d038", "fff498")),
    "deathbell": ("bell", ("243824", "3c5838", "587a52", "789c70"), ("181238", "2e2468", "52429c", "7c6cd0"), ("8c7ce0", "bcb0f8", "e8e0ff")),
    "nirnroot": ("rosette", ("48846c", "6eb498", "98dcc0", "ccf8e4"), ("98f4d8", "c8ffec", "ffffff"), ("2e4e40", "48705e", "689680")),
    "frost_mirriam": ("spike", ("28443c", "3e685c", "5c8c7e", "80b0a2"), ("7868b8", "a494dc", "ccc0f4", "ece8ff"), ("c8dcf8", "e8f4ff", "ffffff")),

    # Regional 14 flora blocks
    "crimson_nirnroot": ("rosette", ("681824", "9c2434", "cc3848", "f46470"), ("ff6874", "ffa8b0", "ffe0e4"), ("3c1018", "5c1a24", "802834")),
    "blisterwort": ("fungi_dome", ("58381c", "84582e", "b07c44", "dcac68"), ("78644c", "a48c6e", "ccb494"), ("442c18", "644428", "88603c"), ("d8a858", "ffe098"), False),
    "fly_amanita": ("fungi_dome", ("781212", "b01c1c", "dc2e28", "f85044"), ("948874", "c4b8a0", "ece2cc"), ("584838", "7c6852", "a48c70"), ("e4dcc8", "ffffff"), False),
    "scaly_pholiota": ("fungi_flat", ("643c14", "945c20", "c48432", "eca84c"), ("58442c", "806444", "a88860"), ("3e2812", "5c3c1c", "7c542c"), ("442810", "683c18"), True),
    "rot_scale_fungus": ("fungi_flat", ("281c30", "443052", "684c7c", "9070a8"), ("48404c", "6c6072", "94889c"), ("223024", "38503c", "547458"), ("78b068", "a8dc94"), True),
    "kresh_fiber": ("spike", ("284828", "3e6c3c", "5c9458", "84bc7c"), ("688c58", "90b47c", "b8d8a4"), ("c4d8ac", "e4f0d0", "f8fff0")),
    "coda_flower": ("star", ("284c34", "3c704c", "589668", "78bc88"), ("982c24", "cc4834", "f07448", "ffa868"), ("d89820", "ffd448", "fff8a8")),
    "hackle_lo": ("rosette", ("244c20", "3c7434", "5ca04e", "88cc70"), ("98dc78", "c4f49c", "e8ffd0"), ("283c20", "405c34", "5c804c")),
    "desert_rose": ("star", ("38543c", "507856", "6c9c74", "90c098"), ("781832", "ac284c", "dc486c", "f8809c"), ("d89028", "ffd058", "fff4a8")),
    "cactus_pulp": ("spike", ("1e4c24", "307036", "48984e", "6cc070"), ("387c3c", "54a458", "78cc7c"), ("d85878", "f888a4", "ffc4d4")),
    "hist_carp": ("roots", ("2c1e12", "48321e", "68482c", "88603c"), ("b85810", "e8881c", "ffbc3c", "fff088"), ("284c24", "407438", "609c54")),
    "bog_beacon": ("fungi_dome", ("983810", "cc5818", "f48828", "ffc448"), ("384824", "546c36", "74944c"), ("283418", "405428", "5c783c"), ("ffe078", "ffffff"), False),
    "luminous_russula": ("fungi_dome", ("14503c", "227c5c", "38b080", "68e4ac"), ("285848", "40806c", "60ac94"), ("183c30", "28604c", "40886c"), ("b8ffe0", "ffffff"), False),
    "snowberries": ("berry_snow", ("183820", "285834", "3c7c4c", "58a068"), ("881218", "c02028", "ec3c44", "ff8088"), ("c8dcf0", "e4f0fc", "ffffff")),

    # MORE_INGREDIENTS 50 flora blocks
    "canis_root": ("roots", ("3c2414", "5c3a22", "845836", "ac784c"), ("684828", "94683c", "c09058", "e0b478"), ("2e5024", "487838", "689c54")),
    "creep_cluster": ("roots", ("381c30", "582c4c", "7c446c", "a46490"), ("782028", "b03438", "dc5448", "ff8870"), ("442838", "683c54", "8c5874")),
    "elves_ear": ("rosette", ("385442", "547c64", "74a488", "9cccb0"), ("b86018", "e48828", "ffb848", "ffe488"), ("2c4034", "446050", "608470")),
    "imp_stool": ("fungi_dome", ("7c2c0c", "b04414", "dc6820", "ff9838"), ("887860", "b4a488", "dcd0b4"), ("4c3824", "705438", "987450"), ("ffc868", "fff0a8"), False),
    "jazbay_grapes": ("berry", ("244420", "386832", "529048", "74b868"), ("2c1238", "4e2064", "783894", "a860c8")),
    "juniper_berries": ("berry", ("183424", "28543a", "3c7854", "589c72"), ("243c5c", "38608c", "5888bc", "88b8ec")),
    "namiras_rot": ("fungi_flat", ("303c14", "4e6020", "748c32", "9cb84c"), ("4c4438", "706454", "988874"), ("382444", "58386c", "7c5498"), ("c8dc68", "f0ff98"), True),
    "white_cap": ("fungi_dome", ("687484", "90a0b4", "bccce0", "eef6ff"), ("585044", "807464", "a89c88"), ("38404c", "546070", "748498"), ("ffffff", "ffffff"), False),
    "glowing_mushroom": ("fungi_dome", ("124460", "1e7098", "34a8d4", "70e4ff"), ("244858", "3c7084", "5c9cb4"), ("143448", "245874", "3c84a8"), ("c8f8ff", "ffffff"), False),
    "bleeding_crown": ("fungi_flat", ("7c1218", "b41e24", "dc383c", "f86868"), ("786450", "a48c74", "ccb49c"), ("4c382c", "705442", "94745c"), ("f0dcd0", "fff4ec"), True),
    "hanging_moss": ("curtain", ("203824", "34583a", "4e7c54", "70a476"), ("84b888", "acd8b0")),
    "swamp_fungal_pod": ("fungi_dome", ("3c541c", "5c802c", "84b040", "b4dc60"), ("382c40", "584464", "7c648c"), ("283814", "405822", "5c7c34"), ("d8f880", "ffffb8"), False),
    "thistle_branch": ("spike", ("244424", "386838", "50904e", "70b86c"), ("582068", "84349c", "b054cc", "dc88f8"), ("e098ff", "f4ccff", "ffffff")),
    "giant_lichen": ("rosette", ("284438", "3e6856", "5c907a", "84bca4"), ("98d0b8", "c4ece0", "e8fff8"), ("20342c", "345044", "4c7060")),
    "mora_tapinella": ("fungi_flat", ("483444", "6c4e66", "94708c", "bc98b4"), ("544434", "7c6850", "a48c70"), ("382834", "543c4e", "74546c"), ("dcc4d8", "f8e8f4"), True),
    "poison_bloom": ("star", ("244428", "38683e", "509058", "70b878"), ("4c1458", "7c248c", "ac40c0", "dc70f0"), ("88c828", "b8f048", "e8ff98")),
    "emperor_parasol_moss": ("curtain", ("2c4430", "446848", "609064", "84b888"), ("a868c8", "dc98f8", "ffd878")),
    "burnt_spriggan_wood": ("roots", ("181210", "2c221e", "44342e", "604a40"), ("b83c10", "ec6c1c", "ffa438", "ffe078"), None),
    "rock_warbler_egg": ("nest", ("382818", "584028", "785c3c", "44642c"), ("786c58", "a89880", "d4c4ac", "f4ead8"), ("385860", "588894", "84b8c8")),
    "pine_thrush_egg": ("nest", ("342416", "523c24", "745838", "385c28"), ("406878", "6094a8", "88c0d4", "bce8f8"), ("483828", "6c543c", "907458")),
    "hawks_egg": ("nest", ("3c2c1c", "5c442c", "806040", "506834"), ("88745c", "b8a084", "e0ccb0", "f8eee0"), ("783020", "a84830", "d06848")),
    "hawk_beak": ("relic", ("38342c", "585246", "7c7464", "486438"), ("584818", "887028", "bc9c40", "e8cc68"), ("2c2418", "483c28", "68583c")),
    "pearl": ("shell", ("28343c", "40505c", "5c7080", "345c48"), ("9890a4", "c4bcd0", "e8e2f4", "ffffff"), ("584c60", "80708c", "a898b8")),
    "small_pearl": ("shell", ("2c3840", "445460", "607484", "38604c"), ("a89888", "d0c0b0", "f0e4d8", "ffffff"), ("645448", "8c7868", "b49c8c")),
    "nordic_barnacle": ("shell", ("242c34", "384450", "506070", "305444"), ("686860", "949488", "c0c0b4", "e8e8dc"), ("384840", "546c60", "749484")),
    "trama_root": ("roots", ("281824", "44283c", "643c58", "885878"), ("543444", "7c4e64", "a46c88", "cc94b0"), None),
    "ashen_grass_pod": ("spike", ("383430", "545048", "746e64", "989084"), ("883814", "bc5820", "ec8430", "ffbc58"), ("ffd068", "ffe898", "ffffff")),
    "ash_creep_cluster": ("roots", ("242220", "3c3836", "58524e", "78706a"), ("983010", "cc501c", "f4802c", "ffbc48"), ("48423e", "68605a", "888078")),
    "ash_hopper_jelly": ("pod", ("343028", "504a3e", "706858", "4c5c30"), ("485818", "708828", "9cbc3c", "cce864"), ("d8f878", "f0ffa8", "ffffff")),
    "boar_tusk": ("relic", ("382c20", "584432", "786048", "446030"), ("847450", "b09c70", "d8c498", "f8ecd0"), ("4c3c28", "70583c", "947854")),
    "spawn_ash": ("relic", ("24201e", "3c3634", "58504c", "746c66"), ("383230", "544c48", "746a64", "988e88"), ("983818", "cc5824", "f48438")),
    "comberry": ("berry", ("284820", "3e6c32", "589448", "78bc64"), ("681020", "9c1c34", "cc344c", "f86878")),
    "marshmerrow": ("spike", ("2c441c", "44682c", "609040", "84b85c"), ("687c38", "90a850", "b8d070", "dce898"), ("e8f4b0", "f8ffd0", "ffffff")),
    "saltrice": ("spike", ("384824", "546c36", "74944c", "98bc68"), ("8c7c50", "b8a470", "dcd098", "f8f0c8"), ("fff4d0", "ffffec", "ffffff")),
    "hypha_facia": ("fungi_flat", ("442818", "684028", "905c3c", "bc8058"), ("483828", "6c543c", "947458"), ("342014", "503420", "704c30"), ("d8b080", "f4d8b0"), True),
    "bittergreen_petals": ("star", ("1c441c", "2e682e", "449044", "64b864"), ("a82418", "dc4424", "f87038", "ffa860"), ("c8d838", "f0f868", "ffffb0")),
    "gold_kanet": ("star", ("204420", "346832", "4c9048", "6cb864"), ("785008", "ac7810", "dcac24", "ffe054"), ("782808", "b04010", "e86420")),
    "scrib_jelly": ("pod", ("283828", "3e583e", "587c58", "385468"), ("243460", "3c5490", "5c80c0", "90b8f0"), ("c8e0ff", "e8f4ff", "ffffff")),
    "kwama_egg": ("pod", ("383424", "585238", "7c7450", "485c34"), ("686848", "94946c", "c0c094", "e8e8c4"), ("849860", "acc080", "d0e4a4")),
    "ash_yam": ("roots", ("3c2028", "5c343e", "844c58", "b06c78"), ("542c34", "7c4450", "a86470", "d08c98"), ("2c5424", "448038", "64ac54")),
    "moon_sugar": ("spike", ("2c542c", "447c44", "60a460", "84cc84"), ("9888b8", "c0b4dc", "e4dcf8", "ffffff"), ("d8f0ff", "f0faff", "ffffff")),
    "hist_sap": ("roots", ("281c10", "44301c", "64482c", "84643e"), ("984c08", "cc7414", "f4a428", "ffd858"), ("fff098", "ffffd0", "ffffff")),
    "briar_heart": ("roots", ("2c1c14", "483020", "684830", "3c5824"), ("681018", "9c1c28", "cc303c", "f86068"), ("ffd068", "ffe898", "ffffff")),
    "falmer_ear": ("fungi_flat", ("342c3c", "50445c", "706080", "9484a8"), ("584c68", "807094", "a898bc"), ("282030", "40344c", "5c4c6c"), ("c8bce0", "ece4fc"), True),
    "spider_egg": ("pod", ("384044", "546068", "74848e", "98a8b4"), ("74848c", "9cb0b8", "c8dce4", "f0fcff"), ("d8e8f0", "f0f8fc", "ffffff")),
    "eye_of_sabre_cat": ("pod", ("382c1c", "58442c", "786040", "486030"), ("988048", "c4a868", "ecd490", "fff4c0"), ("181010", "302018", "503828")),
    "powdered_mammoth_tusk": ("relic", ("3c2c1c", "5c442c", "806040", "a48058"), ("989078", "c0b8a0", "e4dcc8", "fffaf0"), ("58442c", "7c6040", "a08058")),
    "wisp_wrappings": ("curtain", ("485870", "6c84a4", "98b4d4", "cce4fc"), ("e8f4ff", "ffffff")),
    "dwarven_oil": ("pod", ("382c18", "584428", "7c643c", "a48854"), ("544424", "806838", "b09454", "dcc078"), ("986818", "cc9428", "ffc448")),
    "chaurus_hunter_antennae": ("spike", ("282014", "443620", "645030", "886e44"), ("4c3818", "785828", "a8803c", "d4ac58"), ("98d868", "c4f498", "e8ffd0")),
}


def draw_flora_3d_atlas(name, spec):
    """Generates a 16x16 4-quadrant surface material atlas for 3D voxel flora models:
    - Quadrant A (0..8, 0..8): Cap top / Egg shell / Upper surface (with spots/speckles)
    - Quadrant B (8..16, 0..8): Cap side / Rim / Fluted shell side
    - Quadrant C (0..8, 8..16): Underside gills / Woven twig nest / Mossy stone base
    - Quadrant D (8..16, 8..16): Stalk stipe / Luminous pearl / Accent core
    """
    seed = sum((i + 1) * ord(ch) for i, ch in enumerate(name)) + 303
    rnd = random.Random(seed)
    img = new()
    kind = spec[0]
    if kind in ("fungi_dome", "fungi_flat"):
        cap_pal = pal_list(*spec[1])
        stalk_pal = pal_list(*spec[2])
        gill_pal = pal_list(*spec[3])
        spot_pal = pal_list(*spec[4]) if spec[4] else None
        # Quadrant A (0..8, 0..8): Cap top with radial shading & spots
        for y in range(8):
            for x in range(8):
                d = math.hypot(x - 3.5, y - 3.5) / 5.0
                t = max(0.08, min(0.95, 0.82 - d * 0.58 + (rnd.random() - 0.5) * 0.10))
                img.putpixel((x, y), ramp_rgba(cap_pal, t))
        if spot_pal:
            for sx, sy in ((2, 2), (5, 1), (1, 5), (4, 4), (6, 5), (3, 6)):
                img.putpixel((sx, sy), ramp_rgba(spot_pal, 0.88))
                if sx + 1 < 7 and (sx + sy) % 2 == 0:
                    img.putpixel((sx + 1, sy), ramp_rgba(spot_pal, 0.65))
        # Quadrant B (8..16, 0..8): Cap side / rim
        for y in range(8):
            for x in range(8, 16):
                v = y / 7.0
                u_shade = 1.0 - abs((x - 11.5) / 4.5) * 0.25
                t = max(0.08, min(0.95, (0.85 - v * 0.55) * u_shade + (rnd.random() - 0.5) * 0.08))
                if y == 7:
                    t *= 0.55
                img.putpixel((x, y), ramp_rgba(cap_pal, t))
        if spot_pal:
            for sx, sy in ((9, 2), (12, 1), (14, 3), (10, 4), (13, 5)):
                img.putpixel((sx, sy), ramp_rgba(spot_pal, 0.82))
        # Quadrant C (0..8, 8..16): Gill underside (radial lamellae)
        for y in range(8, 16):
            for x in range(8):
                stripe = ((x + y) % 2 == 0)
                d = math.hypot(x - 3.5, (y - 8) - 3.5) / 5.0
                t = (0.68 if stripe else 0.36) - d * 0.22 + (rnd.random() - 0.5) * 0.06
                img.putpixel((x, y), ramp_rgba(gill_pal, t))
        # Quadrant D (8..16, 8..16): Fibrous cylindrical stalk
        for y in range(8, 16):
            for x in range(8, 16):
                cyl = 1.0 - abs((x - 11.5) / 4.0) * 0.45
                grain = -0.10 if (x % 3 == 0 and y % 2 == 0) else 0.06 if (x % 2 == 1 and y % 3 == 0) else 0.0
                t = max(0.10, min(0.95, 0.72 * cyl + grain - ((y - 8) / 14.0) * 0.15))
                img.putpixel((x, y), ramp_rgba(stalk_pal, t))
    else:
        base_pal = pal_list(*spec[1])
        main_pal = pal_list(*spec[2])
        acc_pal = pal_list(*spec[3]) if spec[3] else main_pal
        # Quadrant A (0..8, 0..8): Main upper surface (egg / shell / pod / relic)
        for y in range(8):
            for x in range(8):
                d = math.hypot(x - 3.5, y - 3.5) / 5.2
                rib = 0.10 * math.sin((x - 3.5) * 1.6) if kind == "shell" else 0.0
                t = max(0.12, min(0.96, 0.84 - d * 0.52 + rib + (rnd.random() - 0.5) * 0.08))
                img.putpixel((x, y), ramp_rgba(main_pal, t))
        if kind == "nest":
            for sx, sy in ((2, 2), (5, 2), (3, 5), (6, 4)):
                img.putpixel((sx, sy), ramp_rgba(acc_pal, 0.72))
        # Quadrant B (8..16, 0..8): Main side surface with vertical ridges
        for y in range(8):
            for x in range(8, 16):
                ridge = 0.12 if (x % 2 == 0) else -0.08
                t = max(0.10, min(0.95, 0.78 - (y / 7.0) * 0.42 + ridge + (rnd.random() - 0.5) * 0.08))
                img.putpixel((x, y), ramp_rgba(main_pal, t))
        # Quadrant C (0..8, 8..16): Woven twig nest / mossy stone base
        for y in range(8, 16):
            for x in range(8):
                weave = 0.18 if ((x // 2 + y) % 2 == 0) else -0.14
                t = max(0.08, min(0.92, 0.52 + weave + (rnd.random() - 0.5) * 0.12))
                img.putpixel((x, y), ramp_rgba(base_pal, t))
        # Quadrant D (8..16, 8..16): Pearl / glowing core / accent
        for y in range(8, 16):
            for x in range(8, 16):
                d = math.hypot((x - 8) - 3.2, (y - 8) - 3.0) / 4.8
                t = max(0.20, min(0.98, 0.94 - d * 0.60))
                img.putpixel((x, y), ramp_rgba(acc_pal, t))
    return img


def draw_flora_texture(name, spec, harvested=False):
    seed = sum((i + 1) * ord(ch) for i, ch in enumerate(name)) + (777 if harvested else 101)
    kind = spec[0]
    if kind == "star":
        return botanical_star_bush(seed, pal_list(*spec[1]), pal_list(*spec[2]), pal_list(*spec[3]), harvested=harvested)
    if kind == "bell":
        return botanical_bell_stalk(seed, pal_list(*spec[1]), pal_list(*spec[2]), pal_list(*spec[3]), harvested=harvested)
    if kind == "rosette":
        return botanical_rosette(seed, pal_list(*spec[1]), pal_list(*spec[2]), pal_list(*spec[3]), harvested=harvested)
    if kind == "spike":
        return botanical_spike_herb(seed, pal_list(*spec[1]), pal_list(*spec[2]), pal_list(*spec[3]), harvested=harvested)
    if kind == "berry":
        return botanical_berry_shrub(seed, pal_list(*spec[1]), pal_list(*spec[2]), snow_pal=None, harvested=harvested)
    if kind == "berry_snow":
        return botanical_berry_shrub(seed, pal_list(*spec[1]), pal_list(*spec[2]), snow_pal=pal_list(*spec[3]), harvested=harvested)
    if kind in ("fungi_dome", "fungi_flat"):
        spots = pal_list(*spec[4]) if spec[4] else None
        return botanical_mushroom_cluster(seed, pal_list(*spec[1]), pal_list(*spec[2]), pal_list(*spec[3]),
                                          spot_pal=spots, flat=spec[5], harvested=harvested)
    if kind == "roots":
        leaves = pal_list(*spec[3]) if spec[3] else None
        return botanical_creeping_roots(seed, pal_list(*spec[1]), pal_list(*spec[2]), leaf_pal=leaves, harvested=harvested)
    if kind == "curtain":
        acc = pal_list(*spec[2]) if spec[2] else None
        return botanical_hanging_curtain(seed, pal_list(*spec[1]), accent_pal=acc, harvested=harvested)
    return botanical_forage_node(seed, kind, pal_list(*spec[1]), pal_list(*spec[2]), pal_list(*spec[3]), harvested=harvested)


def _box(x0, y0, z0, x1, y1, z1, uv_up, uv_down, uv_side, tex="#atlas", rot=None):
    el = {
        "from": [x0, y0, z0],
        "to": [x1, y1, z1],
        "faces": {
            "up": {"uv": list(uv_up), "texture": tex},
            "down": {"uv": list(uv_down), "texture": tex},
            "north": {"uv": list(uv_side), "texture": tex},
            "south": {"uv": list(uv_side), "texture": tex},
            "west": {"uv": list(uv_side), "texture": tex},
            "east": {"uv": list(uv_side), "texture": tex},
        },
    }
    if rot:
        el["rotation"] = rot
    return el


def flora_3d_model(tex_name, kind, harvested=False, base_name=None):
    if base_name is None:
        base_name = tex_name[:-10] if tex_name.endswith("_harvested") else tex_name
    tex_ref = "nirncraft:block/" + tex_name
    atlas_ref = "nirncraft:block/" + base_name + "_atlas"

    # True 3D voxel geometry for mushrooms, nests, shells, pods, and relics
    if kind in ("fungi_dome", "fungi_flat"):
        uv_cap_top = (0, 0, 8, 8)
        uv_cap_crown = (1, 1, 7, 7)
        uv_cap_side = (8, 1, 16, 7)
        uv_gills = (0, 8, 8, 16)
        uv_stalk = (9, 9, 15, 15)
        if harvested:
            elements = [
                _box(4.6, 0.0, 4.6, 7.4, 2.4, 7.4, uv_gills, uv_stalk, uv_stalk),
                _box(10.0, 0.0, 8.8, 12.2, 1.7, 11.0, uv_gills, uv_stalk, uv_stalk),
                _box(4.2, 0.0, 10.4, 6.0, 1.4, 12.2, uv_gills, uv_stalk, uv_stalk),
            ]
        elif kind == "fungi_dome":
            elements = [
                # Main tall dome mushroom (back-left)
                _box(4.6, 0.0, 4.6, 7.4, 7.2, 7.4, uv_stalk, uv_stalk, uv_stalk),
                _box(2.2, 6.6, 2.2, 9.8, 8.8, 9.8, uv_cap_top, uv_gills, uv_cap_side),
                _box(3.4, 8.8, 3.4, 8.6, 10.6, 8.6, uv_cap_crown, uv_cap_top, uv_cap_side),
                # Secondary medium dome mushroom (right)
                _box(10.2, 0.0, 8.6, 12.2, 4.6, 10.6, uv_stalk, uv_stalk, uv_stalk),
                _box(8.4, 4.2, 6.8, 14.0, 6.0, 12.4, uv_cap_top, uv_gills, uv_cap_side),
                _box(9.4, 6.0, 7.8, 13.0, 7.2, 11.4, uv_cap_crown, uv_cap_top, uv_cap_side),
                # Small young mushroom bud (front-left)
                _box(4.2, 0.0, 10.6, 5.8, 2.8, 12.2, uv_stalk, uv_stalk, uv_stalk),
                _box(3.0, 2.5, 9.4, 7.0, 4.3, 13.4, uv_cap_top, uv_gills, uv_cap_side),
            ]
        else:
            # fungi_flat: broad tiered shelf/flat-cap mushrooms with distinct separation
            elements = [
                # Main broad flat mushroom (back-left)
                _box(4.6, 0.0, 4.6, 7.4, 5.8, 7.4, uv_stalk, uv_stalk, uv_stalk),
                _box(1.8, 5.4, 1.8, 10.2, 7.0, 10.2, uv_cap_top, uv_gills, uv_cap_side),
                _box(3.6, 7.0, 3.6, 8.4, 7.8, 8.4, uv_cap_crown, uv_cap_top, uv_cap_side),
                # Secondary flat mushroom (right)
                _box(10.2, 0.0, 8.8, 12.2, 3.6, 10.8, uv_stalk, uv_stalk, uv_stalk),
                _box(8.0, 3.3, 6.6, 14.4, 4.6, 13.0, uv_cap_top, uv_gills, uv_cap_side),
                # Third low flat mushroom (front-left)
                _box(4.0, 0.0, 10.6, 5.8, 2.1, 12.4, uv_stalk, uv_stalk, uv_stalk),
                _box(2.4, 1.8, 9.0, 7.4, 3.0, 14.0, uv_cap_top, uv_gills, uv_cap_side),
            ]
        return {
            "ambientocclusion": True,
            "render_type": "minecraft:cutout",
            "textures": {
                "atlas": atlas_ref,
                "particle": tex_ref,
            },
            "elements": elements,
        }

    if kind in ("nest", "shell", "pod", "relic"):
        uv_top = (0, 0, 8, 8)
        uv_side = (8, 0, 16, 8)
        uv_base = (0, 8, 8, 16)
        uv_acc = (8, 8, 16, 16)
        if kind == "nest":
            elements = [
                # Woven twig nest floor & 4 cup walls
                _box(3.5, 0.0, 3.5, 12.5, 1.4, 12.5, uv_base, uv_base, uv_base),
                _box(3.0, 1.0, 3.0, 13.0, 3.2, 4.6, uv_base, uv_base, uv_base),
                _box(3.0, 1.0, 11.4, 13.0, 3.2, 13.0, uv_base, uv_base, uv_base),
                _box(3.0, 1.0, 4.6, 4.6, 3.2, 11.4, uv_base, uv_base, uv_base),
                _box(11.4, 1.0, 4.6, 13.0, 3.2, 11.4, uv_base, uv_base, uv_base),
            ]
            if not harvested:
                elements.extend([
                    _box(4.8, 1.4, 4.8, 7.4, 4.2, 7.4, uv_top, uv_side, uv_side),
                    _box(5.3, 4.2, 5.3, 6.9, 4.9, 6.9, uv_top, uv_top, uv_side),
                    _box(8.4, 1.4, 5.2, 11.0, 4.0, 7.8, uv_top, uv_side, uv_side),
                    _box(8.9, 4.0, 5.7, 10.5, 4.7, 7.3, uv_top, uv_top, uv_side),
                    _box(6.4, 1.4, 8.2, 9.0, 4.1, 10.8, uv_top, uv_side, uv_side),
                    _box(6.9, 4.1, 8.7, 8.5, 4.8, 10.3, uv_top, uv_top, uv_side),
                ])
        elif kind == "shell":
            elements = [
                _box(3.0, 0.0, 3.0, 13.0, 1.4, 13.0, uv_base, uv_base, uv_base),
                _box(4.0, 1.4, 4.5, 12.0, 2.7, 12.0, uv_top, uv_base, uv_side),
            ]
            if not harvested:
                elements.extend([
                    _box(6.6, 2.5, 6.8, 9.4, 5.0, 9.6, uv_acc, uv_acc, uv_acc),
                    _box(4.0, 2.7, 3.8, 12.0, 3.9, 10.6, uv_top, uv_acc, uv_side,
                         rot={"origin": [8.0, 2.7, 4.5], "axis": "x", "angle": -22.5}),
                ])
        elif kind == "pod":
            elements = [
                _box(3.5, 0.0, 3.5, 12.5, 1.3, 12.5, uv_base, uv_base, uv_base),
            ]
            if not harvested:
                elements.extend([
                    _box(5.6, 1.0, 5.6, 10.0, 6.8, 10.0, uv_top, uv_base, uv_side),
                    _box(6.4, 6.8, 6.4, 9.2, 7.6, 9.2, uv_acc, uv_top, uv_acc),
                    _box(9.6, 1.0, 7.8, 12.8, 4.8, 11.0, uv_top, uv_base, uv_side),
                    _box(3.4, 1.0, 7.6, 6.6, 4.4, 10.8, uv_top, uv_base, uv_side),
                ])
        else:
            # relic
            elements = [
                _box(3.5, 0.0, 3.5, 12.5, 1.8, 12.5, uv_base, uv_base, uv_base),
            ]
            if not harvested:
                elements.extend([
                    _box(6.5, 1.5, 6.5, 9.5, 7.4, 9.5, uv_top, uv_base, uv_side,
                         rot={"origin": [8.0, 1.5, 8.0], "axis": "z", "angle": 22.5}),
                    _box(4.2, 1.5, 7.2, 6.6, 5.4, 9.6, uv_acc, uv_base, uv_side,
                         rot={"origin": [5.4, 1.5, 8.4], "axis": "x", "angle": -22.5}),
                ])
        return {
            "ambientocclusion": True,
            "render_type": "minecraft:cutout",
            "textures": {
                "atlas": atlas_ref,
                "particle": tex_ref,
            },
            "elements": elements,
        }

    # Clean, symmetric 2-plane 45-degree botanical cross (matches vanilla Minecraft flowers/sweet_berry_bush/fern
    # and waves smoothly in Complementary Reimagined without duplicate tilted planes or UV shearing)
    elements = [
        {
            "from": [0.8, 0, 8],
            "to": [15.2, 16, 8],
            "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True},
            "shade": False,
            "faces": {
                "north": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                "south": {"uv": [0, 0, 16, 16], "texture": "#cross"},
            },
        },
        {
            "from": [8, 0, 0.8],
            "to": [8, 16, 15.2],
            "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True},
            "shade": False,
            "faces": {
                "west": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                "east": {"uv": [0, 0, 16, 16], "texture": "#cross"},
            },
        },
    ]
    return {
        "ambientocclusion": False,
        "render_type": "minecraft:cutout",
        "textures": {
            "cross": tex_ref,
            "particle": tex_ref,
        },
        "elements": elements,
    }


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def plants():
    models_block_dir = os.path.join(ASSETS_DIR, "models", "block")
    blockstates_dir = os.path.join(ASSETS_DIR, "blockstates")
    flower_ids = []
    for name, spec in sorted(FLORA_SPECS.items()):
        kind = spec[0]
        img = draw_flora_texture(name, spec, harvested=False)
        img_harv = draw_flora_texture(name, spec, harvested=True)
        out("block", name, img)
        out("block", name + "_harvested", img_harv)
        if kind in ("fungi_dome", "fungi_flat", "nest", "shell", "pod", "relic"):
            out("block", name + "_atlas", draw_flora_3d_atlas(name, spec))

        write_json(os.path.join(models_block_dir, name + ".json"), flora_3d_model(name, kind, harvested=False, base_name=name))
        write_json(os.path.join(models_block_dir, name + "_harvested.json"), flora_3d_model(name + "_harvested", kind, harvested=True, base_name=name))

        bs = {
            "variants": {
                "harvested=false": [
                    {"model": "nirncraft:block/" + name},
                    {"model": "nirncraft:block/" + name, "y": 90},
                    {"model": "nirncraft:block/" + name, "y": 180},
                    {"model": "nirncraft:block/" + name, "y": 270},
                ],
                "harvested=true": [
                    {"model": "nirncraft:block/" + name + "_harvested"},
                    {"model": "nirncraft:block/" + name + "_harvested", "y": 90},
                    {"model": "nirncraft:block/" + name + "_harvested", "y": 180},
                    {"model": "nirncraft:block/" + name + "_harvested", "y": 270},
                ],
            }
        }
        write_json(os.path.join(blockstates_dir, name + ".json"), bs)
        flower_ids.append("nirncraft:" + name)

    # Also include tamriel biome wildflower scathecraw in flower tags
    tag_values = sorted(set(flower_ids + ["nirncraft:scathecraw"]))
    mc_block_tags = os.path.join(DATA_DIR, "minecraft", "tags", "blocks")
    write_json(os.path.join(mc_block_tags, "small_flowers.json"), {"replace": False, "values": tag_values})
    write_json(os.path.join(mc_block_tags, "flowers.json"), {"replace": False, "values": tag_values})



# ============================================================================ stations

def planks(img, base, dark, light, seed, x0=0, y0=0, x1=16, y1=16, vertical=False):
    rnd = random.Random(seed)
    for y in range(y0, y1):
        for x in range(x0, x1):
            u, v = (y, x) if vertical else (x, y)
            c = base
            if v % 4 == 3:
                c = dark
            elif rnd.random() < 0.12:
                c = light
            elif rnd.random() < 0.10:
                c = shade(base, 0.88)
            if (u + (v // 4) * 5) % 9 == 0 and v % 4 != 3:
                c = dark
            img.putpixel((x, y), c)


def rune(img, cx, cy, color, glow):
    pattern = ["..#..", ".#.#.", "#.#.#", ".#.#.", "..#.."]
    for j, row in enumerate(pattern):
        for i, ch in enumerate(row):
            if ch == "#":
                put(img, cx - 2 + i, cy - 2 + j, color)
            elif glow and abs(i - 2) + abs(j - 2) <= 2:
                blend(img, cx - 2 + i, cy - 2 + j, glow)


def stations():
    wood, wood_d, wood_l = hexc("3a2232"), hexc("22121e"), hexc("543446")
    gold, gold_d = hexc("d8aa48"), hexc("8a6420")
    # ---- arcane enchanter side / front / bottom
    for name in ("side", "front", "bottom"):
        img = new()
        planks(img, wood, wood_d, wood_l, 11 + len(name), vertical=True)
        if name != "bottom":
            for x in range(16):
                put(img, x, 4, gold)
                put(img, x, 5, gold_d)
                put(img, x, 15, gold_d)
            for y in range(4, 16):
                put(img, 0, y, gold_d)
                put(img, 15, y, gold_d)
        if name == "side":
            rune(img, 8, 10, hexc("b58cff"), hexc("7a4ad0", 110))
        if name == "front":
            # inlaid soul crystal in a gold setting
            for y in range(7, 14):
                for x in range(5, 11):
                    if abs(x - 7.5) + abs(y - 10) <= 3.6:
                        put(img, x, y, gold_d)
            for y in range(8, 13):
                for x in range(6, 10):
                    if abs(x - 7.5) + abs(y - 10) <= 2.6:
                        put(img, x, y, mix(hexc("5aa8ff"), hexc("d8f0ff"), (10 - y) / 3 + 0.2))
        out("block", "arcane_enchanter_" + name, img)

    # ---- top: purple cloth, gold border, arcane circle
    img = new()
    rnd = random.Random(7)
    for y in range(16):
        for x in range(16):
            c = hexc("3c1a5a") if rnd.random() < 0.8 else hexc("341650")
            img.putpixel((x, y), c)
    for i in range(16):
        for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
            put(img, x, y, gold)
        for (x, y) in ((i, 1), (i, 14), (1, i), (14, i)):
            if 1 <= i <= 14:
                put(img, x, y, gold_d)
    for a in range(0, 360, 8):
        r = 5.5
        put(img, int(round(7.5 + r * math.cos(math.radians(a)))), int(round(7.5 + r * math.sin(math.radians(a)))), hexc("a87ae8"))
    rune(img, 8, 8, hexc("e0c8ff"), None)
    out("block", "arcane_enchanter_top", img)

    # ---- book (geometry uses region x 3..13, y 4..12; page edges in rows 13-14)
    img = new()
    cover, cover_d = hexc("6a1c1c"), hexc("3a0c0c")
    page, page_d, ink = hexc("f2e6c4"), hexc("d4c49a"), hexc("8a7a5a")
    for y in range(4, 12):
        for x in range(3, 13):
            img.putpixel((x, y), page if x not in (7, 8) else page_d)
    for x in range(3, 13):
        put(img, x, 4, cover)
        put(img, x, 11, cover)
    for y in range(4, 12):
        put(img, 3, y, cover)
        put(img, 12, y, cover)
    for y in (6, 8, 10):
        for x in list(range(4, 7)) + list(range(9, 12)):
            if (x + y) % 5 != 0:
                put(img, x, y, ink)
    put(img, 10, 7, hexc("8a40d0"))
    put(img, 5, 9, hexc("8a40d0"))
    for x in range(16):
        put(img, x, 13, page_d)
        put(img, x, 14, cover_d)
    for y in range(16):
        for x in range(16):
            if img.getpixel((x, y))[3] == 0:
                img.putpixel((x, y), cover)
    out("block", "arcane_enchanter_book", img)

    # ---- floating crystal
    img = new()
    for y in range(16):
        for x in range(16):
            t = y / 15.0
            c = mix(hexc("e8f4ff"), hexc("3a6ad8"), t)
            if x % 4 == 0:
                c = shade(c, 0.8)
            img.putpixel((x, y), c)
    out("block", "arcane_enchanter_crystal", img)

    # ---- alchemy lab
    oak, oak_d, oak_l = hexc("8a6038"), hexc("5a3c20"), hexc("a87a4a")
    img = new()
    planks(img, oak, oak_d, oak_l, 21)
    # a shelf with bottles
    for x in range(16):
        put(img, x, 9, oak_d)
        put(img, x, 10, hexc("6a4628"))
    bottle_cols = [hexc("d03a3a"), hexc("3a8ad0"), hexc("4ac04a"), hexc("d0a03a")]
    for i, bx in enumerate((2, 6, 10, 13)):
        col = bottle_cols[i % 4]
        put(img, bx, 4, hexc("c8b088"))
        for y in range(5, 9):
            put(img, bx, y, col if y > 5 else hexc("e8f4ff"))
            if bx + 1 < 16 and i != 3:
                put(img, bx + 1, y, shade(col, 0.75) if y > 5 else hexc("b8d0e0"))
    for x in range(16):
        put(img, x, 0, oak_d)
        put(img, x, 15, oak_d)
    for y in range(16):
        put(img, 0, y, oak_d)
        put(img, 15, y, oak_d)
    out("block", "alchemy_lab_side", img)

    img = new()
    planks(img, oak, oak_d, oak_l, 22)
    for (x0, y0) in ((2, 2), (9, 2), (2, 9), (9, 9)):
        for y in range(y0, y0 + 5):
            for x in range(x0, x0 + 5):
                edge = x in (x0, x0 + 4) or y in (y0, y0 + 4)
                put(img, x, y, oak_d if edge else oak_l)
        put(img, x0 + 2, y0 + 2, gold)
    for x in range(16):
        put(img, x, 0, oak_d)
        put(img, x, 15, oak_d)
    for y in range(16):
        put(img, 0, y, oak_d)
        put(img, 15, y, oak_d)
    out("block", "alchemy_lab_front", img)

    img = new()
    rnd = random.Random(31)
    for y in range(16):
        for x in range(16):
            c = hexc("8e8a84") if rnd.random() < 0.7 else hexc("7a7670")
            img.putpixel((x, y), c)
    for (sx, sy, col) in ((4, 5, hexc("4a8a3a")), (11, 10, hexc("7a3a8a")), (10, 3, hexc("a0602a"))):
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                if abs(dx) + abs(dy) < 2:
                    blend(img, sx + dx, sy + dy, col[:3] + (150,))
    for i in range(16):
        for (x, y) in ((i, 0), (i, 15), (0, i), (15, i)):
            put(img, x, y, oak_d)
    out("block", "alchemy_lab_top", img)

    img = new()
    planks(img, oak_d, shade(oak_d, 0.7), oak, 23)
    out("block", "alchemy_lab_bottom", img)

    # glassware atlas: x0-7 green flask, x8-15 amber retort, rows 12-15 stone mortar
    img = new()
    for y in range(12):
        for x in range(8):
            liquid = y >= 5
            c = mix(hexc("6ae07a"), hexc("2a8a3a"), (y - 5) / 6) if liquid else hexc("d8eef4")
            if x == 0 or x == 7:
                c = shade(c, 0.8)
            if x == 2 and not liquid:
                c = hexc("ffffff")
            img.putpixel((x, y), c)
        for x in range(8, 16):
            liquid = y >= 4
            c = mix(hexc("f0b040"), hexc("a05a10"), (y - 4) / 7) if liquid else hexc("e8e0d0")
            if x in (8, 15):
                c = shade(c, 0.8)
            if x == 10 and not liquid:
                c = hexc("ffffff")
            img.putpixel((x, y), c)
    for y in range(12, 16):
        for x in range(16):
            img.putpixel((x, y), hexc("9a948a") if (x + y) % 3 else hexc("7a746a"))
    out("block", "alchemy_lab_glass", img)


# ============================================================================ effect icons (18x18)

GLYPHS = {
    "plus": ["...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#..."],
    "minus": [".......", ".......", ".......", "#######", ".......", ".......", "......."],
    "drop": ["...#...", "..###..", "..###..", ".#####.", ".#####.", ".#####.", "..###.."],
    "up": ["...#...", "..###..", ".#####.", "#######", "..###..", "..###..", "..###.."],
    "down": ["..###..", "..###..", "..###..", "#######", ".#####.", "..###..", "...#..."],
    "shield": ["#######", "#######", "#######", "#######", ".#####.", "..###..", "...#..."],
    "broken": ["###.###", "##..###", "###.###", "##..###", ".#.###.", "..###..", "...#..."],
    "swirl": [".#####.", "#.....#", "#.###.#", "#.#.#.#", "#.#...#", "#.####.", "#......"],
    "bang": ["...#...", "..###..", "..###..", "..###..", "...#...", ".......", "...#..."],
    "fang": ["#.....#", "##...##", "#######", "#.#.#.#", "..#.#..", "..#.#..", "......."],
    "cross": ["..###..", "..###..", "#######", "#######", "#######", "..###..", "..###.."],
    "star": ["...#...", "...#...", "#.###.#", ".#####.", "..###..", ".##.##.", "#.....#"],
}

EFFECTS = {
    "restore_magicka": ("2E6BE6", "plus"), "restore_stamina": ("3FAF3F", "plus"),
    "damage_magicka": ("23305E", "minus"), "damage_stamina": ("4F5E23", "minus"),
    "regenerate_magicka": ("5A8CFF", "swirl"), "regenerate_stamina": ("7AD36A", "swirl"),
    "damage_magicka_regen": ("404880", "down"), "damage_stamina_regen": ("607040", "down"),
    "ravage_magicka": ("302060", "drop"), "ravage_stamina": ("505020", "drop"),
    "resist_frost": ("9AD8F0", "shield"), "resist_shock": ("D8D060", "shield"),
    "resist_poison": ("60A040", "shield"), "resist_magic": ("B080E0", "shield"),
    "weakness_to_fire": ("C04020", "broken"), "weakness_to_frost": ("6090B0", "broken"),
    "weakness_to_shock": ("909030", "broken"), "weakness_to_poison": ("406020", "broken"),
    "weakness_to_magic": ("704090", "broken"),
    "fortify_one_handed": ("C0A060", "up"), "fortify_two_handed": ("A08040", "up"),
    "fortify_archery": ("80A040", "up"), "fortify_light_armor": ("70A070", "up"),
    "fortify_heavy_armor": ("707090", "up"), "fortify_sneak": ("505050", "up"),
    "fortify_smithing": ("B06030", "up"), "fortify_enchanting": ("8060D0", "star"),
    "fortify_alchemy": ("50B080", "star"), "fortify_destruction": ("E05030", "up"),
    "fortify_restoration": ("F0E080", "up"), "fortify_conjuration": ("9050C0", "up"),
    "fortify_illusion": ("C070D0", "up"), "fortify_alteration": ("50A0C0", "up"),
    "fortify_lockpicking": ("909090", "up"), "fortify_pickpocket": ("60A060", "up"),
    "fortify_barter": ("E0C040", "up"), "fortify_carry_weight": ("A07850", "up"),
    "fear": ("502040", "bang"), "frenzy": ("C02020", "fang"), "cure_disease": ("E0E0C0", "cross"),
}


def effect_icons():
    for name, (col, glyph) in EFFECTS.items():
        img = new(18)
        base = hexc(col)
        rim = shade(base, 0.45)
        for y in range(18):
            for x in range(18):
                d = math.hypot(x - 8.5, y - 8.5)
                if d <= 8.2:
                    t = (x + y) / 34.0
                    c = mix(shade(base, 1.25), shade(base, 0.7), t)
                    if d > 7.0:
                        c = rim
                    img.putpixel((x, y), c[:3] + (255,))
        lum = 0.3 * base[0] + 0.59 * base[1] + 0.11 * base[2]
        ink = (30, 24, 20, 255) if lum > 170 else (255, 250, 235, 255)
        shadow = (0, 0, 0, 110)
        rows = GLYPHS[glyph]
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch == "#":
                    blend(img, 6 + i, 6 + j, shadow)
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch == "#":
                    put(img, 5 + i, 5 + j, ink)
        out("mob_effect", name, img)


if __name__ == "__main__":
    soul_gems()
    azuras_star()
    ingredients()
    plants()
    stations()
    effect_icons()
    print("arcane textures written to", TEX)
