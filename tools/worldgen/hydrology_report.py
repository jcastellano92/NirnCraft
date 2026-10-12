"""Hydrology analysis and map views of BAKED Tamriel rasters (no Minecraft needed).

usage (from the repo root):
  py -3 -I tools/worldgen/hydrology_report.py analyze --dir DIR [--label before] [--json OUT.json]
  py -3 -I tools/worldgen/hydrology_report.py render  --dir DIR --tag after --out OUTDIR [--hydro hydrology.json]
  py -3 -I tools/worldgen/hydrology_report.py compare --before DIR --after DIR --out OUTDIR

DIR holds elevation.png, features.png, water.png, terrain.png, coast.png, regions.png (the resource folder
`mod/src/main/resources/skycraft/tamriel/` or a TAMRIEL_OUT experiment folder).

analyze: priority-flood the baked macro height field (pit filling), route D8, accumulate runoff, then compare the
network with the authored vectors: where rivers end, rivers that run uphill or above the ground, lakes without an
outlet, big drainage that has no channel, and how much mountain run-off reaches a channel and how far it travels.
"""
import argparse
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import hydrology as hy  # noqa: E402

V = json.load(open(os.path.join(HERE, "tamriel_vectors.json"), encoding="utf-8"))
M = V["meta"]
CELL, MIN_X, MIN_Z, W, H, SEA = M["cell"], M["min_x"], M["min_z"], M["width_cells"], M["height_cells"], M["sea_level"]
GRID = hy.Grid(MIN_X, MIN_Z, W, H)


# ---------------------------------------------------------------------------------------------------------------------
class Baked:
    def __init__(self, d):
        self.dir = d
        rd = lambda n, mode=None: np.array(Image.open(os.path.join(d, n)).convert(mode) if mode else Image.open(os.path.join(d, n)))
        self.h = rd("elevation.png").astype(float) / 32.0 - 64.0
        f = rd("features.png", "RGBA").astype(float)
        self.riv_d = np.where(f[..., 0] <= 0, 400.0, (255 - f[..., 0]) * 0.5)
        self.ridge = f[..., 1] / 255.0
        self.lake_sd = np.where(f[..., 2] <= 0, -400.0, (f[..., 2] - 128) * 0.5)
        w = rd("water.png", "RGBA").astype(float)
        code = w[..., 0] * 256 + w[..., 1]
        self.level = np.where(code > 0, code / 64.0 - 64.0, np.nan)
        self.riv_hw = w[..., 2]
        t = rd("terrain.png", "RGBA").astype(float)
        self.dunes = t[..., 2] / 255.0
        self.wet = t[..., 3] / 255.0
        self.coast = rd("coast.png").astype(float) - 32768.0
        self.land = self.coast > 0
        reg_img = Image.open(os.path.join(d, "regions.png"))
        self.reg = np.array(reg_img)
        pal = reg_img.getpalette() or []
        self.palette = np.array(pal[:768] + [0] * (768 - len(pal)), np.uint8).reshape(256, 3)
        rj = os.path.join(d, "regions.json")
        self.regions = json.load(open(rj, encoding="utf-8")) if os.path.exists(rj) else []
        # a cell is on a channel when the line crosses it (centre within half a cell diagonal of the channel edge)
        self.channel_river = self.riv_d < (self.riv_hw + 23.0)
        self.channel_lake = self.lake_sd > -16.0
        self.channel = self.channel_river | self.channel_lake


def authored_lines(step=16.0):
    out = []
    for rv in V["rivers"]:
        pts = np.array([p[:2] for p in rv["line"]], float)
        lev = np.array([p[2] for p in rv["line"]], float)
        w = rv["width"]
        hwv = np.full(len(pts), w / 2.0)
        dp, dl, dh = hy.densify(pts, lev, hwv, step)
        out.append(dict(name=rv.get("name") or rv.get("id") or "river", pts=dp, lev=dl, hw=dh, width=w))
    return out


def sample_nearest(grid, a, pts):
    ix = np.clip(((pts[:, 0] - grid.min_x) / CELL).astype(int), 0, grid.w - 1)
    iz = np.clip(((pts[:, 1] - grid.min_z) / CELL).astype(int), 0, grid.h - 1)
    return a[iz, ix]


# ---------------------------------------------------------------------------------------------------------------------
def analyze(d, label="", hydro_json=None):
    b = Baked(d)
    out = {"label": label, "dir": d}
    lines = authored_lines()
    # ---- rivers ------------------------------------------------------------------------------------------------
    rivers = []
    for i, ln in enumerate(lines):
        pts = ln["pts"]
        end, start = pts[-1], pts[0]
        d_end = float(GRID.bil(b.coast, end[0], end[1]))
        lk_end = float(GRID.bil(b.lake_sd, end[0], end[1]))
        end_kind = "sea" if d_end <= 48 else "lake" if lk_end > -60 else None
        if end_kind is None:
            for j, other in enumerate(lines):
                if j == i:
                    continue
                dd = np.hypot(other["pts"][:, 0] - end[0], other["pts"][:, 1] - end[1])
                k = int(dd.argmin())
                if dd[k] < other["hw"][k] + 60:
                    end_kind = "river:" + other["name"]
                    break
        lk_start = float(GRID.bil(b.lake_sd, start[0], start[1]))
        start_kind = "lake" if lk_start > -150 else None
        if start_kind is None:
            for j, other in enumerate(lines):
                if j == i:
                    continue
                dd = np.hypot(other["pts"][:, 0] - start[0], other["pts"][:, 1] - start[1])
                if dd.min() < 100:
                    start_kind = "river"
                    break
        if start_kind is None:
            start_kind = "source@%dm" % round(float(GRID.bil(b.h, start[0], start[1])))
        lev = sample_nearest(GRID, b.level, pts)
        hh = np.array([float(GRID.bil(b.h, p[0], p[1])) for p in pts])
        ok = ~np.isnan(lev)
        rises = np.diff(lev[ok])
        up_steps = int((rises > 0.3).sum())
        up_blocks = float(rises[rises > 0.3].sum()) if up_steps else 0.0
        perched = int(((lev[ok] - hh[ok]) > 0.5).sum())
        rivers.append(dict(name=ln["name"], length=round(float(np.hypot(*np.diff(pts, axis=0).T).sum())),
                           end=end_kind or "DEAD END inland", start=start_kind, uphill_steps=up_steps,
                           uphill_blocks=round(up_blocks, 1), above_ground_points=perched, points=int(ok.sum())))
    out["rivers"] = rivers
    # ---- lakes -------------------------------------------------------------------------------------------------
    lab, n = hy.label(b.lake_sd > 0)
    lakes = []
    outlets = 0
    for k in range(1, n + 1):
        m = lab == k
        cells = int(m.sum())
        if cells < 2:
            continue
        zz, xx = np.nonzero(m)
        dil = np.zeros_like(m)
        for dz in range(-5, 6):
            for dx in range(-5, 6):
                dil |= np.roll(np.roll(m, dz, 0), dx, 1)
        ring = dil & ~m & ~b.channel_lake
        lvl = float(np.nanmedian(b.level[m])) if np.isfinite(b.level[m]).any() else float("nan")
        # an outlet: a river channel in the ring with water not above the lake
        riv = ring & b.channel_river & b.land
        has_out = bool(riv.any())
        lakes.append(dict(x=int(MIN_X + (xx.mean() + 0.5) * CELL), z=int(MIN_Z + (zz.mean() + 0.5) * CELL), cells=cells,
                          level=round(lvl, 1), channel_touching=has_out))
        outlets += has_out
    out["lakes"] = lakes
    out["lakes_total"] = len(lakes)
    out["lakes_without_channel"] = sum(1 for l in lakes if not l["channel_touching"])
    # ---- flow --------------------------------------------------------------------------------------------------
    sink = ~b.land
    recv, order, filled = hy.flood(b.h, sink)
    ones = np.where(b.land, 1.0, 0.0)
    acc = hy.accumulate(recv, order, ones)
    pits = (filled - b.h > 1.0) & b.land & (b.lake_sd < -64.0) & ~(b.wet > 0.3)
    out["pit_cells"] = int(pits.sum())
    plab, pn = hy.label(pits)
    big_pits = 0
    for k in range(1, pn + 1):
        m = plab == k
        if m.sum() >= 5 and (filled - b.h)[m].max() >= 3:
            big_pits += 1
    out["pits_5cells_3blocks"] = big_pits
    chan = b.channel & b.land
    import hydrology_build as hb
    zones = hb.place_exclusion(GRID)
    if hy._ndi is not None:
        zones = hy._ndi.binary_dilation(zones, iterations=3)
    out["trunk_cells_inside_place_zones"] = int(((acc >= 800) & ~chan & b.land & zones).sum())
    trunk = (acc >= 800) & ~chan & b.land & ~zones
    tl, tn = hy.label(trunk)
    trunks = []
    for k in range(1, tn + 1):
        m = tl == k
        if m.sum() < 3:
            continue
        zz, xx = np.nonzero(m)
        a = acc[m].max()
        j = int(np.argmax(np.where(m, acc, 0)))
        trunks.append(dict(x=int(MIN_X + (j % W + 0.5) * CELL), z=int(MIN_Z + (j // W + 0.5) * CELL), cells=int(m.sum()),
                           max_acc_cells=int(a), km2_blocks=int(a * CELL * CELL)))
    trunks.sort(key=lambda t: -t["max_acc_cells"])
    out["unchanneled_trunks"] = len(trunks)
    out["unchanneled_trunks_top"] = trunks[:12]
    # run-off destination of mountain cells
    N = H * W
    chanf = chan.ravel()
    cls = np.zeros(N, np.int8)
    dist = np.zeros(N, np.float32)
    for c in order.tolist():
        if chanf[c]:
            cls[c] = 1
        else:
            r = recv[c]
            if r < 0:
                cls[c] = 2
            else:
                cls[c] = cls[r]
                dist[c] = dist[r] + CELL
    mount = ((b.h > SEA + 100) | (b.ridge > 0.35)) & b.land
    mf = mount.ravel()
    runoff = hy.runoff_weight(GRID, b.reg, b.regions, b.h, b.ridge, b.dunes, b.land).ravel() if b.regions else ones.ravel()
    tot = float(runoff[mf].sum())
    to_chan = float(runoff[mf & (cls == 1)].sum())
    dd = dist[mf & (cls == 1)]
    out["mountain_cells"] = int(mf.sum())
    out["mountain_runoff_to_channel_share"] = round(to_chan / tot, 4) if tot else None
    out["mountain_runoff_to_sea_directly_share"] = round(float(runoff[mf & (cls == 2)].sum()) / tot, 4) if tot else None
    out["mountain_flow_path_to_channel_blocks_median"] = float(np.median(dd)) if len(dd) else None
    out["mountain_flow_path_to_channel_blocks_p90"] = float(np.percentile(dd, 90)) if len(dd) else None
    lf = b.land.ravel()
    dall = dist[lf & (cls == 1)]
    out["land_flow_path_to_channel_blocks_median"] = float(np.median(dall)) if len(dall) else None
    out["channel_land_cells"] = int((chan & b.land).sum())
    out["land_cells"] = int(b.land.sum())
    out["place_conflicts"] = place_conflicts(b)
    out["acc"] = acc
    out["_b"] = b
    out["_filled"] = filled
    return out


def place_conflicts(b, margin=3.0):
    """Places standing in a channel or a lake of these rasters (the footprint centre only; the layout lint covers
    the rest). Returns {'settlements': [...ids], 'other': [...ids]}."""
    import hydrology_build as hb
    places = hb.load_places()
    xs = np.array([[p["x"] for p in places]], float)
    zs = np.array([[p["z"] for p in places]], float)
    inside = (xs >= MIN_X + CELL) & (xs < MIN_X + W * CELL - CELL) & (zs >= MIN_Z + CELL) & (zs < MIN_Z + H * CELL - CELL)
    xs = np.where(inside, xs, 0.0)
    zs = np.where(inside, zs, 0.0)
    rd = river_distance(b.riv_d, xs, zs)[0]
    hw = GRID.bil(np.where(b.riv_d < 220, b.riv_hw, 0), xs, zs)[0]
    lk = GRID.bil(b.lake_sd, xs, zs)[0]
    out = {"settlements": [], "other": []}
    for i, p in enumerate(places):
        if not inside[0, i]:
            continue
        if p.get("variant") in ("island", "dock", "pier", "shipwreck", "lighthouse", "ferry", "harbour", "harbor"):
            continue
        hit = (rd[i] < hw[i] + margin and hw[i] > 0) or lk[i] > 0
        if hit:
            (out["settlements"] if p.get("type") in hb.SETTLEMENT_TYPES else out["other"]).append(p["id"])
    return out


def summarize(a):
    return {k: v for k, v in a.items() if not k.startswith("_") and k not in ("acc",)}


# ---------------------------------------------------------------------------------------------------------------------
STOPS = [(-40, (10, 30, 70)), (40, (25, 70, 130)), (62.9, (70, 130, 180)), (63, (200, 190, 140)), (66, (150, 175, 95)),
         (90, (95, 145, 70)), (130, (70, 115, 55)), (170, (125, 115, 75)), (240, (140, 130, 115)), (320, (175, 172, 168)),
         (420, (225, 225, 230)), (520, (255, 255, 255))]


def hypso(hh):
    out = np.zeros(hh.shape + (3,))
    for (h0, c0), (h1, c1) in zip(STOPS[:-1], STOPS[1:]):
        m = (hh >= h0) & (hh < h1)
        t = ((hh - h0) / (h1 - h0))[m][:, None]
        out[m] = np.array(c0) * (1 - t) + np.array(c1) * t
    out[hh >= STOPS[-1][0]] = STOPS[-1][1]
    return out


def shade(hh, px):
    gy, gx = np.gradient(hh, px)
    return np.clip(0.75 + (-gx * 0.7 - gy * 0.7) * 1.2, 0.35, 1.3)[..., None]


def river_distance(arr, gx, gz):
    """What MapRaster.riverDistance computes: a bilinear blend of the unsigned distance cannot reach zero between the
    32-block cell centres, so near a river a planar signed distance is fitted through the four corners."""
    fx = np.clip((gx - MIN_X) / CELL - 0.5, 0, W - 1.001)
    fz = np.clip((gz - MIN_Z) / CELL - 0.5, 0, H - 1.001)
    ix, iz = fx.astype(int), fz.astype(int)
    tx, tz = fx - ix, fz - iz
    d00, d10, d01, d11 = arr[iz, ix], arr[iz, ix + 1], arr[iz + 1, ix], arr[iz + 1, ix + 1]
    bil = (d00 * (1 - tx) + d10 * tx) * (1 - tz) + (d01 * (1 - tx) + d11 * tx) * tz
    near = (np.minimum(np.minimum(d00, d10), np.minimum(d01, d11)) <= CELL * 1.5) & \
           (np.maximum(np.maximum(d00, d10), np.maximum(d01, d11)) < 127)
    best = np.full(bil.shape, np.inf)
    val = bil.copy()
    for m in range(8):
        v00 = d00
        v10 = -d10 if m & 1 else d10
        v01 = -d01 if m & 2 else d01
        v11 = -d11 if m & 4 else d11
        bb = ((v10 - v00) + (v11 - v01)) * 0.5
        cc = ((v01 - v00) + (v11 - v10)) * 0.5
        twist = v00 - v10 - v01 + v11
        score = np.abs(twist) / CELL + np.abs(np.sqrt(bb * bb + cc * cc) / CELL - 1)
        a = (v00 + v10 + v01 + v11) * 0.25 - (bb + cc) * 0.5
        v = np.abs(a + bb * tx + cc * tz)
        take = near & (score < best)
        best = np.where(take, score, best)
        val = np.where(take, v, val)
    return np.where(near & (best < 0.25), np.minimum(bil, val), bil)


def view(b, x0, z0, x1, z1, bpp, biome=True, hydro=None, flow=None):
    xs = np.arange(x0, x1, bpp) + bpp / 2
    zs = np.arange(z0, z1, bpp) + bpp / 2
    gx, gz = np.meshgrid(xs, zs)
    hh = GRID.bil(b.h, gx, gz)
    D = GRID.bil(b.coast, gx, gz)
    lsd = GRID.bil(b.lake_sd, gx, gz)
    rd = river_distance(b.riv_d, gx, gz)
    hw = GRID.bil(np.where(b.riv_d < 220, b.riv_hw, 0), gx, gz)
    sea = (D <= 0)
    water = sea | (lsd > 0) | (rd < np.maximum(hw, bpp * 0.9))
    rgb = hypso(np.where(sea, np.minimum(hh, SEA - 1), hh)) * shade(hh, bpp)
    if biome and b.regions:
        ix = np.clip(((gx - MIN_X) / CELL).astype(int), 0, W - 1)
        iz = np.clip(((gz - MIN_Z) / CELL).astype(int), 0, H - 1)
        pal = b.palette[b.reg[iz, ix]].astype(float)
        rgb = rgb * 0.75 + pal * 0.25
    if flow is not None:
        la = np.log10(np.maximum(GRID.bil(flow, gx, gz), 1.0))
        t = np.clip(la / 4.5, 0, 1)[..., None]
        rgb = rgb * 0.0 + (np.array([240, 240, 235]) * (1 - t) + np.array([20, 60, 200]) * t) * 1.0
        rgb = np.where(sea[..., None], np.array([15, 25, 50]), rgb)
    else:
        rgb = np.where(water[..., None], np.array([55, 105, 215]), rgb)
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))
    if hydro:
        dr = ImageDraw.Draw(img)
        for f in hydro.get("waterfalls", []):
            if x0 <= f[0] < x1 and z0 <= f[1] < z1:
                px, pz = (f[0] - x0) / bpp, (f[1] - z0) / bpp
                r = 2 + (1 if f[8] else 0)
                dr.ellipse([px - r, pz - r, px + r, pz + r], fill=(255, 40, 40) if f[8] else (255, 170, 60))
        for s in hydro.get("springs", []):
            if x0 <= s[0] < x1 and z0 <= s[1] < z1 and bpp <= 8:
                px, pz = (s[0] - x0) / bpp, (s[1] - z0) / bpp
                dr.point([px, pz], fill=(0, 255, 255))
    return img


AREAS = {
    "whiterun_white_river": (-2200, -2000, 2800, 1500),
    "rift_treva": (200, 200, 3300, 2300),
    "cyrodiil_niben": (-500, 3600, 4100, 7300),
    "black_marsh_delta": (3000, 8200, 8400, 11800),
}


def render(d, tag, out, hydro_path=None):
    os.makedirs(out, exist_ok=True)
    b = Baked(d)
    hydro = json.load(open(hydro_path, encoding="utf-8")) if hydro_path and os.path.exists(hydro_path) else None
    for name, (x0, z0, x1, z1) in AREAS.items():
        bpp = 4 if (x1 - x0) <= 5200 else 6
        view(b, x0, z0, x1, z1, bpp, hydro=hydro).save(os.path.join(out, f"{tag}_{name}.png"))
    return b


def compare(before, after, out, hydro_path=None):
    os.makedirs(out, exist_ok=True)
    bb, ba = Baked(before), Baked(after)
    hydro = json.load(open(hydro_path, encoding="utf-8")) if hydro_path and os.path.exists(hydro_path) else None
    for name, (x0, z0, x1, z1) in AREAS.items():
        bpp = 4 if (x1 - x0) <= 5200 else 6
        i0 = view(bb, x0, z0, x1, z1, bpp)
        i1 = view(ba, x0, z0, x1, z1, bpp, hydro=hydro)
        w, h = i0.size
        both = Image.new("RGB", (w * 2 + 8, h), (255, 255, 255))
        both.paste(i0, (0, 0))
        both.paste(i1, (w + 8, 0))
        both.save(os.path.join(out, f"compare_{name}.png"))
    # whole-map views of the after state
    a = analyze(after)
    b = a["_b"]
    view(b, MIN_X, MIN_Z, MIN_X + W * CELL, MIN_Z + H * CELL, 16, flow=a["acc"]).save(os.path.join(out, "flow_accumulation.png"))
    view(b, MIN_X, MIN_Z, MIN_X + W * CELL, MIN_Z + H * CELL, 16, hydro=hydro).save(os.path.join(out, "streams_over_biomes.png"))
    view(bb, MIN_X, MIN_Z, MIN_X + W * CELL, MIN_Z + H * CELL, 16).save(os.path.join(out, "before_over_biomes.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["analyze", "render", "compare"])
    ap.add_argument("--dir")
    ap.add_argument("--label", default="")
    ap.add_argument("--json")
    ap.add_argument("--tag", default="view")
    ap.add_argument("--out")
    ap.add_argument("--hydro")
    ap.add_argument("--before")
    ap.add_argument("--after")
    a = ap.parse_args()
    if a.cmd == "analyze":
        r = analyze(a.dir, a.label)
        s = summarize(r)
        txt = json.dumps(s, indent=1)
        if a.json:
            open(a.json, "w", encoding="utf-8").write(txt)
        keys = [k for k in s if k not in ("rivers", "lakes", "unchanneled_trunks_top")]
        print(json.dumps({k: s[k] for k in keys}, indent=1))
        for rv in s["rivers"]:
            print(rv)
    elif a.cmd == "render":
        render(a.dir, a.tag, a.out, a.hydro)
    else:
        compare(a.before, a.after, a.out, a.hydro)


if __name__ == "__main__":
    main()
