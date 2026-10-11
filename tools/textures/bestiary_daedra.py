"""Textures of the daedra, Dwemer automatons and dragon priests (rig group DaedraDefs), painted from the rig layouts.

Run tools/textures/dump_rigs.sh DaedraDefs first when a rig changed, then (from tools/textures):
    py -3 bestiary_daedra.py [name ...]
Writes textures/entity/creatures/<name>[_<variant>].png plus the _glow.png emissive layers.
"""
import math
import random
import sys

from rigpaint import FACE_LIGHT, Mat, clamp, hash2, has_pixels, lerp, load, paint, pal, ramp, save, shift, vnoise

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


SIDES = ("right", "front", "left", "back")


# ================================================================================================ helpers


def face_px(ctx, face):
    """Yields (xx, yy, fw, fh, X, Y) for every texel of one face of the context's box."""
    x0, y0, fw, fh = ctx.f[face]
    for yy in range(fh):
        for xx in range(fw):
            X, Y = x0 + xx, y0 + yy
            if X < ctx.img.width and Y < ctx.img.height:
                yield xx, yy, fw, fh, X, Y


def setpx(ctx, X, Y, c, glow=None, alpha=255):
    ctx.px[X, Y] = (clamp(c[0]), clamp(c[1]), clamp(c[2]), alpha)
    if glow is not None:
        ctx.gx[X, Y] = (clamp(glow[0]), clamp(glow[1]), clamp(glow[2]), 255)


def base_t(face, yy, fh, grad=0.2):
    t = 0.55 + FACE_LIGHT[face] * 1.5
    if face in SIDES and fh > 1:
        t += grad * (0.5 - yy / (fh - 1))
    return t


def voronoi(x, y, seed, s):
    """(distance to nearest cell point, edge distance, cell id hash) of a jittered grid of cell size s."""
    cx, cy = x / s, y / s
    best, second, bid = 9.0, 9.0, 0.0
    for gx in range(int(math.floor(cx)) - 1, int(math.floor(cx)) + 2):
        for gy in range(int(math.floor(cy)) - 1, int(math.floor(cy)) + 2):
            px = gx + hash2(gx, gy, seed)
            py = gy + hash2(gy, gx, seed + 1)
            dd = (px - cx) ** 2 + (py - cy) ** 2
            if dd < best:
                second, best, bid = best, dd, hash2(gx, gy, seed + 7)
            elif dd < second:
                second = dd
    return math.sqrt(best), math.sqrt(second) - math.sqrt(best), bid


def crack_walk(ctx, face, rnd, length, color, halo=None, glow=True, halo_glow=None):
    """A wandering crack (one-pixel polyline) across a face; returns visited points."""
    _, _, fw, fh = ctx.f[face]
    if fw < 3 or fh < 3:
        return []
    if rnd.random() < 0.5:
        x, y, dx, dy = rnd.randrange(fw), 0, 0, 1
    else:
        x, y, dx, dy = 0, rnd.randrange(fh), 1, 0
    pts = []
    for _ in range(length):
        if not (0 <= x < fw and 0 <= y < fh):
            break
        pts.append((x, y))
        r = rnd.random()
        if r < 0.45:
            x += dy and rnd.choice((-1, 1))
            y += dx and rnd.choice((-1, 1))
        x += dx
        y += dy
    if halo:
        for (x, y) in pts:
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + ox, y + oy) not in pts:
                    c = ctx.get(face, x + ox, y + oy)
                    if c:
                        m = lerp(c[:3], halo, 0.45)
                        ctx.put(face, x + ox, y + oy, m)
                        if halo_glow:
                            x0, y0, _, _ = ctx.f[face]
                            X, Y = x0 + x + ox, y0 + y + oy
                            if 0 <= x + ox < fw and 0 <= y + oy < fh:
                                ctx.gx[X, Y] = (clamp(halo_glow[0]), clamp(halo_glow[1]), clamp(halo_glow[2]), 255)
    for (x, y) in pts:
        ctx.put(face, x, y, color, glow)
    return pts


# ================================================================================================ dwemer metal

BRONZE = pal("#2a1a0c", "#553517", "#875a28", "#b8853f", "#dcb064", "#f4d89a")
BRONZE_DARK = pal("#1a1109", "#352211", "#563a1d", "#7a552d", "#9c7444")
GOLD_TRIM = pal("#36240a", "#6c4c18", "#a37c2c", "#d4aa48", "#f2d886", "#fff2c4")
COPPER = pal("#24100a", "#4e2412", "#7e3e20", "#ac6236", "#d48e5c")
VERD = pal("#183c34", "#245e50", "#3c8a72", "#62b498", "#98dcc0")
STEEL = pal("#2a2622", "#4a443c", "#746a5c", "#a49884", "#d8ccb4", "#fff6e2")
AMBER = (255, 196, 92)


def dwemer_box(ctx, palette, verd=0.06, rivets=True, engrave=True, brushed=True, light=0.0):
    """Riveted bronze plate: bevelled panel edges, rivets, engraved Dwemer border lines, verdigris in patches and
    seams (heavier low down), faint horizontal brushing."""
    seed = ctx.seed
    for face in ctx.f:
        x0, y0, fw, fh = ctx.f[face]
        if fw <= 0 or fh <= 0:
            continue
        riv = set()
        if rivets and fw >= 5 and fh >= 4:
            for rx in ([1, fw - 2] + (list(range(4, fw - 3, 3)) if fw >= 10 else [])):
                riv.add((rx, 1))
                if fh >= 5:
                    riv.add((rx, fh - 2))
            if fh >= 9:
                for ry in range(4, fh - 3, 3):
                    riv.add((1, ry))
                    riv.add((fw - 2, ry))
        eng = set()
        if engrave and fw >= 7 and fh >= 6:
            ins = 2
            for x in range(ins + 1, fw - ins - 1):
                eng.add((x, ins))
                eng.add((x, fh - ins - 1))
            for y in range(ins + 1, fh - ins - 1):
                eng.add((ins, y))
                eng.add((fw - ins - 1, y))
            if fw >= 10 and fh >= 8:
                cx, cy = (fw - 1) / 2.0, (fh - 1) / 2.0
                r = min(fw, fh) / 2.0 - 3.2
                for y in range(fh):
                    for x in range(fw):
                        if abs(abs(x - cx) + abs(y - cy) - r) < 0.5:
                            eng.add((x, y))
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            t = base_t(face, yy, fh, 0.24) + light
            seam = 0.0
            if fw > 2 and fh > 2:
                if yy == 0 or xx == 0:
                    t += 0.13
                if yy == fh - 1 or xx == fw - 1:
                    t -= 0.2
                    seam = 0.12
            if brushed:
                t += (hash2(0, Y, seed) - 0.5) * 0.07
            if (xx, yy) in eng:
                t -= 0.17
                seam = 0.22
            elif (xx - 1, yy - 1) in eng:
                t += 0.06
            if (xx, yy) in riv:
                t += 0.34
            elif (xx - 1, yy - 1) in riv or (xx, yy - 1) in riv:
                t -= 0.12
                seam = max(seam, 0.12)
            c = ramp(palette, t)
            n = vnoise(X, Y, seed + 5, 2.3) * 0.7 + vnoise(X, Y, seed + 11, 5) * 0.3
            bias = verd + seam
            if face in SIDES and fh > 1:
                bias += 0.16 * (yy / (fh - 1))
            if face == "bottom":
                bias += 0.12
            v = n + bias - 1.02
            if v > 0:
                g = ramp(VERD, 0.35 + min(0.5, v * 2.0) + (t - 0.55) * 0.6)
                c = lerp(c, g, min(1.0, 0.45 + v * 3))
            setpx(ctx, X, Y, c)


def dwemer_eye(ctx, face, x, y, w=1, h=1, color=AMBER):
    for yy in range(h):
        for xx in range(w):
            ctx.put(face, x + xx, y + yy, color, glow=True)


def dwemer_mats():
    """Mats for the Dwemer: everything is overpainted by dwemer_decor except the glowing parts."""
    return {
        "dwemer": Mat(BRONZE, "flat"),
        "dwemer_dark": Mat(BRONZE_DARK, "flat"),
        "trim": Mat(GOLD_TRIM, "flat"),
        "pipe": Mat(COPPER, "flat"),
        "blade": Mat(STEEL, "flat"),
        "bolt": Mat(STEEL, "flat"),
        "string": Mat(pal("#1a140e", "#2e241a", "#463828"), "plain"),
        "core": Mat(pal("#a0500c", "#e08a1c", "#ffbc48", "#ffe49a", "#fff8e0"), "ember", base=0.62, glow=True, contrast=0.6),
        "soulgem": Mat(pal("#4a3a8a", "#7a6ad0", "#b4a8f4", "#e4defc", "#ffffff"), "crystal", base=0.6, glow=True),
        "shock": Mat(pal("#3a6ae0", "#80b0ff", "#d0e6ff", "#ffffff"), "plain", base=0.7, glow=True),
    }


def dwemer_decor_base(ctx):
    m = ctx.mat
    if m == "dwemer":
        dwemer_box(ctx, BRONZE)
    elif m == "dwemer_dark":
        dwemer_box(ctx, BRONZE_DARK, verd=0.12, engrave=False)
    elif m == "trim":
        dwemer_box(ctx, GOLD_TRIM, verd=0.0, rivets=ctx.is_("band", "shield", "chestplate", "hammer_face", "winch"))
    elif m == "pipe":
        dwemer_box(ctx, COPPER, verd=0.16, rivets=False, engrave=False)
        for face in SIDES:
            fw, fh = ctx.size(face)
            for y in range(1, fh, 3):
                ctx.hline(face, y, ramp(GOLD_TRIM, 0.6))
    elif m in ("blade", "bolt"):
        for face in ctx.f:
            for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                t = base_t(face, yy, fh, 0.1) + (hash2(X, 0, ctx.seed) - 0.5) * 0.08
                if face in ("top", "bottom") or yy == 0 or yy == fh - 1:
                    t += 0.25
                setpx(ctx, X, Y, ramp(STEEL, t))
    elif m == "core":
        for face in ctx.f:
            fw, fh = ctx.size(face)
            for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                d = math.hypot(xx - (fw - 1) / 2.0, yy - (fh - 1) / 2.0) / max(1.0, max(fw, fh) / 2.0)
                c = ramp(pal("#9a4808", "#e0861a", "#ffbc48", "#ffe49a", "#fffbe8"), 1.0 - d * 0.8)
                setpx(ctx, X, Y, c, glow=c)
    elif m == "soulgem":
        for face in ctx.f:
            for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                t = 0.45 + (0.35 if (xx + yy) % 3 == 0 else 0) + (0.2 if xx == 0 else 0)
                c = ramp(pal("#4a3a8a", "#7a6ad0", "#b4a8f4", "#e4defc", "#ffffff"), t)
                setpx(ctx, X, Y, c, glow=c)


# ================================================================================================ flame atronach

FIRE = pal("#5c0c04", "#a8260a", "#e45e14", "#ff9a28", "#ffd25a", "#fff6c8")
EMBER = pal("#3c0a04", "#7a1c06", "#c2420c", "#f07a1c", "#ffb840", "#ffe8a0")
OBSIDIAN = pal("#08070a", "#141118", "#221d28", "#342c3c", "#4c4256")


def lava_box(ctx, palette, cell=3.0, bright=0.0, glow_mul=1.0):
    """Molten skin: dark cooling-crust plates separated by bright glowing seams; the glow layer gets all of it."""
    seed = ctx.seed % 997
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            d, edge, cid = voronoi(X, Y, seed, cell)
            t = 0.5 + bright + (cid - 0.5) * 0.14 + FACE_LIGHT[face]
            if face in SIDES and fh > 1:
                t += 0.12 * (0.5 - yy / (fh - 1))
            if edge < 0.16:
                t = 0.92 + bright * 0.5
            elif edge < 0.3:
                t += 0.22
            elif cid > 0.66:
                t -= 0.12  # darker crust plate
            t += d * -0.12
            c = ramp(palette, t)
            g = tuple(v * glow_mul * (0.55 + 0.45 * min(1.0, max(0.0, t))) for v in c)
            setpx(ctx, X, Y, c, glow=g)


def flame_box(ctx, palette, heat, seed_off=0):
    """Fire: vertical tongues brightest at the root, cooling to red at the tips (heat 0..1 picks the band)."""
    seed = ctx.seed + seed_off
    off = hash2(seed, 3, 9) * 6
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            f = yy / max(1, fh - 1) if face in SIDES else 0.5
            tongue = math.sin((X + off) * 1.7) * 0.5 + 0.5
            flick = vnoise(X, Y * 0.6, seed, 1.8)
            t = heat - f * 0.45 + tongue * 0.2 + (flick - 0.5) * 0.35
            if face == "top":
                t += 0.12
            c = ramp(palette, t)
            setpx(ctx, X, Y, c, glow=c)


@painter("flame_atronach")
def flame_atronach():
    lay = load("flame_atronach")
    mats = {
        "ember": Mat(EMBER, "flat"),
        "flame": Mat(FIRE, "flat"),
        "flame_tip": Mat(FIRE, "flat"),
        "obsidian": Mat(OBSIDIAN, "crystal", base=0.42, contrast=0.6),
    }
    heat = {"flame1": 0.75, "flame2": 0.6, "flame3": 0.45, "flame4": 0.3, "hair": 0.7, "hair_plume": 0.55,
            "hair_tip": 0.35}

    def decor(ctx):
        rnd = random.Random(ctx.seed)
        if ctx.mat == "ember":
            lava_box(ctx, EMBER, cell=2.6, bright=0.05 if ctx.is_("chest", "head") else 0.0)
        elif ctx.mat in ("flame", "flame_tip"):
            h = heat.get(ctx.bone, 0.45)
            if ctx.mat == "flame_tip":
                h -= 0.1
            if ctx.is_("tongue", "claw"):
                h = 0.5
            flame_box(ctx, FIRE, h)
        elif ctx.mat == "obsidian":
            for face in ctx.f:
                fw, fh = ctx.size(face)
                # glassy rim highlight on the top edge, glowing magma cracks
                ctx.hline(face, 0, ramp(OBSIDIAN, 0.95)) if face in SIDES and fh > 2 else None
                if fw * fh >= 6:
                    crack_walk(ctx, face, rnd, fw + fh, (255, 150, 40), halo=(150, 40, 10), halo_glow=(120, 30, 6))
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # white-hot eyes, a darker brow and lips
            for x in (0, 1, fw - 2, fw - 1):
                ctx.tint("front", x, 1, 0.55)
            ctx.put("front", 0, 2, (255, 255, 230), glow=True)
            ctx.put("front", 1, 2, (255, 240, 170), glow=True)
            ctx.put("front", fw - 2, 2, (255, 240, 170), glow=True)
            ctx.put("front", fw - 1, 2, (255, 255, 230), glow=True)
            ctx.put("front", 1, 4, (120, 20, 6), glow=False)
            ctx.put("front", 2, 4, (120, 20, 6), glow=False)
            for x in range(fw):
                ctx.tint("front", x, 0, 0.75)
        if ctx.is_("circlet"):
            ctx.put("front", 1, 0, (255, 190, 80), glow=True)
            ctx.put("front", 2, 0, (255, 190, 80), glow=True)

    img, glow = paint(lay, mats, decor, seed=41)
    # obsidian keeps a faint dark presence in the glow (not emissive): clear non-glowing pixels
    out("flame_atronach", img, glow)


# ================================================================================================ frost atronach

ICE = pal("#1e4870", "#3a76a6", "#6aa6d2", "#a6d4f0", "#dcf2fe", "#ffffff")
PACKED = pal("#2c5878", "#5a8cb2", "#92bedc", "#c4e2f4", "#eef8ff")
SNOW = pal("#a8c4d8", "#cfe2ee", "#ecf6fc", "#ffffff")
SHARD = pal("#3c86c0", "#6cb4e4", "#a4daf8", "#d8f2ff", "#ffffff")


def ice_box(ctx, palette, seed, facet=True):
    """Clear ice: angular facets with bright diagonal glints and a darker deep core."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            d, edge, cid = voronoi(X * 0.8 + Y * 0.3, Y, seed, 5.0)
            t = base_t(face, yy, fh, 0.22) - 0.04 + (cid - 0.5) * 0.2
            if edge < 0.14:
                t += 0.28
            if facet and (X - Y * 2 + seed) % 11 == 0:
                t += 0.3
            if fw > 2 and fh > 2 and (xx == fw - 1 or yy == fh - 1):
                t -= 0.12
            setpx(ctx, X, Y, ramp(palette, t))


@painter("frost_atronach")
def frost_atronach():
    lay = load("frost_atronach")
    mats = {
        "packed_ice": Mat(PACKED, "flat"),
        "ice": Mat(ICE, "flat"),
        "ice_head": Mat(shift(ICE, mul=0.72), "flat"),
        "ice_fist": Mat(shift(ICE, mul=0.9), "flat"),
        "shard": Mat(SHARD, "flat"),
        "core": Mat(pal("#1060d0", "#3a9cf4", "#8ad8ff", "#dcf6ff", "#ffffff"), "flat", glow=True),
    }

    def decor(ctx):
        if ctx.mat in ("ice", "ice_head", "ice_fist"):
            ice_box(ctx, mats[ctx.mat].pal, ctx.seed % 101)
        elif ctx.mat == "packed_ice":
            # packed blocks of snow-ice with deep blue crevices between them
            for face in ctx.f:
                for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                    d, edge, cid = voronoi(X, Y * 1.3, ctx.seed % 89, 4.5)
                    t = base_t(face, yy, fh, 0.3) + (cid - 0.5) * 0.16 - d * 0.1
                    if edge < 0.1:
                        t -= 0.32
                    elif edge < 0.2:
                        t -= 0.1
                    setpx(ctx, X, Y, ramp(PACKED, t))
        elif ctx.mat == "shard":
            for face in ctx.f:
                for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                    t = 0.45 + (0.35 if xx == 0 else 0) - (0.2 if xx == fw - 1 else 0) + yy / max(1, fh) * -0.15 + \
                        (0.2 if (xx + yy) % 4 == 0 else 0)
                    setpx(ctx, X, Y, ramp(SHARD, t + FACE_LIGHT[face]))
        elif ctx.mat == "core":
            for face in ctx.f:
                for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                    d = math.hypot(xx - (fw - 1) / 2.0, yy - (fh - 1) / 2.0) / max(1.0, max(fw, fh) / 2.0)
                    c = ramp(mats["core"].pal, 1.05 - d * 0.9 + (0.1 if (xx + yy) % 2 else 0))
                    setpx(ctx, X, Y, c, glow=c)
        # snow caps on upward faces of the big chunks
        if ctx.is_("shoulder", "slab", "torso", "hips", "bracer"):
            fw, fh = ctx.size("top")
            for xx, yy, fw, fh, X, Y in face_px(ctx, "top"):
                if vnoise(X, Y, ctx.seed, 2) > 0.3:
                    setpx(ctx, X, Y, ramp(SNOW, 0.5 + (vnoise(X, Y, ctx.seed + 3, 1.5) - 0.5) * 0.6))
            for face in SIDES:
                fw, fh = ctx.size(face)
                for x in range(fw):
                    drip = 1 + int(hash2(x, 3, ctx.seed) * 2.2)
                    for y in range(min(drip, fh)):
                        ctx.put(face, x, y, ramp(SNOW, 0.75 - y * 0.2))
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            ctx.rect("front", 0, 1, fw, 1, ramp(ICE, 0.05))
            ctx.rect("front", 1, 2, 2, 2, (120, 210, 255), glow=True)
            ctx.rect("front", fw - 3, 2, 2, 2, (120, 210, 255), glow=True)
            ctx.put("front", 2, 2, (240, 253, 255), glow=True)
            ctx.put("front", fw - 3, 2, (240, 253, 255), glow=True)
            ctx.hline("front", fh - 1, ramp(ICE, 0.05), 1, fw - 1)
            ctx.hline("front", fh - 2, ramp(ICE, 0.2), 2, fw - 2)
        if ctx.is_("hand"):
            fw, fh = ctx.size("front")
            for x in range(1, fw - 1, 2):
                for y in range(fh - 3, fh):
                    ctx.tint("front", x, y, 0.82)
        if ctx.is_("torso"):
            # a faint glow seeping through cracks from the core
            rnd = random.Random(ctx.seed)
            for _ in range(3):
                crack_walk(ctx, "front", rnd, 10, (150, 220, 255), halo=(70, 130, 190), halo_glow=None)

    img, glow = paint(lay, mats, decor, seed=51)
    out("frost_atronach", img, glow)


# ================================================================================================ storm atronach

ROCK = pal("#221e1c", "#3a3430", "#57504a", "#776e64", "#968c80", "#b4aa9c")
BOLT = pal("#1c2c8a", "#3a62e4", "#7aa8ff", "#c8e2ff", "#ffffff")


@painter("storm_atronach")
def storm_atronach():
    lay = load("storm_atronach")
    mats = {
        "rock": Mat(ROCK, "stone", base=0.62, scale=1.3, contrast=0.8, grad=0.3),
        "lightning": Mat(BOLT, "flat", glow=True),
    }

    def decor(ctx):
        rnd = random.Random(ctx.seed)
        if ctx.mat == "rock":
            for face in ctx.f:
                fw, fh = ctx.size(face)
                area = fw * fh
                n = 1 if area >= 30 and face not in ("top", "bottom") else 0
                for _ in range(n):
                    crack_walk(ctx, face, rnd, max(fw, fh) + 2, (200, 236, 255), halo=(30, 34, 44))
            if ctx.is_("head"):
                fw, fh = ctx.size("front")
                ctx.rect("front", 1, 2, 2, 1, (200, 236, 255), glow=True)
                ctx.rect("front", fw - 3, 2, 2, 1, (200, 236, 255), glow=True)
                ctx.rect("front", 1, 1, fw - 2, 1, ramp(ROCK, 0.1))
                ctx.put("front", fw // 2 - 1, 4, (120, 180, 255), glow=True)
                ctx.put("front", fw // 2, 4, (120, 180, 255), glow=True)
        elif ctx.mat == "lightning":
            for face in ctx.f:
                for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                    zig = abs(((Y * 0.8 + 2.0 * abs(((X + ctx.seed) % 4) - 2)) % 4) - 2)
                    t = 0.35 + (0.55 if zig < 0.6 else 0) + (vnoise(X, Y, ctx.seed, 1.5) - 0.5) * 0.4
                    if ctx.is_("core"):
                        d = math.hypot(xx - (fw - 1) / 2.0, yy - (fh - 1) / 2.0)
                        t += 0.35 - d * 0.18
                    c = ramp(BOLT, t)
                    setpx(ctx, X, Y, c, glow=c)

    img, glow = paint(lay, mats, decor, seed=61)
    out("storm_atronach", img, glow)


# ================================================================================================ dremora

DAEDRIC = pal("#070505", "#120c0d", "#1f1517", "#302226", "#4a3438", "#6a4c50")
RUNE = (235, 44, 20)
RUNE_DIM = (120, 16, 8)


def daedric_box(ctx, palette, runes=True):
    """Black daedric plate: bevelled edges, a dark red sheen, glowing red rune channels."""
    seed = ctx.seed
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            t = base_t(face, yy, fh, 0.3) - 0.05
            if fw > 2 and fh > 2:
                if yy == 0 or xx == 0:
                    t += 0.22
                if yy == fh - 1 or xx == fw - 1:
                    t -= 0.15
            t += (vnoise(X, Y, seed, 3) - 0.5) * 0.12
            if (X + Y * 2 + seed) % 9 == 0:
                t += 0.12
            setpx(ctx, X, Y, ramp(palette, t))
    if not runes:
        return
    rnd = random.Random(seed)
    for face in SIDES:
        fw, fh = ctx.size(face)
        if fw < 3 or fh < 3:
            continue
        if face == "front" and fw >= 6 and fh >= 5:
            # central channel with chevrons
            cx = fw // 2
            for y in range(1, fh - 1):
                ctx.put(face, cx, y, RUNE, glow=True)
                if fw % 2 == 0:
                    ctx.put(face, cx - 1, y, RUNE_DIM, glow=True)
            for y in range(1, fh - 2, 3):
                for k in (1, 2):
                    ctx.put(face, cx - 1 - k - (0 if fw % 2 else 0), y + k - 1, RUNE_DIM, glow=True)
                    ctx.put(face, cx + k, y + k - 1, RUNE_DIM, glow=True)
        elif fh >= 4 and rnd.random() < 0.45:
            # a vertical glyph: stem with angular branches
            x = rnd.randrange(1, fw - 1)
            for y in range(1, fh - 1):
                ctx.put(face, x, y, RUNE, glow=True)
            for y in range(2, fh - 2, 2):
                dx = 1 if rnd.random() < 0.5 else -1
                ctx.put(face, x + dx, y, RUNE_DIM, glow=True)


@painter("dremora")
def dremora():
    lay = load("dremora")
    mats = {
        "armor": Mat(DAEDRIC, "flat"),
        "gauntlet": Mat(DAEDRIC, "flat"),
        "daedric": Mat(DAEDRIC, "flat"),
        "spike": Mat(pal("#0a0606", "#1e1214", "#3a2426", "#5c3c3e"), "horn", base=0.5),
        "face": Mat(pal("#2e2a2c", "#4c4648", "#6c6668", "#8e8888", "#b2acaa"), "skin", base=0.58, contrast=0.6),
        "hair": Mat(pal("#080606", "#161212", "#262020", "#383030"), "hair", base=0.5, contrast=0.7),
        "horn": Mat(pal("#140e0a", "#2a1e16", "#463428", "#6a5240", "#8a7058"), "horn", base=0.5),
        "cloth": Mat(pal("#1e0404", "#3a0a0a", "#5c1210", "#7e1e18", "#a02c22"), "cloth", base=0.5),
        "grip": Mat(pal("#120c0a", "#24180e", "#3a2a1a"), "leather", base=0.5),
        "blade": Mat(DAEDRIC, "flat"),
    }

    def decor(ctx):
        if ctx.mat in ("armor", "gauntlet", "daedric"):
            daedric_box(ctx, DAEDRIC, runes=ctx.is_("chestplate", "pauldron", "tasset", "greave", "collar", "guard"))
        elif ctx.mat == "blade":
            for face in ctx.f:
                for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                    t = base_t(face, yy, fh, 0.1) + (0.25 if face in ("top", "bottom") else 0)
                    setpx(ctx, X, Y, ramp(DAEDRIC, t + 0.1))
            if ctx.is_("blade"):
                # glowing fuller down the middle of both flats, a pale honed edge
                for face in ("right", "left"):
                    fw, fh = ctx.size(face)
                    for x in range(1, fw - 1):
                        ctx.put(face, x, fh // 2, RUNE, glow=True)
                        if x % 4 == 0:
                            ctx.put(face, x, fh // 2 - 1, RUNE_DIM, glow=True)
                    ctx.hline(face, 0, ramp(DAEDRIC, 1.0))
                    ctx.hline(face, fh - 1, ramp(DAEDRIC, 1.0))
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # ash-grey face: red war markings, burning eyes, a hard mouth
            for x in range(fw):
                ctx.tint("front", x, 1, 0.75)
            ctx.put("front", 1, 2, (255, 60, 30), glow=True)
            ctx.put("front", fw - 2, 2, (255, 60, 30), glow=True)
            for y in (3, 4):
                ctx.put("front", 0, y, (150, 18, 14))
                ctx.put("front", fw - 1, y, (150, 18, 14))
            ctx.put("front", fw // 2, 0, (170, 20, 14))
            ctx.put("front", fw // 2, 1, (170, 20, 14))
            ctx.hline("front", fh - 2, (40, 20, 22), 1, fw - 1)
            ctx.put("front", fw // 2, fh - 1, (150, 18, 14))
            for face in ("right", "left"):
                sw, sh = ctx.size(face)
                for y in range(2, sh - 1):
                    ctx.put(face, sw - 2 if face == "right" else 1, y, (130, 16, 12))
        if ctx.is_("tabard"):
            fw, fh = ctx.size("front")
            for y in range(1, fh, 3):
                ctx.put("front", fw // 2, y, (255, 70, 30), glow=True)
            ctx.hline("front", fh - 1, (20, 4, 4))
        if ctx.is_("belt"):
            ctx.put("front", ctx.size("front")[0] // 2, 0, (255, 70, 30), glow=True)

    img, glow = paint(lay, mats, decor, seed=71)
    out("dremora", img, glow)


# ================================================================================================ scamp

@painter("scamp")
def scamp():
    lay = load("scamp")
    skin = pal("#40100a", "#74240e", "#a83e16", "#d06026", "#ec8a46")
    mats = {
        "scamp_skin": Mat(skin, "leather", base=0.55, counter=0.25, dorsal=0.12, grad=0.1),
        "scamp_belly": Mat(pal("#7a3214", "#b05628", "#d6804a", "#eeaa72"), "leather", base=0.55),
        "scamp_dark": Mat(pal("#220806", "#3e1008", "#5e1c0c", "#7e2e14"), "leather", base=0.55),
        "claw": Mat(pal("#0e0a08", "#2a2018", "#4c3e30", "#6e6050"), "horn", base=0.55),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # heavy brow shadow, slit-pupilled yellow eyes, wide grin line
            ctx.hline("front", 0, ramp(skin, 0.2))
            ctx.rect("front", 1, 1, 2, 1, (255, 210, 60), glow=True)
            ctx.rect("front", fw - 3, 1, 2, 1, (255, 210, 60), glow=True)
            ctx.put("front", 2, 1, (60, 10, 4))
            ctx.put("front", fw - 3, 1, (60, 10, 4))
            ctx.hline("front", fh - 1, (40, 8, 4), 0, fw)
            ctx.put("front", 0, fh - 2, (40, 8, 4))
            ctx.put("front", fw - 1, fh - 2, (40, 8, 4))
        if ctx.is_("nose"):
            ctx.put("front", 0, 0, (40, 10, 6))
            ctx.put("front", 1, 0, (40, 10, 6))
        if ctx.is_("jaw"):
            fw, fh = ctx.size("top")
            ctx.hline("top", 0, (40, 8, 4))
            for x in range(0, fw, 2):
                ctx.put("top", x, 0, (240, 228, 200))
            ctx.hline("front", 0, (240, 228, 200))
            for x in range(1, ctx.size("front")[0], 2):
                ctx.put("front", x, 0, (60, 14, 6))
        if ctx.is_("ear", "ear_tip"):
            # pale inner ear
            for face in ("front",):
                fw, fh = ctx.size(face)
                for x in range(1, fw - 1):
                    ctx.tint(face, x, fh // 2, 1.25, (20, 10, 0))
        if ctx.is_("torso"):
            fw, fh = ctx.size("back")
            for y in range(1, fh, 2):
                ctx.put("back", fw // 2, y, ramp(skin, 0.1))
        if ctx.is_("belly"):
            fw, fh = ctx.size("front")
            for y in range(1, fh, 2):
                ctx.hline("front", y, ramp(mats["scamp_belly"].pal, 0.3), 1, fw - 1)

    img, glow = paint(lay, mats, decor, seed=81)
    out("scamp", img, glow)


# ================================================================================================ imp

@painter("imp")
def imp():
    lay = load("imp")
    skin = pal("#232a1e", "#3c4632", "#5c684c", "#7e8a6a", "#a2ac8a")
    mats = {
        "imp_skin": Mat(skin, "bark", base=0.56, scale=1.3, contrast=0.55, counter=0.15, dorsal=0.08),
        "imp_belly": Mat(pal("#5c6448", "#7e8866", "#a2ac88", "#c2caa6"), "skin", base=0.55),
        "imp_dark": Mat(pal("#14180f", "#262c1e", "#3a4230", "#505a42"), "skin", base=0.5),
        "membrane": Mat(pal("#1e1a14", "#342e24", "#4e4636", "#6a604a", "#8a7e62"), "membrane", base=0.55),
        "horn": Mat(pal("#16120c", "#2e261c", "#4c4030", "#70624c", "#958668"), "horn", base=0.5),
        "claw": Mat(pal("#0e0c08", "#26201a", "#463c30"), "horn", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            ctx.hline("front", 0, ramp(skin, 0.15))
            ctx.put("front", 1, 1, (255, 170, 30), glow=True)
            ctx.put("front", fw - 2, 1, (255, 170, 30), glow=True)
            ctx.hline("front", fh - 1, (24, 18, 12), 1, fw - 1)
            ctx.put("front", 1, fh - 1, (220, 214, 190))
            ctx.put("front", fw - 2, fh - 1, (220, 214, 190))
        if ctx.is_("jaw"):
            ctx.hline("top", 0, (200, 194, 170))
        if ctx.is_("membrane"):
            # wing fingers: darker radiating bones across the membrane
            for face in ("top", "bottom"):
                fw, fh = ctx.size(face)
                for k in range(1, 4):
                    x = int(fw * k / 4)
                    for y in range(fh):
                        ctx.put(face, max(0, x - y // 3), y, ramp(skin, 0.2))
                for x in range(fw):
                    if hash2(x, 1, ctx.seed) > 0.6:
                        ctx.put(face, x, fh - 1, (0, 0, 0, 0))

    img, glow = paint(lay, mats, decor, seed=91)
    out("imp", img, glow)


# ================================================================================================ dragon priest

MASKS = {
    "dragon_priest": ("#3a2410", "#6a4420", "#9a6a34", "#c89a58", "#ecc888", (1.0, 1.0, 1.0)),
    "dragon_priest_morokei": ("#4a3008", "#8a6414", "#c89a28", "#f0cc50", "#fff0a8", (0.9, 0.95, 1.12)),
    "dragon_priest_rahgot": ("#4a2408", "#8a4a10", "#c87a20", "#f0a840", "#ffd890", (1.1, 0.95, 0.85)),
    "dragon_priest_vokun": ("#141414", "#26262a", "#3c3c42", "#5a5a62", "#80808a", (0.85, 0.85, 0.95)),
    "dragon_priest_volsung": ("#4a3410", "#80602a", "#b08e44", "#d8b866", "#f4e0a0", (1.0, 1.05, 0.9)),
    "dragon_priest_nahkriin": ("#3a3e44", "#62686e", "#8e949a", "#bcc2c8", "#e8ecf0", (0.9, 1.0, 1.05)),
    "dragon_priest_otar": ("#0a0a08", "#1a1814", "#2c2822", "#423c34", "#5e564a", (0.8, 0.8, 0.8)),
    "dragon_priest_krosis": ("#3a1408", "#6e2a12", "#a04a24", "#c87048", "#e8a07a", (1.12, 0.92, 0.9)),
    "dragon_priest_hevnoraak": ("#24262a", "#3e4146", "#5a5e64", "#7c8086", "#a2a6aa", (0.92, 0.98, 1.0)),
    "dragon_priest_konahrik": ("#5a3c08", "#a07814", "#d8ae2a", "#ffd84a", "#fff6c0", (1.05, 1.0, 0.9)),
}
EYE = (170, 235, 255)


def tint_pal(p, mul):
    return [(clamp(c[0] * mul[0]), clamp(c[1] * mul[1]), clamp(c[2] * mul[2])) for c in p]


def mask_box(ctx, palette, ornate=False):
    """Polished metal mask: a diagonal sheen, engraved dragon-scale brow lines, dark eye holes with glowing eyes."""
    for face in ctx.f:
        for xx, yy, fw, fh, X, Y in face_px(ctx, face):
            t = base_t(face, yy, fh, 0.15)
            sheen = (xx - yy * 0.6) % 6
            if sheen < 1.0:
                t += 0.3
            if fw > 2 and fh > 2 and (xx == fw - 1 or yy == fh - 1):
                t -= 0.15
            setpx(ctx, X, Y, ramp(palette, t))
    if ctx.is_("mask"):
        fw, fh = ctx.size("front")
        dark = ramp(palette, 0.0)
        line = ramp(palette, 0.22)
        # brow: a chevron rising to the middle like folded dragon wings
        for x in range(fw):
            ctx.put("front", x, 1 + abs(x - fw // 2) // 2 if abs(x - fw // 2) > 0 else 0, line)
        # eye holes with glowing eyes
        for ex in (1, fw - 2):
            ctx.put("front", ex, 2, (14, 10, 8))
            ctx.put("front", ex, 3, EYE, glow=True)
        # snout ridge and a mouth slit with teeth notches
        for y in range(1, fh - 2):
            if y not in (3,):
                ctx.tint("front", fw // 2, y, 1.15)
        ctx.hline("front", fh - 2, dark, 1, fw - 1)
        for x in range(1, fw - 1, 2):
            ctx.put("front", x, fh - 2, line)
        if ornate:
            for x in range(0, fw, 2):
                ctx.put("front", x, 0, ramp(palette, 1.0))
            ctx.put("front", fw // 2, fh - 1, ramp(palette, 1.0))
            ctx.put("front", 0, fh - 3, ramp(palette, 1.0))
            ctx.put("front", fw - 1, fh - 3, ramp(palette, 1.0))


def rag_edges(ctx, depth=2):
    """Tattered bottoms and moth holes on the side faces (transparent texels: render cutout)."""
    rnd = random.Random(ctx.seed)
    for face in SIDES:
        fw, fh = ctx.size(face)
        for x in range(fw):
            cut = rnd.randrange(0, depth + 1)
            for y in range(fh - cut, fh):
                ctx.put(face, x, y, (0, 0, 0, 0))
        if fw * fh >= 8 and rnd.random() < 0.6:
            ctx.put(face, rnd.randrange(fw), rnd.randrange(max(1, fh - 2)), (0, 0, 0, 0))


@painter("dragon_priest")
def dragon_priest():
    lay = load("dragon_priest")
    glow_saved = None
    for name, (*hexes, rtint) in MASKS.items():
        mask_pal = pal(*hexes)
        robe = tint_pal(pal("#1e1712", "#342920", "#4e4032", "#6a5844", "#867058"), rtint)
        robe_dark = tint_pal(pal("#120e0a", "#221a14", "#36291f", "#4c3c2c"), rtint)
        trim = mask_pal if name != "dragon_priest_otar" else pal("#3a2410", "#6a4420", "#9a6a34", "#c89a58")
        mats = {
            "robe": Mat(robe, "cloth", base=0.55, contrast=0.9),
            "robe_dark": Mat(robe_dark, "cloth", base=0.55, contrast=0.9),
            "rag": Mat(robe_dark, "cloth", base=0.6, contrast=0.9),
            "mantle": Mat(tint_pal(pal("#2a0e0a", "#441812", "#62261c", "#80382a"), rtint), "cloth", base=0.55),
            "tabard": Mat(tint_pal(pal("#2a0e0a", "#441812", "#62261c", "#80382a"), rtint), "cloth", base=0.5),
            "trim": Mat(trim, "metal", base=0.55, scale=0.7, contrast=0.6),
            "mummy": Mat(pal("#22180e", "#3e2e1e", "#5c4630", "#7a6248", "#988062"), "skin", base=0.5),
            "bone": Mat(pal("#5e5444", "#867c68", "#aea48c", "#d2caae"), "bone", base=0.6),
            "mask": Mat(mask_pal, "flat"),
            "staff": Mat(pal("#16100a", "#2a2016", "#423424", "#5a4834"), "bark", base=0.55),
            "staff_metal": Mat(mask_pal, "metal", base=0.55, scale=0.7, contrast=0.7),
        }
        ornate = name == "dragon_priest_konahrik"

        def decor(ctx, mask_pal=mask_pal, trim=trim, ornate=ornate):
            if ctx.mat == "mask":
                mask_box(ctx, mask_pal, ornate)
            if ctx.is_("rag", "hem", "cape"):
                rag_edges(ctx, 1 if ctx.is_("hem") else 2)
            if ctx.is_("mantle", "collar"):
                # Nordic knot-band trim along the edges
                for face in SIDES:
                    fw, fh = ctx.size(face)
                    ctx.hline(face, fh - 1, ramp(trim, 0.7))
                    for x in range(0, fw, 2):
                        ctx.put(face, x, fh - 2 if fh > 2 else 0, ramp(trim, 0.45))
            if ctx.is_("tabard"):
                fw, fh = ctx.size("front")
                for y in range(fh):
                    ctx.put("front", 0, y, ramp(trim, 0.6))
                    ctx.put("front", fw - 1, y, ramp(trim, 0.6))
                for y in range(1, fh - 1, 3):
                    ctx.put("front", fw // 2, y, ramp(trim, 0.8))
                    ctx.put("front", fw // 2, y + 1, ramp(trim, 0.4))
            if ctx.is_("head"):
                for face in ("right", "left"):
                    fw, fh = ctx.size(face)
                    ctx.put(face, fw // 2, fh // 2, (20, 14, 10))
                    ctx.hline(face, fh - 2, (40, 30, 20), 1, fw - 1)
            if ctx.is_("staff_orb"):
                for face in ctx.f:
                    for xx, yy, fw, fh, X, Y in face_px(ctx, face):
                        c = ramp(pal("#2a4a6a", "#5a8ab4", "#a8d4f0", "#e8f8ff"), 0.4 + (0.4 if (xx + yy) % 2 else 0))
                        setpx(ctx, X, Y, c, glow=c)
            if ctx.is_("chest"):
                fw, fh = ctx.size("front")
                ctx.hline("front", fh - 1, ramp(trim, 0.6))

        img, glow = paint(lay, mats, decor, seed=101)
        save(img, "creatures/%s.png" % name)
        if glow_saved is None:
            glow_saved = glow
    save(glow_saved, "creatures/dragon_priest_glow.png")


# ================================================================================================ dwemer automatons


def dwemer_painter(name, seed, extra=None):
    lay = load(name)

    def decor(ctx):
        dwemer_decor_base(ctx)
        if ctx.is_("visor"):
            fw, fh = ctx.size("front")
            dwemer_eye(ctx, "front", 1, 0, fw - 2, 1)
        if ctx.is_("beard"):
            fw, fh = ctx.size("front")
            for x in range(0, fw, 2):
                for y in range(fh):
                    ctx.tint("front", x, y, 0.7)
        if ctx.is_("head") and ctx.bone == "head" and name == "dwarven_spider":
            fw, fh = ctx.size("front")
            dwemer_eye(ctx, "front", fw // 2 - 1, 1, 2, 1)
            ctx.put("front", fw // 2 - 1, 0, ramp(GOLD_TRIM, 0.8))
            ctx.put("front", fw // 2, 0, ramp(GOLD_TRIM, 0.8))
        if extra:
            extra(ctx)

    img, glow = paint(lay, dwemer_mats(), decor, seed=seed)
    out(name, img, glow)


@painter("dwarven_spider")
def dwarven_spider():
    dwemer_painter("dwarven_spider", 111)


@painter("dwarven_sphere")
def dwarven_sphere():
    def extra(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            # helmet face: brow line and nose guard above the visor
            ctx.hline("front", 1, ramp(BRONZE_DARK, 0.3), 0, fw)
            ctx.put("front", fw // 2, 2, ramp(GOLD_TRIM, 0.8))
        if ctx.is_("ball"):
            # rolling seams: the ball's plates are split in petals
            for face in ctx.f:
                fw, fh = ctx.size(face)
                if fw >= 8:
                    for y in range(fh):
                        ctx.tint(face, fw // 2, y, 0.65)
    dwemer_painter("dwarven_sphere", 121, extra)


@painter("dwarven_centurion")
def dwarven_centurion():
    def extra(ctx):
        if ctx.is_("boiler"):
            fw, fh = ctx.size("front")
            # boiler grille slots
            for y in range(2, fh - 1, 2):
                ctx.hline("front", y, ramp(BRONZE_DARK, 0.1), 3, fw - 3)
        if ctx.is_("cap"):
            ctx.rect("bottom", 0, 0, 3, 3, (24, 16, 10))
        if ctx.is_("chimney"):
            ctx.rect("top", 0, 0, 2, 2, (18, 12, 8))
    dwemer_painter("dwarven_centurion", 131, extra)


@painter("dwarven_ballista")
def dwarven_ballista():
    def extra(ctx):
        if ctx.is_("muzzle"):
            ctx.put("front", 1, 0, (16, 10, 6))
            ctx.put("front", 1, 1, (16, 10, 6))
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            dwemer_eye(ctx, "front", 1, 1, fw - 2, 1)
    dwemer_painter("dwarven_ballista", 141, extra)


# ================================================================================================ main


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)


if __name__ == "__main__":
    main(sys.argv[1:])
