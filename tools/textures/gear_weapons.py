"""Weapon and bow pixel art: every material gets its own shape language, not one silhouette per type recoloured.

Weapons are drawn on the diagonal lattice of gear_px.Lattice (a = along the weapon, b = across it). A weapon is a
stack of parts (pommel, grip, guard, blade or haft and head); each material picks a style for every part plus a
palette, and the styles carry the silhouette: Daedric blades are jagged with red veins, Elven ones curve in gold
with winged guards, Glass is faceted green crystal, Ebony dark and angular, Dwarven geometric bronze with chisel
tips, Orcish hooked and spiked, Dragonbone ridged bone with teeth, Akaviri katanas curve with a disc guard...
"""
import math

import gear_heads
from gear_px import Lattice, alpha, dist, hexc, mix, ramp, rnd, shade

# --------------------------------------------------------------------------------------------- materials
# metal: dark, mid, light, highlight; trim (guard/pommel/fittings) and grip: dark, mid, light; accent: glow/gem/inlay
MAT = {
    "iron": dict(metal=ramp("4b4a4c", "727174", "9d9ca0", "c8c7cb"), trim=ramp("3d3631", "5d544c", "80766a"),
                 grip=ramp("3b2616", "5a3b22", "7a5332"), accent=None, wood=ramp("4a3220", "6e4b2e", "8f673f"),
                 blade="iron", guard="cross", pommel="round", axe="bearded", mace="flanged", hammer="block", bow="long"),
    "steel": dict(metal=ramp("4d5862", "7c8994", "b2bec8", "eaf2f8"), trim=ramp("3a3d42", "5f656c", "8a9199"),
                  grip=ramp("2f1f13", "4d3320", "684630"), accent=None, wood=ramp("3d2a1a", "5a3e26", "7a5634"),
                  blade="fuller", guard="nord_curl", pommel="disk", axe="crescent", mace="spiked_flange", hammer="beak", bow="hunting"),
    "orcish": dict(metal=ramp("2d3426", "4a5540", "6e7c5f", "97a585"), trim=ramp("2a2a22", "45443a", "666454"),
                   grip=ramp("2a1d12", "45311e", "5e452b"), accent=ramp("5a2410", "8a3e1c", "b4602e"), wood=ramp("33291c", "4d3e2a", "665338"),
                   blade="hooked", guard="tusks", pommel="spike", axe="orc_spiked", mace="spike_ball", hammer="orc_maul", bow="orcish"),
    "dwarven": dict(metal=ramp("6a4214", "a26c2b", "d29e52", "f6dc94"), trim=ramp("4f3110", "7d5420", "ad7d3a"),
                    grip=ramp("3a2a1a", "5a432b", "75593a"), accent=ramp("2c4a52", "4a7a84", "7fb4bc"), wood=ramp("553411", "86581f", "b8823c"),
                    blade="chisel", guard="block", pommel="square", axe="dwemer_axe", mace="dwemer_mace", hammer="dwemer_hammer", bow="dwarven"),
    "elven": dict(metal=ramp("7a5816", "b88b2c", "e3c05a", "fff2ae"), trim=ramp("6c4e14", "a87c26", "d6b04e"),
                  grip=ramp("27401f", "3e6230", "5c8a44"), accent=ramp("1f5a46", "2f8a6a", "62c2a0"), wood=ramp("7a5816", "b88b2c", "e3c05a"),
                  blade="leaf_curve", guard="wings", pommel="teardrop", axe="elf_crescent", mace="elf_mace", hammer="elf_hammer", bow="elven"),
    "glass": dict(metal=ramp("2a6c38", "48a85a", "8adc98", "dcffe4", a=232), trim=ramp("6b7a74", "9fb1aa", "d2e2dc"),
                  grip=ramp("23352a", "36503f", "4b6a56"), accent=ramp("2d8a52", "5ccc84", "b8ffd0"), wood=ramp("6b7a74", "9fb1aa", "d2e2dc"),
                  blade="crystal", guard="crystal", pommel="gem", axe="crystal_axe", mace="crystal_mace", hammer="crystal_hammer", bow="glass"),
    "ebony": dict(metal=ramp("110e14", "221c28", "3a3244", "6a5c80"), trim=ramp("0d0b10", "1e1824", "382e42"),
                  grip=ramp("1c1510", "30251b", "433427"), accent=ramp("2c2438", "504468", "8a7cb4"), wood=ramp("110e14", "221c28", "3a3244"),
                  blade="angular", guard="ebony", pommel="ebony", axe="angular_axe", mace="ebony_mace", hammer="angular_hammer", bow="ebony"),
    "daedric": dict(metal=ramp("100909", "22131a", "382029", "5c3343"), trim=ramp("0c0707", "1e1012", "361c22"),
                    grip=ramp("1d1010", "321a1a", "472525"), accent=ramp("7a0b0b", "d0281e", "ff6a46"), wood=ramp("100909", "22131a", "382029"),
                    blade="jagged", guard="horns", pommel="spike", axe="daedric_axe", mace="daedric_mace", hammer="daedric_hammer", bow="daedric"),
    "dragonbone": dict(metal=ramp("8a7e68", "bcb196", "e0d6be", "fbf6e8"), trim=ramp("4e4132", "73624b", "98856a"),
                       grip=ramp("2e2219", "4a382a", "64503d"), accent=ramp("3a2e25", "584638", "7a6450"), wood=ramp("8a7e68", "bcb196", "e0d6be"),
                       blade="bone_teeth", guard="dragon", pommel="claw", axe="bone_axe", mace="bone_mace", hammer="bone_hammer", bow="dragonbone"),
    "silver": dict(metal=ramp("707784", "a9b2be", "dce3ec", "ffffff"), trim=ramp("7a5a16", "b88b2c", "e8c862"),
                   grip=ramp("22243a", "373a58", "50547a"), accent=ramp("3a5aa0", "5a82d0", "a8c6ff"), wood=ramp("4a3220", "6e4b2e", "8f673f"),
                   blade="slender", guard="ornate", pommel="gem", axe="glaive_axe", mace="breton_mace", hammer="beak", bow="silver"),
    "ancient_nord": dict(metal=ramp("36392f", "585d4c", "7f8670", "a6ad96"), trim=ramp("3a3428", "5a5040", "7c705a"),
                         grip=ramp("2e2418", "463826", "5e4c34"), accent=ramp("4c6a5a", "6c9480", "9cc4ae"), wood=ramp("3e3020", "5a4630", "765e42"),
                         blade="wavy", guard="spiral", pommel="ring", axe="bearded", mace="flanged", hammer="block", bow="ancient"),
    "forsworn": dict(metal=ramp("8a7a5e", "b8a884", "dccda8", "f2e8d0"), trim=ramp("5a3c22", "7a5432", "9c7044"),
                     grip=ramp("40281a", "5e3c26", "7c5234"), accent=ramp("7a1c14", "b4321e", "e0603a"), wood=ramp("4a3020", "6c4a2e", "8e663e"),
                     blade="bone_shard", guard="feathers", pommel="knot", axe="stone_axe", mace="antler_club", hammer="stone_maul", bow="forsworn"),
    "imperial": dict(metal=ramp("525a62", "88929c", "bac4cd", "eef3f8"), trim=ramp("6e4c18", "a8782a", "d8aa50"),
                     grip=ramp("4a1a14", "6e2a20", "923a2c"), accent=ramp("6e4c18", "a8782a", "d8aa50"), wood=ramp("4a3220", "6e4b2e", "8f673f"),
                     blade="gladius", guard="imperial", pommel="ball", axe="crescent", mace="flanged", hammer="block", bow="imperial"),
    "falmer": dict(metal=ramp("4a3a22", "776039", "a48a55", "cdb87e"), trim=ramp("33281a", "54432c", "766040"),
                   grip=ramp("2a2016", "40321f", "584630"), accent=ramp("2e5a5a", "4a8a86", "86c8c0"), wood=ramp("4a3a22", "776039", "a48a55"),
                   blade="chitin_curve", guard="chitin", pommel="knob", axe="chitin_axe", mace="chitin_mace", hammer="chitin_hammer", bow="falmer"),
    "nordic": dict(metal=ramp("3a4350", "636f82", "95a2b5", "ccd6e2"), trim=ramp("5c4418", "8a6826", "b8923e"),
                   grip=ramp("2a1e16", "443226", "5e4634"), accent=ramp("2a3240", "3e4a5c", "56667c"), wood=ramp("3a2a1e", "56402c", "70563c"),
                   blade="carved", guard="knot", pommel="disk", axe="nordic_axe", mace="nordic_mace", hammer="nordic_hammer", bow="nordic"),
    "skyforge": dict(metal=ramp("5a636e", "94a0ac", "cad4de", "f6fbff"), trim=ramp("5c4a2a", "8a7040", "b8985a"),
                     grip=ramp("2a1c12", "46301e", "62442a"), accent=ramp("9a4a12", "d8782a", "ffb05a"), wood=ramp("3d2a1a", "5a3e26", "7a5634"),
                     blade="broad_fuller", guard="dragon_wings", pommel="disk", axe="skyforge_axe", mace="spiked_flange", hammer="beak", bow="skyforge"),
    "akaviri": dict(metal=ramp("5a626c", "98a2ac", "d0d8e0", "ffffff"), trim=ramp("6a4c1c", "a07428", "d0a248"),
                    grip=ramp("141826", "262c44", "3c4468"), accent=ramp("8a1a1a", "c03030", "e86060"), wood=ramp("2a1a12", "442a1c", "5e3c28"),
                    blade="katana", guard="tsuba", pommel="kashira", axe="akaviri_axe", mace="kanabo", hammer="akaviri_hammer", bow="yumi"),
    "stalhrim": dict(metal=ramp("2c5c76", "5898b6", "98d0e6", "e6faff", a=228), trim=ramp("3a5a6a", "5e8296", "8eb2c4"),
                     grip=ramp("1e2a34", "30404e", "46586a"), accent=ramp("6ab8d8", "a8e4f6", "ffffff"), wood=ramp("3a5a6a", "5e8296", "8eb2c4"),
                     blade="ice", guard="ice", pommel="gem", axe="ice_axe", mace="ice_mace", hammer="ice_hammer", bow="stalhrim"),
    # ------------------------------------------------------------------ the provinces
    "chitin": dict(metal=ramp("35402a", "56683c", "82945a", "b0c08a"), trim=ramp("5c4a30", "84704c", "ac9a72"),
                   grip=ramp("3a2a1a", "56402a", "70583c"), accent=ramp("3c5a2a", "5a8a3e", "8ac060"), wood=ramp("5c4a30", "84704c", "ac9a72"),
                   blade="chitin_short", guard="chitin", pommel="knob", axe="chitin_axe", mace="chitin_club", hammer="chitin_hammer", bow="chitin"),
    "redguard": dict(metal=ramp("525a62", "8a949e", "c0cad2", "f0f6fa"), trim=ramp("6e4c18", "a8782a", "dcb25a"),
                     grip=ramp("3a1a24", "5a2a38", "7a3c4c"), accent=ramp("1a5a6a", "2a8aa0", "6ac8dc"), wood=ramp("4a3220", "6e4b2e", "8f673f"),
                     blade="scimitar", guard="crescent_small", pommel="bird", axe="tabar", mace="flanged", hammer="block", bow="redguard"),
    "yokudan": dict(metal=ramp("3c4c64", "627898", "9aaec8", "dae8f6"), trim=ramp("6a4c1c", "a07a2c", "d8b05a"),
                    grip=ramp("1a2a3a", "2a4058", "3e5a7a"), accent=ramp("1a6a6a", "2aa0a0", "7adcdc"), wood=ramp("3a2a1e", "56402c", "70563c"),
                    blade="yokudan", guard="ornate", pommel="ball", axe="glaive_axe", mace="flanged", hammer="block", bow="yokudan"),
    "khajiit": dict(metal=ramp("5a5e66", "929aa4", "c8d0d8", "f4f8fc"), trim=ramp("7a5214", "b8822a", "eab858"),
                    grip=ramp("4a2a12", "6e421e", "925a2c"), accent=ramp("6a1a5a", "a0308a", "dc6ac4"), wood=ramp("5a3a1e", "7e5430", "a27244"),
                    blade="saber", guard="moon", pommel="ball", axe="elf_crescent", mace="flanged", hammer="block", bow="khajiit"),
    "bosmer": dict(metal=ramp("8a7a5a", "b8a680", "dccca4", "f4ead2"), trim=ramp("2e4a22", "4a7034", "6e9a4c"),
                   grip=ramp("2e4a22", "4a7034", "6e9a4c"), accent=ramp("6a3a1a", "9a5a2a", "c8843c"), wood=ramp("8a7a5a", "b8a680", "dccca4"),
                   blade="bone_leaf", guard="leaves", pommel="knot", axe="bone_axe", mace="bone_mace", hammer="bone_hammer", bow="bosmer"),
    "breton": dict(metal=ramp("4c5868", "7e8ea2", "b4c4d6", "eef4fc"), trim=ramp("4a4f58", "767d88", "a4abb6"),
                   grip=ramp("1e2a4a", "2e4070", "44609a"), accent=ramp("1e3a8a", "3a62c8", "8aaaf0"), wood=ramp("3d2a1a", "5a3e26", "7a5634"),
                   blade="arming", guard="long_cross", pommel="diamond", axe="crescent", mace="breton_mace", hammer="beak", bow="breton"),
    "argonian": dict(metal=ramp("2a4a46", "407068", "62a094", "a0d8c8"), trim=ramp("5a4a2a", "806c40", "a8925a"),
                     grip=ramp("2a3a1e", "40562c", "58743e"), accent=ramp("6a2a5a", "9a4a8a", "c87ab8"), wood=ramp("4a3a22", "6a5432", "8a7046"),
                     blade="hook", guard="chitin", pommel="knob", axe="chitin_axe", mace="hist_club", hammer="block", bow="argonian"),
    "atmoran": dict(metal=ramp("3e4448", "62696e", "8c9498", "b8c0c4"), trim=ramp("4a3a2a", "6e5a40", "92785a"),
                    grip=ramp("2a2018", "403226", "584634"), accent=ramp("2a5a8a", "4a8ac8", "9ad0ff"), wood=ramp("3a2c20", "54402e", "6e563e"),
                    blade="runic", guard="lobed", pommel="lobed", axe="bearded", mace="flanged", hammer="block", bow="ancient"),
    "maormer": dict(metal=ramp("1e5a6a", "3a8ea0", "7ac8d4", "d4f6fa", a=236), trim=ramp("1a4a5a", "2e7082", "56a2b2"),
                    grip=ramp("16303a", "244a58", "386878"), accent=ramp("8a2a6a", "c04a9a", "f08ad0"), wood=ramp("1a4a5a", "2e7082", "56a2b2"),
                    blade="serpent", guard="fins", pommel="fin", axe="fin_axe", mace="flanged", hammer="block", bow="maormer"),
}


# --------------------------------------------------------------------------------------------- blades
# Blade styles: base half-width w (lattice b units: 1 = 2px row, 2 = 3px), along-profile modifiers and paint.
BLADES = {
    "iron":         dict(w=1.0, tip=4, paint="rough"),
    "fuller":       dict(w=1.6, tip=4, paint="fuller"),
    "hooked":       dict(w=1.4, tip=3, widen=0.9, back_spikes=5, paint="rough", hook=True),
    "chisel":       dict(w=2.0, tip=3, chisel=True, paint="rivets"),
    "leaf_curve":   dict(w=1.3, tip=5, leaf=0.8, curve=-1.2, paint="inlay"),
    "crystal":      dict(w=1.6, tip=5, facets=True, paint="crystal"),
    "angular":      dict(w=2.0, tip=6, notch=True, paint="sheen"),
    "jagged":       dict(w=1.6, tip=4, saw=4, back_spikes=6, paint="vein"),
    "bone_teeth":   dict(w=1.6, tip=4, teeth=3, paint="ridges"),
    "slender":      dict(w=0.9, tip=5, paint="mirror"),
    "wavy":         dict(w=1.4, tip=3, wave=True, paint="rust"),
    "bone_shard":   dict(w=1.5, tip=5, rough_edge=True, saw=5, paint="bone"),
    "gladius":      dict(w=1.6, tip=4, leaf=0.7, short=0.78, paint="fuller"),
    "chitin_curve": dict(w=1.4, tip=4, curve=-2.2, saw=3, paint="segments"),
    "carved":       dict(w=2.0, tip=4, paint="carve"),
    "broad_fuller": dict(w=2.0, tip=4, paint="fuller"),
    "katana":       dict(w=1.0, tip=3, curve=-2.4, paint="hamon", long=1.04),
    "ice":          dict(w=1.6, tip=6, facets=True, back_spikes=7, paint="ice"),
    "chitin_short": dict(w=1.6, tip=4, short=0.82, curve=-1.0, paint="segments"),
    "scimitar":     dict(w=1.2, tip=4, widen=0.9, curve=-2.0, paint="mirror"),
    "yokudan":      dict(w=1.2, tip=4, curve=-2.0, paint="hamon", long=1.04),
    "saber":        dict(w=1.2, tip=4, curve=-2.4, paint="inlay"),
    "bone_leaf":    dict(w=1.1, tip=5, leaf=0.7, paint="bone"),
    "arming":       dict(w=1.3, tip=4, paint="fuller", long=1.04),
    "hook":         dict(w=1.3, tip=3, curve=-1.0, hook=True, paint="segments"),
    "runic":        dict(w=1.9, tip=4, leaf=0.4, paint="runes"),
    "serpent":      dict(w=1.4, tip=5, wave=True, curve=-1.6, paint="crystal"),
}

# geometry per weapon family: pommel, grip end, guard, blade start, blade end (lattice a)
LAYOUT = {
    "dagger":     dict(pommel=6, grip=(7, 10), guard=11, blade=(12, 23), wscale=0.85, glen=2.2),
    "sword":      dict(pommel=1, grip=(2, 6), guard=7, blade=(8, 28), wscale=1.0, glen=3.0),
    "greatsword": dict(pommel=0, grip=(1, 7), guard=8, blade=(9, 29), wscale=1.35, glen=4.0),
}


def _blade(cv, M, st, a0, a1, wscale, seed, two_handed=False):
    """Draws the blade from a0 (guard side) to a1 (tip)."""
    met = M["metal"]
    acc = M["accent"] or M["trim"]
    r = rnd(seed)
    L = a1 - a0
    rows = {}
    for x, y, a, b in cv.cells():
        if not (a0 <= a <= a1):
            continue
        t = (a - a0) / max(1, L)
        w = st["w"] * wscale
        if st.get("widen"):
            w += st["widen"] * t * wscale
        if st.get("leaf"):
            w += st["leaf"] * math.sin(math.pi * min(1.0, t * 1.15)) * wscale
        if st.get("wave"):
            w += 0.6 * (1 if (a // 3) % 2 == 0 else -0.4)
        if st.get("facets"):
            w += 0.55 * (1 if (a // 4) % 2 == 0 else 0)
        if st.get("notch") and t < 0.18:
            w = max(1.0, w - 1.0)
        tip = st["tip"] * (1.25 if two_handed else 1.0)
        if a > a1 - tip:
            k = (a1 - a) / tip
            w = w * k if not st.get("chisel") else w
        c = st.get("curve", 0) * t * t * wscale
        lo, hi = c - w, c + w
        if st.get("chisel") and a > a1 - tip:
            # dwarven chisel tip: the lower edge cuts straight across towards the upper point
            hi = c + w - 2.2 * (a - (a1 - tip))
        if st.get("hook") and a > a1 - 3:
            lo -= 1.2
        if st.get("rough_edge") and r.random() < 0.35:
            hi -= 1
        if lo - 1e-6 <= b <= hi + 1e-6:
            rows.setdefault(a, []).append(b)
            continue
        # protrusions beyond the profile
        if st.get("saw") and a0 + 2 <= a <= a1 - 3 and a % st["saw"] == 0 and hi < b <= hi + 2.01:
            rows.setdefault(a, []).append(b)
        elif st.get("back_spikes") and a0 + 3 <= a <= a1 - 4 and a % st["back_spikes"] == 1 and lo - 2.01 <= b < lo:
            rows.setdefault(a, []).append(b)
        elif st.get("teeth") and a0 + 2 <= a <= a1 - 3 and a % st["teeth"] == 0 and hi < b <= hi + 1.01:
            rows.setdefault(a, []).append(b)
    paint = st.get("paint")
    for a, bs in rows.items():
        bmin, bmax = min(bs), max(bs)
        t = (a - a0) / max(1, L)
        c = st.get("curve", 0) * t * t * wscale
        for b in bs:
            if b == bmin:
                col = met[3]
            elif b == bmax:
                col = met[0]
            elif b < c - 0.2:
                col = met[2]
            elif b > c + 0.6:
                col = mix(met[1], met[0], 0.4)
            else:
                col = met[1]
            role = "solid"
            centre = abs(b - c) <= 0.6 and bmin < b < bmax
            if paint == "fuller" and centre and a0 + 1 < a < a1 - 4:
                col = shade(met[1], 0.78)
            elif paint == "vein" and centre and a0 < a < a1 - 2:
                col = acc[1] if a % 3 else acc[2]
            elif paint == "inlay" and centre and a0 + 1 < a < a1 - 4:
                col = acc[1]
            elif paint == "crystal":
                if bmin < b < bmax:
                    col = met[2] if (a + b) % 4 else met[3]
                if centre and a0 < a < a1 - 3:
                    col = M["trim"][2]
            elif paint == "ice":
                if bmin < b < bmax:
                    col = met[2] if (a // 2 + b) % 3 else acc[1]
            elif paint == "sheen" and b == bmin + 1 and a % 4 in (0, 1):
                col = acc[2]
            elif paint == "ridges" and b == bmax and a % 3 == 1:
                col = M["trim"][0]
            elif paint == "ridges" and centre and a % 4 == 0:
                col = met[0]
            elif paint == "rust" and r.random() < 0.18 and bmin < b < bmax:
                col = mix(col, hexc("6a4428"), 0.6)
            elif paint == "rough" and r.random() < 0.15 and bmin < b < bmax:
                col = shade(col, 0.85)
            elif paint == "rivets" and centre and a % 3 == 0 and a0 < a < a1 - 2:
                col = M["trim"][0]
            elif paint == "segments" and a % 4 == 0:
                col = shade(col, 0.7)
            elif paint == "carve" and centre and a0 < a < a1 - 3:
                col = acc[0] if a % 2 == 0 else met[0]
            elif paint == "hamon":
                if b == bmax - (0 if bmax - bmin < 2 else 1) and b != bmin:
                    col = met[3] if a % 3 else met[2]
            elif paint == "mirror" and bmin < b < bmax and a % 5 == 0:
                col = met[3]
            elif paint == "bone" and r.random() < 0.2 and bmin < b < bmax:
                col = M["metal"][0]
            elif paint == "runes" and centre and a0 + 1 < a < a1 - 3:
                col = acc[1] if a % 3 == 0 else shade(met[1], 0.8)
            if a == a1 and len(bs) == 1:
                col = met[3]
            cv.put(a, b, col, role, dark=shade(met[0], 0.55))


def _grip(cv, M, a0, a1, style="wrap"):
    g = M["grip"]
    for a in range(a0, a1 + 1):
        for b in (-1, 0, 1):
            if cv.xy(a, b) is None:
                continue
            if style == "katana":
                col = M["accent"][1] if (a + b) % 4 == 0 else (g[2] if b < 0 else g[1])
            else:
                col = g[2] if a % 3 == 0 else (g[1] if b <= 0 else g[0])
            cv.put(a, b, col, dark=shade(g[0], 0.5))


def _guard(cv, M, style, a, half):
    """Crossguard centred on lattice row a, spreading across (b) by `half`."""
    t = M["trim"]
    acc = M["accent"] or t
    met = M["metal"]

    def put(aa, bb, col):
        cv.put(aa, bb, col, dark=shade(t[0], 0.5))

    if style in ("cross", "long_cross", "imperial", "ornate", "nord_curl", "knot", "spiral", "lobed"):
        h = half + (1 if style == "long_cross" else 0)
        for b in range(-h, h + 1):
            for aa in (a, a + 1):
                if cv.xy(aa, b):
                    put(aa, b, t[2] if b < 0 else (t[1] if b == 0 or aa == a + 1 else t[0]))
        if style == "nord_curl":   # quillon ends curl up towards the blade
            for s in (-1, 1):
                put(a + 2, s * h, t[2]); put(a + 3, s * (h - 1), t[1])
        elif style == "knot":
            for s in (-1, 1):
                put(a + 2, s * (h + 1), acc[1]); put(a - 1, s * (h + 1), t[1]); put(a + 1, s * (h + 1), t[0])
        elif style == "spiral":
            for s in (-1, 1):
                put(a - 1, s * (h + 1), t[1]); put(a - 2, s * h, t[2])
        elif style == "lobed":
            for s in (-1, 1):
                put(a + 1, s * (h + 1), t[2]); put(a + 2, s * h, t[1])
        elif style == "ornate":
            put(a + 1, 0, acc[2]); put(a + 2, 0, acc[1])
            for s in (-1, 1):
                put(a - 1, s * h, t[2])
        elif style == "imperial":
            for s in (-1, 1):
                put(a - 1, s * (h - 1), t[1])
    elif style == "wings":      # elven: crescent wings sweeping toward the blade
        for b in range(-half - 1, half + 2):
            for aa in (a, a + 1):
                if cv.xy(aa + abs(b) // 2, b):
                    put(aa + abs(b) // 2, b, t[2] if b < 0 else t[1])
        put(a, 0, acc[2])
    elif style == "tusks":      # orcish: thick guard with tusks bending back to the grip
        for b in range(-half, half + 1):
            for aa in (a, a + 1):
                if cv.xy(aa, b):
                    put(aa, b, t[2] if b < 0 else t[1])
        for s in (-1, 1):
            put(a - 1, s * (half + 1), M["metal"][3]); put(a - 2, s * (half + 1), M["metal"][2])
    elif style == "block":      # dwarven: thick stepped block
        for b in range(-half, half + 1):
            for aa in (a - 1, a, a + 1):
                if cv.xy(aa, b) and not (aa == a - 1 and abs(b) == half):
                    put(aa, b, t[2] if b < 0 else (t[1] if abs(b) < half else t[0]))
        put(a, 0, acc[1])
    elif style == "crystal":
        for b in range(-half, half + 1):
            if cv.xy(a, b):
                put(a, b, t[2] if b < 0 else t[1])
        for s in (-1, 1):
            put(a + 1, s * half, met[3]); put(a + 2, s * (half + 1), met[2])
    elif style == "ebony":      # flat, broad, angular wings
        for b in range(-half - 1, half + 2):
            for aa in (a, a + 1):
                if cv.xy(aa, b):
                    put(aa, b, t[2] if b < -1 else (t[1] if b < 2 else t[0]))
        for s in (-1, 1):
            put(a + 2, s * (half + 1), t[1]); put(a - 1, s * (half + 2), t[2])
    elif style == "horns":      # daedric: horns curling forward along the blade
        for b in range(-half, half + 1):
            for aa in (a, a + 1):
                if cv.xy(aa, b):
                    put(aa, b, t[2] if b < 0 else t[1])
        for s in (-1, 1):
            for k in range(1, 4):
                put(a + 1 + k, s * (half + (1 if k < 3 else 0)), M["accent"][1] if k == 2 else t[2])
        put(a, 0, M["accent"][2])
    elif style == "dragon":     # dragonbone: two curved teeth
        for b in range(-half, half + 1):
            if cv.xy(a, b):
                put(a, b, M["metal"][2] if b < 0 else M["metal"][1])
        for s in (-1, 1):
            put(a + 1, s * (half + 1), M["metal"][3]); put(a + 3, s * (half + 1), M["metal"][2]); put(a + 2, s * (half + 1), M["metal"][2])
    elif style == "feathers":   # forsworn: binding plus a red feather tuft hanging off one side
        for b in (-1, 0, 1, 2):
            if cv.xy(a, b):
                put(a, b, t[1])
        for k, (da, db) in enumerate(((-1, 3), (-2, 4), (-3, 4), (-3, 5), (-4, 5))):
            cv.put(a + da, db, acc[2] if k % 2 else acc[1], dark=shade(acc[0], 0.6))
    elif style in ("chitin", "leaves", "fins"):
        col = M["trim"] if style != "leaves" else M["grip"]
        for b in range(-half, half + 1):
            if cv.xy(a, b):
                put(a, b, col[2] if b < 0 else col[1])
        for s in (-1, 1):
            put(a + 1, s * half, col[2]); put(a + 2, s * (half - 1), col[1])
            if style == "fins":
                put(a + 3, s * (half - 1), col[2])
    elif style in ("tsuba", "moon", "crescent_small"):
        r = half - (1 if style == "tsuba" else 0)
        for x, y, aa, b in cv.cells():
            d = dist(aa, b, a + 0.5, 0)
            if style == "tsuba" and d <= r * 0.85 and abs(aa - a - 0.5) <= 1.6:
                put(aa, b, t[2] if b < 0 else t[1])
            elif style == "moon" and r - 1.6 < d <= r and aa <= a + 1:
                put(aa, b, t[2] if b < 0 else t[1])
            elif style == "crescent_small" and abs(b) <= r and aa in (a, a + 1) and (aa - a) * 2 >= abs(b) - r:
                put(aa, b, t[2] if b < 0 else t[1])
        if style == "moon":
            for s in (-1, 1):
                put(a + 1, s * (r + 1), t[2]); put(a + 2, s * (r + 1), t[1])
    elif style == "dragon_wings":   # skyforge: wings swept back, gold
        for b in range(-half, half + 1):
            for aa in (a, a + 1):
                if cv.xy(aa - abs(b) // 2, b):
                    put(aa - abs(b) // 2, b, t[2] if b < 0 else t[1])
        put(a + 1, 0, M["accent"][1])
    else:
        for b in range(-half, half + 1):
            if cv.xy(a, b):
                put(a, b, t[1])


def _pommel(cv, M, style, a):
    t = M["trim"]
    acc = M["accent"] or t

    def put(aa, bb, col):
        cv.put(aa, bb, col, dark=shade(t[0], 0.5))

    if style in ("round", "disk", "ball", "knob"):
        for b in (-1, 0, 1):
            put(a, b, t[2] if b < 0 else t[1])
        put(a - 1, 0, t[1] if style != "ball" else t[2])
        if style in ("disk", "ball"):
            put(a - 1, -1 if style == "ball" else 1, t[0]); put(a + 1, -2, t[2]); put(a + 1, 2, t[0])
    elif style in ("spike", "claw"):
        put(a, 0, t[1]); put(a - 1, -1, t[2]); put(a - 1, 1, t[1]); put(a - 2, 0, t[2]); put(a - 3, -1 if style == "claw" else 1, t[2])
    elif style in ("gem", "teardrop", "diamond"):
        put(a, 0, t[1]); put(a - 1, -1, t[2]); put(a - 1, 1, t[0])
        put(a - 2, 0, acc[2] if style == "gem" else (acc[1] if style == "diamond" else t[2]))
        if style == "diamond":
            put(a - 3, -1, acc[2]); put(a - 3, 1, acc[0])
    elif style == "square":
        for aa in (a - 1, a):
            for b in (-1, 1):
                put(aa, b, t[2] if b < 0 else t[0])
        put(a - 2, 0, t[1]); put(a, 0, M["accent"][1] if M["accent"] else t[1])
    elif style == "ebony":
        put(a, -2, t[2]); put(a, 2, t[1]); put(a, 0, t[1]); put(a - 1, -1, t[2]); put(a - 1, 1, t[0])
    elif style in ("ring", "kashira", "lobed", "bird", "fin", "knot"):
        put(a, -1, t[2]); put(a, 1, t[0]); put(a - 1, 0, t[1])
        if style in ("ring", "lobed"):
            put(a - 2, -1, t[2]); put(a - 2, 1, t[1])
        if style == "bird":
            put(a - 2, -1, t[2]); put(a - 3, -2, t[1])
        if style == "fin":
            put(a - 2, 1, t[2]); put(a - 3, 2, t[1])
    else:
        put(a, 0, t[1])


def sword_like(mat, wtype, seed=0):
    M = MAT[mat]
    st = BLADES[M["blade"]]
    lay = dict(LAYOUT[wtype])
    cv = Lattice(16)
    a0, a1 = lay["blade"]
    if st.get("short") and wtype != "greatsword":
        a1 = a0 + int((a1 - a0) * st["short"])
    if st.get("long") and wtype == "sword":
        a1 = min(29, a1 + 1)
    two = wtype == "greatsword"
    gstyle = M["guard"]
    half = {"dagger": 2, "sword": 3, "greatsword": 4}[wtype]
    if M["blade"] in ("katana", "yokudan") and gstyle == "tsuba":
        half = {"dagger": 2, "sword": 3, "greatsword": 3}[wtype]
    grip0, grip1 = lay["grip"]
    if M["blade"] == "katana":
        grip0 = max(0, grip0 - 2)
    _grip(cv, M, grip0, grip1, "katana" if M["blade"] == "katana" else "wrap")
    _blade(cv, M, st, a0, a1, lay["wscale"], seed + 7, two)
    _guard(cv, M, gstyle, lay["guard"], half)
    _pommel(cv, M, M["pommel"], max(1, grip0 - 0) if wtype != "dagger" else lay["pommel"])
    return cv.image()


# --------------------------------------------------------------------------------------------- bows

BOWS = {
    # shape: d (plain arc), recurve (tips bend back), reflex (grip set back, tips forward), angular (straight limbs),
    # asym (Akaviri yumi: grip in the lower third); ornaments: blades, gears, curls, crystals, glow, teeth, feathers, knots
    "long": dict(shape="d", arc=6.0), "hunting": dict(shape="recurve", arc=6.5, recurve=0.7),
    "orcish": dict(shape="d", arc=6.0, blades=True, thick=True), "dwarven": dict(shape="angular", arc=6.0, gears=True, metal=True),
    "elven": dict(shape="reflex", arc=7.0, curls=True, metal=True), "glass": dict(shape="recurve", arc=6.5, recurve=0.9, crystals=True, metal=True),
    "ebony": dict(shape="angular", arc=6.5, blades=True, metal=True), "daedric": dict(shape="reflex", arc=7.0, blades=True, glow=True, metal=True),
    "dragonbone": dict(shape="recurve", arc=6.5, recurve=0.6, teeth=True, metal=True), "silver": dict(shape="reflex", arc=6.5, metal=True),
    "ancient": dict(shape="d", arc=6.0, knots=True), "forsworn": dict(shape="d", arc=6.0, feathers=True),
    "imperial": dict(shape="recurve", arc=6.5, recurve=1.2), "falmer": dict(shape="recurve", arc=6.5, recurve=0.9, teeth=True, metal=True, segments=True),
    "nordic": dict(shape="recurve", arc=6.5, recurve=0.6, metal=True, knots=True), "skyforge": dict(shape="recurve", arc=6.5, recurve=0.9, metal=True),
    "yumi": dict(shape="asym", arc=5.5, long=True), "stalhrim": dict(shape="angular", arc=6.5, crystals=True, metal=True),
    "chitin": dict(shape="recurve", arc=6.0, recurve=0.7, metal=True, segments=True), "redguard": dict(shape="reflex", arc=7.0),
    "yokudan": dict(shape="reflex", arc=7.5, metal=True, curls=True), "khajiit": dict(shape="reflex", arc=7.0, curls=True),
    "bosmer": dict(shape="d", arc=5.5, feathers=True, long=True), "breton": dict(shape="d", arc=6.5, knots=True),
    "argonian": dict(shape="recurve", arc=6.0, recurve=0.7, metal=True, segments=True), "maormer": dict(shape="reflex", arc=7.0, curls=True, metal=True),
}


def _bow_arc(spec, u, T0, B):
    """Lattice row of the limb at across-position u in [-1, 1]."""
    shape = spec["shape"]
    if shape == "angular":
        return T0 + min(B, B * 1.35 * (1 - abs(u)))
    if shape == "asym":
        v = (u - 0.3) / (1.3 if u < 0.3 else 0.7)
        return T0 + B * (1 - v * v)
    arc = T0 + B * (1 - u * u)
    if shape in ("recurve", "reflex"):
        arc -= spec.get("recurve", 1.2) * max(0.0, abs(u) - 0.72) * 7.0
    if shape == "reflex":
        arc -= 1.6 * math.exp(-(u / 0.22) ** 2)
    return arc



def bow(mat, pull):
    """pull: -1 idle, 0..2 drawing stages. The bow arcs from the top-left to the bottom-right corner."""
    M = MAT[mat]
    spec = BOWS.get(M["bow"], BOWS["long"])
    cv = Lattice(16)
    limb = M["metal"] if spec.get("metal") else M["wood"]
    g = M["grip"]
    acc = M["accent"] or M["trim"]
    T0, C = 12.5, 12.5 if spec.get("long") else 11.5
    draw = [0, 2.5, 4.0, 5.5][pull + 1]
    B = spec["arc"] + draw * 0.3
    string = (232, 228, 214, 255) if mat not in ("daedric",) else (200, 70, 56, 255)
    if mat in ("glass", "stalhrim", "maormer"):
        string = (210, 250, 240, 255)
    for x, y, a, b in cv.cells():
        if abs(b) > C + 0.5:
            continue
        u = b / C
        arc = _bow_arc(spec, u, T0, B) - draw * 0.2
        col = None
        thick = 1.6 if spec.get("thick") else 1.1
        if arc - 0.9 <= a <= arc + thick:
            if abs(b) <= 1:
                col = g[2] if a > arc else g[1]
            else:
                col = limb[2] if a > arc + 0.2 else (limb[1] if b < 0 else limb[0])
                if abs(b) >= C - 1.5:
                    col = M["trim"][2]
                if spec.get("glow") and abs(b) in (4, 5):
                    col = acc[1]
                if spec.get("crystals") and abs(b) % 4 == 2 and a > arc:
                    col = limb[3]
                if spec.get("curls") and abs(b) in (6, 7):
                    col = M["trim"][2]
                if spec.get("segments") and abs(b) % 3 == 0:
                    col = limb[0]
                if spec.get("knots") and abs(b) in (4, 8):
                    col = M["trim"][2]
        elif a > arc + thick and a <= arc + thick + 1.5:
            if spec.get("blades") and abs(b) in (5, 6, 8):
                col = M["metal"][3] if mat != "daedric" else acc[1]
            if spec.get("teeth") and abs(b) % 3 == 0 and 3 <= abs(b) <= 9:
                col = M["metal"][3]
            if spec.get("gears") and abs(b) in (4, 5):
                col = M["trim"][2]
            if spec.get("crystals") and abs(b) in (3, 7):
                col = limb[3]
        if col is not None:
            cv.put(a, b, col, dark=shade(limb[0], 0.45))
            continue
        sl = T0 - draw * (1 - abs(b) / C)
        if abs(a - sl) <= (0.5 if draw == 0 else 0.8) and abs(b) < C - 0.5:
            cv.put(a, b, string, role="string")
    if spec.get("feathers"):
        for k, (da, db) in enumerate(((-1, 2), (-2, 3), (-3, 3))):
            cv.put(int(T0 + B) - 1 + da, db, acc[2] if k % 2 else acc[1], role="nooutline")
    if pull >= 0:
        tail = T0 - draw
        for a in range(int(math.ceil(tail)), 29):
            for b in (0, 1):
                if cv.xy(a, b) is None:
                    continue
                if a >= 26:
                    col = (200, 200, 205, 255) if b == 0 else (130, 130, 138, 255)
                elif a <= tail + 2:
                    col = (235, 235, 235, 255) if b == 0 else (190, 60, 50, 255)
                else:
                    col = (140, 100, 60, 255) if b == 0 else (100, 70, 40, 255)
                if b == 1 and 3 <= a - tail and a < 26:
                    continue
                cv.put(a, b, col, role="arrow")
    # vanilla orientation: the belly faces the top-left (the display transforms of item/bow assume it)
    return cv.image(skip_roles=("string", "arrow", "nooutline")).transpose(0)


def weapon(mat, wtype, seed=0):
    if wtype in ("dagger", "sword", "greatsword"):
        return sword_like(mat, wtype, seed)
    M = MAT[mat]
    if wtype == "war_axe":
        return gear_heads.axe(M, M["axe"], False, seed)
    if wtype == "battleaxe":
        return gear_heads.axe(M, M["axe"], True, seed)
    if wtype == "mace":
        return gear_heads.mace(M, M["mace"], mat, seed)
    if wtype == "warhammer":
        return gear_heads.warhammer(M, M["hammer"], mat, seed)
    if wtype == "bow":
        return bow(mat, -1)
    raise ValueError(wtype)
