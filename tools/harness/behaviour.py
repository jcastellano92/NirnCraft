"""Behaviour scenarios: does a wolf go for a sheep, does a guard fight a bandit, does an NPC use a door?

A scenario is a JSON file in tools/harness/scenarios/ (`harness.py scenario new NAME` writes a template):

    {"id": "wolf-vs-sheep", "title": "...", "tags": ["behaviour:predation"],
     "arena": {"size": 21, "floor": "minecraft:grass_block", "walls": "minecraft:glass", "height": 3},
     "props": [{"block": "minecraft:oak_door[facing=south,half=lower]", "at": [0, 1, 4], "watch": true}],
     "actors": [{"name": "wolf", "entity": "nirncraft:wolf", "at": [-5, 1, 0], "nbt": "{}"},
                {"name": "sheep", "entity": "minecraft:sheep", "at": [5, 1, 0]}],
     "difficulty": "easy", "duration": 600, "every": 10, "trials": 5, "frames": 0,
     "end_when": "dead:sheep",
     "expect": [{"what": "targets", "actor": "wolf", "target": "sheep", "within": 400, "rate": 0.6}]}

Positions are relative to the arena centre. Every trial rebuilds the arena, spawns the actors (tagged
qa_actor_<name>), records them every `every` ticks (/nirncraft-qa record), and the expectations are judged on the
recording; a scenario passes when each expectation holds in at least `rate` of the trials (default all).

Expectations (`what`): targets / never_targets (actor, target, within), health_drops (actor, by), alive_at_end
(actor, alive true/false), reaches (actor, to [x,y,z] or actor, distance, within), block_changes (at, contains, within).
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ROOT / "tools/harness/scenarios"
ARENA = (4096 - 64, 520, 4096)  # beside the stage, in the sky: nothing else wanders in

TEMPLATE = {
    "id": "NAME", "title": "What this checks, in one line", "tags": ["behaviour:TOPIC"],
    "arena": {"size": 21, "floor": "minecraft:grass_block", "walls": "minecraft:glass", "height": 3},
    "props": [],
    "actors": [{"name": "a", "entity": "nirncraft:wolf", "at": [-5, 1, 0], "nbt": "{}"},
               {"name": "b", "entity": "minecraft:sheep", "at": [5, 1, 0]}],
    "difficulty": "easy", "duration": 600, "every": 10, "trials": 5, "frames": 0,
    "expect": [{"what": "targets", "actor": "a", "target": "b", "within": 400, "rate": 0.6}],
}


def load():
    out = []
    for f in sorted(SCENARIOS.glob("*.json")):
        try:
            s = json.loads(f.read_text(encoding="utf-8"))
        except ValueError as e:
            out.append({"id": f"scenario-{f.stem}", "_broken": str(e)})
            continue
        out.append(s)
    return out


def scenes():
    """Each scenario as a live behaviour scene for the catalog."""
    out = []
    for sc in load():
        if sc.get("_broken"):
            continue
        out.append({"id": f"behaviour-{sc['id']}", "title": sc.get("title", sc["id"]), "_file": "scenarios",
                    "tier": "live", "kind": "behaviour", "variants": ["measure"], "group": "live",
                    "tags": ["suite:behaviour"] + sc.get("tags", []), "pose": {"camera": list(overview())},
                    "scenario": sc, "checks": {}, "timeout": 120 + sc.get("duration", 600) // 20 * sc.get("trials", 5) * 2,
                    "source": f"tools/harness/scenarios/{sc['id']}.json"})
    return out


def overview():
    x, y, z = ARENA
    return (x, y + 14, z + 16, x, y + 1, z)


def trial_actions(sc, n):
    """The live verbs of one trial (arena, props, actors, recording, frames)."""
    x, y, z = ARENA
    a = sc.get("arena", {})
    half = a.get("size", 21) // 2
    h = a.get("height", 3)
    lines = [f"try kill @e[type=!player,x={x},y={y},z={z},distance=..{half + 8}]",
             f"try fill {x - half - 1} {y} {z - half - 1} {x + half + 1} {y + h + 4} {z + half + 1} minecraft:air",
             f"try fill {x - half} {y} {z - half} {x + half} {y} {z + half} {a.get('floor', 'minecraft:grass_block')}"]
    if a.get("walls"):
        lines.append(f"try fill {x - half - 1} {y + 1} {z - half - 1} {x + half + 1} {y + h} {z + half + 1} "
                     f"{a['walls']} hollow")
        lines.append(f"try fill {x - half} {y + 1} {z - half} {x + half} {y + h} {z + half} minecraft:air")
    lines.append(f"try difficulty {sc.get('difficulty', 'easy')}")
    for p in sc.get("props", []):
        px, py, pz = (x + p["at"][0], y + p["at"][1], z + p["at"][2])
        lines.append(f"try setblock {px} {py} {pz} {p['block']}")
    name = f"{sc['id']}-t{n}"
    for actor in sc.get("actors", []):
        ax, ay, az = actor["at"]
        nbt = actor.get("nbt", "{}").strip()
        tag = f'Tags:["qa_actor_{actor["name"]}"],PersistenceRequired:1b'
        nbt = "{" + tag + ("," + nbt[1:-1] if len(nbt) > 2 else "") + "}"
        lines.append(f"command summon {actor['entity']} {x + ax} {y + ay} {z + az} {nbt}")
    lines.append(f"command nirncraft-qa record start {name} {x} {y + 1} {z} {half + 4} {sc.get('every', 10)}")
    for p in sc.get("props", []):
        if p.get("watch"):
            lines.append(f"command nirncraft-qa record watch {x + p['at'][0]} {y + p['at'][1]} {z + p['at'][2]}")
    # scripted actions during the trial (e.g. the test player kills an actor: a player kill leaves a body)
    acts = [a.replace("{actor:", "@e[limit=1,tag=qa_actor_").replace("}", "]") if "{actor:" in a else a
            for a in sc.get("act", [])]
    dur, frames = int(sc.get("duration", 600)), int(sc.get("frames", 0))
    end = sc.get("end_when", "")
    photos = n == 0 and sc.get("photos", "first") != "none" or sc.get("photos") == "all"
    half = dur // 2

    def wait(ticks):  # until decided (a kill: on after a second) or the ticks run out
        return f"wait-dead {end[5:]} {ticks}" if end.startswith("dead:") else f"wait {ticks}"
    if photos:  # start, middle (or just after the deciding moment), end: one strip with the result
        lines += ["wait 10", f"capture b-{name}-start"] + acts + [wait(half), f"capture b-{name}-middle",
                                                                  wait(dur - half), f"capture b-{name}-end"]
    elif frames:
        lines += acts
        for f in range(frames):
            lines += [f"wait {dur // frames}", f"capture b-{name}-f{f}"]
    else:
        lines += acts + [wait(dur)]
    lines += ["command nirncraft-qa record stop",
              f"try kill @e[type=!player,x={x},y={y},z={z},distance=..{half + 8}]", "try difficulty peaceful"]
    return name, lines


def _actors(sample):
    return {e["actor"]: e for e in sample.get("entities", []) if e.get("actor")}


def judge(expect, rec):
    """Did one expectation hold in one trial's recording? (bool, the tick it happened or a short reason)"""
    samples = rec.get("samples", [])
    within = expect.get("within", 10 ** 9)
    what = expect["what"]
    if what in ("targets", "never_targets"):
        hit = next((s["tick"] for s in samples if s["tick"] <= within and
                    _actors(s).get(expect["actor"], {}).get("target") == expect["target"]), None)
        return (hit is not None, hit) if what == "targets" else (hit is None, hit)
    if what == "health_drops":
        hs = [_actors(s).get(expect["actor"], {}).get("health") for s in samples]
        hs = [h for h in hs if h is not None]
        drop = (hs[0] - min(hs)) if hs else 0
        return drop >= expect.get("by", 1), round(drop, 1)
    if what == "alive_at_end":
        last = _actors(samples[-1]).get(expect["actor"]) if samples else None
        alive = bool(last and last.get("alive"))
        return alive == expect.get("alive", True), alive
    if what == "reaches":
        for s in samples:
            if s["tick"] > within:
                break
            acts = _actors(s)
            me = acts.get(expect["actor"])
            to = acts.get(expect["to"]) if isinstance(expect.get("to"), str) else None
            goal = (to["x"], to["y"], to["z"]) if to else tuple(expect.get("to", (0, 0, 0)))
            if me and math.dist((me["x"], me["y"], me["z"]), goal) <= expect.get("distance", 1.5):
                return True, s["tick"]
        return False, None
    if what == "appears":  # an entity of a type shows up (a corpse after a kill), within the ticks
        hit = next((s["tick"] for s in samples if s["tick"] <= within and
                    any(e.get("type") == expect["type"] for e in s.get("entities", []))), None)
        return hit is not None, hit
    if what == "rests":  # the first entity of a type stops moving (a body comes to rest)
        pos = [next(((e["x"], e["y"], e["z"]) for e in s.get("entities", []) if e.get("type") == expect["type"]), None)
               for s in samples]
        pos = [q for q in pos if q]
        if len(pos) < 3:
            return False, "not seen long enough"
        moved = math.dist(pos[-1], pos[-3])
        return moved < expect.get("tolerance", 0.05), round(moved, 3)
    if what == "block_changes":
        at = expect["at"]
        for s in samples:
            if s["tick"] > within:
                break
            for b in s.get("blocks", []):
                if [b["x"] - ARENA[0], b["y"] - ARENA[1], b["z"] - ARENA[2]] == at and expect["contains"] in b["state"]:
                    return True, s["tick"]
        return False, None
    return False, f"unknown expectation {what}"


def describe(e):
    w = e["what"]
    tail = f" within {e['within']} ticks" if "within" in e else ""
    return {"targets": f"{e.get('actor')} targets {e.get('target')}{tail}",
            "never_targets": f"{e.get('actor')} never targets {e.get('target')}{tail}",
            "health_drops": f"{e.get('actor')} loses >= {e.get('by', 1)} health",
            "alive_at_end": f"{e.get('actor')} {'alive' if e.get('alive', True) else 'dead'} at the end",
            "reaches": f"{e.get('actor')} reaches {e.get('to')}{tail}",
            "block_changes": f"block {e.get('at')} becomes {e.get('contains')}{tail}",
            "appears": f"a {e.get('type')} appears{tail}",
            "rests": f"the {e.get('type')} comes to rest"}.get(w, w)
