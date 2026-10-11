"""Renders for the region audit (no Minecraft): biome map, region map, seam overlay, transect strips, contact sheet.

Usage:
  py -3 -I tools/qa/region_render.py maps      # biome_map.png, region_map.png, seams.png in tools/qa/out/regions/
  py -3 -I tools/qa/region_render.py transects [--before-root DIR]
        # transect_NN_<label>.png per worst border (8 borders taken from DIR's audit when given, else the current
        # audit.json) plus transects_sheet.png: every transect twice, left/right randomly assigned to
        # "before" (the tree at --before-root) and "after" (this tree), key in transects_key.json.

The biome pick is a numpy port of TamrielBiomeSource's palette walk: region from a domain-warped position, context
from the macro elevation/coast/river rasters, then the palette's weighted choices walked with a smooth climate value
and the palette's geographic bias. The terrain-router climate is replaced by smooth value noise with the same
spread, so this shows palette geography and seams, not individual chunks. `warp_old` reproduces the pre-audit warp.
Output goes to tools/qa/out/regions/ (gitignored).
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))


def load_tree(root):
    """All the inputs from one repo tree (so 'before' can be a git archive of HEAD)."""
    tam = os.path.join(root, "mod", "src", "main", "resources", "skycraft", "tamriel")
    data = os.path.join(root, "mod", "src", "main", "resources", "data", "skycraft", "worldgen")
    meta = json.load(open(os.path.join(root, "tools", "worldgen", "tamriel_vectors.json"), encoding="utf-8"))["meta"]
    t = {"meta": meta, "cell": meta["cell"], "min_x": meta["min_x"], "min_z": meta["min_z"]}
    t["reg"] = np.array(Image.open(os.path.join(tam, "regions.png")))
    t["elev"] = np.array(Image.open(os.path.join(tam, "elevation.png"))).astype(np.float32) / 32.0 - 64.0
    t["feat"] = np.array(Image.open(os.path.join(tam, "features.png")))
    t["ter"] = np.array(Image.open(os.path.join(tam, "terrain.png")))
    t["coast"] = np.array(Image.open(os.path.join(tam, "coast.png"))).astype(np.float32) - 32768.0
    t["regions"] = json.load(open(os.path.join(tam, "regions.json"), encoding="utf-8"))
    pj = json.load(open(os.path.join(tam, "palettes.json"), encoding="utf-8"))["palettes"]
    t["pal"] = pj
    bio = {}
    import glob
    for f in glob.glob(os.path.join(data, "biome", "*.json")):
        j = json.load(open(f, encoding="utf-8"))
        bio["skycraft:" + os.path.basename(f)[:-5]] = j
    t["biomes"] = bio
    return t


# ------------------------------------------------------------------ noise (vectorised value noise)
def _hash(ix, iz, seed):
    h = (ix.astype(np.int64) * 374761393 + iz.astype(np.int64) * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFF) / 65535.0


def vnoise(x, z, seed):
    """Value noise in [-1, 1], one lattice cell per unit."""
    x = np.asarray(x, dtype=np.float64)
    z = np.asarray(z, dtype=np.float64)
    x0, z0 = np.floor(x), np.floor(z)
    fx, fz = x - x0, z - z0
    fx, fz = fx * fx * (3 - 2 * fx), fz * fz * (3 - 2 * fz)
    a = _hash(x0, z0, seed)
    b = _hash(x0 + 1, z0, seed)
    c = _hash(x0, z0 + 1, seed)
    d = _hash(x0 + 1, z0 + 1, seed)
    return (a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz) * 2 - 1


def fbm(x, z, seed, octaves=3):
    s, amp, tot = 0.0, 1.0, 0.0
    for o in range(octaves):
        s = s + amp * vnoise(x * (2 ** o), z * (2 ** o), seed + o * 17)
        tot += amp
        amp *= 0.5
    return s / tot


# ------------------------------------------------------------------ sampling
class Sampler:
    def __init__(self, tree, warp_old=False):
        self.t = tree
        self.warp_old = warp_old
        self.cell, self.min_x, self.min_z = tree["cell"], tree["min_x"], tree["min_z"]
        self.H, self.W = tree["reg"].shape
        self.names = sorted(tree["pal"])
        self.pal = {}
        for key in tree["pal"]:
            self.pal[key] = self._resolve(key)
        self.region_pal = {}
        for r in tree["regions"]:
            for k in (r["id"], r["palette"], "province/" + r["province"], "default"):
                if k in tree["pal"]:
                    self.region_pal[r["index"]] = k
                    break
        self.biome_ids = sorted(tree["biomes"])
        self.biome_idx = {b: i for i, b in enumerate(self.biome_ids)}

    def _resolve(self, key):
        pj = self.t["pal"]

        def get(field, default):
            k = key
            while k:
                if field in pj[k]:
                    return pj[k][field]
                k = pj[k].get("fallback")
            return default

        def ctx(c):
            k = key
            while k:
                if c in pj[k]["contexts"]:
                    return pj[k]["contexts"][c]
                k = pj[k].get("fallback")
            return []
        out = {"upland": get("upland_y", 115), "peak": get("peak_y", 175), "summit": get("summit_y", 270),
               "bias": pj[key].get("bias") or {}, "ctx": {}}
        for c in ("ocean", "deep_ocean", "beach", "river", "lake", "marsh", "lowland", "upland", "peak", "summit"):
            ch = [e for e in ctx(c) if e["biome"] in self.t["biomes"]] or [{"biome": "skycraft:temperate_ocean",
                                                                              "weight": 1}]
            tot = float(sum(e["weight"] for e in ch))
            out["ctx"][c] = (np.cumsum([0.0] + [e["weight"] / tot for e in ch])[1:],
                             [self.biome_idx_of(e["biome"]) for e in ch])
        return out

    def biome_idx_of(self, b):
        if not hasattr(self, "_bi"):
            self._bi = {b2: i for i, b2 in enumerate(sorted(self.t["biomes"]))}
        return self._bi.get(b, 0)

    def cells(self, x, z):
        c = np.clip(np.floor((x - self.min_x) / self.cell).astype(int), 0, self.W - 1)
        r = np.clip(np.floor((z - self.min_z) / self.cell).astype(int), 0, self.H - 1)
        return r, c

    def bilinear(self, arr, x, z):
        fx = np.clip((x - self.min_x) / self.cell - 0.5, 0, self.W - 1.001)
        fz = np.clip((z - self.min_z) / self.cell - 0.5, 0, self.H - 1.001)
        x0, z0 = np.floor(fx).astype(int), np.floor(fz).astype(int)
        ax, az = fx - x0, fz - z0
        a = arr[z0, x0] * (1 - ax) + arr[z0, x0 + 1] * ax
        b = arr[z0 + 1, x0] * (1 - ax) + arr[z0 + 1, x0 + 1] * ax
        return a * (1 - az) + b * az

    def sample(self, x, z):
        """x, z: float arrays of block coordinates (same shape). Returns dict of arrays."""
        t = self.t
        x = np.asarray(x, dtype=np.float64)
        z = np.asarray(z, dtype=np.float64)
        qx, qz = np.floor(x / 4), np.floor(z / 4)
        j1, j2 = _hash(qx, qz, 7), _hash(qx, qz, 9)
        if self.warp_old:
            wx = fbm(x / 160.0, z / 160.0, 101) * 36 + (j1 * 15 - 7.5)
            wz = fbm(x / 160.0, z / 160.0, 202) * 36 + (j2 * 15 - 7.5)
        else:
            wx = fbm(x / 160.0, z / 160.0, 101) * 36 + vnoise(x / 40.0, z / 40.0, 111) * 24 + (j1 * 31 - 15.5) * 1.4
            wz = fbm(x / 160.0, z / 160.0, 202) * 36 + vnoise(x / 40.0, z / 40.0, 222) * 24 + (j2 * 31 - 15.5) * 1.4
        rr, rc = self.cells(x + wx, z + wz)
        region = t["reg"][rr, rc]
        r, c = self.cells(x, z)
        h = self.bilinear(t["elev"], x, z)
        coast = self.bilinear(t["coast"], x, z)
        riv = t["feat"][r, c, 0].astype(np.float32)
        lake = t["feat"][r, c, 2].astype(np.float32)
        wet = t["ter"][r, c, 3].astype(np.float32)
        ridge = t["feat"][r, c, 1].astype(np.float32)
        jit = np.round(vnoise(x / 48.0, z / 48.0, 404) * 10 + (_hash(qx, qz, 7) * 8 - 4))
        hj = h + jit
        # smooth climate value in [0, 1): mixed low-frequency noise, spread like the router's humidity/temperature
        n = 0.55 * vnoise(x / 900.0, z / 900.0, 31) + 0.3 * vnoise(x / 1400.0, z / 1400.0, 47) + \
            0.45 * vnoise(x / 320.0, z / 320.0, 303)
        v = 0.5 * (1 + np.vectorize(math.erf)(n / 0.22 / math.sqrt(2) * 0.55))
        out = np.zeros(x.shape, dtype=np.int32)
        ctx_name = np.empty(x.shape, dtype=object)
        sea = coast < 0
        low = h < 63 + 4
        ctx = np.full(x.shape, "lowland", dtype=object)
        # order mirrors TamrielBiomeSource.context()
        up = (hj >= 0)  # placeholder, replaced per palette below
        ctx[sea & (h < 62)] = "ocean"
        ctx[sea & (h < 36)] = "deep_ocean"
        land_low = (~sea) & low
        ctx[land_low & (wet > 140) & (h < 66)] = "marsh"
        ctx[land_low & (coast < 12 + (jit.astype(int) & 7))] = "beach"
        ctx[land_low & (riv > 115)] = "river"
        ctx[land_low & (lake > 140)] = "lake"
        out_ctx = ctx
        for ri in np.unique(region):
            key = self.region_pal.get(int(ri), "default")
            p = self.pal[key]
            m = region == ri
            # height contexts for this palette
            cm = out_ctx.copy()
            hh = hj
            land_hi = m & (~sea) & ~np.isin(out_ctx, ["beach", "river", "lake", "marsh"])
            cm[land_hi & (hh >= p["upland"])] = "upland"
            cm[land_hi & (ridge > 153) & (hh >= p["upland"] - 25)] = "upland"
            cm[land_hi & (hh >= p["peak"])] = "peak"
            cm[land_hi & (hh >= p["summit"])] = "summit"
            vv = v.copy()
            b = p["bias"]
            if "linear" in b:
                lx, lz, dx, dz, span, shift = b["linear"]
                vv = vv + shift * np.clip(((x - lx) * dx + (z - lz) * dz) / span, -1, 1)
            if "radial" in b:
                rx, rz, inner, outer, scale = b["radial"]
                tt = np.clip((np.hypot(x - rx, z - rz) - inner) / max(outer - inner, 1.0), 0, 1)
                vv = vv * (scale + (1 - scale) * tt * tt * (3 - 2 * tt))
            vv = np.clip(vv, 0, 0.99999)
            for cname in np.unique(cm[m]):
                mm = m & (cm == cname)
                edges, ids = p["ctx"][cname]
                k = np.minimum(np.searchsorted(edges, vv[mm], side="right"), len(ids) - 1)
                out[mm] = np.array(ids)[k]
        return {"biome": out, "region": region, "h": h, "ctx": out_ctx, "coast": coast}


# ------------------------------------------------------------------ colours and classes
def biome_colour(biomes, bid):
    j = biomes[bid]
    e = j.get("effects", {})
    col = e.get("grass_color")
    n = bid.split(":")[1]
    if "ocean" in n or n in ("coral_sea", "sea_of_ghosts", "inner_sea"):
        col = e.get("water_color", 0x3040A0)
    elif col is None:
        col = e.get("foliage_color", e.get("fog_color", 0x808080))
    return ((col >> 16) & 255, (col >> 8) & 255, col & 255)


def veg_class(biomes, bid):
    """0 water, 1 bare/rock/ice, 2 grass/tundra, 3 scrub/savanna, 4 conifer/boreal, 5 broadleaf/temperate forest,
    6 jungle/rain, 7 swamp/marsh, 8 desert, 9 ash/volcanic, 10 magical."""
    j = biomes[bid]
    n = bid.split(":")[1]
    t, d = j["temperature"], j["downfall"]
    if any(w in n for w in ("ocean", "sea", "river", "lake")) or n in ("coral_sea", "inner_sea"):
        return 0
    if any(w in n for w in ("marsh", "swamp", "mire", "fen", "mangrove", "reedlands", "delta", "shadowfen")):
        return 7
    if any(w in n for w in ("ashlands", "red_mountain", "molag", "stonefalls", "lava", "ash_shore", "deadlands")):
        return 9
    if any(w in n for w in ("crystal", "artaeum", "summerset", "mushroom", "mania", "dementia")):
        return 10
    if t >= 1.4 and d <= 0.3 or n in ("alikr_desert", "alikr_dunes", "yokudan_desert", "elsweyr_dunes"):
        return 8
    if t >= 0.95 and d >= 0.85:
        return 6
    if any(w in n for w in ("peak", "slopes", "throat", "glacier", "ice", "crags", "mountains", "canyons",
                            "badlands", "craglorn", "rock", "shore", "beach")):
        return 1
    if t <= 0.45 and d >= 0.55 or "pine" in n or "snowy" in n:
        return 4
    if "forest" in n or "weald" in n or d >= 0.7:
        return 5
    if t >= 1.1 or "savanna" in n or "steppe" in n:
        return 3
    return 2


VEG_COLOURS = [(40, 80, 200), (150, 150, 150), (170, 200, 90), (205, 190, 90), (40, 110, 80), (50, 150, 50),
               (10, 120, 60), (90, 120, 100), (235, 215, 140), (110, 90, 90), (200, 120, 220)]
VEG_NAMES = ["water", "bare/rock/ice", "grass/tundra", "scrub/savanna", "conifer", "broadleaf", "jungle", "swamp",
             "desert", "ash/volcanic", "magical"]


def regions_colour(i):
    rnd = random.Random(i * 7919 + 13)
    return tuple(rnd.randint(70, 235) for _ in range(3))


# ------------------------------------------------------------------ maps
def render_maps(tree, audit, outdir, tag=""):
    s = Sampler(tree)
    H, W, cell = s.H, s.W, s.cell
    xs = s.min_x + (np.arange(W) + 0.5) * cell
    zs = s.min_z + (np.arange(H) + 0.5) * cell
    X, Z = np.meshgrid(xs, zs)
    res = s.sample(X.ravel(), Z.ravel())
    biome = res["biome"].reshape(H, W)
    lut = np.array([biome_colour(tree["biomes"], b) for b in s.biome_ids], dtype=np.uint8)
    img = lut[biome]
    # light hillshade so mountains read
    h = tree["elev"]
    gy, gx = np.gradient(h)
    shade = np.clip(1 + (gx - gy) * 0.012, 0.75, 1.25)
    img = np.clip(img * shade[..., None], 0, 255).astype(np.uint8)
    Image.fromarray(img).save(os.path.join(outdir, "biome_map%s.png" % tag))
    reg = tree["reg"]
    rl = np.array([regions_colour(i) for i in range(256)], dtype=np.uint8)
    rim = rl[reg]
    Image.fromarray(rim).save(os.path.join(outdir, "region_map%s.png" % tag))
    return biome, reg


def seam_overlay(tree, audit, outdir, biome_img_path, n=15, tag=""):
    reg = tree["reg"]
    base = Image.open(biome_img_path).convert("RGB")
    arr = np.array(base)
    idx = {r["id"]: r["index"] for r in tree["regions"]}
    for rank, b in enumerate(audit["borders"][:n]):
        ia, ib = idx[b["a"]], idx[b["b"]]
        col = (255, 0, 0) if rank < 5 else (255, 140, 0) if rank < 10 else (255, 235, 0)
        for axis in (0, 1):
            a, bb = (reg[:, :-1], reg[:, 1:]) if axis == 0 else (reg[:-1, :], reg[1:, :])
            m = ((a == ia) & (bb == ib)) | ((a == ib) & (bb == ia))
            rr, cc = np.nonzero(m)
            for r, c in zip(rr, cc):
                if axis == 0:
                    arr[r, c:c + 2] = col
                else:
                    arr[r:r + 2, c] = col
    im = Image.fromarray(arr).resize((arr.shape[1] * 2, arr.shape[0] * 2), Image.NEAREST)
    d = ImageDraw.Draw(im)
    for rank, b in enumerate(audit["borders"][:n]):
        x = (b["at"][0] - tree["min_x"]) / tree["cell"] * 2
        z = (b["at"][1] - tree["min_z"]) / tree["cell"] * 2
        d.text((x + 3, z - 5), str(rank + 1), fill=(255, 255, 255))
    im.save(os.path.join(outdir, "seams%s.png" % tag))


# ------------------------------------------------------------------ transects
def border_normal(tree, b):
    """Unit vector (x, z) from region a to region b near the border's reference point, from the raster edges."""
    reg = tree["reg"]
    idx = {r["id"]: r["index"] for r in tree["regions"]}
    ia, ib = idx[b["a"]], idx[b["b"]]
    cx = (b["at"][0] - tree["min_x"]) / tree["cell"]
    cz = (b["at"][1] - tree["min_z"]) / tree["cell"]
    sx = sz = 0.0
    for axis in (0, 1):
        a, bb = (reg[:, :-1], reg[:, 1:]) if axis == 0 else (reg[:-1, :], reg[1:, :])
        for first, second, sign in ((ia, ib, 1), (ib, ia, -1)):
            rr, cc = np.nonzero((a == first) & (bb == second))
            for r, c in zip(rr, cc):
                if abs(c - cx) < 14 and abs(r - cz) < 14:
                    if axis == 0:
                        sx += sign
                    else:
                        sz += sign
    n = math.hypot(sx, sz)
    return (sx / n, sz / n) if n else (1.0, 0.0)


def render_transect(tree, b, length=2400, width=400, step=4, scale=2, warp_old=False):
    s = Sampler(tree, warp_old=warp_old)
    nx, nz = border_normal(tree, b)
    cx, cz = b["at"]
    tx, tz = -nz, nx  # along-border direction
    n_len, n_wid = length // step, width // step
    u = (np.arange(n_len) - n_len / 2 + 0.5) * step
    w = (np.arange(n_wid) - n_wid / 2 + 0.5) * step
    U, Wd = np.meshgrid(u, w)
    X = cx + U * nx + Wd * tx
    Z = cz + U * nz + Wd * tz
    r = s.sample(X.ravel(), Z.ravel())
    biome = r["biome"].reshape(U.shape)
    h = r["h"].reshape(U.shape)
    lut = np.array([biome_colour(tree["biomes"], bb) for bb in s.biome_ids], dtype=np.uint8)
    vlut = np.array([veg_class(tree["biomes"], bb) for bb in s.biome_ids], dtype=np.int32)
    vcol = np.array(VEG_COLOURS, dtype=np.uint8)
    p_biome = lut[biome]
    dlut = np.array([tree["biomes"][bb]["downfall"] for bb in s.biome_ids], dtype=np.float32)
    tlut = np.array([tree["biomes"][bb]["temperature"] for bb in s.biome_ids], dtype=np.float32)
    mod = (0.7 + 0.5 * dlut[biome] - 0.08 * np.clip(tlut[biome], -1, 2))[..., None]
    p_veg = np.clip(vcol[vlut[biome]] * mod, 0, 255).astype(np.uint8)
    hn = np.clip((h - 40) / 280.0, 0, 1)
    gy, gx = np.gradient(h)
    sh = np.clip(0.5 + (gx - gy) * 0.02, 0, 1)
    g = (np.clip(0.55 * hn + 0.45 * sh, 0, 1) * 255).astype(np.uint8)
    p_h = np.stack([g, g, g], -1)
    # sea level contour tint
    p_h[h < 63] = (60, 90, 170)
    panels = [Image.fromarray(p).resize((n_len * scale, n_wid * scale), Image.NEAREST) for p in (p_biome, p_h, p_veg)]
    gap = 4
    out = Image.new("RGB", (n_len * scale, 3 * n_wid * scale + 2 * gap), (20, 20, 20))
    for i, p in enumerate(panels):
        out.paste(p, (0, i * (n_wid * scale + gap)))
    d = ImageDraw.Draw(out)
    mid = n_len * scale // 2
    d.line([(mid, 0), (mid, out.size[1])], fill=(255, 0, 0), width=1)
    # tick every 400 blocks
    for k in range(0, n_len, 400 // step):
        d.line([(k * scale, 0), (k * scale, 6)], fill=(255, 255, 255))
    return out


def label(img, text):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 8 * len(text) + 8, 14], fill=(0, 0, 0))
    d.text((4, 1), text, fill=(255, 255, 255))
    return img


def cmd_transects(args):
    outdir = os.path.join(HERE, "out", "regions")
    os.makedirs(outdir, exist_ok=True)
    after_root = os.path.abspath(os.path.join(HERE, "..", ".."))
    before_root = None
    if "--before-root" in args:
        before_root = args[args.index("--before-root") + 1]
    n = 8
    audit_path = os.path.join(before_root, "audit_before.json") if before_root else os.path.join(outdir, "audit.json")
    if "--audit" in args:
        audit_path = args[args.index("--audit") + 1]
    audit = json.load(open(audit_path, encoding="utf-8"))
    after = load_tree(after_root)
    before = load_tree(before_root) if before_root else None
    borders = audit["borders"]
    # prefer distinct region pairs, long-ish borders
    pick = [b for b in borders if b["length_blocks"] >= 1000][:n]
    key = []
    tiles = []
    rnd = random.Random(7)
    for k, b in enumerate(pick):
        name = "%02d_%s__%s" % (k + 1, b["a"].replace("/", "-"), b["b"].replace("/", "-"))
        imgs = {}
        imgs["after"] = render_transect(after, b)
        if before is not None:
            imgs["before"] = render_transect(before, b, warp_old=True)
        for tag, im in imgs.items():
            im.save(os.path.join(outdir, "transect_%s_%s.png" % (name, tag)))
        if before is not None:
            order = ["before", "after"]
            rnd.shuffle(order)
            key.append({"n": k + 1, "left": order[0], "right": order[1], "a": b["a"], "b": b["b"], "at": b["at"],
                        "seam_before": b["seam"]})
            tile = Image.new("RGB", (imgs["after"].size[0] * 2 + 12, imgs["after"].size[1] + 18), (0, 0, 0))
            tile.paste(imgs[order[0]], (0, 18))
            tile.paste(imgs[order[1]], (imgs["after"].size[0] + 12, 18))
            label(tile, "T%d  L=left  R=right   (red line = region border; rows: biome colours / height / vegetation class)"
                  % (k + 1))
            tile.save(os.path.join(outdir, "pair_%02d.png" % (k + 1)))
            tiles.append(tile)
        else:
            tiles.append(label(imgs["after"].copy(), "T%d %s | %s" % (k + 1, b["a"], b["b"])))
    if tiles:
        w, h = tiles[0].size
        sheet = Image.new("RGB", (w, h * len(tiles) + 4 * len(tiles)), (0, 0, 0))
        for i, t in enumerate(tiles):
            sheet.paste(t, (0, i * (h + 4)))
        sheet.save(os.path.join(outdir, "transects_sheet.png"))
        # legend strip
        leg = Image.new("RGB", (900, 22), (255, 255, 255))
        d = ImageDraw.Draw(leg)
        for i, (c, nme) in enumerate(zip(VEG_COLOURS, VEG_NAMES)):
            d.rectangle([4 + i * 80, 4, 16 + i * 80, 16], fill=c)
            d.text((20 + i * 80, 5), nme[:9], fill=(0, 0, 0))
        leg.save(os.path.join(outdir, "legend.png"))
    json.dump(key, open(os.path.join(outdir, "transects_key.json"), "w"), indent=1)
    print("wrote %d transects" % len(pick))


def cmd_maps(args):
    outdir = os.path.join(HERE, "out", "regions")
    os.makedirs(outdir, exist_ok=True)
    root = os.path.abspath(os.path.join(HERE, "..", ".."))
    tree = load_tree(root)
    audit = json.load(open(os.path.join(outdir, "audit.json"), encoding="utf-8"))
    render_maps(tree, audit, outdir)
    # the flagged seams: the worst 15 from the audit given with --audit (e.g. the pre-fix one), else the current one
    flagged = audit
    if "--audit" in args:
        flagged = json.load(open(args[args.index("--audit") + 1], encoding="utf-8"))
    seam_overlay(tree, flagged, outdir, os.path.join(outdir, "biome_map.png"))
    seam_overlay(tree, audit, outdir, os.path.join(outdir, "biome_map.png"), tag="_remaining")
    print("maps written to", outdir)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "maps"
    {"maps": cmd_maps, "transects": cmd_transects}[cmd](sys.argv[2:])
