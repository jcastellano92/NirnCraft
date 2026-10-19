"""Effects as subjects: place an effect, scale it, picture it, automatically.

Each entry of EFFECTS says where the effect is staged (the dark studio, the open sky stage, or out in a region whose
climate has it), how to start it (live verbs; {s} is the scale value) and the scale steps (one scene each, so a
sandwall is seen at 600, 1500 and 3000 blocks across, a fire as 1, 9 and 25 burning blocks). Adding an effect is one
entry. Fronts are placed over a place in a region whose climate rolls that weather (the game's subject index), so a
sandwall appears over a desert, not over Whiterun.
"""
import catalog

ELEMENTS = ("fire", "frost", "shock", "holy", "arcane", "conjure", "mind", "soul", "nature")
FIRES = ("rubble_fire", "big_fire", "smouldering_embers")


def _fire_square(block, n):
    """n burning blocks on the stage: 1, 9 (3x3) or 25 (5x5)."""
    sx, sy, sz = catalog.STAGE
    r = {1: 0, 9: 1, 25: 2}.get(n, 0)
    return [f"try fill {sx - r} {sy} {sz - r} {sx + r} {sy} {sz + r} minecraft:netherrack",
            f"command fill {sx - r} {sy + 1} {sz - r} {sx + r} {sy + 1} {sz + r} nirncraft:{block}"]


EFFECTS = {}
for el in ELEMENTS:  # magic beams in the dark studio (they read best against black)
    EFFECTS[f"beam-{el}"] = {"stage": "studio", "setup": lambda s, el=el: [f"fx {el}"], "scales": [1],
                             "frames": [4, 5], "tags": ["effect:magic", f"element:{el}"]}
EFFECTS["air-push"] = {"stage": "studio", "setup": lambda s: ["fx push"], "scales": [1], "frames": [4, 5],
                       "tags": ["effect:air"]}
for fire in FIRES:
    EFFECTS[f"fire-{fire}"] = {"stage": "stage", "setup": lambda s, fire=fire: _fire_square(fire, s) + ["wait 40"],
                               "scales": [1, 9, 25], "frames": [3, 15], "box": True, "tags": ["effect:fire"]}
for mode in ("cg", "ic", "c2c"):  # lightning: cloud-to-ground, intra-cloud, cloud-to-cloud, under a storm
    EFFECTS[f"lightning-{mode}"] = {"stage": "front", "weather": "storm", "setup": lambda s, mode=mode: [f"flash {mode}"],
                                    "scales": [1500], "frames": [3, 6], "tags": ["effect:lightning"]}
for kind in ("sandstorm", "ash_storm", "blight", "blizzard", "storm", "fog", "gale"):  # walls of weather
    EFFECTS[f"front-{kind}"] = {"stage": "front", "weather": kind, "setup": lambda s: [], "scales": [600, 1500, 3000],
                                "tags": ["effect:weather-front", f"weather:{kind}"]}


def _where(weather):
    """A place in a region whose climate rolls `weather` (the subject index), else beside Whiterun."""
    for w in catalog.subject_index().get("weather", []):
        if weather in w.get("kinds", []) and "x" in w:
            return w["x"], w["z"], w["region"]
    return 62, -68, "skyrim/whiterun"


def scenes():
    out = []
    sx, sy, sz = catalog.STAGE
    for eid, e in EFFECTS.items():
        for s in e["scales"]:
            sid = f"effect-{eid}" + (f"-{s}" if len(e["scales"]) > 1 else "")
            sc = {"id": sid.replace("_", "-"), "title": f"effect {eid}" + (f" at scale {s}" if len(e["scales"]) > 1 else ""),
                  "_file": "(generated effects)", "tier": "live", "kind": "visual", "variants": ["shaders"],
                  "tags": ["suite:effects", f"effect:{eid}"] + e.get("tags", []), "checks": {"maxBlackTiles": 0},
                  "setup": e["setup"](s), "cleanup": [], "role": "front", "source": "tools/harness/effects.py"}
            if e.get("frames"):
                sc["frames"] = e["frames"]
            if e["stage"] == "studio":
                sc["pose"], sc["time"] = {"studio": True}, 18000
            elif e["stage"] == "stage":
                sc["pose"], sc["time"] = {"stage": [sx, sy, sz, 6]}, 18000  # fire reads at night
                if e.get("box"):
                    r = {1: 0, 9: 1, 25: 2}.get(s, 0)
                    sc["frameBox"] = [sx - r, sy + 1, sz - r, sx + r, sy + 3, sz + r, 30, 0.6]
                sc["cleanup"] = [f"try fill {sx - 3} {sy + 1} {sz - 3} {sx + 3} {sy + 4} {sz + 3} minecraft:air"]
            else:  # a front over a place where that weather belongs, seen from its edge
                x, z, region = _where(e["weather"])
                sc["pose"] = {"visit": [x, z + s // 2 + 300, 60, 180, 4]}
                sc["time"], sc["weather"] = 6000, e["weather"]
                sc["front"] = [x, z, s, e["weather"]]
                sc["tags"].append(f"region:{region}")
            out.append(sc)
    return out
