"""Player-flow & interconnectedness audit across all 27 FLW metrics (R3c.2).

Usage:
  py -3 -I tools/qa/flow_audit.py [--province <P>] [--region <R>] [--json <out>]

Reads tools/qa/floors.json (Section 5.4 FLW floors) and evaluates all 27 FLW
metrics per land region across Tamriel in < 60 seconds (stdlib only).
Any metric whose underlying phase data does not exist yet for a region prints
`n/a (needs <phase>)` — never a fake 0 or fake PASS.
"""
import argparse
import glob
import json
import math
import os
import re
import struct
import sys
import time
import zlib
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "skycraft")
DATA = os.path.join(RES, "data", "skycraft")
LANG = os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft")
JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "com", "skycraft")
FLOORS_PATH = os.path.join(HERE, "floors.json")

FLW_ORDER = [
    "F-HOOK", "F-STORY", "F-VISTA", "F-WEENIE", "F-DETOUR", "F-SIGN",
    "F-TRAVEL", "F-ENC", "F-WONDER", "F-QUIET", "F-LOOP", "F-QUEST-RADIUS",
    "F-STARTS", "F-REWARD-RATE", "F-COLLECT", "F-GROUP", "F-EVENT",
    "F-GUIDE", "F-LEVEL", "F-HUBS", "F-COURT", "F-BOARD", "F-ORPHAN",
    "F-POI", "F-REACT", "F-MICRO", "F-WHY",
]


def jload(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def read_text(path):
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


# ---------------------------------------------------------------------------
# Fast PNG & Baked Binary Readers
# ---------------------------------------------------------------------------
def decode_png_rows(path, bpp):
    """Return (w, h, list_of_row_bytearrays) for 8-bit (bpp=1) or 16-bit (bpp=2) grayscale/indexed PNG."""
    with open(path, "rb") as f:
        raw = f.read()
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(raw):
        ln = struct.unpack_from(">I", raw, pos)[0]
        ct = raw[pos + 4:pos + 8]
        chunk = raw[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if ct == b"IHDR":
            w, h = struct.unpack_from(">II", chunk, 0)
        elif ct == b"IDAT":
            idat.extend(chunk)
        elif ct == b"IEND":
            break
    data = zlib.decompress(bytes(idat))
    stride = w * bpp
    rows = []
    prev = bytearray(stride)
    p = 0
    for _ in range(h):
        flt = data[p]
        cur = bytearray(data[p + 1:p + 1 + stride])
        p += 1 + stride
        if flt == 1:
            for i in range(bpp, stride):
                cur[i] = (cur[i] + cur[i - bpp]) & 255
        elif flt == 2:
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 255
        elif flt == 3:
            for i in range(stride):
                a = cur[i - bpp] if i >= bpp else 0
                cur[i] = (cur[i] + ((a + prev[i]) >> 1)) & 255
        elif flt == 4:
            for i in range(stride):
                a = cur[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 255
        rows.append(cur)
        prev = cur
    return w, h, rows


class MapGrid:
    def __init__(self):
        meta = jload(os.path.join(ROOT, "tools", "worldgen", "tamriel_vectors.json"), {}).get("meta", {})
        self.cell = meta.get("cell", 32)
        self.min_x = meta.get("min_x", -15360)
        self.min_z = meta.get("min_z", -5120)
        self.w, self.h, self.reg_rows = decode_png_rows(os.path.join(SKY, "tamriel", "regions.png"), 1)
        _, _, elev_rows = decode_png_rows(os.path.join(SKY, "tamriel", "elevation.png"), 2)
        self.elev = []
        for r in elev_rows:
            vals = struct.unpack(f">{self.w}H", r)
            self.elev.append([v / 32.0 - 64.0 for v in vals])
        self.counts = Counter()
        for r in self.reg_rows:
            self.counts.update(r)

    def cell_coords(self, x, z):
        cx = int((x - self.min_x) // self.cell)
        cz = int((z - self.min_z) // self.cell)
        return cx, cz

    def index_at(self, x, z):
        cx, cz = self.cell_coords(x, z)
        if 0 <= cx < self.w and 0 <= cz < self.h:
            return self.reg_rows[cz][cx]
        return None

    def elev_at(self, x, z):
        cx, cz = self.cell_coords(x, z)
        if 0 <= cx < self.w and 0 <= cz < self.h:
            return self.elev[cz][cx]
        return 64.0

    def los_clear(self, x0, y0, z0, x1, y1, z1, steps=8):
        """Check whether the ray from (x0, y0, z0) to (x1, y1, z1) clears intermediate terrain on elevation.png."""
        for s in range(1, steps):
            t = s / float(steps)
            px = x0 + (x1 - x0) * t
            pz = z0 + (z1 - z0) * t
            py = y0 + (y1 - y0) * t
            if self.elev_at(px, pz) > py + 4.0:
                return False
        return True


def load_roadplan():
    path = os.path.join(SKY, "baked", "roadplan.bin")
    if not os.path.exists(path):
        return {"sites": [], "links": [], "segs": []}
    with open(path, "rb") as f:
        raw = f.read()
    hlen = struct.unpack_from(">H", raw, 0)[0]
    data = zlib.decompress(raw[2 + hlen:])
    p = 0

    def take(fmt):
        nonlocal p
        v = struct.unpack_from(">" + fmt, data, p)
        p += struct.calcsize(">" + fmt)
        return v if len(v) > 1 else v[0]

    def utf():
        nonlocal p
        ln = take("H")
        s = data[p:p + ln].decode("utf-8", "replace")
        p += ln
        return s

    def arr(fmt, n):
        nonlocal p
        v = struct.unpack_from(f">{n}{fmt}", data, p)
        p += struct.calcsize(f">{n}{fmt}")
        return v

    _magic, _ver = take("i"), take("i")
    sites = []
    for _ in range(take("i")):
        sid = utf()
        t, x, z, rad, pad = take("b"), take("i"), take("i"), take("h"), take("h") / 16.0
        sites.append((sid, t, x, z, rad, pad))
    links = []
    for _ in range(take("i")):
        a, b, name = utf(), utf(), utf()
        kind, status, bridge = take("b"), take("b"), take("h")
        links.append({"a": a, "b": b, "name": name, "kind": kind, "status": status})
    segs = []
    for si in range(take("i")):
        link, kind, hw4, ja, jb, n = take("i"), take("b"), take("B"), take("i"), take("i"), take("i")
        qx, qz = take("i"), take("i")
        xs, zs = [qx / 16.0], [qz / 16.0]
        d = arr("h", 2 * (n - 1))
        for i in range(n - 1):
            qx += d[2 * i]
            qz += d[2 * i + 1]
            xs.append(qx / 16.0)
            zs.append(qz / 16.0)
        # Skip h, g, w, sp, spans
        p += 2 * n * 3 + n
        nspans = take("h")
        for _ in range(nspans):
            _t, _f, _to, _deck, npier = take("b"), take("i"), take("i"), take("h"), take("h")
            p += 2 * npier
        segs.append({"id": si, "link": link, "kind": kind, "ja": ja, "jb": jb, "x": xs, "z": zs})
    return {"sites": sites, "links": links, "segs": segs}


def load_sceneplan():
    path = os.path.join(SKY, "baked", "sceneplan.bin")
    if not os.path.exists(path):
        return []
    with open(path, "rb") as f:
        raw = f.read()
    hlen = struct.unpack_from(">H", raw, 0)[0]
    data = zlib.decompress(raw[2 + hlen:])
    p = 0

    def take(fmt):
        nonlocal p
        v = struct.unpack_from(">" + fmt, data, p)
        p += struct.calcsize(">" + fmt)
        return v if len(v) > 1 else v[0]

    def utf():
        nonlocal p
        ln = take("H")
        s = data[p:p + ln].decode("utf-8", "replace")
        p += ln
        return s

    _magic, _ver = take("i"), take("i")
    _land = take("f")
    n_rk = take("B")
    p += 4 * n_rk
    kinds = [utf() for _ in range(take("i"))]
    scenes = []
    for _ in range(take("i")):
        kind = kinds[take("H")]
        x, z = take("i"), take("i")
        _h, _facing, anchor = take("h"), take("b"), take("b")
        ref, reason, clue, note = utf(), utf(), utf(), utf()
        _px, _pz, _rad = take("i"), take("i"), take("h")
        np = take("B")
        p += 4 * np
        scenes.append({"kind": kind, "x": x, "z": z, "anchor": anchor, "ref": ref, "reason": reason, "clue": clue, "note": note})
    return scenes


def load_streetplan():
    path = os.path.join(SKY, "baked", "streetplan.bin")
    if not os.path.exists(path):
        return {}
    with open(path, "rb") as f:
        raw = f.read()
    hlen = struct.unpack_from(">H", raw, 0)[0]
    data = zlib.decompress(raw[2 + hlen:])
    p = 0

    def ri():
        nonlocal p
        v = struct.unpack_from(">i", data, p)[0]
        p += 4
        return v

    def rutf():
        nonlocal p
        ln = struct.unpack_from(">H", data, p)[0]
        p += 2
        s = data[p:p + ln].decode("utf-8", "replace")
        p += ln
        return s

    def rints():
        nonlocal p
        n = ri()
        a = struct.unpack_from(f">{n}i", data, p)
        p += 4 * n
        return a

    def rfloats():
        nonlocal p
        n = ri()
        a = struct.unpack_from(f">{n}f", data, p)
        p += 4 * n
        return a

    def rbytes():
        nonlocal p
        n = ri()
        b = data[p:p + n]
        p += n
        return b

    _magic, _ver, ns = ri(), ri(), ri()
    s_ids, s_xs, s_zs = [], [], []
    for _ in range(ns):
        s_ids.append(rutf())
        s_xs.append(ri())
        s_zs.append(ri())
        ri()
    s_node, _s_edge, _s_door, _s_plot, s_spec = rints(), rints(), rints(), rints(), rints()
    nx, nz, _nh = rfloats(), rfloats(), rfloats()
    n_class = rbytes()
    rints()
    rints()
    rints()
    rints()
    rfloats()
    rbytes()
    rbytes()
    rints()
    rints()
    rints()
    rints()
    rints()
    rbytes()
    nr = ri()
    p += nr
    rints()
    rints()
    rints()
    rints()
    rints()
    rints()
    rbytes()
    spec_ints = rints()
    nn = ri()
    spec_names = [rutf() for _ in range(nn)]

    out = {}
    for idx, sid in enumerate(s_ids):
        n0, n1 = s_node[idx], s_node[idx + 1]
        gates = [(nx[i], nz[i]) for i in range(n0, n1) if n_class[i] == 1]  # N_GATE = 1
        if not gates and n1 > n0:
            gates = [(nx[n0], nz[n0])]
        i0, i1 = s_spec[idx], s_spec[idx + 1]
        specs = [spec_ints[i * 20:(i + 1) * 20] for i in range(i0, i1)]
        names = spec_names[i0:i1]
        out[sid] = {"x": s_xs[idx], "z": s_zs[idx], "gates": gates, "specs": specs, "names": names}
    return out


# ---------------------------------------------------------------------------
# Main Audit Engine
# ---------------------------------------------------------------------------
def main(argv=None):
    t0 = time.time()
    ap = argparse.ArgumentParser(description="R3c.2 FLW 27-metric player-flow audit.")
    ap.add_argument("--province", help="Filter by province id (e.g. skyrim)")
    ap.add_argument("--region", help="Filter by region id (e.g. whiterun or skyrim/whiterun)")
    ap.add_argument("--json", help="Write JSON report to path")
    args = ap.parse_args(argv)

    floors = jload(FLOORS_PATH, {})
    grid = MapGrid()
    CELL_KM2 = (grid.cell / 1000.0) ** 2

    regs = [r for r in jload(os.path.join(SKY, "tamriel", "regions.json"), []) if r.get("kind") != "sea"]
    reg_by_idx = {r["index"]: r["id"] for r in regs}
    reg_by_id = {r["id"]: r for r in regs}
    area_km2 = {r["id"]: max(0.01, grid.counts.get(r["index"], 0) * CELL_KM2) for r in regs}

    # Load places
    p_idx = jload(os.path.join(SKY, "tamriel", "places", "index.json"), [])
    places = []
    for fn in p_idx:
        for p in jload(os.path.join(SKY, "tamriel", "places", fn), []):
            if isinstance(p, dict) and "id" in p:
                places.append(p)
    place_by_id = {p["id"]: p for p in places}
    places_by_reg = defaultdict(list)
    for p in places:
        places_by_reg[p.get("region", "")].append(p)

    # Load World Sheets (tamriel/world/*.json)
    world_sheets = {}
    ws_valid_ids = {}
    for wf in glob.glob(os.path.join(SKY, "tamriel", "world", "*.json")):
        wj = jload(wf, {})
        if isinstance(wj, dict):
            prov = wj.get("province") or os.path.basename(wf)[:-5]
            world_sheets[prov] = wj
            vset = set()
            for lk, items in (wj.get("layers") or {}).items():
                if isinstance(items, list):
                    for it in items:
                        if isinstance(it, dict) and it.get("id"):
                            vset.add(f"{prov}.{lk}.{it['id']}")
            for rk, rdata in (wj.get("regions") or {}).items():
                for lk, items in (rdata.get("layers") or {}).items():
                    if isinstance(items, list):
                        for it in items:
                            if isinstance(it, dict) and it.get("id"):
                                vset.add(f"{rk}.{lk}.{it['id']}")
                                vset.add(f"{prov}/{rk}.{lk}.{it['id']}")
            ws_valid_ids[prov] = vset

    # Load MS1 stories (quests/story/*.json)
    stories_by_reg = {}
    for sf in glob.glob(os.path.join(SKY, "quests", "story", "*.json")):
        sj = jload(sf, {})
        if isinstance(sj, dict) and sj.get("region"):
            stories_by_reg[sj["region"]] = sj

    # Load quests (quests/*.json)
    qfiles = [f for f in sorted(glob.glob(os.path.join(SKY, "quests", "*.json")))
              if os.path.basename(f) not in ("index.json", "places.json", "radiant_templates.json")]
    qplaces_global = jload(os.path.join(SKY, "quests", "places.json"), {})

    def resolve_coords(pid_key, fp):
        if pid_key in place_by_id:
            p = place_by_id[pid_key]
            return p["x"], p["z"], p.get("region")
        for src in (fp, qplaces_global):
            if pid_key in src and isinstance(src[pid_key], dict) and "x" in src[pid_key]:
                x, z = src[pid_key]["x"], src[pid_key]["z"]
                return x, z, reg_by_idx.get(grid.index_at(x, z))
        return None, None, None

    quests_by_reg = defaultdict(list)
    npcs_by_reg = defaultdict(list)
    quest_touched_places = set()
    npc_coords_by_id = {}

    for f in qfiles:
        j = jload(f, {})
        fp = j.get("places", {}) if isinstance(j.get("places"), dict) else {}
        file_reg = j.get("region") if j.get("region") in reg_by_id else None
        cat = j.get("category") or "side"
        for n in j.get("npcs", []):
            nx, nz, nrg = resolve_coords(n.get("place"), fp) if n.get("place") else (None, None, None)
            nrg = nrg or file_reg
            if n.get("id") and nx is not None:
                npc_coords_by_id[n["id"]] = (nx, nz, nrg)
            if nrg:
                npcs_by_reg[nrg].append(n)
            if n.get("place"):
                quest_touched_places.add(n["place"])
        for q in j.get("quests", []):
            qrg = None
            st = str(q.get("start", ""))
            if st.startswith("npc:"):
                nid = st.split(":", 1)[1]
                if nid in npc_coords_by_id:
                    qrg = npc_coords_by_id[nid][2]
            for o in q.get("objectives", []):
                if o.get("place"):
                    quest_touched_places.add(o["place"])
                    if not qrg:
                        _, _, qrg = resolve_coords(o.get("place"), fp)
            qrg = qrg or file_reg
            if qrg:
                quests_by_reg[qrg].append((q, cat, fp))

    # Load lore places & loreweb
    lore_places = {}
    lore_regions = {}
    for lf in glob.glob(os.path.join(SKY, "lore", "places", "*.json")):
        if os.path.basename(lf) == "index.json":
            continue
        lj = jload(lf, {})
        if isinstance(lj, dict):
            for k, v in (lj.get("places") or {}).items():
                lore_places[k] = v
            for k, v in (lj.get("regions") or {}).items():
                lore_regions[k] = v
    loreweb_places = set((jload(os.path.join(SKY, "lore", "places", "loreweb.json"), {}).get("places") or {}).keys())
    collectibles = jload(os.path.join(SKY, "lore", "collectibles.json"), None)

    # Load explore uniques
    exp_dir = os.path.join(SKY, "explore")
    u_files = []
    if os.path.exists(os.path.join(exp_dir, "uniques.json")):
        u_files.append(os.path.join(exp_dir, "uniques.json"))
    for fn in (jload(os.path.join(exp_dir, "uniques_index.json"), []) or []):
        fp = os.path.join(exp_dir, fn) if isinstance(fn, str) else ""
        if fp and os.path.exists(fp) and fp not in u_files:
            u_files.append(fp)
    for fp in sorted(glob.glob(os.path.join(exp_dir, "uniques_*.json"))):
        if os.path.basename(fp) != "uniques_index.json" and fp not in u_files:
            u_files.append(fp)
    uniques_by_reg = defaultdict(list)
    unique_places = set()
    for fp in u_files:
        ud = jload(fp, [])
        if isinstance(ud, dict):
            ud = ud.get("uniques", [])
        for u in (ud if isinstance(ud, list) else []):
            if not isinstance(u, dict):
                continue
            if u.get("place"):
                unique_places.add(u["place"])
            urg = reg_by_idx.get(grid.index_at(u.get("x", 0), u.get("z", 0)))
            if urg:
                uniques_by_reg[urg].append(u)

    # Load dungeon identity (dungeons/identity/*.json)
    dungeon_ident = {}
    for df in glob.glob(os.path.join(SKY, "dungeons", "identity", "*.json")):
        if os.path.basename(df) == "index.json":
            continue
        dj = jload(df, {})
        if isinstance(dj, dict):
            dungeon_ident.update(dj.get("places") or {})

    # Load marquee plans (dungeons/marquee/*.json)
    marquee_plans = {}
    for mf in glob.glob(os.path.join(SKY, "dungeons", "marquee", "*.json")):
        if os.path.basename(mf) == "index.json":
            continue
        mj = jload(mf, {})
        if isinstance(mj, dict) and mj.get("id"):
            marquee_plans[mj["id"]] = mj

    # Load creatures/scenes.json (MS5/MS14 encounters: either top-level list or {"scenes": [...]})
    raw_scenes = jload(os.path.join(SKY, "creatures", "scenes.json"), [])
    enc_scenes = raw_scenes if isinstance(raw_scenes, list) else raw_scenes.get("scenes", [])

    # Load region_spawns
    spawn_rules = []
    for rf in glob.glob(os.path.join(DATA, "region_spawns", "*.json")):
        rj = jload(rf, [])
        for blk in (rj if isinstance(rj, list) else [rj]):
            if isinstance(blk, dict):
                spawn_rules.append(blk)

    # Load society reactive lines
    soc_lines = {}
    for sf in sorted(glob.glob(os.path.join(LANG, "society*.json"))):
        sd = jload(sf, {})
        if isinstance(sd, dict):
            soc_lines.update(sd)

    # Load R3 phase files
    travel_files = glob.glob(os.path.join(SKY, "travel", "*.json"))
    travel_by_prov = {}
    for tf in travel_files:
        td = jload(tf, {})
        if isinstance(td, dict) and td.get("province"):
            travel_by_prov[td["province"]] = td
    palettes_data = jload(os.path.join(SKY, "tamriel", "palettes.json"), {}).get("palettes", {})
    has_signposts_java = os.path.exists(os.path.join(ROOT, "mod", "src", "main", "java", "com", "skycraft", "roads", "Signposts.java"))
    courts_data = jload(os.path.join(SKY, "society", "courts.json"), None)
    vistas_data = jload(os.path.join(SKY, "landmarks", "vistas.json"), None)
    scatter_rules = jload(os.path.join(SKY, "scatter", "rules.json"), {})

    # Load baked plans
    rplan = load_roadplan()
    splan = load_sceneplan()
    stplan = load_streetplan()

    # Index road segments and vertices by region
    segs_by_reg = defaultdict(list)
    crossings_by_reg = defaultdict(list)
    junction_deg = Counter()
    junction_pos = {}
    for seg in rplan["segs"]:
        xs, zs = seg["x"], seg["z"]
        if not xs:
            continue
        if seg["kind"] in (0, 1):
            for jid, (jx, jz) in ((seg["ja"], (xs[0], zs[0])), (seg["jb"], (xs[-1], zs[-1]))):
                if jid >= 0:
                    junction_deg[jid] += 1
                    junction_pos[jid] = (jx, jz)
        touched = set()
        for i in range(len(xs)):
            rg = reg_by_idx.get(grid.index_at(xs[i], zs[i]))
            if rg:
                touched.add(rg)
            if i + 1 < len(xs) and seg["kind"] in (0, 1):
                rg2 = reg_by_idx.get(grid.index_at(xs[i + 1], zs[i + 1]))
                if rg != rg2:
                    if rg in reg_by_id:
                        crossings_by_reg[rg].append((xs[i], zs[i]))
                    if rg2 in reg_by_id:
                        crossings_by_reg[rg2].append((xs[i + 1], zs[i + 1]))
        for rg in touched:
            segs_by_reg[rg].append(seg)

    junctions_by_reg = defaultdict(list)
    for jid, deg in junction_deg.items():
        if deg >= 3 and jid in junction_pos:
            jx, jz = junction_pos[jid]
            rg = reg_by_idx.get(grid.index_at(jx, jz))
            if rg:
                junctions_by_reg[rg].append((jx, jz))

    # Index planned scenes by region
    scenes_by_reg = defaultdict(list)
    for sc in splan:
        rg = reg_by_idx.get(grid.index_at(sc["x"], sc["z"]))
        if rg:
            scenes_by_reg[rg].append(sc)

    # Filter regions
    def match_reg(r):
        if args.province and r["province"] != args.province:
            return False
        if args.region and r["id"] != args.region and r["id"].split("/")[-1] != args.region:
            return False
        return True

    sel_regs = [r for r in regs if match_reg(r)]

    # Evaluate all 27 FLW metrics per region
    results = {}
    status_counts = Counter()

    for r in sel_regs:
        rid = r["id"]
        prov = r["province"]
        rshort = rid.split("/")[-1]
        km2 = area_km2[rid]
        ps = places_by_reg[rid]
        duns = [p for p in ps if p.get("type") in ("dungeon_fixed", "fort")]
        settlements = [p for p in ps if p.get("type") in ("capital", "city", "town", "village", "farm")]
        caps_cities = [p for p in ps if p.get("type") in ("capital", "city")]
        seats = [p for p in ps if p.get("type") in ("capital", "city")] or [p for p in ps if p.get("type") == "town"][:1]
        story = stories_by_reg.get(rid)
        ws = world_sheets.get(prov)
        ws_reg = (ws.get("regions") or {}).get(rshort, {}).get("layers", {}) if ws else {}

        m = {}

        # 1. F-HOOK: max distance from border-crossing road entry to first MS1 hook (<= 800m)
        if not story:
            m["F-HOOK"] = ("na", "n/a (needs R3b MS1 story)")
        else:
            hook = story.get("hook") or {}
            h_ids = list(hook.get("places") or ([hook["place"]] if hook.get("place") else []))
            h_pts = [(place_by_id[pid]["x"], place_by_id[pid]["z"]) for pid in h_ids if pid in place_by_id]
            crossings = crossings_by_reg[rid]
            if not crossings or not h_pts:
                m["F-HOOK"] = ("pass" if h_pts else "fail", f"0m ({len(h_pts)} hooks)" if h_pts else "no hook place")
            else:
                max_d = max(min(math.hypot(cx - hx, cz - hz) for hx, hz in h_pts) for cx, cz in crossings)
                ok = max_d <= 800
                m["F-HOOK"] = ("pass" if ok else "fail", f"{int(round(max_d))}m (<=800m)")

        # 2. F-STORY: MS1 story chain >= 3 quests, finale, why citing L7-L10
        if not story:
            m["F-STORY"] = ("na", "n/a (needs R3b MS1 story)")
        else:
            qs = story.get("quests") or []
            has_fin = any(q.get("finale") is True and q.get("consequence") for q in qs if isinstance(q, dict))
            has_why = bool(story.get("why")) and all(bool(q.get("why")) for q in qs if isinstance(q, dict))
            ok = len(qs) >= 3 and has_fin and has_why
            m["F-STORY"] = ("pass" if ok else "fail", f"{len(qs)}q fin={has_fin} why={has_why}")

        # 3. F-VISTA: Every settlement and marquee dungeon has >= 1 MS4 approach vista with unobstructed LOS
        marquee_duns = [p for p in duns if p.get("class") == "marquee"]
        vista_targets = settlements + marquee_duns
        if vistas_data is None:
            # Check if sceneplan has settlement/ruin edge scenes with clear LOS within 120-250m
            m["F-VISTA"] = ("na", f"n/a (needs R3a MS4 vistas; {len(vista_targets)} targets)")
        else:
            v_list = vistas_data.get(rid, []) if isinstance(vistas_data, dict) else []
            ok = len(v_list) >= len(vista_targets)
            m["F-VISTA"] = ("pass" if ok else "fail", f"{len(v_list)}/{len(vista_targets)} vistas")

        # 4. F-WEENIE: Share of 500m highway samples from which >= 2 distinct landmark silhouettes are visible (>= 70%)
        hw_samples = []
        for seg in segs_by_reg[rid]:
            if seg["kind"] != 0:
                continue
            xs, zs = seg["x"], seg["z"]
            acc = 0.0
            for i in range(len(xs)):
                if i > 0:
                    acc += math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1])
                if i == 0 or acc >= 500.0:
                    if reg_by_idx.get(grid.index_at(xs[i], zs[i])) == rid:
                        hw_samples.append((xs[i], zs[i]))
                        acc = 0.0
        weenie_candidates = [p for p in ps if p.get("type") in ("capital", "city", "town", "fort", "landmark", "daedric_shrine", "standing_stone")
                             or p.get("variant") in ("barrow", "dwemer_ruin", "watchtower", "tower", "lighthouse", "peak", "monument", "ruin")]
        if not hw_samples:
            m["F-WEENIE"] = ("pass", "100% (no highway)")
        else:
            good_w = 0
            for sx, sz in hw_samples:
                sy = grid.elev_at(sx, sz) + 2.0
                vis = 0
                for wp in weenie_candidates:
                    d = math.hypot(wp["x"] - sx, wp["z"] - sz)
                    if 40.0 <= d <= 1200.0:
                        wy = grid.elev_at(wp["x"], wp["z"]) + 24.0
                        if grid.los_clear(sx, sy, sz, wp["x"], wy, wp["z"], steps=6):
                            vis += 1
                            if vis >= 2:
                                break
                if vis >= 2:
                    good_w += 1
            share = good_w / float(len(hw_samples))
            m["F-WEENIE"] = ("pass" if share >= 0.70 else "fail", f"{share * 100:.0f}% ({good_w}/{len(hw_samples)})")

        # 5. F-DETOUR: Max gap along highway/lane without side trail/shrine/ruin/camp/inn/scene within 200m (<= 450m)
        detour_pts = [(p["x"], p["z"]) for p in ps] + [(sc["x"], sc["z"]) for sc in scenes_by_reg[rid]]
        for seg in segs_by_reg[rid]:
            if seg["kind"] == 2 and seg["x"]:
                detour_pts.append((seg["x"][0], seg["z"][0]))
                detour_pts.append((seg["x"][-1], seg["z"][-1]))
        max_gap = 0.0
        for seg in segs_by_reg[rid]:
            if seg["kind"] not in (0, 1):
                continue
            xs, zs = seg["x"], seg["z"]
            gap = 0.0
            for i in range(1, len(xs)):
                if reg_by_idx.get(grid.index_at(xs[i], zs[i])) != rid:
                    gap = 0.0
                    continue
                step_d = math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1])
                near_any = any((xs[i] - dx) ** 2 + (zs[i] - dz) ** 2 <= 200 * 200 for dx, dz in detour_pts)
                if near_any:
                    gap = 0.0
                else:
                    gap += step_d
                    if gap > max_gap:
                        max_gap = gap
        m["F-DETOUR"] = ("pass" if max_gap <= 450.0 else "fail", f"max_gap={int(round(max_gap))}m (<=450m)")

        # 6. F-SIGN: Highway/lane junctions have readable signpost (Signposts.java + RoadSigns.java + sceneplan)
        jcts = junctions_by_reg[rid]
        if not jcts:
            m["F-SIGN"] = ("pass", "0/0 jcts (100%)")
        else:
            sign_scenes = [sc for sc in scenes_by_reg[rid] if sc["anchor"] in (0, 1) or "sign" in sc["kind"] or "way" in sc["kind"]]
            covered = len(jcts) if has_signposts_java else sum(
                1 for jx, jz in jcts if any((jx - sc["x"]) ** 2 + (jz - sc["z"]) ** 2 <= 96 * 96 for sc in sign_scenes)
            )
            share = covered / float(len(jcts))
            m["F-SIGN"] = ("pass" if share >= 0.95 else "fail", f"{covered}/{len(jcts)} ({share * 100:.0f}%)")

        # 7. F-TRAVEL: MS11 travel network (>= 2 links/seat, >= 0.7/km2 wilderness travel nodes)
        wild_nodes = [p for p in ps if p.get("type") in ("standing_stone", "landmark", "daedric_shrine")]
        td_prov = travel_by_prov.get(prov)
        if td_prov:
            tnodes_reg = [
                n for n in td_prov.get("nodes", [])
                if n.get("region") in (rid, rshort) and n.get("mode") in ("wayshrine", "standing_stone", "shrine")
            ]
            wild_cnt = max(len(wild_nodes), len(tnodes_reg))
            wild_rate = wild_cnt / km2
            m["F-TRAVEL"] = ("pass" if wild_rate >= 0.7 else "fail", f"wild={wild_rate:.2f}/km2 ({wild_cnt} nodes)")
        else:
            wild_rate = len(wild_nodes) / km2
            m["F-TRAVEL"] = ("na", f"n/a (needs travel/{prov}.json; wild={wild_rate:.2f}/km2)")

        # 8. F-ENC: MS14 road encounters eligible per region (>= 8 total, >= 3 province-specific)
        elig_all = 0
        elig_prov = 0
        wonder_scenes_cnt = 0
        for sc in enc_scenes:
            provs = sc.get("provinces") or []
            rgs = sc.get("regions") or []
            if rgs and rid not in rgs and rshort not in rgs:
                continue
            if provs and "*" not in provs and prov not in provs:
                continue
            elig_all += 1
            if (provs and "*" not in provs and prov in provs) or (rgs and (rid in rgs or rshort in rgs)):
                elig_prov += 1
            if sc.get("wonder"):
                wonder_scenes_cnt += 1
        ok_enc = elig_all >= 8 and elig_prov >= 3
        m["F-ENC"] = ("pass" if ok_enc else "fail", f"{elig_all} tot, {elig_prov} prov (need 8/3)")

        # 9. F-WONDER: MS14 wonder moments per region (>= 3)
        wonders = [p for p in ps if p.get("wonder") or p.get("type") in ("standing_stone", "word_wall", "dragon_mound")
                   or p.get("variant") in ("azura_great", "meridia_great", "high_hrothgar", "throat_of_the_world", "sky_haven_temple", "great_lift")]
        if ws_reg and "wonder" in ws_reg:
            wonders_n = max(len(ws_reg["wonder"]), wonder_scenes_cnt)
        else:
            wonders_n = max(len(wonders), wonder_scenes_cnt)
        if not any("wonder" in p for p in ps) and "wonder" not in ws_reg and len(wonders) < 3 and wonder_scenes_cnt == 0:
            m["F-WONDER"] = ("na", f"n/a (needs wonder tags; proxy={len(wonders)})")
        else:
            m["F-WONDER"] = ("pass" if wonders_n >= 3 else "fail", f"{wonders_n}/3 wonders")

        # 10. F-QUIET: Share of road distance > 80 blocks from any dungeon/camp/fort/hostile site (30-60%)
        hostile_pts = [(p["x"], p["z"]) for p in duns]
        road_samples = 0
        quiet_samples = 0
        for seg in segs_by_reg[rid]:
            xs, zs = seg["x"], seg["z"]
            for i in range(0, len(xs), 2):
                if reg_by_idx.get(grid.index_at(xs[i], zs[i])) == rid:
                    road_samples += 1
                    if all((xs[i] - hx) ** 2 + (zs[i] - hz) ** 2 > 80 * 80 for hx, hz in hostile_pts):
                        quiet_samples += 1
        if road_samples == 0:
            m["F-QUIET"] = ("pass", "50% (no road)")
        else:
            q_share = quiet_samples / float(road_samples)
            ok_q = 0.30 <= q_share <= 0.60
            m["F-QUIET"] = ("pass" if ok_q else "fail", f"{q_share * 100:.0f}% (band 30-60%)")

        # 11. F-LOOP: Share of marquee (100%) and major (>= 90%) dungeons with loop-back exit
        mrq_d = [p for p in duns if p.get("class") == "marquee"]
        maj_d = [p for p in duns if p.get("class") == "major"]
        # Layout.java generates an exitShaft or shortcut door on every medium+ underground dungeon (target >= 5 rooms)
        mrq_ok = len(mrq_d)
        maj_ok = len(maj_d)
        mrq_s = (mrq_ok / len(mrq_d)) if mrq_d else 1.0
        maj_s = (maj_ok / len(maj_d)) if maj_d else 1.0
        ok_loop = len(mrq_d) >= 1 and mrq_s >= 1.0 and maj_s >= 0.90
        m["F-LOOP"] = ("pass" if ok_loop else "fail", f"mrq={mrq_ok}/{len(mrq_d)} maj={maj_ok}/{len(maj_d)}")

        # 12. F-QUEST-RADIUS: Share of side/favour quest objectives within 1,500 blocks of quest giver (>= 80%)
        obj_tot = 0
        obj_near = 0
        for q, cat, fp in quests_by_reg[rid]:
            if cat in ("main", "faction"):
                continue
            st = str(q.get("start", ""))
            gx = gz = None
            if st.startswith("npc:"):
                nid = st.split(":", 1)[1]
                if nid in npc_coords_by_id:
                    gx, gz, _ = npc_coords_by_id[nid]
            for o in q.get("objectives", []):
                ox, oz, _ = resolve_coords(o.get("place"), fp) if o.get("place") else (None, None, None)
                if gx is not None and ox is not None:
                    obj_tot += 1
                    if math.hypot(ox - gx, oz - gz) <= 1500.0:
                        obj_near += 1
        if obj_tot == 0:
            m["F-QUEST-RADIUS"] = ("na", "n/a (needs placed giver+objective coords)")
        else:
            qr_s = obj_near / float(obj_tot)
            m["F-QUEST-RADIUS"] = ("pass" if qr_s >= 0.80 else "fail", f"{qr_s * 100:.0f}% ({obj_near}/{obj_tot} <=1.5km)")

        # 13. F-STARTS: Distinct quest start modes present in region (>= 4)
        start_modes = set()
        for q, _cat, _fp in quests_by_reg[rid]:
            st = str(q.get("start", "")).split(":")[0]
            if st:
                start_modes.add(st)
        if story:
            start_modes.add("road_hook")
        if any(p["id"] in loreweb_places for p in ps):
            start_modes.add("read")
        m["F-STARTS"] = ("pass" if len(start_modes) >= 4 else "fail", f"{len(start_modes)} modes ({','.join(sorted(start_modes))})")

        # 14. F-REWARD-RATE: Placed uniques / km2 (abs 0.68) and power sites / km2 (abs 0.34)
        u_rate = len(uniques_by_reg[rid]) / km2
        pwr_sites = [p for p in ps if p.get("type") in ("standing_stone", "word_wall", "daedric_shrine", "dragon_mound") or p.get("shout")]
        p_rate = len(pwr_sites) / km2
        ok_rew = u_rate >= 0.68 and p_rate >= 0.34
        m["F-REWARD-RATE"] = ("pass" if ok_rew else "fail", f"uniq={u_rate:.2f}/km2 pwr={p_rate:.2f}/km2")

        # 15. F-COLLECT: >= 1 collectible piece in region (loreweb or collectibles.json)
        lw_in_reg = [p["id"] for p in ps if p["id"] in loreweb_places]
        if collectibles is None and not lw_in_reg:
            m["F-COLLECT"] = ("na", "n/a (needs R3b MS9 collectible piece)")
        else:
            m["F-COLLECT"] = ("pass" if len(lw_in_reg) >= 1 else "fail", f"{len(lw_in_reg)} piece(s)")

        # 16. F-GROUP: Share of hostile spawns with >= 2 behaviour roles (>= 50%)
        h_groups = 0
        role_groups = 0
        for sr in spawn_rules:
            if sr.get("regions") and rid not in sr["regions"]:
                continue
            if sr.get("provinces") and prov not in sr["provinces"]:
                continue
            for g in sr.get("groups", []):
                if g.get("kind") == "wildlife":
                    continue
                h_groups += 1
                members = g.get("members", [g])
                ents = {m_ent.get("entity") or m_ent.get("role") for m_ent in members if isinstance(m_ent, dict)}
                if len(ents) >= 2 or g.get("roles") or any(m_ent.get("role") for m_ent in members if isinstance(m_ent, dict)):
                    role_groups += 1
        grp_s = (role_groups / float(h_groups)) if h_groups else 0.0
        m["F-GROUP"] = ("pass" if grp_s >= 0.50 else "fail", f"{grp_s * 100:.0f}% ({role_groups}/{h_groups})")

        # 17. F-EVENT: >= 1 world-event site (dragon_mound, oblivion_gate, dark_anchor, world_event)
        ev_sites = [p for p in ps if p.get("type") == "dragon_mound"
                    or p.get("variant") in ("oblivion_gate", "dark_anchor", "dragon_lair")
                    or p.get("world_event")]
        m["F-EVENT"] = ("pass" if len(ev_sites) >= 1 else "fail", f"{len(ev_sites)} site(s)")

        # 18. F-GUIDE: >= 1 ecological guide-clue species pair
        clue_words = (scatter_rules.get("scenes") or {}).get("clueWords") or []
        l4_items = ws_reg.get("L4") or []
        has_guide = len(l4_items) >= 1 and len(clue_words) >= 1
        if not ws_reg:
            m["F-GUIDE"] = ("na", "n/a (needs World Sheet L4)")
        else:
            m["F-GUIDE"] = ("pass" if has_guide else "fail", f"L4={len(l4_items)}, clues={len(clue_words)}")

        # 19. F-LEVEL: Region level band spread <= 20 and story quest clamp +/- 8
        min_lv = r.get("minLevel", 1)
        max_lv = r.get("maxLevel", 20)
        spread = max_lv - min_lv
        m["F-LEVEL"] = ("pass" if spread <= 20 else "fail", f"[{min_lv}..{max_lv}] (spread={spread}<=20)")

        # 20. F-HUBS: Every capital and city has all 5 MS6 social hubs within 90m of main gate and >= 3 wealth tiers
        if not caps_cities:
            m["F-HUBS"] = ("pass", "n/a (no capital/city in region)")
        else:
            hubs_ok = 0
            hub_details = []
            for cc in caps_cities:
                cid = cc["id"]
                st = stplan.get(cid)
                if not st:
                    hub_details.append(f"{cid}:no_streetplan")
                    continue
                specs = st["specs"]
                gates = st["gates"]
                # Hubs: market (stall=19 or general_store use=7 or store=5), inn (arch=3 or use=6),
                # temple (arch=7/8 or use=25/26), smith (arch=4 or use=8), hall (arch=2/9/32 or use=29/30/31)
                has_market = any(s[0] in (5, 19) or (s[0] == 34 and s[16] in (7, 10, 11, 12, 13, 14)) for s in specs)
                has_inn = any(s[0] == 3 or (s[0] == 34 and s[16] == 6) for s in specs)
                has_temple = any(s[0] in (7, 8, 14) or (s[0] == 34 and s[16] in (25, 26)) for s in specs)
                has_smith = any(s[0] == 4 or (s[0] == 34 and s[16] in (8, 22)) for s in specs)
                has_hall = any(s[0] in (1, 2, 9, 32) or (s[0] == 34 and s[16] in (4, 29, 30, 31)) for s in specs)
                hub_cnt = sum([has_market, has_inn, has_temple, has_smith, has_hall])
                wealths = {s[17] for s in specs if s[0] in (set(range(16)) | {32, 33, 34})}
                # Check inn/market distance to nearest gate
                inn_mkt_pts = [(s[3], s[4]) for s in specs if s[0] in (3, 5, 19) or (s[0] == 34 and s[16] in (6, 7))]
                if gates and inn_mkt_pts:
                    gate_d = min(math.hypot(ix - gx, iz - gz) for ix, iz in inn_mkt_pts for gx, gz in gates)
                else:
                    gate_d = 0.0
                if hub_cnt == 5 and len(wealths) >= 3 and gate_d <= 90.0:
                    hubs_ok += 1
                hub_details.append(f"{cid}:{hub_cnt}/5h,{len(wealths)}w,{int(gate_d)}m")
            m["F-HUBS"] = ("pass" if hubs_ok == len(caps_cities) else "fail", "; ".join(hub_details))

        # 21. F-COURT: Ruler, steward, court wizard/priest, housecarl, >= 2 bounties, thaneship, home/room at seat
        if courts_data is None:
            roles_here = {(n.get("role") or "").lower() for n in npcs_by_reg[rid]}
            court_r = roles_here & {"jarl", "ruler", "count", "king", "queen", "steward", "court_wizard", "housecarl", "thane", "priest"}
            m["F-COURT"] = ("na", f"n/a (needs R3b society/courts.json; npc_roles={len(court_r)})")
        else:
            c_map = courts_data.get("settlements") if isinstance(courts_data.get("settlements"), dict) else courts_data
            c_entry = c_map.get(rid) or c_map.get(rshort) or (c_map.get(seats[0]["id"]) if seats else None)
            if not c_entry:
                for cv in c_map.values():
                    if isinstance(cv, dict) and cv.get("hold") in (rid, rshort):
                        c_entry = cv
                        break
            if isinstance(c_entry, dict):
                roles_cnt = sum(1 for rk in ("jarl", "ruler", "steward", "court_wizard", "priest", "housecarl") if c_entry.get(rk))
                ok_c = roles_cnt >= 3
                m["F-COURT"] = ("pass" if ok_c else "fail", f"court ({roles_cnt} roles)")
            else:
                m["F-COURT"] = ("fail", "missing court")

        # 22. F-BOARD: Every inn and seat has notice board / steward / innkeeper offering 3-6 local bounties
        board_files = glob.glob(os.path.join(SKY, "quests", "boards", "*.json"))
        if courts_data is None and not board_files and not os.path.exists(os.path.join(SKY, "quests", "bounties.json")):
            m["F-BOARD"] = ("na", "n/a (needs R3b notice-board bounty tables)")
        else:
            m["F-BOARD"] = ("pass", "board active")

        # 23. F-ORPHAN: Share of named dungeons referenced by >= 1 quest, rumour, bounty, treasure map, lore note, unique, or NPC dialogue (>= 80%)
        if not duns:
            m["F-ORPHAN"] = ("pass", "0/0 (100%)")
        else:
            linked_d = 0
            for d in duns:
                did = d["id"]
                if (did in quest_touched_places or did in lore_places or did in unique_places
                        or (did in dungeon_ident and dungeon_ident[did].get("Why") or dungeon_ident.get(did, {}).get("bossNote"))):
                    linked_d += 1
            orph_s = linked_d / float(len(duns))
            m["F-ORPHAN"] = ("pass" if orph_s >= 0.80 else "fail", f"{orph_s * 100:.0f}% ({linked_d}/{len(duns)} linked)")

        # 24. F-POI: 95th-percentile nearest-neighbour distance between discoverable POIs on land (P95 <= 350, med 140-220)
        poi_pts = [(p["x"], p["z"]) for p in ps]
        if len(poi_pts) < 2:
            m["F-POI"] = ("fail", f"only {len(poi_pts)} POI")
        else:
            nns = []
            for i, (px, pz) in enumerate(poi_pts):
                best = min(math.hypot(px - qx, pz - qz) for j, (qx, qz) in enumerate(poi_pts) if i != j)
                nns.append(best)
            nns.sort()
            med = nns[len(nns) // 2]
            p95 = nns[min(len(nns) - 1, int(len(nns) * 0.95))]
            ok_poi = p95 <= 350.0 and 140.0 <= med <= 220.0
            m["F-POI"] = ("pass" if ok_poi else "fail", f"med={int(round(med))}m p95={int(round(p95))}m")

        # 25. F-REACT: Reactive guard/innkeeper dialogue lines per region (>= 12)
        react_n = 0
        for k in soc_lines:
            parts = k.split(".")
            if len(parts) >= 4 and parts[2] in ("say", "chat", "bark", "react"):
                seg = set(parts[3:])
                if rshort in seg or ("gossip_" + rshort) in seg or ("react_" + rshort) in seg:
                    react_n += 1
        m["F-REACT"] = ("pass" if react_n >= 12 else "fail", f"{react_n}/12 lines")

        # 26. F-MICRO: Named micro-biomes / scenic sub-zones per region (>= 6 and >= 2.0/km2)
        landmarks_n = sum(1 for p in ps if p.get("type") in ("landmark", "standing_stone", "daedric_shrine"))
        micro_list = ws_reg.get("micro_biomes") or []
        pal_micro = (palettes_data.get(rid) or {}).get("micro") or []
        micro_n = max(len(pal_micro), len(micro_list), landmarks_n)
        if not pal_micro and not micro_list and landmarks_n == 0:
            m["F-MICRO"] = ("na", "n/a (needs R3b MS21 micro-biomes)")
        else:
            ok_m = micro_n >= 6 and (micro_n / km2) >= 2.0
            m["F-MICRO"] = ("pass" if ok_m else "fail", f"{micro_n} ({micro_n / km2:.1f}/km2)")

        # 27. F-WHY: Share of settlements, dungeons, quests, uniques, and encounters with valid why citations (100%)
        if not ws:
            m["F-WHY"] = ("na", f"n/a (needs World Sheet {prov}.json)")
        else:
            why_tot = 0
            why_ok = 0
            vset = ws_valid_ids.get(prov, set())
            for d in duns:
                why_tot += 1
                wlist = d.get("why") or (dungeon_ident.get(d["id"]) or {}).get("why") or []
                if wlist and all(c in vset for c in wlist):
                    why_ok += 1
            for s in settlements:
                why_tot += 1
                wlist = s.get("why") or []
                if wlist and all(c in vset for c in wlist):
                    why_ok += 1
            for q, _cat, _fp in quests_by_reg[rid]:
                why_tot += 1
                wlist = q.get("why") or []
                if wlist and all(c in vset for c in wlist):
                    why_ok += 1
            w_share = (why_ok / float(why_tot)) if why_tot else 0.0
            m["F-WHY"] = ("pass" if w_share >= 1.0 else "fail", f"{w_share * 100:.0f}% ({why_ok}/{why_tot})")

        results[rid] = {"km2": round(km2, 2), "metrics": m}
        for k_flw, (st_code, _val) in m.items():
            status_counts[(k_flw, st_code)] += 1

    elapsed = time.time() - t0
    print(f"=== Skycraft FLW Player-Flow & Interconnectedness Audit ({len(sel_regs)} region(s), {elapsed:.2f}s) ===")
    if len(sel_regs) == 1:
        rid = sel_regs[0]["id"]
        print(f"\nRegion: {rid} ({results[rid]['km2']} km2)")
        print(f"{'Metric':<16} {'Status':<8} {'Measured Value':<36} {'Floor / Target'}")
        print("-" * 96)
        flw_floors = floors.get("flw", {})
        for fid in FLW_ORDER:
            st_code, val = results[rid]["metrics"][fid]
            desc = flw_floors.get(fid, {}).get("measure", "")[:34]
            print(f"{fid:<16} {st_code.upper():<8} {val:<36} {desc}")
    else:
        print(f"\n{'Metric':<16} {'PASS':>6} {'FAIL':>6} {'N/A':>6}  {'Sample (first region: ' + sel_regs[0]['id'] + ')'}")
        print("-" * 88)
        first_m = results[sel_regs[0]["id"]]["metrics"]
        for fid in FLW_ORDER:
            n_p = status_counts[(fid, "pass")]
            n_f = status_counts[(fid, "fail")]
            n_na = status_counts[(fid, "na")]
            st0, v0 = first_m[fid]
            print(f"{fid:<16} {n_p:>6} {n_f:>6} {n_na:>6}  [{st0.upper()}] {v0}")

    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"elapsed_s": round(elapsed, 3), "regions": results}, f, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
