"""A tiny software renderer for previewing the worn armour models (gear_geo) with their layer textures.

It replicates ModelPart's cube vertices and UVs, poses a mannequin in the neutral stance and draws textured quads with
a painter's algorithm, so the 3D armour can be checked without launching the game:

    python tools/textures/gear_render.py daedric out.png
"""
import math
import sys

from PIL import Image, ImageDraw

import gear_geo as geo

MANNEQUIN = {"head": ((205, 160, 130), (-4, -8, -4, 8, 8, 8)), "body": ((90, 92, 100), (-4, 0, -2, 8, 12, 4)),
             "right_arm": ((205, 160, 130), (-3, -2, -2, 4, 12, 4)), "left_arm": ((205, 160, 130), (-1, -2, -2, 4, 12, 4)),
             "right_leg": ((60, 62, 72), (-2, 0, -2, 4, 12, 4)), "left_leg": ((60, 62, 72), (-2, 0, -2, 4, 12, 4))}


def _rot(v, rx, ry, rz):
    x, y, z = v
    # X then Y then Z (ModelPart: rotationZYX applied to the vertex)
    c, s = math.cos(rx), math.sin(rx)
    y, z = y * c - z * s, y * s + z * c
    c, s = math.cos(ry), math.sin(ry)
    x, z = x * c + z * s, -x * s + z * c
    c, s = math.cos(rz), math.sin(rz)
    x, y = x * c - y * s, x * s + y * c
    return x, y, z


def cube_quads(part, frm, size, inflate, uv, mirror, pivot=(0, 0, 0), rot=(0, 0, 0)):
    """Yields (4 vertices in model space, (u0, v0, u1, v1)) per face, like ModelPart.Cube."""
    ox, oy, oz = frm
    w, h, d = size
    x1, y1, z1 = ox + w + inflate, oy + h + inflate, oz + d + inflate
    ox, oy, oz = ox - inflate, oy - inflate, oz - inflate
    if mirror:
        ox, x1 = x1, ox
    v7, v0, v1, v2 = (ox, oy, oz), (x1, oy, oz), (x1, y1, oz), (ox, y1, oz)
    v3, v4, v5, v6 = (ox, oy, z1), (x1, oy, z1), (x1, y1, z1), (ox, y1, z1)
    u, v = uv
    f4, f5, f6, f7 = u, u + d, u + d + w, u + d + w + w
    f8, f9 = u + d + w + d, u + d + w + d + w
    f10, f11, f12 = v, v + d, v + d + h
    faces = [((v4, v3, v7, v0), (f5, f10, f6, f11)), ((v1, v2, v6, v5), (f6, f11, f7, f10)),
             ((v7, v3, v6, v2), (f4, f11, f5, f12)), ((v0, v7, v2, v1), (f5, f11, f6, f12)),
             ((v4, v0, v1, v5), (f6, f11, f8, f12)), ((v3, v4, v5, v6), (f8, f11, f9, f12))]
    rx, ry, rz = (math.radians(a) for a in rot)
    ppx, ppy, ppz = geo.PART_PIVOT[part]
    for verts, (u0, vv0, u1, vv1) in faces:
        # (ModelPart assigns the UVs before reversing a mirrored polygon's winding: the x swap above is all it takes)
        out = []
        for p in verts:
            q = _rot(p, rx, ry, rz)
            out.append((q[0] + pivot[0] + ppx, q[1] + pivot[1] + ppy, q[2] + pivot[2] + ppz))
        yield out, (u0, vv0, u1, vv1)


def texel_quads(verts, uvs, tex):
    """Splits a face into one quad per texel: [(4 points, color)]."""
    u0, v0, u1, v1 = uvs
    # polygon order (MC): vertex0 -> (u1, v0), vertex1 -> (u0, v0), vertex2 -> (u0, v1), vertex3 -> (u1, v1)
    A, B, Cc = verts[1], verts[0], verts[2]   # A=(u0,v0), B=(u1,v0), C=(u0,v1)
    du, dv = u1 - u0, v1 - v0
    if du == 0 or dv == 0:
        return []
    out = []
    W, H = tex.size
    px = tex.load()
    us = sorted((u0, u1))
    vs = sorted((v0, v1))
    for tu in range(int(math.floor(us[0])), int(math.ceil(us[1]))):
        for tv in range(int(math.floor(vs[0])), int(math.ceil(vs[1]))):
            if not (0 <= tu < W and 0 <= tv < H):
                continue
            c = px[tu, tv]
            if c[3] < 8:
                continue
            pts = []
            for (uu, vv) in ((tu, tv), (tu + 1, tv), (tu + 1, tv + 1), (tu, tv + 1)):
                s = (uu - u0) / du
                t = (vv - v0) / dv
                pts.append(tuple(A[i] + s * (B[i] - A[i]) + t * (Cc[i] - A[i]) for i in range(3)))
            out.append((pts, c))
    return out


def render(built, layer1, layer2, yaw=30, pitch=8, scale=9, mannequin=True, size=(300, 340)):
    quads = []
    if mannequin:
        for part, (col, (x, y, z, w, h, d)) in MANNEQUIN.items():
            solid = Image.new("RGBA", (64, 64), col + (255,))
            for verts, uvs in cube_quads(part, (x, y, z), (w, h, d), 0, (0, 0), False):
                for pts, c in texel_quads(verts, uvs, solid):
                    quads.append((pts, c, True))
    for slot, data in built["slots"].items():
        tex = layer2 if slot == "legs" else layer1
        for part, inflate in data["base"].items():
            u, v, w, h, d = geo.BASE_UV[part]
            x, y, z, _, _, _ = geo.BASE_BOX[part]
            for verts, uvs in cube_quads(part, (x, y, z), (w, h, d), inflate, (u, v), part.startswith("left")):
                for pts, c in texel_quads(verts, uvs, tex):
                    quads.append((pts, c, False))
        for cb in data["cubes"]:
            for verts, uvs in cube_quads(cb.part, cb.frm, cb.size, cb.inflate, cb.uv, cb.mirrored, cb.pivot, cb.rot):
                for pts, c in texel_quads(verts, uvs, tex):
                    quads.append((pts, c, False))
    ya, pa = math.radians(yaw), math.radians(pitch)

    def proj(p):
        x, y, z = p
        x, z = x * math.cos(ya) - z * math.sin(ya), x * math.sin(ya) + z * math.cos(ya)
        y, z = y * math.cos(pa) - z * math.sin(pa), y * math.sin(pa) + z * math.cos(pa)
        return x, y, z

    img = Image.new("RGBA", size, (70, 74, 84, 255))
    dr = ImageDraw.Draw(img)
    cx, cy = size[0] / 2, size[1] * 0.42
    items = []
    for pts, c, flat in quads:
        pp = [proj(p) for p in pts]
        depth = sum(p[2] for p in pp) / len(pp)
        items.append((depth, pp, c, flat))
    items.sort(key=lambda it: -it[0])
    for depth, pp, c, flat in items:
        poly = [(cx + p[0] * scale, cy + p[1] * scale) for p in pp]
        col = c
        if flat:
            # light the mannequin by its face normal
            (x0, y0, _), (x1, y1, _), (x2, y2, _) = pp[0], pp[1], pp[2]
            k = 0.75 + 0.25 * (1 if (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0) > 0 else 0)
            col = (int(c[0] * k), int(c[1] * k), int(c[2] * k), 255)
        dr.polygon(poly, fill=col)
    return img


def _majority_downsample(img, n=16):
    """Downsamples a rendered icon to n x n: each output pixel takes the most common opaque colour of its block when at
    least ~40% of the block is covered."""
    from collections import Counter
    W, H = img.size
    bw, bh = W / n, H / n
    src = img.load()
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    o = out.load()
    for y in range(n):
        for x in range(n):
            cnt = Counter()
            total = 0
            for yy in range(int(y * bh), int((y + 1) * bh)):
                for xx in range(int(x * bw), int((x + 1) * bw)):
                    total += 1
                    c = src[xx, yy]
                    if c[3] > 128:
                        cnt[c[:3]] += 1
            if total and sum(cnt.values()) >= total * 0.4:
                o[x, y] = cnt.most_common(1)[0][0] + (255,)
    return out


def _outline(img):
    W, H = img.size
    px = img.load()
    src = [[px[x, y] for y in range(H)] for x in range(W)]
    for x in range(W):
        for y in range(H):
            if src[x][y][3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < W and 0 <= ny < H and src[nx][ny][3]:
                    c = src[nx][ny]
                    px[x, y] = (int(c[0] * 0.3), int(c[1] * 0.3), int(c[2] * 0.3), 255)
                    break
    return img


def icon(built, slots, layer1, layer2, yaw=-28, pitch=18, n=16, pad=1, only_parts=None):
    """Renders the cubes of `slots` alone and turns them into an n x n pixel-art icon."""
    sub = dict(built, slots={k: v for k, v in built["slots"].items() if k in slots})
    if only_parts:
        sub["slots"] = {k: dict(v, base={p: i for p, i in v["base"].items() if p in only_parts},
                                cubes=[c for c in v["cubes"] if c.part in only_parts]) for k, v in sub["slots"].items()}
    big = render(sub, layer1, layer2, yaw=yaw, pitch=pitch, scale=12, mannequin=False, size=(480, 600))
    px = big.load()
    bg = px[0, 0]
    xs, ys = [], []
    for y in range(big.size[1]):
        for x in range(big.size[0]):
            if px[x, y] != bg:
                xs.append(x)
                ys.append(y)
            else:
                px[x, y] = (0, 0, 0, 0)
    if not xs:
        return Image.new("RGBA", (n, n), (0, 0, 0, 0))
    x0, x1, y0, y1 = min(xs), max(xs) + 1, min(ys), max(ys) + 1
    side = max(x1 - x0, y1 - y0)
    inner = n - 2 * pad
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(big.crop((x0, y0, x1, y1)), ((side - (x1 - x0)) // 2, (side - (y1 - y0)) // 2))
    small = _majority_downsample(canvas.resize((inner * 8, inner * 8), Image.NEAREST), inner)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(small, (pad, pad))
    return _outline(out)


def sheet(style_id, layer1, layer2, views=((25, 8), (-35, 8), (180 + 30, 8)), scale=8):
    built = geo.build(style_id)
    imgs = [render(built, layer1, layer2, yaw=y, pitch=p, scale=scale, size=(26 * scale, 38 * scale)) for y, p in views]
    W = sum(i.size[0] for i in imgs)
    out = Image.new("RGBA", (W, imgs[0].size[1]), (0, 0, 0, 255))
    x = 0
    for i in imgs:
        out.paste(i, (x, 0))
        x += i.size[0]
    return out


if __name__ == "__main__":
    sid, path = sys.argv[1], sys.argv[2]
    import os
    root = os.path.join(os.path.dirname(__file__), "..", "..", "mod", "src", "main", "resources", "assets", "skycraft", "textures", "models", "armor")
    l1 = Image.open(os.path.join(root, f"{sid}_layer_1.png")).convert("RGBA")
    l2 = Image.open(os.path.join(root, f"{sid}_layer_2.png")).convert("RGBA")
    sheet(sid, l1, l2).save(path)
