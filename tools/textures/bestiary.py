"""Textures of the rig-model creatures (Skycraft's bestiary), painted from the rig layouts by rigpaint.py.

Run tools/textures/dump_rigs.sh first when a rig changed, then:  py -3 tools/textures/bestiary.py [name ...]
Writes textures/entity/creatures/<name>[_<variant>].png (+ _glow.png emissive layers).
"""
import sys

from rigpaint import Mat, load, paint, pal, save, has_pixels, shift

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

CLAW = Mat(pal("#1c1814", "#3a332c", "#6a6056"), "horn")
NOSE = Mat(pal("#0e0c0c", "#24201e", "#3c3634"), "plain")
TOOTH = Mat(pal("#a8a090", "#d8d0bc", "#f4eedc"), "bone")
HOOF = Mat(pal("#1e1812", "#3a2e24", "#5a4a3a"), "horn")


def beast_eyes(ctx, color, glow=False, row=0.32, inset=0.18):
    if ctx.is_("head"):
        ctx.eyes_front(color, row=row, inset=inset, glow=glow)


# ================================================================================================ wolves


@painter("wolf")
def wolf():
    lay = load("wolf")
    variants = {
        "wolf": (pal("#231e1a", "#40372f", "#62564a", "#8a7c6a", "#b4a68e"),
                 pal("#6a5e50", "#9a8c78", "#c4b8a2", "#e0d6c2"), (232, 176, 60)),
        "wolf_ice": (pal("#59636e", "#8590a0", "#b0bccb", "#d8e2ec", "#f6f9fc"),
                     pal("#a8b4c2", "#d0dae4", "#eef4f8", "#ffffff"), (150, 220, 255)),
    }
    for name, (fur, belly, eye) in variants.items():
        mats = {
            "fur": Mat(fur, "fur", base=0.52, belly=belly, counter=0.35, grad=0.05),
            "ruff": Mat(shift(fur, mul=0.9), "hair", base=0.5, scale=0.8, contrast=0.6),
            "leg": Mat(fur, "fur", base=0.62, counter=0.15),
            "muzzle": Mat(belly, "fur", base=0.5, counter=0.3),
            "tail": Mat(fur, "hair", base=0.45, contrast=0.6, counter=-0.2),
            "belly": Mat(belly, "fur", base=0.6),
            "nose": NOSE,
            "claw": CLAW,
        }

        def decor(ctx, eye=eye):
            beast_eyes(ctx, eye, glow=False, row=0.3, inset=0.16)
            if ctx.is_("snout"):
                ctx.mouth("front", ctx.size("front")[1] - 1, (26, 20, 18))
            if ctx.is_("jaw"):
                ctx.hline("top", 0, (230, 225, 210))

        img, glow = paint(lay, mats, decor, seed=11)
        out(name, img)



# ================================================================================================ trolls & giants


@painter("troll")
def troll():
    lay = load("troll")
    variants = {
        "troll": (pal("#5e5852", "#8a8378", "#b1a99a", "#d2cbbc", "#ece6d8"), pal("#2a2624", "#3e3834", "#57504a", "#6e665e"),
                  (255, 140, 40)),
        "troll_frost": (pal("#7d8996", "#a8b6c4", "#cdd8e2", "#e6eef4", "#fbfdff"), pal("#33404c", "#4a5a68", "#647686", "#7f92a2"),
                        (120, 220, 255)),
    }
    for name, (fur, hide, eye) in variants.items():
        mats = {
            "fur": Mat(fur, "hair", base=0.55, counter=0.25, contrast=0.55),
            "mane": Mat(shift(fur, mul=0.95), "hair", base=0.6, scale=1.4, contrast=0.7),
            "face": Mat(hide, "skin", base=0.55),
            "hide": Mat(hide, "skin", base=0.5),
            "tooth": TOOTH,
        }

        def decor(ctx, eye=eye):
            if ctx.is_("head"):
                fw, fh = ctx.size("front")
                # three eyes: two low, one centred above
                ctx.eye("front", 1, 2, eye, glow=True)
                ctx.eye("front", fw - 2, 2, eye, glow=True)
                ctx.eye("front", fw // 2 - 1, 0, eye, glow=True)
                ctx.mouth("front", fh - 1, (30, 20, 18))
                ctx.put("front", fw // 2 - 1, 3, (40, 34, 30))
                ctx.put("front", fw // 2, 3, (40, 34, 30))
            if ctx.is_("jaw"):
                fw, _ = ctx.size("front")
                ctx.hline("front", 0, (220, 214, 196))
            if ctx.is_("hand", "foot"):
                fw, fh = ctx.size("front")
                for x in range(0, fw, 2):
                    ctx.put("front", x, fh - 1, (30, 26, 22))

        img, glow = paint(lay, mats, decor, seed=21)
        out(name, img, glow)


@painter("giant")
def giant():
    lay = load("giant")
    mats = {
        "skin": Mat(pal("#5c3e2c", "#82593e", "#a67a56", "#c49a72", "#dcb890"), "skin", base=0.55, counter=-0.1),
        "hair": Mat(pal("#1e1712", "#3a2c20", "#56432f", "#6e5840"), "hair", base=0.5),
        "loincloth": Mat(pal("#3a2a1c", "#5a4430", "#7a5e42", "#987a58"), "leather", base=0.55),
        "wood": Mat(pal("#2e2016", "#4a3524", "#664a32", "#806040"), "bark", base=0.55),
        "bone": Mat(pal("#8a8070", "#b8ae98", "#ddd4bc", "#f4eedc"), "bone", base=0.6),
    }

    def decor(ctx):
        if ctx.is_("head"):
            fw, fh = ctx.size("front")
            ctx.eye("front", 1, 3, (40, 30, 24), socket=True)
            ctx.eye("front", fw - 2, 3, (40, 30, 24), socket=True)
            ctx.hline("front", fh - 2, (60, 36, 28), 2, fw - 2)
            for x in range(fw):
                ctx.tint("front", x, 2, 0.8)
        if ctx.is_("torso"):
            fw, fh = ctx.size("front")
            # war paint stripes
            for y in range(3, fh - 3, 4):
                ctx.hline("front", y, (110, 40, 30), 2, fw - 2)

    img, glow = paint(lay, mats, decor, seed=31)
    out("giant", img, glow)



# ================================================================================================ dragons

DRAGONS = {
    # name: (scales, belly, membrane, horn, eye)
    "dragon": ("#2b271d #474131 #696047 #8b8063 #ada17c", "#5a5038 #857754 #ad9e74 #cfc29a", "#2e1c14 #4a2e20 #6a4430 #8a5e44",
               "#6a6150 #9a8f78 #c9bea4 #ece4ce", (255, 196, 64)),
    "blood": ("#240e0c #451a15 #6f281f #973d2f #bb5e46", "#6a3820 #94542e #bc7a44 #dca064", "#2a0c0a #4a1612 #6c241c #8c3628",
              "#5a4a3c #887260 #b49c84 #dcc8ae", (255, 150, 40)),
    "frost": ("#4e5e6c #7f92a2 #adbfcc #d6e2ea #f4f8fb", "#8a9aa8 #b4c4d0 #d8e4ec #f2f8fb", "#5a6a7a #7c8e9e #a2b4c4 #c8d8e4",
              "#8a96a0 #b4c0c8 #d8e0e6 #f4f8fa", (140, 220, 255)),
    "elder": ("#21261f #374034 #515d48 #6e7a62 #929c84", "#4e5240 #727660 #989c80 #bcc0a2", "#1e221c #343a30 #4c5446 #66705e",
              "#6c6a5a #9a9682 #c4c0aa #e4e0cc", (230, 230, 120)),
    "ancient": ("#2a210e #54411c #7f622a #a98740 #d0ae5e", "#6a5428 #977a3a #c2a154 #e2c47a", "#2e2010 #4c361c #6c4e2a #8e6a3c",
                "#7a6a48 #a8946a #d2bc92 #f0deb8", (255, 220, 90)),
    "revered": ("#1c121e #342334 #53364c #744f66 #987588", "#4c3444 #6e4c62 #946a84 #b88ea6", "#1a0e1a #2c1a2a #42283e #5a3854",
                "#8a8486 #b2acae #d6d2d2 #f4f2f2", (230, 120, 255)),
    "legendary": ("#0e141c #1b2734 #304559 #4a6680 #7393ad", "#2c3c4c #48607a #6a86a2 #92aec6", "#0c1218 #18222e #283848 #3a5068",
                  "#6c7c8c #94a6b6 #bccad6 #e2ecf2", (120, 200, 255)),
    "serpentine": ("#102c26 #1c483f #2b6a5a #438c76 #69ae95", "#3e6a50 #5c8e6c #80b08c #a6d2ae", "#0e2420 #1a3a32 #2a5446 #3c705c",
                   "#6c7a64 #96a488 #c0ccac #e2ecd0", (180, 255, 140)),
    "alduin": ("#080606 #15100e #261b17 #3a2a23 #553d33", "#2a1c16 #46302a #62463c #7e5c4e", "#100808 #1e0e0c #301612 #44201a",
               "#1a1614 #2e2824 #463c36 #5e5048", (255, 50, 30)),
    "paarthurnax": ("#46453f #6a6861 #8f8c84 #b4b0a6 #d6d2c6", "#7a7466 #a09886 #c2baa6 #e0d8c6", "#3a3632 #56504a #746c64 #948a80",
                    "#8a8270 #b0a690 #d2c8b0 #ece4cc", (255, 200, 90)),
    "odahviing": ("#33100a #631f12 #93331c #bd512d #dc7647", "#7a4426 #a46236 #c8844c #e4a868", "#2c0e0a #4c1a12 #70281c #94382a",
                  "#5c4c3e #8a7460 #b49c84 #dac6aa", (255, 170, 50)),
}


def _p(s):
    return pal(*s.split())


def wing_shape(ctx):
    """Cuts a membrane plane into a wing: the trailing edge (row 0) sweeps from the tip (column 0) to the body,
    scalloped between the finger spars, with a few ragged nicks."""
    import math as _m
    tag = ctx.tag
    for face in ("top", "bottom"):
        fw, fh = ctx.size(face)
        for x in range(fw):
            u = x / max(1, fw - 1)
            if tag == "membrane_inner":
                f = 0.72 + 0.28 * u
            elif tag == "membrane_mid":
                f = 0.5 + 0.5 * u - 0.1 * abs(_m.sin(_m.pi * u))
            else:
                f = 0.12 + 0.88 * (u ** 0.85) - 0.09 * abs(_m.sin(3 * _m.pi * u))
            cut = int(round(fh * (1 - f)))
            if ctx.rnd.random() < 0.12:
                cut += 1
            for y in range(min(fh, cut)):
                ctx.put(face, x, y, (0, 0, 0, 0))
            # darker hem along the trailing edge
            if cut < fh:
                ctx.tint(face, x, cut, 0.7)


@painter("dragon")
def dragon():
    lay = load("dragon")
    for name, (sc, be, mem, horn, eye) in DRAGONS.items():
        mats = {
            "scales": Mat(_p(sc), "scales", base=0.55, belly=_p(be), dorsal=0.1, counter=0.2, scale=1.0),
            "belly": Mat(_p(be), "chitin", base=0.55, scale=0.6, contrast=0.6),
            "membrane": Mat(_p(mem), "membrane", base=0.55),
            "wingbone": Mat(_p(sc), "scales", base=0.42, scale=0.7),
            "horn": Mat(_p(horn), "horn", base=0.55),
            "tooth": TOOTH,
        }

        def decor(ctx, eye=eye):
            if ctx.is_("head"):
                for face in ("right", "left"):
                    fw, fh = ctx.size(face)
                    ex = fw - 3 if face == "right" else 1
                    ctx.eye(face, ex, 1, eye, glow=True)
                    ctx.eye(face, ex + 1, 1, eye, glow=True)
                    ctx.tint(face, ex, 0, 0.6)
                    ctx.tint(face, ex + 1, 0, 0.6)
            if ctx.is_("snout"):
                fw, fh = ctx.size("front")
                ctx.put("front", 1, 0, (20, 14, 12))
                ctx.put("front", fw - 2, 0, (20, 14, 12))
            if ctx.tag.startswith("membrane"):
                wing_shape(ctx)

        img, glow = paint(lay, mats, decor, seed=41)
        out("dragon" if name == "dragon" else "dragon_" + name, img, glow)

    # skeletal dragon (and burned corpses): bone frame, ribs, tattered membranes, hollow eye sockets
    bone = pal("#4e4638", "#7c7260", "#a89c84", "#d0c6ac", "#ece6d2")
    mats = {
        "scales": Mat(bone, "bone", base=0.6),
        "belly": Mat(bone, "bone", base=0.5),
        "membrane": Mat(pal("#2a221c", "#40362c", "#5a4c3e"), "membrane", base=0.5),
        "wingbone": Mat(bone, "bone", base=0.55),
        "horn": Mat(pal("#3a342c", "#5e5648", "#867c68", "#aaa088"), "horn", base=0.5),
        "tooth": TOOTH,
    }

    def skel(ctx):
        tag = ctx.tag
        if tag in ("torso", "chest", "tail", "neck", "belly", "neck_belly", "thigh", "shin"):
            for face in ctx.all_faces():
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        spine = face == "top" and x == fw // 2
                        rib = (x % 3 == 0) if face in ("right", "left", "top", "bottom") else (y % 3 == 0)
                        if tag in ("tail", "neck") and face in ("front", "back"):
                            rib = True
                        if not (rib or spine):
                            ctx.put(face, x, y, (0, 0, 0, 0))
        if tag.startswith("membrane"):
            for face in ("top", "bottom"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    for x in range(fw):
                        if ctx.rnd.random() < 0.82:
                            ctx.put(face, x, y, (0, 0, 0, 0))
        if tag == "head":
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                ex = fw - 3 if face == "right" else 1
                ctx.rect(face, ex, 1, 2, 2, (10, 8, 6))
                ctx.put(face, ex, 1, (90, 200, 255), glow=True)

    img, glow = paint(lay, mats, skel, seed=43)
    out("dragon_skeletal", img, glow)



# ================================================================================================ humanoid costumes

IRON = pal("#26231f", "#3e3a33", "#5c564c", "#7e776a", "#a49c8c")
WRAP = pal("#3a342a", "#564d3e", "#73684f", "#8f8463", "#aaa07e")
BONE = pal("#6e6450", "#9a8e74", "#c4b89a", "#e2d8bc")


@painter("costume_draugr")
def costume_draugr():
    lay = load("costume_draugr")
    mats = {
        "iron": Mat(IRON, "metal", base=0.5, scale=0.7, contrast=0.8),
        "wrap": Mat(WRAP, "cloth", base=0.5),
        "bone": Mat(BONE, "horn", base=0.55),
        "gold": Mat(pal("#5a4210", "#8e6a1e", "#bf9634", "#e2c060"), "metal", base=0.55, scale=0.6),
        "fur": Mat(pal("#2a241e", "#463c32", "#665848", "#86765e"), "hair", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("faceguard"):
            fw, fh = ctx.size("front")
            for x in (2, 3, fw - 4, fw - 3):
                ctx.put("front", x, 1, (8, 8, 10))
            for y in range(2, fh, 2):
                ctx.hline("front", y, (30, 28, 24), 2, fw - 2)
        if ctx.is_("rim", "crown"):
            fw, _ = ctx.size("front")
            for x in range(0, fw, 2):
                ctx.tint("front", x, 0, 1.25)
        if ctx.is_("hood"):
            fw, fh = ctx.size("front")
            for x in range(fw):
                if ctx.rnd.random() < 0.3:
                    ctx.put("front", x, fh - 1, (0, 0, 0, 0))

    img, _ = paint(lay, mats, decor, seed=61)
    out("costume_draugr", img)


@painter("costume_deathlord")
def costume_deathlord():
    lay = load("costume_deathlord")
    mats = {
        "plate": Mat(pal("#0c0c10", "#1a1a22", "#2c2c38", "#44445a", "#64647e"), "metal", base=0.45, scale=0.8),
        "horn": Mat(pal("#1a1612", "#30281f", "#4c3f30", "#6a5a46"), "horn", base=0.5),
        "fur": Mat(pal("#141210", "#28241e", "#3e3830", "#585044"), "hair", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("faceguard"):
            fw, fh = ctx.size("front")
            for x in (2, 3, fw - 4, fw - 3):
                ctx.put("front", x, 1, (90, 200, 255), glow=True)
        if ctx.is_("cuirass"):
            fw, fh = ctx.size("front")
            for y in range(1, fh, 3):
                ctx.hline("front", y, (60, 140, 200), 1, fw - 1)

    img, _ = paint(lay, mats, decor, seed=62)
    out("costume_deathlord", img)


@painter("costume_falmer")
def costume_falmer():
    lay = load("costume_falmer")
    mats = {
        "chitin": Mat(pal("#2a1e10", "#4a3418", "#6e4e22", "#9a7032", "#c49a50"), "chitin", base=0.55, scale=0.8),
        "skin": Mat(pal("#6a6058", "#8e8478", "#b0a698", "#cec6b8"), "skin", base=0.55),
        "leather": Mat(pal("#2e2218", "#4a3828", "#66503a", "#82684c"), "leather", base=0.5),
    }

    def decor(ctx):
        if ctx.is_("brow"):
            ctx.hline("front", ctx.size("front")[1] - 1, (20, 14, 8))

    img, _ = paint(lay, mats, decor, seed=63)
    out("costume_falmer", img)


@painter("costume_forsworn")
def costume_forsworn():
    lay = load("costume_forsworn")
    mats = {
        "hide": Mat(pal("#3e2a1a", "#5e4028", "#7e5a3a", "#9e7650"), "leather", base=0.5),
        "bone": Mat(BONE, "horn", base=0.6),
        "feather": Mat(pal("#1a1414", "#3a2a22", "#7a4a2a", "#c8783a"), "feather", base=0.55, scale=0.6),
        "fur": Mat(pal("#2e2620", "#4e4236", "#70604e", "#948068"), "hair", base=0.55),
    }

    def decor(ctx):
        if ctx.is_("band"):
            fw, fh = ctx.size("front")
            for x in range(0, fw, 3):
                ctx.put("front", x, fh // 2, (140, 40, 30))
        if ctx.is_("feather"):
            for face in ("right", "left"):
                fw, fh = ctx.size(face)
                for y in range(fh):
                    if (y + fw) % 2 == 0:
                        ctx.put(face, 0, y, (0, 0, 0, 0))

    img, _ = paint(lay, mats, decor, seed=64)
    out("costume_forsworn", img)


@painter("costume_vampire")
def costume_vampire():
    lay = load("costume_vampire")
    mats = {
        "collar": Mat(pal("#0c080a", "#1c1216", "#2e1c24", "#462a36"), "leather", base=0.5),
        "cape": Mat(pal("#1a0408", "#32080e", "#4e0e16", "#6e1620", "#902030"), "cloth", base=0.5),
        "trim": Mat(pal("#3a2a10", "#6a4c1c", "#9a742c", "#c8a048"), "metal", base=0.5, scale=0.6),
    }

    def decor(ctx):
        if ctx.is_("cape"):
            for face in ("top", "bottom"):
                pass
            fw, fh = ctx.size("front")
            for x in range(fw):
                cut = ctx.rnd.randint(0, 2) if x % 3 == 0 else 0
                for y in range(fh - cut, fh):
                    ctx.put("front", x, y, (0, 0, 0, 0))
                    ctx.put("back", x, y, (0, 0, 0, 0))

    img, _ = paint(lay, mats, decor, seed=65)
    out("costume_vampire", img)


@painter("costume_necromancer")
def costume_necromancer():
    lay = load("costume_necromancer")
    mats = {
        "robe": Mat(pal("#0c0a10", "#1a1622", "#2a2236", "#3e324e", "#564668"), "cloth", base=0.5),
        "trim": Mat(pal("#2a0c2e", "#46164c", "#62226a", "#80348a"), "cloth", base=0.5),
        "bone": Mat(BONE, "bone", base=0.6),
    }

    def decor(ctx):
        if ctx.is_("skull"):
            fw, fh = ctx.size("front")
            ctx.put("front", 0, 1, (20, 16, 12))
            ctx.put("front", fw - 1, 1, (20, 16, 12))
            ctx.put("front", fw // 2, fh - 1, (20, 16, 12))
        if ctx.is_("hood"):
            # the hood opening: dark inside the face front
            fw, fh = ctx.size("front")
            for y in range(2, fh):
                for x in range(1, fw - 1):
                    ctx.put("front", x, y, (0, 0, 0, 0))

    img, _ = paint(lay, mats, decor, seed=66)
    out("costume_necromancer", img)


@painter("vampire_eyes")
def vampire_eyes():
    from PIL import Image as _I
    img = _I.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = img.load()
    for x in (9, 10, 13, 14):
        px[x, 12] = (255, 40, 30, 255)
    save(img, "creatures/vampire_eyes.png")


# ================================================================================================ main


def main(names):
    todo = names or list(PAINTERS)
    for n in todo:
        PAINTERS[n]()
        print("painted", n)


if __name__ == "__main__":
    main(sys.argv[1:])
