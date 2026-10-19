#!/usr/bin/env python3
"""Procedural pixel art for the NirnCraft smithing module (Forge 1.20.1).

Writes 16x16 item/block textures and 64x32 armor layers into
mod/src/main/resources/assets/nirncraft/textures/{item,block,models/armor}.

    python3 tools/textures/crafting.py [--preview out.png]

Everything is deterministic (fixed seeds). Weapons are drawn in a rotated (t, c) frame along the
bottom-left -> top-right diagonal (t = along the weapon, c = across it), so each weapon family is a small
shape function and every Skyrim material is a palette + style flags on top of it.
"""
import math
import os
import random
import sys

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures")
ITEM = os.path.join(TEX, "item")
BLOCK = os.path.join(TEX, "block")
ARMOR = os.path.join(TEX, "models", "armor")
PREVIEW = []


# ============================================================================ color helpers

def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def shade(c, f):
    """f < 1 darkens, f > 1 lightens (towards white)."""
    if f <= 1:
        return (int(c[0] * f), int(c[1] * f), int(c[2] * f), c[3])
    t = min(1.0, f - 1)
    return mix(c, (255, 255, 255, c[3]), t)


def with_alpha(c, a):
    return (c[0], c[1], c[2], a)


def new(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def save(img, folder, name):
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, name + ".png"))
    PREVIEW.append((name, img))


# ============================================================================ generic mask shading

def outline(img, color_of=None, diagonal=False, skip=None):
    """Adds a 1px outline around opaque pixels, colored by darkening the neighbor (or color_of(neighbor))."""
    w, h = img.size
    px = img.load()
    src = [[px[x, y] for y in range(h)] for x in range(w)]
    dirs = [(1, 0), (-1, 0), (0, 1), (0, -1)]
    if diagonal:
        dirs += [(1, 1), (-1, -1), (1, -1), (-1, 1)]
    for x in range(w):
        for y in range(h):
            if src[x][y][3] != 0:
                continue
            for dx, dy in dirs:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and src[nx][ny][3] > 0:
                    if skip and skip(nx, ny):
                        continue
                    n = src[nx][ny]
                    px[x, y] = color_of(n) if color_of else with_alpha(shade(n, 0.35), 255)
                    break


def shade_mask(mask, pal, seed=0, noise=0.0, light_bias=0):
    """Shades a boolean 16x16 mask: top-left facing pixels light, bottom-right dark, plus optional noise.

    pal = (dark, mid, light, highlight)."""
    rnd = random.Random(seed)
    h = len(mask)
    w = len(mask[0])
    img = new(w, h)
    px = img.load()

    def m(x, y):
        return 0 <= x < w and 0 <= y < h and mask[y][x]

    for y in range(h):
        for x in range(w):
            if not mask[y][x]:
                continue
            up = not m(x, y - 1)
            left = not m(x - 1, y)
            down = not m(x, y + 1)
            right = not m(x + 1, y)
            score = (1 if up else 0) + (1 if left else 0) - (1 if down else 0) - (1 if right else 0) + light_bias
            if noise:
                score += rnd.uniform(-noise, noise)
            if score >= 2:
                c = pal[3]
            elif score >= 0.75:
                c = pal[2]
            elif score > -0.75:
                c = pal[1]
            else:
                c = pal[0]
            px[x, y] = c
    return img


def grid(rows):
    return [[ch != "." for ch in row] for row in rows]


def paint_grid(rows, colors):
    """rows of chars -> image using colors dict (missing chars are transparent)."""
    img = new(len(rows[0]), len(rows))
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colors and colors[ch] is not None:
                px[x, y] = colors[ch]
    return img


# ============================================================================ weapons

def P(*hexes, a=255):
    return [hexc(h, a) for h in hexes]


# dark, mid, light, edge highlight
TIER_STYLE = {
    "iron": dict(blade=P("575757", "8a8a8a", "b9b9b9", "e4e4e4"), guard=P("3e3a36", "66605a", "8c857c"),
                 grip=P("3d2716", "5e3d22", "7a5230"), accent=None, wood=P("4a3220", "6e4b2e", "8f673f"), style=set()),
    "steel": dict(blade=P("4f5964", "7f8c98", "b6c2cc", "eef5fa"), guard=P("3a3f46", "626a73", "8d969f"),
                  grip=P("2f1f13", "4d3320", "684630"), accent=None, wood=P("3d2a1a", "5a3e26", "7a5634"), style={"fuller"}),
    "orcish": dict(blade=P("363f31", "56634c", "7d8c72", "a7b59a"), guard=P("2a2f25", "454f3c", "667357"),
                   grip=P("2e2416", "4b3a24", "634d31"), accent=P("5a2a12", "8a4420", "b0602c"), wood=P("33291c", "4d3e2a", "665338"),
                   style={"jagged", "spikes"}),
    "dwarven": dict(blade=P("6e4416", "a8722e", "d4a256", "f6d890"), guard=P("553411", "86581f", "b8823c"),
                    grip=P("362619", "564029", "6e5236"), accent=P("3c2a10", "6a4a1c", "9a7030"), wood=P("553411", "86581f", "b8823c"),
                    style={"blocky", "fuller"}),
    "elven": dict(blade=P("80621a", "bc922e", "e3c35c", "fff3b0"), guard=P("2c5426", "4a8238", "79b852"),
                  grip=P("33401f", "4f6230", "6a7f42"), accent=P("2c5426", "4a8238", "79b852"), wood=P("80621a", "bc922e", "e3c35c"),
                  style={"curved", "fuller_accent"}),
    "glass": dict(blade=P("2a7034", "46ad55", "86dd93", "d6ffdf", a=225), guard=P("5f6d68", "95a6a0", "cbd9d4"),
                  grip=P("26352b", "395040", "4d6a55"), accent=P("1f6e44", "33a066", "6fd89c"), wood=P("5f6d68", "95a6a0", "cbd9d4"),
                  style={"glass", "fuller_accent"}),
    "ebony": dict(blade=P("121014", "221e26", "39323f", "6a5f78"), guard=P("0d0b0f", "1d1922", "352e3c"),
                  grip=P("1c1510", "30251b", "433427"), accent=P("2b2433", "4b3f5a", "77699a"), wood=P("121014", "221e26", "39323f"),
                  style={"sheen"}),
    "daedric": dict(blade=P("100909", "211416", "362026", "5a3340"), guard=P("0c0707", "1d1012", "331c20"),
                    grip=P("1d1010", "321a1a", "472525"), accent=P("7a0b0b", "c81f1a", "ff5a3c"), wood=P("100909", "211416", "362026"),
                    style={"spikes", "fuller_accent", "serrate", "glow"}),
    "dragonbone": dict(blade=P("857a66", "b8ad94", "ddd3bc", "faf5e6"), guard=P("4c3e2e", "6f5c45", "94805f"),
                       grip=P("33261a", "4f3b2a", "6a5039"), accent=P("3a2f26", "5c4a3c", "84705c"), wood=P("857a66", "b8ad94", "ddd3bc"),
                       style={"serrate", "spikes", "bone"}),
}


class Canvas:
    """16x16 canvas addressed in the diagonal (t, c) frame: x = (t + c) / 2, y = 15 - (t - c) / 2."""

    def __init__(self):
        self.img = new()
        self.px = self.img.load()
        self.roles = {}

    @staticmethod
    def xy(t, c):
        if (t + c) % 2:
            return None
        x = (t + c) // 2
        b = (t - c) // 2
        y = 15 - b
        if 0 <= x < 16 and 0 <= y < 16:
            return x, y
        return None

    @staticmethod
    def tc(x, y):
        b = 15 - y
        return x + b, x - b

    def put(self, t, c, color, role="solid"):
        p = self.xy(t, c)
        if p:
            self.px[p] = color
            self.roles[p] = role

    def each(self):
        for x in range(16):
            for y in range(16):
                t, c = self.tc(x, y)
                yield x, y, t, c


def blade_color(st, c, w, t, t0, t1):
    dark, mid, light, edge = st["blade"]
    if w <= 1:
        col = edge if c < 0 else (mid if c == 0 else dark)
    else:
        if c <= -w:
            col = edge
        elif c < 0:
            col = light
        elif c == 0:
            col = mid
        elif c < w:
            col = mix(mid, dark, 0.5)
        else:
            col = dark
    if "fuller" in st["style"] and w >= 1 and c == 0 and t0 + 1 < t < t1 - 3:
        col = shade(mid, 0.82)
    if "fuller_accent" in st["style"] and c == 0 and t0 + 1 < t < t1 - 3 and st["accent"]:
        col = st["accent"][2] if "glow" in st["style"] else st["accent"][1]
    if "sheen" in st["style"] and c == -1 and (t // 3) % 2 == 0:
        col = st["accent"][2]
    return col


def draw_blade_weapon(st, pommel, grip, guard, blade, width, tip_len=2):
    """pommel=(t0,t1); grip=(t0,t1); guard=(t0,t1,halfwidth); blade=(t0,t1)."""
    cv = Canvas()
    gd, gm, gl = st["guard"]
    hd, hm, hl = st["grip"]
    t0, t1 = blade
    for x, y, t, c in cv.each():
        col = None
        if pommel[0] <= t <= pommel[1] and abs(c) <= 1:
            col = gl if c < 0 else (gm if c == 0 else gd)
        elif grip[0] <= t <= grip[1] and c in (0, 1):
            col = hl if (t % 3 == 0) else (hm if c == 0 else hd)
        elif guard[0] <= t <= guard[1] and abs(c) <= guard[2]:
            col = gl if t == guard[1] and c < 0 else (gm if c <= 0 else gd)
            if "elven" == st.get("name") and abs(c) == guard[2]:
                col = gl
        elif t0 <= t <= t1:
            hw = width
            if t > t1 - tip_len * width:
                hw = max(0.0, (t1 - t) / tip_len)
            if "curved" in st["style"]:
                # elven blades swell slightly toward the tip
                if t0 + 4 < t < t1 - 4:
                    hw = width + (0.6 if c < 0 else 0)
            if abs(c) <= hw + 1e-6:
                col = blade_color(st, c, width, t, t0, t1)
            elif "serrate" in st["style"] and t0 + 2 <= t <= t1 - 3 and t % 4 == 0 and abs(c) == int(hw) + 1:
                col = st["blade"][2] if c < 0 else st["blade"][0]
        if col is not None:
            cv.px[x, y] = col
            cv.roles[(x, y)] = "solid"
    if "jagged" in st["style"]:
        for x, y, t, c in cv.each():
            if t0 + 2 < t < t1 - 2 and c == -width and t % 4 == 1 and (x, y) in cv.roles:
                cv.px[x, y] = (0, 0, 0, 0)
                del cv.roles[(x, y)]
    if "spikes" in st["style"]:
        for c in (-(guard[2] + 1), guard[2] + 1):
            cv.put(guard[1] + 1, c + (1 if c < 0 else -1) * 0, st["guard"][2] if c < 0 else st["guard"][0])
    return cv


def draw_hafted(st, haft_end, grip_end, head):
    """War axes, battleaxes, maces, warhammers: a straight haft with a head function."""
    cv = Canvas()
    wd, wm, wl = st["wood"]
    hd, hm, hl = st["grip"]
    for x, y, t, c in cv.each():
        col = None
        if 1 <= t <= haft_end and c in (0, 1):
            if t <= grip_end:
                col = hl if t % 3 == 0 else (hm if c == 0 else hd)
            else:
                col = wl if c == 0 else wd
                if t % 5 == 0:
                    col = wm
        h = head(t, c)
        if h is not None:
            col = h
        if col is not None:
            cv.px[x, y] = col
            cv.roles[(x, y)] = "solid"
    return cv


def axe_head(st, tm, depth, neck, bit, double, round_k=2.5):
    """A fan-shaped axe blade growing out of the haft (perpendicular = c), narrow neck, wide curved bit."""
    dark, mid, light, edge = st["blade"]
    gd, gm, gl = st["guard"]
    blocky = "blocky" in st["style"]

    def f(t, c):
        dt = t - tm
        for s in ([-1, 1] if double else [-1]):
            cc = c * s
            if cc < 1:
                continue
            frac = min(1.0, cc / depth)
            hw = neck + (bit - neck) * (frac if blocky else frac ** 1.6)
            if abs(dt) > hw + 0.01:
                continue
            reach = depth - (0 if blocky else round_k * (dt / bit) ** 2)
            if "jagged" in st["style"] and t % 3 == 0:
                reach -= 1
            if cc > reach + 0.01:
                continue
            if cc > reach - 1.5:
                if "glow" in st["style"]:
                    return st["accent"][2]
                return edge if s < 0 else light
            if cc <= 1.5:
                return gd  # socket around the haft
            if "glow" in st["style"] and abs(dt) <= 0.5 and cc > 2:
                return st["accent"][0]
            if "bone" in st["style"] and (cc + t) % 4 == 0:
                return dark
            return light if dt > 1 else (mid if dt > -2 else dark)
        if not double and 1 <= c <= 3 and abs(dt) <= 1:
            return gm if c < 3 else gd  # back poll / spike
        if "spikes" in st["style"] and not double and c == 4 and dt == 0:
            return gl
        return None

    return f


def mace_head(st, center, radius):
    dark, mid, light, edge = st["blade"]

    def f(t, c):
        dt, dc = t - center, c
        r = math.sqrt(dt * dt + dc * dc)
        flange = min(abs(dt), abs(dc)) <= 0.5 and r <= radius + 2.2  # four flanges
        diag_flange = abs(abs(dt) - abs(dc)) <= 0.5 and r <= radius + 1.5 and "spikes" in st["style"]
        if r <= radius:
            lightness = (-dc - dt * 0.3) / radius
            if lightness > 0.55:
                return edge
            if lightness > 0.1:
                return light
            if lightness > -0.45:
                return mid
            return dark
        if flange or diag_flange:
            return light if dc < 0 or (dc == 0 and dt > 0) else dark
        return None

    return f


def hammer_head(st, t0, t1, half):
    dark, mid, light, edge = st["blade"]
    gd, gm, gl = st["guard"]

    def f(t, c):
        if t0 <= t <= t1 and abs(c) <= half:
            if abs(c) >= half - 1:
                return gd if c > 0 else gl  # striking faces
            if t == t1:
                return edge
            if t == t0:
                return dark
            if "blocky" in st["style"] and abs(c) % 4 == 2:
                return gm
            if "glow" in st["style"] and c == 0:
                return st["accent"][1]
            return light if c < 0 else mid
        if "spikes" in st["style"] and t1 < t <= t1 + 2 and abs(c) <= 1:
            return gl if c <= 0 else gd
        return None

    return f


def weapon_texture(tier, wtype):
    st = dict(TIER_STYLE[tier])
    st["name"] = tier
    if wtype == "dagger":
        cv = draw_blade_weapon(st, pommel=(3, 4), grip=(5, 8), guard=(9, 10, 3), blade=(11, 22), width=1, tip_len=2)
    elif wtype == "sword":
        cv = draw_blade_weapon(st, pommel=(1, 2), grip=(3, 7), guard=(8, 9, 4), blade=(10, 27), width=1, tip_len=2)
    elif wtype == "greatsword":
        cv = draw_blade_weapon(st, pommel=(0, 1), grip=(2, 7), guard=(8, 9, 5), blade=(10, 28), width=2, tip_len=1.5)
    elif wtype == "war_axe":
        cv = draw_hafted(st, 24, 8, axe_head(st, 17, 8.5, 1.5, 5.0, False))
    elif wtype == "battleaxe":
        cv = draw_hafted(st, 26, 9, axe_head(st, 18, 9.5, 1.5, 5.5, True, round_k=3.0))
    elif wtype == "mace":
        cv = draw_hafted(st, 21, 8, mace_head(st, 24, 3.2))
    elif wtype == "warhammer":
        cv = draw_hafted(st, 21, 9, hammer_head(st, 20, 26, 7))
    else:
        raise ValueError(wtype)
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255))
    return img


def bow_texture(tier, pull):
    """pull: -1 idle, 0..2 pulling stages."""
    st = TIER_STYLE[tier]
    wd, wm, wl = st["wood"] if tier in ("iron", "steel") else st["blade"][:3]
    hd, hm, hl = st["grip"]
    cv = Canvas()
    T0, C, B = 13.0, 12.0, 7.0
    P_ = [0, 2.5, 4.0, 5.5][pull + 1]
    B += P_ * 0.35
    string = (225, 222, 210, 255) if tier != "daedric" else (190, 60, 50, 255)
    for x, y, t, c in cv.each():
        if abs(c) > C:
            continue
        arc = T0 + B * (1 - (c / C) ** 2) - P_ * 0.25
        col = None
        if arc - 0.9 <= t <= arc + 1.1:
            if abs(c) <= 1:
                col = hl if t > arc else hm
            elif abs(c) >= C - 1.5:
                col = st["guard"][2] if tier not in ("iron", "steel") else wl
            else:
                col = wl if t > arc + 0.2 else (wm if c < 0 else wd)
                if "spikes" in st["style"] and abs(c) in (6, 7) and t > arc:
                    col = st["accent"][1] if st["accent"] else wl
                if "glow" in st["style"] and abs(c) == 4:
                    col = st["accent"][1]
        if col is None:
            sl = T0 - P_ * (1 - abs(c) / C)
            if abs(t - sl) <= (0.5 if P_ == 0 else 0.8) and abs(c) < C - 0.5:
                col = string
        if col is not None:
            cv.px[x, y] = col
            cv.roles[(x, y)] = "string" if col == string else "solid"
    if pull >= 0:
        tail = T0 - P_
        for t in range(int(math.ceil(tail)), 29):
            for c in (0, 1):
                p = cv.xy(t, c)
                if not p:
                    continue
                if t >= 26:
                    col = (200, 200, 205, 255) if c == 0 else (130, 130, 138, 255)
                elif t <= tail + 2:
                    col = (235, 235, 235, 255) if c == 0 else (190, 60, 50, 255)
                else:
                    col = (140, 100, 60, 255) if c == 0 else (100, 70, 40, 255)
                if c == 1 and 3 <= t - tail and t < 26:
                    continue
                cv.px[p] = col
                cv.roles[p] = "arrow"
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255),
            skip=lambda x, y: cv.roles.get((x, y)) in ("string", "arrow"))
    # vanilla orientation: the belly faces the top-left (the display transforms of item/bow assume it)
    return img.transpose(0)


# ============================================================================ materials, ores, ingots

ORE_COLORS = {
    "corundum": P("6e3a22", "b0643c", "e0915c", "f6c08c"),
    "orichalcum": P("3f5430", "6f8a54", "9fbb7a", "c8dca8"),
    "moonstone": P("7d93ad", "b8cadd", "e6f0fa", "ffffff"),
    "malachite": P("17553a", "2f9a5a", "5fd08a", "a8f0c0"),
    "quicksilver": P("6b747d", "aab3bb", "dde4ea", "ffffff"),
    "ebony": P("08070a", "1d1922", "3b3346", "7a6c90"),
    "silver": P("7c8389", "c0c6cc", "e8ecef", "ffffff"),
}
INGOT_COLORS = dict(ORE_COLORS)
INGOT_COLORS.update({
    "steel": P("4c5560", "7d8a96", "b3c0cb", "eaf2f8"),
    "dwarven_metal": P("6b4214", "a86f2c", "d6a154", "f7da94"),
    "ebony": P("0f0d12", "24202b", "403847", "7b6d8f"),
})


def stone_bg(seed, deep=False):
    rnd = random.Random(seed)
    img = new()
    px = img.load()
    if deep:
        base = [hexc("3b3b41"), hexc("47474e"), hexc("52525a"), hexc("5f5f68"), hexc("2f2f34")]
    else:
        base = [hexc("6c6c6c"), hexc("7a7a7a"), hexc("878787"), hexc("959595"), hexc("5e5e5e")]
    for y in range(16):
        for x in range(16):
            v = rnd.random()
            if deep:
                band = (y + (x // 5)) % 4 == 0
                idx = 4 if band and v < 0.5 else (1 if v < 0.45 else 2 if v < 0.8 else 0 if v < 0.93 else 3)
            else:
                idx = 1 if v < 0.4 else 2 if v < 0.7 else 0 if v < 0.85 else 3 if v < 0.95 else 4
            px[x, y] = base[idx]
    # a few blotches like vanilla stone
    for _ in range(6):
        cx, cy = rnd.randrange(16), rnd.randrange(16)
        col = base[rnd.choice([0, 3])]
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            if rnd.random() < 0.8:
                px[(cx + dx) % 16, (cy + dy) % 16] = col
    return img


def ore_block(ore, deep):
    img = stone_bg(sum(map(ord, ore)) + (7 if deep else 0), deep)
    px = img.load()
    dark, mid, light, hi = ORE_COLORS[ore]
    rnd = random.Random(sum(map(ord, ore)) * 31 + (1 if deep else 0))
    clusters = [(3, 3), (10, 2), (12, 9), (4, 11), (8, 7)]
    for i, (cx, cy) in enumerate(clusters):
        if i == 4 and ore in ("ebony", "malachite", "moonstone"):
            continue
        cx += rnd.randint(-1, 1)
        cy += rnd.randint(-1, 1)
        shape = rnd.choice([
            [(0, 0), (1, 0), (0, 1), (1, 1), (2, 1)],
            [(0, 0), (1, 0), (1, 1), (2, 1), (1, 2)],
            [(1, 0), (0, 1), (1, 1), (2, 1), (1, 2), (2, 2)],
            [(0, 0), (1, 0), (2, 0), (1, 1)],
        ])
        cells = {(cx + dx, cy + dy) for dx, dy in shape if 0 <= cx + dx < 16 and 0 <= cy + dy < 16}
        for (x, y) in cells:
            up = (x, y - 1) in cells
            lf = (x - 1, y) in cells
            dn = (x, y + 1) in cells
            col = mid
            if not up and not lf:
                col = hi if ore not in ("ebony",) else light
            elif not up or not lf:
                col = light
            elif not dn:
                col = dark
            px[x, y] = col
        # shadow under the vein
        for (x, y) in cells:
            for sx, sy in ((x + 1, y + 1), (x, y + 1)):
                if (sx, sy) not in cells and 0 <= sx < 16 and 0 <= sy < 16:
                    px[sx, sy] = shade(px[sx, sy], 0.72)
    return img


RAW_SHAPE = [
    "................",
    "................",
    "................",
    "......####......",
    "....########....",
    "...##########...",
    "..############..",
    "..#############.",
    ".##############.",
    ".##############.",
    ".#############..",
    "..###########...",
    "...#########....",
    ".....#####......",
    "................",
    "................",
]


def raw_ore(ore):
    pal = ORE_COLORS[ore]
    mask = grid(RAW_SHAPE)
    img = shade_mask(mask, pal, seed=len(ore), noise=0.9)
    px = img.load()
    rnd = random.Random(ore)
    # lumpy crevices and stone bits
    for _ in range(7):
        x, y = rnd.randrange(3, 13), rnd.randrange(5, 12)
        if mask[y][x]:
            px[x, y] = pal[0]
            if mask[y - 1][x - 1]:
                px[x - 1, y - 1] = pal[3]
    for _ in range(3):
        x, y = rnd.randrange(3, 13), rnd.randrange(5, 12)
        if mask[y][x]:
            px[x, y] = hexc("7a7a7a")
    outline(img, color_of=lambda n: with_alpha(shade(pal[0], 0.55), 255))
    return img


INGOT = [
    "................",
    "................",
    "................",
    "................",
    "........ooooooo.",
    "......oohhhhhhto",
    "....oohttttttsfo",
    "..oohttttttssffo",
    ".ohttttttssffffo",
    ".ossssssffffffo.",
    ".offffffffffoo..",
    ".offffffffoo....",
    "..oooooooo......",
    "................",
    "................",
    "................",
]


def ingot(name):
    dark, mid, light, hi = INGOT_COLORS[name]
    img = paint_grid(INGOT, {"o": shade(dark, 0.55), "h": hi, "t": light, "s": mid, "f": mix(mid, dark, 0.45)})
    px = img.load()
    # top-face sheen and a stamp mark on the front face
    for x, y in ((6, 7), (7, 7), (9, 6)):
        px[x, y] = hi
    for x, y in ((4, 10), (5, 10)):
        px[x, y] = shade(mid, 0.8)
    if name in ("moonstone", "malachite"):  # refined gems: faceted glint
        px[11, 7] = hi
        px[3, 9] = hi
    return img


def gem(color, flawless):
    dark, mid, light, hi = color
    rows = [
        "................",
        "................",
        "................",
        "....oooooooo....",
        "...otthhhhtto...",
        "..othhttttthto..",
        ".otllllllllllto.",
        ".offllmmmmllffo.",
        "..offlmmmmlffo..",
        "...offmmmmffo...",
        "....offmmffo....",
        ".....offffo.....",
        "......offo......",
        ".......oo.......",
        "................",
        "................",
    ]
    if not flawless:
        rows = rows[1:] + ["................"]
        rows = ["................"] + [r[1:] + "." if i > 0 else r for i, r in enumerate(rows[1:])]
        rows = [r.replace("oooooooo", ".oooooo.") if "oooooooo" in r else r for r in rows]
    img = paint_grid(rows, {"o": shade(dark, 0.5), "t": light, "h": hi, "l": mix(light, mid, 0.5), "m": mid, "f": dark})
    px = img.load()
    if flawless:
        px[6, 5] = (255, 255, 255, 255)
        px[13, 3] = (255, 255, 255, 220)
        px[14, 2] = (255, 255, 255, 140)
        px[12, 2] = (255, 255, 255, 140)
        px[13, 1] = (255, 255, 255, 140)
    return img


def strip_mask(fn):
    return [[bool(fn(x, y)) for x in range(16)] for y in range(16)]


def item_ring(metal, stone):
    img = new()
    px = img.load()
    m = metal
    for y in range(16):
        for x in range(16):
            dx, dy = (x - 7.5) / 5.6, (y - 9.0) / 4.4
            r = math.hypot(dx, dy)
            if 0.66 <= r <= 1.0:
                ang = math.atan2(dy, dx)
                light = -math.sin(ang + 0.8)
                px[x, y] = m[3] if light > 0.6 else m[2] if light > 0.0 else m[1] if light > -0.6 else m[0]
    if stone:
        for (x, y), ci in {(6, 3): 2, (7, 3): 3, (8, 3): 2, (6, 4): 1, (7, 4): 2, (8, 4): 1, (7, 2): 3, (7, 5): 0}.items():
            px[x, y] = stone[ci]
    outline(img, color_of=lambda n: with_alpha(shade(m[0], 0.5), 255))
    return img


def item_necklace(metal, stone):
    img = new()
    px = img.load()
    m = metal
    pts = []
    for i in range(60):
        a = math.pi * (0.05 + 0.9 * i / 59)
        x = 7.5 - 6.2 * math.cos(a)
        y = 2.0 + 7.5 * math.sin(a)
        pts.append((int(round(x)), int(round(y))))
    for i, (x, y) in enumerate(pts):
        if 0 <= x < 16 and 0 <= y < 16:
            px[x, y] = m[2] if (x + y) % 2 else m[1]
    # pendant
    pend = {(7, 10): 2, (8, 10): 1, (6, 11): 2, (7, 11): 3, (8, 11): 2, (9, 11): 1, (7, 12): 1, (8, 12): 0, (7, 13): 0}
    for (x, y), ci in pend.items():
        px[x, y] = (stone or m)[ci]
    outline(img, color_of=lambda n: with_alpha(shade(m[0], 0.45), 255))
    return img


GOLD = P("8a5a10", "d29a22", "f4d050", "fff4a8")
SILVER = P("6c737a", "aab1b8", "dfe4e8", "ffffff")
GEMS = {"garnet": P("4a0c1e", "8c1a35", "c43a5a", "f08aa0"), "ruby": P("5a0808", "a8141a", "e0303a", "ff9a9a"),
        "sapphire": P("0c1a5a", "1f3aa8", "3f6ae0", "a8c0ff"), "diamond": P("6a90a8", "b8e0f0", "e8faff", "ffffff")}


def sprite(rows, palette, outline_color=None):
    img = paint_grid(rows, palette)
    if outline_color:
        outline(img, color_of=lambda n: outline_color)
    return img


def misc_items():
    out = {}
    # leather strips: coiled strap
    rows = [
        "................",
        "................",
        "................",
        "....mmmmmmm.....",
        "...mlllllllm....",
        "..mlddddddddm...",
        "..mlm.....mlm...",
        "..mld.mmm.dlm...",
        "..mld.mld.dlm...",
        "..mldmmld.dlm...",
        "...mllldd.dlm...",
        "....mddd..dlm...",
        "..........dlm.l.",
        "...........dlll.",
        "............dd..",
        "................",
    ]
    out["leather_strips"] = sprite(rows, {"m": hexc("6b4426"), "l": hexc("a8743f"), "d": hexc("4a2d17")}, hexc("2a180b"))
    # hide: stretched pelt
    pelt = strip_mask(lambda x, y: (abs(x - 7.5) / 6.5) ** 2 + (abs(y - 8) / 6.5) ** 2 <= 1.0
                      or (x in (1, 2, 13, 14) and y in (2, 3, 13, 14)))
    img = shade_mask(pelt, P("5a3a20", "8a5e36", "a8784a", "c49a68"), seed=3, noise=1.1)
    px = img.load()
    for y in range(5, 12):
        for x in range(5, 11):
            if pelt[y][x] and (x + y) % 3 == 0:
                px[x, y] = hexc("b88a58")
    outline(img, color_of=lambda n: hexc("2e1d0e"))
    out["hide"] = img
    # firewood: two split logs
    img = new()
    px = img.load()
    for log, (ox, oy) in enumerate(((2, 9), (5, 5))):
        for i in range(10):
            for j in range(3):
                x, y = ox + i, oy + j - i // 3
                if 0 <= x < 16 and 0 <= y < 16:
                    px[x, y] = hexc("6e4b2e") if j == 0 else hexc("8f673f") if j == 1 else hexc("4a3220")
        ex, ey = ox + 9, oy - 3
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (0, 2), (1, 2)):
            if 0 <= ex + dx < 16 and 0 <= ey + dy < 16:
                px[ex + dx, ey + dy] = hexc("c8a070") if (dx + dy) % 2 == 0 else hexc("a8804e")
    outline(img, color_of=lambda n: hexc("2a1a0e"))
    out["firewood"] = img
    # daedra heart: dark red with glowing veins
    heart = strip_mask(lambda x, y: ((x - 5) ** 2 + (y - 6) ** 2 <= 10 or (x - 10) ** 2 + (y - 6) ** 2 <= 10
                                     or (y >= 6 and abs(x - 7.5) <= (13.5 - y) * 0.95)) and 2 <= y <= 14 and 1 <= x <= 14)
    img = shade_mask(heart, P("2a0606", "5a0e10", "8a1c1c", "b83a30"), seed=4, noise=0.6)
    px = img.load()
    for (x, y) in ((6, 7), (7, 8), (8, 8), (9, 9), (7, 10), (6, 11), (10, 6), (11, 7), (5, 5)):
        if heart[y][x]:
            px[x, y] = hexc("ff6a2a")
    for (x, y) in ((7, 3), (8, 2), (8, 3)):
        px[x, y] = hexc("3a1010")
    outline(img, color_of=lambda n: hexc("140303"))
    out["daedra_heart"] = img
    # dragon bone: big diagonal bone
    cv = Canvas()
    for x, y, t, c in cv.each():
        end = (t <= 6 or t >= 24) and abs(c) <= 3 and not (abs(c) <= 1 and (t <= 3 or t >= 27))
        shaft = 5 <= t <= 25 and abs(c) <= 1
        if end or shaft:
            cv.px[x, y] = hexc("faf5e6") if c < 0 else hexc("ddd3bc") if c == 0 else hexc("a89c82")
    outline(cv.img, color_of=lambda n: hexc("4c4234"))
    out["dragon_bone"] = cv.img
    # dragon scales: overlapping scales
    img = new()
    px = img.load()
    for row, (oy, xs) in enumerate(((3, (4, 9)), (7, (2, 7, 12)), (11, (5, 10)))):
        for ox in xs:
            for y in range(oy, oy + 5):
                for x in range(ox - 2, ox + 3):
                    if 0 <= x < 16 and 0 <= y < 16 and abs(x - ox) <= 2 - max(0, y - oy - 2):
                        light = (oy + 1 - y) + (ox - x) * 0.5
                        px[x, y] = hexc("c8c0b0") if light > 0.5 else hexc("8a8478") if light > -1.5 else hexc("5a5650")
    outline(img, color_of=lambda n: hexc("2a2824"))
    out["dragon_scale"] = img
    # giant's toe
    toe = strip_mask(lambda x, y: ((x - 8) / 5.5) ** 2 + ((y - 9) / 5.5) ** 2 <= 1 and not (x > 10 and y < 6))
    img = shade_mask(toe, P("7a5a48", "b08a70", "d0aa8c", "e8c8ac"), seed=5, noise=0.5)
    px = img.load()
    for (x, y) in ((4, 5), (5, 4), (6, 4), (5, 5), (4, 6)):
        px[x, y] = hexc("e6dccc")
    for (x, y) in ((8, 10), (9, 11), (7, 12)):
        px[x, y] = hexc("8a6250")
    outline(img, color_of=lambda n: hexc("3a2a20"))
    out["giants_toe"] = img
    # troll fat: waxy yellow blob
    fat = strip_mask(lambda x, y: ((x - 7.5) / 6) ** 2 + ((y - 9) / 4.5) ** 2 <= 1)
    img = shade_mask(fat, P("a8944a", "d8c87a", "eee2a4", "fffbe0"), seed=6, noise=0.8)
    outline(img, color_of=lambda n: hexc("5a4a1e"))
    out["troll_fat"] = img
    # skeever tail: curling pink tail
    img = new()
    px = img.load()
    pts = []
    for i in range(40):
        a = i / 39 * math.pi * 1.6
        r = 6.0 - i / 39 * 3.0
        pts.append((7.5 + r * math.cos(a + 0.4), 8 + r * math.sin(a + 0.4) * 0.9))
    for i, (x, y) in enumerate(pts):
        xi, yi = int(round(x)), int(round(y))
        px[xi, yi] = hexc("d89a9a") if i % 4 else hexc("b07070")
        if i < 18:
            px[xi + 1, yi] = hexc("b07070")
    outline(img, color_of=lambda n: hexc("4a2828"))
    out["skeever_tail"] = img
    # draugr bone meal: grey-blue dust pile
    pile = strip_mask(lambda x, y: y >= 6 and abs(x - 7.5) <= (y - 5) * 1.1 and y <= 13)
    img = shade_mask(pile, P("6a7078", "a0a8b0", "c8ced4", "e8eef2"), seed=7, noise=1.4)
    px = img.load()
    rnd = random.Random(7)
    for _ in range(8):
        x, y = rnd.randrange(16), rnd.randrange(7, 14)
        if pile[y][x]:
            px[x, y] = hexc("3a4048")
    outline(img, color_of=lambda n: hexc("2a2e34"))
    out["bone_meal_draugr"] = img
    # dwarven scrap: bronze gear piece
    gear = strip_mask(lambda x, y: (3.0 <= math.hypot(x - 7.5, y - 7.5) <= 5.5)
                      or (math.hypot(x - 7.5, y - 7.5) <= 7.2 and (int(round(math.degrees(math.atan2(y - 7.5, x - 7.5)) / 45)) % 2 == 0)
                          and math.hypot(x - 7.5, y - 7.5) > 5.4))
    img = shade_mask(gear, INGOT_COLORS["dwarven_metal"], seed=8, noise=0.6)
    outline(img, color_of=lambda n: hexc("3a2408"))
    out["dwarven_scrap"] = img
    return out


# ============================================================================ armor icons

ARMOR_STYLE = {
    # base (dark, mid, light, hi), trim, accent
    "hide": dict(base=P("5a3a20", "8a5e36", "a8784a", "c49a68"), trim=hexc("d8c8a8"), accent=hexc("4a2d17"), fx="fur"),
    "elven": dict(base=P("7a5c18", "b88e2c", "e0bc56", "fff0a8"), trim=hexc("4a8238"), accent=hexc("79b852"), fx="elven"),
    "glass": dict(base=P("2a6e3a", "44a858", "86dc96", "d8ffe0"), trim=hexc("a3b3ad"), accent=hexc("e0ece8"), fx="glass"),
    "dragonscale": dict(base=P("4e4a44", "7c766c", "a8a092", "d0c8b8"), trim=hexc("3a2f26"), accent=hexc("b8ae96"), fx="scale"),
    "steel": dict(base=P("4c5560", "7d8a96", "b3c0cb", "eaf2f8"), trim=hexc("5c3b22"), accent=hexc("8a6a3a"), fx="plate"),
    "dwarven": dict(base=P("6b4214", "a86f2c", "d6a154", "f7da94"), trim=hexc("4a2e0e"), accent=hexc("f0c870"), fx="rivets"),
    "orcish": dict(base=P("323a2d", "515d47", "75846a", "9fae92"), trim=hexc("23281f"), accent=hexc("8a4420"), fx="spikes"),
    "ebony": dict(base=P("100e13", "221e27", "3b3443", "6e6180"), trim=hexc("0a090c"), accent=hexc("7a6c90"), fx="sheen"),
    "daedric": dict(base=P("100909", "201416", "35202a", "54303e"), trim=hexc("080404"), accent=hexc("e0281e"), fx="daedric"),
    "dragonplate": dict(base=P("857a66", "b8ad94", "ddd3bc", "faf5e6"), trim=hexc("4c3e2e"), accent=hexc("6f5c45"), fx="bone"),
}

ICON_SHAPES = {
    "helmet": [
        "................",
        "................",
        ".....oooooo.....",
        "...oobbbbbboo...",
        "..obbbbbbbbbbo..",
        "..obbbbbbbbbbo..",
        "..obbbbbbbbbbo..",
        "..otttttttttto..",
        "..obbbbbbbbbbo..",
        "..obbbo..obbbo..",
        "..obbo....obbo..",
        "..obbo....obbo..",
        "..oooo....oooo..",
        "................",
        "................",
        "................",
    ],
    "chestplate": [
        "................",
        ".oooo......oooo.",
        ".obbboooooobbbo.",
        ".obbbbttttbbbbo.",
        ".obbbbbbbbbbbbo.",
        ".oooobbbbbbbooo.",
        "....obbbbbbbo...",
        "....obbbbbbbo...",
        "....obbbbbbbo...",
        "....obbbbbbbo...",
        "....obbbbbbbo...",
        "....otttttttto..",
        "....oooooooooo..",
        "................",
        "................",
        "................",
    ],
    "leggings": [
        "................",
        "................",
        "...oooooooooo...",
        "...otttttttto...",
        "...obbbbbbbbo...",
        "...obbbbbbbbo...",
        "...obbboobbbo...",
        "...obbbo.obbbo..",
        "...obbbo.obbbo..",
        "...obbbo.obbbo..",
        "...obbbo.obbbo..",
        "...obbbo.obbbo..",
        "...oooo..oooo...",
        "................",
        "................",
        "................",
    ],
    "boots": [
        "................",
        "................",
        "................",
        "................",
        "................",
        "...oooo..oooo...",
        "...obbo..obbo...",
        "...obbo..obbo...",
        "...obbo..obbo...",
        "..oobbo.oobbo...",
        ".obbbbo.obbbbo..",
        ".otttto.otttto..",
        ".oooooo.oooooo..",
        "................",
        "................",
        "................",
    ],
}


def armor_icon(mat, piece):
    st = ARMOR_STYLE[mat]
    rows = ICON_SHAPES[piece]
    h, w = len(rows), len(rows[0])
    body = [[rows[y][x] in "bt" for x in range(w)] for y in range(h)]
    img = shade_mask(body, st["base"], seed=sum(map(ord, mat + piece)), noise=0.35 if st["fx"] in ("fur", "scale") else 0.0)
    px = img.load()
    dark, mid, light, hi = st["base"]
    fx = st["fx"]
    for y in range(h):
        for x in range(w):
            ch = rows[y][x]
            if ch == "t":
                px[x, y] = st["trim"]
            elif ch == "o":
                px[x, y] = with_alpha(shade(dark, 0.45), 255)
    # material details
    cells = [(x, y) for y in range(h) for x in range(w) if rows[y][x] == "b"]
    for (x, y) in cells:
        if fx == "plate" and y % 3 == 0:
            px[x, y] = mix(px[x, y], dark, 0.5)
        elif fx == "rivets" and (x + y * 3) % 7 == 0:
            px[x, y] = st["accent"]
        elif fx == "scale" and (x + (y % 2)) % 2 == 0:
            px[x, y] = mix(px[x, y], light, 0.35)
        elif fx == "glass" and (x + y) % 4 == 0:
            px[x, y] = st["accent"]
        elif fx == "elven" and (x - y) % 5 == 0:
            px[x, y] = st["trim"]
        elif fx == "sheen" and (x - y) % 6 == 0:
            px[x, y] = st["accent"]
        elif fx == "daedric" and ((x * 2 + y) % 7 == 0):
            px[x, y] = st["accent"]
        elif fx == "bone" and y % 4 == 1:
            px[x, y] = st["accent"]
        elif fx == "fur" and (x * 7 + y * 3) % 5 == 0:
            px[x, y] = mix(px[x, y], light, 0.5)
    # silhouettes: horns, spikes, crests
    if piece == "helmet":
        if mat in ("daedric", "orcish", "dragonplate"):
            for (x, y) in ((2, 2), (1, 1), (13, 2), (14, 1)) if mat != "orcish" else ((3, 2), (12, 2)):
                px[x, y] = st["accent"] if mat == "daedric" else (light if mat == "dragonplate" else st["trim"])
        if mat in ("elven", "glass", "ebony"):
            for x in range(6, 10):
                px[x, 2] = st["trim"] if mat != "ebony" else st["accent"]
        if mat == "hide":
            for x in (4, 6, 9, 11):
                px[x, 3] = st["trim"]
    if piece == "chestplate" and mat in ("daedric", "dragonplate", "orcish"):
        px[2, 0] = px[13, 0] = st["accent"] if mat != "dragonplate" else light
    return img


# ============================================================================ armor layers (64x32)

def surface(mat, face, u, v, fw, fh, part, rnd):
    """Color of an armor surface pixel. part in head/body/arm/leg/boot/belt."""
    st = ARMOR_STYLE[mat]
    dark, mid, light, hi = st["base"]
    fx = st["fx"]
    edge = u == 0 or v == 0 or u == fw - 1 or v == fh - 1
    col = mid
    n = rnd.random()
    if n < 0.18:
        col = light
    elif n < 0.3:
        col = mix(mid, dark, 0.5)
    if fx == "plate" or fx == "rivets" or fx == "spikes" or fx == "daedric" or fx == "sheen":
        if v % 4 == 0:
            col = light
        elif v % 4 == 3:
            col = dark
    if fx == "rivets" and v % 4 == 1 and u % 3 == 1:
        col = st["accent"]
    if fx == "scale":
        col = light if (u + (v // 2) % 2) % 2 == 0 and v % 2 == 0 else (mid if v % 2 == 0 else dark)
    if fx == "fur":
        col = mix(mid, light, rnd.random() * 0.6) if (u + v * 3) % 4 else dark
    if fx == "glass":
        col = with_alpha(mix(mid, light, 0.5 if (u + v) % 3 == 0 else 0.0), 255)
        if (u + v) % 5 == 0:
            col = st["accent"]
    if fx == "elven":
        if (u - v) % 6 == 0:
            col = st["trim"]
    if fx == "sheen" and (u - v) % 7 == 0:
        col = st["accent"]
    if fx == "daedric" and face == "front" and (u == fw // 2 or (v % 4 == 2 and (u + v) % 3 == 0)):
        col = st["accent"]
    if fx == "bone" and v % 3 == 0:
        col = st["accent"] if u % 2 else dark
    if edge:
        col = st["trim"] if fx not in ("glass", "elven") else (st["trim"] if fx == "glass" else hi)
    return col


def box(img, u0, v0, w, h, d, painter):
    faces = {
        "top": (u0 + d, v0, w, d),
        "bottom": (u0 + d + w, v0, w, d),
        "right": (u0, v0 + d, d, h),
        "front": (u0 + d, v0 + d, w, h),
        "left": (u0 + d + w, v0 + d, d, h),
        "back": (u0 + d + w + d, v0 + d, w, h),
    }
    px = img.load()
    for face, (fx, fy, fw, fh) in faces.items():
        for v in range(fh):
            for u in range(fw):
                c = painter(face, u, v, fw, fh)
                if c is not None:
                    px[fx + u, fy + v] = c


def armor_layers(mat):
    rnd = random.Random(mat)
    st = ARMOR_STYLE[mat]
    heavy = mat in ("steel", "dwarven", "orcish", "ebony", "daedric", "dragonplate")

    def paint(part, rows=None):
        def f(face, u, v, fw, fh):
            if rows is not None and face not in ("top", "bottom"):
                if v < rows[0] or v > rows[1]:
                    return None
                vv, hh = v - rows[0], rows[1] - rows[0] + 1
            else:
                vv, hh = v, fh
            if rows is not None and face == "top":
                return None
            if part == "head" and face == "front":
                if heavy:
                    # closed helm: T-shaped visor (eye slit + mouth slit)
                    if (v == 3 and 1 <= u <= 6 and u not in (3, 4)) or (4 <= v <= 6 and u in (3, 4)):
                        return None
                elif 3 <= v <= 7 and 1 <= u <= 6:
                    return None  # open face
            if part == "head" and face == "bottom":
                return None
            return surface(mat, face, u, vv, fw, hh, part, rnd)

        return f

    l1 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    box(l1, 0, 0, 8, 8, 8, paint("head"))  # helmet
    box(l1, 16, 16, 8, 12, 4, paint("body"))  # cuirass
    box(l1, 40, 16, 4, 12, 4, paint("arm", (0, 9) if not heavy else None))  # pauldrons/sleeves
    box(l1, 0, 16, 4, 12, 4, paint("boot", (7, 11)))  # boots
    # boots soles
    px = l1.load()
    for u in range(4):
        for v in range(4):
            px[8 + u, 16 + v] = shade(st["base"][0], 0.6)
    # helmet crests / horns on the hat layer
    if mat in ("daedric", "orcish", "dragonplate", "elven"):
        hat = ARMOR_STYLE[mat]
        col = hat["accent"] if mat != "dragonplate" else hat["base"][3]
        for (x, y) in ((33, 9), (34, 9), (33, 10), (54, 9), (55, 9), (54, 10)) if mat != "elven" else ((44, 1), (44, 2), (43, 1)):
            px[x, y] = col
    l2 = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    box(l2, 0, 16, 4, 12, 4, paint("leg", (0, 9)))  # greaves
    box(l2, 16, 16, 8, 12, 4, paint("belt", (8, 11)))  # waist
    return l1, l2


# ============================================================================ station blocks

def bricks(seed, base, mortar, rows_h=4):
    rnd = random.Random(seed)
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            off = 4 if (y // rows_h) % 2 else 0
            if y % rows_h == rows_h - 1 or (x + off) % 8 == 7:
                px[x, y] = mortar
            else:
                v = rnd.random()
                px[x, y] = base[1] if v < 0.5 else base[2] if v < 0.75 else base[0]
                if y % rows_h == 0:
                    px[x, y] = base[2]
    return img


def wood_planks(seed, pal, vertical=False):
    rnd = random.Random(seed)
    img = new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            a, b = (x, y) if not vertical else (y, x)
            if b % 4 == 3:
                c = pal[0]
            else:
                c = pal[1] if rnd.random() < 0.7 else pal[2]
                if (a + b * 5) % 11 == 0:
                    c = pal[0]
            px[x, y] = c
    return img


STONE = P("4e4e52", "6a6a6e", "86868a")
MORTAR = hexc("38383c")
WOOD = P("4a3220", "6e4b2e", "8f673f")
DARKWOOD = P("33231a", "4d3526", "674834")


def glow(px, x, y, t):
    cols = [hexc("5a1a06"), hexc("b8400c"), hexc("f07a18"), hexc("ffd25a")]
    px[x, y] = cols[max(0, min(3, t))]


def station_textures():
    out = {}
    # ---------------- blacksmith forge
    side = bricks(11, STONE, MORTAR)
    px = side.load()
    for x in range(16):
        px[x, 0] = hexc("2c2c30")
    out["blacksmith_forge_side"] = side
    front = bricks(12, STONE, MORTAR)
    px = front.load()
    for y in range(6, 15):
        for x in range(3, 13):
            d = math.hypot(x - 7.5, max(0, 9 - y))
            if y >= 9 or d <= 4.6:
                px[x, y] = hexc("140c08")
    for x in range(3, 13):
        for y in (12, 13, 14):
            glow(px, x, y, 3 - abs(x - 7) // 2 - (y == 12) + (y == 14) * 0)
    for (x, y) in ((5, 11), (9, 11), (7, 10)):
        glow(px, x, y, 2)
    for x in range(2, 14):
        px[x, 15] = hexc("3a3a3e")
    out["blacksmith_forge_front"] = front
    top = bricks(13, STONE, MORTAR)
    px = top.load()
    for y in range(3, 13):
        for x in range(3, 13):
            r = math.hypot(x - 7.5, y - 7.5)
            px[x, y] = hexc("1a1210") if r > 4.2 else hexc("2a1a14")
            if r <= 4.2 and (x * 3 + y * 5) % 4 == 0:
                glow(px, x, y, 3 if r < 2 else 2 if r < 3.2 else 1)
    out["blacksmith_forge_top"] = top
    out["blacksmith_forge_bottom"] = bricks(14, STONE, MORTAR)
    # ---------------- smelter
    clay = P("6a4a3a", "8a6450", "a87e66")
    side = bricks(21, clay, hexc("4a3428"), rows_h=5)
    out["smelter_side"] = side
    front = bricks(22, clay, hexc("4a3428"), rows_h=5)
    px = front.load()
    for y in range(7, 13):
        for x in range(4, 12):
            px[x, y] = hexc("140c08")
    for x in range(4, 12):
        glow(px, x, 12, 3)
        glow(px, x, 11, 2 if x % 2 else 1)
    for y in range(12, 16):  # spout of molten metal
        glow(px, 7, y, 3)
        glow(px, 8, y, 2)
    out["smelter_front"] = front
    top = bricks(23, clay, hexc("4a3428"), rows_h=5)
    px = top.load()
    for y in range(2, 14):
        for x in range(2, 14):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 5.8:
                px[x, y] = hexc("3a2a22")
            if r <= 4.5:
                glow(px, x, y, 3 if r < 2.0 else 2 if (x + y) % 3 else 1)
    out["smelter_top"] = top
    out["smelter_bottom"] = bricks(24, clay, hexc("4a3428"), rows_h=5)
    # ---------------- tanning rack
    hide_pal = P("8a5e36", "a8784a", "c49a68")
    front = new()
    px = front.load()
    rnd = random.Random(31)
    for y in range(16):
        for x in range(16):
            if x in (0, 1, 14, 15) or y in (0, 1):
                px[x, y] = WOOD[1] if (x + y) % 5 else WOOD[0]
                if x in (1, 14) or y == 1:
                    px[x, y] = WOOD[0]
            elif 3 <= x <= 12 and 3 <= y <= 14:
                px[x, y] = hide_pal[1] if rnd.random() < 0.6 else hide_pal[2] if rnd.random() < 0.5 else hide_pal[0]
            else:
                px[x, y] = DARKWOOD[0]
            if (x == 2 or x == 13) and y % 3 == 0 and 3 <= y <= 14:
                px[x, y] = hexc("d8c8a8")  # lacing
    out["tanning_rack_front"] = front
    out["tanning_rack_side"] = front.copy()
    out["tanning_rack_top"] = wood_planks(32, WOOD)
    out["tanning_rack_bottom"] = wood_planks(33, DARKWOOD)
    # ---------------- grindstone wheel
    front = wood_planks(41, DARKWOOD, vertical=True)
    px = front.load()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r <= 6.9:
                v = (x * 7 + y * 13) % 5
                px[x, y] = hexc("8a8a86") if v < 2 else hexc("9c9c96") if v < 4 else hexc("74746f")
                if r >= 6.0:
                    px[x, y] = hexc("5c5c58")
                if r <= 1.5:
                    px[x, y] = hexc("4a3220")
    out["grindstone_wheel_front"] = front
    side = wood_planks(42, DARKWOOD, vertical=True)
    px = side.load()
    for y in range(2, 14):
        for x in range(5, 11):
            px[x, y] = hexc("8a8a86") if (x + y) % 3 else hexc("74746f")
    for x in range(16):
        px[x, 7] = px[x, 8] = hexc("3a2a1c")
    out["grindstone_wheel_side"] = side
    top = wood_planks(43, WOOD)
    px = top.load()
    for y in range(1, 15):
        for x in range(6, 10):
            px[x, y] = hexc("8a8a86") if (y % 2) else hexc("74746f")
    out["grindstone_wheel_top"] = top
    out["grindstone_wheel_bottom"] = wood_planks(44, DARKWOOD)
    # ---------------- armor workbench
    top = wood_planks(51, WOOD)
    px = top.load()
    for (x, y) in [(3, 3), (4, 4), (5, 5), (6, 6), (7, 7), (2, 3), (3, 2), (2, 2)]:  # hammer
        px[x, y] = hexc("8f673f") if x > 3 else hexc("5a5a5e")
    for (x, y) in [(10, 4), (11, 4), (12, 4), (10, 5), (11, 5), (12, 5), (11, 6), (11, 7), (11, 8), (11, 9), (11, 10)]:
        px[x, y] = hexc("6a6a6e")
    for x in range(3, 13):
        for y in (11, 12, 13):
            px[x, y] = hexc("7d8a96") if y != 13 else hexc("4c5560")  # steel plate on the bench
    out["armor_workbench_top"] = top
    side = wood_planks(52, DARKWOOD)
    px = side.load()
    for x in range(16):
        px[x, 0] = px[x, 1] = WOOD[2]
        px[x, 2] = WOOD[0]
    for y in range(3, 16):
        for x in (1, 2, 13, 14):
            px[x, y] = WOOD[1]
    out["armor_workbench_side"] = side
    front = side.copy()
    px = front.load()
    for y in range(5, 12):
        for x in range(4, 12):
            px[x, y] = hexc("7d8a96") if (x + y) % 4 else hexc("b3c0cb")
    for x in range(4, 12):
        px[x, 5] = hexc("5c3b22")
    out["armor_workbench_front"] = front
    out["armor_workbench_bottom"] = wood_planks(53, DARKWOOD)
    # ---------------- homestead drafting table (blueprint pinned on a plank desk)
    top = wood_planks(61, WOOD)
    px = top.load()
    paper, ink = hexc("d8ccae"), hexc("3c5a8a")
    for y in range(2, 14):
        for x in range(2, 14):
            px[x, y] = paper if (x * 5 + y * 3) % 7 else hexc("cbbf9f")
    for x in range(4, 12):  # floor plan: outer walls, an inner wall with a door gap
        px[x, 4] = px[x, 11] = ink
    for y in range(4, 12):
        px[4, y] = px[11, y] = ink
    for y in range(4, 12):
        if y not in (7, 8):
            px[8, y] = ink
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):  # pins
        px[x, y] = hexc("8a2a1e")
    for i in range(5):  # quill lying on the corner
        px[10 + i // 2, 13 - i] = hexc("f0ece0") if i else hexc("2a2a2a")
    out["drafting_table_top"] = top
    side = wood_planks(62, DARKWOOD)
    px = side.load()
    for x in range(16):
        px[x, 0] = px[x, 1] = WOOD[2]
        px[x, 2] = WOOD[0]
    for y in range(3, 16):
        for x in (1, 2, 13, 14):
            px[x, y] = WOOD[1]
    out["drafting_table_side"] = side
    front = side.copy()
    px = front.load()
    for y in range(5, 10):  # rolled-up plans in a cubby
        for x in range(4, 12):
            px[x, y] = DARKWOOD[0]
    for x in range(4, 12):
        for y in (6, 7, 8):
            px[x, y] = paper if (x + y) % 3 else hexc("bfb393")
    out["drafting_table_front"] = front
    out["drafting_table_bottom"] = wood_planks(63, DARKWOOD)
    return out


# ============================================================================ main

TIERS = ["iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric", "dragonbone"]
MELEE = ["dagger", "sword", "war_axe", "mace", "greatsword", "battleaxe", "warhammer"]
ORES = ["corundum", "orichalcum", "moonstone", "malachite", "quicksilver", "ebony", "silver"]
INGOT_NAMES = {"corundum": "corundum_ingot", "orichalcum": "orichalcum_ingot", "moonstone": "refined_moonstone",
               "malachite": "refined_malachite", "quicksilver": "quicksilver_ingot", "ebony": "ebony_ingot",
               "silver": "silver_ingot", "steel": "steel_ingot", "dwarven_metal": "dwarven_metal_ingot"}
ARMOR_MATS = list(ARMOR_STYLE.keys())
PIECES = ["helmet", "chestplate", "leggings", "boots"]


def main():
    # weapons, bows, armour icons and armour layers are drawn by gear.py (tools/textures/gear_catalogue.py)
    for ore in ORES:
        save(ore_block(ore, False), BLOCK, f"{ore}_ore")
        save(ore_block(ore, True), BLOCK, f"deepslate_{ore}_ore")
        save(raw_ore(ore), ITEM, f"raw_{ore}")
    for key, name in INGOT_NAMES.items():
        save(ingot(key), ITEM, name)
    for g in ("garnet", "ruby", "sapphire"):
        save(gem(GEMS[g], False), ITEM, g)
        save(gem(GEMS[g], True), ITEM, f"flawless_{g}")
    save(gem(GEMS["diamond"], True), ITEM, "flawless_diamond")
    save(item_ring(SILVER, None), ITEM, "silver_ring")
    save(item_ring(GOLD, None), ITEM, "gold_ring")
    save(item_ring(SILVER, GEMS["garnet"]), ITEM, "silver_garnet_ring")
    save(item_ring(GOLD, GEMS["diamond"]), ITEM, "gold_diamond_ring")
    save(item_necklace(SILVER, None), ITEM, "silver_necklace")
    save(item_necklace(GOLD, None), ITEM, "gold_necklace")
    save(item_necklace(GOLD, GEMS["ruby"]), ITEM, "gold_ruby_necklace")
    for name, img in misc_items().items():
        save(img, ITEM, name)
    for name, img in station_textures().items():
        save(img, BLOCK, name)
    print(f"wrote {len(PREVIEW)} textures")
    if "--preview" in sys.argv:
        path = sys.argv[sys.argv.index("--preview") + 1]
        cols = 16
        icons = [(n, i) for n, i in PREVIEW if i.size == (16, 16)]
        rows = (len(icons) + cols - 1) // cols
        scale = 6
        sheet = Image.new("RGBA", (cols * 18 * scale, rows * 18 * scale), (60, 60, 70, 255))
        for k, (n, i) in enumerate(icons):
            x, y = (k % cols) * 18 * scale, (k // cols) * 18 * scale
            sheet.alpha_composite(i.resize((16 * scale, 16 * scale), Image.NEAREST), (x + scale, y + scale))
        sheet.save(path)
        layers = [(n, i) for n, i in PREVIEW if i.size == (64, 32)]
        lsheet = Image.new("RGBA", (4 * 66 * 4, ((len(layers) + 3) // 4) * 34 * 4), (60, 60, 70, 255))
        for k, (n, i) in enumerate(layers):
            x, y = (k % 4) * 66 * 4, (k // 4) * 34 * 4
            lsheet.alpha_composite(i.resize((64 * 4, 32 * 4), Image.NEAREST), (x + 4, y + 4))
        lsheet.save(path.replace(".png", "_layers.png"))


if __name__ == "__main__":
    main()
