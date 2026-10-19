#!/usr/bin/env python3
"""City plan lint: mod/src/main/resources/nirncraft/tamriel/cityplans/*.json against what CityPlan / CityPlans parse.

Checks (each one a problem; exit 1 + `problems N` when any):
  schema    the file parses, `id` equals the file name, `base` is none|zoning|java, every building / queue `kind` is
            an Archetype (Archetype.valueOf throws on an unknown kind: the whole plan fails to load), `face` / `back`
            are N|E|S|W, numeric pairs (`at`, `size`, gates) are two numbers
  index     cityplans/index.json lists every plan file and only existing ones; its overlays list covers what plans use
  places    the plan id is a place of tamriel/places (a plan for no place is never applied)
  styles    every overlay (plan, quarter, landmark, building) has styles/overlays/<id>.json; every kit is a kit palette
            name (Kits.kitNames); every culture string maps onto a culture (Culture.of turns unknown ones into nord)
  bounds    every building / landmark / plaza footprint (at +- size/2, Archetype default size) lies within the plan's
            reach (or radius); walled plans keep named buildings and landmarks inside the outermost wall
  ids       building / landmark / plaza names are unique within a plan; `sil` silhouette tags are unique across plans
  lang      named buildings / landmarks / plazas and the `names` roles have settlement.nirncraft.name.<key>
  gates     on a walled plan every road gate lies on the wall ring (radius r*cos(pi/sides)-6 .. r+24) at one of the
            wall's gate openings (gate angle a -> (sin a * r, cos a * r), as Plan.wallRing cuts them)

    py -3 tools/qa/cityplan_lint.py [--limit 25] [--plan whiterun]
"""
import argparse
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import refs  # noqa: E402

TAM = refs.SKY / "tamriel"
PLANS = TAM / "cityplans"
OVERLAYS = TAM / "styles" / "overlays"
SETTLE = refs.JAVA / "com" / "nirncraft" / "tamriel" / "settlement"
DIRS = {"N", "E", "S", "W", "NORTH", "EAST", "SOUTH", "WEST"}


def java_tables():
    arch = {}
    for m in re.finditer(r"^\s{4}([A-Z_]+)\(\s*(?:true|false)\s*,\s*(\d+)\s*,\s*(\d+)",
                         refs.load_text(SETTLE / "plan" / "Archetype.java"), re.M):
        arch[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    kits_src = refs.load_text(SETTLE / "kit" / "Kits.java")
    body = re.search(r"kitNames\(Culture c\)\s*\{(.*?)\n    \}", kits_src, re.S)
    kits = set(re.findall(r'"([a-z_]+)"', body.group(1))) if body else set()
    cult_src = refs.load_text(SETTLE / "Culture.java")
    enum = re.search(r"enum Culture\s*\{\s*([A-Z_,\s]+);", cult_src)
    cultures = {c.strip().lower() for c in enum.group(1).split(",")} if enum else set()
    of = re.search(r"Culture of\(String s\)(.*?)default ->", cult_src, re.S)
    aliases = set(re.findall(r'"([a-z_]+)"', of.group(1))) if of else set()
    return arch, kits | cultures, cultures | aliases


def pair(v):
    return isinstance(v, list) and len(v) >= 2 and all(isinstance(x, (int, float)) for x in v[:2])


def lint(only, limit):
    arch, kits, cultures = java_tables()
    places = refs.places()
    lang = refs.lang()
    overlays = {f.stem for f in OVERLAYS.glob("*.json")}
    index = refs.load(PLANS / "index.json", {}) or {}
    pr = refs.Problems()
    if not arch or not kits or not cultures:
        pr.add("setup", f"Java tables not parsed (archetypes {len(arch)}, kits {len(kits)}, cultures {len(cultures)})")
    files = sorted(f for f in PLANS.glob("*.json") if f.name != "index.json")
    listed = set(index.get("plans") or [])
    for f in files:
        if f.stem not in listed:
            pr.add("index", f"{f.name} is not in cityplans/index.json plans")
    for p in sorted(listed - {f.stem for f in files}):
        pr.add("index", f"index.json lists plan {p} which has no file")
    sils, used_overlays, n = {}, set(), 0
    for f in files:
        if only and f.stem != only:
            continue
        d = refs.load(f)
        if not isinstance(d, dict):
            pr.add("schema", f"{f.name}: does not parse as a JSON object")
            continue
        n += 1
        pid = d.get("id")
        w = f.stem
        if pid != f.stem:
            pr.add("schema", f"{w}: id {pid!r} differs from the file name")
        if pid and pid not in places:
            pr.add("places", f"{w}: no tamriel place {pid} (the plan is never applied)")
        if d.get("base", "none") not in ("none", "zoning", "java"):
            pr.add("schema", f"{w}: base {d.get('base')!r}")
        if d.get("back") and str(d["back"]).upper() not in DIRS:
            pr.add("schema", f"{w}: back {d['back']!r}")
        if d.get("sil"):
            if d["sil"] in sils:
                pr.add("ids", f"{w}: silhouette {d['sil']!r} also used by {sils[d['sil']]}")
            sils.setdefault(d["sil"], w)
        if d.get("kit") and d["kit"] not in kits:
            pr.add("styles", f"{w}: kit {d['kit']!r} is no kit palette")
        bound = d.get("reach") or d.get("radius") or 0
        walls = [x for x in d.get("walls") or [] if isinstance(x, dict) and x.get("r")]
        outer = max((x["r"] for x in walls), default=0)
        ov = [("plan", d.get("overlay"))]
        for q in d.get("quarters") or []:
            ov.append(("quarter", q.get("overlay")))
            if q.get("culture") and q["culture"].lower() not in cultures:
                pr.add("styles", f"{w}: quarter culture {q['culture']!r} is unknown (becomes nord)")
            if q.get("kit") and q["kit"] not in kits:
                pr.add("styles", f"{w}: quarter kit {q['kit']!r} is no kit palette")
        for role, key in (d.get("names") or {}).items():  # "" = the place has none (no inn in a stronghold)
            if key and "settlement.nirncraft.name." + key not in lang:
                pr.add("lang", f"{w}: names.{role} settlement.nirncraft.name.{key} has no lang entry")
        names = {}
        for kind, lst in (("building", d.get("buildings")), ("landmark", d.get("landmarks")), ("plaza", d.get("plazas"))):
            for b in lst or []:
                if not isinstance(b, dict):
                    pr.add("schema", f"{w}: a {kind} is not an object")
                    continue
                label = b.get("name") or b.get("id") or b.get("kind") or kind
                ov.append((f"{kind} {label}", b.get("overlay")))
                if kind == "building":
                    k = str(b.get("kind", "house")).upper()
                    if k not in arch and arch:
                        pr.add("schema", f"{w}: building {label}: kind {b.get('kind')!r} is no Archetype (plan fails to load)")
                    if b.get("culture") and b["culture"].lower() not in cultures:
                        pr.add("styles", f"{w}: building {label}: culture {b['culture']!r} is unknown (becomes nord)")
                    if b.get("kit") and b["kit"] not in kits:
                        pr.add("styles", f"{w}: building {label}: kit {b['kit']!r} is no kit palette")
                if b.get("face") and str(b["face"]).upper() not in DIRS:
                    pr.add("schema", f"{w}: {kind} {label}: face {b['face']!r}")
                if b.get("name"):
                    if "settlement.nirncraft.name." + b["name"] not in lang:
                        pr.add("lang", f"{w}: {kind} name settlement.nirncraft.name.{b['name']} has no lang entry")
                    if b["name"] in names:
                        pr.add("ids", f"{w}: name {b['name']} used by {names[b['name']]} and {kind} at {b.get('at')}")
                    names.setdefault(b["name"], f"{kind} at {b.get('at')}")
                at = b.get("at")
                if at is None:
                    continue
                if not pair(at):
                    pr.add("schema", f"{w}: {kind} {label}: at {at!r}")
                    continue
                size = b.get("size")
                if kind == "plaza":
                    half = (b.get("size") or 2 * b.get("r", 6)) / 2
                    hw = hd = half
                elif pair(size):
                    hw, hd = size[0] / 2, size[1] / 2
                else:
                    hw, hd = (x / 2 for x in arch.get(str(b.get("kind", "house")).upper(), (7, 9)))
                count = max(1, int(b.get("count", 1) or 1))
                step = b.get("step") if pair(b.get("step")) else [0, 0]
                for i in range(count):
                    x, z = at[0] + i * step[0], at[1] + i * step[1]
                    far = math.hypot(abs(x) + hw, abs(z) + hd)
                    if bound and far > bound + 4:
                        pr.add("bounds", f"{w}: {kind} {label} at [{x}, {z}] reaches {far:.0f}, beyond reach {bound}")
                    elif outer and kind != "plaza" and b.get("name") and math.hypot(x, z) > outer:
                        pr.add("bounds", f"{w}: {kind} {label} at [{x}, {z}] stands outside the wall (r {outer})")
        for where, o in ov:
            if o:
                used_overlays.add(o)
                if o not in overlays:
                    pr.add("styles", f"{w}: {where}: overlay {o!r} has no styles/overlays/{o}.json")
        gates = d.get("gates") or []
        for g in gates:
            if not pair(g):
                pr.add("schema", f"{w}: gate {g!r}")
        if walls and gates:
            wall = max(walls, key=lambda x: x["r"])
            r, sides = wall["r"], max(3, int(wall.get("sides", 16)))
            openings = wall.get("gates") or []
            openings = openings if isinstance(openings, list) else [openings]
            for g in (g for g in gates if pair(g)):
                dist = math.hypot(*g[:2])
                if not r * math.cos(math.pi / sides) - 6 <= dist <= r + 24:
                    pr.add("gates", f"{w}: gate {g[:2]} at radius {dist:.0f}, the wall ring is r {r}")
                    continue
                best = min((math.hypot(g[0] - math.sin(a) * dist, g[1] - math.cos(a) * dist) for a in openings
                            if isinstance(a, (int, float))), default=None)
                if best is None or best > 10:
                    pr.add("gates", f"{w}: road gate {g[:2]} meets the wall (r {r}) away from every gate opening"
                                    + (f" (nearest {best:.0f} blocks)" if best is not None else " (the wall has none)"))
    for o in sorted(used_overlays - set(index.get("overlays") or [])):
        if index.get("overlays") is not None:
            pr.add("index", f"overlay {o} is used by a plan but not in cityplans/index.json overlays")
    print(f"cityplans: {n} plans, {len(overlays)} overlays, {len(arch)} archetypes, {len(kits)} kits; "
          f"{sum(1 for f in files if refs.load(f, {}).get('walls'))} walled")
    return pr.report(limit)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=25)
    ap.add_argument("--plan")
    a = ap.parse_args()
    return lint(a.plan, a.limit)


if __name__ == "__main__":
    raise SystemExit(main())
