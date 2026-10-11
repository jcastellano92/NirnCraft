"""Textures of the seas module (com.skycraft.seas): its creatures, the kollop shellfish and the ship liveries' sails.

Run tools/textures/dump_sea_rigs.sh first when a rig in SeaDefs.java changed, then (from tools/textures):
    py -3 seas.py
Writes
    textures/entity/creatures/<name>.png (+ _glow) for dreugh (+ elder, queen), sea_leviathan, giant_slaughterfish,
        sea_netch (+ blue), whale, sea_crab (+ coral), seal, hawk, river_betty, abecean_longfin;
    textures/block/kollop.png;
    textures/entity/sailing/livery_sails.png: 64 px sail pictures in a 4 x 4 grid (merchant, navy, pirate, Maormer,
        Sload, ghost), read by ShipRenderer.
All original procedural art.
"""
import math
import os
import random

from PIL import Image, ImageDraw

from rigpaint import Mat, load, paint, pal, save, has_pixels, shift, lerp, hash2, vnoise, clamp, OUT

CLEAR = (0, 0, 0, 0)


def out(name, img, glow=None):
    save(img, "creatures/%s.png" % name)
    if glow is not None and has_pixels(glow):
        save(glow, "creatures/%s_glow.png" % name)


def eyes_on(ctx, face, color, glow=False, rows=(0.4,)):
    fw, fh = ctx.size(face)
    if fw <= 0 or fh <= 0:
        return
    for r in rows:
        y = int(fh * r)
        ctx.put(face, 0, y, color, glow)
        ctx.put(face, fw - 1, y, color, glow)


# ================================================================================================ dreugh

def dreugh(name, shell_pal, flesh_pal, seed, crown=False):
    lay = load("dreugh")
    mats = {
        "shell": Mat(shell_pal, "chitin", base=0.55, contrast=0.7, dorsal=0.08, spots=(0.12, 2.5, 0.12)),
        "flesh": Mat(flesh_pal, "skin", base=0.52, contrast=0.6),
        "claw": Mat(pal("#1a120e", "#3a2a20", "#6a5038", "#9a8060"), "horn", base=0.5),
        "eye": Mat(pal("#0a0a08", "#1a1a12", "#f0d060", "#fff4b0"), "plain", base=0.7),
    }

    def decor(ctx):
        if ctx.is_("stalk"):
            fw, fh = ctx.size("top")
            ctx.fill("top", (250, 210, 80), glow=False)
            ctx.put("front", 0, 0, (255, 230, 120), glow=True)
        if ctx.is_("carapace", "dome"):
            # barnacle crust and growth rings
            for face in ("top", "right", "left", "front", "back"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if hash2(x, y, ctx.seed + 3) > 0.94:
                            ctx.put(face, x, y, (196, 190, 168))
                        elif (x + y * 2) % 7 == 0 and hash2(x, y, ctx.seed) > 0.6:
                            ctx.tint(face, x, y, 0.8)
            if crown and ctx.is_("dome"):
                fw, fh = ctx.size("top")
                for x in range(0, fw, 3):
                    for face in ("front", "back", "right", "left"):
                        ctx.put(face, min(x, ctx.size(face)[0] - 1), 0, (230, 190, 90), glow=True)
        if ctx.is_("face"):
            fw, fh = ctx.size("front")
            ctx.hline("front", fh - 2, (40, 14, 18))
            for x in range(1, fw - 1, 2):
                ctx.put("front", x, fh - 1, (220, 210, 180))
        if ctx.is_("belly", "tentacle", "tentacle_tip"):
            for face in ctx.all_faces():
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if (y + x) % 3 == 0 and face != "top":
                            ctx.tint(face, x, y, 1.18)
    img, glow = paint(lay, mats, decor=decor, seed=seed)
    out(name, img, glow)


# ================================================================================================ leviathan

def leviathan():
    lay = load("sea_leviathan")
    hide = pal("#120a1a", "#24142e", "#3a2448", "#563a66", "#7a5a88")
    mats = {
        "hide": Mat(hide, "skin", base=0.52, contrast=0.6, dorsal=0.1, counter=0.25, spots=(0.1, 3, 0.15),
                    belly=pal("#6a4a5a", "#8a6a76", "#b09098", "#d0b4b4")),
        "arm": Mat(shift(hide, 10, 0, 6), "skin", base=0.55, contrast=0.5),
        "sucker": Mat(pal("#5a3a50", "#8a6078", "#c098a8", "#e8c8cc"), "skin", base=0.55, contrast=0.4),
        "fin": Mat(pal("#1a0e24", "#3a2448", "#6a4a7a", "#9a7aa8"), "membrane", base=0.55),
        "eye": Mat(pal("#101a08", "#406a10", "#b0f040", "#f0ffa0"), "plain", base=0.7, glow=True),
        "beak": Mat(pal("#0e0c0a", "#2a2620", "#4a443c", "#6e665a"), "horn", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("eye"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    ctx.put(face, fw // 2, y, (10, 10, 6))
        if ctx.is_("arm", "arm_tip"):
            for face in ("bottom",):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if y % 2 == 0:
                            ctx.put(face, x, y, (220, 180, 190))
    img, glow = paint(lay, mats, decor=decor, seed=71)
    out("sea_leviathan", img, glow)


# ================================================================================================ fish

def fish(rig, name, body, belly, fin, seed, stripes=None, teeth=False, scale=1.0):
    lay = load(rig)
    mats = {
        "scales": Mat(body, "scales", base=0.55, contrast=0.6, dorsal=0.12, counter=0.3, scale=scale, belly=belly),
        "head": Mat(shift(body, mul=0.95), "scales", base=0.55, contrast=0.5, belly=belly),
        "belly": Mat(belly, "chitin", base=0.6, contrast=0.4),
        "fin": Mat(fin, "membrane", base=0.55),
        "teeth": Mat(pal("#8a826e", "#bcb49c", "#e2dcc6", "#f8f4e8"), "bone", base=0.62),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            ctx.put("right", max(0, fw - 2), max(0, fh // 3), (240, 220, 80))
            ctx.put("left", min(1, fw - 1), max(0, fh // 3), (240, 220, 80))
        if stripes and ctx.is_("segment"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for x in range(fw):
                    if x % stripes[0] < stripes[1]:
                        for y in range(fh // 2):
                            ctx.tint(face, x, y, 0.65)
    img, glow = paint(lay, mats, decor=decor, seed=seed)
    out(name, img, glow)


def whale():
    lay = load("whale")
    mats = {
        "hide": Mat(pal("#141a20", "#222c36", "#34424e", "#4c5c6a", "#6a7a86"), "skin", base=0.52, contrast=0.45, dorsal=0.1,
                    counter=0.3, spots=(0.06, 2, 0.25), belly=pal("#8a949a", "#a8b2b6", "#c8d0d0", "#e2e8e6")),
        "belly": Mat(pal("#8a949a", "#a8b2b6", "#c8d0d0", "#e2e8e6"), "cloth", base=0.6, contrast=0.5),
        "fin": Mat(pal("#141a20", "#283440", "#3c4c5a", "#5a6c7a"), "skin", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            ctx.put("right", fw - 4, fh // 2, (8, 8, 8))
            ctx.put("left", 3, fh // 2, (8, 8, 8))
        if ctx.is_("throat"):
            fw, fh = ctx.size("bottom")
            for y in range(fh):
                for x in range(0, fw, 2):
                    ctx.tint("bottom", x, y, 0.75)
    img, glow = paint(lay, mats, decor=decor, seed=33)
    out("whale", img, glow)


# ================================================================================================ sea netch

def sea_netch(name, bell_pal, glow_col, seed):
    lay = load("sea_netch")
    mats = {
        "bell": Mat(bell_pal, "ethereal", base=0.55, alpha=200, contrast=0.6),
        "rim": Mat(shift(bell_pal, mul=1.15), "ethereal", base=0.6, alpha=210),
        "thread": Mat(shift(bell_pal, mul=1.2), "plain", base=0.6, alpha=190),
        "glow": Mat(pal(*glow_col), "ember", base=0.65, glow=True, glow_strength=0.9),
    }

    def decor(ctx):
        if ctx.is_("bell"):
            for face in ("right", "left", "front", "back"):
                fw, fh = ctx.size(face)
                for x in range(0, fw, 4):
                    for y in range(fh):
                        c = ctx.get(face, x, y)
                        if c:
                            ctx.put(face, x, y, (min(255, c[0] + 30), min(255, c[1] + 30), min(255, c[2] + 40), c[3]), glow=True)
    img, glow = paint(lay, mats, decor=decor, seed=seed)
    out(name, img, glow)


# ================================================================================================ crab, seal, hawk

def crab(name, shell, seed, coral=False):
    lay = load("sea_crab")
    mats = {
        "shell": Mat(shell, "chitin", base=0.55, contrast=0.7, dorsal=0.1, spots=(0.15 if coral else 0.06, 2, 0.2)),
        "flesh": Mat(shift(shell, 40, 30, 20, 0.9), "skin", base=0.6, contrast=0.4),
        "claw": Mat(pal("#1a120e", "#3a2a20", "#6a5038", "#9a8060"), "horn", base=0.5),
        "eye": Mat(pal("#0a0a08", "#1a1a12", "#202018", "#303026"), "plain", base=0.4),
    }

    def decor(ctx):
        if ctx.is_("stalk"):
            ctx.fill("top", (10, 10, 10))
        if coral and ctx.is_("carapace", "dome"):
            for face in ("top", "right", "left"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        h = hash2(x, y, ctx.seed + 11)
                        if h > 0.9:
                            ctx.put(face, x, y, (240, 120, 200) if h > 0.95 else (90, 200, 220))
    img, glow = paint(lay, mats, decor=decor, seed=seed)
    out(name, img, glow)


def seal():
    lay = load("seal")
    fur = pal("#2a2a2e", "#46464c", "#62626a", "#82828a", "#a2a2a8")
    mats = {
        "fur": Mat(fur, "fur", base=0.55, contrast=0.4, scale=0.6, dorsal=0.08, spots=(0.15, 2.5, -0.18), counter=0.25,
                   belly=pal("#9a9a98", "#b8b8b4", "#d0d0ca", "#e8e8e2")),
        "belly": Mat(pal("#9a9a98", "#b8b8b4", "#d0d0ca", "#e8e8e2"), "fur", base=0.6, contrast=0.3),
        "muzzle": Mat(pal("#5a5a5c", "#7a7a7c", "#9a9a9a", "#b8b8b6"), "fur", base=0.55, contrast=0.3),
        "flipper": Mat(pal("#1a1a1c", "#2a2a2e", "#3a3a40", "#4a4a52"), "skin", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            for x in (1, fw - 2):
                ctx.put("front", x, fh // 2 - 1, (8, 8, 10))
                ctx.put("front", x, fh // 2 - 2, (60, 60, 64))
        if ctx.is_("muzzle"):
            fw, fh = ctx.size("front")
            ctx.put("front", fw // 2, 0, (10, 10, 10))
            ctx.put("front", fw // 2 - 1, 0, (10, 10, 10))
            for y in (1, 2):
                if y < fh:
                    ctx.put("front", 0, y, (220, 220, 210))
                    ctx.put("front", fw - 1, y, (220, 220, 210))
    img, glow = paint(lay, mats, decor=decor, seed=19)
    out("seal", img, glow)


def hawk():
    lay = load("hawk")
    brown = pal("#2a1a10", "#4a3018", "#6a4626", "#8a6438", "#a8844e")
    mats = {
        "feather": Mat(brown, "feather", base=0.55, contrast=0.6, belly=pal("#a08a6a", "#c8b08a", "#e0ccaa", "#f0e4cc")),
        "wing": Mat(brown, "feather", base=0.5, contrast=0.7, stripes=(4, 1, 0.18)),
        "head": Mat(shift(brown, 20, 16, 10), "feather", base=0.55, contrast=0.5),
        "breast": Mat(pal("#a08a6a", "#c8b08a", "#e0ccaa", "#f0e4cc"), "feather", base=0.6, contrast=0.5, spots=(0.2, 1.5, -0.3)),
        "tailfeather": Mat(pal("#5a2a10", "#7a3a18", "#a05028", "#c06a38"), "feather", base=0.55, stripes=(3, 1, 0.3)),
        "beak": Mat(pal("#1a1a1a", "#3a3a36", "#c8a830", "#e8d060"), "horn", base=0.45),
        "talon": Mat(pal("#3a3010", "#806a20", "#c8a830", "#e8d060"), "skin", base=0.6),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("right")
            ctx.put("right", fw - 2, 1, (240, 200, 40))
            ctx.put("left", 1, 1, (240, 200, 40))
    img, glow = paint(lay, mats, decor=decor, seed=5)
    out("hawk", img, glow)


# ================================================================================================ kollop

def kollop():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    shell = [(70, 58, 52), (104, 88, 78), (146, 128, 112), (190, 174, 152), (226, 214, 196)]
    for y in range(16):
        for x in range(16):
            ridge = (x + int(math.sin(y * 0.6) * 1.5)) % 3
            t = 0.35 + 0.4 * (1 - y / 15) + (0.12 if ridge == 0 else -0.05) + (vnoise(x, y, 4, 3) - 0.5) * 0.2
            i = max(0, min(len(shell) - 1, int(t * len(shell))))
            px[x, y] = shell[i] + (255,)
    # pearly lip across the middle row
    for x in range(16):
        px[x, 8] = (236, 228, 240, 255)
        if x % 4 == 1:
            px[x, 9] = (200, 190, 214, 255)
    path = os.path.join(OUT.replace("entity", "block"), "kollop_bed.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


# ================================================================================================ sails

def sail(kind, rnd):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    px = img.load()

    def cloth(base, var=10):
        for y in range(64):
            for x in range(64):
                n = (vnoise(x, y, 9, 4) - 0.5) * var + (6 if (x // 8 + y // 8) % 2 == 0 else 0) * 0.3
                seam = -14 if x % 16 == 0 else 0
                px[x, y] = (clamp(base[0] + n + seam), clamp(base[1] + n + seam), clamp(base[2] + n + seam), 255)

    if kind == "merchant":
        cloth((220, 208, 180))
        d.rectangle([0, 26, 63, 35], fill=(60, 120, 64))
        d.ellipse([24, 8, 40, 22], outline=(120, 90, 40), width=2)
        d.line([32, 10, 32, 20], fill=(120, 90, 40), width=2)
    elif kind == "navy":
        cloth((168, 30, 28))
        # the gold dragon of the Empire, original stylised curl
        for t in range(0, 360, 4):
            a = math.radians(t)
            r = 12 + 5 * math.sin(a * 3)
            x, y = 32 + r * math.cos(a), 30 + r * math.sin(a) * 0.8
            d.ellipse([x - 1.5, y - 1.5, x + 1.5, y + 1.5], fill=(226, 180, 60))
        d.polygon([(32, 14), (38, 24), (32, 21), (26, 24)], fill=(226, 180, 60))
        d.rectangle([0, 0, 63, 3], fill=(226, 180, 60))
    elif kind == "pirate":
        cloth((34, 30, 30), 14)
        for _ in range(6):
            x, y = rnd.randint(2, 54), rnd.randint(2, 54)
            d.rectangle([x, y, x + rnd.randint(5, 9), y + rnd.randint(4, 8)], fill=(58, 50, 44), outline=(20, 18, 18))
        # a skull over crossed cutlasses
        d.line([18, 46, 46, 18], fill=(200, 196, 186), width=3)
        d.line([18, 18, 46, 46], fill=(200, 196, 186), width=3)
        d.ellipse([22, 16, 42, 36], fill=(228, 224, 212))
        d.rectangle([26, 32, 38, 40], fill=(228, 224, 212))
        d.ellipse([26, 22, 30, 27], fill=(20, 18, 18))
        d.ellipse([34, 22, 38, 27], fill=(20, 18, 18))
        for x in range(27, 38, 3):
            d.line([x, 36, x, 40], fill=(30, 26, 26))
    elif kind == "maormer":
        cloth((232, 240, 244))
        for y in range(0, 64, 12):
            d.rectangle([0, y, 63, y + 5], fill=(54, 110, 196))
        # the sea serpent coiled across the stripes
        pts = [(8 + i * 1.6, 32 + 12 * math.sin(i * 0.35)) for i in range(32)]
        d.line(pts, fill=(18, 40, 90), width=4)
        d.ellipse([54, 22, 62, 30], fill=(18, 40, 90))
    elif kind == "sload":
        cloth((84, 96, 52), 22)
        for _ in range(140):
            x, y = rnd.randint(0, 63), rnd.randint(0, 63)
            px[x, y] = (56, 70, 30, 255) if rnd.random() < 0.7 else (120, 130, 70, 255)
        for _ in range(5):
            x, y = rnd.randint(4, 56), rnd.randint(4, 56)
            d.ellipse([x, y, x + rnd.randint(3, 7), y + rnd.randint(3, 6)], fill=(0, 0, 0, 0))
        d.ellipse([24, 22, 40, 38], outline=(30, 40, 16), width=2)
        d.ellipse([29, 27, 35, 33], fill=(160, 200, 80))
    elif kind == "ghost":
        cloth((196, 222, 226), 20)
        for y in range(64):
            for x in range(64):
                c = px[x, y]
                a = int(150 + 80 * vnoise(x, y, 21, 6))
                px[x, y] = (c[0], c[1], c[2], a)
        for _ in range(9):
            x = rnd.randint(0, 60)
            y0 = rnd.randint(30, 60)
            d.rectangle([x, y0, x + rnd.randint(2, 5), 63], fill=(0, 0, 0, 0))
    return img


def sails():
    atlas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    rnd = random.Random(1717)
    for i, kind in enumerate(["merchant", "navy", "pirate", "maormer", "sload", "ghost"]):
        atlas.paste(sail(kind, rnd), ((i % 4) * 64, (i // 4) * 64))
    path = os.path.join(OUT, "sailing", "livery_sails.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    atlas.save(path)


def main():
    dreugh("dreugh", pal("#2a1a12", "#4a2e1e", "#6e4630", "#946448", "#b88a66"), pal("#4a2a2a", "#704040", "#9a6058", "#c08878"), 41)
    dreugh("dreugh_elder", pal("#1a1612", "#2e2820", "#4a4030", "#6a5c44", "#8a7c5c"), pal("#3a2a30", "#5a4048", "#7a6066", "#a08088"), 43)
    dreugh("dreugh_queen", pal("#2a0e18", "#4a1a2a", "#6e2a40", "#94445a", "#b86a7a"), pal("#5a3a20", "#7a5430", "#a07444", "#c89a62"), 47,
           crown=True)
    leviathan()
    fish("giant_slaughterfish", "giant_slaughterfish", pal("#1e2a22", "#2e4034", "#465a48", "#62765c", "#8a9a7a"),
         pal("#8a8a6a", "#aaa888", "#c8c4a4", "#e0dcc0"), pal("#3a2a1a", "#5a3a22", "#7a5030", "#a06a40"), 13, stripes=(5, 2), scale=1.3)
    fish("school_fish", "river_betty", pal("#2a3420", "#3e4c2e", "#5a6a40", "#7a8a58", "#9aa874"),
         pal("#a0a080", "#c0c0a0", "#dcdcc0", "#f0f0dc"), pal("#4a3a20", "#6a5430", "#8a7040", "#aa8c58"), 3, stripes=(2, 1))
    fish("school_fish", "abecean_longfin", pal("#123048", "#1c4a6a", "#2a6a8e", "#4a8cae", "#78b0cc"),
         pal("#a0b8c0", "#c0d4d8", "#dcecee", "#f0fafa"), pal("#806010", "#a87e18", "#d0a028", "#f0c848"), 4)
    whale()
    sea_netch("sea_netch", pal("#5a2a4a", "#8a4a70", "#b47098", "#d898bc", "#f0c8de"), ("#802060", "#c040a0", "#f070d0", "#ffb0f0"), 61)
    sea_netch("sea_netch_blue", pal("#1a3a5a", "#2a5a84", "#4a80aa", "#78a8cc", "#b0d4ec"), ("#104080", "#2070c0", "#40b0f0", "#a0e8ff"), 63)
    crab("sea_crab", pal("#2a2014", "#46341e", "#66502e", "#8a7044", "#ac9060"), 23)
    crab("sea_crab_coral", pal("#5a1a12", "#8a2e1c", "#b8482a", "#de6a40", "#f49a6a"), 29, coral=True)
    seal()
    hawk()
    kollop()
    sails()
    print("seas textures written")


if __name__ == "__main__":
    main()
