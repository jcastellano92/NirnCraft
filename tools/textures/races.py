"""Generates the body layers of the races added after Skyrim's ten, the cosmetic sub-peoples, and the race feature
texture (PIL). Everything here is original procedural art.

Writes into mod/src/main/resources/assets/nirncraft/textures/entity/society/npc/:
  body/<race>_<m|f>_<tone>.png          the new races (Maormer, Reachmen, Ohmes-raht, Lilmothiit, Imga, Tsaesci,
                                        Ka Po' Tun, Tang Mo, Atmorans, Snow Elves, Dwemer, Ayleids, Kothringi)
  body/<race>_v<n>_<m|f>_<tone>.png     variants: Nibenese Imperials, Wood Orcs, Cathay-raht, Naga and Agaceph
  features.png                          greyscale texture of RaceFeatureLayer's boxes (UVs must match the Java model)

The tones here are mirrored in Java (society.NpcLook.TONES / VARIANT_TONES) for the feature-layer tint; keep them in
sync. society.py (the original ten races, hair, beards, outfits) calls into this module, or run it alone:
  python3 tools/textures/races.py
"""
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import society as S  # noqa: E402
from society import hexc, shade, mix, paint_humanoid, paint_box  # noqa: E402

# race id (NpcNames.Race, lower case) -> 4 tones; order of the new races = Java enum order after the first ten
NEW_TONES = {
    "maormer": ["b8d0d4", "9cc0c8", "8aa8c0", "c4d8cc"],
    "reachman": ["e6c4a8", "d8b090", "c89c7c", "b88a6a"],
    "ohmes_raht": ["d8b48c", "c8a07a", "b88c64", "e0c8a4"],
    "lilmothiit": ["c8682c", "a85a2c", "8a6a4a", "d89a58"],
    "imga": ["4a3a30", "5c4a3a", "3a3432", "6e5e4e"],
    "tsaesci": ["c8a048", "a8463a", "4a5a3e", "7a8a50"],
    "ka_po_tun": ["d8822a", "c06a22", "e8dcc8", "b87a3a"],
    "tang_mo": ["8a6a4a", "6a5646", "a8906a", "5a4a44"],
    "atmoran": ["f4e4dc", "ecd8cc", "e0ccbc", "d6c2b2"],
    "falmer": ["eef0f4", "e2e8f0", "d6dee8", "e8ecec"],
    "dwemer": ["d4ac8c", "c49c7c", "b48c6c", "a47c5e"],
    "ayleid": ["ece0c4", "e0d2b2", "d4c4a0", "f2ead6"],
    "kothringi": ["c8ccd2", "b2b8c0", "9ca2ac", "dcdee2"],
}
NEW_EYES = {
    "maormer": ["3a5ab0", "2a7a9a", "6a4ab0", "2a8a7a"],
    "reachman": ["5a7080", "4a6a5a", "6a5a3a", "7a8090"],
    "ohmes_raht": ["b8c040", "d0a030", "70b050", "c09030"],
    "lilmothiit": ["d89a20", "e0b030", "c08020", "b0c040"],
    "imga": ["9a6a2a", "b07a30", "8a5a22", "c08a3a"],
    "tsaesci": ["e0c020", "e05020", "c0e040", "f0a020"],
    "ka_po_tun": ["d8b030", "a8c840", "70a8d8", "e0a020"],
    "tang_mo": ["4a2e14", "6a4a1a", "3a2a14", "8a5a20"],
    "atmoran": ["7ab0d8", "8ac0e0", "6aa0c8", "9ac8d8"],
    "falmer": ["5a8ad8", "6a9ae0", "4a7ac8", "7aa0d8"],
    "dwemer": ["a06a20", "8a5a1a", "b8862a", "6a4a1a"],
    "ayleid": ["9a7ad0", "8a9ad0", "7a9ad0", "b08ae0"],
    "kothringi": ["2a2a34", "3a3a48", "1e2028", "4a3a5a"],
}
# (base race, variant) -> 4 tones; mirrored in NpcLook.VARIANT_TONES
VARIANT_TONES = {
    ("imperial", 1): ["b88658", "a87650", "98684a", "886040"],   # Nibenese
    ("orc", 1): ["98a066", "86905a", "a6a878", "74804c"],        # Wood Orc
    ("khajiit", 1): ["b08850", "7a5a3a", "d8c8a8", "5a5048"],    # Cathay-raht
    ("argonian", 1): ["3e5034", "34402e", "4a4e38", "2e3436"],   # Naga
    ("argonian", 2): ["8aaa86", "7a9cae", "a4ac7a", "92a49c"],   # Agaceph
}

LINEN = hexc("cdbf9f")
BLACK = hexc("161212")


def _elf_face(p, skin, eye, female, style, rnd):
    return S.npc_face(p, style, skin, eye, female, rnd)


# ------------------------------------------------------------------------------------------------ faces of the folk

def face_maormer(p, skin, eye, female, rnd):
    c = S.npc_face(p, "altmer", skin, eye, female, rnd)
    teal = mix(skin, hexc("2a6a7a"), 0.35)
    if p.side and p.y in (5, 6) and 2 <= p.x <= 5 and (p.x + p.y) % 2 == 0:
        return teal  # gill-like folds on the neck
    if p.face == "front":
        if p.y == 4 and p.x in (1, 6):
            return mix(eye, hexc("ffffff"), 0.35)  # the whole eye is coloured
        if p.y == 5 and p.x in (0, 7):
            return teal
    return c


def face_reachman(p, skin, eye, female, rnd):
    c = S.npc_face(p, "breton", skin, eye, female, rnd)
    ochre = hexc("8a2a1a")
    if p.face == "front":
        if p.y == 3 and 0 <= p.x <= 7:
            return ochre  # war paint band over the brows
        if p.y == 5 and p.x in (1, 6):
            return ochre
        if p.y == 6 and p.x in (1, 6) and not female:
            return ochre
        if p.y == 7 and p.x in (3, 4):
            return hexc("d8d0c0")  # chalk stripe on the chin
    if p.side and p.y == 3 and p.x in (3, 4, 5):
        return ochre
    return c


def face_ohmes(p, skin, eye, female, rnd):
    c = S.npc_face(p, "bosmer", skin, eye, female, rnd)
    mark = shade(skin, 0.6)
    if p.face == "front":
        if p.y == 4 and p.x in (2, 5):
            return BLACK  # slit pupils
        if p.y == 4 and p.x in (1, 6):
            return eye
        if p.y == 1 and p.x in (3, 4):
            return mark  # tattooed tabby stripes
        if p.y == 5 and p.x in (0, 7):
            return mark
        if p.y == 5 and p.x in (3, 4):
            return mix(skin, hexc("b06a6a"), 0.4)  # pink nose
    if p.side and p.y in (2, 5) and 2 <= p.x <= 5:
        return mark
    return c


def face_atmoran(p, skin, eye, female, rnd):
    c = S.npc_face(p, "nord", skin, eye, female, rnd)
    woad = hexc("3a5a9a")
    if p.face == "front":
        if p.y == 4 and p.x in (2, 5):
            return eye
        if p.y in (5, 6) and p.x in (1, 6):
            return woad  # woad lines down the cheeks
        if p.y == 1 and 2 <= p.x <= 5:
            return woad
    if p.side and p.y == 4 and p.x in (2, 3, 4, 5):
        return woad
    return c


def face_falmer(p, skin, eye, female, rnd):
    c = S.npc_face(p, "altmer", skin, eye, female, rnd)
    if p.face == "front":
        if p.y == 4 and p.x in (1, 2, 5, 6):
            return shade(eye, 0.8) if p.x in (2, 5) else mix(eye, hexc("ffffff"), 0.7)  # pale, glowing eyes
        if p.y == 5 and p.x in (1, 6):
            return mix(skin, hexc("8aa8d8"), 0.35)  # frost-blue marks
    return c


def face_dwemer(p, skin, eye, female, rnd):
    c = S.npc_face(p, "orc" if not female else "breton", skin, eye, female, rnd)
    if p.face == "front":
        if p.y == 6 and p.x in (2, 5):
            return shade(skin, 0.85)  # no tusks: a broad jaw
        if p.y == 4 and p.x in (1, 6):
            return hexc("eadcc0")
        if p.y == 5 and p.x in (3, 4):
            return shade(skin, 0.75)  # heavy nose
    return c


def face_ayleid(p, skin, eye, female, rnd):
    c = S.npc_face(p, "altmer", skin, eye, female, rnd)
    welkynd = hexc("4a8ad8")
    if p.face == "front":
        if p.y == 2 and p.x in (1, 2, 5, 6):
            return welkynd  # tattooed arcs over the eyes
        if p.y in (5, 6) and p.x in (1, 6):
            return welkynd
        if p.y == 0 and p.x in (3, 4):
            return welkynd
    return c


def face_kothringi(p, skin, eye, female, rnd):
    hi = mix(skin, hexc("ffffff"), 0.45)
    lo = shade(skin, 0.75)
    if p.face == "top":
        return hi if (p.x + p.y) % 5 == 0 else skin
    if p.face == "bottom":
        return lo
    if p.face == "back":
        return hi if (p.x * 3 + p.y) % 7 == 0 else shade(skin, 0.94)
    if p.side:
        if p.y in (4, 5) and p.x in (3, 4):
            return lo  # small ear
        return hi if (p.x + p.y * 2) % 6 == 0 else skin
    x, y = p.x, p.y
    if y == 3:
        return lo if x in (1, 2, 5, 6) else skin
    if y == 4:
        if x in (1, 2, 5, 6):
            return eye if x in (2, 5) else shade(eye, 0.7)  # large dark eyes, no whites
        return skin
    if y == 5 and x in (3, 4):
        return lo
    if y == 6 and 2 <= x <= 5:
        return shade(skin, 0.6)
    if y in (1, 2) and x in (2, 5):
        return hi  # quicksilver gleam
    return skin


def face_fox(p, skin, eye, female, rnd):
    cream = mix(skin, hexc("f6eee0"), 0.85)
    dark = shade(skin, 0.45)
    if p.face == "top":
        return skin
    if p.face == "bottom":
        return cream
    if p.face == "back":
        return shade(skin, 0.92)
    if p.side:
        if p.y >= 4:
            near_front = p.x >= 4 if p.face == "right" else p.x <= 3
            return cream if near_front else skin
        return skin
    x, y = p.x, p.y
    if y <= 3:
        return dark if (y == 3 and x in (1, 6)) else skin
    if y == 4:
        if x in (1, 6):
            return eye
        if x in (2, 5):
            return BLACK
        return skin
    if y == 5:
        if x in (2, 5):
            return dark  # tear marks
        return skin if x in (3, 4) else cream
    if y == 6:
        if x in (3, 4):
            return BLACK  # nose
        return cream
    return cream


def face_tiger(p, skin, eye, female, rnd):
    cream = mix(skin, hexc("f4ecdc"), 0.8)
    stripe = hexc("201814")
    if p.face == "top":
        return stripe if p.x in (1, 3, 4, 6) and p.y % 3 != 2 else skin
    if p.face == "bottom":
        return cream
    if p.face == "back":
        return stripe if p.y % 3 == 0 and 1 <= p.x <= 6 else skin
    if p.side:
        if p.y in (2, 5) and 1 <= p.x <= 5:
            return stripe
        return cream if p.y >= 6 else skin
    x, y = p.x, p.y
    if y == 0:
        return stripe if x in (1, 3, 4, 6) else skin
    if y == 1:
        return stripe if x in (2, 5) else skin
    if y == 2:
        return stripe if x in (0, 7) else skin
    if y == 3:
        return cream if x in (1, 2, 5, 6) else skin
    if y == 4:
        if x in (1, 6):
            return eye
        if x in (2, 5):
            return BLACK
        return cream if x in (3, 4) else skin
    if y == 5:
        if x in (0, 7):
            return stripe
        if x in (3, 4):
            return hexc("6a3a34")
        return cream
    if y == 6:
        return shade(cream, 0.7) if x in (3, 4) else cream
    return cream


def face_ape(p, skin, eye, female, rnd):
    leather = mix(skin, hexc("7a6e66"), 0.55)
    brow = shade(skin, 0.6)
    if p.face == "top":
        return skin
    if p.face == "bottom":
        return shade(leather, 0.85)
    if p.face == "back":
        return shade(skin, 0.92)
    if p.side:
        return skin
    x, y = p.x, p.y
    if y <= 1:
        return skin
    if x in (0, 7):
        return skin
    if y == 2:
        return leather
    if y == 3:
        return brow
    if y == 4:
        if x in (2, 5):
            return eye
        return leather
    if y == 5:
        return shade(leather, 0.55) if x in (3, 4) else leather
    if y == 6:
        return shade(leather, 0.5) if 2 <= x <= 5 else leather
    return leather


def face_monkey(p, skin, eye, female, rnd):
    bare = hexc("d8a890")
    if p.face == "top":
        return skin
    if p.face == "bottom":
        return shade(bare, 0.85)
    if p.face == "back":
        return shade(skin, 0.92)
    if p.side:
        return skin
    x, y = p.x, p.y
    if y <= 1:
        return skin
    if y == 2:
        return bare if x in (2, 5) else skin
    if x in (0, 7):
        return skin
    if y == 3:
        return bare
    if y == 4:
        if x in (2, 5):
            return eye
        return bare
    if y == 5:
        return shade(bare, 0.6) if x in (3, 4) else bare
    if y == 6:
        if x in (1, 6):
            return skin
        return shade(bare, 0.5) if x in (3, 4) else bare
    return bare if 2 <= x <= 5 else skin


def face_snake(p, skin, eye, female, rnd):
    belly = mix(skin, hexc("e8d8a0"), 0.6)
    dark = shade(skin, 0.62)
    if p.face == "top":
        return dark if (p.x + p.y) % 4 == 0 or p.x in (3, 4) and p.y % 2 == 0 else skin
    if p.face == "bottom":
        return belly
    if p.face == "back":
        return dark if (p.x + p.y) % 4 == 0 else skin
    if p.side:
        if p.y >= 6:
            return belly
        return dark if (p.x + p.y) % 4 == 0 else skin
    x, y = p.x, p.y
    if y <= 2:
        return dark if (x + y) % 4 == 0 or (y == 2 and x in (0, 7)) else skin
    if y == 3:
        return dark if x in (0, 1, 6, 7) else skin
    if y == 4:
        if x in (1, 6):
            return eye
        if x in (2, 5):
            return BLACK
        return skin
    if y == 5:
        return skin
    if y == 6:
        if x in (2, 5):
            return BLACK  # heat pits
        return skin
    return belly


FACES = {
    "maormer": face_maormer, "reachman": face_reachman, "ohmes_raht": face_ohmes, "atmoran": face_atmoran,
    "falmer": face_falmer, "dwemer": face_dwemer, "ayleid": face_ayleid, "kothringi": face_kothringi,
    "lilmothiit": face_fox, "ka_po_tun": face_tiger, "imga": face_ape, "tang_mo": face_monkey, "tsaesci": face_snake,
}
FUR = {"lilmothiit", "ka_po_tun", "imga", "tang_mo"}


def body_detail(race, p, skin, c):
    """Race markings on torso, arms and legs."""
    k = p.kind
    if race == "ka_po_tun":
        wave = (p.y + abs(p.x - p.fw // 2) // 2) % 5
        if p.face in ("back", "right", "left", "front") and (wave == 0 or (wave == 1 and (p.x + p.y) % 3 == 0)):
            if not (p.face == "front" and k == "body" and 2 <= p.x <= 5):
                return hexc("201814")
        return c
    if race == "lilmothiit":
        if k in ("arm", "leg") and p.h < 4.5:
            return shade(skin, 0.4)  # dark socks and gloves
        return c
    if race == "imga":
        if k in ("arm", "leg") and p.h < 1.5:
            return mix(skin, hexc("7a6e66"), 0.55)  # leathery hands and feet
        return shade(c, 0.9) if (p.x * 3 + p.y * 5) % 7 == 0 else c
    if race == "tang_mo":
        if k == "arm" and p.h < 2.5:
            return hexc("c8987e")  # bare hands
        return shade(c, 1.08) if (p.x + p.y * 3) % 5 == 0 else c
    if race == "tsaesci":
        if (p.x + p.y) % 4 == 0 or (p.x - p.y) % 4 == 0:
            return shade(c, 0.84)  # diamond scales
        return c
    if race == "kothringi":
        return mix(c, hexc("ffffff"), 0.35) if (p.x + p.y * 2) % 7 == 0 else c
    if race == "atmoran":
        if k == "arm" and 15 <= p.h <= 17:
            return hexc("3a5a9a")  # woad armbands
        return c
    if race == "ayleid":
        if k == "arm" and p.face == "front" and p.h in (16.5, 17.5) and p.x in (1, 2):
            return hexc("4a8ad8")
        return c
    if race == "maormer":
        if p.face in ("right", "left", "back") and (p.x + p.y * 3) % 9 == 0:
            return mix(c, hexc("2a6a7a"), 0.3)
        return c
    return c


def race_body(race, female, tone, rnd):
    skin = hexc(NEW_TONES[race][tone])
    eye = hexc(NEW_EYES[race][tone])
    furry = race in FUR
    scaly = race == "tsaesci"
    belly = mix(skin, hexc("f2e8d4") if race != "tsaesci" else hexc("e8d8a0"), 0.6)
    if race == "imga":
        belly = mix(skin, hexc("7a6e66"), 0.45)

    def base(p):
        if p.kind == "head":
            return FACES[race](p, skin, eye, female, rnd)
        if p.kind == "body":
            if 10.5 <= p.h <= 12.6:
                return LINEN
            if female and 18 <= p.h <= 21 and p.face != "top":
                return LINEN
            c = skin
            if (furry or scaly) and p.face == "front" and 2 <= p.x <= 5:
                c = belly
            return body_detail(race, p, skin, c)
        if p.kind == "arm":
            if (furry or scaly) and p.h < 1.5 and race not in ("tang_mo", "imga"):
                return shade(skin, 0.7)  # claws / paws
            return body_detail(race, p, skin, shade(skin, 0.95 if p.side else 1.0))
        if p.kind == "leg":
            if p.h > 10.5:
                return LINEN
            if (furry or scaly) and p.h < 1.5 and race not in ("imga",):
                return shade(skin, 0.7)
            return body_detail(race, p, skin, shade(skin, 0.95 if p.side else 1.0))
        return skin

    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    paint_humanoid(img, base, lambda p: None, rnd, noise=5)
    return img


# ------------------------------------------------------------------------------------------------ variants

def variant_marks(base, v, p, skin):
    """Overlay colour for a variant's markings, or None."""
    if base == "imperial" and v == 1:
        # Nibenese body art: dark tattoo lines on the cheeks and forearms
        ink = hexc("2a3060")
        if p.kind == "head" and p.face == "front" and (p.y in (5, 6) and p.x in (0, 1, 6, 7) or p.y == 1 and 2 <= p.x <= 5):
            return ink
        if p.kind == "head" and p.side and p.y in (4, 5) and 2 <= p.x <= 5:
            return ink
        if p.kind == "arm" and p.face in ("front", "right", "left") and int(p.h) in (6, 8, 16):
            return ink
        return None
    if base == "orc" and v == 1:
        # Wood Orcs: leafy green tattoos, smaller tusks
        if p.kind == "head" and p.face == "front" and p.y == 6 and p.x in (2, 5):
            return shade(skin, 0.85)
        if p.kind == "head" and p.face == "front" and p.y == 2 and p.x in (2, 3, 4, 5):
            return hexc("3a5a2a")
        if p.kind == "arm" and p.face in ("front", "right", "left") and (p.x + int(p.h)) % 5 == 0 and 14 <= p.h <= 20:
            return hexc("3a5a2a")
        return None
    if base == "khajiit" and v == 1:
        # Cathay-raht: jaguar rosettes instead of tabby stripes, darker muzzle
        if p.kind != "head" and p.face != "front" and (p.x * 5 + int(p.h) * 3) % 7 == 0:
            return shade(skin, 0.45)
        if p.kind == "head" and p.face in ("top", "back") and (p.x * 3 + p.y * 5) % 6 == 0:
            return shade(skin, 0.45)
        return None
    if base == "argonian" and v == 1:
        # Naga: heavy dark ridges and red eyes
        if p.kind == "head" and p.face == "front" and p.y == 3 and p.x in (1, 6):
            return hexc("c02818")
        if p.kind in ("body", "arm", "leg") and p.face in ("back", "right", "left") and p.x % 3 == 1:
            return shade(skin, 0.6)
        return None
    if base == "argonian" and v == 2:
        # Agaceph: slender, banded
        if p.kind in ("body", "arm", "leg") and p.face != "front" and int(p.h) % 3 == 0:
            return mix(skin, hexc("e8e0b0"), 0.45)
        if p.kind == "head" and p.face in ("top", "back") and p.x in (2, 5):
            return mix(skin, hexc("e8e0b0"), 0.45)
        return None
    return None


def variant_body(base, v, female, tone, rnd):
    tones = S.NPC_TONES[base]
    S.NPC_TONES[base] = VARIANT_TONES[(base, v)]
    try:
        img = S.npc_body(base, female, tone, rnd)
    finally:
        S.NPC_TONES[base] = tones
    skin = hexc(VARIANT_TONES[(base, v)][tone])
    paint_humanoid(img, lambda p: variant_marks(base, v, p, skin), lambda p: None, rnd, noise=4)
    return img


# ------------------------------------------------------------------------------------------------ feature texture

# (u, v, w, h, d, kind) of every RaceFeatureLayer box; must match RaceFeatureLayer.createLayer()
FEATURE_BOXES = [
    (0, 0, 1, 3, 2, "ear"), (6, 0, 1, 3, 2, "ear"),               # elf ears
    (12, 0, 2, 2, 1, "cat_ear"), (18, 0, 2, 2, 1, "cat_ear"),     # cat ears
    (0, 6, 2, 2, 6, "tail"), (16, 6, 2, 2, 6, "tail_tip"),        # cat tail
    (0, 16, 3, 3, 6, "scale_tail"), (18, 16, 2, 2, 6, "scale_tail"),  # argonian tail
    (32, 0, 1, 1, 4, "horn"), (42, 0, 1, 1, 3, "horn"),           # horns
    (32, 8, 1, 1, 1, "tusk"),                                     # tusks
    (50, 0, 4, 3, 2, "muzzle"),                                   # muzzle (tiger, apes)
    (36, 8, 3, 4, 1, "fox_ear"),                                  # fox ears
    (46, 8, 1, 2, 2, "round_ear"),                                # monkey / ape ears
    (36, 14, 1, 1, 1, "fang"),                                    # serpent fangs
    (36, 16, 1, 1, 6, "thin_tail"),                               # monkey tail segments
    (0, 26, 14, 12, 1, "hood"),                                    # Tsaesci hood
    (30, 26, 3, 3, 7, "fox_tail"), (50, 26, 3, 3, 4, "fox_tip"),  # fox tail
    (0, 40, 4, 4, 8, "snake"), (24, 40, 3, 3, 8, "snake"), (0, 52, 2, 2, 8, "snake_tip"),  # serpent tail
    (24, 52, 3, 9, 3, "arm"),                                     # Tang Mo's second arms
]


def features_texture(rnd):
    """Greyscale (tinted at runtime) texture of RaceFeatureLayer's boxes."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for u, v, w, h, d, kind in FEATURE_BOXES:
        def fn(face, x, y, fw, fh, kind=kind):
            if kind == "cat_ear" and face == "front":
                return (232, 196, 196) if 0 < x < fw - 1 or y > 0 else (210, 210, 210)
            if kind == "fox_ear":
                if face == "front":
                    return (60, 52, 50) if y == 0 else (236, 226, 214) if 0 < x < fw - 1 else (200, 200, 200)
                return (205, 205, 205) if y > 0 else (70, 60, 56)  # black tips
            if kind == "tail_tip":
                return (150, 150, 150) if y > fh // 2 or face in ("front", "back") else (200, 200, 200)
            if kind in ("tail", "scale_tail", "thin_tail"):
                return (215, 215, 215) if (x + y) % 3 else (180, 180, 180)
            if kind == "fox_tail":
                return (220, 220, 220) if (x + y * 2) % 5 else (190, 190, 190)
            if kind == "fox_tip":
                return (250, 250, 250)  # rendered in the cream pass
            if kind in ("snake", "snake_tip"):
                if face == "bottom" or (face in ("right", "left") and y >= fh - 1):
                    return (250, 240, 200)
                return (150, 150, 150) if (x + y) % 4 == 0 or (x - y) % 4 == 0 else (220, 220, 220)
            if kind == "hood":
                if face == "front":
                    # the pale, banded underside of the hood
                    return (200, 196, 180) if y % 3 == 0 else (245, 238, 210)
                if face == "back" and y in (3, 4) and (x in (3, 4, 9, 10) or y == 4 and 5 <= x <= 8):
                    return (70, 70, 70)  # the spectacle mark of a cobra
                return (150, 150, 150) if (x + y) % 3 == 0 else (215, 215, 215)
            if kind == "muzzle":
                if face == "front":
                    if y == 0 and x in (1, 2):
                        return (70, 50, 50)  # nose leather
                    if y == fh - 1:
                        return (120, 110, 110)  # mouth line
                    return (238, 230, 220)
                return (225, 220, 210)
            if kind == "round_ear":
                return (200, 180, 170) if face in ("front", "right") else (210, 210, 210)
            if kind == "horn":
                return (230, 230, 230) if face != "back" else (200, 200, 200)
            if kind == "arm":
                return (200, 200, 200) if y >= fh - 2 else (225, 225, 225)
            return (225, 225, 225)
        paint_box(img, u, v, w, h, d, fn, rnd, noise=6, edges=True)
    return img


# ------------------------------------------------------------------------------------------------ main

def main_layers():
    for ri, race in enumerate(NEW_TONES):
        for female in (False, True):
            for tone in range(4):
                rnd = random.Random(20000 + ri * 31 + tone * 7 + (1 if female else 0))
                S.save(race_body(race, female, tone, rnd), "npc", "body", f"{race}_{'f' if female else 'm'}_{tone}.png")
    for vi, (base, v) in enumerate(VARIANT_TONES):
        for female in (False, True):
            for tone in range(4):
                rnd = random.Random(21000 + vi * 31 + tone * 7 + (1 if female else 0))
                S.save(variant_body(base, v, female, tone, rnd), "npc", "body", f"{base}_v{v}_{'f' if female else 'm'}_{tone}.png")
    S.save(features_texture(random.Random(9900)), "npc", "features.png")


if __name__ == "__main__":
    main_layers()
