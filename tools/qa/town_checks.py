"""Offline town audit data + automated checks, working on a SAVED world folder (no Minecraft needed).

Reads settlement plan pieces out of chunk structure starts (kind, footprint, wealth, use, yard variant), the road
network out of world/data/skycraft_roads*.dat, and real blocks via mca.World.  Used by render_town.py too.

usage: py -3 -I tools/qa/town_checks.py <world_dir> [samples.json] [--json out.json]
"""
import glob
import gzip
import json
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mca import World, _nbt  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
JAVA = os.path.join(ROOT, 'mod', 'src', 'main', 'java', 'com', 'skycraft', 'tamriel', 'settlement')


def _enum_names(path, stop=';'):
    src = open(path, encoding='utf8').read()
    i = src.index('enum ')
    body = src[src.index('{', i) + 1:]
    body = body[:body.index(stop)]
    body = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
    body = re.sub(r'//[^\n]*', '', body)
    return re.findall(r'(?m)^\s*([A-Z][A-Z_0-9]*)\s*(?:\(|,|$)', body)


ARCH = _enum_names(os.path.join(JAVA, 'plan', 'Archetype.java'))
USE = _enum_names(os.path.join(JAVA, 'grammar', 'Use.java'))
WEALTH = ['poor', 'common', 'wealthy', 'noble']
_out = open(os.path.join(JAVA, 'grammar', 'Outdoors.java'), encoding='utf8').read()
_yard = _out[_out.index('public static final int CART'):_out.index('YARD_KINDS')]
YARD = {int(v): k for k, v in re.findall(r'([A-Z_]+)\s*=\s*(\d+)', _yard)}
# Java's `A = 0, B = 1` chain has implicit values only where written; the file lists them all explicitly except via
# comma-chains, so rebuild by order when numbers are missing
if len(YARD) < 20:
    names = re.findall(r'([A-Z_]+)\s*=\s*\d+', _yard)
    YARD = {i: n for i, n in enumerate(names)}
DIRS = ['SOUTH', 'WEST', 'NORTH', 'EAST']  # Direction.get2DDataValue order
BUILDING_KINDS = {'HOUSE', 'MANOR', 'LONGHOUSE', 'INN', 'SMITHY', 'STORE', 'ALCHEMIST', 'TEMPLE', 'SHRINE', 'PALACE',
                  'BARRACKS', 'STABLES', 'FARMHOUSE', 'MILL', 'HALL_OF_DEAD', 'TOWER', 'TENT', 'GUILD_HALL', 'JAIL',
                  'BUILDING', 'RESERVED'}
LINE_KINDS = {'STREET', 'WALL', 'BRIDGE', 'CANAL'}
ROAD_KIND = ['highway', 'road', 'lane', 'trail']
ROAD_WIDTH = [5, 4, 3, 2]
F_MAJOR, F_PALISADE, F_RUINED, F_WATER = 64, 128, 256, 8


class Piece:
    __slots__ = ('kind', 'x', 'z', 'x2', 'z2', 'w', 'd', 'h', 'floor', 'flags', 'variant', 'margin', 'facing', 'use',
                 'wealth', 'name', 'settlement', 'index')

    def is_line(self):
        return self.kind in LINE_KINDS

    @property
    def building(self):
        return self.kind in BUILDING_KINDS

    def half(self):
        """(halfX, halfZ) of the footprint without margin (lines: extent plus half width)."""
        if self.is_line():
            return abs(self.x2 - self.x) / 2 + self.w / 2, abs(self.z2 - self.z) / 2 + self.w / 2
        zaxis = self.facing in ('NORTH', 'SOUTH')
        return (self.w if zaxis else self.d) / 2, (self.d if zaxis else self.w) / 2

    def centre(self):
        return ((self.x + self.x2) / 2, (self.z + self.z2) / 2) if self.is_line() else (self.x, self.z)

    def label(self):
        if self.kind == 'YARD':
            return YARD.get(self.variant, 'YARD%d' % self.variant)
        if self.kind == 'BUILDING' and 0 <= self.use < len(USE):
            return USE[self.use]
        return self.kind


def _piece(ch):
    p = Piece()
    g = list(ch.get('G', []))
    if len(g) < 12:
        return None
    k = int(ch['K'])
    p.kind = ARCH[k] if k < len(ARCH) else 'K%d' % k
    p.x, p.z, p.x2, p.z2, p.w, p.d, p.h, p.floor = g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7]
    p.flags, p.variant, p.margin = g[9], g[10], g[11]
    p.facing = DIRS[int(ch.get('F', 0)) % 4]
    gx = list(ch.get('Gx', []))
    p.use, p.wealth = (gx[0], gx[1]) if len(gx) >= 4 else (-1, 1)
    p.name = ch.get('N', '')
    p.settlement = ch.get('S', '')
    p.index = int(ch.get('I', 0))
    return p


def load_pieces(world, x1, z1, x2, z2):
    """Settlement plan pieces whose structure start chunk lies inside the box (+ a margin)."""
    seen = {}
    pad = 96
    for cz in range((z1 - pad) >> 4, ((z2 + pad) >> 4) + 1):
        for cx in range((x1 - pad) >> 4, ((x2 + pad) >> 4) + 1):
            c = world.chunk_nbt(cx, cz)
            if not c:
                continue
            starts = (c.get('structures') or {}).get('starts') or {}
            for key, s in starts.items():
                if 'settlement' not in key:
                    continue
                for ch in s.get('Children', []) or []:
                    if 'K' not in ch:
                        continue
                    p = _piece(ch)
                    if p and (p.settlement, p.index) not in seen:
                        seen[(p.settlement, p.index)] = p
    return list(seen.values())


class Roads:
    def __init__(self, world_dir):
        self.roads = []       # dict(id,a,b,xs,zs,flags,kind,name)
        self.settlements = {}  # id -> dict
        self.features = []
        d = os.path.join(world_dir, 'data')
        for f in glob.glob(os.path.join(d, 'skycraft_roads*.dat')):
            try:
                data = _nbt(gzip.decompress(open(f, 'rb').read())).get('data', {})
            except Exception:
                continue
            for r in data.get('roads', []) or []:
                self.roads.append(dict(id=r['id'], a=r['a'], b=r['b'], xs=np.array(r['x']), zs=np.array(r['z']),
                                       flags=np.frombuffer(bytes(r.get('f', b'')), np.int8)
                                       if len(r.get('f', b'')) else np.zeros(len(r['x']), np.int8),
                                       kind=int(r.get('k', 1)), name=r.get('n', '')))
            for s in data.get('settlements', []) or []:
                p = list(s.get('pos', [0, 0, 0]))
                b = list(s.get('box', [0, 0, 0, 0]))
                self.settlements[s['id']] = dict(id=s['id'], x=p[0], y=p[1], z=p[2], box=b, name=s.get('name', ''),
                                                 structure=s.get('structure', ''), place=s.get('place', ''),
                                                 dest=bool(s.get('dest', 0)))
            for f2 in data.get('features', []) or []:
                self.features.append(dict(type=f2['type'], x=f2['x'], z=f2['z'], road=f2['road'], variant=f2.get('v', 0)))

    def in_box(self, x1, z1, x2, z2):
        out = []
        for r in self.roads:
            m = (r['xs'] >= x1) & (r['xs'] <= x2) & (r['zs'] >= z1) & (r['zs'] <= z2)
            if m.any():
                out.append(r)
        return out


def seg_dist(px, pz, ax, az, bx, bz):
    dx, dz = bx - ax, bz - az
    L = dx * dx + dz * dz
    t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (pz - az) * dz) / L))
    return math.hypot(px - (ax + t * dx), pz - (az + t * dz))


def road_dist(roads, px, pz):
    """(distance, road) to the nearest road polyline vertex-to-vertex segment."""
    best, br = 1e9, None
    for r in roads:
        xs, zs = r['xs'], r['zs']
        m = (np.abs(xs - px) < 60) & (np.abs(zs - pz) < 60)
        if not m.any():
            continue
        idx = np.nonzero(m)[0]
        for i in idx:
            for j in (i - 1, i):
                if 0 <= j < len(xs) - 1:
                    d = seg_dist(px, pz, xs[j], zs[j], xs[j + 1], zs[j + 1])
                    if d < best:
                        best, br = d, r
    return best, br


# ------------------------------------------------------------------ terrain columns
WATER_SUB = ('water', 'kelp', 'seagrass', 'bubble_column')
SOFT_SUB = ('leaves', 'grass', 'fern', 'flower', 'vine', 'snow', 'bush', 'sapling', 'tulip', 'poppy', 'dandelion',
            'orchid', 'allium', 'bluet', 'lichen', 'roots', 'mushroom', 'lily', 'azalea', 'moss_carpet', 'sugar_cane',
            'cobweb', 'dead_bush', 'rose', 'peony', 'lilac', 'cornflower', 'oxeye', 'berry', 'sweet_berry', 'wheat',
            'carrots', 'potatoes', 'beetroots', 'pumpkin_stem', 'melon_stem', 'tall_', 'cave_vines', 'nirnroot')


def block_flags(names):
    air = np.array([n.endswith(':air') or n.endswith('cave_air') or n.endswith('void_air') for n in names])
    water = np.array([any(k in n for k in WATER_SUB) for n in names])
    soft = np.array([any(k in n.split(':')[-1] for k in SOFT_SUB) and 'block' not in n.split(':')[-1] for n in names])
    return air, water, soft


class Area:
    """A block box of the world with derived per-column ground height."""

    def __init__(self, world, x1, z1, x2, z2, y1=30, y2=330):
        self.w = world
        self.x1, self.z1, self.x2, self.z2, self.y1, self.y2 = x1, z1, x2, z2, y1, y2
        self.A = world.box(x1, z1, x2, z2, y1, y2)
        self.names = list(world.names)
        self.air, self.water, self.soft = block_flags(self.names)
        self.loaded = (self.A != 0).any(0)  # any non-air column at all
        solid = ~self.air[self.A] & ~self.soft[self.A] & ~self.water[self.A]
        H = solid.shape[0]
        top = H - 1 - np.argmax(solid[::-1], axis=0)
        self.top = np.where(solid.any(0), top + y1, y1 - 1)       # highest solid (roofs included)
        anyb = ~self.air[self.A]
        topany = H - 1 - np.argmax(anyb[::-1], axis=0)
        self.topany = np.where(anyb.any(0), topany + y1, y1 - 1)
        self.topblock = np.take_along_axis(self.A, (np.clip(self.topany - y1, 0, H - 1))[None], 0)[0]
        wat = self.water[self.A]
        self.has_water_top = np.take_along_axis(wat, (np.clip(self.topany - y1, 0, H - 1))[None], 0)[0]

    def in_box(self, x, z):
        return self.x1 <= x <= self.x2 and self.z1 <= z <= self.z2

    def col(self, x, z):
        return self.A[:, z - self.z1, x - self.x1]

    def block(self, x, y, z):
        if not self.in_box(x, z) or y < self.y1 or y > self.y2:
            return 'minecraft:air'
        return self.names[self.A[y - self.y1, z - self.z1, x - self.x1]]


# ------------------------------------------------------------------ checks

NATURAL_EXACT = {'grass_block', 'dirt', 'coarse_dirt', 'podzol', 'rooted_dirt', 'sand', 'red_sand', 'gravel', 'stone',
                 'snow', 'snow_block', 'mycelium', 'mud', 'clay', 'terracotta', 'netherrack', 'moss_block', 'deepslate',
                 'tuff', 'granite', 'diorite', 'andesite', 'ice', 'packed_ice', 'blue_ice', 'bedrock', 'sandstone',
                 'red_sandstone', 'calcite', 'dripstone_block', 'water', 'lava', 'powder_snow', 'basalt', 'blackstone',
                 'cobbled_deepslate', 'amethyst_block', 'smooth_basalt', 'dirt_path'}
NATURAL_SUB = ('leaves', 'grass', 'fern', 'flower', 'bush', 'sapling', 'mushroom', '_ore', 'terracotta', 'frosted',
               'tundra', 'ash', 'volcanic', 'moss', 'lichen', 'kelp', 'coral', 'sea_pickle', 'seagrass')


def natural_ids(names):
    out = np.zeros(len(names), bool)
    for i, n in enumerate(names):
        s = n.split(':')[-1]
        out[i] = s in NATURAL_EXACT or any(k in s for k in NATURAL_SUB) or s.endswith('_log') and 'skycraft' in n and 'tree' in n
    return out


def floating_column(area, cx, cz):
    """y of the bottom of the top built run of the column when it hangs in the air (2+ empty cells below, nothing
    beside it), else None."""
    c = area.col(cx, cz)
    ys = np.nonzero(~area.air[c] & ~area.water[c])[0]
    if len(ys) == 0:
        return None
    y = int(ys.max())
    while y - 1 >= 0 and not area.air[c[y - 1]]:
        y -= 1
    if y < 2 or not (area.air[c[y - 1]] and area.air[c[y - 2]]):
        return None
    if not (~area.air[c[:y - 2]]).any():
        return None  # nothing below at all (unloaded / void): not a prop problem
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if (dx or dz) and area.in_box(cx + dx, cz + dz):
                if not area.air[area.A[y, cz + dz - area.z1, cx + dx - area.x1]]:
                    return None
    return y + area.y1

def floating_vegetation(world, cx, cz, r=150, y1=40, y2=330):
    """Count log/leaf blocks that float: connected (6-neighbour) components of logs+leaves+wood where no log has a
    non-vegetation solid block directly below it and no leaf sits on one. Components touching the box edge or an
    unloaded column are ignored. Returns dict(components, logs, leaves, samples)."""
    from scipy import ndimage
    x1, z1, x2, z2 = cx - r, cz - r, cx + r, cz + r
    A = world.box(x1, z1, x2, z2, y1, y2)
    names = list(world.names)
    air, water, _soft = block_flags(names)
    base = [n.split(':')[-1] for n in names]
    isleaf = np.array([b.endswith('leaves') for b in base])
    islog = np.array([b.endswith('_log') or b.endswith('_wood') or b.endswith('_stem') for b in base])
    leaf, log = isleaf[A], islog[A]
    veg = leaf | log
    loaded = (A != 0).any(0)
    solid_other = ~air[A] & ~water[A] & ~veg
    sup = np.zeros_like(veg)
    sup[1:] = veg[1:] & solid_other[:-1]
    sup[0] = veg[0]
    lab, n = ndimage.label(veg, structure=np.ones((3, 3, 3), bool))  # 26-connected: diagonal contact reads as attached
    if n == 0:
        return dict(components=0, logs=0, leaves=0, samples=[])
    grounded = np.zeros(n + 1, bool)
    grounded[np.unique(lab[sup])] = True
    edge = np.zeros(veg.shape, bool)
    edge[:, 0, :] = edge[:, -1, :] = edge[:, :, 0] = edge[:, :, -1] = True
    edge[0] = True
    edge |= ndimage.binary_dilation(~loaded, iterations=2)[None]
    grounded[np.unique(lab[edge & veg])] = True
    bad = ~grounded
    bad[0] = False
    ids = np.nonzero(bad)[0]
    logs = leaves = 0
    samples = []
    if len(ids):
        m = bad[lab]
        logs = int((m & log).sum())
        leaves = int((m & leaf).sum())
        objs = ndimage.find_objects(lab)
        for i in ids[:6]:
            sl = objs[i - 1]
            samples.append((x1 + (sl[2].start + sl[2].stop) // 2, y1 + sl[0].start, z1 + (sl[1].start + sl[1].stop) // 2))
    return dict(components=int(len(ids)), logs=logs, leaves=leaves, samples=samples)

PROP_KINDS = {'YARD', 'STALL'}
SOFT_PROPS = {'GARDEN', 'PEN'}


def town_box(place, half):
    return place['x'] - half, place['z'] - half, place['x'] + half, place['z'] + half


def analyse(world, roads, place, half):
    """Run every check for one place sample. Returns a result dict."""
    x1, z1, x2, z2 = town_box(place, half)
    pieces = [p for p in load_pieces(world, x1, z1, x2, z2)
              if x1 - 8 <= p.centre()[0] <= x2 + 8 and z1 - 8 <= p.centre()[1] <= z2 + 8]
    # keep the settlement key that owns the most pieces near the centre
    cnt = {}
    for p in pieces:
        cx, cz = p.centre()
        if math.hypot(cx - place['x'], cz - place['z']) <= place['radius'] + 20:
            cnt[p.settlement] = cnt.get(p.settlement, 0) + 1
    keep = set(cnt) if cnt else {p.settlement for p in pieces}
    res = dict(id=place['id'], culture=place['culture'], type=place['type'], pieces=0, loaded_chunks=0)
    area = Area(world, x1, z1, x2, z2)
    res['loaded_cols'] = int(area.loaded.sum())
    res['loaded_frac'] = round(float(area.loaded.mean()), 3)
    pieces = [p for p in pieces if p.settlement in keep]
    res['pieces'] = len(pieces)
    if not pieces:
        res['note'] = 'no settlement pieces found (not generated, or start chunk outside box)'
        return res, pieces, area
    cx0, cz0 = place['x'], place['z']
    bld = [p for p in pieces if p.building]
    props = [p for p in pieces if p.kind in PROP_KINDS]
    streets = [p for p in pieces if p.kind == 'STREET']
    gates = [p for p in pieces if p.kind == 'GATE']
    soft = [p for p in pieces if p.kind in SOFT_PROPS]
    res.update(buildings=len(bld), props=len(props), yards=sum(p.kind == 'YARD' for p in pieces),
               stalls=sum(p.kind == 'STALL' for p in pieces), gardens=sum(p.kind == 'GARDEN' for p in pieces),
               pens=sum(p.kind == 'PEN' for p in pieces), streets=len(streets), gates=len(gates),
               built_radius=int(max([math.hypot(p.centre()[0] - cx0, p.centre()[1] - cz0) for p in bld] or [0])))
    res['props_per_building'] = round(len(props) / max(1, len(bld)), 2)
    # road geometry near this town
    near_roads = roads.in_box(x1 - 40, z1 - 40, x2 + 40, z2 + 40)
    res['roads_nearby'] = len(near_roads)
    res['road_kinds'] = {ROAD_KIND[min(3, r['kind'])]: sum(1 for q in near_roads if q['kind'] == r['kind'])
                         for r in near_roads}

    # --- 1. every gate has a road end within 24 blocks
    gate_pts = [(p.x, p.z, 'gate') for p in gates]
    if not gate_pts:  # unwalled towns: the road ends are the major signposts
        gate_pts = [(p.x, p.z, 'signpost') for p in props if p.label() == 'SIGNPOST' and p.flags & F_MAJOR]
    bad_gate = []
    gate_info = []
    bridges = [p for p in pieces if p.kind == 'BRIDGE']
    for gx, gz, what in gate_pts:
        d, r = road_dist(near_roads, gx, gz)
        # the plan's road end is the far foot of the gate's bridge/ramp when it has one (Solitude, Windhelm...)
        for b in bridges:
            for (ax, az), (bx, bz) in (((b.x, b.z), (b.x2, b.z2)), ((b.x2, b.z2), (b.x, b.z))):
                if math.hypot(ax - gx, az - gz) <= 12:
                    d = min(d, road_dist(near_roads, bx, bz)[0])
        # also accept the town's own street ending there
        sd = min([min(math.hypot(s.x - gx, s.z - gz), math.hypot(s.x2 - gx, s.z2 - gz)) for s in streets] or [999])
        gate_info.append((gx, gz, round(d, 1), round(sd, 1)))
        if d > 24:
            bad_gate.append((gx, gz, round(d, 1)))
    res['gate_points'] = len(gate_pts)
    res['gates_without_road_24'] = len(bad_gate)
    res['gates_without_street_24'] = sum(1 for g in gate_info if g[3] > 24)
    res['gate_detail'] = gate_info[:12]

    # --- 2. road ends terminate at settlement / junction / destination (roads touching this box)
    stubs = []
    ends = 0
    sets = list(roads.settlements.values())
    for r in near_roads:
        for ei, (ex, ez) in enumerate(((int(r['xs'][0]), int(r['zs'][0])), (int(r['xs'][-1]), int(r['zs'][-1])))):
            if not (x1 - 40 <= ex <= x2 + 40 and z1 - 40 <= ez <= z2 + 40):
                continue
            ends += 1
            ok = False
            for s in sets:
                b = s['box']
                if b[0] - 24 <= ex <= b[2] + 24 and b[1] - 24 <= ez <= b[3] + 24:
                    ok = True
                    break
                if math.hypot(s['x'] - ex, s['z'] - ez) <= 40:
                    ok = True
                    break
            if not ok:
                for q in near_roads:
                    if q is r:
                        continue
                    if road_dist([q], ex, ez)[0] <= 6:
                        ok = True
                        break
            water = False
            if area.in_box(ex, ez) and area.loaded[ez - z1, ex - x1]:
                water = bool(area.has_water_top[ez - z1, ex - x1])
            if not ok or water:
                stubs.append((ex, ez, 'water' if water else 'dead-end', r['id']))
    res['road_ends'] = ends
    res['road_end_stubs'] = len(stubs)
    res['stub_detail'] = stubs[:10]

    # --- 3. props on roads / inside buildings
    on_road, in_bld, on_street = [], [], []
    for p in props + soft:
        px, pz = p.centre()
        hx, hz = p.half()
        inside = False
        for b in bld:
            bx, bz = b.centre()
            bhx, bhz = b.half()
            if abs(px - bx) < bhx and abs(pz - bz) < bhz:
                inside = True
                break
        if inside:
            in_bld.append((p.label(), int(px), int(pz)))
        for s in streets:
            if seg_dist(px, pz, s.x, s.z, s.x2, s.z2) < s.w / 2.0 - 0.5 and p.kind in PROP_KINDS:
                on_street.append((p.label(), int(px), int(pz)))
                break
        if p.kind in PROP_KINDS:
            # signposts and lamps AT a gate / road end are where the road is meant to arrive
            if any(math.hypot(px - gx, pz - gz) < 8 for gx, gz, _ in gate_pts):
                continue
            d, r = road_dist(near_roads, px, pz)
            if r is not None and d < ROAD_WIDTH[min(3, r['kind'])] / 2.0:
                on_road.append((p.label(), int(px), int(pz), ROAD_KIND[min(3, r['kind'])]))
    res['props_in_buildings'] = len(in_bld)
    res['props_on_street'] = len(on_street)
    res['props_on_road'] = len(on_road)
    res['prop_problem_detail'] = (in_bld[:4], on_street[:4], on_road[:4])

    # --- 4. density by distance and by wealth tier
    rings = []
    step = 25
    Rm = max(step, int(math.ceil((res['built_radius'] + 10) / step) * step))
    for r0 in range(0, Rm, step):
        r1 = r0 + step
        area_m2 = math.pi * (r1 * r1 - r0 * r0)
        n = nb = 0
        for p in pieces:
            if p.kind in PROP_KINDS:
                d = math.hypot(p.centre()[0] - cx0, p.centre()[1] - cz0)
                n += r0 <= d < r1
            elif p.building:
                d = math.hypot(p.centre()[0] - cx0, p.centre()[1] - cz0)
                nb += r0 <= d < r1
        rings.append(dict(r0=r0, r1=r1, props=n, buildings=nb, per1000=round(n / area_m2 * 1000, 2)))
    res['rings'] = rings
    # wealth: props within 9 blocks of buildings of each tier (each prop assigned to nearest building)
    wl = {w: [0, 0] for w in range(4)}
    for b in bld:
        wl[min(3, max(0, b.wealth))][1] += 1
    for p in props + soft:
        px, pz = p.centre()
        best, bb = 9.0, None
        for b in bld:
            bx, bz = b.centre()
            bhx, bhz = b.half()
            d = max(abs(px - bx) - bhx, abs(pz - bz) - bhz)
            if d < best:
                best, bb = d, b
        if bb is not None:
            wl[min(3, max(0, bb.wealth))][0] += 1
    res['by_wealth'] = {WEALTH[w]: dict(buildings=wl[w][1], nearby_props=wl[w][0],
                                        per_building=round(wl[w][0] / wl[w][1], 2) if wl[w][1] else None)
                        for w in wl}
    # monotonic fall with distance? compare first third vs last third of populated rings
    pr = [r for r in rings if r['buildings'] > 0]
    if len(pr) >= 3:
        k = max(1, len(pr) // 3)
        inner = sum(r['per1000'] for r in pr[:k]) / k
        outer = sum(r['per1000'] for r in pr[-k:]) / k
        res['density_inner'] = round(inner, 2)
        res['density_outer'] = round(outer, 2)
        res['density_falls_with_distance'] = bool(inner > outer)

    # --- 4b. what stands OUTSIDE the wall: road features (lanterns, signposts, milestones, shrines, roadside dressing)
    # by distance beyond the built radius, per 1000 m2 of the annulus (the plan props above stop at the wall)
    names = {0: 'lantern', 1: 'signpost', 2: 'milestone', 3: 'shrine', 4: 'dressing'}
    br = res['built_radius']
    outer = []
    for r0 in range(0, 200, 50):
        a0, a1 = br + r0, br + r0 + 50
        cnt = {}
        for f in roads.features:
            d = math.hypot(f['x'] - cx0, f['z'] - cz0)
            if a0 <= d < a1:
                k = names.get(f['type'], str(f['type']))
                cnt[k] = cnt.get(k, 0) + 1
        n = sum(cnt.values())
        outer.append(dict(from_wall=r0, to_wall=r0 + 50, features=n, dressing=cnt.get('dressing', 0),
                          per1000=round(n / (math.pi * (a1 * a1 - a0 * a0)) * 1000, 3), kinds=cnt))
    res['outer_features'] = outer
    res['outer_dressing_100'] = sum(o['dressing'] for o in outer[:2])
    res['outer_features_100'] = sum(o['features'] for o in outer[:2])

    # --- 5. prop presence + floating
    missing, floating = [], []
    nat = natural_ids(area.names)
    for p in props:
        px, pz = int(p.centre()[0]), int(p.centre()[1])
        if not area.in_box(px, pz) or not area.loaded[pz - z1, px - x1]:
            continue
        hx, hz = p.half()
        cols = [(px + dx, pz + dz) for dx in range(-int(min(hx, 2)), int(min(hx, 2)) + 1)
                for dz in range(-int(min(hz, 2)), int(min(hz, 2)) + 1)]
        cols = [c for c in cols if area.in_box(*c)]
        placed = False
        ring = []
        r0 = int(max(hx, hz)) + 2
        for k in range(-r0, r0 + 1):
            for (qx, qz) in ((px + k, pz - r0), (px + k, pz + r0), (px - r0, pz + k), (px + r0, pz + k)):
                if area.in_box(qx, qz) and area.loaded[qz - z1, qx - x1]:
                    ring.append(int(area.topany[qz - z1, qx - x1]))
        base = sorted(ring)[len(ring) // 2] if ring else None
        for (cx, cz) in cols:
            tb = area.topblock[cz - z1, cx - x1]
            if not nat[tb] or (base is not None and area.topany[cz - z1, cx - x1] > base):
                placed = True
            fl = floating_column(area, cx, cz)
            if fl is not None:
                floating.append((p.label(), cx, cz, fl))
                break
        if not placed and p.label() not in ('TREE', 'FLOWER_BED'):
            missing.append((p.label(), px, pz))
    res['props_missing'] = len(missing)
    res['props_floating'] = len(floating)
    res['prop_missing_detail'] = missing[:6]
    res['floating_detail'] = floating[:6]
    # lone built blocks that are the top of their column with all six neighbours empty (anywhere in the box)
    A = area.A
    nonair = ~area.air[A]
    built = nonair & ~area.soft[A] & ~area.water[A] & ~nat[A]
    pad = np.pad(nonair | area.water[A], 1)
    nb = (pad[:-2, 1:-1, 1:-1] | pad[2:, 1:-1, 1:-1] | pad[1:-1, :-2, 1:-1] | pad[1:-1, 2:, 1:-1]
          | pad[1:-1, 1:-1, :-2] | pad[1:-1, 1:-1, 2:])
    iso = built & ~nb
    yy = np.arange(A.shape[0])[:, None, None] + area.y1
    iso &= (yy == area.topany[None])
    iso[:, 0, :] = iso[:, -1, :] = False
    iso[:, :, 0] = iso[:, :, -1] = False
    iso[0] = iso[-1] = False
    res['isolated_blocks'] = int(iso.sum())
    if iso.any():
        ys, zs, xs = np.nonzero(iso)
        res['isolated_detail'] = [(int(xs[i]) + x1, int(ys[i]) + area.y1, int(zs[i]) + z1, area.names[A[ys[i], zs[i], xs[i]]]) for i in range(min(6, len(xs)))]
    res.update(access_report(area))
    return res, pieces, area


# ------------------------------------------------------------------ accessibility check
_PASS_END = ('_carpet', '_pressure_plate', '_door', '_fence_gate', '_trapdoor', '_sign', '_hanging_sign', '_banner', '_button',
             '_torch', '_candle', '_rail')


def access_report(area):
    """Unreachable doors and blocked stairs, from the blocks alone (block names carry no state).

    Doors: every lower door half with exactly one side open to the sky (within 4 blocks) is an exterior door; its
    doorstep (the cell in front and the one behind it) must have 2 passable cells and a floor. Stairs: every run end
    (a stair cell with exactly one stair neighbour one level up or down, under a roof) needs a free 2-high standing cell
    beside it (same level for a bottom end, one up for a top end). Returns counts and the first few positions."""
    names = area.names
    def passable(n):
        n = n.split(':')[-1]
        return (n.endswith(_PASS_END) or 'crop' in n or n in ('snow', 'torch', 'lever', 'vine', 'candle', 'wheat', 'carrots',
                                                              'potatoes', 'beetroots', 'sweet_berry_bush'))
    pas = np.array([passable(n) for n in names])
    isdoor = np.array([n.endswith('_door') and 'trapdoor' not in n for n in names])
    isstair = np.array([n.endswith('_stairs') for n in names])
    isfurn = np.array([any(k in n.split(':')[-1] for k in ('chest', 'barrel', 'bed', 'cauldron', 'furnace', 'smoker',
                                                           'crafting_table', 'bookshelf', 'lectern', 'flower_pot', 'loom',
                                                           'grindstone', 'anvil', 'brewing', 'cooking', 'enchant'))
                       and 'bedrock' not in n for n in names])
    A = area.A
    H, D, W = A.shape
    y1 = area.y1
    P = pas[A] | area.soft[A] | area.air[A]
    DR = isdoor[A]
    ST = isstair[A]
    FU = isfurn[A]
    solid_top = ~(area.air[A] | area.soft[A] | area.water[A])
    def sky(x, z, y):  # column open to the sky above y (cells y+2 .. top)
        return area.top[z, x] <= y + 1
    doors = bad_doors = 0
    bad_door_pos = []
    ys, zs, xs = np.nonzero(DR)
    for y, z, x in zip(ys, zs, xs):
        if y > 0 and DR[y - 1, z, x]:
            continue  # upper half
        if x < 5 or z < 5 or x >= W - 5 or z >= D - 5:
            continue
        wy = int(y) + y1
        # the wall runs along the axis whose two neighbours are both non-passable; the door's sides are the other axis
        wx_ = (not P[y, z, x - 1]) and (not P[y, z, x + 1])
        wz_ = (not P[y, z - 1, x]) and (not P[y, z + 1, x])
        if wx_ == wz_:
            continue
        sides = ((0, 1), (0, -1)) if wx_ else ((1, 0), (-1, 0))
        cnt = [sum(1 for k in range(1, 5) if sky(x + dx * k, z + dz * k, wy)) for dx, dz in sides]
        if cnt[0] == cnt[1] or max(cnt) == 0:
            continue
        dx, dz = sides[0] if cnt[0] > cnt[1] else sides[1]
        # the opposite side must be roofed (it is, by the single open direction)
        doors += 1
        ok = True
        for k in (1, 2):
            cx, cz = x + dx * k, z + dz * k
            if not (P[y, cz, cx] and P[min(y + 1, H - 1), cz, cx]):
                ok = False
            if y > 0 and not solid_top[y - 1, cz, cx] and k == 1:
                ok = False
        if not ok:
            bad_doors += 1
            if len(bad_door_pos) < 8:
                bad_door_pos.append((int(x) + area.x1, wy, int(z) + area.z1, names[A[y, z, x]].split(':')[-1]))
    runs = bad_ends = 0
    bad_end_pos = []
    ys, zs, xs = np.nonzero(ST)
    for y, z, x in zip(ys, zs, xs):
        if x < 3 or z < 3 or x >= W - 3 or z >= D - 3 or y < 1 or y >= H - 4:
            continue
        wy = int(y) + y1
        roofed = any(not P[y + k, z, x] for k in range(3, 7) if y + k < H)
        if not roofed:
            continue
        nb = []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for dy in (1, -1):
                if ST[y + dy, z + dz, x + dx]:
                    nb.append((dx, dz, dy))
        if len(nb) != 1:
            continue
        runs += 1
        ndx, ndz, ndy = nb[0]
        land_y = y if ndy == 1 else y + 1   # bottom end: stand beside it at its level; top end: one up
        free = False
        furn = False
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (dx, dz) == (ndx, ndz):
                continue
            cx, cz = x + dx, z + dz
            if land_y + 1 < H and P[land_y, cz, cx] and P[land_y + 1, cz, cx] and solid_top[land_y - 1, cz, cx]:
                free = True
                break
            if land_y + 1 < H and (FU[land_y, cz, cx] or FU[land_y + 1, cz, cx]):
                furn = True
        if not free and furn:  # blocked by furniture (roof and exterior stair ends have none beside them)
            bad_ends += 1
            if len(bad_end_pos) < 8:
                bad_end_pos.append((int(x) + area.x1, wy, int(z) + area.z1, 'bottom' if ndy == 1 else 'top'))
    return dict(exterior_doors=doors, doors_unreachable=bad_doors, doors_unreachable_detail=bad_door_pos,
                stair_runs=runs, stairs_blocked=bad_ends, stairs_blocked_detail=bad_end_pos)


# ------------------------------------------------------------------ sealed-building check
_MAPX = {'EAST': lambda lx, lz: -lz, 'SOUTH': lambda lx, lz: -lx, 'WEST': lambda lx, lz: lz}
_MAPZ = {'EAST': lambda lx, lz: lx, 'SOUTH': lambda lx, lz: -lz, 'WEST': lambda lx, lz: -lx}


def local_to_world(p, lx, lz):
    """World column of local building cell (lx, lz) (mirrors SettlementPiece.origin/mapX/mapZ)."""
    mx = _MAPX.get(p.facing, lambda a, b: a)
    mz = _MAPZ.get(p.facing, lambda a, b: b)
    lcx, lcz = (p.w - 1) // 2, (p.d - 1) // 2
    ox, oz = p.x - mx(lcx, lcz), p.z - mz(lcx, lcz)
    return ox + mx(lx, lz), oz + mz(lx, lz)


def _is_air(name):
    return name.split(':')[-1] in ('air', 'cave_air', 'void_air')


def sealed_report(area, pieces):
    """Per building: share of wall cells that are solid at head height of the first storey, by side (front, back,
    left, right). A side below 55% is 'open'; a building with an open side is not sealed (a window leaves one of
    the two rows above the floor non-air, an open cell is air in both).
    Returns (list of dicts, summary dict)."""
    out = []
    for p in pieces:
        if not p.building or p.kind in ('TENT', 'RESERVED') or p.w < 4 or p.d < 4:
            continue
        sides = {'front': [(x, 0) for x in range(p.w)], 'back': [(x, p.d - 1) for x in range(p.w)],
                 'left': [(0, z) for z in range(p.d)], 'right': [(p.w - 1, z) for z in range(p.d)]}
        res = {}
        for name, cells in sides.items():
            solid = tot = 0
            for lx, lz in cells:
                wx, wz = local_to_world(p, lx, lz)
                if not area.in_box(wx, wz) or not area.loaded[wz - area.z1, wx - area.x1]:
                    continue
                tot += 1
                a = area.block(wx, p.floor + 2, wz)
                b = area.block(wx, p.floor + 3, wz)
                if not (_is_air(a) and _is_air(b)):
                    solid += 1
            res[name] = round(solid / tot, 2) if tot else None
        if all(v is None for v in res.values()):
            continue
        open_sides = [k for k, v in res.items() if v is not None and v < 0.55]
        out.append(dict(label=p.label(), kind=p.kind, x=p.x, z=p.z, w=p.w, d=p.d, facing=p.facing, floor=p.floor,
                        sides=res, open=open_sides))
    bad = [o for o in out if o['open']]
    return out, dict(buildings=len(out), not_sealed=len(bad),
                     open_sides={k: sum(1 for o in out if k in o['open']) for k in ('front', 'back', 'left', 'right')})


def verdict(res):
    if res.get('note'):
        return 'NOGEN'
    bad = []
    if res.get('gates_without_road_24'):
        bad.append('gate-no-road')
    if res.get('road_end_stubs'):
        bad.append('stubs')
    if res.get('props_in_buildings') or res.get('props_on_street') or res.get('props_on_road'):
        bad.append('props-overlap')
    if res.get('doors_unreachable') or res.get('stairs_blocked'):
        bad.append('access')
    if res.get('props_floating') or res.get('isolated_blocks', 0) > 3:
        bad.append('floating')
    if res.get('density_falls_with_distance') is False:
        bad.append('density-rises-outward')
    return 'OK' if not bad else ','.join(bad)


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('world')
    ap.add_argument('samples', nargs='?', default=None)
    ap.add_argument('--json', default=None)
    ap.add_argument('--floating', default=None, help='comma list of place ids: count floating logs/leaves within 150')
    a = ap.parse_args()
    samples = json.load(open(a.samples, encoding='utf8')) if a.samples else []
    world = World(a.world)
    roads = Roads(a.world)
    print('roads: %d polylines, %d settlements, %d features' % (len(roads.roads), len(roads.settlements), len(roads.features)))
    allres = []
    hdr = '%-16s %-8s %4s %4s %5s %5s | %5s %5s | %5s %5s %5s | %5s %5s | %s'
    print(hdr % ('town', 'culture', 'bld', 'prop', 'p/b', 'ldfr', 'gate', 'g-rd', 'stub', 'ends', 'inB', 'onSt', 'flt', 'verdict'))
    for s in samples:
        res, _, _ = analyse(world, roads, s, s['half'])
        allres.append(res)
        print(hdr % (res['id'], res['culture'], res.get('buildings', 0), res.get('props', 0),
                     res.get('props_per_building', 0), res.get('loaded_frac', 0),
                     res.get('gate_points', 0), res.get('gates_without_road_24', 0), res.get('road_end_stubs', 0),
                     res.get('road_ends', 0), res.get('props_in_buildings', 0), res.get('props_on_street', 0),
                     res.get('props_floating', 0), verdict(res)))
    ok = [r for r in allres if not r.get('note')]
    tot = lambda k: sum(r.get(k, 0) or 0 for r in ok)  # noqa: E731
    print('ACCESS exterior_doors=%d unreachable=%d stair_runs=%d blocked_ends=%d' % (
        sum(r.get('exterior_doors', 0) for r in allres), sum(r.get('doors_unreachable', 0) for r in allres),
        sum(r.get('stair_runs', 0) for r in allres), sum(r.get('stairs_blocked', 0) for r in allres)))
    print('TOTAL towns=%d gate_points=%d gates_without_road_24=%d stubs=%d props_on_road=%d props_on_street=%d '
          'props_in_buildings=%d' % (len(ok), tot('gate_points'), tot('gates_without_road_24'), tot('road_end_stubs'),
                                     tot('props_on_road'), tot('props_on_street'), tot('props_in_buildings')))
    if ok:
        for k, lab in ((0, '0-50 m'), (1, '50-100 m'), (2, '100-150 m'), (3, '150-200 m')):
            rows = [r['outer_features'][k] for r in ok if r.get('outer_features')]
            if rows:
                print('OUTSIDE %-10s beyond the built radius: %.3f road features / 1000 m2 (mean), dressing %d, all %d' % (
                    lab, sum(x['per1000'] for x in rows) / len(rows), sum(x['dressing'] for x in rows),
                    sum(x['features'] for x in rows)))
        print('towns with no road feature within 100 m of the wall: %d of %d' % (
            sum(1 for r in ok if r.get('outer_features_100', 0) == 0), len(ok)))
    if a.floating:
        for nm in a.floating.split(','):
            pl = next((x for x in roads.settlements.values() if x['place'] == nm), None)
            if pl is None:
                print('FLOATING %s: not in road data' % nm)
                continue
            f = floating_vegetation(world, pl['x'], pl['z'])
            print('FLOATING %-12s within 150 blocks: components=%d log_blocks=%d leaf_blocks=%d %s' % (
                nm, f['components'], f['logs'], f['leaves'], f['samples'][:3]))
    if a.json:
        json.dump(allres, open(a.json, 'w'), indent=1, default=str)


if __name__ == '__main__':
    main()
