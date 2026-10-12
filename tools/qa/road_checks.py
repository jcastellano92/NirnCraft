"""Offline road checks on a SAVED world (no Minecraft): continuity gaps, steps over half a block, litter counts.

usage: py -3 -I tools/qa/road_checks.py <world_dir> <place_id_a> <place_id_b> [more place ids...]
       (roads that touch any of the named places are walked: planned polyline vs the blocks that were written)

* gap:   a run of more than 2 consecutive polyline points whose top block is not road material (dry land, loaded
         chunks, not inside a settlement's core, not water/bridge).
* step:  adjacent polyline points whose walking surface differs by more than 0.5 block (slab = half block).
* litter: pumpkins, jack-o-lanterns, hay bales, moss carpet/blocks, loose fences/signs, stray logs/stumps in a
         corridor of 14 blocks either side of the road (outside the settlement cores).
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mca import World  # noqa: E402
from town_checks import Roads, Area  # noqa: E402

ROAD_SUB = ('dirt_path', 'cobblestone', 'gravel', 'stone_bricks', 'polished_andesite', 'andesite', 'coarse_dirt',
            'rooted_dirt', 'packed_mud', 'mud_bricks', 'sandstone', 'calcite', 'polished_diorite', 'quartz_bricks',
            'smooth_quartz', 'deepslate_tiles', 'deepslate_bricks', 'tuff', 'smooth_basalt', 'ash_block',
            'volcanic_rock', 'planks', '_slab', 'blackstone_bricks', 'dirt')
LITTER_SUB = ('pumpkin', 'jack_o_lantern', 'hay_block', 'moss_carpet', 'moss_block', 'barrel', 'fence', 'sign',
              'stripped_', '_log', 'cobblestone_wall', 'stone_brick_wall', 'campfire', 'cauldron', 'chest', 'bed',
              'skull', 'banner', 'lantern', 'composter')


def surface_h(name, y):
    """walking surface height of a top block at y"""
    n = name.split(':')[-1]
    if n.endswith('_slab'):
        return y + 0.5
    return y + 1.0


def main():
    world_dir = sys.argv[1]
    ids = sys.argv[2:]
    world = World(world_dir)
    roads = Roads(world_dir)
    wanted = {s['id'] for s in roads.settlements.values() if s['place'] in ids or s['name'] in ids}
    sel = [r for r in roads.roads if r['a'] in wanted or r['b'] in wanted]
    print('roads touching %s: %d' % (ids, len(sel)))
    tot_gap = tot_step = tot_litter = 0
    for r in sel:
        xs, zs = r['xs'], r['zs']
        pad = 20
        x1, x2 = int(xs.min()) - pad, int(xs.max()) + pad
        z1, z2 = int(zs.min()) - pad, int(zs.max()) + pad
        # tile the polyline's bounding box into 160x160 windows that actually contain points
        cache = {}

        def area_for(x, z):
            key = (x // 128, z // 128)
            if key not in cache:
                ax1, az1 = key[0] * 128 - 16, key[1] * 128 - 16
                cache[key] = Area(world, ax1, az1, ax1 + 159, az1 + 159)
            return cache[key]

        hs, road_ok, loaded = [], [], []
        for x, z in zip(xs, zs):
            a = area_for(int(x), int(z))
            if not a.in_box(int(x), int(z)) or not a.loaded[int(z) - a.z1, int(x) - a.x1]:
                hs.append(None)
                road_ok.append(None)
                continue
            y = int(a.top[int(z) - a.z1, int(x) - a.x1])
            nm = a.names[a.A[y - a.y1, int(z) - a.z1, int(x) - a.x1]]
            # fluids on top: bridge / water, not judged
            if any(k in nm for k in ('water', 'lava', 'ice')):
                hs.append(None)
                road_ok.append(None)
                continue
            hs.append(surface_h(nm, y))
            road_ok.append(any(k in nm for k in ROAD_SUB))
        # gaps
        gaps, run, start = [], 0, 0
        for i, ok in enumerate(road_ok):
            if ok is False:
                if run == 0:
                    start = i
                run += 1
            else:
                if run > 2:
                    gaps.append((start, run))
                run = 0
        if run > 2:
            gaps.append((start, run))
        steps = [(i, hs[i + 1] - hs[i]) for i in range(len(hs) - 1)
                 if hs[i] is not None and hs[i + 1] is not None and abs(hs[i + 1] - hs[i]) > 0.5]
        # litter beside the road
        litter = 0
        seen = set()
        for x, z in zip(xs[::3], zs[::3]):
            a = area_for(int(x), int(z))
            for dx in range(-14, 15, 1):
                for dz in range(-14, 15, 1):
                    px, pz = int(x) + dx, int(z) + dz
                    if (px, pz) in seen or not a.in_box(px, pz):
                        continue
                    seen.add((px, pz))
                    if not a.loaded[pz - a.z1, px - a.x1] or math.hypot(dx, dz) < 3:
                        continue
                    ty = int(a.topany[pz - a.z1, px - a.x1])
                    nm = a.names[a.A[ty - a.y1, pz - a.z1, px - a.x1]].split(':')[-1]
                    if any(k in nm for k in ('pumpkin', 'jack_o', 'hay_block', 'moss_carpet', 'moss_block')):
                        litter += 1
        print('road %-5s kind %d pts %-4d unloaded %-4d gaps %s steps>0.5: %d litter: %d' % (
            r['id'], r['kind'], len(xs), sum(1 for h in hs if h is None), gaps[:6], len(steps), litter))
        tot_gap += sum(g[1] for g in gaps)
        tot_step += len(steps)
        tot_litter += litter
    print('TOTAL gap_points=%d steps=%d litter=%d' % (tot_gap, tot_step, tot_litter))


if __name__ == '__main__':
    main()
