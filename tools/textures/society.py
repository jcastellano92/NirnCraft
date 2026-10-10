"""Generates the society module's textures (PIL).

Writes PNGs into mod/src/main/resources/assets/skycraft/textures/entity/society/:
  <role>_0.png, <role>_1.png         64x64 player-layout skins for every NPC role (0 = male, 1 = female); fallback only
  cosmetic/<id>.png                  64x64 faction regalia (CosmeticLayer UVs: hood, torso, tabard panels, cloak, pelts)
  npc/...                            the layers NpcSkins composites into race-correct townsfolk (see "NPC layers")

Every model box is painted face by face from the same UV layout as the Java models. Run:
  python3 tools/textures/society.py
"""
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures", "entity", "society")


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


def box_faces(u, v, w, h, d):
    """UV rectangles of a Minecraft model box (texOffs u,v; size w,h,d)."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


FACE_LIGHT = {"top": 1.06, "front": 1.0, "right": 0.93, "left": 0.93, "back": 0.9, "bottom": 0.8}


def paint_box(img, u, v, w, h, d, fn, rnd, noise=6, edges=True):
    """fn(face, x, y, fw, fh) -> rgb / rgba tuple, or None for transparent."""
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
                c = shade(c[:3], FACE_LIGHT[face])
                if edges and (x == 0 or x == fw - 1 or y == fh - 1) and face != "top":
                    c = shade(c, 0.9)
                if noise:
                    c = jitter(c, rnd, noise)
                img.putpixel((px, py), (c[0], c[1], c[2], alpha))


def save(img, *path):
    p = os.path.join(OUT, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    print("wrote", os.path.relpath(p, ROOT))


# ---------------------------------------------------------------------------------------------------- humanoid layout

# name: (u, v, w, h, d, height of the part's top above the feet in pixels, kind)
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
    __slots__ = ("part", "kind", "face", "x", "y", "fw", "fh", "h", "side")

    def __init__(self, part, kind, face, x, y, fw, fh, h):
        self.part, self.kind, self.face, self.x, self.y, self.fw, self.fh, self.h = part, kind, face, x, y, fw, fh, h
        self.side = face in ("left", "right")


def paint_humanoid(img, base_fn, over_fn, rnd, noise=7):
    for parts, fn, is_over in ((BASE_PARTS, base_fn, False), (OVER_PARTS, over_fn, True)):
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


SKIN = {"nord": hexc("e0b79a"), "pale": hexc("e8cbb6"), "tan": hexc("c69072"), "dark": hexc("8a5a3e"),
        "altmer": hexc("e2c27a"), "dunmer": hexc("7c8a92"), "vampire": hexc("d8d2cc"), "corpse": hexc("b8b0a8")}
HAIR = {"blond": hexc("c8a462"), "brown": hexc("5a3a22"), "black": hexc("2a2220"), "red": hexc("8a3a1e"),
        "grey": hexc("9a968e"), "white": hexc("d8d4cc"), "auburn": hexc("7a4024"), "gold": hexc("d8b860")}


def face(p, L):
    """Head base layer: hair, face, beard, long hair for women."""
    skin, hair, beard, eye = L["skin"], L["hair"], L.get("beard"), L.get("eye", hexc("3a5a7a"))
    female = L.get("female", False)
    if p.face == "top":
        return hair
    if p.face == "bottom":
        return shade(skin, 0.8) if beard is None else beard
    if p.face == "back":
        return hair if (p.y < 7 or female) else shade(skin, 0.85)
    if p.side:
        if p.y < 3 or (female and (p.x <= 3 if p.face == "right" else p.x >= 4)):
            return hair
        if beard is not None and p.y >= 5 and (p.x >= 4 if p.face == "right" else p.x <= 3):
            return beard
        if p.y < 5 and (p.x <= 2 if p.face == "right" else p.x >= 5):
            return hair
        return shade(skin, 0.95)
    x, y = p.x, p.y
    if y <= 1:
        return hair
    if y == 2 and (x == 0 or x == 7):
        return hair
    if female and x in (0, 7) and y <= 6:
        return hair
    if y == 3 and x in (1, 2, 5, 6):
        return shade(hair, 0.8)
    if y == 4:
        if x in (1, 6):
            return hexc("eeeeee") if not L.get("red_eyes") else hexc("f0d8d0")
        if x in (2, 5):
            return eye
    if L.get("paint") and y in (3, 4, 5) and x in (0, 1, 2) and not (y == 4 and x in (1, 2)):
        return L["paint"]
    if y == 5 and x in (3, 4):
        return shade(skin, 0.82)
    if beard is not None and y >= 5:
        if y == 6 and x in (3, 4):
            return shade(beard, 0.6)
        if y >= 6 or x in (0, 1, 6, 7):
            return beard
    if y == 6 and x in (2, 3, 4, 5):
        return shade(skin, 0.7) if not female else hexc("b06058")
    if y == 7:
        return shade(skin, 0.9)
    return skin


def stripes(p, a, b, period=4):
    return a if (p.y // 2 + p.x // 2) % period else b


def humanoid(L, rnd, clothes_only=False):
    """Paints a skin from a look dict (see LOOKS). clothes_only leaves skin and face transparent (NPC outfit layers)."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    skin = L.get("skin", SKIN["nord"])
    tunic = L["tunic"]
    trim = L.get("trim", shade(tunic, 0.7))
    pants = L.get("pants", hexc("4a4038"))
    boots = L.get("boots", hexc("3a2a1c"))
    belt = L.get("belt", hexc("4a3020"))
    robe = L.get("robe", False)
    cuirass = L.get("cuirass")
    sleeves = L.get("sleeves", "long")
    gloves = L.get("gloves")
    bare = L.get("bare_chest", False)

    def limb(p, skin):
        return None if clothes_only else _limb(p, skin)

    def base(p):
        if p.kind == "head":
            return None if clothes_only else face(p, L)
        if p.kind == "body":
            if not robe and 11.0 <= p.h <= 12.6:
                return hexc("b89a50") if (p.face == "front" and p.x in (3, 4)) else belt
            if bare and p.h < 22:
                if (p.face in ("front", "back")) and (p.x + p.y) % 8 in (0, 1):
                    return L.get("strap", hexc("6a5a46"))
                return None if clothes_only else shade(skin, 0.97)
            if cuirass is not None and p.h > 12.5:
                c = cuirass
                if p.face == "front" and p.y in (3, 7):
                    return shade(c, 0.7)
                if p.face == "front" and p.x in (0, 7):
                    return shade(c, 0.85)
                return c
            if L.get("sash") and p.face in ("front", "back") and (p.x + p.y) % 9 in (0, 1, 2):
                return L["sash"]
            if L.get("apron") and p.face == "front" and p.y >= 3:
                return L["apron"] if p.x not in (0, 7) else shade(L["apron"], 0.85)
            if L.get("emblem") and p.face == "front" and 2 <= p.y <= 5 and 2 <= p.x <= 5:
                if (p.x + p.y) % 2 == 0 or p.y in (2, 5):
                    return L["emblem"]
            if p.face == "front":
                if p.y <= 1 and p.x in (3, 4) and not robe and not L.get("hood"):
                    return None if clothes_only else shade(skin, 0.95)  # neckline
                if robe and p.x in (3, 4):
                    return trim  # robe opening
                if p.x in (0, 7):
                    return trim
            if L.get("plaid"):
                return stripes(p, tunic, shade(tunic, 0.78))
            if L.get("rags") and rnd.random() < 0.18:
                return shade(tunic, rnd.choice((0.7, 1.2)))
            return tunic
        if p.kind == "arm":
            if gloves is not None and p.h < 14:
                return gloves
            if p.h < 12.6:
                return limb(p, skin)
            if L.get("bracers") and p.h < 15:
                return L["bracers"]
            if sleeves == "none":
                return limb(p, skin)
            if sleeves == "short" and p.h < 19:
                return limb(p, skin)
            if cuirass is not None and p.h > 20:
                return shade(cuirass, 0.95)
            if L.get("mail_sleeves"):
                return hexc("8a8e92") if (p.x + p.y) % 2 else hexc("6e7276")
            if L.get("plaid"):
                return stripes(p, tunic, shade(tunic, 0.78))
            return tunic if not robe else (trim if p.h < 14 else tunic)
        if p.kind == "leg":
            if robe:
                if p.h < 1.5:
                    return boots
                return tunic if not (p.face == "front" and p.x == 0) else shade(tunic, 0.85)
            if L.get("barefoot") and p.h < 2:
                return limb(p, skin)
            if p.h < 4:
                return boots if p.h > 1 else shade(boots, 0.75)
            if L.get("leg_wraps") and p.y % 3 == 0:
                return L["leg_wraps"]
            return pants if not (p.face == "front" and p.x == 0) else shade(pants, 0.82)
        return None if clothes_only else skin

    def over(p):
        hood = L.get("hood")
        if p.kind == "head":
            if hood is not None:
                if p.face == "top":
                    return hood
                if p.face in ("back", "left", "right"):
                    return hood if p.y < 8 else None
                if p.face == "front":
                    if p.y <= 1 or (p.x in (0, 7) and p.y <= 6):
                        return hood
                    if L.get("mask") and p.y >= 5:
                        return L["mask"]
                return None
            if L.get("mask") and p.face == "front" and p.y >= 5:
                return L["mask"]
            if L.get("cap") is not None:
                cap = L["cap"]
                if p.face == "top":
                    return cap
                if p.face != "bottom" and p.y <= 1:
                    return cap if p.y == 0 else shade(cap, 0.8)
                if L.get("feather") and p.face == "left" and p.y in (0, 1, 2) and p.x in (5, 6):
                    return L["feather"]
                return None
            if L.get("circlet") and p.face != "top" and p.face != "bottom" and p.y == 2:
                if p.face == "front" and p.x in (3, 4):
                    return hexc("c03030")
                return L["circlet"]
            if L.get("headband") and p.face not in ("top", "bottom") and p.y == 1:
                return L["headband"]
            if L.get("headdress"):
                if p.face == "top" and (p.x + p.y) % 3 == 0:
                    return hexc("e8e0d0")
                if p.face in ("back", "left", "right") and p.y <= 1 and p.x % 2 == 0:
                    return rnd.choice((hexc("e8e0d0"), hexc("2a2a2a"), hexc("8a3a2a")))
            return None
        if p.kind == "body":
            if L.get("fur") is not None and p.h > 21.5 and p.face != "bottom":
                return L["fur"]
            if L.get("cloak") is not None and p.face == "back":
                return L["cloak"] if p.y < 11 else shade(L["cloak"], 0.8)
            if L.get("satchel") and p.face in ("front", "back") and (p.x + p.y) % 9 == 4:
                return hexc("6a4a2a")
            return None
        if p.kind == "arm":
            if L.get("fur") is not None and p.h > 21 and p.face != "bottom" and L.get("pauldrons", True):
                return L["fur"]
            if L.get("plate_pauldrons") and p.h > 20 and p.face != "bottom":
                return L["plate_pauldrons"]
            return None
        if p.kind == "leg":
            if robe and p.h > 2 and p.face not in ("top", "bottom"):
                return tunic if p.h > 2.6 else trim
            if L.get("skirt") is not None and p.h > 7 and p.face not in ("top", "bottom"):
                return L["skirt"] if p.h > 8 else shade(L["skirt"], 0.75)
            if L.get("fur_boots") and 3 <= p.h < 4.5:
                return L["fur_boots"]
            return None
        return None

    paint_humanoid(img, base, over, rnd)
    return img


def _limb(p, skin):
    return shade(skin, 0.95 if p.side else 1.0)


# ---------------------------------------------------------------------------------------------------- role looks

def looks(role, female):
    """Look dict for a role and gender; each role is recognizable at a glance."""
    F = female
    hair_m = {"hunter": "brown", "miner": "black", "lumberjack": "red", "bard": "auburn", "priest": "grey", "beggar": "grey",
              "mage": "brown", "adventurer": "blond", "thalmor": "gold", "imperial_soldier": "black", "stormcloak_soldier": "blond",
              "forsworn": "black", "vampire": "black", "necromancer": "black", "assassin": "black", "thug": "brown",
              "courier": "brown", "innkeeper": "brown", "jarl": "blond", "housecarl": "red", "thief": "brown"}
    L = dict(skin=SKIN["nord"], hair=HAIR[hair_m[role]], female=F)
    if not F and role in ("hunter", "lumberjack", "miner", "stormcloak_soldier", "jarl", "housecarl", "thug", "beggar", "innkeeper"):
        L["beard"] = shade(L["hair"], 0.9)
    if F:
        L["hair"] = HAIR[{"brown": "auburn", "black": "brown", "red": "red", "grey": "white", "blond": "blond",
                          "auburn": "red", "gold": "gold"}[hair_m[role]]]
    if role == "hunter":
        L.update(tunic=hexc("6b5236"), trim=hexc("4a3824"), pants=hexc("4e4436"), fur=hexc("8a7a62"), bracers=hexc("3e2c1c"),
                 sleeves="short", hood=None if not F else None, headband=hexc("4a3824"))
    elif role == "miner":
        L.update(tunic=hexc("6e6a62"), apron=hexc("5a4632"), pants=hexc("44403a"), gloves=hexc("3a3028"), sleeves="short",
                 cap=hexc("5a4e40"), skin=shade(SKIN["tan"], 0.95))
    elif role == "lumberjack":
        L.update(tunic=hexc("8a3a2a"), plaid=True, pants=hexc("4a4036"), fur=hexc("7a6a52"), pauldrons=False, gloves=hexc("4a3626"),
                 sleeves="long")
    elif role == "bard":
        L.update(tunic=hexc("6a3a7a") if not F else hexc("8a3044"), trim=hexc("c8a040"), pants=hexc("3e3450"), sash=hexc("c8a040"),
                 cap=hexc("4a2a5a") if not F else None, feather=hexc("e8e0d0"), boots=hexc("5a3a24"))
    elif role == "priest":
        L.update(tunic=hexc("d8ccb0"), trim=hexc("b89040"), robe=True, emblem=hexc("b89040"), boots=hexc("6a5a46"),
                 hood=hexc("c8bca0") if F else None)
    elif role == "beggar":
        L.update(tunic=hexc("7a6c58"), trim=hexc("5a4e40"), pants=hexc("5e5446"), rags=True, barefoot=True, sleeves="short",
                 skin=shade(SKIN["nord"], 0.88), belt=hexc("5a4e40"))
    elif role == "mage":
        L.update(tunic=hexc("2e4a8a"), trim=hexc("c8b070"), robe=True, hood=hexc("28407a"), emblem=hexc("c8b070"),
                 skin=SKIN["pale"] if not F else SKIN["dunmer"], eye=hexc("c03020") if F else hexc("3a5a7a"))
    elif role == "adventurer":
        L.update(tunic=hexc("6a4a2e"), cuirass=hexc("7a5a3a"), pants=hexc("4a3e30"), fur=hexc("9a8a70"), bracers=hexc("4a3020"),
                 fur_boots=hexc("9a8a70"))
    elif role == "thalmor":
        L.update(skin=SKIN["altmer"], hair=HAIR["gold"], tunic=hexc("1c1a1c"), trim=hexc("d4b048"), robe=True, hood=hexc("161416"),
                 emblem=hexc("d4b048"), eye=hexc("c8a030"), boots=hexc("1a1818"), plate_pauldrons=hexc("c8a440"))
        L.pop("beard", None)
    elif role == "imperial_soldier":
        L.update(tunic=hexc("8a2420"), cuirass=hexc("a8783a"), skirt=hexc("8a2420"), pants=hexc("5a2a22"), bracers=hexc("6a4a2a"),
                 boots=hexc("4a3020"), plate_pauldrons=hexc("b08848"), skin=SKIN["tan"] if not F else SKIN["pale"])
    elif role == "stormcloak_soldier":
        L.update(tunic=hexc("2e4a7a"), mail_sleeves=True, sash=hexc("3a5a9a"), pants=hexc("3e3a36"), fur=hexc("a89a80"),
                 bracers=hexc("5a4632"), fur_boots=hexc("a89a80"), skirt=hexc("2e4a7a"))
    elif role == "forsworn":
        L.update(skin=SKIN["tan"], tunic=hexc("6a5440"), bare_chest=not F, strap=hexc("e0d8c4"), pants=hexc("5a4632"),
                 leg_wraps=hexc("d8d0bc"), headdress=True, paint=hexc("8a2a20"), sleeves="none", barefoot=True,
                 fur=hexc("7a6a50"), pauldrons=True)
    elif role == "vampire":
        L.update(skin=SKIN["vampire"], tunic=hexc("2a1418"), trim=hexc("8a1a20"), pants=hexc("1e1a1c"), eye=hexc("d01818"),
                 red_eyes=True, cloak=hexc("3a0e14"), boots=hexc("161214"), hair=HAIR["black"] if not F else HAIR["white"])
        L.pop("beard", None)
    elif role == "necromancer":
        L.update(skin=SKIN["corpse"], tunic=hexc("1e1a22"), trim=hexc("5a2a6a"), robe=True, hood=hexc("141218"),
                 emblem=hexc("6a3a7a"), eye=hexc("7a4aa0"), boots=hexc("141214"))
    elif role == "assassin":
        L.update(tunic=hexc("3a1414"), trim=hexc("1a0c0c"), cuirass=hexc("2a1212"), pants=hexc("1e1414"), hood=hexc("1a0e0e"),
                 mask=hexc("4a1616"), gloves=hexc("1a1010"), boots=hexc("140e0e"), skin=SKIN["dunmer"] if not F else SKIN["pale"],
                 eye=hexc("c02020"))
    elif role == "thug":
        L.update(tunic=hexc("5a4a3a"), cuirass=hexc("6a5034"), pants=hexc("3e3830"), fur=hexc("6a5a46"), gloves=hexc("3a2a1c"),
                 sleeves="short", skin=SKIN["tan"])
    elif role == "courier":
        L.update(tunic=hexc("4a5a6e"), trim=hexc("3a4656"), pants=hexc("4e463c"), satchel=True, cap=hexc("5a3e2a"),
                 boots=hexc("5a3e26"))
    elif role == "innkeeper":
        L.update(tunic=hexc("7a5a3a") if not F else hexc("6a7a4a"), apron=hexc("d8d0bc"), pants=hexc("4a4038"), sleeves="short")
    elif role == "jarl":
        L.update(tunic=hexc("5a1e2a") if not F else hexc("2a4a3a"), trim=hexc("c8a040"), emblem=hexc("c8a040"), pants=hexc("3a2e26"),
                 fur=hexc("d8d0c0"), cloak=hexc("7a6a5a"), circlet=hexc("d8b040"), boots=hexc("3a2a1e"))
    elif role == "housecarl":
        L.update(tunic=hexc("4a4a50"), cuirass=hexc("9aa0a6"), pants=hexc("3e3e42"), fur=hexc("8a7a62"),
                 plate_pauldrons=hexc("a8aeb4"), bracers=hexc("5a5e64"), boots=hexc("3a3a3e"))
    elif role == "thief":
        L.update(tunic=hexc("3a3430"), trim=hexc("2a2622"), pants=hexc("2e2a26"), hood=hexc("2a2622"), mask=hexc("1e1c1a"),
                 gloves=hexc("1e1a16"), boots=hexc("221e1a"), satchel=True,
                 skin=SKIN["tan"] if F else SKIN["nord"])
    if F:
        L.pop("beard", None)
    return L


ROLES = ["hunter", "miner", "lumberjack", "bard", "priest", "beggar", "mage", "adventurer", "thalmor", "imperial_soldier",
         "stormcloak_soldier", "forsworn", "vampire", "necromancer", "assassin", "thug", "courier", "innkeeper", "jarl",
         "housecarl", "thief"]


# ---------------------------------------------------------------------------------------------------- cosmetics

# CosmeticLayer boxes: name -> (u, v, w, h, d)
COSMETIC_BOXES = {
    "hood": (0, 0, 8, 8, 8),
    "torso": (0, 16, 8, 12, 4),
    "skirt_front": (0, 32, 9, 9, 1),
    "skirt_back": (24, 32, 9, 9, 1),
    "cloak": (0, 44, 10, 19, 1),
    "right_pelt": (32, 0, 5, 4, 5),
    "left_pelt": (32, 10, 5, 4, 5),
}


def cosmetic(cid, rnd):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    fns = COSMETICS[cid](rnd)
    for name, (u, v, w, h, d) in COSMETIC_BOXES.items():
        fn = fns.get(name)
        if fn is None:
            continue
        paint_box(img, u, v, w, h, d, fn, rnd, noise=5, edges=True)
    return img


def fur_px(base, rnd, x, y):
    return jitter(shade(base, 1.0 + 0.12 * (((x * 7 + y * 13) % 5) - 2) / 2), rnd, 8)


def imperial_tabard(rnd):
    red, bronze, gold = hexc("8e2420"), hexc("a8783a"), hexc("d4b048")

    def torso(face, x, y, fw, fh):
        if face in ("top", "bottom"):
            return None
        if face in ("left", "right"):
            return bronze if y < 2 else None  # shoulder straps only
        if y < 1 or y == fh - 1 or x in (0, fw - 1):
            return bronze
        # golden dragon emblem on the chest
        if face == "front" and 2 <= y <= 6 and 2 <= x <= 5:
            if (y == 2 and x in (2, 5)) or (y == 3 and 3 <= x <= 4) or (y == 4 and 2 <= x <= 5) or (y == 5 and x in (3, 4)) \
                    or (y == 6 and x in (2, 5)):
                return gold
        return red

    def panel(face, x, y, fw, fh):
        if face in ("front", "back"):
            if y == fh - 1 or x in (0, fw - 1):
                return bronze
            if x in (3, 4, 5) and y < fh - 2:
                return shade(red, 1.12)
            return red
        return shade(red, 0.8)

    return {"torso": torso, "skirt_front": panel, "skirt_back": panel}


def stormcloak_cloak(rnd):
    blue, fur = hexc("2e4a80"), hexc("b0a288")

    def cloak(face, x, y, fw, fh):
        if face in ("front", "back"):
            if y < 3:
                return fur_px(fur, rnd, x, y)
            if y >= fh - 2 and (x % 2 == 0):
                return None  # ragged hem
            return shade(blue, 0.92 + 0.08 * ((x // 2) % 2))
        return shade(blue, 0.8)

    def pelt(face, x, y, fw, fh):
        return fur_px(fur, rnd, x, y)

    return {"cloak": cloak, "right_pelt": pelt, "left_pelt": pelt}


def companions_pelt(rnd):
    grey, dark = hexc("8a8478"), hexc("5a5650")

    def hood(face, x, y, fw, fh):
        # a wolf's head worn as a hood: ears on top, snout over the brow
        if face == "front":
            if y <= 1:
                return fur_px(grey, rnd, x, y)
            if y == 2 and x in (2, 5):
                return hexc("d8c040")  # glass eyes
            if y == 2:
                return fur_px(dark, rnd, x, y)
            if x in (0, 7) and y <= 6:
                return fur_px(grey, rnd, x, y)
            return None
        if face == "top":
            if (x in (1, 6)) and y in (2, 3):
                return dark  # ears
            return fur_px(grey, rnd, x, y)
        if face == "bottom":
            return None
        return fur_px(grey, rnd, x, y) if y < 7 else None

    def cloak(face, x, y, fw, fh):
        if face in ("front", "back"):
            if y >= 12 and (x + y) % 3 == 0:
                return None
            if y >= 14:
                return None if (x in (0, 1, fw - 2, fw - 1)) else fur_px(grey, rnd, x, y)
            return fur_px(grey if (x // 3 + y // 4) % 2 else shade(grey, 0.92), rnd, x, y)
        return fur_px(dark, rnd, x, y)

    def pelt(face, x, y, fw, fh):
        return fur_px(grey, rnd, x, y)

    return {"hood": hood, "cloak": cloak, "right_pelt": pelt, "left_pelt": pelt}


def college_sash(rnd):
    blue, gold = hexc("2c4c94"), hexc("d0b060")

    def torso(face, x, y, fw, fh):
        if face in ("front", "back"):
            if y == 0 and face == "front":
                return gold  # collar
            d = (x + y) if face == "front" else (fw - 1 - x + y)
            if d in (5, 6, 7):
                return blue
            if d in (4, 8):
                return gold
            return None
        if face in ("left", "right") and y in (0, 1):
            return blue
        return None

    def panel(face, x, y, fw, fh):
        # short sash tails over the hip
        if face == "front" and x in (6, 7) and y < 5:
            return blue if x == 6 else gold
        return None

    return {"torso": torso, "skirt_front": panel}


def thieves_hood(rnd):
    leather, dark = hexc("3a3430"), hexc("221e1a")

    def hood(face, x, y, fw, fh):
        if face == "front":
            if y <= 1 or (x in (0, 7) and y <= 6):
                return leather
            if y >= 5:
                return dark  # face mask
            return None
        if face == "bottom":
            return None
        return leather if (x + y) % 5 else shade(leather, 0.85)

    def pelt(face, x, y, fw, fh):
        return leather if (x + y) % 3 else hexc("6a5a3a")  # buckled leather

    return {"hood": hood, "right_pelt": pelt, "left_pelt": pelt}


def brotherhood_hood(rnd):
    black, red = hexc("1a1214"), hexc("7a1418")

    def hood(face, x, y, fw, fh):
        if face == "front":
            if y <= 1 or (x in (0, 7) and y <= 6):
                return black if y > 0 else red
            return None
        if face == "bottom":
            return None
        return black

    def cloak(face, x, y, fw, fh):
        if face == "front":
            return red  # red lining faces the body
        if face == "back":
            if y >= fh - 2 and x % 3 == 1:
                return None
            # faint black hand
            if 5 <= y <= 9 and 3 <= x <= 6 and not (y == 5 and x in (3, 6)):
                return hexc("0e0a0c")
            return black
        return shade(black, 1.2)

    return {"hood": hood, "cloak": cloak}


def bards_cape(rnd):
    purple, gold = hexc("5a2a6e"), hexc("d0b050")

    def cloak(face, x, y, fw, fh):
        if face in ("front", "back"):
            if y < 2 or y == fh - 1:
                return gold
            if x in (0, fw - 1):
                return gold
            return shade(purple, 0.92 + 0.08 * ((x // 2) % 2))
        return purple

    def torso(face, x, y, fw, fh):
        if face == "front" and y == 0:
            return gold  # clasp chain
        if face in ("left", "right") and y < 2:
            return purple
        if face == "top":
            return purple
        return None

    return {"cloak": cloak, "torso": torso}


COSMETICS = {
    "imperial_tabard": imperial_tabard,
    "stormcloak_cloak": stormcloak_cloak,
    "companions_pelt": companions_pelt,
    "college_sash": college_sash,
    "thieves_hood": thieves_hood,
    "brotherhood_hood": brotherhood_hood,
    "bards_cape": bards_cape,
}


# ---------------------------------------------------------------------------------------------------- NPC layers
#
# Townsfolk are composited at runtime by society.client.NpcSkins from four layers (all 64x64, player layout):
#   npc/body/<race>_<m|f>_<tone>.png      skin, face and race details (fur, scales, tusks), plain linen underclothes
#   npc/hair/<style>.png                  greyscale, tinted with the NPC's hair colour (also carries the brows)
#   npc/beard/<style>.png                 greyscale, tinted with the hair colour
#   npc/outfit/<id>_<variant>_<m|f>.png   clothing (transparent where skin shows)
#   npc/features.png                      greyscale texture of the extra race parts (RaceFeatureModel)
# Index orders MUST match NpcLook / Outfit in Java (race, tone, hair, beard and outfit ordinals).

NPC_RACES = ["nord", "imperial", "breton", "redguard", "altmer", "bosmer", "dunmer", "khajiit", "argonian", "orc"]
# 4 tones per race; the same values are mirrored in NpcLook.TONES (feature layer tint)
NPC_TONES = {
    "nord": ["edd3c0", "e3bfa3", "d6ad8e", "c79c7e"],
    "imperial": ["deb492", "cda07c", "bb8c66", "a87b58"],
    "breton": ["f2d8c4", "e8c6ac", "dcb699", "cfa588"],
    "redguard": ["a46e4a", "925e3c", "7e4e32", "6a4029"],
    "altmer": ["ead489", "dcc47a", "cfb46a", "c2a55e"],
    "bosmer": ["c8a070", "b88f60", "a68052", "937047"],
    "dunmer": ["8e979c", "7d878e", "6c767d", "8f8582"],
    "khajiit": ["c9a46c", "9e7044", "ddd0b4", "8c7d6e"],
    "argonian": ["5f7f4c", "7c6d44", "4d6f7c", "8a5c3c"],
    "orc": ["84a05e", "72904f", "8e9e66", "627e48"],
}
NPC_EYES = {
    "nord": ["4a6e94", "6a8aa0", "5a7a5a", "6a6a7a"],
    "imperial": ["5a3a24", "4a3220", "6a4a2a", "3a2a1c"],
    "breton": ["4a7a9a", "5a8a5a", "6a4a2a", "7a8a9a"],
    "redguard": ["3a2216", "2e1a10", "4a2a18", "3a2a1a"],
    "altmer": ["d8a830", "c89a2a", "e0b848", "b88a20"],
    "bosmer": ["2a1a10", "3a2a14", "1e140c", "4a3018"],
    "dunmer": ["e03020", "d02818", "f04030", "c02010"],
    "khajiit": ["a8c040", "d0a030", "60b050", "c08a28"],
    "argonian": ["e0c030", "e08a20", "d0d040", "f0a030"],
    "orc": ["c07020", "a05018", "d08a30", "8a4a1a"],
}


def npc_face(p, race, skin, eye, female, rnd):
    """Head base pixel of an NPC (no hair: that is its own layer)."""
    light = mix(skin, hexc("ffffff"), 0.25)
    dark = shade(skin, 0.78)
    if race == "khajiit":
        cream = mix(skin, hexc("f4ead8"), 0.6)
        stripe = shade(skin, 0.62)
        if p.face == "top":
            return stripe if p.x in (2, 5) and p.y < 6 else skin
        if p.face == "bottom":
            return cream
        if p.face == "back":
            return stripe if p.y % 3 == 0 and 1 <= p.x <= 6 else skin
        if p.side:
            if p.y in (2, 5) and 1 <= p.x <= 5:
                return stripe
            return cream if p.y >= 6 else skin
        x, y = p.x, p.y
        if y <= 1:
            return stripe if x in (3, 4) else skin
        if y == 2 and x in (2, 5):
            return stripe
        if y == 3:
            return shade(skin, 0.85) if x in (1, 2, 5, 6) else skin
        if y == 4:
            if x in (1, 6):
                return eye
            if x in (2, 5):
                return hexc("141010")  # slit pupils
            return skin
        if y == 5:
            if x in (3, 4):
                return hexc("8a4a4a")  # nose leather
            return cream if 2 <= x <= 5 else skin
        if y == 6:
            if x in (1, 6):
                return shade(skin, 0.55)  # whisker pads
            if x in (3, 4):
                return shade(cream, 0.6)
            return cream if 2 <= x <= 5 else skin
        return cream if 1 <= x <= 6 else skin
    if race == "argonian":
        belly = mix(skin, hexc("d8c890"), 0.55)
        scale = shade(skin, 0.82)
        ridge = shade(skin, 0.6)

        def scaled(c):
            return scale if (p.x + p.y * 2) % 4 == 0 else c
        if p.face == "top":
            return ridge if p.x in (3, 4) else scaled(skin)
        if p.face == "bottom":
            return belly
        if p.face == "back":
            return ridge if p.x in (3, 4) else scaled(skin)
        if p.side:
            if p.y >= 6:
                return belly if (p.x >= 4 if p.face == "right" else p.x <= 3) else scaled(skin)
            return scaled(skin)
        x, y = p.x, p.y
        if y == 2:
            return ridge if x in (0, 1, 2, 5, 6, 7) else skin  # brow ridge
        if y == 3:
            if x in (1, 6):
                return eye
            if x in (2, 5):
                return hexc("141410")
            return scaled(skin)
        if y == 5 and x in (2, 5):
            return hexc("1a1a14")  # nostrils
        if y == 6:
            return shade(skin, 0.55) if 1 <= x <= 6 else skin  # long mouth line
        if y == 7:
            return belly
        return scaled(skin)
    # men and mer
    if p.face == "top":
        return skin
    if p.face == "bottom":
        return dark
    if p.face == "back":
        return shade(skin, 0.94)
    if p.side:
        if p.y in (4, 5) and p.x in (3, 4):
            return shade(skin, 0.86)  # ear
        return shade(skin, 0.96)
    x, y = p.x, p.y
    if y == 3:
        if race == "orc":
            return shade(skin, 0.7)  # heavy brow
        return skin
    if y == 4:
        white = hexc("eeeeee")
        if race == "dunmer":
            white = shade(eye, 0.55)
        elif race == "altmer":
            white = hexc("f2e8c0")
        elif race == "bosmer":
            white = shade(eye, 1.6)
        if x in (1, 6):
            return white
        if x in (2, 5):
            return eye
        return skin
    if y == 5:
        if x in (3, 4):
            return shade(skin, 0.84)
        if race == "orc" and x in (2, 5):
            return shade(skin, 0.86)
        if female and x in (1, 6):
            return mix(skin, hexc("d88a80"), 0.18)  # a little colour in the cheeks
        return skin
    if y == 6:
        if race == "orc" and x in (2, 5):
            return hexc("e8e0c8")  # tusks
        if x in (3, 4):
            return mix(skin, hexc("a04a44"), 0.5) if female else shade(skin, 0.68)
        if x in (2, 5):
            return mix(skin, hexc("a04a44"), 0.2) if female else shade(skin, 0.85)
        return skin
    if y == 7:
        return shade(skin, 0.93)
    return skin


def npc_body(race, female, tone, rnd):
    skin = hexc(NPC_TONES[race][tone])
    eye = hexc(NPC_EYES[race][tone])
    linen = hexc("cdbf9f")
    furry = race == "khajiit"
    scaly = race == "argonian"

    def detail(p, c):
        if furry and p.face in ("back", "right", "left") and p.y % 4 == 1 and p.kind != "head":
            return shade(c, 0.66)  # tabby stripes
        if scaly and (p.x + p.y * 2) % 4 == 0:
            return shade(c, 0.84)
        return c

    def base(p):
        if p.kind == "head":
            return npc_face(p, race, skin, eye, female, rnd)
        if p.kind == "body":
            if 10.5 <= p.h <= 12.6:
                return linen
            if female and 18 <= p.h <= 21 and p.face != "top":
                return linen
            c = skin
            if (furry or scaly) and p.face == "front" and 2 <= p.x <= 5:
                c = mix(skin, hexc("e8dcc0") if furry else hexc("d8c890"), 0.5)  # light belly
            return detail(p, c)
        if p.kind == "arm":
            if (furry or scaly) and p.h < 1.5:
                return shade(skin, 0.7)  # paws / claws
            return detail(p, shade(skin, 0.95 if p.side else 1.0))
        if p.kind == "leg":
            if p.h > 10.5:
                return linen
            if (furry or scaly) and p.h < 1.5:
                return shade(skin, 0.7)
            return detail(p, shade(skin, 0.95 if p.side else 1.0))
        return skin

    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    paint_humanoid(img, base, lambda p: None, rnd, noise=5)
    return img


# hair styles: index -> painter(p, over) returning a grey level (0..1 brightness factor) or None
GREY = (214, 214, 214)


def g(f):
    return shade(GREY, f)


def brows(p):
    if p.face == "front" and p.y == 3 and p.x in (1, 2, 5, 6):
        return g(0.62)
    return None


def hair_short(p, over, long=False, back_rows=5):
    if over:
        return None
    if p.face == "top":
        return g(1.0)
    if p.face == "front":
        if p.y <= 1:
            return g(0.95 if p.y == 0 else 0.86)
        if p.y == 2 and p.x in (0, 7):
            return g(0.8)
        return brows(p)
    if p.face == "back":
        return g(0.9) if p.y <= back_rows else None
    if p.side:
        near_back = p.x <= 2 if p.face == "right" else p.x >= 5
        if p.y <= 2 or (p.y <= 4 and near_back):
            return g(0.88)
        return None
    return None


def hair_long(p, over, braids=False):
    if p.kind == "body":
        if not over:
            return None
        if braids:
            if p.face == "front" and p.x in (0, 7) and p.y <= 4:
                return g(0.92 if p.y % 2 == 0 else 0.72)
            return None
        if p.face == "back" and p.y <= 6:
            if p.y == 6 and p.x % 2 == 1:
                return None
            return g(0.86 - 0.02 * p.y)
        return None
    if p.kind != "head":
        return None
    if over:
        if p.face == "back":
            return g(0.82)
        if p.side:
            near_back = p.x <= 4 if p.face == "right" else p.x >= 3
            if p.y >= 2 and near_back:
                return g(0.8)
            return None
        return None
    if p.face == "top":
        return g(1.0)
    if p.face == "front":
        if p.y <= 1:
            return g(0.95 if p.y == 0 else 0.86)
        if p.x in (0, 7) and p.y <= 6:
            return g(0.82)
        return brows(p)
    if p.face == "back":
        return g(0.9)
    if p.side:
        if p.y <= 2:
            return g(0.88)
        near_back = p.x <= 3 if p.face == "right" else p.x >= 4
        return g(0.84) if near_back else None
    return None


def hair_shoulder(p, over):
    if p.kind != "head":
        return None
    if over:
        if p.face == "back" and p.y >= 2:
            return g(0.82) if not (p.y == 7 and p.x % 2) else None
        return None
    return hair_long(p, over)


def hair_ponytail(p, over):
    if p.kind == "body":
        if over and p.face == "back" and p.x in (3, 4) and p.y <= 4:
            return g(0.86)
        return None
    if p.kind != "head":
        return None
    if over:
        if p.face == "back" and p.x in (3, 4) and p.y >= 1:
            return g(0.92 if p.y % 3 else 0.6)
        return None
    return hair_short(p, over, back_rows=7)


def hair_mohawk(p, over):
    if p.kind != "head":
        return None
    if over:
        if p.face == "top" and p.x in (3, 4):
            return g(0.95)
        if p.face == "back" and p.x in (3, 4) and p.y <= 5:
            return g(0.86)
        if p.face == "front" and p.x in (3, 4) and p.y == 0:
            return g(0.9)
        return None
    if p.face == "top":
        return g(0.95) if p.x in (2, 3, 4, 5) else g(0.55)  # shaved sides look darker
    if p.face == "back" and p.y <= 2:
        return g(0.55)
    return brows(p)


def hair_bun(p, over):
    if p.kind != "head":
        return None
    if over:
        if p.face == "back" and 2 <= p.x <= 5 and 1 <= p.y <= 4:
            return g(0.9 if (p.x + p.y) % 3 else 0.7)
        if p.face == "top" and 2 <= p.x <= 5 and p.y >= 5:
            return g(0.9)
        return None
    return hair_short(p, over, back_rows=7)


def hair_shaved(p, over):
    if p.kind != "head" or over:
        return None
    if p.face == "top":
        return g(1.0)
    if p.face == "front":
        if p.y == 0:
            return g(0.92)
        return brows(p)
    if p.face == "back":
        return g(0.86) if p.y <= 2 else (g(0.5) if p.y <= 4 else None)
    if p.side:
        return g(0.85) if p.y == 0 else (g(0.5) if p.y == 1 else None)
    return None


def hair_wild(p, over, rnd=random.Random(77)):
    if p.kind != "head":
        return None
    if over:
        if p.face == "top" and (p.x * 3 + p.y * 5) % 4 != 0:
            return g(0.92)
        if p.face in ("back", "right", "left") and p.y <= 4 and (p.x * 7 + p.y * 3) % 3 != 0:
            return g(0.82)
        if p.face == "front" and p.y == 0 and p.x % 2 == 0:
            return g(0.9)
        return None
    return hair_long(p, over)


def hair_bald(p, over):
    if p.kind != "head" or over:
        return None
    return brows(p)


def khajiit_mane(p, over):
    if p.kind != "head" or not over:
        return None
    if p.side and p.y >= 3:
        near_front = p.x >= 4 if p.face == "right" else p.x <= 3
        return g(0.9 if (p.x + p.y) % 2 else 0.75) if near_front else None
    if p.face == "back" and p.y >= 2 and (p.x + p.y) % 3 != 0:
        return g(0.8)
    if p.face == "top" and p.x in (3, 4) and p.y <= 3:
        return g(0.9)
    return None


def khajiit_braids(p, over):
    if p.kind == "body":
        if over and p.face == "front" and p.x in (0, 7) and p.y <= 5:
            return g(0.92 if p.y % 2 == 0 else 0.68)
        return None
    if p.kind != "head" or not over:
        return None
    if p.face == "back" and p.y >= 1:
        return g(0.85) if p.x not in (0, 7) else None
    if p.face == "top" and p.y >= 3:
        return g(0.9)
    return None


def argonian_crest(p, over):
    if p.kind != "head" or not over:
        return None
    if p.face == "top" and 2 <= p.x <= 5:
        return g(1.0 if p.x in (3, 4) else 0.8)
    if p.face == "back" and 1 <= p.x <= 6 and p.y <= 5:
        return g(0.95 - 0.06 * p.y) if (p.x + p.y) % 2 == 0 or p.x in (3, 4) else None
    if p.face == "front" and p.y == 0 and p.x in (3, 4):
        return g(1.0)
    return None


def argonian_frills(p, over):
    if p.kind != "head" or not over:
        return None
    if p.side:
        near_back = p.x <= 3 if p.face == "right" else p.x >= 4
        if near_back and 1 <= p.y <= 6:
            return g(0.95 if (p.y % 2 == 0) else 0.7)
        return None
    if p.face == "back" and p.y <= 6 and p.x in (0, 1, 6, 7):
        return g(0.85)
    return None


def argonian_spines(p, over):
    if p.kind != "head" or not over:
        return None
    if p.face == "top" and p.x in (1, 3, 4, 6) and p.y >= 2:
        return g(0.9)
    if p.face == "back" and p.x in (1, 3, 4, 6) and p.y <= 3:
        return g(0.8)
    return None


def nothing(p, over):
    return None


# order = NpcLook hair style index
HAIR_STYLES = [
    ("bald", hair_bald), ("short", lambda p, o: hair_short(p, o) if p.kind == "head" else None),
    ("shoulder", hair_shoulder), ("long", hair_long), ("braids", lambda p, o: hair_long(p, o, braids=True)),
    ("ponytail", hair_ponytail), ("mohawk", hair_mohawk), ("bun", hair_bun), ("shaved", hair_shaved),
    ("wild", hair_wild), ("khajiit_mane", khajiit_mane), ("khajiit_braids", khajiit_braids),
    ("argonian_crest", argonian_crest), ("argonian_frills", argonian_frills), ("argonian_spines", argonian_spines),
    ("none", nothing),
]


def beard_painter(style):
    def paint(p, over):
        if style == 0:
            return None
        if p.kind == "body":
            if style == 3 and over and p.face == "front" and 2 <= p.x <= 5 and p.y <= 3:
                if p.y == 3 and p.x in (2, 5):
                    return None
                return g(0.86 if (p.x + p.y) % 2 else 0.7)
            return None
        if p.kind != "head":
            return None
        if over:
            if style in (2, 3) and p.face == "front" and p.y == 7:
                return g(0.84)
            return None
        if p.face == "bottom":
            return g(0.8) if style in (2, 3, 6) else None
        if p.face == "front":
            x, y = p.x, p.y
            if style == 1:  # stubble: sparse dark speckles
                return (g(0.55) + (150,)) if y >= 5 and (x + y) % 2 == 0 and not (y == 5 and x in (3, 4)) else None
            if style == 4:  # goatee
                return g(0.8) if (y == 7 and 2 <= x <= 5) or (y == 6 and x in (2, 5)) else None
            if style == 5:  # moustache
                return g(0.84) if y == 5 and x in (1, 2, 5, 6) or (y == 6 and x in (1, 6)) else None
            # full / braided / short full
            if y == 5 and x in (1, 2, 5, 6):
                return g(0.86)
            if y == 6 and x not in (3, 4):
                return g(0.84)
            if y == 7:
                return g(0.8)
            return None
        if p.side and style in (2, 3, 6):
            near_front = p.x >= 4 if p.face == "right" else p.x <= 3
            if near_front and p.y >= 5:
                return g(0.82)
            if near_front and p.y >= 3 and style == 2:
                return g(0.78)  # sideburns
        return None
    return paint


BEARD_STYLES = ["none", "stubble", "full", "braided", "goatee", "moustache", "short"]


def grey_layer(fn, rnd):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    paint_humanoid(img, lambda p: fn(p, False), lambda p: fn(p, True), rnd, noise=7)
    return img


def V(v, *opts):
    return hexc(opts[v % len(opts)])


def outfit_look(oid, v, female):
    """Look dict (clothing only) for an outfit id, colour variant 0..2 and sex."""
    F = female
    if oid in ("miner", "hunter", "bard", "innkeeper", "courier", "lumberjack", "thalmor", "imperial_soldier",
               "stormcloak_soldier", "forsworn", "vampire", "necromancer", "assassin", "thug", "thief", "adventurer",
               "housecarl", "jarl", "priest", "mage", "beggar"):
        L = looks(oid, F)
        L.pop("beard", None)
        if oid == "mage":
            L.update(tunic=V(v, "2e4a8a", "5a2a6a", "2a5a4a"), hood=V(v, "28407a", "4a2260", "224a3c"))
        elif oid == "priest":
            L.update(tunic=V(v, "d8ccb0", "9a9a9e", "c8a8a0"), trim=V(v, "b89040", "d0c080", "8a3a4a"),
                     emblem=V(v, "b89040", "e0d090", "8a3a4a"))
        elif oid == "jarl":
            L.update(tunic=V(v, "5a1e2a", "2a4a3a", "2a3460"))
        elif oid == "innkeeper":
            L.update(tunic=V(v, "7a5a3a", "6a7a4a", "5a4a6a"))
        elif oid == "bard":
            L.update(tunic=V(v, "6a3a7a", "8a3044", "2e5a6a"))
        elif oid == "hunter":
            L.update(tunic=V(v, "6b5236", "5a5a3a", "7a5a3e"))
        return L
    L = dict(female=F)
    if oid == "farm":
        if F:
            L.update(tunic=V(v, "7a6a4a", "5a6a7a", "8a5a4a"), robe=True, apron=hexc("d8d0bc"), trim=V(v, "5a4a34", "3e4a5a", "6a3e30"),
                     boots=hexc("4a3626"), sleeves="long")
        else:
            L.update(tunic=V(v, "8a7a5a", "6a7a4a", "7a5a44"), pants=V(v, "4e4436", "5a4a3a", "44403a"), sleeves="short",
                     belt=hexc("4a3020"), boots=hexc("4a3626"))
    elif oid == "fine":
        trim = V(v, "c8a040", "b0b4b8", "c8a040")
        if F:
            L.update(tunic=V(v, "2e3e6a", "6a1e2a", "2a4a3a"), trim=trim, robe=True, emblem=trim, boots=hexc("2a2220"), sash=trim)
        else:
            L.update(tunic=V(v, "2e3e6a", "6a1e2a", "2a4a3a"), trim=trim, emblem=trim, pants=hexc("2e2a26"),
                     boots=hexc("2a2220"), cloak=V(v, "1e2a4a", "4a1418", "1e3424"))
    elif oid == "merchant":
        L.update(tunic=V(v, "8a6a3a", "4a5a6a", "6a3a3a"), cuirass=V(v, "5a3a24", "3a2a1e", "2a2a2a"), pants=hexc("4a4036"),
                 satchel=True, belt=hexc("3a2616"), cap=None if F else V(v, "5a3e2a", "3a3a4a", "4a2a2a"))
        if F:
            L.update(robe=True, trim=V(v, "5a3a24", "3a2a1e", "2a2a2a"))
    elif oid == "blacksmith":
        L.update(tunic=hexc("5a5450"), apron=V(v, "5a4632", "4a3a2a", "6a4a2a"), pants=hexc("3e3a36"),
                 gloves=hexc("3a2c20"), sleeves="none" if not F else "short", boots=hexc("2e241a"))
    elif oid == "steward":
        L.update(tunic=V(v, "3a3446", "3a2a24", "2a3a3a"), trim=hexc("c8a040"), emblem=hexc("d8b850"), pants=hexc("2a2622"),
                 boots=hexc("2a2220"), sash=V(v, "6a1e2a", "2a4a6a", "4a2a5a"))
        if F:
            L["robe"] = True
    elif oid == "child":
        if F:
            L.update(tunic=V(v, "8a6a7a", "6a7a8a", "8a7a5a"), robe=True, trim=V(v, "6a4a5a", "4a5a6a", "6a5a3a"), boots=hexc("4a3626"),
                     sleeves="short")
        else:
            L.update(tunic=V(v, "7a6a4a", "5a6a5a", "6a5a6a"), pants=V(v, "5a4a3a", "4a4a44", "5a4636"), sleeves="short",
                     boots=hexc("4a3626"))
    elif oid == "scholar":
        L.update(tunic=V(v, "5a4a3a", "3a3a4a", "4a3a2a"), trim=V(v, "8a7a5a", "6a6a8a", "a08a5a"), robe=True, satchel=True,
                 emblem=None, boots=hexc("2a2220"))
    elif oid == "fisher":
        L.update(tunic=V(v, "3a5a5a", "4a5a3a", "5a5a5a"), cuirass=V(v, "2a3a3a", "3a3a2a", "3a3a3a"), pants=hexc("3e4040"),
                 boots=hexc("2a2622"), cap=V(v, "4a4a3a", "3a4a4a", "5a4a3a"), sleeves="long")
    elif oid == "nord_fur":
        L.update(tunic=V(v, "6a5a4a", "4a5a6a", "6a4a3a"), pants=hexc("4a4036"), fur=V(v, "a89a80", "8a7a62", "c8bca8"),
                 fur_boots=V(v, "a89a80", "8a7a62", "c8bca8"), bracers=hexc("4a3626"), belt=hexc("3a2616"))
        if F:
            L.update(robe=True, trim=V(v, "4a3e30", "34404a", "4a3024"))
    elif oid == "dunmer":
        L.update(tunic=V(v, "8a6a4a", "6a5a6a", "7a4a3a"), trim=V(v, "3a2a1e", "2a2232", "2a1a14"), robe=True,
                 sash=V(v, "a8803a", "8a3a2a", "5a7a6a"), fur=V(v, "5a4030", "3a3040", "4a2a20"), pauldrons=False,
                 boots=hexc("2a2018"))
    elif oid == "redguard":
        L.update(tunic=V(v, "e0d0b0", "c8b088", "d8c8b8"), trim=V(v, "a83a2a", "2a5a8a", "8a6a2a"), sash=V(v, "a83a2a", "2a5a8a", "c08a2a"),
                 pants=V(v, "c8b898", "b8a078", "c0b0a0"), sleeves="long", hood=V(v, "e0d0b0", "c8b088", "d8c8b8") if v != 1 else None,
                 boots=hexc("6a4a2a"))
    elif oid == "khajiit":
        L.update(tunic=V(v, "6a3a7a", "c06a2a", "2a7a7a"), cuirass=V(v, "4a2a1a", "5a3a1a", "3a2a2a"), sleeves="none",
                 sash=V(v, "d8a83a", "8a2a3a", "d8d0a0"), pants=V(v, "8a6a4a", "6a4a3a", "5a5a6a"), headband=V(v, "d8a83a", "8a2a3a", "d8d0a0"),
                 barefoot=True, bracers=V(v, "d8a83a", "8a5a3a", "a0a0a0"))
    elif oid == "caravan":
        L.update(tunic=V(v, "8a5a2a", "6a2a3a", "3a4a6a"), trim=V(v, "d8a83a", "d8c890", "c8a040"), robe=True, sash=V(v, "c03a2a", "d8a83a", "2a7a6a"),
                 hood=V(v, "a87a4a", "8a3a4a", "4a5a7a"), boots=hexc("4a3020"), satchel=True)
    elif oid == "argonian":
        L.update(tunic=V(v, "6a5a3a", "4a5a3a", "5a4a3a"), bare_chest=True, strap=V(v, "8a6a3a", "6a4a2a", "4a3a2a"),
                 skirt=V(v, "6a5a3a", "4a5a3a", "5a4a3a"), leg_wraps=V(v, "a89a70", "8a7a5a", "9a8a6a"), barefoot=True, sleeves="none",
                 bracers=V(v, "8a6a3a", "6a4a2a", "4a3a2a"), pants=V(v, "5a4a30", "3a4a2a", "4a3a2a"))
    elif oid == "imperial":
        L.update(tunic=V(v, "c8b890", "9a8a7a", "b8a080"), trim=V(v, "8a2420", "2a3a6a", "8a2420"), belt=hexc("6a4a2a"),
                 skirt=V(v, "c8b890", "9a8a7a", "b8a080"), pants=hexc("6a5a46"), sleeves="short", boots=hexc("6a4a2a"))
        if F:
            L.update(robe=True)
    elif oid == "altmer":
        L.update(tunic=V(v, "e8e0c8", "6a8a5a", "2a6a6a"), trim=hexc("d8b040"), robe=True, emblem=hexc("d8b040"),
                 sash=V(v, "c8a040", "e8e0c8", "e0c060"), boots=hexc("8a7a5a"))
    elif oid == "bosmer":
        L.update(tunic=V(v, "5a6a3a", "6a5a3a", "4a5a3a"), cuirass=V(v, "6a4a2a", "4a3a2a", "5a4a2a"), pants=V(v, "4a3e2a", "3e3a2a", "4a4030"),
                 leg_wraps=hexc("8a7a5a"), bracers=hexc("4a3a20"), sleeves="short", boots=hexc("3a2a1a"))
    elif oid == "breton":
        L.update(tunic=V(v, "3a4a7a", "7a3a3a", "4a6a4a"), trim=V(v, "d0c090", "c8a040", "d8d0c0"), plaid=v == 2,
                 pants=V(v, "6a2a2a", "2a2a4a", "4a3a2a"), cap=None if F else V(v, "2a2a3a", "3a1e1e", "2a3a2a"),
                 feather=hexc("e8e0d0"), boots=hexc("2a2018"))
        if F:
            L.update(robe=True)
    elif oid == "orc":
        L.update(tunic=V(v, "4a3a2a", "3a3a3a", "5a4a3a"), cuirass=V(v, "5a5a5e", "4a4a3a", "6a5a4a"), fur=V(v, "6a5a46", "5a4a3a", "7a6a52"),
                 bracers=hexc("5a5e64"), pants=hexc("3e3830"), sleeves="none" if not F else "short", gloves=hexc("3a2a1c"),
                 boots=hexc("2e241a"))
    else:
        raise ValueError(oid)
    if F and "skirt" not in L and L.get("robe") is not True and oid not in ("blacksmith", "khajiit", "argonian", "bosmer", "fisher", "orc"):
        L["skirt"] = shade(L.get("tunic", hexc("6a5a4a")), 0.92)
    return L


# order = Java Outfit enum ordinals
OUTFITS = ["farm", "fine", "merchant", "blacksmith", "priest", "mage", "beggar", "jarl", "steward", "child", "miner", "hunter",
           "bard", "innkeeper", "courier", "lumberjack", "scholar", "fisher", "thalmor", "imperial_soldier",
           "stormcloak_soldier", "forsworn", "vampire", "necromancer", "assassin", "thug", "thief", "adventurer",
           "housecarl", "nord_fur", "dunmer", "redguard", "khajiit", "caravan", "argonian", "imperial", "altmer", "bosmer",
           "breton", "orc"]


def features_texture(rnd):
    """Greyscale (tinted at runtime) texture of RaceFeatureModel's boxes; UVs must match that model."""
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    boxes = [
        (0, 0, 1, 3, 2, "ear"), (6, 0, 1, 3, 2, "ear"),        # elf ears (left/right)
        (12, 0, 2, 2, 1, "cat_ear"), (18, 0, 2, 2, 1, "cat_ear"),  # khajiit ears
        (0, 6, 2, 2, 6, "tail"), (16, 6, 2, 2, 6, "tail_tip"),     # khajiit tail
        (0, 16, 3, 3, 6, "scale_tail"), (18, 16, 2, 2, 6, "scale_tail"),  # argonian tail
        (32, 0, 1, 1, 4, "horn"), (42, 0, 1, 1, 3, "horn"),         # argonian horns
        (32, 8, 1, 1, 1, "tusk"),                                   # orc tusk
    ]
    for u, v, w, h, d, kind in boxes:
        def fn(face, x, y, fw, fh, kind=kind):
            if kind == "cat_ear" and face == "front":
                return (232, 196, 196) if 0 < x < fw - 1 or y > 0 else (210, 210, 210)
            if kind == "tail_tip":
                return (150, 150, 150) if y > fh // 2 or face in ("front", "back") else (200, 200, 200)
            if kind in ("tail", "scale_tail"):
                return (215, 215, 215) if (x + y) % 3 else (180, 180, 180)
            if kind == "horn":
                return (230, 230, 230) if face != "back" else (200, 200, 200)
            return (225, 225, 225)
        paint_box(img, u, v, w, h, d, fn, rnd, noise=6, edges=True)
    return img


def npc_layers():
    for ri, race in enumerate(NPC_RACES):
        for female in (False, True):
            for tone in range(4):
                rnd = random.Random(9000 + ri * 31 + tone * 7 + (1 if female else 0))
                save(npc_body(race, female, tone, rnd), "npc", "body", f"{race}_{'f' if female else 'm'}_{tone}.png")
    for i, (name, fn) in enumerate(HAIR_STYLES):
        save(grey_layer(fn, random.Random(9500 + i)), "npc", "hair", f"{i}.png")
    for i in range(len(BEARD_STYLES)):
        save(grey_layer(beard_painter(i), random.Random(9700 + i)), "npc", "beard", f"{i}.png")
    for oi, oid in enumerate(OUTFITS):
        for v in range(3):
            for female in (False, True):
                rnd = random.Random(10000 + oi * 13 + v * 3 + (1 if female else 0))
                img = humanoid(outfit_look(oid, v, female), rnd, clothes_only=True)
                save(img, "npc", "outfit", f"{oid}_{v}_{'f' if female else 'm'}.png")
    save(features_texture(random.Random(9900)), "npc", "features.png")


def main():
    for i, role in enumerate(ROLES):
        for variant in (0, 1):
            rnd = random.Random(1000 + i * 17 + variant)
            save(humanoid(looks(role, variant == 1), rnd), f"{role}_{variant}.png")
    for j, cid in enumerate(COSMETICS):
        rnd = random.Random(5000 + j)
        save(cosmetic(cid, rnd), "cosmetic", f"{cid}.png")
    npc_layers()


if __name__ == "__main__":
    main()
