"""Shared id sets for the offline reference validators (quest_refs, cityplan_lint, uniques_audit, spell_lint, ...).

Everything is read from the sources, no game: lang keys (mod/src/main/lang/nirncraft/*.json), places (tamriel/places via
index.json), NirnCraft items (assets/nirncraft/items definitions + item/block lang names), NirnCraft entities (the Java
registrations, via tools/audit_entity_renderers.py), and the vanilla 26.3 items / entity types from the reference jars
(tools/qa/mc263.py; None when the jars are absent, then minecraft: ids are not verified).
"""
from __future__ import annotations

import functools
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MAIN = ROOT / "mod" / "src" / "main"
RES = MAIN / "resources"
SKY = RES / "nirncraft"
JAVA = MAIN / "java"
LANG_DIR = MAIN / "lang" / "nirncraft"
ASSETS = RES / "assets" / "nirncraft"
DATA = RES / "data" / "nirncraft"
sys.path[:0] = [str(HERE), str(ROOT / "tools")]


def load(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def load_text(path):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def rel(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


@functools.lru_cache(None)
def lang():
    out = {}
    for f in sorted(LANG_DIR.glob("*.json")):
        d = load(f, {})
        if isinstance(d, dict):
            out.update(d)
    return out


@functools.lru_cache(None)
def places():
    """{place id: place dict (with '_file')} from tamriel/places/index.json."""
    out = {}
    base = SKY / "tamriel" / "places"
    for fn in load(base / "index.json", []) or []:
        for p in load(base / fn, []) or []:
            if isinstance(p, dict) and p.get("id"):
                out.setdefault(p["id"], dict(p, _file=fn))
    return out


@functools.lru_cache(None)
def nirn_items():
    ids = {f.stem for f in (ASSETS / "items").glob("*.json")}
    for k in lang():
        p = k.split(".")
        if len(p) == 3 and p[0] in ("item", "block") and p[1] == "nirncraft":
            ids.add(p[2])
    return ids


@functools.lru_cache(None)
def nirn_entities():
    import audit_entity_renderers as aer
    files = aer.java_files()
    fields, loops = aer.registrations(files, aer.spec_ids(files))
    ids = {v.lstrip("?") for v in fields.values()}
    for v in loops.values():
        ids.update(v)
    return ids


@functools.lru_cache(None)
def _jar_sets():
    import mc263
    items = ents = None
    cj = mc263.client_jar()
    if cj:
        import zipfile
        try:
            with zipfile.ZipFile(cj) as z:
                items = {n[len("assets/minecraft/items/"):-5] for n in z.namelist()
                         if n.startswith("assets/minecraft/items/") and n.endswith(".json")} or None
        except (OSError, zipfile.BadZipFile):
            items = None
    kj = mc263.class_jar()
    if kj:
        b = mc263.read(kj, "net/minecraft/world/entity/EntityTypes.class")
        if b:
            try:
                ents = {n.lower() for n, d in mc263.static_fields(b) if d == "Lnet/minecraft/world/entity/EntityType;"} or None
            except Exception:  # noqa: BLE001
                ents = None
    return items, ents, mc263.missing()


def vanilla_items():
    return _jar_sets()[0]


def vanilla_entities():
    return _jar_sets()[1]


def jar_note():
    items, ents, why = _jar_sets()
    return "vanilla ids verified against the 26.3 jars" if items and ents else why + ": minecraft: ids not verified"


def item_ok(rid):
    """True when a namespaced item id exists (None: cannot tell, e.g. another mod's namespace or no vanilla jar)."""
    rid = rid.lstrip("#")
    ns, _, path = rid.partition(":") if ":" in rid else ("minecraft", "", rid)
    if ns == "nirncraft":
        return path in nirn_items()
    if ns == "minecraft":
        v = vanilla_items()
        return None if v is None else path in v
    return None


def entity_ok(rid):
    ns, _, path = rid.partition(":") if ":" in rid else ("minecraft", "", rid)
    if ns == "nirncraft":
        return path in nirn_entities()
    if ns == "minecraft":
        v = vanilla_entities()
        return None if v is None else path in v
    return None


LANG_KEY = re.compile(r"^(?:item|block|entity|story|quest|place|gui|tooltip|spell|effect|message|book|lore|dungeon|"
                      r"region|faction|npc|title|skill|perk)\.nirncraft\.[a-z0-9_.]+$")


def looks_like_lang_key(s):
    return isinstance(s, str) and bool(LANG_KEY.match(s))


class Problems:
    """Collects problems by category; prints the worst first and a final `problems N` line."""

    def __init__(self):
        self.items = []

    def add(self, cat, msg):
        self.items.append((cat, msg))

    def report(self, limit=40, title="problems"):
        by = {}
        for c, m in self.items:
            by.setdefault(c, []).append(m)
        for c, ms in sorted(by.items(), key=lambda kv: -len(kv[1])):
            print(f"{c}: {len(ms)}")
            for m in ms[:limit]:
                print(f"  {m}")
            if len(ms) > limit:
                print(f"  ... {len(ms) - limit} more")
        print(f"{title} {len(self.items)}")
        return 1 if self.items else 0
