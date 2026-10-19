"""The camera library: named views of places, dungeons, towns, landscapes and biomes that make good tests.

Two sources: cameras the offline planner suggests for every place (cameras.py, per seed), and cameras the owner
saves in game with `/nirncraft-tour save NAME [note]` (host only, any world): `harness.py library import` copies them
from every Prism instance's nirncraft-cameras.jsonl into tools/harness/cameras/library.json (in git), and each
becomes a test at exactly that view (scene cam-<name>). `library suggest` lists places that still lack a good camera.
"""
import json
import math
import os
from pathlib import Path

import catalog

LIBRARY = catalog.ROOT / "tools/harness/cameras/library.json"
PRISM = Path(os.environ.get("NIRNCRAFT_PRISM_ROOT", Path(os.environ.get("APPDATA", "")) / "PrismLauncher"))


def load():
    try:
        return json.loads(LIBRARY.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def save(entries):
    LIBRARY.parent.mkdir(parents=True, exist_ok=True)
    LIBRARY.write_text(json.dumps(entries, indent=1) + "\n", encoding="utf-8")


def saved_files():
    out = []
    for inst in (PRISM / "instances").glob("*"):
        for sub in (".minecraft", "minecraft"):
            f = inst / sub / "nirncraft-cameras.jsonl"
            if f.exists():
                out.append(f)
    return out


def import_saved(replace=False):
    """Merge cameras saved in game into the library; returns (added, skipped)."""
    lib = load()
    names = {e["name"] for e in lib}
    added, skipped = [], []
    for f in saved_files():
        for line in f.read_text(encoding="utf-8").splitlines():
            try:
                e = json.loads(line)
            except ValueError:
                continue
            e["source"] = f"owner ({f.parent.parent.name})"
            if e["name"] in names and not replace:
                skipped.append(e["name"])
                continue
            lib = [x for x in lib if x["name"] != e["name"]] + [e]
            names.add(e["name"])
            added.append(e["name"])
    save(lib)
    return added, skipped


def scenes():
    """Every library camera as a scene at exactly its view (time / weather from the entry, else noon / clear)."""
    out = []
    for e in load():
        if e.get("dimension", "minecraft:overworld") != "minecraft:overworld":
            continue  # realm / interior dimensions: saved for reference, no overworld pose
        yaw, pitch = math.radians(e["yaw"]), math.radians(e["pitch"])
        fwd = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
        feet = (e["x"], e["y"] - 1.62, e["z"])
        look = (e["x"] + fwd[0] * 16, e["y"] + fwd[1] * 16, e["z"] + fwd[2] * 16)
        tags = ["suite:camera-library", "view:library"] + ([f"place:{e['place']}"] if e.get("place") else []) \
            + ([f"biome:{e['biome'].split(':')[-1]}"] if e.get("biome") else [])
        out.append({"id": f"cam-{e['name']}".replace("_", "-").lower(), "title": e.get("note") or e["name"],
                    "_file": "cameras/library.json", "tier": "live", "kind": "visual", "variants": ["shaders"],
                    "tags": tags, "seed": e.get("seed"),
                    "pose": {"camera": [round(v, 2) for v in feet + look]},
                    "time": e.get("time", 6000), "weather": e.get("weather", "clear"), "checks": {"maxBlackTiles": 0},
                    "source": e.get("source", "library")})
    return out


def suggest(limit=40):
    """Places with no library camera, and whose planned cameras see them poorly (or are missing): biggest first."""
    lib_places = {e.get("place") for e in load()}
    try:
        plan = json.loads(sorted((catalog.ROOT / ".work/harness/cameras").glob("*.json"))[-1].read_text(encoding="utf-8"))
    except (IndexError, OSError, ValueError):
        plan = {}
    out = []
    for pid, pl in catalog.places().items():
        if pid in lib_places:
            continue
        shots = plan.get(pid) or {}
        best = max((c.get("visible", 0) for cands in shots.values() if isinstance(cands, list) for c in cands),
                   default=None)
        if best is None or best < 0.6:
            out.append((pl.get("radius", 0), pid, pl.get("type", "?"), best))
    out.sort(reverse=True)
    return out[:limit]
