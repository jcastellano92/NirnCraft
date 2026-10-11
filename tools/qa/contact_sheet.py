"""Tiles every screenshot in a folder into ONE labelled JPG, so a reviewer looks at a single image.

usage: py -3 -I tools/qa/contact_sheet.py <screenshot dir> <out.jpg> [columns]
"""
import os
import sys

from PIL import Image, ImageDraw


def main():
    src, out = sys.argv[1], sys.argv[2]
    cols = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    if not os.path.isdir(src):
        print("no screenshots at", src)
        return
    files = sorted(f for f in os.listdir(src) if f.lower().endswith((".png", ".jpg", ".jpeg")))
    if not files:
        print("no screenshots in", src)
        return
    tw, th = 480, 270
    rows = (len(files) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows * (th + 16)), (20, 20, 20))
    draw = ImageDraw.Draw(sheet)
    for i, f in enumerate(files):
        try:
            im = Image.open(os.path.join(src, f)).convert("RGB")
        except OSError:
            continue
        im.thumbnail((tw, th))
        x, y = (i % cols) * tw, (i // cols) * (th + 16)
        sheet.paste(im, (x, y + 16))
        draw.text((x + 4, y + 2), f[:70], fill=(230, 220, 160))
    sheet.save(out, quality=82)
    print(f"{len(files)} screenshots -> {out}")


if __name__ == "__main__":
    main()
