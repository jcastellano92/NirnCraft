"""Offline preview of rig-model creatures: renders the textured model (rest pose or any animation frame) from a few
angles into a contact sheet, with the same box/UV/bone maths as Minecraft's ModelPart. For checking models and
textures without starting the game.

    py -3 tools/textures/rigview.py wolf [wolf_ice] [--anim walk --t 0.3] [--out sheet.png]

Needs the rig layouts (tools/textures/rigs) and, for --anim, mod/build/rigdump/anims (dump_rigs.sh writes both).
"""
import argparse
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RIGS = os.path.join(HERE, "rigs")
ANIMS = os.path.join(ROOT, "mod", "build", "rigdump", "anims")
TEX = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "nirncraft", "textures", "entity")


def rot_zyx(rx, ry, rz):
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


def sample(ch, t):
    times, vals = ch["times"], ch["values"]
    n = len(times)
    i = 0
    while i < n - 1 and times[i + 1] < t:
        i += 1
    j = min(n - 1, i + 1)
    if j == i or t <= times[0]:
        return np.array(vals[i if t > times[0] else 0], dtype=float)
    f = max(0.0, min(1.0, (t - times[i]) / max(1e-6, times[j] - times[i])))
    a, b = np.array(vals[i], dtype=float), np.array(vals[j], dtype=float)
    if ch["smooth"]:
        p0 = np.array(vals[max(0, i - 1)], dtype=float)
        p3 = np.array(vals[min(n - 1, j + 1)], dtype=float)
        return catmull(p0, a, b, p3, f)
    return a + (b - a) * f


def pose_offsets(anim, t):
    """bone -> [rot(rad) xyz, pos xyz (model space, y down), scale add xyz]"""
    out = {}
    if anim is None:
        return out
    L = anim["length"]
    tt = (t % L) if anim["loop"] and L > 0 else min(t, L)
    for ch in anim["channels"]:
        v = sample(ch, tt)
        o = out.setdefault(ch["bone"], [np.zeros(3), np.zeros(3), np.zeros(3)])
        if ch["target"] == "ROTATION":
            o[0] += np.radians(v)
        elif ch["target"] == "POSITION":
            o[1] += np.array([v[0], -v[1], v[2]])
        else:
            o[2] += v - 1.0
    return out


def bone_mats(layout, offsets):
    bones = {b["name"]: b for b in layout["bones"]}
    mats = {}

    def mat(name):
        if name in mats:
            return mats[name]
        b = bones[name]
        off = offsets.get(name)
        x, y, z = b["x"], b["y"], b["z"]
        rx, ry, rz = math.radians(b["rx"]), math.radians(b["ry"]), math.radians(b["rz"])
        sc = np.ones(3)
        if off is not None:
            rx, ry, rz = rx + off[0][0], ry + off[0][1], rz + off[0][2]
            x, y, z = x + off[1][0], y + off[1][1], z + off[1][2]
            sc = sc + off[2]
        M = np.eye(4)
        M[:3, 3] = [x, y, z]
        R = np.eye(4)
        R[:3, :3] = rot_zyx(rx, ry, rz) @ np.diag(sc)
        local = M @ R
        if b["parent"]:
            local = mat(b["parent"]) @ local
        mats[name] = local
        return local

    for n in bones:
        mat(n)
    return mats


def box_polys(b):
    """The six polygons of a box: list of (4 vertex positions, 4 uvs) exactly like ModelPart.Cube."""
    x0, y0, z0 = b["x"], b["y"], b["z"]
    w, h, d = b["w"], b["h"], b["d"]
    g = b.get("inflate", 0.0)
    u, v = b["u"], b["v"]
    x1, y1, z1 = x0 + w + g, y0 + h + g, z0 + d + g
    x0, y0, z0 = x0 - g, y0 - g, z0 - g
    mirror = b["mirror"]
    if mirror:
        x0, x1 = x1, x0
    v7 = (x0, y0, z0)
    v0 = (x1, y0, z0)
    v1 = (x1, y1, z0)
    v2 = (x0, y1, z0)
    v3 = (x0, y0, z1)
    v4 = (x1, y0, z1)
    v5 = (x1, y1, z1)
    v6 = (x0, y1, z1)
    f4, f5, f6, f7 = u, u + d, u + d + w, u + d + w + w
    f8, f9 = u + d + w + d, u + d + w + d + w
    f10, f11, f12 = v, v + d, v + d + h
    polys = [
        ([v4, v3, v7, v0], (f5, f10, f6, f11)),
        ([v1, v2, v6, v5], (f6, f11, f7, f10)),
        ([v7, v3, v6, v2], (f4, f11, f5, f12)),
        ([v0, v7, v2, v1], (f5, f11, f6, f12)),
        ([v4, v0, v1, v5], (f6, f11, f8, f12)),
        ([v3, v4, v5, v6], (f8, f11, f9, f12)),
    ]
    out = []
    for verts, (ua, va, ub, vb) in polys:
        uvs = [(ub, va), (ua, va), (ua, vb), (ub, vb)]
        if mirror:
            verts = verts[::-1]
            uvs = uvs[::-1]
        out.append((verts, uvs))
    return out


def render(layout, tex, offsets, yaw, pitch, size=360, scale=None, tint=None):
    mats = bone_mats(layout, offsets)
    tw, th = tex.size
    T = np.asarray(tex.convert("RGBA")).astype(float)
    if tint is not None:
        T[..., :3] *= np.array(tint) / 255.0
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    V = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    P = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    view = P @ V
    tris = []
    allpts = []
    for b in layout["boxes"]:
        M = mats[b["bone"]]
        for verts, uvs in box_polys(b):
            pts = []
            for p in verts:
                q = M @ np.array([p[0], p[1], p[2], 1.0])
                pts.append(view @ q[:3])
            pts = np.array(pts)
            allpts.append(pts)
            e1, e2 = pts[1] - pts[0], pts[3] - pts[0]
            n = np.cross(e1, e2)
            nn = np.linalg.norm(n)
            light = 0.65 + 0.35 * abs(n[1] / nn) if nn > 1e-6 else 1.0
            if nn > 1e-6 and n[1] / nn < 0:
                light = 0.75
            tris.append((pts[[0, 1, 2]], [uvs[0], uvs[1], uvs[2]], light))
            tris.append((pts[[0, 2, 3]], [uvs[0], uvs[2], uvs[3]], light))
    allp = np.concatenate(allpts)
    mn, mx = allp.min(axis=0), allp.max(axis=0)
    span = max(mx[0] - mn[0], mx[1] - mn[1], 1)
    s = scale or (size * 0.86 / span)
    ox = size / 2 - (mn[0] + mx[0]) / 2 * s
    oy = size / 2 - (mn[1] + mx[1]) / 2 * s
    img = np.zeros((size, size, 4))
    img[..., :3] = (58, 64, 74)
    img[..., 3] = 255
    zbuf = np.full((size, size), 1e9)
    for pts, uvs, light in tris:
        sx = pts[:, 0] * s + ox
        syy = pts[:, 1] * s + oy
        z = pts[:, 2]
        xmin, xmax = int(max(0, math.floor(sx.min()))), int(min(size - 1, math.ceil(sx.max())))
        ymin, ymax = int(max(0, math.floor(syy.min()))), int(min(size - 1, math.ceil(syy.max())))
        if xmax < xmin or ymax < ymin:
            continue
        den = (syy[1] - syy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (syy[0] - syy[2])
        if abs(den) < 1e-9:
            continue
        X, Y = np.meshgrid(np.arange(xmin, xmax + 1) + 0.5, np.arange(ymin, ymax + 1) + 0.5)
        a = ((syy[1] - syy[2]) * (X - sx[2]) + (sx[2] - sx[1]) * (Y - syy[2])) / den
        bb = ((syy[2] - syy[0]) * (X - sx[2]) + (sx[0] - sx[2]) * (Y - syy[2])) / den
        c = 1 - a - bb
        m = (a >= -1e-4) & (bb >= -1e-4) & (c >= -1e-4)
        if not m.any():
            continue
        zz = a * z[0] + bb * z[1] + c * z[2]
        U = a * uvs[0][0] + bb * uvs[1][0] + c * uvs[2][0]
        Vv = a * uvs[0][1] + bb * uvs[1][1] + c * uvs[2][1]
        ui = np.clip(np.floor(U).astype(int), 0, tw - 1)
        vi = np.clip(np.floor(Vv).astype(int), 0, th - 1)
        col = T[vi, ui]
        m &= col[..., 3] > 25
        sub = zbuf[ymin:ymax + 1, xmin:xmax + 1]
        m &= zz < sub
        sub[m] = zz[m]
        out = img[ymin:ymax + 1, xmin:xmax + 1]
        alpha = (col[..., 3:4] / 255.0)
        shaded = col[..., :3] * light
        out[..., :3] = np.where(m[..., None], shaded * alpha + out[..., :3] * (1 - alpha), out[..., :3])
    # ground line
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


def sheet(names, anim=None, t=0.0, out=None, size=300, tint=None):
    views = [(145, 18), (90, 4), (0, 8), (-140, 22)]
    rows = []
    for nm in names:
        base = nm.split(":")[0]
        texname = nm.split(":")[1] if ":" in nm else base
        layout = json.load(open(os.path.join(RIGS, base + ".json"), encoding="utf-8"))
        texpath = os.path.join(TEX, "creatures", texname + ".png")
        tex = Image.open(texpath) if os.path.exists(texpath) else Image.new("RGBA", (layout["width"], layout["height"]), (200, 0, 200, 255))
        offs = {}
        if anim:
            ap = os.path.join(ANIMS, base + ".json")
            if os.path.exists(ap):
                A = json.load(open(ap, encoding="utf-8"))
                offs = pose_offsets(A.get(anim), t)
        row = [render(layout, tex, offs, yaw, pitch, size, tint=tint) for yaw, pitch in views]
        rows.append((texname, row))
    W = size * len(views)
    img = Image.new("RGBA", (W, size * len(rows)), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    for i, (nm, row) in enumerate(rows):
        for j, r in enumerate(row):
            img.paste(r, (j * size, i * size))
        d.text((6, i * size + 4), nm + ((" [%s %.2fs]" % (anim, t)) if anim else ""), fill=(255, 255, 255, 255))
    if out:
        img.save(out)
    return img


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="+")
    ap.add_argument("--anim")
    ap.add_argument("--t", type=float, default=0.0)
    ap.add_argument("--out", default=os.path.join(ROOT, "mod", "build", "rigview.png"))
    ap.add_argument("--size", type=int, default=300)
    a = ap.parse_args()
    sheet(a.names, a.anim, a.t, a.out, a.size)
    print("wrote", a.out)
