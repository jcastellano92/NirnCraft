"""Textures of the realm creatures (rig group RealmDefs): the Deadlands, the Shivering Isles, Apocrypha, the Soul Cairn,
Sovngarde, Coldharbour and Miraak, painted from the rig layouts. All original procedural work.

Run tools/textures/dump_realm_rigs.sh first when a rig changed, then (from tools/textures):
    py -3 bestiary_realms.py [name ...]
Writes textures/entity/creatures/<name>[_<variant>].png plus the _glow.png emissive layers, and the spawn egg item
models (models/item/<id>_spawn_egg.json) of every realm creature.
"""
import colorsys
import json
import math
import os
import random
import sys

from rigpaint import FACE_LIGHT, OUT, Mat, clamp, hash2, has_pixels, lerp, load, paint, pal, ramp, save, shift, vnoise
from bestiary_daedra import SIDES, base_t, crack_walk, face_px, setpx, voronoi

PAINTERS = {}

# entity ids (registered by RealmCreatures) that need a spawn egg item model
EGG_IDS = ["clannfear", "daedroth", "spider_daedra", "grummite", "baliwog", "gnarl", "elytra", "flesh_atronach", "seeker",
           "lurker", "keeper", "boneman", "mistman", "wrathman", "wandering_soul", "sovngarde_hero", "tsun", "harvester",
           "miraak"]


def painter(name):
    def wrap(fn):
        PAINTERS[name] = fn
        return fn
    return wrap


def out(name, img, glow=None):
    save(img, "creatures/%s.png" % name)
    if glow is not None and has_pixels(glow):
        save(glow, "creatures/%s_glow.png" % name)
    elif glow is not None:
        print("  (no glow pixels for %s)" % name)


# ================================================================================================ helpers


def put_glow(ctx, face, x, y, c):
    """Writes only the glow layer at one texel of a face."""
    x0, y0, fw, fh = ctx.f[face]
    if 0 <= x < fw and 0 <= y < fh:
        X, Y = x0 + x, y0 + y
        if X < ctx.glow.width and Y < ctx.glow.height:
            ctx.gx[X, Y] = (clamp(c[0]), clamp(c[1]), clamp(c[2]), 255)


def set_alpha(ctx, face, x, y, a):
    x0, y0, fw, fh = ctx.f[face]
    if 0 <= x < fw and 0 <= y < fh:
        X, Y = x0 + x, y0 + y
        c = ctx.px[X, Y]
        ctx.px[X, Y] = (c[0], c[1], c[2], clamp(a))


def ragged(ctx, faces=SIDES, depth=2, seed=0, keep=0):
    """Tatters: cuts an irregular hem out of the bottom rows of the given faces (cutout transparency)."""
    for face in faces:
        fw, fh = ctx.size(face)
        if fh <= keep + 1:
            continue
        for x in range(fw):
            cut = int(hash2(x, seed, ctx.seed % 97) * (depth + 1))
            if hash2(x // 2, seed + 3, 11) > 0.7:
                cut += 1
            for y in range(max(keep, fh - cut), fh):
                set_alpha(ctx, face, x, y, 0)


def fade(ctx, faces, a0, a1):
    """Scales the alpha of a box's faces from a0 (top row) to a1 (bottom row)."""
    for face in faces:
        fw, fh = ctx.size(face)
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            f = yy / max(1, fh - 1) if face in SIDES else 0.5
            c = ctx.px[X, Y]
            if c[3] == 0:
                continue
            a = (a0 + (a1 - a0) * f) / 255.0
            ctx.px[X, Y] = (c[0], c[1], c[2], clamp(c[3] * a))


def body_glow(img, glow, k=0.3, tint=None):
    """Spirits: adds a faint luminous copy of the whole body to the glow layer (kept where it is already brighter)."""
    px = img.load()
    gx = glow.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            gr, gg, gb, ga = gx[x, y]
            f = k * (0.4 + 0.6 * a / 255.0)
            c = (r * f, g * f, b * f)
            if tint:
                c = (c[0] * tint[0], c[1] * tint[1], c[2] * tint[2])
            if ga == 0:
                gr = gg = gb = 0
            gx[x, y] = (clamp(max(gr, c[0])), clamp(max(gg, c[1])), clamp(max(gb, c[2])), 255)


def glyphs(ctx, faces, color, dim, rnd, chance=0.5, glow=True):
    """Angular glyph strokes: a vertical stem with alternating branches."""
    for face in faces:
        fw, fh = ctx.size(face)
        if fw < 3 or fh < 4 or rnd.random() > chance:
            continue
        x = rnd.randrange(1, fw - 1)
        for y in range(1, fh - 1):
            ctx.put(face, x, y, color, glow=glow)
        for y in range(2, fh - 2, 2):
            dx = 1 if rnd.random() < 0.5 else -1
            ctx.put(face, x + dx, y, dim, glow=glow)


def bands(ctx, period=3, mul=0.8, faces=SIDES, offset=0):
    for face in faces:
        fw, fh = ctx.size(face)
        for y in range(offset, fh, period):
            for x in range(fw):
                ctx.tint(face, x, y, mul)


def edge_light(ctx, faces=SIDES, mul=1.2):
    for face in faces:
        fw, fh = ctx.size(face)
        for x in range(fw):
            ctx.tint(face, x, 0, mul)


def dots(ctx, faces, density, color, seed, glow=False, mul=None):
    for face in faces:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            if hash2(X, Y, seed) > 1.0 - density:
                if mul:
                    ctx.tint(face, xx, yy, mul)
                else:
                    ctx.put(face, xx, yy, color, glow=glow)


def round_cut(ctx, face):
    """Cuts a face to a disc (round shields): texels outside the inscribed circle become transparent."""
    fw, fh = ctx.size(face)
    cx, cy = (fw - 1) / 2.0, (fh - 1) / 2.0
    r = min(fw, fh) / 2.0
    for y in range(fh):
        for x in range(fw):
            if (x - cx) ** 2 + (y - cy) ** 2 > (r - 0.15) ** 2:
                set_alpha(ctx, face, x, y, 0)


def iridescent(ctx, h0, h1, sat, seed, lo=0.08, hi=0.95, seams=4):
    """An iridescent shell: the hue shifts across the surface; segment seams and a glossy highlight stripe."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            t = base_t(face, yy, fh, 0.25)
            if seams and face in ("top", "back", "front") and yy % seams == seams - 1:
                t -= 0.25
            if fw > 2 and abs(xx - fw * 0.3) < 0.8 and face in ("top", "right", "left"):
                t += 0.25
            t += (vnoise(X, Y, seed, 3) - 0.5) * 0.12
            ph = 0.5 + 0.5 * math.sin(X * 0.33 + Y * 0.21 + seed * 0.1)
            h = (h0 + (h1 - h0) * ph) % 1.0
            v = lo + (hi - lo) * max(0.0, min(1.0, t))
            s = sat * (1.0 - max(0.0, t - 0.75) * 1.6)
            r, g, b = colorsys.hsv_to_rgb(h, max(0.0, s), v)
            setpx(ctx, X, Y, (r * 255, g * 255, b * 255))


def chainmail(ctx, palette, light=0.0, alpha=255):
    """Riveted ring mail: alternating rows of bright ring tops and dark gaps."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            t = base_t(face, yy, fh, 0.2) + light
            off = (yy // 2) % 2
            if yy % 2 == 0:
                t += 0.18 if (xx + off) % 2 == 0 else -0.08
            else:
                t -= 0.16 if (xx + off) % 2 == 1 else 0.02
            setpx(ctx, X, Y, ramp(palette, t), alpha=alpha)


def plate(ctx, palette, light=0.0, alpha=255, rivets=True, trim=None):
    """Bevelled plate armour: lit top/left edges, dark bottom/right edges, a faint brushed sheen and rivets."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            t = base_t(face, yy, fh, 0.28) + light
            edge = False
            if fw > 2 and fh > 2:
                if yy == 0 or xx == 0:
                    t += 0.2
                    edge = True
                if yy == fh - 1 or xx == fw - 1:
                    t -= 0.18
                    edge = True
            t += (vnoise(X, Y, ctx.seed, 3) - 0.5) * 0.1 + (hash2(0, Y, ctx.seed) - 0.5) * 0.05
            if rivets and fw >= 5 and fh >= 4 and yy == 1 and xx in (1, fw - 2):
                t += 0.3
            c = ramp(palette, t)
            if trim is not None and edge:
                c = ramp(trim, t + 0.1)
            setpx(ctx, X, Y, c, alpha=alpha)


def stitched_flesh(ctx, palettes, seed, cell=5.0, alpha=255):
    """Patchwork flesh: skin patches of different tones joined by dark seams with pale stitches."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            d, edge, cid = voronoi(X, Y, seed, cell)
            p = palettes[int(cid * len(palettes)) % len(palettes)]
            t = base_t(face, yy, fh, 0.22) + (vnoise(X, Y, seed + 3, 4) - 0.5) * 0.18
            if hash2(X, Y, seed + 9) > 0.985:
                t -= 0.15
            c = ramp(p, t)
            if edge < 0.05:
                c = (84, 30, 32)
                if (X + Y) % 3 == 0:
                    c = (200, 186, 156)
            elif edge < 0.11:
                c = lerp(c, (130, 64, 62), 0.35)
            setpx(ctx, X, Y, c, alpha=alpha)


def leaves(ctx, palette, seed, holes=0.18):
    """Leafy clumps with holes (cutout)."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            n = vnoise(X, Y, seed, 1.6)
            t = base_t(face, yy, fh, 0.3) + (n - 0.5) * 0.6
            a = 255
            if hash2(X, Y, seed) < holes and (xx in (0, fw - 1) or yy in (0, fh - 1) or hash2(X, Y, seed + 1) < 0.5):
                a = 0
            setpx(ctx, X, Y, ramp(palette, t), alpha=a)


def tentacle_face(ctx, face, flesh, eyes, seed, glow_eye=True):
    """A hood's dark hollow: a void with writhing tentacle shapes and a few glowing points."""
    fw, fh = ctx.size(face)
    for y in range(fh):
        for x in range(fw):
            ctx.put(face, x, y, (6, 8, 7))
    rnd = random.Random(seed)
    for k in range(fw):
        x = k
        for y in range(rnd.randrange(1, 3), fh):
            if 0 <= x < fw:
                ctx.put(face, x, y, ramp(flesh, 0.25 + 0.35 * y / fh))
            if rnd.random() < 0.35:
                x += rnd.choice((-1, 1))
    for (x, y) in eyes:
        ctx.put(face, x, y, (150, 255, 120), glow=glow_eye)


# ================================================================================================ the Deadlands


@painter("clannfear")
def clannfear():
    lay = load("clannfear")
    scales = pal("#1e0a06", "#3e160c", "#662614", "#8e3c1e", "#b05a2e", "#c87a46")
    mats = {
        "clann_scales": Mat(scales, "scales", base=0.55, dorsal=0.1, counter=0.18, stripes=(6, 1.2, 0.12), scale=0.7),
        "clann_head": Mat(shift(scales, mul=1.05), "scales", base=0.55, dorsal=0.08, scale=0.6),
        "clann_belly": Mat(pal("#5a2e18", "#8a5030", "#b47a4c", "#d4a070", "#e8c290"), "chitin", base=0.55, scale=0.6),
        "beak": Mat(pal("#0e0a08", "#221a14", "#3a2e24", "#56463a", "#76665a"), "horn", base=0.55),
        "bone": Mat(pal("#5a4a36", "#8a7658", "#b4a07c", "#d6c6a2", "#ece0c4"), "bone", base=0.6),
        "frill": Mat(pal("#4a1a10", "#7a3220", "#a8583a", "#ccaa84", "#e6d4b2"), "bone", base=0.62, grad=0.35),
        "horn": Mat(pal("#2a2018", "#5a4a36", "#8e7a5c", "#c4b08c", "#e6d8b8"), "horn", base=0.6, grad=0.35),
        "claw": Mat(pal("#0c0a08", "#201a14", "#3a3028", "#5a4e42"), "horn", base=0.5),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("right")
            for face, x in (("right", fw - 2), ("left", 1)):
                ctx.tint(face, x, 1, 0.55)
                ctx.tint(face, x + (1 if face == "left" else -1), 1, 0.65)
                ctx.put(face, x, 2, (255, 190, 40), glow=True)
                ctx.put(face, x + (1 if face == "left" else -1), 2, (200, 90, 10), glow=True)
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, (30, 10, 6))
        if t in ("beak", "beak_tip", "beak_low"):
            for face in ("top",) + SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.tint(face, x, 0, 1.35)
        if t == "frill":
            # radiating ridges, a dark red rim and a ring of eye-like spots
            fw, fh = ctx.size("front")
            for face in ("front", "back"):
                for y in range(fh):
                    for k in range(-2, 3):
                        x = int(round(fw / 2.0 + k * (1.0 + 2.2 * (fh - 1 - y) / max(1, fh - 1))))
                        ctx.tint(face, x, y, 0.75)
                for x in range(fw):
                    ctx.put(face, x, 0, (90, 24, 14))
                for y in range(fh):
                    ctx.put(face, 0, y, (90, 24, 14))
                    ctx.put(face, fw - 1, y, (90, 24, 14))
            for x in (2, fw - 3):
                ctx.put("front", x, 2, (40, 12, 8))
                ctx.put("front", x, 3, (200, 120, 40))
        if t == "cheek_frill":
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    ctx.tint(face, 0, y, 0.7)
        if t in ("horn_tip",):
            for face in ctx.all_faces():
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        ctx.tint(face, x, y, 0.65 + 0.35 * (fh - y) / max(1, fh))
        if t == "torso":
            fw, fh = ctx.size("top")
            for y in range(fh):
                ctx.tint("top", fw // 2, y, 0.8)

    img, glow = paint(lay, mats, decor, seed=211)
    out("clannfear", img, glow)
    # the runt: duller, browner hide
    mats["clann_scales"] = Mat(shift(scales, 14, 8, 0, mul=0.95), "scales", base=0.55, dorsal=0.12, counter=0.18,
                               stripes=(5, 1.2, 0.16), scale=0.7)
    mats["clann_head"] = Mat(shift(scales, 14, 8, 0, mul=1.0), "scales", base=0.55, dorsal=0.08, scale=0.6)
    img, glow2 = paint(lay, mats, decor, seed=212)
    out("clannfear_runt", img, None)


@painter("daedroth")
def daedroth():
    lay = load("daedroth")
    scales = pal("#2a0806", "#561206", "#86200a", "#b23a12", "#d45e22", "#ea8a40")
    mats = {
        "daedroth_scales": Mat(scales, "scales", base=0.55, dorsal=0.14, counter=0.2, scale=1.2),
        "daedroth_head": Mat(scales, "scales", base=0.56, dorsal=0.1, scale=0.9),
        "daedroth_dark": Mat(pal("#120404", "#260a06", "#3e120a", "#5a1e10", "#762c18"), "scales", base=0.5),
        "daedroth_belly": Mat(pal("#6a3010", "#9c5418", "#c8802a", "#e4a848", "#f4cc78"), "chitin", base=0.55, scale=0.7),
        "horn": Mat(pal("#0c0806", "#1e1610", "#38281c", "#56402c", "#7a5c40"), "horn", base=0.55, grad=0.3),
        "tooth": Mat(pal("#8a7a5a", "#bcae8a", "#e0d6b6", "#f6f0dc"), "bone", base=0.65),
        "claw": Mat(pal("#0a0606", "#1c1210", "#34241e", "#4e3a30"), "horn", base=0.5),
    }
    EMBER = pal("#5a0a02", "#a82a06", "#ec6a14", "#ffb03a", "#fff0a0")

    def decor(ctx):
        t = ctx.tag
        rnd = random.Random(ctx.seed)
        if t == "head":
            for face, x in (("right", ctx.size("right")[0] - 2), ("left", 1)):
                ctx.tint(face, x, 0, 0.5)
                ctx.put(face, x, 1, (255, 210, 60), glow=True)
                ctx.put(face, x + (1 if face == "left" else -1), 1, (255, 140, 20), glow=True)
        if t == "snout":
            # mouth roof glowing with banked fire; nostril pits and scute ridges
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(fw):
                    c = ramp(EMBER, 0.45 + 0.35 * hash2(x, y, 7) - 0.3 * abs(x - fw / 2.0) / fw)
                    ctx.put("bottom", x, y, c, glow=True)
            fw, fh = ctx.size("top")
            for y in range(0, fh, 2):
                for x in range(fw):
                    ctx.tint("top", x, y, 0.82)
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                ctx.hline(face, fh - 1, (30, 6, 4))
        if t == "jaw":
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    c = ramp(EMBER, 0.5 + 0.4 * hash2(x, y, 9) - 0.4 * abs(x - fw / 2.0) / fw)
                    ctx.put("top", x, y, c, glow=True)
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                ctx.hline(face, 0, (30, 6, 4))
        if t == "throat":
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(fw):
                    ctx.tint("bottom", x, y, 1.0, add=(30, 12, 0))
        if t == "nostrils":
            fw, fh = ctx.size("top")
            ctx.put("top", 0, fh - 1, (20, 4, 2))
            ctx.put("top", fw - 1, fh - 1, (20, 4, 2))
        if t == "belly":
            bands(ctx, period=2, mul=0.78, faces=("front",))
        if t in ("torso", "shoulder"):
            # a few banked embers glowing between the scales
            for face in ("front", "right", "left"):
                fw, fh = ctx.size(face)
                for _ in range(2 if fw * fh > 40 else 0):
                    x, y = rnd.randrange(fw), rnd.randrange(fh)
                    ctx.put(face, x, y, (255, 120, 30), glow=True)
        if t in ("horn_tip", "spine"):
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.tint(face, x, 0, 1.5)

    img, glow = paint(lay, mats, decor, seed=221)
    out("daedroth", img, glow)


@painter("spider_daedra")
def spider_daedra():
    lay = load("spider_daedra")
    chitin = pal("#07040a", "#140a1c", "#24132e", "#381e44", "#52305e", "#6c4678")
    skin = pal("#6e6274", "#968a9c", "#bcb0c0", "#dcd2de", "#f2ecf2")
    mats = {
        "sd_chitin": Mat(chitin, "chitin", base=0.55, scale=0.8),
        "sd_leg": Mat(chitin, "chitin", base=0.5, scale=0.6, stripes=(6, 1.0, -0.18)),
        "sd_abdomen": Mat(shift(chitin, mul=0.95), "chitin", base=0.5, scale=1.2),
        "sd_skin": Mat(skin, "skin", base=0.58, contrast=0.5),
        "sd_hair": Mat(pal("#05030a", "#110a18", "#1e1428", "#2e2040"), "hair", base=0.5, contrast=0.7),
        "sd_claw": Mat(pal("#0a060c", "#1e1424", "#3a2a44", "#6a5874", "#a898b0"), "horn", base=0.5, grad=0.4),
    }
    VIOLET = (190, 90, 255)

    def decor(ctx):
        t = ctx.tag
        if t == "face":
            fw, fh = ctx.size("front")
            # six eyes in two rows, dark lips, violet markings down the cheeks
            for (x, y) in ((1, 1), (2, 1), (3, 1), (1, 2), (3, 2)):
                ctx.put("front", x, y, (235, 60, 110) if y == 1 else (190, 70, 255), glow=True)
            ctx.put("front", 2, 2, (40, 10, 50))
            for y in range(1, 3):
                ctx.tint("front", 0, y, 0.6)
                ctx.tint("front", fw - 1, y, 0.6)
            ctx.put("front", 2, fh - 1, (70, 20, 60))
            ctx.put("front", 1, fh - 1, (100, 40, 90))
            ctx.put("front", 3, fh - 1, (100, 40, 90))
            for face in ("right", "left"):
                sw, sh = ctx.size(face)
                x = sw - 2 if face == "right" else 1
                for y in range(1, sh):
                    ctx.put(face, x, y, (110, 50, 130))
        if t in ("chest", "waist"):
            fw, fh = ctx.size("front")
            for y in range(fh):
                ctx.tint("front", fw // 2, y, 0.86)
            for face in ("right", "left"):
                sw, sh = ctx.size(face)
                for y in range(0, sh, 2):
                    ctx.put(face, sw // 2, y, (120, 70, 140))
        if t == "abdomen":
            # an hourglass sigil glowing faintly on the back of the abdomen, and chevrons down the top
            fw, fh = ctx.size("top")
            cx = fw / 2.0
            for y in range(fh):
                w = abs(y - fh / 2.0) / (fh / 2.0) * (fw * 0.3) + 0.6
                for x in range(fw):
                    if abs(x + 0.5 - cx) < w and 1 < y < fh - 1:
                        ctx.put("top", x, y, lerp(ctx.get("top", x, y)[:3], VIOLET, 0.55))
                        put_glow(ctx, "top", x, y, (80, 30, 120))
            fw, fh = ctx.size("back")
            for y in range(1, fh - 1):
                for x in range(fw):
                    if abs(x - (fw - 1) / 2.0) <= abs(y - (fh - 1) / 2.0) * 0.6 + 0.5:
                        ctx.put("back", x, y, (120, 60, 170), glow=False)
        if t == "leg":
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(0, fw, 5):
                    for y in range(fh):
                        ctx.tint(face, x, y, 1.6)
        if t in ("leg_tip", "claw", "spike", "fang"):
            for face in ctx.all_faces():
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        ctx.tint(face, x, y, 1.25)
        if t in ("cup", "girdle", "gauntlet", "pauldron", "back_plate", "crown"):
            edge_light(ctx, mul=1.6)

    img, glow = paint(lay, mats, decor, seed=231)
    out("spider_daedra", img, glow)


# ================================================================================================ the Shivering Isles


@painter("grummite")
def grummite():
    lay = load("grummite")

    def make(skin, belly, cloth, robe, seed, magus):
        mats = {
            "grum_skin": Mat(skin, "skin", base=0.55, dorsal=0.12, counter=0.15, spots=(0.12, 1.4, -0.15)),
            "grum_belly": Mat(belly, "skin", base=0.58, contrast=0.6),
            "grum_dark": Mat(shift(skin, mul=0.7), "skin", base=0.5),
            "grum_fin": Mat(pal("#1e2a22", "#344838", "#4e6a52", "#6e8c6a", "#94b08a"), "membrane", base=0.55),
            "grum_eye": Mat(pal("#5a4a10", "#a08a20", "#d8c040", "#f4e27a"), "plain", base=0.6),
            "grum_cloth": Mat(cloth, "cloth", base=0.5),
            "claw": Mat(pal("#141210", "#2a2620", "#46403a", "#6a6258"), "horn", base=0.5),
            "wood": Mat(pal("#2a1a0e", "#46301c", "#644628", "#7e5c38"), "bark", base=0.55, scale=0.7),
            "wood_dark": Mat(pal("#1a100a", "#2e1e12", "#4a3220", "#64462e"), "bark", base=0.5, scale=0.8),
            "bone": Mat(pal("#5a5040", "#8a7e66", "#b8ac90", "#ddd2b8"), "bone", base=0.6),
            "shell": Mat(pal("#2a5a62", "#3e8a90", "#62bcb8", "#a2e6da", "#e2fff6"), "crystal", base=0.55, glow=magus,
                         glow_strength=0.9),
            "robe": Mat(robe, "cloth", base=0.5),
        }

        def decor(ctx):
            t = ctx.tag
            if t == "head":
                fw, fh = ctx.size("front")
                ctx.hline("front", fh - 1, (22, 26, 20))
                for x in range(1, fw - 1, 2):
                    ctx.tint("front", x, fh - 2, 0.85)
            if t == "eye_bulb":
                # yellow iris with a horizontal black slit facing forward and out
                for face in ("front", "right", "left"):
                    fw, fh = ctx.size(face)
                    for x in range(fw):
                        ctx.put(face, x, fh // 2, (16, 14, 6))
                    ctx.put(face, 0, 0, (250, 248, 200))
                if magus:
                    for face in ("front",):
                        fw, fh = ctx.size(face)
                        for x in range(fw):
                            put_glow(ctx, face, x, 0, (120, 110, 40))
            if t == "throat":
                bands(ctx, period=2, mul=0.85, faces=("bottom", "front"))
            if t == "belly":
                bands(ctx, period=2, mul=0.86, faces=("front",))
            if t in ("ear_fin", "dorsal_fin"):
                for face in ("right", "left", "front", "back"):
                    fw, fh = ctx.size(face)
                    for x in range(0, fw, 2):
                        for y in range(fh):
                            ctx.tint(face, x, y, 0.72)
            if t in ("loincloth", "robe_skirt"):
                ragged(ctx, depth=2, seed=ctx.seed % 13)
            if t == "strap":
                fw, fh = ctx.size("front")
                ctx.put("front", fw // 2, 0, (180, 160, 90))
            if t == "staff_head" and magus:
                for face in ctx.all_faces():
                    fw, fh = ctx.size(face)
                    ctx.put(face, fw // 2, fh // 2, (220, 255, 250), glow=True)
            if magus and t in ("hood_mantle", "robe_skirt"):
                # ochre trim and a few sigils
                for face in SIDES:
                    fw, fh = ctx.size(face)
                    for x in range(fw):
                        ctx.put(face, x, 0, (176, 130, 46))
                rnd = random.Random(ctx.seed)
                glyphs(ctx, ("front", "back"), (120, 230, 220), (60, 140, 140), rnd, chance=0.8)

        return paint(lay, mats, decor, seed=seed)

    skin = pal("#161c14", "#2a3424", "#425036", "#5c6c48", "#7a8a60", "#96a47a")
    img, glow = make(skin, pal("#5e5a3a", "#8a8458", "#b0a87a", "#cec6a0"), pal("#24180e", "#3c2a1a", "#58402a", "#70563a"),
                     pal("#1a1022", "#2c1a38", "#42284e", "#583866"), 241, False)
    out("grummite", img)
    mskin = shift(skin, -6, 0, 14, mul=1.02)
    img, glow = make(mskin, pal("#5a5a48", "#86866a", "#acac8c", "#ccccae"), pal("#24180e", "#3c2a1a", "#58402a", "#70563a"),
                     pal("#170a24", "#2a143e", "#3e1e5a", "#563076", "#704490"), 242, True)
    out("grummite_magus", img, glow)


@painter("baliwog")
def baliwog():
    lay = load("baliwog")
    skin = pal("#12160e", "#222a1a", "#384428", "#525e3a", "#6e7a4e", "#8a9464")
    mats = {
        "bali_skin": Mat(skin, "skin", base=0.55, dorsal=0.15, counter=0.25, spots=(0.16, 1.3, -0.18)),
        "bali_belly": Mat(pal("#6a6448", "#968e68", "#bcb48c", "#dad2ae"), "skin", base=0.58, contrast=0.5),
        "bali_fin": Mat(pal("#1c2216", "#2e3824", "#485636", "#66744c", "#88966a"), "membrane", base=0.6),
        "bali_gill": Mat(pal("#5a1a20", "#8e2c34", "#c04a52", "#e07a7c", "#f4aaa8"), "plain", base=0.6),
        "bali_eye": Mat(pal("#5a4a10", "#a08a20", "#d8c040", "#f4e27a"), "plain", base=0.6),
        "claw": Mat(pal("#141210", "#2a2620", "#46403a"), "horn", base=0.5),
        "tooth": Mat(pal("#8a826a", "#bcb498", "#e0dac2", "#f6f2e2"), "bone", base=0.65),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 1, (26, 16, 14))
        if t == "snout":
            fw, fh = ctx.size("front")
            ctx.put("front", 1, 0, (10, 12, 8))
            ctx.put("front", fw - 2, 0, (10, 12, 8))
            ctx.hline("front", fh - 1, (26, 16, 14))
            for x in range(0, fw, 2):
                ctx.put("front", x, fh - 1, (220, 214, 190))
        if t == "eye_bulb":
            for face in ("front", "right", "left", "top"):
                fw, fh = ctx.size(face)
                ctx.put(face, fw // 2, fh // 2, (14, 12, 6))
        if t == "gill":
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(0, fw, 2):
                    for y in range(fh):
                        ctx.tint(face, x, y, 0.75)
        if t in ("dorsal_fin", "tail_fin"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(0, fw, 2):
                    for y in range(fh):
                        ctx.tint(face, x, y, 0.75)
                for x in range(fw):
                    ctx.tint(face, x, 0, 1.25)

    img, glow = paint(lay, mats, decor, seed=251)
    out("baliwog", img)


@painter("gnarl")
def gnarl():
    lay = load("gnarl")
    variants = [
        # name, bark, dark bark, moss, leaves, eye colour
        ("gnarl", pal("#1c120a", "#34241a", "#4e3828", "#6a4e38", "#86684c"), pal("#0e0806", "#1e140e", "#2e2018", "#423022"),
         pal("#1e2c12", "#34481e", "#4e6a2a", "#6c8a3a"), pal("#1e3a10", "#2e5a18", "#467c22", "#68a032", "#90c24a"), (255, 170, 40)),
        ("gnarl_elder", pal("#14160e", "#262a1c", "#3a402c", "#52583e", "#6c7252"), pal("#0a0c08", "#161a10", "#22281a", "#323a26"),
         pal("#142010", "#22361a", "#345024", "#486a30"), pal("#3a4a10", "#5a6e18", "#7e9424", "#a6ba3a", "#cad86a"), (170, 255, 90)),
        ("gnarl_ancient", pal("#3a3a40", "#5c5c64", "#82828a", "#a8a8b0", "#cacad0"), pal("#1e1e24", "#2e2e36", "#44444c", "#5a5a64"),
         pal("#2a3a44", "#3e5662", "#587680", "#78969e"), pal("#2a2448", "#40386e", "#5c5294", "#8278ba", "#b0a8dc"), (150, 210, 255)),
    ]
    for i, (name, bark, dark, moss, leaf, eye) in enumerate(variants):
        mats = {
            "bark": Mat(bark, "bark", base=0.55, grad=0.3),
            "bark_face": Mat(bark, "bark", base=0.6, grad=0.2),
            "bark_dark": Mat(dark, "bark", base=0.5),
            "root": Mat(dark, "bark", base=0.55, scale=0.7),
            "moss": Mat(moss, "fur", base=0.55),
            "leaf": Mat(leaf, "flat"),
        }

        def decor(ctx, moss=moss, leaf=leaf, eye=eye):
            t = ctx.tag
            if ctx.mat == "leaf":
                leaves(ctx, leaf, ctx.seed % 101)
            if ctx.mat in ("bark", "bark_dark", "bark_face") and t not in ("twig",):
                # moss on the upward faces and creeping down the tops of the sides
                for xx, yy, fw, fh, X, Y in face_px(ctx, "top"):
                    if vnoise(X, Y, ctx.seed, 2) > 0.45:
                        setpx(ctx, X, Y, ramp(moss, 0.4 + vnoise(X, Y, ctx.seed + 4, 1.4) * 0.5))
                for face in SIDES:
                    fw, fh = ctx.size(face)
                    for x in range(fw):
                        drip = int(hash2(x, 5, ctx.seed) * 2.6)
                        for y in range(min(drip, fh)):
                            ctx.put(face, x, y, ramp(moss, 0.55 - y * 0.15))
            if t == "head":
                fw, fh = ctx.size("front")
                # knot-hole eyes with an ember glow deep inside, a gaping knot mouth
                for (x, y) in ((1, 2), (fw - 3, 2)):
                    ctx.rect("front", x, y, 2, 2, (10, 6, 4))
                    ctx.put("front", x + (1 if x < fw // 2 else 0), y + 1, eye, glow=True)
                    put_glow(ctx, "front", x + (0 if x < fw // 2 else 1), y + 1, tuple(c * 0.4 for c in eye))
                ctx.rect("front", fw // 2 - 1, fh - 2, 3, 1, (14, 8, 6))
                ctx.put("front", fw // 2, fh - 1, (14, 8, 6))
            if t == "hollow":
                fw, fh = ctx.size("front")
                for y in range(fh):
                    for x in range(fw):
                        d = math.hypot(x - (fw - 1) / 2.0, y - (fh - 1) / 2.0)
                        if d < min(fw, fh) / 2.0:
                            ctx.put("front", x, y, (8, 5, 4))
                ctx.put("front", fw // 2, fh // 2, tuple(c * 0.7 for c in eye), glow=True)
            if t == "finger":
                for face in SIDES:
                    fw, fh = ctx.size(face)
                    ctx.tint(face, 0, fh - 1, 0.6)

        img, glow = paint(lay, mats, decor, seed=261 + i)
        out(name, img, glow)


@painter("elytra")
def elytra():
    lay = load("elytra")
    variants = [
        ("elytra", 0.42, 0.62, 0.75, (255, 70, 40)),
        ("elytra_hatchling", 0.17, 0.30, 0.55, (255, 150, 40)),
        ("elytra_noble", 0.72, 0.95, 0.8, (255, 210, 60)),
    ]
    for i, (name, h0, h1, sat, eye) in enumerate(variants):
        leg = [(int(r * 255), int(g * 255), int(b * 255)) for r, g, b in
               (colorsys.hsv_to_rgb((h0 + 0.02) % 1, sat * 0.6, v) for v in (0.05, 0.12, 0.2, 0.3, 0.42))]
        mats = {
            "ely_shell": Mat(leg, "flat"),
            "ely_case": Mat(leg, "flat"),
            "ely_abdomen": Mat(leg, "flat"),
            "ely_leg": Mat(leg, "chitin", base=0.5, scale=0.6),
            "ely_blade": Mat(pal("#3a3a28", "#6a6a4a", "#9a9a72", "#c8c89e", "#eeeed0"), "horn", base=0.6, grad=0.4),
            "ely_eye": Mat(pal("#3a0806", "#7a1a0c", "#c03a1a", "#f06a3a"), "plain", base=0.6),
            "ely_wing": Mat(pal("#6a7a6a", "#94a494", "#bcccbc", "#e2eee2"), "membrane", base=0.6, alpha=170),
        }

        def decor(ctx, h0=h0, h1=h1, sat=sat, eye=eye):
            t = ctx.tag
            if ctx.mat in ("ely_shell", "ely_case", "ely_abdomen"):
                iridescent(ctx, h0, h1, sat, ctx.seed % 50, seams=0 if ctx.mat == "ely_case" else 3)
            if t == "wing_case":
                for face in ("top",):
                    fw, fh = ctx.size(face)
                    for y in range(fh):
                        ctx.tint(face, 0, y, 0.6)
                        ctx.tint(face, fw - 1, y, 1.3)
                    for y in range(1, fh, 3):
                        for x in range(1, fw - 1, 2):
                            ctx.tint(face, x, y, 0.82)
            if t == "compound_eye":
                for face in ctx.all_faces():
                    fw, fh = ctx.size(face)
                    for y in range(fh):
                        for x in range(fw):
                            c = eye if (x + y) % 2 == 0 else tuple(v * 0.55 for v in eye)
                            ctx.put(face, x, y, c, glow=True)
                    ctx.put(face, 0, 0, (255, 240, 200), glow=True)
            if t == "head":
                fw, fh = ctx.size("front")
                ctx.hline("front", fh - 1, (20, 10, 10))
            if t in ("blade", "spike", "fang"):
                for face in SIDES:
                    fw, fh = ctx.size(face)
                    for x in range(fw):
                        ctx.tint(face, x, fh - 1, 1.3)
            if t == "wing":
                for face in ("top", "bottom"):
                    fw, fh = ctx.size(face)
                    for y in range(fh):
                        for x in range(fw):
                            if (x * 3 + y) % 7 == 0 or y == 0:
                                ctx.tint(face, x, y, 0.6)
                    for x in range(fw):
                        h = hash2(x, 3, ctx.seed)
                        for y in range(fh - 1 - int(h * 2), fh):
                            set_alpha(ctx, face, x, y, 0)

        img, glow = paint(lay, mats, decor, seed=271 + i)
        out(name, img, glow)


@painter("flesh_atronach")
def flesh_atronach():
    lay = load("flesh_atronach")
    tones = [pal("#5a3a36", "#8a5e56", "#b48478", "#d2a698", "#e8c4b6"),
             pal("#4e3634", "#7a5650", "#a27a70", "#c49c90", "#dcbcae"),
             pal("#5e4436", "#8e6a52", "#b88e72", "#d6b092", "#ead0b4"),
             pal("#4a3238", "#74505a", "#9a727c", "#bc94a0", "#d8b6c0")]
    SIGIL = (255, 70, 200)
    mats = {
        "flesh": Mat(tones[0], "flat"),
        "flesh_head": Mat(tones[1], "flat"),
        "flesh_dark": Mat(shift(tones[3], mul=0.8), "flat"),
        "iron": Mat(pal("#1a120e", "#2e221a", "#4a3626", "#6a4c32", "#8a6644"), "metal", base=0.5, contrast=0.9),
        "leather": Mat(pal("#1a100a", "#2e1c12", "#4a2e1c", "#664028"), "leather", base=0.55),
        "sigil": Mat(pal("#3a0628", "#7a1060", "#c4309e", "#ff70dc", "#ffd0f4"), "flat", glow=True),
    }

    def decor(ctx):
        t = ctx.tag
        if ctx.mat in ("flesh", "flesh_head", "flesh_dark"):
            ps = tones if ctx.mat != "flesh_dark" else [shift(p, mul=0.8) for p in tones]
            stitched_flesh(ctx, ps, ctx.seed % 211, cell=6.0 if ctx.mat == "flesh_head" else 8.0)
            # bruises and veins
            rnd = random.Random(ctx.seed)
            for face in ("front", "right", "left"):
                fw, fh = ctx.size(face)
                if fw * fh > 60 and rnd.random() < 0.35:
                    cx, cy = rnd.randrange(fw), rnd.randrange(fh)
                    for y in range(fh):
                        for x in range(fw):
                            if (x - cx) ** 2 + (y - cy) ** 2 < 3:
                                ctx.tint(face, x, y, 0.8, add=(-6, -10, 10))
        if t == "head":
            fw, fh = ctx.size("front")
            # stitched-shut sack: small burning eyes, a sewn mouth
            ctx.put("front", 1, 2, (255, 230, 120), glow=True)
            ctx.put("front", fw - 2, 2, (255, 230, 120), glow=True)
            ctx.put("front", 1, 1, (40, 14, 14))
            ctx.put("front", fw - 2, 1, (40, 14, 14))
            for x in range(1, fw - 1):
                ctx.put("front", x, fh - 2, (60, 18, 20))
                if x % 2:
                    ctx.put("front", x, fh - 3, (200, 184, 150))
                    ctx.put("front", x, fh - 1, (200, 184, 150))
        if t == "sigil":
            fw, fh = ctx.size("front")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("front", x, y, (60, 8, 46), glow=False)
            # a daedric sigil: a ring with a cross-stroke
            for (x, y) in ((1, 0), (0, 1), (2, 1), (0, 2), (2, 2), (1, 3), (1, 1), (1, 2)):
                ctx.put("front", x, y, SIGIL, glow=True)
        if t == "sigil_plate":
            fw, fh = ctx.size("front")
            for x in range(fw):
                ctx.put("front", x, 0, (255, 90, 210), glow=True)
                ctx.put("front", x, fh - 1, (255, 90, 210), glow=True)
            for y in range(fh):
                ctx.put("front", 0, y, (160, 40, 130), glow=True)
                ctx.put("front", fw - 1, y, (160, 40, 130), glow=True)
        if t in ("band", "collar", "cuff", "head_band", "pauldron"):
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(1, fw, 3):
                    ctx.put(face, x, fh // 2, (150, 120, 90))
        if t in ("strap", "apron"):
            fw, fh = ctx.size("front")
            ctx.rect("front", fw // 2 - 1, fh // 3, 2, 2, (120, 100, 70))

    img, glow = paint(lay, mats, decor, seed=281)
    out("flesh_atronach", img, glow)


# ================================================================================================ Apocrypha


@painter("seeker")
def seeker():
    lay = load("seeker")
    robe = pal("#040806", "#0a120e", "#122018", "#1c2e24", "#284032", "#365444")
    flesh = pal("#0e1410", "#1c2820", "#2c3c30", "#3e5242", "#566a56")
    GREEN = (120, 255, 140)
    mats = {
        "robe": Mat(robe, "cloth", base=0.55, alpha=236),
        "robe_dark": Mat(shift(robe, mul=0.75), "cloth", base=0.5, alpha=236),
        "robe_rag": Mat(shift(robe, mul=0.85), "cloth", base=0.5, alpha=205),
        "robe_trim": Mat(pal("#141a0c", "#2a3418", "#465428", "#66763a", "#8c9a52"), "metal", base=0.5, scale=0.7),
        "seeker_face": Mat(pal("#020302", "#060806"), "flat"),
        "seeker_flesh": Mat(flesh, "skin", base=0.55, spots=(0.12, 1.2, 0.2)),
        "seeker_claw": Mat(pal("#3a4232", "#5e6a52", "#8a9678", "#b8c4a2"), "horn", base=0.6, grad=0.4),
    }

    def decor(ctx):
        t = ctx.tag
        rnd = random.Random(ctx.seed)
        if t == "face":
            fw, fh = ctx.size("front")
            tentacle_face(ctx, "front", flesh, ((1, 1), (3, 1), (2, 2)), ctx.seed)
        if t == "hood":
            fw, fh = ctx.size("front")
            # the hood's opening is a black hollow
            for y in range(1, fh):
                for x in range(1, fw - 1):
                    ctx.put("front", x, y, (4, 6, 5))
            put_glow(ctx, "front", fw // 2, fh // 2, (30, 80, 40))
        if t in ("panel", "belt"):
            glyphs(ctx, ("front", "back"), GREEN, (50, 140, 70), rnd, chance=1.0)
            for face in ("front",):
                fw, fh = ctx.size(face)
                for y in range(2, fh, 3):
                    ctx.put(face, fw // 2, y, GREEN, glow=True)
        if t in ("hem", "rag", "cape", "bell_sleeve"):
            ragged(ctx, depth=2 if t != "cape" else 3, seed=ctx.seed % 17)
        if t in ("mantle", "chest", "robe"):
            glyphs(ctx, ("front", "back", "right", "left"), (70, 170, 90), (30, 90, 45), rnd, chance=0.35)
        if t in ("segment", "tip", "tentacle_arm", "hand"):
            # sucker dots along one side
            for face in ("front",):
                fw, fh = ctx.size(face)
                for y in range(0, fh, 2):
                    ctx.put(face, fw // 2, y, (120, 150, 110))

    img, glow = paint(lay, mats, decor, seed=291)
    body_glow(img, glow, k=0.08)
    out("seeker", img, glow)


@painter("lurker")
def lurker():
    lay = load("lurker")
    hide = pal("#040806", "#0a140e", "#122218", "#1c3424", "#2a4a32", "#3c6244")
    ICHOR = (110, 255, 120)
    mats = {
        "lurker_hide": Mat(hide, "skin", base=0.55, dorsal=0.1, counter=0.18, spots=(0.1, 1.5, 0.15)),
        "lurker_head": Mat(hide, "skin", base=0.58, spots=(0.1, 1.2, 0.15)),
        "lurker_dark": Mat(shift(hide, mul=0.7), "skin", base=0.5),
        "lurker_belly": Mat(pal("#2a3428", "#46543e", "#64745a", "#84967a", "#a8b89a"), "chitin", base=0.55, scale=0.6),
        "lurker_spine": Mat(pal("#1a2018", "#3a4432", "#62705a", "#909e84", "#bccab0"), "horn", base=0.6, grad=0.4),
        "lurker_tentacle": Mat(pal("#0c1410", "#1a2a20", "#2c4232", "#426048", "#5e7e62"), "skin", base=0.55),
        "lurker_claw": Mat(pal("#050605", "#121412", "#24281f", "#3a4032"), "horn", base=0.5),
    }

    def decor(ctx):
        t = ctx.tag
        rnd = random.Random(ctx.seed)
        if ctx.mat in ("lurker_hide", "lurker_head", "lurker_dark"):
            # weeping ichor: glowing green spots and drips
            for face in SIDES + ("top",):
                fw, fh = ctx.size(face)
                n = (fw * fh) // 45
                for _ in range(n):
                    x, y = rnd.randrange(fw), rnd.randrange(fh)
                    ctx.put(face, x, y, ICHOR, glow=True)
                    if face in SIDES:
                        for dy in range(1, 1 + rnd.randrange(3)):
                            ctx.put(face, x, y + dy, (60, 170, 70), glow=True)
        if t == "head":
            fw, fh = ctx.size("front")
            # three small burning eyes a side under the crest
            for k in range(3):
                for x in (1 + k, fw - 2 - k):
                    ctx.put("front", x, 1 + (k % 2), (170, 255, 120), glow=True)
            ctx.hline("front", fh - 1, (8, 12, 8))
        if t == "jaw":
            fw, fh = ctx.size("top")
            for y in range(fh):
                for x in range(fw):
                    ctx.put("top", x, y, (40, 14, 20))
            for x in range(0, fw, 2):
                ctx.put("top", x, 0, (200, 210, 170))
        if t == "belly":
            bands(ctx, period=2, mul=0.78, faces=("front",))
        if t in ("spine", "crest_fin", "horn"):
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.put(face, x, 0, (150, 255, 150), glow=True)
        if t in ("segment", "tip"):
            for face in ("front",):
                fw, fh = ctx.size(face)
                for y in range(0, fh, 2):
                    ctx.put(face, fw // 2, y, (120, 150, 110))

    img, glow = paint(lay, mats, decor, seed=301)
    out("lurker", img, glow)


# ================================================================================================ the Soul Cairn

SOUL = (200, 120, 255)
SOUL_DIM = (110, 50, 160)
SKULL_BONE = pal("#3e3646", "#6a6074", "#968aa0", "#c2b8c8", "#e4dee8")


def skull_face(ctx, face="front", eye=SOUL, teeth=True):
    fw, fh = ctx.size(face)
    ex = max(1, fw // 2 - 2)
    for x in (ex, fw - 1 - ex - 1):
        ctx.rect(face, x, fh // 3, 2, 2, (14, 8, 18))
        ctx.put(face, x + (1 if x == ex else 0), fh // 3 + 1, eye, glow=True)
        put_glow(ctx, face, x + (0 if x == ex else 1), fh // 3 + 1, tuple(c * 0.4 for c in eye))
    ctx.put(face, fw // 2, fh // 3 + 2, (20, 12, 24))
    if fw % 2 == 0:
        ctx.put(face, fw // 2 - 1, fh // 3 + 2, (20, 12, 24))
    if teeth:
        for x in range(1, fw - 1):
            ctx.put(face, x, fh - 1, (230, 222, 236) if x % 2 else (40, 30, 44))


@painter("keeper")
def keeper():
    lay = load("keeper")
    PLATE = pal("#0a0810", "#16121e", "#241e30", "#363046", "#4e4662", "#6c6284")
    mats = {
        "keeper_plate": Mat(PLATE, "flat"),
        "keeper_bone": Mat(SKULL_BONE, "bone", base=0.58),
        "keeper_skull": Mat(SKULL_BONE, "bone", base=0.6),
        "keeper_spike": Mat(pal("#0a0810", "#1e1828", "#3a3248", "#605670", "#8a80a0"), "horn", base=0.55, grad=0.4),
        "keeper_cloth": Mat(pal("#0e0614", "#1c0c28", "#2c143c", "#3e1e52", "#542a6a"), "cloth", base=0.5),
        "keeper_haft": Mat(pal("#0a080c", "#18141c", "#2a2430", "#3e3646"), "leather", base=0.5),
        "keeper_blade": Mat(pal("#2a2834", "#4e4a5e", "#7a7690", "#aaa6c0", "#d8d4ec"), "metal", base=0.55),
        "soul": Mat(pal("#3a0c6a", "#6a24b0", "#a060f0", "#d4a8ff", "#f6eaff"), "crystal", base=0.6, glow=True),
    }

    def decor(ctx):
        t = ctx.tag
        rnd = random.Random(ctx.seed)
        if ctx.mat == "keeper_plate":
            plate(ctx, PLATE, rivets=ctx.is_("breastplate", "pauldron", "fauld"))
            if t in ("breastplate", "pauldron", "greave", "tasset", "helm", "fauld", "vambrace"):
                glyphs(ctx, ("front", "right", "left"), SOUL, SOUL_DIM, rnd, chance=0.45)
                edge_light(ctx, mul=1.5)
        if t == "head":
            skull_face(ctx)
        if t == "jaw":
            fw, fh = ctx.size("front")
            for x in range(1, fw - 1):
                ctx.put("front", x, 0, (230, 222, 236) if x % 2 else (40, 30, 44))
        if t in ("cape", "tabard"):
            ragged(ctx, depth=3, seed=ctx.seed % 23)
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.put(face, x, 0, (150, 120, 60))
        if t in ("axe_blade", "axe_horn", "axe_beard", "spike"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    ctx.put(face, 0, y, (210, 160, 255), glow=True)
                for x in range(fw):
                    ctx.put(face, x, 0, (210, 160, 255), glow=True)
        if ctx.mat == "keeper_bone" and t in ("arm", "forearm", "leg", "shin"):
            # bare bones between the plates: knuckle bumps
            bands(ctx, period=4, mul=0.8)

    img, glow = paint(lay, mats, decor, seed=311)
    out("keeper", img, glow)


@painter("boneman")
def boneman():
    lay = load("boneman")
    BONE = pal("#3a3242", "#665c70", "#92889c", "#beb4c6", "#e2dce8")
    RUST = pal("#1a1010", "#34201e", "#523430", "#704a42", "#8a6052")
    mats = {
        "bone": Mat(BONE, "bone", base=0.6),
        "skull": Mat(BONE, "bone", base=0.62),
        "ribs": Mat(BONE, "bone", base=0.6),
        "rust_metal": Mat(RUST, "metal", base=0.5, contrast=0.9),
        "rust_shield": Mat(pal("#1e1410", "#34241a", "#4e3828", "#664a34"), "bark", base=0.5, scale=0.8),
        "rag": Mat(pal("#120e16", "#221a28", "#342a3c", "#463a50"), "cloth", base=0.5),
        "blade": Mat(pal("#2a2428", "#4a4048", "#6e646c", "#948a90"), "metal", base=0.55),
        "leather": Mat(pal("#1a120c", "#2e2016", "#463222"), "leather", base=0.55),
        "bow_wood": Mat(pal("#3a3036", "#5c5058", "#82747c", "#a89aa0"), "bone", base=0.55),
        "string": Mat(pal("#8a8288", "#b0a8ae", "#d4ccd2"), "plain", base=0.6),
        "feather": Mat(pal("#1a141c", "#2e2432", "#463a4c", "#604e66"), "feather", base=0.5),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "ribcage":
            # rib bars with open gaps (cutout), a sternum down the front
            for face in SIDES:
                fw, fh = ctx.size(face)
                for y in range(fh):
                    if y % 2 == 1:
                        for x in range(fw):
                            if face == "front" and abs(x - (fw - 1) / 2.0) < 1:
                                continue
                            if face == "back" and abs(x - (fw - 1) / 2.0) < 1:
                                continue
                            set_alpha(ctx, face, x, y, 0)
            for face in ("top", "bottom"):
                fw, fh = ctx.size(face)
                for y in range(1, fh - 1):
                    for x in range(1, fw - 1):
                        set_alpha(ctx, face, x, y, 0)
        if t == "head":
            skull_face(ctx, teeth=False)
        if t == "jaw":
            fw, fh = ctx.size("front")
            for x in range(fw):
                ctx.put("front", x, 0, (230, 222, 236) if x % 2 else (40, 30, 44))
        if t == "rag":
            ragged(ctx, depth=3, seed=ctx.seed % 19)
        if t == "shield":
            for face in ("right", "left"):
                round_cut(ctx, face)
                fw, fh = ctx.size(face)
                cx, cy = (fw - 1) / 2.0, (fh - 1) / 2.0
                for y in range(fh):
                    for x in range(fw):
                        if abs(math.hypot(x - cx, y - cy) - (min(fw, fh) / 2.0 - 1)) < 0.6:
                            c = ctx.get(face, x, y)
                            if c and c[3]:
                                ctx.put(face, x, y, (110, 74, 62))
            for face in ("front", "back", "top", "bottom"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if (face in ("front", "back") and (y < 2 or y >= fh - 2)) or (face in ("top", "bottom") and (x < 2 or x >= fw - 2)):
                            set_alpha(ctx, face, x, y, 0)
        if t in ("helm",):
            dots(ctx, SIDES, 0.06, (40, 26, 22), ctx.seed)

    img, glow = paint(lay, mats, decor, seed=321)
    out("boneman", img, glow)


@painter("mistman")
def mistman():
    lay = load("mistman")
    MIST = pal("#3a4a5e", "#5e7288", "#869ab0", "#b0c2d4", "#dae6f0")
    ICE = (170, 230, 255)
    mats = {
        "mist": Mat(MIST, "ethereal", base=0.6, alpha=190),
        "mist_dark": Mat(shift(MIST, mul=0.75), "ethereal", base=0.55, alpha=200),
        "mist_wisp": Mat(shift(MIST, mul=1.05), "ethereal", base=0.65, alpha=120),
        "mist_claw": Mat(pal("#5a7a96", "#88aac4", "#b4d4ea", "#e2f4ff"), "crystal", base=0.6, alpha=235),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("front")
            for x in (1, fw - 2):
                ctx.put("front", x, 2, ICE, glow=True)
                ctx.put("front", x, 1, (20, 30, 44), alpha=230)
            ctx.rect("front", 1, fh - 2, fw - 2, 1, (14, 20, 30))
        if t == "jaw":
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, (14, 20, 30))
        if t == "streamer":
            fade(ctx, ("front", "back", "right", "left"), 200, 20)
        if t in ("segment", "tip"):
            fade(ctx, SIDES, 190, 120)
        if t == "tip":
            fade(ctx, SIDES, 160, 30)
        if t == "torso":
            fw, fh = ctx.size("front")
            for y in range(2, fh - 1, 2):
                for x in range(1, fw - 1):
                    ctx.tint("front", x, y, 0.85)
            put_glow(ctx, "front", fw // 2, 3, (60, 110, 140))

    img, glow = paint(lay, mats, decor, seed=331)
    body_glow(img, glow, k=0.22)
    out("mistman", img, glow)


@painter("wrathman")
def wrathman():
    lay = load("wrathman")
    PLATE = pal("#08060a", "#141018", "#221c28", "#342a3c", "#4a3e56", "#625472")
    WRATH = (255, 50, 110)
    WRATH_DIM = (150, 20, 70)
    mats = {
        "wrath_plate": Mat(PLATE, "flat"),
        "wrath_helm": Mat(PLATE, "flat"),
        "wrath_horn": Mat(pal("#060406", "#140e14", "#281c26", "#40303c"), "horn", base=0.55, grad=0.4, alpha=230),
        "wrath_cloth": Mat(pal("#14040a", "#280812", "#3e0e1c", "#561628"), "cloth", base=0.5, alpha=215),
        "wrath_grip": Mat(pal("#120c0a", "#24180e", "#3a2a1a"), "leather", base=0.5),
        "wrath_blade": Mat(PLATE, "flat"),
    }

    def decor(ctx):
        t = ctx.tag
        rnd = random.Random(ctx.seed)
        if ctx.mat in ("wrath_plate", "wrath_helm", "wrath_blade"):
            plate(ctx, PLATE, alpha=225, rivets=False)
            edge_light(ctx, mul=1.5)
            for face in SIDES:
                fw, fh = ctx.size(face)
                if fw * fh >= 24 and rnd.random() < 0.7:
                    crack_walk(ctx, face, rnd, fw + fh, WRATH, halo=(90, 20, 50), halo_glow=(70, 10, 40))
        if t == "head":
            fw, fh = ctx.size("front")
            # a T-shaped visor burning with wrath
            for x in range(1, fw - 1):
                ctx.put("front", x, 2, WRATH, glow=True)
            for y in range(3, fh - 1):
                ctx.put("front", fw // 2, y, WRATH_DIM, glow=True)
                if fw % 2 == 0:
                    ctx.put("front", fw // 2 - 1, y, WRATH_DIM, glow=True)
        if t == "blade":
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(1, fw - 1):
                    ctx.put(face, x, fh // 2, WRATH, glow=True)
                    ctx.put(face, x, fh // 2 - 1, WRATH_DIM, glow=True)
        if t in ("cape", "tabard"):
            ragged(ctx, depth=3, seed=ctx.seed % 29)
        if t in ("leg", "shin", "foot"):
            # the spirit fades towards its feet
            fade(ctx, SIDES, 230, 120)

    img, glow = paint(lay, mats, decor, seed=341)
    body_glow(img, glow, k=0.12, tint=(1.3, 0.6, 1.0))
    out("wrathman", img, glow)


@painter("wandering_soul")
def wandering_soul():
    lay = load("wandering_soul")
    SOULP = pal("#3e3a6a", "#5e5a94", "#8682bc", "#b2aedc", "#dcdaf6")
    mats = {
        "soul": Mat(SOULP, "ethereal", base=0.62, alpha=150),
        "soul_shroud": Mat(shift(SOULP, mul=0.9), "ethereal", base=0.6, alpha=110),
        "soul_tail": Mat(SOULP, "ethereal", base=0.62, alpha=120),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("front")
            ctx.put("front", 1, 2, (220, 230, 255), glow=True)
            ctx.put("front", fw - 2, 2, (220, 230, 255), glow=True)
            ctx.put("front", fw // 2, fh - 1, (40, 40, 80), alpha=120)
        if t == "segment":
            fade(ctx, SIDES, 130, 80)
        if t == "tip":
            fade(ctx, SIDES, 80, 10)
        if t in ("hair", "sleeve", "shroud"):
            ragged(ctx, depth=2, seed=ctx.seed % 7)

    img, glow = paint(lay, mats, decor, seed=351)
    body_glow(img, glow, k=0.45)
    out("wandering_soul", img, glow)


# ================================================================================================ Sovngarde


def nord_mats(p, hair, cloth, alpha, ghost):
    """Materials of a Nord in armour ({@code p}_* names), ghostly (pale blue, translucent) or living."""
    if ghost:
        steel = pal("#4a5e78", "#6e86a4", "#98b0cc", "#c2d6ec", "#eaf4ff")
        gold = pal("#7a6430", "#b0944a", "#dcc070", "#f4e09e", "#fff6d2")
        skin = pal("#6a7e98", "#8ea4be", "#b2c6dc", "#d2e2f2", "#eef6ff")
        leather = pal("#3a4a62", "#56688a", "#7488aa", "#94a8c8")
        fur = pal("#7a8ca4", "#a0b2c8", "#c4d4e6", "#e6f0fa")
        wood = pal("#4a5466", "#687488", "#8894aa", "#a8b4c8")
    else:
        steel = pal("#2a2c30", "#4a4e56", "#767c86", "#a4abb4", "#d2d8de", "#f4f8fc")
        gold = pal("#3a2808", "#6e4c14", "#a6782a", "#d4a848", "#f2d486", "#fff2c4")
        skin = pal("#4a2e22", "#7a5040", "#a6765e", "#c89a80", "#e0bca2")
        leather = pal("#1e140c", "#382618", "#563c26", "#725236")
        fur = pal("#1e140c", "#3a2818", "#5a4028", "#7a5a3c", "#9a7a56")
        wood = pal("#2a1c10", "#46301c", "#644628", "#7e5c38")
    return {
        p + "_mail": Mat(steel, "flat", alpha=alpha),
        p + "_plate": Mat(steel, "flat", alpha=alpha),
        p + "_steel": Mat(steel, "metal", base=0.6, alpha=alpha),
        p + "_gold": Mat(gold, "metal", base=0.6, alpha=alpha, glow=ghost, glow_strength=0.45),
        p + "_face": Mat(skin, "skin", base=0.6, contrast=0.4, alpha=alpha),
        p + "_skin": Mat(skin, "skin", base=0.6, contrast=0.4, alpha=alpha),
        p + "_hair": Mat(hair, "hair", base=0.55, contrast=0.6, alpha=alpha),
        p + "_leather": Mat(leather, "leather", base=0.55, alpha=alpha),
        p + "_cloth": Mat(cloth, "cloth", base=0.55, alpha=alpha),
        p + "_fur": Mat(fur, "fur", base=0.55, alpha=alpha),
        p + "_wood": Mat(wood, "bark", base=0.55, scale=0.7, alpha=alpha),
        p + "_horn": Mat(pal("#5a5240", "#8c826a", "#bab096", "#e0d8c2", "#f6f0e2") if not ghost else
                         pal("#6a7a8e", "#92a2b6", "#b8c8da", "#dce8f4"), "horn", base=0.6, grad=0.3, alpha=alpha),
        p + "_blade": Mat(steel, "metal", base=0.62, alpha=alpha),
        p + "_shield": Mat(cloth, "bark", base=0.5, alpha=alpha),
        p + "_feather": Mat(pal("#3a3a40", "#5e5e66", "#8a8a92", "#b4b4bc"), "feather", base=0.55, alpha=alpha),
        "string": Mat(pal("#8a8a80", "#b4b4a8", "#dcdcd0"), "plain", base=0.6, alpha=alpha),
    }, steel, gold


def nord_decor(p, gold, steel, eye, alpha, beard=True):
    def decor(ctx):
        t = ctx.tag
        if ctx.mat == p + "_mail":
            chainmail(ctx, steel, alpha=alpha)
        if ctx.mat == p + "_plate":
            plate(ctx, steel, alpha=alpha, trim=gold, rivets=ctx.is_("breastplate", "pauldron", "helm"))
        if t == "head":
            fw, fh = ctx.size("front")
            # brow, eyes, nose and mouth
            for x in range(fw):
                ctx.tint("front", x, 1, 0.85)
            ex = max(1, fw // 2 - 2)
            for x in (ex, fw - 1 - ex):
                ctx.put("front", x, 2, (250, 250, 250), alpha=alpha)
                ctx.put("front", x + (1 if x == ex else -1), 2, eye, glow=True, alpha=alpha)
            ctx.tint("front", fw // 2, 3, 0.85)
            if fw % 2 == 0:
                ctx.tint("front", fw // 2 - 1, 3, 0.85)
            ctx.hline("front", fh - 2, lerp(ctx.get("front", 0, fh - 2)[:3], (60, 30, 30), 0.5), 2, fw - 2)
        if t in ("helm", "helm_band", "circlet"):
            edge_light(ctx, mul=1.3)
        if t == "shield":
            for face in ("right", "left"):
                round_cut(ctx, face)
                fw, fh = ctx.size(face)
                cx, cy = (fw - 1) / 2.0, (fh - 1) / 2.0
                r = min(fw, fh) / 2.0
                for y in range(fh):
                    for x in range(fw):
                        d = math.hypot(x - cx, y - cy)
                        c = ctx.get(face, x, y)
                        if not c or not c[3]:
                            continue
                        if d > r - 1.3:
                            ctx.put(face, x, y, ramp(gold, 0.6), alpha=alpha)
                        elif abs(x - cx) < 0.6 or abs(y - cy) < 0.6:
                            ctx.put(face, x, y, ramp(gold, 0.45), alpha=alpha)
            for face in ("front", "back", "top", "bottom"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if (face in ("front", "back") and (y < 2 or y >= fh - 2)) or (face in ("top", "bottom") and (x < 2 or x >= fw - 2)):
                            set_alpha(ctx, face, x, y, 0)
        if t in ("kilt",):
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(0, fw, 3):
                    for y in range(fh):
                        ctx.tint(face, x, y, 0.82)
                for x in range(fw):
                    ctx.put(face, x, fh - 1, ramp(gold, 0.5), alpha=alpha)
        if t in ("beard", "beard_tip", "braid", "moustache"):
            for face in SIDES:
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if (x + y) % 3 == 0:
                            ctx.tint(face, x, y, 0.85)
        if t in ("cape", "mantle") and ctx.mat == p + "_fur":
            ragged(ctx, faces=("front", "back", "right", "left") if t == "cape" else (), depth=2, seed=ctx.seed % 11)
        if t in ("blade", "axe_blade", "axe_head"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.tint(face, x, 0, 1.3)
    return decor


@painter("sovngarde_hero")
def sovngarde_hero():
    lay = load("sovngarde_hero")
    variants = [
        ("sovngarde_hero", pal("#7a6a3a", "#a89058", "#d2ba7e", "#ecdaa8", "#fbf0d2"), pal("#2e4670", "#46648e", "#6484ac", "#88a6c8")),
        ("sovngarde_hero_shieldmaiden", pal("#8a7a52", "#b8a676", "#ded0a0", "#f4eacc", "#fffaea"), pal("#245a62", "#367a82", "#529aa0", "#78bcc0")),
        ("sovngarde_hero_archer", pal("#6a4a2e", "#946a44", "#be9060", "#dcb486", "#f0d4ac"), pal("#2e5a46", "#427a5e", "#5e9a78", "#80ba98")),
    ]
    for i, (name, hair, cloth) in enumerate(variants):
        alpha = 205
        mats, steel, gold = nord_mats("hero", hair, cloth, alpha, True)
        decor = nord_decor("hero", gold, steel, (150, 220, 255), alpha)
        img, glow = paint(lay, mats, decor, seed=361 + i)
        body_glow(img, glow, k=0.28, tint=(0.85, 0.95, 1.15))
        out(name, img, glow)


@painter("tsun")
def tsun():
    lay = load("tsun")
    hair = pal("#4a3e2e", "#7a6a50", "#a89676", "#cec0a2", "#ece2cc")
    cloth = pal("#0e1a3a", "#1a2c5a", "#2a4280", "#3e5ca2")
    mats, steel, gold = nord_mats("tsun", hair, cloth, 255, False)
    mats["tsun_gold"] = Mat(gold, "metal", base=0.6, glow=True, glow_strength=0.3)
    base = nord_decor("tsun", gold, steel, (120, 190, 255), 255)

    def decor(ctx):
        base(ctx)
        t = ctx.tag
        if t == "head":
            fw, fh = ctx.size("front")
            # weathered cheeks and a scar
            ctx.tint("front", 1, 3, 0.9, add=(10, -4, -6))
            ctx.tint("front", fw - 2, 3, 0.9, add=(10, -4, -6))
            ctx.put("front", fw - 2, 1, (150, 90, 80))
            ctx.put("front", fw - 2, 2, (150, 90, 80))
        if t == "haft":
            for face in SIDES:
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if (y % 3) == 0 and fh < 10:
                            ctx.tint(face, x, y, 0.75)
        if t == "axe_blade":
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                # etched knotwork line and a bright honed edge
                for x in range(1, fw - 1):
                    ctx.put(face, x, fh // 2, ramp(gold, 0.55))
                for y in range(fh):
                    ctx.tint(face, 0, y, 1.35)

    img, glow = paint(lay, mats, decor, seed=371)
    out("tsun", img, glow)


# ================================================================================================ Coldharbour and Miraak


@painter("harvester")
def harvester():
    lay = load("harvester")
    SKIN = pal("#3a3e4a", "#5c6272", "#828a9c", "#a8b0c0", "#ccd2de")
    CARA = pal("#04050a", "#0c0e18", "#161a2a", "#22283e", "#323a56", "#46507a")
    RED = (255, 50, 40)
    mats = {
        "harv_skin": Mat(SKIN, "skin", base=0.58, contrast=0.5),
        "harv_carapace": Mat(CARA, "chitin", base=0.55, scale=0.7),
        "harv_scales": Mat(pal("#0a0c12", "#161a24", "#242a38", "#343c50", "#485268"), "scales", base=0.55, dorsal=0.12, counter=0.2),
        "harv_belly": Mat(pal("#3a3a42", "#5a5a64", "#7c7c88", "#a0a0ac"), "chitin", base=0.55, scale=0.6),
        "harv_hair": Mat(pal("#030305", "#0a0a10", "#14141c", "#202030"), "hair", base=0.5, contrast=0.7),
        "harv_spike": Mat(pal("#050508", "#121218", "#24242e", "#3a3a48", "#5a5a6a"), "horn", base=0.55, grad=0.4),
    }

    def decor(ctx):
        t = ctx.tag
        rnd = random.Random(ctx.seed)
        if t == "head":
            fw, fh = ctx.size("front")
            ctx.put("front", 1, 2, RED, glow=True)
            ctx.put("front", fw - 2, 2, RED, glow=True)
            ctx.tint("front", 1, 1, 0.5)
            ctx.tint("front", fw - 2, 1, 0.5)
            for y in range(3, fh):
                ctx.put("front", 0, y, (30, 30, 46))
                ctx.put("front", fw - 1, y, (30, 30, 46))
            ctx.hline("front", fh - 1, (60, 16, 24), 1, fw - 1)
        if ctx.mat == "harv_carapace":
            for face in SIDES:
                fw, fh = ctx.size(face)
                if fw * fh >= 12 and rnd.random() < 0.6:
                    crack_walk(ctx, face, rnd, fw + fh, (220, 40, 40), halo=(70, 14, 20), halo_glow=None)
        if t in ("torso",):
            fw, fh = ctx.size("front")
            for y in range(fh):
                ctx.tint("front", fw // 2, y, 0.85)
        if t == "belly_plates":
            bands(ctx, period=2, mul=0.78, faces=("front",))
        if t in ("claw", "horn_tip", "spike"):
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.tint(face, x, fh - 1, 1.6)

    img, glow = paint(lay, mats, decor, seed=381)
    out("harvester", img, glow)


@painter("miraak")
def miraak():
    lay = load("miraak")
    ROBE = pal("#0c0e08", "#1a1e10", "#2a301a", "#3c4426", "#525c34")
    GOLD = pal("#2a1c06", "#5a3e10", "#8e6a1e", "#c09a34", "#e2c462", "#fae8a2")
    mats = {
        "mir_robe": Mat(ROBE, "cloth", base=0.55),
        "mir_hood": Mat(shift(ROBE, mul=0.8), "cloth", base=0.5),
        "mir_dark": Mat(pal("#060504", "#100e0a", "#1c1812", "#2a241c"), "leather", base=0.5),
        "mir_glove": Mat(pal("#0e0a06", "#1e160e", "#302418", "#443424"), "leather", base=0.55),
        "mir_scale": Mat(GOLD, "scales", base=0.5, scale=0.8, contrast=0.9),
        "mir_trim": Mat(GOLD, "metal", base=0.5, scale=0.7),
        "mir_mask": Mat(GOLD, "metal", base=0.62, scale=0.6),
        "mir_blade": Mat(pal("#0a120c", "#16241a", "#28402c", "#3e5c40", "#5e7e58"), "metal", base=0.55),
    }

    def decor(ctx):
        t = ctx.tag
        if t == "mask":
            fw, fh = ctx.size("front")
            # sunken eye slits with a faint sickly glow, tentacle relief writhing down the face
            for x in (1, fw - 2):
                ctx.put("front", x, 2, (24, 18, 6))
                ctx.put("front", x, 1, (24, 18, 6))
                put_glow(ctx, "front", x, 2, (150, 170, 60))
            for x in range(fw):
                for y in range(3, fh):
                    if (x + y // 2) % 2 == 0:
                        ctx.tint("front", x, y, 0.8)
            ctx.put("front", fw // 2, 0, (255, 236, 160))
        if t in ("hood", "hood_peak"):
            fw, fh = ctx.size("front")
            for y in range(fh):
                for x in range(1, fw - 1):
                    ctx.put("front", x, y, (8, 8, 6))
        if t in ("robe_panel", "robe_side"):
            ragged(ctx, depth=1, seed=ctx.seed % 13)
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    if ctx.get(face, x, fh - 3) and ctx.get(face, x, fh - 3)[3]:
                        ctx.put(face, x, fh - 3, ramp(GOLD, 0.55))
        if t == "panel":
            fw, fh = ctx.size("front")
            for y in range(1, fh, 2):
                ctx.put("front", fw // 2, y, ramp(GOLD, 0.2))
        if t == "blade":
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(fw):
                    ctx.put(face, x, 0, ramp(GOLD, 0.6))

    img, glow = paint(lay, mats, decor, seed=391)
    out("miraak", img, glow)


# ================================================================================================ spawn eggs and main


def eggs():
    base = os.path.join(os.path.dirname(OUT), "..", "models", "item")
    base = os.path.normpath(base)
    os.makedirs(base, exist_ok=True)
    for i in EGG_IDS:
        p = os.path.join(base, i + "_spawn_egg.json")
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps({"parent": "minecraft:item/template_spawn_egg"}, indent=2) + "\n")


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)
    eggs()


if __name__ == "__main__":
    main(sys.argv[1:])
