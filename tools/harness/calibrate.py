"""Projection calibration: is a world point drawn where the game drew it?

The scene `calibrate-projection` puts red concrete markers at known positions on the stage and captures them from
an oblique camera; `check` projects each marker's centre through the capture's receipt (towns.project: eye, yaw,
pitch, field of view) and finds the red blob nearest to it in the picture. The pixel error says whether overlays
(the road highlights on town views) can be trusted; a consistent scale error points at the field of view.
"""
import math

import numpy as np
from PIL import Image

import catalog
import towns

TOL_PX = 6


def markers():
    sx, sy, sz = catalog.STAGE
    return [(sx - 3, sy + 1, sz - 2), (sx + 3, sy + 1, sz + 1), (sx, sy + 1, sz + 3)]


def scene():
    sx, sy, sz = catalog.STAGE
    # on the stage pad (built by the stage pose), then an oblique camera onto the markers
    setup = [f"command setblock {x} {y} {z} minecraft:red_concrete" for x, y, z in markers()] + [
        f"camera {sx + 7} {sy + 5} {sz + 9} {sx} {sy + 1} {sz}", "settle-visual 600"]
    return {"id": "calibrate-projection", "title": "Projection calibration: red markers at known positions",
            "_file": "(generated calibration)", "tier": "live", "kind": "visual", "variants": ["shaders"],
            "tags": ["suite:validate-tools", "check:projection", "suite:smoke"], "time": 6000, "weather": "clear",
            "pose": {"stage": [sx, sy, sz, 6]}, "setup": setup,
            "cleanup": [f"try setblock {x} {y} {z} minecraft:air" for x, y, z in markers()],
            "calibrate": [list(m) for m in markers()], "checks": {}, "source": "tools/harness/calibrate.py"}


def _blobs(img):
    """Centres of red blobs (strongly red pixels)."""
    a = np.asarray(img.convert("RGB"), dtype=np.int16)
    mask = (a[..., 0] > 120) & (a[..., 0] > a[..., 1] * 2) & (a[..., 0] > a[..., 2] * 2)
    ys, xs = np.nonzero(mask)
    return xs, ys


def check(rec, png):
    """{"errors": [px per marker], "max": px, "scale": detected / projected distance from centre} or a reason."""
    g = rec.get("game") or {}
    if "yaw" not in g:
        return {"error": "no camera receipt"}
    img = Image.open(png)
    to_px = towns.project(g, img.size)
    xs, ys = _blobs(img)
    if len(xs) == 0:
        return {"error": "no red markers found in the picture"}
    # compare outlines, not centroids: a block's visible red is mostly its lit top face (its shaded sides can fall
    # below the red threshold), so the projected 8-corner box is matched to the blob's box on the top, left and
    # right edges, which the picture always shows
    errors, ratios = [], []
    cx, cy = img.width / 2, img.height / 2
    for m in rec.get("calibrate", []):
        cs = [to_px((m[0] + a, m[1] + b, m[2] + c)) for a in (0, 1) for b in (0, 1) for c in (0, 1)]
        if any(c is None for c in cs):
            errors.append(None)
            continue
        px, py = [c[0] for c in cs], [c[1] for c in cs]
        pc = ((min(px) + max(px)) / 2, (min(py) + max(py)) / 2)
        near = ((xs - pc[0]) ** 2 + (ys - pc[1]) ** 2) < 80 ** 2
        if not near.any():
            errors.append(None)
            continue
        bx0, by0, bx1 = xs[near].min(), ys[near].min(), xs[near].max()
        errors.append(round(max(abs(bx0 - min(px)), abs(by0 - min(py)), abs(bx1 - max(px))), 1))
        pd = math.hypot(pc[0] - cx, pc[1] - cy)
        if pd > 20:
            ratios.append(math.hypot((bx0 + bx1) / 2 - cx, by0 - cy) / max(1, math.hypot(pc[0] - cx, min(py) - cy)))
    found = [e for e in errors if e is not None]
    return {"errors": errors, "max": max(found) if found else None,
            "scale": round(sum(ratios) / len(ratios), 3) if ratios else None}
