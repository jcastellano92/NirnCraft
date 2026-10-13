"""P3 check: the PAINTED roads of the generated corridors, measured on saved blocks (docs/WORLDGEN-PIPELINE.md).

usage:
  py -3 -I tools/qa/p3_corridors.py measure <world_dir> <roadplan.bin> <a>:<b> [<a>:<b> ...] [--margin N] [--json out.json]
        walks the planned centre line of those links every 2 blocks and reports, outside the margin (default 8 blocks)
        of any site's radius (settlement streets are the grammar's, P5):
          gaps        points whose walking surface is not road material and not a deck/ford (road missing)
          runs        distinct runs of gaps
          drops       consecutive points whose surfaces differ by more than 1 block (stairs spans are not counted)
          tree        leaves or logs within 4 blocks over the surface
          unbridged   points whose top block is water (the road dives in)
          obstacle    solid non-road block 1-2 blocks over the surface
          spans       per span type: points, gaps, and for bridges / culverts / tunnels / stairs / fords the number of
                      spans ([spans, judged, clean]: every sample a clean road, for culverts open to water or air below)
        Generate first: tiles from p2_corridors.py, a fresh world with the roads module ON (RoadPaint is a world
        generation feature; no legacy paver needed), 'skycraft-roads stats' to idle, 'stop'.
  py -3 -I tools/qa/p3_corridors.py pick <roadplan.bin> <a>:<b> ...
        prints one example centre per bridge / culvert / ford / stairs / tunnel / switchback span of those links
        (for render_town.py --box close-ups).
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roadplan_lint import load  # noqa: E402
from p2_corridors import corridor_segments, SPAN  # noqa: E402

NORMAL, CUT, FILL, BRIDGE, CULVERT, FORD, SWITCHBACK, STAIRS, TUNNEL = range(9)


def samples(seg):
    """(x, z, h, span, arc_index) every ~2 blocks along a segment."""
    n = len(seg['x'])
    for i in range(n):
        yield seg['x'][i], seg['z'][i], seg['h'][i], seg['span'][i], i
        if i + 1 < n:
            yield ((seg['x'][i] + seg['x'][i + 1]) / 2, (seg['z'][i] + seg['z'][i + 1]) / 2,
                   (seg['h'][i] + seg['h'][i + 1]) / 2, seg['span'][i], i)


def site_edge(plan, x, z):
    best = 1e9
    for _sid, _t, sx, sz, r, _pad in plan['sites']:
        d = math.hypot(x - sx, z - sz) - r
        if d < best:
            best = d
    return best


def pick(plan, pairs):
    seen = {}
    for s in corridor_segments(plan, pairs):
        for t, f, to, deck, piers in s['spans']:
            k = SPAN[t]
            if k == 'normal' or k == 'cut' or k == 'fill':
                continue
            mid = (f + to) // 2
            x, z = s['x'][mid], s['z'][mid]
            seen.setdefault(k, []).append((int(x), int(z), len(range(f, to + 1))))
    for k, v in sorted(seen.items()):
        v.sort(key=lambda e: -e[2])
        print(k, len(v), 'longest at', v[0][0], v[0][1], 'verts', v[0][2], '; also', [(a, b) for a, b, _ in v[1:4]])


def box_segments(plan, boxes):
    """Segments with a vertex inside any (x, z, half) box, clipped to the vertices inside it."""
    out = []
    for s in plan['segs']:
        for x0, z0, h in boxes:
            idx = [i for i in range(len(s['x'])) if abs(s['x'][i] - x0) <= h and abs(s['z'][i] - z0) <= h]
            if len(idx) >= 2:
                i0, i1 = idx[0], idx[-1]
                c = dict(s)
                for k in ('x', 'z', 'h', 'g', 'w', 'span', 'arc'):
                    c[k] = s[k][i0:i1 + 1]
                c['spans'] = [(t, f - i0, to - i0, d, p) for t, f, to, d, p in s['spans'] if f >= i0 and to <= i1]
                out.append(c)
    return out


def measure(world_dir, plan, pairs, margin, out_json, boxes=None):
    from road_measure import Col, analyse_point, is_water, is_air, is_road  # noqa: E402
    from mca import World  # noqa: E402
    cols = Col(World(world_dir))
    covered = 0
    tot = dict(points=0, gaps=0, runs=0, drops=0, big_drops=0, tree=0, unbridged=0, obstacle=0, unloaded=0, skipped_zone=0)
    by_span = {}
    spans_ok = {}
    examples = {'gap': [], 'tree': [], 'unbridged': [], 'drop': []}
    for s in (box_segments(plan, boxes) if boxes else corridor_segments(plan, pairs)):
        prev = None
        prev_span = None
        prev_h = None
        in_run = False
        span_clean = {}
        vspan = {}
        for k, (t, f, to, deck, piers) in enumerate(s['spans']):
            for v in range(f, to + 1):
                vspan[v] = k
        for x, z, h, sp, i in samples(s):
            if site_edge(plan, x, z) < margin:
                tot['skipped_zone'] += 1
                prev = None
                in_run = False
                continue
            bx, bz = int(math.floor(x)), int(math.floor(z))
            p = analyse_point(cols, bx, bz)
            if p is not None and (sp == TUNNEL or not (p['road'] or p['water'])):
                # the road runs inside the ground (a tunnel, or a bridge under a natural rise the macro plan did not
                # know): judge the floor within a block of the profile height and the bore above it
                cc = cols.column(bx, bz)
                y0, arr = cc
                names = cols.w.names
                fy = int(math.floor(h))
                best = None
                for yy in (fy, fy - 1, fy + 1):
                    if not (0 <= yy - y0 < len(arr) - 3):
                        continue
                    nm = names[arr[yy - y0]]
                    air = all(is_air(names[arr[yy + k - y0]]) or 'lantern' in names[arr[yy + k - y0]] for k in (1, 2))
                    if is_road(nm) and air:
                        best = (yy, nm)
                        break
                if best is not None or sp == TUNNEL:
                    yy, nm = best if best else (fy, names[arr[fy - y0]])
                    covered += 1
                    p = dict(p, sy=yy, name=nm, h=yy + (0.5 if nm.endswith('_slab') else 1.0), water=False,
                             road=best is not None, tree=False, obstacle=False)
            if p is None:
                tot['unloaded'] += 1
                prev = None
                in_run = False
                continue
            k = SPAN[sp]
            row = by_span.setdefault(k, dict(points=0, gaps=0, tree=0, unbridged=0, drops=0))
            row['points'] += 1
            tot['points'] += 1
            gap = (not p['road']) and not p['water']
            if gap:
                tot['gaps'] += 1
                row['gaps'] += 1
                if not in_run:
                    tot['runs'] += 1
                in_run = True
                if sum(1 for e in examples['gap'] if e[2] == k) < 4:
                    examples['gap'].append((bx, bz, k, p['name'].split(':')[-1], round(h, 1)))
            else:
                in_run = False
            if p['water']:
                tot['unbridged'] += 1
                row['unbridged'] += 1
                if len(examples['unbridged']) < 12:
                    examples['unbridged'].append((bx, bz, k))
            if p['tree']:
                tot['tree'] += 1
                row['tree'] += 1
                if len(examples['tree']) < 12:
                    examples['tree'].append((bx, bz, k))
            if p['obstacle']:
                tot['obstacle'] += 1
            steep_tunnel = sp == TUNNEL and prev is not None and abs(p['h'] - prev['h']) > 1.0 and (
                abs(h - prev_h) / 2.0 > 0.25 if prev_h is not None else False)
            if prev is not None and not p['water'] and not prev['water'] and sp != STAIRS and prev_span != STAIRS                     and not steep_tunnel:
                dd = abs(p['h'] - prev['h'])
                if dd > 1.0:
                    tot['drops'] += 1
                    row['drops'] += 1
                    if len(examples['drop']) < 12:
                        examples['drop'].append((bx, bz, k, dd))
                if dd > 3.0:
                    tot['big_drops'] += 1
            if sp in (BRIDGE, CULVERT, FORD, STAIRS, TUNNEL):
                ok = (not gap) and (not p['water']) and not p['tree']
                if sp == CULVERT:
                    # the stream passes under the deck within 2 blocks of the centre line: water or air under the lintel
                    opened = False
                    for ox, oz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2)):
                        cc = cols.column(bx + ox, bz + oz)
                        if cc is None:
                            continue
                        y0, arr = cc
                        names = cols.w.names
                        below = [names[arr[j]] for j in range(max(0, p['sy'] - 3 - y0), max(0, p['sy'] - y0))]
                        if any(is_water(b) or is_air(b) for b in below):
                            opened = True
                            break
                    ok = ok and opened
                span_clean.setdefault(vspan.get(i, -1), []).append(ok)
            prev = p
            prev_span = sp
            prev_h = h
        # a span counts as clean when every sample on it is
        for k, (t, f, to, deck, piers) in enumerate(s['spans']):
            if t in (BRIDGE, CULVERT, FORD, STAIRS, TUNNEL):
                r = spans_ok.setdefault(SPAN[t], [0, 0, 0])  # spans, judged (loaded, outside zones), clean
                r[0] += 1
                vals = span_clean.get(k)
                if vals:
                    r[1] += 1
                    if all(vals):
                        r[2] += 1
    tot['covered_by_ground'] = covered
    res = dict(totals=tot, by_span=by_span, spans=spans_ok, examples=examples, margin=margin)
    print(json.dumps(res, indent=1))
    if out_json:
        json.dump(res, open(out_json, 'w'), indent=1)


def main():
    a = [x for x in sys.argv[1:] if not x.startswith('--')]
    out_json = None
    margin = 8
    if '--json' in sys.argv:
        out_json = sys.argv[sys.argv.index('--json') + 1]
        a = [x for x in a if x != out_json]
    if '--margin' in sys.argv:
        m = sys.argv[sys.argv.index('--margin') + 1]
        margin = int(m)
        a = [x for x in a if x != m]
    if not a:
        raise SystemExit(__doc__)
    if a[0] == 'measure' and '--examples' in sys.argv:
        # --examples <examples.json>: [[kind, x, z], ...] boxes of 56 blocks around each example
        ex = json.load(open(sys.argv[sys.argv.index('--examples') + 1]))
        a = [v for v in a if not v.endswith('.json') or v.endswith('roadplan.json')]
        measure(a[1], load(a[2]), [], margin, out_json, boxes=[(x, z, 52) for _k, x, z in ex])
    elif a[0] == 'measure':
        measure(a[1], load(a[2]), a[3:], margin, out_json)
    elif a[0] == 'pick':
        pick(load(a[1]), a[2:])
    else:
        raise SystemExit(__doc__)


if __name__ == '__main__':
    main()
