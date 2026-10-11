"""Textures of the mainland rig creatures (Morrowind, Cyrodiil, Black Marsh, Elsweyr), painted from the rig layouts
by rigpaint.py. All original, procedural.

Run tools/textures/dump_rigs.sh MainlandDefs first when a rig changed, then (from tools/textures):
    py -3 bestiary_mainland.py [name ...]
Writes textures/entity/creatures/<name>[_<variant>].png (+ _glow.png emissive layers).
"""
import math
import sys

from rigpaint import Mat, load, paint, pal, save, has_pixels, shift, lerp

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


# ================================================================================================ common materials

CLAW = Mat(pal("#16120e", "#2e2620", "#4e443a", "#706458"), "horn", base=0.5)
TOOTH = Mat(pal("#a49a84", "#cfc6ae", "#ece4cc", "#fbf6e6"), "bone", base=0.62)
HOOF = Mat(pal("#17120e", "#2e241c", "#4a3c30", "#62523f"), "horn", base=0.45)
IVORY = Mat(pal("#8e826a", "#bcb094", "#ddd3b6", "#f4eed8"), "horn", base=0.6, grad=0.3)
MOUTH = (70, 22, 24)
MOUTH_DARK = (34, 12, 14)
GUM = (150, 70, 72)
WHITE = (236, 230, 212)

SIDES = ("front", "right", "back", "left")


# ================================================================================================ decorator helpers


def ribs(ctx, period=4, mul=0.8, faces=SIDES, offset=0):
    """Darker vertical lines on side faces (meridians on bladders, ribbing)."""
    for f in faces:
        fw, fh = ctx.size(f)
        for x in range(offset % period, fw, period):
            for y in range(fh):
                ctx.tint(f, x, y, mul)


def bands(ctx, period=3, mul=0.78, faces=SIDES, offset=0, light=None):
    """Horizontal bands (rings on tentacles, belly plates). light = highlight multiplier for the row below a band."""
    for f in faces:
        fw, fh = ctx.size(f)
        for y in range(offset % period, fh, period):
            for x in range(fw):
                ctx.tint(f, x, y, mul)
                if light and y + 1 < fh:
                    ctx.tint(f, x, y + 1, light)


def edge_light(ctx, face, mul=1.18, rows=1):
    fw, fh = ctx.size(face)
    for y in range(min(rows, fh)):
        for x in range(fw):
            ctx.tint(face, x, y, mul)


def edge_dark(ctx, faces=SIDES, mul=0.7):
    for f in faces:
        fw, fh = ctx.size(f)
        for x in range(fw):
            ctx.tint(f, x, fh - 1, mul)


def maw_roof(ctx, face="bottom", teeth=WHITE, inner=MOUTH, step=2):
    """Inside of an upper jaw / skull bottom: dark mouth with a row of teeth around the front and sides."""
    fw, fh = ctx.size(face)
    for y in range(fh):
        for x in range(fw):
            ctx.put(face, x, y, inner if 0 < x < fw - 1 else lerp(inner, (0, 0, 0), 0.3))
    for x in range(0, fw, step):
        ctx.put(face, x, 0, teeth)
    for y in range(0, fh, step):
        ctx.put(face, 0, y, teeth)
        ctx.put(face, fw - 1, y, teeth)


def maw_floor(ctx, face="top", teeth=WHITE, tongue=(150, 70, 74), inner=MOUTH, step=2):
    fw, fh = ctx.size(face)
    for y in range(fh):
        for x in range(fw):
            c = inner
            if fw > 3 and 1 <= x < fw - 1 and y > 1:
                c = lerp(inner, tongue, 0.7 if fw // 2 - 1 <= x <= fw // 2 else 0.35)
            ctx.put(face, x, y, c)
    for x in range(1, fw - 1, step):
        ctx.put(face, x, 0, teeth)
    for y in range(1, fh, step):
        ctx.put(face, 0, y, teeth)
        ctx.put(face, fw - 1, y, teeth)


def eye(ctx, face, x, y, iris, pupil=(14, 10, 8), glow=False, big=False):
    """A small reptile/beast eye: iris with a dark slit and a socket shadow above."""
    for dx in range(-1, 3 if big else 2):
        ctx.tint(face, x + dx, y - 1, 0.65)
    if big:
        ctx.rect(face, x, y, 2, 2, iris, glow)
        ctx.put(face, x + 1, y, pupil)
        ctx.put(face, x + 1, y + 1, pupil)
        ctx.put(face, x, y, lerp(iris, (255, 255, 255), 0.45), glow)
    else:
        ctx.put(face, x, y, iris, glow)


def side_eyes(ctx, iris, row, from_front=1, pupil=(14, 10, 8), glow=False, big=False):
    """One eye on each side face, from_front pixels behind the front edge."""
    fw, fh = ctx.size("right")
    sz = 2 if big else 1
    # right face: the front edge is its right end; left face: its left end
    eye(ctx, "right", fw - from_front - sz, row, iris, pupil, glow, big)
    eye(ctx, "left", from_front, row, iris, pupil, glow, big)


# ================================================================================================ netch


@painter("netch")
def netch():
    lay = load("netch")
    variants = {
        # bull: brown/tan leather, dark horn plates
        "netch": dict(
            hide=pal("#2e1d12", "#4f3220", "#74502f", "#9a7246", "#bf9a68"),
            belly=pal("#5a3e28", "#80603e", "#a4845a", "#c4a87c"),
            skin=pal("#3e2a1c", "#62442c", "#87623f", "#ab8658"),
            plate=pal("#1e1814", "#3a3028", "#5c4c3c", "#7e6a54", "#9c8a70"),
            tent=pal("#2a1a12", "#47301f", "#6a4a30", "#8c6a48"),
            eye=(214, 160, 52), pattern="leather", plate_pat="chitin", spots=(0.18, 4, -0.12)),
        # betty: pinkish purple, smooth
        "netch_betty": dict(
            hide=pal("#3c2034", "#62344f", "#8a5072", "#ae7896", "#d0a4bc"),
            belly=pal("#6e4060", "#94627e", "#b88aa2", "#d8b4c6"),
            skin=pal("#4c2a40", "#70425e", "#965e7e", "#b8849e"),
            plate=pal("#4a2a40", "#6e4260", "#946482", "#b88aa6", "#d6b2c6"),
            tent=pal("#3a1e32", "#5a3250", "#7c4e6e", "#9e6e8c"),
            eye=(120, 200, 190), pattern="skin", plate_pat="skin", spots=(0.2, 5, 0.1)),
    }
    for name, v in variants.items():
        mats = {
            "hide": Mat(v["hide"], v["pattern"], base=0.55, belly=v["belly"], counter=-0.15, grad=0.25,
                        spots=v["spots"], contrast=0.8),
            "plate": Mat(v["plate"], v["plate_pat"], base=0.55, grad=0.15, scale=1.5),
            "skin": Mat(v["skin"], "skin", base=0.5, belly=v["skin"], grad=0.1),
            "tentacle": Mat(v["tent"], "chitin", base=0.55, scale=0.75, contrast=0.6),
        }
        bull = name == "netch"

        def decor(ctx, v=v, bull=bull):
            t = ctx.tag
            if t.startswith("dome"):
                # meridian folds of the bladder, lighter crest of each fold
                ribs(ctx, period=4, mul=0.82)
                ribs(ctx, period=4, mul=1.1, offset=1)
                fw, fh = ctx.size("top")
                for x in range(0, fw, 4):
                    for y in range(fh):
                        ctx.tint("top", x, y, 0.88)
                if t == "dome_top" and not bull:
                    # betty: pale mottled crown
                    for y in range(fh):
                        for x in range(fw):
                            if (x * 7 + y * 3) % 11 == 0:
                                ctx.tint("top", x, y, 1.15)
            if t == "plate":
                for f in ("top",):
                    fw, fh = ctx.size(f)
                    for x in range(fw):
                        ctx.tint(f, x, 0, 1.25)
                        ctx.tint(f, x, fh - 1, 0.7)
                    for y in range(fh):
                        ctx.tint(f, 0, y, 0.75)
                        ctx.tint(f, fw - 1, y, 0.75)
                    if bull and fw > 4:
                        # rivet-like bony nodules
                        ctx.put(f, 1, 1, shift([v["plate"][4]], mul=1.05)[0])
                        ctx.put(f, fw - 2, 1, shift([v["plate"][4]], mul=1.05)[0])
                edge_dark(ctx, mul=0.6)
            if t in ("tentacle", "tentacle_tip", "tentacle_end"):
                bands(ctx, period=3, mul=0.86, light=1.06, offset=1)
                fw, fh = ctx.size("bottom")
                for y in range(fh):
                    for x in range(fw):
                        ctx.tint("bottom", x, y, 0.6)
            if t == "mantle":
                fw, fh = ctx.size("bottom")
                # radial folds on the underside and a dark central pore
                cx, cy = fw / 2 - 0.5, fh / 2 - 0.5
                for y in range(fh):
                    for x in range(fw):
                        a = math.atan2(y - cy, x - cx)
                        if math.sin(a * 6) > 0.75:
                            ctx.tint("bottom", x, y, 0.8)
                ctx.rect("bottom", int(cx) - 1, int(cy) - 1, 3, 3, (40, 24, 22))
                ctx.put("bottom", int(cx), int(cy), (18, 10, 10))
            if t == "skirt":
                bands(ctx, period=2, mul=0.85)
            if t == "face":
                fw, fh = ctx.size("front")
                eye(ctx, "front", 1, 1, v["eye"], big=False)
                eye(ctx, "front", fw - 2, 1, v["eye"], big=False)
                ctx.put("front", 0, 1, (30, 20, 16))
                ctx.put("front", fw - 1, 1, (30, 20, 16))
                ctx.hline("front", fh - 1, (40, 24, 20), 2, fw - 2)
                ctx.put("front", fw // 2 - 1, fh - 1, (22, 12, 12))
                ctx.put("front", fw // 2, fh - 1, (22, 12, 12))

        img, glow = paint(lay, mats, decor, seed=41)
        out(name, img)


# ================================================================================================ cliff racer


@painter("cliff_racer")
def cliff_racer():
    lay = load("cliff_racer")
    skin = pal("#262a30", "#3e4650", "#5c6670", "#7e8890", "#a2aaae")
    belly = pal("#7a776a", "#a09c8a", "#c4bea8", "#ddd8c2")
    mats = {
        "skin": Mat(skin, "skin", base=0.55, belly=belly, counter=0.45, dorsal=0.1, grad=0.1, scale=0.8),
        "membrane": Mat(pal("#2c2622", "#4a3e36", "#6a5a4c", "#8a7864", "#a8967e"), "membrane", base=0.5),
        "sail": Mat(pal("#3a2018", "#5e3426", "#844e38", "#a86c4c", "#c88e66"), "membrane", base=0.55),
        "spine": Mat(pal("#1e1a16", "#3a322a", "#5a4e42", "#7a6c5c"), "horn", base=0.5),
        "crest": Mat(pal("#1e1a16", "#3a322a", "#5a4e42", "#7a6c5c"), "horn", base=0.55),
        "spike": Mat(pal("#2a2420", "#544a40", "#8a7e6e", "#bab0a0"), "horn", base=0.6),
        "claw": CLAW,
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            side_eyes(ctx, (232, 196, 70), row=0, from_front=1)
            maw_roof(ctx, step=3)
        if t == "beak":
            # upper row of needle teeth along the snout's lower edge
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(1, fw, 3):
                    ctx.put(f, x, fh - 1, (210, 204, 186))
            maw_roof(ctx, step=3)
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 0, fw, fh, (40, 36, 34))
        if t == "jaw":
            maw_floor(ctx, step=3)
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(2, fw, 3):
                    ctx.put(f, x, 0, (210, 204, 186))
        if t in ("sail", "tail_fin_v"):
            # spines radiating through the sail
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(0, fw, 2):
                    for y in range(fh):
                        ctx.tint(f, x, y, 0.72)
                for x in range(fw):
                    ctx.tint(f, x, 0, 1.2)
        if t == "membrane" or t == "tail_fin":
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.tint(f, x, 0, 0.7)
        if t == "torso":
            # pale flank stripe
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    if (x // 3) % 2 == 0:
                        ctx.tint(f, x, 1, 0.85)
        if t == "tail":
            bands(ctx, period=3, mul=0.85, faces=("top", "right", "left"))

    img, glow = paint(lay, mats, decor, seed=53)
    out("cliff_racer", img)


# ================================================================================================ guar


@painter("guar")
def guar():
    lay = load("guar")
    variants = {
        "guar": dict(hide=pal("#262a1e", "#3e4630", "#5a6444", "#7a845c", "#9ea47c"),
                     belly=pal("#7c7a5c", "#a29e7c", "#c4bc98", "#ddd4b2"),
                     ridge=pal("#1c1c14", "#30301f", "#4a4a32", "#646448")),
        "guar_pack": dict(hide=pal("#2e2618", "#4a3e28", "#6a5a3c", "#8c7a56", "#ae9c76"),
                          belly=pal("#80745a", "#a49678", "#c6b898", "#ded2b4"),
                          ridge=pal("#201a12", "#362c1e", "#504230", "#6a5a44")),
    }
    for name, v in variants.items():
        mats = {
            "hide": Mat(v["hide"], "skin", base=0.55, belly=v["belly"], counter=0.3, dorsal=0.1, scale=0.6,
                        contrast=0.75, spots=(0.16, 1.6, 0.16)),
            "belly": Mat(v["belly"], "scales", base=0.55, scale=1.2, contrast=0.5),
            "ridge": Mat(v["ridge"], "horn", base=0.55),
            "claw": CLAW,
            "leather": Mat(pal("#2e1c10", "#4e321e", "#70492c", "#93653e", "#b0824e"), "leather", base=0.55),
            "cloth": Mat(pal("#3c1612", "#62241a", "#8c3624", "#b04e2e", "#cc7040"), "cloth", base=0.55),
            "bag": Mat(pal("#4a3e2c", "#6a5a40", "#8a7858", "#a89470", "#c2b08a"), "cloth", base=0.55),
        }

        def decor(ctx):
            t = ctx.tag
            if t == "head":
                side_eyes(ctx, (40, 30, 18), row=0, from_front=3)
                # wide mouth line along both sides
                for f in ("right", "left"):
                    fw, fh = ctx.size(f)
                    for x in range(fw):
                        ctx.tint(f, x, fh - 1, 0.55)
                maw_roof(ctx, teeth=(200, 190, 160))
            if t == "jaw":
                maw_floor(ctx, teeth=(200, 190, 160))
                fw, fh = ctx.size("front")
                ctx.hline("front", 0, (60, 40, 30))
            if t == "nose":
                fw, fh = ctx.size("front")
                ctx.put("front", 1, 0, (24, 20, 14))
                ctx.put("front", fw - 2, 0, (24, 20, 14))
            if t == "belly":
                bands(ctx, period=2, mul=0.88, faces=("bottom", "right", "left", "front"))
                fw, fh = ctx.size("bottom")
                for y in range(0, fh, 2):
                    for x in range(fw):
                        ctx.tint("bottom", x, y, 0.88)
            if t == "blanket" or t == "blanket_side":
                # Ashlander-style woven band pattern
                for f in ctx.all_faces():
                    fw, fh = ctx.size(f)
                    for y in range(fh):
                        for x in range(fw):
                            k = (x + (y if f in SIDES else 0)) % 6 if f == "top" else y % 6
                            if f == "top":
                                k = x % 6
                            if k == 0:
                                ctx.put(f, x, y, (206, 168, 82))
                            elif k == 3:
                                ctx.put(f, x, y, (40, 30, 26))
            if t in ("seat", "cantle", "pommel", "bag_flap"):
                fw, fh = ctx.size("top")
                for x in range(fw):
                    ctx.tint("top", x, 0, 1.2)
                for f in SIDES:
                    fw, fh = ctx.size(f)
                    for x in range(0, fw, 2):
                        ctx.put(f, x, 0, (196, 170, 120))  # stitching
            if t == "bag":
                fw, fh = ctx.size("right")
                for f in ("right", "left", "front", "back"):
                    fw, fh = ctx.size(f)
                    ctx.hline(f, fh // 2, (70, 46, 26))  # strap
            if t == "bedroll":
                for f in ("right", "left", "top", "back", "front"):
                    fw, fh = ctx.size(f)
                    for x in range(fw):
                        if x % 5 == 2:
                            for y in range(fh):
                                ctx.put(f, x, y, (70, 46, 26))
            if t in ("thigh", "shin"):
                fw, fh = ctx.size("front")
            if t == "foot":
                pass

        img, glow = paint(lay, mats, decor, seed=61)
        out(name, img)


# ================================================================================================ kagouti


@painter("kagouti")
def kagouti():
    lay = load("kagouti")
    hide = pal("#30241a", "#4e3c2c", "#705a44", "#94785c", "#b49878")
    mats = {
        "hide": Mat(hide, "skin", base=0.55, belly=pal("#7a6450", "#9c8468", "#bca484", "#d4c09e"), counter=0.3,
                    dorsal=0.14, contrast=0.8),
        "belly": Mat(pal("#7a6450", "#9c8468", "#bca484", "#d4c09e"), "skin", base=0.55),
        "plate": Mat(pal("#2a2119", "#463829", "#64533e", "#857155", "#a8946f"), "chitin", base=0.55, scale=0.75),
        "tusk": IVORY,
        "spike": Mat(pal("#2a2018", "#4e3e30", "#7a6a56", "#a0927c"), "horn", base=0.6),
        "claw": CLAW,
        "muzzle": Mat(pal("#5a4632", "#7c644a", "#9c8264", "#bca282"), "skin", base=0.55),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            side_eyes(ctx, (240, 110, 30), row=2, from_front=0)
            fw, fh = ctx.size("front")
            # heavy brow shadow, small fierce eyes at the corners of the face
            for x in range(fw):
                ctx.tint("front", x, 0, 0.6)
                ctx.tint("front", x, 1, 0.8)
            eye(ctx, "front", 1, 2, (240, 110, 30))
            eye(ctx, "front", fw - 2, 2, (240, 110, 30))
        if t == "cheek":
            bands(ctx, period=2, mul=0.85)
        if t == "plate_side":
            ribs(ctx, period=2, mul=0.82)
        if t == "snout":
            maw_roof(ctx, teeth=(220, 210, 180))
            fw, fh = ctx.size("front")
            for y in range(fh):
                for x in range(fw):
                    ctx.tint("front", x, y, 1.18)
            ctx.hline("front", fh - 1, MOUTH_DARK)
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                ctx.hline(f, fh - 1, MOUTH_DARK)
        if t == "nose":
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 0, fw, fh, (96, 78, 60))
            ctx.put("front", 1, 1, (20, 14, 10))
            ctx.put("front", fw - 2, 1, (20, 14, 10))
        if t == "jaw":
            maw_floor(ctx, teeth=(220, 210, 180))
        if t == "plate":
            fw, fh = ctx.size("top")
            for x in range(fw):
                ctx.tint("top", x, 0, 1.2)
                ctx.tint("top", x, fh - 1, 0.7)
            for y in range(fh):
                ctx.tint("top", 0, y, 0.8)
                ctx.tint("top", fw - 1, y, 0.8)
            edge_dark(ctx, mul=0.7)
        if t == "tusk_tip":
            ctx.fill("top", (250, 244, 226))
        if t in ("leg", "paw"):
            bands(ctx, period=4, mul=0.9)

    img, glow = paint(lay, mats, decor, seed=71)
    out("kagouti", img)


# ================================================================================================ alit


@painter("alit")
def alit():
    lay = load("alit")
    hide = pal("#4a2a14", "#744420", "#9c6630", "#c08a48", "#dcae6a")
    mats = {
        "hide": Mat(hide, "skin", base=0.58, belly=pal("#a07850", "#c49a6a", "#dcbc8c", "#eed6a8"), counter=0.25,
                    spots=(0.22, 3, -0.3), contrast=0.7),
        "belly": Mat(pal("#a07850", "#c49a6a", "#dcbc8c", "#eed6a8"), "skin", base=0.55),
        "tooth": TOOTH,
        "claw": CLAW,
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            maw_roof(ctx)
            # lips: dark line along the bottom of the sides and front
            for f in ("front", "right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.tint(f, x, fh - 1, 0.5)
            # nostrils
            fw, fh = ctx.size("front")
            ctx.put("front", fw // 2 - 2, 1, (40, 20, 10))
            ctx.put("front", fw // 2 + 1, 1, (40, 20, 10))
        if t == "jaw":
            maw_floor(ctx)
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (60, 30, 20))
        if t == "eye_ridge":
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 0, fw, 1, (52, 28, 12))
            ctx.put("front", 1, 0, (244, 206, 52))
            ctx.put("front", 0, 0, (200, 150, 30))
            ctx.put("front", 2, 0, (20, 12, 6))
            for f in ("right", "left"):
                ctx.put(f, 0, 0, (244, 206, 52))
                ctx.put(f, 2, 0, (244, 206, 52))
                ctx.put(f, 1, 0, (20, 12, 6))
        if t in ("crown", "knob"):
            ctx.fill("top", (110, 60, 26))
        if t == "belly":
            bands(ctx, period=2, mul=0.9)
        if t in ("head", "torso", "tail", "neck", "thigh"):
            # dark transverse mottled bands across the back
            for f in ("top", "right", "left"):
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        along = y if f == "top" else x
                        across = x if f == "top" else y
                        wob = math.sin(across * 0.9 + along * 0.2) * 1.2
                        if f != "top" and y > fh * 0.55:
                            continue
                        if ((along + wob) % 5) < 1.6:
                            ctx.tint(f, x, y, 0.72)

    img, glow = paint(lay, mats, decor, seed=81)
    out("alit", img)


# ================================================================================================ nix-hound


@painter("nix_hound")
def nix_hound():
    lay = load("nix_hound")
    cara = pal("#170f18", "#2c1d2e", "#463048", "#624664", "#7e6280")
    joint = pal("#7c6e62", "#a4968a", "#c6baac", "#e0d6c8")
    mats = {
        "carapace": Mat(cara, "chitin", base=0.55, belly=joint, scale=1.2, grad=0.15, contrast=0.8),
        "joint": Mat(joint, "chitin", base=0.55, scale=0.6, contrast=0.6),
        "leg": Mat(shift(cara, mul=1.1), "chitin", base=0.5, scale=2.0, contrast=0.5),
        "crest": Mat(pal("#1e1218", "#3c2230", "#64384c", "#8a5466"), "horn", base=0.55),
        "claw": Mat(pal("#a49684", "#c8bcaa", "#e4dccc"), "horn", base=0.5),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            # two pairs of small pale eyes
            fw, fh = ctx.size("right")
            for f, xs in (("right", (fw - 1, fw - 3)), ("left", (0, 2))):
                ctx.put(f, xs[0], 1, (230, 220, 150))
                ctx.put(f, xs[1], 1, (200, 190, 120))
                ctx.tint(f, xs[0], 0, 0.6)
                ctx.tint(f, xs[1], 0, 0.6)
        if t == "snout":
            fw, fh = ctx.size("front")
            ctx.fill("front", (28, 18, 24))
            maw_roof(ctx, teeth=(200, 190, 170), step=3)
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                ctx.hline(f, fh - 1, (20, 12, 16))
        if t == "jaw":
            maw_floor(ctx, teeth=(200, 190, 170), step=3)
        if t in ("segment", "abdomen"):
            fw, fh = ctx.size("top")
            for x in range(fw):
                ctx.tint("top", x, fh - 1, 0.55)
                ctx.tint("top", x, 0, 1.3)
            for y in range(fh):
                ctx.tint("top", fw // 2, y, 1.15)
            edge_dark(ctx, mul=0.55)
        if t == "torso":
            bands(ctx, period=3, mul=0.75, faces=("right", "left"))
        if t == "leg":
            # pale joint rings at both ends of every leg segment
            for f in SIDES:
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.put(f, x, 0, joint[2])
                    ctx.put(f, x, fh - 1, joint[1])
        if t == "crest" or t == "spur":
            fw, fh = ctx.size("right")
            for f in ("right", "left"):
                for x in range(fw):
                    ctx.tint(f, x, 0, 1.3)

    img, glow = paint(lay, mats, decor, seed=91)
    out("nix_hound", img)


# ================================================================================================ silt strider


@painter("silt_strider")
def silt_strider():
    lay = load("silt_strider")
    shell = pal("#3a2e22", "#5e4c38", "#857054", "#ab9676", "#cab89a")
    mats = {
        "shell": Mat(shell, "chitin", base=0.55, belly=shift(shell, mul=0.8), scale=1.3, grad=0.2, contrast=0.8),
        "skin": Mat(pal("#2e2420", "#4a3a32", "#6a5446", "#8a705e", "#a68c78"), "skin", base=0.5),
        "leg": Mat(pal("#1e1611", "#33271d", "#4e3e2e", "#6a5640", "#86704f"), "chitin", base=0.55, scale=1.5),
        "wood": Mat(pal("#3a2618", "#5a3c24", "#7a5634", "#9a7048"), "bark", base=0.55),
        "leather": Mat(pal("#3a1e14", "#5c3020", "#80462e", "#a06040"), "leather", base=0.55),
        "rope": Mat(pal("#6e5a3a", "#9a8256", "#c0aa78"), "cloth", base=0.55),
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("shell", "shell_band", "keel", "abdomen", "abdomen_tip"):
            # growth rings around the shell and darker seams along the top
            bands(ctx, period=3, mul=0.84, light=1.06)
            fw, fh = ctx.size("top")
            for y in range(0, fh, 4):
                for x in range(fw):
                    ctx.tint("top", x, y, 0.85)
            for x in range(0, fw, 5):
                for y in range(fh):
                    ctx.tint("top", x, y, 0.9)
        if t in ("rim", "rim_side"):
            edge_light(ctx, "top", 1.25)
            for f in SIDES:
                edge_light(ctx, f, 1.15)
        if t == "deck":
            fw, fh = ctx.size("top")
            for x in range(0, fw, 2):
                for y in range(fh):
                    ctx.tint("top", x, y, 0.78)
            ctx.put("top", 1, 1, (40, 30, 20))
            ctx.put("top", fw - 2, fh - 2, (40, 30, 20))
        if t == "head":
            side_eyes(ctx, (20, 16, 14), row=1, from_front=1, big=False)
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                ctx.put(f, fw - 2 if f == "right" else 1, 1, (150, 140, 120))
        if t in ("proboscis", "proboscis_tip"):
            bands(ctx, period=2, mul=0.82)
        if t == "proboscis_end":
            ctx.fill("front", (30, 20, 18))
        if t in ("leg_upper", "leg_lower", "leg_tip"):
            bands(ctx, period=5, mul=0.8, light=1.1)
        if t == "knee":
            edge_light(ctx, "top", 1.3)

    img, glow = paint(lay, mats, decor, seed=101)
    out("silt_strider", img)


# ================================================================================================ minotaur


@painter("minotaur")
def minotaur():
    lay = load("minotaur")
    fur = pal("#22160e", "#3c2818", "#5a3e26", "#7a5636", "#97704a")
    mats = {
        "fur": Mat(fur, "fur", base=0.55, counter=-0.1, grad=0.15),
        "mane": Mat(shift(fur, mul=0.75), "hair", base=0.5, contrast=0.6),
        "muzzle": Mat(pal("#4a3430", "#6e524a", "#927266", "#b09086", "#c8aaa0"), "skin", base=0.5),
        "skin": Mat(pal("#2a1c16", "#44302a", "#604640", "#7c5e56"), "skin", base=0.5),
        "horn": Mat(pal("#5a4e3c", "#8c7e64", "#b8aa8a", "#ddd2b4", "#f0e8d2"), "horn", base=0.65, grad=0.2),
        "hoof": HOOF,
        "loincloth": Mat(pal("#2e1a12", "#4a2c1c", "#6a4028", "#8a5636"), "leather", base=0.55),
        "leather": Mat(pal("#1e140e", "#352418", "#523a26", "#6e5034"), "leather", base=0.55),
        "metal": Mat(pal("#1e2024", "#383b40", "#595d64", "#80858c", "#a8adb4"), "metal", base=0.55),
        "wood": Mat(pal("#2a1c12", "#46301e", "#64462c", "#7e5c3c"), "bark", base=0.55, scale=0.7),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("front")
            for x in range(fw):
                ctx.tint("front", x, 2, 0.7)
            eye(ctx, "front", 1, 3, (180, 40, 20))
            eye(ctx, "front", fw - 2, 3, (180, 40, 20))
        if t == "muzzle":
            fw, fh = ctx.size("front")
            ctx.put("front", 1, 1, (16, 10, 8))
            ctx.put("front", fw - 2, 1, (16, 10, 8))
            ctx.hline("front", fh - 1, (30, 18, 14), 1, fw - 1)
            fw, fh = ctx.size("top")
            for x in range(fw):
                ctx.tint("top", x, 0, 1.1)
        if t == "horn_tip":
            for f in ctx.all_faces():
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        ctx.tint(f, x, y, 0.55)
        if t == "torso":
            # pectoral and ab shading on the chest
            fw, fh = ctx.size("front")
            for y in range(fh):
                ctx.tint("front", fw // 2, y, 0.8)
            for x in range(1, fw - 1):
                ctx.tint("front", x, fh // 3, 0.8)
            for y in range(fh // 3 + 2, fh, 2):
                for x in range(fw // 2 - 2, fw // 2 + 2):
                    ctx.tint("front", x, y, 0.88)
        if t == "belt":
            fw, fh = ctx.size("front")
            ctx.rect("front", fw // 2 - 1, 0, 2, fh, (160, 150, 120))
        if t == "flap":
            fw, fh = ctx.size("front")
            for x in range(fw):
                if x % 3 == 0:
                    ctx.tint("front", x, fh - 1, 0.4)
            ribs(ctx, period=3, mul=0.85, faces=("front", "back"))
        if t == "hammer":
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        ctx.tint(f, x, y, 1.18)
            for f in SIDES:
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.tint(f, x, 0, 1.3)
                    ctx.tint(f, x, fh - 1, 1.3)
        if t == "haft":
            for f in SIDES:
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        if (x + y) % 4 == 0 and fw > 3:
                            ctx.tint(f, x, y, 0.75)  # leather wrap at the grip end
        if t == "foot":
            fw, fh = ctx.size("front")
            ctx.put("front", fw // 2, 0, (10, 8, 6))
            ctx.put("front", fw // 2, 1, (10, 8, 6))

    img, glow = paint(lay, mats, decor, seed=111)
    out("minotaur", img)


# ================================================================================================ ogre


@painter("ogre")
def ogre():
    lay = load("ogre")
    skin = pal("#262c22", "#3e4636", "#5a624e", "#788068", "#969c84")
    mats = {
        "skin": Mat(skin, "skin", base=0.55, counter=0.1, grad=0.15, dorsal=0.08, spots=(0.14, 2.2, -0.16)),
        "belly": Mat(pal("#5a604a", "#767c62", "#92987c", "#adb296"), "skin", base=0.55, grad=0.25),
        "hide": Mat(pal("#2e2014", "#4a3420", "#684a30", "#866444", "#a07c58"), "fur", base=0.55),
        "hair": Mat(pal("#0e0c0a", "#1e1a16", "#302a24"), "hair", base=0.5),
        "tooth": IVORY,
        "bone": Mat(pal("#8a806c", "#b0a690", "#d6ccb4", "#eee6d0"), "bone", base=0.6),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("front")
            eye(ctx, "front", 1, 2, (220, 180, 40))
            eye(ctx, "front", fw - 2, 2, (220, 180, 40))
            ctx.hline("front", fh - 1, (30, 20, 16), 1, fw - 1)
        if t == "brow":
            edge_dark(ctx, faces=("front",), mul=0.6)
        if t == "nose":
            fw, fh = ctx.size("front")
            ctx.put("front", 0, fh - 1, (20, 18, 14))
            ctx.put("front", fw - 1, fh - 1, (20, 18, 14))
        if t == "jaw":
            fw, fh = ctx.size("front")
            maw_floor(ctx, teeth=(200, 190, 150), step=3)
            ctx.hline("front", 0, (40, 26, 20))
        if t == "belly_front":
            fw, fh = ctx.size("front")
            ctx.put("front", fw // 2, fh // 2 + 1, (40, 40, 30))
        if t == "belly":
            fw, fh = ctx.size("front")
            for x in range(fw):
                ctx.tint("front", x, 0, 0.75)
            bands(ctx, period=3, mul=0.92, faces=("right", "left"))
        if t == "torso":
            # scars across the shoulders
            fw, fh = ctx.size("back")
            for i in range(min(fw, fh) - 2):
                ctx.tint("back", 1 + i, 2 + i // 2, 1.3)
        if t in ("flap", "pelt"):
            for f in ("front", "back", "right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    if (x * 5) % 7 < 3:
                        ctx.tint(f, x, fh - 1, 0.4)
        if t == "necklace":
            fw, fh = ctx.size("front")
            for x in range(0, fw, 2):
                ctx.put("front", x, 0, (140, 120, 90))
        if t == "hand":
            fw, fh = ctx.size("front")
            for x in range(0, fw, 2):
                ctx.tint("front", x, fh - 1, 0.6)

    img, glow = paint(lay, mats, decor, seed=121)
    out("ogre", img)


# ================================================================================================ land dreugh


@painter("land_dreugh")
def land_dreugh():
    lay = load("land_dreugh")
    cara = pal("#2c0e0a", "#521a10", "#7a2a1a", "#a0432a", "#c06644")
    mats = {
        "carapace": Mat(cara, "skin", base=0.55, scale=1.4, grad=0.3, contrast=0.6),
        "shell": Mat(shift(cara, mul=1.05), "skin", base=0.55, scale=1.2, grad=0.25, contrast=0.5),
        "flesh": Mat(pal("#5c3a30", "#80564a", "#a27464", "#c49482", "#dcb4a0"), "skin", base=0.55, counter=0.2),
        "claw": Mat(pal("#1e0a08", "#3c140e", "#6a2618", "#94402a", "#b8604a"), "horn", base=0.55, grad=0.3),
        "eye": Mat(pal("#080808", "#1a1614", "#38302c"), "plain", base=0.5),
    }
    pale = (222, 186, 160)

    def decor(ctx):
        t = ctx.tag
        if t in ("back_shell", "back_dome", "helmet", "collar", "pauldron", "palm"):
            # broad plates: a dark seam every 5 rows with a bright rim below it, scattered nodules
            bands(ctx, period=5, mul=0.6, light=1.22, faces=("back", "right", "left", "front"), offset=4)
            fw, fh = ctx.size("top")
            for x in range(fw):
                ctx.tint("top", x, 0, 1.25)
            for y in range(0, fh, 5):
                for x in range(fw):
                    ctx.tint("top", x, y, 0.7)
            fw, fh = ctx.size("back")
            for y in range(2, fh, 5):
                for x in range(2 + (y // 5) % 2 * 2, fw - 1, 4):
                    ctx.tint("back", x, y, 1.35)
        if t == "plastron":
            bands(ctx, period=3, mul=0.7, light=1.15, faces=("front",))
        if t in ("torso", "hips"):
            bands(ctx, period=4, mul=0.82, faces=("front",))
        if t == "head":
            fw, fh = ctx.size("front")
            maw_roof(ctx, teeth=(230, 210, 190), inner=(60, 20, 22), step=2)
            ctx.hline("front", fh - 1, (40, 14, 12))
        if t == "jaw":
            maw_floor(ctx, teeth=(230, 210, 190), inner=(60, 20, 22))
        if t == "stalk":
            fw, fh = ctx.size("top")
            ctx.fill("top", (230, 200, 60))
            for f in SIDES:
                ctx.put(f, 0, 0, (230, 200, 60))
        if t in ("leg", "shin", "arm", "forearm", "crab_leg", "crab_leg_low"):
            # pale soft joints between the shell segments
            for f in SIDES:
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.put(f, x, 0, pale)
                    if fh > 4:
                        ctx.put(f, x, fh // 2, lerp(pale, cara[2], 0.5))
        if t == "finger":
            for f in SIDES:
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.tint(f, x, fh - 1, 0.6)
                for y in range(fh):
                    if y % 2 == 0:
                        ctx.put(f, 0, y, (210, 190, 160))  # serrated inner edge
        if t == "feeler":
            bands(ctx, period=2, mul=0.85)

    img, glow = paint(lay, mats, decor, seed=131)
    out("land_dreugh", img)


# ================================================================================================ wamasu


def transverse(ctx, period, mul, width=1.6, max_side=0.6, faces=("top", "right", "left"), wobble=1.2, phase=0):
    """Dark bands across the back (top face rows, side face columns), fading down the flanks."""
    for f in faces:
        fw, fh = ctx.size(f)
        for y in range(fh):
            for x in range(fw):
                along = y if f == "top" else x
                across = x if f == "top" else y
                if f != "top" and y > fh * max_side:
                    continue
                wob = math.sin(across * 0.9 + along * 0.2) * wobble
                if ((along + wob + phase) % period) < width:
                    ctx.tint(f, x, y, mul)


@painter("wamasu")
def wamasu():
    lay = load("wamasu")
    scales = pal("#081a1e", "#11313a", "#1a4a52", "#26646a", "#378284")
    belly = pal("#7a5e14", "#a4821e", "#c8a232", "#e0c052")
    spark = (176, 228, 255)
    core = (236, 250, 255)
    mats = {
        "scales": Mat(scales, "scales", base=0.55, belly=belly, counter=0.2, dorsal=0.08, scale=1.0, contrast=0.4),
        "belly": Mat(belly, "scales", base=0.55, scale=1.3, contrast=0.4),
        "spine": Mat(pal("#0c1418", "#182830", "#26404a", "#365a64"), "horn", base=0.5),
        "crest_skin": Mat(pal("#0a2028", "#123440", "#1c4c58", "#286470"), "membrane", base=0.5),
        "tooth": TOOTH,
        "claw": CLAW,
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("torso", "neck", "tail", "thigh", "head", "jaw"):
            # yellow underside creeping up the lower flanks
            for f in ("right", "left", "front", "back") if t in ("neck", "jaw") else ("right", "left"):
                fw, fh = ctx.size(f)
                rows = 2 if fh > 3 else 1
                for y in range(fh - rows, fh):
                    for x in range(fw):
                        ctx.put(f, x, y, belly[2] if y == fh - 1 else belly[1])
        if t in ("torso", "neck", "tail", "thigh", "head"):
            transverse(ctx, 5, 0.7, width=1.4, max_side=0.5)
            # a thin electric line along the spine
            fw, fh = ctx.size("top")
            if t != "head":
                for y in range(fh):
                    if y % 3 != 2:
                        ctx.put("top", fw // 2, y, spark, glow=True)
        if t == "head":
            side_eyes(ctx, core, row=1, from_front=2, pupil=(30, 80, 120), glow=True, big=False)
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                ctx.put(f, fw - 3 if f == "right" else 2, 1, spark, glow=True)
            maw_roof(ctx, teeth=(220, 220, 200), step=2)
        if t == "jaw":
            maw_floor(ctx, teeth=(220, 220, 200))
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (20, 30, 30))
        if t == "spine":
            # glowing core stripe up each spine, brighter tip
            for f in ("front", "back", "right", "left"):
                fw, fh = ctx.size(f)
                for y in range(fh):
                    ctx.put(f, fw // 2, y, spark if y > 0 else core, glow=True)
            ctx.fill("top", core, glow=True)
        if t == "crest_skin":
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    for y in range(fh):
                        if (x + y) % 3 == 0 and y < fh - 1:
                            ctx.put(f, x, y, spark, glow=True)
        if t == "belly":
            bands(ctx, period=2, mul=0.88, faces=("bottom", "right", "left", "front"))

    img, glow = paint(lay, mats, decor, seed=141)
    out("wamasu", img, glow)


# ================================================================================================ swamp leviathan


@painter("swamp_leviathan")
def swamp_leviathan():
    lay = load("swamp_leviathan")
    hide = pal("#181c12", "#2a301e", "#40482c", "#58603a", "#72784c")
    belly = pal("#5a5a3c", "#7a7852", "#9a966c", "#b6b086")
    mats = {
        "hide": Mat(hide, "scales", base=0.55, belly=belly, counter=0.25, dorsal=0.08, scale=1.2, contrast=0.6),
        "belly": Mat(belly, "scales", base=0.55, scale=1.6, contrast=0.4),
        "scute": Mat(pal("#10120c", "#22261a", "#363c28", "#4c5236"), "horn", base=0.55),
        "moss": Mat(pal("#16280e", "#244214", "#36601c", "#4c7c28", "#669836"), "fur", base=0.55, scale=0.7),
        "growth": Mat(pal("#3a3220", "#5e5030", "#86723e", "#a89052"), "mushroom", base=0.55),
        "tooth": TOOTH,
        "claw": CLAW,
    }

    def decor(ctx):
        t = ctx.tag
        if t in ("torso", "tail", "neck"):
            transverse(ctx, 6, 0.78, width=2, max_side=0.5)
        if t == "eye_bump":
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 0, fw, 1, (40, 44, 24))
            ctx.put("front", 1, 1, (200, 190, 40))
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                ctx.rect(f, 0, 0, fw, fh, (34, 38, 22))
                ctx.put(f, 1, 0, (210, 200, 50))
                ctx.put(f, 1, 1, (20, 16, 6))
        if t == "snout":
            maw_roof(ctx, teeth=(220, 214, 190), inner=(80, 40, 36))
            for f in ("right", "left", "front"):
                fw, fh = ctx.size(f)
                ctx.hline(f, fh - 1, (26, 20, 14))
            fw, fh = ctx.size("top")
            for y in range(0, fh, 2):
                for x in range(fw):
                    if (x + y // 2) % 3 == 0:
                        ctx.tint("top", x, y, 0.8)
        if t == "jaw":
            maw_floor(ctx, teeth=(220, 214, 190), inner=(80, 40, 36), tongue=(150, 100, 80))
        if t == "nostrils":
            ctx.put("top", 0, 0, (14, 12, 8))
            fw, fh = ctx.size("top")
            ctx.put("top", fw - 1, 0, (14, 12, 8))
        if t in ("moss", "moss_side"):
            # straggly strands hanging off the edges
            for f in SIDES:
                fw, fh = ctx.size(f)
                for x in range(fw):
                    if (x * 7) % 5 < 2:
                        ctx.tint(f, x, fh - 1, 0.6)
        if t == "growth":
            # swamp fungus caps: pale rim, dark pitted centre
            fw, fh = ctx.size("top")
            ctx.fill("top", (176, 160, 104))
            ctx.put("top", fw // 2, fh // 2, (70, 60, 36))
            for f in SIDES:
                ctx.hline(f, 0, (150, 136, 88))
        if t == "belly":
            bands(ctx, period=2, mul=0.86, faces=("bottom",))

    img, glow = paint(lay, mats, decor, seed=151)
    out("swamp_leviathan", img)


# ================================================================================================ hackwing


@painter("hackwing")
def hackwing():
    lay = load("hackwing")
    plum = pal("#08161a", "#0f2a2e", "#173f40", "#215852", "#2f7464")
    red = pal("#4a0a0a", "#7c1412", "#ac241c", "#d4402c")
    mats = {
        "feather": Mat(plum, "feather", base=0.55, belly=shift(plum, mul=1.25), counter=0.2, scale=0.8),
        "wing": Mat(plum, "feather", base=0.5, scale=1.0),
        "tailfeather": Mat(pal("#08141c", "#0f2632", "#183c4a", "#245462", "#386e7a"), "feather", base=0.5, scale=1.2),
        "throat": Mat(red, "feather", base=0.6, scale=0.7),
        "beak": Mat(pal("#2a261c", "#544c38", "#867a5c", "#b4a880", "#d4caa2"), "horn", base=0.6, grad=0.3),
        "crest": Mat(red, "feather", base=0.5, scale=0.7),
        "talon": Mat(pal("#1a1610", "#3a3224", "#6a5c40"), "horn", base=0.5),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            side_eyes(ctx, (236, 160, 30), row=1, from_front=0, big=False)
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("bottom", x, y, red[2])
        if t == "neck":
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("bottom", x, y, red[2] if (x + y) % 3 else red[1])
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.put(f, x, fh - 1, red[1])
        if t == "hook":
            for f in ctx.all_faces():
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        ctx.tint(f, x, y, 0.55)
        if t == "beak":
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                ctx.hline(f, fh - 1, (40, 34, 24))
        if t in ("membrane", "tailfeather"):
            # pale bars near the feather tips, dark trailing edge
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.tint(f, x, fh - 1, 0.5)
                    if fh > 4:
                        ctx.tint(f, x, fh - 3, 1.5)
        if t == "torso":
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, fh - 2, fw, 2, red[2])
        if t == "breast":
            fw, fh = ctx.size("front")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("front", x, y, red[2] if y < 2 else lerp(red[1], plum[2], 0.5))
            fw, fh = ctx.size("bottom")
            for y in range(min(2, fh)):
                for x in range(fw):
                    ctx.put("bottom", x, y, red[1])

    img, glow = paint(lay, mats, decor, seed=161)
    out("hackwing", img)


# ================================================================================================ senche tiger


def tiger_stripes(ctx, faces, period=4, width=1.4, color=(26, 16, 10), skip_bottom=0.75, seed=0, vertical=True):
    for f in faces:
        fw, fh = ctx.size(f)
        for y in range(fh):
            if f != "top" and y > fh * skip_bottom:
                continue
            for x in range(fw):
                along = (y if f == "top" else x) if vertical else y
                across = (x if f == "top" else y) if vertical else x
                wob = math.sin(across * 0.7 + seed + along * 0.3) * 1.3
                taper = 1.0 - (across / max(1, fh if f != "top" else fw)) * 0.5 if f != "top" else 1.0
                if ((along + wob + seed) % period) < width * taper:
                    ctx.put(f, x, y, color)


@painter("senche_tiger")
def senche_tiger():
    lay = load("senche_tiger")
    fur = pal("#5a2206", "#8a3a0c", "#b85a16", "#d87a2a", "#eca04e")
    white = pal("#9e9484", "#c8bea8", "#e6dece", "#faf6ee")
    mats = {
        "fur": Mat(fur, "fur", base=0.55, belly=white, counter=0.1, grad=0.1),
        "face": Mat(fur, "fur", base=0.6, grad=0.05),
        "tail": Mat(fur, "fur", base=0.55),
        "belly": Mat(white, "fur", base=0.55),
        "muzzle": Mat(white, "fur", base=0.6),
        "ruff": Mat(white, "hair", base=0.6, contrast=0.5),
        "nose": Mat(pal("#2a1414", "#4a2626", "#6a3a3a"), "plain", base=0.5),
        "tooth": TOOTH,
        "claw": CLAW,
    }
    black = (24, 14, 10)

    def decor(ctx):
        t = ctx.tag
        if t in ("torso", "chest"):
            tiger_stripes(ctx, ("right", "left"), period=4, width=1.5, seed=1)
            tiger_stripes(ctx, ("top",), period=4, width=1.6, seed=2)
            # pale underside running up the flanks
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.put(f, x, fh - 1, white[2])
        if t == "neck":
            tiger_stripes(ctx, ("right", "left", "top"), period=3, width=1.2, seed=3)
        if t == "leg":
            for f in SIDES:
                fw, fh = ctx.size(f)
                for y in range(1, fh - 2, 3):
                    for x in range(fw):
                        if f != "back" and (x + y) % 5 != 0:
                            ctx.put(f, x, y, black)
        if t == "tail":
            for f in ("top", "right", "left", "bottom"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    for y in range(fh):
                        along = y if f in ("top", "bottom") else x
                        if along % 3 == 0:
                            ctx.put(f, x, y, black)
        if t == "head":
            fw, fh = ctx.size("front")
            # white brows and cheeks, forehead stripes, amber-green eyes
            ctx.put("front", 1, 1, white[3])
            ctx.put("front", fw - 2, 1, white[3])
            eye(ctx, "front", 1, 2, (190, 200, 60), pupil=(10, 10, 6), big=True)
            eye(ctx, "front", fw - 3, 2, (190, 200, 60), pupil=(10, 10, 6), big=True)
            ctx.put("front", fw // 2 - 1, 0, black)
            ctx.put("front", fw // 2, 0, black)
            ctx.put("front", fw // 2, 1, black)
            ctx.put("front", 0, 0, black)
            ctx.put("front", fw - 1, 0, black)
            for x in range(fw):
                for y in range(fh - 2, fh):
                    ctx.put("front", x, y, white[2])
            tiger_stripes(ctx, ("top", "right", "left"), period=3, width=1.1, seed=5)
            maw_roof(ctx, teeth=(240, 236, 220), step=3)
        if t == "snout":
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, (40, 24, 20))
            ctx.put("front", fw // 2, fh - 2, (40, 24, 20))
            maw_roof(ctx, teeth=(240, 236, 220), step=2)
        if t == "jaw":
            maw_floor(ctx, teeth=(240, 236, 220), step=2)
        if t == "ear":
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 0, fw, fh, (40, 24, 18))
            fw, fh = ctx.size("back")
            ctx.rect("back", 0, 0, fw, fh, black)
            ctx.put("back", 0, fh - 1, white[3])
        if t == "ruff":
            for f in ("right", "left", "front"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    if x % 2 == 0:
                        ctx.put(f, x, 1, black)

    img, glow = paint(lay, mats, decor, seed=171)
    out("senche_tiger", img)


# ================================================================================================ alfiq


@painter("alfiq")
def alfiq():
    lay = load("alfiq")
    fur = pal("#5a4426", "#806238", "#a6844e", "#c4a46a", "#dcc28e")
    cream = pal("#a8987a", "#cabca0", "#e4d8c0", "#f6eedc")
    rune = (150, 214, 255)
    rune2 = (196, 160, 255)
    mats = {
        "fur": Mat(fur, "fur", base=0.58, belly=cream, counter=0.25, grad=0.1),
        "face": Mat(fur, "fur", base=0.62),
        "tail": Mat(fur, "hair", base=0.55, contrast=0.5, scale=0.7),
        "belly": Mat(cream, "fur", base=0.55),
        "gold": Mat(pal("#5a3c0c", "#94701a", "#c89e2c", "#f0d064"), "metal", base=0.6, scale=0.5),
        "claw": CLAW,
    }

    def decor(ctx):
        t = ctx.tag
        if t == "torso":
            # glowing arcane sigils along the flanks: a ring with a dot and a trailing line
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                cx = fw // 2
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    ctx.put(f, cx + dx, 1 + dy + 1, rune, glow=True)
                ctx.put(f, cx, 2, rune2, glow=True)
                for x in range(1, fw - 1):
                    if abs(x - cx) > 2 and x % 2 == 0:
                        ctx.put(f, x, 1, rune, glow=True)
            fw, fh = ctx.size("top")
            for y in range(1, fh - 1, 2):
                ctx.put("top", fw // 2, y, rune, glow=True)
            # faint tabby stripes
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(0, fw, 3):
                    ctx.tint(f, x, 0, 0.8)
        if t == "head":
            fw, fh = ctx.size("front")
            eye(ctx, "front", 0, 1, (140, 220, 120), pupil=(10, 20, 10), big=True)
            eye(ctx, "front", fw - 2, 1, (140, 220, 120), pupil=(10, 20, 10), big=True)
            # forehead rune
            ctx.put("front", fw // 2, 0, rune, glow=True)
            fw, fh = ctx.size("top")
            ctx.put("top", fw // 2, fh - 2, rune, glow=True)
            ctx.put("top", fw // 2, fh - 1, rune2, glow=True)
            ctx.put("top", fw // 2 - 1, fh - 1, rune, glow=True)
            ctx.put("top", fw // 2 + 1, fh - 1, rune, glow=True)
        if t == "snout":
            fw, fh = ctx.size("front")
            ctx.put("front", fw // 2, 0, (200, 120, 120))
            ctx.put("front", fw // 2 - 1, 0, (160, 90, 90))
            maw_roof(ctx, teeth=(240, 236, 220), step=2)
        if t == "jaw":
            maw_floor(ctx, teeth=(240, 236, 220), step=2)
        if t == "ear":
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 1, fw, fh - 1, (200, 140, 130))
            ctx.put("front", 0, 0, (60, 44, 26))
        if t in ("tail", "tail_tuft"):
            fw, fh = ctx.size("top")
            for y in range(1, fh, 3):
                for x in range(fw):
                    ctx.put("top", x, y, rune, glow=True) if t == "tail" else None
            if t == "tail_tuft":
                fw, fh = ctx.size("back")
                for y in range(ctx.size("back")[1]):
                    for x in range(ctx.size("back")[0]):
                        ctx.tint("back", x, y, 1.15)
        if t == "leg":
            for f in ("front",):
                fw, fh = ctx.size(f)
                ctx.put(f, fw // 2, fh // 2, rune, glow=True)

    img, glow = paint(lay, mats, decor, seed=181)
    out("alfiq", img, glow)


# ================================================================================================ main


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)


if __name__ == "__main__":
    main(sys.argv[1:])
