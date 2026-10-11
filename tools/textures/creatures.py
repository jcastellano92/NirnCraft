"""Generates the creatures module's entity textures (PIL).

Writes PNGs into mod/src/main/resources/assets/skycraft/textures/entity/:
  bandit/bandit_0..3.png, bandit/bandit_chief.png       64x64 player-layout skins
  draugr/draugr_0..1.png, draugr/draugr_deathlord.png   64x64 (+ draugr_eyes.png glow layer)
  guard/guard_<hold>.png                               64x64, one tunic color per hold
  (giants, skeevers, trolls and dragons are rig models now: bestiary.py and bestiary_fauna.py paint them)

Every model box is painted face by face from the same UV layout as the Java models, so details (eyes, belly
plates, teeth, membranes) land on the right faces. Run:  python3 tools/textures/creatures.py
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures", "entity")


# ---------------------------------------------------------------------------------------------------- color helpers

def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3])


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def jitter(c, rnd, amt):
    d = rnd.uniform(-amt, amt)
    return tuple(clamp(x + d + rnd.uniform(-amt * 0.3, amt * 0.3)) for x in c[:3])


def rgba(c, a=255):
    if c is None:
        return (0, 0, 0, 0)
    if len(c) == 4:
        return c
    return (c[0], c[1], c[2], a)


def box_faces(u, v, w, h, d):
    """UV rectangles of a Minecraft model box (texOffs u,v; size w,h,d). top = visually up, front = -z."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


FACE_LIGHT = {"top": 1.06, "front": 1.0, "right": 0.93, "left": 0.93, "back": 0.88, "bottom": 0.78}


def paint_box(img, u, v, w, h, d, fn, rnd, noise=6, light=True, edges=True):
    """fn(face, x, y, fw, fh) -> color (rgb / rgba) or None (transparent / untouched)."""
    W, H = img.size
    for face, (x0, y0, fw, fh) in box_faces(u, v, w, h, d).items():
        for y in range(fh):
            for x in range(fw):
                px, py = x0 + x, y0 + y
                if px >= W or py >= H:
                    continue
                c = fn(face, x, y, fw, fh)
                if c is None:
                    continue
                alpha = c[3] if len(c) == 4 else 255
                c = c[:3]
                if light:
                    c = shade(c, FACE_LIGHT[face])
                if edges and (x == 0 or x == fw - 1 or y == fh - 1) and face not in ("top",):
                    c = shade(c, 0.9)
                if noise:
                    c = jitter(c, rnd, noise)
                img.putpixel((px, py), rgba(c, alpha))


def save(img, *path):
    p = os.path.join(OUT, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    print("wrote", os.path.relpath(p, ROOT))


# ---------------------------------------------------------------------------------------------------- humanoid skins

# name: (u, v, w, h, d, height of the part's top in "body pixels" (feet = 0, head top = 32), kind)
BASE_PARTS = {
    "head": (0, 0, 8, 8, 8, 32, "head"),
    "body": (16, 16, 8, 12, 4, 24, "body"),
    "rarm": (40, 16, 4, 12, 4, 24, "arm"),
    "larm": (32, 48, 4, 12, 4, 24, "arm"),
    "rleg": (0, 16, 4, 12, 4, 12, "leg"),
    "lleg": (16, 48, 4, 12, 4, 12, "leg"),
}
OVER_PARTS = {
    "head": (32, 0, 8, 8, 8, 32, "head"),
    "body": (16, 32, 8, 12, 4, 24, "body"),
    "rarm": (40, 32, 4, 12, 4, 24, "arm"),
    "larm": (48, 48, 4, 12, 4, 24, "arm"),
    "rleg": (0, 32, 4, 12, 4, 12, "leg"),
    "lleg": (0, 48, 4, 12, 4, 12, "leg"),
}


class P:
    """A pixel query: which part/face, local x/y on the face, and the height above the feet in body pixels."""
    __slots__ = ("part", "kind", "face", "x", "y", "fw", "fh", "h", "side")

    def __init__(self, part, kind, face, x, y, fw, fh, h):
        self.part, self.kind, self.face, self.x, self.y, self.fw, self.fh, self.h = part, kind, face, x, y, fw, fh, h
        self.side = face in ("left", "right")


def paint_humanoid(img, base_fn, over_fn, rnd, noise=7):
    for parts, fn, is_over in ((BASE_PARTS, base_fn, False), (OVER_PARTS, over_fn, True)):
        if fn is None:
            continue
        for name, (u, v, w, h, d, top, kind) in parts.items():
            def f(face, x, y, fw, fh, name=name, kind=kind, top=top, h=h):
                if face == "top":
                    hh = top
                elif face == "bottom":
                    hh = top - h
                else:
                    hh = top - y - 0.5
                return fn(P(name, kind, face, x, y, fw, fh, hh))
            paint_box(img, u, v, w, h, d, f, rnd, noise=noise, edges=not is_over)


SKIN = {
    "nord": hexc("e0b79a"), "pale": hexc("e8cbb6"), "tan": hexc("c69072"), "dark": hexc("8a5a3e"),
    "giant": hexc("cdb69c"), "withered": hexc("6f7a78"), "withered2": hexc("5e6b72"),
}
HAIR = {"blond": hexc("c8a462"), "brown": hexc("5a3a22"), "black": hexc("2a2220"), "red": hexc("8a3a1e"),
        "grey": hexc("9a968e"), "white": hexc("d8d4cc")}


def face_features(p, skin, hair, beard=None, eye=hexc("3a5a7a"), brows=True):
    """Head base layer: hair on top/back/upper sides, a face on the front."""
    if p.face == "top":
        return hair
    if p.face == "bottom":
        return shade(skin, 0.8) if beard is None else beard
    if p.face == "back":
        return hair if p.y < 7 else shade(skin, 0.85)
    if p.side:
        if p.y < 3:
            return hair
        if beard is not None and p.y >= 5 and (p.x >= 4 if p.face == "right" else p.x <= 3):
            return beard
        if p.y < 5 and (p.x <= 2 if p.face == "right" else p.x >= 5):
            return hair
        return shade(skin, 0.95)
    # front
    x, y = p.x, p.y
    if y <= 1:
        return hair
    if y == 2 and (x == 0 or x == 7):
        return hair
    if brows and y == 3 and x in (1, 2, 5, 6):
        return shade(hair, 0.8)
    if y == 4:
        if x in (1, 6):
            return hexc("eeeeee")
        if x in (2, 5):
            return eye
    if y == 5 and x in (3, 4):
        return shade(skin, 0.82)  # nose shadow
    if beard is not None and y >= 5:
        if y == 6 and x in (3, 4):
            return shade(beard, 0.6)  # mouth
        if y >= 6 or x in (0, 1, 6, 7):
            return beard
    if y == 6 and x in (2, 3, 4, 5):
        return shade(skin, 0.7)
    if y == 7:
        return shade(skin, 0.9)
    return skin


def limb_skin(p, skin):
    return shade(skin, 0.95 if p.side else 1.0)


# ---- bandits

BANDIT_LOOKS = [
    # skin, hair, beard, leather, pants, hood
    dict(skin="nord", hair="brown", beard="brown", leather=hexc("5a3a22"), dark=hexc("3a2616"), pants=hexc("4a3e34"), hood="hood"),
    dict(skin="tan", hair="black", beard=None, leather=hexc("6b4a2e"), dark=hexc("3e2a18"), pants=hexc("3c3a36"), hood="fur"),
    dict(skin="pale", hair="red", beard="red", leather=hexc("4a3526"), dark=hexc("2c1f14"), pants=hexc("524436"), hood="mask"),
    dict(skin="dark", hair="black", beard="black", leather=hexc("55463a"), dark=hexc("30261e"), pants=hexc("3a3028"), hood="paint"),
]


def bandit_skin(look, rnd, chief=False):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    skin = SKIN[look["skin"]]
    hair = HAIR[look["hair"]]
    beard = HAIR[look["beard"]] if look["beard"] else None
    leather, dark, pants = look["leather"], look["dark"], look["pants"]
    fur = hexc("cfc6b4") if look["hood"] != "fur" else hexc("8a7a62")
    steel = hexc("8c9196")

    def base(p):
        if p.kind == "head":
            c = face_features(p, skin, hair, beard)
            if look["hood"] == "paint" and p.face == "front" and p.y in (3, 4, 5) and p.x in (0, 1, 2) and c == skin:
                return hexc("7a1e1e")  # war paint
            return c
        if p.kind == "body":
            if 11.0 <= p.h <= 13.0:
                if p.face == "front" and p.x in (3, 4):
                    return hexc("b89a50")  # buckle
                return dark
            if chief:
                if p.h > 21:
                    return fur
                if p.face == "front" and (p.x in (0, 7) or p.y in (3, 6)):
                    return shade(steel, 0.75)
                return steel
            # leather cuirass with a diagonal strap
            if p.face in ("front", "back") and (p.x + p.y) % 9 in (0, 1):
                return dark
            if p.face == "front" and p.y in (4, 5) and p.x in (1, 2, 5, 6):
                return shade(leather, 1.2)  # stitched panels
            return leather
        if p.kind == "arm":
            if p.h > 19:
                return steel if chief else leather
            if p.h > 14:
                return limb_skin(p, skin) if not chief else shade(steel, 0.9)
            if p.h > 12.5:
                return dark  # bracer
            return limb_skin(p, skin)
        if p.kind == "leg":
            if p.h < 4:
                return hexc("3a2a1c") if p.h > 1 else hexc("2a1e14")  # boots
            if chief and p.h > 6:
                return shade(steel, 0.85)
            return pants if not (p.face == "front" and p.x == 0) else shade(pants, 0.8)
        return skin

    def over(p):
        if p.kind == "head":
            if look["hood"] in ("hood", "mask") or chief:
                hood = shade(leather, 0.85) if not chief else steel
                if p.face == "top":
                    return hood
                if p.face in ("back", "left", "right"):
                    if chief and p.side and p.y in (0, 1) and p.x in (3, 4):
                        return hexc("d8cfb0")  # little horns
                    return hood if p.y < 7 or not chief else None
                if p.face == "front":
                    if p.y <= 1 or (p.x in (0, 7) and p.y <= 5):
                        return hood
                    if look["hood"] == "mask" and p.y >= 5:
                        return hexc("3a2e28")
                    if chief and p.x in (3, 4) and p.y <= 5:
                        return shade(steel, 0.8)  # nose guard
                return None
            if look["hood"] == "fur":
                if p.face != "bottom" and p.y in (1, 2) and p.face != "top":
                    return fur  # fur headband
            return None
        if p.kind == "body":
            if p.h > 22 and p.face != "bottom":
                return fur  # fur collar
            return None
        if p.kind == "arm":
            if p.h > 21 and p.face != "bottom":
                return fur if look["hood"] in ("fur", "hood") or chief else None  # fur pauldrons
            return None
        if p.kind == "leg":
            if 3 <= p.h < 4.5:
                return shade(fur, 0.9)  # fur boot tops
            return None
        return None

    paint_humanoid(img, base, over, rnd)
    return img


# ---- draugr

def draugr_skin(variant, rnd, deathlord=False):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    skin = SKIN["withered"] if variant == 0 else SKIN["withered2"]
    bone = hexc("b8b098")
    wrap = hexc("4a4238")
    bronze = hexc("7a6a48") if not deathlord else hexc("3a3f44")
    bronze_dark = shade(bronze, 0.65)
    hair = HAIR["white"] if variant == 1 else HAIR["grey"]

    def base(p):
        if p.kind == "head":
            if p.face == "front":
                x, y = p.x, p.y
                if y == 4 and x in (1, 2, 5, 6):
                    return hexc("101418") if x in (1, 6) else hexc("8fd8ff")  # sunken sockets, faint eyes
                if y == 3 and x in (1, 2, 5, 6):
                    return shade(skin, 0.55)
                if y == 5 and x in (3, 4):
                    return hexc("1a1a1a")  # rotted nose
                if y == 6 and 1 <= x <= 6:
                    return bone if x % 2 == 0 else hexc("2a2420")  # bared teeth
                if y == 7 and 2 <= x <= 5:
                    return shade(skin, 0.7)
                if y <= 1:
                    return hair
                c = skin
                if (x * 7 + y * 3) % 5 == 0:
                    c = shade(skin, 0.82)  # withered blotches
                return c
            if p.face == "top" or (p.face == "back" and p.y < 6) or (p.side and p.y < 2):
                return hair
            return shade(skin, 0.92)
        if p.kind == "body":
            if p.face == "front" and 4 <= p.y <= 8 and p.x in (2, 3, 4, 5) and not deathlord:
                return bone if p.y % 2 == 0 else shade(skin, 0.6)  # exposed ribs
            if 11.0 <= p.h <= 12.5:
                return hexc("2c2620")
            return wrap if (p.x + p.y) % 4 else shade(wrap, 0.75)
        if p.kind == "arm":
            if p.h > 20:
                return bronze
            if p.h < 13.5:
                return shade(skin, 0.85)
            return skin if (p.y % 3) else shade(wrap, 0.9)  # wrapped forearms
        if p.kind == "leg":
            if p.h < 3.5:
                return hexc("2e2a26")
            return wrap if (p.y + p.x) % 3 else shade(wrap, 0.8)
        return skin

    def over(p):
        if p.kind == "head":
            if variant == 1 and not deathlord:
                # long white hair hanging at the back and sides
                if p.face == "back" or (p.side and p.y < 7 and (p.x <= 2 if p.face == "right" else p.x >= 5)):
                    return hair
                return None
            helm = bronze
            if p.face == "top":
                return helm
            if p.face in ("back", "left", "right"):
                if deathlord and p.side and p.y <= 2 and p.x in (2, 3, 4, 5):
                    return hexc("d0c8b0") if p.y < 2 else hexc("a09880")  # great horns
                if p.y <= 3 or deathlord:
                    return helm if p.y != 3 else bronze_dark
                return None
            if p.face == "front":
                if p.y <= 2:
                    return helm if p.y < 2 else bronze_dark
                if deathlord and (p.x in (0, 7) or (p.x in (3, 4) and p.y <= 5)):
                    return helm  # face plate edges and nose guard; eyes stay open
                if deathlord and p.y == 7:
                    return bronze_dark
            return None
        if p.kind == "body":
            if deathlord:
                if p.face == "bottom":
                    return None
                return bronze if p.y % 4 else bronze_dark
            if p.h > 21 and p.face != "bottom":
                return bronze  # gorget
            return None
        if p.kind == "arm":
            if (p.h > 20 or (deathlord and p.h > 13)) and p.face != "bottom":
                return bronze if p.y % 3 else bronze_dark
            return None
        if p.kind == "leg":
            if deathlord and p.h > 3 and p.face != "top":
                return bronze if p.y % 4 else bronze_dark
            if 6 < p.h < 11 and p.face == "front" and not deathlord:
                return hexc("5a4a34")  # loincloth flap
            return None
        return None

    paint_humanoid(img, base, over, rnd)
    return img


def draugr_eyes():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    glow, soft = (120, 220, 255, 255), (60, 140, 220, 200)
    # head front face is at (8,8); eye row 4 -> y = 12
    for x in (10, 13):
        img.putpixel((x, 12), glow)
    for x in (9, 14):
        img.putpixel((x, 12), soft)
    return img


# ---- guards

HOLD_COLORS = {
    "whiterun": "c8a23a", "the_rift": "6b3a7a", "eastmarch": "2e4c8a", "the_pale": "5c7a99", "winterhold": "8fa6b8",
    "haafingar": "9a2222", "hjaalmarch": "4e5f3c", "the_reach": "6e2a20", "falkreath": "2f5a2e",
}


def guard_skin(hold, rnd):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    tunic = hexc(HOLD_COLORS[hold])
    trim = shade(tunic, 0.6)
    emblem = mix(tunic, hexc("f0e6c8"), 0.6)
    mail = hexc("8a8e92")
    steel = hexc("9aa0a6")
    leather = hexc("5a3e26")
    skin = SKIN["nord"]

    def base(p):
        if p.kind == "head":
            return face_features(p, skin, HAIR["brown"], HAIR["brown"])
        if p.kind == "body":
            if 11.0 <= p.h <= 12.5:
                return leather if not (p.face == "front" and p.x in (3, 4)) else hexc("b0a070")
            if p.face in ("front", "back"):
                # hold emblem on the chest
                if p.face == "front" and 2 <= p.y <= 7 and 2 <= p.x <= 5:
                    if (p.y in (2, 7) and p.x in (3, 4)) or (3 <= p.y <= 6 and 2 <= p.x <= 5 and not (p.y in (3, 6) and p.x in (2, 5))):
                        return emblem
                if p.x in (0, 7):
                    return trim
                return tunic
            return mail if (p.y + p.x) % 2 else shade(mail, 0.8)
        if p.kind == "arm":
            if p.h > 15:
                return mail if (p.y + p.x) % 2 else shade(mail, 0.8)  # chainmail sleeves
            if p.h > 12.5:
                return leather  # bracers
            return hexc("4a3a2a")  # gloves
        if p.kind == "leg":
            if p.h < 4:
                return hexc("3a2a1c")
            return hexc("4a4038") if p.face != "front" or p.x else hexc("3a322c")
        return skin

    def over(p):
        if p.kind == "head":
            # closed helmet: the face is hidden, only a dark T-shaped visor
            if p.face == "front":
                if p.y == 3 and 1 <= p.x <= 6:
                    return hexc("16181a")
                if p.y in (4, 5) and p.x in (3, 4):
                    return hexc("16181a")
                if p.y == 0 or p.x in (0, 7):
                    return shade(steel, 0.8)
                return steel
            if p.face == "top":
                return steel if (p.x not in (3, 4)) else shade(steel, 1.15)  # crest ridge
            if p.face == "bottom":
                return shade(steel, 0.7)
            return steel if p.y != 7 else shade(steel, 0.75)
        if p.kind == "body":
            if p.h > 22 and p.face != "bottom":
                return hexc("6a5a46")  # leather collar
            return None
        if p.kind == "leg":
            # tunic skirt over the thighs
            if p.h > 7 and p.face != "top" and p.face != "bottom":
                return trim if p.h < 8 else tunic
            return None
        if p.kind == "arm":
            if p.h > 21 and p.face != "bottom":
                return steel  # pauldrons
            return None
        return None

    paint_humanoid(img, base, over, rnd)
    return img


# ---- giant

def main():
    for i, look in enumerate(BANDIT_LOOKS):
        save(bandit_skin(look, random.Random(100 + i)), "bandit", "bandit_%d.png" % i)
    chief = dict(skin="nord", hair="blond", beard="blond", leather=hexc("4a3526"), dark=hexc("2a1e14"),
                 pants=hexc("3a3430"), hood="hood")
    save(bandit_skin(chief, random.Random(150), chief=True), "bandit", "bandit_chief.png")

    for i in range(2):
        save(draugr_skin(i, random.Random(200 + i)), "draugr", "draugr_%d.png" % i)
    save(draugr_skin(0, random.Random(250), deathlord=True), "draugr", "draugr_deathlord.png")
    save(draugr_eyes(), "draugr", "draugr_eyes.png")

    for i, hold in enumerate(HOLD_COLORS):
        save(guard_skin(hold, random.Random(300 + i)), "guard", "guard_%s.png" % hold)



if __name__ == "__main__":
    main()
