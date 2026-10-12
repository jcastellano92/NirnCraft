"""Glue between tamriel_map.py and hydrology.py: place exclusion zones, carving, merging the derived channels into
the river fields, waterfall ranking, and the `hydrology.json` the Java side loads (waterfalls, springs, river sites)."""
import json
import os

import numpy as np

import hydrology as hy
from hydrology import CELL, SEA, smoothstep

SETTLEMENT_TYPES = {"capital", "city", "town", "village", "farm"}


def load_places():
    pdir = os.path.join(hy.RES_DIR, "places")
    out = []
    idx = json.load(open(os.path.join(pdir, "index.json"), encoding="utf-8"))
    for f in idx:
        p = os.path.join(pdir, f)
        if os.path.exists(p):
            out.extend(json.load(open(p, encoding="utf-8")))
    return out


def place_radius(p, tight=False):
    """Footprint (blocks) new water must keep clear of for this place."""
    r = float(p.get("radius", 24 if p.get("type") not in SETTLEMENT_TYPES else 120))
    t = p.get("type")
    if t in SETTLEMENT_TYPES:
        return r * 1.0 + 20 if tight else r * 1.35 + 44
    if t == "fort":
        return r + 36
    if t == "dungeon_fixed":
        return max(r, 18) + 20
    return r + 24


def place_exclusion(grid, places=None, only_settlements=False):
    """Cells that new water must keep out of: every place's footprint plus a margin (settlements most of all)."""
    places = load_places() if places is None else places
    if only_settlements:
        places = [p for p in places if p.get("type") in SETTLEMENT_TYPES]
    ex = np.zeros((grid.h, grid.w), bool)
    for p in places:
        r = place_radius(p, only_settlements)
        x, z = p["x"], p["z"]
        i0 = max(0, int((x - r - grid.min_x) / CELL) - 1)
        i1 = min(grid.w, int((x + r - grid.min_x) / CELL) + 2)
        j0 = max(0, int((z - r - grid.min_z) / CELL) - 1)
        j1 = min(grid.h, int((z + r - grid.min_z) / CELL) + 2)
        if i0 >= i1 or j0 >= j1:
            continue
        dd = np.hypot(grid.xs[i0:i1][None, :] - x, grid.zs[j0:j1][:, None] - z)
        ex[j0:j1, i0:i1] |= dd < r + 12
    return ex


def carve(h, d, hw, lev, land, lake_sd, bank, max_cut=10.0):
    """Lowers the macro height field into a V valley under a stream; the banks never sit below the water."""
    valley = 24.0 + 14.0 * hw
    wv = 1.0 - smoothstep(hw + 2.0, valley, d)
    floor = lev + 1.3 + np.maximum(d - hw - 4.0, 0.0) * 0.3
    hn = np.minimum(h, h + (floor - h) * wv)
    hn = np.maximum(hn, h - np.minimum(max_cut, 2.0 + 3.0 * hw))
    hn = np.where(d < hw + 6.0, np.maximum(hn, lev + bank), hn)
    ok = land & (d < valley) & (lake_sd < -12.0)
    return np.where(ok, hn, h)


def merge_channels(riv_d, riv_hw, riv_level, d, hw, lev):
    """Streams join the river fields where their channel edge is nearer than the authored river's."""
    sd_cur = riv_d - riv_hw
    sd_new = d - hw
    m = (sd_new < sd_cur - 1e-6) & (d < 1e4)
    return np.where(m, d, riv_d), np.where(m, hw, riv_hw), np.where(m, lev, riv_level), m


def integrate(grid, h, land, D, wet, reg, regions, ridge, dunes, riv_d, riv_hw, riv_level, lake_sd, lake_level,
              excl, auth_lines, falls_all, delta_names=None):
    """Derives and rasterises the whole network on top of the authored rivers and lakes (which are never moved).
    Returns a dict: updated h / riv_d / riv_hw / riv_level, the network, falls, springs, deltas and stats."""
    runoff = hy.runoff_weight(grid, reg, regions, h, ridge, dunes, land)
    dense = []
    for l in auth_lines:
        p, lv, _ = hy.densify(l["pts"], l["lev"], l["hw"], 12.0)
        dense.append(np.column_stack([p, lv]))
    rp = np.concatenate(dense)
    excl_s = place_exclusion(grid, only_settlements=True)
    net = hy.derive_streams(grid, h, land, D, wet, runoff, riv_d, riv_hw, riv_level, lake_sd, lake_level, excl, rp,
                            excl_hard=excl_s)
    existing = [(f["x"], f["z"]) for f in falls_all]
    for ln in net.lines:
        lev, fl = hy.add_falls(ln["pts"], ln["lev"], ln["hw"], "stream", hy.FALL_MIN_STREAM, existing)
        ln["lev"] = lev
        for f in fl:
            existing.append((f["x"], f["z"]))
            falls_all.append(f)
    # authored lines as dense dicts for the delta search
    deltas = hy.derive_deltas(grid, auth_lines, D, wet, excl_s, land, delta_names)
    exts = hy.extend_dead_ends(grid, auth_lines, net.recv, net.filled, D, land, lake_sd)
    deltas = deltas + exts
    springs = []
    for ln in net.lines:
        if ln["up"] == 0:
            springs.append([round(ln["head"][0]), round(ln["head"][1]), round(float(ln["lev"][0]), 1),
                            1 if ln["head_h"] > SEA + 90 else 0])
    sd_, shw_, slev_, _ = hy.splat(grid, [(l["pts"], l["lev"], l["hw"]) for l in net.lines])
    h = carve(h, sd_, shw_, slev_, land, lake_sd, np.where(wet > 0.3, 0.5, 1.4))
    riv_d, riv_hw, riv_level, _m1 = merge_channels(riv_d, riv_hw, riv_level, sd_, shw_, slev_)
    if deltas:
        dd_, dhw_, dlev_, _ = hy.splat(grid, [(l["pts"], l["lev"], l["hw"]) for l in deltas])
        h = carve(h, dd_, dhw_, dlev_, land, lake_sd, 0.4, 3.0)
        riv_d, riv_hw, riv_level, _m2 = merge_channels(riv_d, riv_hw, riv_level, dd_, dhw_, dlev_)
    # closed hollows that did not become lakes are filled to their spill height: no dry pits that water could only
    # reach by accident (lake cells, marsh and channels are left alone)
    _r, _o, filled2 = hy.flood(h, ~land)
    dep = filled2 - h
    fillm = land & (dep > 0.4) & (lake_sd < -24.0) & (wet < 0.3) & (riv_d > riv_hw + 12.0)
    h = np.where(fillm, h + np.minimum(dep, 8.0), h)
    filled_cells = int(fillm.sum())
    falls_all.sort(key=lambda f: -(f["up"] - f["down"]) * (1.0 + 0.2 * f["hw"]))
    falls = falls_all[:600]
    for f in falls:
        big = (f["up"] - f["down"]) >= hy.FALL_BIG and f["hw"] >= 1.5
        f["big"] = bool(big)
        f["secret"] = bool(big and hy.hash2(round(f["x"]), round(f["z"]), 41) < 0.25)
    ends = {}
    for ln in net.lines:
        ends[ln["end"]] = ends.get(ln["end"], 0) + 1

    def length(ls):
        return round(sum(float(np.hypot(*(np.diff(l["pts"], axis=0)).T).sum()) for l in ls))

    stats = dict(net.stats)
    stats.update(filled_hollow_cells=filled_cells, extensions=len(exts),
                 level_rises=int(sum(int((np.diff(l["lev"]) > 1e-6).sum()) for l in net.lines)), streams=len(net.lines), stream_length_blocks=length(net.lines), end_kinds=ends, deltas=len(deltas),
                 delta_length_blocks=length(deltas), waterfalls=len(falls), big_falls=sum(1 for f in falls if f["big"]),
                 secret_falls=sum(1 for f in falls if f["secret"]), springs=len(springs),
                 mountain_springs=sum(1 for s in springs if s[3]))
    return dict(h=h, riv_d=riv_d, riv_hw=riv_hw, riv_level=riv_level, net=net, falls=falls, springs=springs,
                deltas=deltas, auth_lines=auth_lines, stats=stats)


# ---------------------------------------------------------------------------------------------------------------------
KINDS = ["fishing_hut", "watermill", "ford", "ferry", "toll_post", "fall_overlook", "fall_secret", "sunken_cache",
         "bank_cache", "hidden_fishing", "spring_shrine"]
KMAX = {"fishing_hut": 90, "watermill": 45, "ford": 70, "ferry": 16, "toll_post": 16, "fall_overlook": 70,
        "fall_secret": 60, "sunken_cache": 40, "bank_cache": 70, "hidden_fishing": 40, "spring_shrine": 60}
KSPACE = {"fishing_hut": 900, "watermill": 1200, "ford": 700, "ferry": 1800, "toll_post": 600, "fall_overlook": 250,
          "fall_secret": 0, "sunken_cache": 500, "bank_cache": 700, "hidden_fishing": 900, "spring_shrine": 500}
GLOBAL_SPACE = 110.0


def _facing(wx, wz):
    if abs(wz) >= abs(wx):
        return 0 if wz > 0 else 2
    return 3 if wx > 0 else 1


def _seg_cross(p, q, r, s):
    d1, d2 = q - p, s - r
    den = d1[0] * d2[1] - d1[1] * d2[0]
    if abs(den) < 1e-9:
        return None
    t = ((r[0] - p[0]) * d2[1] - (r[1] - p[1]) * d2[0]) / den
    u = ((r[0] - p[0]) * d1[1] - (r[1] - p[1]) * d1[0]) / den
    if 0 <= t <= 1 and 0 <= u <= 1:
        return p + d1 * t, t
    return None


def plan_sites(grid, h, D, lake_sd, riv_d, riv_hw, auth_lines, stream_lines, falls, springs, places, roads,
               basin_lakes, lake_polys, excl):
    """River-aware points of interest, rule-checked against the rasters (dry bank, gentle slope, clear of places,
    away from each other). Deterministic: hashes of positions decide which candidates are used."""
    sites = []
    counts = {k: 0 for k in KINDS}
    last = {k: [] for k in KINDS}
    allp = []
    hubs = np.array([[p["x"], p["z"]] for p in places if p.get("type") in ("town", "village", "city", "capital", "farm")])

    def dry(x, z, off_water=5.0, slope_max=0.35):
        if float(grid.bil(D, x, z)) < 40 or float(grid.bil(lake_sd, x, z)) > -14:
            return False
        if float(grid.bil(riv_d, x, z)) < float(grid.bil(riv_hw, x, z)) + off_water:
            return False
        ix, iz = grid.cell_of(x, z)
        if excl[iz, ix]:
            return False
        e = [float(grid.bil(h, x + dx, z + dz)) for dx, dz in ((10, 0), (-10, 0), (0, 10), (0, -10))]
        return max(abs(e[0] - e[1]), abs(e[2] - e[3])) / 20.0 <= slope_max

    def add(kind, x, z, f, p=0.0, force=False):
        if counts[kind] >= KMAX[kind]:
            return False
        if not force:
            if any((x - a) ** 2 + (z - b) ** 2 < KSPACE[kind] ** 2 for a, b in last[kind]):
                return False
            if any((x - a) ** 2 + (z - b) ** 2 < GLOBAL_SPACE ** 2 for a, b in allp):
                return False
        sites.append([KINDS.index(kind), int(round(x)), int(round(z)), int(f), round(float(p), 1)])
        counts[kind] += 1
        last[kind].append((x, z))
        allp.append((x, z))
        return True

    def hub_dist(x, z):
        if not len(hubs):
            return 1e9
        return float(np.hypot(hubs[:, 0] - x, hubs[:, 1] - z).min())

    # secrets and overlooks at waterfalls
    for f in falls:
        n = np.array([-f["uz"], f["ux"]])
        lip = np.array([f["x"], f["z"]])
        if f["secret"]:
            fx = f["ux"]
            fz = f["uz"]
            cx, cz = lip[0], lip[1]
            add("fall_secret", cx, cz, _facing(fx, fz), f["up"] - f["down"], force=True)
        if f["big"]:
            for sgn in (1, -1):
                q = lip - np.array([f["ux"], f["uz"]]) * 6 + n * sgn * (f["hw"] + 7)
                if dry(q[0], q[1], 3.0):
                    add("fall_overlook", q[0], q[1], _facing(-n[0] * sgn, -n[1] * sgn), f["up"] - f["down"])
                    break

    def walk(line, step=40.0, hwmin=1.0):
        pts = np.asarray(line["pts"])
        hw = np.asarray(line["hw"])
        lev = np.asarray(line["lev"])
        if len(pts) < 7:
            return
        s = np.concatenate([[0], np.cumsum(np.hypot(*np.diff(pts, axis=0).T))])
        for t in np.arange(step * 0.5, s[-1] - step * 0.5, step):
            i = int(np.searchsorted(s, t))
            i = min(max(i, 2), len(pts) - 3)
            if hw[i] < hwmin:
                continue
            tan = pts[i + 2] - pts[i - 2]
            tn = np.hypot(*tan)
            if tn < 1e-6:
                continue
            tan = tan / tn
            drop = float(lev[max(0, i - 3)] - lev[min(len(lev) - 1, i + 3)])
            yield pts[i], tan, float(hw[i]), drop

    lines = [(True, l) for l in auth_lines] + [(False, l) for l in stream_lines]
    for auth, line in lines:
        for P, tan, hw, drop in walk(line):
            hsh = hy.hash2(round(P[0]), round(P[1]), 7)
            n = np.array([-tan[1], tan[0]])
            # bank cache / hidden fishing spot / fishing hut: on a dry, gentle bank
            if hw >= 1.8 and (hsh < 0.14 or hy.hash2(round(P[0]), round(P[1]), 8) < 0.1):
                side = 1 if hy.hash2(round(P[0]), round(P[1]), 9) < 0.5 else -1
                for sg in (side, -side):
                    q = P + n * sg * (hw + 8 + 6 * hy.hash2(round(P[0]), round(P[1]), 10))
                    if dry(q[0], q[1], 4.0, 0.22):
                        face = _facing(-n[0] * sg, -n[1] * sg)
                        r = hy.hash2(round(q[0]), round(q[1]), 11)
                        if hw >= 3.0 and r < 0.5:
                            add("fishing_hut", q[0], q[1], face, hw)
                        elif r < 0.78:
                            add("bank_cache", q[0], q[1], face, hw)
                        else:
                            add("hidden_fishing", q[0], q[1], face, hw)
                        break
            # mills: a stream with some fall, a few hundred blocks from a village
            if 2.0 <= hw <= 5.5 and drop >= 1.2 and 220 <= hub_dist(P[0], P[1]) <= 1700 and hsh > 0.7:
                for sg in (1, -1):
                    q = P + n * sg * (hw + 7)
                    if dry(q[0], q[1], 4.0, 0.25):
                        add("watermill", q[0], q[1], _facing(-n[0] * sg, -n[1] * sg), hw)
                        break
            # fords: gentle, shallow-ish stretches near settlements
            if 1.5 <= hw <= 4.5 and drop <= 2.5 and 150 <= hub_dist(P[0], P[1]) <= 1100 and 0.35 < hsh < 0.5:
                if float(grid.bil(D, P[0], P[1])) > 60:
                    add("ford", P[0], P[1], _facing(tan[0], tan[1]), hw)
            # ferries on the wide rivers
            if auth and hw >= 8.0 and 0.5 < hsh < 0.62:
                for sg in (1, -1):
                    q = P + n * sg * (hw + 5)
                    if dry(q[0], q[1], 3.0, 0.3):
                        add("ferry", q[0], q[1], _facing(-n[0] * sg, -n[1] * sg), hw)
                        break
            # a deep pool: something sunk in it
            if hw >= 6.0 and hsh > 0.93:
                add("sunken_cache", P[0], P[1], 0, hw)
    # toll posts: authored roads crossing wide rivers
    for rd in roads:
        rp = np.array([p[:2] for p in rd["line"]], float)
        for a in range(len(rp) - 1):
            for line in auth_lines:
                lp = np.asarray(line["pts"])
                # coarse reject
                if (min(rp[a][0], rp[a + 1][0]) > lp[:, 0].max() + 60 or max(rp[a][0], rp[a + 1][0]) < lp[:, 0].min() - 60
                        or min(rp[a][1], rp[a + 1][1]) > lp[:, 1].max() + 60 or max(rp[a][1], rp[a + 1][1]) < lp[:, 1].min() - 60):
                    continue
                for b in range(0, len(lp) - 1, 2):
                    c = _seg_cross(rp[a], rp[a + 1], lp[b], lp[min(b + 2, len(lp) - 1)])
                    if c is None:
                        continue
                    X, t = c
                    hwx = float(line["hw"][b])
                    if hwx < 4.5:
                        continue
                    d = rp[a + 1] - rp[a]
                    dn = np.hypot(*d)
                    if dn < 1:
                        continue
                    d = d / dn
                    nn = np.array([-d[1], d[0]])
                    for off in (hwx + 14, -(hwx + 14)):
                        q = X + d * off + nn * 9
                        if dry(q[0], q[1], 3.0, 0.3):
                            add("toll_post", q[0], q[1], _facing(-nn[0], -nn[1]), hwx)
                            break
    # lakes: a sunken cache in the deep, a fishing hut or hidden spot on a shore
    for bl in basin_lakes:
        q = (bl["cx"], bl["cz"])
        if float(grid.bil(lake_sd, q[0], q[1])) > 12 and hy.hash2(round(q[0]), round(q[1]), 12) < 0.7:
            add("sunken_cache", q[0], q[1], 0, 0.0)
    for lp in lake_polys:
        pts = np.array(lp["poly"], float)
        if len(pts) < 3:
            continue
        cx, cz = pts.mean(axis=0)
        if float(grid.bil(lake_sd, cx, cz)) > 14:
            add("sunken_cache", cx, cz, 0, 0.0)
        for k in range(len(pts)):
            a = pts[k]
            hsh = hy.hash2(round(a[0]), round(a[1]), 13)
            if hsh > 0.35:
                continue
            out = a - np.array([cx, cz])
            on = np.hypot(*out)
            if on < 1:
                continue
            q = a + out / on * 24
            if dry(q[0], q[1], 3.0, 0.25):
                add("fishing_hut" if hsh < 0.18 else "hidden_fishing", q[0], q[1], _facing(-out[0], -out[1]), 0.0)
    # springs: the mountain-fed heads get a little mossy basin
    for sp in springs:
        if sp[3] and hy.hash2(sp[0], sp[1], 14) < 0.25 and dry(sp[0], sp[1], -9.0, 0.6):
            add("spring_shrine", sp[0], sp[1], int(hy.hash2(sp[0], sp[1], 15) * 4), 0.0)
    return sites, counts


def hydrology_json(hyd, sites, counts):
    falls = [[round(f["x"]), round(f["z"]), round(f["ux"], 3), round(f["uz"], 3), round(f["up"], 1), round(f["down"], 1),
              round(f["hw"], 1), 1 if f["big"] else 0, 1 if f["secret"] else 0] for f in hyd["falls"]]
    return {"_comment": "Written by tools/worldgen/tamriel_map.py (hydrology). waterfalls: [x, z, flowX, flowZ, "
                        "upstreamLevel, downstreamLevel, halfWidth, big, secret]; springs: [x, z, level, mountain]; "
                        "sites: [kind index into kinds, x, z, facing 0=S 1=W 2=N 3=E (towards the water), param].",
            "kinds": KINDS, "waterfalls": falls, "springs": hyd["springs"], "sites": sites, "stats": hyd["stats"],
            "site_counts": counts}
