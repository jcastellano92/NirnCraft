"""The pinned ground-truth suite (suite:pinned): Minecraft's own rendering of one element at any distance, from 8 m to
64 km, against the exact offline reference (atlas_truth) of the same blocks from the same equivalent camera.

Run it deliberately: `py -3 tools/harness/harness.py run --suite pinned` before a merge, or when an offline result is
in doubt (`--tag pinned:<slug>` for one element, `--tag pinned:rung` / `pinned:flyin` / `pinned:ladder` for one kind).
It is the FINAL live validation, not part of the everyday loop: development runs offline against existing exports
(atlas_truth, atlas_lab, atlas_render). Its scenes carry only suite:pinned and descriptive tags (never suite:smoke or a
tag tools/harness/impact.json selects), and a rerun costs little: live captures and lab renders are reused while their
inputs are unchanged (the harness's fingerprints), the exports are rewritten with the same bytes, and the block
library and mesh data (atlaslab quads) are skipped when current.

How a pinned capture works (mod: com.nirncraft.client.pinned.PinnedTruth): the scene goes to the element, freezes the
world and pins its box (`pin`: blocks, light with a one-block margin, blended tints, block entities, and for entity
elements the entities, all as Minecraft has them), exports the same box for the offline reference (`pin-export`:
ALV2 with light, the box plus its margin; atlas_truth cuts the box out with box=), then turns the near field down
(render distance 2), the far field off (`atlas-suspend`) and fog off, moves the camera to the capture point high over
the sky stage and draws the copy there with Minecraft's own section compiler, terrain pipelines, block atlas and
entity / block entity renderers (`pin-view`): at its real size up to 32 m, beyond that drawn smaller and nearer (the
same picture: same pixels, same mip levels, same baked light). `pin-shimmer` grabs the frame twice 6 ticks apart (does
Minecraft's own picture move by itself there). The capture's receipt carries the equivalent eye; the lab scene renders
the export from it (harness.camera_from with "pinned": the receipt's eye and box replace the stage camera's).

Per element:
* `pinned-<slug>-<d>m` (live) and `pinned-<slug>-<d>m-truth` (lab, atlas_truth vs the capture: dE, edges, missing,
  flat) for every distance in DISTANCES, plus `-culled` rungs for the mob (only what Minecraft itself would draw
  there: entity and block entity view distances);
* `pinned-<slug>-fly-<speed>` (live, frames: one geometric step closer after every capture) and
  `pinned-<slug>-fly-<speed>-truth` (check: the same frames offline, frame-to-frame change live vs offline: pop,
  sub-pixel flicker, per-frame dE) for every speed in FLYINS;
* `pinned-<slug>-ladder` (check): the rungs in one table (`py -3 tools/harness/pinned.py ladder <slug>`): pixels the
  element covers, dE and edges against the reference, shimmer, what Minecraft culls, so where Minecraft itself stops
  being a usable ground truth (sub-pixel, mip blur, culled entities) is a number, not a guess. There a renderer that
  disagrees with Minecraft is information, not an error.

Entity elements (a cow, a villager, an NPC, a lit armour stand, a torch-bearing NPC at night, a mob) are staged on a
pad at BUILD on the sky stage, with NoAI and the world frozen; their offline comparisons are informational (the
reference draws no entities yet: atlas_truth's header). World elements are the offline element tier's
(tools/harness/element_lab.py: every export's centre chunk and biggest tree by world box, its camera direction and
fly-ins), so one element is compared offline-to-offline there (suite:elements) and offline-to-live here; ELEMENTS
below (first guesses around named places, unvetted) stand in only while there are no exports. The live copy is
rendered against the offline reference in its exact mode (one sample a pixel, as Minecraft renders), not the
intended mode the element tier scores our renderer against.

Every capture position must be vetted by a cheap visual review before it is relied on (is the element in view,
unblocked, informative): the visual-reviewer subagent on the run's agent pages, or `harness.py review --pages DIR`.
"""
import json
import math
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EXPORT_DIR = "@game/nirncraft-atlaslab/"
DISTANCES = [8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536]
# speed, from, to, frames (geometric steps); the element tier's own fly-ins replace these (flyins())
FLYINS = [("slow", 2048, 16, 32), ("medium", 32768, 8, 24), ("fast", 65536, 32, 24)]
VET_UP_TO = 256  # rungs up to this distance get the pose review; beyond, the element is meant to shrink to pixels
EYE = 1.62
TAGS = ["suite:pinned", "feature:pinned", "feature:ground-truth"]

# element: slug, title, how to reach it (a harness pose, or None for a staged element), the box (six coordinates,
# "~" relative to the player there), entities, time, view (yaw, pitch: the direction the element is seen in),
# build commands (staged elements), tags
ELEMENTS = [
    ("falkreath-house", "A Falkreath house with its yard",
     {"place": "falkreath", "height": 3, "yaw": 0, "pitch": 10}, "~-10 ~-5 ~-10 ~10 ~13 ~10", False, 6000, (0, 8), None,
     ["place:falkreath", "element:house"]),
    ("falkreath-pine", "One Falkreath pine and the ground under it",
     {"place": "falkreath", "height": 3, "yaw": 135, "pitch": 4, "offset": [120, 120]}, "~-4 ~-4 ~-4 ~4 ~24 ~4", False, 6000,
     (0, 6), None, ["place:falkreath", "element:tree"]),
    ("plains-grass", "A chunk of open plains with its grass",
     {"place": "whiterun", "height": 3, "yaw": 180, "pitch": 10, "offset": [0, 160]}, "~-8 ~-5 ~-8 ~7 ~3 ~7", False, 6000,
     (0, 20), None, ["place:whiterun", "element:grass"]),
    ("whiterun-wall", "A section of Whiterun's wall",
     {"place": "whiterun", "height": 4, "yaw": 180, "pitch": 3, "offset": [0, 75]}, "~-10 ~-6 ~-6 ~10 ~16 ~6", False, 6000,
     (180, 4), None, ["place:whiterun", "element:wall"]),
    ("cave-mouth", "A cave mouth's chunk",
     {"place": "greywater_grotto", "height": 3, "yaw": 0, "pitch": 4}, "~-8 ~-10 ~-8 ~7 ~10 ~7", False, 6000, (0, 8),
     None, ["place:greywater_grotto", "element:cave"]),
]


def _stage_points():
    import catalog
    sx, sy, sz = catalog.STAGE
    # the capture point: high over the sky stage, away from its pads (nothing but the copy and the sky in view); the
    # staging pad for built elements well outside the capture point's render distance
    return (sx + 300, sy + 16, sz - 300), (sx - 300, sy, sz + 300)


def _staged():
    """Elements built on a pad at BUILD (absolute boxes), entities NoAI, the world frozen once they stand."""
    _, (bx, by, bz) = _stage_points()
    box = f"{bx - 3} {by} {bz - 3} {bx + 3} {by + 3} {bz + 3}"
    nbt = "NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[180f,0f]"
    at = f"{bx + 0.5} {by + 1} {bz + 0.5}"
    npc = f"command nirncraft-qa npc citizen nord {bx} {by + 1} {bz}"
    torch = "command item replace entity @e[type=nirncraft:npc,limit=1,sort=nearest] weapon.mainhand with minecraft:torch"
    armour = ('equipment:{head:{id:"minecraft:iron_helmet"},chest:{id:"minecraft:iron_chestplate"},'
              'legs:{id:"minecraft:iron_leggings"},feet:{id:"minecraft:iron_boots"},mainhand:{id:"minecraft:torch"}}')
    E = ["feature:entities", "pinned:informational"]
    return [
        ("cow", "A cow standing on grass", box, True, 6000, (0, 8), [f"command summon minecraft:cow {at} {{{nbt}}}"],
         E + ["element:creature"]),
        ("villager", "A villager", box, True, 6000, (0, 8), [f"command summon minecraft:villager {at} {{{nbt}}}"],
         E + ["element:npc"]),
        ("npc", "One of our NPCs (a Nord citizen)", box, True, 6000, (0, 8), [npc], E + ["element:npc"]),
        ("armour-stand-torch", "An armour stand in full armour holding a torch, at night", box, True, 18000, (0, 8),
         [f"command summon minecraft:armor_stand {at} {{ShowArms:1b,Rotation:[180f,0f],{armour}}}"],
         E + ["element:player-like", "feature:lights", "pinned:night"]),
        ("npc-torch-night", "An NPC holding a torch at night", box, True, 18000, (0, 8), [npc, torch],
         E + ["element:npc", "feature:lights", "pinned:night"]),
        ("chests", "A loot chest with its lid ajar next to a vanilla chest", box, False, 6000, (0, 12),
         [f"try setblock {bx - 1} {by + 1} {bz} nirncraft:loot_chest[lid=ajar,facing=north]",
          f"try setblock {bx + 1} {by + 1} {bz} minecraft:chest[facing=north]"], ["element:container", "feature:block-entities"]),
        ("campfire-noon", "A lit campfire at noon", box, False, 6000, (0, 12),
         [f"try setblock {bx} {by + 1} {bz} minecraft:campfire[lit=true]"], ["element:fire", "feature:lights"]),
        ("campfire-night", "A lit campfire at night", box, False, 18000, (0, 12),
         [f"try setblock {bx} {by + 1} {bz} minecraft:campfire[lit=true]"], ["element:fire", "feature:lights", "pinned:night"]),
        ("mob", "A mob (a husk: no burning in daylight) at distance: does Minecraft draw it at all", box, True, 6000, (0, 8),
         ["try difficulty easy", f"command summon minecraft:husk {at} {{{nbt}}}"], E + ["element:mob", "feature:culling"]),
    ]


def _tier():
    try:
        import element_lab
        return element_lab
    except Exception:  # noqa: BLE001  (numpy / PIL missing, or no export directory: the built-in list stands)
        return None


def elements_from_tier():
    """The offline element tier's elements (element_lab.elements(): every export's centre chunk and biggest tree, by
    world box), seen along the tier's own camera direction (element_lab.camera's yaw and pitch), so one element is
    compared offline-to-offline there and offline-to-live here. None when there are no exports yet."""
    el = _tier()
    if el is None:
        return None
    try:
        els = el.elements()
        yaw, pitch = el.camera.__defaults__[0], el.camera.__defaults__[1]
    except Exception:  # noqa: BLE001
        return None
    out = []
    for e in els:
        b = e["box"]
        pose = {"visit": [(b[0] + b[3]) // 2, (b[2] + b[5]) // 2, 3, yaw, max(0.0, pitch)]}
        out.append((e["slug"], f"element {e['slug']} ({e['kind']})", pose, " ".join(str(v) for v in b), False, 6000,
                    (yaw, pitch), None, [f"element:{e['kind']}", "element:tier"]))
    return out or None


def flyins():
    """The fly-ins: the element tier's (so both tiers fly the same path), else FLYINS."""
    el = _tier()
    fl = getattr(el, "FLYINS", None) if el else None
    if isinstance(fl, dict) and fl:
        return [(speed, int(v[0]), int(v[1]), int(v[2])) for speed, v in fl.items()]
    return FLYINS


def _pin(slug):
    """The pin's name, and its export's (pinned-<slug>.alv2 / .view.json: never one of the tier's own exports)."""
    return f"pinned-{slug}"


def _camera(yaw, pitch):
    (cx, cy, cz), _ = _stage_points()
    import catalog  # noqa: F401  (the stage)
    yr, pr = math.radians(yaw), math.radians(pitch)
    f = (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))
    ey = cy + EYE
    return f"camera {cx} {cy} {cz} {cx + f[0] * 100:.3f} {ey + f[1] * 100:.3f} {cz + f[2] * 100:.3f}"


def _live(slug, title, pose, box, entities, time, view, build, tags, sid, place_line, extra=None):
    import catalog
    yaw, pitch = view
    name = _pin(slug)
    _, (bx, by, bz) = _stage_points()
    setup = []
    if build is not None:  # staged: clear the pad's air and entities, build, let it stand, then freeze
        setup += [f"try kill @e[type=!player,x={bx},y={by},z={bz},distance=..12]",
                  f"try fill {bx - 6} {by + 1} {bz - 6} {bx + 6} {by + 6} {bz + 6} minecraft:air",
                  f"try fill {bx - 3} {by} {bz - 3} {bx + 3} {by} {bz + 3} minecraft:grass_block"] + build + ["wait 20"]
    setup += ["try tick freeze", f"pin {name} {box}" + (" entities" if entities else ""), f"pin-export {name}",
              "atlaslab quads", "renderdistance 2", "atlas-suspend on", "fog-off on",
              _camera(yaw, pitch), "settle-visual 600", place_line, "wait 10", "pin-shimmer 6"]
    cleanup = [f"pin-hide {name}", "atlas-suspend off", "fog-off off", f"renderdistance {catalog.NEAR_RD}",
               "try tick unfreeze"]
    if build is not None:
        cleanup += [f"try kill @e[type=!player,x={bx},y={by},z={bz},distance=..12]", "try difficulty peaceful"]
    s = {"_file": "(generated pinned)", "id": sid, "title": title, "tags": list(TAGS) + [f"pinned:{slug}"] + tags,
         "tier": "live", "group": "live", "variants": ["vanilla"], "weather": "off", "time": time,
         "pose": pose if pose is not None else {"stage": [bx, by, bz, 6]}, "wait": 20, "setup": setup,
         "cleanup": cleanup, "measure": 0, "checks": {}, "capture": "world", "timeout": 1200,
         "source": "tools/harness/pinned.py",
         "note": "a pinned copy drawn by Minecraft's own code on the sky stage; the receipt's 'pinned' block carries the "
                 "equivalent eye" + ("; entity element: the offline comparison is informational until atlas_truth "
                                    "renders entities" if "pinned:informational" in tags else "")}
    s.update(extra or {})
    return s


def _lab(slug, sid, title, tags):
    return {"_file": "(generated pinned)", "id": f"{sid}-truth",
            "title": f"{title}: Minecraft's own mesh offline from the pinned capture's equivalent eye",
            "tags": list(TAGS) + [f"pinned:{slug}", "pinned:truth"] + tags, "tier": "lab", "group": "lab",
            "variants": ["lab"],
            "lab": {"tool": "atlas_truth", "quads": EXPORT_DIR + "quads.bin", "sprites": EXPORT_DIR + "sprites.bin",
                    "volume": EXPORT_DIR + f"{_pin(slug)}.alv2", "json": EXPORT_DIR + f"{_pin(slug)}.view.json"},
            "camera_from": {"scene": sid, "variant": "vanilla", "pinned": True}, "after": [sid],
            "wait": 0, "setup": [], "cleanup": [], "measure": 0, "checks": {}, "capture": "world",
            "source": "native/tools/atlas_truth.cpp"}


def _check(sid, title, args, inputs, tags, metrics):
    return {"_file": "(generated pinned)", "id": sid, "title": title, "tags": list(TAGS) + tags, "tier": "check",
            "variants": ["check"], "run": ["py", "-3", "tools/harness/pinned.py"] + args, "inputs": inputs,
            "metrics": metrics, "fail_regex": r"^ERR", "timeout": 1800, "source": "tools/harness/pinned.py"}


def _dist(d):
    return f"{d}m" if d < 1000 else f"{d // 1024}k" if d % 1024 == 0 else f"{d}m"


def scenes():
    out = []
    world = elements_from_tier() or ELEMENTS
    staged = [(slug, title, None, box, ent, time, view, build, tags) for slug, title, box, ent, time, view, build, tags
              in _staged()]
    for slug, title, pose, box, ent, time, view, build, tags in list(world) + staged:
        rung_ids = []
        culled = slug == "mob"
        for d in DISTANCES:
            for cull in ((False, True) if culled else (False,)):
                sid = f"pinned-{slug}-{_dist(d)}" + ("-culled" if cull else "")
                t = f"Pinned {title} at {d} m" + (" (only what Minecraft itself draws there)" if cull else "")
                place = f"pin-view {_pin(slug)} {d} {view[0]} {view[1]}" + (" cull" if cull else "")
                # the pose review where the element should still be plainly visible (farther it is meant to vanish)
                vet = {"vet_pose": f"{title}: one small isolated element in the middle of the frame against the sky, "
                                   f"seen from {d} m"} if d <= VET_UP_TO else {}
                out.append(_live(slug, t, pose, box, ent, time, view, build, tags + ["pinned:rung", f"distance:{d}"], sid,
                                 place, extra=vet))
                out.append(_lab(slug, sid, t, tags + [f"distance:{d}"]))
                rung_ids.append(sid)
        for speed, d0, d1, n in flyins():
            sid = f"pinned-{slug}-fly-{speed}"
            t = f"Pinned {title}: fly-in {d0} m to {d1} m in {n} frames ({speed})"
            place = f"pin-fly {_pin(slug)} {d0} {d1} {n} {view[0]} {view[1]}"
            out.append(_live(slug, t, pose, box, ent, time, view, build, tags + ["pinned:flyin"], sid, place,
                             extra={"frames": [n, 3]}))
            out.append(_check(f"{sid}-truth", f"{t}: offline frames from the same eyes, change per step live vs offline",
                              ["flyin", sid, slug], [f".work/harness/results/{sid}"], [f"pinned:{slug}", "pinned:flyin"] + tags,
                              {"popLive": r"popLive (\S+)", "popOffline": r"popOffline (\S+)", "flyDE": r"flyDE (\S+)",
                               "subPixelFrom": r"subPixelFrom (\S+)"}))
        out.append(_check(f"pinned-{slug}-ladder", f"Pinned {title}: every distance in one table (where Minecraft "
                          "itself stops being a usable ground truth)", ["ladder", slug],
                          [f".work/harness/results/{r}" for r in rung_ids] + [f".work/harness/results/{r}-truth" for r in rung_ids],
                          [f"pinned:{slug}", "pinned:ladder"] + tags,
                          {"rungs": r"rungs (\d+)", "subPixelFrom": r"subPixelFrom (\S+)", "worstDE": r"worstDE (\S+)",
                           "shimmerMax": r"shimmerMax (\S+)", "culledFrom": r"culledFrom (\S+)"}))
    return out


# ------------------------------------------------------------------------------------------------ the checks (CLI)
def _results():
    sys.path.insert(0, str(HERE))
    import results
    return results


def _game_dir():
    sys.path.insert(0, str(ROOT / "tools/dev"))
    import live_world as lw
    return lw.GAME


def ladder(slug):
    """One element's rungs in one table: covered pixels, dE / edges against the offline reference, shimmer, culling."""
    results = _results()
    rows, worst, sub_from, culled_from, shimmer_max = [], 0.0, None, None, 0.0
    for d in DISTANCES:
        live = results.latest(f"pinned-{slug}-{_dist(d)}", "vanilla") or {}
        lab = results.latest(f"pinned-{slug}-{_dist(d)}-truth", "lab") or {}
        p = (live.get("game") or {}).get("pinned") or {}
        m = lab.get("metrics") or {}
        if not p:
            print(f"{d:>6} m  no live capture yet")
            continue
        g = live.get("game") or {}
        w, h = 1920, 1080
        png = Path(f"{results.stem(f'pinned-{slug}-{_dist(d)}', 'vanilla')}.after.png")
        if png.exists():
            from PIL import Image
            w, h = Image.open(png).size
        px = m.get("liveShare", 0) * w * h
        sh = (p.get("shimmer") or {}).get("mean", 0.0)
        shimmer_max = max(shimmer_max, sh)
        ents = p.get("entities") or []
        drawn = p.get("entitiesVanillaDraws", 0)
        if ents and drawn < len(ents) and culled_from is None:
            culled_from = d
        if px < 4 and sub_from is None:
            sub_from = d
        de = m.get("liveDE")
        if isinstance(de, (int, float)):
            worst = max(worst, de)
        rows.append({"distance": d, "pixels": round(px, 1), "scale": p.get("scale"), "dE": de, "edges": m.get("liveEdges"),
                     "missing": m.get("liveMissing"), "shimmer": sh, "entities": len(ents), "vanillaDraws": drawn,
                     "blockEntitiesVanillaDraws": p.get("blockEntitiesVanillaDraws"), "eye": p.get("eye"),
                     "fov": g.get("fov")})
        print(f"{d:>6} m  {px:>9.1f} px  scale {p.get('scale', 0):.5f}  dE {de}  edges {m.get('liveEdges')}  "
              f"shimmer {sh}  entities drawn by Minecraft {drawn}/{len(ents)}")
    out = ROOT / ".work/harness/pinned"
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{slug}.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"rungs {len(rows)}")
    print(f"subPixelFrom {sub_from or 0}")
    print(f"worstDE {round(worst, 2)}")
    print(f"shimmerMax {shimmer_max}")
    print(f"culledFrom {culled_from or 0}")
    return 0


def _tiles(strip_png, n, tile_w=480):
    """The frames of a capture strip (results.strip: rows of up to 5 tiles 480 px wide under a 22 px label)."""
    from PIL import Image
    im = Image.open(strip_png).convert("RGB")
    cols = min(5, n)
    th = im.height // ((n + cols - 1) // cols) - 22
    return [im.crop(((i % cols) * tile_w, (i // cols) * (th + 22) + 22, (i % cols + 1) * tile_w,
                     (i // cols) * (th + 22) + 22 + th)) for i in range(n)]


def flyin(sid, slug):
    """The fly-in offline: atlas_truth from every frame's equivalent eye (the receipt's flight), at the strip's tile size;
    per step the change of the picture live and offline (results._centre_diff) and the colour error per frame."""
    results = _results()
    rec = results.latest(sid, "vanilla") or {}
    g = rec.get("game") or {}
    p = g.get("pinned") or {}
    fl = p.get("flight") or []
    png = Path(f"{results.stem(sid, 'vanilla')}.after.png")
    if not fl or not png.exists():
        print(f"ERR no live fly-in capture of {sid} with a pinned flight yet: run it first")
        return 1
    exe = ROOT / "native/build/Release/atlas_truth.exe"
    if not exe.exists():
        print("ERR atlas_truth.exe not built (native/build.ps1)")
        return 1
    game = _game_dir() / "nirncraft-atlaslab"
    live = _tiles(png, len(fl))
    tw, th = live[0].size
    w, h = tw * 4, th * 4  # the game's window (1920 wide), shrunk to the strip's tiles as results.strip shrinks them
    from PIL import Image
    import numpy as np
    offline = []
    with tempfile.TemporaryDirectory() as tmp:
        for i, f in enumerate(fl):
            out = Path(tmp) / f"f{i}.png"
            cmd = [str(exe), f"out={out.as_posix()}", f"quads={game / 'quads.bin'}", f"sprites={game / 'sprites.bin'}",
                   f"volume={game / (_pin(slug) + '.alv2')}", f"json={game / (_pin(slug) + '.view.json')}",
                   f"box={p['box']}",
                   "camera=mc", "eye=" + ",".join(f"{c:.4f}" for c in f["eye"]), f"yaw={g.get('yaw', 0)}",
                   f"pitch={g.get('pitch', 0)}", f"fov={g.get('fov', 70)}", f"w={w}", f"h={h}",
                   f"time={int(g.get('dayTime', 6000)) % 24000}"]
            r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=600,
                               creationflags=0x00004000 if sys.platform == "win32" else 0)  # below normal priority
            if r.returncode or not out.exists():
                print(f"ERR atlas_truth failed on frame {i}: {(r.stdout + r.stderr)[-300:]}")
                return 1
            offline.append(Image.open(out).convert("RGB").resize((tw, th)))
    step_live = [results._centre_diff(live[i], live[i + 1]) for i in range(len(live) - 1)]
    step_off = [results._centre_diff(offline[i], offline[i + 1]) for i in range(len(offline) - 1)]
    de = [float(np.sqrt(((np.asarray(results._lab(a, w=240)) - np.asarray(results._lab(b, w=240))) ** 2).sum(axis=2)).mean())
          for a, b in zip(live, offline)]
    # pop: the largest step's excess over the offline reference's own change at that step (the reference has no
    # levels: its change is what the motion makes anyway)
    excess = [a - b for a, b in zip(step_live, step_off)]
    k = int(np.argmax(excess)) if excess else 0
    for i, f in enumerate(fl):
        print(f"frame {i:>2}  {f['distance']:>9.1f} m  dE {de[i]:.2f}" + (
            f"  step live {step_live[i]:.2f} offline {step_off[i]:.2f}" if i < len(step_live) else ""))
    sub = next((f["distance"] for f, img in zip(fl, live) if _covered(img) * 16 < 4), 0)  # tile pixels x 16: the window's
    print(f"popLive {round(max(step_live), 2) if step_live else 0}")
    print(f"popOffline {round(max(step_off), 2) if step_off else 0}")
    print(f"popExcess {round(excess[k], 2) if excess else 0} at frame {k + 1}")
    print(f"flyDE {round(float(np.mean(de)), 2)}")
    print(f"subPixelFrom {round(sub, 1)}")
    return 0


def _covered(img):
    """Pixels that differ from the frame's sky (its top rows' median): how much of the frame the element covers."""
    import numpy as np
    a = np.asarray(img, dtype=np.float32)
    sky = np.median(a[: max(2, a.shape[0] // 40)].reshape(-1, 3), axis=0)
    return int((np.abs(a - sky).max(axis=2) > 12).sum())


def main(argv):
    if len(argv) >= 2 and argv[0] == "ladder":
        return ladder(argv[1])
    if len(argv) >= 3 and argv[0] == "flyin":
        return flyin(argv[1], argv[2])
    print("usage: pinned.py ladder SLUG | flyin LIVE-SCENE-ID SLUG")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
