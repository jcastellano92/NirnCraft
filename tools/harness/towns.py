"""Towns in the real world: cameras planned from the town's own data, and the roads drawn onto the pictures.

Offline, the town plan diagram (subjects.town_plans: the game's planner, nothing built) is the quick check; these
views are the final word, taken at the town's actual location:

    top       straight down over the centre, high enough to hold the town's radius
    oblique   from the south-east at 40 degrees (the isometric look)
    gate-N    from the road outside each gate (the plan's gates, world coordinates), at eye height, looking in
    distance  far out along the first gate's road, looking at the town

`roads`: after a capture, the road plan's segments near the town are projected through the capture's own camera
(eye, yaw, pitch, field of view from the receipt) and drawn onto a copy of the picture (<variant>.roads.png):
highways yellow, roads orange, lanes and trails pale, so the main routes and how they reach the gates read at a glance.
"""
import importlib.util
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

import catalog
import results

SETTLEMENTS = ("capital", "city", "town", "village", "fort")
COLOURS = {"highway": (255, 214, 60), "road": (255, 150, 40), "lane": (235, 235, 200), "trail": (200, 220, 200)}
_ROADS = {}


def _yaw_to(dx, dz):
    """Minecraft yaw looking along (dx, dz)."""
    return round(math.degrees(math.atan2(-dx, dz)), 1)


def _gates(pid):
    rec = results.latest(f"plan-{pid}".replace("_", "-"), "measure") or {}
    return ((rec.get("game") or {}).get("report") or {}).get("gatesWorld") or []


def scenes():
    out = []
    for pid, pl in catalog.places().items():
        if pl.get("type") not in SETTLEMENTS:
            continue
        x, z, r = pl["x"], pl["z"], max(40, pl.get("radius", 60))
        base = {"_file": "(generated towns)", "tier": "live", "kind": "visual", "variants": ["shaders"],
                "time": 6000, "weather": "clear", "checks": {"maxBlackTiles": 0}, "annotate": "roads",
                "town": {"id": pid, "x": x, "z": z, "radius": r}, "source": "towns.py (the town's own data)"}
        tags = ["suite:towns", f"place:{pid}", f"subject:{pid}", f"type:{pl['type']}"] + \
            ([f"region:{pl['region']}"] if pl.get("region") else [])
        views = [("top", [x, z, int(r * 2.6), 180, 89.5]),
                 ("oblique", [int(x + r * 1.1), int(z + r * 1.1), int(r * 1.3), 135, 38])]
        gates = _gates(pid)
        for n, (gx, gz) in enumerate(gates[:4]):
            dx, dz = gx - x, gz - z
            d = math.hypot(dx, dz) or 1
            ox, oz = gx + dx / d * 30, gz + dz / d * 30
            views.append((f"gate-{n}", [int(ox), int(oz), 3, _yaw_to(x - ox, z - oz), 4]))
        gx, gz = gates[0] if gates else (x, z + r)
        dx, dz = gx - x, gz - z
        d = math.hypot(dx, dz) or 1
        fx, fz = x + dx / d * r * 4, z + dz / d * r * 4
        views.append(("distance", [int(fx), int(fz), 28, _yaw_to(x - fx, z - fz), 6]))
        for view, visit in views:
            out.append({**base, "id": f"town-{view}-{pid}".replace("_", "-"), "title": f"{pid}: {view}",
                        "tags": tags + [f"view:{view}"], "pose": {"visit": visit},
                        "role": {"top": "front", "oblique": "side"}.get(view, "other")})
    return out


def _roads():
    """The road plan's segments (tools/qa/roadplan_lint.py's reader), loaded once."""
    if "segs" not in _ROADS:
        spec = importlib.util.spec_from_file_location("roadplan_lint", catalog.ROOT / "tools/qa/roadplan_lint.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        plan = mod.load(mod.DEFAULT)
        _ROADS["segs"] = [(mod.CLASSES[s["kind"]], s["x"], s["z"], s["h"]) for s in plan["segs"]]
    return _ROADS["segs"]


def project(receipt, size):
    """world (x, y, z) -> pixel (u, v) or None, through the capture's camera."""
    eye = receipt.get("eye") or [receipt["position"][0], receipt["position"][1] + 1.62, receipt["position"][2]]
    yaw, pitch = math.radians(receipt["yaw"]), math.radians(receipt["pitch"])
    f = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
    r = (f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0)  # forward x up
    rn = math.hypot(*r) or 1
    r = tuple(c / rn for c in r)
    u = (r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
    w, h = size
    tv = math.tan(math.radians(receipt.get("fov", 70)) / 2)
    th = tv * w / h

    def to_px(p):
        d = (p[0] - eye[0], p[1] - eye[1], p[2] - eye[2])
        zc = d[0] * f[0] + d[1] * f[1] + d[2] * f[2]
        if zc <= 0.5:
            return None
        xc = d[0] * r[0] + d[1] * r[1] + d[2] * r[2]
        yc = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
        return ((xc / (zc * th) + 1) / 2 * w, (1 - yc / (zc * tv)) / 2 * h)
    return to_px


def annotate(rec, png, out):
    """Draw the road plan near the town onto a copy of the capture (out); returns how many segments were drawn."""
    town = rec.get("town") or {}
    g = rec.get("game") or {}
    if not town or "yaw" not in g:
        return 0
    im = Image.open(png).convert("RGB")
    to_px = project(g, im.size)
    reach = town["radius"] * 6
    d = ImageDraw.Draw(im)
    drawn = 0
    for cls, xs, zs, hs in _roads():
        if min(abs(x - town["x"]) for x in xs[::8] + xs[-1:]) > reach and \
                min(abs(z - town["z"]) for z in zs[::8] + zs[-1:]) > reach:
            continue
        pts = [to_px((x, h + 0.3, z)) for x, z, h in zip(xs, zs, hs)
               if abs(x - town["x"]) < reach and abs(z - town["z"]) < reach]
        line, w = [], 5 if cls == "highway" else 4 if cls == "road" else 2
        for p in pts + [None]:
            if p is not None and -200 < p[0] < im.width + 200 and -200 < p[1] < im.height + 200:
                line.append(p)
            else:
                if len(line) > 1:
                    d.line(line, fill=COLOURS.get(cls, (255, 255, 255)), width=w)
                    drawn += 1
                line = []
    im.save(out)
    return drawn
