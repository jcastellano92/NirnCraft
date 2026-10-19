"""Paints creature textures from the packed rig layouts (tools/textures/rigs/<name>.json, written by dump_rigs.sh).

Every box face is painted from its material: a 3-5 tone palette (dark -> light) plus a pattern (fur strands, scales,
chitin plates, bark grain, stone cracks, riveted metal, feathers, membrane veins, glowing embers...). Shading is
computed per pixel: face orientation light, a top-to-bottom gradient on side faces, darkened edges (ambient
occlusion) and the pattern's own relief, then mapped onto the palette ramp. Features (eyes, mouths, stripes, runes)
are drawn by small decorator functions keyed by box tag or bone name.

Usage (from a generator script):
    lay = load("wolf")
    img, glow = paint(lay, MATS, decor=wolf_decor, seed=7)
    save(img, "creatures/wolf.png"); save(glow, "creatures/wolf_glow.png")
"""
import json
import math
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RIGS = os.path.join(HERE, "rigs")
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures", "entity")

# ------------------------------------------------------------------------------------------------ colours


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def clamp(v, lo=0, hi=255):
    return max(lo, min(hi, int(round(v))))


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(len(a)))


def ramp(pal, t):
    """Palette ramp lookup: pal = list of rgb (dark -> light), t in [0, 1]."""
    t = max(0.0, min(1.0, t))
    if len(pal) == 1:
        return pal[0]
    x = t * (len(pal) - 1)
    i = min(int(x), len(pal) - 2)
    return lerp(pal[i], pal[i + 1], x - i)


def pal(*hexes):
    return [hexc(h) for h in hexes]


def shift(pal_, dr=0, dg=0, db=0, mul=1.0):
    return [(clamp(c[0] * mul + dr), clamp(c[1] * mul + dg), clamp(c[2] * mul + db)) for c in pal_]


# ------------------------------------------------------------------------------------------------ noise


def hash2(x, y, s):
    n = (x * 374761393 + y * 668265263 + s * 1442695040888963407) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, s, scale):
    """Smooth value noise in [0, 1]."""
    fx, fy = x / scale, y / scale
    x0, y0 = math.floor(fx), math.floor(fy)
    tx, ty = fx - x0, fy - y0
    tx = tx * tx * (3 - 2 * tx)
    ty = ty * ty * (3 - 2 * ty)
    a = hash2(x0, y0, s)
    b = hash2(x0 + 1, y0, s)
    c = hash2(x0, y0 + 1, s)
    d = hash2(x0 + 1, y0 + 1, s)
    return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty


# ------------------------------------------------------------------------------------------------ layout


def load(name):
    with open(os.path.join(RIGS, name + ".json"), encoding="utf-8") as f:
        return json.load(f)


def faces(b):
    u, v, w, h, d = b["u"], b["v"], b["w"], b["h"], b["d"]
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


FACE_LIGHT = {"top": 0.08, "front": 0.02, "right": -0.02, "left": -0.02, "back": -0.06, "bottom": -0.12}


# ------------------------------------------------------------------------------------------------ materials


class Mat:
    """A material: palette (dark -> light), pattern name and options.

    Options: base (mid tone 0..1), contrast, grad (side gradient), ao (edge darkening), alpha (0..255),
    glow (also paints into the glow layer), belly (palette used on bottom faces), dorsal (darker back stripe),
    stripes (period, width, strength), spots (density, size, strength), scale (pattern size).
    """

    def __init__(self, palette, pattern="plain", **opts):
        self.pal = palette
        self.pattern = pattern
        self.o = opts

    def get(self, k, default):
        return self.o.get(k, default)


def pattern_value(m, face, x, y, fw, fh, seed):
    """Pattern relief in roughly [-0.5, 0.5]; x, y are face-local pixel coordinates."""
    p = m.pattern
    sc = m.get("scale", 1.0)
    if p == "plain":
        return (vnoise(x, y, seed, 3 * sc) - 0.5) * 0.18
    if p == "fur":
        strand = max(2, int(3 * sc))
        col = hash2(x, (y + int(hash2(x, 0, seed) * strand)) // strand, seed)
        n = (col - 0.5) * 0.16 + (vnoise(x, y, seed + 3, 5 * sc) - 0.5) * 0.14
        if hash2(x, y, seed + 9) > 0.96:
            n -= 0.1
        return n
    if p == "hair":
        strand = max(3, int(5 * sc))
        col = hash2(x, (y + int(hash2(x, 1, seed) * strand)) // strand, seed)
        return (col - 0.5) * 0.6 + (math.sin(x * 1.7 + hash2(x, 7, seed) * 3) * 0.1)
    if p == "scales":
        s = max(2, int(3 * sc))
        row = y // s
        ox = (s // 2) if row % 2 else 0
        cx = (x + ox) % s
        cy = y % s
        v = 0.18 - (cx + cy) / (2.0 * s) * 0.36
        if cy == s - 1 or cx == s - 1:
            v -= 0.22
        return v + (hash2((x + ox) // s, row, seed) - 0.5) * 0.2
    if p == "chitin":
        band = max(2, int(4 * sc))
        cy = y % band
        v = 0.15 - cy / band * 0.3
        if cy == band - 1:
            v -= 0.3
        if cy == 0:
            v += 0.12
        gloss = 0.22 if abs(x - fw * 0.35) < max(1, fw * 0.08) else 0.0
        return v + gloss + (vnoise(x, y, seed, 3) - 0.5) * 0.12
    if p == "skin":
        n = (vnoise(x, y, seed, 5 * sc) - 0.5) * 0.35 + (vnoise(x, y, seed + 1, 2) - 0.5) * 0.12
        if hash2(x // 2, y, seed + 4) > 0.95:
            n -= 0.2
        return n
    if p == "bark":
        wob = math.sin(y * 0.45 + hash2(x // 2, 0, seed) * 6) * 1.2
        line = ((x + wob) % max(2, int(3 * sc)))
        v = -0.3 if line < 0.9 else 0.06
        if vnoise(x, y, seed + 5, 3) > 0.78:
            v -= 0.25
        return v + (hash2(x, y // 3, seed) - 0.5) * 0.15
    if p == "stone":
        s = 4 * sc
        cx, cy = x / s, y / s
        best, second = 9.0, 9.0
        for gx in range(int(cx) - 1, int(cx) + 2):
            for gy in range(int(cy) - 1, int(cy) + 2):
                px = gx + hash2(gx, gy, seed)
                py = gy + hash2(gy, gx, seed + 1)
                dd = (px - cx) ** 2 + (py - cy) ** 2
                if dd < best:
                    second, best = best, dd
                elif dd < second:
                    second = dd
        edge = math.sqrt(second) - math.sqrt(best)
        v = (hash2(int(cx), int(cy), seed) - 0.5) * 0.2
        if edge < 0.12:
            v -= 0.35
        return v + (vnoise(x, y, seed + 2, 2) - 0.5) * 0.12
    if p == "metal":
        s = max(4, int(6 * sc))
        cx, cy = x % s, y % s
        v = 0.04
        if cx == 0 or cy == 0:
            v += 0.22
        if cx == s - 1 or cy == s - 1:
            v -= 0.28
        if (cx == 1 and cy == 1) or (cx == s - 2 and cy == 1):
            v += 0.35  # rivets
        if vnoise(x, y, seed + 6, 3) > 0.8:
            v -= 0.18  # tarnish
        return v + (math.sin((x + y) * 0.9) * 0.04)
    if p == "cloth":
        weave = 0.06 if (x + y) % 2 == 0 else -0.06
        fold = math.sin(x * 0.8 + hash2(x // 3, 0, seed) * 2) * 0.12
        return weave + fold + (vnoise(x, y, seed, 4) - 0.5) * 0.15
    if p == "leather":
        return (vnoise(x, y, seed, 3) - 0.5) * 0.25 + (0.08 if hash2(x, y, seed) > 0.9 else 0) - (
            0.15 if y % 5 == 4 and hash2(x // 3, y, seed) > 0.5 else 0)
    if p == "feather":
        s = max(3, int(4 * sc))
        row = y // s
        ox = (s // 2) if row % 2 else 0
        cx = (x + ox) % s
        cy = y % s
        v = 0.18 - cy / s * 0.4
        if cx == 0:
            v -= 0.25
        if cx == s // 2 and cy < s - 1:
            v += 0.12  # quill
        return v + (hash2((x + ox) // s, row, seed) - 0.5) * 0.18
    if p == "membrane":
        v = (vnoise(x, y, seed, 6) - 0.5) * 0.15 + 0.05
        vein = abs(math.sin(x * 0.55 + vnoise(x, y, seed + 1, 5) * 3.0))
        if vein < 0.12:
            v -= 0.3
        return v
    if p == "bone":
        v = (vnoise(x, y, seed, 4) - 0.5) * 0.18
        if hash2(x, y // 2, seed) > 0.96:
            v -= 0.3
        return v + (0.08 if y % 4 == 0 else 0)
    if p == "horn":
        return (0.1 if y % 3 == 0 else -0.05) + (vnoise(x, y, seed, 3) - 0.5) * 0.12
    if p == "ember":
        return (vnoise(x, y, seed, 2.5) - 0.5) * 0.8
    if p == "ethereal":
        return (vnoise(x, y, seed, 4) - 0.5) * 0.5 + math.sin(y * 0.7 + x * 0.3) * 0.1
    if p == "crystal":
        v = ((x - y) % 5) / 5.0 * 0.4 - 0.2
        return v + (0.3 if (x + y) % 7 == 0 else 0)
    if p == "mushroom":
        return (vnoise(x, y, seed, 3) - 0.5) * 0.3 + (0.2 if hash2(x // 2, y // 2, seed) > 0.9 else 0)
    if p == "flat":
        return 0.0
    return 0.0


def paint(layout, mats, decor=None, seed=1, default="plain"):
    """Paints every (non shared) box; returns (texture, glow) images."""
    W, H = layout["width"], layout["height"]
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = img.load()
    gx = glow.load()
    for bi, b in enumerate(layout["boxes"]):
        if b["shared"]:
            continue
        m = mats.get(b["mat"]) or mats.get(default) or Mat(pal("#777777", "#999999", "#bbbbbb"))
        bseed = seed * 131 + bi * 17
        for face, (x0, y0, fw, fh) in faces(b).items():
            if fw <= 0 or fh <= 0:
                continue
            fpal = m.pal
            if face == "bottom" and m.get("belly", None):
                fpal = m.get("belly", None)
            base = m.get("base", 0.55)
            contrast = m.get("contrast", 1.0)
            grad = m.get("grad", 0.2)
            ao = m.get("ao", 0.1)
            alpha = m.get("alpha", 255)
            for yy in range(fh):
                for xx in range(fw):
                    X, Y = x0 + xx, y0 + yy
                    if X >= W or Y >= H:
                        continue
                    # world-ish coordinates keep patterns continuous across a box's faces
                    t = base + FACE_LIGHT[face]
                    if face in ("right", "front", "left", "back") and fh > 1:
                        t += grad * (0.5 - yy / (fh - 1))
                        cs = m.get("counter", 0)
                        if cs:
                            t += cs * (yy / (fh - 1) - 0.35)
                    if fw > 2 and fh > 2 and (xx == 0 or yy == 0 or xx == fw - 1 or yy == fh - 1):
                        t -= ao
                    t += pattern_value(m, face, X, Y, fw, fh, bseed) * contrast
                    dors = m.get("dorsal", 0)
                    if dors and (face == "top" or (face in ("right", "left") and yy < fh * 0.3)):
                        t -= dors
                    st = m.get("stripes", None)
                    if st:
                        period, width, strength = st
                        coord = X + Y * 0.35 + math.sin(Y * 0.5 + bi) * 1.5
                        if (coord % period) < width:
                            t -= strength
                    sp = m.get("spots", None)
                    if sp:
                        density, size, strength = sp
                        if vnoise(X, Y, bseed + 77, size) > 1.0 - density:
                            t += strength
                    c = ramp(fpal, t)
                    a = alpha
                    if m.pattern == "ethereal":
                        a = clamp(alpha * (0.75 + pattern_value(m, face, X, Y, fw, fh, bseed) * 0.6))
                    px[X, Y] = (clamp(c[0]), clamp(c[1]), clamp(c[2]), a)
                    if m.get("glow", False):
                        gl = m.get("glow_strength", 1.0)
                        gx[X, Y] = (clamp(c[0] * gl), clamp(c[1] * gl), clamp(c[2] * gl), 255)
        if decor:
            ctx = Ctx(img, glow, b, bseed)
            decor(ctx)
    return img, glow


class Ctx:
    """Decorator context for one box: face rectangles and pixel helpers."""

    def __init__(self, img, glow, box, seed):
        self.img = img
        self.glow = glow
        self.px = img.load()
        self.gx = glow.load()
        self.box = box
        self.seed = seed
        self.f = faces(box)
        self.tag = box["tag"]
        self.bone = box["bone"]
        self.mat = box["mat"]
        self.rnd = random.Random(seed)

    def is_(self, *tags):
        return self.tag in tags

    def bone_has(self, *names):
        return any(n in self.bone for n in names)

    def size(self, face):
        _, _, fw, fh = self.f[face]
        return fw, fh

    def put(self, face, x, y, color, glow=False, alpha=255):
        x0, y0, fw, fh = self.f[face]
        x, y = int(round(x)), int(round(y))
        if 0 <= x < fw and 0 <= y < fh:
            X, Y = x0 + x, y0 + y
            if X < self.img.width and Y < self.img.height:
                c = color if len(color) == 4 else (color[0], color[1], color[2], alpha)
                self.px[X, Y] = tuple(clamp(v) for v in c)
                if glow:
                    self.gx[X, Y] = (clamp(c[0]), clamp(c[1]), clamp(c[2]), 255)

    def get(self, face, x, y):
        x0, y0, fw, fh = self.f[face]
        if 0 <= x < fw and 0 <= y < fh:
            return self.px[x0 + x, y0 + y]
        return None

    def tint(self, face, x, y, mul=1.0, add=(0, 0, 0)):
        c = self.get(face, x, y)
        if c:
            self.put(face, x, y, (c[0] * mul + add[0], c[1] * mul + add[1], c[2] * mul + add[2], c[3]))

    def rect(self, face, x, y, w, h, color, glow=False):
        for yy in range(h):
            for xx in range(w):
                self.put(face, x + xx, y + yy, color, glow)

    def fill(self, face, color, glow=False):
        fw, fh = self.size(face)
        self.rect(face, 0, 0, fw, fh, color, glow)

    def hline(self, face, y, color, x0=0, x1=None, glow=False):
        fw, _ = self.size(face)
        for x in range(x0, fw if x1 is None else x1):
            self.put(face, x, y, color, glow)

    def eye(self, face, x, y, color, pupil=None, glow=False, size=1, socket=None):
        """An eye of size 1 (one pixel + socket shadow) or 2 (2x2 with highlight)."""
        if socket:
            for dx in range(-1, size + 1):
                self.tint(face, x + dx, y - 1, 0.7)
        if size == 1:
            self.put(face, x, y, color, glow)
        else:
            self.rect(face, x, y, size, size, color, glow)
            if pupil:
                self.put(face, x + size - 1, y + size - 1, pupil, False)
            self.put(face, x, y, lerp(color, (255, 255, 255), 0.5), glow)

    def eyes_front(self, color, row=0.3, inset=0.22, size=1, pupil=None, glow=False, socket=True):
        """Two eyes on the front face, mirrored."""
        fw, fh = self.size("front")
        y = int(fh * row)
        xl = int(fw * inset)
        xr = fw - 1 - xl - (size - 1)
        self.eye("front", xl, y, color, pupil, glow, size, socket)
        self.eye("front", xr, y, color, pupil, glow, size, socket)

    def eyes_sides(self, color, row=0.3, from_front=1, size=1, pupil=None, glow=False):
        """One eye on each side face, near the front edge."""
        fw, fh = self.size("right")
        y = int(fh * row)
        self.eye("right", fw - 1 - from_front - (size - 1), y, color, pupil, glow, size, True)
        self.eye("left", from_front, y, color, pupil, glow, size, True)

    def mouth(self, face, row, color=(30, 18, 16), teeth=None, x0=0, x1=None):
        fw, fh = self.size(face)
        self.hline(face, row, color, x0, x1)
        if teeth:
            for x in range(x0 + 1, (fw if x1 is None else x1) - 1, 2):
                self.put(face, x, row + 1 if row + 1 < fh else row, teeth)

    def all_faces(self):
        return list(self.f.keys())


def save(img, rel):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return path


def has_pixels(img):
    return img.getbbox() is not None
