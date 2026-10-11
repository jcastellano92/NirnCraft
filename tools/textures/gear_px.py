"""Pixel helpers shared by the gear generators (colors, ramps, the diagonal weapon lattice, outlines)."""
import math
import random

from PIL import Image


def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def shade(c, f):
    """f < 1 darkens, f > 1 lightens towards white."""
    if f <= 1:
        return (int(c[0] * f), int(c[1] * f), int(c[2] * f), c[3])
    return mix(c, (255, 255, 255, c[3]), min(1.0, f - 1))


def alpha(c, a):
    return (c[0], c[1], c[2], a)


def ramp(*hexes, a=255):
    """A color ramp, darkest first."""
    return [hexc(h, a) for h in hexes]


def new(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


class Lattice:
    """A square canvas addressed along the bottom-left -> top-right diagonal.

    a = x - y + (n - 1) runs along the weapon (0 at the bottom-left corner, 2n-2 at the top-right corner);
    b = x + y - (n - 1) runs across it (negative = upper-left side, positive = lower-right side).
    One pixel step along the diagonal is a += 2; a and b always have the same parity.
    """

    def __init__(self, n=16):
        self.n = n
        self.px = {}       # (x, y) -> color
        self.role = {}     # (x, y) -> role string (outline colors, skipping strings...)
        self.dark = {}     # (x, y) -> outline color for this pixel

    def xy(self, a, b):
        if (a + b) % 2:
            return None
        x = (a + b) // 2
        y = (b - a) // 2 + (self.n - 1)
        if 0 <= x < self.n and 0 <= y < self.n:
            return x, y
        return None

    def ab(self, x, y):
        return x - y + self.n - 1, x + y - self.n + 1

    def cells(self):
        for x in range(self.n):
            for y in range(self.n):
                a, b = self.ab(x, y)
                yield x, y, a, b

    def put(self, a, b, color, role="solid", dark=None):
        a, b = int(round(a)), int(round(b))
        if (a + b) % 2:
            a += 1   # snap decorations onto the lattice (one half-step along the weapon)
        p = self.xy(a, b)
        if p is None or color is None:
            return
        self.px[p] = color
        self.role[p] = role
        self.dark[p] = dark if dark is not None else shade(color, 0.33)

    def get(self, a, b):
        p = self.xy(a, b)
        return None if p is None else self.px.get(p)

    def erase(self, a, b):
        p = self.xy(a, b)
        if p is not None and p in self.px:
            del self.px[p]
            del self.role[p]
            del self.dark[p]

    def has(self, a, b):
        p = self.xy(a, b)
        return p is not None and p in self.px

    def image(self, outline=True, skip_roles=("string", "arrow", "glow", "nooutline")):
        img = new(self.n, self.n)
        pix = img.load()
        for (x, y), c in self.px.items():
            pix[x, y] = c
        if outline:
            for x in range(self.n):
                for y in range(self.n):
                    if (x, y) in self.px:
                        continue
                    best = None
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        q = (x + dx, y + dy)
                        if q in self.px and self.role[q] not in skip_roles:
                            # outlines follow the darker neighbour so blades get crisp dark edges
                            d = self.dark[q]
                            if best is None or sum(d[:3]) < sum(best[:3]):
                                best = d
                    if best is not None:
                        pix[x, y] = alpha(best, 255)
        return img


def dist(a1, b1, a2, b2):
    """Euclidean pixel distance between two lattice points."""
    return math.sqrt(((a1 - a2) ** 2 + (b1 - b2) ** 2) / 2.0)


def rnd(seed):
    return random.Random(seed)
