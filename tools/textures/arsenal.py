#!/usr/bin/env python3
"""Generator for the NirnCraft arsenal module (Forge 1.20.1).

Writes, deterministically:
  assets/nirncraft/textures/item/*.png          arrows, bolts, crossbows (+pull/charged states), staves, artifacts
  assets/nirncraft/models/item/*.json           item models (bow & crossbow overrides)
  data/nirncraft/loot_tables/...                leveled lists and dungeon chests (contract 20)
  data/nirncraft/loot_modifiers/leveled_loot.json
  data/nirncraft/nirncraft_values/arsenal.json   gold values (economy)
  data/nirncraft/nirncraft_weights/arsenal.json  carry weights (inventory)
  data/nirncraft/tags/items/...                 arsenal_goods, artifacts, bolts, staves, arsenal/<family>
  data/minecraft/tags/items/arrows.json        Skyrim arrows count as arrows for bows
  data/nirncraft/weapon_attributes/*.json       Better Combat presets for artifacts
  data/nirncraft/recipes/arsenal/*.json         arrows, bolts, crossbows
  mod/src/main/lang/nirncraft/arsenal.json      English names, tooltips, lore, messages

    python3 tools/textures/arsenal.py [--preview out.png]

Pixel helpers (diagonal Canvas, outline, weapon shapes) are shared with tools/textures/crafting.py so artifacts
read as part of the same set as the smithing weapons.
"""
import json
import math
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crafting as cr  # noqa: E402
from crafting import Canvas, P, hexc, mix, outline, shade, with_alpha  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
ITEM_TEX = os.path.join(RES, "assets", "nirncraft", "textures", "item")
MODELS = os.path.join(RES, "assets", "nirncraft", "models", "item")
DATA = os.path.join(RES, "data", "nirncraft")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft", "arsenal.json")
PREVIEW = []


def save(img, name):
    os.makedirs(ITEM_TEX, exist_ok=True)
    img.save(os.path.join(ITEM_TEX, name + ".png"))
    PREVIEW.append((name, img))


def write_json(path, obj):
    import sys
    from pathlib import Path
    native_tools263 = str(Path(__file__).resolve().parents[1])
    if native_tools263 not in sys.path: sys.path.insert(0, native_tools263)
    from data_format_263 import prepare
    path, obj = prepare(path, obj)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def finish(cv, skip_roles=()):
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255),
            skip=(lambda x, y: cv.roles.get((x, y)) in skip_roles) if skip_roles else None)
    return img


# ============================================================================ item lists (must match ArsenalItems.java)

ARROWS = {
    # id: (name, head palette (dark, mid, light), fletching (dark, light), shaft, value, kind)
    "iron_arrow": ("Iron Arrow", P("4a4a4a", "8a8a8a", "c8c8c8"), P("b8b8b0", "f0f0e8"), "wood", 1),
    "steel_arrow": ("Steel Arrow", P("46505a", "7f8c98", "d0dbe4"), P("6a6a70", "a8a8b0"), "wood", 2),
    "orcish_arrow": ("Orcish Arrow", P("2c3326", "56634c", "93a384"), P("3a4a2a", "6b7f4a"), "dark", 3),
    "dwarven_arrow": ("Dwarven Arrow", P("6e4416", "b07a32", "f2cf86"), P("7a5420", "c8964a"), "metal", 4),
    "elven_arrow": ("Elven Arrow", P("80621a", "c89a32", "fff0a0"), P("3d6a2c", "79b852"), "gold", 5),
    "glass_arrow": ("Glass Arrow", P("2a7034", "46ad55", "b8f5c4"), P("6a7a74", "c8d6d0"), "dark", 6),
    "ebony_arrow": ("Ebony Arrow", P("120f15", "2d2734", "6a5f78"), P("1e1a22", "4a4252"), "dark", 7),
    "daedric_arrow": ("Daedric Arrow", P("1d0c0c", "3a1a1e", "e0402c"), P("5a0e0e", "b01e1a"), "dark", 8),
    "dragonbone_arrow": ("Dragonbone Arrow", P("857a66", "c8bc9f", "faf5e6"), P("5c4a3c", "a08a70"), "bone", 9),
    "fire_arrow": ("Fire Arrow", P("8a1a08", "e85a14", "ffd040"), P("a02a10", "f07a2a"), "wood", 6),
    "frost_arrow": ("Frost Arrow", P("2a6aa0", "6ab8f0", "e8fbff"), P("8ab8d8", "e8f6ff"), "wood", 6),
    "shock_arrow": ("Shock Arrow", P("4a2a9a", "8a6af0", "f0eaff"), P("6a5ab0", "c8c0ff"), "wood", 6),
}
BOLTS = {
    "steel_bolt": ("Steel Bolt", P("46505a", "7f8c98", "d0dbe4"), P("3a4048", "8d969f"), "wood", 3),
    "dwarven_bolt": ("Dwarven Bolt", P("6e4416", "b07a32", "f2cf86"), P("553411", "b8823c"), "metal", 4),
    "explosive_bolt": ("Explosive Bolt", P("2a1a14", "8a2a1a", "f06a2a"), P("3a4048", "8d969f"), "wood", 12),
}
CROSSBOWS = {
    # id: (name, wood palette, metal palette, string, value, weight)
    "imperial_crossbow": ("Crossbow", P("3d2a1a", "5e4128", "86603a"), P("3e464e", "6f7a85", "aab5bf"), "d8d2c0", 120, 14),
    "enhanced_crossbow": ("Enhanced Crossbow", P("2c1d12", "4a3220", "6a4a30"), P("4f5964", "8c99a5", "d3dde6"), "e8e2d0", 200, 15),
    "dwarven_crossbow": ("Dwarven Crossbow", P("553411", "86581f", "b8823c"), P("6e4416", "b07a32", "f2cf86"), "3a2a1a", 350, 20),
    "enhanced_dwarven_crossbow": ("Enhanced Dwarven Crossbow", P("4a2c0e", "7a4e1a", "a8722e"), P("80561c", "d4a256", "fff0b0"),
                                  "2a1e12", 550, 21),
}
STAVES = {
    # id: (name, orb palette (dark, mid, light, glow), claw palette, wood palette, value, effect text)
    "staff_of_flames": ("Staff of Flames", P("7a1206", "e0400e", "ff9a2a", "fff0a0"), P("4a3a30", "7a6450", "a88c70"), "wood", 600,
                        "Hold to spray fire at close range."),
    "staff_of_frostbite": ("Staff of Frostbite", P("1a4a80", "4aa0e8", "a8e4ff", "ffffff"), P("4a525a", "7a8690", "aab6c0"), "wood", 600,
                           "Hold to blast frost; slows and drains stamina."),
    "staff_of_sparks": ("Staff of Sparks", P("2a1a6a", "6a4ae0", "b8a8ff", "ffffff"), P("3a3448", "625a78", "8c84a4"), "wood", 600,
                        "Hold to shoot lightning; drains magicka."),
    "staff_of_fireballs": ("Staff of Fireballs", P("6a0a04", "c8280a", "ff7a1a", "ffe060"), P("2c2420", "4c4038", "6c5c50"), "dark", 1200,
                           "Hurls fireballs that explode on impact."),
    "staff_of_ice_storms": ("Staff of Ice Storms", P("1a5a7a", "5ac0e8", "c8f4ff", "ffffff"), P("5a6a78", "8aa0b0", "c0d4e0"), "pale", 1200,
                            "Unleashes a slow, freezing whirlwind."),
    "staff_of_chain_lightning": ("Staff of Chain Lightning", P("3a1a7a", "8a5af0", "d8ccff", "ffffff"), P("2a2a3a", "4a4a62", "70708c"), "dark",
                                 1200, "Lightning that leaps between foes."),
    "staff_of_calm": ("Staff of Calm", P("2a4a7a", "6a9ae0", "b8d4ff", "ffffff"), P("6a6a6a", "9a9a9a", "cacaca"), "pale", 700,
                      "Calms a creature so it stops fighting."),
    "staff_of_fury": ("Staff of Fury", P("5a0a0a", "b81a1a", "ff5a4a", "ffc0b0"), P("3a2a2a", "5a4040", "806060"), "dark", 700,
                      "Drives creatures into a frenzy against each other."),
    "staff_of_conjure_familiar": ("Staff of Conjure Familiar", P("2a0a4a", "6a2aa0", "b070f0", "f0d0ff"), P("2a2a30", "4a4a54", "70707c"),
                                  "dark", 600, "Summons a spectral wolf for a minute."),
    "staff_of_paralysis": ("Staff of Paralysis", P("4a5a0a", "9ab82a", "e0f070", "ffffd0"), P("5a4a2a", "8a7448", "b8a070"), "wood", 1500,
                           "Paralyzes the target for five seconds."),
    "staff_of_healing": ("Staff of Healing", P("8a6a10", "e0b830", "fff090", "ffffff"), P("8a7a6a", "c0b0a0", "f0e6d8"), "pale", 800,
                         "Hold to heal whoever you aim at."),
    "wabbajack": ("Wabbajack", None, None, None, 1565, "Who knows what it will do?"),
    "staff_of_magnus": ("Staff of Magnus", P("1a3a8a", "4a8af0", "b8d8ff", "ffffff"), P("8a5a10", "d29a22", "f4d050"), "dark", 1500,
                        "Hold to drain magicka (or health, if it has none)."),
}
ARTIFACTS = {
    # id: (name, base kind, value, weight, Better Combat preset or None, effect, lore)
    "dawnbreaker": ("Dawnbreaker", "sword", 740, 10, "bettercombat:sword",
                    "Burns targets; undead may explode in Meridia's light.",
                    "Meridia's blade, forged to purge the unliving from Nirn."),
    "chillrend": ("Chillrend", "sword", 1460, 15, "bettercombat:sword",
                  "Frost damage; chance to paralyze.",
                  "A glass blade so cold it hums. Stolen once from Mercer Frey."),
    "mehrunes_razor": ("Mehrunes' Razor", "dagger", 860, 3, "bettercombat:dagger",
                       "A small chance to slay the target instantly.",
                       "The Prince of Destruction's own dagger. One cut is all it takes."),
    "dragonbane": ("Dragonbane", "sword", 1000, 10, "bettercombat:katana",
                   "Shock damage; devastating against dragons.",
                   "An Akaviri blade of the old Dragonguard."),
    "volendrung": ("Volendrung", "warhammer", 1480, 26, "bettercombat:hammer",
                   "Absorbs stamina with every blow.",
                   "Malacath's hammer, wrought by the Dwemer for an Orc's grudge."),
    "ebony_blade": ("Ebony Blade", "greatsword", 2000, 10, "bettercombat:claymore",
                    "Absorbs the health of those it strikes.",
                    "Mephala's blade feeds on betrayal and blood."),
    "mace_of_molag_bal": ("Mace of Molag Bal", "mace", 1250, 18, "bettercombat:mace",
                          "Drains magicka and stamina; soul traps the fallen.",
                          "The Lord of Domination's spiked mace. Souls flee from it in vain."),
    "bloodskal_blade": ("Bloodskal Blade", "greatsword", 1000, 15, "bettercombat:claymore",
                        "Full-strength swings release a crimson energy blade.",
                        "The blade of the Bloodskal clan, sharpened on Solstheim's stone."),
    "wuuthrad": ("Wuuthrad", "battleaxe", 2000, 25, "bettercombat:double_axe",
                 "Extra damage against monsters and elves.",
                 "Ysgramor's axe, which drove the elves from Skyrim."),
    "windshear": ("Windshear", "sword", 1000, 12, "bettercombat:cutlass",
                  "Strikes often stagger the target.",
                  "A Hammerfell scimitar, forged in the Gilane style."),
    "auriels_bow": ("Auriel's Bow", "bow", 1000, 11, None,
                    "Sun damage; arrows burn the undead for double damage.",
                    "The bow of Auri-El, first of the Aedra, king of the Aldmeri."),
    "nightingale_bow": ("Nightingale Bow", "bow", 1000, 18, None,
                        "Arrows deal frost and shock damage.",
                        "Nocturnal's gift to her Nightingales."),
    "zephyr": ("Zephyr", "bow", 1000, 8, None,
               "Draws a third faster than other bows.",
               "A Dwemer bow found in the ruins of the Lost Valley."),
    "bow_of_shadows": ("Bow of Shadows", "bow", 1000, 14, None,
                       "Its wielder fades from sight while drawing.",
                       "A shadow-wrapped bow from the halls of the Ayleids."),
    "spellbreaker": ("Spellbreaker", "shield", 900, 12, None,
                     "While blocking, a ward stops spells from the front.",
                     "Peryite's Dwemer shield, lost in the ruins of Bthardamz."),
}
BOW_DISPLAY = None  # copied from the crafting module's bow model at runtime


# ============================================================================ arrows & bolts

SHAFTS = {
    "wood": P("4a3220", "7a5634", "a07a50"),
    "dark": P("1f1a16", "3a302a", "5a4c40"),
    "metal": P("553411", "86581f", "b8823c"),
    "gold": P("6a5014", "a8862a", "d8b850"),
    "bone": P("6a6050", "a89c84", "d8ceb8"),
    "pale": P("8a8070", "b8ae9c", "e0d8c8"),
}


def arrow_texture(head, fletch, shaft_key, element=None):
    cv = Canvas()
    sd, sm, sl = SHAFTS[shaft_key]
    hd, hm, hl = head
    fd, fl = fletch
    tip = 28
    for x, y, t, c in cv.each():
        col = None
        role = "solid"
        if 3 <= t <= 22 and c == 0:
            col = sl if t % 4 == 0 else sm
        elif 22 < t <= tip:
            w = (tip - t) / 2.2
            if abs(c) <= w + 0.01:
                col = hl if c < 0 or t == tip else (hm if c == 0 else hd)
        if 1 <= t <= 8 and 1 <= abs(c) <= 3 and abs(c) <= (t - 0.5) / 2:
            col = fl if c < 0 else fd
        if col is not None:
            cv.put(t, c, col, role)
    if element == "fire":
        for t, c in ((29, -1), (27, -3), (30, 0), (25, -3), (28, 2)):
            cv.put(t, c, hexc("ffd040") if (t + c) % 4 else hexc("ff7a1a"), "glow")
    elif element == "frost":
        for t, c in ((29, -1), (26, -4), (30, 2), (25, 3)):
            cv.put(t, c, hexc("e8fbff"), "glow")
    elif element == "shock":
        for t, c in ((29, -1), (27, -3), (30, 2), (26, 4), (31, 1)):
            cv.put(t, c, hexc("d8ccff") if t % 2 else hexc("ffffff"), "glow")
    return finish(cv, skip_roles=("glow",))


def bolt_texture(head, fins, shaft_key, explosive=False):
    cv = Canvas()
    sd, sm, sl = SHAFTS[shaft_key]
    hd, hm, hl = head
    fd, fl = fins
    for x, y, t, c in cv.each():
        col = None
        if 5 <= t <= 22 and c in (0, 1):
            col = sm if c == 0 else sd
            if t % 5 == 0 and c == 0:
                col = sl
        if 4 <= t <= 9 and 1 <= abs(c) <= 3 and abs(c) <= (t - 3):
            col = fl if c < 0 else fd
        if explosive:
            if 21 <= t <= 27 and math.hypot(t - 24, c) <= 2.6:
                col = hl if c < -1 else (hm if c <= 1 else hd)
                if (t, c) in ((23, -1), (24, -2)):
                    col = hexc("ffd8a0")
            if 27 < t <= 29 and c == 0:
                col = hexc("ffd040")
        else:
            if 22 < t <= 28:
                w = (28 - t) / 1.6
                if abs(c) <= w + 0.01 or (t <= 24 and abs(c) <= 2):
                    col = hl if c < 0 or t == 28 else (hm if c <= 1 else hd)
        if col is not None:
            cv.put(t, c, col)
    if explosive:
        cv.put(30, -1, hexc("ffffff"), "glow")
        cv.put(29, -2, hexc("ff7a1a"), "glow")
    return finish(cv, skip_roles=("glow",))


# ============================================================================ crossbows

def crossbow_texture(wood, metal, string_hex, state, enhanced=False, dwarven=False):
    """state: standby, pulling_0..2, arrow, firework."""
    cv = Canvas()
    wd, wm, wl = wood
    md, mm, ml = metal
    string = hexc(string_hex)
    limb_t, span = 18, 8
    pull = {"standby": 0.0, "pulling_0": 2.5, "pulling_1": 5.0, "pulling_2": 8.0, "arrow": 8.0, "firework": 8.0}[state]
    string_t0 = limb_t + 0.5
    for x, y, t, c in cv.each():
        col = None
        role = "solid"
        # stock: butt (bottom-left) to front (top-right)
        if 1 <= t <= 24 and c in (0, 1):
            col = wl if c == 0 else wd
            if t <= 7:
                col = wm if c == 0 else wd
        if 1 <= t <= 7 and c == 2 or 1 <= t <= 4 and c == 3:
            col = wd  # rifle-like butt
        if t in (9, 10) and c == 2:  # trigger
            col = md
        if 17 <= t <= 21 and c in (0, 1):
            col = mm if c == 0 else md  # metal lath housing
        if 23 <= t <= 25 and c in (-1, 0, 1):
            col = ml if c < 0 else mm  # metal nose
        # limbs (curving forward in the middle)
        curve = limb_t + 2.5 * (1 - (c / span) ** 2)
        if abs(c) <= span and curve - 0.6 <= t <= curve + 0.9 and abs(c) >= 2:
            col = ml if t > curve else mm
            if abs(c) >= span - 1:
                col = md if not dwarven else ml
            if enhanced and abs(c) in (4, 5):
                col = ml
            if dwarven and abs(c) % 3 == 0:
                col = md
        if col is None:
            sl_ = string_t0 - pull * (1 - abs(c) / span)
            if abs(t - sl_) <= 0.75 and abs(c) <= span - 1:
                col = string
                role = "string"
        if col is not None:
            cv.px[x, y] = col
            cv.roles[(x, y)] = role
    if state == "arrow":
        for t in range(10, 28):
            if t >= 25:
                col = (220, 225, 230, 255)
            else:
                col = (120, 90, 55, 255)
            cv.put(t, -1, col, "arrow")
    elif state == "firework":
        for t in range(10, 28):
            col = (200, 40, 40, 255) if t < 24 else (235, 235, 235, 255)
            if t in (16, 20):
                col = (235, 235, 235, 255)
            cv.put(t, -1, col, "arrow")
            if t >= 25:
                cv.put(t, -3, (255, 220, 120, 255), "arrow")
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255),
            skip=lambda x, y: cv.roles.get((x, y)) in ("string",))
    return img


# ============================================================================ staves

WOODS = {
    "wood": P("3d2a1a", "5e4128", "86603a"),
    "dark": P("1c1612", "2e2620", "4a3e34"),
    "pale": P("7a6a56", "a8967c", "d0c0a4"),
}


def staff_texture(orb, claw, wood_key, style=None):
    cv = Canvas()
    wd, wm, wl = WOODS[wood_key]
    od, om, ol, og = orb
    cd, cm, cl = claw
    center_t = 25.5
    for x, y, t, c in cv.each():
        col = None
        role = "solid"
        if 0 <= t <= 21 and c in (0, 1):
            col = wl if c == 0 else wd
            if t % 6 == 3:
                col = cm  # metal bands
        # claws: two prongs curling around the orb
        for s in (-1, 1):
            cc = c * s
            if 19 <= t <= 29 and 1 <= cc <= 4:
                want = 3.2 - 2.4 * ((t - 24.5) / 5.0) ** 2 + (0.8 if t < 21 else 0)
                if abs(cc - want) <= 0.7 and not (t > 28 and cc > 2):
                    col = cl if s < 0 else cd
                    if t in (20, 21) and cc <= 2:
                        col = cm
        r = math.hypot(t - center_t, c * 1.0)
        if r <= 2.2:
            light = (-c - (t - center_t) * 0.4)
            col = og if light > 1.4 else (ol if light > 0.3 else (om if light > -1.0 else od))
            role = "orb"
        if col is not None:
            cv.px[x, y] = col
            cv.roles[(x, y)] = role
    if style == "magnus":
        # a ring of gold around a brilliant orb
        for a in range(0, 360, 20):
            t = center_t + 3.6 * math.cos(math.radians(a))
            c = 3.6 * math.sin(math.radians(a))
            ti, ci = int(round(t)), int(round(c))
            if (ti + ci) % 2:
                ti += 1
            if cv.xy(ti, ci) and (cv.xy(ti, ci) not in cv.roles or cv.roles[cv.xy(ti, ci)] != "orb"):
                cv.put(ti, ci, cm if a % 40 else cl)
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255))
    if style:
        px = img.load()
        for (x, y), role in cv.roles.items():
            if role == "orb" and style in ("glow", "magnus"):
                pass
    return img


# ============================================================================ polearms (Polearms skill)

POLEARM_TIERS = ["iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric", "dragonbone"]
POLEARM_TIER_NAMES = {"iron": "Iron", "steel": "Steel", "orcish": "Orcish", "dwarven": "Dwarven", "elven": "Elven", "glass": "Glass",
                      "ebony": "Ebony", "daedric": "Daedric", "dragonbone": "Dragonbone"}
SWORD_VALUE = {"iron": 25, "steel": 45, "orcish": 75, "dwarven": 135, "elven": 235, "glass": 410, "ebony": 720, "daedric": 1250,
               "dragonbone": 1500}
# kind: (name, value mult, base weight, Better Combat preset, tooltip)
POLEARMS = {
    "spear": ("Spear", 1.1, 9, "bettercombat:spear", "Quick thrusts at long reach. Light enough to carry a shield."),
    "halberd": ("Halberd", 2.1, 18, "bettercombat:halberd", "An axe blade, a hook and a spike on a long haft. Slow, heavy, sweeping."),
    "glaive": ("Glaive", 1.9, 16, "bettercombat:glaive", "A long curved blade on a staff, for wide sweeping cuts."),
}


def spear_head(st, t0, t1):
    dark, mid, light, edge = st["blade"]
    gd, gm, gl = st["guard"]

    def f(t, c):
        if t0 - 1 <= t < t0 and abs(c) <= 1:
            return gm  # socket collar
        if t0 <= t <= t1:
            k = (t - t0) / max(1, (t1 - t0))
            hw = 1.6 * math.sin(math.pi * min(1.0, k * 1.15)) if k < 0.85 else (1 - k) * 4
            if "jagged" in st["style"] and t % 3 == 0:
                hw -= 0.6
            if abs(c) <= hw + 1e-6:
                if "glow" in st["style"] and c == 0:
                    return st["accent"][2]
                return edge if c < 0 else (light if c == 0 else mid)
        return None

    return f


def halberd_head(st):
    axe = cr.axe_head(st, 21, 6.0, 1.2, 3.6, False)
    tip = spear_head(st, 25, 30)
    gd, gm, gl = st["guard"]

    def f(t, c):
        r = tip(t, c) if t >= 24 else None
        if r is None:
            r = axe(t, c)
        if r is None and t == 21 and 1 <= c <= 3:
            r = gl if c == 3 else gm  # the hook behind the blade
        return r

    return f


def glaive_head(st):
    dark, mid, light, edge = st["blade"]
    gd, gm, gl = st["guard"]

    def f(t, c):
        if t == 18 and abs(c) <= 2:
            return gl if c < 0 else gd  # small guard
        if 19 <= t <= 30:
            k = (t - 19) / 11.0
            center = -1.6 * math.sin(k * math.pi * 0.9)
            hw = 1.3 if k < 0.8 else max(0.0, (1 - k) * 6.5)
            if abs(c - center) <= hw + 1e-6:
                if "glow" in st["style"] and abs(c - center) < 0.5:
                    return st["accent"][2]
                return edge if c < center - 0.5 else (light if c < center + 0.5 else mid)
        return None

    return f


def polearm_texture(tier, kind):
    st = dict(cr.TIER_STYLE[tier])
    st["name"] = tier
    if kind == "spear":
        cv = cr.draw_hafted(st, 25, 4, spear_head(st, 24, 30))
    elif kind == "halberd":
        cv = cr.draw_hafted(st, 26, 4, halberd_head(st))
    else:
        cv = cr.draw_hafted(st, 19, 4, glaive_head(st))
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255))
    return img


# ============================================================================ staff enchanting (Dragonborn)

def unenchanted_staff_texture():
    return staff_texture(P("3a3a46", "6a6a7a", "9a9aaa", "c8c8d8"), P("4a4038", "7a6a58", "a8947a"), "wood")


def enchanted_staff_texture():
    return staff_texture(P("3a1a6a", "7a4ae0", "c8a8ff", "ffffff"), P("6e4416", "b07a32", "f2cf86"), "dark")


def enchanter_texture(part):
    """Staff Enchanter: a Dwemer bronze pedestal with a soul-lit socket."""
    import random as _r
    rnd = _r.Random("staff_enchanter_" + part)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    bronze = hexc("8a5a22")
    for y in range(16):
        for x in range(16):
            px[x, y] = shade(bronze, 0.75 + rnd.random() * 0.3)
    if part == "top":
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                if d < 3:
                    px[x, y] = shade(hexc("60E0E8"), 0.8 + (3 - d) / 8)
                elif d < 4:
                    px[x, y] = hexc("3a2a10")
                elif abs(d - 6) < 0.5:
                    px[x, y] = shade(bronze, 1.3)
    elif part == "side":
        for x in range(16):
            px[x, 0] = shade(bronze, 1.35)
            px[x, 15] = shade(bronze, 0.55)
        for y in range(3, 13, 3):
            for x in range(2, 14):
                px[x, y] = shade(bronze, 0.6)  # plate seams
        for (x, y) in ((4, 6), (11, 6), (4, 9), (11, 9)):
            px[x, y] = hexc("f2cf86")  # rivets
    else:
        for x in range(16):
            px[x, 0] = shade(bronze, 1.25)
        for y in range(16):
            px[0, y] = shade(bronze, 0.6)
            px[15, y] = shade(bronze, 0.6)
    return img


def enchanter_models():
    tex = os.path.join(RES, "assets", "nirncraft", "textures", "block")
    os.makedirs(tex, exist_ok=True)
    for part in ("top", "side", "base"):
        enchanter_texture(part).save(os.path.join(tex, f"staff_enchanter_{part}.png"))
    face = lambda t, uv=None: ({"texture": t, "uv": uv} if uv else {"texture": t})  # noqa: E731
    model = {
        "parent": "minecraft:block/block",
        "textures": {"particle": "nirncraft:block/staff_enchanter_side", "top": "nirncraft:block/staff_enchanter_top",
                     "side": "nirncraft:block/staff_enchanter_side", "base": "nirncraft:block/staff_enchanter_base"},
        "elements": [
            {"from": [2, 0, 2], "to": [14, 4, 14], "faces": {d: face("#base") for d in ("down", "up", "north", "south", "west", "east")}},
            {"from": [5, 4, 5], "to": [11, 12, 11], "faces": {d: face("#side") for d in ("north", "south", "west", "east")}},
            {"from": [3, 12, 3], "to": [13, 15, 13], "faces": {"down": face("#base"), "up": face("#top"),
                                                                **{d: face("#side", [0, 0, 16, 3]) for d in ("north", "south", "west", "east")}}},
        ],
    }
    blockmodels = os.path.join(RES, "assets", "nirncraft", "models", "block")
    write_json(os.path.join(blockmodels, "staff_enchanter.json"), model)
    write_json(os.path.join(MODELS, "staff_enchanter.json"), {"parent": "nirncraft:block/staff_enchanter"})
    states = {"variants": {f"facing={f}": ({"model": "nirncraft:block/staff_enchanter", "y": r} if r else {"model": "nirncraft:block/staff_enchanter"})
                           for f, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}}
    write_json(os.path.join(RES, "assets", "nirncraft", "blockstates", "staff_enchanter.json"), states)
    write_json(os.path.join(DATA, "loot_table", "blocks", "staff_enchanter.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "nirncraft:staff_enchanter"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
    })


def wabbajack_texture():
    """Sheogorath's staff: a twisted shaft crowned with three grinning faces (gold, purple, red)."""
    cv = Canvas()
    wd, wm, wl = P("4a2a5a", "7a4a8a", "a87ab8")
    faces = [(23, -3, P("8a5a10", "d29a22", "fff0a0")), (27, 0, P("4a1a6a", "8a3ab8", "e0a0ff")), (23, 3, P("6a0a0a", "c82a2a", "ff9a8a"))]
    for x, y, t, c in cv.each():
        col = None
        if 0 <= t <= 22 and c in (0, 1):
            twist = (t // 2) % 3
            col = (wl, wm, wd)[twist] if c == 0 else wd
        for (ft, fc, (fd, fm, fl)) in faces:
            r = math.hypot(t - ft, c - fc)
            if r <= 2.3:
                col = fl if (c - fc) < -0.5 else (fm if r < 1.6 else fd)
                if (t - ft, c - fc) in ((1, -1), (1, 1)):
                    col = hexc("1a1a1a")  # eyes
                if (t - ft) == -1 and abs(c - fc) <= 1:
                    col = hexc("f8f8f0")  # grin
        if col is not None:
            cv.put(t, c, col)
    return finish(cv)


# ============================================================================ artifacts

def style(blade, guard, grip, accent=None, wood=None, flags=()):
    return dict(blade=blade, guard=guard, grip=grip, accent=accent, wood=wood or guard, style=set(flags), name="artifact")


def sword_like(st, kind):
    if kind == "dagger":
        return cr.draw_blade_weapon(st, pommel=(3, 4), grip=(5, 8), guard=(9, 10, 3), blade=(11, 23), width=1, tip_len=2)
    if kind == "sword":
        return cr.draw_blade_weapon(st, pommel=(1, 2), grip=(3, 7), guard=(8, 9, 4), blade=(10, 28), width=1, tip_len=2)
    return cr.draw_blade_weapon(st, pommel=(0, 1), grip=(2, 7), guard=(8, 9, 5), blade=(10, 29), width=2, tip_len=1.5)


def dawnbreaker():
    st = style(P("8a8a80", "c8c8c0", "eeeee6", "ffffff"), P("8a5a10", "d29a22", "f4d050"), P("5a2a10", "8a4420", "b0602c"),
               accent=P("c87a10", "f4b030", "fff0a0"), flags=("fuller_accent",))
    cv = sword_like(st, "sword")
    # Meridia's sunburst on the guard
    sun_t = 9
    for t in range(4, 15):
        for c in range(-6, 7):
            r = math.hypot(t - sun_t, c)
            if r <= 1.6:
                cv.put(t, c, hexc("fff6c0") if r < 0.9 else hexc("ffb020"))
            elif r <= 3.2 and (t + c) % 2 == 0 and (abs(t - sun_t) == 0 or abs(c) == 0 or abs(abs(t - sun_t) - abs(c)) <= 0):
                cv.put(t, c, hexc("f4d050"))
    for t, c in ((30, -1), (29, 2)):
        cv.put(t, c, hexc("fff6c0"), "glow")
    return finish(cv, skip_roles=("glow",))


def chillrend():
    st = style(P("3a7aa8", "6ab8e8", "b8ecff", "ffffff"), P("2a3038", "4a5560", "7a8894"), P("1e2a38", "2e4256", "44607a"),
               accent=P("4aa0e8", "8ad4ff", "e8fbff"), flags=("glass", "fuller_accent"))
    st["blade"] = [with_alpha(col, 230) for col in st["blade"]]
    cv = sword_like(st, "sword")
    for t, c in ((14, -2), (19, 2), (24, -2), (27, 1), (12, 3)):
        cv.put(t, c, hexc("e8fbff"), "glow")
    return finish(cv, skip_roles=("glow",))


def mehrunes_razor():
    st = style(P("0e0808", "2a1416", "4a2228", "8a3a44"), P("3a0a0a", "7a1414", "b82a1a"), P("1a1010", "2e1a1a", "442424"),
               accent=P("7a0b0b", "c81f1a", "ff5a3c"), flags=("serrate", "fuller_accent", "glow"))
    cv = sword_like(st, "dagger")
    # the razor's hooked tip
    for t, c in ((22, -2), (23, -3), (21, -2)):
        cv.put(t, c, hexc("4a2228"))
    cv.put(24, -3, hexc("ff5a3c"), "glow")
    return finish(cv, skip_roles=("glow",))


def dragonbane():
    st = style(P("5a6470", "9aa6b2", "d8e2ec", "ffffff"), P("8a5a10", "d29a22", "f4d050"), P("10162a", "1e2a4a", "34507a"),
               accent=P("2a5ab0", "4a8af0", "a8d0ff"), flags=("fuller_accent",))
    cv = cr.draw_blade_weapon(st, pommel=(1, 2), grip=(3, 8), guard=(9, 9, 2), blade=(10, 29), width=1, tip_len=2)
    # wrapped grip diamonds and a square tsuba
    for t in range(3, 9):
        if t % 2 == 0:
            cv.put(t, 0, hexc("d8b850"))
    for c in (-2, 2):
        cv.put(9, c, hexc("f4d050"))
    cv.put(30, -2, hexc("a8d0ff"), "glow")
    return finish(cv, skip_roles=("glow",))


def volendrung():
    st = style(P("1a1418", "3a2e34", "5a4a52", "8a7a82"), P("8a5a10", "d29a22", "f4d050"), P("2a1c10", "44301c", "604428"),
               accent=P("8a5a10", "d29a22", "f4d050"), wood=P("2a2026", "4a3a42", "6a5860"), flags=())
    cv = cr.draw_hafted(st, 21, 8, cr.hammer_head(st, 19, 27, 7))
    # golden bands across the head
    for c in range(-6, 7):
        if cv.xy(21, c) in cv.roles:
            cv.put(21, c, hexc("f4d050") if c < 0 else hexc("b07a18"))
        if cv.xy(25, c) in cv.roles:
            cv.put(25, c, hexc("f4d050") if c < 0 else hexc("b07a18"))
    return finish(cv)


def ebony_blade():
    st = style(P("0a080c", "1a1620", "2e2838", "5a4a6a"), P("120e14", "241e2a", "3a3244"), P("200a10", "3a1420", "5a2030"),
               accent=P("5a0a3a", "a01a6a", "e05aa8"), flags=("fuller_accent", "glow", "curved"))
    cv = sword_like(st, "greatsword")
    for t, c in ((31, -1), (16, 3), (24, -3)):
        cv.put(t, c, hexc("e05aa8"), "glow")
    return finish(cv, skip_roles=("glow",))


def mace_of_molag_bal():
    st = style(P("161214", "2e282a", "4a4044", "6e6064"), P("1a1414", "322828", "4a3c3c"), P("1c1010", "321a1a", "472525"),
               accent=P("8a1a04", "e05a14", "ffb040"), wood=P("1a1414", "2e2424", "443636"), flags=("spikes",))
    cv = cr.draw_hafted(st, 21, 8, cr.mace_head(st, 24, 3.4))
    # molten core and extra spikes
    for t, c in ((24, 0), (23, -1), (25, 1)):
        cv.put(t, c, hexc("ffb040") if t == 24 else hexc("e05a14"))
    for t, c in ((29, -2), (29, 2), (19, -4), (19, 4)):
        cv.put(t, c, hexc("6e6064"))
    return finish(cv)


def bloodskal_blade():
    st = style(P("3a3e44", "6a7078", "a0a8b0", "d8dee4"), P("141014", "2a2228", "443a42"), P("2a1010", "441818", "602424"),
               accent=P("6a0a10", "c01828", "ff4a5a"), flags=("fuller_accent", "glow", "serrate"))
    cv = sword_like(st, "greatsword")
    for t in range(12, 27):
        if (t + 1) % 2 == 0:
            cv.put(t, -1, hexc("c01828"))
    return finish(cv)


def windshear():
    st = style(P("6a7078", "a8b0b8", "e0e6ec", "ffffff"), P("8a5a10", "d29a22", "f4d050"), P("4a1a0a", "7a2c14", "a84420"),
               accent=None, flags=("curved",))
    cv = cr.draw_blade_weapon(st, pommel=(1, 2), grip=(3, 7), guard=(8, 9, 3), blade=(10, 27), width=1, tip_len=2)
    # scimitar sweep: widen the blade toward the tip on the back edge
    for t in range(18, 27):
        w = 1 + (t - 18) / 4.5
        for c in range(-3, 0):
            if -c <= w and cv.xy(t, c) and cv.xy(t, c) not in cv.roles:
                cv.put(t, c, hexc("e0e6ec") if c == -int(w) else hexc("a8b0b8"))
    cv.put(0, 2, hexc("e07a20"), "glow")  # tassel
    cv.put(1, 3, hexc("e07a20"), "glow")
    return finish(cv, skip_roles=("glow",))


def wuuthrad():
    st = style(P("4a5a6a", "8aa0b4", "c8dcec", "f4fbff"), P("2a3440", "4a5a6a", "7a8ca0"), P("2a1c10", "44301c", "604428"),
               accent=P("2a3440", "4a5a6a", "7a8ca0"), wood=P("3a2a1c", "5a422c", "7a5a3c"), flags=("bone",))
    cv = cr.draw_hafted(st, 27, 9, cr.axe_head(st, 19, 10.0, 1.5, 6.0, True, round_k=3.0))
    # engraved runes
    for t, c in ((19, -5), (19, 5), (17, -4), (21, 4)):
        cv.put(t, c, hexc("2a3440"))
    return finish(cv)


ARTIFACT_BOWS = {
    # id: (limb palette (dark, mid, light), tip, grip palette, string, decoration)
    "auriels_bow": (P("a87a10", "f0c030", "fff4b0"), hexc("ffffff"), P("8a6a3a", "c8a060", "f0d8a0"), hexc("fff8d0"), "sun"),
    "nightingale_bow": (P("140c1e", "2e2240", "4e3c66"), hexc("8a6ab8"), P("1a1a20", "2c2c34", "40404c"), hexc("b8a8e0"), "nightingale"),
    "zephyr": (P("6e4416", "b07a32", "f2cf86"), hexc("fff0b0"), P("3a2a1a", "5a4028", "7a5a38"), hexc("4a3a2a"), "dwarven"),
    "bow_of_shadows": (P("0a0a0c", "1c1a22", "322e3c"), hexc("4a8a5a"), P("1a141e", "2a2230", "3c3244"), hexc("6a4a8a"), "shadow"),
}


def artifact_bow(bow_id, pull):
    limb, tip, grip, string, deco = ARTIFACT_BOWS[bow_id]
    wd, wm, wl = limb
    hd, hm, hl = grip
    cv = Canvas()
    T0, C, B = 13.0, 12.0, 7.0
    P_ = [0, 2.5, 4.0, 5.5][pull + 1]
    B += P_ * 0.35
    for x, y, t, c in cv.each():
        if abs(c) > C:
            continue
        arc = T0 + B * (1 - (c / C) ** 2) - P_ * 0.25
        col = None
        if arc - 0.9 <= t <= arc + 1.1:
            if abs(c) <= 1:
                col = hl if t > arc else hm
            elif abs(c) >= C - 1.5:
                col = tip
            else:
                col = wl if t > arc + 0.2 else (wm if c < 0 else wd)
                if deco == "nightingale" and abs(c) in (5, 6) and t > arc:
                    col = hexc("8a6ab8")
                if deco == "dwarven" and abs(c) % 4 == 0:
                    col = hexc("553411")
                if deco == "shadow" and abs(c) in (3, 8):
                    col = hexc("4a8a5a")
                if deco == "sun" and abs(c) in (6, 7):
                    col = hexc("ffffff")
        if col is None:
            sl = T0 - P_ * (1 - abs(c) / C)
            if abs(t - sl) <= (0.5 if P_ == 0 else 0.8) and abs(c) < C - 0.5:
                col = string
        if col is not None:
            cv.px[x, y] = col
            cv.roles[(x, y)] = "string" if col == string else "solid"
    if deco == "sun":
        # sun disc on the grip
        for t in range(18, 24):
            for c in range(-3, 4):
                if math.hypot(t - 20.5, c) <= 1.7:
                    cv.put(t, c, hexc("fff6c0") if math.hypot(t - 20.5, c) < 1 else hexc("ffb020"))
    if deco == "nightingale":
        cv.put(T0 + B + 2, -1, hexc("b8a8e0"))  # crest above the grip
    if pull >= 0:
        tail = T0 - P_
        for t in range(int(math.ceil(tail)), 29):
            for c in (0, 1):
                p = cv.xy(t, c)
                if not p:
                    continue
                if t >= 26:
                    col = (200, 200, 205, 255) if c == 0 else (130, 130, 138, 255)
                elif t <= tail + 2:
                    col = (235, 235, 235, 255) if c == 0 else (190, 60, 50, 255)
                else:
                    col = (140, 100, 60, 255) if c == 0 else (100, 70, 40, 255)
                if c == 1 and 3 <= t - tail and t < 26:
                    continue
                cv.px[p] = col
                cv.roles[p] = "arrow"
    img = cv.img
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255),
            skip=lambda x, y: cv.roles.get((x, y)) in ("string", "arrow"))
    # vanilla orientation: the belly faces the top-left (the display transforms of item/bow assume it)
    return img.transpose(0)


def spellbreaker():
    """Peryite's Dwemer shield: bronze plate, gold rim, a glowing ward gem in a spiral boss."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    rim = P("6e4416", "d29a22", "f4d050")
    body = P("553411", "86581f", "b8823c")
    for y in range(16):
        for x in range(16):
            # heater-shield silhouette
            cx, cy = x - 7.5, y - 6.5
            inside = (abs(cx) <= 6.5 and y <= 8) or (y > 8 and abs(cx) <= 6.5 - (y - 8) * 0.95)
            if not inside or y < 1:
                continue
            edge = abs(cx) >= 5.6 - (max(0, y - 8) * 0.95 if y > 8 else 0) or y == 1
            if edge:
                col = rim[2] if cx < 0 or y == 1 else rim[1]
            else:
                ang = math.atan2(cy, cx)
                r = math.hypot(cx, cy)
                spiral = (ang + r * 0.9) % (math.pi / 1.5) < 0.5
                col = body[2] if cx + cy < -2 else (body[1] if cx + cy < 4 else body[0])
                if spiral and r > 2.2:
                    col = rim[0]
            px[x, y] = col
    for (x, y) in ((7, 6), (8, 6), (7, 7), (8, 7)):
        px[x, y] = hexc("8ad4ff")
    px[7, 6] = hexc("e8fbff")
    for (x, y) in ((6, 5), (9, 5), (6, 8), (9, 8)):
        px[x, y] = hexc("f4d050")
    outline(img, color_of=lambda n: with_alpha(shade(n, 0.3), 255))
    return img


ARTIFACT_DRAW = {
    "dawnbreaker": dawnbreaker, "chillrend": chillrend, "mehrunes_razor": mehrunes_razor, "dragonbane": dragonbane,
    "volendrung": volendrung, "ebony_blade": ebony_blade, "mace_of_molag_bal": mace_of_molag_bal,
    "bloodskal_blade": bloodskal_blade, "wuuthrad": wuuthrad, "windshear": windshear, "spellbreaker": spellbreaker,
}


# ============================================================================ models

def generated(item_id, parent="minecraft:item/generated"):
    write_json(os.path.join(MODELS, item_id + ".json"), {"parent": parent, "textures": {"layer0": f"nirncraft:item/{item_id}"}})


def bow_models(item_id):
    display = BOW_DISPLAY or {}
    base = {"parent": "minecraft:item/generated", "textures": {"layer0": f"nirncraft:item/{item_id}"}}
    if display:
        base["display"] = display
    base["overrides"] = [
        {"predicate": {"pulling": 1}, "model": f"nirncraft:item/{item_id}_pulling_0"},
        {"predicate": {"pulling": 1, "pull": 0.65}, "model": f"nirncraft:item/{item_id}_pulling_1"},
        {"predicate": {"pulling": 1, "pull": 0.9}, "model": f"nirncraft:item/{item_id}_pulling_2"},
    ]
    write_json(os.path.join(MODELS, item_id + ".json"), base)
    for i in range(3):
        write_json(os.path.join(MODELS, f"{item_id}_pulling_{i}.json"),
                   {"parent": f"nirncraft:item/{item_id}", "textures": {"layer0": f"nirncraft:item/{item_id}_pulling_{i}"}})


def crossbow_models(item_id):
    write_json(os.path.join(MODELS, item_id + ".json"), {
        "parent": "minecraft:item/crossbow",
        "textures": {"layer0": f"nirncraft:item/{item_id}_standby"},
        "overrides": [
            {"predicate": {"pulling": 1}, "model": f"nirncraft:item/{item_id}_pulling_0"},
            {"predicate": {"pulling": 1, "pull": 0.58}, "model": f"nirncraft:item/{item_id}_pulling_1"},
            {"predicate": {"pulling": 1, "pull": 1.0}, "model": f"nirncraft:item/{item_id}_pulling_2"},
            {"predicate": {"charged": 1}, "model": f"nirncraft:item/{item_id}_arrow"},
            {"predicate": {"charged": 1, "firework": 1}, "model": f"nirncraft:item/{item_id}_firework"},
        ],
    })
    for state in ("pulling_0", "pulling_1", "pulling_2", "arrow", "firework"):
        write_json(os.path.join(MODELS, f"{item_id}_{state}.json"),
                   {"parent": "minecraft:item/crossbow", "textures": {"layer0": f"nirncraft:item/{item_id}_{state}"}})


def shield_model(item_id):
    write_json(os.path.join(MODELS, item_id + ".json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"nirncraft:item/{item_id}"},
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 0], "translation": [3, 1, 1], "scale": [1.1, 1.1, 1.1]},
            "thirdperson_lefthand": {"rotation": [0, 90, 0], "translation": [3, 1, 1], "scale": [1.1, 1.1, 1.1]},
            "firstperson_righthand": {"rotation": [0, -90, 15], "translation": [-2, 2, 2], "scale": [1.0, 1.0, 1.0]},
            "firstperson_lefthand": {"rotation": [0, 90, -15], "translation": [-2, 2, 2], "scale": [1.0, 1.0, 1.0]},
        },
    })


# ============================================================================ data

GEMS = ["nirncraft:garnet", "nirncraft:ruby", "nirncraft:sapphire", "minecraft:amethyst_shard", "minecraft:emerald"]
FLAWLESS = ["nirncraft:flawless_garnet", "nirncraft:flawless_ruby", "nirncraft:flawless_sapphire", "nirncraft:flawless_diamond",
            "minecraft:diamond"]


def leveled(kind, weight=None, enchant=0.0, quality=0.0, count=None):
    fn = {"function": "nirncraft:leveled_gear", "kind": kind}
    if enchant:
        fn["enchant_chance"] = enchant
    if quality:
        fn["quality_chance"] = quality
    base = {"weapon": "minecraft:iron_sword", "armor": "minecraft:iron_chestplate", "bow": "minecraft:bow", "arrow": "minecraft:arrow"}[kind]
    funcs = []
    if count:
        funcs.append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": count[0], "max": count[1]}})
    funcs.append(fn)
    e = {"type": "minecraft:item", "name": base, "functions": funcs}
    if weight:
        e["weight"] = weight
    return e


def item(name, weight=1, count=None, nbt=None):
    e = {"type": "minecraft:item", "name": name, "weight": weight}
    funcs = []
    if count:
        funcs.append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": count[0], "max": count[1]}})
    if nbt:
        funcs.append({"function": "minecraft:set_nbt", "tag": nbt})
    if funcs:
        e["functions"] = funcs
    return e


def table_ref(name, weight=1):
    return {"type": "minecraft:loot_table", "name": name, "weight": weight}


def empty(weight):
    return {"type": "minecraft:empty", "weight": weight}


def pool(rolls, entries):
    if isinstance(rolls, tuple):
        rolls = {"type": "minecraft:uniform", "min": rolls[0], "max": rolls[1]}
    return {"rolls": rolls, "entries": entries}


def loot_table(path, ltype, pools):
    write_json(os.path.join(DATA, "loot_table", path + ".json"),
               {"type": ltype, "pools": pools, "random_sequence": f"nirncraft:{path}"})


def loot_tables():
    loot_table("leveled/weapon", "minecraft:generic", [pool(1, [leveled("weapon", enchant=0.1, quality=0.15)])])
    loot_table("leveled/armor", "minecraft:generic", [pool(1, [leveled("armor", enchant=0.1, quality=0.15)])])
    loot_table("leveled/bow", "minecraft:generic", [pool(1, [leveled("bow", enchant=0.1, quality=0.15)])])
    loot_table("leveled/arrows", "minecraft:generic", [pool(1, [leveled("arrow", count=(4, 12))])])
    loot_table("leveled/dungeon_extra", "minecraft:generic", [pool(1, [
        table_ref("nirncraft:leveled/weapon", 35), table_ref("nirncraft:leveled/armor", 30),
        table_ref("nirncraft:leveled/bow", 10), table_ref("nirncraft:leveled/arrows", 25)])])
    common_staves = [f"nirncraft:{s}" for s in STAVES if s not in ("wabbajack", "staff_of_magnus")]
    loot_table("arsenal/staves", "minecraft:generic", [pool(1, [item(s) for s in common_staves])])
    loot_table("arsenal/artifacts", "minecraft:generic", [pool(1, [item(f"nirncraft:{a}") for a in ARTIFACTS]
                                                                 + [item("nirncraft:wabbajack"), item("nirncraft:staff_of_magnus")])])
    loot_table("chests/dungeon_minor", "minecraft:chest", [
        pool((1, 2), [item("nirncraft:septim", 60, count=(3, 25)), item(GEMS[0], 4), item(GEMS[1], 3), item(GEMS[2], 2),
                      item(GEMS[3], 4), table_ref("nirncraft:chests/alchemy_ingredients", 30), empty(25)]),
    ])
    loot_table("chests/dungeon_common", "minecraft:chest", [
        pool(1, [table_ref("nirncraft:chests/gold", 1)]),
        pool(1, [leveled("weapon", 30, 0.12, 0.15), leveled("armor", 25, 0.12, 0.15), leveled("bow", 8, 0.12, 0.15),
                 leveled("arrow", 20, count=(4, 12)), empty(25)]),
        pool((1, 3), [table_ref("nirncraft:chests/soul_gems", 12), table_ref("nirncraft:chests/alchemy_ingredients", 20),
                      table_ref("nirncraft:chests/lockpicks", 10)] + [item(g, 3) for g in GEMS]
             + [{"type": "minecraft:tag", "name": "nirncraft:spell_tomes", "expand": True, "weight": 1},
                item("minecraft:bread", 6, count=(1, 3)), empty(30)]),
    ])
    loot_table("chests/dungeon_boss", "minecraft:chest", [
        pool(1, [item("nirncraft:coin_purse", 40, nbt="{gold:250L}"), item("nirncraft:coin_purse", 30, nbt="{gold:400L}"),
                 item("nirncraft:coin_purse", 15, nbt="{gold:650L}"), item("nirncraft:coin_purse", 5, nbt="{gold:1000L}")]),
        pool(1, [item("nirncraft:septim", 1, count=(30, 64))]),
        pool((2, 3), [leveled("weapon", 35, 0.35, 0.4), leveled("armor", 35, 0.35, 0.4), leveled("bow", 12, 0.35, 0.4)]),
        pool(1, [leveled("arrow", 1, count=(8, 20))]),
        pool((1, 2), [item(g, 4) for g in GEMS] + [item(g, 1) for g in FLAWLESS]),
        pool(1, [table_ref("nirncraft:chests/soul_gems", 1)]),
        pool(1, [{"type": "minecraft:tag", "name": "nirncraft:spell_tomes", "expand": True, "weight": 3}, empty(7)]),
        pool(1, [table_ref("nirncraft:arsenal/staves", 6), empty(94)]),
        pool(1, [table_ref("nirncraft:arsenal/artifacts", 1), empty(199)]),
    ])
    write_json(os.path.join(DATA, "loot_modifiers", "leveled_loot.json"), {
        "type": "nirncraft:add_table", "conditions": [], "table": "nirncraft:leveled/dungeon_extra", "prefix": "chests/", "chance": 0.5})


def tags_and_values():
    arrows = list(ARROWS)
    bolts = list(BOLTS)
    crossbows = list(CROSSBOWS)
    staves = [s for s in STAVES if s not in ("wabbajack", "staff_of_magnus")]
    artifacts = list(ARTIFACTS) + ["wabbajack", "staff_of_magnus"]
    sk = lambda ids: [f"nirncraft:{i}" for i in ids]  # noqa: E731
    tag = lambda path, ids: write_json(os.path.join(DATA, "tags", "item", path + ".json"), {"replace": False, "values": ids})  # noqa: E731
    polearms = [f"{t}_{k}" for t in POLEARM_TIERS for k in POLEARMS]
    tag("arsenal_goods", sk(arrows + bolts + crossbows + staves + polearms + ["unenchanted_staff"]))
    tag("polearms", sk(polearms))
    tag("artifacts", sk(artifacts))
    tag("bolts", sk(bolts))
    tag("staves", sk(staves + ["wabbajack", "staff_of_magnus"]))
    tag("arsenal/daggers", sk(["mehrunes_razor", "bound_dagger"]))
    tag("arsenal/maces", sk(["mace_of_molag_bal"]))
    tag("arsenal/greatswords", sk(["ebony_blade", "bloodskal_blade"]))
    tag("arsenal/battleaxes", sk(["wuuthrad"]))
    tag("arsenal/warhammers", sk(["volendrung"]))
    tag("arsenal/two_handed", sk(["ebony_blade", "bloodskal_blade", "wuuthrad", "volendrung"]))
    write_json(os.path.join(RES, "data", "minecraft", "tags", "item", "arrows.json"), {"replace": False, "values": sk(arrows)})
    write_json(os.path.join(RES, "data", "forge", "tags", "item", "tools", "crossbows.json"), {"replace": False, "values": sk(crossbows)})
    write_json(os.path.join(RES, "data", "forge", "tags", "item", "tools", "shields.json"), {"replace": False, "values": sk(["spellbreaker"])})

    values, weights = {}, {}
    for i, v in ARROWS.items():
        values[f"nirncraft:{i}"] = v[4]
        weights[f"nirncraft:{i}"] = 0
    for i, v in BOLTS.items():
        values[f"nirncraft:{i}"] = v[4]
        weights[f"nirncraft:{i}"] = 0
    for i, v in CROSSBOWS.items():
        values[f"nirncraft:{i}"] = v[4]
        weights[f"nirncraft:{i}"] = v[5]
    for i, v in STAVES.items():
        values[f"nirncraft:{i}"] = v[4]
        weights[f"nirncraft:{i}"] = 1 if i == "wabbajack" else 8
    for i, v in ARTIFACTS.items():
        values[f"nirncraft:{i}"] = v[2]
        weights[f"nirncraft:{i}"] = v[3]
    for n, t in enumerate(POLEARM_TIERS):
        for k, (_, vm, w, _, _) in POLEARMS.items():
            values[f"nirncraft:{t}_{k}"] = int(round(SWORD_VALUE[t] * vm))
            weights[f"nirncraft:{t}_{k}"] = int(round(w * (1 + 0.06 * n)))
    values.update({"nirncraft:unenchanted_staff": 100, "nirncraft:enchanted_staff": 900, "nirncraft:staff_enchanter": 300})
    weights.update({"nirncraft:unenchanted_staff": 8, "nirncraft:enchanted_staff": 8, "nirncraft:staff_enchanter": 30})
    write_json(os.path.join(DATA, "nirncraft_values", "arsenal.json"), {"values": values})
    write_json(os.path.join(DATA, "nirncraft_weights", "arsenal.json"), {"weights": weights})

    for i, v in ARTIFACTS.items():
        if v[4]:
            write_json(os.path.join(DATA, "weapon_attributes", f"{i}.json"), {"parent": v[4]})


def recipes():
    out = os.path.join(DATA, "recipe", "arsenal")

    def shaped(name, pattern, key, result, count):
        write_json(os.path.join(out, name + ".json"), {
            "type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern,
            "key": {k: ({"tag": v[1:]} if v.startswith("#") else {"item": v}) for k, v in key.items()},
            "result": {"item": f"nirncraft:{result}", "count": count}})

    def shapeless(name, ingredients, result, count):
        write_json(os.path.join(out, name + ".json"), {
            "type": "minecraft:crafting_shapeless", "category": "equipment",
            "ingredients": [({"tag": v[1:]} if v.startswith("#") else {"item": v}) for v in ingredients],
            "result": {"item": f"nirncraft:{result}", "count": count}})

    heads = {"iron_arrow": "minecraft:iron_nugget", "steel_arrow": "nirncraft:steel_ingot", "orcish_arrow": "nirncraft:orichalcum_ingot",
             "dwarven_arrow": "nirncraft:dwarven_metal_ingot", "elven_arrow": "nirncraft:refined_moonstone",
             "glass_arrow": "nirncraft:refined_malachite", "ebony_arrow": "nirncraft:ebony_ingot",
             "dragonbone_arrow": "nirncraft:dragon_bone"}
    for arrow, head in heads.items():
        shaped(arrow, ["H", "S", "F"], {"H": head, "S": "minecraft:stick", "F": "minecraft:feather"}, arrow, 12 if arrow != "iron_arrow" else 6)
    shaped("daedric_arrow", ["HD", "S ", "F "], {"H": "nirncraft:ebony_ingot", "D": "nirncraft:daedra_heart", "S": "minecraft:stick",
                                                 "F": "minecraft:feather"}, "daedric_arrow", 24)
    shapeless("fire_arrow", ["nirncraft:steel_arrow"] * 4 + ["minecraft:blaze_powder"], "fire_arrow", 4)
    shapeless("frost_arrow", ["nirncraft:steel_arrow"] * 4 + ["minecraft:blue_ice"], "frost_arrow", 4)
    shapeless("shock_arrow", ["nirncraft:steel_arrow"] * 4 + ["minecraft:glowstone_dust"], "shock_arrow", 4)
    shaped("steel_bolt", ["H", "S"], {"H": "nirncraft:steel_ingot", "S": "minecraft:stick"}, "steel_bolt", 10)
    shaped("dwarven_bolt", ["H", "S"], {"H": "nirncraft:dwarven_metal_ingot", "S": "minecraft:stick"}, "dwarven_bolt", 10)
    shapeless("explosive_bolt", ["nirncraft:steel_bolt"] * 4 + ["minecraft:gunpowder", "minecraft:gunpowder"], "explosive_bolt", 4)
    shaped("imperial_crossbow", ["#I#", "@T@", " # "], {"#": "minecraft:stick", "I": "nirncraft:steel_ingot", "@": "minecraft:string",
                                                        "T": "minecraft:tripwire_hook"}, "imperial_crossbow", 1)
    shaped("enhanced_crossbow", ["I I", " C ", "I I"], {"I": "nirncraft:steel_ingot", "C": "nirncraft:imperial_crossbow"},
           "enhanced_crossbow", 1)
    shaped("dwarven_crossbow", ["#I#", "@T@", " # "], {"#": "nirncraft:dwarven_metal_ingot", "I": "nirncraft:dwarven_metal_ingot",
                                                       "@": "minecraft:string", "T": "minecraft:tripwire_hook"}, "dwarven_crossbow", 1)
    shaped("enhanced_dwarven_crossbow", ["I I", " C ", "I I"], {"I": "nirncraft:dwarven_metal_ingot", "C": "nirncraft:dwarven_crossbow"},
           "enhanced_dwarven_crossbow", 1)


# ============================================================================ lang

def lang():
    L = {}
    for i, v in {**ARROWS, **BOLTS}.items():
        L[f"item.nirncraft.{i}"] = v[0]
    for i, v in CROSSBOWS.items():
        L[f"item.nirncraft.{i}"] = v[0]
    for i, v in STAVES.items():
        L[f"item.nirncraft.{i}"] = v[0]
        L[f"tooltip.nirncraft.staff.{i}"] = v[5]
    for i, v in ARTIFACTS.items():
        L[f"item.nirncraft.{i}"] = v[0]
        L[f"tooltip.nirncraft.artifact.{i}"] = v[5]
        L[f"lore.nirncraft.artifact.{i}"] = v[6]
    for t in POLEARM_TIERS:
        for k, v in POLEARMS.items():
            L[f"item.nirncraft.{t}_{k}"] = f"{POLEARM_TIER_NAMES[t]} {v[0]}"
    for k, v in POLEARMS.items():
        L[f"tooltip.nirncraft.polearm.{k}"] = v[4]
    L.update({
        "tooltip.nirncraft.polearm.one_handed": "One-handed polearm (Polearms skill)",
        "tooltip.nirncraft.polearm.two_handed": "Two-handed polearm (Polearms skill)",
        "item.nirncraft.unenchanted_staff": "Unenchanted Staff",
        "item.nirncraft.enchanted_staff": "Enchanted Staff",
        "item.nirncraft.enchanted_staff.named": "Staff of %s",
        "tooltip.nirncraft.enchanted_staff.blank": "Bind a spell into it at a Staff Enchanter",
        "item.nirncraft.staff_enchanter": "Staff Enchanter",
        "block.nirncraft.staff_enchanter": "Staff Enchanter",
        "screen.nirncraft.staff_enchanter": "Staff Enchanter",
        "staff_enchanter.nirncraft.spells": "Spells",
        "staff_enchanter.nirncraft.no_spells": "You know no spells that can be bound into a staff.",
        "staff_enchanter.nirncraft.enchant": "Enchant",
        "staff_enchanter.nirncraft.need_staff": "Unenchanted Staff in your pack",
        "staff_enchanter.nirncraft.need_gem": "A filled soul gem in your pack",
        "staff_enchanter.nirncraft.charges": "The staff will hold %s charges",
        "staff_enchanter.nirncraft.hint": "Click a spell, then Enchant. The biggest soul you carry is used.",
        "staff_enchanter.nirncraft.made": "Staff enchanted",
        "staff_enchanter.nirncraft.problem.spell": "That spell can't be bound into a staff",
        "staff_enchanter.nirncraft.problem.unknown": "You don't know that spell",
        "staff_enchanter.nirncraft.problem.staff": "You need an Unenchanted Staff",
        "staff_enchanter.nirncraft.problem.gem": "You need a filled soul gem",
    })
    L["lore.nirncraft.artifact.wabbajack"] = "Sheogorath's staff. Even he isn't sure what it does."
    L["lore.nirncraft.artifact.staff_of_magnus"] = "The staff of the god of magic, once kept at the College of Winterhold."
    L.update({
        "entity.nirncraft.sky_arrow": "Arrow",
        "entity.nirncraft.staff_bolt": "Staff Spell",
        "entity.nirncraft.familiar": "Familiar",
        "tooltip.nirncraft.arsenal.arrow_damage": "Arrow damage: %s",
        "tooltip.nirncraft.arsenal.arrow.fire": "Sets the target on fire",
        "tooltip.nirncraft.arsenal.arrow.frost": "Frost: slows the target",
        "tooltip.nirncraft.arsenal.arrow.shock": "Shock: extra damage, drains magicka",
        "tooltip.nirncraft.arsenal.arrow.explosive": "Explodes on impact",
        "tooltip.nirncraft.arsenal.bolt": "Crossbow bolt",
        "tooltip.nirncraft.arsenal.crossbow_damage": "Bolt damage %s",
        "tooltip.nirncraft.arsenal.armor_pierce": "Ignores %s%% of armor",
        "tooltip.nirncraft.arsenal.charges": "Charges: %s / %s",
        "tooltip.nirncraft.arsenal.staff_skill": "Skill: %s",
        "tooltip.nirncraft.arsenal.recharge_hint": "Sneak + use with a filled soul gem in the other hand to recharge",
        "message.nirncraft.arsenal.headshot": "Headshot!",
        "message.nirncraft.arsenal.staff_empty": "%s is out of charges",
        "message.nirncraft.arsenal.staff_full": "%s is fully charged",
        "message.nirncraft.arsenal.staff_recharged": "%s recharged",
        "message.nirncraft.arsenal.gem_empty": "That soul gem is empty",
        "message.nirncraft.arsenal.resisted": "%s resisted the spell",
        "message.nirncraft.arsenal.mehrunes_razor": "Mehrunes' Razor claims a soul!",
        "message.nirncraft.arsenal.meridia": "Meridia's light purges the undead!",
        "message.nirncraft.arsenal.wabbajack.0": "Bawk!",
        "message.nirncraft.arsenal.wabbajack.1": "Boom!",
        "message.nirncraft.arsenal.wabbajack.2": "A gift of health. How dull.",
        "message.nirncraft.arsenal.wabbajack.3": "It's raining gold!",
        "message.nirncraft.arsenal.wabbajack.4": "Fire! Lovely fire!",
        "message.nirncraft.arsenal.wabbajack.5": "A bolt from the blue!",
        "message.nirncraft.arsenal.wabbajack.6": "Up, up and away!",
        "message.nirncraft.arsenal.wabbajack.7": "Rabbits. Obviously.",
        "message.nirncraft.arsenal.wabbajack.8": "Madness takes hold!",
    })
    os.makedirs(os.path.dirname(LANG), exist_ok=True)
    with open(LANG, "w") as f:
        json.dump(dict(sorted(L.items())), f, indent=2, ensure_ascii=False)
        f.write("\n")


# ============================================================================ main

def main():
    global BOW_DISPLAY
    bow_model = os.path.join(MODELS, "daedric_bow.json")
    if os.path.exists(bow_model):
        with open(bow_model) as f:
            BOW_DISPLAY = json.load(f).get("display")

    elements = {"fire_arrow": "fire", "frost_arrow": "frost", "shock_arrow": "shock"}
    for i, (_, head, fletch, shaft, _) in ARROWS.items():
        save(arrow_texture(head, fletch, shaft, elements.get(i)), i)
        generated(i)
    for i, (_, head, fins, shaft, _) in BOLTS.items():
        save(bolt_texture(head, fins, shaft, explosive=(i == "explosive_bolt")), i)
        generated(i)
    for i, (_, wood, metal, string, _, _) in CROSSBOWS.items():
        for state in ("standby", "pulling_0", "pulling_1", "pulling_2", "arrow", "firework"):
            save(crossbow_texture(wood, metal, string, state, enhanced="enhanced" in i, dwarven="dwarven" in i), f"{i}_{state}")
        crossbow_models(i)
    for i, (_, orb, claw, wood, _, _) in STAVES.items():
        if i == "wabbajack":
            save(wabbajack_texture(), i)
        else:
            save(staff_texture(orb, claw, wood, style="magnus" if i == "staff_of_magnus" else None), i)
        generated(i, "minecraft:item/handheld")
    for i in ARTIFACTS:
        if i in ARTIFACT_BOWS:
            save(artifact_bow(i, -1), i)
            for p in range(3):
                save(artifact_bow(i, p), f"{i}_pulling_{p}")
            bow_models(i)
        elif i == "spellbreaker":
            save(spellbreaker(), i)
            shield_model(i)
        else:
            save(ARTIFACT_DRAW[i](), i)
            generated(i, "minecraft:item/handheld")
    for t in POLEARM_TIERS:
        for k in POLEARMS:
            save(polearm_texture(t, k), f"{t}_{k}")
            generated(f"{t}_{k}", "minecraft:item/handheld")
    save(unenchanted_staff_texture(), "unenchanted_staff")
    generated("unenchanted_staff", "minecraft:item/handheld")
    save(enchanted_staff_texture(), "enchanted_staff")
    generated("enchanted_staff", "minecraft:item/handheld")
    enchanter_models()
    loot_tables()
    tags_and_values()
    recipes()
    lang()
    print(f"wrote {len(PREVIEW)} textures")
    if "--preview" in sys.argv:
        path = sys.argv[sys.argv.index("--preview") + 1]
        cols = 12
        rows = (len(PREVIEW) + cols - 1) // cols
        scale = 6
        sheet = Image.new("RGBA", (cols * 18 * scale, rows * 18 * scale), (60, 60, 70, 255))
        for k, (n, img) in enumerate(PREVIEW):
            x, y = (k % cols) * 18 * scale, (k // cols) * 18 * scale
            sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (x + scale, y + scale))
        sheet.save(path)


if __name__ == "__main__":
    main()
