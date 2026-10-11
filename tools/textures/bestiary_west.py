"""Textures of the western / foreign / endless-lands rig creatures (WesternDefs.java), painted from the rig layouts.

Run tools/textures/dump_rigs.sh WesternDefs first when a rig changed, then (from tools/textures):
    py -3 bestiary_west.py [name ...]
Writes textures/entity/creatures/<name>.png (+ <name>_glow.png emissive layers where the creature has glowing eyes).

Fins, frills, feathers and the tsaesci's hood are flat planes shaped with transparent (cut-out) pixels.
The realm_* creatures of the procedurally generated endless lands are painted in desaturated light greys: the game
tints them per region with a colour multiplier.
"""
import math
import sys

from rigpaint import Mat, load, paint, pal, save, has_pixels, shift, lerp, hash2

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

DARK = (22, 16, 14)
IVORY = (232, 224, 200)
CLEAR = (0, 0, 0, 0)
CLAW = Mat(pal("#141210", "#2a2622", "#4a443c", "#6e665a"), "horn", base=0.5)
TOOTH = Mat(pal("#8a826e", "#bcb49c", "#e2dcc6", "#f8f4e8"), "bone", base=0.62)
SIDES = ("front", "right", "left", "back")


def grey(*vals):
    """A neutral grey palette from 0..255 values (realm creatures)."""
    return [(v, v, v) for v in vals]


def norm(ctx, face, x, y):
    """Face pixel -> box-local normalised (fx, fy, fz): fx 0 = the box's -x side (right), fy 0 = top, fz 0 = front."""
    fw, fh = ctx.size(face)
    u = (x + 0.5) / fw
    v = (y + 0.5) / fh
    if face == "front":
        return u, v, 0.0
    if face == "back":
        return 1 - u, v, 1.0
    if face == "right":
        return 0.0, v, 1 - u
    if face == "left":
        return 1.0, v, u
    return u, (0.0 if face == "top" else 1.0), 1 - v


def each(ctx, faces=None):
    """Yields (face, x, y, fx, fy, fz) for every pixel of the given faces."""
    for face in faces or ctx.all_faces():
        fw, fh = ctx.size(face)
        if fw <= 0 or fh <= 0:
            continue
        for y in range(fh):
            for x in range(fw):
                fx, fy, fz = norm(ctx, face, x, y)
                yield face, x, y, fx, fy, fz


def cut(ctx, keep, faces=None):
    """Clears (makes transparent) every pixel where keep(fx, fy, fz) is false: shapes flat fins and feathers."""
    for face, x, y, fx, fy, fz in list(each(ctx, faces)):
        if not keep(fx, fy, fz):
            ctx.put(face, x, y, CLEAR)


def shade(ctx, fn, faces=None):
    """Multiplies pixels by fn(face, x, y, fx, fy, fz) (return 1 to leave unchanged)."""
    for face, x, y, fx, fy, fz in list(each(ctx, faces)):
        m = fn(face, x, y, fx, fy, fz)
        if m != 1:
            ctx.tint(face, x, y, m)


def blend(ctx, color, fn, faces=None):
    """Blends a colour in by amount fn(...) in [0, 1]."""
    for face, x, y, fx, fy, fz in list(each(ctx, faces)):
        k = fn(face, x, y, fx, fy, fz)
        if k > 0:
            c = ctx.get(face, x, y)
            if c and c[3] > 0:
                m = lerp(c[:3], color, min(1.0, k))
                ctx.put(face, x, y, (m[0], m[1], m[2], c[3]))


def band_front(ctx, width=2, mul=0.62, faces=("right", "left", "top")):
    """A darker band across the front end of a box (segment banding)."""
    for face in faces:
        fw, fh = ctx.size(face)
        if fw <= 0 or fh <= 0:
            continue
        if face == "right":
            cols = range(max(0, fw - width), fw)
            for x in cols:
                for y in range(fh):
                    ctx.tint(face, x, y, mul)
        elif face == "left":
            for x in range(min(width, fw)):
                for y in range(fh):
                    ctx.tint(face, x, y, mul)
        elif face in ("top", "bottom"):
            for y in range(max(0, fh - width), fh):
                for x in range(fw):
                    ctx.tint(face, x, y, mul)


def stripes(ctx, period=4, width=2, mul=0.55, faces=("right", "left", "top"), fade=0.8, seed=0):
    """Wavy vertical stripes across the flanks and back (tiger / hyena), fading out towards the belly."""
    for face in faces:
        fw, fh = ctx.size(face)
        if fw <= 0 or fh <= 0:
            continue
        for y in range(fh):
            for x in range(fw):
                if face in ("top", "bottom"):
                    along, depth = y, 0.0
                    wob = 1 if (x * 2 // max(1, fw)) == 0 else 0
                else:
                    along, depth = x, y / max(1, fh - 1)
                    wob = int(round(math.sin(y * 0.9 + seed) * 0.8))
                if (along + wob + seed) % period < width and depth < fade:
                    ctx.tint(face, x, y, mul + depth * 0.25)


def slit_eye(ctx, face, x, y, iris, glow=False):
    """A 1x2 reptile eye: iris over a dark slit pixel."""
    ctx.put(face, x, y, iris, glow)
    ctx.put(face, x, y + 1, lerp(iris, (0, 0, 0), 0.75), glow)


def teeth_row(ctx, face, row, color=IVORY, gap=(70, 40, 34), x0=0, x1=None):
    fw, fh = ctx.size(face)
    for x in range(x0, fw if x1 is None else x1):
        ctx.put(face, x, row, color if x % 2 == 0 else gap)


def ragged(ctx, depth=0.3, faces=SIDES, seed=0):
    """Ragged hanging hair / fur ends: clears an uneven fringe from the bottom edge of the side faces."""
    for face, x, y, fx, fy, fz in list(each(ctx, faces)):
        k = hash2(x, 3, ctx.seed + seed)
        if fy > 1 - depth * k:
            ctx.put(face, x, y, CLEAR)


# ================================================================================================ Hammerfell


@painter("sand_serpent")
def sand_serpent():
    lay = load("sand_serpent")
    hide = pal("#4a3420", "#7a5a36", "#a88656", "#cfae7a", "#ead2a2")
    belly = pal("#9a845e", "#c4ae86", "#e2d2aa", "#f4ead0")
    mats = {
        "hide": Mat(hide, "scales", base=0.58, belly=belly, dorsal=0.06, contrast=0.5, counter=0.12, scale=1.3),
        "belly": Mat(belly, "chitin", base=0.6, contrast=0.6),
        "carapace": Mat(pal("#2a1c10", "#4c3520", "#765634", "#a2804c", "#c8a46a"), "chitin", base=0.56, contrast=0.8),
        "horn": Mat(pal("#1e1712", "#3a2e22", "#665440", "#958060", "#bcab88"), "horn", base=0.55),
        "tusk": Mat(pal("#5a4a36", "#9a8664", "#ccba92", "#ece0c0"), "bone", base=0.6),
        "spine": Mat(pal("#22180e", "#40301e", "#6a5032", "#987650"), "horn", base=0.55),
    }

    def decor(ctx):
        if ctx.is_("segment"):
            band_front(ctx, 3, 0.58)
            # dark diamonds along the spine
            fw, fh = ctx.size("top")
            cx = fw / 2
            for y in range(fh):
                k = 2 - abs((y % 5) - 2)
                for x in range(fw):
                    if abs(x + 0.5 - cx) <= k:
                        ctx.tint("top", x, y, 0.72)
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            for face, x in (("right", fw - 3), ("left", 2)):
                ctx.tint(face, x - 1, 0, 0.6)
                ctx.tint(face, x, 0, 0.6)
                ctx.tint(face, x + 1, 0, 0.6)
                slit_eye(ctx, face, x, 1, (240, 160, 40))
                for xx in range(fw):
                    ctx.put(face, xx, fh - 1, (40, 26, 16))
            fw, fh = ctx.size("front")
            teeth_row(ctx, "front", fh - 1)
            ctx.put("front", 2, 1, DARK)
            ctx.put("front", fw - 3, 1, DARK)
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (96, 36, 30) if 0 < x < fw - 1 else (IVORY if y % 2 == 0 else (150, 120, 96)))
            fw, fh = ctx.size("front")
            teeth_row(ctx, "front", 0)
        if ctx.is_("crown", "nose"):
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.25 if abs(fx - 0.5) < 0.08 else 1, ("top", "front"))
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.75 if int(fz * 9) % 3 == 0 else 1, ("top",))
        if ctx.is_("mandible", "mandible_tip"):
            blend(ctx, (60, 40, 24), lambda f, x, y, fx, fy, fz: 0.5 * (1 - fz) if ctx.is_("mandible_tip") else 0)
        if ctx.is_("horn_tip", "spike"):
            blend(ctx, (24, 18, 12), lambda f, x, y, fx, fy, fz: 0.5 * (1 - fy) if ctx.is_("spike") else 0.4)

    img, glow = paint(lay, mats, decor, seed=41)
    out("sand_serpent", img)


@painter("giant_scorpion")
def giant_scorpion():
    lay = load("giant_scorpion")
    yellow = pal("#3a2610", "#6e4c1e", "#a87a32", "#d6aa52", "#f2d482")
    brown = pal("#140c06", "#2a1a0e", "#4a321c", "#6e4c2c", "#946c40")
    mats = {
        "chitin": Mat(yellow, "chitin", base=0.56, belly=shift(yellow, mul=0.85), contrast=0.9),
        "plate": Mat(brown, "chitin", base=0.56, contrast=1.0),
        "leg": Mat(yellow, "chitin", base=0.6, contrast=0.7, scale=0.75),
        "fang": Mat(pal("#0e0a08", "#241a12", "#3e2e20", "#5e4630"), "horn", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("tail", "abdomen", "leg", "arm", "thorax"):
            # dark brown banding across each segment
            band_front(ctx, 2 if ctx.is_("tail", "arm") else 3, 0.5, faces=("right", "left", "top", "bottom"))
        if ctx.is_("leg"):
            # dark joints at both ends of each leg piece
            for f in ("front", "back", "top", "bottom"):
                w, h = ctx.size(f)
                for x in range(w):
                    for y in range(h):
                        if x < 2 or x >= w - 1:
                            ctx.tint(f, x, y, 0.55)
        if ctx.is_("sting"):
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.35)
        if ctx.is_("sting_bulb"):
            blend(ctx, (70, 24, 14), lambda f, x, y, fx, fy, fz: 0.75 * fz)
        if ctx.is_("head"):
            # median eyes on top, lateral eye clusters at the front corners
            fw, fh = ctx.size("top")
            for x in (fw // 2 - 1, fw // 2):
                ctx.put("top", x, fh - 2, (10, 8, 8))
                ctx.put("top", x, fh - 3, (90, 76, 60))
            for x in (0, 1, fw - 2, fw - 1):
                ctx.put("top", x, fh - 1, (10, 8, 8))
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, (30, 16, 10))
        if ctx.is_("palm", "finger", "pincer"):
            # dark brown finger tips, glossy palm
            blend(ctx, (40, 24, 12), lambda f, x, y, fx, fy, fz: 0.8 * max(0.0, 1 - fz * 1.6) if not ctx.is_("palm") else 0)
            if ctx.is_("palm"):
                blend(ctx, (40, 24, 12), lambda f, x, y, fx, fy, fz: 0.6 if fz < 0.2 else 0)
                shade(ctx, lambda f, x, y, fx, fy, fz: 1.25 if abs(fx - 0.35) < 0.12 else 1, ("top", "front"))

    img, glow = paint(lay, mats, decor, seed=43)
    out("giant_scorpion", img)


# ================================================================================================ High Rock


@painter("harpy")
def harpy():
    lay = load("harpy")
    plum = pal("#2a2420", "#4c4038", "#76685a", "#a0907c", "#c8baa4")
    wing = pal("#1c1612", "#36281e", "#5a4430", "#86684a", "#ae906a")
    pale = (214, 200, 176)
    mats = {
        "skin": Mat(pal("#6e5040", "#9a7460", "#c09a7e", "#dcbca0", "#f0d6bc"), "skin", base=0.6, contrast=0.45),
        "hair": Mat(pal("#120c0a", "#241812", "#3a2a1e", "#52402c", "#6a563c"), "hair", base=0.5, contrast=0.8),
        "plumage": Mat(plum, "feather", base=0.56, contrast=0.7),
        "wingarm": Mat(wing, "feather", base=0.52, contrast=0.6),
        "wing": Mat(wing, "feather", base=0.55, contrast=0.55, grad=0.25),
        "feather": Mat(wing, "feather", base=0.5, contrast=0.55),
        "birdleg": Mat(pal("#3e3016", "#6e5a2a", "#9c8442", "#c4ac62", "#e0cc88"), "scales", base=0.56, scale=0.7, contrast=0.6),
        "talon": CLAW,
    }

    def feather_wing(ctx, tip_rows):
        # vanes: dark shafts, pale bars, dark tips and serrated feather ends
        faces = ("right", "left")
        shade(ctx, lambda f, x, y, fx, fy, fz: 0.8 if int(fy * ctx.size(f)[1]) % 2 == 0 and fz < 0.15 else 1, faces)
        shade(ctx, lambda f, x, y, fx, fy, fz: 1.22 if int(fz * 12) % 4 == 1 else 1, faces)
        blend(ctx, (18, 12, 10), lambda f, x, y, fx, fy, fz: max(0.0, (fz - (1 - tip_rows)) / tip_rows) * 0.7, faces)
        cut(ctx, lambda fx, fy, fz: fz <= 1 - 0.28 * ((fy * 5) % 1), faces)

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # dark brows, fierce yellow eyes, nose shadow, hard mouth (rows 0-1 lie under the hair)
            ctx.hline("front", 1, (64, 40, 30))
            for x in (1, fw - 2):
                ctx.put("front", x, 2, (236, 196, 50))
            ctx.put("front", 0, 2, (120, 84, 66))
            ctx.put("front", fw - 1, 2, (120, 84, 66))
            ctx.tint("front", 2, 3, 0.85)
            for x in range(1, fw - 1):
                ctx.put("front", x, 4, (110, 62, 54))
                ctx.put("front", x, 5, (40, 14, 16))
        if ctx.is_("jaw"):
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (176, 116, 100))
            fw, fh = ctx.size("top")
            for x in range(fw):
                ctx.put("top", x, fh - 1, (230, 222, 206))
        if ctx.is_("nose"):
            fw, fh = ctx.size("front")
            ctx.put("front", 0, fh - 1, (130, 86, 70))
        if ctx.is_("covert"):
            feather_wing(ctx, 0.35)
        if ctx.is_("secondary"):
            feather_wing(ctx, 0.4)
        if ctx.is_("primary"):
            feather_wing(ctx, 0.5)
        if ctx.is_("tail_fan"):
            faces = ("top", "bottom")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.8 if x % 2 == 0 else 1, faces)
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.22 if int(fz * 9) % 3 == 1 else 1, faces)
            blend(ctx, (18, 12, 10), lambda f, x, y, fx, fy, fz: max(0.0, fz - 0.65) * 2, faces)
            cut(ctx, lambda fx, fy, fz: fz <= 1 - 0.35 * (2 * abs(fx - 0.5)) ** 2 - 0.12 * ((fx * 7) % 1), faces)
        if ctx.is_("breast", "skirt", "thigh"):
            # scalloped pale-edged contour feathers
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.25 if (y % 3 == 2 and (x + y // 3) % 2 == 0) else 1, SIDES)
        if ctx.is_("breast"):
            blend(ctx, pale, lambda f, x, y, fx, fy, fz: 0.35 if f == "front" else 0)
        if ctx.is_("skirt"):
            ragged(ctx, 0.35)
        if ctx.is_("hair_back", "hair_side"):
            ragged(ctx, 0.3)

    img, glow = paint(lay, mats, decor, seed=47)
    out("harpy", img)


# ================================================================================================ Summerset & Valenwood


@painter("indrik")
def indrik():
    lay = load("indrik")
    coat = pal("#a49a84", "#d0c8b4", "#eae4d6", "#f8f6ee", "#ffffff")
    plume = pal("#a08a50", "#d4c088", "#eee4c4", "#faf8ee", "#ffffff")
    blue = (96, 160, 232)
    gold = (222, 184, 92)
    mats = {
        "coat": Mat(coat, "fur", base=0.66, belly=pal("#d0ccc4", "#e8e6e0", "#f8f8f4", "#ffffff"), counter=0.15, contrast=0.45),
        "belly": Mat(pal("#d4d0c8", "#ecebe6", "#fafaf6", "#ffffff"), "fur", base=0.64, contrast=0.4),
        "leg": Mat(coat, "fur", base=0.66, contrast=0.4),
        "muzzle": Mat(pal("#a49890", "#c8beb6", "#e4dcd6", "#f6f0ec"), "skin", base=0.6, contrast=0.4),
        "hoof": Mat(pal("#5e4818", "#987432", "#c8a250", "#ecd084", "#fff0b8"), "horn", base=0.62),
        "crest": Mat(plume, "feather", base=0.62, contrast=0.5),
        "plume": Mat(plume, "feather", base=0.62, contrast=0.5),
        "antler": Mat(pal("#7a6024", "#b08c40", "#d8b860", "#f4dc94"), "horn", base=0.62),
    }

    def decor(ctx):
        if ctx.is_("head"):
            # large dark gentle eyes with a blue glint
            fw, fh = ctx.size("right")
            for face, x, dx in (("right", fw - 2, -1), ("left", 1, 1)):
                ctx.put(face, x, 1, (30, 34, 50))
                ctx.put(face, x, 2, (30, 34, 50))
                ctx.put(face, x + dx, 1, (100, 150, 220))
                ctx.tint(face, x + dx, 2, 0.8)
            # a pale gold blaze down the brow
            blend(ctx, gold, lambda f, x, y, fx, fy, fz: 0.35 if abs(fx - 0.5) < 0.25 else 0, ("top", "front"))
        if ctx.is_("snout"):
            fw, fh = ctx.size("front")
            ctx.put("front", 0, 1, (150, 128, 124))
            ctx.put("front", fw - 1, 1, (150, 128, 124))
            ctx.hline("front", fh - 1, (176, 156, 150), 1, fw - 1)
        if ctx.is_("crest"):
            faces = ("right", "left")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.88 if x % 2 == 0 else 1, faces)
            blend(ctx, blue, lambda f, x, y, fx, fy, fz: max(0.0, 0.7 - fy) * 1.1, faces)
            cut(ctx, lambda fx, fy, fz: fy >= 0.6 * ((fz * 3) % 1), faces)
        if ctx.is_("vane", "vane_tip"):
            faces = ("right", "left")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.86 if y % 2 == 0 else 1, faces)
            blend(ctx, gold, lambda f, x, y, fx, fy, fz: 0.35 * fy, faces)
            blend(ctx, blue, lambda f, x, y, fx, fy, fz: max(0.0, fz - 0.35) * 1.3 + max(0.0, 0.3 - fy) * 1.5, faces)
            cut(ctx, lambda fx, fy, fz: fz <= 0.3 + 0.7 * fy - 0.18 * ((fy * 4) % 1) + 0.12, faces)
        if ctx.is_("tail_plume"):
            # crossed vanes: shafts, gold at the root, blue tips, serrated ends
            flat = ("top", "bottom")
            upright = ("right", "left")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.88 if x % 2 == 0 else 1, flat)
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.88 if y % 2 == 0 else 1, upright)
            blend(ctx, gold, lambda f, x, y, fx, fy, fz: max(0.0, 0.4 - fz) * 0.8)
            blend(ctx, blue, lambda f, x, y, fx, fy, fz: max(0.0, fz - 0.45) * 1.4)
            cut(ctx, lambda fx, fy, fz: fz <= 1 - 0.3 * abs(fx - 0.5) * 2, flat)
            cut(ctx, lambda fx, fy, fz: fz <= 1 - 0.3 * abs(fy - 0.5) * 2, upright)
        if ctx.is_("ruff"):
            blend(ctx, gold, lambda f, x, y, fx, fy, fz: 0.35 * fy, SIDES)
            ragged(ctx, 0.3)
        if ctx.is_("tail", "ear"):
            blend(ctx, gold, lambda f, x, y, fx, fy, fz: 0.4 * (1 - fy), SIDES)
        if ctx.is_("leg") and "lower" in ctx.bone:
            # soft blue-grey stockings above the gilded hooves
            blend(ctx, (196, 212, 236), lambda f, x, y, fx, fy, fz: max(0.0, fy - 0.45) * 1.2, SIDES)
        if ctx.is_("torso"):
            # pale gold dappling along the back
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    if (x * 5 + y * 3) % 7 == 0:
                        ctx.tint("top", x, y, 0.94, (6, 2, -12))

    img, glow = paint(lay, mats, decor, seed=53)
    out("indrik", img)


@painter("sea_serpent")
def sea_serpent():
    lay = load("sea_serpent")
    teal = pal("#0a2426", "#123e40", "#1c5c5a", "#2a7e78", "#4ea29a")
    belly = pal("#7c968c", "#a8bcae", "#ccdccc", "#e8f0e2")
    finpal = pal("#0a2e30", "#16504e", "#25746c", "#4c9c8e", "#86c6b2")
    mats = {
        "scales": Mat(teal, "scales", base=0.56, belly=belly, dorsal=0.12, counter=0.2, contrast=0.55, scale=1.3),
        "head": Mat(shift(teal, mul=0.92), "scales", base=0.56, belly=belly, contrast=0.5),
        "belly": Mat(belly, "chitin", base=0.6, contrast=0.6),
        "fin": Mat(finpal, "membrane", base=0.56),
        "horn": Mat(pal("#2e2c26", "#5a564a", "#8a846e", "#b8b096"), "horn", base=0.55),
    }
    rim = (150, 214, 196)

    def fin_rays(ctx, faces, along_z=True):
        shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if (int(fz * 40) if along_z else int(fy * 40)) % 3 == 0 else 1, faces)

    def decor(ctx):
        if ctx.is_("segment"):
            # pale lateral line of spots and darker saddle bands
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(1, fw, 3):
                    ctx.tint(f, x, fh // 2, 1.4)
            band_front(ctx, 2, 0.8)
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            for face, x, dx in (("right", fw - 4, 1), ("left", 3, -1)):
                ctx.tint(face, x, 0, 0.6)
                ctx.tint(face, x + dx, 0, 0.6)
                ctx.put(face, x, 1, (226, 206, 60))
                ctx.put(face, x + dx, 1, (250, 240, 130))
                ctx.put(face, x, 2, (20, 30, 20))
                for xx in range(fw - 6 if face == "right" else 0, fw if face == "right" else 6):
                    ctx.put(face, xx, fh - 1, IVORY if xx % 2 == 0 else DARK)
            fw, fh = ctx.size("front")
            teeth_row(ctx, "front", fh - 1, gap=DARK)
            ctx.put("front", 1, 1, (8, 20, 20))
            ctx.put("front", fw - 2, 1, (8, 20, 20))
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (110, 40, 44) if 0 < x < fw - 1 else (IVORY if y % 2 == 0 else (80, 30, 30)))
        if ctx.is_("crest"):
            faces = ("right", "left")
            fin_rays(ctx, faces)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, 0.35 - fy), faces)
            cut(ctx, lambda fx, fy, fz: fy >= 0.85 - 0.85 * fz + 0.25 * ((fz * 4) % 1), faces)
        if ctx.is_("dorsal"):
            faces = ("right", "left")
            fin_rays(ctx, faces)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, 0.3 - fy), faces)
            cut(ctx, lambda fx, fy, fz: fy >= 0.75 * (1 - ((fz * 2) % 1)), faces)
        if ctx.is_("frill"):
            faces = ("right", "left")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if int((math.atan2(fy - 0.5, fz + 0.05) + 2) * 4) % 2 == 0 else 1, faces)
            cut(ctx, lambda fx, fy, fz: math.hypot(fz, (fy - 0.5) * 1.2) <= 0.95 - 0.12 * abs(math.sin(math.atan2(fy - 0.5, fz) * 5)), faces)
        if ctx.is_("fin"):
            faces = ("top", "bottom")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if x % 2 == 0 else 1, faces)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, 0.3 - fx), faces)
            cut(ctx, lambda fx, fy, fz: fz >= (1 - fx) * 0.65 - 0.05, faces)
        if ctx.is_("tail_fin"):
            faces = ("right", "left")
            fin_rays(ctx, faces, along_z=False)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, fz - 0.7), faces)
            cut(ctx, lambda fx, fy, fz: abs(fy - 0.5) * 2 <= 0.25 + 0.75 * fz
                and not (fz > 0.5 and abs(fy - 0.5) * 2 < (fz - 0.5) * 1.2), faces)

    img, glow = paint(lay, mats, decor, seed=59)
    out("sea_serpent", img)


# ================================================================================================ Wrothgar


@painter("echatere")
def echatere():
    lay = load("echatere")
    shag = pal("#17130f", "#2a221b", "#40352a", "#584a3b", "#72624e")
    face = pal("#4e3e2e", "#76604a", "#9a8064", "#b89e80", "#d0b89a")
    mats = {
        "shag": Mat(shag, "hair", base=0.55, contrast=0.65, scale=1.3, belly=shift(shag, mul=0.8)),
        "mane": Mat(shift(shag, mul=1.08), "hair", base=0.56, contrast=0.7, scale=1.5),
        "face": Mat(face, "fur", base=0.55, contrast=0.5),
        "headfur": Mat(pal("#231c16", "#3a3026", "#54473a", "#6e604e", "#8a7a64"), "fur", base=0.55, contrast=0.6),
        "snout": Mat(pal("#3e302c", "#5e4a44", "#826a60", "#a48a7e"), "skin", base=0.56, contrast=0.5),
        "legfur": Mat(shag, "fur", base=0.5, contrast=0.5),
        "belly": Mat(shift(shag, mul=0.85), "hair", base=0.5, contrast=0.5),
        "hoof": Mat(pal("#141210", "#28221c", "#40372e", "#5a4e42"), "horn", base=0.5),
        "tusk": Mat(pal("#7a6c50", "#b0a07c", "#d8caa4", "#f0e6c8"), "bone", base=0.6),
    }
    tip = (122, 104, 82)

    def decor(ctx):
        if ctx.is_("head"):
            # the lighter face: buff on the front, fading back along the cheeks; darker crown
            blend(ctx, (176, 150, 118), lambda f, x, y, fx, fy, fz: (0.75 if f == "front" else 0.6 * (1 - fz) ** 1.5) * min(1.0, 0.4 + fy),
                  ("front", "right", "left"))
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.8 if fy < 0.2 else 1, ("front", "right", "left"))
            # small sunken eyes
            fw, fh = ctx.size("front")
            for x in (1, fw - 2):
                ctx.put("front", x, 2, (14, 10, 8))
                ctx.put("front", x + (1 if x == 1 else -1), 2, (60, 44, 32))
                ctx.tint("front", x, 1, 0.65)
        if ctx.is_("snout"):
            fw, fh = ctx.size("front")
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.12, ("front",))
            for dx in (-2, 1):
                ctx.put("front", fw // 2 + dx, fh // 2, (24, 16, 14))
                ctx.put("front", fw // 2 + dx, fh // 2 - 1, (44, 30, 26))
            ctx.hline("front", fh - 1, (30, 20, 18))
        if ctx.is_("leg") and "lower" in ctx.bone:
            blend(ctx, (20, 16, 12), lambda f, x, y, fx, fy, fz: 0.5 * fy, SIDES)
        if ctx.is_("skirt", "hump", "ridge", "throat", "forelock", "torso", "chest"):
            # sun-bleached tips on the long hair
            blend(ctx, tip, lambda f, x, y, fx, fy, fz: max(0.0, fy - 0.6) * 0.7 if ctx.is_("skirt", "throat") else max(0.0, 0.35 - fy), SIDES)
        if ctx.is_("skirt", "throat"):
            ragged(ctx, 0.4)
        if ctx.is_("hump"):
            ragged(ctx, 0.25)

    img, glow = paint(lay, mats, decor, seed=61)
    out("echatere", img)


@painter("durzog")
def durzog():
    lay = load("durzog")
    hide = pal("#2a2810", "#4a481e", "#6c6a2c", "#92883c", "#b6a852")
    bel = pal("#6a6438", "#948c54", "#bab072", "#d8d094")
    mats = {
        "hide": Mat(hide, "scales", base=0.58, belly=bel, counter=0.22, contrast=0.4, scale=1.2),
        "belly": Mat(bel, "chitin", base=0.6, contrast=0.5),
        "leg": Mat(hide, "scales", base=0.56, scale=0.8, contrast=0.4),
        "beak": Mat(pal("#1a1612", "#322a22", "#52463a", "#776656", "#9a8a76"), "horn", base=0.56),
        "tooth": TOOTH,
        "spine": Mat(pal("#26120c", "#442016", "#6c3624", "#965436"), "horn", base=0.56),
        "claw": CLAW,
    }

    def decor(ctx):
        if ctx.is_("torso", "chest", "neck", "tail"):
            stripes(ctx, period=4, width=2, mul=0.5, seed=ctx.seed % 3)
        if ctx.is_("leg") and "lower" not in ctx.bone:
            stripes(ctx, period=3, width=1, mul=0.55, faces=("right", "left", "front", "back"))
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            for face, x in (("right", fw - 2), ("left", 1)):
                ctx.tint(face, x, 0, 0.55)
                slit_eye(ctx, face, x, 1, (240, 150, 30))
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if (x + y) % 3 == 0 and fy < 0.4 else 1, ("top",))
        if ctx.is_("snout"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.put(face, x, fh - 1, DARK)
        if ctx.is_("teeth"):
            for face in ("right", "left", "front"):
                fw, fh = ctx.size(face)
                for x in range(fw):
                    if x % 2 == 1:
                        ctx.put(face, x, 0, (60, 40, 30))
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(1, fw - 1):
                    ctx.put("top", x, y, (120, 44, 40))
        if ctx.is_("spike"):
            blend(ctx, (214, 180, 126), lambda f, x, y, fx, fy, fz: 0.6 * (1 - fy), SIDES)

    img, glow = paint(lay, mats, decor, seed=67)
    out("durzog", img)


# ================================================================================================ Atmora & Akavir


@painter("frost_giant")
def frost_giant():
    lay = load("frost_giant")
    skin = pal("#4e6480", "#728ca8", "#9ab4cc", "#c0d6e6", "#e2eef8")
    frost = pal("#7c94aa", "#a8bed0", "#d0e0ec", "#eef6fc", "#ffffff")
    mats = {
        "skin": Mat(skin, "skin", base=0.56, contrast=0.55),
        "beard": Mat(frost, "hair", base=0.56, contrast=0.6),
        "hair": Mat(frost, "hair", base=0.56, contrast=0.6, scale=1.2),
        "fur": Mat(pal("#3c3a36", "#5e5a52", "#837e72", "#aaa496", "#ccc6b8"), "hair", base=0.56, contrast=0.6, scale=1.2),
        "bone": Mat(pal("#746c5a", "#a49c84", "#cec6ac", "#ece6d2"), "bone", base=0.6),
        "horn": Mat(pal("#1e1c1a", "#38342e", "#58524a", "#7c7468"), "horn", base=0.55),
        "leather": Mat(pal("#241a12", "#3e2c20", "#5a4232", "#785a44"), "leather", base=0.55),
        "wood": Mat(pal("#26201a", "#3e342a", "#584c40", "#746656"), "bark", base=0.55),
        "ice": Mat(pal("#4e80a8", "#7cb0d2", "#aed8ee", "#d8f0fa", "#ffffff"), "crystal", base=0.6, contrast=0.7),
    }
    eye = (200, 240, 255)

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # deep-set glowing eyes under the brow (row 2 is the brow, nose in the middle)
            for x in range(fw):
                ctx.tint("front", x, 3, 0.8)
            for x in (1, fw - 2):
                ctx.put("front", x, 3, eye, glow=True)
            ctx.put("front", 2, 3, (110, 170, 210), glow=True)
            ctx.put("front", fw - 3, 3, (110, 170, 210), glow=True)
            ctx.hline("front", 4, (60, 74, 96), 1, fw - 1)
        if ctx.is_("beard", "beard_tip", "hair_back"):
            # icicles at the ends
            blend(ctx, (240, 250, 255), lambda f, x, y, fx, fy, fz: max(0.0, fy - 0.55) * 1.3, SIDES)
            ragged(ctx, 0.35)
        if ctx.is_("beard"):
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (64, 74, 96), 1, fw - 1)
        if ctx.is_("ribs"):
            fw, fh = ctx.size("front")
            for y in range(0, fh, 2):
                ctx.hline("front", y, (90, 84, 70), 0, fw)
            for y in range(fh):
                ctx.put("front", fw // 2, y, (220, 214, 196))
        if ctx.is_("skull"):
            fw, fh = ctx.size("front")
            ctx.put("front", 0, 1, DARK)
            ctx.put("front", fw - 1, 1, DARK)
            ctx.put("front", 1, 2, (80, 70, 60))
        if ctx.is_("strap"):
            fw, fh = ctx.size("front")
            for y in range(1, fh, 4):
                ctx.hline("front", y, (190, 180, 150))
        if ctx.is_("mantle", "kilt", "boot"):
            ragged(ctx, 0.3)
        if ctx.is_("torso", "arm", "forearm"):
            # frost rime on the upper surfaces
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.14 if fy < 0.25 and (x * 3 + y * 5) % 4 == 0 else 1,
                  ("top", "front", "right", "left"))
        if ctx.is_("hand", "foot"):
            blend(ctx, (60, 80, 110), lambda f, x, y, fx, fy, fz: 0.35 * fy, SIDES)
        if ctx.is_("crystal", "ice"):
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.15 if (x + y) % 3 == 0 else 1)

    img, glow = paint(lay, mats, decor, seed=71)
    out("frost_giant", img, glow)


@painter("kamal")
def kamal():
    lay = load("kamal")
    skin = pal("#34465e", "#56708e", "#7e9ab6", "#a8c2d8", "#d4e4f0")
    mats = {
        "skin": Mat(skin, "skin", base=0.56, contrast=0.6),
        "fur": Mat(pal("#3a3836", "#5e5a54", "#86807a", "#aea69a", "#d0cabe"), "hair", base=0.56, contrast=0.8, scale=1.1),
        "rag": Mat(pal("#221c18", "#3a3028", "#54463a", "#6e5e4e"), "hair", base=0.55, contrast=0.6, scale=1.3),
        "horn": Mat(pal("#141212", "#2a2624", "#443e3a", "#625a54", "#847a72"), "horn", base=0.55),
        "tooth": TOOTH,
        "claw": CLAW,
    }
    eye = (160, 236, 255)

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # sunken sockets with glowing eyes above the muzzle (row 2), dark maw lines
            for x in range(fw):
                ctx.tint("front", x, 1, 0.75)
            for x in (1, fw - 2):
                ctx.tint("front", x - 1, 2, 0.6)
                ctx.tint("front", x + 1, 2, 0.6)
                ctx.put("front", x, 2, eye, glow=True)
            ctx.hline("front", fh - 1, (30, 20, 30), 1, fw - 1)
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.8 if fy < 0.3 else 1, ("top", "right", "left"))
        if ctx.is_("muzzle"):
            fw, fh = ctx.size("front")
            ctx.put("front", 1, 0, (20, 20, 30))
            ctx.put("front", fw - 2, 0, (20, 20, 30))
            teeth_row(ctx, "front", fh - 1, gap=(40, 20, 36))
        if ctx.is_("brow"):
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.75)
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (70, 30, 50))
        if ctx.is_("hand", "foot", "forearm"):
            blend(ctx, (28, 38, 58), lambda f, x, y, fx, fy, fz: 0.6 * fy, SIDES)
        if ctx.is_("horn", "horn_tip"):
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.25 if y % 2 == 0 else 1, SIDES)
        if ctx.is_("torso"):
            # ribs showing through the gaunt chest
            fw, fh = ctx.size("front")
            for y in range(3, fh - 2, 2):
                for x in list(range(1, fw // 2 - 1)) + list(range(fw // 2 + 1, fw - 1)):
                    ctx.tint("front", x, y, 0.82)
        if ctx.is_("ruff", "spine_fur", "arm_fur", "thigh_fur", "rags"):
            ragged(ctx, 0.4)

    img, glow = paint(lay, mats, decor, seed=73)
    out("kamal", img, glow)


@painter("tsaesci")
def tsaesci():
    lay = load("tsaesci")
    gold = pal("#3e2a0c", "#6e4e18", "#9e7a28", "#caa444", "#ecd078")
    belly = pal("#7a6438", "#a68e58", "#ccb87e", "#ead8a6")
    mats = {
        "scales": Mat(gold, "scales", base=0.58, belly=belly, dorsal=0.08, contrast=0.45, scale=1.2),
        "belly": Mat(belly, "chitin", base=0.6, contrast=0.6),
        "lacquer": Mat(pal("#160808", "#36100e", "#621a16", "#8e2820", "#b83c2c"), "plain", base=0.56, contrast=0.6),
        "cloth": Mat(pal("#101018", "#1c1c30", "#2c2c4c", "#40406a"), "cloth", base=0.55),
        "hood": Mat(gold, "scales", base=0.58, belly=belly, contrast=0.45),
        "metal": Mat(pal("#3a2a10", "#6a5020", "#a08038", "#d6b860"), "metal", base=0.6),
        "blade": Mat(pal("#4e545e", "#7e868e", "#aeb6be", "#dce2e8", "#ffffff"), "plain", base=0.62, contrast=0.3),
    }
    lace = (214, 176, 80)

    def hood_w(fy):
        # cobra hood: narrow neck at the bottom, widest a little above the middle, rounded top
        return 0.4 + 0.6 * math.sin(math.pi * min(1.0, 0.08 + fy * 1.05)) ** 0.7

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            for face, x in (("right", fw - 2), ("left", 1)):
                ctx.tint(face, x, 0, 0.55)
                slit_eye(ctx, face, x, 1, (250, 206, 40))
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.68 if (x + y) % 3 == 0 else 1, ("top",))
        if ctx.is_("snout"):
            fw, fh = ctx.size("front")
            ctx.put("front", 1, 0, DARK)
            ctx.put("front", fw - 2, 0, DARK)
            for f in ("right", "left"):
                w, h = ctx.size(f)
                for x in range(w):
                    ctx.put(f, x, h - 1, DARK)
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (120, 40, 50))
            ctx.put("top", 0, fh - 1, IVORY)
            ctx.put("top", fw - 1, fh - 1, IVORY)
        if ctx.is_("hood"):
            # inside: pale with a dark rim and two dark eye-spots; outside: chevrons
            fw, fh = ctx.size("front")
            for y in range(fh):
                for x in range(fw):
                    fx = (x + 0.5) / fw
                    fy = (y + 0.5) / fh
                    edge = abs(fx - 0.5) * 2 > hood_w(fy) - 0.22 or fy < 0.12
                    ctx.put("front", x, y, (100, 72, 26) if edge else (228, 208, 152))
            for (x, y) in ((2, 3), (fw - 3, 3)):
                ctx.put("front", x, y, (40, 24, 10))
                ctx.put("front", x, y + 1, (40, 24, 10))
            for y in range(2, fh):
                ctx.put("front", fw // 2, y, (196, 166, 104))
                ctx.put("front", fw // 2 - 1, y, (196, 166, 104))
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.62 if abs(abs(fx - 0.5) * 8 - (fy * 8) % 3) < 0.5 else 1, ("back",))
            cut(ctx, lambda fx, fy, fz: abs(fx - 0.5) * 2 <= hood_w(fy))
        if ctx.is_("cuirass", "tasset", "pauldron", "bracer"):
            # lacquered lames: dark seams between rows, gold cross-lacing, a bright top edge on each lame
            for f in SIDES:
                fw, fh = ctx.size(f)
                for y in range(fh):
                    for x in range(fw):
                        if y % 2 == 1:
                            ctx.tint(f, x, y, 0.62)
                            if x % 3 == 1:
                                ctx.put(f, x, y, lace)
                        elif x % 3 == 1:
                            ctx.tint(f, x, y, 1.15)
        if ctx.is_("sash"):
            fw, fh = ctx.size("front")
            ctx.put("front", fw // 2 + 1, 0, (196, 40, 30))
            ctx.put("front", fw // 2 + 1, 1, (150, 30, 24))
        if ctx.is_("tail", "column", "coil"):
            # diamond back pattern and darker scale flecks
            fw, fh = ctx.size("top")
            for y in range(fh):
                k = 2 - abs((y % 5) - 2)
                for x in range(fw):
                    if abs(x + 0.5 - fw / 2) <= k:
                        ctx.tint("top", x, y, 0.68)
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.72 if (x + y * 2) % 7 == 0 else 1, ("right", "left", "back"))
        if ctx.is_("blade", "blade_tip"):
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("bottom", x, y, (250, 252, 255))
            for f in ("right", "left"):
                fw, fh = ctx.size(f)
                for x in range(fw):
                    ctx.put(f, x, fh - 1, (240, 246, 250))
                    ctx.tint(f, x, 0, 0.8)

    img, glow = paint(lay, mats, decor, seed=79)
    out("tsaesci", img)


# ================================================================================================ the endless lands


@painter("realm_stalker")
def realm_stalker():
    lay = load("realm_stalker")
    hide = grey(84, 118, 152, 186, 214)
    mats = {
        "hide": Mat(hide, "skin", base=0.62, belly=grey(170, 196, 220, 238), counter=0.22, contrast=0.55, dorsal=0.06),
        "belly": Mat(grey(172, 198, 222, 240), "skin", base=0.6, contrast=0.45),
        "leg": Mat(hide, "skin", base=0.6, contrast=0.5),
        "spine": Mat(grey(46, 70, 98, 128, 156), "horn", base=0.55),
        "claw": Mat(grey(30, 52, 76, 100), "horn", base=0.5),
        "tooth": Mat(grey(170, 200, 226, 246), "bone", base=0.62),
    }
    eye = (246, 246, 246)
    eye2 = (214, 214, 214)

    def decor(ctx):
        if ctx.is_("torso", "chest", "neck", "tail"):
            stripes(ctx, period=4, width=1, mul=0.55, seed=1)
        if ctx.is_("leg") and "lower" not in ctx.bone:
            stripes(ctx, period=3, width=1, mul=0.6, faces=("right", "left", "front", "back"))
        if ctx.is_("leg") and "lower" in ctx.bone:
            blend(ctx, (60, 60, 60), lambda f, x, y, fx, fy, fz: 0.5 * fy, SIDES)
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # a cluster of six eyes: a big pair, a small pair above, one more on each cheek
            for x in range(fw):
                ctx.tint("front", x, 0, 0.7)
            for x in (0, fw - 1):
                ctx.put("front", x, 1, eye, glow=True)
            for x in (1, fw - 2):
                ctx.put("front", x, 0, eye2, glow=True)
            ctx.put("front", fw // 2, 1, (60, 60, 60))
            fw, fh = ctx.size("right")
            for face, x in (("right", fw - 2), ("left", 1)):
                ctx.put(face, x, 2, eye2, glow=True)
        if ctx.is_("snout"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.put(face, x, fh - 1, (40, 40, 40))
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (92, 92, 92))
        if ctx.is_("quill", "horn", "spike", "barb"):
            blend(ctx, (232, 232, 232), lambda f, x, y, fx, fy, fz: 0.6 * (1 - fy) if not ctx.is_("barb") else 0.3, SIDES)

    img, glow = paint(lay, mats, decor, seed=83)
    out("realm_stalker", img, glow)


@painter("realm_grazer")
def realm_grazer():
    lay = load("realm_grazer")
    hide = grey(100, 132, 164, 196, 222)
    mats = {
        "hide": Mat(hide, "fur", base=0.62, belly=grey(178, 202, 224, 240), counter=0.22, contrast=0.45),
        "belly": Mat(grey(180, 204, 226, 242), "fur", base=0.6, contrast=0.4),
        "shag": Mat(grey(62, 90, 120, 150, 180), "hair", base=0.55, contrast=0.75, scale=1.3),
        "face": Mat(grey(146, 176, 206, 230, 246), "fur", base=0.6, contrast=0.4),
        "leg": Mat(hide, "fur", base=0.6, contrast=0.45),
        "horn": Mat(grey(52, 76, 102, 130, 160), "horn", base=0.55),
        "hoof": Mat(grey(34, 52, 72, 94), "horn", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            for face, x in (("right", fw - 2), ("left", 1)):
                ctx.put(face, x, 2, (30, 30, 30))
                ctx.put(face, x, 1, (80, 80, 80))
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.8 if fy < 0.25 else 1, ("front", "right", "left"))
        if ctx.is_("snout"):
            fw, fh = ctx.size("front")
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.1, ("front",))
            ctx.put("front", 0, 1, (128, 128, 128))
            ctx.put("front", fw - 1, 1, (128, 128, 128))
            for f in ("right", "left"):
                w, h = ctx.size(f)
                for x in range(w):
                    ctx.put(f, x, h - 1, (80, 80, 80))
        if ctx.is_("horn", "horn_tip"):
            # growth ridges across the curl
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.25 if int(fz * 10) % 2 == 0 else 0.92, ("right", "left", "top", "bottom"))
        if ctx.is_("leg") and "lower" in ctx.bone:
            blend(ctx, (84, 84, 84), lambda f, x, y, fx, fy, fz: 0.6 * fy, SIDES)
        if ctx.is_("torso"):
            # pale flank spots
            shade(ctx, lambda f, x, y, fx, fy, fz: 1.15 if (x * 5 + y * 7) % 11 == 0 and 0.2 < fy < 0.8 else 1, ("right", "left"))
        if ctx.is_("fringe", "saddle", "hump", "mane"):
            blend(ctx, (196, 196, 196), lambda f, x, y, fx, fy, fz: max(0.0, fy - 0.6) * 0.6, SIDES)
            ragged(ctx, 0.45 if ctx.is_("fringe") else 0.25)

    img, glow = paint(lay, mats, decor, seed=89)
    out("realm_grazer", img)


@painter("realm_wyrm")
def realm_wyrm():
    lay = load("realm_wyrm")
    sc = grey(92, 124, 156, 190, 218)
    mats = {
        "scales": Mat(sc, "scales", base=0.6, belly=grey(176, 200, 222, 240), dorsal=0.08, contrast=0.45, scale=1.2),
        "belly": Mat(grey(178, 202, 224, 242), "chitin", base=0.6, contrast=0.55),
        "frill": Mat(grey(80, 112, 146, 182, 214), "membrane", base=0.6),
        "horn": Mat(grey(46, 70, 96, 124), "horn", base=0.55),
    }
    eye = (246, 246, 246)
    rim = (238, 238, 238)

    def decor(ctx):
        if ctx.is_("segment"):
            # dark diamond saddles along the back, a dotted lateral line
            fw, fh = ctx.size("top")
            for y in range(fh):
                k = 2 - abs((y % 5) - 2)
                for x in range(fw):
                    if abs(x + 0.5 - fw / 2) <= k + 0.5:
                        ctx.tint("top", x, y, 0.58)
            for f in ("right", "left"):
                w, h = ctx.size(f)
                for x in range(w):
                    if x % 5 == 2:
                        ctx.tint(f, x, 0, 0.6)
                        ctx.tint(f, x, 1, 0.7)
                    if x % 2 == 0:
                        ctx.tint(f, x, h // 2, 1.18)
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            for face, x, dx in (("right", fw - 3, -2), ("left", 2, 2)):
                ctx.tint(face, x, 0, 0.6)
                ctx.put(face, x, 1, eye, glow=True)
                ctx.put(face, x + dx, 1, (214, 214, 214), glow=True)
            fw, fh = ctx.size("front")
            teeth_row(ctx, "front", fh - 1, color=(226, 226, 226), gap=(70, 70, 70), x0=1, x1=fw - 1)
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if (x + y) % 3 == 0 and fz > 0.3 else 1, ("top",))
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (104, 104, 104))
        if ctx.is_("frill"):
            # a fan with dark rays and spots, pale scalloped rim; root at the head (fx = 1)
            faces = ("front", "back")

            def polar(fx, fy):
                # radius from the root at the head (fx = 1), corrected for the 6 x 8 plane's aspect
                return math.hypot(1 - fx, (fy - 0.5) * 1.9), math.atan2(fy - 0.5, 1 - fx)

            shade(ctx, lambda f, x, y, fx, fy, fz: 0.68 if int((polar(fx, fy)[1] + 2) * 5) % 2 == 0 else 1, faces)
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.6 if abs(polar(fx, fy)[0] - 0.55) < 0.08 else 1, faces)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, polar(fx, fy)[0] - 0.75) * 3, faces)
            cut(ctx, lambda fx, fy, fz: polar(fx, fy)[0] <= 1.02 - 0.12 * abs(math.sin(polar(fx, fy)[1] * 4)), faces)
        if ctx.is_("crest", "dorsal"):
            faces = ("right", "left")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if x % 2 == 0 else 1, faces)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, 0.35 - fy) * 1.5, faces)
            # backward-raking sawtooth spines along the frill's top edge
            cut(ctx, lambda fx, fy, fz: fy >= 0.75 * ((fz * 2) % 1) ** 2, faces)
        if ctx.is_("tail_fin"):
            faces = ("right", "left")
            shade(ctx, lambda f, x, y, fx, fy, fz: 0.7 if y % 2 == 0 else 1, faces)
            blend(ctx, rim, lambda f, x, y, fx, fy, fz: max(0.0, fz - 0.7) * 2, faces)
            cut(ctx, lambda fx, fy, fz: abs(fy - 0.5) * 2 <= 0.2 + 0.8 * math.sin(fz * math.pi * 0.85), faces)

    img, glow = paint(lay, mats, decor, seed=97)
    out("realm_wyrm", img, glow)


# ================================================================================================ main


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)


if __name__ == "__main__":
    main(sys.argv[1:])
