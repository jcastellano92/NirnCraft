"""Scenes for every generated feature type, from the cached feature index (features.py; `harness.py features` makes
it, nothing here runs Java): per biome spot a ground view (a few blocks up, looking along the biome) and an aerial one,
per natural landmark / formation spot an approach and an aerial view, per site spot (minor sites, stashes, scene-plan
kinds and anchors) an approach view. All visit poses (surface-relative) made for the index's seed.

    suite:features, suite:biomes | suite:natural-landmarks | suite:sites, biome:<id>, landmark:<kind>, site:<kind>,
    region:<id>, shot:ground|aerial|approach, feature:flora / foliage / water / snow / ash / sand where they fit

No fidelity ladders here: ladders need a named place (catalog.ladder_rungs), and these spots are not places.
"""
import json
import math
import os
import re
from functools import lru_cache
from pathlib import Path

import features

ROOT = Path(__file__).resolve().parents[2]
BIOMES = ROOT / "mod/src/main/resources/data/nirncraft/worldgen/biome"
SEED = int(os.environ.get("NIRNCRAFT_HARNESS_SEED", "1234"))  # the preview world's seed (harness.DEFAULT_SEED)

VEGETATED = re.compile(r"forest|grove|wood|jungle|rainforest|swamp|marsh|mire|fen|mangrove|meadow|vineyard|mushroom|"
                       r"taiga|reed|weald|delta|farmland|fields|heartlands|valley|ascadian|moors|glenumbra|bangkorai|"
                       r"rivenspire|stormhaven|grazelands|savanna|sugar|reapers|nibenay|shadowfen|murkmire|hist|"
                       r"bitter_coast|iliac|pellitine|tigonus|whiterun_plains|falkreath|rift")
WATER = re.compile(r"ocean|sea|river|lake|coast|shore|beach|reef|coral|delta|mangrove|inner_sea|sunken")
SNOW = re.compile(r"snow|frozen|glacier|ice|frost|pale|winterhold|kamal|atmora|throat|alpine|jerall|wrothgarian|tundra")
ASH = re.compile(r"ash|red_mountain|molag|stonefalls|vvardenfell|volcanic|vent|tar|lava")
SAND = re.compile(r"desert|dune|beach|alikr|sand|badlands|craglorn|savanna|butte|mesa|dry_lake|glass|hoodoos")
FLORA_LM = re.compile(r"tree|grove|log|mushroom|fungal|hollow|garden|herb|hist|sapling|beehive|reagent|moon_sugar|"
                      r"flowers|field_plot|charms")


@lru_cache(maxsize=None)
def _biome(bid):
    """(vegetated, cold) from the biome's data: tree / cover features in the vegetation step, temperature."""
    f = BIOMES / f"{bid}.json"
    try:
        d = json.loads(f.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False, False
    veg = [x for step in d.get("features", []) for x in (step if isinstance(step, list) else []) if "flora/" in x
           or "trees" in x]
    trees = [x for x in veg if "trees" in x and not any(w in x for w in ("dead", "scrub", "sparse", "few", "lone"))]
    return len(trees) >= 3, float(d.get("temperature", 0.5)) <= 0.15  # a forest: several living tree layers


def _slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def _tags(spot, extra=()):
    tags = ["suite:features"] + list(extra)
    if spot.get("region"):
        tags.append(f"region:{spot['region']}")
    if spot.get("water") or spot.get("aquatic"):
        tags.append("feature:water")
    return tags


def _scene(sid, title, tags, visit, seed):
    return {"id": sid, "title": title, "_file": "(generated features)", "tags": sorted(dict.fromkeys(tags)),
            "pose": {"visit": visit}, "time": 6000, "weather": "clear", "variants": ["shaders"], "checks": {},
            "seed": seed, "source": "generated: feature_scenes from the feature index (tools/harness/features.py)"}


PITCH = [4, 0, 10, -3, 7, 2]       # ground views: slightly down, level, down, up, ...
YAW_OFF = [0, 25, -25, 50, -50, 0]
LIFT = [3, 4, 5, 6, 4, 3]


def _biome_scenes(idx, seed):
    out = []
    for bid, spots in sorted(idx.get("biomes", {}).items()):
        name = bid.split(":")[-1]
        vegetated, cold = _biome(name)
        kind = ["suite:biomes", f"biome:{bid}"]
        if vegetated or VEGETATED.search(name):
            kind += ["feature:flora", "feature:foliage"]
        if WATER.search(name):
            kind.append("feature:water")
        if cold or SNOW.search(name):
            kind.append("feature:snow")
        if ASH.search(name):
            kind.append("feature:ash")
        if SAND.search(name):
            kind.append("feature:sand")
        for n, sp in enumerate(spots, 1):
            yaw = float(sp.get("yaw", 0.0))
            k = (n - 1) % len(PITCH)
            g = [sp["x"], sp["z"], LIFT[k], round(yaw + YAW_OFF[k], 1), PITCH[k]]
            slug = f"biome-{_slug(name)}-{n}"
            tags = _tags(sp, kind)
            out.append(_scene(f"{slug}-ground", f"{name} #{n} from the ground ({sp.get('region', '?')})",
                              tags + ["shot:ground"], g, seed))
            # aerial: 60-90 up, 100 m back along the view, looking down across the spot
            h = 60 + (n * 7) % 31
            ry = math.radians(yaw)
            dx, dz = math.sin(ry), -math.cos(ry)   # Minecraft: yaw 0 looks +z, so "back" is (sin yaw, -cos yaw)
            cx, cz = round(sp["x"] + dx * 100), round(sp["z"] + dz * 100)
            pitch = round(math.degrees(math.atan2(h, 100 + 60)), 1)
            out.append(_scene(f"{slug}-aerial", f"{name} #{n} from {h} m up ({sp.get('region', '?')})",
                              tags + ["shot:aerial"], [cx, cz, h, round(yaw, 1), pitch], seed))
    return out


def _feature_kind_tags(kind):
    t = []
    if FLORA_LM.search(kind):
        t += ["feature:flora", "feature:foliage"]
    if SNOW.search(kind):
        t.append("feature:snow")
    if ASH.search(kind):
        t.append("feature:ash")
    if SAND.search(kind):
        t.append("feature:sand")
    return t


def _landmark_scenes(idx, seed):
    out = []
    for kind, spots in sorted(idx.get("landmarks", {}).items()):
        base = ["suite:natural-landmarks", f"landmark:{kind}"] + _feature_kind_tags(kind) \
            + (["feature:formation"] if kind.startswith("formation/") else [])
        for n, sp in enumerate(spots, 1):
            views = sp.get("views") or {}
            tags = _tags(sp, base + ([f"tier:{sp['tier'].lower()}"] if sp.get("tier") else []))
            for shot in ("approach", "aerial"):
                if views.get(shot):
                    out.append(_scene(f"nat-{_slug(kind)}-{n}-{shot}", f"{kind} #{n} ({shot}, {sp.get('region', '?')})",
                                      tags + [f"shot:{shot}"], views[shot], seed))
    return out


def _site_scenes(idx, seed):
    out = []
    for kind, spots in sorted(idx.get("sites", {}).items()):
        base = ["suite:sites", f"site:{kind}"] + _feature_kind_tags(kind)
        for n, sp in enumerate(spots, 1):
            v = (sp.get("views") or {}).get("approach")
            if not v:
                continue
            extra = [f"anchor:{sp['anchor']}"] if sp.get("anchor") else []
            out.append(_scene(f"site-{_slug(kind)}-{n}", f"{kind} #{n} ({sp.get('region', '?')})",
                              _tags(sp, base + extra + ["shot:approach"]), v, seed))
    return out


def feature_scenes(seed=None):
    """Scenes from the newest cached feature index of the seed ([] when there is none: `harness.py features`)."""
    seed = SEED if seed is None else seed
    idx = features.latest(seed)
    if not idx:
        return []
    s = int(idx.get("_seed", seed))
    return _biome_scenes(idx, s) + _landmark_scenes(idx, s) + _site_scenes(idx, s)
