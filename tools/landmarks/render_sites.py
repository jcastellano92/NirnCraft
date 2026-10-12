"""Offline renderer for natural landmarks: top-down plus isometric close-ups of the sites a
`/skycraft-landmarks sample ...` run generated. No Minecraft client; reads the saved world's region files.

usage:
  py -3 -I tools/landmarks/render_sites.py <world_dir> <sample.json> [<sample2.json> ...] --out docs/screens/landmarks
         [--pick N] [--sheet sheet.jpg]

Writes one PNG per site (<tier>_<kind>_<x>_<z>.png: isometric from the south-east next to the top-down view, with a
caption of the kind and name) and, with --sheet, a contact sheet of all of them.
"""
import argparse
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'qa'))
import render_dungeon as RD  # noqa: E402
from mca import World  # noqa: E402


def render_site(world, site, out_dir):
    r = int(site['radius']) + 8
    x, z = site['x'], site['z']
    big = site['tier'] == 'XL'
    r = max(r, 30 if big else 18)
    # first pass: the whole column range, to find the surface and the tallest block
    m0 = RD.Model(world, x - r, z - r, x + r, z + r, -64, 319)
    vis = m0.cl['solid'][m0.A]
    ys, zs, xs = np.nonzero(vis)
    if len(ys) == 0:
        return None
    top = np.where(vis.any(0), vis.shape[0] - 1 - np.argmax(vis[::-1], axis=0), -1)
    ground = int(np.percentile(top[top >= 0], 20)) + m0.y1
    ymax = int(ys.max()) + m0.y1
    y1 = max(-64, ground - 10)
    y2 = min(319, ymax + 3)
    m = RD.Model(world, x - r, z - r, x + r, z + r, y1, y2)
    scale_top = max(4, min(10, 640 // (2 * r + 1)))
    topimg = RD.surface_topdown(m, scale_top)
    iso = RD.iso_render(m, view=0, ymax=y2, ybottom=y1, maxw=900)
    h = max(topimg.height, iso.height)
    sheet = Image.new('RGB', (iso.width + topimg.width + 8, h + 28), (24, 24, 28))
    sheet.paste(iso, (0, 28))
    sheet.paste(topimg, (iso.width + 8, 28))
    d = ImageDraw.Draw(sheet)
    d.text((6, 6), '[%s] %s "%s"  (%s, %s)  at %d,%d  y %d..%d' % (site['tier'], site['kind'], site.get('name', ''),
                                                                site.get('family', ''), site.get('region', ''), x, z, y1, y2),
           fill=(235, 235, 235), font=RD.font(13))
    name = '%s_%s_%d_%d.png' % (site['tier'], site['kind'], x, z)
    path = os.path.join(out_dir, name)
    RD.finish(sheet, path)
    return path


def contact_sheet(paths, out, cols=3, cell=(560, 400)):
    rows = (len(paths) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * cell[0], rows * cell[1]), (16, 16, 18))
    for i, p in enumerate(paths):
        im = Image.open(p).convert('RGB')
        im.thumbnail(cell)
        sheet.paste(im, ((i % cols) * cell[0], (i // cols) * cell[1]))
    sheet.save(out, quality=88)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('world')
    ap.add_argument('samples', nargs='+')
    ap.add_argument('--out', default='docs/screens/landmarks')
    ap.add_argument('--pick', type=int, default=0, help='render at most N sites per sample file (spread over tiers)')
    ap.add_argument('--sheet', default=None)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    world = World(a.world)
    done = []
    for sf in a.samples:
        sites = [s for s in json.load(open(sf, encoding='utf-8')) if s.get('built', True)]
        if a.pick:
            sites = sites[:a.pick]
        for s in sites:
            try:
                p = render_site(world, s, a.out)
            except Exception as e:  # keep going: one bad box must not lose the rest
                print('render failed for', s.get('kind'), s.get('x'), s.get('z'), e)
                continue
            if p:
                print('wrote', p)
                done.append(p)
    if a.sheet and done:
        contact_sheet(done, a.sheet)
        print('sheet', a.sheet)


if __name__ == '__main__':
    main()
