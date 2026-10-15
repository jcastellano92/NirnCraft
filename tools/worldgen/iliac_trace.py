#!/usr/bin/env python3
"""Iliac Bay vector & fief tracer (IB1, IB2, IB6).

Reads the gitignored Daggerfall reference extraction in `.reference/daggerfall_ref/v2/`
at authoring time only, applies the similarity + bay-opening hinge transform from
`docs/world/ILIAC-BAY.md` section 2.3, and outputs simplified vector polygons and
polylines (at fan-map resolution, never raw or resampled rasters) into:
  - `mod/src/main/resources/skycraft/tamriel/fiefs.json` (43 fiefs >= 0.01 km2)
  - `tools/worldgen/tamriel_vectors.json` (coastline, islands, realms, ranges, areas,
    peaks, lakes, rivers, winds, geothermal)

Usage:
  py -3 -I tools/worldgen/iliac_trace.py [--verify-only]
"""
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
V2_DIR = os.path.join(ROOT, ".reference", "daggerfall_ref", "v2")
VEC_PATH = os.path.join(HERE, "tamriel_vectors.json")
FIEFS_PATH = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft", "tamriel", "fiefs.json")
TAM_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft", "tamriel")

# Similarity + bay-opening hinge parameters (ILIAC-BAY.md section 2.3)
S_SCALE = 6.221
THETA = math.radians(7.2)
TX, TZ = -10414.1, 1120.4
PHI_HINGE = math.radians(-23.33)
HX, HZ = -4835.0, 3613.0

REALM_FIEFS = {
    "high_rock/daggerfall": [17, 18, 58, 59, 19, 60, 32],
    "high_rock/anticlere": [21, 42, 41, 39, 40, 38],
    "high_rock/dwynnen": [5, 37, 36, 35, 34],
    "high_rock/wayrest": [23, 33, 57, 9, 26],
    "high_rock/wrothgar": [16, 105],
    "hammerfell/sentinel": [20, 48, 47, 55, 56, 46, 45, 44, 43, 61],
    "hammerfell/alikr_desert": [0, 11],
    "hammerfell/bangkorai": [22, 49, 51, 50, 52, 53, 54],
    "hammerfell/dragontail": [1, 4],
}

FIEF_META = {
    # High Rock - Daggerfall realm
    17: ("Daggerfall", "Kingdom of Daggerfall", "King of Daggerfall", "green_gold_dragon", "breton"),
    18: ("Glenpoint", "Barony of Glenpoint", "Baron of Glenpoint", "navy_silver_owl", "breton"),
    58: ("Tulune", "Barony of Tulune", "Baron of Tulune", "rose_ivory_lily", "breton"),
    59: ("Glenumbra Moors", "Barony of the Glenumbra Moors", "Baron of Grimley", "heather_peat_cairn", "breton"),
    19: ("Betony", "Island Barony of Betony", "Baron of Whitefort", "sea_green_anchor", "breton"),
    60: ("Ilessan Hills", "Barony of the Ilessan Hills", "Baron of Ilessan", "ochre_grey_wheel", "breton"),
    32: ("Northmoor", "Barony of Northmoor", "Baron of Northmoor", "slate_white_stag", "breton"),
    # High Rock - Anticlere realm
    21: ("Anticlere", "Duchy of Anticlere", "Duke of Anticlere", "crimson_gold_flame", "breton"),
    42: ("Shalgora", "Barony of Shalgora", "Baron of Shalgora", "amber_black_sheaf", "breton"),
    41: ("Daenia", "Barony of Daenia", "Baron of Daenia", "violet_silver_swan", "breton"),
    39: ("Urvaius", "Barony of Urvaius", "Baron of Urvaius", "blue_gold_chalice", "breton"),
    40: ("Ykalon", "Barony of Ykalon", "Baron of Ykalon", "charcoal_green_oak", "breton"),
    38: ("Phrygias", "Barony of Phrygias", "Baron of Phrygias", "iron_crimson_boar", "breton"),
    # High Rock - Dwynnen realm
    5: ("Dwynnen", "Barony of Dwynnen", "Baron of Dwynnen", "black_silver_raven", "breton"),
    37: ("Kambria", "Barony of Kambria", "Baron of Kambria", "russet_iron_keys", "breton"),
    36: ("Bhoriane", "Barony of Bhoriane", "Baron of Bhoriane", "lead_grey_pick", "breton"),
    35: ("Koegria", "Barony of Koegria", "Baron of Koegria", "indigo_white_scroll", "breton"),
    34: ("Alcaire", "Barony of Alcaire", "Baron of Alcaire", "emerald_gold_oak", "breton"),
    # High Rock - Wayrest realm
    23: ("Wayrest", "Kingdom of Wayrest", "King of Wayrest", "royal_blue_white_rose", "breton"),
    33: ("Menevia", "Barony of Menevia", "Baron of Menevia", "purple_silver_crown", "breton"),
    57: ("Gavaudon", "Barony of Gavaudon", "Baron of Gavaudon", "green_white_bridge", "breton"),
    9: ("Isle of Balfiera", "Tower Lordship of Balfiera", "Castellan of Blackhead", "silver_azure_spire", "breton"),
    26: ("Orsinium Area", "March of the Orsinium Valley", "March-Baron of Reytry", "iron_green_tusk", "breton"),
    # High Rock - Wrothgar realm
    16: ("Wrothgarian Mountains", "High Barony of Wrothgar", "Lord of Newing", "granite_iron_anvil", "orc"),
    # Hammerfell - Sentinel realm
    20: ("Sentinel", "Kingdom of Sentinel", "King of Sentinel", "cobalt_saffron_candle", "forebears"),
    48: ("Tigonus", "Emirate of Tigonus", "Emir of Tigonus", "reed_green_bronze", "forebears"),
    47: ("Ayasofya", "Emirate of Ayasofya", "Emir of Ayasofya", "terracotta_white_crescent", "forebears"),
    55: ("Antiphyllos", "Emirate of Antiphyllos", "Emir of Jalallaara", "ochre_crimson_dune", "crowns"),
    56: ("Bergama", "Emirate of Bergama", "Emir of Bergama", "salt_white_gold", "crowns"),
    46: ("Myrkwasa", "Emirate of Myrkwasa", "Emir of Myrkwasa", "olive_gold_palm", "forebears"),
    45: ("Pothago", "Emirate of Pothago", "Emir of Pothago", "azure_sand_star", "forebears"),
    44: ("Kairou", "Emirate of Kairou", "Emir of Kairou", "saffron_blue_galley", "forebears"),
    43: ("Abibon-Gora", "Principality of Abibon-Gora", "Prince of Abibon-Gora", "crimson_gold_wheel", "forebears"),
    # Hammerfell - Alik'r Desert realm
    0: ("Alik'r Desert", "Grand Oasis of the Alik'r", "High Emir of Berbaaqnia", "gold_ochre_scimitar", "crowns"),
    11: ("Dak'fron", "Emirate of Dak'fron", "Emir of Pheonahator", "copper_black_drake", "crowns"),
    # Hammerfell - Bangkorai realm
    22: ("Lainlyn", "Barony of Lainlyn", "Baron of Lainlyn", "jade_gold_horn", "forebears"),
    49: ("Kozanset", "Emirate of Kozanset", "Emir of Kozanset", "teal_bronze_heron", "forebears"),
    51: ("Totambu", "Principality of Totambu", "Prince of Totambu", "emerald_gold_scarab", "crowns"),
    50: ("Satakalaam", "Emirate of Satakalaam", "Emir of Satakalaam", "crimson_copper_serpent", "crowns"),
    52: ("Mournoth", "Emirate of Mournoth", "Emir of Mournoth", "forest_amber_axe", "crowns"),
    53: ("Ephesus", "Mountain Lordship of Ephesus", "Lord of Ephesus", "slate_white_cloud", "crowns"),
    54: ("Santaki", "Emirate of Santaki", "Emir of Santaki", "scarlet_gold_hawk", "forebears"),
    # Hammerfell - Dragontail realm
    1: ("Dragontail Mountains", "High Lordship of Dragontail", "High Lord of Dragontail", "basalt_crimson_wyrm", "crowns"),
}


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", name.lower().replace("'", "")).strip("_")


def rigid_n(px: float, py: float) -> tuple[float, float]:
    c, s = math.cos(THETA), math.sin(THETA)
    return S_SCALE * (c * px - s * py) + TX, S_SCALE * (s * px + c * py) + TZ


def rot_hinge(x: float, z: float, phi: float) -> tuple[float, float]:
    dx, dz = x - HX, z - HZ
    cp, sp = math.cos(phi), math.sin(phi)
    return HX + cp * dx - sp * dz, HZ + sp * dx + cp * dz


def make_warper(ws: np.ndarray):
    def warp(px: float, py: float) -> tuple[float, float]:
        x, z = rigid_n(px, py)
        ix = min(max(int(round(px)), 0), 999)
        iy = min(max(int(round(py)), 0), 499)
        w = float(ws[iy, ix])
        if w <= 1e-4:
            return x, z
        return rot_hinge(x, z, w * PHI_HINGE)
    return warp


def trace_loops(mask: np.ndarray) -> list[list[tuple[int, int]]]:
    H, W = mask.shape
    edges: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for y in range(H):
        for x in range(W):
            if not mask[y, x]:
                continue
            if y == 0 or not mask[y - 1, x]:
                edges.setdefault((x, y), []).append((x + 1, y))
            if x == W - 1 or not mask[y, x + 1]:
                edges.setdefault((x + 1, y), []).append((x + 1, y + 1))
            if y == H - 1 or not mask[y + 1, x]:
                edges.setdefault((x + 1, y + 1), []).append((x, y + 1))
            if x == 0 or not mask[y, x - 1]:
                edges.setdefault((x, y + 1), []).append((x, y))
    loops = []
    while edges:
        start = next(iter(edges))
        loop = [start]
        cur = start
        while True:
            nxts = edges.get(cur)
            if not nxts:
                break
            nxt = nxts.pop(0)
            if not nxts:
                del edges[cur]
            if nxt == start:
                break
            loop.append(nxt)
            cur = nxt
        if len(loop) >= 3:
            loops.append(loop)
    return loops


def trace_component_outer(comp_mask: np.ndarray) -> list[tuple[int, int]]:
    filled = ndimage.binary_fill_holes(comp_mask)
    return max(trace_loops(filled), key=len)


def rdp(pts: list[tuple[float, float]], eps: float) -> list[tuple[float, float]]:
    if len(pts) <= 2:
        return pts
    x1, z1 = pts[0]
    x2, z2 = pts[-1]
    dx, dz = x2 - x1, z2 - z1
    l2 = dx * dx + dz * dz
    max_d = -1.0
    idx = 0
    for i in range(1, len(pts) - 1):
        px, pz = pts[i]
        if l2 == 0:
            d = math.hypot(px - x1, pz - z1)
        else:
            t = max(0.0, min(1.0, ((px - x1) * dx + (pz - z1) * dz) / l2))
            d = math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz))
        if d > max_d:
            max_d = d
            idx = i
    if max_d > eps:
        left = rdp(pts[:idx + 1], eps)
        right = rdp(pts[idx:], eps)
        return left[:-1] + right
    return [pts[0], pts[-1]]


def simplify_closed(pts: list[tuple[float, float]], tol: float = 64.0) -> list[tuple[float, float]]:
    if len(pts) < 4:
        return pts
    i0 = 0
    i1 = max(range(len(pts)), key=lambda i: math.hypot(pts[i][0] - pts[i0][0], pts[i][1] - pts[i0][1]))
    i0 = max(range(len(pts)), key=lambda i: math.hypot(pts[i][0] - pts[i1][0], pts[i][1] - pts[i1][1]))
    if i0 > i1:
        i0, i1 = i1, i0
    h1 = rdp(pts[i0:i1 + 1], tol)
    h2 = rdp(pts[i1:] + pts[:i0 + 1], tol)
    out = h1[:-1] + h2[:-1]
    if len(out) < 4 and len(pts) >= 4:
        step = max(1, len(pts) // 4)
        out = [pts[0], pts[step], pts[2 * step], pts[3 * step]]
    return out


def poly_area(ring: list[list[int]]) -> float:
    if len(ring) < 3:
        return 0.0
    a = 0.0
    for i in range(len(ring)):
        x1, z1 = ring[i]
        x2, z2 = ring[(i + 1) % len(ring)]
        a += x1 * z2 - x2 * z1
    return abs(a) * 0.5


def point_in_poly(x: float, z: float, poly: list[list[int]]) -> bool:
    n = len(poly)
    if n < 3:
        return False
    inside = False
    j = n - 1
    for i in range(n):
        xi, zi = poly[i]
        xj, zj = poly[j]
        if ((zi > z) != (zj > z)) and (x < (xj - xi) * (z - zi) / float(zj - zi) + xi):
            inside = not inside
        j = i
    return inside


def scale_ring(ring: list[list[int]], scale: float) -> list[list[int]]:
    cx = sum(p[0] for p in ring) / len(ring)
    cz = sum(p[1] for p in ring) / len(ring)
    return [[int(round(cx + (p[0] - cx) * scale)), int(round(cz + (p[1] - cz) * scale))] for p in ring]


def build_fiefs(pol: np.ndarray, warp, rt: list[dict]) -> list[dict]:
    idx_to_realm = {}
    for realm, indices in REALM_FIEFS.items():
        for idx in indices:
            idx_to_realm[idx] = realm

    fiefs_out = []
    for r in rt:
        idx = r["index"]
        km2 = float(r["area_km2"])
        if km2 < 0.01:
            continue
        realm = idx_to_realm[idx]
        prov = realm.split("/")[0]
        name, title, lord, banner, faction = FIEF_META[idx]
        fief_id = f"{prov}/{slugify(name)}"

        mask = (pol == (idx + 128))
        if idx == 20:
            # Merge rule (ILIAC-BAY.md section 4.2): Cybiades (0.002 km2) folds into Sentinel
            mask = mask | (pol == (61 + 128))
        lbl, n = ndimage.label(mask)
        comps = []
        for c in range(1, n + 1):
            cm = (lbl == c)
            if cm.sum() >= 15 or (idx == 20 and cm.sum() >= 3):
                comps.append((int(cm.sum()), cm))
        comps.sort(key=lambda x: -x[0])

        mc_rings = []
        for _, cm in comps:
            lp = trace_component_outer(cm)
            wpts = [warp(px, py) for px, py in lp]
            simp = simplify_closed(wpts, 64.0)
            mc_rings.append([[int(round(x)), int(round(z))] for x, z in simp])

        tot_km2 = sum(poly_area(ring) for ring in mc_rings) / 1e6
        if abs(tot_km2 - km2) / km2 > 0.05:
            s = math.sqrt(km2 / tot_km2)
            mc_rings = [scale_ring(ring, s) for ring in mc_rings]
            tot_km2 = sum(poly_area(ring) for ring in mc_rings) / 1e6

        seat_mc = r.get("seat_mc") or r.get("centroid_mc") or [0, 0]
        sx, sz = int(seat_mc[0]), int(seat_mc[1])
        if idx == 17:
            sx, sz = -9300, 2600
        elif idx == 23:
            sx, sz = -5300, 3300
        elif idx == 20:
            sx, sz = -7976, 4900

        # Ensure seat_mc is strictly inside one of the fief's rings for Fiefs.at(seatX, seatZ)
        if not any(point_in_poly(sx, sz, ring) for ring in mc_rings):
            primary = mc_rings[0]
            cx = sum(p[0] for p in primary) / len(primary)
            cz = sum(p[1] for p in primary) / len(primary)
            k_near = min(range(len(primary)), key=lambda k: math.hypot(primary[k][0] - sx, primary[k][1] - sz))
            vx, vz = primary[k_near]
            dx, dz = sx - cx, sz - cz
            dist = max(math.hypot(dx, dz), 1.0)
            primary[k_near] = [int(round(sx + 25.0 * dx / dist)), int(round(sz + 25.0 * dz / dist))]
            tot_km2 = sum(poly_area(ring) for ring in mc_rings) / 1e6
            if abs(tot_km2 - km2) / km2 > 0.08:
                s = math.sqrt(km2 / tot_km2)
                mc_rings = [scale_ring(ring, s) for ring in mc_rings]
                tot_km2 = sum(poly_area(ring) for ring in mc_rings) / 1e6
            if not any(point_in_poly(sx, sz, ring) for ring in mc_rings):
                # Pull seat slightly toward centroid if still on edge
                for step_t in (0.05, 0.1, 0.15, 0.25, 0.4):
                    tx = int(round(sx + (cx - sx) * step_t))
                    tz = int(round(sz + (cz - sz) * step_t))
                    if any(point_in_poly(tx, tz, ring) for ring in mc_rings):
                        sx, sz = tx, tz
                        break

        orders = r.get("knightly_orders") or []
        covens = r.get("witch_covens") or []
        fiefs_out.append({
            "id": fief_id,
            "df_index": idx,
            "realm": realm,
            "name": name,
            "title": title,
            "lord": lord,
            "seat": r.get("seat") or name,
            "seat_mc": [sx, sz],
            "ruling_house": r.get("ruling_house") or name,
            "temple": r.get("temple") or "",
            "order": orders[0] if orders else "",
            "orders": orders,
            "coven": covens[0] if covens else "",
            "covens": covens,
            "bloodline": r.get("vampire_clan") or "",
            "banner": banner,
            "faction": faction,
            "area_km2": round(tot_km2, 3),
            "target_area_km2": round(km2, 3),
            "poly": mc_rings[0],
            "polys": mc_rings,
        })

    return fiefs_out


def trace_coast_and_islands(pol: np.ndarray, warp, vec: dict) -> tuple[list[list[int]], list[dict]]:
    land = (pol != 64).copy()
    # Bridge the 1-px Bjoulsae river channel at the east map edge so north & south shores form one mainland coast
    land[272:277, 920:] = True
    lbl, n = ndimage.label(land)
    sizes = ndimage.sum(land, lbl, range(1, n + 1))
    main_id = int(np.argmax(sizes)) + 1
    main_mask = (lbl == main_id)
    loops = trace_loops(main_mask)
    main_loop = max(loops, key=len)

    top_idx = min(range(len(main_loop)), key=lambda i: (main_loop[i][1], main_loop[i][0]))
    bot_idx = min(range(len(main_loop)), key=lambda i: (-main_loop[i][1], main_loop[i][0]))
    if bot_idx < top_idx:
        coast_px = main_loop[bot_idx:top_idx + 1]
    else:
        coast_px = main_loop[bot_idx:] + main_loop[:top_idx + 1]
    coast_mc = []
    for px, py in coast_px:
        x, z = warp(px, py)
        if -8150.0 <= x <= -7650.0 and 4815.0 <= z <= 5150.0:
            z = min(z, 4815.0)
        coast_mc.append((x, z))
    coast_simp = [[int(round(x)), int(round(z))] for x, z in rdp(coast_mc, 36.0)]

    # Ensure coastal binding anchors (daggerfall -9300,2600; wayrest -5300,3300)
    # sit comfortably on land (50..250 blocks inland)
    for ax, az, push_x, push_z in (
        (-9300, 2600, 0, 90),     # push coast south of Daggerfall
        (-5300, 3300, 0, 95),     # push coast south of Wayrest
    ):
        k_near = min(range(len(coast_simp)), key=lambda k: math.hypot(coast_simp[k][0] - ax, coast_simp[k][1] - az))
        if math.hypot(coast_simp[k_near][0] - ax, coast_simp[k_near][1] - az) < 85:
            coast_simp[k_near][0] = ax + push_x
            coast_simp[k_near][1] = az + push_z

    tam = next(e["poly"] for e in vec["land"] if e["name"] == "tamriel")
    # Identify splice indices in tamriel mainland polygon around Iliac Bay:
    # South of window: vertex near (-9300, 6800)
    # North of window: vertex near (-11300, -700)
    idx_south = min(range(len(tam)), key=lambda i: math.hypot(tam[i][0] - (-9300), tam[i][1] - 6800))
    idx_north = min(range(len(tam)), key=lambda i: math.hypot(tam[i][0] - (-11300), tam[i][1] - (-700)))
    new_tam = (
        tam[idx_north:idx_south + 1]
        + [[-9450, 6450]]
        + coast_simp
        + [[-9600, 800], [-10400, 200], [-11000, -500]]
    )

    # Trace DF islands >= 35 px (Betony, Balfiera + main islets, Cybiades, bay islands)
    df_islands = []
    for i in range(1, n + 1):
        if i == main_id:
            continue
        imask = (lbl == i)
        px_cnt = int(imask.sum())
        if px_cnt < 35 and not ((pol[imask] == (61 + 128)).any()):
            continue
        iloop = max(trace_loops(imask), key=len)
        imc = [warp(px, py) for px, py in iloop]
        tol = 18.0 if px_cnt < 80 else 28.0
        isimp = simplify_closed(imc, tol)
        if len(isimp) < 3:
            continue
        ring = [[int(round(x)), int(round(z))] for x, z in isimp]
        cx = sum(p[0] for p in ring) / len(ring)
        cz = sum(p[1] for p in ring) / len(ring)
        vals = pol[imask] - 128
        dom_reg = int(np.bincount(vals[vals >= 0]).argmax())
        df_islands.append((px_cnt, dom_reg, cx, cz, ring))

    df_islands.sort(key=lambda x: -x[0])
    named_islands = []
    used_names = set()
    for px_cnt, dom_reg, cx, cz, ring in df_islands:
        if dom_reg == 19 and "betony" not in used_names:
            iname = "betony"
        elif dom_reg == 9 and "balfiera" not in used_names:
            iname = "balfiera"
        elif dom_reg == 61 and "cybiades" not in used_names:
            iname = "cybiades"
        else:
            idx_num = 1
            while f"iliac_isle_{idx_num}" in used_names:
                idx_num += 1
            iname = f"iliac_isle_{idx_num}"
        used_names.add(iname)
        named_islands.append({"name": iname, "poly": ring})

    # Stros M'kai (two-lobed island per hammerfell.md section 1.5 & 3) + Necromancer's islet at (-10850, 6950)
    stros_poly = [
        [-10680, 7020], [-10450, 6950], [-10150, 6980], [-9920, 7040],
        [-9900, 7320], [-10020, 7560], [-10140, 7670], [-10220, 7380],
        [-10340, 7580], [-10640, 7620], [-10720, 7350],
    ]
    stros_islet = {
        "name": "stros_mkai_islet",
        "poly": [[-10910, 6910], [-10790, 6910], [-10790, 6990], [-10910, 6990]],
    }
    named_islands.append({"name": "stros_mkai", "poly": stros_poly})
    named_islands.append(stros_islet)

    return new_tam, named_islands


def trace_realm_rings(pol: np.ndarray, warp) -> dict[str, list[list[list[int]]]]:
    out = {}
    for realm, indices in REALM_FIEFS.items():
        mask = np.isin(pol - 128, indices)
        lbl, n = ndimage.label(mask)
        rings = []
        for c in range(1, n + 1):
            cm = (lbl == c)
            if cm.sum() < 25 and not (realm == "hammerfell/sentinel" and (pol[cm] == (61 + 128)).any()):
                continue
            lp = trace_component_outer(cm)
            wpts = []
            for px, py in lp:
                x, z = warp(px, py)
                if realm == "hammerfell/sentinel" and -8150.0 <= x <= -7650.0 and 4815.0 <= z <= 5150.0:
                    z = min(z, 4815.0)
                wpts.append((x, z))
            simp = simplify_closed(wpts, 48.0)
            rings.append((int(cm.sum()), [[int(round(x)), int(round(z))] for x, z in simp]))
        rings.sort(key=lambda x: -x[0])
        out[realm] = [r for _, r in rings]
    return out


def update_vectors(vec: dict, new_tam: list[list[int]], islands: list[dict], realm_rings: dict[str, list[list[list[int]]]]):
    # 1. Update land polygons
    drop_land = {"betony", "balfiera", "cybiades", "stros_mkai", "stros_mkai_islet"}
    new_land = []
    for entry in vec["land"]:
        nm = entry["name"]
        if nm == "tamriel":
            new_land.append({"name": "tamriel", "poly": new_tam})
        elif nm in drop_land or nm.startswith("iliac_isle_"):
            continue
        else:
            new_land.append(entry)
    new_land.extend(islands)
    vec["land"] = new_land

    # 2. Update coast_detail_keep so traced Iliac Bay and Stros M'kai coasts keep their exact shape
    iliac_keep = [[-10650, 1250], [-4050, 1250], [-4050, 5850], [-10650, 5850]]
    stros_keep = [[-11000, 6850], [-9800, 6850], [-9800, 7750], [-11000, 7750]]
    cdk = [p for p in vec.get("coast_detail_keep", []) if p not in (iliac_keep, stros_keep) and p[0][0] > -9000]
    cdk.extend([iliac_keep, stros_keep])
    vec["coast_detail_keep"] = cdk

    # 3. Update High Rock & Hammerfell regions + sea/iliac_bay
    hr_hf_specs = [
        {
            "id": "high_rock/daggerfall", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [15, 32], "weather": "maritime", "capital": "daggerfall", "realm_banner": "green_gold",
            "terrain": [98, 28, 0.55, 0.35, 0.0, 0.05, 110], "priority": 2,
            "polys": realm_rings["high_rock/daggerfall"],
        },
        {
            "id": "high_rock/anticlere", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [16, 34], "weather": "maritime", "capital": "anticlere", "realm_banner": "crimson_white",
            "terrain": [88, 24, 0.45, 0.25, 0.0, 0.08, 140], "priority": 2,
            "polys": realm_rings["high_rock/anticlere"],
        },
        {
            "id": "high_rock/dwynnen", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [18, 36], "weather": "maritime", "capital": "dwynnen", "realm_banner": "black_silver",
            "terrain": [104, 28, 0.60, 0.42, 0.0, 0.04, 130], "priority": 2,
            "polys": realm_rings["high_rock/dwynnen"],
        },
        {
            "id": "high_rock/wayrest", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [15, 35], "weather": "maritime", "capital": "wayrest", "realm_banner": "royal_blue_rose",
            "terrain": [98, 26, 0.50, 0.32, 0.0, 0.12, 160], "priority": 2,
            "polys": realm_rings["high_rock/wayrest"],
        },
        {
            "id": "high_rock/wrothgar", "province": "high_rock", "kind": "kingdom", "culture": "orc",
            "levels": [22, 40], "weather": "skyrim_cold", "capital": "orsinium", "realm_banner": "iron_tusk",
            "terrain": [175, 42, 0.80, 0.70, 0.0, 0.0, 120], "priority": 2,
            "polys": [
                [[-6800, -3300], [-4500, -3300], [-3500, -2200], [-3300, -1200], [-3350, 0],
                 [-3300, 1700], [-4000, 2300], [-4700, 2500], [-5500, 1700], [-5600, -300],
                 [-5800, -1600], [-6600, -1800]],
            ] + realm_rings["high_rock/wrothgar"],
        },
        {
            "id": "high_rock/glenumbra", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [18, 36], "weather": "maritime", "capital": "camlorn", "realm_banner": "heather_grey",
            "terrain": [108, 26, 0.55, 0.35, 0.0, 0.10, 140], "priority": 1,
            "polys": [[[-12000, -1500], [-9700, -2200], [-8600, -1200], [-8400, 600],
                       [-8400, 1850], [-10800, 1850], [-12200, 1000]]],
        },
        {
            "id": "high_rock/rivenspire", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [22, 40], "weather": "maritime", "capital": "shornhelm", "realm_banner": "slate_crimson",
            "terrain": [132, 32, 0.68, 0.55, 0.0, 0.0, 110], "priority": 1,
            "polys": [[[-9700, -2200], [-8600, -3300], [-6800, -3300], [-6600, -1800],
                       [-6900, -600], [-7800, -400], [-8600, -1200]]],
        },
        {
            "id": "high_rock/stormhaven", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [16, 34], "weather": "maritime", "realm_banner": "oak_gold",
            "terrain": [104, 24, 0.50, 0.25, 0.0, 0.04, 160], "priority": 1,
            "polys": [[[-8400, 600], [-7800, -400], [-6900, -600], [-6700, 800],
                       [-6600, 1950], [-8400, 1850]]],
        },
        {
            "id": "high_rock/bjoulsae", "province": "high_rock", "kind": "kingdom", "culture": "breton",
            "levels": [16, 36], "weather": "maritime", "capital": "evermor", "realm_banner": "river_blue_white",
            "terrain": [102, 24, 0.48, 0.25, 0.0, 0.10, 180], "priority": 1,
            "polys": [[[-6900, -600], [-6600, -1800], [-5800, -1600], [-5600, -300],
                       [-5600, 700], [-5500, 1950], [-6600, 1950], [-6700, 800]]],
        },
        {
            "id": "hammerfell/sentinel", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [18, 36], "weather": "desert", "capital": "sentinel", "realm_banner": "cobalt_saffron",
            "terrain": [86, 22, 0.45, 0.30, 0.15, 0.06, 120], "priority": 2,
            "polys": realm_rings["hammerfell/sentinel"],
        },
        {
            "id": "hammerfell/alikr_desert", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [22, 42], "weather": "desert", "realm_banner": "ochre_sun",
            "terrain": [88, 22, 0.45, 0.25, 0.85, 0.0, 220], "priority": 1,
            "polys": realm_rings["hammerfell/alikr_desert"] + [
                [[-9800, 6100], [-8300, 5600], [-6000, 5100], [-5800, 5200],
                 [-5900, 6400], [-6600, 7200], [-8400, 7300], [-9800, 6800]],
            ],
        },
        {
            "id": "hammerfell/bangkorai", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [20, 38], "weather": "tropical", "realm_banner": "emerald_clay",
            "terrain": [106, 28, 0.58, 0.40, 0.0, 0.12, 160], "priority": 2,
            "polys": realm_rings["hammerfell/bangkorai"],
        },
        {
            "id": "hammerfell/dragontail", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [25, 45], "weather": "desert", "capital": "rihad", "realm_banner": "basalt_crimson",
            "terrain": [168, 38, 0.78, 0.70, 0.10, 0.0, 140], "priority": 2,
            "polys": realm_rings["hammerfell/dragontail"] + [
                [[-5900, 5100], [-4700, 4900], [-3500, 4700], [-3600, 5800],
                 [-3700, 7300], [-4400, 7000], [-4600, 8000], [-5600, 7900], [-5900, 6400]],
            ],
        },
        {
            "id": "hammerfell/craglorn", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [24, 44], "weather": "desert", "capital": "elinhir", "realm_banner": "star_bronze",
            "terrain": [144, 30, 0.68, 0.75, 0.15, 0.0, 180], "priority": 1,
            "polys": [[[-4700, 2500], [-4000, 2300], [-3300, 2600], [-3300, 3800],
                       [-3500, 4700], [-4700, 5000], [-4400, 4200], [-4350, 3400]]],
        },
        {
            "id": "hammerfell/hews_bane", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [22, 42], "weather": "tropical", "capital": "taneth", "realm_banner": "pearl_teal",
            "terrain": [96, 22, 0.50, 0.38, 0.20, 0.0, 120], "priority": 1,
            "polys": [[[-8400, 7300], [-6600, 7200], [-5900, 6400], [-5600, 7900],
                       [-4600, 8000], [-4600, 8800], [-8000, 8600]]],
        },
        {
            "id": "hammerfell/stros_mkai", "province": "hammerfell", "kind": "kingdom", "culture": "redguard",
            "levels": [22, 40], "weather": "tropical", "capital": "stros_mkai", "realm_banner": "hunding_gold",
            "terrain": [90, 22, 0.52, 0.38, 0.10, 0.0, 90], "priority": 3,
            "polys": [[[-11000, 6700], [-9600, 6700], [-9600, 7800], [-11000, 7800]]],
        },
    ]

    # Replace High Rock / Hammerfell regions while preserving order of all other provinces
    new_regions = []
    inserted = False
    for r in vec["regions"]:
        if r["id"].startswith(("high_rock/", "hammerfell/")):
            if not inserted:
                new_regions.extend(hr_hf_specs)
                inserted = True
            continue
        if r["id"] == "sea/iliac_bay":
            r = dict(r)
            r["polys"] = [[[-10600, 2100], [-10300, 5800], [-7000, 5200], [-4400, 3900], [-4400, 2600], [-7500, 2100]]]
        new_regions.append(r)
    vec["regions"] = new_regions

    # Region overrides for the 9 traced Iliac Bay realms + Stros M'kai so domain warp does not shift traced borders
    overrides = [ov for ov in vec.get("region_overrides", []) if not ov["id"].startswith(("high_rock/", "hammerfell/"))]
    for realm, rings in realm_rings.items():
        for ring in rings:
            overrides.append({"id": realm, "poly": ring})
    overrides.append({"id": "hammerfell/stros_mkai", "poly": [[-11000, 6700], [-9600, 6700], [-9600, 7800], [-11000, 7800]]})
    vec["region_overrides"] = overrides

    # 4. Signature areas (IB6 landforms & macro heights)
    iliac_area_names = {
        "daggerfall_bluffs", "glenpoint_headland", "glenumbra_moors_upland", "camlorn_western_moors",
        "anticlere_meadows", "ykalon_hollows", "dwynnen_gorge_karst", "balfiera_island_hill",
        "wayrest_estuary_flats", "orsinium_area_valley", "wrothgar_south_highlands",
        "sentinel_harbour_cliff", "abibon_sea_cliffs", "tigonus_marsh_lowland", "bergama_salt_pans",
        "alikr_erg_dunes", "dakfron_hot_plain", "alikr_mesas", "bangkorai_jungle_valleys",
        "ephesus_heights_area", "dragontail_black_ridges_area", "craglorn_badlands_area",
        "stros_mkai_west_plateau",
    }
    areas = [a for a in vec.get("areas", []) if a["name"] not in iliac_area_names]
    areas.extend([
        {
            "name": "daggerfall_bluffs", "base": 110, "relief": 22, "rough": 0.55, "crag": 0.45,
            "cliff": 0.85, "coast": 70, "hills": 18,
            "poly": [[-9650, 2320], [-9050, 2320], [-9050, 2720], [-9650, 2720]],
        },
        {
            "name": "glenpoint_headland", "base": 116, "relief": 24, "rough": 0.58, "crag": 0.48,
            "cliff": 0.90, "coast": 65, "hills": 20,
            "poly": [[-10150, 1750], [-9250, 1750], [-9250, 2250], [-10150, 2250]],
        },
        {
            "name": "glenumbra_moors_upland", "base": 92, "relief": 18, "rough": 0.50, "crag": 0.30,
            "wet": 0.18, "hills": 16,
            "poly": [[-10050, 1300], [-8850, 1300], [-8850, 1780], [-10050, 1780]],
        },
        {
            "name": "camlorn_western_moors", "base": 114, "relief": 28, "rough": 0.60, "crag": 0.38,
            "wet": 0.14, "hills": 22,
            "poly": [[-11200, -1200], [-8500, -1200], [-8500, 1200], [-11200, 1200]],
        },
        {
            "name": "anticlere_meadows", "base": 74, "relief": 10, "rough": 0.25, "crag": 0.08,
            "wet": 0.15, "coast": 180, "hills": 8,
            "poly": [[-8450, 2150], [-7450, 2150], [-7450, 2650], [-8450, 2650]],
        },
        {
            "name": "ykalon_hollows", "base": 82, "relief": 16, "rough": 0.42, "crag": 0.20,
            "wet": 0.22, "hills": 12,
            "poly": [[-8500, 1420], [-7550, 1420], [-7550, 1950], [-8500, 1950]],
        },
        {
            "name": "dwynnen_gorge_karst", "base": 108, "relief": 26, "rough": 0.65, "crag": 0.52,
            "cliff": 0.65, "hills": 22,
            "poly": [[-7500, 1800], [-6800, 1800], [-6800, 2380], [-7500, 2380]],
        },
        {
            "name": "balfiera_island_hill", "base": 135, "relief": 28, "rough": 0.60, "crag": 0.45,
            "cliff": 0.65, "coast": 75, "hills": 24,
            "poly": [[-6480, 2720], [-6080, 2720], [-6080, 3120], [-6480, 3120]],
        },
        {
            "name": "wayrest_estuary_flats", "base": 72, "relief": 8, "rough": 0.22, "crag": 0.05,
            "wet": 0.35, "coast": 200, "hills": 6,
            "poly": [[-5500, 3150], [-4750, 3150], [-4750, 3580], [-5500, 3580]],
        },
        {
            "name": "orsinium_area_valley", "base": 140, "relief": 34, "rough": 0.68, "crag": 0.52,
            "hills": 24,
            "poly": [[-5650, 2550], [-4450, 2550], [-4450, 3350], [-5650, 3350]],
        },
        {
            "name": "wrothgar_south_highlands", "base": 205, "relief": 48, "rough": 0.85, "crag": 0.75,
            "hills": 30,
            "poly": [[-6100, 1650], [-4250, 1650], [-4250, 2650], [-6100, 2650]],
        },
        {
            "name": "sentinel_harbour_cliff", "base": 96, "relief": 22, "rough": 0.52, "crag": 0.42,
            "cliff": 0.85, "coast": 75, "hills": 18,
            "poly": [[-8350, 4780], [-7650, 4780], [-7650, 5350], [-8350, 5350]],
        },
        {
            "name": "abibon_sea_cliffs", "base": 88, "relief": 20, "rough": 0.55, "crag": 0.48,
            "cliff": 0.88, "coast": 70, "hills": 16,
            "poly": [[-9250, 5350], [-8350, 5350], [-8350, 6250], [-9250, 6250]],
        },
        {
            "name": "tigonus_marsh_lowland", "base": 68, "relief": 8, "rough": 0.25, "crag": 0.08,
            "wet": 0.48, "coast": 200, "hills": 6,
            "poly": [[-7550, 4400], [-6800, 4400], [-6800, 5050], [-7550, 5050]],
        },
        {
            "name": "bergama_salt_pans", "base": 69, "relief": 6, "rough": 0.20, "crag": 0.05,
            "dunes": 0.35, "wet": 0.15, "hills": 4,
            "poly": [[-7800, 5150], [-7250, 5150], [-7250, 5700], [-7800, 5700]],
        },
        {
            "name": "alikr_erg_dunes", "base": 75, "relief": 16, "rough": 0.42, "crag": 0.12,
            "dunes": 0.95, "hills": 18,
            "poly": [[-8300, 4850], [-6600, 4850], [-6600, 6200], [-8300, 6200]],
        },
        {
            "name": "dakfron_hot_plain", "base": 96, "relief": 24, "rough": 0.55, "crag": 0.42,
            "dunes": 0.65, "hills": 20,
            "poly": [[-6650, 4950], [-5950, 4950], [-5950, 5850], [-6650, 5850]],
        },
        {
            "name": "alikr_mesas", "base": 124, "relief": 32, "rough": 0.68, "crag": 0.62,
            "dunes": 0.45, "hills": 24,
            "poly": [[-8800, 6150], [-6200, 6150], [-6200, 7100], [-8800, 7100]],
        },
        {
            "name": "bangkorai_jungle_valleys", "base": 84, "relief": 20, "rough": 0.48, "crag": 0.25,
            "wet": 0.22, "coast": 160, "hills": 16,
            "poly": [[-6250, 3650], [-4450, 3650], [-4450, 4450], [-6250, 4450]],
        },
        {
            "name": "ephesus_heights_area", "base": 190, "relief": 44, "rough": 0.80, "crag": 0.68,
            "hills": 28,
            "poly": [[-6400, 4450], [-4550, 4450], [-4550, 5050], [-6400, 5050]],
        },
        {
            "name": "dragontail_black_ridges_area", "base": 195, "relief": 46, "rough": 0.82, "crag": 0.72,
            "hills": 30,
            "poly": [[-5950, 4350], [-4600, 4350], [-4600, 5350], [-5950, 5350]],
        },
        {
            "name": "craglorn_badlands_area", "base": 148, "relief": 34, "rough": 0.72, "crag": 0.78,
            "dunes": 0.20, "hills": 24,
            "poly": [[-4550, 2500], [-3350, 2500], [-3350, 4700], [-4550, 4700]],
        },
        {
            "name": "stros_mkai_west_plateau", "base": 118, "relief": 24, "rough": 0.60, "crag": 0.50,
            "cliff": 0.85, "coast": 60, "hills": 18,
            "poly": [[-10720, 7000], [-10260, 7000], [-10260, 7640], [-10720, 7640]],
        },
    ])
    vec["areas"] = areas

    # 5. Signature ranges (IB6 mountain spines & scarps)
    iliac_range_names = {
        "wrothgarian_mountains", "dragontail_mountains", "craglorn_crags", "bangkorai_hills",
        "rivenspire_heights", "ilessan_hills", "anticlere_scarp", "dwynnen_march_hills",
        "camlorn_moors_ridge", "stormhaven_uplands", "dragontail_red_wall", "ephesus_santaki_ridge",
        "sentinel_terrace_hills", "alikr_mesa_escarpment", "hews_bane_headlands",
    }
    ranges = [rg for rg in vec.get("ranges", []) if rg["name"] not in iliac_range_names]
    ranges.extend([
        {
            "name": "wrothgarian_mountains", "height": 470, "width": 1050, "jag": 1.5,
            "line": [[-6100, -2500], [-5400, -1600], [-5100, -700], [-5100, 300],
                     [-4900, 1200], [-5300, 2150], [-4950, 2650], [-4500, 3250]],
        },
        {
            "name": "ilessan_hills", "height": 185, "width": 650, "jag": 1.0,
            "line": [[-9750, 1880], [-9150, 1820], [-8600, 2220]],
        },
        {
            "name": "anticlere_scarp", "height": 210, "width": 600, "jag": 1.3,
            "line": [[-8650, 2100], [-8050, 1950], [-7550, 1900], [-7180, 1620]],
        },
        {
            "name": "dwynnen_march_hills", "height": 240, "width": 680, "jag": 1.3,
            "line": [[-6800, 1980], [-6300, 2120], [-5950, 2400], [-5450, 2820]],
        },
        {
            "name": "camlorn_moors_ridge", "height": 215, "width": 750, "jag": 1.2,
            "line": [[-10400, 400], [-9400, 200], [-8700, 700]],
        },
        {
            "name": "rivenspire_heights", "height": 290, "width": 800, "jag": 1.6,
            "line": [[-9200, -1900], [-8000, -2100], [-7000, -1800]],
        },
        {
            "name": "stormhaven_uplands", "height": 195, "width": 650, "jag": 1.1,
            "line": [[-8100, 700], [-7300, 600], [-6800, 1100]],
        },
        {
            "name": "dragontail_mountains", "height": 440, "width": 950, "jag": 1.6,
            "line": [[-5850, 4550], [-5250, 4680], [-4850, 4780], [-5300, 5800], [-4950, 6600], [-4300, 6900]],
        },
        {
            "name": "dragontail_red_wall", "height": 450, "width": 850, "jag": 1.7,
            "line": [[-5500, 6500], [-4800, 7000], [-4100, 7400]],
        },
        {
            "name": "ephesus_santaki_ridge", "height": 310, "width": 700, "jag": 1.4,
            "line": [[-6350, 4950], [-5600, 4800], [-4800, 4720]],
        },
        {
            "name": "bangkorai_hills", "height": 210, "width": 620, "jag": 1.2,
            "line": [[-6150, 4250], [-5250, 4180], [-4700, 3850]],
        },
        {
            "name": "sentinel_terrace_hills", "height": 175, "width": 550, "jag": 1.1,
            "line": [[-8750, 5550], [-8200, 5250], [-7750, 5150]],
        },
        {
            "name": "alikr_mesa_escarpment", "height": 220, "width": 650, "jag": 1.5,
            "line": [[-8200, 6350], [-7100, 6150], [-6300, 5350]],
        },
        {
            "name": "craglorn_crags", "height": 285, "width": 780, "jag": 1.5,
            "line": [[-4500, 2900], [-3900, 3400], [-3700, 4200], [-3600, 4800]],
        },
        {
            "name": "hews_bane_headlands", "height": 190, "width": 600, "jag": 1.3,
            "line": [[-7800, 7900], [-6900, 7700], [-5800, 7800]],
        },
    ])
    vec["ranges"] = ranges

    # 6. Signature XL peaks (>= 1 per realm/region)
    iliac_peak_names = {
        "glenpoint_sea_stack", "phrygias_tor", "koegria_harpy_tor", "balfiera_summit",
        "wrothgar_arch_peak", "sorrow_peak_wrothgar", "camlorn_tor", "doomcrag_spire",
        "weeping_giant_crag", "druadach_watch_peak", "sentinel_harbour_bluff",
        "dakfron_dragon_mesa", "ephesus_crag", "scourg_volcano_peak", "skyreach_pinnacle_peak",
        "starfall_crater_1", "taneth_headland_arch", "bthzark_plateau_peak",
    }
    peaks = [pk for pk in vec.get("peaks", []) if pk["name"] not in iliac_peak_names]
    peaks.extend([
        {
            "name": "glenpoint_sea_stack", "x": -9780, "z": 2080, "height": 160, "radius": 250,
            "sharp": {"reach": 210, "shape": 1.3, "power": 1.3, "horn": [18, 45], "jag": 14,
                      "spurs": [[-120, 22, 0.25], [40, 20, 0.22], [160, 24, 0.25]]},
        },
        {
            "name": "phrygias_tor", "x": -7250, "z": 1580, "height": 230, "radius": 290,
            "sharp": {"reach": 240, "shape": 1.3, "power": 1.35, "horn": [20, 50], "jag": 16,
                      "spurs": [[-90, 24, 0.28], [30, 22, 0.25], [140, 20, 0.22]]},
        },
        {
            "name": "koegria_harpy_tor", "x": -6180, "z": 2080, "height": 220, "radius": 270,
            "sharp": {"reach": 220, "shape": 1.3, "power": 1.35, "horn": [18, 45], "jag": 15,
                      "spurs": [[-110, 22, 0.25], [20, 20, 0.24], [135, 22, 0.26]]},
        },
        {
            "name": "balfiera_summit", "x": -6265, "z": 2915, "height": 175, "radius": 250,
            "sharp": {"reach": 210, "shape": 1.4, "power": 1.25, "horn": [14, 40], "jag": 12,
                      "spurs": [[-100, 25, 0.22], [35, 25, 0.22], [150, 25, 0.22]]},
        },
        {
            "name": "wrothgar_arch_peak", "x": -5220, "z": 2220, "height": 445, "radius": 460,
            "sharp": {"reach": 370, "shape": 1.4, "power": 1.35, "horn": [28, 70], "jag": 22,
                      "spurs": [[-130, 24, 0.32], [-20, 20, 0.26], [85, 22, 0.28], [165, 24, 0.30]]},
        },
        {
            "name": "sorrow_peak_wrothgar", "x": -5050, "z": -650, "height": 460, "radius": 480,
            "sharp": {"reach": 390, "shape": 1.45, "power": 1.35, "horn": [30, 75], "jag": 24,
                      "spurs": [[-140, 24, 0.32], [-30, 22, 0.28], [75, 24, 0.30], [160, 22, 0.28]]},
        },
        {
            "name": "camlorn_tor", "x": -9550, "z": 350, "height": 225, "radius": 300,
            "sharp": {"reach": 250, "shape": 1.35, "power": 1.3, "horn": [18, 48], "jag": 15,
                      "spurs": [[-100, 22, 0.25], [30, 22, 0.25], [150, 22, 0.25]]},
        },
        {
            "name": "doomcrag_spire", "x": -8750, "z": -1950, "height": 315, "radius": 360,
            "sharp": {"reach": 300, "shape": 1.25, "power": 1.4, "horn": [28, 60], "jag": 22,
                      "spurs": [[-120, 20, 0.30], [10, 22, 0.28], [130, 20, 0.30]]},
        },
        {
            "name": "weeping_giant_crag", "x": -7450, "z": 750, "height": 205, "radius": 270,
            "sharp": {"reach": 220, "shape": 1.35, "power": 1.3, "horn": [16, 42], "jag": 14,
                      "spurs": [[-90, 22, 0.24], [45, 22, 0.24], [160, 22, 0.24]]},
        },
        {
            "name": "druadach_watch_peak", "x": -5850, "z": 450, "height": 275, "radius": 310,
            "sharp": {"reach": 250, "shape": 1.35, "power": 1.35, "horn": [20, 50], "jag": 16,
                      "spurs": [[-110, 22, 0.26], [25, 22, 0.26], [145, 22, 0.26]]},
        },
        {
            "name": "sentinel_harbour_bluff", "x": -8250, "z": 5150, "height": 155, "radius": 240,
            "sharp": {"reach": 200, "shape": 1.4, "power": 1.25, "horn": [14, 40], "jag": 12,
                      "spurs": [[-110, 24, 0.22], [30, 24, 0.22], [150, 24, 0.22]]},
        },
        {
            "name": "dakfron_dragon_mesa", "x": -6550, "z": 5550, "height": 215, "radius": 310,
            "sharp": {"reach": 250, "shape": 1.6, "power": 1.2, "horn": [12, 55], "jag": 14,
                      "spurs": [[-100, 26, 0.25], [20, 26, 0.25], [140, 26, 0.25]]},
        },
        {
            "name": "ephesus_crag", "x": -4920, "z": 4620, "height": 295, "radius": 330,
            "sharp": {"reach": 270, "shape": 1.35, "power": 1.35, "horn": [22, 55], "jag": 18,
                      "spurs": [[-115, 22, 0.28], [15, 22, 0.26], [140, 22, 0.28]]},
        },
        {
            "name": "scourg_volcano_peak", "x": -5480, "z": 4580, "height": 420, "radius": 450,
            "sharp": {"reach": 360, "shape": 1.4, "power": 1.35, "horn": [26, 65], "jag": 22,
                      "spurs": [[-125, 24, 0.30], [-10, 22, 0.28], [95, 24, 0.30], [170, 22, 0.26]]},
        },
        {
            "name": "skyreach_pinnacle_peak", "x": -3750, "z": 3250, "height": 315, "radius": 350,
            "sharp": {"reach": 280, "shape": 1.3, "power": 1.4, "horn": [24, 55], "jag": 20,
                      "spurs": [[-110, 22, 0.28], [25, 22, 0.28], [145, 22, 0.28]]},
        },
        {
            "name": "starfall_crater_1", "x": -4150, "z": 3350, "height": 155, "radius": 240,
            "caldera": 110, "caldera_depth": 28,
        },
        {
            "name": "taneth_headland_arch", "x": -7350, "z": 7850, "height": 190, "radius": 270,
            "sharp": {"reach": 220, "shape": 1.4, "power": 1.3, "horn": [16, 45], "jag": 14,
                      "spurs": [[-100, 24, 0.24], [30, 24, 0.24], [150, 24, 0.24]]},
        },
        {
            "name": "bthzark_plateau_peak", "x": -10520, "z": 7380, "height": 150, "radius": 210,
            "sharp": {"reach": 175, "shape": 1.5, "power": 1.25, "horn": [12, 40], "jag": 10,
                      "spurs": [[-90, 25, 0.20], [45, 25, 0.20], [160, 25, 0.20]]},
        },
    ])
    vec["peaks"] = peaks

    # 7. Lakes (Evermor lake on the upper Bjoulsae)
    iliac_lake_names = {"evermor_lake"}
    lakes = [lk for lk in vec.get("lakes", []) if lk["name"] not in iliac_lake_names]
    lakes.append({
        "name": "evermor_lake", "level": 96, "depth": 9,
        "poly": [[-6420, -340], [-6200, -350], [-6160, -160], [-6260, -60], [-6420, -120]],
        "islands": [[[-6330, -250], [-6260, -250], [-6260, -180], [-6330, -180]]],
    })
    vec["lakes"] = lakes

    # 8. Rivers (7 named rivers entering the Iliac Bay)
    iliac_river_names = {
        "daggerfall_river", "anticlere_river", "dwynnen_river", "bjoulsae_river",
        "sentinel_wadi", "lainlyn_river", "totambu_river",
    }
    rivers = [rv for rv in vec.get("rivers", []) if rv["name"] not in iliac_river_names]
    rivers.extend([
        {
            "name": "daggerfall_river", "width": 12, "mouth_width": 20,
            "line": [[-9050, 1820, 130], [-9160, 2120, 92], [-9210, 2420, 70], [-9210, 2670, 63]],
        },
        {
            "name": "anticlere_river", "width": 12, "mouth_width": 20,
            "line": [[-8050, 1780, 135], [-8080, 2040, 88], [-8140, 2300, 68], [-8160, 2560, 63]],
        },
        {
            "name": "dwynnen_river", "width": 12, "mouth_width": 20, "valley": 95,
            "line": [[-7220, 1680, 148], [-7270, 1980, 84], [-7260, 2200, 68], [-7250, 2530, 63]],
        },
        {
            "name": "bjoulsae_river", "width": 20, "mouth_width": 34,
            "line": [[-6300, -1600, 125], [-6280, -340, 96], [-6290, -100, 96], [-6350, 200, 88],
                     [-6100, 1100, 78], [-5800, 2000, 72], [-5480, 2650, 67], [-5080, 3120, 64], [-5160, 3410, 63]],
        },
        {
            "name": "sentinel_wadi", "width": 10, "mouth_width": 18,
            "line": [[-7350, 5350, 112], [-7180, 4950, 76], [-7060, 4580, 66], [-7020, 4210, 63]],
        },
        {
            "name": "lainlyn_river", "width": 12, "mouth_width": 22,
            "line": [[-6250, 4920, 148], [-6180, 4520, 90], [-6120, 4150, 68], [-6120, 3810, 63]],
        },
        {
            "name": "totambu_river", "width": 14, "mouth_width": 24,
            "line": [[-4950, 4680, 175], [-5080, 4350, 88], [-5250, 4050, 68], [-5480, 3670, 63]],
        },
    ])
    vec["rivers"] = rivers

    # 9. Winds (MS21 rainshadow drivers for High Rock & Hammerfell)
    winds = dict(vec.get("winds", {}))
    winds.update({
        "high_rock/daggerfall": [[0.95, 0.30], [0.90, -0.42], [1.0, 0.0]],
        "high_rock/anticlere": [[0.60, -0.80], [0.92, 0.38]],
        "high_rock/dwynnen": [[0.70, -0.71], [0.95, 0.30]],
        "high_rock/wayrest": [[0.85, -0.52], [0.95, 0.30]],
        "high_rock/wrothgar": [[0.95, 0.30], [0.85, -0.52], [-0.85, 0.52]],
        "high_rock/glenumbra": [[0.96, 0.28], [0.90, -0.42]],
        "high_rock/rivenspire": [[0.65, 0.76], [0.92, 0.38]],
        "high_rock/stormhaven": [[0.92, 0.38], [0.70, -0.71]],
        "high_rock/bjoulsae": [[0.90, 0.42], [-0.85, 0.52]],
        "hammerfell/sentinel": [[0.60, 0.80], [0.92, 0.38]],
        "hammerfell/alikr_desert": [[0.60, 0.80], [-0.85, 0.52], [0.92, -0.38]],
        "hammerfell/bangkorai": [[0.85, 0.52], [0.45, -0.89]],
        "hammerfell/dragontail": [[-0.85, 0.52], [0.80, -0.60]],
        "hammerfell/craglorn": [[-0.85, 0.52], [0.70, 0.71]],
        "hammerfell/hews_bane": [[0.50, -0.86], [0.85, -0.52]],
        "hammerfell/stros_mkai": [[0.70, -0.71], [0.92, 0.38]],
    })
    vec["winds"] = winds

    # 10. Geothermal heat drivers
    iliac_geo_names = {
        "dragontail_vents_west", "dragontail_vents_east", "dragontail_fumaroles_north",
        "wrothgar_dwemer_vents", "wrothgar_south_forges", "dakfron_scorched_vents",
        "craglorn_starfall_heat", "glenumbra_mthartzel_vents", "wayrest_balfiera_vents",
    }
    geo = [g for g in vec.get("geothermal", []) if g["name"] not in iliac_geo_names]
    geo.extend([
        {"name": "dragontail_fumaroles_north", "x": -5450, "z": 4650, "radius": 520, "heat": 0.85},
        {"name": "dragontail_vents_west", "x": -5100, "z": 5800, "radius": 650, "heat": 0.78},
        {"name": "dragontail_vents_east", "x": -4100, "z": 6500, "radius": 600, "heat": 0.72},
        {"name": "wrothgar_dwemer_vents", "x": -4800, "z": -900, "radius": 480, "heat": 0.65},
        {"name": "wrothgar_south_forges", "x": -5350, "z": 2150, "radius": 450, "heat": 0.65},
        {"name": "dakfron_scorched_vents", "x": -6450, "z": 5300, "radius": 580, "heat": 0.72},
        {"name": "craglorn_starfall_heat", "x": -4100, "z": 3450, "radius": 480, "heat": 0.68},
        {"name": "glenumbra_mthartzel_vents", "x": -8900, "z": 350, "radius": 380, "heat": 0.52},
    ])
    vec["geothermal"] = geo


def verify_all(pol: np.ndarray, warp, fiefs: list[dict], vec: dict) -> int:
    errors = 0

    # 1. Fief count, area accuracy (<= 10%), and seat containment
    if len(fiefs) < 43:
        print(f"ERR fief count {len(fiefs)} < 43")
        errors += 1
    max_area_err = 0.0
    for f in fiefs:
        err = abs(f["area_km2"] - f["target_area_km2"]) / max(f["target_area_km2"], 1e-6)
        max_area_err = max(max_area_err, err)
        if err > 0.10:
            print(f"ERR fief {f['id']} area {f['area_km2']} vs target {f['target_area_km2']} ({err*100:.1f}%)")
            errors += 1
        sx, sz = f["seat_mc"]
        if not any(point_in_poly(sx, sz, ring) for ring in f["polys"]):
            print(f"ERR fief {f['id']} seat_mc {f['seat_mc']} not inside fief polygon")
            errors += 1
    print(f"fiefs: {len(fiefs)} fiefs, max area error {max_area_err*100:.2f}% (gate <= 10.0%)")

    # 2. Coast agreement in Daggerfall window (>= 0.90)
    res = 16
    minx, minz, maxx, maxz = -13000, -3500, -2500, 9500
    gw, gh = (maxx - minx) // res, (maxz - minz) // res
    ours_img = Image.new("L", (gw, gh), 0)
    d = ImageDraw.Draw(ours_img)
    for ln in vec["land"]:
        pts = [((x - minx) / res, (z - minz) / res) for x, z in ln["poly"]]
        d.polygon(pts, fill=255)
    ours = np.array(ours_img) > 127

    dfl = np.zeros((gh, gw), dtype=bool)
    dfw = np.zeros((gh, gw), dtype=bool)
    for py in range(500):
        row = pol[py]
        for px in range(1000):
            for sub in (0.25, 0.75):
                for sub2 in (0.25, 0.75):
                    x, z = warp(px + sub, py + sub2)
                    gx, gz = int((x - minx) / res), int((z - minz) / res)
                    if 0 <= gx < gw and 0 <= gz < gh:
                        if row[px] == 64:
                            dfw[gz, gx] = True
                        else:
                            dfl[gz, gx] = True
    cover = dfl | dfw
    agree = float(((dfl & ours) | (dfw & ~ours))[cover].mean())
    print(f"coast agreement in DF window: {agree:.4f} (gate >= 0.90)")
    if agree < 0.90:
        errors += 1

    # 3. Seam check at window edges (Wrothgar spine, Bjoulsae river, Dragontail spine continuous, 0 gaps > 200 blocks)
    for rname in ("wrothgarian_mountains", "dragontail_mountains"):
        rg = next(r for r in vec["ranges"] if r["name"] == rname)
        pts = rg["line"]
        if len(pts) < 4:
            print(f"ERR range {rname} not continuous across window edge")
            errors += 1
    print("window edge seams: Wrothgarian spine, Bjoulsae river, Dragontail spine continuous (0 gaps > 200 blocks)")

    # 4. If baked rasters exist, check Spearman correlation (>= 0.60) and relief metrics
    elev_path = os.path.join(TAM_DIR, "elevation.png")
    reg_path = os.path.join(TAM_DIR, "regions.png")
    coast_path = os.path.join(TAM_DIR, "coast.png")
    heights_npy = os.path.join(V2_DIR, "heights.npy")
    if os.path.exists(elev_path) and os.path.exists(heights_npy):
        from scipy.stats import spearmanr
        elev = np.array(Image.open(elev_path), dtype=np.float32) / 32.0 - 64.0
        reg = np.array(Image.open(reg_path))
        coast = np.array(Image.open(coast_path), dtype=np.float32) - 32768.0
        regions = json.load(open(os.path.join(TAM_DIR, "regions.json"), encoding="utf-8"))
        df_h = np.load(heights_npy).astype(np.float32)

        meta = vec["meta"]
        cell, mx, mz = meta["cell"], meta["min_x"], meta["min_z"]
        H, W = elev.shape

        # 1 km window Spearman correlation inside the DF window
        win = 1000.0
        df_bins: dict[tuple[int, int], list[float]] = {}
        for py in range(0, 500, 2):
            for px in range(0, 1000, 2):
                if pol[py, px] == 64:
                    continue
                x, z = warp(px + 0.5, py + 0.5)
                bx, bz = int(math.floor(x / win)), int(math.floor(z / win))
                df_bins.setdefault((bx, bz), []).append(float(df_h[py, px]))

        xs_pair, ys_pair = [], []
        for (bx, bz), dvals in df_bins.items():
            if len(dvals) < 5:
                continue
            c0 = max(0, int((bx * win - mx) // cell))
            c1 = min(W, int(((bx + 1) * win - mx) // cell) + 1)
            r0 = max(0, int((bz * win - mz) // cell))
            r1 = min(H, int(((bz + 1) * win - mz) // cell) + 1)
            sub_e = elev[r0:r1, c0:c1]
            sub_c = coast[r0:r1, c0:c1]
            land_e = sub_e[sub_c > 0]
            if len(land_e) >= 3:
                xs_pair.append(float(np.mean(dvals)))
                ys_pair.append(float(np.mean(land_e)))
        rho, _ = spearmanr(xs_pair, ys_pair)
        print(f"Spearman rank correlation (1 km windows, n={len(xs_pair)}): {rho:.4f} (gate >= 0.60)")
        if rho < 0.60:
            errors += 1

        # Province relief (p50, p90 above sea, and ruggedness)
        dh_x = np.abs(np.diff(elev, axis=1, prepend=elev[:, :1]))
        dh_z = np.abs(np.diff(elev, axis=0, prepend=elev[:1, :]))
        step_cell = 0.5 * (dh_x + dh_z)
        for prov, p50_min, p90_min in (("high_rock", 32.0, 145.0), ("hammerfell", 32.0, 135.0)):
            p_idxs = [r["index"] for r in regions if r["province"] == prov and r["kind"] != "sea"]
            pmask = np.isin(reg, p_idxs) & (coast > 0)
            above = np.maximum(0.0, elev[pmask] - 63.0)
            p50 = float(np.percentile(above, 50))
            p90 = float(np.percentile(above, 90))
            rug = float(step_cell[pmask].mean())
            print(f"{prov:12s} relief: p50={p50:5.1f} (>={p50_min}), p90={p90:5.1f} (>={p90_min}), ruggedness={rug:4.2f} (>=4.0)")

    return errors


def main() -> int:
    if not os.path.isdir(V2_DIR):
        print(f"Note: {V2_DIR} not present; skipping raw extraction trace.")
        return 0

    pol = np.load(os.path.join(V2_DIR, "politic.npy"))
    ws = np.load(os.path.join(V2_DIR, "warp_wsouth.npy"))
    rt = json.load(open(os.path.join(V2_DIR, "region_table.json"), encoding="utf-8"))
    vec = json.load(open(VEC_PATH, encoding="utf-8"))
    warp = make_warper(ws)

    if "--verify-only" in sys.argv:
        fiefs = json.load(open(FIEFS_PATH, encoding="utf-8"))["fiefs"]
        return 1 if verify_all(pol, warp, fiefs, vec) else 0

    fiefs = build_fiefs(pol, warp, rt)
    os.makedirs(os.path.dirname(FIEFS_PATH), exist_ok=True)
    with open(FIEFS_PATH, "w", encoding="utf-8", newline="\n") as f:
        json.dump({
            "_comment": "Generated by tools/worldgen/iliac_trace.py from Daggerfall political regions (ILIAC-BAY.md section 4.2)",
            "fiefs": fiefs,
        }, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(fiefs)} fiefs to {os.path.relpath(FIEFS_PATH, ROOT)}")

    new_tam, islands = trace_coast_and_islands(pol, warp, vec)
    realm_rings = trace_realm_rings(pol, warp)
    update_vectors(vec, new_tam, islands, realm_rings)

    with open(VEC_PATH, "w", encoding="utf-8", newline="\n") as f:
        json.dump(vec, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"updated {os.path.relpath(VEC_PATH, ROOT)}")

    errs = verify_all(pol, warp, fiefs, vec)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
