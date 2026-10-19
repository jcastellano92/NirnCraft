"""Builds the Tamriel map rasters from the hand-authored vectors in tamriel_vectors.json.

Usage: py -3 tools/worldgen/tamriel_map.py [--no-preview]

Writes to mod/src/main/resources/nirncraft/tamriel/ (all rasters are CELL = 32 blocks per pixel, pixel (0, 0) is the
cell whose north-west corner is (MIN_X, MIN_Z)):

* regions.png   8-bit indexed: region index into regions.json (0 = sea/open_ocean).
* elevation.png 16-bit grey: macro surface height, value = round((y + 64) * 32). Lakes hold their bed height.
* features.png  RGBA: R = river proximity (255 - 2 * blocks to the nearest river centre line), G = mountain ridge
                strength, B = lake signed distance (128 + 2 * blocks inside the shore), A = road corridor
                (255 - 4 * blocks to the road centre line).
* terrain.png   RGBA terrain character, 0..255: R = roughness (hill amplitude), G = crags/cliffs, B = dunes,
                A = wetland.
* water.png     RGBA: R,G = water surface of the nearest lake/river, ((y + 64) * 64) big-endian, 0 = none;
                B = river half width in blocks; A = flags (1 = lake nearby, 2 = river nearby).
* coast.png     16-bit grey: signed distance to the macro coastline in blocks + 32768 (positive on land).
* regions.json  the region table (index = position).

and tools/worldgen/preview_*.png (relief + borders + anchors; Skyrim close-up) for checking the result by eye.
Needs numpy and Pillow; uses scipy for exact distance transforms when it is installed (otherwise a slower chamfer
fallback with the same results to within a few blocks).
"""
import json
import math
import os
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

try:
    from scipy import ndimage as _ndi
except ImportError:  # pragma: no cover
    _ndi = None

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)  # `py -3 -I` does not add the script's folder
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
import hydrology as hy  # noqa: E402  (tributaries, lake basins, deltas, waterfalls)
import hydrology_build as hb  # noqa: E402
RES_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "nirncraft", "tamriel")
OUT_DIR = os.environ.get("TAMRIEL_OUT") or RES_DIR  # rasters go here (set TAMRIEL_OUT to experiment without touching the resources)
PLACES = os.path.join(RES_DIR, "places", "provinces.json")
SKYRIM_SETTLEMENTS = os.path.join(RES_DIR, "places", "skyrim_settlements.json")

V = json.load(open(os.path.join(HERE, "tamriel_vectors.json"), encoding="utf-8"))
M = V["meta"]
CELL = M["cell"]
MIN_X, MIN_Z = M["min_x"], M["min_z"]
W, H = M["width_cells"], M["height_cells"]
SEA = M["sea_level"]
FINE = 4  # supersampling for coast/lake shapes
FRES = CELL / FINE

XS = MIN_X + (np.arange(W) + 0.5) * CELL
ZS = MIN_Z + (np.arange(H) + 0.5) * CELL
GX, GZ = np.meshgrid(XS, ZS)


# ---------------------------------------------------------------------------------------------------------------------
# helpers
def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def _hash(ix, iz, seed):
    h = (ix.astype(np.int64) * 374761393 + iz.astype(np.int64) * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFFFF) / float(0xFFFFFF) * 2.0 - 1.0


def vnoise(x, z, scale, seed):
    fx, fz = x / scale, z / scale
    ix, iz = np.floor(fx), np.floor(fz)
    tx, tz = fx - ix, fz - iz
    tx = tx * tx * (3 - 2 * tx)
    tz = tz * tz * (3 - 2 * tz)
    ix, iz = ix.astype(np.int64), iz.astype(np.int64)
    a = _hash(ix, iz, seed)
    b = _hash(ix + 1, iz, seed)
    c = _hash(ix, iz + 1, seed)
    d = _hash(ix + 1, iz + 1, seed)
    return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz


def fbm(x, z, scale, octaves, seed, gain=0.5):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        tot = tot + vnoise(x, z, scale / (2 ** o), seed + o * 31) * amp
        norm += amp
        amp *= gain
    return tot / norm


def ridged(x, z, scale, octaves, seed):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        n = 1.0 - np.abs(vnoise(x, z, scale / (2 ** o), seed + o * 17))
        tot = tot + n * n * amp
        norm += amp
        amp *= 0.5
    return tot / norm


def _grad(ix, iz, seed):
    h = (ix.astype(np.int64) * 374761393 + iz.astype(np.int64) * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    a = ((h ^ (h >> 16)) & 0xFFFF) / 65536.0 * 2 * math.pi
    return np.cos(a), np.sin(a)


GN_SCALE = 2.1  # brings gradient noise to about the spread of vnoise, so the authored amplitudes keep their meaning


def gnoise(x, z, scale, seed):
    """Gradient (Perlin-style) noise in about [-1, 1] with quintic fades: no value-noise lattice blobs."""
    fx, fz = x / scale, z / scale
    ix, iz = np.floor(fx), np.floor(fz)
    tx, tz = fx - ix, fz - iz
    ix, iz = ix.astype(np.int64), iz.astype(np.int64)

    def corner(dx, dz):
        gx, gz = _grad(ix + dx, iz + dz, seed)
        return gx * (tx - dx) + gz * (tz - dz)

    ux = tx * tx * tx * (tx * (tx * 6 - 15) + 10)
    uz = tz * tz * tz * (tz * (tz * 6 - 15) + 10)
    a, b, c, d = corner(0, 0), corner(1, 0), corner(0, 1), corner(1, 1)
    return np.clip(((a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uz) * GN_SCALE, -1, 1)


def _octave(x, z, o, seed):
    """Each octave rotated and shifted, so octaves never line up into a grid."""
    ang = 0.61 + o * 1.17 + seed * 0.37
    ca, sa = math.cos(ang), math.sin(ang)
    return x * ca - z * sa + o * 1371.3 + seed * 17.7, x * sa + z * ca - o * 713.9 - seed * 31.1


def gfbm(x, z, scale, octaves, seed, gain=0.5, lac=2.03):
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        ox, oz = _octave(x, z, o, seed)
        tot = tot + gnoise(ox, oz, scale / lac ** o, seed + o * 31) * amp
        norm += amp
        amp *= gain
    return tot / norm


def gridged(x, z, scale, octaves, seed, lac=2.03):
    """Ridged multifractal in [0, 1]: sharp crests, each finer octave weighted by the crest signal of the coarser
    ones, so detail gathers on the ridges and the valleys between stay smooth (as erosion leaves them)."""
    tot, norm, weight, amp = 0.0, 0.0, 1.0, 1.0
    for o in range(octaves):
        ox, oz = _octave(x, z, o, seed)
        s = (1.0 - np.abs(gnoise(ox, oz, scale / lac ** o, seed + o * 17))) ** 2
        s = s * weight
        weight = np.clip(s * 1.6, 0, 1)
        tot = tot + s * amp
        norm += amp
        amp *= 0.5
    return tot / norm


def box_blur(a, r):
    """Box blur with radius r cells (edge-clamped), via cumulative sums."""
    if r <= 0:
        return a
    p = np.pad(a, r, mode="edge")
    c = np.cumsum(np.cumsum(p, 0), 1)
    c = np.pad(c, ((1, 0), (1, 0)))
    k = 2 * r + 1
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def blur(a, r):
    for _ in range(3):
        a = box_blur(a, r)
    return a


def raster_polys(polys, res, w, h, value=1):
    img = Image.new("L", (w, h), 0)
    dr = ImageDraw.Draw(img)
    for poly in polys:
        pts = [((x - MIN_X) / res - 0.5, (z - MIN_Z) / res - 0.5) for x, z in poly]
        if len(pts) >= 3:
            dr.polygon(pts, fill=value)
    return np.array(img) > 0


def _chamfer_nearest(seed):
    """Nearest seed pixel for every pixel (two-pass chamfer propagation). Returns (rows, cols)."""
    hh, ww = seed.shape
    big = 1e9
    d = np.where(seed, 0.0, big)
    idx = np.where(seed, np.arange(hh * ww).reshape(hh, ww), -1)
    cols = np.arange(ww)
    s2 = math.sqrt(2)

    def scan_row(dr, ir, reverse):
        if reverse:
            dr, ir = dr[::-1], ir[::-1]
        key = dr - cols
        run = np.minimum.accumulate(key)
        pos = np.where(key <= run, cols, 0)
        pos = np.maximum.accumulate(pos)
        nd = run + cols
        better = nd < dr
        dr = np.where(better, nd, dr)
        ir = np.where(better, ir[pos], ir)
        if reverse:
            dr, ir = dr[::-1], ir[::-1]
        return dr, ir

    for order in (range(hh), range(hh - 1, -1, -1)):
        prev = None
        for r in order:
            dr, ir = d[r], idx[r]
            if prev is not None:
                pd, pi = prev
                for shift, cost in ((0, 1.0), (1, s2), (-1, s2)):
                    cd = np.full(ww, big)
                    ci = np.full(ww, -1)
                    if shift == 0:
                        cd[:], ci[:] = pd + cost, pi
                    elif shift == 1:
                        cd[1:], ci[1:] = pd[:-1] + cost, pi[:-1]
                    else:
                        cd[:-1], ci[:-1] = pd[1:] + cost, pi[1:]
                    b = cd < dr
                    dr = np.where(b, cd, dr)
                    ir = np.where(b, ci, ir)
            dr, ir = scan_row(dr, ir, False)
            dr, ir = scan_row(dr, ir, True)
            d[r], idx[r] = dr, ir
            prev = (dr, ir)
    idx = np.where(idx < 0, 0, idx)
    return idx // ww, idx % ww


def nearest(seed):
    """(rows, cols) of the nearest True pixel of seed, for every pixel."""
    if _ndi is not None:
        _, (r, c) = _ndi.distance_transform_edt(~seed, return_indices=True)
        return r, c
    return _chamfer_nearest(seed)


def dist_to(seed):
    """Euclidean distance (pixels) to the nearest True pixel."""
    if _ndi is not None:
        return _ndi.distance_transform_edt(~seed)
    r, c = _chamfer_nearest(seed)
    rr, cc = np.indices(seed.shape)
    return np.hypot(rr - r, cc - c)


def signed_dist(mask, res):
    """Signed distance in blocks to the mask boundary, positive inside."""
    return (dist_to(~mask) - dist_to(mask)) * res + np.where(mask, -0.5, 0.5) * res


def polyline_field(line, radius, extra=None, gx=None, gz=None):
    """Distance to a polyline over the coarse grid within radius. Returns (dist, t-param, seg-interp of extra)."""
    GXa = GX if gx is None else gx
    GZa = GZ if gz is None else gz
    pts = np.array([p[:2] for p in line], float)
    best = np.full(GX.shape, np.inf)
    tpar = np.zeros(GX.shape)
    ext = np.zeros(GX.shape) if extra is not None else None
    seglen = np.hypot(*(pts[1:] - pts[:-1]).T)
    cum = np.concatenate([[0], np.cumsum(seglen)])
    total = max(cum[-1], 1e-6)
    for i in range(len(pts) - 1):
        (x0, z0), (x1, z1) = pts[i], pts[i + 1]
        lo_x, hi_x = min(x0, x1) - radius, max(x0, x1) + radius
        lo_z, hi_z = min(z0, z1) - radius, max(z0, z1) + radius
        i0 = max(0, int((lo_x - MIN_X) / CELL))
        i1 = min(W, int((hi_x - MIN_X) / CELL) + 2)
        j0 = max(0, int((lo_z - MIN_Z) / CELL))
        j1 = min(H, int((hi_z - MIN_Z) / CELL) + 2)
        if i0 >= i1 or j0 >= j1:
            continue
        sx, sz = GXa[j0:j1, i0:i1], GZa[j0:j1, i0:i1]
        dx, dz = x1 - x0, z1 - z0
        l2 = dx * dx + dz * dz
        u = np.clip(((sx - x0) * dx + (sz - z0) * dz) / max(l2, 1e-6), 0, 1)
        d = np.hypot(sx - (x0 + u * dx), sz - (z0 + u * dz))
        sub = best[j0:j1, i0:i1]
        m = d < sub
        sub[m] = d[m]
        tpar[j0:j1, i0:i1][m] = ((cum[i] + u * seglen[i]) / total)[m]
        if ext is not None:
            ev = extra[i] + (extra[i + 1] - extra[i]) * u
            ext[j0:j1, i0:i1][m] = ev[m]
    return best, tpar, ext


# ---------------------------------------------------------------------------------------------------------------------
def anchor_list():
    by_id = {}
    for p in json.load(open(PLACES, encoding="utf-8")):
        by_id[p["id"]] = (p["id"], p["region"], p["x"], p["z"], p.get("radius", 150))
    for pid, (rid, x, z) in ILIAC_ANCHORS.items():
        rad = by_id[pid][4] if pid in by_id else 150
        by_id[pid] = (pid, rid, x, z, rad)
    out = list(by_id.values())
    for pid, (rid, x, z) in SKYRIM_ANCHORS.items():
        out.append((pid, rid, x, z, 140))
    return out


# How flat the ground under each kind of settlement is made (1 = the macro surface is levelled to the local mean
# inside the settlement's radius); the hill/crag detail fades by the same weight.
PAD_STRENGTH = {"capital": 1.0, "city": 1.0, "town": 0.9, "village": 0.7, "farm": 0.55}


def settlement_pads():
    """(id, x, z, radius, strength) of every settlement in the places files (Skyrim's at their real positions)."""
    out = {}
    for f in (PLACES, SKYRIM_SETTLEMENTS):
        for p in json.load(open(f, encoding="utf-8")):
            s = PAD_STRENGTH.get(p["type"])
            if s:
                pid = p["id"]
                _, x, z = ILIAC_ANCHORS.get(pid, (p.get("region"), p["x"], p["z"]))
                out[pid] = (pid, x, z, p.get("radius", 120), s)
    return list(out.values())


def warp_fields(gx, gz, scale, amp, seed):
    return (gx + fbm(gx, gz, scale, 3, seed) * amp, gz + fbm(gx, gz, scale, 3, seed + 500) * amp)


def build():
    regions = V["regions"]
    rindex = {r["id"]: i for i, r in enumerate(regions)}
    assert len(regions) < 256
    anchors_ = anchor_list()

    # --- land and coast distance -----------------------------------------------------------------------------------
    print("land mask")
    fw, fh = W * FINE, H * FINE
    land_f = raster_polys([l["poly"] for l in V["land"]], FRES, fw, fh)
    coast_f = signed_dist(land_f, FRES)
    # fractal coastline detail: noise on the signed distance, damped where the coast is already traced (Skyrim,
    # Solstheim), around small islands and around coastal towns so anchors stay put
    fx = MIN_X + (np.arange(fw) + 0.5) * FRES
    fz = MIN_Z + (np.arange(fh) + 0.5) * FRES
    fgx, fgz = np.meshgrid(fx.astype(np.float32), fz.astype(np.float32))
    amp = np.ones((fh, fw), np.float32)
    for poly in V.get("coast_detail_keep", []):
        amp[raster_polys([poly], FRES, fw, fh)] = 0.15
    small = [l["poly"] for l in V["land"] if poly_area(l["poly"]) < 4.0e6]
    sm = raster_polys(small, FRES, fw, fh)
    amp = np.minimum(amp, np.where(dist_to(sm) * FRES < 500, 0.3, 1.0))
    for pid, rid, x, z, rad in anchors_:
        if pid not in COASTAL and pid not in ISLANDS:
            continue
        dd = np.hypot(fgx - x, fgz - z)
        amp = np.minimum(amp, smoothstep(rad + 120, rad + 700, dd).astype(np.float32))
    n = (fbm(fgx, fgz, 2200, 2, 3) * 0.45 + fbm(fgx, fgz, 700, 2, 7) * 0.35 + fbm(fgx, fgz, 220, 2, 9) * 0.2)
    land_f = (coast_f + n * 1100 * amp) > 0
    del fgx, fgz, n
    coast_f = signed_dist(land_f, FRES)
    D = coast_f[FINE // 2::FINE, FINE // 2::FINE][:H, :W].copy()
    land = D > 0

    # --- regions ---------------------------------------------------------------------------------------------------
    print("regions")
    reg = np.full((H, W), -1, np.int32)
    masks = {k: raster_polys([p], CELL, W, H) for k, p in V["masks"].items()}
    land_regions = [r for r in regions if r["kind"] != "sea" and r["polys"]]
    for r in sorted(land_regions, key=lambda r: r.get("priority", 1)):
        m = raster_polys(r["polys"], CELL, W, H)
        if "mask" in r:
            m &= masks[r["mask"]]
        reg[m] = rindex[r["id"]]
    has = reg >= 0
    rr, cc = nearest(has)
    reg = np.where(has, reg, reg[rr, cc])
    sky = masks["skyrim"]
    is_sky = np.isin(reg, [rindex[r["id"]] for r in regions if r["province"] == "skyrim"])
    bad = is_sky & ~sky
    if bad.any():
        ok = ~is_sky & (reg >= 0)
        rr, cc = nearest(ok)
        reg[bad] = reg[rr, cc][bad]
    # organic borders: resample the political map through a domain warp (Java adds a finer one)
    wx, wz = warp_fields(GX, GZ, 1400, 260, 101)
    ri = np.clip(((wx - MIN_X) / CELL).astype(int), 0, W - 1)
    rj = np.clip(((wz - MIN_Z) / CELL).astype(int), 0, H - 1)
    reg = reg[rj, ri]
    for ov in V.get("region_overrides", []):
        reg[raster_polys([ov["poly"]], CELL, W, H)] = rindex[ov["id"]]
    # seas: polygons, else nearest seed
    sea_reg = np.full((H, W), -1, np.int32)
    best = np.full((H, W), np.inf)
    for name, pts in V["sea_seeds"].items():
        for sx, sz in pts:
            d = np.hypot(wx - sx, wz - sz)
            m = d < best
            best[m] = d[m]
            sea_reg[m] = rindex[name]
    for r in regions:
        if r["kind"] == "sea" and r["polys"]:
            sea_reg[raster_polys(r["polys"], CELL, W, H)[rj, ri]] = rindex[r["id"]]
    reg = np.where(land, reg, sea_reg).astype(np.int32)

    # --- region terrain properties -----------------------------------------------------------------------------------
    print("terrain properties")
    # base, relief, rough, crag, dunes, wet, coast ramp, plus the optional hills (amplitude of the rolling hill field in
    # blocks; default 24 x rough) and cliff (0..1: steep coasts with a narrow beach)
    area_keys = ["base", "relief", "rough", "crag", "dunes", "wet", "coast", "hills", "cliff"]
    props = np.zeros((len(area_keys), H, W))
    default = [90, 10, 0.35, 0.1, 0, 0, 200]

    def full(t):
        t = list(t)
        return t + [24.0 * t[2], 0.0][len(t) - 7:]

    table = np.array([full(r.get("terrain", default)) for r in regions], float)
    props[:] = table[reg].transpose(2, 0, 1)
    for a in V["areas"]:
        m = raster_polys([a["poly"]], CELL, W, H)[rj, ri]
        for k, v in a.items():
            if k in area_keys:
                props[area_keys.index(k)][m] = v
    landf = land.astype(float)
    wsum = blur(landf, 6) + 1e-6
    for k in range(len(area_keys)):
        props[k] = blur(props[k] * landf, 6) / wsum
    base, relief, rough, crag, dunes, wet, ramp, hamp, cliff = props

    # --- elevation ---------------------------------------------------------------------------------------------------
    print("elevation")
    # (gradient noise with rotated octaves throughout: value noise and aligned octaves left lattice patterns)
    big = gfbm(GX, GZ, 2600, 4, 11)
    mid = gfbm(GX, GZ, 900, 4, 23)
    hills = gridged(GX, GZ, 1500, 4, 29) - 0.35
    B = base + relief * (0.55 * big + 0.45 * mid) + rough * (26 * hills + 8 * gfbm(GX, GZ, 380, 3, 31))
    # rolling hills and dales a few hundred blocks across: ridged crests (spurs and knolls) over a smooth swell
    dales = 0.6 * (gridged(GX, GZ, 640, 4, 61) - 0.38) * 2.6 + 0.4 * gfbm(GX, GZ, 280, 3, 62) * 1.8
    B = B + hamp * dales
    # continuous across the coast: land rises from sea level, the sea floor falls from it
    # (linear in D within 64 blocks of the coast, so interpolating the 32-block raster gives a smooth shoreline);
    # cliff coasts have a narrow strand and rise fast
    bw = 48 - 36 * cliff
    t = smoothstep(bw, bw + np.maximum(ramp * 1.8, 60) * (1 - 0.7 * cliff), D)
    beach = SEA + 0.05 * np.minimum(D, 64)
    hland = beach + (np.maximum(B, beach) - beach) * t
    hsea = SEA - sea_depth(np.maximum(-D, 0)) + 5 * gfbm(GX, GZ, 700, 3, 5) * smoothstep(64, 400, -D)
    h = np.where(D > 0, hland, hsea)

    # roads first: where a road crosses a range the range dips into a pass
    road_d = np.full((H, W), 1e5)
    for rd in V["roads"]:
        d, _, _ = polyline_field(rd["line"], 600)
        road_d = np.minimum(road_d, np.where(np.isfinite(d), d, 1e5))
    passes = 1 - 0.65 * (1 - smoothstep(80, 560, road_d))
    for ps in V.get("passes", []):
        passes = np.minimum(passes, 1 - ps.get("depth", 0.5) * (1 - smoothstep(ps["radius"] * 0.3, ps["radius"],
                                                                                 np.hypot(GX - ps["x"], GZ - ps["z"]))))

    ridge = np.zeros((H, W))
    rwx, rwz = warp_fields(GX, GZ, 1100, 320, 202)
    # ridged multifractal crests: spurs branching off the main ridgelines, smooth valleys between
    rn = np.clip(1.2 * (0.65 * gridged(GX, GZ, 650, 4, 77) + 0.35 * gridged(GX, GZ, 260, 3, 78)), 0, 1)
    wmod = 0.75 + 0.5 * (gfbm(GX, GZ, 1800, 3, 79) * 0.5 + 0.5)
    hvar = gfbm(GX, GZ, 800, 3, 80)
    for rg in V["ranges"]:
        half = rg["width"] / 2.0
        d, _, _ = polyline_field(rg["line"], half * 1.3 + 350, gx=rwx, gz=rwz)
        p = 1 - smoothstep(0, half * wmod, d)
        if not p.any():
            continue
        hl = rg["height"] * (0.88 + 0.12 * hvar)
        # jagged ranges: sharper crests (rn squared) and individual summits standing above the crest line
        shape = p ** 1.25 * (0.3 + 0.7 * rn ** rg.get("jag", 1.0))
        lift = np.maximum(hl - h, 0) * shape * (passes if rg.get("passes", True) else 1)
        h = h + lift * smoothstep(0, 150, D)
        ridge = np.maximum(ridge, p * (0.5 + 0.5 * rn))
    for pk in V["peaks"]:
        if pk.get("sharp"):
            # a horn peak is not in the raster: Java's MapPeaks adds it at full resolution (see sharp_lift)
            ridge = np.maximum(ridge, 0.9 * (1 - smoothstep(0, pk["radius"], np.hypot(GX - pk["x"], GZ - pk["z"]))))
            continue
        r = np.hypot(GX - pk["x"], GZ - pk["z"])
        # a caldera volcano keeps its full height out to the crater rim
        p = 1 - smoothstep(0, pk["radius"], np.maximum(r - pk.get("caldera", 0), 0))
        q = p ** 4
        shape = p ** 1.6 * ((0.75 + 0.25 * rn) * (1 - q) + q)
        h = h + np.maximum(pk["height"] - h, 0) * shape * smoothstep(0, 150, D)
        if pk["height"] > 200:
            ridge = np.maximum(ridge, p * 0.9)
        if pk.get("caldera"):
            h = h - pk["caldera_depth"] * np.clip(1 - (r / pk["caldera"]) ** 2, 0, 1) ** 0.5

    # --- erosion: drainage valleys and gullies cut by the run-off, talus slopes below the crags ------------------------
    print("erosion")
    lake_near = dist_to(raster_polys([lk["poly"] for lk in V["lakes"]], CELL, W, H)) * CELL < 220
    keep = (smoothstep(60, 220, D) * (1 - smoothstep(0.2, 0.5, wet)) * ~lake_near).astype(float)
    h = erode(h, keep)

    # wetlands sit just around sea level so low spots become pools
    wl = SEA + 0.7 + 1.7 * gfbm(GX, GZ, 160, 3, 41)
    wv = smoothstep(0.2, 0.6, wet) * land
    # settlements get dry, level ground
    pad = np.zeros((H, W))
    for pid, rid, x, z, rad in anchors_:
        pad = np.maximum(pad, 1 - smoothstep(rad * 0.8, rad * 2.0, np.hypot(GX - x, GZ - z)))
    wv = wv * (1 - pad)
    h = h + (wl - h) * wv * smoothstep(SEA + 45, SEA + 20, h)
    h = np.where((D > 64) & (pad > 0), np.maximum(h, SEA + 1 + 2.5 * pad), h)
    # ... and towns stand on buildable ground: level the macro surface to its local mean, fading out past the walls
    flat = np.zeros((H, W))
    h0 = h.copy()
    for pid, x, z, rad, s in settlement_pads():
        dd = np.hypot(GX - x, GZ - z)
        disc = (dd < rad * 1.2 + 16) & land
        if not disc.any():
            continue
        target = max(float(h0[disc].mean()), SEA + 2.0)
        w = s * (1 - smoothstep(rad * 0.9, rad * 1.9 + 32, dd))
        h = h + (target - h) * w * land
        flat = np.maximum(flat, w)

    # --- hydrology: where places stand, and closed depressions that deserve to be lakes -------------------------------
    print("hydrology: lake basins")
    grid = hy.Grid(MIN_X, MIN_Z, W, H)
    excl = hb.place_exclusion(grid)
    near_auth = dist_to(raster_polys([lk["poly"] for lk in V["lakes"]], CELL, W, H)) * CELL < 220
    rd0 = np.full((H, W), 1e5)
    for rv in V["rivers"]:
        d0, _, _ = polyline_field(rv["line"], 400)
        rd0 = np.minimum(rd0, np.where(np.isfinite(d0), d0, 1e5))
    near_auth = near_auth | (rd0 < 260)
    pit_lab, pit_specs = hy.find_pits(grid, h, land, D, wet, near_auth, excl)
    n_auth_lakes = len(V["lakes"])
    basin_lakes = [dict(name="basin_%d" % s["id"], level=round(s["level"], 1), depth=round(s["depth"], 1), poly=[],
                        cx=s["cx"], cz=s["cz"], cells=s["cells"], basin=True) for s in pit_specs]
    all_lakes = list(V["lakes"]) + basin_lakes

    # --- lakes -----------------------------------------------------------------------------------------------------
    print("lakes")
    lake_mask_f = np.zeros((fh, fw), bool)
    lake_id_f = np.zeros((fh, fw), np.int32)
    for i, lk in enumerate(V["lakes"]):
        m = raster_polys([lk["poly"]], FRES, fw, fh)
        if lk.get("islands"):
            m &= ~raster_polys(lk["islands"], FRES, fw, fh)
        lake_mask_f |= m
        lake_id_f[m] = i
    if basin_lakes:
        # basin lakes come from cell masks: upsample, round the corners with a blur and threshold
        bl = np.kron(pit_lab, np.ones((FINE, FINE), np.int32))
        bm = blur((bl > 0).astype(np.float32), FINE) > 0.5
        bm &= ~lake_mask_f
        bid = np.where(bm, bl, 0)
        if bm.any():
            rr0, cc0 = nearest(bl > 0)
            bid = np.where(bm, bl[rr0, cc0], 0)
        for j in range(len(basin_lakes)):
            lake_id_f[bm & (bid == j + 1)] = n_auth_lakes + j
        lake_mask_f |= bm
        del bl, bm, bid
    lsd_f = signed_dist(lake_mask_f, FRES)
    rr, cc = nearest(lake_mask_f)
    lid = lake_id_f[rr, cc]
    lake_sd = lsd_f[FINE // 2::FINE, FINE // 2::FINE][:H, :W]
    lid = lid[FINE // 2::FINE, FINE // 2::FINE][:H, :W]
    levels = np.array([lk["level"] for lk in all_lakes], float)
    depths = np.array([lk.get("depth", 8) for lk in all_lakes], float)
    lake_level = levels[lid]
    is_auth = lid < n_auth_lakes
    near_lake = (lake_sd > -260) & is_auth  # basin lakes sit in natural hollows: no shore guard or height cap around them
    guard = lake_level + 1.6
    shore = 1 - smoothstep(0, 220, -lake_sd)
    h_l = np.maximum(h, h + (guard - h) * shore)
    h_l = np.minimum(h_l, guard + np.maximum(-lake_sd, 0) * 0.45)
    bed = lake_level - np.minimum(depths[lid], 1.5 + np.maximum(lake_sd, 0) * 0.22)
    h = np.where(near_lake & land, np.where(lake_sd > 0, bed, h_l), h)
    h = np.where(~is_auth & (lake_sd > 0) & land, np.minimum(h, bed), h)
    for lk in V["lakes"]:
        for isl in lk.get("islands", []):
            m = raster_polys([isl], CELL, W, H)
            h[m] = np.maximum(h[m], lk["level"] + 7)

    # --- rivers ----------------------------------------------------------------------------------------------------
    print("rivers")
    riv_d = np.full((H, W), 1e5)
    riv_level = np.zeros((H, W))
    riv_hw = np.zeros((H, W))
    auth_lines = []   # dense authored centre lines for the tributary snap, the deltas and the river sites
    falls_all = []
    for rv in V["rivers"]:
        line = river_profile(rv, h)
        width = rv["width"]
        # waterfalls where the authored profile already drops steeply: the drop is squared into one step
        larr = np.array(line)
        dp, dl, _ = hy.densify(larr[:, :2], larr[:, 2], np.full(len(larr), width / 2.0), 16.0)
        if width <= 16:
            dl, fl = hy.add_falls(dp, dl, np.full(len(dp), width / 2.0), "river", hy.FALL_MIN_RIVER, [], rv.get("id", ""))
            falls_all += fl
            line = [[float(p[0]), float(p[1]), float(l)] for p, l in zip(dp, dl)]
        lv = [p[2] for p in line]
        hwa = (width + (rv.get("mouth_width", width) - width) * np.linspace(0, 1, len(line)) ** 3) / 2.0
        auth_lines.append(dict(name=rv.get("name", rv.get("id", "river")), pts=np.array([p[:2] for p in line]),
                               lev=np.array(lv), hw=hwa, width=width))
        mw = rv.get("mouth_width", width)
        valley = rv.get("valley", 90 + width * 4)
        d, tp, lev = polyline_field(line, valley * 2.5 + 80, lv)
        hw = (width + (mw - width) * tp ** 3) / 2.0
        sel = np.isfinite(d) & land
        d = np.where(np.isfinite(d), d, 1e5)
        floor = lev + 1.5 + np.maximum(d - hw - 6, 0) * 0.12
        wv_ = 1 - smoothstep(hw, valley * 2.5, d)
        hn = np.minimum(h, h + (floor - h) * wv_)
        hn = np.where(d < hw + 14, np.maximum(hn, lev + 1.4), hn)
        h = np.where(sel, hn, h)
        m = d < riv_d
        riv_d[m] = d[m]
        riv_level[m] = lev[m]
        riv_hw[m] = hw[m]

    # --- hydrology: tributaries, lake outlets, deltas, waterfalls ---------------------------------------------------
    print("hydrology: streams")
    hyd = hb.integrate(grid, h, land, D, wet, reg, regions, ridge, dunes, riv_d, riv_hw, riv_level, lake_sd,
                          levels[lid], excl, auth_lines, falls_all,
                          delta_names={"niben_river", "onkobra_river", "karth_river", "strid_river", "xylo_river"})
    h = hyd["h"]
    hyd["basin_lakes"] = basin_lakes
    riv_d, riv_hw, riv_level = hyd["riv_d"], hyd["riv_hw"], hyd["riv_level"]

    h = np.clip(h, -40, 568)

    # --- sharp peaks (drawn by Java on top of the raster; previews and checks use h_view) --------------------------
    # the road channel as Java sees it: road corridors and settlement pads
    roadw = np.clip(np.maximum(1 - road_d / 63.75, flat), 0, 1)
    sharp = []
    h_view = h.copy()
    for pk in V["peaks"]:
        if not pk.get("sharp"):
            continue
        pk = dict(pk)
        shelves = []
        for sx, sz, sr in pk["sharp"].get("shelves", []):
            b = float(bil(h, sx, sz))
            shelves.append([sx, sz, sr, round(float(sharp_core(sx, sz, pk, b, bil(roadw, sx, sz))), 1)])
        pk["_shelves"] = shelves

        def surface(x, z, pk=pk):
            b = float(bil(h, x, z))
            return b + float(sharp_lift(x, z, pk, b, bil(roadw, x, z)))

        # paths climb evenly from their foot to their end (a shelf, or the summit)
        pk["_paths"] = [graded_path(p, surface, surface(*p[0]), surface(*p[-1])) for p in pk["sharp"].get("paths", [])]
        h_view = h_view + sharp_lift(GX, GZ, pk, h, roadw)
        sharp.append(pk)

    # --- terrain character -----------------------------------------------------------------------------------------
    # (settlement pads keep a little of the seeded detail; the settlement builders level the rest)
    ridge = ridge * (1 - 0.8 * flat)
    rough_t = np.clip(rough + ridge * 0.45, 0, 1) * (1 - 0.6 * flat)
    crag_t = np.clip(crag + ridge * 0.6 * smoothstep(110, 200, h), 0, 1) * (1 - 0.85 * flat)
    dunes_t = np.clip(dunes, 0, 1) * land
    wet_t = wv

    # --- rainshadow & geothermal heat (MS21 micro-biome drivers) ---------------------------------------------------
    print("rainshadow & geothermal")
    crest = h + ridge * 90.0
    winds_cfg = V.get("winds", {})
    default_winds = winds_cfg.get("default", [[1.0, 0.0]])
    yy, xx = np.indices((H, W), dtype=np.float32)
    rainshadow = np.zeros((H, W), np.float32)
    wind_groups = {}
    for i, r in enumerate(regions):
        wkey = tuple(tuple(w) for w in winds_cfg.get(r["id"], default_winds))
        wind_groups.setdefault(wkey, []).append(i)
    for wlist, rindices in wind_groups.items():
        acc = np.zeros((H, W), np.float32)
        for wx, wz in wlist:
            wacc = np.zeros((H, W), np.float32)
            for k in range(8, 49, 2):
                ux = np.clip(np.round(xx - k * wx).astype(int), 0, W - 1)
                uz = np.clip(np.round(yy - k * wz).astype(int), 0, H - 1)
                dh = np.where(land[uz, ux], np.maximum(crest[uz, ux] - h, 0.0), 0.0)
                rw = (0.3 + 0.7 * ridge[uz, ux]) * (1.0 - (k - 8) / 48.0)
                wacc += (dh * rw) / 21.0
            acc = np.maximum(acc, wacc / 48.0)
        m = np.isin(reg, rindices)
        rainshadow[m] = acc[m]
    rainshadow = np.clip(blur(rainshadow * landf, 2) / (blur(landf, 2) + 1e-6), 0.0, 1.0) * land

    heat = np.zeros((H, W), np.float32)
    for geo in V.get("geothermal", []):
        hval = float(geo.get("heat", 0.5))
        if "poly" in geo:
            pm = raster_polys([geo["poly"]], CELL, W, H)
            falloff = float(geo.get("falloff", 200.0))
            gd = dist_to(pm) * CELL
            p = 1.0 - smoothstep(0.0, falloff, gd)
            heat = np.maximum(heat, hval * p)
        else:
            gr = np.hypot(GX - float(geo["x"]), GZ - float(geo["z"]))
            p = 1.0 - smoothstep(0.0, float(geo.get("radius", 220.0)), gr)
            heat = np.maximum(heat, hval * (p ** 1.15))
    heat = np.clip(blur(heat, 1), 0.0, 1.0) * land

    # --- water raster -----------------------------------------------------------------------------------------------
    use_lake = lake_sd > -64
    use_riv = (riv_d < 220) & ~use_lake
    wlevel = np.where(use_lake, lake_level, np.where(use_riv, riv_level, 0))
    wflags = (lake_sd > -260).astype(np.uint8) | ((riv_d < 220).astype(np.uint8) << 1)
    wlevel = np.where((wlevel == 0) & (lake_sd > -260), lake_level, wlevel)
    wl_code = np.where(wlevel > 0, np.round((wlevel + 64) * 64), 0).astype(np.uint32)

    print("hydrology: river sites")
    sites, site_counts = hb.plan_sites(grid, h, D, lake_sd, riv_d, riv_hw, hyd["auth_lines"], hyd["net"].lines,
                                       hyd["falls"], hyd["springs"], hb.load_places(), V["roads"], basin_lakes,
                                       V["lakes"], excl)
    hyd["sites"], hyd["site_counts"] = sites, site_counts

    return dict(regions=regions, reg=reg, h=h, D=D, land=land, ridge=ridge, rough=rough_t, crag=crag_t,
                dunes=dunes_t, wet=wet_t, rainshadow=rainshadow, heat=heat, lake_sd=lake_sd, riv_d=riv_d,
                riv_hw=riv_hw, road_d=road_d, wl_code=wl_code, wflags=wflags, wlevel=wlevel, h_view=h_view,
                sharp=sharp, flat=flat, roadw=roadw, hyd=hyd)


D8 = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def erode(h, weight, iters=6, k=0.55, cap=9.0, talus=0.95):
    """A macro hydraulic + thermal erosion pass at raster resolution (32-block cells). Each round routes the run-off
    downhill (D8), cuts every cell by stream power (k * sqrt(upstream area) * slope, at most `cap` blocks) and lets
    slopes steeper than `talus` (blocks per block) slump. Big catchments cut dendritic valleys that feed the rivers,
    small ones leave gullies down the mountain flanks, and the plains (no slope) barely change. `weight` (0..1) keeps
    the coasts, lakes and marshes as authored."""
    hh, ww = h.shape
    rows, cols = np.indices(h.shape)
    flat_idx = rows * ww + cols
    h = h.copy()
    h0 = h.copy()
    for _ in range(iters):
        p = np.pad(h, 1, mode="edge")
        best = np.zeros_like(h)
        rec = flat_idx.copy()
        drop_max = np.zeros_like(h)
        for dz, dx in D8:
            nb = p[1 + dz:1 + dz + hh, 1 + dx:1 + dx + ww]
            dd = CELL * (math.sqrt(2) if dz and dx else 1.0)
            s = (h - nb) / dd
            m = s > best
            best = np.where(m, s, best)
            rr = np.clip(rows + dz, 0, hh - 1)
            cc = np.clip(cols + dx, 0, ww - 1)
            rec = np.where(m, rr * ww + cc, rec)
            drop_max = np.maximum(drop_max, (h - nb) - talus * dd)
        # upstream area, highest cells first
        area = np.ones(hh * ww)
        recf = rec.ravel()
        for i in np.argsort(-h.ravel(), kind="stable"):
            r = recf[i]
            if r != i:
                area[r] += area[i]
        cut = np.minimum(cap, k * np.sqrt(area.reshape(h.shape)) * best)
        slump = np.maximum(drop_max, 0) * 0.3
        h = h - (cut + slump) * weight
    # widen the cuts a little: valleys, not slots
    eroded = h0 - h
    return h0 - (0.6 * eroded + 0.4 * box_blur(eroded, 1))


def peak_profile(x, z, pk):
    """0..1 shape of a sharp (horn) peak: 1 at the summit, 0 past its foot. Its reach varies with the bearing, longest
    along the authored spurs (aretes), so the contours bulge out into ridges. MapPeaks.java computes the same."""
    s = pk["sharp"]
    dx, dz = np.asarray(x, float) - pk["x"], np.asarray(z, float) - pk["z"]
    r = np.hypot(dx, dz)
    th = np.arctan2(dz, dx)
    reach = s["reach"] * (1 + 0.06 * (0.5 * np.sin(3 * th + 1.3) + 0.3 * np.sin(7 * th + 0.4) + 0.2 * np.sin(11 * th + 2.1)))
    for ang, width, gain in s["spurs"]:
        da = np.degrees(np.angle(np.exp(1j * (th - np.radians(ang)))))
        reach = reach * (1 + gain * np.exp(-(da / width) ** 2))
    # (1 - u^shape)^power: shape 1 is a horn with concave flanks, larger shapes a massif with steep lower walls
    u = np.clip(r / reach, 0, 1)
    return (1 - u ** s.get("shape", 1.0)) ** s["power"]


def sharp_core(x, z, pk, base, road=0.0):
    """A sharp peak's surface over terrain of height `base` before its shelves and paths: the massif (damped in road
    corridors and settlement pads, which stay passes and level ground), clear zones, and the summit horn."""
    s = pk["sharp"]
    x, z = np.asarray(x, float), np.asarray(z, float)
    prof = peak_profile(x, z, pk) * (1 - 0.85 * np.asarray(road, float))
    # settlements at the foot keep their own ground
    for cx, cz, cr in s.get("clear", []):
        prof = prof * smoothstep(cr * 0.8, cr + 60, np.hypot(x - cx, z - cz))
    hh, hr = s.get("horn", [0, 1])
    y = base + np.maximum(pk["height"] - hh - base, 0) * prof
    return y + hh * np.clip(1 - np.hypot(x - pk["x"], z - pk["z"]) / hr, 0, 1) ** 1.5


def sharp_lift(x, z, pk, base, road=0.0):
    """How far a sharp peak raises terrain of height `base` at (x, z), shelves included (MapPeaks.java; the graded
    paths are only a few blocks wide and only drawn there)."""
    y = sharp_core(x, z, pk, base, road)
    for sx, sz, sr, sy in pk.get("_shelves", []):
        w = 1 - smoothstep(sr, sr + 40, np.hypot(np.asarray(x, float) - sx, np.asarray(z, float) - sz))
        y = y + (sy - y) * w
    return y - base


def graded_path(path, surface, ystart, yend, step=8.0, grade=0.6):
    """A climbing path resampled every `step` blocks with its levels: as close to the ground as possible while never
    descending and never climbing more than `grade` blocks per block, from ystart to yend. Java levels a ledge along
    it (cut into the slope or built out from it, with talus banks), so the climb stays walkable."""
    pts = np.array(path, float)
    out = [pts[0]]
    for a, b in zip(pts[:-1], pts[1:]):
        n = max(1, int(math.ceil(np.hypot(*(b - a)) / step)))
        out += [a + (b - a) * (k / n) for k in range(1, n + 1)]
    out = np.array(out)
    ds = np.concatenate([[0], np.hypot(*(out[1:] - out[:-1]).T)])
    ground = np.array([surface(x, z) for x, z in out])
    y = ground.copy()
    y[0], y[-1] = ystart, yend
    for i in range(1, len(y)):  # never down, at most `grade` up
        y[i] = min(max(y[i], y[i - 1]), y[i - 1] + grade * ds[i])
    y[-1] = yend
    for i in range(len(y) - 2, -1, -1):  # and still arrive: pull the climb forward where the end needs it
        y[i] = max(min(y[i], y[i + 1]), y[i + 1] - grade * ds[i + 1])
    y[0] = ystart
    return [[round(float(x), 1), round(float(z), 1), round(float(v), 1)] for (x, z), v in zip(out, y)]


def sea_depth(d):
    """Depth below sea level at d blocks from the coast (Beyond.java uses the same curve)."""
    return 0.05 * np.minimum(d, 64) + 75 * (1 - np.exp(-np.maximum(d - 64, 0) / 1300.0))


def river_profile(rv, h, step=32.0):
    """Densifies a river and sets its water level: the authored levels are upper bounds, the level follows the
    terrain 2.5 blocks down, never rises downstream, and stays between the authored mouth and source levels (so rivers
    leave and enter lakes at the lake's level)."""
    pts = rv["line"]
    out = []
    for (x0, z0, l0), (x1, z1, l1) in zip(pts[:-1], pts[1:]):
        n = max(1, int(math.hypot(x1 - x0, z1 - z0) / step))
        for k in range(n):
            t = k / n
            out.append([x0 + (x1 - x0) * t, z0 + (z1 - z0) * t, l0 + (l1 - l0) * t])
    out.append(list(pts[-1]))
    arr = np.array(out)
    terr = bil(h, arr[:, 0], arr[:, 1])
    lo, hi = pts[-1][2], pts[0][2]
    lev = np.clip(np.minimum(arr[:, 2], terr - 2.5), lo, hi)
    lev = np.minimum.accumulate(lev)
    lev[-1] = lo
    arr[:, 2] = lev
    return arr.tolist()


def poly_area(p):
    a = 0.0
    for i in range(len(p)):
        x0, z0 = p[i]
        x1, z1 = p[(i + 1) % len(p)]
        a += x0 * z1 - x1 * z0
    return abs(a) / 2


# ---------------------------------------------------------------------------------------------------------------------
PROV_HUE = {"skyrim": (120, 150, 200), "cyrodiil": (200, 170, 90), "morrowind": (170, 90, 70),
            "high_rock": (110, 160, 110), "hammerfell": (215, 150, 80), "black_marsh": (90, 130, 90),
            "elsweyr": (220, 190, 110), "valenwood": (70, 150, 70), "summerset": (200, 200, 140),
            "atmora": (220, 230, 240), "akavir": (180, 80, 120), "yokuda": (230, 200, 130), "pyandonea": (80, 160, 160),
            "thras": (120, 200, 200), "sea": (40, 70, 140)}


def region_palette(regions):
    pal = []
    for i, r in enumerate(regions):
        c = PROV_HUE.get(r["province"], (128, 128, 128))
        j = (i * 37) % 7 - 3
        pal.extend([max(0, min(255, v + j * 12)) for v in c])
    pal.extend([0] * (768 - len(pal)))
    return pal


def write_assets(o):
    os.makedirs(OUT_DIR, exist_ok=True)
    regions = o["regions"]
    img = Image.fromarray(o["reg"].astype(np.uint8), "P")
    img.putpalette(region_palette(regions))
    img.save(os.path.join(OUT_DIR, "regions.png"), optimize=True)

    elev = np.clip(np.round((o["h"] + 64) * 32), 0, 65535).astype(np.uint16)
    Image.fromarray(elev).save(os.path.join(OUT_DIR, "elevation.png"))  # PIL writes uint16 as 16-bit grey

    feat = np.zeros((H, W, 4), np.uint8)
    feat[..., 0] = np.clip(255 - 2 * o["riv_d"], 0, 255)
    feat[..., 1] = np.clip(o["ridge"] * 255, 0, 255)
    feat[..., 2] = np.clip(128 + 2 * o["lake_sd"], 0, 255)
    # the road corridor channel also covers settlement pads: both calm the seeded detail and keep cave mouths shut
    feat[..., 3] = np.clip(np.maximum(255 - 4 * o["road_d"], o["flat"] * 255), 0, 255)
    Image.fromarray(feat, "RGBA").save(os.path.join(OUT_DIR, "features.png"))

    ter = np.zeros((H, W, 4), np.uint8)
    for k, key in enumerate(("rough", "crag", "dunes", "wet")):
        ter[..., k] = np.clip(np.round(o[key] * 255), 0, 255)
    Image.fromarray(ter, "RGBA").save(os.path.join(OUT_DIR, "terrain.png"))

    wat = np.zeros((H, W, 4), np.uint8)
    wat[..., 0] = (o["wl_code"] >> 8) & 255
    wat[..., 1] = o["wl_code"] & 255
    wat[..., 2] = np.clip(np.round(np.where(o["riv_d"] < 220, o["riv_hw"], 0)), 0, 255)
    wat[..., 3] = o["wflags"]
    Image.fromarray(wat, "RGBA").save(os.path.join(OUT_DIR, "water.png"))

    hy.write_json(os.path.join(OUT_DIR, "hydrology.json"), hb.hydrology_json(o["hyd"], o["hyd"]["sites"],
                                                                             o["hyd"]["site_counts"]))
    coast = np.clip(np.round(o["D"]) + 32768, 0, 65535).astype(np.uint16)
    Image.fromarray(coast).save(os.path.join(OUT_DIR, "coast.png"))

    rs_u8 = np.clip(np.round(o["rainshadow"] * 255), 0, 255).astype(np.uint8)
    Image.fromarray(rs_u8, "L").save(os.path.join(OUT_DIR, "rainshadow.png"))

    heat_u8 = np.clip(np.round(o["heat"] * 255), 0, 255).astype(np.uint8)
    Image.fromarray(heat_u8, "L").save(os.path.join(OUT_DIR, "heat.png"))

    # sharp peaks for MapPeaks.java: the profile parameters, shelves with their levelled height, calm path corridors
    peaks = []
    for pk in o["sharp"]:
        s = pk["sharp"]
        peaks.append({"name": pk["name"], "x": pk["x"], "z": pk["z"], "height": pk["height"], "reach": s["reach"],
                      "shape": s.get("shape", 1.0), "power": s["power"], "horn": s.get("horn", [0, 1]),
                      "spurs": s["spurs"], "shelves": pk["_shelves"], "clear": s.get("clear", []),
                      "paths": pk["_paths"], "jag": s.get("jag", 20)})
    with open(os.path.join(OUT_DIR, "peaks.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"_comment": "Written by tools/worldgen/tamriel_map.py from the sharp peaks in tamriel_vectors.json",
                   "peaks": peaks}, f, indent=1)
        f.write("\n")

    table = []
    for i, r in enumerate(regions):
        prov, short = r["id"].split("/")
        e = {"index": i, "id": r["id"], "province": prov, "kind": r["kind"], "culture": r["culture"],
             "name": f"region.nirncraft.{prov}.{short}", "levels": r["levels"], "palette": r["id"],
             "music": prov, "weather": r["weather"]}
        if r.get("capital"):
            e["capital"] = r["capital"]
        if r.get("realm_banner"):
            e["realm_banner"] = r["realm_banner"]
        if r.get("terrain"):
            e["terrain"] = dict(zip(["base", "relief", "rough", "crag", "dunes", "wet", "coast"], r["terrain"]))
        if r.get("procedural"):
            e["procedural"] = True
        table.append(e)
    with open(os.path.join(OUT_DIR, "regions.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write("[\n" + ",\n".join(" " + json.dumps(e) for e in table) + "\n]\n")

    lang = {e["name"]: REGION_NAMES.get(e["id"], e["id"].split("/")[1].replace("_", " ").title()) for e in table}
    lang["region.nirncraft.realm.generated"] = "%s"
    lang.update(RIVER_NAMES)
    with open(LANG, "w", encoding="utf-8", newline="\n") as f:
        json.dump(dict(sorted(lang.items())), f, indent=2, ensure_ascii=False)
        f.write("\n")


LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft", "tamriel_regions.json")
RIVER_NAMES = {
    "river.nirncraft.daggerfall_river": "Ilessan Water",
    "river.nirncraft.anticlere_river": "Maran Water",
    "river.nirncraft.dwynnen_river": "Karstbeck",
    "river.nirncraft.bjoulsae_river": "Bjoulsae River",
    "river.nirncraft.sentinel_wadi": "Wadi Tigonus",
    "river.nirncraft.lainlyn_river": "Lainlyn Run",
    "river.nirncraft.totambu_river": "Totambu River",
}
REGION_NAMES = {
    "sea/open_ocean": "The Open Sea", "sea/sea_of_ghosts": "Sea of Ghosts", "sea/abecean_sea": "Abecean Sea",
    "sea/iliac_bay": "Iliac Bay", "sea/topal_bay": "Topal Bay", "sea/inner_sea": "Inner Sea",
    "sea/padomaic_ocean": "Padomaic Ocean", "sea/eltheric_ocean": "Eltheric Ocean",
    "skyrim/whiterun": "Whiterun Hold", "skyrim/falkreath": "Falkreath Hold", "skyrim/the_rift": "The Rift",
    "skyrim/eastmarch": "Eastmarch", "skyrim/the_pale": "The Pale", "skyrim/winterhold": "Winterhold",
    "skyrim/hjaalmarch": "Hjaalmarch", "skyrim/haafingar": "Haafingar", "skyrim/the_reach": "The Reach",
    "cyrodiil/jerall_mountains": "Jerall Mountains", "cyrodiil/colovian_highlands": "Colovian Highlands",
    "cyrodiil/gold_coast": "Gold Coast", "cyrodiil/west_weald": "West Weald", "cyrodiil/heartlands": "Heartlands",
    "cyrodiil/great_forest": "Great Forest", "cyrodiil/nibenay_basin": "Nibenay Basin",
    "cyrodiil/nibenay_valley": "Nibenay Valley", "cyrodiil/blackwood": "Blackwood",
    "morrowind/solstheim": "Solstheim", "morrowind/vvardenfell_ashlands": "Ashlands",
    "morrowind/red_mountain": "Red Mountain", "morrowind/bitter_coast": "Bitter Coast", "morrowind/west_gash": "West Gash",
    "morrowind/ascadian_isles": "Ascadian Isles", "morrowind/grazelands": "Grazelands", "morrowind/molag_amur": "Molag Amur",
    "morrowind/azuras_coast": "Azura's Coast", "morrowind/sheogorad": "Sheogorad", "morrowind/telvannis": "Telvannis",
    "morrowind/deshaan": "Deshaan", "morrowind/stonefalls": "Stonefalls",
    "high_rock/daggerfall": "Daggerfall", "high_rock/anticlere": "Anticlere", "high_rock/dwynnen": "Dwynnen",
    "high_rock/glenumbra": "Glenumbra", "high_rock/stormhaven": "Stormhaven", "high_rock/rivenspire": "Rivenspire",
    "high_rock/wrothgar": "Wrothgar", "high_rock/wayrest": "Wayrest", "high_rock/bjoulsae": "Bjoulsae River",
    "hammerfell/sentinel": "Sentinel", "hammerfell/alikr_desert": "Alik'r Desert", "hammerfell/bangkorai": "Bangkorai",
    "hammerfell/craglorn": "Craglorn", "hammerfell/stros_mkai": "Stros M'Kai", "hammerfell/hews_bane": "Hew's Bane",
    "hammerfell/dragontail": "Dragontail Mountains",
    "black_marsh/shadowfen": "Shadowfen", "black_marsh/murkmire": "Murkmire", "black_marsh/gideon": "Gideon",
    "black_marsh/stormhold": "Stormhold", "black_marsh/helstrom": "Helstrom", "black_marsh/lilmoth": "Lilmoth",
    "elsweyr/anequina": "Anequina", "elsweyr/rimmen": "Rimmen", "elsweyr/torval": "Torval", "elsweyr/pellitine": "Pellitine",
    "elsweyr/senchal": "Senchal",
    "valenwood/grahtwood": "Grahtwood", "valenwood/greenshade": "Greenshade", "valenwood/malabal_tor": "Malabal Tor",
    "valenwood/reapers_march": "Reaper's March",
    "summerset/summerset_isle": "Summerset Isle", "summerset/auridon": "Auridon", "summerset/artaeum": "Artaeum",
    "atmora/ice_waste": "Atmoran Ice Waste", "atmora/frozen_forest": "Atmoran Frozen Forest",
    "akavir/tsaesci": "Tsaesci", "akavir/kamal": "Kamal", "akavir/tang_mo": "Tang Mo", "akavir/ka_po_tun": "Ka Po' Tun",
    "yokuda/yokudan_desert": "Yokudan Desert", "yokuda/sunken_isles": "Sunken Isles of Yokuda",
    "pyandonea/maormer_isles": "Pyandonea", "thras/coral_kingdom": "Coral Kingdom of Thras",
}


# ---------------------------------------------------------------------------------------------------------------------
SKYRIM_ANCHORS = {  # from the reference markers (Whiterun = 0, 0)
    "whiterun": ("skyrim/whiterun", 0, 0), "solitude": ("skyrim/haafingar", -1350, -1778),
    "windhelm": ("skyrim/eastmarch", 1829, -686), "riften": ("skyrim/the_rift", 2437, 1400),
    "markarth": ("skyrim/the_reach", -3075, -203), "winterhold": ("skyrim/winterhold", 1414, -1753),
    "falkreath": ("skyrim/falkreath", -798, 1246), "morthal": ("skyrim/hjaalmarch", -924, -1185),
    "dawnstar": ("skyrim/the_pale", 172, -1806), "riverwood": ("skyrim/whiterun", 33, 593),
    "helgen": ("skyrim/falkreath", -24, 1143), "ivarstead": ("skyrim/the_rift", 896, 901),
    "rorikstead": ("skyrim/whiterun", -1652, -228), "dragon_bridge": ("skyrim/haafingar", -1934, -1432),
    "karthwasten": ("skyrim/the_reach", -2420, -729), "kynesgrove": ("skyrim/eastmarch", 1825, -298),
    "shors_stone": ("skyrim/the_rift", 2269, 842), "throat_of_the_world": ("skyrim/whiterun", 590, 650),
}
ILIAC_ANCHORS = {  # IB1 binding anchor updates (TAMRIEL.md section 9)
    "daggerfall": ("high_rock/daggerfall", -9300, 2600),
    "sentinel": ("hammerfell/sentinel", -7976, 4900),
    "elinhir": ("hammerfell/craglorn", -3950, 3800),
}
COASTAL = {"anvil", "leyawiin", "vivec", "sadrith_mora", "necrom", "raven_rock", "wayrest", "daggerfall", "sentinel",
           "taneth", "lilmoth", "senchal", "woodhearth", "alinor", "firsthold", "solitude", "dawnstar", "winterhold"}
ON_RIVER = {"dragon_bridge"}  # bridge towns: the river runs through them
ISLANDS = {"stros_mkai", "artaeum", "sadrith_mora", "imperial_city", "raven_rock", "vivec", "balmora", "aldruhn",
           "red_mountain", "alinor", "firsthold"}


def anchors():
    out = {}
    for p in json.load(open(PLACES, encoding="utf-8")):
        out[p["id"]] = (p["region"], p["x"], p["z"])
    out.update(ILIAC_ANCHORS)
    out.update(SKYRIM_ANCHORS)
    return out


def bil(a, x, z):
    fx = np.clip((np.asarray(x, float) - MIN_X) / CELL - 0.5, 0, W - 1.001)
    fz = np.clip((np.asarray(z, float) - MIN_Z) / CELL - 0.5, 0, H - 1.001)
    ix, iz = fx.astype(int), fz.astype(int)
    tx, tz = fx - ix, fz - iz
    a00, a10, a01, a11 = a[iz, ix], a[iz, ix + 1], a[iz + 1, ix], a[iz + 1, ix + 1]
    return (a00 * (1 - tx) + a10 * tx) * (1 - tz) + (a01 * (1 - tx) + a11 * tx) * tz


def validate(o):
    regions = o["regions"]
    land = o["land"]
    # connected components of land (4-neighbour) to tell islands from the mainland
    if _ndi is not None:
        comp, _ = _ndi.label(land)
    else:
        comp = np.zeros(land.shape, np.int32)
    wcomp = comp[int((0 - MIN_Z) / CELL), int((0 - MIN_X) / CELL)]
    problems = 0
    for pid, (rid, x, z) in sorted(anchors().items()):
        ix, iz = int((x - MIN_X) / CELL), int((z - MIN_Z) / CELL)
        got = regions[o["reg"][iz, ix]]["id"]
        h = float(bil(o["h_view"], x, z))
        d = float(bil(o["D"], x, z))
        lk = float(bil(o["lake_sd"], x, z))
        rv = float(bil(o["riv_d"], x, z))
        msg = []
        if got != rid:
            msg.append(f"region {got} != {rid}")
        if d <= 0 or h < SEA + 1:
            msg.append(f"not on land (coast {d:.0f}, y {h:.0f})")
        if lk > -10 and pid != "imperial_city":
            msg.append(f"in a lake ({lk:.0f})")
        if rv < 25 and pid not in ON_RIVER:
            msg.append(f"in a river ({rv:.0f})")
        if pid in COASTAL and d > 400:
            msg.append(f"not coastal ({d:.0f} blocks inland)")
        if pid in ISLANDS and _ndi is not None and pid != "imperial_city" and comp[iz, ix] == wcomp:
            msg.append("not on an island")
        if msg:
            problems += 1
            print(f"  ANCHOR {pid:20s} ({x},{z}): " + "; ".join(msg))
        elif "-v" in sys.argv:
            print(f"  ok     {pid:20s} y {h:5.1f}  coast {d:6.0f}  {got}")
    print(f"anchors checked, {problems} problem(s)")
    return problems


# ---------------------------------------------------------------------------------------------------------------------
def hypso(h, water):
    stops = [(-40, (10, 30, 70)), (40, (25, 70, 130)), (62.9, (70, 130, 180)), (63, (200, 190, 140)),
             (66, (150, 175, 95)), (90, (95, 145, 70)), (130, (70, 115, 55)), (170, (125, 115, 75)),
             (240, (140, 130, 115)), (320, (175, 172, 168)), (420, (225, 225, 230)), (520, (255, 255, 255))]
    out = np.zeros(h.shape + (3,))
    for (h0, c0), (h1, c1) in zip(stops[:-1], stops[1:]):
        m = (h >= h0) & (h < h1)
        t = ((h - h0) / (h1 - h0))[m][:, None]
        out[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    out[h >= stops[-1][0]] = stops[-1][1]
    out[water] = out[water] * 0.3 + np.array([60, 110, 200]) * 0.7
    return out


def shade(h, px):
    gy, gx = np.gradient(h, px)
    s = np.clip(0.75 + (-gx * 0.7 - gy * 0.7) * 1.2, 0.35, 1.3)
    return s[..., None]


def font(size):
    for f in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def preview(o, name, x0, z0, x1, z1, bpp, labels=True, borders=True):
    xs = np.arange(x0, x1, bpp) + bpp / 2
    zs = np.arange(z0, z1, bpp) + bpp / 2
    gx, gz = np.meshgrid(xs, zs)
    h = bil(o["h"], gx, gz)
    for pk in o["sharp"]:
        h = h + sharp_lift(gx, gz, pk, h, bil(o["roadw"], gx, gz))
    D = bil(o["D"], gx, gz)
    lsd = bil(o["lake_sd"], gx, gz)
    rd = bil(o["riv_d"], gx, gz)
    hw = bil(np.where(o["riv_d"] < 220, o["riv_hw"], 0), gx, gz)
    sea = (D <= 0) | (h < SEA - 0.5)
    water = sea | (lsd > 0) | (rd < np.maximum(hw, bpp * 0.6))
    hh = np.where(sea, np.minimum(h, SEA - 1), h)
    rgb = hypso(hh, water) * np.where(water[..., None], 1.0, shade(hh, bpp))
    if borders:
        ix = np.clip(((gx - MIN_X) / CELL).astype(int), 0, W - 1)
        iz = np.clip(((gz - MIN_Z) / CELL).astype(int), 0, H - 1)
        rg = o["reg"][iz, ix]
        e = np.zeros(rg.shape, bool)
        e[:, 1:] |= rg[:, 1:] != rg[:, :-1]
        e[1:, :] |= rg[1:, :] != rg[:-1, :]
        prov = np.array([zlib.crc32(r["province"].encode()) for r in o["regions"]])[rg]
        pe = np.zeros(rg.shape, bool)
        pe[:, 1:] |= prov[:, 1:] != prov[:, :-1]
        pe[1:, :] |= prov[1:, :] != prov[:-1, :]
        landr = np.array([r["kind"] != "sea" for r in o["regions"]])[rg]
        both = landr.copy()
        both[:, 1:] &= landr[:, :-1]
        both[1:, :] &= landr[:-1, :]
        rgb[e & both & ~sea] = rgb[e & both & ~sea] * 0.55
        rgb[pe & both & ~sea] = (30, 20, 20)
    road = bil(o["road_d"], gx, gz) < bpp * 0.7
    rgb[road & ~water] = (150, 60, 40)
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))
    if labels:
        dr = ImageDraw.Draw(img)
        f = font(max(10, int(14 * min(1.0, 16 / bpp) + 2)))
        for pid, (rid, x, z) in anchors().items():
            if not (x0 <= x < x1 and z0 <= z < z1):
                continue
            px, pz = (x - x0) / bpp, (z - z0) / bpp
            dr.ellipse([px - 3, pz - 3, px + 3, pz + 3], fill=(200, 20, 20), outline=(0, 0, 0))
            dr.text((px + 5, pz - 8), pid.replace("_", " ").title(), fill=(255, 255, 255), font=f,
                    stroke_width=2, stroke_fill=(0, 0, 0))
        if bpp >= 16:
            fr = font(13)
            for i, r in enumerate(o["regions"]):
                m = o["reg"] == i
                if not m.any() or r["kind"] == "sea" and not r["polys"]:
                    continue
                zz, xx = np.nonzero(m)
                cx, cz = MIN_X + (xx.mean() + 0.5) * CELL, MIN_Z + (zz.mean() + 0.5) * CELL
                if x0 <= cx < x1 and z0 <= cz < z1:
                    dr.text(((cx - x0) / bpp - 30, (cz - z0) / bpp + 6), r["id"].split("/")[1], fill=(255, 240, 160),
                            font=fr, stroke_width=1, stroke_fill=(0, 0, 0))
    img.save(os.path.join(HERE, name))
    print("wrote", name, img.size)


def main():
    o = build()
    write_assets(o)
    problems = validate(o)
    print("hydrology:", json.dumps(o["hyd"]["stats"]), "basin lakes:", len(o["hyd"].get("basin_lakes", [])),
          "sites:", json.dumps(o["hyd"]["site_counts"]))
    if "--no-preview" not in sys.argv:
        preview(o, "preview_tamriel.png", MIN_X, MIN_Z, MIN_X + W * CELL, MIN_Z + H * CELL, 16)
        preview(o, "preview_skyrim.png", -3800, -2700, 3600, 2900, 4)
        preview(o, "preview_morrowind.png", 2600, -4400, 11500, 4600, 8)
        preview(o, "preview_cyrodiil.png", -4800, 1500, 5000, 10800, 8)
    print("land cells", int(o["land"].sum()), "of", W * H, "max y", float(o["h"].max()))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
