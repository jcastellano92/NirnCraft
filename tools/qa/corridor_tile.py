"""Picks measurement tiles on a corridor from the roads a world has actually planned (world/data/skycraft_roads*.dat).

usage: py -3 -I tools/qa/corridor_tile.py <world_dir> <placeA>:<placeB> [--index N] [--tile 160]
  prints 'x1 z1 x2 z2 points' for the N-th best tile (default 0): tiles on the 160-block grid used by p2_corridors.py,
  ranked by the number of road points outside settlement zones (the points road_measure.py judges). Exit 3 when the
  world has no planned road for the pair yet (the planner is still working: wait and retry).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from road_measure import load_places, zone_fn  # noqa: E402
from town_checks import Roads  # noqa: E402


def main():
    a = sys.argv[1:]
    index, tile = 0, 160
    if '--index' in a:
        i = a.index('--index')
        index = int(a[i + 1])
        del a[i:i + 2]
    if '--tile' in a:
        i = a.index('--tile')
        tile = int(a[i + 1])
        del a[i:i + 2]
    world_dir, spec = a[0], a[1]
    roads = Roads(world_dir)
    here = os.path.dirname(os.path.abspath(__file__))
    places = load_places(os.path.join(here, '..', '..', 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel',
                                      'places', 'skyrim_settlements.json'))
    inzone = zone_fn(roads, places)
    pa, _, pb = spec.partition(':')
    by_place = {}
    for s in roads.settlements.values():
        if s['place']:
            by_place.setdefault(s['place'], set()).add(s['id'])
    ia, ib = by_place.get(pa, set()), by_place.get(pb, set())
    score = {}
    n = 0
    for r in roads.roads:
        ends = {r['a'], r['b']}
        if not (ends & ia and (not pb or ends & ib)):
            continue
        n += 1
        for x, z in zip(r['xs'], r['zs']):
            x, z = int(x), int(z)
            if inzone(x, z):
                continue
            k = (x // tile, z // tile)
            score[k] = score.get(k, 0) + 1
    if not score:
        print('no planned road for %s yet (%d matching roads)' % (spec, n))
        sys.exit(3)
    ranked = sorted(score.items(), key=lambda kv: (-kv[1], kv[0]))
    (tx, tz), pts = ranked[min(index, len(ranked) - 1)]
    print('%d %d %d %d %d' % (tx * tile, tz * tile, tx * tile + tile - 1, tz * tile + tile - 1, pts))


if __name__ == '__main__':
    main()
