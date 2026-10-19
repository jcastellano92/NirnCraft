"""Element tests, offline: one element (a chunk, a tree, ...) cut from an export, rendered by the exact reference
(atlas_truth: Minecraft's own mesh) and by our far-field renderer at distances from a few metres to tens of km and
along fly-ins, compared per distance (does it represent the real blocks) and per step (does it resolve smoothly).

The first tier of renderer verification (docs/ATLAS-LOD.md, "What each comparison means"): seconds per element, no
game. The frame is small (256 x 256 by default) but its pixels are the game's at 1080p (fov 70), so what a pixel holds
at each distance is what a player would see.

    py -3 tools/harness/element_lab.py <element-slug> [--renderer lab-groups] [--out DIR] [--size 256]
    py -3 tools/harness/element_lab.py --list

Prints one JSON line (the last) with per-distance dE / IoU and the fly-ins' pop; writes a sheet PNG to --out.
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/harness"))
sys.path.insert(0, str(ROOT / "tools/dev"))

DISTANCES = [8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536]
FLYINS = {"fast": (65536, 32, 24), "slow": (2048, 16, 32)}  # from, to, steps (geometric)
EXE = ROOT / "native/build/Release"


def export_dir():
    import live_world as lw
    return lw.GAME / "nirncraft-atlaslab"


def read_alv(path):
    d = Path(path).read_bytes()
    x0, y0, z0, sx, sy, sz = struct.unpack("<6i", d[4:28])
    ids = np.frombuffer(d[28:28 + 4 * sx * sy * sz], dtype="<u4").reshape(sy, sz, sx)
    return (x0, y0, z0), ids


def block_names():
    b = (export_dir() / "blocks.bin").read_bytes()
    n = struct.unpack("<I", b[4:8])[0]
    p, names = 8, {}
    for _ in range(n):
        sid, nl = struct.unpack("<IH", b[p:p + 6])
        p += 6
        names[sid] = b[p:p + nl].decode()
        p += nl + 4 + 8 + 4 + 6 * 16 * 4
    return names


def find_elements(volume_slug):
    """Elements in one export: the chunk at its centre, and its biggest tree (connected logs and leaves)."""
    origin, ids = read_alv(export_dir() / f"{volume_slug}.alv")
    x0, y0, z0 = origin
    sy, sz, sx = ids.shape
    out = []
    cx, cz = x0 + sx // 2, z0 + sz // 2
    c0x, c0z = cx - (cx % 16), cz - (cz % 16)
    nonair = np.argwhere(ids[:, max(0, c0z - z0):c0z - z0 + 16, max(0, c0x - x0):c0x - x0 + 16] != 0)
    if len(nonair):
        out.append({"slug": f"{volume_slug}-chunk", "volume": volume_slug, "kind": "chunk",
                    "box": [c0x, y0 + int(nonair[:, 0].min()), c0z, c0x + 15, y0 + int(nonair[:, 0].max()), c0z + 15]})
    names = block_names()
    wood = {i for i, n in names.items() if "_log" in n or "_wood" in n}
    leaves = {i for i, n in names.items() if "leaves" in n}
    tree_ids = np.isin(ids, list(wood | leaves))
    logs = np.argwhere(np.isin(ids, list(wood)))
    best = None
    seen = np.zeros_like(tree_ids)
    for y, z, x in logs[:: max(1, len(logs) // 400)]:
        if seen[y, z, x]:
            continue
        stack, cells = [(y, z, x)], []
        seen[y, z, x] = True
        while stack and len(cells) < 4000:
            p = stack.pop()
            cells.append(p)
            for dy, dz, dx in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                q = (p[0] + dy, p[1] + dz, p[2] + dx)
                if 0 <= q[0] < sy and 0 <= q[1] < sz and 0 <= q[2] < sx and tree_ids[q] and not seen[q]:
                    seen[q] = True
                    stack.append(q)
        c = np.array(cells)
        ext = c.max(axis=0) - c.min(axis=0)
        if 4 <= ext[0] and ext[1] <= 24 and ext[2] <= 24 and (best is None or len(cells) > best[0]):
            best = (len(cells), c.min(axis=0), c.max(axis=0))
    if best:
        lo, hi = best[1], best[2]
        out.append({"slug": f"{volume_slug}-tree", "volume": volume_slug, "kind": "tree",
                    "box": [x0 + int(lo[2]), y0 + int(lo[0]) - 1, z0 + int(lo[1]), x0 + int(hi[2]), y0 + int(hi[0]),
                            z0 + int(hi[1])]})
    return out


def elements():
    """Every element of every export there is (the element list the offline and live tiers share)."""
    out = []
    for f in sorted(export_dir().glob("*.alv")):
        if f.with_suffix(".alv2").exists():
            out += find_elements(f.stem)
    return out


def camera(box, dist, yaw_deg=30.0, pitch_deg=-15.0, size=256):
    """A Minecraft camera looking at the box's centre from `dist` blocks, yaw / pitch in Minecraft's conventions, the
    fov narrowed so a `size`-pixel frame keeps 1080p's pixel size at fov 70."""
    c = [(box[0] + box[3] + 1) / 2, (box[1] + box[4] + 1) / 2, (box[2] + box[5] + 1) / 2]
    yaw, pitch = math.radians(yaw_deg), math.radians(pitch_deg)
    f = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
    eye = [c[i] - f[i] * dist for i in range(3)]
    fov = math.degrees(2 * math.atan(math.tan(math.radians(35)) * size / 1080))
    return {"camera": "mc", "eye": ",".join(f"{v:.3f}" for v in eye), "yaw": yaw_deg, "pitch": pitch_deg,
            "fov": f"{fov:.4f}", "w": size, "h": size}


def run(tool, args, out):
    cmd = [str(EXE / f"{tool}.exe"), f"out={out}"] + [f"{k}={v}" for k, v in args.items()]
    r = subprocess.run(cmd, capture_output=True, text=True, creationflags=0x00004000 if sys.platform == "win32" else 0)
    if r.returncode or not Path(out).exists():
        raise RuntimeError(f"{tool} failed: {(r.stdout + r.stderr)[-400:]}")


# The reference's two modes (docs/ATLAS-LOD.md, "Reference modes"): "exact" is Minecraft as it renders (one sample a
# pixel: a sub-pixel tree is there or not, and flickers in motion), only for checking the reference against the game;
# "intended" is the target our renderer is scored against: 16 samples a pixel (true coverage: a far tree fades, it
# does not vanish), plus each documented fix of a vanilla quirk, one at a time.
REFERENCE = {"exact": {"spp": 1}, "intended": {"spp": 16}}
TRUTH_MODE = "intended"


def render(renderer, el, cam, out):
    d = export_dir()
    box = ",".join(str(v) for v in el["box"])
    if renderer == "truth":
        run("atlas_truth", {**cam, "quads": d / "quads.bin", "sprites": d / "sprites.bin",
                            "volume": d / f"{el['volume']}.alv2", "box": box, **REFERENCE[TRUTH_MODE]}, out)
    elif renderer == "lab-groups":  # the grouped-voxel representation on the CPU bench (until the far pass lands)
        run("atlas_lab", {**cam, "blocks": d / "blocks.bin", "volume": d / f"{el['volume']}.alv", "box": box,
                          "variants": "groups", "frames": 4, "shade": "mc"}, out)
    else:
        raise ValueError(f"unknown renderer {renderer}")


def load(png):
    a = np.asarray(Image.open(png).convert("RGB"), dtype=np.float32)
    m = Path(str(png)[:-4] + "_mask.png")
    mask = np.asarray(Image.open(m).convert("L"), dtype=np.float32) / 255.0 if m.exists() else (a.sum(axis=2) > 0) * 1.0
    return a, mask


def lab(img):
    import results
    return np.asarray(results._lab(Image.fromarray(img.astype(np.uint8)), w=img.shape[1]), dtype=np.float32)


def compare(ref_png, our_png):
    """Silhouette IoU of the coverage masks, mean dE over where either shows the element."""
    a, ma = load(ref_png)
    b, mb = load(our_png)
    inter, union = np.minimum(ma, mb).sum(), np.maximum(ma, mb).sum()
    sel = np.maximum(ma, mb) > 0.05
    de = np.sqrt(((lab(a) - lab(b)) ** 2).sum(axis=2))
    return {"iou": round(float(inter / union), 4) if union > 0 else 1.0,
            "dE": round(float(de[sel].mean()), 2) if sel.any() else 0.0,
            "pixels": int(sel.sum())}


def flyin(renderer, el, start, end, steps, tmp, size):
    """Frame-to-frame change of ours beyond the reference's own along a geometric approach: popping or shimmer."""
    prev = {}
    excess = []
    for i in range(steps):
        dist = start * (end / start) ** (i / (steps - 1))
        cam = camera(el["box"], dist, size=size)
        cur = {}
        for r in ("truth", renderer):
            p = tmp / f"fly-{r}-{i}.png"
            render(r, el, cam, p)
            cur[r] = lab(load(p)[0])
        if prev:
            ch = {r: float(np.sqrt(((cur[r] - prev[r]) ** 2).sum(axis=2)).mean()) for r in cur}
            excess.append(max(0.0, ch[renderer] - ch["truth"]))
        prev = cur
    return {"pop": round(float(np.mean(excess)), 3) if excess else 0.0, "popMax": round(float(max(excess)), 3) if excess else 0.0}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("element", nargs="?")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--renderer", default="lab-groups")
    ap.add_argument("--out", type=Path, default=None)
    ap.add_argument("--size", type=int, default=256)
    ap.add_argument("--truth", choices=sorted(REFERENCE), default="intended",
                    help="the reference's mode: intended (scores our renderer) or exact (Minecraft as it renders)")
    ap.add_argument("--distances", default=",".join(str(d) for d in DISTANCES))
    a = ap.parse_args()
    els = elements()
    if a.list or not a.element:
        for e in els:
            print(e["slug"], e["kind"], e["box"])
        return 0
    el = next((e for e in els if e["slug"] == a.element), None)
    if not el:
        print(f"element_lab: no element {a.element} (--list)")
        return 2
    global TRUTH_MODE
    TRUTH_MODE = a.truth
    dists = [int(x) for x in a.distances.split(",")]
    rows, report = [], {"element": el, "renderer": a.renderer, "distances": []}
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        for dist in dists:
            cam = camera(el["box"], dist, size=a.size)
            ref, ours = tmp / f"truth-{dist}.png", tmp / f"ours-{dist}.png"
            render("truth", el, cam, ref)
            render(a.renderer, el, cam, ours)
            m = compare(ref, ours)
            report["distances"].append({"dist": dist, **m})
            print(f"{dist:>7} m  iou {m['iou']:.3f}  dE {m['dE']:6.2f}  ({m['pixels']} px)")
            rows.append((Image.open(ref).convert("RGB"), Image.open(ours).convert("RGB")))
        for name, (s, e, n) in FLYINS.items():
            report[f"flyin_{name}"] = flyin(a.renderer, el, s, e, n, tmp, min(a.size, 160))
            print(f"fly-in {name}: pop {report[f'flyin_{name}']['pop']}  max {report[f'flyin_{name}']['popMax']}")
    if a.out:
        a.out.mkdir(parents=True, exist_ok=True)
        cell = 128
        sheet = Image.new("RGB", (cell * 2 + 8, cell * len(rows)), (20, 20, 24))
        for i, (r, o) in enumerate(rows):
            sheet.paste(r.resize((cell, cell), Image.NEAREST), (0, i * cell))
            sheet.paste(o.resize((cell, cell), Image.NEAREST), (cell + 8, i * cell))
        sheet.save(a.out / f"{el['slug']}.png")
    worst = max(report["distances"], key=lambda d: d["dE"])
    report["worst"] = {"dist": worst["dist"], "dE": worst["dE"], "iou": min(d["iou"] for d in report["distances"])}
    print(json.dumps(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
