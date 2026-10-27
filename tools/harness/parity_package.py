"""Package paired parity captures for the independent visual review (docs/agents/visual-reviewer.md).

    py -3 tools/harness/parity_package.py <outdir> <label> <left_dir> <left_prefix> <right_dir> <right_prefix>

Rows are `left | right | diff` at the same pose (left plays the "before" role); writes <outdir>/<label>-page1.jpg and
appends index.txt lines: page, row, test, metrics, flags. Needs pngs named <prefix>-p0..p3.png in each folder.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parity_diff

POSES = ['p0', 'p1', 'p2', 'p3']


def main():
    out, label, ldir, lpre, rdir, rpre = sys.argv[1:7]
    out = Path(out); out.mkdir(parents=True, exist_ok=True)
    w, h = 640, 400
    page = Image.new('RGB', (w * 3, h * len(POSES)), (20, 20, 20))
    lines = []
    for row, pose in enumerate(POSES):
        lp, rp = Path(ldir) / f'{lpre}-{pose}.png', Path(rdir) / f'{rpre}-{pose}.png'
        prefix = str(out / f'{label}-{pose}')
        m = parity_diff.diff(str(lp), str(rp), prefix)
        a, b, d = Image.open(lp).convert('RGB'), Image.open(rp).convert('RGB'), Image.open(prefix + '-diff.png').convert('RGB')
        for col, im in enumerate((a, b, d)):
            page.paste(im.resize((w, h)), (col * w, row * h))
        ImageDraw.Draw(page).text((6, row * h + 6), f'{label} {pose}', fill=(255, 255, 0))
        flags = 'SAME-DIR-NOT-COMPARABLE' if str(lp.parent) == str(rp.parent) and lpre == rpre else 'none'
        lines.append(f"{label}-page1.jpg row{row + 1} {label}-{pose} left={lp} right={rp} mean_abs_diff={m['mean_abs_diff']:.2f} "
                     f"over24={m['fraction_over_24'] * 100:.1f}% p99={m['p99_diff']:.0f} flags={flags}")
    page.save(out / f'{label}-page1.jpg', quality=85)
    with open(out / 'index.txt', 'a', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
