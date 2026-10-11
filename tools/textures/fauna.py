#!/usr/bin/env python3
"""Generates the fauna module's textures (PIL).

Writes PNGs into mod/src/main/resources/assets/skycraft/textures/:
  (the wildlife - deer, elk, sabre cats, bears, horkers, mudcrabs, mammoths, slaughterfish - are rig models painted by
   bestiary_fauna.py into entity/creatures/)
  entity/fauna/butterfly_monarch.png, butterfly_blue.png, luna_moth.png   32x32 (InsectModels.Butterfly)
  entity/fauna/dragonfly.png                              32x32  (InsectModels.Dragonfly, translucent wings)
  entity/fauna/torchbug.png, torchbug_glow.png            32x32  (InsectModels.Torchbug + emissive layer)
  item/<ingredient>.png                                   16x16 icons of the animal parts and insect ingredients
  block/<plant>_harvested.png                             16x16 bare stems of picked alchemy plants (arcane plants)

Every model box is painted face by face from the same UV layout as the Java models. Face conventions (Minecraft
box unwrap): top = (u+d, v, w, d), bottom = (u+d+w, v, w, d), right = (u, v+d, d, h), front (-z) = (u+d, v+d, w, h),
left = (u+d+w, v+d, d, h), back = (u+2d+w, v+d, w, h). Painters get the face plus normalized coordinates:
zt (0 = rear .. 1 = front of the box), ht (0 = top .. 1 = bottom), xt (0..1 across the face).
Run:  python3 tools/textures/fauna.py
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "skycraft", "textures")


# ---------------------------------------------------------------------------------------------------- helpers

def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, f):
    return (clamp(c[0] * f), clamp(c[1] * f), clamp(c[2] * f), c[3] if len(c) > 3 else 255)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    a = a if len(a) == 4 else a + (255,)
    b = b if len(b) == 4 else b + (255,)
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(4))


def jitter(c, rnd, amt):
    d = rnd.uniform(-amt, amt)
    return (clamp(c[0] + d + rnd.uniform(-amt * 0.3, amt * 0.3)), clamp(c[1] + d + rnd.uniform(-amt * 0.3, amt * 0.3)),
            clamp(c[2] + d + rnd.uniform(-amt * 0.3, amt * 0.3)), c[3])


def save(img, kind, name):
    p = os.path.join(TEX, kind, name + ".png")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    print("wrote", os.path.relpath(p, ROOT))


def box_faces(u, v, w, h, d):
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


FACE_LIGHT = {"top": 1.06, "front": 1.0, "right": 0.94, "left": 0.94, "back": 0.9, "bottom": 0.8}


def norm(i, n):
    return 0.5 if n <= 1 else i / (n - 1)


def coords(face, x, y, fw, fh):
    """(zt, ht, xt): position along the box's z (0 rear, 1 front), height (0 top, 1 bottom), across the face."""
    if face == "top":
        return norm(y, fh), 0.0, norm(x, fw)
    if face == "bottom":
        return 1.0 - norm(y, fh), 1.0, norm(x, fw)
    if face == "right":
        return norm(x, fw), norm(y, fh), norm(x, fw)
    if face == "left":
        return 1.0 - norm(x, fw), norm(y, fh), norm(x, fw)
    if face == "front":
        return 1.0, norm(y, fh), norm(x, fw)
    return 0.0, norm(y, fh), norm(x, fw)


def paint(img, rnd, u, v, w, h, d, fn, noise=7, light=True, edges=True):
    """fn(face, zt, ht, xt, x, y, fw, fh) -> rgba / rgb or None (left transparent)."""
    W, H = img.size
    for face, (x0, y0, fw, fh) in box_faces(u, v, w, h, d).items():
        for y in range(fh):
            for x in range(fw):
                px, py = x0 + x, y0 + y
                if px >= W or py >= H:
                    continue
                zt, ht, xt = coords(face, x, y, fw, fh)
                c = fn(face, zt, ht, xt, x, y, fw, fh)
                if c is None:
                    continue
                if len(c) == 3:
                    c = c + (255,)
                if light:
                    c = shade(c, FACE_LIGHT[face])
                if edges and face not in ("top", "bottom") and (x == 0 or x == fw - 1) and fw > 2:
                    c = shade(c, 0.92)
                if noise:
                    c = jitter(c, rnd, noise)
                img.putpixel((px, py), c)


def solid(color):
    return lambda face, zt, ht, xt, x, y, fw, fh: color


def fill_rect(img, rnd, x0, y0, x1, y1, color, noise=6):
    for y in range(y0, y1):
        for x in range(x0, x1):
            img.putpixel((x, y), jitter(color, rnd, noise))


def in_wing(x, y):
    return x <= WING_ROWS.get(y, -1)


def wing_edge(x, y):
    if not in_wing(x, y):
        return False
    for dx, dy in ((1, 0), (0, 1), (0, -1)):
        if not in_wing(x + dx, y + dy):
            return True
    return False


def paint_wing(img, rnd, x0, y0, fn):
    for y in range(8):
        for x in range(7):
            if in_wing(x, y):
                img.putpixel((x0 + x, y0 + y), fn(x, y))


def butterfly(name, main, vein, border, dots, body_c, seed, moth=False):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    paint(img, rnd, 0, 0, 1, 1, 4, solid(body_c), noise=4, edges=False)
    # antennae (front plane at 10,0 3x2): a V
    for (x, y) in ((10, 0), (12, 0), (11, 1)):
        img.putpixel((x, y), body_c)
    for (x, y) in ((13, 0), (15, 0), (14, 1)):
        img.putpixel((x, y), body_c)

    def wing(x, y):
        if moth:
            if y == 7 and x <= 4:
                return hexc("b07080")  # pink-brown leading edge
            if (x, y) in ((3, 5), (2, 1)):
                return hexc("e8c040")  # eyespots
            if (x, y) in ((4, 5), (3, 4), (2, 5), (3, 6), (1, 1), (2, 2), (3, 1)):
                return hexc("7a8a50")
            c = main if x > 0 else shade(main, 0.85)
            return jitter(c, rnd, 6)
        if wing_edge(x, y):
            return dots if (x + y) % 2 == 0 and x > 1 else border
        if x == 0:
            return shade(border, 1.2)
        if (x == 2 and y >= 2) or (y == 4 and x <= 3) or (x == 4 and y >= 5) or (y == 1 and x == 1):
            return vein
        return jitter(main, rnd, 8)

    paint_wing(img, rnd, 8, 6, wing)
    paint_wing(img, rnd, 15, 6, lambda x, y: shade(wing(x, y), 0.82))
    save(img, "entity/fauna", name)


def dragonfly():
    rnd = random.Random(71)
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    blue, black = hexc("2a6ad8"), hexc("101820")

    def body(face, zt, ht, xt, x, y, fw, fh):
        seg = int((1 - zt) * 9)
        return black if seg % 2 == 1 and zt < 0.8 else blue

    paint(img, rnd, 0, 0, 1, 1, 9, body, noise=5, edges=False)

    def head(face, zt, ht, xt, x, y, fw, fh):
        if face in ("right", "left", "front", "top"):
            return hexc("3ac8b8") if (x + y) % 2 == 0 else hexc("2a8ab8")  # compound eyes
        return black

    paint(img, rnd, 20, 0, 2, 2, 2, head, noise=4, edges=False)
    membrane, veinc = (205, 230, 250, 120), (60, 80, 110, 200)
    for (x0, y0, w) in ((2, 12, 7), (9, 12, 7), (2, 15, 6), (8, 15, 6)):
        for y in range(2):
            for x in range(w):
                c = membrane
                if y == 1 and x < w:
                    c = veinc  # leading edge vein
                if x == w - 2:
                    c = (40, 40, 60, 230)  # stigma
                img.putpixel((x0 + x, y0 + y), c)
    save(img, "entity/fauna", "dragonfly")


def torchbug():
    rnd = random.Random(81)
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    glow = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    shell, abdomen = hexc("2e2218"), hexc("d8d050")

    def thorax(face, zt, ht, xt, x, y, fw, fh):
        if face == "front" and y == 0:
            return hexc("6a5a40")
        return shell

    paint(img, rnd, 0, 0, 2, 2, 3, thorax, noise=4, edges=False)

    def abd(face, zt, ht, xt, x, y, fw, fh):
        if face == "top" and zt > 0.7:
            return shell
        return abdomen if (y + x) % 3 else shade(abdomen, 0.85)

    paint(img, rnd, 0, 5, 3, 3, 3, abd, noise=4, edges=False)
    paint(glow, rnd, 0, 5, 3, 3, 3, lambda f, zt, ht, xt, x, y, fw, fh: None if (f == "top" and zt > 0.7) else hexc("fff59a"),
          noise=6, light=False, edges=False)
    for (x0, y0) in ((15, 0), (19, 0)):
        for y in range(3):
            for x in range(4):
                img.putpixel((x0 + x, y0 + y), jitter(hexc("a89a80") if x < 3 else hexc("7a6a54"), rnd, 5))
    save(img, "entity/fauna", "torchbug")
    save(glow, "entity/fauna", "torchbug_glow")


# ---------------------------------------------------------------------------------------------------- item icons

def icon():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def put(img, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), c)


def line(img, x0, y0, x1, y1, c, width=1):
    steps = max(abs(x1 - x0), abs(y1 - y0)) * 2 + 1
    for i in range(steps + 1):
        t = i / steps
        x = x0 + (x1 - x0) * t
        y = y0 + (y1 - y0) * t
        for dx in range(width):
            put(img, int(round(x)) + dx, int(round(y)), c)


def outline(img, color):
    src = img.copy()
    for y in range(16):
        for x in range(16):
            if src.getpixel((x, y))[3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and src.getpixel((nx, ny))[3] > 200:
                    img.putpixel((x, y), color)
                    break


def antlers(large):
    img = icon()
    bone, dark = hexc("d2c098"), hexc("9a8458")
    if large:
        line(img, 3, 14, 6, 3, bone, 2)
        line(img, 6, 3, 12, 1, bone)
        line(img, 5, 9, 11, 7, bone)
        line(img, 8, 7, 9, 3, bone)
        line(img, 10, 7, 13, 4, bone)
        line(img, 4, 12, 9, 12, bone)
        for (x, y) in ((12, 1), (9, 3), (13, 4), (9, 12)):
            put(img, x, y, hexc("f4eedc"))
        line(img, 3, 14, 4, 11, dark)
    else:
        line(img, 5, 14, 8, 4, bone, 2)
        line(img, 8, 4, 11, 1, bone)
        line(img, 7, 8, 12, 6, bone)
        line(img, 6, 11, 3, 8, bone)
        for (x, y) in ((11, 1), (12, 6), (3, 8)):
            put(img, x, y, hexc("f4eedc"))
        line(img, 5, 14, 6, 12, dark)
    outline(img, hexc("3a2e1a"))
    return img


def fang(points, base, tipc, width=2):
    img = icon()
    n = len(points) - 1
    for i in range(n):
        t = i / max(1, n - 1)
        c = mix(base, tipc, t)
        w = width if i < n * 0.6 else 1
        line(img, points[i][0], points[i][1], points[i + 1][0], points[i + 1][1], c, w)
    outline(img, hexc("4a3e2a"))
    return img


def chitin():
    img = icon()
    rnd = random.Random(5)
    for y in range(3, 14):
        for x in range(2, 14):
            dx, dy = (x - 7.5) / 6.0, (y - 8.5) / 5.5
            if dx * dx + dy * dy <= 1.0 and not (y > 11 and x > 10):
                c = hexc("5e6a46")
                if dy < -0.4:
                    c = hexc("7a8656")
                if rnd.random() < 0.18:
                    c = hexc("8a8a5a")
                if dx * dx + dy * dy > 0.75:
                    c = hexc("3e4a30")
                put(img, x, y, c)
    outline(img, hexc("1e2618"))
    return img


def scales():
    img = icon()
    rnd = random.Random(9)
    for (cx, cy) in ((5, 5), (10, 5), (7, 9), (12, 10), (4, 11), (9, 13)):
        for y in range(cy - 2, cy + 2):
            for x in range(cx - 2, cx + 3):
                if abs(x - cx) + abs(y - cy) <= 2:
                    c = mix(hexc("8a9a80"), hexc("d8e0d0"), (cy + 1 - y) / 4.0)
                    if rnd.random() < 0.15:
                        c = hexc("e8f0e8")
                    put(img, x, y, c)
        put(img, cx, cy + 1, hexc("6a1e18"))
    outline(img, hexc("2a3228"))
    return img


def wing_icon(main, vein, border, dots, moth=False):
    img = icon()
    rnd = random.Random(13)
    # one forewing and hindwing seen from above, body at the left
    for y in range(16):
        for x in range(16):
            fx, fy = (x - 2) / 12.0, (y - 6.5) / 6.0           # forewing
            hx, hy = (x - 2) / 8.5, (y - 12) / 3.8             # hindwing
            fore = fx >= 0 and fx * fx + fy * fy <= 1.0 and y <= 9
            hind = hx >= 0 and hx * hx + hy * hy <= 1.0
            if moth and 4 <= x <= 6 and 13 <= y <= 15:
                hind = True                                    # luna moth tail
            if not (fore or hind):
                continue
            edge = (fore and fx * fx + fy * fy > 0.72) or (hind and not fore and hx * hx + hy * hy > 0.6)
            if moth:
                c = main
                if y == 1 and x < 12:
                    c = hexc("b07080")
                if (x, y) in ((8, 5), (6, 12)):
                    c = hexc("e8c040")
                elif abs(x - 8) + abs(y - 5) == 1 or abs(x - 6) + abs(y - 12) == 1:
                    c = hexc("7a8a50")
            elif edge:
                c = dots if (x + y) % 3 == 0 else border
            elif x % 4 == 1 or (y == 9 and x < 10):
                c = vein
            else:
                c = main
            put(img, x, y, jitter(c, rnd, 6))
    for y in range(3, 15):
        put(img, 1, y, hexc("201a14"))
    outline(img, hexc("1a1410"))
    return img


def dartwing_icon():
    img = icon()
    blue, black = hexc("2a6ad8"), hexc("101820")
    for i in range(10):
        put(img, 3 + i, 12 - i, black if i % 2 else blue)
        put(img, 4 + i, 12 - i, blue)
    put(img, 13, 3, hexc("3ac8b8"))
    put(img, 14, 2, hexc("3ac8b8"))
    put(img, 13, 2, hexc("2a8ab8"))
    wingc = (210, 232, 250, 255)
    line(img, 10, 6, 4, 2, wingc, 1)
    line(img, 10, 6, 15, 10, wingc, 1)
    line(img, 9, 7, 3, 4, wingc, 1)
    line(img, 9, 7, 14, 12, wingc, 1)
    outline(img, hexc("1a2430"))
    return img


def thorax_icon():
    img = icon()
    for y in range(16):
        for x in range(16):
            dx, dy = (x - 7.5) / 5.0, (y - 8.5) / 6.0
            d = dx * dx + dy * dy
            if d <= 1.0:
                c = mix(hexc("fff59a"), hexc("c8b030"), d)
                if y < 5:
                    c = hexc("3a2c1e")
                if y in (8, 11) and d < 0.9:
                    c = shade(c, 0.8)
                put(img, x, y, c)
    put(img, 6, 6, hexc("ffffff"))
    outline(img, hexc("2a1e10"))
    return img


def items():
    save(antlers(False), "item", "small_antlers")
    save(antlers(True), "item", "large_antlers")
    save(fang([(11, 2), (10, 5), (9, 8), (8, 10), (6, 12), (4, 13)], hexc("e8dcc0"), hexc("fffaf0")), "item", "sabre_cat_tooth")
    save(fang([(12, 2), (12, 5), (11, 8), (9, 11), (6, 13), (3, 13)], hexc("d8c8a0"), hexc("f8f2e2"), 3), "item", "horker_tusk")
    save(fang([(14, 1), (12, 3), (9, 5), (6, 8), (4, 11), (3, 14), (5, 15)], hexc("d0c098"), hexc("fcf6e8"), 3), "item", "mammoth_tusk")
    save(chitin(), "item", "mudcrab_chitin")
    save(scales(), "item", "slaughterfish_scales")
    save(wing_icon(hexc("f08a20"), hexc("2a1a10"), hexc("18120e"), hexc("f8f4ec")), "item", "monarch_wing")
    save(wing_icon(hexc("3a8ae8"), hexc("1a3a7a"), hexc("101828"), hexc("e8f0ff")), "item", "blue_butterfly_wing")
    save(wing_icon(hexc("b8f0c0"), hexc("7aa880"), hexc("7aa880"), hexc("7aa880"), moth=True), "item", "luna_moth_wing")
    save(dartwing_icon(), "item", "blue_dartwing")
    save(thorax_icon(), "item", "torchbug_thorax")


# ---------------------------------------------------------------------------------------------------- harvested plants

def stems(name, color, dark, tops, leaves, base=None):
    img = icon()
    for (x0, x1, top) in tops:
        line(img, x0, 15, x1, top, color)
        put(img, x1, top, dark)          # cut end
    for (x, y, d) in leaves:
        put(img, x, y, color)
        put(img, x + d, y, color)
        put(img, x + 2 * d, y - 1, dark)
    if base:
        for (x, y, c) in base:
            put(img, x, y, c)
    save(img, "block", name + "_harvested")


def harvested_plants():
    g, gd = hexc("2e5a2a"), hexc("1c3a1a")
    stems("nightshade", g, gd, [(8, 8, 9), (8, 5, 11), (8, 11, 10)], [(7, 13, -1), (9, 12, 1)])
    g, gd = hexc("5a6a4a"), hexc("3a4a30")
    stems("deathbell", g, gd, [(8, 8, 7)], [(7, 14, -1), (9, 13, 1)])
    pale, dk = hexc("cfe8d0"), hexc("6a9a80")
    stems("nirnroot", pale, dk, [(8, 7, 12), (8, 9, 12)], [],
          [(x, 14, dk) for x in range(6, 11)] + [(x, 13, dk) for x in (6, 10)] + [(x, 13, pale) for x in (7, 8, 9)])
    g, gd = hexc("5a7a6a"), hexc("3a5a4a")
    stems("frost_mirriam", g, gd, [(5, 4, 9), (8, 8, 8), (11, 12, 10)], [(5, 13, -1), (11, 12, 1), (8, 11, 1)])


def main():
    butterfly("butterfly_monarch", hexc("f08a20"), hexc("2a1a10"), hexc("18120e"), hexc("f8f4ec"), hexc("1a1410"), 101)
    butterfly("butterfly_blue", hexc("3a8ae8"), hexc("1a3a7a"), hexc("101828"), hexc("e8f0ff"), hexc("141a24"), 102)
    butterfly("luna_moth", hexc("b8f0c0"), hexc("7aa880"), hexc("7aa880"), hexc("7aa880"), hexc("e8f0d8"), 103, moth=True)
    dragonfly()
    torchbug()
    items()
    harvested_plants()


if __name__ == "__main__":
    main()
