"""Cutaway views of the grand caverns of a dungeon export (offline, no client).

  py -3 -I tools/qa/dungeon_cavern_view.py mod/run/qa-exports/<id>.json.gz [out_dir] [--max N]

For every room piece flagged FLAG_GRAND (2048) writes, into out_dir (default docs/screens/dungeon-audit):
  <id>_cavern<k>_half.png    the cavern cut in half along its middle, seen from the cut side (isometric)
  <id>_cavern<k>_dome.png    the roof lifted off, seen from the south-east (isometric)
  <id>_cavern<k>_plan.png    the floor plan at rim level (water blue, lava orange), with markers
Uses the renderer of render_dungeon.py (same palette and legend).
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dungeon_export  # noqa: E402
import render_dungeon as rd  # noqa: E402

FLAG_GRAND = 2048


def grand_pieces(ex):
    return [p for p in ex.pieces if p['kind'] == 'room' and (p['flags'] & FLAG_GRAND)]


def shell(m, depth=2):
    """Keep only rock within `depth` blocks of open space (and everything that is not plain rock): the cavern as a mould."""
    A = m.A
    air = m.cl['air'][A]
    near = air.copy()
    for _ in range(depth):
        n = near.copy()
        n[1:] |= near[:-1]
        n[:-1] |= near[1:]
        n[:, 1:] |= near[:, :-1]
        n[:, :-1] |= near[:, 1:]
        n[:, :, 1:] |= near[:, :, :-1]
        n[:, :, :-1] |= near[:, :, 1:]
        near = n
    keep = near | m.cl['fluid'][A] | ~m.cl['natural'][A]
    m.A = np.where(keep, A, 0).astype(A.dtype)


def plan(m, box, floor, scale=8):
    x1, y1, z1, x2, y2, z2 = box
    sub = m.A[:, z1 - m.z1:z2 - m.z1 + 1, x1 - m.x1:x2 - m.x1 + 1]
    Y, Z, X = sub.shape
    air = m.cl['air'][sub]
    solid = m.cl['solid'][sub]
    fluid = m.cl['fluid'][sub]
    names = np.array(m.names)
    img = np.zeros((Z, X, 3), np.uint8)
    img[:] = (12, 12, 14)
    # the topmost solid or fluid block of every column below the roof, shaded by height
    yy = np.arange(Y)[:, None, None]
    top_ok = (solid | fluid) & (yy <= floor + 6 - m.y1)
    has = top_ok.any(0)
    top = Y - 1 - np.argmax(top_ok[::-1], axis=0)
    zz, xx = np.mgrid[0:Z, 0:X]
    bid = sub[top, zz, xx]
    col = m.col[bid].astype(np.float32)
    shade = 0.55 + 0.9 * np.clip((top - (floor - 18 - m.y1)) / 30.0, 0, 1)
    col *= shade[..., None]
    img[has] = np.clip(col[has], 0, 255).astype(np.uint8)
    wat = np.isin(bid, [i for i, n in enumerate(m.names) if n.endswith('water')]) & has
    lav = np.isin(bid, [i for i, n in enumerate(m.names) if n.endswith('lava')]) & has
    img[wat] = (50, 90, 220)
    img[lav] = (255, 120, 20)
    im = Image.fromarray(img).resize((X * scale, Z * scale), Image.NEAREST)
    d = ImageDraw.Draw(im)
    rd.draw_markers(d, m, scale, floor - 18, floor + 40)
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export')
    ap.add_argument('out', nargs='?', default=os.path.join(HERE, '..', '..', 'docs', 'screens', 'dungeon-audit'))
    ap.add_argument('--max', type=int, default=2)
    a = ap.parse_args()
    ex = dungeon_export.load(a.export)
    os.makedirs(a.out, exist_ok=True)
    w = rd.ExportWorld(ex)
    pieces = grand_pieces(ex)
    print(ex.id, 'grand caverns:', len(pieces))
    for k, pc in enumerate(pieces[:a.max]):
        x1, y1, z1, x2, y2, z2 = pc['box']
        floor = pc['floor']
        pad = 2
        m = rd.Model(w, x1 - pad, z1 - pad, x2 + pad, z2 + pad, y1, y2 + 1)
        full = m.A.copy()
        shell(m)
        cz = (z1 + z2) // 2
        feats = ', '.join(sorted(f for f in pc.get('features', []) if f.startswith(('setpiece', 'camp', 'reward'))))
        base = os.path.join(a.out, '%s_cavern%d' % (ex.id, k))
        half = rd.iso_render(m, view=2, ymax=y2, ybottom=y1, sub=(x1, cz, x2, z2 + pad), maxw=1500, scale=11)
        rd.finish(rd.compose(half, [('cut along the middle, seen from the north; natural rock shown', (120, 120, 120))],
                             '%s: grand cavern %d  %dx%dx%d  %s' % (ex.id, k, x2 - x1 + 1, z2 - z1 + 1, y2 - floor, feats)),
                  base + '_half.png')
        dome = rd.iso_render(m, view=0, ymax=floor + max(6, (y2 - floor) * 6 // 10), ybottom=y1, sub=(x1, z1, x2, z2), maxw=1500, scale=11)
        rd.finish(rd.compose(dome, [('roof lifted off, seen from the south-east', (120, 120, 120))],
                             '%s: grand cavern %d, roof off' % (ex.id, k)), base + '_dome.png')
        m.A = full
        pl = plan(m, (x1, y1, z1, x2, y2, z2), floor)
        rd.finish(rd.compose(pl, [('top-down below the roof; water blue, lava orange; markers as in the level plans', (120, 120, 120))],
                             '%s: grand cavern %d, plan' % (ex.id, k)), base + '_plan.png')


if __name__ == '__main__':
    main()
