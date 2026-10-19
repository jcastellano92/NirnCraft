"""Subjects: everything the game defines, times the views each kind of subject gets, as scenes.

The game lists its subjects itself (`harness.py run subject-index` -> /nirncraft-qa subjects -> .work/harness/
subjects.json), so a new entity, item, block, spell, race, role or building type gets its scenes without anyone
writing them. What each kind is shown as lives in VIEWS; how a view is staged lives in one function per view
(RECIPES). Adding a view: one entry in VIEWS and one recipe.

Every scene carries: subject:<id>, kind:<kind>, view:<view>, suite:subjects, and a sheet role (front / side /
animation / other) so `harness.py sheet <id>` lays it out.
"""
import json

import catalog

# what each kind of subject is shown as (views in sheet order)
VIEWS = {
    "weapon": ["icon", "dropped", "held"],
    "tool": ["icon", "dropped", "held"],
    "armour": ["icon", "dropped", "worn"],
    "ingredient": ["icon", "dropped", "source"],
    "food": ["icon", "dropped"],
    "fish": ["icon", "dropped"],
    "item": ["icon", "dropped"],
    "block_item": ["icon"],
    "spawn_egg": [],
    "flora": ["placed", "icon"],
    "crop": ["placed"],
    "sapling": ["placed"],
    "block": ["placed"],
    "light": ["placed", "placed-night"],
    "door": ["placed"],
    "furniture": ["placed"],
    "race": ["front", "side"],
    "role": ["front"],
    "spell": ["cast"],
    "building": ["exterior", "exterior-side", "inside"],
}
ROLE = {"icon": "front", "placed": "front", "front": "front", "exterior": "front", "cast": "front",
        "side": "side", "exterior-side": "side", "dropped": "side", "placed-night": "other", "held": "other",
        "worn": "other", "source": "other", "inside": "other", "exterior-night": "other"}
WORN_RACES = ("nord", "khajiit", "argonian", "altmer", "orc")  # a spread of body shapes; --tag race:<r> for one
SLOTS = {"head": "armor.head", "chest": "armor.chest", "legs": "armor.legs", "feet": "armor.feet"}
BUILD_AT = (8192, 540, 8192)  # the building yard above the clouds: one building at a time, rebuilt per scene


def _scene(kind, sid, view, title, setup, frame=None, extra=None, **kw):
    sx, sy, sz = catalog.STAGE
    s = catalog._stage_scene(f"{view}-{kind}-{sid}".replace("_", "-"), title,
                             ["suite:subjects", f"subject:{sid}", f"kind:{kind}", f"view:{view}"],
                             setup, 4.5, extra={"role": ROLE.get(view, "other"), **(extra or {})})
    if frame:
        s["frame"] = frame
    if frame or (extra or {}).get("frameBox"):  # a framed subject fills the picture: dark fur or cloth is not a
        s["checks"] = {"maxBlackTiles": 3}      # NaN box; a couple of dark areas are allowed, none elsewhere
    s.update(kw)
    return s


def _icon(kind, it):
    return _scene(kind, it, "icon", f"{it}: as the UI shows it", [f"showcase nirncraft:{it}", "wait 10"],
                  capture="ui", cleanup=["ui close"])


def _dropped(kind, it):
    sx, sy, sz = catalog.STAGE
    return _scene(kind, it, "dropped", f"{it}: dropped", [
        f"command summon item {sx} {sy + 1.1} {sz} {{Item:{{id:\"nirncraft:{it}\",count:1}},PickupDelay:32767,"
        f"Age:-32768,NoGravity:1b}}", "wait 10"], frame=[30, 0.45])


def _npc(role="citizen", race="nord"):
    sx, sy, sz = catalog.STAGE
    return f"command nirncraft-qa npc {role} {race} {sx} {sy + 1} {sz}"


def _equip(slot, it):
    return (f"command item replace entity @e[type=nirncraft:npc,limit=1,sort=nearest] {slot} with "
            f"nirncraft:{it}")


def recipes(kind, rec, index):
    """The scenes of one subject (rec: its index entry)."""
    it = rec["id"]
    sx, sy, sz = catalog.STAGE
    out = []
    for view in VIEWS.get(kind, []):
        if view == "icon":
            out.append(_icon(kind, it))
        elif view == "dropped":
            out.append(_dropped(kind, it))
        elif view == "held":
            out.append(_scene(kind, it, "held", f"{it}: held by an NPC", [_npc(), _equip("weapon.mainhand", it),
                                                                           "wait 10"], frame=[30, 0.7]))
        elif view == "worn" and rec.get("slot") in SLOTS:
            for race in WORN_RACES:
                s = _scene(kind, it, "worn", f"{it}: worn by a {race}", [_npc("citizen", race),
                           _equip(SLOTS[rec["slot"]], it), "wait 10"], frame=[25, 0.7])
                s["id"] += f"-{race}"
                s["tags"].append(f"race:{race}")
                out.append(s)
        elif view == "source":
            block = next((b for b in index.get("blocks", []) if b["id"] == it or b["id"] == f"{it}_plant"), None)
            if block:
                out.append(_placed(kind, it, block["id"], "flora", view="source"))
        elif view in ("placed", "placed-night"):
            s = _placed(kind, it, it, kind, view=view)
            if view == "placed-night":
                s["time"] = 18000
            out.append(s)
        elif view in ("front", "side"):
            npc = _npc("citizen", it) if kind == "race" else _npc(it, "nord")
            out.append(_scene(kind, it, view, f"{it} ({view})", [npc, "wait 10"],
                              frame=[30 if view == "front" else 90, 0.7]))
        elif view == "cast":  # in the dark studio, seen from behind the caster, cast at the far wall
            s = _scene(kind, it, "cast", f"spell {it}", [f"command execute as @p run nirncraft-dev scenario equip {it}",
                       "perspective back", "cast start", "wait 8"], frames=[4, 6],
                       cleanup=["cast stop", "perspective first"])
            s["pose"] = {"studio": True}
            s["time"] = 18000  # the studio is closed: no sky light, only its own lights and the spell's
            out.append(s)
    return out


def _placed(kind, it, block, block_kind, view="placed"):
    sx, sy, sz = catalog.STAGE
    ground = {"flora": "grass_block", "sapling": "grass_block", "crop": "farmland"}.get(block_kind)
    setup = ([f"try setblock {sx} {sy} {sz} minecraft:{ground}"] if ground else []) + [
        f"command setblock {sx} {sy + 1} {sz} nirncraft:{block}", "wait 10"]
    return _scene(kind, it, view, f"{it}: placed" + (" at night" if view == "placed-night" else ""), setup,
                  extra={"frameBox": [sx, sy + 1, sz, sx, sy + 1, sz, 30, 0.55]})


def _furniture(fk):
    sx, sy, sz = catalog.STAGE
    return _scene("furniture", fk, "placed", f"furniture: {fk}", [
        f"command setblock {sx} {sy + 1} {sz} nirncraft:sky_furniture{{Kind:\"{fk}\"}}", "wait 10"],
        extra={"frameBox": [sx - 1, sy + 1, sz - 1, sx + 1, sy + 2, sz + 1, 30, 0.6]})


def _building(culture, use, wealth):
    bx, by, bz = BUILD_AT
    sid = f"{culture}-{use}-{wealth}"
    report = "nirncraft-qa/building.json"
    out = []
    views = [("exterior", f"frame-file {report} 20 0.8"), ("exterior-side", f"frame-file {report} 110 0.8"),
             ("inside", f"inside-file {report}")]
    if wealth == "common":  # night only where it shows something: lit windows (most subjects are noon only)
        views.append(("exterior-night", f"frame-file {report} 20 0.8"))
    for view, verb in views:
        s = {"id": f"{view}-building-{sid}".replace("_", "-"), "title": f"{use} ({culture}, {wealth}): {view}",
             "_file": "(generated subjects)", "tier": "live", "kind": "visual",
             "tags": ["suite:subjects", "suite:buildings", f"subject:{sid}", "kind:building", f"view:{view}",
                      f"culture:{culture}", f"use:{use}", f"wealth:{wealth}"],
             "pose": {"visit": [bx, bz + 60, 40, 180, 20]}, "time": 18000 if view == "exterior-night" else 6000,
             "weather": "clear", "variants": ["shaders"],
             "setup": [f"command nirncraft-qa building {culture} {use} {wealth} {bx} {by} {bz}", "wait 10"],
             "camera_verb": verb, "role": ROLE[view], "checks": {"maxBlackTiles": 0},
             "source": "generated from the game's subject index (subjects.py)"}
        out.append(s)
    return out


def weather_audit(index):
    """Every weather a region's climate can roll, seen from beside the region's capital (or first place): the
    weather audit. Pinned at noon with a front over the place so the weather is really there when captured."""
    out = []
    for w in index.get("weather", []):
        if "x" not in w:
            continue
        rid = w["region"].replace("/", "-").replace("_", "-")
        for kind in w.get("kinds", []):
            if kind not in catalog.WEATHERS:
                continue
            out.append({"id": f"weather-{rid}-{kind.replace('_', '-')}",
                        "title": f"{w['region']}: {kind} ({w.get('profile')} climate) over {w.get('place')}",
                        "_file": "(generated subjects)", "tier": "live", "kind": "visual", "variants": ["shaders"],
                        "tags": ["suite:weather-audit", f"region:{w['region']}", f"weather:{kind}",
                                 f"climate:{w.get('profile')}", f"place:{w.get('place')}"],
                        "pose": {"visit": [w["x"], w["z"] + 140, 50, 180, 6]}, "time": 6000, "weather": kind,
                        "front": [w["x"], w["z"], 2600, kind], "checks": {"maxBlackTiles": 0},
                        "source": "the game's weather profiles (subject index)"})
    return out


ROUTES = catalog.ROOT / ".work/harness/route-problems.json"  # written by check-roadplan (roadplan_lint --problems)


def route_audit():
    """The road plan's problem spots and a spread sample of its bridges, tunnels, switchbacks, stairs, fords,
    culverts, cuts and fills, each seen from above and to the side (the route audit)."""
    try:
        data = json.loads(ROUTES.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    out = []
    for n, spot in enumerate(data.get("problems", []) + data.get("samples", [])):
        x, z, h = spot["x"], spot["z"], spot.get("h", 80)
        eye = (x + 22, h + 16, z + 22)
        kind = spot["kind"]
        out.append({"id": f"route-{kind}-{n}", "title": f"road {kind} ({spot.get('class')}) at {int(x)},{int(z)}",
                    "_file": "(generated subjects)", "tier": "live", "kind": "visual", "variants": ["shaders"],
                    "tags": ["suite:route-audit", f"route:{kind}", f"road:{spot.get('class')}", "feature:road"]
                            + (["route:problem"] if spot in data.get("problems", []) else []),
                    "pose": {"camera": [round(eye[0], 1), round(eye[1] - 1.62, 1), round(eye[2], 1),
                                        round(x, 1), round(h, 1), round(z, 1)]},
                    "time": 6000, "weather": "clear", "checks": {"maxBlackTiles": 0},
                    "source": "road plan (tools/qa/roadplan_lint.py --problems)", "spot": spot})
    return out


SETTLEMENTS = ("capital", "city", "town", "village", "fort")
DUNGEONS = ("dungeon_fixed", "dungeon")


def dungeon_cutaways():
    """Every dungeon at a glance from its in-memory paint (the world is not touched): an iso cutaway of its floors
    from the inside, and a top-down plan by height (/nirncraft-dungeon-qa cutaway)."""
    out = []
    for pid, pl in catalog.places().items():
        if pl.get("type") not in DUNGEONS:
            continue
        out.append({"id": f"cutaway-{pid}".replace("_", "-"), "title": f"{pid}: dungeon cutaway (floors, levels)",
                    "_file": "(generated subjects)", "tier": "live", "kind": "measure", "variants": ["measure"],
                    "tags": ["suite:dungeon-cutaways", f"place:{pid}", f"subject:{pid}", "view:cutaway", "feature:dungeon"]
                            + ([f"region:{pl['region']}"] if pl.get("region") else []),
                    "pose": {}, "settle": False, "role": "front",
                    "measure_command": f"command nirncraft-dungeon-qa cutaway {pid}",
                    "measure_file": f"nirncraft-qa/dungeon_{pid}.json",
                    "image": f"nirncraft-qa/dungeon_{pid}_cutaway.png",
                    "extra_images": {"plan": f"nirncraft-qa/dungeon_{pid}_plan.png"}, "autocrop": True,
                    "measure_sum": {"pieces": "pieces", "floors": "floors", "levels": "levels"},
                    "keep": ["pieces", "floors", "levels", "width", "depth"], "timeout": 240,
                    "checks": {}, "source": "DungeonQa cutaway (DungeonCutaway)"})
    return out


def town_plans():
    """Every settlement's plan as the game computes it (streets, plots, buildings; /skysettle plan, nothing built):
    the town layout view, with its built / developed ratios as numbers."""
    out = []
    for pid, pl in catalog.places().items():
        if pl.get("type") not in SETTLEMENTS:
            continue
        out.append({"id": f"plan-{pid}".replace("_", "-"), "title": f"{pid}: town plan (streets, plots, buildings)",
                    "_file": "(generated subjects)", "tier": "live", "kind": "measure", "variants": ["measure"],
                    "tags": ["suite:town-plans", f"place:{pid}", f"subject:{pid}", "view:plan", f"type:{pl['type']}"]
                            + ([f"region:{pl['region']}"] if pl.get("region") else []),
                    "pose": {}, "settle": False, "role": "front",
                    "measure_command": f"command skysettle plan {pid}",
                    "measure_file": f"nirncraft-previews/plan_{pid}.json", "image": f"nirncraft-previews/plan_{pid}.png",
                    "measure_sum": {"radius": "radius", "built": "built_ratio", "developed": "developed_ratio"},
                    "keep": ["gatesWorld", "gates", "radius", "culture", "inns"],  # the town views need the gates
                    "checks": {}, "source": "SettlementDevCommands plan (the game's own planner)"})
    return out


def scenes():
    """Every subject scene the game's index asks for ([] until subject-index has run)."""
    index = catalog.subject_index()
    import calibrate
    out = route_audit() + town_plans() + dungeon_cutaways() + [calibrate.scene()]
    if not index:
        return out
    for rec in index.get("items", []):
        out += recipes(rec.get("kind", "item"), rec, index)
    for rec in index.get("blocks", []):
        if rec.get("kind") != "furniture":
            out += recipes(rec.get("kind", "block"), rec, index)
    for fk in index.get("furnitureKinds", []):
        out.append(_furniture(fk))
    for race in index.get("npcRaces", []):
        out += recipes("race", {"id": race}, index)
    for role in index.get("roles", []):
        out += recipes("role", {"id": role}, index)
    for spell in index.get("spells", []):
        out += recipes("spell", spell, index)
    b = index.get("buildings", {})
    for culture in b.get("cultures", []):
        for use in b.get("uses", []):
            for wealth in b.get("wealth", []):
                out += _building(culture, use, wealth)
    out += weather_audit(index)
    seen, unique = set(), []
    for s in out:
        if s["id"] not in seen:
            seen.add(s["id"])
            unique.append(s)
    return unique
