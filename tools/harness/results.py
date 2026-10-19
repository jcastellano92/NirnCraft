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
           f"<h1>NirnCraft harness</h1><p>{len(recs)} test result(s) stored; updated {time.strftime('%Y-%m-%d %H:%M')}</p>",
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
    out.append("</table><h2>Needs attention</h2><table><tr><th>status</th><th>test</th><th>when</th><th>why</th></tr>")
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

