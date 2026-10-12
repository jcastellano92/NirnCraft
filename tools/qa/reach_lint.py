"""Reachability lint: does a road or trail reach every place? No Minecraft, no world (a few seconds).

usage: py -3 -I tools/qa/reach_lint.py [--area whiterun|skyrim|all] [--top N] [--before] [--json out.json]
Prints a short summary, writes tools/qa/out/reach_lint.txt (every finding), exits 1 on errors.

The planned network is rebuilt offline by porting the rules of roads/RoadGraph.java (named roads from
skycraft/roads/named_roads.json, the greedy spanner, group joins, farm lanes; landmasses from skycraft/baked/landmass.bin)
and roads/WorldPlan.java (trails to destinations: TRAIL_RANGE, TRAIL_MIN, FOOTPATH_RANGE for tourist-grade places).
Links are straight segments here, the planner's routes wander a little around them, so ranges carry a ~10% slack, and
the terrain weights ignore ridges. --before lints with the trail rules of before the reach pass (no word walls, dragon
mounds or long footpaths). Keep the constants below in step with RoadGraph / WorldPlan.

Classes per place:
  settled   inside a settlement's footprint (a temple, a keep, a district...)
  on-road   within ON_ROAD blocks of a road (no trail needed)
  trail     a trail/footpath is (or will be) planned from the nearest road point
  isolated  nothing reaches it: farther than its range from every road, or a type that gets no trail
Errors: a settlement not connected to the capital network of its landmass; a settlement with no road at all.
"""
import heapq
import json
import math
import os
import struct
import sys
import time
import zlib
from collections import defaultdict

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft")
MAPDIR = os.path.join(RES, "tamriel")
PLACES = os.path.join(MAPDIR, "places")
OUT = os.path.join(ROOT, "tools", "qa", "out", "reach_lint.txt")

# ---- constants mirrored from the Java planner
STRETCH = 1.45
MAJOR_LINK, FARM_LINK, MINOR_LINK = 4600, 1600, 2400
DEFAULT_BRIDGE = 64
CELL, MIN_X, MIN_Z = 32, -15360, -5120
TRAIL_RANGE = 300          # WorldPlan.TRAIL_RANGE: ordinary trail destinations
TRAIL_MIN = 20             # right beside a road: no trail
ON_ROAD = 24
FOOTPATH_RANGE = 900       # tourist-grade destinations farther than TRAIL_RANGE get a long footpath (WorldPlan.FOOTPATH_RANGE)
SLACK = 1.10               # straight link vs the A* route

SETTLE = {"capital", "city", "town", "village", "farm"}
TRAIL_BASE = {"dungeon_fixed", "fort", "standing_stone", "daedric_shrine", "landmark"}   # before the reach pass
TRAIL_TYPES = TRAIL_BASE | {"word_wall", "dragon_mound"}                               # RoadGraph.isTrailDestination
# RoadGraph.isTourist: dungeon / landmark variants worth a long footpath
TOURIST_VARIANTS = {"altar", "monument", "stones", "ruin", "keep", "barrow", "nordic_ruin", "moon_temple", "dwemer_ruin",
                    "ayleid_ruin", "velothi_tomb", "xanmeer", "daedric_ruin", "tower", "mage_tower", "watchtower",
                    "lighthouse", "dragon_lair", "imperial_crypt", "yokudan_tomb", "orc_ruin", "giant_camp",
                    "fort_mage", "fort_vampire", "oblivion_gate", "pass", "peak", "waterfall", "sixth_house_base",
                    "crystal_vault", "tree_tomb", "breton_crypt", "ice_cave"}
MINOR_LANDMARKS = {"clearing", "rock", "source", "island", "district"}

AREAS = {  # name -> (x0, z0, x1, z1) block box, Whiterun = 0,0 (north = -Z)
    "whiterun": (-1500, -1100, 1500, 1400),
    "skyrim": (-3300, -2600, 3000, 2000),
}


def load_places():
    idx = json.load(open(os.path.join(PLACES, "index.json"), encoding="utf-8"))
    out = []
    for f in idx:
        p = os.path.join(PLACES, f)
        if os.path.exists(p):
            for e in json.load(open(p, encoding="utf-8")):
                e["_file"] = f
                out.append(e)
    return out


class Map:
    def __init__(self):
        self.reg = np.array(Image.open(os.path.join(MAPDIR, "regions.png")))
        self.elev = np.array(Image.open(os.path.join(MAPDIR, "elevation.png"))).astype(np.float32) / 32 - 64
        self.coast = np.array(Image.open(os.path.join(MAPDIR, "coast.png"))).astype(np.float32) - 32768
        feat = np.array(Image.open(os.path.join(MAPDIR, "features.png")).convert("RGBA"))
        self.lake = (feat[..., 2].astype(np.float32) - 128) * 0.5
        self.H, self.W = self.reg.shape
        self.max_x, self.max_z = MIN_X + self.W * CELL, MIN_Z + self.H * CELL
        self.wet = (self.reg == 0) | (self.coast < 0) | (self.lake > 0)
        with open(os.path.join(RES, "baked", "landmass.bin"), "rb") as fh:
            raw = fh.read()
        raw = zlib.decompress(raw[2 + struct.unpack(">H", raw[:2])[0]:])  # Bake: UTF hash header + deflated payload
        _, _, n = struct.unpack(">iii", raw[:12])
        self.lm = np.frombuffer(raw[12:12 + 2 * n], dtype=">i2").astype(np.int16).reshape(self.H, self.W)
        o = 12 + 2 * n
        m = struct.unpack(">i", raw[o:o + 4])[0]
        o += 4
        self.off = {}
        for _ in range(m):
            k, v = struct.unpack(">qh", raw[o:o + 10])
            o += 10
            self.off[k] = v

    def inside(self, x, z):
        return MIN_X <= x < self.max_x and MIN_Z <= z < self.max_z

    def at(self, x, z):
        if not self.inside(x, z):
            cx, cz = math.floor(x / CELL), math.floor(z / CELL)
            k = (cx << 32) ^ (cz & 0xffffffff)
            k = (k + (1 << 63)) % (1 << 64) - (1 << 63)
            return self.off.get(k, -1)
        return int(self.lm[(z - MIN_Z) // CELL, (x - MIN_X) // CELL])

    def near(self, x, z, search):
        own = self.at(x, z)
        if own > 0:
            return own
        r = max(1, search // CELL)
        best, bd = own, None
        for dz in range(-r, r + 1):
            for dx in range(-r, r + 1):
                l = self.at(x + dx * CELL, z + dz * CELL)
                if l <= 0:
                    continue
                d = dx * dx + dz * dz
                if bd is None or d < bd:
                    bd, best = d, l
        return best

    def _line(self, x0, z0, x1, z1, step):
        ln = math.hypot(x1 - x0, z1 - z0)
        n = max(2, int(ln / step))
        t = np.linspace(0.0, 1.0, n + 1)
        xs = np.rint(x0 + (x1 - x0) * t).astype(np.int64)
        zs = np.rint(z0 + (z1 - z0) * t).astype(np.int64)
        ok = (xs >= MIN_X) & (xs < self.max_x) & (zs >= MIN_Z) & (zs < self.max_z)
        cx = np.clip((xs - MIN_X) // CELL, 0, self.W - 1)
        cz = np.clip((zs - MIN_Z) // CELL, 0, self.H - 1)
        return ln, n, ok, cx, cz

    def factor(self, x0, z0, x1, z1):
        """RoadGraph.terrainFactor without ridge and corridor terms."""
        ln, n, ok, cx, cz = self._line(x0, z0, x1, z1, 64)
        el = np.where(ok, self.elev[cz, cx], np.nan)
        climb = float(np.nansum(np.abs(np.diff(el)))) if ok.sum() > 1 else 0.0
        water = float((self.wet[cz, cx] & ok).sum())
        return 1.0 + 6.0 * climb / max(1.0, ln) + 2.5 * water / (n + 1)

    def sea_run(self, x0, z0, x1, z1):
        ln, n, ok, cx, cz = self._line(x0, z0, x1, z1, 16)
        sea = ((self.reg[cz, cx] == 0) | (self.coast[cz, cx] < 0)) & ok
        if not sea.any():
            return 0.0
        best = run = 0
        for s in sea:
            run = run + 1 if s else 0
            best = max(best, run)
        return best * ln / n


def rank(p):
    return {"capital": 0, "city": 0, "town": 1, "village": 2}.get(p["type"], 3)


def build_graph(mp, places):
    nodes, land = [], {}
    for p in places:
        if p["type"] not in SETTLE or p["id"].startswith("roadside_"):
            continue
        l = mp.near(p["x"], p["z"], 160)
        if l <= 0:
            continue
        nodes.append(p)
        land[p["id"]] = l
    by_id = {p["id"]: p for p in nodes}
    tf_cache = {}

    def tf(a, b):
        k = (a["id"], b["id"]) if a["id"] < b["id"] else (b["id"], a["id"])
        v = tf_cache.get(k)
        if v is None:
            v = tf_cache[k] = mp.factor(a["x"], a["z"], b["x"], b["z"])
        return v

    def length(a, b):
        return math.hypot(a["x"] - b["x"], a["z"] - b["z"])

    edges, adj, pairs = [], defaultdict(list), set()

    def add(a, b, kind, name=""):
        if a is b:
            return False
        k = (a["id"], b["id"]) if a["id"] < b["id"] else (b["id"], a["id"])
        if k in pairs:
            return False
        pairs.add(k)
        edges.append((a, b, kind, name))
        adj[a["id"]].append(b)
        adj[b["id"]].append(a)
        return True

    def add_split(a, b, kind, name):
        dx, dz = b["x"] - a["x"], b["z"] - a["z"]
        len2 = dx * dx + dz * dz
        ln = math.sqrt(len2)
        via = []
        if ln > 200:
            tol = min(260, max(60, ln * 0.12))
            for c in nodes:
                if c is a or c is b or rank(c) == 3 or land[c["id"]] != land[a["id"]]:
                    continue
                t = ((c["x"] - a["x"]) * dx + (c["z"] - a["z"]) * dz) / len2
                if t < 0.06 or t > 0.94:
                    continue
                if math.hypot(a["x"] + dx * t - c["x"], a["z"] + dz * t - c["z"]) > tol:
                    continue
                via.append((t, c))
        via.sort(key=lambda v: v[0])
        prev = a
        for _, c in via:
            add(prev, c, kind, name)
            prev = c
        add(prev, b, kind, name)

    def distance(a, b, limit):
        dist = {a["id"]: 0.0}
        heap = [(0.0, a["id"], a)]
        while heap:
            d, pid, p = heapq.heappop(heap)
            if pid == b["id"]:
                return d
            if d > dist.get(pid, 1e18) or d > limit:
                continue
            for o in adj[pid]:
                nd = d + length(p, o) * tf(p, o)
                if nd < dist.get(o["id"], 1e18) and nd <= limit:
                    dist[o["id"]] = nd
                    heapq.heappush(heap, (nd, o["id"], o))
        return math.inf

    def components(plist):
        comp, nxt = {}, 0
        for p in plist:
            if p["id"] in comp:
                continue
            comp[p["id"]] = nxt
            stack = [p]
            while stack:
                cur = stack.pop()
                for o in adj[cur["id"]]:
                    if o["id"] not in comp:
                        comp[o["id"]] = nxt
                        stack.append(o)
            nxt += 1
        return comp

    named = json.load(open(os.path.join(RES, "roads", "named_roads.json"), encoding="utf-8"))
    for n in named:
        kind = {"road": "road", "lane": "lane"}.get(n.get("class", "highway"), "highway")
        bridge = n.get("bridge", DEFAULT_BRIDGE)
        for i in range(len(n["route"]) - 1):
            a, b = by_id.get(n["route"][i]), by_id.get(n["route"][i + 1])
            if a is None or b is None:
                continue
            if bridge <= DEFAULT_BRIDGE and land[a["id"]] != land[b["id"]]:
                continue
            add_split(a, b, kind, n["id"])
    hubs = [p for p in nodes if rank(p) < 3]
    cands = []
    for i, a in enumerate(hubs):
        for j in range(i + 1, len(hubs)):
            b = hubs[j]
            if land[a["id"]] != land[b["id"]]:
                continue
            d = length(a, b)
            if d > (MAJOR_LINK if rank(a) == 0 and rank(b) == 0 else MINOR_LINK):
                continue
            if mp.sea_run(a["x"], a["z"], b["x"], b["z"]) > 2 * DEFAULT_BRIDGE:
                continue
            cands.append((d * tf(a, b), i, j))
    cands.sort()
    for w, i, j in cands:
        a, b = hubs[i], hubs[j]
        if distance(a, b, w * STRETCH) <= w * STRETCH:
            continue
        add_split(a, b, "highway" if rank(a) == 0 and rank(b) == 0 else "road", "")
    hx = np.array([p["x"] for p in hubs], np.float64)
    hz = np.array([p["z"] for p in hubs], np.float64)
    hl = np.array([land[p["id"]] for p in hubs])
    while True:
        comp = components(hubs)
        hc = np.array([comp[p["id"]] for p in hubs])
        d2 = (hx[:, None] - hx[None, :]) ** 2 + (hz[:, None] - hz[None, :]) ** 2
        ok = (hc[:, None] < hc[None, :]) & (hl[:, None] == hl[None, :])
        if not ok.any():
            break
        i, j = np.unravel_index(np.argmin(np.where(ok, d2, np.inf)), d2.shape)
        ba, bb = hubs[int(i)], hubs[int(j)]
        add(ba, bb, "highway" if rank(ba) == 0 and rank(bb) == 0 else "road")
    # farms, Prim style: the nearest farm to the attached set first (a farm-to-farm hop counts 1.6x)
    farms = [f for f in nodes if rank(f) == 3 and not adj[f["id"]]]
    if farms:
        fx = np.array([f["x"] for f in farms], np.float64)
        fz = np.array([f["z"] for f in farms], np.float64)
        fl = np.array([land[f["id"]] for f in farms])
        bestd = np.full(len(farms), np.inf)
        besto = [None] * len(farms)
        for k in range(len(farms)):
            m = hl == fl[k]
            if m.any():
                dd = np.sqrt((hx[m] - fx[k]) ** 2 + (hz[m] - fz[k]) ** 2)
                a = int(np.argmin(dd))
                bestd[k] = dd[a]
                besto[k] = [h for h, mm in zip(hubs, m) if mm][a]
        alive = np.ones(len(farms), bool)
        while alive.any():
            k = int(np.argmin(np.where(alive, bestd, np.inf)))
            if not alive[k] or not np.isfinite(bestd[k]) or bestd[k] > FARM_LINK * 2:
                break
            add(farms[k], besto[k], "lane")
            alive[k] = False
            dd = 1.6 * np.sqrt((fx - fx[k]) ** 2 + (fz - fz[k]) ** 2)
            upd = alive & (fl == fl[k]) & (dd < bestd)
            for q in np.flatnonzero(upd):
                bestd[q] = dd[q]
                besto[q] = farms[k]
        for k in np.flatnonzero(alive):
            f = farms[int(k)]
            bd, bo = None, None
            for o in nodes:
                if o is f or land[o["id"]] != land[f["id"]]:
                    continue
                d = (o["x"] - f["x"]) ** 2 + (o["z"] - f["z"]) ** 2
                if bd is None or d < bd:
                    bd, bo = d, o
            if bo is not None:
                add(f, bo, "lane")
    return nodes, land, edges, components(nodes)


class Segs:
    """Road segments bucketed on a 256-block grid for nearest-point queries."""

    def __init__(self, segs):
        self.segs = segs
        self.grid = defaultdict(list)
        for i, (x0, z0, x1, z1) in enumerate(segs):
            for gx in range(int(min(x0, x1)) // 256, int(max(x0, x1)) // 256 + 1):
                for gz in range(int(min(z0, z1)) // 256, int(max(z0, z1)) // 256 + 1):
                    self.grid[(gx, gz)].append(i)

    def nearest(self, x, z, rng):
        """Distance to the nearest road point within rng, or None."""
        r = int(rng) // 256 + 1
        gx0, gz0 = int(x) // 256, int(z) // 256
        seen, best = set(), None
        for gx in range(gx0 - r, gx0 + r + 1):
            for gz in range(gz0 - r, gz0 + r + 1):
                for i in self.grid.get((gx, gz), ()):
                    if i in seen:
                        continue
                    seen.add(i)
                    x0, z0, x1, z1 = self.segs[i]
                    dx, dz = x1 - x0, z1 - z0
                    l2 = dx * dx + dz * dz
                    t = 0.0 if l2 == 0 else max(0.0, min(1.0, ((x - x0) * dx + (z - z0) * dz) / l2))
                    d = math.hypot(x0 + dx * t - x, z0 + dz * t - z)
                    if best is None or d < best:
                        best = d
        return best if best is not None and best <= rng else None


def province(p):
    return (p.get("region") or "?").split("/")[0]


def tourist(p):
    t, v = p["type"], p.get("variant") or ""
    if t in ("word_wall", "dragon_mound", "daedric_shrine", "standing_stone", "fort"):
        return True
    if t == "landmark":
        return v not in MINOR_LANDMARKS
    return t == "dungeon_fixed" and v in TOURIST_VARIANTS


def trail_range(p, with_fix):
    if not with_fix:
        return TRAIL_RANGE if p["type"] in TRAIL_BASE else 0
    if p["type"] not in TRAIL_TYPES:
        return 0
    return FOOTPATH_RANGE if tourist(p) else TRAIL_RANGE


def main(argv):
    t0 = time.time()
    args = list(argv)
    top, area, json_out = 20, "all", None
    with_fix = "--before" not in args
    if "--top" in args:
        top = int(args[args.index("--top") + 1])
    if "--area" in args:
        area = args[args.index("--area") + 1]
    if "--json" in args:
        json_out = args[args.index("--json") + 1]
    export = None
    if "--export" in args:
        export = json.load(open(args[args.index("--export") + 1], encoding="utf-8"))
    box = AREAS.get(area)
    mp = Map()
    places = load_places()
    nodes, land, edges, comp = build_graph(mp, places)
    segs = [(a["x"], a["z"], b["x"], b["z"]) for a, b, _, _ in edges]
    main_segs = segs
    errs, warns = [], []
    unserved = []
    if export:
        # the real planned polylines: main roads (kind 0..2, not gate lanes) and trails / gate lanes
        main_segs, segs = [], []
        parent = {}

        def find(a):
            while parent.setdefault(a, a) != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        for r in export["roads"]:
            pts = r["p"]
            trail = r["k"] == 3 or r.get("gate")
            for i in range(0, len(pts) - 3, 2):
                seg = (pts[i], pts[i + 1], pts[i + 2], pts[i + 3])
                segs.append(seg)
                if not trail:
                    main_segs.append(seg)
            if not trail and r["a"] and r["b"]:
                parent[find(r["a"])] = find(r["b"])
        comp = {p["id"]: hash(find(p["id"])) for p in nodes}
        unserved = [g for g in export.get("gates", []) if not g["served"]]

    by_land = defaultdict(list)
    for p in nodes:
        by_land[land[p["id"]]].append(p)
    for l, plist in by_land.items():
        sizes, cap = defaultdict(int), defaultdict(int)
        for p in plist:
            sizes[comp[p["id"]]] += 1
            if rank(p) == 0:
                cap[comp[p["id"]]] += 1
        if len(sizes) <= 1:
            continue
        main_c = max(sizes, key=lambda c: (cap[c], sizes[c]))
        for p in plist:
            if comp[p["id"]] != main_c and rank(p) < 3 and not p.get("isolated"):
                errs.append(f"settlement {p['id']} ({p['type']}, {province(p)}) is on landmass {l} but not joined "
                            f"to the capital network ({sizes[comp[p['id']]]} place group)")
    deg = defaultdict(int)
    for a, b, _, _ in edges:
        deg[a["id"]] += 1
        deg[b["id"]] += 1
    for p in nodes:
        if deg[p["id"]] == 0 and len(by_land[land[p["id"]]]) > 1 and not p.get("isolated"):
            errs.append(f"settlement {p['id']} has no road")
    ids_nodes = {p["id"] for p in nodes}
    for p in places:
        if p["type"] in SETTLE and not p["id"].startswith("roadside_") and p["id"] not in ids_nodes and not p.get("isolated"):
            warns.append(f"settlement {p['id']} is on no landmass: no roads")
    roads = Segs(segs)
    main_roads = Segs(main_segs)

    settlements = [p for p in places if p["type"] in SETTLE and not p["id"].startswith("roadside_")]
    grid = defaultdict(list)
    for s in settlements:
        grid[(s["x"] // 256, s["z"] // 256)].append(s)

    def inside_town(p):
        gx, gz = p["x"] // 256, p["z"] // 256
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                for s in grid.get((gx + dx, gz + dz), ()):
                    r = max(8, s.get("radius", 32)) + 8
                    if (s["x"] - p["x"]) ** 2 + (s["z"] - p["z"]) ** 2 <= r * r:
                        return True
        return False

    rows = []  # (place, class, distance to the nearest road or None)
    for p in places:
        if p["type"] in SETTLE and not p["id"].startswith("roadside_"):
            continue
        if box and not (box[0] <= p["x"] < box[2] and box[1] <= p["z"] < box[3]):
            continue
        if p["id"].startswith("roadside_"):
            d = roads.nearest(p["x"], p["z"], 400)
            rows.append((p, "isolated" if d is None else "on-road" if d <= ON_ROAD else "trail", d))
            continue
        if inside_town(p):
            rows.append((p, "settled", 0.0))
            continue
        v = p.get("variant") or ""
        if v in ("shipwreck", "wreck", "sea_vault", "reef", "sea_cave", "ferry", "boat", "island", "dock", "pier"):
            rows.append((p, "marine", 0.0))   # reached by boat
            continue
        l = mp.near(p["x"], p["z"], 160)
        if l not in by_land:
            rows.append((p, "marine", 0.0))   # a landmass without any settlement (an island, Atmora, Akavir): no road system
            continue
        if export:
            d = main_roads.nearest(p["x"], p["z"], ON_ROAD)
            if d is not None:
                rows.append((p, "on-road", d))
                continue
            d = roads.nearest(p["x"], p["z"], ON_ROAD)
            if d is not None:
                rows.append((p, "trail", d))
            else:
                rows.append((p, "isolated", roads.nearest(p["x"], p["z"], 4000)))
            continue
        rng = trail_range(p, with_fix)
        d = roads.nearest(p["x"], p["z"], max(rng, ON_ROAD) * SLACK)
        if d is None:
            rows.append((p, "isolated", roads.nearest(p["x"], p["z"], 4000)))
        elif d <= ON_ROAD:
            rows.append((p, "on-road", d))
        else:
            rows.append((p, "trail", d))

    cnt = defaultdict(lambda: defaultdict(int))
    tour_iso = defaultdict(int)
    trail_km = sum(d for p, cls, d in rows if cls == "trail" and d) / 1000
    for p, cls, d in rows:
        for k in (province(p), "ALL"):
            cnt[k][cls] += 1
            if cls == "isolated" and tourist(p):
                tour_iso[k] += 1
    lines = ["== places by class: province  settled  on-road  trail  isolated [tourist-grade isolated]  (marine = by boat, not counted)"]
    for prov in sorted(cnt, key=lambda k: (k == "ALL", k)):
        c = cnt[prov]
        lines.append(f"  {prov:<12} {c['settled']:5d} {c['on-road']:5d} {c['trail']:5d} {c['isolated']:5d} [{tour_iso[prov]}]")
    km = sum(math.hypot(a["x"] - b["x"], a["z"] - b["z"]) for a, b, _, _ in edges) / 1000
    lines.append(f"== road network: {len(nodes)} settlements, {len(edges)} links, {km:.0f} km straight-line")
    ncomp = defaultdict(set)
    for p in nodes:
        ncomp[land[p["id"]]].add(comp[p["id"]])
    extra = sum(len(c) - 1 for c in ncomp.values())
    lines.append(f"   {len(ncomp)} landmasses, {extra} road networks beyond one per landmass")
    for l in sorted(ncomp):
        if len(ncomp[l]) > 1:
            groups = defaultdict(list)
            for p in by_land[l]:
                groups[comp[p["id"]]].append(p["id"])
            parts = sorted(groups.values(), key=len, reverse=True)
            lines.append(f"   landmass {l}: " + "; ".join(f"{len(g)} ({', '.join(g[:3])})" for g in parts[:6]))
    per = defaultdict(list)
    for p, cls, d in rows:
        if cls == "isolated":
            per[province(p)].append((d if d is not None else 99999, p))
    for prov in sorted(per):
        lst = sorted(per[prov], key=lambda e: (not tourist(e[1]), -e[0]))
        lines.append(f"== isolated in {prov} ({len(lst)}); tourist-grade first, then farthest")
        for d, p in lst[:top]:
            dd = f"{d:.0f}" if d != 99999 else ">4000"
            lines.append(f"   {p['id']:<34} {p['type']}/{(p.get('variant') or '-'):<14} @{p['x']},{p['z']} road {dd}"
                         + ("  [tourist]" if tourist(p) else ""))
        if len(lst) > top:
            lines.append(f"   ... {len(lst) - top} more")
    lines += ["ERROR " + e for e in errs] + ["WARN  " + w for w in warns]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    if json_out:
        json.dump({"edges": [[a["id"], b["id"], k, n] for a, b, k, n in edges],
                   "isolated": [p["id"] for p, c, d in rows if c == "isolated"]}, open(json_out, "w"))
    a = cnt["ALL"]
    if export:
        lines.append(f"== export: {len(export['roads'])} roads, {export.get('pending', 0)} pending, {export.get('failed', 0)} failed; "
                     f"{len(unserved)} of {len(export.get('gates', []))} gates without a road end")
        for g in unserved[:top]:
            lines.append(f"   gate {g['p']}#{g['i']} @{g['x']},{g['z']}")
    for e in errs[:12]:
        print("ERR", e)
    if len(errs) > 12:
        print(f"... {len(errs) - 12} more errors in {os.path.relpath(OUT, ROOT)}")
    if export:
        print(f"  export: {len(unserved)} of {len(export.get('gates', []))} gates without a road end, "
              f"{export.get('pending', 0)} links still pending")
    print("reach lint [%s%s]: %d places (%d settled, %d on-road, %d trail, %d isolated of which %d tourist-grade; ~%.0f km of trail), "
          "%d settlement errors, %d extra road networks, %.1fs" % (
              area, ", export" if export else "" if with_fix else ", before", len(rows), a["settled"], a["on-road"], a["trail"], a["isolated"],
              tour_iso["ALL"], trail_km, len(errs), extra, time.time() - t0))
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
