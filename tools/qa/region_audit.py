"""Region variety + border transition audit (data only, no Minecraft).

Usage: py -3 -I tools/qa/region_audit.py [--json OUT.json]

Reads the Tamriel rasters (mod/src/main/resources/skycraft/tamriel), regions.json, palettes.json, the biome and
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
TAM = os.path.join(RES, "skycraft", "tamriel")
DATA = os.path.join(RES, "data", "skycraft", "worldgen")
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
        out["skycraft:" + os.path.basename(f)[:-5]] = j
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
                trees.add("skycraft:" + sp)
            for ref in set(re.findall(r'"([a-z_]+:[a-z0-9_]+/[a-z0-9_/]+)"', s)):
                if ref != fid and depth < 6 and ref.split(":")[0] in ("skycraft", "minecraft"):
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
                if f.startswith("skycraft:biome/") or f.split(":")[0] == "minecraft":
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


def main():
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "audit.json")
    if "--json" in sys.argv:
        path = sys.argv[sys.argv.index("--json") + 1]
    rows, borders = analyse()
    json.dump({"regions": rows, "borders": borders}, open(path, "w", encoding="utf-8"), indent=1)
    print("%d land regions, %d borders -> %s" % (len(rows), len(borders), path))
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


if __name__ == "__main__":
    main()
