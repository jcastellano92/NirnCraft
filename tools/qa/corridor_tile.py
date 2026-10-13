"""Picks what to regenerate and measure for corridors from the roads a world has actually planned
(world/data/skycraft_roads*.dat).

usage: py -3 -I tools/qa/corridor_tile.py <world_dir> <placeA>:<placeB> [--index N] [--tile 160]
  prints 'x1 z1 x2 z2 points' for the N-th best tile (default 0): tiles on the 160-block grid used by p2_corridors.py,
  ranked by the number of road points outside settlement zones (the points road_measure.py judges).
       py -3 -I tools/qa/corridor_tile.py <world_dir> <a>:<b> [<a>:<b> ...] --region [R]
  prints the chunk runs 'cz:cx1-cx2,cx1-cx2;cz:...' of every chunk within R chunks (default 2) of a judged road point
  of ALL given corridors (for '/skycraft-dev regenchunks'); the point count goes to stderr.
Exit 3 when a corridor has no planned road in the world yet (the planner is still working: wait and retry); the message\nends with 'at <x> <z>' (the corridor's far end) so the caller can move the world spawn there: the planner takes the\nlinks nearest to the spawn first.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from road_measure import load_places, zone_fn  # noqa: E402
from town_checks import Roads  # noqa: E402


def judged_points(roads, inzone, by_place, spec):
    pa, _, pb = spec.partition(':')
    ia, ib = by_place.get(pa, set()), by_place.get(pb, set())
    pts, n = [], 0
    for r in roads.roads:
        ends = {r['a'], r['b']}
        if not (ends & ia and (not pb or ends & ib)):
            continue
        n += 1
        for x, z in zip(r['xs'], r['zs']):
            x, z = int(x), int(z)
            if not inzone(x, z):
                pts.append((x, z))
    return pts, n


def main():
    a = sys.argv[1:]
    index, tile, region = 0, 160, None
    if '--index' in a:
        i = a.index('--index')
        index = int(a[i + 1])
        del a[i:i + 2]
    if '--tile' in a:
        i = a.index('--tile')
        tile = int(a[i + 1])
        del a[i:i + 2]
    if '--region' in a:
        i = a.index('--region')
        if i + 1 < len(a) and a[i + 1].isdigit():
            region = int(a[i + 1])
            del a[i:i + 2]
        else:
            region = 2
            del a[i:i + 1]
    world_dir, specs = a[0], a[1:]
    roads = Roads(world_dir)
    here = os.path.dirname(os.path.abspath(__file__))
    places = load_places(os.path.join(here, '..', '..', 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel',
                                      'places', 'skyrim_settlements.json'))
    inzone = zone_fn(roads, places)
    by_place = {}
    for s in roads.settlements.values():
        if s['place']:
            by_place.setdefault(s['place'], set()).add(s['id'])
    allpts = []
    for spec in specs:
        pts, n = judged_points(roads, inzone, by_place, spec)
        if not pts:
            pl = places.get(spec.partition(':')[2] or spec.partition(':')[0]) or places.get(spec.partition(':')[0]) or {}
            print('no planned road for %s yet (%d matching roads) at %d %d' % (spec, n, pl.get('x', 0), pl.get('z', 0)))
            sys.exit(3)
        allpts += pts
    if region is not None:
        chunks = set()
        for x, z in allpts:
            for dx in range(-region, region + 1):
                for dz in range(-region, region + 1):
                    chunks.add(((x >> 4) + dx, (z >> 4) + dz))
        rows = {}
        for cx, cz in chunks:
            rows.setdefault(cz, []).append(cx)
        out = []
        for cz in sorted(rows):
            xs = sorted(rows[cz])
            runs, s0, prev = [], xs[0], xs[0]
            for cx in xs[1:] + [None]:
                if cx is not None and cx == prev + 1:
                    prev = cx
                    continue
                runs.append('%d-%d' % (s0, prev) if s0 != prev else '%d' % s0)
                if cx is not None:
                    s0 = prev = cx
            out.append('%d:%s' % (cz, ','.join(runs)))
        print(';'.join(out))
        print('%d chunks, %d judged road points' % (len(chunks), len(allpts)), file=sys.stderr)
        return
    score = {}
    for x, z in allpts:
        k = (x // tile, z // tile)
        score[k] = score.get(k, 0) + 1
    ranked = sorted(score.items(), key=lambda kv: (-kv[1], kv[0]))
    (tx, tz), pts = ranked[min(index, len(ranked) - 1)]
    print('%d %d %d %d %d' % (tx * tile, tz * tile, tx * tile + tile - 1, tz * tile + tile - 1, pts))


if __name__ == '__main__':
    main()
