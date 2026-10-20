"""Element tests, offline: one element (a chunk, a tree, ...) cut from an export, rendered by the exact reference
(atlas_truth: Minecraft's own mesh) and by our far-field renderer at distances from a few metres to tens of km and
along fly-ins, compared per distance (does it represent the real blocks) and per step (does it resolve smoothly).

The first tier of renderer verification (docs/ATLAS-LOD.md, "What each comparison means"): seconds per element, no
game. The frame is small (256 x 256 by default) but its pixels are the game's at 1080p (fov 70), so what a pixel holds
at each distance is what a player would see.

    py -3 tools/harness/element_lab.py <element-slug> [--renderer lab-groups] [--out DIR] [--size 256]
                                       [--truth intended|exact|realistic] [--time TICKS]
    py -3 tools/harness/element_lab.py --list

--truth realistic scores against the shader pack's light ray traced on the real mesh (atlas_truth lighting=realistic:
sun or moon with ray traced shadows, sky visibility, shadowed block lights, the pack's tonemap) at --time (6000 day,
12500 dusk, 18000 night); the renderers are lit the same way: lab-groups and far-groups sample surface cards baked
with that light (the representation under realistic light), far-lit is atlas_core's own lighting (its sun shadows,
sky ambient, AO and far lights on the surfaces' albedo cards) through the same tonemap (where its light falls short);
far-lit-game is the same light on the game's own far materials (no cards): far-lit against it is the materials alone.

Prints one JSON line (the last) with per-distance dE / IoU and the fly-ins' pop; writes a sheet PNG to --out.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import struct
import subprocess
import sys
import tempfile
import time
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
    # featured chunks: per kind the chunk of the export with the most of its blocks (when it has enough of them)
    classes = block_classes()
    picked = {tuple(e["box"]) for e in out}
    air_ids = [i for i, n in names.items() if n.startswith(("Block{minecraft:air}", "Block{minecraft:cave_air}"))]
    solid = ~np.isin(ids, air_ids)
    top = np.where(solid.any(axis=0), sy - 1 - np.argmax(solid[::-1], axis=0), -1)  # (z, x): each column's top block
    for kind, want, least in FEATURED:
        sel = np.isin(ids, [i for i, n in names.items() if want(n, classes.get(i, 5))])
        if kind == "water":  # open water: only where water is the column's top (a lake, the sea), not an aquifer
            yy = np.arange(sy)[:, None, None]
            sel &= yy == top[None, :, :]
        best = None
        cols = sel.sum(axis=0)  # per column; 16 x 16 windows every 4 blocks: the feature in the box's middle
        for gz in range(0, sz - 15, 4):
            for gx in range(0, sx - 15, 4):
                n = int(cols[gz:gz + 16, gx:gx + 16].sum())
                if n >= least and (best is None or n > best[0]):
                    best = (n, gx, gz)
        if not best:
            continue
        _, gx, gz = best
        col = np.argwhere(ids[:, gz:gz + 16, gx:gx + 16] != 0)
        feat = np.argwhere(sel[:, gz:gz + 16, gx:gx + 16])
        # from a few blocks under the feature to the column's top: the export reaches 32 blocks down, and a water
        # element was a dark cube of underground stone with a sliver of lake on it
        lo = max(int(col[:, 0].min()), int(feat[:, 0].min()) - 4)
        box = [x0 + gx, y0 + lo, z0 + gz, x0 + gx + 15, y0 + int(col[:, 0].max()), z0 + gz + 15]
        if tuple(box) not in picked:
            picked.add(tuple(box))
            out.append({"slug": f"{volume_slug}-{kind}", "volume": volume_slug, "kind": kind, "box": box})
        if kind == "building":
            # a town: the blocks around the building chunk too (48 x 48), where buildings shade one another and the
            # ground between them (sun and moon shadows of casters larger than a chunk, docs/ATLAS-LOD.md)
            tx0, tz0 = max(0, gx - 16), max(0, gz - 16)
            tx1, tz1 = min(sx, gx + 32), min(sz, gz + 32)
            tops = top[tz0:tz1, tx0:tx1]
            if tx1 - tx0 >= 40 and tz1 - tz0 >= 40 and (tops >= 0).any():
                lo = max(0, int(tops[tops >= 0].min()) - 4)
                tb = [x0 + tx0, y0 + lo, z0 + tz0, x0 + tx1 - 1, y0 + int(tops.max()), z0 + tz1 - 1]
                if tuple(tb) not in picked:
                    picked.add(tuple(tb))
                    out.append({"slug": f"{volume_slug}-town", "volume": volume_slug, "kind": "town", "box": tb})
    # relief: the chunk whose ground rises most across it (a ridge, a cliff, a terrace: land that casts shadows on
    # itself at a low sun), when it rises RELIEF_LEAST blocks or more
    best = None
    for gz in range(0, sz - 15, 4):
        for gx in range(0, sx - 15, 4):
            w = top[gz:gz + 16, gx:gx + 16]
            if (w < 0).any():
                continue
            rise = int(w.max() - w.min())
            if rise >= RELIEF_LEAST and (best is None or rise > best[0]):
                best = (rise, gx, gz, int(w.min()), int(w.max()))
    if best:
        _, gx, gz, lo, hi = best
        box = [x0 + gx, y0 + max(0, lo - 4), z0 + gz, x0 + gx + 15, y0 + hi, z0 + gz + 15]
        if tuple(box) not in picked:
            out.append({"slug": f"{volume_slug}-relief", "volume": volume_slug, "kind": "relief", "box": box})
    return out


RELIEF_LEAST = 12


BUILT = ("planks", "brick", "glass", "_door", "fence", "_stairs", "_slab", "wool", "terracotta", "trapdoor", "bookshelf",
         "barrel", "_bed", "lantern", "chain", "_wall")
# (kind, does a block state (its name, its class in blocks.bin) count, how many a chunk needs)
FEATURED = [
    ("water", lambda n, c: n.startswith("Block{minecraft:water}"), 48),  # columns of open water (see find_elements)
    ("lights", lambda n, c: c == 3, 3),  # emissive (light 10 and up): lanterns, torches, fires, glowstone
    ("building", lambda n, c: any(k in n for k in BUILT), 96),
    ("custom", lambda n, c: not n.startswith("Block{minecraft:"), 8),  # NirnCraft's own blocks, models and textures
]


def block_classes():
    """blocks.bin's class per state id (0 solid, 1 foliage, 2 water, 3 emissive, 4 plant, 5 other)."""
    b = (export_dir() / "blocks.bin").read_bytes()
    n = struct.unpack("<I", b[4:8])[0]
    p, cls = 8, {}
    for _ in range(n):
        sid, nl = struct.unpack("<IH", b[p:p + 6])
        p += 6 + nl
        cls[sid] = b[p]
        p += 4 + 8 + 4 + 6 * 16 * 4
    return cls


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
    env = dict(os.environ)
    # the far field drops real sections a few regions from the camera, as in game: atlas_render sends them as the
    # game's server does (its sections window around the camera, again on return; atlas_render server=1), and what is
    # drawn from farther away comes from the coarse levels the core kept of them (docs/ATLAS-LOD.md, "Coarse levels
    # from real blocks"). (It once kept every section for the whole run, ATLAS_SECTION_KEEP=320: no game does.)
    env.pop("ATLAS_SECTION_KEEP", None)
    r = subprocess.run(cmd, capture_output=True, text=True, env=env, creationflags=0x00004000 if sys.platform == "win32" else 0)
    if r.returncode:
        raise RuntimeError(f"{tool} failed: {(r.stdout + r.stderr)[-400:]}")
    return r


def wrote_variant(log, png, variant):
    """Whether atlas_lab's log says frame `png` was rendered by `variant` ("camera frame <v> WxH in N ms -> <path>")."""
    want = str(Path(png)).replace("\\", "/").lower()
    for line in log.splitlines():
        m = re.match(r"atlas_lab: camera frame (\S+) \d+x\d+ in \d+ ms -> (.+)$", line.strip())
        if m and m.group(2).replace("\\", "/").lower() == want:
            return m.group(1) == variant
    return False


# The reference's two modes (docs/ATLAS-LOD.md, "Reference modes"): "exact" is Minecraft as it renders (one sample a
# pixel: a sub-pixel tree is there or not, and flickers in motion), only for checking the reference against the game;
# "intended" is the target our renderer is scored against: 16 samples a pixel (true coverage: a far tree fades, it
# does not vanish), plus each documented fix of a vanilla quirk, one at a time.
REFERENCE = {"exact": {"spp": 1}, "intended": {"spp": 16},
             # the shader pack's light, physically placed (docs/ATLAS-LOD.md, "A realistic reference"): 16 samples a
             # pixel, each with a sun / moon shadow ray, 4 sky rays and a ray per block light in reach
             "realistic": {"spp": 16, "lighting": "realistic"}}
TRUTH_MODE = "intended"
TIME = None  # --time: the day time (ticks) the reference is lit at (realistic: 6000 when not given)


def realistic():
    return TRUTH_MODE == "realistic"


def day_time():
    return 6000 if TIME is None else TIME


_INFO = {}


def light_info():
    """The realistic light of the time of day (atlas_truth info=1): sun, key light, exposure, the background sky."""
    t = day_time()
    if t not in _INFO:
        r = subprocess.run([str(EXE / "atlas_truth.exe"), "info=1", f"time={t}"], capture_output=True, text=True)
        if r.returncode:
            raise RuntimeError(f"atlas_truth info failed: {r.stderr[-300:]}")
        _INFO[t] = json.loads(r.stdout.strip().splitlines()[-1])
    return _INFO[t]


def truth_inputs(el):
    d = export_dir()
    return {"quads": d / "quads.bin", "sprites": d / "sprites.bin", "volume": d / f"{el['volume']}.alv2",
            "box": ",".join(str(v) for v in el["box"])}


def card_path(el, tmp, kind="vanilla"):
    """An element's cards: per element and box (a changed box reused the old cards) and per light (vanilla, or the
    realistic / albedo bake at the time of day), baked again whenever an input is newer (render() reuses a folder)."""
    base = f"{el['slug']}-{'_'.join(str(v) for v in el['box']).replace('-', 'm')}"
    return Path(tmp) / (f"{base}.alc" if kind == "vanilla" else f"{base}-{kind}-{day_time()}.alc")


def bake_cards(el, tmp, kind=None):
    """The element's surface cards (atlas_truth bake=): the real faces' colour and light, sampled by lab-groups. kind:
    vanilla (Minecraft's light), realistic (the reference's light at the time of day: the default under --truth
    realistic) or albedo (the faces' own colour, for a renderer that lights them itself)."""
    kind = kind or ("realistic" if realistic() else "vanilla")
    out = card_path(el, tmp, kind)
    ins = [Path(p) for k, p in truth_inputs(el).items() if k != "box"]
    if not out.exists() or any(p.stat().st_mtime > out.stat().st_mtime for p in ins if p.exists()):
        extra = {} if kind == "vanilla" else {"lighting": kind, "time": day_time(), "classes": export_dir() / "blocks.bin"}
        if TIME is not None and kind == "vanilla":
            extra = {"time": TIME}
        run("atlas_truth", {**truth_inputs(el), **extra, "bake": out.as_posix()})
    return out


def renderer_args(renderer, el, tmp):
    d = export_dir()
    if renderer == "truth":
        args = {"camera": "mc", **truth_inputs(el), **REFERENCE[TRUTH_MODE]}
        if realistic():
            args.update({"time": day_time(), "classes": d / "blocks.bin"})
        elif TIME is not None:
            args["time"] = TIME
        return "atlas_truth", args
    sky = {"sky": light_info()["sky"]} if realistic() else {}  # the realistic reference's background
    lab = {"camera": "mc", "blocks": d / "blocks.bin", "volume": d / f"{el['volume']}.alv", "box": truth_inputs(el)["box"],
           "variants": "groups", "frames": 16, "shade": "mc", **sky}
    if renderer == "lab-groups":  # the grouped-voxel representation on the CPU bench, coloured from surface cards;
        # leaves and plants whole voxels cut by their cards' alpha (masked face textures)
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp), "leaves": "cards"}
    if renderer in ("far-groups", "far-old"):  # the far field itself (atlas_core through atlas_render): its groups
        # (ATLAS_FRAME_GROUPS) or the path before them, flat world (radius 0), coloured from the same surface cards
        return "atlas_render", {"camera": "mc", "blocks": d / "blocks.bin", "volume": d / f"{el['volume']}.alv",
                                "box": truth_inputs(el)["box"], "surface": bake_cards(el, tmp), "shade": "mc",
                                "spp": 16, "radius": 0, "groups": 1 if renderer == "far-groups" else 0, **sky}
    if renderer in ("far-game", "far-game-lit"):  # the far field as the game draws it without a shader pack: its
        # groups, its own materials (no cards: the game sends none), FRAME_VANILLA_LIGHT (AtlasClient); far-game-lit:
        # its own light, shadows and haze as before (the stark line's A/B); scored against Minecraft itself, so no
        # clouds (the reference has none; the far fly-in camera passes above the deck)
        return "atlas_render", {"camera": "mc", "blocks": d / "blocks.bin", "volume": d / f"{el['volume']}.alv",
                                "box": truth_inputs(el)["box"], "shade": "game" if renderer == "far-game" else "lit",
                                "flags": "shadows,atmos,sky,actors", "spp": 16, "radius": 0, "groups": 1}
    if renderer in ("far-lit", "far-lit-game"):  # the far field's own light (atlas_core: its sun / moon and shadows, sky
        # ambient, AO, far lights) on the faces' albedo (cards), through the reference's tonemap: where its lighting falls
        # short; far-lit-game: the same light on the game's own materials (no cards): far-lit against it is the materials
        if not realistic():
            raise ValueError("far-lit is scored against the realistic reference: --truth realistic")
        info = light_info()
        cards = {"surface": bake_cards(el, tmp, "albedo")} if renderer == "far-lit" else {}
        return "atlas_render", {"camera": "mc", "blocks": d / "blocks.bin", "volume": d / f"{el['volume']}.alv",
                                "box": truth_inputs(el)["box"], **cards, "shade": "pack",
                                "flags": "shadows", "sun": f"{info['sunElevation']:.4f},{info['sunAzimuth']:.4f}",
                                "exposure": f"{info['exposure']:.5f}", "nv": f"{info['nightVision']:.3f}",
                                "skyhdr": ",".join(f"{v:.6f}" for v in info["skyHdr"]), "lights": 1,
                                "spp": 16, "radius": 0, "groups": 1}
    if renderer.startswith("lab-groups-bias"):  # (a probe) cards sampled this many mip levels blurrier: lab-groups-bias0.5
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp), "leaves": "cards", "mipbias": renderer[len("lab-groups-bias"):]}
    if renderer == "lab-groupsf":  # groups with prefiltered face textures (the footprint's average, holes as coverage)
        return "atlas_lab", {**lab, "variants": "groupsf", "surface": bake_cards(el, tmp), "leaves": "cards"}
    if renderer.startswith("lab-groups-frames"):  # (a probe) more samples a pixel: lab-groups-frames64
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp), "leaves": "cards", "frames": renderer[len("lab-groups-frames"):]}
    if renderer == "lab-groups-finest":  # (a probe) every group descended to its leaves: no level transitions at all
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp), "leaves": "cards", "tol": 0.0001}
    if renderer == "lab-groups-hashed":  # the same with leaves as hashed micro-voxels (their 4 x 4 tiles' alpha)
        return "atlas_lab", {**lab, "surface": bake_cards(el, tmp)}
    if renderer == "lab-groups-own":  # the same with the bench's own 4 x 4 mean tiles under a fixed sun (no cache)
        return "atlas_lab", lab
    raise ValueError(f"unknown renderer {renderer}")


BACKENDS = set()  # which atlas_core backends rendered this run's frames (the report says)
FORCE_BACKEND = None  # --backend


def gpu_busy():
    """The GPU's utilisation in percent (0 when nvidia-smi is missing: no NVIDIA GPU, the CPU backend decides then)."""
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=10).stdout
        return max(int(x) for x in out.split())
    except (OSError, ValueError, subprocess.SubprocessError):
        return -1


def backend():
    """atlas_core's backend for the far field's frames: Vulkan when the GPU is mostly idle (the game, or another
    agent's timing, would otherwise share it), else the CPU backend (its pictures are held to Vulkan's by the native
    parity tests). It had always been the CPU: atlas_render's default, nothing set it."""
    if FORCE_BACKEND:
        return FORCE_BACKEND
    busy = gpu_busy()
    return "vulkan" if 0 <= busy < 40 else "cpu"


def render_all(renderer, el, frames, tmp):
    """Every (png, camera) of `frames` in one process: the mesh, textures, volume and trees loaded once."""
    lst = tmp / f"cameras-{renderer}.txt"
    lst.write_text("".join(f"{png.as_posix()} {c['eye']} {c['yaw']} {c['pitch']} {c['fov']} {c['w']} {c['h']}\n"
                           for png, c in frames), encoding="utf-8")
    tool, args = renderer_args(renderer, el, tmp)
    if tool == "atlas_render" and "backend" not in args:
        args["backend"] = backend()
    if tool == "atlas_render" and len(frames) == 1:
        # one camera has no approach: a server would never have sent a far element's sections (server=1), so they
        # are uploaded and kept (server=0), as for a place visited before
        args["server"] = 0
    try:
        r = run(tool, {**args, "cameras": lst.as_posix()})
    except RuntimeError:
        if args.get("backend") != "vulkan":
            raise
        args["backend"] = "cpu"  # no usable Vulkan device here (the CPU backend is held to the same pictures)
        r = run(tool, {**args, "cameras": lst.as_posix()})
    if tool == "atlas_render":
        BACKENDS.add(args["backend"])
    missing = [png for png, _ in frames if not png.exists()]
    if missing:
        raise RuntimeError(f"{tool} wrote no {missing[0].name}")
    # atlas_lab's camera frame once put its ref variant at out= whatever was asked: every "lab-groups" number was the
    # plain micro-voxels; the frames scored must be the representation's own
    if tool == "atlas_lab" and not wrote_variant(r.stderr, frames[0][0], args["variants"].split(",")[0]):
        raise RuntimeError(f"atlas_lab did not render {frames[0][0].name} with {args['variants'].split(',')[0]}")


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
    excess, prev, still = [], None, 0
    for (pt, _), (po, _) in zip(frames_truth, frames_ours):
        cur = (lab(load(pt)[0]), lab(load(po)[0]))
        if prev:
            ct = float(np.sqrt(((cur[0] - prev[0]) ** 2).sum(axis=2)).mean())
            co = float(np.sqrt(((cur[1] - prev[1]) ** 2).sum(axis=2)).mean())
            excess.append(max(0.0, co - ct))
            still += co == 0.0 and ct > 0.5  # the reference moved, ours did not change a pixel
        prev = cur
    return {"pop": round(float(np.mean(excess)), 3) if excess else 0.0, "popMax": round(float(max(excess)), 3) if excess else 0.0,
            "steps": [round(e, 3) for e in excess], "frozen": still}


# an element fails below this silhouette agreement, above this popping, or (a dot of under SHAPE_PX pixels) with its
# coverage off the reference's by more than presence (either way)
LIMITS = {"iou": 0.9, "pop": 0.1, "presence": 0.75}
SHAPE_PX = 16
MASS_PX = 0.25  # pixels of coverage: a dot's mass is measured to about this (16 samples, sub-pixel placement)
DARK_LIGHT = 2  # mean sky light (0..15) on the element's exposed faces: below it, it is underground;
# a light level, not a brightness: a tree at night is dark yet under the open sky, and night is a case to test


def outside_light(el, alc):
    """The mean sky light (0..15) in front of the element's exposed faces (those facing air or the box's edge), from
    its surface cards: under the open sky (day or night) or underground (torches are block light: a lit dungeon hall
    is still underground, and from outside the box it is a black cube)."""
    (x0, y0, z0), ids = read_alv(export_dir() / f"{el['volume']}.alv")
    air = {sid for sid, n in block_names().items() if n.startswith(("Block{minecraft:air}", "Block{minecraft:cave_air}",
                                                                    "Block{minecraft:void_air}"))}
    b = el["box"]
    step = ((0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0))
    d = Path(alc).read_bytes()
    n, p, levels = struct.unpack("<i", d[4:8])[0], 8, []
    for _ in range(n):
        x, y, z, f = struct.unpack("<4i", d[p:p + 16])
        sky = np.frombuffer(d[p + 16 + 1024:p + 16 + 1280], dtype=np.uint8)
        cov = (np.frombuffer(d[p + 16:p + 16 + 1024], dtype="<u4") >> 24) > 0
        p += 16 + 1536
        q = (x + step[f][0], y + step[f][1], z + step[f][2])
        i = (q[1] - y0, q[2] - z0, q[0] - x0)
        if not all(0 <= i[k] < ids.shape[k] for k in range(3)):
            continue  # beyond the export: its light is the reference's open-sky guess, not data
        if all(b[k] <= q[k] <= b[k + 3] for k in range(3)) and int(ids[i]) not in air:
            continue  # covered by the element's own blocks
        if cov.any():
            levels.append(float(sky[cov].mean()) / 16.0)
    return round(float(np.mean(levels)), 2) if levels else 15.0


LANE_WAIT_S = 1800  # the longest an element run waits for its lane before it goes ahead anyway (and says so)


def lane(wait_s=LANE_WAIT_S, poll_s=20):
    """The machine's element lane, for the length of this run (an OS file lock beside the shared Prism instance: one
    element sweep at a time across every checkout, released by the OS if the process dies). Before taking it, waits
    while another checkout's live run holds the game client: agents' CPU sweeps had run beside a live run, slowing
    both (the owner: "are we getting something useful out of that test for the time it's taking?")."""
    import game
    t0 = time.monotonic()
    while time.monotonic() - t0 < wait_s:
        held = game.lease_holder()
        if not held or held.get("owner") == game.LEASE_OWNER:
            break
        print(f"element_lab: {held.get('owner')} holds the game client (a live run): waiting", file=sys.stderr, flush=True)
        time.sleep(poll_s)
    path = export_dir().parent / "nirncraft-element-lane.lock"
    f = open(path, "a+b")
    while True:
        try:
            if sys.platform == "win32":
                import msvcrt
                f.seek(0)
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return f  # held while the file stays open (this run)
        except OSError:
            if time.monotonic() - t0 > wait_s:
                print("element_lab: the element lane stayed busy: going ahead", file=sys.stderr, flush=True)
                return f
            print("element_lab: another element run holds the lane: waiting", file=sys.stderr, flush=True)
            time.sleep(poll_s)


def view(a, el):
    """--view DIST: the reference once (kept beside the output, keyed by element, distance and truth), ours every
    time, scored and put side by side (reference | ours | dE x4) in one png. --watch: atlas_render stays up with
    watch=1 and every new frame is scored as it lands."""
    out = a.out or ROOT / ".work/view"
    out.mkdir(parents=True, exist_ok=True)
    cam = camera(el["box"], a.view, size=a.size)
    tag = f"{el['slug']}-{a.view}-{TRUTH_MODE}" + (f"-{TIME}" if TIME is not None else "")
    ref = out / f"{tag}-truth.png"
    if not ref.exists() or not Path(str(ref)[:-4] + "_mask.png").exists():
        render("truth", el, cam, ref)
    ours = out / f"{tag}-{a.renderer}.png"

    def score():
        m = compare(ref, ours)
        ra, oa = (np.asarray(Image.open(p).convert("RGB"), dtype=np.float32) for p in (ref, ours))
        de = np.sqrt(((lab(ra) - lab(oa)) ** 2).sum(axis=2))
        heat = np.clip(de * 4, 0, 255).astype(np.uint8)
        sheet = np.concatenate([ra.astype(np.uint8), oa.astype(np.uint8), np.stack([heat] * 3, axis=2)], axis=1)
        Image.fromarray(sheet).save(out / f"{tag}-{a.renderer}-sheet.png")
        print(f"{el['slug']} {a.view} m {a.renderer}: iou {m['iou']:.3f}  dE {m['dE']:.2f}  presence {m['presence']:.2f}  "
              f"-> {out / (tag + '-' + a.renderer + '-sheet.png')}", flush=True)

    if not a.watch:
        render(a.renderer, el, cam, ours)
        score()
        return 0
    tool, args = renderer_args(a.renderer, el, out)
    if tool != "atlas_render":
        print("element_lab: --watch drives atlas_render (a far-* renderer)")
        return 2
    shaders = ROOT / ".work/shaders"
    env = {**os.environ, "ATLAS_SHADER_DIR": str(shaders)}
    cmd = [str(EXE / "atlas_render.exe"), f"out={ours}", "watch=1", "backend=vulkan", "server=0"] + \
          [f"{k}={v}" for k, v in {**args, **cam}.items()]
    print(f"element_lab: watching; run tools/dev/shader_watch.py and edit native/shaders (ctrl-c ends)", flush=True)
    p = subprocess.Popen(cmd, env=env, stderr=subprocess.PIPE, text=True)
    try:
        for line in p.stderr:
            if line.startswith("rendered ") or line.startswith("watching "):
                if ours.exists():
                    score()
            elif line.strip():
                print(line.rstrip(), flush=True)
    finally:
        p.kill()
    return p.wait()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("element", nargs="?")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--renderer", default="lab-groups")
    ap.add_argument("--out", type=Path, default=None)
    ap.add_argument("--size", type=int, default=256)
    ap.add_argument("--truth", choices=sorted(REFERENCE), default="intended",
                    help="the reference's mode: intended (scores our renderer), exact (Minecraft as it renders) or "
                         "realistic (the shader pack's light ray traced; the renderers lit the same way)")
    ap.add_argument("--time", type=int, default=None,
                    help="day time in ticks (6000 noon, 12500 dusk, 18000 midnight): the reference's light")
    ap.add_argument("--distances", default=",".join(str(d) for d in DISTANCES))
    ap.add_argument("--backend", choices=("vulkan", "cpu"), default=None,
                    help="atlas_core's backend for the far field (default: Vulkan when the GPU is mostly idle)")
    ap.add_argument("--view", type=int, default=None, metavar="DIST",
                    help="the development loop: one distance, no fly-ins: reference (kept) | ours | dE in --out (default "
                         ".work/view), seconds a change")
    ap.add_argument("--watch", action="store_true",
                    help="with --view: keep atlas_render running and score again on every shader save "
                         "(tools/dev/shader_watch.py compiles; Vulkan)")
    a = ap.parse_args()
    global FORCE_BACKEND
    FORCE_BACKEND = a.backend
    els = elements()
    if a.list or not a.element:
        for e in els:
            print(e["slug"], e["kind"], e["box"])
        return 0
    el = next((e for e in els if e["slug"] == a.element), None)
    if not el:
        print(f"element_lab: no element {a.element} (--list)")
        return 2
    global TRUTH_MODE, TIME
    TRUTH_MODE = a.truth
    TIME = a.time
    if a.renderer in ("far-lit", "far-lit-game") and not realistic():
        print("element_lab: far-lit is scored against the realistic reference (--truth realistic)")
        return 2
    if a.view:
        return view(a, el)
    near = 1.5 * radius(el["box"])  # never from inside or against the element: not a view anyone has of it
    dists = [d for d in (int(x) for x in a.distances.split(",")) if d >= near] or [int(near)]
    report = {"element": el, "renderer": a.renderer, "truth": TRUTH_MODE, "time": TIME if TIME is not None or not realistic() else 6000,
              "distances": []}
    rows = []
    held_lane = lane() if not a.list else None  # noqa: F841  kept open: the lane is ours until the run ends
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
        report["outsideLight"] = outside_light(el, bake_cards(el, tmp))
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
        tag = "" if TRUTH_MODE == "intended" and TIME is None else f"-{TRUTH_MODE}" + (f"-{day_time()}" if realistic() or TIME is not None else "")
        sheet.save(a.out / f"{el['slug']}{tag}.png")
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
        # a renderer that stops following the camera changes less than the reference everywhere: "no pop" (atlas_render
        # once drew sky for every frame after a far rung, and every fly-in read as perfect)
        if report[f"flyin_{name}"]["frozen"] >= 3:
            fails.append(f"fly-in {name}: ours did not change over {report[f'flyin_{name}']['frozen']} steps where the "
                         "reference did (the renderer stopped following the camera)")
        if report[f"flyin_{name}"]["pop"] > LIMITS["pop"]:
            fails.append(f"fly-in {name} pops: {report[f'flyin_{name}']['pop']} > {LIMITS['pop']}")
    report["fails"] = fails
    if BACKENDS:
        report["backend"] = ",".join(sorted(BACKENDS))
    print(json.dumps(report))
    if report.get("outsideLight", 15) < DARK_LIGHT:  # black on black agrees to dE 0 and says nothing: not a pass
        print(f"SKIP: the element is underground (sky light {report['outsideLight']} of 15 on its faces): judge it "
              "from inside, in the scene tier")
        return 3  # harness.CHECK_SKIP_EXIT
    for f in fails:
        print("FAIL " + f)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
