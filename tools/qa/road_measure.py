"""Acceptance test for roads: measure the roads of a SAVED world (blocks, not the plan): the numbers a player feels.

usage: py -3 -I tools/qa/road_measure.py <world_dir> <placeA>:<placeB> [<placeA>:<placeB> ...] [--json out.json]
                                         [--box x1 z1 x2 z2]
       (every road of the pair, or ':placeA' / 'placeA' = every road touching that place)
       --box: judge only road points inside that block box (the rest is not counted at all) and only the doors of
       settlements centred in it; used by tools/qa/quick_corridor.ps1 after a dev-server regen of one tile.

Per road (planned polyline points, 2 blocks apart, outside settlement zones, in loaded chunks):
  gaps      points whose walking surface is not road material (and not water): the road is missing there
  runs      distinct runs of gaps (a run of 1 is a hole, longer is a road that was dropped)
  drops     adjacent points whose walking surface differs by more than 1 block (a cliff in the road)
  tree      points with leaves/logs in the 4 blocks of headroom, or a trunk in the surface (tree-blocked)
  obstacle  points with a solid non-road block 1-2 blocks over the surface (shrine, statue, barrel, well...)
  unbridged points whose top block is water (road dives into a river/lake)
  unloaded  points in chunks that are not saved (not judged)
  Reproduce: (1) boot a headless server on a fresh world with JAVA_TOOL_OPTIONS=-Dskycraft.autorun=<cmds separated by |,
  spaces as +> -Dskycraft.autorun.wait=<ticks>, cmds = 'forceload add x1 z1 x2 z2' (10x10-chunk tiles along the
  road polylines), then 'skycraft-roads stats' x N to idle while the planner/paver work, then 'stop'; (2) run this
  script on mod/run/world. Needs the planned roads (world/data/skycraft_roads*.dat) and saved chunks. Corridors used:
  whiterun:riverwood riverwood:helgen whiterun:rorikstead dragon_bridge:karthwasten.
  doors     doors within 70 blocks of the road's settlements; 'nopath' = no road/path material within 3 blocks;
            'unconnected' = none connected (8-neighbour, steps <= 2) to the planned road points
"""
import json
import os
import sys
from collections import deque

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mca import World  # noqa: E402
from town_checks import Roads  # noqa: E402

ROAD_SUB = ('dirt_path', 'cobblestone', 'gravel', 'stone_bricks', 'polished_andesite', 'andesite', 'coarse_dirt',
            'rooted_dirt', 'packed_mud', 'mud_bricks', 'sandstone', 'calcite', 'polished_diorite', 'quartz_bricks',
            'smooth_quartz', 'deepslate_tiles', 'deepslate_bricks', 'tuff', 'smooth_basalt', 'ash_block',
            'volcanic_rock', 'planks', '_slab', 'blackstone_bricks', 'terracotta', 'basalt', 'path', 'dirt', 'stone',
            'bricks', 'deepslate', '_stairs')
NATURAL_NOT_ROAD = ('grass_block', 'podzol', 'mycelium', 'sand', 'red_sand', 'snow', 'moss_block', 'water', 'clay',
                    'mud', 'ice', 'lava')
SOFT_SUB = ('grass', 'fern', 'flower', 'bush', 'sapling', 'mushroom', 'vine', 'lichen', 'kelp', 'seagrass', 'roots',
            'rose', 'tulip', 'dandelion', 'poppy', 'orchid', 'allium', 'azure', 'cornflower', 'lily', 'peony',
            'lilac', 'sunflower', 'azalea', 'dead_bush', 'sweet_berry', 'snow', 'carpet', 'sugar_cane', 'reeds',
            'cobweb', 'torch', 'pointed_dripstone', 'moss_carpet', 'spore', 'hanging', 'big_dripleaf', 'small_dripleaf')
DOOR_SUB = ('_door',)


def is_air(n):
    return n in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air')


def short(n):
    return n.split(':')[-1]


def is_leaf(n):
    return n.endswith('leaves')


def is_log(n):
    s = short(n)
    return s.endswith('_log') or s.endswith('_wood') or s in ('mushroom_stem',)


def is_soft(n):
    s = short(n)
    if is_air(n):
        return True
    if s.endswith('_door') or s.endswith('fence') or s.endswith('wall') or s.endswith('_sign') or 'lantern' in s:
        return False
    return any(k in s for k in SOFT_SUB) and 'block' not in s


def is_road(n):
    s = short(n)
    if any(k == s or (k in s and k not in ('stone', 'dirt', 'path', 'sand')) for k in ROAD_SUB):
        if s in ('grass_block', 'podzol', 'moss_block', 'sand', 'red_sand', 'mud', 'clay', 'snow_block'):
            return False
        return True
    return s in ('stone', 'dirt', 'dirt_path')


def is_water(n):
    s = short(n)
    return s in ('water', 'bubble_column', 'kelp', 'kelp_plant', 'seagrass', 'tall_seagrass')


class Col:
    """Column cache over a World."""

    def __init__(self, world):
        self.w = world
        self.cache = {}

    def chunk(self, cx, cz):
        k = (cx, cz)
        if k not in self.cache:
            self.cache[k] = self.w.chunk_blocks(cx, cz)
        return self.cache[k]

    def column(self, x, z):
        c = self.chunk(x >> 4, z >> 4)
        if c is None:
            return None
        y0, arr = c
        return y0, arr[:, z & 15, x & 15]

    def name(self, i):
        return self.w.names[i]


def analyse_point(cols, x, z):
    """-> dict(loaded, surface_y, surface_name, h, top_name, tree, obstacle, water) for one column."""
    cc = cols.column(x, z)
    if cc is None:
        return None
    y0, col = cc
    names = cols.w.names
    n = len(col)
    # topmost non-air block
    y = n - 1
    while y >= 0 and is_air(names[col[y]]):
        y -= 1
    if y < 0:
        return None
    top_name = names[col[y]]
    # walking surface: skip air, soft plants, leaves and logs from the top
    yy = y
    leaves_above = 0
    log_hit = False
    while yy >= 0:
        nm = names[col[yy]]
        if is_air(nm) or is_soft(nm):
            yy -= 1
        elif is_leaf(nm):
            leaves_above += 1
            yy -= 1
        elif is_log(nm):
            log_hit = True
            yy -= 1
        else:
            break
    if yy < 0:
        return None
    sname = names[col[yy]]
    sy = yy + y0
    water = is_water(sname)
    # headroom: leaves/logs within 4 blocks above the surface
    tree = False
    for k in range(1, 5):
        j = yy + k
        if j >= n:
            break
        nm = names[col[j]]
        if is_leaf(nm) or is_log(nm):
            tree = True
    obstacle = False
    for k in (1, 2):
        j = yy + k
        if j >= n:
            break
        nm = names[col[j]]
        if is_air(nm) or is_soft(nm) or is_leaf(nm) or is_log(nm) or is_water(nm):
            continue
        s = short(nm)
        if s.endswith('fence') or s.endswith('_wall') or 'lantern' in s or s.endswith('_sign') or s.endswith('_slab') \
                or s.endswith('_stairs'):
            # rails and posts beside the centre are fine; at the centre of the road they are an obstacle only for
            # bridges' own rails, which are never on the centre line: count them
            pass
        obstacle = True
    # height of the surface for step checks (slab on top = half block)
    h = float(sy) + 1.0
    if short(sname).endswith('_slab'):
        h = sy + 0.5
    # a thin layer above the surface block (carpet, snow layer) is ignored
    return dict(sy=sy, name=sname, h=h, tree=tree or log_hit, obstacle=obstacle, water=water, top=top_name,
                road=is_road(sname), above=names[col[yy + 1]] if yy + 1 < n else 'minecraft:air')


def load_places(path):
    try:
        data = json.load(open(path, encoding='utf8'))
        return {p['id']: p for p in data}
    except Exception:
        return {}


def zone_fn(roads, places):
    zs = []
    for s in roads.settlements.values():
        box = s['box']
        named = bool(s.get('place'))
        r = 40
        if named and s['place'] in places:
            r = max(40, int(places[s['place']].get('radius', 40) * 0.8))
        zs.append((s['x'], s['z'], r, box if not named else None))

    def inzone(x, z):
        for cx, cz, r, box in zs:
            if (x - cx) ** 2 + (z - cz) ** 2 <= r * r:
                return True
            if box and len(box) >= 4 and box[0] - 6 <= x <= box[2] + 6 and box[1] - 6 <= z <= box[3] + 6:
                return True
        return False
    return inzone


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out_json = None
    if '--json' in sys.argv:
        out_json = sys.argv[sys.argv.index('--json') + 1]
        args = [a for a in args if a != out_json]
    box = None
    if '--box' in sys.argv:
        i = sys.argv.index('--box')
        b = [int(v) for v in sys.argv[i + 1:i + 5]]
        for v in sys.argv[i + 1:i + 5]:
            args.remove(v)
        box = (min(b[0], b[2]), min(b[1], b[3]), max(b[0], b[2]), max(b[1], b[3]))
    world_dir = args[0]
    pairs = args[1:]
    world = World(world_dir)
    roads = Roads(world_dir)
    here = os.path.dirname(os.path.abspath(__file__))
    places = load_places(os.path.join(here, '..', '..', 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel',
                                      'places', 'skyrim_settlements.json'))
    inzone = zone_fn(roads, places)
    by_place = {}
    for s in roads.settlements.values():
        if s['place']:
            by_place.setdefault(s['place'], []).append(s['id'])
    cols = Col(world)
    total = dict(roads=0, points=0, gaps=0, runs=0, drops=0, big_drops=0, tree=0, obstacle=0, unbridged=0,
                 crossings=0, unloaded=0, doors=0, loose=0)
    report = []
    chosen = {}
    for spec in pairs:
        a, _, b = spec.partition(':')
        ia = set(by_place.get(a, [])) if a else None
        ib = set(by_place.get(b, [])) if b else None
        for r in roads.roads:
            ends = {r['a'], r['b']}
            ok = False
            if ia is not None and ib is not None and a and b:
                ok = bool(ends & ia) and bool(ends & ib)
            elif a:
                ok = bool(ends & ia)
            elif b:
                ok = bool(ends & ib)
            if ok:
                chosen[r['id']] = r
    names_of = {s['id']: (s['place'] or s['name'] or str(s['id'])) for s in roads.settlements.values()}
    water_runs_total = 0
    for rid, r in sorted(chosen.items()):
        xs, zs = r['xs'], r['zs']
        n = len(xs)
        info = [None] * n
        for i in range(n):
            x, z = int(xs[i]), int(zs[i])
            if box and not (box[0] <= x <= box[2] and box[1] <= z <= box[3]):
                info[i] = 'zone'
                continue
            if inzone(x, z):
                info[i] = 'zone'
                continue
            p = analyse_point(cols, x, z)
            info[i] = p if p is not None else 'unloaded'
        row = dict(id=rid, a=names_of.get(r['a']), b=names_of.get(r['b']), kind=r['kind'], points=n, gaps=0, runs=0,
                   drops=0, big_drops=0, tree=0, obstacle=0, unbridged=0, crossings=0, unloaded=0, judged=0)
        in_run = False
        in_water = False
        prev = None
        for i in range(n):
            p = info[i]
            if p == 'zone':
                in_run = False
                in_water = False
                prev = None
                continue
            if p == 'unloaded':
                row['unloaded'] += 1
                in_run = False
                in_water = False
                prev = None
                continue
            row['judged'] += 1
            gap = (not p['road']) and not p['water']
            if p['water']:
                row['unbridged'] += 1
                if not in_water:
                    row['crossings'] += 1
                in_water = True
            else:
                in_water = False
            if gap:
                row['gaps'] += 1
                if not in_run:
                    row['runs'] += 1
                in_run = True
            else:
                in_run = False
            if p['tree']:
                row['tree'] += 1
            if p['obstacle']:
                row['obstacle'] += 1
            if prev is not None and not p['water'] and not prev['water']:
                d = abs(p['h'] - prev['h'])
                if d > 1.0:
                    row['drops'] += 1
                if d > 3.0:
                    row['big_drops'] += 1
            prev = p
        report.append(row)
        for k in ('gaps', 'runs', 'drops', 'big_drops', 'tree', 'obstacle', 'unbridged', 'crossings', 'unloaded'):
            total[k] += row[k]
        total['points'] += row['judged']
        total['roads'] += 1

    # doors near the corridors: connected to a road/path cell reachable from the planned road points?
    seeds = []
    for r in chosen.values():
        for x, z in zip(r['xs'], r['zs']):
            seeds.append((int(x), int(z)))
    sett_ids = set()
    for r in chosen.values():
        sett_ids.add(r['a'])
        sett_ids.add(r['b'])
    door_report = []
    for sid in sorted(sett_ids):
        s = roads.settlements.get(sid)
        if not s:
            continue
        if box and not (box[0] <= s['x'] <= box[2] and box[1] <= s['z'] <= box[3]):
            continue
        rad = 70
        x1, z1, x2, z2 = s['x'] - rad - 40, s['z'] - rad - 40, s['x'] + rad + 40, s['z'] + rad + 40
        if s['box'] and len(s['box']) >= 4:
            x1, z1 = min(x1, s['box'][0] - 12), min(z1, s['box'][1] - 12)
            x2, z2 = max(x2, s['box'][2] + 12), max(z2, s['box'][3] + 12)
        x1, z1, x2, z2 = max(x1, s['x'] - 220), max(z1, s['z'] - 220), min(x2, s['x'] + 220), min(z2, s['z'] + 220)
        # surface grid
        W, H = x2 - x1 + 1, z2 - z1 + 1
        road = np.zeros((H, W), bool)
        surf = np.full((H, W), -999, np.int32)
        doors = []
        for cz in range(z1 >> 4, (z2 >> 4) + 1):
            for cx in range(x1 >> 4, (x2 >> 4) + 1):
                c = cols.chunk(cx, cz)
                if c is None:
                    continue
                y0, arr = c
                isdoor = np.array([short(nm).endswith('_door') for nm in world.names], bool)
                # world.names can grow while reading: rebuild flags on demand
                isroad = np.array([is_road(nm) for nm in world.names], bool)
                for lz in range(16):
                    for lx in range(16):
                        x, z = cx * 16 + lx, cz * 16 + lz
                        if x < x1 or x > x2 or z < z1 or z > z2:
                            continue
                        col = arr[:, lz, lx]
                        dmask = isdoor[col]
                        if dmask.any():
                            ys = np.nonzero(dmask)[0]
                            for yv in ys:
                                if yv == 0 or not dmask[yv - 1]:
                                    doors.append((x, int(yv) + y0, z))
                        p = analyse_point(cols, x, z)
                        if p is not None:
                            road[z - z1, x - x1] = p['road'] and not p['water']
                            surf[z - z1, x - x1] = p['sy']
        # flood from seeds that stand on road material
        flood = np.zeros_like(road)
        dq = deque()
        for x, z in seeds:
            if x1 <= x <= x2 and z1 <= z <= z2:
                for dx in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        xx, zz = x + dx - x1, z + dz - z1
                        if 0 <= xx < W and 0 <= zz < H and road[zz, xx] and not flood[zz, xx]:
                            flood[zz, xx] = True
                            dq.append((xx, zz))
        while dq:
            xx, zz = dq.popleft()
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nx, nz = xx + dx, zz + dz
                    if 0 <= nx < W and 0 <= nz < H and road[nz, nx] and not flood[nz, nx]:
                        # keep the walkable chain: a step of 2 or less
                        if abs(int(surf[nz, nx]) - int(surf[zz, xx])) <= 2:
                            flood[nz, nx] = True
                            dq.append((nx, nz))
        nd = nl = nn = 0
        seen = set()
        for x, y, z in doors:
            # one count per building door cluster (doors are 2 tall; double doors 2 wide)
            key = (x // 2, z // 2, y // 4)
            if key in seen:
                continue
            seen.add(key)
            if not (x1 + 4 <= x <= x2 - 4 and z1 + 4 <= z <= z2 - 4):
                continue
            nd += 1
            okn = False
            for dx in range(-3, 4):
                for dz in range(-3, 4):
                    xx, zz = x + dx - x1, z + dz - z1
                    if 0 <= xx < W and 0 <= zz < H and road[zz, xx] and abs(int(surf[zz, xx]) - (y - 1)) <= 3:
                        okn = True
            if not okn:
                nn += 1
            ok = False
            for dx in range(-3, 4):
                for dz in range(-3, 4):
                    xx, zz = x + dx - x1, z + dz - z1
                    if 0 <= xx < W and 0 <= zz < H and flood[zz, xx] and abs(int(surf[zz, xx]) - (y - 1)) <= 3:
                        ok = True
                        break
                if ok:
                    break
            if not ok:
                nl += 1
        door_report.append(dict(place=names_of.get(sid), doors=nd, loose=nl, nopath=nn))
        total['doors'] += nd
        total['loose'] += nl
        total['nopath'] = total.get('nopath', 0) + nn

    print('roads: %d  points judged: %d  (unloaded %d)' % (total['roads'], total['points'], total['unloaded']))
    print('GAPS %d (runs %d) | DROPS>1 %d (>3: %d) | TREE-blocked %d | OBSTACLE %d | UNBRIDGED pts %d (crossings %d)'
          % (total['gaps'], total['runs'], total['drops'], total['big_drops'], total['tree'], total['obstacle'],
             total['unbridged'], total['crossings']))
    print('DOORS %d | no path material within 3: %d | not connected to the road network: %d' % (total['doors'], total.get('nopath', 0), total['loose']))
    for row in report:
        print('  road %-5d %-14s <-> %-14s k%d pts %-4d gaps %-3d runs %-2d drops %-2d big %-2d tree %-3d obst %-2d '
              'wet %-3d x%-2d unl %d' % (row['id'], row['a'], row['b'], row['kind'], row['judged'], row['gaps'],
                                         row['runs'], row['drops'], row['big_drops'], row['tree'], row['obstacle'],
                                         row['unbridged'], row['crossings'], row['unloaded']))
    for d in door_report:
        print('  doors %-14s %d nopath %d unconnected %d' % (d['place'], d['doors'], d['nopath'], d['loose']))
    if out_json:
        with open(out_json, 'w') as f:
            json.dump(dict(total=total, roads=report, doors=door_report), f, indent=1)


if __name__ == '__main__':
    main()
