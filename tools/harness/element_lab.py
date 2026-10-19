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


def camera(box, dist, yaw_deg=30.0, pitch_deg=25.0, size=256):
    """A Minecraft camera looking at the box's centre from `dist` blocks, yaw / pitch in Minecraft's conventions (pitch
    > 0 looks down: an element seen from above, as a player sees the land around them), the fov narrowed so a
    `size`-pixel frame keeps 1080p's pixel size at fov 70."""
    c = [(box[0] + box[3] + 1) / 2, (box[1] + box[4] + 1) / 2, (box[2] + box[5] + 1) / 2]
    yaw, pitch = math.radians(yaw_deg), math.radians(pitch_deg)
    f = (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))
    eye = [c[i] - f[i] * dist for i in range(3)]
    fov = math.degrees(2 * math.atan(math.tan(math.radians(35)) * size / 1080))
    return {"camera": "mc", "eye": ",".join(f"{v:.3f}" for v in eye), "yaw": yaw_deg, "pitch": pitch_deg,
            "fov": f"{fov:.4f}", "w": size, "h": size}


def run(tool, args, out=None):
    cmd = [str(EXE / f"{tool}.exe")] + ([f"out={out}"] if out else []) + [f"{k}={v}" for k, v in args.items()]
    r = subprocess.run(cmd, capture_output=True, text=True, creationflags=0x00004000 if sys.platform == "win32" else 0)
    if r.returncode:
        raise RuntimeError(f"{tool} failed: {(r.stdout + r.stderr)[-400:]}")


# The reference's two modes (docs/ATLAS-LOD.md, "Reference modes"): "exact" is Minecraft as it renders (one sample a
# pixel: a sub-pixel tree is there or not, and flickers in motion), only for checking the reference against the game;
# "intended" is the target our renderer is scored against: 16 samples a pixel (true coverage: a far tree fades, it
# does not vanish), plus each documented fix of a vanilla quirk, one at a time.
REFERENCE = {"exact": {"spp": 1}, "intended": {"spp": 16}}
TRUTH_MODE = "intended"


def truth_inputs(el):
    d = export_dir()
    return {"quads": d / "quads.bin", "sprites": d / "sprites.bin", "volume": d / f"{el['volume']}.alv2",
            "box": ",".join(str(v) for v in el["box"])}


def bake_cards(el, tmp):
    """The element's surface cards (atlas_truth bake=): the real faces' colour and light, sampled by lab-groups."""
    out = tmp / f"{el['slug']}.alc"  # per element, and again whenever an input is newer (render() reuses a folder)
    ins = [Path(p) for k, p in truth_inputs(el).items() if k != "box"]
    if not out.exists() or any(p.stat().st_mtime > out.stat().st_mtime for p in ins if p.exists()):
        run("atlas_truth", {**truth_inputs(el), "bake": out.as_posix()})
    return out


def renderer_args(renderer, el, tmp):
    d = export_dir()
    if renderer == "truth":
        return "atlas_truth", {"camera": "mc", **truth_inputs(el), **REFERENCE[TRUTH_MODE]}
    lab = {"camera": "mc", "blocks": d / "blocks.bin", "volume": d / f"{el['volume']}.alv", "box": truth_inputs(el)["box"],
           "variants": "groups", "frames": 16, "shade": "mc"}
    if renderer == "lab-groups":  # the grouped-voxel representation on the CPU bench, coloured from surface cards;
        # leaves and plants whole voxels cut by their cards' alpha (masked face textures)
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp), "leaves": "cards"}
    if renderer == "lab-groups-hashed":  # the same with leaves as hashed micro-voxels (their 4 x 4 tiles' alpha)
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp)}
    if renderer == "lab-groups-own":  # the same with the bench's own 4 x 4 mean tiles under a fixed sun (no cache)
        return "atlas_lab", lab
    raise ValueError(f"unknown renderer {renderer}")


def render_all(renderer, el, frames, tmp):
    """Every (png, camera) of `frames` in one process: the mesh, textures, volume and trees loaded once."""
    lst = tmp / f"cameras-{renderer}.txt"
    lst.write_text("".join(f"{png.as_posix()} {c['eye']} {c['yaw']} {c['pitch']} {c['fov']} {c['w']} {c['h']}\n"
                           for png, c in frames), encoding="utf-8")
    tool, args = renderer_args(renderer, el, tmp)
    run(tool, {**args, "cameras": lst.as_posix()})
    missing = [png for png, _ in frames if not png.exists()]
    if missing:
        raise RuntimeError(f"{tool} wrote no {missing[0].name}")


def render(renderer, el, cam, out):  # one frame (probes, debugging)
    render_all(renderer, el, [(Path(out), cam)], Path(out).parent)


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
            "pixels": int(sel.sum()),
            "presence": round(float(mb.sum() / ma.sum()), 3) if ma.sum() > 0 else (0.0 if mb.sum() > 0 else 1.0),
            "mass": round(float(ma.sum()), 3), "massOurs": round(float(mb.sum()), 3)}


def radius(box):
    return 0.5 * math.sqrt(sum((box[k + 3] - box[k] + 1) ** 2 for k in range(3)))


def flyin_frames(el, start, end, steps, tmp, name, size):
    near = max(end, 1.5 * radius(el["box"]))
    dists = [start * (near / start) ** (i / (steps - 1)) for i in range(steps)]
    return [(tmp / f"fly-{name}-{r}-{i}.png", camera(el["box"], d, size=size)) for i, d in enumerate(dists)
            for r in ("x",)]


def pop(renderer, frames_truth, frames_ours):
    """Frame-to-frame change of ours beyond the reference's own along an approach: popping or shimmer."""
    excess, prev = [], None
    for (pt, _), (po, _) in zip(frames_truth, frames_ours):
        cur = (lab(load(pt)[0]), lab(load(po)[0]))
        if prev:
            ct = float(np.sqrt(((cur[0] - prev[0]) ** 2).sum(axis=2)).mean())
            co = float(np.sqrt(((cur[1] - prev[1]) ** 2).sum(axis=2)).mean())
            excess.append(max(0.0, co - ct))
        prev = cur
    return {"pop": round(float(np.mean(excess)), 3) if excess else 0.0, "popMax": round(float(max(excess)), 3) if excess else 0.0,
            "steps": [round(e, 3) for e in excess]}


# an element fails below this silhouette agreement, above this popping, or (a dot of under SHAPE_PX pixels) with its
# coverage off the reference's by more than presence (either way)
LIMITS = {"iou": 0.9, "pop": 0.1, "presence": 0.75}
SHAPE_PX = 16
MASS_PX = 0.25  # pixels of coverage: a dot's mass is measured to about this (16 samples, sub-pixel placement)


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
    near = 1.5 * radius(el["box"])  # never from inside or against the element: not a view anyone has of it
    dists = [d for d in (int(x) for x in a.distances.split(",")) if d >= near] or [int(near)]
    report = {"element": el, "renderer": a.renderer, "truth": TRUTH_MODE, "distances": []}
    rows = []
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        ladder = {r: [(tmp / f"{r}-{d}.png", camera(el["box"], d, size=a.size)) for d in dists] for r in ("truth", a.renderer)}
        fly = {}
        for name, (st, en, n) in FLYINS.items():
            base = flyin_frames(el, st, en, n, tmp, name, min(a.size, 160))
            fly[name] = {r: [(tmp / f"fly-{name}-{r}-{i}.png", c) for i, (_, c) in enumerate(base)] for r in ("truth", a.renderer)}
        for r in ("truth", a.renderer):  # one process per renderer: the ladder and every fly-in frame
            render_all(r, el, ladder[r] + [f for name in fly for f in fly[name][r]], tmp)
        for (ref, _), (ours, _), dist in zip(ladder["truth"], ladder[a.renderer], dists):
            m = compare(ref, ours)
            report["distances"].append({"dist": dist, **m})
            print(f"{dist:>7} m  iou {m['iou']:.3f}  dE {m['dE']:6.2f}  presence {m['presence']:.2f}  ({m['pixels']} px)")
            rows.append((Image.open(ref).convert("RGB"), Image.open(ours).convert("RGB")))
        for name in fly:
            report[f"flyin_{name}"] = pop(a.renderer, fly[name]["truth"], fly[name][a.renderer])
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
    fails = []
    # a silhouette needs pixels to have a shape: below SHAPE_PX the element is a dot whose IoU is sub-pixel placement,
    # and what matters is that it is there as much as the reference (its coverage mass)
    shaped = [d for d in report["distances"] if d["pixels"] >= SHAPE_PX]
    if shaped and min(d["iou"] for d in shaped) < LIMITS["iou"]:
        bad = min(shaped, key=lambda d: d["iou"])
        fails.append(f"silhouette {bad['iou']:.3f} < {LIMITS['iou']} at {bad['dist']} m")
    for d in report["distances"]:  # within presence of the reference's coverage, or MASS_PX (its sampling's grain)
        off = abs(d["massOurs"] - d["mass"])
        if d["pixels"] < SHAPE_PX and off > max((1 - LIMITS["presence"]) * d["mass"], MASS_PX):
            fails.append(f"presence {d['massOurs']:.2f} px of coverage vs the reference's {d['mass']:.2f} at {d['dist']} m")
    for name in FLYINS:
        if report[f"flyin_{name}"]["pop"] > LIMITS["pop"]:
            fails.append(f"fly-in {name} pops: {report[f'flyin_{name}']['pop']} > {LIMITS['pop']}")
    report["fails"] = fails
    print(json.dumps(report))
    for f in fails:
        print("FAIL " + f)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
