"""Lint and render the baked road plan (pipeline P1, com.skycraft.worldplan.RoadPlan).

usage: py -3 -I tools/qa/roadplan_lint.py [roadplan.bin] [--render] [--screens]
       default file: mod/src/main/resources/skycraft/baked/roadplan.bin (written by tools/bake/bake.ps1)

Checks (exit code 1 on any ERR):
  profile   every planned link has segments whose vertical profile keeps to its class's grade cap
            (main roads 1:8, tracks 1:5, footpaths 1:1 as stairs); failed links are listed
  water     every vertex over water is on a bridge, culvert or ford span; bridge and culvert decks clear the water
  overlap   no two segments run within their joint half widths of each other except near their ends (junctions)
  spans     every vertex belongs to exactly one span; spans tile each segment
--render   map view of the whole plan + crops to tools/qa/out/roadplan/ (classes by colour, spans marked)
--screens  also writes small JPGs to docs/screens/worldplan/
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
DEFAULT = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'baked', 'roadplan.bin')
SPANS = ['normal', 'cut', 'fill', 'bridge', 'culvert', 'ford', 'switchback', 'stairs', 'tunnel']
CLASSES = ['highway', 'road', 'lane', 'trail']
CAPS = [1 / 8, 1 / 8, 1 / 5, 1.0]
CLEARANCE = [3, 3, 2, 2]
BRIDGE, CULVERT, FORD = 3, 4, 5
MAGIC, VERSION = 0x52504c4e, 1


class Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, fmt):
        v = struct.unpack_from('>' + fmt, self.d, self.p)
        self.p += struct.calcsize('>' + fmt)
        return v if len(v) > 1 else v[0]

    def utf(self):
        n = self.take('H')
        s = self.d[self.p:self.p + n].decode('utf-8', 'replace')
        self.p += n
        return s

    def arr(self, fmt, n):
        v = struct.unpack_from('>%d%s' % (n, fmt), self.d, self.p)
        self.p += struct.calcsize('>%d%s' % (n, fmt))
        return v


def load(path):
    raw = open(path, 'rb').read()
    n = struct.unpack_from('>H', raw, 0)[0]
    inputs = raw[2:2 + n].decode()
    r = Reader(zlib.decompress(raw[2 + n:]))
    if r.take('i') != MAGIC:
        raise SystemExit('not a road plan: ' + path)
    v = r.take('i')
    if v != VERSION:
        raise SystemExit('road plan version %d, this lint reads %d' % (v, VERSION))
    plan = {'inputs': inputs, 'sites': [], 'links': [], 'segs': []}
    for _ in range(r.take('i')):
        sid = r.utf()
        t, x, z, rad, pad = r.take('b'), r.take('i'), r.take('i'), r.take('h'), r.take('h') / 16
        plan['sites'].append((sid, t, x, z, rad, pad))
    for _ in range(r.take('i')):
        a, b, name = r.utf(), r.utf(), r.utf()
        kind, status, bridge = r.take('b'), r.take('b'), r.take('h')
        plan['links'].append({'a': a, 'b': b, 'name': name, 'kind': kind, 'status': status, 'bridge': bridge, 'segs': []})
    for si in range(r.take('i')):
        link, kind, hw4, ja, jb, n = r.take('i'), r.take('b'), r.take('B'), r.take('i'), r.take('i'), r.take('i')
        xs, zs = [], []
        qx, qz = r.take('i'), r.take('i')
        xs.append(qx / 16)
        zs.append(qz / 16)
        d = r.arr('h', 2 * (n - 1))
        for i in range(n - 1):
            qx += d[2 * i]
            qz += d[2 * i + 1]
            xs.append(qx / 16)
            zs.append(qz / 16)
        h = [v / 16 for v in r.arr('h', n)]
        g = [v / 16 for v in r.arr('h', n)]
        w = [None if v == -32768 else v / 16 for v in r.arr('h', n)]
        sp = list(r.arr('b', n))
        spans = []
        for _ in range(r.take('h')):
            t, f, to, deck, npier = r.take('b'), r.take('i'), r.take('i'), r.take('h'), r.take('h')
            piers = [v / 16 for v in r.arr('h', npier)]
            spans.append((t, f, to, None if deck == -32768 else deck / 16, piers))
        seg = {'id': si, 'link': link, 'kind': kind, 'hw': hw4 / 4, 'ja': ja, 'jb': jb, 'x': xs, 'z': zs, 'h': h, 'g': g,
               'w': w, 'span': sp, 'spans': spans}
        arc = [0.0]
        for i in range(1, n):
            arc.append(arc[-1] + math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]))
        seg['arc'] = arc
        plan['segs'].append(seg)
        plan['links'][link]['segs'].append(si)
    return plan


def lint(plan):
    errs, warns, out = [], [], []
    links, segs = plan['links'], plan['segs']
    # ---- profile within grade caps
    over = [0] * 4
    worst = [0.0] * 4
    for s in segs:
        cap = CAPS[s['kind']]
        for i in range(len(s['x']) - 1):
            ds = s['arc'][i + 1] - s['arc'][i]
            if ds < 1e-6:
                continue
            gr = abs(s['h'][i + 1] - s['h'][i]) / ds
            tol = cap + (1 / 16 + cap * 0.1 + 0.01) / ds  # heights and positions are stored in 1/16 blocks
            if gr > tol:
                over[s['kind']] += 1
            worst[s['kind']] = max(worst[s['kind']], gr)
    failed = [l for l in links if l['status'] == 1]
    merged = [l for l in links if l['status'] == 2]
    noprofile = [l for l in links if l['status'] == 0 and not l['segs']]
    for k in range(4):
        out.append('profile  %-7s %5d links, %5d segments, %7.1f km, worst grade %.3f (cap %.3f), %d edges over cap'
                   % (CLASSES[k], sum(1 for l in links if l['kind'] == k), sum(1 for s in segs if s['kind'] == k),
                      sum(s['arc'][-1] for s in segs if s['kind'] == k) / 1000, worst[k], CAPS[k], over[k]))
        if over[k]:
            errs.append('ERR profile: %d %s edges steeper than the cap' % (over[k], CLASSES[k]))
    if noprofile:
        errs.append('ERR profile: %d links without a profile: %s' % (len(noprofile), ', '.join(l['a'] + '-' + l['b'] for l in noprofile[:5])))
    main_failed = [l for l in failed if l['kind'] <= 2 and not l['b'].startswith('@')]
    out.append('links    %d total, %d failed (%d graph links), %d merged into other roads'
               % (len(links), len(failed), len(main_failed), len(merged)))
    for l in main_failed[:8]:
        warns.append('WARN link failed: %s %s -> %s' % (CLASSES[l['kind']], l['a'], l['b']))
    # ---- water crossings
    wet = unspanned = low = 0
    crossings = {BRIDGE: 0, CULVERT: 0, FORD: 0}
    for s in segs:
        n = len(s['x'])
        for i in range(n):
            if s['w'][i] is None:
                continue
            wet += 1
            t = s['span'][i]
            if t not in crossings:
                unspanned += 1
                continue
            need = 0 if t == FORD else (2 if t == CULVERT else CLEARANCE[s['kind']])
            if t != FORD and s['h'][i] < s['w'][i] + need - 0.07:
                low += 1
        for t, f, to, deck, piers in s['spans']:
            if t in crossings and any(s['w'][k] is not None for k in range(f, to + 1)):
                crossings[t] += 1
    out.append('water    %d wet vertices; crossings: %d bridges, %d culverts, %d fords; %d wet vertices without a span, %d below clearance'
               % (wet, crossings[BRIDGE], crossings[CULVERT], crossings[FORD], unspanned, low))
    if unspanned:
        errs.append('ERR water: %d wet vertices are not on a bridge/culvert/ford' % unspanned)
    if low:
        errs.append('ERR water: %d bridge/culvert vertices below water + clearance' % low)
    # ---- spans tile segments
    bad_tiling = 0
    counts = [0] * len(SPANS)
    gap_bridges = 0
    for s in segs:
        base = 0  # span vertex indices are local to their segment in the file
        expect = base
        for t, f, to, deck, piers in s['spans']:
            if f != expect or to < f or any(s['span'][k - base] != t for k in range(f, to + 1)):
                bad_tiling += 1
            expect = to + 1
            counts[t] += 1
            if t == BRIDGE and all(s['w'][k - base] is None for k in range(f, to + 1)):
                gap_bridges += 1
        if expect != base + len(s['x']):
            bad_tiling += 1
    out.append('spans    ' + ', '.join('%d %s' % (counts[k], SPANS[k]) for k in range(len(SPANS))) +
               ' (%d bridges over dry gaps)' % gap_bridges)
    if bad_tiling:
        errs.append('ERR spans: %d segments whose spans do not tile them' % bad_tiling)
    # ---- overlaps
    ov, pairs = overlaps(segs)
    out.append('overlap  %d vertices within joint half widths of another road away from junctions (%d road pairs)' % (ov, len(pairs)))
    if ov:
        errs.append('ERR overlap: %d vertices on %d road pairs overlap another road outside junctions' % (ov, len(pairs)))
    return out, warns, errs



JUNCTION = 14.0
B = 32


def overlaps(segs):
    grid = {}
    for s in segs:
        for i in range(len(s['x']) - 1):
            x0, x1 = sorted((s['x'][i], s['x'][i + 1]))
            z0, z1 = sorted((s['z'][i], s['z'][i + 1]))
            for bx in range(int(math.floor(x0)) // B, int(math.floor(x1)) // B + 1):
                for bz in range(int(math.floor(z0)) // B, int(math.floor(z1)) // B + 1):
                    grid.setdefault((bx, bz), []).append((s['id'], i))
    count = 0
    pairs = set()
    for s in segs:
        n = len(s['x'])
        L = s['arc'][-1]
        for i in range(n):
            a = s['arc'][i]
            if a < JUNCTION + s['hw'] or L - a < JUNCTION + s['hw']:
                continue
            x, z = s['x'][i], s['z'][i]
            hit = False
            for bx in range(int(math.floor(x - 6)) // B, int(math.floor(x + 6)) // B + 1):
                for bz in range(int(math.floor(z - 6)) // B, int(math.floor(z + 6)) // B + 1):
                    for (oid, k) in grid.get((bx, bz), ()):
                        if oid == s['id']:
                            continue
                        o = segs[oid]
                        ax, az, ex, ez = o['x'][k], o['z'][k], o['x'][k + 1], o['z'][k + 1]
                        dx, dz = ex - ax, ez - az
                        l2 = dx * dx + dz * dz
                        t = 0 if l2 < 1e-9 else max(0, min(1, ((x - ax) * dx + (z - az) * dz) / l2))
                        d = math.hypot(ax + dx * t - x, az + dz * t - z)
                        if d >= s['hw'] + o['hw']:
                            continue
                        oa = o['arc'][k] + (o['arc'][k + 1] - o['arc'][k]) * t
                        if oa < JUNCTION + o['hw'] or o['arc'][-1] - oa < JUNCTION + o['hw']:
                            continue  # the other road's junction zone
                        hit = True
                        pairs.add((min(s['id'], oid), max(s['id'], oid)))
                        break
                    if hit:
                        break
                if hit:
                    break
            if hit:
                count += 1
    return count, pairs


# ---------------------------------------------------------------------- rendering

MIN_X, MIN_Z, CELL, W_CELLS, H_CELLS = -15360, -5120, 32, 880, 672
CLASS_RGB = [(255, 210, 60), (240, 240, 240), (170, 120, 70), (120, 220, 120)]
SPAN_RGB = {3: (230, 40, 40), 4: (60, 140, 255), 5: (40, 230, 230), 6: (230, 60, 230), 7: (255, 150, 30), 8: (0, 0, 0)}


def backdrop(x0, z0, x1, z1, scale):
    from PIL import Image
    import numpy as np
    w, h = int((x1 - x0) / scale), int((z1 - z0) / scale)
    tam = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel')
    try:  # the map rasters as MapRaster reads them: elevation y = v/32 - 64, coast distance = v - 32768
        el = np.array(Image.open(os.path.join(tam, 'elevation.png')), dtype=np.float32) / 32 - 64
        coast = np.array(Image.open(os.path.join(tam, 'coast.png')), dtype=np.float32) - 32768
        feat = np.array(Image.open(os.path.join(tam, 'features.png')).convert('RGBA'), dtype=np.float32)
        wat = np.array(Image.open(os.path.join(tam, 'water.png')).convert('RGBA'), dtype=np.float32)
    except Exception:
        return Image.new('RGB', (w, h), (40, 40, 40))
    xs = (x0 + (np.arange(w) + 0.5) * scale - MIN_X) / CELL
    zs = (z0 + (np.arange(h) + 0.5) * scale - MIN_Z) / CELL
    xi = np.clip(xs.astype(int), 0, W_CELLS - 1)
    zi = np.clip(zs.astype(int), 0, H_CELLS - 1)
    e = el[np.ix_(zi, xi)]
    v = np.clip((e - 63) / 260.0, 0, 1) ** 0.7
    g = (55 + 150 * v).astype(np.uint8)
    rgb = np.stack([(g * 0.9).astype(np.uint8), g, (g * 0.8).astype(np.uint8)], axis=-1)
    river_d = (255 - feat[..., 0]) * 0.5
    lake = (feat[..., 2] - 128) * 0.5 > 0
    river = (river_d < np.maximum(wat[..., 2], 1)) & ((wat[..., 0] + wat[..., 1]) > 0)
    rgb[(lake | river)[np.ix_(zi, xi)]] = (50, 90, 160)
    rgb[(coast < 0)[np.ix_(zi, xi)]] = (25, 40, 75)
    return Image.fromarray(rgb, 'RGB')


def render(plan, path, box, scale, labels=True, marks=True):
    from PIL import ImageDraw
    x0, z0, x1, z1 = box
    im = backdrop(x0, z0, x1, z1, scale)
    d = ImageDraw.Draw(im)

    def P(x, z):
        return ((x - x0) / scale, (z - z0) / scale)

    lw = max(1, int(round(3 / max(scale, 1))))
    for k in (3, 2, 1, 0):
        for s in plan['segs']:
            if s['kind'] != k:
                continue
            if max(s['x']) < x0 or min(s['x']) > x1 or max(s['z']) < z0 or min(s['z']) > z1:
                continue
            pts = [P(s['x'][i], s['z'][i]) for i in range(len(s['x']))]
            d.line(pts, fill=CLASS_RGB[k], width=lw + (2 if k == 0 else 1 if k == 1 else 0))
    if marks:
        r = max(1.5, 3 / scale ** 0.5)
        for s in plan['segs']:
            base = 0  # span vertex indices are local to their segment in the file
            for t, f, to, deck, piers in s['spans']:
                if t not in SPAN_RGB:
                    continue
                m = (f + to) // 2 - base
                x, z = s['x'][m], s['z'][m]
                if not (x0 <= x <= x1 and z0 <= z <= z1):
                    continue
                if scale <= 2:
                    d.line([P(s['x'][i - base], s['z'][i - base]) for i in range(f, to + 1)] or [P(x, z)], fill=SPAN_RGB[t], width=lw + 2)
                else:
                    px, pz = P(x, z)
                    d.ellipse([px - r, pz - r, px + r, pz + r], fill=SPAN_RGB[t])
    if labels:
        for sid, t, x, z, rad, pad in plan['sites']:
            if t > 1 or not (x0 <= x <= x1 and z0 <= z <= z1):
                continue
            px, pz = P(x, z)
            d.ellipse([px - 3, pz - 3, px + 3, pz + 3], outline=(255, 80, 80))
            d.text((px + 5, pz - 6), sid, fill=(255, 255, 255))
    # legend
    y = 6
    for k in range(4):
        d.rectangle([6, y, 22, y + 8], fill=CLASS_RGB[k])
        d.text((26, y - 2), CLASSES[k], fill=(255, 255, 255))
        y += 14
    for t, c in SPAN_RGB.items():
        d.ellipse([10, y, 18, y + 8], fill=c)
        d.text((26, y - 2), SPANS[t], fill=(255, 255, 255))
        y += 14
    im.save(path, quality=88) if path.endswith('.jpg') else im.save(path)
    return path


def site(plan, sid):
    for s in plan['sites']:
        if s[0] == sid:
            return s[2], s[3]
    return None


def main(argv):
    path = DEFAULT
    args = [a for a in argv if not a.startswith('--')]
    if args:
        path = args[0]
    plan = load(path)
    out, warns, errs = lint(plan)
    for line in out:
        print(line)
    for line in warns:
        print(line)
    for line in errs:
        print(line)
    if '--render' in argv or '--screens' in argv:
        od = os.path.join(ROOT, 'tools', 'qa', 'out', 'roadplan')
        os.makedirs(od, exist_ok=True)
        xs = [x for s in plan['segs'] for x in s['x']] or [0]
        zs = [z for s in plan['segs'] for z in s['z']] or [0]
        bx = (max(MIN_X, min(xs)) - 200, max(MIN_Z, min(zs)) - 200,
              min(MIN_X + W_CELLS * CELL, max(xs)) + 200, min(MIN_Z + H_CELLS * CELL, max(zs)) + 200)
        made = [render(plan, os.path.join(od, 'roadplan_tamriel.png'), bx, 12.0, labels=False)]
        wr = site(plan, 'whiterun') or (0, 0)
        made.append(render(plan, os.path.join(od, 'roadplan_skyrim.png'), (-3300, -2600, 2900, 2000), 4.0))
        made.append(render(plan, os.path.join(od, 'roadplan_whiterun.png'), (wr[0] - 1400, wr[1] - 1000, wr[0] + 1400, wr[1] + 1200), 1.5))
        rw = site(plan, 'riverwood') or (wr[0], wr[1] + 900)
        made.append(render(plan, os.path.join(od, 'roadplan_riverwood.png'), (rw[0] - 350, rw[1] - 350, rw[0] + 350, rw[1] + 350), 0.6))
        print('rendered: ' + ', '.join(os.path.relpath(m, ROOT) for m in made))
        if '--screens' in argv:
            from PIL import Image
            sd = os.path.join(ROOT, 'docs', 'screens', 'worldplan')
            os.makedirs(sd, exist_ok=True)
            for src, name in ((made[1], 'roadplan_skyrim.jpg'), (made[2], 'roadplan_whiterun.jpg'), (made[3], 'roadplan_riverwood.jpg')):
                im = Image.open(src).convert('RGB')
                im.thumbnail((1100, 1100))
                im.save(os.path.join(sd, name), quality=82)
            print('screens: docs/screens/worldplan/roadplan_{skyrim,whiterun,riverwood}.jpg')
    print('roadplan_lint: %d errors, %d warnings' % (len(errs), len(warns)))
    return 1 if errs else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
