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
import json
import math
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
# pass / fail (printed in every report). dE is CIE76 on the crop's pixels; the pit share counts pixels where atlas is
# darker than vanilla by more than PIT_L after the pair's median brightness offset is removed (the texel-sized black
# pits in grass tops are a share of the crop's texels, not a mean shift)
LIMITS = {"dE": 8.0, "pit": 0.005, "dE16": 8.0, "pit16": 0.01}
PIT_L = 20.0
NEAR, MID = 2.0, 16.0
SWEEP_DISTANCES = [64, 256, 1000]
SWEEP_LIMITS = {"colourDE": 12.0, "coverage": 0.2}


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
    index = load_index(g)
    groups = choose(index["groups"], a, sweep)
    print(f"  gallery: {index['states']} states in {len(index['groups'])} model groups; testing {len(groups)}", flush=True)
    work = folder / "work"
    work.mkdir(exist_ok=True)
    g.ensure_visible()
    atlas_tools.wait_alive(g)
    setup = ["ui close", "perspective first", "hud off", "try time set 6000", "try nirncraft-weather fronts clear", "weather-off on",
             "try nirncraft-weather set-all clear 120"]
    ox, oy, oz = ORIGIN
    half = (COLS // 2) * SPACING + 8
    try:
        g.actions(setup + [f"camera {ox} {oy + 10} {oz} {ox} {oy} {oz + 20}", "settle-atlas 600 60",
                           f"try fill {ox - half} {oy - 1} {oz - half} {ox + half} {oy - 1} {oz + half} minecraft:smooth_stone",
                           f"try fill {ox - half} {oy} {oz - half} {ox + half} {oy + 4} {oz + half} minecraft:air"], timeout=900)
        rows = sweep_rows(g, groups, work, a, say=print) if sweep else near_rows(g, groups, work, a, say=print)
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


def choose(groups, a, sweep):
    pats = [p for p in (getattr(a, "gallery_filter", None) or "").split(",") if p]
    out = [g for g in groups if not pats or any(fnmatch.fnmatch(g["block"], p) or fnmatch.fnmatch(g["id"], p) for p in pats)]
    n = getattr(a, "gallery_limit", 0) or 0
    if sweep and not n and not pats and not getattr(a, "gallery_all", False):
        n = 96  # a spread through the registry, in order; --gallery-all for every group
    if n and len(out) > n:
        step = len(out) / n
        out = [out[int(i * step)] for i in range(n)]
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
def near_rows(g, groups, work, a, say=print):
    rows = []
    nb = (len(groups) + COLS * ROWS - 1) // (COLS * ROWS)
    t0 = time.monotonic()
    for b in range(nb):
        batch = groups[b * COLS * ROWS:(b + 1) * COLS * ROWS]
        out = work / f"b{b:03d}"
        for m in ("vanilla", "atlas"):
            (out / m).mkdir(parents=True, exist_ok=True)
        ox, oy, oz = ORIGIN
        steps = []
        if b == 0:
            steps.append({"op": "window", "w": WINDOW[0], "h": WINDOW[1]})
        steps += [{"op": "goto", "at": [ox, oy + 6, oz], "wait": 30},
                  {"op": "place", "items": [dict(zip("xyz", cell(i)), state=gr["state"], w=3, d=3) for i, gr in enumerate(batch)],
                   "wait": 60}]
        for mode in ("vanilla", "atlas"):
            steps.append({"op": "mode", "name": mode, "wait": 50 if mode == "vanilla" else 80})
            for i, gr in enumerate(batch):
                c = cell(i)
                for tag, dist, half in (("a2", NEAR, 0.5), ("b16", MID, 1.5)):
                    e, lk = eye(c, dist)
                    steps.append({"op": "shot", "name": f"{mode}/{gr['id']}__{tag}", "eye": e, "look": lk, "settle": 5,
                                  "targets": [{"name": "c", "at": lk, "half": half}]})
        steps.append({"op": "mode", "name": "atlas", "wait": 1})
        receipt = run_script(g, f"batch{b}", steps, out, timeout=2400)
        for i, gr in enumerate(batch):
            rows.append(measure_pair(out, gr))
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


def pair_metrics(v_png, a_png):
    v, a = Image.open(v_png), Image.open(a_png)
    if v.size != a.size:
        a = a.resize(v.size)
    lv, la = lab(v), lab(a)
    d = la - lv
    de = np.sqrt((d ** 2).sum(-1))
    off = np.median(d.reshape(-1, 3), axis=0)
    shape = np.sqrt(((d - off) ** 2).sum(-1))
    dl = (la[..., 0] - off[0]) - lv[..., 0]
    return {"dE": float(de.mean()), "dEp95": float(np.percentile(de, 95)), "dEshape": float(shape.mean()),
            "offsetL": float(off[0]), "pit": float((dl < -PIT_L).mean()), "bright": float((dl > PIT_L).mean()),
            "lumaVanilla": float(lv[..., 0].mean()), "lumaAtlas": float(la[..., 0].mean()), "px": int(de.size)}


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
    m2, m16 = row["m2"], row["m16"]
    why = []
    if row.get("missing"):
        why.append("no capture (" + ",".join(row["missing"]) + ")")
    if m2:
        if m2["dE"] > LIMITS["dE"]:
            why.append(f"dE {m2['dE']:.1f} at 2 m")
        if m2["pit"] > LIMITS["pit"]:
            why.append(f"{m2['pit'] * 100:.1f}% dark pits at 2 m")
    if m16:
        if m16["dE"] > LIMITS["dE16"]:
            why.append(f"dE {m16['dE']:.1f} at 16 m")
        if m16["pit"] > LIMITS["pit16"]:
            why.append(f"{m16['pit'] * 100:.1f}% dark pits at 16 m")
    row["fails"] = why
    row["score"] = max([(m2 or {}).get("dE", 99) / LIMITS["dE"], (m16 or {}).get("dE", 99) / LIMITS["dE16"],
                        (m2 or {}).get("pit", 0) / LIMITS["pit"], (m16 or {}).get("pit", 0) / LIMITS["pit16"]]) if (m2 or m16) else 99
    return row


def font(size):
    from PIL import ImageFont
    try:
        return ImageFont.truetype("arial.ttf", size)
    except OSError:
        return ImageFont.load_default()


def heat(v_png, a_png, size):
    v, a = Image.open(v_png), Image.open(a_png).resize(Image.open(v_png).size)
    d = np.sqrt(((lab(a) - lab(v)) ** 2).sum(-1))
    t = np.clip(d / 30.0, 0, 1)
    rgb = np.stack([t * 255, (1 - np.abs(t - 0.5) * 2) * 160, (1 - t) * 90], axis=-1).astype(np.uint8)
    return Image.fromarray(rgb).resize((size, size), Image.NEAREST)


def failure_sheets(rows, work_dirs, folder, per=24, tile=200):
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
                    img.paste(heat(vp, ap, tile), (x + 2 * tile, y))
        f = folder / f"failures-{p // per + 1:02d}.png"
        img.save(f)
        sheets.append(f)
    return sheets


def finish_near(g, s, v, fp, rows, folder, groups, counts):
    import results
    work = folder / "work"
    where = {}
    # which batch folder holds which group
    for b, d in enumerate(sorted(work.glob("b*"))):
        for gr in groups[b * COLS * ROWS:(b + 1) * COLS * ROWS]:
            where[gr["id"]] = d
    sheets = failure_sheets(rows, where, folder)
    failing = [r for r in rows if r["fails"]]
    worst = sorted(failing, key=lambda r: -r["score"])[:5]
    (folder / "report.json").write_text(json.dumps({"limits": LIMITS, "pitL": PIT_L, "groups": len(rows), "failing": len(failing),
                                                    "rows": rows}, indent=1), encoding="utf-8")
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
    shutil.rmtree(work, ignore_errors=True)
    body = "".join(f"<tr><td>{i + 1}</td><td>{r['id']}</td><td>{r['state']}</td><td>{(r['m2'] or {}).get('dE', 0):.1f}</td>"
                   f"<td>{(r['m2'] or {}).get('pit', 0) * 100:.2f}%</td><td>{(r['m16'] or {}).get('dE', 0):.1f}</td>"
                   f"<td>{(r['m16'] or {}).get('pit', 0) * 100:.2f}%</td><td>{'; '.join(r['fails'])}</td></tr>"
                   for i, r in enumerate(sorted(failing, key=lambda r: -r['score'])[:300]))
    (folder / "report.html").write_text(
        f"<!doctype html><meta charset=utf-8><title>atlas gallery</title><style>body{{font:13px system-ui;background:#111;color:#ddd;margin:16px}}"
        f"td,th{{border:1px solid #444;padding:2px 8px}}img{{max-width:100%}}</style><h1>Vanilla gallery: atlas vs vanilla's raster</h1>"
        f"<p>{len(rows)} model groups tested, <b>{len(failing)}</b> failing. Limits: {LIMITS} (dE = CIE76 over the centre crop; pit = share of "
        f"pixels {PIT_L:g} L darker than vanilla after the median offset).</p>"
        + "".join(f'<img src="{x.name}">' for x in sheets[:3])
        + f"<table><tr><th>#</th><th>group</th><th>state</th><th>dE 2m</th><th>pits 2m</th><th>dE 16m</th><th>pits 16m</th><th>why</th></tr>{body}</table>",
        encoding="utf-8")
    latest = OUT / "near" / "latest"
    shutil.rmtree(latest, ignore_errors=True)
    shutil.copytree(folder, latest)
    fails = [f"{len(failing)} of {len(rows)} state groups differ from vanilla beyond the limits"] if failing else []
    metrics = {"groups": len(rows), "failing": len(failing), "worst": [f"{r['id']}: {'; '.join(r['fails'][:2])}" for r in worst],
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
            steps.append({"op": "shot", "name": f"near/{gr['id']}", "eye": e, "look": lk, "settle": 8,
                          "targets": [{"name": "q", "quad": quad(c), "at": lk, "half": 0.5}]})
        cx, cz = ox, oz - 20  # the middle of the fields
        for dist in SWEEP_DISTANCES:
            alt = min(110.0, dist * 0.9)
            ey = oy + alt
            eyep = [cx + 0.5, ey, cz + 0.5 - dist]
            look = [cx + 0.5, oy + 0.5, cz + 0.5]
            steps.append({"op": "goto", "at": [eyep[0], eyep[1] - 1.62, eyep[2]], "wait": 20})
            steps.append({"op": "settle", "max": 1800, "min": 60})
            steps.append({"op": "shot", "name": f"d{dist}/all", "eye": eyep, "look": look, "settle": 10,
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
