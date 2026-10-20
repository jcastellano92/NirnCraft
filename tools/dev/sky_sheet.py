"""Contact sheets of tools/dev/sky_offline.py's images (one row a scene: the dome, the view, the emitter cube), labelled.

    py -3 tools/dev/sky_sheet.py OUT.png scene1 scene2 ... [--dir .work/sky_offline]
    py -3 tools/dev/sky_sheet.py OUT.png scene1 ... --views   # the view and its chart side by side, stars kept

--views shrinks each view by its brightest pixel in every 2 x 2 (the stars, a pixel or two wide, survive the shrink that
a filter would average away) and sets its chart (sky_offline's <scene>_chart.png: the sky named) beside it.
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]


def shrink(im: Image.Image, width: int) -> Image.Image:
    """Down to about width by 2 x 2 maxima (points kept), then a plain resize for the rest."""
    a = np.asarray(im.convert("RGB"))
    while a.shape[1] >= 2 * width:
        h, w = a.shape[0] // 2 * 2, a.shape[1] // 2 * 2
        a = a[:h, :w].reshape(h // 2, 2, w // 2, 2, 3).max(axis=(1, 3))
    out = Image.fromarray(a)
    return out.resize((width, round(out.height * width / out.width)), Image.LANCZOS) if out.width != width else out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("scenes", nargs="+")
    ap.add_argument("--dir", default=str(ROOT / ".work/sky_offline"))
    ap.add_argument("--views", action="store_true", help="the view and its chart, side by side")
    a = ap.parse_args()
    d = Path(a.dir)
    rows = []
    for s in a.scenes:
        if a.views:
            view = shrink(Image.open(d / f"{s}_view.png"), 960)
            chart_path = d / f"{s}_chart.png"
            chart = shrink(Image.open(chart_path), 960) if chart_path.exists() else Image.new("RGB", view.size)
            row = Image.new("RGB", (view.width * 2 + 8, view.height), (24, 24, 24))
            row.paste(view, (0, 0))
            row.paste(chart, (view.width + 8, 0))
            ImageDraw.Draw(row).text((8, 8), s, fill=(230, 230, 230))
            rows.append(row)
            continue
        dome = Image.open(d / f"{s}_dome.png").resize((320, 320))
        view = Image.open(d / f"{s}_view.png").resize((568, 320))
        cube = Image.open(d / f"{s}_cube.png").resize((576, 96))
        row = Image.new("RGB", (320 + 568 + 8, 320 + 96 + 4), (24, 24, 24))
        row.paste(dome, (0, 0))
        row.paste(view, (328, 0))
        row.paste(cube, (0, 324))
        ImageDraw.Draw(row).text((582, 330), s, fill=(230, 230, 230))
        rows.append(row)
    sheet = Image.new("RGB", (max(r.width for r in rows), sum(r.height + 6 for r in rows)), (0, 0, 0))
    y = 0
    for r in rows:
        sheet.paste(r, (0, y))
        y += r.height + 6
    sheet.save(a.out)


if __name__ == "__main__":
    main()
