"""Item art of the extra alchemy ingredients (PIL): Skyrim's remaining flora, fungi, eggs and creature parts, and the
classic ingredients of Morrowind, Elsweyr and Black Marsh (comberry, marshmerrow, saltrice, kwama eggs, moon sugar...).
Every icon is drawn procedurally from a small set of shapes (flower, root, berries, mushroom, egg, jelly...) with its
own palette and seed; nothing is copied from any game. Also writes the item model JSONs. Run:
    py -3 tools/textures/ingredients.py
"""
import json
import math
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft")
ITEM_TEX = os.path.join(ASSETS, "textures", "item")
MODELS = os.path.join(ASSETS, "models", "item")

S = 16
OUTLINE = (28, 20, 16)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def ramp(pal, t):
    t = max(0.0, min(1.0, t))
    x = t * (len(pal) - 1)
    i = min(int(x), len(pal) - 2)
    f = x - i
    a, b = pal[i], pal[i + 1]
    return tuple(int(round(a[k] + (b[k] - a[k]) * f)) for k in range(3))


def P(*hexes):
    return [hexc(h) for h in hexes]


class Icon:
    def __init__(self, seed):
        self.img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        self.px = self.img.load()
        self.rnd = random.Random(seed)

    def put(self, x, y, c, a=255):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < S and 0 <= y < S:
            self.px[x, y] = (c[0], c[1], c[2], a)

    def opaque(self, x, y):
        return 0 <= x < S and 0 <= y < S and self.px[x, y][3] > 0

    def blob(self, cx, cy, rx, ry, pal, light=(-0.7, -0.7), noise=0.1):
        """A shaded ellipse lit from the top-left."""
        for y in range(S):
            for x in range(S):
                dx, dy = (x - cx) / max(0.5, rx), (y - cy) / max(0.5, ry)
                d = dx * dx + dy * dy
                if d > 1.0:
                    continue
                t = 0.55 - (dx * light[0] + dy * light[1]) * 0.35 - d * 0.25 + (self.rnd.random() - 0.5) * noise
                self.put(x, y, ramp(pal, t))

    def line(self, x0, y0, x1, y1, pal, width=1):
        n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
        for i in range(n + 1):
            f = i / max(1, n)
            x, y = x0 + (x1 - x0) * f, y0 + (y1 - y0) * f
            c = ramp(pal, 0.35 + 0.4 * (1 - f) + (self.rnd.random() - 0.5) * 0.15)
            self.put(x, y, c)
            if width > 1:
                self.put(x + 1, y, ramp(pal, 0.25))

    def speckle(self, pal, count, inside_only=True):
        for _ in range(count):
            x, y = self.rnd.randrange(S), self.rnd.randrange(S)
            if not inside_only or self.opaque(x, y):
                self.put(x, y, ramp(pal, self.rnd.random()))

    def outline(self, color=OUTLINE):
        src = self.img.copy().load()
        for y in range(S):
            for x in range(S):
                if src[x, y][3] > 0:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < S and 0 <= ny < S and src[nx, ny][3] > 0:
                        self.put(x, y, color)
                        break

    def save(self, name):
        os.makedirs(ITEM_TEX, exist_ok=True)
        self.img.save(os.path.join(ITEM_TEX, name + ".png"))


STEM = P("#2E4A1E", "#4E7A2E", "#78A848")
WOOD = P("#3A2414", "#6A4428", "#9A6C40")


# ----------------------------------------------------------------------------------------------- shapes

def flower(ic, petals, center, n=5, r=3.2, stem=STEM, leaves=True):
    ic.line(8, 15, 8, 8, stem)
    if leaves:
        ic.line(8, 12, 5, 10, stem)
        ic.line(8, 13, 11, 11, stem)
    cx, cy = 8, 6
    for i in range(n):
        a = i * 2 * math.pi / n + 0.3
        ic.blob(cx + math.cos(a) * 2.6, cy + math.sin(a) * 2.6, r * 0.62, r * 0.62, petals)
    ic.blob(cx, cy, 1.3, 1.3, center)


def bell(ic, petals, stem=STEM):
    """Drooping bell flowers on an arching stem (bleeding crown style bells / deathbell kin)."""
    ic.line(4, 15, 6, 4, stem)
    ic.line(6, 4, 11, 3, stem)
    for (x, y) in ((11, 6), (8, 8), (5, 10)):
        ic.blob(x, y, 1.8, 2.2, petals)
        ic.put(x, y + 2, ramp(petals, 0.1))


def spike(ic, petals, stem=STEM):
    """A tall spike of small blossoms."""
    ic.line(8, 15, 8, 3, stem)
    for y in range(3, 11):
        w = 1 + (y - 3) // 3
        for dx in range(-w, w + 1):
            if ic.rnd.random() < 0.8:
                ic.put(8 + dx, y, ramp(petals, ic.rnd.random() * 0.6 + 0.3))
    ic.line(8, 13, 5, 11, stem)


def root(ic, pal, hair=None, knobs=3):
    pts = [(4, 3), (6, 6), (8, 9), (10, 12), (12, 14)]
    for i in range(len(pts) - 1):
        ic.line(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], pal, width=2)
    for i in range(knobs):
        x, y = pts[1 + i]
        ic.blob(x, y, 1.6, 1.4, pal)
    hair = hair or pal
    for _ in range(6):
        x, y = pts[ic.rnd.randrange(1, len(pts))]
        ic.line(x, y, x + ic.rnd.choice((-3, 3)), y + ic.rnd.randint(-1, 2), hair)
    ic.line(4, 3, 3, 1, STEM)
    ic.line(4, 3, 6, 1, STEM)


def berries(ic, pal, n=6, stem=STEM, leaf=True):
    ic.line(8, 1, 8, 5, stem)
    if leaf:
        ic.blob(10.5, 3, 2.2, 1.2, stem)
    spots = [(6, 8), (10, 8), (8, 11), (5, 12), (11, 12), (8, 6), (8, 14)][:n]
    for x, y in spots:
        ic.blob(x, y, 1.9, 1.9, pal, noise=0.05)
        ic.put(x - 1, y - 1, ramp(pal, 1.0))


def mushroom(ic, cap, stalk, spots=None, flat=False):
    ic.blob(8, 12, 1.6, 3.4, stalk)
    if flat:
        ic.blob(8, 7, 6.5, 2.2, cap)
    else:
        ic.blob(8, 6.5, 6, 4, cap)
        for x in range(3, 14):
            if ic.opaque(x, 9):
                ic.put(x, 9, ramp(cap, 0.15))
    if spots:
        for _ in range(6):
            x, y = ic.rnd.randint(4, 12), ic.rnd.randint(4, 7)
            if ic.opaque(x, y):
                ic.put(x, y, spots)


def cluster(ic, pal, n=5, r=2.2):
    for _ in range(n):
        ic.blob(ic.rnd.uniform(4, 12), ic.rnd.uniform(5, 12), r, r * ic.rnd.uniform(0.8, 1.2), pal)


def egg(ic, pal, spots, size=1.0):
    ic.blob(8, 8.5, 4.2 * size, 5.6 * size, pal, noise=0.04)
    for _ in range(9):
        x, y = ic.rnd.randint(5, 11), ic.rnd.randint(4, 13)
        if ic.opaque(x, y):
            ic.put(x, y, spots)


def jelly(ic, pal, glow=None):
    ic.blob(8, 9.5, 6, 4.5, pal, noise=0.05)
    ic.blob(6, 7.5, 1.6, 1.0, [ramp(pal, 1.0), (255, 255, 255)])
    if glow:
        ic.put(10, 10, glow)
        ic.put(9, 11, glow)


def pearl(ic, pal, size=4.0):
    ic.blob(8, 8.5, size, size, pal, noise=0.02)
    ic.put(7 - int(size / 3), 7 - int(size / 3), (255, 255, 255))


def tusk(ic, pal):
    for i in range(12):
        f = i / 11
        x = 3 + f * 9 + math.sin(f * 2.8) * 1.5
        y = 13 - f * 10
        r = 2.0 * (1 - f) + 0.5
        ic.blob(x, y, r, r, pal, noise=0.04)


def powder(ic, pal, sack=None):
    if sack:
        ic.blob(8, 10, 5.5, 4.5, sack)
        ic.blob(8, 6, 3.5, 1.4, P("#3A2A1A", "#5A4028"))
    for y in range(6, 15):
        w = (y - 5) * 0.8
        for x in range(int(8 - w), int(8 + w) + 1):
            if sack is None or y < 8:
                ic.put(x, y, ramp(pal, ic.rnd.random() * 0.5 + 0.4 - (y - 6) * 0.03))


def heart(ic, pal):
    ic.blob(6, 7, 3.2, 3.4, pal)
    ic.blob(10, 7, 3.2, 3.4, pal)
    for y in range(8, 15):
        w = (14 - y) * 0.9
        for x in range(int(8 - w), int(8 + w) + 1):
            ic.put(x, y, ramp(pal, 0.45 - (y - 8) * 0.04 + ic.rnd.random() * 0.1))
    ic.line(7, 4, 6, 1, P("#4A1A10", "#7A3020"))


def ear(ic, pal):
    for y in range(2, 15):
        f = (y - 2) / 12
        w = 1.0 + 3.2 * math.sin(f * math.pi) * (1 - f * 0.3)
        for x in range(int(8 - w), int(8 + w * 0.6) + 1):
            ic.put(x + (2 - y // 4), y, ramp(pal, 0.35 + ic.rnd.random() * 0.2 + (x - 6) * 0.05))
    ic.line(7, 5, 6, 11, P("#4A3030", "#6A4040"))


def stick(ic, pal, char=None):
    ic.line(3, 13, 13, 3, pal, width=2)
    ic.line(7, 9, 9, 13, pal)
    ic.line(10, 6, 13, 8, pal)
    if char:
        for _ in range(7):
            f = ic.rnd.random()
            ic.put(3 + 10 * f, 13 - 10 * f, ramp(char, ic.rnd.random()))


def pod(ic, pal, seam):
    ic.blob(8, 8.5, 3.6, 6, pal)
    ic.line(8, 3, 8, 14, seam)
    ic.line(8, 2, 9, 0, STEM)


def moss(ic, pal):
    for i in range(7):
        x = 3 + i * 1.7
        ic.line(x, 2, x + ic.rnd.uniform(-1.5, 1.5), 8 + ic.rnd.randint(2, 6), pal)
    ic.blob(8, 3, 6, 1.5, pal)


def leafy(ic, pal, n=4):
    for i in range(n):
        a = -1.2 + i * 2.4 / max(1, n - 1)
        x, y = 8 + math.sin(a) * 4.5, 12 - math.cos(a) * 6
        ic.line(8, 14, x, y, pal)
        ic.blob((8 + x) / 2 + math.sin(a), (14 + y) / 2 - 1, 1.7, 3.0, pal)


def grains(ic, pal, husk):
    ic.line(8, 15, 8, 3, husk)
    for y in range(3, 12, 2):
        ic.blob(6.5, y + 0.5, 1.4, 1.0, pal)
        ic.blob(9.5, y + 1.5, 1.4, 1.0, pal)


def crystals(ic, pal):
    for (x, y, h) in ((6, 12, 7), (9, 13, 9), (11, 12, 5), (4, 13, 4)):
        for i in range(h):
            ic.put(x, y - i, ramp(pal, 0.4 + i / (h * 2.0)))
            if i < h - 1:
                ic.put(x + 1, y - i, ramp(pal, 0.25 + i / (h * 2.5)))
    ic.blob(8, 13.5, 6, 1.6, pal)


def drop(ic, pal):
    for y in range(2, 15):
        f = (y - 2) / 12
        w = 0.5 + 4.2 * math.sin(min(1, f * 1.15) * math.pi * 0.62) if f < 0.85 else 4.2 * (1 - f) * 6
        for x in range(int(8 - w), int(8 + w) + 1):
            ic.put(x, y, ramp(pal, 0.75 - f * 0.5 - (x - 8) * 0.04))
    ic.put(6, 9, (255, 250, 220))


def wraps(ic, pal):
    for i in range(5):
        y = 3 + i * 2.4
        ic.line(3, y, 13, y + 1.5, pal, width=2)
    ic.speckle(P("#E8F0FF", "#FFFFFF"), 6)


def flask(ic, glass, fill):
    ic.blob(8, 10.5, 4.2, 4, glass, noise=0.02)
    ic.blob(8, 11.5, 3.4, 2.6, fill, noise=0.05)
    for y in range(2, 7):
        ic.put(7, y, ramp(glass, 0.6))
        ic.put(8, y, ramp(glass, 0.4))
    ic.put(7, 1, (110, 80, 50))
    ic.put(8, 1, (110, 80, 50))


def antennae(ic, pal):
    ic.line(4, 14, 7, 3, pal)
    ic.line(7, 3, 9, 1, pal)
    ic.line(11, 14, 10, 3, pal)
    ic.line(10, 3, 12, 1, pal)
    for y in range(4, 14, 3):
        ic.put(5 + (14 - y) * 0.2, y, ramp(pal, 1.0))
        ic.put(11 - (14 - y) * 0.08, y, ramp(pal, 1.0))


def barnacles(ic, pal, rock):
    ic.blob(8, 10, 6.5, 4.5, rock)
    for (x, y) in ((5, 8), (9, 7), (11, 10), (7, 11)):
        ic.blob(x, y, 1.7, 1.7, pal)
        ic.put(x, y, OUTLINE)


def beak(ic, pal):
    for y in range(3, 14):
        f = (y - 3) / 10
        for x in range(4, int(4 + 9 * (1 - f) + 2)):
            ic.put(x, y, ramp(pal, 0.75 - f * 0.4 - (x - 4) * 0.03))
    ic.line(4, 8, 12, 9, P("#3A2A10", "#4A3A18"))


# ----------------------------------------------------------------------------------------------- catalogue

ICONS = {
    # Skyrim flora & fungi
    "canis_root": lambda ic: root(ic, P("#5A3A22", "#8A6440", "#C0965E")),
    "creep_cluster": lambda ic: cluster(ic, P("#3A1E3A", "#6A3A62", "#A06A90"), n=6, r=2.0),
    "elves_ear": lambda ic: leafy(ic, P("#4A3A18", "#8A7A30", "#D0C060"), n=3),
    "imp_stool": lambda ic: mushroom(ic, P("#6A4A20", "#A07A38", "#D8B060"), P("#8A7A60", "#C8B898", "#EEE4CC"), flat=True),
    "jazbay_grapes": lambda ic: berries(ic, P("#24123A", "#4A2A70", "#8A60B8"), n=7),
    "juniper_berries": lambda ic: berries(ic, P("#1A2A3A", "#3A5A7A", "#7A9AB8"), n=6, stem=P("#203820", "#3A5A30", "#5A7A40")),
    "namiras_rot": lambda ic: mushroom(ic, P("#2A3A10", "#5A6A20", "#90A040"), P("#5A5040", "#8A7A60", "#B0A080"), spots=(200, 210, 120)),
    "white_cap": lambda ic: mushroom(ic, P("#8A8A80", "#C8C8C0", "#F4F4EE"), P("#706050", "#A09078", "#CCBCA0")),
    "glowing_mushroom": lambda ic: mushroom(ic, P("#1A4A6A", "#3A9AC0", "#9AF0FF"), P("#305060", "#5A8A9A", "#8AC0C8"), spots=(220, 255, 255)),
    "bleeding_crown": lambda ic: mushroom(ic, P("#4A0A0A", "#8A1A1A", "#D04040"), P("#5A4030", "#8A6A50", "#B89A78"), spots=(240, 200, 180)),
    "hanging_moss": lambda ic: moss(ic, P("#2A3A1A", "#5A7A3A", "#9AB86A")),
    "swamp_fungal_pod": lambda ic: pod(ic, P("#3A2A40", "#6A5080", "#A890C8"), P("#2A1A30", "#3A2840")),
    "thistle_branch": lambda ic: spike(ic, P("#4A2A5A", "#8A50A0", "#C890E0")),
    "giant_lichen": lambda ic: cluster(ic, P("#3A4A3A", "#7A9A80", "#C0D8C0"), n=7, r=1.8),
    "mora_tapinella": lambda ic: mushroom(ic, P("#4A3010", "#8A6020", "#D0A040"), P("#5A4A30", "#8A7A58", "#B0A078"), flat=True),
    "poison_bloom": lambda ic: flower(ic, P("#3A1050", "#7A30A0", "#C070E0"), P("#C8D040", "#F0F080"), n=6),
    "emperor_parasol_moss": lambda ic: moss(ic, P("#4A3A10", "#8A7A28", "#C8B850")),
    "burnt_spriggan_wood": lambda ic: stick(ic, P("#1A1410", "#3A2A20", "#5A4430"), char=P("#C04010", "#F08030")),
    # eggs, beaks and sea life
    "rock_warbler_egg": lambda ic: egg(ic, P("#9A8A70", "#C8B898", "#EEE4D0"), (90, 70, 50)),
    "pine_thrush_egg": lambda ic: egg(ic, P("#5A8A9A", "#8AC0D0", "#C8F0FF"), (60, 50, 40), 0.85),
    "hawks_egg": lambda ic: egg(ic, P("#A08A70", "#D0B898", "#F4E8D8"), (120, 60, 40)),
    "hawk_beak": lambda ic: beak(ic, P("#5A4A18", "#A08A30", "#E0C860")),
    "pearl": lambda ic: pearl(ic, P("#A8A0B0", "#E0D8E8", "#FFFFFF"), 4.5),
    "small_pearl": lambda ic: pearl(ic, P("#B8A898", "#E8DCD0", "#FFFFFF"), 3.0),
    "nordic_barnacle": lambda ic: barnacles(ic, P("#7A7A70", "#B8B8A8", "#E8E8D8"), P("#2A3038", "#4A5058", "#6A7078")),
    # Solstheim and the ashlands
    "trama_root": lambda ic: root(ic, P("#2A1A2A", "#5A3A50", "#8A6A80"), hair=P("#3A2A30", "#5A4048")),
    "ashen_grass_pod": lambda ic: pod(ic, P("#4A4A40", "#8A8878", "#C0BEB0"), P("#3A3830", "#4A4840")),
    "ash_creep_cluster": lambda ic: cluster(ic, P("#2A2A2A", "#5A5450", "#9A9088"), n=6),
    "ash_hopper_jelly": lambda ic: jelly(ic, P("#5A6A20", "#9AB040", "#D8F080")),
    "boar_tusk": lambda ic: tusk(ic, P("#9A8A60", "#D0C098", "#F4EAD0")),
    "spawn_ash": lambda ic: powder(ic, P("#2A2624", "#5A5450", "#8A8480")),
    # Morrowind
    "comberry": lambda ic: berries(ic, P("#5A0A1A", "#A02A3A", "#E87080"), n=6),
    "marshmerrow": lambda ic: spike(ic, P("#4A5A2A", "#8A9A50", "#C8D888"), stem=P("#3A4A20", "#5A6A30", "#7A8A48")),
    "saltrice": lambda ic: grains(ic, P("#A89A70", "#D8CCA0", "#F8F0D8"), P("#5A5030", "#8A7A48", "#B0A068")),
    "hypha_facia": lambda ic: mushroom(ic, P("#4A2A1A", "#7A5030", "#B08A60"), P("#4A3A2A", "#6A5A40", "#8A7A58"), flat=True),
    "bittergreen_petals": lambda ic: flower(ic, P("#1A4A1A", "#3A8A3A", "#80D070"), P("#D0E040", "#F8F8A0"), n=5),
    "gold_kanet": lambda ic: flower(ic, P("#6A4A00", "#C09010", "#F8D850"), P("#6A3A00", "#A06010"), n=6),
    "scrib_jelly": lambda ic: jelly(ic, P("#2A3A6A", "#5A70B0", "#A8C0F0")),
    "kwama_egg": lambda ic: egg(ic, P("#7A7A5A", "#B8B898", "#E8E8D0"), (120, 140, 90), 1.1),
    "ash_yam": lambda ic: root(ic, P("#4A2A30", "#8A4A50", "#C88088"), knobs=3),
    # Elsweyr and Black Marsh
    "moon_sugar": lambda ic: crystals(ic, P("#C8B8D8", "#EEE8F8", "#FFFFFF")),
    "hist_sap": lambda ic: drop(ic, P("#6A3A08", "#B87018", "#F8C048")),
    # creature parts
    "briar_heart": lambda ic: heart(ic, P("#2A3A10", "#5A6A20", "#9AA850")),
    "falmer_ear": lambda ic: ear(ic, P("#7A7088", "#B0A8C0", "#E0D8F0")),
    "spider_egg": lambda ic: egg(ic, P("#8A9AA0", "#C8D8E0", "#F4FCFF"), (150, 170, 180), 0.9),
    "eye_of_sabre_cat": lambda ic: (ic.blob(8, 8, 5, 5, P("#C8B070", "#E8D890", "#FFF4C0")),
                                     ic.blob(8, 8, 1.2, 3.8, P("#100808", "#201010"))),
    "powdered_mammoth_tusk": lambda ic: powder(ic, P("#C8C0A8", "#E8E0CC", "#FFFAF0"), sack=P("#5A4028", "#8A6A48", "#B09068")),
    "wisp_wrappings": lambda ic: wraps(ic, P("#7A8AA8", "#B8C8E0", "#E8F0FF")),
    "dwarven_oil": lambda ic: flask(ic, P("#6A5A30", "#A89058", "#E0C888"), P("#3A2808", "#6A4A10", "#A07820")),
    "chaurus_hunter_antennae": lambda ic: antennae(ic, P("#3A2A10", "#7A5A20", "#C09040")),
}


def model(name):
    os.makedirs(MODELS, exist_ok=True)
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "skycraft:item/" + name}}, f, indent=2)
        f.write("\n")


EFFECT_TEX = os.path.join(ASSETS, "textures", "mob_effect")

# new alchemy effects: derived from the arcane module's own icons (tools/textures/arcane.py), tinted and marked
EFFECTS = {
    "fortify_magicka": ("restore_magicka", (70, 120, 255), "plus"),
    "fortify_stamina": ("restore_stamina", (80, 200, 80), "plus"),
    "lingering_damage_magicka": ("damage_magicka", (60, 70, 140), "drip"),
    "lingering_damage_stamina": ("damage_stamina", (110, 120, 50), "drip"),
    "cure_poison": ("cure_disease", (150, 230, 120), "plus"),
}


def effect_icons():
    for name, (base, tint, mark) in EFFECTS.items():
        src = Image.open(os.path.join(EFFECT_TEX, base + ".png")).convert("RGBA")
        px = src.load()
        w, h = src.size
        for y in range(h):
            for x in range(w):
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                lum = (r * 0.3 + g * 0.59 + b * 0.11) / 255
                px[x, y] = tuple(min(255, int(c * (0.4 + lum))) for c in tint) + (a,)
        # corner mark: a plus for fortify/cure, three drops for lingering drains
        light = (250, 250, 240, 255)
        if mark == "plus":
            for d in range(-2, 3):
                px[w - 4 + d, 3] = light
                px[w - 4, 3 + d] = light
        else:
            for i in range(3):
                px[w - 2 - i * 2, 2 + i] = light
                px[w - 2 - i * 2, 3 + i] = light
        src.save(os.path.join(EFFECT_TEX, name + ".png"))


def main():
    for i, (name, draw) in enumerate(sorted(ICONS.items())):
        ic = Icon(seed=1000 + i * 37)
        draw(ic)
        ic.outline()
        ic.save(name)
        model(name)
    effect_icons()
    print("wrote", len(ICONS), "ingredient icons and", len(EFFECTS), "effect icons")


if __name__ == "__main__":
    main()
