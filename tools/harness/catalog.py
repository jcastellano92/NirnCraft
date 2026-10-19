"""Scene catalog: every visual and non-visual test the harness knows, from tools/harness/scenes/*.json plus generated
sets (the entity gallery from the mod's own entity list). See tools/harness/README.md for the scene schema.

A scene is a plain dict after loading; derived keys start with an underscore (_file, _xz, _tier...).
"""
import hashlib
import json
import math
import re
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENES = ROOT / "tools/harness/scenes"
PLACES = ROOT / "mod/src/main/resources/nirncraft/tamriel/places"
LANG = ROOT / "mod/src/main/lang/nirncraft"
NOT_SCENES = {"groups.json", "legacy-seed.json"}
POSES = ("visit", "camera", "place", "dungeon", "realm", "spawn", "stage")
WEATHERS = {"clear", "cloudy", "overcast", "fog", "drizzle", "rain", "storm", "snow", "blizzard", "ashfall",
            "ash_storm", "dust", "sandstorm", "blight", "gale"}


@lru_cache(maxsize=1)
def places():
    out = {}
    for name in json.loads((PLACES / "index.json").read_text(encoding="utf-8")):
        f = PLACES / name
        if not f.exists():
            continue
        data = json.loads(f.read_text(encoding="utf-8"))
        for p in data if isinstance(data, list) else data.get("places", []):
            if isinstance(p, dict) and "id" in p and "x" in p and "z" in p:
                out.setdefault(p["id"], p)
    return out


@lru_cache(maxsize=1)
def groups():
    f = SCENES / "groups.json"
    g = json.loads(f.read_text(encoding="utf-8")) if f.exists() else {}
    g.setdefault("live", {"description": "default live preview", "jvm": {}})
    return g


# ---------------------------------------------------------------------------------------------------- gallery
# A stage in the sky (fixed noon light, clear weather, sky backdrop, nothing else in frame): every entity gets its own
# scene so one creature, one section (gallery:monster ...) or all of them can be asked for. All gallery scenes share
# the stage, so a run never moves the camera between shots.
STAGE = (4096, 300, 4096)
BIG = {"giant": 12, "mammoth": 12, "dragon": 26, "alduin": 30, "dwarven_centurion": 12, "netch": 10, "troll": 7,
       "frost_troll": 7, "silt_strider": 30, "wispmother": 7, "dragon_priest": 6, "werewolf": 6, "horker": 6,
       "sabre_cat": 6, "bear": 6, "mudcrab": 4}


def _entity_categories():
    """id -> monster / creature / npc / water / misc, read from the Java registrations (best effort)."""
    cats = {}
    for f in ROOT.glob("mod/src/main/java/com/nirncraft/**/*.java"):
        text = f.read_text(encoding="utf-8", errors="replace")
        if "MobCategory." not in text:
            continue
        for m in re.finditer(r'"([a-z0-9_]+)"', text):
            tail = text[m.end():m.end() + 500]
            c = re.search(r"MobCategory\.(\w+)", tail)
            if c and m.group(1) not in cats:
                cats[m.group(1)] = {"MONSTER": "monster", "CREATURE": "creature", "AMBIENT": "creature",
                                    "WATER_CREATURE": "water", "WATER_AMBIENT": "water",
                                    "UNDERGROUND_WATER_CREATURE": "water", "AXOLOTLS": "water"}.get(c.group(1), "misc")
    return cats


def _lang_ids(kind):
    ids = set()
    for f in LANG.glob("*.json"):
        for k in json.loads(f.read_text(encoding="utf-8")):
            p = k.split(".")
            if len(p) == 3 and p[0] == kind and p[1] == "nirncraft":
                ids.add(p[2])
    return ids


WEAPON = re.compile(r"_(sword|dagger|greatsword|battleaxe|war_axe|mace|warhammer|bow|crossbow|staff|spear|glaive|halberd|"
                    r"claw|katana|scimitar|axe)$")
FURNITURE = re.compile(r"(chair|table|bench|bed|shelf|chest|cabinet|stool|barrel|crate|wardrobe|desk|throne|rug|candle|"
                       r"chandelier|lantern|drawer|counter|cupboard|dresser|bookcase|sconce|workbench|bedroll|tablet)")


def _stage_scene(sid, title, tags, setup, d=4.5, frames=None, extra=None):
    sx, sy, sz = STAGE
    clear = f"try kill @e[type=!player,x={sx},y={sy},z={sz},distance=..40]"
    s = {"id": sid, "title": title, "_file": "(generated gallery)", "tags": ["suite:gallery"] + tags,
         "pose": {"stage": [sx, sy, sz, d]}, "time": 6000, "weather": "clear",
         "setup": [clear, f"try fill {sx - 7} {sy + 1} {sz - 7} {sx + 7} {sy + 6} {sz + 7} minecraft:air"] + setup,
         "cleanup": [clear, "try difficulty peaceful"], "variants": ["shaders"], "checks": {"maxBlackTiles": 0},
         "source": "generated from the mod's lang ids (tools/harness/catalog.py gallery)"}
    if frames:
        s["frames"] = frames
    s.update(extra or {})
    return s


def gallery():
    """Generated visual galleries on the sky stage, one scene per subject so any part can be asked for:
    gallery:monster|creature|npc|water|misc (entities), gallery:armor (one scene per armour material: front and back
    stands with the set, its shield and a weapon), gallery:weapons (every weapon of a material on stands),
    gallery:blocks / gallery:furniture (block lineups), suite:animation (entities moving, a 6-frame filmstrip)."""
    sx, sy, sz = STAGE
    out = []
    cats = _entity_categories()
    for e in sorted(_lang_ids("entity")):
        cat = cats.get(e, "npc")
        summon = (f"command summon nirncraft:{e} {sx} {sy + 1} {sz} {{NoAI:1b,PersistenceRequired:1b,Silent:1b,"
                  f"Rotation:[180f,0f]}}")
        tags = [f"gallery:{cat}", f"gallery:entity:{e}", "feature:creature"]
        out.append(_stage_scene(f"gallery-{e.replace('_', '-')}", f"Gallery: {e}", tags,
                                ["try difficulty easy", summon, "wait 30"], BIG.get(e, 4.5)))
        # animation: free to move inside an invisible pen; six frames a third of a second apart
        pen = f"try fill {sx - 6} {sy + 1} {sz - 6} {sx + 6} {sy + 5} {sz + 6} minecraft:barrier hollow"
        walk = summon.replace("NoAI:1b,", "")
        out.append(_stage_scene(f"anim-{e.replace('_', '-')}", f"Animation: {e}", tags + ["suite:animation",
                                "feature:animation"], [pen, "try difficulty easy", walk, "wait 20"],
                                BIG.get(e, 4.5) * 1.3, frames=[6, 7]))
    items = _lang_ids("item")
    mats = sorted({i[: -len("_chestplate")] for i in items if i.endswith("_chestplate")})
    for m in mats:
        piece = lambda slot: f"{m}_{slot}" if f"{m}_{slot}" in items else None  # noqa: E731
        weapon = next((f"{m}_{w}" for w in ("sword", "war_axe", "mace", "dagger", "spear", "bow") if f"{m}_{w}" in items), None)
        eq = []
        for slot, part in (("head", "helmet"), ("chest", "chestplate"), ("legs", "leggings"), ("feet", "boots")):
            if piece(part):
                eq.append(f'{slot}:{{id:"nirncraft:{piece(part)}"}}')
        if weapon:
            eq.append(f'mainhand:{{id:"nirncraft:{weapon}"}}')
        if piece("shield"):
            eq.append(f'offhand:{{id:"nirncraft:{piece("shield")}"}}')
        nbt = "ShowArms:1b,NoBasePlate:1b,NoGravity:1b,equipment:{" + ",".join(eq) + "}"
        out.append(_stage_scene(f"gallery-armor-{m.replace('_', '-')}", f"Armour: {m}", ["gallery:armor", f"gallery:material:{m}",
                                "feature:armor"],
                                [f"command summon minecraft:armor_stand {sx - 1} {sy + 1} {sz} {{{nbt},Rotation:[180f,0f]}}",
                                 f"command summon minecraft:armor_stand {sx + 1} {sy + 1} {sz} {{{nbt},Rotation:[0f,0f]}}",
                                 "wait 10"], 4.0))
    by_mat = {}
    for i in sorted(items):
        if WEAPON.search(i):
            by_mat.setdefault(i.rsplit("_", 2)[0] if i.endswith(("_war_axe",)) else i.rsplit("_", 1)[0], []).append(i)
    for m, ws in sorted(by_mat.items()):
        for part in range(0, len(ws), 7):
            chunk = ws[part:part + 7]
            stands = [f"command summon minecraft:armor_stand {sx - 6 + 2 * k} {sy + 1} {sz} {{ShowArms:1b,NoBasePlate:1b,"
                      f"NoGravity:1b,Invisible:1b,Rotation:[180f,0f],equipment:{{mainhand:{{id:\"nirncraft:{w}\"}}}}}}"
                      for k, w in enumerate(chunk)]
            sid = f"gallery-weapons-{m.replace('_', '-')}" + (f"-{part // 7 + 1}" if len(ws) > 7 else "")
            out.append(_stage_scene(sid, f"Weapons: {m}", ["gallery:weapons", f"gallery:material:{m}", "feature:weapons"],
                                    stands + ["wait 10"], 9.0))
    blocks = sorted(_lang_ids("block"))
    for part in range(0, len(blocks), 9):
        chunk = blocks[part:part + 9]
        fur = any(FURNITURE.search(b) for b in chunk)
        place = [f"try setblock {sx - 8 + 2 * k} {sy + 1} {sz} nirncraft:{b}" for k, b in enumerate(chunk)]
        out.append(_stage_scene(f"gallery-blocks-{part // 9 + 1:02d}", f"Blocks {part + 1}-{part + len(chunk)}: "
                                + ", ".join(chunk), ["gallery:blocks"] + (["gallery:furniture"] if fur else [])
                                + [f"gallery:block:{b}" for b in chunk], place + ["wait 5"], 10.0))
    return out


# ---------------------------------------------------------------------------------------------------- loading
def load():
    scenes, seen = [], {}
    for f in sorted(SCENES.glob("*.json")):
        if f.name in NOT_SCENES:
            continue
        doc = json.loads(f.read_text(encoding="utf-8"))
        defaults = doc.get("defaults", {})
        for s in doc.get("scenes", []):
            s = {**defaults, **s, "_file": f.name}
            s.setdefault("tags", [])
            if doc.get("suite") and f"suite:{doc['suite']}" not in s["tags"]:
                s["tags"].append(f"suite:{doc['suite']}")
            scenes.append(s)
    scenes += gallery()
    scenes += planned_places()
    scenes += probes()
    scenes += grounding()
    for s in scenes:
        if s["id"] in seen:
            raise ValueError(f"duplicate scene id {s['id']} ({seen[s['id']]} and {s['_file']})")
        seen[s["id"]] = s["_file"]
        s.setdefault("tier", "live")
        s.setdefault("group", "live")
        s.setdefault("variants", ["shaders"])
        s.setdefault("kind", "check" if s["tier"] == "check" else "visual")
        if "camera" in (s.get("pose") or {}):
            s.setdefault("seed", 1234)  # absolute poses belong to the world they were made in (surface-relative ones don't)
        s["_xz"] = position(s)
    return scenes


# ---------------------------------------------------------------------------------------------------- planned places
PLANNED_TYPES = {"capital", "city", "town", "village", "fort", "landmark", "standing_stone", "word_wall",
                 "dragon_mound", "daedric_shrine"}


def planned_places():
    """Two planned shots (aerial, approach) for every named place: cameras come from the offline planner for the active
    seed (tools/harness/cameras.py), so these work in any world and never go stale."""
    out = []
    for pid, pl in places().items():
        kind = str(pl.get("type", "")).lower()
        if kind not in PLANNED_TYPES:
            continue
        region = pl.get("region") or ""
        for shot in ("aerial", "approach"):
            out.append({"id": f"atlas-{pid.replace('_', '-')}-{shot}", "title": f"{pid} ({shot}, planned)",
                        "_file": "(generated planned places)",
                        "tags": ["suite:places-planned", f"place:{pid}", f"type:{kind}", f"shot:{shot}"]
                                + ([f"region:{region}"] if region else []),
                        "pose": {"place": pid, "shot": shot}, "time": 6000, "weather": "clear",
                        "variants": ["shaders"], "checks": {}, "source": "planned from the places index"})
    return out


PROBES = ROOT / ".work/harness/probes.json"


def probes():
    """Ad-hoc tests made with `harness.py probe` (not in git; pruned after a day / beyond the newest 40)."""
    try:
        data = json.loads(PROBES.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return [{**s, "_file": "(probe)"} for s in data.values()]


def grounding():
    """Floating-structure census for every named place (live, no picture): /nirncraft-landmarks groundcheck counts site,
    minor-site and prop parts with no way down to the terrain, against a plain-terrain control. Numbers get
    before/after like images; a place getting worse fails the gate."""
    out = []
    for pid, pl in places().items():
        kind = str(pl.get("type", "")).lower()
        if kind not in PLANNED_TYPES:
            continue
        half = max(48, int(pl.get("radius", 40) * 1.5))
        region = pl.get("region") or ""
        out.append({"id": f"ground-{pid.replace('_', '-')}", "title": f"{pid}: floating parts census",
                    "_file": "(generated grounding)", "kind": "measure",
                    "tags": ["suite:grounding", "check:floating", f"place:{pid}", f"type:{kind}"]
                            + ([f"region:{region}"] if region else []),
                    "pose": {"visit": [pl["x"], pl["z"] + half, 80, 180, 30]}, "variants": ["measure"],
                    "measure_command": f"command nirncraft-landmarks groundcheck {pl['x']} {pl['z']} {half}",
                    "measure_file": "nirncraft-landmarks/groundcheck.json",
                    "measure_sum": {"floating": "categories.*.floating", "stray": "categories.*.stray",
                                    "built": "categories.*.built", "control": "categories.control.floating"},
                    "checks": {}, "source": "LandmarkCommands groundcheck"})
    return out


def legacy_seed():
    f = SCENES / "legacy-seed.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else {}


def position(s):
    """Rough world x,z of a scene, for ordering runs by distance (None: no move / unknown)."""
    p = s.get("pose") or {}
    if "visit" in p:
        return p["visit"][0], p["visit"][1]
    if "camera" in p:
        return p["camera"][0], p["camera"][2]
    if "stage" in p:
        return p["stage"][0], p["stage"][2]
    if "place" in p:
        pl = places().get(p["place"])
        if pl:
            dx, dz = p.get("offset", [0, 0])
            return pl["x"] + dx, pl["z"] + dz
    if "dungeon" in p:
        pl = places().get(p["dungeon"])
        if pl:
            return pl["x"], pl["z"]
    return None


def fingerprint(s, inputs):
    """What a scene's result depends on: its own definition plus the build inputs it can see."""
    clean = {k: v for k, v in s.items() if not k.startswith("_")}
    h = hashlib.sha256(json.dumps(clean, sort_keys=True).encode())
    for k in sorted(inputs):
        h.update(f"{k}={inputs[k]}".encode())
    return h.hexdigest()[:16]


# ---------------------------------------------------------------------------------------------------- live actions
def _look(x, y, z, yaw, pitch, dist=100.0):
    # Minecraft: yaw 0 looks +Z, 90 looks -X; pitch negative looks up
    ry, rp = math.radians(yaw), math.radians(pitch)
    return (round(x - math.sin(ry) * math.cos(rp) * dist, 2), round(y - math.sin(rp) * dist, 2),
            round(z + math.cos(ry) * math.cos(rp) * dist, 2))


def pose_actions(s, plan=None, candidate=0):
    """Live verbs that put the camera on the scene (weather and time included), ready to settle. `plan`: the camera
    plan for the active seed (place poses with a "shot"); `candidate`: which planned pose (the next after a refusal)."""
    p = s.get("pose") or {}
    weather = s.get("weather")
    lines = []
    if s.get("time") is not None:
        lines.append(f"command time set {int(s['time'])}")
    if "visit" in p:
        x, z, h, yaw, pitch = p["visit"]
        lines.append(f"visit {int(x)} {int(z)} {int(h)} {yaw} {pitch} {weather or 'clear'}")
        weather = None  # visit sets it
    elif "camera" in p:
        lines.append("camera " + " ".join(str(v) for v in p["camera"]))
    elif "place" in p and p.get("shot"):
        cands = [(e["camera"], e["look"]) for e in ((plan or {}).get(p["place"]) or {}).get(p["shot"], [])]
        if candidate >= len(cands):
            raise ValueError(f"{s['id']}: no planned {p['shot']} camera #{candidate + 1} for {p['place']}")
        (cx, cy, cz), (lx, ly, lz) = cands[candidate]
        lines.append(f"camera {cx} {cy} {cz} {lx} {ly} {lz}")
    elif "place" in p:
        pl = places().get(p["place"])
        if pl is None:
            raise ValueError(f"{s['id']}: unknown place {p['place']}")
        dx, dz = p.get("offset", [0, 0])
        lines.append(f"visit {int(pl['x'] + dx)} {int(pl['z'] + dz)} {int(p.get('height', 40))} {p.get('yaw', 0)} "
                     f"{p.get('pitch', -15)} {weather or 'clear'}")
        weather = None
    elif "stage" in p:
        x, y, z, d = p["stage"]
        lines += [f"try fill {x - 7} {y} {z - 7} {x + 7} {y} {z + 7} minecraft:glass",
                  f"try fill {x - 7} {y} {z - 7} {x + 7} {y} {z + 7} minecraft:smooth_stone",
                  f"camera {x} {y + 1 + d * 0.45:.2f} {z - d:.2f} {x} {y + 1 + d * 0.25:.2f} {z}"]
    elif "dungeon" in p:
        lines.append(f"command nirncraft-dungeon-qa view {p['dungeon']} {p.get('view', 'entrance')}")
        lines.append("try effect clear @p minecraft:night_vision")  # the view adds night vision; judge real light
    elif "realm" in p:
        lines.append(f"command nirncraft-realm go {p['realm']}")
        if p.get("look"):
            lines.append(f"command nirncraft-realm look {p['look']}")
    if weather:
        if weather not in WEATHERS:
            raise ValueError(f"{s['id']}: unknown weather {weather}")
        lines.append(f"command execute as @p at @s run nirncraft-weather set {weather} 120")
    if s.get("front"):
        x, z, r, w, *v = s["front"]
        lines.append(f"front {x} {z} {r} {w} " + " ".join(str(c) for c in v))
    return lines


def capture_actions(s, tag):
    lines = ["settle 1800"]
    if s.get("wait"):
        lines.append(f"wait {int(s['wait'])}")
    lines += list(s.get("setup", []))
    if s.get("measure"):
        lines.append(f"measure {tag} {int(s['measure'])}")
    if s.get("frames"):
        n, every = s["frames"]
        for f in range(n):
            lines += [f"capture {tag}-f{f}"] + ([f"wait {every}"] if f < n - 1 else [])
    else:
        lines.append(("capture-ui " if s.get("capture") == "ui" else "capture ") + tag)
    lines += list(s.get("cleanup", []))
    if "realm" in (s.get("pose") or {}):
        lines.append("try nirncraft-realm home")
    return lines
