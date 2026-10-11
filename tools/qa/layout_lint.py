"""Layout lint: place data against the map rasters. No Minecraft, no chunk generation (a few seconds).

usage: py -3 -I tools/qa/layout_lint.py [--all]      (from the repo root)
Prints a short summary, writes tools/qa/out/layout_lint.txt (every finding), exits 1 on errors.

Checks (E = error, W = warning):
  land     every place is on land (not sea, not a lake) unless it is marine (variant shipwreck / lighthouse / island /
           dock / pier / ship ..., or id says so). Inside the Tamriel raster: E if in a sea region or > 24 blocks
           off the coast (what the road planner's Landmass calls sea), W if on the shore line or in a lake. Outside
           the raster (foreign lands): E if the Beyond noise port (tools/provinces.py) says open sea.
  spacing  no two places closer than MIN_SPACING blocks (settlements: their combined footprint) unless they are one
           complex (id prefix, or a site inside a settlement's radius).
  region   the declared region exists, is of the right province, and lies within REGION_SLACK blocks of the place.
  roads    settlements need a hub neighbour on their landmass (the road planner's MINOR_LINK / FARM_LINK), unless the
           place JSON carries "isolated": true. Off-raster settlements are checked against each other.
  dupes    near-identical ids at near-identical positions (septimus_outpost vs septimus_signuss_outpost).
Stdlib + numpy + PIL only (rasters are read through tools/provinces.py's TamrielRasters / Beyond).
"""
import importlib.util
import json
import math
import os
import re
import sys
import time

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
PLACES = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft", "tamriel", "places")
OUT = os.path.join(ROOT, "tools", "qa", "out", "layout_lint.txt")

MIN_SPACING = 24        # blocks, between any two non-complex places (their centres)
SETTLEMENT_TYPES = {"capital", "city", "town", "village", "farm"}
HUBS = {"capital", "city", "town", "village"}
MARINE_VARIANTS = {"shipwreck", "lighthouse", "island", "dock", "pier", "harbor", "harbour", "ship", "wreck", "sea_vault",
                   "reef", "sea_cave", "ferry", "boat"}
MARINE_WORDS = ("wreck", "shipwreck", "dock", "pier", "lighthouse", "_ship", "ship_", "reef", "island", "isle", "ferry")
SHORE_ERR = -24         # coast distance below which Landmass counts a column as sea
REGION_SLACK = 250      # blocks a place may sit from the border of its declared region before it is an error
MINOR_LINK, FARM_LINK = 2400, 3200


def load_provinces():
    spec = importlib.util.spec_from_file_location("skycraft_provinces", os.path.join(ROOT, "tools", "provinces.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules["skycraft_provinces"] = mod
    spec.loader.exec_module(mod)
    return mod


def load_places():
    idx = json.load(open(os.path.join(PLACES, "index.json"), encoding="utf-8"))
    out = []
    for f in idx:
        p = os.path.join(PLACES, f)
        if not os.path.exists(p):
            continue
        for e in json.load(open(p, encoding="utf-8")):
            e["_file"] = f
            out.append(e)
    return out


def is_marine(p):
    v = p.get("variant") or ""
    if v in MARINE_VARIANTS:
        return True
    pid = p["id"]
    return any(w in pid for w in MARINE_WORDS)


class Land:
    """Land classification at a block column; shared with the fix helpers."""

    def __init__(self, pv):
        self.pv = pv
        self.c = pv.TamrielRasters()
        self.beyond = pv.Beyond

    def probe(self, x, z):
        """(kind, detail): kind in 'land', 'shore', 'lake', 'sea'; detail is a short string."""
        c = self.c
        if c.inside(x, z):
            cx, cz = c.cell(x, z)
            r = c.by_index.get(int(c.reg[cz, cx]))
            co, lk = float(c.coast[cz, cx]), float(c.lake[cz, cx])
            rid = r["id"] if r else "?"
            if r is None or r["kind"] == "sea" or r["province"] == "sea" or co < SHORE_ERR:
                return "sea", f"{rid} coast {co:.0f}"
            if lk > 0:
                return "lake", f"{rid} lake {lk:.0f}"
            if co <= 0:
                return "shore", f"{rid} coast {co:.0f}"
            return "land", f"{rid} coast {co:.0f}"
        f = self.beyond.land_at(x, z)
        if f is None:
            return "sea", "open sea (outside raster)"
        return "land", f"{f[0]} field {f[1]:.0f}"

    def inland(self, x, z):
        """Blocks from the coast (field value for foreign lands); negative at sea. Lakes count as -1."""
        c = self.c
        if c.inside(x, z):
            cx, cz = c.cell(x, z)
            r = c.by_index.get(int(c.reg[cz, cx]))
            if r is None or r["kind"] == "sea" or c.lake[cz, cx] > 0:
                return -1.0
            return float(c.coast[cz, cx])
        f = self.beyond.land_at(x, z)
        return -1.0 if f is None else float(f[1])

    def region_at(self, x, z):
        c = self.c
        if c.inside(x, z):
            r = c.region(x, z)
            return r["id"] if r else None
        f = self.beyond.land_at(x, z)
        return f[0] if f else None


def landmass_labels(c):
    """Flood fill of the raster's land cells (coast > SHORE_ERR, region != 0), 4-connected. Returns int32 H x W."""
    land = (c.reg != 0) & (c.coast > SHORE_ERR)
    H, W = land.shape
    lab = np.zeros((H, W), np.int32)
    nxt = 1
    flat = land.ravel()
    labf = lab.ravel()
    for start in np.flatnonzero(flat):
        if labf[start]:
            continue
        stack = [start]
        labf[start] = nxt
        while stack:
            i = stack.pop()
            x = i % W
            for j in (i - W, i + W, i - 1 if x else -1, i + 1 if x < W - 1 else -1):
                if 0 <= j < H * W and flat[j] and not labf[j]:
                    labf[j] = nxt
                    stack.append(j)
        nxt += 1
    return lab


def region_cells(c):
    """region index -> (xs, zs) arrays of cell centres, for the distance-to-region check."""
    out = {}
    order = np.argsort(c.reg.ravel(), kind="stable")
    flat = c.reg.ravel()[order]
    cuts = np.flatnonzero(np.diff(flat)) + 1
    W = c.W
    for chunk, idxs in zip(np.split(flat, cuts), np.split(order, cuts)):
        zs, xs = np.divmod(idxs, W)
        out[int(chunk[0])] = (c.MIN_X + xs * c.CELL + c.CELL // 2, c.MIN_Z + zs * c.CELL + c.CELL // 2)
    return out


def tokens(pid):
    return [t for t in pid.split("_") if t]


def main(argv):
    t0 = time.time()
    pv = load_provinces()
    land = Land(pv)
    c = land.c
    places = load_places()
    errs, warns = [], []

    def E(check, p, msg):
        errs.append((check, p["id"], f"{p['id']} ({p['type']}/{p.get('variant') or '-'} @ {p['x']},{p['z']} in {p['_file']}): {msg}"))

    def W(check, p, msg):
        warns.append((check, p["id"], f"{p['id']} ({p['type']}/{p.get('variant') or '-'} @ {p['x']},{p['z']} in {p['_file']}): {msg}"))

    # ---- land
    for p in places:
        kind, detail = land.probe(p["x"], p["z"])
        if kind == "land" or is_marine(p):
            continue
        if kind == "sea" and p["type"] in ("capital", "city") and land.inland(p["x"], p["z"]) > 2 * SHORE_ERR:
            W("land", p, f"on the shore line of a harbour city ({detail}); the road planner snaps to land within 160")
        elif kind == "sea":
            E("land", p, f"in the sea ({detail})")
        elif p["type"] not in ("dungeon_fixed",) or kind != "shore":
            W("land", p, f"on the {kind} ({detail})")

    # ---- region
    cells = region_cells(c)
    for p in places:
        rid = p.get("region", "")
        x, z = p["x"], p["z"]
        prov = rid.split("/")[0]
        actual = land.region_at(x, z)
        if actual == rid or (actual or "").startswith("sea/"):
            continue  # a place in the water is the land check's business
        if prov in pv.FOREIGN:
            at = land.beyond.land_at(x, z)
            if at is None:
                continue  # in the sea: reported by the land check
            if actual.split("/")[0] != prov:
                E("region", p, f"declared {rid} but the map has {actual}")
            else:
                W("region", p, f"declared {rid}, map has {actual} (sub-region border noise)")
            continue
        r = c.by_id.get(rid)
        if r is None:
            E("region", p, f"declared region {rid} does not exist")
            continue
        if not c.inside(x, z):
            E("region", p, f"declared {rid} but the place is outside the raster")
            continue
        xs, zs = cells[r["index"]]
        d = float(np.sqrt(np.min((xs - x) ** 2 + (zs - z) ** 2))) if len(xs) else 1e9
        if d > REGION_SLACK:
            E("region", p, f"declared {rid}, but the map has {actual} here; {rid} is {d:.0f} blocks away")
        elif d > 0:
            W("region", p, f"declared {rid}, map has {actual} ({d:.0f} blocks from the declared region)")

    # ---- spacing and duplicates (pairwise by sorted x sweep)
    n = len(places)
    xs = np.array([p["x"] for p in places], np.float64)
    zs = np.array([p["z"] for p in places], np.float64)
    order = np.argsort(xs)
    ids = [p["id"] for p in places]
    maxwin = 500.0  # duplicates are looked for within this distance
    seen_pairs = set()
    for ai in range(n):
        i = order[ai]
        for bi in range(ai + 1, n):
            j = order[bi]
            dx = xs[j] - xs[i]
            if dx > maxwin:
                break
            dz = zs[j] - zs[i]
            d = math.hypot(dx, dz)
            if d > maxwin:
                continue
            a, b = places[i], places[j]
            ia, ib = a["id"], b["id"]
            complex_ = ia.startswith(ib) or ib.startswith(ia)
            sa, sb = a["type"] in SETTLEMENT_TYPES, b["type"] in SETTLEMENT_TYPES
            # a site inside a settlement's radius belongs to that settlement's complex
            if (sa and not sb and d <= a.get("radius", 32)) or (sb and not sa and d <= b.get("radius", 32)):
                complex_ = True
            # near-identical id at a near position
            ta, tb = set(tokens(ia)), set(tokens(ib))
            stop = {"the", "of", "a", "and", "in", "at"}
            ta2, tb2 = ta - stop, tb - stop
            same_name = (ia == ib or (ta2 and tb2 and (ta2 <= tb2 or tb2 <= ta2)
                                      and min(len(ta2), len(tb2)) >= 2 and len(ta2 ^ tb2) <= 2))
            if same_name and d <= 400 and ia != ib and a["type"] == b["type"] and a.get("variant") == b.get("variant"):
                key = tuple(sorted((ia, ib)))
                if key not in seen_pairs:
                    seen_pairs.add(key)
                    E("dupes", a, f"looks like a duplicate of {ib} ({d:.0f} blocks apart)")
                continue
            if d < 3 and (a.get("variant") == b.get("variant")) and a["type"] == b["type"] and not complex_:
                E("dupes", a, f"same type/variant at the same spot as {ib} ({d:.0f} blocks)")
                continue
            if complex_:
                continue
            lim = MIN_SPACING
            if sa and sb:
                lim = max(lim, 0.5 * (a.get("radius", 32) + b.get("radius", 32)))
            if d < lim:
                E("spacing", a, f"{d:.0f} blocks from {ib} (min {lim:.0f})")

    # ---- roads
    lab = landmass_labels(c)
    hubs_by_land = {}
    settlements = [p for p in places if p["type"] in SETTLEMENT_TYPES and not p["id"].startswith("roadside_")]
    for p in settlements:
        if p["type"] in HUBS:
            if c.inside(p["x"], p["z"]):
                cx, cz = c.cell(p["x"], p["z"])
                key = int(lab[cz, cx]) or ("near", p["id"])
            else:
                key = "off"
            hubs_by_land.setdefault(key, []).append(p)
    off_raster = 0
    for p in settlements:
        if p.get("isolated"):
            continue
        x, z = p["x"], p["z"]
        limit = MINOR_LINK if p["type"] in HUBS else FARM_LINK * 2
        if not c.inside(x, z):
            off_raster += 1
            peers = [q for q in hubs_by_land.get("off", []) if q is not p and math.hypot(q["x"] - x, q["z"] - z) <= limit]
            if not peers:
                W("roads", p, f"no other settlement within {limit} blocks on its (off-raster) land")
            continue
        cx, cz = c.cell(x, z)
        l = int(lab[cz, cx])
        if l == 0:
            near = lab[max(cz - 5, 0):cz + 6, max(cx - 5, 0):cx + 6]
            vals = near[near > 0]
            l = int(np.bincount(vals).argmax()) if len(vals) else 0
        if l == 0:
            E("roads", p, "no landmass within 160 blocks: the road planner skips it (not on land)")
            continue
        peers = [q for q in hubs_by_land.get(l, []) if q is not p and math.hypot(q["x"] - x, q["z"] - z) <= limit]
        if not peers and len(hubs_by_land.get(l, [])) > (1 if p["type"] in HUBS else 0):
            W("roads", p, f"no hub within {limit} blocks (planner joins it to the nearest group; check the link is sane)")
        elif not peers and p["type"] in HUBS:
            W("roads", p, "only settlement on its landmass: no roads possible (flag \"isolated\": true)")
        elif not peers:
            E("roads", p, f"farm with no hub on its landmass within {limit} blocks")

    # ---- report
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    checks = ["land", "spacing", "region", "roads", "dupes"]
    lines = []
    for sev, items in (("ERROR", errs), ("WARN", warns)):
        for chk in checks:
            sel = sorted(i for i in items if i[0] == chk)
            if sel:
                lines.append(f"== {sev} {chk} ({len(sel)})")
                lines.extend("  " + i[2] for i in sel)
    if off_raster:
        lines.append(f"== INFO: {off_raster} settlements lie outside the Tamriel raster; the road planner "
                     "(Landmass.near -> UNKNOWN) skips them")
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    summ = ", ".join(f"{k} {sum(1 for e in errs if e[0] == k)}E/{sum(1 for e in warns if e[0] == k)}W" for k in checks)
    for e in errs[:12]:
        print("ERR", e[2])
    if len(errs) > 12:
        print(f"... {len(errs) - 12} more errors in {os.path.relpath(OUT, ROOT)}")
    print(f"layout lint: {len(places)} places, {len(errs)} errors, {len(warns)} warnings ({summ}) in {time.time() - t0:.1f}s")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
