"""The vanilla gallery and the distance sweep (docs/ATLAS-PLAN.md stage 0 item 4), in the real game.

    harness.py run atlas-gallery                     every block-state group, vanilla's raster vs atlas unified, 2 m and 16 m
    harness.py run atlas-gallery --gallery-filter 'minecraft:grass_block,minecraft:oak_*' --gallery-limit 40
    harness.py run atlas-gallery-sweep               the same states in atlas at 64 m, 256 m and 1 km vs their near look

The game side is two verbs (AtlasGallery.java): `gallery-index` groups every state of the block registry by the baked
model it draws with, and `gallery-run SCRIPT.json` runs a whole batch of placements and shots inside the client (64
states a batch: a patch of each on a flat stone stage 12 blocks apart, the camera on the exact same pose in vanilla's
raster alone and in atlas unified; the crops come from the real projection). This module plans the batches, measures
every pair and writes the report: per-state dE, and one sheet of the failures only, worst first, side by side.

Results: .work/harness/atlas/gallery/<stamp>/ (report.json, report.html, failures-NN.png, crops of the failures) and
.../latest/.
"""
import fnmatch
import hashlib
import json
import math
import re
import shutil
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".work/harness/atlas/gallery"

ORIGIN = (4400, 521, 4096)       # the middle of the stage; the patches' layer (the stone floor is the layer below)
SPACING, COLS, ROWS = 12, 8, 8   # 64 patches a batch, 12 blocks apart
WINDOW = (1920, 1080)
# pass / fail (printed in every report). dE is CIE76 on the crop's pixels. A state fails on STRUCTURE (dEshape: dE after
# the pair's median Lab offset is removed, so lighting level does not hide or fake a shape difference; pit: the share of
# pixels where atlas is darker than vanilla by more than PIT_L after that offset, the texel-sized black pits in grass
# tops) or on TONE (the brightness offset itself, more than `tone` L: atlas lights the block differently from vanilla)
LIMITS = {"shape": 6.0, "pit": 0.005, "shape16": 8.0, "pit16": 0.01, "tone": 6.0}
PIT_L = 20.0
NEAR, MID = 2.0, 16.0
SWEEP_DISTANCES = [64, 256, 1000]
SWEEP_LIMITS = {"colourDE": 12.0, "coverage": 0.2}
RESUME = {"dir": None}


def scenes(common):
    base = {**common, "atlas_tool": "gallery", "timeout": 14400}
    return [
        {**base, "id": "atlas-gallery", "tags": ["suite:atlas-gates", "feature:gallery", "feature:atlas"],
         "title": "Every block-state group (vanilla and NirnCraft): vanilla's raster vs atlas unified from the same camera at "
                  "2 m and 16 m; per-state dE and one sheet of the failures"},
        {**base, "id": "atlas-gallery-sweep", "sweep": True, "tags": ["suite:atlas-gates", "feature:gallery", "feature:atlas"],
         "title": "The same states in atlas at 64 m, 256 m and 1 km: mean colour and coverage against the 2 m look"},
    ]


def run(runner, s, v, fp):
    import atlas_tools
    g = runner.game
    a = runner.a
    stamp = time.strftime("%Y%m%d-%H%M%S")
    sweep = bool(s.get("sweep"))
    folder = OUT / ("sweep" if sweep else "near") / stamp
    folder.mkdir(parents=True, exist_ok=True)
    mark = g.log_mark()
    g.ensure_visible()
    atlas_tools.wait_alive(g)
    index = load_index(g)
    groups = choose(index["groups"], a, sweep)
    print(f"  gallery: {index['states']} states, {index.get('exactGroups')} exact model groups, {len(index['groups'])} representatives "
          f"that together draw every part of every block; testing {len(groups)}", flush=True)
    idle = bool(getattr(a, "idle_only", False)) and not sweep
    key = hashlib.sha1((",".join(x["id"] for x in groups)).encode()).hexdigest()[:10]
    resume = OUT / "resume" / key
    done_rows = []
    if idle and (resume / "rows.json").exists():
        done_rows = json.loads((resume / "rows.json").read_text(encoding="utf-8"))
        print(f"  resuming: {len(done_rows)} of {len(groups)} groups were measured by earlier slices", flush=True)
    done_ids = {r["id"] for r in done_rows}
    todo = [x for x in groups if x["id"] not in done_ids]
    RESUME["dir"] = resume if idle else None
    work = (resume / "work") if idle else (folder / "work")
    work.mkdir(parents=True, exist_ok=True)
    first = len(list(work.glob("b*")))
    g.ensure_visible()
    setup = ["ui close", "perspective first", "hud off", "try time set 6000", "try nirncraft-weather fronts clear", "weather-off on",
             "try nirncraft-weather set-all clear 120"]
    ox, oy, oz = ORIGIN
    half = (COLS // 2) * SPACING + 8
    try:
        g.actions(setup + [f"camera {ox} {oy + 10} {oz} {ox} {oy} {oz + 20}", "settle-atlas 600 60",
                           f"try fill {ox - half} {oy - 1} {oz - half} {ox + half} {oy - 1} {oz + half} minecraft:smooth_stone",
                           f"try fill {ox - half} {oy} {oz - half} {ox + half} {oy + 4} {oz + half} minecraft:air"], timeout=900)
        if sweep:
            rows = sweep_rows(g, groups, work, a, say=print)
        else:
            try:
                rows = done_rows + near_rows(g, todo, work, a, say=print, first=first,
                                             yield_check=waiting_elsewhere if idle else None)
            except Yielded as y:  # someone waits for the machine: stop here, keep the slice, the next run goes on
                rows = done_rows + y.rows
                resume.mkdir(parents=True, exist_ok=True)
                (resume / "rows.json").write_text(json.dumps(rows), encoding="utf-8")
                import results
                msg = f"yielded to a waiting job after {len(rows)} of {len(groups)} groups; run it again to go on"
                print("  " + msg, flush=True)
                return results.record(s["id"], v, "atlas-gallery", "skip", {"groups": len(rows), "of": len(groups)}, fp,
                                     extra={"error": msg})
    except RuntimeError:
        g.recover(s)
        raise
    finally:
        pass
    log = g.log_since(mark)
    counts, serious = g.log_summary(log)
    if sweep:
        return finish_sweep(g, s, v, fp, rows, folder, groups, counts)
    return finish_near(g, s, v, fp, rows, folder, groups, counts)


# ------------------------------------------------------------------------------------------------------------ the index
def load_index(g):
    import game
    jar = game.sha(game.GAME / "mods/nirncraft-core.jar")
    f = OUT / f"index-{jar}.json"
    if not f.exists():
        OUT.mkdir(parents=True, exist_ok=True)
        f.with_suffix(".tmp").unlink(missing_ok=True)
        g.actions([f"gallery-index {f.as_posix()}"], timeout=300)
        if not f.exists():
            raise RuntimeError("gallery-index wrote nothing")
    return json.loads(f.read_text(encoding="utf-8"))


CLASSES = ["cube", "cutout", "connecting", "noncube", "plant", "fluid", "emissive", "animated", "nirncraft"]


def classify(g):
    """The model class a group is sampled by (docs/ATLAS-PLAN.md stage 3's classes): the first that fits."""
    props = set((g.get("props") or "").split(","))
    if g["block"].startswith("nirncraft:"):
        return "nirncraft"
    if g.get("fluid"):
        return "fluid"
    if (g.get("emits") or 0) > 0:
        return "emissive"
    if g.get("animated"):
        return "animated"
    if {"north", "east", "south", "west"} <= props:
        return "connecting"
    if g.get("noCollision"):
        return "plant"
    if "CUTOUT" in (g.get("layers") or "") or "TRANSLUCENT" in (g.get("layers") or ""):
        return "cutout"
    if not g.get("full"):
        return "noncube"
    return "cube"


def spread(lst, k):
    return list(lst) if len(lst) <= k else [lst[int(i * len(lst) / k)] for i in range(k)]


def choose(groups, a, sweep):
    """The groups to test. Default: a sample of 300 spread over the model classes (grass included), about 7 minutes of
    the game; --sample N changes it, --class a,b limits it, --gallery-filter picks blocks, --gallery-all (or
    --idle-only, which yields to other jobs between batches) takes every representative (about 4900 at 6 a block)."""
    pats = [p for p in (getattr(a, "gallery_filter", None) or "").split(",") if p]
    out = [g for g in groups if not pats or any(fnmatch.fnmatch(g["block"], p) or fnmatch.fnmatch(g["id"], p) for p in pats)]
    for g in out:
        g["class"] = classify(g)
    want = [c for c in (getattr(a, "gallery_class", None) or "").split(",") if c]
    if want:
        out = [g for g in out if g["class"] in want]
    full = getattr(a, "gallery_all", False) or getattr(a, "idle_only", False)
    per = getattr(a, "gallery_per_block", 0) or 6
    if not full and not sweep:
        # models that differ only by a rotation or a facing are one kind of thing: at most `per` of a block (the ones
        # with the most parts first, the rest spread), every part of the block is still in the sample
        keep, byblock = [], {}
        for g in out:
            byblock.setdefault(g["block"], []).append(g)
        for lst in byblock.values():
            if len(lst) > per:
                lst = lst[:2] + spread(lst[2:], per - 2)
            keep += lst
        out = keep
    n = getattr(a, "sample", 0) or getattr(a, "gallery_limit", 0) or 0
    if not n and not full and not pats and not want:
        n = 96 if sweep else 300
    if n and len(out) > n:
        by = {}
        for g in out:
            by.setdefault(g["class"], []).append(g)
        classes = [c for c in CLASSES if c in by]
        quota = {c: n // len(classes) for c in classes}
        for c in classes[: n - sum(quota.values())]:
            quota[c] += 1
        spare = sum(max(0, quota[c] - len(by[c])) for c in classes)  # a small class gives its unused share to the others
        out = []
        for c in classes:
            t = min(len(by[c]), quota[c] + (spare if quota[c] < len(by[c]) else 0))
            sel = spread(by[c], t)
            if c == "cube":  # grass first: the texel pits live in its tops
                grass = [g for g in by[c] if g["block"] == "minecraft:grass_block"]
                sel = grass + [g for g in sel if g not in grass][: max(0, t - len(grass))]
            out += sel
    return out


def cell(i):
    ox, oy, oz = ORIGIN
    return ox + (i % COLS - COLS // 2) * SPACING, oy, oz + (i // COLS - ROWS // 2) * SPACING


def run_script(g, name, steps, out, timeout=1800):
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"{name}.script.json"
    (out / "done.json").unlink(missing_ok=True)
    f.write_text(json.dumps({"name": name, "out": str(out).replace("\\", "/"), "steps": steps}), encoding="utf-8")
    g.actions([f"gallery-run {f.as_posix()}"], timeout=timeout)
    d = out / "done.json"
    if not d.exists():
        raise RuntimeError(f"gallery script {name} wrote no receipt")
    return json.loads(d.read_text(encoding="utf-8"))


def eye(c, dist, dy=0.5):
    x, y, z = c
    k = dist / math.sqrt(2)
    return [x + 0.5, y + dy + k, z + 0.5 - k], [x + 0.5, y + dy, z + 0.5]


# ------------------------------------------------------------------------------------------------------------ near / 16 m
def blank(png):
    """A crop that is one flat colour: the camera saw nothing (a section vanilla had not meshed yet), not a block."""
    try:
        return float(np.asarray(Image.open(png).convert("RGB"), dtype=np.float32).std()) < 0.6
    except OSError:
        return True


def shots_for(batch_cells, mode, settle):
    steps = []
    for gr, c in batch_cells:
        for tag, dist, half in (("a2", NEAR, 0.5), ("b16", MID, 1.0)):
            e, lk = eye(c, dist)
            steps.append({"op": "shot", "name": f"{mode}/{gr['id']}__{tag}", "eye": e, "look": lk, "settle": settle,
                          "targets": [{"name": "c", "at": lk, "half": half}]})
    return steps


class Yielded(Exception):
    def __init__(self, rows):
        super().__init__("yielded")
        self.rows = rows


def waiting_elsewhere():
    """Whether another job waits for the heavy lock (a waiting file whose process is alive)."""
    import ctypes
    d = Path(r"E:\NirnCraft-scratch\locks\waiting")
    for f in (d.glob("*.json") if d.exists() else []):
        try:
            pid = int(json.loads(f.read_text(encoding="utf-8")).get("pid", 0))
        except (OSError, ValueError):
            continue
        h = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
        if h:
            ctypes.windll.kernel32.CloseHandle(h)
            return True
    return False


def near_rows(g, groups, work, a, say=print, yield_check=None, first=0):
    rows = []
    nb = (len(groups) + COLS * ROWS - 1) // (COLS * ROWS)
    t0 = time.monotonic()
    for b in range(nb):
        batch = groups[b * COLS * ROWS:(b + 1) * COLS * ROWS]
        out = work / f"b{first + b:03d}"
        for m in ("vanilla", "atlas"):
            (out / m).mkdir(parents=True, exist_ok=True)
        ox, oy, oz = ORIGIN
        steps = []
        if b == 0:
            steps.append({"op": "window", "w": WINDOW[0], "h": WINDOW[1]})
        steps += [{"op": "goto", "at": [ox, oy + 6, oz], "wait": 30},
                  {"op": "place", "items": [dict(zip("xyz", cell(i)), state=gr["state"], w=3, d=3) for i, gr in enumerate(batch)],
                   "wait": 60}]
        steps.append({"op": "describe", "name": f"b{b:03d}", "states": [gr["state"] for gr in batch]})
        settle = getattr(a, "gallery_settle", 0) or 3
        cells = [(gr, cell(i)) for i, gr in enumerate(batch)]
        for mode in ("vanilla", "atlas"):
            steps.append({"op": "mode", "name": mode, "wait": 100})
            if mode == "atlas":  # (what was placed is up in atlas: nothing pending, the grid's counters still)
                steps.append({"op": "settle", "max": 900, "min": 40})
                steps.append({"op": "info", "name": f"b{b:03d}-atlas"})
                steps.append({"op": "probe", "name": f"b{b:03d}-atlas", "blocks": [list(cell(i)) for i in range(len(batch))]})
            steps += shots_for(cells, mode, settle)
        steps.append({"op": "mode", "name": "atlas", "wait": 1})
        receipt = run_script(g, f"batch{b}", steps, out, timeout=2400)
        for attempt in range(2):  # a crop of one flat colour is a view vanilla had not meshed yet: shoot those again
            again = [(gr, cell(i)) for i, gr in enumerate(batch)
                     if any(blank(out / m / f"{gr['id']}__{t}__c.png") for m in ("vanilla", "atlas") for t in ("a2", "b16"))]
            if not again:
                break
            say(f"  {len(again)} state(s) came out blank; shooting them again")
            ox, oy, oz = ORIGIN
            steps = [{"op": "goto", "at": [ox, oy + 6, oz], "wait": 40}]
            for mode in ("vanilla", "atlas"):
                steps += [{"op": "mode", "name": mode, "wait": 120}] + shots_for(again, mode, settle + 2)
            steps.append({"op": "mode", "name": "atlas", "wait": 1})
            run_script(g, f"batch{b}-again{attempt}", steps, out, timeout=1800)
        probes = []
        try:
            probes = (out / f"b{b:03d}-atlas.probe.txt").read_text(encoding="utf-8").splitlines()
        except OSError:
            pass
        for i, gr in enumerate(batch):
            row = measure_pair(out, gr)
            if i < len(probes):
                row["probe"] = probes[i]
                m = re.search(r" core=(\d+) ", probes[i])
                if m and int(m.group(1)) == 0 and " client=minecraft:air" not in probes[i]:
                    row["notInCore"] = True
                    judge(row)
            row["dir"] = str(out)
            row["class"] = gr.get("class", "")
            row["cell"] = list(cell(i))
            rows.append(row)
        if yield_check and b + 1 < nb and yield_check():
            raise Yielded(rows)
        elapsed = time.monotonic() - t0
        say(f"  TRACE progress gallery {min(len(groups), (b + 1) * COLS * ROWS)}/{len(groups)} groups, {elapsed:.0f} s "
            f"(batch {b + 1}/{nb}: {receipt.get('shots')} shots in {receipt.get('ms')} ms)")
    return rows


def lab(img):
    arr = np.asarray(img.convert("RGB"), dtype=np.float32) / 255.0
    lin = np.where(arr <= 0.04045, arr / 12.92, ((arr + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]], dtype=np.float32)
    xyz = lin @ m.T / np.array([0.95047, 1.0, 1.08883], dtype=np.float32)
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)


def minfilter3(a):
    """The 3 x 3 neighbourhood minimum (a one-pixel shift between vanilla's raster and atlas's rays is not a pit)."""
    p = np.pad(a, 1, mode="edge")
    h, w = a.shape
    return np.min([p[y:y + h, x:x + w] for y in range(3) for x in range(3)], axis=0)


def block_mean(a, k=4):
    h, w = a.shape[:2]
    h -= h % k
    w -= w % k
    return a[:h, :w].reshape(h // k, k, w // k, k, -1).mean((1, 3))


def load_rgb(png, size=None):
    im = Image.open(png).convert("RGB")
    if size and im.size != size:
        im = im.resize(size)
    return np.asarray(im, dtype=np.float32)


def lab_arr(rgb):
    return lab(Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)))


# ---- the look: the tone curves between vanilla's raster and atlas's rays ---------------------------------------------
# Atlas lights a block with a physical sun and sky and a tone map; vanilla multiplies the texture by a lightmap and a face
# shade (top 1, north / south 0.8, east / west 0.6). The two differ by a curve per face over the whole picture (about x2 in
# linear light on a sunlit top, compressed above; the north face, which only the sky lights, differs again), not by a
# block's material: the same on stone, a tinted grass top, a terracotta. The gallery fits those curves from the full cubes
# (blocks with nothing but a texture to differ in) and puts vanilla through them before it compares, so a state fails on
# what it shows differently and the look difference is recorded once, in the report.
CURVE_FILE = OUT / "tone-curve.json"
CURVE_BIN = 8
FACES = ("top", "front")   # what the 45-degree view shows of a patch (label 2, the floor and the sides, take the top's curve)


def face_labels(row, tag, size):
    """Per pixel of a pair's crop (size = (w, h)): 0 the patch's top, 1 its front (north) face, 2 anything else, from the shot's
    own camera (the pose the gallery put it on, the window and the crop's rectangle the game wrote beside the picture)."""
    out = Path(row["dir"])
    try:
        rec = json.loads((out / "done.json").read_text(encoding="utf-8"))
        side = json.loads((out / "vanilla" / f"{row['id']}__{tag}.json").read_text(encoding="utf-8"))
        rect = side["targets"]["c"]["rect"]
        c = row["cell"]
    except (OSError, ValueError, KeyError):
        return np.zeros((size[1], size[0]), dtype=np.uint8)
    e, lk = eye(c, NEAR if tag == "a2" else MID)
    e, lk = np.array(e), np.array(lk)
    d0 = lk - e
    yaw = math.atan2(-d0[0], d0[2])
    pitch = -math.atan2(d0[1], math.hypot(d0[0], d0[2]))
    f = np.array([-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    r = np.array([-math.cos(yaw), 0.0, -math.sin(yaw)])
    u = np.array([r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]])
    th = math.tan(math.radians(rec["fov"]) / 2)
    W, H = rec["w"], rec["h"]
    gx = max(0, rect[0]) + (np.arange(size[0]) + 0.5) * (rect[2] / size[0] if rect[0] >= 0 else 1.0)
    gy = max(0, rect[1]) + (np.arange(size[1]) + 0.5) * (rect[3] / size[1] if rect[1] >= 0 else 1.0)
    sx = (gx / W) * 2 - 1
    sy = 1 - (gy / H) * 2
    d = (f[None, None, :] + sx[None, :, None] * th * (W / H) * r[None, None, :] + sy[:, None, None] * th * u[None, None, :])
    x, y, z = c
    lo, hi = np.array([x - 1.0, y, z - 1.0]), np.array([x + 2.0, y + 1.0, z + 2.0])
    with np.errstate(divide="ignore", invalid="ignore"):
        t1, t2 = (lo - e) / d, (hi - e) / d
    tn = np.where(np.isfinite(t1), np.minimum(t1, t2), -np.inf)
    tf = np.where(np.isfinite(t1), np.maximum(t1, t2), np.inf)
    tmin, tmax = tn.max(-1), tf.min(-1)
    axis = tn.argmax(-1)
    lab_ = np.full(tmin.shape, 2, dtype=np.uint8)
    hit = (tmin < tmax) & (tmax > 0)
    lab_[hit & (axis == 1) & (d[..., 1] < 0)] = 0
    lab_[hit & (axis == 2) & (d[..., 2] > 0)] = 1
    return lab_


def apply_curves(rgb, labels, curves):
    """Vanilla's display values (0..255 a channel) as atlas's look shows them: each face's fitted tone curve, per channel."""
    out = rgb.copy()
    for k, name in enumerate(FACES):
        c = curves.get(name) or curves.get("top")
        if not c:
            continue
        xs, ys = c["x"], c["y"]
        m = labels != 1 if k == 0 else labels == 1
        mapped = np.where(rgb > xs[-1], ys[-1] + (rgb - xs[-1]), np.interp(rgb, xs, ys))
        out = np.where(m[..., None], mapped, out)
    return out


def split_pits(dark, shape):
    """A pit is a dark spot in a picture that otherwise matches: its component's ring (3 px around it) shows the block as
    vanilla does (the shape difference there under 6). Dark where its surroundings differ too is a silhouette or a lighting
    difference (a missing part, a shadow), not a hole. Returns (pit mask, silhouette mask)."""
    from scipy import ndimage
    lab_, n = ndimage.label(dark)
    pit = np.zeros_like(dark)
    if n == 0:
        return pit, dark.copy()
    for i, sl in enumerate(ndimage.find_objects(lab_), start=1):
        y0, y1, x0, x1 = max(0, sl[0].start - 4), sl[0].stop + 4, max(0, sl[1].start - 4), sl[1].stop + 4
        comp = lab_[y0:y1, x0:x1] == i
        ring = ndimage.binary_dilation(comp, iterations=3) & ~comp
        ring &= ~dark[y0:y1, x0:x1]
        if ring.sum() >= 8 and float(shape[y0:y1, x0:x1][ring].mean()) < 6.0 and comp.sum() < 0.25 * dark.size:
            pit[y0:y1, x0:x1] |= comp
    return pit, dark & ~pit


def pair_metrics(v_png, a_png, curves=None, labels=None):
    """The pair's numbers. With `curves` (fit_curves) vanilla's pixels are first put through the look's tone curves: what is
    left is the block's own difference, not the pipeline's (a physical sun and a tone map against vanilla's lightmap)."""
    v = load_rgb(v_png)
    a = load_rgb(a_png, (v.shape[1], v.shape[0]))
    if curves:
        if labels is None or labels.shape != v.shape[:2]:
            labels = np.zeros(v.shape[:2], dtype=np.uint8)
        v = apply_curves(v, labels, curves)
    lv, la = lab_arr(v), lab_arr(a)
    d = la - lv
    de = np.sqrt((d ** 2).sum(-1))
    off = np.median(d.reshape(-1, 3), axis=0)
    shape = np.sqrt(((d - off) ** 2).sum(-1))
    ca = la[..., 0] - off[0]
    dark = ca < minfilter3(lv[..., 0]) - PIT_L          # darker than every vanilla pixel around it
    pit, sil = split_pits(dark, shape)
    bright = ca > -minfilter3(-lv[..., 0]) + PIT_L      # brighter than every vanilla pixel around it
    return {"dE": float(de.mean()), "dEp95": float(np.percentile(de, 95)), "dEshape": float(shape.mean()),
            "offsetL": float(off[0]), "pit": float(pit.mean()), "silhouette": float(sil.mean()), "bright": float(bright.mean()),
            "lumaVanilla": float(lv[..., 0].mean()), "lumaAtlas": float(la[..., 0].mean()), "px": int(de.size),
            # what is in the picture at all: the share of pixels near black in atlas and not in vanilla (a hole: nothing
            # drawn, nothing loaded), and the spread of luminance in each (an atlas picture as flat as the stage's floor
            # where vanilla's is not: the block is not drawn)
            "blackAtlas": float(((a.max(-1) < 8) & (v.max(-1) >= 8)).mean()), "stdVanilla": float(lv[..., 0].std()),
            "stdAtlas": float(la[..., 0].std())}


def fit_curve(v, a, bin_w=CURVE_BIN, need=300):
    """A monotone curve vanilla value -> atlas value: the median of atlas's values per bin of vanilla's. None when too thin."""
    xs, ys = [0.0], [0.0]
    for lo in range(0, 256, bin_w):
        m = (v >= lo) & (v < lo + bin_w)
        if m.sum() >= need:
            xs.append(float(np.median(v[m])))
            ys.append(float(np.median(a[m])))
    if len(xs) < 6:
        return None
    ys = np.maximum.accumulate(np.array(ys))
    return {"x": xs, "y": [float(y) for y in ys], "samples": int(v.size)}


def fit_curves(items):
    """items: [(vanilla png, atlas png, labels)] -> {face: curve} from 4 x 4 block means of every channel of every pair."""
    vs, as_, ls = [], [], []
    for vp, ap, lb in items:
        try:
            v = load_rgb(vp)
            a = load_rgb(ap, (v.shape[1], v.shape[0]))
        except OSError:
            continue
        bv, ba = block_mean(v), block_mean(a)
        if lb is None or lb.shape != v.shape[:2]:
            lb = np.zeros(v.shape[:2], dtype=np.uint8)
        bl = lb[2::4, 2::4][:bv.shape[0], :bv.shape[1]]
        vs.append(bv.reshape(-1, 3))
        as_.append(ba.reshape(-1, 3))
        ls.append(bl.reshape(-1))
    if not vs:
        return {}
    v, a, lb = np.concatenate(vs), np.concatenate(as_), np.concatenate(ls)
    out = {}
    top = lb != 1
    c = fit_curve(v[top].reshape(-1), a[top].reshape(-1))
    if c:
        out["top"] = c
    front = lb == 1
    c = fit_curve(v[front].reshape(-1), a[front].reshape(-1), 16, 150)
    if c:
        out["front"] = c
    return out


def tone_curves(rows, work_dirs):
    """The curves of this run (its full cubes within reach of vanilla's look), else the last good ones, else {}."""
    items = []
    for r in rows:
        m = r.get("raw2") or r.get("m2")
        if r.get("class") == "cube" and m and m["dEshape"] <= 8.0 and r["id"] in work_dirs:
            od = work_dirs[r["id"]]
            for tag in ("a2", "b16"):
                vp, ap = od / "vanilla" / f"{r['id']}__{tag}__c.png", od / "atlas" / f"{r['id']}__{tag}__c.png"
                if vp.exists() and ap.exists():
                    items.append((vp, ap, face_labels(r, tag, Image.open(vp).size)))
    curves = fit_curves(items) if len(items) >= 16 else {}
    if curves.get("top"):
        CURVE_FILE.parent.mkdir(parents=True, exist_ok=True)
        CURVE_FILE.write_text(json.dumps(curves), encoding="utf-8")
        return curves
    if CURVE_FILE.exists():
        try:
            c = json.loads(CURVE_FILE.read_text(encoding="utf-8"))
            for v in c.values():
                v["fromFile"] = True
            return c
        except ValueError:
            pass
    return {}


def judge(row):
    """The row's verdict from its m2 / m16."""
    m2, m16 = row["m2"], row["m16"]
    why = []
    if row.get("missing"):
        why.append("no capture (" + ",".join(row["missing"]) + ")")
    struct, tone = 0.0, 0.0
    for m, d, lim_s, lim_p in ((m2, "2 m", LIMITS["shape"], LIMITS["pit"]), (m16, "16 m", LIMITS["shape16"], LIMITS["pit16"])):
        if not m:
            continue
        struct = max(struct, m["dEshape"] / lim_s, m["pit"] / lim_p)
        tone = max(tone, abs(m["offsetL"]) / LIMITS["tone"])
        if m["dEshape"] > lim_s:
            why.append(f"structure dE {m['dEshape']:.1f} at {d}")
        if m["pit"] > lim_p:
            why.append(f"{m['pit'] * 100:.1f}% dark pits at {d}")
        if abs(m["offsetL"]) > LIMITS["tone"]:
            why.append(f"{m['offsetL']:+.0f} L brighter/darker than vanilla at {d}")
    row["fails"] = why
    # the cause the numbers point at first (the report counts them): a hole (black where vanilla draws), a block not drawn
    # (atlas as flat as the floor where vanilla has texture), dark pits, the shape's colours, the tone alone
    causes = []
    for m, d in ((m2, "2 m"), (m16, "16 m")):
        if not m:
            continue
        if m["blackAtlas"] > 0.05:
            causes.append("hole")
        if m["stdAtlas"] < 2.5 and m["stdVanilla"] > 6.0:
            causes.append("not drawn")
        if m["pit"] > (LIMITS["pit"] if d == "2 m" else LIMITS["pit16"]):
            causes.append("pits")
        if m["dEshape"] > (LIMITS["shape"] if d == "2 m" else LIMITS["shape16"]):
            causes.append("shape")
    if row.get("notInCore"):
        causes.append("not in core")  # (atlas's core holds air where the client has a block: the feed lost it)
    if tone > 1 and not causes:
        causes.append("tone")
    row["causes"] = sorted(set(causes))
    row["structure"], row["tone"] = round(struct, 2), round(tone, 2)
    row["score"] = struct * 10 + tone if (m2 or m16) else 999
    return row


def measure_pair(out, gr):
    row = {"id": gr["id"], "block": gr["block"], "state": gr["state"], "count": gr["count"],
           "full": gr.get("full"), "tinted": gr.get("tinted"), "emits": gr.get("emits"), "fluid": gr.get("fluid")}
    for tag, key in (("a2", "m2"), ("b16", "m16")):
        vp, ap = out / "vanilla" / f"{gr['id']}__{tag}__c.png", out / "atlas" / f"{gr['id']}__{tag}__c.png"
        if vp.exists() and ap.exists():
            row[key] = pair_metrics(vp, ap)
        else:
            row[key] = None
            row.setdefault("missing", []).append(tag)
    return judge(row)


def apply_look(rows, work_dirs):
    """Fits the look and re-measures every pair through it. The first measurement stays in each row (raw2, raw16) and its
    verdict in `rawFails`: the old numbers are what the gallery said before the look was taken out."""
    for r in rows:
        r.setdefault("raw2", r["m2"])
        r.setdefault("raw16", r["m16"])
        r.setdefault("rawFails", list(r["fails"]))
    curves = tone_curves(rows, work_dirs)
    if curves:
        for r in rows:
            od = work_dirs[r["id"]]
            for tag, key, raw in (("a2", "m2", "raw2"), ("b16", "m16", "raw16")):
                vp, ap = od / "vanilla" / f"{r['id']}__{tag}__c.png", od / "atlas" / f"{r['id']}__{tag}__c.png"
                if vp.exists() and ap.exists():
                    r[key] = pair_metrics(vp, ap, curves, face_labels(r, tag, Image.open(vp).size))
                else:
                    r[key] = r[raw]
            judge(r)
    return curves


def font(size):
    from PIL import ImageFont
    try:
        return ImageFont.truetype("arial.ttf", size)
    except OSError:
        return ImageFont.load_default()


def heat(v_png, a_png, size, curves=None, labels=None):
    v = load_rgb(v_png)
    a = load_rgb(a_png, (v.shape[1], v.shape[0]))
    if curves:
        v = apply_curves(v, labels if labels is not None and labels.shape == v.shape[:2] else np.zeros(v.shape[:2], dtype=np.uint8), curves)
    d = lab_arr(a) - lab_arr(v)
    d = np.sqrt(((d - np.median(d.reshape(-1, 3), axis=0)) ** 2).sum(-1))  # the shape difference: lighting level removed
    t = np.clip(d / 30.0, 0, 1)
    rgb = np.stack([t * 255, (1 - np.abs(t - 0.5) * 2) * 160, (1 - t) * 90], axis=-1).astype(np.uint8)
    return Image.fromarray(rgb).resize((size, size), Image.NEAREST)


def failure_sheets(rows, work_dirs, folder, per=24, tile=200, curves=None):
    """Worst first, failures only: label | vanilla 2 m | atlas 2 m | dE heat | vanilla 16 m | atlas 16 m | heat."""
    bad = sorted([r for r in rows if r["fails"]], key=lambda r: -r["score"])
    sheets = []
    for p in range(0, len(bad), per):
        page = bad[p:p + per]
        w = 330 + 6 * tile
        img = Image.new("RGB", (w, len(page) * (tile + 4) + 24), (14, 14, 16))
        d = ImageDraw.Draw(img)
        d.text((6, 4), "vanilla | atlas | dE (blue 0 ... red 30) at 2 m, then at 16 m", font=font(13), fill=(200, 200, 200))
        for i, r in enumerate(page):
            y = 24 + i * (tile + 4)
            od = work_dirs[r["id"]]
            d.text((6, y + 4), f"#{p + i + 1} {r['id']}", font=font(12), fill=(255, 220, 120))
            d.text((6, y + 22), r["state"][:48], font=font(11), fill=(190, 190, 190))
            for j, why in enumerate(r["fails"][:5]):
                d.text((6, y + 42 + j * 15), why[:50], font=font(11), fill=(255, 130, 110))
            for k, tag in enumerate(("a2", "b16")):
                vp, ap = od / "vanilla" / f"{r['id']}__{tag}__c.png", od / "atlas" / f"{r['id']}__{tag}__c.png"
                if vp.exists() and ap.exists():
                    x = 330 + k * 3 * tile
                    img.paste(Image.open(vp).convert("RGB").resize((tile, tile), Image.NEAREST), (x, y))
                    img.paste(Image.open(ap).convert("RGB").resize((tile, tile), Image.NEAREST), (x + tile, y))
                    img.paste(heat(vp, ap, tile, curves, face_labels(r, tag, Image.open(vp).size) if curves else None), (x + 2 * tile, y))
        f = folder / f"failures-{p // per + 1:02d}.png"
        img.save(f)
        sheets.append(f)
    return sheets


def finish_near(g, s, v, fp, rows, folder, groups, counts):
    import results
    work = folder / "work"
    where = {r["id"]: Path(r["dir"]) for r in rows}
    curves = apply_look(rows, where)
    sheets = failure_sheets(rows, where, folder, curves=curves)
    failing = [r for r in rows if r["fails"]]
    raw_failing = [r for r in rows if r["rawFails"]]
    worst = sorted(failing, key=lambda r: -r["score"])[:5]
    causes = {}
    for r in failing:
        for c in r.get("causes") or ["other"]:
            causes[c] = causes.get(c, 0) + 1
    by_class = {}
    for r in rows:
        k = by_class.setdefault(r.get("class", "?"), {"groups": 0, "failing": 0})
        k["groups"] += 1
        k["failing"] += 1 if r["fails"] else 0
    look = {"curves": curves, "medianOffsetL2m": round(float(np.median([r["raw2"]["offsetL"] for r in rows if r["raw2"]] or [0])), 1)}
    (folder / "report.json").write_text(json.dumps({"limits": LIMITS, "pitL": PIT_L, "groups": len(rows), "failing": len(failing),
                                                    "rawFailing": len(raw_failing), "causes": causes, "byClass": by_class, "look": look,
                                                    "rows": rows}, indent=1),
                                        encoding="utf-8")
    # the failures' crops stay (they are the evidence); everything else is dropped
    keep = {r["id"] for r in failing}
    fail_dir = folder / "failures"
    fail_dir.mkdir(exist_ok=True)
    for r in failing:
        od = where[r["id"]]
        for m in ("vanilla", "atlas"):
            for tag in ("a2", "b16"):
                src = od / m / f"{r['id']}__{tag}__c.png"
                if src.exists():
                    shutil.copy2(src, fail_dir / f"{r['id']}__{m}__{tag}.png")
    infos = folder / "info"
    for f in list(work.glob("b*/*.info.txt")) + list(work.glob("b*/*.describe.txt")) + list(work.glob("b*/*.probe.txt")):  # (atlas's own report at each batch's shots: kept, small)
        infos.mkdir(exist_ok=True)
        shutil.copy2(f, infos / f"{f.parent.name}-{f.name}")
    shutil.rmtree(work, ignore_errors=True)
    if RESUME["dir"]:
        shutil.rmtree(RESUME["dir"], ignore_errors=True)
    body = "".join(f"<tr><td>{i + 1}</td><td>{r['id']}</td><td>{r['state']}</td><td>{(r['m2'] or {}).get('dE', 0):.1f}</td>"
                   f"<td>{(r['m2'] or {}).get('pit', 0) * 100:.2f}%</td><td>{(r['m16'] or {}).get('dE', 0):.1f}</td>"
                   f"<td>{(r['m16'] or {}).get('pit', 0) * 100:.2f}%</td><td>{'; '.join(r['fails'])}</td></tr>"
                   for i, r in enumerate(sorted(failing, key=lambda r: -r['score'])[:300]))
    (folder / "report.html").write_text(
        f"<!doctype html><meta charset=utf-8><title>atlas gallery</title><style>body{{font:13px system-ui;background:#111;color:#ddd;margin:16px}}"
        f"td,th{{border:1px solid #444;padding:2px 8px}}img{{max-width:100%}}</style><h1>Vanilla gallery: atlas vs vanilla's raster</h1>"
        f"<p>{len(rows)} model groups tested, <b>{len(failing)}</b> failing. Limits: {LIMITS} (dE = CIE76 over the centre crop; structure = dE "
        f"after the median offset; pit = share of pixels {PIT_L:g} L darker than vanilla after the offset; tone = the offset).</p>"
        + "".join(f'<img src="{x.name}">' for x in sheets[:3])
        + f"<table><tr><th>#</th><th>group</th><th>state</th><th>dE 2m</th><th>pits 2m</th><th>dE 16m</th><th>pits 16m</th><th>why</th></tr>{body}</table>",
        encoding="utf-8")
    latest = OUT / "near" / "latest"
    shutil.rmtree(latest, ignore_errors=True)
    shutil.copytree(folder, latest)
    fails = [f"{len(failing)} of {len(rows)} state groups differ from vanilla beyond the limits "
             f"({len([r for r in failing if r.get('structure', 0) > 1])} in structure, the rest in tone only)"] if failing else []
    structural = [r for r in failing if r.get("structure", 0) > 1]
    metrics = {"groups": len(rows), "failing": len(failing), "structureFailing": len(structural),
               "toneOnlyFailing": len(failing) - len(structural),
               "medianOffsetL2m": look["medianOffsetL2m"], "rawFailing": len(raw_failing), "causes": causes, "byClass": by_class,
               "lookCurves": {k: {"x": [round(x) for x in c["x"]], "y": [round(y) for y in c["y"]]} for k, c in curves.items()},
               "pitFailing": len([r for r in rows if any(r[k] and r[k]["pit"] > LIMITS["pit" if k == "m2" else "pit16"] for k in ("m2", "m16"))]),
               "worst": [f"{r['id']}: {'; '.join(r['fails'][:2])}" for r in worst],
               "dEmean2m": round(float(np.mean([r["m2"]["dE"] for r in rows if r["m2"]] or [0])), 2),
               "dEmean16m": round(float(np.mean([r["m16"]["dE"] for r in rows if r["m16"]] or [0])), 2),
               "report": str(latest / "report.html"), "sheet": str(latest / "failures-01.png"), **counts}
    extra = {"fails": fails, "report": str(latest / "report.html")}
    if sheets:
        tmp = Path(g.SHOTS) / f"h-{s['id']}-{v}.png"
        shutil.copy2(sheets[0], tmp)
        rec = results.store(s["id"], v, tmp, fingerprint=fp, extra=extra)
        rec["metrics"].update(metrics)
        rec["status"] = "fail" if failing else "pass"
        Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1, default=str), encoding="utf-8")
        return rec
    return results.record(s["id"], v, "atlas-gallery", "fail" if failing else "pass", metrics, fp, extra=extra)


# ------------------------------------------------------------------------------------------------------------ the sweep
def sweep_rows(g, groups, work, a, say=print):
    """Batches of fields (16 x 16 of one state) built together, seen from 2 m (the near look) and from 64 m, 256 m and
    1 km (one hop each: all the batch's fields in the picture)."""
    rows = []
    cols, per = 8, 32
    nb = (len(groups) + per - 1) // per
    t0 = time.monotonic()
    ox, oy, oz = ORIGIN
    for b in range(nb):
        batch = groups[b * per:(b + 1) * per]
        out = work / f"s{b:03d}"
        out.mkdir(parents=True, exist_ok=True)
        # the fields: 8 across, 4 deep, 20 blocks apart; the last cell is the reference (stone on the stone floor)
        fields = []
        for i, gr in enumerate(batch + [{"id": "ref", "state": "minecraft:smooth_stone"}]):
            c = (ox + (i % cols - cols // 2) * 20, oy, oz + (i // cols - 2) * 20)
            fields.append((gr, c))
        steps = [{"op": "window", "w": WINDOW[0], "h": WINDOW[1]}] if b == 0 else []
        steps += [{"op": "goto", "at": [ox, oy + 6, oz], "wait": 30},
                  {"op": "mode", "name": "atlas", "wait": 20},
                  {"op": "place", "items": [dict(zip("xyz", c), state=gr["state"], w=16, d=16) for gr, c in fields], "wait": 40},
                  {"op": "settle", "max": 600, "min": 40}]
        for gr, c in fields:
            e, lk = eye(c, NEAR)
            steps.append({"op": "shot", "name": f"near/{gr['id']}", "eye": e, "look": lk, "settle": 12,
                          "targets": [{"name": "q", "at": lk, "half": 0.5}]})
        cx, cz = ox, oz - 20  # the middle of the fields
        for dist in SWEEP_DISTANCES:
            alt = min(110.0, dist * 0.9)
            ey = oy + alt
            eyep = [cx + 0.5, ey, cz + 0.5 - dist]
            look = [cx + 0.5, oy + 0.5, cz + 0.5]
            steps.append({"op": "goto", "at": [eyep[0], eyep[1] - 1.62, eyep[2]], "wait": 20})
            steps.append({"op": "settle", "max": 1800, "min": 60})
            steps.append({"op": "info", "name": f"d{dist}"})
            steps.append({"op": "shot", "name": f"d{dist}/all", "eye": eyep, "look": look, "settle": 20,
                          "targets": [{"name": gr["id"], "quad": quad(c), "at": [c[0] + 8.5, oy + 1, c[2] + 8.5], "half": 8}
                                      for gr, c in fields]})
        steps.append({"op": "goto", "at": [ox, oy + 6, oz], "wait": 20})
        for sub in ["near"] + [f"d{d}" for d in SWEEP_DISTANCES]:
            (out / sub).mkdir(parents=True, exist_ok=True)
        receipt = run_script(g, f"sweep{b}", steps, out, timeout=3600)
        rows += measure_sweep(out, batch)
        say(f"  TRACE progress sweep {min(len(groups), (b + 1) * per)}/{len(groups)} states, {time.monotonic() - t0:.0f} s "
            f"({receipt.get('shots')} shots in {receipt.get('ms')} ms)")
    return rows


def quad(c):
    x, y, z = c
    return [[x - 8, y + 1, z - 8], [x + 8, y + 1, z - 8], [x + 8, y + 1, z + 8], [x - 8, y + 1, z + 8]]


def field_stats(png, side_json, ref_colour=None):
    img = Image.open(png).convert("RGB")
    poly = side_json.get("poly")
    mask = Image.new("L", img.size, 0)
    if poly:
        ImageDraw.Draw(mask).polygon([tuple(p) for p in poly], fill=255)
    else:
        mask = Image.new("L", img.size, 255)
    m = np.asarray(mask) > 0
    if m.sum() < 4:
        return None
    px = lab(img)[m]
    mean = px.mean(axis=0)
    cov = None
    if ref_colour is not None:
        cov = float((np.sqrt(((px - ref_colour) ** 2).sum(-1)) > 8.0).mean())
    return {"mean": mean.tolist(), "coverage": cov, "px": int(m.sum())}


def measure_sweep(out, batch):
    rows = []
    near_side = side(out / "near" / "ref.json").get("targets", {}).get("q", {})
    ref_near = field_stats(out / "near" / "ref__q.png", near_side) if (out / "near" / "ref__q.png").exists() else None
    far_side, ref_far = {}, {}
    for d in SWEEP_DISTANCES:
        far_side[d] = side(out / f"d{d}" / "all.json").get("targets", {})
        p = out / f"d{d}" / "all__ref.png"
        ref_far[d] = field_stats(p, far_side[d].get("ref", {})) if p.exists() and "ref" in far_side[d] else None
    for gr in batch:
        row = {"id": gr["id"], "block": gr["block"], "state": gr["state"], "fails": []}
        nsp = out / "near" / f"{gr['id']}__q.png"
        near = None
        if nsp.exists() and ref_near:
            nj = side(out / "near" / f"{gr['id']}.json").get("targets", {}).get("q", {})
            near = field_stats(nsp, nj, np.array(ref_near["mean"]))
        row["near"] = near
        for d in SWEEP_DISTANCES:
            p = out / f"d{d}" / f"all__{gr['id']}.png"
            st = None
            if p.exists() and ref_far.get(d) and gr["id"] in far_side[d]:
                st = field_stats(p, far_side[d][gr["id"]], np.array(ref_far[d]["mean"]))
            row[f"d{d}"] = st
            if near is None or st is None:
                row["fails"].append(f"no reading at {d} m")
                continue
            cde = float(np.sqrt(((np.array(st["mean"]) - np.array(near["mean"])) ** 2).sum()))
            st["colourDE"] = cde
            st["coverageGap"] = abs((st["coverage"] or 0) - (near["coverage"] or 0))
            if cde > SWEEP_LIMITS["colourDE"]:
                row["fails"].append(f"colour dE {cde:.0f} at {d} m")
            if st["coverageGap"] > SWEEP_LIMITS["coverage"]:
                row["fails"].append(f"coverage {st['coverage']:.2f} vs {near['coverage']:.2f} near at {d} m")
        row["score"] = max([(row.get(f"d{d}") or {}).get("colourDE", 99) / SWEEP_LIMITS["colourDE"] for d in SWEEP_DISTANCES])
        rows.append(row)
    return rows


def side(p):
    try:
        return json.loads(Path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def finish_sweep(g, s, v, fp, rows, folder, groups, counts):
    import results
    failing = [r for r in rows if r["fails"]]
    (folder / "report.json").write_text(json.dumps({"limits": SWEEP_LIMITS, "distances": SWEEP_DISTANCES, "groups": len(rows),
                                                    "failing": len(failing), "rows": rows}, indent=1), encoding="utf-8")
    worst = sorted(failing, key=lambda r: -r["score"])[:5]
    lines = "".join(f"<tr><td>{r['id']}</td>" + "".join(
        f"<td>{(r.get(f'd{d}') or {}).get('colourDE', float('nan')):.1f} / {(r.get(f'd{d}') or {}).get('coverage') or 0:.2f}</td>"
        for d in SWEEP_DISTANCES) + f"<td>{'; '.join(r['fails'])}</td></tr>" for r in sorted(rows, key=lambda r: -r["score"]))
    (folder / "report.html").write_text(
        f"<!doctype html><meta charset=utf-8><title>atlas sweep</title><style>body{{font:13px system-ui;background:#111;color:#ddd;margin:16px}}"
        f"td,th{{border:1px solid #444;padding:2px 8px}}</style><h1>Distance sweep: atlas at 64 m, 256 m, 1 km vs its 2 m look</h1>"
        f"<p>{len(rows)} states, {len(failing)} failing (limits {SWEEP_LIMITS}). Cells: colour dE vs the near look / coverage.</p>"
        f"<table><tr><th>group</th>{''.join(f'<th>{d} m</th>' for d in SWEEP_DISTANCES)}<th>why</th></tr>{lines}</table>", encoding="utf-8")
    latest = OUT / "sweep" / "latest"
    shutil.rmtree(latest, ignore_errors=True)
    shutil.copytree(folder, latest)
    metrics = {"groups": len(rows), "failing": len(failing), "worst": [f"{r['id']}: {'; '.join(r['fails'][:2])}" for r in worst],
               "report": str(latest / "report.html"), **counts}
    return results.record(s["id"], v, "atlas-gallery", "fail" if failing else "pass", metrics, fp,
                          extra={"fails": [f"{len(failing)} of {len(rows)} states change colour or coverage with distance"] if failing else [],
                                 "report": str(latest / "report.html")})
