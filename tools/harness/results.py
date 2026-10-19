"""Result store for the NirnCraft test harness: before/after rotation, image metrics, compare images and sheets.

Layout (all under .work/harness, never in git):

    results/<test>/<variant>.after.json        the newest result of that test + variant (every kind: status, metrics,
                                               fingerprint, game receipt), .after.png for visual tests
    results/<test>/<variant>.before.json|png   the result before it (older ones are deleted)
    runs/<stamp>/                               the latest run only: report.html, sheet.png (all scenes, before|after),
                                                combos.png (every variant of a scene side by side), compare/*.png

A new result moves `after` to `before` and drops the old `before`, so iterating with live reload always compares the
last two looks (or numbers) of the same test. Non-visual tests (checks, perf) use the same record without images.
"""
import json
import re
import shutil
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / ".work/harness"
SCENES = STORE / "results"
RUNS = STORE / "runs"  # (older layout; removed by the next report)
LATEST = STORE / "latest"


def _font(size):
    for name in ("segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


# ------------------------------------------------------------------------------------------------------- metrics
def metrics(img, before=None):
    """Numbers that catch the usual breakages without a human: black boxes (NaN in a mip chain), all-black or blown
    frames, and how much the look moved since the previous capture."""
    a = np.asarray(img.convert("RGB"), dtype=np.float32)
    luma = a @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    h, w = luma.shape
    t = 16
    tiles = luma[: h - h % t, : w - w % t].reshape(h // t, t, w // t, t).max(axis=(1, 3))
    mean = float(luma.mean())
    black = tiles < 3
    out = {
        "luma": round(mean, 1),
        "p1": round(float(np.percentile(luma, 1)), 1),
        "p99": round(float(np.percentile(luma, 99)), 1),
        # pure-black 16 px tiles (dark scenery, silhouettes, crushed shadows: a number to watch, not a flag)
        "blackTiles": int(black.sum()),
        # solid rectangles of them in a frame that is not dark overall: the NaN-in-a-mip-chain black-box signature
        "blackBoxes": _boxes(black) if mean > 40 else 0,
        "clipped": round(float((a.max(axis=2) >= 254).mean()) * 100, 2),
        # Minecraft's missing-texture checkerboard is magenta (#F800F8) and black
        "magenta": round(float(((a[..., 0] > 235) & (a[..., 1] < 25) & (a[..., 2] > 235)).mean()) * 100, 3),
    }
    if before is not None:
        b = np.asarray(before.convert("RGB").resize(img.size), dtype=np.float32)
        d = np.abs(a - b).max(axis=2)
        out["diff"] = round(float(d.mean()), 2)
        out["changed"] = round(float((d > 24).mean()) * 100, 1)
    flags = []
    if out["blackBoxes"]:
        flags.append(f"{out['blackBoxes']} black box(es)")
    if mean < 6:
        flags.append("black frame")
    if out["clipped"] > 25:
        flags.append("blown out")
    if out["magenta"] > 0.05:
        flags.append(f"missing texture? {out['magenta']}% magenta")
    out["flags"] = flags
    return out


def _boxes(black):
    """Connected groups of pure-black tiles that fill their bounding rectangle (>= 2x2 tiles, >= 90 % full): bloom
    smears one NaN pixel into exactly such blocks, while dark scenery is ragged."""
    h, w = black.shape
    seen = np.zeros_like(black, dtype=bool)
    n = 0
    for y in range(h):
        for x in range(w):
            if not black[y, x] or seen[y, x]:
                continue
            stack, cells = [(y, x)], []
            seen[y, x] = True
            while stack:
                cy, cx = stack.pop()
                cells.append((cy, cx))
                for ny, nx in ((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)):
                    if 0 <= ny < h and 0 <= nx < w and black[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
            ys, xs = [c[0] for c in cells], [c[1] for c in cells]
            bh, bw = max(ys) - min(ys) + 1, max(xs) - min(xs) + 1
            if bh >= 2 and bw >= 2 and len(cells) >= 0.9 * bh * bw:
                n += 1
    return n


# ------------------------------------------------------------------------------------------------------- storage
def stem(scene, variant):
    return SCENES / scene / variant


def _rotate(base):
    for ext in ("png", "json"):
        src, dst = Path(f"{base}.after.{ext}"), Path(f"{base}.before.{ext}")
        dst.unlink(missing_ok=True)
        if src.exists():
            src.replace(dst)


def store(scene, variant, png, meta=None, status=None, fingerprint="", extra=None):
    """Visual result: rotate after -> before, file the capture as after; returns the record."""
    base = stem(scene, variant)
    base.parent.mkdir(parents=True, exist_ok=True)
    before = Path(f"{base}.before.png")
    if Path(f"{base}.after.png").exists():
        _rotate(base)
    after = Path(f"{base}.after.png")
    shutil.move(str(png), after)
    m = metrics(Image.open(after), Image.open(before) if before.exists() else None)
    return _write(base, scene, variant, "visual", status or ("flag" if m["flags"] else "pass"), m, fingerprint, meta,
                  extra)


def record(scene, variant, kind, status, metrics_, fingerprint="", meta=None, extra=None):
    """Non-visual result (check, perf, error): same rotation and record, no image."""
    base = stem(scene, variant)
    base.parent.mkdir(parents=True, exist_ok=True)
    if Path(f"{base}.after.json").exists():
        _rotate(base)
        Path(f"{base}.before.png").unlink(missing_ok=True)  # a failed capture keeps no stale picture as `after`
    return _write(base, scene, variant, kind, status, metrics_, fingerprint, meta, extra)


def _write(base, scene, variant, kind, status, m, fingerprint, meta, extra):
    prev = Path(f"{base}.before.json")
    rec = {"scene": scene, "variant": variant, "kind": kind, "status": status,
           "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "fingerprint": fingerprint, "metrics": m,
           "game": meta or {}, **(extra or {})}
    if prev.exists():
        try:
            rec["previous"] = {k: v for k, v in json.loads(prev.read_text(encoding="utf-8")).items()
                               if k in ("status", "metrics", "capturedAt", "fingerprint")}
        except ValueError:
            pass
    Path(f"{base}.after.json").write_text(json.dumps(rec, indent=1), encoding="utf-8")
    return rec


def latest(scene, variant):
    j = Path(f"{stem(scene, variant)}.after.json")
    try:
        return json.loads(j.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def seed_before(scene, variant, png):
    """Use an older capture (e.g. a legacy screenshot of the same pose) as the first `before`."""
    base = stem(scene, variant)
    base.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(png, f"{base}.before.png")


def load(scene, variant):
    base = stem(scene, variant)
    a, b = Path(f"{base}.after.png"), Path(f"{base}.before.png")
    j = Path(f"{base}.after.json")
    return (Image.open(b) if b.exists() else None, Image.open(a) if a.exists() else None,
            json.loads(j.read_text(encoding="utf-8")) if j.exists() else {})


# ------------------------------------------------------------------------------------------------------- images
def _label(img, text, sub="", color=(235, 235, 235)):
    bar = 30 if not sub else 46
    out = Image.new("RGB", (img.width, img.height + bar), (18, 18, 22))
    out.paste(img, (0, bar))
    d = ImageDraw.Draw(out)
    d.text((8, 5), text, font=_font(17), fill=color)
    if sub:
        d.text((8, 26), sub, font=_font(13), fill=(170, 170, 180))
    return out


def _diff_image(before, after):
    a = np.asarray(after.convert("RGB"), dtype=np.int16)
    b = np.asarray(before.convert("RGB").resize(after.size), dtype=np.int16)
    d = np.clip(np.abs(a - b).max(axis=2) * 4, 0, 255).astype(np.uint8)
    heat = np.stack([d, (d * 0.35).astype(np.uint8), (255 - d) // 6], axis=2)  # blue calm -> orange change
    base = (np.asarray(after.convert("L"), dtype=np.uint8) // 4)[..., None]
    return Image.fromarray(np.clip(heat.astype(np.int16) + base, 0, 255).astype(np.uint8))


def compare(scene, variant, width=640):
    """before | after | diff for one scene + variant (after only when there is no before)."""
    before, after, rec = load(scene, variant)
    if after is None:
        return None
    m = rec.get("metrics", {})
    scale = width / after.width
    size = (width, round(after.height * scale))
    sub = f"luma {m.get('luma')}  diff {m.get('diff', '-')}  changed {m.get('changed', '-')}%"
    if m.get("flags"):
        sub += "   ! " + ", ".join(m["flags"])
    panels = [_label(after.resize(size), "after", sub, (255, 214, 120) if m.get("flags") else (235, 235, 235))]
    if before is not None:
        if m.get("notComparable"):  # not the same view / time / weather: shown, never diffed
            panels.insert(0, _label(before.resize(size), "before: NOT COMPARABLE", "; ".join(m["notComparable"]),
                                    (255, 110, 110)))
        else:
            panels.insert(0, _label(before.resize(size), "before", "previous capture"))
            panels.append(_label(_diff_image(before, after).resize(size), "change", "x4 per-pixel difference"))
    h = max(p.height for p in panels)
    out = Image.new("RGB", (sum(p.width for p in panels) + 6 * (len(panels) - 1), h + 34), (10, 10, 12))
    d = ImageDraw.Draw(out)
    d.text((8, 7), f"{scene}  [{variant}]", font=_font(19), fill=(255, 255, 255))
    x = 0
    for p in panels:
        out.paste(p, (x, 34))
        x += p.width + 6
    return out


def combos(scene, variants, width=420):
    """Every variant of one scene side by side (shaders / vanilla / far field off ...), latest captures."""
    panels = []
    for v in variants:
        _, after, rec = load(scene, v)
        if after is not None:
            size = (width, round(after.height * width / after.width))
            panels.append(_label(after.resize(size), v, f"luma {rec.get('metrics', {}).get('luma')}"))
    if len(panels) < 2:
        return None
    out = Image.new("RGB", (sum(p.width for p in panels) + 6 * (len(panels) - 1), panels[0].height + 34), (10, 10, 12))
    ImageDraw.Draw(out).text((8, 7), scene, font=_font(19), fill=(255, 255, 255))
    x = 0
    for p in panels:
        out.paste(p, (x, 34))
        x += p.width + 6
    return out


def stack(images, gap=10):
    images = [i for i in images if i is not None]
    if not images:
        return None
    w = max(i.width for i in images)
    out = Image.new("RGB", (w, sum(i.height for i in images) + gap * (len(images) - 1)), (0, 0, 0))
    y = 0
    for i in images:
        out.paste(i, (0, y))
        y += i.height + gap
    return out


# ------------------------------------------------------------------------------------------------------- reports
REPORTS = STORE / "reports"
KEEP_REPORTS = 15
ALWAYS_KEEP = {"suite-smoke", "gate"}


def report(run_name, rows, title="NirnCraft harness", scope="custom"):
    """Write the run folder (only the latest run is kept): compare images, sheet.png, combos.png, report.html.
    rows: [{"scene", "variant", "metrics", "tags", "title"}] in run order."""
    shutil.rmtree(RUNS, ignore_errors=True)  # (older layouts)
    shutil.rmtree(LATEST, ignore_errors=True)
    run = REPORTS / scope  # one folder per kind of run: a focused run never replaces the smoke report
    shutil.rmtree(run, ignore_errors=True)
    (run / "compare").mkdir(parents=True)
    _prune_reports(keep=run)
    cmp_imgs = []
    for r in rows:
        img = compare(r["scene"], r["variant"])
        if img is not None:
            name = f"{r['scene']}--{r['variant']}.png"
            img.save(run / "compare" / name)
            r["compare"] = f"compare/{name}"
            cmp_imgs.append(img.resize((img.width // 2, img.height // 2)))
    sheets = []
    for i in range(0, len(cmp_imgs), 12):  # keep each sheet a sane size
        s = stack(cmp_imgs[i:i + 12])
        if s is not None:
            p = run / f"sheet{'' if i == 0 else i // 12 + 1}.png"
            s.save(p)
            sheets.append(p.name)
    by_scene = {}
    for r in rows:
        by_scene.setdefault(r["scene"], []).append(r["variant"])
    combo_imgs = [combos(s, v) for s, v in by_scene.items() if len(v) > 1]
    c = stack(combo_imgs)
    if c is not None:
        c.save(run / "combos.png")
    agent_dir, agent_n = agent_pages(run, rows)
    flagged = [r for r in rows if r.get("metrics", {}).get("flags")]
    html = [f"<!doctype html><meta charset=utf-8><title>{title}</title>",
            "<style>body{background:#111;color:#ddd;font:14px system-ui;margin:16px}img{max-width:100%}"
            "td,th{padding:4px 10px;text-align:left}tr.bad{color:#ffb74d}a{color:#8cf}</style>",
            f"<h1>{title}</h1><p>{run_name}: {len(rows)} result(s), {len(flagged)} flagged. Sheets: "
            + " ".join(f"<a href='{s}'>{s}</a>" for s in sheets)
            + (" <a href='combos.png'>combos.png</a>" if c is not None else "")
            + f" Agent pages: {agent_n} capture(s) in <a href='agent/index.txt'>agent/</a></p>",
            "<table><tr><th>scene</th><th>variant</th><th>tags</th><th>luma</th><th>diff</th><th>changed %</th>"
            "<th>flags</th></tr>"]
    for r in rows:
        m = r.get("metrics", {})
        html.append(f"<tr class='{'bad' if m.get('flags') else ''}'><td><a href='#{r['scene']}-{r['variant']}'>"
                    f"{r['scene']}</a></td><td>{r['variant']}</td><td>{' '.join(r.get('tags', []))}</td>"
                    f"<td>{m.get('luma')}</td><td>{m.get('diff', '-')}</td><td>{m.get('changed', '-')}</td>"
                    f"<td>{', '.join(m.get('flags', []))}"
                    + (f" <a href='file:///{r['crashLog']}'>crash log</a>" if r.get("crashLog") else "")
                    + "</td></tr>")
    html.append("</table>")
    for r in rows:
        if r.get("compare"):
            html.append(f"<h3 id='{r['scene']}-{r['variant']}'>{r.get('title') or r['scene']} [{r['variant']}]</h3>"
                        f"<img src='{r['compare']}'>")
    (run / "report.html").write_text("\n".join(html), encoding="utf-8")
    return run

# ------------------------------------------------------------------------------------------------------- agent pages
AGENT_TILE = 760   # px per image: enough for an agent to see a broken texture, a black box, a missing NPC
AGENT_ROWS = 3     # rows per page: a page stays under ~1550 px, about what one agent image read resolves


def agent_pages(run, rows, include_unchanged=False):
    """Pages made for agents: before | after pairs at a size they can actually judge, problems first, unchanged captures
    left out; page-NN.png plus index.txt (which row is which test, with its numbers). More detail: harness.py zoom."""
    def weight(r):
        m = r.get("metrics", {})
        return (0 if m.get("flags") else 1, -(m.get("changed") or 0))
    picked = []
    for r in sorted(rows, key=weight):
        m = r.get("metrics", {})
        if not include_unchanged and not m.get("flags") and (m.get("changed") is not None and m["changed"] < 1.0):
            continue
        before, after, _ = load(r["scene"], r["variant"])
        if after is not None:
            picked.append((r, before, after))
    d = run / "agent"
    d.mkdir(parents=True, exist_ok=True)
    index = []
    for p in range(0, len(picked), AGENT_ROWS):
        tiles = []
        for n, (r, before, after) in enumerate(picked[p:p + AGENT_ROWS]):
            if after.width > 2.2 * after.height:  # a strip (ladder / sweep / frames): full width, before under it
                m = r.get("metrics", {})
                wide = (2 * AGENT_TILE + 8, round(after.height * (2 * AGENT_TILE + 8) / after.width))
                head = f"{n + 1}. {r['scene']} [{r['variant']}]"
                sub = "; ".join(m.get("flags", []))[:120] or f"changed {m.get('changed', '-')}%"
                parts = [_label(after.resize(wide), head + " (after)", sub,
                                (255, 214, 120) if m.get("flags") else (235, 235, 235))]
                if before is not None:
                    parts.append(_label(before.resize(wide), "before", ""))
                tiles.append(stack(parts, 4))
                index.append(f"page-{p // AGENT_ROWS + 1:02d} row {n + 1}: {r['scene']} [{r['variant']}] strip; {sub}")
                continue
            size = (AGENT_TILE, round(after.height * AGENT_TILE / after.width))
            m = r.get("metrics", {})
            head = f"{n + 1}. {r['scene']} [{r['variant']}]"
            sub = f"luma {m.get('luma')} diff {m.get('diff', '-')} changed {m.get('changed', '-')}%"
            if m.get("flags"):
                sub += "  ! " + ", ".join(m["flags"])[:90]
            left = _label(before.resize(size), "before", "") if before is not None else _label(Image.new("RGB", size, (30, 30, 34)), "before", "(first capture)")
            right = _label(after.resize(size), head, sub, (255, 214, 120) if m.get("flags") else (235, 235, 235))
            row = Image.new("RGB", (left.width + right.width + 8, max(left.height, right.height)), (10, 10, 12))
            row.paste(left, (0, 0))
            row.paste(right, (left.width + 8, 0))
            tiles.append(row)
            index.append(f"page-{p // AGENT_ROWS + 1:02d} row {n + 1}: {r['scene']} [{r['variant']}] {sub}")
        stack(tiles, 8).save(d / f"page-{p // AGENT_ROWS + 1:02d}.png")
    (d / "index.txt").write_text(chr(10).join(index) + chr(10), encoding="utf-8")
    return d, len(picked)


def zoom(scene, variant, crop=None, cell=None, which="both", out=None):
    """Full-resolution crop of before/after (x,y,w,h in after pixels, or cell "c,r" of a 3x3 grid)."""
    before, after, _ = load(scene, variant)
    if after is None:
        raise SystemExit(f"no capture for {scene} [{variant}]")
    W, H = after.size
    if cell:
        c, r = (int(v) for v in cell.split(","))
        crop = (c * W // 3, r * H // 3, W // 3, H // 3)
    x, y, w, h = crop or (0, 0, W, H)
    box = (x, y, x + w, y + h)
    imgs = []
    if which in ("both", "before") and before is not None:
        imgs.append(_label(before.resize(after.size).crop(box), "before", f"{box}"))
    if which in ("both", "after"):
        imgs.append(_label(after.crop(box), "after", f"{box}"))
    scale = min(1.0, 1500 / max(1, sum(i.width for i in imgs)))
    imgs = [i.resize((round(i.width * scale), round(i.height * scale))) for i in imgs]
    row = Image.new("RGB", (sum(i.width for i in imgs) + 8 * (len(imgs) - 1), max(i.height for i in imgs)), (10, 10, 12))
    xx = 0
    for i in imgs:
        row.paste(i, (xx, 0))
        xx += i.width + 8
    out = Path(out or STORE / "zoom" / f"{scene}--{variant}--{x}-{y}-{w}-{h}.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    row.save(out)
    return out


def _prune_reports(keep):
    if not REPORTS.exists():
        return
    dirs = sorted((d for d in REPORTS.iterdir() if d.is_dir()), key=lambda d: d.stat().st_mtime, reverse=True)
    for d in dirs[KEEP_REPORTS:]:
        if d != keep and d.name not in ALWAYS_KEEP:
            shutil.rmtree(d, ignore_errors=True)


def overview(scenes):
    """.work/harness/index.html from every stored result (not one run): each test's latest status by tier and suite,
    problems first, links to every scope report and to the images. Rebuilt after each run; cheap (JSON only)."""
    import html
    by_id = {s["id"]: s for s in scenes}
    recs = []
    if SCENES.exists():
        for f in SCENES.glob("*/*.after.json"):
            try:
                recs.append(json.loads(f.read_text(encoding="utf-8")))
            except ValueError:
                continue
    order = {"crash": 0, "error": 1, "fail": 2, "flag": 3, "skip": 4, "pass": 5}
    recs.sort(key=lambda r: (order.get(r.get("status"), 9), r.get("scene", "")))
    groups = {}
    for r in recs:
        s = by_id.get(r.get("scene"), {})
        suite = next((t[6:] for t in s.get("tags", []) if t.startswith("suite:")), "-")
        g = groups.setdefault((s.get("tier", "?"), suite), {})
        g[r.get("status")] = g.get(r.get("status"), 0) + 1
    reports = sorted((d for d in REPORTS.iterdir() if (d / "report.html").exists()), key=lambda d: -d.stat().st_mtime) \
        if REPORTS.exists() else []
    out = ["<!doctype html><meta charset=utf-8><title>NirnCraft harness overview</title>",
           "<style>body{background:#111;color:#ddd;font:14px system-ui;margin:16px}td,th{padding:3px 9px;text-align:left}"
           "a{color:#8cf}.bad{color:#ffb74d}.crash,.error{color:#ff7a7a}</style>",
           f"<h1>NirnCraft harness</h1><p>{len(recs)} test result(s) stored; updated {time.strftime('%Y-%m-%d %H:%M')}"
           + _issue_line() + "</p>",
           "<h2>Reports</h2><ul>"]
    for d in reports:
        out.append(f"<li><a href='reports/{d.name}/report.html'>{d.name}</a> "
                   f"({time.strftime('%m-%d %H:%M', time.localtime(d.stat().st_mtime))})"
                   + (f" &middot; <a href='reports/{d.name}/agent/index.txt'>agent pages</a>"
                      if (d / 'agent/index.txt').exists() else "") + "</li>")
    out.append("</ul><h2>By tier and suite</h2><table><tr><th>tier</th><th>suite</th>"
               + "".join(f"<th>{k}</th>" for k in order) + "</tr>")
    for (tier, suite), c in sorted(groups.items()):
        out.append(f"<tr><td>{tier}</td><td>{html.escape(suite)}</td>" + "".join(f"<td>{c.get(k, '')}</td>" for k in order) + "</tr>")
    out.append("</table>")
    out += seam_matrix(recs, by_id)
    out += tool_validation(recs)
    out.append("<h2>Needs attention</h2><table><tr><th>status</th><th>test</th><th>when</th><th>why</th></tr>")
    for r in recs:
        if r.get("status") in ("pass", "skip"):
            continue
        sid, v = r.get("scene"), r.get("variant")
        why = r.get("error") or "; ".join((r.get("fails") or []) + (r.get("metrics", {}).get("flags") or []))
        img = f" <a href='results/{sid}/{v}.after.png'>image</a>" if (SCENES / sid / f"{v}.after.png").exists() else ""
        crash = f" <a href='results/{sid}/{v}.crash.txt'>crash log</a>" if (SCENES / sid / f"{v}.crash.txt").exists() else ""
        out.append(f"<tr class='{r.get('status')} bad'><td>{r.get('status')}</td><td>{sid} [{v}]{img}{crash}</td>"
                   f"<td>{r.get('capturedAt', '')}</td><td>{html.escape(str(why))[:220]}</td></tr>")
    out.append("</table>")
    (STORE / "index.html").write_text("\n".join(out), encoding="utf-8")
    return STORE / "index.html"

# ------------------------------------------------------------------------------------------------------- strips + pop
def strip(imgs, labels, tile_w=480):
    """Several captures as one labelled grid (rows of up to 5): ladders, sweeps, animation frames."""
    cols = min(5, len(imgs))
    rows = (len(imgs) + cols - 1) // cols
    th = round(imgs[0].height * tile_w / imgs[0].width)
    out = Image.new("RGB", (cols * tile_w, rows * (th + 22)), (10, 10, 12))
    d = ImageDraw.Draw(out)
    for i, (im, lab) in enumerate(zip(imgs, labels)):
        x, y = (i % cols) * tile_w, (i // cols) * (th + 22)
        out.paste(im.resize((tile_w, th)), (x, y + 22))
        if lab:
            d.text((x + 6, y + 3), lab, font=_font(14), fill=(235, 235, 235))
    return out


def _centre_diff(a, b, frac=0.4):
    w, h = 320, round(320 * a.height / a.width)
    a = np.asarray(a.resize((w, h)), dtype=np.float32)
    b = np.asarray(b.resize((w, h)), dtype=np.float32)
    y0, y1 = int(h * (0.5 - frac / 2)), int(h * (0.5 + frac / 2))
    x0, x1 = int(w * (0.5 - frac / 2)), int(w * (0.5 + frac / 2))
    return float(np.abs(a[y0:y1, x0:x1] - b[y0:y1, x0:x1]).mean())


def _lab(img, w=160):
    """CIE Lab of a downscaled copy (D65), as float arrays: perceptual colour comparisons."""
    a = np.asarray(img.convert("RGB").resize((w, round(w * img.height / img.width))), dtype=np.float32) / 255.0
    a = np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)
    xyz = a @ np.array([[0.4124, 0.2126, 0.0193], [0.3576, 0.7152, 0.1192], [0.1805, 0.0722, 0.9505]], dtype=np.float32)
    xyz /= np.array([0.95047, 1.0, 1.08883], dtype=np.float32)
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)


def fidelity(near, far):
    """How well the far field alone reproduces the real world in the same view (ground part of the frame, below the
    upper 35 %): mean colour difference (Delta E), brightness ratio, and large-scale structure difference (8 px blur)."""
    a, b = _lab(near), _lab(far)
    h = a.shape[0]
    a, b = a[int(h * 0.35):], b[int(h * 0.35):]
    de = float(np.sqrt(((a.mean(axis=(0, 1)) - b.mean(axis=(0, 1))) ** 2).sum()))
    luma = float(b[..., 0].mean() / max(a[..., 0].mean(), 1e-3))
    sa = np.asarray(near.convert("L").resize((40, 22)), dtype=np.float32)
    sb = np.asarray(far.convert("L").resize((40, 22)), dtype=np.float32)
    struct = float(np.abs(sa[8:] - sb[8:]).mean())
    return round(de, 2), round(luma, 3), round(struct, 2)


def twin_compare(lab_png, live_png, out_png):
    """A lab render against its live twin, measured instead of eyeballed: both cropped to the same aspect (centre) and
    scaled alike; colour dE, brightness ratio and large-scale structure of the ground part (fidelity) plus the skyline
    (per column, the first row that is not sky, as a share of the height: where the land meets the sky). Writes the
    side-by-side to out_png as the evidence."""
    a, b = Image.open(lab_png).convert("RGB"), Image.open(live_png).convert("RGB")
    ar = min(a.width / a.height, b.width / b.height)

    def crop(im):
        w, h = im.width, im.height
        if w / h > ar:
            nw = int(h * ar)
            im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
        else:
            nh = int(w / ar)
            im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
        return im.resize((640, int(640 / ar)))
    a, b = crop(a), crop(b)
    de, luma, struct = fidelity(a, b)

    def skyline(im):
        g = np.asarray(im.convert("L").resize((64, 64)), dtype=np.float32)
        sky = g[:6].mean(axis=0)  # the top rows are sky in a horizon view
        rows = np.argmax(np.abs(g - sky) > 28, axis=0)
        return rows / 64.0
    sky = float(np.abs(skyline(a) - skyline(b)).mean() * 100)
    side = Image.new("RGB", (a.width * 2 + 8, a.height), (20, 20, 24))
    side.paste(a, (0, 0))
    side.paste(b, (a.width + 8, 0))
    side.save(out_png)
    return {"twinDE": de, "twinLuma": luma, "twinStructure": struct, "twinSkyline": round(sky, 2)}


def offline_vs_live(live_png, lab_png, mask_png, out_png):
    """The offline render of a live capture's own view against that capture, where the offline frame shows the
    exported blocks (its mask: the live view also shows land beyond them): mean colour dE there, the share of the
    frame compared, and the edge agreement (structure: do the outlines fall in the same places). Writes the sheet
    live | offline | difference (dE as heat, grey outside the mask) to out_png."""
    a = Image.open(live_png).convert("RGB")
    b = Image.open(lab_png).convert("RGB").resize(a.size)
    m = np.asarray(Image.open(mask_png).convert("L").resize(a.size), dtype=np.float32) / 255.0 if Path(mask_png).exists()         else np.ones((a.height, a.width), np.float32)
    la, lb = (np.asarray(_lab(x, w=a.width), dtype=np.float32) for x in (a, b))
    de = np.sqrt(((la - lb) ** 2).sum(axis=2))
    inside = m > 0.5
    share = float(inside.mean())
    mean_de = float(de[inside].mean()) if inside.any() else 0.0
    ga, gb = (np.asarray(x.convert("L"), dtype=np.float32) for x in (a, b))

    def edges(g):
        gx = np.abs(np.diff(g, axis=1, prepend=g[:, :1]))
        gy = np.abs(np.diff(g, axis=0, prepend=g[:1]))
        return (gx + gy) > 24
    ea, eb = edges(ga) & inside, edges(gb) & inside
    struct = float((ea & eb).sum() / max(1, (ea | eb).sum()))
    heat = np.clip(de / 30.0, 0, 1)
    hm = np.stack([heat * 255, (1 - np.abs(heat - 0.5) * 2) * 180, (1 - heat) * 120], axis=2)
    hm[~inside] = 60
    side = Image.new("RGB", (a.width * 3 + 16, a.height), (20, 20, 24))
    side.paste(a, (0, 0))
    side.paste(b, (a.width + 8, 0))
    side.paste(Image.fromarray(hm.astype(np.uint8)), (2 * a.width + 16, 0))
    side.save(out_png)
    # missing: where the real blocks have something but the live picture shows sky (its colour from the frame's top
    # rows): a far field that did not draw, a hole at the seam. Not judged by dE alone: that averages it away.
    top = la[: max(2, la.shape[0] // 40)].reshape(-1, 3)
    sky = np.median(top, axis=0)
    near_sky = np.sqrt(((la - sky) ** 2).sum(axis=2)) < 6.0
    missing = float((near_sky & inside).sum() / max(1, inside.sum()))
    # flat: where the real blocks show texture but the live picture is featureless (a fogged wall, an empty patch in
    # fog colour: not sky-coloured, so `missing` does not see it), from the local spread of lightness (5 x 5)
    def spread(L):
        from PIL import ImageFilter
        im = Image.fromarray(np.clip(L * 2.55, 0, 255).astype(np.uint8))
        mean = np.asarray(im.filter(ImageFilter.BoxBlur(2)), dtype=np.float32)
        sq = Image.fromarray(np.clip((L * 2.55) ** 2 / 255.0, 0, 255).astype(np.uint8))
        msq = np.asarray(sq.filter(ImageFilter.BoxBlur(2)), dtype=np.float32) * 255.0
        return np.sqrt(np.maximum(msq - mean ** 2, 0)) / 2.55
    sa, sb = spread(la[..., 0]), spread(lb[..., 0])
    flat = float(((sa < 1.5) & (sb > 4.0) & inside).sum() / max(1, inside.sum()))
    out = {"liveDE": round(mean_de, 2), "liveShare": round(share, 3), "liveEdges": round(struct, 3),
           "liveMissing": round(missing, 4), "liveFlat": round(flat, 4)}
    # by distance (the offline render's out_depth.png: 1/16 blocks in 24 bits, all ones for sky): where the error is
    # (far trees thinning, far materials flattening show as the far bands' dE rising)
    depth_png = Path(str(lab_png)[:-4] + "_depth.png")
    if depth_png.exists():
        dp = np.asarray(Image.open(depth_png).convert("RGB").resize(a.size, Image.NEAREST), dtype=np.uint32)
        dist = ((dp[..., 0] << 16) | (dp[..., 1] << 8) | dp[..., 2]).astype(np.float64) / 16.0
        bands = {}
        for lo, hi in ((0, 64), (64, 192), (192, 512), (512, 1024), (1024, 4096)):
            sel = inside & (dist >= lo) & (dist < hi)
            if sel.sum() > 200:
                bands[f"{lo}-{hi}"] = {"dE": round(float(de[sel].mean()), 2), "share": round(float(sel.mean()), 3)}
        out["liveDEByBand"] = bands
    return out


def pop_metrics(scene, imgs):
    """Ladder: how much the subject changes between the two rungs that straddle the near/far hand-off (the same view
    16 blocks apart: a big change is a seam or pop). Sweep: frame-to-frame change while flying through the hand-off;
    a spike far above the flight's usual change is a pop-in / pop-out (its frame says where)."""
    p = scene.get("pose") or {}
    m, flags = {}, []
    if scene.get("fidelity") and "ladder" in p:  # pairs: real chunks, far field only, per height
        hs = p["ladder"].get("heights") or p["ladder"]["distances"]
        worst = (0, 0, 0)
        for i in range(0, len(imgs) - 1, 2):
            de, luma, struct = fidelity(imgs[i], imgs[i + 1])
            tag = f"{hs[i // 2]}m"
            m[f"colorDE_{tag}"], m[f"lumaRatio_{tag}"], m[f"structure_{tag}"] = de, luma, struct
            worst = max(worst, (de, struct, abs(1 - luma)))
            if de > 8:
                flags.append(f"far field colour off by dE {de} at {tag} up")
            if abs(1 - luma) > 0.15:
                flags.append(f"far field {'brighter' if luma > 1 else 'darker'} x{luma} at {tag} up")
            if struct > 20:
                flags.append(f"far field shapes differ ({struct}) at {tag} up")
        m["fidelityDE"], m["fidelityStructure"] = worst[0], worst[1]
        if flags:
            m["popFlags"] = flags
        return m
    if "ladder" in p:
        d = p["ladder"]["distances"]
        pair = [i for i in range(len(d) - 1) if d[i] < 176 <= d[i + 1]]
        if pair:
            i = pair[0]
            m["handoffDiff"] = round(_centre_diff(imgs[i], imgs[i + 1]), 2)
            m["handoffColorDE"] = fidelity(imgs[i], imgs[i + 1])[0]  # colour jump across the ring
            if m["handoffColorDE"] > 6:
                flags.append(f"colour jumps by dE {m['handoffColorDE']} across the hand-off")
            # control pairs: the same step wholly inside or wholly outside the ring; a step changes the view about in
            # proportion to step / distance, so each is scaled to the hand-off distance
            step = d[i + 1] - d[i]
            ctrl = [_centre_diff(imgs[k], imgs[k + 1]) * d[k] / d[i] for k in range(len(d) - 1)
                    if k != i and d[k + 1] - d[k] == step and k + 1 < len(imgs)]
            if ctrl:
                m["handoffExpected"] = round(float(np.mean(ctrl)), 2)
                m["handoffExcess"] = round(m["handoffDiff"] - m["handoffExpected"], 2)
                if m["handoffExcess"] > 10:
                    flags.append(f"hand-off change {m['handoffDiff']} between {d[i]} m and {d[i + 1]} m "
                                 f"({m['handoffExcess']} over the {m['handoffExpected']} a {step} m step makes anyway)")
            elif m["handoffDiff"] > 18:
                flags.append(f"hand-off change {m['handoffDiff']} between {d[i]} m and {d[i + 1]} m")
    if "sweep" in p and len(imgs) > 3:
        diffs = [_centre_diff(imgs[i], imgs[i + 1]) for i in range(len(imgs) - 1)]
        med = float(np.median(diffs)) or 0.5
        k = int(np.argmax(diffs))
        m.update(popMax=round(max(diffs), 2), popMedian=round(med, 2), popRatio=round(max(diffs) / max(med, 0.5), 2),
                 popFrame=k + 1)
        if m["popRatio"] > 4 and m["popMax"] > 6:
            flags.append(f"pop during the flight at frame {k + 1} (x{m['popRatio']} the usual change)")
    if flags:
        m["popFlags"] = flags
    return m


SEAM_COLUMNS = [  # metric, label, warn, bad (higher is worse; luma ratio as |1 - x|)
    ("handoffDiff", "hand-off change", 10, 18), ("handoffExcess", "hand-off excess", 5, 10), ("handoffColorDE", "hand-off colour dE", 3, 6),
    ("popRatio", "pop in motion (x usual)", 2.5, 4), ("fidelityDE", "far field colour dE", 4, 8),
    ("fidelityStructure", "far field shapes", 12, 20)]


def seam_matrix(recs, by_id):
    """Every distance / seam test's numbers in one grid: rows by category (subject:*), columns by instrument."""
    rows = {}
    for r in recs:
        m = r.get("metrics", {})
        if not any(k in m for k, *_ in SEAM_COLUMNS):
            continue
        s = by_id.get(r.get("scene"), {})
        cat = next((t[8:] for t in s.get("tags", []) if t.startswith("subject:")), "other")
        subject = re.sub(r"^(dist|pop|fid|alt)-", "", r.get("scene", ""))
        rows.setdefault((cat, subject), {}).update({k: m[k] for k, *_ in SEAM_COLUMNS if k in m})
    if not rows:
        return []
    def cell(k, v, warn, bad):
        c = "#5c1f1f" if v >= bad else "#5c4a1f" if v >= warn else "#1f3d24"
        return f"<td style='background:{c}'>{v}</td>"
    out = ["<h2>Seam matrix (Infinite ATLAS at every distance)</h2><table><tr><th>category</th><th>subject</th>"
           + "".join(f"<th>{lab}</th>" for _, lab, *_ in SEAM_COLUMNS) + "</tr>"]
    for (cat, subject), vals in sorted(rows.items()):
        out.append(f"<tr><td>{cat}</td><td>{subject}</td>" + "".join(
            cell(k, vals[k], w, b) if k in vals else "<td></td>" for k, _, w, b in SEAM_COLUMNS) + "</tr>")
    out.append("</table>")
    return out


def tool_validation(recs):
    """Where the tools and render techniques disagree with the game: offline heights / landmarks vs the generated
    world, lab renders vs live twins, camera plans accepted in game, detector self-test."""
    out = ["<h2>Tool validation (offline tools vs the real game)</h2>"]
    world = [r for r in recs if str(r.get("scene", "")).startswith("validate-world-")]
    if world:
        out.append("<table><tr><th>place</th><th>heights within 2</th><th>median err</th><th>bias</th>"
                   "<th>landmarks on / off / mismatch</th><th>biomes</th></tr>")
        for r in world:
            m = r.get("metrics", {})
            out.append(f"<tr class='{'bad' if r.get('status') != 'pass' else ''}'><td>{r['scene'][15:]}</td>"
                       f"<td>{m.get('heightWithin2', '-')}%</td><td>{m.get('heightMedianErr', '-')}</td>"
                       f"<td>{m.get('heightBias', '-')}</td><td>{m.get('landmarksOnline', '-')} / "
                       f"{m.get('landmarksOffline', '-')} / {m.get('landmarkMismatch', '-')}</td><td>{m.get('biomes', '-')}</td></tr>")
        out.append("</table>")
    twins = []
    for r in recs:
        sid = str(r.get("scene", ""))
        if sid.startswith("twin-") and r.get("variant") == "shaders":
            live = SCENES / sid / "shaders.after.png"
            lab = SCENES / sid[5:] / "lab.after.png"
            if live.exists() and lab.exists():
                de, luma, struct = fidelity(Image.open(live), Image.open(lab))
                twins.append((sid[5:], de, luma, struct))
    if twins:
        out.append("<h3>Lab render vs live game (same place, sun, weather)</h3><table><tr><th>lab scene</th>"
                   "<th>colour dE</th><th>brightness lab/live</th><th>shapes</th></tr>")
        for name, de, luma, struct in twins:
            c = "bad" if de > 8 or struct > 20 or abs(1 - luma) > 0.2 else ""
            out.append(f"<tr class='{c}'><td>{name}</td><td>{de}</td><td>{luma}</td><td>{struct}</td></tr>")
        out.append("</table>")
    planned = [r for r in recs if "camera" in r]
    if planned:
        first = sum(1 for r in planned if r.get("camera") == 0)
        out.append(f"<p>Planned cameras accepted in game at the first candidate: {first}/{len(planned)} "
                   f"({100 * first // max(1, len(planned))}%)</p>")
    conf = [r["metrics"]["biomeConfirmed"] for r in recs if "biomeConfirmed" in r.get("metrics", {})]
    lconf = [r["metrics"]["landmarkConfirmed"] for r in recs if "landmarkConfirmed" in r.get("metrics", {})]
    if conf or lconf:
        out.append(f"<p>Feature index confirmed online: biomes {sum(conf)}/{len(conf)}, "
                   f"landmarks {sum(lconf)}/{len(lconf)}</p>")
    det = next((r for r in recs if r.get("scene") == "check-detectors"), None)
    if det:
        out.append(f"<p>Image detectors self-test: {det.get('status')}</p>")
    return out


def _issue_line():
    try:
        led = json.loads((STORE / "issues.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return ""
    c = {}
    for e in led.values():
        c[e.get("state")] = c.get(e.get("state"), 0) + 1
    return " &middot; issues: " + ", ".join(f"{c.get(s, 0)} {s}" for s in ("open", "in-progress", "testing",
                                                                        "needs-review", "fixed")) + " (harness.py issues)"


# ------------------------------------------------------------------------------------------------ subject sheets
SHEETS = STORE / "sheets"
SHEET_W = 1600                 # what a reviewer can still judge per page
LEVELS = ("full", "top", "pair", "single")


def _panel(scene_id, title, width):
    """The latest capture of a scene (shaders preferred), labelled; None when it was never captured."""
    for v in ("shaders", "vanilla", "lab", "measure"):
        png = Path(f"{stem(scene_id, v)}.after.png")
        roads = png.with_name(f"{v}.roads.png")  # a town view with its roads drawn on reads better on a sheet
        if roads.exists():
            png = roads
        if png.exists():
            rec = latest(scene_id, v) or {}
            im = Image.open(png).convert("RGB")
            im = im.resize((width, round(im.height * width / im.width)))
            flags = (rec.get("metrics") or {}).get("flags") or []
            return _label(im, title, "; ".join(flags)[:120] if flags else f"[{v}] {rec.get('status', '')}",
                          (255, 214, 120) if flags else (235, 235, 235)), bool(flags)
    return None


def subject_sheet(subject, roles, level="top", view="front", top=2):
    """One subject on a sheet: roles is [(role, scene_id, title)] with role front / side / animation / other.
    Levels: full (every view, several pages when needed), top (front, side, animation and the `top` other views,
    flagged ones first), pair (front | side), single (`view`: front or side). Returns the written page paths."""
    by_role = {}
    for role, sid, title in roles:
        by_role.setdefault(role, []).append((sid, title))
    if level == "single":
        pick = by_role.get(view) or by_role.get("front") or by_role.get("side") or []
        rows = [[pick[0]]] if pick else []
    elif level == "pair":
        rows = [[x for x in (by_role.get("front", [])[:1] + by_role.get("side", [])[:1])]]
    else:
        rows = [by_role.get("front", [])[:1] + by_role.get("side", [])[:1]]
        rows += [[a] for a in by_role.get("animation", [])]  # strips: a row of their own
        others = by_role.get("other", [])
        if level == "top":
            scored = []
            for sid, title in others:
                got = _panel(sid, title, 400)
                if got:
                    scored.append((0 if got[1] else 1, sid, title))
            others = [(sid, title) for _, sid, title in sorted(scored)[:top]]
        rows += [others[i:i + 2] for i in range(0, len(others), 2)]
    rows = [r for r in rows if r]
    imgs = []
    for r in rows:
        w = SHEET_W // len(r) - (6 if len(r) > 1 else 0)
        panels = [x[0] for x in (_panel(sid, title, w) for sid, title in r) if x]
        if panels:
            h = max(pn.height for pn in panels)
            row = Image.new("RGB", (SHEET_W, h), (10, 10, 12))
            x = 0
            for pn in panels:
                row.paste(pn, (x, 0))
                x += pn.width + 6
            imgs.append(row)
    if not imgs:
        return []
    out = SHEETS / subject
    out.mkdir(parents=True, exist_ok=True)
    for f in out.glob(f"{level}*.png"):
        f.unlink()
    pages, page, height = [], [], 0
    for im in imgs:  # pages up to ~square so a reviewer still sees detail
        if page and height + im.height > SHEET_W:
            pages.append(page)
            page, height = [], 0
        page.append(im)
        height += im.height + 6
    pages.append(page)
    paths = []
    for n, page in enumerate(pages, 1):
        sheet = Image.new("RGB", (SHEET_W, sum(i.height + 6 for i in page) + 36), (10, 10, 12))
        d = ImageDraw.Draw(sheet)
        d.text((8, 8), f"{subject}  [{level}]" + (f"  page {n}/{len(pages)}" if len(pages) > 1 else ""),
               font=_font(20), fill=(255, 255, 255))
        y = 36
        for im in page:
            sheet.paste(im, (0, y))
            y += im.height + 6
        f = out / (f"{level}.png" if len(pages) == 1 else f"{level}-p{n}.png")
        sheet.save(f)
        paths.append(f)
    return paths


def autocrop(src, dest, target=1400, gain=1.35):
    """A drawing on a plain dark background (a dungeon cutaway): cropped to what is drawn, scaled up crisp (nearest)
    to about `target` pixels and brightened, so a reviewer sees the structure, not the empty canvas."""
    im = Image.open(src).convert("RGB")
    a = np.asarray(im, dtype=np.int16)
    bg = a[0, 0]
    mask = np.abs(a - bg).sum(axis=2) > 24
    ys, xs = np.nonzero(mask)
    if len(xs):
        pad = 12
        im = im.crop((max(0, xs.min() - pad), max(0, ys.min() - pad), min(im.width, xs.max() + pad),
                      min(im.height, ys.max() + pad)))
    s = max(1, int(target / max(im.width, im.height)))
    if s > 1:
        im = im.resize((im.width * s, im.height * s), Image.NEAREST)
    im = Image.fromarray(np.clip(np.asarray(im, dtype=np.float32) * gain, 0, 255).astype(np.uint8))
    im.save(dest)
    return dest

