"""Validates the harness catalog exactly as the harness loads it (scene files + generated sets): ids unique and
kebab-case, one pose kind, known places / weathers / groups / live verbs, check scenes runnable, lab scenes complete,
and every nirncraft block / entity a scene's commands name exists (an unknown id does not parse in game).

    py -3 tools/harness/validate_scenes.py        exit 1 with one line per problem
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalog  # noqa: E402

# the pack's options (a #define, commented or not, or a const in lib/settings.glsl): what pack_options may name
def _pack_options():
    import re
    text = (Path(__file__).resolve().parents[2] / "shaderpack/InfiniteATLAS/shaders/lib/settings.glsl").read_text(encoding="utf-8")
    return {a or b for a, b in re.findall(r"^\s*(?://)?#define\s+(\w+)|^\s*const\s+\w+\s+(\w+)\s*=", text, re.M)}


PACK_OPTIONS = _pack_options()

VERBS = {"wait-dead", "door-file", "frame", "frame-box", "frame-file", "inside-file", "showcase", "reload", "resources", "shaders", "rebuild", "texture-probe", "flash", "cast", "use", "fx", "wait", "settle",
         "measure", "command", "try", "camera", "camera-plan", "camera-travel", "visit", "front", "ui", "capture-ui",
         "capture", "identity", "transport", "stop", "screen", "click", "key", "expect-screen", "perspective",
         "renderdistance", "hud", "settle-visual", "message", "weather-off", "atlaslab", "atlas-edits-why", "fog-off", "truth-quiet"}
ID = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
TAG = re.compile(r"^[a-z]+:[A-Za-z0-9_/:.\-]+$")
SUMMON = re.compile(r"\bsummon\s+nirncraft:([a-z0-9_]+)")
PLACE = re.compile(r"\b(?:setblock|fill)\b")
ID_ANY = re.compile(r"nirncraft:([a-z0-9_]+)")
ROOT = Path(__file__).resolve().parents[2]
BLOCKS = {p.stem for p in (ROOT / "mod/src/main/resources/assets/nirncraft/blockstates").glob("*.json")}
ENTITIES = set(catalog._lang_ids("entity"))
ITEMS = set(catalog._lang_ids("item")) | BLOCKS


def main():
    errors = []
    try:
        scenes = catalog.load()
    except ValueError as e:  # duplicate ids
        print(f"catalog: {e}")
        return 1
    places, groups = catalog.places(), catalog.groups()
    ids = {s["id"] for s in scenes}
    for s in scenes:
        w = f"{s['_file']}/{s['id']}"
        if not ID.match(s["id"]):
            errors.append(f"{w}: id is not kebab-case")
        for t in s["tags"]:
            if not TAG.match(t):
                errors.append(f"{w}: bad tag {t!r}")
        if s["tier"] == "check":
            if not s.get("run"):
                errors.append(f"{w}: check scenes need a run command")
            continue
        if s["tier"] == "lab":
            if not s.get("lab") or s["lab"].get("tool") not in ("atlas_render", "pack_lab", "atlas_lab", "atlas_truth"):
                errors.append(f"{w}: lab scenes need lab.tool atlas_render|pack_lab|atlas_lab|atlas_truth")
            if s.get("settle") not in (None, "static"):
                errors.append(f"{w}: settle is \"static\" or absent")
            cf = s.get("camera_from")
            if cf and (cf.get("scene") not in ids or cf.get("scene") not in s.get("after", [])):
                errors.append(f"{w}: camera_from {cf.get('scene')} must be a scene this one comes after")
            for d in s.get("after", []):
                if d not in ids:
                    errors.append(f"{w}: comes after {d}, which is not a scene")
            continue
        if s["group"] not in groups:
            errors.append(f"{w}: unknown group {s['group']}")
        pose = s.get("pose") or {}
        kinds = [k for k in catalog.POSES if k in pose]
        if not pose and s.get("kind") == "measure" and s.get("settle") is False:
            continue  # pure data (e.g. subject-index): no camera at all
        if len(kinds) != 1:
            errors.append(f"{w}: needs exactly one pose kind, has {kinds}")
            continue
        k = kinds[0]
        if k == "visit" and len(pose["visit"]) != 5:
            errors.append(f"{w}: visit is [x, z, height, yaw, pitch]")
        if k == "camera" and len(pose["camera"]) != 6:
            errors.append(f"{w}: camera is [x, y, z, lookX, lookY, lookZ]")
        if k in ("ladder", "sweep") and not pose[k].get("stage") and pose[k].get("place") not in places:
            errors.append(f"{w}: unknown place {pose[k].get('place')}")
        if k in ("place", "dungeon") and pose[k] not in places:
            errors.append(f"{w}: unknown place {pose[k]}")
        if k == "place" and pose.get("shot") not in (None, "aerial", "approach"):
            errors.append(f"{w}: shot is aerial|approach")
        if s.get("weather") and s["weather"] not in catalog.WEATHERS:
            errors.append(f"{w}: unknown weather {s['weather']}")
        if s.get("weather") == "off" and any(tag.startswith(catalog.WEATHER_TAGS) for tag in s.get("tags", [])):
            errors.append(f"{w}: tagged as a weather scene but has weather off")
        for opt in (s.get("pack_options") or {}):
            if opt not in PACK_OPTIONS:
                errors.append(f"{w}: pack option {opt} is not an option of the pack (lib/settings.glsl)")
        if s.get("front") and (len(s["front"]) < 4 or s["front"][3] not in catalog.WEATHERS):
            errors.append(f"{w}: front is [x, z, radius, weather, vx?, vz?]")
        for v in s.get("setup", []) + s.get("cleanup", []):
            verb = v.split()[0] if v.split() else ""
            if verb not in VERBS:
                errors.append(f"{w}: unknown live verb {verb!r} in {v!r}")
            ent = SUMMON.findall(v)
            for rid in ent:
                if rid not in ENTITIES:
                    errors.append(f"{w}: summon names entity nirncraft:{rid}, which does not exist")
            if PLACE.search(v) or ent:  # the other ids: blocks for setblock / fill, items (equipment) for summon
                for rid in ID_ANY.findall(v):
                    if rid in ent:
                        continue
                    if rid not in (BLOCKS if PLACE.search(v) else ITEMS):
                        errors.append(f"{w}: names nirncraft:{rid}, which does not exist")
        if s.get("frames") and (len(s["frames"]) != 2 or s["frames"][0] < 2):
            errors.append(f"{w}: frames is [count >= 2, ticks between]")
    for e in errors[:200]:
        print(e)
    print(f"{len(scenes)} scenes, {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
