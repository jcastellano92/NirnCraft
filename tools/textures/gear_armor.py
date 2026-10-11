"""Armour layer textures (64x32, vanilla layout) and armour icons for every set of the catalogue.

A set's LOOK names its palette and how each area is made: the helmet's face (closed slit, open face, nose guard,
mask, hood...), the cuirass material (plate, scale, chain, leather, fur, cloth, chitin, lamellar, crystal, bone) with
overlays (pectoral plates, abdominal bands, belts, straps, sashes, tabards, emblems, glowing veins...), sleeves,
boots, gauntlets and greaves. Extra cubes of the 3D model (gear_geo) sample material swatches painted here.
"""
import math
import zlib

from PIL import Image

import gear_geo as geo
from gear_px import alpha, hexc, mix, ramp, rnd, shade

T = (0, 0, 0, 0)


def R(*h):
    return ramp(*h)


# ============================================================================================= looks
# metal: 5 shades darkest -> highlight; others 3 shades dark -> light

BROWN_LEATHER = R("3e2614", "5c3b20", "7c5530")
DARK_LEATHER = R("261a12", "3a291c", "52392a")
BLACK_LEATHER = R("16141a", "24212a", "34303c")
FUR_BROWN = R("4a3424", "6e5236", "947552")
FUR_GREY = R("4a4a4c", "707072", "98989a")
CHAIN = R("3a3c40", "5e6268", "8a8e94")


def look(metal, trim=None, leather=BROWN_LEATHER, cloth=None, fur=FUR_BROWN, accent=None, helm="nose", helm_mat="metal",
         chest="plate", over=(), arms="metal", boots="metal", gaunt="metal", greaves="metal", horn=None, crest="metal",
         plume=None, under=CHAIN, emblem=None, glow=None):
    metal = R(*metal) if isinstance(metal[0], str) else metal
    return dict(metal=metal, trim=R(*trim) if trim and isinstance(trim[0], str) else (trim or metal[1:4]),
                leather=leather, cloth=R(*cloth) if cloth and isinstance(cloth[0], str) else (cloth or R("3a3a44", "565664", "767688")),
                fur=fur, accent=R(*accent) if accent and isinstance(accent[0], str) else (accent or R("6a1a1a", "a02a2a", "d04a4a")),
                helm=helm, helm_mat=helm_mat, chest=chest, over=tuple(over), arms=arms, boots=boots, gaunt=gaunt, greaves=greaves,
                horn=R(*horn) if horn and isinstance(horn[0], str) else horn, crest=crest,
                plume=R(*plume) if plume and isinstance(plume[0], str) else plume, under=under, emblem=emblem,
                glow=R(*glow) if glow and isinstance(glow[0], str) else glow)


IRON = ("2a2a2c", "48484c", "66666a", "86868a", "a8a8ac")
STEEL = ("2e353c", "4e5862", "74808c", "a0acb8", "d4dee8")
GOLD = ("4a3008", "7a5414", "b08028", "dcb050", "fff0a0")
BONE_HORN = ("6e604a", "a49678", "dccfb2")

LOOKS = {
    # ------------------------------------------------------------------ light
    "hide": look(("2e2014", "4a3420", "664a30", "84643f", "a07e54"), leather=BROWN_LEATHER, fur=FUR_BROWN, helm="cap", helm_mat="fur",
                 chest="leather", over=("straps", "belt"), arms="fur", boots="leather", gaunt="leather", greaves="leather", horn=None),
    "leather": look(("2a1a10", "422a18", "5e3e24", "7a5634", "966e46"), helm="cap", helm_mat="leather", chest="leather",
                    over=("cross_straps", "belt"), arms="leather", boots="leather", gaunt="leather", greaves="leather"),
    "fur": look(("2a2a2c", "48484c", "66666a", "86868a", "a8a8ac"), fur=FUR_GREY, helm="hood", helm_mat="fur", chest="fur",
                over=("belt",), arms="fur", boots="fur", gaunt="leather", greaves="leather"),
    "studded": look(("1e1612", "34261c", "4c3828", "644a36", "7c5e46"), trim=("4a4a4e", "7a7a80", "a8a8ae"), helm="cap",
                    helm_mat="leather", chest="leather", over=("studs", "belt"), arms="leather", boots="leather", gaunt="leather",
                    greaves="leather"),
    "elven": look(GOLD, trim=("6a4a10", "a0781e", "d8b048"), leather=R("3a2a16", "56402a", "74583a"), accent=("1e5a40", "2e8a5e", "5cc08a"),
                  cloth=("1e4a36", "2e6a4c", "4a9068"), helm="open", chest="plate", over=("pecs", "center_ridge", "belt"),
                  crest="trim", boots="metal", greaves="leather"),
    "elven_gilded": look(("5a3c0c", "8c641c", "c4962e", "ecc860", "fff6c0"), trim=("8a8a90", "c0c0c8", "eeeef4"),
                         accent=("1e4a6a", "2e6a9a", "5ca0d0"), cloth=("1e3a5a", "2e5a8a", "4a80b8"), helm="open", chest="plate",
                         over=("pecs", "center_ridge", "trim_edges", "belt"), crest="trim"),
    "scaled": look(("2a2016", "4a3a26", "6c5838", "8e7650", "b09868"), trim=("3e3e42", "66666c", "909098"), helm="nose",
                   chest="scale", over=("belt",), arms="scale", boots="leather", gaunt="leather", greaves="scale", horn=BONE_HORN),
    "glass": look(("2e3a36", "5a6a64", "8a9c96", "bccfc8", "eefaf6"), trim=("2e3a36", "5a6a64", "8a9c96"),
                  accent=("1e7a46", "3cb46a", "8ef0b0"), helm="visor", chest="crystal", over=("pecs",), arms="crystal",
                  boots="metal", gaunt="metal", greaves="crystal", crest="crystal", glow=("1e7a46", "3cb46a", "8ef0b0")),
    "stalhrim_light": look(("1e3442", "3a5c72", "5e8ca6", "8ec0d8", "d8f4ff"), trim=("2a3440", "445262", "62748a"), leather=DARK_LEATHER,
                           accent=("4a9ac0", "8ad0ec", "e0faff"), helm="visor", chest="crystal", over=("belt",), arms="leather",
                           boots="leather", gaunt="crystal", greaves="leather", crest="crystal", glow=("4a9ac0", "8ad0ec", "e0faff")),
    "dragonscale": look(("1e1a18", "3a3430", "5a524a", "7c7266", "a09484"), trim=("6e604a", "a49678", "dccfb2"), leather=DARK_LEATHER,
                        helm="open", chest="scale", over=("ribs", "belt"), arms="scale", greaves="scale", horn=BONE_HORN, crest="trim"),
    "forsworn": look(("4a3a28", "6e5838", "927650", "b49870", "d2ba90"), trim=("6e604a", "a49678", "dccfb2"), fur=FUR_BROWN,
                     accent=("6a1a14", "a02a1e", "d04a32"), helm="cap", helm_mat="fur", chest="fur", over=("ribs", "straps"),
                     arms="fur", boots="leather", gaunt="leather", greaves="leather", horn=BONE_HORN),
    "thieves_guild": look(("1c1814", "2e2822", "443a30", "5c4e40", "766450"), leather=R("2a2018", "3e3024", "564434"),
                          cloth=("24242a", "34343c", "4a4a54"), accent=("5a4a2a", "8a7040", "b89858"), helm="hood", helm_mat="cloth",
                          chest="leather", over=("cross_straps", "belt"), arms="leather", boots="leather", gaunt="leather",
                          greaves="leather"),
    "nightingale": look(("0e0c10", "1a181e", "28262e", "3a3842", "524e5c"), leather=BLACK_LEATHER, cloth=("121016", "1e1c24", "2c2a34"),
                        accent=("3a3a5a", "56567e", "7a7aa8"), helm="hood", helm_mat="cloth", chest="leather",
                        over=("sash", "belt"), arms="leather", boots="leather", gaunt="leather", greaves="leather"),
    "shrouded": look(("140c0c", "241616", "382222", "4c3030", "644040"), leather=R("1a1010", "2a1a1a", "3c2626"),
                     cloth=("1a1010", "2e1a1a", "442828"), accent=("6a0a0a", "a01414", "d02828"), helm="hood", helm_mat="cloth",
                     chest="leather", over=("cross_straps", "emblem_hand", "belt"), arms="cloth", boots="leather", gaunt="leather",
                     greaves="cloth"),
    "vampire": look(("120c0e", "22161a", "362228", "4c3038", "664450"), trim=("4a4a52", "7a7a86", "aaaab6"),
                    cloth=("2a0c10", "461418", "6a2026"), accent=("6a0a10", "a01820", "d03040"), helm="hood", helm_mat="cloth",
                    chest="cloth", over=("trim_edges", "belt"), arms="cloth", boots="leather", gaunt="leather", greaves="cloth"),
    "chitin": look(("1a2a26", "2e4842", "48685e", "6a8e80", "98bcaa"), trim=("2a3a30", "44584a", "62786a"), helm="open",
                   helm_mat="chitin", chest="chitin", over=("belt",), arms="chitin", boots="chitin", gaunt="chitin", greaves="chitin",
                   crest="metal", horn=R("2e4842", "48685e", "6a8e80")),
    "imperial_light": look(("2e353c", "4e5862", "74808c", "a0acb8", "d4dee8"), trim=("5a3c10", "906420", "c8963a"),
                           cloth=("5a1010", "8a1c1a", "b02c26"), helm="open", chest="leather", over=("tabard", "belt"),
                           arms="cloth", boots="leather", gaunt="leather", greaves="leather", plume=R("6a1010", "a01c18", "d03a2e")),
    # ------------------------------------------------------------------ heavy
    "iron": look(IRON, trim=("3a3634", "5a5450", "7e7670"), helm="nose", chest="plate", over=("abs", "rivets", "belt"),
                 arms="metal", greaves="metal", horn=BONE_HORN, emblem=None),
    "banded_iron": look(IRON, trim=("5a3a1e", "7e5430", "a27244"), helm="nose", chest="plate", over=("bands", "belt"),
                        arms="metal", horn=None),
    "steel": look(STEEL, trim=("3a3f46", "5f656c", "8a9199"), helm="nose", chest="plate", over=("pecs", "belt"), arms="chain",
                  greaves="chain", horn=BONE_HORN),
    "steel_plate": look(("323a44", "56626e", "7e8c9a", "aebcc8", "e4eef6"), trim=("4a3a20", "6e5634", "927448"), helm="closed_slit",
                        chest="plate", over=("pecs", "abs", "center_ridge", "belt"), arms="metal"),
    "dwarven": look(("3a2408", "6a4214", "a26c2b", "d29e52", "f6dc94"), trim=("4f3110", "7d5420", "ad7d3a"),
                    accent=("1e4a52", "2e7a84", "6ab8c0"), helm="mask", chest="plate", over=("pecs", "center_ridge", "rivets", "belt"),
                    arms="metal", crest="trim"),
    "orcish": look(("161a12", "2a3224", "445038", "627056", "869478"), trim=("3a2a1a", "5a4028", "7c5a3a"), helm="open",
                   chest="plate", over=("abs", "rivets", "belt"), arms="metal", horn=R("2a3224", "627056", "a8b49a")),
    "ebony": look(("0a080c", "18141c", "2a2430", "443c4e", "6e6280"), trim=("100e12", "221e28", "3a3444"), accent=("3a2e52", "5a4a7e", "8a7cb4"),
                  helm="closed_slit", chest="plate", over=("pecs", "center_ridge", "sheen"), arms="metal", crest="metal"),
    "stalhrim_heavy": look(("16303e", "2c5468", "4e809a", "84b8d0", "d4f2ff"), trim=("1e242c", "343e4a", "4e5a6a"),
                           accent=("4a9ac0", "8ad0ec", "e0faff"), helm="closed_slit", chest="crystal", over=("pecs",),
                           arms="crystal", greaves="crystal", crest="crystal", glow=("4a9ac0", "8ad0ec", "e0faff")),
    "daedric": look(("0a0606", "180c0e", "2a1418", "402028", "5e3040"), trim=("0c0707", "1e1012", "361c22"), accent=("7a0b0b", "d0281e", "ff6a46"),
                    helm="closed_slit", chest="plate", over=("pecs", "glow_lines"), arms="metal", greaves="metal",
                    horn=R("180c0e", "2a1418", "402028"), glow=("7a0b0b", "d0281e", "ff6a46")),
    "dragonplate": look(("5a5040", "8a7e66", "b4a88c", "d8cdb0", "f6eedc"), trim=("3a2e22", "5a4632", "7a6046"), leather=DARK_LEATHER,
                        helm="open", chest="bone", over=("ribs", "belt"), arms="bone", greaves="bone", horn=BONE_HORN, crest="metal"),
    "nordic": look(("1e242e", "36404e", "56647a", "7e8ea6", "b4c2d6"), trim=("5c4418", "8a6826", "b8923e"), helm="nose", chest="plate",
                   over=("pecs", "knots", "belt"), arms="metal", fur=FUR_GREY),
    "bonemold": look(("5a4428", "84683e", "a88a5a", "c8ac7a", "e4cc9c"), trim=("4a1e14", "6e2e1e", "92402a"), leather=R("3a1e14", "56301e", "744430"),
                     helm="open", chest="plate", over=("bands", "belt"), arms="metal", crest="metal"),
    "ancient_nord": look(("242a22", "3a4236", "56604e", "76806a", "98a288"), trim=("4a3a22", "6e5634", "927448"), helm="nose",
                         chest="plate", over=("abs", "rust", "belt"), arms="metal", horn=R("3a4236", "76806a", "a8ae96")),
    "falmer": look(("2a2014", "4a3a22", "6e5834", "967c4e", "bca470"), trim=("2a3a3a", "3e5656", "5a7a78"), accent=("2e6a6a", "4aa09a", "8ae0d8"),
                   helm="mask", helm_mat="chitin", chest="chitin", over=("glow_lines",), arms="chitin", boots="chitin", gaunt="chitin",
                   greaves="chitin", horn=R("4a3a22", "6e5834", "a48a55"), glow=("2e6a6a", "4aa09a", "8ae0d8")),
    "blades": look(("2a1a14", "4a2a1e", "6e3c28", "925238", "b46c4c"), trim=("5a4410", "8a6a1e", "c09a38"), helm="open",
                   chest="lamellar", over=("belt", "emblem_dragon"), arms="lamellar", greaves="lamellar",
                   horn=R("5a4410", "8a6a1e", "c09a38"), accent=("5a4410", "8a6a1e", "c09a38")),
    "imperial": look(STEEL, trim=("5a3c10", "906420", "c8963a"), cloth=("5a1010", "8a1c1a", "b02c26"), helm="open", chest="plate",
                     over=("pecs", "imperial_skirt", "belt"), arms="cloth", greaves="leather", plume=R("6a1010", "a01c18", "d03a2e"),
                     emblem="imperial"),
    "stormcloak": look(("262c34", "3e4650", "5c6672", "7e8a98", "a8b4c2"), trim=("3a2a1a", "5a4028", "7c5a3a"),
                       cloth=("142a4a", "1e3e6e", "2e5a9a"), fur=FUR_BROWN, helm="nose", chest="chain", over=("sash", "belt"),
                       arms="cloth", boots="leather", gaunt="leather", greaves="leather", crest="trim"),
    "dawnguard": look(("1e2024", "34383e", "50565e", "727a84", "9ea6b0"), trim=("4a3a20", "6e5634", "927448"), cloth=("3a2a1e", "56402c", "74583c"),
                      accent=("6a4a14", "a07424", "d8aa4a"), helm="visor", chest="plate", over=("pecs", "emblem_sun", "belt"),
                      arms="metal", crest="metal"),
    "wolf": look(("16181c", "282c32", "40464e", "5c646e", "828c98"), trim=("3a2a1a", "5a4028", "7c5a3a"), fur=FUR_GREY, helm="nose",
                 chest="plate", over=("emblem_wolf", "belt"), arms="metal", boots="metal"),
    # ------------------------------------------------------------------ the provinces
    "netch": look(("2a2420", "443a32", "5e5248", "7a6c60", "988a7c"), leather=R("3a3028", "564a3e", "74665a"),
                  accent=("6a5a3a", "9a8454", "c4ae78"), helm="cap", helm_mat="leather", chest="leather", over=("netch_spots", "belt"),
                  arms="leather", boots="leather", gaunt="leather", greaves="leather", crest="metal"),
    "ordinator": look(("2a1a0c", "4a3018", "6e4a26", "967038", "c0984e"), trim=("6a4a10", "a07418", "d8a838"), cloth=("3a1a14", "56281e", "74382a"),
                      helm="mask", chest="plate", over=("pecs", "center_ridge", "trim_edges"), arms="metal", crest="trim"),
    "thalmor": look(("0e0e10", "1a1a1e", "28282e", "3a3a42", "50505a"), trim=("6a4a10", "a87a1e", "e0b040"), leather=BLACK_LEATHER,
                    cloth=("121214", "1e1e22", "2e2e34"), accent=("6a4a10", "a87a1e", "e0b040"), helm="hood", helm_mat="cloth",
                    chest="cloth", over=("trim_edges", "emblem_thalmor", "sash_gold"), arms="cloth", boots="leather", gaunt="leather",
                    greaves="cloth"),
    "bosmer": look(("2a2a14", "44441e", "62602c", "828040", "a4a05a"), leather=R("3a2a14", "56401e", "74582c"),
                   accent=("2e5a1e", "4a8a2e", "78bc4a"), cloth=("2e4a1e", "44682c", "609040"), helm="cap", helm_mat="leather",
                   chest="leather", over=("leaves", "belt"), arms="leather", boots="leather", gaunt="leather", greaves="leather",
                   horn=BONE_HORN),
    "ra_gada": look(("3a2a10", "5e441c", "86642c", "ae8840", "d4ac5c"), trim=("4a3a20", "6e5634", "927448"), cloth=("a08a64", "c4ac80", "e0cca0"),
                    accent=("6a1414", "a02020", "d03a32"), helm="turban", helm_mat="cloth", chest="scale", over=("sash", "belt"),
                    arms="cloth", boots="leather", gaunt="leather", greaves="cloth"),
    "breton": look(("2e3846", "4e5e72", "7a8ca2", "aabcd0", "e2ecf8"), trim=("5a4410", "8a6a1e", "c09a38"), cloth=("142a5a", "1e3e86", "2e5ab8"),
                   accent=("5a4410", "8a6a1e", "c09a38"), helm="greathelm", chest="plate", over=("tabard", "emblem_lion", "belt"),
                   arms="metal", plume=R("142a5a", "1e3e86", "2e5ab8")),
    "argonian": look(("142a22", "22443a", "346656", "4e8a76", "78b49c"), leather=R("2a2a16", "40401e", "5a5a2c"),
                     accent=("5a2a5a", "8a4a8a", "b87ab8"), helm="open", helm_mat="chitin", chest="chitin", over=("straps",),
                     arms="leather", boots="leather", gaunt="chitin", greaves="leather", crest="metal"),
    "khajiit": look(("4a2e10", "6e4618", "966424", "c08a38", "e8b45a"), trim=("6a4a10", "a87a1e", "e0b040"), cloth=("4a1440", "6e2060", "9a3486"),
                    accent=("6a4a10", "a87a1e", "e0b040"), helm="open", helm_mat="metal", chest="leather", over=("sash", "trim_edges", "belt"),
                    arms="cloth", boots="leather", gaunt="leather", greaves="cloth", crest="trim"),
    "orsinium": look(("12140e", "24281e", "3a4030", "565e48", "7a8466"), trim=("6e604a", "a49678", "dccfb2"), fur=FUR_BROWN,
                     helm="open", chest="plate", over=("abs", "rivets", "belt"), arms="metal", horn=BONE_HORN),
    "akaviri": look(("10182a", "1c2a46", "2a406a", "3e5a92", "5a7ec0"), trim=("5a4410", "8a6a1e", "c09a38"), accent=("6a1010", "a01c18", "d03a2e"),
                    helm="open", chest="lamellar", over=("belt",), arms="lamellar", greaves="lamellar", horn=R("5a4410", "8a6a1e", "c09a38")),
    "atmoran": look(("2a2e30", "464c50", "666e72", "8a9296", "b0b8bc"), trim=("3a2a1a", "5a4028", "7c5a3a"), fur=FUR_GREY,
                    helm="nose", chest="chain", over=("runes", "belt"), arms="fur", boots="fur", gaunt="leather", greaves="leather",
                    horn=BONE_HORN, accent=("2a5a8a", "4a8ac8", "9ad0ff")),
    "maormer": look(("0e3440", "1c5868", "2e8294", "5ab4c2", "a8e6ee"), trim=("4a5a66", "7a8c98", "aec0cc"), accent=("8a2a6a", "c04a9a", "f08ad0"),
                    helm="open", chest="scale", over=("pecs",), arms="scale", greaves="scale", crest="crystal",
                    glow=("2e8294", "5ab4c2", "a8e6ee")),
}


# ============================================================================================= surfaces

def mat_ramp(L, mat):
    """4-shade ramp (dark .. light, highlight) for a surface material."""
    m = L["metal"]
    if mat in ("metal", "plate", "scale", "bone", "chitin", "lamellar"):
        return [m[1], m[2], m[3], m[4]]
    if mat == "crystal":
        g = L["glow"] or L["accent"]
        return [g[0], g[1], g[2], mix(g[2], hexc("ffffff"), 0.5)]
    if mat == "chain":
        u = L["under"]
        return [u[0], u[1], u[2], mix(u[2], hexc("ffffff"), 0.3)]
    if mat == "leather":
        c = L["leather"]
        return [c[0], c[1], c[2], mix(c[2], hexc("ffffff"), 0.15)]
    if mat == "fur":
        c = L["fur"]
        return [c[0], c[1], c[2], mix(c[2], hexc("ffffff"), 0.2)]
    if mat == "cloth":
        c = L["cloth"]
        return [c[0], c[1], c[2], mix(c[2], hexc("ffffff"), 0.15)]
    if mat == "trim":
        t = L["trim"]
        return [t[0], t[1], t[2], mix(t[2], hexc("ffffff"), 0.35)]
    raise ValueError(mat)


def surface(L, mat, i, j, w, h, r, face="side"):
    """Colour of texel (i, j) of a w x h face made of `mat`."""
    c = mat_ramp(L, mat)
    if mat in ("metal", "plate"):
        col = c[1]
        if j == 0:
            col = c[2]
        if face == "top":
            col = c[2]
        if i == 0 or i == w - 1:
            col = mix(col, c[0], 0.35)
        if j == h - 1:
            col = c[0]
        if r.random() < 0.08:
            col = shade(col, 1.06)
        return col
    if mat == "scale":
        jj = j + (1 if (i // 2) % 2 else 0)
        return c[2] if jj % 2 == 0 and i % 2 == 0 else (c[0] if jj % 2 == 1 and i % 2 == 1 else c[1])
    if mat == "chain":
        return c[2] if (i + j) % 2 == 0 else c[0] if r.random() < 0.5 else c[1]
    if mat == "leather":
        col = c[1] if r.random() > 0.15 else c[0]
        if j == 0:
            col = c[2]
        return col
    if mat == "fur":
        v = r.random()
        col = c[2] if v > 0.72 else c[0] if v < 0.25 else c[1]
        if (i + j * 3) % 5 == 0:
            col = mix(col, c[3], 0.5)
        return col
    if mat == "cloth":
        col = c[1]
        if i % 3 == 2:
            col = c[0]
        if j == 0:
            col = c[2]
        return col
    if mat == "chitin":
        k = j % 3
        col = c[2] if k == 0 else (c[1] if k == 1 else c[0])
        if k == 0 and i in (0, w - 1):
            col = c[1]
        return col
    if mat == "lamellar":
        k = j % 3
        lace = L["accent"]
        if k == 2:
            return lace[1] if i % 2 == 0 else lace[0]
        return c[2] if k == 0 and i % 2 == 0 else c[1] if i % 2 == 0 else c[0]
    if mat == "crystal":
        frame = mat_ramp(L, "metal")
        if i == 0 or i == w - 1 or j == 0:
            return frame[2] if j == 0 else frame[1]
        # faceted panes: diagonal facet lines with bright glints
        k = (i + 2 * j) % 6
        if k == 0:
            return c[3]
        if k in (1, 2):
            return c[2]
        return c[1] if (i - j) % 4 else c[0]
    if mat == "bone":
        col = c[2] if j % 3 != 2 else c[0]
        if j % 3 == 0:
            col = c[3]
        return col
    if mat == "trim":
        return c[2] if j == 0 else c[1]
    raise ValueError(mat)


class Painter:
    def __init__(self, L, seed):
        self.L = L
        self.img = Image.new("RGBA", (64, 32), T)
        self.px = self.img.load()
        self.r = rnd(seed)

    def set(self, x, y, c):
        if 0 <= x < 64 and 0 <= y < 32:
            self.px[x, y] = c

    def fill(self, x0, y0, w, h, mat, face="side", rows=None):
        for j in range(h):
            if rows is not None and j not in rows:
                continue
            for i in range(w):
                self.set(x0 + i, y0 + j, surface(self.L, mat, i, j, w, h, self.r, face))

    def box_faces(self, u, v, w, h, d):
        """Face rects of a cube with UV origin (u, v): top, bottom, right, front, left, back."""
        return dict(top=(u + d, v, w, d), bottom=(u + d + w, v, w, d), right=(u, v + d, d, h), front=(u + d, v + d, w, h),
                    left=(u + d + w, v + d, d, h), back=(u + 2 * d + w, v + d, w, h))


# ============================================================================================= parts

def paint_helmet(P):
    L = P.L
    f = P.box_faces(0, 0, 8, 8, 8)
    mat = L["helm_mat"]
    hm = {"metal": "plate", "fur": "fur", "leather": "leather", "cloth": "cloth", "chitin": "chitin"}.get(mat, mat)
    helm = L["helm"]
    for name in ("top", "right", "front", "left", "back"):
        x, y, w, h = f[name]
        P.fill(x, y, w, h, hm, "top" if name == "top" else "side")
    t = mat_ramp(L, "trim")
    m = mat_ramp(L, hm)
    fx, fy = f["front"][0], f["front"][1]
    dark = shade(m[0], 0.5)

    def clear(x0, y0, x1, y1):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                P.set(fx + x, fy + y, T)

    if helm in ("open", "nose", "hood", "kabuto", "turban"):
        clear(1, 2 if helm != "hood" else 1, 6, 7)
        if helm == "nose":
            for y in range(1, 6):
                P.set(fx + 3, fy + y, t[2]); P.set(fx + 4, fy + y, t[1])
        if helm in ("open", "nose"):
            for x in range(8):
                P.set(fx + x, fy + 1, t[2] if x % 2 else t[1])   # brow band
        # sides: cut out the ears below the cheek line for open helmets
        for side in ("right", "left"):
            sx, sy, sw, sh = f[side]
            for y in range(6, 8):
                for x in range(sw):
                    if helm in ("hood",):
                        continue
                    P.set(sx + x, sy + y, T if helm == "open" and x in (3, 4) else P.px[sx + x, sy + y])
    elif helm in ("closed_slit", "visor", "greathelm"):
        for x in range(1, 7):
            P.set(fx + x, fy + 3, dark)
        if helm == "visor":
            g = L["glow"] or L["accent"]
            for x in range(1, 7):
                P.set(fx + x, fy + 3, g[1]); P.set(fx + x, fy + 4, g[0])
        if helm == "greathelm":
            for y in range(3, 7):
                P.set(fx + 3, fy + y, dark); P.set(fx + 4, fy + y, dark)
            for (x, y) in ((1, 5), (6, 5), (2, 6), (5, 6)):
                P.set(fx + x, fy + y, dark)
        if L["glow"] and helm == "closed_slit":
            P.set(fx + 2, fy + 3, L["glow"][2]); P.set(fx + 5, fy + 3, L["glow"][2])
        for y in range(8):
            P.set(fx + 3, fy + y, m[2] if y != 3 else dark)  # centre ridge
    elif helm == "mask":
        for (x, y) in ((1, 3), (2, 3), (5, 3), (6, 3), (3, 6), (4, 6)):
            P.set(fx + x, fy + y, dark)
        if L["glow"]:
            P.set(fx + 2, fy + 3, L["glow"][2]); P.set(fx + 5, fy + 3, L["glow"][2])
        for y in range(8):
            P.set(fx + 0, fy + y, t[1]); P.set(fx + 7, fy + y, t[1])
    elif helm == "cap":
        clear(0, 3, 7, 7)
        for side in ("right", "left", "back"):
            sx, sy, sw, sh = f[side]
            for y in range(4, 8):
                for x in range(sw):
                    P.set(sx + x, sy + y, T)
        for x in range(8):
            P.set(fx + x, fy + 2, t[1] if x % 2 else t[0])
    # crest stripe on top
    tx, ty, tw, th = f["top"]
    for z in range(8):
        P.set(tx + 3, ty + z, t[2]); P.set(tx + 4, ty + z, t[1])
    # bottom of the head stays open


def paint_body(P):
    L = P.L
    f = P.box_faces(16, 16, 8, 12, 4)
    chest = L["chest"]
    cm = {"plate": "plate", "scale": "scale", "chain": "chain", "leather": "leather", "fur": "fur", "cloth": "cloth",
          "chitin": "chitin", "lamellar": "lamellar", "crystal": "crystal", "bone": "bone"}[chest]
    for name in ("top", "right", "front", "left", "back"):
        x, y, w, h = f[name]
        P.fill(x, y, w, h, cm, "top" if name == "top" else "side")
    fx, fy = f["front"][0], f["front"][1]
    bx, by = f["back"][0], f["back"][1]
    t = mat_ramp(L, "trim")
    m = mat_ramp(L, "plate")
    lea = mat_ramp(L, "leather")
    acc = L["accent"]
    for o in L["over"]:
        if o == "pecs":
            for x in range(8):
                for y in range(1, 5):
                    c = m[2] if y == 1 else m[1]
                    if x in (3, 4):
                        c = m[0] if y > 1 else m[1]
                    if y == 4:
                        c = m[0]
                    P.set(fx + x, fy + y, c)
        elif o == "abs":
            for y in (6, 8):
                for x in range(1, 7):
                    P.set(fx + x, fy + y, m[0])
                    P.set(fx + x, fy + y - 1, m[2] if x in (1, 2, 5, 6) else P.px[fx + x, fy + y - 1])
        elif o == "center_ridge":
            for y in range(0, 9):
                P.set(fx + 3, fy + y, m[3]); P.set(fx + 4, fy + y, m[1])
        elif o == "belt":
            for x in range(8):
                for y in (9, 10):
                    P.set(fx + x, fy + y, lea[1] if y == 9 else lea[0])
                    P.set(bx + x, by + y, lea[1] if y == 9 else lea[0])
            P.set(fx + 3, fy + 9, t[2]); P.set(fx + 4, fy + 9, t[2]); P.set(fx + 3, fy + 10, t[1]); P.set(fx + 4, fy + 10, t[0])
            for side in ("right", "left"):
                sx, sy, sw, sh = f[side]
                for x in range(sw):
                    P.set(sx + x, sy + 9, lea[1]); P.set(sx + x, sy + 10, lea[0])
        elif o == "rivets":
            for (x, y) in ((1, 1), (6, 1), (1, 7), (6, 7), (0, 4), (7, 4)):
                P.set(fx + x, fy + y, t[2])
        elif o == "studs":
            for x in range(8):
                for y in range(12):
                    if (x + y) % 3 == 0 and y < 9:
                        P.set(fx + x, fy + y, t[2] if (x + y) % 2 else t[1])
                        P.set(bx + x, by + y, t[1])
        elif o == "bands":
            for y in (2, 5, 8):
                for x in range(8):
                    P.set(fx + x, fy + y, t[1]); P.set(bx + x, by + y, t[1])
                    P.set(fx + x, fy + y + 1, m[0])
        elif o in ("straps", "cross_straps"):
            for k in range(9):
                P.set(fx + 1 + k * 6 // 9, fy + k, lea[0]); P.set(fx + 2 + k * 6 // 9, fy + k, lea[1])
                P.set(bx + 1 + k * 6 // 9, by + k, lea[0])
                if o == "cross_straps":
                    P.set(fx + 6 - k * 6 // 9, fy + k, lea[0]); P.set(fx + 5 - k * 6 // 9, fy + k, lea[1])
            P.set(fx + 3, fy + 4, t[2])
        elif o in ("sash", "sash_gold"):
            col = acc if o == "sash" else L["trim"]
            for k in range(10):
                x = 6 - k * 6 // 9
                P.set(fx + x, fy + k, col[1]); P.set(fx + x + 1, fy + k, col[2])
                P.set(bx + 1 + k * 6 // 9, by + k, col[1])
        elif o == "tabard":
            cl = mat_ramp(L, "cloth")
            for x in range(2, 6):
                for y in range(3, 12):
                    P.set(fx + x, fy + y, cl[1] if x in (3, 4) else cl[0])
            for x in range(2, 6):
                P.set(fx + x, fy + 3, t[1])
        elif o == "imperial_skirt":
            cl = mat_ramp(L, "cloth")
            for x in range(8):
                P.set(fx + x, fy + 11, cl[1]); P.set(bx + x, by + 11, cl[1])
        elif o == "trim_edges":
            for y in range(12):
                P.set(fx + 0, fy + y, t[2]); P.set(fx + 7, fy + y, t[1])
            for x in range(8):
                P.set(fx + x, fy + 0, t[2])
        elif o == "glow_lines":
            # veins of molten light branching out from the sternum
            g = L["glow"]
            for (x, y, k) in ((3, 2, 2), (4, 3, 1), (3, 4, 1), (4, 5, 2), (2, 3, 1), (1, 4, 1), (5, 4, 1), (6, 5, 1),
                              (2, 6, 1), (1, 7, 2), (5, 6, 1), (6, 7, 2)):
                P.set(fx + x, fy + y, g[k])
            for (x, y) in ((3, 2), (4, 3), (3, 4), (4, 5), (3, 6), (2, 7), (5, 7)):
                P.set(bx + x, by + y, g[1])
        elif o == "sheen":
            for y in range(0, 9, 2):
                P.set(fx + 1, fy + y, acc[2])
        elif o == "ribs":
            tt = L["trim"]
            for y in (2, 4, 6):
                for x in range(1, 7):
                    if x not in (3, 4):
                        P.set(fx + x, fy + y, tt[2] if x in (1, 6) else tt[1])
        elif o == "knots":
            for (x, y) in ((1, 2), (2, 3), (5, 3), (6, 2), (3, 6), (4, 6), (2, 7), (5, 7)):
                P.set(fx + x, fy + y, t[2])
        elif o == "rust":
            for _ in range(10):
                P.set(fx + P.r.randrange(8), fy + P.r.randrange(12), hexc("6a4428"))
        elif o == "netch_spots":
            for (x, y) in ((1, 2), (5, 1), (3, 5), (6, 6), (2, 8)):
                P.set(fx + x, fy + y, acc[1])
        elif o == "leaves":
            g = L["accent"]
            for (x, y) in ((1, 1), (2, 2), (6, 1), (5, 2), (1, 6), (6, 6), (3, 7)):
                P.set(fx + x, fy + y, g[2] if (x + y) % 2 else g[1])
        elif o == "runes":
            g = L["accent"]
            for (x, y) in ((3, 2), (4, 3), (3, 4), (4, 5)):
                P.set(fx + x, fy + y, g[2])
        elif o.startswith("emblem"):
            paint_emblem(P, fx, fy, o, L)


EMBLEMS = {
    "emblem_hand": ["....", ".r.r", "rrrr", "rrrr", ".rr."],
    "emblem_dragon": ["g..g", ".gg.", "gggg", ".gg.", "g..g"],
    "emblem_sun": [".g.", "ggg", ".g."],
    "emblem_wolf": ["w..w", "wwww", ".ww.", ".w.."],
    "emblem_thalmor": [".g.", "g.g", "ggg"],
    "emblem_lion": [".gg.", "gggg", ".gg.", "g..g"],
}


def paint_emblem(P, fx, fy, key, L):
    rows = EMBLEMS.get(key)
    if not rows:
        return
    cols = {"r": L["accent"][1], "g": L["trim"][2], "w": mix(L["metal"][4], hexc("ffffff"), 0.3)}
    x0 = 4 - len(rows[0]) // 2
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in cols:
                P.set(fx + x0 + x, fy + 2 + y, cols[ch])


def paint_arms(P):
    L = P.L
    f = P.box_faces(40, 16, 4, 12, 4)
    am = {"metal": "plate"}.get(L["arms"], L["arms"])
    rows = range(0, 6) if am not in ("cloth",) else range(0, 8)
    for name in ("top", "right", "front", "left", "back"):
        x, y, w, h = f[name]
        if name == "top":
            P.fill(x, y, w, h, am, "top")
        else:
            P.fill(x, y, w, h, am, "side", rows=rows)
    t = mat_ramp(L, "trim")
    for name in ("right", "front", "left", "back"):
        x, y, w, h = f[name]
        last = max(rows)
        for i in range(w):
            P.set(x + i, y + last, t[1])


def paint_boots(P, layer_img=None):
    L = P.L
    f = P.box_faces(0, 16, 4, 12, 4)
    bm = {"metal": "plate"}.get(L["boots"], L["boots"])
    for name in ("right", "front", "left", "back"):
        x, y, w, h = f[name]
        P.fill(x, y, w, h, bm, "side", rows=range(6, 12))
        t = mat_ramp(L, "trim") if bm == "plate" else mat_ramp(L, bm)
        for i in range(w):
            P.set(x + i, y + 6, t[2])
            P.set(x + i, y + 11, shade(mat_ramp(L, bm)[0], 0.6))
    bx, by, bw, bh = f["bottom"]
    for i in range(bw):
        for j in range(bh):
            P.set(bx + i, by + j, shade(mat_ramp(L, "leather")[0], 0.7))


def paint_greaves(P):
    L = P.L
    f = P.box_faces(0, 16, 4, 12, 4)
    gm = {"metal": "plate"}.get(L["greaves"], L["greaves"])
    for name in ("right", "front", "left", "back"):
        x, y, w, h = f[name]
        P.fill(x, y, w, h, gm, "side", rows=range(0, 12))
    if gm in ("plate", "crystal", "bone", "scale"):
        m = mat_ramp(L, gm)
        x, y, w, h = f["front"]
        for i in range(w):
            P.set(x + i, y + 4, m[3]); P.set(x + i, y + 5, m[0])
    # belt on the inner body
    fb = P.box_faces(16, 16, 8, 12, 4)
    lea = mat_ramp(L, "leather")
    for name in ("right", "front", "left", "back"):
        x, y, w, h = fb[name]
        for i in range(w):
            for j in range(8, 12):
                P.set(x + i, y + j, lea[1] if j == 8 else (lea[0] if j == 11 else surface(L, gm if gm != "crystal" else "plate", i, j, w, h, P.r)))


def paint_gauntlet(P):
    L = P.L
    gm = {"metal": "plate"}.get(L["gaunt"], L["gaunt"])
    f = P.box_faces(40, 16, 4, 6, 4)
    for name in ("top", "right", "front", "left", "back"):
        x, y, w, h = f[name]
        P.fill(x, y, w, h, gm, "top" if name == "top" else "side")
    t = mat_ramp(L, "trim") if gm == "plate" else mat_ramp(L, gm)
    for name in ("right", "front", "left", "back"):
        x, y, w, h = f[name]
        for i in range(w):
            P.set(x + i, y, t[2])
            P.set(x + i, y + h - 1, shade(mat_ramp(L, gm)[0], 0.75))
    x, y, w, h = f["bottom"]
    lea = mat_ramp(L, "leather")
    for i in range(w):
        for j in range(h):
            P.set(x + i, y + j, lea[0])


# ============================================================================================= swatches

def paint_swatch(P, kind, region):
    L = P.L
    x0, y0, w, h = region
    for j in range(h):
        for i in range(w):
            P.set(x0 + i, y0 + j, swatch_color(L, kind, i, j, w, h, P.r))


def swatch_color(L, kind, i, j, w, h, r):
    if kind == "plate":
        m = mat_ramp(L, "plate" if L["chest"] not in ("leather", "fur", "cloth", "chitin", "lamellar", "scale", "crystal", "bone", "chain")
                     else ("leather" if L["chest"] in ("fur", "cloth") else L["chest"] if L["chest"] != "chain" else "plate"))
        col = m[1] if j > 1 else m[2]
        if j == 0:
            col = m[3]
        if (i + j) % 7 == 0:
            col = mix(col, m[0], 0.4)
        if j == h - 1:
            col = m[0]
        return col
    if kind == "trim":
        t = mat_ramp(L, "trim")
        return t[2] if j == 0 else (t[1] if (i + j) % 4 else t[0])
    if kind == "horn":
        hr = L["horn"] or [L["metal"][1], L["metal"][2], L["metal"][3]]
        # rows go from the tip (top) to the base
        k = j / max(1, h - 1)
        col = mix(hr[2], hr[0], k)
        if i % 4 == 3:
            col = shade(col, 0.85)
        return col
    if kind == "crest":
        src = L["crest"]
        if src == "crystal":
            g = L["glow"] or L["accent"]
            return g[2] if (i + j) % 3 == 0 else g[1]
        rr = mat_ramp(L, "trim" if src == "trim" else "plate")
        return rr[3] if j == 0 else (rr[2] if j < 3 else rr[1])
    if kind == "fur":
        return surface(L, "fur", i, j, w, h, r)
    if kind == "cloth":
        return surface(L, "cloth", i, j, w, h, r)
    if kind == "leather":
        return surface(L, "leather", i, j, w, h, r)
    if kind == "lamellar":
        return surface(L, "lamellar", i, j, w, h, r)
    if kind == "crystal":
        g = L["glow"] or L["accent"]
        return g[2] if (i + 2 * j) % 5 == 0 else (g[1] if (i + j) % 2 else mix(g[1], g[0], 0.4))
    if kind == "plume":
        p = L["plume"] or L["accent"]
        return p[2] if j == 0 else (p[1] if (i + j) % 3 else p[0])
    raise ValueError(kind)


# ============================================================================================= layers

def layers(set_id, style_id):
    L = LOOKS[set_id]
    built = geo.build(style_id)
    p1 = Painter(L, zlib.crc32(set_id.encode()) % 9973)
    paint_helmet(p1)
    paint_body(p1)
    paint_arms(p1)
    paint_boots(p1)
    for kind, region in built["swatches"][1].items():
        paint_swatch(p1, kind, region)
    p2 = Painter(L, zlib.crc32(set_id.encode()) % 9973 + 1)
    paint_greaves(p2)
    paint_gauntlet(p2)
    for kind, region in built["swatches"][2].items():
        paint_swatch(p2, kind, region)
    return p1.img, p2.img, built
