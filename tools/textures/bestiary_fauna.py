"""Textures of Skyrim's wildlife rigs (SkyrimFauna.java): sabre cats, bears, deer, elk, mammoths, horkers, mudcrabs,
skeevers, slaughterfish and death hounds. Painted from the rig layouts by rigpaint.py; everything is procedural.

Run tools/textures/dump_rigs.sh SkyrimFauna first when a rig changed, then (from tools/textures):
    py -3 bestiary_fauna.py [name ...]
Writes textures/entity/creatures/<name>[_<variant>].png (+ _glow.png emissive layers).

Face orientation reminder (rigpaint faces): top/bottom rows run back (row 0) -> front; "right" (-x side) columns run
back -> front, "left" columns front -> back; "front" column 0 is the creature's right.
"""
import math
import sys

from rigpaint import Mat, load, paint, pal, save, has_pixels, shift, hash2, vnoise, lerp

PAINTERS = {}


def painter(name):
    def wrap(fn):
        PAINTERS[name] = fn
        return fn
    return wrap


def out(name, img, glow=None):
    save(img, "creatures/%s.png" % name)
    if glow is not None and has_pixels(glow):
        save(glow, "creatures/%s_glow.png" % name)


# ================================================================================================ common

CLAW = Mat(pal("#1c1814", "#3a332c", "#6a6056", "#8a8070"), "horn")
PALE_CLAW = Mat(pal("#4a4238", "#7a7062", "#aaa08e", "#d0c8b4"), "horn")
NOSE = Mat(pal("#0c0a0a", "#1e1a19", "#363030", "#4a4442"), "plain", base=0.45)
TOOTH = Mat(pal("#8a826e", "#bcb49c", "#e0d8c2", "#f6f0e0"), "bone", base=0.62)
IVORY = Mat(pal("#8c7c5c", "#b4a47e", "#d6caa6", "#eee6cc", "#fbf7ea"), "bone", base=0.6, grad=0.1)
HOOF = Mat(pal("#141010", "#2a221c", "#44382e", "#5c4c3e"), "horn", base=0.45)
ANTLER = Mat(pal("#4a3c2c", "#6e5c46", "#968266", "#bca88a", "#ddd0b4"), "bone", base=0.55, grad=0.25)
DARK = (22, 16, 14)


def side_faces(ctx):
    return [f for f in ("right", "left") if ctx.size(f)[0] > 0 and ctx.size(f)[1] > 0]


def from_back(ctx, face, k):
    """Column of a side face that lies k pixels from the box's back end."""
    fw, _ = ctx.size(face)
    return k if face == "right" else fw - 1 - k


def back_bands(ctx, color, period, depth=0.5, width=2, phase=0, wobble=0.6, skip=0.0):
    """Transverse dark bands over the back: rows on the top face continued down the side faces, tapering (tiger
    style). k counts pixels from the box's back end."""
    tw, td = ctx.size("top")
    for k in range(phase, td, period):
        if skip and hash2(k, 3, ctx.seed) < skip:
            continue
        for w in range(width):
            kk = k + w
            if kk >= td:
                continue
            for x in range(tw):
                off = int(round(math.sin(x * 0.7 + k) * wobble))
                ctx.put("top", x, td - 1 - kk + off, color)
            for face in side_faces(ctx):
                fw, fh = ctx.size(face)
                if kk >= fw:
                    continue
                n = max(1, int(round(fh * depth * (0.75 + 0.5 * hash2(k, 7 + (face == "left"), ctx.seed)))))
                if w > 0:
                    n = max(1, int(n * 0.6))
                for y in range(n):
                    off = int(round(math.sin(y * 0.6 + k * 1.3) * wobble * 1.5))
                    ctx.put(face, from_back(ctx, face, kk) + off, y, color)


def scatter_spots(ctx, faces, color, density, seed_off=0, rows=(0.0, 1.0), size=1):
    for face in faces:
        fw, fh = ctx.size(face)
        if fw <= 0 or fh <= 0:
            continue
        for y in range(int(fh * rows[0]), int(math.ceil(fh * rows[1]))):
            for x in range(fw):
                if hash2(x + seed_off * 31, y, ctx.seed + 5) < density:
                    for dy in range(size):
                        for dx in range(size):
                            ctx.put(face, x + dx, y + dy, color)


def patches(ctx, faces, color_mul, add=(0, 0, 0), scale=4.0, thresh=0.68, seed_off=0):
    """Soft irregular patches: tints pixels where smooth noise exceeds a threshold."""
    x0s = {}
    for face in faces:
        fx, fy, fw, fh = ctx.f[face]
        if fw <= 0 or fh <= 0:
            continue
        for y in range(fh):
            for x in range(fw):
                v = vnoise(fx + x, fy + y, ctx.seed + 900 + seed_off, scale)
                if v > thresh:
                    k = min(1.0, (v - thresh) / 0.08)
                    m = 1 + (color_mul - 1) * k
                    ctx.tint(face, x, y, m, (add[0] * k, add[1] * k, add[2] * k))
    return x0s


def gradient_tint(ctx, faces, color, start=0.45, strength=0.85):
    """Blends the lower part of side faces toward a colour (fish bellies, pale flanks)."""
    for face in faces:
        fw, fh = ctx.size(face)
        for y in range(fh):
            f = (y / max(1, fh - 1) - start) / max(0.01, 1 - start)
            if f <= 0:
                continue
            k = min(1.0, f) * strength
            for x in range(fw):
                c = ctx.get(face, x, y)
                if c and c[3] > 0:
                    lum = (c[0] + c[1] + c[2]) / (3 * 150.0)
                    target = tuple(min(255, v * (0.75 + lum * 0.4)) for v in color)
                    ctx.put(face, x, y, lerp(c[:3], target, k) + (c[3],))


def fill_face(ctx, face, color, alpha=255):
    fw, fh = ctx.size(face)
    for y in range(fh):
        for x in range(fw):
            c = ctx.get(face, x, y)
            if c is not None:
                ctx.put(face, x, y, (color[0], color[1], color[2], alpha))


def clear_face(ctx, face):
    fw, fh = ctx.size(face)
    for y in range(fh):
        for x in range(fw):
            ctx.put(face, x, y, (0, 0, 0, 0))


def eye2(ctx, face, x, y, iris, pupil=(10, 8, 8), glow=False, slit=False, lid=True):
    """A 2-wide eye: iris + pupil, a lid shadow above."""
    if lid:
        for dx in range(-1, 3):
            ctx.tint(face, x + dx, y - 1, 0.65)
    ctx.put(face, x, y, iris, glow)
    ctx.put(face, x + 1, y, iris, glow)
    if slit:
        ctx.put(face, x + (1 if face != "left" else 0), y, pupil, glow)
    else:
        ctx.put(face, x + 1 if face in ("right", "front") else x, y, pupil, glow)


def paint_mouth_line(ctx, face, row, color=(34, 20, 18), x0=0, x1=None):
    ctx.hline(face, row, color, x0, x1)


# ================================================================================================ sabre cat


@painter("sabre_cat")
def sabre_cat():
    lay = load("sabre_cat")
    variants = {
        "sabre_cat": dict(
            fur=pal("#3e2614", "#6e4522", "#9c6a36", "#c08f52", "#ddb57e"),
            belly=pal("#9c8668", "#c8b490", "#e4d6b6", "#f6eedc"),
            mark=(58, 34, 18), eye=(236, 186, 52), nose=(52, 30, 26)),
        "sabre_cat_snowy": dict(
            fur=pal("#6a727e", "#98a0aa", "#c2c8cf", "#e0e4e8", "#f7f9fa"),
            belly=pal("#b4bac2", "#d6dbe0", "#edf0f3", "#ffffff"),
            mark=(92, 98, 110), eye=(150, 206, 232), nose=(70, 64, 70)),
    }
    for name, v in variants.items():
        fur, belly = v["fur"], v["belly"]
        mats = {
            "fur": Mat(fur, "fur", base=0.55, belly=belly, counter=0.3, grad=0.08, dorsal=0.08),
            "leg": Mat(fur, "fur", base=0.6, belly=belly, counter=0.2),
            "face": Mat(fur, "fur", base=0.58, counter=0.15),
            "muzzle": Mat([lerp(a, b, 0.55) for a, b in zip(fur[1:], belly)], "fur", base=0.55, counter=0.3),
            "ruff": Mat([lerp(a, b, 0.3) for a, b in zip(fur[1:], belly)], "hair", base=0.6, contrast=0.4, scale=0.7, counter=0.4),
            "tail": Mat(fur, "fur", base=0.5, counter=0.2),
            "belly": Mat(belly, "fur", base=0.62),
            "fang": IVORY,
            "nose": Mat(pal("#1c1210", "#3a2622", "#5a3c36", "#7a5a52"), "plain", base=0.5),
            "claw": PALE_CLAW,
        }
        mark = v["mark"]

        def decor(ctx, v=v, mark=mark):
            t = ctx.tag
            if t in ("torso", "chest"):
                back_bands(ctx, mark, 4, depth=0.55, width=2, phase=1)
            elif t == "neck":
                back_bands(ctx, mark, 4, depth=0.45, width=2, phase=1)
            elif t in ("thigh", "shoulder"):
                back_bands(ctx, mark, 4, depth=0.35, width=1, phase=1)
            elif t == "tail":
                back_bands(ctx, mark, 3, depth=0.8, width=1, phase=1)
                if ctx.bone == "tail3":
                    fw, fh = ctx.size("top")
                    for f in ("top", "right", "left", "bottom"):
                        w, h = ctx.size(f)
                        for y in range(h):
                            for x in range(w):
                                if (f in ("top", "bottom") and y < 2) or (f == "right" and x < 2) or (f == "left" and x >= w - 2):
                                    ctx.put(f, x, y, mark)
                    ctx.fill("back", mark)
            elif t == "head":
                fw, fh = ctx.size("front")
                eye2(ctx, "front", 1, 2, v["eye"], slit=True)
                eye2(ctx, "front", fw - 3, 2, v["eye"], slit=True)
                # tear lines and forehead stripes
                ctx.put("front", 2, 3, mark)
                ctx.put("front", fw - 3, 3, mark)
                tw, td = ctx.size("top")
                for x in (tw // 2 - 2, tw // 2 + 1):
                    for y in range(0, td - 1):
                        ctx.put("top", x, y, mark)
                ctx.put("front", fw // 2 - 1, 1, mark)
                ctx.put("front", fw // 2, 1, mark)
                for f in ("right", "left"):
                    sw, sh = ctx.size(f)
                    for y in range(1, 3):
                        ctx.put(f, from_back(ctx, f, 1 + y), y, mark)
            elif t == "brow":
                fw, fh = ctx.size("front")
                for x in range(fw):
                    ctx.tint("front", x, fh - 1, 0.85)
            elif t == "snout":
                fw, fh = ctx.size("front")
                ctx.hline("front", fh - 1, v["belly"][3])
                ctx.put("front", fw // 2, fh - 1, (40, 24, 20))
                ctx.put("front", fw // 2 - 1, fh - 2, (40, 24, 20))
                ctx.put("front", fw // 2 + 1, fh - 2, (40, 24, 20))
                for f in ("right", "left", "front"):
                    sw, sh = ctx.size(f)
                    for y in (1, 2):
                        for x in range(sw):
                            if (x + y) % 2 == 0 and f != "front":
                                ctx.put(f, x, y, lerp(mark, (0, 0, 0), 0.3))
            elif t == "jaw":
                ctx.hline("top", ctx.size("top")[1] - 1, (60, 24, 24))
                ctx.hline("front", 0, (46, 22, 20))
            elif t == "ear":
                fw, fh = ctx.size("back")
                ctx.fill("back", mark)
                ctx.put("back", fw // 2, fh // 2, lerp(v["fur"][4], (255, 255, 255), 0.4))
                ctx.put("front", 0, fh - 1, v["belly"][2])
                ctx.put("front", fw - 1, fh - 1, v["belly"][2])
            elif t == "fang":
                fw, fh = ctx.size("front")
                for f in ("front", "right", "left", "back"):
                    ctx.tint(f, 0, 0, 0.8)
            elif t == "paw":
                for f in ("front",):
                    fw, fh = ctx.size(f)
                    for x in range(1, fw, 2):
                        ctx.put(f, x, fh - 1, lerp(v["fur"][1], (0, 0, 0), 0.3))

        img, glow = paint(lay, mats, decor, seed=41)
        out(name, img)


# ================================================================================================ bear


@painter("bear")
def bear():
    lay = load("bear")
    variants = {
        "bear": dict(fur=pal("#1e120a", "#3a2414", "#5a3a20", "#7a5432", "#9c7048"),
                     muzzle=pal("#4e3a28", "#76583c", "#9a7a58", "#bc9c76"), tip=(176, 140, 96)),
        "bear_cave": dict(fur=pal("#0a0808", "#161210", "#241e1a", "#342c26", "#463c34"),
                          muzzle=pal("#2e2620", "#463a30", "#5e4e40", "#786654"), tip=(92, 80, 70)),
        "bear_snow": dict(fur=pal("#8a8272", "#b4ac9a", "#d2cab8", "#e6e1d2", "#f8f5ec"),
                          muzzle=pal("#7a7064", "#9c9284", "#bcb2a2", "#d6cebe"), tip=(255, 255, 250)),
    }
    for name, v in variants.items():
        fur = v["fur"]
        mats = {
            "fur": Mat(fur, "fur", base=0.55, contrast=1.3, scale=1.3, counter=0.1, dorsal=0.05),
            "belly": Mat(shift(fur, mul=0.85), "fur", base=0.5, contrast=1.2, scale=1.2),
            "leg": Mat(shift(fur, mul=0.8), "fur", base=0.5, contrast=1.2, scale=1.1, counter=-0.15),
            "face": Mat(fur, "fur", base=0.56, counter=0.1),
            "muzzle": Mat(v["muzzle"], "fur", base=0.55, counter=0.2),
            "nose": NOSE,
            "claw": PALE_CLAW if name != "bear_snow" else CLAW,
        }

        def decor(ctx, v=v):
            t = ctx.tag
            if t in ("chest", "scruff", "rump", "torso"):
                # grizzled pale tips over the hump and back
                tw, td = ctx.size("top")
                for y in range(td):
                    for x in range(tw):
                        if hash2(x, y, ctx.seed + 3) > (0.78 if t != "torso" else 0.9):
                            ctx.put("top", x, y, lerp(ctx.get("top", x, y)[:3], v["tip"], 0.45))
            if t == "head":
                fw, fh = ctx.size("front")
                for x in (1, fw - 3):
                    ctx.tint("front", x, 1, 0.75)
                    ctx.tint("front", x + 1, 1, 0.75)
                    ctx.put("front", x, 2, (12, 8, 6))
                    ctx.put("front", x + 1, 2, (12, 8, 6))
                ctx.put("front", 1, 2, (120, 100, 80))
                ctx.put("front", fw - 2, 2, (120, 100, 80))
                # lighter brow fur over the eyes
                ctx.put("front", 1, 0, v["muzzle"][2])
                ctx.put("front", fw - 2, 0, v["muzzle"][2])
            elif t == "snout":
                fw, fh = ctx.size("front")
                ctx.hline("front", fh - 1, (30, 20, 16), 1, fw - 1)
            elif t == "jaw":
                ctx.hline("top", ctx.size("top")[1] - 1, (70, 30, 30))
                ctx.hline("front", 0, (34, 22, 18))
            elif t == "nose":
                ctx.put("front", 0, 1, (60, 56, 56))
                ctx.put("front", 2, 1, (60, 56, 56))
            elif t == "ear":
                fw, fh = ctx.size("front")
                ctx.rect("front", 0, 0, fw, 1, lerp(v["fur"][1], (0, 0, 0), 0.3))
                ctx.put("front", fw // 2, fh - 1, v["muzzle"][1])
            elif t == "paw":
                fw, fh = ctx.size("bottom")
                ctx.fill("bottom", (30, 26, 26))

        img, glow = paint(lay, mats, decor, seed=51)
        out(name, img)


# ================================================================================================ deer & elk


def cervid_decor(white, dark, eye_col=(16, 12, 10)):
    def decor(ctx):
        t = ctx.tag
        if t == "head":
            # eyes on the sides, dark lids, white eye ring
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                x = from_back(ctx, f, fw - 2)
                ctx.put(f, x, 1, eye_col)
                ctx.put(f, x + (1 if f == "right" else -1), 1, lerp(white, (0, 0, 0), 0.25))
                ctx.put(f, x, 0, lerp(white, (0, 0, 0), 0.1))
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, lerp(white, (0, 0, 0), 0.1))
            ctx.fill("bottom", white)
        elif t == "snout":
            fw, fh = ctx.size("front")
            ctx.hline("bottom", ctx.size("bottom")[1] - 1, white)
            for f in ("right", "left"):
                ctx.put(f, from_back(ctx, f, ctx.size(f)[0] - 1), ctx.size(f)[1] - 1, white)
            ctx.hline("front", fh - 1, white)
        elif t == "nose":
            ctx.put("front", 0, 0, (60, 52, 50))
        elif t == "jaw":
            ctx.fill("bottom", white)
            ctx.fill("front", white)
            ctx.hline("top", ctx.size("top")[1] - 1, (40, 28, 24))
        elif t in ("neck",):
            ctx.fill("bottom", white)
            fw, fh = ctx.size("front")
            ctx.fill("front", white)
        elif t == "ear":
            fw, fh = ctx.size("front")
            ctx.fill("front", lerp(white, (190, 150, 140), 0.4))
            ctx.hline("front", 0, dark)
            for y in range(fh):
                ctx.put("front", 0, y, dark)
            ctx.fill("back", lerp(dark, (120, 90, 60), 0.4))
        elif t == "torso":
            # white rump patch around the tail
            fw, fh = ctx.size("back")
            for y in range(fh):
                for x in range(fw):
                    if y > 0 and 1 <= x < fw - 1:
                        ctx.put("back", x, y, white)
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                for y in range(1, sh - 1):
                    ctx.put(f, from_back(ctx, f, 0), y, white)
        elif t == "rump":
            fw, fh = ctx.size("back")
            ctx.fill("back", white)
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                for y in range(sh):
                    for k in range(min(2, sw)):
                        ctx.put(f, from_back(ctx, f, k), y, white)
            tw, td = ctx.size("top")
            for x in range(1, tw - 1):
                ctx.put("top", x, 0, white)
        elif t == "tail":
            ctx.fill("bottom", white)
            ctx.fill("top", dark)
        elif t == "leg":
            # darker lower legs, a pale band above the hoof
            for f in ("front", "back", "right", "left"):
                fw, fh = ctx.size(f)
                if "lower" in ctx.bone:
                    for y in range(fh // 2, fh):
                        for x in range(fw):
                            ctx.tint(f, x, y, 0.8)
                    ctx.hline(f, fh - 3, lerp(white, (0, 0, 0), 0.15))
        elif t in ("antler", "tine"):
            # pale polished tips
            for f in ("top",):
                ctx.fill(f, (236, 228, 208))
    return decor


@painter("deer")
def deer():
    lay = load("deer")
    fur = pal("#4a2e1a", "#6e4a2a", "#946a3e", "#b68a54", "#d0a86e")
    white = (238, 232, 220)
    mats = {
        "fur": Mat(fur, "fur", base=0.56, counter=0.18, dorsal=0.1, belly=pal("#b8ac98", "#dcd2c0", "#f0eadc", "#faf8f2")),
        "neck": Mat(fur, "fur", base=0.58, counter=0.1),
        "face": Mat(shift(fur, mul=0.95), "fur", base=0.55),
        "muzzle": Mat(pal("#2e2018", "#4a3424", "#684a34", "#86644a"), "fur", base=0.5),
        "leg": Mat(shift(fur, mul=0.9), "fur", base=0.55, counter=-0.1),
        "tail": Mat(fur, "fur", base=0.5),
        "belly": Mat(pal("#b8ac98", "#dcd2c0", "#f0eadc", "#faf8f2"), "fur", base=0.6),
        "hoof": HOOF,
        "nose": NOSE,
        "antler": ANTLER,
    }
    img, glow = paint(lay, mats, cervid_decor(white, (40, 26, 16)), seed=61)
    out("deer", img)


@painter("elk")
def elk():
    lay = load("elk")
    fur = pal("#4e3420", "#74532f", "#98764a", "#b89662", "#d2b37e")
    mane = pal("#1a110a", "#2e2014", "#46301e", "#5e432a", "#76583a")
    rump = pal("#9c8662", "#c0aa84", "#dccaa6", "#efe4c8")
    white = (226, 212, 180)
    mats = {
        "fur": Mat(fur, "fur", base=0.56, counter=0.12, dorsal=0.06, belly=shift(mane, mul=1.2)),
        "mane": Mat(mane, "hair", base=0.55, contrast=0.55, scale=0.9),
        "face": Mat(shift(fur, mul=0.85), "fur", base=0.52),
        "muzzle": Mat(shift(mane, mul=1.3), "fur", base=0.55),
        "leg": Mat(shift(mane, mul=1.35), "fur", base=0.55),
        "rump": Mat(rump, "fur", base=0.58),
        "belly": Mat(shift(mane, mul=1.3), "fur", base=0.5),
        "hoof": HOOF,
        "nose": NOSE,
        "antler": ANTLER,
    }
    base_decor = cervid_decor(white, (30, 20, 12))

    def decor(ctx):
        t = ctx.tag
        if t in ("neck", "tail"):
            return  # mane covers the throat; tail is part of the pale rump
        if t == "torso":
            # pale rump fades into the body over the back third
            for f in ("right", "left", "top"):
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        k = (x if f == "right" else (fw - 1 - x)) if f != "top" else y
                        span = 4
                        if k < span and (f == "top" or y < fh - 1):
                            c = ctx.get(f, x, y)
                            ctx.put(f, x, y, lerp(c[:3], rump[2], 0.85 - k * 0.18) + (255,))
            ctx.fill("back", rump[2])
            return
        if t == "beard" or t == "bib":
            return
        base_decor(ctx)

    img, glow = paint(lay, mats, decor, seed=71)
    out("elk", img)


# ================================================================================================ mammoth


@painter("mammoth")
def mammoth():
    lay = load("mammoth")
    wool = pal("#24160c", "#3e2716", "#5c3b20", "#7a522e", "#986c40")
    mats = {
        "wool": Mat(wool, "hair", base=0.55, contrast=0.5, counter=-0.1, dorsal=-0.05),
        "shag": Mat(shift(wool, mul=0.85), "hair", base=0.5, contrast=0.7, scale=1.4),
        "leg": Mat(pal("#1e140e", "#33241a", "#4a3626", "#614834"), "hair", base=0.5, contrast=0.5),
        "trunk": Mat(pal("#2a1e16", "#43301f", "#5c442e", "#765a40", "#8e7052"), "skin", base=0.55),
        "ear": Mat(pal("#1a110a", "#2c1d12", "#42301e", "#5a432c"), "hair", base=0.5, contrast=0.5),
        "nail": Mat(pal("#3a3228", "#5c5244", "#80766a", "#a29a8c"), "horn", base=0.6),
        "tusk": IVORY,
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("trunk", "trunk_tip", "snout"):
            # wrinkle rings
            for f in ("front", "back", "right", "left"):
                fw, fh = ctx.size(f)
                for y in range(1, fh, 2):
                    for x in range(fw):
                        ctx.tint(f, x, y, 0.78)
            if t == "trunk_tip":
                ctx.fill("bottom", (40, 28, 22))
                fw, fh = ctx.size("bottom")
                ctx.put("bottom", 0, fh // 2, (12, 8, 6))
                ctx.put("bottom", fw - 1, fh // 2, (12, 8, 6))
        elif t == "head":
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                x = from_back(ctx, f, fw - 3)
                ctx.put(f, x, 5, (14, 10, 8))
                ctx.put(f, x + (1 if f == "right" else -1), 5, (60, 40, 26))
                for dx in (-1, 0, 1):
                    ctx.tint(f, x + dx, 4, 0.6)
            # long hair of the forehead hangs over the face
            fw, fh = ctx.size("front")
            for x in range(fw):
                for y in range(int(3 + 2 * hash2(x, 1, ctx.seed))):
                    ctx.tint("front", x, y, 0.82)
        elif t in ("tusk", "tusk_tip"):
            for f in ("right", "left", "top", "bottom"):
                fw, fh = ctx.size(f)
                for x in range(0, fw, 3):
                    for y in range(fh):
                        ctx.tint(f, x if f != "top" else y, y if f != "top" else x, 0.9)
            if t == "tusk" and "tusk_mid" not in ctx.bone:
                for f in ("right", "left", "top", "bottom"):
                    fw, fh = ctx.size(f)
                    for y in range(fh):
                        ctx.tint(f, from_back(ctx, f, 0) if f in ("right", "left") else 0, y, 0.7)
        elif t in ("skirt", "belly_skirt", "bib", "throat", "leg_shag"):
            # ragged hanging fringe: transparent gaps along the bottom edge of side faces
            for f in ("front", "back", "right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    n = int(hash2(x, 9, ctx.seed) * 2.6)
                    for k in range(n):
                        ctx.put(f, x, fh - 1 - k, (0, 0, 0, 0))
        elif t == "nails":
            pass

    img, glow = paint(lay, mats, decor, seed=81)
    out("mammoth", img)


# ================================================================================================ horker


@painter("horker")
def horker():
    lay = load("horker")
    hide = pal("#2e2824", "#4a423c", "#675e56", "#857b72", "#a2988e")
    mats = {
        "hide": Mat(hide, "skin", base=0.56, counter=-0.15, belly=pal("#5a524a", "#766e64", "#928a80", "#aaa298")),
        "belly": Mat(pal("#5a524a", "#766e64", "#928a80", "#aaa298"), "skin", base=0.55),
        "flipper": Mat(pal("#1e1a18", "#2e2926", "#423b36", "#564d46"), "leather", base=0.5),
        "muzzle": Mat(pal("#5e4c46", "#7e6a62", "#9c8880", "#b8a49a"), "skin", base=0.55),
        "tusk": IVORY,
        "whisker": Mat(pal("#c8c0b0", "#e4ddd0", "#f6f2ea"), "flat", base=0.7),
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("torso", "chest", "hind", "neck_fold", "hind_tip"):
            # darker mottling, pale scars, heavy skin folds around the neck
            patches(ctx, ["top", "right", "left", "back"], 0.8, scale=3.0, thresh=0.66)
            patches(ctx, ["top", "right", "left"], 1.0, add=(26, 22, 18), scale=2.0, thresh=0.8, seed_off=5)
            if t in ("chest", "neck_fold"):
                for f in ("right", "left", "top"):
                    fw, fh = ctx.size(f)
                    for k in (1, 4, 7):
                        if f == "top":
                            for x in range(fw):
                                ctx.tint(f, x, fh - 1 - k + int(round(math.sin(x * 0.6 + k) * 0.7)), 0.72)
                        else:
                            for y in range(int(fh * 0.8)):
                                ctx.tint(f, from_back(ctx, f, fw - 1 - k) + int(round(math.sin(y * 0.45 + k) * 0.8)), y, 0.74)
                fw, fh = ctx.size("front")
                for y in range(3, fh, 4):
                    for x in range(fw):
                        ctx.tint("front", x, y + int(round(math.sin(x * 0.7) * 0.7)), 0.8)
        elif t == "head":
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                x = from_back(ctx, f, fw - 3)
                ctx.put(f, x, 3, (10, 8, 8))
                ctx.put(f, x + (1 if f == "right" else -1), 3, (10, 8, 8))
                ctx.tint(f, x, 2, 0.6)
                ctx.tint(f, x + (1 if f == "right" else -1), 2, 0.6)
            fw, fh = ctx.size("front")
            for x in range(1, fw - 1):
                ctx.tint("front", x, 0, 0.85)
        elif t == "muzzle":
            # whisker pores in staggered rows on the pad, nostrils on top
            for f in ("front", "right", "left"):
                fw, fh = ctx.size(f)
                for y in range(1, fh - 1):
                    for x in range(fw):
                        if y % 2 == 0 and (x + y // 2) % 3 == 0:
                            ctx.put(f, x, y, (78, 60, 54))
            tw, td = ctx.size("top")
            ctx.put("top", tw // 2 - 2, td - 1, (20, 14, 12))
            ctx.put("top", tw // 2 + 1, td - 1, (20, 14, 12))
            ctx.hline("front", ctx.size("front")[1] - 1, (40, 26, 24))
        elif t == "jaw":
            ctx.hline("top", ctx.size("top")[1] - 1, (80, 40, 40))
        elif t == "whisker":
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                clear_face(ctx, f)
                for y in range(0, fh, 2):
                    for x in range(fw):
                        if x < fw - (y // 2) % 2:
                            ctx.put(f, x, y, (232, 226, 214, 255))
        elif t in ("flipper", "flipper_hand", "tail_flipper_web", "tail_flipper"):
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                for x in range(1, fw, 2):
                    for y in range(fh):
                        ctx.tint(f, x, y, 0.8)
            if t in ("flipper_hand",):
                fw, fh = ctx.size("top")
                for y in range(fh):
                    ctx.put("top", 0, y, (120, 110, 96))

    img, glow = paint(lay, mats, decor, seed=91)
    out("horker", img)


# ================================================================================================ mudcrab


@painter("mudcrab")
def mudcrab():
    lay = load("mudcrab")
    variants = {
        "mudcrab": dict(shell=pal("#221d10", "#38301b", "#524629", "#6b5d38", "#857549"),
                        plate=pal("#16120a", "#262012", "#3a301c", "#4e4228", "#625436"),
                        moss=0.0, barn=0.0),
        "mudcrab_giant": dict(shell=pal("#232418", "#3a3c26", "#545434", "#6e6c46", "#8a865c"),
                              plate=pal("#1a1a10", "#2c2c1a", "#424028", "#5a5538", "#706a4a"),
                              moss=0.62, barn=0.06),
    }
    for name, v in variants.items():
        mats = {
            "shell": Mat(v["shell"], "skin", base=0.55, contrast=0.9, scale=0.7, belly=pal("#3e3828", "#565038", "#6e684e", "#868064")),
            "plate": Mat(v["plate"], "skin", base=0.6, contrast=0.9, scale=0.6),
            "leg": Mat(shift(v["shell"], mul=1.1), "chitin", base=0.55, contrast=0.6, scale=0.6),
            "under": Mat(pal("#3e3828", "#565038", "#6e684e", "#868064"), "chitin", base=0.5, contrast=0.4),
            "spike": Mat(pal("#16130a", "#2c2616", "#463e26", "#5e5436"), "horn", base=0.5),
            "eye": Mat(pal("#040404", "#141212", "#2a2626"), "plain", base=0.4),
        }

        def decor(ctx, v=v):
            t = ctx.tag
            if t in ("thorax", "plate", "plate_top", "plate_peak", "rim_r", "rim_l", "rim_front", "rim_back", "head"):
                # radial grooves on the carapace and a pale rim
                tw, td = ctx.size("top")
                if t in ("thorax", "plate"):
                    # the H-shaped groove of a crab's carapace, and a speckled rim
                    for y in range(1, td - 1):
                        ctx.tint("top", tw // 2 - 3, y, 0.75)
                        ctx.tint("top", tw // 2 + 2, y, 0.75)
                    for x in range(tw // 2 - 3, tw // 2 + 3):
                        ctx.tint("top", x, td // 2, 0.75)
                scatter_spots(ctx, ["top", "front", "back", "right", "left"], v["plate"][2], 0.12, seed_off=2)
                for f in ("right", "left", "front", "back"):
                    fw, fh = ctx.size(f)
                    if fh > 2:
                        ctx.hline(f, fh - 1, v["plate"][0])
                if v["moss"] > 0:
                    patches(ctx, ["top"], 0.75, add=(10, 38, 0), scale=2.5, thresh=v["moss"], seed_off=3)
                    patches(ctx, ["right", "left", "front", "back"], 0.8, add=(10, 30, 0), scale=2.0, thresh=v["moss"] + 0.1, seed_off=4)
                if v["barn"] > 0:
                    scatter_spots(ctx, ["top", "right", "left", "front"], (200, 196, 180), v["barn"], seed_off=7)
                    scatter_spots(ctx, ["top"], (150, 146, 132), v["barn"] * 0.6, seed_off=9)
            if t == "head":
                fw, fh = ctx.size("front")
                for x in range(1, fw - 1):
                    ctx.put("front", x, fh - 1, (60, 30, 24) if x % 2 else (90, 60, 40))
            elif t == "leg" or t == "arm":
                for f in ("top", "front", "back", "bottom", "right", "left"):
                    fw, fh = ctx.size(f)
                    if f in ("top", "bottom"):
                        continue
                    for y in range(fh):
                        ctx.tint(f, 0, y, 0.7)
                if "lower" in ctx.bone and t == "leg":
                    # dark pointed tips
                    for f in ("front", "back", "top", "bottom"):
                        fw, fh = ctx.size(f)
                        for y in range(fh):
                            ctx.put(f, 0, y, (26, 20, 12))
            elif t in ("pincer", "crusher", "crusher_finger", "cutter"):
                # claws darken toward the pincer tips, with warm calcareous tubercles on the inner cutting edge
                for f in ("top", "bottom", "front", "back"):
                    fw, fh = ctx.size(f)
                    for y in range(fh):
                        for x in range(min(2, fw)):
                            ctx.tint(f, x, y, 0.72 if x == 0 else 0.86)
                rw, rh = ctx.size("right")
                for y in range(rh):
                    for x in range(rw):
                        if y == rh - 1 or x in (0, rw - 1):
                            ctx.tint("right", x, y, 0.78)
                scatter_spots(ctx, ["top", "right", "front"], v["plate"][3], 0.14, seed_off=5)
                if v["barn"] > 0:
                    scatter_spots(ctx, ["top", "right", "front"], (196, 192, 176), v["barn"], seed_off=11)
            elif t == "eye":
                ctx.fill("top", (70, 66, 60))
            elif t == "spike":
                for f in ("right", "left", "front", "top"):
                    ctx.tint(f, 0, 0, 0.6)
            elif t == "mouth":
                ctx.fill("front", (70, 30, 24))

        img, glow = paint(lay, mats, decor, seed=101)
        out(name, img)


# ================================================================================================ skeever


@painter("skeever")
def skeever():
    lay = load("skeever")
    fur = pal("#241e1a", "#3e342e", "#5a4e46", "#76685e", "#928478")
    pink = pal("#7a4248", "#a2606a", "#c48288", "#dea4a6")
    mats = {
        "fur": Mat(fur, "hair", base=0.55, contrast=0.5, scale=0.7, counter=0.25, dorsal=0.08,
                   belly=pal("#6e625a", "#8c8076", "#a89c90", "#c0b6aa")),
        "face": Mat(fur, "fur", base=0.6, counter=0.15),
        "leg": Mat(fur, "fur", base=0.55),
        "belly": Mat(pal("#6e625a", "#8c8076", "#a89c90", "#c0b6aa"), "fur", base=0.6),
        "tail": Mat(pink, "skin", base=0.5, scale=0.6),
        "pink": Mat(pink, "plain", base=0.6),
        "tooth": Mat(pal("#7a5c18", "#b08a30", "#d8b450", "#eed68a"), "bone", base=0.6),
        "claw": CLAW,
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("torso", "hump", "haunch"):
            patches(ctx, ["top", "right", "left"], 0.72, scale=2.5, thresh=0.74)  # mangy patches
        if t == "head":
            fw, fh = ctx.size("front")
            ctx.put("front", 0, 1, (200, 24, 20))
            ctx.put("front", fw - 1, 1, (200, 24, 20))
            for f in ("right", "left"):
                x = from_back(ctx, f, ctx.size(f)[0] - 1)
                ctx.put(f, x, 1, (220, 30, 24))
                ctx.put(f, x + (-1 if f == "right" else 1), 1, (120, 14, 12))
        elif t == "snout":
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    if x % 2 == 0:
                        ctx.put(f, x, fh - 1, (30, 24, 22))
        elif t == "jaw":
            ctx.fill("bottom", pink[2])
            ctx.hline("top", ctx.size("top")[1] - 1, (110, 50, 56))
        elif t == "ear":
            fw, fh = ctx.size("front")
            ctx.fill("front", pink[2])
            ctx.fill("back", pink[1])
            ctx.hline("front", 0, fur[2])
        elif t == "paw":
            for f in ("top", "front", "right", "left", "back", "bottom"):
                fill_face(ctx, f, pink[2])
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.tint(f, x, 0, 0.85)
        elif t == "tail":
            for f in ("top", "right", "left", "bottom"):
                fw, fh = ctx.size(f)
                if f in ("top", "bottom"):
                    for y in range(0, fh, 2):
                        ctx.hline(f, y, lerp(pink[1], (0, 0, 0), 0.15))
                else:
                    for x in range(0, fw, 2):
                        for y in range(fh):
                            ctx.tint(f, x, y, 0.85)
        elif t == "teeth":
            ctx.put("front", 0, 1, (90, 66, 20))
            ctx.put("front", 1, 1, (90, 66, 20))
            for y in range(2):
                ctx.tint("front", 1, y, 0.75)
        elif t == "nose":
            ctx.put("front", 0, 0, (60, 26, 30))
            ctx.put("front", 1, 0, (60, 26, 30))

    img, glow = paint(lay, mats, decor, seed=111)
    out("skeever", img)


# ================================================================================================ slaughterfish


@painter("slaughterfish")
def slaughterfish():
    lay = load("slaughterfish")
    olive = pal("#1a2410", "#2e3c1a", "#475628", "#637038", "#80894a")
    rust = (178, 92, 44)
    mats = {
        "scales": Mat(olive, "scales", base=0.58, contrast=0.8, scale=0.8, belly=pal("#6a2c12", "#8e421e", "#b05e2c", "#c87a40")),
        "head": Mat(shift(olive, mul=0.9), "skin", base=0.55, belly=pal("#6a2c12", "#8e421e", "#b05e2c", "#c87a40")),
        "belly": Mat(pal("#6a2c12", "#8e421e", "#b05e2c", "#c87a40", "#d8945a"), "scales", base=0.55, scale=0.8),
        "fin": Mat(pal("#3a2414", "#5c3a1e", "#7e522a", "#9a6a3a"), "membrane", base=0.55, alpha=235),
        "tooth": TOOTH,
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("segment", "head", "jaw", "chin"):
            gradient_tint(ctx, ["right", "left", "front", "back"], rust, start=0.4 if t != "jaw" else 0.0, strength=0.8)
            # dark dorsal speckles
            scatter_spots(ctx, ["top"], (20, 26, 12), 0.12)
            scatter_spots(ctx, ["right", "left"], (24, 30, 14), 0.05, rows=(0.0, 0.4))
        if t == "head":
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                x = from_back(ctx, f, fw - 3)
                ctx.put(f, x, 1, (230, 196, 60))
                ctx.put(f, x + (1 if f == "right" else -1), 1, (230, 196, 60))
                ctx.put(f, x + (1 if f == "right" else 0), 1, (10, 8, 6))
                ctx.tint(f, x, 0, 0.6)
                # gill slit
                gx = from_back(ctx, f, 0)
                for y in range(1, fh):
                    ctx.put(f, gx, y, (60, 20, 14))
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, (40, 14, 10))
        elif t == "jaw":
            ctx.fill("top", (90, 24, 20))
        elif t == "chin":
            ctx.fill("top", (90, 24, 20))
        elif t in ("dorsal", "tail_fin", "ventral"):
            # fin rays with a spiny, notched edge
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    if x % 2 == 0:
                        for y in range(fh):
                            ctx.tint(f, x, y, 0.65)
                    elif t == "dorsal":
                        ctx.put(f, x, 0, (0, 0, 0, 0))
                if t == "tail_fin":
                    # fan out from a narrow base to a forked trailing edge
                    mid = (fh - 1) / 2.0
                    for c in range(fw):  # c = distance from the base
                        x = from_back(ctx, f, fw - 1 - c)
                        half = 1.2 + c / max(1, fw - 1) * (mid + 0.2)
                        fork = c - (fw - 3)
                        for y in range(fh):
                            d = abs(y - mid)
                            if d > half or (fork >= 0 and d < fork + 0.6):
                                ctx.put(f, x, y, (0, 0, 0, 0))
                if t == "dorsal":
                    for x in range(0, fw, 2):
                        ctx.put(f, x, 0, (200, 180, 150))
        elif t == "fin":
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                for x in range(0, fw, 2):
                    for y in range(fh):
                        ctx.tint(f, x, y, 0.7)
        elif t in ("teeth_front", "teeth_side", "teeth_upper"):
            for f in ("front", "right", "left", "top", "back", "bottom"):
                fw, fh = ctx.size(f)
                if f in ("right", "left") and t == "teeth_side":
                    for x in range(fw):
                        if x % 2:
                            ctx.put(f, x, 0, (0, 0, 0, 0))
                if f in ("front", "back") and t != "teeth_side":
                    for x in range(fw):
                        if x % 2:
                            ctx.put(f, x, 0 if t == "teeth_front" else fh - 1, (0, 0, 0, 0))

    img, glow = paint(lay, mats, decor, seed=121)
    out("slaughterfish", img)


# ================================================================================================ death hound


@painter("death_hound")
def death_hound():
    lay = load("death_hound")
    hide = pal("#110f11", "#1e1b1e", "#2c282c", "#3c373c", "#514a51")
    bone = pal("#4a4440", "#6e6660", "#948a82", "#b8aea4", "#d4ccc2")
    eye = (255, 40, 24)
    mats = {
        "hide": Mat(hide, "skin", base=0.55, counter=-0.1, scale=0.8),
        "face": Mat(shift(hide, mul=0.9), "skin", base=0.55),
        "leg": Mat(hide, "skin", base=0.55, scale=0.6),
        "bone": Mat(bone, "bone", base=0.5, contrast=1.2),
        "rib": Mat(shift(hide, mul=1.4), "skin", base=0.55),
        "nose": NOSE,
        "tooth": TOOTH,
        "claw": Mat(pal("#5a5650", "#868078", "#aaa49a"), "horn"),
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("torso", "chest", "neck", "shoulder_blades"):
            patches(ctx, ["top", "right", "left", "front", "back"], 1.0, add=(74, 70, 72), scale=3.5, thresh=0.6)
            if t == "torso":
                # hollow flanks: dark tuck under the ribs, ridge shadows beside the spine
                for f in ("right", "left"):
                    fw, fh = ctx.size(f)
                    for x in range(fw):
                        ctx.tint(f, x, fh - 1, 0.6)
                        ctx.tint(f, x, fh - 2, 0.8)
                tw, td = ctx.size("top")
                for y in range(td):
                    ctx.tint("top", tw // 2 - 1, y, 0.7)
                    ctx.tint("top", tw // 2 + 1, y, 0.7)
        if t == "rib":
            # rib arcs: pale bone ridges with deep shadows; clear the top so the spine shows
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        ctx.put(f, x, y, lerp(bone[3], bone[1], y / max(1, fh - 1)))
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("bottom", x, y, bone[1])
        elif t == "head":
            # glowing eyes beside the muzzle, wrapping round onto the sides
            fw, fh = ctx.size("front")
            for x in (0, fw - 1):
                ctx.put("front", x, 1, eye, glow=True)
                ctx.tint("front", x, 0, 0.4)
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                x = from_back(ctx, f, sw - 1)
                ctx.put(f, x, 1, eye, glow=True)
                ctx.put(f, from_back(ctx, f, sw - 2), 1, (120, 10, 8), glow=True)
                ctx.tint(f, x, 0, 0.4)
            patches(ctx, ["top", "right", "left"], 1.0, add=(60, 56, 58), scale=2.5, thresh=0.66)
            # bony cheek ridges
            for f in ("right", "left"):
                fw2, fh2 = ctx.size(f)
                for k in range(fw2):
                    ctx.put(f, k, fh2 - 2, lerp(hide[3], bone[1], 0.5))
        elif t == "brow":
            ctx.fill("front", lerp(bone[0], hide[3], 0.5))
            ctx.fill("top", bone[1])
        elif t == "snout":
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, (70, 14, 14))
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                for x in range(sw):
                    ctx.put(f, x, sh - 1, (60, 12, 14) if x % 2 else (190, 180, 160))
        elif t == "jaw":
            ctx.fill("top", (90, 16, 18))
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                for x in range(sw):
                    if x % 2 == 0:
                        ctx.put(f, x, 0, (200, 190, 170))
        elif t == "ear":
            # ragged notches and a torn tip
            for f in ("front", "back"):
                fw, fh = ctx.size(f)
                ctx.put(f, 0, 0, (0, 0, 0, 0))
                ctx.put(f, fw - 1, 1, (0, 0, 0, 0))
                ctx.put(f, 0, 2, (0, 0, 0, 0))
            ctx.fill("front", (70, 40, 44))
        elif t in ("vertebra", "pelvis"):
            for f in ("right", "left", "front", "back"):
                fw, fh = ctx.size(f)
                ctx.hline(f, fh - 1, bone[0])
        elif t == "leg":
            # knobbly joints
            for f in ("front", "back", "right", "left"):
                fw, fh = ctx.size(f)
                if "lower" in ctx.bone:
                    ctx.hline(f, 0, lerp(hide[3], bone[1], 0.6))
        elif t == "tail":
            for f in ("top",):
                fw, fh = ctx.size(f)
                for y in range(0, fh, 2):
                    ctx.put(f, fw // 2, y, bone[1])

    img, glow = paint(lay, mats, decor, seed=131)
    out("death_hound", img, glow)


# ================================================================================================ main


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)


if __name__ == "__main__":
    main(sys.argv[1:])
