"""Result store for the NirnCraft test harness: before/after rotation, image metrics, compare images and sheets.

Layout (all under .work/harness, never in git):

    scenes/<scene>/<variant>.after.png|json    the newest capture of that scene + variant
    scenes/<scene>/<variant>.before.png|json   the capture before it (older ones are deleted)
    runs/<stamp>/                               the latest run only: report.html, sheet.png (all scenes, before|after),
                                                combos.png (every variant of a scene side by side), compare/*.png

A new capture moves `after` to `before` and drops the old `before`, so iterating with live reload always compares the
last two looks of the same pose.
"""
import json
import shutil
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / ".work/harness"
SCENES = STORE / "scenes"
RUNS = STORE / "runs"


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
    out = {
        "luma": round(mean, 1),
        "p1": round(float(np.percentile(luma, 1)), 1),
        "p99": round(float(np.percentile(luma, 99)), 1),
        # tiles that are pure black in a frame that is not dark overall: the NaN / black-box signature
        "blackTiles": int((tiles < 3).sum()) if mean > 40 else 0,
        "clipped": round(float((a.max(axis=2) >= 254).mean()) * 100, 2),
    }
    if before is not None:
        b = np.asarray(before.convert("RGB").resize(img.size), dtype=np.float32)
        d = np.abs(a - b).max(axis=2)
        out["diff"] = round(float(d.mean()), 2)
        out["changed"] = round(float((d > 24).mean()) * 100, 1)
    flags = []
    if out["blackTiles"]:
        flags.append(f"{out['blackTiles']} black tiles")
    if mean < 6:
        flags.append("black frame")
    if out["clipped"] > 25:
        flags.append("blown out")
    out["flags"] = flags
    return out


# ------------------------------------------------------------------------------------------------------- storage
def stem(scene, variant):
    return SCENES / scene / variant


def store(scene, variant, png, meta=None):
    """Rotate after -> before and file the new capture as after; returns its metrics."""
    base = stem(scene, variant)
    base.parent.mkdir(parents=True, exist_ok=True)
    after, before = Path(f"{base}.after.png"), Path(f"{base}.before.png")
    if after.exists():
        for ext in ("png", "json"):
            src, dst = Path(f"{base}.after.{ext}"), Path(f"{base}.before.{ext}")
            if src.exists():
                dst.unlink(missing_ok=True)
                src.replace(dst)
    shutil.move(str(png), after)
    img = Image.open(after)
    m = metrics(img, Image.open(before) if before.exists() else None)
    record = {"scene": scene, "variant": variant, "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "metrics": m,
              "game": meta or {}}
    Path(f"{base}.after.json").write_text(json.dumps(record, indent=1), encoding="utf-8")
    return m


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
def report(run_name, rows, title="NirnCraft harness"):
    """Write the run folder (only the latest run is kept): compare images, sheet.png, combos.png, report.html.
    rows: [{"scene", "variant", "metrics", "tags", "title"}] in run order."""
    RUNS.mkdir(parents=True, exist_ok=True)
    for old in RUNS.iterdir():  # older runs go; their captures live on as `before` in scenes/
        shutil.rmtree(old, ignore_errors=True)
    run = RUNS / run_name
    (run / "compare").mkdir(parents=True)
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
    flagged = [r for r in rows if r.get("metrics", {}).get("flags")]
    html = [f"<!doctype html><meta charset=utf-8><title>{title}</title>",
            "<style>body{background:#111;color:#ddd;font:14px system-ui;margin:16px}img{max-width:100%}"
            "td,th{padding:4px 10px;text-align:left}tr.bad{color:#ffb74d}a{color:#8cf}</style>",
            f"<h1>{title}</h1><p>{len(rows)} capture(s), {len(flagged)} flagged. Sheets: "
            + " ".join(f"<a href='{s}'>{s}</a>" for s in sheets)
            + (" <a href='combos.png'>combos.png</a>" if c is not None else "") + "</p>",
            "<table><tr><th>scene</th><th>variant</th><th>tags</th><th>luma</th><th>diff</th><th>changed %</th>"
            "<th>flags</th></tr>"]
    for r in rows:
        m = r.get("metrics", {})
        html.append(f"<tr class='{'bad' if m.get('flags') else ''}'><td><a href='#{r['scene']}-{r['variant']}'>"
                    f"{r['scene']}</a></td><td>{r['variant']}</td><td>{' '.join(r.get('tags', []))}</td>"
                    f"<td>{m.get('luma')}</td><td>{m.get('diff', '-')}</td><td>{m.get('changed', '-')}</td>"
                    f"<td>{', '.join(m.get('flags', []))}</td></tr>")
    html.append("</table>")
    for r in rows:
        if r.get("compare"):
            html.append(f"<h3 id='{r['scene']}-{r['variant']}'>{r.get('title') or r['scene']} [{r['variant']}]</h3>"
                        f"<img src='{r['compare']}'>")
    (run / "report.html").write_text("\n".join(html), encoding="utf-8")
    return run
