"""Dungeon class & room-band audit (offline places + optional in-memory exports).

Usage:
  py -3 -I tools/qa/dungeon_stats.py [--province <P>] [--region <R>] [--json <out>]
  py -3 -I tools/qa/dungeon_stats.py --exports [export.json.gz ...]

Default mode (MS7 / R3c.4):
  - Reads tools/qa/floors.json (Section 4.5 dungeon_classes) and places/*.json.
  - Asserts every dungeon place (type in {"dungeon_fixed", "fort"}) has a valid
    "class" tag in {"marquee", "major", "short", "minor", "unmarked"}.
  - Reports room-count and play-time bands per class alongside actual room bands
    from hand-authored marquee plans and Theme.java.
  - Reports per-region counts and per-km2 densities against Section 4.5 floors.
"""
import argparse
import glob
import json
import os
import struct
import sys
import zlib
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "skycraft")
FLOORS_PATH = os.path.join(HERE, "floors.json")

CLASSES = ("marquee", "major", "short", "minor", "unmarked")
CELL_KM2 = (32.0 / 1000.0) ** 2  # 1 raster px = 32x32 blocks = 1024 m^2


def jload(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def region_areas_km2():
    """Count pixels per region index from tamriel/regions.png."""
    png_path = os.path.join(SKY, "tamriel", "regions.png")
    regs = jload(os.path.join(SKY, "tamriel", "regions.json"), [])
    by_idx = {r["index"]: r["id"] for r in regs if r.get("kind") != "sea"}
    if not os.path.exists(png_path):
        return {r["id"]: 1.0 for r in regs if r.get("kind") != "sea"}
    with open(png_path, "rb") as f:
        raw = f.read()
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(raw):
        ln = struct.unpack(">I", raw[pos:pos + 4])[0]
        ct = raw[pos + 4:pos + 8]
        chunk = raw[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if ct == b"IHDR":
            w, h = struct.unpack(">II", chunk[:8])
        elif ct == b"IDAT":
            idat.extend(chunk)
        elif ct == b"IEND":
            break
    data = zlib.decompress(bytes(idat))
    counts = Counter()
    prev = bytearray(w)
    p = 0
    for _ in range(h):
        flt = data[p]
        cur = bytearray(data[p + 1:p + 1 + w])
        p += 1 + w
        if flt == 1:
            for i in range(1, w):
                cur[i] = (cur[i] + cur[i - 1]) & 255
        elif flt == 2:
            for i in range(w):
                cur[i] = (cur[i] + prev[i]) & 255
        elif flt == 3:
            for i in range(w):
                cur[i] = (cur[i] + (((cur[i - 1] if i else 0) + prev[i]) >> 1)) & 255
        elif flt == 4:
            for i in range(w):
                a = cur[i - 1] if i else 0
                b = prev[i]
                c = prev[i - 1] if i else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 255
        counts.update(cur)
        prev = cur
    return {rid: max(0.01, counts.get(idx, 0) * CELL_KM2) for idx, rid in by_idx.items()}


def load_places():
    idx = jload(os.path.join(SKY, "tamriel", "places", "index.json"), [])
    out = []
    for fn in idx:
        p = os.path.join(SKY, "tamriel", "places", fn)
        for item in jload(p, []):
            if isinstance(item, dict):
                item["_file"] = fn
                out.append(item)
    return out


def match_region(rid, prov, f_prov, f_reg):
    if f_prov and prov != f_prov:
        return False
    if f_reg:
        if rid != f_reg and rid.split("/")[-1] != f_reg:
            return False
    return True


def run_exports(paths):
    import numpy as np
    sys.path.insert(0, HERE)
    import dungeon_export as dx
    import dungeon_reach as dr

    if not paths:
        paths = sorted(glob.glob(os.path.join(ROOT, "mod", "run", "qa-exports", "*.json.gz")))
    if not paths:
        print("dungeon_stats: no exports found")
        return 0
    rows = []
    for p in paths:
        ex = dx.load(p)
        R = dr.analyse(ex)
        x1, y1, z1, x2, y2, z2 = ex.box
        vol = (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1)
        air = int((ex.A != 0).sum())
        rms = dx.rooms(ex)
        rows.append(dict(
            id=ex.id,
            theme=ex.meta.get("theme", "?"),
            box=(x2 - x1 + 1, y2 - y1 + 1, z2 - z1 + 1),
            rooms=len(rms),
            reachable_rooms=sum(1 for x in R["rooms"] if x["reachable"]),
            chests=len(ex.containers),
            reachable_chests=sum(1 for x in R["containers"] if x["reachable"]),
            lecterns=len(ex.lecterns),
            spawners=len(ex.spawners),
            entities=len(ex.entities),
            boss_ok=R["boss_ok"],
            loop_ok=R["loop_ok"],
            standable=R["standable_cells"],
            reached=R["reached_cells"],
            volume=vol,
            non_air=air,
        ))
    print(f"{'id':<24} {'theme':<16} {'box':<13} {'rooms':>7} {'chests':>7} {'lect':>4} {'spwn':>4} {'ent':>4} {'boss':>5} {'loop':>5} {'reachCells':>12}")
    for r in rows:
        bx = f"{r['box'][0]}x{r['box'][1]}x{r['box'][2]}"
        rm = f"{r['reachable_rooms']}/{r['rooms']}"
        ch = f"{r['reachable_chests']}/{r['chests']}"
        rc = f"{r['reached']}/{r['standable']}"
        print(f"{r['id']:<24} {r['theme']:<16} {bx:<13} {rm:>7} {ch:>7} {r['lecterns']:>4} {r['spawners']:>4} {r['entities']:>4} {str(r['boss_ok']):>5} {str(r['loop_ok']):>5} {rc:>12}")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="MS7 dungeon class & room-band audit.")
    ap.add_argument("--province", help="Filter by province id (e.g. skyrim)")
    ap.add_argument("--region", help="Filter by region id (e.g. whiterun or skyrim/whiterun)")
    ap.add_argument("--json", help="Write JSON report to path")
    ap.add_argument("--exports", action="store_true", help="Analyse in-memory .json.gz exports instead of places")
    ap.add_argument("files", nargs="*", help="Optional .json.gz export files")
    args = ap.parse_args(argv)

    if args.exports or (args.files and all(f.endswith(".gz") for f in args.files)):
        return run_exports(args.files)

    floors = jload(FLOORS_PATH, {}).get("dungeon_classes", {})
    regs = [r for r in jload(os.path.join(SKY, "tamriel", "regions.json"), []) if r.get("kind") != "sea"]
    areas = region_areas_km2()
    places = load_places()
    dungeons = [p for p in places if p.get("type") in ("dungeon_fixed", "fort")]

    # 1. Verify every dungeon place has a valid class tag
    missing_or_invalid = []
    for p in dungeons:
        cls = p.get("class")
        if cls not in CLASSES:
            missing_or_invalid.append((p.get("_file", "?"), p.get("id", "?"), cls))

    # 2. Compute marquee plan room counts for room-band reporting
    marquee_rooms = []
    for mf in sorted(glob.glob(os.path.join(SKY, "dungeons", "marquee", "*.json"))):
        if os.path.basename(mf) == "index.json":
            continue
        mj = jload(mf, {})
        if isinstance(mj, dict) and "rooms" in mj:
            marquee_rooms.append(len(mj["rooms"]))

    # Room-count bands per class (DESIGN-PRINCIPLES Section 4.5)
    bands_report = {}
    print("=== MS7 Dungeon Class Room & Play-Time Bands (Section 4.5) ===")
    print(f"{'Class':<10} {'Total':>6} {'Target Rooms':>14} {'Target Mins':>13} {'Floor':>18} {'Loop-Back':>11} {'Configured / Plan Rooms':>24}")
    print("-" * 102)
    actual_band_desc = {
        "marquee": f"15-30 (plans: {min(marquee_rooms) if marquee_rooms else 15}-{max(marquee_rooms) if marquee_rooms else 30} rooms)",
        "major": "8-14 (Theme 8-12 +3 named)",
        "short": "4-6 (lair/mine/crypt 4-6)",
        "minor": "1-3 (camp/den/tower 1-3)",
        "unmarked": "1 (cabin/micro-site 1)",
    }
    total_by_cls = Counter(p.get("class") for p in dungeons)
    for cls in CLASSES:
        spec = floors.get(cls, {})
        rm_lo, rm_hi = spec.get("rooms", [1, 1])
        mn_lo, mn_hi = spec.get("minutes", [1, 5])
        if spec.get("per_region") is not None:
            fl_str = f"{spec['per_region']} / region"
        else:
            fl_str = f"{spec.get('per_km2', 0):.2f} (abs {spec.get('abs_per_km2', 0):.2f})/km2"
        lp_str = f"{int((spec.get('loop_share') or 0) * 100)}%"
        rm_str = f"{rm_lo}-{rm_hi}" if rm_lo != rm_hi else f"{rm_lo}"
        mn_str = f"{mn_lo}-{mn_hi} min"
        print(f"{cls:<10} {total_by_cls[cls]:>6} {rm_str:>14} {mn_str:>13} {fl_str:>18} {lp_str:>11} {actual_band_desc[cls]:>24}")
        bands_report[cls] = {
            "count": total_by_cls[cls],
            "target_rooms": [rm_lo, rm_hi],
            "target_minutes": [mn_lo, mn_hi],
            "per_region": spec.get("per_region"),
            "per_km2": spec.get("per_km2"),
            "abs_per_km2": spec.get("abs_per_km2"),
            "loop_share": spec.get("loop_share"),
        }

    # 3. Per-region class counts vs Section 4.5 floors
    by_reg = defaultdict(Counter)
    for p in dungeons:
        by_reg[p.get("region", "")][p.get("class", "?")] += 1

    sel_regs = [r for r in regs if match_region(r["id"], r["province"], args.province, args.region)]
    print(f"\n=== Per-Region Dungeon Class Counts & Density ({len(sel_regs)} regions) ===")
    hdr = f"{'Region':<28} {'km2':>5} {'Tot':>4} | {'mrq':>4} {'maj':>4} {'maj/k':>6} {'sht':>4} {'sht/k':>6} {'min':>4} {'min/k':>6} {'unm':>4} {'unm/k':>6}"
    print(hdr)
    print("-" * len(hdr))

    reg_rows = {}
    for r in sel_regs:
        rid = r["id"]
        km2 = areas.get(rid, 1.0)
        c = by_reg[rid]
        tot = sum(c.values())
        mrq = c["marquee"]
        maj, sht, mnr, unm = c["major"], c["short"], c["minor"], c["unmarked"]
        print(f"{rid:<28} {km2:>5.2f} {tot:>4} | {mrq:>4} {maj:>4} {maj / km2:>6.2f} {sht:>4} {sht / km2:>6.2f} {mnr:>4} {mnr / km2:>6.2f} {unm:>4} {unm / km2:>6.2f}")
        reg_rows[rid] = {
            "km2": round(km2, 3),
            "total": tot,
            "counts": {cls: c[cls] for cls in CLASSES},
            "per_km2": {cls: round(c[cls] / km2, 3) for cls in CLASSES},
        }

    print(f"\nClass tag check: {len(dungeons) - len(missing_or_invalid)}/{len(dungeons)} dungeon places tagged with a valid class.")
    if missing_or_invalid:
        for fn, pid, bad in missing_or_invalid[:20]:
            print(f"  FAIL: {fn}:{pid} has invalid/missing class={bad!r}")
        return 1

    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"bands": bands_report, "regions": reg_rows}, f, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
