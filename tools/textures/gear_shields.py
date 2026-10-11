"""Skyrim shields as 3D item models (JSON elements) with 32x32 textures.

The plate is built row by row from a 12 x 22 silhouette (round, buckler, kite, heater, tower, spiked, oval, leaf...),
so round shields have round edges in 3D, plus a boss, a handle and per-style extras (spikes, a dragon crest). The
elements sit in the vanilla shield's frame (plate x -6..6, y -11..11, z 1..2; handle behind it), so the vanilla
shield's display transforms (held, blocking, GUI) apply unchanged.
"""
import math

from PIL import Image

import gear_armor as ga
from gear_px import hexc, mix, rnd, shade

W, H = 12, 22          # plate silhouette size (texels = model units)
TEX = 32               # texture size; UV units = TEX / 16 texels
FRONT = (1, 1)         # texture origin of the front face
BACK = (14, 1)         # texture origin of the back face
RIM = (27, 1)          # 4 x 22 rim strip
BOSS = (27, 24)        # 4 x 4 boss
WOOD = (14, 24)        # handle

SHAPES = {
    "round": lambda x, y: (x - 5.5) ** 2 / 6.3 ** 2 + (y - 10.5) ** 2 / 6.3 ** 2 <= 1.0,
    "buckler": lambda x, y: (x - 5.5) ** 2 / 5.2 ** 2 + (y - 10.5) ** 2 / 5.2 ** 2 <= 1.0,
    "oval": lambda x, y: (x - 5.5) ** 2 / 6.2 ** 2 + (y - 10.5) ** 2 / 10.6 ** 2 <= 1.0,
    "kite": lambda x, y: y >= 1 and (y <= 7 or abs(x - 5.5) <= 6.0 * max(0.0, (21.5 - y) / 14.5) ** 0.85)
                         and (y >= 3 or abs(x - 5.5) <= 6.0 - (3 - y) * 1.2),
    "heater": lambda x, y: 2 <= y <= 19 and (y <= 10 or abs(x - 5.5) <= 6.0 * math.sqrt(max(0.0, 1 - ((y - 10) / 9.6) ** 2))),
    "tower": lambda x, y: 0 <= y <= 21 and not ((x in (0, 11)) and (y in (0, 21))),
    "spiked": lambda x, y: (2 <= y <= 19 and (y <= 10 or abs(x - 5.5) <= 6.0 * math.sqrt(max(0.0, 1 - ((y - 10) / 9.6) ** 2))))
                           or (y in (0, 1) and x in (0, 1, 10, 11)) or (y == 1 and x in (5, 6)),
    "leaf": lambda x, y: abs(x - 5.5) <= 6.2 * math.sin(math.pi * min(1.0, max(0.0, (y + 0.5) / 22.0))) ** 0.8,
    "dwemer": lambda x, y: (x - 5.5) ** 2 / 6.3 ** 2 + (y - 10.5) ** 2 / 6.3 ** 2 <= 1.0
                           or (abs(x - 5.5) <= 1.5 and 3 <= y <= 18),
}

# per set: silhouette, face material, emblem, boss, extras
SHIELDS = {
    "hide": dict(shape="round", face="wood_hide", emblem=None, boss=True),
    "elven": dict(shape="leaf", face="metal", emblem="leaf", boss=False),
    "glass": dict(shape="kite", face="crystal", emblem=None, boss=False),
    "dragonscale": dict(shape="kite", face="scale", emblem=None, boss=False, spikes=True),
    "imperial_light": dict(shape="round", face="wood_cloth", emblem="dragon", boss=True),
    "iron": dict(shape="round", face="wood_banded", emblem=None, boss=True),
    "banded_iron": dict(shape="heater", face="wood_banded", emblem=None, boss=True),
    "steel": dict(shape="heater", face="metal", emblem=None, boss=True),
    "dwarven": dict(shape="dwemer", face="metal", emblem="rings", boss=True),
    "orcish": dict(shape="spiked", face="metal", emblem="tusks", boss=True),
    "ebony": dict(shape="heater", face="metal", emblem="ridge", boss=False),
    "daedric": dict(shape="spiked", face="metal", emblem="glow", boss=True, spikes=True),
    "dragonplate": dict(shape="heater", face="bone", emblem="ribs", boss=False, spikes=True),
    "nordic": dict(shape="round", face="metal", emblem="knots", boss=True),
    "bonemold": dict(shape="oval", face="metal", emblem=None, boss=True),
    "blades": dict(shape="round", face="metal", emblem="dragon", boss=False),
    "imperial": dict(shape="tower", face="cloth", emblem="dragon", boss=True),
    "stormcloak": dict(shape="round", face="wood_cloth", emblem="bear", boss=True),
    "dawnguard": dict(shape="heater", face="metal", emblem="sun", boss=False),
    "bosmer": dict(shape="leaf", face="wood_hide", emblem="leaf", boss=False),
    "ra_gada": dict(shape="buckler", face="metal", emblem="star", boss=True),
    "breton": dict(shape="kite", face="cloth", emblem="lion", boss=False),
    "khajiit": dict(shape="round", face="metal", emblem="moon", boss=True),
    "orsinium": dict(shape="spiked", face="metal", emblem="tusks", boss=True, spikes=True),
    "atmoran": dict(shape="round", face="wood_banded", emblem=None, boss=True),
    "maormer": dict(shape="oval", face="crystal", emblem="wave", boss=False),
}

EMBLEM_ART = {
    "dragon": ["..a..a..", ".aaaaaa.", "..aaaa..", "...aa...", "..a..a..", ".a....a."],
    "bear": ["aa..aa", "aaaaaa", ".aaaa.", "aaaaaa", "a.aa.a"],
    "sun": [".a.a.a.", "..aaa..", "aaaaaaa", "..aaa..", ".a.a.a."],
    "lion": ["..aa..", ".aaaa.", "aaaaa.", ".aaaa.", ".a..a."],
    "moon": [".aaa.", "aa...", "a....", "aa...", ".aaa."],
    "star": ["..a..", ".aaa.", "aaaaa", ".aaa.", "..a.."],
    "leaf": ["..a..", ".aaa.", ".aaa.", "aaaaa", ".aaa.", "..a..", "..a.."],
    "tusks": ["a....a", "a....a", ".a..a.", "..aa.."],
    "wave": ["aa..aa", "..aa..", "aa..aa"],
    "rings": [".aaaa.", "a....a", "a.aa.a", "a....a", ".aaaa."],
}


def _mask(shape):
    f = SHAPES[shape]
    return [[bool(f(x, y)) for x in range(W)] for y in range(H)]


def texture(set_id):
    L = ga.LOOKS[set_id]
    spec = SHIELDS[set_id]
    mask = _mask(spec["shape"])
    img = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    px = img.load()
    r = rnd(sum(map(ord, set_id)))
    m = ga.mat_ramp(L, "plate")
    t = ga.mat_ramp(L, "trim")
    wood = [hexc("4a3220"), hexc("6e4b2e"), hexc("8f673f"), hexc("a8805a")]
    lea = ga.mat_ramp(L, "leather")
    face = spec["face"]

    def inside(x, y):
        return 0 <= x < W and 0 <= y < H and mask[y][x]

    for y in range(H):
        for x in range(W):
            if not mask[y][x]:
                continue
            edge = not (inside(x - 1, y) and inside(x + 1, y) and inside(x, y - 1) and inside(x, y + 1))
            edge2 = not (inside(x - 2, y) and inside(x + 2, y) and inside(x, y - 2) and inside(x, y + 2))
            if face.startswith("wood"):
                base = wood[1] if x % 3 else wood[0]
                if r.random() < 0.15:
                    base = wood[2]
                if face == "wood_hide":
                    hide = ga.mat_ramp(L, "leather")
                    base = hide[1] if r.random() > 0.2 else hide[2]
                if face == "wood_cloth":
                    cl = ga.mat_ramp(L, "cloth")
                    base = cl[1] if (x + y) % 5 else cl[0]
                if face == "wood_banded" and y in (6, 7, 14, 15):
                    base = m[1] if y in (6, 14) else m[0]
            elif face == "metal":
                base = m[1] if (x + y) % 9 else m[2]
                if y < 3:
                    base = m[2]
            elif face == "crystal":
                g = L["glow"] or L["accent"]
                base = g[2] if (x + 2 * y) % 7 == 0 else (g[1] if (x - y) % 4 else mix(g[1], g[0], 0.5))
            elif face == "scale":
                yy = y + (1 if (x // 2) % 2 else 0)
                base = m[2] if yy % 2 == 0 else m[0]
            elif face == "bone":
                base = m[2] if y % 3 else m[0]
            elif face == "cloth":
                cl = ga.mat_ramp(L, "cloth")
                base = cl[1] if x % 4 else cl[0]
            else:
                base = m[1]
            col = base
            if edge:
                col = t[2] if (x + y) % 2 else t[1]
            elif edge2 and face not in ("crystal",):
                col = mix(base, t[0], 0.35)
            px[FRONT[0] + x, FRONT[1] + y] = col
            # back: wood with leather straps
            bcol = wood[1] if (x % 3) else wood[0]
            if y in (8, 9, 13, 14):
                bcol = lea[1]
            if edge:
                bcol = t[0]
            px[BACK[0] + (W - 1 - x), BACK[1] + y] = bcol
    # emblem
    em = spec.get("emblem")
    acc = L["accent"]
    if em in EMBLEM_ART:
        art = EMBLEM_ART[em]
        x0 = (W - len(art[0])) // 2
        y0 = 7 if spec["shape"] not in ("round", "buckler") else 8
        col = acc[2] if em not in ("dragon", "sun", "lion", "moon", "star") else t[2]
        if set_id == "stormcloak":
            col = hexc("e8e8f0")
        if set_id == "imperial":
            col = t[2]
        for yy, row in enumerate(art):
            for xx, ch in enumerate(row):
                if ch == "a" and inside(x0 + xx, y0 + yy):
                    px[FRONT[0] + x0 + xx, FRONT[1] + y0 + yy] = col
    elif em == "glow":
        g = L["glow"]
        for y in range(3, 19):
            for x in (3, 8):
                if inside(x, y) and y % 3:
                    px[FRONT[0] + x, FRONT[1] + y] = g[1]
        for x in range(3, 9):
            px[FRONT[0] + x, FRONT[1] + 10] = g[2]
    elif em == "ridge":
        for y in range(2, 20):
            if inside(5, y):
                px[FRONT[0] + 5, FRONT[1] + y] = m[3]
                px[FRONT[0] + 6, FRONT[1] + y] = m[0]
    elif em == "ribs":
        tt = L["trim"]
        for y in (5, 8, 11, 14):
            for x in range(1, 11):
                if inside(x, y) and x not in (5, 6):
                    px[FRONT[0] + x, FRONT[1] + y] = tt[1]
    elif em == "knots":
        for (x, y) in ((3, 6), (8, 6), (2, 10), (9, 10), (3, 14), (8, 14), (5, 4), (6, 16)):
            if inside(x, y):
                px[FRONT[0] + x, FRONT[1] + y] = t[2]
    # rim strip, boss and handle
    for y in range(22):
        for x in range(4):
            px[RIM[0] + x, RIM[1] + y] = t[1] if (x + y) % 3 else t[0]
    for y in range(4):
        for x in range(4):
            px[BOSS[0] + x, BOSS[1] + y] = t[2] if (x + y) < 3 else (t[1] if (x + y) < 5 else t[0])
    for y in range(7):
        for x in range(12):
            px[WOOD[0] + x, WOOD[1] + y] = wood[1] if (x + y) % 4 else wood[0]
    return img


def _uv(x, y, w, h):
    s = 16 / TEX
    return [round(x * s, 4), round(y * s, 4), round((x + w) * s, 4), round((y + h) * s, 4)]


def model(set_id, tex_ref):
    spec = SHIELDS[set_id]
    mask = _mask(spec["shape"])
    elements = []
    # merge consecutive rows with the same span
    rows = []
    for y in range(H):
        xs = [x for x in range(W) if mask[y][x]]
        if not xs:
            continue
        span = (min(xs), max(xs) + 1)
        if rows and rows[-1][1] == span and rows[-1][0] + rows[-1][2] == y:
            rows[-1][2] += 1
        else:
            rows.append([y, span, 1])
    for y, (x0, x1), h in rows:
        top = 11 - y
        e = {
            "from": [x0 - 6, top - h, 1],
            "to": [x1 - 6, top, 2],
            "faces": {
                "south": {"uv": _uv(FRONT[0] + x0, FRONT[1] + y, x1 - x0, h), "texture": "#shield"},
                "north": {"uv": _uv(BACK[0] + (W - x1), BACK[1] + y, x1 - x0, h), "texture": "#shield"},
                "east": {"uv": _uv(RIM[0], RIM[1] + y, 1, h), "texture": "#shield"},
                "west": {"uv": _uv(RIM[0] + 1, RIM[1] + y, 1, h), "texture": "#shield"},
                "up": {"uv": _uv(RIM[0], RIM[1] + y, x1 - x0 if x1 - x0 <= 4 else 4, 1), "texture": "#shield"},
                "down": {"uv": _uv(RIM[0], RIM[1] + y + h - 1, 4, 1), "texture": "#shield"},
            },
        }
        elements.append(e)
    # handle behind the plate
    elements.append({"from": [-1, -3, -5], "to": [1, 3, 1], "faces": {
        f: {"uv": _uv(WOOD[0], WOOD[1], 6, 6), "texture": "#shield"} for f in ("north", "south", "east", "west", "up", "down")}})
    if spec.get("boss"):
        elements.append({"from": [-2, -2, 2], "to": [2, 2, 3], "faces": {
            f: {"uv": _uv(BOSS[0], BOSS[1], 4, 4), "texture": "#shield"} for f in ("north", "south", "east", "west", "up", "down")}})
    if spec.get("spikes"):
        for (x, y) in ((-5, 7), (4, 7), (-0.5, -9)):
            elements.append({"from": [x, y, 2], "to": [x + 1, y + 1, 4], "faces": {
                f: {"uv": _uv(BOSS[0], BOSS[1], 1, 1), "texture": "#shield"} for f in ("north", "south", "east", "west", "up", "down")}})
    display = {
        "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [10, 6, -4], "scale": [1, 1, 1]},
        "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [10, 6, 12], "scale": [1, 1, 1]},
        "firstperson_righthand": {"rotation": [0, 180, 5], "translation": [-10, 2, -10], "scale": [1.25, 1.25, 1.25]},
        "firstperson_lefthand": {"rotation": [0, 180, 5], "translation": [10, 0, -10], "scale": [1.25, 1.25, 1.25]},
        "gui": {"rotation": [15, -25, -5], "translation": [2, 3, 0], "scale": [0.65, 0.65, 0.65]},
        "fixed": {"rotation": [0, 180, 0], "translation": [-4.5, 4.5, -5], "scale": [0.55, 0.55, 0.55]},
        "ground": {"rotation": [0, 0, 0], "translation": [2, 4, 2], "scale": [0.25, 0.25, 0.25]},
    }
    blocking = dict(display)
    blocking.update({
        "thirdperson_righthand": {"rotation": [45, 135, 0], "translation": [3.51, 11, -2], "scale": [1, 1, 1]},
        "thirdperson_lefthand": {"rotation": [45, 135, 0], "translation": [13.51, 3, 5], "scale": [1, 1, 1]},
        "firstperson_righthand": {"rotation": [0, 180, -5], "translation": [-15, 5, -11], "scale": [1.25, 1.25, 1.25]},
        "firstperson_lefthand": {"rotation": [0, 180, -5], "translation": [5, 5, -11], "scale": [1.25, 1.25, 1.25]},
    })
    base = {"gui_light": "front", "textures": {"shield": tex_ref, "particle": tex_ref}, "elements": elements}
    main = dict(base, display=display, overrides=[{"predicate": {"blocking": 1}, "model": f"skycraft:item/{set_id}_shield_blocking"}])
    block = dict(base, display=blocking)
    return main, block
