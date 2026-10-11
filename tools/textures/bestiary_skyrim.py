"""Textures of Skyrim's creatures (SkyrimCreatures.java rigs), painted from the rig layouts by rigpaint.py.

Run tools/textures/dump_rigs.sh SkyrimCreatures first when a rig changed, then (from tools/textures):
    py -3 bestiary_skyrim.py [name ...]
Writes textures/entity/creatures/<name>[_<variant>].png (+ _glow.png emissive layers). All original work.
"""
import math
import sys

from rigpaint import Mat, clamp, hash2, lerp, load, paint, pal, save, has_pixels, shift, vnoise

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


# ================================================================================================ helpers

SIDE_FACES = ("front", "back", "right", "left")
LONG_FACES = ("top", "front", "back", "bottom")  # faces whose texture x runs along a box's x (width)


def mix(ctx, face, x, y, color, t, glow=False):
    """Blends a pixel toward a colour by t, keeping the painted shading underneath."""
    c = ctx.get(face, x, y)
    if c is None:
        return
    r = lerp(c[:3], color, t)
    ctx.put(face, x, y, (r[0], r[1], r[2], c[3]), glow)


def mul(ctx, face, x, y, k):
    ctx.tint(face, x, y, k)


def each(ctx, face):
    fw, fh = ctx.size(face)
    for y in range(fh):
        for x in range(fw):
            yield x, y, fw, fh


def bands(ctx, period, width, k, faces=LONG_FACES, offset=0):
    """Dark (k < 1) or light (k > 1) bands across a limb box that runs along x."""
    for f in faces:
        for x, y, fw, fh in each(ctx, f):
            if (x + offset) % period < width:
                mul(ctx, f, x, y, k)


def alpha_set(ctx, face, x, y, a):
    c = ctx.get(face, x, y)
    if c is not None:
        ctx.put(face, x, y, (c[0], c[1], c[2], a))


def model_xyz(ctx, face, x, y):
    """Bone-space centre of a face pixel (unmirrored boxes), so patterns run on across several boxes."""
    b = ctx.box
    bx, by, bz, w, h, d = b["x"], b["y"], b["z"], b["w"], b["h"], b["d"]
    if face == "top":
        return bx + x + 0.5, by, bz + d - y - 0.5
    if face == "bottom":
        return bx + x + 0.5, by + h, bz + d - y - 0.5
    if face == "front":
        return bx + x + 0.5, by + y + 0.5, bz
    if face == "back":
        return bx + w - x - 0.5, by + y + 0.5, bz + d
    if face == "right":
        return bx, by + y + 0.5, bz + d - x - 0.5
    return bx + w, by + y + 0.5, bz + x + 0.5


def glossy_eye(ctx, face, x, y, dark, hi, size=1, glow=False):
    if size == 1:
        ctx.put(face, x, y, dark, glow)
    else:
        ctx.rect(face, x, y, size, size, dark, glow)
        ctx.put(face, x, y, hi, glow)


# ================================================================================================ frostbite spider


@painter("frostbite_spider")
def frostbite_spider():
    lay = load("frostbite_spider")
    variants = {
        "frostbite_spider": dict(
            shell=pal("#1e242c", "#3a4652", "#62707e", "#93a2b0", "#c4ced8"),
            fur=pal("#262c34", "#46525e", "#727f8c", "#a6b2be", "#d6dde4"),
            abd=pal("#1c232c", "#36414e", "#55636f", "#7b8996", "#a6b2be"),
            frost=(238, 245, 251), band=0.55, eye=(14, 16, 22), hi=(150, 200, 230)),
        "frostbite_spider_giant": dict(
            shell=pal("#3a4654", "#62728a", "#8ea0b6", "#bccada", "#e6eef6"),
            fur=pal("#44505e", "#6c7c8e", "#9cacbe", "#c8d4e0", "#f0f5fa"),
            abd=pal("#34404e", "#56667a", "#7a8ca0", "#a2b4c6", "#cad8e4"),
            frost=(226, 246, 255), band=0.62, eye=(10, 18, 30), hi=(170, 235, 255)),
    }
    for name, v in variants.items():
        mats = {
            "carapace": Mat(v["shell"], "chitin", base=0.5, contrast=0.8, scale=1.2),
            "abdomen": Mat(v["abd"], "fur", base=0.55, contrast=0.6, counter=-0.2, grad=0.1, ao=0.04,
                           belly=shift(v["abd"], mul=0.7)),
            "leg": Mat(v["fur"], "fur", base=0.55, contrast=1.1, scale=0.7),
            "hair": Mat(shift(v["fur"], mul=0.8), "hair", base=0.5, contrast=0.6),
            "fang": Mat(pal("#08080a", "#1c1d22", "#3a3d46", "#6a707c"), "horn", base=0.45),
        }

        def decor(ctx, v=v):
            tag = ctx.tag
            if tag == "leg" or tag == "palp":
                # dark joint bands and a dark foot end: striped, hairy legs
                bands(ctx, 5, 2, v["band"], offset=1)
                for f in LONG_FACES:
                    for x, y, fw, fh in each(ctx, f):
                        if x < 2:
                            mul(ctx, f, x, y, 0.55)
                        elif ctx.rnd.random() < 0.08:
                            mix(ctx, f, x, y, (240, 246, 250), 0.5)
            if tag == "head":
                # big front eyes, small side pairs; dark mouthparts below
                fw, fh = ctx.size("front")
                cx = fw // 2
                glossy_eye(ctx, "front", cx - 2, 0, v["eye"], v["hi"], 2)
                glossy_eye(ctx, "front", cx + 1, 0, v["eye"], v["hi"], 2)
                glossy_eye(ctx, "front", 0, 1, v["eye"], v["hi"])
                glossy_eye(ctx, "front", fw - 1, 1, v["eye"], v["hi"])
                for x in range(1, fw - 1):
                    mul(ctx, "front", x, fh - 1, 0.6)
                for f in ("right", "left"):
                    sw, sh = ctx.size(f)
                    glossy_eye(ctx, f, sw - 2 if f == "right" else 1, 1, v["eye"], v["hi"])
            if tag == "eyes":
                fw, fh = ctx.size("top")
                for (x, y) in ((1, fh - 1), (fw - 2, fh - 1), (0, fh - 2), (fw - 1, fh - 2)):
                    glossy_eye(ctx, "top", x, y, v["eye"], v["hi"])
                fw, fh = ctx.size("front")
                glossy_eye(ctx, "front", 1, 0, v["eye"], v["hi"])
                glossy_eye(ctx, "front", fw - 2, 0, v["eye"], v["hi"])
            if tag in ("thorax", "dome"):
                # dark radial groove and a pale midline on the carapace
                fw, fh = ctx.size("top")
                for x, y, fw, fh in each(ctx, "top"):
                    if abs(x - (fw - 1) / 2.0) < 0.6:
                        mix(ctx, "top", x, y, v["frost"], 0.45)
                    elif abs(x - (fw - 1) / 2.0) < 1.6 and y in (fh // 2, fh // 2 - 1):
                        mul(ctx, "top", x, y, 0.6)
            if tag.startswith("abdomen"):
                frost_abdomen(ctx, v)
            if tag == "spinneret":
                for f in ctx.all_faces():
                    for x, y, fw, fh in each(ctx, f):
                        mix(ctx, f, x, y, v["frost"], 0.3)

        img, glow = paint(lay, mats, decor, seed=41)
        out(name, img)


def frost_abdomen(ctx, v):
    """Pale frost folium with dark chevrons over the back, dark bands curving down the flanks, pale hair flecks.
    Evaluated in abdomen bone space (x -7..7, y -7 top .. 6, z 0 front .. 18 back) so it runs across the blocks."""
    frost = v["frost"]
    for f in ("top", "right", "left", "back", "front"):
        for x, y, fw, fh in each(ctx, f):
            X, Y, Z = model_xyz(ctx, f, x, y)
            s = max(0.0, min(1.0, Z / 17.5))           # 0 front .. 1 rear
            u = abs(X) / 7.0                            # 0 midline .. 1 flank
            up = max(0.0, min(1.0, (-Y + 1) / 7.0))     # 1 on the crown, 0 at mid height
            # unroll the flanks onto the dorsal map: lower = further out
            uu = u + (1 - up) * 0.6 if f != "top" else u
            width = 0.2 + 0.42 * math.sin(math.pi * (0.08 + 0.84 * s)) ** 0.7
            if f in ("front",) and Y < -2:
                uu = u
            if uu < width and f != "front":
                chev = (s * 6.5 - uu * 2.0) % 1.0
                if chev < 0.2:
                    mul(ctx, f, x, y, 0.58)
                else:
                    mix(ctx, f, x, y, frost, 0.72 - 0.3 * (uu / width))
            elif uu < width + 0.1 and f != "front":
                mul(ctx, f, x, y, 0.55)
            else:
                band = (Z * 0.55 + Y * 0.25) % 3.0
                if band < 1.0:
                    mul(ctx, f, x, y, v["band"])
                elif band > 2.4 and hash2(int(X * 3), int(Y * 3 + Z * 7), 5) > 0.55:
                    mix(ctx, f, x, y, frost, 0.45)


# ================================================================================================ chaurus

CHAURUS_SHELL = pal("#0e0f08", "#22230f", "#3c3a19", "#5e5424", "#8c7332")
CHAURUS_PLATE = pal("#110f07", "#2c2610", "#4c3f17", "#7a5e1f", "#c08a32")
AMBER = (232, 164, 52)


def chaurus_mats(extra=None):
    mats = {
        "carapace": Mat(CHAURUS_SHELL, "chitin", base=0.5, contrast=1.0, scale=1.0),
        "plate": Mat(CHAURUS_PLATE, "chitin", base=0.5, contrast=1.1, scale=1.4),
        "belly": Mat(pal("#2e2c1c", "#4e4a30", "#746c48", "#9c9266"), "chitin", base=0.55, scale=0.8),
        "leg": Mat(pal("#0f0e08", "#262210", "#43391a", "#6c5826"), "chitin", base=0.5, scale=0.6),
        "mandible": Mat(pal("#0a0805", "#24180b", "#4c3214", "#7e5420", "#b07a34"), "horn", base=0.55),
        "spike": Mat(pal("#0c0a06", "#2a1e0e", "#54401c", "#8a6a30"), "horn", base=0.5),
        "claw": Mat(pal("#0c0a06", "#2a1e0e", "#54401c", "#8a6a30"), "horn", base=0.45),
    }
    if extra:
        mats.update(extra)
    return mats


def plate_rims(ctx):
    """Amber gloss along a plate's rear rim and a dark seam ahead of it: overlapping, lacquered segments."""
    fw, fh = ctx.size("top")
    for x in range(fw):
        mix(ctx, "top", x, 0, AMBER, 0.55)
        mix(ctx, "top", x, 1, AMBER, 0.25)
        mul(ctx, "top", x, fh - 1, 0.6)
    for f in ("right", "left", "back"):
        sw, sh = ctx.size(f)
        for x in range(sw):
            mix(ctx, f, x, 0, AMBER, 0.35)
            mul(ctx, f, x, sh - 1, 0.55)
    # a glossy highlight streak along the crown of the plate
    for x, y, fw, fh in each(ctx, "top"):
        if abs(x - fw * 0.38) < 0.8 and 1 < y < fh - 1:
            mix(ctx, "top", x, y, (250, 214, 140), 0.35)


@painter("chaurus")
def chaurus():
    lay = load("chaurus")

    def decor(ctx):
        tag = ctx.tag
        if ctx.mat == "plate":
            plate_rims(ctx)
        if tag in ("segment", "abdomen", "thorax"):
            for f in ("right", "left"):
                for x, y, fw, fh in each(ctx, f):
                    if y == fh - 1 or (y > fh * 0.6 and (x % 3 == 0)):
                        mul(ctx, f, x, y, 0.7)
        if tag == "head":
            # small glossy black eyes on the sides, under the shield
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                ex = sw - 2 if f == "right" else 1
                glossy_eye(ctx, f, ex, 1, (8, 8, 6), (200, 160, 80))
                ctx.put(f, ex, 2, (8, 8, 6))
            fw, fh = ctx.size("front")
            for x in range(1, fw - 1):
                mul(ctx, "front", x, fh - 1, 0.5)
        if tag == "leg":
            bands(ctx, 4, 1, 1.35, offset=2)
            for f in LONG_FACES:
                for x, y, fw, fh in each(ctx, f):
                    if x < 2:
                        mul(ctx, f, x, y, 0.6)
        if tag in ("mandible", "mandible_tip"):
            for f in LONG_FACES + ("right", "left"):
                for x, y, fw, fh in each(ctx, f):
                    if f in ("right", "left") and x == 0:
                        mix(ctx, f, x, y, (240, 200, 120), 0.3)

    img, glow = paint(lay, chaurus_mats(), decor, seed=52)
    out("chaurus", img)


# ================================================================================================ chaurus hunter


@painter("chaurus_hunter")
def chaurus_hunter():
    lay = load("chaurus_hunter")
    extra = {
        "eye": Mat(pal("#0c0603", "#2a1206", "#5a260a", "#9a4a14", "#e08a30"), "crystal", base=0.35, contrast=0.5),
        "blade": Mat(pal("#2a2414", "#5a4c28", "#8c7a44", "#bca868", "#e8d8a0"), "horn", base=0.55),
        "wing": Mat(pal("#3a3424", "#6a6044", "#9c9068", "#cfc59a", "#efe8c8"), "membrane", base=0.6, alpha=150),
    }

    def decor(ctx):
        tag = ctx.tag
        if ctx.mat == "plate":
            plate_rims(ctx)
        if tag in ("segment", "thorax"):
            for f in SIDE_FACES:
                for x, y, fw, fh in each(ctx, f):
                    if y % 3 == 2:
                        mul(ctx, f, x, y, 0.7)
        if tag == "eye":
            # faceted compound eye with a glint
            for f in ctx.all_faces():
                for x, y, fw, fh in each(ctx, f):
                    if (x + y) % 2 == 0:
                        mul(ctx, f, x, y, 0.8)
                ctx.put(f, 0, 0, (255, 230, 170))
        if tag == "head":
            fw, fh = ctx.size("front")
            for x in range(1, fw - 1):
                mul(ctx, "front", x, fh - 1, 0.5)
            mul(ctx, "front", fw // 2, fh - 2, 0.5)
        if tag in ("thigh", "shin", "arm", "antenna"):
            for f in SIDE_FACES:
                for x, y, fw, fh in each(ctx, f):
                    if y % 4 == 3:
                        mix(ctx, f, x, y, AMBER, 0.3)
                    if y == fh - 1:
                        mul(ctx, f, x, y, 0.6)
        if tag == "blade":
            # sharp pale cutting edge on the front
            for x, y, fw, fh in each(ctx, "front"):
                mix(ctx, "front", x, y, (230, 220, 170), 0.45)
        if tag in ("wing", "hwing"):
            wing_veins(ctx)

    img, glow = paint(lay, chaurus_mats(extra), decor, seed=63)
    out("chaurus_hunter", img)


def wing_veins(ctx, cell=(214, 200, 150), vein=(58, 44, 22), stigma=(120, 70, 20), cell_alpha=120):
    """Insect wing plane (col 0 = tip, last col = root, last row = leading edge): a thick leading-edge vein,
    longitudinal veins fanning out from the root, a few cross veins, a dark stigma near the tip and clear,
    faintly iridescent cells (translucent)."""
    for f in ("top", "bottom"):
        fw, fh = ctx.size(f)
        for x, y, _, _ in each(ctx, f):
            t = (fw - 1 - x) / max(1, fw - 1)  # 0 root .. 1 tip
            is_vein = y == fh - 1
            for k in (0.34, 0.67, 1.0):
                yl = (fh - 1) * (1 - k * (0.25 + 0.75 * t))
                if abs(y - yl) < 0.5 and not (k == 1.0 and t > 0.85):
                    is_vein = True
            if x in (int(fw * 0.35), int(fw * 0.62)) and y > 0:
                is_vein = True
            if x <= 0 and y > 0:
                is_vein = True
            if is_vein:
                ctx.put(f, x, y, vein + (235,))
            elif y == fh - 2 and abs(t - 0.72) < 0.08:
                ctx.put(f, x, y, stigma + (230,))
            else:
                n = vnoise(x, y, 3, 4)
                c = lerp(cell, (cell[0] * 0.85, cell[1] * 1.0, cell[2] * 1.12), n)
                if t > 0.8:
                    c = lerp(c, (cell[0] * 0.7, cell[1] * 0.7, cell[2] * 0.7), 0.5)
                a = cell_alpha - (30 if y == 0 else 0)
                ctx.put(f, x, y, (c[0], c[1], c[2], a))
    # the zero-height side strips
    for f in ("front", "back", "right", "left"):
        for x, y, fw, fh in each(ctx, f):
            ctx.put(f, x, y, vein + (235,))


# ================================================================================================ shared bits

BONE = Mat(pal("#7a7060", "#a89e88", "#cfc6ae", "#ece6d2"), "bone", base=0.6)
IVORY = Mat(pal("#9a8e74", "#c4b896", "#e6dcc0", "#fbf6e6"), "horn", base=0.6)
BLACK_CLAW = Mat(pal("#08070a", "#1a181c", "#34303a", "#565060"), "horn", base=0.45)
FIRE = Mat(pal("#5a1404", "#a8340a", "#e8681a", "#ffb040", "#fff0b0"), "ember", base=0.6, glow=True)


def cracks(ctx, color, count, length, faces=None, glow=True, width_k=0.0, dim=0.55):
    """Jagged glowing crack lines (random walks) over a box's faces: ember seams, sap seams..."""
    rnd = ctx.rnd
    for f in faces or ctx.all_faces():
        fw, fh = ctx.size(f)
        if fw < 2 or fh < 2:
            continue
        n = max(0, int(round(count * fw * fh / 64.0 + rnd.random() - 0.5)))
        for _ in range(n):
            x, y = rnd.randrange(fw), rnd.randrange(fh)
            dx = rnd.choice((-1, 1))
            for step in range(rnd.randint(max(2, length // 2), length)):
                # hot core, dimmer halo beside it
                ctx.put(f, x, y, color, glow)
                if width_k > 0 and rnd.random() < width_k:
                    c = ctx.get(f, x + 1, y)
                    if c is not None:
                        ctx.put(f, x + 1, y, lerp(c[:3], color, dim), glow)
                if rnd.random() < 0.55:
                    y += 1 if rnd.random() < 0.7 else -1
                else:
                    x += dx
                if rnd.random() < 0.15:
                    dx = -dx
                if not (0 <= x < fw and 0 <= y < fh):
                    break


def eye_px(ctx, face, x, y, color, glow=False, pupil=None):
    ctx.put(face, x, y, color, glow)
    if pupil:
        ctx.put(face, x, y, pupil)


# ================================================================================================ riekling


@painter("riekling")
def riekling():
    lay = load("riekling")
    skin = pal("#26303c", "#41526a", "#647a92", "#8aa0b6", "#b4c4d4")
    mats = {
        "skin": Mat(skin, "skin", base=0.55, counter=0.12, contrast=0.8),
        "hide": Mat(pal("#2a1c12", "#463020", "#664a32", "#86664a", "#a4845e"), "leather", base=0.5),
        "leather": Mat(pal("#1e140c", "#362416", "#523822", "#6e4e30"), "leather", base=0.5),
        "fur": Mat(pal("#3e3226", "#62503e", "#8a765e", "#b2a088", "#d8ccb6"), "hair", base=0.6, contrast=0.6, scale=0.7),
        "bone": BONE,
        "tusk": IVORY,
        "wood": Mat(pal("#2e2014", "#4a3522", "#694c30", "#866440"), "bark", base=0.55, scale=0.6),
    }

    def decor(ctx):
        tag = ctx.tag
        if tag == "head":
            fw, fh = ctx.size("front")
            # beady yellow eyes in dark sockets, wrinkles under them
            for ex in (1, fw - 3):
                ctx.rect("front", ex, 2, 2, 1, (24, 26, 34))
                ctx.put("front", ex + (1 if ex < fw // 2 else 0), 3, (236, 196, 60))
                ctx.put("front", ex + (0 if ex < fw // 2 else 1), 3, (40, 30, 20))
            for x in range(fw):
                mul(ctx, "front", x, 4, 0.85)
        if tag == "jaw":
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (30, 18, 22))
            for x in range(1, fw - 1, 2):
                ctx.put("front", x, 0, (200, 190, 160))
            ctx.hline("top", ctx.size("top")[1] - 1, (40, 20, 26))
        if tag == "nose":
            ctx.put("front", 0, 1, (20, 22, 30))
            ctx.put("front", 2, 1, (20, 22, 30))
        if tag in ("ear", "ear_tip"):
            # pinkish inner ear on the front face, darker rim
            fw, fh = ctx.size("front")
            for x, y, _, _ in each(ctx, "front"):
                if 0 < y < fh - 1 or fh <= 2:
                    mix(ctx, "front", x, y, (170, 120, 140), 0.35)
            for x, y, _, _ in each(ctx, "back"):
                mul(ctx, "back", x, y, 0.85)
        if tag in ("flap", "cap"):
            fw, fh = ctx.size("front")
            for x in range(fw):
                if hash2(x, 3, 7) > 0.5:
                    alpha_set(ctx, "front", x, fh - 1, 0)
                    alpha_set(ctx, "back", x, fh - 1, 0)
            # stitched seam
            for x in range(0, fw, 2):
                ctx.put("front", x, 1, (200, 180, 140))
        if tag == "necklace":
            for x, y, fw, fh in each(ctx, "front"):
                if x % 2 == 0:
                    mul(ctx, "front", x, y, 0.6)
        if tag in ("hand", "foot"):
            fw, fh = ctx.size("front")
            for x in range(0, fw, 2):
                ctx.put("front", x, fh - 1, (220, 210, 190))
        if tag == "torso":
            fw, fh = ctx.size("front")
            # war paint: two red-ochre streaks across the chest
            for y in (fh - 3,):
                ctx.hline("front", y, (150, 60, 40), 1, fw - 1)
        if tag == "binding":
            bands(ctx, 2, 1, 0.6, faces=("top", "bottom", "right", "left"))

    img, glow = paint(lay, mats, decor, seed=71)
    out("riekling", img)


# ================================================================================================ ash spawn

EMBER = (255, 140, 40)
EMBER_HOT = (255, 214, 120)


@painter("ash_spawn")
def ash_spawn():
    lay = load("ash_spawn")
    mats = {
        "ash": Mat(pal("#201d1b", "#38342f", "#56514b", "#7a746c", "#a29a90"), "stone", base=0.55, contrast=0.6, scale=1.1),
        "cinder": Mat(pal("#0b0908", "#1a1614", "#2e2724", "#463b34", "#5e4e42"), "stone", base=0.5, scale=0.6),
        "claw": Mat(pal("#060505", "#141110", "#28221e", "#40352e"), "horn", base=0.45),
        "ember": FIRE,
    }

    def decor(ctx):
        tag = ctx.tag
        if ctx.mat in ("ash",):
            cracks(ctx, EMBER, 1.1, 5, width_k=0.1)
        if ctx.mat == "cinder":
            cracks(ctx, EMBER, 1.0, 4)
            for f in ctx.all_faces():
                for x, y, fw, fh in each(ctx, f):
                    if hash2(x + ctx.seed, y, 13) > 0.94:
                        ctx.put(f, x, y, EMBER_HOT, True)
        if tag == "head":
            fw, fh = ctx.size("front")
            # sunken sockets with burning eyes, a glowing mouth slit
            for ex in (1, fw - 3):
                ctx.rect("front", ex, 2, 2, 2, (10, 8, 8))
                ctx.put("front", ex + (1 if ex < fw // 2 else 0), 3, EMBER_HOT, True)
                ctx.put("front", ex + (0 if ex < fw // 2 else 1), 3, EMBER, True)
            mul(ctx, "front", fw // 2, 4, 0.5)
            mul(ctx, "front", fw // 2 - 1, 4, 0.6)
        if tag == "jaw":
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (255, 110, 30), glow=True)
            ctx.hline("top", ctx.size("top")[1] - 1, (200, 70, 20), glow=True)
        if tag in ("torso", "ribs"):
            fw, fh = ctx.size("front")
            # a smouldering heart showing through the ribs
            cx = fw // 2
            for x in range(fw):
                for y in range(fh):
                    d = abs(x - cx + 0.5) + abs(y - fh * 0.35) * 0.8
                    if tag == "torso" and d < 1.6:
                        ctx.put("front", x, y, EMBER_HOT if d < 0.8 else EMBER, True)
            if tag == "ribs":
                for y in range(0, fh, 2):
                    for x in range(1, fw - 1):
                        mul(ctx, "front", x, y + 1, 0.55)
                        if abs(x - (fw - 1) / 2.0) < 1.2 and y + 1 < fh - 1:
                            ctx.put("front", x, y + 1, lerp(EMBER, (60, 20, 6), 0.35), True)

    img, glow = paint(lay, mats, decor, seed=81)
    out("ash_spawn", img, glow)


# ================================================================================================ gargoyle


@painter("gargoyle")
def gargoyle():
    lay = load("gargoyle")
    stone = pal("#232325", "#3e3e41", "#5c5b5e", "#7e7c7e", "#a4a1a0")
    mats = {
        "stone": Mat(stone, "stone", base=0.55, contrast=0.5, scale=2.2, counter=-0.1),
        "membrane": Mat(shift(stone, mul=0.85), "stone", base=0.5, contrast=0.45, scale=2.5),
        "horn": Mat(pal("#141416", "#28282b", "#424145", "#605e62"), "horn", base=0.5),
        "claw": Mat(pal("#101012", "#222225", "#3a393d", "#58565a"), "horn", base=0.45),
        "tooth": Mat(pal("#6a6862", "#8e8a82", "#b2ada2"), "stone", base=0.6),
    }

    def decor(ctx):
        tag = ctx.tag
        if ctx.mat in ("stone", "membrane"):
            # weathering: rain streaks running down the sides and pale lichen crusts on top
            for f in SIDE_FACES:
                for x, y, fw, fh in each(ctx, f):
                    if hash2(x + ctx.seed, 0, 21) > 0.82 and y > fh * 0.2:
                        mul(ctx, f, x, y, 0.82)
            for f in ("top",) + SIDE_FACES:
                for x, y, fw, fh in each(ctx, f):
                    v = vnoise(x + ctx.seed * 3, y, 31, 2.5)
                    if v > 0.8 and (f == "top" or y < 2):
                        mix(ctx, f, x, y, (150, 156, 128), 0.45)
        if tag == "head":
            fw, fh = ctx.size("front")
            for ex in (1, fw - 3):
                ctx.rect("front", ex, 3, 2, 1, (14, 10, 10))
                ctx.put("front", ex + (1 if ex < fw // 2 else 0), 3, (200, 30, 24), True)
                ctx.put("front", ex + (0 if ex < fw // 2 else 1), 3, (120, 14, 12), True)
        if tag == "muzzle":
            fw, fh = ctx.size("front")
            ctx.put("front", 0, 0, (22, 20, 20))
            ctx.put("front", fw - 1, 0, (22, 20, 20))
            for x in range(fw):
                mul(ctx, "front", x, fh - 1, 0.75)
        if tag == "jaw":
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (30, 26, 26))
            for x in range(0, fw, 2):
                ctx.put("front", x, 0, (150, 146, 136))
        if tag in ("membrane", "membrane_tip"):
            # ribs fanning through the stone membrane, a ragged, chipped trailing edge
            for f in ("top", "bottom"):
                fw, fh = ctx.size(f)
                for x, y, _, _ in each(ctx, f):
                    if x % 4 == 0:
                        mul(ctx, f, x, y, 0.7)
                    # row 0 = trailing edge (+z): scallops between the ribs
                    depth = 1 + int(2.5 * abs(math.sin((x % 4) / 4.0 * math.pi)))
                    if y < depth and x % 4 != 0:
                        alpha_set(ctx, f, x, y, 0)
        if tag in ("chest", "torso"):
            fw, fh = ctx.size("front")
            for y in range(1, fh, 3):
                for x in range(1, fw - 1):
                    if (x + y) % 5 == 0:
                        mul(ctx, "front", x, y, 0.75)

    img, glow = paint(lay, mats, decor, seed=91)
    out("gargoyle", img, glow)


# ================================================================================================ spriggan

SAP = (255, 176, 48)
SAP_HOT = (255, 236, 150)


@painter("spriggan")
def spriggan():
    lay = load("spriggan")
    mats = {
        "bark": Mat(pal("#2c2014", "#4a3622", "#6a5032", "#8c6e46", "#ad9060"), "bark", base=0.58, contrast=0.6, scale=1.0),
        "face": Mat(pal("#3a2a1a", "#5a4229", "#7c5e3a", "#9e7e52", "#bea070"), "skin", base=0.55, contrast=0.5),
        "wood": Mat(pal("#1c140c", "#33251a", "#4e3a28", "#6c5238"), "bark", base=0.55, scale=0.5),
        "leaves": Mat(pal("#16240c", "#253c12", "#3a5a1a", "#557a26", "#7aa038"), "scales", base=0.55, scale=0.8),
        "sap": Mat(pal("#7a4006", "#c47612", "#f0a828", "#ffd870", "#fff4c0"), "ember", base=0.65, glow=True),
    }

    def decor(ctx):
        tag = ctx.tag
        if ctx.mat == "bark":
            # moss creeping over the tops and down the sides
            for f in ("top",) + SIDE_FACES:
                for x, y, fw, fh in each(ctx, f):
                    v = vnoise(x + ctx.seed, y * 2, 17, 3)
                    if v > (0.62 if f == "top" else 0.8) and (f == "top" or y < fh * 0.4):
                        mix(ctx, f, x, y, (86, 120, 44), 0.6)
        if ctx.mat == "leaves":
            # ragged leafy silhouette: bite holes out of the clusters, lighter leaf tips
            for f in ctx.all_faces():
                for x, y, fw, fh in each(ctx, f):
                    edge = x == 0 or y == 0 or x == fw - 1 or y == fh - 1
                    h = hash2(x + ctx.seed, y, 5)
                    if edge and h > 0.6:
                        alpha_set(ctx, f, x, y, 0)
                    elif h > 0.85:
                        mix(ctx, f, x, y, (150, 190, 80), 0.5)
        if tag == "head":
            fw, fh = ctx.size("front")
            # amber eyes, glowing; bark-grain cheeks
            ctx.put("front", 1, 2, SAP, True)
            ctx.put("front", fw - 2, 2, SAP, True)
            for x in (1, fw - 2):
                ctx.put("front", x, 1, (30, 20, 12))
                mul(ctx, "front", x, 3, 0.7)
            ctx.put("front", fw // 2, 4, (40, 26, 16))
            mul(ctx, "front", fw // 2, 3, 0.85)
        if tag in ("chest", "torso"):
            fw, fh = ctx.size("front")
            # sap seams branching out from the core
            cx = fw // 2
            for y in range(fh):
                for x in range(fw):
                    dx = x - (fw - 1) / 2.0
                    if tag == "chest" and y >= 1 and abs(dx) < 1.2 and y <= 4:
                        ctx.put("front", x, y, SAP, True)
                    if tag == "chest" and abs(abs(dx) - (y - 0.5) * 0.9) < 0.5 and y < 3:
                        ctx.put("front", x, y, SAP, True)
            if tag == "torso":
                for y in range(fh):
                    if fh * 0.25 < y < fh * 0.6:
                        ctx.put("front", cx, y, lerp(SAP, (90, 50, 20), 0.4), True)
        if tag == "core":
            for f in ctx.all_faces():
                ctx.put(f, 0, 0, SAP_HOT, True)
        if tag == "navel":
            ctx.put("front", 1, 1, SAP, True)

    img, glow = paint(lay, mats, decor, seed=101)
    out("spriggan", img, glow)


# ================================================================================================ hagraven


@painter("hagraven")
def hagraven():
    lay = load("hagraven")
    feathers = pal("#07070a", "#121218", "#20202a", "#33333f", "#4c4e60")
    mats = {
        "skin": Mat(pal("#352a30", "#56464c", "#77666a", "#998688", "#b8a6a4"), "skin", base=0.55, contrast=1.1),
        "feathers": Mat(feathers, "feather", base=0.55, contrast=1.1, scale=1.0),
        "plume": Mat(shift(feathers, db=12, mul=1.1), "feather", base=0.6, scale=0.8),
        "hair": Mat(pal("#141416", "#262428", "#3e3a3e", "#5a5458", "#787074"), "hair", base=0.5, contrast=0.7),
        "beak": Mat(pal("#16120e", "#2e2620", "#4c4236", "#6e6050", "#8e806a"), "horn", base=0.55),
        "claw": BLACK_CLAW,
        "talon": BLACK_CLAW,
        "scaly": Mat(pal("#1e1a18", "#363028", "#524840", "#72665a", "#90827a"), "scales", base=0.55, scale=0.7),
        "rags": Mat(pal("#1e1a16", "#342e26", "#4e463a", "#6a604e"), "cloth", base=0.5),
        "fire": FIRE,
    }

    def decor(ctx):
        tag = ctx.tag
        if tag == "head":
            fw, fh = ctx.size("front")
            # deep sockets, small pale-yellow eyes, sunken cheeks and a thin grim mouth
            for ex in (1, fw - 3):
                ctx.rect("front", ex, 2, 2, 2, (28, 20, 24))
                ctx.put("front", ex + (1 if ex < fw // 2 else 0), 3, (226, 210, 120))
            for x in (0, fw - 1):
                for y in range(3, fh - 1):
                    mul(ctx, "front", x, y, 0.75)
            ctx.hline("front", fh - 2, (40, 22, 26), 2, fw - 2)
            for x in range(fw):
                if hash2(x, 1, 3) > 0.5:
                    mul(ctx, "front", x, fh - 1, 0.85)
        if tag in ("torso",):
            fw, fh = ctx.size("front")
            # ribs showing on the emaciated chest
            for y in range(2, fh - 3, 2):
                ctx.hline("front", y, (70, 56, 60), 1, fw - 1)
        if tag in ("cape", "cape_low", "mantle"):
            # ragged feather tips along the cape's bottom edge (cutout), a blue-green sheen
            fw, fh = ctx.size("back")
            for f in ("back", "front", "right", "left"):
                sw, sh = ctx.size(f)
                for x in range(sw):
                    if tag != "mantle" and hash2(x, 5, 11 + len(f)) > 0.45:
                        alpha_set(ctx, f, x, sh - 1, 0)
                        if hash2(x, 6, 11) > 0.6:
                            alpha_set(ctx, f, x, sh - 2, 0)
            for x, y, w, h in each(ctx, "back"):
                if vnoise(x, y, 41, 3) > 0.72:
                    mix(ctx, "back", x, y, (70, 96, 110), 0.35)
        if tag == "skirt":
            for f in ("front", "back"):
                sw, sh = ctx.size(f)
                for x in range(sw):
                    k = int(hash2(x, 2, 19) * 3)
                    for y in range(sh - k, sh):
                        alpha_set(ctx, f, x, y, 0)
        if tag in ("hair", "hair_top"):
            for f in ("back", "right", "left"):
                sw, sh = ctx.size(f)
                for x in range(sw):
                    k = int(hash2(x, 4, 23) * 3)
                    for y in range(sh - k, sh):
                        alpha_set(ctx, f, x, y, 0)
        if tag == "beak":
            ctx.put("front", 0, 1, (12, 10, 8))
            ctx.put("front", 1, 1, (12, 10, 8))

    img, glow = paint(lay, mats, decor, seed=111)
    out("hagraven", img, glow)


# ================================================================================================ ice wraith


@painter("ice_wraith")
def ice_wraith():
    lay = load("ice_wraith")
    mats = {
        "ice": Mat(pal("#1e4a6a", "#3a76a0", "#68a8cc", "#a0d4ec", "#dcf4ff"), "crystal", base=0.55, contrast=0.7, alpha=196,
                   belly=pal("#5a92b8", "#8cc0dc", "#c0e4f4", "#f0fbff")),
        "ice_belly": Mat(pal("#5a92b8", "#8cc0dc", "#c0e4f4", "#f0fbff"), "crystal", base=0.6, contrast=0.5, alpha=205),
        "ice_bone": Mat(pal("#4c7290", "#80a8c2", "#b4d4e6", "#e2f2fa", "#ffffff"), "bone", base=0.62, contrast=1.0, alpha=226),
        "ice_fin": Mat(pal("#3a78a8", "#6aa8d4", "#a4d4f0", "#dcf4ff"), "crystal", base=0.6, alpha=168),
        "core": Mat(pal("#3a9cff", "#7cc8ff", "#bfeaff", "#ffffff"), "ethereal", base=0.65, alpha=255, glow=True),
    }

    def decor(ctx):
        tag = ctx.tag
        if tag in ("segment",):
            # frost seams where the segments meet, rime streaks along the back
            for f in ("right", "left", "top", "bottom"):
                fw, fh = ctx.size(f)
                for x, y, _, _ in each(ctx, f):
                    edge = (x == 0 or x == fw - 1) if f in ("right", "left") else (y == 0 or y == fh - 1)
                    if edge:
                        c = ctx.get(f, x, y)
                        ctx.put(f, x, y, (c[0] * 0.6, c[1] * 0.75, c[2] * 0.9, 220))
            for x, y, fw, fh in each(ctx, "top"):
                if abs(x - (fw - 1) / 2.0) < 0.6:
                    mix(ctx, "top", x, y, (240, 252, 255), 0.6)
        if tag == "head":
            fw, fh = ctx.size("front")
            # skull face: dark sockets with burning white-blue eyes, nasal pits
            for ex in (0, fw - 2):
                ctx.rect("front", ex, 0, 2, 2, (14, 34, 60, 255))
                ctx.put("front", ex + (1 if ex == 0 else 0), 1, (200, 245, 255), True)
                ctx.put("front", ex + (0 if ex == 0 else 1), 0, (110, 200, 255), True)
            ctx.put("front", fw // 2, 2, (20, 44, 70, 255))
            for f in ("right", "left"):
                sw, sh = ctx.size(f)
                ex = sw - 2 if f == "right" else 1
                ctx.put(f, ex, 0, (160, 230, 255), True)
        if tag == "jaw":
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (16, 40, 66, 255))
        if tag == "fang":
            for f in ctx.all_faces():
                ctx.fill(f, (236, 248, 255, 240))
        if tag in ("fin", "tail_fin", "spine", "cheek", "crest"):
            for f in ctx.all_faces():
                for x, y, fw, fh in each(ctx, f):
                    if (x + y) % 3 == 0:
                        mix(ctx, f, x, y, (230, 250, 255), 0.35)

    img, glow = paint(lay, mats, decor, seed=121)
    out("ice_wraith", img, glow)


# ================================================================================================ wispmother


@painter("wispmother")
def wispmother():
    lay = load("wispmother")
    mats = {
        "skin": Mat(pal("#5e7290", "#8aa0bc", "#b6c8de", "#dce6f2", "#f6faff"), "skin", base=0.6, contrast=0.4, alpha=215),
        "gown": Mat(pal("#3e4e6c", "#62789a", "#8ea4c4", "#bccee4", "#e4eef8"), "ethereal", base=0.55, contrast=0.8, alpha=200),
        "hair": Mat(pal("#7a8aa6", "#a2b2cc", "#c8d6ea", "#eaf2fb"), "hair", base=0.6, contrast=0.6, alpha=205),
        "wisp": Mat(pal("#6c90c4", "#9cbce6", "#cce2f8", "#f6fbff"), "ethereal", base=0.6, alpha=160, glow=True,
                    glow_strength=0.45),
        "claw": Mat(pal("#8aa0bc", "#b6c8de", "#e4eef8"), "horn", base=0.6, alpha=225),
    }

    def decor(ctx):
        tag = ctx.tag
        if tag == "head":
            fw, fh = ctx.size("front")
            # hollow, glowing eyes, faint cheekbones and a thin mouth
            for ex in (1, fw - 2):
                ctx.put("front", ex, 2, (210, 240, 255, 255), True)
                ctx.tint("front", ex, 1, 0.8)
            ctx.put("front", fw // 2, 4, (70, 84, 110, 225))
        if tag in ("skirt", "skirt_hem", "bodice"):
            # flowing folds down the gown, fading toward the hem
            for f in SIDE_FACES:
                for x, y, fw, fh in each(ctx, f):
                    c = ctx.get(f, x, y)
                    if x % 3 == 0:
                        mul(ctx, f, x, y, 0.85)
                    if tag == "skirt_hem" and y == fh - 1 and hash2(x, y, 7) > 0.5:
                        alpha_set(ctx, f, x, y, 0)
        if tag in ("wisp", "wisp_tip"):
            # mist ribbons: a brighter streak down the middle, fading out toward the frayed end
            for f in ("front", "back"):
                for x, y, fw, fh in each(ctx, f):
                    c = ctx.get(f, x, y)
                    k = y / max(1, fh - 1)
                    a = (175 - 45 * k) if tag == "wisp" else (125 - 85 * k)
                    if abs(x - (fw - 1) / 2.0) < 0.6:
                        c = lerp(c[:3], (240, 248, 255), 0.4) + (c[3],)
                    if tag == "wisp_tip" and y == fh - 1 and x != fw // 2:
                        a = 0
                    ctx.put(f, x, y, (c[0], c[1], c[2], a), glow=True)
        if tag == "sash":
            for x in range(ctx.size("front")[0]):
                mix(ctx, "front", x, 0, (230, 240, 255), 0.4)

    img, glow = paint(lay, mats, decor, seed=131)
    out("wispmother", img, glow)


# ================================================================================================ wisps


@painter("wisp")
def wisp():
    lay = load("wisp")
    mats = {
        "core": Mat(pal("#a8d8ff", "#d4eeff", "#f2fbff", "#ffffff"), "ethereal", base=0.7, contrast=0.4, alpha=255, glow=True),
        "shell": Mat(pal("#3a7ccc", "#5a9ce4", "#8cc6f6", "#c8e6ff"), "ethereal", base=0.6, alpha=110, glow=True,
                     glow_strength=0.55),
        "wisp": Mat(pal("#4a80cc", "#7aaeea", "#b4d6f8", "#eaf6ff"), "ethereal", base=0.6, alpha=140, glow=True,
                    glow_strength=0.55),
        "mote": Mat(pal("#d8f0ff", "#ffffff"), "flat", base=0.8, alpha=255, glow=True),
        "halo": Mat(pal("#4a8ad8", "#76b0ee", "#a8d2fa", "#e0f2ff"), "ethereal", base=0.6, alpha=70, glow=True,
                    glow_strength=0.4),
    }

    def decor(ctx):
        if ctx.tag == "tendril_tip":
            for f in ctx.all_faces():
                for x, y, fw, fh in each(ctx, f):
                    c = ctx.get(f, x, y)
                    ctx.put(f, x, y, (c[0], c[1], c[2], 90), True)

    img, glow = paint(lay, mats, decor, seed=141)
    out("wisp", img, glow)


@painter("will_o_wisp")
def will_o_wisp():
    lay = load("will_o_wisp")
    mats = {
        "shell": Mat(pal("#1e6a5c", "#3c9482", "#6cc2ac", "#a8e6d4", "#dcfff4"), "ethereal", base=0.55, alpha=80, glow=True,
                     glow_strength=0.45),
        "glowshell": Mat(pal("#3c9a8a", "#6cc6b2", "#a8ecda", "#e6fff8"), "ethereal", base=0.6, alpha=95, glow=True,
                         glow_strength=0.6),
        "skull": Mat(pal("#0a1210", "#16221e", "#283a34", "#3e544c"), "bone", base=0.5, contrast=0.8),
        "wisp": Mat(pal("#2c7c6c", "#58aa96", "#94d8c4", "#d4fbee"), "ethereal", base=0.6, alpha=150, glow=True,
                    glow_strength=0.5),
        "mote": Mat(pal("#c8ffee", "#ffffff"), "flat", base=0.8, alpha=255, glow=True),
    }

    def decor(ctx):
        tag = ctx.tag
        if tag == "skull":
            fw, fh = ctx.size("front")
            # pinpoint green eyes in black sockets, a nasal pit
            ctx.put("front", 0, 1, (0, 0, 0))
            ctx.put("front", fw - 1, 1, (0, 0, 0))
            ctx.put("front", 0, 1, (150, 255, 200), True)
            ctx.put("front", fw - 1, 1, (150, 255, 200), True)
            ctx.put("front", fw // 2, 2, (4, 8, 6))
        if tag == "skull_jaw":
            fw, fh = ctx.size("front")
            for x in range(fw):
                ctx.put("front", x, 0, (120, 140, 128) if x % 2 == 0 else (6, 10, 8))
        if tag in ("tendril_tip", "tail_tip"):
            for f in ctx.all_faces():
                for x, y, fw, fh in each(ctx, f):
                    c = ctx.get(f, x, y)
                    ctx.put(f, x, y, (c[0], c[1], c[2], 95 if y >= fh // 2 else 130), True)

    img, glow = paint(lay, mats, decor, seed=151)
    out("will_o_wisp", img, glow)


# ================================================================================================ main


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)


if __name__ == "__main__":
    main(sys.argv[1:])
