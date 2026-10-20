"""Contact sheets of tools/dev/sky_offline.py's images (one row a scene: the dome, the view, the emitter cube), labelled.

    py -3 tools/dev/sky_sheet.py OUT.png scene1 scene2 ... [--dir .work/sky_offline]
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("scenes", nargs="+")
    ap.add_argument("--dir", default=str(ROOT / ".work/sky_offline"))
    a = ap.parse_args()
    d = Path(a.dir)
    rows = []
    for s in a.scenes:
        dome = Image.open(d / f"{s}_dome.png").resize((320, 320))
        view = Image.open(d / f"{s}_view.png").resize((568, 320))
        cube = Image.open(d / f"{s}_cube.png").resize((576, 96))
        row = Image.new("RGB", (320 + 568 + 8, 320 + 96 + 4), (24, 24, 24))
        row.paste(dome, (0, 0))
        row.paste(view, (328, 0))
        row.paste(cube, (0, 324))
        ImageDraw.Draw(row).text((582, 330), s, fill=(230, 230, 230))
        rows.append(row)
    sheet = Image.new("RGB", (rows[0].width, sum(r.height + 6 for r in rows)), (0, 0, 0))
    y = 0
    for r in rows:
        sheet.paste(r, (0, y))
        y += r.height + 6
    sheet.save(a.out)


if __name__ == "__main__":
    main()
