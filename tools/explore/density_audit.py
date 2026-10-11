"""Exploration density audit: report and offline estimate.

Two subcommands:

  py -3 tools/explore/density_audit.py render [--dir mod/run/skycraft-explore] [--tag regions] [--tiles tamriel]
      Reads the CSVs written by the in-game audit (`/skycraft-explore audit all`), draws heatmaps of the walking
      distance to the nearest point of interest over the Tamriel preview (docs/exploration/*.png), writes
      docs/exploration/density.csv and fills the region table of docs/EXPLORATION.md between the AUDIT markers.

  py -3 tools/explore/density_audit.py estimate [--seed 1234] [--out mod/run/skycraft-explore]
      An offline estimate for the Tamriel raster from the map assets alone (no game needed): named places,
      random-spread structure sets (exact placement math, map-only acceptance), the minor-site grid (same hash as
      explore/MinorSites.java, map context from the raster cells) and a Poisson estimate of the rarity-filtered site
      features. Writes CSVs in the same format with tag "offline", so `render --tag offline --tiles offline` works.
      Terrain flatness and biome checks need the game: the in-game audit is the reference.
"""
import argparse
import csv
import glob
import json
import math
import os
import re
import zlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
TAMRIEL = os.path.join(RES, "skycraft", "tamriel")
DATA = os.path.join(RES, "data", "skycraft")
DOCS = os.path.join(ROOT, "docs")
OUTDIR = os.path.join(DOCS, "exploration")
PREVIEW = os.path.join(ROOT, "tools", "worldgen", "preview_tamriel.png")
MIN_X, MIN_Z, CELL, W, H = -15360, -5120, 32, 880, 672
WALK = 4.3
TARGET_NOTABLE, TARGET_MAJOR = 250, 600


# ================================================================================================ render

def colour(d):
    stops = [(0, (30, 150, 60)), (150, (110, 200, 70)), (250, (240, 220, 60)), (400, (240, 140, 40)),
             (600, (210, 50, 40)), (1000, (110, 20, 30))]
    for (d0, c0), (d1, c1) in zip(stops, stops[1:]):
        if d <= d1:
            t = (d - d0) / (d1 - d0)
            return tuple(int(a + (b - a) * t) for a, b in zip(c0, c1))
    return stops[-1][1]


def font(size):
    for f in ("arial.ttf", "DejaVuSans.ttf", "C:/Windows/Fonts/arial.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def heatmap(points, column, title, out):
    base = Image.open(PREVIEW).convert("RGB")
    over = Image.new("RGBA", base.size, (0, 0, 0, 0))
    px = over.load()
    step = None
    for p in points:
        x, z = int(p["x"]), int(p["z"])
        if step is None:
            step = 64
        cx, cz = (x - MIN_X) // 16, (z - MIN_Z) // 16
        c = colour(int(p[column]))
        r = step // 16 // 2 + 1
        for dz in range(-r, r):
            for dx in range(-r, r):
                if 0 <= cx + dx < base.size[0] and 0 <= cz + dz < base.size[1]:
                    px[cx + dx, cz + dz] = c + (170,)
    img = Image.alpha_composite(base.convert("RGBA"), over)
    dr = ImageDraw.Draw(img)
    f = font(28)
    dr.rectangle([10, 10, 760, 140], fill=(0, 0, 0, 170))
    dr.text((22, 18), title, fill=(255, 255, 255), font=f)
    legend = [(0, "< 150"), (200, "150-250"), (330, "250-400"), (500, "400-600"), (800, "> 600 blocks")]
    for i, (d, label) in enumerate(legend):
        x0 = 22 + i * 145
        dr.rectangle([x0, 70, x0 + 30, 100], fill=colour(d))
        dr.text((x0 + 36, 72), label, fill=(255, 255, 255), font=font(20))
    img = img.convert("RGB").resize((img.size[0] // 2, img.size[1] // 2), Image.LANCZOS)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, optimize=True)
    print("wrote", out)


def read_csv(path):
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def render(args):
    regions = read_csv(os.path.join(args.dir, f"audit_{args.tag}_regions.csv"))
    tiles_regions = os.path.join(args.dir, f"audit_{args.tiles}_regions.csv")
    tile_points = os.path.join(args.dir, f"audit_{args.tiles}_points.csv")
    if os.path.exists(tile_points):
        pts = read_csv(tile_points)
        after, before = ("d_core_small", "d_core") if "d_core" in pts[0] else ("d_notable", "d_notable_before")
        heatmap(pts, after, "Walk to the nearest point of interest (with minor sites)",
                os.path.join(OUTDIR, "heatmap_notable.png"))
        heatmap(pts, before, "Walk to the nearest point of interest (before minor sites)",
                os.path.join(OUTDIR, "heatmap_notable_before.png"))
        heatmap(pts, "d_major", "Walk to the nearest major site (settlement, dungeon, landmark)",
                os.path.join(OUTDIR, "heatmap_major.png"))
    # merge: the per-region areas, then any region only the tiles reached
    by = {r["region"]: r for r in regions}
    if os.path.exists(tiles_regions):
        for r in read_csv(tiles_regions):
            by.setdefault(r["region"], r)
    rows = sorted(by.values(), key=lambda r: (r["province"], r["region"]))
    os.makedirs(OUTDIR, exist_ok=True)
    with open(os.path.join(OUTDIR, "density.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    table = ["| Region | Land km² | Settl./km² | Dung./km² | Landm./km² | Minor/km² | Feat./km² | Forage/km² | "
             "Enc./km | Before: p50 / p90, ≤250 | After: p50 / p90 (walk p90), ≤250 | With features ≤250 | Major ≤600 | Verdict |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---|"]
    for r in rows:
        if float(r["land_km2"]) < 0.3:
            continue
        table.append(
            f"| {r['region']} | {float(r['land_km2']):.1f} | {float(r['settlements_km2']):.1f} | {float(r['dungeons_km2']):.1f} | "
            f"{float(r['landmarks_km2']):.1f} | {float(r['small_sites_km2']):.1f} | {float(r['site_features_km2']):.1f} | "
            f"{float(r['forage_patches_km2']):.0f} | {float(r['encounters_per_km']):.2f} | "
            f"{r.get('core_p50', r['before_p50'])} / {r.get('core_p90', r['before_p90'])}, "
            f"{float(r.get('core_pct_250', r['before_pct_250'])):.0f}% | "
            f"{r.get('core_small_p50', r['notable_p50'])} / {r.get('core_small_p90', r['notable_p90'])} "
            f"({float(r.get('core_small_p90', r['notable_p90'])) / WALK:.0f} s), {float(r.get('core_small_pct_250', r['pct_notable_250'])):.0f}% | "
            f"{float(r['pct_notable_250']):.0f}% | {float(r['pct_major_600']):.0f}% | {r['verdict']} |")
    doc = os.path.join(DOCS, "EXPLORATION.md")
    if os.path.exists(doc):
        text = open(doc, encoding="utf-8").read()
        start, end = "<!-- AUDIT:BEGIN -->", "<!-- AUDIT:END -->"
        if start in text and end in text:
            a, b = text.index(start) + len(start), text.index(end)
            text = text[:a] + "\n" + "\n".join(table) + "\n" + text[b:]
            open(doc, "w", encoding="utf-8", newline="\n").write(text)
            print("updated", doc)
    ok = sum(1 for r in rows if r["verdict"] == "ok")
    print(f"{ok}/{len(rows)} regions meet the targets")


# ================================================================================================ offline estimate

MASK = (1 << 64) - 1


def to_signed(v):
    v &= MASK
    return v - (1 << 64) if v >> 63 else v


def mix(z):
    z &= MASK
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
    return z ^ (z >> 31)


def cell_hash(seed, cx, cz):
    inner = (cx * 0x9E3779B97F4A7C15 + cz * 0xC2B2AE3D27D4EB4F + 0x51C37A0EEF1D) & MASK
    return mix((seed & MASK) ^ mix(inner))


def unit(h, k):
    return (mix(h + k * 0x9E3779B97F4A7C15) >> 11) * 2.0 ** -53


class JavaRandom:
    def __init__(self, seed):
        self.s = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits):
        self.s = (self.s * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        v = self.s >> (48 - bits)
        return v - (1 << bits) if v >> (bits - 1) else v

    def next_int(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * (self.next(31) & 0x7FFFFFFF)) >> 31
        while True:
            bits = self.next(31) & 0x7FFFFFFF
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val

    def next_long(self):
        return to_signed((self.next(32) << 32) + self.next(32))


def potential_chunk(seed, cx, cz, spacing, separation, salt, triangular):
    """RandomSpreadStructurePlacement.getPotentialStructureChunk for placement cell (cx, cz)."""
    rnd = JavaRandom(to_signed(cx * 341873128712 + cz * 132897987541 + seed + salt))
    k = spacing - separation
    if triangular:
        a = (rnd.next_int(k) + rnd.next_int(k)) // 2
        b = (rnd.next_int(k) + rnd.next_int(k)) // 2
    else:
        a, b = rnd.next_int(k), rnd.next_int(k)
    return cx * spacing + a, cz * spacing + b


def large_feature_random(seed, x, z):
    r = JavaRandom(seed)
    a, b = r.next_long(), r.next_long()
    return JavaRandom(to_signed((x * a) ^ (z * b) ^ seed))


class Map:
    def __init__(self):
        self.reg = np.array(Image.open(os.path.join(TAMRIEL, "regions.png")))
        self.coast = np.array(Image.open(os.path.join(TAMRIEL, "coast.png"))).astype(np.float64) - 32768
        feat = np.array(Image.open(os.path.join(TAMRIEL, "features.png")).convert("RGBA")).astype(np.float64)
        self.riv_d = (255 - feat[..., 0]) * 0.5
        self.riv_d[self.riv_d >= 127.5] = 400
        self.ridge = feat[..., 1] / 255
        self.lake = (feat[..., 2] - 128) * 0.5
        self.road = feat[..., 3] / 255
        wat = np.array(Image.open(os.path.join(TAMRIEL, "water.png")).convert("RGBA")).astype(np.float64)
        self.riv_hw = wat[..., 2]
        self.has_water = ((wat[..., 0] * 256 + wat[..., 1]) > 0)
        self.elev = np.array(Image.open(os.path.join(TAMRIEL, "elevation.png"))).astype(np.float64) / 32 - 64
        ter = np.array(Image.open(os.path.join(TAMRIEL, "terrain.png")).convert("RGBA")).astype(np.float64)
        self.crag = ter[..., 1] / 255
        self.regions = json.load(open(os.path.join(TAMRIEL, "regions.json"), encoding="utf-8"))

    def cell(self, x, z):
        cx = min(max(int((x - MIN_X) // CELL), 0), W - 1)
        cz = min(max(int((z - MIN_Z) // CELL), 0), H - 1)
        return cz, cx

    def region(self, x, z):
        return self.regions[int(self.reg[self.cell(x, z)])]

    def in_water(self, x, z):
        c = self.cell(x, z)
        if not self.has_water[c]:
            return False
        return self.lake[c] > 0 or self.riv_d[c] < self.riv_hw[c]

    def land(self, x, z):
        c = self.cell(x, z)
        return self.coast[c] > 0 and not self.in_water(x, z) and self.region(x, z)["kind"] != "sea"


def province_of(r):
    if r["kind"] == "sea" or r["province"] == "sea":
        return "sea"
    return r["province"]


def load_places():
    root = os.path.join(TAMRIEL, "places")
    out = []
    for f in json.load(open(os.path.join(root, "index.json"), encoding="utf-8")):
        p = os.path.join(root, f)
        if os.path.exists(p):
            out += json.load(open(p, encoding="utf-8"))
    return out


CATS = ("settlement", "dungeon", "landmark", "small", "feature")


def place_cat(t):
    if t in ("capital", "city", "town", "village", "farm"):
        return "settlement"
    if t in ("dungeon_fixed", "fort"):
        return "dungeon"
    return "landmark"


def structure_sets():
    def tag_values(name, seen=()):
        p = os.path.join(DATA, "tags", "worldgen", "structure", name + ".json")
        out = set()
        if not os.path.exists(p) or name in seen:
            return out
        for v in json.load(open(p))["values"]:
            v = v["id"] if isinstance(v, dict) else v
            if v.startswith("#skycraft:"):
                out |= tag_values(v[len("#skycraft:"):], seen + (name,))
            elif not v.startswith("#"):
                out.add(v)
        return out

    tags = {t: tag_values(t) for t in ("towns", "dungeons", "ignored_landmarks")}
    sets = []
    for path in glob.glob(os.path.join(DATA, "worldgen", "structure_set", "*.json")):
        d = json.load(open(path))
        pl = d["placement"]
        if pl["type"] != "minecraft:random_spread":
            continue
        entries = []
        for e in d["structures"]:
            sid = e["structure"]
            sp = os.path.join(DATA, "worldgen", "structure", sid.split(":")[1] + ".json")
            st = json.load(open(sp)) if os.path.exists(sp) else {}
            cat = "settlement" if sid in tags["towns"] else "dungeon" if sid in tags["dungeons"] else "landmark"
            if sid in tags["ignored_landmarks"]:
                cat = None
            entries.append((sid, e["weight"], cat, st.get("where"), st.get("type", "")))
        sets.append(dict(spacing=pl["spacing"], separation=pl["separation"], salt=pl["salt"],
                         triangular=pl.get("spread_type") == "triangular", entries=entries))
    return sets


def allows(where, prov, region_id):
    """dungeons/world/Cultures.allows (settlements accept every land region)."""
    if prov == "sea":
        return False
    if where is None:
        return True
    if prov == "morrowind" and region_id == "morrowind/solstheim":
        return False
    if not where:
        return prov != "skyrim"
    for w in where:
        if w in ("*", prov):
            return True
        if w.startswith("!") and w[1:] == prov:
            return False
    return False


KINDS = ["abandoned_camp", "hunters_camp", "wayshrine", "treasure_cache", "dead_adventurer", "herb_garden", "rare_reagent",
         "stone_circle", "ancient_battlefield", "mage_ruin", "hermit_hut", "bandit_lookout", "overlook", "mineral_vein",
         "shipwreck_debris", "fishing_dock", "hot_spring", "dwemer_vent", "nordic_cairn", "ayleid_well", "velothi_shrine",
         "hist_sapling", "moon_sugar_field", "giant_beehive", "crystal_outcrop", "yokudan_obelisk", "breton_menhir",
         "akaviri_shrine", "sea_shrine", "realm_monolith"]
RADIUS = dict(zip(KINDS, [7, 7, 5, 4, 4, 6, 6, 7, 10, 7, 7, 6, 5, 6, 10, 9, 6, 6, 6, 5, 6, 7, 8, 6, 7, 5, 6, 5, 5, 6]))
OCCUPANCY = {"skyrim": 0.45, "cyrodiil": 0.6, "high_rock": 0.6, "hammerfell": 0.6, "black_marsh": 0.7, "elsweyr": 0.6,
             "valenwood": 0.6, "summerset": 0.6}
SPECIAL = {"skyrim": {"nordic_cairn": 5}, "atmora": {"nordic_cairn": 7}, "cyrodiil": {"ayleid_well": 6},
           "morrowind": {"velothi_shrine": 6}, "black_marsh": {"hist_sapling": 6}, "elsweyr": {"moon_sugar_field": 6},
           "valenwood": {"giant_beehive": 6, "ayleid_well": 1.5}, "summerset": {"crystal_outcrop": 6},
           "hammerfell": {"yokudan_obelisk": 6}, "yokuda": {"yokudan_obelisk": 6},
           "high_rock": {"breton_menhir": 6, "ayleid_well": 1}, "akavir": {"akaviri_shrine": 7},
           "pyandonea": {"sea_shrine": 7}, "thras": {"sea_shrine": 7}}
ISLES = {"hammerfell/stros_mkai", "summerset/artaeum", "yokuda/sunken_isles", "akavir/tang_mo", "thras/coral_kingdom",
         "pyandonea/maormer_isles"}
NINE = {"skyrim", "cyrodiil", "high_rock", "hammerfell", "summerset", "valenwood", "elsweyr", "black_marsh", "atmora", "realm"}


def minor_site(m, places_xy, seed, cx, cz):
    """explore/MinorSites.compute from raster cells (no warp, no bilinear blend; wrecks and docks are not walked
    to the waterline here, so their positions differ by up to 60 blocks)."""
    h = cell_hash(seed, cx, cz)
    x = (cx * 10 + 2 + int(unit(h, 1) * 6)) * 16 + 4 + int(unit(h, 3) * 8)
    z = (cz * 10 + 2 + int(unit(h, 2) * 6)) * 16 + 4 + int(unit(h, 4) * 8)
    r = m.region(x, z)
    prov = province_of(r)
    if prov == "sea":
        return None
    occ = 0.9 if r["id"] in ISLES else OCCUPANCY.get(prov, 0.5 if r["id"] == "morrowind/solstheim" else 0.6)
    if unit(h, 5) >= occ:
        return None
    c = m.cell(x, z)
    if m.in_water(x, z) or m.coast[c] < 3:
        return None
    elev = m.elev[c]
    ring = [m.elev[m.cell(x + int(math.cos(a) * 80), z + int(math.sin(a) * 80))] for a in [i * math.pi / 3 for i in range(6)]]
    high = elev - sum(ring) / 6 > 10 or m.ridge[c] > 0.45
    mountains = m.crag[c] > 0.3 or m.ridge[c] > 0.3 or elev > 130
    coast = 3 <= m.coast[c] <= 64 and elev < 77
    hw = m.riv_hw[c]
    shore = bool(m.has_water[c]) and ((-40 <= m.lake[c] <= -3) or (hw >= 2 and hw + 3 <= m.riv_d[c] <= hw + 40))
    rid = r["id"]
    cold = elev > 170 or rid in ("skyrim/the_pale", "skyrim/winterhold", "skyrim/eastmarch", "morrowind/solstheim",
                                 "akavir/kamal", "cyrodiil/jerall_mountains") or prov == "atmora"
    w = dict(abandoned_camp=5, hunters_camp=1 if prov in ("pyandonea", "thras") else 4,
             wayshrine=(3 + (6 if m.road[c] > 0.25 else 0)) if prov in NINE else 0, treasure_cache=4, dead_adventurer=5,
             herb_garden=2, rare_reagent=2, stone_circle=1, ancient_battlefield=1.5, mage_ruin=1.5, hermit_hut=2,
             bandit_lookout=1 + (4 if high else 0), overlook=5 if high else 0, mineral_vein=1 + (4 if mountains else 0))
    if coast:
        w["shipwreck_debris"] = 10
    if shore:
        w["fishing_dock"] = 10
    elif coast:
        w["fishing_dock"] = 3
    if cold:
        w["hot_spring"] = 2.5
    if prov in ("skyrim", "morrowind", "hammerfell", "high_rock"):
        w["dwemer_vent"] = 1.5 + (2 if mountains else 0)
    for k, v in SPECIAL.get(prov, {}).items():
        w[k] = w.get(k, 0) + v
    if rid == "morrowind/solstheim":
        w["nordic_cairn"] = 3
    total = sum(w.values())
    roll = unit(h, 6) * total
    kind = None
    for k in KINDS:
        roll -= w.get(k, 0)
        if roll < 0:
            kind = k
            break
    if kind is None:
        return None
    mg = RADIUS[kind] + 40
    for (px, pz, pr) in places_xy:
        if abs(px - x) <= pr + mg and abs(pz - z) <= pr + mg:
            return None
    if kind not in ("fishing_dock", "shipwreck_debris"):
        for ox, oz in ((RADIUS[kind], 0), (-RADIUS[kind], 0), (0, RADIUS[kind]), (0, -RADIUS[kind])):
            if not m.land(x + ox, z + oz):
                return None
    return x, z, kind


def estimate(args):
    m = Map()
    seed = args.seed
    places = load_places()
    places_xy = [(p["x"], p["z"], p.get("radius", 32)) for p in places]
    sets = structure_sets()
    pois = []
    for p in places:
        pois.append((p["x"], p["z"], place_cat(p["type"]), "place:" + p["type"]))
    x0, z0, x1, z1 = MIN_X, MIN_Z, MIN_X + W * CELL, MIN_Z + H * CELL
    for st in sets:
        sp = st["spacing"]
        for cz in range((z0 >> 4) // sp, (z1 >> 4) // sp + 1):
            for cx in range((x0 >> 4) // sp, (x1 >> 4) // sp + 1):
                kx, kz = potential_chunk(seed, cx, cz, sp, st["separation"], st["salt"], st["triangular"])
                bx, bz = kx * 16 + 8, kz * 16 + 8
                if not m.land(bx, bz):
                    continue
                r = m.region(bx, bz)
                prov = province_of(r)
                if any(abs(px - bx) <= pr + 48 and abs(pz - bz) <= pr + 48 for px, pz, pr in places_xy):
                    continue
                entries = list(st["entries"])
                rnd = large_feature_random(seed, kx, kz)
                total = sum(e[1] for e in entries)
                while entries:
                    if len(entries) == 1:
                        e = entries[0]
                    else:
                        roll = rnd.next_int(total)
                        i = 0
                        for e2 in entries:
                            roll -= e2[1]
                            if roll < 0:
                                break
                            i += 1
                        e = entries[min(i, len(entries) - 1)]
                    sid, wgt, cat, where, typ = e
                    ok = allows(where if typ == "skycraft:dungeon" else None, prov, r["id"])
                    if typ == "skycraft:dungeon" and where is None:
                        ok = allows([], prov, r["id"])
                    if ok:
                        if cat:
                            pois.append((bx, bz, cat, sid))
                        break
                    entries.remove(e)
                    total -= wgt
    for cz in range(z0 // 160, z1 // 160 + 1):
        for cx in range(x0 // 160, x1 // 160 + 1):
            s = minor_site(m, places_xy, seed, cx, cz)
            if s:
                pois.append((s[0], s[1], "small", s[2]))
    # Poisson estimate of the site features: wayside shrines (all land, 1/360, half pass their ground check) and
    # bandit camps (wilderness, 1/180, a third pass)
    rng = np.random.default_rng(seed & 0xFFFFFFFF)
    for (rate, frac) in ((1 / 360 * 0.5, 1.0), (1 / 180 * 0.35, 0.6)):
        n = rng.poisson(rate * frac * (W * CELL // 16) * (H * CELL // 16))
        xs = rng.uniform(x0, x1, n)
        zs = rng.uniform(z0, z1, n)
        for x, z in zip(xs, zs):
            if m.land(x, z):
                pois.append((int(x), int(z), "feature", "estimate"))
    write_estimate(m, pois, args.out)


def write_estimate(m, pois, out):
    os.makedirs(out, exist_ok=True)
    arr = np.array([(p[0], p[1]) for p in pois], dtype=np.float64)
    cats = np.array([CATS.index(p[2]) for p in pois])
    from scipy.spatial import cKDTree  # noqa: E402 (optional dependency, only for the estimate)
    trees = {
        "notable": cKDTree(arr),
        "before": cKDTree(arr[cats != CATS.index("small")]),
        "major": cKDTree(arr[np.isin(cats, [0, 1, 2])]),
        "small": cKDTree(arr[cats == CATS.index("small")]),
    }
    stats = {}
    step = 64
    rows = []
    for z in range(MIN_Z + step // 2, MIN_Z + H * CELL, step):
        for x in range(MIN_X + step // 2, MIN_X + W * CELL, step):
            if not m.land(x, z):
                continue
            r = m.region(x, z)
            d = {k: int(t.query((x, z))[0]) for k, t in trees.items()}
            rows.append((x, z, r["id"], d["notable"], d["before"], d["major"], d["small"]))
            st = stats.setdefault(r["id"], dict(province=r["province"], n=0, notable=[], before=[], major=[], road=0,
                                                cat=[0] * 5))
            st["n"] += 1
            st["notable"].append(d["notable"])
            st["before"].append(d["before"])
            st["major"].append(d["major"])
            st["road"] += m.road[m.cell(x, z)] > 0.5
    for x, z, cat, _ in pois:
        if not (MIN_X <= x < MIN_X + W * CELL and MIN_Z <= z < MIN_Z + H * CELL):
            continue
        rid = m.region(x, z)["id"]
        if rid in stats:
            stats[rid]["cat"][CATS.index(cat)] += 1
    with open(os.path.join(out, "audit_offline_points.csv"), "w", encoding="utf-8", newline="") as f:
        f.write("x,z,region,d_notable,d_notable_before,d_major,d_small\n")
        for r in rows:
            f.write(",".join(map(str, r)) + "\n")
    with open(os.path.join(out, "audit_offline_regions.csv"), "w", encoding="utf-8", newline="") as f:
        f.write("region,province,land_km2,settlements_km2,dungeons_km2,landmarks_km2,small_sites_km2,site_features_km2,"
                "forage_patches_km2,encounters_per_km,notable_p50,notable_p90,notable_p99,walk_s_p50,walk_s_p90,"
                "pct_notable_250,pct_major_600,before_p50,before_p90,before_pct_250,verdict\n")
        for rid, st in stats.items():
            km2 = st["n"] * step * step / 1e6
            nb, bf, mj = np.array(st["notable"]), np.array(st["before"]), np.array(st["major"])
            pn, pm = (nb <= TARGET_NOTABLE).mean() * 100, (mj <= TARGET_MAJOR).mean() * 100
            per_km = 1000 / WALK
            enc = per_km / 270 * 0.85 + st["road"] / st["n"] * per_km / 40 * 0.5
            verdict = "ok" if pn >= 90 and pm >= 90 else "major_gaps" if pn >= 90 else "notable_gaps"
            c = [v / km2 for v in st["cat"]]
            f.write(f"{rid},{st['province']},{km2:.2f},{c[0]:.2f},{c[1]:.2f},{c[2]:.2f},{c[3]:.2f},{c[4]:.2f},0,{enc:.2f},"
                    f"{int(np.percentile(nb, 50))},{int(np.percentile(nb, 90))},{int(np.percentile(nb, 99))},"
                    f"{np.percentile(nb, 50) / WALK:.0f},{np.percentile(nb, 90) / WALK:.0f},{pn:.1f},{pm:.1f},"
                    f"{int(np.percentile(bf, 50))},{int(np.percentile(bf, 90))},{(bf <= TARGET_NOTABLE).mean() * 100:.1f},{verdict}\n")
    print("wrote offline estimate to", out, "regions:", len(stats))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("render")
    r.add_argument("--dir", default=os.path.join(ROOT, "mod", "run", "skycraft-explore"))
    r.add_argument("--tag", default="regions")
    r.add_argument("--tiles", default="tamriel")
    e = sub.add_parser("estimate")
    e.add_argument("--seed", type=int, default=1234)
    e.add_argument("--out", default=os.path.join(ROOT, "mod", "run", "skycraft-explore"))
    a = ap.parse_args()
    if a.cmd == "render":
        render(a)
    else:
        estimate(a)


if __name__ == "__main__":
    main()
