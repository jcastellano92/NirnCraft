"""Fast static data checks that catch the integration bugs merges keep producing.

usage: py -3 -I tools/qa/data_checks.py      (from the repo root)
Prints 'ERR ...' lines and a final summary line.
  - every JSON file under mod/src/main/resources and mod/src/main/lang parses
  - places: no duplicate ids across files listed in places/index.json; every file in the index exists
  - quests: index.json lists existing files
  - Java: no item/block/entity id registered twice in the same kind of registry (ITEMS / BLOCKS / ENTITIES)
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
JAVA = os.path.join(ROOT, "mod", "src", "main", "java")
errs = []


def err(msg):
    errs.append(msg)
    print("ERR", msg)


def main():
    n = 0
    for pattern in ("mod/src/main/resources/**/*.json", "mod/src/main/lang/**/*.json"):
        for f in glob.glob(os.path.join(ROOT, pattern), recursive=True):
            n += 1
            try:
                with open(f, encoding="utf-8") as fh:
                    json.load(fh)
            except Exception as e:  # noqa: BLE001
                err(f"bad json {os.path.relpath(f, ROOT)}: {str(e)[:100]}")

    pdir = os.path.join(RES, "skycraft", "tamriel", "places")
    idx = json.load(open(os.path.join(pdir, "index.json"), encoding="utf-8"))
    seen = {}
    places = 0
    for f in idx:
        p = os.path.join(pdir, f)
        if not os.path.exists(p):
            err(f"places index lists missing file {f}")
            continue
        for pl in json.load(open(p, encoding="utf-8")):
            places += 1
            if pl["id"] in seen:
                err(f"duplicate place id {pl['id']} in {f} and {seen[pl['id']]}")
            else:
                seen[pl["id"]] = f

    qdir = os.path.join(RES, "skycraft", "quests")
    qi = os.path.join(qdir, "index.json")
    if os.path.exists(qi):
        for f in json.load(open(qi, encoding="utf-8")):
            name = f if isinstance(f, str) else f.get("file", "")
            if name and not os.path.exists(os.path.join(qdir, name)):
                err(f"quests index lists missing file {name}")

    # duplicate registrations: classify each register("id") call by the DeferredRegister field it is called on
    kinds = defaultdict(lambda: defaultdict(list))
    reg = re.compile(r'\b([A-Z_]*(ITEMS|BLOCKS|ENTITIES|ENTITY_TYPES|BLOCK_ENTITIES|SOUNDS|EFFECTS|MENUS))\.register\(\s*"([a-z0-9_/]+)"')
    for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        src = open(f, encoding="utf-8", errors="replace").read()
        for m in reg.finditer(src):
            kinds[m.group(2)][m.group(3)].append(os.path.relpath(f, JAVA))
    for kind, ids in kinds.items():
        for i, where in ids.items():
            files = sorted(set(where))
            if len(where) > 1 and len(files) > 1:
                err(f"{kind} id '{i}' registered in {', '.join(files)}")

    # natural landmarks: kinds.json must match the generator's table and builders (tools/landmarks/gen_kinds.py)
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "landmarks"))
        import gen_kinds
        for e in gen_kinds.check():
            err("landmarks: " + e)
        on_disk = json.load(open(gen_kinds.OUT, encoding="utf-8"))["kinds"]
        if [k["id"] for k in on_disk] != [k["id"] for k in gen_kinds.KINDS]:
            err("landmarks: kinds.json is stale, run tools/landmarks/gen_kinds.py")
    except Exception as ex:  # a broken generator must show up as a check failure, not a crash
        err(f"landmarks: {ex}")


    # Forge's EventBus generates handler classes named __<SimpleClass>_<method>_<Event> in the class's package:
    # two nested classes with the same simple name and handler method in one package collide at runtime.
    seen_h = defaultdict(set)
    hre = re.compile(r'@SubscribeEvent\s*(?:\([^)]*\))?\s*public\s+(?:static\s+)?void\s+(\w+)\s*\(\s*([\w.]+)')
    for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        src = open(f, encoding="utf-8", errors="replace").read()
        pm = re.search(r'^package ([\w.]+);', src, re.M)
        pkg = pm.group(1) if pm else ""
        for m in hre.finditer(src):
            cls = re.findall(r'class\s+(\w+)', src[:m.start()])
            if cls:
                seen_h[(pkg, cls[-1], m.group(1), m.group(2).split('.')[-1])].add(os.path.relpath(f, JAVA))
    for k, files in seen_h.items():
        if len(files) > 1:
            err(f"event handler class name collision {k[1]}.{k[2]}({k[3]}) in package {k[0]}: {', '.join(sorted(files))}")
    print(f"{n} json files, {places} places, {sum(len(v) for v in kinds.values())} registry ids checked, {len(errs)} errors")


if __name__ == "__main__":
    main()
