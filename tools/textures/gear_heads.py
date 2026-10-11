"""Heads of the hafted weapons (war axes, battleaxes, maces, warhammers) as hand-tuned shape templates.

Each head is a list of primitives in pixel units around an anchor on the haft: s runs along the haft towards the top
right, t across it (negative = upper-left). Primitives are polygons, circles and rectangles; they are rasterised at
pixel centres, shaded with a top-left light (lit where the pixel above or to the left is outside the shape), and
segments flagged as cutting edges get the material's highlight. Battleaxes mirror their bit across the haft.
"""
import math

from gear_px import Lattice, hexc, mix, rnd, shade

SQ2 = math.sqrt(2)


def _in_poly(s, t, pts):
    inside = False
    n = len(pts)
    for i in range(n):
        s1, t1 = pts[i]
        s2, t2 = pts[(i + 1) % n]
        if (t1 > t) != (t2 > t):
            x = s1 + (t - t1) * (s2 - s1) / (t2 - t1)
            if s < x:
                inside = not inside
    return inside


def _seg_dist(s, t, p, q):
    (s1, t1), (s2, t2) = p, q
    ds, dt = s2 - s1, t2 - t1
    L = ds * ds + dt * dt
    k = 0 if L == 0 else max(0.0, min(1.0, ((s - s1) * ds + (t - t1) * dt) / L))
    return math.hypot(s - (s1 + k * ds), t - (t1 + k * dt))


def P(*pts, part="metal", edges=(), mirror=False, deco=None):
    return dict(kind="poly", pts=pts, part=part, edges=edges, mirror=mirror, deco=deco)


def C(cs, ct, r, part="metal", mirror=False, deco=None):
    return dict(kind="circle", c=(cs, ct), r=r, part=part, edges=(), mirror=mirror, deco=deco)


def R(s0, t0, s1, t1, part="metal", mirror=False, deco=None, edge_t=None):
    pts = ((s0, t0), (s1, t0), (s1, t1), (s0, t1))
    edges = ()
    if edge_t == "lo":
        edges = (0,)
    elif edge_t == "hi":
        edges = (2,)
    return dict(kind="poly", pts=pts, part=part, edges=edges, mirror=mirror, deco=deco)


def _inside(prim, s, t):
    if prim["kind"] == "circle":
        return math.hypot(s - prim["c"][0], t - prim["c"][1]) <= prim["r"]
    return _in_poly(s, t, prim["pts"])


def _edge_dist(prim, s, t):
    if prim["kind"] != "poly" or not prim["edges"]:
        return 99.0
    pts = prim["pts"]
    return min(_seg_dist(s, t, pts[i], pts[(i + 1) % len(pts)]) for i in prim["edges"])


def _mirror(prim):
    q = dict(prim)
    if prim["kind"] == "circle":
        q["c"] = (prim["c"][0], -prim["c"][1])
    else:
        q["pts"] = tuple((s, -t) for s, t in prim["pts"])
    return q


def render_head(cv, M, prims, anchor_a, seed=0, scale=1.0):
    """Rasterises primitives around lattice row anchor_a onto the canvas (over the haft)."""
    expanded = []
    for p in prims:
        expanded.append(p)
        if p["mirror"]:
            expanded.append(_mirror(p))
    # pass 1: which primitive owns each pixel (later primitives win)
    owner = {}
    for x, y, a, b in cv.cells():
        s, t = (a - anchor_a) / SQ2 / scale, b / SQ2 / scale
        for i, p in enumerate(expanded):
            if _inside(p, s, t):
                owner[(x, y)] = (i, s, t)
    r = rnd(seed)
    for (x, y), (i, s, t) in owner.items():
        p = expanded[i]
        ramp = _ramp(M, p["part"])

        def same(qx, qy):
            o = owner.get((qx, qy))
            return o is not None and expanded[o[0]]["part"] == p["part"]

        lit = (not same(x, y - 1)) + (not same(x - 1, y))
        shadow = (not same(x, y + 1)) + (not same(x + 1, y))
        col = ramp[2] if lit > shadow else (ramp[0] if shadow > lit else ramp[1])
        if lit == 2:
            col = ramp[3] if len(ramp) > 3 else ramp[2]
        if _edge_dist(p, s, t) < 0.72:
            col = ramp[3] if len(ramp) > 3 else ramp[2]
        if p.get("deco"):
            c2 = p["deco"](M, s, t, col, r)
            if c2 is not None:
                col = c2
        a, b = cv.ab(x, y)
        cv.put(a, b, col, dark=shade(ramp[0], 0.5))


def _ramp(M, part):
    if part == "metal":
        return M["metal"]
    if part == "trim":
        return M["trim"]
    if part == "accent":
        return M["accent"] or M["trim"]
    if part == "wood":
        return M["wood"]
    if part == "grip":
        return M["grip"]
    if part == "stone":
        return [hexc("4a4a4c"), hexc("6e6e70"), hexc("8e8e90"), hexc("aaaaac")]
    raise ValueError(part)


# ------------------------------------------------------------------------------------------- decorations

def glow_line(M, s, t, col, r):
    return (M["accent"] or M["trim"])[1] if abs(s) < 0.45 and -4.2 < t < -1.4 else None


def glow_line_m(M, s, t, col, r):
    return (M["accent"] or M["trim"])[1] if abs(s) < 0.45 and 1.4 < abs(t) < 4.2 else None


def carve_dots(M, s, t, col, r):
    if abs(abs(t) - 3.0) < 0.5 and int(round(s * 1.4)) % 2 == 0 and abs(s) < 2.0:
        return M["accent"][1]
    return None


def rivets(M, s, t, col, r):
    if abs(abs(t) - 2.0) < 0.45 and abs(abs(s) - 1.4) < 0.45:
        return M["accent"][1] if M["accent"] else M["trim"][0]
    return None


def facets(M, s, t, col, r):
    return M["metal"][3] if (int(math.floor(s * 1.2 - t * 0.8)) % 3 == 0) else None


def rough(M, s, t, col, r):
    return shade(col, 0.82) if r.random() < 0.18 else None


def segments(M, s, t, col, r):
    return shade(col, 0.72) if int(math.floor(abs(t) * 1.3)) % 3 == 2 else None


def bone_rings(M, s, t, col, r):
    return M["trim"][1] if int(math.floor(abs(t) * 1.1 + s * 0.3)) % 3 == 2 else None


def ice_glint(M, s, t, col, r):
    return M["accent"][1] if (int(s * 1.5) + int(t * 1.5)) % 4 == 0 else None


# ------------------------------------------------------------------------------------------- axes
# bit polygons for the war axe (upper-left side); the battleaxe mirrors a slimmer copy on both sides

AX = {
    "bearded": [P((1.0, -0.6), (2.0, -2.2), (2.3, -4.4), (0.4, -4.9), (-2.4, -5.0), (-3.6, -4.3), (-2.2, -2.6), (-1.0, -0.6),
                  edges=(2, 3, 4), deco=rough),
                R(-0.8, 0.6, 0.8, 1.9, part="trim")],
    "crescent": [P((0.9, -0.6), (1.8, -2.0), (3.3, -4.0), (2.0, -5.2), (0.0, -5.5), (-2.0, -5.2), (-3.3, -4.0), (-1.8, -2.0),
                   (-0.9, -0.6), edges=(2, 3, 4, 5, 6)),
                 R(-0.8, 0.6, 0.8, 1.6, part="trim")],
    "orc_spiked": [P((1.4, -0.6), (3.0, -3.6), (3.4, -5.4), (-3.0, -5.4), (-3.4, -3.6), (-1.4, -0.6), edges=(2,), deco=rough),
                   P((3.0, -3.8), (4.8, -4.6), (3.2, -5.0)),
                   P((-0.6, 0.6), (0.6, 0.6), (1.6, 3.2)),
                   P((-1.6, -1.6), (-2.8, -0.2), (-1.0, -1.0), part="trim")],
    "dwemer_axe": [P((1.4, -0.6), (1.4, -1.8), (2.8, -2.6), (2.8, -5.2), (-2.8, -5.2), (-2.8, -2.6), (-1.4, -1.8), (-1.4, -0.6),
                     edges=(3,), deco=rivets),
                   R(-1.0, 0.6, 1.0, 2.6, part="trim")],
    "elf_crescent": [P((0.8, -0.6), (2.2, -1.8), (4.4, -2.6), (4.2, -4.2), (1.6, -5.4), (-1.4, -5.2), (-3.0, -4.2), (-1.6, -2.2),
                       (-0.8, -0.6), edges=(2, 3, 4, 5, 6)),
                     P((0.0, -2.6), (1.2, -3.4), (0.0, -4.2), (-1.2, -3.4), part="accent")],
    "crystal_axe": [P((1.0, -0.6), (2.6, -2.2), (2.8, -4.6), (0.6, -5.8), (-2.4, -5.0), (-2.8, -2.4), (-1.0, -0.6), edges=(2, 3, 4),
                      deco=facets),
                    P((-0.6, 0.6), (0.6, 0.6), (0.0, 2.8), part="metal")],
    "angular_axe": [P((1.2, -0.6), (1.2, -2.2), (3.8, -5.4), (-1.2, -5.4), (-3.6, -4.2), (-2.4, -2.6), (-1.2, -0.6), edges=(2, 3, 4)),
                    R(-1.0, 0.6, 1.0, 2.2, part="trim")],
    "daedric_axe": [P((1.0, -0.6), (2.0, -2.0), (4.4, -2.8), (3.4, -4.0), (2.8, -5.6), (1.2, -4.8), (0.0, -5.8), (-1.2, -4.8),
                      (-2.8, -5.6), (-3.4, -4.0), (-4.4, -2.8), (-2.0, -2.0), (-1.0, -0.6), edges=(2, 3, 4, 5, 6, 7, 8, 9, 10),
                      deco=glow_line),
                    P((-0.6, 0.6), (0.6, 0.6), (1.8, 3.0), part="trim")],
    "bone_axe": [P((1.0, -0.6), (2.4, -2.6), (3.0, -4.8), (1.2, -5.4), (-1.0, -5.0), (-3.0, -4.6), (-3.6, -3.0), (-2.0, -2.2),
                   (-1.0, -0.6), edges=(2, 3, 4, 5), deco=bone_rings),
                 P((-0.6, 0.6), (0.6, 0.6), (0.2, 2.6), part="trim")],
    "stone_axe": [C(0.0, -3.0, 2.5, part="stone", deco=rough),
                  R(-0.9, -1.2, 0.9, 1.0, part="trim")],
    "chitin_axe": [P((0.8, -0.6), (1.6, -2.6), (1.2, -4.6), (-0.8, -5.4), (-3.4, -5.2), (-4.6, -4.0), (-3.0, -3.8), (-1.6, -2.4),
                     (-0.8, -0.6), edges=(2, 3, 4, 5), deco=segments)],
    "nordic_axe": [P((1.0, -0.6), (2.2, -2.4), (2.6, -4.6), (0.6, -5.2), (-2.6, -5.4), (-4.0, -4.4), (-2.4, -2.6), (-1.0, -0.6),
                     edges=(2, 3, 4), deco=carve_dots),
                   R(-0.8, 0.6, 0.8, 1.9, part="trim")],
    "skyforge_axe": [P((0.9, -0.6), (2.2, -2.0), (3.6, -4.0), (2.0, -5.4), (-1.2, -5.4), (-3.4, -4.6), (-2.0, -2.2), (-0.9, -0.6),
                       edges=(2, 3, 4, 5)),
                     P((-0.6, 0.6), (0.6, 0.6), (1.2, 2.8), part="trim")],
    "akaviri_axe": [P((1.0, -0.6), (1.6, -4.6), (0.4, -5.4), (-2.6, -5.4), (-2.2, -0.6), edges=(1, 2, 3)),
                    R(-0.8, 0.6, 0.8, 1.6, part="trim")],
    "ice_axe": [P((1.0, -0.6), (2.6, -2.2), (3.4, -4.6), (1.0, -5.8), (-1.8, -5.6), (-2.8, -3.2), (-1.0, -0.6), edges=(2, 3, 4),
                  deco=ice_glint),
                P((-0.6, 0.6), (0.6, 0.6), (0.4, 3.2))],
    "tabar": [P((0.8, -0.6), (1.2, -2.4), (0.6, -4.6), (-2.2, -5.4), (-4.6, -4.6), (-3.2, -2.6), (-0.8, -0.6), edges=(2, 3, 4))],
    "glaive_axe": [P((0.8, -0.6), (2.0, -2.0), (4.4, -3.0), (4.6, -4.4), (2.4, -5.4), (-1.0, -5.2), (-2.4, -3.6), (-0.8, -0.6),
                     edges=(2, 3, 4, 5)),
                   P((0.0, -2.8), (1.0, -3.6), (0.0, -4.4), (-1.0, -3.6), part="accent")],
    "fin_axe": [P((0.8, -0.6), (2.6, -2.2), (3.4, -4.0), (2.0, -4.4), (1.8, -5.4), (0.0, -4.8), (-1.2, -5.6), (-2.6, -4.6),
                  (-2.4, -2.6), (-0.8, -0.6), edges=(2, 3, 4, 5, 6, 7))],
}


def _battle(prims):
    """Battleaxe version of a war axe head: both bits, a little slimmer."""
    out = []
    for p in prims:
        q = dict(p)
        if p["kind"] == "poly":
            if all(t <= 0.01 for _, t in p["pts"]):
                q["pts"] = tuple((s * 0.92, t * 0.9) for s, t in p["pts"])
                q["mirror"] = True
            else:
                continue   # the back of the head is the other bit now
        elif p["kind"] == "circle":
            q = dict(p, c=(p["c"][0], p["c"][1] * 0.9), mirror=True)
        out.append(q)
    return out


# ------------------------------------------------------------------------------------------- maces

def _flanges(n=4, length=3.4, w=0.65, part="metal", r0=0.0):
    out = []
    for k in range(n):
        ang = math.pi * k / n
        cs, sn = math.cos(ang), math.sin(ang)
        pts = []
        for (u, v) in ((-length, -w), (length, -w), (length, w), (-length, w)):
            pts.append((u * cs - v * sn, u * sn + v * cs))
        out.append(dict(kind="poly", pts=tuple(pts), part=part, edges=(), mirror=False, deco=None))
    return out


def _spikes(n=8, r_in=1.8, r_out=3.6, w=0.55, part="metal", phase=0.0):
    out = []
    for k in range(n):
        ang = phase + 2 * math.pi * k / n
        cs, sn = math.cos(ang), math.sin(ang)
        tip = (r_out * cs, r_out * sn)
        b1 = (r_in * cs - w * sn, r_in * sn + w * cs)
        b2 = (r_in * cs + w * sn, r_in * sn - w * cs)
        out.append(dict(kind="poly", pts=(b1, tip, b2), part=part, edges=(), mirror=False, deco=None))
    return out


MC = {
    "flanged": _flanges(4, 3.2) + [C(0, 0, 2.0)],
    "spiked_flange": _flanges(4, 3.0) + _spikes(4, 1.6, 3.4, phase=math.pi / 4) + [C(0, 0, 2.0)],
    "spike_ball": _spikes(8, 1.8, 3.6) + [C(0, 0, 2.3, deco=rough)],
    "dwemer_mace": [R(-2.6, -2.6, 2.6, 2.6, part="trim"), R(-1.8, -3.4, 1.8, 3.4), C(0, 0, 0.9, part="accent")],
    "elf_mace": [P((3.6, 0), (0.8, -1.2), (0, -3.4), (-0.8, -1.2), (-3.0, 0), (-0.8, 1.2), (0, 3.4), (0.8, 1.2)), C(0, 0, 1.0, part="accent")],
    "crystal_mace": [P((3.8, -0.4), (1.2, -2.6), (-1.6, -2.4), (-2.4, 0.2), (-1.0, 2.6), (1.6, 2.4), deco=facets),
                     P((4.6, -1.6), (2.0, -1.0), (3.0, -2.8))],
    "ebony_mace": _flanges(3, 3.4, 0.8) + [C(0, 0, 1.9)],
    "daedric_mace": _spikes(6, 1.6, 3.8, 0.7) + [C(0, 0, 2.3, deco=glow_line)] + [C(0, 0, 0.8, part="accent")],
    "bone_mace": [P((3.4, -1.6), (2.6, -2.6), (-1.6, -2.0), (-2.6, -0.6), (-1.6, 2.0), (2.6, 2.4), (3.4, 1.4), deco=bone_rings),
                  P((3.0, -2.0), (4.4, -3.2), (3.6, -1.4)), P((3.0, 2.0), (4.4, 3.2), (3.6, 1.4))],
    "antler_club": [R(-4.0, -1.4, 3.0, 1.4, deco=rough), P((2.0, -1.2), (3.6, -3.4), (2.8, -1.0)),
                    P((0.0, -1.2), (1.0, -3.0), (0.6, -1.0)), P((2.0, 1.2), (3.8, 3.0), (2.8, 1.0))],
    "chitin_mace": [C(0, 0, 2.6, deco=segments), P((2.0, -1.4), (3.8, -2.4), (2.4, -0.4)), P((2.0, 1.4), (3.8, 2.4), (2.4, 0.4))],
    "nordic_mace": _flanges(4, 3.3, 0.7) + [C(0, 0, 2.0, deco=carve_dots), C(0, 0, 0.7, part="trim")],
    "ice_mace": [P((4.0, 0.0), (1.4, -2.6), (-1.6, -2.0), (-2.4, 0.4), (-0.6, 2.6), (1.8, 2.2), deco=ice_glint),
                 P((3.2, -1.4), (4.4, -3.4), (2.0, -2.2)), P((3.0, 1.4), (4.2, 3.0), (2.0, 2.0))],
    "kanabo": [R(-5.0, -1.6, 3.2, 1.6, part="wood"), C(3.2, 0, 1.6, part="wood")] +
              [C(s, t, 0.5, part="trim") for s in (-3.6, -1.6, 0.4, 2.4) for t in (-1.2, 1.2)],
    "breton_mace": _flanges(4, 3.2, 0.6) + _spikes(4, 1.4, 3.0, phase=math.pi / 4) + [C(0, 0, 1.9), C(0, 0, 0.7, part="accent")],
    "hist_club": [R(-5.0, -1.4, 3.0, 1.4, part="wood", deco=rough), C(3.0, 0, 1.7, part="wood", deco=rough),
                  C(-1.0, -1.6, 0.6, part="accent"), C(1.4, 1.6, 0.6, part="accent")],
    "chitin_club": [R(-5.0, -1.4, 3.0, 1.4, deco=segments), C(3.0, 0, 1.9, deco=segments)],
}

# ------------------------------------------------------------------------------------------- warhammers

HM = {
    "block": [R(-2.0, -3.8, 2.0, 3.8, deco=rough), R(-2.4, -4.2, 2.4, -3.2, part="trim"), R(-2.4, 3.2, 2.4, 4.2, part="trim")],
    "beak": [R(-1.8, 0.0, 1.8, 3.8), R(-2.2, 3.2, 2.2, 4.2, part="trim"), P((-1.2, 0.0), (1.2, 0.0), (0.4, -5.2), edges=(1,))],
    "orc_maul": [R(-2.2, -3.6, 2.4, 3.6, deco=rough), P((2.4, -3.6), (4.2, -4.4), (2.4, -2.0)), P((2.4, 3.6), (4.2, 4.4), (2.4, 2.0)),
                 P((-2.2, -3.6), (-3.6, -4.6), (-2.2, -2.2)), P((-2.2, 3.6), (-3.6, 4.6), (-2.2, 2.2))],
    "dwemer_hammer": [R(-2.6, -4.0, 2.6, 4.0, part="trim"), R(-1.8, -4.8, 1.8, 4.8), R(-0.8, -0.8, 0.8, 0.8, part="accent"),
                      R(-2.6, -0.4, 2.6, 0.4, part="trim")],
    "elf_hammer": [P((2.2, 0.0), (1.6, -4.2), (-1.6, -4.2), (-2.2, 0.0), (-1.6, 4.2), (1.6, 4.2)),
                   P((0.0, -4.2), (1.2, -5.6), (-1.2, -5.6), part="accent"), P((0.0, 4.2), (1.2, 5.4), (-1.2, 5.4), part="accent")],
    "crystal_hammer": [P((2.6, -1.0), (2.0, -4.6), (-0.4, -5.4), (-2.4, -3.6), (-2.4, 3.6), (-0.4, 5.4), (2.0, 4.6), (2.6, 1.0),
                         deco=facets)],
    "angular_hammer": [P((2.2, -5.2), (2.2, 5.2), (-1.4, 4.0), (-2.2, 0.0), (-1.4, -4.0), deco=None), R(1.4, -5.6, 2.6, 5.6, part="accent")],
    "daedric_hammer": [R(-2.2, -4.0, 2.2, 4.0, deco=glow_line_m), P((2.2, -4.0), (4.4, -5.2), (2.2, -2.2)),
                       P((2.2, 4.0), (4.4, 5.2), (2.2, 2.2)), P((-2.2, -4.0), (-3.6, -5.6), (-1.0, -4.0)),
                       P((-2.2, 4.0), (-3.6, 5.6), (-1.0, 4.0)), P((-0.8, 0.0), (0.8, 0.0), (0.0, 0.0), part="accent")],
    "bone_hammer": [P((2.0, -4.6), (2.4, 4.0), (-1.8, 4.4), (-2.4, -3.8), deco=bone_rings), P((-1.0, -4.0), (0.6, -6.0), (1.2, -4.0))],
    "stone_maul": [C(0.0, 0.0, 3.0, part="stone", deco=rough), R(-1.0, -1.0, 1.0, 1.0, part="trim")],
    "chitin_hammer": [P((2.0, -4.0), (2.6, 0.0), (2.0, 4.0), (-2.0, 4.4), (-2.6, 0.0), (-2.0, -4.4), deco=segments)],
    "nordic_hammer": [R(-2.2, -4.0, 2.2, 4.0, deco=carve_dots), R(-2.6, -4.6, 2.6, -3.6, part="trim"), R(-2.6, 3.6, 2.6, 4.6, part="trim")],
    "akaviri_hammer": [R(-2.4, -3.0, 2.4, 3.0, part="wood"), R(-2.8, -3.4, 2.8, -2.6, part="trim"), R(-2.8, 2.6, 2.8, 3.4, part="trim")],
    "ice_hammer": [P((2.6, -1.0), (2.2, -4.8), (-0.2, -5.6), (-2.6, -3.4), (-2.6, 3.4), (-0.2, 5.6), (2.2, 4.8), (2.6, 1.0),
                     deco=ice_glint), P((2.2, -4.8), (3.6, -6.0), (1.0, -5.4))],
}


# ------------------------------------------------------------------------------------------- builders

def haft(cv, M, a0, a1, grip_end, metal=False):
    w = M["trim"] if metal else M["wood"]
    g = M["grip"]
    for a in range(a0, a1 + 1):
        for b in (-1, 0, 1):
            if cv.xy(a, b) is None:
                continue
            if a <= grip_end:
                col = g[2] if a % 3 == 0 else (g[1] if b <= 0 else g[0])
                dk = shade(g[0], 0.5)
            else:
                col = w[2] if b < 0 else (w[1] if b == 0 else w[0])
                dk = shade(w[0], 0.5)
            cv.put(a, b, col, dark=dk)
    cv.put(a0 - 1, 0, M["trim"][1], dark=shade(M["trim"][0], 0.5))


def axe(M, style, double, seed=0):
    cv = Lattice(16)
    prims = AX[style]
    if double:
        haft(cv, M, 2, 27, 9)
        render_head(cv, M, _battle(prims), 21, seed, 1.1)
        cv.put(28, 0, M["trim"][2], dark=shade(M["trim"][0], 0.5))
    else:
        haft(cv, M, 2, 25, 8)
        render_head(cv, M, prims, 19.5, seed, 1.12)
    return cv.image()


METAL_HAFTS = ("dwarven", "ebony", "daedric", "glass", "stalhrim", "elven", "maormer")


def mace(M, style, mat, seed=0):
    cv = Lattice(16)
    club = style in ("antler_club", "kanabo", "hist_club", "chitin_club")
    haft(cv, M, 2, 20 if not club else 16, 8, metal=mat in METAL_HAFTS)
    render_head(cv, M, MC[style], 23 if not club else 22.5, seed, 1.2 if not club else 1.0)
    return cv.image()


def warhammer(M, style, mat, seed=0):
    cv = Lattice(16)
    haft(cv, M, 2, 25, 9, metal=mat in METAL_HAFTS)
    render_head(cv, M, HM[style], 21.5, seed, 1.1)
    return cv.image()
