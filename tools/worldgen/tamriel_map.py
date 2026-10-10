"""Builds the Tamriel map rasters from the hand-authored vectors in tamriel_vectors.json.

Usage: py -3 tools/worldgen/tamriel_map.py [--no-preview]

Writes to mod/src/main/resources/skycraft/tamriel/ (all rasters are CELL = 32 blocks per pixel, pixel (0, 0) is the
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
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft", "tamriel")
PLACES = os.path.join(RES_DIR, "places", "provinces.json")

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
    out = []
    for p in json.load(open(PLACES, encoding="utf-8")):
        out.append((p["id"], p["region"], p["x"], p["z"], p.get("radius", 150)))
    for pid, (rid, x, z) in SKYRIM_ANCHORS.items():
        out.append((pid, rid, x, z, 140))
    return out


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
    props = np.zeros((7, H, W))
    default = [90, 10, 0.35, 0.1, 0, 0, 200]
    table = np.array([r.get("terrain", default) for r in regions], float)
    props[:] = table[reg].transpose(2, 0, 1)
    area_keys = ["base", "relief", "rough", "crag", "dunes", "wet", "coast"]
    for a in V["areas"]:
        m = raster_polys([a["poly"]], CELL, W, H)[rj, ri]
        for k, v in a.items():
            if k in area_keys:
                props[area_keys.index(k)][m] = v
    landf = land.astype(float)
    wsum = blur(landf, 6) + 1e-6
    for k in range(7):
        props[k] = blur(props[k] * landf, 6) / wsum
    base, relief, rough, crag, dunes, wet, ramp = props

    # --- elevation ---------------------------------------------------------------------------------------------------
    print("elevation")
    big = fbm(GX, GZ, 2600, 3, 11)
    mid = fbm(GX, GZ, 900, 3, 23)
    hills = ridged(GX, GZ, 1500, 3, 29) - 0.42
    B = base + relief * (0.55 * big + 0.45 * mid) + rough * (26 * hills + 8 * fbm(GX, GZ, 380, 2, 31))
    # continuous across the coast: land rises from sea level, the sea floor falls from it
    # (linear in D within 64 blocks of the coast, so interpolating the 32-block raster gives a smooth shoreline)
    t = smoothstep(48, 48 + np.maximum(ramp * 1.8, 60), D)
    beach = SEA + 0.05 * np.minimum(D, 64)
    hland = beach + (np.maximum(B, beach) - beach) * t
    hsea = SEA - sea_depth(np.maximum(-D, 0)) + 5 * fbm(GX, GZ, 700, 2, 5) * smoothstep(64, 400, -D)
    h = np.where(D > 0, hland, hsea)

    ridge = np.zeros((H, W))
    rwx, rwz = warp_fields(GX, GZ, 1100, 320, 202)
    rn1 = ridged(GX, GZ, 650, 3, 77)
    rn2 = ridged(GX, GZ, 260, 2, 78)
    rn = 0.65 * rn1 + 0.35 * rn2
    wmod = 0.75 + 0.5 * (fbm(GX, GZ, 1800, 2, 79) * 0.5 + 0.5)
    hvar = fbm(GX, GZ, 800, 2, 80)
    for rg in V["ranges"]:
        half = rg["width"] / 2.0
        d, _, _ = polyline_field(rg["line"], half * 1.3 + 350, gx=rwx, gz=rwz)
        p = 1 - smoothstep(0, half * wmod, d)
        if not p.any():
            continue
        hl = rg["height"] * (0.88 + 0.12 * hvar)
        shape = p ** 1.25 * (0.38 + 0.62 * rn)
        lift = np.maximum(hl - h, 0) * shape
        h = h + lift * smoothstep(0, 150, D)
        ridge = np.maximum(ridge, p * (0.5 + 0.5 * rn))
    for pk in V["peaks"]:
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

    # wetlands sit just around sea level so low spots become pools
    wl = SEA + 0.7 + 1.7 * fbm(GX, GZ, 160, 2, 41)
    wv = smoothstep(0.2, 0.6, wet) * land
    # settlements get dry, level ground
    pad = np.zeros((H, W))
    for pid, rid, x, z, rad in anchors_:
        pad = np.maximum(pad, 1 - smoothstep(rad * 0.8, rad * 2.0, np.hypot(GX - x, GZ - z)))
    wv = wv * (1 - pad)
    h = h + (wl - h) * wv * smoothstep(SEA + 45, SEA + 20, h)
    h = np.where((D > 64) & (pad > 0), np.maximum(h, SEA + 1 + 2.5 * pad), h)

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
    lsd_f = signed_dist(lake_mask_f, FRES)
    rr, cc = nearest(lake_mask_f)
    lid = lake_id_f[rr, cc]
    lake_sd = lsd_f[FINE // 2::FINE, FINE // 2::FINE][:H, :W]
    lid = lid[FINE // 2::FINE, FINE // 2::FINE][:H, :W]
    levels = np.array([lk["level"] for lk in V["lakes"]], float)
    depths = np.array([lk.get("depth", 8) for lk in V["lakes"]], float)
    lake_level = levels[lid]
    near_lake = lake_sd > -260
    guard = lake_level + 1.6
    shore = 1 - smoothstep(0, 220, -lake_sd)
    h_l = np.maximum(h, h + (guard - h) * shore)
    h_l = np.minimum(h_l, guard + np.maximum(-lake_sd, 0) * 0.45)
    bed = lake_level - np.minimum(depths[lid], 1.5 + np.maximum(lake_sd, 0) * 0.22)
    h = np.where(near_lake & land, np.where(lake_sd > 0, bed, h_l), h)
    for lk in V["lakes"]:
        for isl in lk.get("islands", []):
            m = raster_polys([isl], CELL, W, H)
            h[m] = np.maximum(h[m], lk["level"] + 7)

    # --- rivers ----------------------------------------------------------------------------------------------------
    print("rivers")
    riv_d = np.full((H, W), 1e5)
    riv_level = np.zeros((H, W))
    riv_hw = np.zeros((H, W))
    for rv in V["rivers"]:
        line = river_profile(rv, h)
        lv = [p[2] for p in line]
        width = rv["width"]
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

    h = np.clip(h, -40, 376)

    # --- roads -------------------------------------------------------------------------------------------------------
    road_d = np.full((H, W), 1e5)
    for rd in V["roads"]:
        d, _, _ = polyline_field(rd["line"], 80)
        road_d = np.minimum(road_d, np.where(np.isfinite(d), d, 1e5))

    # --- terrain character -----------------------------------------------------------------------------------------
    rough_t = np.clip(rough + ridge * 0.45, 0, 1)
    crag_t = np.clip(crag + ridge * 0.6 * smoothstep(110, 200, h), 0, 1)
    dunes_t = np.clip(dunes, 0, 1) * land
    wet_t = wv

    # --- water raster -----------------------------------------------------------------------------------------------
    use_lake = lake_sd > -64
    use_riv = (riv_d < 220) & ~use_lake
    wlevel = np.where(use_lake, lake_level, np.where(use_riv, riv_level, 0))
    wflags = (lake_sd > -260).astype(np.uint8) | ((riv_d < 220).astype(np.uint8) << 1)
    wlevel = np.where((wlevel == 0) & (lake_sd > -260), lake_level, wlevel)
    wl_code = np.where(wlevel > 0, np.round((wlevel + 64) * 64), 0).astype(np.uint32)

    return dict(regions=regions, reg=reg, h=h, D=D, land=land, ridge=ridge, rough=rough_t, crag=crag_t,
                dunes=dunes_t, wet=wet_t, lake_sd=lake_sd, riv_d=riv_d, riv_hw=riv_hw, road_d=road_d,
                wl_code=wl_code, wflags=wflags, wlevel=wlevel)


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
    os.makedirs(RES_DIR, exist_ok=True)
    regions = o["regions"]
    img = Image.fromarray(o["reg"].astype(np.uint8), "P")
    img.putpalette(region_palette(regions))
    img.save(os.path.join(RES_DIR, "regions.png"), optimize=True)

    elev = np.clip(np.round((o["h"] + 64) * 32), 0, 65535).astype(np.uint16)
    Image.fromarray(elev).save(os.path.join(RES_DIR, "elevation.png"))  # PIL writes uint16 as 16-bit grey

    feat = np.zeros((H, W, 4), np.uint8)
    feat[..., 0] = np.clip(255 - 2 * o["riv_d"], 0, 255)
    feat[..., 1] = np.clip(o["ridge"] * 255, 0, 255)
    feat[..., 2] = np.clip(128 + 2 * o["lake_sd"], 0, 255)
    feat[..., 3] = np.clip(255 - 4 * o["road_d"], 0, 255)
    Image.fromarray(feat, "RGBA").save(os.path.join(RES_DIR, "features.png"))

    ter = np.zeros((H, W, 4), np.uint8)
    for k, key in enumerate(("rough", "crag", "dunes", "wet")):
        ter[..., k] = np.clip(np.round(o[key] * 255), 0, 255)
    Image.fromarray(ter, "RGBA").save(os.path.join(RES_DIR, "terrain.png"))

    wat = np.zeros((H, W, 4), np.uint8)
    wat[..., 0] = (o["wl_code"] >> 8) & 255
    wat[..., 1] = o["wl_code"] & 255
    wat[..., 2] = np.clip(np.round(np.where(o["riv_d"] < 220, o["riv_hw"], 0)), 0, 255)
    wat[..., 3] = o["wflags"]
    Image.fromarray(wat, "RGBA").save(os.path.join(RES_DIR, "water.png"))

    coast = np.clip(np.round(o["D"]) + 32768, 0, 65535).astype(np.uint16)
    Image.fromarray(coast).save(os.path.join(RES_DIR, "coast.png"))

    table = []
    for i, r in enumerate(regions):
        prov, short = r["id"].split("/")
        e = {"index": i, "id": r["id"], "province": prov, "kind": r["kind"], "culture": r["culture"],
             "name": f"region.skycraft.{prov}.{short}", "levels": r["levels"], "palette": r["id"],
             "music": prov, "weather": r["weather"]}
        if r.get("capital"):
            e["capital"] = r["capital"]
        if r.get("terrain"):
            e["terrain"] = dict(zip(["base", "relief", "rough", "crag", "dunes", "wet", "coast"], r["terrain"]))
        if r.get("procedural"):
            e["procedural"] = True
        table.append(e)
    with open(os.path.join(RES_DIR, "regions.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write("[\n" + ",\n".join(" " + json.dumps(e) for e in table) + "\n]\n")

    lang = {e["name"]: REGION_NAMES.get(e["id"], e["id"].split("/")[1].replace("_", " ").title()) for e in table}
    lang["region.skycraft.realm.generated"] = "%s"
    with open(LANG, "w", encoding="utf-8", newline="\n") as f:
        json.dump(dict(sorted(lang.items())), f, indent=2, ensure_ascii=False)
        f.write("\n")


LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft", "tamriel_regions.json")
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
    "high_rock/glenumbra": "Glenumbra", "high_rock/stormhaven": "Stormhaven", "high_rock/rivenspire": "Rivenspire",
    "high_rock/wrothgar": "Wrothgar", "high_rock/wayrest": "Wayrest", "high_rock/bjoulsae": "Bjoulsae River",
    "hammerfell/alikr_desert": "Alik'r Desert", "hammerfell/bangkorai": "Bangkorai", "hammerfell/craglorn": "Craglorn",
    "hammerfell/stros_mkai": "Stros M'Kai", "hammerfell/hews_bane": "Hew's Bane", "hammerfell/dragontail": "Dragontail Mountains",
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
COASTAL = {"anvil", "leyawiin", "vivec", "sadrith_mora", "necrom", "raven_rock", "wayrest", "daggerfall", "sentinel",
           "taneth", "lilmoth", "senchal", "woodhearth", "alinor", "firsthold", "solitude", "dawnstar", "winterhold"}
ON_RIVER = {"dragon_bridge"}  # bridge towns: the river runs through them
ISLANDS = {"stros_mkai", "artaeum", "sadrith_mora", "imperial_city", "raven_rock", "vivec", "balmora", "aldruhn",
           "red_mountain", "alinor", "firsthold"}


def anchors():
    out = {}
    for p in json.load(open(PLACES, encoding="utf-8")):
        out[p["id"]] = (p["region"], p["x"], p["z"])
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
        h = float(bil(o["h"], x, z))
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
             (220, (140, 130, 115)), (270, (185, 185, 185)), (330, (245, 245, 250)), (400, (255, 255, 255))]
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
    if "--no-preview" not in sys.argv:
        preview(o, "preview_tamriel.png", MIN_X, MIN_Z, MIN_X + W * CELL, MIN_Z + H * CELL, 16)
        preview(o, "preview_skyrim.png", -3800, -2700, 3600, 2900, 4)
        preview(o, "preview_morrowind.png", 2600, -4400, 11500, 4600, 8)
        preview(o, "preview_cyrodiil.png", -4800, 1500, 5000, 10800, 8)
    print("land cells", int(o["land"].sum()), "of", W * H, "max y", float(o["h"].max()))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
