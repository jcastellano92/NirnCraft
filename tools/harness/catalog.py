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

import feature_scenes

ROOT = Path(__file__).resolve().parents[2]
SCENES = ROOT / "tools/harness/scenes"
PLACES = ROOT / "mod/src/main/resources/nirncraft/tamriel/places"
LANG = ROOT / "mod/src/main/lang/nirncraft"
NOT_SCENES = {"groups.json", "legacy-seed.json"}
POSES = ("visit", "camera", "place", "dungeon", "realm", "spawn", "stage", "studio", "ladder", "sweep")
WEATHERS = {"clear", "cloudy", "overcast", "fog", "drizzle", "rain", "storm", "snow", "blizzard", "ashfall",
            "ash_storm", "dust", "sandstorm", "blight", "gale"}


# open ground the harness builds on (entity and material-pad seam tests); planned like any place
HARNESS_SPOTS = {
    "harness_actors_plains": {"id": "harness_actors_plains", "x": 420, "z": 120, "radius": 30, "type": "landmark",
                              "region": "skyrim/whiterun"},
    "harness_pad_plains": {"id": "harness_pad_plains", "x": 470, "z": -60, "radius": 40, "type": "landmark",
                           "region": "skyrim/whiterun"},
}


@lru_cache(maxsize=1)
def places():
    out = dict(HARNESS_SPOTS)
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
# the stage, so a run never moves the camera between shots. y 520: above the cloud deck (base ~232, top ~320 in clear
# weather, see WeatherPhysics) and below the build limit (640); at 300 the near rungs sat inside the clouds.
STAGE = (4096, 520, 4096)
# the dark studio beside the stage: a black room lit by invisible light blocks, for spells and effects (they read
# best against black); the camera stands at the south end looking north across it
STUDIO = (4096 + 48, 520, 4096)
BIG = {"giant": 12, "mammoth": 12, "dragon": 26, "alduin": 30, "dwarven_centurion": 12, "netch": 10, "troll": 7,
       "frost_troll": 7, "silt_strider": 30, "wispmother": 7, "dragon_priest": 6, "werewolf": 6, "horker": 6,
       "sabre_cat": 6, "bear": 6, "mudcrab": 4, "longship": 18, "cog": 14, "rowboat": 5}


SUBJECT_INDEX = ROOT / ".work/harness/subjects.json"  # the game's own answer (scene subject-index)


def subject_index():
    """The game's subject index ({} until `harness.py run subject-index` has run once)."""
    try:
        return json.loads(SUBJECT_INDEX.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
SIDE_KINDS = ("boss", "monster", "creature", "npc", "mount", "fish", "water")  # these get a side profile too


def _entity_categories():
    """id -> boss / monster / creature / water / npc / vehicle / misc. The game's index when it exists (harness.py run
    entity-index: /nirncraft-qa entities inspects every type), else a best-effort read of the Java registrations."""
    ents = subject_index().get("entities")
    if ents:
        return {e["id"]: e.get("kind", "misc") for e in ents}
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
    out = [{"id": "subject-index", "title": "Every testable subject the game defines, by kind, from its registries",
            "_file": "(generated gallery)", "kind": "measure", "tags": ["suite:gallery", "check:entities", "check:subjects"],
            "pose": {}, "settle": False, "variants": ["measure"],  # pure data: no camera, no terrain to wait for
            "setup": ["try difficulty easy"],  # monsters cannot even be created in peaceful
            "measure_command": "command nirncraft-qa subjects", "measure_file": "nirncraft-qa/subjects.json",
            "export": "subjects.json", "checks": {}, "source": "QaCommands subjects"}]
    cats = _entity_categories()
    for e in sorted(_lang_ids("entity")):
        cat = cats.get(e, "npc")
        summon = (f"command summon nirncraft:{e} {sx} {sy + 1} {sz} {{NoAI:1b,PersistenceRequired:1b,Silent:1b,"
                  f"Rotation:[180f,0f]}}")
        tags = [f"gallery:{cat}", f"gallery:entity:{e}", "feature:creature"]
        # framed by the game from the subject's real bounding box: three-quarter front, and a side profile for
        # monsters, creatures and NPCs (frame ANGLE FILL, see VisualCamera.frame)
        out.append(_stage_scene(f"gallery-{e.replace('_', '-')}", f"Gallery: {e}", tags,
                                ["try difficulty easy", summon, "wait 10"], BIG.get(e, 4.5),
                                extra={"frame": [30, 0.7], "subject": f"nirncraft:{e}"}))
        if cat in SIDE_KINDS:
            out.append(_stage_scene(f"gallery-{e.replace('_', '-')}-side", f"Gallery: {e} (side)",
                                    tags + ["gallery:side"], ["try difficulty easy", summon, "wait 10"],
                                    BIG.get(e, 4.5), extra={"frame": [90, 0.7], "subject": f"nirncraft:{e}"}))
        # animation: free to move inside an invisible pen; six frames a third of a second apart
        pen = f"try fill {sx - 6} {sy + 1} {sz - 6} {sx + 6} {sy + 5} {sz + 6} minecraft:barrier hollow"
        walk = summon.replace("NoAI:1b,", "")
        out.append(_stage_scene(f"anim-{e.replace('_', '-')}", f"Animation: {e}", tags + ["suite:animation",
                                "feature:animation"], [pen, "try difficulty easy", walk, "wait 20"],
                                BIG.get(e, 4.5) * 1.3, frames=[6, 7],
                                extra={"frame": [30, 0.55], "subject": f"nirncraft:{e}"}))
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
        # the same lineup at distance: textures, mipmaps and shimmer from 6 m to 128 m
        out.append({"id": f"blocks-ladder-{part // 9 + 1:02d}", "title": f"Blocks {part + 1}-{part + len(chunk)} from 6 m to 128 m",
                    "_file": "(generated gallery)", "tags": ["suite:gallery", "suite:distance", "gallery:blocks",
                    "feature:textures", "feature:materials"] + [f"gallery:block:{b}" for b in chunk],
                    "time": 6000, "weather": "clear", "variants": ["shaders", "vanilla"], "checks": {}, "timeout": 900,
                    "setup": place, "pose": {"ladder": {"stage": True, "distances": [6, 16, 48, 128]}},
                    "source": "generated from the mod's lang ids (tools/harness/catalog.py gallery)"})
    return out


# ---------------------------------------------------------------------------------------------------- distance
# The renderer at every scale: near chunks end at render distance x 16 - 16 (176 at the test client's 12 chunks) and
# Infinite ATLAS takes over; these scenes look at the same subjects across that hand-off and far beyond it.
HANDOFF = 176
LADDER = [64, 128, 168, 184, 256, 512, 1024, 2048, 4096]      # 168 / 184 straddle the hand-off ring
TIMES = {"day": 6000, "night": 18000, "dawn": 23300, "dusk": 12600}
DISTANCE_SUBJECTS = [  # (place, kind, times); night ladders of lit places also get close rungs (torches, windows)
    ("whiterun", "city", ("day", "night")), ("solitude", "city", ("day", "night")),
    ("windhelm", "city", ("day", "night")), ("markarth", "city", ("day", "night")),
    ("riften", "city", ("day", "night")), ("imperial_city", "city", ("day", "night")),
    ("vivec", "city", ("day", "night")), ("dragon_bridge", "river", ("day", "night")),
    ("ilinalta_fishery", "lake", ("day", "night")), ("whiterun_stables", "road", ("day", "night")),
    ("bleak_falls_barrow", "dungeon", ("day", "night")), ("riverwood", "forest", ("day", "night")),
    ("helgen", "effects", ("day", "night")), ("treva_cascade_falls", "waterfall", ("day", "night")),
    ("dawnstar", "coast", ("day", "night")), ("eldergleam_sanctuary", "landmark", ("day",)),
    ("falkreath", "forest", ("day", "night")), ("college_of_winterhold", "landmark", ("day", "night")),
    # long shadows from the peaks across the valleys at sunrise and sunset
    ("throat_of_the_world", "mountain", ("day", "night", "dawn", "dusk")),
    ("high_hrothgar", "mountain", ("day", "dawn", "dusk")),
    ("red_mountain", "volcano", ("day", "night", "dawn", "dusk")),
]
LIT = {"city", "river", "road", "lake", "dungeon", "forest", "effects", "coast", "landmark", "waterfall"}
SEAM_ACTORS = ["dragon", "giant", "mammoth", "troll", "horse"]  # far actors: model -> impostor across the ring
PAD = [  # player edits: patches of materials built on the ground (does Infinite ATLAS show what players build?)
    "minecraft:gold_block", "minecraft:iron_block", "minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:glass",
    "minecraft:snow_block", "minecraft:sand", "minecraft:red_concrete", "minecraft:blue_concrete",
    "minecraft:white_wool", "minecraft:magma_block", "minecraft:water"]
NEAR_RD = 12   # the test client's render distance (HANDOFF = 12 x 16 - 16)
MATERIAL_SAMPLES = [  # metal, precious metal, glass / ice, snow, polished stone, hot rock, PBR-mapped, plain references
    "minecraft:iron_block", "minecraft:gold_block", "minecraft:copper_block", "minecraft:glass", "minecraft:ice",
    "minecraft:packed_ice", "minecraft:snow_block", "minecraft:polished_andesite", "minecraft:magma_block",
    "nirncraft:crystal_block", "nirncraft:rubble_fire", "minecraft:oak_planks",
    "minecraft:stone_bricks", "minecraft:mossy_cobblestone", "minecraft:quartz_block"]
NIGHT_LADDER = [16, 32] + LADDER  # lights: close, mid, far, very far


def distance_scenes():
    """Per subject and time of day: a distance ladder (one bearing, one labelled strip, the hand-off pair measured;
    at night lit places start at 16 m so torches and windows are seen at every distance), a pop sweep (a smooth 120 m
    flight through the hand-off ring, a frame every 10 steps, frame-to-frame change measured: pop-in of terrain,
    buildings and lights) and high-altitude views; plus clouds approached from below and through the deck.
    suite:distance-ladder / distance-sweep / altitude / clouds-distance."""
    out = []
    for pid, kind, times in DISTANCE_SUBJECTS:
        pl = places().get(pid)
        if not pl:
            continue
        region = [f"region:{pl['region']}"] if pl.get("region") else []
        slug = pid.replace("_", "-")
        for tod in times:
            tags = ["suite:distance", "feature:farfield", "feature:popin", f"place:{pid}", f"subject:{kind}",
                    f"time:{tod}"] + region + (["feature:lights"] if tod == "night" and kind in LIT else []) \
                + (["feature:shadows"] if tod in ("dawn", "dusk") else [])
            common = {"_file": "(generated distance)", "time": TIMES[tod], "weather": "clear", "variants": ["shaders"],
                      "checks": {}, "source": "generated: catalog.distance_scenes"}
            rungs = NIGHT_LADDER if tod == "night" and kind in LIT else LADDER
            out.append({**common, "id": f"dist-{slug}-{tod}", "title": f"{pid} from {rungs[0]} m to 4 km ({tod})",
                        "tags": tags + ["suite:distance-ladder"], "timeout": 1500,
                        # by day also without shaders: the fallback far field at every scale (combos.png: side by side)
                        "variants": ["shaders", "vanilla"] if tod == "day" else ["shaders"],
                        "pose": {"ladder": {"place": pid, "distances": rungs}}})
            if tod in ("day", "night"):
                out.append({**common, "id": f"pop-{slug}-{tod}", "title": f"{pid}: flying through the hand-off ({tod})",
                            "tags": tags + ["suite:distance-sweep"], "timeout": 600, "pose": {"sweep": {"place": pid}}})
            if tod in ("day", "night"):  # far field vs real world: the same view, 120 m off, at three heights
                out.append({**common, "id": f"fid-{slug}-{tod}",
                            "title": f"{pid}: real chunks vs far field only, 3 / 60 / 200 m up ({tod})",
                            "tags": tags + ["suite:fidelity", "feature:seam", "feature:horizon"], "timeout": 1500,
                            "fidelity": True,
                            "pose": {"ladder": {"place": pid, "distances": [120, 120, 120], "heights": [3, 60, 200]}}})
            out.append({**common, "id": f"alt-{slug}-{tod}", "title": f"{pid} from high altitude ({tod})",
                        "tags": tags + ["suite:altitude"], "timeout": 900,
                        "pose": {"ladder": {"place": pid, "distances": [1200, 1500, 2500],
                                            "heights": [250, 700, 1400]}}})
    # entities across the ring: each summoned on the plains, ladder (model -> impostor) and a sweep, day and night
    ents = {k.split(".")[2] for f in LANG.glob("*.json") for k in json.loads(f.read_text(encoding="utf-8"))
            if k.startswith("entity.nirncraft.") and k.count(".") == 2}
    spot = HARNESS_SPOTS["harness_actors_plains"]
    for e in SEAM_ACTORS:
        eid = f"nirncraft:{e}" if e in ents else f"minecraft:{e}"
        summon = (f"try execute positioned {spot['x']} 0 {spot['z']} positioned over world_surface run summon {eid} "
                  f"~ ~1 ~ {{NoAI:1b,PersistenceRequired:1b,Silent:1b}}")
        kill = f"try kill @e[type={eid}]"
        for tod in ("day", "night"):
            common = {"_file": "(generated distance)", "time": TIMES[tod], "weather": "clear", "variants": ["shaders"],
                      "checks": {}, "source": "generated: catalog.distance_scenes",
                      "setup": ["try difficulty easy", summon, "wait 20"], "cleanup": [kill, "try difficulty peaceful"],
                      "tags": ["suite:distance", "suite:seam-entities", "feature:farfield", "feature:popin",
                               "feature:creature", "subject:entity", f"gallery:entity:{e}", f"time:{tod}",
                               "region:skyrim/whiterun"]}
            out.append({**common, "id": f"dist-actor-{e}-{tod}", "title": f"{e} from 16 m to 2 km ({tod})",
                        "tags": common["tags"] + ["suite:distance-ladder"], "timeout": 1500,
                        "pose": {"ladder": {"place": "harness_actors_plains",
                                            "distances": [16, 48, 128, 168, 184, 320, 640, 1280, 2048]}}})
            out.append({**common, "id": f"pop-actor-{e}-{tod}", "title": f"{e}: flying through the hand-off ({tod})",
                        "tags": common["tags"] + ["suite:distance-sweep"], "timeout": 600,
                        "pose": {"sweep": {"place": "harness_actors_plains"}}})
    # player edits: a pad of 12 x 12 material patches on the plains, then out to 2 km
    pad = HARNESS_SPOTS["harness_pad_plains"]
    fills = [f"try execute positioned {pad['x'] + (i % 4) * 14 - 21} 0 {pad['z'] + (i // 4) * 14 - 14} "
             f"positioned over world_surface run fill ~ ~-1 ~ ~11 ~-1 ~11 {b}" for i, b in enumerate(PAD)]
    for tod in ("day", "night"):
        out.append({"_file": "(generated distance)", "id": f"dist-edits-pad-{tod}",
                    "title": f"Player-built material pad from 24 m to 2 km ({tod}): does the far field show edits?",
                    "time": TIMES[tod], "weather": "clear", "variants": ["shaders"], "checks": {}, "timeout": 1500,
                    "setup": fills + ["wait 20"], "fidelity": False,
                    "source": "generated: catalog.distance_scenes",
                    "tags": ["suite:distance", "suite:seam-edits", "suite:distance-ladder", "feature:farfield",
                             "feature:materials", "feature:textures", "subject:edits", f"time:{tod}", "region:skyrim/whiterun"],
                    "pose": {"ladder": {"place": "harness_pad_plains",
                                        "distances": [24, 64, 128, 168, 184, 320, 640, 1280, 2048]}}})
    # PBR / materials at distance: a lineup on the sky stage, 4 m to 128 m (POM range, specular fade, metal F0)
    for tod in ("day", "dusk"):
        out.append({"_file": "(generated distance)", "id": f"pbr-ladder-{tod}",
                    "title": f"Materials from 4 m to 128 m ({tod}): metal, glass, ice, snow, PBR-mapped, plain",
                    "time": TIMES[tod], "weather": "clear", "variants": ["shaders", "vanilla"], "checks": {},
                    "timeout": 900, "source": "generated: catalog.distance_scenes",
                    "tags": ["suite:distance", "suite:pbr", "feature:materials", "feature:pbr", f"time:{tod}"],
                    "setup": [f"try setblock {STAGE[0] - 7 + i % 8 * 2} {STAGE[1] + 1 + i // 8} {STAGE[2] + (i // 8) * 2} {b}"
                              for i, b in enumerate(MATERIAL_SAMPLES)],
                    "pose": {"ladder": {"stage": True, "distances": [4, 8, 16, 32, 64, 128]}}})
    # clouds across the ring: a storm front parked over Whiterun, seen from 64 m out to 4 km (and flown through)
    for tod in ("day", "dusk"):
        common = {"_file": "(generated distance)", "time": TIMES[tod], "weather": "cloudy", "variants": ["shaders"],
                  "checks": {}, "source": "generated: catalog.distance_scenes", "front": [62, -68, 600, "storm"],
                  "tags": ["suite:distance", "suite:clouds-distance", "feature:clouds", "feature:storm",
                           "feature:farfield", "feature:popin", "subject:clouds", f"time:{tod}", "place:whiterun",
                           "region:skyrim/whiterun"]}
        out.append({**common, "id": f"dist-storm-front-{tod}", "title": f"A storm front from 64 m to 4 km ({tod})",
                    "timeout": 1500, "tags": common["tags"] + ["suite:distance-ladder"],
                    "pose": {"ladder": {"place": "whiterun", "distances": LADDER}}})
        out.append({**common, "id": f"pop-storm-front-{tod}", "title": f"Flying through the hand-off under a storm ({tod})",
                    "timeout": 600, "tags": common["tags"] + ["suite:distance-sweep"],
                    "pose": {"sweep": {"place": "whiterun"}}})
    # clouds at every distance and on approach: one spot, rising from the ground through the deck and above it
    for weather in ("overcast", "storm", "cloudy"):
        out.append({"_file": "(generated distance)", "id": f"clouds-approach-whiterun-{weather}",
                    "title": f"Clouds from the ground up through the deck ({weather})", "time": 6000, "weather": weather,
                    "variants": ["shaders"], "checks": {}, "timeout": 1500, "source": "generated: catalog.distance_scenes",
                    "tags": ["suite:distance", "suite:clouds-distance", "feature:clouds", "feature:farfield",
                             f"weather:{weather}", "place:whiterun", "region:skyrim/whiterun"],
                    "pose": {"ladder": {"place": "whiterun", "distances": [600, 600, 600, 600, 600, 600],
                                        "heights": [40, 200, 400, 700, 1000, 1400]}}})
    return out


def _bearing(plan, pid):
    """Unit vector from the subject towards its best planned aerial camera (a clear line of sight), else south."""
    e = (plan or {}).get(pid) or {}
    shots = e.get("aerial") or []
    pl = places().get(pid) or {}
    if shots and pl:
        cx, _, cz = shots[0]["camera"]
        dx, dz = cx - pl["x"], cz - pl["z"]
        n = math.hypot(dx, dz) or 1.0
        return dx / n, dz / n
    return 0.0, 1.0


def _aim(cx, cz, h, sx, sz, rise=15.0):
    """Yaw / pitch from a camera h above ground at (cx, cz) to a subject at (sx, sz), Minecraft convention."""
    yaw = math.degrees(math.atan2(-(sx - cx), sz - cz))
    pitch = math.degrees(math.atan2(h - rise, math.hypot(sx - cx, sz - cz)))
    return round(yaw, 1), round(pitch, 1)


def ladder_rungs(s, plan):
    """[(x, z, height, yaw, pitch, distance)] for a ladder pose (stage ladders: see stage_rungs)."""
    lad = s["pose"]["ladder"]
    pl = places()[lad["place"]]
    bx, bz = _bearing(plan, lad["place"])
    heights = lad.get("heights")
    rungs = []
    for i, d in enumerate(lad["distances"]):
        h = heights[i] if heights else max(25, round(d * 0.10))
        x, z = pl["x"] + bx * d, pl["z"] + bz * d
        rungs.append((int(x), int(z), int(h)) + _aim(x, z, h, pl["x"], pl["z"]) + (d,))
    return rungs


def sweep_path(s, plan):
    """((start x, y, z), (end x, y, z), (look x, y, z)): 70 m outside the hand-off ring to 50 m inside it, at a height
    above the subject that clears the ground between."""
    pid = s["pose"]["sweep"]["place"]
    pl = places()[pid]
    ground = ((plan or {}).get(pid) or {}).get("ground", 100.0)
    bx, bz = _bearing(plan, pid)
    y = round(ground + 60, 1)
    d0, d1 = HANDOFF + 70, HANDOFF - 50
    start = (round(pl["x"] + bx * d0, 1), y, round(pl["z"] + bz * d0, 1))
    end = (round(pl["x"] + bx * d1, 1), y, round(pl["z"] + bz * d1, 1))
    return start, end, (pl["x"], round(ground + 15, 1), pl["z"])


def shot_files(s, tag):
    """The screenshots a scene writes ([(file stem, label)]): one, or several that become one labelled strip."""
    p = s.get("pose") or {}
    if s.get("frames"):
        return [(f"{tag}-f{i}", f"frame {i + 1}") for i in range(s["frames"][0])]
    if "ladder" in p:
        d = p["ladder"]["distances"]
        hs = p["ladder"].get("heights")
        name = lambda i, x: (f"{x} m" if x < 1000 else f"{x / 1000:g} km") + (f" @ {hs[i]} m up" if hs else "")  # noqa: E731
        if s.get("fidelity"):
            return [item for i, x in enumerate(d) for item in
                    ((f"{tag}-r{i}", name(i, x) + ": real chunks"), (f"{tag}-r{i}b", name(i, x) + ": far field only"))]
        return [(f"{tag}-r{i}", name(i, x)) for i, x in enumerate(d)]
    if "sweep" in p:
        # camera-travel captures every 10 steps from step 10 (240 steps over 120 m: 0.5 m a step)
        return [(f"{tag}s_{i * 10:04d}", f"{HANDOFF + 70 - i * 5} m") for i in range(1, 25)]
    return [(tag, None)]


# ---------------------------------------------------------------------------------------------------- tool validation
VALIDATE_PLACES = ["whiterun", "solitude", "markarth", "riften", "windhelm", "falkreath", "dawnstar",
                   "college_of_winterhold", "helgen", "red_mountain", "imperial_city", "vivec"]
WEATHER_PRESET = {"clear": "clear", "fair": "cloudy", "overcast": "overcast", "storm": "storm"}


def validation_scenes():
    """The tools checked against the real game: per place, the offline exact heights and seeded landmarks vs what the
    world generated (/nirncraft-qa sample); per lab render, a live twin at the same place, sun and weather (compared in
    the overview). suite:validate-tools."""
    out = []
    for pid in VALIDATE_PLACES:
        pl = places().get(pid)
        if not pl:
            continue
        out.append({"id": f"validate-world-{pid.replace('_', '-')}", "title": f"{pid}: offline heights and landmarks vs the generated world",
                    "_file": "(generated validation)", "kind": "measure", "variants": ["measure"], "validate": "world",
                    "tags": ["suite:validate-tools", "check:tools", "feature:farfield", f"place:{pid}"]
                            + ([f"region:{pl['region']}"] if pl.get("region") else []),
                    "pose": {"visit": [pl["x"], pl["z"] + 40, 60, 180, 30]},
                    "measure_command": f"command nirncraft-qa sample {pl['x']} {pl['z']} 48 16",
                    "measure_file": "nirncraft-qa/sample.json", "checks": {}, "timeout": 300,
                    "source": "generated: catalog.validation_scenes"})
    try:
        labs = json.loads((SCENES / "lab.json").read_text(encoding="utf-8"))["scenes"]
    except (OSError, ValueError, KeyError):
        labs = []
    for lab in labs:
        a = lab.get("lab", {})
        if a.get("tool") != "atlas_render" or "x" not in a or "z" not in a or a.get("flags", "").find("dbg") >= 0:
            continue
        elev = float(str(a.get("sun", "35,120")).split(",")[0])
        ticks = int(round(6000 - (90 - elev) / 90 * 6000)) % 24000  # morning sun at that elevation
        out.append({"id": f"twin-{lab['id']}", "title": f"Live twin of {lab['id']} (lab vs game)",
                    "_file": "(generated validation)", "variants": ["shaders"], "time": ticks,
                    "weather": WEATHER_PRESET.get(a.get("weather", "fair"), "cloudy"),
                    "tags": ["suite:validate-tools", "feature:farfield", "feature:lab-twin", f"twin:{lab['id']}"],
                    # atlas_render: yaw 0 = north (-Z), pitch < 0 looks down; Minecraft: yaw 0 = south, pitch > 0 down
                    "pose": {"visit": [int(a["x"]), int(a["z"]), int(a.get("above", 40)),
                                       (float(a.get("yaw", 0)) + 180) % 360, -float(a.get("pitch", 0))]},
                    "checks": {}, "source": f"twin of {lab['id']}"})
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
    scenes += distance_scenes()
    scenes += validation_scenes()
    scenes += feature_scenes.feature_scenes()  # every generated biome / landmark / site kind (the cached index)
    import subjects  # every subject the game defines x the views its kind gets (the game's subject index)
    scenes += subjects.scenes()
    import behaviour  # behaviour scenarios (tools/harness/scenarios/*.json)
    scenes += behaviour.scenes()
    seam_core(scenes)
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


def seam_core(scenes):
    """One subject per category (its first), day and night, each instrument: the subset the gate runs for renderer
    and shader changes (the full matrix is `--suite distance`)."""
    first = {}
    for s in scenes:
        if "suite:distance" not in s["tags"]:
            continue
        cat = next((x for x in s["tags"] if x.startswith("subject:")), None)
        place = next((x for x in s["tags"] if x.startswith("place:") or x.startswith("gallery:entity:")), None)
        if cat and place:
            first.setdefault(cat, place)
    for s in scenes:
        cat = next((x for x in s["tags"] if x.startswith("subject:")), None)
        if cat and first.get(cat) in s["tags"] and any(x in s["tags"] for x in ("time:day", "time:night"))                 and "suite:altitude" not in s["tags"]:
            s["tags"].append("suite:seam-core")


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
    if "ladder" in p and p["ladder"].get("stage"):
        return STAGE[0], STAGE[2]
    for k in ("ladder", "sweep"):
        if k in p:
            pl = places().get(p[k].get("place"))
            if pl:
                return pl["x"], pl["z"]
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
    # every scene pins its time and weather: unset ones would inherit whatever the previous scene left (another
    # time of day, moon phase or weather), and a before / after would then compare two different skies
    indoors = "realm" in p or "dungeon" in p
    weather = s.get("weather") or (None if indoors else "clear")
    lines = [f"try time set {int(s['time']) if s.get('time') is not None else 6000}"]  # 26.3 reports 0: not a failure
    if "visit" in p:
        x, z, h, yaw, pitch = p["visit"]
        h = int(h) + 25 * candidate  # a retry after "camera intersects terrain" goes higher
        lines.append(f"visit {int(x)} {int(z)} {h} {yaw} {pitch} {weather or 'clear'}")
        weather = None  # visit sets it
    elif "camera" in p:
        x, y, z, lx, ly, lz = p["camera"]
        up = 4 * candidate  # a retry after "camera intersects terrain" lifts the camera (and its aim) a little
        lines.append(f"camera {x} {y + up} {z} {lx} {ly + up} {lz}")
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
        h = int(p.get('height', 40)) + 25 * candidate  # a retry after "camera intersects terrain" goes higher
        lines.append(f"visit {int(pl['x'] + dx)} {int(pl['z'] + dz)} {h} {p.get('yaw', 0)} "
                     f"{p.get('pitch', -15)} {weather or 'clear'}")
        weather = None
    elif "stage" in p:
        x, y, z, d = p["stage"]
        # the camera first: it loads the chunks around itself, so the pad below can then be built
        lines += [f"camera {x} {y + 1 + d * 0.45:.2f} {z - d:.2f} {x} {y + 1 + d * 0.25:.2f} {z}",
                  f"try setworldspawn {x} {y + 1} {z}",  # the next boot loads here, not in the spawn cabin
                  f"try fill {x - 7} {y} {z - 7} {x + 7} {y} {z + 7} minecraft:glass",
                  f"try fill {x - 7} {y} {z - 7} {x + 7} {y} {z + 7} minecraft:smooth_stone"]
    elif "studio" in p:
        x, y, z = STUDIO
        lines += [f"camera {x} {y + 1} {z + 5} {x} {y + 2.5} {z - 6}",  # first: it loads the chunks
                  f"try fill {x - 7} {y} {z - 7} {x + 7} {y + 9} {z + 7} minecraft:black_concrete hollow",
                  f"try fill {x - 6} {y + 1} {z - 6} {x + 6} {y + 8} {z + 6} minecraft:air"] +                  [f"try setblock {x + dx} {y + 7} {z + dz} minecraft:light[level=12]" for dx in (-5, 5) for dz in (-5, 5)]
    elif "dungeon" in p:
        lines.append(f"command nirncraft-dungeon-qa view {p['dungeon']} {p.get('view', 'entrance')}")
        lines.append("try effect clear @p minecraft:night_vision")  # the view adds night vision; judge real light
    elif "realm" in p:
        lines.append(f"command nirncraft-realm go {p['realm']}")
        if p.get("look"):
            lines.append(f"command nirncraft-realm look {p['look']}")
    elif "ladder" in p:
        pass  # each rung moves the camera itself (capture_actions)
    elif "sweep" in p:
        (x, y, z), _, (lx, ly, lz) = sweep_path(s, plan)
        lines.append(f"camera {x} {y + 6 * candidate} {z} {lx} {ly} {lz}")
    elif "spawn" in p:  # the live spawn can sit inside terrain: screens and spawn shots get a clear spot above (0, 0)
        lines.append(f"visit -150 260 {30 + 25 * candidate} 180 10 {weather or 'clear'}")  # open ground south of Whiterun
        weather = None
    if weather:
        if weather not in WEATHERS:
            raise ValueError(f"{s['id']}: unknown weather {weather}")
        lines.append(f"command execute as @p at @s run nirncraft-weather set {weather} 120")
    if s.get("front"):
        x, z, r, w, *v = s["front"]
        lines.append(f"front {x} {z} {r} {w} " + " ".join(str(c) for c in v))
    return lines


def capture_actions(s, tag, plan=None):
    p = s.get("pose") or {}
    if "ladder" in p and p["ladder"].get("stage"):  # on the sky stage: build it, lay out the samples, step back
        sx, sy, sz = STAGE
        lines = [f"try fill {sx - 9} {sy} {sz - 9} {sx + 9} {sy} {sz + 9} minecraft:glass",
                 f"try fill {sx - 9} {sy} {sz - 9} {sx + 9} {sy} {sz + 9} minecraft:smooth_stone",
                 f"try fill {sx - 9} {sy + 1} {sz - 9} {sx + 9} {sy + 6} {sz + 9} minecraft:air"] + list(s.get("setup", []))
        for i, d in enumerate(p["ladder"]["distances"]):
            lines += [f"camera {sx} {sy + 2 + d * 0.35:.1f} {sz - 2 - d:.1f} {sx} {sy + 1.5} {sz + 1}", "settle-visual 1800",
                      "wait 20", f"capture {tag}-r{i}"]
        return lines + list(s.get("cleanup", []))
    if "ladder" in p:  # every rung: move, let the world load, hold, capture
        lines = []
        for i, (x, z, h, yaw, pitch, _) in enumerate(ladder_rungs(s, plan)):
            lines += [f"visit {x} {z} {h} {yaw} {pitch} {s.get('weather') or 'clear'}", "settle-visual 1800"]
            if i == 0:  # the subject's chunks are loaded now: build / summon what the test needs
                lines += list(s.get("setup", []))
            lines += [f"wait {int(s.get('wait', 40))}", f"capture {tag}-r{i}"]
            if s.get("fidelity"):  # the same view drawn by the far field alone (render distance 2), then back
                lines += ["renderdistance 2", "settle-visual 1800", f"wait {int(s.get('wait', 40))}", f"capture {tag}-r{i}b",
                          f"renderdistance {NEAR_RD}", "settle-visual 1800"]
        return lines + list(s.get("cleanup", []))
    if "sweep" in p:  # a smooth flight through the hand-off ring; the travel verb captures every 10 steps
        _, (ex, ey, ez), (lx, ly, lz) = sweep_path(s, plan)
        return ["settle-visual 1800"] + list(s.get("setup", [])) + [f"wait {int(s.get('wait', 60))}",
                f"camera-travel {tag}s 240 {ex} {ey} {ez} {lx} {ly} {lz}"] + list(s.get("cleanup", []))
    staged = s.get("frame") or s.get("frameBox") or s.get("camera_verb")
    showcase = any(str(c).startswith("showcase ") for c in s.get("setup", []))
    # the world behind a screen is part of the picture too; a staged subject settles once, after it is framed;
    # a showcase (an item's UI view) needs no world at all
    lines = [] if (staged or showcase) else ["settle-visual 1800"]
    if s.get("wait"):
        lines.append(f"wait {int(s['wait'])}")
    lines += list(s.get("setup", []))
    if s.get("frame"):  # the game frames the subject that setup just placed, from its real size
        lines += [f"frame {s['frame'][0]} {s['frame'][1]}", "settle-visual 600"]
    elif s.get("frameBox"):  # a placed block / furniture: framed by its box
        lines += ["frame-box " + " ".join(str(v) for v in s["frameBox"]), "settle-visual 600"]
    elif s.get("camera_verb"):  # e.g. a building framed from the report its command wrote
        lines += [s["camera_verb"], "settle-visual 900"]
    if s.get("measure"):
        lines.append(f"measure {tag} {int(s['measure'])}")
    if s.get("frames"):
        n, every = s["frames"]
        for f in range(n):
            lines += [f"capture {tag}-f{f}"] + ([f"wait {every}"] if f < n - 1 else [])
    else:
        hud = "feature:hud" in s.get("tags", [])
        lines += (["hud on", "wait 10"] if hud else []) + [("capture-ui " if s.get("capture") == "ui" else "capture ")
                                                            + tag] + (["hud off"] if hud else [])
    lines += list(s.get("cleanup", []))
    if "realm" in (s.get("pose") or {}):
        lines.append("try nirncraft-realm home")
    return lines
