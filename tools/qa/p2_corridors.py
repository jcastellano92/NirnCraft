"""P2 check: the terrain of the generated corridors against the baked road plan's profile (docs/WORLDGEN-PIPELINE.md).

usage:
  py -3 -I tools/qa/p2_corridors.py tiles <roadplan.bin> <a>:<b> [<a>:<b> ...]
        prints the autorun command list ('forceload add' tiles of 10x10 chunks along the corridors, '|'-separated,
        '+' for spaces; use as -Dnirncraft.autorun=<list>|stop) and the tile count
  py -3 -I tools/qa/p2_corridors.py measure <world_dir> <roadplan.bin> <a>:<b> [<a>:<b> ...] [--json out.json]
        for every planned vertex of those links (and lateral offsets within the half width): the top solid block of the
        SAVED chunk against floor(profile); also drops > 1 block between consecutive vertices. Run it on a world
        generated with the roads module disabled (RoadsConfig.ENABLED=false), so Paver has not touched the blocks.
Bridge, culvert and tunnel spans are skipped (the terrain is deliberately natural or open there), and so are
vertices within 60 blocks of a site's edge (settlement structures, farms and mines build on their own levels).
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roadplan_lint import load  # noqa: E402

TILE = 160
BRIDGE, CULVERT, TUNNEL = 3, 4, 8
SPAN = ['normal', 'cut', 'fill', 'bridge', 'culvert', 'ford', 'switchback', 'stairs', 'tunnel']


def corridor_segments(plan, pairs):
    want = set()
    for p in pairs:
        a, _, b = p.partition(':')
        want.add((a, b))
        want.add((b, a))
    out = []
    for li, l in enumerate(plan['links']):
        if (l['a'], l['b']) in want:
            out.extend(plan['segs'][si] for si in l['segs'])
    return out


def tiles(plan, pairs):
    got = set()
    for s in corridor_segments(plan, pairs):
        for x, z in zip(s['x'], s['z']):
            for dx in (-24, 24):
                for dz in (-24, 24):
                    got.add((math.floor((x + dx) / TILE), math.floor((z + dz) / TILE)))
    cmds = []
    for tx, tz in sorted(got):
        x0, z0 = tx * TILE, tz * TILE
        cmds.append('forceload+add+%d+%d+%d+%d' % (x0, z0, x0 + TILE - 1, z0 + TILE - 1))
    return cmds


def site_edge_distance(plan, x, z):
    best = 1e9
    for _sid, _t, sx, sz, r, _pad in plan['sites']:
        d = math.hypot(x - sx, z - sz) - r
        if d < best:
            best = d
    return best


def measure(world_dir, plan, pairs, out_json, margin=60):
    from road_measure import Col, analyse_point  # noqa: E402
    from mca import World  # noqa: E402
    cols = Col(World(world_dir))
    stats = {'cells': 0, 'exact': 0, 'off1': 0, 'off2': 0, 'off3': 0, 'unloaded': 0, 'skipped': 0, 'water': 0,
             'near_site': 0}
    worst = []
    drops = 0
    steps = 0
    by_kind = {}
    for s in corridor_segments(plan, pairs):
        prev = None
        for i in range(len(s['x'])):
            sp = s['span'][i]
            if sp in (BRIDGE, CULVERT, TUNNEL):
                stats['skipped'] += 1
                prev = None
                continue
            x, z, h, hw = s['x'][i], s['z'][i], s['h'][i], s['hw']
            if site_edge_distance(plan, x, z) < margin:
                # inside a settlement (its own grammar builds terraces, walls, streets: P5) or within the margin
                stats['near_site'] += 1
                prev = None
                continue
            # centre and the two edges of the bed across the road
            if i + 1 < len(s['x']):
                dx, dz = s['x'][i + 1] - x, s['z'][i + 1] - z
            else:
                dx, dz = x - s['x'][i - 1], z - s['z'][i - 1]
            ln = math.hypot(dx, dz) or 1.0
            nx, nz = -dz / ln, dx / ln
            tops = []
            for off in (0.0, -hw * 0.8, hw * 0.8):
                px, pz = int(math.floor(x + nx * off)), int(math.floor(z + nz * off))
                p = analyse_point(cols, px, pz)
                if p is None:
                    stats['unloaded'] += 1
                    continue
                if p['water'] and sp != 5:
                    stats['water'] += 1
                    continue
                want = int(math.floor(h))
                e = abs(p['sy'] - want)
                stats['cells'] += 1
                k = SPAN[sp]
                by_kind.setdefault(k, [0, 0])
                by_kind[k][0] += 1
                if e == 0:
                    stats['exact'] += 1
                    by_kind[k][1] += 1
                elif e == 1:
                    stats['off1'] += 1
                    stats['above' if p['sy'] > want else 'below'] = stats.get('above' if p['sy'] > want else 'below', 0) + 1
                elif e == 2:
                    stats['off2'] += 1
                else:
                    stats['off3'] += 1
                    if len(worst) < 12:
                        worst.append((int(x), int(z), p['sy'], want, k))
                if off == 0.0:
                    tops.append(p['sy'])
            if tops:
                if prev is not None:
                    steps += 1
                    if abs(tops[0] - prev) > 1:
                        drops += 1
                prev = tops[0]
            else:
                prev = None
    res = dict(stats=stats, drops_gt1=drops, consecutive_pairs=steps, by_span=by_kind, worst=worst)
    print(json.dumps(res, indent=1))
    if out_json:
        json.dump(res, open(out_json, 'w'), indent=1)


def main():
    a = [x for x in sys.argv[1:] if not x.startswith('--')]
    out_json = None
    if '--json' in sys.argv:
        out_json = sys.argv[sys.argv.index('--json') + 1]
        a = [x for x in a if x != out_json]
    if a[0] == 'tiles':
        plan = load(a[1])
        c = tiles(plan, a[2:])
        print('|'.join(c))
        print('%d tiles' % len(c), file=sys.stderr)
    elif a[0] == 'measure':
        plan = load(a[2])
        measure(a[1], plan, a[3:], out_json)
    else:
        raise SystemExit(__doc__)


if __name__ == '__main__':
    main()
