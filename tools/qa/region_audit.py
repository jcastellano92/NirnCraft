"""Region variety + border transition audit (data only, no Minecraft).

Usage: py -3 -I tools/qa/region_audit.py [--json OUT.json]

Reads the Tamriel rasters (mod/src/main/resources/nirncraft/tamriel), regions.json, palettes.json, the biome and
feature JSON, structure JSON and the place lists, and prints/dumps:

* per-region variety (distinct surface biomes, tree + flora species, structure kinds, place types and places per
  1000 km2, land elevation spread) and a 0-100 variety score;
* per adjacent land-region pair: border length, straight (collinear) share, climate/palette/terrain jump across the
  border, whether a shared/ecotone biome exists; a 0-10 seam score; plus checks for desert<->snow, jungle<->tundra
  and swamp<->mountain adjacency.

tools/qa/region_render.py draws the maps and transect strips from the JSON this writes.
Only reads repository files; writes only the JSON path given (default tools/qa/out/regions/audit.json).
"""
import glob
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict

import numpy as np
from PIL import Image
from scipy import ndimage
from scipy.spatial import cKDTree

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.environ.get("REGION_AUDIT_ROOT") or os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
TAM = os.path.join(RES, "nirncraft", "tamriel")
DATA = os.path.join(RES, "data", "nirncraft", "worldgen")
VEC = os.path.join(ROOT, "tools", "worldgen", "tamriel_vectors.json")
OUT = os.environ.get("REGION_AUDIT_OUT") or os.path.join(HERE, "out", "regions")

META = json.load(open(VEC, encoding="utf-8"))["meta"]
CELL, MIN_X, MIN_Z = META["cell"], META["min_x"], META["min_z"]


def block_xy(col, row):
    return int(MIN_X + (col + 0.5) * CELL), int(MIN_Z + (row + 0.5) * CELL)


def load_rasters():
    reg = np.array(Image.open(os.path.join(TAM, "regions.png")))
    elev = np.array(Image.open(os.path.join(TAM, "elevation.png"))).astype(np.float32) / 32.0 - 64.0
    ter = np.array(Image.open(os.path.join(TAM, "terrain.png")))
    feat = np.array(Image.open(os.path.join(TAM, "features.png")))
    coast = np.array(Image.open(os.path.join(TAM, "coast.png"))).astype(np.float32) - 32768.0
    return reg, elev, ter, feat, coast


# ---------------------------------------------------------------------------------------------------- biomes
def load_biomes():
    out = {}
    for f in glob.glob(os.path.join(DATA, "biome", "*.json")):
        j = json.load(open(f, encoding="utf-8"))
        out["nirncraft:" + os.path.basename(f)[:-5]] = j
    return out


TREE_FEATS = {"oak", "fancy_oak", "birch", "dark_oak", "spruce", "pine", "mega_spruce", "mega_pine", "acacia",
              "jungle_tree", "mega_jungle_tree", "jungle_bush", "mangrove", "tall_mangrove", "cherry"}
PLANT_WORDS = ("flower", "tulip", "poppy", "orchid", "fern", "grass", "bush", "mushroom", "cactus", "bamboo", "lily",
                "berry", "dandelion", "allium", "bluet", "cornflower", "oxeye", "peony", "lilac", "rose", "sunflower",
                "torchflower", "sugar_cane", "pumpkin", "reeds", "nirnroot", "nightshade", "deathbell", "mirriam",
                "scathecraw", "harrada", "bloodgrass", "dementia_bulb", "mania_bloom", "wither_rose", "moss_carpet",
                "azalea", "crystal_cluster", "cap", "dead_bush", "glow_lichen", "kelp", "seagrass", "coral")
_feat_cache = {}


def feature_names(fid, depth=0):
    """(tree species, plant species) a placed/configured feature id can produce (recursive through JSON refs)."""
    if fid in _feat_cache:
        return _feat_cache[fid]
    trees, plants = set(), set()
    _feat_cache[fid] = (trees, plants)
    ns, _, path = fid.partition(":")
    if ns == "minecraft":
        base = re.sub(r"(_checked|_tree|_bees.*)$", "", path)
        if base in TREE_FEATS or path in TREE_FEATS:
            trees.add("vanilla:" + base)
        return trees, plants
    for kind in ("placed_feature", "configured_feature"):
        p = os.path.join(DATA, kind, path + ".json")
        if os.path.exists(p):
            s = open(p, encoding="utf-8").read()
            for n in re.findall(r'"Name":\s*"([^"]+)"', s):
                short = n.split(":")[1]
                if short.endswith("_leaves"):
                    trees.add(n)
                elif any(w in short for w in PLANT_WORDS) and "ore" not in short.split("_"):
                    plants.add(n)
            for sp in re.findall(r'"species":\s*"([a-z_0-9]+)"', s):
                trees.add("nirncraft:" + sp)
            for ref in set(re.findall(r'"([a-z_]+:[a-z0-9_]+/[a-z0-9_/]+)"', s)):
                if ref != fid and depth < 6 and ref.split(":")[0] in ("nirncraft", "minecraft"):
                    t, pl = feature_names(ref, depth + 1)
                    trees |= t
                    plants |= pl
    return trees, plants


_biome_flora = {}


def biome_flora(bid, biomes):
    if bid not in _biome_flora:
        trees, plants = set(), set()
        for step in biomes[bid]["features"]:
            for f in step:
                if f.startswith("nirncraft:biome/") or f.split(":")[0] == "minecraft":
                    t, p = feature_names(f)
                    trees |= t
                    plants |= p
        _biome_flora[bid] = (trees, plants)
    return _biome_flora[bid]


# ---------------------------------------------------------------------------------------------------- palettes
SURF = ["lowland", "upland", "peak", "summit"]
EXTRA = ["beach", "river", "lake", "marsh"]


def resolve_palettes():
    pj = json.load(open(os.path.join(TAM, "palettes.json"), encoding="utf-8"))["palettes"]

    def get(key, field, default):
        k = key
        while k:
            p = pj[k]
            if field in p:
                return p[field]
            k = p.get("fallback")
        return default

    def ctx(key, c):
        k = key
        while k:
            p = pj[k]
            if c in p["contexts"]:
                return p["contexts"][c]
            k = p.get("fallback")
        return []

    out = {}
    for key in pj:
        out[key] = {"upland_y": get(key, "upland_y", 115), "peak_y": get(key, "peak_y", 175),
                    "summit_y": get(key, "summit_y", 270), "bias": pj[key].get("bias"),
                    "micro": pj[key].get("micro", []),
                    "ctx": {c: ctx(key, c) for c in SURF + EXTRA}}
    return out


def cell_dist(pal, h, x, z, biomes):
    """Probability of each biome in a land cell of height h at (x, z): the palette's weighted walk with its
    geographic bias applied (mirrors Palettes.Palette.bias; v is uniform on [0, 1))."""
    c = "lowland" if h < pal["upland_y"] else "upland" if h < pal["peak_y"] else "peak" if h < pal["summit_y"] \
        else "summit"
    ch = [e for e in pal["ctx"][c] if e["biome"] in biomes]
    tot = float(sum(e["weight"] for e in ch)) or 1.0
    edges = np.cumsum([0.0] + [e["weight"] / tot for e in ch])
    vs = (np.arange(48) + 0.5) / 48.0
    b = pal.get("bias") or {}
    if "linear" in b:
        lx, lz, dx, dz, span, shift = b["linear"]
        vs = vs + shift * max(-1.0, min(1.0, ((x - lx) * dx + (z - lz) * dz) / span))
    if "radial" in b:
        rx, rz, inner, outer, scale = b["radial"]
        t = max(0.0, min(1.0, (math.hypot(x - rx, z - rz) - inner) / max(outer - inner, 1.0)))
        vs = vs * (scale + (1 - scale) * t * t * (3 - 2 * t))
    vs = np.clip(vs, 0.0, 0.99999)
    idx = np.clip(np.searchsorted(edges, vs, side="right") - 1, 0, len(ch) - 1)
    out = Counter()
    for i in idx:
        out[ch[int(i)]["biome"]] += 1.0 / len(vs)
    return out


def side_dist(pal, cells, elev, biomes):
    acc = Counter()
    for r, c in cells:
        x, z = block_xy(c, r)
        for k, w in cell_dist(pal, float(elev[r, c]), x, z, biomes).items():
            acc[k] += w
    n = sum(acc.values()) or 1.0
    return {k: v / n for k, v in acc.items()}


def palette_for(region, pals):
    for k in (region["id"], region["palette"], "province/" + region["province"], "default"):
        if k in pals:
            return k, pals[k]
    return "default", pals["default"]


# ---------------------------------------------------------------------------------------------------- structures
def structure_kinds():
    by_scope = defaultdict(set)
    for f in glob.glob(os.path.join(DATA, "structure", "**", "*.json"), recursive=True):
        j = json.load(open(f, encoding="utf-8"))
        name = os.path.relpath(f, os.path.join(DATA, "structure")).replace("\\", "/")[:-5]
        for w in (j.get("where") or ["*"]):
            by_scope[w].add(name)
    return by_scope


def load_places():
    out = []
    d = os.path.join(TAM, "places")
    for f in glob.glob(os.path.join(d, "*.json")):
        if os.path.basename(f) == "index.json":
            continue
        j = json.load(open(f, encoding="utf-8"))
        if isinstance(j, list):
            out += [p for p in j if isinstance(p, dict) and "x" in p]
    return out


# ---------------------------------------------------------------------------------------------------- analysis
PAL_BY_INDEX = {}


def analyse():
    reg, elev, ter, feat, coast = load_rasters()
    regions = json.load(open(os.path.join(TAM, "regions.json"), encoding="utf-8"))
    biomes = load_biomes()
    pals = resolve_palettes()
    scopes = structure_kinds()
    places = load_places()
    H, W = reg.shape
    cell_km2 = (CELL / 1000.0) ** 2
    is_land = np.array([r["kind"] != "sea" for r in regions])

    # places per region (by nearest raster cell)
    place_types = defaultdict(Counter)
    for p in places:
        c = int((p["x"] - MIN_X) // CELL)
        r = int((p["z"] - MIN_Z) // CELL)
        if 0 <= c < W and 0 <= r < H:
            place_types[int(reg[r, c])][p.get("type", "?")] += 1

    rows = []
    for r in regions:
        i = r["index"]
        if not is_land[i]:
            continue
        mask = reg == i
        n = int(mask.sum())
        if n == 0:
            continue
        pk, pal = palette_for(r, pals)
        PAL_BY_INDEX[i] = pal
        h = elev[mask]
        land = h[h > 63]
        # share of the region's cells in each surface context (lowland/upland/peak/summit)
        shares = {
            "lowland": float((h < pal["upland_y"]).mean()),
            "upland": float(((h >= pal["upland_y"]) & (h < pal["peak_y"])).mean()),
            "peak": float(((h >= pal["peak_y"]) & (h < pal["summit_y"])).mean()),
            "summit": float((h >= pal["summit_y"]).mean())}
        dist = Counter()
        temps = []
        for c, sh in shares.items():
            ch = pal["ctx"][c]
            tw = sum(x["weight"] for x in ch) or 1
            for x in ch:
                dist[x["biome"]] += sh * x["weight"] / tw
        biome_set = set(dist)
        for c in EXTRA:
            for x in pal["ctx"][c]:
                biome_set.add(x["biome"])
        for mr in pal.get("micro", []):
            if mr.get("biome"):
                biome_set.add(mr["biome"])
        biome_set = {b for b in biome_set if b in biomes}
        major = {b: w for b, w in dist.items() if b in biomes}
        trees, plants = set(), set()
        for b in biome_set:
            t, p = biome_flora(b, biomes)
            trees |= t
            plants |= p
        prov = r["province"]
        kinds = set(scopes.get(prov, set())) | set(scopes.get(r["id"], set()))
        generic = len(scopes.get("*", set()))
        pt = place_types.get(i, Counter())
        tw = sum(major.values()) or 1
        temp = sum(biomes[b]["temperature"] * w for b, w in major.items()) / tw
        down = sum(biomes[b]["downfall"] * w for b, w in major.items()) / tw
        km2 = n * cell_km2
        rows.append({
            "index": i, "id": r["id"], "province": prov, "culture": r["culture"], "cells": n, "km2": round(km2, 1),
            "palette": pk, "biomes": sorted(biome_set), "n_biomes": len(biome_set),
            "n_micro": len(pal.get("micro", [])),
            "n_major_biomes": sum(1 for w in major.values() if w >= 0.04),
            "trees": sorted(trees), "plants": sorted(plants), "n_trees": len(trees), "n_plants": len(plants),
            "struct_specific": sorted(kinds), "n_struct_specific": len(kinds), "n_struct_generic": generic,
            "place_types": dict(pt), "n_places": int(sum(pt.values())),
            "places_per_1000km2": round(1000 * sum(pt.values()) / km2, 2) if km2 else 0,
            "elev_p5": round(float(np.percentile(h, 5)), 0), "elev_p95": round(float(np.percentile(h, 95)), 0),
            "elev_range": round(float(np.percentile(h, 95) - np.percentile(h, 5)), 0),
            "temp": round(temp, 2), "downfall": round(down, 2),
            "ridge": round(float(feat[..., 1][mask].mean()), 0), "hi_share": round(float((h >= 150).mean()), 2),
            "rough": round(float(ter[..., 0][mask].mean()), 0), "crag": round(float(ter[..., 1][mask].mean()), 0),
            "dunes": round(float(ter[..., 2][mask].mean()), 0), "wet": round(float(ter[..., 3][mask].mean()), 0),
            "dist": {b: round(w, 3) for b, w in sorted(major.items(), key=lambda kv: -kv[1])},
        })

    # variety score: mean of min-max-normalised components (per the current set of land regions)
    comps = [("n_major_biomes", 1.5), ("n_biomes", 1.0), ("n_trees", 1.0), ("n_plants", 1.0),
             ("n_struct_specific", 1.0), ("n_places", 0.7), ("places_per_1000km2", 0.7), ("elev_range", 0.8)]
    for key, _ in comps:
        vals = np.array([row[key] for row in rows], dtype=float)
        lo, hi = vals.min(), np.percentile(vals, 95)
        for row, v in zip(rows, vals):
            row.setdefault("_norm", {})[key] = float(np.clip((v - lo) / (hi - lo + 1e-9), 0, 1))
    wsum = sum(w for _, w in comps)
    for row in rows:
        row["variety"] = round(100 * sum(row["_norm"][k] * w for k, w in comps) / wsum, 1)
        del row["_norm"]

    borders = analyse_borders(reg, elev, ter, regions, rows, biomes, is_land)
    return rows, borders


def region_profile(row):
    return row["dist"], row["temp"], row["downfall"]


STRAIGHT_SIGMA = 0.5  # cells of perpendicular scatter over a +-10 cell (640 block) window


def family(bid, biomes):
    j = biomes.get(bid)
    if j is None:
        return "other"
    t, d = j["temperature"], j["downfall"]
    n = bid.split(":")[1]
    if t <= 0.1 or "ice" in n or "snow" in n or "glacier" in n or "frozen" in n:
        return "snow"
    if re.search(r"marsh|swamp|mire|mangrove|fen|delta|shadowfen", n):
        return "swamp"
    if t >= 1.4 and d <= 0.3:
        return "desert"
    if t >= 0.95 and d >= 0.8:
        return "jungle"
    if t <= 0.45:
        return "tundra"
    return "temperate"


def analyse_borders(reg, elev, ter, regions, rows, biomes, is_land):
    H, W = reg.shape
    by_id = {r["index"]: r for r in rows}
    pair_cells = defaultdict(list)  # (a,b) -> list of (row, col) midpoints (float) of the border edges
    # horizontal neighbours (boundary between col c and c+1) and vertical (between row r and r+1)
    for axis in (0, 1):
        if axis == 0:
            a, b = reg[:, :-1], reg[:, 1:]
        else:
            a, b = reg[:-1, :], reg[1:, :]
        diff = (a != b) & (a > 0) & (b > 0)
        rr, cc = np.nonzero(diff)
        for r, c in zip(rr, cc):
            ia, ib = int(a[r, c]), int(b[r, c])
            if ia not in by_id or ib not in by_id:
                continue
            key = (min(ia, ib), max(ia, ib))
            if axis == 0:
                pair_cells[key].append((r, c + 0.5))
            else:
                pair_cells[key].append((r + 0.5, c))
    out = []
    for (ia, ib), pts in pair_cells.items():
        pts = np.array(pts, dtype=float)
        n = len(pts)
        length = n * CELL
        if length < 4 * CELL:
            continue
        # straightness: local PCA on a +-6 cell window; straight if perpendicular scatter < 0.45 cell
        tree = cKDTree(pts)
        straight = np.zeros(n, bool)
        axis_aligned = np.zeros(n, bool)
        for k, nb in enumerate(tree.query_ball_point(pts, 10.0)):
            if len(nb) < 14:
                continue
            q = pts[nb] - pts[nb].mean(0)
            w, v = np.linalg.eigh(q.T @ q / len(nb))
            span = np.sqrt(w[1]) * 3.46  # ~ length of a uniform segment
            if np.sqrt(max(w[0], 0)) < STRAIGHT_SIGMA and span >= 14:
                straight[k] = True
                d = np.abs(v[:, 1])
                axis_aligned[k] = d.max() > 0.97
        frac_straight = float(straight.mean())
        frac_axis = float(axis_aligned.mean())
        A, B = by_id[ia], by_id[ib]
        # biome / climate distance
        # the land right at the border on each side (palette walk + bias at those cells)
        sa, sb = [], []
        for (r, c) in pts[:: max(1, n // 80)]:
            if abs(r - round(r)) > 0.1:
                cells = ((int(r - 0.5), int(c)), (int(r + 0.5), int(c)))
            else:
                cells = ((int(r), int(c - 0.5)), (int(r), int(c + 0.5)))
            for rc in cells:
                v = int(reg[rc])
                if v == ia:
                    sa.append(rc)
                elif v == ib:
                    sb.append(rc)
        da = side_dist(PAL_BY_INDEX[ia], sa, elev, biomes) if sa else A["dist"]
        db = side_dist(PAL_BY_INDEX[ib], sb, elev, biomes) if sb else B["dist"]
        keys = set(da) | set(db)
        overlap = sum(min(da.get(k, 0), db.get(k, 0)) for k in keys)
        # expected contrast between two random land points either side of the border (0 same biome .. 1 opposite)
        gap = 0.0
        for ka, wa in da.items():
            for kb, wb in db.items():
                if ka == kb or ka not in biomes or kb not in biomes:
                    continue
                d = 0.2 + 0.5 * abs(biomes[ka]["temperature"] - biomes[kb]["temperature"]) +                     0.8 * abs(biomes[ka]["downfall"] - biomes[kb]["downfall"])
                gap += wa * wb * min(d, 1.0)
        shared_any = len(set(A["biomes"]) & set(B["biomes"]))

        def climate(d):
            w = sum(d.values()) or 1.0
            return (sum(biomes[k]["temperature"] * v for k, v in d.items() if k in biomes) / w,
                    sum(biomes[k]["downfall"] * v for k, v in d.items() if k in biomes) / w)
        (ta, wa_), (tb, wb_) = climate(da), climate(db)
        dT = abs(ta - tb)
        dD = abs(wa_ - wb_)
        na = nb_ = 1.0
        fa = Counter()
        fb = Counter()
        for k, w in da.items():
            fa[family(k, biomes)] += w / na
        for k, w in db.items():
            fb[family(k, biomes)] += w / nb_
        fam_a = fa.most_common(1)[0][0] if fa else "other"
        fam_b = fb.most_common(1)[0][0] if fb else "other"
        # height step across the border (2 cells each side, measured on the raster)
        step = []
        for (r, c) in pts[:: max(1, n // 60)]:
            if abs(r - round(r)) > 0.1:   # vertical boundary between rows floor(r) and floor(r)+1
                r0 = int(r - 0.5)
                c0 = int(c)
                ya = elev[max(r0 - 1, 0):r0 + 1, c0].mean()
                yb = elev[r0 + 1:r0 + 3, c0].mean()
            else:
                r0 = int(r)
                c0 = int(c - 0.5)
                ya = elev[r0, max(c0 - 1, 0):c0 + 1].mean()
                yb = elev[r0, c0 + 1:c0 + 3].mean()
            step.append(abs(ya - yb))
        hstep = float(np.mean(step)) if step else 0.0
        drough = abs(A["rough"] - B["rough"])
        dwet = abs(A["wet"] - B["wet"])
        # the "jump": palette disjointness + climate + terrain, 0..10
        jump = 4.0 * gap + 2.2 * min(dT / 1.0, 1.5) + 1.2 * min(dD / 0.5, 1.5) + \
            1.0 * min(hstep / 40.0, 1.5) + 0.6 * min(drough / 60.0, 1.5) + 0.6 * min(dwet / 80.0, 1.5)
        ecotone = overlap >= 0.10
        seam = jump * (0.55 + 0.9 * frac_straight) * (0.7 if ecotone else 1.0) * min(1.0, np.sqrt(length / 1500.0))
        # coordinates: centre of the longest straight stretch, else border centroid
        sel = pts[straight] if straight.any() else pts
        cr, cc2 = np.median(sel[:, 0]), np.median(sel[:, 1])
        x, z = block_xy(cc2, cr)
        ext = [block_xy(pts[:, 1].min(), pts[:, 0].min()), block_xy(pts[:, 1].max(), pts[:, 0].max())]
        out.append({
            "a": A["id"], "b": B["id"], "ia": ia, "ib": ib, "length_blocks": int(length),
            "straight_frac": round(frac_straight, 2), "axis_frac": round(frac_axis, 2),
            "overlap": round(overlap, 2), "gap": round(gap, 2), "shared_biomes": shared_any, "ecotone": bool(ecotone),
            "d_temp": round(dT, 2), "d_down": round(dD, 2), "fam_a": fam_a, "fam_b": fam_b,
            "height_step": round(hstep, 0), "d_rough": round(drough, 0), "jump": round(jump, 2),
            "seam": round(float(seam), 2), "at": [x, z], "bbox": [ext[0][0], ext[0][1], ext[1][0], ext[1][1]],
            "tags": [],
        })
        # specific checks
        pair = {fam_a, fam_b}
        if pair == {"desert", "snow"} or (dT >= 1.8):
            out[-1]["tags"].append("desert<->snow")
        if pair == {"jungle", "tundra"} or pair == {"jungle", "snow"}:
            out[-1]["tags"].append("jungle<->tundra")
        mtn = [x for x in (A, B) if x["hi_share"] >= 0.3 or x["ridge"] >= 70]
        if "swamp" in pair and mtn and hstep > 20 and ((fam_a == "swamp") != (fam_b == "swamp")):
            out[-1]["tags"].append("swamp<->mountain")
        out[-1]["tags"] = sorted(set(out[-1]["tags"]))
    out.sort(key=lambda r: -r["seam"])
    return out


# ---------------------------------------------------------------------------------------------------- MS21 micro-biomes
ASPECT_BOUNDS = {
    "any": (0.0, 360.0),
    "N": (300.0, 60.0),
    "NE": (345.0, 105.0),
    "E": (30.0, 150.0),
    "SE": (75.0, 195.0),
    "S": (120.0, 240.0),
    "SW": (165.0, 285.0),
    "W": (210.0, 330.0),
    "NW": (255.0, 15.0),
}


def _hash_unit_vec(x, z, salt):
    x64 = np.asarray(x, dtype=np.int64)
    z64 = np.asarray(z, dtype=np.int64)
    h = (x64 * 374761393 + z64 * 668265263 + int(salt) * 1442695041) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = (h ^ (h >> 16)) & 0xFFFFFFFF
    return ((h & 0xFFFF) / 32767.5) - 1.0


def _micro_noise(qx, qz, salt, step=6):
    gx = np.floor_divide(qx, step)
    gz = np.floor_divide(qz, step)
    tx = (qx - gx * step) / float(step)
    tz = (qz - gz * step) / float(step)
    tx = tx * tx * (3.0 - 2.0 * tx)
    tz = tz * tz * (3.0 - 2.0 * tz)
    v00 = _hash_unit_vec(gx, gz, salt)
    v10 = _hash_unit_vec(gx + 1, gz, salt)
    v01 = _hash_unit_vec(gx, gz + 1, salt)
    v11 = _hash_unit_vec(gx + 1, gz + 1, salt)
    return (1.0 - tz) * ((1.0 - tx) * v00 + tx * v10) + tz * ((1.0 - tx) * v01 + tx * v11)


def _eval_hold_rules(rules, qx, qz, e, sl, ax, az, rs, ht, wd, cd, rg):
    n = len(qx)
    out = np.full(n, len(rules) - 1, dtype=np.int32)
    matched = np.zeros(n, dtype=bool)
    n701 = _micro_noise(qx, qz, 701)
    n702 = _micro_noise(qx, qz, 702)
    eff_ax = ax * sl + n701 * 0.14
    eff_az = az * sl + n702 * 0.14
    aspect_deg = np.degrees(np.arctan2(eff_ax, -eff_az)) % 360.0

    for k, rule in enumerate(rules):
        w = rule.get("when") or {}
        d = _micro_noise(qx, qz, k * 31 + 17)
        m = ~matched
        if "elev" in w:
            lo, hi = w["elev"]
            ev = e + d * 8.0
            m &= (ev >= lo) & (ev <= hi)
        if "slope" in w:
            lo, hi = w["slope"]
            sv = np.maximum(0.0, sl + d * 0.035)
            m &= (sv >= lo) & (sv <= hi)
        if "rainshadow" in w:
            lo, hi = w["rainshadow"]
            rv = rs + d * 0.05
            m &= (rv >= lo) & (rv <= hi)
        if "heat" in w:
            lo, hi = w["heat"]
            hv = np.where(ht > 0.01, ht + d * 0.03, 0.0)
            m &= (hv >= lo) & (hv <= hi)
        if "water_dist" in w:
            lo, hi = w["water_dist"]
            wv = np.maximum(0.0, wd + d * 12.0)
            m &= (wv >= lo) & (wv <= hi)
        if "coast_dist" in w:
            lo, hi = w["coast_dist"]
            cv = np.maximum(0.0, cd + d * 24.0)
            m &= (cv >= lo) & (cv <= hi)
        if "ridge" in w:
            lo, hi = w["ridge"]
            rgv = rg + d * 0.04
            m &= (rgv >= lo) & (rgv <= hi)
        if "aspect" in w:
            asp = w["aspect"]
            alo, ahi = ASPECT_BOUNDS.get(asp, (0.0, 360.0)) if isinstance(asp, str) else (float(asp[0]), float(asp[1]))
            if not (alo <= 0.0 and ahi >= 360.0):
                ad = (aspect_deg + d * 18.0) % 360.0
                if alo <= ahi:
                    m &= (ad >= alo) & (ad <= ahi)
                else:
                    m &= (ad >= alo) | (ad <= ahi)
        out[m] = k
        matched |= m
    return out


def audit_micro(province=None):
    if HERE not in sys.path:
        sys.path.insert(0, HERE)
    import roadplan_lint
    import worldsheet_lint

    regions = json.load(open(os.path.join(TAM, "regions.json"), encoding="utf-8"))
    reg_to_idx = {r["id"]: r["index"] for r in regions}
    idx_to_id = {r["index"]: r["id"] for r in regions}
    reg_meta = {r["id"]: r for r in regions}
    pj = json.load(open(os.path.join(TAM, "palettes.json"), encoding="utf-8"))["palettes"]

    target_holds = {}
    for r in regions:
        if r["kind"] == "sea":
            continue
        if province and r["province"] != province:
            continue
        rid = r["id"]
        if rid in pj and pj[rid].get("micro"):
            target_holds[rid] = pj[rid]["micro"]

    if not target_holds:
        return {"holds": {}, "road_transitions_per_km": 0.0}, []

    elev = np.array(Image.open(os.path.join(TAM, "elevation.png")), dtype=np.float32) / 32.0 - 64.0
    reg = np.array(Image.open(os.path.join(TAM, "regions.png")))
    feat = np.array(Image.open(os.path.join(TAM, "features.png")), dtype=np.float32)
    coast = np.array(Image.open(os.path.join(TAM, "coast.png")), dtype=np.float32) - 32768.0
    wat = np.array(Image.open(os.path.join(TAM, "water.png")), dtype=np.float32)
    rs_path = os.path.join(TAM, "rainshadow.png")
    ht_path = os.path.join(TAM, "heat.png")
    rs_img = np.array(Image.open(rs_path), dtype=np.float32) / 255.0 if os.path.exists(rs_path) else np.zeros_like(elev)
    ht_img = np.array(Image.open(ht_path), dtype=np.float32) / 255.0 if os.path.exists(ht_path) else np.zeros_like(elev)

    peaks_path = os.path.join(TAM, "peaks.json")
    H, W = elev.shape
    xs_c = MIN_X + (np.arange(W) + 0.5) * CELL
    zs_c = MIN_Z + (np.arange(H) + 0.5) * CELL
    GX, GZ = np.meshgrid(xs_c, zs_c)
    road_ch = feat[:, :, 3] / 255.0
    if os.path.exists(peaks_path):
        for pk in json.load(open(peaks_path, encoding="utf-8")).get("peaks", []):
            dx, dz = GX - pk["x"], GZ - pk["z"]
            r_dist = np.hypot(dx, dz)
            th = np.arctan2(dz, dx)
            reach = pk["reach"] * (1 + 0.06 * (0.5 * np.sin(3 * th + 1.3) + 0.3 * np.sin(7 * th + 0.4) + 0.2 * np.sin(11 * th + 2.1)))
            for ang, width, gain in pk["spurs"]:
                da = np.degrees(np.angle(np.exp(1j * (th - np.radians(ang)))))
                reach = reach * (1 + gain * np.exp(-(da / width) ** 2))
            u = np.clip(r_dist / reach, 0, 1)
            prof = ((1 - u ** pk.get("shape", 1.0)) ** pk["power"]) * (1 - 0.85 * road_ch)
            hh, hr = pk.get("horn", [0, 1])
            y = elev + np.maximum(pk["height"] - hh - elev, 0) * prof
            if hr > 0:
                y = y + hh * (np.clip(1 - r_dist / hr, 0, 1) ** 1.5)
            elev = np.where(r_dist < reach, y, elev)

    gz_grad, gx_grad = np.gradient(elev, float(CELL))
    slope_grid = np.hypot(gx_grad, gz_grad)
    norm_g = np.maximum(slope_grid * CELL, 1e-4)
    ax_grid = np.where(slope_grid * CELL > 1e-4, -gx_grad * CELL / norm_g, 0.0)
    az_grid = np.where(slope_grid * CELL > 1e-4, -gz_grad * CELL / norm_g, 0.0)

    riv_d = (255.0 - feat[:, :, 0]) * 0.5
    riv_d = np.where(riv_d >= 127.5, 400.0, riv_d)
    riv_hw = wat[:, :, 2]
    lake_sd = (feat[:, :, 2] - 128.0) * 0.5
    lake_sd = np.where(lake_sd <= -64.0, -400.0, lake_sd)
    water_dist_grid = np.clip(np.minimum(np.maximum(0.0, riv_d - riv_hw), np.where(lake_sd >= 0.0, 0.0, -lake_sd)), 0.0, 512.0)
    coast_dist_grid = np.maximum(0.0, coast)
    ridge_grid = feat[:, :, 1] / 255.0

    _, world_idx = worldsheet_lint.validate_sheets(province, [])
    provs_to_check = [province] if province else sorted({r.split("/")[0] for r in target_holds})
    for prov_id in provs_to_check:
        if not any(v.get("province") == prov_id for v in world_idx.values()):
            md_path = os.path.join(ROOT, "docs", "world", "provinces", f"{prov_id}.md")
            if os.path.exists(md_path):
                cur_scope = None
                for line in open(md_path, encoding="utf-8"):
                    s = line.strip()
                    if s.startswith("### 1.0 "):
                        cur_scope = prov_id
                    elif s.startswith("### 1."):
                        m_sc = re.search(r"`([a-z_]+/[a-z_]+)`", s)
                        cur_scope = worldsheet_lint.canon_scope(m_sc.group(1)) if m_sc else None
                    elif s.startswith("## 2."):
                        break
                    elif cur_scope and s.startswith("| L"):
                        parts = [p.strip() for p in s.strip("|").split("|")]
                        if len(parts) >= 2:
                            m_lyr = re.match(r"^(L\d+)\b", parts[0])
                            if m_lyr:
                                lyr = m_lyr.group(1)
                                for fid in re.findall(r"`([a-z0-9_]+)`", parts[1]):
                                    gid = f"{cur_scope}.{lyr}.{fid}"
                                    world_idx.setdefault(gid, {"scope": cur_scope, "layer": lyr, "id": fid, "province": prov_id})
    errors = []
    sub_offsets = [-12.0, -4.0, 4.0, 12.0]
    cell_km2 = (CELL / 1000.0) ** 2
    holds_out = {}

    for rid, rules in target_holds.items():
        ridx = reg_to_idx[rid]
        total_cells = int((reg == ridx).sum())
        km2 = total_cells * cell_km2
        cz_idx, cx_idx = np.nonzero((reg == ridx) & (coast > 0))
        land_km2 = len(cx_idx) * cell_km2
        base_x = MIN_X + (cx_idx + 0.5) * CELL
        base_z = MIN_Z + (cz_idx + 0.5) * CELL
        all_qx, all_qz = [], []
        for ox in sub_offsets:
            for oz in sub_offsets:
                all_qx.append(np.floor_divide((base_x + ox).astype(np.int32), 4))
                all_qz.append(np.floor_divide((base_z + oz).astype(np.int32), 4))
        qx = np.concatenate(all_qx)
        qz = np.concatenate(all_qz)
        rep = len(sub_offsets) ** 2
        res = _eval_hold_rules(
            rules, qx, qz,
            np.tile(elev[cz_idx, cx_idx], rep),
            np.tile(slope_grid[cz_idx, cx_idx], rep),
            np.tile(ax_grid[cz_idx, cx_idx], rep),
            np.tile(az_grid[cz_idx, cx_idx], rep),
            np.tile(rs_img[cz_idx, cx_idx], rep),
            np.tile(ht_img[cz_idx, cx_idx], rep),
            np.tile(water_dist_grid[cz_idx, cx_idx], rep),
            np.tile(coast_dist_grid[cz_idx, cx_idx], rep),
            np.tile(ridge_grid[cz_idx, cx_idx], rep),
        )
        rule_shares = {}
        distinct_active = 0
        cites_ok = 0
        for k, rule in enumerate(rules):
            pct = float((res == k).mean() * 100.0)
            rule_shares[rule["id"]] = round(pct, 2)
            if pct >= 0.5:
                distinct_active += 1
            if pct < 0.5 or pct > 60.0:
                errors.append(f"{rid}:{rule['id']}: coverage {pct:.2f}% outside [0.5%, 60.0%]")
            why_ids = rule.get("why") or rule.get("cites") or []
            if not why_ids:
                errors.append(f"{rid}:{rule['id']}: missing why/cites field citations")
            else:
                bad = [fid for fid in why_ids if worldsheet_lint.canon_field_id(fid) not in world_idx]
                if bad:
                    errors.append(f"{rid}:{rule['id']}: unresolved worldsheet citations {bad}")
                else:
                    cites_ok += 1

        req_distinct = max(6, int(math.ceil(2.0 * km2)))
        if distinct_active < req_distinct:
            errors.append(f"{rid}: distinct micro-biomes {distinct_active} < required {req_distinct} (km2={km2:.2f})")

        holds_out[rid] = {
            "km2": round(km2, 2),
            "land_km2": round(land_km2, 2),
            "distinct_micro": distinct_active,
            "required_micro": req_distinct,
            "micro_per_km2": round(distinct_active / max(km2, 1e-6), 2),
            "cites_resolved": f"{cites_ok}/{len(rules)}",
            "min_share_pct": min(rule_shares.values()) if rule_shares else 0.0,
            "max_share_pct": max(rule_shares.values()) if rule_shares else 0.0,
            "rules": rule_shares,
        }

    # Road transitions per km along roadplan.bin
    rp = roadplan_lint.load(roadplan_lint.DEFAULT)
    hold_km = {r: 0.0 for r in target_holds}
    hold_trans = {r: 0 for r in target_holds}
    for s in rp["segs"]:
        xs, zs, arc = s["x"], s["z"], s["arc"]
        if arc[-1] < 16.0:
            continue
        steps = max(2, int(arc[-1] // 4.0) + 1)
        target_s = np.linspace(0.0, arc[-1], steps)
        sx = np.interp(target_s, arc, xs)
        sz = np.interp(target_s, arc, zs)
        cx = np.clip(((sx - MIN_X) // CELL).astype(np.int32), 0, W - 1)
        cz = np.clip(((sz - MIN_Z) // CELL).astype(np.int32), 0, H - 1)
        rids = [idx_to_id.get(int(r), "") for r in reg[cz, cx]]
        present_holds = set(r for r in rids if r in target_holds)
        if not present_holds:
            continue
        qx = np.floor_divide(sx.astype(np.int32), 4)
        qz = np.floor_divide(sz.astype(np.int32), 4)
        m_by_hold = {
            h: _eval_hold_rules(
                target_holds[h], qx, qz,
                elev[cz, cx], slope_grid[cz, cx], ax_grid[cz, cx], az_grid[cz, cx],
                rs_img[cz, cx], ht_img[cz, cx], water_dist_grid[cz, cx], coast_dist_grid[cz, cx], ridge_grid[cz, cx]
            )
            for h in present_holds
        }
        prev_m = None
        prev_r = None
        for i, r in enumerate(rids):
            if r not in target_holds:
                prev_m = None
                prev_r = None
                continue
            if i > 0 and rids[i - 1] == r:
                hold_km[r] += float(target_s[i] - target_s[i - 1]) / 1000.0
            m = int(m_by_hold[r][i])
            if prev_r == r and prev_m is not None and m != prev_m:
                hold_trans[r] += 1
            prev_r = r
            prev_m = m

    tot_km = sum(hold_km.values())
    tot_tr = sum(hold_trans.values())
    tot_rate = tot_tr / max(tot_km, 1e-6)
    for r in target_holds:
        rate = hold_trans[r] / max(hold_km[r], 1e-6)
        holds_out[r]["road_km"] = round(hold_km[r], 2)
        holds_out[r]["road_transitions"] = hold_trans[r]
        holds_out[r]["road_transitions_per_km"] = round(rate, 2)
        if hold_km[r] > 0.5 and rate < 3.0:
            errors.append(f"{r}: road transitions/km {rate:.2f} < 3.0")
    if tot_km > 0.5 and tot_rate < 3.0:
        errors.append(f"province road transitions/km {tot_rate:.2f} < 3.0")

    return {
        "holds": holds_out,
        "total_rules": sum(len(v) for v in target_holds.values()),
        "road_km": round(tot_km, 2),
        "road_transitions": tot_tr,
        "road_transitions_per_km": round(tot_rate, 2),
    }, errors


def main():
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "audit.json")
    if "--json" in sys.argv:
        path = sys.argv[sys.argv.index("--json") + 1]
    province = None
    if "--province" in sys.argv:
        province = sys.argv[sys.argv.index("--province") + 1]
    rows, borders = analyse()
    micro_report, micro_errors = audit_micro(province)
    json.dump({"regions": rows, "borders": borders, "micro": micro_report}, open(path, "w", encoding="utf-8"), indent=1)
    print("%d land regions, %d borders -> %s" % (len(rows), len(borders), path))
    if not province:
        print("weakest by variety:")
        for r in sorted(rows, key=lambda r: r["variety"])[:15]:
            print("  %-34s %5.1f biomes %2d/%2d trees %2d plants %2d struct %2d places %3d elev %3d" % (
                r["id"], r["variety"], r["n_major_biomes"], r["n_biomes"], r["n_trees"], r["n_plants"],
                r["n_struct_specific"], r["n_places"], r["elev_range"]))
        print("worst borders:")
        for b in borders[:15]:
            print("  %-30s | %-30s len %5d straight %.2f ovl %.2f dT %.2f step %3d jump %.1f seam %.1f %s" % (
                b["a"], b["b"], b["length_blocks"], b["straight_frac"], b["overlap"], b["d_temp"], b["height_step"],
                b["jump"], b["seam"], b["tags"]))
        tags = [b for b in borders if b["tags"]]
        print("tagged:", [(b["a"], b["b"], b["tags"]) for b in tags])
    if micro_report.get("holds"):
        print("\nMS21 Micro-Biome Audit (%d holds, %d rules):" % (
            len(micro_report["holds"]), micro_report["total_rules"]))
        print("  %-22s %6s %8s %8s %15s %12s %8s" % (
            "Hold", "km2", "Distinct", "Micro/km2", "Share [min,max]", "Trans/km", "Cites"))
        for rid, h in micro_report["holds"].items():
            print("  %-22s %6.2f %4d/>=%d %8.2f   [%5.2f%%, %5.2f%%] %9.2f/km %8s" % (
                rid, h["km2"], h["distinct_micro"], h["required_micro"], h["micro_per_km2"],
                h["min_share_pct"], h["max_share_pct"], h["road_transitions_per_km"], h["cites_resolved"]))
        print("  TOTAL ROAD TRANSITIONS: %.2f / km (%d transitions over %.2f km)" % (
            micro_report["road_transitions_per_km"], micro_report["road_transitions"], micro_report["road_km"]))
        if micro_errors:
            for e in micro_errors:
                print("  ERROR:", e)
            print("region_audit: %d micro-biome gate error(s)" % len(micro_errors))
            sys.exit(1)
        else:
            print("region_audit: 0 errors (all MS21 gates passed)")


if __name__ == "__main__":
    main()
