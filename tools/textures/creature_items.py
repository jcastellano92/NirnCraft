"""Item art of the bestiary (PIL): Dragon Priest masks (16x16 icons and their worn 64x32 armor layers) and the creature
ingredients and trophies. Also writes the matching item model JSONs (and the spawn egg models of every bestiary
creature). Run:  py -3 tools/textures/creature_items.py
"""
import json
import math
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft")
ITEM_TEX = os.path.join(ASSETS, "textures", "item")
ARMOR_TEX = os.path.join(ASSETS, "textures", "models", "armor")
MODELS = os.path.join(ASSETS, "models", "item")


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
    def __init__(self, size=16):
        self.img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.px = self.img.load()
        self.s = size

    def put(self, x, y, c, a=255):
        if 0 <= x < self.s and 0 <= y < self.s:
            self.px[x, y] = (c[0], c[1], c[2], a)

    def get(self, x, y):
        return self.px[x, y] if 0 <= x < self.s and 0 <= y < self.s else (0, 0, 0, 0)

    def outline(self, color=(24, 18, 14)):
        """Dark outline around the opaque shape (classic item look)."""
        src = self.img.copy().load()
        for y in range(self.s):
            for x in range(self.s):
                if src[x, y][3] > 0:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.s and 0 <= ny < self.s and src[nx, ny][3] > 0:
                        self.put(x, y, color)
                        break

    def shape(self, inside, pal, light=(-0.6, -0.8), seed=1, noise=0.08):
        """Fills pixels where inside(x, y) with palette shading by a light direction (top-left)."""
        rnd = random.Random(seed)
        cx = cy = (self.s - 1) / 2
        for y in range(self.s):
            for x in range(self.s):
                if not inside(x, y):
                    continue
                t = 0.55 + ((x - cx) * light[0] + (y - cy) * light[1]) / self.s * 0.9
                # darker towards the rim
                rim = sum(1 for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if not inside(x + dx, y + dy))
                t -= rim * 0.12
                t += (rnd.random() - 0.5) * noise
                self.put(x, y, ramp(pal, t))

    def save(self, name):
        os.makedirs(ITEM_TEX, exist_ok=True)
        self.img.save(os.path.join(ITEM_TEX, name + ".png"))


def ellipse(cx, cy, rx, ry):
    return lambda x, y: ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0


def union(*fs):
    return lambda x, y: any(f(x, y) for f in fs)


# ================================================================================================ ingredients

def pelt(name, pal, seed):
    ic = Icon()
    body = ellipse(7.5, 8, 5.5, 5)
    legs = lambda x, y: (abs(x - 3) <= 1 or abs(x - 12) <= 1) and (2 <= y <= 4 or 11 <= y <= 13)
    head = ellipse(7.5, 2.5, 2, 1.6)
    tail = lambda x, y: 7 <= x <= 8 and 12 <= y <= 15
    inside = union(body, legs, head, tail)
    ic.shape(inside, pal, seed=seed, noise=0.2)
    rnd = random.Random(seed)
    for _ in range(14):  # fur strands
        x, y = rnd.randint(3, 12), rnd.randint(4, 12)
        if inside(x, y):
            ic.put(x, y, ramp(pal, 0.2))
    ic.outline()
    ic.save(name)


def feather(name, pal, quill=(230, 220, 200), seed=1):
    ic = Icon()
    for i in range(12):
        x0, y0 = 3 + i * 0.75, 13 - i
        w = 2.6 * math.sin((i + 1) / 13 * math.pi)
        for dx in range(-3, 4):
            for dy in range(-3, 4):
                x, y = int(round(x0 + dx * 0.7)), int(round(y0 + dy * 0.7))
                d = abs(dx * 0.7 - dy * 0.7)
                if d <= w:
                    t = 0.35 + 0.5 * (1 - d / max(w, 0.1)) - (0.15 if (x + y) % 3 == 0 else 0)
                    ic.put(x, y, ramp(pal, t))
        ic.put(int(round(x0)), int(round(y0)), quill)
    ic.put(2, 14, quill)
    ic.put(1, 15, quill)
    ic.outline()
    ic.save(name)


def vial(name, liquid, glow=False):
    ic = Icon()
    glass = P("#5a6a72", "#9fb4bc", "#e2f0f4")
    for y in range(3, 15):
        for x in range(4, 12):
            if y < 6 and not (6 <= x <= 9):
                continue
            if y >= 6 and (x == 4 or x == 11) and y in (6, 14):
                continue
            edge = x in (4, 11) or y == 14 or (y < 6 and x in (6, 9))
            if edge:
                ic.put(x, y, ramp(glass, 0.5))
            elif y >= 8:
                t = 0.4 + (11 - x) * 0.06 - (y - 8) * 0.03
                ic.put(x, y, ramp(liquid, t))
            else:
                ic.put(x, y, ramp(glass, 0.85), 120)
    for x in range(6, 10):
        ic.put(x, 2, (110, 80, 50))
    ic.put(6, 9, (255, 255, 255))
    ic.outline()
    ic.save(name)


def pile(name, pal, sparkle=None, seed=1):
    ic = Icon()
    rnd = random.Random(seed)
    inside = lambda x, y: y >= 6 and abs(x - 7.5) <= (y - 4) * 0.9 and y <= 13
    ic.shape(inside, pal, seed=seed, noise=0.35)
    for _ in range(10):
        x, y = rnd.randint(3, 12), rnd.randint(7, 12)
        if inside(x, y):
            ic.put(x, y, sparkle or ramp(pal, 0.95))
    ic.outline()
    ic.save(name)


def horn(name, pal, seed=1):
    ic = Icon()
    pts = [(3 + i * 0.85, 13 - i * 0.55 - (i * i) * 0.045) for i in range(13)]
    for i, (x0, y0) in enumerate(pts):
        r = 2.4 * (1 - i / 14)
        for dx in range(-3, 4):
            for dy in range(-3, 4):
                if dx * dx + dy * dy <= r * r:
                    t = 0.55 - dy * 0.12 + (0.12 if i % 3 == 0 else 0)
                    ic.put(int(round(x0 + dx)), int(round(y0 + dy)), ramp(pal, t))
    ic.outline()
    ic.save(name)


def fang(name, pal, count=1, seed=1):
    ic = Icon()
    for k in range(count):
        ox = 2 + k * (12 // max(1, count))
        for y in range(2, 15):
            w = max(0, (14 - y) * 0.35)
            for x in range(int(ox), int(ox + 2 * w + 1)):
                cx = ox + w
                if abs(x - cx) <= w:
                    ic.put(x, y, ramp(pal, 0.7 - (x - cx) * 0.15 - y * 0.02))
    ic.outline()
    ic.save(name)


def scale(name, pal, seed=1):
    ic = Icon()
    inside = lambda x, y: ((x - 7.5) / 6) ** 2 + ((y - 9) / 6.5) ** 2 <= 1 and y >= 2.5 + abs(x - 7.5) * 0.7
    ic.shape(inside, pal, seed=seed, noise=0.05)
    for y in range(4, 14, 3):
        for x in range(3, 13):
            if inside(x, y):
                ic.put(x, y, ramp(pal, 0.25))
    ic.outline()
    ic.save(name)


def eggs(name, pal, seed=1):
    ic = Icon()
    for cx, cy in ((5, 10), (10, 10), (7.5, 6), (12, 5)):
        ic.shape(ellipse(cx, cy, 2.6, 3.1), pal, seed=seed, noise=0.05)
    ic.outline()
    ic.save(name)


def blob(name, pal, seed=1, alpha=230):
    ic = Icon()
    inside = lambda x, y: ((x - 7.5) / 6) ** 2 + ((y - 9.5) / 4.5) ** 2 <= 1 or ((x - 7.5) / 3) ** 2 + ((y - 5) / 3) ** 2 <= 1
    ic.shape(inside, pal, seed=seed, noise=0.1)
    for y in range(ic.s):
        for x in range(ic.s):
            c = ic.get(x, y)
            if c[3]:
                ic.put(x, y, c[:3], alpha)
    ic.put(5, 6, (255, 255, 255))
    ic.outline()
    ic.save(name)


def roll(name, pal, seed=1):
    ic = Icon()
    inside = lambda x, y: 3 <= y <= 12 and 2 <= x <= 13 and not (y in (3, 12) and x in (2, 13))
    ic.shape(inside, pal, seed=seed, noise=0.15)
    for y in range(4, 12):
        ic.put(11, y, ramp(pal, 0.2))
    for x in range(2, 14):
        ic.put(x, 7, ramp(pal, 0.3))
    ic.outline()
    ic.save(name)


def cog(name, pal, seed=1):
    ic = Icon()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            outer = 6.3 if math.cos(a * 8) > 0.2 else 5.0
            if 2.0 <= r <= outer:
                t = 0.6 - (dx + dy) * 0.04 - (0.2 if r > 5.2 else 0)
                ic.put(x, y, ramp(pal, t))
    ic.outline()
    ic.save(name)


def shard(name, pal, seed=1):
    ic = Icon()
    inside = lambda x, y: abs(x - 7.5 - (y - 8) * 0.3) <= max(0, 4.5 - abs(y - 8) * 0.55) and 1 <= y <= 15
    ic.shape(inside, pal, seed=seed, noise=0.25)
    ic.outline()
    ic.save(name)


def meat(name, pal, seed=1):
    ic = Icon()
    ic.shape(ellipse(8, 8.5, 6, 4.5), pal, seed=seed, noise=0.12)
    for x in range(4, 13):
        ic.put(x, 8, (230, 210, 190))
    ic.outline()
    ic.save(name)


def claw(name, pal, seed=1):
    ic = Icon()
    for i in range(12):
        a = i / 11 * 1.9
        x0, y0 = 4 + 8 * math.sin(a), 13 - 8 * (1 - math.cos(a)) - i * 0.3
        r = 2.2 * (1 - i / 13)
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if dx * dx + dy * dy <= r * r:
                    ic.put(int(round(x0 + dx)), int(round(y0 + dy)), ramp(pal, 0.6 - dy * 0.15))
    ic.outline()
    ic.save(name)


def wax(name, pal, seed=1):
    ic = Icon()
    inside = lambda x, y: 4 <= y <= 13 and 3 <= x <= 12 and not (y < 6 and (x < 5 or x > 10))
    ic.shape(inside, pal, seed=seed, noise=0.1)
    ic.outline()
    ic.save(name)


def ingredients():
    pelt("wolf_pelt", P("#3a332c", "#62564a", "#8a7c6a", "#b4a68e"), 3)
    pelt("guar_hide", P("#3e4232", "#5e6448", "#848a64", "#aab088"), 4)
    pelt("kagouti_hide", P("#4a3420", "#6e4e30", "#946c44", "#b88c5c"), 5)
    pelt("alit_hide", P("#6a4020", "#9a6030", "#c08044", "#dca060"), 6)
    pelt("echatere_hide", P("#2e2824", "#4a4038", "#6a5e52", "#8c7e6e"), 7)
    pelt("durzog_hide", P("#4a4a1e", "#6e6a2c", "#948c3e", "#b8ae58"), 8)
    pelt("scamp_skin", P("#5a1c0c", "#8a3016", "#b84a22", "#dc6c34"), 9)
    feather("hagraven_feathers", P("#0c0c10", "#1e1e26", "#34343e", "#4c4c58"), seed=1)
    feather("harpy_feather", P("#3a2a1c", "#5e4630", "#866444", "#ac8a62"), seed=2)
    feather("indrik_feather", P("#a0b8d8", "#c8d8ec", "#ece8d8", "#fffaf0"), (240, 220, 150), seed=3)
    feather("cliff_racer_plumes", P("#2a3a4a", "#3e5468", "#5a7488", "#8098aa"), seed=4)
    vial("frostbite_venom", P("#1e4a5a", "#3a7a8e", "#6ab0c4", "#a8e0ec"))
    vial("spriggan_sap", P("#6a3a08", "#a0600e", "#d08c20", "#f4b848"))
    vial("netch_jelly", P("#5a2a4a", "#8a4a72", "#b874a0", "#e0a8cc"))
    pile("glow_dust", P("#3a5a8a", "#6a90c8", "#a8c8f0", "#e8f4ff"), (255, 255, 255), 2)
    pile("ash_salts", P("#2a2826", "#4a4642", "#76706a", "#a8a29a"), (220, 120, 60), 3)
    blob("ectoplasm", P("#2a6a8a", "#4aa0c8", "#8ad0f0", "#d0f4ff"), 4)
    roll("netch_leather", P("#4a3020", "#6e4a30", "#946a46", "#b88c62"), 5)
    horn("kamal_horn", P("#6a7a8a", "#9aaabc", "#c8d4e0", "#eef4fa"), 1)
    horn("minotaur_horn", P("#2a2420", "#4a4036", "#76685a", "#a89884"), 2)
    fang("ice_wraith_teeth", P("#6a8aa0", "#a0c4d8", "#d4ecf8", "#ffffff"), count=2)
    fang("ogre_tooth", P("#7a6e58", "#a89c80", "#d0c4a6", "#eee6cc"), count=1)
    fang("leviathan_tooth", P("#5a5a48", "#8a8a6a", "#b8b892", "#e0e0c0"), count=1)
    fang("scorpion_stinger", P("#3a2a10", "#6a4c1c", "#a07a30", "#d0a848"), count=1)
    scale("sea_serpent_scale", P("#0e3a40", "#1e5a62", "#3a8288", "#70b4b4"), 1)
    scale("tsaesci_scale", P("#5a4010", "#8a661c", "#b88e30", "#e4c058"), 2)
    scale("wamasu_scale", P("#0e3030", "#1a4c4c", "#2e6e6e", "#58a0a0"), 3)
    eggs("chaurus_eggs", P("#4a4a14", "#7a7a22", "#a8a838", "#d4d468"), 1)
    claw("hagraven_claw", P("#1e1814", "#3a302a", "#5e5246", "#8a7c6a"), 1)
    wax("dreugh_wax", P("#8a6e30", "#b8943c", "#dcbc5c", "#f4e098"), 1)
    cog("dwemer_gear", P("#4a3410", "#7a5a1c", "#b08a34", "#e0c060"), 1)
    shard("gargoyle_shard", P("#3a3a38", "#5c5c58", "#84847e", "#aaa9a2"), 1)
    meat("nix_hound_meat", P("#5a1c1c", "#8a2e2a", "#b4463e", "#d8706a"), 1)


# ================================================================================================ masks

MASKS = {
    # id: (metal palette, design)
    "morokei": (P("#5a3e0a", "#9a7016", "#d0a02c", "#f4d468", "#fff2b8"), "round"),
    "rahgot": (P("#5a2e08", "#8e4a12", "#c4741e", "#e8a040", "#f8d08a"), "jagged"),
    "vokun": (P("#16161a", "#2c2c34", "#46464e", "#64646c", "#8a8a92"), "horned"),
    "volsung": (P("#4a2e10", "#7a5020", "#a87434", "#d09c52", "#ecc88a"), "wide"),
    "nahkriin": (P("#3a3e44", "#62686e", "#8e949a", "#bcc2c6", "#e8ecee"), "spikes"),
    "otar": (P("#140e0c", "#2a1e1a", "#44322a", "#62483a", "#86684e"), "crest"),
    "krosis": (P("#4a160c", "#7a2a18", "#a8462a", "#cc6a44", "#e89a74"), "sorrow"),
    "hevnoraak": (P("#262628", "#424246", "#606066", "#84848a", "#aaaab0"), "fangs"),
    "konahrik": (P("#6a4a08", "#b08414", "#e4b828", "#ffe070", "#fff8d0"), "ornate"),
}


def mask_face(w, h, design, pal, put, seed):
    """Paints a mask face of w x h at put(x, y, color)."""
    rnd = random.Random(seed)
    for y in range(h):
        for x in range(w):
            nx = (x + 0.5) / w - 0.5
            ny = (y + 0.5) / h
            inside = abs(nx) <= 0.5 - max(0, ny - 0.8) * 1.2
            if not inside:
                continue
            t = 0.62 - abs(nx) * 0.5 - (ny - 0.4) * 0.25 + (rnd.random() - 0.5) * 0.06
            put(x, y, ramp(pal, t))
    eye_y = int(h * 0.38)
    for side in (-1, 1):
        ex = int(w / 2 + side * w * 0.2) - (1 if side < 0 else 0)
        if design == "round":
            for dx in range(2):
                for dy in range(2):
                    put(ex + dx - (1 if side < 0 else 0), eye_y + dy, (8, 6, 4))
        else:
            for dx in range(2):
                put(ex + dx * side, eye_y, (8, 6, 4))
            put(ex, eye_y + 1, (8, 6, 4))
    # brow ridge, nose ridge
    for x in range(1, w - 1):
        put(x, eye_y - 1, ramp(pal, 0.85))
    for y in range(eye_y, int(h * 0.75)):
        put(w // 2 - 1, y, ramp(pal, 0.9))
        put(w // 2, y, ramp(pal, 0.75))
    mouth_y = int(h * 0.8)
    if design in ("wide", "fangs", "sorrow", "jagged"):
        for x in range(2, w - 2):
            put(x, mouth_y, (10, 8, 6))
        if design == "fangs":
            for x in range(2, w - 2, 2):
                put(x, mouth_y + 1, ramp(pal, 0.95))
        if design == "sorrow":
            put(2, mouth_y + 1, (10, 8, 6))
            put(w - 3, mouth_y + 1, (10, 8, 6))
    else:
        for x in range(w // 2 - 2, w // 2 + 2):
            put(x, mouth_y, (10, 8, 6))
    if design == "jagged":
        for x in range(0, w, 2):
            put(x, 0, (10, 8, 6))
    if design == "spikes":
        for x in (1, w - 2):
            for y in range(int(h * 0.75), h):
                put(x, y, ramp(pal, 0.95))
    if design in ("crest", "ornate"):
        for y in range(0, eye_y - 1):
            put(w // 2 - 1, y, ramp(pal, 0.95))
            put(w // 2, y, ramp(pal, 0.8))
    if design == "ornate":
        for x in range(1, w - 1, 2):
            put(x, int(h * 0.62), ramp(pal, 1.0))
    if design == "horned":
        for side in (0, w - 1):
            for y in range(0, eye_y):
                put(side, y, ramp(pal, 0.95))


def masks():
    os.makedirs(ARMOR_TEX, exist_ok=True)
    for i, (mid, (pal, design)) in enumerate(MASKS.items()):
        # icon
        ic = Icon()
        mask_face(12, 14, design, pal, lambda x, y, c: ic.put(x + 2, y + 1, c), i)
        ic.outline((20, 14, 8))
        ic.save("mask_" + mid)
        # worn layer: the helmet box's front face (8,8)-(16,16), its sides' front columns and a crest on top
        layer = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
        px = layer.load()

        def put(x, y, c, ox=8, oy=8):
            if 0 <= x < 8 and 0 <= y < 8:
                px[ox + x, oy + y] = (c[0], c[1], c[2], 255)

        mask_face(8, 8, design, pal, lambda x, y, c: put(x, y, c), i)
        for y in range(8):
            px[7, 8 + y] = (*ramp(pal, 0.4), 255)  # right side, front column
            px[16, 8 + y] = (*ramp(pal, 0.4), 255)  # left side, front column
        for x in range(8):
            px[8 + x, 7] = (*ramp(pal, 0.6), 255)  # top face, front row
        if design in ("crest", "ornate", "horned", "spikes"):
            for y in range(8):
                px[11, y] = (*ramp(pal, 0.8), 255)
                px[12, y] = (*ramp(pal, 0.7), 255)
        layer.save(os.path.join(ARMOR_TEX, "mask_%s_layer_1.png" % mid))
        Image.new("RGBA", (64, 32), (0, 0, 0, 0)).save(os.path.join(ARMOR_TEX, "mask_%s_layer_2.png" % mid))


# ================================================================================================ item models

EGGS = ["wolf", "chaurus", "chaurus_hunter", "riekling", "ash_spawn", "gargoyle", "wisp", "will_o_wisp", "death_hound",
        "dwarven_ballista", "dragon_priest", "dremora", "flame_atronach", "frost_atronach", "storm_atronach", "scamp", "imp",
        "cliff_racer", "guar", "kagouti", "alit", "nix_hound", "netch", "silt_strider", "minotaur", "ogre", "land_dreugh",
        "wamasu", "swamp_leviathan", "hackwing", "senche_tiger", "alfiq", "sand_serpent", "giant_scorpion", "harpy", "indrik",
        "sea_serpent", "echatere", "durzog", "frost_giant", "kamal", "tsaesci", "realm_stalker", "realm_grazer", "realm_wyrm"]

INGREDIENTS = ["chaurus_eggs", "frostbite_venom", "spriggan_sap", "hagraven_feathers", "hagraven_claw", "ice_wraith_teeth",
               "wolf_pelt", "ectoplasm", "glow_dust", "netch_jelly", "netch_leather", "guar_hide", "scamp_skin",
               "cliff_racer_plumes", "kagouti_hide", "alit_hide", "ash_salts", "harpy_feather", "scorpion_stinger",
               "sea_serpent_scale", "kamal_horn", "tsaesci_scale", "indrik_feather", "minotaur_horn", "ogre_tooth",
               "dreugh_wax", "echatere_hide", "leviathan_tooth", "wamasu_scale", "gargoyle_shard", "durzog_hide",
               "nix_hound_meat", "dwemer_gear"]


def models():
    os.makedirs(MODELS, exist_ok=True)

    def write(name, data):
        with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
            f.write("\n")

    for e in EGGS:
        write(e + "_spawn_egg", {"parent": "minecraft:item/template_spawn_egg"})
    for i in INGREDIENTS + ["mask_" + m for m in MASKS]:
        write(i, {"parent": "minecraft:item/generated", "textures": {"layer0": "nirncraft:item/" + i}})


if __name__ == "__main__":
    ingredients()
    masks()
    models()
    print("creature items written")
